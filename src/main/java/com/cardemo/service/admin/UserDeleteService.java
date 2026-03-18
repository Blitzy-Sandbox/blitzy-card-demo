/*
 * UserDeleteService.java — Spring @Service for User Deletion
 *
 * Migrated from COBOL source artifact:
 *   - app/cbl/COUSR03C.cbl (359 lines, transaction CU03, commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA 80-byte record layout, commit 27d6c6f)
 *
 * This service implements the user deletion flow from the COBOL COUSR03C
 * program, which provides a two-phase delete operation:
 *   Phase 1 (getUserForDelete): Read user by ID and return details for
 *           confirmation display — maps PROCESS-ENTER-KEY (lines 142-169)
 *   Phase 2 (deleteUser): Validate user ID, re-read user, then delete —
 *           maps DELETE-USER-INFO (lines 174-192) + DELETE-USER-SEC-FILE (lines 305-336)
 *
 * COBOL CICS Operations Replaced:
 *   - EXEC CICS READ DATASET(USRSEC) RIDFLD(SEC-USR-ID) UPDATE → JPA findById()
 *   - EXEC CICS DELETE DATASET(USRSEC) → JPA delete()
 *
 * COBOL Bug Fix (D-BUG-COUSR03):
 *   Original COBOL line 332 contains: MOVE 'Unable to Update User...' TO WS-MESSAGE
 *   This is a copy-paste error from COUSR02C (User Update). The correct message
 *   is "Unable to Delete User..." — corrected in this Java implementation.
 *   See TRACEABILITY_MATRIX.md and DECISION_LOG.md for documentation.
 *
 * Key Differences from UserUpdateService (COUSR02C):
 *   1. No PasswordEncoder dependency — deletion does not process passwords
 *   2. No change detection logic — delete is binary (exists or not)
 *   3. Password NOT shown on delete confirmation screen (CU03 omits it)
 *   4. DELETE VSAM operation instead of REWRITE
 *   5. Simpler validation: only user ID validated (not all fields like update)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.admin;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service class providing user deletion functionality for the CardDemo application.
 *
 * <p>Migrates the COBOL program {@code COUSR03C.cbl} (transaction CU03) to a
 * Spring-managed service component. The original COBOL program implements a
 * two-phase user deletion flow:</p>
 * <ol>
 *   <li><strong>Confirmation Phase</strong> ({@link #getUserForDelete(String)}):
 *       Reads the user record by ID and returns details for the delete
 *       confirmation screen. Maps COBOL paragraph {@code PROCESS-ENTER-KEY}
 *       (lines 142-169) which performs a CICS READ with UPDATE lock on the
 *       USRSEC file and populates screen fields FNAMEI, LNAMEI, USRTYPEI.</li>
 *   <li><strong>Deletion Phase</strong> ({@link #deleteUser(String)}):
 *       Validates the user ID, re-reads the user to verify existence (COBOL
 *       re-acquires the UPDATE lock), then performs the actual deletion. Maps
 *       paragraphs {@code DELETE-USER-INFO} (lines 174-192) and
 *       {@code DELETE-USER-SEC-FILE} (lines 305-336).</li>
 * </ol>
 *
 * <p>The COBOL navigation keys (PF3=return, PF4=clear, PF5=confirm delete,
 * PF12=return) are handled at the controller layer via REST endpoint design.
 * This service focuses exclusively on business logic.</p>
 *
 * <h3>COBOL Paragraph Traceability</h3>
 * <table>
 *   <caption>COUSR03C.cbl paragraph to Java method mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>MAIN-PARA</td><td>82</td><td>Class-level orchestration</td></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>142</td><td>{@link #getUserForDelete(String)}</td></tr>
 *   <tr><td>DELETE-USER-INFO</td><td>174</td><td>{@link #deleteUser(String)}</td></tr>
 *   <tr><td>RETURN-TO-PREV-SCREEN</td><td>197</td><td>N/A (controller redirect)</td></tr>
 *   <tr><td>SEND-USRDEL-SCREEN</td><td>213</td><td>N/A (REST JSON response)</td></tr>
 *   <tr><td>RECEIVE-USRDEL-SCREEN</td><td>230</td><td>N/A (REST JSON request)</td></tr>
 *   <tr><td>POPULATE-HEADER-INFO</td><td>243</td><td>N/A (framework-level)</td></tr>
 *   <tr><td>READ-USER-SEC-FILE</td><td>267</td>
 *       <td>{@code userSecurityRepository.findById()}</td></tr>
 *   <tr><td>DELETE-USER-SEC-FILE</td><td>305</td>
 *       <td>{@code userSecurityRepository.delete()}</td></tr>
 *   <tr><td>CLEAR-CURRENT-SCREEN</td><td>341</td><td>N/A (client-side)</td></tr>
 *   <tr><td>INITIALIZE-ALL-FIELDS</td><td>349</td><td>N/A (DTO construction)</td></tr>
 * </table>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see UserSecurityDto
 */
@Service
public class UserDeleteService {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     *
     * <p>Replaces COBOL DISPLAY statements (lines 294, 330) used for
     * diagnostic output in the original program. Log entries include
     * correlation ID propagated via MDC for distributed tracing.</p>
     */
    private static final Logger logger = LoggerFactory.getLogger(UserDeleteService.class);

    /**
     * Spring Data JPA repository for user security record CRUD operations.
     *
     * <p>Replaces all VSAM keyed access to the USRSEC file. Injected via
     * constructor injection as the sole dependency of this service — no
     * {@code PasswordEncoder} is needed because the deletion flow does not
     * process passwords.</p>
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Constructs a new {@code UserDeleteService} with the required repository dependency.
     *
     * <p>Uses constructor injection per Spring best practices. No
     * {@code PasswordEncoder} is injected because user deletion does not
     * handle passwords — unlike {@code UserAddService} (COUSR01C) and
     * {@code UserUpdateService} (COUSR02C) which require BCrypt hashing.</p>
     *
     * @param userSecurityRepository the JPA repository for user security
     *                               record operations; must not be {@code null}
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    // -----------------------------------------------------------------------
    // Phase 1: Confirmation Lookup
    // -----------------------------------------------------------------------

    /**
     * Retrieves a user by ID for delete confirmation display.
     *
     * <p>Maps COBOL paragraph {@code PROCESS-ENTER-KEY} (lines 142-169).
     * The original COBOL flow:</p>
     * <ol>
     *   <li>Validate user ID is not blank (lines 144-154)</li>
     *   <li>Read user record from USRSEC file with UPDATE lock (lines 160-162,
     *       calling READ-USER-SEC-FILE at lines 267-300)</li>
     *   <li>Populate screen fields: SEC-USR-FNAME → FNAMEI (line 165),
     *       SEC-USR-LNAME → LNAMEI (line 166), SEC-USR-TYPE → USRTYPEI (line 167)</li>
     *   <li>Display message: "Press PF5 key to delete this user ..." (line 283)</li>
     * </ol>
     *
     * <p>The password field is intentionally NOT populated for the delete
     * confirmation screen — COBOL CU03 screen omits the password field
     * (unlike CU02 which shows it for update).</p>
     *
     * @param userId the user identifier to look up; must not be {@code null}
     *               or blank (maps COBOL USRIDINI OF COUSR3AI)
     * @return a {@link UserSecurityDto} containing user details for
     *         confirmation display; password field is always {@code null}
     * @throws ValidationException       if {@code userId} is {@code null} or
     *                                   blank — maps COBOL lines 144-154
     * @throws RecordNotFoundException   if no user exists with the given ID —
     *                                   maps COBOL DFHRESP(NOTFND) at line 287
     */
    @Transactional(readOnly = true)
    public UserSecurityDto getUserForDelete(String userId) {
        // Step 1: Validate user ID — maps COBOL lines 144-154
        validateUserId(userId);

        // Step 2: Read user from USRSEC — maps READ-USER-SEC-FILE (lines 267-300)
        // COBOL: EXEC CICS READ DATASET(USRSEC) RIDFLD(SEC-USR-ID) UPDATE
        UserSecurity entity = userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    // Maps DFHRESP(NOTFND) at lines 287-292
                    logger.error("User ID NOT found during delete confirmation lookup: {}", userId);
                    return new RecordNotFoundException("User ID NOT found...");
                });

        // Step 3: Convert to DTO for confirmation display — maps lines 164-168
        // COBOL: MOVE SEC-USR-FNAME TO FNAMEI, MOVE SEC-USR-LNAME TO LNAMEI,
        //        MOVE SEC-USR-TYPE TO USRTYPEI
        // Password is NOT displayed on the CU03 delete screen
        UserSecurityDto dto = convertToDto(entity);

        // Step 4: Log lookup success — replaces COBOL DISPLAY diagnostic output
        logger.info("User {} retrieved for deletion confirmation", userId);

        return dto;
    }

    // -----------------------------------------------------------------------
    // Phase 2: Actual Deletion
    // -----------------------------------------------------------------------

    /**
     * Deletes a user by ID after confirmation.
     *
     * <p>Maps COBOL paragraph {@code DELETE-USER-INFO} (lines 174-192) and
     * {@code DELETE-USER-SEC-FILE} (lines 305-336). The original COBOL flow:</p>
     * <ol>
     *   <li>Validate user ID is not blank (lines 176-186)</li>
     *   <li>Re-read user with UPDATE lock to verify existence and acquire lock
     *       (lines 188-190, calling READ-USER-SEC-FILE)</li>
     *   <li>Delete the user record (line 191, calling DELETE-USER-SEC-FILE at
     *       lines 305-336)</li>
     *   <li>Handle response codes:
     *       <ul>
     *         <li>DFHRESP(NORMAL): Build success message "User {id} has been deleted ..."
     *             (lines 314-322)</li>
     *         <li>DFHRESP(NOTFND): "User ID NOT found..." (lines 323-328)</li>
     *         <li>OTHER: Log error (lines 329-335) — <strong>COBOL BUG FIX:</strong>
     *             Original says "Unable to Update User..." (copy-paste from COUSR02C),
     *             corrected to "Unable to Delete User..."</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>In JPA, the transaction provides isolation guarantees equivalent to
     * CICS READ with UPDATE lock. The {@code @Transactional} annotation ensures
     * that both the read verification and delete operation execute within a
     * single database transaction.</p>
     *
     * @param userId the user identifier to delete; must not be {@code null}
     *               or blank (maps COBOL USRIDINI OF COUSR3AI)
     * @return a {@link UserSecurityDto} containing the deleted user's details
     *         for confirmation; password field is always {@code null}
     * @throws ValidationException       if {@code userId} is {@code null} or
     *                                   blank — maps COBOL lines 176-186
     * @throws RecordNotFoundException   if no user exists with the given ID —
     *                                   maps COBOL DFHRESP(NOTFND) at lines 287, 323
     */
    @Transactional
    public UserSecurityDto deleteUser(String userId) {
        // Step 1: Validate user ID — maps COBOL lines 176-186
        validateUserId(userId);

        // Step 2: Re-read user to verify existence — maps READ-USER-SEC-FILE (lines 188-190)
        // COBOL re-reads with UPDATE lock before DELETE to ensure record still exists
        // and to acquire an exclusive lock. In JPA, the @Transactional boundary
        // provides equivalent isolation guarantees.
        UserSecurity entity = userSecurityRepository.findById(userId)
                .orElseThrow(() -> {
                    // Maps DFHRESP(NOTFND) at lines 287-292 and 323-328
                    logger.error("User ID NOT found during delete operation: {}", userId);
                    return new RecordNotFoundException("User ID NOT found...");
                });

        // Step 3: Delete user — maps DELETE-USER-SEC-FILE (lines 305-336)
        // COBOL: EXEC CICS DELETE DATASET(USRSEC)
        try {
            userSecurityRepository.delete(entity);
        } catch (Exception ex) {
            // Maps DFHRESP(OTHER) at lines 329-335
            // COBOL BUG FIX: Original says "Unable to Update User..." at line 332
            // which is a copy-paste error from COUSR02C. Corrected to "Unable to Delete User..."
            // See DECISION_LOG.md entry D-BUG-COUSR03 for rationale.
            logger.error("Unable to Delete User {}: {}", userId, ex.getMessage(), ex);
            throw new RecordNotFoundException("Unable to Delete User...");
        }

        // Step 4: Success response — maps DFHRESP(NORMAL) at lines 314-322
        // COBOL STRING: "User " + SEC-USR-ID + " has been deleted ..."
        UserSecurityDto dto = convertToDto(entity);
        logger.info("User {} has been deleted", userId);

        return dto;
    }

    // -----------------------------------------------------------------------
    // Private Support Methods
    // -----------------------------------------------------------------------

    /**
     * Validates that the user ID is not null, empty, or blank.
     *
     * <p>Extracts the common validation pattern used by both
     * {@link #getUserForDelete(String)} and {@link #deleteUser(String)}.
     * Maps the COBOL validation at lines 144-154 (PROCESS-ENTER-KEY) and
     * lines 176-186 (DELETE-USER-INFO):</p>
     * <pre>
     * EVALUATE TRUE
     *     WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES
     *         MOVE 'Y' TO WS-ERR-FLG
     *         MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
     * </pre>
     *
     * @param userId the user identifier to validate
     * @throws ValidationException if {@code userId} is null, empty, or blank
     */
    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            logger.error("User ID validation failed: User ID is null or blank");
            throw new ValidationException("User ID can NOT be empty...");
        }
    }

    /**
     * Converts a {@link UserSecurity} entity to a {@link UserSecurityDto} for
     * API response.
     *
     * <p>Maps entity fields to DTO fields matching the COBOL field population
     * at lines 164-168 of COUSR03C.cbl:</p>
     * <ul>
     *   <li>{@code SEC-USR-FNAME → FNAMEI} (line 165) → {@code dto.secUsrFname}</li>
     *   <li>{@code SEC-USR-LNAME → LNAMEI} (line 166) → {@code dto.secUsrLname}</li>
     *   <li>{@code SEC-USR-TYPE → USRTYPEI} (line 167) → {@code dto.secUsrType}</li>
     * </ul>
     *
     * <p>The password field is explicitly set to {@code null} because the COBOL
     * CU03 delete confirmation screen does NOT display the password (unlike the
     * CU02 update screen which shows it). This ensures no sensitive credential
     * data is returned in delete operation responses.</p>
     *
     * @param entity the user security entity to convert; must not be {@code null}
     * @return a new {@link UserSecurityDto} with user details populated and
     *         password set to {@code null}
     */
    private UserSecurityDto convertToDto(UserSecurity entity) {
        UserSecurityDto dto = new UserSecurityDto();
        dto.setSecUsrId(entity.getSecUsrId());
        dto.setSecUsrFname(entity.getSecUsrFname());
        dto.setSecUsrLname(entity.getSecUsrLname());
        dto.setSecUsrType(entity.getSecUsrType());
        // Password NOT displayed on delete screen — COBOL CU03 screen omits password
        // Unlike CU02 (update) which shows the password field for modification
        dto.setSecUsrPwd(null);
        return dto;
    }
}
