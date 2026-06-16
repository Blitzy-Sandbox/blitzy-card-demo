package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests for {@link com.carddemo.service.admin.UserUpdateService} (&larr; COBOL COUSR02C, SHA 27d6c6f). */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService - COUSR02C user update (read-then-update by id)")
class UserUpdateServiceTest {

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserUpdateService userUpdateService;

    @Captor
    private ArgumentCaptor<UserSecurity> entityCaptor;

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static UserSecurity existing(String id, String fn, String ln, String pwdHash, UserType t) {
        UserSecurity entity = new UserSecurity();
        entity.setSecUsrId(id);
        entity.setSecUsrFname(fn);
        entity.setSecUsrLname(ln);
        entity.setSecUsrPwd(pwdHash);
        entity.setSecUsrType(t);
        return entity;
    }

    private static UserUpdateRequest req(String id, String fn, String ln, String pwd, UserType t) {
        return new UserUpdateRequest(id, fn, ln, pwd, t);
    }

    // ---------------------------------------------------------------------------------------------
    // getUser - load-for-edit (COUSR02C PROCESS-ENTER-KEY)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("getUser: blank or null user id is rejected before any repository access")
    void getUser_blankId_throwsValidation() {
        assertThatThrownBy(() -> userUpdateService.getUser(""))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage("User ID can NOT be empty...");

        assertThatThrownBy(() -> userUpdateService.getUser(null))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage("User ID can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("getUser: missing record maps to RecordNotFoundException (FILE STATUS 23)")
    void getUser_notFound_throwsRecordNotFound() {
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userUpdateService.getUser("USER0001"))
                .isInstanceOfSatisfying(RecordNotFoundException.class,
                        ex -> assertThat(ex.getFileStatus()).isEqualTo("23"))
                .hasMessage("User ID NOT found...");

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    @Test
    @DisplayName("getUser: existing record returns the edit prompt and never saves")
    void getUser_happyPath_returnsPromptMessageAndDoesNotSave() {
        when(userSecurityRepository.findBySecUsrId("USER0001"))
                .thenReturn(Optional.of(existing("USER0001", "Ann", "Adams", "$2a$hash", UserType.ADMIN)));

        UserResponse response = userUpdateService.getUser("USER0001");

        assertThat(response.userId()).isEqualTo("USER0001");
        assertThat(response.firstName()).isEqualTo("Ann");
        assertThat(response.lastName()).isEqualTo("Adams");
        assertThat(response.userType()).isEqualTo(UserType.ADMIN);
        assertThat(response.message()).isEqualTo("Press PF5 key to save your updates ...");
        assertThat(response.errorMessage()).isNull();

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    // ---------------------------------------------------------------------------------------------
    // updateUser - required-field validation (first-error-wins; password is NOT validated, C-003)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("updateUser: blank user id fails first, before the record is loaded")
    void updateUser_blankUserId_throwsValidation() {
        assertThatThrownBy(() -> userUpdateService.updateUser(req("", "Ann", "Adams", "", UserType.ADMIN)))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage("User ID can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser: blank first name fails after user id")
    void updateUser_blankFirstName_throwsValidation() {
        assertThatThrownBy(() -> userUpdateService.updateUser(req("USER0001", "", "Adams", "", UserType.ADMIN)))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("firstName"))
                .hasMessage("First Name can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser: blank last name fails after first name")
    void updateUser_blankLastName_throwsValidation() {
        assertThatThrownBy(() -> userUpdateService.updateUser(req("USER0001", "Ann", "", "", UserType.ADMIN)))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("lastName"))
                .hasMessage("Last Name can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser: null user type fails after last name")
    void updateUser_nullUserType_throwsValidation() {
        assertThatThrownBy(() -> userUpdateService.updateUser(req("USER0001", "Ann", "Adams", "", null)))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userType"))
                .hasMessage("User Type can NOT be empty...");

        verifyNoInteractions(userSecurityRepository, passwordEncoder);
    }

    @Test
    @DisplayName("updateUser: a blank password is allowed (C-003) and is not a validation error")
    void updateUser_blankPasswordIsAllowed_notAValidationError() {
        when(userSecurityRepository.findBySecUsrId("USER0001"))
                .thenReturn(Optional.of(existing("USER0001", "Ann", "Adams", "$2a$hash", UserType.ADMIN)));
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // First name differs, so the update genuinely changes the record; the blank password must
        // simply be skipped (no encode, no compare) rather than rejected as an empty field.
        UserResponse response =
                userUpdateService.updateUser(req("USER0001", "Annabel", "Adams", "", UserType.ADMIN));

        assertThat(response.message()).isEqualTo("User USER0001 has been updated ...");
        verifyNoInteractions(passwordEncoder);
    }

    // ---------------------------------------------------------------------------------------------
    // updateUser - record not found
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("updateUser: missing record maps to RecordNotFoundException (FILE STATUS 23)")
    void updateUser_notFound_throwsRecordNotFound() {
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userUpdateService.updateUser(req("USER0001", "Ann", "Adams", "", UserType.ADMIN)))
                .isInstanceOfSatisfying(RecordNotFoundException.class,
                        ex -> assertThat(ex.getFileStatus()).isEqualTo("23"))
                .hasMessage("User ID NOT found...");

        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    // ---------------------------------------------------------------------------------------------
    // updateUser - change detection (COUSR02C USR-MODIFIED-YES flag)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("updateUser: a changed field saves and returns the updated message (load before save)")
    void updateUser_fieldChanged_savesAndReturnsUpdatedMessage() {
        UserSecurity entity = existing("USER0001", "Ann", "Adams", "$2a$hash", UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(entity));
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response =
                userUpdateService.updateUser(req("USER0001", "Annabel", "Adams", "", UserType.ADMIN));

        assertThat(response.message()).isEqualTo("User USER0001 has been updated ...");
        assertThat(response.firstName()).isEqualTo("Annabel");

        InOrder inOrder = inOrder(userSecurityRepository);
        inOrder.verify(userSecurityRepository).findBySecUsrId("USER0001");
        inOrder.verify(userSecurityRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSecUsrFname()).isEqualTo("Annabel");

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("updateUser: no field changed returns the modify prompt and never saves")
    void updateUser_noChange_returnsModifyMessageAndDoesNotSave() {
        UserSecurity entity = existing("USER0001", "Ann", "Adams", "$2a$hash", UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(entity));

        UserResponse response =
                userUpdateService.updateUser(req("USER0001", "Ann", "Adams", "", UserType.ADMIN));

        assertThat(response.message()).isEqualTo("Please modify to update ...");
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("updateUser: a new password is re-encoded via matches()+encode(), never String.equals")
    void updateUser_passwordChanged_reEncodesViaMatchesNotEquals() {
        UserSecurity entity = existing("USER0001", "Ann", "Adams", "$2a$oldHash", UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(entity));
        // Login upper-cases before matching, so the service must upper-case "newpass1" -> "NEWPASS1"
        // before both matches() and encode(); the lower-case request input below proves normalization.
        when(passwordEncoder.matches("NEWPASS1", "$2a$oldHash")).thenReturn(false);
        when(passwordEncoder.encode("NEWPASS1")).thenReturn("$2a$newHash");
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response =
                userUpdateService.updateUser(req("USER0001", "Ann", "Adams", "newpass1", UserType.ADMIN));

        assertThat(response.message()).isEqualTo("User USER0001 has been updated ...");
        verify(userSecurityRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSecUsrPwd()).isEqualTo("$2a$newHash");
        verify(passwordEncoder).matches("NEWPASS1", "$2a$oldHash");
        verify(passwordEncoder).encode("NEWPASS1");
        // The raw lower-case form is never used, confirming normalization to upper case.
        verify(passwordEncoder, never()).encode("newpass1");
    }

    @Test
    @DisplayName("updateUser: a password equal to the stored hash is not a change (matches true, no encode/save)")
    void updateUser_passwordSameAsCurrent_notModifiedByPassword() {
        UserSecurity entity = existing("USER0001", "Ann", "Adams", "$2a$hash", UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(entity));
        // Service upper-cases "samepass" -> "SAMEPASS" before the BCrypt compare.
        when(passwordEncoder.matches("SAMEPASS", "$2a$hash")).thenReturn(true);

        UserResponse response =
                userUpdateService.updateUser(req("USER0001", "Ann", "Adams", "samepass", UserType.ADMIN));

        assertThat(response.message()).isEqualTo("Please modify to update ...");
        verify(passwordEncoder).matches("SAMEPASS", "$2a$hash");
        verify(passwordEncoder, never()).encode(any());
        verify(userSecurityRepository, never()).save(any(UserSecurity.class));
    }

    @Test
    @DisplayName("updateUser: a changed user type saves and returns the updated message")
    void updateUser_userTypeChanged_savesUpdated() {
        UserSecurity entity = existing("USER0001", "Ann", "Adams", "$2a$hash", UserType.ADMIN);
        when(userSecurityRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(entity));
        when(userSecurityRepository.save(any(UserSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response =
                userUpdateService.updateUser(req("USER0001", "Ann", "Adams", "", UserType.USER));

        assertThat(response.message()).isEqualTo("User USER0001 has been updated ...");
        verify(userSecurityRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSecUsrType()).isEqualTo(UserType.USER);
        verifyNoInteractions(passwordEncoder);
    }
}
