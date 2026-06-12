package com.cardemo.service.admin;

import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.repository.UserSecurityRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-list service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COUSR00C.cbl}</strong> (CICS transaction {@code CU00}, BMS map
 * {@code COUSR0A} / mapset {@code COUSR00}, &ldquo;List Users&rdquo;). The program performs a
 * <strong>paginated forward/backward browse of the {@code USRSEC} VSAM&nbsp;KSDS, exactly ten rows per
 * page</strong>, keyed on the eight-character {@code SEC-USR-ID}, optionally positioned at a starting
 * user id, painting each page of rows onto the 3270 admin list screen.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces {@code COUSR00C}'s observable browse behavior <em>exactly</em> &mdash; the
 * same page size (ten), the same ascending {@code SEC-USR-ID} browse order and the same
 * &ldquo;start from the typed user id, otherwise start from the beginning&rdquo; positioning
 * semantics. Per the Minimal Change Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized
 * beyond the technology transition: no caching, no async, no extra sorting or filtering. The COBOL is
 * read-only reference material at the frozen baseline commit SHA {@code 27d6c6f} and is never copied
 * into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>The single most important parity constant &mdash; page size is EXACTLY ten</h2>
 * <p>The legacy program painted a fixed {@code 02 USER-REC OCCURS 10 TIMES} row array
 * ({@code COUSR00C} L57); the forward-read loop ({@code PROCESS-PAGE-FORWARD}, L293-306) and the
 * backward-read loop ({@code PROCESS-PAGE-BACKWARD}) each fill at most ten rows per page. That contract
 * is encoded once as {@link UserSecurityDto#ROWS_PER_PAGE} (ten) and supplied to the query through the
 * {@link Pageable} this service builds. No other page size is ever used.</p>
 *
 * <h2>Decimal precision is not applicable here (AAP &sect;0.7.3)</h2>
 * <p>The {@code USRSEC} record ({@code app/cpy/CSUSR01Y.cpy}) is entirely textual ({@code PIC X}): user
 * id, first name, last name, password and type. It contains <strong>no</strong> {@code COMP-3}/{@code
 * COMP} or {@code PIC ...V99} numeric field, so there is no {@code float}, {@code double} or
 * {@link java.math.BigDecimal} anywhere in this service.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM browse {@code STARTBR(GTEQ)}&nbsp;+&nbsp;{@code READNEXT}/{@code READPREV}/{@code
 *       ENDBR} cursor &rarr; Spring Data pagination.</strong> The {@code STARTBR-USER-SEC-FILE}
 *       paragraph issued {@code EXEC CICS STARTBR DATASET('USRSEC') RIDFLD(SEC-USR-ID)} (greater-than-
 *       or-equal positioning, {@code COUSR00C} L586-595) and {@code READNEXT-USER-SEC-FILE} then looped
 *       accumulating ten rows in ascending {@code SEC-USR-ID} order ({@code POPULATE-USER-DATA}). That
 *       becomes {@code PageRequest.of(pageNumber - 1, 10, Sort.by(ASC, "secUsrId"))} handed to
 *       {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqual(String, Pageable)}. The ascending
 *       {@code secUsrId} sort reproduces the VSAM key order exactly. The {@code READPREV} backward
 *       (PF7) path becomes a lower page index requested by the controller, not by this service.</li>
 *   <li><strong>The peek-one-extra {@code READNEXT} (set {@code NEXT-PAGE-YES}/{@code NEXT-PAGE-NO})
 *       &rarr; {@link Page#hasNext()}.</strong> After filling the tenth row the COBOL issued one more
 *       {@code READNEXT} purely to learn whether a next page exists ({@code COUSR00C} L308-323),
 *       driving PF8 enablement. The count-based Spring Data {@link Page#hasNext()} flag is the exact
 *       analog; next/previous-page (PF8/PF7) enablement is derived by the controller (which simply
 *       requests the next/previous page), so this service returns the rows and the page number without
 *       a dedicated &ldquo;has next&rdquo; field.</li>
 *   <li><strong>CICS map I/O ({@code SEND MAP}/{@code RECEIVE MAP}) &rarr; DTO.</strong> There is no
 *       3270 screen; this service populates a {@link UserSecurityDto} (with its nested
 *       {@link UserSecurityDto.UserListItem} rows) instead of the {@code COUSR0A} symbolic map.</li>
 *   <li><strong>Pseudo-conversational COMMAREA state ({@code CDEMO-CU00-INFO}) &rarr; stateless request
 *       parameters.</strong> The caller supplies the starting filter and the one-based page number on
 *       every call; no server-side conversation state (the COBOL {@code CDEMO-CU00-PAGE-NUM},
 *       {@code CDEMO-CU00-USRID-FIRST}/{@code -LAST} page anchors) is retained between calls.</li>
 * </ul>
 *
 * <h2>Browse-edge messages and row selection are controller concerns</h2>
 * <p>The legacy &ldquo;You are already at the top of the page...&rdquo; / &ldquo;...bottom of the
 * page...&rdquo; guards and the {@code STARTBR}/{@code READNEXT}/{@code READPREV} edge messages
 * (&ldquo;You are at the top of the page...&rdquo;, &ldquo;You have reached the bottom of the
 * page...&rdquo;) are 3270 screen niceties. In the stateless REST model they are conveyed by ordinary
 * pagination &mdash; a requested page&nbsp;&le;&nbsp;1 has no previous page, and {@code !page.hasNext()}
 * has no next page &mdash; so this <strong>read-only</strong> browse throws no domain exception for
 * these boundaries. Likewise, the COBOL {@code PROCESS-ENTER-KEY} row-selection logic, where typing
 * {@code 'U'}/{@code 'u'} {@code XCTL}s to the user-update program ({@code COUSR02C}) and
 * {@code 'D'}/{@code 'd'} to the user-delete program ({@code COUSR03C}) (any other non-space selection
 * raising &ldquo;Invalid selection. Valid values are U and D&rdquo;), is a <strong>controller</strong>
 * concern in REST: the client calls the update/delete endpoints directly. That routing is deliberately
 * <em>not</em> implemented here &mdash; {@code UserListService} only returns the paginated data.</p>
 *
 * <h2>Layering &amp; security</h2>
 * <p>This is a {@link Service @Service} that returns a DTO only. It builds no {@code ResponseEntity},
 * sets no HTTP status and renders no message (HTTP mapping is centralized in the
 * {@code config/WebConfig} {@code @RestControllerAdvice}). The browse list <strong>never</strong>
 * exposes the credential: the per-row mapping copies only the user id, first name, last name and type,
 * never {@link UserSecurity#getSecUsrPwd()} (the BCrypt hash). The program performs only browse
 * operations, so {@link #listUsers(String, int)} is annotated
 * {@link Transactional @Transactional(readOnly = true)}.</p>
 *
 * @see UserSecurityRepository
 * @see UserSecurity
 * @see UserSecurityDto
 * @see UserSecurityDto.UserListItem
 */
@Service
public class UserListService {

    // -----------------------------------------------------------------------------------------------
    // Browse contract constants.
    // -----------------------------------------------------------------------------------------------

    /**
     * The fixed browse page size &mdash; <strong>exactly ten rows</strong>.
     *
     * <p>Bound to {@link UserSecurityDto#ROWS_PER_PAGE} (the single source of truth for the
     * ten-rows-per-page contract) so the value cannot silently drift. It is the Java mapping of the
     * COBOL working-storage table {@code 02 USER-REC OCCURS 10 TIMES} ({@code COUSR00C} L57), which
     * capped the on-screen row array at ten. This page size is non-negotiable for parity.</p>
     */
    // USER-REC OCCURS 10 TIMES -> fixed page size of 10 rows [COUSR00C L57]
    private static final int PAGE_SIZE = UserSecurityDto.ROWS_PER_PAGE;

    /**
     * The {@link UserSecurity} entity property the browse is ordered by.
     *
     * <p>The COBOL {@code STARTBR ... } positioned the browse on the {@code USRSEC} cluster key, the
     * eight-character {@code SEC-USR-ID}, and {@code READNEXT} then walked the file in ascending key
     * order. The entity property {@code secUsrId} (column {@code user_id}) is that key, so a
     * {@code Sort} by {@code secUsrId} ascending reproduces the {@code STARTBR(GTEQ)}+{@code READNEXT}
     * traversal order exactly.</p>
     */
    // STARTBR ... GTEQ on the 8-char SEC-USR-ID cluster key -> Sort by UserSecurity.secUsrId ASC [COUSR00C L586-595, L619-629]
    private static final String SORT_PROPERTY = "secUsrId";

    /**
     * Empty start key &mdash; the relational equivalent of the COBOL {@code LOW-VALUES} positioning
     * that starts the browse at the first record.
     *
     * <p>{@code COUSR00C} L218-221: {@code IF USRIDINI = SPACES OR LOW-VALUES MOVE LOW-VALUES TO
     * SEC-USR-ID}. Because every persisted {@code user_id} compares greater-than-or-equal to the empty
     * string under the ascending sort, a {@code ""} start key reproduces &ldquo;start from the
     * beginning&rdquo; exactly.</p>
     */
    // USRIDINI = SPACES OR LOW-VALUES -> start from beginning; "" sorts first under ASC [COUSR00C L218-221]
    private static final String BROWSE_FROM_BEGINNING = "";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborator. Constructor injection with a private-final field (no field @Autowired).
    // -----------------------------------------------------------------------------------------------

    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its required collaborator.
     *
     * @param userSecurityRepository repository for the {@code USRSEC} VSAM replacement; supplies the
     *                               greater-than-or-equal paginated browse
     *                               ({@link UserSecurityRepository#findBySecUsrIdGreaterThanEqual(String, Pageable)})
     *                               that reproduces the {@code COUSR00C}
     *                               {@code STARTBR}/{@code READNEXT} loop
     */
    public UserListService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Returns one page of the user-list browse, reproducing {@code COUSR00C} end to end.
     *
     * <p>Processing order mirrors the COBOL dispatch:</p>
     * <ol>
     *   <li><strong>Derive the start key</strong> from the optional filter
     *       ({@code COUSR00C} L218-221): a null/blank/whitespace filter positions the browse at the
     *       first record (empty start key = {@code LOW-VALUES}); otherwise the trimmed filter is the
     *       greater-than-or-equal start key.</li>
     *   <li><strong>Run the paginated browse</strong> ({@code STARTBR(GTEQ)} + {@code READNEXT} loop),
     *       capped at {@link #PAGE_SIZE} (ten) rows in ascending {@code SEC-USR-ID} order.</li>
     *   <li><strong>Map the page rows</strong> to the response {@link UserSecurityDto} (with its nested
     *       {@link UserSecurityDto.UserListItem} rows) and stamp the one-based page number.</li>
     * </ol>
     *
     * <p>Forward/backward paging (PF8/PF7) is a controller concern: the controller requests
     * {@code pageNumber + 1} / {@code pageNumber - 1}; this method simply returns the requested page. An
     * empty page is returned as a {@link UserSecurityDto} with an empty {@code users} list (no
     * exception): the COBOL treats an exhausted browse as an informational edge state, never an error,
     * so this read-only browse throws nothing under normal operation.</p>
     *
     * @param userIdFilter the optional starting user-id filter ({@code COUSR00C USRIDIN}); when blank
     *                     (null, empty or whitespace) the browse starts at the first record, otherwise
     *                     it starts at the first user id greater than or equal to the trimmed value
     * @param pageNumber   the <strong>one-based</strong> page index to return
     *                     ({@code COUSR00C CDEMO-CU00-PAGE-NUM}, first page = 1); any value below one is
     *                     normalized to one
     * @return the populated {@link UserSecurityDto} whose {@code users} list holds up to
     *         {@link #PAGE_SIZE} rows for the requested page (possibly empty, never {@code null}), with
     *         the one-based {@code pageNumber} and the echoed {@code userIdFilter} set
     */
    @Transactional(readOnly = true)
    public UserSecurityDto listUsers(String userIdFilter, int pageNumber) {
        // COMMAREA -> stateless params: the start filter and page number arrive on every call; no
        // server-side conversation state (CDEMO-CU00-INFO) is retained [COUSR00C L218-221, L327].

        // USRIDINI -> GTEQ start key. Blank/whitespace => "" (LOW-VALUES, start from the beginning);
        // otherwise the trimmed user id positions STARTBR (GTEQ) [COUSR00C L218-221].
        String startKey = resolveStartKey(userIdFilter);

        // Page numbering convention: pageNumber is 1-based (COBOL CDEMO-CU00-PAGE-NUM, first page = 1).
        // Normalize < 1 to 1, then convert to Spring Data's 0-based page index.
        int normalizedPageNumber = Math.max(pageNumber, 1);
        int zeroBasedPage = normalizedPageNumber - 1;

        // VSAM STARTBR(GTEQ)+READNEXT loop, 10 rows (USER-REC OCCURS 10 TIMES)
        // -> PageRequest.of(page-1, 10, Sort by secUsrId ASC) [COUSR00C L57, L293-306, L586-629].
        // The peek-one-extra READNEXT that set NEXT-PAGE-YES/NO is Page.hasNext() (derived by the
        // controller for PF8 enablement), so it is not carried on the DTO [COUSR00C L308-323].
        Pageable pageable = PageRequest.of(zeroBasedPage, PAGE_SIZE, Sort.by(Sort.Direction.ASC, SORT_PROPERTY));
        Page<UserSecurity> page = userSecurityRepository.findBySecUsrIdGreaterThanEqual(startKey, pageable);

        // SEND MAP -> DTO: map the (<= 10) browsed rows onto the response and stamp the 1-based page.
        return buildDto(page.getContent(), normalizedPageNumber, userIdFilter);
    }

    /**
     * Resolves the greater-than-or-equal browse start key from the optional filter, reproducing
     * {@code COUSR00C} L218-221.
     *
     * <p>The COBOL set the {@code STARTBR} {@code RIDFLD} to {@code LOW-VALUES} (start at the first
     * record) when {@code USRIDINI} was {@code SPACES} or {@code LOW-VALUES}, and to the typed user id
     * otherwise. The relational analog is an empty start key (which sorts first under the ascending
     * {@code secUsrId} order) versus the trimmed filter value.</p>
     *
     * @param userIdFilter the raw filter input (may be {@code null})
     * @return {@link #BROWSE_FROM_BEGINNING} ({@code ""}) when the filter is null/blank/whitespace,
     *         otherwise the trimmed filter
     */
    private static String resolveStartKey(String userIdFilter) {
        if (userIdFilter == null) {
            return BROWSE_FROM_BEGINNING;
        }
        String trimmed = userIdFilter.trim();
        // SPACES / LOW-VALUES (blank or whitespace-only) -> start from the beginning [COUSR00C L218-219].
        if (trimmed.isEmpty()) {
            return BROWSE_FROM_BEGINNING;
        }
        // USRIDINI present -> it is the GTEQ start key [COUSR00C L221].
        return trimmed;
    }

    /**
     * Builds the response {@link UserSecurityDto} from the page rows, reproducing the row mapping the
     * COBOL {@code POPULATE-USER-DATA} loop performed when populating the {@code USER-REC OCCURS 10
     * TIMES} screen array.
     *
     * @param rows                 the browsed rows (at most {@link #PAGE_SIZE}), in ascending-key browse
     *                             order
     * @param normalizedPageNumber the one-based page number to stamp on the DTO
     * @param userIdFilter         the caller's original start filter, echoed back on the response
     * @return the populated {@link UserSecurityDto}
     */
    private UserSecurityDto buildDto(List<UserSecurity> rows, int normalizedPageNumber, String userIdFilter) {
        UserSecurityDto dto = new UserSecurityDto();

        List<UserSecurityDto.UserListItem> items = new ArrayList<>();
        for (UserSecurity user : rows) {
            items.add(toItem(user));
        }
        dto.setUsers(items);

        // PAGENUM is a fixed-width PIC X(8) field -> the DTO page number is a String; set the 1-based
        // value as text (matches CDEMO-CU00-PAGE-NUM displayed on the screen) [COUSR00C L327].
        dto.setPageNumber(String.valueOf(normalizedPageNumber));

        // USRIDIN echoed back so the stateless caller knows the active start filter (the legacy screen
        // carried this field); a null filter is echoed as null.
        dto.setUserIdFilter(userIdFilter);

        return dto;
    }

    /**
     * Maps one browsed {@link UserSecurity} record to a {@link UserSecurityDto.UserListItem},
     * reproducing the COBOL row {@code MOVE}s in {@code POPULATE-USER-DATA}.
     *
     * <ul>
     *   <li>{@code SEL000n PIC X(1)} (the per-row select field) is an input field, blank on display, so
     *       the selection flag is set to the empty string.</li>
     *   <li>{@code SEC-USR-ID PIC X(8)} maps straight across to the row user id ({@code USRID0n}).</li>
     *   <li>{@code SEC-USR-FNAME PIC X(20)} maps straight across to the row first name
     *       ({@code FNAME0n}) &mdash; the <strong>user</strong> name width {@code X(20)}, distinct from
     *       the customer name width {@code X(25)} used elsewhere.</li>
     *   <li>{@code SEC-USR-LNAME PIC X(20)} maps straight across to the row last name
     *       ({@code LNAME0n}).</li>
     *   <li>{@code SEC-USR-TYPE PIC X(01)} ({@code 'A'}/{@code 'U'}) maps to the row user type
     *       ({@code UTYPE0n}) as the type-safe {@code UserType} enum.</li>
     * </ul>
     *
     * <p>The credential ({@code SEC-USR-PWD}) is <strong>never</strong> copied onto a browse row: the
     * list output exposes no password/hash.</p>
     *
     * @param user the browsed user record (never {@code null})
     * @return the populated browse row
     */
    private UserSecurityDto.UserListItem toItem(UserSecurity user) {
        UserSecurityDto.UserListItem item = new UserSecurityDto.UserListItem();

        // SEL000n PIC X(1) is a blank input field on display [COUSR00C INITIALIZE-USER-DATA].
        item.setSelectionFlag("");

        // SEC-USR-ID PIC X(8) -> USRID0n PIC X(8).
        item.setUserId(user.getSecUsrId());

        // SEC-USR-FNAME PIC X(20) -> FNAME0n PIC X(20) (user name X(20), NOT customer X(25)).
        item.setFirstName(user.getSecUsrFname());

        // SEC-USR-LNAME PIC X(20) -> LNAME0n PIC X(20) (user name X(20), NOT customer X(25)).
        item.setLastName(user.getSecUsrLname());

        // SEC-USR-TYPE PIC X(01) ('A'/'U') -> UTYPE0n -> type-safe UserType enum.
        item.setUserType(user.getSecUsrType());

        // SEC-USR-PWD is intentionally NOT mapped: the browse list never exposes the credential.
        return item;
    }
}
