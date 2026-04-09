/*
 * UserAddService.java — Spring @Service for User Creation with BCrypt
 *
 * Migrated from COBOL source artifact:
 *   - app/cbl/COUSR01C.cbl (299 lines, transaction ID CU01, commit 27d6c6f)
 *   - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA record layout, 80 bytes, commit 27d6c6f)
 *
 * This service encapsulates the user creation business logic originally implemented
 * in COBOL program COUSR01C.cbl. The COBOL program operates within the CICS
 * pseudo-conversational model (SEND MAP / RECEIVE MAP / RETURN TRANSID) and writes
 * new user security records to the USRSEC VSAM KSDS dataset via CICS WRITE.
 *
 * COBOL Paragraph → Java Method Traceability:
 *   MAIN-PARA              (line 71)  → Class-level orchestration (controller)
 *   PROCESS-ENTER-KEY      (line 115) → addUser() validation + persist flow
 *   RETURN-TO-PREV-SCREEN  (line 165) → N/A (controller routing)
 *   SEND-USRADD-SCREEN     (line 184) → N/A (REST JSON response)
 *   RECEIVE-USRADD-SCREEN  (line 201) → N/A (REST JSON request)
 *   POPULATE-HEADER-INFO   (line 214) → N/A (controller/framework)
 *   WRITE-USER-SEC-FILE    (line 238) → userSecurityRepository.save()
 *   CLEAR-CURRENT-SCREEN   (line 279) → N/A (client-side)
 *   INITIALIZE-ALL-FIELDS  (line 287) → N/A (DTO construction)
 *
 * SECURITY UPGRADE (Decision D-002):
 *   The original COBOL application stores passwords in plaintext:
 *     MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD  (line 157)
 *   where SEC-USR-PWD is PIC X(08) — an 8-character plaintext field.
 *
 *   The Java migration upgrades to BCrypt hashing via Spring Security's
 *   PasswordEncoder interface. The BCrypt hash ($2a$10$..., ~60 chars) is
 *   stored in the database instead of the plaintext password. The column
 *   is sized at 60 characters to accommodate standard BCrypt output.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.admin;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementing user creation logic migrated from COBOL program COUSR01C.cbl.
 *
 * <p>This service handles the "Add User" functionality (CICS Transaction CU01),
 * replacing the CICS WRITE operation on the USRSEC VSAM KSDS dataset with
 * Spring Data JPA's {@code save()} method. All five field validations from the
 * COBOL EVALUATE TRUE block in PROCESS-ENTER-KEY (lines 117–151) are preserved
 * in exact order.</p>
 *
 * <h3>COBOL Source Mapping</h3>
 * <table>
 *   <caption>COUSR01C.cbl paragraph to Java method mapping</caption>
 *   <tr><th>COBOL Paragraph</th><th>Line</th><th>Java Method</th></tr>
 *   <tr><td>PROCESS-ENTER-KEY</td><td>115</td><td>{@link #addUser(UserSecurityDto)}</td></tr>
 *   <tr><td>WRITE-USER-SEC-FILE</td><td>238</td><td>{@code repository.save()}</td></tr>
 * </table>
 *
 * <h3>Validation Order (Preserved from COBOL)</h3>
 * <ol>
 *   <li>First Name (FNAMEI) — line 118–123</li>
 *   <li>Last Name (LNAMEI) — line 124–129</li>
 *   <li>User ID (USERIDI) — line 130–135</li>
 *   <li>Password (PASSWDI) — line 136–141</li>
 *   <li>User Type (USRTYPEI) — line 142–147</li>
 * </ol>
 *
 * <h3>Error Messages (Exact COBOL Parity)</h3>
 * <ul>
 *   <li>{@code "First Name can NOT be empty..."} — line 120–121</li>
 *   <li>{@code "Last Name can NOT be empty..."} — line 126–127</li>
 *   <li>{@code "User ID can NOT be empty..."} — line 132–133</li>
 *   <li>{@code "Password can NOT be empty..."} — line 138–139</li>
 *   <li>{@code "User Type can NOT be empty..."} — line 144–145</li>
 *   <li>{@code "User ID already exist..."} — line 263 (DFHRESP DUPKEY/DUPREC)</li>
 *   <li>{@code "Unable to Add User..."} — line 270 (OTHER response code)</li>
 * </ul>
 *
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.model.dto.UserSecurityDto
 */
@Service
public class UserAddService {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs user creation success/failure events per AAP observability rules.
     */
    private static final Logger logger = LoggerFactory.getLogger(UserAddService.class);

    /**
     * Spring Data JPA repository for USRSEC VSAM dataset access.
     * Replaces CICS WRITE DATASET(USRSEC) operations from COUSR01C.cbl.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Spring Security password encoder (BCryptPasswordEncoder injected from SecurityConfig).
     * CRITICAL security upgrade from COBOL plaintext password storage (constraint C-003).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Constructs a new {@code UserAddService} with required dependencies.
     *
     * <p>Both dependencies are injected via Spring constructor injection.
     * The {@code PasswordEncoder} is expected to be a {@code BCryptPasswordEncoder}
     * configured in {@code SecurityConfig.java}.</p>
     *
     * @param userSecurityRepository the JPA repository for user security record persistence;
     *                               replaces CICS WRITE DATASET(USRSEC) operations
     * @param passwordEncoder        the password encoder for BCrypt hashing; replaces the
     *                               COBOL plaintext {@code MOVE PASSWDI TO SEC-USR-PWD}
     *                               (line 157)
     */
    public UserAddService(UserSecurityRepository userSecurityRepository,
                          PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a new user security record in the database.
     *
     * <p>This method maps the COBOL PROCESS-ENTER-KEY paragraph (lines 115–160) and
     * WRITE-USER-SEC-FILE paragraph (lines 238–274) from COUSR01C.cbl. The processing
     * flow is:</p>
     * <ol>
     *   <li><strong>Validate all fields</strong> — Five sequential field validations in
     *       exact COBOL order (FNAME → LNAME → USERID → PASSWORD → USERTYPE). Each
     *       validation throws {@link ValidationException} with the exact COBOL error
     *       message on failure.</li>
     *   <li><strong>Check for duplicate user ID</strong> — Pre-checks for existing user
     *       via {@code findById()} before attempting to write, mapping the COBOL
     *       DFHRESP(DUPKEY)/DFHRESP(DUPREC) handling (lines 260–266).</li>
     *   <li><strong>Build entity</strong> — Maps DTO fields to entity fields per COBOL
     *       MOVE statements (lines 154–158). Password is BCrypt-hashed (security
     *       upgrade from plaintext).</li>
     *   <li><strong>Persist</strong> — Saves the entity via {@code repository.save()},
     *       replacing CICS WRITE DATASET USRSEC (lines 240–248).</li>
     *   <li><strong>Return DTO</strong> — Converts the persisted entity back to a DTO
     *       with the password field nulled out for security.</li>
     * </ol>
     *
     * <p>The method is annotated with {@code @Transactional} to ensure atomicity of the
     * write operation, mapping the implicit CICS unit-of-work semantics.</p>
     *
     * @param dto the user security data transfer object containing the new user's
     *            information (user ID, first name, last name, password, user type)
     * @return a {@link UserSecurityDto} representing the created user, with the
     *         password field set to {@code null} for security
     * @throws ValidationException       if any of the 5 required fields are null or blank
     * @throws DuplicateRecordException  if a user with the specified ID already exists
     *                                   (maps DFHRESP(DUPKEY)/DFHRESP(DUPREC))
     * @throws RuntimeException          if the persistence operation fails for any other
     *                                   reason (maps COBOL "Unable to Add User..." error)
     */
    @Transactional
    public UserSecurityDto addUser(UserSecurityDto dto) {
        // Step 1: Validate all input fields in exact COBOL order
        // Maps PROCESS-ENTER-KEY EVALUATE TRUE block (lines 117-151)
        validateUserInput(dto);

        // Step 2: Check for duplicate user ID before attempting write
        // Maps DFHRESP(DUPKEY)/DFHRESP(DUPREC) handling (lines 260-266)
        String userId = dto.getSecUsrId().trim();
        if (userSecurityRepository.findById(userId).isPresent()) {
            logger.warn("Duplicate user creation attempt for user ID: {}", userId);
            throw new DuplicateRecordException("User ID already exist...");
        }

        // Step 3: Build the UserSecurity entity from DTO fields
        // Maps COBOL MOVE statements (lines 154-158)
        UserSecurity entity = buildEntityFromDto(dto);

        try {
            // Step 4: Persist the entity
            // Maps WRITE-USER-SEC-FILE paragraph (lines 240-248)
            // Replaces: EXEC CICS WRITE DATASET(WS-USRSEC-FILE)
            //           FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)
            UserSecurity savedEntity = userSecurityRepository.save(entity);

            // Step 5: Log success — maps COBOL STRING success message (lines 255-258):
            //   STRING 'User ' DELIMITED BY SIZE
            //          SEC-USR-ID DELIMITED BY SPACE
            //          ' has been added ...' DELIMITED BY SIZE INTO WS-MESSAGE
            logger.info("User {} has been added", savedEntity.getSecUsrId());

            // Return the created DTO with password nulled out for security
            return convertToDto(savedEntity);

        } catch (DuplicateRecordException ex) {
            // Re-throw DuplicateRecordException — already a business exception
            throw ex;
        } catch (RuntimeException ex) {
            // Maps EVALUATE WS-RESP-CD ... WHEN OTHER (lines 267-273)
            // COBOL: MOVE 'Unable to Add User...' TO WS-MESSAGE
            logger.error("Unable to Add User... userId={}", userId, ex);
            throw new RuntimeException("Unable to Add User...", ex);
        }
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Validates all input fields in the exact order defined by the COBOL
     * EVALUATE TRUE block in PROCESS-ENTER-KEY (lines 117–151).
     *
     * <p>The validation order is CRITICAL for behavioral parity with the COBOL
     * source. The COBOL EVALUATE TRUE evaluates WHEN clauses sequentially and
     * terminates at the first match. The Java implementation preserves this
     * by checking fields in order and throwing on the first failure:</p>
     * <ol>
     *   <li>First Name (FNAMEI) — COBOL line 118</li>
     *   <li>Last Name (LNAMEI) — COBOL line 124</li>
     *   <li>User ID (USERIDI) — COBOL line 130</li>
     *   <li>Password (PASSWDI) — COBOL line 136</li>
     *   <li>User Type (USRTYPEI) — COBOL line 142</li>
     * </ol>
     *
     * <p>In COBOL, each WHEN clause checks for SPACES or LOW-VALUES, which
     * maps to null or blank string checks in Java.</p>
     *
     * @param dto the user security DTO to validate
     * @throws ValidationException if any required field is null or blank,
     *                             with the exact COBOL error message
     */
    private void validateUserInput(UserSecurityDto dto) {
        // Validation 1: First Name — maps COBOL line 118-123
        // WHEN FNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'First Name can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrFname() == null || dto.getSecUsrFname().isBlank()) {
            throw new ValidationException("First Name can NOT be empty...");
        }

        // Validation 2: Last Name — maps COBOL line 124-129
        // WHEN LNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'Last Name can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrLname() == null || dto.getSecUsrLname().isBlank()) {
            throw new ValidationException("Last Name can NOT be empty...");
        }

        // Validation 3: User ID — maps COBOL line 130-135
        // WHEN USERIDI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'User ID can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrId() == null || dto.getSecUsrId().isBlank()) {
            throw new ValidationException("User ID can NOT be empty...");
        }

        // Validation 4: Password — maps COBOL line 136-141
        // WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'Password can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrPwd() == null || dto.getSecUsrPwd().isBlank()) {
            throw new ValidationException("Password can NOT be empty...");
        }

        // Validation 5: User Type — maps COBOL line 142-147
        // WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES
        //   MOVE 'User Type can NOT be empty...' TO WS-MESSAGE
        if (dto.getSecUsrType() == null) {
            throw new ValidationException("User Type can NOT be empty...");
        }
    }

    /**
     * Builds a {@link UserSecurity} entity from the validated DTO fields.
     *
     * <p>Maps the COBOL MOVE statements from PROCESS-ENTER-KEY (lines 154–158):</p>
     * <ul>
     *   <li>{@code MOVE USERIDI OF COUSR1AI TO SEC-USR-ID} (line 154)</li>
     *   <li>{@code MOVE FNAMEI OF COUSR1AI TO SEC-USR-FNAME} (line 155)</li>
     *   <li>{@code MOVE LNAMEI OF COUSR1AI TO SEC-USR-LNAME} (line 156)</li>
     *   <li>{@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD} (line 157)
     *       — <strong>UPGRADED</strong> to BCrypt hash instead of plaintext</li>
     *   <li>{@code MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE} (line 158)</li>
     * </ul>
     *
     * <p>The password field undergoes BCrypt encoding via {@code passwordEncoder.encode()}.
     * This is the CRITICAL security upgrade from COBOL's plaintext storage
     * (constraint C-003, Decision D-002).</p>
     *
     * @param dto the validated user security DTO
     * @return a new {@link UserSecurity} entity ready for persistence
     */
    private UserSecurity buildEntityFromDto(UserSecurityDto dto) {
        UserSecurity entity = new UserSecurity();

        // MOVE USERIDI OF COUSR1AI TO SEC-USR-ID (line 154)
        entity.setSecUsrId(dto.getSecUsrId().trim());

        // MOVE FNAMEI OF COUSR1AI TO SEC-USR-FNAME (line 155)
        entity.setSecUsrFname(dto.getSecUsrFname().trim());

        // MOVE LNAMEI OF COUSR1AI TO SEC-USR-LNAME (line 156)
        entity.setSecUsrLname(dto.getSecUsrLname().trim());

        // SECURITY UPGRADE: MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD (line 157)
        // COBOL stored plaintext PIC X(08); Java stores BCrypt hash (~60 chars).
        // Password is uppercased before hashing to preserve COBOL behavioral parity:
        // COSGN00C.cbl line 135 uppercases the password before comparison
        // (MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD), making passwords
        // effectively case-insensitive. AuthenticationService.authenticate() applies
        // the same uppercasing before BCrypt verification, so encoding must also
        // use the uppercased form to ensure matches() succeeds.
        entity.setSecUsrPwd(passwordEncoder.encode(dto.getSecUsrPwd().trim().toUpperCase()));

        // MOVE USRTYPEI OF COUSR1AI TO SEC-USR-TYPE (line 158)
        entity.setSecUsrType(dto.getSecUsrType());

        return entity;
    }

    /**
     * Converts a {@link UserSecurity} entity to a {@link UserSecurityDto} for
     * API response, with the password field explicitly nulled out.
     *
     * <p>This method ensures that the BCrypt password hash is NEVER included in
     * any API response. While the DTO's {@code @JsonProperty(access = WRITE_ONLY)}
     * annotation on the password field prevents serialization, this method provides
     * defense-in-depth by setting the password to {@code null} at the service layer.</p>
     *
     * @param entity the persisted user security entity
     * @return a DTO representation of the entity with password set to {@code null}
     */
    private UserSecurityDto convertToDto(UserSecurity entity) {
        UserSecurityDto dto = new UserSecurityDto();

        dto.setSecUsrId(entity.getSecUsrId());
        dto.setSecUsrFname(entity.getSecUsrFname());
        dto.setSecUsrLname(entity.getSecUsrLname());
        dto.setSecUsrType(entity.getSecUsrType());

        // NEVER include password in the returned DTO — defense-in-depth security
        dto.setSecUsrPwd(null);

        return dto;
    }
}
