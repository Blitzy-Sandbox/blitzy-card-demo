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
package com.carddemo.service;

import com.carddemo.dto.AuthDto;
import com.carddemo.entity.User;
import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Sign-on credential verification and JSON Web Token issuance for the
 * authentication endpoint {@code POST /api/auth/signin}.
 *
 * <p>Replaces the legacy CICS sign-on program {@code COSGN00C} (transaction
 * {@code CC00}, copybooks {@code CSUSR01Y} and {@code COCOM01Y}) at source
 * commit {@code 27d6c6f}. The pseudo-conversational {@code PROCESS-ENTER-KEY}
 * and {@code READ-USER-SEC-FILE} paragraphs are realized as a single stateless
 * {@link #signin(AuthDto.SigninRequest)} operation:</p>
 *
 * <ul>
 *   <li>The blank-field guards of {@code PROCESS-ENTER-KEY} become
 *       {@link ValidationException}s that carry the offending field name.</li>
 *   <li>The {@code FUNCTION UPPER-CASE} normalization of the user id and
 *       password is preserved; the user id is additionally trimmed.</li>
 *   <li>The keyed {@code USRSEC} read and its {@code EVALUATE WS-RESP-CD}
 *       outcomes (found, not found, other) become a repository
 *       {@code findById} lookup whose results map to success or an
 *       {@link AuthenticationFailedException}.</li>
 *   <li>The plaintext {@code SEC-USR-PWD} comparison is upgraded to BCrypt
 *       verification through the injected {@link PasswordEncoder}
 *       (constraint C-003).</li>
 *   <li>The COMMAREA hand-off and {@code XCTL} routing to the administrator or
 *       main menu become a signed JWT plus the returned user-type code; the
 *       subsequent menu selection is the client's concern.</li>
 * </ul>
 *
 * <p>Every sign-on attempt, successful or not, is counted on the
 * {@code carddemo.auth.attempts} meter. The detail messages are reproduced
 * verbatim from the COBOL source for behavioral parity. Credentials and issued
 * tokens are never logged nor echoed in any exception message.</p>
 */
@Service
public class AuthService {

    /** Detail message when the submitted user id is blank ({@code COSGN00C} line 120). */
    private static final String MESSAGE_ENTER_USER_ID = "Please enter User ID ...";

    /** Detail message when the submitted password is blank ({@code COSGN00C} line 125). */
    private static final String MESSAGE_ENTER_PASSWORD = "Please enter Password ...";

    /** Detail message when no user record exists ({@code COSGN00C} line 249, {@code WS-RESP-CD} 13). */
    private static final String MESSAGE_USER_NOT_FOUND = "User not found. Try again ...";

    /** Detail message when the supplied password fails verification ({@code COSGN00C} line 242). */
    private static final String MESSAGE_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** Detail message for any other lookup failure ({@code COSGN00C} line 254, {@code WS-RESP-CD} other). */
    private static final String MESSAGE_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /** Field-error key identifying the user-id input. */
    private static final String FIELD_USER_ID = "userId";

    /** Field-error key identifying the password input. */
    private static final String FIELD_PASSWORD = "password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final Counter authAttemptsCounter;

    /**
     * Creates the sign-on service with its injected collaborators.
     *
     * @param userRepository      loads the security {@link User} record keyed by
     *                            user id (replaces the keyed {@code USRSEC} read)
     * @param passwordEncoder     the BCrypt encoder used to verify the submitted
     *                            password against the stored hash
     * @param jwtTokenService     issues the signed JWT carrying the authenticated
     *                            identity and user type
     * @param authAttemptsCounter the {@code carddemo.auth.attempts} meter,
     *                            incremented once per sign-on attempt
     */
    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            @Qualifier("authAttemptsCounter") Counter authAttemptsCounter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.authAttemptsCounter = authAttemptsCounter;
    }

    /**
     * Verifies sign-on credentials and issues a JWT, reproducing the ordered
     * decision flow of the COBOL {@code COSGN00C} program.
     *
     * <p>The submitted user id and password are normalized to upper case
     * (matching the legacy {@code FUNCTION UPPER-CASE}); the user id is
     * additionally trimmed of surrounding whitespace. The user record is then
     * loaded by id and its stored BCrypt hash is verified against the
     * normalized password.</p>
     *
     * @param request the sign-on request carrying the user id and password
     * @return a response carrying the issued token, the authenticated user id,
     *         and the single-character user-type code ({@code 'A'} administrator,
     *         {@code 'U'} standard user)
     * @throws ValidationException           if the user id or password is blank
     * @throws AuthenticationFailedException if no matching user exists, the
     *                                       password does not match, or the user
     *                                       record cannot be read
     */
    public AuthDto.SigninResponse signin(AuthDto.SigninRequest request) {
        authAttemptsCounter.increment();

        String submittedUserId = request.userId();
        if (submittedUserId == null || submittedUserId.isBlank()) {
            throw new ValidationException(MESSAGE_ENTER_USER_ID, Map.of(FIELD_USER_ID, MESSAGE_ENTER_USER_ID));
        }

        String submittedPassword = request.password();
        if (submittedPassword == null || submittedPassword.isBlank()) {
            throw new ValidationException(MESSAGE_ENTER_PASSWORD, Map.of(FIELD_PASSWORD, MESSAGE_ENTER_PASSWORD));
        }

        String userId = submittedUserId.trim().toUpperCase(Locale.ROOT);
        String password = submittedPassword.toUpperCase(Locale.ROOT);

        User user = findUser(userId);

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new AuthenticationFailedException(MESSAGE_WRONG_PASSWORD);
        }

        String userType = user.getUserType();
        String token = jwtTokenService.generateToken(userId, userType);
        return new AuthDto.SigninResponse(token, userId, userType);
    }

    /**
     * Loads the security user record for the normalized user id, translating the
     * {@code EVALUATE WS-RESP-CD} outcomes of {@code READ-USER-SEC-FILE} into
     * their Java equivalents.
     *
     * @param userId the normalized (trimmed, upper-cased) user id
     * @return the matching {@link User} record
     * @throws AuthenticationFailedException if no record exists for the id
     *                                       (response code 13) or the lookup
     *                                       fails for any other reason
     */
    private User findUser(String userId) {
        Optional<User> found;
        try {
            found = userRepository.findById(userId);
        } catch (DataAccessException ex) {
            throw new AuthenticationFailedException(MESSAGE_UNABLE_TO_VERIFY, ex);
        }
        return found.orElseThrow(() -> new AuthenticationFailedException(MESSAGE_USER_NOT_FOUND));
    }
}
