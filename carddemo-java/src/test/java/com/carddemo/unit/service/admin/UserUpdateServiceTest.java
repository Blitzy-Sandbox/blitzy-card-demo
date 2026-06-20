package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.dto.UserUpdateRequest;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.admin.UserUpdateService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Isolated, JVM-only unit tests for {@link UserUpdateService}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the online
 * user-update program {@code app/cbl/COUSR02C.cbl} runs {@code UPDATE-USER-INFO}, whose
 * {@code EVALUATE TRUE} edits the required fields in the order user id, first name, last name,
 * password, user type &mdash; rejecting a blank password with {@code "Password can NOT be empty..."}
 * &mdash; then field-by-field change detection ({@code PASSWDI NOT = SEC-USR-PWD}) rewrites the
 * record only when something changed. These tests pin behavioral parity with that required-field
 * order (first-error-wins) and the BCrypt-at-rest change-detection, using Mockito mocks (no database).</p>
 */
@DisplayName("UserUpdateService - COUSR02C required-field order, required password, BCrypt change detection")
@ExtendWith(MockitoExtension.class)
class UserUpdateServiceTest {

    private static final String USER_ID = "USER0001";
    private static final String STORED_HASH = "$2a$10$storedHashValuePlaceholderForBcrypt00000000000000000000";

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserUpdateService service() {
        return new UserUpdateService(userSecurityRepository, passwordEncoder);
    }

    private static UserSecurity storedUser() {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId(USER_ID);
        u.setSecUsrFname("JOHN");
        u.setSecUsrLname("SMITH");
        u.setSecUsrPwd(STORED_HASH);
        u.setSecUsrType(UserType.USER);
        return u;
    }

    private static UserUpdateRequest request(String userId, String first, String last,
            String password, UserType type) {
        return new UserUpdateRequest(userId, first, last, password, type);
    }

    // ---- Required-field validation order (first-error-wins) ----

    @Test
    @DisplayName("blank user id is rejected first with the exact COBOL message")
    void userIdBlankRejected() {
        assertThatThrownBy(() -> service().updateUser(request("", "JOHN", "SMITH", "PASS", UserType.USER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User ID can NOT be empty...");
        verify(userSecurityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("blank first name is rejected with the exact COBOL message")
    void firstNameBlankRejected() {
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "", "SMITH", "PASS", UserType.USER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("First Name can NOT be empty...");
    }

    @Test
    @DisplayName("blank last name is rejected with the exact COBOL message")
    void lastNameBlankRejected() {
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "", "PASS", UserType.USER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Last Name can NOT be empty...");
    }

    @Test
    @DisplayName("blank password is rejected with the exact COBOL message (required-password parity)")
    void passwordBlankRejected() {
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "SMITH", "  ", UserType.USER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Password can NOT be empty...");
        verify(userSecurityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("null password is rejected with the exact COBOL message")
    void passwordNullRejected() {
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "SMITH", null, UserType.USER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Password can NOT be empty...");
    }

    @Test
    @DisplayName("null user type is rejected once required fields incl. password are present")
    void userTypeNullRejected() {
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "SMITH", "PASS", null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User Type can NOT be empty...");
    }

    @Test
    @DisplayName("last-name edit precedes password edit (ordering)")
    void lastNameEditPrecedesPassword() {
        // both last name and password blank -> last name wins
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "", "", UserType.USER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Last Name can NOT be empty...");
    }

    @Test
    @DisplayName("password edit precedes user-type edit (ordering)")
    void passwordEditPrecedesUserType() {
        // both password blank and user type null -> password wins
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "SMITH", "", null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Password can NOT be empty...");
    }

    // ---- Record lookup ----

    @Test
    @DisplayName("missing user record throws RecordNotFoundException with the exact COBOL message")
    void userNotFound() {
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().updateUser(request(USER_ID, "JOHN", "SMITH", "PASS", UserType.USER)))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("User ID NOT found...");
    }

    // ---- BCrypt change detection ----

    @Test
    @DisplayName("a different password is re-encoded and the record is saved")
    void passwordChangeReEncodesAndSaves() {
        UserSecurity stored = storedUser();
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("NEWPASS", STORED_HASH)).thenReturn(false);
        when(passwordEncoder.encode("NEWPASS")).thenReturn("$2a$10$newlyEncodedHashValue000000000000000000000000000000");

        UserResponse resp = service().updateUser(request(USER_ID, "JOHN", "SMITH", "NEWPASS", UserType.USER));

        assertThat(stored.getSecUsrPwd()).startsWith("$2a$10$newlyEncoded");
        verify(passwordEncoder).encode("NEWPASS");
        verify(userSecurityRepository).save(stored);
        assertThat(resp.message()).isEqualTo("User " + USER_ID + " has been updated ...");
        assertThat(resp.errorMessage()).isNull();
    }

    @Test
    @DisplayName("re-typing the existing password with no other change yields 'Please modify to update ...' and no save")
    void unchangedPasswordNoSave() {
        UserSecurity stored = storedUser();
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("SAMEPASS", STORED_HASH)).thenReturn(true);

        UserResponse resp = service().updateUser(request(USER_ID, "JOHN", "SMITH", "SAMEPASS", UserType.USER));

        verify(passwordEncoder, never()).encode(anyString());
        verify(userSecurityRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(resp.message()).isEqualTo("Please modify to update ...");
    }

    @Test
    @DisplayName("changing only the first name (password unchanged) saves the record")
    void firstNameChangeSaves() {
        UserSecurity stored = storedUser();
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("SAMEPASS", STORED_HASH)).thenReturn(true);

        UserResponse resp = service().updateUser(request(USER_ID, "JANE", "SMITH", "SAMEPASS", UserType.USER));

        assertThat(stored.getSecUsrFname()).isEqualTo("JANE");
        verify(passwordEncoder, never()).encode(anyString());
        verify(userSecurityRepository).save(stored);
        assertThat(resp.message()).isEqualTo("User " + USER_ID + " has been updated ...");
        assertThat(resp.firstName()).isEqualTo("JANE");
    }

    // ---- getUser (PROCESS-ENTER-KEY) ----

    @Test
    @DisplayName("getUser rejects a blank id")
    void getUserBlankThrows() {
        assertThatThrownBy(() -> service().getUser(" "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User ID can NOT be empty...");
    }

    @Test
    @DisplayName("getUser throws when the user does not exist")
    void getUserNotFound() {
        when(userSecurityRepository.findBySecUsrId("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().getUser("NOPE"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("User ID NOT found...");
    }

    @Test
    @DisplayName("getUser returns the editable fields and the save prompt without a password")
    void getUserSuccess() {
        when(userSecurityRepository.findBySecUsrId(USER_ID)).thenReturn(Optional.of(storedUser()));

        UserResponse resp = service().getUser(USER_ID);

        assertThat(resp.userId()).isEqualTo(USER_ID);
        assertThat(resp.firstName()).isEqualTo("JOHN");
        assertThat(resp.lastName()).isEqualTo("SMITH");
        assertThat(resp.userType()).isEqualTo(UserType.USER);
        assertThat(resp.message()).isEqualTo("Press PF5 key to save your updates ...");
        assertThat(resp.errorMessage()).isNull();
    }
}
