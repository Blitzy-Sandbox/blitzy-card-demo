package com.carddemo.service.admin;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.dto.UserUpdateRequest;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Update-user (USRSEC modify) service &mdash; the Java translation of the AWS CardDemo CICS online
 * program {@code COUSR02C} ({@code EXEC CICS READ ... UPDATE} followed by {@code REWRITE}), source
 * commit {@code 27d6c6f}. Behavior is translated, never copied.
 *
 * <p>Consumed by {@code UserAdminController} for the administrative user-maintenance flow
 * ({@code PUT /api/admin/users/{userId}} plus a load-for-edit {@code GET}). The two public methods
 * mirror the two interaction steps of the original pseudo-conversational program:</p>
 * <ul>
 *   <li>{@link #getUser(String)} &mdash; the {@code PROCESS-ENTER-KEY} / {@code READ-USER-SEC-FILE}
 *       step that loads an existing user for editing and returns the save prompt.</li>
 *   <li>{@link #updateUser(UserUpdateRequest)} &mdash; the {@code UPDATE-USER-INFO} /
 *       {@code UPDATE-USER-SEC-FILE} step that validates input, applies field-by-field change
 *       detection, and rewrites the record only when something actually changed.</li>
 * </ul>
 *
 * <p>The persisted password is a BCrypt hash (column length 60), so the plaintext compare of the
 * original program ({@code PASSWDI NOT = SEC-USR-PWD}) is expressed with
 * {@link PasswordEncoder#matches(CharSequence, String)}; a newly supplied password is re-encoded with
 * {@link PasswordEncoder#encode(CharSequence)} only when it differs from the stored hash. A password
 * is never stored in plaintext and is never echoed back in any response (security constraint C-003).
 * The {@code REWRITE} of a single record is bracketed by {@link Transactional} for correct
 * commit/rollback semantics.</p>
 */
@Service
public class UserUpdateService {

    /** USRSEC repository providing the keyed read and the record rewrite. */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt encoder used to detect a real password change and to hash a new password. */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its collaborators.
     *
     * @param userSecurityRepository repository for USRSEC keyed read and rewrite
     * @param passwordEncoder        BCrypt encoder for password change detection and hashing
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Loads an existing user for editing. Java equivalent of the {@code COUSR02C}
     * {@code PROCESS-ENTER-KEY} / {@code READ-USER-SEC-FILE} step.
     *
     * <p>The returned {@link UserResponse} carries the editable fields and the save prompt; it never
     * carries the password (the stored value is a BCrypt hash and is intentionally not surfaced).</p>
     *
     * @param userId the target user identifier
     * @return a {@link UserResponse} populated with the user's first name, last name, and type, plus
     *         the {@code "Press PF5 key to save your updates ..."} prompt and no error message
     * @throws ValidationException     if {@code userId} is blank
     * @throws RecordNotFoundException if no user exists for {@code userId} (FILE STATUS {@code 23})
     */
    public UserResponse getUser(String userId) {
        if (isBlank(userId)) {
            throw new ValidationException("User ID can NOT be empty...", "userId");
        }
        UserSecurity entity = userSecurityRepository.findBySecUsrId(userId)
                .orElseThrow(() -> new RecordNotFoundException("User ID NOT found..."));
        return new UserResponse(entity.getSecUsrId(), entity.getSecUsrFname(), entity.getSecUsrLname(),
                entity.getSecUsrType(), "Press PF5 key to save your updates ...", null);
    }

    /**
     * Applies an update to an existing user. Java equivalent of the {@code COUSR02C}
     * {@code UPDATE-USER-INFO} / {@code UPDATE-USER-SEC-FILE} step.
     *
     * <p>Required-field validation is performed first, in the original program's order: user id,
     * first name, last name, then user type. The password is intentionally <em>not</em> validated
     * here: the {@link UserUpdateRequest#password()} component is optional, and a blank or absent
     * value means "leave the existing password unchanged".</p>
     *
     * <p>After loading the record, each field is compared against the stored value and applied only
     * when it differs. The password is treated as a change candidate only when supplied and only when
     * {@link PasswordEncoder#matches(CharSequence, String)} reports that it does not already match the
     * stored hash, in which case it is re-encoded. The record is rewritten only when at least one
     * field changed; otherwise no write occurs and a "nothing to update" message is returned.</p>
     *
     * @param request the update request (user id, first name, last name, optional password, user type)
     * @return a {@link UserResponse} reflecting the resulting field values and the operation message;
     *         never includes a password
     * @throws ValidationException     if user id, first name, or last name is blank, or user type is
     *                                 {@code null}
     * @throws RecordNotFoundException if no user exists for the requested id (FILE STATUS {@code 23})
     */
    @Transactional
    public UserResponse updateUser(UserUpdateRequest request) {
        if (isBlank(request.userId())) {
            throw new ValidationException("User ID can NOT be empty...", "userId");
        }
        if (isBlank(request.firstName())) {
            throw new ValidationException("First Name can NOT be empty...", "firstName");
        }
        if (isBlank(request.lastName())) {
            throw new ValidationException("Last Name can NOT be empty...", "lastName");
        }
        if (request.userType() == null) {
            throw new ValidationException("User Type can NOT be empty...", "userType");
        }

        UserSecurity entity = userSecurityRepository.findBySecUsrId(request.userId())
                .orElseThrow(() -> new RecordNotFoundException("User ID NOT found..."));

        boolean modified = false;
        if (!Objects.equals(request.firstName(), entity.getSecUsrFname())) {
            entity.setSecUsrFname(request.firstName());
            modified = true;
        }
        if (!Objects.equals(request.lastName(), entity.getSecUsrLname())) {
            entity.setSecUsrLname(request.lastName());
            modified = true;
        }
        if (!isBlank(request.password())
                && !passwordEncoder.matches(request.password(), entity.getSecUsrPwd())) {
            entity.setSecUsrPwd(passwordEncoder.encode(request.password()));
            modified = true;
        }
        if (request.userType() != entity.getSecUsrType()) {
            entity.setSecUsrType(request.userType());
            modified = true;
        }

        String message;
        if (modified) {
            userSecurityRepository.save(entity);
            message = "User " + request.userId() + " has been updated ...";
        } else {
            message = "Please modify to update ...";
        }
        return new UserResponse(entity.getSecUsrId(), entity.getSecUsrFname(), entity.getSecUsrLname(),
                entity.getSecUsrType(), message, null);
    }

    /**
     * Reports whether a string is {@code null} or blank (empty or only whitespace).
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is {@code null} or contains only whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
