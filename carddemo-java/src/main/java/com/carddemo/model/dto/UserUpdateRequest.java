package com.carddemo.model.dto;

import com.carddemo.model.dto.constraint.NoHtml;
import com.carddemo.model.enums.UserType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the administrative user-update screen
 * ({@code PUT /api/admin/users/{userId}}).
 *
 * <p>This DTO is the JSON body accepted by {@code UserAdminController} and
 * consumed by {@code UserUpdateService}, the Java re-platforming of the CICS
 * online program {@code COUSR02C}. It mirrors the <strong>input / editable</strong>
 * fields of the {@code COUSR02} BMS symbolic map (copybook
 * {@code app/cpy-bms/COUSR02.CPY}, input group {@code COUSR2AI}; source commit
 * {@code 27d6c6f}). The screen-chrome fields ({@code TRNNAME}, {@code TITLE01},
 * {@code CURDATE}, {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}), the
 * response-only {@code ERRMSG} field, and the PF-key legend are intentionally
 * excluded from this contract.</p>
 *
 * <p><strong>Field lengths.</strong> Every {@link String} component carries a
 * {@link Size} whose {@code max} equals the byte length of the corresponding
 * BMS {@code PIC X(n)} field exactly, preserving the fixed-width record
 * contract. The user identifier and the customer first and last names are
 * mandatory ({@link NotBlank}), mirroring the keyed, must-be-present semantics
 * of the original map.</p>
 *
 * <p><strong>Password semantics.</strong> On update the {@code password}
 * component is <em>mandatory</em> ({@link NotBlank}), mirroring the original
 * program's {@code UPDATE-USER-INFO} edit which rejects a blank {@code PASSWDI}
 * with the message {@code "Password can NOT be empty..."}. The supplied value is
 * plaintext input ({@code PASSWD PIC X(8)}) that {@code UserUpdateService}
 * BCrypt-hashes before persistence (security constraint C-003), re-encoding the
 * stored hash only when the supplied credential actually differs from it; it is
 * never echoed back in any response DTO, and instances of this record must never
 * be logged because they may convey a plaintext credential in transit.</p>
 *
 * <p><strong>User type.</strong> The {@code userType} component maps to the
 * sibling {@link UserType} enumeration ({@code ADMIN} = {@code "A"} /
 * {@code USER} = {@code "U"}), whose closed set of constants already constrains
 * the value to the valid one-byte {@code USRTYPE PIC X(1)} codes; consequently
 * no {@link Size} constraint is required on this component.</p>
 *
 * <p>The field-edit and attribute semantics of {@code app/cpy/CSSETATY.cpy}
 * (which flags fields in error and marks blanks on the 3270 screen) are
 * expressed here as the Jakarta Bean Validation constraints applied to each
 * component; the constraints are enforced on the canonical constructor when the
 * controller annotates the argument with {@code @Valid}, before the service
 * layer is invoked.</p>
 *
 * <p>This is a plain, immutable, serializable data carrier (a {@code record})
 * with no persistence concerns: it intentionally references no JPA or entity
 * types and exposes only the editable update contract together with an accessor
 * for each component.</p>
 *
 * @param userId    the target user identifier ({@code USRIDIN PIC X(8)} of
 *                  {@code COUSR02}); required and at most 8 characters
 * @param firstName the customer first name ({@code FNAME PIC X(20)} of
 *                  {@code COUSR02}); required and at most 20 characters
 * @param lastName  the customer last name ({@code LNAME PIC X(20)} of
 *                  {@code COUSR02}); required and at most 20 characters
 * @param password  the new plaintext password ({@code PASSWD PIC X(8)} of
 *                  {@code COUSR02}); required and at most 8 characters; the
 *                  supplied value is BCrypt-hashed by the service before
 *                  persistence and re-encoded only when it differs from the
 *                  stored hash
 * @param userType  the authorization type ({@code USRTYPE PIC X(1)} of
 *                  {@code COUSR02}) as the {@link UserType} enum
 *                  ({@code ADMIN} / {@code USER})
 */
public record UserUpdateRequest(
        @NotBlank @Size(max = 8) String userId,
        @NotBlank @NoHtml @Size(max = 20) String firstName,
        @NotBlank @NoHtml @Size(max = 20) String lastName,
        @NotBlank @Size(max = 8) String password,
        UserType userType) {
}
