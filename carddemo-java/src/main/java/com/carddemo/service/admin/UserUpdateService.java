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
 * Administrative update of a single {@code USRSEC} user record.
 *
 * <p>Java re-platforming of the CICS online program {@code COUSR02C} (transaction
 * {@code CU02}), whose two interaction steps &mdash; the {@code PROCESS-ENTER-KEY}
 * load-for-edit ({@code EXEC CICS READ ... UPDATE}) and the {@code UPDATE-USER-INFO}
 * field-edit-then-{@code REWRITE} &mdash; are expressed here as {@link #getUser(String)}
 * and {@link #updateUser(UserUpdateRequest)} respectively. The COBOL is a behavioral
 * reference only and is not copied; lineage is preserved against source repository commit
 * {@code 27d6c6f}.</p>
 *
 * <p>The service is consumed by {@code com.carddemo.controller.UserAdminController}
 * ({@code GET} load-for-edit and {@code PUT /api/admin/users/{userId}}) and always returns a
 * {@link UserResponse}. It is stateless: the conversational {@code CARDDEMO-COMMAREA} of the
 * source program is not reproduced as server-held state.</p>
 *
 * <p>The persisted password is a BCrypt hash (constraint C-003); a supplied password is
 * re-encoded only when it differs from the stored hash, and a password value is never logged,
 * echoed, or returned to the caller.</p>
 */
@Service
public class UserUpdateService {

    /** Keyed access to the {@code user_security} table (re-platformed {@code USRSEC} KSDS). */
    private final UserSecurityRepository userSecurityRepository;

    /** BCrypt encoder used to detect and apply password changes without storing plaintext. */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the service with its collaborators.
     *
     * @param userSecurityRepository repository providing the keyed user lookup and the
     *                               {@code save} used to persist an updated record
     * @param passwordEncoder        BCrypt encoder used by {@link #updateUser(UserUpdateRequest)}
     *                               to compare and re-encode a supplied password
     */
    public UserUpdateService(UserSecurityRepository userSecurityRepository, PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Loads an existing user for editing &mdash; the equivalent of {@code COUSR02C}'s
     * {@code PROCESS-ENTER-KEY} paragraph and its {@code READ-USER-SEC-FILE} keyed read.
     *
     * @param userId the user identifier to load; must be non-blank
     * @return a {@link UserResponse} carrying the current first name, last name, and user type
     *         together with the edit prompt; the password is intentionally omitted
     * @throws ValidationException     if {@code userId} is {@code null} or blank
     * @throws RecordNotFoundException if no user exists for {@code userId} (COBOL FILE STATUS
     *                                 {@code 23})
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
     * Applies an administrative update to an existing user &mdash; the equivalent of
     * {@code COUSR02C}'s {@code UPDATE-USER-INFO} paragraph and its {@code UPDATE-USER-SEC-FILE}
     * {@code REWRITE}.
     *
     * <p>Required-field validation is performed in the original program's order (user id, first
     * name, last name, user type); the password is not validated because the request contract
     * makes it optional. After loading the record, each field is compared and applied
     * individually; the record is saved only if at least one field actually changed. A supplied
     * password is re-encoded only when it does not already match the stored BCrypt hash.</p>
     *
     * @param request the update payload; {@code userId}, {@code firstName}, {@code lastName}, and
     *                {@code userType} are required, while {@code password} is optional (a blank or
     *                {@code null} value keeps the existing password)
     * @return a {@link UserResponse} reflecting the resulting record state and an outcome message;
     *         the password is intentionally omitted
     * @throws ValidationException     if {@code userId}, {@code firstName}, or {@code lastName} is
     *                                 blank, or if {@code userType} is {@code null}
     * @throws RecordNotFoundException if no user exists for {@code request.userId()} (COBOL FILE
     *                                 STATUS {@code 23})
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
     * Null-safe blank check mirroring the COBOL {@code = SPACES OR LOW-VALUES} edit.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is {@code null} or contains only whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
