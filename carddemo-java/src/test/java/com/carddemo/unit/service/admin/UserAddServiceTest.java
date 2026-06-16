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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for {@link com.carddemo.service.admin.UserAddService} (&larr; COBOL COUSR01C, SHA 27d6c6f). */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService - COUSR01C add-user: ordered validation, duplicate guard, BCrypt encode-and-save")
class UserAddServiceTest {

    /** Eight-character user id (SEC-USR-ID PIC X(08)) reused across the add tests. */
    private static final String USER_ID = "USER0001";

    /** Plaintext credential entered on the add-user screen (COBOL SEC-USR-PWD PIC X(08)); test fixture only. */
    private static final String PLAINTEXT_PWD = "pass1234";

    /** Clearly-fake stand-in for the BCrypt hash returned by the encoder; never a real credential. */
    private static final String ENCODED_HASH = "$2a$10$ENCODEDHASHVALUE...";

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserAddService userAddService;

    @Captor
    private ArgumentCaptor<UserSecurity> entityCaptor;

    @Test
    @DisplayName("happy path: validates, BCrypt-encodes the password, and persists the user (find before save)")
    void addUser_happyPath_encodesPasswordAndSaves() {
        stubAddSucceeds();

        UserResponse resp = userAddService.addUser(valid());

        // Confirmation response mirrors the request and carries the COBOL success message; no error, no password.
        assertThat(resp.userId()).isEqualTo(USER_ID);
        assertThat(resp.firstName()).isEqualTo("Ann");
        assertThat(resp.lastName()).isEqualTo("Adams");
        assertThat(resp.userType()).isEqualTo(UserType.ADMIN);
        assertThat(resp.message()).isEqualTo("User USER0001 has been added ...");
        assertThat(resp.errorMessage()).isNull();

        // C-003: the password is BCrypt-encoded; the stored value is the hash, never the plaintext.
        verify(passwordEncoder).encode(PLAINTEXT_PWD);
        verify(userSecurityRepository).save(entityCaptor.capture());
        UserSecurity saved = entityCaptor.getValue();
        assertThat(saved.getSecUsrPwd()).isEqualTo(ENCODED_HASH);
        assertThat(saved.getSecUsrPwd()).isNotEqualTo(PLAINTEXT_PWD);
        assertThat(saved.getSecUsrId()).isEqualTo(USER_ID);
        assertThat(saved.getSecUsrFname()).isEqualTo("Ann");
        assertThat(saved.getSecUsrLname()).isEqualTo("Adams");
        assertThat(saved.getSecUsrType()).isEqualTo(UserType.ADMIN);

        // The duplicate guard (findBySecUsrId) runs BEFORE encoding and persistence.
        InOrder inOrder = inOrder(userSecurityRepository, passwordEncoder);
        inOrder.verify(userSecurityRepository).findBySecUsrId(USER_ID);
        inOrder.verify(passwordEncoder).encode(PLAINTEXT_PWD);
        inOrder.verify(userSecurityRepository).save(any(UserSecurity.class));
    }

    @Test
    @DisplayName("duplicate id: raises DuplicateRecordException (FILE STATUS 22) and never encodes or saves")
    void addUser_duplicateId_throwsDuplicateRecordException() {
        UserSecurity existing = new UserSecurity();
        existing.setSecUsrId(USER_ID);
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userAddService.addUser(valid()))
                .isInstanceOfSatisfying(DuplicateRecordException.class,
                        ex -> assertThat(ex.getFileStatus()).isEqualTo("22"))
                .hasMessage("User ID already exist...");

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("validation: a null first name is rejected first; repository and encoder are never touched")
    void addUser_blankFirstName_throwsValidation() {
        UserAddRequest req = request(null, "Adams", USER_ID, PLAINTEXT_PWD, UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("firstName"))
                .hasMessage("First Name can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("validation: an empty last name is rejected; repository and encoder are never touched")
    void addUser_blankLastName_throwsValidation() {
        UserAddRequest req = request("Ann", "", USER_ID, PLAINTEXT_PWD, UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("lastName"))
                .hasMessage("Last Name can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("validation: a whitespace-only user id is rejected (isBlank); repository and encoder untouched")
    void addUser_blankUserId_throwsValidation() {
        UserAddRequest req = request("Ann", "Adams", "   ", PLAINTEXT_PWD, UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage("User ID can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("validation: an empty password is rejected; repository and encoder are never touched")
    void addUser_blankPassword_throwsValidation() {
        UserAddRequest req = request("Ann", "Adams", USER_ID, "", UserType.ADMIN);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("password"))
                .hasMessage("Password can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("validation: a null user type is rejected last; repository and encoder are never touched")
    void addUser_nullUserType_throwsValidation() {
        UserAddRequest req = request("Ann", "Adams", USER_ID, PLAINTEXT_PWD, null);

        assertThatThrownBy(() -> userAddService.addUser(req))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userType"))
                .hasMessage("User Type can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @ParameterizedTest
    @EnumSource(UserType.class)
    @DisplayName("user type round-trips unchanged through add for both ADMIN and USER")
    void addUser_userTypeRoundTrips_forAdminAndUser(UserType userType) {
        stubAddSucceeds();

        UserResponse resp = userAddService.addUser(request("Ann", "Adams", USER_ID, PLAINTEXT_PWD, userType));

        assertThat(resp.userType()).isEqualTo(userType);
        verify(userSecurityRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSecUsrType()).isEqualTo(userType);
    }

    @Test
    @DisplayName("response never leaks the plaintext password nor the encoded hash")
    void addUser_responseNeverContainsPassword() {
        stubAddSucceeds();

        UserResponse resp = userAddService.addUser(valid());

        // UserResponse has no password component (structural guarantee); the message must not leak either value.
        assertThat(resp.message()).doesNotContain(PLAINTEXT_PWD);
        assertThat(resp.message()).doesNotContain(ENCODED_HASH);
        assertThat(resp.errorMessage()).isNull();
    }

    /**
     * Stubs the collaborators for a successful add: no pre-existing user, a deterministic encoded password,
     * and a {@code save} that echoes its argument. Every stub is exercised by the add flow, satisfying
     * Mockito strict-stub checking.
     */
    private void stubAddSucceeds() {
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(PLAINTEXT_PWD)).thenReturn(ENCODED_HASH);
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Builds a {@link UserAddRequest} in the source-confirmed component order
     * (firstName, lastName, userId, password, userType).
     *
     * @param fn first name
     * @param ln last name
     * @param id user id
     * @param pwd plaintext password
     * @param t  user type
     * @return the assembled request
     */
    private static UserAddRequest request(String fn, String ln, String id, String pwd, UserType t) {
        return new UserAddRequest(fn, ln, id, pwd, t);
    }

    /**
     * A fully valid add request (admin user) used by the success-path tests.
     *
     * @return a valid {@link UserAddRequest}
     */
    private static UserAddRequest valid() {
        return request("Ann", "Adams", USER_ID, PLAINTEXT_PWD, UserType.ADMIN);
    }
}
