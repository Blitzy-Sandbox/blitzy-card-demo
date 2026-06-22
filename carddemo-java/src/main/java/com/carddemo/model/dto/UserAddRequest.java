package com.carddemo.model.dto;

import com.carddemo.model.dto.constraint.NoHtml;
import com.carddemo.model.enums.UserType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/admin/users} (user add &mdash; admin function).
 *
 * <p>Immutable DTO mirroring the <em>input/editable</em> fields of the {@code COUSR01}
 * BMS symbolic map ({@code app/cpy-bms/COUSR01.CPY}); consumed by
 * {@code com.carddemo.service.admin.UserAddService} (migrated from COBOL program
 * {@code COUSR01C}, source commit {@code 27d6c6f}). Field-edit semantics from
 * {@code app/cpy/CSSETATY.cpy} are expressed here as Jakarta Bean Validation
 * constraints.</p>
 *
 * <p>Screen chrome, the response-only {@code ERRMSG} field, and PF-key/attribute
 * fields of the original map are intentionally excluded from this contract. Every
 * {@code String} component is length-bounded to match its originating BMS field
 * width, while {@link #userType()} relies on the {@link UserType} enum to
 * intrinsically constrain the single-character user-type value.</p>
 *
 * <p>The {@link #password()} component carries the <strong>plaintext</strong>
 * credential input only: the user-add service BCrypt-hashes it before persistence
 * (constraint C-003), so it is never stored in clear text nor echoed in any
 * response DTO. Instances of this record must never be logged, as they convey a
 * plaintext credential in transit.</p>
 *
 * @param firstName the user's first name; required and at most 20 characters
 *                  (mirrors {@code FNAME PIC X(20)})
 * @param lastName  the user's last name; required and at most 20 characters
 *                  (mirrors {@code LNAME PIC X(20)})
 * @param userId    the user identifier; required and at most 8 characters
 *                  (mirrors {@code USERID PIC X(8)})
 * @param password  the plaintext password input; required and at most 8 characters
 *                  (mirrors {@code PASSWD PIC X(8)}); BCrypt-hashed by the service
 *                  before persistence and never returned in a response
 * @param userType  the authorization type ({@link UserType#ADMIN} or
 *                  {@link UserType#USER}); the enum constrains the valid value,
 *                  replacing the original single-character {@code USRTYPE PIC X(1)}
 *                  length edit
 */
public record UserAddRequest(
        @NotBlank @NoHtml @Size(max = 20) String firstName,
        @NotBlank @NoHtml @Size(max = 20) String lastName,
        @NotBlank @Size(max = 8) String userId,
        @NotBlank @Size(max = 8) String password,
        UserType userType) {
}
