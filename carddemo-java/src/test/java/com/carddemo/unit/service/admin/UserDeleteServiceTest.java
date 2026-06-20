package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.model.enums.UserType;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.admin.UserDeleteService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UserDeleteService} (COBOL {@code COUSR03C} parity): the read-for-confirm
 * gate ({@code getUser}) and the PF5 delete ({@code deleteUser}), including the exact COBOL
 * messages and the never-mutate-on-load contract.
 */
@ExtendWith(MockitoExtension.class)
class UserDeleteServiceTest {

    @Mock
    private UserSecurityRepository userSecurityRepository;

    private UserDeleteService service() {
        return new UserDeleteService(userSecurityRepository);
    }

    private static UserSecurity user() {
        UserSecurity u = new UserSecurity();
        u.setSecUsrId("JPUBLIC");
        u.setSecUsrFname("John");
        u.setSecUsrLname("Public");
        u.setSecUsrType(UserType.USER);
        return u;
    }

    @Test
    void getUserBlankRejected() {
        assertThatThrownBy(() -> service().getUser("  "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User ID can NOT be empty...");
    }

    @Test
    void getUserNotFound() {
        when(userSecurityRepository.findBySecUsrId("JPUBLIC")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().getUser("JPUBLIC"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("User ID NOT found...");
    }

    @Test
    void getUserReturnsConfirmationPromptWithoutDeleting() {
        when(userSecurityRepository.findBySecUsrId("JPUBLIC")).thenReturn(Optional.of(user()));

        UserResponse response = service().getUser("JPUBLIC");

        assertThat(response.userId()).isEqualTo("JPUBLIC");
        assertThat(response.firstName()).isEqualTo("John");
        assertThat(response.message()).isEqualTo("Press PF5 key to delete this user ...");
        verify(userSecurityRepository, never()).deleteById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteUserBlankRejected() {
        assertThatThrownBy(() -> service().deleteUser(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User ID can NOT be empty...");
    }

    @Test
    void deleteUserNotFound() {
        when(userSecurityRepository.findBySecUsrId("JPUBLIC")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().deleteUser("JPUBLIC"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("User ID NOT found...");
        verify(userSecurityRepository, never()).deleteById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteUserRemovesAndEchoesRemovedRecord() {
        when(userSecurityRepository.findBySecUsrId("JPUBLIC")).thenReturn(Optional.of(user()));

        UserResponse response = service().deleteUser("JPUBLIC");

        verify(userSecurityRepository).deleteById("JPUBLIC");
        assertThat(response.userId()).isEqualTo("JPUBLIC");
        assertThat(response.message()).isEqualTo("User JPUBLIC has been deleted ...");
        assertThat(response.errorMessage()).isNull();
    }
}
