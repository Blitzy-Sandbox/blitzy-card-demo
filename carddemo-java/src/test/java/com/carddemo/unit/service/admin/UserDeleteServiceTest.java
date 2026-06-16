package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.admin.UserDeleteService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link com.carddemo.service.admin.UserDeleteService} (← COBOL COUSR03C, SHA 27d6c6f). */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService - COUSR03C user delete (two-step read-before-delete gate)")
class UserDeleteServiceTest {

    private static final String USER_ID = "USER0001";
    private static final String MISSING_ID = "USER0009";
    private static final String MSG_EMPTY = "User ID can NOT be empty...";
    private static final String MSG_NOT_FOUND = "User ID NOT found...";
    private static final String MSG_CONFIRM = "Press PF5 key to delete this user ...";
    private static final String FILE_STATUS_NOT_FOUND = "23";

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @InjectMocks
    private UserDeleteService userDeleteService;

    /**
     * Builds a populated user-security entity for stubbing the keyed read. The password is
     * intentionally left unset: the delete flow never reads it and {@link UserResponse} has no
     * password field, so leaving it null keeps the fixture aligned with what the service consumes.
     */
    private static UserSecurity existing(String id, String firstName, String lastName, UserType type) {
        UserSecurity entity = new UserSecurity();
        entity.setSecUsrId(id);
        entity.setSecUsrFname(firstName);
        entity.setSecUsrLname(lastName);
        entity.setSecUsrType(type);
        return entity;
    }

    // ============================ getUser (confirm step - never deletes) ============================

    @Test
    @DisplayName("getUser: blank/whitespace/null id -> ValidationException(field=userId); no repository interaction")
    void getUser_blankId_throwsValidation() {
        // COUSR03C PROCESS-ENTER-KEY: USRIDIN = SPACES/LOW-VALUES -> "User ID can NOT be empty...".
        assertThatThrownBy(() -> userDeleteService.getUser(""))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage(MSG_EMPTY);

        assertThatThrownBy(() -> userDeleteService.getUser("   "))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage(MSG_EMPTY);

        assertThatThrownBy(() -> userDeleteService.getUser(null))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage(MSG_EMPTY);

        // The edit fails before any keyed read, so the store is never touched.
        verifyNoInteractions(userSecurityRepository);
    }

    @Test
    @DisplayName("getUser: id not found -> RecordNotFoundException (FILE STATUS 23); never deletes")
    void getUser_notFound_throwsRecordNotFound() {
        when(userSecurityRepository.findBySecUsrId(MISSING_ID)).thenReturn(Optional.empty());

        // COUSR03C READ-USER-SEC-FILE NOTFND -> "User ID NOT found...".
        assertThatThrownBy(() -> userDeleteService.getUser(MISSING_ID))
                .isInstanceOfSatisfying(RecordNotFoundException.class,
                        ex -> assertThat(ex.getFileStatus()).isEqualTo(FILE_STATUS_NOT_FOUND))
                .hasMessage(MSG_NOT_FOUND);

        verify(userSecurityRepository, never()).deleteById(any());
        verify(userSecurityRepository, never()).delete(any());
    }

    @Test
    @DisplayName("getUser: happy path returns display fields + PF5 prompt and NEVER deletes (confirm gate)")
    void getUser_happyPath_returnsPromptAndDoesNotDelete() {
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(existing(USER_ID, "Ann", "Adams", UserType.ADMIN)));

        UserResponse response = userDeleteService.getUser(USER_ID);

        // Display fields echo the loaded record (COUSR03C moves FNAME/LNAME/USRTYPE to the map).
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo("Ann");
        assertThat(response.lastName()).isEqualTo("Adams");
        assertThat(response.userType()).isEqualTo(UserType.ADMIN);
        assertThat(response.message()).isEqualTo(MSG_CONFIRM);
        assertThat(response.errorMessage()).isNull();

        // Two-step gate: the confirm/load step must not mutate the store.
        verify(userSecurityRepository, never()).deleteById(any());
        verify(userSecurityRepository, never()).delete(any());
    }

    // ================================== deleteUser (PF5 path) ======================================

    @Test
    @DisplayName("deleteUser: blank/whitespace/null id -> ValidationException(field=userId); no repository interaction")
    void deleteUser_blankId_throwsValidation() {
        // COUSR03C DELETE-USER-INFO: USRIDIN = SPACES/LOW-VALUES -> "User ID can NOT be empty...".
        assertThatThrownBy(() -> userDeleteService.deleteUser(""))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage(MSG_EMPTY);

        assertThatThrownBy(() -> userDeleteService.deleteUser("   "))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage(MSG_EMPTY);

        assertThatThrownBy(() -> userDeleteService.deleteUser(null))
                .isInstanceOfSatisfying(ValidationException.class,
                        ex -> assertThat(ex.getField()).isEqualTo("userId"))
                .hasMessage(MSG_EMPTY);

        verifyNoInteractions(userSecurityRepository);
    }

    @Test
    @DisplayName("deleteUser: id not found -> RecordNotFoundException (23); existence check gates the delete")
    void deleteUser_notFound_throwsRecordNotFoundAndDoesNotDelete() {
        when(userSecurityRepository.findBySecUsrId(MISSING_ID)).thenReturn(Optional.empty());

        // COUSR03C reads before deleting; a missing record stops the flow before DELETE-USER-SEC-FILE.
        assertThatThrownBy(() -> userDeleteService.deleteUser(MISSING_ID))
                .isInstanceOfSatisfying(RecordNotFoundException.class,
                        ex -> assertThat(ex.getFileStatus()).isEqualTo(FILE_STATUS_NOT_FOUND))
                .hasMessage(MSG_NOT_FOUND);

        verify(userSecurityRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("deleteUser: happy path finds then deletes exactly once and returns the deletion message")
    void deleteUser_happyPath_findsThenDeletesOnceAndReturnsMessage() {
        when(userSecurityRepository.findBySecUsrId(USER_ID))
                .thenReturn(Optional.of(existing(USER_ID, "Ann", "Adams", UserType.ADMIN)));

        UserResponse response = userDeleteService.deleteUser(USER_ID);

        // Display fields are captured from the entity loaded BEFORE the delete.
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.firstName()).isEqualTo("Ann");
        assertThat(response.lastName()).isEqualTo("Adams");
        assertThat(response.userType()).isEqualTo(UserType.ADMIN);
        assertThat(response.message()).isEqualTo("User USER0001 has been deleted ...");
        assertThat(response.errorMessage()).isNull();

        // Read-then-delete ordering (COUSR03C READ-USER-SEC-FILE then DELETE-USER-SEC-FILE), deleted once.
        InOrder inOrder = inOrder(userSecurityRepository);
        inOrder.verify(userSecurityRepository).findBySecUsrId(USER_ID);
        inOrder.verify(userSecurityRepository).deleteById(USER_ID);
        verify(userSecurityRepository, times(1)).deleteById(USER_ID);
        verifyNoMoreInteractions(userSecurityRepository);
    }
}
