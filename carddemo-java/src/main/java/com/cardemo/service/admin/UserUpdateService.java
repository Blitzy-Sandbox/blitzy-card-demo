package com.cardemo.service.admin;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Update-user service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COUSR02C.cbl}</strong> (CICS transaction {@code CU02}, BMS map
 * {@code COUSR2A} / mapset {@code COUSR02}, &ldquo;Update User&rdquo;). The legacy program first
 * <em>loaded</em> a user record for edit (keyed {@code READ ... UPDATE} on the eight-character
 * {@code SEC-USR-ID} of the {@code USRSEC} VSAM&nbsp;KSDS) and then, on PF5, <em>applied</em> the edits
 * back with an {@code EXEC CICS REWRITE} &mdash; but only when at least one field had actually changed.
 *
 * <h2>Two logical operations, two methods (key insight)</h2>
 * <p>{@code COUSR02C} fused two operations behind one 3270 screen: {@code PROCESS-ENTER-KEY} loaded the
 * record for edit, and {@code UPDATE-USER-INFO} (PF5) applied the update. The migrated contract keeps
 * them as two distinct methods so the stateless REST controller can wire <strong>GET</strong> (load)
 * and <strong>PUT</strong> (update) independently:</p>
 * <ul>
 *   <li>{@link #loadUser(String)} &larr; {@code PROCESS-ENTER-KEY} (read-for-edit).</li>
 *   <li>{@link #updateUser(UserSecurityDto)} &larr; {@code UPDATE-USER-INFO} (apply changes on PF5).</li>
 * </ul>
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COUSR02C}'s observable behavior <em>exactly</em>. In particular it
 * preserves the program's {@code UPDATE-USER-INFO} {@code EVALUATE TRUE} field-edit cascade
 * (&ldquo;first-error-wins&rdquo;) verbatim &mdash; the same field order
 * (user&nbsp;id&nbsp;&rarr;&nbsp;first&nbsp;name&nbsp;&rarr;&nbsp;last&nbsp;name&nbsp;&rarr;&nbsp;password&nbsp;&rarr;&nbsp;user&nbsp;type),
 * the same emptiness test ({@code SPACES OR LOW-VALUES}) and the same user-facing message text &mdash;
 * and it preserves the program's distinctive <strong>per-field change detection</strong>: the record is
 * rewritten only if at least one field differs from its stored value, otherwise the COBOL surfaced the
 * red &ldquo;Please modify to update ...&rdquo; feedback and performed no write. Per the Minimal Change
 * Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized beyond the technology transition: no
 * new fields, no new endpoints and (crucially) <strong>no new password-complexity rules</strong>. The
 * COBOL is read-only reference material at the frozen baseline commit SHA {@code 27d6c6f} and is never
 * copied into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>The single permitted behavioral change &mdash; BCrypt (constraint C-003 / AAP &sect;0.7.2)</h2>
 * <p><strong>This service is one of the migration's two sanctioned BCrypt sites</strong> (the other is
 * {@code UserAddService}). The crux is the password change-detection step. The legacy {@code COUSR02C}
 * compared the typed password directly to the stored <em>plaintext</em>
 * ({@code IF PASSWDI NOT = SEC-USR-PWD}) and, on a difference, moved the new plaintext into
 * {@code SEC-USR-PWD}. The stored value is now a one-way <strong>BCrypt</strong> hash
 * ({@code password VARCHAR(72)} on the {@code user_security} table), so a literal string comparison is
 * impossible. The semantically-correct, documented replacement is:</p>
 * <pre>{@code
 * boolean passwordChanged = !passwordEncoder.matches(request.getPassword(), entity.getSecUsrPwd());
 * if (passwordChanged) {
 *     entity.setSecUsrPwd(passwordEncoder.encode(request.getPassword()));
 * }
 * }</pre>
 * <p>i.e. the password is treated as &ldquo;changed&rdquo; precisely when the typed value does
 * <em>not</em> already match the stored hash, and on a change it is re-encoded with BCrypt rather than
 * stored as plaintext. The <em>input</em> password keeps the preserved legacy 8-character contract
 * ({@code @Size(max = 8)} on the request DTO); only the <em>stored</em> form is the BCrypt digest, and
 * <strong>no</strong> additional credential rule (length floor, character classes, history, expiry,
 * &hellip;) is introduced. The encoder is the shared {@link PasswordEncoder} bean declared in
 * {@code config/SecurityConfig}; this service never instantiates its own.</p>
 *
 * <h2>Decimal precision is not applicable here (AAP &sect;0.7.3)</h2>
 * <p>The {@code USRSEC} record ({@code app/cpy/CSUSR01Y.cpy}) is entirely textual ({@code PIC X}):
 * user id, first name, last name, password and type. It contains <strong>no</strong>
 * {@code COMP-3}/{@code COMP} or {@code PIC ...V99} numeric field, so there is no {@code float},
 * {@code double} or {@link java.math.BigDecimal} anywhere in this service.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>CICS {@code READ ... UPDATE} + {@code REWRITE} &rarr; {@code findBySecUsrId} +
 *       {@code save} inside one {@code @Transactional} unit of work.</strong> JPA dirty-checking plus
 *       an explicit {@code save} reproduces the COBOL read&ndash;update&ndash;rewrite as a single
 *       atomic operation. An absent record ({@code DFHRESP(NOTFND)}) becomes an empty
 *       {@link Optional} surfaced as {@link RecordNotFoundException}.</li>
 *   <li><strong>{@code EVALUATE TRUE} field-edit cascade &rarr; ordered guarded checks.</strong> The
 *       authoritative, ordered emptiness validation lives here (not in bean {@code @Valid}): the first
 *       blank field throws a {@link ValidationException} carrying the verbatim COBOL message and
 *       short-circuits the rest (Java methods do not fall through).</li>
 *   <li><strong>{@code SPACES OR LOW-VALUES} emptiness test &rarr; {@link #isBlank(String)}.</strong>
 *       A value is &ldquo;empty&rdquo; when {@code null} or blank after trimming, mirroring the COBOL
 *       test that treated all-spaces and uninitialized ({@code LOW-VALUES}) fields alike.</li>
 *   <li><strong>Fixed-width field comparison &rarr; {@link #normalize(String)}.</strong> COBOL
 *       compared two right-space-padded {@code PIC X} fields, so trailing spaces were insignificant;
 *       {@link #normalize(String)} reproduces that by null-safe trimming both sides of each
 *       change-detection comparison.</li>
 *   <li><strong>Plaintext password compare/move &rarr; BCrypt {@code matches}/{@code encode}.</strong>
 *       See the dedicated section above &mdash; the single permitted behavioral change (C-003).</li>
 *   <li><strong>CICS map I/O ({@code RECEIVE MAP}/{@code SEND MAP}) &rarr; DTO.</strong> The
 *       {@code COUSR2AI} symbolic input map becomes the {@link UserSecurityDto} request/response; the
 *       success {@code SEND} (the COBOL {@code STRING 'User ' SEC-USR-ID ' has been updated ...'}) is
 *       conveyed by returning the populated DTO. HTTP status/messaging is centralized in the
 *       {@code config/WebConfig} {@code @RestControllerAdvice}.</li>
 *   <li><strong>Pseudo-conversational COMMAREA &rarr; method parameter/return.</strong> No server-side
 *       conversation state is retained: the request arrives as the method argument and the echoed
 *       result is the return value.</li>
 *   <li><strong>{@code SEC-USR-TYPE PIC X(01)} &rarr; {@link UserType}.</strong> The DTO already
 *       carries the role as the type-safe {@link UserType} enum ({@code ADMIN('A')} / {@code USER('U')}),
 *       so the {@code IF USRTYPEI NOT = SEC-USR-TYPE} comparison is a direct enum comparison and the
 *       legacy single-character storage semantics are preserved by the entity's converter.</li>
 * </ul>
 *
 * <h2>Layering &amp; security</h2>
 * <p>This is a {@link Service @Service} that returns a DTO only. It builds no {@code ResponseEntity},
 * sets no HTTP status and renders no message line (those concerns are centralized in the
 * {@code config/WebConfig} {@code @RestControllerAdvice}). The response <strong>never</strong> carries
 * the credential: the echoed DTO leaves {@code password} unset, and the password/hash is never logged.
 * {@link #updateUser(UserSecurityDto)} is annotated {@link Transactional @Transactional} so the load and
 * the conditional rewrite share one unit of work (the sole {@code SYNCPOINT}-style boundary here); the
 * thrown {@link ValidationException} and {@link RecordNotFoundException} are unchecked, so they trigger
 * the standard Spring rollback automatically. {@link #loadUser(String)} is
 * {@link Transactional @Transactional(readOnly = true)} because it only reads.</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see UserSecurityDto
 * @see UserType
 * @see PasswordEncoder
 */
@Service
public class UserUpdateService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message text (COUSR02C.cbl). Kept byte-identical to guarantee user-facing parity
    // and surfaced through the single-String exception constructors (which expose the text verbatim via
    // getMessage()). Package-private so the behavioral-parity unit test can assert against the same
    // canonical literals. Mind the trailing spaces/dots -- they are part of the external contract.
    // -----------------------------------------------------------------------------------------------

    /** {@code COUSR02C} L148/L182: empty user-id edit ({@code UPDATE-USER-INFO} branch 1; also the sole {@code PROCESS-ENTER-KEY} edit). */
    static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** {@code COUSR02C} L188: empty first-name edit ({@code UPDATE-USER-INFO} branch 2). */
    static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** {@code COUSR02C} L194: empty last-name edit ({@code UPDATE-USER-INFO} branch 3). */
    static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** {@code COUSR02C} L200: empty password edit ({@code UPDATE-USER-INFO} branch 4). */
    static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** {@code COUSR02C} L206: empty user-type edit ({@code UPDATE-USER-INFO} branch 5). */
    static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    // Length guards (QA F5). COUSR02C had no "too long" edit because the BMS map fields were
    // fixed-width PICs (FNAMEI/LNAMEI PIC X(20)) that could not overflow; the REST contract has no such
    // bound, so an over-length first/last name would otherwise reach the VARCHAR(20) column and surface
    // as a 500. These reject it as a 400 instead, preserving the external field-width contract
    // (AAP §0.7.2). Only first/last name need guarding: the user id is the lookup key (an over-length
    // id simply finds no row -> the verbatim "User ID NOT found..." 404), and the password widens to a
    // BCrypt digest that cannot overflow VARCHAR(72). The ellipsis mirrors the "...empty..." style.

    /** First-name length guard: {@code SEC-USR-FNAME PIC X(20)} / {@code user_security.first_name VARCHAR(20)} (QA F5). */
    static final String MSG_FIRST_NAME_TOO_LONG = "First Name can NOT be longer than 20 characters...";

    /** Last-name length guard: {@code SEC-USR-LNAME PIC X(20)} / {@code user_security.last_name VARCHAR(20)} (QA F5). */
    static final String MSG_LAST_NAME_TOO_LONG = "Last Name can NOT be longer than 20 characters...";

    /**
     * {@code COUSR02C} L342/L379: not-found message from the {@code READ-USER-SEC-FILE} and
     * {@code UPDATE-USER-SEC-FILE} {@code WHEN DFHRESP(NOTFND)} branches.
     */
    static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /**
     * {@code COUSR02C} L239: the &ldquo;no change&rdquo; feedback from {@code UPDATE-USER-INFO}'s
     * {@code ELSE} branch (shown in red, {@code DFHRED}) when {@code WS-USR-MODIFIED} stayed {@code N}.
     * Note the single space before the ellipsis is preserved verbatim ({@code "update ..."}).
     */
    static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators. Constructor injection with private-final fields (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    /** Data-access for the {@code USRSEC} VSAM replacement (keyed read + rewrite). */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Shared BCrypt {@link PasswordEncoder} (the {@code config/SecurityConfig} bean). Injected rather
     * than instantiated so a single hashing policy governs this update path, the add path and sign-on
     * verification (constraint C-003). Used for {@code matches(...)} (change detection) and
     * {@code encode(...)} (re-hash on change).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its required collaborators.
     *
     * @param userSecurityRepository repository for the {@code USRSEC} VSAM replacement; supplies the
     *                               {@link UserSecurityRepository#findBySecUsrId(String) findBySecUsrId}
     *                               keyed read that reproduces {@code EXEC CICS READ DATASET('USRSEC')
     *                               ... UPDATE} and the inherited
     *                               {@link UserSecurityRepository#save(Object) save} that reproduces the
     *                               {@code EXEC CICS REWRITE}
     * @param passwordEncoder        the shared BCrypt {@link PasswordEncoder} bean used to detect a
     *                               password change ({@code matches}) and re-hash it ({@code encode})
     *                               (constraint C-003); never {@code null}
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Loads a user record for edit, reproducing {@code COUSR02C}'s {@code PROCESS-ENTER-KEY}
     * (read-for-edit) path.
     *
     * <p>Processing order mirrors the COBOL exactly:</p>
     * <ol>
     *   <li><strong>User-id edit.</strong> {@code PROCESS-ENTER-KEY} ran one {@code EVALUATE TRUE}
     *       whose only branch checked {@code WHEN USRIDINI = SPACES OR LOW-VALUES} ({@code COUSR02C}
     *       L145-155). A blank id throws a {@link ValidationException} carrying the verbatim
     *       &ldquo;User ID can NOT be empty...&rdquo; message. No other field is edited on load.</li>
     *   <li><strong>Keyed read.</strong> The COBOL moved {@code USRIDINI} into {@code SEC-USR-ID} and
     *       performed {@code READ-USER-SEC-FILE} ({@code EXEC CICS READ DATASET('USRSEC') ... UPDATE},
     *       {@code COUSR02C} L162-163, L320-331). Here {@link UserSecurityRepository#findBySecUsrId(String)}
     *       loads the record; an empty {@link Optional} reproduces {@code DFHRESP(NOTFND)} and throws
     *       {@link RecordNotFoundException} with the verbatim &ldquo;User ID NOT found...&rdquo; text
     *       ({@code COUSR02C} L340-344).</li>
     *   <li><strong>Populate for display.</strong> On {@code DFHRESP(NORMAL)} the COBOL moved
     *       {@code SEC-USR-FNAME}/{@code SEC-USR-LNAME}/{@code SEC-USR-PWD}/{@code SEC-USR-TYPE} into the
     *       map fields and prompted &ldquo;Press PF5 key to save your updates ...&rdquo; ({@code COUSR02C}
     *       L166-171, L336). In REST the populated response DTO <em>is</em> the success; the PF5 prompt
     *       is 3270 screen chrome with no REST channel and is intentionally not reproduced.</li>
     * </ol>
     *
     * <p><strong>The password is deliberately not returned.</strong> {@code COUSR02C} moved
     * {@code SEC-USR-PWD} into the {@code PASSWDI} map field for display, but post-C-003 the stored value
     * is a one-way BCrypt hash and the original plaintext is unrecoverable; the {@link UserSecurityDto}
     * {@code password} field is moreover {@code WRITE_ONLY} (never serialized). The legacy
     * plaintext-display behavior is therefore intentionally <strong>not</strong> reproduced &mdash; a
     * direct, documented consequence of the BCrypt upgrade (constraint C-003, AAP &sect;0.7.2).</p>
     *
     * @param userId the 8-character {@code USRSEC} key to load ({@code USRIDINI} on the update screen);
     *               must be non-blank
     * @return a {@link UserSecurityDto} populated with the user id, first name, last name and user type
     *         (the password is never populated)
     * @throws ValidationException     if {@code userId} is blank, carrying the verbatim
     *                                 &ldquo;User ID can NOT be empty...&rdquo; message (HTTP 400 via the
     *                                 advice layer)
     * @throws RecordNotFoundException if no user has that id, carrying the verbatim
     *                                 &ldquo;User ID NOT found...&rdquo; message (HTTP 404 via the advice
     *                                 layer)
     */
    // Admin-only: COUSR02C ran only under the CDEMO-USRTYP-ADMIN context. The method-level ADMIN
    // gate is defence in depth behind the /api/admin/** route rule in SecurityConfig, and protects
    // the operation from internal (non-HTTP) misuse. Enforced via @EnableMethodSecurity.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public UserSecurityDto loadUser(String userId) {
        // -------------------------------------------------------------------------------------------
        // PROCESS-ENTER-KEY EVALUATE TRUE (COUSR02C L145-155): the only edit on load is the user id.
        // WHEN USRIDINI = SPACES OR LOW-VALUES -> verbatim "User ID can NOT be empty..." via the
        // single-String ValidationException constructor (which surfaces the text exactly).
        // -------------------------------------------------------------------------------------------
        if (isBlank(userId)) {
            throw new ValidationException(MSG_USER_ID_EMPTY);
        }

        // MOVE USRIDINI TO SEC-USR-ID + PERFORM READ-USER-SEC-FILE (COUSR02C L162-163, L320-331).
        // The 8-byte VSAM key is trimmed so the keyed read matches the canonical stored primary key
        // (the add path stores the trimmed user id as the @Id); isBlank above guarantees a non-empty
        // result after trimming.
        final String key = userId.trim();
        final Optional<UserSecurity> existing = userSecurityRepository.findBySecUsrId(key);

        // READ-USER-SEC-FILE WHEN DFHRESP(NOTFND) -> "User ID NOT found..." (COUSR02C L340-344). The
        // WHEN OTHER abnormal-RESP branch ("Unable to lookup User...", L349) has no literal analogue:
        // an unexpected DataAccessException propagates to the centralized advice as the generic
        // infrastructure-error path.
        final UserSecurity entity =
                existing.orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));

        // WHEN DFHRESP(NORMAL): populate the display fields (COBOL MOVE SEC-USR-* TO map fields,
        // L166-171). The "Press PF5 key to save your updates ..." prompt (L336) is screen chrome with
        // no REST channel; the populated DTO is the success outcome.
        final UserSecurityDto response = new UserSecurityDto();
        response.setUserId(entity.getSecUsrId());          // <- SEC-USR-ID
        response.setFirstName(entity.getSecUsrFname());    // <- SEC-USR-FNAME (MOVE ... TO FNAMEI)
        response.setLastName(entity.getSecUsrLname());     // <- SEC-USR-LNAME (MOVE ... TO LNAMEI)
        response.setUserType(entity.getSecUsrType());      // <- SEC-USR-TYPE (MOVE ... TO USRTYPEI)
        // password deliberately left null: the legacy MOVE SEC-USR-PWD TO PASSWDI display is NOT
        // reproduced (one-way BCrypt hash; DTO field is WRITE_ONLY) -- a documented C-003 consequence.
        return response;
    }

    /**
     * Applies an edit to an existing user, reproducing {@code COUSR02C}'s {@code UPDATE-USER-INFO}
     * (PF5) path end to end.
     *
     * <p>Processing order mirrors the COBOL dispatch exactly:</p>
     * <ol>
     *   <li><strong>Ordered field edits (first-error-wins).</strong> The {@code EVALUATE TRUE} cascade
     *       ({@code COUSR02C} L179-213) checks emptiness in the exact COBOL order &mdash; user id, first
     *       name, last name, password, user type &mdash; and the <em>first</em> blank one throws a
     *       {@link ValidationException} carrying that field's verbatim message, short-circuiting the
     *       remaining checks. (Note this order differs from the add screen {@code COUSR01C}, which edited
     *       first name before user id; each program's order is preserved as-is.)</li>
     *   <li><strong>Keyed read for update.</strong> The (trimmed) user id is loaded with
     *       {@link UserSecurityRepository#findBySecUsrId(String)} ({@code MOVE USRIDINI TO SEC-USR-ID} +
     *       {@code PERFORM READ-USER-SEC-FILE}, {@code COUSR02C} L216-217); an absent record reproduces
     *       {@code DFHRESP(NOTFND)} as {@link RecordNotFoundException}
     *       (&ldquo;User ID NOT found...&rdquo;).</li>
     *   <li><strong>Per-field change detection.</strong> Each of first name, last name, password and
     *       user type is compared to its stored value in the COBOL order ({@code COUSR02C} L219-234); a
     *       differing field is overwritten on the managed entity and a &ldquo;modified&rdquo; flag is
     *       raised, reproducing the {@code WS-USR-MODIFIED} bookkeeping. Text comparisons are
     *       trailing-space-insensitive (the COBOL fixed-width {@code PIC X} compare, via
     *       {@link #normalize(String)}); the password uses the BCrypt {@code matches} rule (see the
     *       class-level C-003 note); the user type is a direct {@link UserType} enum comparison.</li>
     *   <li><strong>Conditional rewrite.</strong> If at least one field changed
     *       ({@code IF USR-MODIFIED-YES}) the record is saved &mdash; the {@code EXEC CICS REWRITE} of
     *       {@code UPDATE-USER-SEC-FILE} ({@code COUSR02C} L236-237, L358-366). Otherwise the COBOL
     *       {@code ELSE} branch showed the red &ldquo;Please modify to update ...&rdquo; feedback and
     *       wrote nothing ({@code COUSR02C} L238-242); here that is surfaced as a
     *       {@link ValidationException} so the no-op is explicit and <strong>no</strong> save occurs.</li>
     *   <li><strong>Echo back.</strong> On a successful rewrite the COBOL built
     *       &ldquo;User &lt;id&gt; has been updated ...&rdquo; ({@code COUSR02C} L369-376); in REST the
     *       success outcome is the populated response DTO (id, first name, last name, type), with the
     *       password deliberately left unset &mdash; the credential/hash is never echoed.</li>
     * </ol>
     *
     * @param request the update-user request payload ({@code COUSR2AI} symbolic map replacement); its
     *                user id (the {@code USRSEC} key), first name, last name, password and user type are
     *                validated and conditionally applied
     * @return a {@link UserSecurityDto} echoing the persisted user id, first name, last name and user
     *         type (never the password)
     * @throws ValidationException     if any required field is empty (carrying the verbatim COBOL message
     *                                 for the first blank field) or if no field changed (carrying the
     *                                 verbatim &ldquo;Please modify to update ...&rdquo; message); HTTP 400
     *                                 via the advice layer
     * @throws RecordNotFoundException if no user has the supplied id, carrying the verbatim
     *                                 &ldquo;User ID NOT found...&rdquo; message (HTTP 404 via the advice
     *                                 layer)
     */
    // Admin-only: COUSR02C ran only under the CDEMO-USRTYP-ADMIN context. The method-level ADMIN
    // gate is defence in depth behind the /api/admin/** route rule in SecurityConfig, and protects
    // the operation from internal (non-HTTP) misuse. Enforced via @EnableMethodSecurity.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserSecurityDto updateUser(UserSecurityDto request) {
        // -------------------------------------------------------------------------------------------
        // Step 1 - UPDATE-USER-INFO EVALUATE TRUE (COUSR02C L179-213): ordered, first-error-wins
        // emptiness edits. Each guarded throw reproduces one EVALUATE branch in the EXACT COBOL order
        // (user id -> first name -> last name -> password -> user type); the first blank field
        // short-circuits the rest (Java methods do not fall through). Verbatim message text is
        // preserved via the single-String ValidationException constructor. NOTE: Bean Validation
        // (@Valid) at the controller cannot guarantee this field ordering or the exact message text,
        // so the authoritative ordered check lives here (agent prompt §3).
        // -------------------------------------------------------------------------------------------

        // WHEN USRIDINI = SPACES OR LOW-VALUES (branch 1)
        if (isBlank(request.getUserId())) {
            throw new ValidationException(MSG_USER_ID_EMPTY);
        }
        // WHEN FNAMEI = SPACES OR LOW-VALUES (branch 2)
        if (isBlank(request.getFirstName())) {
            throw new ValidationException(MSG_FIRST_NAME_EMPTY);
        }
        // WHEN LNAMEI = SPACES OR LOW-VALUES (branch 3)
        if (isBlank(request.getLastName())) {
            throw new ValidationException(MSG_LAST_NAME_EMPTY);
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

        // -------------------------------------------------------------------------------------------
        // Step 1b - field-width guards (QA F5). Enforce the PIC X(20) first/last-name widths here,
        // AFTER the empty cascade (so a blank field still wins its "...can NOT be empty..." message) and
        // BEFORE the keyed READ / rewrite (so an over-length name is a 400, never a
        // DataIntegrityViolationException 500 on the VARCHAR(20) column). First-error-wins, in the
        // COBOL field order (first name, last name). Validated on the normalized (trimmed) value because
        // the normalized value is exactly what is stored on a change (see Step 3); trailing 3270 padding
        // is a storage artifact, not data, so it must not count toward the width. getFirstName()/
        // getLastName() are non-blank here (empty cascade branches 2-3 already ran). The user id is the
        // lookup key (an over-length id finds no row -> "User ID NOT found..." 404, so no guard here) and
        // the password widens to a BCrypt digest that cannot overflow, so neither is length-guarded.
        // -------------------------------------------------------------------------------------------
        if (normalize(request.getFirstName()).length() > 20) {
            throw new ValidationException(MSG_FIRST_NAME_TOO_LONG);
        }
        if (normalize(request.getLastName()).length() > 20) {
            throw new ValidationException(MSG_LAST_NAME_TOO_LONG);
        }

        // -------------------------------------------------------------------------------------------
        // Step 2 - MOVE USRIDINI TO SEC-USR-ID + PERFORM READ-USER-SEC-FILE (COUSR02C L216-217). The
        // CICS READ ... UPDATE becomes a keyed findBySecUsrId; the surrounding @Transactional method
        // makes the load + conditional rewrite one unit of work (JPA dirty-checking + save reproduces
        // READ-UPDATE-REWRITE). The 8-byte key is trimmed to match the canonical stored primary key.
        // WHEN DFHRESP(NOTFND) -> "User ID NOT found..." (COUSR02C L340-344). The abnormal WHEN OTHER
        // branch ("Unable to Update User...", L386) has no literal analogue: an unexpected
        // DataAccessException propagates to the centralized advice.
        // -------------------------------------------------------------------------------------------
        final String key = request.getUserId().trim();
        final Optional<UserSecurity> existing = userSecurityRepository.findBySecUsrId(key);
        final UserSecurity entity =
                existing.orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));

        // -------------------------------------------------------------------------------------------
        // Step 3 - per-field change detection (COUSR02C L219-234). WS-USR-MODIFIED starts 'N'; each
        // IF <field> NOT = SEC-USR-<field> overwrites the field and sets the flag 'Y'. The COBOL order
        // is first name, last name, password, user type. Text comparisons are trailing-space-insensitive
        // (fixed-width PIC X compare) via normalize(); the moved/stored value is the normalized input,
        // faithful to MOVE <map field> TO SEC-USR-<field> (3270 padding is a storage artifact, not data).
        // -------------------------------------------------------------------------------------------
        boolean modified = false;

        // IF FNAMEI OF COUSR2AI NOT = SEC-USR-FNAME (COUSR02C L219-222)
        final String inputFirstName = normalize(request.getFirstName());
        if (!inputFirstName.equals(normalize(entity.getSecUsrFname()))) {
            entity.setSecUsrFname(inputFirstName);   // MOVE FNAMEI TO SEC-USR-FNAME
            modified = true;                         // SET USR-MODIFIED-YES TO TRUE
        }

        // IF LNAMEI OF COUSR2AI NOT = SEC-USR-LNAME (COUSR02C L223-226)
        final String inputLastName = normalize(request.getLastName());
        if (!inputLastName.equals(normalize(entity.getSecUsrLname()))) {
            entity.setSecUsrLname(inputLastName);    // MOVE LNAMEI TO SEC-USR-LNAME
            modified = true;                         // SET USR-MODIFIED-YES TO TRUE
        }

        // IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD (COUSR02C L227-230) -- THE C-003 CRUX.
        // The COBOL compared the typed plaintext to the stored plaintext. The stored value is now a
        // one-way BCrypt hash, so a literal string compare is impossible; "changed" is defined as the
        // typed password NOT already matching the stored hash. On a change the new password is
        // re-encoded with BCrypt (never stored as plaintext). request.getPassword() is guaranteed
        // non-null by the branch-4 emptiness edit above. This is the single permitted behavioral
        // change of the migration (constraint C-003, AAP §0.7.2); no new password rule is added.
        final boolean passwordChanged =
                !passwordEncoder.matches(request.getPassword(), entity.getSecUsrPwd());
        if (passwordChanged) {
            entity.setSecUsrPwd(passwordEncoder.encode(request.getPassword())); // MOVE PASSWDI TO SEC-USR-PWD (BCrypt-encoded)
            modified = true;                                                    // SET USR-MODIFIED-YES TO TRUE
        }

        // IF USRTYPEI OF COUSR2AI NOT = SEC-USR-TYPE (COUSR02C L231-234). Direct type-safe enum
        // comparison: the DTO and entity both carry the UserType enum, so identity comparison
        // reproduces the 1-byte 'A'/'U' code compare without converting via UserType.fromCode(...).
        final UserType inputUserType = request.getUserType(); // non-null (passed the branch-5 edit)
        if (inputUserType != entity.getSecUsrType()) {
            entity.setSecUsrType(inputUserType);     // MOVE USRTYPEI TO SEC-USR-TYPE
            modified = true;                         // SET USR-MODIFIED-YES TO TRUE
        }

        // -------------------------------------------------------------------------------------------
        // Step 4 - IF USR-MODIFIED-YES PERFORM UPDATE-USER-SEC-FILE ELSE "Please modify ..."
        // (COUSR02C L236-243).
        // -------------------------------------------------------------------------------------------
        if (!modified) {
            // ELSE branch (COUSR02C L238-242): no field changed -> the program showed the red
            // "Please modify to update ..." feedback and performed NO REWRITE. In the stateless REST
            // model the faithful way to surface this and prevent a silent no-op is a ValidationException
            // (HTTP 400 via the advice). Crucially, save() is NOT called, exactly as the COBOL wrote
            // nothing; the @Transactional method rolls back with no change persisted.
            throw new ValidationException(MSG_PLEASE_MODIFY);
        }

        // IF USR-MODIFIED-YES (COUSR02C L236-237): PERFORM UPDATE-USER-SEC-FILE -> EXEC CICS REWRITE
        // DATASET('USRSEC') -> JpaRepository.save. WHEN DFHRESP(NORMAL) built "User <id> has been
        // updated ..." (L369-376); in REST the populated response DTO is the success outcome.
        final UserSecurity saved = userSecurityRepository.save(entity);

        // -------------------------------------------------------------------------------------------
        // Step 5 - success echo. The password is intentionally NOT set on the response (it is
        // WRITE_ONLY; the BCrypt hash is never echoed back). HTTP status/messaging is centralized in
        // WebConfig's advice.
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
     * matching the way {@code COUSR02C}'s {@code EVALUATE TRUE} edits rejected blank input fields.</p>
     *
     * @param value the field value to test (may be {@code null})
     * @return {@code true} if {@code value} is {@code null} or blank after trimming; {@code false}
     *         otherwise
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Normalizes a text field to its trimmed, null-safe logical value for change detection.
     *
     * <p>COBOL {@code PIC X} fields are fixed-width and right-space-padded, so a comparison between two
     * such fields ({@code IF FNAMEI NOT = SEC-USR-FNAME}) ignores trailing spaces. Normalizing both
     * sides to a trimmed, non-{@code null} value reproduces that fixed-width comparison semantics
     * exactly and avoids spuriously flagging a record &ldquo;modified&rdquo; on a trailing-space-only
     * difference. The same normalized value is stored on a real change, faithful to the COBOL
     * {@code MOVE} (the 3270 field padding is a storage artifact, not business data).</p>
     *
     * @param value the field value to normalize (may be {@code null})
     * @return the trimmed value, or the empty string when {@code value} is {@code null}
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
