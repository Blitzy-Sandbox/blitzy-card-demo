package com.cardemo.unit.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserSecurityDto;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import com.cardemo.repository.UserSecurityRepository;
import com.cardemo.service.admin.UserDeleteService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fast, fully-mocked behavioral-parity unit test for
 * {@link com.cardemo.service.admin.UserDeleteService}, the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * migration of the legacy AWS CardDemo admin delete-user CICS program
 * {@code app/cbl/COUSR03C.cbl} (CICS transaction {@code CU03}, BMS map {@code COUSR3A} / mapset
 * {@code COUSR03}, &ldquo;Delete User&rdquo;). The COBOL is read-only reference material at the
 * frozen baseline commit SHA {@code 27d6c6f} and is never copied into this repository &mdash; only
 * its observable behavior is asserted here (AAP &sect;0.7.1&ndash;&sect;0.7.2).
 *
 * <h2>Test strategy &mdash; pure Mockito, no Spring</h2>
 * <p>These are millisecond, in-memory unit tests: there is <em>no</em> {@code @SpringBootTest}, no
 * Spring context, no {@code @DataJpaTest}, no database, no Testcontainers and no file/network I/O.
 * The single {@link UserSecurityRepository} collaborator is a Mockito {@code @Mock}; the system under
 * test is wired by constructor injection via {@code @InjectMocks}. Because the service is invoked
 * directly (not through a Spring AOP proxy), its {@code @PreAuthorize("hasRole('ADMIN')")} /
 * {@code @Transactional} advice is intentionally not exercised here &mdash; that wiring is verified by
 * the integration tier, not by this fast unit tier.</p>
 *
 * <p>The class runs under {@link MockitoExtension} (default {@code STRICT_STUBS}), so every
 * {@code when(...)} stub must be exercised by the test that declares it. The blank-id validation tests
 * throw <em>before</em> any repository call, so they declare <em>no</em> stubs; the not-found and
 * happy-path tests stub only {@code findBySecUsrId} (which is always consumed). The delete itself is a
 * {@code void} method, so it is never stubbed &mdash; only verified. No {@code lenient()} is needed
 * anywhere.</p>
 *
 * <h2>No {@code PasswordEncoder} &mdash; delete handles no credential (key insight)</h2>
 * <p>Unlike add ({@code COUSR01C}) and update ({@code COUSR02C}) &mdash; whose tests wire a real
 * {@code BCryptPasswordEncoder} to exercise the single permitted behavioral change (constraint
 * C-003) &mdash; the {@code COUSR3A} delete screen has <strong>no password field</strong> and
 * {@code COUSR03C} never reads, compares, moves or displays {@code SEC-USR-PWD}. The migrated
 * {@link UserDeleteService} therefore injects <strong>no</strong> {@code PasswordEncoder}. This test
 * class deliberately references <strong>no</strong> encoder, no BCrypt, and no hashing of any kind:
 * the delete flow's only collaborator is the repository.</p>
 *
 * <h2>Pinned parity contract (verified against the on-disk service + {@code COUSR03C.cbl})</h2>
 * <ul>
 *   <li><strong>Two operations, two methods.</strong> {@code COUSR03C} fused a read-for-confirm
 *       ({@code PROCESS-ENTER-KEY}) and an apply-on-PF5 ({@code DELETE-USER-INFO}) behind one 3270
 *       screen; the migration keeps them as {@code loadUser(String)} and {@code deleteUser(String)},
 *       each tested independently here.</li>
 *   <li><strong>Single field edit.</strong> Delete edits <strong>only the user id</strong>. The
 *       {@code DELETE-USER-INFO} {@code EVALUATE TRUE} ({@code COUSR03C} L176-186) has exactly one
 *       branch ({@code WHEN USRIDINI = SPACES OR LOW-VALUES}); its {@code WHEN OTHER} simply
 *       {@code CONTINUE}s. A blank id throws a {@link ValidationException} carrying the verbatim
 *       &ldquo;User ID can NOT be empty...&rdquo; text (three trailing dots, no leading space).</li>
 *   <li><strong>Find-then-delete guard (headline parity assertion).</strong> {@code DELETE-USER-INFO}
 *       performs {@code READ-USER-SEC-FILE} <em>then</em> {@code DELETE-USER-SEC-FILE}
 *       ({@code COUSR03C} L188-191). A missing id ({@code DFHRESP(NOTFND)}) maps to
 *       {@link RecordNotFoundException} (&ldquo;User ID NOT found...&rdquo;) and the keyed read
 *       guard means <strong>no row is ever deleted</strong> for an absent id. The happy path reads,
 *       <em>then</em> deletes, exactly once and in that order.</li>
 *   <li><strong>Delete-by-entity.</strong> The service issues {@code delete(entity)} on the
 *       already-loaded record (reproducing the COBOL {@code READ ... UPDATE} then
 *       {@code DELETE DATASET('USRSEC')}), <strong>not</strong> {@code deleteById(id)} &mdash; so the
 *       verifications assert {@code delete(UserSecurity)}.</li>
 *   <li><strong>No credential surfaced.</strong> The success echo conveys id/first&nbsp;name/last&nbsp;name/type
 *       only; the response password is always {@code null} (the delete flow reads no credential and the
 *       DTO password is {@code WRITE_ONLY}).</li>
 *   <li><strong>Unreachable COBOL artifact not modeled.</strong> {@code DELETE-USER-SEC-FILE}'s
 *       {@code WHEN OTHER} branch reads &ldquo;Unable to Update User...&rdquo; &mdash; a known
 *       copy/paste artifact made unreachable by the read guard. It is deliberately <strong>not</strong>
 *       asserted by any test.</li>
 * </ul>
 *
 * <p>There is <strong>no</strong> {@code USRSEC} golden fixture under {@code app/data/ASCII/} (only
 * nine fixtures exist, none for users), so all test data is built inline. The user id is {@code X(08)}
 * (max eight characters) and the user names {@code X(20)} (max twenty), per {@code app/cpy/CSUSR01Y.cpy}.</p>
 *
 * @see com.cardemo.service.admin.UserDeleteService
 * @see com.cardemo.repository.UserSecurityRepository
 * @see com.cardemo.model.dto.UserSecurityDto
 * @see com.cardemo.model.enums.UserType
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserDeleteService — COUSR03C delete-user parity (CU03)")
class UserDeleteServiceTest {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message literals (COUSR03C.cbl) — asserted directly for byte-exact parity. The
    // trailing dots are part of the external contract and must not drift. These mirror the
    // package-private constants on the service under test (UserDeleteService.MSG_*).
    // -----------------------------------------------------------------------------------------------

    /** {@code COUSR03C} L147/L179: empty user-id edit (the sole {@code DELETE-USER-INFO} edit). */
    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    /**
     * {@code COUSR03C} L289/L325: {@code DFHRESP(NOTFND)} not-found message from the
     * {@code READ-USER-SEC-FILE} / {@code DELETE-USER-SEC-FILE} branches.
     */
    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    // -----------------------------------------------------------------------------------------------
    // Inline fixtures. The existing record is the user loaded/deleted; values are whitespace-free so a
    // trimmed key compare equals the raw value (the service trims the 8-byte VSAM key before the read).
    // -----------------------------------------------------------------------------------------------

    /** Valid 8-character user id ({@code USRIDIN PIC X(8)} / {@code SEC-USR-ID}). */
    private static final String EXISTING_USER_ID = "USER0003";

    /** Existing first name ({@code SEC-USR-FNAME PIC X(20)}). */
    private static final String EXISTING_FIRST_NAME = "John";

    /** Existing last name ({@code SEC-USR-LNAME PIC X(20)}). */
    private static final String EXISTING_LAST_NAME = "Smith";

    /**
     * Placeholder for the stored credential on the fixture entity. The delete flow {@code COUSR03C}
     * <strong>never</strong> reads {@code SEC-USR-PWD}, so any value works; this is an obvious
     * non-secret marker (never a real or BCrypt-shaped value). Its presence on the loaded entity lets
     * the happy-path test prove the service does <em>not</em> copy the credential into the response.
     */
    private static final String IGNORED_STORED_PASSWORD = "IGNORED";

    /** Mocked data-access collaborator (the {@code USRSEC} VSAM KSDS replacement); the only dependency. */
    @Mock
    private UserSecurityRepository userSecurityRepository;

    /** System under test; constructor-injected with the mock repository (no encoder, no other beans). */
    @InjectMocks
    private UserDeleteService userDeleteService;

    /** Captures the {@link UserSecurity} entity handed to {@code delete(...)} on the happy path. */
    private final ArgumentCaptor<UserSecurity> deletedCaptor = ArgumentCaptor.forClass(UserSecurity.class);

    /**
     * Builds the existing user record as it would be loaded from {@code USRSEC} for the
     * delete-confirmation view: id, first name, last name and {@code ADMIN} type, plus a placeholder
     * stored credential the delete flow ignores. The same instance is what the service hands to
     * {@code delete(...)} after the keyed read.
     *
     * @return a populated {@link UserSecurity} fixture (id/names/type set; password is an ignored placeholder)
     */
    private static UserSecurity existingUser() {
        UserSecurity user = new UserSecurity();
        user.setSecUsrId(EXISTING_USER_ID);
        user.setSecUsrFname(EXISTING_FIRST_NAME);
        user.setSecUsrLname(EXISTING_LAST_NAME);
        user.setSecUsrType(UserType.ADMIN);
        user.setSecUsrPwd(IGNORED_STORED_PASSWORD); // delete reads no credential; value is irrelevant
        return user;
    }

    // ===============================================================================================
    // Phase 3 — loadUser(String) : read-for-confirm parity (COUSR03C PROCESS-ENTER-KEY).
    //   The only edit on load is the user id; a successful load returns demographics but NEVER the
    //   password (the delete screen COUSR3A carries no password field). loadUser only reads — it must
    //   never delete.
    // ===============================================================================================

    @ParameterizedTest(name = "loadUser blank id [{0}] -> ValidationException, no repository read")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("loadUser: blank/null/whitespace id -> 'User ID can NOT be empty...'; no repo read/delete")
    void loadUserBlankIdThrowsValidationAndDoesNotRead(String blankUserId) {
        // PROCESS-ENTER-KEY EVALUATE TRUE: WHEN USRIDINI = SPACES OR LOW-VALUES (COUSR03C L144-154). The
        // blank edit fires before the keyed read, so findBySecUsrId is never reached and nothing is deleted.
        assertThatThrownBy(() -> userDeleteService.loadUser(blankUserId))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).delete(any());
    }

    @Test
    @DisplayName("loadUser: id not found -> RecordNotFoundException('User ID NOT found...')")
    void loadUserNotFoundThrowsRecordNotFound() {
        // READ-USER-SEC-FILE WHEN DFHRESP(NOTFND) -> "User ID NOT found..." (COUSR03C L287-292). An empty
        // Optional reproduces the not-found RESP code.
        when(userSecurityRepository.findBySecUsrId(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDeleteService.loadUser(EXISTING_USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_NOT_FOUND);
        // A read-for-confirm never removes a row, even on the not-found path.
        verify(userSecurityRepository, never()).delete(any());
    }

    @Test
    @DisplayName("loadUser: happy path returns demographics (id/first/last/type) but NEVER the password")
    void loadUserHappyPathReturnsDemographicsWithoutPassword() {
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));

        UserSecurityDto response = userDeleteService.loadUser(EXISTING_USER_ID);

        // WHEN DFHRESP(NORMAL): MOVE SEC-USR-FNAME/LNAME/TYPE TO map fields (COUSR03C L164-169). The
        // populated DTO is the success outcome; the "Press PF5 key to delete this user ..." prompt is
        // 3270 screen chrome with no REST channel and is intentionally not reproduced.
        assertThat(response.getUserId()).isEqualTo(EXISTING_USER_ID);
        assertThat(response.getFirstName()).isEqualTo(EXISTING_FIRST_NAME);
        assertThat(response.getLastName()).isEqualTo(EXISTING_LAST_NAME);
        assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
        // The delete screen (COUSR3A) has NO password field, so the response credential is left unset
        // even though the loaded entity carries a (placeholder) stored value.
        assertThat(response.getPassword()).isNull();
        // loadUser is read-only (COUSR03C PROCESS-ENTER-KEY performs no DELETE).
        verify(userSecurityRepository, never()).delete(any());
    }

    @Test
    @DisplayName("loadUser: the 8-byte user id key is trimmed for the keyed read")
    void loadUserTrimsUserIdForKeyedRead() {
        // MOVE USRIDINI TO SEC-USR-ID + PERFORM READ-USER-SEC-FILE (COUSR03C L160-161): the canonical
        // VSAM key is the trimmed id, so a padded input still reads the same stored record.
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existingUser()));

        userDeleteService.loadUser("  " + EXISTING_USER_ID + "  "); // "  USER0003  "

        verify(userSecurityRepository).findBySecUsrId(EXISTING_USER_ID);
    }

    // ===============================================================================================
    // Phase 4 — deleteUser(String) : PF5 commit parity (COUSR03C DELETE-USER-INFO). A single empty-id
    //   edit precedes a keyed read THEN a delete. The find-then-delete guard (READ before DELETE) is
    //   the headline parity point: a missing id never reaches the delete call. The delete operates on
    //   the already-loaded entity via delete(entity), NOT deleteById.
    // ===============================================================================================

    @ParameterizedTest(name = "deleteUser blank id [{0}] -> ValidationException, no read and no delete")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("deleteUser: blank/null/whitespace id -> 'User ID can NOT be empty...'; no read, no delete")
    void deleteUserBlankIdThrowsValidationNoReadNoDelete(String blankUserId) {
        // DELETE-USER-INFO EVALUATE TRUE: WHEN USRIDINI = SPACES OR LOW-VALUES (COUSR03C L176-186). The
        // single emptiness edit fires before the keyed read, so neither the read nor the delete occurs.
        assertThatThrownBy(() -> userDeleteService.deleteUser(blankUserId))
                .isInstanceOf(ValidationException.class)
                .hasMessage(MSG_USER_ID_EMPTY);
        verify(userSecurityRepository, never()).findBySecUsrId(anyString());
        verify(userSecurityRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteUser: id not found -> RecordNotFoundException; NO delete (find-then-delete guard)")
    void deleteUserNotFoundThrowsRecordNotFoundAndDoesNotDelete() {
        // HEADLINE PARITY ASSERTION. COUSR03C performs READ-USER-SEC-FILE before DELETE-USER-SEC-FILE
        // (L188-191): an absent record (DFHRESP(NOTFND) -> empty Optional) yields "User ID NOT found..."
        // and the keyed read guard means the DELETE is never reached. Reproduces the observable
        // "not found, nothing removed" outcome.
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDeleteService.deleteUser(EXISTING_USER_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_USER_NOT_FOUND);
        // The critical guard: a missing record MUST NOT reach the delete call.
        verify(userSecurityRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteUser: happy path reads THEN deletes exactly once; echoes user without password")
    void deleteUserHappyPathReadsThenDeletesExactlyOnce() {
        UserSecurity existing = existingUser();
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existing));

        UserSecurityDto response = userDeleteService.deleteUser(EXISTING_USER_ID);

        // Read-guard-then-delete ORDER and COUNTS (COUSR03C L188-191): READ-USER-SEC-FILE precedes
        // DELETE-USER-SEC-FILE, each performed exactly once. InOrder proves the keyed read happens
        // BEFORE the delete; verifyNoMoreInteractions confirms nothing else touched the repository.
        InOrder inOrder = inOrder(userSecurityRepository);
        inOrder.verify(userSecurityRepository, times(1)).findBySecUsrId(EXISTING_USER_ID);
        inOrder.verify(userSecurityRepository, times(1)).delete(deletedCaptor.capture());
        verifyNoMoreInteractions(userSecurityRepository);

        // delete(entity) operates on the SAME managed instance returned by the keyed read — the COBOL
        // re-used the record it READ ... UPDATE before issuing DELETE DATASET('USRSEC') (delete-by-entity,
        // NOT deleteById).
        assertThat(deletedCaptor.getValue()).isSameAs(existing);

        // Success echo (the COBOL "User <id> has been deleted ..." outcome, COUSR03C L314-322): the
        // populated DTO conveys the deleted user's identity/name/role, and NEVER the credential (delete
        // reads no password; the DTO password is WRITE_ONLY).
        assertThat(response.getUserId()).isEqualTo(EXISTING_USER_ID);
        assertThat(response.getFirstName()).isEqualTo(EXISTING_FIRST_NAME);
        assertThat(response.getLastName()).isEqualTo(EXISTING_LAST_NAME);
        assertThat(response.getUserType()).isEqualTo(UserType.ADMIN);
        assertThat(response.getPassword()).isNull();
    }

    @Test
    @DisplayName("deleteUser: the 8-byte user id key is trimmed for the keyed read, then deletes that record")
    void deleteUserTrimsUserIdForKeyedRead() {
        // MOVE USRIDINI TO SEC-USR-ID + READ-USER-SEC-FILE then DELETE-USER-SEC-FILE (COUSR03C L188-191):
        // the canonical VSAM key is the trimmed id, and the delete targets the record that read returned.
        UserSecurity existing = existingUser();
        when(userSecurityRepository.findBySecUsrId(EXISTING_USER_ID)).thenReturn(Optional.of(existing));

        userDeleteService.deleteUser("  " + EXISTING_USER_ID + "  "); // "  USER0003  "

        verify(userSecurityRepository).findBySecUsrId(EXISTING_USER_ID);
        verify(userSecurityRepository).delete(existing);
    }
}
