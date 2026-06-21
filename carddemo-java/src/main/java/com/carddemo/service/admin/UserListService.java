package com.carddemo.service.admin;

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
 * the Repository pattern ("browse &rarr; pagination") this maps to pure page-number
 * pagination via the inherited {@link UserSecurityRepository#findAll(Pageable)},
 * ordered ascending by the user-id key to preserve VSAM key order.</p>
 *
 * <p>This service is consumed by {@code controller.UserAdminController}
 * ({@code GET /api/admin/users}) and is stateless: no conversational COMMAREA state is
 * retained between calls. The COBOL row-selection routing ('U' &rarr; update,
 * 'D' &rarr; delete) is a controller/menu concern and is intentionally not implemented
 * here. The user-id filter is echoed back unchanged; the repository exposes only
 * page-number pagination ({@code findAll(Pageable)}) and no key-positioned range query,
 * so it is not used to filter the result set. The persisted password is never read,
 * mapped, or echoed.</p>
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

    /** Status message shown when a backward page is requested from the first page ({@code COUSR00C} PROCESS-PF7-KEY). */
    private static final String MESSAGE_ALREADY_TOP_OF_PAGE = "You are already at the top of the page...";

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
     * <p>The requested 1-based page is converted to a zero-based page index (clamped at
     * zero), the corresponding page of {@link UserSecurity} rows is fetched, and each row
     * is projected onto a {@link UserListResponse.UserListItem} with a blank selection
     * flag (the selection flag is an output column, set by the client when marking a row).
     * The navigation state that the COBOL program surfaced on its message line is conveyed
     * through {@link UserListResponse#errorMessage()}:</p>
     * <ul>
     *   <li>an empty dataset yields {@code "You are at the top of the page..."};</li>
     *   <li>a forward request past the last page yields
     *       {@code "You have reached the bottom of the page..."};</li>
     *   <li>a backward request below the first page yields
     *       {@code "You are already at the top of the page..."};</li>
     *   <li>a populated page yields {@code null} (no message).</li>
     * </ul>
     *
     * @param pageNumber   the 1-based page number requested by the caller (COBOL page
     *                     numbers start at one)
     * @param userIdFilter the user-id filter from the request, echoed back unchanged on
     *                     the response; may be {@code null}
     * @return a {@link UserListResponse} carrying the echoed page number and filter, the
     *         (up to ten) user rows for the page, and a boundary/navigation message or
     *         {@code null}
     */
    public UserListResponse listUsers(int pageNumber, String userIdFilter) {
        // Users paginate 1-based (page 1 == first page), so translate to the zero-based index, then
        // clamp so the resulting SQL offset (pageIndex * PAGE_SIZE) cannot exceed Integer.MAX_VALUE.
        // clampPageToMaxOffset also floors negatives at 0, so an absurd page number now yields a
        // graceful empty page (HTTP 200) instead of an offset-overflow InvalidDataAccessApiUsageException
        // surfacing as HTTP 500.
        int pageIndex = PaginationSupport.clampPageToMaxOffset(pageNumber - 1, PAGE_SIZE);
        Pageable pageable = PageRequest.of(pageIndex, PAGE_SIZE, Sort.by(SORT_PROPERTY).ascending());
        Page<UserSecurity> page = userSecurityRepository.findAll(pageable);

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
        } else if (pageNumber < 1) {
            errorMessage = MESSAGE_ALREADY_TOP_OF_PAGE;
        }

        return new UserListResponse(String.valueOf(pageNumber), userIdFilter, users, errorMessage);
    }
}
