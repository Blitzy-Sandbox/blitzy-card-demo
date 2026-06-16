package com.carddemo.service.admin;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.UserResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delete-user service: removes a record from the user-security store (the
 * re-platformed VSAM {@code USRSEC} KSDS).
 *
 * <p>Behavioral translation of the COBOL online program {@code COUSR03C}
 * (CICS transaction {@code CU03}); see {@code app/cbl/COUSR03C.cbl} at source
 * commit {@code 27d6c6f}. The COBOL is a REFERENCE only and is not embedded
 * here.</p>
 *
 * <p>{@code COUSR03C} is pseudo-conversational: the operator first keys a user
 * id and presses Enter, which reads and displays the record and prompts for
 * confirmation ({@code PROCESS-ENTER-KEY} then {@code READ-USER-SEC-FILE}); the
 * operator then presses {@code PF5} to perform the removal
 * ({@code DELETE-USER-INFO} then {@code DELETE-USER-SEC-FILE}). The stateless
 * REST surface maps this onto two operations &mdash; {@link #getUser(String)}
 * for the load-for-confirm step and {@link #deleteUser(String)} for the delete
 * step &mdash; so the {@code GET}/{@code DELETE} split is the confirmation gate
 * and the load operation never deletes.</p>
 */
@Service
public class UserDeleteService {

    /** Empty-user-id edit message (COUSR03C {@code PROCESS-ENTER-KEY} and {@code DELETE-USER-INFO}). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /** Not-found message; COBOL FILE STATUS {@code 23} / CICS {@code NOTFND} (COUSR03C read and delete). */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    /** Confirmation prompt returned after a successful load (COUSR03C {@code READ-USER-SEC-FILE} NORMAL). */
    private static final String MSG_CONFIRM_PROMPT = "Press PF5 key to delete this user ...";

    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its required repository collaborator.
     *
     * @param userSecurityRepository keyed access to the user-security store
     */
    public UserDeleteService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Loads a user for delete confirmation (COUSR03C {@code PROCESS-ENTER-KEY}).
     *
     * <p>Reads the record and returns its display fields together with the
     * confirmation prompt; it performs no mutation, preserving the COBOL
     * confirmation gate.</p>
     *
     * @param userId the user id to load (COUSR03C {@code USRIDIN})
     * @return the user's display fields and the {@code PF5} confirmation prompt
     * @throws ValidationException     if {@code userId} is null or blank
     * @throws RecordNotFoundException if no user exists for {@code userId} (FILE STATUS {@code 23})
     */
    public UserResponse getUser(String userId) {
        // COUSR03C PROCESS-ENTER-KEY: WHEN USRIDIN = SPACES/LOW-VALUES -> empty-id edit.
        if (isBlank(userId)) {
            throw new ValidationException(MSG_USER_ID_EMPTY, "userId");
        }
        // READ-USER-SEC-FILE: keyed read; CICS NOTFND -> "User ID NOT found...".
        UserSecurity entity = userSecurityRepository.findBySecUsrId(userId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));
        // READ-USER-SEC-FILE NORMAL: display FNAME/LNAME/USRTYPE and prompt for PF5.
        return new UserResponse(entity.getSecUsrId(), entity.getSecUsrFname(), entity.getSecUsrLname(),
                entity.getSecUsrType(), MSG_CONFIRM_PROMPT, null);
    }

    /**
     * Deletes a user (COUSR03C {@code DELETE-USER-INFO}, the {@code PF5} path).
     *
     * <p>Re-reads the record before removing it, mirroring the COBOL
     * read-then-delete sequence, and echoes the removed user's display fields in
     * the success response. The whole operation is one transactional unit (CICS
     * task scope): any persistence failure rolls it back.</p>
     *
     * @param userId the user id to delete (COUSR03C {@code USRIDIN})
     * @return the removed user's display fields and the deletion success message
     * @throws ValidationException     if {@code userId} is null or blank
     * @throws RecordNotFoundException if no user exists for {@code userId} (FILE STATUS {@code 23})
     */
    @Transactional
    public UserResponse deleteUser(String userId) {
        // COUSR03C DELETE-USER-INFO: WHEN USRIDIN = SPACES/LOW-VALUES -> empty-id edit.
        if (isBlank(userId)) {
            throw new ValidationException(MSG_USER_ID_EMPTY, "userId");
        }
        // READ-USER-SEC-FILE before delete: existence check; CICS NOTFND -> "User ID NOT found...".
        UserSecurity entity = userSecurityRepository.findBySecUsrId(userId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_USER_NOT_FOUND));
        // DELETE-USER-SEC-FILE: EXEC CICS DELETE. An unexpected failure propagates as a DataAccessException.
        userSecurityRepository.deleteById(userId);
        // DELETE-USER-SEC-FILE NORMAL: success message echoing the removed user's details.
        return new UserResponse(entity.getSecUsrId(), entity.getSecUsrFname(), entity.getSecUsrLname(),
                entity.getSecUsrType(), "User " + userId + " has been deleted ...", null);
    }

    /**
     * Returns whether the supplied value is null or contains only whitespace.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is null or blank, otherwise {@code false}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
