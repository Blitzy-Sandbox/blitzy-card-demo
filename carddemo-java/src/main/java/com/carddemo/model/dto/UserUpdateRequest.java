package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Immutable request payload for the administrative user-update operation.
 *
 * <p>Accepted as the JSON body by {@code com.carddemo.controller.UserAdminController}
 * on {@code PUT /api/admin/users/{userId}} and consumed by
 * {@code com.carddemo.service.admin.UserUpdateService} — the Java re-platforming of
 * the COBOL/CICS online program {@code COUSR02C}, which lets an administrator amend an
 * existing security record. This record mirrors only the <strong>input/editable</strong>
 * fields of the {@code COUSR02} BMS symbolic map ({@code COUSR2AI}).</p>
 *
 * <p>Per the migration contract, the following elements of the original map are
 * intentionally excluded from this request: the error message line ({@code ERRMSG},
 * a response-only DISPLAY field), all screen chrome ({@code TRNNAME}, {@code TITLE01},
 * {@code CURDATE}, {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}), and every BMS
 * attribute/length/PF-key field. None of these belong to the client-supplied contract.</p>
 *
 * <p>Each component is a {@link String} that preserves the original fixed-width BMS field
 * length (enforced with {@link Size}), except {@code userType}, which is the strongly typed
 * sibling enum {@link UserType} so that the single-character {@code PIC X(1)} code is
 * constrained to its valid domain ({@code "A"} = administrator, {@code "U"} = standard user).
 * Field-edit semantics carried by the COBOL screen handler (the {@code CSSETATY} attribute
 * helper, which flags blank/invalid fields) are expressed here as Jakarta Bean Validation
 * constraints, enforced by the controller via {@code @Valid}.</p>
 *
 * <p>The {@code password} component is deliberately <em>optional</em> on update: it carries
 * no {@link NotBlank} constraint because a blank or absent value signals "leave the existing
 * password unchanged". When a value is supplied it is plaintext input that the service layer
 * hashes with BCrypt before persistence (constraint C-003); it is never echoed back in any
 * response.</p>
 *
 * <p>Lineage is preserved by reference to the source repository commit {@code 27d6c6f}; the
 * original COBOL is not copied into this project. Primary symbolic map:
 * {@code app/cpy-bms/COUSR02.CPY}; field-edit semantics: {@code app/cpy/CSSETATY.cpy}.</p>
 *
 * @param userId    the user identifier being updated
 *                  ({@code COUSR02} input field {@code USRIDIN}, {@code PIC X(8)});
 *                  required, maximum 8 characters
 * @param firstName the user's first name
 *                  ({@code COUSR02} input field {@code FNAME}, {@code PIC X(20)});
 *                  required, maximum 20 characters
 * @param lastName  the user's last name
 *                  ({@code COUSR02} input field {@code LNAME}, {@code PIC X(20)});
 *                  required, maximum 20 characters
 * @param password  the new sign-on password
 *                  ({@code COUSR02} input field {@code PASSWD}, {@code PIC X(8)});
 *                  optional (blank or absent keeps the current password), maximum 8
 *                  characters, BCrypt-hashed by the service and never returned
 * @param userType  the authorization type
 *                  ({@code COUSR02} input field {@code USRTYPE}, {@code PIC X(1)});
 *                  {@link UserType#ADMIN} or {@link UserType#USER}
 */
public record UserUpdateRequest(

        @NotBlank
        @Size(max = 8)
        String userId,

        @NotBlank
        @Size(max = 20)
        String firstName,

        @NotBlank
        @Size(max = 20)
        String lastName,

        @Size(max = 8)
        String password,

        UserType userType) {
}
