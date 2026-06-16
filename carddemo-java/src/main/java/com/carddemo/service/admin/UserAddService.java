package com.carddemo.service.admin;

import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserAddRequest;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Application service for adding a new security ({@code USRSEC}) user.
 *
 * <p>Java re-platforming of the COBOL/CICS online program {@code COUSR01C}
 * (paragraph {@code WRITE-USER-SEC-FILE}), which created a regular or admin user
 * in the VSAM {@code USRSEC} dataset. It is invoked by
 * {@code com.carddemo.controller.UserAdminController} for {@code POST /api/admin/users}:
 * it accepts a {@link UserAddRequest}, validates the input, guards against a
 * duplicate user id, persists a {@link UserSecurity} row, and returns a
 * {@link UserResponse} confirmation.</p>
 *
 * <p>The five required-field edits and their messages mirror the
 * {@code PROCESS-ENTER-KEY} {@code EVALUATE TRUE} cascade of the original program,
 * preserving both the check order and the message text. The duplicate-key guard
 * reproduces the COBOL {@code DUPKEY}/{@code DUPREC} branch of the indexed
 * {@code WRITE} by performing an existence check before insert. An unexpected
 * persistence failure (the COBOL {@code WHEN OTHER} branch) is not caught here; any
 * {@code org.springframework.dao.DataAccessException} propagates to the global
 * exception handler.</p>
 *
 * <p>The plaintext credential carried on {@link UserAddRequest#password()} is
 * BCrypt-encoded before persistence and is never stored in plaintext, logged, or
 * returned (constraint C-003); {@link UserResponse} has no password field.</p>
 *
 * <p>Lineage is preserved by reference to source commit {@code 27d6c6f}; the
 * original COBOL is not copied into this project.</p>
 */
@Service
public class UserAddService {

    private final UserSecurityRepository userSecurityRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its required collaborators.
     *
     * @param userSecurityRepository repository providing keyed lookup and persistence
     *                               for {@link UserSecurity} records
     * @param passwordEncoder        BCrypt encoder used to hash the supplied password
     *                               before persistence (constraint C-003)
     */
    public UserAddService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Adds a new user to the security store.
     *
     * <p>Validates the five required fields in the original program's order (first
     * name, last name, user id, password, user type), rejecting the first blank or
     * missing field with a {@link ValidationException}. If a user already exists for
     * the requested id, a {@link DuplicateRecordException} is raised. Otherwise the
     * record is built, the password is BCrypt-encoded, and the row is persisted; a
     * confirmation {@link UserResponse} is returned with no password and a
     * {@code null} error message.</p>
     *
     * @param request the add-user request carrying the new user's details
     * @return a confirmation response describing the created user
     * @throws ValidationException      if a required field is blank or the user type is missing
     * @throws DuplicateRecordException if a user already exists for the requested id
     */
    public UserResponse addUser(UserAddRequest request) {
        if (isBlank(request.firstName())) {
            throw new ValidationException("First Name can NOT be empty...", "firstName");
        }
        if (isBlank(request.lastName())) {
            throw new ValidationException("Last Name can NOT be empty...", "lastName");
        }
        if (isBlank(request.userId())) {
            throw new ValidationException("User ID can NOT be empty...", "userId");
        }
        if (isBlank(request.password())) {
            throw new ValidationException("Password can NOT be empty...", "password");
        }
        if (request.userType() == null) {
            throw new ValidationException("User Type can NOT be empty...", "userType");
        }

        if (userSecurityRepository.findBySecUsrId(request.userId()).isPresent()) {
            throw new DuplicateRecordException("User ID already exist...");
        }

        // Upper-case the password (Locale.ROOT) before BCrypt encoding so the stored hash
        // matches AuthenticationService, which upper-cases the entered password before BCrypt
        // verification — reproducing the COSGN00C/COUSR01C FUNCTION UPPER-CASE(PASSWDI) behavior.
        // Without this, a user created with lower/mixed-case input could never authenticate.
        String normalizedPassword = request.password().toUpperCase(Locale.ROOT);

        UserSecurity entity = new UserSecurity();
        entity.setSecUsrId(request.userId());
        entity.setSecUsrFname(request.firstName());
        entity.setSecUsrLname(request.lastName());
        entity.setSecUsrPwd(passwordEncoder.encode(normalizedPassword));
        entity.setSecUsrType(request.userType());
        userSecurityRepository.save(entity);

        String message = "User " + request.userId() + " has been added ...";
        return new UserResponse(request.userId(), request.firstName(), request.lastName(),
                request.userType(), message, null);
    }

    /**
     * Reports whether a string is {@code null} or contains only whitespace.
     *
     * @param value the value to test
     * @return {@code true} if {@code value} is {@code null} or blank, otherwise {@code false}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
