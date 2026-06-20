package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * Shared, immutable response payload for the user-administration operations.
 *
 * <p>Serialized to JSON and returned by {@code UserAdminController} for user add
 * ({@code POST}), user update ({@code PUT}), user delete ({@code DELETE}), and the
 * single-user display/confirmation view. It is produced by the user-admin service
 * layer ({@code UserAddService}, {@code UserUpdateService}, and
 * {@code UserDeleteService}), migrated from the COBOL online programs
 * {@code COUSR01C}, {@code COUSR02C}, and {@code COUSR03C} respectively. A single
 * response shape is intentionally shared across all four flows because the user
 * CRUD confirmations and the user-detail display project an identical set of
 * fields.</p>
 *
 * <p>It mirrors the display/confirmation fields of the {@code COUSR03} BMS symbolic
 * map (the canonical user delete/display screen) of the AWS CardDemo mainframe
 * application. The {@code message} component carries the operation-confirmation
 * text (for example, that a user was added, updated, or deleted) and has no direct
 * BMS field; {@code errorMessage} mirrors the BMS {@code ERRMSG} field. Screen
 * chrome (transaction name, titles, date, time, program name) and PF-key legend
 * fields from the map are intentionally excluded, as they have no place in a
 * stateless REST contract. Lineage is preserved by reference to the original COBOL
 * source commit {@code 27d6c6f}; no COBOL is copied here.</p>
 *
 * <p><strong>Security:</strong> this response never includes a password. The
 * plaintext {@code SEC-USR-PWD} field of the COBOL {@code USRSEC} record is
 * deliberately omitted so that credentials are never echoed back to clients
 * (the C-003 credential-hardening constraint).</p>
 *
 * <p>Field mapping (COBOL symbolic-map field &rarr; record component):</p>
 * <ul>
 *   <li>{@code USRIDIN} (PIC X(8)) &rarr; {@link #userId()}</li>
 *   <li>{@code FNAME} (PIC X(20)) &rarr; {@link #firstName()}</li>
 *   <li>{@code LNAME} (PIC X(20)) &rarr; {@link #lastName()}</li>
 *   <li>{@code USRTYPE} (PIC X(1)) &rarr; {@link #userType()}, represented as the
 *       {@link UserType} enum ({@code ADMIN}/{@code USER})</li>
 *   <li>(operation confirmation) &rarr; {@link #message()}</li>
 *   <li>{@code ERRMSG} (PIC X(78)) &rarr; {@link #errorMessage()}</li>
 * </ul>
 *
 * @param userId       the user identifier echoed on the screen (BMS {@code USRIDIN},
 *                     {@code PIC X(8)}); up to 8 characters
 * @param firstName    the user's first name (BMS {@code FNAME}, {@code PIC X(20)});
 *                     up to 20 characters
 * @param lastName     the user's last name (BMS {@code LNAME}, {@code PIC X(20)});
 *                     up to 20 characters
 * @param userType     the user authorization type (BMS {@code USRTYPE},
 *                     {@code PIC X(1)}) as a {@link UserType} ({@code ADMIN} or
 *                     {@code USER}); may be {@code null} when not applicable to the
 *                     operation result
 * @param message      the operation-confirmation message (for example, that the
 *                     user was added, updated, or deleted); {@code null} or empty
 *                     when there is no confirmation to convey
 * @param errorMessage the screen error or status message text (BMS {@code ERRMSG},
 *                     {@code PIC X(78)}); {@code null} or empty when there is no
 *                     error to display
 */
public record UserResponse(
        String userId,
        String firstName,
        String lastName,
        UserType userType,
        String message,
        String errorMessage) {
}
