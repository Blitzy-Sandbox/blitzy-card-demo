package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionAddController;
import com.vsergeychik.carddemo.transaction.TransactionAddController.ProgramState;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The parity gate for {@code app/cbl/COTRN01C.cbl} - twenty declarative cases, judged field by field,
 * with a required diff count of zero.
 *
 * <h2>Risk R-B: the class is called {@code TransactionAddController} and the program does not add</h2>
 *
 * <p>This is the single highest-risk naming ambiguity in the whole migration, and it is recorded here
 * rather than quietly reconciled, because a reader who trusts the class name will write the wrong
 * expectation and the wrong expectation will pass.
 *
 * <ul>
 *   <li><strong>The build prompt's mapping.</strong> {@code COTRN01C} maps to
 *       {@link TransactionAddController} and {@code COTRN02C} maps to
 *       {@code TransactionViewController}.</li>
 *   <li><strong>The verified source behaviour is the opposite.</strong>
 *       {@code app/cbl/COTRN01C.cbl:5} states
 *       {@code Function    : View a Transaction from TRANSACT file}, and the program contains a single
 *       {@code EXEC CICS READ} ({@code :269-278}) and <em>no</em> {@code WRITE}, {@code REWRITE} or
 *       {@code DELETE} anywhere in its 330 lines.</li>
 *   <li><strong>An independent corroboration.</strong> {@code README.md:213-231} carries the project's
 *       own online inventory and records {@code CT01 | COTRN01 | COTRN01C | Transaction View} against
 *       {@code CT02 | COTRN02 | COTRN02C | Transaction Add} - the inverse of the prompt's mapping.</li>
 *   <li><strong>The resolution, by rule R1.</strong> The name comes from the prompt and the behaviour
 *       comes from the source. The prompt's class name is honoured verbatim; every expectation in this
 *       file describes a <em>keyed single-transaction read and detail display</em>.</li>
 *   <li><strong>It is flagged, not decided.</strong> The ambiguity is escalated for explicit user
 *       confirmation. Nothing here guesses which of the two artefacts is authoritative about intent -
 *       only about behaviour, where the source is the only oracle there is.</li>
 * </ul>
 *
 * <p><strong>Consequence for anyone editing this file:</strong> a case that asserts a written
 * transaction record would be wrong. {@link NoRecordIsEverWritten} exists to make that failure
 * immediate rather than subtle. The mirror-image test is {@code COTRN02CParityTest}, whose class is
 * named {@code TransactionViewController} and whose source really does add a transaction, write a
 * 350-byte record and call {@code CSUTLDTC} twice. If an insert expectation belongs anywhere, it
 * belongs there.
 *
 * <h2>Risk R-A: the baseline is statically derived, never captured</h2>
 *
 * <p>Every expected value in {@code src/test/resources/parity/COTRN01C/} was derived by reading the
 * COBOL, its copybooks, its BMS mapset and the CSD - <em>not</em> by executing the legacy program.
 * Execution is impossible in this environment; the eight independently verified blockers are recorded
 * in the migration plan, and they include a COBOL compiler whose indexed-file handler is disabled, the
 * absence of any Language Environment {@code CEE*} service, the absence of a CICS emulator, and the
 * absence of the IBM-supplied {@code DFHAID} and {@code DFHBMSCA} copybooks this program copies at
 * {@code :71-72}. Practice B12 requires that limit be stated where the expectations live rather than
 * absorbed silently, so it is stated here.
 *
 * <p>Because a statically derived expectation can encode a misreading where a captured one cannot,
 * each case's {@code description} names the source lines it was derived from, and the structural
 * assertions in the nested classes below re-derive the widths mechanically from the copybook-backed
 * layouts instead of restating them as literals.
 *
 * <h2>What the twenty cases pin</h2>
 *
 * <p>The dataset is {@code TRANSACT} and nothing else. The access path is a keyed read on the 16-byte
 * {@code TRAN-ID}, issued as {@link TransactionRepository#readForUpdateByTranId(String)} because
 * {@code :275} states the {@code UPDATE} option. Between them the cases drive: the
 * {@code EIBCALEN = 0} guard; first entry with and without a selection carried from
 * {@code COTRN00C}'s list; every arm of the ordered {@code EVALUATE EIBAID} including
 * {@code WHEN OTHER} and an AID that matches no arm; the three {@code EVALUATE WS-RESP-CD} arms of the
 * read; both rejection messages and the invalid-key message byte for byte; the {@code nextProgram}
 * that replaces {@code EXEC CICS XCTL}; the cursor request; and the send count, which is behaviour in
 * its own right because {@code SEND-TRNVIEW-SCREEN} is not terminal in this program.
 *
 * <p>The unit is constructed as a plain Java object and its {@code MAIN-PARA} method is called
 * directly. There is no {@code MockMvc}, no {@code TestRestTemplate}, no {@code WebTestClient} and no
 * {@code JobLauncher} in the path, so the program's decisions are reached with no HTTP layer between
 * the assertion and the arithmetic (gate G51).
 *
 * @see ParityHarness for how a case is seeded, run and fingerprinted
 * @see FieldDiffer for the field-by-field comparison the diff count comes from
 */
@DisplayName("COTRN01C parity - transaction CT01, a keyed READ, and the class name says Add")
final class COTRN01CParityTest {

    /**
     * The program under test, which is also the name of its case directory:
     * {@code src/test/resources/parity/COTRN01C/}.
     */
    private static final String PROGRAM = "COTRN01C";

    /**
     * {@code COTRN01C} is a CICS online program and the migration gives it a controller with no
     * service beneath it, so the unit a case reaches is the controller itself, constructed as a plain
     * object.
     */
    private static final ParityCase.UnitKind UNIT_KIND = ParityCase.UnitKind.CONTROLLER_POJO;

    /**
     * The dataset binding key for {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'}
     * ({@code app/cbl/COTRN01C.cbl:39}) - the only file this program names.
     *
     * <p>Taken from the repository rather than written as a literal, so no dataset name is spelled out
     * in Java (gate G46) and the key a case declares cannot drift from the key the repository answers
     * to.
     */
    private static final String TRANSACT_DATASET = TransactionRepository.CICS_FILE_NAME;

    /**
     * The symbolic-map length item {@code MOVE -1} is moved into, which is how COBOL positions the
     * cursor. {@code COTRN01C} moves {@code -1} into {@code TRNIDINL} at {@code :102}, {@code :151},
     * {@code :154}, {@code :287}, {@code :294} and {@code :311} and into nothing else, so this is the
     * only cursor field the program can ever request.
     */
    private static final String CURSOR_LENGTH_ITEM =
            ScreenField.TRNIDINO.baseName() + "L";

    /**
     * The status the {@code WHEN OTHER} arm of {@code EVALUATE WS-RESP-CD} ({@code :289}) is reached
     * with when a case forces it.
     *
     * <p>{@code :289} is written for a condition the source does not enumerate, so no enumerated
     * status can reach it. {@link FileStatus#RECORD_LENGTH_CONFLICT} is used because it is a real
     * status that is deliberately none of the four the program's siblings test for, which is exactly
     * the shape of an unexpected condition.
     */
    private static final String UNEXPECTED_STATUS = FileStatus.RECORD_LENGTH_CONFLICT;

    // =================================================================================================
    // The gate.
    // =================================================================================================

    /**
     * The program's twenty cases, in ascending case order.
     *
     * <p>{@link ParityHarness#casesOf(String)} is the only loader used, and it refuses anything other
     * than exactly {@code case01.json} through {@code case20.json} - a short set, a long set, or a
     * directory holding a stray file all fail loudly there. The redundant check below states the count
     * a second time at the call site so a reader of this class can see the number the gate requires
     * without following the call (gate G15).
     *
     * @return exactly twenty cases
     */
    static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the gate is stated as twenty declarative cases per program, and "
                        + "'diff count is zero across all twenty' is satisfied vacuously by a shorter "
                        + "set - so the count is asserted before a single case runs")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> ids = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            ids.add(parityCase.caseId());
            assertThat(parityCase.program())
                    .as("every case in parity/%s/ must name that program, or it is judging a "
                            + "different one", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                    .as("case %s declares unitKind %s; COTRN01C is an online program reached as a "
                            + "plain controller object, so every one of its cases is %s",
                            parityCase.caseId(), parityCase.unitKind(), UNIT_KIND)
                    .isEqualTo(UNIT_KIND);
        }

        List<String> expectedIds = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expectedIds.add(ParityHarness.caseId(ordinal));
        }
        assertThat(ids)
                .as("the twenty cases must be case01 through case20 in order, so that a "
                        + "mis-numbered fixture cannot silently replace another")
                .containsExactlyElementsOf(expectedIds);

        return loaded;
    }

    /**
     * Runs one case and requires the differ to find nothing.
     *
     * <p>The assertion is on the whole {@link FieldDiffer.DiffResult} rather than on a boolean, so a
     * failure reports the count and then every difference the differ found, each naming the field, the
     * expected value, the observed value and why the field matters. That rendering is the differ's
     * work and is surfaced verbatim rather than summarised.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("field-for-field identical to app/cbl/COTRN01C.cbl")
    void isFieldForFieldIdenticalToTheCobol(ParityCase parityCase) {
        FieldDiffer.DiffResult diff =
                ParityHarness.usAscii().judge(parityCase, UNIT_KIND, COTRN01CParityTest::execute);

        assertThat(diff.count())
                .as("%s/%s must produce a diff count of zero. A module is not complete until the "
                        + "count is zero across all twenty of its cases, so a single difference here "
                        + "is a failed gate rather than a tolerance.%n%s",
                        parityCase.program(), parityCase.caseId(), diff.render())
                .isZero();
        assertThat(diff.isClean())
                .as("the differ reported a clean result and a non-zero count, or the reverse - the "
                        + "two must agree.%n%s", diff.render())
                .isTrue();
    }

    // =================================================================================================
    // The adapter: how a case reaches COTRN01C. No HTTP, no job launcher, no session (gates G37, G51).
    // =================================================================================================

    /**
     * Constructs {@link TransactionAddController} as a plain object and calls its {@code MAIN-PARA}
     * method once.
     *
     * <p>One invocation is one CICS task. The three collaborators are supplied explicitly:
     *
     * <ul>
     *   <li>the {@code TRANSACT} repository, stubbed from the case's own seeded rows so that the keyed
     *       read answers from the data the case declares rather than from a hand-written outcome;</li>
     *   <li>the case's pinned clock, so the two header items {@code POPULATE-HEADER-INFO} builds at
     *       {@code :245-262} are comparable byte for byte and the run is deterministic (practice
     *       B7);</li>
     *   <li>a genuine unit of work over a genuine transaction manager, because {@code :275} states the
     *       {@code UPDATE} option and {@link TransactionRepository#readForUpdateByTranId(String)}
     *       refuses to take a record lock with no transaction open. A stub that merely ran the body
     *       would hide whether the boundary is there at all.</li>
     * </ul>
     *
     * <p>The code page is the case's, never the platform default (practice B8).
     *
     * @param invocation the seeded, clocked invocation the harness prepared
     * @return the fingerprint of that one run
     */
    private static ParityHarness.UnitOutcome execute(ParityHarness.Invocation invocation) {
        SingleConnectionDataSource dataSource = taskBoundaryDataSource(invocation);
        try {
            TransactionRepository repository = repositoryFor(invocation);
            TransactionAddController controller = new TransactionAddController(
                    repository,
                    invocation.clock(),
                    new DatasetUnitOfWork(new JdbcTransactionManager(dataSource)),
                    invocation.charset());

            ProgramState state = controller.mainPara(requestOf(invocation));

            requireNothingWasWritten(repository);
            return fingerprintOf(invocation, state);
        } finally {
            // The connection is released whatever happened, so twenty cases do not leave twenty open
            // in-memory databases behind them.
            dataSource.destroy();
        }
    }

    /**
     * A single real connection to a private in-memory database, used for nothing but the transaction
     * boundary.
     *
     * <p>No table is created and no row is read through it. The subject is the boundary itself - which
     * is the framework's behaviour rather than the deployment driver's - so an in-memory database is
     * exactly sufficient and keeps the run offline and deterministic. The database is named after the
     * case so two cases can never share one, and it is discarded when the last connection closes.
     *
     * @param invocation the invocation being run, which names the case
     * @return the data source; the caller must {@code destroy()} it
     */
    private static SingleConnectionDataSource taskBoundaryDataSource(
            ParityHarness.Invocation invocation) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:parity-" + invocation.program() + '-' + invocation.caseId()
                        + ";DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        dataSource.setSuppressClose(true);
        return dataSource;
    }

    /**
     * The {@code TRANSACT} dataset as this case declares it.
     *
     * <p>Fixture-backed by default: the keyed read answers from the rows the case seeded, decoded at
     * the copybook's own 350-byte width, and reports {@code DFHRESP(NOTFND)} for a key no seeded row
     * carries. That keeps the case's data and the case's expectation describing the same thing, and it
     * means the {@code NORMAL} and {@code NOTFND} arms of {@code EVALUATE WS-RESP-CD} ({@code :281},
     * {@code :283}) are reached by the same mechanism a terminal would reach them by.
     *
     * <p>The {@code WHEN OTHER} arm at {@code :289} is different in kind. It is written for a condition
     * the source does not enumerate, so no seeded row can produce it; a case reaches it by declaring a
     * forced outcome, which the harness then requires the run to actually consume. That is the only
     * situation in which the declared outcome overrides the data.
     *
     * @param invocation the invocation being run
     * @return the repository the controller will read through
     */
    private static TransactionRepository repositoryFor(ParityHarness.Invocation invocation) {
        TransactionRepository repository = mock(TransactionRepository.class);

        List<String> rows = invocation.hasDataset(TRANSACT_DATASET)
                ? invocation.dataset(TRANSACT_DATASET).rows()
                : List.of();
        Charset charset = invocation.charset();

        if (invocation.hasForcedOutcome(ParityCase.RepositoryOperation.READ_FOR_UPDATE)) {
            ParityCase.ForcedOutcome forced =
                    invocation.forcedOutcome(ParityCase.RepositoryOperation.READ_FOR_UPDATE);
            // The key is needed as well as the outcome, because the duplicate arm carries a record and
            // that record is resolved from the case's own seeded rows rather than invented.
            when(repository.readForUpdateByTranId(anyString()))
                    .thenAnswer(answer -> forcedReadResult(
                            forced, answer.getArgument(0, String.class), rows, charset));
            return repository;
        }

        when(repository.readForUpdateByTranId(anyString()))
                .thenAnswer(answer -> keyedRead(answer.getArgument(0, String.class), rows, charset));
        return repository;
    }

    /**
     * {@code EXEC CICS READ ... RIDFLD(TRAN-ID) KEYLENGTH(LENGTH OF TRAN-ID)} against the seeded rows.
     *
     * <p>The comparison is on the record's own 16-byte {@code TRAN-ID} span rather than on a trimmed
     * value, because the key the program supplies is whatever {@code MOVE TRNIDINI TO TRAN-ID}
     * ({@code :172}) left in the field - a 16-character image, space-padded on the right like any
     * {@code PIC X} receiver. Comparing images is what a keyed read does.
     *
     * @param key     the key the program moved into {@code TRAN-ID}
     * @param rows    the seeded rows, each a full 350-byte image
     * @param charset the case's code page
     * @return {@code found} carrying the matching record, or {@code notFound}
     */
    private static ReadResult keyedRead(String key, List<String> rows, Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        String wanted = codec.movePicX(key == null ? "" : key, TranRecord.TRAN_ID_LENGTH);
        for (String row : rows) {
            TranRecord record = TranRecord.decode(row, charset);
            if (record.rawSpan(TranRecord.TRAN_ID).equals(wanted)) {
                return ReadResult.found(TransactionRepository.INPUT_DD_NAME, record);
            }
        }
        return ReadResult.notFound(TransactionRepository.INPUT_DD_NAME);
    }

    /**
     * Translates a case's declared {@link ParityCase.ForcedOutcome} into the outcome the repository
     * reports.
     *
     * <p>{@code OK} is refused rather than mapped. It is the outcome the fixture-backed read already
     * produces for a key a seeded row carries, so forcing it would either duplicate that path or
     * require inventing the record's 350 bytes - a record no copybook, no fixture and no case declared,
     * every field of which would then be compared against an expectation derived from nothing. A case
     * that wants a record read normally seeds one and lets the keyed read find it.
     *
     * <p>{@code DUPLICATE} <strong>is</strong> mapped, and is the one forced outcome that still needs
     * the key. {@code DFHRESP(DUPKEY)} is a condition CICS reports <em>alongside</em> the record rather
     * than instead of it, which is why {@link ReadResult} permits a record on that arm and requires one;
     * so the arm is reached without inventing anything by taking the record from the row the case itself
     * seeded, exactly as the refusal above prescribes. A case forcing a duplicate against data that does
     * not carry the key is rejected rather than served a fabricated record.
     *
     * @param forced     what the case declared
     * @param key        the key the program moved into {@code TRAN-ID}
     * @param seededRows the rows the case seeded, each a full 350-byte image
     * @param charset    the case's code page
     * @return the corresponding read outcome
     */
    private static ReadResult forcedReadResult(ParityCase.ForcedOutcome forced, String key,
                                               List<String> seededRows, Charset charset) {
        return switch (forced.outcome()) {
            case NOT_FOUND -> ReadResult.notFound(TransactionRepository.INPUT_DD_NAME);
            case END_OF_FILE -> ReadResult.endOfFile(TransactionRepository.INPUT_DD_NAME);
            case OTHER -> ReadResult.other(TransactionRepository.INPUT_DD_NAME, UNEXPECTED_STATUS);
            case DUPLICATE -> forcedDuplicate(forced, key, seededRows, charset);
            case OK -> throw new IllegalArgumentException("A COTRN01C case forced the "
                    + forced.outcome() + " outcome for the keyed read. That outcome carries a record, "
                    + "and forcing it would require inventing 350 bytes no copybook, fixture or case "
                    + "declared - so every field of the resulting screen would be compared against an "
                    + "expectation derived from nothing. Seed a TRANSACT row instead: the "
                    + "fixture-backed read then reports " + FileStatus.Outcome.OK + " for a key that "
                    + "matches and " + FileStatus.Outcome.NOT_FOUND + " for one that does not.");
        };
    }

    /**
     * The duplicate-key arm of the keyed read, carrying the seeded record and the case's own
     * {@code RESP}/{@code RESP2} pair.
     *
     * <h4>Why the response pair comes from the case</h4>
     *
     * <p>Both {@link FileStatus#DUPREC} and {@link FileStatus#DUPKEY} map forward onto the batch status
     * {@code '22'}, and {@code FileStatus} deliberately leaves the reverse direction ambiguous for that
     * reason - so the status alone cannot say which condition a case meant. The case names it, and it is
     * reported through {@link CicsResponse#reported(int, int)}, whose contract is the pair an adapter
     * actually reported rather than one derived from something else. For a keyed {@code READ} the apt
     * condition is {@link FileStatus#DUPKEY}: {@link FileStatus#DUPREC} is documented as the duplicate a
     * {@code WRITE} reports, and this program issues no {@code WRITE} anywhere. That is the default when
     * a case declares no response, and any response a case does declare must still classify as
     * {@link FileStatus.Outcome#DUPLICATE} - otherwise the fixture and the outcome it names would
     * disagree, which is the contradiction {@link ReadResult}'s own invariants exist to prevent.
     *
     * @param forced     what the case declared
     * @param key        the key the program moved into {@code TRAN-ID}
     * @param seededRows the rows the case seeded
     * @param charset    the case's code page
     * @return the duplicate outcome, status {@code '22'}, carrying the first matching seeded record
     * @throws IllegalArgumentException if no seeded row carries the key, or if the declared response is
     *                                  not a duplicate condition
     */
    private static ReadResult forcedDuplicate(ParityCase.ForcedOutcome forced, String key,
                                              List<String> seededRows, Charset charset) {
        TranRecord record = keyedRead(key, seededRows, charset).record()
                .orElseThrow(() -> new IllegalArgumentException("A COTRN01C case forced the "
                        + FileStatus.Outcome.DUPLICATE + " outcome for the keyed read of '" + key
                        + "', but no seeded TRANSACT row carries that key. A duplicate is reported "
                        + "alongside a record, so the record has to come from somewhere; taking it from "
                        + "the case's own seeded data is what keeps it from being invented. Seed a row "
                        + "whose TRAN-ID is the key the case asks for."));

        int resp = forced.resp() == null ? FileStatus.DUPKEY : forced.resp();
        int resp2 = forced.resp2() == null ? FileStatus.NO_REASON_CODE : forced.resp2();
        if (FileStatus.outcomeOfCicsResp(resp) != FileStatus.Outcome.DUPLICATE) {
            throw new IllegalArgumentException("A COTRN01C case forced the "
                    + FileStatus.Outcome.DUPLICATE + " outcome but declared RESP " + resp
                    + ", which common.FileStatus classifies as "
                    + FileStatus.outcomeOfCicsResp(resp) + ". The two must agree, or the DISPLAY at "
                    + ":290 would render a response code that contradicts the arm the case says it "
                    + "pins. The duplicate conditions are DFHRESP(DUPREC) = " + FileStatus.DUPREC
                    + " and DFHRESP(DUPKEY) = " + FileStatus.DUPKEY + ".");
        }
        return new ReadResult(TransactionRepository.INPUT_DD_NAME, FileStatus.DUPLICATE,
                FileStatus.Outcome.DUPLICATE, Optional.of(record),
                CicsResponse.reported(resp, resp2), Optional.empty(), Optional.empty());
    }

    /**
     * Proves, on <em>every</em> case rather than on one, that no path through this program writes.
     *
     * <p>This is the mechanical half of risk R-B. The class is named
     * {@code TransactionAddController}; the program it translates contains no {@code WRITE},
     * {@code REWRITE} or {@code DELETE}, so no arm may reach a mutating repository method - not the
     * first-entry arm, not the enter-key arm after a successful read, not the clear arm, not the
     * invalid-key arm. Asserting it inside the adapter means the twentieth case is not the only thing
     * standing between a future "fix" and a silently passing suite.
     *
     * @param repository the repository the run went through
     */
    private static void requireNothingWasWritten(TransactionRepository repository) {
        verify(repository, never()).write(any());
        verify(repository, never()).openOutput();
        // The non-locking read is never used either: :275 states UPDATE, so the locking form is the
        // one the translation issues. Naming it here keeps the two apart, because a translation that
        // quietly dropped the UPDATE option would have dropped the record lock with it.
        verify(repository, never()).readByTranId(anyString());
    }

    // =================================================================================================
    // Building the inbound screen: the commarea, the EIBAID and the received map.
    // =================================================================================================

    /**
     * Assembles the request from the three things {@code COTRN01C} is driven by.
     *
     * <p>{@code EIBCALEN = 0} is expressed by leaving the communication area absent, which is what the
     * translation reads it as at {@code :94} - a request with no area cannot say who called, and that
     * is the whole content of the condition. A case declaring {@code eibcalen: 0} therefore produces a
     * request carrying no {@link NavigationContext} at all rather than one carrying a blank area, and
     * the two are not the same state.
     *
     * <p>For a non-zero length, the area is assembled through the codec from the field images the case
     * declares and then parsed back by the domain types. That is {@code MOVE
     * DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA} done the way the copybook describes it: the case
     * names fields, the codec lays them out at their declared offsets and widths, and any field the
     * case does not name keeps the initialised content its {@code VALUE} clause or its picture gives
     * it. Nothing here decides what a field means.
     *
     * @param invocation the invocation being run
     * @return the inbound screen
     */
    private static TransactionAddRequest requestOf(ParityHarness.Invocation invocation) {
        TransactionAddRequest request = new TransactionAddRequest();
        FixedWidthCodec codec = new FixedWidthCodec(invocation.charset());

        if (invocation.eibcalen() > 0) {
            request.setNavigationContext(NavigationContext.fromFixedWidth(codec,
                    codec.serialise(NavigationContext.LAYOUT,
                            subsetOf(invocation.commarea(), baseCommareaFieldNames()))));
            request.setCt01Info(Ct01Info.fromFixedWidth(codec,
                    codec.serialise(Ct01Info.LAYOUT,
                            subsetOf(invocation.commarea(), extensionFieldNames()))));
        }

        // EIBAID. Declared by DFHAID mnemonic in the case and converted to the raw byte here, because
        // the payload projects a one-byte item and the mnemonic is what a reader of the case needs to
        // see. An undeclared AID stays absent, which the translation reads as DFHNULL - the AID CICS
        // reports when no key raised the interrupt, and one that matches none of the four named arms.
        String aid = rawAidOf(invocation.aid());
        if (aid != null) {
            request.setAid(aid);
        }

        for (Map.Entry<String, String> field : invocation.mapFields().entrySet()) {
            applyReceivedField(request, field.getKey(), field.getValue());
        }
        return request;
    }

    /**
     * Narrows a case's communication-area declaration to the fields one layout actually owns.
     *
     * <p>{@code COTRN01C} declares its 58-byte {@code CDEMO-CT01-INFO} extension <em>inside</em> the
     * same {@code 01} group as the 160-byte {@code CARDDEMO-COMMAREA} at {@code :52-61}, which is what
     * makes the passed area 218 bytes. In Java the two are separate types, so a case's single flat
     * {@code commarea} map has to be split, and the split is made by asking each layout which names it
     * declares rather than by matching a name prefix. A prefix test would quietly send an unrecognised
     * {@code CDEMO-} field nowhere; this way the codec rejects it.
     *
     * @param layout the layout to collect field images for
     * @param values every communication-area field the case declares
     * @return only the entries {@code layout} declares a span for
     */
    private static Map<String, String> subsetOf(Map<String, String> values, List<String> names) {
        Map<String, String> owned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (names.contains(entry.getKey())) {
                owned.put(entry.getKey(), entry.getValue());
            }
        }
        return owned;
    }

    /**
     * The sixteen field names the 160-byte {@code CARDDEMO-COMMAREA} declares, read from its own
     * layout so this class restates no offset and no width.
     *
     * @return the declared names, in copybook order
     */
    private static List<String> baseCommareaFieldNames() {
        List<String> names = new ArrayList<>();
        for (var span : NavigationContext.LAYOUT.spans()) {
            names.add(span.name());
        }
        return names;
    }

    /**
     * The six field names the 58-byte {@code CDEMO-CT01-INFO} extension declares
     * ({@code app/cbl/COTRN01C.cbl:53-61}), read from its own layout for the same reason.
     *
     * @return the declared names, in copybook order
     */
    private static List<String> extensionFieldNames() {
        List<String> names = new ArrayList<>();
        for (var span : Ct01Info.LAYOUT.spans()) {
            names.add(span.name());
        }
        return names;
    }

    /**
     * The raw {@code EIBAID} byte a {@code DFHAID} mnemonic stands for, as a one-character string.
     *
     * <p>{@code DFHAID} is IBM-supplied and absent from this repository (risk R-D), so
     * {@link CicsAid} is the single reproduction of it and the mnemonic-to-byte correspondence is read
     * from there rather than restated. An unknown mnemonic cannot occur - {@link ParityCase} validates
     * the value against the same map when the case loads - so reaching the throw means the two have
     * drifted apart, which is worth failing loudly for.
     *
     * @param mnemonic the mnemonic the case declared, or {@code null} for no AID
     * @return the one-character raw byte, or {@code null} when no AID was declared
     */
    private static String rawAidOf(String mnemonic) {
        if (mnemonic == null) {
            return null;
        }
        for (Map.Entry<Byte, String> entry : CicsAid.mnemonicsByAid().entrySet()) {
            if (entry.getValue().equals(mnemonic)) {
                return String.valueOf((char) (entry.getKey() & 0xFF));
            }
        }
        throw new IllegalArgumentException('"' + mnemonic + "\" is not a DFHAID mnemonic that "
                + CicsAid.class.getName() + " reproduces, yet ParityCase accepted it. The two read "
                + "the same map, so this means they have drifted apart.");
    }

    /**
     * {@code EXEC CICS RECEIVE MAP ... INTO(COTRN1AI)} - one received field.
     *
     * <p>Written as an explicit dispatch over the twenty-one {@code xxxI} names the symbolic map
     * declares, taken from {@link TransactionAddRequest}'s own constants so a name can never be
     * mistyped here. Explicit rather than reflective on purpose: the copybook-to-field correspondence
     * is the thing under test, and a reflective setter would make it invisible to a reviewer.
     *
     * <p>Every one of the twenty-one is settable even though the program reads only {@code TRNIDINI}
     * at {@code :147}, because the {@code WHEN OTHER} arm at {@code :128-131} sends the screen back
     * without blanking it - so what the operator typed into the other twenty fields is echoed, and a
     * case that could not set them could not assert that echo.
     *
     * @param request the request being assembled
     * @param name    the {@code xxxI} item name
     * @param value   the image the case declared
     */
    private static void applyReceivedField(TransactionAddRequest request, String name, String value) {
        switch (name) {
            case TransactionAddRequest.TRNNAME_FIELD -> request.setTrnname(value);
            case TransactionAddRequest.TITLE01_FIELD -> request.setTitle01(value);
            case TransactionAddRequest.CURDATE_FIELD -> request.setCurdate(value);
            case TransactionAddRequest.PGMNAME_FIELD -> request.setPgmname(value);
            case TransactionAddRequest.TITLE02_FIELD -> request.setTitle02(value);
            case TransactionAddRequest.CURTIME_FIELD -> request.setCurtime(value);
            case TransactionAddRequest.TRNIDIN_FIELD -> request.setTrnidin(value);
            case TransactionAddRequest.TRNID_FIELD -> request.setTrnid(value);
            case TransactionAddRequest.CARDNUM_FIELD -> request.setCardnum(value);
            case TransactionAddRequest.TTYPCD_FIELD -> request.setTtypcd(value);
            case TransactionAddRequest.TCATCD_FIELD -> request.setTcatcd(value);
            case TransactionAddRequest.TRNSRC_FIELD -> request.setTrnsrc(value);
            case TransactionAddRequest.TDESC_FIELD -> request.setTdesc(value);
            case TransactionAddRequest.TRNAMT_FIELD -> request.setTrnamt(value);
            case TransactionAddRequest.TORIGDT_FIELD -> request.setTorigdt(value);
            case TransactionAddRequest.TPROCDT_FIELD -> request.setTprocdt(value);
            case TransactionAddRequest.MID_FIELD -> request.setMid(value);
            case TransactionAddRequest.MNAME_FIELD -> request.setMname(value);
            case TransactionAddRequest.MCITY_FIELD -> request.setMcity(value);
            case TransactionAddRequest.MZIP_FIELD -> request.setMzip(value);
            case TransactionAddRequest.ERRMSG_FIELD -> request.setErrmsg(value);
            default -> throw new IllegalArgumentException('"' + name + "\" is not one of the "
                    + TransactionAddRequest.PAYLOAD_FIELD_COUNT + " xxxI items "
                    + "app/cpy-bms/COTRN01.CPY declares. The declared set is "
                    + TransactionAddRequest.PAYLOAD_FIELD_NAMES + ". The xxxL, xxxF and xxxA items are "
                    + "length, flag and attribute metadata and are not payload fields, so they are not "
                    + "settable from a case.");
        }
    }

    // =================================================================================================
    // Projecting the run into a fingerprint.
    // =================================================================================================

    /**
     * Everything one execution of {@code COTRN01C} leaves behind that a case can compare.
     *
     * <p>Four channels, and the third and fourth are the ones a reader is most likely to overlook:
     *
     * <ol>
     *   <li>the online response - the screen, the navigation context, the next program, the cursor and
     *       the termination;</li>
     *   <li>{@code TRANSACT}'s final state, reported <em>unchanged</em>. That is an assertion, not
     *       bookkeeping: this program reads with the {@code UPDATE} option, which takes a record lock,
     *       and a translation that took the lock and then rewrote the record would be caught here and
     *       nowhere else in the response;</li>
     *   <li>{@code WS-MESSAGE} at its declared {@code PIC X(80)} and {@code ERRMSGO} at its declared
     *       {@code PIC X(78)}, reported as two separate channels. {@code MOVE WS-MESSAGE TO ERRMSGO}
     *       at {@code :217} moves 80 characters into a 78-character receiver, so the two differ by the
     *       two bytes COBOL loses on the right, and reporting only one of them would make that move
     *       unobservable;</li>
     *   <li>the {@code DISPLAY} at {@code :290}, which is the only console output the program has and
     *       is emitted on exactly one path.</li>
     * </ol>
     *
     * <p>The {@code RETURN-CODE} is reported as zero. {@code COTRN01C} is an online program: it sets no
     * {@code RETURN-CODE} and contains no {@code CALL 'CEE3ABD'}, so zero is the value and it is stated
     * rather than defaulted.
     *
     * @param invocation the invocation that was run
     * @param state      the working storage as it stood when the task ended
     * @return the recorded outcome
     */
    private static ParityHarness.UnitOutcome fingerprintOf(ParityHarness.Invocation invocation,
                                                           ProgramState state) {
        FixedWidthCodec codec = new FixedWidthCodec(invocation.charset());
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        recorder.response(observedResponseOf(codec, state));

        if (invocation.hasDataset(TRANSACT_DATASET)) {
            recorder.finalStateUnchanged(invocation.dataset(TRANSACT_DATASET), TranRecord.LAYOUT);
        }

        recorder.message(new ParityCase.EmittedMessage(
                ParityCase.MessageChannel.WS_MESSAGE_80, state.message()));
        recorder.message(new ParityCase.EmittedMessage(
                ParityCase.MessageChannel.SCREEN_ERRMSG_78, state.response().getErrmsgo()));
        for (String line : state.displays()) {
            recorder.display(line);
        }

        recorder.returnCode(0);
        return recorder.build();
    }

    /**
     * The response as the differ compares it.
     *
     * <h4>Why every send carries the same field map, and why that is exact rather than approximate</h4>
     *
     * <p>{@code SEND-TRNVIEW-SCREEN} ({@code :213-225}) ends with no {@code GO TO}, so control returns
     * to the statement after the {@code PERFORM} and two of this program's paths send the screen twice:
     * first entry carrying a selection sends at {@code :191} and again at {@code :109}, and so does
     * every rejecting arm of {@code PROCESS-ENTER-KEY}. The count is behaviour and is reported as it
     * stands.
     *
     * <p>The content of those two sends is identical, and provably so rather than by assumption. Only
     * three statements run between them - {@code POPULATE-HEADER-INFO}, which rebuilds the two header
     * items from the case's <em>pinned</em> clock and therefore writes the same bytes; {@code MOVE
     * WS-MESSAGE TO ERRMSGO}, which copies a value nothing has changed; and the {@code CSSETATY}
     * decision, which for this program is always "touch nothing". {@link SendIsIdempotent} asserts
     * that directly by sending twice and comparing all twenty-one fields, so the claim is tested and
     * not merely argued.
     *
     * <h4>Why a blank mapset is reported as none</h4>
     *
     * <p>{@code RETURN-TO-PREV-SCREEN} sends no map before transferring - the target program paints its
     * own - so the response blanks its mapset and map rather than leaving them naming this screen. A
     * blank is how the 3270 layer says "no map"; {@code null} is how the case model says it, because
     * {@link ParityCase.ExpectedResponse} validates a mapset name against a pattern that a run of
     * spaces cannot satisfy. Translating one to the other here keeps the case readable and loses
     * nothing: the blank and the absence carry the same single fact.
     *
     * @param codec the case's code page and move rules
     * @param state the working storage as it stood when the task ended
     * @return the observed response
     */
    private static FieldDiffer.ObservedResponse observedResponseOf(FixedWidthCodec codec,
                                                                  ProgramState state) {
        TransactionAddResponse response = state.response();

        // The 218 bytes the XCTL at :207 and the RETURN at :138 both pass, projected field by field.
        // Statelessness (rule R6, gate G37) is what makes this comparable at all: the conversation
        // state is in the payload, so it can be read off the response instead of out of a session.
        Map<String, String> navigation = new LinkedHashMap<>();
        navigation.putAll(codec.deserialise(NavigationContext.LAYOUT,
                response.getNavigationContext().toFixedWidth(codec)));
        navigation.putAll(codec.deserialise(Ct01Info.LAYOUT,
                response.getCt01Info().toFixedWidth(codec)));

        Map<String, String> painted = new LinkedHashMap<>();
        for (Map.Entry<ScreenField, String> item : response.payloadItems().entrySet()) {
            painted.put(item.getKey().outputItemName(), item.getValue());
        }

        List<FieldDiffer.ObservedSend> sends = new ArrayList<>(state.screensSent());
        for (int send = 0; send < state.screensSent(); send++) {
            // No attribute map: COTRN01C does not copy CSSETATY and moves no attribute byte, so there
            // is nothing to report on that channel and an empty map says exactly that (gate G38).
            sends.add(FieldDiffer.ObservedSend.ofFields(painted));
        }

        return new FieldDiffer.ObservedResponse(
                response.getNextProgram(),
                namedOrNone(response.getNextMapset()),
                namedOrNone(response.getNextMap()),
                navigation,
                sends,
                cursorLengthItemOf(state),
                terminationOf(state));
    }

    /**
     * A mapset or map name, or {@code null} where the program named none.
     *
     * @param reference the value the response carries
     * @return the name, or {@code null} when it is absent or blank
     */
    private static String namedOrNone(String reference) {
        return reference == null || reference.isBlank() ? null : reference;
    }

    /**
     * The symbolic-map length item {@code MOVE -1} was moved into, or {@code null} where the program
     * requested no cursor.
     *
     * <p>COBOL positions the cursor by moving {@code -1} into a field's {@code xxxL} item, so the
     * length item <em>is</em> the cursor and it is reported under that name rather than under the
     * field's. The two paths that transfer control - {@code :96} and {@code :122}/{@code :127} - send
     * no map and request no cursor.
     *
     * @param state the working storage as it stood when the task ended
     * @return the {@code xxxL} item name, or {@code null}
     */
    private static String cursorLengthItemOf(ProgramState state) {
        return state.cursorRequested() ? state.cursorField().baseName() + "L" : null;
    }

    /**
     * How the task ended: {@code EXEC CICS XCTL} at {@code :205-208} or {@code EXEC CICS RETURN} at
     * {@code :136-139}.
     *
     * <p>The two are not interchangeable and cannot both happen: an {@code XCTL} transfers and never
     * comes back, so the {@code RETURN} that follows it in the source is not reached. Neither happening
     * would mean a path fell out of {@code MAIN-PARA} without terminating, which no arm of the source
     * does, so it is refused rather than reported as a difference - a fingerprint that cannot say how
     * the task ended is not a fingerprint of a CICS program.
     *
     * @param state the working storage as it stood when the task ended
     * @return the termination
     */
    private static ParityCase.Termination terminationOf(ProgramState state) {
        if (state.transferred() == state.returned()) {
            throw new IllegalStateException("The run reports transferred=" + state.transferred()
                    + " and returned=" + state.returned() + ". Exactly one must hold: every arm of "
                    + "app/cbl/COTRN01C.cbl:86-139 ends either at the XCTL of :205-208 or at the "
                    + "EXEC CICS RETURN of :136-139, and an XCTL never reaches that RETURN.");
        }
        return state.transferred() ? ParityCase.Termination.XCTL
                : ParityCase.Termination.RETURN_TRANSID;
    }

    // =================================================================================================
    // Fixtures for the assertions that do not go through a case file.
    //
    // The twenty cases judge behaviour. The nested classes below pin the structural facts the cases are
    // *written against* - the record widths, the two commarea widths, the truncation rule, the AID
    // dispatch order and the absence of highlighting. A case cannot assert those: it can only be
    // consistent with them, and a case consistent with a wrong width still reports a diff count of zero.
    // =================================================================================================

    /** The instant the direct assertions pin, matching the one the case files declare. */
    private static final Instant PINNED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    /** A transaction id that the direct assertions seed a record under. */
    private static final String KNOWN_TRAN_ID = "0000000000000001";

    /**
     * Runs {@code work} against a controller wired exactly as {@link #execute} wires one, and releases
     * the connection afterwards.
     *
     * @param repository the {@code TRANSACT} dataset
     * @param work       what to do with the controller
     * @param <T>        whatever the caller wants back
     * @return the result of {@code work}
     */
    private static <T> T withController(TransactionRepository repository,
                                        Function<TransactionAddController, T> work) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:parity-direct-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "", true);
        dataSource.setSuppressClose(true);
        try {
            return work.apply(new TransactionAddController(
                    repository,
                    Clock.fixed(PINNED_INSTANT, ZoneOffset.UTC),
                    new DatasetUnitOfWork(new JdbcTransactionManager(dataSource)),
                    ParityHarness.FIXTURE_CHARSET));
        } finally {
            dataSource.destroy();
        }
    }

    /** A repository whose locking keyed read reports {@code outcome} for any key. */
    private static TransactionRepository repositoryReporting(ReadResult outcome) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.readForUpdateByTranId(anyString())).thenReturn(outcome);
        return repository;
    }

    /** A 350-byte {@code TRAN-RECORD} with every field distinguishable from every other. */
    private static TranRecord tranRecord(String tranId, BigDecimal amount) {
        TranRecord record = new TranRecord(ParityHarness.FIXTURE_CHARSET);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(5);
        record.moveTranSource("POS TERM");
        record.moveTranDesc("PARITY DESCRIPTION");
        record.moveTranAmt(amount);
        record.moveTranMerchantId(800000001L);
        record.moveTranMerchantName("PARITY MERCHANT");
        record.moveTranMerchantCity("PARITY CITY");
        record.moveTranMerchantZip("12345-6789");
        record.moveTranCardNum("4111111111111111");
        record.moveTranOrigTs("2022-07-19 23:12:34.123456");
        record.moveTranProcTs("2022-07-20 01:02:03.654321");
        return record;
    }

    /** {@code EIBCALEN = 0} - {@code :94}: a request carrying no communication area at all. */
    private static TransactionAddRequest coldStart() {
        return new TransactionAddRequest();
    }

    /** A re-entry carrying the given raw {@code EIBAID} byte - {@code :110-112}. */
    private static TransactionAddRequest reentryWith(byte eibAid) {
        TransactionAddRequest request = new TransactionAddRequest();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid(String.valueOf((char) (eibAid & 0xFF)));
        return request;
    }

    // =================================================================================================

    @Nested
    @DisplayName("Risk R-B - no path writes, because the program views")
    class NoRecordIsEverWritten {

        @Test
        @DisplayName("the source contains no WRITE, REWRITE or DELETE and says so in its header")
        void theCobolSourceNeverWrites() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN01C.cbl"),
                    StandardCharsets.UTF_8);

            assertThat(cobol)
                    .as("the Function header is the primary evidence for risk R-B and the reason "
                            + "every expectation in this file describes a read")
                    .contains("Function    : View a Transaction from TRANSACT file");
            assertThat(cobol)
                    .as("a WRITE, REWRITE or DELETE anywhere in COTRN01C would mean this whole file "
                            + "is written against the wrong program")
                    .doesNotContain("WRITE")
                    .doesNotContain("DELETE");
            assertThat(cobol)
                    .as("the only file operation is the keyed READ at :269-278, with the UPDATE "
                            + "option at :275")
                    .contains("EXEC CICS READ")
                    .contains("UPDATE");
        }

        @Test
        @DisplayName("README.md's own inventory records CT01 as Transaction View, not Add")
        void theProjectInventoryContradictsThePromptsMapping() throws IOException {
            String readme = Files.readString(repositoryFile("README.md"), StandardCharsets.UTF_8);

            assertThat(readme)
                    .as("README.md:213-231 is the independent corroboration of risk R-B; if this row "
                            + "ever changes, the resolution recorded in this class must be revisited")
                    .contains("| CT01 | COTRN01 | COTRN01C | Transaction View    |");
            assertThat(readme)
                    .as("and the sibling really is the one that adds")
                    .contains("| CT02 | COTRN02 | COTRN02C | Transaction Add     |");
        }

        @Test
        @DisplayName("no arm of the program reaches a mutating repository method")
        void noArmWrites() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.found(TransactionRepository.INPUT_DD_NAME,
                            tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"))));

            withController(repository, controller -> {
                TransactionAddRequest enter = reentryWith(CicsAid.DFHENTER);
                enter.setTrnidin(KNOWN_TRAN_ID);

                controller.mainPara(enter);
                controller.mainPara(coldStart());
                controller.mainPara(firstEntrySelecting(KNOWN_TRAN_ID));
                controller.mainPara(reentryWith(CicsAid.DFHPF3));
                controller.mainPara(reentryWith(CicsAid.DFHPF4));
                controller.mainPara(reentryWith(CicsAid.DFHPF5));
                controller.mainPara(reentryWith(CicsAid.DFHPF12));
                return null;
            });

            requireNothingWasWritten(repository);
        }

        /** A first entry arriving from {@code COTRN00C}'s list with a transaction selected. */
        private TransactionAddRequest firstEntrySelecting(String tranId) {
            TransactionAddRequest request = new TransactionAddRequest();
            request.setNavigationContext(NavigationContext.empty());
            Ct01Info info = new Ct01Info();
            info.setTrnSelected(tranId);
            info.setTrnSelFlg("S");
            request.setCt01Info(info);
            return request;
        }
    }

    @Nested
    @DisplayName("SEND-TRNVIEW-SCREEN is repeatable, which is why every send carries one field map")
    class SendIsIdempotent {

        @Test
        @DisplayName("sending twice under a pinned clock paints byte-identical values")
        void twoSendsAreIdentical() {
            Map<ScreenField, String> after = withController(repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME)), controller -> {
                        ProgramState state =
                                new ProgramState(new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET));
                        controller.sendTrnviewScreen(state);
                        Map<ScreenField, String> first =
                                new LinkedHashMap<>(state.response().payloadItems());
                        controller.sendTrnviewScreen(state);

                        assertThat(state.response().payloadItems())
                                .as("POPULATE-HEADER-INFO reads a pinned clock and MOVE WS-MESSAGE TO "
                                        + "ERRMSGO copies an unchanged value, so a second send writes "
                                        + "the same bytes. The whole reason observedResponseOf can give "
                                        + "every send one field map rests on this")
                                .isEqualTo(first);
                        assertThat(state.screensSent())
                                .as("both sends are counted: the send count is behaviour, because "
                                        + "SEND-TRNVIEW-SCREEN is not terminal in this program")
                                .isEqualTo(2);
                        return state.response().payloadItems();
                    });

            assertThat(after.get(ScreenField.CURDATEO))
                    .as("MM/DD/YY from the pinned instant - :252-256")
                    .isEqualTo("07/19/22");
            assertThat(after.get(ScreenField.CURTIMEO))
                    .as("HH:MM:SS from the pinned instant - :258-262")
                    .isEqualTo("23:12:34");
        }
    }

    @Nested
    @DisplayName("The widths every case is written against")
    class DeclaredWidths {

        @Test
        @DisplayName("TRAN-RECORD is 350 bytes and its FILLER is present and space-filled")
        void tranRecordIsThreeHundredAndFiftyBytesIncludingFiller() {
            TranRecord record = tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"));

            assertThat(TranRecord.RECORD_LENGTH)
                    .as("CVTRA05Y declares a 350-byte TRAN-RECORD")
                    .isEqualTo(350);
            assertThat(record.displayImage())
                    .as("a serialised record is exactly its declared width, which is the check that "
                            + "fails immediately if a FILLER span were omitted")
                    .hasSize(TranRecord.RECORD_LENGTH);
            assertThat(TranRecord.sumOfDeclaredSpanLengths())
                    .as("every byte of the record belongs to a declared span; a gap would leave the "
                            + "sum short of the record length")
                    .isEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(record.rawSpan(TranRecord.FILLER))
                    .as("CVTRA05Y's trailing FILLER PIC X(20) is emitted as spaces; omit it and every "
                            + "downstream offset and the total width are wrong")
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the commarea is 160 bytes and the CT01 extension is a separate 58")
        void theExtensionIsNotFoldedIntoTheCommarea() {
            FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y's CARDDEMO-COMMAREA is 160 bytes and is shared by all 17 online "
                            + "programs, so the per-program extension must not be folded into it")
                    .isEqualTo(160);
            assertThat(Ct01Info.RECORD_LENGTH)
                    .as("CDEMO-CT01-INFO is 16 + 16 + 8 + 1 + 1 + 16 = 58 bytes "
                            + "(app/cbl/COTRN01C.cbl:53-61)")
                    .isEqualTo(58);
            assertThat(TransactionAddResponse.PASSED_COMMAREA_LENGTH)
                    .as("the area the XCTL at :207 and the RETURN at :138 pass is the two together")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH)
                    .isEqualTo(218);

            assertThat(NavigationContext.empty().toFixedWidth(codec))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(new Ct01Info().toFixedWidth(codec))
                    .hasSize(Ct01Info.RECORD_LENGTH);
            assertThat(baseCommareaFieldNames())
                    .as("the sixteen COCOM01Y fields and the six extension fields are disjoint, which "
                            + "is what lets a case declare them in one flat map")
                    .doesNotContainAnyElementsOf(extensionFieldNames());
            assertThat(extensionFieldNames())
                    .containsExactly(Ct01Info.TRNID_FIRST_FIELD, Ct01Info.TRNID_LAST_FIELD,
                            Ct01Info.PAGE_NUM_FIELD, Ct01Info.NEXT_PAGE_FLG_FIELD,
                            Ct01Info.TRN_SEL_FLG_FIELD, Ct01Info.TRN_SELECTED_FIELD);
        }

        @Test
        @DisplayName("WS-MESSAGE is 80 and ERRMSGO is 78, so :217 loses the last two bytes")
        void theMessageIsTruncatedByTwoOnItsWayToTheScreen() {
            FixedWidthCodec codec = new FixedWidthCodec(ParityHarness.FIXTURE_CHARSET);

            assertThat(ParityCase.MessageChannel.WS_MESSAGE_80.fixedWidth()).isEqualTo(80);
            assertThat(ParityCase.MessageChannel.SCREEN_ERRMSG_78.fixedWidth()).isEqualTo(78);
            assertThat(ScreenField.ERRMSGO.payloadLength())
                    .as("the symbolic map declares ERRMSGO PIC X(78)")
                    .isEqualTo(ParityCase.MessageChannel.SCREEN_ERRMSG_78.fixedWidth());

            // A message wide enough to actually lose its last two characters, which none of this
            // program's own four literals is - so the rule is proved rather than assumed.
            String eighty = codec.movePicX("X".repeat(80), TransactionAddController.WS_MESSAGE_LENGTH);
            TransactionAddResponse response = new TransactionAddResponse();
            response.setErrmsgo(eighty);

            assertThat(eighty).hasSize(80);
            assertThat(response.getErrmsgo())
                    .as("MOVE PIC X(80) TO PIC X(78) keeps the leading 78 characters and discards the "
                            + "rest on the right")
                    .hasSize(78)
                    .isEqualTo("X".repeat(78));
        }

        @Test
        @DisplayName("the symbolic map projects exactly 21 payload fields")
        void theScreenHasTwentyOnePayloadFields() {
            assertThat(ScreenField.values())
                    .as("app/bms/COTRN01.bms declares 21 DFHMDF fields and app/cpy-bms/COTRN01.CPY "
                            + "declares 21 xxxI items; the payload is those and nothing else")
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT)
                    .hasSize(21);
            assertThat(TransactionAddRequest.PAYLOAD_FIELD_NAMES)
                    .hasSize(TransactionAddResponse.PAYLOAD_FIELD_COUNT);
        }
    }

    @Nested
    @DisplayName("Numeric parity - this program computes nothing, and the one numeric move truncates")
    class NumericParity {

        @Test
        @DisplayName("the source contains no arithmetic verb and no ROUNDED at all")
        void theProgramPerformsNoArithmetic() throws IOException {
            String cobol = Files.readString(repositoryFile("app/cbl/COTRN01C.cbl"),
                    StandardCharsets.UTF_8);

            for (String verb : List.of("ADD", "SUBTRACT", "COMPUTE", "MULTIPLY", "DIVIDE",
                    "ROUNDED")) {
                assertThat(cobol)
                        .as("COTRN01C is 57 MOVEs and no arithmetic whatever. %s appearing here would "
                                + "mean this program acquired a calculation, and every rounding and "
                                + "scale question in the migration would then apply to it", verb)
                        .doesNotContain(verb);
            }
        }

        @Test
        @DisplayName("TRAN-AMT is scale 2 and the edited move discards high-order digits, not low")
        void theEditedMoveTruncatesOnTheLeft() {
            assertThat(TranRecord.TRAN_AMT_SCALE)
                    .as("CVTRA05Y declares TRAN-AMT PIC S9(09)V99, so the scale is fixed at two and "
                            + "there is no fractional excess for a rounding mode to decide")
                    .isEqualTo(2);

            // MOVE PIC S9(09)V99 TO PIC +99999999.99 - :177. Nine integer digits into eight, aligned
            // on the decimal point, so the digit that does not fit is the high-order one. There is no
            // ON SIZE ERROR phrase in the program, so nothing is raised.
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("504.77")))
                    .isEqualTo("+00000504.77")
                    .hasSize(TransactionAddController.WS_TRAN_AMT_LENGTH);
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("-50.00")))
                    .as("the picture's sign insertion is fixed, so a negative prints its sign rather "
                            + "than carrying a zoned overpunch")
                    .isEqualTo("-00000050.00");
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("0.00")))
                    .as("zero is not negative, so a fixed sign insertion prints '+'")
                    .isEqualTo("+00000000.00");
            assertThat(TransactionAddController.editedTranAmt(new BigDecimal("123456789.12")))
                    .as("the leading 1 is discarded - truncation on the left for a numeric receiver, "
                            + "which is the opposite direction from a PIC X move")
                    .isEqualTo("+23456789.12");
        }
    }

    @Nested
    @DisplayName("EVALUATE EIBAID - four named arms in source order, then WHEN OTHER")
    class AidDispatch {

        @Test
        @DisplayName("the four named arms are the four the source names, and nothing else matches")
        void onlyTheFourNamedKeysMatchAnArm() {
            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf4(CicsAid.DFHPF4)).isTrue();
            assertThat(PfKeyResolver.isPf5(CicsAid.DFHPF5)).isTrue();

            for (byte aid : new byte[] {CicsAid.DFHNULL, CicsAid.DFHCLEAR, CicsAid.DFHPA1,
                    CicsAid.DFHPF1, CicsAid.DFHPF12, CicsAid.DFHPF15}) {
                assertThat(PfKeyResolver.isEnter(aid) || PfKeyResolver.isPf3(aid)
                        || PfKeyResolver.isPf4(aid) || PfKeyResolver.isPf5(aid))
                        .as("EIBAID 0x%02X must fall to WHEN OTHER at :128. COTRN01C tests EIBAID "
                                + "inline rather than through CSSTRPFY, and the resolver has to "
                                + "reproduce those inline tests as identical boolean outcomes",
                                aid)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("PF3 with no caller falls back to the main menu, and with one echoes it")
        void pf3ResolvesItsTargetFromTheCommarea() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME));

            withController(repository, controller -> {
                ProgramState blankCaller = controller.mainPara(reentryWith(CicsAid.DFHPF3));
                assertThat(blankCaller.response().getNextProgram())
                        .as(":116-117 - a blank CDEMO-FROM-PROGRAM defaults the target to COMEN01C")
                        .isEqualTo(TransactionAddController.MAIN_MENU_PROGRAM);
                assertThat(blankCaller.transferred())
                        .as("the PF3 arm reaches the XCTL at :205-208 and never the RETURN at :136")
                        .isTrue();
                assertThat(blankCaller.screensSent())
                        .as("no map is sent before transferring - the target paints its own")
                        .isZero();

                TransactionAddRequest fromList = reentryWith(CicsAid.DFHPF3);
                fromList.setNavigationContext(NavigationContext.empty()
                        .withPgmReenter()
                        .withFromProgram(TransactionAddController.TRANSACTION_LIST_PROGRAM));
                assertThat(controller.mainPara(fromList).response().getNextProgram())
                        .as(":119-120 - a named caller is echoed back as the target")
                        .isEqualTo(TransactionAddController.TRANSACTION_LIST_PROGRAM);

                assertThat(controller.mainPara(reentryWith(CicsAid.DFHPF5))
                        .response().getNextProgram())
                        .as(":126 - PF5 always goes to the transaction list")
                        .isEqualTo(TransactionAddController.TRANSACTION_LIST_PROGRAM);
                assertThat(controller.mainPara(coldStart()).response().getNextProgram())
                        .as(":95 - no commarea, so the program abandons the transaction for signon")
                        .isEqualTo(TransactionAddController.SIGN_ON_PROGRAM);
                return null;
            });
        }

        @Test
        @DisplayName("an unnamed key takes WHEN OTHER and reports the invalid-key message")
        void whenOtherReportsTheInvalidKeyMessage() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME));

            withController(repository, controller -> {
                ProgramState state = controller.mainPara(reentryWith(CicsAid.DFHPF12));

                assertThat(state.errFlagOn())
                        .as(":129 - MOVE 'Y' TO WS-ERR-FLG")
                        .isTrue();
                assertThat(state.message())
                        .as(":130 - CCDA-MSG-INVALID-KEY moved into PIC X(80)")
                        .hasSize(TransactionAddController.WS_MESSAGE_LENGTH)
                        .startsWith("Invalid key pressed. Please see below...");
                assertThat(state.response().getErrmsgo())
                        .as(":217 - and then into PIC X(78)")
                        .hasSize(ScreenField.ERRMSGO.payloadLength());
                assertThat(state.returned())
                        .as(":131 sends the screen and control falls through to the RETURN at :136")
                        .isTrue();
                assertThat(state.response().getNextMapset())
                        .as("the screen is named on a path that sends one")
                        .isEqualTo(TransactionAddController.MAPSET_NAME);
                return null;
            });
        }
    }

    @Nested
    @DisplayName("Gate G38 - this screen highlights in neither state, and gate G37 - no session")
    class HighlightingAndStatelessness {

        @Test
        @DisplayName("the CSSETATY decision is 'touch nothing' on both ENTER and REENTER")
        void neverHighlightsInEitherState() {
            for (boolean reenter : new boolean[] {false, true}) {
                FieldHighlight decision = TransactionAddController.lookupFieldHighlight(reenter);

                assertThat(decision.untouched())
                        .as("COTRN01C does not copy CSSETATY and declares no FLG-<field>-NOT-OK pair, "
                                + "so with reenter=%s the resolver writes no byte. Gate G38 asks that "
                                + "highlighting apply only in REENTER; this program satisfies it by "
                                + "applying none in either state", reenter)
                        .isTrue();
                assertThat(decision.colourItemAssigned()).isFalse();
                assertThat(decision.outputItemAssigned()).isFalse();
            }
        }

        @Test
        @DisplayName("no rejecting path ever colours the lookup field red")
        void theLookupFieldIsNeverRecoloured() {
            withController(repositoryReporting(
                    ReadResult.notFound(TransactionRepository.INPUT_DD_NAME)), controller -> {
                        TransactionAddRequest blankKey = reentryWith(CicsAid.DFHENTER);
                        TransactionAddRequest missingKey = reentryWith(CicsAid.DFHENTER);
                        missingKey.setTrnidin(KNOWN_TRAN_ID);

                        for (TransactionAddRequest request
                                : List.of(blankKey, missingKey, reentryWith(CicsAid.DFHPF4))) {
                            ProgramState state = controller.mainPara(request);
                            assertThat(TransactionAddController.isErrorColoured(state.response()))
                                    .as("TRNIDINC must never hold DFHRED (0x%02X): this program moves "
                                            + "no attribute byte at all", BmsAttributes.DFHRED)
                                    .isFalse();
                        }
                        return null;
                    });
        }

        @Test
        @DisplayName("two runs of one controller cannot see each other - WORKING-STORAGE is per task")
        void theControllerKeepsNoStateBetweenRuns() {
            TransactionRepository repository = repositoryReporting(
                    ReadResult.found(TransactionRepository.INPUT_DD_NAME,
                            tranRecord(KNOWN_TRAN_ID, new BigDecimal("504.77"))));

            withController(repository, controller -> {
                TransactionAddRequest found = reentryWith(CicsAid.DFHENTER);
                found.setTrnidin(KNOWN_TRAN_ID);
                ProgramState first = controller.mainPara(found);

                // A second run with no key at all. If any WORKING-STORAGE item had become a field on
                // the controller, the first run's record would still be painted here.
                ProgramState second = controller.mainPara(reentryWith(CicsAid.DFHENTER));

                assertThat(first.response().getTrnido())
                        .as(":178 - the first run painted the record it read")
                        .startsWith(KNOWN_TRAN_ID);
                assertThat(second.response().getTrnido())
                        .as("the second run rejected a blank key at :147-152 and reached neither the "
                                + "read nor the paint, so the detail field is blank. A shared field "
                                + "would show the first run's value here")
                        .isBlank();
                assertThat(second.message())
                        .as(":149 - Tran ID can NOT be empty...")
                        .startsWith("Tran ID can NOT be empty...");
                assertThat(second.response())
                        .as("each run builds its own response, so the two are distinct objects")
                        .isNotSameAs(first.response());
                return null;
            });
        }
    }

    /**
     * Resolves a repository-relative path from wherever the suite was launched.
     *
     * <p>Reference sources are read to <em>assert</em> facts about them and never written: the trees
     * under {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms}, {@code app/jcl},
     * {@code app/proc}, {@code app/csd}, {@code app/ctl}, {@code app/catlg} and {@code app/data} are the
     * only oracle this migration has (practice B3).
     *
     * @param relativePath the path relative to the repository root
     * @return the resolved path
     * @throws IllegalStateException if it cannot be found at or above the working directory
     */
    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath() + ". The parity assertions read the COBOL source and "
                + "README.md as evidence for risk R-B, so the suite must run inside the repository.");
    }
}
