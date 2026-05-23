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
import com.awsm2.carddemo.dto.UserDeleteDto;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.UserSecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Admin user-delete service &mdash; the Java target for the COBOL/CICS
 * program {@code app/cbl/COUSR03C.cbl} (CICS transaction id {@code CU03}).
 *
 * <p>This service deletes an existing user row from the {@code USRSEC}
 * dataset. In the COBOL source, {@code COUSR03C.cbl} displays the user
 * record (read-only) and prompts the operator to confirm by typing
 * {@code 'Y'} into the {@code CONFIRMI} BMS field. Upon confirmation it
 * issues {@code EXEC CICS DELETE FILE('USRSEC') RIDFLD(WS-USER-ID)}.
 * The Java target preserves both the &quot;preview&quot; and the
 * &quot;confirm-then-delete&quot; semantics via the
 * {@link UserDeleteDto#confirm()} component.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/COUSR03C.cbl} (CICS TRANID
 *       {@code 'CU03'}, file {@code 'USRSEC'}).</li>
 *   <li><b>BMS mapset:</b> {@code app/bms/COUSR03.bms} (mapset
 *       {@code COUSR03}, map {@code COUSR3A}).</li>
 *   <li><b>Symbolic map:</b> {@code app/cpy-bms/COUSR03.CPY}.</li>
 *   <li><b>Record layout:</b> {@code app/cpy/CSUSR01Y.cpy}
 *       ({@code SEC-USER-DATA}, 80 bytes) &mdash; {@link UserSecurity}.</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>COUSR03C.cbl &harr; UserDeleteService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code 1000-PROCESS-INPUTS} (validate userId)</td>
 *       <td>{@link #validate(UserDeleteDto)}</td></tr>
 *   <tr><td>{@code 9000-READ-USER-SEC-FILE}</td>
 *       <td>{@link UserSecurityRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code IF CONFIRMI = 'Y'} (confirmation gate)</td>
 *       <td>{@link #payload}{@code .confirm().equalsIgnoreCase("Y")}</td></tr>
 *   <tr><td>{@code 9300-DELETE-USER-SEC-FILE}</td>
 *       <td>{@link UserSecurityRepository#delete(Object)}</td></tr>
 *   <tr><td>{@code SYNCPOINT}</td>
 *       <td>{@link Transactional @Transactional(rollbackFor =
 *       Exception.class)}</td></tr>
 * </table>
 *
 * <h2>Confirmation-then-delete semantics</h2>
 *
 * <p>The COBOL source presents the operator with the user record and
 * requires an explicit &quot;Y&quot; confirmation before deletion.
 * Callers of this Java service have two options:</p>
 * <ol>
 *   <li><b>Preview only:</b> Pass {@code confirm = null} or any
 *       non-{@code 'Y'} value, and this service returns the loaded
 *       user record (with {@code confirm} populated as the COBOL
 *       &quot;awaiting confirmation&quot; sentinel). No delete is
 *       performed.</li>
 *   <li><b>Confirm and delete:</b> Pass {@code confirm = 'Y'} (or
 *       {@code 'y'}); this service deletes the row and returns the
 *       deleted record's summary.</li>
 * </ol>
 *
 * @see UserSecurityRepository
 * @see UserDeleteDto
 */
@Service
public class UserDeleteService {

    private static final Logger LOG = LoggerFactory.getLogger(UserDeleteService.class);

    private final UserSecurityRepository userSecurityRepository;
    private final AuditLogService auditLogService;

    public UserDeleteService(UserSecurityRepository userSecurityRepository,
                             AuditLogService auditLogService) {
        this.userSecurityRepository = Objects.requireNonNull(userSecurityRepository,
                "userSecurityRepository");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    /**
     * Deletes an existing user row when {@code request.confirm()} is
     * {@code "Y"} (case-insensitive); otherwise returns the loaded
     * row as a preview.
     *
     * @param request the request carrying {@code userId} and
     *                {@code confirm}
     * @return the {@link UserDeleteDto} populated either with the
     *         pre-delete preview (when {@code confirm != "Y"}) or
     *         with the deleted record's snapshot (when delete
     *         executed)
     * @throws ValidationException     if {@code userId} is missing
     * @throws RecordNotFoundException if no user exists
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDeleteDto deleteUser(UserDeleteDto request) {
        Objects.requireNonNull(request, "request");
        validate(request);

        String userId = request.userId().trim().toUpperCase(Locale.US);

        // ---- COBOL 9000-READ-USER-SEC-FILE -----------------------------
        UserSecurity user = userSecurityRepository.findById(userId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "USER_NOT_FOUND", "User not found"));

        boolean confirmed = request.confirm() != null
                && "Y".equalsIgnoreCase(request.confirm().trim());

        if (!confirmed) {
            // ---- COBOL CONFIRMATION SCREEN (pre-delete preview) -------
            LOG.debug("UserDeleteService: preview (no delete) userId={}",
                    user.getSecUsrId());
            return new UserDeleteDto(
                    user.getSecUsrId(),
                    user.getSecUsrFname(),
                    user.getSecUsrLname(),
                    user.getSecUsrType(),
                    "AWAITING_CONFIRM");
        }

        // ---- COBOL 9300-DELETE-USER-SEC-FILE ---------------------------
        userSecurityRepository.delete(user);

        // ---- Audit -----------------------------------------------------
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", user.getSecUsrId());
        payload.put("userType", user.getSecUsrType());
        auditLogService.logSecurityEvent("user.deleted",
                user.getSecUsrId(),
                "SUCCESS",
                null,
                payload,
                null);

        LOG.info("UserDeleteService.deleteUser deleted userId={} type={}",
                user.getSecUsrId(), user.getSecUsrType());

        return new UserDeleteDto(
                user.getSecUsrId(),
                user.getSecUsrFname(),
                user.getSecUsrLname(),
                user.getSecUsrType(),
                "Y");
    }

    private void validate(UserDeleteDto request) {
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ValidationException(
                    "MISSING_USER_ID",
                    "userId is required",
                    List.of(new ValidationException.FieldError(
                            "userId", "userId is required")));
        }
        if (request.userId().length() > 8) {
            throw new ValidationException(
                    "INVALID_USER_ID",
                    "userId must be at most 8 characters",
                    List.of(new ValidationException.FieldError(
                            "userId", "userId must be at most 8 characters")));
        }
    }
}
