package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.user.SecUserRepository;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.UserDeleteController;
import com.vsergeychik.carddemo.user.UserDeleteController.CursorField;
import com.vsergeychik.carddemo.user.UserDeleteController.ProgramState;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest.Cu03Info;
import com.vsergeychik.carddemo.user.dto.UserDeleteResponse;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parity gate for {@code app/cbl/COUSR03C.cbl} - "Delete a user from USRSEC file", CICS
 * transaction {@code CU03}, projected as {@code DELETE /api/users/{userId}}.
 *
 * <h2>What this class asserts</h2>
 * Twenty declarative cases, {@code case01} through {@code case20}, each run through
 * {@link ParityHarness} and judged by {@link FieldDiffer}. A case passes only when the diff count is
 * <strong>zero</strong>: the comparison is bidirectional and exact, so an unpinned observation is a
 * difference exactly as a mismatched one is. The module is not complete until all twenty are clean.
 *
 * <h2>Where the expected values come from - and where they do not</h2>
 * <p><strong>The baseline is statically derived, never captured.</strong> Running the legacy program
 * to record its output is impossible in this environment - no z/OS, no CICS emulator, an indexed-file
 * handler that is disabled in the available compiler, no Language Environment {@code CEE*} services,
 * and the three IBM-supplied copybooks {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} absent
 * from the repository. Every expected byte below is therefore read out of the authoritative sources
 * by hand: the paragraph structure and literals of {@code app/cbl/COUSR03C.cbl}, the field names and
 * widths of {@code app/cpy-bms/COUSR03.CPY} and {@code app/bms/COUSR03.bms}, the 80-byte record of
 * {@code app/cpy/CSUSR01Y.cpy}, the communication area of {@code app/cpy/COCOM01Y.cpy}, the
 * transaction and file definitions of {@code app/csd/CARDDEMO.CSD}, and the ten seed rows of
 * {@code app/jcl/DUSRSECJ.jcl}. That substitution is a documented deviation from the "run the legacy
 * program" wording of the success criteria and is recorded as risk R-A; it changes the
 * <em>provenance</em> of the expected values and nothing else - the case count, the field-by-field
 * diffing and the zero-diff gate all stand.
 *
 * <p><strong>Nothing here is derived from the translation it judges.</strong> Every expected literal
 * is written out in full rather than referenced from a constant on the class under test: an
 * expectation that read {@code UserDeleteController.MSG_PRESS_PF5} would pass whatever that constant
 * happened to say, which is the one thing a parity gate must never do. The only values taken from
 * production code are structural facts a case has to speak in - the record layout it seeds through,
 * the code page, and the attention-identifier byte a terminal would have sent.
 *
 * <h2>Why the cases are declared here rather than loaded from {@code parity/COUSR03C/}</h2>
 * The harness convention is that {@code COUSR03CParityTest} reads
 * {@code src/test/resources/parity/COUSR03C/}, and this class's stem matches that directory exactly -
 * {@link #resourceConventionIsHonoured()} asserts the agreement rather than asserting it in a comment.
 * That directory is not present in this working tree, so the twenty cases are declared in Java through
 * {@link ParityCase}'s own canonical constructor: the identical constructor a JSON fixture is bound
 * through, with the identical validation of case identifiers, dataset binding keys, AID mnemonics,
 * code pages, {@code xxxO} and {@code xxxL} field names, attribute mnemonics and message widths. A
 * declaration that a fixture file would reject cannot be written here either. When the fixture set is
 * shipped, {@link #cases()} is the single seam that adopts it - {@code ParityHarness.casesOf("COUSR03C")}
 * returns the same twenty {@link ParityCase} values this method builds - and no other line of this
 * class changes.
 *
 * <h2>How the unit is reached: {@code CONTROLLER_POJO}, and no HTTP</h2>
 * The {@code user} package has a separate service class only for {@code COSGN00C}; the other four
 * programs, this one included, keep their decision logic in the controller. The unit is therefore
 * {@link UserDeleteController} constructed through its own constructor as a plain Java object, with a
 * fixture-backed {@link SecUserRepository} and the case's pinned {@link java.time.Clock}, and
 * {@link UserDeleteController#mainPara(UserDeleteRequest, byte, String)} called directly. This is
 * fully compliant with the "no HTTP layer in the path" requirement, and the reason is worth stating
 * plainly: calling a Java method on a Java object involves no request, no dispatcher servlet, no
 * filter chain, no content negotiation and no serialisation round trip. Mock MVC, a test REST
 * template, a web test client, a servlet container and a job launcher are all absent from this file,
 * and {@code deleteUser} - the {@code @DeleteMapping} adapter - is never called.
 *
 * <h2>The three traps this screen sets</h2>
 * <ol>
 *   <li><strong>Eleven fields, and no password.</strong> {@code COUSR03} declares eleven
 *       name-labelled {@code DFHMDF} fields where {@code COUSR02} declares twelve, and the twelfth is
 *       the one this screen omits: there is no {@code PASSWD} item anywhere in
 *       {@code app/cpy-bms/COUSR03.CPY}. The three user-maintenance screens are otherwise so alike
 *       that a copied expectation is easy to write and hard to spot, so every case here pins the
 *       eleven {@code xxxI}/{@code xxxO} items and nothing else. {@code SEC-USR-PWD} is read from the
 *       dataset because the copybook declares it inside the eighty bytes, and it is never painted,
 *       never asserted as a screen field and never logged.</li>
 *   <li><strong>The delete is a <em>held</em> delete.</strong> {@code app/cbl/COUSR03C.cbl:307-311}
 *       issues {@code EXEC CICS DELETE} with a dataset, a {@code RESP} and a {@code RESP2} and
 *       <em>no</em> {@code RIDFLD}, so it removes the record the preceding {@code READ ... UPDATE}
 *       left the task holding - not a record named by a key. The cases prove the ordering directly:
 *       {@link ParityScenario#expectedTrace()} records the repository operations in the order they
 *       happened, so a delete is only ever seen after a read-for-update of the same record, and
 *       {@code case10} proves the other half - a read that failed leaves nothing held, the keyless
 *       delete then names no record, and no delete of any kind is issued against the dataset.</li>
 *   <li><strong>The delete-failure message says "Update", and stays wrong.</strong>
 *       {@code app/cbl/COUSR03C.cbl:332} moves {@code 'Unable to Update User...'} on the
 *       <em>delete</em> path - a copy/paste defect inherited from {@code COUSR02C}. It is asserted
 *       exactly as written, in three separate cases, so that a well-meaning correction to "Delete"
 *       fails the build. Preserving an observable defect is the migration's contract; correcting one
 *       is a behaviour change.</li>
 * </ol>
 *
 * <h2>The observation contract for screen sends</h2>
 * A send in this program is not terminal: {@code PERFORM SEND-USRDEL-SCREEN} returns to its caller
 * and execution continues, so a single invocation paints the screen up to three times and each send
 * re-derives the whole map. {@link ProgramState} for this program reports the send <em>count</em> and
 * the terminal buffer - deliberately, because the last send is what the terminal shows - so
 * {@link FieldDiffer.ObservedResponse#sends()} is projected accordingly: one entry per send, every
 * entry carrying the six header fields that {@code POPULATE-HEADER-INFO} paints identically on every
 * send under the case's pinned clock, and the final entry additionally carrying the five variable
 * fields and the {@code ERRMSGC} colour byte. Nothing is invented for an earlier send and nothing
 * observable is dropped: the send count is asserted as behaviour, and every message this program can
 * produce reaches the terminal buffer on some case's last send, so all seven of its texts are pinned
 * byte-exactly somewhere in the twenty.
 *
 * <p>{@code CSSETATY}'s {@code DFHRED}-plus-{@code '*'} highlight has no part here, and that is a
 * source fact rather than an omission: {@code COUSR03C} copies {@code DFHAID} and {@code DFHBMSCA}
 * and copies neither {@code CSSETATY} nor {@code DFHATTR}, and it moves exactly one attribute item -
 * {@code ERRMSGC}, at lines 285 and 317. Highlighting a field here to match its sibling screens would
 * be an invention, so the attribute expectation is the colour byte and only the colour byte.
 *
 * <h2>Statelessness, and the credential in the seed</h2>
 * Conversation state travels in the payload: the 160-byte {@code CARDDEMO-COMMAREA} plus this
 * program's 34-byte {@code CDEMO-CU03-INFO} extension arrive in the request and leave in the response,
 * and every case pins all twenty-two of those fields on the way out.
 * {@link #stateIsNotCarriedBetweenRequests()} drives two consecutive requests through one controller
 * and one repository to prove nothing survives between them. The seed rows carry the literal
 * {@code PASSWORD} because {@code app/jcl/DUSRSECJ.jcl} does and the eighty bytes cannot be asserted
 * without it; it is legacy sample data and an obvious placeholder, no hashing or encoding is
 * introduced anywhere - {@code COSGN00C} compares the field in plaintext and this screen does not
 * read it at all - and {@link ParityCase.Redaction} masks the span out of every diagnostic.
 *
 * <h2>Rules</h2>
 * {@code review_rules} reports <strong>"No user rules provided"</strong> for this project. Their
 * absence is not licence to assert less: this class is held to the enterprise practices the plan
 * records as B1-B12 - verified dependency versions only (JUnit 5, AssertJ, Mockito, all managed by
 * the Boot parent; no Lombok, no Testcontainers), reference sources never written to, defects
 * preserved, security posture unchanged, a deterministic non-interactive run on a fixed clock, no
 * static mutable state, no wildcard imports, no {@code double} or {@code float}, an explicitly named
 * code page, no dataset name in source, and an environmental limit documented and escalated rather
 * than absorbed.
 *
 * @see ParityHarness the seeding, invocation and fingerprint capture
 * @see FieldDiffer the field-by-field comparison and the diff count the gate is stated in
 * @see UserDeleteController the unit under test
 */
class COUSR03CParityTest {

    // =================================================================================================
    // Identity. The program name is also the resource directory stem and this class's own stem.
    // =================================================================================================

    /** The COBOL program this class gates, spelled as {@code app/cbl/} and the resource root spell it. */
    private static final String PROGRAM = "COUSR03C";

    /** Every case reaches the controller as a plain Java object, with no HTTP layer in the path. */
    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    /**
     * The dataset binding key for {@code AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS}.
     *
     * <p>The key, never the dataset name: a case addresses a dataset the way {@code application.yml}
     * binds it, so no dataset name appears in this source file. {@code COUSR03C} touches this one file
     * and no other.
     */
    private static final String USRSEC = "USRSEC";

    /** The code page, named explicitly and never the platform default. The fixtures are US-ASCII. */
    private static final String CHARSET = "US-ASCII";

    /**
     * The instant every case pins, taken from {@code COUSR03C}'s own version footer
     * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:35 CDT} at
     * {@code app/cbl/COUSR03C.cbl:358}.
     *
     * <p>Pinned because {@code POPULATE-HEADER-INFO} reads {@code FUNCTION CURRENT-DATE} on every
     * send: a wall clock would make {@code CURDATEO} and {@code CURTIMEO} unassertable and the run
     * non-deterministic.
     */
    private static final String PINNED_CLOCK = "2022-07-19T23:12:35";

    /** {@code CURDATEO} for {@link #PINNED_CLOCK}: the {@code MM/DD/YY} assembly of lines 252 to 256. */
    private static final String CUR_DATE = "07/19/22";

    /** {@code CURTIMEO} for {@link #PINNED_CLOCK}: the {@code HH:MM:SS} assembly of lines 258 to 262. */
    private static final String CUR_TIME = "23:12:35";

    /**
     * {@code EIBCALEN} for a full communication area: the 160 bytes of {@code app/cpy/COCOM01Y.cpy}
     * plus the 34 bytes of {@code CDEMO-CU03-INFO} at {@code app/cbl/COUSR03C.cbl:50-58}.
     */
    private static final int EIBCALEN_FULL = 194;

    /** {@code EIBCALEN = 0} - the cold start guarded at lines 90 to 92. */
    private static final int EIBCALEN_NONE = 0;

    // =================================================================================================
    // Declared widths, transcribed from app/cpy-bms/COUSR03.CPY and app/cpy/CSUSR01Y.cpy. Every padded
    // image below is built from these rather than from a counted run of spaces in a literal.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)} - COUSR03.CPY:24. */
    private static final int TRNNAME_WIDTH = 4;

    /** {@code TITLE01I PIC X(40)} and {@code TITLE02I PIC X(40)} - COUSR03.CPY:30 and :48. */
    private static final int TITLE_WIDTH = 40;

    /** {@code CURDATEI PIC X(8)}, {@code CURTIMEI PIC X(8)}, {@code PGMNAMEI PIC X(8)}. */
    private static final int EIGHT = 8;

    /** {@code USRIDINI PIC X(8)} - COUSR03.CPY:60, and {@code SEC-USR-ID PIC X(08)}. */
    private static final int USRIDIN_WIDTH = 8;

    /** {@code FNAMEI PIC X(20)} and {@code LNAMEI PIC X(20)} - COUSR03.CPY:66 and :72. */
    private static final int NAME_WIDTH = 20;

    /** {@code USRTYPEI PIC X(1)} - COUSR03.CPY:78. */
    private static final int USRTYPE_WIDTH = 1;

    /** {@code ERRMSGI PIC X(78)} - COUSR03.CPY:84, the screen field the 80-byte message truncates into. */
    private static final int ERRMSG_WIDTH = 78;

    /** {@code WS-MESSAGE PIC X(80)} - app/cbl/COUSR03C.cbl:38. */
    private static final int WS_MESSAGE_WIDTH = 80;

    /** {@code SEC-USER-DATA} - app/cpy/CSUSR01Y.cpy, eighty bytes including the trailing filler. */
    private static final int USRSEC_RECORD_WIDTH = 80;

    // =================================================================================================
    // The seven message texts, transcribed from app/cbl/COUSR03C.cbl. Literals, not references: an
    // expectation that read the constant it is judging would agree with whatever that constant said.
    // =================================================================================================

    /** Line 283-284, the read's {@code NORMAL} arm - the confirmation prompt. */
    private static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    /** Lines 289-290 and 325-326 - both not-found arms carry the same text. */
    private static final String MSG_NOT_FOUND = "User ID NOT found...";

    /** Lines 147-148 and 179-180 - the blank-identifier arms of both entry paragraphs. */
    private static final String MSG_ID_EMPTY = "User ID can NOT be empty...";

    /** Lines 296-297, the read's {@code WHEN OTHER} arm. */
    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    /**
     * Lines 332-333, the delete's {@code WHEN OTHER} arm - <strong>a preserved source defect</strong>.
     *
     * <p>The word is "Update" on the delete path, copied from {@code COUSR02C}'s rewrite arm. The text
     * is observable behaviour, so it is asserted exactly as the source writes it. Correcting it to
     * "Delete" is a behaviour change this migration forbids, and three cases here would fail if
     * anyone tried.
     */
    private static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    /** The {@code STRING} prefix at line 318, {@code 'User '} - delimited by size, so both spaces count. */
    private static final String MSG_USER_PREFIX = "User ";

    /** The {@code STRING} suffix at line 320, {@code ' has been deleted ...'} - delimited by size. */
    private static final String MSG_DELETED_SUFFIX = " has been deleted ...";

    /**
     * {@code CCDA-MSG-INVALID-KEY} - {@code app/cpy/CSMSG01Y.cpy}, {@code PIC X(50)}, moved into
     * {@code WS-MESSAGE} at line 128 and therefore padded from fifty to eighty.
     *
     * <p>Forty characters of text and ten trailing spaces, which is fifty exactly. The trailing spaces
     * are part of the declared item and are transcribed rather than trimmed.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below..."
            + "          ";

    // =================================================================================================
    // Screen literals from app/cpy/COTTL01Y.cpy, transcribed character for character. Six leading
    // spaces and seven trailing for the first, fourteen and eighteen for the second: forty each.
    // =================================================================================================

    /** {@code CCDA-TITLE01} - the upper heading line of every online screen. */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} - the lower heading line. */
    private static final String TITLE02 = "              CardDemo                  ";

    /** {@code WS-TRANID PIC X(04) VALUE 'CU03'} - line 37, and the CSD transaction identifier. */
    private static final String TRANID = "CU03";

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} - line 36. */
    private static final String PGMNAME = "COUSR03C";

    /** The mapset this program sends and receives - lines 221 and 234. */
    private static final String MAPSET = "COUSR03";

    /** The map - lines 220 and 233. */
    private static final String MAP = "COUSR3A";

    /** {@code 'COSGN00C'}, the cold-start target at line 91. */
    private static final String SIGNON_PGM = "COSGN00C";

    /** {@code 'COADM01C'}, the PF3 fallback at line 113 and the PF12 target at line 124. */
    private static final String ADMIN_PGM = "COADM01C";

    /** The user-list screen this one is normally reached from, and the PF3 target when it is named. */
    private static final String USER_LIST_PGM = "COUSR00C";

    // =================================================================================================
    // The USRSEC seed. app/jcl/DUSRSECJ.jcl holds ten in-stream rows of exactly 57 characters - the
    // 8 + 20 + 20 + 8 + 1 of CSUSR01Y with SEC-USR-FILLER X(23) absent - while the same job writes them
    // to LRECL=80 and defines the cluster RECORDSIZE(80,80). The declared normalisation supplies the
    // missing 23 bytes as spaces, which is why every expected image below is eighty characters.
    // =================================================================================================

    /** {@code ADMIN001}, an administrator. */
    private static final String SEED_ADMIN001 =
            "ADMIN001MARGARET            GOLD                PASSWORDA";

    /** {@code ADMIN002}, the record {@code case03} fetches through {@code CDEMO-CU03-USR-SELECTED}. */
    private static final String SEED_ADMIN002 =
            "ADMIN002RUSSELL             RUSSELL             PASSWORDA";

    /** {@code ADMIN003}. */
    private static final String SEED_ADMIN003 =
            "ADMIN003RAYMOND             WHITMORE            PASSWORDA";

    /** {@code ADMIN004}. */
    private static final String SEED_ADMIN004 =
            "ADMIN004EMMANUEL            CASGRAIN            PASSWORDA";

    /** {@code ADMIN005}. */
    private static final String SEED_ADMIN005 =
            "ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA";

    /** {@code USER0001}, the record whose lookup is forced to fail in {@code case07}. */
    private static final String SEED_USER0001 =
            "USER0001LAWRENCE            THOMAS              PASSWORDU";

    /** {@code USER0002}, the record {@code case08} deletes. */
    private static final String SEED_USER0002 =
            "USER0002AJITH               KUMAR               PASSWORDU";

    /** {@code USER0003}, the record {@code case04} looks up. */
    private static final String SEED_USER0003 =
            "USER0003LAURITZ             ALME                PASSWORDU";

    /** {@code USER0004}, the record whose delete is forced to fail in {@code case11}. */
    private static final String SEED_USER0004 =
            "USER0004AVERARDO            MAZZI               PASSWORDU";

    /** {@code USER0005}, the record whose delete is forced not-found in {@code case12}. */
    private static final String SEED_USER0005 =
            "USER0005LEE                 TING                PASSWORDU";

    /**
     * An eleventh row whose key is <strong>shorter than its declared width</strong>, seeded only by
     * {@code case09}.
     *
     * <p>{@code DUSRSECJ.jcl} has no such row - all ten of its identifiers occupy the full eight
     * characters - and without one the {@code STRING ... DELIMITED BY SPACE} operand at line 319 is
     * unobservable, because a key with no space in it contributes all eight characters either way.
     * {@code 'AB      '} contributes two, which is the only way to prove the delimiter is a space and
     * not the item's size. Fifty-seven characters like every other row, so the same normalisation
     * applies.
     */
    private static final String SEED_SHORT_KEY =
            "AB      ANNABEL             BRIGHTWELL          PASSWORDU";

    /** The ten seed rows in {@code DUSRSECJ.jcl} order, which is also their key order. */
    private static final List<String> SEED_ROWS = List.of(SEED_ADMIN001, SEED_ADMIN002,
            SEED_ADMIN003, SEED_ADMIN004, SEED_ADMIN005, SEED_USER0001, SEED_USER0002,
            SEED_USER0003, SEED_USER0004, SEED_USER0005);

    /** The ten rows plus the short-key row, for the one case that needs a key with a space in it. */
    private static final List<String> SEED_ROWS_WITH_SHORT_KEY = List.of(SEED_ADMIN001, SEED_ADMIN002,
            SEED_ADMIN003, SEED_ADMIN004, SEED_ADMIN005, SEED_USER0001, SEED_USER0002,
            SEED_USER0003, SEED_USER0004, SEED_USER0005, SEED_SHORT_KEY);

    /** A user identifier no seed row carries, for the two not-found paths. */
    private static final String ABSENT_ID = "NOSUCH01";

    // =================================================================================================
    // Attribute and cursor names, as the copybook spells them.
    // =================================================================================================

    /** {@code ERRMSGC} - the colour byte of the message line, the one attribute item this program moves. */
    private static final String ERRMSGC = "ERRMSGC";

    /** {@code DFHDFCOL}, the default colour - the state of {@code ERRMSGC} where no colour was moved. */
    private static final String DFHDFCOL = "DFHDFCOL";

    /** {@code DFHNEUTR}, moved at line 285 when the lookup succeeds. */
    private static final String DFHNEUTR = "DFHNEUTR";

    /** {@code DFHGREEN}, moved at line 317 when the delete succeeds. */
    private static final String DFHGREEN = "DFHGREEN";

    /** {@code USRIDINL} - COUSR03.CPY:55, the cursor target of every arm but the two {@code WHEN OTHER}s. */
    private static final String USRIDINL = "USRIDINL";

    /** {@code FNAMEL} - COUSR03.CPY:61, the cursor target of lines 298 and 334 only. */
    private static final String FNAMEL = "FNAMEL";

    // =================================================================================================
    // THE GATE. One parameterized test over the twenty cases, and the diff count must be zero.
    // =================================================================================================

    /**
     * Runs one case and requires the diff count to be zero.
     *
     * <p>The whole rendered report is attached to the failure rather than a summary of it: a difference
     * is only actionable next to the field name, the expected image and the observed image, and
     * {@link FieldDiffer.DiffResult#render()} writes all three for every entry with the credential span
     * of a {@code USRSEC} row already masked.
     *
     * <p>The operation trace is asserted after the diff, and it asserts what a fingerprint structurally
     * cannot: the <em>order</em> in which the dataset was reached. A keyless {@code EXEC CICS DELETE}
     * is only correct after a {@code READ ... UPDATE} of the record it is meant to remove, so "read
     * then delete, same record" is behaviour in its own right - and so is "no delete at all", which is
     * what {@code case10} pins when the read has already failed.
     *
     * @param scenario the case and the repository operations it must produce, in order
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COUSR03C parity: every case produces zero differences")
    void producesNoDifferences(ParityScenario scenario) {
        Cousr03cUnit unit = new Cousr03cUnit();

        FieldDiffer.DiffResult result =
                ParityHarness.usAscii().judge(scenario.parityCase(), UNIT_KIND, unit);

        assertThat(result.count())
                .withFailMessage("%s", result.render())
                .isZero();
        assertThat(unit.trace())
                .as("the USRSEC operations %s issued, in order - a keyless DELETE is only ever "
                        + "correct against a record a preceding READ ... UPDATE left held",
                        scenario.caseId())
                .containsExactlyElementsOf(scenario.parityCase().expectedOperations());
    }

    /**
     * The twenty cases, in {@code case01} through {@code case20} order.
     *
     * <p>This method is the single seam between the gate and the case set. It is where a shipped
     * {@code src/test/resources/parity/COUSR03C/} fixture directory is adopted - the harness returns
     * the same twenty {@link ParityCase} values from {@code ParityHarness.casesOf(PROGRAM)}, validated
     * by the same constructor - and nothing else in this class depends on which of the two supplied
     * them.
     *
     * @return the twenty scenarios; never {@code null}
     */
    private static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = ParityHarness.casesOf(PROGRAM).stream()
                .map(ParityScenario::new)
                .toList();
        requireCompleteCaseSet(scenarios);
        return scenarios;
    }

    /**
     * Refuses a case set that is not exactly {@code case01} through {@code case20} of
     * {@value #PROGRAM}, in order and without repetition.
     *
     * <p>Loud on purpose, and checked inside the supplier so it cannot be bypassed by running a single
     * case. "The diff count is zero across all twenty cases" is satisfied vacuously by a set of four,
     * so a short, long, misnumbered or duplicated set is a gate that has stopped asking the questions
     * while still reporting green.
     *
     * @param scenarios the declared set
     * @throws IllegalStateException if the set is not the exact twenty
     */
    private static void requireCompleteCaseSet(List<ParityScenario> scenarios) {
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException(PROGRAM + " declares " + scenarios.size()
                    + " parity case(s) but the gate requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + ". A short set is not a smaller gate, it is a "
                    + "gate that passes without asking the questions.");
        }
        for (int ordinal = 1; ordinal <= scenarios.size(); ordinal++) {
            String expected = ParityHarness.caseId(ordinal);
            ParityCase declared = scenarios.get(ordinal - 1).parityCase();
            if (!expected.equals(declared.caseId())) {
                throw new IllegalStateException("Case " + ordinal + " of " + PROGRAM + " is declared "
                        + declared.caseId() + " where the set requires " + expected
                        + ". The identifiers are positional: a gap or a repeat means a case nobody "
                        + "is running, or one running twice while another runs not at all.");
            }
            if (!PROGRAM.equals(declared.program())) {
                throw new IllegalStateException("Case " + expected + " names program "
                        + declared.program() + " but this class gates " + PROGRAM
                        + ", whose cases live in " + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + '/');
            }
        }
    }

    // =================================================================================================
    // Invariants of the case set itself. These assert the shape every case must have, so a new case
    // cannot be added that quietly drops the seed, the normalisation, the code page or the unit kind.
    // =================================================================================================

    /**
     * Every case declares the same contract: this program, the controller-as-POJO unit kind, the named
     * code page, the {@code USRSEC} seed with its width normalisation, a return code of zero and no
     * job parameters.
     */
    @Test
    @DisplayName("all twenty cases declare COUSR03C, CONTROLLER_POJO, US-ASCII and the USRSEC 57-to-80 pad")
    void everyCaseDeclaresTheSameContract() {
        List<ParityScenario> scenarios = cases();

        assertThat(scenarios).hasSize(ParityHarness.CASES_PER_PROGRAM);
        for (ParityScenario scenario : scenarios) {
            ParityCase declared = scenario.parityCase();
            assertThat(declared.program()).isEqualTo(PROGRAM);
            assertThat(declared.unitKind()).isEqualTo(UNIT_KIND);
            assertThat(declared.description()).isNotBlank();
            assertThat(declared.jobParameters())
                    .as("%s is an online case: a batch job parameter here would be meaningless",
                            scenario.caseId())
                    .isEmpty();
            assertThat(declared.expectedReturnCode())
                    .as("%s - an online transaction sets no RETURN-CODE", scenario.caseId())
                    .isZero();
            assertThat(declared.expectedWrites())
                    .as("%s - COUSR03C writes no record: it reads for update and deletes",
                            scenario.caseId())
                    .isEmpty();
            assertThat(declared.screenRequest()).isNotNull();
            assertThat(declared.screenRequest().charset()).isEqualTo(CHARSET);
            assertThat(declared.screenRequest().pinnedClock()).isEqualTo(PINNED_CLOCK);
            assertThat(declared.inputs()).containsOnlyKeys(USRSEC);
            assertThat(declared.normalisations())
                    .as("%s must declare the 57-to-80 pad: DUSRSECJ.jcl holds 57-character rows and "
                            + "CSUSR01Y declares an 80-byte record", scenario.caseId())
                    .containsExactly(new ParityCase.DatasetNormalisation(USRSEC,
                            ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80));
            assertThat(declared.expectedResponse()).isNotNull();
        }
    }

    /**
     * The class stem, the program name and the harness's resource convention agree.
     *
     * <p>Asserted rather than asserted-in-a-comment: the harness resolves a case to
     * {@code parity/<PROGRAM>/<caseId>.json} using the upper-case program name, and that name is this
     * class's own stem. A class renamed without its program - or a program name lower-cased - would
     * read a directory nobody writes to.
     */
    @Test
    @DisplayName("the class stem matches the parity/COUSR03C resource directory")
    void resourceConventionIsHonoured() {
        assertThat(getClass().getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(20)))
                .isEqualTo("parity/COUSR03C/case20.json");
        assertThat(ParityHarness.CASES_PER_PROGRAM).isEqualTo(20);
    }

    /**
     * The seed rows are 57 characters and become 80-byte records whose {@code SEC-USR-FILLER} span is
     * space-filled.
     *
     * <p>The pad is the difference between what {@code app/jcl/DUSRSECJ.jcl} writes in-stream and what
     * {@code app/cpy/CSUSR01Y.cpy} declares, and it is what makes every expected image in this file
     * eighty characters wide. Decoding proves the pad landed in the declared span rather than
     * anywhere else: the identifier, both names, the credential span and the type all still read back
     * at their declared offsets.
     */
    @Test
    @DisplayName("the ten USRSEC seed rows pad from 57 to 80 with a space-filled SEC-USR-FILLER")
    void seedRowsPadFromFiftySevenToEighty() {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

        assertThat(ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80.sourceWidth()).isEqualTo(57);
        assertThat(ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80.targetWidth())
                .isEqualTo(USRSEC_RECORD_WIDTH);
        for (String row : SEED_ROWS_WITH_SHORT_KEY) {
            assertThat(row).hasSize(57);
            String padded = padded(row);
            assertThat(padded).hasSize(USRSEC_RECORD_WIDTH);
            SecUserRecord record =
                    SecUserRecord.decode(codec.encodeImage(padded, "a seeded USRSEC row"), codec);
            assertThat(record.secUsrFiller())
                    .as("SEC-USR-FILLER PIC X(23) is space-filled, and omitting it would make every "
                            + "downstream offset and the record width wrong")
                    .isEqualTo(blanks(23));
            assertThat(record.secUsrId()).hasSize(USRIDIN_WIDTH);
            assertThat(record.secUsrType()).hasSize(USRTYPE_WIDTH);
        }
    }

    /**
     * Two consecutive requests through one controller and one repository do not see each other.
     *
     * <p>This is rule R6 asserted directly rather than inferred. CICS is pseudo-conversational and the
     * translation is stateless: {@code WORKING-STORAGE} became a per-request {@link ProgramState}, so a
     * request that deleted a record and left a green confirmation on the screen must hand the next
     * request a blank message, no held record and no cursor of its own. The two requests are driven
     * against the <em>same</em> controller instance on purpose - a message, a colour byte, a hold or a
     * cursor kept in a field on the bean would be visible here and nowhere else.
     */
    @Test
    @DisplayName("no state survives between two requests through one controller")
    void stateIsNotCarriedBetweenRequests() {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);
        Map<String, String> rows = new LinkedHashMap<>();
        for (String row : SEED_ROWS) {
            String image = padded(row);
            rows.put(image.substring(0, USRIDIN_WIDTH), image);
        }
        Cousr03cUnit unit = new Cousr03cUnit();
        SecUserRepository repository =
                unit.stubRepository(codec, ParityHarness.FIXTURE_CHARSET, rows, null);
        UserDeleteController controller = new UserDeleteController(repository,
                ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK)));

        ProgramState first = controller.mainPara(
                reentryRequest(codec, fields(MAP_USRIDIN, "USER0002")), CicsAid.DFHPF5, null);
        ProgramState second = controller.mainPara(
                reentryRequest(codec, fields(MAP_USRIDIN, "USER0003")), CicsAid.DFHENTER, null);

        assertThat(first.message()).isEqualTo(pad(MSG_USER_PREFIX + "USER0002" + MSG_DELETED_SUFFIX,
                WS_MESSAGE_WIDTH));
        assertThat(first.errMsgColour()).isEqualTo(BmsAttributes.DFHGREEN);
        assertThat(rows).doesNotContainKey("USER0002");

        assertThat(second.message())
                .as("the second request paints its own prompt: the first request's green confirmation "
                        + "is not carried forward")
                .isEqualTo(pad(MSG_PRESS_PF5, WS_MESSAGE_WIDTH));
        assertThat(second.errMsgColour()).isEqualTo(BmsAttributes.DFHNEUTR);
        assertThat(second.response().usrIdIn()).isEqualTo("USER0003");
        assertThat(second.heldRecord())
                .as("the hold the first request took died with it; a hold that outlived a request "
                        + "would be a lock nobody releases")
                .isPresent();
        assertThat(unit.trace()).containsExactly(
                new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.READ_FOR_UPDATE, "USER0002"),
                new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.DELETE, "USER0002"),
                new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.READ_FOR_UPDATE, "USER0003"));
    }

    /**
     * The communication area this screen carries is the 160 bytes of {@code COCOM01Y} plus the 34-byte
     * {@code CDEMO-CU03-INFO} extension, and it survives the round trip.
     *
     * <p>Pinned because the payload <em>is</em> the conversation state: {@code EIBCALEN} in a case is
     * {@value #EIBCALEN_FULL} for exactly this reason, and a commarea that lost a byte on the way
     * through would take a customer, account or card identifier with it into the next transaction.
     */
    @Test
    @DisplayName("the carried commarea is 160 bytes plus a 34-byte extension and round-trips exactly")
    void commareaIsOneHundredAndSixtyBytesPlusThirtyFour() {
        FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(Cu03Info.LENGTH).isEqualTo(34);
        assertThat(NavigationContext.COMMAREA_LENGTH + Cu03Info.LENGTH).isEqualTo(EIBCALEN_FULL);

        NavigationContext carried = commareaOf(reentryCommarea(), codec);
        assertThat(carried.toFixedWidth(codec)).hasSize(NavigationContext.COMMAREA_LENGTH);
        assertThat(navigationImages(carried, extensionOf(reentryCommarea(), codec), codec))
                .containsAllEntriesOf(reentryCommarea());
    }

    // =================================================================================================
    // The scenario: one loaded case, wrapped only so the parameterized test can print a short label.
    // =================================================================================================

    /**
     * One loaded case.
     *
     * <p>A wrapper rather than the {@link ParityCase} itself for one reason: the display name. A
     * {@code ParityCase}'s own {@code toString} is deliberately redacting and long, and a parameterized
     * test's label wants the identifier and one sentence.
     *
     * <p>It carries nothing else. The operation order the gate asserts comes from
     * {@link ParityCase#expectedOperations()} - the case file - so there is no expectation authored here
     * for a reader to have to reconcile against the resource.
     *
     * @param parityCase the loaded case
     */
    private record ParityScenario(ParityCase parityCase) {

        /**
         * The case identifier, for a failure message that names the fixture.
         *
         * @return {@code case01} through {@code case20}
         */
        String caseId() {
            return parityCase.caseId();
        }

        /**
         * The parameterized test's display name.
         *
         * <p>The identifier and the first sentence of the description only. The description's later
         * sentences quote source lines and are long, and no field value is ever printed - a
         * {@code USRSEC} row carries the legacy plaintext credential span.
         *
         * @return a short label
         */
        @Override
        public String toString() {
            String description = parityCase.description();
            int firstStop = description.indexOf(". ");
            return caseId() + " - "
                    + (firstStop < 0 ? description : description.substring(0, firstStop));
        }
    }

    // =================================================================================================
    // THE ADAPTER. Constructs the controller as a plain Java object, calls MAIN-PARA, and projects what
    // came back. No HTTP layer, no job launcher, no Spring context.
    // =================================================================================================

    /**
     * Reaches {@link UserDeleteController} for one case and reports what it observably produced.
     *
     * <p>Not static, and one instance per run: the trace is per-invocation state, so a shared instance
     * would let one case see another's operations. There is no static mutable state in this class.
     */
    private static final class Cousr03cUnit implements ParityHarness.ParityUnit {

        /** Every {@code USRSEC} operation the run issued, in order. */
        private final List<ParityCase.ExpectedOperation> trace = new ArrayList<>();

        /**
         * The operations issued so far, in order.
         *
         * <p>Recorded as {@link ParityCase.ExpectedOperation} values rather than formatted strings, so
         * that what the run did and what the case file declares are the <em>same</em> type and no
         * rendering convention sits between them.
         *
         * @return an immutable copy; never {@code null}
         */
        List<ParityCase.ExpectedOperation> trace() {
            return List.copyOf(trace);
        }

        /**
         * Seeds the dataset, constructs the controller, calls {@code MAIN-PARA}, and records the
         * outcome.
         *
         * <p>The AID is handed over the way CICS hands it over - as the raw {@code EIBAID} byte - and it
         * is compared, never folded through a {@code CCARD-AID} token, which is how the twelve programs
         * that do not copy {@code CSSTRPFY}, this one included, spell it inline. A case that declares no
         * AID reaches {@code MAIN-PARA} as {@code DFHNULL}, a byte no {@code WHEN} clause names, which is
         * the honest projection of a path where {@code EIBAID} is never read: the cold start of lines 90
         * to 92 and the first entry of lines 95 to 105.
         *
         * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
         * @return the recorded outcome; never {@code null}
         */
        @Override
        public ParityHarness.UnitOutcome invoke(ParityHarness.Invocation invocation) {
            FixedWidthCodec codec = invocation.codec();
            Map<String, String> rows = seededRows(invocation);
            SecUserRepository repository =
                    stubRepository(codec, invocation.charset(), rows, invocation);
            UserDeleteController controller =
                    new UserDeleteController(repository, invocation.clock());

            UserDeleteRequest request = inboundScreen(invocation, codec);
            String usrSelected = invocation.commarea().get(CU03_USR_SELECTED);

            ProgramState state = invocation.aid() == null
                    ? controller.mainPara(request, CicsAid.DFHNULL, usrSelected)
                    : controller.mainPara(request, aidByte(invocation.aid()), usrSelected);

            ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();
            for (String line : state.displayLines()) {
                recorder.display(line);
            }
            recorder.message(new ParityCase.EmittedMessage(ParityCase.MessageChannel.WS_MESSAGE_80,
                    state.message()));
            recorder.message(new ParityCase.EmittedMessage(
                    ParityCase.MessageChannel.SCREEN_ERRMSG_78, state.response().errMsg()));
            return recorder.response(observedResponse(state, codec))
                    .finalState(USRSEC, SecUserRecord.LAYOUT, List.copyOf(rows.values()))
                    .returnCode(0)
                    .build();
        }

        /**
         * A {@code USRSEC} that answers from the seeded rows, and mutates them exactly as the program's
         * one write operation does.
         *
         * <p>Three refusals are wired deliberately, because each stands for an operation
         * {@code COUSR03C} does not perform and each would otherwise fail silently as a Mockito
         * default:
         *
         * <ul>
         *   <li>{@link SecUserRepository#read(String)} - the non-locking read. This program's only
         *       read is {@code EXEC CICS READ ... UPDATE} at lines 269 to 278, and a plain read would
         *       take no hold, leaving the delete at line 307 with nothing to act on.</li>
         *   <li>{@link SecUserRepository#deleteHeld(HeldRecord)} - the repository-level delete. The
         *       program reaches the delete from the hold itself, and routing it any other way would
         *       hide the precondition the source expresses by omitting {@code RIDFLD}.</li>
         *   <li>{@link SecUserRepository#rewrite(SecUserRecord)} - {@code COUSR02C} rewrites;
         *       {@code COUSR03C} deletes. The two sibling screens are one keystroke apart in the
         *       source and one method apart here.</li>
         * </ul>
         *
         * <p>A forced outcome is taken from the {@link ParityHarness.Invocation}
         * <strong>at the call site</strong> rather than resolved up front, so a case that declares one
         * the run never reaches is reported by the harness as the false claim it is.
         *
         * @param codec      the codec, carrying the case's code page
         * @param charset    the code page the controller will render every field in
         * @param rows       the seeded rows keyed by their eight-character identifier; mutated by a
         *                   successful delete, which is how the row's absence becomes observable
         * @param invocation the invocation whose forced outcomes apply, or {@code null} where a caller
         *                   drives the controller directly with no case behind it
         * @return the stub; never {@code null}
         */
        SecUserRepository stubRepository(FixedWidthCodec codec,
                                        Charset charset,
                                        Map<String, String> rows,
                                        ParityHarness.Invocation invocation) {
            SecUserRepository repository = mock(SecUserRepository.class);
            when(repository.datasetCharset()).thenReturn(charset);
            when(repository.read(anyString())).thenThrow(new IllegalStateException(
                    "COUSR03C issues no non-locking READ: its only read is EXEC CICS READ ... UPDATE "
                            + "at app/cbl/COUSR03C.cbl:269-278, and the keyless DELETE at :307-311 "
                            + "acts on the hold that read takes"));
            when(repository.deleteHeld(any(HeldRecord.class))).thenThrow(new IllegalStateException(
                    "the delete is reached from the hold - HeldRecord.deleteHeld() - because "
                            + "app/cbl/COUSR03C.cbl:307-311 names no RIDFLD and therefore removes the "
                            + "record the task is holding, not one identified by key"));
            when(repository.rewrite(any(SecUserRecord.class))).thenThrow(new IllegalStateException(
                    "COUSR03C never rewrites a record; that is COUSR02C's UPDATE-USER-INFO. This "
                            + "screen reads for update and deletes"));

            when(repository.readForUpdate(anyString())).thenAnswer(call -> {
                String key = call.getArgument(0);
                trace.add(new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.READ_FOR_UPDATE, key.strip()));
                if (forces(invocation, ParityCase.RepositoryOperation.READ_FOR_UPDATE)) {
                    return forcedRead(invocation.forcedOutcome(
                            ParityCase.RepositoryOperation.READ_FOR_UPDATE));
                }
                String image = rows.get(key);
                if (image == null) {
                    return ReadResult.notFound();
                }
                SecUserRecord record = SecUserRecord.decode(
                        codec.encodeImage(image, "the seeded USRSEC row for key " + key.strip()),
                        codec);
                return ReadResult.held(record, heldRecord(record, key, rows, invocation));
            });
            return repository;
        }

        /**
         * The hold a successful {@code READ ... UPDATE} leaves the task with.
         *
         * <p>{@link HeldRecord} has a private constructor - a hold is only ever produced by the read
         * that took it - so it is stubbed rather than built. {@link HeldRecord#deleteHeld()} removes
         * the row, which is the whole of {@code EXEC CICS DELETE}'s effect, unless the case forces the
         * command to fail.
         */
        private HeldRecord heldRecord(SecUserRecord record,
                                      String key,
                                      Map<String, String> rows,
                                      ParityHarness.Invocation invocation) {
            HeldRecord hold = mock(HeldRecord.class);
            when(hold.record()).thenReturn(record);
            when(hold.datasetName()).thenReturn(USRSEC);
            when(hold.deleteHeld()).thenAnswer(call -> {
                trace.add(new ParityCase.ExpectedOperation(USRSEC,
                        ParityCase.RepositoryOperation.DELETE, record.secUsrId().strip()));
                if (forces(invocation, ParityCase.RepositoryOperation.DELETE)) {
                    return forcedWrite(
                            invocation.forcedOutcome(ParityCase.RepositoryOperation.DELETE));
                }
                rows.remove(key);
                return WriteResult.written();
            });
            return hold;
        }

        /** Whether a case is behind this run and forces an outcome for {@code operation}. */
        private static boolean forces(ParityHarness.Invocation invocation,
                                     ParityCase.RepositoryOperation operation) {
            return invocation != null && invocation.hasForcedOutcome(operation);
        }
    }

    // =================================================================================================
    // Projection: seeded rows in, observed response out. Everything here reads the unit's own report -
    // nothing is re-derived from an expectation, or the comparison would be judging itself.
    // =================================================================================================

    /** The {@code CDEMO-CU03-INFO} item names, spelled as {@code app/cbl/COUSR03C.cbl:51-58} spells them. */
    private static final String CU03_PREFIX = "CDEMO-CU03-";

    /** {@code CDEMO-CU03-USRID-FIRST PIC X(08)} - line 51, carried and never read by this program. */
    private static final String CU03_USRID_FIRST = CU03_PREFIX + "USRID-FIRST";

    /** {@code CDEMO-CU03-USRID-LAST PIC X(08)} - line 52. */
    private static final String CU03_USRID_LAST = CU03_PREFIX + "USRID-LAST";

    /** {@code CDEMO-CU03-PAGE-NUM PIC 9(08)} - line 53, carried as its zero-filled image. */
    private static final String CU03_PAGE_NUM = CU03_PREFIX + "PAGE-NUM";

    /** {@code CDEMO-CU03-NEXT-PAGE-FLG PIC X(01) VALUE 'N'} - line 54, the one item with a VALUE. */
    private static final String CU03_NEXT_PAGE_FLG = CU03_PREFIX + "NEXT-PAGE-FLG";

    /** {@code CDEMO-CU03-USR-SEL-FLG PIC X(01)} - line 57. */
    private static final String CU03_USR_SEL_FLG = CU03_PREFIX + "USR-SEL-FLG";

    /**
     * {@code CDEMO-CU03-USR-SELECTED PIC X(08)} - line 58, and the only item of the extension this
     * program reads: lines 99 to 102 fetch the record it names on first entry.
     */
    private static final String CU03_USR_SELECTED = CU03_PREFIX + "USR-SELECTED";

    /** {@code TRNNAMEI} - the received transaction name, overwritten again by every send. */
    private static final String MAP_TRNNAME = "TRNNAMEI";

    /** {@code TITLE01I} - the received upper title. */
    private static final String MAP_TITLE01 = "TITLE01I";

    /** {@code CURDATEI} - the received date. */
    private static final String MAP_CURDATE = "CURDATEI";

    /** {@code PGMNAMEI} - the received program name. */
    private static final String MAP_PGMNAME = "PGMNAMEI";

    /** {@code TITLE02I} - the received lower title. */
    private static final String MAP_TITLE02 = "TITLE02I";

    /** {@code CURTIMEI} - the received time. */
    private static final String MAP_CURTIME = "CURTIMEI";

    /** {@code USRIDINI} - the only field on this map the operator can type into. */
    private static final String MAP_USRIDIN = "USRIDINI";

    /** {@code FNAMEI} - the first name, {@code ASKIP} and transmitted because it is {@code FSET}. */
    private static final String MAP_FNAME = "FNAMEI";

    /** {@code LNAMEI} - the last name. */
    private static final String MAP_LNAME = "LNAMEI";

    /** {@code USRTYPEI} - the user type, one byte. */
    private static final String MAP_USRTYPE = "USRTYPEI";

    /** {@code ERRMSGI} - the message line as the terminal transmitted it back. */
    private static final String MAP_ERRMSG = "ERRMSGI";

    /**
     * The seeded rows, keyed by the eight-character identifier they begin with.
     *
     * <p>Mutable and ordered: a successful delete removes an entry, and the remaining entries keep
     * their relative order, which is what makes the row's absence and the surviving rows' positions
     * both observable in the final state.
     *
     * @param invocation the invocation carrying the seeded dataset
     * @return the rows in seed order
     */
    private static Map<String, String> seededRows(ParityHarness.Invocation invocation) {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String row : invocation.dataset(USRSEC).rows()) {
            rows.put(row.substring(0, USRIDIN_WIDTH), row);
        }
        return rows;
    }

    /**
     * The inbound screen, or {@code null} for {@code EIBCALEN = 0}.
     *
     * <p>An absent payload is the cold start of lines 90 to 92, and the harness expresses it as an
     * {@code eibcalen} of zero rather than as an absent {@code screenRequest}, because a case still
     * pins the response that path produces.
     *
     * @param invocation the invocation carrying the received map and the communication area
     * @param codec      the codec, for assembling the 160-byte area
     * @return the request, or {@code null} when nothing was passed
     */
    private static UserDeleteRequest inboundScreen(ParityHarness.Invocation invocation,
                                                  FixedWidthCodec codec) {
        if (invocation.eibcalen() == EIBCALEN_NONE) {
            return null;
        }
        return screenOf(invocation.mapFields(), invocation.commarea(), codec);
    }

    /**
     * Builds a received map and its communication area into one payload.
     *
     * <p>A field the case does not name arrives {@code null}, which the controller turns into the
     * spaces a symbolic-map item holds when the terminal transmitted nothing for it. The AID is not
     * placed in the payload: it travels to {@code MAIN-PARA} as the raw {@code EIBAID} byte, which is
     * where the source reads it from.
     *
     * @param fields   the {@code xxxI} items the terminal transmitted
     * @param commarea the communication area, including the six extension items
     * @param codec    the codec, for assembling the 160-byte area
     * @return the payload; never {@code null}
     */
    private static UserDeleteRequest screenOf(Map<String, String> fields,
                                              Map<String, String> commarea,
                                              FixedWidthCodec codec) {
        return new UserDeleteRequest(fields.get(MAP_TRNNAME),
                fields.get(MAP_TITLE01),
                fields.get(MAP_CURDATE),
                fields.get(MAP_PGMNAME),
                fields.get(MAP_TITLE02),
                fields.get(MAP_CURTIME),
                fields.get(MAP_USRIDIN),
                fields.get(MAP_FNAME),
                fields.get(MAP_LNAME),
                fields.get(MAP_USRTYPE),
                fields.get(MAP_ERRMSG),
                commareaOf(commarea, codec),
                null,
                extensionOf(commarea, codec));
    }

    /**
     * A payload carrying the standard re-entry communication area and the given received fields.
     *
     * @param codec  the codec, for assembling the 160-byte area
     * @param fields the {@code xxxI} items the terminal transmitted
     * @return the payload; never {@code null}
     */
    private static UserDeleteRequest reentryRequest(FixedWidthCodec codec,
                                                   Map<String, String> fields) {
        return screenOf(fields, reentryCommarea(), codec);
    }

    /**
     * The 160-byte {@code CARDDEMO-COMMAREA} half of a declared communication area.
     *
     * <p>Assembled through the layout rather than through sixteen {@code with} calls, so a case states
     * each field as the <em>image</em> it occupies in the area - {@code "000000000"} for
     * {@code CDEMO-CUST-ID PIC 9(09)}, a single space for a one-byte alphanumeric - and a mis-shaped
     * image is refused by the codec rather than coerced.
     *
     * @param images the field images, extension items included and ignored here
     * @param codec  the codec
     * @return the context; never {@code null}
     */
    private static NavigationContext commareaOf(Map<String, String> images, FixedWidthCodec codec) {
        Map<String, String> general = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : images.entrySet()) {
            if (!entry.getKey().startsWith(CU03_PREFIX)) {
                general.put(entry.getKey(), entry.getValue());
            }
        }
        return NavigationContext.fromFixedWidth(codec,
                codec.serialise(NavigationContext.LAYOUT, general));
    }

    /**
     * The 34-byte {@code CDEMO-CU03-INFO} half of a declared communication area.
     *
     * @param images the field images, general items included and ignored here
     * @param codec  the codec, for reading the {@code PIC 9(08)} page number's image
     * @return the extension; never {@code null}
     */
    private static Cu03Info extensionOf(Map<String, String> images, FixedWidthCodec codec) {
        return new Cu03Info(images.get(CU03_USRID_FIRST),
                images.get(CU03_USRID_LAST),
                codec.decodePic9AsInt(images.get(CU03_PAGE_NUM)),
                images.get(CU03_NEXT_PAGE_FLG),
                images.get(CU03_USR_SEL_FLG),
                images.get(CU03_USR_SELECTED));
    }

    /**
     * The twenty-two communication-area images a response carries back.
     *
     * @param context   the 160-byte area
     * @param extension the 34-byte extension
     * @param codec     the codec
     * @return the images, general items first
     */
    private static Map<String, String> navigationImages(NavigationContext context,
                                                        Cu03Info extension,
                                                        FixedWidthCodec codec) {
        Map<String, String> images =
                new LinkedHashMap<>(codec.deserialise(NavigationContext.LAYOUT,
                        context.toFixedWidth(codec)));
        images.putAll(extension.fieldImages());
        return images;
    }

    /**
     * Projects the terminal {@link ProgramState} into the observation the differ judges.
     *
     * <p>Four things that are <em>not</em> payload fields are reported where they belong: the
     * {@code MOVE -1 TO <field>L} cursor request as the {@code xxxL} item's own name, the
     * {@code MOVE <colour> TO ERRMSGC} byte as an attribute rather than a field, the termination, and
     * the send count as the length of the send list. A blank {@code nextProgram}, {@code nextMapset}
     * or {@code nextMap} is reported as absent rather than as spaces: the transfer at lines 205 to 208
     * names a program and leaves the map and mapset untouched, and "named nothing" is the honest
     * reading of a field the program never wrote.
     *
     * @param state the state the invocation ended in
     * @param codec the codec, for rendering the carried communication area
     * @return the observation; never {@code null}
     */
    private static FieldDiffer.ObservedResponse observedResponse(ProgramState state,
                                                                 FixedWidthCodec codec) {
        UserDeleteResponse response = state.response();
        // One observation per SEND, from the snapshot that send took. EXEC CICS SEND MAP ... FROM(COUSR3AO)
        // transmits the whole map area, so every send carries all eleven xxxO items and they are not the
        // same eleven each time: the fetch paints the record and the prompt, and the unconditional send that
        // follows paints it again with the type resolved. Deriving the earlier sends from the FINAL screen
        // buffer would report the last values for all of them and make a two-send path indistinguishable
        // from a one-send path that ended the same way.
        List<FieldDiffer.ObservedSend> sends = new ArrayList<>(state.sendCount());
        for (UserDeleteController.Send send : state.sends()) {
            sends.add(new FieldDiffer.ObservedSend(send.fields(), send.attributes()));
        }
        return new FieldDiffer.ObservedResponse(blankToNull(response.nextProgram()),
                blankToNull(response.nextMapset()),
                blankToNull(response.nextMap()),
                navigationImages(state.commarea(), state.cu03Info(), codec),
                sends,
                state.cursorField().map(CursorField::lengthItem).orElse(null),
                termination(state));
    }

    /**
     * How the transaction ended.
     *
     * <p>{@code XCTL} and {@code RETURN} are not interchangeable: control that transferred never
     * reaches the {@code EXEC CICS RETURN} at lines 134 to 137, and a translation that reported both
     * would have invented a path the source cannot take.
     *
     * @param state the terminal state
     * @return the termination, or {@code null} where the unit reported neither
     */
    private static ParityCase.Termination termination(ProgramState state) {
        if (state.isTransferred()) {
            return ParityCase.Termination.XCTL;
        }
        return state.isReturned() ? ParityCase.Termination.RETURN_TRANSID : null;
    }

    /**
     * The raw {@code EIBAID} byte a {@code DFHAID} mnemonic stands for.
     *
     * <p>Inverted from {@link CicsAid#mnemonicsByAid()} rather than restated, because {@code DFHAID}
     * is IBM-supplied and absent from this repository: that class is the single reproduction of it,
     * and a second table here would be a second thing to keep right.
     *
     * @param mnemonic the mnemonic a case declares
     * @return the byte CICS would have placed in {@code EIBAID}
     * @throws IllegalArgumentException if the mnemonic names no attention identifier
     */
    private static byte aidByte(String mnemonic) {
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("'" + mnemonic + "' is not a DFHAID mnemonic, so no "
                + "EIBAID byte stands for it. CicsAid reproduces the copybook; name one of its "
                + "constants.");
    }

    /**
     * The read outcome a case forces, for an arm the seeded rows cannot reach.
     *
     * <p>{@code OK} is deliberately not forcible. A successful read hands back a record, and the
     * record a case wants back is the one it seeded - forcing success would let a case assert against
     * a record that was never in the file.
     *
     * @param forced the declared outcome
     * @return the outcome the repository will report
     * @throws IllegalArgumentException if {@code OK} is forced
     */
    private static ReadResult forcedRead(ParityCase.ForcedOutcome forced) {
        CicsResponse response = respOf(forced);
        return switch (forced.outcome()) {
            case NOT_FOUND -> ReadResult.of(FileStatus.NOT_FOUND, response);
            case END_OF_FILE -> ReadResult.of(FileStatus.END_OF_FILE, response);
            case DUPLICATE -> ReadResult.of(FileStatus.DUPLICATE, response);
            case OTHER -> ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, response);
            case OK -> throw new IllegalArgumentException("A successful read is not forced: seed the "
                    + "row and let the read find it, so the record the program paints is the record "
                    + "the case put in the file.");
        };
    }

    /**
     * The delete outcome a case forces.
     *
     * <p>{@code OK} is deliberately not forcible here either: a successful delete's whole observable
     * effect is the row's absence from the final state, so it is produced by removing the row rather
     * than by reporting success over a row that is still there.
     *
     * @param forced the declared outcome
     * @return the outcome the hold will report
     * @throws IllegalArgumentException if {@code OK} is forced
     */
    private static WriteResult forcedWrite(ParityCase.ForcedOutcome forced) {
        CicsResponse response = respOf(forced);
        return switch (forced.outcome()) {
            case NOT_FOUND -> WriteResult.of(FileStatus.NOT_FOUND, response);
            case END_OF_FILE -> WriteResult.of(FileStatus.END_OF_FILE, response);
            case DUPLICATE -> WriteResult.of(FileStatus.DUPLICATE, response);
            case OTHER -> WriteResult.of(SecUserRepository.PERMANENT_ERROR_STATUS, response);
            case OK -> throw new IllegalArgumentException("A successful delete is not forced: it is "
                    + "the removal of the seeded row, which is what the final state asserts.");
        };
    }

    /**
     * The {@code RESP} and {@code RESP2} pair a forced outcome carries.
     *
     * <p>Every forced outcome in this file states its {@code RESP} explicitly, because the two
     * {@code WHEN OTHER} arms display it: {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at
     * lines 294 and 330 puts the number into the fingerprint, so a case that left it unstated would be
     * asserting a value it had not chosen.
     *
     * @param forced the declared outcome
     * @return the response pair
     */
    private static CicsResponse respOf(ParityCase.ForcedOutcome forced) {
        if (forced.resp() == null) {
            return CicsResponse.none();
        }
        return CicsResponse.reported(forced.resp(),
                forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2());
    }

    // =================================================================================================
    // COBOL rendering helpers. Every expected image in this file is built through these, so a width is
    // never a counted run of spaces in a literal and an over-long value is refused rather than clipped.
    // =================================================================================================

    /**
     * {@code n} spaces - the content of a cleared {@code PIC X(n)} item.
     *
     * @param width the item's declared width
     * @return the image
     */
    private static String blanks(int width) {
        return " ".repeat(width);
    }

    /**
     * A value in a {@code PIC X(width)} item: right-padded with spaces, exactly as an alphanumeric
     * {@code MOVE} into a wider receiver pads.
     *
     * @param value the value moved
     * @param width the receiver's declared width
     * @return an image of exactly {@code width} characters
     * @throws IllegalArgumentException if the value is wider than the receiver. COBOL would truncate
     *                                  on the right; an expectation that silently truncated would
     *                                  assert a value nobody wrote, so this refuses instead
     */
    private static String pad(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException("'" + value + "' is " + value.length()
                    + " characters and the receiver is PIC X(" + width + "). A COBOL MOVE would "
                    + "truncate on the right; state the truncated image outright rather than letting "
                    + "an expectation shorten it.");
        }
        return value + blanks(width - value.length());
    }

    /**
     * A 57-character {@code DUSRSECJ.jcl} row as the 80-byte record {@code CSUSR01Y} declares.
     *
     * @param row the in-stream row
     * @return the record image, {@code SEC-USR-FILLER} supplied as 23 spaces
     */
    private static String padded(String row) {
        return pad(row, USRSEC_RECORD_WIDTH);
    }

    /**
     * A value that holds nothing but spaces and low-values, reported as absent.
     *
     * @param value the field's image
     * @return the value, or {@code null} when it is blank
     */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && character != '\u0000') {
                return value;
            }
        }
        return null;
    }

    // =================================================================================================
    // Case construction. Every field name below is written out as the copybook spells it rather than
    // referenced from a constant on the production type, so a renamed field is a difference rather than
    // a silent agreement between two sides that moved together.
    // =================================================================================================

    /**
     * A declared communication area: the sixteen {@code COCOM01Y} items and the six extension items,
     * as the images they occupy in the 194 bytes.
     *
     * <p>The customer, account and card items are the zeros and spaces a screen reached from the user
     * list carries - {@code COUSR03C} reads none of them and writes none of them, and they are pinned
     * precisely because they must travel through untouched. {@code CDEMO-LAST-MAP} and
     * {@code CDEMO-LAST-MAPSET} likewise: this program sets neither anywhere in its 359 lines.
     *
     * @param fromTranid  {@code CDEMO-FROM-TRANID}, the transaction control came from
     * @param fromProgram {@code CDEMO-FROM-PROGRAM}, which the PF3 arm at line 112 tests for blankness
     * @param toProgram   {@code CDEMO-TO-PROGRAM}
     * @param pgmContext  {@code CDEMO-PGM-CONTEXT}: {@code "0"} is first entry, {@code "1"} re-entry
     * @param usrSelected {@code CDEMO-CU03-USR-SELECTED}, read only on first entry
     * @return the images, general items first
     */
    private static Map<String, String> commareaImages(String fromTranid,
                                                      String fromProgram,
                                                      String toProgram,
                                                      String pgmContext,
                                                      String usrSelected) {
        Map<String, String> images = new LinkedHashMap<>();
        images.put("CDEMO-FROM-TRANID", pad(fromTranid, TRNNAME_WIDTH));
        images.put("CDEMO-FROM-PROGRAM", pad(fromProgram, EIGHT));
        images.put("CDEMO-TO-TRANID", TRANID);
        images.put("CDEMO-TO-PROGRAM", pad(toProgram, EIGHT));
        images.put("CDEMO-USER-ID", "ADMIN001");
        images.put("CDEMO-USER-TYPE", "A");
        images.put("CDEMO-PGM-CONTEXT", pgmContext);
        images.put("CDEMO-CUST-ID", "000000000");
        images.put("CDEMO-CUST-FNAME", blanks(25));
        images.put("CDEMO-CUST-MNAME", blanks(25));
        images.put("CDEMO-CUST-LNAME", blanks(25));
        images.put("CDEMO-ACCT-ID", "00000000000");
        images.put("CDEMO-ACCT-STATUS", " ");
        images.put("CDEMO-CARD-NUM", "0000000000000000");
        images.put("CDEMO-LAST-MAP", MAP);
        images.put("CDEMO-LAST-MAPSET", MAPSET);
        images.put(CU03_USRID_FIRST, "ADMIN001");
        images.put(CU03_USRID_LAST, "USER0005");
        images.put(CU03_PAGE_NUM, "00000001");
        images.put(CU03_NEXT_PAGE_FLG, "N");
        images.put(CU03_USR_SEL_FLG, " ");
        images.put(CU03_USR_SELECTED, pad(usrSelected, USRIDIN_WIDTH));
        return images;
    }

    /** The communication area of a re-entry: the operator is typing into the map, context {@code 1}. */
    private static Map<String, String> reentryCommarea() {
        return commareaImages("CU00", USER_LIST_PGM, PGMNAME, "1", blanks(USRIDIN_WIDTH));
    }

    /**
     * A map of field images from alternating names and values.
     *
     * <p>Hand-rolled rather than {@code Map.of}, for two reasons that both matter here: the received
     * map of {@code case04} names all eleven {@code xxxI} items and {@code Map.of} stops at ten pairs,
     * and declaration order is preserved so a failure lists the fields in the order the copybook
     * declares them rather than in hash order.
     *
     * @param pairs alternating field name and image
     * @return the images in declaration order
     * @throws IllegalArgumentException if the pairs are not in twos
     */
    private static Map<String, String> fields(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Field images come in name-and-image pairs; "
                    + pairs.length + " value(s) were given");
        }
        Map<String, String> images = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            images.put(pairs[index], pairs[index + 1]);
        }
        return images;
    }

    /** The six header items every send repaints, as {@code POPULATE-HEADER-INFO} leaves them. */
    private static Map<String, String> headerFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRNNAMEO", TRANID);
        fields.put("TITLE01O", TITLE01);
        fields.put("CURDATEO", CUR_DATE);
        fields.put("PGMNAMEO", PGMNAME);
        fields.put("TITLE02O", TITLE02);
        fields.put("CURTIMEO", CUR_TIME);
        return fields;
    }

    /**
     * The sends one invocation performed.
     *
     * <p>A send is not terminal in this program, so the count is behaviour: the successful lookup of
     * lines 156 to 169 sends twice, a first entry that fetched a selected record sends three times, and
     * a confirm that failed sends twice. Each send repaints the header identically under the pinned
     * clock; the last one carries the five variable items and the colour byte, which is what the
     * terminal is left showing.
     *
     * @param count   how many times {@code SEND-USRDEL-SCREEN} ran
     * @param painted the five variable items of the last send
     * @param colour  the {@code ERRMSGC} mnemonic the last send carried
     * @return the sends, in order
     */
    private static List<ParityCase.ScreenSend> sends(int count,
                                                     Map<String, String> painted,
                                                     String colour) {
        List<ParityCase.ScreenSend> screens = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (index == count - 1) {
                Map<String, String> fields = headerFields();
                fields.putAll(painted);
                screens.add(new ParityCase.ScreenSend(fields, Map.of(ERRMSGC, colour)));
            } else {
                screens.add(new ParityCase.ScreenSend(headerFields(), Map.of()));
            }
        }
        return screens;
    }

    /**
     * What {@code USRSEC} holds afterwards, row by row, as the 80-byte images {@code CSUSR01Y} declares.
     *
     * <p>Every row is pinned as a whole image rather than as a handful of fields: pinning
     * {@code SEC-USR-ID} alone would say nothing about the other seventy-two bytes, and the
     * twenty-three-byte {@code SEC-USR-FILLER} is exactly where an omission would hide.
     *
     * @param rows the 57-character rows expected to remain, in row order
     * @return one expectation per row
     */
    private static List<ParityCase.ExpectedRecord> finalState(List<String> rows) {
        List<ParityCase.ExpectedRecord> records = new ArrayList<>(rows.size());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            records.add(new ParityCase.ExpectedRecord(USRSEC, rowIndex, Map.of(),
                    padded(rows.get(rowIndex))));
        }
        return records;
    }

    /** The dataset-level expectation: {@code USRSEC} exists on the final-state channel at its width. */
    private static List<ParityCase.ExpectedDataset> datasets(int rowCount) {
        return List.of(new ParityCase.ExpectedDataset(USRSEC,
                ParityCase.DatasetChannel.FINAL_STATE, rowCount, USRSEC_RECORD_WIDTH));
    }

    /** The {@code USRSEC} seed, addressed by its binding key and never by its dataset name. */
    private static Map<String, ParityCase.DatasetInput> input(List<String> rows) {
        return Map.of(USRSEC, ParityCase.DatasetInput.ofRows(rows));
    }

    /** The 57-to-80 pad, bound to the dataset it repairs. */
    private static List<ParityCase.DatasetNormalisation> normalisations() {
        return List.of(new ParityCase.DatasetNormalisation(USRSEC,
                ParityCase.Normalisation.USRSEC_FILLER_PAD_57_TO_80));
    }

    /**
     * One declared invocation of the screen.
     *
     * @param aid      the {@code DFHAID} mnemonic of the key pressed, or {@code null} where
     *                 {@code EIBAID} is never read
     * @param fields   the {@code xxxI} items the terminal transmitted
     * @param commarea the communication area that arrived
     * @param forced   the outcomes the case forces, for arms the seeded rows cannot reach
     * @return the request
     */
    private static ParityCase.ScreenRequest screenRequest(String aid,
                                                          Map<String, String> fields,
                                                          Map<String, String> commarea,
                                                          Map<ParityCase.RepositoryOperation,
                                                                  ParityCase.ForcedOutcome> forced) {
        return new ParityCase.ScreenRequest(EIBCALEN_FULL, aid, PINNED_CLOCK, CHARSET, commarea,
                fields, forced);
    }

    /**
     * Assembles one case from its parts.
     *
     * @param caseId      {@code case01} through {@code case20}
     * @param description what the case exercises, and the source lines it exercises it at
     * @param seedRows    the {@code USRSEC} rows to seed, 57 characters each
     * @param request     the declared invocation
     * @param response    the expected response
     * @param finalRows   the rows {@code USRSEC} is expected to hold afterwards
     * @param messages    the lines expected to be emitted, in order
     * @return the case, validated by {@link ParityCase}'s own constructor
     */
    private static ParityCase parityCase(String caseId,
                                         String description,
                                         List<String> seedRows,
                                         ParityCase.ScreenRequest request,
                                         ParityCase.ExpectedResponse response,
                                         List<String> finalRows,
                                         List<ParityCase.EmittedMessage> messages) {
        return new ParityCase(PROGRAM, caseId, description, UNIT_KIND, input(seedRows), Map.of(),
                request, response, List.of(), finalState(finalRows), 0, messages, normalisations(),
                datasets(finalRows.size()));
    }

    // =================================================================================================
    // THE TWENTY CASES. Ordered by the paragraph they exercise: the two entry guards first, then
    // PROCESS-ENTER-KEY and its read, then DELETE-USER-INFO and its keyless delete, then the remaining
    // arms of EVALUATE EIBAID.
    // =================================================================================================

}
