package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserAddRequest;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.admin.UserAddService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for {@link com.carddemo.service.admin.UserAddService} (&larr; COBOL COUSR01C, SHA 27d6c6f). */
@ExtendWith(MockitoExtension.class)
class UserAddServiceTest {

    /** First name used across the happy-path requests (BMS {@code FNAME}). */
    private static final String FIRST_NAME = "Ann";

    /** Last name used across the happy-path requests (BMS {@code LNAME}). */
    private static final String LAST_NAME = "Adams";

    /** User id used across the happy-path requests (BMS {@code USERID}, {@code SEC-USR-ID}). */
    private static final String USER_ID = "USER0001";

    /** Plaintext password submitted on input; must never be the value that is persisted. */
    private static final String PLAINTEXT_PASSWORD = "pass1234";

    /**
     * Obviously-fake, BCrypt-shaped placeholder returned by the mocked encoder. It is not a real
     * hash of anything and carries no secret; it only proves the stored credential is the encoder
     * output (constraint C-003) rather than the plaintext.
     */
    private static final String ENCODED_PASSWORD = "$2a$10$ENCODEDHASHVALUE...";

    /** Expected confirmation message for {@link #USER_ID} (COBOL {@code COUSR01C} {@code NORMAL} branch). */
    private static final String EXPECTED_ADDED_MESSAGE = "User USER0001 has been added ...";

    /** Repository collaborator (the re-platformed VSAM {@code USRSEC} KSDS). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** External Spring BCrypt encoder; mocked so the test stays a fast JVM-only unit test. */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Service under test, with both mocks injected through its two-argument constructor. */
    @InjectMocks
    private UserAddService userAddService;

    /** Captures the entity handed to {@code save(...)} so the persisted fields can be asserted. */
    @Captor
    private ArgumentCaptor<UserSecurity> entityCaptor;

    /**
     * Builds a {@link UserAddRequest} with the record's component order
     * (firstName, lastName, userId, password, userType).
     *
     * @param firstName the first name component
     * @param lastName  the last name component
     * @param userId    the user id component
     * @param password  the plaintext password component
     * @param userType  the user type component
     * @return a new positional {@link UserAddRequest}
     */
    private static UserAddRequest request(String firstName, String lastName, String userId,
            String password, UserType userType) {
        return new UserAddRequest(firstName, lastName, userId, password, userType);
    }

    /**
     * Returns a fully valid add request that passes every required-field edit.
     *
     * @return a valid {@link UserAddRequest} for {@link #USER_ID}
     */
    private static UserAddRequest valid() {
        return request(FIRST_NAME, LAST_NAME, USER_ID, PLAINTEXT_PASSWORD, UserType.ADMIN);
    }

    @Test
    void addUser_happyPath_encodesPasswordAndSaves() {
        UserAddRequest req = valid();
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse resp = userAddService.addUser(req);

        // Response echoes the request (without any password) and carries the COBOL success message.
        assertThat(resp.userId()).isEqualTo(USER_ID);
        assertThat(resp.firstName()).isEqualTo(FIRST_NAME);
        assertThat(resp.lastName()).isEqualTo(LAST_NAME);
        assertThat(resp.userType()).isEqualTo(UserType.ADMIN);
        assertThat(resp.message()).isEqualTo(EXPECTED_ADDED_MESSAGE);
        assertThat(resp.errorMessage()).isNull();

        // C-003 proof: the plaintext is BCrypt-encoded and the ENCODED hash is what gets persisted.
        verify(passwordEncoder).encode(PLAINTEXT_PASSWORD);
        verify(userSecurityRepository).save(entityCaptor.capture());
        UserSecurity saved = entityCaptor.getValue();
        assertThat(saved.getSecUsrPwd()).isEqualTo(ENCODED_PASSWORD);
        assertThat(saved.getSecUsrPwd()).isNotEqualTo(PLAINTEXT_PASSWORD);
        assertThat(saved.getSecUsrId()).isEqualTo(USER_ID);
        assertThat(saved.getSecUsrFname()).isEqualTo(FIRST_NAME);
        assertThat(saved.getSecUsrLname()).isEqualTo(LAST_NAME);
        assertThat(saved.getSecUsrType()).isEqualTo(UserType.ADMIN);

        // Ordering: the duplicate-key lookup must run before the password encode and the save.
        InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
        inOrder.verify(userSecurityRepository).findBySecUsrId(USER_ID);
        inOrder.verify(passwordEncoder).encode(PLAINTEXT_PASSWORD);
        inOrder.verify(userSecurityRepository).save(any(UserSecurity.class));
    }

    @Test
    void addUser_duplicateId_throwsDuplicateRecordException() {
        UserSecurity existing = new UserSecurity();
        existing.setSecUsrId(USER_ID);
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userAddService.addUser(valid()))
                .isInstanceOfSatisfying(DuplicateRecordException.class,
                        ex -> assertThat(ex.getFileStatus()).isEqualTo("22"))
                .hasMessage("User ID already exist...");

        // A duplicate is rejected before any encoding or persistence occurs.
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void addUser_blankFirstName_throwsValidation() {
        UserAddRequest req = request(null, LAST_NAME, USER_ID, PLAINTEXT_PASSWORD, UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("firstName"))
                .hasMessage("First Name can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    void addUser_blankLastName_throwsValidation() {
        UserAddRequest req = request(FIRST_NAME, "", USER_ID, PLAINTEXT_PASSWORD, UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("lastName"))
                .hasMessage("Last Name can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    void addUser_blankUserId_throwsValidation() {
        // A whitespace-only value exercises the String.isBlank() branch of the blank test.
        UserAddRequest req = request(FIRST_NAME, LAST_NAME, "   ", PLAINTEXT_PASSWORD, UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage("User ID can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    void addUser_blankPassword_throwsValidation() {
        UserAddRequest req = request(FIRST_NAME, LAST_NAME, USER_ID, "", UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("password"))
                .hasMessage("Password can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    void addUser_nullUserType_throwsValidation() {
        UserAddRequest req = request(FIRST_NAME, LAST_NAME, USER_ID, PLAINTEXT_PASSWORD, null);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userType"))
                .hasMessage("User Type can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    void addUser_userTypeRoundTrips_forAdmin() {
        assertUserTypeRoundTrips(UserType.ADMIN);
    }

    @Test
    void addUser_userTypeRoundTrips_forUser() {
        assertUserTypeRoundTrips(UserType.USER);
    }

    @Test
    void addUser_responseNeverContainsPassword() {
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse resp = userAddService.addUser(valid());

        // UserResponse structurally has no password component; assert neither secret leaks via text.
        assertThat(resp.message()).doesNotContain(PLAINTEXT_PASSWORD).doesNotContain(ENCODED_PASSWORD);
        assertThat(resp.errorMessage()).isNull();
    }

    /**
     * Drives a happy-path add for the supplied user type and asserts the enum is moved through to
     * both the persisted entity and the response unchanged.
     *
     * @param userType the user type to round-trip ({@link UserType#ADMIN} or {@link UserType#USER})
     */
    private void assertUserTypeRoundTrips(UserType userType) {
        UserAddRequest req = request(FIRST_NAME, LAST_NAME, USER_ID, PLAINTEXT_PASSWORD, userType);
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PLAINTEXT_PASSWORD)).thenReturn(ENCODED_PASSWORD);
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse resp = userAddService.addUser(req);

        verify(userSecurityRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSecUsrType()).isEqualTo(userType);
        assertThat(resp.userType()).isEqualTo(userType);
    }
}
