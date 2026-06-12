package com.cardemo.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pure-JVM behavioral-parity unit test for {@link UserAddService}, the Java migration of the legacy
 * AWS CardDemo admin add-user CICS program {@code app/cbl/COUSR01C.cbl} (transaction {@code CU01},
 * BMS map {@code COUSR1A} / mapset {@code COUSR01}; frozen baseline commit SHA {@code 27d6c6f}).
 *
 * <p>These tests pin the observable add contract the migration must reproduce exactly (AAP
 * &sect;0.7.2):</p>
 * <ul>
 *   <li><strong>First-error-wins field edits.</strong> The COBOL {@code PROCESS-ENTER-KEY}
 *       {@code EVALUATE TRUE} ({@code COUSR01C} L117-151) stopped at the first empty field, so only
 *       the first failing field's message was returned. The tests assert each field's verbatim
 *       message independently <em>and</em> that earlier-listed blanks take precedence over later ones
 *       (order: first name &rarr; last name &rarr; user id &rarr; password &rarr; user type).</li>
 *   <li><strong>Duplicate-key outcome.</strong> The {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)}
 *       branch ({@code COUSR01C} L260-266) maps to a {@link DuplicateRecordException} carrying the
 *       verbatim &ldquo;User ID already exist...&rdquo; text, including a concurrent-insert race
 *       backstop.</li>
 *   <li><strong>The single permitted behavioral change (constraint C-003).</strong> The legacy
 *       plaintext {@code MOVE PASSWDI TO SEC-USR-PWD} becomes a BCrypt hash; the tests prove the
 *       stored value is hashed (never the raw input), verifiable with the encoder, and that the
 *       credential is never echoed on the response.</li>
 *   <li><strong>Happy-path field mapping.</strong> A valid request persists exactly one entity with
 *       the user id, names and {@code 'A'}/{@code 'U'} type mapped across, and the response echoes
 *       those fields (the COBOL success &ldquo;User &lt;id&gt; has been added ...&rdquo;).</li>
 * </ul>
 *
 * <p>Fast and isolated: the {@link UserSecurityRepository} collaborator is a Mockito mock, and a
 * <strong>real</strong> {@link BCryptPasswordEncoder} is used so the BCrypt assertions are genuine
 * (no Spring context, database or Testcontainers; Surefire-compatible). The COBOL source is read-only
 * reference and is never copied into this repository; only its behavior is asserted.</p>
 *
 * @see UserAddService
 * @see UserSecurityRepository
 * @see UserSecurityDto
 * @see UserType
 */
class UserAddServiceTest {

    /** Verbatim COBOL message literals ({@code COUSR01C.cbl}) — asserted directly for byte-exact parity. */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";
    private static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    /** The legacy 8-character plaintext password contract ({@code PASSWD PIC X(8)}). */
    private static final String RAW_PASSWORD = "pass1234";

    /** Mocked data-access collaborator (the {@code USRSEC} VSAM replacement). */
    private UserSecurityRepository repository;

    /** Real BCrypt encoder so {@code matches(...)} / hash-prefix assertions are genuine. */
    private PasswordEncoder passwordEncoder;

    /** The system under test. */
    private UserAddService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserSecurityRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        service = new UserAddService(repository, passwordEncoder);
    }

    /**
     * Builds a fully valid add-user request with every required field populated, so individual tests
     * can blank exactly one field and assert the corresponding edit.
     */
    private static UserSecurityDto validRequest() {
        UserSecurityDto dto = new UserSecurityDto();
        dto.setUserId("USER0001");
        dto.setFirstName("John");
        dto.setLastName("Doe");
        dto.setPassword(RAW_PASSWORD);
        dto.setUserType(UserType.ADMIN);
        return dto;
    }

    // ===========================================================================================
    // Ordered field-edit parity (COUSR01C PROCESS-ENTER-KEY EVALUATE TRUE, first-error-wins).
    // ===========================================================================================

    @Nested
    @DisplayName("Field-edit validation (first-error-wins, verbatim messages)")
    class FieldValidation {

        @Test
        @DisplayName("Blank first name (SPACES) -> 'First Name can NOT be empty...'")
        void blankFirstName() {
            UserSecurityDto dto = validRequest();
            dto.setFirstName("   ");
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_FIRST_NAME_EMPTY);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Null first name (LOW-VALUES) is treated as empty")
        void nullFirstName() {
            UserSecurityDto dto = validRequest();
            dto.setFirstName(null);
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_FIRST_NAME_EMPTY);
        }

        @Test
        @DisplayName("Blank last name -> 'Last Name can NOT be empty...'")
        void blankLastName() {
            UserSecurityDto dto = validRequest();
            dto.setLastName(" ");
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_LAST_NAME_EMPTY);
        }

        @Test
        @DisplayName("Blank user id -> 'User ID can NOT be empty...'")
        void blankUserId() {
            UserSecurityDto dto = validRequest();
            dto.setUserId(null);
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_USER_ID_EMPTY);
        }

        @Test
        @DisplayName("Blank password -> 'Password can NOT be empty...'")
        void blankPassword() {
            UserSecurityDto dto = validRequest();
            dto.setPassword("");
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_PASSWORD_EMPTY);
        }

        @Test
        @DisplayName("Null user type (USRTYPEI empty) -> 'User Type can NOT be empty...'")
        void nullUserType() {
            UserSecurityDto dto = validRequest();
            dto.setUserType(null);
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage(MSG_USER_TYPE_EMPTY);
        }

        @Test
        @DisplayName("Precedence: blank first name wins over later blank fields")
        void firstNameWinsOverEverything() {
            UserSecurityDto dto = validRequest();
            dto.setFirstName(null);
            dto.setLastName(null);
            dto.setUserId(null);
            dto.setPassword(null);
            dto.setUserType(null);
            assertThatThrownBy(() -> service.addUser(dto)).hasMessage(MSG_FIRST_NAME_EMPTY);
        }

        @Test
        @DisplayName("Precedence: last name wins over user id/password/type")
        void lastNameWinsOverLaterFields() {
            UserSecurityDto dto = validRequest();
            dto.setLastName(null);
            dto.setUserId(null);
            dto.setPassword(null);
            dto.setUserType(null);
            assertThatThrownBy(() -> service.addUser(dto)).hasMessage(MSG_LAST_NAME_EMPTY);
        }

        @Test
        @DisplayName("Precedence: user id wins over password/type")
        void userIdWinsOverLaterFields() {
            UserSecurityDto dto = validRequest();
            dto.setUserId(null);
            dto.setPassword(null);
            dto.setUserType(null);
            assertThatThrownBy(() -> service.addUser(dto)).hasMessage(MSG_USER_ID_EMPTY);
        }

        @Test
        @DisplayName("Precedence: password wins over user type")
        void passwordWinsOverUserType() {
            UserSecurityDto dto = validRequest();
            dto.setPassword(null);
            dto.setUserType(null);
            assertThatThrownBy(() -> service.addUser(dto)).hasMessage(MSG_PASSWORD_EMPTY);
        }
    }

    // ===========================================================================================
    // Duplicate-key parity (COUSR01C WRITE-USER-SEC-FILE, DFHRESP(DUPKEY)/DFHRESP(DUPREC)).
    // ===========================================================================================

    @Nested
    @DisplayName("Duplicate-key handling (verbatim 'User ID already exist...')")
    class DuplicateHandling {

        @Test
        @DisplayName("Existing id pre-check -> DuplicateRecordException; save never invoked")
        void duplicateOnPreCheck() {
            when(repository.existsById("USER0001")).thenReturn(true);
            UserSecurityDto dto = validRequest();
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessage(MSG_USER_ID_ALREADY_EXISTS);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Race: save throws DataIntegrityViolationException -> DuplicateRecordException (cause preserved)")
        void duplicateOnSaveRace() {
            when(repository.existsById(anyString())).thenReturn(false);
            when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup key"));
            UserSecurityDto dto = validRequest();
            assertThatThrownBy(() -> service.addUser(dto))
                    .isInstanceOf(DuplicateRecordException.class)
                    .hasMessage(MSG_USER_ID_ALREADY_EXISTS)
                    .hasCauseInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ===========================================================================================
    // BCrypt (the single permitted behavioral change, C-003) + happy-path field mapping.
    // ===========================================================================================

    @Nested
    @DisplayName("Persistence: BCrypt hashing and field mapping")
    class Persistence {

        @Test
        @DisplayName("Password is BCrypt-hashed (not plaintext), verifiable, and never echoed")
        void passwordIsBCryptHashed() {
            when(repository.existsById(anyString())).thenReturn(false);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            UserSecurityDto dto = validRequest();

            UserSecurityDto response = service.addUser(dto);

            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(repository).save(captor.capture());
            UserSecurity saved = captor.getValue();

            assertThat(saved.getSecUsrPwd()).isNotNull();
            assertThat(saved.getSecUsrPwd()).isNotEqualTo(RAW_PASSWORD);
            assertThat(saved.getSecUsrPwd()).startsWith("$2"); // BCrypt digest prefix
            assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getSecUsrPwd())).isTrue();
            // The credential/hash is never echoed back (DTO password is WRITE_ONLY).
            assertThat(response.getPassword()).isNull();
        }

        @Test
        @DisplayName("Happy path persists one entity with correct mapping and ADMIN ('A') type")
        void happyPathAdmin() {
            when(repository.existsById(anyString())).thenReturn(false);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            UserSecurityDto dto = validRequest();

            UserSecurityDto response = service.addUser(dto);

            ArgumentCaptor<UserSecurity> captor = ArgumentCaptor.forClass(UserSecurity.class);
            verify(repository).save(captor.capture());
            UserSecurity saved = captor.getValue();

            assertThat(saved.getSecUsrId()).isEqualTo("USER0001");
            assertThat(saved.getSecUsrFname()).isEqualTo("John");
            assertThat(saved.getSecUsrLname()).isEqualTo("Doe");
            assertThat(saved.getSecUsrType()).isEqualTo(UserType.ADMIN);

            assertThat(response.getUserId()).isEqualTo("USER0001");
            assertThat(response.getFirstName()).isEqualTo("John");
            assertThat(response.getLastName()).isEqualTo("Doe");
            assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("Regular user persists with USER ('U') type")
        void happyPathUser() {
            when(repository.existsById(anyString())).thenReturn(false);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            UserSecurityDto dto = validRequest();
            dto.setUserType(UserType.USER);

            UserSecurityDto response = service.addUser(dto);

            assertThat(response.getUserType()).isEqualTo(UserType.USER);
            assertThat(response.getUserType().getCode()).isEqualTo('U');
        }

        @Test
        @DisplayName("User id is trimmed for the existence probe and the stored/echoed key")
        void userIdTrimmed() {
            when(repository.existsById("USER0002")).thenReturn(false);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            UserSecurityDto dto = validRequest();
            dto.setUserId("  USER0002  ");

            UserSecurityDto response = service.addUser(dto);

            verify(repository).existsById("USER0002");
            assertThat(response.getUserId()).isEqualTo("USER0002");
        }
    }
}
