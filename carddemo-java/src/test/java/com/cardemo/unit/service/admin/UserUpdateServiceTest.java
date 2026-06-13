package com.cardemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserUpdateService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Fast, fully-mocked behavioral-parity unit test for
 * {@link com.cardemo.service.admin.UserUpdateService}, the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * migration of the legacy AWS CardDemo admin update-user CICS program
 * {@code app/cbl/COUSR02C.cbl} (CICS transaction {@code CU02}, BMS map {@code COUSR2A} / mapset
 * {@code COUSR02}, &ldquo;Update User&rdquo;). The COBOL is read-only reference material at the
 * frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only
 * its observable behavior is asserted here (AAP &sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring</h2>
 * <p>These are millisecond, in-memory unit tests: there is <em>no</em> {@code @SpringBootTest}, no
 * Spring context, no {@code @DataJpaTest}, no database, no Testcontainers and no file/network I/O.
 * The {@link UserSecurityRepository} collaborator is a Mockito {@code @Mock}; the
 * {@link PasswordEncoder} is a {@code @Spy} wrapping a <strong>real</strong>
 * {@link BCryptPasswordEncoder} so the password change-detection assertions are a genuine
 * encode/verify round-trip (and so a pure mock encoder cannot raise {@code STRICT_STUBS}
 * &ldquo;unnecessary stubbing&rdquo; errors); the system under test is wired by constructor injection
 * via {@code @InjectMocks}. Because the service is invoked directly (not through a Spring AOP proxy),
 * its {@code @PreAuthorize("hasRole('ADMIN')")} / {@code @Transactional} advice is intentionally not
 * exercised &mdash; that wiring is verified by the integration tier, not by this fast unit tier.</p>
 *
 * <p>The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so every
 * {@code when(...)} stub must be exercised by the test that declares it. The field-edit validation
 * tests throw <em>before</em> any repository or encoder call, so they declare <em>no</em> stubs; the
 * &ldquo;no fields modified&rdquo; no-op tests stub only {@code findBySecUsrId} (never {@code save},
 * because nothing is persisted); the persisting tests stub {@code findBySecUsrId} + {@code save}
 * exactly where they are consumed. No {@code lenient()} is needed anywhere.</p>
 *
 * <h2>Pinned parity contract (verified against the on-disk service + {@code COUSR02C.cbl})</h2>
 * <ul>
 *   <li><strong>Two operations, two methods.</strong> {@code COUSR02C} fused a read-for-edit
 *       ({@code PROCESS-ENTER-KEY}) and an apply-on-PF5 ({@code UPDATE-USER-INFO}) behind one 3270
 *       screen; the migration keeps them as {@code loadUser(String)} and
 *       {@code updateUser(UserSecurityDto)}, each tested independently here.</li>
 *   <li><strong>First-error-wins field edits &mdash; UPDATE order.</strong> The
 *       {@code UPDATE-USER-INFO} {@code EVALUATE TRUE} cascade ({@code COUSR02C} L179-213) stops at
 *       the first empty field in the order <em>user id &rarr; first name &rarr; last name &rarr;
 *       password &rarr; user type</em>. This order deliberately <strong>differs</strong> from the add
 *       screen ({@code COUSR01C}, which edits first name before user id); each program's order is
 *       preserved verbatim. Every field's exact message (three trailing dots, no leading space) is
 *       asserted, and precedence cases prove earlier-listed blanks win over later ones.</li>
 *   <li><strong>Not-found.</strong> The {@code READ-USER-SEC-FILE} / {@code UPDATE-USER-SEC-FILE}
 *       {@code DFHRESP(NOTFND)} branch maps to {@link RecordNotFoundException} carrying the verbatim
 *       &ldquo;User ID NOT found...&rdquo; text; on a not-found commit nothing is persisted.</li>
 *   <li><strong>Per-field change detection &amp; the no-op guard.</strong> The record is rewritten
 *       only when at least one field actually differs ({@code WS-USR-MODIFIED}); when nothing changed
 *       the COBOL {@code ELSE} branch surfaced the red &ldquo;Please modify to update ...&rdquo;
 *       feedback and performed <strong>no</strong> {@code REWRITE} ({@code COUSR02C} L236-243). Note
 *       the single space before the ellipsis is part of the contract.</li>
 *   <li><strong>BCrypt &mdash; the single permitted behavioral change (constraint C-003).</strong>
 *       The legacy literal compare {@code IF PASSWDI NOT = SEC-USR-PWD} cannot work against a one-way
 *       hash, so a change is detected with {@code !passwordEncoder.matches(raw, storedHash)} and an
 *       actually-changed password is re-encoded with BCrypt; an <em>unchanged</em> password is never
 *       re-encoded and the stored hash is preserved byte-for-byte. The credential is never echoed on
 *       the response (the DTO password is {@code WRITE_ONLY}).</li>
 * </ul>
 *
 * <p>There is <strong>no</strong> {@code USRSEC} golden fixture under {@code app/data/ASCII/} (only
 * nine fixtures exist, none for users), so all test data is built inline. User ids are {@code X(08)}
 * (max eight characters), user names {@code X(20)} (max twenty) and the password input {@code X(08)}
 * (max eight), per {@code app/cpy/CSUSR01Y.cpy}.</p>
 *
 * @see com.cardemo.service.admin.UserUpdateService
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see com.cardemo.model.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserUpdateService — COUSR02C update-user parity (CU02)")
class UserUpdateServiceTest {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message literals (COUSR02C.cbl) — asserted directly for byte-exact parity. The
    // trailing dots, and (for MSG_PLEASE_MODIFY) the single space before the ellipsis, are part of the
    // external contract and must not drift.
    // -----------------------------------------------------------------------------------------------

    /** {@code COUSR02C} L182-183: empty user-id edit ({@code UPDATE-USER-INFO} branch 1). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code COUSR02C} L188-189: empty first-name edit ({@code UPDATE-USER-INFO} branch 2). */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code COUSR02C} L194-195: empty last-name edit ({@code UPDATE-USER-INFO} branch 3). */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code COUSR02C} L200-201: empty password edit ({@code UPDATE-USER-INFO} branch 4). */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** {@code COUSR02C} L206-207: empty user-type edit ({@code UPDATE-USER-INFO} branch 5). */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** QA F5 length guard: first name beyond {@code SEC-USR-FNAME PIC X(20)} / {@code VARCHAR(20)}. */
    private static final String MSG_FIRST_NAME_TOO_LONG = "First Name can NOT be longer than 20 characters...";

    /** QA F5 length guard: last name beyond {@code SEC-USR-LNAME PIC X(20)} / {@code VARCHAR(20)}. */
    private static final String MSG_LAST_NAME_TOO_LONG = "Last Name can NOT be longer than 20 characters...";

    /** {@code COUSR02C} L342/L379: {@code DFHRESP(NOTFND)} not-found message. */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /**
     * {@code COUSR02C} L239: the &ldquo;no change&rdquo; feedback ({@code UPDATE-USER-INFO}
     * {@code ELSE} branch). The single space before the ellipsis is preserved verbatim.
     */
    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    // -----------------------------------------------------------------------------------------------
    // Inline fixtures. The existing record is the loaded/edited user; values are whitespace-free so a
    // trimmed compare equals the raw value, letting an individual test mutate exactly one field.
    // -----------------------------------------------------------------------------------------------

    /** Valid 8-character user id ({@code USRIDIN PIC X(8)} / {@code SEC-USR-ID}). */
    private static final String EXISTING_USER_ID = "USER0002";

    /** Existing first name ({@code SEC-USR-FNAME PIC X(20)}). */
    private static final String EXISTING_FIRST_NAME = "John";

    /** Existing last name ({@code SEC-USR-LNAME PIC X(20)}). */
    private static final String EXISTING_LAST_NAME = "Smith";

    /** Existing (raw) 8-character password whose BCrypt hash is stored ({@code PASSWD PIC X(8)}). */
    private static final String EXISTING_RAW_PASSWORD = "OLDPASS1";

    /** A different, valid 8-character password used to prove change-detection + re-encode. */
    private static final String NEW_RAW_PASSWORD = "NEWPASS1";

    /** Mocked data-access collaborator (the {@code USRSEC} VSAM KSDS replacement). */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /**
     * Real BCrypt encoder behind a {@code @Spy} so {@code encode(...)} / {@code matches(...)} run for
     * real &mdash; enabling a genuine hash round-trip for the C-003 change-detection assertions. A
     * {@code @Spy} over the real encoder is preferred to a pure {@code @Mock} here because it proves
     * true BCrypt parity and avoids {@code STRICT_STUBS} &ldquo;unnecessary stubbing&rdquo; on the
     * encode/matches paths.
     */
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** System under test; constructor-injected with the mock repository and the spy encoder. */
    @InjectMocks
    private UserUpdateService userUpdateService;

    /** Captures the {@link UserSecurity} entity handed to {@code save(...)}. */
    private final ArgumentCaptor<UserSecurity> savedCaptor = ArgumentCaptor.forClass(UserSecurity.class);

    /**
     * A dedicated, real BCrypt encoder used ONLY to build fixture password hashes in
     * {@link #existingUser()}. It is intentionally separate from the injected {@code @Spy}
     * {@link #passwordEncoder}: {@link #existingUser()} is frequently evaluated <em>inline</em> inside
     * a {@code when(...).thenReturn(Optional.of(existingUser()))} argument, and invoking a Mockito spy
     * inside that in-progress stubbing would be misread by Mockito as &ldquo;unfinished stubbing&rdquo;.
     * BCrypt verification is cross-instance (the salt is embedded in the digest), so a hash produced
     * here verifies against the injected spy's {@code matches(...)} exactly as a production hash would
     * &mdash; the service's password change-detection still runs entirely through the injected
     * {@code @Spy} {@link #passwordEncoder}.
     */
    private static final PasswordEncoder FIXTURE_PASSWORD_ENCODER = new BCryptPasswordEncoder();

    /**
     * Builds a fully valid update request whose fields are all identical to the existing record (id
     * {@code USER0002}, ADMIN, raw password {@code OLDPASS1}). An individual test then blanks exactly
     * one field (for a validation case) or mutates exactly one field (for a change-detection case).
     *
     * @return a fully populated, valid {@link UserSecurityDto} matching the existing user
     */
    private static UserSecurityDto requestMatchingExisting() {
        UserSecurityDto dto = new UserSecurityDto();
        dto.setUserId(EXISTING_USER_ID);
        dto.setFirstName(EXISTING_FIRST_NAME);
        dto.setLastName(EXISTING_LAST_NAME);
        dto.setPassword(EXISTING_RAW_PASSWORD);
        dto.setUserType(UserType.ADMIN);
        return dto;
    }

    /**
     * Builds the existing user record as it would be loaded from {@code USRSEC}, with the credential
     * stored as a <strong>real</strong> BCrypt hash of {@link #EXISTING_RAW_PASSWORD} so that
     * {@code matches("OLDPASS1", hash)} is {@code true} and {@code matches("NEWPASS1", hash)} is
     * {@code false}.
     *
     * <p>The hash is produced by the dedicated {@link #FIXTURE_PASSWORD_ENCODER}, not the injected
     * {@code @Spy} {@link #passwordEncoder}, so this builder records <em>no</em> spy interaction and is
     * therefore safe to evaluate inline inside a {@code when(...).thenReturn(...)} argument (a spy call
     * inside an in-progress stubbing would trip Mockito's &ldquo;unfinished stubbing&rdquo; detection).
     * The service still performs its real BCrypt change-detection ({@code matches}/{@code encode})
     * through the injected spy.</p>
     *
     * @return a managed-style {@link UserSecurity} with id/names/type set and a BCrypt password hash
     */
    private static UserSecurity existingUser() {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(EXISTING_USER_ID);
        user.setSecUsrFname(EXISTING_FIRST_NAME);
        user.setSecUsrLname(EXISTING_LAST_NAME);
        user.setSecUsrType(UserType.ADMIN);
        user.setSecUsrPwd(FIXTURE_PASSWORD_ENCODER.encode(EXISTING_RAW_PASSWORD));
        return user;
    }

    // ===============================================================================================
    // Phase 3 — loadUser(String) : read-for-edit parity (COUSR02C PROCESS-ENTER-KEY).
    //   The only edit on load is the user id; a successful load returns demographics but NEVER the
    //   password (the plaintext is unrecoverable post-BCrypt; the DTO password field is WRITE_ONLY).
    // ===============================================================================================

    @ParameterizedTest(name = "loadUser blank id [{0}] -> ValidationException, no repository read")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("loadUser: blank/null/whitespace id -> 'User ID can NOT be empty...'; no repo read")
    void loadUserBlankIdThrowsValidationAndDoesNotRead(String blankUserId) {
        // PROCESS-ENTER-KEY EVALUATE: WHEN USRIDINI = SPACES OR LOW-VALUES (COUSR02C L146-149). The
        // blank edit fires before the keyed read, so findBySecUsrId is never reached.
        assertThatThrownBy(() -> userUpdateService.loadUser(blankUserId))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("loadUser: id not found -> RecordNotFoundException('User ID NOT found...')")
    void loadUserNotFoundThrowsRecordNotFound() {
        // READ-USER-SEC-FILE WHEN DFHRESP(NOTFND) -> "User ID NOT found..." (COUSR02C L340-344).
        when(userSecurityRepository.findBySecUsrId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userUpdateService.loadUser(EXISTING_USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_NOT_FOUND);
    }

    @Test
    @DisplayName("loadUser: happy path returns demographics but NEVER the password")
    void loadUserHappyPathReturnsDemographicsWithoutPassword() {
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));

        UserSecurityDto response = userUpdateService.loadUser(EXISTING_USER_ID);

        // WHEN DFHRESP(NORMAL): MOVE SEC-USR-* TO map fields (COUSR02C L166-171).
        assertThat(response.getUserId()).isEqualTo(EXISTING_USER_ID);
        assertThat(response.getFirstName()).isEqualTo(EXISTING_FIRST_NAME);
        assertThat(response.getLastName()).isEqualTo(EXISTING_LAST_NAME);
        assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
        // The legacy MOVE SEC-USR-PWD TO PASSWDI display is deliberately NOT reproduced: the stored
        // value is a one-way BCrypt hash and the DTO password is WRITE_ONLY (C-003 consequence).
        assertThat(response.getPassword()).isNull();
    }

    // ===============================================================================================
    // Phase 4A — updateUser(...) ordered field-edit parity. COUSR02C UPDATE-USER-INFO EVALUATE TRUE
    //   (L179-213) is first-error-wins in the EXACT order user id -> first name -> last name ->
    //   password -> user type, which differs from the add screen. All edits precede the keyed read,
    //   so on ANY validation failure nothing is read and nothing is saved. A field is "blank" when
    //   null (LOW-VALUES) or whitespace-only (SPACES); user type is "blank" when null.
    // ===============================================================================================

    @Test
    @DisplayName("updateUser: blank user id -> 'User ID can NOT be empty...'; no read, no save")
    void updateUserBlankUserId() {
        UserSecurityDto request = requestMatchingExisting();
        request.setUserId(null);
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: blank first name -> 'First Name can NOT be empty...'; nothing saved")
    void updateUserBlankFirstName() {
        UserSecurityDto request = requestMatchingExisting();
        request.setFirstName("   ");
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: blank last name -> 'Last Name can NOT be empty...'; nothing saved")
    void updateUserBlankLastName() {
        UserSecurityDto request = requestMatchingExisting();
        request.setLastName("");
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_LAST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: blank password -> 'Password can NOT be empty...'; nothing saved")
    void updateUserBlankPassword() {
        UserSecurityDto request = requestMatchingExisting();
        request.setPassword("   ");
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_PASSWORD_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: null user type -> 'User Type can NOT be empty...'; nothing saved")
    void updateUserNullUserType() {
        UserSecurityDto request = requestMatchingExisting();
        request.setUserType(null);
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_TYPE_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: first-error-wins — blank user id beats a blank last name (UPDATE order)")
    void updateUserFirstErrorWinsUserIdBeatsLastName() {
        // Pin the UPDATE-specific ordering: user id is edited FIRST, so a blank user id wins even when
        // the last name is also blank (unlike the add screen, which edits first name before user id).
        UserSecurityDto request = requestMatchingExisting();
        request.setUserId(null);
        request.setLastName(null);
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: precedence — blank first name beats blank last name/password/type")
    void updateUserFirstNameBeatsLaterFields() {
        UserSecurityDto request = requestMatchingExisting();
        request.setFirstName(null);
        request.setLastName(null);
        request.setPassword(null);
        request.setUserType(null);
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: precedence — blank last name beats blank password/type")
    void updateUserLastNameBeatsLaterFields() {
        UserSecurityDto request = requestMatchingExisting();
        request.setLastName(null);
        request.setPassword(null);
        request.setUserType(null);
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_LAST_NAME_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: precedence — blank password beats blank user type")
    void updateUserPasswordBeatsUserType() {
        UserSecurityDto request = requestMatchingExisting();
        request.setPassword(null);
        request.setUserType(null);
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_PASSWORD_EMPTY);
        verify(userSecurityRepository, never()).save(any());
    }

    // ===============================================================================================
    // Phase 4A-bis — field-width guards (QA F5). COUSR02C relied on the fixed-width BMS PIC fields
    //   (FNAME/LNAME PIC X(20)) so an over-length name could not occur; the REST contract has no such
    //   bound, so the service enforces the PIC widths AFTER the empty cascade and BEFORE the keyed read,
    //   making an over-length name a 400 (ValidationException) with nothing read and nothing saved. The
    //   user id is the lookup key (an over-length id simply finds no row -> 404) and the password widens
    //   to a BCrypt digest, so only first/last name are length-guarded. Names are validated on their
    //   normalized (trimmed) value, since that is exactly what is stored on a change.
    // ===============================================================================================

    @Test
    @DisplayName("F5: first name > 20 chars -> 'First Name can NOT be longer than 20 characters...'; no read, no save")
    void updateUserOverLengthFirstNameRejected() {
        UserSecurityDto request = requestMatchingExisting();
        request.setFirstName("A".repeat(21)); // PIC X(20) / VARCHAR(20) -> 21 overflows
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_TOO_LONG);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("F5: last name > 20 chars -> 'Last Name can NOT be longer than 20 characters...'; no read, no save")
    void updateUserOverLengthLastNameRejected() {
        UserSecurityDto request = requestMatchingExisting();
        request.setLastName("B".repeat(21));
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_LAST_NAME_TOO_LONG);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("F5: empty cascade still wins over a length violation (blank first name beats over-length last name)")
    void updateUserEmptyCascadeWinsOverLengthGuard() {
        UserSecurityDto request = requestMatchingExisting();
        request.setFirstName("   ");          // blank -> empty-edit branch 2 must win first
        request.setLastName("B".repeat(30));  // also over-length, but later in the order
        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_FIRST_NAME_EMPTY);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("F5: boundary first name (exactly 20 chars) is accepted, read, and persisted")
    void updateUserBoundaryFirstNameAccepted() {
        UserSecurity existing = existingUser();
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existing));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSecurityDto request = requestMatchingExisting();
        String boundaryName = "A".repeat(20); // exactly at the PIC X(20) bound -> inclusive, valid
        request.setFirstName(boundaryName);    // differs from the existing name -> change-detected

        userUpdateService.updateUser(request);

        verify(userSecurityRepository, times(1)).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getSecUsrFname()).isEqualTo(boundaryName);
    }

    // ===============================================================================================
    // Phase 4B — updateUser(...) record-not-found on commit. A fully-valid request whose key is absent
    //   reproduces UPDATE-USER-SEC-FILE / READ-USER-SEC-FILE DFHRESP(NOTFND): RecordNotFoundException
    //   and no REWRITE (no save).
    // ===============================================================================================

    @Test
    @DisplayName("updateUser: valid request but id not found -> RecordNotFoundException; nothing saved")
    void updateUserRecordNotFoundOnCommit() {
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.empty());

        UserSecurityDto request = requestMatchingExisting();

        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_NOT_FOUND);
        verify(userSecurityRepository, never()).save(any());
    }

    // ===============================================================================================
    // Phase 4C — password change detection via !passwordEncoder.matches(raw, storedHash). Because the
    //   stored value is a one-way BCrypt hash, the legacy literal compare IF PASSWDI NOT = SEC-USR-PWD
    //   is impossible; "changed" means the typed password does NOT already match the stored hash.
    // ===============================================================================================

    @Test
    @DisplayName("updateUser: changed password -> re-encoded with BCrypt and saved (verifiable hash)")
    void updateUserChangedPasswordReEncodesAndSaves() {
        UserSecurity existing = existingUser();
        // Capture the pre-update hash into a separate reference: the service mutates the SAME entity
        // instance in place (and save returns that instance), so reading the captured entity's hash
        // afterwards would otherwise show the post-update value.
        String oldHash = existing.getSecUsrPwd();
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existing));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSecurityDto request = requestMatchingExisting();
        request.setPassword(NEW_RAW_PASSWORD); // only the password differs from the existing record

        userUpdateService.updateUser(request);

        verify(userSecurityRepository, times(1)).save(savedCaptor.capture());
        UserSecurity saved = savedCaptor.getValue();

        // Tech-spec note (C-003 / AAP §0.7.2): the legacy IF PASSWDI NOT = SEC-USR-PWD literal compare
        // + MOVE PASSWDI TO SEC-USR-PWD becomes !matches(raw, hash) + setSecUsrPwd(encode(raw)). Prove
        // the stored form is a real BCrypt digest of the NEW raw password (round-trip via the @Spy),
        // is not the plaintext, and differs from the prior hash.
        assertThat(saved.getSecUsrPwd()).isNotNull();
        assertThat(saved.getSecUsrPwd()).startsWith("$2"); // BCrypt digest prefix ($2a/$2b/$2y)
        assertThat(saved.getSecUsrPwd()).isNotEqualTo(NEW_RAW_PASSWORD);
        assertThat(saved.getSecUsrPwd()).isNotEqualTo(oldHash);
        assertThat(passwordEncoder.matches(NEW_RAW_PASSWORD, saved.getSecUsrPwd())).isTrue();
        verify(passwordEncoder).encode(NEW_RAW_PASSWORD);
    }

    @Test
    @DisplayName("updateUser: same password and no other change -> 'Please modify to update ...'; no save")
    void updateUserSamePasswordNoOtherChangeThrowsAndDoesNotSave() {
        // findBySecUsrId IS consumed; save is NOT (the no-fields-modified branch performs no REWRITE),
        // so save must NOT be stubbed here (STRICT_STUBS would flag an unnecessary stub otherwise).
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));

        UserSecurityDto request = requestMatchingExisting(); // every field identical, password OLDPASS1

        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_PLEASE_MODIFY);
        // COUSR02C ELSE branch (L238-242): nothing modified -> no REWRITE.
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: same password but another field changed -> save; stored hash preserved")
    void updateUserSamePasswordOtherFieldChangedPreservesHash() {
        UserSecurity existing = existingUser();
        String originalHash = existing.getSecUsrPwd();
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existing));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSecurityDto request = requestMatchingExisting();
        request.setLastName("Doe"); // a real demographic change; password stays OLDPASS1 (unchanged)

        userUpdateService.updateUser(request);

        verify(userSecurityRepository, times(1)).save(savedCaptor.capture());
        UserSecurity saved = savedCaptor.getValue();

        assertThat(saved.getSecUsrLname()).isEqualTo("Doe");
        // An unchanged password must NOT be re-encoded: the stored hash is preserved byte-for-byte and
        // still verifies against the original raw password.
        assertThat(saved.getSecUsrPwd()).isEqualTo(originalHash);
        assertThat(passwordEncoder.matches(EXISTING_RAW_PASSWORD, saved.getSecUsrPwd())).isTrue();
    }

    // ===============================================================================================
    // Phase 4D — field mapping, UserType, and fixed-width (PIC X) compare parity on a normal update.
    //   COBOL MOVE <map field> TO SEC-USR-<field> stores the (trimmed) value; trailing-space-only
    //   differences are not real changes (normalize / PIC X compare); the success echo never carries
    //   the credential.
    // ===============================================================================================

    @Test
    @DisplayName("updateUser: maps first/last name + USER ('U') type; echoes update without password")
    void updateUserMapsDemographicsAndUserTypeAndEchoesWithoutPassword() {
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSecurityDto request = requestMatchingExisting();
        request.setFirstName("Jane");
        request.setLastName("Doe");
        request.setUserType(UserType.USER); // ADMIN -> USER is a real change
        // password left as OLDPASS1 (unchanged) so the modification comes from the demographics/type.

        UserSecurityDto response = userUpdateService.updateUser(request);

        verify(userSecurityRepository, times(1)).save(savedCaptor.capture());
        UserSecurity saved = savedCaptor.getValue();

        // MOVE FNAMEI/LNAMEI/USRTYPEI TO SEC-USR-* (COUSR02C L219-234).
        assertThat(saved.getSecUsrFname()).isEqualTo("Jane");
        assertThat(saved.getSecUsrLname()).isEqualTo("Doe");
        assertThat(saved.getSecUsrType()).isEqualTo(UserType.USER);
        assertThat(saved.getSecUsrType().getCode()).isEqualTo('U'); // byte-faithful 1-char code

        // The success echo returns the updated demographics (the COBOL "User <id> has been updated
        // ..." outcome) and NEVER the credential/hash (DTO password is WRITE_ONLY).
        assertThat(response.getUserId()).isEqualTo(EXISTING_USER_ID);
        assertThat(response.getFirstName()).isEqualTo("Jane");
        assertThat(response.getLastName()).isEqualTo("Doe");
        assertThat(response.getUserType()).isEqualTo(UserType.USER);
        assertThat(response.getPassword()).isNull();
    }

    @Test
    @DisplayName("updateUser: trailing-space-only difference is NOT a change -> 'Please modify ...'; no save")
    void updateUserTrailingSpacesAreNotAModification() {
        // COBOL compared two right-space-padded PIC X fields, so trailing spaces are insignificant; the
        // service reproduces this with null-safe trimming (normalize). A last name that differs only by
        // trailing spaces is therefore NOT a modification -> the no-op guard fires and nothing is saved.
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));

        UserSecurityDto request = requestMatchingExisting();
        request.setLastName(EXISTING_LAST_NAME + "   "); // "Smith   " — trailing spaces only

        assertThatThrownBy(() -> userUpdateService.updateUser(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_PLEASE_MODIFY);
        verify(userSecurityRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateUser: a real change stores the trimmed value (fixed-width PIC X MOVE)")
    void updateUserStoresTrimmedValueOnRealChange() {
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSecurityDto request = requestMatchingExisting();
        request.setFirstName("  Jane  "); // a real change to first name, padded with surrounding spaces

        userUpdateService.updateUser(request);

        verify(userSecurityRepository, times(1)).save(savedCaptor.capture());
        // The stored value is the trimmed logical value (3270 field padding is a storage artifact).
        assertThat(savedCaptor.getValue().getSecUsrFname()).isEqualTo("Jane");
    }

    @Test
    @DisplayName("updateUser: the 8-byte user id key is trimmed for the keyed read")
    void updateUserTrimsUserIdForKeyedRead() {
        // MOVE USRIDINI TO SEC-USR-ID + READ-USER-SEC-FILE: the canonical VSAM key is the trimmed id.
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));
        when(userSecurityRepository.save(any(UserSecurity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserSecurityDto request = requestMatchingExisting();
        request.setUserId("  " + EXISTING_USER_ID + "  "); // "  USER0002  "
        request.setFirstName("Jane"); // a real change so the record is rewritten

        userUpdateService.updateUser(request);

        verify(userSecurityRepository).findBySecUsrId(EXISTING_USER_ID);
        verify(userSecurityRepository, times(1)).save(any(UserSecurity.class));
    }
}
