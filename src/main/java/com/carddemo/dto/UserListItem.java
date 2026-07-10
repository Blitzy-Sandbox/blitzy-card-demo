package com.carddemo.dto;

import jakarta.validation.constraints.Size;

/**
 * Immutable projection of a single <em>user list</em> row.
 *
 * <p>This DTO represents one repeated display row of the legacy CardDemo
 * <strong>List Users</strong> screen (BMS map {@code COUSR00}, online transaction
 * {@code CU00}, program {@code COUSR00C}). Each such row is backed by the
 * {@code SEC-USER-DATA} record defined in copybook {@code CSUSR01Y}; a
 * {@code UserListItem} is one element of the paginated {@code UserListResponse}
 * returned by the users listing endpoint.</p>
 *
 * <p>Field widths mirror the legacy fixed-width layout exactly (see the
 * {@link Size} constraints): {@code SEC-USR-ID PIC X(08)},
 * {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)} and
 * {@code SEC-USR-TYPE PIC X(01)}.</p>
 *
 * <p><strong>Security:</strong> the {@code SEC-USR-PWD} field is deliberately
 * <em>not</em> exposed here. This list row carries only the user identifier,
 * name and type; no password, password hash, or other credential material is
 * ever serialized through this DTO.</p>
 *
 * <p>The type is a stateless, immutable value holder (a Java {@code record});
 * instances are inherently thread-safe and safe to share.</p>
 *
 * @param userId    the 8-character user identifier ({@code SEC-USR-ID} /
 *                  {@code USRIDnn} on the BMS map)
 * @param firstName the user's first name, up to 20 characters
 *                  ({@code SEC-USR-FNAME} / {@code FNAMEnn})
 * @param lastName  the user's last name, up to 20 characters
 *                  ({@code SEC-USR-LNAME} / {@code LNAMEnn})
 * @param userType  the single-character user type ({@code SEC-USR-TYPE} /
 *                  {@code UTYPEnn}); {@code "A"} for administrator, {@code "U"}
 *                  for a regular user (per the {@code CDEMO-USRTYP-ADMIN} /
 *                  {@code CDEMO-USRTYP-USER} 88-levels)
 */
public record UserListItem(

        @Size(max = 8) String userId,

        @Size(max = 20) String firstName,

        @Size(max = 20) String lastName,

        @Size(max = 1) String userType

) {
}
