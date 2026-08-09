package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetChannel;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedDataset;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.Redaction;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenSend;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The deterministic judge of this migration: it compares one {@link ParityCase}'s expectations
 * against one observed {@link Fingerprint} <strong>field by field</strong> and reports how many
 * differences it found.
 *
 * <h2>Why this class exists in exactly this shape</h2>
 * <p>The acceptance gate for every module is "diff count = 0", and this class is what computes that
 * number. Everything about it follows from one decision: <em>the unit of comparison is a field, never
 * a record and never a whole document</em>. A record whose three fields are wrong yields
 * <strong>three</strong> diffs, each naming its own COBOL field, its declared offset and its
 * declared length. Comparing record images as opaque strings would collapse those three findings into
 * one unhelpful "not equal", and a single width error would report one difference instead of naming
 * the field that shifted - which is precisely why the comparison here is field-granular
 * <em>by construction</em> rather than by convention. For the same reason nothing is delegated to a
 * whole-document assertion library.
 *
 * <h2>Four channels, compared independently</h2>
 * <p>A case declares four separate kinds of expectation and this class judges each on its own terms,
 * because collapsing any two of them lets a real difference hide behind a satisfied one:
 * <ol>
 *   <li><strong>Ordered writes.</strong> {@link ParityCase#expectedWrites()} against
 *       {@link Fingerprint#writes()}. Write order is behaviour, and a write the case did not expect
 *       is a difference even if every expected write is also present.</li>
 *   <li><strong>Final state.</strong> {@link ParityCase#expectedFinalState()} against
 *       {@link Fingerprint#finalState()}. For a rejection path this is the seeded pre-state,
 *       unchanged - which is how such a case asserts that nothing was written.</li>
 *   <li><strong>The online response.</strong> {@link ParityCase#expectedResponse()} against
 *       {@link Fingerprint#response()}: every screen send's BMS payload and attribute metadata, the
 *       navigation context, the cursor field and the termination. Several online paths write no
 *       dataset at all, so without this channel they would have nothing asserted about them.</li>
 *   <li><strong>The return code and the emitted lines.</strong> Each line compared on its declared
 *       channel, so an 80-byte working-storage message is never mistaken for the 78-byte screen
 *       field it is truncated into.</li>
 * </ol>
 *
 * <h2>Completeness: what the unit did NOT do is checked too</h2>
 * <p>Every comparison here is a <strong>set</strong> comparison, not a lookup. For each of the two
 * record channels the observed datasets are compared against the expected datasets and the observed
 * row indices against the expected row indices, in both directions. An unexpected dataset, an
 * unexpected row at <em>any</em> index, and every observed write when the case expects none are all
 * differences. A judge that only walked the expectations would report clean on a unit that wrote to a
 * dataset nobody mentioned, wrote an extra row below the highest expected index, or wrote everything
 * while the case expected nothing - three false-pass modes that a parity gate cannot afford.
 *
 * <h2>Raw bytes are authoritative</h2>
 * <p>For a signed zoned field the expectation is <em>canonicalised to bytes</em> and then compared as
 * bytes. Numeric decoding is used to explain a difference, never to excuse one. Two images that
 * decode to the same {@link BigDecimal} can still be different bytes on disk - in a twelve-character
 * {@code S9(10)V99} span the unsigned form {@code "000000019400"} and the positive-overpunch form
 * <code>"00000001940&#123;"</code> both mean 194.00 - and since COBOL writes the overpunch form when
 * it stores into a signed {@code DISPLAY} item, the unsigned one is a real difference in what reached
 * the dataset. Reporting it is the whole point of a byte-level parity gate.
 *
 * <h2>Nothing is padded here</h2>
 * <p>This class performs <strong>no width normalisation whatsoever</strong>. The two recorded
 * fixture-to-copybook deviations are repaired once, at seed time, by
 * {@link ParityCase.Normalisation} - which is their single owner, and which has to be the seeding side
 * because a short row cannot be decoded at all. A width disagreement reaching a comparison is
 * therefore always a difference: either the unit built a record of the wrong size, or the case wrote
 * an expectation at the wrong width. Padding it away here would have made those two indistinguishable
 * from a correctly seeded run, and would have let a case that forgot its normalisation pass anyway.
 *
 * <h2>Comparison semantics, per PICTURE category</h2>
 * <p>The category comes from the {@link FieldSpan} descriptor, so the rule applied to a field is the
 * rule its copybook declaration implies and nothing else.
 * <table border="1">
 *   <caption>How each category is compared</caption>
 *   <tr><th>Category</th><th>Rule</th></tr>
 *   <tr>
 *     <td>{@link PictureKind#ALPHANUMERIC} - {@code PIC X(n)}</td>
 *     <td>Exact equality of the full {@code n}-character image, <strong>including significant
 *         trailing spaces</strong>. Nothing is trimmed. A value differing only in trailing spaces
 *         IS a difference, and so is an expectation written short of the declared width.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link PictureKind#UNSIGNED_NUMERIC} - {@code PIC 9(n)}</td>
 *     <td>Exact equality of the full zero-filled {@code n}-character image. Leading zeros are
 *         significant and are never normalised away.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link PictureKind#SIGNED_SCALED} - {@code PIC S9(p)V99}</td>
 *     <td>Exact equality of the <strong>stored bytes</strong>. An expectation written as a zoned
 *         image of the span's declared width is compared verbatim; an expectation written as a
 *         decimal literal is first encoded to the canonical image the picture implies, through
 *         {@link FixedWidthCodec#encodeSignedZoned}, the same operation the implementation writes
 *         the field with, at {@link CobolDecimal#MONETARY_SCALE} and with
 *         {@link CobolDecimal#COBOL_ROUNDING} - truncation toward zero, because {@code ROUNDED}
 *         appears zero times in all 28 programs. The observed side is never normalised, and the
 *         decoded quantities appear in the <em>explanation</em> of a difference rather than in the
 *         decision to report one.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link PictureKind#FILLER} and report edit masks</td>
 *     <td>Exact string equality. A {@code FILLER} span is an addressable, comparable field, not a
 *         gap. An edited field such as {@code CVTRA07Y}'s {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} is compared as
 *         text because for an edited field the mask <em>is</em> the value.</td>
 *   </tr>
 * </table>
 *
 * <p><strong>The arithmetic of a signed span repays being spelled out, because it is easy to get
 * wrong by one factor of ten.</strong> {@code PIC S9(10)V99} occupies exactly twelve character
 * positions - ten integer digits and two fraction digits - and the sign consumes <em>no position of
 * its own</em>, which is the only reading under which {@code app/cpy/CVACT01Y.cpy} sums to its
 * documented {@code RECLN 300}. So the twelve characters <code>00000001940&#123;</code> carry the
 * digits {@code 000000019400}, whose first ten are the integer part {@code 0000000194} and whose last
 * two are the fraction {@code 00}: the value is <strong>194.00</strong>. Reading the trailing byte as
 * a separate sign character would leave eleven digits and yield 1940.00, and that is exactly the slip
 * to avoid. Nothing in this class performs that arithmetic itself - it is delegated in full to the
 * codec, which is precisely why the differ cannot acquire an off-by-a-factor-of-ten of its own.
 *
 * <h2>Total record width is checked on every record</h2>
 * <p>Before a single field of a record is compared, the record's total width is verified against its
 * layout's declared length. Omitting a {@code FILLER} span shifts every byte offset after it, so a
 * width check is the cheapest possible way to catch that class of defect, and it is reported as
 * {@link DiffKind#RECORD_WIDTH_MISMATCH} rather than as a cascade of field differences. The
 * authoritative widths are Account 300, Card 150, CardXref 50, Customer 500, Transaction 350,
 * DalyTran 350, TranCatBal 50, DisclosureGroup 50, TranType 60, TranCategory 60, SecUser 80 and
 * Trnx 350; and for generated output {@code DALYREJS} 430, {@code TRANREPT} 133, {@code STMTFILE} 80
 * and {@code HTMLFILE} <strong>100</strong>. That last one needs care: {@code app/jcl/CREASTMT.JCL}
 * declares {@code HTMLFILE} twice, at {@code LRECL=80} in the {@code IEFBR14} pre-delete step and at
 * {@code DCB=(LRECL=100,BLKSIZE=800)} in {@code STEP040}, the step that actually creates it. The
 * creating step is authoritative, so 100 wins and 80 is the losing declaration.
 *
 * <h2>Field names are the COBOL names, verbatim</h2>
 * <p>{@code ACCT-EXPIRAION-DATE} is misspelled in {@code app/cpy/CVACT01Y.cpy} and is compared
 * misspelled; {@code CUSTREC}'s {@code CUST-DOB-YYYYMMDD} stays distinct from {@code CVCUS01Y}'s
 * {@code CUST-DOB-YYYY-MM-DD}. Where one record declares several {@code FILLER} spans, this class
 * owns the disambiguating convention - the spans are named {@code FILLER}, {@code FILLER-2},
 * {@code FILLER-3} and so on in copybook declaration order, because a JSON object cannot hold a
 * duplicate key.
 *
 * <h2>A diagnostic never discloses a credential span</h2>
 * <p>The legacy design compares {@code SEC-USR-PWD PIC X(08)} in plaintext and this migration
 * preserves that, so the parity contract carries those eight bytes. A failure report exists to show
 * bytes, which makes it the one place they would reliably escape into a build log or a CI artefact.
 * Every value this class renders therefore goes through {@link Redaction}, which masks the named
 * password fields and the credential <em>span</em> inside a whole-record image. Comparison is
 * unaffected: the raw values are compared byte for byte, so a wrong password is still a difference and
 * is still reported - as a difference in a redacted field, which is all a reviewer needs.
 *
 * <h2>Determinism, statelessness and the closed dependency set</h2>
 * <p>There is no mutable state here, static or otherwise: the only field is an immutable,
 * constructor-injected {@link FixedWidthCodec}, and every call to
 * {@link #compare(ParityCase, Fingerprint)} builds and returns a fresh {@link DiffResult}. Diff order
 * is fully determined - channels in a fixed order, datasets and rows in the expectation's declared
 * order then the observation's, fields in copybook declaration order - so no traversal ever depends on
 * a hash-ordered collection and running the same comparison twice produces byte-identical output.
 * Decoding goes through the hand-written, offset-explicit {@link FixedWidthCodec} and
 * {@link FixedWidthRecord}; no third-party copybook parser is involved, no annotation processor
 * generates any accessor here, and every {@link Charset} is named by the caller and never taken from
 * the platform.
 *
 * @see ParityCase
 * @see FixedWidthCodec
 * @see CobolDecimal
 */
public final class FieldDiffer {

    /**
     * The reserved COBOL name for an unnamed span. A layout may declare it many times and it can
     * never be referenced, which is why the ordinal-suffix convention below exists.
     */
    private static final String FILLER_NAME = "FILLER";

    /**
     * The separator introducing a {@code FILLER} span's ordinal, so the second {@code FILLER} of a
     * record is addressed as {@code FILLER-2}. A hyphen matches COBOL's own word separator, so the
     * synthetic name still reads like a copybook name.
     */
    private static final String FILLER_ORDINAL_SEPARATOR = "-";

    /**
     * The pseudo-dataset a return-code difference is reported against.
     *
     * <p>Angle brackets guarantee this can never collide with a real dataset binding key: those are
     * validated as one to eight upper-case alphanumerics beginning with a letter, so no legitimate
     * key can contain a bracket. The same holds for the other pseudo-scopes below.
     */
    private static final String RETURN_CODE_SCOPE = "<return-code>";

    /** The pseudo-dataset an emitted-message difference is reported against. */
    private static final String MESSAGES_SCOPE = "<messages>";

    /** The pseudo-dataset an online-response difference is reported against. */
    private static final String RESPONSE_SCOPE = "<response>";

    /**
     * The pseudo-field a whole-record difference is reported against - a width mismatch, a missing
     * record or an extra record, none of which belongs to any single field.
     */
    private static final String RECORD_SCOPE_FIELD = "<record>";

    /** The pseudo-field an unexpected whole dataset is reported against. */
    private static final String DATASET_SCOPE_FIELD = "<dataset>";

    /**
     * How the {@link DiffKind#RETURN_CODE_MISMATCH} entry names its subject, so a rendered line reads
     * as prose rather than as a bare pseudo-key.
     */
    private static final String RETURN_CODE_FIELD = "RETURN-CODE";

    /** How a message difference names its subject, mirroring the COBOL verb that emits it. */
    private static final String MESSAGE_FIELD = "DISPLAY";

    /** How a send-count difference names its subject. */
    private static final String SEND_COUNT_FIELD = "SEND-MAP-COUNT";

    /** The channel name used when reporting a difference among the ordered writes. */
    private static final String WRITES_CHANNEL = "writes";

    /** The channel name used when reporting a difference in the final dataset state. */
    private static final String FINAL_STATE_CHANNEL = "final state";

    /**
     * The immutable, hand-written codec every decode in this class goes through. Constructor-injected
     * and never replaced, so there is no mutable state on this object at all and the code page in use
     * cannot change beneath a comparison that is already running.
     */
    private final FixedWidthCodec codec;

    /**
     * Builds a differ over an explicit codec, which is where the code page comes from.
     *
     * @param codec the hand-written fixed-width codec whose {@link FixedWidthCodec#charset()} governs
     *     every byte-to-text conversion this differ performs; never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public FieldDiffer(FixedWidthCodec codec) {
        this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: the differ decodes "
            + "every record through it so that field offsets stay reviewable against the copybook, "
            + "and it is also where the code page comes from");
    }

    /**
     * Builds a differ over a named charset - the convenient form for a test, which knows its fixtures
     * are {@code US-ASCII} and says so rather than relying on a platform default.
     *
     * @param charset the code page of the data being compared; never {@code null}
     * @return a differ whose codec uses {@code charset}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static FieldDiffer forCharset(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required and is never defaulted: the ASCII "
            + "fixtures are US-ASCII and the EBCDIC datasets are IBM037, and which one applies is "
            + "the caller's knowledge, not this class's");
        return new FieldDiffer(new FixedWidthCodec(charset));
    }

    /**
     * The codec this differ decodes through, exposed so a caller can encode an expectation the same
     * way the differ will decode it.
     *
     * @return the injected codec, never {@code null}
     */
    public FixedWidthCodec codec() {
        return codec;
    }

    /**
     * Compares one case's expectations against one observed fingerprint and returns every difference
     * found, in a fully determined order.
     *
     * <p>The traversal is, in order:
     * <ol>
     *   <li>the ordered writes - each expectation in declared order, then every observed write the
     *       case did not expect;</li>
     *   <li>the final dataset state - the same two passes;</li>
     *   <li>the online response - navigation, the send sequence, the cursor and the termination;</li>
     *   <li>the return code;</li>
     *   <li>the emitted messages - the count first, then each position in ascending order.</li>
     * </ol>
     * Nothing in that traversal consults a hash-ordered collection, so the result is reproducible
     * run to run and machine to machine.
     *
     * <p>This method does not throw for a malformed expectation or an undecodable field. Such a
     * finding is <em>reported</em> as a difference - {@link DiffKind#MALFORMED_EXPECTATION} or
     * {@link DiffKind#UNDECODABLE_FIELD} - because an exception escaping here would abandon the
     * comparison mid-way and hide every difference after it, which is the opposite of what a judge
     * should do. The gate still fails, loudly, and the reviewer still sees the whole picture.
     *
     * @param parityCase the case whose expectations are authoritative; never {@code null}
     * @param fingerprint what the unit under test actually produced; never {@code null}
     * @return a fresh result carrying every difference in traversal order
     * @throws NullPointerException if either argument is {@code null}
     */
    public DiffResult compare(ParityCase parityCase, Fingerprint fingerprint) {
        Objects.requireNonNull(parityCase, "A ParityCase is required: it is the authoritative "
            + "expectation side of the comparison");
        Objects.requireNonNull(fingerprint, "A Fingerprint is required: it is what the unit under "
            + "test actually produced. A unit that writes nothing still reports its return code, so "
            + "use Fingerprint.ofReturnCode(int) rather than passing null");

        List<Diff> diffs = new ArrayList<>();

        compareChannel(parityCase.expectedWrites(), fingerprint.writes(), WRITES_CHANNEL,
            parityCase.normalisations(), datasetExpectationsOn(parityCase, DatasetChannel.WRITES),
            diffs);
        compareChannel(parityCase.expectedFinalState(), fingerprint.finalState(),
            FINAL_STATE_CHANNEL, parityCase.normalisations(),
            datasetExpectationsOn(parityCase, DatasetChannel.FINAL_STATE), diffs);
        compareResponse(parityCase.expectedResponse(), fingerprint.response(), diffs);
        compareReturnCode(parityCase, fingerprint, diffs);
        compareMessages(parityCase, fingerprint, diffs);

        return new DiffResult(parityCase.program(), parityCase.caseId(), diffs);
    }

    // ===============================================================================================
    // Record channels. Each is compared as a complete SET, in both directions.
    // ===============================================================================================

    /**
     * Compares one record channel: every expectation, then every observation the case did not expect.
     *
     * <p>The second pass is what makes the comparison complete, and it is the half a lookup-based
     * judge omits. Three false-pass modes live there and all three are closed here: a dataset the
     * case never mentions, a row at an index no expectation reaches - <em>including</em> an index
     * below the highest expected one, which a "beyond the last expected row" check would sail past -
     * and the degenerate case of a unit that wrote a great deal while the expectations were empty.
     *
     * @param expectations the case's expectations for this channel, in declared order
     * @param observed     what the unit produced on this channel, keyed by dataset
     * @param channel      the channel name, quoted so a difference says which one it belongs to
     * @param diffs        the accumulator every difference is appended to, in traversal order
     */
    private void compareChannel(List<ExpectedRecord> expectations,
                                Map<String, DatasetOutput> observed,
                                String channel,
                                List<ParityCase.DatasetNormalisation> normalisations,
                                List<ExpectedDataset> datasetExpectations,
                                List<Diff> diffs) {
        // Expected row indices per dataset, in first-appearance order, built during the first pass
        // and consumed by the second so the two cannot disagree.
        Map<String, Set<Integer>> expectedRows = new LinkedHashMap<>();

        // A dataset named by a dataset-level expectation is a dataset the case IS about, even when it
        // pins no row of it - so it is entered here with an empty row set. Two things follow, and both
        // are the point of the entry: the dataset is no longer reported as one the case never mentions,
        // which is what a legitimately empty output used to be reported as, and any row it does hold is
        // still reported as unaccounted for, so a row count is never a substitute for reading bytes.
        for (ExpectedDataset expectation : datasetExpectations) {
            expectedRows.computeIfAbsent(expectation.dataset(), key -> new LinkedHashSet<>());
        }

        for (ExpectedRecord expectation : expectations) {
            expectedRows.computeIfAbsent(expectation.dataset(), key -> new LinkedHashSet<>())
                .add(expectation.rowIndex());
            compareExpectedRecord(observed, expectation, channel, normalisations, diffs);
        }

        compareDatasetExpectations(datasetExpectations, observed, channel, diffs);
        reportUnexpectedOutput(observed, expectedRows, channel, diffs);
    }

    /**
     * Compares each dataset-level expectation against the observed channel: the dataset's existence
     * first, then its row count, then its declared width when the case pins one.
     *
     * <p>Independent of the row comparison, deliberately. A row-driven scan cannot see a dataset that
     * holds zero rows - there is no row to iterate to - so "the unit opened this output and wrote
     * nothing to it" is unreachable from the row side however carefully it is written. Comparing the
     * dataset itself is the only way to assert it, and it is a real behaviour: a job that creates its
     * reject file and rejects nothing is not the same as a job that never opened it, and a
     * {@code COND} gate downstream reacts to the difference.
     *
     * <p>Existence is checked first and stops the comparison for that dataset. A count and a width
     * asserted against a dataset that does not exist would be two more differences saying the same
     * thing as the first.
     *
     * @param datasetExpectations the case's dataset-level expectations for this channel
     * @param observed            what the unit produced on this channel, keyed by dataset
     * @param channel             the channel name, quoted in the failure text
     * @param diffs               the accumulator every difference is appended to
     */
    private static void compareDatasetExpectations(List<ExpectedDataset> datasetExpectations,
                                                   Map<String, DatasetOutput> observed,
                                                   String channel,
                                                   List<Diff> diffs) {
        for (ExpectedDataset expectation : datasetExpectations) {
            String dataset = expectation.dataset();
            DatasetOutput output = observed.get(dataset);
            if (output == null) {
                diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                    Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE,
                    expectation.rowCount() + " row(s)", null, DiffKind.MISSING_DATASET,
                    "the case expects dataset " + dataset + " on the " + channel + " channel with "
                        + expectation.rowCount() + " row(s), and the fingerprint carries "
                        + describeDatasetKeys(observed) + ". A dataset-level expectation asserts that "
                        + "the dataset itself was opened or created, which is the one statement no row "
                        + "expectation can make: a dataset holding no row has no row to address. Report "
                        + "the dataset through the recorder - openedWithoutWriting for an output that "
                        + "was created and never written to - rather than leaving it out of the "
                        + "fingerprint."));
                continue;
            }
            if (output.rowCount() != expectation.rowCount()) {
                diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                    Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE,
                    expectation.rowCount() + " row(s)",
                    output.rowCount() + " row(s)", DiffKind.DATASET_ROW_COUNT_MISMATCH,
                    "dataset " + dataset + " holds " + output.rowCount() + " row(s) on the " + channel
                        + " channel where the case expects exactly " + expectation.rowCount()
                        + ". Counted once for the dataset: this is a different finding from a row whose "
                        + "bytes are wrong, and an expected count of zero is the assertion that the "
                        + "dataset exists and produced nothing."));
            }
            Integer expectedWidth = expectation.recordLength();
            if (expectedWidth != null && output.layout().recordLength() != expectedWidth) {
                diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                    Diff.NOT_APPLICABLE, expectedWidth, Integer.toString(expectedWidth),
                    Integer.toString(output.layout().recordLength()),
                    DiffKind.DATASET_WIDTH_MISMATCH,
                    "dataset " + dataset + " was reported on the " + channel + " channel with a "
                        + output.layout().recordLength() + "-byte layout where the case expects "
                        + expectedWidth + ". The dataset's declared width is the only width an empty "
                        + "dataset has, so pinning it is how a case pins the identity of an output it "
                        + "expects to be empty - a 430-byte reject file and a 133-byte report are not "
                        + "interchangeable just because both are empty."));
            }
        }
    }

    /**
     * The dataset-level expectations a case declares for one channel, in declaration order.
     *
     * @param parityCase the case
     * @param channel    the channel to select
     * @return the matching expectations, empty when the case declares none for that channel
     */
    private static List<ExpectedDataset> datasetExpectationsOn(ParityCase parityCase,
                                                               DatasetChannel channel) {
        List<ExpectedDataset> selected = new ArrayList<>();
        for (ExpectedDataset expectation : parityCase.expectedDatasets()) {
            if (expectation.channel() == channel) {
                selected.add(expectation);
            }
        }
        return selected;
    }

    /**
     * Compares one expectation against the observed record it addresses.
     *
     * <p>Runs in three stages that deliberately stop early. A missing record is one difference, not a
     * difference per field: the fields of a record that was never written are not independently wrong,
     * they are collectively absent. A width mismatch likewise stops the comparison for that record,
     * because once the total width is wrong every offset after the missing span addresses the wrong
     * bytes and the resulting field differences would be noise obscuring the single real finding.
     *
     * @param observed    what the unit produced on this channel, searched for the addressed dataset
     * @param expectation the single expectation being evaluated
     * @param channel     the channel name, quoted in the failure text
     * @param diffs       the accumulator every difference is appended to, in traversal order
     */
    private void compareExpectedRecord(Map<String, DatasetOutput> observed,
                                       ExpectedRecord expectation,
                                       String channel,
                                       List<ParityCase.DatasetNormalisation> normalisations,
                                       List<Diff> diffs) {
        String dataset = expectation.dataset();
        int rowIndex = expectation.rowIndex();

        DatasetOutput output = observed.get(dataset);
        if (output == null) {
            // No layout is known here: the unit produced nothing for this dataset, so there is no
            // DatasetOutput to take one from. The expectation's image is still masked, by the
            // dataset-and-width rule alone.
            diffs.add(missingRecord(dataset, rowIndex, expectation, null, channel,
                "the unit wrote no record at all to dataset " + dataset + " on the " + channel
                    + " channel: the fingerprint carries " + describeDatasetKeys(observed)));
            return;
        }

        if (!output.hasRow(rowIndex)) {
            diffs.add(missingRecord(dataset, rowIndex, expectation, output.layout(), channel,
                "dataset " + dataset + " holds " + output.rowCount() + " row(s), so there is no row "
                    + "at 0-based index " + rowIndex));
            return;
        }

        RecordLayout layout = output.layout();
        byte[] observedRow = output.row(rowIndex);
        if (observedRow.length != layout.recordLength()) {
            diffs.add(widthMismatch(dataset, rowIndex, layout, observedRow.length, channel,
                "the observed record is " + observedRow.length + " byte(s) wide but "
                    + describeLayout(layout) + " declares " + layout.recordLength()
                    + ". A record short by exactly one span almost always means a FILLER was "
                    + "omitted, which shifts every offset after it - and a FILLER the copybook "
                    + "declares must not be padded away, because its bytes are part of the record "
                    + "the COBOL writes. Note that nothing is padded at comparison time: "
                    + describeNormalisations(normalisations, dataset)
                    + ", and the two recorded fixture-to-copybook deviations are repaired once at "
                    + "SEED time by ParityCase.Normalisation, so a width disagreement here is a "
                    + "genuine difference and not a missing declaration."));
            return;
        }

        FixedWidthRecord record = codec.wrap(observedRow, layout);
        Map<String, FieldSpan> addressable = addressableSpans(layout);

        Set<String> comparedFields =
            compareNamedFields(expectation, layout, addressable, record, channel, diffs);
        compareRecordImage(expectation, layout, addressable, record, comparedFields, channel, diffs);
        requireCompleteCoverage(expectation, layout, addressable, comparedFields, channel, diffs);
    }

    /**
     * Reports an expectation that leaves part of the record it addresses unasserted.
     *
     * <p>This is the check that makes "the diff count is zero" mean what it says. Every other
     * comparison here answers "does what the case pinned match?", and answering yes says nothing at all
     * about the bytes the case did not pin. A case pinning two fields of a 300-byte account row leaves
     * 289 bytes unchecked; the {@code FILLER} span could be zeroes instead of spaces, a monetary field
     * could be off by a factor of ten, and the count would still be zero.
     *
     * <p>An expectation is complete in one of exactly two ways, and one of them is required:
     * <ul>
     *   <li>it pins {@code expectedBytes}, which is the whole record by definition - and is also how a
     *       case proves the total width and therefore that {@code FILLER} was emitted at all; or</li>
     *   <li>its named fields between them cover every byte of the record.</li>
     * </ul>
     *
     * <p>Coverage is computed <strong>per byte</strong> rather than per span, which is what makes it
     * correct in the presence of the two things a span-counting check would get wrong. A
     * {@code REDEFINES} overlay covers the same bytes as the span it redefines, so pinning either one
     * covers those bytes and pinning both is not required - {@code CVCRD01Y}'s
     * {@code CC-ACCT-ID}/{@code CC-ACCT-ID-N} pair is a real instance. And a {@code FILLER} span is
     * bytes like any other, so a record whose every other field is pinned is still incomplete until the
     * {@code FILLER} is - which is precisely the span most likely to be wrong and least likely to be
     * noticed.
     *
     * <p>Reported as <strong>one</strong> difference for the record, naming every uncovered span with
     * its offset and length, because "this expectation does not assert enough" is one defect in one
     * fixture however many spans it left out.
     *
     * @param expectation    the expectation being evaluated
     * @param layout         the record's layout, named in the failure text
     * @param addressable    every span keyed by its addressing name, in copybook order
     * @param comparedFields the names the field pass actually compared
     * @param channel        the channel name, quoted in the failure text
     * @param diffs          the accumulator every difference is appended to
     */
    private static void requireCompleteCoverage(ExpectedRecord expectation,
                                                RecordLayout layout,
                                                Map<String, FieldSpan> addressable,
                                                Set<String> comparedFields,
                                                String channel,
                                                List<Diff> diffs) {
        if (expectation.expectedBytes() != null) {
            return;
        }
        boolean[] covered = new boolean[layout.recordLength()];
        for (String fieldName : comparedFields) {
            FieldSpan span = addressable.get(fieldName);
            if (span == null) {
                continue;
            }
            int end = Math.min(span.offset() + span.length(), covered.length);
            for (int index = span.offset(); index < end; index++) {
                covered[index] = true;
            }
        }

        List<String> uncovered = new ArrayList<>();
        int uncoveredBytes = 0;
        for (Map.Entry<String, FieldSpan> entry : addressable.entrySet()) {
            FieldSpan span = entry.getValue();
            int missing = 0;
            int end = Math.min(span.offset() + span.length(), covered.length);
            for (int index = span.offset(); index < end; index++) {
                if (!covered[index]) {
                    missing++;
                }
            }
            if (missing > 0) {
                uncovered.add(entry.getKey() + " (offset " + span.offset() + ", length "
                    + span.length() + ')');
                uncoveredBytes += missing;
            }
        }
        if (uncovered.isEmpty()) {
            return;
        }

        diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), RECORD_SCOPE_FIELD, 0,
            layout.recordLength(), layout.recordLength() + " byte(s) accounted for",
            (layout.recordLength() - uncoveredBytes) + " byte(s) accounted for",
            DiffKind.INCOMPLETE_EXPECTATION,
            "the " + channel + " expectation accounts for only "
                + (layout.recordLength() - uncoveredBytes) + " of " + describeLayout(layout)
                + "'s " + layout.recordLength() + " byte(s), leaving " + uncoveredBytes
                + " unasserted in: " + String.join(", ", uncovered)
                + ". A byte no expectation covers is a byte that can be wrong while the diff count is "
                + "zero, and the spans that go unpinned are the ones hardest to notice - a FILLER "
                + "written as zeroes instead of spaces, or a monetary span out by a factor of ten. "
                + "Complete the expectation either way: pin \"expectedBytes\" with the whole record "
                + "image, which also proves the total width and therefore that every FILLER was "
                + "emitted, or name the remaining fields in \"fields\". A REDEFINES overlay covers the "
                + "same bytes as the span it redefines, so pinning either one of a pair is enough."));
    }

    /**
     * Reports every observed dataset and row the case did not expect on this channel.
     *
     * <p>Two passes, in a fixed order: first the datasets the case addresses - reporting each row it
     * never accounted for - and then the datasets it never mentions at all. Within each pass the
     * observed datasets are visited in the order the fingerprint declared them and rows in ascending
     * index, so this adds nothing hash-ordered to the traversal and the rendered order is stable
     * whatever order the unit happened to open its outputs in.
     *
     * <p>The two passes report at different granularities, deliberately. An unaccounted row inside a
     * dataset the case is about is reported per row, because each one is a separate thing the unit
     * wrote that the case has no answer for. A dataset the case never mentions is reported once, with
     * its row count, because "the unit touched an output this case is not about" is one defect no
     * matter how many rows followed - enumerating them would make the diff count a function of the
     * unit's output volume rather than of the number of things wrong.
     *
     * @param observed     what the unit produced on this channel
     * @param expectedRows the row indices each dataset's expectations addressed
     * @param channel      the channel name, quoted in the failure text
     * @param diffs        the accumulator every difference is appended to
     */
    private void reportUnexpectedOutput(Map<String, DatasetOutput> observed,
                                        Map<String, Set<Integer>> expectedRows,
                                        String channel,
                                        List<Diff> diffs) {
        // Pass one: datasets the case DOES address, reporting each row it never accounted for. These
        // come first so a rendered failure reads outwards - what went wrong inside the datasets the
        // case is about, and only then which datasets it was never about at all.
        for (Map.Entry<String, DatasetOutput> entry : observed.entrySet()) {
            String dataset = entry.getKey();
            DatasetOutput output = entry.getValue();
            Set<Integer> expected = expectedRows.get(dataset);
            if (expected == null) {
                continue;
            }

            for (int rowIndex = 0; rowIndex < output.rowCount(); rowIndex++) {
                if (expected.contains(rowIndex)) {
                    continue;
                }
                byte[] extra = output.row(rowIndex);
                diffs.add(new Diff(dataset, rowIndex, RECORD_SCOPE_FIELD, Diff.NOT_APPLICABLE,
                    extra.length, null,
                    maskImageOf(dataset, output.layout(), imageOf(extra)),
                    DiffKind.EXTRA_RECORD,
                    "the unit wrote " + output.rowCount() + " row(s) to dataset " + dataset
                        + " but the case expects " + expected.size() + ": the " + channel + " row at "
                        + "0-based index " + rowIndex + " is one no expectation addresses. The case "
                        + "addresses row(s) " + new TreeSet<>(expected) + " of that dataset. Extra "
                        + "output is a parity failure in its own right, and it is reported wherever "
                        + "the row sits, not only beyond the highest expected index, because an extra "
                        + "row inserted among the expected ones is exactly as wrong and considerably "
                        + "easier to miss."));
            }
        }

        // Pass two: datasets the case never mentions at all. One finding each, carrying the row count,
        // because "the unit touched a dataset this case is not about" is a single defect however many
        // rows it wrote - the same principle that makes a missing record one difference rather than one
        // per pinned field. The count is stated in the message and carried in the observed side, so
        // nothing about the scale of the violation is lost by not enumerating the rows. Crucially the
        // pass is driven by what the unit produced rather than by the expectation keys, which is what
        // lets it see a dataset that was opened and written to with no expectation to look it up from -
        // including one holding zero rows, the single shape a row-driven scan can never reach.
        for (Map.Entry<String, DatasetOutput> entry : observed.entrySet()) {
            String dataset = entry.getKey();
            DatasetOutput output = entry.getValue();
            if (expectedRows.containsKey(dataset)) {
                continue;
            }
            diffs.add(new Diff(dataset, Diff.NOT_APPLICABLE, DATASET_SCOPE_FIELD,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, null,
                output.rowCount() + " row(s)", DiffKind.EXTRA_DATASET,
                "the unit produced " + output.rowCount() + " row(s) on the " + channel
                    + " channel for dataset " + dataset + ", which the case expects nothing from. "
                    + "Touching a dataset the case does not mention is a parity failure in its own "
                    + "right: the expected datasets on this channel are "
                    + describeExpectedDatasets(expectedRows)
                    + ". An empty expectation is a positive assertion that nothing was produced, "
                    + "not an absence of interest."));
        }
    }

    /**
     * Compares every field the expectation names, in <strong>copybook declaration order</strong>.
     *
     * <p>Copybook order rather than fixture order is a deliberate choice: it means a rendered failure
     * reads down the record the same way the copybook does, so a reviewer holding the copybook open
     * beside the output can follow both together. Order is still completely determined either way -
     * what matters for reproducibility is that no traversal here depends on a hash-ordered collection,
     * and none does.
     *
     * <p>Expectation keys that name no span at all are handled afterwards, in the fixture's own
     * declared order, and reported as {@link DiffKind#FIELD_ABSENT_IN_FINGERPRINT} with every
     * addressable name listed - which is what turns a misspelling into a five-second fix.
     *
     * @param expectation the expectation whose {@code fields} are being evaluated
     * @param layout      the record's layout, named in the failure text
     * @param addressable every span keyed by the name an expectation addresses it by, in copybook
     *                    order - which is what makes this pass copybook-ordered
     * @param record      the decoded observed record
     * @param channel     the channel name, quoted in the failure text
     * @param diffs       the accumulator every difference is appended to
     * @return the set of field names actually compared, so the whole-record pass that follows does not
     *     report the same span twice
     */
    private Set<String> compareNamedFields(ExpectedRecord expectation,
                                           RecordLayout layout,
                                           Map<String, FieldSpan> addressable,
                                           FixedWidthRecord record,
                                           String channel,
                                           List<Diff> diffs) {
        Map<String, String> expectedFields = expectation.fields();
        Set<String> compared = new LinkedHashSet<>();
        if (expectedFields.isEmpty()) {
            return compared;
        }

        // Pass one: copybook order, over the spans the expectation actually pins.
        for (Map.Entry<String, FieldSpan> span : addressable.entrySet()) {
            String fieldName = span.getKey();
            if (!expectedFields.containsKey(fieldName)) {
                continue;
            }
            compared.add(fieldName);
            compareField(expectation, fieldName, span.getValue(), expectedFields.get(fieldName),
                record, channel, diffs);
        }

        // Pass two: whatever the expectation named that pass one did not reach. Every span the layout
        // declares is addressable - REDEFINES overlays under their own names, FILLER spans under their
        // ordinal names - so anything still unmatched here names no span at all.
        for (Map.Entry<String, String> entry : expectedFields.entrySet()) {
            String fieldName = entry.getKey();
            if (compared.contains(fieldName)) {
                continue;
            }
            diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), fieldName,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE,
                Redaction.maskFieldValue(fieldName, entry.getValue()), null,
                DiffKind.FIELD_ABSENT_IN_FINGERPRINT,
                "the " + channel + " expectation pins '" + fieldName + "' but "
                    + describeLayout(layout) + " declares no such span, so nothing was decoded for "
                    + "it. Field names are the copybook's own, verbatim and case-sensitive - the "
                    + "misspelled ACCT-EXPIRAION-DATE is spelled that way on purpose - and a "
                    + "record's several FILLER spans are addressed as FILLER, FILLER"
                    + FILLER_ORDINAL_SEPARATOR + "2 and so on in declaration order. Addressable "
                    + "here: " + String.join(", ", addressable.keySet())));
        }
        return compared;
    }

    /**
     * Compares a whole-record image expectation, localising any difference to the span that carries
     * it.
     *
     * <p>An {@code expectedBytes} value pins the complete record, which is how a case asserts the
     * total width and therefore how it proves a {@code FILLER} was emitted at all. It is nonetheless
     * <strong>not</strong> compared as one opaque string: the image is walked span by span in copybook
     * order so that a difference is reported against the field that holds it, with that field's
     * declared offset and length. That keeps the field-granularity guarantee intact even for an
     * expectation phrased at record level.
     *
     * <p>Spans already pinned individually by the same expectation are skipped, so a field that is
     * wrong in both {@code fields} and {@code expectedBytes} counts once rather than twice and the
     * diff count stays a count of distinct findings. That skip is only safe because the field pass
     * compares bytes: a pass that could accept a byte-different field as numerically equal would
     * suppress the image's own finding as well, and both halves of that defect are closed together.
     *
     * @param expectation    the expectation whose {@code expectedBytes} is being evaluated
     * @param layout         the record's layout
     * @param addressable    every span keyed by its addressing name, in copybook order
     * @param record         the decoded observed record
     * @param comparedFields the names the field pass already reported on, which are skipped here
     * @param channel        the channel name, quoted in the failure text
     * @param diffs          the accumulator every difference is appended to
     */
    private void compareRecordImage(ExpectedRecord expectation,
                                    RecordLayout layout,
                                    Map<String, FieldSpan> addressable,
                                    FixedWidthRecord record,
                                    Set<String> comparedFields,
                                    String channel,
                                    List<Diff> diffs) {
        String expectedImage = expectation.expectedBytes();
        if (expectedImage == null) {
            return;
        }

        String dataset = expectation.dataset();
        int rowIndex = expectation.rowIndex();
        // Encoded with the codec's own charset, never a platform default, because the width that has
        // to match the layout is a width in bytes and not in characters.
        byte[] expectedBytes = expectedImage.getBytes(codec.charset());
        if (expectedBytes.length != layout.recordLength()) {
            diffs.add(widthMismatch(dataset, rowIndex, layout, expectedBytes.length, channel,
                "the expected record image is " + expectedBytes.length + " byte(s) wide but "
                    + describeLayout(layout) + " declares " + layout.recordLength()
                    + ". The expectation itself is the wrong width here, so it cannot be compared "
                    + "against a correctly built record. Write it out to the full declared width: "
                    + "the two recorded fixture-to-copybook deviations are repaired at SEED time by "
                    + "ParityCase.Normalisation and never at comparison time, so an expectation is "
                    + "always stated at the copybook's width."));
            return;
        }

        FixedWidthRecord expected = codec.wrap(expectedBytes, layout);
        for (Map.Entry<String, FieldSpan> entry : addressable.entrySet()) {
            String fieldName = entry.getKey();
            if (comparedFields.contains(fieldName)) {
                continue;
            }
            FieldSpan span = entry.getValue();
            if (span.redefinition()) {
                // An overlay shares bytes a storage span already covers; comparing it as well would
                // report the same bytes twice.
                continue;
            }
            compareField(expectation, fieldName, span, expected.readSpan(span), record, channel,
                diffs);
        }
    }


    // ===============================================================================================
    // Field-level comparison. One difference per field, always, and always on bytes.
    // ===============================================================================================

    /**
     * Compares one field. The comparison is of characters in every category; the category only
     * decides how an expectation is <em>canonicalised</em> first and how a difference is explained.
     *
     * <p>The actual value is read through {@link FixedWidthRecord#readSpan(FieldSpan)}, which is
     * documented as <strong>untrimmed</strong>. That is the whole point. A COBOL {@code PIC X} field
     * is space-padded to its declared width and that padding is part of its value; trimming it here
     * would discard exactly the bytes a parity comparison exists to check. With 2,795 {@code MOVE}
     * sites in the 28 programs - truncating on the right for alphanumeric receivers and on the left
     * for numeric ones - padding and truncation are the dominant parity risk in this codebase, far
     * ahead of arithmetic, and the only defence is to compare at full declared width every time.
     *
     * @param expectation   the expectation this field belongs to, for the dataset and row it names
     * @param fieldName     the name the expectation addressed the span by, which may be a
     *                      {@code FILLER} ordinal name rather than a copybook name
     * @param span          the descriptor supplying the offset, the width and the PICTURE category
     * @param expectedValue the expected value exactly as the case carries it
     * @param record        the decoded observed record
     * @param channel       the channel name, quoted in the failure text
     * @param diffs         the accumulator a difference is appended to
     */
    private void compareField(ExpectedRecord expectation,
                              String fieldName,
                              FieldSpan span,
                              String expectedValue,
                              FixedWidthRecord record,
                              String channel,
                              List<Diff> diffs) {
        String actual = record.readSpan(span);
        if (span.kind() == PictureKind.SIGNED_SCALED) {
            compareSignedScaled(expectation, fieldName, span, expectedValue, actual, channel, diffs);
            return;
        }
        if (expectedValue.equals(actual)) {
            return;
        }
        diffs.add(valueMismatch(expectation, fieldName, span, expectedValue, actual,
            textMismatchExplanation(span, expectedValue, actual, channel)));
    }

    /**
     * Compares a {@code PIC S9(p)V(s)} field by its <strong>stored bytes</strong>, with the numeric
     * reading used only to explain a difference.
     *
     * <h2>Why bytes and not values</h2>
     * <p>The gate this class computes is byte-for-byte parity with what the COBOL writes, so the
     * question a signed field poses is "are these the same stored bytes", not "are these the same
     * quantity". The two questions have different answers, and the difference is not academic:
     * <ul>
     *   <li>the unsigned zone-F image {@code "000000019400"} and the positive-overpunch image
     *       <code>"00000001940&#123;"</code> both <em>denote</em> 194.00, but only one of them is what
     *       a COBOL program storing into {@code PIC S9(10)V99} produces, and the fixtures settle
     *       which: {@code app/data/ASCII/acctdata.txt} holds 250 <code>&#123;</code> and no
     *       bare-digit trailing byte in any signed field;</li>
     *   <li><code>"0000000000&#125;"</code> and <code>"0000000000&#123;"</code> both <em>denote</em>
     *       zero, and a {@link BigDecimal} cannot even hold the distinction, yet they are eleven
     *       bytes against eleven different bytes.</li>
     * </ul>
     * A numeric comparison declares both of those pairs equal, which means a Java unit that wrote the
     * wrong zone, or lost the sign of a zero, would pass the gate that exists to catch exactly that.
     * So the comparison is on bytes, and the decoded values appear in the <em>explanation</em> of a
     * difference rather than in the decision to report one.
     *
     * <h2>What an expectation may say</h2>
     * <p>Both shapes a fixture author may write are still accepted, and each has an exact meaning:
     * <ul>
     *   <li>a <strong>zoned image</strong> of exactly the span's declared width is taken as the bytes
     *       themselves and compared verbatim. This is how an expectation pins an unusual stored form
     *       on purpose - the unsigned zone-F rendering of a field written by a program that treated
     *       the picture as unsigned, for instance;</li>
     *   <li>a plain <strong>decimal literal</strong> such as {@code "194.00"}, {@code "-919.00"} or
     *       {@code "-0.00"} is <em>encoded</em> to the canonical image the picture implies, through
     *       the same codec the implementation writes with, and that image is compared. So a literal
     *       states a value and gets the one stored form a COBOL store produces for it - including
     *       <code>"0000000000&#125;"</code> for a negative zero, which is why the literal reader
     *       honours a leading minus even when every digit is zero.</li>
     * </ul>
     * Nothing is normalised on the observed side. Whatever the unit wrote is what is compared.
     *
     * <p>The scale is {@link CobolDecimal#MONETARY_SCALE}. A {@link FieldSpan} carries {@code p + s}
     * as one width and no separate scale, and the evidence says that is sufficient: every signed
     * decimal PICTURE in this codebase is {@code V99} - the only three forms present are
     * {@code S9(10)V99}, {@code S9(09)V99} and {@code S9(9)V99} - so scale 2 is not an assumption
     * about a field, it is the measured property of every field of this kind. A scaleless signed
     * span, should one ever be declared, is still compared exactly by pinning it as a raw image.
     *
     * @param expectation   the expectation this field belongs to
     * @param fieldName     the name the expectation addressed the span by
     * @param span          the descriptor supplying the offset and the {@code p + s} width
     * @param expectedValue the expectation, either a zoned image of the span's width or a decimal
     *                      literal
     * @param actual        the span's observed characters, untrimmed, sign overpunch intact
     * @param channel       the channel name, quoted in the failure text
     * @param diffs         the accumulator a difference is appended to
     */
    private void compareSignedScaled(ExpectedRecord expectation,
                                     String fieldName,
                                     FieldSpan span,
                                     String expectedValue,
                                     String actual,
                                     String channel,
                                     List<Diff> diffs) {
        if (expectedValue.equals(actual)) {
            return;
        }

        int scale = CobolDecimal.MONETARY_SCALE;
        FixedWidthCodec.SignedZoned actualValue;
        try {
            actualValue = codec.decodeSignedZoned(actual, scale);
        } catch (IllegalArgumentException undecodable) {
            // The observed side is not a signed zoned image at all. That is a more specific finding
            // than "wrong value" - the field is corrupt rather than merely incorrect - so it is
            // reported as its own kind.
            diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), fieldName, span.offset(),
                span.length(), Redaction.maskFieldValue(fieldName, expectedValue),
                Redaction.maskFieldValue(fieldName, actual), DiffKind.UNDECODABLE_FIELD,
                "the observed bytes are not a valid signed zoned DISPLAY image for " + span.describe()
                    + " on the " + channel + " channel, so no value can be read from them at all. "
                    + signHint(actual) + " The codec reports: " + undecodable.getMessage()));
            return;
        }

        String expectedImage;
        try {
            expectedImage = canonicalSignedImage(expectedValue, span, scale);
        } catch (IllegalArgumentException | ArithmeticException malformed) {
            diffs.add(new Diff(expectation.dataset(), expectation.rowIndex(), fieldName, span.offset(),
                span.length(), Redaction.maskFieldValue(fieldName, expectedValue),
                Redaction.maskFieldValue(fieldName, actual), DiffKind.MALFORMED_EXPECTATION,
                "the " + channel + " expectation for " + span.describe() + " is neither a zoned "
                    + "image of the span's " + span.length() + " declared character(s) nor a "
                    + "decimal literal that can be encoded into one, so it cannot be compared. "
                    + "Write either the raw image - for example \"00000001940{\", whose trailing "
                    + "'{' is a positive-zero overpunch and which denotes 194.00 in a "
                    + "twelve-character S9(10)V99 span - or a decimal literal such as "
                    + "\"194.00\". Rejected because: " + malformed.getMessage()));
            return;
        }

        if (expectedImage.equals(actual)) {
            return;
        }
        diffs.add(valueMismatch(expectation, fieldName, span, expectedImage, actual,
            signedMismatchExplanation(span, expectedValue, expectedImage, actual, actualValue, scale,
                channel)));
    }

    /**
     * Reduces a signed-field expectation to the exact bytes it stands for.
     *
     * <p>An expectation that is already a valid zoned image of the span's declared width <em>is</em>
     * the bytes, and is returned untouched - that is how a fixture pins a specific stored form.
     * Anything else is read as a decimal literal and encoded to the canonical image the picture
     * implies, through {@link FixedWidthCodec#encodeSignedZoned}, which is the same operation the
     * implementation writes the field with. The literal reader is
     * {@link FixedWidthCodec.SignedZoned#ofLiteral(String)} rather than a bare {@link BigDecimal},
     * so an expectation of {@code "-0.00"} encodes to a negative zero instead of quietly becoming a
     * positive one.
     *
     * @param value the expectation exactly as the case carries it
     * @param span  the descriptor supplying the {@code p + s} width
     * @param scale the field's declared scale
     * @return the bytes the expectation stands for, as characters
     * @throws IllegalArgumentException if the value is neither a valid zoned image of the span's
     *     width nor a parseable decimal literal
     */
    private String canonicalSignedImage(String value, FieldSpan span, int scale) {
        if (value.length() == span.length()) {
            try {
                codec.decodeSignedZoned(value, scale);
                return value;
            } catch (IllegalArgumentException notAZonedImage) {
                // Same width but not a zoned image - a literal such as "0000001940.00" for instance.
                // Fall through to the literal reading rather than rejecting a legitimate expectation.
                return canonicalImageOfLiteral(value, span, scale);
            }
        }
        return canonicalImageOfLiteral(value, span, scale);
    }

    /** Encodes a decimal literal to the one stored image a COBOL store into this span produces. */
    private String canonicalImageOfLiteral(String value, FieldSpan span, int scale) {
        return codec.encodeSignedZoned(FixedWidthCodec.SignedZoned.ofLiteral(value),
            span.length() - scale, scale);
    }

    /**
     * Explains a signed-field difference in bytes, and says plainly when the two sides agree on the
     * value but disagree on how it is stored.
     *
     * <p>That case is the one a reader is most likely to mistake for a false positive, so it is
     * named explicitly along with the two stored forms that produce it: an unsigned zone-F rendering
     * where the signed form is expected, and a negative zero against a positive zero. Both are real
     * parity failures - the bytes the unit wrote are not the bytes the COBOL writes - and neither is
     * visible to a comparison of values.
     *
     * <p>The wording is deliberately redundant, and that is not an accident of drafting. A signed
     * difference is reported to a reader who has to decide, from this sentence alone, whether the unit
     * wrote the wrong number or the wrong encoding of the right number, so the message names it both
     * ways: the quantities are stated, the two images are stated, and the conclusion - value agrees,
     * representation does not; or the values differ as well as the bytes - is stated in words as well.
     * The one phrase that is never emitted when the quantities differ is "right value but the wrong
     * bytes", because that would be false.
     *
     * <p>Both shapes of expectation reach this method already reduced to bytes by
     * {@link #canonicalSignedImage(String, FieldSpan, int)}: a full-width zoned image is its own bytes,
     * and a decimal literal is encoded to the one image a COBOL store of that literal produces. There
     * is deliberately no numeric-equality escape for the literal shape. Allowing one would mean a case
     * pinning {@code "194.00"} accepted the unsigned {@code "000000019400"} rendering, and a case
     * pinning {@code "-0.00"} accepted a positive zero - which are precisely the two byte differences
     * the signed codec exists to preserve, so the judge would be blind to the defects it is here to
     * catch.
     */
    private String signedMismatchExplanation(FieldSpan span,
                                             String expectedValue,
                                             String expectedImage,
                                             String actual,
                                             FixedWidthCodec.SignedZoned actualValue,
                                             int scale,
                                             String channel) {
        StringBuilder text = new StringBuilder("signed zoned ")
            .append(span.describe())
            .append(" differs in its stored bytes on the ")
            .append(channel)
            .append(" channel: expected ")
            .append(quoted(expectedImage));
        if (!expectedImage.equals(expectedValue)) {
            text.append(" (the canonical image of the literal ").append(quoted(expectedValue))
                .append(')');
        }
        text.append(", observed ").append(quoted(actual)).append('.');

        FixedWidthCodec.SignedZoned expectedNumber = null;
        try {
            expectedNumber = codec.decodeSignedZoned(expectedImage, scale);
        } catch (IllegalArgumentException undecodable) {
            text.append(" The expected image cannot itself be decoded, which means the expectation is "
                + "malformed: ").append(undecodable.getMessage());
        }
        if (expectedNumber != null) {
            boolean sameQuantity =
                expectedNumber.signedValue().compareTo(actualValue.signedValue()) == 0;
            if (sameQuantity) {
                text.append(" Both images denote ")
                    .append(expectedNumber.signedValue().toPlainString())
                    .append(", so this is a difference in stored FORM, not in quantity: the VALUE "
                        + "agrees but the REPRESENTATION does not - the record holds the right value "
                        + "but the wrong bytes, which is still a parity failure, because the gate is "
                        + "byte-for-byte.");
                if (expectedNumber.negative() != actualValue.negative()) {
                    text.append(" Specifically the two differ in SIGN while agreeing on every digit: "
                        + "they carry the opposite overpunch of a zero - one a negative zero, the "
                        + "other a positive zero - a distinction a BigDecimal cannot hold at all.");
                } else {
                    text.append(" The usual cause is an unsigned zone-F rendering - a plain digit in "
                        + "the trailing byte - where the signed overpunch form is expected.");
                }
                text.append(" A signed zoned field carries its sign as an overpunch in the trailing "
                    + "character, so one value has several encodings, and the parity contract is the "
                    + "record's bytes rather than the number they happen to denote. Emit the encoding "
                    + "the COBOL emits.");
            } else {
                text.append(" They also denote different quantities, so this differs numerically: "
                        + "expected ")
                    .append(expectedNumber.signedValue().toPlainString())
                    .append(", decoded ")
                    .append(actualValue.signedValue().toPlainString())
                    .append(" - the values differ as well as the bytes: expected image ")
                    .append(quoted(expectedImage))
                    .append(" denoting ")
                    .append(signedRendering(expectedNumber))
                    .append(", observed ")
                    .append(quoted(actual))
                    .append(" denoting ")
                    .append(signedRendering(actualValue))
                    .append(", at scale ").append(scale).append(" with rounding ")
                    .append(CobolDecimal.COBOL_ROUNDING)
                    .append(" (truncation toward zero, because ROUNDED appears zero times in all 28 "
                        + "programs).");
            }
        }
        return text.append(' ').append(signHint(actual)).toString();
    }

    /** Renders a decoded quantity, naming a negative zero rather than printing it as a plain zero. */
    private static String signedRendering(FixedWidthCodec.SignedZoned value) {
        return value.negativeZero()
            ? "a negative zero"
            : value.signedValue().toPlainString();
    }

    /** Quotes an image so leading and trailing spaces in it are visible in a message. */
    private static String quoted(String image) {
        return "\"" + image + "\"";
    }

    /**
     * Builds a value difference, masking both sides when the field is a credential.
     *
     * @param expectation the expectation the field belongs to
     * @param fieldName   the field name
     * @param span        the span, for its offset and width
     * @param expected    the expected value, already canonicalised where that applies
     * @param actual      the observed value
     * @param explanation prose a reviewer can act on
     * @return the difference
     */
    private static Diff valueMismatch(ExpectedRecord expectation,
                                      String fieldName,
                                      FieldSpan span,
                                      String expected,
                                      String actual,
                                      String explanation) {
        return new Diff(expectation.dataset(), expectation.rowIndex(), fieldName, span.offset(),
            span.length(), Redaction.maskFieldValue(fieldName, expected),
            Redaction.maskFieldValue(fieldName, actual), DiffKind.VALUE_MISMATCH, explanation);
    }

    /**
     * Explains a text-field difference, and names the two mistakes that account for most of them.
     *
     * <p>Both hints exist because the underlying rule surprises people. A {@code PIC X} field is
     * compared at its full declared width, so an expectation written as {@code "MARGARET"} against a
     * {@code PIC X(20)} span differs from {@code "MARGARET            "} and is reported - that is
     * deliberate, and the message says how to fix it rather than leaving the author to guess. A
     * {@code PIC 9} field is compared as its zero-filled image, so leading zeros are significant and
     * {@code "1940"} is not {@code "00001940"}.
     */
    private static String textMismatchExplanation(FieldSpan span,
                                                  String expected,
                                                  String actual,
                                                  String channel) {
        StringBuilder text = new StringBuilder(describeKind(span.kind()))
            .append(' ')
            .append(span.describe())
            .append(" differs on the ")
            .append(channel)
            .append(" channel.");
        if (expected.length() != span.length()) {
            text.append(" Note the expectation is ")
                .append(expected.length())
                .append(" character(s) where the span is ")
                .append(span.length())
                .append(": comparison is at full declared width and never trims, so write the "
                    + "expectation out to the span's width - space-padded on the right for PIC X, "
                    + "zero-filled on the left for PIC 9.");
        }
        if (differOnlyInTrailingSpaces(expected, actual)) {
            text.append(" The two differ ONLY in trailing spaces, which are significant: a COBOL "
                + "PIC X field is space-padded to its declared width and that padding is part of "
                + "its value.");
        }
        if (span.kind() == PictureKind.UNSIGNED_NUMERIC && differOnlyInLeadingZeros(expected, actual)) {
            text.append(" The two differ ONLY in leading zeros, which are significant: a PIC 9 image "
                + "is zero-filled to its declared width.");
        }
        return text.toString();
    }

    /**
     * Whether two values are equal once every trailing space is removed from both, while still being
     * unequal as they stand - the signature of a padding defect.
     */
    private static boolean differOnlyInTrailingSpaces(String expected, String actual) {
        return !expected.equals(actual)
            && stripTrailingSpaces(expected).equals(stripTrailingSpaces(actual));
    }

    /**
     * Whether two values are equal once every leading zero is removed from both, while still being
     * unequal as they stand - the signature of a zero-fill defect.
     */
    private static boolean differOnlyInLeadingZeros(String expected, String actual) {
        return !expected.equals(actual)
            && stripLeadingZeros(expected).equals(stripLeadingZeros(actual));
    }

    /**
     * Names the sign the trailing byte of a zoned image carries, so a reviewer does not have to
     * remember the overpunch tables.
     *
     * <p>The classification is derived from the byte and from the decoded sign rather than from a
     * copy of the overpunch tables kept here: duplicating them would create a second source of truth
     * for sign semantics, and the codec is the first.
     *
     * <p>Every caller passes the <em>observed</em> image, and every caller has just named it as such in
     * the sentence before, so the hint says "the trailing byte" rather than repeating "observed" a
     * second time in one breath.
     *
     * @param image the observed bytes whose trailing sign position is to be named
     * @return one sentence naming the trailing byte and the sign it carries
     */
    private String signHint(String image) {
        if (image.isEmpty()) {
            return "The image is empty, so it carries no trailing sign position.";
        }
        char trailing = image.charAt(image.length() - 1);
        String rendered = "The trailing byte is " + describeCharacter(trailing) + ", which";
        if (trailing >= '0' && trailing <= '9') {
            return rendered + " is a plain digit: the unsigned zoned form, read as positive.";
        }
        try {
            // Read the SIGN, not the sign of a decoded BigDecimal: a BigDecimal has no negative
            // zero, so decoding "0000000000}" and asking signum() would report a negative-zero
            // overpunch as positive - in a hint whose whole job is to say which overpunch is
            // present.
            FixedWidthCodec.SignedZoned decoded =
                codec.decodeSignedZoned(image, CobolDecimal.MONETARY_SCALE);
            return rendered + (decoded.negative()
                ? " is a NEGATIVE sign overpunch."
                : " is a POSITIVE sign overpunch.");
        } catch (IllegalArgumentException notAnOverpunch) {
            return rendered + " is neither a digit nor a recognised sign overpunch, so the field "
                + "cannot be read as a number at all.";
        }
    }


    // ===============================================================================================
    // The online response channel.
    // ===============================================================================================

    /**
     * Compares the online response field by field, or reports its unexpected presence or absence.
     *
     * <p>This channel exists because an online program's observable behaviour is mostly its response.
     * Of the seventeen translated programs several paths write no dataset at all - the no-commarea
     * guard, an empty-field rejection, an invalid key - and before this channel existed every one of
     * them could have returned the wrong next program, the wrong screen text, the wrong colour or the
     * wrong cursor and still produced a diff count of zero.
     *
     * @param expected the case's expectation, or {@code null} for a batch case
     * @param observed what the unit returned, or {@code null} when it returned nothing
     * @param diffs    the accumulator every difference is appended to
     */
    private void compareResponse(ExpectedResponse expected,
                                 ObservedResponse observed,
                                 List<Diff> diffs) {
        if (expected == null && observed == null) {
            return;
        }
        if (expected == null) {
            diffs.add(responseDiff("<whole>", null, observed.toString(),
                DiffKind.RESPONSE_MISMATCH,
                "the unit returned an online response but the case expects none. A batch case has no "
                    + "screen, so a response here means the harness invoked something other than the "
                    + "unit the case names."));
            return;
        }
        if (observed == null) {
            diffs.add(responseDiff("<whole>", expected.toString(), null,
                DiffKind.RESPONSE_MISMATCH,
                "the case expects an online response but the unit returned none. Every path through "
                    + "an online program produces one - even the no-commarea guard, which produces a "
                    + "response carrying nothing but the next program and the navigation context."));
            return;
        }

        compareResponseScalar("nextProgram", expected.nextProgram(), observed.nextProgram(),
            "the target of EXEC CICS XCTL PROGRAM(...), which becomes a response field in a "
                + "stateless translation because the client resolves the navigation", diffs);
        compareResponseScalar("nextMapset", expected.nextMapset(), observed.nextMapset(),
            "the mapset the response names, carried in CDEMO-LAST-MAPSET", diffs);
        compareResponseScalar("nextMap", expected.nextMap(), observed.nextMap(),
            "the map the response names, carried in CDEMO-LAST-MAP", diffs);
        compareResponseScalar("cursorField", expected.cursorField(), observed.cursorField(),
            "the symbolic-map length item that received MOVE -1, which is how COBOL positions the "
                + "cursor - so a different field here means the cursor landed somewhere else", diffs);
        compareResponseScalar("termination", nameOf(expected.termination()),
            nameOf(observed.termination()),
            "how the transaction ended. XCTL transfers control and never returns, so the EXEC CICS "
                + "RETURN that follows it in the source is not reached; the two are not "
                + "interchangeable", diffs);

        compareNavigation(expected.navigation(), observed.navigation(), diffs);
        compareSends(expected.sends(), observed.sends(), diffs);
    }

    /**
     * Compares one scalar response field, treating absent and present as distinct.
     *
     * <p>Rendered through {@link Redaction} like every other value, even though the scalars this
     * method compares - a program name, a mapset, a map, a cursor field, a termination - are all
     * unclassified and therefore render verbatim. Routing them anyway is the point: the policy is
     * applied at every rendering site rather than at the sites someone remembered, so classifying a
     * new field later takes one edit in one place and cannot miss a caller.
     */
    private static void compareResponseScalar(String fieldName,
                                              String expected,
                                              String actual,
                                              String meaning,
                                              List<Diff> diffs) {
        if (Objects.equals(expected, actual)) {
            return;
        }
        diffs.add(responseDiff(fieldName, Redaction.maskFieldValue(fieldName, expected),
            Redaction.maskFieldValue(fieldName, actual), DiffKind.RESPONSE_MISMATCH,
            "response field '" + fieldName + "' differs. It carries " + meaning + '.'));
    }

    /**
     * Compares the navigation context in both directions, so an unexpected field is reported too.
     *
     * <p>This is where statelessness is actually asserted (rule R6): the conversation state travels
     * in the payload, which is the only reason it is comparable at all. A translation that kept it in
     * a server-side session would have nothing here to compare.
     */
    private static void compareNavigation(Map<String, String> expected,
                                          Map<String, String> actual,
                                          List<Diff> diffs) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String field = entry.getKey();
            String actualValue = actual.get(field);
            if (Objects.equals(entry.getValue(), actualValue)) {
                continue;
            }
            // Masked by field name like every other value the differ renders. The commarea is where
            // the account, card, customer and user identifiers travel between transactions, so this
            // rendering is one of the two places a single missed call would put them into a build log.
            diffs.add(responseDiff("navigation." + field,
                Redaction.maskFieldValue(field, entry.getValue()),
                Redaction.maskFieldValue(field, actualValue),
                actual.containsKey(field)
                    ? DiffKind.RESPONSE_MISMATCH
                    : DiffKind.FIELD_ABSENT_IN_FINGERPRINT,
                "the CARDDEMO-COMMAREA field " + field + " differs in the returned navigation "
                    + "context. Conversation state travels in the payload rather than in a "
                    + "server-side session, so every one of these fields is part of the observable "
                    + "response. Present in the response: " + actual.keySet()));
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String field = entry.getKey();
            if (expected.containsKey(field)) {
                continue;
            }
            diffs.add(responseDiff("navigation." + field, null,
                Redaction.maskFieldValue(field, entry.getValue()),
                DiffKind.RESPONSE_MISMATCH,
                "the response carries the CARDDEMO-COMMAREA field " + field + ", which the case does "
                    + "not pin. A commarea field the case says nothing about is a field nobody has "
                    + "checked, and the commarea is carried forward into the next transaction."));
        }
    }

    /**
     * Compares the ordered sequence of screen sends: the count first, then each send's payload and
     * attribute metadata.
     *
     * <p>The count is behaviour in its own right. Several {@code COUSR02C} paths perform
     * {@code SEND-USRUPD-SCREEN} more than once in a single invocation - an inherited quirk of the
     * program rather than a translation artefact - so a translation that sent once where the COBOL
     * sends twice has changed what the terminal saw, and that must fail.
     */
    private static void compareSends(List<ScreenSend> expected,
                                     List<ObservedSend> actual,
                                     List<Diff> diffs) {
        if (expected.size() != actual.size()) {
            diffs.add(new Diff(RESPONSE_SCOPE, Diff.NOT_APPLICABLE, SEND_COUNT_FIELD,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, Integer.toString(expected.size()),
                Integer.toString(actual.size()), DiffKind.SEND_COUNT_MISMATCH,
                "the case expects " + expected.size() + " screen send(s) but the unit performed "
                    + actual.size() + ". The send count is behaviour: several paths through these "
                    + "programs send the same map more than once in one invocation, and a "
                    + "translation that collapsed them would have changed what the terminal saw."));
        }

        int shared = Math.min(expected.size(), actual.size());
        for (int index = 0; index < shared; index++) {
            compareOneSend(index, expected.get(index), actual.get(index), diffs);
        }
    }

    /** Compares one send's payload fields and attribute metadata, in both directions. */
    private static void compareOneSend(int index,
                                       ScreenSend expected,
                                       ObservedSend actual,
                                       List<Diff> diffs) {
        String prefix = "sends[" + index + "].";
        compareSendMap(prefix + "fields.", expected.fields(), actual.fields(),
            "a symbolic-map output field of this send", diffs);
        compareSendMap(prefix + "attributes.", expected.attributes(), actual.attributes(),
            "an attribute item of this send - the colour, protection or highlight byte the program "
                + "moved, named by its DFHBMSCA or DFHATTR mnemonic. Colour is not decoration: it is "
                + "how the program distinguishes an error from a prompt from a confirmation", diffs);
    }

    /** Compares one map of a send, reporting a missing, differing or unexpected entry. */
    private static void compareSendMap(String prefix,
                                       Map<String, String> expected,
                                       Map<String, String> actual,
                                       String meaning,
                                       List<Diff> diffs) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String field = entry.getKey();
            String actualValue = actual.get(field);
            if (Objects.equals(entry.getValue(), actualValue)) {
                continue;
            }
            diffs.add(responseDiff(prefix + field,
                Redaction.maskFieldValue(field, entry.getValue()),
                Redaction.maskFieldValue(field, actualValue),
                actual.containsKey(field)
                    ? DiffKind.RESPONSE_MISMATCH
                    : DiffKind.FIELD_ABSENT_IN_FINGERPRINT,
                field + " is " + meaning + ", and it differs. Present in this send: "
                    + actual.keySet()));
        }
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String field = entry.getKey();
            if (expected.containsKey(field)) {
                continue;
            }
            diffs.add(responseDiff(prefix + field, null,
                Redaction.maskFieldValue(field, entry.getValue()), DiffKind.RESPONSE_MISMATCH,
                "the send carries " + field + ", which the case does not pin. " + meaning
                    + ", so an unpinned one is a field nobody has checked."));
        }
    }

    /** Builds a response-channel difference. */
    private static Diff responseDiff(String fieldName,
                                     String expected,
                                     String actual,
                                     DiffKind kind,
                                     String explanation) {
        return new Diff(RESPONSE_SCOPE, Diff.NOT_APPLICABLE, fieldName, Diff.NOT_APPLICABLE,
            Diff.NOT_APPLICABLE, expected, actual, kind, explanation);
    }

    /** Renders an enum constant, or {@code null} when it is absent, without a null-check at the call. */
    private static String nameOf(Enum<?> constant) {
        return constant == null ? null : constant.name();
    }


    // ===============================================================================================
    // Return code and emitted messages.
    // ===============================================================================================

    /**
     * Compares the COBOL {@code RETURN-CODE} the case expects against the one observed.
     *
     * <p>The values this migration produces are {@code 0} for a normal completion, {@code 3} for every
     * named {@code CEEDAYS} feedback token in the translated date utility, {@code 4}, {@code 8} and
     * {@code 12} from the {@code MOVE 8}/{@code MOVE 12 TO APPL-RESULT}-then-abend convention shared by
     * the nine {@code CALL 'CEE3ABD'} sites, and {@code 16} for the end-of-file condition that
     * {@link FileStatus#APPL_EOF} names. They are quoted in the failure message rather than enforced
     * here, because the case model already bounds the value and this class judges rather than
     * validates.
     */
    private void compareReturnCode(ParityCase parityCase, Fingerprint fingerprint, List<Diff> diffs) {
        int expected = parityCase.expectedReturnCode();
        int actual = fingerprint.returnCode();
        if (expected == actual) {
            return;
        }
        diffs.add(new Diff(RETURN_CODE_SCOPE, Diff.NOT_APPLICABLE, RETURN_CODE_FIELD,
            Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, Integer.toString(expected),
            Integer.toString(actual), DiffKind.RETURN_CODE_MISMATCH,
            "the case expects RETURN-CODE " + expected + " but the unit reported " + actual
                + ". The codes this migration produces are 0 (normal), 3 (a named CEEDAYS feedback "
                + "token), 4, 8 and 12 (the MOVE 8 / MOVE 12 TO APPL-RESULT then abend convention) "
                + "and " + FileStatus.APPL_EOF + " (end of file); a batch exit status must carry the "
                + "same value so JCL COND gating continues to behave as it does today."));
    }

    /**
     * Compares the emitted lines positionally, on their declared channels.
     *
     * <p>Both a count difference and every positional difference are reported: the count says the unit
     * emitted the wrong number of lines, and the positional entries say which lines are wrong, and a
     * reviewer needs both. The common prefix is still compared when the counts differ, because a run
     * that emitted one line too few usually has something to say about the lines it did emit.
     *
     * <p>The channel is compared before the text, and a channel difference is reported as its own kind.
     * Three related things travel through this list - a {@code DISPLAY} line, the 80-byte
     * {@code WS-MESSAGE} working-storage field, and the 78-byte {@code ERRMSGO} screen field that
     * {@code MOVE WS-MESSAGE TO ERRMSGO} truncates it into - and comparing an 80-byte expectation
     * against a 78-byte observation as though they were the same thing is how a two-byte truncation
     * defect goes unnoticed.
     *
     * <p>Text comparison is exact. Where a line comes from a shared renderer the expectation must
     * match that renderer's output character for character - {@link FileStatus#toDisplayLine(String)}
     * emits {@code "FILE STATUS IS: NNNN0000"} for status {@code "00"}, where the {@code NNNN} is
     * genuinely part of the COBOL literal and not a placeholder awaiting substitution.
     */
    private void compareMessages(ParityCase parityCase, Fingerprint fingerprint, List<Diff> diffs) {
        List<EmittedMessage> expected = parityCase.expectedMessages();
        List<EmittedMessage> actual = fingerprint.messages();

        if (expected.size() != actual.size()) {
            diffs.add(new Diff(MESSAGES_SCOPE, Diff.NOT_APPLICABLE, MESSAGE_FIELD,
                Diff.NOT_APPLICABLE, Diff.NOT_APPLICABLE, Integer.toString(expected.size()),
                Integer.toString(actual.size()), DiffKind.MESSAGE_COUNT_MISMATCH,
                "the case expects " + expected.size() + " emitted line(s) but the unit emitted "
                    + actual.size() + ". Emission order and count are both part of the expectation; "
                    + "an empty string on the DISPLAY_LINE channel is a legitimate expectation, "
                    + "because a COBOL DISPLAY of a blank line emits one."));
        }

        int shared = Math.min(expected.size(), actual.size());
        for (int position = 0; position < shared; position++) {
            EmittedMessage expectedLine = expected.get(position);
            EmittedMessage actualLine = actual.get(position);

            if (expectedLine.channel() != actualLine.channel()) {
                diffs.add(new Diff(MESSAGES_SCOPE, position, MESSAGE_FIELD, Diff.NOT_APPLICABLE,
                    Diff.NOT_APPLICABLE, expectedLine.channel().name(), actualLine.channel().name(),
                    DiffKind.MESSAGE_CHANNEL_MISMATCH,
                    "emitted line " + position + " arrived on the " + actualLine.channel()
                        + " channel where the case expects " + expectedLine.channel()
                        + ". The channels have different widths - " + expectedLine.channel()
                        + " because " + expectedLine.channel().declaration() + " - so comparing "
                        + "across them would let the 80-to-78 truncation of MOVE WS-MESSAGE TO "
                        + "ERRMSGO pass unnoticed."));
                continue;
            }
            if (expectedLine.text().equals(actualLine.text())) {
                continue;
            }
            diffs.add(new Diff(MESSAGES_SCOPE, position, MESSAGE_FIELD, Diff.NOT_APPLICABLE,
                expectedLine.text().length(), Redaction.maskIfSensitiveText(expectedLine.text()),
                Redaction.maskIfSensitiveText(actualLine.text()), DiffKind.MESSAGE_MISMATCH,
                "emitted line " + position + " differs byte-exactly on the "
                    + expectedLine.channel() + " channel."
                    + fileStatusHint(expectedLine.text(), actualLine.text())));
        }
    }

    /**
     * Adds a targeted hint when both sides are file-status {@code DISPLAY} lines, narrowing the
     * difference to the four-character {@code IO-STATUS-04} image the shared renderer appends.
     */
    private static String fileStatusHint(String expected, String actual) {
        int prefixLength = FileStatus.DISPLAY_PREFIX.length();
        int fullLength = prefixLength + FileStatus.STATUS_IMAGE_LENGTH;
        if (expected.length() == fullLength && actual.length() == fullLength
            && expected.startsWith(FileStatus.DISPLAY_PREFIX)
            && actual.startsWith(FileStatus.DISPLAY_PREFIX)) {
            return " Both lines are file-status DISPLAY lines carrying the literal '"
                + FileStatus.DISPLAY_PREFIX + "', so only the four-character IO-STATUS-04 image "
                + "differs: expected '" + expected.substring(prefixLength) + "', observed '"
                + actual.substring(prefixLength) + "'. Emit these lines through "
                + "FileStatus.toDisplayLine so all sixteen legacy emission sites stay byte-identical.";
        }
        return "";
    }


    // ===============================================================================================
    // Span addressing, diff construction and description helpers.
    // ===============================================================================================

    /**
     * Maps every span of a layout to the name an expectation addresses it by, in copybook declaration
     * order.
     *
     * <p>This is where the {@code FILLER} ordinal convention lives, and this class owns it because a
     * JSON object cannot hold a duplicate key. The spans of a record are walked in declaration order
     * and each unnamed {@code FILLER} takes the next ordinal: the first is {@code FILLER}, the second
     * {@code FILLER-2}, the third {@code FILLER-3}. The convention is deliberately strict - the first
     * span is {@code FILLER} and not {@code FILLER-1} - so that a single name never has two spellings.
     * An expectation using an unrecognised spelling is reported as
     * {@link DiffKind#FIELD_ABSENT_IN_FINGERPRINT} with the addressable names listed, which is a loud
     * and immediately actionable failure rather than a silently tolerated alias.
     *
     * <p>A {@code FILLER} span reached this way is an ordinary comparable field. That matters: the
     * persisted copybooks all end in one - {@code CVACT01Y} in {@code FILLER X(178)}, {@code CVACT02Y}
     * in {@code FILLER X(59)}, {@code CVACT03Y} in {@code FILLER X(14)}, {@code CVTRA05Y} in
     * {@code FILLER X(20)} and {@code CVCUS01Y} in {@code FILLER X(168)} - and their content is a
     * property of the shipped data rather than of the copybook, some datasets holding spaces there and
     * others zeros. Only a comparison that can address a {@code FILLER} can check that.
     *
     * <p>{@code REDEFINES} overlays are included under their own names, because an overlay is a
     * legitimate alternative view of storage that a case may reasonably pin - there are 82 such sites
     * in the codebase, {@code CVCRD01Y}'s {@code CC-ACCT-ID PIC X(11)} over
     * {@code CC-ACCT-ID-N PIC 9(11)} among them.
     */
    private static Map<String, FieldSpan> addressableSpans(RecordLayout layout) {
        Map<String, FieldSpan> addressable = new LinkedHashMap<>();
        int fillerOrdinal = 0;
        for (FieldSpan span : layout.spans()) {
            if (FILLER_NAME.equals(span.name())) {
                fillerOrdinal++;
                addressable.put(
                    fillerOrdinal == 1
                        ? FILLER_NAME
                        : FILLER_NAME + FILLER_ORDINAL_SEPARATOR + fillerOrdinal,
                    span);
                continue;
            }
            addressable.put(span.name(), span);
        }
        return Collections.unmodifiableMap(addressable);
    }

    /**
     * Builds the single difference that stands for a record the unit never produced.
     *
     * @param dataset     the dataset binding key the expectation addressed
     * @param rowIndex    the 0-based row index it addressed
     * @param expectation the expectation, summarised into the {@code expected} side
     * @param channel     the channel name, quoted in the failure text
     * @param reason      why no record was found, phrased to complete a sentence
     * @return the difference
     */
    private static Diff missingRecord(String dataset,
                                      int rowIndex,
                                      ExpectedRecord expectation,
                                      RecordLayout layout,
                                      String channel,
                                      String reason) {
        return new Diff(dataset, rowIndex, RECORD_SCOPE_FIELD, Diff.NOT_APPLICABLE,
            Diff.NOT_APPLICABLE, summariseExpectation(expectation, layout), null,
            DiffKind.MISSING_RECORD,
            "no " + channel + " record was found to compare against: " + reason
                + ". This counts as one difference for the record rather than one per field, because "
                + "the fields of a record that was never produced are not independently wrong - they "
                + "are collectively absent.");
    }

    /**
     * Builds the single difference that stands for a record of the wrong total width.
     *
     * @param dataset       the dataset binding key
     * @param rowIndex      the 0-based row index
     * @param layout        the layout whose declared length was not met
     * @param measuredWidth the width actually measured, in bytes
     * @param channel       the channel name, quoted in the failure text
     * @param reason        which side disagreed and by how much
     * @return the difference
     */
    private static Diff widthMismatch(String dataset,
                                      int rowIndex,
                                      RecordLayout layout,
                                      int measuredWidth,
                                      String channel,
                                      String reason) {
        return new Diff(dataset, rowIndex, RECORD_SCOPE_FIELD, 0, layout.recordLength(),
            Integer.toString(layout.recordLength()), Integer.toString(measuredWidth),
            DiffKind.RECORD_WIDTH_MISMATCH,
            "total " + channel + " record width disagrees with the layout: " + reason
                + " Field comparison is skipped for this record, because once the total width is "
                + "wrong every offset past the missing span addresses the wrong bytes and the field "
                + "differences that follow would be noise hiding this one real finding.");
    }

    /**
     * Summarises what an expectation pinned, for the {@code expected} side of a record-level
     * difference: the whole record image where the case pins one - with every classified span masked -
     * and the list of field names it pins otherwise.
     *
     * @param expectation the expectation being summarised
     * @param layout      the layout of the dataset it addresses, or {@code null} when the unit
     *                    produced nothing for that dataset and no layout is therefore known
     */
    private static String summariseExpectation(ExpectedRecord expectation, RecordLayout layout) {
        if (expectation.expectedBytes() != null) {
            return maskImageOf(expectation.dataset(), layout, expectation.expectedBytes());
        }
        return "{" + String.join(", ", expectation.fields().keySet()) + "}";
    }

    /**
     * Masks every classified span of a record image before it is rendered.
     *
     * <p>Two rules, applied in that order, because each closes what the other cannot:
     * <ul>
     *   <li>The <strong>layout-driven</strong> pass masks every span whose name
     *       {@link Redaction} classifies - the password, the customer and user names, the address, the
     *       social-security number, the account and card identifiers - wherever the copybook puts them,
     *       in any dataset. It is available whenever the unit produced something for the dataset,
     *       which is when a record image is rendered at all.</li>
     *   <li>The <strong>dataset-and-width</strong> pass is {@link Redaction#maskRecordImage} and is
     *       kept because it handles the one shape the layout cannot: an image whose width disagrees
     *       with the copybook, where every offset past the shortfall addresses the wrong bytes. That is
     *       not a hypothetical width here - a {@code MISSING_RECORD} rendering prints whatever width a
     *       fixture author typed, and an {@code EXTRA_RECORD} rendering prints whatever width the unit
     *       wrote, which for a truncation defect is exactly the wrong one.</li>
     * </ul>
     * Both preserve the rendered length, so composing them cannot shift a byte and a width difference
     * stays diagnosable.
     *
     * <p>With <strong>no layout at all</strong> - the {@code MISSING_RECORD} case where the unit
     * produced nothing for the dataset, so there is no output to take one from - neither pass can
     * locate a span, and the image is rendered as its length and a digest by
     * {@link Redaction#maskUnlocatedImage(String)}. Guessing that such an image is harmless would be
     * wrong for almost every dataset here: a customer row is names, an address and a social-security
     * number, an account, card or cross-reference row leads with an identifier, and a security-user row
     * carries the legacy plaintext password.
     */
    private static String maskImageOf(String dataset, RecordLayout layout, String image) {
        if (layout == null) {
            return Redaction.maskUnlocatedImage(image);
        }
        return Redaction.maskRecordImage(dataset, Redaction.maskImage(image, redactionSpans(layout)));
    }

    /**
     * Projects a layout's addressable spans onto the shape {@link Redaction} masks by.
     *
     * <p>Built from {@link #addressableSpans(RecordLayout)}, which is the same view the comparison
     * itself addresses fields through - {@code REDEFINES} overlays under their own names and
     * {@code FILLER} spans under their ordinal names - so a span that can be compared can be masked,
     * with no second opinion about where a field sits.
     */
    private static List<Redaction.Span> redactionSpans(RecordLayout layout) {
        Map<String, FieldSpan> addressable = addressableSpans(layout);
        List<Redaction.Span> spans = new ArrayList<>(addressable.size());
        for (Map.Entry<String, FieldSpan> entry : addressable.entrySet()) {
            FieldSpan span = entry.getValue();
            spans.add(new Redaction.Span(entry.getKey(), span.offset(), span.length()));
        }
        return spans;
    }

    /** Names a layout by its shape, so a width failure identifies which layout disagreed. */
    private static String describeLayout(RecordLayout layout) {
        return "the layout (" + layout.storageSpans().size() + " storage span(s) and "
            + layout.redefinitions().size() + " REDEFINES overlay(s), declared length "
            + layout.recordLength() + ")";
    }

    /** Lists the dataset keys a channel actually carries, so a missing dataset is diagnosable. */
    private static String describeDatasetKeys(Map<String, DatasetOutput> observed) {
        return observed.isEmpty()
            ? "no dataset at all on this channel, so there is no output dataset at all to look in"
            : "dataset(s) " + String.join(", ", observed.keySet());
    }

    /**
     * States what the case declared for a dataset, so a width difference can say plainly whether a
     * normalisation was in play at all.
     *
     * <p>A reader meeting a width mismatch has exactly two hypotheses: the unit wrote the wrong number
     * of bytes, or the case forgot to declare the pad that repairs a known fixture-to-copybook
     * deviation. Naming the declarations settles which, and naming them from the case rather than
     * asserting a general truth means the sentence is still correct when a case has declared a pad for
     * some other dataset.
     *
     * @param normalisations every normalisation the case declares
     * @param dataset        the dataset whose record disagreed on width
     * @return a clause naming the declarations that bind to this dataset, or their absence
     */
    private static String describeNormalisations(
            List<ParityCase.DatasetNormalisation> normalisations, String dataset) {
        List<String> bound = new ArrayList<>();
        for (ParityCase.DatasetNormalisation normalisation : normalisations) {
            if (normalisation.dataset().equals(dataset)) {
                bound.add(normalisation.kind().name());
            }
        }
        if (bound.isEmpty()) {
            return "The case declares no normalisation for dataset " + dataset;
        }
        return "The case declares " + String.join(", ", bound) + " for dataset " + dataset
            + ", which is applied when the dataset is seeded and not here";
    }

    /** Lists the datasets a channel's expectations address, for an extra-dataset difference. */
    private static String describeExpectedDatasets(Map<String, Set<Integer>> expectedRows) {
        return expectedRows.isEmpty() ? "none at all"
            : String.join(", ", expectedRows.keySet());
    }

    /** Names a PICTURE category in the words the copybook uses. */
    private static String describeKind(PictureKind kind) {
        return switch (kind) {
            case ALPHANUMERIC -> "alphanumeric PIC X";
            case UNSIGNED_NUMERIC -> "unsigned zoned PIC 9";
            case SIGNED_SCALED -> "signed zoned PIC S9";
            case FILLER -> "reserved FILLER";
        };
    }

    /**
     * Decodes a raw row to text through the record layer, so the code page is the codec's and no
     * charset is ever taken from the platform.
     *
     * <p>A row of any width can be described this way, including one whose width is exactly the reason
     * it is being reported, which is why it goes through {@link FixedWidthRecord#copyOf} at the row's
     * own length rather than through the layout.
     */
    private String imageOf(byte[] row) {
        if (row.length == 0) {
            return "";
        }
        return FixedWidthRecord.copyOf(row, row.length, codec.charset()).readString(0, row.length);
    }

    // ===============================================================================================
    // Rendering. Invisible bytes are made visible, because a byte a reviewer cannot see is a byte
    // they cannot diagnose - and a credential span is masked, because a byte a reviewer can see is a
    // byte a build log can keep.
    // ===============================================================================================

    /**
     * Renders a value so that every byte of it is legible: the visible part quoted, trailing spaces
     * counted rather than printed, non-printing bytes escaped, and the exact length stated.
     *
     * <p>Trailing spaces are the specific hazard. They are significant in COBOL, they are invisible in
     * any ordinary failure message, and they are the single most common cause of a comparison that
     * looks correct and is not - so they are reported as a count that cannot be overlooked instead of
     * as whitespace that can.
     *
     * <p>Whatever reaches here has already been masked where masking applies: the callers pass values
     * through {@link Redaction}, so this method renders faithfully and makes no policy decision of its
     * own.
     */
    private static String describeValue(String value) {
        if (value == null) {
            return "<absent>";
        }
        int trailing = trailingSpaceCount(value);
        StringBuilder text = new StringBuilder("'")
            .append(escapeNonPrinting(value.substring(0, value.length() - trailing)))
            .append('\'');
        if (trailing > 0) {
            text.append(" + ").append(trailing).append(" trailing space(s)");
        }
        return text.append(" (len=").append(value.length()).append(')').toString();
    }

    /** Renders one character with both its glyph and its code point, for a sign-overpunch note. */
    private static String describeCharacter(char character) {
        return "'" + escapeNonPrinting(String.valueOf(character)) + "' (0x"
            + String.format(Locale.ROOT, "%02X", (int) character) + ")";
    }

    /**
     * Escapes every byte outside the printable ASCII range, so a control byte or a high byte shows up
     * as {@code \x1A} rather than as nothing at all. Deliberately ASCII-only output: a rendered diff
     * has to survive being pasted into a build log of unknown encoding.
     */
    private static String escapeNonPrinting(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= 0x20 && character <= 0x7E) {
                out.append(character);
            } else if (character <= 0xFF) {
                out.append("\\x").append(String.format(Locale.ROOT, "%02X", (int) character));
            } else {
                out.append("\\u").append(String.format(Locale.ROOT, "%04X", (int) character));
            }
        }
        return out.toString();
    }

    /** Counts the trailing spaces of a value, which is what makes them reportable. */
    private static int trailingSpaceCount(String value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == ' '; index--) {
            count++;
        }
        return count;
    }

    /** Removes every trailing space, used only to classify a difference and never to compare one. */
    private static String stripTrailingSpaces(String value) {
        return value.substring(0, value.length() - trailingSpaceCount(value));
    }

    /** Removes every leading zero, used only to classify a difference and never to compare one. */
    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }


    // ===============================================================================================
    // Nested contract types. All of them live here; see the class documentation for why.
    // ===============================================================================================

    /**
     * What kind of difference a {@link Diff} records.
     *
     * <p>The set is deliberately fine-grained. "Not equal" is not a diagnosis, and a reviewer working
     * through a failing module needs to know at a glance whether a record is absent, mis-sized,
     * mis-valued or undecodable, because each points at a different class of translation defect.
     * <strong>Every constant counts toward the diff count</strong> - there is no advisory kind, because
     * an advisory finding in a parity gate is a finding that gets ignored.
     */
    public enum DiffKind {

        /**
         * A field's value differs. The commonest kind, and the one the whole class exists to produce
         * accurately: it is emitted once per field, so a record with three wrong fields yields three of
         * these.
         */
        VALUE_MISMATCH,

        /**
         * An expectation addresses a record the unit never produced on that channel - either the
         * dataset carries no rows at all, or it carries fewer than the expectation's 0-based row index
         * requires.
         */
        MISSING_RECORD,

        /**
         * The unit produced a row at an index no expectation for that dataset addresses. Reported
         * wherever the row sits, including below the highest expected index: writing one record too
         * many is a parity failure on its own terms, and the extra record does not have to be wrong in
         * a field to be wrong.
         */
        EXTRA_RECORD,

        /**
         * The unit touched a dataset the case expects nothing from. A separate kind from
         * {@link #EXTRA_RECORD} because it is a different defect: not one row too many, but a whole
         * dataset the case never mentioned - which is exactly what a judge that only walked the
         * expectations could never see.
         */
        EXTRA_DATASET,

        /**
         * A record's total width disagrees with its layout's declared length. This is the check that
         * catches an omitted {@code FILLER}: a record short by exactly one span shifts every byte
         * offset after it, and reporting that once against the record is far more useful than reporting
         * it as a cascade of field differences. Since nothing is padded at comparison time, this kind
         * always means a real width disagreement.
         */
        RECORD_WIDTH_MISMATCH,

        /**
         * An expectation names a field the layout does not declare, or a response field the unit did
         * not return, so nothing was produced to compare it against. Usually a misspelling - and worth
         * remembering that the copybook's own spellings are authoritative,
         * {@code ACCT-EXPIRAION-DATE} included - or a {@code FILLER} addressed by an ordinal the
         * convention does not produce.
         */
        FIELD_ABSENT_IN_FINGERPRINT,

        /**
         * The observed bytes of a field are not a valid image for its declared PICTURE category - most
         * often a signed zoned field whose trailing byte is neither a digit nor a recognised sign
         * overpunch. Reported rather than thrown, so one bad field cannot abandon the comparison and
         * hide every difference after it.
         */
        UNDECODABLE_FIELD,

        /**
         * The expectation itself cannot be interpreted for the field's declared category. This is an
         * authoring error rather than a translation defect, but it is still counted: a case that cannot
         * be evaluated has not passed, and letting it look clean would be the worst possible outcome.
         */
        MALFORMED_EXPECTATION,

        /**
         * An online response field differs, is absent, or is present when nothing was expected. This
         * covers the next program an {@code XCTL} resolves to, the navigation context carried in the
         * payload, a screen send's BMS payload or attribute metadata, the cursor field and the
         * termination.
         */
        RESPONSE_MISMATCH,

        /**
         * The unit performed a different number of screen sends than the case expects. Its own kind
         * because the count is behaviour: several paths through these programs legitimately send the
         * same map twice in one invocation.
         */
        SEND_COUNT_MISMATCH,

        /** The COBOL {@code RETURN-CODE}, and therefore the batch exit status, differs. */
        RETURN_CODE_MISMATCH,

        /** An emitted line differs from the line expected at that position, byte-exactly. */
        MESSAGE_MISMATCH,

        /**
         * An emitted line arrived on a different channel from the one expected - a variable-width
         * {@code DISPLAY} where an 80-byte working-storage message was expected, or the 78-byte screen
         * field where the 80-byte field was. The widths differ, so the channel has to match before the
         * text can mean anything.
         */
        MESSAGE_CHANNEL_MISMATCH,

        /**
         * A record expectation does not account for every byte of the record it addresses.
         *
         * <p>The one difference that is about the <em>expectation</em> rather than about the output. A
         * case that pins two fields of a 300-byte account row has left 289 bytes unasserted, and a
         * comparison that reported nothing would report a diff count of zero over a record it barely
         * looked at. Reported per record, naming every span nothing accounted for.
         */
        INCOMPLETE_EXPECTATION,

        /**
         * A dataset the case expects on a channel is absent from the fingerprint entirely.
         *
         * <p>Distinct from {@link #MISSING_RECORD}, which is about a row. This is the assertion that the
         * dataset itself was opened or created - the one statement no row expectation can make, because
         * a dataset that exists and holds no row has no row to address.
         */
        MISSING_DATASET,

        /**
         * A dataset holds a different number of rows than the case expects on that channel.
         *
         * <p>Counted once for the dataset. It is what makes "created and left empty" assertable, and it
         * is not the same finding as an unaccounted row: a count can be wrong while every row the case
         * does pin is right.
         */
        DATASET_ROW_COUNT_MISMATCH,

        /**
         * A dataset reports a different record width than the case expects on that channel.
         *
         * <p>Distinct from {@link #RECORD_WIDTH_MISMATCH}, which measures one row against its layout.
         * This measures the layout the unit reported for the whole dataset, which is the only width an
         * empty dataset has.
         */
        DATASET_WIDTH_MISMATCH,

        /** The unit emitted a different number of lines than the case expects. */
        MESSAGE_COUNT_MISMATCH
    }

    /**
     * One difference, located precisely enough to act on without re-deriving anything by hand.
     *
     * @param dataset the dataset binding key the difference belongs to, or one of the bracketed
     *     pseudo-scopes for a return-code, message or response difference. Bracketed names cannot
     *     collide with a real binding key, which is validated as one to eight upper-case alphanumerics
     * @param rowIndex the 0-based row index within that dataset, the 0-based position within the
     *     emitted lines for a message difference, or {@link #NOT_APPLICABLE}
     * @param fieldName the COBOL field name verbatim, a {@code FILLER} ordinal name, a response field
     *     path, or a bracketed pseudo-field for a record-level or dataset-level difference
     * @param offset the field's declared 0-based byte offset within the record, or
     *     {@link #NOT_APPLICABLE}
     * @param length the field's declared width in bytes, or {@link #NOT_APPLICABLE}
     * @param expected what the case expects, with any credential span already masked; {@code null}
     *     only where nothing was expected, as for an extra record
     * @param actual what the unit produced, with any credential span already masked; {@code null} only
     *     where nothing was produced, as for a missing record
     * @param kind which kind of difference this is; never {@code null}
     * @param explanation prose a reviewer can act on, naming the rule that was violated and, where
     *     there is one, the remedy
     */
    public record Diff(String dataset,
                       int rowIndex,
                       String fieldName,
                       int offset,
                       int length,
                       String expected,
                       String actual,
                       DiffKind kind,
                       String explanation) {

        /**
         * The value carried by {@link #rowIndex}, {@link #offset} or {@link #length} when the concept
         * does not apply - a return code has no offset, and a missing record has no field length.
         * Negative on purpose, so it can never be mistaken for a real 0-based index or width.
         */
        public static final int NOT_APPLICABLE = -1;

        /**
         * Validates the entry, so a malformed difference cannot reach a report.
         *
         * @throws NullPointerException if {@code dataset}, {@code fieldName}, {@code kind} or
         *     {@code explanation} is {@code null}
         * @throws IllegalArgumentException if a text member is blank, or an index or width is negative
         *     without being exactly {@link #NOT_APPLICABLE}
         */
        public Diff {
            Objects.requireNonNull(dataset, "Diff.dataset is required: a difference must say which "
                + "dataset or pseudo-scope it belongs to");
            Objects.requireNonNull(fieldName, "Diff.fieldName is required: a difference must name the "
                + "field it localises to, which is the entire point of a field-by-field differ");
            Objects.requireNonNull(kind, "Diff.kind is required");
            Objects.requireNonNull(explanation, "Diff.explanation is required: an unexplained "
                + "difference costs the reader a debugging session that a sentence would have saved");
            if (dataset.isBlank()) {
                throw new IllegalArgumentException("Diff.dataset must not be blank");
            }
            if (fieldName.isBlank()) {
                throw new IllegalArgumentException("Diff.fieldName must not be blank");
            }
            if (explanation.isBlank()) {
                throw new IllegalArgumentException("Diff.explanation must not be blank");
            }
            requireIndexOrNotApplicable(rowIndex, "rowIndex");
            requireIndexOrNotApplicable(offset, "offset");
            requireIndexOrNotApplicable(length, "length");
        }

        /**
         * Renders this difference as one self-contained, multi-line block: what it is, where it is, what
         * was expected, what was observed, and why that matters.
         *
         * <p>Both values go through the value renderer, so trailing spaces are counted, non-printing
         * bytes are escaped and lengths are stated. The declared offset and length are printed whenever
         * they apply, which is what saves a reviewer from counting bytes in a copybook to find out
         * where the difference sits.
         *
         * @return the rendered block, never {@code null} and never truncated
         */
        public String render() {
            StringBuilder text = new StringBuilder()
                .append(kind)
                .append(" at ")
                .append(dataset);
            if (rowIndex != NOT_APPLICABLE) {
                text.append(" row ").append(rowIndex);
            }
            text.append(" field ").append(fieldName);
            if (offset != NOT_APPLICABLE && length != NOT_APPLICABLE) {
                text.append(" (offset ").append(offset).append(", length ").append(length).append(')');
            } else if (length != NOT_APPLICABLE) {
                text.append(" (length ").append(length).append(')');
            }
            return text.append(System.lineSeparator())
                .append("    expected: ").append(describeValue(expected))
                .append(System.lineSeparator())
                .append("    actual:   ").append(describeValue(actual))
                .append(System.lineSeparator())
                .append("    because:  ").append(explanation)
                .toString();
        }

        /**
         * The rendered block, so a difference printed by an assertion library reads the same way it
         * does in a report.
         *
         * @return {@link #render()}
         */
        @Override
        public String toString() {
            return render();
        }

        /** Rejects a negative index that is not the explicit not-applicable sentinel. */
        private static void requireIndexOrNotApplicable(int value, String member) {
            if (value < 0 && value != NOT_APPLICABLE) {
                throw new IllegalArgumentException("Diff." + member + " is " + value
                    + "; it must be zero or positive, or exactly " + NOT_APPLICABLE
                    + " to state that the concept does not apply");
            }
        }
    }

    /**
     * The rows one dataset carried on one channel, together with the layout that describes them.
     *
     * <p>Rows are held as <strong>bytes</strong> because bytes are what a repository writes and bytes
     * are what parity is about; a row that is the wrong width is still perfectly representable here,
     * which is what lets the differ report the width rather than fail to hold the row at all. Every
     * array is copied on the way in and on the way out, so a fingerprint cannot be perturbed after it
     * is captured and two comparisons over the same fingerprint cannot interfere.
     *
     * <p>This is a class rather than a record deliberately: a record holding an array would publish
     * {@code equals} and {@code hashCode} that compare arrays by identity, which is a trap for any
     * caller who reasonably assumes a record has value semantics.
     */
    public static final class DatasetOutput {

        /** The dataset binding key, never a literal dataset name. */
        private final String dataset;

        /** The layout describing every row, transcribed from the dataset's copybook. */
        private final RecordLayout layout;

        /** The rows in order; each element is privately owned and never handed out. */
        private final List<byte[]> rows;

        /** Copies every row so the instance owns its bytes outright. */
        private DatasetOutput(String dataset, RecordLayout layout, List<byte[]> rows) {
            this.dataset = dataset;
            this.layout = layout;
            List<byte[]> copies = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                byte[] row = rows.get(index);
                if (row == null) {
                    throw new IllegalArgumentException("DatasetOutput row " + index + " of dataset "
                        + dataset + " is null; a row the unit did not produce must be absent from the "
                        + "list rather than present as a hole in the order");
                }
                copies.add(row.clone());
            }
            this.rows = Collections.unmodifiableList(copies);
        }

        /**
         * Captures rows already held as bytes - the shape a repository or a fixed-width writer produces.
         *
         * @param dataset the dataset binding key; never blank
         * @param layout the layout describing the rows; never {@code null}
         * @param rows the rows in order; never {@code null} and never containing {@code null},
         *     though it may be empty for a dataset the unit opened and did not write to
         * @return the captured output
         * @throws NullPointerException if {@code dataset}, {@code layout} or {@code rows} is
         *     {@code null}
         * @throws IllegalArgumentException if {@code dataset} is blank or a row is {@code null}
         */
        public static DatasetOutput of(String dataset, RecordLayout layout, List<byte[]> rows) {
            return new DatasetOutput(requireDatasetKey(dataset), requireLayout(layout, dataset),
                Objects.requireNonNull(rows, "DatasetOutput rows are required; pass an empty list for "
                    + "a dataset the unit produced nothing for"));
        }

        /**
         * Captures rows held as text, encoding each with an <strong>explicitly named</strong> charset.
         *
         * <p>The charset is a parameter and is never defaulted, because the pad and digit bytes of a
         * fixed-width record are code-page dependent: a space is {@code 0x40} under {@code IBM037} and
         * {@code 0x20} under {@code US-ASCII}. Pass the same charset the differ's codec uses.
         *
         * @param dataset the dataset binding key; never blank
         * @param layout the layout describing the rows; never {@code null}
         * @param images the row images in order; never {@code null} and never containing {@code null}
         * @param charset the code page to encode the images with; never {@code null}
         * @return the captured output
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code dataset} is blank or an image is {@code null}
         */
        public static DatasetOutput ofImages(String dataset,
                                            RecordLayout layout,
                                            List<String> images,
                                            Charset charset) {
            Objects.requireNonNull(images, "DatasetOutput row images are required; pass an empty list "
                + "for a dataset the unit produced nothing for");
            Objects.requireNonNull(charset, "A charset is required to encode row images and is never "
                + "defaulted: a space is 0x40 under IBM037 and 0x20 under US-ASCII, so the code page "
                + "changes the bytes");
            List<byte[]> encoded = new ArrayList<>(images.size());
            for (int index = 0; index < images.size(); index++) {
                String image = images.get(index);
                if (image == null) {
                    throw new IllegalArgumentException("DatasetOutput row image " + index
                        + " of dataset " + dataset + " is null; a row the unit did not produce must be "
                        + "absent from the list rather than present as a hole in the order");
                }
                encoded.add(image.getBytes(charset));
            }
            return of(dataset, layout, encoded);
        }

        /**
         * Captures a dataset the unit produced nothing for, which is how a fingerprint states that a
         * dataset was opened and left alone.
         *
         * @param dataset the dataset binding key; never blank
         * @param layout the layout that would have described the rows; never {@code null}
         * @return an output carrying no rows
         */
        public static DatasetOutput empty(String dataset, RecordLayout layout) {
            return of(dataset, layout, List.of());
        }

        /**
         * The dataset binding key.
         *
         * @return the key, never blank
         */
        public String dataset() {
            return dataset;
        }

        /**
         * The layout describing every row of this dataset.
         *
         * @return the layout, never {@code null}
         */
        public RecordLayout layout() {
            return layout;
        }

        /**
         * How many rows this channel carries for the dataset.
         *
         * @return the row count, never negative
         */
        public int rowCount() {
            return rows.size();
        }

        /**
         * Whether a 0-based row index addresses a row that exists.
         *
         * @param rowIndex the 0-based index
         * @return {@code true} when the index is within the captured rows
         */
        public boolean hasRow(int rowIndex) {
            return rowIndex >= 0 && rowIndex < rows.size();
        }

        /**
         * A copy of one row's bytes.
         *
         * @param rowIndex the 0-based index
         * @return a fresh copy of the row, so a caller cannot mutate the captured fingerprint
         * @throws IndexOutOfBoundsException if the index addresses no captured row
         */
        public byte[] row(int rowIndex) {
            if (!hasRow(rowIndex)) {
                throw new IndexOutOfBoundsException("Dataset " + dataset + " holds " + rows.size()
                    + " row(s); there is no row at 0-based index " + rowIndex);
            }
            return rows.get(rowIndex).clone();
        }

        /**
         * A short description naming the dataset, its row count and its declared record length. No row
         * content, because a {@code USRSEC} row carries a credential span.
         *
         * @return the description, never {@code null}
         */
        @Override
        public String toString() {
            return "DatasetOutput[" + dataset + ", " + rows.size() + " row(s) of declared length "
                + layout.recordLength() + "]";
        }

        /** Requires a binding key rather than a dataset name, matching the case model's own rule. */
        private static String requireDatasetKey(String dataset) {
            Objects.requireNonNull(dataset, "DatasetOutput dataset is required and must be the "
                + "binding key declared in application.yml, never a literal dataset name");
            if (dataset.isBlank()) {
                throw new IllegalArgumentException("DatasetOutput dataset must not be blank");
            }
            return dataset;
        }

        /** Requires a layout, naming the dataset in the failure so the caller knows which one. */
        private static RecordLayout requireLayout(RecordLayout layout, String dataset) {
            return Objects.requireNonNull(layout, "A RecordLayout is required for dataset " + dataset
                + ": the differ decodes each row through it, and its own self-check is what proves "
                + "every FILLER is declared and the spans total the declared record length");
        }
    }

    /**
     * One screen send as it was actually observed: the BMS payload it carried and the attribute
     * metadata it set.
     *
     * <p>The observation mirror of {@link ScreenSend}, and deliberately a separate type: the
     * expectation is validated at load time against the symbolic-map naming rules, whereas an
     * observation is whatever the unit produced and must be representable even when it is wrong. A
     * shared type would have to relax the expectation's validation to hold a defective observation,
     * which is the wrong trade.
     *
     * @param fields the {@code xxxO} output items the send carried, keyed by symbolic-map name
     * @param attributes the attribute items the send set, keyed by symbolic-map name and valued with
     *     the {@code DFHBMSCA} or {@code DFHATTR} mnemonic
     */
    public record ObservedSend(Map<String, String> fields, Map<String, String> attributes) {

        /**
         * Freezes both maps so an observation cannot change after capture.
         *
         * @throws NullPointerException if either map is {@code null}
         */
        public ObservedSend {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(fields, "ObservedSend fields are required; pass an empty map "
                    + "for a send that carried none")));
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(attributes, "ObservedSend attributes are required; pass an "
                    + "empty map for a send that set none")));
        }

        /**
         * Captures a send that carried payload fields and set no attribute.
         *
         * @param fields the {@code xxxO} items keyed by symbolic-map name
         * @return the observation
         */
        public static ObservedSend ofFields(Map<String, String> fields) {
            return new ObservedSend(fields, Map.of());
        }

        /**
         * Renders the send without printing a field value, because {@code PASSWDO} is one of them.
         *
         * @return the shape of this send
         */
        @Override
        public String toString() {
            return "ObservedSend[fields=" + fields.keySet() + ", attributes=" + attributes + ']';
        }
    }

    /**
     * The online response as it was actually observed.
     *
     * <p>The observation mirror of {@link ExpectedResponse}, and a separate type for the same reason
     * {@link ObservedSend} is: an observation has to be representable even when it is wrong, so it
     * carries no validation of its own beyond freezing.
     *
     * @param nextProgram the program the response named as the next target, or {@code null}
     * @param nextMapset the mapset the response named, or {@code null}
     * @param nextMap the map the response named, or {@code null}
     * @param navigation the {@code CARDDEMO-COMMAREA} field values the response carried
     * @param sends every screen send in order
     * @param cursorField the symbolic-map length item that received the cursor, or {@code null}
     * @param termination how the transaction ended, or {@code null} when the unit did not say
     */
    public record ObservedResponse(String nextProgram,
                                   String nextMapset,
                                   String nextMap,
                                   Map<String, String> navigation,
                                   List<ObservedSend> sends,
                                   String cursorField,
                                   Termination termination) {

        /**
         * Freezes the collections so an observation cannot change after capture.
         *
         * @throws NullPointerException if {@code navigation} or {@code sends} is {@code null}
         */
        public ObservedResponse {
            navigation = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(navigation, "ObservedResponse navigation is required; pass an "
                    + "empty map for a response carrying no commarea field")));
            sends = List.copyOf(Objects.requireNonNull(sends, "ObservedResponse sends are required; "
                + "pass an empty list for a path that sends no map"));
        }

        /**
         * Renders the response without printing a screen field value.
         *
         * @return the shape of this response
         */
        @Override
        public String toString() {
            return "ObservedResponse[nextProgram=" + nextProgram + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap + ", navigation=" + navigation.keySet()
                + ", sends=" + sends.size() + ", cursorField=" + cursorField
                + ", termination=" + termination + ']';
        }
    }

    /**
     * Everything one run of a unit under test observably produced: the records it wrote in order, the
     * state each dataset was left in, the online response it returned, the {@code RETURN-CODE} it set
     * and the lines it emitted.
     *
     * <p>This is the "behavioural fingerprint" the harness captures and the differ judges. It is
     * declared here rather than in the harness on purpose: the differ is the consumer, so it declares
     * the shape it needs, which leaves the harness free to assemble one from a batch tasklet's writes,
     * a service's return value or a controller invoked as a plain object - without the differ knowing
     * or caring which.
     *
     * <p>The writes and the final state are held <strong>separately</strong> because they answer
     * different questions and a single map cannot answer both. "Did the unit write the right records,
     * in the right order?" is about the writes. "Is the dataset in the right state now?" is about the
     * final state, and for a rejection path the honest answer is "exactly as seeded" - which is a
     * positive assertion that requires the seeded rows to be visible on that channel and absent from
     * the write channel.
     *
     * <p>Immutable once built, and it never carries an HTTP response, a {@code JobExecution} or any
     * other framework object. A parity assertion is about arithmetic and byte layout, and neither is
     * improved by putting a servlet container or a job repository between the assertion and the code.
     */
    public static final class Fingerprint {

        /** Dataset key to the rows the unit wrote, in the order the harness declared them. */
        private final Map<String, DatasetOutput> writes;

        /** Dataset key to the rows the dataset holds after the run. */
        private final Map<String, DatasetOutput> finalState;

        /** The online response, or {@code null} for a unit that returns none. */
        private final ObservedResponse response;

        /** The COBOL {@code RETURN-CODE} the run ended with, mapped onto the batch exit status. */
        private final int returnCode;

        /** The emitted lines, in emission order, each on its declared channel. */
        private final List<EmittedMessage> messages;

        /** Freezes every channel, rejecting a duplicate dataset key within either record channel. */
        private Fingerprint(List<DatasetOutput> writes,
                            List<DatasetOutput> finalState,
                            ObservedResponse response,
                            int returnCode,
                            List<EmittedMessage> messages) {
            this.writes = freezeOutputs(writes, "writes");
            this.finalState = freezeOutputs(finalState, "finalState");
            this.response = response;
            this.returnCode = returnCode;
            List<EmittedMessage> emitted = new ArrayList<>(messages.size());
            for (int index = 0; index < messages.size(); index++) {
                EmittedMessage message = messages.get(index);
                if (message == null) {
                    throw new IllegalArgumentException("Fingerprint message " + index + " is null; "
                        + "capture an EmittedMessage with an empty text on the DISPLAY_LINE channel "
                        + "for a blank emitted line, which is what a COBOL DISPLAY of a blank line "
                        + "produces");
                }
                emitted.add(message);
            }
            this.messages = Collections.unmodifiableList(emitted);
        }

        /**
         * Captures a complete fingerprint.
         *
         * @param writes the records the unit wrote, per dataset, in write order; never {@code null}
         * @param finalState what each dataset holds after the run; never {@code null}
         * @param response the online response, or {@code null} for a unit that returns none
         * @param returnCode the COBOL {@code RETURN-CODE} the run ended with; never negative
         * @param messages the emitted lines in emission order; never {@code null}, and never
         *     containing {@code null}
         * @return the captured fingerprint
         * @throws NullPointerException if {@code writes}, {@code finalState} or {@code messages} is
         *     {@code null}
         * @throws IllegalArgumentException if {@code returnCode} is negative, an element is
         *     {@code null}, or a dataset key appears twice within one channel
         */
        public static Fingerprint of(List<DatasetOutput> writes,
                                     List<DatasetOutput> finalState,
                                     ObservedResponse response,
                                     int returnCode,
                                     List<EmittedMessage> messages) {
            Objects.requireNonNull(writes, "Fingerprint writes are required; pass an empty list for a "
                + "unit that writes nothing");
            Objects.requireNonNull(finalState, "Fingerprint finalState is required; pass an empty "
                + "list only when the unit touches no dataset at all - a unit that READ a dataset and "
                + "wrote nothing must still report that dataset's unchanged rows, because that is the "
                + "assertion such a case makes");
            Objects.requireNonNull(messages, "Fingerprint messages are required; pass an empty list "
                + "for a unit that emits nothing");
            if (returnCode < 0) {
                throw new IllegalArgumentException("Fingerprint returnCode is " + returnCode
                    + "; a z/OS step return code is never negative, so a negative value is a sign "
                    + "error in the capture rather than an observation");
            }
            return new Fingerprint(writes, finalState, response, returnCode, messages);
        }

        /**
         * Captures a fingerprint for a unit that touched no dataset, returned no response and emitted
         * no line - a pure computation, or a validation that rejected its input before doing anything.
         *
         * @param returnCode the COBOL {@code RETURN-CODE} the run ended with; never negative
         * @return the captured fingerprint
         */
        public static Fingerprint ofReturnCode(int returnCode) {
            return of(List.of(), List.of(), null, returnCode, List.of());
        }

        /**
         * The records the unit wrote, keyed by dataset and in the order the harness declared them.
         *
         * @return an immutable map in declaration order
         */
        public Map<String, DatasetOutput> writes() {
            return writes;
        }

        /**
         * What each dataset holds after the run, keyed by dataset.
         *
         * @return an immutable map in declaration order
         */
        public Map<String, DatasetOutput> finalState() {
            return finalState;
        }

        /**
         * The online response the unit returned.
         *
         * @return the response, or {@code null} for a unit that returns none
         */
        public ObservedResponse response() {
            return response;
        }

        /**
         * Looks up one dataset's writes.
         *
         * @param dataset the dataset binding key; never {@code null}
         * @return the output, or empty when the unit wrote nothing to that dataset
         * @throws NullPointerException if {@code dataset} is {@code null}
         */
        public Optional<DatasetOutput> findWrites(String dataset) {
            Objects.requireNonNull(dataset, "A dataset binding key is required to look up writes");
            return Optional.ofNullable(writes.get(dataset));
        }

        /**
         * Looks up one dataset's final state.
         *
         * @param dataset the dataset binding key; never {@code null}
         * @return the output, or empty when the fingerprint carries no final state for it
         * @throws NullPointerException if {@code dataset} is {@code null}
         */
        public Optional<DatasetOutput> findFinalState(String dataset) {
            Objects.requireNonNull(dataset,
                "A dataset binding key is required to look up a final state");
            return Optional.ofNullable(finalState.get(dataset));
        }

        /**
         * The COBOL {@code RETURN-CODE} the run ended with.
         *
         * @return the return code, never negative
         */
        public int returnCode() {
            return returnCode;
        }

        /**
         * The emitted lines, in emission order.
         *
         * @return an immutable list in emission order
         */
        public List<EmittedMessage> messages() {
            return messages;
        }

        /**
         * A short description naming each channel's datasets, the return code and the line count.
         *
         * @return the description, never {@code null}
         */
        @Override
        public String toString() {
            return "Fingerprint[writes=" + writes.keySet() + ", finalState=" + finalState.keySet()
                + ", response=" + (response == null ? "absent" : "present")
                + ", returnCode=" + returnCode + ", messages=" + messages.size() + ']';
        }

        /** Freezes one record channel into an order-preserving map, rejecting a duplicate key. */
        private static Map<String, DatasetOutput> freezeOutputs(List<DatasetOutput> outputs,
                                                               String channel) {
            Map<String, DatasetOutput> byKey = new LinkedHashMap<>();
            for (int index = 0; index < outputs.size(); index++) {
                DatasetOutput output = outputs.get(index);
                if (output == null) {
                    throw new IllegalArgumentException("Fingerprint " + channel + " entry " + index
                        + " is null; remove the entry rather than leaving a hole among the datasets");
                }
                DatasetOutput previous = byKey.put(output.dataset(), output);
                if (previous != null) {
                    throw new IllegalArgumentException("Fingerprint " + channel + " declares dataset "
                        + output.dataset() + " more than once; merge the rows into one DatasetOutput "
                        + "in order, because a second entry would silently shadow the first and the "
                        + "rows it carries would never be compared");
                }
            }
            return Collections.unmodifiableMap(byKey);
        }
    }

    /**
     * The outcome of one comparison: every difference found, in traversal order.
     *
     * <p>{@link #count()} is the number the gate is stated in terms of. A module is not complete until
     * its diff count is zero across all twenty of its cases - the gate is per module, so nineteen clean
     * cases and one difference means the module is incomplete, not ninety-five per cent done.
     *
     * <p>A fresh instance is produced by every comparison and nothing about it is shared or mutable, so
     * two tests running over the same case cannot interfere and a result captured from one run stays
     * valid after the next.
     */
    public static final class DiffResult {

        /** The program whose case produced this result, for the report header. */
        private final String program;

        /** The case identifier, so a failure is traceable to its fixture file from the header alone. */
        private final String caseId;

        /** Every difference, in traversal order. */
        private final List<Diff> entries;

        /** Freezes the list, so a result cannot change after the comparison that produced it. */
        private DiffResult(String program, String caseId, List<Diff> entries) {
            this.program = program;
            this.caseId = caseId;
            this.entries = List.copyOf(entries);
        }

        /**
         * The number of differences found - the value the parity gate is stated in terms of.
         *
         * @return the diff count, zero when the comparison is clean
         */
        public int count() {
            return entries.size();
        }

        /**
         * Whether the comparison found nothing.
         *
         * @return {@code true} when {@link #count()} is zero
         */
        public boolean isClean() {
            return entries.isEmpty();
        }

        /**
         * Every difference, in traversal order.
         *
         * @return an immutable list; empty when the comparison is clean
         */
        public List<Diff> entries() {
            return entries;
        }

        /**
         * The program this result belongs to.
         *
         * @return the eight-character COBOL program name
         */
        public String program() {
            return program;
        }

        /**
         * The case this result belongs to.
         *
         * @return the case identifier, {@code case01} through {@code case20}
         */
        public String caseId() {
            return caseId;
        }

        /**
         * Renders the whole result as failure text a reviewer can act on without opening anything else.
         *
         * <p><strong>Every</strong> difference is rendered. The list is never truncated, never
         * summarised and never capped: a differ that hides its twentieth finding has taught its reader
         * to distrust it, and the cost of a long report is nothing beside the cost of a missed
         * difference. What is never rendered is a credential span - the callers masked those before the
         * difference was built, so the report is complete without being a disclosure.
         *
         * @return the rendered report, never {@code null}
         */
        public String render() {
            String newLine = System.lineSeparator();
            StringBuilder text = new StringBuilder()
                .append("Parity comparison ")
                .append(program)
                .append('/')
                .append(caseId)
                .append(": ")
                .append(count())
                .append(count() == 1 ? " difference" : " differences")
                .append(isClean() ? " - CLEAN, the gate is satisfied for this case."
                    : " - the gate requires a diff count of 0.");
            for (int index = 0; index < entries.size(); index++) {
                text.append(newLine)
                    .append("  [")
                    .append(index + 1)
                    .append('/')
                    .append(entries.size())
                    .append("] ")
                    .append(entries.get(index).render());
            }
            return text.toString();
        }

        /**
         * The rendered report, so an assertion failure shows the whole picture rather than an object
         * identity.
         *
         * @return {@link #render()}
         */
        @Override
        public String toString() {
            return render();
        }
    }

}
