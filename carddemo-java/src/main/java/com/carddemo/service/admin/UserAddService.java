package com.carddemo.service.admin;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserAddRequest;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Add-user service &mdash; the Java translation of the COBOL online program
 * {@code COUSR01C} (the {@code WRITE-USER-SEC-FILE} flow that creates a new
 * regular/admin user in the {@code USRSEC} file), source commit {@code 27d6c6f}.
 *
 * <p>Backs {@code POST /api/admin/users}: it accepts a {@link UserAddRequest},
 * reproduces the program's {@code PROCESS-ENTER-KEY} required-field edit cascade
 * and its {@code WRITE} duplicate-key guard, persists a {@link UserSecurity} row,
 * and returns a {@link UserResponse} confirmation. The CICS {@code WRITE} that
 * targets the VSAM {@code USRSEC} KSDS is expressed as a Spring Data JPA
 * {@code save}; the program's existence-first duplicate branch
 * ({@code DUPKEY}/{@code DUPREC}) is reproduced with a deterministic keyed lookup
 * rather than relying on a database-constraint exception.</p>
 *
 * <p><strong>Security (constraint C-003):</strong> the submitted password is
 * BCrypt-hashed via the injected {@link PasswordEncoder} before persistence; the
 * plaintext credential is never stored, never logged, and never echoed back in the
 * response.</p>
 */
@Service
public class UserAddService {

    /** Required-field message for a missing first name (COBOL {@code COUSR01C}). */
    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    /** Required-field message for a missing last name (COBOL {@code COUSR01C}). */
    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    /** Required-field message for a missing user id (COBOL {@code COUSR01C}). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Required-field message for a missing password (COBOL {@code COUSR01C}). */
    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    /** Required-field message for a missing user type (COBOL {@code COUSR01C}). */
    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    /** Duplicate-key message (COBOL {@code COUSR01C} {@code DUPKEY}/{@code DUPREC} branch). */
    private static final String MSG_DUPLICATE_USER = "User ID already exist...";

    /** Leading fragment of the success confirmation (COBOL {@code COUSR01C} {@code NORMAL} branch). */
    private static final String MSG_ADDED_PREFIX = "User ";

    /** Trailing fragment of the success confirmation (COBOL {@code COUSR01C} {@code NORMAL} branch). */
    private static final String MSG_ADDED_SUFFIX = " has been added ...";

    /** Field name reported with the first-name validation failure. */
    private static final String FIELD_FIRST_NAME = "firstName";

    /** Field name reported with the last-name validation failure. */
    private static final String FIELD_LAST_NAME = "lastName";

    /** Field name reported with the user-id validation failure. */
    private static final String FIELD_USER_ID = "userId";

    /** Field name reported with the password validation failure. */
    private static final String FIELD_PASSWORD = "password";

    /** Field name reported with the user-type validation failure. */
    private static final String FIELD_USER_TYPE = "userType";

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its collaborators.
     *
     * @param userSecurityRepository repository for the {@code user_security} table
     *                               (re-platformed VSAM {@code USRSEC} KSDS)
     * @param passwordEncoder        BCrypt encoder used to hash the password before
     *                               persistence (constraint C-003)
     */
    public UserAddService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Adds a new user to the {@code user_security} store.
     *
     * <p>Mirrors {@code COUSR01C}: the required-field edits are applied in the
     * program's {@code EVALUATE TRUE} order (first name, last name, user id,
     * password, user type) and fail on the first blank field; a user id that already
     * exists is rejected as a duplicate; otherwise the password is BCrypt-hashed and
     * the new user is persisted before the confirmation is built.</p>
     *
     * @param request the user-add request carrying the editable {@code COUSR01} map
     *                fields; its {@code password()} component is plaintext on input
     * @return a {@link UserResponse} confirmation echoing the saved user (without any
     *         password) and the COBOL-equivalent success message
     * @throws ValidationException      if a required field is blank; the offending
     *                                  field name accompanies the message
     * @throws DuplicateRecordException if the requested user id already exists
     *                                  (COBOL {@code DUPKEY}/{@code DUPREC})
     */
    public UserResponse addUser(UserAddRequest request) {
        if (isBlank(request.firstName())) {
            throw new ValidationException(MSG_FIRST_NAME_EMPTY, FIELD_FIRST_NAME);
        }
        if (isBlank(request.lastName())) {
            throw new ValidationException(MSG_LAST_NAME_EMPTY, FIELD_LAST_NAME);
        }
        if (isBlank(request.userId())) {
            throw new ValidationException(MSG_USER_ID_EMPTY, FIELD_USER_ID);
        }
        if (isBlank(request.password())) {
            throw new ValidationException(MSG_PASSWORD_EMPTY, FIELD_PASSWORD);
        }
        if (request.userType() == null) {
            throw new ValidationException(MSG_USER_TYPE_EMPTY, FIELD_USER_TYPE);
        }

        if (userSecurityRepository.findBySecUsrId(request.userId()).isPresent()) {
            throw new DuplicateRecordException(MSG_DUPLICATE_USER);
        }

        UserSecurity entity = new UserSecurity();
        entity.setSecUsrId(request.userId());
        entity.setSecUsrFname(request.firstName());
        entity.setSecUsrLname(request.lastName());
        entity.setSecUsrPwd(passwordEncoder.encode(request.password()));
        entity.setSecUsrType(request.userType());
        userSecurityRepository.save(entity);

        String message = MSG_ADDED_PREFIX + request.userId() + MSG_ADDED_SUFFIX;
        return new UserResponse(
                request.userId(),
                request.firstName(),
                request.lastName(),
                request.userType(),
                message,
                null);
    }

    /**
     * Reports whether a string is {@code null} or contains only whitespace, mirroring
     * the COBOL {@code SPACES OR LOW-VALUES} blank test.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when {@code value} is {@code null}, empty, or whitespace-only
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
