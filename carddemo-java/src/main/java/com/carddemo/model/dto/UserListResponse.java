package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;
import java.util.List;

/**
 * Immutable response payload for the paginated user-list (admin browse) screen.
 *
 * <p>This DTO is serialized to JSON and returned by {@code UserAdminController} for
 * {@code GET /api/admin/users}, and is produced by {@code UserListService}. It mirrors the
 * {@code COUSR00} BMS symbolic map of the originating CICS program {@code COUSR00C}, which
 * renders a single browse page of up to <strong>ten</strong> user rows. Behavioral lineage is
 * preserved by reference to source commit {@code 27d6c6f}; no COBOL is copied.</p>
 *
 * <p>The user-id filter and the page number are supplied by the client as query parameters
 * (there is no dedicated request DTO), and are <em>echoed</em> back here so the caller can
 * correlate the response with its request. The fixed page size of ten rows is enforced by the
 * controller/service layer and is represented structurally by {@link #users()} rather than by
 * flattened, individually numbered fields. Screen chrome (titles, program name, date/time) and
 * PF-key legends from the BMS map are intentionally omitted, and no password field is ever
 * exposed.</p>
 *
 * @param pageNumber   the current page number, echoed from the request
 *                     (&larr; {@code COUSR00.PAGENUM}, PIC X(8))
 * @param userIdFilter the user-id filter value, echoed from the request
 *                     (&larr; {@code COUSR00.USRIDIN}, PIC X(8))
 * @param users        the displayed rows for this page, up to ten items
 *                     (&larr; the {@code COUSR00} row group, repeated ten times)
 * @param errorMessage the screen-level message, or {@code null}/blank when none applies
 *                     (&larr; {@code COUSR00.ERRMSG}, PIC X(78))
 */
public record UserListResponse(
        String pageNumber,
        String userIdFilter,
        List<UserListItem> users,
        String errorMessage) {

    /**
     * A single displayed user row within a {@link UserListResponse}.
     *
     * <p>The {@code COUSR00} map repeats this field group ten times per page; the rows are
     * represented collectively via {@link UserListResponse#users()} rather than being flattened
     * into individually numbered fields.</p>
     *
     * <p>The {@code userType} component is modeled with the typed
     * {@link com.carddemo.model.enums.UserType} enum (code {@code "A"} for administrators,
     * {@code "U"} for standard users), consistent with the established typed user-type convention
     * across the DTO layer. All other components are {@link String} values mirroring their
     * fixed-width display fields.</p>
     *
     * @param selectionFlag the row selection indicator
     *                      (&larr; {@code COUSR00.SEL}, PIC X(1))
     * @param userId        the eight-character user identifier
     *                      (&larr; {@code COUSR00.USRID}, PIC X(8))
     * @param firstName     the user's first name
     *                      (&larr; {@code COUSR00.FNAME}, PIC X(20))
     * @param lastName      the user's last name
     *                      (&larr; {@code COUSR00.LNAME}, PIC X(20))
     * @param userType      the user authorization type
     *                      (&larr; {@code COUSR00.UTYPE}, PIC X(1))
     */
    public static record UserListItem(
            String selectionFlag,
            String userId,
            String firstName,
            String lastName,
            UserType userType) {
    }
}
