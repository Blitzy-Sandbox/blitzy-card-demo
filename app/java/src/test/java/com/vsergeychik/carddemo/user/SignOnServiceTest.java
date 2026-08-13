package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SignOnService.CursorField;
import com.vsergeychik.carddemo.user.SignOnService.MapInputArea;
import com.vsergeychik.carddemo.user.SignOnService.ReceiveOutcome;
import com.vsergeychik.carddemo.user.SignOnService.SignOnInput;
import com.vsergeychik.carddemo.user.SignOnService.SignOnOutcome;
import com.vsergeychik.carddemo.user.SignOnService.Termination;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SignOnService} against {@code app/cbl/COSGN00C.cbl} - the decision-logic test of the sign-on
 * transaction {@code CC00}.
 *
 * <p>The service is exercised as a plain object with a stubbed repository: no Spring context, no
 * {@code MockMvc}, no servlet and no {@code JobLauncher}. That is the point of putting the program's
 * decisions in a service, and it is what makes every branch below reachable. The HTTP surface is
 * somebody else's test; nothing here constructs a request, a response or a filter.
 *
 * <p>Eight paths exist through the program and each one is asserted whole - message, error flag, cursor,
 * paint flags, termination and the returned communication area - rather than one field at a time, because
 * a path that produces the right message with the wrong error flag is still a parity failure.
 *
 * <h2>The reference sources every expectation below was read from</h2>
 *
 * <p>These files are the parity contract. They are <strong>read</strong> and never written - not one byte
 * of {@code app/cbl}, {@code app/cpy}, {@code app/jcl} or {@code app/csd} is modified by this work,
 * because they are the only oracle available for behavioural equivalence and editing them would destroy
 * it.
 *
 * <ul>
 *   <li>{@code app/cbl/COSGN00C.cbl:35-46} - the {@code WS-VARIABLES} literals and the program's only
 *       two condition names, {@code 88 ERR-FLG-ON VALUE 'Y'} and {@code 88 ERR-FLG-OFF VALUE 'N'}.
 *       Asserted by {@code WiringAndLiterals} and, for both states of both condition names, by
 *       {@code GoverningPracticeGuards}.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl:117-130} - the ordered {@code EVALUATE TRUE} that validates the two
 *       screen fields. First match wins and {@code WHEN OTHER} is a bare {@code CONTINUE}, so all three
 *       arms are driven by {@code ValidationArms}, the ordering included.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl:132-137} - the two unconditional {@code MOVE FUNCTION UPPER-CASE}
 *       statements. They sit <em>after</em> {@code END-EVALUATE} and <em>before</em> {@code :138}'s
 *       {@code IF NOT ERR-FLG-ON}, so they run on the validation-failure paths too, which is asserted
 *       rather than optimised away.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl:211-257} - the keyed {@code EXEC CICS READ} and the three-arm
 *       {@code EVALUATE WS-RESP-CD}, including the plaintext comparison at {@code :223} and the
 *       two-way role split at {@code :230-240}. Driven by {@code ReadArms} and
 *       {@code RoleRouting}.</li>
 *   <li>{@code app/cpy/CSUSR01Y.cpy:17-23} - {@code SEC-USER-DATA}: an eight-character key, two
 *       twenty-character names, {@code SEC-USR-PWD PIC X(08)}, a one-character type and
 *       {@code SEC-USR-FILLER PIC X(23)}, eighty bytes in all.</li>
 *   <li>{@code app/cpy/COCOM01Y.cpy:19-31} - {@code CARDDEMO-COMMAREA}, and the
 *       {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} condition name the role split is decided on.</li>
 *   <li>{@code app/jcl/DUSRSECJ.jcl:34-49} - the seed data. See {@link #SEEDED_ADMIN_ID}.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD:88} {@code DEFINE FILE(USRSEC)}, {@code :249-253}
 *       {@code DEFINE PROGRAM(COSGN00C) DESCRIPTION(LOGIN) ... TRANSID(CC00)} and {@code :378-379}
 *       {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)} - the transaction, program and file
 *       bindings, asserted by {@code GoverningPracticeGuards}. The {@code DSNAME} those definitions
 *       carry is deliberately <strong>not</strong> reproduced anywhere in Java: a dataset name is
 *       resolved from configuration, so no test may pin one.</li>
 * </ul>
 *
 * <h2>Where these expected values came from, and why that matters</h2>
 *
 * <p>Every expectation in this class is <strong>statically derived</strong> by reading the COBOL lines
 * cited above and cross-checking them against the copybook byte layouts, the JCL record contracts and
 * the CSD definitions. None of it was captured by running the legacy program, because <em>the legacy
 * program cannot be run in this environment</em>: there is no z/OS runtime and no CICS emulator; the
 * available COBOL compiler reports its indexed file handler as disabled; no Language Environment
 * {@code CEE*} services exist; and {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are absent from
 * the repository altogether. Those blockers are recorded and individually evidenced in the migration
 * plan, together with the decision to substitute a static-derivation baseline for an execution-captured
 * one, and the substitution is flagged there as the plan's highest-severity open risk.
 *
 * <p>The consequence for a reader of this file is concrete and worth stating plainly: a statically
 * derived expectation can encode a <em>misreading</em> of the COBOL, where a captured one cannot. That
 * is why every assertion here carries the source line it was read from. If one of them is ever found to
 * be wrong, the citation is what makes the error locatable in seconds rather than arguable.
 *
 * <h2>The password comparison is plaintext, and this test pins it that way on purpose</h2>
 *
 * <p>{@code app/cbl/COSGN00C.cbl:223} is one statement - {@code IF SEC-USR-PWD = WS-USER-PWD} - over two
 * {@code PIC X(08)} fields, and {@code app/jcl/DUSRSECJ.jcl:35-44} seeds all ten of them in the clear.
 * {@code ReadArms} asserts that comparison happens on the clear text, and {@code GoverningPracticeGuards}
 * asserts the <em>absence</em> of everything that would change it: no hash, no salt, no key-derivation
 * function, no {@code PasswordEncoder}, no BCrypt, no token, no JWT and no Spring Security filter chain
 * anywhere in the collaborator graph.
 *
 * <p>Both halves of that are deliberate, and the second is the one that is easy to get wrong. Adding
 * hashing here would not be a hardening - it would be a <em>behaviour change</em>: a hashed comparison
 * rejects every record the loader seeds, so the transaction would stop working, and Spring Security is
 * in any case a named exclusion from this migration's closed dependency set. So the plaintext comparison
 * is recorded as what it is - <strong>an inherited property of the legacy design, and an explicit
 * non-goal of this migration to change</strong> - asserted in a test rather than left as an unremarked
 * absence, so that the characteristic stays visible to whoever reads or operates the result instead of
 * being buried in generated code. Closing it properly is a separate, deliberate decision taken with the
 * {@code USRSEC} dataset and its loader in scope; it is not a side effect of a translation.
 *
 * <p>Nothing here weakens the posture either. No real credential appears in this source: the two
 * passwords used are the seeded literal from the loader and an obviously synthetic value, and the tests
 * that exercise a leak path assert that neither the input's nor the outcome's rendering discloses one.
 *
 * <h2>{@code DFHATTR} is copied out, so nothing here asserts anything about it</h2>
 *
 * <p>{@code app/cbl/COSGN00C.cbl:59} reads {@code *COPY DFHATTR.} - an asterisk in column 7, which in
 * fixed-format COBOL is a <strong>comment</strong>. The copy therefore never happens and
 * {@code DFHATTR} contributes nothing to this program at compile time, unlike {@code COPY DFHAID.} at
 * {@code :57} and {@code COPY DFHBMSCA.} at {@code :58}, which are live. The migration plan's tally of
 * "{@code DFHATTR}: 2 consumers" is a count of textual occurrences and includes commented lines, so it
 * overstates the real figure; the discrepancy is documented here rather than silently corrected, and no
 * assertion in this class depends on a {@code DFHATTR} attribute constant. {@link CicsAid} is used
 * because {@code DFHAID} genuinely is copied.
 *
 * <h2>Determinism: there is no clock, and no fixed one is needed</h2>
 *
 * <p>{@link SignOnService} declares exactly one instance field, its repository. No clock, no
 * {@code DateHeader}, no {@code java.time} type and no source of randomness reaches any decision -
 * {@code POPULATE-HEADER-INFO} at {@code app/cbl/COSGN00C.cbl:177-204} is the only part of the program
 * that reads {@code FUNCTION CURRENT-DATE}, and it is presentation that belongs to the controller and
 * its response DTO. So no fixed {@link java.time.Clock} has to be injected here; instead
 * {@code GoverningPracticeGuards} <em>asserts</em> that no time or random collaborator exists, which is
 * the stronger statement, and asserts that a repeated identical invocation yields an equal outcome. No
 * test below depends on execution order, and the suite runs non-interactively.
 *
 * <h2>Test stack, imports and state</h2>
 *
 * <p>JUnit Jupiter, Mockito and AssertJ only, all at the versions the parent BOM manages - no other
 * library is imported and none is needed. Every import is explicit: there is no wildcard import
 * anywhere, so each dependency stays auditable at a glance. The service and its stub are rebuilt in
 * {@link #setUp()} for every test, there is no static mutable state, and the only way anything is
 * injected is through the constructor.
 *
 * <h2>No project rules were supplied</h2>
 *
 * <p>The rules document for this migration contains a single line, "No user rules provided.", and that
 * is the whole of it. Their absence is not treated as licence to relax anything. The enterprise
 * practices the migration plan substitutes for them govern instead, and the ones that bear on this file
 * are each cited above: preserve odd behaviour rather than tidying it, neither weaken nor unrequestedly
 * strengthen the security posture, document conflicts instead of silently correcting them, stay
 * deterministic and non-interactive, prefer the explicit to the implicit, hold no static mutable state,
 * keep the test stack closed and version-pinned, treat the reference inputs as immutable, and document
 * environmental limits rather than absorbing them.
 *
 * @see SignOnService
 * @see SecUserRepository
 */
@DisplayName("SignOnService - the COSGN00C sign-on transaction")
class SignOnServiceTest {

    /** A single-byte code page, named explicitly rather than taken from the platform. */
    private static final Charset CODE_PAGE = StandardCharsets.US_ASCII;

    // =============================================================================================
    // The seed data, taken from app/jcl/DUSRSECJ.jcl rather than invented.
    //
    // There is NO USRSEC fixture file: app/data/ASCII holds nine files and none of them is a
    // security-user dataset. The seed lives in-stream in the loader instead - //SYSUT1 DD * at
    // DUSRSECJ.jcl:34, followed by ten records on :35-44. Each record is exactly 57 characters, and
    // //SYSUT2 at :46-49 writes them at LRECL=80: the loader omits SEC-USR-FILLER PIC X(23), so the
    // records are right-padded 57 -> 80 on the way in, and 57 + 23 = 80 confirms the arithmetic.
    //
    // The values below are the first administrator record (:35) and the first regular-user record
    // (:40), reproduced field for field so that every stubbed record in this class traces to a real
    // seeded row rather than to a name somebody made up. SecUserRecord.of applies the PIC X move rule,
    // so the eight- and twenty-character widths are reached by padding rather than being written out.
    // =============================================================================================

    /** {@code SEC-USR-ID} of the first seeded administrator, {@code app/jcl/DUSRSECJ.jcl:35}. */
    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    /** {@code SEC-USR-FNAME} of that record, {@code app/jcl/DUSRSECJ.jcl:35}. */
    private static final String SEEDED_ADMIN_FNAME = "MARGARET";

    /** {@code SEC-USR-LNAME} of that record, {@code app/jcl/DUSRSECJ.jcl:35}. */
    private static final String SEEDED_ADMIN_LNAME = "GOLD";

    /** {@code SEC-USR-ID} of the first seeded regular user, {@code app/jcl/DUSRSECJ.jcl:40}. */
    private static final String SEEDED_USER_ID = "USER0001";

    /** {@code SEC-USR-FNAME} of that record, {@code app/jcl/DUSRSECJ.jcl:40}. */
    private static final String SEEDED_USER_FNAME = "LAWRENCE";

    /** {@code SEC-USR-LNAME} of that record, {@code app/jcl/DUSRSECJ.jcl:40}. */
    private static final String SEEDED_USER_LNAME = "THOMAS";

    /**
     * The {@code SEC-USR-PWD} every one of the ten seeded records carries,
     * {@code app/jcl/DUSRSECJ.jcl:35-44}.
     *
     * <p>Eight characters, so it fills {@code PIC X(08)} exactly with no padding - which is why a
     * mismatch test has to supply a deliberately different value rather than relying on a width
     * difference. It is a shipped demonstration literal in a public sample repository, not a credential
     * for anything, and it is written here because the comparison at
     * {@code app/cbl/COSGN00C.cbl:223} is on the clear text and a test that hid the value could not
     * assert that.
     */
    private static final String STORED_PASSWORD = "PASSWORD";

    /**
     * An obviously synthetic eight-character value that is <em>not</em> the seeded password.
     *
     * <p>Used wherever a mismatch has to be provoked. Already upper case, so
     * {@code app/cbl/COSGN00C.cbl:135-136}'s fold leaves it unchanged and the test is measuring the
     * comparison rather than the normalisation.
     */
    private static final String WRONG_PASSWORD = "N0TTHEPW";

    /** A byte that is not an attention identifier at all, let alone a mapped one. */
    private static final byte NOT_AN_AID = (byte) 0x00;

    private SecUserRepository repository;

    private SignOnService service;

    @BeforeEach
    void setUp() {
        repository = mock(SecUserRepository.class);
        service = new SignOnService(repository);
    }

    // =============================================================================================
    // Helpers.
    // =============================================================================================

    /**
     * A {@code USRSEC} record for the stub to return, built the way the loader builds one.
     *
     * <p>The two name fields are the seeded values for whichever of the two example records the user id
     * matches, so a record that appears in an assertion failure can be traced straight back to a line in
     * {@code app/jcl/DUSRSECJ.jcl}. Neither name is ever read by {@code COSGN00C} - the program consults
     * {@code SEC-USR-PWD} at {@code :223} and {@code SEC-USR-TYPE} at {@code :227} and nothing else - so
     * they are evidence rather than input, and carrying the real ones costs nothing.
     *
     * <p>The charset is passed explicitly. {@link SecUserRecord#of} needs one to apply the
     * {@code PIC X} move rule, and the platform default is never the right answer for mainframe data
     * even when, as here, the values are plain upper-case Latin.
     *
     * @param userId   {@code SEC-USR-ID}; padded to eight characters by the codec
     * @param password {@code SEC-USR-PWD}; padded or right-truncated to eight
     * @param userType {@code SEC-USR-TYPE}; padded or right-truncated to one
     * @return the record, every field at its declared width
     */
    private static SecUserRecord storedUser(String userId, String password, String userType) {
        boolean admin = SEEDED_ADMIN_ID.equals(userId);
        return SecUserRecord.of(userId,
                admin ? SEEDED_ADMIN_FNAME : SEEDED_USER_FNAME,
                admin ? SEEDED_ADMIN_LNAME : SEEDED_USER_LNAME,
                password,
                userType,
                CODE_PAGE);
    }

    private static SignOnInput enterKey(String userId, String password) {
        return new SignOnInput(NavigationContext.empty(), CicsAid.DFHENTER, userId, password);
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    /** The 80-character {@code WS-MESSAGE} image a {@code PIC X(50)} or shorter literal produces. */
    private static String messageImage(String literal) {
        return literal + spaces(SignOnService.MESSAGE_LENGTH - literal.length());
    }

    private void stubRead(ReadResult result) {
        when(repository.read(anyString())).thenReturn(result);
    }

    // =============================================================================================
    // Wiring and the WS-VARIABLES literals.
    // =============================================================================================

    @Nested
    @DisplayName("Wiring and the WS-VARIABLES literals, COSGN00C:35-46")
    class WiringAndLiterals {

        @Test
        @DisplayName("the repository is required, and is the only collaborator")
        void repositoryIsRequired() {
            Assertions.assertThatThrownBy(() -> new SignOnService(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("USRSEC");
            Assertions.assertThat(service.secUserRepository()).isSameAs(repository);
        }

        @Test
        @DisplayName("the file name matches the repository's, trailing spaces included")
        void fileNameAgreesWithTheRepository() {
            Assertions.assertThat(SignOnService.USRSEC_FILE_NAME)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE)
                    .hasSize(SecUserRepository.CICS_FILE_NAME_LENGTH)
                    .isEqualTo("USRSEC  ");
        }

        @Test
        @DisplayName("the identifiers and widths come from the copybooks")
        void identifiersAndWidths() {
            Assertions.assertThat(SignOnService.PROGRAM_NAME).isEqualTo("COSGN00C")
                    .hasSize(NavigationContext.FROM_PROGRAM_LENGTH);
            Assertions.assertThat(SignOnService.TRANSACTION_ID).isEqualTo("CC00")
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);
            Assertions.assertThat(SignOnService.MAPSET_NAME).isEqualTo("COSGN00");
            Assertions.assertThat(SignOnService.MAP_NAME).isEqualTo("COSGN0A");
            Assertions.assertThat(SignOnService.ADMIN_PROGRAM).isEqualTo("COADM01C")
                    .hasSize(SignOnService.NEXT_PROGRAM_LENGTH);
            Assertions.assertThat(SignOnService.USER_PROGRAM).isEqualTo("COMEN01C")
                    .hasSize(SignOnService.NEXT_PROGRAM_LENGTH);
            Assertions.assertThat(SignOnService.MESSAGE_LENGTH).isEqualTo(80);
            Assertions.assertThat(SignOnService.USER_ID_LENGTH).isEqualTo(SecUserRecord.KEY_LENGTH);
            Assertions.assertThat(SignOnService.PASSWORD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            Assertions.assertThat(SignOnService.ROLE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
            Assertions.assertThat(SignOnService.ERR_FLG_ON).isEqualTo("Y");
            Assertions.assertThat(SignOnService.ERR_FLG_OFF).isEqualTo("N");
            Assertions.assertThat(SignOnService.CURSOR_POSITION).isEqualTo(-1);
        }

        @Test
        @DisplayName("the two response arms are the raw literals 0 and 13 the source compares")
        void responseArmsAreTheRawLiterals() {
            // Two spellings of one thing, and this program uses the blunter one. COSGN00C:222 and :247
            // are literally WHEN 0 and WHEN 13 - bare numbers, with no DFHRESP() around them - whereas
            // the sibling user-maintenance programs write the same two conditions symbolically:
            // COUSR02C:334 and COUSR03C:281 are WHEN DFHRESP(NORMAL), COUSR02C:340 and COUSR03C:287 are
            // WHEN DFHRESP(NOTFND). The values are identical because DFHRESP(NORMAL) expands to 0 and
            // DFHRESP(NOTFND) to 13; only the notation differs, and a reader comparing the two programs
            // side by side should not have to rediscover that.
            //
            // FileStatus is where the two spellings are reconciled, so the assertion is made against it
            // rather than against a number written out again here: the raw literal is pinned on the left
            // to show the source text, and the named constant on the right to show they agree.
            Assertions.assertThat(SignOnService.RESP_NORMAL).isEqualTo(0).isEqualTo(FileStatus.NORMAL);
            Assertions.assertThat(SignOnService.RESP_NOTFND).isEqualTo(13).isEqualTo(FileStatus.NOTFND);

            // Every read below is therefore classified through FileStatus.Outcome rather than through a
            // number, and the third arm is genuinely open-ended: WHEN OTHER at :252 enumerates nothing,
            // so it has to absorb every classification that is neither of these two.
            Assertions.assertThat(FileStatus.outcomeOfCicsResp(SignOnService.RESP_NORMAL))
                    .isEqualTo(FileStatus.Outcome.OK);
            Assertions.assertThat(FileStatus.outcomeOfCicsResp(SignOnService.RESP_NOTFND))
                    .isEqualTo(FileStatus.Outcome.NOT_FOUND);
        }

        @Test
        @DisplayName("the CSD binds transaction CC00 to program COSGN00C and names the USRSEC file")
        void theCsdBindingsAgreeWithTheConstants() {
            // app/csd/CARDDEMO.CSD:249-253 defines PROGRAM(COSGN00C) DESCRIPTION(LOGIN) with
            // TRANSID(CC00), and :378-379 defines TRANSACTION(CC00) with PROGRAM(COSGN00C) - the binding
            // is stated in both directions there, and both directions are checked here.
            Assertions.assertThat(SignOnService.PROGRAM_NAME).isEqualTo("COSGN00C");
            Assertions.assertThat(SignOnService.TRANSACTION_ID).isEqualTo("CC00");

            // :88 defines FILE(USRSEC) with READ(YES) and UPDATEMODEL(LOCKING). The CICS file name is
            // what the program names at :212 and it is asserted here; the DSNAME the same definition
            // carries is deliberately NOT written anywhere in Java, because a dataset name is resolved
            // from configuration and pinning one in a test would defeat that.
            Assertions.assertThat(SignOnService.USRSEC_FILE_NAME)
                    .startsWith(SecUserRepository.CICS_FILE_NAME)
                    .isEqualTo(SecUserRepository.CICS_FILE_NAME_IMAGE);
            Assertions.assertThat(SecUserRepository.CICS_FILE_NAME).isEqualTo("USRSEC");
        }
    }

    // =============================================================================================
    // The five message literals.
    // =============================================================================================

    @Nested
    @DisplayName("The five message literals, byte for byte")
    class MessageLiterals {

        @Test
        @DisplayName("each literal is exactly what the source writes, ellipsis spacing included")
        void literalsAreByteExact() {
            Assertions.assertThat(SignOnService.MSG_ENTER_USER_ID)
                    .isEqualTo("Please enter User ID ...").hasSize(24);
            Assertions.assertThat(SignOnService.MSG_ENTER_PASSWORD)
                    .isEqualTo("Please enter Password ...").hasSize(25);
            Assertions.assertThat(SignOnService.MSG_WRONG_PASSWORD)
                    .isEqualTo("Wrong Password. Try again ...").hasSize(29);
            Assertions.assertThat(SignOnService.MSG_USER_NOT_FOUND)
                    .isEqualTo("User not found. Try again ...").hasSize(29);
            Assertions.assertThat(SignOnService.MSG_UNABLE_TO_VERIFY)
                    .isEqualTo("Unable to verify the User ...").hasSize(29);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Please enter User ID ...", "Please enter Password ...",
                "Wrong Password. Try again ...", "User not found. Try again ...",
                "Unable to verify the User ..."})
        @DisplayName("every literal has a space before its ellipsis and no double space")
        void everyLiteralEndsWithSpaceThenEllipsis(String literal) {
            Assertions.assertThat(literal).endsWith(" ...").doesNotContain("  ");
        }

        @Test
        @DisplayName("the thank-you and invalid-key texts are the CSMSG01Y ones, not redeclared here")
        void copybookMessagesComeFromSystemMessages() {
            Assertions.assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .startsWith("Thank you for using CardDemo application...");
            Assertions.assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .startsWith("Invalid key pressed. Please see below...");
        }
    }

    // =============================================================================================
    // MAIN-PARA :80-83 - the EIBCALEN = 0 path.
    // =============================================================================================

    @Nested
    @DisplayName("First entry with no communication area, COSGN00C:80-83")
    class FirstEntry {

        @Test
        @DisplayName("paints the screen, clears the output fields, puts the cursor on the user id")
        void paintsTheSignonScreen() {
            SignOnOutcome outcome = service.handle(
                    SignOnInput.withoutCommarea(CicsAid.DFHENTER, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isTrue();
            Assertions.assertThat(outcome.plainTextSent()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.message()).isEqualTo(spaces(SignOnService.MESSAGE_LENGTH));
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.hasNextProgram()).isFalse();
            Assertions.assertThat(outcome.nextProgram())
                    .isEqualTo(spaces(SignOnService.NEXT_PROGRAM_LENGTH));
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.returnTransid())
                    .contains(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(outcome.navigationContext()).isEqualTo(NavigationContext.empty());
            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
        }

        @Test
        @DisplayName("validates nothing and reads nothing, whatever the fields hold")
        void neitherValidatesNorReads() {
            SignOnOutcome blankFields =
                    service.handle(SignOnInput.withoutCommarea(CicsAid.DFHENTER, null, null));

            Assertions.assertThat(blankFields.message())
                    .isEqualTo(spaces(SignOnService.MESSAGE_LENGTH));
            Assertions.assertThat(blankFields.errorFlag()).isFalse();
            verifyNoInteractions(repository);
        }

        @ParameterizedTest
        @ValueSource(bytes = {125, -13, 0, -3})
        @DisplayName("diverts before the EVALUATE, so the attention identifier is immaterial")
        void divertsWhateverTheKey(byte eibAid) {
            SignOnOutcome outcome =
                    service.handle(SignOnInput.withoutCommarea(eibAid, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.resetAllOutputFields()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("an absent area is EIBCALEN = 0; an initialised area is not")
        void absenceIsDistinctFromAnInitialisedArea() {
            Assertions.assertThat(
                    SignOnInput.withoutCommarea(CicsAid.DFHENTER, "A", "B").isCommareaPresent())
                    .isFalse();
            Assertions.assertThat(enterKey("A", "B").isCommareaPresent()).isTrue();
        }
    }

    // =============================================================================================
    // MAIN-PARA :85-95 - EVALUATE EIBAID.
    // =============================================================================================

    @Nested
    @DisplayName("EVALUATE EIBAID, COSGN00C:85-95")
    class AttentionIdentifierArms {

        @Test
        @DisplayName("PF3 sends plain text, ends the conversation and sets no error flag")
        void pf3SignsOff() {
            NavigationContext inbound = NavigationContext.empty().withUserId(SEEDED_ADMIN_ID);

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHPF3, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.plainTextSent()).isTrue();
            Assertions.assertThat(outcome.screenPainted()).isFalse();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SystemMessages.CCDA_MSG_THANK_YOU))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_NO_TRANSID);
            Assertions.assertThat(outcome.termination().conversationContinues()).isFalse();
            Assertions.assertThat(outcome.returnTransid()).isEmpty();
            Assertions.assertThat(outcome.navigationContext()).isEqualTo(inbound);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            Assertions.assertThat(outcome.resolvedAid()).contains(PfKeyResolver.AidKey.PFK03);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("PF4 - a mapped key the program does not handle - takes WHEN OTHER")
        void aMappedButUnhandledKeyIsInvalid() {
            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF4, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).contains(PfKeyResolver.AidKey.PFK04);
        }

        @Test
        @DisplayName("PF15 resolves to PFK03 like PF3 but is still invalid - the token is not the "
                + "dispatcher")
        void pf15DoesNotBehaveAsPf3() {
            Assertions.assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .contains(PfKeyResolver.AidKey.PFK03);

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF15, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.plainTextSent()).isFalse();
            Assertions.assertThat(outcome.resolvedAid()).contains(PfKeyResolver.AidKey.PFK03);
        }

        @Test
        @DisplayName("PA3 - a real AID that CSSTRPFY maps to nothing - takes WHEN OTHER")
        void aResolverNoMatchIsInvalid() {
            Assertions.assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3)).isEmpty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), CicsAid.DFHPA3, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).isEmpty();
        }

        @Test
        @DisplayName("a byte that is no attention identifier at all takes WHEN OTHER too")
        void anUnknownByteIsInvalid() {
            Assertions.assertThat(PfKeyResolver.resolve(NOT_AN_AID)).isEmpty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(NavigationContext.empty(), NOT_AN_AID, SEEDED_ADMIN_ID,
                            STORED_PASSWORD));

            assertInvalidKey(outcome);
            Assertions.assertThat(outcome.resolvedAid()).isEmpty();
        }

        @Test
        @DisplayName("CLEAR and PA1 are invalid keys here as well")
        void clearAndPa1AreInvalid() {
            assertInvalidKey(service.handle(new SignOnInput(NavigationContext.empty(),
                    CicsAid.DFHCLEAR, SEEDED_ADMIN_ID, STORED_PASSWORD)));
            assertInvalidKey(service.handle(new SignOnInput(NavigationContext.empty(),
                    CicsAid.DFHPA1, SEEDED_ADMIN_ID, STORED_PASSWORD)));
        }

        private void assertInvalidKey(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SystemMessages.CCDA_MSG_INVALID_KEY))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            // :91-94 contains no MOVE -1, so no field is repositioned on this arm.
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.cursorField().isPositioned()).isFalse();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.NOT_PERFORMED);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY :117-130 - the ordered EVALUATE TRUE.
    // =============================================================================================

    @Nested
    @DisplayName("PROCESS-ENTER-KEY validation, COSGN00C:117-130")
    class ValidationArms {

        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "   "})
        @DisplayName("an all-spaces user id asks for the user id")
        void spacesUserId(String userId) {
            assertBlankUserId(service.handle(enterKey(userId, STORED_PASSWORD)));
        }

        @Test
        @DisplayName("a user id the terminal never transmitted is LOW-VALUES and asks for the user id")
        void lowValuesUserId() {
            assertBlankUserId(service.handle(enterKey(null, STORED_PASSWORD)));
            assertBlankUserId(service.handle(
                    enterKey(lowValues(SignOnService.USER_ID_LENGTH), STORED_PASSWORD)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"        ", "", "  "})
        @DisplayName("an all-spaces password asks for the password")
        void spacesPassword(String password) {
            assertBlankPassword(service.handle(enterKey(SEEDED_ADMIN_ID, password)));
        }

        @Test
        @DisplayName("a password the terminal never transmitted is LOW-VALUES and asks for the password")
        void lowValuesPassword() {
            assertBlankPassword(service.handle(enterKey(SEEDED_ADMIN_ID, null)));
            assertBlankPassword(service.handle(
                    enterKey(SEEDED_ADMIN_ID, lowValues(SignOnService.PASSWORD_LENGTH))));
        }

        @Test
        @DisplayName("EVALUATE stops at the first match, so both blank asks for the user id only")
        void bothBlankAsksForTheUserIdFirst() {
            assertBlankUserId(service.handle(enterKey(null, null)));
            assertBlankUserId(service.handle(enterKey("   ", "   ")));
        }

        @Test
        @DisplayName("a field mixing low-values and spaces satisfies neither test and is used as data")
        void aMixedFieldIsData() {
            stubRead(ReadResult.notFound());
            String mixed = lowValues(4) + spaces(4);

            SignOnOutcome outcome = service.handle(enterKey(mixed, STORED_PASSWORD));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_USER_NOT_FOUND));
            verify(repository).read(mixed);
        }

        @Test
        @DisplayName("the uppercase normalisation runs on the blank-password path, and the read does "
                + "not - COSGN00C:130-140")
        void normalisationHappensEvenWhenValidationFailed() {
            SignOnOutcome outcome = service.handle(enterKey("adm1", "   "));

            // :132-134 ran although :123-127 had already flagged the error and painted the screen.
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo("ADM1    ");
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD));
            // :138 gated the read on the error flag.
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("the normalisation runs on the blank-user-id path too, storing LOW-VALUES")
        void normalisationOnTheBlankUserIdPath() {
            SignOnOutcome fromNull = service.handle(enterKey(null, STORED_PASSWORD));
            SignOnOutcome fromSpaces = service.handle(enterKey("", STORED_PASSWORD));

            Assertions.assertThat(fromNull.navigationContext().userId())
                    .isEqualTo(lowValues(SignOnService.USER_ID_LENGTH));
            Assertions.assertThat(fromSpaces.navigationContext().userId())
                    .isEqualTo(spaces(SignOnService.USER_ID_LENGTH));
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("neither validation arm touches the rest of the communication area")
        void validationLeavesTheRestOfTheAreaAlone() {
            NavigationContext inbound = NavigationContext.empty()
                    .withFromProgram("COMEN01C")
                    .withUserType(NavigationContext.USER_TYPE_ADMIN)
                    .withPgmReenter()
                    .withAcctId(12345678901L);

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHENTER, "", STORED_PASSWORD));

            Assertions.assertThat(outcome.navigationContext())
                    .isEqualTo(inbound.withUserId(spaces(SignOnService.USER_ID_LENGTH)));
            Assertions.assertThat(outcome.navigationContext().fromProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.navigationContext().acctId()).isEqualTo(12345678901L);
            Assertions.assertThat(outcome.navigationContext().isReenter()).isTrue();
            // The role on the outcome is blank because no sign-on established one, even though the
            // inbound area happened to carry an administrator type.
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
        }

        @Test
        @DisplayName("WHEN OTHER is a bare CONTINUE, so two present fields fall straight through to "
                + "the read - COSGN00C:128-129")
        void bothFieldsPresentFallThroughToTheRead() {
            stubRead(ReadResult.notFound());

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));

            // :128-129 WHEN OTHER CONTINUE does nothing at all: it sets no flag, writes no message and
            // moves no -1. Everything the two failing arms would have produced is therefore absent, and
            // the only reason this outcome carries a message and an error flag at all is the read that
            // followed at :211 - which is exactly what makes this the fall-through arm rather than a
            // third validation arm.
            verify(repository).read(SEEDED_ADMIN_ID);
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.NOT_FOUND);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_USER_NOT_FOUND))
                    .isNotEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID))
                    .isNotEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD));
        }

        @Test
        @DisplayName("the fall-through leaves the error flag off up to the point the read decides it")
        void theFallThroughArmSetsNoErrorFlagOfItsOwn() {
            // Proved by choosing the one read arm that also sets no flag: :241-246, wrong password. If
            // WHEN OTHER had quietly set 'Y' the way the two validation arms do, this outcome could not
            // come back with the flag off, because nothing between :129 and :246 would clear it again.
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, WRONG_PASSWORD));

            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD));
        }

        @Test
        @DisplayName("the RECEIVE ran on this path, and its response is carried untested")
        void theReceiveIsPerformedAndCarried() {
            SignOnOutcome outcome = service.handle(enterKey("", ""));

            Assertions.assertThat(outcome.receive()).isEqualTo(ReceiveOutcome.normal());
            Assertions.assertThat(outcome.receive().performed()).isTrue();
            Assertions.assertThat(outcome.receive().respCode()).isEqualTo(SignOnService.RESP_NORMAL);
            Assertions.assertThat(outcome.receive().reasonCode())
                    .isEqualTo(FileStatus.NO_REASON_CODE);
        }

        private void assertBlankUserId(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.cursorField().lengthItemName()).contains("USERIDL");
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.resetAllOutputFields()).isFalse();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }

        private void assertBlankPassword(SignOnOutcome outcome) {
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_PASSWORD))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.PASSWORD);
            Assertions.assertThat(outcome.cursorField().lengthItemName()).contains("PASSWDL");
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.readOutcome()).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    // =============================================================================================
    // READ-USER-SEC-FILE :211-257 - the three-arm response split.
    // =============================================================================================

    @Nested
    @DisplayName("READ-USER-SEC-FILE, COSGN00C:211-257")
    class ReadArms {

        @Test
        @DisplayName("WHEN 0 with a matching password signs an administrator on to COADM01C")
        void administratorSignsOn() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey("admin001", STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.XCTL);
            Assertions.assertThat(outcome.termination().conversationContinues()).isFalse();
            Assertions.assertThat(outcome.returnTransid()).isEmpty();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COADM01C");
            Assertions.assertThat(outcome.hasNextProgram()).isTrue();
            Assertions.assertThat(outcome.role()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            Assertions.assertThat(outcome.isAdminRole()).isTrue();
            Assertions.assertThat(outcome.message()).isEqualTo(spaces(SignOnService.MESSAGE_LENGTH));
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.NONE);
            Assertions.assertThat(outcome.screenPainted()).isFalse();
            Assertions.assertThat(outcome.plainTextSent()).isFalse();
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.OK);

            // :224-228, the five moves into the communication area.
            NavigationContext area = outcome.navigationContext();
            Assertions.assertThat(area.fromTranid()).isEqualTo(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(area.fromProgram()).isEqualTo(SignOnService.PROGRAM_NAME);
            Assertions.assertThat(area.userId()).isEqualTo(SEEDED_ADMIN_ID);
            Assertions.assertThat(area.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN);
            Assertions.assertThat(area.isAdmin()).isTrue();
            Assertions.assertThat(area.isUser()).isFalse();
            Assertions.assertThat(area.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            Assertions.assertThat(area.isEnter()).isTrue();
            Assertions.assertThat(area.isReenter()).isFalse();
        }

        @Test
        @DisplayName("a regular user signs on to COMEN01C")
        void regularUserSignsOn() {
            stubRead(ReadResult.found(storedUser(SEEDED_USER_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.role()).isEqualTo(NavigationContext.USER_TYPE_USER);
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
            Assertions.assertThat(outcome.navigationContext().isUser()).isTrue();
            Assertions.assertThat(outcome.navigationContext().isAdmin()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "X", "a", "0"})
        @DisplayName("any user type but 'A' takes the ELSE branch to COMEN01C - a two-way split")
        void anyNonAdminTypeGoesToTheUserMenu(String userType) {
            stubRead(ReadResult.found(storedUser(SEEDED_USER_ID, STORED_PASSWORD, userType)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo("COMEN01C");
            Assertions.assertThat(outcome.role()).isEqualTo(userType);
            Assertions.assertThat(outcome.isAdminRole()).isFalse();
        }

        @Test
        @DisplayName("the comparison is case-insensitive on the submitted side only")
        void caseFoldingAppliesToTheSubmittedSideOnly() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            // :135-136 upper-cases what was typed, so a lower-case submission matches.
            Assertions.assertThat(service.handle(enterKey(SEEDED_ADMIN_ID, "password")).signedOn())
                    .isTrue();
        }

        @ParameterizedTest
        @CsvSource({"admin001,password", "AdMiN001,PaSsWoRd", "ADMIN001,PASSWORD", "aDMIN001,passworD"})
        @DisplayName("however the two fields are typed, the key read is ADMIN001 and the value compared "
                + "is PASSWORD - COSGN00C:132-137")
        void everyCasingNormalisesToTheSameKeyAndTheSameComparand(String typedId, String typedPassword) {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            SignOnOutcome outcome = service.handle(enterKey(typedId, typedPassword));

            // :132-134 folds the user id into WS-USER-ID, which is the RIDFLD at :215, so the key the
            // repository sees is the upper-cased form and never the raw one.
            verify(repository).read(key.capture());
            Assertions.assertThat(key.getValue())
                    .isEqualTo(SEEDED_ADMIN_ID)
                    .isEqualTo(SignOnService.upperCase(typedId))
                    .hasSize(SignOnService.USER_ID_LENGTH);

            // :135-136 folds the password into WS-USER-PWD, so :223 compares the seeded literal against
            // the upper-cased submission and the sign-on succeeds whatever the typist did with shift.
            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo(SEEDED_ADMIN_ID);
        }

        @Test
        @DisplayName("the comparison is on the clear text: the seeded literal matches, a different "
                + "eight-character value does not - COSGN00C:223")
        void theComparisonIsPlaintext() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            // The stored side is the clear text app/jcl/DUSRSECJ.jcl:35 seeds, and it is compared byte
            // for byte with what was typed. Nothing is hashed, encoded or digested on either side, so
            // supplying the clear text is sufficient - which is the whole of the assertion.
            Assertions.assertThat(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)).signedOn())
                    .isTrue();

            // And a genuinely different value of the same width fails. It has to differ in content
            // rather than in length, because both operands are eight characters wide and always will be:
            // SEC-USR-PWD is PIC X(08) at app/cpy/CSUSR01Y.cpy:21 and PASSWDI is PIC X(8) at
            // app/cpy-bms/COSGN00.CPY:78 - two notations for the same width, in two different copybooks.
            SignOnOutcome mismatch = service.handle(enterKey(SEEDED_ADMIN_ID, WRONG_PASSWORD));
            Assertions.assertThat(WRONG_PASSWORD)
                    .hasSameSizeAs(STORED_PASSWORD)
                    .isNotEqualTo(STORED_PASSWORD);
            Assertions.assertThat(mismatch.signedOn()).isFalse();
            Assertions.assertThat(mismatch.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD));
        }

        @Test
        @DisplayName("a ninth character cannot reach the comparison at all, because PASSWDI is "
                + "PIC X(8) - so a trailing '1' is truncated away and the sign-on still succeeds")
        void aNinthPasswordCharacterIsTruncatedBeforeTheComparison() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD + "1"));

            // This is the one case where the obvious expectation is the wrong one, so it is asserted
            // explicitly rather than left to be discovered.
            //
            // "PASSWORD1" is nine characters. PASSWDI OF COSGN0AI is declared PIC X(8) at
            // app/cpy-bms/COSGN00.CPY:78, so a ninth character has nowhere to go: the alphanumeric MOVE
            // rule truncates on the RIGHT, leaving "PASSWORD", and that eight-character image is what
            // :135-136 folds and :223 compares. The sign-on therefore SUCCEEDS.
            //
            // Asserting a non-match here would have been asserting something the COBOL does not do. On a
            // real 3270 the ninth keystroke never even reaches the program - the field holds eight
            // positions - so the truncation is not a shortcut this migration took, it is the terminal
            // contract. The behaviour is pinned as it is and the reasoning recorded, rather than
            // quietly diverging in either direction.
            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.nextProgram()).isEqualTo(SignOnService.ADMIN_PROGRAM);

            // The same rule, stated on its own so the mechanism is visible without inferring it from the
            // outcome above: the receiver's width decides, and it truncates on the right.
            Assertions.assertThat(STORED_PASSWORD + "1")
                    .hasSize(SignOnService.PASSWORD_LENGTH + 1)
                    .startsWith(STORED_PASSWORD);
        }

        @Test
        @DisplayName("a stored lower-case password can never be matched, because it is used verbatim")
        void aStoredLowerCasePasswordNeverMatches() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, "password",
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome submittedLower = service.handle(enterKey(SEEDED_ADMIN_ID, "password"));
            SignOnOutcome submittedUpper = service.handle(enterKey(SEEDED_ADMIN_ID, "PASSWORD"));

            Assertions.assertThat(submittedLower.signedOn()).isFalse();
            Assertions.assertThat(submittedUpper.signedOn()).isFalse();
        }

        @Test
        @DisplayName("trailing spaces are part of the comparison, so a prefix does not match")
        void aPrefixDoesNotMatch() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            Assertions.assertThat(service.handle(enterKey(SEEDED_ADMIN_ID, "PASS")).signedOn()).isFalse();
        }

        @Test
        @DisplayName("WHEN 0 with a wrong password produces a message but NO error flag - :241-246")
        void wrongPasswordDoesNotSetTheErrorFlag() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, "WRONG"));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_WRONG_PASSWORD))
                    .hasSize(SignOnService.MESSAGE_LENGTH);
            // The asymmetry the source has and this class preserves: every other failure sets 'Y'.
            Assertions.assertThat(outcome.errorFlag()).isFalse();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.PASSWORD);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.nextProgram())
                    .isEqualTo(spaces(SignOnService.NEXT_PROGRAM_LENGTH));
            Assertions.assertThat(outcome.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.OK);
            // The area still carries the normalised user id from :132-134, but no role.
            Assertions.assertThat(outcome.navigationContext().userId()).isEqualTo(SEEDED_ADMIN_ID);
            Assertions.assertThat(outcome.navigationContext().userType())
                    .isEqualTo(spaces(SignOnService.ROLE_LENGTH));
        }

        @Test
        @DisplayName("WHEN 13 - the user id is not in USRSEC")
        void notFound() {
            stubRead(ReadResult.notFound());

            SignOnOutcome outcome = service.handle(enterKey("nobody", STORED_PASSWORD));

            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_USER_NOT_FOUND));
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.readOutcome()).contains(FileStatus.Outcome.NOT_FOUND);
        }

        @Test
        @DisplayName("WHEN OTHER covers end-of-file, which is neither 0 nor 13")
        void endOfFileTakesWhenOther() {
            stubRead(ReadResult.endOfFile());

            assertUnableToVerify(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)),
                    FileStatus.Outcome.END_OF_FILE);
        }

        @Test
        @DisplayName("WHEN OTHER covers a duplicate classification too")
        void duplicateTakesWhenOther() {
            stubRead(ReadResult.of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC)));

            assertUnableToVerify(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)),
                    FileStatus.Outcome.DUPLICATE);
        }

        @Test
        @DisplayName("WHEN OTHER covers a backend refusal")
        void aRefusalTakesWhenOther() {
            stubRead(ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, CicsResponse.none()));

            assertUnableToVerify(service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)),
                    FileStatus.Outcome.OTHER);
        }

        @Test
        @DisplayName("the key handed to the repository is the upper-cased eight-character RIDFLD")
        void theKeyIsTheNormalisedRidfld() {
            stubRead(ReadResult.notFound());
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

            service.handle(enterKey("ad", STORED_PASSWORD));

            verify(repository).read(key.capture());
            Assertions.assertThat(key.getValue())
                    .isEqualTo("AD      ")
                    .hasSize(SignOnService.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("a wiring failure propagates rather than becoming 'Unable to verify the User ...'")
        void aWiringFailurePropagates() {
            when(repository.read(anyString()))
                    .thenThrow(new IllegalStateException("USRSEC is unconfigured"));

            Assertions.assertThatThrownBy(() -> service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("USRSEC");
        }

        private void assertUnableToVerify(SignOnOutcome outcome, FileStatus.Outcome expected) {
            Assertions.assertThat(outcome.message())
                    .isEqualTo(messageImage(SignOnService.MSG_UNABLE_TO_VERIFY));
            Assertions.assertThat(outcome.errorFlag()).isTrue();
            Assertions.assertThat(outcome.cursorField()).isEqualTo(CursorField.USER_ID);
            Assertions.assertThat(outcome.screenPainted()).isTrue();
            Assertions.assertThat(outcome.signedOn()).isFalse();
            Assertions.assertThat(outcome.termination()).isEqualTo(Termination.RETURN_TRANSID);
            Assertions.assertThat(outcome.readOutcome()).contains(expected);
        }
    }

    // =============================================================================================
    // :230-240 - the two-way role split, on its own.
    // =============================================================================================

    @Nested
    @DisplayName("resolveNextProgram, COSGN00C:230-240")
    class RoleRouting {

        @Test
        @DisplayName("only an exact 'A' routes to the administrator menu")
        void adminRoutesToAdminMenu() {
            Assertions.assertThat(service.resolveNextProgram(
                    NavigationContext.empty().withUserTypeAdmin()))
                    .isEqualTo(SignOnService.ADMIN_PROGRAM);
        }

        @ParameterizedTest
        @ValueSource(strings = {"U", " ", "a", "Z", "1"})
        @DisplayName("every other value routes to the main menu - no third branch")
        void everythingElseRoutesToTheMainMenu(String userType) {
            Assertions.assertThat(service.resolveNextProgram(
                    NavigationContext.empty().withUserType(userType)))
                    .isEqualTo(SignOnService.USER_PROGRAM)
                    .hasSize(SignOnService.NEXT_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("a blank area - which satisfies neither 88-level - still routes somewhere")
        void aBlankAreaRoutesToTheMainMenu() {
            NavigationContext blank = NavigationContext.empty();
            Assertions.assertThat(blank.isAdmin()).isFalse();
            Assertions.assertThat(blank.isUser()).isFalse();
            Assertions.assertThat(service.resolveNextProgram(blank))
                    .isEqualTo(SignOnService.USER_PROGRAM);
        }

        @Test
        @DisplayName("a communication area is required")
        void areaIsRequired() {
            Assertions.assertThatThrownBy(() -> service.resolveNextProgram(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CDEMO-USER-TYPE");
        }
    }

    // =============================================================================================
    // FUNCTION UPPER-CASE, :132 and :135.
    // =============================================================================================

    @Nested
    @DisplayName("FUNCTION UPPER-CASE, COSGN00C:132 and :135")
    class UpperCase {

        @ParameterizedTest
        @ValueSource(strings = {"admin001", "ADMIN001", "AdMiN001", "aDmIn001"})
        @DisplayName("folds a to z and leaves digits alone")
        void foldsLettersOnly(String value) {
            Assertions.assertThat(SignOnService.upperCase(value)).isEqualTo(SEEDED_ADMIN_ID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"        ", "\u0000\u0000", "12345678", "@[`{|}~", "-_. ,;"})
        @DisplayName("leaves every character outside a to z exactly as it is")
        void leavesOtherCharactersUntouched(String value) {
            Assertions.assertThat(SignOnService.upperCase(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("preserves length, where String.toUpperCase would not")
        void preservesLength() {
            // The sharp s upper-cases to two characters under String.toUpperCase, which would widen an
            // eight-character field to nine and break both the RIDFLD width and CDEMO-USER-ID.
            String sharpS = "stra\u00dfe  ";
            Assertions.assertThat(sharpS).hasSize(SignOnService.USER_ID_LENGTH);
            Assertions.assertThat(SignOnService.upperCase(sharpS))
                    .hasSize(SignOnService.USER_ID_LENGTH)
                    .isEqualTo("STRA\u00dfE  ");
            Assertions.assertThat(sharpS.toUpperCase(java.util.Locale.ROOT))
                    .hasSizeGreaterThan(SignOnService.USER_ID_LENGTH);
        }

        @Test
        @DisplayName("the boundary characters either side of a to z are not folded")
        void boundariesAreExclusive() {
            Assertions.assertThat(SignOnService.upperCase("`")).isEqualTo("`");
            Assertions.assertThat(SignOnService.upperCase("a")).isEqualTo("A");
            Assertions.assertThat(SignOnService.upperCase("z")).isEqualTo("Z");
            Assertions.assertThat(SignOnService.upperCase("{")).isEqualTo("{");
        }

        @Test
        @DisplayName("an empty field is legal and a null one is not")
        void emptyIsLegalNullIsNot() {
            Assertions.assertThat(SignOnService.upperCase("")).isEmpty();
            Assertions.assertThatThrownBy(() -> SignOnService.upperCase(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("UPPER-CASE");
        }

        @Test
        @DisplayName("an over-long submitted user id is truncated on the right, not rejected")
        void anOverLongUserIdIsTruncatedOnTheRight() {
            stubRead(ReadResult.notFound());

            service.handle(enterKey("verylonguserid", STORED_PASSWORD));

            verify(repository).read("VERYLONG");
        }
    }

    // =============================================================================================
    // Statelessness and confidentiality.
    // =============================================================================================

    @Nested
    @DisplayName("Statelessness and confidentiality")
    class StatelessnessAndConfidentiality {

        @Test
        @DisplayName("two successive calls cannot influence one another")
        void noStateSurvivesACall() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome first = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome second = service.handle(enterKey("", ""));
            SignOnOutcome third = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(first.signedOn()).isTrue();
            // The second call sees none of the first call's role, message or user id.
            Assertions.assertThat(second.signedOn()).isFalse();
            Assertions.assertThat(second.role()).isEqualTo(spaces(SignOnService.ROLE_LENGTH));
            Assertions.assertThat(second.message())
                    .isEqualTo(messageImage(SignOnService.MSG_ENTER_USER_ID));
            Assertions.assertThat(second.navigationContext().fromProgram())
                    .isEqualTo(spaces(NavigationContext.FROM_PROGRAM_LENGTH));
            // And the third call reproduces the first exactly, so the second left nothing behind.
            Assertions.assertThat(third).isEqualTo(first);
        }

        @Test
        @DisplayName("the inbound communication area is never mutated")
        void theInboundAreaIsNeverMutated() {
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            NavigationContext inbound = NavigationContext.empty();

            SignOnOutcome outcome = service.handle(
                    new SignOnInput(inbound, CicsAid.DFHENTER, SEEDED_ADMIN_ID, STORED_PASSWORD));

            Assertions.assertThat(inbound).isEqualTo(NavigationContext.empty());
            Assertions.assertThat(outcome.navigationContext()).isNotEqualTo(inbound);
        }

        @Test
        @DisplayName("the input's rendering redacts the password")
        void theInputRedactsThePassword() {
            String fakePassword = "N0TAREAL";

            String rendered = enterKey(SEEDED_ADMIN_ID, fakePassword).toString();

            Assertions.assertThat(rendered)
                    .doesNotContain(fakePassword)
                    .contains(NavigationContext.REDACTED)
                    .contains(SEEDED_ADMIN_ID)
                    .contains("eibAid=0x7D");
            // One line whatever it holds, or a stored control character could append a log entry of the
            // writer's choosing after it.
            Assertions.assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the attention identifier renders as an unsigned byte, not a sign-extended int")
        void theAidRendersUnsigned() {
            // PF3 is 0xF3, which as a Java byte is negative; a sign-extended rendering would show
            // FFFFFFF3 and make a raw byte comparison look wrong to a reader.
            String rendered = new SignOnInput(NavigationContext.empty(), CicsAid.DFHPF3, "A", "B")
                    .toString();

            Assertions.assertThat(rendered).contains("eibAid=0xF3").doesNotContain("FFFFFFF3");
        }

        @Test
        @DisplayName("the outcome carries no password component, and its rendering leaks none")
        void theOutcomeCarriesNoPassword() {
            // Upper case throughout, so that :135-136's fold leaves it matchable and the sign-on
            // actually succeeds - the path on which a leak would be most likely. An obviously synthetic
            // value: no real credential belongs in a test source.
            String fakePassword = "N0TAREAL";
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, fakePassword,
                    NavigationContext.USER_TYPE_ADMIN)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_ADMIN_ID, fakePassword));

            Assertions.assertThat(outcome.signedOn()).isTrue();
            Assertions.assertThat(outcome.toString()).doesNotContain(fakePassword);
            for (RecordComponent component : SignOnOutcome.class.getRecordComponents()) {
                Assertions.assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("pwd")
                        .doesNotContain("password")
                        .doesNotContain("secret")
                        .doesNotContain("credential");
            }
        }

        @Test
        @DisplayName("the service declares no hashing, token or filter-chain collaborator")
        void noSecurityCollaboratorIsIntroduced() {
            Assertions.assertThat(SignOnService.class.getDeclaredFields())
                    .allSatisfy(field -> Assertions.assertThat(field.getType().getName())
                            .doesNotContain("security")
                            .doesNotContain("crypto")
                            .doesNotContain("Encoder")
                            .doesNotContain("Digest"));
        }
    }

    // =============================================================================================
    // The practices this migration substitutes for the absent project rules, asserted rather than
    // asserted-in-prose. Each test below pins one of them on this class's unit under test, so a later
    // change that quietly breaks one fails the build instead of passing review.
    // =============================================================================================

    @Nested
    @DisplayName("Governing-practice guards")
    class GoverningPracticeGuards {

        @Test
        @DisplayName("both states of both condition names are driven, and 'Y' and 'N' are the only two "
                + "images WS-ERR-FLG ever holds - COSGN00C:40-42")
        void bothStatesOfTheOnlyTwoConditionNamesAreDriven() {
            // WS-ERR-FLG PIC X(01) VALUE 'N' carries the program's ONLY two 88-levels: ERR-FLG-ON 'Y' at
            // :41 and ERR-FLG-OFF 'N' at :42. Both have to be reached in both states, so all eight paths
            // are run here and their flag images collected, rather than trusting the per-path assertions
            // elsewhere in this class to have covered the pair between them.
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));

            // ERR-FLG-OFF holds: first entry (:75 sets it and nothing changes it), PF3 (:88-90 sets no
            // flag), the fall-through to a successful sign-on, and the wrong-password arm at :241-246
            // which deliberately omits the MOVE 'Y'.
            SignOnOutcome firstEntry = service.handle(SignOnInput.withoutCommarea(
                    CicsAid.DFHENTER, SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome signOff = service.handle(new SignOnInput(
                    NavigationContext.empty(), CicsAid.DFHPF3, SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome signedOn = service.handle(enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome wrongPassword = service.handle(enterKey(SEEDED_ADMIN_ID, WRONG_PASSWORD));

            Assertions.assertThat(firstEntry.errorFlag()).isFalse();
            Assertions.assertThat(signOff.errorFlag()).isFalse();
            Assertions.assertThat(signedOn.errorFlag()).isFalse();
            Assertions.assertThat(wrongPassword.errorFlag()).isFalse();
            Assertions.assertThat(firstEntry.errFlgImage())
                    .isEqualTo(signOff.errFlgImage())
                    .isEqualTo(signedOn.errFlgImage())
                    .isEqualTo(wrongPassword.errFlgImage())
                    .isEqualTo(SignOnService.ERR_FLG_OFF);

            // ERR-FLG-ON holds: the invalid-key arm at :92, the two validation arms at :119 and :124,
            // and the two failing read arms at :248 and :253.
            SignOnOutcome invalidKey = service.handle(new SignOnInput(
                    NavigationContext.empty(), CicsAid.DFHPF4, SEEDED_ADMIN_ID, STORED_PASSWORD));
            SignOnOutcome blankUserId = service.handle(enterKey("", STORED_PASSWORD));
            SignOnOutcome blankPassword = service.handle(enterKey(SEEDED_ADMIN_ID, ""));

            Assertions.assertThat(invalidKey.errorFlag()).isTrue();
            Assertions.assertThat(blankUserId.errorFlag()).isTrue();
            Assertions.assertThat(blankPassword.errorFlag()).isTrue();

            stubRead(ReadResult.notFound());
            SignOnOutcome notFound = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));
            stubRead(ReadResult.endOfFile());
            SignOnOutcome unableToVerify = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            Assertions.assertThat(notFound.errorFlag()).isTrue();
            Assertions.assertThat(unableToVerify.errorFlag()).isTrue();
            Assertions.assertThat(invalidKey.errFlgImage())
                    .isEqualTo(blankUserId.errFlgImage())
                    .isEqualTo(blankPassword.errFlgImage())
                    .isEqualTo(notFound.errFlgImage())
                    .isEqualTo(unableToVerify.errFlgImage())
                    .isEqualTo(SignOnService.ERR_FLG_ON);

            // A one-character field with two condition names has exactly two images the program can
            // produce, and neither is anything else. WS-ERR-FLG is PIC X(01), so a third value would not
            // fit a condition name at all.
            Assertions.assertThat(SignOnService.ERR_FLG_ON).isEqualTo("Y").hasSize(1);
            Assertions.assertThat(SignOnService.ERR_FLG_OFF).isEqualTo("N").hasSize(1);
            Assertions.assertThat(SignOnService.ERR_FLG_ON).isNotEqualTo(SignOnService.ERR_FLG_OFF);
        }

        @Test
        @DisplayName("no clock, no calendar and no randomness is in the decision graph, so no fixed "
                + "Clock has to be injected")
        void nothingTimeDependentReachesADecision() {
            // FUNCTION CURRENT-DATE appears once in the program, at :179 inside POPULATE-HEADER-INFO,
            // which stamps the header on the outgoing map. That is presentation: it belongs to the
            // controller and its response DTO, and no branch depends on it. So the service needs no
            // clock, and the right way to keep this test deterministic is to assert the absence rather
            // than to inject a fixed Clock that nothing would consult.
            for (Field field : SignOnService.class.getDeclaredFields()) {
                Assertions.assertThat(field.getType().getName())
                        .as("field %s of SignOnService", field.getName())
                        .doesNotStartWith("java.time")
                        .doesNotContain("Random")
                        .doesNotContain("Clock")
                        .doesNotContain("DateHeader");
            }
            for (RecordComponent component : SignOnOutcome.class.getRecordComponents()) {
                Assertions.assertThat(component.getType().getName())
                        .as("component %s of SignOnOutcome", component.getName())
                        .doesNotStartWith("java.time");
            }
            for (RecordComponent component : SignOnInput.class.getRecordComponents()) {
                Assertions.assertThat(component.getType().getName())
                        .as("component %s of SignOnInput", component.getName())
                        .doesNotStartWith("java.time");
            }

            // And the observable consequence: the same invocation twice over is the same outcome, so
            // nothing in the path is reading a moving value.
            stubRead(ReadResult.found(storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN)));
            SignOnInput input = enterKey(SEEDED_ADMIN_ID, STORED_PASSWORD);

            Assertions.assertThat(service.handle(input)).isEqualTo(service.handle(input));
        }

        @Test
        @DisplayName("the collaborator set is exactly the repository, injected through the constructor")
        void theOnlyCollaboratorIsTheRepositoryAndItArrivesByConstructor() {
            // Synthetic fields are excluded for the same reason as in the static-state test: they belong
            // to the tooling, not to the source, and counting them would make the size assertion below
            // depend on whether a coverage agent happened to be attached.
            Field[] instanceFields = Arrays.stream(SignOnService.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .toArray(Field[]::new);

            // One collaborator, and it is the USRSEC file. COSGN00C touches no other resource: :211-219
            // is the only EXEC CICS command in the program that names a dataset.
            Assertions.assertThat(instanceFields).hasSize(1);
            Assertions.assertThat(instanceFields[0].getType()).isEqualTo(SecUserRepository.class);
            Assertions.assertThat(Modifier.isFinal(instanceFields[0].getModifiers()))
                    .as("the collaborator field is final")
                    .isTrue();

            // Constructor injection, not field injection: one constructor, taking exactly that one
            // collaborator. Field injection would make the field non-final and would make this class
            // impossible to build without a container.
            Assertions.assertThat(SignOnService.class.getDeclaredConstructors()).hasSize(1);
            Assertions.assertThat(SignOnService.class.getDeclaredConstructors()[0].getParameterTypes())
                    .containsExactly(SecUserRepository.class);

            // Nothing is injected into a field by annotation, which is the other way the rule gets
            // broken. Checked by annotation name so that no framework type has to be imported here.
            for (Field field : SignOnService.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    Assertions.assertThat(annotation.annotationType().getSimpleName())
                            .as("annotation on field %s", field.getName())
                            .isNotIn("Autowired", "Inject", "Resource", "Value");
                }
            }
        }

        @Test
        @DisplayName("no static mutable state exists, so two concurrent sign-ons cannot see each other")
        void noStaticMutableStateExists() {
            // Every COBOL WORKING-STORAGE item in this program - WS-ERR-FLG, WS-MESSAGE, WS-USER-ID,
            // WS-USER-PWD, SEC-USER-DATA - is per-task storage in CICS and a method-local variable here.
            // A static field holding any of them would be shared across requests and would make this
            // suite's results depend on execution order.
            //
            // Synthetic fields are skipped, and the reason is worth stating: they are not authored state.
            // Running under a coverage agent adds an instrumentation array to every loaded class, and a
            // compiler adds its own bookkeeping fields; counting either would make this assertion report
            // on the tooling rather than on the source, and would fail differently depending on whether
            // coverage happened to be enabled. What remains after the filter is exactly what somebody
            // wrote, which is what the practice is about.
            assertEveryDeclaredStaticFieldIsFinal(SignOnService.class);
            assertEveryDeclaredStaticFieldIsFinal(SignOnServiceTest.class);
        }

        private void assertEveryDeclaredStaticFieldIsFinal(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Assertions.assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s of %s is final", field.getName(), type.getSimpleName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("conversation state travels in the payload: no session, cache or thread-local "
                + "collaborator exists")
        void conversationStateTravelsInThePayloadAndNowhereElse() {
            // CICS is pseudo-conversational, so the migration's rule is that the communication area, the
            // attention identifier and the screen's own values move in the request and the response and
            // never into server-side state. Asserted two ways.
            //
            // First, structurally: nothing that could hold state between calls is a collaborator.
            for (Field field : SignOnService.class.getDeclaredFields()) {
                Assertions.assertThat(field.getType().getName())
                        .as("field %s of SignOnService", field.getName())
                        .doesNotContain("Session")
                        .doesNotContain("session")
                        .doesNotContain("servlet")
                        .doesNotContain("Cache")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("Request")
                        .doesNotContain("Holder");
            }

            // Second, behaviourally: the area the caller gets back is the one it has to send on, and it
            // carries the whole of the conversation - who signed on, from where, and in which context.
            stubRead(ReadResult.found(storedUser(SEEDED_USER_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER)));

            SignOnOutcome outcome = service.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD));

            NavigationContext area = outcome.navigationContext();
            Assertions.assertThat(area.fromTranid()).isEqualTo(SignOnService.TRANSACTION_ID);
            Assertions.assertThat(area.fromProgram()).isEqualTo(SignOnService.PROGRAM_NAME);
            Assertions.assertThat(area.userId()).isEqualTo(SEEDED_USER_ID);
            Assertions.assertThat(area.userType()).isEqualTo(NavigationContext.USER_TYPE_USER);
            Assertions.assertThat(area.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);

            // A fresh service given the same stub reproduces it exactly, which is what "stateless" has
            // to mean: the instance contributed nothing that the arguments did not.
            SignOnService another = new SignOnService(repository);
            Assertions.assertThat(another.handle(enterKey(SEEDED_USER_ID, STORED_PASSWORD)))
                    .isEqualTo(outcome);
        }

        @Test
        @DisplayName("the seed fixtures trace to app/jcl/DUSRSECJ.jcl, and the loader's 57 characters "
                + "reach the copybook's 80")
        void theSeedFixturesTraceToTheLoader() {
            // The loader writes SEC-USR-ID, SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD and SEC-USR-TYPE
            // and stops - it omits SEC-USR-FILLER PIC X(23) entirely - so an in-stream record is 57
            // characters where CSUSR01Y declares 80. //SYSUT2 at DUSRSECJ.jcl:46-49 writes at LRECL=80,
            // which is what supplies the missing 23. This is the arithmetic that reconciles the two, and
            // it is asserted because a codec that dropped the filler would still produce a plausible
            // record and a wrong width.
            int seededPrefix = SecUserRecord.SEC_USR_ID_LENGTH
                    + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH;
            Assertions.assertThat(seededPrefix).isEqualTo(57);
            Assertions.assertThat(seededPrefix + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SecUserRecord.RECORD_LENGTH)
                    .isEqualTo(80);

            // And the two example records this class stubs with are the loader's own rows, field for
            // field, rather than invented names: DUSRSECJ.jcl:35 and :40.
            SecUserRecord admin = storedUser(SEEDED_ADMIN_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_ADMIN);
            SecUserRecord user = storedUser(SEEDED_USER_ID, STORED_PASSWORD,
                    NavigationContext.USER_TYPE_USER);

            Assertions.assertThat(admin.secUsrId()).isEqualTo("ADMIN001");
            Assertions.assertThat(admin.secUsrFname()).startsWith("MARGARET");
            Assertions.assertThat(admin.secUsrLname()).startsWith("GOLD");
            Assertions.assertThat(admin.secUsrPwd()).isEqualTo("PASSWORD");
            Assertions.assertThat(admin.secUsrType()).isEqualTo("A");
            Assertions.assertThat(user.secUsrId()).isEqualTo("USER0001");
            Assertions.assertThat(user.secUsrFname()).startsWith("LAWRENCE");
            Assertions.assertThat(user.secUsrLname()).startsWith("THOMAS");
            Assertions.assertThat(user.secUsrPwd()).isEqualTo("PASSWORD");
            Assertions.assertThat(user.secUsrType()).isEqualTo("U");

            // Both rows carry the same password, which is why a mismatch test cannot simply pick another
            // seeded user's value and has to supply a deliberately different one instead.
            Assertions.assertThat(admin.secUsrPwd()).isEqualTo(user.secUsrPwd());
        }
    }

    // =============================================================================================
    // The carriers, on their own.
    // =============================================================================================

    @Nested
    @DisplayName("The carriers")
    class Carriers {

        @Test
        @DisplayName("CursorField names the length item the source writes -1 into")
        void cursorFieldNamesItsLengthItem() {
            Assertions.assertThat(CursorField.NONE.lengthItemName()).isEmpty();
            Assertions.assertThat(CursorField.NONE.isPositioned()).isFalse();
            Assertions.assertThat(CursorField.USER_ID.lengthItemName()).contains("USERIDL");
            Assertions.assertThat(CursorField.USER_ID.isPositioned()).isTrue();
            Assertions.assertThat(CursorField.PASSWORD.lengthItemName()).contains("PASSWDL");
            Assertions.assertThat(CursorField.PASSWORD.isPositioned()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(Termination.class)
        @DisplayName("only RETURN TRANSID continues the conversation")
        void onlyReturnTransidContinues(Termination termination) {
            boolean expected = termination == Termination.RETURN_TRANSID;
            Assertions.assertThat(termination.conversationContinues()).isEqualTo(expected);
            Assertions.assertThat(termination.returnTransid().isPresent()).isEqualTo(expected);
            if (expected) {
                Assertions.assertThat(termination.returnTransid())
                        .contains(SignOnService.TRANSACTION_ID);
            }
        }

        @Test
        @DisplayName("ReceiveOutcome distinguishes a receive that happened from one that did not")
        void receiveOutcome() {
            Assertions.assertThat(ReceiveOutcome.NOT_PERFORMED.performed()).isFalse();
            Assertions.assertThat(ReceiveOutcome.NOT_PERFORMED.respCode()).isZero();
            Assertions.assertThat(ReceiveOutcome.NOT_PERFORMED.reasonCode()).isZero();
            Assertions.assertThat(ReceiveOutcome.normal().performed()).isTrue();
            Assertions.assertThat(ReceiveOutcome.normal().respCode())
                    .isEqualTo(SignOnService.RESP_NORMAL);
            Assertions.assertThat(ReceiveOutcome.normal())
                    .isNotEqualTo(ReceiveOutcome.NOT_PERFORMED);
        }

        @Test
        @DisplayName("MapInputArea carries the two spans COSGN0AO REDEFINES COSGN0AI makes one")
        void mapInputArea() {
            // app/cpy-bms/COSGN00.CPY:85 makes USERIDI/USERIDO one eight-byte span and PASSWDI/PASSWDO
            // another, so what the RECEIVE at :110-115 leaves there is what the SEND at :151-157 sends.
            MapInputArea received = MapInputArea.received("ADMIN001", "PASSWORD");

            Assertions.assertThat(received.useridi()).isEqualTo("ADMIN001");
            Assertions.assertThat(received.passwdi()).isEqualTo("PASSWORD");
            Assertions.assertThat(received).isEqualTo(MapInputArea.received("ADMIN001", "PASSWORD"));

            // The three paths that never receive: cold start at :80-83, PF3 at :88-90 and the
            // invalid-key arm at :91-94. LOW-VALUES, not spaces - the distinction :118 and :123 test.
            Assertions.assertThat(MapInputArea.UNTRANSMITTED.useridi())
                    .isEqualTo(lowValues(SignOnService.USER_ID_LENGTH));
            Assertions.assertThat(MapInputArea.UNTRANSMITTED.passwdi())
                    .isEqualTo(lowValues(SignOnService.PASSWORD_LENGTH));
            Assertions.assertThat(MapInputArea.UNTRANSMITTED)
                    .isNotEqualTo(MapInputArea.received(spaces(8), spaces(8)));
        }

        @Test
        @DisplayName("MapInputArea withholds the password span from its rendering, and keeps it in equals")
        void mapInputAreaRendering() {
            MapInputArea received = MapInputArea.received("ADMIN001", "ZQX7PW  ");

            Assertions.assertThat(received.toString())
                    .as("the span reaches a 3270 as dark field data; a log line is not a 3270 - CWE-532")
                    .contains("ADMIN001")
                    .doesNotContain("ZQX7PW")
                    .contains(SensitiveDiagnostics.REDACTED);
            Assertions.assertThat(received)
                    .as("equals is value semantics and discloses nothing, so it keeps the span")
                    .isNotEqualTo(MapInputArea.received("ADMIN001", "OTHERPW "));
        }

        @ParameterizedTest
        @CsvSource({"USERIDI, 7", "USERIDI, 9", "PASSWDI, 7", "PASSWDI, 9"})
        @DisplayName("MapInputArea rejects a span that departs from its declared width")
        void mapInputAreaRejectsWrongWidths(String item, int width) {
            String wrong = spaces(width);
            boolean userId = "USERIDI".equals(item);
            ThrowingCallable construction = userId
                    ? () -> MapInputArea.received(wrong, spaces(8))
                    : () -> MapInputArea.received(spaces(8), wrong);

            Assertions.assertThatThrownBy(construction)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(item);
        }

        @Test
        @DisplayName("MapInputArea rejects a null span: an untransmitted field is LOW-VALUES, not null")
        void mapInputAreaRejectsNull() {
            Assertions.assertThatThrownBy(() -> MapInputArea.received(null, spaces(8)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("USERIDI");
            Assertions.assertThatThrownBy(() -> MapInputArea.received(spaces(8), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("PASSWDI");
        }

        @Test
        @DisplayName("the outcome requires every reference component")
        void theOutcomeRequiresEveryReference() {
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), null,
                    Termination.RETURN_TRANSID)).isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> outcome(false, null, spaces(8),
                    spaces(80), Termination.RETURN_TRANSID))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> outcome(false, " ", null,
                    spaces(80), Termination.RETURN_TRANSID))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(80), null))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, null, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED,
                    MapInputArea.UNTRANSMITTED, Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    null, ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED, Optional.empty(),
                    Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), null, MapInputArea.UNTRANSMITTED, Optional.empty(),
                    Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED, null,
                    Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
            Assertions.assertThatThrownBy(() -> new SignOnOutcome(false, " ", spaces(8), spaces(80),
                    false, CursorField.NONE, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED,
                    Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the outcome rejects a width that departs from its copybook")
        void theOutcomeRejectsWrongWidths() {
            Assertions.assertThatThrownBy(() -> outcome(false, "AB", spaces(8), spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CDEMO-USER-TYPE");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(7), spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL target");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(79),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WS-MESSAGE");
        }

        @Test
        @DisplayName("a sign-on is an XCTL that names its target, and nothing else is")
        void theOutcomeTiesSuccessToTheExit() {
            // Both consistent combinations are legal.
            Assertions.assertThatCode(() -> outcome(false, " ", spaces(8), spaces(80),
                    Termination.RETURN_TRANSID)).doesNotThrowAnyException();
            Assertions.assertThatCode(() -> outcome(true, "A", "COADM01C", spaces(80),
                    Termination.XCTL)).doesNotThrowAnyException();

            // signedOn without an XCTL, and an XCTL without signedOn.
            Assertions.assertThatThrownBy(() -> outcome(true, "A", "COADM01C", spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL");
            Assertions.assertThatThrownBy(() -> outcome(false, " ", spaces(8), spaces(80),
                    Termination.XCTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("XCTL");

            // A named target without a sign-on, and a sign-on without a named target.
            Assertions.assertThatThrownBy(() -> outcome(false, " ", "COADM01C", spaces(80),
                    Termination.RETURN_TRANSID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names its target");
            Assertions.assertThatThrownBy(() -> outcome(true, "A", spaces(8), spaces(80),
                    Termination.XCTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("names its target");
        }

        @Test
        @DisplayName("errFlgImage, hasNextProgram and isAdminRole read the stored values")
        void derivedAccessors() {
            SignOnOutcome signedOn = outcome(true, "A", "COADM01C", spaces(80), Termination.XCTL);
            SignOnOutcome failed = new SignOnOutcome(false, "U", spaces(8), spaces(80), true,
                    CursorField.USER_ID, true, false, false, Termination.RETURN_TRANSID,
                    NavigationContext.empty(), ReceiveOutcome.NOT_PERFORMED,
                    MapInputArea.UNTRANSMITTED, Optional.empty(), Optional.empty());

            Assertions.assertThat(signedOn.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_OFF);
            Assertions.assertThat(signedOn.hasNextProgram()).isTrue();
            Assertions.assertThat(signedOn.isAdminRole()).isTrue();
            Assertions.assertThat(signedOn.returnTransid()).isEmpty();

            Assertions.assertThat(failed.errFlgImage()).isEqualTo(SignOnService.ERR_FLG_ON);
            Assertions.assertThat(failed.hasNextProgram()).isFalse();
            Assertions.assertThat(failed.isAdminRole()).isFalse();
            Assertions.assertThat(failed.returnTransid()).contains(SignOnService.TRANSACTION_ID);
        }

        @Test
        @DisplayName("an invocation is required")
        void anInvocationIsRequired() {
            Assertions.assertThatThrownBy(() -> service.handle(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("invocation");
        }

        private SignOnOutcome outcome(boolean signedOn,
                                      String role,
                                      String nextProgram,
                                      String message,
                                      Termination termination) {
            return new SignOnOutcome(signedOn, role, nextProgram, message, false, CursorField.NONE,
                    true, false, false, termination, NavigationContext.empty(),
                    ReceiveOutcome.NOT_PERFORMED, MapInputArea.UNTRANSMITTED, Optional.empty(),
                    Optional.empty());
        }
    }
}
