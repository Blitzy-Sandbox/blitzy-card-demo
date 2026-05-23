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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.domain.UserSecurity;
import com.awsm2.carddemo.dto.UserAddDto;
import com.awsm2.carddemo.exception.DuplicateRecordException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Admin user-add service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR01C.cbl} (CICS transaction id {@code CU01}).
 *
 * <p>This service creates a new user row in the {@code USRSEC} dataset.
 * In the COBOL source, {@code COUSR01C.cbl} validates the inbound BMS
 * fields, issues an {@code EXEC CICS READ} on {@code USRSEC} to detect a
 * pre-existing user (FILE STATUS 22 / {@code DFHRESP(DUPKEY)}), then
 * issues an {@code EXEC CICS WRITE} to create the row. The COBOL source
 * stores the password in plaintext (an 8-character {@code SEC-USR-PWD
 * PIC X(08)} field per {@code app/cpy/CSUSR01Y.cpy}). The Java target
 * <b>upgrades</b> password storage to BCrypt (cost factor 12) per AAP
 * &sect;0.1.1 (a deliberate security improvement explicitly permitted
 * by the AAP within the scope of PCI-DSS compliance).</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR01C.cbl} (CICS TRANID
 *       {@code 'CU01'}, file {@code 'USRSEC'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR01.bms} (mapset
 *       {@code COUSR01}, map {@code COUSR1A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COUSR01.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA entity
 *       {@link UserSecurity}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COUSR01C.cbl &harr; UserAddService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS}</td>
 *       <td>{@link #validate(UserAddDto)}</td></tr>
 *   <tr><td>{@code 9100-READ-USER-SEC-FILE} (detect existing user) &mdash;
 *       FILE STATUS 22 / {@code DFHRESP(DUPKEY)}</td>
 *       <td>{@link UserSecurityRepository#existsById(Object)} &rarr;
 *       {@link DuplicateRecordException} (HTTP 409)</td></tr>
 *   <tr><td>{@code 9200-WRITE-USER-SEC-FILE}</td>
 *       <td>{@link UserSecurityRepository#save(Object)} after BCrypt
 *       hashing</td></tr>
 *   <tr><td>{@code SYNCPOINT} (CICS commit)</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class)}</td></tr>
 * </table>
 *
 * <h2>Security upgrade: plaintext &rarr; BCrypt</h2>
 *
 * <p>The COBOL source stores the password verbatim. This service hashes
 * the password with {@link PasswordEncoder#encode} before persisting
 * &mdash; the stored value is the BCrypt hash, never the plaintext.
 * This is a permitted security upgrade per AAP &sect;0.1.1 "Plaintext
 * password storage in the source USRSEC file must be upgraded to BCrypt
 * hashing in the Java target." Verification flows (e.g.,
 * {@code SignonService}) call {@link PasswordEncoder#matches} which
 * compares the user-supplied plaintext against the stored hash.</p>
 *
 * <h2>User-ID normalization</h2>
 *
 * <p>The COBOL source's BMS map declares {@code USERID PIC X(08)} as a
 * fixed-length uppercase field. The Java target normalizes the
 * supplied {@code userId} to uppercase using {@link Locale#US} before
 * the duplicate-check and save &mdash; matching the COBOL
 * {@code FUNCTION UPPER-CASE} behavior. This is consistent with
 * {@code SignonService}'s normalization and ensures any
 * subsequent sign-on attempt matches the same stored key.</p>
 *
 * @see UserSecurityRepository
 * @see UserAddDto
 * @see PasswordEncoder
 */
@Service
public class UserAddService {

    private static final Logger LOG = LoggerFactory.getLogger(UserAddService.class);

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserAddService(UserSecurityRepository userSecurityRepository,
                          PasswordEncoder passwordEncoder,
                          AuditLogService auditLogService) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
                "passwordEncoder");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    /**
     * Adds a new user to the {@code user_security} table.
     *
     * @param request the validated add request
     * @return the persisted {@link UserAddDto} with the password
     *         component cleared (a stored hash MUST NEVER be echoed
     *         back over the wire)
     * @throws ValidationException        if any required field is
     *                                    missing or malformed
     * @throws DuplicateRecordException   if a user with the same
     *                                    normalized {@code userId}
     *                                    already exists (HTTP 409 via
     *                                    {@code GlobalExceptionHandler})
     */
    @Transactional(rollbackFor = Exception.class)
    public UserAddDto addUser(UserAddDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        // COBOL: FUNCTION UPPER-CASE(SEC-USR-ID) — normalize to upper
        // before duplicate-check and save so that case-insensitive
        // lookups by subsequent signon attempts succeed.
        String userId = request.userId().trim().toUpperCase(Locale.US);

        // ---- COBOL 9100-READ-USER-SEC-FILE — DUPKEY detection ---------
        if (userSecurityRepository.existsById(userId)) {
            throw new DuplicateRecordException(
                    "USER_ALREADY_EXISTS",
                    "A user with id '" + userId + "' already exists");
        }

        // ---- BCrypt hashing (security upgrade per AAP §0.1.1) ---------
        String hash = passwordEncoder.encode(request.password());

        // ---- COBOL INITIALIZE SEC-USER-DATA + MOVE * TO SEC-USR-* ----
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(userId);
        user.setSecUsrFname(safeTrim(request.firstName()));
        user.setSecUsrLname(safeTrim(request.lastName()));
        user.setSecUsrPwd(hash);
        user.setSecUsrType(safeTrim(request.userType()));

        // ---- COBOL 9200-WRITE-USER-SEC-FILE ----------------------------
        UserSecurity saved = userSecurityRepository.save(user);

        // ---- Audit -----------------------------------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", saved.getSecUsrId());
        payload.put("userType", saved.getSecUsrType());
        auditLogService.logSecurityEvent("user.added",
                saved.getSecUsrId(),
                "SUCCESS",
                null,
                payload,
                null);

        LOG.info("UserAddService.addUser created userId={} type={}",
                saved.getSecUsrId(), saved.getSecUsrType());

        // Response MUST NOT echo the password (hash or plaintext) per
        // PCI-DSS / least-privilege handling. The DTO requires a
        // non-null password by @NotBlank; we emit a sentinel "(set)"
        // string to confirm the password was applied without exposing
        // the hashed value.
        return new UserAddDto(
                saved.getSecUsrId(),
                saved.getSecUsrFname(),
                saved.getSecUsrLname(),
                "(set)",
                saved.getSecUsrType());
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    /**
     * Field-level validation; throws {@link ValidationException}
     * carrying every error discovered.
     */
    private void validate(UserAddDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.userId() == null || request.userId().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "userId", "userId is required"));
        } else if (request.userId().length() > 8) {
            errors.add(new ValidationException.FieldError(
                    "userId", "userId must be at most 8 characters"));
        }

        if (request.firstName() == null || request.firstName().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "firstName", "firstName is required"));
        }

        if (request.lastName() == null || request.lastName().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "lastName", "lastName is required"));
        }

        if (request.password() == null || request.password().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "password", "password is required"));
        }

        if (request.userType() == null || request.userType().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "userType", "userType is required"));
        } else if (!"A".equals(request.userType()) && !"U".equals(request.userType())) {
            errors.add(new ValidationException.FieldError(
                    "userType", "userType must be 'A' (admin) or 'U' (user)"));
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "VALIDATION_FAILED",
                    "User add request contains invalid fields",
                    errors);
        }
    }

    private String safeTrim(String s) {
        return s == null ? null : s.trim();
    }
}
