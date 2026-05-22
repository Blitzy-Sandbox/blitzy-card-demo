/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.service;

import com.aws.carddemo.dto.auth.AuthenticationRequest;
import com.aws.carddemo.dto.auth.AuthenticationResult;
import com.aws.carddemo.dto.auth.UserSession;
import com.aws.carddemo.entity.SecurityUser;
import com.aws.carddemo.repository.UserSecurityRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Authentication service — the Java migration of the CICS sign-on program
 * {@code app/cbl/COSGN00C.cbl} (TRANID {@code CC00}). Replaces the COBOL plaintext
 * password comparison with BCrypt verification per AAP §0.10.5.
 *
 * <h2>COBOL Provenance — COSGN00C.cbl</h2>
 *
 * <p>The original {@code PROCESS-ENTER-KEY} (lines ~108–140) and
 * {@code READ-USER-SEC-FILE} (lines ~209–257) paragraphs together encode the
 * sign-on workflow:
 *
 * <ol>
 *   <li>{@code IF USERIDI = SPACES OR LOW-VALUES} →
 *       {@code 'Please enter User ID ...'}</li>
 *   <li>{@code IF PASSWDI = SPACES OR LOW-VALUES} →
 *       {@code 'Please enter Password ...'}</li>
 *   <li>{@code EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)}
 *     <ul>
 *       <li>{@code WS-RESP-CD = 13} (NOTFND) →
 *           {@code 'User not found. Try again ...'}</li>
 *       <li>{@code WS-RESP-CD = WHEN OTHER} →
 *           {@code 'Unable to verify the User ...'}</li>
 *     </ul>
 *   </li>
 *   <li>{@code IF SEC-USR-PWD = WS-USER-PWD} (Java: BCrypt.matches)
 *     <ul>
 *       <li>mismatch → {@code 'Wrong Password. Try again ...'}</li>
 *     </ul>
 *   </li>
 *   <li>{@code IF CDEMO-USRTYP-ADMIN} → {@code XCTL COADM01C} (admin menu)
 *       else → {@code XCTL COMEN01C} (main menu)</li>
 * </ol>
 *
 * <h2>Java Migration Changes</h2>
 *
 * <ul>
 *   <li><b>BCrypt verification</b> — plaintext comparison is replaced by
 *       {@code passwordEncoder.matches(plaintext, hash)} per AAP §0.10.5. The
 *       stored {@code SEC-USR-PWD} is now a 60-character BCrypt hash, not the
 *       original 8-character plaintext.</li>
 *   <li><b>Account locking</b> — a Java-migration addition (industry-standard
 *       hardening absent from the COBOL source). Locked accounts are rejected
 *       BEFORE attempting BCrypt verification, preventing CPU exhaustion via
 *       repeated attempts.</li>
 *   <li><b>Invalid user type guard</b> — the COBOL workflow falls through to the
 *       regular-user dispatch when {@code SEC-USR-TYPE} is neither {@code 'A'}
 *       nor {@code 'U'}; the Java migration adds an explicit guard that rejects
 *       authentication for unknown user types rather than silently routing as
 *       regular user.</li>
 *   <li><b>Clock injection</b> — the session login time is stamped from an
 *       injected {@link Clock} so tests can deterministically verify
 *       {@link UserSession#getLoginTime()} (AAP §0.4.2 Blueprint A).</li>
 * </ul>
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype annotation;
 * subsequent migration agents will add it when the full Spring application
 * context is wired up. For now, the constructor accepts collaborators directly
 * so unit tests can wire mocks without a Spring context.
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>The service contains the COMPLETE business logic for the sign-on workflow
 * (no helper methods are extracted; all branches are visible in the single
 * {@link #authenticate(AuthenticationRequest)} method). The corresponding
 * {@code AuthenticationServiceTest} exercises every branch via real method calls
 * — no business logic is duplicated in the test.
 *
 * @see AuthenticationRequest
 * @see AuthenticationResult
 * @see UserSession
 * @see SecurityUser
 * @see UserSecurityRepository
 */
@Service
public class AuthenticationService {

    /** COBOL message: {@code 'Please enter User ID ...'} */
    static final String MSG_EMPTY_USER_ID = "Please enter User ID ...";

    /** COBOL message: {@code 'Please enter Password ...'} */
    static final String MSG_EMPTY_PASSWORD = "Please enter Password ...";

    /** COBOL message for {@code WS-RESP-CD = 13} (NOTFND). */
    static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /** COBOL message for {@code SEC-USR-PWD ≠ WS-USER-PWD} (BCrypt mismatch in Java). */
    static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** Java-migration addition: account-lockout reject message. */
    static final String MSG_ACCOUNT_LOCKED = "Account is locked. Contact administrator ...";

    /** Java-migration addition: invalid-user-type reject message. */
    static final String MSG_INVALID_USER_TYPE = "User type not valid ...";

    /** SEC-USR-TYPE value for admin users — drives {@code XCTL COADM01C} (Java: ADMIN_MENU route). */
    static final String USER_TYPE_ADMIN = "A";

    /** SEC-USR-TYPE value for regular users — drives {@code XCTL COMEN01C} (Java: MAIN_MENU route). */
    static final String USER_TYPE_REGULAR = "U";

    /** Next-route hint for admin users — controller layer interprets this to dispatch the admin menu. */
    static final String ROUTE_ADMIN_MENU = "ADMIN_MENU";

    /** Next-route hint for regular users — controller layer interprets this to dispatch the main menu. */
    static final String ROUTE_MAIN_MENU = "MAIN_MENU";

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Constructs a new {@code AuthenticationService}.
     *
     * @param userSecurityRepository JPA repository for {@link SecurityUser} lookups;
     *                               the Java replacement for COBOL
     *                               {@code EXEC CICS READ DATASET('USRSEC')}
     * @param passwordEncoder        Spring Security {@link PasswordEncoder} used for
     *                               BCrypt verification; must be a real implementation
     *                               (production wires {@link
     *                               org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder})
     * @param clock                  {@link Clock} used to stamp the session login
     *                               time; injectable so tests can replace with
     *                               {@link Clock#fixed(java.time.Instant, java.time.ZoneId)}
     *                               for deterministic timestamps
     */
    public AuthenticationService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Authenticates a user by verifying the supplied plaintext password against the
     * stored BCrypt hash. Returns a populated {@link UserSession} on success or a
     * reject message on failure.
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li>Validate {@code request.userId()} is non-blank</li>
     *   <li>Validate {@code request.password()} is non-blank</li>
     *   <li>Look up the user via {@code userSecurityRepository.findById(userId)}</li>
     *   <li>If the user is locked, reject BEFORE attempting BCrypt verification</li>
     *   <li>Verify the password via {@code passwordEncoder.matches(plaintext, hash)}</li>
     *   <li>Dispatch by user type: {@code 'A'} → ADMIN_MENU, {@code 'U'} → MAIN_MENU,
     *       otherwise reject</li>
     *   <li>Build and return a {@link UserSession} stamped with the injected clock's instant</li>
     * </ol>
     *
     * @param request the authentication request carrying the user ID and plaintext
     *                password candidate; must not be {@code null}
     * @return an {@link AuthenticationResult} encoding success (with session) or
     *         failure (with reject message)
     */
    public AuthenticationResult authenticate(AuthenticationRequest request) {
        // Step 1 — validate user ID (COBOL: IF USERIDI = SPACES OR LOW-VALUES)
        if (isBlank(request.userId())) {
            return AuthenticationResult.failure(MSG_EMPTY_USER_ID);
        }

        // Step 2 — validate password (COBOL: IF PASSWDI = SPACES OR LOW-VALUES)
        if (isBlank(request.password())) {
            return AuthenticationResult.failure(MSG_EMPTY_PASSWORD);
        }

        // Step 3 — look up user (COBOL: EXEC CICS READ DATASET('USRSEC'))
        Optional<SecurityUser> userOpt = userSecurityRepository.findById(request.userId());
        if (userOpt.isEmpty()) {
            // COBOL: WS-RESP-CD = 13 (NOTFND)
            return AuthenticationResult.failure(MSG_USER_NOT_FOUND);
        }
        SecurityUser user = userOpt.get();

        // Step 4 — Java-migration addition: account-lockout check BEFORE BCrypt
        // (protects against CPU exhaustion via repeated attempts on locked accounts)
        if (user.isLocked()) {
            return AuthenticationResult.failure(MSG_ACCOUNT_LOCKED);
        }

        // Step 5 — verify password via BCrypt (COBOL: IF SEC-USR-PWD = WS-USER-PWD)
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            return AuthenticationResult.failure(MSG_WRONG_PASSWORD);
        }

        // Step 6 — dispatch by user type (COBOL: IF CDEMO-USRTYP-ADMIN ... ELSE ...)
        String userType = user.getUserType();
        String nextRoute;
        if (USER_TYPE_ADMIN.equals(userType)) {
            nextRoute = ROUTE_ADMIN_MENU;
        } else if (USER_TYPE_REGULAR.equals(userType)) {
            nextRoute = ROUTE_MAIN_MENU;
        } else {
            // Java-migration safety net: COBOL would silently fall through to the
            // regular-user dispatch for unknown types; the Java migration explicitly
            // rejects to prevent quiet privilege misassignment.
            return AuthenticationResult.failure(MSG_INVALID_USER_TYPE);
        }

        // Step 7 — build session stamped from the injected clock
        LocalDateTime loginTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        UserSession session = new UserSession(user.getUserId(), userType, loginTime, nextRoute);

        // Step 8 — return success with welcome message
        // (COBOL: 'Welcome <FIRST> <LAST> ...' from COSGN00C welcome-message flow)
        String welcome = String.format("Welcome %s %s ...",
                safeName(user.getFirstName()), safeName(user.getLastName()));
        return AuthenticationResult.success(welcome, session);
    }

    /**
     * Returns {@code true} iff the supplied string is {@code null}, empty, or
     * contains only whitespace. Used to validate that the user ID and password
     * fields of the request are populated.
     *
     * <p>Matches COBOL semantics: {@code IF FIELD = SPACES OR LOW-VALUES} is true
     * for any all-whitespace field, including a fully-padded space-fill field
     * (COBOL {@code PIC X(08)} = "        ").
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Returns the supplied name verbatim, or an empty string if {@code null}. Used
     * to compose the welcome message even when the user record has a missing first
     * or last name (defensive — never fails the success path on a malformed user
     * record).
     */
    private static String safeName(String s) {
        return s == null ? "" : s;
    }
}
