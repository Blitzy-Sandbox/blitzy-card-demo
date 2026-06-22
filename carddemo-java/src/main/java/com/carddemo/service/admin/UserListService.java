package com.carddemo.service.admin;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserListResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.shared.PaginationSupport;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Paginated user-list (admin browse) service. Java translation of the online CICS
 * program {@code COUSR00C} (CardDemo source commit {@code 27d6c6f}; COBOL not copied
 * into this project), whose function is to list the USRSEC security records
 * <strong>ten rows per page</strong>.
 *
 * <p>The COBOL program performs a CICS browse over the USRSEC KSDS: {@code STARTBR}
 * positions on the user-id key, a {@code READNEXT} loop fills the
 * {@code USER-REC OCCURS 10 TIMES} table, a peek-ahead {@code READNEXT} sets the
 * more-pages indicator, and {@code ENDBR} closes the browse; {@code PF8} pages forward
 * and {@code PF7} pages backward while the page number is tracked in the COMMAREA. Per
 * the Repository pattern ("browse &rarr; pagination") this maps to page-number
 * pagination via {@link UserSecurityRepository}, ordered ascending by the user-id key
 * to preserve VSAM key order.</p>
 *
 * <p>The optional user-id filter reproduces the program's {@code STARTBR} positioning:
 * it is a <strong>"start-at" lower bound</strong> applied before paging via
 * {@link UserSecurityRepository#findBySecUsrIdGreaterThanEqual(String, Pageable)}, so the
 * browse begins at the supplied user id (or the next existing id) and reads forward; when
 * no filter is supplied the full key range is browsed via
 * {@link UserSecurityRepository#findAll(Pageable)}. The 8-character user-id key is
 * compared with raw lexicographic order (no numeric normalization, no case folding),
 * preserving the COBOL key order.</p>
 *
 * <p>This service is consumed by {@code controller.UserAdminController}
 * ({@code GET /api/admin/users}) and is stateless: no conversational COMMAREA state is
 * retained between calls. The COBOL row-selection routing ('U' &rarr; update,
 * 'D' &rarr; delete) is a controller/menu concern and is intentionally not implemented
 * here. The user-id filter is echoed back unchanged on the response. The persisted
 * password is never read, mapped, or echoed.</p>
 */
@Service
public class UserListService {

    /** Rows displayed per page; mirrors the COBOL {@code USER-REC OCCURS 10 TIMES} table. */
    private static final int PAGE_SIZE = 10;

    /** JPA property name of the USRSEC key, used to browse in ascending VSAM key order. */
    private static final String SORT_PROPERTY = "secUsrId";

    /** Status message shown when the USRSEC dataset holds no users ({@code COUSR00C} STARTBR NOTFND). */
    private static final String MESSAGE_TOP_OF_PAGE = "You are at the top of the page...";

    /** Status message shown when a forward page lands past the last record ({@code COUSR00C} READNEXT ENDFILE). */
    private static final String MESSAGE_BOTTOM_OF_PAGE = "You have reached the bottom of the page...";

    /** Validation message when the 1-based page number is below one (Issue 5: invalid page bound). */
    private static final String MSG_PAGE_INVALID = "Page number must be one or greater";

    /** Repository over the re-platformed USRSEC KSDS, browsed one page at a time. */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its injected repository collaborator.
     *
     * @param userSecurityRepository the USRSEC repository used for the paginated browse
     */
    public UserListService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Returns a single page of users for the admin browse, reproducing the
     * {@code COUSR00C} browse-and-paginate behavior (ten rows per page, ascending
     * user-id key order).
     *
     * <p>The requested 1-based page (which must be at least one) is converted to a
     * zero-based page index, an optional "start-at" user-id filter is applied, the
     * corresponding page of {@link UserSecurity} rows is fetched, and each row is projected
     * onto a {@link UserListResponse.UserListItem} with a blank selection flag (the
     * selection flag is an output column, set by the client when marking a row). The
     * navigation state that the COBOL program surfaced on its message line is conveyed
     * through {@link UserListResponse#errorMessage()}:</p>
     * <ul>
     *   <li>an empty result (no users, or none at/after the start-at filter) yields
     *       {@code "You are at the top of the page..."} (the COBOL {@code STARTBR} NOTFND
     *       message);</li>
     *   <li>a forward request past the last page yields
     *       {@code "You have reached the bottom of the page..."};</li>
     *   <li>a populated page yields {@code null} (no message).</li>
     * </ul>
     *
     * @param pageNumber   the 1-based page number requested by the caller (COBOL page
     *                     numbers start at one); a value below one is rejected with a
     *                     {@link ValidationException} (HTTP 400)
     * @param userIdFilter the optional "start-at" user-id filter; when present and non-blank
     *                     the browse begins at the first user id {@code >=} this value and
     *                     reads forward. Echoed back unchanged on the response; may be
     *                     {@code null}
     * @return a {@link UserListResponse} carrying the echoed page number and filter, the
     *         (up to ten) user rows for the page, and a boundary/navigation message or
     *         {@code null}
     * @throws ValidationException if {@code pageNumber} is less than one
     */
    public UserListResponse listUsers(int pageNumber, String userIdFilter) {
        // Issue 5 (invalid pagination bounds): users paginate 1-based (page 1 == first page), so any
        // page below one is not a valid page. The COBOL browse tracked a 1-based page counter in the
        // COMMAREA and could not express page <= 0; the REST "page" parameter makes one expressible,
        // so reject it explicitly (HTTP 400) instead of silently coercing it to the first page. A
        // huge but valid page still yields a graceful empty page (HTTP 200) via the offset clamp.
        if (pageNumber < 1) {
            throw new ValidationException(MSG_PAGE_INVALID);
        }

        // Translate the 1-based page to a zero-based index, then clamp so the resulting SQL offset
        // (pageIndex * PAGE_SIZE) cannot exceed Integer.MAX_VALUE, yielding a graceful empty page
        // (HTTP 200) instead of an offset-overflow InvalidDataAccessApiUsageException (HTTP 500).
        int pageIndex = PaginationSupport.clampPageToMaxOffset(pageNumber - 1, PAGE_SIZE);
        Pageable pageable = PageRequest.of(pageIndex, PAGE_SIZE, Sort.by(SORT_PROPERTY).ascending());

        // Issue 4 (filters not applied): COUSR00C's STARTBR-USER-SEC-FILE positions the browse on the
        // entered user id with GTEQ and reads forward, so the user-id filter is a "start-at" lower
        // bound applied BEFORE paging (the earlier implementation echoed the filter but queried the
        // whole table). User ids are 8-character alphanumeric keys, so the raw lexicographic >=
        // comparison preserves the VSAM key order (no numeric normalization, no case folding).
        boolean filterActive = userIdFilter != null && !userIdFilter.isBlank();
        Page<UserSecurity> page = filterActive
                ? userSecurityRepository.findBySecUsrIdGreaterThanEqual(userIdFilter, pageable)
                : userSecurityRepository.findAll(pageable);

        List<UserListResponse.UserListItem> users = page.getContent().stream()
                .map(user -> new UserListResponse.UserListItem(
                        null,
                        user.getSecUsrId(),
                        user.getSecUsrFname(),
                        user.getSecUsrLname(),
                        user.getSecUsrType()))
                .toList();

        String errorMessage = null;
        if (page.getTotalElements() == 0L) {
            errorMessage = MESSAGE_TOP_OF_PAGE;
        } else if (users.isEmpty() && pageNumber > 1) {
            errorMessage = MESSAGE_BOTTOM_OF_PAGE;
        }

        return new UserListResponse(String.valueOf(pageNumber), userIdFilter, users, errorMessage);
    }
}
