package com.carddemo.service.admin;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for deleting a user from the security store (USRSEC).
 *
 * <p>Java equivalent of the CICS online program {@code COUSR03C}, which performs a
 * confirmation {@code EXEC CICS READ} followed by an {@code EXEC CICS DELETE}. The
 * original program is pseudo-conversational: the operator first keys a user
 * identifier and presses Enter, at which point the record is read and displayed with
 * a delete-confirmation prompt; the actual removal occurs only after the operator
 * presses PF5. That two-step gate is preserved here as two methods and is realised in
 * the REST layer as a load-for-confirm {@code GET} followed by a separate
 * {@code DELETE}, so a load never mutates the store.</p>
 *
 * <p>The response never carries a password: the plaintext {@code SEC-USR-PWD} field of
 * the COBOL {@code USRSEC} record is deliberately excluded from every result. Lineage
 * is preserved by reference to the original COBOL source commit {@code 27d6c6f}; no
 * COBOL is copied here.</p>
 */
@Service
public class UserDeleteService {

    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its collaborating repository.
     *
     * @param userSecurityRepository repository providing the keyed user lookup and the
     *                               inherited keyed delete against the
     *                               {@code user_security} table
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Loads a single user for delete confirmation without mutating the store.
     *
     * <p>Equivalent of the {@code COUSR03C PROCESS-ENTER-KEY} / {@code READ-USER-SEC-FILE}
     * path: validates the identifier, reads the record, and returns its display fields
     * together with the delete-confirmation prompt. This is the read half of the
     * confirmation gate and never deletes.</p>
     *
     * @param userId the identifier of the user to load; must be non-blank
     * @return the user's display fields and the delete-confirmation prompt, with a
     *         {@code null} error message on success
     * @throws ValidationException     when {@code userId} is {@code null} or blank
     * @throws RecordNotFoundException when no user exists for {@code userId}
     *                                 (COBOL FILE STATUS {@code 23})
     */
    public UserResponse getUser(String userId) {
        if (isBlank(userId)) {
            throw new ValidationException("User ID can NOT be empty...", "userId");
        }
        UserSecurity entity = userSecurityRepository.findBySecUsrId(userId)
                .orElseThrow(() -> new RecordNotFoundException("User ID NOT found..."));
        return new UserResponse(
                entity.getSecUsrId(),
                entity.getSecUsrFname(),
                entity.getSecUsrLname(),
                entity.getSecUsrType(),
                "Press PF5 key to delete this user ...",
                null);
    }

    /**
     * Deletes a user from the security store after confirming the record exists.
     *
     * <p>Equivalent of the {@code COUSR03C DELETE-USER-INFO} path taken when the operator
     * presses PF5: validates the identifier, re-reads the record to confirm it is
     * present, removes it, and returns the removed user's display fields with the success
     * message. The display fields are captured before the delete so the result can still
     * echo the removed record, matching the original success screen. The method runs
     * within a single write transaction, so any runtime exception rolls the unit of work
     * back.</p>
     *
     * @param userId the identifier of the user to delete; must be non-blank
     * @return the removed user's display fields and the success message, with a
     *         {@code null} error message on success
     * @throws ValidationException     when {@code userId} is {@code null} or blank
     * @throws RecordNotFoundException when no user exists for {@code userId}
     *                                 (COBOL FILE STATUS {@code 23})
     */
    @Transactional
    public UserResponse deleteUser(String userId) {
        if (isBlank(userId)) {
            throw new ValidationException("User ID can NOT be empty...", "userId");
        }
        UserSecurity entity = userSecurityRepository.findBySecUsrId(userId)
                .orElseThrow(() -> new RecordNotFoundException("User ID NOT found..."));
        userSecurityRepository.deleteById(userId);
        return new UserResponse(
                entity.getSecUsrId(),
                entity.getSecUsrFname(),
                entity.getSecUsrLname(),
                entity.getSecUsrType(),
                "User " + userId + " has been deleted ...",
                null);
    }

    /**
     * Reports whether a string is {@code null} or contains only whitespace.
     *
     * @param s the value to test
     * @return {@code true} when {@code s} is {@code null} or blank
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
