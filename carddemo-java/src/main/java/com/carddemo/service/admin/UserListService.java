package com.carddemo.service.admin;

import com.carddemo.model.dto.UserListResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Paginated user-list (admin browse) business logic for the USRSEC store.
 *
 * <p>Java equivalent of the online CICS program {@code COUSR00C} ("List all users from
 * USRSEC file"), source commit {@code 27d6c6f}; no COBOL is copied. The original program
 * performed a CICS browse &mdash; {@code STARTBR} on USRSEC keyed by {@code SEC-USR-ID},
 * a {@code READNEXT} loop populating a {@code USER-REC OCCURS 10 TIMES} group (ten rows per
 * page), a peek-ahead {@code READNEXT} to detect whether a further page exists, then
 * {@code ENDBR}; the {@code PF8}/{@code PF7} keys paged forward and backward while
 * {@code CDEMO-CU00-PAGE-NUM} tracked the one-based page number.</p>
 *
 * <p>Per the Repository pattern adopted for this migration (VSAM browse &rarr; pagination),
 * the browse is expressed as page-number pagination over
 * {@link UserSecurityRepository#findAll(Pageable)} sorted ascending by the entity's key
 * field {@code secUsrId}, which reproduces the VSAM key order of the {@code SEC-USR-ID}
 * primary key. Exactly ten rows are returned per page, mirroring the COBOL row group.</p>
 *
 * <p>Boundary state was conveyed in the COBOL program through the on-screen message line
 * rather than through dedicated indicators; that behavior is preserved here by populating
 * {@link UserListResponse#errorMessage()} with the corresponding message at the top, bottom,
 * and already-at-top boundaries, and leaving it {@code null} for a normally populated page.</p>
 *
 * <p>This service is read-only and never reads, maps, or echoes the user password. Row
 * selection routing (the {@code 'U'}&rarr;update and {@code 'D'}&rarr;delete actions of the
 * source program) is a controller/menu concern and is intentionally absent here. This service
 * is consumed by the user-administration controller for {@code GET /api/admin/users}.</p>
 */
@Service
public class UserListService {

    /**
     * Fixed number of rows returned per page. Mirrors the COBOL
     * {@code USER-REC OCCURS 10 TIMES} row group of {@code COUSR00C}.
     */
    private static final int PAGE_SIZE = 10;

    /** Message shown when the USRSEC store holds no users at all (top-of-page boundary). */
    private static final String MSG_TOP_OF_PAGE = "You are at the top of the page...";

    /** Message shown when a forward page beyond the last populated page is requested. */
    private static final String MSG_BOTTOM_OF_PAGE = "You have reached the bottom of the page...";

    /** Message shown when a backward move below the first page is requested. */
    private static final String MSG_ALREADY_AT_TOP = "You are already at the top of the page...";

    /** Repository providing keyed and paginated access to the re-platformed USRSEC store. */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its required repository dependency.
     *
     * @param userSecurityRepository the Spring Data JPA repository for {@link UserSecurity}
     */
    public UserListService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Returns a single browse page of users ordered by user id ascending.
     *
     * <p>The {@code pageNumber} is one-based to match the COBOL page numbering: page 1 is the
     * first page. Values below one are clamped to the first page for the query and additionally
     * flagged as an already-at-top boundary. The {@code userIdFilter} is echoed back unchanged;
     * the re-platformed repository exposes no key-positioned or range query, so it is not used to
     * filter the result set.</p>
     *
     * @param pageNumber   the one-based page number requested by the caller
     * @param userIdFilter the user-id filter value supplied by the caller, echoed back unchanged;
     *                     may be {@code null}
     * @return a {@link UserListResponse} containing up to {@value #PAGE_SIZE} rows for the page,
     *         the echoed page number and filter, and a boundary message (or {@code null} when the
     *         page is normally populated)
     */
    public UserListResponse listUsers(int pageNumber, String userIdFilter) {
        int pageIndex = Math.max(0, pageNumber - 1);
        Pageable pageable = PageRequest.of(pageIndex, PAGE_SIZE, Sort.by("secUsrId").ascending());
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
            errorMessage = MSG_TOP_OF_PAGE;
        } else if (users.isEmpty() && pageNumber > 1) {
            errorMessage = MSG_BOTTOM_OF_PAGE;
        } else if (pageNumber < 1) {
            errorMessage = MSG_ALREADY_AT_TOP;
        }

        return new UserListResponse(String.valueOf(pageNumber), userIdFilter, users, errorMessage);
    }
}
