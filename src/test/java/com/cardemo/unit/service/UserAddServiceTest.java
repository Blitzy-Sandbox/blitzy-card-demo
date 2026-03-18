/*
 * UserAddServiceTest.java — JUnit 5 + Mockito Unit Tests for UserAddService
 *
 * Validates the user-creation logic migrated from COBOL program COUSR01C.cbl
 * (299 lines, CICS transaction CU01, commit 27d6c6f). Covers the complete
 * PROCESS-ENTER-KEY validation cascade (FNAME → LNAME → USERID → PASSWORD →
 * USERTYPE), duplicate user detection (DFHRESP DUPKEY/DUPREC), BCrypt password
 * encoding (security upgrade from COBOL plaintext PIC X(08)), successful entity
 * persistence via JPA save(), and defense-in-depth password exclusion from
 * returned DTOs.
 *
 * COBOL Source Mapping:
 *   COUSR01C.cbl  PROCESS-ENTER-KEY  (line 115) → addUser() validation flow
 *   COUSR01C.cbl  WRITE-USER-SEC-FILE (line 238) → repository.save()
 *   CSUSR01Y.cpy  SEC-USER-DATA       (80 bytes) → UserSecurity entity fields
 *
 * Test Framework:
 *   - JUnit 5 (@Test, @BeforeEach, @ExtendWith(MockitoExtension.class))
 *   - Mockito 5.x (@Mock, @InjectMocks, ArgumentCaptor)
 *   - AssertJ (assertThat, assertThatThrownBy)
 *   - NO Spring context loading — pure unit tests
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.service;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserAddService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAddService} — validates COUSR01C.cbl user creation
 * with BCrypt password hashing migrated to Java.
 *
 * <p>14 test cases cover:
 * <ul>
 *   <li>5-field validation order matching COBOL EVALUATE TRUE cascade
 *       (FNAME → LNAME → USERID → PASSWORD → USERTYPE)</li>
 *   <li>Duplicate user detection mapping DFHRESP(DUPKEY)/DFHRESP(DUPREC)</li>
 *   <li>BCrypt password encoding replacing COBOL plaintext storage</li>
 *   <li>Successful creation with entity persistence verification</li>
 *   <li>Defense-in-depth password exclusion from response DTOs</li>
 *   <li>Transactional annotation verification for atomicity</li>
 * </ul>
 *
 * <p>Uses pure Mockito (no Spring context) with AssertJ assertions.</p>
 *
 * @see UserAddService
 * @see com.cardemo.model.entity.UserSecurity
 * @see com.cardemo.model.dto.UserSecurityDto
 */
@ExtendWith(MockitoExtension.class)
class UserAddServiceTest {

    // ── Mocks ──────────────────────────────────────────────────────────────

    /**
     * Mock for the user security repository — VSAM USRSEC KSDS access layer.
     * Provides {@code findById(String)} stubs for duplicate detection and
     * {@code save(UserSecurity)} stubs for entity persistence verification.
     * Maps CICS READ/WRITE DATASET(USRSEC) operations from COUSR01C.cbl.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mock for Spring Security PasswordEncoder — provides stubbed
     * {@code encode(CharSequence)} returning a fixed BCrypt hash string.
     * Replaces COBOL plaintext password storage (MOVE PASSWDI TO SEC-USR-PWD,
     * COUSR01C.cbl line 157) with BCrypt hashing per AAP §0.8.1 D-002.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Service under test — receives mocked UserSecurityRepository and
     * PasswordEncoder via Mockito constructor injection (@InjectMocks).
     */
    @InjectMocks
    private UserAddService userAddService;

    // ── Test fixture constants ─────────────────────────────────────────────

    /** 8-character user ID matching COBOL PIC X(08) from CSUSR01Y.cpy. */
    private static final String USER_ID = "USR00001";

    /** 20-character first name matching COBOL PIC X(20) SEC-USR-FNAME. */
    private static final String FIRST_NAME = "JOHN";

    /** 20-character last name matching COBOL PIC X(20) SEC-USR-LNAME. */
    private static final String LAST_NAME = "DOE";

    /** Plaintext password (8-char COBOL PIC X(08) SEC-USR-PWD boundary). */
    private static final String PASSWORD = "PASS1234";

    /**
     * Fixed BCrypt hash returned by the mocked PasswordEncoder.encode().
     * Represents the ~60-character BCrypt output that replaces COBOL's
     * plaintext storage. Used to verify encoded (not plaintext) persistence.
     */
    private static final String ENCODED_PASSWORD =
            "$2a$10$eXaMpLeHaShFoRtEsTiNgOnLyNoTrEaL12345678901";

    // ── Mutable test fixture ───────────────────────────────────────────────

    /**
     * A fully valid {@link UserSecurityDto} pre-populated in {@code @BeforeEach}.
     * Individual tests mutate specific fields to trigger targeted validation
     * failures while keeping all other fields valid.
     */
    private UserSecurityDto validDto;

    // ── Setup ──────────────────────────────────────────────────────────────

    /**
     * Initializes a valid {@link UserSecurityDto} with all five required fields
     * set to realistic values matching the COBOL SEC-USER-DATA record layout
     * (CSUSR01Y.cpy). Each test method then mutates a single field or configures
     * mock behavior to isolate the scenario under test.
     */
    @BeforeEach
    void setUp() {
        validDto = new UserSecurityDto();
        validDto.setSecUsrId(USER_ID);
        validDto.setSecUsrFname(FIRST_NAME);
        validDto.setSecUsrLname(LAST_NAME);
        validDto.setSecUsrPwd(PASSWORD);
        validDto.setSecUsrType(UserType.ADMIN);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Field Validations — COBOL COUSR01C ORDER: FNAME→LNAME→USERID→PASSWORD→USERTYPE
    // Maps PROCESS-ENTER-KEY EVALUATE TRUE (lines 117-151)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Blank first name → ValidationException.
     *
     * <p>Maps COBOL EVALUATE TRUE WHEN clause at line 118:
     * {@code WHEN FNAMEI OF COUSR1AI = SPACES OR LOW-VALUES}
     * Sets FNAME to null (maps LOW-VALUES) to trigger the first validation
     * check in the cascade. Verifies the exact COBOL error message is preserved.</p>
     */
    @Test
    void testAddUser_blankFirstName_throwsValidation() {
        validDto.setSecUsrFname(null);

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("First Name can NOT be empty");
    }

    /**
     * Test 2: Blank last name → ValidationException.
     *
     * <p>Maps COBOL EVALUATE TRUE WHEN clause at line 124:
     * {@code WHEN LNAMEI OF COUSR1AI = SPACES OR LOW-VALUES}
     * Sets LNAME to empty string (maps SPACES) to trigger the second validation
     * check. First name is valid, so the cascade advances past check 1.</p>
     */
    @Test
    void testAddUser_blankLastName_throwsValidation() {
        validDto.setSecUsrLname("");

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Last Name can NOT be empty");
    }

    /**
     * Test 3: Blank user ID → ValidationException.
     *
     * <p>Maps COBOL EVALUATE TRUE WHEN clause at line 130:
     * {@code WHEN USERIDI OF COUSR1AI = SPACES OR LOW-VALUES}
     * Sets USERID to whitespace-only string (maps COBOL SPACES) to trigger
     * the third validation check. FNAME and LNAME are valid.</p>
     */
    @Test
    void testAddUser_blankUserId_throwsValidation() {
        validDto.setSecUsrId("   ");

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID can NOT be empty");
    }

    /**
     * Test 4: Blank password → ValidationException.
     *
     * <p>Maps COBOL EVALUATE TRUE WHEN clause at line 136:
     * {@code WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES}
     * Sets PASSWORD to null (maps LOW-VALUES) to trigger the fourth validation
     * check. FNAME, LNAME, and USERID are all valid.</p>
     */
    @Test
    void testAddUser_blankPassword_throwsValidation() {
        validDto.setSecUsrPwd(null);

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password can NOT be empty");
    }

    /**
     * Test 5: Blank user type → ValidationException.
     *
     * <p>Maps COBOL EVALUATE TRUE WHEN clause at line 142:
     * {@code WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES}
     * Sets USERTYPE to null (the only blank state for an enum type) to trigger
     * the fifth and final validation check. All prior fields are valid.</p>
     */
    @Test
    void testAddUser_blankUserType_throwsValidation() {
        validDto.setSecUsrType(null);

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User Type can NOT be empty");
    }

    /**
     * Test 6: Invalid user type → ValidationException.
     *
     * <p>Maps COBOL 88-level condition check from COCOM01Y.cpy where
     * USRTYPEI must be 'A' (ADMIN) or 'U' (USER). Since UserType is a Java
     * enum with only {@link UserType#ADMIN} and {@link UserType#USER} values,
     * any value outside those two can only be represented as {@code null} at
     * the DTO layer. Verifies the service rejects null type and that the
     * thrown {@link ValidationException} carries an empty field-errors list
     * (single-arg constructor path from COBOL MOVE ... TO WS-MESSAGE).</p>
     */
    @Test
    void testAddUser_invalidUserType_throwsValidation() {
        validDto.setSecUsrType(null);

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> {
                    ValidationException ve = (ValidationException) thrown;
                    // Single-arg constructor creates empty fieldErrors list —
                    // matches COBOL MOVE ... TO WS-MESSAGE pattern (no structured field errors)
                    assertThat(ve.getFieldErrors()).isNotNull();
                    assertThat(ve.getFieldErrors()).isEmpty();
                });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Duplicate User Detection
    // Maps WRITE-USER-SEC-FILE DFHRESP(DUPKEY)/DFHRESP(DUPREC) (lines 260-266)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Test 7: Duplicate user ID → DuplicateRecordException.
     *
     * <p>Maps COBOL DFHRESP(DUPKEY)/DFHRESP(DUPREC) response from
     * COUSR01C.cbl WRITE-USER-SEC-FILE paragraph (lines 260–266):
     * {@code MOVE 'User ID already exist...' TO WS-MESSAGE}
     *
     * <p>Stubs {@code userSecurityRepository.findById()} to return an existing
     * user entity, simulating the pre-check that prevents a VSAM DUPKEY error.
     * The service performs this pre-check before attempting to write.</p>
     */
    @Test
    void testAddUser_duplicateUserId_throwsDuplicateRecord() {
        UserSecurity existingUser = new UserSecurity();
        existingUser.setSecUsrId(USER_ID);
        existingUser.setSecUsrFname("EXISTING");
        existingUser.setSecUsrLname("USER");
        existingUser.setSecUsrPwd(ENCODED_PASSWORD);
        existingUser.setSecUsrType(UserType.USER);

        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> userAddService.addUser(validDto))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("User ID already exist");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // BCrypt Password Encoding
    // Security upgrade from COBOL plaintext PIC X(08) → BCrypt hash (D-002)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Test 8: Verify {@code passwordEncoder.encode()} called with user's password.
     *
     * <p>Maps COBOL line 157: {@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD}
     * — the original plaintext move is replaced by BCrypt encoding. Verifies
     * that the password encoder is invoked exactly once with the user's
     * plaintext password, confirming the encoding step occurs.</p>
     */
    @Test
    void testAddUser_passwordEncodedWithBCrypt() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userAddService.addUser(validDto);

        verify(passwordEncoder, times(1)).encode(PASSWORD);
    }

    /**
     * Test 9: Encoded password (not plaintext) stored in the entity.
     *
     * <p>Captures the {@link UserSecurity} entity passed to {@code save()} via
     * {@link ArgumentCaptor} and verifies its password field contains the
     * BCrypt hash, not the plaintext password. This is the CRITICAL security
     * validation — the COBOL source stored plaintext; the Java migration must
     * store only the encoded hash.</p>
     */
    @Test
    void testAddUser_encodedPasswordStoredInEntity() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userAddService.addUser(validDto);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity savedEntity = captor.getValue();

        // Entity must contain BCrypt hash, NOT plaintext
        assertThat(savedEntity.getSecUsrPwd()).isEqualTo(ENCODED_PASSWORD);
        assertThat(savedEntity.getSecUsrPwd()).isNotEqualTo(PASSWORD);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Successful Creation
    // Maps WRITE-USER-SEC-FILE paragraph DFHRESP(NORMAL) path (lines 252-258)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Test 10: Verify {@code userSecurityRepository.save()} called on success.
     *
     * <p>Maps COBOL CICS WRITE DATASET(USRSEC) FROM(SEC-USER-DATA)
     * RIDFLD(SEC-USR-ID) at lines 240–248. Verifies the repository's
     * {@code save()} method is invoked exactly once with a UserSecurity entity,
     * confirming the persistence step executes.</p>
     */
    @Test
    void testAddUser_success_savedToRepository() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userAddService.addUser(validDto);

        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }

    /**
     * Test 11: All five fields correctly set on persisted entity.
     *
     * <p>Maps the five COBOL MOVE statements from PROCESS-ENTER-KEY
     * (lines 154–158) that populate SEC-USER-DATA before the WRITE:
     * <ol>
     *   <li>SEC-USR-ID    ← USERIDI  (line 154)</li>
     *   <li>SEC-USR-FNAME ← FNAMEI   (line 155)</li>
     *   <li>SEC-USR-LNAME ← LNAMEI   (line 156)</li>
     *   <li>SEC-USR-PWD   ← BCrypt   (line 157, UPGRADED from plaintext)</li>
     *   <li>SEC-USR-TYPE  ← USRTYPEI (line 158)</li>
     * </ol>
     * Uses {@link ArgumentCaptor} to inspect the entity at the moment of save.</p>
     */
    @Test
    void testAddUser_success_allFieldsSet() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        userAddService.addUser(validDto);

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity savedEntity = captor.getValue();

        // Verify all 5 fields mapped from COBOL MOVE statements
        assertThat(savedEntity.getSecUsrId()).isEqualTo(USER_ID);
        assertThat(savedEntity.getSecUsrFname()).isEqualTo(FIRST_NAME);
        assertThat(savedEntity.getSecUsrLname()).isEqualTo(LAST_NAME);
        assertThat(savedEntity.getSecUsrPwd()).isEqualTo(ENCODED_PASSWORD);
        assertThat(savedEntity.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    /**
     * Test 12: Returns {@link UserSecurityDto} with all fields populated.
     *
     * <p>Verifies the response DTO returned by {@code addUser()} contains
     * the user ID, first name, last name, and user type from the persisted
     * entity. This maps the COBOL success path where COUSR01C.cbl builds
     * a STRING message: {@code 'User ' ... SEC-USR-ID ... ' has been added ...'}
     * (lines 255–258) — the Java equivalent returns the created DTO.</p>
     */
    @Test
    void testAddUser_success_returnsDto() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserSecurityDto result = userAddService.addUser(validDto);

        assertThat(result).isNotNull();
        assertThat(result.getSecUsrId()).isEqualTo(USER_ID);
        assertThat(result.getSecUsrFname()).isEqualTo(FIRST_NAME);
        assertThat(result.getSecUsrLname()).isEqualTo(LAST_NAME);
        assertThat(result.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Password Security — Defense-in-Depth
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Test 13: CRITICAL — Returned DTO must NOT contain password.
     *
     * <p>Verifies defense-in-depth password exclusion: the service's
     * {@code convertToDto()} method explicitly sets the password to
     * {@code null} before returning. Combined with the DTO's
     * {@code @JsonProperty(access = Access.WRITE_ONLY)} annotation on
     * {@code secUsrPwd}, this ensures the BCrypt hash is NEVER exposed
     * in API responses.</p>
     *
     * <p>This is a security-critical test — the COBOL source stored plaintext
     * passwords that could be read back from the screen map. The Java migration
     * explicitly prevents any password leakage.</p>
     */
    @Test
    void testAddUser_passwordNeverInReturnedDto() {
        when(userSecurityRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserSecurityDto result = userAddService.addUser(validDto);

        // Password must be null in returned DTO — defense-in-depth security
        assertThat(result.getSecUsrPwd()).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Transactional Behavior
    // Maps CICS implicit unit-of-work semantics for WRITE operations
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Test 14: Verify {@code addUser()} is annotated with {@code @Transactional}.
     *
     * <p>Maps the CICS implicit unit-of-work semantics — the COBOL WRITE
     * operation in COUSR01C.cbl is atomic within the CICS task boundary.
     * The Java equivalent uses Spring's {@code @Transactional} annotation
     * to ensure all-or-nothing behavior for the create operation.</p>
     *
     * <p>Uses reflection to verify the annotation is present on the
     * {@code addUser(UserSecurityDto)} method, confirming transactional
     * behavior is configured at the service layer.</p>
     *
     * @throws Exception if reflection access to the method fails
     */
    @Test
    void testAddUser_transactional() throws Exception {
        Method addUserMethod = UserAddService.class.getMethod(
                "addUser", UserSecurityDto.class);

        Transactional transactionalAnnotation =
                addUserMethod.getAnnotation(Transactional.class);

        assertThat(transactionalAnnotation).isNotNull();
    }
}
