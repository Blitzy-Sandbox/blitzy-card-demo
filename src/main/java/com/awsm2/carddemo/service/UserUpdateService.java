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
import com.awsm2.carddemo.dto.UserUpdateDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
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
 * Admin user-update service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR02C.cbl} (CICS transaction id {@code CU02}).
 *
 * <p>This service updates an existing user row in the {@code USRSEC}
 * dataset. In the COBOL source, {@code COUSR02C.cbl} issues an
 * {@code EXEC CICS READ UPDATE} on the {@code USRSEC} VSAM KSDS,
 * applies the BMS field values to the {@code SEC-USER-DATA} record, and
 * finally issues {@code EXEC CICS REWRITE} to persist the change. The
 * Java target re-hashes the password with BCrypt (cost factor 12) when
 * the operator supplies a non-blank new password &mdash; preserving the
 * spirit of the legacy &quot;optional password change&quot; behavior
 * while applying the security upgrade mandated by AAP &sect;0.1.1.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR02C.cbl} (CICS TRANID
 *       {@code 'CU02'}, file {@code 'USRSEC'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR02.bms} (mapset
 *       {@code COUSR02}, map {@code COUSR2A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COUSR02.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; mapped to JPA entity
 *       {@link UserSecurity}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COUSR02C.cbl &harr; UserUpdateService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS} (validate the BMS fields)</td>
 *       <td>{@link #validate(UserUpdateDto)}</td></tr>
 *   <tr><td>{@code 9100-READ-USER-SEC-FILE} ({@code EXEC CICS READ
 *       UPDATE FILE('USRSEC')}); FILE STATUS 23 / NOTFND</td>
 *       <td>{@link UserSecurityRepository#findById(Object)} &rarr;
 *       {@link RecordNotFoundException} (HTTP 404)</td></tr>
 *   <tr><td>{@code MOVE BMS-FIELD TO SEC-USR-*}</td>
 *       <td>{@link #applyEdits(UserSecurity, UserUpdateDto)}</td></tr>
 *   <tr><td>{@code 9200-WRITE-USER-SEC-FILE} ({@code EXEC CICS
 *       REWRITE})</td>
 *       <td>{@link UserSecurityRepository#save(Object)}</td></tr>
 *   <tr><td>{@code SYNCPOINT} (CICS commit)</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class)}</td></tr>
 * </table>
 *
 * <h2>Optional password rotation</h2>
 *
 * <p>The COBOL source unconditionally overwrites
 * {@code SEC-USR-PWD} on update (any blank input would store blanks).
 * The Java target adopts a safer policy: when the caller supplies a
 * non-blank {@code password} component, the new value is BCrypt-hashed
 * and stored; when the caller supplies a {@code null} or blank value,
 * the existing hash is preserved. This is consistent with the AAP
 * &sect;0.1.1 security upgrade and prevents accidental password
 * erasure by admin tools that submit "partial" updates.</p>
 *
 * @see UserSecurityRepository
 * @see UserUpdateDto
 * @see PasswordEncoder
 */
@Service
public class UserUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UserUpdateService.class);

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserUpdateService(UserSecurityRepository userSecurityRepository,
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
     * Updates an existing user row.
     *
     * @param request the update request
     * @return the persisted {@link UserUpdateDto} with the password
     *         component cleared (a stored hash MUST NEVER be echoed
     *         back over the wire)
     * @throws ValidationException     if any field is malformed
     * @throws RecordNotFoundException if no user exists for the
     *                                 supplied {@code userId}
     */
    @Transactional(rollbackFor = Exception.class)
    public UserUpdateDto updateUser(UserUpdateDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        // COBOL: FUNCTION UPPER-CASE(SEC-USR-ID).
        String userId = request.userId().trim().toUpperCase(Locale.US);

        // ---- COBOL 9100-READ-USER-SEC-FILE -----------------------------
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "USER_NOT_FOUND", "User not found"));

        boolean passwordChanged = applyEdits(user, request);

        // ---- COBOL 9200-WRITE-USER-SEC-FILE (REWRITE) -----------------
        UserSecurity saved = userSecurityRepository.save(user);

        // ---- Audit -----------------------------------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", saved.getSecUsrId());
        payload.put("userType", saved.getSecUsrType());
        payload.put("passwordChanged", passwordChanged);
        auditLogService.logSecurityEvent("user.updated",
                saved.getSecUsrId(),
                "SUCCESS",
                null,
                payload,
                null);

        LOG.info("UserUpdateService.updateUser updated userId={} type={} passwordChanged={}",
                saved.getSecUsrId(), saved.getSecUsrType(), passwordChanged);

        return new UserUpdateDto(
                saved.getSecUsrId(),
                saved.getSecUsrFname(),
                saved.getSecUsrLname(),
                passwordChanged ? "(rotated)" : "(unchanged)",
                saved.getSecUsrType());
    }

    /**
     * Applies the validated DTO fields onto the loaded
     * {@link UserSecurity} entity. Returns {@code true} when the
     * password was rotated, {@code false} when the existing hash was
     * preserved.
     */
    private boolean applyEdits(UserSecurity user, UserUpdateDto request) {
        if (request.firstName() != null && !request.firstName().isBlank()) {
            user.setSecUsrFname(request.firstName().trim());
        }
        if (request.lastName() != null && !request.lastName().isBlank()) {
            user.setSecUsrLname(request.lastName().trim());
        }
        if (request.userType() != null && !request.userType().isBlank()) {
            user.setSecUsrType(request.userType().trim());
        }

        boolean rotated = false;
        if (request.password() != null && !request.password().isBlank()) {
            // BCrypt re-hash on password change (AAP §0.1.1 / §0.4.1
            // — UserUpdateService.java "re-hash on password change").
            user.setSecUsrPwd(passwordEncoder.encode(request.password()));
            rotated = true;
        }
        return rotated;
    }

    private void validate(UserUpdateDto request) {
        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.userId() == null || request.userId().isBlank()) {
            errors.add(new ValidationException.FieldError(
                    "userId", "userId is required"));
        } else if (request.userId().length() > 8) {
            errors.add(new ValidationException.FieldError(
                    "userId", "userId must be at most 8 characters"));
        }

        if (request.userType() != null
                && !request.userType().isBlank()
                && !"A".equals(request.userType())
                && !"U".equals(request.userType())) {
            errors.add(new ValidationException.FieldError(
                    "userType", "userType must be 'A' (admin) or 'U' (user)"));
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(
                    "VALIDATION_FAILED",
                    "User update request contains invalid fields",
                    errors);
        }
    }
}
