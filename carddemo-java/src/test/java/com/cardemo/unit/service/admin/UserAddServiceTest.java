package com.cardemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserAddService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Fast, fully-mocked behavioral-parity unit test for
 * {@link com.cardemo.service.admin.UserAddService}, the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * migration of the legacy AWS CardDemo admin add-user CICS program
 * {@code app/cbl/COUSR01C.cbl} (CICS transaction {@code CU01}, BMS map {@code COUSR1A} / mapset
 * {@code COUSR01}, &ldquo;Add User&rdquo;). The COBOL is read-only reference material at the frozen
 * baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only its
 * observable behavior is asserted here (AAP &sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring</h2>
 * <p>These are millisecond, in-memory unit tests: there is <em>no</em> {@code @SpringBootTest}, no
 * Spring context, no {@code @DataJpaTest}, no database, no Testcontainers and no file/network I/O.
 * The {@link UserSecurityRepository} collaborator is a Mockito {@code @Mock}; the
 * {@link PasswordEncoder} is a {@code @Spy} wrapping a <strong>real</strong>
 * {@link BCryptPasswordEncoder} so the BCrypt assertions are a genuine encode/verify round-trip
 * (and so a pure mock encoder cannot raise {@code STRICT_STUBS} "unnecessary stubbing" errors); the
 * system under test is wired by constructor injection via {@code @InjectMocks}. Because the service
 * is invoked directly (not through a Spring AOP proxy), its
 * {@code @PreAuthorize("hasRole('ADMIN')")}/{@code @Transactional} advice is intentionally not
 * exercised &mdash; that wiring is verified by the integration tier, not by this fast unit tier.</p>
 *
 * <p>The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so every
 * {@code when(...)} stub must be exercised by the test that declares it. The validation tests throw
 * before any repository or encoder call, so they declare <em>no</em> stubs; the duplicate and
 * persistence tests stub {@code existsById}/{@code save} locally, exactly where they are consumed.
 * No {@code lenient()} is needed anywhere.</p>
 *
 * <h2>Pinned parity contract (verified against the on-disk service + {@code COUSR01C.cbl})</h2>
 * <ul>
 *   <li><strong>First-error-wins field edits.</strong> The COBOL {@code PROCESS-ENTER-KEY}
 *       {@code EVALUATE TRUE} ({@code COUSR01C} L117-151) stopped at the first empty field, so only
 *       the first failing field's message was ever returned. Order is
 *       first name &rarr; last name &rarr; user id &rarr; password &rarr; user type; each field's
 *       verbatim message (three trailing dots, no leading space) is asserted independently, and the
 *       precedence cases prove earlier-listed blanks win over later ones.</li>
 *   <li><strong>Duplicate-key outcome.</strong> The {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)}
 *       branch ({@code COUSR01C} L260-266) maps to {@link DuplicateRecordException} carrying the
 *       verbatim &ldquo;User ID already exist...&rdquo; text, including a concurrent-insert race
 *       backstop ({@link DataIntegrityViolationException} from {@code save}).</li>
 *   <li><strong>BCrypt &mdash; the single permitted behavioral change (constraint C-003).</strong>
 *       The legacy plaintext {@code MOVE PASSWDI TO SEC-USR-PWD} becomes a BCrypt hash; the stored
 *       value is proven hashed (never the raw input), verifiable with the encoder, and the credential
 *       is never echoed on the response (the DTO password is {@code WRITE_ONLY}).</li>
 *   <li><strong>Happy-path field mapping.</strong> A valid request persists exactly one entity with
 *       the user id, names and {@code 'A'}/{@code 'U'} type mapped across, and the response echoes
 *       those fields (the COBOL success &ldquo;User &lt;id&gt; has been added ...&rdquo;).</li>
 * </ul>
 *
 * <p>There is <strong>no</strong> {@code USRSEC} golden fixture under {@code app/data/ASCII/} (only
 * nine fixtures exist, none for users), so all test data is built inline by {@link #validRequest()}.
 * User ids are {@code X(08)} (max eight characters), user names {@code X(20)} (max twenty) and the
 * password input {@code X(08)} (max eight), per {@code app/cpy/CSUSR01Y.cpy}.</p>
 *
 * @see com.cardemo.service.admin.UserAddService
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see com.cardemo.model.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAddService — COUSR01C add-user parity (CU01)")
class UserAddServiceTest {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message literals (COUSR01C.cbl L120-145, L263) — asserted directly for byte-exact
    // parity. The three trailing dots and the absence of a leading space are part of the contract.
    // -----------------------------------------------------------------------------------------------

    /** {@code COUSR01C} L120-121: empty first-name edit ({@code EVALUATE} branch 1). */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code COUSR01C} L126-127: empty last-name edit ({@code EVALUATE} branch 2). */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code COUSR01C} L132-133: empty user-id edit ({@code EVALUATE} branch 3). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code COUSR01C} L138-139: empty password edit ({@code EVALUATE} branch 4). */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** {@code COUSR01C} L144-145: empty user-type edit ({@code EVALUATE} branch 5). */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** {@code COUSR01C} L263-264: duplicate-key message (note legacy spelling "exist"). */
    private static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    // -----------------------------------------------------------------------------------------------
    // Inline fixtures. Values are deliberately whitespace-free so trimmed == raw: the service trims
    // only the user id (the VSAM key), setting first/last name straight across.
    // -----------------------------------------------------------------------------------------------

    /** Legacy 8-character plaintext password contract ({@code PASSWD PIC X(8)}). */
    private static final String RAW_PASSWORD = "PASS0001";

    /** Valid 8-character user id ({@code USERID PIC X(8)} / {@code SEC-USR-ID}). */
    private static final String VALID_USER_ID = "USER0001";

    /** Valid first name ({@code FNAME PIC X(20)}). */
    private static final String VALID_FIRST_NAME = "John";

    /** Valid last name ({@code LNAME PIC X(20)}). */
    private static final String VALID_LAST_NAME = "Doe";

    /** Mocked data-access collaborator (the {@code USRSEC} VSAM KSDS replacement). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Real BCrypt encoder behind a {@code @Spy} so {@code encode(...)}/{@code matches(...)} run for
     * real &mdash; enabling a genuine hash round-trip assertion. Tech-spec note: a {@code @Spy} over
     * the real encoder is preferred to a pure {@code @Mock} here because it proves true BCrypt parity
     * (constraint C-003) and avoids {@code STRICT_STUBS} "unnecessary stubbing" on the encode path.
     */
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** System under test; constructor-injected with the mock repository and the spy encoder. */
    @InjectMocks
    private UserAddService userAddService;

    /** Captures the {@link UserSecurity} entity handed to {@code save(...)}. */
    private final ArgumentCaptor<UserSecurity> savedCaptor = ArgumentCaptor.forClass(UserSecurity.class);

    /**
     * Builds a fully valid add-user request with every required field populated, so an individual
     * test can blank exactly one field (or mutate the type) and assert the corresponding behavior.
     *
     * @return a valid {@link UserSecurityDto} (id {@code USER0001}, ADMIN type, 8-char password)
     */
    private static UserSecurityDto validRequest() {
        UserSecurityDto dto = new UserSecurityDto();
        dto.setUserId(VALID_USER_ID);
        dto.setFirstName(VALID_FIRST_NAME);
        dto.setLastName(VALID_LAST_NAME);
        dto.setPassword(RAW_PASSWORD);
        dto.setUserType(UserType.ADMIN);
        return dto;
    }

    // ===============================================================================================
    // 3A. Ordered field-edit parity — COUSR01C PROCESS-ENTER-KEY EVALUATE TRUE, first-error-wins.
    //     A field is "blank" when null (LOW-VALUES) or whitespace-only (SPACES). On ANY validation
    //     failure, nothing is persisted: verify(repository, never()).save(any()).
    // ===============================================================================================

    @Test
    @DisplayName("Blank first name (SPACES) -> 'First Name can NOT be empty...'; nothing saved")
    void blankFirstNameSpaces() {
        UserSecurityDto dto = validRequest();
        dto.setFirstName("   ");
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Null first name (LOW-VALUES) -> 'First Name can NOT be empty...'; nothing saved")
    void nullFirstName() {
        UserSecurityDto dto = validRequest();
        dto.setFirstName(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Blank last name -> 'Last Name can NOT be empty...'; nothing saved")
    void blankLastName() {
        UserSecurityDto dto = validRequest();
        dto.setLastName(" ");
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_LAST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Blank user id -> 'User ID can NOT be empty...'; nothing saved")
    void blankUserId() {
        UserSecurityDto dto = validRequest();
        dto.setUserId(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Blank password -> 'Password can NOT be empty...'; nothing saved")
    void blankPassword() {
        UserSecurityDto dto = validRequest();
        dto.setPassword("");
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_PASSWORD_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Null user type (USRTYPEI empty) -> 'User Type can NOT be empty...'; nothing saved")
    void nullUserType() {
        UserSecurityDto dto = validRequest();
        dto.setUserType(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_TYPE_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Precedence: blank first name wins over every later blank field")
    void firstNameWinsOverEverything() {
        UserSecurityDto dto = validRequest();
        dto.setFirstName(null);
        dto.setLastName(null);
        dto.setUserId(null);
        dto.setPassword(null);
        dto.setUserType(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Precedence: blank last name wins over user id/password/type")
    void lastNameWinsOverLaterFields() {
        UserSecurityDto dto = validRequest();
        dto.setLastName(null);
        dto.setUserId(null);
        dto.setPassword(null);
        dto.setUserType(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_LAST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Precedence: blank user id wins over password/type")
    void userIdWinsOverLaterFields() {
        UserSecurityDto dto = validRequest();
        dto.setUserId(null);
        dto.setPassword(null);
        dto.setUserType(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Precedence: blank password wins over user type")
    void passwordWinsOverUserType() {
        UserSecurityDto dto = validRequest();
        dto.setPassword(null);
        dto.setUserType(null);
        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_PASSWORD_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    // ===============================================================================================
    // 3B. Duplicate-key parity — COUSR01C WRITE-USER-SEC-FILE, DFHRESP(DUPKEY)/DFHRESP(DUPREC) branch
    //     (L260-266) -> DuplicateRecordException("User ID already exist..."); save never reached on
    //     the deterministic existsById pre-check, and a concurrent-insert race is caught and rethrown.
    // ===============================================================================================

    @Test
    @DisplayName("Existing id pre-check -> DuplicateRecordException; save never invoked")
    void duplicateOnExistsPreCheck() {
        // The service trims the (whitespace-free) id and probes existsById before writing.
        when(userSecurityRepository.existsById(VALID_USER_ID)).thenReturn(true);

        UserSecurityDto dto = validRequest();

        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage(MSG_USER_ID_ALREADY_EXISTS);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Race: save throws DataIntegrityViolationException -> DuplicateRecordException (cause kept)")
    void duplicateOnSaveRace() {
        // existsById says "absent", but a concurrent insert wins the race and save fails with a
        // data-integrity violation; the service rethrows the verbatim duplicate exception, cause kept.
        when(userSecurityRepository.existsById(anyString())).thenReturn(false);
        when(userSecurityRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        UserSecurityDto dto = validRequest();

        assertThatThrownBy(() -> userAddService.addUser(dto))
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessage(MSG_USER_ID_ALREADY_EXISTS)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    // ===============================================================================================
    // 3C + 3D. BCrypt-on-add — the single permitted behavioral change (constraint C-003). The stored
    //          credential must be a BCrypt hash of the raw input (never the plaintext), and the
    //          credential must never be echoed back to the caller (DTO password is WRITE_ONLY).
    // ===============================================================================================

    @Test
    @DisplayName("Password is BCrypt-hashed (not plaintext), verifiable round-trip, never echoed")
    void passwordIsBCryptHashedAndNeverEchoed() {
        when(userSecurityRepository.existsById(anyString())).thenReturn(false);
        when(userSecurityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserSecurityDto dto = validRequest();

        UserSecurityDto response = userAddService.addUser(dto);

        verify(userSecurityRepository).save(savedCaptor.capture());
        UserSecurity saved = savedCaptor.getValue();

        // Tech-spec note (C-003 / AAP §0.7.2): legacy "MOVE PASSWDI TO SEC-USR-PWD" stored the raw
        // 8-char plaintext; the migration BCrypt-hashes it. Prove the stored form is a real BCrypt
        // digest of the raw input (round-trip via the real @Spy encoder), not the plaintext.
        assertThat(saved.getSecUsrPwd()).isNotNull();
        assertThat(saved.getSecUsrPwd()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.getSecUsrPwd()).startsWith("$2"); // BCrypt digest prefix ($2a/$2b/$2y)
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getSecUsrPwd())).isTrue();
        // The encoder was invoked with the raw password exactly (spy records the real call).
        verify(passwordEncoder).encode(RAW_PASSWORD);

        // 3D — the credential/hash is never echoed back on the response (WRITE_ONLY contract).
        assertThat(response.getPassword()).isNull();
    }

    // ===============================================================================================
    // 3E. Happy-path persistence + field mapping + UserType — a valid request persists exactly one
    //     entity with the id/names/type mapped across, and the response echoes those fields (the
    //     COBOL success "User <id> has been added ..."). The service exposes no message field, so
    //     parity is asserted behaviorally (save invoked once, fields mapped, DTO echoed).
    // ===============================================================================================

    @Test
    @DisplayName("Happy path persists one entity with correct mapping and ADMIN ('A') type")
    void happyPathAdmin() {
        when(userSecurityRepository.existsById(anyString())).thenReturn(false);
        when(userSecurityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserSecurityDto dto = validRequest();

        UserSecurityDto response = userAddService.addUser(dto);

        verify(userSecurityRepository, times(1)).save(savedCaptor.capture());
        UserSecurity saved = savedCaptor.getValue();

        assertThat(saved.getSecUsrId()).isEqualTo(VALID_USER_ID);
        assertThat(saved.getSecUsrFname()).isEqualTo(VALID_FIRST_NAME);
        assertThat(saved.getSecUsrLname()).isEqualTo(VALID_LAST_NAME);
        assertThat(saved.getSecUsrType()).isEqualTo(UserType.ADMIN);

        assertThat(response.getUserId()).isEqualTo(VALID_USER_ID);
        assertThat(response.getFirstName()).isEqualTo(VALID_FIRST_NAME);
        assertThat(response.getLastName()).isEqualTo(VALID_LAST_NAME);
        assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
    }

    @Test
    @DisplayName("Regular user persists and echoes USER ('U') type")
    void happyPathUser() {
        when(userSecurityRepository.existsById(anyString())).thenReturn(false);
        when(userSecurityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserSecurityDto dto = validRequest();
        dto.setUserType(UserType.USER);

        UserSecurityDto response = userAddService.addUser(dto);

        verify(userSecurityRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getSecUsrType()).isEqualTo(UserType.USER);
        assertThat(response.getUserType()).isEqualTo(UserType.USER);
        assertThat(response.getUserType().getCode()).isEqualTo('U'); // byte-faithful 'U' code
    }

    @Test
    @DisplayName("User id is trimmed for the existence probe and the stored/echoed key")
    void userIdIsTrimmed() {
        // The VSAM key SEC-USR-ID is the canonical (trimmed) value; the probe and stored key use it.
        when(userSecurityRepository.existsById("USER0002")).thenReturn(false);
        when(userSecurityRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserSecurityDto dto = validRequest();
        dto.setUserId("  USER0002  ");

        UserSecurityDto response = userAddService.addUser(dto);

        verify(userSecurityRepository).existsById("USER0002");
        verify(userSecurityRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getSecUsrId()).isEqualTo("USER0002");
        assertThat(response.getUserId()).isEqualTo("USER0002");
    }
}
