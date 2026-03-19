/*
 * UserUpdateServiceTest.java — Unit Tests for User Update (COUSR02C.cbl)
 *
 * JUnit 5 + Mockito unit tests for UserUpdateService — tests COUSR02C.cbl (414 lines)
 * user record modification: getUserForUpdate(String) for read-before-update display,
 * and updateUser(String, UserSecurityDto) for 5-field validation (COBOL order:
 * USERID→FNAME→LNAME→PWD→TYPE), change detection with WS-USR-MODIFIED flag pattern,
 * BCrypt password change detection via passwordEncoder.matches(), and password
 * exclusion from returned DTOs.
 *
 * COBOL Paragraph Coverage (tested through UserUpdateService methods):
 *   PROCESS-ENTER-KEY   (line 143) → tests 1-3 (getUserForUpdate validation + read)
 *   UPDATE-USER-INFO    (line 177) → tests 4-18 (5-field validation, change detection,
 *                                      BCrypt comparison, persistence, DTO security)
 *   READ-USER-SEC-FILE  (line 320) → tests 1-2, 9-18 (findById with NOTFND mapping)
 *   UPDATE-USER-SEC-FILE(line 358) → tests 10-13, 16-18 (save/REWRITE on modification)
 *
 * Testing Framework:
 *   - JUnit 5 with @ExtendWith(MockitoExtension.class) — NO Spring context loading
 *   - Mockito @Mock for UserSecurityRepository and PasswordEncoder
 *   - Mockito @InjectMocks for UserUpdateService (constructor injection)
 *   - AssertJ assertThat/assertThatThrownBy for fluent assertions
 *
 * COBOL Source References (original repository commit SHA 27d6c6f):
 *   - app/cbl/COUSR02C.cbl — User Update program (414 lines, CICS transaction CU02)
 *   - app/cpy/CSUSR01Y.cpy — SEC-USER-DATA record layout (80 bytes)
 *   - app/cpy-bms/COUSR02.CPY — BMS symbolic map for user update screen
 *   - app/bms/COUSR02.bms — BMS mapset definition for COUSR2A map
 */
package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserUpdateService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserUpdateService} — pure Mockito-based tests with
 * no Spring context loading.
 *
 * <p>Validates all COUSR02C.cbl user update logic paths migrated into
 * {@code UserUpdateService.getUserForUpdate(String)} and
 * {@code UserUpdateService.updateUser(String, UserSecurityDto)}, including:</p>
 * <ul>
 *   <li>User ID blank validation (maps PROCESS-ENTER-KEY line 145)</li>
 *   <li>5-field validation cascade in COBOL order: USERID→FNAME→LNAME→PWD→TYPE</li>
 *   <li>Change detection with modified flag (maps WS-USR-MODIFIED 88-level)</li>
 *   <li>BCrypt password change detection replacing plaintext comparison (line 227)</li>
 *   <li>Password NEVER returned in DTO (security upgrade from COBOL plaintext display)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserUpdateServiceTest {

    // -----------------------------------------------------------------------
    // Mocks — replaces VSAM I/O and BCrypt infrastructure
    // -----------------------------------------------------------------------

    /**
     * Mocked UserSecurityRepository — provides findById(String) stubs
     * for user lookup and save(UserSecurity) stubs for persistence.
     * Maps CICS READ DATASET(USRSEC) at lines 322-331 and CICS REWRITE
     * at lines 360-366 in COUSR02C.cbl.
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Mocked PasswordEncoder — provides matches(CharSequence, String) stubs
     * for BCrypt password change detection and encode(CharSequence) stubs for
     * re-hashing. Replaces COBOL plaintext comparison at line 227:
     * {@code PASSWDI OF COUSR2AI NOT = SEC-USR-PWD}.
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Service under test — UserUpdateService with mocked dependencies injected
     * via Mockito @InjectMocks (constructor injection). Maps COBOL program
     * COUSR02C (CICS transaction CU02).
     */
    @InjectMocks
    private UserUpdateService userUpdateService;

    // -----------------------------------------------------------------------
    // Test Fixtures — realistic data matching CSUSR01Y.cpy 80-byte record
    // -----------------------------------------------------------------------

    /** Reusable test entity — represents the existing user in the USRSEC dataset. */
    private UserSecurity existingUser;

    /** Reusable test DTO — represents the update request from the API client. */
    private UserSecurityDto updateDto;

    /** Test user ID constant — 8-char PIC X(08) format per SEC-USR-ID. */
    private static final String TEST_USER_ID = "USER0001";

    /** Stored BCrypt hash — represents the password hash in the database. */
    private static final String STORED_BCRYPT_HASH =
            "$2a$10$dXJ3SW6G7P50lGmMQoeqhOwxR6PF2/CqEqDHOfLCXXimV2Gwrm0Wy";

    /**
     * Sets up fresh test fixtures before each test method.
     *
     * <p>Creates a UserSecurity entity representing an existing user record in the
     * USRSEC VSAM dataset (maps CSUSR01Y.cpy SEC-USER-DATA structure) and a
     * UserSecurityDto representing the update input from the API client (maps
     * COUSR2AI BMS symbolic map fields).</p>
     *
     * <p>Default setup: all DTO fields match the entity fields, so tests that
     * need changes must explicitly set different values. The DTO password is
     * the raw plaintext "password" while the entity stores the BCrypt hash.</p>
     */
    @BeforeEach
    void setUp() {
        // Entity — existing user in the database (BCrypt-hashed password)
        existingUser = new UserSecurity();
        existingUser.setSecUsrId(TEST_USER_ID);
        existingUser.setSecUsrFname("John");
        existingUser.setSecUsrLname("Doe");
        existingUser.setSecUsrPwd(STORED_BCRYPT_HASH);
        existingUser.setSecUsrType(UserType.USER);

        // DTO — update request from client (raw plaintext password)
        // Default: all fields match entity (no changes) — tests override as needed
        updateDto = new UserSecurityDto();
        updateDto.setSecUsrId(TEST_USER_ID);
        updateDto.setSecUsrFname("John");
        updateDto.setSecUsrLname("Doe");
        updateDto.setSecUsrPwd("password");
        updateDto.setSecUsrType(UserType.USER);
    }

    // ========================================================================
    // Get User For Update — Tests 1-3
    // Maps COUSR02C.cbl PROCESS-ENTER-KEY + READ-USER-SEC-FILE paragraphs
    // ========================================================================

    /**
     * Test 1: getUserForUpdate with valid userId returns populated UserSecurityDto.
     *
     * <p>Maps COUSR02C.cbl READ-USER-SEC-FILE DFHRESP(NORMAL) path (line 333).
     * Verifies the returned DTO contains all entity fields with password set to
     * {@code null} (security requirement — COBOL displayed plaintext at line 169,
     * Java NEVER exposes the BCrypt hash).</p>
     */
    @Test
    void testGetUserForUpdate_success() {
        // Arrange — stub findById to return the test user entity
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));

        // Act — invoke service method
        UserSecurityDto result = userUpdateService.getUserForUpdate(TEST_USER_ID);

        // Assert — verify all DTO fields are populated from the entity
        assertThat(result).isNotNull();
        assertThat(result.getSecUsrId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getSecUsrFname()).isEqualTo("John");
        assertThat(result.getSecUsrLname()).isEqualTo("Doe");
        assertThat(result.getSecUsrType()).isEqualTo(UserType.USER);
        // CRITICAL: password must NEVER be in the DTO
        assertThat(result.getSecUsrPwd()).isNull();
    }

    /**
     * Test 2: getUserForUpdate when user not found throws RecordNotFoundException.
     *
     * <p>Maps COUSR02C.cbl READ-USER-SEC-FILE DFHRESP(NOTFND) path (line 342).
     * COBOL: "User ID NOT found..." message. Java: RecordNotFoundException thrown
     * when findById returns empty Optional (FILE STATUS 23 equivalent).</p>
     */
    @Test
    void testGetUserForUpdate_notFound_throwsRecordNotFound() {
        // Arrange — stub findById to return empty (NOTFND)
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        // Act & Assert — expect RecordNotFoundException
        assertThatThrownBy(() -> userUpdateService.getUserForUpdate(TEST_USER_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    /**
     * Test 3: getUserForUpdate with blank userId throws ValidationException.
     *
     * <p>Maps COUSR02C.cbl PROCESS-ENTER-KEY validation (lines 145-155):
     * {@code WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES}
     * → "User ID can NOT be empty..."</p>
     */
    @Test
    void testGetUserForUpdate_blankUserId_throwsValidation() {
        // Act & Assert — blank userId triggers validation before any repository call
        assertThatThrownBy(() -> userUpdateService.getUserForUpdate("   "))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID");

        // Verify repository was never called (validation short-circuits)
        verify(userSecurityRepository, never()).findById(any());
    }

    // ========================================================================
    // Field Validations — Tests 4-8
    // Maps COUSR02C.cbl UPDATE-USER-INFO validation cascade (lines 179-213)
    // EXACT COBOL ORDER: USERID → FNAME → LNAME → PWD → TYPE
    // ========================================================================

    /**
     * Test 4: updateUser with blank userId throws ValidationException.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-INFO validation (lines 180-185):
     * {@code WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES}
     * → "User ID can NOT be empty..."
     * First field in the 5-field COBOL EVALUATE TRUE cascade.</p>
     */
    @Test
    void testUpdateUser_blankUserId_throwsValidation() {
        // Act & Assert — blank userId is the first validation in COBOL order
        assertThatThrownBy(() -> userUpdateService.updateUser("   ", updateDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("User ID");

        // Verify — validation short-circuits before repository access
        verify(userSecurityRepository, never()).findById(any());
    }

    /**
     * Test 5: updateUser with blank first name throws ValidationException.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-INFO validation (lines 186-191):
     * {@code WHEN FNAMEI OF COUSR2AI = SPACES OR LOW-VALUES}
     * → "First Name can NOT be empty..."
     * Second field in the 5-field COBOL EVALUATE TRUE cascade.</p>
     */
    @Test
    void testUpdateUser_blankFirstName_throwsValidation() {
        // Arrange — set first name to blank
        updateDto.setSecUsrFname("   ");

        // Act & Assert
        assertThatThrownBy(() -> userUpdateService.updateUser(TEST_USER_ID, updateDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("First Name");

        // Verify — validation short-circuits before repository access
        verify(userSecurityRepository, never()).findById(any());
    }

    /**
     * Test 6: updateUser with blank last name throws ValidationException.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-INFO validation (lines 192-197):
     * {@code WHEN LNAMEI OF COUSR2AI = SPACES OR LOW-VALUES}
     * → "Last Name can NOT be empty..."
     * Third field in the 5-field COBOL EVALUATE TRUE cascade.</p>
     */
    @Test
    void testUpdateUser_blankLastName_throwsValidation() {
        // Arrange — set last name to blank
        updateDto.setSecUsrLname("   ");

        // Act & Assert
        assertThatThrownBy(() -> userUpdateService.updateUser(TEST_USER_ID, updateDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Last Name");

        // Verify — validation short-circuits before repository access
        verify(userSecurityRepository, never()).findById(any());
    }

    /**
     * Test 7: updateUser with blank password throws ValidationException.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-INFO validation (lines 198-203):
     * {@code WHEN PASSWDI OF COUSR2AI = SPACES OR LOW-VALUES}
     * → "Password can NOT be empty..."
     * Fourth field in the 5-field COBOL EVALUATE TRUE cascade.</p>
     */
    @Test
    void testUpdateUser_blankPassword_throwsValidation() {
        // Arrange — set password to blank
        updateDto.setSecUsrPwd("   ");

        // Act & Assert
        assertThatThrownBy(() -> userUpdateService.updateUser(TEST_USER_ID, updateDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password");

        // Verify — validation short-circuits before repository access
        verify(userSecurityRepository, never()).findById(any());
    }

    /**
     * Test 8: updateUser with null user type preserves existing type (partial update).
     *
     * <p>With partial update support, null fields mean "keep existing value."
     * A null userType in the DTO should not cause a validation error — instead,
     * the service preserves the existing user's type. When combined with a
     * real field change (e.g., firstName), the update succeeds and the original
     * type remains unchanged.</p>
     */
    @Test
    void testUpdateUser_nullUserType_preservesExistingType() {
        // Arrange — null type with a real first-name change
        updateDto.setSecUsrType(null);
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        UserSecurityDto result = userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — user type remains USER (original value preserved)
        assertThat(result).isNotNull();
        verify(userSecurityRepository).save(argThat(saved ->
                saved.getSecUsrType() == UserType.USER));
    }

    // ========================================================================
    // Change Detection — Tests 9-12
    // Maps COUSR02C.cbl UPDATE-USER-INFO change detection (lines 219-243)
    // COBOL: WS-USR-MODIFIED flag with 88-level USR-MODIFIED-YES/NO
    // ========================================================================

    /**
     * Test 9: updateUser with no field changes throws ValidationException.
     *
     * <p>Maps COUSR02C.cbl lines 236-242: when WS-USR-MODIFIED remains 'N'
     * (USR-MODIFIED-NO), the program moves "Please modify to update ..." to
     * WS-MESSAGE. Java: if {@code modified == false}, throws
     * {@code ValidationException("Please modify to update ...")}.</p>
     *
     * <p>All DTO fields match the entity: fname="John", lname="Doe",
     * userType=USER, and password matches (passwordEncoder.matches returns true).
     * Since no field has changed, modified stays false → exception thrown.</p>
     */
    @Test
    void testUpdateUser_noChanges_throwsValidation() {
        // Arrange — all fields match entity, password matches (same password)
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);

        // Act & Assert — no changes detected → "Please modify to update ..."
        assertThatThrownBy(() -> userUpdateService.updateUser(TEST_USER_ID, updateDto))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Please modify to update");

        // Verify — save was NEVER called (no modifications to persist)
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    /**
     * Test 10: updateUser with first name changed triggers save.
     *
     * <p>Maps COUSR02C.cbl lines 219-222:
     * {@code IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME}
     * → {@code MOVE FNAMEI TO SEC-USR-FNAME, SET USR-MODIFIED-YES TO TRUE}
     * Java: isFieldChanged() detects trimmed difference → entity updated,
     * modified=true → save called.</p>
     */
    @Test
    void testUpdateUser_firstNameChanged_savesUpdate() {
        // Arrange — only first name is different
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — save was called exactly once (modification detected)
        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }

    /**
     * Test 11: updateUser with last name changed triggers save.
     *
     * <p>Maps COUSR02C.cbl lines 223-226:
     * {@code IF LNAMEI OF COUSR2AI NOT = SEC-USR-LNAME}
     * → {@code MOVE LNAMEI TO SEC-USR-LNAME, SET USR-MODIFIED-YES TO TRUE}</p>
     */
    @Test
    void testUpdateUser_lastNameChanged_savesUpdate() {
        // Arrange — only last name is different
        updateDto.setSecUsrLname("Smith");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — save was called exactly once
        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }

    /**
     * Test 12: updateUser with user type changed triggers save.
     *
     * <p>Maps COUSR02C.cbl lines 231-234:
     * {@code IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE}
     * → {@code MOVE USRTYPEI TO SEC-USR-TYPE, SET USR-MODIFIED-YES TO TRUE}
     * Java: Objects.equals() detects enum difference → entity updated,
     * modified=true → save called.</p>
     */
    @Test
    void testUpdateUser_userTypeChanged_savesUpdate() {
        // Arrange — user type changed from USER to ADMIN
        updateDto.setSecUsrType(UserType.ADMIN);
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — save was called exactly once
        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }

    // ========================================================================
    // BCrypt Password Change Detection — Tests 13-15
    // Maps COUSR02C.cbl line 227 (plaintext compare → BCrypt matches)
    // ========================================================================

    /**
     * Test 13: updateUser with changed password triggers re-encoding.
     *
     * <p>Maps COUSR02C.cbl lines 227-230:
     * {@code IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD}
     * → {@code MOVE PASSWDI TO SEC-USR-PWD, SET USR-MODIFIED-YES TO TRUE}
     * Java upgrade: when passwordEncoder.matches() returns false (different password),
     * the new password is re-hashed via passwordEncoder.encode() before saving.</p>
     */
    @Test
    void testUpdateUser_passwordChanged_reencoded() {
        // Arrange — new password "newpass" differs from stored hash
        updateDto.setSecUsrPwd("newpass");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("newpass", STORED_BCRYPT_HASH)).thenReturn(false);
        when(passwordEncoder.encode("newpass")).thenReturn("$2a$10$newEncodedHashForNewPass");
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — encode was called with the new plaintext password
        verify(passwordEncoder, times(1)).encode("newpass");
    }

    /**
     * Test 14: updateUser with unchanged password does NOT trigger re-encoding.
     *
     * <p>When passwordEncoder.matches() returns true (same password), the password
     * has NOT changed and should NOT be re-hashed. This preserves the existing
     * BCrypt hash and avoids unnecessary hash computation.</p>
     *
     * <p>A different field (first name) is changed to ensure the update proceeds
     * (modified=true) so the test can verify encode() was never called.</p>
     */
    @Test
    void testUpdateUser_passwordUnchanged_notReencoded() {
        // Arrange — password matches (same), but first name changed for modified=true
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — encode was NEVER called (password did not change)
        verify(passwordEncoder, never()).encode(any());
    }

    /**
     * Test 15: updateUser uses passwordEncoder.matches() for password change detection.
     *
     * <p>Verifies that BCrypt matches() is the mechanism for detecting password
     * changes, replacing the COBOL plaintext comparison at line 227. This is the
     * core security upgrade from C-003 (plaintext storage) to BCrypt hashing.</p>
     *
     * <p>A different field (first name) is changed to ensure the update proceeds
     * past change detection without triggering the "no changes" exception.</p>
     */
    @Test
    void testUpdateUser_passwordChangeDetectionUsesBCryptMatches() {
        // Arrange — first name changed so update proceeds, password same
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — matches() was called with the raw DTO password and stored hash
        verify(passwordEncoder, times(1)).matches("password", STORED_BCRYPT_HASH);
    }

    // ========================================================================
    // Successful Update — Tests 16-17
    // Maps COUSR02C.cbl UPDATE-USER-SEC-FILE (lines 358-390)
    // ========================================================================

    /**
     * Test 16: updateUser successfully saves to repository on modification.
     *
     * <p>Maps COUSR02C.cbl UPDATE-USER-SEC-FILE DFHRESP(NORMAL) path (line 369):
     * {@code EXEC CICS REWRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)}
     * → "User [userId] has been updated..."
     * Java: userSecurityRepository.save() called when modified=true.</p>
     */
    @Test
    void testUpdateUser_success_savedToRepository() {
        // Arrange — first name changed triggers modification
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — save was called exactly once
        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }

    /**
     * Test 17: updateUser returns updated UserSecurityDto with correct field values.
     *
     * <p>Maps COUSR02C.cbl field population after successful REWRITE: the service
     * converts the saved entity to a DTO with all fields populated (except password,
     * which is always null). Verifies the returned DTO reflects the updated values.</p>
     */
    @Test
    void testUpdateUser_success_returnsUpdatedDto() {
        // Arrange — first name changed to "Jane"
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Act
        UserSecurityDto result = userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — DTO contains updated values
        assertThat(result).isNotNull();
        assertThat(result.getSecUsrId()).isEqualTo(TEST_USER_ID);
        // First name changed to "Jane" — entity was modified in-place before save
        assertThat(result.getSecUsrFname()).isEqualTo("Jane");
        assertThat(result.getSecUsrLname()).isEqualTo("Doe");
        assertThat(result.getSecUsrType()).isEqualTo(UserType.USER);
    }

    // ========================================================================
    // Password Security — Test 18
    // CRITICAL: Password must NEVER appear in returned DTO
    // Maps security upgrade from COBOL plaintext display (line 169)
    // ========================================================================

    /**
     * Test 18: updateUser NEVER returns password in the DTO.
     *
     * <p>CRITICAL security requirement: the COBOL program at line 169
     * ({@code MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI}) displays the plaintext
     * password on the 3270 screen. In the Java migration, the BCrypt hash must
     * NEVER be exposed in API responses. The service's convertToDto() method
     * explicitly sets {@code dto.setSecUsrPwd(null)}.</p>
     *
     * <p>This test verifies the password is null in the returned DTO even though
     * the entity has a valid BCrypt hash in the database.</p>
     */
    @Test
    void testUpdateUser_passwordNeverInDto() {
        // Arrange — first name changed so update proceeds
        updateDto.setSecUsrFname("Jane");
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password", STORED_BCRYPT_HASH)).thenReturn(true);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenReturn(existingUser);

        // Pre-condition: entity DOES have a password hash stored
        assertThat(existingUser.getSecUsrPwd()).isNotNull();

        // Act
        UserSecurityDto result = userUpdateService.updateUser(TEST_USER_ID, updateDto);

        // Assert — CRITICAL: password is NEVER in the returned DTO
        assertThat(result.getSecUsrPwd()).isNull();
    }
}
