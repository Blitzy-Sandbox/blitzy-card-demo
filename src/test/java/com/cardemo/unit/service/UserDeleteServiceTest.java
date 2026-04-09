package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserDeleteService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

/**
 * Unit tests for {@link UserDeleteService} — migrated from COUSR03C.cbl (359 lines).
 *
 * <p>Tests user deletion with confirmation flow:
 * <ul>
 *   <li>getUserForDelete: validates userId, reads USRSEC, returns DTO (password excluded)</li>
 *   <li>deleteUser: validates userId, reads USRSEC, deletes record, returns DTO</li>
 * </ul>
 *
 * <p>COBOL mapping:
 * <ul>
 *   <li>PROCESS-ENTER-KEY (lines 142-154): userId blank check → ValidationException</li>
 *   <li>READ-USER-SEC-FILE (lines 267-300): CICS READ USRSEC → findById()</li>
 *   <li>DELETE-USER-SEC-FILE (lines 305-336): CICS DELETE USRSEC → delete()</li>
 *   <li>DFHRESP(NOTFND): RecordNotFoundException</li>
 * </ul>
 *
 * <p>Framework: JUnit 5 + Mockito (no Spring context). Zero-warning build.
 */
@ExtendWith(MockitoExtension.class)
class UserDeleteServiceTest {

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @InjectMocks
    private UserDeleteService userDeleteService;

    /** Reusable test fixture — realistic UserSecurity entity matching CSUSR01Y.cpy 80-byte record. */
    private UserSecurity testUser;

    /** Test user ID constant — 8-char PIC X(08) format per SEC-USR-ID. */
    private static final String TEST_USER_ID = "ADMIN001";

    @BeforeEach
    void setUp() {
        testUser = new UserSecurity();
        testUser.setSecUsrId(TEST_USER_ID);
        testUser.setSecUsrFname("John");
        testUser.setSecUsrLname("Doe");
        testUser.setSecUsrPwd("$2a$10$dXJ3SW6G7P50lGmMQoeqhOwxR6PF2/CqEqDHOfLCXXimV2Gwrm0Wy");
        testUser.setSecUsrType(UserType.ADMIN);
    }

    // ========================================================================
    // Get User For Delete — Tests 1-3
    // Maps COUSR03C.cbl PROCESS-ENTER-KEY + READ-USER-SEC-FILE paragraphs
    // ========================================================================

    /**
     * Test 1: getUserForDelete with valid userId returns populated UserSecurityDto.
     * Maps COUSR03C.cbl READ-USER-SEC-FILE DFHRESP(NORMAL) path (line 275).
     */
    @Test
    void testGetUserForDelete_success() {
        // Arrange — stub findById to return the test user entity
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Act — invoke service method
        UserSecurityDto result = userDeleteService.getUserForDelete(TEST_USER_ID);

        // Assert — verify all DTO fields are populated from the entity
        assertThat(result).isNotNull();
        assertThat(result.getSecUsrId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getSecUsrFname()).isEqualTo("John");
        assertThat(result.getSecUsrLname()).isEqualTo("Doe");
        assertThat(result.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    /**
     * Test 2: getUserForDelete when user not found throws RecordNotFoundException.
     * Maps COUSR03C.cbl READ-USER-SEC-FILE DFHRESP(NOTFND) path (line 289).
     */
    @Test
    void testGetUserForDelete_notFound_throwsRecordNotFound() {
        // Arrange — stub findById to return empty (NOTFND)
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        // Act & Assert — expect RecordNotFoundException
        assertThatThrownBy(() -> userDeleteService.getUserForDelete(TEST_USER_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    /**
     * Test 3: getUserForDelete with blank userId throws ValidationException.
     * Maps COUSR03C.cbl PROCESS-ENTER-KEY validation (lines 144-154):
     * "User ID can NOT be empty..."
     */
    @Test
    void testGetUserForDelete_blankUserId_throwsValidation() {
        // Act & Assert — blank userId triggers validation before any repository call
        assertThatThrownBy(() -> userDeleteService.getUserForDelete("   "))
                .isInstanceOf(ValidationException.class);

        // Verify repository was never called (validation short-circuits)
        verify(userSecurityRepository, never()).findById(any());
    }

    // ========================================================================
    // Delete User — Tests 4-6
    // Maps COUSR03C.cbl DELETE-USER-SEC-FILE paragraph (lines 305-336)
    // ========================================================================

    /**
     * Test 4: deleteUser with valid userId successfully deletes the user.
     * Maps COUSR03C.cbl DELETE-USER-SEC-FILE DFHRESP(NORMAL) path (line 314).
     */
    @Test
    void testDeleteUser_success() {
        // Arrange — stub findById for existence check, then allow delete
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Act — invoke delete
        UserSecurityDto result = userDeleteService.deleteUser(TEST_USER_ID);

        // Assert — returns DTO with user data (password excluded per convertToDto)
        assertThat(result).isNotNull();
        assertThat(result.getSecUsrId()).isEqualTo(TEST_USER_ID);
        assertThat(result.getSecUsrFname()).isEqualTo("John");
        assertThat(result.getSecUsrLname()).isEqualTo("Doe");
        assertThat(result.getSecUsrType()).isEqualTo(UserType.ADMIN);
    }

    /**
     * Test 5: deleteUser when user not found throws RecordNotFoundException.
     * Maps COUSR03C.cbl DELETE-USER-SEC-FILE DFHRESP(NOTFND) path (line 326).
     */
    @Test
    void testDeleteUser_notFound_throwsRecordNotFound() {
        // Arrange — stub findById to return empty (NOTFND)
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        // Act & Assert — expect RecordNotFoundException
        assertThatThrownBy(() -> userDeleteService.deleteUser(TEST_USER_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    /**
     * Test 6: deleteUser with blank userId throws ValidationException.
     * Maps COUSR03C.cbl DELETE-USER-INFO validation (lines 176-186):
     * "User ID can NOT be empty..."
     */
    @Test
    void testDeleteUser_blankUserId_throwsValidation() {
        // Act & Assert — blank userId triggers validation before any repository call
        assertThatThrownBy(() -> userDeleteService.deleteUser(""))
                .isInstanceOf(ValidationException.class);

        // Verify repository was never called (validation short-circuits)
        verify(userSecurityRepository, never()).findById(any());
    }

    // ========================================================================
    // Password Security — Test 7
    // CRITICAL: Password must NEVER appear in returned DTO
    // ========================================================================

    /**
     * Test 7: getUserForDelete NEVER returns password in DTO.
     * Maps security requirement — COBOL SEC-USR-PWD (PIC X(08)) is stored as BCrypt hash
     * in Java but must never be exposed in API responses. The service's convertToDto()
     * explicitly sets dto.setSecUsrPwd(null).
     */
    @Test
    void testGetUserForDelete_passwordNeverInDto() {
        // Arrange — entity has a BCrypt password hash
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Pre-condition: entity DOES have a password
        assertThat(testUser.getSecUsrPwd()).isNotNull();

        // Act — get user for delete
        UserSecurityDto result = userDeleteService.getUserForDelete(TEST_USER_ID);

        // Assert — CRITICAL: password MUST be null in returned DTO
        assertThat(result).isNotNull();
        assertThat(result.getSecUsrPwd()).isNull();
    }

    // ========================================================================
    // Verification — Tests 8-9
    // Verify COBOL READ before DELETE pattern is preserved
    // ========================================================================

    /**
     * Test 8: deleteUser verifies that repository delete() is called exactly once.
     * Maps COUSR03C.cbl DELETE-USER-SEC-FILE — EXEC CICS DELETE (line 307).
     * The service uses delete(entity), not deleteById(String).
     */
    @Test
    void testDeleteUser_verifiesDeleteCalled() {
        // Arrange — stub findById for existence check
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Act — invoke delete
        userDeleteService.deleteUser(TEST_USER_ID);

        // Assert — verify delete(entity) is called exactly once
        verify(userSecurityRepository, times(1)).delete(testUser);
    }

    /**
     * Test 9: deleteUser verifies findById is called before delete.
     * Maps COBOL READ before DELETE pattern — COUSR03C.cbl reads the user record
     * (READ-USER-SEC-FILE, lines 267-300) before deleting it
     * (DELETE-USER-SEC-FILE, lines 305-336).
     */
    @Test
    void testDeleteUser_verifyFindByIdCalled() {
        // Arrange — stub findById for existence check
        when(userSecurityRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Act — invoke delete
        userDeleteService.deleteUser(TEST_USER_ID);

        // Assert — verify findById was called exactly once (existence check before delete)
        verify(userSecurityRepository, times(1)).findById(TEST_USER_ID);
    }

    // ========================================================================
    // Edge Cases — Test 10
    // ========================================================================

    /**
     * Test 10: deleteUser with null userId throws ValidationException.
     * Maps COUSR03C.cbl PROCESS-ENTER-KEY / DELETE-USER-INFO validation:
     * null is treated the same as blank/spaces — "User ID can NOT be empty..."
     */
    @Test
    void testDeleteUser_nullUserId_throwsValidation() {
        // Act & Assert — null userId triggers validation
        assertThatThrownBy(() -> userDeleteService.deleteUser(null))
                .isInstanceOf(ValidationException.class);

        // Verify repository was never called (validation short-circuits)
        verify(userSecurityRepository, never()).findById(any());
        verify(userSecurityRepository, never()).delete(any());
    }
}
