package com.carddemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link UserAddService} (COBOL {@code COUSR01C} parity): the required-field
 * edit cascade in EVALUATE order, the duplicate-key guard, and BCrypt-at-rest persistence.
 */
@ExtendWith(MockitoExtension.class)
class UserAddServiceTest {

    @Mock
    private UserSecurityRepository userSecurityRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserAddService service() {
        return new UserAddService(userSecurityRepository, passwordEncoder);
    }

    private static UserAddRequest req() {
        return new UserAddRequest("John", "Public", "JPUBLIC", "secret12", UserType.USER);
    }

    @Test
    void blankFirstNameRejectedFirst() {
        UserAddRequest request = new UserAddRequest("", "", "", "", null);
        assertThatThrownBy(() -> service().addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("First Name can NOT be empty...");
    }

    @Test
    void blankLastNameRejected() {
        UserAddRequest request = new UserAddRequest("John", "", "JPUBLIC", "secret12", UserType.USER);
        assertThatThrownBy(() -> service().addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Last Name can NOT be empty...");
    }

    @Test
    void blankUserIdRejected() {
        UserAddRequest request = new UserAddRequest("John", "Public", "", "secret12", UserType.USER);
        assertThatThrownBy(() -> service().addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User ID can NOT be empty...");
    }

    @Test
    void blankPasswordRejected() {
        UserAddRequest request = new UserAddRequest("John", "Public", "JPUBLIC", "", UserType.USER);
        assertThatThrownBy(() -> service().addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Password can NOT be empty...");
    }

    @Test
    void nullUserTypeRejected() {
        UserAddRequest request = new UserAddRequest("John", "Public", "JPUBLIC", "secret12", null);
        assertThatThrownBy(() -> service().addUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User Type can NOT be empty...");
    }

    @Test
    void duplicateUserIdRejected() {
        when(userSecurityRepository.findBySecUsrId("JPUBLIC")).thenReturn(Optional.of(new UserSecurity()));
        assertThatThrownBy(() -> service().addUser(req()))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage("User ID already exist...");
        verify(userSecurityRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void successHashesPasswordAndPersists() {
        when(userSecurityRepository.findBySecUsrId("JPUBLIC")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret12")).thenReturn("$2a$HASH");

        UserResponse response = service().addUser(req());

        ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
        verify(userSecurityRepository).save(captor.capture());
        UserSecurity saved = captor.getValue();
        assertThat(saved.getSecUsrId()).isEqualTo("JPUBLIC");
        assertThat(saved.getSecUsrPwd()).isEqualTo("$2a$HASH");
        assertThat(saved.getSecUsrType()).isEqualTo(UserType.USER);

        assertThat(response.message()).isEqualTo("User JPUBLIC has been added ...");
        assertThat(response.userId()).isEqualTo("JPUBLIC");
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    void passwordNeverEncodedWhenValidationFails() {
        UserAddRequest request = new UserAddRequest("John", "Public", "JPUBLIC", "", UserType.USER);
        assertThatThrownBy(() -> service().addUser(request)).isInstanceOf(ValidationException.class);
        verify(passwordEncoder, never()).encode(anyString());
    }
}
