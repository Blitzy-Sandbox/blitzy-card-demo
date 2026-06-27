package com.carddemo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request and response data transfer objects for the user-administration flows
 * (CICS CU00-CU03; programs COUSR00C, COUSR01C, COUSR02C, COUSR03C).
 *
 * <p>Field widths are derived byte-accurately from the BMS symbolic maps
 * {@code COUSR00}, {@code COUSR01}, {@code COUSR02} and {@code COUSR03} at
 * source commit SHA {@code 27d6c6f}. Consumed by {@code UserController}
 * ({@code /api/admin/users} CRUD) and {@code UserService}.
 */
public final class UserDto {

    private UserDto() {
    }

    /**
     * A single user-list row, mapped from a COUSR00 repeated row
     * ({@code USRIDnn X(8)}, {@code FNAMEnn X(20)}, {@code LNAMEnn X(20)},
     * {@code UTYPEnn X(1)}) at SHA {@code 27d6c6f}.
     */
    public record UserSummary(
            @Size(max = 8) String userId,
            @Size(max = 20) String firstName,
            @Size(max = 20) String lastName,
            @Size(max = 1) String userType
    ) {}

    /**
     * The user-list response, mapped from COUSR00 ({@code PAGENUM X(8)},
     * {@code USRIDIN X(8)} filter, and up to ten repeated rows) at SHA
     * {@code 27d6c6f}.
     */
    public record ListResponse(
            @Size(max = 8) String pageNumber,
            @Size(max = 8) String userIdFilter,
            List<@Valid UserSummary> users
    ) {}

    /**
     * The user-add request, mapped from COUSR01 ({@code FNAME X(20)},
     * {@code LNAME X(20)}, {@code USERID X(8)}, {@code PASSWD X(8)},
     * {@code USRTYPE X(1)}) at SHA {@code 27d6c6f}. The {@code password} is
     * plaintext on the wire only and is stored BCrypt-hashed by the service.
     *
     * <p>Blank/empty fields are intentionally <em>not</em> rejected here with
     * {@code @NotBlank}. The COUSR01C PROCESS-ENTER-KEY logic emits specific
     * byte-exact on-screen literals in a defined field order ("First Name can
     * NOT be empty...", "Last Name can NOT be empty...", "User ID can NOT be
     * empty...", "Password can NOT be empty...", "User Type can NOT be
     * empty..."); a {@code @NotBlank} at this boundary would shadow them with
     * the generic Bean Validation default ("must not be blank"). Presence
     * validation is therefore delegated to {@code UserAddService} to preserve
     * 100% behavioral parity (AAP &sect;0.7.1.1) and to keep the ADD path
     * consistent with the UPDATE path (which is already {@code @Size}-only);
     * see DECISION_LOG D-056. The {@code @Size} upper bounds mirror the
     * fixed-width COBOL pictures and remain here.
     *
     * <p>For the same reason, {@code userType} carries only {@code @Size(max = 1)}
     * here and deliberately <em>no</em> {@code @Pattern(regexp = "[AU]")}: a
     * pattern at this boundary would reject the empty string before
     * {@code UserAddService} can emit the byte-exact "User Type can NOT be
     * empty..." literal, shadowing it with the generic Bean Validation default.
     * The canonical {@code A} (admin) / {@code U} (user) role domain &mdash;
     * defined by the {@code COCOM01Y} condition names
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} and
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} &mdash; is therefore enforced at the
     * service level (after the presence check) by {@code UserAddService}; see
     * DECISION_LOG D-072.
     */
    public record CreateRequest(
            @Size(max = 20) String firstName,
            @Size(max = 20) String lastName,
            @Size(max = 8) String userId,
            @Size(max = 8) String password,
            @Size(max = 1) String userType
    ) {}

    /**
     * The user-update request, mapped from COUSR02 ({@code USRIDIN X(8)} target,
     * {@code FNAME X(20)}, {@code LNAME X(20)}, {@code PASSWD X(8)},
     * {@code USRTYPE X(1)}) at SHA {@code 27d6c6f}. The {@code password} is
     * optional and supplied only when it is being changed.
     *
     * <p>As on {@code CreateRequest}, {@code userType} carries only
     * {@code @Size(max = 1)} and deliberately no {@code @Pattern(regexp = "[AU]")}
     * so the byte-exact "User Type can NOT be empty..." presence literal is not
     * shadowed. The canonical {@code A}/{@code U} role domain (the
     * {@code COCOM01Y} {@code 88 CDEMO-USRTYP-ADMIN}/{@code CDEMO-USRTYP-USER}
     * condition names) is enforced at the service level by
     * {@code UserUpdateService} after the presence check; see DECISION_LOG D-072.
     */
    public record UpdateRequest(
            @NotBlank @Size(max = 8) String userId,
            @Size(max = 20) String firstName,
            @Size(max = 20) String lastName,
            @Size(max = 8) String password,
            @Size(max = 1) String userType
    ) {}

    /**
     * The user-delete confirmation, mapped from COUSR03 ({@code USRIDIN X(8)},
     * {@code FNAME X(20)}, {@code LNAME X(20)}, {@code USRTYPE X(1)}) at SHA
     * {@code 27d6c6f}. It carries no password field.
     */
    public record DeleteResponse(
            @Size(max = 8) String userId,
            @Size(max = 20) String firstName,
            @Size(max = 20) String lastName,
            @Size(max = 1) String userType
    ) {}
}
