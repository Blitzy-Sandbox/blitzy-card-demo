package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/signin}.
 *
 * <p>Mirrors the credential inputs of the {@code COSGN00} sign-on map
 * ({@code USERIDI} and {@code PASSWDI}, each {@code PIC X(8)}); the map's
 * screen-chrome and CICS system fields are not part of this contract.
 * Source lineage: AWS CardDemo commit {@code 27d6c6f}.</p>
 *
 * @param userId   sign-on user id; required, maximum 8 characters
 * @param password sign-on password; required, maximum 8 characters
 */
public record SignOnRequest(

        @NotBlank
        @Size(max = 8)
        String userId,

        @NotBlank
        @Size(max = 8)
        String password) {
}
