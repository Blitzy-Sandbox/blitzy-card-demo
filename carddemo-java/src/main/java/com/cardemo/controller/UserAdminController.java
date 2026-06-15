package com.cardemo.controller;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST replacement for the four AWS CardDemo CICS BMS 3270 <strong>user-administration screens</strong>:
 * <strong>User List</strong>, <strong>User Add</strong>, <strong>User Update</strong> and
 * <strong>User Delete</strong>. It exposes the CRUD routes under
 * <strong>{@code /api/admin/users/*}</strong> and is a thin adapter over {@link UserListService},
 * {@link UserAddService}, {@link UserUpdateService} and {@link UserDeleteService}.
 *
 * <p>This controller is the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x realization of
 * AAP&nbsp;&sect;0.4.1 (tech-spec&nbsp;L652: <em>{@code controller/UserAdminController.java} CREATE &larr;
 * {@code app/bms/COUSR00.bms}&ndash;{@code COUSR03.bms} &mdash; "CRUD {@code /api/admin/users/*}"</em>)
 * and of AAP&nbsp;&sect;0.3.4 (BMS&nbsp;&rarr;&nbsp;REST contract translation). It preserves features
 * <strong>F-014</strong> (User List), <strong>F-015</strong> (User Add), <strong>F-016</strong>
 * (User Update) and <strong>F-017</strong> (User Delete) without expansion (Minimal Change Clause,
 * AAP&nbsp;&sect;0.7.1).</p>
 *
 * <h2>Authoritative source artifacts (read-only reference, never copied)</h2>
 * <ul>
 *   <li><strong>{@code app/bms/COUSR00.bms}</strong> &mdash; the User List mapset
 *       ({@code COUSR00} / map {@code COUSR0A}), driven by CICS program {@code COUSR00C},
 *       transaction <strong>{@code CU00}</strong>. The browse painted a fixed row array of ten user
 *       rows ({@code USRID01}&hellip;{@code USRID10}) &mdash; <strong>exactly ten rows per page</strong>
 *       &mdash; each with a one-character selection field ({@code SEL0001}&hellip;{@code SEL0010}) and
 *       an optional {@code USRIDIN} starting-id filter.</li>
 *   <li><strong>{@code app/bms/COUSR01.bms}</strong> &mdash; the User Add mapset
 *       ({@code COUSR01} / map {@code COUSR1A}), driven by CICS program {@code COUSR01C},
 *       transaction <strong>{@code CU01}</strong>. The five typed fields ({@code FNAME},
 *       {@code LNAME}, {@code USERID}, {@code PASSWD}, {@code USRTYPE}) were edited and, when all
 *       passed, written as a new 80-byte {@code USRSEC} record.</li>
 *   <li><strong>{@code app/bms/COUSR02.bms}</strong> &mdash; the User Update mapset
 *       ({@code COUSR02} / map {@code COUSR2A}), driven by CICS program {@code COUSR02C},
 *       transaction <strong>{@code CU02}</strong>. A load-then-confirm screen
 *       ({@code ENTER=Fetch}, {@code F5=Save}) that read the record for edit and, on PF5, rewrote it
 *       only when at least one field had changed.</li>
 *   <li><strong>{@code app/bms/COUSR03.bms}</strong> &mdash; the User Delete mapset
 *       ({@code COUSR03} / map {@code COUSR3A}), driven by CICS program {@code COUSR03C},
 *       transaction <strong>{@code CU03}</strong>. A load-then-confirm screen
 *       ({@code ENTER=Fetch}, {@code F5=Delete}) that read the record for confirmation and, on PF5,
 *       deleted it (the delete screen edits only the user id &mdash; no password field).</li>
 * </ul>
 * <p>Their symbolic maps ({@code app/cpy-bms/COUSR00.CPY}&ndash;{@code COUSR03.CPY}) were migrated into
 * {@link UserSecurityDto} (the {@code COPY CSSETATY} field contract) and its nested
 * {@code UserListItem} browse row. This controller never re-declares those structures and never copies
 * COBOL/BMS text &mdash; only the screen <em>behavior</em> is reproduced, by delegation to the four
 * user-administration services.</p>
 *
 * <h2>Thin-adapter contract (AAP &sect;0.3.3)</h2>
 * <p>This controller contains <strong>no business logic and no data access</strong>. The ten-rows/page
 * paginated browse, the ordered field-validation cascades with verbatim COBOL messages, the
 * duplicate-key pre-check, the BCrypt password encoding (constraint C-003), the per-field change
 * detection and the load-then-act flows all live in the services. Each handler is a pure delegation
 * that adds <strong>zero</strong> logic.</p>
 *
 * <h2>COBOL &rarr; REST substitutions (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>{@code SEND MAP} / {@code RECEIVE MAP} &rarr; request/response DTO.</strong> The 3270
 *       map paint and field harvest collapse into Jackson (de)serialization of {@link UserSecurityDto}
 *       (and, for the list, its nested {@code UserListItem} rows). Screen chrome, message lines and BMS
 *       control bytes have no REST analogue and are not modeled (AAP&nbsp;&sect;0.4.2).</li>
 *   <li><strong>{@code RETURN TRANSID(...) COMMAREA} &rarr; stateless REST.</strong> The CICS
 *       pseudo-conversational hand-off (the {@code COCOM01Y} COMMAREA carrying state across 3270 turns)
 *       is replaced by stateless HTTP; this controller holds no conversational state
 *       (AAP&nbsp;&sect;0.1.2).</li>
 *   <li><strong>PF07 (page-up) / PF08 (page-down) &rarr; {@code page&plusmn;1} query parameter.</strong>
 *       The {@code COUSR00C} backward/forward browse keys map to the client re-calling
 *       {@code GET /api/admin/users?page=N-1} / {@code ?page=N+1}; paging is stateless navigation, not
 *       server-held cursor state. The page size is fixed at <strong>ten rows</strong>
 *       ({@link UserSecurityDto#ROWS_PER_PAGE}) and {@code page} is <strong>one-based</strong> (the
 *       service clamps a value below one to one).</li>
 *   <li><strong>Row-selection {@code 'U'}/{@code 'D'} flags and the PF5 load-then-confirm flows &rarr;
 *       distinct client-driven REST calls.</strong> On the list screen the operator typed {@code 'U'}
 *       to update or {@code 'D'} to delete a selected row ({@code COUSR00C} {@code XCTL} to
 *       {@code COUSR02C}/{@code COUSR03C}); in REST the client simply chooses the
 *       {@code PUT}/{@code DELETE} route for that id. The {@code COUSR02C}/{@code COUSR03C}
 *       <em>load-then-confirm</em> choreography ({@code ENTER=Fetch} then {@code F5=Save}/{@code F5=Delete})
 *       decomposes into a {@code GET /api/admin/users/{id}} (load) followed by a
 *       {@code PUT /api/admin/users/{id}} / {@code DELETE /api/admin/users/{id}} (act) &mdash; all
 *       interpreted by the services, never here (AAP&nbsp;&sect;0.1.2).</li>
 * </ul>
 *
 * <h2>Validation parity &mdash; delegated to the services (AAP &sect;0.7.2)</h2>
 * <p>The services are the authoritative source for validation <em>order</em> and verbatim COBOL
 * message text ({@code UserAddService}'s and {@code UserUpdateService}'s ordered first-error-wins
 * cascades, the &ldquo;Please modify to update ...&rdquo; no-change message, and the blank-id edits in
 * particular). Request bodies are therefore bound as a plain {@code @RequestBody}
 * <strong>without</strong> {@code @Valid}: applying bean validation here would pre-empt those ordered
 * messages with a generic {@code MethodArgumentNotValidException} and break message parity (consistent
 * with the other CardDemo mutation controllers, e.g. {@code AccountController}/{@code TransactionController}).
 * The {@link UserSecurityDto} Jakarta constraints remain the documented field contract (the
 * {@code COPY CSSETATY} translation), but the service is the runtime source of truth. On update the
 * path {@code {id}} is authoritative for the resource id and is stamped onto the request before
 * delegation.</p>
 *
 * <h2>Error handling &mdash; centralized advice, exceptions propagate</h2>
 * <p>This controller defines <strong>no</strong> {@code @ExceptionHandler} /
 * {@code @RestControllerAdvice} and catches no domain exception. The services throw and this controller
 * lets propagate the typed exceptions translated by the centralized {@code @RestControllerAdvice} in
 * {@code config/WebConfig}:</p>
 * <ul>
 *   <li>{@code com.cardemo.exception.ValidationException} &rarr; HTTP&nbsp;<strong>400 Bad
 *       Request</strong> (the verbatim, ordered COBOL edit messages &mdash; e.g. blank field, or
 *       &ldquo;Please modify to update ...&rdquo; when an update changed nothing).</li>
 *   <li>{@code com.cardemo.exception.RecordNotFoundException} &rarr; HTTP&nbsp;<strong>404 Not
 *       Found</strong> (&ldquo;User ID NOT found...&rdquo; on a load/update/delete of an unknown id).</li>
 *   <li>{@code com.cardemo.exception.DuplicateRecordException} &rarr; HTTP&nbsp;<strong>409
 *       Conflict</strong> (&ldquo;User ID already exist...&rdquo; when an add collides with an existing
 *       user id).</li>
 * </ul>
 *
 * <h2>Security &mdash; admin-only, enforced centrally</h2>
 * <p>These four screens were reachable only from the CardDemo <strong>Admin Menu</strong>
 * ({@code COADM01C}, transaction {@code CA00}) under the COBOL {@code CDEMO-USRTYP-ADMIN} context.
 * In the migrated system the admin gate is enforced <strong>centrally</strong> by
 * {@code config/SecurityConfig}, whose route rule maps every {@code /api/admin/**} request to the
 * {@code ROLE_ADMIN} authority (a non-admin authenticated caller receives {@code 403 Forbidden}; an
 * unauthenticated caller receives {@code 401 Unauthorized}). Defence-in-depth is additionally applied
 * at the admin-service method level (each service method is {@code @PreAuthorize("hasRole('ADMIN')")}
 * under {@code @EnableMethodSecurity}). This thin adapter therefore introduces <strong>no</strong>
 * security infrastructure and <strong>no</strong> method-security annotations of its own.</p>
 *
 * <h2>Credential safety (constraint C-003, AAP &sect;0.7.2)</h2>
 * <p>The single permitted behavioral change of the migration &mdash; upgrading the stored {@code USRSEC}
 * password to a BCrypt hash &mdash; is performed entirely in {@link UserAddService} (encode on add) and
 * {@link UserUpdateService} (re-encode on change); this controller never hashes, never re-hashes and
 * never reads the credential. The inbound password travels only on the add/update request body and is
 * passed straight to the service; it is <strong>never logged or echoed</strong>. The
 * {@link UserSecurityDto#getPassword() password} field is moreover {@code @JsonProperty(access = WRITE_ONLY)},
 * so Jackson accepts it inbound but never serializes it into any response body &mdash; the controller
 * relies on that model-layer guarantee rather than scrubbing the field here.</p>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL/BMS baseline at commit SHA
 * {@code 27d6c6f}. The COBOL and BMS sources are read-only reference material and are never copied into
 * this repository (AAP&nbsp;&sect;0.7.2).</p>
 *
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see UserSecurityDto
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    /** Service owning the paginated user browse &mdash; ten rows/page, one-based ({@code COUSR00C}). */
    private final UserListService userListService;

    /**
     * Service owning the {@code @Transactional} user add ({@code COUSR01C}): the ordered first-error-wins
     * validation cascade, the duplicate-key pre-check and the BCrypt password encoding (constraint C-003).
     */
    private final UserAddService userAddService;

    /**
     * Service owning the user load-for-edit and {@code @Transactional} update ({@code COUSR02C}): the
     * ordered validation cascade, the per-field change detection (including the BCrypt
     * {@code matches}/{@code encode} password rule, C-003) and the &ldquo;no change&rdquo; rejection.
     * Its {@code loadUser} is the canonical single keyed read used by the {@code GET /{id}} route.
     */
    private final UserUpdateService userUpdateService;

    /**
     * Service owning the user load-for-confirm and {@code @Transactional} delete ({@code COUSR03C}): the
     * single blank-id edit and the read-guard-then-delete that returns a DTO echoing the deleted user.
     */
    private final UserDeleteService userDeleteService;

    /**
     * Constructs the controller with the four user-administration services injected by Spring
     * (constructor injection; all fields are {@code final}; no field {@code @Autowired}).
     *
     * @param userListService   the paginated user-list browse service ({@code COUSR00C})
     * @param userAddService    the transactional user-add service ({@code COUSR01C})
     * @param userUpdateService the user load-for-edit and transactional update service ({@code COUSR02C})
     * @param userDeleteService the user load-for-confirm and transactional delete service ({@code COUSR03C})
     */
    public UserAdminController(final UserListService userListService,
                               final UserAddService userAddService,
                               final UserUpdateService userUpdateService,
                               final UserDeleteService userDeleteService) {
        this.userListService = userListService;
        this.userAddService = userAddService;
        this.userUpdateService = userUpdateService;
        this.userDeleteService = userDeleteService;
    }

    /**
     * Returns one page of the user-list browse, reproducing {@code COUSR00C}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/admin/users}.</p>
     *
     * <p>The optional {@code userId} query parameter is the browse <em>start-key filter</em> the
     * operator typed into the {@code USRIDIN} field; when omitted (or blank) the browse starts at the
     * beginning of the {@code USRSEC} file. The {@code page} query parameter is the
     * <strong>one-based</strong> page index (default {@code 1}); the service caps each page at
     * <strong>ten rows</strong> ({@link UserSecurityDto#ROWS_PER_PAGE}), orders ascending by user id and
     * normalizes a value below one to one. The controller performs no page arithmetic and adds no logic;
     * it passes the two parameters straight to {@link UserListService#listUsers(String, int)}.</p>
     *
     * @param userId optional starting-id filter ({@code COUSR00C USRIDIN}); when blank the browse starts
     *               from the beginning
     * @param page   the one-based page index to return (default {@code 1}, ten rows/page)
     * @return {@code 200 OK} carrying a {@link UserSecurityDto} whose {@code users} list holds up to ten
     *         summary rows for the requested {@code pageNumber} (possibly empty when the browse yields no
     *         records); this read-only browse raises no domain exception
     */
    // COBOL substitution: COUSR00C SEND MAP('COUSR0A') painted a fixed USRID01..USRID10 row array (ten
    // rows, each with a SEL flag); its STARTBR(GTEQ)/READNEXT/READPREV browse + PF07 (page-up) / PF08
    // (page-down) keys map to a stateless GET with a one-based `page` query param at 10 rows/page -- the
    // client requests page-1 / page+1 (AAP §0.1.2). RECEIVE MAP('COUSR0A') field harvest (USRIDIN
    // start-key filter) -> the `userId` query param; RETURN TRANSID('CU00') COMMAREA -> stateless REST
    // (no conversational state). Pure delegation: UserListService owns the 10-rows/page cap, the
    // ascending user-id browse order and the start-key filter resolution. The list-row 'U'/'D' selection
    // flags are a client concern -- the client calls the PUT/DELETE route for the chosen id.
    @GetMapping
    public ResponseEntity<UserSecurityDto> listUsers(
            @RequestParam(required = false) final String userId,
            @RequestParam(defaultValue = "1") final int page) {
        return ResponseEntity.ok(userListService.listUsers(userId, page));
    }

    /**
     * Loads a single user for display / pre-edit / pre-delete, reproducing the {@code PROCESS-ENTER-KEY}
     * (read-for-edit/confirm) step of {@code COUSR02C}/{@code COUSR03C}.
     *
     * <p><strong>Endpoint:</strong> {@code GET /api/admin/users/{id}}.</p>
     *
     * <p>This is the canonical single keyed read of the {@code USRSEC} replacement &mdash; the
     * &ldquo;load&rdquo; half of the legacy load-then-confirm screens. The user id is the authoritative
     * read key (path variable); it is passed through unchanged to
     * {@link UserUpdateService#loadUser(String)}, which performs the blank-id edit (raising the verbatim
     * &ldquo;User ID can NOT be empty...&rdquo; message) and the keyed read in the exact COBOL order. The
     * response carries the id, first name, last name and user type; the password is never populated (it
     * is a one-way BCrypt hash and the DTO field is write-only). The controller performs no validation of
     * its own so a malformed id reaches the service and surfaces the verbatim message rather than a
     * generic framework error (parity, AAP&nbsp;&sect;0.7.2).</p>
     *
     * @param id the user id to read ({@code COUSR02C}/{@code COUSR03C} {@code USRIDIN}); the sole read key
     * @return {@code 200 OK} carrying the populated single-user {@link UserSecurityDto} (never the password)
     * @throws com.cardemo.exception.ValidationException     if the user id is blank (HTTP&nbsp;400);
     *         propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if no user exists for the id (HTTP&nbsp;404,
     *         &ldquo;User ID NOT found...&rdquo;); propagated, not caught
     */
    // COBOL substitution: the COUSR02C/COUSR03C load-then-confirm screens both began with ENTER=Fetch ->
    // EXEC CICS READ DATASET('USRSEC') RIDFLD(SEC-USR-ID). That read-for-edit/confirm step maps to a
    // single keyed GET; UserUpdateService.loadUser is the canonical single-user read (delete's load is
    // an equivalent read). RECEIVE MAP field harvest (USRIDIN) -> the {id} path binding; SEND MAP ->
    // UserSecurityDto JSON; RETURN TRANSID COMMAREA -> stateless GET. The PF5 "act" step is a separate
    // PUT/DELETE call (see updateUser/deleteUser below). The credential is never returned.
    @GetMapping("/{id}")
    public ResponseEntity<UserSecurityDto> getUser(@PathVariable("id") final String id) {
        return ResponseEntity.ok(userUpdateService.loadUser(id));
    }

    /**
     * Adds a new user, reproducing the server-side core of {@code COUSR01C}.
     *
     * <p><strong>Endpoint:</strong> {@code POST /api/admin/users}.</p>
     *
     * <p>The new-user fields (first name, last name, user id, password, user type) travel in the request
     * body. The body is bound as {@code @RequestBody} <strong>without</strong> {@code @Valid}: the
     * service owns the ordered, first-error-wins verbatim COBOL edit cascade (first name &rarr; last name
     * &rarr; user id &rarr; password &rarr; user type), so applying bean validation here would pre-empt
     * those messages and break message parity (AAP&nbsp;&sect;0.7.2). The duplicate-key pre-check and the
     * BCrypt password encoding (the single permitted behavioral change, constraint C-003) also live in
     * the service. On success a {@code 201 Created} carries the created user DTO &mdash; with the
     * password deliberately omitted (write-only; never echoed).</p>
     *
     * @param request the add-user payload ({@code COUSR1AI} symbolic map replacement); validated and
     *                persisted by the service (the raw password is never logged or echoed)
     * @return {@code 201 Created} carrying the created {@link UserSecurityDto} (never the password)
     * @throws com.cardemo.exception.ValidationException      if any required field is empty, carrying the
     *         verbatim COBOL message for the first blank field (HTTP&nbsp;400); propagated, not caught
     * @throws com.cardemo.exception.DuplicateRecordException if a user with the same id already exists
     *         (HTTP&nbsp;409, &ldquo;User ID already exist...&rdquo;); propagated, not caught
     */
    // COBOL substitution: COUSR01C PROCESS-ENTER-KEY (the EVALUATE TRUE field-edit cascade) +
    // WRITE-USER-SEC-FILE (EXEC CICS WRITE DATASET('USRSEC')) collapse into a single POST. RECEIVE
    // MAP('COUSR1A') field harvest -> @RequestBody binding (NO @Valid: the service owns the ordered
    // verbatim messages); SEND MAP('COUSR1A') success line -> the created DTO; ENTER=Add -> POST; RETURN
    // TRANSID('CU01') COMMAREA -> stateless REST. Pure delegation: UserAddService owns the validation
    // cascade, the duplicate pre-check (-> 409) and the BCrypt encode (C-003). 201 Created marks the new
    // resource. The raw password is never logged or echoed.
    @PostMapping
    public ResponseEntity<UserSecurityDto> addUser(@RequestBody final UserSecurityDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAddService.addUser(request));
    }

    /**
     * Updates an existing user, reproducing the {@code UPDATE-USER-INFO} (PF5) path of {@code COUSR02C}.
     *
     * <p><strong>Endpoint:</strong> {@code PUT /api/admin/users/{id}}.</p>
     *
     * <p>The load-then-confirm choreography ({@code ENTER=Fetch} then {@code F5=Save}) collapses into a
     * {@code GET /{id}} (load, see {@link #getUser(String)}) followed by this {@code PUT}. The user id is
     * the authoritative key (path variable) and is stamped onto the request body before delegation, so
     * the path identifies the resource regardless of any id in the payload. The body is bound as
     * {@code @RequestBody} <strong>without</strong> {@code @Valid}: the service owns the ordered
     * verbatim COBOL edit cascade (user id &rarr; first name &rarr; last name &rarr; password &rarr; user
     * type), the per-field change detection (including the BCrypt {@code matches}/{@code encode} password
     * rule, C-003) and the &ldquo;Please modify to update ...&rdquo; no-change rejection, so bean
     * validation here would pre-empt those messages (AAP&nbsp;&sect;0.7.2). On success a {@code 200 OK}
     * carries the updated DTO &mdash; with the password omitted (write-only; never echoed).</p>
     *
     * @param id      the user id to update ({@code COUSR02C USRIDIN}); the authoritative key, stamped
     *                onto the request
     * @param request the update-user payload ({@code COUSR2AI} symbolic map replacement); validated and
     *                conditionally applied by the service (the raw password is never logged or echoed)
     * @return {@code 200 OK} carrying the updated {@link UserSecurityDto} (never the password)
     * @throws com.cardemo.exception.ValidationException     if any required field is empty (carrying the
     *         verbatim message for the first blank field) or if no field changed (carrying the verbatim
     *         &ldquo;Please modify to update ...&rdquo; message); HTTP&nbsp;400, propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if no user exists for the id (HTTP&nbsp;404,
     *         &ldquo;User ID NOT found...&rdquo;); propagated, not caught
     */
    // COBOL substitution: COUSR02C was a load-then-confirm screen -- ENTER=Fetch (GET /{id}) then F5=Save
    // (this PUT) -> UPDATE-USER-INFO + UPDATE-USER-SEC-FILE (EXEC CICS REWRITE). RECEIVE MAP('COUSR2A')
    // field harvest -> @RequestBody binding (NO @Valid: the service owns the ordered verbatim messages,
    // the per-field change detection and the "Please modify to update ..." no-change rule); SEND
    // MAP('COUSR2A') success line -> the updated DTO; RETURN TRANSID('CU02') COMMAREA -> stateless PUT.
    // The path {id} is authoritative for the resource id (the COBOL keyed the rewrite by USRIDIN), so it
    // is stamped onto the request before delegation. The raw password is never logged or echoed.
    @PutMapping("/{id}")
    public ResponseEntity<UserSecurityDto> updateUser(@PathVariable("id") final String id,
                                                      @RequestBody final UserSecurityDto request) {
        // CWE-20 null-body guard: a JSON `null` body would NPE on setUserId below (HTTP 500). Synthesize
        // an empty DTO so the authoritative path {id} is still stamped and the request flows into the
        // service's ordered COBOL edits; with the user id present, the (absent) first name is rejected
        // with the verbatim first-error (HTTP 400) -- exactly as an empty COUSR2A map would behave.
        final UserSecurityDto target = (request != null) ? request : new UserSecurityDto();
        // Path identifies the resource: stamp the authoritative {id} onto the request before delegating
        // (the COBOL REWRITE was keyed by USRIDIN). UserSecurityDto exposes the user id via setUserId.
        target.setUserId(id);
        return ResponseEntity.ok(userUpdateService.updateUser(target));
    }

    /**
     * Deletes an existing user, reproducing the {@code DELETE-USER-INFO} (PF5) path of {@code COUSR03C}.
     *
     * <p><strong>Endpoint:</strong> {@code DELETE /api/admin/users/{id}}.</p>
     *
     * <p>The load-then-confirm choreography ({@code ENTER=Fetch} then {@code F5=Delete}) collapses into a
     * {@code GET /{id}} (load, see {@link #getUser(String)}) followed by this {@code DELETE}. The user id
     * is the authoritative key (path variable); it is passed through unchanged to
     * {@link UserDeleteService#deleteUser(String)}, which performs the single blank-id edit and the
     * read-guard-then-delete (a missing id is rejected with the not-found message and no row is removed).
     * The delete screen carries no password and the service touches no credential. The response is
     * {@code 200 OK} <strong>with a body</strong> (not {@code 204 No Content}) because the service returns
     * a DTO echoing the deleted user's id, first name, last name and type &mdash; the REST analogue of the
     * COBOL &ldquo;User &lt;id&gt; has been deleted ...&rdquo; confirmation.</p>
     *
     * @param id the user id to delete ({@code COUSR03C USRIDIN}); the authoritative key
     * @return {@code 200 OK} carrying a {@link UserSecurityDto} echoing the deleted user (never a password)
     * @throws com.cardemo.exception.ValidationException     if the user id is blank (HTTP&nbsp;400);
     *         propagated, not caught
     * @throws com.cardemo.exception.RecordNotFoundException if no user exists for the id (HTTP&nbsp;404,
     *         &ldquo;User ID NOT found...&rdquo;); propagated, not caught &mdash; no row is deleted
     */
    // COBOL substitution: COUSR03C was a load-then-confirm screen -- ENTER=Fetch (GET /{id}) then
    // F5=Delete (this DELETE) -> DELETE-USER-INFO + DELETE-USER-SEC-FILE (EXEC CICS DELETE
    // DATASET('USRSEC')). RECEIVE MAP('COUSR3A') field harvest (USRIDIN only -- no password field) ->
    // the {id} path binding; SEND MAP('COUSR3A') success line -> the echoed DTO; RETURN TRANSID('CU03')
    // COMMAREA -> stateless DELETE. Pure delegation: UserDeleteService owns the blank-id edit and the
    // read-guard-then-delete. 200 + body (not 204) is deliberate: the service returns a confirmation DTO
    // echoing the deleted user (delete reads/echoes no credential).
    @DeleteMapping("/{id}")
    public ResponseEntity<UserSecurityDto> deleteUser(@PathVariable("id") final String id) {
        return ResponseEntity.ok(userDeleteService.deleteUser(id));
    }
}
