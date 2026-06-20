package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;
import java.util.List;

/**
 * Response payload for the paginated user-list (admin browse) screen.
 *
 * <p>Serialized to JSON and returned by {@code controller.UserAdminController}
 * for {@code GET /api/admin/users}; produced by
 * {@code service.admin.UserListService}, which migrates the online CICS program
 * {@code COUSR00C} (a browse that shows <strong>10 user rows per page</strong>).
 * The structure mirrors the {@code COUSR00} BMS symbolic map, excluding terminal
 * screen chrome, 3270 attribute bytes, and PF-key legends.</p>
 *
 * <p>The user-id filter and the page number are supplied by the client as request
 * query parameters (there is no separate request DTO); both are <em>echoed</em>
 * back here so the caller can re-render the current filter/paging state without
 * any server-side conversational state. The exact 10-row page size is preserved
 * through {@link #users} (the controller/service layer enforces the page-size
 * limit).</p>
 *
 * <p>Lineage: AWS CardDemo COBOL source, commit {@code 27d6c6f}
 * ({@code app/cpy-bms/COUSR00.CPY}). Reference only - no COBOL is copied into
 * this project.</p>
 *
 * <p>This is a plain, immutable, JSON-serializable {@code record}: it carries no
 * JPA, persistence, or bean-validation concerns and is decoupled from the JPA
 * entity layer. No password or other credential field is ever exposed here.</p>
 *
 * @param pageNumber   the current page number, echoed from the request
 *                     (COUSR00 {@code PAGENUM}, {@code PIC X(8)}); {@code null}
 *                     when not set
 * @param userIdFilter the user-id filter echoed from the request
 *                     (COUSR00 {@code USRIDIN}, {@code PIC X(8)}); {@code null}
 *                     when no filter was supplied
 * @param users        the displayed user rows for this page (up to 10; COUSR00
 *                     row group); never contains screen-chrome fields
 * @param errorMessage a human-readable error/status message for the screen
 *                     (COUSR00 {@code ERRMSG}, {@code PIC X(78)}); {@code null}
 *                     or blank when the page rendered without error
 */
public record UserListResponse(
        String pageNumber,
        String userIdFilter,
        List<UserListItem> users,
        String errorMessage) {

    /**
     * A single displayed user row within a {@link UserListResponse}.
     *
     * <p>The {@code COUSR00} map repeats this field group ten times (one per row
     * on the browse screen); the rows are represented here as elements of
     * {@link UserListResponse#users} rather than flattened into positional
     * fields.</p>
     *
     * @param selectionFlag the single-character row selection indicator
     *                      (COUSR00 {@code SEL}, {@code PIC X(1)}); used by the
     *                      client to mark a row
     * @param userId        the 8-character user identifier
     *                      (COUSR00 {@code USRID}, {@code PIC X(8)})
     * @param firstName     the user's first name, up to 20 characters
     *                      (COUSR00 {@code FNAME}, {@code PIC X(20)})
     * @param lastName      the user's last name, up to 20 characters
     *                      (COUSR00 {@code LNAME}, {@code PIC X(20)})
     * @param userType      the user's authorization type
     *                      (COUSR00 {@code UTYPE}, {@code PIC X(1)}); the typed
     *                      {@link UserType} enum ({@code ADMIN} = {@code "A"},
     *                      {@code USER} = {@code "U"})
     */
    public static record UserListItem(
            String selectionFlag,
            String userId,
            String firstName,
            String lastName,
            UserType userType) {
    }
}
