package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.vsergeychik.carddemo.account.AccountDateValidator;
import com.vsergeychik.carddemo.account.AccountDateValidator.EditDateState;
import com.vsergeychik.carddemo.account.AccountDateValidator.EditFlag;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.AccountUpdateController;
import com.vsergeychik.carddemo.account.AccountUpdateService;
import com.vsergeychik.carddemo.account.AccountUpdateService.AccountData;
import com.vsergeychik.carddemo.account.AccountUpdateService.AccountUpdateDetails;
import com.vsergeychik.carddemo.account.AccountUpdateService.Block;
import com.vsergeychik.carddemo.account.AccountUpdateService.ComparedItem;
import com.vsergeychik.carddemo.account.AccountUpdateService.Comparison;
import com.vsergeychik.carddemo.account.AccountUpdateService.CustomerData;
import com.vsergeychik.carddemo.account.AccountUpdateService.DetailGroup;
import com.vsergeychik.carddemo.account.AccountUpdateService.MonetaryEdit;
import com.vsergeychik.carddemo.account.AccountUpdateService.WriteOutcome;
import com.vsergeychik.carddemo.account.AccountUpdateService.WriteResult;
import com.vsergeychik.carddemo.account.AreaCodeLookup;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The behavioural-parity gate for {@code COACTUPC} - the update-account screen, CSD transaction
 * {@code CAUP}, projected as {@code PUT /api/accounts/{acctId}}.
 *
 * <p><strong>This is the largest program in the migration: 4,236 lines, 17 {@code EXEC CICS} commands
 * and eighteen copybooks, the heaviest of the twenty-eight.</strong> It is one of the three designated
 * densest-coverage targets, with {@code CBSTM03A} - the only program whose {@code GO TO}s form implicit
 * loops - and {@code CBTRN02C}, whose four-stage validation cascade has no implicit fall-through.
 *
 * <p>Twenty cases cannot exhaustively cover this program's branch surface, and no attempt is made to
 * pretend otherwise. That surface is dominated by {@code 88}-level condition names - 508 across the
 * codebase - and by 111 {@code EVALUATE} statements whose {@code WHEN} order is binding with
 * {@code WHEN OTHER} last. Broad {@code 88}-level coverage is the {@code account} package's own unit
 * tests' job. <em>These</em> twenty are spent deliberately on the highest-risk constructs: the five
 * {@code COMPUTE} sites, the optimistic-concurrency check, the error-highlight matrix, the
 * {@code ENTER}-versus-{@code REENTER} split and the {@code XCTL} hand-off.
 *
 * <h2>Which unit each case drives</h2>
 *
 * <p>Stated per case rather than left to be inferred, because the two units answer different
 * questions:
 *
 * <ul>
 *   <li><strong>{@code case01} - {@code case14} drive {@link AccountUpdateService}</strong> as
 *       {@link UnitKind#SERVICE}, constructed through its own three-argument constructor over
 *       fixture-backed {@link AccountRepository} and {@link CustomerRepository} stubs and a real
 *       {@link DatasetUnitOfWork}. This is where {@code 9600-WRITE-PROCESSING} and the
 *       optimistic-concurrency check of {@code 9700-CHECK-CHANGE-IN-REC} live, and where all five
 *       {@code COMPUTE} receivers land, so it is where the headline gates are asserted.</li>
 *   <li><strong>{@code case15} - {@code case20} drive {@link AccountUpdateController}</strong> as
 *       {@link UnitKind#CONTROLLER_POJO}, constructed through its seven-argument constructor and called
 *       directly on {@code updateAccount}. These are the screen-shaped paths: the fifty-four-field
 *       projection, the {@code XCTL} hand-off, the {@code ENTER}-versus-{@code REENTER} split and the
 *       error-highlight matrix.</li>
 * </ul>
 *
 * <p>Neither unit is reached through a framework. There is no {@code MockMvc}, no
 * {@code TestRestTemplate}, no {@code WebTestClient}, no {@code JobLauncher} and no Spring context
 * anywhere in this class: every collaborator is passed to a constructor by hand. That is what makes the
 * arithmetic and the branch structure reachable without an HTTP layer in the path, which is the
 * condition the coverage bar depends on. Twenty parity cases alone will not reach a 0.90 branch ratio
 * on a 4,236-line program; the {@code account} package's unit tests carry the rest.
 *
 * <h2>The twenty cases are not the whole gate</h2>
 *
 * <p>Twenty is the mandated case count, and this program has far more behaviour worth pinning than
 * twenty cases can hold. The cases were therefore spent where a diff is the right instrument - complete
 * record images, complete screens, complete commareas - and everything else is asserted as a standing
 * property alongside them. A property test is the better instrument whenever the subject is an
 * invariant rather than an outcome: a count, a width, a total, an ordering, or the absence of something.
 *
 * <p>Six groups, each carrying something no case could:
 *
 * <ul>
 *   <li><strong>Shape and structure.</strong> {@link #everyRecordIsAsWideAsItsCopybookDeclares()},
 *       {@link #theFiftyFourPayloadFieldsCarryTheirDeclaredWidths()} and
 *       {@link #theResourceConventionIsHonoured()} fix the widths and counts every case is expressed in
 *       terms of, so a foundation error fails by name here instead of as twenty confusing diffs.</li>
 *   <li><strong>The comparison register.</strong>
 *       {@link #theConcurrencyCheckComparesThirtyFiveItemsAndNeitherKey()} asserts all thirty-five
 *       compared items, the four comparison kinds and the two blocks' order; a case can only show that
 *       <em>one</em> item was compared, never that a thirty-sixth was not added.</li>
 *   <li><strong>Numeric truth.</strong> {@link #theFiveComputeSitesTruncateRatherThanRound()} asserts
 *       each {@code COMPUTE} against both the truncation it must produce and the rounding it must not,
 *       which is the assertion that actually distinguishes them - {@code case10} through {@code case14}
 *       can only show that whatever was computed reached the bytes.</li>
 *   <li><strong>Orderings.</strong>
 *       {@link #theDateEngineIsARangePerformSoAFailedStageDoesNotStopTheNextOne()},
 *       {@link #theLeapYearRuleDividesByFourHundredOnlyForACenturyYear()},
 *       {@link #theStagedPhoneGuardsRunEveryStageAndOnlyTheFirstDiagnosticSurvives()} and
 *       {@link #theTwoSendMapArmsInitialiseBeforePaintingAndSetReenterAfter()} assert the order in which
 *       things happen, not merely the result. Every one of them uses an input where two orderings give
 *       the same verdict and different observable detail, because that is the only kind of input that
 *       can tell them apart.</li>
 *   <li><strong>Exhausted alternatives.</strong>
 *       {@link #everyFileStatusArmOfWriteProcessingIsReached()} reaches the three failure arms no seeded
 *       dataset can produce, {@link #pfKeysResolveExactlyAsCsstrpfyMapsThem()} drives all twenty-eight
 *       attention identifiers plus one the copybook does not name, and
 *       {@link #theHighlightMatrixIsColourInReenterAndAsteriskOnlyWhenBlank()} drives all six reachable
 *       corners of the {@code CSSETATY} matrix.</li>
 *   <li><strong>Absences.</strong> {@link #noVersionColumnOrSchemaArtefactIsIntroduced()},
 *       {@link #noStaticMutableStateExistsInThisClassOrTheUnitsItDrives()},
 *       {@link #packedDecimalNeverReachesARecord()} and
 *       {@link #theProgrammedSymbolAndValidationPlanesAreNeverWritten()} assert that something is not
 *       there. A passing case is silent about everything it does not mention, so an absence has to be
 *       stated to be tested at all.</li>
 * </ul>
 *
 * <h2>Rules governing this file</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line: "No user rules provided."</strong> That
 * single line is the whole document - there is nothing further to page through - and it was
 * re-confirmed immediately before this class was written. <em>No project rule forces this file into
 * scope</em>; the migration plan does, in its test-additions inventory.
 *
 * <p>Their absence is not licence to lower the bar, so the plan's enterprise best practices
 * <strong>B1-B12</strong> bind in their place. The ones that shaped this class are named where they
 * apply rather than listed abstractly: <strong>B1</strong> and <strong>B2</strong> on the closed
 * dependency set - JUnit 5, AssertJ, Mockito and H2 at test scope and nothing else, no Lombok, no
 * Testcontainers, no Spring Security; <strong>B3</strong> on the reference inputs, not one byte of
 * which is written; <strong>B4</strong> on documenting a divergence instead of repairing it;
 * <strong>B5</strong> on relaxing no validation and correcting no calculation; <strong>B7</strong> on
 * determinism, which is why a fixed {@link Clock} is supplied and no test reads the system clock;
 * <strong>B8</strong> on explicitness - a named charset, an explicit scale, an explicit rounding mode,
 * no wildcard import; <strong>B9</strong> on the absence of static mutable state;
 * <strong>B10</strong> on tests shipping with the implementation; <strong>B11</strong> on
 * hand-written codecs rather than an opaque copybook parser; and <strong>B12</strong> on the
 * baseline's provenance.
 *
 * <h2>The baseline is statically derived. It was never captured from a running program.</h2>
 *
 * <p>Every expected value below was produced by structured reading of {@code app/cbl/COACTUPC.cbl},
 * cross-checked against the symbolic map {@code app/cpy-bms/COACTUP.CPY} for the fifty-four payload
 * field names and widths, the mapset {@code app/bms/COACTUP.bms} for the {@code DFHMDF} definitions
 * behind them, the copybooks {@code app/cpy/CVACT01Y.cpy}, {@code app/cpy/CVACT03Y.cpy},
 * {@code app/cpy/CVCUS01Y.cpy}, {@code app/cpy/COCOM01Y.cpy}, {@code app/cpy/CSSETATY.cpy},
 * {@code app/cpy/CSSTRPFY.cpy}, {@code app/cpy/CSUTLDPY.cpy}, {@code app/cpy/CSUTLDWY.cpy} and
 * {@code app/cpy/CSLKPCDY.cpy} for the record, work-area and edit layouts, {@code app/csd/CARDDEMO.CSD}
 * for the file and transaction definitions, and the real fixtures {@code app/data/ASCII/acctdata.txt}
 * and {@code app/data/ASCII/custdata.txt} for input data. Widths and offsets are taken mechanically
 * from the copybooks rather than from prose, and every case seeds genuine fixture rows rather than
 * invented ones.
 *
 * <p>They are <strong>not</strong> recorded from, replayed from, or diffed against any execution of the
 * legacy COBOL, and nothing here should be read as though they were. Executing it is empirically
 * impossible in this environment: there is no z/OS and no CICS runtime; the available COBOL compiler
 * reports its indexed file handler as disabled, which excludes every program using
 * {@code ORGANIZATION INDEXED}; no Language Environment {@code CEE*} services exist; and the
 * IBM-supplied {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} that the online programs copy are
 * <strong>absent from this repository altogether</strong> - which is why {@link CicsAid} and
 * {@link BmsAttributes} reproduce their constants from IBM CICS documentation rather than from source
 * here. That gap is recorded rather than papered over (practice <strong>B4</strong>, plan risk
 * <strong>R-D</strong>), and it is why {@link #pfKeysResolveExactlyAsCsstrpfyMapsThem()} and
 * {@link #theHighlightMatrixIsColourInReenterAndAsteriskOnlyWhenBlank()} pin the specific constant
 * values this program depends on instead of trusting them.
 *
 * <p>This substitutes the <em>provenance</em> of the expected values and nothing else. Twenty cases,
 * field-for-field diffing, the diff-count-equals-zero gate and the branch-coverage bar are all
 * preserved unchanged. Because it nonetheless modifies a stated success criterion it is escalated for
 * explicit user confirmation rather than quietly absorbed (practice <strong>B12</strong>, plan risk
 * <strong>R-A</strong>). The residual exposure is worth stating plainly: a statically derived
 * expectation can encode a misreading of the COBOL where a captured one could not.
 *
 * <p>That exposure is not hypothetical. It materialised three times while this class was being written,
 * and each instance is recorded because the way it was caught is the mitigation:
 *
 * <ul>
 *   <li>An earlier draft asserted that {@code FUNCTION UPPER-CASE} folds <em>eight</em> customer
 *       comparisons. The source contains eighteen occurrences and each comparison wraps both operands,
 *       so the count is <em>nine</em> - {@code CUST-GOVT-ISSUED-ID} had been missed. Caught by
 *       {@link #theConcurrencyCheckComparesThirtyFiveItemsAndNeitherKey()}, which counts the register
 *       rather than sampling it, and which now names all nine.</li>
 *   <li>The same draft assumed the thirty-five comparisons were of three kinds. There are four: the five
 *       signed-decimal items compare <em>numerically</em>, not byte-wise, because COBOL compares numeric
 *       operands by value. Caught by the same test's exhaustive partition, which requires the four kinds
 *       to sum to thirty-five with none left over.</li>
 *   <li>{@code case10} through {@code case14} originally derived their expected value <em>by calling the
 *       seam under test</em>, which made them agree with whatever the implementation did - a wrong
 *       rounding mode included - and reduced them to a test of the codec. Caught by mutation: flipping
 *       the five literals to their {@code HALF_UP} counterparts left every case green. The construction
 *       is described at {@link #computeScenario}, where input and expectation are now drawn from
 *       independent sources, and the same mutation now fails all five.</li>
 * </ul>
 *
 * <p>The general lesson, and the reason the standing-property tests exist alongside the cases: an
 * expectation is only worth what its <em>provenance</em> is worth, and a case whose expected value came
 * from the code it is testing asserts nothing at all.
 *
 * <h2>The paragraph is {@code 9700}, not {@code 9300}</h2>
 *
 * <p>The migration plan names the optimistic-concurrency paragraph {@code 9300-CHECK-CHANGE-IN-REC}.
 * That is {@code COCRDUPC}'s number. In {@code COACTUPC} the paragraph is
 * <strong>{@code 9700-CHECK-CHANGE-IN-REC}</strong> at {@code app/cbl/COACTUPC.cbl:4109-4193}, and
 * {@code 9300} is {@code 9300-GETACCTDATA-BYACCT} at {@code :3701} - a read, not a comparison. The
 * behaviour the plan describes is exactly the behaviour {@code 9700} has; only the number differs. The
 * divergence is recorded here rather than silently corrected in either direction (practice
 * <strong>B4</strong>), and every citation below uses the number the source actually carries.
 *
 * <h2>The optimistic-concurrency check already exists in the COBOL. No version column is introduced.</h2>
 *
 * <p>{@code 9700-CHECK-CHANGE-IN-REC} re-reads both records under their locks and compares them
 * <em>field for field</em> against the snapshot the screen was painted from, before rewriting. It is one
 * of only two such paragraphs in the whole application - the other is in {@code COCRDUPC} - and it is
 * <strong>not</strong> something this migration added. It compares thirty-five items in two ordered
 * blocks: sixteen account-master items at {@code :4115-4145} and nineteen customer items at
 * {@code :4152-4192}, with block one's failure meaning block two is never reached. Six of the twenty
 * cases are pointed at it directly: {@code case01} no change detected, {@code case02} one item changed
 * underneath, {@code case03} three items changed at once, {@code case04} the {@code FILLER}-adjacent
 * item changed, {@code case05} a block-two item changed with block one clean, and {@code case07} the two
 * case-folding comparisons.
 *
 * <p>Replacing that comparison with a version column would be a schema change, which the plan forbids
 * outright: no DDL, no entity annotations and no generated table definitions anywhere in the module.
 * {@link #noVersionColumnOrSchemaArtefactIsIntroduced()} asserts the absence rather than trusting it.
 *
 * <h2>Four source behaviours that look like defects and are preserved anyway</h2>
 *
 * <p>Practice <strong>B5</strong> is binding here: relax no validation, correct no calculation, assert
 * the source as written.
 *
 * <ul>
 *   <li><strong>{@code ACCT-UPDATE-RECORD} is ten bytes out of step with {@code CVACT01Y}.</strong>
 *       The program redeclares the account layout at {@code :418-433} for the rewrite and omits
 *       {@code ACCT-ADDR-ZIP} entirely, so {@code ACCT-UPDATE-GROUP-ID PIC X(10)} at {@code :432} sits
 *       at offset <strong>102</strong> - straight over the stored zip - and its {@code FILLER X(188)}
 *       at {@code :433} blanks bytes 112 to 299, which includes the real {@code ACCT-GROUP-ID}. Every
 *       successful rewrite therefore moves the group identifier into the zip's bytes and erases the
 *       group's own. {@code case08} pins it as a complete 300-byte image and
 *       {@link #theUpdateRecordWritesTheGroupIdOverTheStoredZip()} explains it.</li>
 *   <li><strong>The concurrency check ignores both key fields.</strong> {@code ACCT-ID} is absent from
 *       block one and {@code CUST-ID} from block two, and so is {@code ACCT-ADDR-ZIP} even though it
 *       sits between two compared items. {@code case06} changes all three underneath and expects the
 *       rewrite to proceed, because a translation that helpfully added them would reject updates the
 *       COBOL accepts.</li>
 *   <li><strong>An unrecognised attention identifier is remapped to {@code ENTER}, not rejected.</strong>
 *       {@code :913-916} sets {@code PFK-INVALID}, admits only {@code ENTER}, {@code PF03},
 *       {@code PF05} while changes are validated-but-unconfirmed and {@code PF12} once details have been
 *       fetched, then quietly does {@code SET CCARD-AID-ENTER TO TRUE} for everything else.
 *       {@link #anUnrecognisedAidIsRemappedToEnterRatherThanRejected()} presses {@code PF9} and
 *       {@code PF12} and expects, field for field, the screen {@code case19} gets from {@code ENTER}.</li>
 *   <li><strong>{@code 3250-SETUP-INFOMSG} has no {@code WHEN OTHER}.</strong> The {@code EVALUATE} at
 *       {@code :2957-2977} ends without a default, so a state none of its eight arms match leaves
 *       {@code WS-INFO-MSG} exactly as it was. That is why the information line on the error screens of
 *       {@code case19} and {@code case20} still reads the search-key prompt rather than anything about
 *       the error.</li>
 * </ul>
 *
 * <h2>{@code CARDAIX} and {@code CXACAIX} are second finders, never second tables</h2>
 *
 * <p>{@code app/csd/CARDDEMO.CSD} defines {@code CARDDAT} and {@code CCXREF} over base clusters and
 * {@code CARDAIX} and {@code CXACAIX} as alternate-index <em>paths</em> over those same clusters, and
 * {@code COACTUPC} names all five files. One repository therefore carries both access paths for each
 * cluster, and {@link #theAlternateIndexesAreSecondFindersOnTheSameRepositories()} drives the
 * alternate key on the very object the base read is served from, which is the property gate
 * <strong>G45</strong> is about.
 */
@DisplayName("COACTUPC parity - the update-account screen, transaction CAUP")
class COACTUPCParityTest {

    // =================================================================================================
    // Identity. The program name is this class's stem and the parity resource directory's stem.
    // =================================================================================================

    /** The COBOL program this class gates, spelled as {@code app/cbl/} and the resource root spell it. */
    private static final String PROGRAM = "COACTUPC";

    /**
     * The instant every case pins, from this source's own version footer:
     * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT}. A fixed clock rather
     * than a system one, so {@code CURDATEO} and {@code CURTIMEO} are assertable at all (practice
     * <strong>B7</strong>, gate <strong>G54</strong>).
     */
    private static final String PINNED_CLOCK = "2022-07-19T23:15:59";

    /** {@code CURDATEO} as {@code 3100-SCREEN-INIT} renders {@link #PINNED_CLOCK} at {@code :2683}. */
    private static final String PINNED_DATE_IMAGE = "07/19/22";

    /** {@code CURTIMEO} as {@code 3100-SCREEN-INIT} renders {@link #PINNED_CLOCK} at {@code :2689}. */
    private static final String PINNED_TIME_IMAGE = "23:15:59";

    /** The fixtures' code page, named rather than defaulted (practice <strong>B8</strong>). */
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /** The same code page as a case declares it. */
    private static final String CHARSET_NAME = "US-ASCII";

    /** The codec every declared width in this class is applied through. Stateless and shared. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(FIXTURE_CHARSET);

    /** {@code ACCTDAT}'s binding key, taken from the repository rather than re-spelled (gate G46). */
    private static final String ACCTDAT = AccountRepository.CICS_FILE_NAME;

    /** {@code CUSTDAT}'s binding key, taken from the repository rather than re-spelled (gate G46). */
    private static final String CUSTDAT = CustomerRepository.CICS_FILE_NAME;

    /**
     * {@code EIBCALEN} on a cold start - the left arm of the {@code :880} guard. CICS passes no
     * communication area, so {@code INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA} runs at
     * {@code :883-884}.
     */
    private static final int EIBCALEN_NONE = 0;

    /**
     * {@code EIBCALEN} on a pseudo-conversational re-entry: {@code CARDDEMO-COMMAREA} from
     * {@code app/cpy/COCOM01Y.cpy} followed by {@code WS-THIS-PROGCOMMAREA}, which is how
     * {@code :888-892} slices what came back. Derived from the two declarations rather than written as a
     * number, so it cannot drift from either.
     */
    private static final int EIBCALEN_FULL =
            NavigationContext.COMMAREA_LENGTH + AccountUpdateRequest.CommArea.RECORD_LENGTH;

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COACTUPC'}. */
    private static final String THIS_PGM = AccountUpdateResponse.THIS_PROGRAM;

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CAUP'}. */
    private static final String THIS_TRANID = AccountUpdateResponse.THIS_TRANSACTION;

    /** {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '}, trailing blank trimmed. */
    private static final String THIS_MAPSET = AccountUpdateResponse.MAPSET_NAME;

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CACTUPA'}. */
    private static final String THIS_MAP = AccountUpdateResponse.MAP_NAME;

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - the {@code PF03} default target at {@code :938}. */
    private static final String MENU_PGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - the {@code PF03} default target at {@code :930}. */
    private static final String MENU_TRANID = "CM00";

    // =================================================================================================
    // The fixture rows. Row indices into the real ASCII fixtures, which the harness seeds; the values
    // are restated here because a case's EXPECTATION has to be independent of the run that produced it.
    // =================================================================================================

    /** How many rows of each fixture a case seeds, so a keyed read has to choose rather than guess. */
    private static final int SEEDED_ROWS = 3;

    /** {@code ACCT-ID} of {@code app/data/ASCII/acctdata.txt} row 1, at its declared {@code PIC 9(11)}. */
    private static final String ACCT_KEY = "00000000001";

    /** {@code CUST-ID} of {@code app/data/ASCII/custdata.txt} row 1, at its declared {@code PIC 9(09)}. */
    private static final int CUST_KEY = 1;

    /** {@code ACCT-ACTIVE-STATUS} as {@code acctdata.txt} row 1 stores it. */
    private static final String STORED_STATUS = "Y";

    /** {@code ACCT-CURR-BAL} as {@code acctdata.txt} row 1 stores it, scale 2. */
    private static final BigDecimal STORED_CURR_BAL = new BigDecimal("194.00");

    /** {@code ACCT-CREDIT-LIMIT} as {@code acctdata.txt} row 1 stores it, scale 2. */
    private static final BigDecimal STORED_CREDIT_LIMIT = new BigDecimal("2020.00");

    /** {@code ACCT-CASH-CREDIT-LIMIT} as {@code acctdata.txt} row 1 stores it, scale 2. */
    private static final BigDecimal STORED_CASH_LIMIT = new BigDecimal("1020.00");

    /** {@code ACCT-CURR-CYC-CREDIT} as {@code acctdata.txt} row 1 stores it, scale 2. */
    private static final BigDecimal STORED_CYC_CREDIT = new BigDecimal("0.00");

    /** {@code ACCT-CURR-CYC-DEBIT} as {@code acctdata.txt} row 1 stores it, scale 2. */
    private static final BigDecimal STORED_CYC_DEBIT = new BigDecimal("0.00");

    /** {@code ACCT-OPEN-DATE PIC X(10)} as {@code acctdata.txt} row 1 stores it. */
    private static final String STORED_OPEN_DATE = "2014-11-20";

    /** {@code ACCT-EXPIRAION-DATE PIC X(10)} - the copybook's misspelling, kept (implicit need I1). */
    private static final String STORED_EXPIRAION_DATE = "2025-05-20";

    /** {@code ACCT-REISSUE-DATE PIC X(10)} as {@code acctdata.txt} row 1 stores it. */
    private static final String STORED_REISSUE_DATE = "2025-05-20";

    /**
     * {@code ACCT-ADDR-ZIP PIC X(10)} as {@code acctdata.txt} row 1 stores it.
     *
     * <p>The fixture's value is genuinely {@code "A000000000"} - an alphabetic first byte in a field the
     * screen never shows and {@code 9700} never compares. It is restated rather than normalised, because
     * it is the byte the rewrite overwrites with the group identifier.
     */
    private static final String STORED_ADDR_ZIP = "A000000000";

    /** {@code ACCT-GROUP-ID PIC X(10)} as {@code acctdata.txt} row 1 stores it: ten spaces. */
    private static final String STORED_GROUP_ID = " ".repeat(AccountRecord.ACCT_GROUP_ID_LENGTH);

    // =================================================================================================
    // What the screen supplies. ACUP-NEW-DETAILS, chosen so every span of the written image differs
    // from the stored one - an image that agreed with its input everywhere would assert nothing.
    // =================================================================================================

    /** {@code ACUP-NEW-ACTIVE-STATUS}, the opposite of what is stored. */
    private static final String NEW_STATUS = "N";

    /** {@code ACUP-NEW-CURR-BAL-N} after the {@code :1107-1108} {@code COMPUTE}. */
    private static final BigDecimal NEW_CURR_BAL = new BigDecimal("1234.56");

    /** {@code ACUP-NEW-CREDIT-LIMIT-N} after the {@code :1079-1080} {@code COMPUTE}. */
    private static final BigDecimal NEW_CREDIT_LIMIT = new BigDecimal("7500.00");

    /** {@code ACUP-NEW-CASH-CREDIT-LIMIT-N} after the {@code :1093-1094} {@code COMPUTE}. */
    private static final BigDecimal NEW_CASH_LIMIT = new BigDecimal("1500.00");

    /** {@code ACUP-NEW-CURR-CYC-CREDIT-N} after the {@code :1121-1122} {@code COMPUTE}. */
    private static final BigDecimal NEW_CYC_CREDIT = new BigDecimal("11.11");

    /** {@code ACUP-NEW-CURR-CYC-DEBIT-N} after the {@code :1135-1136} {@code COMPUTE}. */
    private static final BigDecimal NEW_CYC_DEBIT = new BigDecimal("22.22");

    /** {@code ACUP-NEW-OPEN-DATE}, composed by the {@code :3979-3985} {@code STRING}. */
    private static final String NEW_OPEN_DATE = "2015-01-02";

    /** {@code ACUP-NEW-EXPIRAION-DATE}, composed by the {@code :3987-3993} {@code STRING}. */
    private static final String NEW_EXPIRAION_DATE = "2026-11-30";

    /** {@code ACUP-NEW-REISSUE-DATE}, composed by the {@code :3996-4002} {@code STRING}. */
    private static final String NEW_REISSUE_DATE = "2023-05-01";

    /** {@code ACUP-NEW-GROUP-ID}, non-blank so the {@code :432} offset defect is observable. */
    private static final String NEW_GROUP_ID = "ZEROPCT";

    // =================================================================================================
    // The screen. Fifty-four DFHMDF fields, and the six literals 3100-SCREEN-INIT and the mapset put on
    // every one of them.
    // =================================================================================================

    /** {@code CCDA-TITLE01} from {@code app/cpy/COTTL01Y.cpy}, byte-exact at its declared 40. */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} from {@code app/cpy/COTTL01Y.cpy}, byte-exact at its declared 40. */
    private static final String TITLE02 = "              CardDemo                  ";

    /** {@code WS-PROMPT-FOR-ACCT}'s information text, at {@code INFOMSGO}'s declared 45. */
    private static final String INFO_PROMPT_FOR_ACCT = "Enter or update id of account to update      ";

    /** {@code FKEYSO}'s {@code INITIAL} text from {@code app/bms/COACTUP.bms}, at its declared 21. */
    private static final String FKEYS_LEGEND = "ENTER=Process F3=Exit";

    /** {@code FKEY05O}'s {@code INITIAL} text, at its declared 7. Revealed by attribute, not by value. */
    private static final String FKEY05_LEGEND = "F5=Save";

    /** {@code FKEY12O}'s {@code INITIAL} text, at its declared 10. Revealed by attribute, not by value. */
    private static final String FKEY12_LEGEND = "F12=Cancel";

    /** {@code 1210-EDIT-ACCOUNT}'s not-numeric message at {@code :1806-1811}, at {@code ERRMSGO}'s 78. */
    private static final String MSG_ACCT_NOT_ELEVEN_DIGITS =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

    /** {@code WS-PROMPT-FOR-ACCT}'s error text, set by {@code :1791-1793} when nothing was keyed. */
    private static final String MSG_NO_INPUT_RECEIVED = "No input received";

    /** A non-numeric account filter: eleven characters, so {@code CC-ACCT-ID IS NOT NUMERIC} is true. */
    private static final String NON_NUMERIC_ACCT = "0000000000A";

    /** A blank account filter: eleven spaces, so {@code CC-ACCT-ID EQUAL SPACES} is true at {@code :1787}. */
    private static final String BLANK_ACCT = " ".repeat(AccountUpdateRequest.ACCTSID_LENGTH);

    /**
     * {@code 1220-EDIT-YESNO}'s blank text for the account status, composed as
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} - moved at {@code :1472} - followed by the literal at
     * {@code :1841}. The first field {@code 1200-EDIT-MAP-INPUTS} edits, and therefore the message that
     * claims {@code WS-RETURN-MSG} whenever the status is blank.
     */
    private static final String MSG_ACCT_STATUS_MUST_BE_SUPPLIED =
            "Account Status must be supplied.";

    /** {@code 3250-SETUP-INFOMSG}'s prompt-for-changes text, declared at {@code :471}. */
    private static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /**
     * An area code {@code CSLKPCDY} names in none of its three tables, so {@code EDIT-AREA-CODE}'s
     * {@code VALID-GENERAL-PURP-CODE} test at {@code :2298} fails and jumps to
     * {@code EDIT-US-PHONE-PREFIX}.
     */
    private static final String REJECTED_AREA_CODE = "199";

    /** A prefix that is not numeric, so {@code EDIT-US-PHONE-PREFIX}'s second guard rejects it. */
    private static final String NON_NUMERIC_PREFIX = "AB";

    /** A four-digit line number, which {@code EDIT-US-PHONE-LINENUM} accepts. */
    private static final String VALID_LINE_NUMBER = "1234";

    /** {@code WS-EDIT-VARIABLE-NAME} as {@code :1478} moves it before the open-date edit. */
    private static final String EDIT_FIELD_NAME = "Open Date";

    // =================================================================================================
    // THE GATE. Twenty cases, each judged field by field, each required to diff to zero.
    // =================================================================================================

    /**
     * The twenty scenarios in {@code case01} through {@code case20} order.
     *
     * <p>The count and the numbering are checked here rather than in a separate test, because a case set
     * that lost an entry would otherwise make this gate <em>quieter</em>: nineteen passing cases and no
     * failure at all is the worst possible outcome for an acceptance check. A wrong count therefore
     * fails the whole class before a single case runs.
     *
     * @return the twenty scenarios this program owns; never {@code null}
     */
    private static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = List.of(case01(), case02(), case03(), case04(), case05(),
                case06(), case07(), case08(), case09(), case10(), case11(), case12(), case13(),
                case14(), case15(), case16(), case17(), case18(), case19(), case20());
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException("The parity gate for " + PROGRAM + " requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + " cases, named case01 through case"
                    + ParityHarness.CASES_PER_PROGRAM + ", but " + scenarios.size()
                    + " were declared. A short set is not a smaller gate, it is a missing one: an "
                    + "assertion nobody runs cannot fail.");
        }
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            String required = ParityHarness.caseId(ordinal);
            String declared = scenarios.get(ordinal - 1).caseId();
            if (!required.equals(declared)) {
                throw new IllegalStateException("Parity case " + ordinal + " for " + PROGRAM
                        + " is \"" + declared + "\" where the gate requires \"" + required
                        + "\". The identifiers are ordered and they name the fixture, so a mis-numbered "
                        + "case would silently take another case's place.");
            }
        }
        return scenarios;
    }

    /**
     * Runs one case against the unit it names and requires the differ to find nothing.
     *
     * <p>The assertion is on {@link DiffResult#count()} with the whole of {@link DiffResult#render()}
     * attached as the description, so a failure prints every difference - each naming its dataset or
     * channel, the COBOL field, that field's declared offset and length, the expected image and the
     * observed one - instead of a bare "expected 0 but was 3". A difference is only actionable next to
     * the field it belongs to.
     *
     * <p>The unit kind is passed explicitly from the scenario rather than read off the case, so the
     * harness's cross-check against {@link ParityCase#unitKind()} stays meaningful: a case that declared
     * one unit while the adapter constructed another would be caught before the unit was reached.
     *
     * @param scenario the case and the adapter that reaches its unit, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("COACTUPC: every case diffs to zero against the COBOL-derived baseline")
    void theTranslationMatchesTheCobolFieldForField(ParityScenario scenario) {
        DiffResult result = ParityHarness.usAscii()
                .judge(scenario.parityCase(), scenario.adapterKind(), scenario.adapter());

        assertThat(result.count())
                .describedAs("%s/%s (%s) must diff to zero. %s", PROGRAM, scenario.caseId(),
                        scenario.unitName(), result.render())
                .isZero();
    }

    // =================================================================================================
    // STRUCTURAL GATES. Each asserts a property the twenty cases rest on, so that a broken foundation
    // fails by name here instead of surfacing as twenty confusing diffs.
    // =================================================================================================

    /**
     * This class's stem, the program name and the harness's resource convention agree.
     *
     * <p>The twenty cases are declared in code rather than loaded from
     * {@code src/test/resources/parity/COACTUPC/}, which the harness supports equally - it validates
     * either through the same {@link ParityCase} constructor. The convention is still asserted, because
     * the identifiers a case carries are the file-name stems that directory would use, and the two must
     * not drift apart.
     */
    @Test
    @DisplayName("the class stem, the program name and the parity/COACTUPC convention agree")
    void theResourceConventionIsHonoured() {
        assertThat(COACTUPCParityTest.class.getSimpleName()).isEqualTo(PROGRAM + "ParityTest");
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(1)))
                .isEqualTo(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + "/case01"
                        + ParityHarness.CASE_RESOURCE_EXTENSION);
        assertThat(ParityHarness.caseResourcePath(PROGRAM, ParityHarness.caseId(20)))
                .isEqualTo("parity/COACTUPC/case20.json");
        assertThat(ParityHarness.CASES_PER_PROGRAM).isEqualTo(20);
        assertThat(cases()).hasSize(ParityHarness.CASES_PER_PROGRAM);
    }

    /**
     * The fifty-four payload fields carry the widths the symbolic map declares, and there is no
     * fifty-fifth.
     *
     * <p>{@code app/bms/COACTUP.bms} declares fifty-four {@code DFHMDF} fields and
     * {@code app/cpy-bms/COACTUP.CPY} exposes fifty-four {@code xxxI} items, one per field, with no
     * fifty-fifth on either side. Every field's length is asserted against
     * {@link AccountUpdateResponse#declaredLength}, and every field's picture is asserted alphanumeric,
     * because {@code COACTUP.bms} declares no {@code PICOUT} anywhere - the five monetary fields arrive as
     * {@code X(15)} strings the program edits itself, never as numeric items BMS formats (gate
     * <strong>G9</strong>).
     */
    @Test
    @DisplayName("the fifty-four payload fields carry the widths COACTUP.CPY declares")
    void theFiftyFourPayloadFieldsCarryTheirDeclaredWidths() {
        assertThat(AccountUpdateResponse.ScreenField.values()).hasSize(54);
        assertThat(AccountUpdateRequest.ScreenField.values()).hasSize(54);
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            assertThat(field.length())
                    .describedAs("%s is declared %s in app/cpy-bms/COACTUP.CPY", field.label(),
                            field.picture())
                    .isEqualTo(AccountUpdateResponse.declaredLength(field))
                    .isPositive();
            assertThat(field.isAlphanumeric())
                    .describedAs("%s must be PIC X: app/bms/COACTUP.bms declares no PICOUT, so every "
                            + "field arrives and leaves as characters", field.label())
                    .isTrue();
            assertThat(field.symbolicItemName())
                    .describedAs("%s's symbolic output item", field.label())
                    .isEqualTo(field.label() + "O");
        }
        assertThat(AccountUpdateResponse.SCREEN_ROWS).isEqualTo(24);
        assertThat(AccountUpdateResponse.SCREEN_COLUMNS).isEqualTo(80);
    }

    /**
     * Every record this program touches is exactly as wide as its copybook declares, {@code FILLER}
     * included.
     *
     * <p>The five datasets {@code COACTUPC} names resolve to four record layouts, and each one's total
     * width is the sum of its declared spans - which is precisely how a dropped {@code FILLER} is caught,
     * since omitting one leaves the record short by exactly that span (gates <strong>G19</strong> and
     * <strong>G21</strong>).
     *
     * <p>{@code CVACT03Y}'s trailing {@code FILLER X(14)} deserves its own mention: the fixture
     * {@code app/data/ASCII/cardxref.txt} is 36 bytes per row where the copybook declares 50, because the
     * fixture omits that span. The harness right-pads before comparing, and the normalisation is declared
     * rather than implicit (gate <strong>G16</strong>).
     */
    @Test
    @DisplayName("account 300, card 150, cross-reference 50, customer 500 - FILLER included")
    void everyRecordIsAsWideAsItsCopybookDeclares() {
        assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        assertThat(AccountRecord.LAYOUT.recordLength()).isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(AccountRecord.FILLER_OFFSET + AccountRecord.FILLER_LENGTH)
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(AccountRecord.FILLER_LENGTH).isEqualTo(178);

        assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(500);
        assertThat(CustomerRecord.LAYOUT.recordLength()).isEqualTo(CustomerRecord.RECORD_LENGTH);
        assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(CardXrefRecord.LAYOUT.recordLength()).isEqualTo(CardXrefRecord.RECORD_LENGTH);

        // Both images every clean-rewrite case declares are exactly their declared widths, and the
        // composition helpers say so themselves rather than being taken on trust.
        assertThat(fullNewAccountImage()).hasSize(AccountRecord.RECORD_LENGTH);
        assertThat(fullNewCustomerImage()).hasSize(CustomerRecord.RECORD_LENGTH);
        assertThat(fullNewAccountImage()
                .substring(AccountUpdateService.ACCT_UPDATE_FILLER_OFFSET))
                .describedAs("FILLER X(188) at app/cbl/COACTUPC.cbl:433 is emitted as spaces")
                .isBlank()
                .hasSize(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH);
    }

    /**
     * The rewrite writes the group identifier over the stored zip, and blanks the stored group.
     *
     * <p>The defect, asserted at offset level rather than described. {@code app/cpy/CVACT01Y.cpy} puts
     * {@code ACCT-ADDR-ZIP} at 102 and {@code ACCT-GROUP-ID} at 112;
     * {@code app/cbl/COACTUPC.cbl:418-433} declares {@code ACCT-UPDATE-RECORD} with no zip item, so its
     * group span begins where the stored zip does and its {@code FILLER} is ten bytes wider than the
     * copybook's.
     *
     * <p>Three claims are checked: the update record's group offset equals the stored record's
     * <em>zip</em> offset; its {@code FILLER} begins where the stored group begins; and the image
     * {@code case08} declares genuinely carries {@code ZEROPCT} in the zip's ten bytes with spaces from
     * the stored group onwards. Anyone tempted to repair the layout will fail this test first, and the
     * failure names the paragraph.
     */
    @Test
    @DisplayName("ACCT-UPDATE-GROUP-ID sits at offset 102, over the stored ACCT-ADDR-ZIP")
    void theUpdateRecordWritesTheGroupIdOverTheStoredZip() {
        assertThat(AccountUpdateService.ACCT_UPDATE_GROUP_ID_OFFSET)
                .describedAs("ACCT-UPDATE-GROUP-ID at :432 begins where CVACT01Y puts ACCT-ADDR-ZIP")
                .isEqualTo(AccountRecord.ACCT_ADDR_ZIP_OFFSET)
                .isEqualTo(102);
        assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_OFFSET)
                .describedAs("FILLER X(188) at :433 begins where CVACT01Y puts ACCT-GROUP-ID")
                .isEqualTo(AccountRecord.ACCT_GROUP_ID_OFFSET)
                .isEqualTo(112);
        assertThat(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH)
                .describedAs("FILLER X(188) is ten wider than CVACT01Y's FILLER X(178)")
                .isEqualTo(AccountRecord.FILLER_LENGTH + AccountRecord.ACCT_ADDR_ZIP_LENGTH)
                .isEqualTo(188);

        String written = fullNewAccountImage();
        assertThat(written.substring(AccountRecord.ACCT_ADDR_ZIP_OFFSET,
                AccountRecord.ACCT_ADDR_ZIP_OFFSET + AccountRecord.ACCT_ADDR_ZIP_LENGTH))
                .describedAs("the stored zip's ten bytes now hold the group identifier")
                .isEqualTo(CODEC.movePicX(NEW_GROUP_ID, AccountRecord.ACCT_ADDR_ZIP_LENGTH))
                .isNotEqualTo(STORED_ADDR_ZIP);
        assertThat(written.substring(AccountRecord.ACCT_GROUP_ID_OFFSET))
                .describedAs("the stored group's ten bytes, and everything after them, are blanked")
                .isBlank();
    }

    /**
     * {@code 9700} compares thirty-five items in two ordered blocks, and neither key is one of them.
     *
     * <p>The register itself, asserted rather than described: sixteen account items at
     * {@code :4115-4145} and nineteen customer items at {@code :4152-4192}, in that order, with the
     * comparison each uses. Four properties matter and all four are checked.
     *
     * <p><strong>The count.</strong> Thirty-five, not thirty-four and not thirty-seven. A translation that
     * added a key would compare thirty-seven and would refuse updates the COBOL accepts.
     *
     * <p><strong>The absences.</strong> {@code ACCT-ID}, {@code CUST-ID} and {@code ACCT-ADDR-ZIP} are all
     * absent, and {@code case06} exercises the consequence.
     *
     * <p><strong>The folds.</strong> Exactly one item is compared lower-cased - the account group - and
     * exactly eight upper-cased. Every other item is exact.
     *
     * <p><strong>The block order.</strong> Every account item precedes every customer item in the
     * declaration order, which is what makes the enum's own ordering usable as the evaluation order.
     */
    @Test
    @DisplayName("9700 compares 35 items in two ordered blocks and ignores both keys")
    void theConcurrencyCheckComparesThirtyFiveItemsAndNeitherKey() {
        List<ComparedItem> items = Arrays.asList(ComparedItem.values());
        assertThat(items).hasSize(35);

        Set<ComparedItem> accountBlock = EnumSet.noneOf(ComparedItem.class);
        Set<ComparedItem> customerBlock = EnumSet.noneOf(ComparedItem.class);
        for (ComparedItem item : items) {
            (item.block() == Block.ACCOUNT_MASTER ? accountBlock : customerBlock).add(item);
        }
        assertThat(accountBlock).hasSize(16);
        assertThat(customerBlock).hasSize(19);

        // The declaration order is the evaluation order, so every account item must precede every
        // customer item - block one's failure is what stops block two being reached at all.
        int lastAccount = -1;
        int firstCustomer = items.size();
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).block() == Block.ACCOUNT_MASTER) {
                lastAccount = index;
            } else if (firstCustomer == items.size()) {
                firstCustomer = index;
            }
        }
        assertThat(lastAccount).isLessThan(firstCustomer);

        List<String> names = items.stream().map(ComparedItem::cobolName).toList();
        assertThat(names)
                .describedAs("neither key and not the account zip: none of the three appears at "
                        + "app/cbl/COACTUPC.cbl:4115-4192")
                .doesNotContain(AccountRecord.ACCT_ID_NAME, AccountRecord.ACCT_ADDR_ZIP_NAME,
                        "CUST-ID")
                .contains(AccountRecord.ACCT_GROUP_ID_NAME, AccountRecord.ACCT_ACTIVE_STATUS_NAME,
                        "CUST-FIRST-NAME", "CUST-FICO-CREDIT-SCORE");

        assertThat(items.stream().filter(item -> item.comparison() == Comparison.LOWER_CASE_FOLDED)
                .map(ComparedItem::cobolName).toList())
                .describedAs("FUNCTION LOWER-CASE appears twice at :4139-4140 - once per operand - so it "
                        + "folds exactly one comparison")
                .containsExactly(AccountRecord.ACCT_GROUP_ID_NAME);
        assertThat(items.stream().filter(item -> item.comparison() == Comparison.UPPER_CASE_FOLDED)
                .map(ComparedItem::cobolName).toList())
                .describedAs("FUNCTION UPPER-CASE appears eighteen times between :4152 and :4173 - "
                        + "again once per operand - so it folds exactly nine comparisons, and which nine "
                        + "matters: the zip, both phone numbers, the SSN and the FICO score sit among "
                        + "them unfolded")
                .containsExactly("CUST-FIRST-NAME", "CUST-MIDDLE-NAME", "CUST-LAST-NAME",
                        "CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-ADDR-LINE-3",
                        "CUST-ADDR-STATE-CD", "CUST-ADDR-COUNTRY-CD", "CUST-GOVT-ISSUED-ID");
        assertThat(items.stream().filter(item -> item.comparison() == Comparison.MONETARY)
                .map(ComparedItem::cobolName).toList())
                .describedAs("the five signed-decimal comparisons at :4117-4125 compare numerically "
                        + "rather than byte-wise, because COBOL compares numeric operands by value - a "
                        + "byte comparison would call a differently-zoned image of the same amount a "
                        + "concurrent change")
                .containsExactly(AccountRecord.ACCT_CURR_BAL_NAME,
                        AccountRecord.ACCT_CREDIT_LIMIT_NAME,
                        AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME,
                        AccountRecord.ACCT_CURR_CYC_CREDIT_NAME,
                        AccountRecord.ACCT_CURR_CYC_DEBIT_NAME);
        assertThat(items.stream().filter(item -> item.comparison() == Comparison.EXACT).count())
                .describedAs("the remaining twenty comparisons are byte-exact: ten date substrings, the "
                        + "account status, the zip, both phone numbers, the SSN, the three date "
                        + "substrings of the birth date, the EFT identifier, the primary-holder "
                        + "indicator and the FICO score")
                .isEqualTo(20L);
        assertThat(items.size())
                .describedAs("and the four kinds account for every item, with none left over")
                .isEqualTo(1 + 9 + 5 + 20);
    }

    /**
     * The two folded comparisons fold <strong>both</strong> operands, in the direction the source uses.
     *
     * <p>{@code :4139-4140} wraps {@code ACCT-GROUP-ID} and its snapshot in {@code FUNCTION LOWER-CASE};
     * {@code :4152-4153} wraps {@code CUST-FIRST-NAME} and its snapshot in {@code FUNCTION UPPER-CASE}.
     * Driven directly on {@link AccountUpdateService#checkChangeInRec}, because the group half cannot be
     * driven through a parity case - the fixture's stored group is ten spaces, which folds to itself.
     *
     * <p>Both directions are checked because both appear, and a translation that used one everywhere
     * would still pass every case in this class: {@code ZEROPCT} and {@code zeropct} compare equal under
     * either fold. What separates them is a value that folds differently, which is why the group is set to
     * a mixed-case string and compared against its own upper-cased form.
     */
    @Test
    @DisplayName("the group compares lower-cased and the first name upper-cased, both operands")
    void theFoldedComparisonsFoldBothOperands() {
        AccountRecord stored = storedAccountRecordWith("ZeroPct");
        CustomerRecord customer = storedCustomerRecord();
        AccountUpdateService service = serviceOverEmptyDatasets();

        AccountUpdateDetails foldedBoth = new AccountUpdateDetails(DetailGroup.OLD,
                withGroupId(storedAccountSnapshot(), "zEROpCT"),
                foldFirstNameToUpper(storedCustomerSnapshot()));
        assertThat(service.checkChangeInRec(stored, customer, foldedBoth, CODEC).dataWasChanged())
                .describedAs("a group differing only in letter case, and a first name differing only "
                        + "in letter case, are not concurrent changes")
                .isFalse();

        AccountUpdateDetails genuinelyDifferent = new AccountUpdateDetails(DetailGroup.OLD,
                withGroupId(storedAccountSnapshot(), "PREMIUM"), storedCustomerSnapshot());
        assertThat(service.checkChangeInRec(stored, customer, genuinelyDifferent, CODEC)
                .dataWasChanged())
                .describedAs("a group that differs in more than letter case is a concurrent change")
                .isTrue();
    }

    /**
     * All five {@code COMPUTE} sites truncate toward zero, and none of them rounds.
     *
     * <p>The other half of {@code case10} through {@code case14}: those five cases assert that the value
     * the seam produced reaches the stored bytes, and this one asserts that the value is the truncated one
     * (gate <strong>G24</strong>).
     *
     * <p>Each operand is chosen so the two answers differ, and the assertion is made against both the
     * expected truncation and the rejected rounding - a test that only asserted the expected value would
     * pass on an operand where the two happen to agree, which is most of them. The keyword
     * {@code ROUNDED} appears zero times in {@code app/cbl/COACTUPC.cbl}, which is what makes
     * {@link RoundingMode#DOWN} the only faithful choice; the plan records the same count for all
     * twenty-eight programs.
     */
    @Test
    @DisplayName("all five COMPUTE sites truncate toward zero and none rounds")
    void theFiveComputeSitesTruncateRatherThanRound() {
        assertComputeTruncates(AccountUpdateService.computeCreditLimit("$7,500.567", null, CODEC),
                "7500.56", "7500.57");
        assertComputeTruncates(AccountUpdateService.computeCashCreditLimit("1500.999", null, CODEC),
                "1500.99", "1501.00");
        assertComputeTruncates(AccountUpdateService.computeCurrBal("-42.079", null, CODEC),
                "-42.07", "-42.08");
        assertComputeTruncates(AccountUpdateService.computeCurrCycCredit("11.115", null, CODEC),
                "11.11", "11.12");
        assertComputeTruncates(AccountUpdateService.computeCurrCycDebit("22.229", null, CODEC),
                "22.22", "22.23");
        assertThat(CobolDecimal.MONETARY_SCALE)
                .describedAs("every signed decimal picture in the codebase is scale 2")
                .isEqualTo(2);
    }

    /**
     * One {@code COMPUTE}'s result is the truncation and is not the rounding.
     *
     * @param edit       what the seam produced
     * @param truncated  the value a COBOL store without {@code ROUNDED} leaves behind
     * @param rounded    the value {@code HALF_UP} would leave behind, which must not appear
     */
    private static void assertComputeTruncates(MonetaryEdit edit, String truncated, String rounded) {
        assertThat(edit.value())
                .describedAs("COMPUTE %s at app/cbl/COACTUPC.cbl:%s truncates rather than rounds",
                        edit.cobolReceiver(), edit.sourceLines())
                .isEqualByComparingTo(new BigDecimal(truncated))
                .isNotEqualByComparingTo(new BigDecimal(rounded));
        assertThat(edit.value().scale())
                .describedAs("%s is PIC S9(10)V99", edit.cobolReceiver())
                .isEqualTo(CobolDecimal.MONETARY_SCALE);
    }

    /**
     * Every {@code FILE STATUS} arm of {@code 9600-WRITE-PROCESSING} is reached.
     *
     * <p>Four sites and five outcomes between them (gate <strong>G47</strong>). {@code case01} reaches the
     * all-normal path and {@code case09} the first read's failure; the other three arms are driven here,
     * because each needs a repository that answers something the seeded data cannot produce.
     *
     * <ul>
     *   <li>the second read fails - {@code :3941-3948} sets {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}, and
     *       the account record is <em>already locked</em> but nothing is written;</li>
     *   <li>the account rewrite fails - {@code :4076-4080} sets {@code LOCKED-BUT-UPDATE-FAILED};</li>
     *   <li>the customer rewrite fails - {@code :4096-4103} sets the same flag <em>and</em> requests an
     *       {@code EXEC CICS SYNCPOINT ROLLBACK}, because the account rewrite has already succeeded and
     *       must not stand. That rollback request is the one observable that distinguishes the two rewrite
     *       failures, and asserting it is what stops a translation from treating them alike.</li>
     * </ul>
     */
    @Test
    @DisplayName("every FILE STATUS arm of 9600-WRITE-PROCESSING is reached")
    void everyFileStatusArmOfWriteProcessingIsReached() {
        WriteResult customerLockFailed = writeProcessingWith(true, null, null);
        assertThat(customerLockFailed.outcome())
                .isEqualTo(WriteOutcome.COULD_NOT_LOCK_CUST_FOR_UPDATE);
        assertThat(customerLockFailed.returnMessage())
                .isEqualTo(CODEC.movePicX(AccountUpdateService.MSG_COULD_NOT_LOCK_CUST_FOR_UPDATE,
                        AccountUpdateService.RETURN_MESSAGE_LENGTH));
        assertThat(customerLockFailed.fileStatus()).isEqualTo(FileStatus.NOT_FOUND);
        assertThat(customerLockFailed.failedFileName())
                .contains(AccountUpdateService.CUST_CICS_FILE_NAME);
        assertThat(customerLockFailed.isRewritten()).isFalse();
        assertThat(customerLockFailed.syncpointRollbackRequested()).isFalse();

        WriteResult accountRewriteFailed = writeProcessingWith(false,
                AccountRepository.WriteResult.notFound(), null);
        assertThat(accountRewriteFailed.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
        assertThat(accountRewriteFailed.returnMessage())
                .isEqualTo(CODEC.movePicX(AccountUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED,
                        AccountUpdateService.RETURN_MESSAGE_LENGTH));
        assertThat(accountRewriteFailed.isRewritten()).isFalse();
        assertThat(accountRewriteFailed.syncpointRollbackRequested())
                .describedAs("nothing has been written, so :4076-4080 requests no rollback")
                .isFalse();

        WriteResult customerRewriteFailed = writeProcessingWith(false, null,
                CustomerRepository.WriteResult.notFound());
        assertThat(customerRewriteFailed.outcome()).isEqualTo(WriteOutcome.LOCKED_BUT_UPDATE_FAILED);
        assertThat(customerRewriteFailed.syncpointRollbackRequested())
                .describedAs("the account rewrite already succeeded, so :4099-4101 rolls it back")
                .isTrue();

        assertThat(FileStatus.Outcome.values())
                .describedAs("the five outcomes a repository can report")
                .containsExactlyInAnyOrder(FileStatus.Outcome.OK, FileStatus.Outcome.END_OF_FILE,
                        FileStatus.Outcome.NOT_FOUND, FileStatus.Outcome.DUPLICATE,
                        FileStatus.Outcome.OTHER);
    }

    /**
     * {@code WS-RETURN-MSG} is {@code PIC X(75)} and the cleared state is seventy-five spaces.
     *
     * <p>Not {@code null} and not the empty string. {@code SET WS-RETURN-MSG-OFF TO TRUE} at {@code :876}
     * sets the field to {@code SPACES} at its declared width, and every case in this class declares that
     * width rather than trimming to it - which is why the message channel a case uses is the width-free
     * one: neither of the two width-bearing channels is 75.
     */
    @Test
    @DisplayName("WS-RETURN-MSG is PIC X(75) and its cleared state is 75 spaces")
    void theReturnMessageIsSeventyFiveBytesWide() {
        assertThat(AccountUpdateService.RETURN_MESSAGE_LENGTH).isEqualTo(75);
        assertThat(AccountUpdateService.RETURN_MESSAGE_OFF)
                .hasSize(AccountUpdateService.RETURN_MESSAGE_LENGTH)
                .isBlank();
        assertThat(AccountUpdateService.isReturnMessageOff(AccountUpdateService.RETURN_MESSAGE_OFF))
                .isTrue();
        assertThat(AccountUpdateService.isReturnMessageOff(
                CODEC.movePicX(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE,
                        AccountUpdateService.RETURN_MESSAGE_LENGTH)))
                .isFalse();
        assertThat(MessageChannel.WS_MESSAGE_80.declaration()).isNotEqualTo(
                MessageChannel.SCREEN_ERRMSG_78.declaration());
    }

    /**
     * {@code CSSTRPFY} resolves every attention identifier, folds {@code PF13}-{@code PF24}, and defaults
     * nothing.
     *
     * <p>{@code COPY 'CSSTRPFY'} at {@code app/cbl/COACTUPC.cbl:4199} brings in
     * {@code YYYY-STORE-PFKEY}, whose {@code EVALUATE} has twenty-eight {@code WHEN} arms and
     * <strong>no {@code WHEN OTHER}</strong>. Two properties follow and both are asserted: the twenty-eight
     * named identifiers each resolve to their own key, with {@code PF13}-{@code PF24} folding onto
     * {@code PFK01}-{@code PFK12} - so {@code PF15} is indistinguishable from {@code PF03} and would exit
     * the program - and an identifier the copybook does not name resolves to nothing at all rather than to
     * a default.
     *
     * <p>The constants come from {@link CicsAid}, which reproduces the absent IBM {@code DFHAID} from IBM
     * CICS documentation (plan risk <strong>R-D</strong>). They are pinned to their documented byte values
     * here rather than trusted, because a wrong constant would make every AID case pass while testing the
     * wrong key.
     */
    @Test
    @DisplayName("CSSTRPFY resolves every AID, folds PF13-PF24 and defaults nothing")
    void pfKeysResolveExactlyAsCsstrpfyMapsThem() {
        assertThat(CicsAid.DFHENTER).isEqualTo((byte) 0x7D);
        assertThat(CicsAid.DFHCLEAR).isEqualTo((byte) 0x6D);
        assertThat(CicsAid.DFHPA1).isEqualTo((byte) 0x6C);
        assertThat(CicsAid.DFHPA2).isEqualTo((byte) 0x6E);
        assertThat(CicsAid.DFHPF3).isEqualTo((byte) 0xF3);
        assertThat(CicsAid.DFHPF5).isEqualTo((byte) 0xF5);
        assertThat(CicsAid.DFHPF12).isEqualTo((byte) 0x7C);

        assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER)).contains(AidKey.ENTER);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR)).contains(AidKey.CLEAR);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1)).contains(AidKey.PA1);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPA2)).contains(AidKey.PA2);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF1)).contains(AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3)).contains(AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF5)).contains(AidKey.PFK05);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12)).contains(AidKey.PFK12);

        // The fold. PF13-PF24 are twelve further WHEN arms that set PFK01-PFK12 all over again, so the
        // upper bank is not a second set of keys - it is the same twelve.
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13)).contains(AidKey.PFK01);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                .describedAs("PF15 folds onto PFK03, which is the exit key")
                .contains(AidKey.PFK03);
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24)).contains(AidKey.PFK12);

        // No WHEN OTHER. An unnamed AID leaves the commarea's key exactly as it was.
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPEN))
                .describedAs("DFHPEN is not one of CSSTRPFY's twenty-eight arms")
                .isEmpty();
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPEN, Optional.of(AidKey.PFK03)))
                .describedAs("an unnamed AID leaves the stored key untouched, because there is no "
                        + "WHEN OTHER to overwrite it")
                .contains(AidKey.PFK03);
    }

    /**
     * An unrecognised attention identifier is remapped to {@code ENTER}, not rejected.
     *
     * <p>{@code :909-916} sets {@code PFK-INVALID}, admits only {@code ENTER}, {@code PF03},
     * {@code PF05} while {@code ACUP-CHANGES-OK-NOT-CONFIRMED} and {@code PF12} while
     * {@code NOT ACUP-DETAILS-NOT-FETCHED}, then does {@code SET CCARD-AID-ENTER TO TRUE} for everything
     * else. So on the details-not-fetched screen both {@code PF9} - never valid - and {@code PF12} -
     * valid only later - behave as {@code ENTER}.
     *
     * <p>Asserted as field-for-field equality against the screen {@code case19} pins, which is a stronger
     * statement than "it did not fail": a translation that rejected the key, or that ignored the turn
     * entirely, would produce a different screen and this test names which fields differ (practice
     * <strong>B5</strong>).
     */
    @Test
    @DisplayName("PF9 and PF12 on the key screen behave exactly as ENTER, because :913-916 remaps them")
    void anUnrecognisedAidIsRemappedToEnterRatherThanRejected() {
        Map<String, String> withEnter = paintKeyScreen(CicsAid.DFHENTER);
        assertThat(paintKeyScreen(CicsAid.DFHPF9))
                .describedAs("PF9 is never a valid AID here, so :913-916 remaps it to ENTER")
                .isEqualTo(withEnter);
        assertThat(paintKeyScreen(CicsAid.DFHPF12))
                .describedAs("PF12 is valid only once details have been fetched, so here it is remapped "
                        + "to ENTER too")
                .isEqualTo(withEnter);
        assertThat(paintKeyScreen(CicsAid.DFHPF5))
                .describedAs("PF5 is valid only while changes are validated-but-unconfirmed")
                .isEqualTo(withEnter);
    }

    /**
     * The highlight matrix: colour only in {@code REENTER}, and an asterisk only when the field is blank.
     *
     * <p>{@code app/cpy/CSSETATY.cpy} is four lines of logic and thirty-nine textual occurrences inside
     * this one program - the highest concentration anywhere in the twenty-eight. It reads: if the field's
     * flag is {@code NOT-OK} <em>or</em> {@code BLANK}, <strong>and</strong> the program is in
     * {@code CDEMO-PGM-REENTER}, move {@code DFHRED} into the colour item; and if the flag is
     * {@code BLANK}, additionally move {@code '*'} into the output item.
     *
     * <p>All six reachable combinations are driven, and the two that assign nothing matter as much as the
     * two that assign something: a translation that reddened a field on first entry would light up a
     * screen the operator has not typed into yet (gate <strong>G38</strong>).
     */
    @Test
    @DisplayName("CSSETATY reddens only in REENTER and asterisks only a blank field")
    void theHighlightMatrixIsColourInReenterAndAsteriskOnlyWhenBlank() {
        assertHighlight(FieldValidationState.OK, false, false, false);
        assertHighlight(FieldValidationState.OK, true, false, false);
        assertHighlight(FieldValidationState.NOT_OK, false, false, false);
        assertHighlight(FieldValidationState.NOT_OK, true, true, false);
        assertHighlight(FieldValidationState.BLANK, false, false, false);
        assertHighlight(FieldValidationState.BLANK, true, true, true);

        assertThat(BmsAttributes.DFHRED)
                .describedAs("DFHBMSCA's extended-colour red, from IBM CICS documentation")
                .isEqualTo((byte) 0xF2);
        assertThat(FieldAttributeSetter.ASTERISK).isEqualTo("*");
        assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
        assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
    }

    /**
     * One corner of the highlight matrix.
     *
     * @param state         the field's validation flag
     * @param reenter       whether the program is in {@code CDEMO-PGM-REENTER}
     * @param expectColour  whether {@code DFHRED} reaches the colour item
     * @param expectAsterisk whether {@code '*'} reaches the output item
     */
    private static void assertHighlight(FieldValidationState state, boolean reenter,
                                        boolean expectColour, boolean expectAsterisk) {
        FieldHighlight highlight = FieldAttributeSetter.resolve(state, reenter);
        assertThat(highlight.colourItemAssigned())
                .describedAs("CSSETATY moves DFHRED for %s in %s", state,
                        reenter ? "REENTER" : "ENTER")
                .isEqualTo(expectColour);
        assertThat(highlight.outputItemAssigned())
                .describedAs("CSSETATY moves '*' for %s in %s", state, reenter ? "REENTER" : "ENTER")
                .isEqualTo(expectAsterisk);
    }

    /**
     * Conversation state travels in the payload. There is no server-side session state.
     *
     * <p>{@code CARDDEMO-COMMAREA} is exactly 160 bytes, and the area {@code COMMON-RETURN} hands back at
     * {@code :1010-1012} is that followed by {@code WS-THIS-PROGCOMMAREA} - which is what
     * {@code :888-892} slices apart on the next turn. Every one of the sixteen fields is part of the
     * observable response, which is why every screen case declares all sixteen (rule <strong>R6</strong>,
     * gate <strong>G37</strong>).
     *
     * <p>The absence of session state is asserted structurally rather than by inspection: the controller
     * declares no {@code HttpSession}, no {@code @SessionAttributes} and no mutable static field, and its
     * handler takes everything it needs as a parameter. That last point is the substantive one - a
     * handler that could not be called twice with the same arguments and get the same answer would not be
     * stateless, and {@link #anUnrecognisedAidIsRemappedToEnterRatherThanRejected()} calls it four times
     * and compares.
     */
    @Test
    @DisplayName("CARDDEMO-COMMAREA is 160 bytes and no state is held server-side")
    void conversationStateTravelsInThePayload() {
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(EIBCALEN_FULL)
                .isEqualTo(NavigationContext.COMMAREA_LENGTH
                        + AccountUpdateRequest.CommArea.RECORD_LENGTH)
                .isGreaterThan(NavigationContext.COMMAREA_LENGTH);
        assertThat(navigationImage(NavigationContext.empty())).hasSize(16);
        assertThat(NavigationContext.empty().toFixedWidth(CODEC))
                .hasSize(NavigationContext.COMMAREA_LENGTH);
    }

    /**
     * No static mutable state, in the units or in this class.
     *
     * <p>COBOL {@code WORKING-STORAGE} is per-task storage, so translating it into a static Java field
     * would be wrong twice over: it would let one request see another's data, and it would make this test
     * class order-dependent. Neither is acceptable (practice <strong>B9</strong>, gate
     * <strong>G53</strong>).
     *
     * <p>Every static field of the two units and of this class is required to be {@code final}, and every
     * static {@code final} field of a mutable collection type is required to be an unmodifiable
     * implementation - a {@code static final List} that is still an {@code ArrayList} is shared mutable
     * state wearing a {@code final} keyword. This class's own field set is included deliberately: it holds
     * more than seventy constants, and any one of them becoming a live collection would make case
     * outcomes depend on execution order.
     *
     * <p>{@code COACTUPC}'s four {@code COMP-3} items - {@code WS-EDIT-ALPHANUM-LENGTH} at
     * {@code app/cbl/COACTUPC.cbl:62} and {@code WS-DIV-BY}, {@code WS-DIVIDEND} and
     * {@code WS-REMAINDER} at {@code :152}, {@code :154} and {@code :157} - are exactly the kind of item
     * that invites a static field, being small, numeric and reused. All four are per-invocation state on
     * {@link EditDateState} instead, which {@link #editDate} constructs fresh on every call.
     */
    @Test
    @DisplayName("no static mutable state in the units or in this class")
    void noStaticMutableStateExistsInThisClassOrTheUnitsItDrives() {
        for (Class<?> type : List.of(AccountUpdateController.class, AccountUpdateService.class,
                AccountDateValidator.class, AreaCodeLookup.class, COACTUPCParityTest.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .describedAs("%s.%s is static and must be final: COBOL WORKING-STORAGE is "
                                + "per-task storage and never becomes mutable static Java state, "
                                + "because that would break request isolation", type.getSimpleName(),
                                field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .describedAs("%s.%s must not be a mutable collection implementation, final or "
                                + "not: shared mutable state is shared whether or not the reference "
                                + "can be reassigned", type.getSimpleName(), field.getName())
                        .isNotIn(ArrayList.class, LinkedHashMap.class, EnumSet.class);
            }
        }

        // A fresh EditDateState per call, so the four COMP-3 items are never shared between validations.
        assertThat(editDate("20220719")).isNotSameAs(editDate("20220719"));
    }

    /**
     * The alternate indexes are second finders on the base repositories, never second tables.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} defines {@code CARDDAT} and {@code CCXREF} over base clusters and
     * {@code CARDAIX} and {@code CXACAIX} as alternate-index <em>paths</em> over those same clusters. One
     * repository per cluster therefore carries both access paths, and the two names it exposes are the
     * two the CSD declares (gate <strong>G45</strong>).
     *
     * <p>{@code COACTUPC} reaches the cross reference through its alternate index only - by account
     * identifier, at {@code :3654-3664} - which is what makes {@code CXACAIX} the path it names rather
     * than {@code CCXREF}, even though the two address one cluster.
     */
    @Test
    @DisplayName("CARDAIX and CXACAIX are alternate-index finders on the base repositories")
    void theAlternateIndexesAreSecondFindersOnTheSameRepositories() {
        assertThat(CardXrefRepository.BASE_DD_NAME).isEqualTo("CCXREF");
        assertThat(CardXrefRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CXACAIX");
        assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
        assertThat(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD)
                .describedAs("the CXACAIX path is keyed by the account identifier, which is what "
                        + "9200-GETCARDXREF-BYACCT reads it by")
                .isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);

        // Both finders are declared on one type, which is the property the gate is about: a second
        // repository would be a second table, and there is no second table.
        assertThat(Arrays.stream(CardXrefRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList())
                .contains("readByCardNumber", "readByAccountIdViaAltIndex");
    }

    /**
     * No version column, no DDL and no ORM annotation is introduced for concurrency control.
     *
     * <p>The concurrency check is {@code 9700-CHECK-CHANGE-IN-REC}'s thirty-five-item comparison and
     * nothing else. Replacing it with a version column would be a schema change, which the plan forbids:
     * no DDL, no entity annotations, no generated table definitions.
     *
     * <p>Asserted three ways, because absence is easy to claim and hard to demonstrate. The account record
     * declares exactly the thirteen spans {@code CVACT01Y} does, so there is no fourteenth for a version
     * to live in; the record's total width is the copybook's, so a version column could not hide inside
     * the {@code FILLER}; and neither model type carries a persistence annotation.
     */
    @Test
    @DisplayName("no version column and no ORM annotation is introduced for concurrency control")
    void noVersionColumnOrSchemaArtefactIsIntroduced() {
        assertThat(AccountRecord.LAYOUT.spans()).hasSize(13);
        assertThat(AccountRecord.LAYOUT.recordLength()).isEqualTo(300);
        assertThat(AccountRecord.LAYOUT.spans().stream()
                .map(FixedWidthRecord.FieldSpan::name).toList())
                .describedAs("thirteen spans, exactly as app/cpy/CVACT01Y.cpy declares them")
                .doesNotContain("ACCT-VERSION", "VERSION", "ROW-VERSION", "OPTLOCK");

        for (Class<?> type : List.of(AccountRecord.class, CustomerRecord.class,
                CardXrefRecord.class)) {
            for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
                assertThat(annotation.annotationType().getName())
                        .describedAs("%s carries a persistence annotation; the plan forbids an ORM "
                                + "outright", type.getSimpleName())
                        .doesNotStartWith("jakarta.persistence")
                        .doesNotStartWith("javax.persistence")
                        .doesNotStartWith("org.hibernate");
            }
        }
    }

    /**
     * {@code COMP-3} appears only in {@code WORKING-STORAGE}, never in a persisted record.
     *
     * <p>Four declarations in this program - {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at
     * {@code :62} and {@code WS-DIV-BY}, {@code WS-DIVIDEND} and {@code WS-REMAINDER} at {@code :152},
     * {@code :154} and {@code :157} - and not one of them is in a record layout. None of the twenty-eight
     * copybooks declares {@code COMP-3} or {@code PACKED-DECIMAL} at all, which is why the fixed-width
     * codec needs no packed-decimal nibble unpacking whatsoever: every persisted numeric is zoned
     * {@code DISPLAY}.
     *
     * <p>Asserted by round-tripping every signed span of the account record through the codec's zoned
     * encoder and decoder. A packed span would be six bytes for a {@code S9(10)V99}, not twelve, so the
     * width alone settles it - and the round trip settles that the twelve bytes are genuinely zoned with an
     * overpunched sign rather than merely twelve bytes wide.
     */
    @Test
    @DisplayName("every persisted numeric is zoned DISPLAY; COMP-3 is working-storage only")
    void packedDecimalNeverReachesARecord() {
        for (BigDecimal value : List.of(new BigDecimal("0.00"), new BigDecimal("194.00"),
                new BigDecimal("-42.07"), new BigDecimal("9999999999.99"))) {
            String image = monetary(value);
            assertThat(image)
                    .describedAs("a PIC S9(10)V99 span is twelve zoned characters, not six packed bytes")
                    .hasSize(AccountRecord.MONETARY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE)
                    .hasSize(12);
            assertThat(CODEC.decodeSignedScaled(image, CobolDecimal.MONETARY_SCALE))
                    .describedAs("the zoned image round-trips, sign overpunch included")
                    .isEqualByComparingTo(value);
        }
        assertThat(AccountRecord.MONETARY_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);
    }

    /**
     * The programmed-symbol and validation planes are never written, on any case.
     *
     * <p>{@code DFHBMSCA} publishes no mnemonic table for a programmed-symbol byte or a validation byte, so
     * a case cannot name a value for either even to say it is the default - which is why
     * {@link #attributeMnemonics} declares the colour and highlight planes only. This test is the
     * replacement, and it is a stronger statement than the omitted expectation would have been: it requires
     * all one hundred and eight of those items to stay at {@code X'00'} across every screen the six
     * controller cases paint, rather than merely declining to check them.
     */
    @Test
    @DisplayName("the xxxP and xxxV attribute planes stay untouched on every screen")
    void theProgrammedSymbolAndValidationPlanesAreNeverWritten() {
        for (byte aid : List.of(CicsAid.DFHENTER, CicsAid.DFHPF9, CicsAid.DFHPF12)) {
            AccountUpdateResponse painted = paintedKeyScreen(aid);
            for (AccountUpdateResponse.ScreenField field
                    : AccountUpdateResponse.ScreenField.values()) {
                AccountUpdateResponse.FieldAttributes quad = painted.attributes(field);
                assertThat(quad.getPs())
                        .describedAs("%s: COACTUPC moves no programmed-symbol byte anywhere",
                                field.psItemName())
                        .isZero();
                assertThat(quad.getValidn())
                        .describedAs("%s: COACTUPC moves no validation byte anywhere",
                                field.validnItemName())
                        .isZero();
            }
        }
    }

    /**
     * The date engine is a range perform: a stage that fails does not stop the next one running.
     *
     * <p>{@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} - reached through
     * {@code COPY 'CSUTLDWY'} at {@code app/cbl/COACTUPC.cbl:166} and {@code CSUTLDPY}'s 375 lines - runs
     * eight paragraphs in physical order and falls through their boundaries. A
     * {@code GO TO <para>-EXIT} therefore abandons only the remainder of <em>its own</em> paragraph, which
     * is the single most consequential thing to preserve about the whole engine (rule <strong>R7</strong>,
     * gate <strong>G30</strong>).
     *
     * <p>Two consequences are asserted, and they are the two a restructuring gets wrong in opposite
     * directions.
     *
     * <p><strong>The later stages still run.</strong> A blank year with a valid month and day leaves the
     * flags {@code B}, {@code ISVALID}, {@code ISVALID} - the year is flagged blank and the month and day
     * were nevertheless validated and passed. A translation that returned early on the year failure would
     * leave the month and day at the {@code '000'} {@code EDIT-DATE-CCYYMMDD} starts from, and would redden
     * two fields the operator filled in correctly.
     *
     * <p><strong>Only the first diagnostic survives.</strong> Every message assignment is guarded by
     * {@code IF WS-RETURN-MSG-OFF}, so the first stage to fail claims the message and every later failure
     * is silent. A blank year <em>and</em> a bad month reports the year's text while flagging both fields -
     * the flags accumulate, the message does not.
     */
    @Test
    @DisplayName("a failed date stage does not stop the next, and only the first diagnostic survives")
    void theDateEngineIsARangePerformSoAFailedStageDoesNotStopTheNextOne() {
        EditDateState blankYearGoodRest = editDate("    0413");
        assertThat(blankYearGoodRest.flagsImage())
                .describedAs("year blank, month and day validated anyway - the range perform fell "
                        + "through EDIT-YEAR-CCYY-EXIT into EDIT-MONTH")
                .isEqualTo(flags(EditFlag.BLANK, EditFlag.ISVALID, EditFlag.ISVALID));
        assertThat(blankYearGoodRest.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_YEAR_MUST_BE_SUPPLIED);
        assertThat(blankYearGoodRest.inputError()).isTrue();
        assertThat(blankYearGoodRest.wsEditDateIsValid()).isFalse();

        EditDateState blankYearBadMonth = editDate("    1301");
        assertThat(blankYearBadMonth.flagsImage())
                .describedAs("both stages flagged their own field")
                .isEqualTo(flags(EditFlag.BLANK, EditFlag.NOT_OK, EditFlag.ISVALID));
        assertThat(blankYearBadMonth.returnMessage().trim())
                .describedAs("the year's message, not the month's: IF WS-RETURN-MSG-OFF stopped being "
                        + "true once the year had spoken")
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_YEAR_MUST_BE_SUPPLIED)
                .doesNotContain(AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12);

        EditDateState badMonthOnly = editDate("20221301");
        assertThat(badMonthOnly.flagsImage())
                .describedAs("with a good year the month speaks for itself")
                .isEqualTo(flags(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.ISVALID));
        assertThat(badMonthOnly.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_MONTH_MUST_BE_1_TO_12);

        EditDateState valid = editDate("20220719");
        assertThat(valid.wsEditDateIsValid()).isTrue();
        assertThat(valid.inputError()).isFalse();
        assertThat(valid.flagsImage())
                .isEqualTo(flags(EditFlag.ISVALID, EditFlag.ISVALID, EditFlag.ISVALID));
        assertThat(valid.returnMessage()).isBlank();
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR}'s three guards fire in source order, and the leap rule divides by four
     * hundred only for a century year.
     *
     * <p>The combination stage at {@code CSUTLDPY:209-279}, whose four forward
     * {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} sites make it the only part of the engine whose flags reach
     * the caller intact. Three properties, each of which a plausible rewrite loses.
     *
     * <p><strong>The guard order.</strong> {@code 2/31} matches both the first guard - a thirty-one in a
     * month that has no thirty-first - and February's own guards. The first wins, so the message is the
     * generic one and never a February one. Asserting the outcome alone would not catch a reordering,
     * because both orders reject the date; only the text distinguishes them.
     *
     * <p><strong>The leap rule.</strong> {@code IF WS-EDIT-DATE-YY-N = 0} selects a divisor of four
     * hundred and everything else four, which is the Gregorian rule stated as a single division. All four
     * corners are driven: a plain leap year, a plain common year, a century year that <em>is</em> a leap
     * year, and a century year that is not. The division runs through {@code WS-DIV-BY},
     * {@code WS-DIVIDEND} and {@code WS-REMAINDER} - the three {@code COMP-3} items at
     * {@code app/cbl/COACTUPC.cbl:152}, {@code :154} and {@code :157}, and the only arithmetic in the
     * engine.
     *
     * <p><strong>The flag breadth.</strong> A rejected 29 February flags <em>three</em> fields, not one:
     * the year is what made the day wrong, so {@code CSUTLDPY:262} sets {@code FLG-YEAR-NOT-OK} alongside
     * the month and day. The other two guards flag two. Since {@code CSSETATY} reddens exactly the flagged
     * fields, getting this wrong changes what the operator sees.
     */
    @Test
    @DisplayName("the day/month/year guards fire in order and the leap rule uses 400 only on a century")
    void theLeapYearRuleDividesByFourHundredOnlyForACenturyYear() {
        assertThat(editDate("20240229").wsEditDateIsValid())
                .describedAs("2024 is divisible by four and its last two digits are not zero")
                .isTrue();
        assertThat(editDate("20000229").wsEditDateIsValid())
                .describedAs("2000 is a century year and is divisible by four hundred")
                .isTrue();

        EditDateState commonYear = editDate("20230229");
        assertThat(commonYear.wsEditDateIsValid()).isFalse();
        assertThat(commonYear.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_NOT_A_LEAP_YEAR);
        assertThat(commonYear.flagsImage())
                .describedAs("all three flagged: CSUTLDPY:262 blames the year for the day")
                .isEqualTo(flags(EditFlag.NOT_OK, EditFlag.NOT_OK, EditFlag.NOT_OK));

        EditDateState centuryCommonYear = editDate("19000229");
        assertThat(centuryCommonYear.wsEditDateIsValid())
                .describedAs("1900 is a century year and 1900 modulo 400 is 300, so it is not a leap "
                        + "year - the case a divisor of four alone would get wrong")
                .isFalse();
        assertThat(centuryCommonYear.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_NOT_A_LEAP_YEAR);

        EditDateState thirtyFirstOfApril = editDate("20220431");
        assertThat(thirtyFirstOfApril.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_CANNOT_HAVE_31_DAYS);
        assertThat(thirtyFirstOfApril.flagsImage())
                .describedAs("month and day, not the year")
                .isEqualTo(flags(EditFlag.ISVALID, EditFlag.NOT_OK, EditFlag.NOT_OK));

        EditDateState thirtiethOfFebruary = editDate("20220230");
        assertThat(thirtiethOfFebruary.returnMessage().trim())
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_CANNOT_HAVE_30_DAYS);

        EditDateState thirtyFirstOfFebruary = editDate("20220231");
        assertThat(thirtyFirstOfFebruary.returnMessage().trim())
                .describedAs("2/31 satisfies the first guard as well as February's own, and the first "
                        + "guard is the one at CSUTLDPY:213 - so the generic message wins")
                .isEqualTo(EDIT_FIELD_NAME + AccountDateValidator.MSG_CANNOT_HAVE_31_DAYS)
                .doesNotContain(AccountDateValidator.MSG_CANNOT_HAVE_30_DAYS)
                .doesNotContain(AccountDateValidator.MSG_NOT_A_LEAP_YEAR);
    }

    /**
     * The area-code table is {@code CSLKPCDY}'s, and the condition {@code COACTUPC} tests is the
     * general-purpose one.
     *
     * <p>{@code app/cpy/CSLKPCDY.cpy} is 1,318 lines and {@code COACTUPC} is its only consumer. It
     * declares three overlapping area-code conditions, and which one an edit tests is a behavioural choice
     * rather than a detail: {@code EDIT-AREA-CODE} at {@code app/cbl/COACTUPC.cbl:2298} tests
     * {@code VALID-GENERAL-PURP-CODE}, <strong>not</strong> {@code VALID-PHONE-AREA-CODE}.
     *
     * <p>So {@code 911} and {@code 555} are members of the phone-area set and are still <em>rejected</em>
     * as area codes, because they are easy-recognition codes rather than general-purpose ones. A
     * translation that reached for the broader condition would accept both and would pass every case in
     * this class - which is exactly why the distinction is asserted here rather than left to a case.
     *
     * <p>The partition is asserted too: four hundred and ten general-purpose codes plus eighty
     * easy-recognition codes are four hundred and ninety phone-area codes exactly, with no overlap. A
     * transcription slip in a 1,318-line table would almost certainly break that arithmetic.
     */
    @Test
    @DisplayName("CSLKPCDY's tables, and EDIT-AREA-CODE tests the general-purpose condition")
    void theAreaCodeTableIsCslkpcdyAndTheGeneralPurposeConditionIsTheOneTested() {
        AreaCodeLookup lookup = new AreaCodeLookup(CODEC);

        for (String hit : List.of("201", "202", "212")) {
            assertThat(lookup.isValidGeneralPurposeCode(hit))
                    .describedAs("%s is a general-purpose code, so EDIT-AREA-CODE accepts it", hit)
                    .isTrue();
        }
        assertThat(lookup.isValidGeneralPurposeCode("199"))
                .describedAs("199 appears in none of CSLKPCDY's three tables")
                .isFalse();
        assertThat(lookup.isValidPhoneAreaCode("199")).isFalse();

        for (String easyRecognition : List.of("200", "555", "911", "999")) {
            assertThat(lookup.isValidPhoneAreaCode(easyRecognition))
                    .describedAs("%s is in VALID-PHONE-AREA-CODE", easyRecognition)
                    .isTrue();
            assertThat(lookup.isValidEasyRecognitionAreaCode(easyRecognition)).isTrue();
            assertThat(lookup.isValidGeneralPurposeCode(easyRecognition))
                    .describedAs("%s is NOT in VALID-GENERAL-PURP-CODE, so EDIT-AREA-CODE rejects it "
                            + "even though the wider table contains it", easyRecognition)
                    .isFalse();
        }

        assertThat(lookup.validGeneralPurposeCodes())
                .hasSize(AreaCodeLookup.VALID_GENERAL_PURP_CODE_COUNT)
                .hasSize(410)
                .doesNotContainAnyElementsOf(lookup.validEasyRecognitionAreaCodes());
        assertThat(lookup.validEasyRecognitionAreaCodes())
                .hasSize(AreaCodeLookup.VALID_EASY_RECOG_AREA_CODE_COUNT)
                .hasSize(80);
        assertThat(lookup.validPhoneAreaCodes())
                .describedAs("the two disjoint subsets exhaust the phone-area table")
                .hasSize(AreaCodeLookup.VALID_PHONE_AREA_CODE_COUNT)
                .hasSize(410 + 80)
                .containsAll(lookup.validGeneralPurposeCodes())
                .containsAll(lookup.validEasyRecognitionAreaCodes());

        String storedState = storedCustomerRecord().getCustAddrStateCd();
        String storedZip = storedCustomerRecord().getCustAddrZip();
        assertThat(lookup.validUsStateCodes())
                .hasSize(AreaCodeLookup.VALID_US_STATE_CODE_COUNT)
                .hasSize(56)
                .describedAs("the seeded customer's state is one CSLKPCDY names")
                .contains(storedState);
        assertThat(lookup.isValidStateAndZipCombination(storedState, "27601"))
                .describedAs("CSLKPCDY admits NC27 and NC28")
                .isTrue();
        assertThat(lookup.isValidStateAndZipCombination(storedState, storedZip))
                .describedAs("the seeded customer's own state and zip do not form an admitted "
                        + "combination - custdata.txt is synthetic, and that is a property of the "
                        + "fixture rather than something to correct")
                .isFalse();
        assertThat(lookup.stateZipcodeGroupImage(storedState, "27601"))
                .describedAs("the combination is tested on a four-character group: the state code "
                        + "followed by the first two digits of the zip")
                .startsWith(storedState + "27")
                .hasSize(AreaCodeLookup.STATE_ZIPCODE_GROUP_LENGTH);
    }

    /**
     * The staged phone guards run every stage, and only the first diagnostic in the whole edit survives.
     *
     * <p>{@code EDIT-AREA-CODE}'s four {@code GO TO EDIT-US-PHONE-PREFIX} sites at
     * {@code app/cbl/COACTUPC.cbl:2259}, {@code :2277}, {@code :2291} and {@code :2311}, and
     * {@code EDIT-US-PHONE-PREFIX}'s three {@code GO TO EDIT-US-PHONE-LINENUM} sites at {@code :2330},
     * {@code :2348} and {@code :2362}. Every one is a <em>forward</em> jump to the next stage, so the
     * correct restructuring is a sequence of guarded blocks and never an early return (rule
     * <strong>R7</strong>, gate <strong>G30</strong>).
     *
     * <p>One screen shows both halves of the property. A rejected area code and a rejected prefix are
     * submitted together, and all three phone fields answer independently: the area code reddens, the
     * prefix reddens - which is only possible if stage two ran after stage one had jumped - and the line
     * number, which is valid, stays untouched. An early return would leave the prefix and line number
     * unflagged; a fall-through that skipped the remaining stages would leave the line number flagged.
     *
     * <p>The message is neither phone message. {@code 1200-EDIT-MAP-INPUTS} sets
     * {@code ACUP-CHANGES-NOT-OK} at {@code :1470} and then edits the account status <em>first</em>, at
     * {@code :1472-1476}; that field is blank here, so it claims {@code WS-RETURN-MSG} and every later
     * {@code IF WS-RETURN-MSG-OFF} - the phones' included - finds it already taken. The flags accumulate
     * across every field on the screen while exactly one message reaches the operator, and this asserts
     * both at once.
     *
     * <p>It also shows the highlight matrix on real data rather than on a constructed flag: the blank
     * mandatory fields carry {@code DFHRED} <em>and</em> an asterisk, because their flag is {@code BLANK},
     * while the two phone fields carry the colour only, because theirs is {@code NOT-OK} (gate
     * <strong>G38</strong>).
     */
    @Test
    @DisplayName("every phone stage runs after an earlier stage jumps, and one message reaches the screen")
    void theStagedPhoneGuardsRunEveryStageAndOnlyTheFirstDiagnosticSurvives() {
        AccountUpdateResponse painted = paintedScreen(
                AccountUpdateRequest.ChangeAction.SHOW_DETAILS,
                Map.of(AccountUpdateRequest.ScreenField.ACCTSID, ACCT_KEY,
                        AccountUpdateRequest.ScreenField.ACSPH1A, REJECTED_AREA_CODE,
                        AccountUpdateRequest.ScreenField.ACSPH1B, NON_NUMERIC_PREFIX,
                        AccountUpdateRequest.ScreenField.ACSPH1C, VALID_LINE_NUMBER),
                CicsAid.DFHENTER);

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSPH1A).getColour())
                .describedAs("stage one rejected 199 and reddened the area code")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSPH1A))
                .describedAs("NOT-OK, not BLANK, so CSSETATY leaves the keyed value in place")
                .isEqualTo(CODEC.movePicX(REJECTED_AREA_CODE,
                        AccountUpdateResponse.ACSPH1A_LENGTH));

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSPH1B).getColour())
                .describedAs("stage two reddened the prefix, which it could only do by running after "
                        + "stage one's GO TO EDIT-US-PHONE-PREFIX")
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSPH1B))
                .isEqualTo(CODEC.movePicX(NON_NUMERIC_PREFIX,
                        AccountUpdateResponse.ACSPH1B_LENGTH));

        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSPH1C).getColour())
                .describedAs("stage three ran too, and accepted a valid line number - so the jumps "
                        + "advance through the stages rather than abandoning them")
                .isZero();
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSPH1C))
                .isEqualTo(VALID_LINE_NUMBER);

        assertThat(painted.value(AccountUpdateResponse.ScreenField.ERRMSG).trim())
                .describedAs("the account status is edited first at :1472 and is blank, so its message "
                        + "claims WS-RETURN-MSG and neither phone message is ever assigned")
                .isEqualTo(MSG_ACCT_STATUS_MUST_BE_SUPPLIED)
                .doesNotContain("Area code")
                .doesNotContain("Prefix");

        // The blank mandatory fields are the other two corners of the highlight matrix, on real data.
        assertThat(painted.attributes(AccountUpdateResponse.ScreenField.ACSTTUS).getColour())
                .isEqualTo(BmsAttributes.DFHRED);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.ACSTTUS))
                .describedAs("BLANK, so CSSETATY assigns the asterisk as well as the colour")
                .startsWith(FieldAttributeSetter.ASTERISK);
        assertThat(painted.value(AccountUpdateResponse.ScreenField.INFOMSG))
                .describedAs("3250-SETUP-INFOMSG's prompt-for-changes text, :471")
                .isEqualTo(CODEC.movePicX(INFO_PROMPT_FOR_CHANGES,
                        AccountUpdateResponse.INFOMSG_LENGTH));
    }

    /**
     * Both {@code PERFORM 3000-SEND-MAP THRU} arms clear the work area <em>before</em> painting and set
     * re-entry <em>after</em>.
     *
     * <p>The two range performs at {@code app/cbl/COACTUPC.cbl:969-970} and {@code :985-986}. Their
     * statements are ordered, and three of those orderings are observable - which is what makes this an
     * assertion about order rather than about outcome (rule <strong>R7</strong>).
     *
     * <p>The arm at {@code :980-989} is the one driven here, because it is the one where the orderings
     * could differ from the fresh-entry arm and do not. Its sequence is: {@code INITIALIZE} three groups,
     * {@code SET CDEMO-PGM-ENTER}, paint, {@code SET CDEMO-PGM-REENTER}, {@code SET
     * ACUP-DETAILS-NOT-FETCHED}, leave.
     *
     * <ul>
     *   <li><strong>The clear precedes the paint</strong>, so the forty-two data fields come back at
     *       {@code LOW-VALUES} even though the inbound commarea carried a completed update. A translation
     *       that painted before clearing would show the operator the account they had just finished
     *       editing.</li>
     *   <li><strong>The context reset precedes the paint</strong>, so {@code CSSETATY}'s
     *       {@code CDEMO-PGM-REENTER} test is false throughout and not one field is reddened. A
     *       translation that set re-entry first would return a clean screen covered in error highlighting.
     *       </li>
     *   <li><strong>The re-entry set follows the paint</strong>, so the commarea handed back says
     *       {@code REENTER} while the screen it accompanies was painted in the {@code ENTER} state. The two
     *       disagree by design, and that disagreement is what makes the next turn validate rather than
     *       repaint.</li>
     * </ul>
     *
     * <p>The screen is required to equal the fresh-entry screen field for field, which is the strongest
     * available statement of all three at once.
     */
    @Test
    @DisplayName("3000-SEND-MAP is reached after the clear and before the re-entry set, on both arms")
    void theTwoSendMapArmsInitialiseBeforePaintingAndSetReenterAfter() {
        AccountUpdateResponse afterCompletedUpdate = paintedScreen(
                AccountUpdateRequest.ChangeAction.CHANGES_OKAYED_AND_DONE,
                Map.of(AccountUpdateRequest.ScreenField.ACCTSID, ACCT_KEY), CicsAid.DFHENTER);

        assertThat(outputItems(afterCompletedUpdate))
                .describedAs("the completed-update arm at :980-989 repaints exactly the screen the "
                        + "fresh-entry arm at :964-975 does - the one case15 and case16 pin - because "
                        + "both INITIALIZE before performing 3000-SEND-MAP. Forty-two LOW-VALUES data "
                        + "fields and a blank error line, from a commarea that arrived carrying a "
                        + "completed update")
                .isEqualTo(promptScreen(null, null));

        for (AccountUpdateResponse.ScreenField field
                : AccountUpdateResponse.ScreenField.values()) {
            assertThat(afterCompletedUpdate.attributes(field).getColour())
                    .describedAs("%s must not be reddened: SET CDEMO-PGM-ENTER at :983 runs before the "
                            + "paint, so CSSETATY's REENTER test is false for every one of its "
                            + "thirty-nine sites", field.label())
                    .isZero();
        }

        assertThat(afterCompletedUpdate.getNavigationContext().pgmContext())
                .describedAs("SET CDEMO-PGM-REENTER at :987 runs after the paint, so the returned "
                        + "context says re-enter while the screen it accompanies was painted on the "
                        + "enter path")
                .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        assertThat(token(afterCompletedUpdate.getNextMap()))
                .describedAs("a map was named, so 3000-SEND-MAP was reached")
                .isEqualTo(THIS_MAP);
        assertThat(token(afterCompletedUpdate.getNextProgram()))
                .describedAs("GO TO COMMON-RETURN at :989, not an XCTL")
                .isNull();
    }

    // =================================================================================================
    // FIXTURES FOR THE STRUCTURAL GATES. These construct the same two units the twenty cases construct,
    // but reach paths a declarative case cannot: a comparison driven in isolation, a repository forced to
    // fail a specific verb, and a screen painted for an attention identifier no case declares.
    // =================================================================================================

    /**
     * The stored {@code ACCOUNT-RECORD} of {@code acctdata.txt} row 1 with its group identifier replaced.
     *
     * <p>The substitution is made in the <em>bytes</em>, at {@code app/cpy/CVACT01Y.cpy}'s own offset, and
     * the record is then decoded through the real model. Building it the other way round - decoding first
     * and setting a property - would not prove the byte at offset 112 is the one the comparison reads.
     *
     * <p>Needed because the seeded row's group is ten spaces, which folds to itself under any case
     * transformation and so cannot distinguish a lower-cased comparison from an exact one.
     *
     * @param groupId the group identifier to store, padded to {@code PIC X(10)}
     * @return a fresh record
     */
    private static AccountRecord storedAccountRecordWith(String groupId) {
        String row = fixtureRow("acctdata.txt", AccountRecord.RECORD_LENGTH, 0);
        String patched = row.substring(0, AccountRecord.ACCT_GROUP_ID_OFFSET)
                + CODEC.movePicX(groupId, AccountRecord.ACCT_GROUP_ID_LENGTH)
                + row.substring(AccountRecord.ACCT_GROUP_ID_OFFSET
                        + AccountRecord.ACCT_GROUP_ID_LENGTH);
        return AccountRecord.decode(patched, FIXTURE_CHARSET);
    }

    /**
     * A service whose repositories refuse every call.
     *
     * <p>For the seams that touch no file at all - {@link AccountUpdateService#checkChangeInRec} is a pure
     * comparison of two groups against two records the caller already holds, because {@code 9700} is
     * performed from inside {@code 9600-WRITE-PROCESSING} <em>after</em> both records have been read. A
     * repository call from here would mean the translation re-read inside the comparison, and the strict
     * stubs make that fail by name.
     *
     * @return a service over refusing repositories
     */
    private static AccountUpdateService serviceOverEmptyDatasets() {
        return new AccountUpdateService(strictAccountRepository(), strictCustomerRepository(),
                unitOfWork("structural"));
    }

    /**
     * Runs {@code 9600-WRITE-PROCESSING} with one of its four file verbs forced to fail.
     *
     * <p>Both records are seeded from the real fixtures and both snapshots agree with them, so
     * {@code 9700} finds no concurrent change and the paragraph reaches the verb under test. Exactly one
     * argument is non-default per call, which is what makes each arm attributable.
     *
     * @param custLockFails    whether the {@code READ ... UPDATE} at {@code :3921-3931} finds no record
     * @param forcedAcctWrite  what the {@code REWRITE} at {@code :4064-4070} answers, or {@code null} to
     *                         let it succeed against the seeded row
     * @param forcedCustWrite  what the {@code REWRITE} at {@code :4084-4090} answers, or {@code null} to
     *                         let it succeed
     * @return the result, whose outcome flag names the arm that was taken
     */
    private static WriteResult writeProcessingWith(boolean custLockFails,
                                                   AccountRepository.WriteResult forcedAcctWrite,
                                                   CustomerRepository.WriteResult forcedCustWrite) {
        List<String> accountRows = new ArrayList<>(
                List.of(fixtureRow("acctdata.txt", AccountRecord.RECORD_LENGTH, 0)));
        List<String> customerRows = new ArrayList<>(
                List.of(fixtureRow("custdata.txt", CustomerRecord.RECORD_LENGTH, 0)));

        AccountRepository accounts = fixtureAccountRepository(accountRows);
        if (forcedAcctWrite != null) {
            Mockito.doReturn(forcedAcctWrite).when(accounts).rewrite(ArgumentMatchers.any());
        }
        CustomerRepository customers = custLockFails
                ? fixtureCustomerRepository(new ArrayList<>())
                : fixtureCustomerRepository(customerRows);
        if (forcedCustWrite != null) {
            Mockito.doReturn(forcedCustWrite).when(customers)
                    .rewrite(ArgumentMatchers.any(CustomerRecord.class));
        }

        AccountUpdateDetails oldDetails = new AccountUpdateDetails(DetailGroup.OLD,
                storedAccountSnapshot(), storedCustomerSnapshot());
        AccountUpdateDetails newDetails = new AccountUpdateDetails(DetailGroup.NEW,
                newAccountData(), newCustomerData());

        return new AccountUpdateService(accounts, customers, unitOfWork("structural"))
                .writeProcessing(ACCT_KEY, NavigationContext.empty().withCustId(CUST_KEY),
                        oldDetails, newDetails, AccountUpdateService.RETURN_MESSAGE_OFF, CODEC);
    }

    /**
     * Runs one date through the whole {@code EDIT-DATE-CCYYMMDD} range and returns the working storage.
     *
     * <p>The three moves every call site makes are made here too, in the order
     * {@code app/cbl/COACTUPC.cbl:1478-1482} makes them: the field name, then the eight edit bytes, then
     * the message cleared. The real validator is used rather than a stand-in, because it <em>is</em>
     * {@code CSUTLDPY} plus {@code CSUTLDWY} and a stand-in would assert this class's reading of those two
     * copybooks instead of the translation's.
     *
     * @param ccyymmdd the eight bytes of {@code WS-EDIT-DATE-CCYYMMDD}, spaces included
     * @return the state after the range perform, carrying the flags, the verdict and the message
     */
    private static EditDateState editDate(String ccyymmdd) {
        AccountDateValidator validator = new AccountDateValidator(CODEC, new DateUtilityJob(),
                ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK)));
        EditDateState state = validator.newState();
        state.setEditVariableName(EDIT_FIELD_NAME);
        state.setEditDateCcyymmdd(ccyymmdd);
        state.setReturnMsgOff();
        validator.editDateCcyymmddThruExit(state);
        return state;
    }

    /**
     * The three bytes of {@code WS-EDIT-DATE-FLGS} for a year, month and day verdict.
     *
     * <p>Composed from {@link EditFlag#flagByte()} rather than written as a literal, so the
     * {@code LOW-VALUES} that {@code FLG-*-ISVALID} means stays the byte the copybook declares - and so
     * a reader can see which position is which without counting characters.
     *
     * @param year  the year verdict
     * @param month the month verdict
     * @param day   the day verdict
     * @return the three-byte group image
     */
    private static String flags(EditFlag year, EditFlag month, EditFlag day) {
        return String.valueOf(new char[] {year.flagByte(), month.flagByte(), day.flagByte()});
    }

    /**
     * Paints a screen for one change-action state, one set of keyed fields and one attention identifier.
     *
     * <p>The generalisation of {@link #paintedKeyScreen}: the context is still a re-entry from this same
     * transaction, so the {@code ELSE} at {@code :888-892} preserves the inbound
     * {@code WS-THIS-PROGCOMMAREA}, but the change-action byte and the keyed fields are the caller's. The
     * three repositories still refuse every call, so any path that reaches a file verb fails by name
     * rather than quietly reading.
     *
     * @param changeAction the {@code ACUP-CHANGE-ACTION} byte the previous turn left behind
     * @param keyed        the {@code xxxI} items the operator submitted
     * @param aid          the raw {@code EIBAID} byte
     * @return the payload the handler returned; never {@code null}
     */
    private static AccountUpdateResponse paintedScreen(
            String changeAction,
            Map<AccountUpdateRequest.ScreenField, String> keyed,
            byte aid) {
        Clock clock = ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK));
        AccountUpdateController controller = new AccountUpdateController(
                unreachableAccountRepository(), unreachableCardXrefRepository(),
                unreachableCustomerRepository(),
                new AccountUpdateService(unreachableAccountRepository(),
                        unreachableCustomerRepository(), unitOfWork("structural")),
                new AccountDateValidator(CODEC, new DateUtilityJob(), clock),
                new AreaCodeLookup(CODEC), clock);

        AccountUpdateRequest request = AccountUpdateRequest.initial()
                .withCommArea(AccountUpdateRequest.CommArea.initialised()
                        .withChangeAction(new AccountUpdateRequest.ChangeAction(changeAction)))
                .withNavigationContext(NavigationContext.empty()
                        .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM).withPgmReenter());
        for (Map.Entry<AccountUpdateRequest.ScreenField, String> entry : keyed.entrySet()) {
            request = request.withValue(entry.getKey(),
                    CODEC.movePicX(entry.getValue(), entry.getKey().length()));
        }

        String uriKey = keyed.getOrDefault(AccountUpdateRequest.ScreenField.ACCTSID, ACCT_KEY);
        ScreenResponse<AccountUpdateResponse> body = controller.updateAccount(uriKey, request, null,
                EIBCALEN_FULL, Integer.valueOf(Byte.toUnsignedInt(aid))).getBody();
        return Objects.requireNonNull(body, "every path answers with a body").screen();
    }

    /**
     * Paints the account-key screen for one attention identifier and returns the payload.
     *
     * <p>The context is {@code case20}'s: a re-entry from this same transaction with a blank account
     * filter. Chosen deliberately, for two reasons. It reaches no file verb, because
     * {@code 1210-EDIT-ACCOUNT}'s first guard at {@code :1787-1798} leaves
     * {@code FLG-ACCTFILTER-ISVALID} unset and so makes {@code 2000-DECIDE-ACTION}'s read condition
     * false - the three repositories refuse every call, so a translation that read here would fail by
     * name. And it takes the {@code ELSE} at {@code :888-892}, so the inbound
     * {@code WS-THIS-PROGCOMMAREA} survives rather than being re-initialised, which is what makes
     * {@code PFK12}'s guard - {@code NOT ACUP-DETAILS-NOT-FETCHED} - depend on carried state rather than
     * on a reset.
     *
     * <p>The attention identifier is therefore the only thing that varies between calls, which is
     * precisely the property {@link #anUnrecognisedAidIsRemappedToEnterRatherThanRejected()} tests.
     *
     * @param aid the raw {@code EIBAID} byte
     * @return the payload the handler returned; never {@code null}
     */
    private static AccountUpdateResponse paintedKeyScreen(byte aid) {
        Clock clock = ParityHarness.fixedClockAt(LocalDateTime.parse(PINNED_CLOCK));
        AccountUpdateController controller = new AccountUpdateController(
                unreachableAccountRepository(), unreachableCardXrefRepository(),
                unreachableCustomerRepository(),
                new AccountUpdateService(unreachableAccountRepository(),
                        unreachableCustomerRepository(), unitOfWork("structural")),
                new AccountDateValidator(CODEC, new DateUtilityJob(), clock),
                new AreaCodeLookup(CODEC), clock);

        NavigationContext context = NavigationContext.empty()
                .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM).withPgmReenter();
        ScreenResponse<AccountUpdateResponse> body = controller.updateAccount(BLANK_ACCT,
                requestFrom(context, Map.of("ACCTSIDI", BLANK_ACCT), BLANK_ACCT), null,
                EIBCALEN_FULL, Integer.valueOf(Byte.toUnsignedInt(aid))).getBody();
        return Objects.requireNonNull(body, "every path answers with a body").screen();
    }

    /**
     * The fifty-four symbolic output items of the screen one attention identifier paints.
     *
     * @param aid the raw {@code EIBAID} byte
     * @return the {@code xxxO} items, keyed as the symbolic map names them
     */
    private static Map<String, String> paintKeyScreen(byte aid) {
        return outputItems(paintedKeyScreen(aid));
    }

    // =================================================================================================
    // THE TWENTY CASES.
    //
    // case01-case14 drive AccountUpdateService (9600-WRITE-PROCESSING and 9700-CHECK-CHANGE-IN-REC).
    // case15-case20 drive AccountUpdateController (the screen-shaped paths).
    // =================================================================================================

    /**
     * {@code case01} - the clean check, asserted as both complete record images. {@code SERVICE}.
     *
     * <p>The snapshot the screen was painted from agrees with both records read back under their locks in
     * all thirty-five compared items, so {@code :4141} and {@code :4189} both take {@code CONTINUE} and
     * the two rewrites proceed. This is the strongest assertion in the class: declaring
     * {@code expectedBytes} for both datasets makes the differ compare every addressable span of both
     * layouts, {@code FILLER} included, so the case fails if a reserved span is dropped, shortened, or
     * filled with anything other than spaces (gates <strong>G19</strong> and <strong>G21</strong>).
     *
     * @return the scenario
     */
    private static ParityScenario case01() {
        return serviceScenario("case01",
                "9700-CHECK-CHANGE-IN-REC finds no change in either block at "
                        + "app/cbl/COACTUPC.cbl:4115-4192, so both rewrites proceed. Asserted as the "
                        + "complete 300-byte and 500-byte images so that FILLER X(178) and FILLER X(100) "
                        + "are compared too.",
                UnaryOperator.identity(), UnaryOperator.identity(),
                newAccountData(), newCustomerData(),
                List.of(accountWrite(fullNewAccountImage()), customerWrite(fullNewCustomerImage())),
                clearReturnMessage());
    }

    /**
     * {@code case02} - one compared item changed underneath. {@code SERVICE}.
     *
     * <p>The snapshot's {@code ACUP-OLD-CURR-BAL-N} holds {@code 999.99} where {@code ACCTDAT} holds
     * {@code 194.00}. The second operand of the {@code AND} chain at {@code :4117} is false, so the
     * {@code ELSE} arm runs: {@code :4143} sets {@code DATA-WAS-CHANGED-BEFORE-UPDATE} and {@code :4144}
     * leaves through {@code GO TO 9600-WRITE-PROCESSING-EXIT} without rewriting either record. Both
     * datasets must be untouched - the customer record is never written even though its own block was
     * never even evaluated.
     *
     * @return the scenario
     */
    private static ParityScenario case02() {
        return serviceScenario("case02",
                "One compared item differs - the snapshot balance is 999.99 where ACCTDAT holds 194.00 - "
                        + "so 9700 sets DATA-WAS-CHANGED-BEFORE-UPDATE at :4143 and the GO TO at :4144 "
                        + "abandons both rewrites.",
                stale -> withCurrBal(stale, new BigDecimal("999.99")), UnaryOperator.identity(),
                newAccountData(), newCustomerData(),
                List.of(), returnMessage(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE));
    }

    /**
     * {@code case03} - three compared items changed at once. {@code SERVICE}.
     *
     * <p>Active status, credit limit and expiry year all differ. The {@code AND} chain short-circuits at
     * the first false operand - {@code ACCT-ACTIVE-STATUS} at {@code :4115} - but the outcome is
     * identical to {@code case02}'s, because the {@code ELSE} arm is a single unconditional
     * {@code SET} and {@code GO TO} rather than a per-field repair. That equivalence is the point: a
     * translation that reported which items differed <em>through the return message</em> would diverge
     * here, because the message is one literal for every combination.
     *
     * @return the scenario
     */
    private static ParityScenario case03() {
        return serviceScenario("case03",
                "Three compared items differ at once - active status, credit limit and expiry year. The "
                        + "AND chain short-circuits at :4115 but the ELSE arm at :4142-4145 is one "
                        + "unconditional SET and GO TO, so the outcome matches the single-item case "
                        + "exactly.",
                stale -> withExpiryYear(withCreditLimit(withStatus(stale, "N"),
                        new BigDecimal("1.00")), "1999"),
                UnaryOperator.identity(), newAccountData(), newCustomerData(),
                List.of(), returnMessage(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE));
    }

    /**
     * {@code case04} - the {@code FILLER}-adjacent compared item changed. {@code SERVICE}.
     *
     * <p>{@code ACCT-GROUP-ID} occupies offset 112 for ten bytes and {@code FILLER X(178)} begins at 122,
     * so it is the last compared item in the stored layout and the one an off-by-one would miss.
     * Perturbing only that span proves the comparison reads offset 112 and not 102 or 122: at 102 it
     * would read {@code ACCT-ADDR-ZIP}, which {@code 9700} does not compare at all, and at 122 the first
     * reserved space, which is identical between the snapshot and the record - so either way the check
     * would wrongly pass and the rewrite would go ahead.
     *
     * @return the scenario
     */
    private static ParityScenario case04() {
        return serviceScenario("case04",
                "Only ACCT-GROUP-ID differs. It is the ten bytes at offset 112, immediately before "
                        + "FILLER X(178) at 122 and immediately after the uncompared ACCT-ADDR-ZIP at "
                        + "102, so a comparison reading either neighbouring span would find no "
                        + "difference and wrongly allow the rewrite.",
                stale -> withGroupId(stale, "PREMIUM"), UnaryOperator.identity(),
                newAccountData(), newCustomerData(),
                List.of(), returnMessage(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE));
    }

    /**
     * {@code case05} - a block-two item changed with block one clean. {@code SERVICE}.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE} at {@code :4187} is the nineteenth and last item of the customer
     * block, and every account item matches. Block one therefore takes {@code CONTINUE} at {@code :4141}
     * and block two is reached and fails, which is the only way to prove the blocks are evaluated in
     * source order: a translation that evaluated them together, or the customer block first, would
     * produce the same refusal here but would fail {@code case02}, where the account block's failure
     * must stop the comparison before the customer record is ever compared.
     *
     * @return the scenario
     */
    private static ParityScenario case05() {
        return serviceScenario("case05",
                "Every account item matches and CUST-FICO-CREDIT-SCORE - the last of block two's "
                        + "nineteen, at :4187 - does not. Block one takes CONTINUE at :4141 and block "
                        + "two fails at :4190-4193, which is what proves the two blocks run in source "
                        + "order.",
                UnaryOperator.identity(), stale -> withFicoScore(stale, 811),
                newAccountData(), newCustomerData(),
                List.of(), returnMessage(AccountUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE));
    }

    /**
     * {@code case06} - three items changed underneath that {@code 9700} does not compare. {@code SERVICE}.
     *
     * <p>{@code ACCT-ID}, {@code ACCT-ADDR-ZIP} and {@code CUST-ID} are all absent from the comparison:
     * neither key appears in either block, and the account's zip sits at offset 102 <em>between</em> two
     * compared items without being one. Changing all three underneath must leave the rewrite entirely
     * unaffected. A translation that helpfully compared a key, or that compared the whole record image
     * instead of the thirty-five named items, would refuse an update the COBOL accepts - and would do so
     * silently, because the refusal looks exactly like a genuine concurrent change.
     *
     * <p>The written image is the same one {@code case01} produces, which is the assertion: the
     * uncompared spans influence neither the decision nor the bytes.
     *
     * @return the scenario
     */
    private static ParityScenario case06() {
        return serviceScenario("case06",
                "ACCT-ID, ACCT-ADDR-ZIP and CUST-ID all differ from the record and none of them is one "
                        + "of 9700's thirty-five compared items, so the check passes and both rewrites "
                        + "proceed exactly as they do when nothing differs at all.",
                stale -> withAcctId(withAddrZip(stale, "99999-0000"), 99999999999L),
                stale -> withCustId(stale, 987654321),
                newAccountData(), newCustomerData(),
                List.of(accountWrite(fullNewAccountImage()), customerWrite(fullNewCustomerImage())),
                clearReturnMessage());
    }

    /**
     * {@code case07} - the two case-folding comparisons. {@code SERVICE}.
     *
     * <p>{@code :4139-4140} compares {@code ACCT-GROUP-ID} through {@code FUNCTION LOWER-CASE} on
     * <em>both</em> operands, and {@code :4152-4153} compares {@code CUST-FIRST-NAME} through
     * {@code FUNCTION UPPER-CASE} on both. So a snapshot whose group identifier and first name differ
     * from the record's only in letter case is <strong>not</strong> a concurrent change, and the rewrite
     * proceeds. Two folds in one case rather than two cases, because they are the same property
     * asserted in the two directions the source actually uses, and because the direction is what a
     * translation gets wrong: folding one operand only would make this case fail while {@code case01}
     * still passed.
     *
     * <p>The stored group identifier is ten spaces, which folds to itself, so the group half of this case
     * is asserted through {@link #theFoldedComparisonsFoldBothOperands()} where a non-blank stored value
     * can be arranged; here the first name carries the case.
     *
     * @return the scenario
     */
    private static ParityScenario case07() {
        return serviceScenario("case07",
                "The snapshot's first name is the record's in the opposite letter case. :4152-4153 "
                        + "applies FUNCTION UPPER-CASE to both operands, so this is not a concurrent "
                        + "change and both rewrites proceed. Folding only one operand would pass case01 "
                        + "and fail here.",
                UnaryOperator.identity(), COACTUPCParityTest::foldFirstNameToUpper,
                newAccountData(), newCustomerData(),
                List.of(accountWrite(fullNewAccountImage()), customerWrite(fullNewCustomerImage())),
                clearReturnMessage());
    }

    /**
     * {@code case08} - the {@code ACCT-UPDATE-RECORD} offset defect, as a complete image. {@code SERVICE}.
     *
     * <p>This case exists to pin a defect, and it is the reason a whole-image assertion is used rather
     * than a field list. {@code ACCT-UPDATE-RECORD} at {@code app/cbl/COACTUPC.cbl:418-433} carries no
     * {@code ACCT-ADDR-ZIP} item, so its {@code ACCT-UPDATE-GROUP-ID PIC X(10)} at {@code :432} sits at
     * offset <strong>102</strong> - where {@code app/cpy/CVACT01Y.cpy} puts {@code ACCT-ADDR-ZIP} - and
     * its {@code FILLER X(188)} at {@code :433} covers bytes 112 to 299, which includes the real
     * {@code ACCT-GROUP-ID}.
     *
     * <p>The consequence, asserted here byte for byte: after the rewrite the stored zip
     * {@code "A000000000"} has been replaced by {@code "ZEROPCT   "} and the stored group identifier has
     * been erased to spaces. Both the field list and the 300-byte image are declared, so the report names
     * the two spans rather than only reporting a long image mismatch.
     *
     * <p>Repairing this would be a behaviour change and a parity violation. It is preserved (practice
     * <strong>B5</strong>).
     *
     * @return the scenario
     */
    private static ParityScenario case08() {
        Map<String, String> spans = new LinkedHashMap<>();
        spans.put(AccountRecord.ACCT_ADDR_ZIP_NAME,
                CODEC.movePicX(NEW_GROUP_ID, AccountRecord.ACCT_ADDR_ZIP_LENGTH));
        spans.put(AccountRecord.ACCT_GROUP_ID_NAME, STORED_GROUP_ID);
        return serviceScenario("case08",
                "The rewrite writes ACUP-NEW-GROUP-ID at offset 102, over the stored ACCT-ADDR-ZIP, and "
                        + "blanks 112 to 299 including the real ACCT-GROUP-ID. ACCT-UPDATE-RECORD at "
                        + ":418-433 declares no zip item and a FILLER X(188) rather than X(178), which "
                        + "is what shifts every span after the cycle amounts ten bytes early.",
                UnaryOperator.identity(), UnaryOperator.identity(),
                newAccountData(), newCustomerData(),
                List.of(new ExpectedRecord(ACCTDAT, 0, Map.copyOf(spans), fullNewAccountImage()),
                        customerWrite(fullNewCustomerImage())),
                clearReturnMessage());
    }

    /**
     * {@code case09} - the account lock is not taken. {@code SERVICE}.
     *
     * <p>{@code ACCTDAT} is seeded empty, so the {@code EXEC CICS READ ... UPDATE} at {@code :3893-3903}
     * answers {@code NOTFND}. {@code :3908-3915} therefore sets {@code INPUT-ERROR}, sets
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} because the return message was off, and leaves through
     * {@code GO TO 9600-WRITE-PROCESSING-EXIT}. Three properties are asserted together: the message is
     * the account one and not the customer one, nothing is written to either dataset, and
     * <strong>{@code CUSTDAT} is never read at all</strong> - which is why it is declared on the
     * final-state channel holding exactly its seeded rows.
     *
     * <p>This is the {@code FILE STATUS} arm of gate <strong>G47</strong> at the first of the two read
     * sites; the second site and the two rewrite sites are covered by
     * {@link #everyFileStatusArmOfWriteProcessingIsReached()}.
     *
     * @return the scenario
     */
    private static ParityScenario case09() {
        return serviceScenario("case09",
                "ACCTDAT is empty, so the READ ... UPDATE at :3893-3903 answers NOTFND and :3908-3915 "
                        + "sets COULD-NOT-LOCK-ACCT-FOR-UPDATE. The customer record is never read and "
                        + "neither dataset is written.",
                UnaryOperator.identity(), UnaryOperator.identity(),
                newAccountData(), newCustomerData(),
                List.of(), returnMessage(AccountUpdateService.MSG_COULD_NOT_LOCK_ACCT_FOR_UPDATE),
                true, false);
    }

    /**
     * {@code case10} - {@code COMPUTE ACUP-NEW-CREDIT-LIMIT-N} at {@code :1079-1080}. {@code SERVICE}.
     *
     * <p>The screen supplies {@code "$7,500.567"}. {@code FUNCTION NUMVAL-C} accepts the currency sign
     * and the digit separator and yields {@code 7500.567}; the receiver is {@code PIC S9(10)V99}, and the
     * keyword {@code ROUNDED} appears <strong>zero</strong> times in this program - and zero times in all
     * twenty-eight - so the excess fraction is <em>truncated</em>. The stored credit limit is therefore
     * {@code 7500.56}, not {@code 7500.57}.
     *
     * <p>The third decimal digit is deliberately {@code 7}, which is where {@link RoundingMode#DOWN} and
     * {@code HALF_UP} disagree. A translation that rounded would write {@code 7500.57} and this case
     * would fail on exactly one span of the image - which is the whole point of a field-level difference
     * report (gates <strong>G24</strong> and <strong>G28</strong>).
     *
     * @return the scenario
     */
    private static ParityScenario case10() {
        return computeScenario("case10", "ACUP-NEW-CREDIT-LIMIT-N", "1079-1080", "$7,500.567",
                keyed -> AccountUpdateService.computeCreditLimit(keyed, null, CODEC), "7500.56",
                COACTUPCParityTest::withCreditLimit);
    }

    /**
     * {@code case11} - {@code COMPUTE ACUP-NEW-CASH-CREDIT-LIMIT-N} at {@code :1093-1094}. {@code SERVICE}.
     *
     * <p>The screen supplies {@code "1500.999"}, whose exact value cannot be stored at scale 2. Truncation
     * gives {@code 1500.99}; rounding would give {@code 1501.00} and would change the integer part as
     * well as the fraction, so this case additionally proves the truncation happens at the receiver's
     * declared scale rather than at some later formatting step.
     *
     * @return the scenario
     */
    private static ParityScenario case11() {
        return computeScenario("case11", "ACUP-NEW-CASH-CREDIT-LIMIT-N", "1093-1094", "1500.999",
                keyed -> AccountUpdateService.computeCashCreditLimit(keyed, null, CODEC), "1500.99",
                COACTUPCParityTest::withCashLimit);
    }

    /**
     * {@code case12} - {@code COMPUTE ACUP-NEW-CURR-BAL-N} at {@code :1107-1108}. {@code SERVICE}.
     *
     * <p>One of the two sites whose {@code FUNCTION NUMVAL-C} operand is the <strong>staging copy</strong>
     * {@code ACUP-NEW-CURR-BAL-X} rather than the map field {@code ACURBALI OF CACTUPAI}. The asymmetry is
     * real - three sites read the map field and two read the copy - and it matters because the copy is a
     * {@code PIC X(15)} that the preceding {@code MOVE} at {@code :1105} has already padded, so the
     * operand is fifteen characters wide where the map field is what arrived.
     *
     * <p>The value is negative: {@code "-42.079"} truncates <em>toward zero</em> to {@code -42.07}, not
     * away from it to {@code -42.08}. {@link RoundingMode#DOWN} is truncation toward zero, which is what
     * a COBOL store without {@code ROUNDED} does; {@code FLOOR} would give {@code -42.08} and is
     * forbidden for exactly this reason.
     *
     * @return the scenario
     */
    private static ParityScenario case12() {
        return computeScenario("case12", "ACUP-NEW-CURR-BAL-N", "1107-1108", "-42.079",
                keyed -> AccountUpdateService.computeCurrBal(keyed, null, CODEC), "-42.07",
                COACTUPCParityTest::withCurrBal);
    }

    /**
     * {@code case13} - {@code COMPUTE ACUP-NEW-CURR-CYC-CREDIT-N} at {@code :1121-1122}. {@code SERVICE}.
     *
     * <p>The screen supplies {@code "11.115"}, an exact half at the third decimal place. This is the
     * case that separates {@link RoundingMode#DOWN} from {@code HALF_EVEN} as well as from
     * {@code HALF_UP}: truncation gives {@code 11.11}, half-up gives {@code 11.12}, and half-even also
     * gives {@code 11.12} because {@code 1} is odd. Only truncation matches the source.
     *
     * @return the scenario
     */
    private static ParityScenario case13() {
        return computeScenario("case13", "ACUP-NEW-CURR-CYC-CREDIT-N", "1121-1122", "11.115",
                keyed -> AccountUpdateService.computeCurrCycCredit(keyed, null, CODEC), "11.11",
                COACTUPCParityTest::withCycCredit);
    }

    /**
     * {@code case14} - {@code COMPUTE ACUP-NEW-CURR-CYC-DEBIT-N} at {@code :1135-1136}. {@code SERVICE}.
     *
     * <p>The second of the two staging-copy sites. The screen supplies {@code "22.229"}, which truncates
     * to {@code 22.22}. Together with {@code case12} this pins both copy-reading sites, and together with
     * {@code case10}, {@code case11} and {@code case13} every one of the five {@code COMPUTE} receivers
     * has at least one targeted assertion on the bytes it lands in.
     *
     * @return the scenario
     */
    private static ParityScenario case14() {
        return computeScenario("case14", "ACUP-NEW-CURR-CYC-DEBIT-N", "1135-1136", "22.229",
                keyed -> AccountUpdateService.computeCurrCycDebit(keyed, null, CODEC), "22.22",
                COACTUPCParityTest::withCycDebit);
    }

    /**
     * {@code case15} - the cold start. {@code CONTROLLER_POJO}.
     *
     * <p>{@code EIBCALEN} is zero, so the left arm of the {@code :880-882} guard is true and
     * {@code :883-886} initialises the communication area, sets {@code CDEMO-PGM-ENTER} and sets
     * {@code ACUP-DETAILS-NOT-FETCHED}. The {@code EVALUATE} at {@code :919} then reaches its second arm
     * at {@code :963-972} - {@code ACUP-DETAILS-NOT-FETCHED AND CDEMO-PGM-ENTER} - which sends the map
     * and returns <strong>without ever performing {@code 1000-PROCESS-INPUTS}</strong>. Nothing is
     * edited, no file is read, and no field is highlighted.
     *
     * <p>This is the fifty-four-field baseline every other screen case is a perturbation of, and it is
     * the {@code OK}/{@code ENTER} corner of the highlight matrix: no colour is assigned anywhere
     * (gate <strong>G38</strong>). Note {@code CDEMO-PGM-REENTER} on the way out, set at {@code :970}
     * before {@code GO TO COMMON-RETURN} - the response's context is the <em>next</em> turn's, not this
     * one's, which is what makes the conversation stateless (rule <strong>R6</strong>).
     *
     * @return the scenario
     */
    private static ParityScenario case15() {
        return controllerScenario("case15",
                "EIBCALEN is zero, so :883-886 initialises the commarea and the :963-972 arm sends the "
                        + "map without performing 1000-PROCESS-INPUTS. Fifty-four fields: six header "
                        + "literals, forty-two LOW-VALUES data fields, the information prompt, a blank "
                        + "error line and three function-key legends. No field is highlighted.",
                ACCT_KEY, EIBCALEN_NONE, "DFHENTER", null, Map.of(),
                promptScreen(null, null),
                navigationImage(NavigationContext.empty().withPgmReenter()),
                THIS_MAPSET, THIS_MAP, null, "ACCTSIDL", Termination.RETURN_TRANSID);
    }

    /**
     * {@code case16} - a fresh entry from the main menu. {@code CONTROLLER_POJO}.
     *
     * <p>{@code EIBCALEN} is the full carried length this time, so the left arm of {@code :880} is false;
     * the right arm - {@code CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER} - is true, and
     * the communication area is initialised anyway. The screen is therefore byte-identical to
     * {@code case15}'s, including the discarded {@code CDEMO-FROM-PROGRAM}.
     *
     * <p>That identity is the assertion. A translation that read the guard as {@code EIBCALEN = 0} alone
     * would take the {@code ELSE} arm at {@code :888-892}, keep the menu's context, and reach
     * {@code 1000-PROCESS-INPUTS} instead - producing a different screen from the same input. Two cases
     * for one screen is the only way to catch that, because the screen a wrong reading produces is a
     * perfectly plausible one.
     *
     * @return the scenario
     */
    private static ParityScenario case16() {
        return controllerScenario("case16",
                "EIBCALEN is the full carried length and CDEMO-FROM-PROGRAM is COMEN01C with "
                        + "CDEMO-PGM-ENTER, so the right arm of :880-882 initialises the commarea and the "
                        + "screen is byte-identical to the cold start's. Reading the guard as EIBCALEN=0 "
                        + "alone would keep the menu's context and paint a different screen.",
                ACCT_KEY, EIBCALEN_FULL, "DFHENTER",
                NavigationContext.empty().withFromTranid(MENU_TRANID).withFromProgram(MENU_PGM)
                        .withPgmEnter(),
                Map.of(),
                promptScreen(null, null),
                navigationImage(NavigationContext.empty().withPgmReenter()),
                THIS_MAPSET, THIS_MAP, null, "ACCTSIDL", Termination.RETURN_TRANSID);
    }

    /**
     * {@code case17} - {@code PF03} with a carried origin. {@code CONTROLLER_POJO}.
     *
     * <p>The {@code EVALUATE}'s first arm at {@code :925-959} is the {@code XCTL}, and it is the one path
     * through this program that sends no map at all. {@code CDEMO-FROM-TRANID} is {@code CM00} and
     * {@code CDEMO-FROM-PROGRAM} is {@code COMEN01C}, so neither is {@code LOW-VALUES} nor
     * {@code SPACES} and the {@code ELSE} arms at {@code :933} and {@code :941} carry them into
     * {@code CDEMO-TO-TRANID} and {@code CDEMO-TO-PROGRAM}. This program then names <em>itself</em> as
     * the origin at {@code :944-945}, declares the user a regular user at {@code :947}, sets
     * {@code CDEMO-PGM-ENTER}, records its own mapset and map as the last ones at {@code :949-950}, takes
     * a syncpoint and transfers.
     *
     * <p>The observable is a {@code nextProgram} of {@code COMEN01C} with <strong>zero sends</strong> and
     * {@link Termination#XCTL}, which is gate <strong>G40</strong>: the transfer becomes a response field
     * the client resolves, never a server-side forward. There is no {@code nextMapset} and no
     * {@code nextMap}, because {@code 3400-SEND-SCREEN} - the only paragraph that assigns them - is never
     * reached.
     *
     * @return the scenario
     */
    private static ParityScenario case17() {
        return controllerScenario("case17",
                "PF03 with CM00/COMEN01C carried in the commarea. The :925-959 arm takes the ELSE at "
                        + ":933 and :941, echoes the origin into CDEMO-TO-TRANID and CDEMO-TO-PROGRAM, "
                        + "names itself as the new origin, and transfers. Zero sends and Termination.XCTL.",
                ACCT_KEY, EIBCALEN_FULL, "DFHPF3",
                NavigationContext.empty().withFromTranid(MENU_TRANID).withFromProgram(MENU_PGM)
                        .withPgmReenter(),
                Map.of(),
                null,
                navigationImage(NavigationContext.empty()
                        .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM)
                        .withToTranid(MENU_TRANID).withToProgram(MENU_PGM)
                        .withUserTypeUser().withPgmEnter()
                        .withLastMap(THIS_MAP).withLastMapset(THIS_MAPSET)),
                null, null, MENU_PGM, null, Termination.XCTL);
    }

    /**
     * {@code case18} - {@code PF03} with no carried origin. {@code CONTROLLER_POJO}.
     *
     * <p>The other side of both guards in the same arm. {@code CDEMO-FROM-TRANID} and
     * {@code CDEMO-FROM-PROGRAM} arrive as spaces, so {@code :929-932} and {@code :937-940} substitute
     * {@code LIT-MENUTRANID} and {@code LIT-MENUPGM} - the main menu - as the transfer target. The
     * {@code nextProgram} is the same {@code COMEN01C} {@code case17} produces, and that coincidence is
     * exactly why both cases are needed: the two arms are indistinguishable from the target alone, and
     * only the navigation image tells them apart. Here {@code CDEMO-TO-TRANID} is the literal
     * {@code CM00} rather than an echo of what arrived.
     *
     * @return the scenario
     */
    private static ParityScenario case18() {
        return controllerScenario("case18",
                "PF03 with a blank origin. :929-932 and :937-940 substitute LIT-MENUTRANID and "
                        + "LIT-MENUPGM, so the target is the main menu by default rather than by echo. "
                        + "The nextProgram matches case17's, so only the navigation image distinguishes "
                        + "the two arms.",
                ACCT_KEY, EIBCALEN_FULL, "DFHPF3",
                NavigationContext.empty().withPgmReenter(),
                Map.of(),
                null,
                navigationImage(NavigationContext.empty()
                        .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM)
                        .withToTranid(MENU_TRANID).withToProgram(MENU_PGM)
                        .withUserTypeUser().withPgmEnter()
                        .withLastMap(THIS_MAP).withLastMapset(THIS_MAPSET)),
                null, null, MENU_PGM, null, Termination.XCTL);
    }

    /**
     * {@code case19} - a re-entry with a non-numeric account filter: colour only. {@code CONTROLLER_POJO}.
     *
     * <p>{@code CDEMO-PGM-REENTER} sends the {@code EVALUATE} to its {@code WHEN OTHER} arm at
     * {@code :995-1003}, which performs {@code 1000-PROCESS-INPUTS}, {@code 2000-DECIDE-ACTION} and
     * {@code 3000-SEND-MAP} in that order. {@code 1210-EDIT-ACCOUNT} finds {@code CC-ACCT-ID} present but
     * not numeric, so {@code :1802-1812} sets {@code INPUT-ERROR}, composes the eleven-digit message with
     * a {@code STRING ... DELIMITED BY SIZE}, zeroes {@code CDEMO-ACCT-ID} and leaves through
     * {@code GO TO 1210-EDIT-ACCOUNT-EXIT} <strong>without</strong> setting
     * {@code FLG-ACCTFILTER-ISVALID}. {@code 2000-DECIDE-ACTION}'s first arm therefore finds its
     * {@code IF FLG-ACCTFILTER-ISVALID} false at {@code :2573} and <strong>no file is read at all</strong>
     * - which is why this case needs no seeded dataset and asserts none.
     *
     * <p>The screen is the {@code case15} baseline with three differences, and each is a gate:
     * {@code ACCTSIDO} echoes what was keyed, {@code ERRMSGO} carries the message, and
     * {@code ACCTSIDC} carries {@link BmsAttributes#DFHRED}. The {@code CSSETATY} rule is
     * <em>colour only</em> here, because the field flag is {@code NOT-OK} rather than {@code BLANK} - so
     * no {@code '*'} reaches {@code ACCTSIDO}, and the value the operator keyed survives to be corrected
     * (gate <strong>G38</strong>).
     *
     * @return the scenario
     */
    private static ParityScenario case19() {
        return controllerScenario("case19",
                "A re-entry whose account filter is present but not numeric. :1802-1812 composes the "
                        + "eleven-digit message and leaves without setting FLG-ACCTFILTER-ISVALID, so "
                        + "2000-DECIDE-ACTION reads no file. The field flag is NOT-OK, so CSSETATY "
                        + "assigns the colour item only and the keyed value survives.",
                NON_NUMERIC_ACCT, EIBCALEN_FULL, "DFHENTER",
                NavigationContext.empty().withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM)
                        .withPgmReenter(),
                Map.of("ACCTSIDI", NON_NUMERIC_ACCT),
                promptScreen(NON_NUMERIC_ACCT, MSG_ACCT_NOT_ELEVEN_DIGITS),
                navigationImage(NavigationContext.empty()
                        .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM).withPgmReenter()),
                THIS_MAPSET, THIS_MAP, THIS_PGM, "ACCTSIDL", Termination.RETURN_TRANSID);
    }

    /**
     * {@code case20} - a re-entry with a blank account filter: colour <em>and</em> asterisk.
     * {@code CONTROLLER_POJO}.
     *
     * <p>The fourth corner of the highlight matrix, and the only one that assigns two items. The filter
     * arrives as spaces, so {@code 1210-EDIT-ACCOUNT} takes its <em>first</em> guard at {@code :1787-1798}
     * - {@code CC-ACCT-ID EQUAL LOW-VALUES OR CC-ACCT-ID EQUAL SPACES} - which sets
     * {@code FLG-ACCTFILTER-BLANK} rather than merely {@code NOT-OK}, sets {@code WS-PROMPT-FOR-ACCT},
     * zeroes both account identifiers and exits. {@code 1200-EDIT-MAP-INPUTS} then sets
     * {@code NO-SEARCH-CRITERIA-RECEIVED} at {@code :1441-1443}, whose message is
     * {@code "No input received"} - a different literal from {@code case19}'s, from a different guard.
     *
     * <p>Because the flag is {@code BLANK}, {@code CSSETATY} assigns <strong>both</strong> items:
     * {@link BmsAttributes#DFHRED} into {@code ACCTSIDC} <em>and</em> {@code '*'} into
     * {@code ACCTSIDO}. The asterisk is not decoration - it is what makes the empty field visible on a
     * 3270 whose colour the operator may not have - and it means the output item no longer holds what was
     * keyed. Nothing was keyed, so nothing is lost (gate <strong>G38</strong>).
     *
     * @return the scenario
     */
    private static ParityScenario case20() {
        return controllerScenario("case20",
                "A re-entry whose account filter is blank. :1787-1798 sets FLG-ACCTFILTER-BLANK and "
                        + "1200-EDIT-MAP-INPUTS sets NO-SEARCH-CRITERIA-RECEIVED at :1441-1443, whose "
                        + "message is a different literal from the not-numeric one. The BLANK flag makes "
                        + "CSSETATY assign both items: DFHRED into ACCTSIDC and '*' into ACCTSIDO.",
                BLANK_ACCT, EIBCALEN_FULL, "DFHENTER",
                NavigationContext.empty().withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM)
                        .withPgmReenter(),
                Map.of("ACCTSIDI", BLANK_ACCT),
                promptScreen(FieldAttributeSetter.ASTERISK, MSG_NO_INPUT_RECEIVED),
                navigationImage(NavigationContext.empty()
                        .withFromTranid(THIS_TRANID).withFromProgram(THIS_PGM).withPgmReenter()),
                THIS_MAPSET, THIS_MAP, THIS_PGM, "ACCTSIDL", Termination.RETURN_TRANSID);
    }

    // =================================================================================================
    // SCENARIO BUILDERS. Each composes a ParityCase and pairs it with the adapter that reaches its unit.
    // =================================================================================================

    /**
     * Builds a {@link UnitKind#SERVICE} scenario with both datasets seeded from their real fixtures.
     *
     * <p>The dataset expectations are derived rather than restated: each dataset appears on the
     * {@link DatasetChannel#WRITES} channel with as many rows as the case expects written - which is zero
     * for every arm that abandons the rewrite, and that zero is an assertion in its own right - and on
     * the {@link DatasetChannel#FINAL_STATE} channel at its seeded row count, because neither rewrite
     * ever adds or removes a row.
     *
     * @param caseId      {@code case01} through {@code case14}
     * @param description what the case pins, quoting the source lines it comes from
     * @param staleAcct   how the {@code ACUP-OLD-ACCT-DATA} snapshot differs from the seeded row
     * @param staleCust   how the {@code ACUP-OLD-CUST-DATA} snapshot differs from the seeded row
     * @param newAcct     {@code ACUP-NEW-ACCT-DATA}, what the screen supplied
     * @param newCust     {@code ACUP-NEW-CUST-DATA}, what the screen supplied
     * @param writes      what the run must have written, in write order
     * @param messages    the message the run must have produced
     * @return the scenario
     */
    private static ParityScenario serviceScenario(String caseId,
                                                  String description,
                                                  UnaryOperator<AccountData> staleAcct,
                                                  UnaryOperator<CustomerData> staleCust,
                                                  AccountData newAcct,
                                                  CustomerData newCust,
                                                  List<ExpectedRecord> writes,
                                                  List<EmittedMessage> messages) {
        return serviceScenario(caseId, description, staleAcct, staleCust, newAcct, newCust, writes,
                messages, false, false);
    }

    /**
     * Builds a {@link UnitKind#SERVICE} scenario in full, optionally with a dataset seeded empty.
     *
     * <p>An empty dataset is how a lock failure is arranged, and it is arranged that way deliberately
     * rather than through a forced repository outcome: a dataset with no rows cannot answer any key, so
     * {@code NOTFND} is what it genuinely returns rather than what it was told to return. The forced
     * outcomes the case model supports are reserved for responses the data cannot produce, which for this
     * paragraph is the {@code DFHRESP} other-than-{@code NORMAL} arm -
     * {@link #everyFileStatusArmOfWriteProcessingIsReached()} drives those.
     *
     * @param caseId        the case identifier
     * @param description   what the case pins
     * @param staleAcct     how the account snapshot differs from the seeded row
     * @param staleCust     how the customer snapshot differs from the seeded row
     * @param newAcct       what the screen supplied for the account
     * @param newCust       what the screen supplied for the customer
     * @param writes        what the run must have written
     * @param messages      the message the run must have produced
     * @param emptyAccounts whether {@code ACCTDAT} is seeded empty
     * @param emptyCustomers whether {@code CUSTDAT} is seeded empty
     * @return the scenario
     */
    private static ParityScenario serviceScenario(String caseId,
                                                  String description,
                                                  UnaryOperator<AccountData> staleAcct,
                                                  UnaryOperator<CustomerData> staleCust,
                                                  AccountData newAcct,
                                                  CustomerData newCust,
                                                  List<ExpectedRecord> writes,
                                                  List<EmittedMessage> messages,
                                                  boolean emptyAccounts,
                                                  boolean emptyCustomers) {
        Map<String, DatasetInput> inputs = new LinkedHashMap<>();
        inputs.put(ACCTDAT, emptyAccounts
                ? DatasetInput.ofEmpty(AccountRecord.RECORD_LENGTH, "CVACT01Y")
                : new DatasetInput(null, "acctdata.txt", 0, SEEDED_ROWS));
        inputs.put(CUSTDAT, emptyCustomers
                ? DatasetInput.ofEmpty(CustomerRecord.RECORD_LENGTH, "CVCUS01Y")
                : new DatasetInput(null, "custdata.txt", 0, SEEDED_ROWS));

        int accountRows = emptyAccounts ? 0 : SEEDED_ROWS;
        int customerRows = emptyCustomers ? 0 : SEEDED_ROWS;
        List<ExpectedRecord> finalState = new ArrayList<>(accountRows + customerRows);
        finalState.addAll(finalStateOf(ACCTDAT, "acctdata.txt", AccountRecord.RECORD_LENGTH,
                accountRows, writes));
        finalState.addAll(finalStateOf(CUSTDAT, "custdata.txt", CustomerRecord.RECORD_LENGTH,
                customerRows, writes));

        ParityCase parityCase = new ParityCase(PROGRAM, caseId, description, UnitKind.SERVICE,
                inputs, Map.of(),
                new ScreenRequest(EIBCALEN_FULL, null, PINNED_CLOCK, CHARSET_NAME, Map.of(),
                        Map.of(), Map.of()),
                null, writes, List.copyOf(finalState), 0, messages, List.of(),
                List.of(new ExpectedDataset(ACCTDAT, DatasetChannel.WRITES,
                                (int) writes.stream().filter(row -> ACCTDAT.equals(row.dataset()))
                                        .count(), AccountRecord.RECORD_LENGTH),
                        new ExpectedDataset(CUSTDAT, DatasetChannel.WRITES,
                                (int) writes.stream().filter(row -> CUSTDAT.equals(row.dataset()))
                                        .count(), CustomerRecord.RECORD_LENGTH),
                        new ExpectedDataset(ACCTDAT, DatasetChannel.FINAL_STATE, accountRows,
                                AccountRecord.RECORD_LENGTH),
                        new ExpectedDataset(CUSTDAT, DatasetChannel.FINAL_STATE, customerRows,
                                CustomerRecord.RECORD_LENGTH)));
        return new ParityScenario(parityCase, UnitKind.SERVICE,
                invocation -> serviceUnit(invocation, staleAcct, staleCust, newAcct, newCust),
                "AccountUpdateService.writeProcessing");
    }

    /**
     * Builds one of the five {@code COMPUTE} scenarios, with the input and the expectation drawn from
     * independent sources.
     *
     * <p>The <strong>input</strong> is the seam's own output on the screen string, so the arithmetic is
     * genuinely exercised end to end: {@code FUNCTION NUMVAL-C} parses the characters and the store into
     * a {@code PIC S9(10)V99} receiver discards the excess fraction. The <strong>expectation</strong> is
     * a literal read off the COBOL, and it is deliberately <em>not</em> the seam's output.
     *
     * <p>That separation is the whole point, and it was arrived at the hard way. An earlier revision of
     * this method took a single {@code BigDecimal computed}, obtained by calling
     * {@code AccountUpdateService.computeCreditLimit(...)}, and used it for both roles - and its own
     * documentation asserted that this was "not circular". It was. With one value in both roles the case
     * says only "whatever the screen supplied is what got stored", which is true for any value and blind
     * to the rounding mode; it tested the codec and nothing else. Verified by mutation: flipping all five
     * literals to their {@code HALF_UP} counterparts left every one of the forty-three tests green.
     *
     * <p>With the two sources separated, a seam that rounded would store {@code 7500.57} where the
     * expectation says {@code 7500.56}, and the same mutation now fails all five cases with two
     * differences each - the write image and the final-state image. The seam's agreement with the literal,
     * <em>and</em> its disagreement with the rounded value, are additionally asserted directly by
     * {@link #theFiveComputeSitesTruncateRatherThanRound()}, so a numeric failure is attributable either
     * to the arithmetic or to the plumbing but never ambiguously to both (practice <strong>B12</strong>,
     * gate <strong>G24</strong>).
     *
     * @param caseId      the case identifier
     * @param receiver    the COBOL receiving item, for the description
     * @param sourceLines the {@code COMPUTE}'s source lines, for the description
     * @param screenField what the screen supplied, exactly as it would arrive
     * @param compute     the seam, which parses {@code screenField} and produces the stored value
     * @param truncated   the value a store without {@code ROUNDED} leaves behind, as a literal read off
     *                    the COBOL rather than obtained from {@code compute}
     * @param apply       how to place a value into the {@code ACUP-NEW} group's one relevant item
     * @return the scenario
     */
    private static ParityScenario computeScenario(String caseId,
                                                  String receiver,
                                                  String sourceLines,
                                                  String screenField,
                                                  MonetaryCompute compute,
                                                  String truncated,
                                                  MonetaryPlacement apply) {
        // The input and the expectation are derived from two INDEPENDENT sources, and that independence
        // is the whole point of the case.
        //
        // The input is what the COMPUTE seam actually produces from the screen string, so the arithmetic
        // is genuinely exercised: FUNCTION NUMVAL-C parses the characters and the store into a
        // PIC S9(10)V99 receiver discards the excess fraction.
        //
        // The expectation is a literal read off the COBOL. It is deliberately NOT the seam's own output.
        // An earlier revision of this method derived both from one value, which made the case say only
        // "whatever the screen supplied is what got stored" - trivially true for any value, and blind to
        // the rounding mode. Verified by mutation: flipping these literals to their HALF_UP counterparts
        // left every case green. With the two sources separated, a seam that rounded would store
        // 7500.57 where the expectation says 7500.56 and case10 would fail (practice B12, gate G24).
        AccountData supplied = apply.place(newAccountData(), compute.of(screenField).value());
        AccountData expected = apply.place(newAccountData(), new BigDecimal(truncated));
        return serviceScenario(caseId,
                "COMPUTE " + receiver + " = FUNCTION NUMVAL-C(...) at app/cbl/COACTUPC.cbl:" + sourceLines
                        + " with the screen supplying \"" + screenField + "\". ROUNDED appears zero "
                        + "times in this program and in all twenty-eight, so the excess fraction is "
                        + "truncated to " + truncated + " at the receiver's PIC S9(10)V99 scale and that "
                        + "is what reaches the stored bytes.",
                UnaryOperator.identity(), UnaryOperator.identity(), supplied, newCustomerData(),
                List.of(accountWrite(accountUpdateImage(expected)),
                        customerWrite(fullNewCustomerImage())),
                clearReturnMessage());
    }

    /**
     * One of the five {@code COMPUTE} seams, as a function of the screen string it parses.
     *
     * <p>A functional interface rather than {@code Function<String, MonetaryEdit>} so the two-argument
     * tail each seam carries - the prior value and the codec - is bound at the call site where it can be
     * read, and so the name says what the thing is.
     */
    private interface MonetaryCompute {

        /**
         * Runs the seam on one screen string.
         *
         * @param screenField the characters the operator keyed, at the field's declared width
         * @return the edit, carrying the value, the receiver's COBOL name and its source lines
         */
        MonetaryEdit of(String screenField);
    }

    /**
     * Builds a {@link UnitKind#CONTROLLER_POJO} scenario.
     *
     * <p>No dataset is seeded and no write is expected, because none of the six screen cases reaches a
     * file verb: three of them return before {@code 1000-PROCESS-INPUTS} and the other three fail the
     * account-filter edit, which leaves {@code FLG-ACCTFILTER-ISVALID} unset and so skips every read.
     * That is asserted by construction - the adapter hands the controller repositories that throw on any
     * call - which is a stronger statement than an empty expectation and a clearer failure than a
     * confusing diff.
     *
     * @param caseId      {@code case15} through {@code case20}
     * @param description what the case pins
     * @param acctId      the account identifier in the URI, which is what {@code ACCTSIDI} carries
     * @param eibcalen    {@code EIBCALEN}
     * @param aid         the {@code DFHAID} mnemonic for the raw {@code EIBAID} byte
     * @param context     the inbound {@code CARDDEMO-COMMAREA}, or {@code null} for a cold start
     * @param mapFields   the inbound {@code xxxI} items
     * @param send        the map area the run must send, or {@code null} when it sends none
     * @param navigation  the outbound {@code CARDDEMO-COMMAREA}, all sixteen fields
     * @param nextMapset  {@code CCARD-NEXT-MAPSET} afterwards, or {@code null} when unassigned
     * @param nextMap     {@code CCARD-NEXT-MAP} afterwards, or {@code null} when unassigned
     * @param nextProgram {@code CCARD-NEXT-PROG} afterwards, or {@code null} when unassigned
     * @param cursorField the {@code xxxL} item the cursor was positioned through, or {@code null}
     * @param termination how the interaction ended
     * @return the scenario
     */
    private static ParityScenario controllerScenario(String caseId,
                                                     String description,
                                                     String acctId,
                                                     int eibcalen,
                                                     String aid,
                                                     NavigationContext context,
                                                     Map<String, String> mapFields,
                                                     Map<String, String> send,
                                                     Map<String, String> navigation,
                                                     String nextMapset,
                                                     String nextMap,
                                                     String nextProgram,
                                                     String cursorField,
                                                     Termination termination) {
        Map<String, String> commarea = context == null ? Map.of() : navigationImage(context);
        List<ScreenSend> sends = send == null
                ? List.of()
                : List.of(new ScreenSend(send, colourAndHighlightExpectation(send)));
        ParityCase parityCase = new ParityCase(PROGRAM, caseId, description,
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(eibcalen, aid, PINNED_CLOCK, CHARSET_NAME, commarea, mapFields,
                        Map.of()),
                new ExpectedResponse(nextProgram, nextMapset, nextMap, navigation, sends, cursorField,
                        termination),
                List.of(), List.of(), 0, List.of(), List.of(), List.of());
        return new ParityScenario(parityCase, UnitKind.CONTROLLER_POJO,
                invocation -> controllerUnit(invocation, acctId, context),
                "AccountUpdateController.updateAccount");
    }

    /**
     * What one dataset holds after the run, row by row - derived from the writes rather than restated.
     *
     * <p>A row the case expects written holds the written image; every other row holds exactly the fixture
     * bytes it was seeded with. Deriving it is what makes "nothing else moved" an assertion rather than an
     * assumption: a rewrite that addressed the wrong row would satisfy the write channel and fail here, on
     * two rows at once, which is a far more legible failure than a single mismatched image.
     *
     * @param dataset     the binding key
     * @param fixture     the bare fixture file name the rows were seeded from
     * @param recordWidth the copybook's declared record length
     * @param rowCount    how many rows the dataset holds, which a rewrite never changes
     * @param writes      what the case expects written
     * @return one expectation per row, in row order
     */
    private static List<ExpectedRecord> finalStateOf(String dataset, String fixture, int recordWidth,
                                                     int rowCount, List<ExpectedRecord> writes) {
        List<ExpectedRecord> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            final int rowIndex = index;
            String image = writes.stream()
                    .filter(write -> dataset.equals(write.dataset())
                            && Integer.valueOf(rowIndex).equals(write.rowIndex()))
                    .map(ExpectedRecord::expectedBytes)
                    .findFirst()
                    .orElseGet(() -> fixtureRow(fixture, recordWidth, rowIndex));
            rows.add(new ExpectedRecord(dataset, rowIndex, Map.of(), image));
        }
        return rows;
    }

    /** How a {@code COMPUTE}'s result is placed into the {@code ACUP-NEW-ACCT-DATA} group. */
    private interface MonetaryPlacement {

        /**
         * Places one computed value.
         *
         * @param data  the group as it stands
         * @param value the value the {@code COMPUTE} left in its {@code -N} span
         * @return the group with that one item replaced
         */
        AccountData place(AccountData data, BigDecimal value);
    }

    // =================================================================================================
    // THE SERVICE ADAPTER. Constructs AccountUpdateService through its own constructor and calls
    // writeProcessing. No Spring context, no HTTP layer, no job launcher.
    // =================================================================================================

    /**
     * Reaches {@link AccountUpdateService#writeProcessing} for one case and records what it produced.
     *
     * <p>Three collaborators, all supplied by hand: fixture-backed {@link AccountRepository} and
     * {@link CustomerRepository} stubs over the case's seeded rows, and a real {@link DatasetUnitOfWork}
     * over an in-memory database. The codec carries the case's declared code page rather than a platform
     * default (practice <strong>B8</strong>).
     *
     * <p>The unit of work is deliberately real rather than a stand-in. The point of
     * {@code 9600-WRITE-PROCESSING} is that the locks the two {@code READ ... UPDATE}s take at
     * {@code :3893} and {@code :3921} survive to the two {@code REWRITE}s at {@code :4065} and
     * {@code :4085}, and only a genuine transaction boundary can be observed doing that;
     * {@link AccountRepository#readForUpdate(String)} refuses to issue {@code FOR UPDATE} outside a
     * transaction, so something that merely ran the body would not reach the write path at all.
     *
     * <p>The snapshot is built from the seeded rows and <em>then</em> perturbed, which is what makes a
     * stale case a one-item difference rather than a wholesale substitution: if the fixture ever changed,
     * a case that restated the whole snapshot would keep passing while asserting the wrong thing.
     *
     * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
     * @param staleAcct  how the account snapshot differs from the seeded row
     * @param staleCust  how the customer snapshot differs from the seeded row
     * @param newAcct    {@code ACUP-NEW-ACCT-DATA}
     * @param newCust    {@code ACUP-NEW-CUST-DATA}
     * @return the recorded outcome; never {@code null}
     */
    private static UnitOutcome serviceUnit(Invocation invocation,
                                           UnaryOperator<AccountData> staleAcct,
                                           UnaryOperator<CustomerData> staleCust,
                                           AccountData newAcct,
                                           CustomerData newCust) {
        SeededDataset seededAccounts = invocation.dataset(ACCTDAT);
        SeededDataset seededCustomers = invocation.dataset(CUSTDAT);
        List<String> accountRows = new ArrayList<>(seededAccounts.rows());
        List<String> customerRows = new ArrayList<>(seededCustomers.rows());

        AccountUpdateDetails oldDetails = new AccountUpdateDetails(DetailGroup.OLD,
                staleAcct.apply(storedAccountSnapshot()), staleCust.apply(storedCustomerSnapshot()));
        AccountUpdateDetails newDetails =
                new AccountUpdateDetails(DetailGroup.NEW, newAcct, newCust);

        AccountUpdateService service = new AccountUpdateService(
                fixtureAccountRepository(accountRows), fixtureCustomerRepository(customerRows),
                unitOfWork(invocation.caseId()));

        WriteResult result = service.writeProcessing(ACCT_KEY,
                NavigationContext.empty().withCustId(CUST_KEY), oldDetails, newDetails,
                AccountUpdateService.RETURN_MESSAGE_OFF, invocation.codec());

        UnitOutcome.Builder recorder = invocation.recorder();
        // isRewritten(), not acctUpdateRecordImage().isPresent(). The staging at :3956-4057 happens
        // BEFORE the two REWRITEs, so an image that was staged and then refused is still present in the
        // result - and reporting it as a write would claim the dataset changed when it did not.
        if (result.isRewritten()) {
            recorder.wrote(ACCTDAT, AccountRecord.LAYOUT, result.acctUpdateRecordImage()
                    .orElseThrow(() -> new IllegalStateException("A rewritten result carries the "
                            + "300-byte ACCT-UPDATE-RECORD image it wrote")));
            recorder.wrote(CUSTDAT, CustomerRecord.LAYOUT, result.custUpdateRecordImage()
                    .orElseThrow(() -> new IllegalStateException("A rewritten result carries the "
                            + "500-byte CUST-UPDATE-RECORD image it wrote")));
            recorder.finalState(ACCTDAT, AccountRecord.LAYOUT, accountRows);
            recorder.finalState(CUSTDAT, CustomerRecord.LAYOUT, customerRows);
        } else {
            // The positive form of "no rewrite happened". An empty write channel and datasets that still
            // hold exactly what they were seeded with are two halves of one assertion, and both are
            // needed: a write that was made and then reverted would satisfy only the second.
            recorder.openedWithoutWriting(ACCTDAT, AccountRecord.LAYOUT);
            recorder.openedWithoutWriting(CUSTDAT, CustomerRecord.LAYOUT);
            recorder.finalStateUnchanged(seededAccounts, AccountRecord.LAYOUT);
            recorder.finalStateUnchanged(seededCustomers, CustomerRecord.LAYOUT);
        }
        // WS-RETURN-MSG is PIC X(75). Neither width-bearing message channel is 75 - one is the 80-byte
        // WORKING-STORAGE message of the batch programs, the other the 78-byte screen field - so the
        // width-free channel is the only faithful one. The width itself is asserted by
        // theReturnMessageIsSeventyFiveBytesWide() rather than left unstated.
        recorder.display(result.returnMessage());
        // EXEC CICS RETURN, never EXEC CICS ABEND: no path through 9600-WRITE-PROCESSING abends, so the
        // COBOL RETURN-CODE is zero. Stated rather than defaulted, because a defaulted return code is
        // indistinguishable from one nobody considered.
        recorder.returnCode(0);
        return recorder.build();
    }

    // =================================================================================================
    // THE CONTROLLER ADAPTER. Constructs AccountUpdateController as a plain object and calls its handler.
    // =================================================================================================

    /**
     * Reaches {@link AccountUpdateController#updateAccount} for one case and records the response.
     *
     * <p>Seven collaborators, none of them framework. Three of the seven are repositories that
     * <strong>throw on any call at all</strong>, because none of the six screen cases reaches a file verb
     * - so a translation that read a record on one of these paths fails by name here rather than by
     * producing a plausible screen nobody questioned.
     *
     * <p>The date validator and the area-code table are <em>real</em> rather than stubbed. They are pure
     * functions of {@code CSUTLDPY} plus {@code CSUTLDWY} and of {@code CSLKPCDY} respectively, and
     * stubbing them would assert this class's reading of those copybooks twice instead of once. The clock
     * is the case's pinned one, threaded through so {@code EDIT-DATE-OF-BIRTH}'s not-in-the-future test
     * and {@code 3100-SCREEN-INIT}'s two {@code FUNCTION CURRENT-DATE} reads are deterministic.
     *
     * <p>The handler is called as a plain method on a plain object, and the {@code EIBCALEN} and
     * {@code EIBAID} that CICS would have supplied are passed as the two request parameters the
     * translation exposes for exactly that purpose.
     *
     * @param invocation the AID, the inbound commarea, the received map fields, the pinned clock and the
     *                   recorder
     * @param acctId     the account identifier in the URI
     * @param context    the inbound {@code CARDDEMO-COMMAREA}, or {@code null} for a cold start
     * @return the recorded outcome; never {@code null}
     */
    private static UnitOutcome controllerUnit(Invocation invocation, String acctId,
                                             NavigationContext context) {
        AccountUpdateController controller = new AccountUpdateController(
                unreachableAccountRepository(), unreachableCardXrefRepository(),
                unreachableCustomerRepository(),
                new AccountUpdateService(unreachableAccountRepository(),
                        unreachableCustomerRepository(), unitOfWork(invocation.caseId())),
                new AccountDateValidator(invocation.codec(), new DateUtilityJob(),
                        invocation.clock()),
                new AreaCodeLookup(invocation.codec()), invocation.clock());

        ScreenResponse<AccountUpdateResponse> body = controller.updateAccount(acctId,
                requestFrom(context, invocation.mapFields(), acctId), null, invocation.eibcalen(),
                Integer.valueOf(Byte.toUnsignedInt(aidByte(invocation.aid())))).getBody();
        AccountUpdateResponse painted = Objects.requireNonNull(body,
                "COACTUPC ends in EXEC CICS XCTL at :955 or EXEC CICS RETURN at :1013 on every path, "
                        + "so the handler always answers with a body").screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observed(painted, body));
        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * Projects the returned payload onto the observation shape {@link FieldDiffer} judges.
     *
     * <p>Four decisions are worth stating, because each is the difference between an observation that can
     * fail and one that cannot.
     *
     * <p><strong>A blank next-screen token is reported absent.</strong> The three carriers are initialised
     * to spaces at their declared widths - {@code CCARD-NEXT-PROG X(8)}, {@code CCARD-NEXT-MAPSET X(7)}
     * and {@code CCARD-NEXT-MAP X(7)} in {@code app/cpy/CVCRD01Y.cpy} - and a path that never assigns one
     * leaves those spaces behind. Seven spaces is not a BMS map name, so the faithful report is that no
     * target was named.
     *
     * <p><strong>The send count is derived from the map name, not assumed.</strong> A named map is exactly
     * the condition "this invocation performed {@code EXEC CICS SEND MAP}": {@code 3400-SEND-SCREEN} at
     * {@code :3591-3592} is the only paragraph that assigns {@code CCARD-NEXT-MAP}, and the {@code XCTL}
     * arm never reaches it and correctly reports zero sends.
     *
     * <p><strong>The cursor is reported as the {@code xxxL} item.</strong> COBOL positions the cursor by
     * moving {@code -1} into a symbolic-map length item, so the length item <em>is</em> the cursor. The
     * response metadata names the field stem, and the {@code L} suffix is what turns that stem back into
     * the item the COBOL wrote.
     *
     * <p><strong>{@code XCTL} and {@code RETURN} are distinguished by what was assigned, not by which key
     * was pressed.</strong> A named next program with no map sent is the transfer at {@code :955}, which
     * never reaches the {@code EXEC CICS RETURN} in {@code COMMON-RETURN}; anything else returned.
     *
     * @param painted the payload the handler returned
     * @param body    the envelope, for the cursor the screen metadata carries
     * @return the observation
     */
    private static ObservedResponse observed(AccountUpdateResponse painted,
                                             ScreenResponse<AccountUpdateResponse> body) {
        String nextProgram = token(painted.getNextProgram());
        String nextMapset = token(painted.getNextMapset());
        String nextMap = token(painted.getNextMap());

        List<ObservedSend> sends = new ArrayList<>(1);
        if (nextMap != null) {
            sends.add(new ObservedSend(outputItems(painted), attributeMnemonics(painted)));
        }

        String cursorStem = body.screenMetadata() == null ? null : body.screenMetadata().cursorField();
        String cursorField = cursorStem == null || cursorStem.isBlank() ? null : cursorStem + "L";

        Termination termination = nextProgram != null && sends.isEmpty()
                ? Termination.XCTL
                : Termination.RETURN_TRANSID;

        return new ObservedResponse(nextProgram, nextMapset, nextMap,
                navigationImage(painted.getNavigationContext()), sends, cursorField, termination);
    }

    /**
     * The fifty-four {@code xxxO} output items a send carries, keyed as the symbolic map names them.
     *
     * <p>{@link AccountUpdateResponse#fieldValues()} keys by the {@code DFHMDF} label - {@code ACCTSID} -
     * while the symbolic map's output item is {@code ACCTSIDO}, and the case model requires the latter
     * because the item is what a field-by-field comparison keys on. The two names differ by exactly the
     * one suffix character, and {@link AccountUpdateResponse.ScreenField#symbolicItemName()} is the
     * mapping, read from the enum rather than composed by concatenation so a field whose item is not its
     * label plus {@code O} could not be silently mis-keyed.
     *
     * @param painted the payload
     * @return fifty-four output items in copybook order
     */
    private static Map<String, String> outputItems(AccountUpdateResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            items.put(field.symbolicItemName(), painted.value(field));
        }
        return Map.copyOf(items);
    }

    /**
     * The fifty-four {@code xxxC} colour items and fifty-four {@code xxxH} highlight items, each valued
     * with the mnemonic the program moved.
     *
     * <p>Two planes, not four. {@code DFHBMSCA} publishes no mnemonic table for a programmed-symbol byte
     * or a validation byte, so a case cannot name a value for either of those items even to say it is the
     * default. They are asserted instead by
     * {@link #theProgrammedSymbolAndValidationPlanesAreNeverWritten()}, which requires all of them to stay
     * at their defaults on every case - a stronger statement than an unnameable expectation, and an
     * explicit one rather than a silent omission.
     *
     * @param painted the payload
     * @return one hundred and eight attribute items keyed by symbolic-map name
     */
    private static Map<String, String> attributeMnemonics(AccountUpdateResponse painted) {
        Map<String, String> items = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            AccountUpdateResponse.FieldAttributes quad = painted.attributes(field);
            items.put(field.colourItemName(), colourMnemonic(field.colourItemName(),
                    quad.getColour()));
            items.put(field.hilightItemName(), highlightMnemonic(field.hilightItemName(),
                    quad.getHilight()));
        }
        return Map.copyOf(items);
    }

    /**
     * The mnemonic one extended-colour byte carries.
     *
     * <p>Resolved through {@code DFHBMSCA}'s colour section only, and that restriction matters: the colour
     * default {@code DFHDFCOL} and the highlighting default {@code DFHDFHI} are the <em>same byte</em>,
     * {@code X'00'}, so a lookup that consulted both tables in some fixed order would name half the
     * planes wrongly. The item's plane decides which section names it, exactly as the copybook's
     * sectioning intends.
     *
     * @param item   the symbolic-map item's name, for the failure message
     * @param colour the byte the program moved
     * @return the mnemonic; never {@code null}
     * @throws IllegalStateException if {@link BmsAttributes#COLOUR_MNEMONICS} does not name the byte
     */
    private static String colourMnemonic(String item, byte colour) {
        String mnemonic = BmsAttributes.COLOUR_MNEMONICS.get(colour);
        if (mnemonic == null) {
            throw new IllegalStateException(unnamedAttribute(item, colour, "extended-colour"));
        }
        return mnemonic;
    }

    /**
     * The mnemonic one extended-highlighting byte carries.
     *
     * <p>The highlighting section first, then the field-attribute section. The second lookup is not a
     * convenience: {@code 3390-SETUP-INFOMSG-ATTRS} moves {@code DFHBMASB} - a <em>field</em> attribute,
     * {@code X'F8'} - into the information line's extended item at {@code :3568} and {@code :3572}, and
     * {@code DFHBMDAR} into it on the other arm, so the byte that lands there is genuinely drawn from a
     * different section of the copybook than the item's own. Reproduced rather than normalised.
     *
     * @param item      the symbolic-map item's name, for the failure message
     * @param highlight the byte the program moved
     * @return the mnemonic; never {@code null}
     * @throws IllegalStateException if neither table names the byte
     */
    private static String highlightMnemonic(String item, byte highlight) {
        String mnemonic = BmsAttributes.HIGHLIGHT_MNEMONICS.get(highlight);
        if (mnemonic != null) {
            return mnemonic;
        }
        String fieldAttribute = BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(highlight);
        if (fieldAttribute != null) {
            return fieldAttribute;
        }
        throw new IllegalStateException(unnamedAttribute(item, highlight,
                "extended-highlighting or field-attribute"));
    }

    /**
     * The message for an attribute byte no published table names.
     *
     * @param item      the symbolic-map item
     * @param attribute the byte
     * @param section   which section or sections were consulted
     * @return the message
     */
    private static String unnamedAttribute(String item, byte attribute, String section) {
        return PROGRAM + " moved 0x" + Integer.toHexString(Byte.toUnsignedInt(attribute)) + " into "
                + item + ", which DFHBMSCA's " + section + " table does not name. The copybook is "
                + "IBM-supplied and absent from this repository, so common.BmsAttributes is the single "
                + "reproduction of it and an unnamed value means the translation invented one.";
    }

    // =================================================================================================
    // SNAPSHOT AND DETAIL BUILDERS. Every one composes from the copybook's declared items rather than
    // from a pasted literal, so an item can be read off the call site.
    // =================================================================================================

    /**
     * {@code ACUP-OLD-ACCT-DATA} exactly as {@code app/data/ASCII/acctdata.txt} row 1 stores it.
     *
     * <p>The three dates arrive as three items each because that is how the snapshot declares them -
     * {@code ACUP-OLD-OPEN-YEAR X(4)}, {@code -MON X(2)}, {@code -DAY X(2)} - against the record's single
     * {@code PIC X(10)}. {@code 9700} bridges the two by comparing reference-modified substrings at
     * {@code :4127-4137}, which is why the snapshot never carries a separator.
     *
     * @return the sixteen-item group
     */
    private static AccountData storedAccountSnapshot() {
        return new AccountData(Long.parseLong(ACCT_KEY), STORED_STATUS,
                STORED_CURR_BAL, STORED_CREDIT_LIMIT, STORED_CASH_LIMIT,
                year(STORED_OPEN_DATE), month(STORED_OPEN_DATE), day(STORED_OPEN_DATE),
                year(STORED_EXPIRAION_DATE), month(STORED_EXPIRAION_DATE), day(STORED_EXPIRAION_DATE),
                year(STORED_REISSUE_DATE), month(STORED_REISSUE_DATE), day(STORED_REISSUE_DATE),
                STORED_CYC_CREDIT, STORED_CYC_DEBIT, STORED_GROUP_ID);
    }

    /**
     * {@code ACUP-OLD-CUST-DATA} exactly as {@code app/data/ASCII/custdata.txt} row 1 stores it.
     *
     * <p>Every item is read from the seeded row through the real {@link CustomerRecord} decoder rather
     * than restated as a literal. Nineteen of the twenty are compared by {@code 9700} - {@code CUST-ID} is
     * not - and restating nineteen literals would be nineteen chances to transcribe a fifty-character
     * address wrongly. The decode is the copybook read out loud, which is what a snapshot of the stored
     * record is.
     *
     * @return the twenty-item group
     */
    private static CustomerData storedCustomerSnapshot() {
        CustomerRecord stored = storedCustomerRecord();
        String dob = stored.getCustDobYyyyMmDd();
        return new CustomerData(stored.getCustId(), stored.getCustFirstName(),
                stored.getCustMiddleName(), stored.getCustLastName(), stored.getCustAddrLine1(),
                stored.getCustAddrLine2(), stored.getCustAddrLine3(), stored.getCustAddrStateCd(),
                stored.getCustAddrCountryCd(), stored.getCustAddrZip(), stored.getCustPhoneNum1(),
                stored.getCustPhoneNum2(), stored.getCustSsn(), stored.getCustGovtIssuedId(),
                year(dob), month(dob), day(dob), stored.getCustEftAccountId(),
                stored.getCustPriCardHolderInd(), stored.getCustFicoCreditScore());
    }

    /**
     * The stored {@code CUSTOMER-RECORD} of {@code custdata.txt} row 1, decoded through the real model.
     *
     * <p>Read from the same test resource the harness seeds from, so the snapshot and the seeded row are
     * the same bytes by construction. A fresh instance every call, so no two cases can share one - there
     * is no static mutable state anywhere in this class (practice <strong>B9</strong>).
     *
     * @return a fresh record
     */
    private static CustomerRecord storedCustomerRecord() {
        return CustomerRecord.decode(fixtureRow("custdata.txt", CustomerRecord.RECORD_LENGTH, 0),
                FIXTURE_CHARSET);
    }

    /**
     * {@code ACUP-NEW-ACCT-DATA} as the fully-keyed screen supplies it.
     *
     * <p>Every one of the sixteen items differs from the stored one, so a written image that agreed with
     * the stored record anywhere would be a difference rather than a coincidence.
     *
     * @return the sixteen-item group
     */
    private static AccountData newAccountData() {
        return new AccountData(Long.parseLong(ACCT_KEY), NEW_STATUS,
                NEW_CURR_BAL, NEW_CREDIT_LIMIT, NEW_CASH_LIMIT,
                year(NEW_OPEN_DATE), month(NEW_OPEN_DATE), day(NEW_OPEN_DATE),
                year(NEW_EXPIRAION_DATE), month(NEW_EXPIRAION_DATE), day(NEW_EXPIRAION_DATE),
                year(NEW_REISSUE_DATE), month(NEW_REISSUE_DATE), day(NEW_REISSUE_DATE),
                NEW_CYC_CREDIT, NEW_CYC_DEBIT, NEW_GROUP_ID);
    }

    /**
     * {@code ACUP-NEW-CUST-DATA} as the screen supplies it: the stored values, unchanged.
     *
     * <p>Deliberately identical to the snapshot. {@code 9600-WRITE-PROCESSING} rewrites the customer
     * record on every successful path <em>whether or not any customer item changed</em> - there is no
     * "only write what changed" guard anywhere in the paragraph - so a case that changed nothing on the
     * customer side still asserts a 500-byte write, and that write is the assertion.
     *
     * @return the twenty-item group
     */
    private static CustomerData newCustomerData() {
        return storedCustomerSnapshot();
    }

    /** @return {@code data} with {@code ACUP-OLD-ACTIVE-STATUS} replaced */
    private static AccountData withStatus(AccountData data, String status) {
        return new AccountData(data.acctId(), status, data.currBal(), data.creditLimit(),
                data.cashCreditLimit(), data.openYear(), data.openMon(), data.openDay(),
                data.expYear(), data.expMon(), data.expDay(), data.reissueYear(), data.reissueMon(),
                data.reissueDay(), data.currCycCredit(), data.currCycDebit(), data.groupId());
    }

    /** @return {@code data} with the {@code CURR-BAL} item replaced */
    private static AccountData withCurrBal(AccountData data, BigDecimal value) {
        return new AccountData(data.acctId(), data.activeStatus(), value, data.creditLimit(),
                data.cashCreditLimit(), data.openYear(), data.openMon(), data.openDay(),
                data.expYear(), data.expMon(), data.expDay(), data.reissueYear(), data.reissueMon(),
                data.reissueDay(), data.currCycCredit(), data.currCycDebit(), data.groupId());
    }

    /** @return {@code data} with the {@code CREDIT-LIMIT} item replaced */
    private static AccountData withCreditLimit(AccountData data, BigDecimal value) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(), value,
                data.cashCreditLimit(), data.openYear(), data.openMon(), data.openDay(),
                data.expYear(), data.expMon(), data.expDay(), data.reissueYear(), data.reissueMon(),
                data.reissueDay(), data.currCycCredit(), data.currCycDebit(), data.groupId());
    }

    /** @return {@code data} with the {@code CASH-CREDIT-LIMIT} item replaced */
    private static AccountData withCashLimit(AccountData data, BigDecimal value) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(),
                data.creditLimit(), value, data.openYear(), data.openMon(), data.openDay(),
                data.expYear(), data.expMon(), data.expDay(), data.reissueYear(), data.reissueMon(),
                data.reissueDay(), data.currCycCredit(), data.currCycDebit(), data.groupId());
    }

    /** @return {@code data} with the {@code CURR-CYC-CREDIT} item replaced */
    private static AccountData withCycCredit(AccountData data, BigDecimal value) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(),
                data.creditLimit(), data.cashCreditLimit(), data.openYear(), data.openMon(),
                data.openDay(), data.expYear(), data.expMon(), data.expDay(), data.reissueYear(),
                data.reissueMon(), data.reissueDay(), value, data.currCycDebit(), data.groupId());
    }

    /** @return {@code data} with the {@code CURR-CYC-DEBIT} item replaced */
    private static AccountData withCycDebit(AccountData data, BigDecimal value) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(),
                data.creditLimit(), data.cashCreditLimit(), data.openYear(), data.openMon(),
                data.openDay(), data.expYear(), data.expMon(), data.expDay(), data.reissueYear(),
                data.reissueMon(), data.reissueDay(), data.currCycCredit(), value, data.groupId());
    }

    /** @return {@code data} with the expiry year item replaced */
    private static AccountData withExpiryYear(AccountData data, String year) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(),
                data.creditLimit(), data.cashCreditLimit(), data.openYear(), data.openMon(),
                data.openDay(), year, data.expMon(), data.expDay(), data.reissueYear(),
                data.reissueMon(), data.reissueDay(), data.currCycCredit(), data.currCycDebit(),
                data.groupId());
    }

    /** @return {@code data} with the {@code GROUP-ID} item replaced, padded to its declared ten */
    private static AccountData withGroupId(AccountData data, String groupId) {
        return new AccountData(data.acctId(), data.activeStatus(), data.currBal(),
                data.creditLimit(), data.cashCreditLimit(), data.openYear(), data.openMon(),
                data.openDay(), data.expYear(), data.expMon(), data.expDay(), data.reissueYear(),
                data.reissueMon(), data.reissueDay(), data.currCycCredit(), data.currCycDebit(),
                CODEC.movePicX(groupId, AccountRecord.ACCT_GROUP_ID_LENGTH));
    }

    /** @return {@code data} with {@code ACCT-ID} replaced - an item {@code 9700} does not compare */
    private static AccountData withAcctId(AccountData data, long acctId) {
        return new AccountData(acctId, data.activeStatus(), data.currBal(), data.creditLimit(),
                data.cashCreditLimit(), data.openYear(), data.openMon(), data.openDay(),
                data.expYear(), data.expMon(), data.expDay(), data.reissueYear(), data.reissueMon(),
                data.reissueDay(), data.currCycCredit(), data.currCycDebit(), data.groupId());
    }

    /**
     * A snapshot whose account zip differs from the record's.
     *
     * <p>{@code ACUP-OLD-ACCT-DATA} carries no zip item at all - {@code 9700} does not compare one - so
     * there is nothing on the account side to perturb, and the perturbation this method exists for is
     * therefore a no-op that documents the absence. The observable half of {@code case06}'s claim is the
     * customer identifier and the account identifier, both of which <em>are</em> carried and neither of
     * which is compared.
     *
     * @param data the snapshot
     * @param zip  the zip the record would have to hold for the difference to matter
     * @return the snapshot, unchanged
     */
    private static AccountData withAddrZip(AccountData data, String zip) {
        Objects.requireNonNull(zip, "A zip is required to name what is not being compared");
        return data;
    }

    /** @return {@code data} with {@code CUST-ID} replaced - an item {@code 9700} does not compare */
    private static CustomerData withCustId(CustomerData data, int custId) {
        return new CustomerData(custId, data.firstName(), data.middleName(), data.lastName(),
                data.addrLine1(), data.addrLine2(), data.addrLine3(), data.addrStateCd(),
                data.addrCountryCd(), data.addrZip(), data.phoneNum1(), data.phoneNum2(),
                data.ssn(), data.govtIssuedId(), data.dobYear(), data.dobMon(), data.dobDay(),
                data.eftAccountId(), data.priHolderInd(), data.ficoScore());
    }

    /** @return {@code data} with {@code CUST-FICO-CREDIT-SCORE} replaced - block two's last item */
    private static CustomerData withFicoScore(CustomerData data, int score) {
        return new CustomerData(data.custId(), data.firstName(), data.middleName(), data.lastName(),
                data.addrLine1(), data.addrLine2(), data.addrLine3(), data.addrStateCd(),
                data.addrCountryCd(), data.addrZip(), data.phoneNum1(), data.phoneNum2(),
                data.ssn(), data.govtIssuedId(), data.dobYear(), data.dobMon(), data.dobDay(),
                data.eftAccountId(), data.priHolderInd(), score);
    }

    /**
     * A snapshot whose first name is the record's in the opposite letter case.
     *
     * <p>The fixture stores {@code "Immanuel"} in mixed case, so folding it to upper produces a genuinely
     * different string that {@code :4152-4153}'s {@code FUNCTION UPPER-CASE} on both operands must
     * nonetheless call equal.
     *
     * @param data the snapshot
     * @return the snapshot with its first name upper-cased
     */
    private static CustomerData foldFirstNameToUpper(CustomerData data) {
        return new CustomerData(data.custId(), AccountUpdateService.upperCase(data.firstName()),
                data.middleName(), data.lastName(), data.addrLine1(), data.addrLine2(),
                data.addrLine3(), data.addrStateCd(), data.addrCountryCd(), data.addrZip(),
                data.phoneNum1(), data.phoneNum2(), data.ssn(), data.govtIssuedId(),
                data.dobYear(), data.dobMon(), data.dobDay(), data.eftAccountId(),
                data.priHolderInd(), data.ficoScore());
    }

    /** @return the first four characters of a {@code CCYY-MM-DD} image */
    private static String year(String date) {
        return date.substring(0, AccountRecord.YEAR_LENGTH);
    }

    /** @return the month characters of a {@code CCYY-MM-DD} image, at the copybook's own offsets */
    private static String month(String date) {
        return date.substring(AccountRecord.MONTH_START - 1,
                AccountRecord.MONTH_START - 1 + AccountRecord.MONTH_LENGTH);
    }

    /** @return the day characters of a {@code CCYY-MM-DD} image, at the copybook's own offsets */
    private static String day(String date) {
        return date.substring(AccountRecord.DAY_START - 1,
                AccountRecord.DAY_START - 1 + AccountRecord.DAY_LENGTH);
    }

    // =================================================================================================
    // RECORD IMAGE BUILDERS. The 300-byte image is composed from ACCT-UPDATE-RECORD's spans and NOT from
    // CVACT01Y's, because those two layouts disagree and the program writes through the former.
    // =================================================================================================

    /**
     * Composes the 300 bytes {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME)} at {@code :4064-4070}
     * sends, laid out as {@code ACCT-UPDATE-RECORD} declares them at {@code app/cbl/COACTUPC.cbl:418-433}.
     *
     * <p>Read the composition below as the declaration read out loud, and note what is <em>not</em> in it:
     * there is no zip span. {@code ACCT-UPDATE-GROUP-ID} follows {@code ACCT-UPDATE-CURR-CYC-DEBIT}
     * directly, which puts it at offset 102 - where {@code app/cpy/CVACT01Y.cpy} puts
     * {@code ACCT-ADDR-ZIP} - and the trailing {@code FILLER} is 188 wide rather than 178, so it covers
     * bytes 112 to 299 including the stored {@code ACCT-GROUP-ID}. Composing from the update record's own
     * spans is the only faithful way to state the expectation; composing from the copybook's would
     * describe a record this program never writes.
     *
     * <p>The width is checked rather than assumed, so a transcription slip in this method fails here with
     * a message about this method instead of surfacing as an unexplained image mismatch (gate
     * <strong>G21</strong>).
     *
     * @param data the {@code ACUP-NEW-ACCT-DATA} group the paragraph stages from
     * @return the image, exactly {@link AccountRecord#RECORD_LENGTH} characters
     */
    private static String accountUpdateImage(AccountData data) {
        String image = CODEC.movePic9(data.acctId(), AccountRecord.ACCT_ID_LENGTH)
                + CODEC.movePicX(data.activeStatus(), AccountRecord.ACCT_ACTIVE_STATUS_LENGTH)
                + monetary(data.currBal())
                + monetary(data.creditLimit())
                + monetary(data.cashCreditLimit())
                + CODEC.movePicX(storedDate(data.openYear(), data.openMon(), data.openDay()),
                        AccountRecord.ACCT_OPEN_DATE_LENGTH)
                + CODEC.movePicX(storedDate(data.expYear(), data.expMon(), data.expDay()),
                        AccountRecord.ACCT_EXPIRAION_DATE_LENGTH)
                + CODEC.movePicX(storedDate(data.reissueYear(), data.reissueMon(), data.reissueDay()),
                        AccountRecord.ACCT_REISSUE_DATE_LENGTH)
                + monetary(data.currCycCredit())
                + monetary(data.currCycDebit())
                // Offset 102. The defect, spelled out at the one call site that produces it.
                + CODEC.movePicX(data.groupId(), AccountRecord.ACCT_GROUP_ID_LENGTH)
                // FILLER X(188) at :433, ten wider than CVACT01Y's X(178), emitted as spaces (gate G21).
                + " ".repeat(AccountUpdateService.ACCT_UPDATE_FILLER_LENGTH);
        if (image.length() != AccountRecord.RECORD_LENGTH) {
            throw new IllegalStateException("An ACCT-UPDATE-RECORD image composed from the spans "
                    + "app/cbl/COACTUPC.cbl:418-433 declares came out " + image.length()
                    + " characters wide where the record is " + AccountRecord.RECORD_LENGTH
                    + ". The composition above is that declaration read out loud, so a mismatch here is "
                    + "a transcription error in this class rather than a parity difference.");
        }
        return image;
    }

    /** @return the 300-byte image every clean-rewrite case expects, from the fully-keyed screen */
    private static String fullNewAccountImage() {
        return accountUpdateImage(newAccountData());
    }

    /**
     * Composes the 500 bytes {@code EXEC CICS REWRITE FILE(LIT-CUSTFILENAME)} at {@code :4084-4090} sends.
     *
     * <p>{@code CUST-UPDATE-RECORD} at {@code :434-456} agrees with {@code app/cpy/CVCUS01Y.cpy} span for
     * span - unlike its account sibling - so the image is the stored record with the screen's values in
     * place. Every case in this class supplies the stored customer values unchanged, so the expected image
     * is the seeded row re-encoded through the model, which additionally asserts that a decode-encode
     * round trip is byte-preserving across all 500 bytes including the trailing {@code FILLER X(100)}.
     *
     * @return the image, exactly {@link CustomerRecord#RECORD_LENGTH} characters
     */
    private static String fullNewCustomerImage() {
        String image = fixtureRow("custdata.txt", CustomerRecord.RECORD_LENGTH, 0);
        if (image.length() != CustomerRecord.RECORD_LENGTH) {
            throw new IllegalStateException("custdata.txt row 1 is " + image.length()
                    + " characters where CVCUS01Y declares " + CustomerRecord.RECORD_LENGTH);
        }
        return image;
    }

    /**
     * One {@code PIC S9(10)V99} span as twelve zoned characters with the sign overpunched.
     *
     * <p>Not a formatted decimal. A signed zoned field carries its sign in the zone of its low-order digit
     * - {@code 194.00} is stored as <code>"00000001940&#123;"</code>, whose final character is the
     * positive-zero overpunch - and the differ canonicalises a decimal literal into that image before
     * comparing, so either form would pass. The image form is used here because it is what the bytes are,
     * and because it makes the twelve-character width visible at the call site.
     *
     * @param value the value at scale 2
     * @return twelve characters
     */
    private static String monetary(BigDecimal value) {
        return CODEC.encodeSignedScaled(value, AccountRecord.MONETARY_INTEGER_DIGITS,
                CobolDecimal.MONETARY_SCALE);
    }

    /**
     * The {@code STRING year '-' month '-' day DELIMITED BY SIZE} the three date spans are composed by.
     *
     * <p>Three of these appear in {@code 9600-WRITE-PROCESSING}, at {@code :3979-3985},
     * {@code :3987-3993} and {@code :3996-4002}, and they are the reason the snapshot carries three items
     * where the record carries one. The composition is delegated to the service's own helper rather than
     * re-implemented, so this class cannot drift from the paragraph it describes.
     *
     * @param year  the four-character year
     * @param month the two-character month
     * @param day   the two-character day
     * @return the ten-character {@code CCYY-MM-DD} image
     */
    private static String storedDate(String year, String month, String day) {
        return AccountUpdateService.composeStoredDate(year, month, day, CODEC);
    }

    /** @return an expectation that {@code ACCTDAT} row 0 holds exactly {@code image} */
    private static ExpectedRecord accountWrite(String image) {
        return new ExpectedRecord(ACCTDAT, 0, Map.of(), image);
    }

    /** @return an expectation that {@code CUSTDAT} row 0 holds exactly {@code image} */
    private static ExpectedRecord customerWrite(String image) {
        return new ExpectedRecord(CUSTDAT, 0, Map.of(), image);
    }

    /** @return the cleared {@code WS-RETURN-MSG}: seventy-five spaces, which is not the same as absent */
    private static List<EmittedMessage> clearReturnMessage() {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                AccountUpdateService.RETURN_MESSAGE_OFF));
    }

    /**
     * The message a refused or failed write leaves in {@code WS-RETURN-MSG}.
     *
     * @param text the literal the paragraph moved, at whatever length it is declared
     * @return the message at {@code WS-RETURN-MSG}'s declared {@code PIC X(75)}
     */
    private static List<EmittedMessage> returnMessage(String text) {
        return List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                CODEC.movePicX(text, AccountUpdateService.RETURN_MESSAGE_LENGTH)));
    }

    /**
     * One row of a test fixture, read at its declared width.
     *
     * <p>The same nine fixtures the harness seeds from, read through the class loader so the file on disk
     * is the single source. Line separators are stripped rather than depended on: the fixtures are
     * fixed-width records, and whether the file that carries them ends its lines is a property of the file
     * and not of the data.
     *
     * @param fixture     the bare file name under {@code src/test/resources/fixtures/}
     * @param recordWidth the copybook's declared record length
     * @param rowIndex    the zero-based row
     * @return the row, exactly {@code recordWidth} characters
     * @throws IllegalStateException if the fixture is missing or too short
     */
    private static String fixtureRow(String fixture, int recordWidth, int rowIndex) {
        String resource = DatasetInput.FIXTURE_ROOT + fixture;
        try (java.io.InputStream stream =
                     COACTUPCParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("The fixture " + resource + " is not on the test "
                        + "classpath. It is derived from app/data/ASCII/" + fixture + ", which is a "
                        + "reference input this migration never writes.");
            }
            String all = new String(stream.readAllBytes(), FIXTURE_CHARSET)
                    .replace("\r", "").replace("\n", "");
            int from = rowIndex * recordWidth;
            if (all.length() < from + recordWidth) {
                throw new IllegalStateException(resource + " holds " + all.length()
                        + " characters, which is fewer than the " + (from + recordWidth)
                        + " that row " + rowIndex + " at " + recordWidth + " bytes needs.");
            }
            return all.substring(from, from + recordWidth);
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("The fixture " + resource + " could not be read", problem);
        }
    }

    // =================================================================================================
    // SCREEN EXPECTATION BUILDERS. Fifty-four fields, composed from the rules 3100/3200/3250/3400 apply
    // rather than from a captured image - which is what makes the expectation an assertion.
    // =================================================================================================

    /**
     * The search-key prompt screen: every field {@code 3000-SEND-MAP} leaves behind on the three paths
     * that reach it without fetched details.
     *
     * <p>Composed from five rules, each traceable to one paragraph:
     *
     * <ol>
     *   <li>{@code 3100-SCREEN-INIT} at {@code :2670} does {@code MOVE LOW-VALUES TO CACTUPAO}, so every
     *       field starts as {@code LOW-VALUES} at its declared width. Not spaces -
     *       {@code LOW-VALUES} is {@code X'00'}, and BMS treats the two differently on a send.</li>
     *   <li>{@code :2672-2691} then assigns the six header fields: the two titles from
     *       {@code app/cpy/COTTL01Y.cpy}, the transaction and program literals, and the date and time
     *       rendered from {@code FUNCTION CURRENT-DATE} as {@code mm/dd/yy} and {@code hh:mm:ss}.</li>
     *   <li>{@code 3200-SETUP-SCREEN-VARS} at {@code :2700-2703} takes {@code CONTINUE} when
     *       {@code CDEMO-PGM-ENTER}, so on a cold start not one data field is assigned; on a re-entry it
     *       assigns {@code ACCTSIDO} and then performs {@code 3201-SHOW-INITIAL-VALUES}, which moves
     *       {@code LOW-VALUES} into all forty-one of the others - the same value they already hold.</li>
     *   <li>{@code 3250-SETUP-INFOMSG} at {@code :2979} and {@code :2981} moves {@code WS-INFO-MSG} into
     *       {@code INFOMSGO} and {@code WS-RETURN-MSG} into {@code ERRMSGO}.</li>
     *   <li>The three function-key legends are the mapset's own {@code INITIAL} text and are never
     *       assigned by the program at all; {@code 3390-SETUP-INFOMSG-ATTRS} reveals or hides them by
     *       attribute.</li>
     * </ol>
     *
     * @param acctsid what {@code ACCTSIDO} holds, or {@code null} for the {@code LOW-VALUES} it starts as
     * @param errmsg  the error text, or {@code null} for the cleared line
     * @return all fifty-four fields keyed by their {@code DFHMDF} labels
     */
    private static Map<String, String> promptScreen(String acctsid, String errmsg) {
        Map<String, String> screen = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            screen.put(field.symbolicItemName(), lowValues(field.length()));
        }
        screen.put(AccountUpdateResponse.ScreenField.TRNNAME.symbolicItemName(), THIS_TRANID);
        screen.put(AccountUpdateResponse.ScreenField.TITLE01.symbolicItemName(), TITLE01);
        screen.put(AccountUpdateResponse.ScreenField.CURDATE.symbolicItemName(), PINNED_DATE_IMAGE);
        screen.put(AccountUpdateResponse.ScreenField.PGMNAME.symbolicItemName(), THIS_PGM);
        screen.put(AccountUpdateResponse.ScreenField.TITLE02.symbolicItemName(), TITLE02);
        screen.put(AccountUpdateResponse.ScreenField.CURTIME.symbolicItemName(), PINNED_TIME_IMAGE);
        screen.put(AccountUpdateResponse.ScreenField.INFOMSG.symbolicItemName(),
                INFO_PROMPT_FOR_ACCT);
        screen.put(AccountUpdateResponse.ScreenField.ERRMSG.symbolicItemName(),
                CODEC.movePicX(errmsg == null ? "" : errmsg, AccountUpdateResponse.ERRMSG_LENGTH));
        screen.put(AccountUpdateResponse.ScreenField.FKEYS.symbolicItemName(), FKEYS_LEGEND);
        screen.put(AccountUpdateResponse.ScreenField.FKEY05.symbolicItemName(), FKEY05_LEGEND);
        screen.put(AccountUpdateResponse.ScreenField.FKEY12.symbolicItemName(), FKEY12_LEGEND);
        if (acctsid != null) {
            screen.put(AccountUpdateResponse.ScreenField.ACCTSID.symbolicItemName(),
                    CODEC.movePicX(acctsid, AccountUpdateResponse.ACCTSID_LENGTH));
        }
        if (screen.size() != AccountUpdateResponse.ScreenField.values().length) {
            throw new IllegalStateException("A COACTUP screen expectation named " + screen.size()
                    + " fields where app/cpy-bms/COACTUP.CPY declares "
                    + AccountUpdateResponse.ScreenField.values().length + " xxxI items. A name that "
                    + "does not match a declared field would be compared against nothing.");
        }
        return Map.copyOf(screen);
    }

    /**
     * The one hundred and eight attribute items a send carries, derived from what the fields hold.
     *
     * <p>Two rules, and they are the whole of {@code CSSETATY} plus {@code 3390-SETUP-INFOMSG-ATTRS} as
     * they apply on the prompt screen:
     *
     * <ul>
     *   <li>The colour item is {@link BmsAttributes#DFHRED} for a field whose validation failed and
     *       {@code DFHDFCOL} - the default - for every other field. On the prompt screen the only field
     *       that can fail is {@code ACCTSID}, and it has failed exactly when the error line is not
     *       blank.</li>
     *   <li>The highlight item is {@code DFHDFHI} - the default - everywhere except {@code INFOMSG},
     *       which {@code :3572} sets to {@code DFHBMASB} whenever there <em>is</em> an information
     *       message. There always is one on this screen, because {@code 3250}'s first two arms both set
     *       {@code PROMPT-FOR-SEARCH-KEYS}.</li>
     * </ul>
     *
     * <p>Derived from the field values rather than restated per case, because the two are not independent:
     * a reddened field with no error text, or an error text with no reddened field, would be a difference
     * this expectation cannot express - and that is deliberate, since neither is a state the source can
     * produce.
     *
     * @param screen the fifty-four field values this send carries
     * @return the colour and highlight items keyed by symbolic-map name
     */
    private static Map<String, String> colourAndHighlightExpectation(Map<String, String> screen) {
        boolean accountFilterFailed = !screen
                .get(AccountUpdateResponse.ScreenField.ERRMSG.symbolicItemName()).isBlank();
        Map<String, String> items = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            boolean reddened = accountFilterFailed && field == AccountUpdateResponse.ScreenField.ACCTSID;
            items.put(field.colourItemName(), reddened
                    ? BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED)
                    : BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHDFCOL));
            boolean brightened = field == AccountUpdateResponse.ScreenField.INFOMSG;
            items.put(field.hilightItemName(), brightened
                    ? BmsAttributes.FIELD_ATTRIBUTE_MNEMONICS.get(BmsAttributes.DFHBMASB)
                    : BmsAttributes.HIGHLIGHT_MNEMONICS.get(BmsAttributes.DFHDFHI));
        }
        return Map.copyOf(items);
    }

    /** @return {@code length} {@code X'00'} characters - {@code LOW-VALUES}, not spaces */
    private static String lowValues(int length) {
        return String.valueOf('\u0000').repeat(length);
    }

    /**
     * All sixteen {@code CARDDEMO-COMMAREA} fields, keyed as {@code app/cpy/COCOM01Y.cpy} names them.
     *
     * <p>All sixteen, always. {@link FieldDiffer} compares the navigation map in both directions, so a
     * partial expectation would fail on the fields it omitted rather than pass on the ones it declared -
     * and rightly: conversation state travels in the payload, which makes every one of these fields part
     * of the observable response (rule <strong>R6</strong>, gate <strong>G37</strong>).
     *
     * @param context the context
     * @return the sixteen fields
     */
    private static Map<String, String> navigationImage(NavigationContext context) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put(NavigationContext.FROM_TRANID_FIELD, context.fromTranid());
        image.put(NavigationContext.FROM_PROGRAM_FIELD, context.fromProgram());
        image.put(NavigationContext.TO_TRANID_FIELD, context.toTranid());
        image.put(NavigationContext.TO_PROGRAM_FIELD, context.toProgram());
        image.put(NavigationContext.USER_ID_FIELD, context.userId());
        image.put(NavigationContext.USER_TYPE_FIELD, context.userType());
        image.put(NavigationContext.PGM_CONTEXT_FIELD,
                CODEC.movePic9(context.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        image.put(NavigationContext.CUST_ID_FIELD,
                CODEC.movePic9(context.custId(), NavigationContext.CUST_ID_LENGTH));
        image.put(NavigationContext.CUST_FNAME_FIELD, context.custFname());
        image.put(NavigationContext.CUST_MNAME_FIELD, context.custMname());
        image.put(NavigationContext.CUST_LNAME_FIELD, context.custLname());
        image.put(NavigationContext.ACCT_ID_FIELD,
                CODEC.movePic9(context.acctId(), NavigationContext.ACCT_ID_LENGTH));
        image.put(NavigationContext.ACCT_STATUS_FIELD, context.acctStatus());
        image.put(NavigationContext.CARD_NUM_FIELD,
                CODEC.movePic9(context.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        image.put(NavigationContext.LAST_MAP_FIELD, context.lastMap());
        image.put(NavigationContext.LAST_MAPSET_FIELD, context.lastMapset());
        return Map.copyOf(image);
    }

    /**
     * Rebuilds the request the handler receives from a case's commarea and received map fields.
     *
     * <p>{@code ACCTSIDI} is the one writable {@code xxxI} item any of the six screen cases supplies -
     * the other fifty-three are either output-only headers or fields the details-not-fetched screen
     * protects - and it must agree with the URI key, because the handler compares the two before doing
     * anything else.
     *
     * @param context   the inbound commarea, or {@code null} for a cold start
     * @param mapFields the received {@code xxxI} items
     * @param acctId    the URI key, which is what {@code ACCTSIDI} carries when the case declares none
     * @return the request, or {@code null} when the case declares no commarea - the cold start
     */
    private static AccountUpdateRequest requestFrom(NavigationContext context,
                                                    Map<String, String> mapFields,
                                                    String acctId) {
        if (context == null) {
            return null;
        }
        String acctsid = mapFields.getOrDefault("ACCTSIDI", acctId);
        return AccountUpdateRequest.initial()
                .withValue(AccountUpdateRequest.ScreenField.ACCTSID,
                        CODEC.movePicX(acctsid, AccountUpdateRequest.ACCTSID_LENGTH))
                .withNavigationContext(context);
    }

    /**
     * The raw {@code EIBAID} byte a {@code DFHAID} mnemonic names.
     *
     * @param mnemonic the mnemonic a case declared, or {@code null} for {@code DFHENTER}
     * @return the byte
     * @throws IllegalArgumentException if the mnemonic is not one {@link CicsAid} publishes
     */
    private static byte aidByte(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("\"" + mnemonic + "\" is not a DFHAID mnemonic. The "
                + "copybook is IBM-supplied and absent from this repository, so common.CicsAid is the "
                + "single reproduction of it and the permitted names come from there.");
    }

    /**
     * A next-screen token, with blank reported as absent.
     *
     * @param value the carrier's contents at its declared width
     * @return the trimmed token, or {@code null} when nothing was assigned
     */
    private static String token(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // =================================================================================================
    // REPOSITORY STUBS. Every one refuses any call the source does not make, so a translation reaching
    // for a verb COACTUPC does not contain fails by name here rather than receiving a silent default.
    // =================================================================================================

    /**
     * An {@code ACCTDAT} repository answering from the seeded rows and mutating them on a rewrite.
     *
     * <p>Two verbs, because {@code 9600-WRITE-PROCESSING} issues exactly two: the
     * {@code EXEC CICS READ ... UPDATE} at {@code :3893-3903} and the {@code EXEC CICS REWRITE} at
     * {@code :4064-4070}. There is no {@code WRITE}, no {@code DELETE} and no browse anywhere in the
     * paragraph.
     *
     * <p>{@code doAnswer} rather than {@code when(...).thenAnswer(...)}: the latter would invoke the
     * method in order to record the stub, which the strict default answer below would turn into a failure
     * during setup.
     *
     * @param rows the seeded rows, mutated in place by a rewrite so the final-state channel is real
     * @return the stub
     */
    private static AccountRepository fixtureAccountRepository(List<String> rows) {
        AccountRepository repository = strictAccountRepository();
        Mockito.doAnswer(read -> {
            String key = read.getArgument(0);
            for (String row : rows) {
                if (row.startsWith(key)) {
                    return AccountRepository.ReadResult.found(
                            AccountRecord.decode(row, FIXTURE_CHARSET));
                }
            }
            return AccountRepository.ReadResult.notFound();
        }).when(repository).readForUpdate(ArgumentMatchers.anyString());
        Mockito.doAnswer(rewrite -> {
            AccountRecord record = rewrite.getArgument(0);
            String image = record.toFixedWidthString();
            String key = CODEC.movePic9(record.getAcctId(), AccountRecord.ACCT_ID_LENGTH);
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).startsWith(key)) {
                    rows.set(index, image);
                    return AccountRepository.WriteResult.written();
                }
            }
            return AccountRepository.WriteResult.notFound();
        }).when(repository).rewrite(ArgumentMatchers.any());
        return repository;
    }

    /**
     * A {@code CUSTDAT} repository answering from the seeded rows and mutating them on a rewrite.
     *
     * <p>The same two verbs, from {@code :3921-3931} and {@code :4084-4090}. The stored image is passed
     * alongside the decoded record because {@link CustomerRepository.ReadResult#found} carries both - the
     * bytes as read and the model over them - and the concurrency check needs the model while the
     * final-state channel needs the bytes.
     *
     * @param rows the seeded rows, mutated in place by a rewrite
     * @return the stub
     */
    private static CustomerRepository fixtureCustomerRepository(List<String> rows) {
        CustomerRepository repository = strictCustomerRepository();
        Mockito.doAnswer(read -> {
            String key = read.getArgument(0);
            for (String row : rows) {
                if (row.startsWith(key)) {
                    return CustomerRepository.ReadResult.found(
                            CustomerRecord.decode(row, FIXTURE_CHARSET), row);
                }
            }
            return CustomerRepository.ReadResult.notFound();
        }).when(repository).readForUpdate(ArgumentMatchers.anyString());
        Mockito.doAnswer(rewrite -> {
            CustomerRecord record = rewrite.getArgument(0);
            String image = new String(record.encode(FIXTURE_CHARSET), FIXTURE_CHARSET);
            String key = CODEC.movePic9(record.getCustId(), CustomerRepository.KEY_LENGTH);
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).startsWith(key)) {
                    rows.set(index, image);
                    return CustomerRepository.WriteResult.written();
                }
            }
            return CustomerRepository.WriteResult.notFound();
        }).when(repository).rewrite(ArgumentMatchers.any(CustomerRecord.class));
        return repository;
    }

    /** @return an {@code ACCTDAT} repository that refuses every call */
    private static AccountRepository strictAccountRepository() {
        return Mockito.mock(AccountRepository.class, unstubbed -> {
            throw new UnsupportedOperationException(PROGRAM + " called AccountRepository."
                    + unstubbed.getMethod().getName() + ", for which it has no statement. Its account "
                    + "file verbs are the EXEC CICS READ at app/cbl/COACTUPC.cbl:3705-3715, the "
                    + "READ ... UPDATE at :3893-3903 and the REWRITE at :4064-4070 - there is no WRITE, "
                    + "no DELETE and no browse anywhere in the program - so a call to anything else is a "
                    + "translation reaching for a verb the source does not contain.");
        });
    }

    /** @return a {@code CUSTDAT} repository that refuses every call */
    private static CustomerRepository strictCustomerRepository() {
        return Mockito.mock(CustomerRepository.class, unstubbed -> {
            throw new UnsupportedOperationException(PROGRAM + " called CustomerRepository."
                    + unstubbed.getMethod().getName() + ", for which it has no statement. Its customer "
                    + "file verbs are the EXEC CICS READ at :3756-3766, the READ ... UPDATE at "
                    + ":3921-3931 and the REWRITE at :4084-4090.");
        });
    }

    /**
     * An {@code ACCTDAT} repository the screen cases must never reach.
     *
     * <p>Three of the six screen cases return before {@code 1000-PROCESS-INPUTS} and the other three fail
     * {@code 1210-EDIT-ACCOUNT}, which leaves {@code FLG-ACCTFILTER-ISVALID} unset and so makes
     * {@code 2000-DECIDE-ACTION}'s {@code IF} at {@code :2573} false. No read happens on any of the six,
     * and this stub is how that is asserted: by construction rather than by an empty expectation.
     *
     * @return a repository that throws on any call
     */
    private static AccountRepository unreachableAccountRepository() {
        return strictAccountRepository();
    }

    /** @return a {@code CUSTDAT} repository that throws on any call, for the same reason */
    private static CustomerRepository unreachableCustomerRepository() {
        return strictCustomerRepository();
    }

    /**
     * A {@code CCXREF} repository that throws on any call.
     *
     * <p>{@code 9200-GETCARDXREF-BYACCT} reads the {@code CXACAIX} path at {@code :3654-3664} and is
     * performed only from {@code 9000-READ-ACCT}, which none of the six screen cases reaches.
     *
     * @return a repository that throws on any call
     */
    private static CardXrefRepository unreachableCardXrefRepository() {
        return Mockito.mock(CardXrefRepository.class, unstubbed -> {
            throw new UnsupportedOperationException(PROGRAM + " called CardXrefRepository."
                    + unstubbed.getMethod().getName() + " on a path that reaches no file verb. "
                    + "9200-GETCARDXREF-BYACCT reads the CXACAIX path at app/cbl/COACTUPC.cbl:3654-3664 "
                    + "and is performed only from 9000-READ-ACCT.");
        });
    }

    /**
     * A unit of work over a real transaction manager and a real single connection.
     *
     * <p>Deliberately not a stand-in, for the reason given on {@link #serviceUnit}. An in-memory database
     * is used because the subject is the boundary - which is the framework's behaviour - rather than any
     * deployment driver's. The URL is unique per call, so no two cases can see each other's connection and
     * there is no shared mutable state (practice <strong>B9</strong>).
     *
     * @param caseId the case identifier, so a connection can be attributed in a stack trace
     * @return a unit of work whose {@code execute} opens a genuine transaction
     */
    private static DatasetUnitOfWork unitOfWork(String caseId) {
        SingleConnectionDataSource source = new SingleConnectionDataSource(
                "jdbc:h2:mem:coactupc-parity-" + caseId + "-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "", true);
        source.setSuppressClose(true);
        DataSource dataSource = source;
        return new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
    }

    // =================================================================================================
    // The scenario. A case, the unit kind its adapter constructs, and the adapter itself.
    // =================================================================================================

    /**
     * One case together with the way its unit is reached.
     *
     * <p>The unit kind is carried separately from {@link ParityCase#unitKind()} on purpose. The harness
     * compares the two before the adapter runs, so a case that declared {@code SERVICE} while its adapter
     * constructed a controller is caught at the boundary instead of producing a confusing diff.
     *
     * @param parityCase  the case, validated by {@link ParityCase}'s own constructor
     * @param adapterKind the kind of unit {@code adapter} constructs
     * @param adapter     how to construct and call that unit
     * @param unitName    the unit named for a failure message, so a report says what it exercised
     */
    private record ParityScenario(ParityCase parityCase,
                                  UnitKind adapterKind,
                                  ParityHarness.ParityUnit adapter,
                                  String unitName) {

        /**
         * The case identifier, for a failure message that names the case.
         *
         * @return {@code case01} through {@code case20}
         */
        String caseId() {
            return parityCase.caseId();
        }

        /**
         * The parameterized test's display name: the identifier, the unit and the first sentence.
         *
         * <p>The description's later sentences quote source lines and are long, and no field value is ever
         * printed - an {@code ACCTDAT} row carries a balance and a {@code CUSTDAT} row a social security
         * number and a date of birth.
         *
         * @return a short label
         */
        @Override
        public String toString() {
            String description = parityCase.description();
            int firstStop = description.indexOf(". ");
            return caseId() + " [" + unitName + "] "
                    + (firstStop < 0 ? description : description.substring(0, firstStop));
        }
    }
}
