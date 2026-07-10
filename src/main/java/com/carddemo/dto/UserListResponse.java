package com.carddemo.dto;

import jakarta.validation.constraints.Size;

/**
 * Immutable, paginated response for the CardDemo <strong>List Users</strong>
 * feature.
 *
 * <p>This DTO is the JSON contract returned by the users-listing endpoint of
 * {@code UserController} (legacy online transaction {@code CU00}, BMS map
 * {@code COUSR00}, program {@code COUSR00C}). It wraps a single page of
 * {@link UserListItem} rows together with the optional user-id search filter that
 * the caller supplied, replacing the pseudo-conversational 3270 screen with a
 * stateless REST payload.</p>
 *
 * <p><strong>Page size is 10 rows per page</strong>, mirroring the ten repeated
 * display rows of the legacy {@code COUSR00} map (symbolic fields
 * {@code USRID01I}..{@code USRID10I}). The current-page indicator, which maps from
 * the BMS {@code PAGENUM} field, together with the PF7/PF8 backward/forward scroll
 * semantics, is carried inside the {@link PageResponse} envelope rather than on
 * this wrapper.</p>
 *
 * <p>The {@code userIdFilter} echoes the {@code USRIDIN} ({@code PIC X(8)}) search
 * field back to the caller so a client can render the currently active filter; it
 * is {@code null}/absent when the full, unfiltered list is requested.</p>
 *
 * <p><strong>Security:</strong> {@link UserListItem} deliberately never exposes the
 * {@code SEC-USR-PWD} field (or any password hash); no credential material is
 * serialized anywhere in this response.</p>
 *
 * <p>Being a stateless, immutable Java {@code record}, instances are inherently
 * thread-safe, retain no server-side conversational ({@code COMMAREA} / session)
 * state, and are serialized to and from JSON natively by Jackson. The rationale for
 * these design choices is recorded in {@code docs/decision-log.md} rather than in
 * verbose inline comments.</p>
 *
 * @param userIdFilter the echoed 8-character user-id search filter
 *                     ({@code COUSR00.USRIDIN}); may be {@code null} when no filter
 *                     was applied
 * @param page         the current page of {@link UserListItem} rows (page size 10);
 *                     never {@code null}
 */
public record UserListResponse(

        @Size(max = 8) String userIdFilter,

        PageResponse<UserListItem> page) {
}
