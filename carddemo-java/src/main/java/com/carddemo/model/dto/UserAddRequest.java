package com.carddemo.model.dto;

import com.carddemo.model.enums.UserType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/admin/users}.
 *
 * <p>Bound by {@code com.carddemo.controller.UserAdminController} and consumed by
 * {@code com.carddemo.service.admin.UserAddService} — the Java re-platforming of the
 * COBOL/CICS online program {@code COUSR01C}, which adds a security ({@code USRSEC})
 * record. This record mirrors the <strong>input</strong> fields of the {@code COUSR01}
 * BMS symbolic map ({@code COUSR1AI}): {@code FNAMEI}, {@code LNAMEI}, {@code USERIDI},
 * {@code PASSWDI} and {@code USRTYPEI}. Screen chrome (title/date/time/program-name),
 * the error-message field ({@code ERRMSG}, which is response-only), and the PF-key
 * legend are intentionally excluded because they are presentation- or response-only
 * concerns.</p>
 *
 * <p>Each {@link String} component preserves the original fixed-width BMS field length,
 * enforced with {@link Size}, and rejects blank input with {@link NotBlank}, mirroring
 * the COBOL field-edit semantics captured in {@code CSSETATY}. The {@code userType}
 * component is the strongly typed {@link UserType} enum ({@link UserType#ADMIN} /
 * {@link UserType#USER}); the enum intrinsically constrains the single-character
 * {@code USRTYPE} code, so it carries no {@link Size} constraint.</p>
 *
 * <p>The {@code password} component is the <strong>plaintext</strong> credential entered
 * on the add-user screen. It is retained on this inbound contract solely so that
 * {@code UserAddService} can BCrypt-hash it before persistence (constraint C-003); it is
 * never stored in plaintext and is never echoed back in any response DTO.</p>
 *
 * <p>Lineage (reference only — the COBOL source is not copied): original repository
 * commit {@code 27d6c6f}; primary symbolic map {@code app/cpy-bms/COUSR01.CPY};
 * field-edit semantics {@code app/cpy/CSSETATY.cpy}.</p>
 *
 * @param firstName user's first name (BMS {@code FNAME}, {@code PIC X(20)}); required, maximum 20 characters.
 * @param lastName  user's last name (BMS {@code LNAME}, {@code PIC X(20)}); required, maximum 20 characters.
 * @param userId    sign-on user id to create (BMS {@code USERID}, {@code PIC X(8)}); required, maximum 8 characters.
 * @param password  plaintext sign-on password (BMS {@code PASSWD}, {@code PIC X(8)}); required, maximum 8 characters; BCrypt-hashed by the service and never echoed.
 * @param userType  authorization type (BMS {@code USRTYPE}, {@code PIC X(1)}); {@link UserType#ADMIN} or {@link UserType#USER}.
 */
public record UserAddRequest(

        @NotBlank
        @Size(max = 20)
        String firstName,

        @NotBlank
        @Size(max = 20)
        String lastName,

        @NotBlank
        @Size(max = 8)
        String userId,

        @NotBlank
        @Size(max = 8)
        String password,

        UserType userType) {
}
