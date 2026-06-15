package com.cardemo.service.admin;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delete-user service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COUSR03C.cbl}</strong> (CICS transaction {@code CU03}, BMS map
 * {@code COUSR3A} / mapset {@code COUSR03}, &ldquo;Delete User&rdquo;). The legacy program first
 * <em>loaded</em> a user record for the delete-confirmation display (keyed {@code READ ... UPDATE} on the
 * eight-character {@code SEC-USR-ID} of the {@code USRSEC} VSAM&nbsp;KSDS) and then, on PF5,
 * <em>applied</em> the delete with an {@code EXEC CICS DELETE DATASET('USRSEC')}.
 *
 * <h2>Two logical operations, two methods (key insight)</h2>
 * <p>{@code COUSR03C} fused two operations behind one 3270 screen: {@code PROCESS-ENTER-KEY} loaded the
 * record so the user could see who they were about to delete, and {@code DELETE-USER-INFO} (PF5) applied
 * the delete. The migrated contract keeps them as two distinct methods so the stateless REST controller
 * can wire <strong>GET</strong> (load/confirm) and <strong>DELETE</strong> independently:</p>
 * <ul>
 *   <li>{@link #loadUser(String)} &larr; {@code PROCESS-ENTER-KEY} (read-for-confirm).</li>
 *   <li>{@link #deleteUser(String)} &larr; {@code DELETE-USER-INFO} (apply the delete on PF5).</li>
 * </ul>
 *
 * <h2>Delete is the simplest of the four COUSR programs (key insight)</h2>
 * <p>Unlike add ({@code COUSR01C}) and update ({@code COUSR02C}), which each ran a multi-field
 * {@code EVALUATE TRUE} edit cascade, delete edits <strong>only the user id</strong>. There is no
 * password field on the delete screen, no per-field change detection and (crucially) <strong>no separate
 * confirmation flag</strong>: PF5 reads the record and then deletes it directly. Per the Minimal Change
 * Clause (AAP &sect;0.7.1) nothing is added beyond the technology transition &mdash; no cascade delete,
 * no extra checks, no new fields and no new endpoints.</p>
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COUSR03C}'s observable behavior <em>exactly</em>. It preserves the
 * single emptiness edit ({@code WHEN USRIDINI = SPACES OR LOW-VALUES}) and its verbatim user-facing
 * message text, and it preserves the read-guard-then-delete order so a missing user id is rejected with
 * the not-found message and <strong>no</strong> row is removed. The COBOL is read-only reference material
 * at the frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only
 * its behavior is reproduced.</p>
 *
 * <h2>No BCrypt here &mdash; no permitted behavioral change (constraint C-003 / AAP &sect;0.7.2)</h2>
 * <p>The migration's single sanctioned behavioral change &mdash; upgrading the stored credential to a
 * BCrypt hash &mdash; lives in {@code UserAddService} (create) and {@code AuthenticationService}
 * (sign-on). <strong>Delete touches no password at all</strong>: the {@code COUSR3A} screen has no
 * password field and {@code COUSR03C} never reads, compares, moves or displays {@code SEC-USR-PWD}.
 * Consequently this service injects <strong>no</strong> {@code PasswordEncoder}, performs no hashing, and
 * never reads the entity's credential. There is <em>no</em> permitted behavioral change in this file; the
 * COBOL is reproduced as-is.</p>
 *
 * <h2>Decimal precision is not applicable here (AAP &sect;0.7.3)</h2>
 * <p>The {@code USRSEC} record ({@code app/cpy/CSUSR01Y.cpy}) is entirely textual ({@code PIC X}): user
 * id, first name, last name, password and type. It contains <strong>no</strong> {@code COMP-3}/{@code COMP}
 * or {@code PIC ...V99} numeric field, so there is no {@code float}, {@code double} or
 * {@link java.math.BigDecimal} anywhere in this service.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>CICS {@code READ ... UPDATE} + {@code DELETE} &rarr; {@code findBySecUsrId} +
 *       {@code delete} inside one {@code @Transactional} unit of work.</strong> The keyed read and the
 *       subsequent delete become a find-then-delete pair under a single transaction. An absent record
 *       ({@code DFHRESP(NOTFND)}) becomes an empty {@link Optional} surfaced as
 *       {@link RecordNotFoundException}. Because the find-then-delete guard re-uses the loaded entity,
 *       the COBOL {@code DELETE-USER-SEC-FILE} {@code WHEN DFHRESP(NOTFND)} branch is effectively
 *       unreachable and maps to the same exception; the abnormal {@code WHEN OTHER} branch on that delete
 *       (&ldquo;Unable to Update User...&rdquo;) is a known copy/paste artifact in the COBOL and is
 *       deliberately <strong>not</strong> surfaced (an unexpected {@code DataAccessException} propagates
 *       to the centralized advice instead).</li>
 *   <li><strong>{@code EVALUATE TRUE} (single check) &rarr; one guarded check.</strong> The only edit is
 *       a blank user id; a blank id throws a {@link ValidationException} carrying the verbatim COBOL
 *       message. &ldquo;Empty&rdquo; means {@code null} or blank after trimming (the COBOL
 *       {@code SPACES OR LOW-VALUES} test), via {@link #isBlank(String)}.</li>
 *   <li><strong>CICS map I/O ({@code SEND MAP}/{@code RECEIVE MAP}) &rarr; DTO.</strong> The
 *       {@code COUSR3AI} symbolic input map becomes the {@link UserSecurityDto} request/response; the
 *       load-success {@code SEND} (the COBOL &ldquo;Press PF5 key to delete this user ...&rdquo; prompt)
 *       and the delete-success {@code SEND} (the COBOL {@code STRING 'User ' SEC-USR-ID ' has been
 *       deleted ...'}) are 3270 screen chrome with no REST channel; the populated response DTO conveys
 *       the outcome instead. HTTP status/messaging is centralized in the {@code config/WebConfig}
 *       {@code @RestControllerAdvice}.</li>
 *   <li><strong>Pseudo-conversational COMMAREA &rarr; method parameter/return.</strong> No server-side
 *       conversation state is retained: the user id arrives as the method argument and the echoed result
 *       is the return value &mdash; the stateless PF5 read-then-delete has no separate &ldquo;confirm&rdquo;
 *       round-trip state.</li>
 * </ul>
 *
 * <h2>Layering &amp; security</h2>
 * <p>This is a {@link Service @Service} that returns a DTO only. It builds no {@code ResponseEntity}, sets
 * no HTTP status and renders no message line (those concerns are centralized in the
 * {@code config/WebConfig} {@code @RestControllerAdvice}). The echoed DTO carries the deleted user's
 * identity, name and role but <strong>never</strong> a credential (delete reads no password).
 * {@link #deleteUser(String)} is annotated {@link Transactional @Transactional} so the keyed read and the
 * delete share one unit of work; the thrown {@link ValidationException} and {@link RecordNotFoundException}
 * are unchecked, so they trigger the standard Spring rollback automatically. {@link #loadUser(String)} is
 * {@link Transactional @Transactional(readOnly = true)} because it only reads.</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see UserSecurityDto
 */
@Service
public class UserDeleteService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message text (COUSR03C.cbl). Kept byte-identical to guarantee user-facing parity
    // and surfaced through the single-String exception constructors (which expose the text verbatim via
    // getMessage()). Package-private so the behavioral-parity unit test can assert against the same
    // canonical literals. Mind the trailing dots -- they are part of the external contract.
    // -----------------------------------------------------------------------------------------------

    /**
     * {@code COUSR03C} L147/L179: empty user-id edit &mdash; the sole {@code PROCESS-ENTER-KEY} edit and
     * the sole {@code DELETE-USER-INFO} {@code EVALUATE TRUE} branch.
     */
    static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * {@code COUSR03C} L289/L325: not-found message from the {@code READ-USER-SEC-FILE} and
     * {@code DELETE-USER-SEC-FILE} {@code WHEN DFHRESP(NOTFND)} branches.
     */
    static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired).
    // NOTE: no PasswordEncoder -- delete handles no credential (see the class-level C-003 note).
    // -----------------------------------------------------------------------------------------------

    /** Data-access for the {@code USRSEC} VSAM replacement (keyed read + delete). */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param userSecurityRepository repository for the {@code USRSEC} VSAM replacement; supplies the
     *                               {@link UserSecurityRepository#findBySecUsrId(String) findBySecUsrId}
     *                               keyed read that reproduces {@code EXEC CICS READ DATASET('USRSEC')
     *                               ... UPDATE} and the inherited
     *                               {@link UserSecurityRepository#delete(Object) delete} that reproduces
     *                               the {@code EXEC CICS DELETE DATASET('USRSEC')}
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Loads a user record for the delete-confirmation display, reproducing {@code COUSR03C}'s
     * {@code PROCESS-ENTER-KEY} (read-for-confirm) path.
     *
     * <p>Processing order mirrors the COBOL exactly:</p>
     * <ol>
     *   <li><strong>User-id edit.</strong> {@code PROCESS-ENTER-KEY} ran one {@code EVALUATE TRUE} whose
     *       only branch checked {@code WHEN USRIDINI = SPACES OR LOW-VALUES} ({@code COUSR03C} L144-154).
     *       A blank id throws a {@link ValidationException} carrying the verbatim
     *       &ldquo;User ID can NOT be empty...&rdquo; message. No other field is edited on load &mdash;
     *       delete validates only the user id.</li>
     *   <li><strong>Keyed read.</strong> The COBOL moved {@code USRIDINI} into {@code SEC-USR-ID} and
     *       performed {@code READ-USER-SEC-FILE} ({@code EXEC CICS READ DATASET('USRSEC') ... UPDATE},
     *       {@code COUSR03C} L160-161, L267-300). Here {@link UserSecurityRepository#findBySecUsrId(String)}
     *       loads the record; an empty {@link Optional} reproduces {@code DFHRESP(NOTFND)} and throws
     *       {@link RecordNotFoundException} with the verbatim &ldquo;User ID NOT found...&rdquo; text
     *       ({@code COUSR03C} L287-292). The abnormal {@code WHEN OTHER} branch
     *       (&ldquo;Unable to lookup User...&rdquo;, L293-299) has no literal analogue: an unexpected
     *       {@code DataAccessException} propagates to the centralized advice as the generic
     *       infrastructure-error path.</li>
     *   <li><strong>Populate for confirmation.</strong> On {@code DFHRESP(NORMAL)} the COBOL moved
     *       {@code SEC-USR-FNAME}/{@code SEC-USR-LNAME}/{@code SEC-USR-TYPE} into the map fields and
     *       prompted &ldquo;Press PF5 key to delete this user ...&rdquo; ({@code COUSR03C} L164-169,
     *       L283-286). In REST the populated response DTO <em>is</em> the success outcome; the PF5 prompt
     *       is 3270 screen chrome with no REST channel and is intentionally not reproduced. There is
     *       <strong>no password field on the delete screen</strong>, so the response credential is left
     *       unset.</li>
     * </ol>
     *
     * @param userId the 8-character {@code USRSEC} key to load ({@code USRIDINI} on the delete screen);
     *               must be non-blank
     * @return a {@link UserSecurityDto} populated with the user id, first name, last name and user type
     *         (the password is never populated &mdash; delete reads no credential)
     * @throws ValidationException     if {@code userId} is blank, carrying the verbatim
     *                                 &ldquo;User ID can NOT be empty...&rdquo; message (HTTP 400 via the
     *                                 advice layer)
     * @throws RecordNotFoundException if no user has that id, carrying the verbatim
     *                                 &ldquo;User ID NOT found...&rdquo; message (HTTP 404 via the advice
     *                                 layer)
     */
    // Admin-only: COUSR03C ran only under the CDEMO-USRTYP-ADMIN context. The method-level ADMIN
    // gate is defence in depth behind the /api/admin/** route rule in SecurityConfig, and protects
    // the operation from internal (non-HTTP) misuse. Enforced via @EnableMethodSecurity.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public UserSecurityDto loadUser(String userId) {
        // -------------------------------------------------------------------------------------------
        // PROCESS-ENTER-KEY EVALUATE TRUE (COUSR03C L144-154): the only edit on load is the user id.
        // WHEN USRIDINI = SPACES OR LOW-VALUES -> verbatim "User ID can NOT be empty..." via the
        // single-String ValidationException constructor (which surfaces the text exactly).
        // -------------------------------------------------------------------------------------------
        if (isBlank(userId)) {
            throw new ValidationException(MSG_USER_ID_EMPTY);
        }

        // MOVE USRIDINI TO SEC-USR-ID + PERFORM READ-USER-SEC-FILE (COUSR03C L160-161, L267-300). The
        // CICS READ ... UPDATE becomes a keyed findBySecUsrId. The 8-byte VSAM key is trimmed so the
        // keyed read matches the canonical stored primary key (the add path stores the trimmed user id
        // as the @Id); isBlank above guarantees a non-empty result after trimming.
        final String key = userId.trim();
        final Optional<UserSecurity> existing = userSecurityRepository.findBySecUsrId(key);

        // READ-USER-SEC-FILE WHEN DFHRESP(NOTFND) -> "User ID NOT found..." (COUSR03C L287-292). The
        // WHEN OTHER abnormal-RESP branch ("Unable to lookup User...", L293-299) has no literal analogue:
        // an unexpected DataAccessException propagates to the centralized advice.
        final UserSecurity entity =
                existing.orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));

        // WHEN DFHRESP(NORMAL): populate the confirmation-display fields (COBOL MOVE SEC-USR-* TO map
        // fields, L164-169). The "Press PF5 key to delete this user ..." prompt (L283) is screen chrome
        // with no REST channel; the populated DTO is the success outcome. The delete screen has NO
        // password field, so the credential is deliberately not populated.
        final UserSecurityDto response = new UserSecurityDto();
        response.setUserId(entity.getSecUsrId());          // <- SEC-USR-ID
        response.setFirstName(entity.getSecUsrFname());    // <- SEC-USR-FNAME (MOVE ... TO FNAMEI)
        response.setLastName(entity.getSecUsrLname());     // <- SEC-USR-LNAME (MOVE ... TO LNAMEI)
        response.setUserType(entity.getSecUsrType());      // <- SEC-USR-TYPE (MOVE ... TO USRTYPEI)
        // password deliberately left null: the delete screen (COUSR3A) carries no password field.
        return response;
    }

    /**
     * Deletes an existing user, reproducing {@code COUSR03C}'s {@code DELETE-USER-INFO} (PF5) path end to
     * end.
     *
     * <p>Processing order mirrors the COBOL dispatch exactly:</p>
     * <ol>
     *   <li><strong>User-id edit (single check).</strong> The {@code EVALUATE TRUE} of
     *       {@code DELETE-USER-INFO} ({@code COUSR03C} L176-186) has exactly one branch
     *       ({@code WHEN USRIDINI = SPACES OR LOW-VALUES}); its {@code WHEN OTHER} simply
     *       {@code CONTINUE}s. A blank id throws a {@link ValidationException} carrying the verbatim
     *       &ldquo;User ID can NOT be empty...&rdquo; message. Unlike add/update there is no further
     *       field cascade &mdash; delete edits only the user id.</li>
     *   <li><strong>Read guard.</strong> The COBOL moved {@code USRIDINI} into {@code SEC-USR-ID} and
     *       performed {@code READ-USER-SEC-FILE} <em>then</em> {@code DELETE-USER-SEC-FILE}
     *       ({@code COUSR03C} L188-192). Here the record is first loaded with
     *       {@link UserSecurityRepository#findBySecUsrId(String)}; an empty {@link Optional} reproduces
     *       {@code DFHRESP(NOTFND)} and throws {@link RecordNotFoundException}
     *       (&ldquo;User ID NOT found...&rdquo;, {@code COUSR03C} L287-292/L323-328) with <strong>no</strong>
     *       delete performed &mdash; faithful to the observable &ldquo;not found, nothing removed&rdquo;
     *       outcome. Because the entity is already in hand, the COBOL
     *       {@code DELETE-USER-SEC-FILE} {@code WHEN DFHRESP(NOTFND)} branch is unreachable and collapses
     *       onto this same guard.</li>
     *   <li><strong>Delete.</strong> {@link UserSecurityRepository#delete(Object)} reproduces
     *       {@code EXEC CICS DELETE DATASET('USRSEC')} ({@code COUSR03C} L305-311). The surrounding
     *       {@link Transactional @Transactional} method makes the read and the delete one unit of work.
     *       The abnormal {@code WHEN OTHER} delete branch (&ldquo;Unable to Update User...&rdquo;,
     *       L329-335) is a known copy/paste artifact in the COBOL and is intentionally not surfaced; an
     *       unexpected {@code DataAccessException} would propagate to the centralized advice.</li>
     *   <li><strong>Echo back.</strong> On {@code DFHRESP(NORMAL)} the COBOL built
     *       &ldquo;User &lt;id&gt; has been deleted ...&rdquo; ({@code COUSR03C} L314-322); in REST the
     *       success outcome is the populated response DTO (id, first name, last name, type), with the
     *       password deliberately left unset &mdash; delete reads no credential.</li>
     * </ol>
     *
     * @param userId the 8-character {@code USRSEC} key to delete ({@code USRIDINI} on the delete screen);
     *               must be non-blank
     * @return a {@link UserSecurityDto} echoing the deleted user's id, first name, last name and user
     *         type (never the password); returned (rather than {@code void}) to keep the response shape
     *         consistent with the other admin services
     * @throws ValidationException     if {@code userId} is blank, carrying the verbatim
     *                                 &ldquo;User ID can NOT be empty...&rdquo; message (HTTP 400 via the
     *                                 advice layer)
     * @throws RecordNotFoundException if no user has that id, carrying the verbatim
     *                                 &ldquo;User ID NOT found...&rdquo; message (HTTP 404 via the advice
     *                                 layer); no row is deleted in this case
     */
    // Admin-only: COUSR03C ran only under the CDEMO-USRTYP-ADMIN context. The method-level ADMIN
    // gate is defence in depth behind the /api/admin/** route rule in SecurityConfig, and protects
    // the operation from internal (non-HTTP) misuse. Enforced via @EnableMethodSecurity.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserSecurityDto deleteUser(String userId) {
        // -------------------------------------------------------------------------------------------
        // Step 1 - DELETE-USER-INFO EVALUATE TRUE (COUSR03C L176-186): a single emptiness edit on the
        // user id (WHEN OTHER -> CONTINUE). A blank id throws a ValidationException carrying the verbatim
        // message via the single-String constructor (which surfaces the text exactly). There is NO
        // further field cascade and NO separate confirmation flag -- PF5 reads then deletes directly.
        // -------------------------------------------------------------------------------------------
        if (isBlank(userId)) {
            throw new ValidationException(MSG_USER_ID_EMPTY);
        }

        // -------------------------------------------------------------------------------------------
        // Step 2 - MOVE USRIDINI TO SEC-USR-ID + PERFORM READ-USER-SEC-FILE (COUSR03C L188-190). The
        // CICS READ ... UPDATE becomes a keyed findBySecUsrId; an empty Optional reproduces
        // DFHRESP(NOTFND) -> "User ID NOT found..." (COUSR03C L287-292) with NO delete performed. The
        // 8-byte key is trimmed to match the canonical stored primary key. The COBOL re-reads inside
        // DELETE-USER-SEC-FILE before deleting; this find-then-delete guard preserves that check, so the
        // DELETE-NOTFND branch (L323-328) is unreachable and maps to the same exception.
        // -------------------------------------------------------------------------------------------
        final String key = userId.trim();
        final Optional<UserSecurity> existing = userSecurityRepository.findBySecUsrId(key);
        final UserSecurity entity =
                existing.orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));

        // -------------------------------------------------------------------------------------------
        // Step 3 - PERFORM DELETE-USER-SEC-FILE -> EXEC CICS DELETE DATASET('USRSEC') (COUSR03C
        // L191/L305-311) -> JpaRepository.delete. delete(entity) is used (rather than deleteById) because
        // the managed entity is already loaded, faithfully reproducing the COBOL read-then-delete as one
        // @Transactional unit of work. The abnormal WHEN OTHER branch ("Unable to Update User...",
        // L329-335) is a known copy/paste artifact and is intentionally not surfaced (the read guard above
        // is authoritative); an unexpected DataAccessException would propagate to the centralized advice.
        // -------------------------------------------------------------------------------------------
        userSecurityRepository.delete(entity);

        // -------------------------------------------------------------------------------------------
        // Step 4 - success echo. DELETE-USER-SEC-FILE WHEN DFHRESP(NORMAL) built the STRING "User " +
        // SEC-USR-ID + " has been deleted ..." (COUSR03C L314-322); in REST the populated response DTO is
        // the success outcome (HTTP status/messaging is centralized in WebConfig's advice). The fields are
        // read from the just-deleted in-memory entity (its Java field values are retained after delete()).
        // The password is intentionally NOT set -- the delete flow never reads a credential.
        // -------------------------------------------------------------------------------------------
        final UserSecurityDto response = new UserSecurityDto();
        response.setUserId(entity.getSecUsrId());          // <- SEC-USR-ID (the trimmed, stored key)
        response.setFirstName(entity.getSecUsrFname());    // <- SEC-USR-FNAME
        response.setLastName(entity.getSecUsrLname());     // <- SEC-USR-LNAME
        response.setUserType(entity.getSecUsrType());      // <- SEC-USR-TYPE
        // response.password deliberately left null - delete reads/echoes no credential.
        return response;
    }

    /**
     * Reproduces the COBOL {@code SPACES OR LOW-VALUES} emptiness test for the user-id field.
     *
     * <p>A value is treated as &ldquo;empty&rdquo; when it is {@code null} (the {@code LOW-VALUES}
     * uninitialized case) or contains only whitespace after trimming (the all-{@code SPACES} case),
     * matching the way {@code COUSR03C}'s {@code EVALUATE TRUE} edit rejected a blank
     * {@code USRIDINI}.</p>
     *
     * @param value the field value to test (may be {@code null})
     * @return {@code true} if {@code value} is {@code null} or blank after trimming; {@code false}
     *         otherwise
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
