package com.cardemo.service.admin;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Add-user service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COUSR01C.cbl}</strong> (CICS transaction {@code CU01}, BMS map
 * {@code COUSR1A} / mapset {@code COUSR01}, &ldquo;Add User&rdquo;). The legacy program edited the
 * five input fields typed on the 3270 add-user screen and, when they all passed, wrote a new
 * fixed-length 80-byte record to the {@code USRSEC} VSAM&nbsp;KSDS keyed on the eight-character
 * {@code SEC-USR-ID}.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COUSR01C}'s observable add behavior <em>exactly</em>. In
 * particular it preserves the program's single {@code EVALUATE TRUE} field-edit cascade
 * (&ldquo;first-error-wins&rdquo;) verbatim &mdash; the same field order, the same emptiness test
 * ({@code SPACES OR LOW-VALUES}) and the same user-facing message text &mdash; and it preserves the
 * duplicate-key outcome of the {@code EXEC CICS WRITE}. Per the Minimal Change Clause (AAP
 * &sect;0.7.1) nothing is added, enhanced or optimized beyond the technology transition: no new
 * fields, no new endpoints and (crucially) <strong>no new password-complexity rules</strong>. The
 * COBOL is read-only reference material at the frozen baseline commit SHA {@code 27d6c6f} and is
 * never copied into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>The single permitted behavioral change &mdash; BCrypt (constraint C-003 / AAP &sect;0.7.2)</h2>
 * <p><strong>This service is one of the migration's two sanctioned BCrypt sites.</strong> The legacy
 * {@code COUSR01C} performed {@code MOVE PASSWDI TO SEC-USR-PWD} and stored the raw 8-character
 * <em>plaintext</em> password directly in the {@code USRSEC} record. Constraint&nbsp;C-003 mandates
 * the one and only deviation from strict byte-for-byte parity in this file: before persistence the
 * password is <strong>BCrypt-hashed</strong> (via the shared Spring Security {@link PasswordEncoder}
 * bean declared in {@code config/SecurityConfig}) into the {@code password} column
 * ({@code VARCHAR(72)} on the {@code user_security} table). The <em>input</em> password remains the
 * preserved legacy 8-character contract ({@code @Size(max = 8)} on the request DTO); only the
 * <em>stored</em> form is widened to the 60-character BCrypt digest. No additional credential rule
 * (length floor, character classes, history, expiry, &hellip;) is introduced &mdash; that would be
 * an enhancement the Minimal Change Clause forbids.</p>
 *
 * <h2>Decimal precision is not applicable here (AAP &sect;0.7.3)</h2>
 * <p>The {@code USRSEC} record ({@code app/cpy/CSUSR01Y.cpy}) is entirely textual ({@code PIC X}):
 * user id, first name, last name, password and type. It contains <strong>no</strong>
 * {@code COMP-3}/{@code COMP} or {@code PIC ...V99} numeric field, so there is no {@code float},
 * {@code double} or {@link java.math.BigDecimal} anywhere in this service.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code EVALUATE TRUE} field-edit cascade &rarr; ordered guarded checks.</strong>
 *       {@code PROCESS-ENTER-KEY} ({@code COUSR01C} L117-151) ran one {@code EVALUATE TRUE} that
 *       stopped at the first failing field, so only the <em>first</em> empty field's message was ever
 *       returned. {@link #addUser(UserSecurityDto)} reproduces this with a sequence of guarded
 *       {@code throw}s in the exact COBOL order (first name &rarr; last name &rarr; user id &rarr;
 *       password &rarr; user type); the first blank field throws a {@link ValidationException}
 *       carrying the verbatim COBOL message and short-circuits the rest. Bean Validation
 *       ({@code @Valid}) at the controller is an additional width guard only &mdash; it does not
 *       guarantee the COBOL field ordering or message text, so the authoritative ordered emptiness
 *       check lives here.</li>
 *   <li><strong>{@code SPACES OR LOW-VALUES} emptiness test &rarr; {@link #isBlank(String)}.</strong>
 *       A value is &ldquo;empty&rdquo; when {@code null} or blank after trimming, mirroring the COBOL
 *       test that treated all-spaces and uninitialized ({@code LOW-VALUES}) fields alike.</li>
 *   <li><strong>{@code EXEC CICS WRITE} + {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} &rarr;
 *       existence pre-check + {@code save}.</strong> The COBOL {@code WRITE-USER-SEC-FILE} paragraph
 *       ({@code COUSR01C} L238-274) wrote the record and, on a duplicate key, surfaced
 *       &ldquo;User ID already exist...&rdquo;. Here an {@link UserSecurityRepository#existsById(Object)
 *       existsById} probe reproduces that branch deterministically (throwing
 *       {@link DuplicateRecordException} with the verbatim text); a
 *       {@link DataIntegrityViolationException} from {@code save} is caught as a race-condition
 *       backstop and rethrown as the same {@link DuplicateRecordException} so a concurrent insert can
 *       never leak a raw persistence error.</li>
 *   <li><strong>Plaintext password &rarr; BCrypt.</strong> See the dedicated section above:
 *       {@code MOVE PASSWDI TO SEC-USR-PWD} becomes
 *       {@code setSecUsrPwd(passwordEncoder.encode(request.getPassword()))}. The encoder is the
 *       injected shared bean; this service never instantiates its own.</li>
 *   <li><strong>CICS map I/O ({@code RECEIVE MAP}/{@code SEND MAP}) &rarr; DTO.</strong> The
 *       {@code COUSR1AI} symbolic input map becomes the {@link UserSecurityDto} request, and the
 *       success {@code SEND} (the COBOL {@code STRING 'User ' SEC-USR-ID ' has been added ...'}) is
 *       conveyed by returning the populated DTO; HTTP status/messaging is centralized in the
 *       {@code config/WebConfig} {@code @RestControllerAdvice}.</li>
 *   <li><strong>Pseudo-conversational COMMAREA &rarr; method parameter/return.</strong> No
 *       server-side conversation state is retained: the request arrives as the method argument and the
 *       echoed result is the return value.</li>
 *   <li><strong>{@code SEC-USR-TYPE PIC X(01)} &rarr; {@link UserType}.</strong> The DTO already
 *       carries the role as the type-safe {@link UserType} enum (constants {@code ADMIN('A')} /
 *       {@code USER('U')}), so it is set straight onto the entity; the legacy single-character
 *       {@code 'A'}/{@code 'U'} storage semantics are preserved by the entity's converter.</li>
 * </ul>
 *
 * <h2>Layering &amp; security</h2>
 * <p>This is a {@link Service @Service} that returns a DTO only. It builds no {@code ResponseEntity},
 * sets no HTTP status and renders no message line (those concerns are centralized in the
 * {@code config/WebConfig} {@code @RestControllerAdvice}). The response <strong>never</strong>
 * carries the credential: the echoed DTO leaves {@code password} unset, and the password/hash is
 * never logged. Because the add is a single {@code USRSEC} write, {@link #addUser(UserSecurityDto)}
 * is annotated {@link Transactional @Transactional} (default propagation); the thrown
 * {@link ValidationException} and {@link DuplicateRecordException} are unchecked, so they trigger the
 * standard Spring rollback automatically.</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see UserSecurityDto
 * @see UserType
 * @see PasswordEncoder
 */
@Service
public class UserAddService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message text (COUSR01C.cbl). Kept byte-identical to guarantee user-facing parity
    // and surfaced through the single-String exception constructors. Package-private so the
    // behavioral-parity unit test can assert against the same canonical literals.
    // -----------------------------------------------------------------------------------------------

    /** {@code COUSR01C} L120-121: empty first-name edit message ({@code EVALUATE} branch 1). */
    static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code COUSR01C} L126-127: empty last-name edit message ({@code EVALUATE} branch 2). */
    static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code COUSR01C} L132-133: empty user-id edit message ({@code EVALUATE} branch 3). */
    static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code COUSR01C} L138-139: empty password edit message ({@code EVALUATE} branch 4). */
    static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** {@code COUSR01C} L144-145: empty user-type edit message ({@code EVALUATE} branch 5). */
    static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    // Length guards (QA F5). COUSR01C had no "too long" edit because each BMS map field was a
    // fixed-width PIC that could not overflow (FNAMEI/LNAMEI PIC X(20), USERIDI PIC X(08)); the REST
    // contract has no such bound, so an over-length value would otherwise reach the VARCHAR column and
    // surface as a 500. These reject it as a 400 instead, preserving the external field-width contract
    // (AAP §0.7.2). The "...characters..." ellipsis mirrors the "...empty..." message style above.

    /** First-name length guard: {@code SEC-USR-FNAME PIC X(20)} / {@code user_security.first_name VARCHAR(20)} (QA F5). */
    static final String MSG_FIRST_NAME_TOO_LONG = "First Name can NOT be longer than 20 characters...";

    /** Last-name length guard: {@code SEC-USR-LNAME PIC X(20)} / {@code user_security.last_name VARCHAR(20)} (QA F5). */
    static final String MSG_LAST_NAME_TOO_LONG = "Last Name can NOT be longer than 20 characters...";

    /** User-id length guard: {@code SEC-USR-ID PIC X(08)} / {@code user_security.user_id VARCHAR(8)} (QA F5). */
    static final String MSG_USER_ID_TOO_LONG = "User ID can NOT be longer than 8 characters...";

    /**
     * Password length guard: the legacy {@code PASSWDI} / {@code SEC-USR-PWD PIC X(08)} input field
     * (QA F-PWD-001). The 8-character INPUT contract is the legacy field width and is enforced here on
     * the raw typed value; it is independent of the C-003 BCrypt storage change (the stored digest
     * widens to {@code VARCHAR(72)}). Phrased to match the sibling {@link #MSG_USER_ID_TOO_LONG}.
     */
    static final String MSG_PASSWORD_TOO_LONG = "Password can NOT be longer than 8 characters...";

    /**
     * {@code COUSR01C} L263-264: duplicate-key message from the
     * {@code WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)} branch. Note the legacy spelling
     * &ldquo;exist&rdquo; (not &ldquo;exists&rdquo;) is preserved verbatim.
     */
    static final String MSG_USER_ID_ALREADY_EXISTS = "User ID already exist...";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators. Constructor injection with private-final fields (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    /** Data-access for the {@code USRSEC} VSAM replacement (existence probe + write). */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Shared BCrypt {@link PasswordEncoder} (the {@code config/SecurityConfig} bean). Injected rather
     * than instantiated so a single hashing policy governs both this add path and sign-on
     * verification (constraint C-003).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its required collaborators.
     *
     * @param userSecurityRepository repository for the {@code USRSEC} VSAM replacement; supplies the
     *                               {@link UserSecurityRepository#existsById(Object) existsById}
     *                               duplicate probe and the inherited
     *                               {@link UserSecurityRepository#save(Object) save} that reproduces
     *                               the {@code EXEC CICS WRITE DATASET('USRSEC')}
     * @param passwordEncoder        the shared BCrypt {@link PasswordEncoder} bean used to hash the
     *                               password before persistence (constraint C-003); never {@code null}
     */
    public UserAddService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Adds a new user, reproducing {@code COUSR01C} {@code PROCESS-ENTER-KEY} &rarr;
     * {@code WRITE-USER-SEC-FILE} end to end.
     *
     * <p>Processing order mirrors the COBOL dispatch exactly:</p>
     * <ol>
     *   <li><strong>Ordered field edits (first-error-wins).</strong> The five inputs are checked for
     *       emptiness in the COBOL {@code EVALUATE TRUE} order &mdash; first name, last name, user id,
     *       password, user type &mdash; and the <em>first</em> blank one throws a
     *       {@link ValidationException} carrying that field's verbatim COBOL message, short-circuiting
     *       the remaining checks ({@code COUSR01C} L117-151).</li>
     *   <li><strong>Duplicate-key pre-check.</strong> The (trimmed) user id is probed with
     *       {@link UserSecurityRepository#existsById(Object) existsById}; if a record already exists the
     *       method throws {@link DuplicateRecordException} with the verbatim
     *       &ldquo;User ID already exist...&rdquo; text, reproducing the
     *       {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} branch ({@code COUSR01C} L260-266).</li>
     *   <li><strong>Build &amp; persist.</strong> A {@link UserSecurity} is populated from the request
     *       (the COBOL {@code MOVE}s into {@code SEC-USR-*}); the password is BCrypt-hashed (the single
     *       permitted behavioral change, C-003) and the record is saved (the
     *       {@code EXEC CICS WRITE}). A late duplicate surfaced by the database
     *       ({@link DataIntegrityViolationException}) is rethrown as the same
     *       {@link DuplicateRecordException} as a race-condition backstop.</li>
     *   <li><strong>Echo back.</strong> A {@link UserSecurityDto} echoing the persisted user id, first
     *       name, last name and type is returned (the COBOL success
     *       &ldquo;User &lt;id&gt; has been added ...&rdquo;). The password is deliberately left unset
     *       &mdash; the credential/hash is never echoed.</li>
     * </ol>
     *
     * @param request the add-user request payload ({@code COUSR1AI} symbolic map replacement); its
     *                first name, last name, user id, password and user type are validated and persisted
     * @return a {@link UserSecurityDto} echoing the persisted user id, first name, last name and user
     *         type (never the password)
     * @throws ValidationException      if any required field is empty, carrying the verbatim COBOL
     *                                  message for the first blank field (HTTP 400 via the advice layer)
     * @throws DuplicateRecordException if a user with the same id already exists, carrying the verbatim
     *                                  &ldquo;User ID already exist...&rdquo; text (HTTP 409 via the
     *                                  advice layer)
     */
    // Admin-only: COUSR01C ran only under the CDEMO-USRTYP-ADMIN context. The method-level ADMIN
    // gate is defence in depth behind the /api/admin/** route rule in SecurityConfig, and protects
    // the operation from internal (non-HTTP) misuse. Enforced via @EnableMethodSecurity.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserSecurityDto addUser(UserSecurityDto request) {
        // CWE-20 null-body guard: the controller omits @Valid to preserve COBOL message ordering, so a
        // JSON `null` body would otherwise NPE on the first emptiness edit (HTTP 500). Map an absent
        // body to the empty-first-name branch -> the same verbatim first-error (HTTP 400).
        if (request == null) {
            throw new ValidationException(MSG_FIRST_NAME_EMPTY);
        }
        // -------------------------------------------------------------------------------------------
        // Step 1 - PROCESS-ENTER-KEY EVALUATE TRUE (COUSR01C L117-151): ordered, first-error-wins
        // emptiness edits. Each guarded throw reproduces one EVALUATE branch in the exact COBOL order;
        // the first blank field short-circuits the rest (Java methods do not fall through). The
        // verbatim message text is preserved via the single-String ValidationException constructor.
        // NOTE: Bean Validation (@Valid) at the controller cannot guarantee this field ordering or the
        // exact message text, so the authoritative ordered check lives here (agent prompt §3).
        // -------------------------------------------------------------------------------------------

        // WHEN FNAMEI = SPACES OR LOW-VALUES (branch 1)
        if (isBlank(request.getFirstName())) {
            throw new ValidationException(MSG_FIRST_NAME_EMPTY);
        }
        // WHEN LNAMEI = SPACES OR LOW-VALUES (branch 2)
        if (isBlank(request.getLastName())) {
            throw new ValidationException(MSG_LAST_NAME_EMPTY);
        }
        // WHEN USERIDI = SPACES OR LOW-VALUES (branch 3)
        if (isBlank(request.getUserId())) {
            throw new ValidationException(MSG_USER_ID_EMPTY);
        }
        // WHEN PASSWDI = SPACES OR LOW-VALUES (branch 4)
        if (isBlank(request.getPassword())) {
            throw new ValidationException(MSG_PASSWORD_EMPTY);
        }
        // WHEN USRTYPEI = SPACES OR LOW-VALUES (branch 5). The DTO carries the role as the type-safe
        // UserType enum, so the COBOL "all spaces / low-values" emptiness test maps to a null check
        // (an absent/blank user type deserializes to null); a non-null enum is one of the legal
        // 'A'/'U' codes by construction.
        if (request.getUserType() == null) {
            throw new ValidationException(MSG_USER_TYPE_EMPTY);
        }

        // All five edits passed -> the COBOL "IF NOT ERR-FLG-ON" branch (COUSR01C L153) proceeds to
        // the MOVEs and the WRITE.

        // The VSAM key SEC-USR-ID is the 8-byte cluster key. Trim the validated user id so the
        // existence probe and the stored primary key use one canonical value (agent prompt §4.2);
        // getUserId() is guaranteed non-blank by branch 3 above, so trim() is safe.
        final String userId = request.getUserId().trim();

        // -------------------------------------------------------------------------------------------
        // Step 1b - field-width guards (QA F5 / F-PWD-001). COUSR01C relied on the fixed-width BMS map
        // fields (FNAMEI/LNAMEI PIC X(20), USERIDI PIC X(08), PASSWDI PIC X(08)) to bound these values;
        // the REST contract does not, so enforce the PIC widths here, AFTER the empty cascade (so a blank
        // field still wins its "...can NOT be empty..." message) and BEFORE the duplicate probe / WRITE
        // (so an over-length value is a 400, never a DataIntegrityViolationException 500). First-error-
        // wins, in the COBOL field order (first name, last name, user id, password). First/last name and
        // password are validated RAW: the names are stored RAW (the MOVEs below copy them verbatim, no
        // trim) and the password is BCrypt-encoded from the RAW typed value below, so the raw character
        // count is the legacy 8-char screen-field contract (the BCrypt digest itself widens to the
        // VARCHAR(72) column; this guard is the INPUT-contract parity check, not 500-prevention). The
        // user id is validated on its trimmed canonical key. getFirstName()/getLastName()/getPassword()
        // are non-blank here (empty-cascade branches already ran), so length() is safe to call.
        // -------------------------------------------------------------------------------------------
        if (request.getFirstName().length() > 20) {
            throw new ValidationException(MSG_FIRST_NAME_TOO_LONG);
        }
        if (request.getLastName().length() > 20) {
            throw new ValidationException(MSG_LAST_NAME_TOO_LONG);
        }
        if (userId.length() > 8) {
            throw new ValidationException(MSG_USER_ID_TOO_LONG);
        }
        if (request.getPassword().length() > 8) {
            throw new ValidationException(MSG_PASSWORD_TOO_LONG);
        }

        // -------------------------------------------------------------------------------------------
        // Step 2 - WRITE-USER-SEC-FILE duplicate branch (COUSR01C L260-266). EXEC CICS WRITE returned
        // DFHRESP(DUPKEY)/DFHRESP(DUPREC) when the key already existed; reproduce that deterministically
        // with an existence pre-check that throws the verbatim "User ID already exist..." text.
        // -------------------------------------------------------------------------------------------
        if (userSecurityRepository.existsById(userId)) {
            throw new DuplicateRecordException(MSG_USER_ID_ALREADY_EXISTS);
        }

        // -------------------------------------------------------------------------------------------
        // Step 3 - the COBOL MOVEs (COUSR01C L154-158): copy the validated inputs into SEC-USR-*.
        // First/last name and type are moved straight across (no enhancement); the user type is the
        // type-safe UserType enum the DTO already carries.
        // -------------------------------------------------------------------------------------------
        final UserSecurity entity = new UserSecurity();
        entity.setSecUsrId(userId);                       // MOVE USERIDI  TO SEC-USR-ID  (trimmed key)
        entity.setSecUsrFname(request.getFirstName());    // MOVE FNAMEI   TO SEC-USR-FNAME
        entity.setSecUsrLname(request.getLastName());     // MOVE LNAMEI   TO SEC-USR-LNAME
        entity.setSecUsrType(request.getUserType());      // MOVE USRTYPEI TO SEC-USR-TYPE ('A'/'U')

        // C-003 / AAP §0.7.2 - THE ONE PERMITTED BEHAVIORAL CHANGE: the legacy program executed
        // MOVE PASSWDI TO SEC-USR-PWD, storing the raw 8-char PLAINTEXT password. Here the password is
        // BCrypt-hashed via the injected shared PasswordEncoder before persistence (stored form widens
        // to the 60-char digest in the VARCHAR(72) `password` column). The INPUT contract stays the
        // legacy 8-char @Size(max=8); NO new password-complexity rule is added (no enhancement).
        entity.setSecUsrPwd(passwordEncoder.encode(request.getPassword()));

        // -------------------------------------------------------------------------------------------
        // Step 4 - EXEC CICS WRITE DATASET('USRSEC') (COUSR01C L240-248) -> JpaRepository.save.
        // The existsById probe above handles the normal duplicate case; a DataIntegrityViolationException
        // here is the concurrent-insert race backstop (another transaction inserted the same key between
        // the probe and this save). Rethrow it as the SAME verbatim DuplicateRecordException, preserving
        // the original cause, so the database error never leaks past the service boundary.
        // -------------------------------------------------------------------------------------------
        final UserSecurity saved;
        try {
            saved = userSecurityRepository.save(entity);
        } catch (DataIntegrityViolationException duplicateKeyRace) {
            throw new DuplicateRecordException(MSG_USER_ID_ALREADY_EXISTS, duplicateKeyRace);
        }

        // -------------------------------------------------------------------------------------------
        // Step 5 - WHEN DFHRESP(NORMAL) success (COUSR01C L251-259): the COBOL built
        // "User <id> has been added ..." and cleared the screen. In REST the success outcome is the
        // populated response DTO; HTTP status/messaging is centralized in WebConfig's advice. The
        // password is intentionally NOT set on the response (it is WRITE_ONLY; the BCrypt hash is
        // never echoed back).
        // -------------------------------------------------------------------------------------------
        final UserSecurityDto response = new UserSecurityDto();
        response.setUserId(saved.getSecUsrId());
        response.setFirstName(saved.getSecUsrFname());
        response.setLastName(saved.getSecUsrLname());
        response.setUserType(saved.getSecUsrType());
        // response.password deliberately left null - never echo the credential/hash.
        return response;
    }

    /**
     * Reproduces the COBOL {@code SPACES OR LOW-VALUES} emptiness test for a text field.
     *
     * <p>A value is treated as &ldquo;empty&rdquo; when it is {@code null} (the {@code LOW-VALUES}
     * uninitialized case) or contains only whitespace after trimming (the all-{@code SPACES} case),
     * matching the way {@code COUSR01C}'s {@code EVALUATE TRUE} edits rejected blank input fields.</p>
     *
     * @param value the field value to test (may be {@code null})
     * @return {@code true} if {@code value} is {@code null} or blank after trimming; {@code false}
     *         otherwise
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
