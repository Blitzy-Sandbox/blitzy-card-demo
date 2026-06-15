package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;

/**
 * Shared response payload for the user-administration operations.
 *
 * <p>Returned as JSON by {@code controller.UserAdminController} for user add
 * ({@code POST}), update ({@code PUT}), and delete ({@code DELETE}), as well as
 * the single-user display/confirmation flow. It is produced by
 * {@code service.admin.UserAddService}, {@code service.admin.UserUpdateService},
 * and {@code service.admin.UserDeleteService} (the modernized equivalents of the
 * COBOL online programs {@code COUSR01C}, {@code COUSR02C}, and
 * {@code COUSR03C}).</p>
 *
 * <p>The contract mirrors the display fields of the {@code COUSR03} BMS symbolic
 * map (the user delete/display screen), which is the canonical user-detail output
 * shape and also serves as the confirmation payload for the add and update
 * operations. Screen chrome, attribute bytes, length subfields, and PF-key
 * legends from the original map ({@code TRNNAME}, {@code TITLE01},
 * {@code CURDATE}, {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}) are
 * intentionally excluded from this contract.</p>
 *
 * <p>A password field is deliberately and permanently absent: credentials are
 * never echoed back to clients (the {@code SEC-USR-PWD} plaintext field from the
 * COBOL {@code USRSEC} record is hardened to BCrypt and never serialized into any
 * response, satisfying constraint C-003).</p>
 *
 * <p>Every field except {@code userType} is a {@code String}, faithfully
 * reflecting the source map's fixed-width alphanumeric ({@code PIC X}) fields;
 * {@code userType} is the sibling {@link UserType} enum, mirroring the
 * single-character {@code USRTYPE PIC X(1)} code ({@code 'A'} = admin,
 * {@code 'U'} = user). Lineage is preserved by reference to the source repository
 * commit {@code 27d6c6f}; the original COBOL is not copied into this project.</p>
 *
 * @param userId       user identifier ({@code COUSR03} field {@code USRIDIN}, {@code PIC X(8)})
 * @param firstName    user first name ({@code COUSR03} field {@code FNAME}, {@code PIC X(20)})
 * @param lastName     user last name ({@code COUSR03} field {@code LNAME}, {@code PIC X(20)})
 * @param userType     user authorization type ({@code COUSR03} field {@code USRTYPE}, {@code PIC X(1)}; {@link UserType})
 * @param message      operation confirmation text (e.g. user added/updated/deleted); may be {@code null} when the operation failed
 * @param errorMessage error/status message echoed to the caller ({@code COUSR03} field {@code ERRMSG}, {@code PIC X(78)}); may be {@code null} when the operation succeeded
 */
public record UserResponse(
        String userId,
        String firstName,
        String lastName,
        UserType userType,
        String message,
        String errorMessage) {
}
