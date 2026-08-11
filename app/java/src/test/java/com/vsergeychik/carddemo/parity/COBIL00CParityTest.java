package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.billing.BillPaymentController;
import com.vsergeychik.carddemo.billing.BillPaymentService;
import com.vsergeychik.carddemo.billing.BillPaymentService.PaymentState;
import com.vsergeychik.carddemo.billing.BillPaymentService.SentScreen;
import com.vsergeychik.carddemo.billing.dto.BillPaymentRequest;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
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
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedSend;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The parity gate for {@code app/cbl/COBIL00C.cbl} - twenty declarative cases, each judged field by
 * field, each required to report a diff count of zero.
 *
 * <h2>Where the expected values come from - read this first</h2>
 * <p>Every expectation below is <strong>statically derived</strong>. It was obtained by reading
 * {@code app/cbl/COBIL00C.cbl} paragraph by paragraph - all 572 lines, thirteen {@code EXEC CICS}
 * commands - and cross-checking against five other authoritative sources: the byte layouts of
 * {@code app/cpy/CVACT01Y.cpy} (300 bytes, ending in {@code FILLER PIC X(178)}),
 * {@code app/cpy/CVTRA05Y.cpy} (350 bytes, ending in {@code FILLER PIC X(20)}) and
 * {@code app/cpy/CVACT03Y.cpy} (50 bytes, ending in {@code FILLER PIC X(14)}); the ten payload items
 * of the symbolic map {@code app/cpy-bms/COBIL00.CPY} and their widths in
 * {@code app/bms/COBIL00.bms}; the communication area of {@code app/cpy/COCOM01Y.cpy}; the
 * transaction, program and file definitions of {@code app/csd/CARDDEMO.CSD}, where {@code CB00}
 * names this program; and the real fixture data in {@code app/data/ASCII/acctdata.txt} and
 * {@code app/data/ASCII/cardxref.txt}.
 *
 * <p><strong>No expected value here was captured from a run of the legacy COBOL.</strong> Running
 * the 28 legacy programs is empirically impossible in this environment: there is no z/OS or CICS
 * runtime, the available COBOL compiler reports its indexed file handler disabled, no Language
 * Environment {@code CEE*} service is present, and {@code DFHAID} and {@code DFHBMSCA} - both copied
 * by this program at {@code :84-85} - are IBM-supplied and absent from this repository, so their
 * constants are reproduced from IBM CICS documentation rather than read from source. The static
 * derivation is the documented substitute for a captured baseline and is a deliberate, escalated
 * deviation from the original wording of the acceptance criterion, not an unremarked convenience.
 * What survives the substitution is everything substantive: twenty cases for this program, comparison
 * field by field rather than as whole strings, and a diff count that must be zero across all twenty
 * before this module is complete. Only the provenance of the expected values changed.
 *
 * <h2>Truncation, not rounding - and why that is the whole point of this program</h2>
 * <p>{@code COBIL00C} owns one monetary computation, at {@code app/cbl/COBIL00C.cbl:234}:
 * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}. It carries <strong>no
 * {@code ROUNDED}</strong> phrase - and neither does any other statement in any of the 28 programs,
 * where the keyword appears exactly zero times - so COBOL <em>truncates</em> excess fractional digits
 * on store. Every store in this gate therefore goes through
 * {@link CobolDecimal}, whose {@link CobolDecimal#COBOL_ROUNDING} is
 * {@code RoundingMode.DOWN} and which is the single place in the system that names a rounding mode.
 * Nothing here rounds to nearest in any direction, and
 * {@link #theTruncationCaseIsSharpEnoughToRejectANearestValueRule()} proves the point positively: it
 * shows that {@code case02}'s discarded remainder is at least half a cent, so a nearest-value rule
 * would have carried and produced a different byte in three separate places.
 *
 * <p>The <em>second</em> numeric rule matters just as much and is easier to miss.
 * {@code app/cbl/COBIL00C.cbl:224} is {@code MOVE ACCT-CURR-BAL TO TRAN-AMT}, and the two pictures
 * are not the same width: {@code ACCT-CURR-BAL} is {@code PIC S9(10)V99} while {@code TRAN-AMT} is
 * {@code PIC S9(09)V99}. A numeric receiver is aligned on its <strong>implied decimal point</strong>,
 * so the digit that does not fit is the <strong>high-order</strong> one - the opposite end from a
 * {@code PIC X} move, which discards on the right. For every balance below one billion the payment
 * equals the balance exactly and the computed result is {@code 0.00}; for a ten-digit balance the
 * payment is a billion short of it and the result is <em>not</em> zero. {@code case03} pins that, and
 * {@link #theBalanceMoveIntoTranAmtDiscardsTheHighOrderDigit()} states the rule on its own.
 *
 * <p>Neither {@code double} nor {@code float} appears in this file, and no value derived from a
 * {@code PIC 9...V...} span is held as anything but {@link BigDecimal} at the scale its picture
 * declares.
 *
 * <h2>Why the cases are declared here rather than loaded from JSON</h2>
 * <p>{@link #cases()} is the single seam between the gate and the case set, and it declares the
 * twenty {@link ParityCase} values in code. They are constructed through {@link ParityCase}'s own
 * canonical constructor, so they are validated exactly as a shipped
 * {@code src/test/resources/parity/COBIL00C/caseNN.json} fixture would be - the same program-name,
 * case-identifier, binding-key, AID-mnemonic, charset and attribute-mnemonic checks all apply, and a
 * malformed case fails at construction rather than at comparison. Should that fixture directory ever
 * be shipped, this method is the one place that adopts it. The same shape is used by
 * {@code CBTRN01CParityTest}, {@code COCRDUPCParityTest} and {@code COUSR03CParityTest}.
 *
 * <h2>How the unit is reached: no HTTP, no launcher, no context</h2>
 * <p>Sixteen cases declare {@code SERVICE} and drive
 * {@link BillPaymentService#processEnterKey(String, String, NavigationContext)} directly - the real
 * {@code @Service}, constructed through its own five-argument constructor. That is where the
 * arithmetic lives, which is exactly why the service split exists and exactly why a parity case must
 * reach it with nothing in between. Four cases declare {@code CONTROLLER_POJO} and call
 * {@link BillPaymentController#payBill(BillPaymentRequest)} as a plain Java object, for the ten-field
 * screen projection and the two navigation exits. That is the handler's own public seam, and it is the
 * right one to assert through: a stateless screen's entire observable is the body it returns, so a
 * payload-only expectation is the correct expectation rather than a weakened one.
 *
 * <p>There is <strong>no</strong> {@code MockMvc}, no {@code TestRestTemplate}, no
 * {@code WebTestClient}, no servlet container, no {@code JobLauncher}, no {@code ApplicationContext}
 * and no HTTP layer anywhere in this file. The three repositories are stubbed and driven from the
 * case's own seeded datasets; the clock is fixed; the unit of work is real, because
 * {@code app/cbl/COBIL00C.cbl:351} states the {@code UPDATE} option and a record lock outside a unit
 * of work is released before the task that asked for it can rely on it.
 *
 * <h2>Statelessness, and this class's own lack of state</h2>
 * <p>{@code COBIL00C} is pseudo-conversational: its whole conversation state is the 160-byte
 * {@code CARDDEMO-COMMAREA}, the {@code EIBAID} and the values on the screen. All three travel in the
 * request and the response here, which is why {@link ExpectedResponse#navigation()} can be compared
 * at all - a translation that kept any of it in a session could not satisfy these cases.
 * {@link #noServerSideStateSurvivesBetweenInvocations()} asserts it directly.
 *
 * <p>This class holds no mutable state, static or otherwise. Every constant is immutable, every
 * collaborator is constructed per invocation, and each case's unit of work runs over a private
 * in-memory database named after a fresh {@link UUID}, so the twenty cases may run in any order,
 * repeatedly, or in parallel and each observes exactly what it seeded.
 *
 * <h2>What this file is allowed to import</h2>
 * <p>Every internal import resolves to one of the nineteen files this test declares a dependency on, or
 * to a type those files' own public signatures are expressed in - which is the same thing, since a
 * dependency cannot be used without the types its methods take and return. Concretely: the two
 * {@code billing.dto} records are the parameter and return types of
 * {@link BillPaymentController#payBill(BillPaymentRequest)} and
 * {@link BillPaymentService#processEnterKey(BillPaymentRequest)};
 * {@link com.vsergeychik.carddemo.config.DatasetUnitOfWork} is the fifth argument of the service's
 * constructor; {@link com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout} is the type of the
 * two record classes' {@code LAYOUT} constant; {@link CicsAid} supplies the raw {@code EIBAID} byte that
 * {@link PfKeyResolver#resolve(byte)} accepts and the mnemonic table {@link ParityCase} validates an AID
 * against; and {@link BmsAttributes} supplies the attribute table {@link ParityCase} validates a
 * highlight against.
 *
 * <p>Nothing is imported for convenience. The two screen titles, for instance, are transcribed from
 * {@code app/cpy/COTTL01Y.cpy} rather than read from the production constant that also holds them -
 * partly because the whitelist does not reach that constant, and mostly because a transcription is the
 * stronger assertion: see {@link #theTranscribedTitlesAreAtTheirDeclaredWidth()}.
 *
 * <h2>No user rules govern this file</h2>
 * <p>{@code review_rules} reports that no user rules were provided for this project, so no rule
 * forces this file into scope and none constrains how it is written. Their absence is not treated as
 * permission to lower the bar: the enterprise-standard practices the plan substitutes for them are
 * honoured here - exact verified library versions only, reference sources never modified, behaviour
 * preserved defect for defect, explicit charsets and explicit rounding, no static mutable state, no
 * wildcard imports, and tests shipped with the implementation rather than after it.
 *
 * @see BillPaymentService the unit under test for sixteen of the twenty cases
 * @see BillPaymentController the unit under test for the remaining four
 * @see ParityHarness which seeds a case, invokes the unit and captures the fingerprint
 * @see FieldDiffer which compares the fingerprint field by field and counts the differences
 */
@DisplayName("COBIL00C parity - 20 statically derived cases over BillPaymentService and "
        + "BillPaymentController, the bill payment that debits a balance by truncation and never "
        + "by rounding")
class COBIL00CParityTest {

    // =================================================================================================
    // Identity. Every name is taken from the class that owns it rather than transcribed, so this file
    // and the production code cannot drift apart without a compilation failure or a named test failure.
    // =================================================================================================

    /** {@code COBIL00C} - also this class's stem and the {@code parity/<PROGRAM>/} directory name. */
    private static final String PROGRAM = BillPaymentService.WS_PGMNAME;

    /** {@code CB00} - the CSD transaction that runs this program, {@code app/csd/CARDDEMO.CSD}. */
    private static final String TRANSACTION_ID = BillPaymentService.WS_TRANID;

    /**
     * {@code ACCTDAT} - the account master, read for update at {@code :345-354} and rewritten at
     * {@code :379-385}. Taken from the repository so a case addresses the dataset by the same binding
     * key {@code application.yml} declares.
     */
    private static final String ACCTDAT = AccountRepository.CICS_FILE_NAME;

    /**
     * {@code CXACAIX} - the cross-reference read at {@code :410-418}.
     *
     * <p>An alternate-index <strong>path</strong> over the {@code CCXREF} base cluster, not a dataset
     * of its own, which is why one repository serves both and the alternate key is reached through a
     * finder method rather than a second table. {@link #theAlternateIndexIsAFinderOnTheBaseCluster()}
     * asserts that.
     */
    private static final String CXACAIX = CardXrefRepository.ALTERNATE_INDEX_DD_NAME;

    /**
     * {@code TRANSACT} - the transaction master, browsed backwards at {@code :443-482} for the
     * highest existing identifier and added to at {@code :512-520}.
     */
    private static final String TRANSACT = TransactionRepository.CICS_FILE_NAME;

    /**
     * The code page every seeded row and every expected image in this file is expressed in:
     * {@code US-ASCII}, taken from the harness so the two cannot disagree.
     *
     * <p>Named explicitly and never left to the platform. A fixed-width mainframe record is bytes in
     * a specific code page; {@code app/data/ASCII} is the ASCII half of the shipped data, and the
     * EBCDIC half is {@code IBM037} and is not what these cases seed.
     */
    private static final Charset CHARSET = ParityHarness.FIXTURE_CHARSET;

    /** The same code page as the name a case declares, which {@link ParityCase} validates. */
    private static final String CHARSET_NAME = CHARSET.name();

    /**
     * The instant every clock in this gate is pinned to: the version footer of
     * {@code app/cbl/COBIL00C.cbl:571}, which makes each derived image checkable by eye.
     *
     * <p>Pinned rather than read. {@code POPULATE-HEADER-INFO} opens with
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :321} and
     * {@code GET-CURRENT-TIMESTAMP} performs {@code EXEC CICS ASKTIME} at {@code :251-253}, so a case
     * that did not pin the clock could assert neither the screen header nor the twenty-six bytes of
     * {@code TRAN-ORIG-TS}.
     */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    /** The same instant as the local date-time a case declares. */
    private static final String PINNED_CLOCK = LocalDateTime
            .ofInstant(PINNED_INSTANT, ZoneOffset.UTC).toString();

    /** The pinned clock itself, at {@link ZoneOffset#UTC} so no timezone can shift a derived image. */
    private static final Clock CLOCK = Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC);

    /**
     * {@code EIBCALEN} for an invocation that carries a communication area.
     *
     * <p>218 rather than 160: {@code app/cbl/COBIL00C.cbl:63-72} declares the 58-byte
     * {@code CDEMO-CB00-INFO} extension inside the same {@code 01} group as the shared
     * {@code CARDDEMO-COMMAREA}, so the area this program receives is
     * {@value NavigationContext#COMMAREA_LENGTH} plus that extension.
     */
    private static final int EIBCALEN_WITH_COMMAREA =
            NavigationContext.COMMAREA_LENGTH + BillPaymentResponse.CB00_INFO_LENGTH;

    /**
     * {@code EIBCALEN} for the first-ever invocation of {@code CB00} at a cleared screen.
     *
     * <p>Zero is a real and important state rather than an absence: it is the condition
     * {@code app/cbl/COBIL00C.cbl:107} guards on, and it is the only path that reaches
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code :108}.
     */
    private static final int EIBCALEN_COLD_START = 0;

    // =================================================================================================
    // The fixture rows this gate is seeded from, and the identifiers they carry.
    //
    // app/data/ASCII/acctdata.txt row 0 and app/data/ASCII/cardxref.txt row 48 describe the same
    // account, which is what makes a single fixture-seeded happy path possible: the cross-reference row
    // is the only place the card number the transaction record carries can come from.
    // =================================================================================================

    /** {@code acctdata.txt}, one of the nine fixtures {@link DatasetInput} permits. */
    private static final String ACCOUNT_FIXTURE = "acctdata.txt";

    /** {@code cardxref.txt}, seeded at 36 bytes and padded to 50 once, at seed time. */
    private static final String XREF_FIXTURE = "cardxref.txt";

    /** Row 0 of {@code acctdata.txt} - account {@code 00000000001}, balance {@code 194.00}. */
    private static final int ACCOUNT_FIXTURE_ROW = 0;

    /** Row 48 of {@code cardxref.txt} - the only row whose {@code XREF-ACCT-ID} is this account. */
    private static final int XREF_FIXTURE_ROW = 48;

    /** How many rows either fixture contributes: one, so the lookup below cannot be ambiguous. */
    private static final int ONE_ROW = 1;

    /** {@code ACCT-ID} as {@code ACTIDINI PIC X(11)} carries it, and as the fixture stores it. */
    private static final String ACCOUNT_ID = "00000000001";

    /** {@code XREF-CARD-NUM} of {@code cardxref.txt} row 48 - the card the payment is booked to. */
    private static final String CARD_NUMBER = "9680294154603697";

    /** {@code XREF-CUST-ID} of the same row. */
    private static final int CUSTOMER_ID = 1;

    /** {@code ACCT-ACTIVE-STATUS} of {@code acctdata.txt} row 0. */
    private static final String ACCOUNT_STATUS = "Y";

    /**
     * {@code ACCT-CREDIT-LIMIT} of the same row: the stored image is <code>00000020200{</code>, whose
     * trailing brace is a positive sign overpunch standing for a low-order digit of zero. The twelve
     * digit positions are therefore {@code 000000202000}, and at the picture's two-place scale that is
     * {@code 2020.00} - not the {@code 20200.00} the bytes read like at a glance.
     *
     * <p>The implied decimal point is the trap, and it is worth naming because it cost a wrong
     * expectation here before the harness caught it: {@code V} occupies no byte, so an eyeball reading
     * of a zoned span is off by a factor of ten whenever the overpunch is mistaken for a digit.
     */
    private static final String CREDIT_LIMIT = "2020.00";

    /**
     * {@code ACCT-CASH-CREDIT-LIMIT} of the same row: <code>00000010200{</code>, so {@code 1020.00} by
     * the same reading.
     */
    private static final String CASH_CREDIT_LIMIT = "1020.00";

    /** {@code ACCT-OPEN-DATE} of the same row. */
    private static final String OPEN_DATE = "2014-11-20";

    /**
     * {@code ACCT-EXPIRAION-DATE} of the same row.
     *
     * <p>The field name is <strong>misspelled in {@code app/cpy/CVACT01Y.cpy}</strong> and is
     * misspelled here too, deliberately. Correcting it to {@code ACCT-EXPIRATION-DATE} would leave the
     * comparison looking for a field the decoder never produces, which is a silent parity break rather
     * than a loud one - so the constant that names it is taken from {@link AccountRecord} and the value
     * from the fixture.
     */
    private static final String EXPIRATION_DATE = "2025-05-20";

    /** {@code ACCT-REISSUE-DATE} of the same row, which the fixture sets equal to the expiry. */
    private static final String REISSUE_DATE = "2025-05-20";

    /** {@code ACCT-CURR-CYC-CREDIT} of the same row. This program never touches either cycle amount. */
    private static final String CYCLE_CREDIT = "0.00";

    /** {@code ACCT-CURR-CYC-DEBIT} of the same row. */
    private static final String CYCLE_DEBIT = "0.00";

    /** {@code ACCT-ADDR-ZIP} of the same row, taken verbatim from the fixture's bytes. */
    private static final String ADDR_ZIP = "A000000000";

    /** {@code ACCT-GROUP-ID} of the same row, which the fixture leaves blank. */
    private static final String GROUP_ID = "";

    // =================================================================================================
    // The balances the twenty cases are written against, each with the payment and the debited balance
    // the two COBOL rules produce. Every one of these three columns was derived by hand from the source
    // and none was read back out of the translation.
    //
    //   balance          -> TRAN-AMT (S9(09)V99, high-order digit discarded) -> ACCT-CURR-BAL after
    //   194.00           -> 194.00                                          -> 0.00
    //   194.007          -> 194.00  (stored 194.00 first, truncating)       -> 0.00
    //   9999999999.99    -> 999999999.99                                    -> 9000000000.00
    //   0.01             -> 0.01                                            -> 0.00
    // =================================================================================================

    /**
     * The fixture's own balance, {@code 194.00} - stored as eleven digits ending in a positive-zero
     * overpunch, exactly as {@code app/data/ASCII/acctdata.txt} row 0 carries it.
     */
    private static final String FIXTURE_BALANCE = "194.00";

    /**
     * A balance carrying seven ten-thousandths of a cent more than the picture can hold.
     *
     * <p>{@code ACCT-CURR-BAL} is {@code PIC S9(10)V99}, so the two fraction digits are all it has and
     * the remainder is discarded on store - downwards, because no statement in any of the 28 programs
     * carries {@code ROUNDED}. The stored value is therefore {@code 194.00} and not {@code 194.01},
     * which is what a nearest-value rule would have produced from a remainder of seven thousandths.
     */
    private static final String SUB_CENT_BALANCE = "194.007";

    /** The largest value {@code PIC S9(10)V99} can hold: ten integer digits and two fraction digits. */
    private static final String MAX_BALANCE = "9999999999.99";

    /** What {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} leaves in {@code PIC S9(09)V99} from that. */
    private static final String MAX_PAYMENT = "999999999.99";

    /** What {@code :234} therefore computes: the discarded high-order digit, times one billion. */
    private static final String MAX_DEBITED_BALANCE = "9000000000.00";

    /** The smallest balance the {@code <= ZEROS} guard at {@code :198} lets through. */
    private static final String MINIMUM_BALANCE = "0.01";

    /** A balance below zero, which that guard rejects - and whose sign this gate still asserts. */
    private static final String NEGATIVE_BALANCE = "-1234.56";

    /** A balance of exactly zero, the other half of that guard's condition. */
    private static final String ZERO_BALANCE = "0.00";

    /** What every payment that completes leaves behind for a balance under one billion. */
    private static final String SETTLED_BALANCE = "0.00";

    // =================================================================================================
    // Transaction identifiers. TRAN-ID is PIC X(16) and WS-TRAN-ID-NUM is PIC 9(16), and :216-217 move
    // one into the other and add one - so the identifier of the payment is always the highest existing
    // identifier plus one, and an empty master yields one.
    // =================================================================================================

    /** The highest {@code TRAN-ID} the seeded master holds in the cases that seed one. */
    private static final String HIGHEST_TRAN_ID = "0000000000000007";

    /** {@code HIGHEST_TRAN_ID} plus one, which is the identifier the payment is written under. */
    private static final String NEXT_TRAN_ID = "0000000000000008";

    /**
     * What {@code :217} produces after {@code :488}'s {@code MOVE ZEROS TO TRAN-ID}.
     *
     * <p>An empty master makes the {@code READPREV} report {@code DFHRESP(ENDFILE)}, whose arm sets the
     * record identification field to zeros with no flag, no message and no send - so the increment
     * starts from zero and the first payment on an empty master is transaction one.
     */
    private static final String FIRST_TRAN_ID = "0000000000000001";

    // =================================================================================================
    // Literals this gate names because a case has to state them, each self-checking.
    // =================================================================================================

    /**
     * The {@code DFHBMSCA} mnemonic {@code app/cbl/COBIL00C.cbl:526} moves into {@code ERRMSGC}.
     *
     * <p>Named rather than imported as a byte for one reason: {@link ScreenSend} requires an attribute
     * to be declared by <em>mnemonic</em> so a case reads as the source does, and it validates the name
     * against the mnemonic set {@code common.BmsAttributes} publishes. A drift between this spelling and
     * that class therefore fails at case construction, by name, rather than comparing a byte nobody
     * recognises. {@link #theSuccessArmIsTheOnlyPlaceThisScreenColoursTheMessage()} ties the mnemonic to
     * the byte {@link BillPaymentService#MESSAGE_HIGHLIGHT_GREEN} actually carries.
     */
    private static final String ERRMSGC_GREEN = "DFHGREEN";

    /** The symbolic-map length item {@code :163}, {@code :203}, {@code :394} and {@code :562} target. */
    private static final String CURSOR_ACTIDIN = "ACTIDINL";

    /** The symbolic-map length item {@code :189} and {@code :239} target. */
    private static final String CURSOR_CONFIRM = "CONFIRML";

    /** {@code COBIL00} - the mapset {@code app/cbl/COBIL00C.cbl:297} names on every send. */
    private static final String MAPSET = BillPaymentResponse.MAPSET_NAME;

    /** {@code COBIL0A} - the map {@code :296} names on every send. */
    private static final String MAP = BillPaymentResponse.MAP_NAME;

    /** {@code COSGN00C} - where {@code :108} sends a caller that arrived with no communication area. */
    private static final String SIGN_ON_PROGRAM = BillPaymentResponse.SIGN_ON_PROGRAM;

    /** {@code COMEN01C} - where {@code :130} sends PF3 when no origin travelled in the area. */
    private static final String MAIN_MENU_PROGRAM = BillPaymentResponse.MAIN_MENU_PROGRAM;

    /** {@code ACTIDINI} - the account-number entry item of {@code app/cpy-bms/COBIL00.CPY:60}. */
    private static final String ACTIDIN_INPUT = "ACTIDINI";

    /** {@code CONFIRMI} - the one-byte confirmation item of {@code app/cpy-bms/COBIL00.CPY:72}. */
    private static final String CONFIRM_INPUT = "CONFIRMI";

    /** A status no seeded row can produce, which is how the {@code WHEN OTHER} arms are reached. */
    private static final String UNEXPECTED_STATUS = FileStatus.RECORD_LENGTH_CONFLICT;

    /**
     * The one line the {@code WHEN OTHER} arms display, composed rather than transcribed.
     *
     * <p>{@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} appears at {@code :366}, {@code :397},
     * {@code :430}, {@code :461}, {@code :490} and {@code :541}. {@code WS-RESP-CD} is
     * {@code PIC S9(09) COMP}; a refusal that has no single CICS counterpart leaves it unreported, which
     * {@link FileStatus#respNotReportedImage(int)} renders as nine fill characters, while
     * {@code WS-REAS-CD} stays at its {@code VALUE ZEROS}.
     */
    private static final String UNREPORTED_RESP_DISPLAY_LINE =
            BillPaymentService.DISPLAY_RESP_PREFIX
                    + FileStatus.respNotReportedImage(BillPaymentService.WS_RESP_CD_DIGITS)
                    + BillPaymentService.DISPLAY_REAS_PREFIX
                    + "0".repeat(BillPaymentService.WS_RESP_CD_DIGITS);

    /**
     * The same line for the cross-reference refusal, whose repository reports {@code NOTOPEN}.
     *
     * <p>{@code CardXrefRepository.ReadResult.other} carries {@link FileStatus#NOTOPEN} as its
     * {@code RESP}, so this arm's display shows digits where the two above show fill characters. The
     * difference is behaviour and is asserted as such rather than normalised away.
     */
    private static final String NOTOPEN_RESP_DISPLAY_LINE =
            BillPaymentService.DISPLAY_RESP_PREFIX
                    + zeroFilled(FileStatus.NOTOPEN, BillPaymentService.WS_RESP_CD_DIGITS)
                    + BillPaymentService.DISPLAY_REAS_PREFIX
                    + "0".repeat(BillPaymentService.WS_RESP_CD_DIGITS);

    // =================================================================================================
    // The COBOL MOVE rules, restated locally.
    //
    // These four helpers are this file's own restatement of the rules, deliberately NOT delegated to
    // FixedWidthCodec. An expectation composed with the same code the unit under test uses would be a
    // tautology: it would agree with the translation by construction and would agree with a broken
    // translation just as readily. Restating the rules here keeps the two independent, and it is why the
    // expected images below can be checked against app/cpy by eye.
    // =================================================================================================

    /**
     * A COBOL {@code MOVE} into a {@code PIC X(n)} receiver: filled from the left, space-padded on the
     * right, and any overflow discarded on the <strong>right</strong>.
     *
     * @param value the sending value; never {@code null}
     * @param width the receiver's declared width
     * @return exactly {@code width} characters
     */
    private static String picX(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * A span of {@code LOW-VALUES}, which COBOL distinguishes from spaces and this program tests for
     * separately at {@code :116-117}, {@code :159} and {@code :199}.
     *
     * @param width how many bytes
     * @return exactly {@code width} characters, each the null character
     */
    private static String lowValues(int width) {
        return String.valueOf(BillPaymentService.LOW_VALUES).repeat(width);
    }

    /**
     * A COBOL {@code MOVE} into an unsigned {@code PIC 9(n)} receiver: aligned on the implied decimal
     * point, zero-filled on the left, and any overflow discarded on the <strong>left</strong>.
     *
     * @param digits the sending digits; never {@code null}
     * @param width  the receiver's declared width
     * @return exactly {@code width} digit characters
     */
    private static String pic9(String digits, int width) {
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * The same rule for a value already known to be a non-negative number.
     *
     * @param value the value
     * @param width the receiver's declared width
     * @return exactly {@code width} digit characters
     */
    private static String zeroFilled(long value, int width) {
        return pic9(Long.toString(value), width);
    }

    /**
     * A signed zoned-decimal image: the digit positions of the picture, with the sign carried as an
     * overpunch on the trailing byte.
     *
     * <p>This is the representation every monetary span in this system uses, because <strong>no
     * copybook in {@code app/cpy} declares {@code COMP-3} at all</strong> - packed decimal appears only
     * in working storage, and in this program only at {@code :59}, where
     * {@code WS-ABS-TIME PIC S9(15) COMP-3} holds the {@code ASKTIME} reading. A stored record therefore
     * needs no nibble unpacking, and the whole of the encoding is the standard IBM overpunch: for a
     * value of zero or above the low-order digit becomes the opening-brace character or one of
     * {@code 'A'} through {@code 'I'}, and for a value below zero the closing-brace character or one of
     * {@code 'J'} through {@code 'R'}. {@code app/data/ASCII/acctdata.txt} corroborates it in its very
     * first row, where {@code 194.00} appears as ten zeros, a nine, a four and a positive-zero
     * overpunch.
     *
     * @param value         the value; its scale beyond {@code scale} is discarded downwards, which is
     *                      what a COBOL store does in the absence of {@code ROUNDED}
     * @param integerDigits the picture's integer digit positions
     * @param scale         the picture's fraction digit positions
     * @return exactly {@code integerDigits + scale} characters
     */
    private static String zoned(String value, int integerDigits, int scale) {
        BigDecimal stored = new BigDecimal(value).setScale(scale, CobolDecimal.COBOL_ROUNDING);
        String digits = pic9(stored.abs().unscaledValue().toString(), integerDigits + scale);
        char lowOrder = digits.charAt(digits.length() - 1);
        int offset = lowOrder - '0';
        char overpunched = stored.signum() < 0
                ? (offset == 0 ? '}' : (char) ('J' + offset - 1))
                : (offset == 0 ? '{' : (char) ('A' + offset - 1));
        return digits.substring(0, digits.length() - 1) + overpunched;
    }

    // =================================================================================================
    // Record images, composed span by span from the copybooks so a reviewer can check each width by eye
    // and so a missing FILLER shows up as a short record rather than as a shifted field.
    // =================================================================================================

    /**
     * A 300-byte {@code CVACT01Y} account record carrying the given balance and the fixture's other
     * twelve spans.
     *
     * <p>The span list is {@code app/cpy/CVACT01Y.cpy} in declaration order, and it sums mechanically:
     * {@code ACCT-ID 9(11)} at 0, {@code ACCT-ACTIVE-STATUS X(01)} at 11, {@code ACCT-CURR-BAL S9(10)V99}
     * at 12, {@code ACCT-CREDIT-LIMIT} at 24, {@code ACCT-CASH-CREDIT-LIMIT} at 36,
     * {@code ACCT-OPEN-DATE X(10)} at 48, the misspelled {@code ACCT-EXPIRAION-DATE X(10)} at 58,
     * {@code ACCT-REISSUE-DATE X(10)} at 68, {@code ACCT-CURR-CYC-CREDIT} at 78,
     * {@code ACCT-CURR-CYC-DEBIT} at 90, {@code ACCT-ADDR-ZIP X(10)} at 102,
     * {@code ACCT-GROUP-ID X(10)} at 112 and {@code FILLER X(178)} at 122, which is
     * {@value AccountRecord#RECORD_LENGTH}.
     *
     * <p>{@code FILLER} is emitted, as spaces. It is not a gap: without it the image would be 122 bytes,
     * and the width assertion in {@link #theRewrittenAccountIsThreeHundredBytesAndKeepsTheMisspelling()}
     * is what proves it was written.
     *
     * @param balance the value of {@code ACCT-CURR-BAL}
     * @return exactly {@value AccountRecord#RECORD_LENGTH} characters
     */
    private static String accountImage(String balance) {
        return pic9(ACCOUNT_ID, AccountRecord.ACCT_ID_LENGTH)
                + picX(ACCOUNT_STATUS, AccountRecord.ACCT_ACTIVE_STATUS_LENGTH)
                + zoned(balance, AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE)
                + zoned(CREDIT_LIMIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + zoned(CASH_CREDIT_LIMIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + picX(OPEN_DATE, AccountRecord.ACCT_OPEN_DATE_LENGTH)
                + picX(EXPIRATION_DATE, AccountRecord.ACCT_EXPIRAION_DATE_LENGTH)
                + picX(REISSUE_DATE, AccountRecord.ACCT_REISSUE_DATE_LENGTH)
                + zoned(CYCLE_CREDIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + zoned(CYCLE_DEBIT, AccountRecord.MONETARY_INTEGER_DIGITS,
                        AccountRecord.MONETARY_SCALE)
                + picX(ADDR_ZIP, AccountRecord.ACCT_ADDR_ZIP_LENGTH)
                + picX(GROUP_ID, AccountRecord.ACCT_GROUP_ID_LENGTH)
                + " ".repeat(AccountRecord.FILLER_LENGTH);
    }

    /**
     * A 350-byte {@code CVTRA05Y} transaction record as {@code app/cbl/COBIL00C.cbl:218-232} assembles
     * one: {@code INITIALIZE}, then the eleven moves in source order, then the two timestamps.
     *
     * <p>The span list is {@code app/cpy/CVTRA05Y.cpy} in declaration order and sums to
     * {@value TranRecord#RECORD_LENGTH}: {@code TRAN-ID X(16)} at 0, {@code TRAN-TYPE-CD X(02)} at 16,
     * {@code TRAN-CAT-CD 9(04)} at 18, {@code TRAN-SOURCE X(10)} at 22, {@code TRAN-DESC X(100)} at 32,
     * {@code TRAN-AMT S9(09)V99} at 132, {@code TRAN-MERCHANT-ID 9(09)} at 143,
     * {@code TRAN-MERCHANT-NAME X(50)} at 152, {@code TRAN-MERCHANT-CITY X(50)} at 202,
     * {@code TRAN-MERCHANT-ZIP X(10)} at 252, {@code TRAN-CARD-NUM X(16)} at 262,
     * {@code TRAN-ORIG-TS X(26)} at 278, {@code TRAN-PROC-TS X(26)} at 304 and {@code FILLER X(20)}
     * at 330.
     *
     * <p>Three of those spans are the cross-width moves worth watching. {@code TRAN-DESC} receives the
     * 21-character literal {@code 'BILL PAYMENT - ONLINE'} into {@code PIC X(100)}, so 79 spaces follow
     * it on the right. {@code TRAN-MERCHANT-CITY} and {@code TRAN-MERCHANT-ZIP} both receive
     * {@code 'N/A'}, padded to 50 and 10. And {@code TRAN-AMT} receives {@code ACCT-CURR-BAL}, which is
     * a digit wider than it is - the rule stated in this class's own documentation and pinned by
     * {@code case03}.
     *
     * @param tranId  the identifier {@code :219} moves in, which is the highest existing one plus one
     * @param amount  the value {@code :224} moves in, at {@code TRAN-AMT}'s own picture
     * @param cardNum the value {@code :225} moves in, from {@code XREF-CARD-NUM}
     * @return exactly {@value TranRecord#RECORD_LENGTH} characters
     */
    private static String transactionImage(String tranId, String amount, String cardNum) {
        String timestamp = expectedTimestamp();
        return picX(tranId, TranRecord.TRAN_ID_LENGTH)
                + picX(BillPaymentService.TRAN_TYPE_CD_BILL_PAYMENT, TranRecord.TRAN_TYPE_CD_LENGTH)
                + zeroFilled(BillPaymentService.TRAN_CAT_CD_BILL_PAYMENT, TranRecord.TRAN_CAT_CD_LENGTH)
                + picX(BillPaymentService.TRAN_SOURCE_POS_TERM, TranRecord.TRAN_SOURCE_LENGTH)
                + picX(BillPaymentService.TRAN_DESC_BILL_PAYMENT_ONLINE, TranRecord.TRAN_DESC_LENGTH)
                + zoned(amount, TranRecord.TRAN_AMT_INTEGER_DIGITS, TranRecord.TRAN_AMT_SCALE)
                + zeroFilled(BillPaymentService.TRAN_MERCHANT_ID_BILL_PAYMENT,
                        TranRecord.TRAN_MERCHANT_ID_LENGTH)
                + picX(BillPaymentService.TRAN_MERCHANT_NAME_BILL_PAYMENT,
                        TranRecord.TRAN_MERCHANT_NAME_LENGTH)
                + picX(BillPaymentService.TRAN_MERCHANT_NOT_APPLICABLE,
                        TranRecord.TRAN_MERCHANT_CITY_LENGTH)
                + picX(BillPaymentService.TRAN_MERCHANT_NOT_APPLICABLE,
                        TranRecord.TRAN_MERCHANT_ZIP_LENGTH)
                + picX(cardNum, TranRecord.TRAN_CARD_NUM_LENGTH)
                + picX(timestamp, TranRecord.TRAN_ORIG_TS_LENGTH)
                + picX(timestamp, TranRecord.TRAN_PROC_TS_LENGTH)
                + " ".repeat(TranRecord.FILLER_LENGTH);
    }

    /**
     * The twenty-six bytes {@code GET-CURRENT-TIMESTAMP} composes from the pinned clock.
     *
     * <p>{@code app/cbl/COBIL00C.cbl:251-266} reads {@code ASKTIME} once, formats it with
     * {@code DATESEP('-')} and {@code TIMESEP(':')}, moves the ten date bytes to
     * {@code WS-TIMESTAMP(01:10)} and the eight time bytes to {@code WS-TIMESTAMP(12:08)}, and moves
     * zeros to the microseconds. Position 11 and positions 20 and 21 are the separator {@code FILLER}s
     * the group declares, which is why the image reads date, space, time, stop, six zeros. Both
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} receive it, from one move with two receivers at
     * {@code :231-232}: this program has no separate processing time.
     *
     * @return exactly {@value TranRecord#TRAN_ORIG_TS_LENGTH} characters
     */
    private static String expectedTimestamp() {
        LocalDateTime at = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        return zeroFilled(at.getYear(), 4) + '-' + zeroFilled(at.getMonthValue(), 2) + '-'
                + zeroFilled(at.getDayOfMonth(), 2) + ' '
                + zeroFilled(at.getHour(), 2) + ':' + zeroFilled(at.getMinute(), 2) + ':'
                + zeroFilled(at.getSecond(), 2) + '.' + "0".repeat(6);
    }

    /**
     * The 14-character image {@code :193-194} leaves in {@code CURBALI}.
     *
     * <p>{@code WS-CURR-BAL} is {@code PIC +9999999999.99}, an edited field: a sign position, ten
     * integer positions, an actual decimal point character and two fraction positions, which is
     * {@value BillPaymentResponse#CUR_BAL_LENGTH} and an exact fit for the symbolic map's
     * {@code CURBALI PIC X(14)}. The {@code +} position emits a plus for a value of zero or above and a
     * minus below, and the digits are the value's own with no separators and no suppression.
     *
     * @param balance the value moved into it
     * @return exactly {@value BillPaymentResponse#CUR_BAL_LENGTH} characters
     */
    private static String editedBalance(String balance) {
        BigDecimal stored = new BigDecimal(balance)
                .setScale(CobolDecimal.MONETARY_SCALE, CobolDecimal.COBOL_ROUNDING);
        String digits = pic9(stored.abs().unscaledValue().toString(),
                AccountRecord.MONETARY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE);
        char sign = stored.signum() < 0
                ? BillPaymentService.CURR_BAL_SIGN_NEGATIVE
                : BillPaymentService.CURR_BAL_SIGN_POSITIVE;
        return sign + digits.substring(0, AccountRecord.MONETARY_INTEGER_DIGITS)
                + BillPaymentService.CURR_BAL_DECIMAL_POINT
                + digits.substring(AccountRecord.MONETARY_INTEGER_DIGITS);
    }

    /**
     * A 350-byte transaction row for the seeded master, carrying only its identifier.
     *
     * <p>The browse at {@code :443-482} reads the highest existing identifier and nothing else from the
     * row, so every other span is what {@code INITIALIZE} leaves - {@code PIC X} spans and the trailing
     * {@code FILLER} space-filled, the three numeric spans zero-filled. Composed through the model
     * rather than by hand because this is an <em>input</em>, not an expectation: an input has no
     * independence to preserve, and building it through {@link TranRecord} keeps the seeded row at the
     * geometry the copybook declares.
     *
     * @param tranId the identifier the row carries
     * @return exactly {@value TranRecord#RECORD_LENGTH} characters
     */
    private static String seededTransactionRow(String tranId) {
        TranRecord row = new TranRecord(CHARSET);
        row.moveTranId(tranId);
        return row.displayImage();
    }

    // =================================================================================================
    // The case set and the gate.
    // =================================================================================================

    /**
     * One case bound to the adapter that reaches its unit.
     *
     * @param parityCase  the inputs and the expectations, validated by {@link ParityCase} itself
     * @param adapterKind the kind of unit the adapter constructs, checked against the case's own
     *                    declaration before anything runs
     * @param adapter     how the unit is constructed and called
     * @param unitName    the method a failure should name, so a diff reads as a place in the code
     */
    private record ParityScenario(ParityCase parityCase,
                                  UnitKind adapterKind,
                                  ParityHarness.ParityUnit adapter,
                                  String unitName) {

        /** @return {@code case01} through {@code case20} */
        String caseId() {
            return parityCase.caseId();
        }

        /**
         * The parameterised test's display name: the case, the unit and the first sentence of the
         * description, so a failing run says what was being asserted without opening the file.
         *
         * @return a one-line identity for this scenario
         */
        @Override
        public String toString() {
            String description = parityCase.description();
            int firstStop = description.indexOf(". ");
            return caseId() + " [" + unitName + "] "
                    + (firstStop < 0 ? description : description.substring(0, firstStop));
        }
    }

    /**
     * This program's complete case set, in {@code case01} through {@code case20} order.
     *
     * <p>The single seam between the gate and the cases. The twenty are declared here and constructed
     * through {@link ParityCase}'s own canonical constructor, so each is validated exactly as a shipped
     * {@code src/test/resources/parity/COBIL00C/caseNN.json} fixture would be; should that directory
     * ever be shipped, {@code ParityHarness.casesOf(PROGRAM)} substitutes here and nothing else in this
     * class changes.
     *
     * @return the twenty scenarios, in ascending case order
     * @throws IllegalStateException if the set is not exactly the twenty, in order, all naming this
     *     program
     */
    static List<ParityScenario> cases() {
        List<ParityScenario> scenarios = List.of(case01(), case02(), case03(), case04(), case05(),
                case06(), case07(), case08(), case09(), case10(), case11(), case12(), case13(),
                case14(), case15(), case16(), case17(), case18(), case19(), case20());
        requireCompleteCaseSet(scenarios);
        return scenarios;
    }

    /**
     * Refuses a case set that is not exactly {@code case01} through {@code case20} of this program, in
     * order and without repetition.
     *
     * <p>Loud on purpose, and checked inside the supplier so it cannot be bypassed by running one case.
     * "The diff count is zero across all twenty cases" is satisfied vacuously by a set of four, so a
     * short, long, misnumbered or duplicated set is not a smaller gate - it is a gate that has stopped
     * asking the questions while still reporting green.
     *
     * @param scenarios the declared set
     * @throws IllegalStateException if the set is not the exact twenty
     */
    private static void requireCompleteCaseSet(List<ParityScenario> scenarios) {
        if (scenarios.size() != ParityHarness.CASES_PER_PROGRAM) {
            throw new IllegalStateException(PROGRAM + " declares " + scenarios.size()
                    + " parity case(s) but the gate requires exactly "
                    + ParityHarness.CASES_PER_PROGRAM + ", named case01 through case"
                    + ParityHarness.CASES_PER_PROGRAM + ". A short set is not a smaller gate, it is a "
                    + "gate that passes without asking the questions.");
        }
        for (int ordinal = 1; ordinal <= scenarios.size(); ordinal++) {
            ParityCase declared = scenarios.get(ordinal - 1).parityCase();
            String required = ParityHarness.caseId(ordinal);
            if (!required.equals(declared.caseId())) {
                throw new IllegalStateException("Parity case " + ordinal + " of " + PROGRAM
                        + " is declared \"" + declared.caseId() + "\" where the set requires \""
                        + required + "\". The identifiers are positional and they name the fixture, so "
                        + "a gap or a repeat means a case nobody runs, or one running twice while "
                        + "another runs not at all.");
            }
            if (!PROGRAM.equals(declared.program())) {
                throw new IllegalStateException("Case " + required + " names program "
                        + declared.program() + " but this class gates " + PROGRAM + ", whose cases "
                        + "belong in " + ParityHarness.CASE_RESOURCE_ROOT + PROGRAM + '/');
            }
        }
    }

    /**
     * Runs one case and requires the diff count to be zero.
     *
     * <p>The whole gate is the last assertion. A module is not complete until the count is zero across
     * all twenty of its cases: nineteen clean and one difference is an incomplete module, not a nearly
     * complete one. The failure text is {@link DiffResult#render()}, which names every difference it
     * found - the dataset, the row, the field, its offset and length, and the expected and observed
     * values - and never truncates the list, so one run is enough to see the whole picture.
     *
     * @param scenario one of the twenty, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/COBIL00C.cbl, with a diff count of zero")
    void theTranslationMatchesTheCobolFieldForField(ParityScenario scenario) {
        DiffResult result = ParityHarness.usAscii()
                .judge(scenario.parityCase(), scenario.adapterKind(), scenario.adapter());

        assertThat(result.count())
                .describedAs("%s/%s (%s) must diff to zero.%n%s", PROGRAM, scenario.caseId(),
                        scenario.unitName(), result.render())
                .isZero();
    }

    // =================================================================================================
    // THE SERVICE ADAPTER.
    //
    // Constructs the real BillPaymentService through its own five-argument constructor and calls
    // PROCESS-ENTER-KEY. No MockMvc, no HTTP, no servlet container, no JobLauncher, no Spring context.
    //
    // Every branch below is a property of the DECLARED INPUT and never of the expectation. Invocation
    // deliberately exposes no part of what a case expects, so this adapter cannot branch on it: it reads
    // the seeded rows, the two received map items and the forced outcomes, and nothing else.
    //
    //   the case declares                       the backend condition
    //   -------------------------------------   ------------------------------------------------------
    //   no ACCTDAT row                          the keyed read reports NOTFND      - :359
    //   an ACCTDAT row                          the keyed read returns it          - :357
    //   no CXACAIX row                          the alternate-index read reports NOTFND - :423
    //   a CXACAIX row                           the alternate-index read returns it     - :421
    //   no TRANSACT row                         the backward browse reports ENDFILE - :487
    //   TRANSACT rows                           the browse returns the highest      - :485
    //   a forced outcome                        the WHEN OTHER arm no seeded row can reach
    // =================================================================================================

    /**
     * Constructs the unit for one case, runs one complete {@code PROCESS-ENTER-KEY}, and records what
     * the run produced.
     *
     * <p>{@code null} is returned rather than a built outcome, which is what the harness asks of a unit
     * whose observations are already in the recorder: the recorder survives an exception and a method's
     * return value does not.
     *
     * @param invocation the seeded datasets, the received map, the forced outcomes and the pinned clock
     * @return {@code null}, meaning the recorder holds the outcome
     */
    private static UnitOutcome billPaymentServiceUnit(Invocation invocation) {
        Charset charset = invocation.charset();
        List<String> seededAccounts = rowsOf(invocation, ACCTDAT);
        List<String> seededTransactions = rowsOf(invocation, TRANSACT);

        AtomicReference<String> rewrittenAccount = new AtomicReference<>();
        AtomicReference<String> addedTransaction = new AtomicReference<>();

        AccountRepository.WriteResult rewriteOutcome = accountRewriteOutcome(invocation);
        TransactionRepository.WriteResult writeOutcome = transactionWriteOutcome(invocation);

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.datasetCharset()).thenReturn(charset);
        when(accounts.readForUpdate(anyString()))
                .thenReturn(accountReadOutcome(invocation, seededAccounts, charset));
        when(accounts.rewrite(any())).thenAnswer(call -> {
            AccountRecord offered = call.getArgument(0);
            rewrittenAccount.set(offered.toFixedWidthString());
            return rewriteOutcome;
        });

        CardXrefRepository crossReference = mock(CardXrefRepository.class);
        when(crossReference.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(crossReferenceReadOutcome(invocation, charset));

        TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
        when(browse.readPrev()).thenReturn(highestTransactionOf(seededTransactions, charset));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        when(transactions.write(any())).thenAnswer(call -> {
            TranRecord offered = call.getArgument(0);
            addedTransaction.set(offered.displayImage());
            return writeOutcome;
        });

        BillPaymentService service = new BillPaymentService(accounts, crossReference, transactions,
                invocation.clock(), privateUnitOfWork());

        PaymentState state = service.processEnterKey(invocation.mapFields().get(ACTIDIN_INPUT),
                invocation.mapFields().get(CONFIRM_INPUT), commareaOf(invocation));

        UnitOutcome.Builder recorder = invocation.recorder();
        recordTransactionChannel(recorder, seededTransactions, addedTransaction.get(),
                writeOutcome.isWritten());
        recordAccountChannel(recorder, seededAccounts, rewrittenAccount.get(),
                rewriteOutcome.isWritten());
        for (String line : state.displays()) {
            recorder.display(line);
        }
        recorder.response(observedServiceResponse(state));

        // COBIL00C never touches RETURN-CODE - the identifier does not appear in the source at all - so
        // every path through it ends at zero. Stated rather than defaulted, so the fingerprint says so.
        recorder.returnCode(0);
        return null;
    }

    /**
     * What a keyed read for update of {@code ACCTDAT} reports - {@code app/cbl/COBIL00C.cbl:345-372}.
     *
     * @param invocation the case's invocation, which owns any forced outcome
     * @param seeded     the rows the case seeded into {@code ACCTDAT}
     * @param charset    the code page the rows are expressed in
     * @return the outcome the three arms of that {@code EVALUATE} distinguish
     */
    private static AccountRepository.ReadResult accountReadOutcome(Invocation invocation,
                                                                   List<String> seeded,
                                                                   Charset charset) {
        if (invocation.hasForcedOutcome(RepositoryOperation.READ_FOR_UPDATE)) {
            // Taken through forcedOutcome rather than read off the case, because that call is what marks
            // the outcome consumed - and the harness refuses a run whose declared forcing never fired.
            return accountReadOf(invocation.forcedOutcome(RepositoryOperation.READ_FOR_UPDATE));
        }
        if (seeded.isEmpty()) {
            return AccountRepository.ReadResult.notFound();
        }
        return AccountRepository.ReadResult.found(AccountRecord.decode(seeded.get(0), charset));
    }

    /**
     * Translates a forced outcome into the read result that reaches the arm it names.
     *
     * @param forced the outcome the case forces
     * @return the matching read result
     */
    private static AccountRepository.ReadResult accountReadOf(ForcedOutcome forced) {
        return switch (forced.outcome()) {
            case NOT_FOUND -> AccountRepository.ReadResult.notFound();
            case END_OF_FILE -> AccountRepository.ReadResult.endOfFile();
            default -> AccountRepository.ReadResult.of(UNEXPECTED_STATUS);
        };
    }

    /**
     * What the {@code REWRITE} of {@code ACCTDAT} reports - {@code app/cbl/COBIL00C.cbl:379-403}.
     *
     * @param invocation the case's invocation
     * @return the outcome the three arms of that {@code EVALUATE} distinguish
     */
    private static AccountRepository.WriteResult accountRewriteOutcome(Invocation invocation) {
        if (!invocation.hasForcedOutcome(RepositoryOperation.REWRITE)) {
            return AccountRepository.WriteResult.written();
        }
        ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.REWRITE);
        return switch (forced.outcome()) {
            case NOT_FOUND -> AccountRepository.WriteResult.notFound();
            default -> AccountRepository.WriteResult.of(UNEXPECTED_STATUS);
        };
    }

    /**
     * What the alternate-index read of the cross-reference reports -
     * {@code app/cbl/COBIL00C.cbl:410-436}.
     *
     * <p>One repository and one finder method, because {@code CXACAIX} is a <em>path</em> over the
     * {@code CCXREF} base cluster rather than a dataset of its own.
     *
     * @param invocation the case's invocation
     * @param charset    the code page the seeded row is expressed in
     * @return the outcome the three arms of that {@code EVALUATE} distinguish
     */
    private static CardXrefRepository.ReadResult crossReferenceReadOutcome(Invocation invocation,
                                                                          Charset charset) {
        if (invocation.hasForcedOutcome(RepositoryOperation.READ)) {
            ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.READ);
            return switch (forced.outcome()) {
                case NOT_FOUND -> CardXrefRepository.ReadResult.notFound(CXACAIX);
                case END_OF_FILE -> CardXrefRepository.ReadResult.endOfFile(CXACAIX);
                default -> CardXrefRepository.ReadResult.other(CXACAIX, UNEXPECTED_STATUS);
            };
        }
        List<String> seeded = rowsOf(invocation, CXACAIX);
        if (seeded.isEmpty()) {
            return CardXrefRepository.ReadResult.notFound(CXACAIX);
        }
        String stored = seeded.get(0);
        return CardXrefRepository.ReadResult.found(CXACAIX,
                CardXrefRecord.decode(stored.getBytes(charset), charset), stored);
    }

    /**
     * What the backward {@code READPREV} reports - {@code app/cbl/COBIL00C.cbl:474-496}.
     *
     * <p>{@code :212} moves {@code HIGH-VALUES} into the record identification field before the
     * {@code STARTBR}, which is CICS's way of saying "position past the last record", so the first
     * backward read returns the <em>highest</em> existing identifier. The seeded rows are in key order,
     * so that is the last of them; an empty master reports {@code DFHRESP(ENDFILE)} instead, whose arm
     * sets the identifier to zeros with no flag, no message and no send.
     *
     * @param seeded  the rows the case seeded into {@code TRANSACT}, in key order
     * @param charset the code page they are expressed in
     * @return the outcome the three arms of that {@code EVALUATE} distinguish
     */
    private static TransactionRepository.ReadResult highestTransactionOf(List<String> seeded,
                                                                        Charset charset) {
        if (seeded.isEmpty()) {
            return TransactionRepository.ReadResult.endOfFile(TRANSACT);
        }
        return TransactionRepository.ReadResult.found(TRANSACT,
                TranRecord.decode(seeded.get(seeded.size() - 1), charset));
    }

    /**
     * What the {@code WRITE} of {@code TRANSACT} reports - {@code app/cbl/COBIL00C.cbl:512-547}.
     *
     * <p>Three arms, and the middle one is shared: {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)}
     * fall into one body at {@code :533-539}, which is why a single {@code DUPLICATE} outcome reaches it.
     *
     * @param invocation the case's invocation
     * @return the outcome those arms distinguish
     */
    private static TransactionRepository.WriteResult transactionWriteOutcome(Invocation invocation) {
        if (!invocation.hasForcedOutcome(RepositoryOperation.WRITE)) {
            return TransactionRepository.WriteResult.written(TRANSACT);
        }
        ForcedOutcome forced = invocation.forcedOutcome(RepositoryOperation.WRITE);
        return switch (forced.outcome()) {
            case DUPLICATE -> TransactionRepository.WriteResult.duplicate(TRANSACT);
            default -> TransactionRepository.WriteResult.other(TRANSACT, UNEXPECTED_STATUS);
        };
    }

    // =================================================================================================
    // Recording. What reaches the fingerprint is what the dataset holds, never what the case declared,
    // so "the master gained exactly one row" and "the account was left alone" are real assertions.
    // =================================================================================================

    /**
     * Reports the {@code TRANSACT} channels: the row the {@code WRITE} added, and the master afterwards.
     *
     * <p>A refused {@code WRITE} adds nothing, so the writes channel is reported as opened and empty
     * rather than omitted - an omitted channel says nothing, while an empty one positively states that
     * the {@code WRITE} was issued and did not land. The final state is the seeded rows plus the new one
     * in key order, which is where it belongs: the identifier is the highest existing one plus one.
     *
     * @param recorder the harness's recorder
     * @param seeded   the rows the case seeded, in key order
     * @param added    the image the {@code WRITE} was handed, or {@code null} if it was never reached
     * @param written  whether the {@code WRITE} reported {@code DFHRESP(NORMAL)}
     */
    private static void recordTransactionChannel(UnitOutcome.Builder recorder, List<String> seeded,
                                                 String added, boolean written) {
        boolean landed = added != null && written;
        if (added == null) {
            recorder.openedWithoutWriting(TRANSACT, TranRecord.LAYOUT);
        } else if (landed) {
            recorder.wrote(TRANSACT, TranRecord.LAYOUT, added);
        } else {
            recorder.openedWithoutWriting(TRANSACT, TranRecord.LAYOUT);
        }
        List<String> afterwards = new ArrayList<>(seeded);
        if (landed) {
            afterwards.add(added);
        }
        recorder.finalState(TRANSACT, TranRecord.LAYOUT, afterwards);
    }

    /**
     * Reports the {@code ACCTDAT} channels: the image the {@code REWRITE} replaced the record with, and
     * the dataset afterwards.
     *
     * <p>The final state is read from the seeded rows with row 0 replaced, so a run that never reached
     * {@code :235} - or reached it and was refused - reports the balance it started with. That is what
     * makes "the payment did not happen" assertable rather than merely unstated.
     *
     * @param recorder  the harness's recorder
     * @param seeded    the rows the case seeded
     * @param rewritten the image the {@code REWRITE} was handed, or {@code null} if never reached
     * @param written   whether the {@code REWRITE} reported {@code DFHRESP(NORMAL)}
     */
    private static void recordAccountChannel(UnitOutcome.Builder recorder, List<String> seeded,
                                             String rewritten, boolean written) {
        boolean landed = rewritten != null && written;
        if (landed) {
            recorder.wrote(ACCTDAT, AccountRecord.LAYOUT, rewritten);
        } else {
            recorder.openedWithoutWriting(ACCTDAT, AccountRecord.LAYOUT);
        }
        if (seeded.isEmpty()) {
            recorder.finalState(ACCTDAT, AccountRecord.LAYOUT, List.of());
            return;
        }
        List<String> afterwards = new ArrayList<>(seeded);
        if (landed) {
            afterwards.set(0, rewritten);
        }
        recorder.finalState(ACCTDAT, AccountRecord.LAYOUT, afterwards);
    }

    /**
     * The online response one {@code PROCESS-ENTER-KEY} produced.
     *
     * <p>Three members are deliberately absent. {@code nextProgram} is absent because the service reaches
     * no {@code XCTL}: the two transfers in this program are at {@code :281-284}, performed only from
     * {@code MAIN-PARA}, so naming one here would claim a transfer that did not happen. {@code nextMapset}
     * and {@code nextMap} are absent for the same reason at one remove - {@code :296-297} names them on
     * the {@code SEND}, and it is the controller that projects them, which is why {@code case18} pins them
     * and these sixteen do not.
     *
     * <p>{@link Termination#RETURN_TRANSID} is right for every one of the sixteen: the service is reached
     * only from the {@code DFHENTER} arm at {@code :127} and from the first-entry path at {@code :120},
     * and both fall through to {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :146-149}. The pseudo-conversation continues, carrying the area in the payload.
     *
     * @param state the working storage of the invocation that has just finished
     * @return the observed response
     */
    private static ObservedResponse observedServiceResponse(PaymentState state) {
        return new ObservedResponse(null, null, null, navigationOf(state.commarea()),
                observedSendsOf(state.sentScreens()), cursorItemOf(state.cursorField()),
                Termination.RETURN_TRANSID);
    }

    /**
     * Every screen this invocation sent, in send order.
     *
     * <p>A list rather than a single state, because the <strong>send count is itself behaviour</strong>.
     * A completed payment sends twice - once from the {@code WRITE}'s {@code DFHRESP(NORMAL)} arm at
     * {@code :532} and once from the unconditional {@code PERFORM SEND-BILLPAY-SCREEN} at {@code :242} -
     * and a model carrying one screen would have to discard one of them and could never assert that the
     * quirk was preserved.
     *
     * <p>Each send carries the four items this program's own paragraphs write. The header six -
     * {@code TRNNAMEO}, {@code TITLE01O}, {@code CURDATEO}, {@code PGMNAMEO}, {@code TITLE02O} and
     * {@code CURTIMEO} - are {@code POPULATE-HEADER-INFO}'s at {@code :319-338} and belong to the
     * controller, so a service case does not claim them.
     *
     * @param sent the screens the invocation recorded
     * @return one observed send per screen, in order
     */
    private static List<ObservedSend> observedSendsOf(List<SentScreen> sent) {
        List<ObservedSend> sends = new ArrayList<>(sent.size());
        for (SentScreen screen : sent) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ACTIDINO", screen.actIdIn());
            fields.put("CURBALO", screen.curBal());
            fields.put("CONFIRMO", screen.confirm());
            fields.put("ERRMSGO", screen.errMsg());
            Map<String, String> attributes = new LinkedHashMap<>();
            if (BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.equals(screen.messageHighlight())) {
                // :526  MOVE DFHGREEN TO ERRMSGC OF COBIL0AO - the success arm, and the only attribute
                // assignment anywhere in this program.
                attributes.put("ERRMSGC", ERRMSGC_GREEN);
            }
            sends.add(attributes.isEmpty()
                    ? ObservedSend.ofFields(fields)
                    : new ObservedSend(fields, attributes));
        }
        return sends;
    }

    /**
     * The 160-byte communication area as sixteen named spans, at the widths
     * {@code app/cpy/COCOM01Y.cpy} declares.
     *
     * <p>This is where statelessness is asserted rather than asserted <em>about</em>: the conversation
     * state is comparable because it travels in the payload, and a translation that kept any of it in a
     * session would have nothing to put here.
     *
     * @param commarea the area the invocation returned
     * @return the sixteen spans in declaration order
     */
    private static Map<String, String> navigationOf(NavigationContext commarea) {
        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.put(NavigationContext.FROM_TRANID_FIELD, commarea.fromTranid());
        navigation.put(NavigationContext.FROM_PROGRAM_FIELD, commarea.fromProgram());
        navigation.put(NavigationContext.TO_TRANID_FIELD, commarea.toTranid());
        navigation.put(NavigationContext.TO_PROGRAM_FIELD, commarea.toProgram());
        navigation.put(NavigationContext.USER_ID_FIELD, commarea.userId());
        navigation.put(NavigationContext.USER_TYPE_FIELD, commarea.userType());
        navigation.put(NavigationContext.PGM_CONTEXT_FIELD,
                zeroFilled(commarea.pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH));
        navigation.put(NavigationContext.CUST_ID_FIELD,
                zeroFilled(commarea.custId(), NavigationContext.CUST_ID_LENGTH));
        navigation.put(NavigationContext.CUST_FNAME_FIELD, commarea.custFname());
        navigation.put(NavigationContext.CUST_MNAME_FIELD, commarea.custMname());
        navigation.put(NavigationContext.CUST_LNAME_FIELD, commarea.custLname());
        navigation.put(NavigationContext.ACCT_ID_FIELD,
                zeroFilled(commarea.acctId(), NavigationContext.ACCT_ID_LENGTH));
        navigation.put(NavigationContext.ACCT_STATUS_FIELD, commarea.acctStatus());
        navigation.put(NavigationContext.CARD_NUM_FIELD,
                zeroFilled(commarea.cardNum(), NavigationContext.CARD_NUM_LENGTH));
        navigation.put(NavigationContext.LAST_MAP_FIELD, commarea.lastMap());
        navigation.put(NavigationContext.LAST_MAPSET_FIELD, commarea.lastMapset());
        return navigation;
    }

    /**
     * The symbolic-map length item {@code MOVE -1} was moved into, which is how COBOL positions the
     * cursor.
     *
     * @param cursor where the invocation left the cursor
     * @return {@code ACTIDINL}, {@code CONFIRML}, or {@code null} when no cursor was positioned
     */
    private static String cursorItemOf(CursorField cursor) {
        return switch (cursor) {
            case ACTIDIN -> CURSOR_ACTIDIN;
            case CONFIRM -> CURSOR_CONFIRM;
            case NONE -> null;
        };
    }

    // =================================================================================================
    // Shared plumbing: the seeded rows, the communication area, and the task boundary.
    // =================================================================================================

    /**
     * The rows a case seeded into one dataset, or an empty list where it declared none.
     *
     * @param invocation the case's invocation
     * @param dataset    the binding key
     * @return the rows in seeding order, never {@code null}
     */
    private static List<String> rowsOf(Invocation invocation, String dataset) {
        if (!invocation.hasDataset(dataset)) {
            return List.of();
        }
        SeededDataset seeded = invocation.dataset(dataset);
        return seeded.rows();
    }

    /**
     * The communication area the invocation is called with, rebuilt from the fields the case declares.
     *
     * <p>Only the two fields this program reads are declared by a case and therefore rebuilt here:
     * {@code CDEMO-PGM-CONTEXT}, which {@code :112} tests through the {@code CDEMO-PGM-REENTER}
     * condition name, and {@code CDEMO-FROM-PROGRAM}, which {@code :129} tests to decide where PF3
     * goes. Every other span keeps the value {@link NavigationContext#empty()} gives it, which is
     * spaces for an alphanumeric span and zeros for a numeric one - the state a
     * {@code WORKING-STORAGE} area starts in.
     *
     * @param invocation the case's invocation
     * @return the area, never {@code null}
     */
    private static NavigationContext commareaOf(Invocation invocation) {
        NavigationContext commarea = NavigationContext.empty();
        Map<String, String> declared = invocation.commarea();
        String context = declared.get(NavigationContext.PGM_CONTEXT_FIELD);
        if (context != null) {
            commarea = commarea.withPgmContext(Integer.parseInt(context.trim()));
        }
        String fromProgram = declared.get(NavigationContext.FROM_PROGRAM_FIELD);
        if (fromProgram != null) {
            commarea = commarea.withFromProgram(fromProgram);
        }
        return commarea;
    }

    /**
     * A unit of work over a private in-memory database, one per invocation.
     *
     * <p>Real rather than stubbed, and it has to be. {@code app/cbl/COBIL00C.cbl:351} states the
     * {@code UPDATE} option, so the read takes a record lock the task holds until its syncpoint, and the
     * program relies on that: it reads the account, adds a transaction, computes the new balance and
     * rewrites the account, and those four steps are coherent only if nothing can move the record in
     * between. {@link AccountRepository#readForUpdate(String)} refuses a locking read outside a unit of
     * work for exactly that reason.
     *
     * <p>Nothing is ever stored in this database - all three repositories are stubbed and driven from the
     * case's seeded rows - so it exists only to make the boundary genuine. The name carries a fresh
     * {@link UUID} so no two invocations can reach each other, and the default close delay discards the
     * database with its last connection, which leaves nothing behind for the next case.
     *
     * @return the boundary one invocation runs inside
     */
    private static DatasetUnitOfWork privateUnitOfWork() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:parity-" + PROGRAM + '-' + UUID.randomUUID(), "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new DatasetUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    // =================================================================================================
    // THE CONTROLLER ADAPTER.
    //
    // Constructs BillPaymentController through its own constructor and calls MAIN-PARA as a plain Java
    // object. Emphatically not MockMvc: there is no servlet container, no web application context, no
    // message converter and no HTTP status code anywhere in this path. What is asserted is the payload
    // the handler returns, which is the whole of what a stateless translation of a CICS screen produces.
    // =================================================================================================

    /**
     * Constructs the controller for one case, runs one {@code MAIN-PARA}, and records the payload.
     *
     * <p>The service underneath is the real one, over the same stubbed repositories the sixteen service
     * cases use, so a controller case that reaches {@code PROCESS-ENTER-KEY} exercises the same
     * arithmetic rather than a stand-in.
     *
     * @param invocation the case's invocation
     * @return {@code null}, meaning the recorder holds the outcome
     */
    private static UnitOutcome billPaymentControllerUnit(Invocation invocation) {
        Charset charset = invocation.charset();
        List<String> seededAccounts = rowsOf(invocation, ACCTDAT);
        List<String> seededTransactions = rowsOf(invocation, TRANSACT);

        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.datasetCharset()).thenReturn(charset);
        when(accounts.readForUpdate(anyString()))
                .thenReturn(accountReadOutcome(invocation, seededAccounts, charset));
        when(accounts.rewrite(any())).thenReturn(AccountRepository.WriteResult.written());

        CardXrefRepository crossReference = mock(CardXrefRepository.class);
        when(crossReference.readByAccountIdViaAltIndex(anyString()))
                .thenReturn(crossReferenceReadOutcome(invocation, charset));

        TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
        when(browse.readPrev()).thenReturn(highestTransactionOf(seededTransactions, charset));
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        when(transactions.write(any())).thenReturn(TransactionRepository.WriteResult.written(TRANSACT));

        BillPaymentService service = new BillPaymentService(accounts, crossReference, transactions,
                invocation.clock(), privateUnitOfWork());
        BillPaymentController controller = new BillPaymentController(service, invocation.clock());

        BillPaymentResponse payload = controller.payBill(requestOf(invocation)).screen();

        UnitOutcome.Builder recorder = invocation.recorder();
        recorder.response(observedControllerResponse(payload));
        recorder.returnCode(0);
        return null;
    }

    /**
     * The bound screen one controller case is called with.
     *
     * <p>{@code EIBCALEN = 0} is expressed as an <em>absent</em> communication area, which is what the
     * controller reads it as: {@code app/cbl/COBIL00C.cbl:107} guards on the length and {@code :111}
     * copies the area only when there is one, so a payload with no area is exactly the cold-start state
     * and is not the same thing as a payload carrying an area of all spaces.
     *
     * <p>The AID travels as a five-character token rather than as a byte, because that is the shape the
     * payload carries and {@link PfKeyResolver.AidKey} defines. {@link #aidTokenOf(String)} maps the
     * {@code DFHAID} mnemonic a case declares onto it through {@link CicsAid}'s own table, so the two
     * cannot drift apart.
     *
     * @param invocation the case's invocation
     * @return the request
     */
    private static BillPaymentRequest requestOf(Invocation invocation) {
        BillPaymentRequest request = new BillPaymentRequest();
        if (invocation.eibcalen() > 0) {
            request.setNavigationContext(commareaOf(invocation));
        }
        request.setAid(aidTokenOf(invocation.aid()));
        request.setActIdIn(invocation.mapFields().get(ACTIDIN_INPUT));
        request.setConfirm(invocation.mapFields().get(CONFIRM_INPUT));
        return request;
    }

    /**
     * The payload the controller returned, as the differ compares it.
     *
     * <p>A single send is reported for a path that painted the screen and none for a path that
     * transferred control, which is the difference the two exits actually have: {@code EXEC CICS XCTL}
     * at {@code :281-284} transfers and never returns, so the {@code SEND MAP} at {@code :295-301} is
     * not reached on that path and the {@code EXEC CICS RETURN TRANSID} at {@code :146-149} is not
     * reached either. The response's mapset and map are present only where a screen was transmitted,
     * which is what makes the two distinguishable.
     *
     * <p>Read from the payload alone, through {@link BillPaymentController#payBill(BillPaymentRequest)} -
     * the handler's own public seam. Nothing here reaches into the controller for a private view of what
     * it did, and nothing needs to: a stateless screen's entire observable is the body it returns, so a
     * payload-only expectation is not a weaker assertion than an internal one but the correct one.
     *
     * @param response the payload {@code MAIN-PARA} returned
     * @return the observed response
     */
    private static ObservedResponse observedControllerResponse(BillPaymentResponse response) {
        List<ObservedSend> sends = new ArrayList<>();
        // "Did a SEND happen?" is "does the response NAME a map?", not "is the member non-null". The
        // navigation carriers are CARDDEMO-COMMAREA items and a carrier that names nothing holds spaces
        // at its declared width, which is what NavigationContext.empty() documents and what all sixteen
        // sibling screens already emit. Keying on null read a blank carrier as a SEND.
        if (namesSomething(response.getNextMap())) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("TRNNAMEO", response.getTrnName());
            fields.put("TITLE01O", response.getTitle01());
            fields.put("CURDATEO", response.getCurDate());
            fields.put("PGMNAMEO", response.getPgmName());
            fields.put("TITLE02O", response.getTitle02());
            fields.put("CURTIMEO", response.getCurTime());
            fields.put("ACTIDINO", response.getActIdIn());
            fields.put("CURBALO", response.getCurBal());
            fields.put("CONFIRMO", response.getConfirm());
            fields.put("ERRMSGO", response.getErrMsg());
            Map<String, String> attributes = new LinkedHashMap<>();
            if (BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.equals(response.getMessageHighlight())) {
                attributes.put("ERRMSGC", ERRMSGC_GREEN);
            }
            sends.add(attributes.isEmpty()
                    ? ObservedSend.ofFields(fields)
                    : new ObservedSend(fields, attributes));
        }
        return new ObservedResponse(nameOrAbsent(response.getNextProgram()),
                nameOrAbsent(response.getNextMapset()),
                nameOrAbsent(response.getNextMap()),
                navigationOf(response.getNavigationContext()), sends,
                cursorItemOf(response.getCursorField()),
                namesSomething(response.getNextProgram())
                        ? Termination.XCTL
                        : Termination.RETURN_TRANSID);
    }

    /**
     * Whether a navigation carrier actually names a target.
     *
     * <p>{@code CDEMO-TO-PROGRAM}, {@code CDEMO-LAST-MAPSET} and {@code CDEMO-LAST-MAP} are
     * {@code PIC X} items of {@code CARDDEMO-COMMAREA}, so "names nothing" is spaces or
     * {@code LOW-VALUES} at the declared width - the COBOL's own {@code = SPACES OR LOW-VALUES} test -
     * and not a Java {@code null}. A projection that treated blank as named would report an
     * {@code XCTL} on a path that only performed {@code EXEC CICS RETURN}.
     *
     * @param carrier the carrier's image, possibly {@code null}
     * @return {@code true} when it holds a value that is neither spaces nor {@code LOW-VALUES}
     */
    private static boolean namesSomething(String carrier) {
        return carrier != null && !carrier.isEmpty()
                && !ScreenFieldImage.isSpacesOrLowValues(carrier);
    }

    /**
     * A navigation carrier as the expected-output model records it: the value where one is named, and
     * {@code null} - "absent" in a parity case - where the carrier is blank.
     *
     * @param carrier the carrier's image, possibly {@code null}
     * @return the named value, or {@code null}
     */
    private static String nameOrAbsent(String carrier) {
        return namesSomething(carrier) ? carrier : null;
    }

    /**
     * The five-character payload token for a {@code DFHAID} mnemonic.
     *
     * <p>Resolved through {@link CicsAid#mnemonicsByAid()} and then {@link PfKeyResolver#resolve(byte)},
     * so the mnemonic a case declares, the byte {@code EIBAID} would carry and the token the payload
     * carries are one chain with no transcription in it. A mnemonic the resolver does not recognise -
     * every {@code DFHPF13} and above, and the attention keys beyond {@code DFHPA2} - yields no token,
     * which the controller reads as {@code DFHNULL} and which therefore reaches the {@code WHEN OTHER}
     * arm at {@code :138}.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null} where it declared none
     * @return the token, or {@code null} for an absent or unresolvable key
     */
    private static String aidTokenOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return PfKeyResolver.resolve(entry.getKey()).map(AidKey::token).orElse(null);
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read the "
                + "same table, so this means they have drifted apart.");
    }

    // =================================================================================================
    // Case construction. Small, named builders so each of the twenty reads as a statement about the
    // source rather than as an argument list.
    // =================================================================================================

    /** The {@code DFHAID} mnemonic whose arm at {@code :126-127} performs {@code PROCESS-ENTER-KEY}. */
    private static final String AID_ENTER = "DFHENTER";

    /** The mnemonic whose arm at {@code :128-135} returns to the previous screen. */
    private static final String AID_PF3 = "DFHPF3";

    /** The mnemonic whose arm at {@code :136-137} clears the current screen. */
    private static final String AID_PF4 = "DFHPF4";

    /**
     * A key with an arm of its own nowhere in this program, which therefore reaches {@code WHEN OTHER}
     * at {@code :138-141}.
     */
    private static final String AID_NO_MATCH = "DFHPF5";

    /** {@code CDEMO-PGM-CONTEXT} on a first entry - the {@code 88 CDEMO-PGM-ENTER} value. */
    private static final String PGM_CONTEXT_ENTER = zeroFilled(
            NavigationContext.empty().pgmContext(), NavigationContext.PGM_CONTEXT_LENGTH);

    /** {@code CDEMO-PGM-CONTEXT} on a re-entry - the {@code 88 CDEMO-PGM-REENTER} value. */
    private static final String PGM_CONTEXT_REENTER = zeroFilled(
            NavigationContext.empty().withPgmReenter().pgmContext(),
            NavigationContext.PGM_CONTEXT_LENGTH);

    /** {@code ACTIDINI} filled with spaces, which is what the check at {@code :159} rejects. */
    private static final String BLANK_ACCOUNT_ID = " ".repeat(BillPaymentResponse.ACT_ID_IN_LENGTH);

    /** {@code CONFIRMI} filled with a space - the {@code WHEN SPACES} arm at {@code :182}. */
    private static final String BLANK_CONFIRM = " ".repeat(BillPaymentResponse.CONFIRM_LENGTH);

    /** {@code CURBALI} before anything is edited into it, and after {@code INITIALIZE-ALL-FIELDS}. */
    private static final String BLANK_BALANCE = " ".repeat(BillPaymentResponse.CUR_BAL_LENGTH);

    /** The {@code WHEN 'Y'} literal at {@code :174}, which sets {@code CONF-PAY-YES}. */
    private static final String CONFIRM_YES = "Y";

    /** The {@code WHEN 'n'} literal at {@code :179} - the lower-case arm, which is a distinct WHEN. */
    private static final String CONFIRM_NO_LOWER = "n";

    /** A value no {@code WHEN} matches, which reaches {@code WHEN OTHER} at {@code :185}. */
    private static final String CONFIRM_INVALID = "X";

    /**
     * The account record as {@code app/data/ASCII/acctdata.txt} row 0 stores it.
     *
     * <p>Fixture-backed rather than inline, so the happy path is seeded from the real production-shaped
     * data rather than from data invented to suit it.
     *
     * @return the input declaration
     */
    private static DatasetInput fixtureAccount() {
        return new DatasetInput(List.of(), ACCOUNT_FIXTURE, ACCOUNT_FIXTURE_ROW, ONE_ROW);
    }

    /**
     * The same account record with {@code ACCT-CURR-BAL} replaced.
     *
     * <p>Inline because no fixture row carries a ten-digit balance, a sub-cent balance or a balance below
     * zero, and those are three of the four numeric shapes this program has to be pinned against. Every
     * other span is the fixture's, so the row differs from the real data in exactly one field.
     *
     * @param balance the balance the row carries
     * @return the input declaration
     */
    private static DatasetInput accountHolding(String balance) {
        return DatasetInput.ofRows(List.of(accountImage(balance)));
    }

    /**
     * An {@code ACCTDAT} that exists and holds no row, so the keyed read reports {@code DFHRESP(NOTFND)}.
     *
     * <p>An empty dataset and an omitted one are different assertions: an omitted dataset is one the case
     * says nothing about, while an empty one positively states that the read was issued against a real
     * dataset and found nothing - which is the arm at {@code :359-364}.
     *
     * @return the input declaration
     */
    private static DatasetInput absentAccount() {
        return DatasetInput.ofEmpty(AccountRecord.RECORD_LENGTH, "CVACT01Y");
    }

    /**
     * The cross-reference row as {@code app/data/ASCII/cardxref.txt} row 48 stores it - at
     * <strong>36</strong> bytes.
     *
     * <p>The fixture is eight-for-nine faithful to its copybook and this is the ninth: {@code CVACT03Y}
     * declares {@value CardXrefRecord#RECORD_LENGTH} bytes and the fixture carries 36, because it omits
     * the trailing {@code FILLER PIC X(14)}. Every case that seeds it therefore declares the
     * {@link Normalisation#CARDXREF_FILLER_PAD_36_TO_50} normalisation, which supplies the absent span
     * once, at seed time, before anything decodes the row.
     *
     * @return the input declaration
     */
    private static DatasetInput fixtureCrossReference() {
        return new DatasetInput(List.of(), XREF_FIXTURE, XREF_FIXTURE_ROW, ONE_ROW);
    }

    /** @return a cross-reference that exists and holds no row, reaching the arm at {@code :423-428} */
    private static DatasetInput absentCrossReference() {
        return DatasetInput.ofEmpty(CardXrefRecord.RECORD_LENGTH, "CVACT03Y");
    }

    /**
     * A {@code TRANSACT} master holding one row, at the identifier the browse must find.
     *
     * @param tranId the identifier the row carries
     * @return the input declaration
     */
    private static DatasetInput masterHolding(String tranId) {
        return DatasetInput.ofRows(List.of(seededTransactionRow(tranId)));
    }

    /** @return a master that exists and holds no row, reaching the {@code ENDFILE} arm at {@code :487} */
    private static DatasetInput emptyMaster() {
        return DatasetInput.ofEmpty(TranRecord.RECORD_LENGTH, "CVTRA05Y");
    }

    /**
     * The three datasets {@code COBIL00C} touches, in the order the source names them at
     * {@code :40-42}.
     *
     * @param account        the {@code ACCTDAT} declaration
     * @param crossReference the {@code CXACAIX} declaration
     * @param master         the {@code TRANSACT} declaration
     * @return the input map
     */
    private static Map<String, DatasetInput> datasets(DatasetInput account,
                                                     DatasetInput crossReference,
                                                     DatasetInput master) {
        Map<String, DatasetInput> inputs = new LinkedHashMap<>();
        inputs.put(ACCTDAT, account);
        inputs.put(CXACAIX, crossReference);
        inputs.put(TRANSACT, master);
        return inputs;
    }

    /**
     * The seed-time normalisation the cross-reference fixture requires, and only when it is seeded.
     *
     * @param crossReference the declaration this case gave {@code CXACAIX}
     * @return the one-element list where the fixture was named, and an empty list otherwise
     */
    private static List<DatasetNormalisation> normalisationsFor(DatasetInput crossReference) {
        return crossReference.fixtureBacked()
                ? List.of(new DatasetNormalisation(CXACAIX, Normalisation.CARDXREF_FILLER_PAD_36_TO_50))
                : List.of();
    }

    /**
     * The invocation a service case is called with: a re-entry carrying the two received map items.
     *
     * @param actIdIn the value of {@code ACTIDINI}
     * @param confirm the value of {@code CONFIRMI}
     * @param forced  the repository outcomes this case forces, empty for the ordinary paths
     * @return the screen request
     */
    private static ScreenRequest enterKeyRequest(String actIdIn, String confirm,
                                                Map<RepositoryOperation, ForcedOutcome> forced) {
        Map<String, String> mapFields = new LinkedHashMap<>();
        mapFields.put(ACTIDIN_INPUT, actIdIn);
        mapFields.put(CONFIRM_INPUT, confirm);
        return new ScreenRequest(EIBCALEN_WITH_COMMAREA, AID_ENTER, PINNED_CLOCK, CHARSET_NAME,
                Map.of(NavigationContext.PGM_CONTEXT_FIELD, PGM_CONTEXT_REENTER), mapFields, forced);
    }

    /**
     * The response a service case expects: the area unchanged, the sends in order, the cursor where the
     * source left it, and the pseudo-conversation continuing.
     *
     * @param cursorItem the {@code xxxL} item {@code MOVE -1} reached, or {@code null} for none
     * @param sends      every screen the invocation is expected to send, in order
     * @return the expectation
     */
    private static ExpectedResponse enterKeyResponse(String cursorItem, List<ScreenSend> sends) {
        return new ExpectedResponse(null, null, null,
                navigationOf(NavigationContext.empty().withPgmReenter()), sends, cursorItem,
                Termination.RETURN_TRANSID);
    }

    /**
     * One expected screen, carrying the four items this program's own paragraphs write.
     *
     * @param actIdIn {@code ACTIDINO}, at its declared eleven characters
     * @param curBal  {@code CURBALO}, at its declared fourteen
     * @param confirm {@code CONFIRMO}, at its declared one
     * @param message the 80-byte {@code WS-MESSAGE} as it reaches the 78-byte {@code ERRMSGO}
     * @return the expectation
     */
    private static ScreenSend send(String actIdIn, String curBal, String confirm, String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ACTIDINO", actIdIn);
        fields.put("CURBALO", curBal);
        fields.put("CONFIRMO", confirm);
        fields.put("ERRMSGO", picX(message, BillPaymentResponse.ERR_MSG_LENGTH));
        return new ScreenSend(fields, Map.of());
    }

    /**
     * The same screen with {@code ERRMSGC} coloured, which only the {@code WRITE} success arm does.
     *
     * @param actIdIn {@code ACTIDINO}
     * @param curBal  {@code CURBALO}
     * @param confirm {@code CONFIRMO}
     * @param message the confirmation text
     * @return the expectation
     */
    private static ScreenSend greenSend(String actIdIn, String curBal, String confirm,
                                       String message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ACTIDINO", actIdIn);
        fields.put("CURBALO", curBal);
        fields.put("CONFIRMO", confirm);
        fields.put("ERRMSGO", picX(message, BillPaymentResponse.ERR_MSG_LENGTH));
        return new ScreenSend(fields, Map.of("ERRMSGC", ERRMSGC_GREEN));
    }

    /**
     * The confirmation {@code :527-531} strings together, as it reaches the screen.
     *
     * <p>{@code STRING 'Payment successful. ' DELIMITED BY SIZE ' Your Transaction ID is ' DELIMITED BY
     * SIZE TRAN-ID DELIMITED BY SPACE '.' DELIMITED BY SIZE INTO WS-MESSAGE}. Two of the three
     * delimiters are {@code SIZE}, so both literals contribute every character they have - including the
     * trailing space of the first and the leading space of the second, which is why two spaces separate
     * the sentence from the clause. The identifier is delimited by {@code SPACE} and carries none, so all
     * sixteen characters contribute.
     *
     * @param tranId the identifier of the transaction just added
     * @return the text, before the 80-to-78 truncation the send performs
     */
    private static String successMessage(String tranId) {
        return BillPaymentService.SUCCESS_PREFIX + BillPaymentService.SUCCESS_INFIX + tranId
                + BillPaymentService.SUCCESS_SUFFIX;
    }

    /**
     * A record expectation pinning both the named fields and the complete image.
     *
     * <p>Both, deliberately. The named fields make a failure say {@code ACCT-CURR-BAL} rather than
     * "offset 12"; the image accounts for every remaining byte, which is what proves the total width and
     * therefore that every {@code FILLER} span was emitted. An expectation that pinned only some fields
     * would leave the rest of the record able to be wrong while the diff count stayed at zero.
     *
     * @param dataset  the binding key
     * @param rowIndex the zero-based write position or row index
     * @param fields   the copybook field names and their expected values
     * @param image    the complete expected record image
     * @return the expectation
     */
    private static ExpectedRecord record(String dataset, int rowIndex, Map<String, String> fields,
                                        String image) {
        return new ExpectedRecord(dataset, rowIndex, fields, image);
    }

    /**
     * A record expectation pinning the complete image alone, for a row whose subject is its bytes.
     *
     * @param dataset  the binding key
     * @param rowIndex the zero-based write position or row index
     * @param image    the complete expected record image
     * @return the expectation
     */
    private static ExpectedRecord image(String dataset, int rowIndex, String image) {
        return new ExpectedRecord(dataset, rowIndex, Map.of(), image);
    }

    /**
     * The dataset-level expectation: how many rows a channel held, at what width.
     *
     * <p>Additional to the row expectations rather than a replacement for them, and it is the only way
     * to assert a channel that holds <em>no</em> row - "the master gained nothing" cannot be stated by
     * any row expectation, because there is no row to address.
     *
     * @param dataset the binding key
     * @param channel {@code WRITES} for what the unit wrote, {@code FINAL_STATE} for what remained
     * @param rows    the expected row count
     * @param width   the record width the layout declares
     * @return the expectation
     */
    private static ExpectedDataset channel(String dataset, DatasetChannel channel, int rows,
                                          int width) {
        return new ExpectedDataset(dataset, channel, rows, width);
    }

    /** @return the field map naming the balance a rewritten account row must carry */
    private static Map<String, String> balanceField(String balance) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(AccountRecord.ACCT_ID_NAME, ACCOUNT_ID);
        fields.put(AccountRecord.ACCT_CURR_BAL_NAME, balance);
        fields.put(AccountRecord.ACCT_EXPIRAION_DATE_NAME, EXPIRATION_DATE);
        return fields;
    }

    /** @return the field map naming the identifier and the amount an added transaction must carry */
    private static Map<String, String> transactionFields(String tranId, String amount) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TranRecord.TRAN_ID.name(), tranId);
        fields.put(TranRecord.TRAN_AMT.name(), amount);
        fields.put(TranRecord.TRAN_CARD_NUM.name(), CARD_NUMBER);
        return fields;
    }

    /**
     * Binds a case to the service adapter.
     *
     * @param parityCase the case
     * @return the scenario
     */
    private static ParityScenario serviceCase(ParityCase parityCase) {
        return new ParityScenario(parityCase, UnitKind.SERVICE,
                COBIL00CParityTest::billPaymentServiceUnit, "BillPaymentService.processEnterKey");
    }

    /**
     * Binds a case to the controller adapter.
     *
     * @param parityCase the case
     * @return the scenario
     */
    private static ParityScenario controllerCase(ParityCase parityCase) {
        return new ParityScenario(parityCase, UnitKind.CONTROLLER_POJO,
                COBIL00CParityTest::billPaymentControllerUnit,
                "BillPaymentController.payBill (MAIN-PARA)");
    }

    // =================================================================================================
    // THE TWENTY CASES.
    //
    // case01-case06  the arithmetic: an exact result, a truncating store, the high-order truncation of
    //                the amount, the smallest balance that pays, and the two shapes the <= ZEROS guard
    //                rejects - a balance below zero and a balance of exactly zero.
    // case07-case12  every outcome the four file operations can report, including the four WHEN OTHER
    //                arms no seeded row can reach.
    // case13-case16  the four arms of EVALUATE CONFIRMI and the empty-identifier check before it.
    // case17-case20  the controller: the cold start, the first-entry paint, the ENTER re-entry and PF3.
    //
    // ONE ADAPTATION, RECORDED SO THE ALLOCATION CAN BE AUDITED. A generic allocation for a payment
    // screen would ask for a blank-amount case and a non-numeric-amount case. Neither exists here,
    // because THIS SCREEN HAS NO AMOUNT FIELD: app/cpy-bms/COBIL00.CPY declares ten input items and the
    // amount is not among them - TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME, ACTIDIN, CURBAL,
    // CONFIRM, ERRMSG. The sum paid is never typed; :224 derives it from the balance the program just
    // read, which is exactly why the high-order MOVE of case03 is this program's headline numeric trap
    // instead of an input edit. There is therefore no NUMVAL or NUMVAL-C call anywhere in COBIL00C to
    // pin - CORPT00C and COTRN02C own those.
    //
    // The two user-entered fields are ACTIDINI and CONFIRMI, and both are edited: the blank test at
    // :159-164 and the four-armed EVALUATE at :175-190. case13 through case16 drive all five outcomes
    // between them, which is the faithful equivalent of the generic pair and covers strictly more.
    // =================================================================================================

    /**
     * {@code case01} - the whole payment, seeded entirely from the shipped fixtures. {@code SERVICE}.
     *
     * <p>{@code app/data/ASCII/acctdata.txt} row 0 carries account {@code 00000000001} at
     * {@code 194.00}, and {@code app/data/ASCII/cardxref.txt} row 48 is the one cross-reference row
     * whose {@code XREF-ACCT-ID} is that account, so this case is production-shaped data end to end.
     *
     * <p>The sequence at {@code :210-235} in order: read the cross-reference for the card number, move
     * {@code HIGH-VALUES} to the identifier, browse backwards for the highest one, increment it, assemble
     * the 350-byte record, add it, <em>then</em> compute the new balance and only then rewrite the
     * account. The order matters and is asserted by the write channels: a translation that rewrote the
     * balance before adding the transaction would have done something different.
     *
     * <p>{@code 194.00} is under one billion, so {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at {@code :224}
     * loses nothing and {@code :234} computes exactly zero. Two sends, because the {@code WRITE}'s
     * success arm sends at {@code :532} and {@code :242} sends again unconditionally - an inherited quirk
     * of the program, preserved.
     *
     * @return the scenario
     */
    private static ParityScenario case01() {
        DatasetInput crossReference = fixtureCrossReference();
        String confirmation = successMessage(NEXT_TRAN_ID);
        return serviceCase(new ParityCase(PROGRAM, "case01",
                "The complete bill payment on fixture data. app/data/ASCII/acctdata.txt row 0 holds "
                        + "account 00000000001 at 194.00 and app/data/ASCII/cardxref.txt row 48 is its "
                        + "cross-reference row, padded from 36 to 50 bytes at seed time. CONFIRMI is 'Y', "
                        + "so :176 sets CONF-PAY-YES and :211-235 run in full: the card number comes from "
                        + "CXACAIX, the identifier is the master's highest plus one, the 350-byte "
                        + "transaction is added first, and only then does :234 compute "
                        + "194.00 - 194.00 = 0.00 and :235 rewrite the account. Both write channels are "
                        + "pinned at full width, so the FILLER X(20) of CVTRA05Y and the FILLER X(178) of "
                        + "CVACT01Y are proved to have been emitted as spaces.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation))),
                List.of(record(TRANSACT, 0, transactionFields(NEXT_TRAN_ID, FIXTURE_BALANCE),
                                transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        image(TRANSACT, 1, transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE,
                                CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 1, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 2, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case02} - the store truncates downwards, and a nearest-value rule would not.
     * {@code SERVICE}.
     *
     * <p>The stored balance is {@code 194.007}. {@code ACCT-CURR-BAL} is {@code PIC S9(10)V99}, so the
     * two fraction digits are all it has and the remaining seven thousandths are discarded - and
     * discarded <em>downwards</em>, because the keyword {@code ROUNDED} appears exactly zero times in all
     * 28 programs, which makes truncation the only faithful rule. The stored balance is therefore
     * {@code 194.00}, the payment is {@code 194.00} and the debited balance is {@code 0.00}.
     *
     * <p>The sharpness is the point: seven thousandths is more than half a cent, so a rule that rounded
     * to the nearest value would have produced {@code 194.01} and would differ in three separate
     * places - the zoned image of the stored balance, the zoned image of {@code TRAN-AMT}, and the
     * edited {@code CURBALI}. {@link #theTruncationCaseIsSharpEnoughToRejectANearestValueRule()} states
     * that independently.
     *
     * @return the scenario
     */
    private static ParityScenario case02() {
        DatasetInput crossReference = fixtureCrossReference();
        String confirmation = successMessage(NEXT_TRAN_ID);
        return serviceCase(new ParityCase(PROGRAM, "case02",
                "A balance of 194.007 reaching PIC S9(10)V99. The picture holds two fraction digits, so "
                        + "the remaining seven thousandths are discarded downwards - ROUNDED appears zero "
                        + "times in any of the 28 programs, so RoundingMode.DOWN is the only faithful "
                        + "choice and CobolDecimal is the only place that names it. The stored balance is "
                        + "194.00, so :224 moves 194.00 into TRAN-AMT and :234 computes 0.00. A "
                        + "nearest-value rule would have carried to 194.01 and would differ in the "
                        + "stored balance, the transaction amount and the edited CURBALI alike.",
                UnitKind.SERVICE,
                datasets(accountHolding(SUB_CENT_BALANCE), crossReference,
                        masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation))),
                List.of(record(TRANSACT, 0, transactionFields(NEXT_TRAN_ID, FIXTURE_BALANCE),
                                transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        image(TRANSACT, 1, transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE,
                                CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 1, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 2, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case03} - the headline trap: the amount is a digit narrower than the balance.
     * {@code SERVICE}.
     *
     * <p>The stored balance is {@code 9999999999.99}, the largest {@code PIC S9(10)V99} can hold.
     * {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} at {@code :224} moves it into {@code PIC S9(09)V99}, and a
     * numeric receiver is aligned on its implied decimal point - so the digit that does not fit is the
     * <strong>high-order</strong> one and the payment is {@code 999999999.99}, a billion short of the
     * balance. {@code :234} therefore computes {@code 9000000000.00} and the account is left holding it.
     *
     * <p>This is the one case in the twenty where the payment does not settle the account, and it exists
     * because a translation that used plain assignment for that {@code MOVE} - or that widened
     * {@code TRAN-AMT} to match its sender - would compute zero here and be wrong by nine billion while
     * passing every other case in the set.
     *
     * @return the scenario
     */
    private static ParityScenario case03() {
        DatasetInput crossReference = fixtureCrossReference();
        String confirmation = successMessage(NEXT_TRAN_ID);
        return serviceCase(new ParityCase(PROGRAM, "case03",
                "The maximum representable balance, 9999999999.99, against TRAN-AMT's PIC S9(09)V99. "
                        + ":224 aligns the receiver on its implied decimal point and discards the "
                        + "high-order digit, so the payment is 999999999.99 rather than the whole "
                        + "balance, and :234 computes 9000000000.00 - the discarded digit, times one "
                        + "billion. The receiver of that COMPUTE is itself PIC S9(10)V99 and the "
                        + "statement carries no ON SIZE ERROR, so the result fits exactly and no second "
                        + "truncation occurs. A translation that treated the cross-width MOVE as a Java "
                        + "assignment would compute zero here.",
                UnitKind.SERVICE,
                datasets(accountHolding(MAX_BALANCE), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation))),
                List.of(record(TRANSACT, 0, transactionFields(NEXT_TRAN_ID, MAX_PAYMENT),
                                transactionImage(NEXT_TRAN_ID, MAX_PAYMENT, CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(MAX_DEBITED_BALANCE),
                                accountImage(MAX_DEBITED_BALANCE))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        image(TRANSACT, 1, transactionImage(NEXT_TRAN_ID, MAX_PAYMENT, CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(MAX_DEBITED_BALANCE),
                                accountImage(MAX_DEBITED_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 1, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 2, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case04} - the smallest balance that pays, on a master that holds nothing.
     * {@code SERVICE}.
     *
     * <p>Two properties in one case, because they belong at the same extreme. The balance is
     * {@code 0.01}: one cent is the smallest value the {@code ACCT-CURR-BAL <= ZEROS} guard at
     * {@code :198} lets through, so this is the boundary of that condition from the paying side, and it
     * proves the comparison is against zero rather than against a rounded-to-nothing scale-2 zero.
     *
     * <p>The master is declared empty, so the {@code READPREV} reports {@code DFHRESP(ENDFILE)} and the
     * arm at {@code :487-488} moves zeros into the record identification field - no flag, no message, no
     * send. {@code :217} then increments from zero, so the very first payment on an empty master is
     * transaction one.
     *
     * @return the scenario
     */
    private static ParityScenario case04() {
        DatasetInput crossReference = fixtureCrossReference();
        String confirmation = successMessage(FIRST_TRAN_ID);
        return serviceCase(new ParityCase(PROGRAM, "case04",
                "A balance of one cent against an empty TRANSACT master. 0.01 is the smallest value the "
                        + "ACCT-CURR-BAL <= ZEROS guard at :198 admits, so this is that condition's "
                        + "boundary from the paying side. The empty master makes the READPREV report "
                        + "DFHRESP(ENDFILE), whose arm at :487-488 moves ZEROS to TRAN-ID with no flag, "
                        + "no message and no send, so :217 increments from zero and the payment is "
                        + "transaction 0000000000000001. The master afterwards holds exactly that one "
                        + "row.",
                UnitKind.SERVICE,
                datasets(accountHolding(MINIMUM_BALANCE), crossReference, emptyMaster()),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation))),
                List.of(record(TRANSACT, 0, transactionFields(FIRST_TRAN_ID, MINIMUM_BALANCE),
                                transactionImage(FIRST_TRAN_ID, MINIMUM_BALANCE, CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                List.of(image(TRANSACT, 0, transactionImage(FIRST_TRAN_ID, MINIMUM_BALANCE,
                                CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 1, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case05} - a balance below zero: the sign, its overpunch, and the guard that rejects it.
     * {@code SERVICE}.
     *
     * <p>The stored balance is {@code -1234.56}, which the {@code ACCT-CURR-BAL <= ZEROS} guard at
     * {@code :198} rejects with {@code 'You have nothing to pay...'}, the cursor at
     * {@code ACTIDINL} and one send. Nothing is written anywhere, and the account is left holding exactly
     * the bytes it started with - including the negative overpunch on the low-order digit of
     * {@code ACCT-CURR-BAL}, which the pinned 300-byte image asserts.
     *
     * <p>{@code CURBALI} nevertheless comes back holding {@code -0000001234.56}, and that is not an
     * oversight in the expectation. The two moves at {@code :193-194} - {@code MOVE ACCT-CURR-BAL TO
     * WS-CURR-BAL} and then to {@code CURBALI} - sit <em>inside</em> the {@code :169} block and
     * <em>above</em> the guard, so they run on every path that read a record, this refusal included. The
     * screen therefore displays the balance it is declining to pay, sign and all, which is also where the
     * edited picture's {@code -} sign position is exercised - every other case in the set leaves it a
     * plus.
     *
     * <p>Why the sign is asserted <em>here</em> rather than on a negative result: a negative result is
     * <strong>unreachable</strong> in this program, and saying so is more useful than inventing a path to
     * it. {@code :224} makes the payment equal to the truncation of the balance, so the payment can never
     * exceed it and {@code :234} can never go below zero from a positive balance - while a balance that
     * is already below zero is stopped by this guard before any payment is assembled. The one place a
     * negative monetary span reaches a record in {@code COBIL00C} is therefore the stored balance it
     * refuses to pay, and that is what this case pins.
     *
     * @return the scenario
     */
    private static ParityScenario case05() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case05",
                "A stored balance of -1234.56. The compound condition at :198-199 is true - the balance "
                        + "is at or below zero and ACTIDINI is neither spaces nor low-values - so :200-204 "
                        + "sets the error flag, moves 'You have nothing to pay...', positions the cursor "
                        + "at ACTIDINL and sends once. Nothing is written and the account keeps every "
                        + "byte it had, negative overpunch included: the low-order digit of "
                        + "ACCT-CURR-BAL carries the sign, because no copybook in app/cpy declares COMP-3 "
                        + "and every stored monetary span is therefore zoned DISPLAY. A negative RESULT "
                        + "is unreachable in this program, since :224 makes the payment equal to the "
                        + "truncation of the balance and can never exceed it, so this guard is the only "
                        + "place a negative monetary span reaches a record here.",
                UnitKind.SERVICE,
                datasets(accountHolding(NEGATIVE_BALANCE), crossReference,
                        masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, editedBalance(NEGATIVE_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_NOTHING_TO_PAY))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(NEGATIVE_BALANCE),
                                accountImage(NEGATIVE_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case06} - a balance of exactly zero: the other half of the guard, and the zero payment.
     * {@code SERVICE}.
     *
     * <p>{@code IF ACCT-CURR-BAL <= ZEROS} is an inclusive comparison, so a balance of exactly
     * {@code 0.00} is rejected as surely as a negative one - which means a payment of zero cannot be
     * assembled at all, and "no transaction is written for a settled account" is the behaviour rather
     * than an omission.
     *
     * <p>{@code CURBALI} comes back as {@code +0000000000.00} for the reason {@code case05} sets out: the
     * stale-balance moves at {@code :193-194} run above the guard, so the screen shows the settled
     * balance while refusing to pay it. Here it also pins the edited picture's other sign position - the
     * {@code +} that a value of exactly zero produces, since the mask emits a plus for zero and above.
     *
     * <p>The comparison is asserted here because it is easy to get wrong in Java for a reason that has
     * nothing to do with COBOL: {@link BigDecimal#equals(Object)} is scale-sensitive, so a scale-2 zero
     * is not equal to {@link BigDecimal#ZERO}, and a translation written with {@code equals} would
     * invert this branch and let a zero-balance account pay.
     *
     * @return the scenario
     */
    private static ParityScenario case06() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case06",
                "A stored balance of exactly 0.00. :198 compares with <=, so zero is rejected on the "
                        + "same arm as a negative balance and no payment of zero can be assembled: the "
                        + "message is 'You have nothing to pay...', the cursor goes to ACTIDINL, one "
                        + "screen is sent and neither dataset changes. The comparison has to be on the "
                        + "value rather than on the representation - a scale-2 zero is not equal to "
                        + "BigDecimal.ZERO - which is why a translation using equals rather than a "
                        + "signum comparison would invert this branch.",
                UnitKind.SERVICE,
                datasets(accountHolding(ZERO_BALANCE), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, editedBalance(ZERO_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_NOTHING_TO_PAY))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(ZERO_BALANCE), accountImage(ZERO_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case07} - the account is not there. {@code SERVICE}.
     *
     * <p>{@code ACCTDAT} is declared empty, so the keyed read for update at {@code :345-354} reports
     * {@code DFHRESP(NOTFND)} and the arm at {@code :359-364} rejects with
     * {@code 'Account ID NOT found...'}, the cursor at {@code ACTIDINL} and one send.
     *
     * <p>Exactly one send, and that is the interesting part. The failure is raised <em>inside</em> the
     * guard at {@code :169}, so the two guards that follow at {@code :197} and {@code :208} both find the
     * flag on and neither runs - no balance check, no payment, no second send. Compare {@code case08},
     * where the failure is raised inside the {@code :208} block and the payment therefore proceeds
     * anyway.
     *
     * @return the scenario
     */
    private static ParityScenario case07() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case07",
                "An ACCTDAT that exists and holds no row, so the keyed read for update at :345-354 "
                        + "reports DFHRESP(NOTFND) and :359-364 moves 'Account ID NOT found...', "
                        + "positions the cursor at ACTIDINL and sends. The failure is raised inside the "
                        + ":169 guard, so :197 and :208 both find the error flag on and neither runs: one "
                        + "send, no payment, no write, and no display line - the NOTFND arm has no "
                        + "DISPLAY, unlike WHEN OTHER.",
                UnitKind.SERVICE,
                datasets(absentAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, BLANK_BALANCE, CONFIRM_YES,
                                BillPaymentService.MSG_ACCOUNT_ID_NOT_FOUND))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 0,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case08} - the cross-reference is not there, and the payment goes ahead anyway.
     * {@code SERVICE}.
     *
     * <p>This is an inherited defect, preserved exactly. {@code READ-CXACAIX-FILE} is performed at
     * {@code :211}, <em>inside</em> the {@code IF NOT ERR-FLG-ON} block that opened at {@code :208}, and
     * there is <strong>no further test of the flag between {@code :211} and {@code :235}</strong>. So the
     * {@code DFHRESP(NOTFND)} arm at {@code :423-428} sets the flag, moves
     * {@code 'Account ID NOT found...'} and sends - and then {@code :212} onwards runs regardless: the
     * transaction is assembled and added, the balance is computed and the account is rewritten.
     *
     * <p>The consequence is visible in the stored bytes. {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM} at
     * {@code :225} moves a record area no read ever filled, so the sixteen bytes of
     * {@code TRAN-CARD-NUM} are {@code LOW-VALUES} rather than a card number, and the pinned 350-byte
     * image says so. Three sends: the rejection, the {@code WRITE}'s own success arm at {@code :532}, and
     * the unconditional one at {@code :242}.
     *
     * <p>Fixing this would be a behaviour change and is not this migration's business. Pinning it is: a
     * translation that added a guard here would produce a cleaner program and a failing case.
     *
     * @return the scenario
     */
    private static ParityScenario case08() {
        String blankCard = lowValues(CardXrefRecord.XREF_CARD_NUM_LENGTH);
        String confirmation = successMessage(NEXT_TRAN_ID);
        Map<String, String> written = new LinkedHashMap<>();
        written.put(TranRecord.TRAN_ID.name(), NEXT_TRAN_ID);
        written.put(TranRecord.TRAN_AMT.name(), FIXTURE_BALANCE);
        written.put(TranRecord.TRAN_CARD_NUM.name(), blankCard);
        return serviceCase(new ParityCase(PROGRAM, "case08",
                "A CXACAIX that exists and holds no row. The alternate-index read at :410-418 reports "
                        + "DFHRESP(NOTFND) and :423-428 rejects - but the read is performed at :211, "
                        + "inside the :208 block, and nothing between :211 and :235 tests the flag again, "
                        + "so the payment proceeds on the rejected path. The transaction is added with "
                        + "TRAN-CARD-NUM at LOW-VALUES, because :225 moves a record area no read ever "
                        + "filled, and the balance is still debited and rewritten. Three sends: the "
                        + "rejection, the WRITE's success arm at :532, and :242. An inherited defect, "
                        + "preserved rather than repaired.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), absentCrossReference(), masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, editedBalance(FIXTURE_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_ACCOUNT_ID_NOT_FOUND),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation))),
                List.of(record(TRANSACT, 0, written,
                                transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, blankCard)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        image(TRANSACT, 1, transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, blankCard)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                0, List.of(), List.of(),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 1, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 2, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case09} - the transaction is refused, and the balance is debited anyway. {@code SERVICE}.
     *
     * <p>A forced {@code WHEN OTHER} on the {@code WRITE}, which no arrangement of seeded rows can
     * produce: the arm at {@code :540-546} exists for a genuine I/O refusal - a closed file, an invalid
     * request, a length error - and forcing the outcome is the only way to drive it.
     *
     * <p>Two behaviours are pinned together. The arm displays before it rejects, so a line reaches
     * {@code SYSOUT}: {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at {@code :541}, with
     * {@code WS-RESP-CD} unreported because this refusal has no single CICS counterpart. And - the same
     * missing guard as {@code case08} - {@code :234} and {@code :235} still run, so the account is
     * debited for a transaction that was never stored. The writes channels say exactly that: nothing on
     * {@code TRANSACT}, one row on {@code ACCTDAT}.
     *
     * @return the scenario
     */
    private static ParityScenario case09() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case09",
                "A forced WHEN OTHER on the WRITE at :512-520, which no seeded row can produce. The arm "
                        + "at :540-546 displays 'RESP:' and 'REAS:' first and then moves 'Unable to Add "
                        + "Bill pay Transaction...', so one line reaches SYSOUT with WS-RESP-CD "
                        + "unreported. Nothing lands on TRANSACT - and :234 and :235 still run, because "
                        + "nothing between :211 and :235 re-tests the error flag, so the account is "
                        + "debited for a transaction that was never stored. Two sends: the rejection and "
                        + ":242.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES,
                        Map.of(RepositoryOperation.WRITE,
                                new ForcedOutcome(FileStatus.Outcome.OTHER, null, null))),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, editedBalance(FIXTURE_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_UNABLE_TO_ADD_TRANSACTION),
                        send(ACCOUNT_ID, editedBalance(FIXTURE_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_UNABLE_TO_ADD_TRANSACTION))),
                List.of(record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                        accountImage(SETTLED_BALANCE))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                0,
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                        UNREPORTED_RESP_DISPLAY_LINE)),
                normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case10} - the identifier is already taken. {@code SERVICE}.
     *
     * <p>{@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)} share one body at {@code :533-539}: two
     * {@code WHEN} clauses, no statement between them, so either condition falls into the same
     * rejection. That is why one {@code DUPLICATE} outcome reaches it, and why the arm carries
     * <strong>no</strong> {@code DISPLAY} - unlike the {@code WHEN OTHER} beneath it, which does. The
     * absence of a display line is asserted by declaring none.
     *
     * @return the scenario
     */
    private static ParityScenario case10() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case10",
                "A forced duplicate on the WRITE. DFHRESP(DUPKEY) and DFHRESP(DUPREC) are two WHEN "
                        + "clauses over one body at :533-539, so either reaches 'Tran ID already "
                        + "exist...' with the cursor at ACTIDINL. The arm carries no DISPLAY, so no line "
                        + "is emitted - which is what distinguishes it from the WHEN OTHER below it and "
                        + "is asserted by expecting none. As on every rejected path inside the :208 "
                        + "block, the balance is still debited.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES,
                        Map.of(RepositoryOperation.WRITE,
                                new ForcedOutcome(FileStatus.Outcome.DUPLICATE, null, null))),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, editedBalance(FIXTURE_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_TRAN_ID_ALREADY_EXIST),
                        send(ACCOUNT_ID, editedBalance(FIXTURE_BALANCE), CONFIRM_YES,
                                BillPaymentService.MSG_TRAN_ID_ALREADY_EXIST))),
                List.of(record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                        accountImage(SETTLED_BALANCE))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(SETTLED_BALANCE),
                                accountImage(SETTLED_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 1, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case11} - the rewrite is refused after the transaction has already landed.
     * {@code SERVICE}.
     *
     * <p>A forced {@code WHEN OTHER} on the {@code REWRITE} at {@code :396-402}. The transaction was
     * added successfully first, so the master keeps it while the account keeps its old balance - the two
     * halves of the payment end up out of step, which is exactly what the ordering at {@code :233-235}
     * makes possible and what a case has to be able to state.
     *
     * <p>The colour is the subtle part. {@code :526} moved {@code DFHGREEN} into {@code ERRMSGC} on the
     * {@code WRITE}'s success arm, and nothing in this program ever moves anything else there - the
     * first-entry {@code MOVE LOW-VALUES TO COBIL0AO} at {@code :114} is the only thing that clears the
     * output map, and this path is a re-entry. So the failure message that follows is transmitted with
     * the confirmation's green attribute still set, on all three sends. Preserved, not tidied.
     *
     * @return the scenario
     */
    private static ParityScenario case11() {
        DatasetInput crossReference = fixtureCrossReference();
        String confirmation = successMessage(NEXT_TRAN_ID);
        return serviceCase(new ParityCase(PROGRAM, "case11",
                "A forced WHEN OTHER on the REWRITE at :379-385. The transaction is added first at "
                        + ":233, so it stays on the master while the account keeps its old 194.00 - the "
                        + "two halves of the payment end up out of step, which is what the :233-235 "
                        + "ordering allows. :397 displays the response codes and :399 moves 'Unable to "
                        + "Update Account...'. All three sends carry ERRMSGC at DFHGREEN, because :526 "
                        + "set it on the WRITE's success arm and nothing in this program ever clears it "
                        + "on a re-entry - the failure message is transmitted in the confirmation's "
                        + "colour.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES,
                        Map.of(RepositoryOperation.REWRITE,
                                new ForcedOutcome(FileStatus.Outcome.OTHER, null, null))),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, confirmation),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM,
                                BillPaymentService.MSG_UNABLE_TO_UPDATE_ACCOUNT),
                        greenSend(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM,
                                BillPaymentService.MSG_UNABLE_TO_UPDATE_ACCOUNT))),
                List.of(record(TRANSACT, 0, transactionFields(NEXT_TRAN_ID, FIXTURE_BALANCE),
                        transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, CARD_NUMBER))),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        image(TRANSACT, 1, transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE,
                                CARD_NUMBER)),
                        record(ACCTDAT, 0, balanceField(FIXTURE_BALANCE),
                                accountImage(FIXTURE_BALANCE))),
                0,
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                        UNREPORTED_RESP_DISPLAY_LINE)),
                normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 1, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 2, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case12} - the account read is refused. {@code SERVICE}.
     *
     * <p>A forced {@code WHEN OTHER} on the read for update at {@code :365-371}: it displays the response
     * codes and rejects with {@code 'Unable to lookup Account...'}, which is a different literal from the
     * {@code NOTFND} arm's above it and is asserted as such.
     *
     * <p>The account row is seeded and left untouched, which is the assertion the empty-dataset shape of
     * {@code case07} cannot make: a refused read means the record is still there, unchanged, and the
     * pinned 300-byte image proves it. One send, because the failure is inside the {@code :169} guard.
     *
     * @return the scenario
     */
    private static ParityScenario case12() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case12",
                "A forced WHEN OTHER on the keyed read for update. :366 displays 'RESP:' and 'REAS:' and "
                        + ":368 moves 'Unable to lookup Account...' - a different literal from the NOTFND "
                        + "arm's 'Account ID NOT found...' two lines above it. The account row is seeded "
                        + "and stays exactly as seeded, which an empty dataset could not assert, and the "
                        + "failure is raised inside the :169 guard so there is one send and no payment.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_YES,
                        Map.of(RepositoryOperation.READ_FOR_UPDATE,
                                new ForcedOutcome(FileStatus.Outcome.OTHER, null, null))),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(ACCOUNT_ID, BLANK_BALANCE, CONFIRM_YES,
                                BillPaymentService.MSG_UNABLE_TO_LOOKUP_ACCOUNT))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(FIXTURE_BALANCE),
                                accountImage(FIXTURE_BALANCE))),
                0,
                List.of(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                        UNREPORTED_RESP_DISPLAY_LINE)),
                normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case13} - no account number was typed. {@code SERVICE}.
     *
     * <p>The first arm of the ordered {@code EVALUATE TRUE} at {@code :158-167}:
     * {@code WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES} rejects with
     * {@code 'Acct ID can NOT be empty...'}, positions the cursor at {@code ACTIDINL} and sends.
     *
     * <p>Nothing after it runs at all. The flag is set before the {@code :169} guard is reached, so
     * {@code :170-194} never execute - which means the balance is never even edited into
     * {@code CURBALI}, and the screen goes back with that field exactly as the terminal sent it. That
     * distinguishes this path from every other rejection in the set, all of which pass through
     * {@code :193-194} first.
     *
     * @return the scenario
     */
    private static ParityScenario case13() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case13",
                "ACTIDINI arrives as eleven spaces, which the first arm of the ordered EVALUATE TRUE at "
                        + ":158-167 rejects with 'Acct ID can NOT be empty...' and the cursor at "
                        + "ACTIDINL. The flag is set before the :169 guard, so :170-194 never run and "
                        + "CURBALI is returned exactly as it arrived - unlike every other rejection here, "
                        + "which passes through :193-194 first. Neither dataset is touched and no line is "
                        + "displayed.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(BLANK_ACCOUNT_ID, BLANK_CONFIRM, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM,
                                BillPaymentService.MSG_ACCT_ID_EMPTY))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(FIXTURE_BALANCE),
                                accountImage(FIXTURE_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case14} - the confirmation is neither yes nor no. {@code SERVICE}.
     *
     * <p>{@code WHEN OTHER} of the ordered {@code EVALUATE CONFIRMI} at {@code :185-190}. The four
     * preceding {@code WHEN} clauses name {@code 'Y'}, {@code 'y'}, {@code 'N'}, {@code 'n'},
     * {@code SPACES} and {@code LOW-VALUES}, and anything else falls here:
     * {@code 'Invalid value. Valid values are (Y/N)...'} with the cursor at {@code CONFIRML} rather than
     * at {@code ACTIDINL}, because it is the confirmation field that is wrong.
     *
     * <p>No file is read on this arm, so {@code :193-194} edit the balance out of an untouched record
     * area - the stale-balance quirk - and the payment never begins.
     *
     * @return the scenario
     */
    private static ParityScenario case14() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case14",
                "CONFIRMI arrives as 'X', which reaches WHEN OTHER of the ordered EVALUATE at :185-190: "
                        + "'Invalid value. Valid values are (Y/N)...' with the cursor at CONFIRML, not "
                        + "ACTIDINL, because it is the confirmation that is wrong. No file is read on "
                        + "this arm, so the send carries CURBALI as it arrived and neither dataset "
                        + "changes.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_INVALID, Map.of()),
                enterKeyResponse(CURSOR_CONFIRM, List.of(
                        send(ACCOUNT_ID, BLANK_BALANCE, CONFIRM_INVALID,
                                BillPaymentService.MSG_INVALID_CONFIRM_VALUE))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(FIXTURE_BALANCE),
                                accountImage(FIXTURE_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case15} - the account is looked up and the user is asked to confirm. {@code SERVICE}.
     *
     * <p>{@code WHEN SPACES} at {@code :182-184} performs the read but does <strong>not</strong> set
     * {@code CONF-PAY-YES}, so the balance is fetched and edited into {@code CURBALI} - this is the one
     * path where the {@code :193-194} move does what it looks like it does - and then {@code :237-239}
     * prompts with {@code 'Confirm to make a bill payment...'} and the cursor at {@code CONFIRML}.
     *
     * <p>Note what is <em>absent</em>: this arm sets no error flag. It is a prompt, not a rejection, and
     * the difference is observable - the flag being off is why {@code :242} reaches its send at all.
     *
     * @return the scenario
     */
    private static ParityScenario case15() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case15",
                "CONFIRMI arrives as a space, which is WHEN SPACES at :182-184: the account is read but "
                        + "CONF-PAY-YES is not set, so :193-194 edit the real balance into CURBALI as "
                        + "+0000000194.00 and :237-239 prompt with 'Confirm to make a bill payment...' "
                        + "and the cursor at CONFIRML. This arm sets no error flag - it is a prompt, not "
                        + "a rejection - which is precisely why :242 reaches its send. No payment is "
                        + "assembled and neither dataset changes.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, BLANK_CONFIRM, Map.of()),
                enterKeyResponse(CURSOR_CONFIRM, List.of(
                        send(ACCOUNT_ID, editedBalance(FIXTURE_BALANCE), BLANK_CONFIRM,
                                BillPaymentService.MSG_CONFIRM_TO_PAY))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(FIXTURE_BALANCE),
                                accountImage(FIXTURE_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    /**
     * {@code case16} - the user declines, and the screen is cleared. {@code SERVICE}.
     *
     * <p>{@code WHEN 'n'} at {@code :179-181}, the lower-case half of a pair of {@code WHEN} clauses
     * sharing one body. It performs {@code CLEAR-CURRENT-SCREEN}, which is
     * {@code INITIALIZE-ALL-FIELDS} followed by a send - so all three entry fields and the message go
     * back blank and the cursor to {@code ACTIDINL} - and <em>then</em> sets the error flag at
     * {@code :181}, in that order.
     *
     * <p>The order is what makes the send blank: the flag is set after the send, so the screen the user
     * sees is the cleared one, and the flag then stops {@code :197} and {@code :208} from running. A
     * translation that set the flag first would send the same fields but would have changed the sequence
     * the state records.
     *
     * @return the scenario
     */
    private static ParityScenario case16() {
        DatasetInput crossReference = fixtureCrossReference();
        return serviceCase(new ParityCase(PROGRAM, "case16",
                "CONFIRMI arrives as 'n', the lower-case half of the WHEN pair at :178-181. "
                        + "CLEAR-CURRENT-SCREEN runs INITIALIZE-ALL-FIELDS and then sends, so all three "
                        + "entry fields and the message go back blank with the cursor at ACTIDINL, and "
                        + "only then does :181 set the error flag - which is why the screen the user sees "
                        + "is the cleared one and why :197 and :208 do not run. No file is read at all on "
                        + "this arm and neither dataset changes.",
                UnitKind.SERVICE,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                enterKeyRequest(ACCOUNT_ID, CONFIRM_NO_LOWER, Map.of()),
                enterKeyResponse(CURSOR_ACTIDIN, List.of(
                        send(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM, ""))),
                List.of(),
                List.of(image(TRANSACT, 0, seededTransactionRow(HIGHEST_TRAN_ID)),
                        record(ACCTDAT, 0, balanceField(FIXTURE_BALANCE),
                                accountImage(FIXTURE_BALANCE))),
                0, List.of(), normalisationsFor(crossReference),
                List.of(channel(TRANSACT, DatasetChannel.WRITES, 0, TranRecord.RECORD_LENGTH),
                        channel(TRANSACT, DatasetChannel.FINAL_STATE, 1, TranRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.WRITES, 0, AccountRecord.RECORD_LENGTH),
                        channel(ACCTDAT, DatasetChannel.FINAL_STATE, 1,
                                AccountRecord.RECORD_LENGTH))));
    }

    // =================================================================================================
    // The four controller cases. What is asserted here is the payload MAIN-PARA returns: the ten screen
    // items at their symbolic-map widths, the sixteen communication-area spans, the cursor, the mapset
    // and map, the next program and the exit. All of it travels in the payload, which is the whole of
    // what a stateless translation of a pseudo-conversational screen produces.
    // =================================================================================================

    /**
     * {@code app/cpy/COTTL01Y.cpy}'s {@code CCDA-TITLE01}, transcribed at its declared
     * {@code PIC X(40)}.
     *
     * <p>Transcribed rather than referenced, deliberately. A parity expectation composed from the
     * production constant would agree with a wrong constant just as readily as with a right one, and
     * reproducing {@code COTTL01Y} byte-exactly is itself one of the things this migration has to get
     * right. {@link #theTranscribedTitlesAreAtTheirDeclaredWidth()} checks the width so a mis-count fails
     * by name.
     */
    private static final String TITLE01 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02}, transcribed from the same copybook at the same width. */
    private static final String TITLE02 = "              CardDemo                  ";

    /**
     * {@code CURDATEO} for the pinned clock: {@code MM/DD/YY}.
     *
     * <p>{@code POPULATE-HEADER-INFO} at {@code :328-332} moves {@code WS-CURDATE-MONTH},
     * {@code WS-CURDATE-DAY} and - note this - {@code WS-CURDATE-YEAR(3:2)}, the reference-modified last
     * two digits of the four-digit year, into a group whose separator {@code FILLER}s are slashes.
     *
     * @return exactly {@value BillPaymentResponse#CUR_DATE_LENGTH} characters
     */
    private static String expectedCurrentDate() {
        LocalDateTime at = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        return zeroFilled(at.getMonthValue(), 2) + '/' + zeroFilled(at.getDayOfMonth(), 2) + '/'
                + zeroFilled(at.getYear() % 100, 2);
    }

    /**
     * {@code CURTIMEO} for the pinned clock: {@code HH:MM:SS}.
     *
     * <p>{@code :334-338}, with colon separators. Eight characters, which is what this map declares -
     * the sibling {@code COSGN00} map declares its own time item at nine, and the two must not be
     * conflated.
     *
     * @return exactly {@value BillPaymentResponse#CUR_TIME_LENGTH} characters
     */
    private static String expectedCurrentTime() {
        LocalDateTime at = LocalDateTime.ofInstant(PINNED_INSTANT, ZoneOffset.UTC);
        return zeroFilled(at.getHour(), 2) + ':' + zeroFilled(at.getMinute(), 2) + ':'
                + zeroFilled(at.getSecond(), 2);
    }

    /**
     * The ten payload items of a painted screen, in the order {@code 01 COBIL0AO} declares them.
     *
     * <p>Every one traces to a {@code DFHMDF} definition in {@code app/bms/COBIL00.bms} and takes its
     * width from the matching {@code xxxI PIC X(n)} item of {@code app/cpy-bms/COBIL00.CPY}: four, forty,
     * eight, eight, forty, eight, eleven, fourteen, one and seventy-eight. The {@code xxxL},
     * {@code xxxF} and {@code xxxA} items are length, flag and attribute metadata and are deliberately
     * absent - the cursor is carried as response metadata instead, which is what
     * {@link ExpectedResponse#cursorField()} is for.
     *
     * @param actIdIn {@code ACTIDINO}
     * @param curBal  {@code CURBALO}
     * @param confirm {@code CONFIRMO}
     * @param errMsg  {@code ERRMSGO}, already at its own width
     * @return the expectation
     */
    private static ScreenSend paintedScreen(String actIdIn, String curBal, String confirm,
                                           String errMsg) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("TRNNAMEO", TRANSACTION_ID);
        fields.put("TITLE01O", TITLE01);
        fields.put("CURDATEO", expectedCurrentDate());
        fields.put("PGMNAMEO", PROGRAM);
        fields.put("TITLE02O", TITLE02);
        fields.put("CURTIMEO", expectedCurrentTime());
        fields.put("ACTIDINO", actIdIn);
        fields.put("CURBALO", curBal);
        fields.put("CONFIRMO", confirm);
        fields.put("ERRMSGO", errMsg);
        return new ScreenSend(fields, Map.of());
    }

    /**
     * {@code case17} - the first-ever invocation, with no communication area at all.
     * {@code CONTROLLER_POJO}.
     *
     * <p>{@code IF EIBCALEN = 0} at {@code :107} is true, so {@code :108} moves {@code 'COSGN00C'} into
     * {@code CDEMO-TO-PROGRAM} and {@code :109} performs {@code RETURN-TO-PREV-SCREEN}, which stamps the
     * area with this transaction and this program, resets the context to {@code ENTER} and transfers
     * control at {@code :281-284}.
     *
     * <p>No screen is painted. {@code SEND-BILLPAY-SCREEN} is never performed on this arm, so
     * {@code POPULATE-HEADER-INFO} never runs and no mapset or map is named - which is what distinguishes
     * an {@code XCTL} from a {@code RETURN TRANSID} in the payload, and why the sends list is empty and
     * the cursor is unset.
     *
     * <p>Statelessness is the substance of this case. The {@code XCTL} becomes a {@code nextProgram}
     * field the client resolves, and the whole 160-byte area travels back with it - stamped, comparable,
     * and held nowhere on the server.
     *
     * @return the scenario
     */
    private static ParityScenario case17() {
        return controllerCase(new ParityCase(PROGRAM, "case17",
                "EIBCALEN is zero, so :107 is true: :108 moves 'COSGN00C' to CDEMO-TO-PROGRAM and :109 "
                        + "performs RETURN-TO-PREV-SCREEN, which stamps CDEMO-FROM-TRANID with CB00, "
                        + "CDEMO-FROM-PROGRAM with COBIL00C and CDEMO-PGM-CONTEXT with zeros before the "
                        + "XCTL at :281-284. No screen is painted, because SEND-BILLPAY-SCREEN is not "
                        + "performed on this arm and POPULATE-HEADER-INFO is its only caller - so no "
                        + "mapset, no map, no send and no cursor, which is exactly how an XCTL differs "
                        + "from a RETURN TRANSID in the payload. The transfer is a response field the "
                        + "client resolves and the whole communication area travels with it, so nothing "
                        + "is held on the server.",
                UnitKind.CONTROLLER_POJO,
                Map.of(),
                Map.of(),
                new ScreenRequest(EIBCALEN_COLD_START, null, PINNED_CLOCK, CHARSET_NAME, Map.of(),
                        Map.of(), Map.of()),
                new ExpectedResponse(SIGN_ON_PROGRAM, null, null,
                        navigationOf(NavigationContext.empty()
                                .withToProgram(SIGN_ON_PROGRAM)
                                .withFromTranid(TRANSACTION_ID)
                                .withFromProgram(PROGRAM)
                                .withPgmEnter()),
                        List.of(), null, Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of(), List.of()));
    }

    /**
     * {@code case18} - the first entry, painting a cleared screen. {@code CONTROLLER_POJO}.
     *
     * <p>An area travelled and {@code CDEMO-PGM-REENTER} is not set, so {@code :112-122} runs:
     * {@code :113} sets the re-entry flag for next time, {@code :114} clears the whole output map with
     * {@code MOVE LOW-VALUES TO COBIL0AO}, {@code :115} positions the cursor at {@code ACTIDINL}, the
     * {@code CDEMO-CB00-TRN-SELECTED} test at {@code :116} finds nothing carried in, and {@code :122}
     * sends.
     *
     * <p>Three details are pinned that nothing else in the set pins. The three entry items come back at
     * {@code LOW-VALUES} rather than spaces, because {@code :114} clears the map with {@code X'00'} and
     * BMS does not transmit a field whose data is null - a screen cleared to low-values and one cleared
     * to spaces do not look the same at the terminal. {@code ERRMSGO}, by contrast, comes back as
     * seventy-eight <em>spaces</em>, because {@code :293} moves the eighty-space {@code WS-MESSAGE} over
     * it on the way out. And the context returns as {@code REENTER}, which is what makes the next
     * invocation take the other arm.
     *
     * @return the scenario
     */
    private static ParityScenario case18() {
        return controllerCase(new ParityCase(PROGRAM, "case18",
                "A first entry: an area travelled and CDEMO-PGM-REENTER is not set, so :113 sets it, "
                        + ":114 clears the output map with LOW-VALUES, :115 puts the cursor at ACTIDINL, "
                        + ":116's CDEMO-CB00-TRN-SELECTED test finds nothing and :122 sends. The ten "
                        + "items come back at their symbolic-map widths with the header painted from the "
                        + "pinned clock; the three entry items are LOW-VALUES rather than spaces, because "
                        + "BMS does not transmit a null field and a map cleared to X'00' does not look "
                        + "like one cleared to X'20'; and ERRMSGO is seventy-eight spaces because :293 "
                        + "moves the eighty-space WS-MESSAGE over it on the way out. The context returns "
                        + "as REENTER, which is what makes the next invocation take the other arm.",
                UnitKind.CONTROLLER_POJO,
                Map.of(),
                Map.of(),
                new ScreenRequest(EIBCALEN_WITH_COMMAREA, null, PINNED_CLOCK, CHARSET_NAME,
                        Map.of(NavigationContext.PGM_CONTEXT_FIELD, PGM_CONTEXT_ENTER), Map.of(),
                        Map.of()),
                new ExpectedResponse(null, MAPSET, MAP,
                        navigationOf(NavigationContext.empty().withPgmReenter()),
                        List.of(paintedScreen(lowValues(BillPaymentResponse.ACT_ID_IN_LENGTH),
                                lowValues(BillPaymentResponse.CUR_BAL_LENGTH),
                                lowValues(BillPaymentResponse.CONFIRM_LENGTH),
                                " ".repeat(BillPaymentResponse.ERR_MSG_LENGTH))),
                        CURSOR_ACTIDIN, Termination.RETURN_TRANSID),
                List.of(), List.of(), 0, List.of(), List.of(), List.of()));
    }

    /**
     * {@code case19} - a re-entry on {@code ENTER}, reaching {@code PROCESS-ENTER-KEY} through the
     * controller. {@code CONTROLLER_POJO}.
     *
     * <p>{@code CDEMO-PGM-REENTER} is set, so {@code :124-125} receives the map and evaluates
     * {@code EIBAID}; the first {@code WHEN} is {@code DFHENTER} and it performs
     * {@code PROCESS-ENTER-KEY} at {@code :127}. The account number arrives blank, so the paragraph
     * rejects at {@code :159-164} and the screen comes back carrying
     * {@code 'Acct ID can NOT be empty...'} with the cursor at {@code ACTIDINL}.
     *
     * <p>This is the {@code REENTER} half of the {@code ENTER}/{@code REENTER} pair - {@code case18} is
     * the other - and it is where the two differ observably: the map is <em>received</em> rather than
     * cleared, so the entry items come back at the values the paragraph left rather than at
     * {@code LOW-VALUES}, and the error message is present rather than blank. Note also what does not
     * happen: no field-level highlight is applied, because {@code COBIL00C} does not copy
     * {@code CSSETATY} at all - its one attribute assignment is the {@code DFHGREEN} of the success arm,
     * which this path never reaches.
     *
     * @return the scenario
     */
    private static ParityScenario case19() {
        DatasetInput crossReference = fixtureCrossReference();
        Map<String, String> mapFields = new LinkedHashMap<>();
        mapFields.put(ACTIDIN_INPUT, BLANK_ACCOUNT_ID);
        mapFields.put(CONFIRM_INPUT, BLANK_CONFIRM);
        return controllerCase(new ParityCase(PROGRAM, "case19",
                "A re-entry on ENTER. CDEMO-PGM-REENTER is set, so :124 receives the map and the first "
                        + "WHEN of the EVALUATE EIBAID at :126 performs PROCESS-ENTER-KEY. The account "
                        + "number arrives blank, so :159-164 rejects with 'Acct ID can NOT be empty...' "
                        + "and the cursor at ACTIDINL. This is the REENTER half of the pair case18 opens: "
                        + "the map is received rather than cleared, so the entry items come back at the "
                        + "values the paragraph left rather than at LOW-VALUES. No field-level highlight "
                        + "is applied on any path here, because COBIL00C does not copy CSSETATY - its "
                        + "only attribute assignment is the DFHGREEN of the WRITE success arm, which this "
                        + "path never reaches.",
                UnitKind.CONTROLLER_POJO,
                datasets(fixtureAccount(), crossReference, masterHolding(HIGHEST_TRAN_ID)),
                Map.of(),
                new ScreenRequest(EIBCALEN_WITH_COMMAREA, AID_ENTER, PINNED_CLOCK, CHARSET_NAME,
                        Map.of(NavigationContext.PGM_CONTEXT_FIELD, PGM_CONTEXT_REENTER), mapFields,
                        Map.of()),
                new ExpectedResponse(null, MAPSET, MAP,
                        navigationOf(NavigationContext.empty().withPgmReenter()),
                        List.of(paintedScreen(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM,
                                picX(BillPaymentService.MSG_ACCT_ID_EMPTY,
                                        BillPaymentResponse.ERR_MSG_LENGTH))),
                        CURSOR_ACTIDIN, Termination.RETURN_TRANSID),
                List.of(), List.of(), 0, List.of(), normalisationsFor(crossReference), List.of()));
    }

    /**
     * {@code case20} - a re-entry on PF3, with nothing to go back to. {@code CONTROLLER_POJO}.
     *
     * <p>The second {@code WHEN} of the {@code EVALUATE EIBAID}, at {@code :128-135}. It tests
     * {@code CDEMO-FROM-PROGRAM}: blank here, so {@code :130} moves {@code 'COMEN01C'} - the main menu -
     * into {@code CDEMO-TO-PROGRAM} rather than echoing an origin, and {@code RETURN-TO-PREV-SCREEN}
     * transfers to it.
     *
     * <p>Two of the three {@code XCTL} shapes this program has are now covered between {@code case17} and
     * this case: the hard-coded {@code 'COSGN00C'} of the no-commarea guard and the conditional main-menu
     * fallback of the PF3 arm. Both become a {@code nextProgram} field, both leave the mapset and map
     * unnamed because no screen is transmitted, and both re-stamp the area with this program before
     * handing it back - which is the same three lines at {@code :278-280} in each case.
     *
     * @return the scenario
     */
    private static ParityScenario case20() {
        return controllerCase(new ParityCase(PROGRAM, "case20",
                "A re-entry on PF3 with CDEMO-FROM-PROGRAM blank, which is the second WHEN of the "
                        + "EVALUATE EIBAID at :128-135. The blank origin sends :130 rather than :132, so "
                        + "CDEMO-TO-PROGRAM becomes 'COMEN01C' - the main menu - and RETURN-TO-PREV-SCREEN "
                        + "transfers there after stamping the area at :278-280. No screen is transmitted, "
                        + "so there is no mapset, no map, no send and no cursor, and the transfer travels "
                        + "as a response field for the client to resolve.",
                UnitKind.CONTROLLER_POJO,
                Map.of(),
                Map.of(),
                new ScreenRequest(EIBCALEN_WITH_COMMAREA, AID_PF3, PINNED_CLOCK, CHARSET_NAME,
                        Map.of(NavigationContext.PGM_CONTEXT_FIELD, PGM_CONTEXT_REENTER), Map.of(),
                        Map.of()),
                new ExpectedResponse(MAIN_MENU_PROGRAM, null, null,
                        navigationOf(NavigationContext.empty()
                                .withPgmReenter()
                                .withToProgram(MAIN_MENU_PROGRAM)
                                .withFromTranid(TRANSACTION_ID)
                                .withFromProgram(PROGRAM)
                                .withPgmEnter()),
                        List.of(), null, Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of(), List.of()));
    }

    // =================================================================================================
    // THE STRUCTURAL GATES.
    //
    // The twenty cases prove that the translation produces the right bytes. These prove why - each one
    // states a single property in isolation, so that when a case fails the failure names the property
    // rather than leaving a reviewer to infer it from a byte offset.
    //
    // Every one is a plain unit test over a real production class. None starts a Spring context, none
    // opens a servlet container, none launches a job, and none reads a resource from the classpath.
    // =================================================================================================

    /**
     * The case-set guard refuses anything that is not exactly {@code case01} through {@code case20} of
     * this program.
     *
     * <p>The one property of {@link #cases()} that nothing else checks, and the one whose failure would be
     * invisible. "The diff count is zero across all twenty cases" is satisfied <em>vacuously</em> by a set
     * of four: the run reports green, the gate reports green, and sixteen questions were never asked. A
     * short set is not a smaller gate; it is a gate that has stopped asking while still reporting green.
     *
     * <p>Three ways the set can be wrong, all driven here:
     *
     * <ul>
     *   <li><strong>Short.</strong> Fewer than twenty - the plain case of a suite left half-written.</li>
     *   <li><strong>Misnumbered.</strong> Twenty entries, but not {@code case01} through {@code case20} in
     *       order. The identifiers are positional and they name the fixture, so a gap or a repeat means a
     *       case nobody runs, or one running twice while another runs not at all - and the count still
     *       reads twenty.</li>
     *   <li><strong>Foreign.</strong> A case belonging to a different program, which would be judged
     *       against this program's unit and would either pass for the wrong reason or fail for a reason
     *       no one could locate.</li>
     * </ul>
     *
     * <p>Each is required to throw, and the message is required to name what is wrong, because the guard
     * runs inside the supplier where a silent misbehaviour could not be observed from outside.
     */
    @Test
    @DisplayName("the case-set guard refuses a short, misnumbered or foreign set - loudly")
    void theCaseSetGuardRefusesAnythingOtherThanTheExactTwenty() {
        List<ParityScenario> complete = cases();

        assertThat(complete)
                .describedAs("the shipped set is exactly the mandated twenty")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        assertThatThrownBy(() -> requireCompleteCaseSet(complete.subList(0, 4)))
                .describedAs("four cases would satisfy 'zero diffs across all cases' vacuously")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROGRAM)
                .hasMessageContaining("exactly");

        List<ParityScenario> misnumbered = new ArrayList<>(complete);
        misnumbered.set(0, complete.get(1));
        assertThatThrownBy(() -> requireCompleteCaseSet(misnumbered))
                .describedAs("twenty entries, but case02 twice and case01 never - the count still reads "
                        + "twenty, which is exactly why the identifiers are checked positionally")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ParityHarness.caseId(1));

        List<ParityScenario> foreign = new ArrayList<>(complete);
        foreign.set(0, controllerCase(new ParityCase("COBIL00X", ParityHarness.caseId(1),
                "A case belonging to another program, which this gate must refuse rather than judge "
                        + "against this program's unit.",
                UnitKind.CONTROLLER_POJO,
                Map.of(), Map.of(),
                new ScreenRequest(EIBCALEN_COLD_START, null, PINNED_CLOCK, CHARSET_NAME, Map.of(),
                        Map.of(), Map.of()),
                new ExpectedResponse(SIGN_ON_PROGRAM, null, null, Map.of(), List.of(), null,
                        Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of(), List.of())));
        assertThatThrownBy(() -> requireCompleteCaseSet(foreign))
                .describedAs("a foreign case would be judged against the wrong unit entirely")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COBIL00X")
                .hasMessageContaining(ParityHarness.CASE_RESOURCE_ROOT + PROGRAM);
    }

    /**
     * The two fraction digits are the whole of {@code ACCT-CURR-BAL}, and what falls off falls
     * <em>downwards</em>.
     *
     * <p>{@code case02} rests on this and the assertion belongs here rather than inside it, because
     * "the case is sharp enough" is a property of the chosen number and not of the run. Three
     * statements make it:
     *
     * <ol>
     *   <li>Storing {@code 194.007} at the picture's scale yields {@code 194.00}: two fraction digits,
     *       and the third discarded.</li>
     *   <li>The stored value is strictly <em>below</em> the value offered. Truncation only ever loses
     *       magnitude, and a rule that carried into the last retained digit would sometimes gain it -
     *       so an implementation that carried would fail this comparison on this number.</li>
     *   <li>The discarded remainder is strictly greater than half of the last retained digit's place
     *       value. That is the condition under which the two rules disagree, and stating it as an
     *       inequality is what makes {@code case02} a proof rather than a coincidence: had the balance
     *       been {@code 194.002}, both rules would have produced {@code 194.00} and the case would have
     *       been green under a wrong implementation.</li>
     * </ol>
     *
     * <p>{@code ROUNDED} appears exactly zero times in all 28 programs, so truncation is the whole of the
     * COBOL store rule and {@link CobolDecimal#COBOL_ROUNDING} is the one place in the system that names
     * it. Nothing in this class names an alternative, which is deliberate: the rule has one home.
     */
    @Test
    @DisplayName("the store truncates, and case02's number is sharp enough to prove it")
    void theTruncationCaseIsSharpEnoughToRejectANearestValueRule() {
        BigDecimal offered = new BigDecimal(SUB_CENT_BALANCE);
        BigDecimal stored = CobolDecimal.storeMonetary(offered);

        assertThat(stored)
                .describedAs("ACCT-CURR-BAL is PIC S9(10)V99, so storing %s keeps two fraction digits "
                        + "and discards the rest downwards", SUB_CENT_BALANCE)
                .isEqualByComparingTo(new BigDecimal(FIXTURE_BALANCE));
        assertThat(stored.scale())
                .describedAs("the stored scale is the picture's scale, not the offered value's")
                .isEqualTo(CobolDecimal.MONETARY_SCALE);
        assertThat(stored)
                .describedAs("truncation only loses magnitude; a rule that carried into the last "
                        + "retained digit would have produced a value above the one offered")
                .isLessThan(offered);

        BigDecimal discarded = offered.subtract(stored);
        BigDecimal halfOfLastPlace = BigDecimal.ONE
                .movePointLeft(CobolDecimal.MONETARY_SCALE)
                .divide(BigDecimal.valueOf(2));
        assertThat(discarded)
                .describedAs("the discarded remainder %s must exceed half of the last retained digit's "
                        + "place value %s, because that is the only region where the two rules disagree "
                        + "- a smaller remainder would leave case02 green under a wrong implementation",
                        discarded, halfOfLastPlace)
                .isGreaterThan(halfOfLastPlace);
    }

    /**
     * {@code MOVE ACCT-CURR-BAL TO TRAN-AMT} loses the high-order digit, not the low-order one.
     *
     * <p>{@code app/cbl/COBIL00C.cbl:224}. The sender is {@code PIC S9(10)V99} and the receiver is
     * {@code PIC S9(09)V99}, one integer digit narrower. A numeric move aligns on the implied decimal
     * point, so the digit with nowhere to go is the leading one - a plain assignment, or a receiver
     * widened to match its sender, would carry the whole balance and be wrong by a billion.
     *
     * <p>Asserted three ways so that no single arithmetic accident can satisfy it: the moved value, the
     * shortfall it leaves, and the balance {@code :234} then computes from the pair. The shortfall being
     * exactly {@code 9,000,000,000.00} is the substance of {@code case03}.
     */
    @Test
    @DisplayName(":224 moves S9(10)V99 into S9(09)V99 and the high-order digit is what falls off")
    void theBalanceMoveIntoTranAmtDiscardsTheHighOrderDigit() {
        BigDecimal balance = new BigDecimal(MAX_BALANCE);

        BigDecimal moved = CobolDecimal.storeAtPicture(balance, TranRecord.TRAN_AMT_INTEGER_DIGITS,
                TranRecord.TRAN_AMT_SCALE);

        assertThat(moved)
                .describedAs("TRAN-AMT holds %d integer digits against ACCT-CURR-BAL's %d, so the "
                        + "leading digit of %s is the one with nowhere to go",
                        TranRecord.TRAN_AMT_INTEGER_DIGITS, AccountRecord.MONETARY_INTEGER_DIGITS,
                        MAX_BALANCE)
                .isEqualByComparingTo(new BigDecimal(MAX_PAYMENT));
        assertThat(balance.subtract(moved))
                .describedAs("the discarded leading digit is worth ten to the power of the receiver's "
                        + "integer width, which is what leaves the account holding a balance rather "
                        + "than settling it")
                .isEqualByComparingTo(new BigDecimal(MAX_DEBITED_BALANCE));
        assertThat(CobolDecimal.subtract(balance, moved, CobolDecimal.MONETARY_SCALE))
                .describedAs(":234 computes ACCT-CURR-BAL - TRAN-AMT from that pair, which is what "
                        + "case03 pins in the rewritten record")
                .isEqualByComparingTo(new BigDecimal(MAX_DEBITED_BALANCE));
        assertThat(TranRecord.TRAN_AMT_INTEGER_DIGITS)
                .describedAs("the widths must actually differ, or this whole test asserts nothing")
                .isLessThan(AccountRecord.MONETARY_INTEGER_DIGITS);
    }

    /**
     * The rewritten account is 300 bytes, its {@code FILLER} is present and space-filled, and the
     * copybook's misspelling survives.
     *
     * <p>Three separate obligations, checked together because they all fail the same way - as a shifted
     * or short record - and are therefore easiest to diagnose side by side.
     *
     * <p><strong>The width.</strong> {@code app/cpy/CVACT01Y.cpy} declares twelve named spans totalling
     * 122 bytes and then {@code FILLER X(178)}. Omitting the {@code FILLER} would yield a 122-byte
     * image: legal-looking, and wrong for every consumer of the file. The image this class composes is
     * decoded by the production record type and re-encoded, and the round trip is required to be
     * byte-identical, which catches a mis-stated offset as readily as a missing span.
     *
     * <p><strong>The misspelling.</strong> The copybook spells byte 58 {@code ACCT-EXPIRAION-DATE}. It
     * is retained verbatim, because the field name is the unit of comparison in a field-for-field diff:
     * a corrected spelling would report as a missing field on one side and an unexpected field on the
     * other, and the correction would have introduced the failure it appeared to be reporting.
     */
    @Test
    @DisplayName("the rewritten CVACT01Y record is 300 bytes with FILLER emitted and EXPIRAION intact")
    void theRewrittenAccountIsThreeHundredBytesAndKeepsTheMisspelling() {
        String image = accountImage(SETTLED_BALANCE);

        assertThat(image.length())
                .describedAs("twelve named spans of 122 bytes plus FILLER X(178); without the FILLER "
                        + "this is 122 and every downstream offset is wrong")
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(image.substring(AccountRecord.FILLER_OFFSET))
                .describedAs("FILLER is emitted as spaces, not omitted and not zero-filled")
                .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH));

        AccountRecord decoded = AccountRecord.decode(image, CHARSET);

        assertThat(decoded.recordLength())
                .describedAs("the production record type agrees on the width")
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(decoded.toFixedWidthString())
                .describedAs("decode then encode is byte-identical, which catches a mis-stated offset "
                        + "as well as a missing span")
                .isEqualTo(image);
        assertThat(decoded.getAcctCurrBal())
                .describedAs("the balance reads back at the picture's scale from the zoned image")
                .isEqualByComparingTo(new BigDecimal(SETTLED_BALANCE));

        assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME)
                .describedAs("CVACT01Y.cpy:L11 spells it EXPIRAION, and a field-for-field diff compares "
                        + "by name - correcting the spelling would manufacture the failure it looked "
                        + "like it was reporting")
                .isEqualTo("ACCT-EXPIRAION-DATE");
        assertThat(decoded.rawAcctExpiraionDate())
                .describedAs("byte 58 for 10 bytes is the expiration date the fixture row carries")
                .isEqualTo(EXPIRATION_DATE);
        RecordLayout layout = AccountRecord.LAYOUT;
        assertThat(layout.recordLength())
                .describedAs("the layout the codec serialises through declares the same width")
                .isEqualTo(AccountRecord.RECORD_LENGTH);
        assertThat(layout.spans())
                .describedAs("thirteen spans - the twelve named items and the FILLER. A layout of "
                        + "twelve would be a record with a 178-byte hole in it")
                .hasSize(13)
                .contains(AccountRecord.SPAN_ACCT_EXPIRAION_DATE, AccountRecord.SPAN_FILLER);
    }

    /**
     * The added transaction is 350 bytes with its own {@code FILLER} emitted.
     *
     * <p>The same obligation as the account record, on the other written dataset.
     * {@code app/cpy/CVTRA05Y.cpy} declares thirteen named spans totalling 330 bytes and then
     * {@code FILLER X(20)}; {@code app/jcl/INTCALC.jcl} independently declares the sequential form of
     * this record at {@code LRECL=350}, so the width is attested twice.
     *
     * <p>{@code TRAN-AMT} is checked at its own offset as well, because it is the one span in this
     * record whose content is computed rather than moved from a literal, and it sits at byte 132 - a
     * position no other assertion in the set would notice had moved.
     */
    @Test
    @DisplayName("the added CVTRA05Y record is 350 bytes with FILLER emitted and TRAN-AMT at byte 132")
    void theAddedTransactionIsThreeHundredAndFiftyBytesWithItsFillerEmitted() {
        String image = transactionImage(NEXT_TRAN_ID, FIXTURE_BALANCE, CARD_NUMBER);

        assertThat(image.length())
                .describedAs("thirteen named spans of 330 bytes plus FILLER X(20), which "
                        + "app/jcl/INTCALC.jcl attests again as LRECL=350")
                .isEqualTo(TranRecord.RECORD_LENGTH);
        assertThat(image.substring(TranRecord.FILLER_OFFSET))
                .describedAs("FILLER is emitted as spaces")
                .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        assertThat(image.substring(TranRecord.TRAN_AMT.offset(),
                        TranRecord.TRAN_AMT.offset() + TranRecord.TRAN_AMT_LENGTH))
                .describedAs("TRAN-AMT is a %d-byte zoned span at byte %d, and the fixture balance "
                        + "%s encodes with a positive overpunch in its low-order position",
                        TranRecord.TRAN_AMT_LENGTH, TranRecord.TRAN_AMT.offset(), FIXTURE_BALANCE)
                .isEqualTo(zoned(FIXTURE_BALANCE, TranRecord.TRAN_AMT_INTEGER_DIGITS,
                        TranRecord.TRAN_AMT_SCALE));

        TranRecord decoded = TranRecord.decode(image, CHARSET);

        assertThat(decoded.displayImage())
                .describedAs("decode then re-render is byte-identical")
                .isEqualTo(image);
        assertThat(decoded.tranAmt())
                .describedAs("the amount reads back at scale %d", TranRecord.TRAN_AMT_SCALE)
                .isEqualByComparingTo(new BigDecimal(FIXTURE_BALANCE));
        RecordLayout layout = TranRecord.LAYOUT;
        assertThat(layout.recordLength())
                .describedAs("the layout declares the same width")
                .isEqualTo(TranRecord.RECORD_LENGTH);
        assertThat(layout.spans())
                .describedAs("fourteen spans - the thirteen named items and the FILLER")
                .hasSize(14)
                .contains(TranRecord.TRAN_AMT, TranRecord.FILLER);
        assertThat(TranRecord.sumOfDeclaredSpanLengths())
                .describedAs("summing the declared span lengths independently of RECORD_LENGTH proves "
                        + "the fourteen spans really do account for every byte")
                .isEqualTo(TranRecord.RECORD_LENGTH);
    }

    /**
     * The two transcribed screen titles are at the width {@code COTTL01Y} declares.
     *
     * <p>{@link #TITLE01} and {@link #TITLE02} are transcribed from {@code app/cpy/COTTL01Y.cpy} lines 19
     * and 22 rather than read from the production constant, so that a wrong constant cannot agree with
     * itself. The risk transcription introduces is a miscounted space, and it is a real one: both literals
     * are mostly padding, forty columns holding twenty-six and eight characters of text respectively.
     *
     * <p>So the transcription is checked three ways here - the declared width, the words with the padding
     * stripped, and the offset at which the text starts - and the binding to production is left to
     * {@code case18}, which compares {@code TITLE01O} and {@code TITLE02O} against these same literals in
     * a full-payload diff. That division is deliberate: the parity case binds transcription to
     * implementation, and this gate checks the transcription's own shape. Echoing the production constant
     * here instead would collapse the two into one assertion that cannot fail.
     */
    @Test
    @DisplayName("the transcribed COTTL01Y titles are 40 bytes each and say what the copybook says")
    void theTranscribedTitlesAreAtTheirDeclaredWidth() {
        assertThat(TITLE01.length())
                .describedAs("CCDA-TITLE01 is PIC X(40) and the map's TITLE01I is X(40) to match")
                .isEqualTo(BillPaymentResponse.TITLE01_LENGTH);
        assertThat(TITLE02.length())
                .describedAs("CCDA-TITLE02 is PIC X(40)")
                .isEqualTo(BillPaymentResponse.TITLE02_LENGTH);
        assertThat(TITLE01.trim())
                .describedAs("the words of COTTL01Y.cpy:19, stated independently of the padding so a "
                        + "miscounted space and a mistyped word fail as separate assertions")
                .isEqualTo("AWS Mainframe Modernization");
        assertThat(TITLE01.indexOf('A'))
                .describedAs("six leading spaces before the first word, per the copybook literal")
                .isEqualTo(6);
        assertThat(TITLE02.trim())
                .describedAs("the words of COTTL01Y.cpy:22. Line 21 sits between the declaration and "
                        + "this value and is a comment holding an abandoned earlier wording, which is "
                        + "deliberately not the value")
                .isEqualTo("CardDemo");
        assertThat(TITLE02.indexOf('C'))
                .describedAs("fourteen leading spaces before CardDemo, per the copybook literal")
                .isEqualTo(14);
        assertThat(expectedCurrentDate().length())
                .describedAs("CURDATEI is X(8): mm/dd/yy, from :328-332")
                .isEqualTo(BillPaymentResponse.CUR_DATE_LENGTH);
        assertThat(expectedCurrentTime().length())
                .describedAs("CURTIMEI is X(8) on this map - hh:mm:ss, from :334-338. The sibling "
                        + "COSGN00 map declares nine, and the two must not be conflated")
                .isEqualTo(BillPaymentResponse.CUR_TIME_LENGTH);
    }

    /**
     * A service over stubbed repositories, for the gates that need to drive one paragraph in isolation.
     *
     * <p>The four outcomes are supplied rather than derived from seeded rows, which is the whole point:
     * an {@code EVALUATE} arm reachable only from a genuine I/O failure - a closed file, an invalid
     * request, a length error - cannot be reached by arranging data, and deleting the arm to avoid
     * testing it would change the guard chain the source declares.
     *
     * @param read  what the keyed read-for-update reports
     * @param write what the rewrite reports
     * @param xref  what the alternate-index read reports
     * @param prev  what the backward browse reports
     * @return a service whose collaborators report exactly those outcomes
     */
    private static BillPaymentService probeService(AccountRepository.ReadResult read,
                                                  AccountRepository.WriteResult write,
                                                  CardXrefRepository.ReadResult xref,
                                                  TransactionRepository.ReadResult prev) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.datasetCharset()).thenReturn(CHARSET);
        when(accounts.readForUpdate(anyString())).thenReturn(read);
        when(accounts.rewrite(any())).thenReturn(write);

        CardXrefRepository crossReference = mock(CardXrefRepository.class);
        when(crossReference.readByAccountIdViaAltIndex(anyString())).thenReturn(xref);

        TransactionRepository.Browse browse = mock(TransactionRepository.Browse.class);
        when(browse.readPrev()).thenReturn(prev);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.startBrowse(BrowseDirection.BACKWARD)).thenReturn(browse);
        when(transactions.write(any()))
                .thenReturn(TransactionRepository.WriteResult.written(TRANSACT));

        return new BillPaymentService(accounts, crossReference, transactions, CLOCK,
                privateUnitOfWork());
    }

    /** @return a service whose every collaborator reports the happy arm over the fixture row */
    private static BillPaymentService probeServiceOnFixtureData() {
        String storedXref = new FixedWidthCodec(CHARSET)
                .padToDeclaredWidth(XREF_ROW_AS_STORED, CardXrefRecord.RECORD_LENGTH);
        return probeService(
                AccountRepository.ReadResult.found(
                        AccountRecord.decode(accountImage(FIXTURE_BALANCE), CHARSET)),
                AccountRepository.WriteResult.written(),
                CardXrefRepository.ReadResult.found(CXACAIX,
                        CardXrefRecord.decode(storedXref.getBytes(CHARSET), CHARSET), storedXref),
                TransactionRepository.ReadResult.found(TRANSACT,
                        TranRecord.decode(seededTransactionRow(HIGHEST_TRAN_ID), CHARSET)));
    }

    /**
     * {@code app/data/ASCII/cardxref.txt} row 48, as the file stores it: 36 bytes, not 50.
     *
     * <p>The one place in the whole fixture set where the data and its copybook disagree.
     * {@code app/cpy/CVACT03Y.cpy} declares {@code XREF-CARD-NUM X(16)}, {@code XREF-CUST-ID 9(09)},
     * {@code XREF-ACCT-ID 9(11)} and then {@code FILLER X(14)}, which is 50; the fixture omits the
     * {@code FILLER} and stops at 36. The row is therefore padded to the declared width before anything
     * decodes it, and {@link Normalisation#CARDXREF_FILLER_PAD_36_TO_50} is what declares that to the
     * harness for the twenty cases.
     */
    private static final String XREF_ROW_AS_STORED =
            CARD_NUMBER + zeroFilled(CUSTOMER_ID, CardXrefRecord.XREF_CUST_ID_LENGTH) + ACCOUNT_ID;

    /**
     * Nothing survives an invocation, so two identical requests produce two identical payloads.
     *
     * <p>{@code COBIL00C} is pseudo-conversational: {@code EXEC CICS RETURN TRANSID('CB00')
     * COMMAREA(CARDDEMO-COMMAREA)} at {@code :146-149} hands the terminal back and takes the task down,
     * and the next keystroke starts a <em>new</em> task that knows only what the communication area
     * carried. A stateless translation therefore has to hold nothing between calls, and this states it
     * three ways:
     *
     * <ol>
     *   <li>Two independently constructed controllers, given the same request, return the same
     *       payload - so no shared static field is involved.</li>
     *   <li>One controller, called twice with the same request, returns the same payload twice - so no
     *       instance field carries a residue forward.</li>
     *   <li>The same controller, called with a <em>different</em> context after a first call, returns
     *       exactly what a freshly built controller returns for that second request alone - so the
     *       first call left nothing behind that changed the second.</li>
     * </ol>
     *
     * <p>The third is the one that matters. The first two would pass a controller that cached a value and
     * happened to be asked for it again; only the third rules that out.
     */
    @Test
    @DisplayName("no server-side state survives an invocation, so a repeat is byte-identical")
    void noServerSideStateSurvivesBetweenInvocations() {
        BillPaymentRequest firstEntry = new BillPaymentRequest();
        firstEntry.setNavigationContext(NavigationContext.empty());

        ObservedResponse fromOne = paintedBy(new BillPaymentController(
                probeServiceOnFixtureData(), CLOCK), firstEntry);
        ObservedResponse fromAnother = paintedBy(new BillPaymentController(
                probeServiceOnFixtureData(), CLOCK), firstEntry);

        assertThat(fromAnother)
                .describedAs("two independently constructed controllers must agree, or something is "
                        + "shared between them - and COBOL WORKING-STORAGE must never become a static "
                        + "Java field, because that is precisely a shared residue")
                .isEqualTo(fromOne);

        BillPaymentController reused = new BillPaymentController(probeServiceOnFixtureData(), CLOCK);
        assertThat(paintedBy(reused, firstEntry))
                .describedAs("one controller called twice with the same request must answer the same "
                        + "way twice, field for field")
                .isEqualTo(paintedBy(reused, firstEntry));

        BillPaymentRequest reentry = new BillPaymentRequest();
        reentry.setNavigationContext(NavigationContext.empty().withPgmReenter());
        reentry.setAid(aidTokenOf(AID_ENTER));
        reentry.setActIdIn(ACCOUNT_ID);
        reentry.setConfirm(CONFIRM_YES);

        assertThat(paintedBy(reused, reentry))
                .describedAs("the second request must be answered as though the first had never "
                        + "happened; this is the assertion the other two cannot make")
                .isEqualTo(paintedBy(new BillPaymentController(probeServiceOnFixtureData(), CLOCK),
                        reentry));
    }

    /**
     * One invocation, projected as the differ compares it.
     *
     * <p>{@link ObservedResponse} rather than the payload's own rendering, deliberately. The payload
     * masks the account identifier and withholds the balance when it prints itself - correct for a log
     * line, and useless for an equality assertion, because a residue in a withheld field would compare
     * equal to its own absence. The observed projection carries every field at full width and is a record,
     * so equality is componentwise over the whole of it.
     *
     * @param controller the controller to invoke
     * @param request    the bound screen
     * @return the projection
     */
    private static ObservedResponse paintedBy(BillPaymentController controller,
                                              BillPaymentRequest request) {
        return observedControllerResponse(controller.payBill(request).screen());
    }

    /**
     * Every attention identifier this screen tests resolves to the boolean the COBOL would compute.
     *
     * <p>{@code COBIL00C} is one of the twelve programs that do <strong>not</strong> copy
     * {@code CSSTRPFY}; it tests {@code EIBAID} inline, in an ordered {@code EVALUATE} at
     * {@code :126-141} with exactly three named arms - {@code DFHENTER}, {@code DFHPF3},
     * {@code DFHPF4} - and a {@code WHEN OTHER}. The Java form routes all seventeen controllers through
     * one {@link PfKeyResolver}, so what has to be shown is that the shared resolver reproduces this
     * program's inline tests as identical boolean outcomes rather than merely similar ones.
     *
     * <p>Seven keys are driven: the three named arms, a function key that is not one of them, the two
     * attention keys, and {@code CLEAR}. Each is checked twice over - once through the resolver's own
     * predicate and once through the payload token the request carries - because those are two separate
     * chains and a divergence between them would be invisible from either side alone.
     *
     * <p>Two properties of the shared resolver are then checked that this program's own inline tests
     * cannot show, because they concern keys it never names.
     *
     * <p>The first is the <strong>fold</strong>. {@code CSSTRPFY} enumerates twenty-eight {@code WHEN}
     * clauses, and the last twelve map {@code DFHPF13} through {@code DFHPF24} back onto the same tokens
     * as {@code DFHPF1} through {@code DFHPF12}. So PF13 does not fail to resolve - it resolves to PF1's
     * token, by identity and not merely by value, and a terminal sending PF15 reaches this program's PF3
     * arm. Reading those as unrecognised keys would silently lose a whole bank of function keys.
     *
     * <p>The second is what happens to a key the copybook tests <em>nowhere</em>. {@code DFHPA3} is one:
     * the paragraph names PA1 and PA2 and stops, as does {@link CicsAid#DFHNULL}. Its {@code EVALUATE}
     * has no {@code WHEN OTHER} and does not clear {@code CCARD-AID} first, so an unmatched AID leaves the
     * previous interaction's token standing - which
     * {@link PfKeyResolver#storePfKey(byte, java.util.Optional)} reproduces, and which
     * {@link PfKeyResolver#resolve(byte)}, being the narrower question, reports as absent.
     */
    @Test
    @DisplayName("the shared PfKeyResolver reproduces this program's inline EIBAID tests exactly")
    void everyAidThisScreenTestsResolvesToTheSameBooleanTheCobolWould() {
        assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER))
                .describedAs(":126 WHEN DFHENTER performs PROCESS-ENTER-KEY")
                .isTrue();
        assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3))
                .describedAs(":128 WHEN DFHPF3 returns to the previous screen")
                .isTrue();
        assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4))
                .describedAs(":136 WHEN DFHPF4 clears the current screen")
                .isTrue();

        for (byte notAnArm : new byte[] {CicsAid.DFHPF5, CicsAid.DFHPA1, CicsAid.DFHPA2,
                CicsAid.DFHCLEAR, CicsAid.DFHNULL}) {
            assertThat(PfKeyResolver.isEnter(notAnArm) || PfKeyResolver.isPf3(notAnArm)
                    || PfKeyResolver.isPf4(notAnArm))
                    .describedAs("AID 0x%02X matches none of this program's three named arms, so it "
                            + "reaches WHEN OTHER at :138 and the screen answers with the standard "
                            + "invalid-key sentence", notAnArm)
                    .isFalse();
        }

        assertThat(aidTokenOf(AID_ENTER))
                .describedAs("the payload token for the ENTER arm")
                .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHENTER).map(AidKey::token).orElseThrow());
        assertThat(aidTokenOf(AID_PF3))
                .describedAs("the payload token for the PF3 arm")
                .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF3).map(AidKey::token).orElseThrow());
        assertThat(aidTokenOf(AID_PF4))
                .describedAs("the payload token for the PF4 arm")
                .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF4).map(AidKey::token).orElseThrow());
        assertThat(aidTokenOf(AID_NO_MATCH))
                .describedAs("PF5 resolves, but to a token none of the three arms names")
                .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF5).map(AidKey::token).orElseThrow());

        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13).orElseThrow())
                .describedAs("CSSTRPFY.cpy:L54-55 folds DFHPF13 back onto PFK01, and the tokens are "
                        + "enum singletons, so the fold is observable by identity - reading PF13 as an "
                        + "unrecognised key would silently lose a whole bank of function keys")
                .isSameAs(PfKeyResolver.resolve(CicsAid.DFHPF1).orElseThrow());
        assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15).orElseThrow())
                .describedAs("and PF15 folds onto PF3's token, so a terminal sending PF15 reaches this "
                        + "program's PF3 arm at :128")
                .isSameAs(PfKeyResolver.resolve(CicsAid.DFHPF3).orElseThrow());

        Optional<AidKey> untestedByTheCopybook = PfKeyResolver.resolve(CicsAid.DFHPA3);
        assertThat(untestedByTheCopybook)
                .describedAs("the paragraph names PA1 and PA2 and stops, so DFHPA3 matches no WHEN at "
                        + "all")
                .isEmpty();
        assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL))
                .describedAs("DFHNULL is likewise untested by the copybook, and is what the controller "
                        + "reads an absent payload token as")
                .isEmpty();
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, Optional.of(AidKey.PFK03)))
                .describedAs("the EVALUATE has no WHEN OTHER and does not clear CCARD-AID first, so an "
                        + "unmatched AID leaves the previous interaction's token standing - the most "
                        + "easily lost property of the whole paragraph")
                .contains(AidKey.PFK03);
        assertThat(PfKeyResolver.storePfKey(CicsAid.DFHPA3, untestedByTheCopybook))
                .describedAs("and with nothing recorded beforehand there is nothing to retain")
                .isEmpty();
    }

    /**
     * The payload is the ten symbolic-map input items, at the ten widths the copybook declares.
     *
     * <p>{@code app/cpy-bms/COBIL00.CPY} declares, for each screen field, a four-item pattern: an
     * {@code xxxL COMP PIC S9(4)} length, an {@code xxxF PICTURE X} flag, an {@code xxxA} attribute view
     * redefining it, and an {@code xxxI PIC X(n)} data item. Only the last is a payload field; the other
     * three are metadata CICS maintains, and promoting any of them to the body would invent a field the
     * screen does not have.
     *
     * <p>Ten fields, summing to 212 data bytes: {@code TRNNAME} 4, {@code TITLE01} 40, {@code CURDATE} 8,
     * {@code PGMNAME} 8, {@code TITLE02} 40, {@code CURTIME} 8, {@code ACTIDIN} 11, {@code CURBAL} 14,
     * {@code CONFIRM} 1, {@code ERRMSG} 78. {@code COBIL00} is the smallest of the seventeen mapsets, at
     * ten {@code DFHMDF} definitions in 141 lines, which is why the whole projection can be stated here
     * in full rather than sampled.
     */
    @Test
    @DisplayName("the payload is exactly the ten xxxI items of COBIL00.CPY at their declared widths")
    void theTenPayloadFieldsAreTheSymbolicMapsInputItemsAtTheirWidths() {
        ScreenSend painted = paintedScreen(BLANK_ACCOUNT_ID, BLANK_BALANCE, BLANK_CONFIRM,
                " ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));

        assertThat(painted.fields())
                .describedAs("ten DFHMDF definitions, ten payload fields - no more, and none of the "
                        + "xxxL, xxxF or xxxA metadata items among them")
                .hasSize(BillPaymentResponse.MAP_FIELD_COUNT)
                .containsOnlyKeys("TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O",
                        "CURTIMEO", "ACTIDINO", "CURBALO", "CONFIRMO", "ERRMSGO");

        Map<String, Integer> declaredWidths = new LinkedHashMap<>();
        declaredWidths.put("TRNNAMEO", BillPaymentResponse.TRN_NAME_LENGTH);
        declaredWidths.put("TITLE01O", BillPaymentResponse.TITLE01_LENGTH);
        declaredWidths.put("CURDATEO", BillPaymentResponse.CUR_DATE_LENGTH);
        declaredWidths.put("PGMNAMEO", BillPaymentResponse.PGM_NAME_LENGTH);
        declaredWidths.put("TITLE02O", BillPaymentResponse.TITLE02_LENGTH);
        declaredWidths.put("CURTIMEO", BillPaymentResponse.CUR_TIME_LENGTH);
        declaredWidths.put("ACTIDINO", BillPaymentResponse.ACT_ID_IN_LENGTH);
        declaredWidths.put("CURBALO", BillPaymentResponse.CUR_BAL_LENGTH);
        declaredWidths.put("CONFIRMO", BillPaymentResponse.CONFIRM_LENGTH);
        declaredWidths.put("ERRMSGO", BillPaymentResponse.ERR_MSG_LENGTH);

        int total = 0;
        for (Map.Entry<String, Integer> declared : declaredWidths.entrySet()) {
            assertThat(painted.fields().get(declared.getKey()).length())
                    .describedAs("%s is PIC X(%d) in app/cpy-bms/COBIL00.CPY and is compared at full "
                            + "width, never trimmed", declared.getKey(), declared.getValue())
                    .isEqualTo(declared.getValue());
            total += declared.getValue();
        }
        assertThat(total)
                .describedAs("the ten widths sum to the map's declared data length")
                .isEqualTo(BillPaymentResponse.MAP_DATA_LENGTH);

        assertThat(painted.attributes())
                .describedAs("a painted screen carries no attribute assignment unless the success arm "
                        + "coloured the message; that is asserted separately")
                .isEmpty();
    }

    /**
     * {@code CXACAIX} is a finder on the {@code CCXREF} cluster, not a dataset of its own.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} defines it as {@code ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY},
     * and {@code app/jcl/INTCALC.jcl} settles the question by opening both in one step - {@code XREFFILE}
     * on the base cluster and {@code XREFFIL1} on the path over it, two DD names addressing one set of
     * records. The relational reflex would be to give the alternate index its own table; that would be a
     * schema change, and the migration forbids one.
     *
     * <p>So there is exactly one repository for the cross-reference, with a second finder method keyed on
     * the eleven-byte account identifier instead of the sixteen-byte card number. This states the four
     * facts that make it one cluster: the two DD names differ, they name the same record length, the
     * alternate key is the account identifier the copybook declares, and the sixteen-byte primary key and
     * the eleven-byte alternate key are different widths over the same fifty bytes.
     */
    @Test
    @DisplayName("CXACAIX is an alternate-index finder over the CCXREF cluster, not a second table")
    void theAlternateIndexIsAFinderOnTheBaseCluster() {
        assertThat(CXACAIX)
                .describedAs("this program's CICS file literal at :412 is the path, not the base")
                .isEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME)
                .isNotEqualTo(CardXrefRepository.BASE_DD_NAME);
        assertThat(CardXrefRepository.RECORD_LENGTH)
                .describedAs("a path over a cluster reads the cluster's own records, so the length is "
                        + "CVACT03Y's fifty bytes on both DD names")
                .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        assertThat(CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD)
                .describedAs("the alternate key is XREF-ACCT-ID, which is what 'VIA ACCOUNT KEY' means")
                .isEqualTo(CardXrefRecord.XREF_ACCT_ID_NAME);
        assertThat(CardXrefRepository.ACCOUNT_ID_KEY_LENGTH)
                .describedAs("eleven bytes of account identifier against the primary key's sixteen "
                        + "bytes of card number - two keys, one set of records")
                .isEqualTo(BillPaymentResponse.ACT_ID_IN_LENGTH)
                .isNotEqualTo(CardXrefRepository.CARD_NUMBER_KEY_LENGTH);

        String stored = new FixedWidthCodec(CHARSET)
                .padToDeclaredWidth(XREF_ROW_AS_STORED, CardXrefRecord.RECORD_LENGTH);
        assertThat(stored.length())
                .describedAs("cardxref.txt stores %d bytes where CVACT03Y declares %d, because the "
                        + "fixture omits the trailing FILLER; the row is padded to the declared width "
                        + "before anything decodes it", XREF_ROW_AS_STORED.length(),
                        CardXrefRecord.RECORD_LENGTH)
                .isEqualTo(CardXrefRecord.RECORD_LENGTH);
        assertThat(CardXrefRecord.decode(stored.getBytes(CHARSET), CHARSET).xrefCardNum())
                .describedAs("the card number this payment stamps into TRAN-CARD-NUM comes off the "
                        + "alternate-index read, keyed by account")
                .isEqualTo(CARD_NUMBER);
    }

    /**
     * The confirmation is the only message this screen ever colours.
     *
     * <p>{@code app/cbl/COBIL00C.cbl:526} moves {@code DFHGREEN} to {@code ERRMSGC} on the
     * {@code WRITE} success arm and nowhere else - it is this program's single attribute assignment, out
     * of the ten fields its map declares. Every rejection leaves the colour item as it found it.
     *
     * <p>Which surfaces an inherited defect worth naming, because a translation would be tempted to fix
     * it: nothing ever moves the colour <em>back</em>. A successful payment leaves {@code ERRMSGC} green,
     * and because the re-entry path does not reset it, a failure message on a later invocation of the
     * same conversation is transmitted in the confirmation's green. That is what the source does, so it
     * is what the translation does, and clearing the attribute would be a behaviour change dressed as a
     * bug fix.
     *
     * <p>What is asserted here is the binding between the two spellings: the mnemonic these cases declare
     * to the harness and the value the production code assigns have to be the same attribute, or the
     * green-send expectations in {@code case01}, {@code case02} and {@code case11} would be asserting
     * nothing.
     */
    @Test
    @DisplayName(":526 is this program's only attribute assignment, and nothing ever clears it")
    void theSuccessArmIsTheOnlyPlaceThisScreenColoursTheMessage() {
        assertThat(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN)
                .describedAs("the production highlight is a single attribute byte")
                .hasSize(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH);
        assertThat(ERRMSGC_GREEN)
                .describedAs("the mnemonic these cases hand the harness must name the attribute the "
                        + "production code assigns, or every green-send expectation asserts nothing")
                .isEqualTo(BmsAttributes.colourMnemonic(
                        (byte) BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.charAt(0)));
        assertThat((byte) BillPaymentService.MESSAGE_HIGHLIGHT_GREEN.charAt(0))
                .describedAs("ERRMSGC is PICTURE X, so the attribute is carried as the one character "
                        + "whose code point is the unsigned value of the DFHBMSCA byte - a hexadecimal "
                        + "rendering would be five characters and would not fit")
                .isEqualTo(BmsAttributes.DFHGREEN);

        String storedXref = new FixedWidthCodec(CHARSET)
                .padToDeclaredWidth(XREF_ROW_AS_STORED, CardXrefRecord.RECORD_LENGTH);
        BillPaymentService rejecting = probeService(
                AccountRepository.ReadResult.notFound(),
                AccountRepository.WriteResult.written(),
                CardXrefRepository.ReadResult.found(CXACAIX,
                        CardXrefRecord.decode(storedXref.getBytes(CHARSET), CHARSET), storedXref),
                TransactionRepository.ReadResult.endOfFile(TRANSACT));

        PaymentState rejected = rejecting.processEnterKey(ACCOUNT_ID, CONFIRM_YES,
                NavigationContext.empty().withPgmReenter());

        assertThat(rejected.messageHighlight())
                .describedAs("a rejection never reaches :526, so it leaves the colour item untouched")
                .isNull();
        assertThat(rejected.errMsg())
                .describedAs("the account read reported NOTFND, which is the arm at :359-364")
                .isEqualTo(picX(BillPaymentService.MSG_ACCOUNT_ID_NOT_FOUND,
                        BillPaymentResponse.ERR_MSG_LENGTH));

        PaymentState paid = probeServiceOnFixtureData().processEnterKey(ACCOUNT_ID, CONFIRM_YES,
                NavigationContext.empty().withPgmReenter());

        assertThat(paid.messageHighlight())
                .describedAs("the WRITE success arm at :526 is the one place the colour is assigned")
                .isEqualTo(BillPaymentService.MESSAGE_HIGHLIGHT_GREEN);
        assertThat(paid.errMsg())
                .describedAs("and the message it colours is the confirmation, naming the identifier "
                        + "the browse computed")
                .isEqualTo(picX(successMessage(NEXT_TRAN_ID), BillPaymentResponse.ERR_MSG_LENGTH));
    }

    /**
     * Every arm of every file operation this program codes is reachable, including the four no seeded row
     * can produce.
     *
     * <p>Four operations, thirteen coded arms between them:
     *
     * <ul>
     *   <li>{@code READ-ACCTDAT-FILE} {@code :348-365} - normal, {@code NOTFND}, other.</li>
     *   <li>{@code UPDATE-ACCTDAT-FILE} {@code :381-402} - normal, {@code NOTFND}, other.</li>
     *   <li>{@code READ-CXACAIX-FILE} {@code :408-436} - normal, {@code NOTFND}, other.</li>
     *   <li>{@code STARTBR-TRANSACT-FILE} {@code :441-467} - normal, {@code NOTFND}, other; and
     *       {@code READPREV-TRANSACT-FILE} {@code :473-499}, where {@code ENDFILE} has an arm of its
     *       own.</li>
     * </ul>
     *
     * <p>The twenty cases drive the arms that seeded data can reach. This drives the ones it cannot -
     * a rewrite reporting {@code NOTFND}, an alternate-index read failing outright, a browse position
     * failing, and a {@code READPREV} failing - each through the public per-paragraph method, so the
     * assertion names the paragraph.
     *
     * <p>One arm placement is worth stating explicitly because it is asymmetric and easy to get wrong:
     * {@code END_OF_FILE} has its own arm in {@code READPREV} at {@code :487-488} but <em>not</em> in
     * {@code STARTBR}, where it falls to {@code WHEN OTHER}. The two are checked side by side below.
     */
    @Test
    @DisplayName("all thirteen coded file-operation arms are reachable, including the four data cannot reach")
    void everyArmOfTheFourFileOperationsIsReachable() {
        BillPaymentService service = probeService(
                AccountRepository.ReadResult.found(
                        AccountRecord.decode(accountImage(FIXTURE_BALANCE), CHARSET)),
                AccountRepository.WriteResult.notFound(),
                CardXrefRepository.ReadResult.other(CXACAIX, UNEXPECTED_STATUS),
                TransactionRepository.ReadResult.other(TRANSACT, UNEXPECTED_STATUS));
        FixedWidthCodec codec = service.codec();

        PaymentState rewriteNotFound = new PaymentState(codec, NavigationContext.empty());
        service.updateAcctdatFile(rewriteNotFound);
        assertThat(rewriteNotFound.errMsg())
                .describedAs(":390-395 - the rewrite reported NOTFND, which no arrangement of seeded "
                        + "rows can produce once the read-for-update has already succeeded")
                .isEqualTo(picX(BillPaymentService.MSG_ACCOUNT_ID_NOT_FOUND,
                        BillPaymentResponse.ERR_MSG_LENGTH));
        assertThat(rewriteNotFound.accountRewritten())
                .describedAs("and the account is left unwritten")
                .isFalse();

        PaymentState crossReferenceFailed = new PaymentState(codec, NavigationContext.empty());
        crossReferenceFailed.setXrefAcctIdRidfld(ACCOUNT_ID);
        service.readCxacaixFile(crossReferenceFailed);
        assertThat(crossReferenceFailed.errMsg())
                .describedAs(":429-435 - WHEN OTHER on the alternate-index read")
                .isEqualTo(picX(BillPaymentService.MSG_UNABLE_TO_LOOKUP_XREF_AIX,
                        BillPaymentResponse.ERR_MSG_LENGTH));
        assertThat(crossReferenceFailed.displays())
                .describedAs("and the arm writes the diagnostic before it rejects, which the NOTFND "
                        + "arm does not")
                .hasSize(1);

        PaymentState positionNotFound = new PaymentState(codec, NavigationContext.empty());
        service.startbrTransactFile(positionNotFound, FileStatus.Outcome.NOT_FOUND);
        assertThat(positionNotFound.errMsg())
                .describedAs(":459-464 - WHEN DFHRESP(NOTFND) on the browse position")
                .isEqualTo(picX(BillPaymentService.MSG_TRANSACTION_ID_NOT_FOUND,
                        BillPaymentResponse.ERR_MSG_LENGTH));

        PaymentState positionFailed = new PaymentState(codec, NavigationContext.empty());
        service.startbrTransactFile(positionFailed, FileStatus.Outcome.END_OF_FILE);
        assertThat(positionFailed.errMsg())
                .describedAs("END_OF_FILE has no arm of its own in STARTBR, so it falls to WHEN OTHER "
                        + "at :465-467 - unlike in READPREV, where it does have one")
                .isEqualTo(picX(BillPaymentService.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                        BillPaymentResponse.ERR_MSG_LENGTH));

        PaymentState readPrevAtEnd = new PaymentState(codec, NavigationContext.empty());
        service.readprevTransactFile(readPrevAtEnd,
                TransactionRepository.ReadResult.endOfFile(TRANSACT));
        assertThat(readPrevAtEnd.errMsg())
                .describedAs(":487-488 - ENDFILE moves zeros to TRAN-ID and raises no flag, no message "
                        + "and no send, which is how the first payment against an empty master gets "
                        + "identifier %s", FIRST_TRAN_ID)
                .isEqualTo(" ".repeat(BillPaymentResponse.ERR_MSG_LENGTH));
        assertThat(readPrevAtEnd.tranIdNum())
                .describedAs("and the identifier the write then increments starts from zero")
                .isZero();

        PaymentState readPrevFailed = new PaymentState(codec, NavigationContext.empty());
        service.readprevTransactFile(readPrevFailed,
                TransactionRepository.ReadResult.other(TRANSACT, UNEXPECTED_STATUS));
        assertThat(readPrevFailed.errMsg())
                .describedAs(":493-499 - WHEN OTHER on the backward read")
                .isEqualTo(picX(BillPaymentService.MSG_UNABLE_TO_LOOKUP_TRANSACTION,
                        BillPaymentResponse.ERR_MSG_LENGTH));
    }

    /**
     * No field-level highlight exists on this screen, because {@code COBIL00C} does not copy
     * {@code CSSETATY}.
     *
     * <p>A negative assertion, and it earns its place. Sixteen of the seventeen online programs are being
     * translated alongside this one, several of them do apply {@code CSSETATY}'s red-and-asterisk
     * treatment to the field that failed validation, and {@code COACTUPC} contains thirty-nine textual
     * occurrences of that include. Adding the same treatment here would look like consistency and would
     * be a new feature: this program's ten-field map has no per-field colour item to write, and its
     * rejections say what went wrong in {@code ERRMSG} and position the cursor, and nothing more.
     *
     * <p>{@link FieldAttributeSetter} is exercised rather than merely named, so that the claim rests on
     * what the shared component actually computes. Under re-entry a failed field would turn red and a
     * blank one would turn red and take the asterisk - and this screen does neither, on any path,
     * including the two rejections a re-entry produces.
     */
    @Test
    @DisplayName("this program copies no CSSETATY, so no field-level highlight is ever applied")
    void noFieldHighlightIsEverAppliedBecauseThisProgramDoesNotCopyCssetaty() {
        FieldHighlight wouldBeRed = FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true);
        FieldHighlight wouldBeRedAndStarred =
                FieldAttributeSetter.resolve(FieldValidationState.BLANK, true);
        FieldHighlight valid = FieldAttributeSetter.resolve(FieldValidationState.OK, true);

        assertThat(wouldBeRed.colourItemAssigned())
                .describedAs("CSSETATY colours a field that failed validation, under re-entry")
                .isTrue();
        assertThat(wouldBeRed.outputItemAssigned())
                .describedAs("but does not star it - the inner test at CSSETATY.cpy:L23 checks "
                        + "blankness only")
                .isFalse();
        assertThat(wouldBeRedAndStarred.outputItemAssigned())
                .describedAs("a blank field satisfies both tests, so it is coloured and starred")
                .isTrue();
        assertThat(valid.colourItemAssigned())
                .describedAs("and a valid field is left completely untouched")
                .isFalse();
        assertThat(FieldAttributeSetter.resolve(FieldValidationState.BLANK, false)
                .colourItemAssigned())
                .describedAs("outside re-entry nothing is highlighted at all, whatever the flags say")
                .isFalse();

        BillPaymentService service = probeServiceOnFixtureData();

        PaymentState blankIdentifier = service.processEnterKey(BLANK_ACCOUNT_ID, BLANK_CONFIRM,
                NavigationContext.empty().withPgmReenter());
        assertThat(blankIdentifier.messageHighlight())
                .describedAs(":159-164 rejects a blank identifier with a sentence and a cursor "
                        + "position, and assigns no attribute - this program has no per-field colour "
                        + "item to write")
                .isNull();
        assertThat(blankIdentifier.cursorField())
                .describedAs("the cursor goes to ACTIDINL, which is the whole of the visual cue")
                .isEqualTo(CursorField.ACTIDIN);

        PaymentState badConfirmation = service.processEnterKey(ACCOUNT_ID, CONFIRM_INVALID,
                NavigationContext.empty().withPgmReenter());
        assertThat(badConfirmation.messageHighlight())
                .describedAs(":185-190, the WHEN OTHER of the EVALUATE CONFIRMI, likewise assigns no "
                        + "attribute")
                .isNull();
        assertThat(badConfirmation.cursorField())
                .describedAs("and positions the cursor on the offending field instead")
                .isEqualTo(CursorField.CONFIRM);
    }

    /**
     * The code page is named at every boundary, never inherited from the platform.
     *
     * <p>A fixed-width image is bytes, and the same characters are different bytes under different code
     * pages: {@code IBM037} for the EBCDIC datasets that {@code README.md:65} requires be transferred in
     * binary mode, {@code US-ASCII} for the nine text fixtures. A codec that took the platform default
     * would decode correctly on the machine it was written on and wrongly on the next one, and the
     * failure would surface as a parity diff in a zoned sign - about the least legible way it could
     * present.
     *
     * <p>So {@link FixedWidthCodec} has no no-argument constructor and the charset travels with the
     * repository, the record and the service alike. This checks the whole chain agrees: what this class
     * declares, what the harness seeds with, what the repository reports and what the service's own codec
     * holds.
     */
    @Test
    @DisplayName("every fixed-width boundary names its code page rather than inheriting a default")
    void theCodecNamesItsCodePageRatherThanInheritingThePlatformDefault() {
        assertThat(CHARSET)
                .describedAs("the ASCII fixtures are authoritative for this work, and US-ASCII is what "
                        + "the harness seeds them under")
                .isEqualTo(ParityHarness.FIXTURE_CHARSET);
        assertThat(CHARSET_NAME)
                .describedAs("ParityCase accepts only the two code pages this system uses, by name")
                .isEqualTo(CHARSET.name());

        FixedWidthCodec codec = new FixedWidthCodec(CHARSET);
        assertThat(codec.charset())
                .describedAs("the codec carries the code page it was built with")
                .isEqualTo(CHARSET);
        assertThat(probeServiceOnFixtureData().codec().charset())
                .describedAs("and the service's codec takes it from the repository rather than from "
                        + "the platform, so one configuration change moves the whole chain")
                .isEqualTo(CHARSET);
        assertThat(AccountRecord.decode(accountImage(FIXTURE_BALANCE), CHARSET).charset())
                .describedAs("a decoded record remembers the code page it was decoded under")
                .isEqualTo(CHARSET);
        assertThat(codec.decodeImage(codec.encodeImage(XREF_ROW_AS_STORED, "cardxref row"),
                        "cardxref row"))
                .describedAs("encode then decode under a named charset is the identity")
                .isEqualTo(XREF_ROW_AS_STORED);
    }

    /**
     * Every dataset this class names is a DD name, and no data set name appears anywhere in it.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} binds {@code ACCTDAT} to a five-qualifier VSAM KSDS name, and that
     * binding belongs in {@code application.yml} and nowhere else - which is why no such name is written
     * anywhere in this file, not even in a comment. A dotted data set name compiled into Java is a
     * deployment decision frozen into source: the same code then cannot address a different region, a
     * test copy, or the fixture-backed bindings this class actually runs against.
     *
     * <p>So the three names here are the eight-character CICS file literals the source itself uses - and
     * they are taken from the repositories rather than typed, which is a second property worth having:
     * a repository that renamed its DD would break this test rather than silently diverge from it.
     */
    @Test
    @DisplayName("datasets are named by DD name; no catalogued data set name appears in Java")
    void theDatasetNamesAreDdNamesRatherThanDataSetNames() {
        for (String ddName : List.of(ACCTDAT, CXACAIX, TRANSACT)) {
            assertThat(ddName)
                    .describedAs("%s must be a DD name; a dotted data set name here would freeze a "
                            + "deployment decision into source", ddName)
                    .doesNotContain(".")
                    .isNotBlank();
            assertThat(ddName.length())
                    .describedAs("%s is a CICS file name, which is at most eight characters", ddName)
                    .isLessThanOrEqualTo(CardXrefRepository.CICS_FILE_NAME_LENGTH);
        }

        assertThat(ACCTDAT)
                .describedAs("taken from the repository, so a rename there fails here rather than "
                        + "diverging quietly")
                .isEqualTo(AccountRepository.CICS_FILE_NAME)
                .isEqualTo(BillPaymentService.WS_ACCTDAT_FILE.trim());
        assertThat(TRANSACT)
                .describedAs("likewise for the transaction file")
                .isEqualTo(TransactionRepository.CICS_FILE_NAME)
                .isEqualTo(BillPaymentService.WS_TRANSACT_FILE.trim());
        assertThat(CXACAIX)
                .describedAs("and for the alternate-index path")
                .isEqualTo(BillPaymentService.WS_CXACAIX_FILE.trim());
    }
}
