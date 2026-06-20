package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-on request body for {@code POST /api/auth/signin}.
 *
 * <p>Carries the credential inputs of the CardDemo sign-on screen. The fields
 * mirror the {@code COSGN00} symbolic map input view ({@code COSGN0AI}):
 * {@code USERIDI PIC X(8)} maps to {@link #userId()} and
 * {@code PASSWDI PIC X(8)} maps to {@link #password()} (source commit
 * {@code 27d6c6f}). CICS system values, screen-chrome, and error-message
 * fields of the original map are intentionally not part of this contract.</p>
 *
 * <p>This is an immutable, serializable data carrier consumed by the
 * authentication controller and service layers. Instances must never be
 * logged, as they convey a plaintext credential in transit.</p>
 *
 * @param userId   the user identifier; required and at most 8 characters
 *                 (mirrors {@code USERID PIC X(8)})
 * @param password the user password; required and at most 8 characters
 *                 (mirrors {@code PASSWD PIC X(8)})
 */
public record SignOnRequest(
        @NotBlank @Size(max = 8) String userId,
        @NotBlank @Size(max = 8) String password) {
}
