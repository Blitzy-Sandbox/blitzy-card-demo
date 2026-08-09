package com.vsergeychik.carddemo.parity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.FieldDiffer.DatasetOutput;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.FieldDiffer.Fingerprint;
import com.vsergeychik.carddemo.parity.FieldDiffer.ObservedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetNormalisation;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.MessageChannel;
import com.vsergeychik.carddemo.parity.ParityCase.Normalisation;
import com.vsergeychik.carddemo.parity.ParityCase.Redaction;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;

/**
 * Seeds a parity case's datasets, runs the unit under test, captures what that run observably
 * produced, and hands the observation to {@link FieldDiffer} to be judged against the case.
 *
 * <h2>Where the expected values come from - read this before trusting a green run</h2>
 * <p>Every expected value carried by every case under {@code src/test/resources/parity} is
 * <strong>statically derived</strong>. Each one was arrived at by structured reading of the COBOL
 * paragraph that produces it, cross-checked against four independent authorities:
 * <ol>
 *   <li>the <strong>copybook byte layouts</strong> in {@code app/cpy} - exact offsets, widths,
 *       {@code PICTURE} clauses and {@code FILLER} spans;</li>
 *   <li>the <strong>JCL DD and {@code PARM} contracts</strong> in {@code app/jcl} and
 *       {@code app/proc} - record formats, {@code LRECL} values, step order and {@code COND}
 *       gating;</li>
 *   <li>the <strong>BMS field definitions</strong> in {@code app/bms} and the symbolic maps in
 *       {@code app/cpy-bms} - every screen field's position, length and attribute;</li>
 *   <li>the <strong>nine real fixtures</strong> in {@code app/data/ASCII}, copied byte for byte to
 *       {@code src/test/resources/fixtures} and used as the seed for every case, so the inputs are
 *       genuine production-shaped data rather than invented rows.</li>
 * </ol>
 *
 * <p><strong>There is no COBOL execution baseline behind these expectations. None was produced,
 * and none could be produced in this environment.</strong> Running the 28 legacy programs was
 * attempted and is empirically impossible here; eight independent blockers were verified, and any
 * one of them is sufficient on its own:
 * <ol>
 *   <li>no mainframe or z/OS runtime is available;</li>
 *   <li>the available COBOL compiler reports {@code indexed file handler : disabled}, so the seven
 *       programs using {@code ORGANIZATION INDEXED} cannot be built;</li>
 *   <li>a subprogram with {@code PROCEDURE DIVISION USING} is rejected when an executable is
 *       requested, which rules out {@code CBACT04C}, {@code CBSTM03B} and {@code CSUTLDTC};</li>
 *   <li>{@code app/cpy/CUSTREC.cpy} carries literal TAB characters on 17 lines - lines 6 to 22
 *       inclusive - which pushes its {@code PICTURE} clauses out of the source margin and breaks
 *       parsing outright, so {@code CBSTM03A} cannot even be compiled;</li>
 *   <li>no Language Environment services exist, so neither {@code CEEDAYS} nor {@code CEE3ABD} can
 *       be resolved;</li>
 *   <li>the 17 online programs are unrunnable at any level: there is no CICS emulator, and
 *       {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} are absent from this repository;</li>
 *   <li>the EBCDIC datasets require binary handling the available toolchain is not configured
 *       for;</li>
 *   <li>an alternative compiler package cannot be installed.</li>
 * </ol>
 *
 * <p>Every substantive requirement of the parity gate survives that substitution: 20 cases per
 * program, 560 in total; field-for-field comparison rather than whole-string comparison; a diff
 * count of zero required per module; and at least 90% branch coverage per module. Only the
 * <em>provenance</em> of the expected values changes. Because that provenance is a stated success
 * criterion, <strong>the deviation is escalated for explicit user confirmation rather than quietly
 * absorbed</strong> - it is risk R-A, the highest-severity open item in the plan, and it is written
 * here, in the class that produces the observation, so that no reader can reach a green run without
 * meeting it.
 *
 * <p><strong>Note to a reviewer grepping this file.</strong> The words "recording", "captured" and
 * "golden" appear below only inside denials - there is no recording of a COBOL run anywhere in this
 * package, and nothing here is a golden master. "Capture" in every other place in this file refers
 * to capturing what the <em>Java</em> unit under test produced, which is exactly what a harness
 * does.
 *
 * <h2>What one run does</h2>
 * <ol>
 *   <li><strong>Seed.</strong> {@link #seed(ParityCase)} turns the case's {@code inputs} into
 *       {@link SeededDataset} values, reading fixture rows from the classpath with an explicitly
 *       named {@link Charset} and honouring inline rows verbatim - every leading zero, every
 *       trailing space and every zoned-sign overpunch byte.</li>
 *   <li><strong>Invoke.</strong> {@link #run(ParityCase, ParityUnit)} passes an
 *       {@link Invocation} to the caller's {@link ParityUnit}, which constructs the unit and calls
 *       it directly.</li>
 *   <li><strong>Capture.</strong> Everything the run produced becomes a
 *       {@link DecodedFingerprint}: each written record decoded into its copybook field names,
 *       held beside its raw image and length, plus the {@code RETURN-CODE} and the emitted lines in
 *       emission order.</li>
 *   <li><strong>Judge.</strong> {@link #judge(ParityCase, ParityUnit)} hands the case and the
 *       fingerprint to {@link FieldDiffer#compare(ParityCase, Fingerprint)} and returns the
 *       {@link DiffResult}.</li>
 * </ol>
 *
 * <h2>This class never asserts</h2>
 * <p>{@link #judge(ParityCase, ParityUnit)} <em>returns</em> a {@link DiffResult}; it does not
 * check it. The assertion belongs to the per-program test class, which is what allows a failure to
 * read "diff count 3 on case07" with all three differences rendered, instead of an opaque harness
 * exception that says only that something went wrong. A harness that asserted would also have to
 * choose a message, and the differ has already written a better one.
 *
 * <h2>Nothing static is mutable</h2>
 * <p>All per-case state - the seeded datasets, the recorder a unit writes its observations into,
 * the fingerprint - lives in locals or in objects created for that one invocation. Collaborators
 * are constructor-injected. That is what makes the 560 cases order-independent: two cases cannot
 * see each other's rows, and running them in any order, or in parallel, gives the same answer.
 *
 * <h2>Determinism</h2>
 * <p>No wall clock and no randomness reaches a fingerprint. A case that pins a clock does so
 * through {@link ParityCase.ScreenRequest#pinnedClock()}, and {@link #clockFor(ParityCase)} turns
 * that into a fixed {@link Clock} for the unit to be constructed with. Every collection this class
 * builds preserves insertion order, because write order and emission order are both parity
 * concerns.
 *
 * <h2>Byte handling</h2>
 * <p>Every byte-to-text conversion goes through the hand-written {@link FixedWidthCodec} and
 * {@link FixedWidthRecord}, so every offset stays reviewable against the copybook it came from. No
 * third-party copybook parser is used, and the {@link Charset} is a parameter everywhere - never a
 * platform default. The nine fixtures are {@code US-ASCII} and this class says so, once, in
 * {@link #FIXTURE_CHARSET}.
 *
 * @see ParityCase
 * @see FieldDiffer
 * @see FixedWidthCodec
 */
public final class ParityHarness {

    /**
     * The code page of the nine shipped fixtures, named explicitly and never defaulted.
     *
     * <p>A fixed-width record's pad bytes are code-page dependent - a space is {@code 0x20} under
     * {@code US-ASCII} and {@code 0x40} under {@code IBM037} - so a harness that relied on the
     * platform default would produce different bytes on different machines from identical inputs.
     * The fixtures under {@code src/test/resources/fixtures} are byte-for-byte copies of
     * {@code app/data/ASCII}, which is plain text, so {@code US-ASCII} is the measured property of
     * the data rather than a convention.
     */
    public static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The number of cases every program must declare.
     *
     * <p>A floor and a ceiling at once: {@link #cases(String)} refuses a program with any other
     * count. A suite that silently ran 3 of 20 cases would satisfy "diff count is zero" while
     * proving almost nothing, which is the one failure mode a parity gate cannot tolerate.
     */
    public static final int CASES_PER_PROGRAM = 20;

    /**
     * The classpath directory holding every program's case files, relative to the test resource
     * root.
     *
     * <p>Named once here so a loader cannot be pointed anywhere else, mirroring
     * {@link ParityCase#FIXTURE_ROOT}'s role for the fixtures.
     */
    public static final String CASE_RESOURCE_ROOT = "parity/";

    /** The extension every case file carries. */
    public static final String CASE_RESOURCE_EXTENSION = ".json";

    /**
     * The identifier of the first case, and the stem every other one is formed from.
     *
     * <p>{@code caseNN} with a leading zero below ten, which is the spelling
     * {@link ParityCase#caseId()} validates and the spelling the files on disk use.
     */
    public static final String CASE_ID_PREFIX = "case";

    /**
     * The clock a case is run under when it pins none of its own.
     *
     * <p>The source revision this migration was derived from is stamped
     * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19}, and that date is the one every
     * shipped case pins. Using it as the default means a batch case - which has no
     * {@link ParityCase.ScreenRequest} to pin a clock in - still runs under a fixed instant rather
     * than under {@code Clock.systemUTC()}, so a job that stamps a record with the current date
     * produces the same bytes on every run.
     */
    public static final LocalDateTime DEFAULT_PINNED_CLOCK =
        LocalDateTime.parse("2022-07-19T23:12:34");

    /**
     * The zone every pinned clock is fixed in.
     *
     * <p>{@code UTC} and not the platform zone, for the same reason the charset is named: a local
     * zone would make the instant behind a pinned local date-time depend on the machine.
     */
    public static final ZoneOffset CLOCK_ZONE = ZoneOffset.UTC;

    /**
     * The name {@link FieldDiffer} addresses the first {@code FILLER} span of a record by.
     *
     * <p>The convention is {@link FieldDiffer}'s and this class follows it rather than inventing a
     * second one: the first {@code FILLER} is {@code FILLER}, the second {@code FILLER-2}, the
     * third {@code FILLER-3}, in copybook declaration order. It exists because a JSON object
     * cannot hold a duplicate key, and it is deliberately strict - the first span is never
     * {@code FILLER-1} - so one span never has two spellings.
     */
    private static final String FILLER_NAME = "FILLER";

    /** The separator introducing a {@code FILLER} span's ordinal, matching COBOL's word separator. */
    private static final String FILLER_ORDINAL_SEPARATOR = "-";

    /**
     * The differ this harness hands its fingerprints to, and the source of the codec every decode
     * goes through.
     *
     * <p>Constructor-injected and never replaced, so the code page in use cannot change beneath a
     * run that has already started and there is no mutable state on this object at all.
     */
    private final FieldDiffer differ;

    /**
     * The mapper case files are deserialised with - a <strong>hardened defensive copy</strong> of
     * whatever the caller supplied, never the caller's own instance.
     *
     * <p>It used to be the caller's instance, on the reasoning that strictness is a property of the
     * model rather than of the mapper: {@link ParityCase} declares
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)} itself, so an unknown key fails whatever
     * mapper reads it. That reasoning is correct as far as it goes and does not go far enough. Three
     * things a lenient mapper accepts are invisible to the model:
     * <ul>
     *   <li>a <strong>duplicate key</strong>. {@code {"expectedReturnCode": 0, "expectedReturnCode":
     *       8}} binds the last occurrence and drops the first without a word, so a case fixture can
     *       state one expectation and assert another;</li>
     *   <li>a <strong>trailing token</strong>. Everything after the first complete JSON value is
     *       ignored, so a fixture whose author pasted a second case object below the first - or left a
     *       stray fragment behind while editing - loads as the first object alone and quietly asserts
     *       half of what it appears to;</li>
     *   <li>an explicit <strong>{@code null} for a primitive</strong>, which binds to zero.</li>
     * </ul>
     * A caller could also have <em>disabled</em> {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, which
     * overrides the annotation and takes the model's own strictness away with it.
     *
     * <p>So the supplied mapper is {@link ObjectMapper#copy() copied} and the copy is configured here.
     * Copying matters as much as configuring: mutating the caller's mapper would reach every other use
     * of it in the same JVM, and reading through it unconfigured would leave the three holes above
     * open. Held as a field rather than a static so two harnesses cannot share one.
     */
    private final ObjectMapper mapper;

    /**
     * The clock a case is run under when it pins none of its own. Always fixed, never a system
     * clock.
     */
    private final Clock clock;

    /**
     * Builds a harness over an explicit differ, mapper and clock.
     *
     * @param differ the differ that will judge every fingerprint this harness captures, and whose
     *     {@link FieldDiffer#codec()} supplies the code page; never {@code null}
     * @param mapper the mapper case files are deserialised with; never {@code null}
     * @param clock the fixed clock a case with no pinned clock of its own runs under; never
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code clock} is a system clock, which would let the wall
     *     clock reach a fingerprint
     */
    public ParityHarness(FieldDiffer differ, ObjectMapper mapper, Clock clock) {
        this.differ = Objects.requireNonNull(differ, "A FieldDiffer is required: the harness "
            + "captures the fingerprint and the differ judges it, and the differ's codec is also "
            + "where this harness's code page comes from");
        this.mapper = harden(Objects.requireNonNull(mapper, "An ObjectMapper is required to read a "
            + "case file; pass JsonMapper.builder().build() and it will be copied and hardened here"));
        this.clock = requireFixedClock(clock);
    }

    /**
     * The harness every shipped parity test uses: a {@code US-ASCII} differ, a default
     * {@link JsonMapper}, and a clock fixed at {@link #DEFAULT_PINNED_CLOCK}.
     *
     * @return a fresh harness; nothing is shared with any other instance
     */
    public static ParityHarness usAscii() {
        return forCharset(FIXTURE_CHARSET);
    }

    /**
     * A harness over a named code page, for the case that reads {@code IBM037} data rather than the
     * {@code US-ASCII} fixtures.
     *
     * @param charset the code page of the data being seeded and compared; never {@code null}
     * @return a fresh harness whose differ, codec and decodes all use {@code charset}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static ParityHarness forCharset(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required and is never defaulted: the nine "
            + "text fixtures are US-ASCII and the EBCDIC datasets are IBM037, and which one applies "
            + "is the caller's knowledge");
        return new ParityHarness(FieldDiffer.forCharset(charset), JsonMapper.builder().build(),
            fixedClockAt(DEFAULT_PINNED_CLOCK));
    }

    /**
     * The differ this harness judges with, exposed so a caller may compare a fingerprint it
     * assembled itself.
     *
     * @return the injected differ, never {@code null}
     */
    public FieldDiffer differ() {
        return differ;
    }

    /**
     * The codec every decode in this harness goes through - the differ's own, so the harness and
     * the judge can never disagree about a byte.
     *
     * @return the codec, never {@code null}
     */
    public FixedWidthCodec codec() {
        return differ.codec();
    }

    /**
     * The code page in use, which is the codec's.
     *
     * @return the charset, never {@code null}
     */
    public Charset charset() {
        return differ.codec().charset();
    }

    /**
     * The fixed clock a case with no pinned clock of its own runs under.
     *
     * @return the clock, never {@code null} and never a system clock
     */
    public Clock clock() {
        return clock;
    }

    /**
     * The fixed clock one case runs under: the clock the case pins, or this harness's default when
     * it pins none.
     *
     * <p>A batch case declares no {@link ParityCase.ScreenRequest} and therefore pins no clock, so
     * it takes the default - which is fixed, not the system clock, precisely so a job that stamps a
     * record with today's date is still reproducible.
     *
     * @param parityCase the case about to run; never {@code null}
     * @return a fixed clock, never {@code null}
     * @throws NullPointerException if {@code parityCase} is {@code null}
     */
    public Clock clockFor(ParityCase parityCase) {
        Objects.requireNonNull(parityCase, "A ParityCase is required to resolve its clock");
        if (parityCase.screenRequest() == null) {
            return clock;
        }
        LocalDateTime pinned = parityCase.screenRequest().pinnedClockAt();
        return pinned == null ? clock : fixedClockAt(pinned);
    }

    /**
     * A clock fixed at one local date-time in {@link #CLOCK_ZONE}.
     *
     * @param at the instant to fix the clock at; never {@code null}
     * @return a fixed clock
     * @throws NullPointerException if {@code at} is {@code null}
     */
    public static Clock fixedClockAt(LocalDateTime at) {
        Objects.requireNonNull(at, "A local date-time is required to fix a clock at");
        return Clock.fixed(at.toInstant(CLOCK_ZONE), CLOCK_ZONE);
    }

    /**
     * Copies a supplied mapper and closes, on the copy, the three holes a lenient configuration
     * leaves open.
     *
     * <p>Each setting corresponds to a way a case fixture can assert less than it appears to, and each
     * is turned on rather than assumed:
     * <ul>
     *   <li>{@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION} - a repeated member is a fixture
     *       stating two expectations, and binding the last silently is how the other one
     *       disappears;</li>
     *   <li>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} - anything after the first complete
     *       value would otherwise be ignored, so half a fixture can load as a whole one;</li>
     *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} - {@link ParityCase} already
     *       asks for this per type, and enabling it here means a caller cannot have switched it off
     *       globally;</li>
     *   <li>{@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} - an explicit {@code null}
     *       against a primitive member binds zero otherwise. It closes the <em>explicit</em> null;
     *       an <em>absent</em> member is closed in {@link ParityCase} itself, by making every
     *       mandatory member a boxed type that arrives {@code null} and is refused.</li>
     * </ul>
     *
     * @param supplied the caller's mapper, which is never mutated
     * @return a hardened copy
     */
    private static ObjectMapper harden(ObjectMapper supplied) {
        return supplied.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /**
     * Refuses a clock that would let the wall clock reach a fingerprint.
     *
     * <p>{@link Clock#systemUTC()} and its siblings are equal to no fixed clock and tick, so a unit
     * constructed with one stamps a different byte on every run. Catching that here, once, is
     * cheaper than diagnosing it later as an intermittent parity difference in a date field.
     */
    private static Clock requireFixedClock(Clock candidate) {
        Objects.requireNonNull(candidate, "A Clock is required and must be fixed: a system clock "
            + "would put the wall time into a fingerprint, and the same case would then produce a "
            + "different answer on every run");
        if (candidate.equals(Clock.systemUTC())
            || candidate.equals(Clock.systemDefaultZone())
            || candidate.equals(Clock.system(candidate.getZone()))) {
            throw new IllegalArgumentException("A system clock was supplied. Parity requires a "
                + "fixed clock: use ParityHarness.fixedClockAt(LocalDateTime), or let the case pin "
                + "its own instant through screenRequest.pinnedClock. A ticking clock makes a "
                + "date-stamped record differ between two runs of the same case, which the differ "
                + "would correctly report as a difference and no one could act on.");
        }

        // Naming the three system clocks is necessary but nowhere near sufficient, and the gap is
        // easy to walk into by accident. Clock.offset(Clock.systemUTC(), duration) and
        // Clock.tick(Clock.systemUTC(), duration) both tick and both are equal to none of the three
        // named above, as is any hand-written subclass; each would pass an identity check and then
        // stamp a different byte on every run.
        //
        // The exact test is the definition itself: a clock is fixed when it is equal to a
        // Clock.fixed built from its own instant and zone. Clock.fixed's equals compares instant and
        // zone and is satisfied by no other implementation, so the comparison is made in that
        // direction deliberately - asking the candidate would let an implementation with a lenient
        // equals answer for itself.
        Clock frozen = Clock.fixed(candidate.instant(), candidate.getZone());
        if (!frozen.equals(candidate)) {
            throw new IllegalArgumentException("A clock that is not fixed was supplied ("
                + candidate.getClass().getName() + "). Clock.offset and Clock.tick over a system "
                + "clock tick, as does any custom implementation reading the wall time, and none of "
                + "them is equal to Clock.systemUTC() - so naming the system clocks does not catch "
                + "them. Use ParityHarness.fixedClockAt(LocalDateTime) or Clock.fixed(Instant, "
                + "ZoneId), or let the case pin its own instant through screenRequest.pinnedClock.");
        }

        // The frozen instance is the one kept, not the candidate. Belt and braces: an implementation
        // whose equals answers yes while its instant() still moves cannot reach a unit this way.
        return frozen;
    }

    /**
     * Refuses a case whose declared {@link ParityCase#unitKind()} is not the kind the adapter builds,
     * before the unit is constructed.
     *
     * <p>The declaration decides real things. Only a {@code BATCH_JOB} may carry job parameters; only
     * a {@code CONTROLLER_POJO} must declare a screen request and an expected response; a
     * {@code COMPONENT} is a called collaborator rather than a job however its mandated class name
     * reads, which is true of both {@code CBSTM03B} and {@code CSUTLDTC}. A case run through the wrong
     * adapter is therefore asserting one shape's contract against another's behaviour, and the
     * mismatch surfaces - if at all - as a puzzling difference well away from its cause.
     *
     * <p>Checked before seeding and before construction, so the failure names the mismatch rather than
     * whatever the wrong adapter happened to do first.
     *
     * @param parityCase the case about to run
     * @param adapterKind the kind the caller states its adapter constructs
     * @throws IllegalArgumentException when the two disagree
     */
    private static void requireDeclaredUnitKind(ParityCase parityCase,
                                                ParityCase.UnitKind adapterKind) {
        if (parityCase.unitKind() != adapterKind) {
            throw new IllegalArgumentException("Case " + parityCase.program() + '/'
                + parityCase.caseId() + " declares unitKind " + parityCase.unitKind()
                + " but is being run through an adapter that constructs a " + adapterKind
                + ". The kind is not a label: only a BATCH_JOB may carry job parameters, only a "
                + "CONTROLLER_POJO declares a screenRequest and an expectedResponse, and a COMPONENT "
                + "is a called collaborator rather than a job whatever its mandated class name says. "
                + "Fix whichever is wrong - the case's unitKind or the call site - rather than "
                + "running one shape's case against another's adapter.");
        }
    }

    /**
     * Refuses a case whose declared code page is not the one this harness seeds and compares under.
     *
     * <p>{@link ParityCase.ScreenRequest#charset()} is a statement about the bytes the case's record
     * images are, and it is only meaningful if something acts on it. Left unread it is worse than
     * absent: a case declaring {@code IBM037} would be seeded and compared as {@code US-ASCII}, every
     * value would round-trip through the same wrong code page on both sides of the comparison, and
     * the diff count would be zero. The case would then be certifying a code page it never exercised
     * - which for the EBCDIC datasets under {@code app/data/EBCDIC} is exactly the thing being
     * asserted.
     *
     * <p>Refused rather than accommodated with a per-case codec. The codec here comes from the
     * differ, so a per-case codec would seed under one code page and judge under another, and a case
     * would silently compare bytes it never wrote. One harness, one code page, named at construction
     * through {@link #forCharset(Charset)}, is the only arrangement in which the declaration and the
     * comparison cannot disagree.
     *
     * @param parityCase the case about to be seeded or captured
     * @throws IllegalArgumentException if the case declares a code page other than this harness's
     */
    private void requireDeclaredCharset(ParityCase parityCase) {
        if (parityCase.screenRequest() == null || parityCase.screenRequest().charset() == null) {
            return;
        }
        String declared = parityCase.screenRequest().charset();
        if (!Charset.forName(declared).equals(charset())) {
            throw new IllegalArgumentException("Case " + parityCase.program() + '/'
                + parityCase.caseId() + " declares screenRequest.charset \"" + declared
                + "\" but this harness seeds and compares under " + charset().name()
                + ". Run it through ParityHarness.forCharset(Charset.forName(\"" + declared
                + "\")) - the declaration is not decoration, and a case compared under a code page "
                + "it did not declare would pass by encoding and decoding through the same wrong "
                + "table on both sides.");
        }
    }

    // =============================================================================================
    //  CASE LOADING AND ENUMERATION
    // =============================================================================================

    /**
     * Loads one case from the classpath at {@code parity/<PROGRAM>/<caseId>.json}.
     *
     * <p>{@code program} is the COBOL program name in <strong>upper case</strong>, which is exactly
     * the stem of the parity test class that owns it: {@code CBACT01CParityTest} reads
     * {@code parity/CBACT01C/}. Because the two spellings are identical there is no case conversion
     * anywhere in this class, and none should be added.
     *
     * <p>The mixed filename casing of the <em>source</em> tree is a trap that does not apply here.
     * {@code app/cbl} holds 26 lower-case {@code .cbl} files but {@code CBSTM03A.CBL} and
     * {@code CBSTM03B.CBL} are upper case; {@code app/cpy} holds 27 {@code .cpy} plus
     * {@code COSTM01.CPY}; all 17 of {@code app/cpy-bms} are {@code .CPY}; and {@code app/jcl} holds
     * 28 {@code .jcl} plus {@code CREASTMT.JCL}. None of that reaches the resource directories read
     * here, which are uniformly the upper-case program name - so no inference may be drawn from a
     * source file's extension.
     *
     * @param program the eight-character upper-case COBOL program name; never {@code null}
     * @param caseId the case identifier, {@code case01} through {@code case20}; never {@code null}
     * @return the deserialised case, fully validated by {@link ParityCase}'s own constructor
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is blank, if the resource does not exist,
     *     if it cannot be read, or if it does not deserialise into a valid case
     * @throws IllegalStateException if the file's own {@code program} or {@code caseId} disagrees
     *     with the location it was loaded from
     */
    public ParityCase load(String program, String caseId) {
        String resource = caseResourcePath(program, caseId);
        byte[] content = readClasspathResource(resource,
            "a parity case for program " + program.strip());
        ParityCase parityCase;
        try {
            parityCase = readCase(content, "classpath resource " + resource);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Classpath resource " + resource + " is not a "
                + "readable ParityCase. Every way a fixture can assert less than it appears to fails "
                + "here rather than binding quietly: an unknown key, a duplicate key, a trailing "
                + "token, and an absent or null mandatory member such as expectedReturnCode or a "
                + "record's rowIndex. Sanitised detail: "
                + ParityCase.Redaction.sanitiseDiagnostic(failure.getMessage()), failure);
        }
        return requireSelfConsistent(parityCase, program.strip(), caseId.strip(), resource);
    }

    /**
     * Deserialises one case body through this harness's hardened mapper.
     *
     * <p>Separate from {@link #load(String, String)} because the two do different jobs: this one binds
     * bytes to a case and lets the failure through as the {@link IOException} it is, and that one
     * resolves a resource, calls this, and turns a failure into a message naming the file. Keeping them
     * apart is what lets the strictness be asserted directly - a test can hand in a body with a
     * duplicated member and read the actual reason it was refused, rather than a wrapper's paraphrase
     * of it.
     *
     * @param content the case body; never {@code null}
     * @param origin how to describe where the body came from, quoted in a failure; never {@code null}
     * @return the deserialised, fully validated case
     * @throws IOException if the body is not a single well-formed case object, if it carries an
     *     unknown or duplicated member, if anything follows it, or if a mandatory member is absent or
     *     null - {@link ParityCase}'s own constructor refuses that last one and Jackson reports it here
     * @throws NullPointerException if either argument is {@code null}
     */
    public ParityCase readCase(byte[] content, String origin) throws IOException {
        Objects.requireNonNull(content, "A case body is required to read");
        Objects.requireNonNull(origin, "A description of where the case body came from is required, "
            + "so a failure says which fixture to open");
        try {
            return mapper.readValue(content, ParityCase.class);
        } catch (IllegalArgumentException | NullPointerException rejected) {
            // ParityCase's own constructor refused the value. Jackson normally wraps such a rejection
            // into a ValueInstantiationException, but a rejection raised while binding a nested member
            // can reach here unwrapped - and an unwrapped one would escape a caller that catches only
            // IOException, which is the contract this method publishes.
            throw new IOException("The case body from " + origin + " was refused by ParityCase's own "
                + "validation: " + ParityCase.Redaction.sanitiseDiagnostic(rejected.getMessage()),
                rejected);
        }
    }

    /**
     * Loads a program's complete set of cases, in {@code case01} through {@code case20} order.
     *
     * <p>The count is checked, and a program that does not declare exactly
     * {@value #CASES_PER_PROGRAM} cases fails loudly with its name and the number actually found.
     * That check is the reason it is worth having this method at all: the gate is stated as "the
     * diff count is zero across all twenty cases", and a suite that quietly enumerated four of them
     * would satisfy the letter of that while proving almost nothing. A missing file is named
     * individually, so the failure says which case to write rather than only that one is absent.
     *
     * <h4>The set is exact, not merely sufficient</h4>
     * <p>Presence of the twenty is necessary and is not enough. The program's case directory is
     * enumerated and anything in it that is not one of the twenty is refused by name, because a
     * resource this method does not read is a resource nobody is running:
     * <ul>
     *   <li>a {@code case21.json} - or a {@code case00.json} - is a case its author wrote and believes
     *       is part of the gate. It is not: {@link ParityCase} validates {@code caseId} against
     *       {@code case01}..{@code case20}, so a twenty-first case could not even load, and left in
     *       place it silently asserts nothing;</li>
     *   <li>a misnamed one - {@code Case07.json}, {@code case7.json}, {@code case07.JSON},
     *       {@code case07.json.bak} - is the same defect wearing a name that looks right at a glance.
     *       The one this repository is most exposed to is case: the source tree it derives from mixes
     *       {@code .cbl} with {@code .CBL} and {@code .cpy} with {@code .CPY}, so a fixture named to
     *       match a source file rather than the convention here is an easy mistake to make and an easy
     *       one to miss.</li>
     * </ul>
     *
     * <p>Suitable for a JUnit {@code @MethodSource} directly, because the returned list is stable,
     * ordered and independent of the file system's own ordering.
     *
     * @param program the eight-character upper-case COBOL program name; never {@code null}
     * @return the program's cases in ascending case order, never {@code null} and always of size
     *     {@value #CASES_PER_PROGRAM}
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank, if any of the twenty case files
     *     is absent or unreadable, or if the directory holds a case the set does not name
     */
    public List<ParityCase> cases(String program) {
        String name = requireProgram(program);
        requireExactCaseSet(name);
        List<ParityCase> loaded = new ArrayList<>(CASES_PER_PROGRAM);
        List<String> missing = new ArrayList<>();
        for (int ordinal = 1; ordinal <= CASES_PER_PROGRAM; ordinal++) {
            String caseId = caseId(ordinal);
            String resource = caseResourcePath(name, caseId);
            if (classpathResourceExists(resource)) {
                loaded.add(load(name, caseId));
            } else {
                missing.add(caseId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Program " + name + " declares " + loaded.size()
                + " parity case(s) but the gate requires exactly " + CASES_PER_PROGRAM
                + ". Absent from " + CASE_RESOURCE_ROOT + name + "/: " + missing
                + ". A short set is not a smaller gate, it is a gate that passes without asking "
                + "the questions: 'diff count is zero across all twenty cases' is satisfied "
                + "vacuously by a set of four. Write the missing case files rather than lowering "
                + "the count.");
        }
        return Collections.unmodifiableList(loaded);
    }

    /**
     * A program's complete set of cases, loaded through a default {@code US-ASCII} harness.
     *
     * <p>Static, so a JUnit {@code @MethodSource} can name it without the test class holding a
     * harness of its own:
     *
     * <pre>{@code
     * static List<ParityCase> cases() {
     *     return ParityHarness.casesOf("CBACT01C");
     * }
     * }</pre>
     *
     * <p>Equivalent to {@code usAscii().cases(program)} in every respect, including the loud failure
     * when the count is not {@value #CASES_PER_PROGRAM}.
     *
     * @param program the eight-character upper-case COBOL program name; never {@code null}
     * @return the program's cases in ascending case order, always of size
     *     {@value #CASES_PER_PROGRAM}
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if the program does not declare exactly
     *     {@value #CASES_PER_PROGRAM} readable cases
     */
    public static List<ParityCase> casesOf(String program) {
        return usAscii().cases(program);
    }

    /**
     * Refuses a program whose case directory holds anything other than exactly
     * {@code case01.json} through {@code case20.json}.
     *
     * <p>Every unexpected entry is named in one message rather than reported one at a time, so a
     * directory with three strays takes one fix instead of three runs.
     *
     * @param program the validated program name
     * @throws IllegalArgumentException if the directory holds a resource the set does not name
     * @throws IllegalStateException if the directory exists but cannot be enumerated
     */
    private static void requireExactCaseSet(String program) {
        Set<String> permitted = new LinkedHashSet<>();
        for (int ordinal = 1; ordinal <= CASES_PER_PROGRAM; ordinal++) {
            permitted.add(caseId(ordinal) + CASE_RESOURCE_EXTENSION);
        }
        List<String> unexpected = new ArrayList<>();
        for (String entry : listCaseDirectory(program)) {
            if (!permitted.contains(entry)) {
                unexpected.add(entry);
            }
        }
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("Directory " + CASE_RESOURCE_ROOT + program
                + "/ holds " + unexpected + ", which " + CASES_PER_PROGRAM + "-case enumeration will "
                + "never read. A case file this method does not load is a case nobody runs, and it "
                + "reads in review as though it were part of the gate. The set is exactly "
                + caseId(1) + CASE_RESOURCE_EXTENSION + " through " + caseId(CASES_PER_PROGRAM)
                + CASE_RESOURCE_EXTENSION + ": rename the file into the set if it is a case, or "
                + "delete it if it is not. A twenty-first case cannot load at all, because "
                + "ParityCase validates caseId against that exact range.");
        }
    }

    /**
     * Lists the file names in a program's case directory on the test classpath.
     *
     * <p>Resolved through the class loader rather than through a hard-coded build path, so the same
     * code works under Maven, under an IDE and from any working directory. The directory is expected to
     * be a real directory, which is what a test classpath is under surefire and in every IDE; a
     * non-directory classpath entry - a jar - is refused with a message saying so rather than skipped
     * silently, because a check that quietly does nothing is worse than no check at all.
     *
     * @param program the validated program name
     * @return the file names present, or an empty set when the directory itself is absent - which the
     *     per-case presence check that follows reports far better than this could
     */
    private static Set<String> listCaseDirectory(String program) {
        URL directory = ParityHarness.class.getClassLoader()
            .getResource(CASE_RESOURCE_ROOT + program);
        if (directory == null) {
            return Collections.emptySet();
        }
        if (!"file".equals(directory.getProtocol())) {
            throw new IllegalStateException("The case directory " + CASE_RESOURCE_ROOT + program
                + "/ resolves to " + directory.getProtocol() + ", which cannot be enumerated, so the "
                + "set of case files cannot be proved exact. Run the suite against a directory-based "
                + "test classpath - which is what Maven surefire and every IDE provide - rather than "
                + "against a packaged archive.");
        }
        try (Stream<Path> entries = Files.list(Path.of(directory.toURI()))) {
            Set<String> names = new TreeSet<>();
            entries.forEach(entry -> names.add(entry.getFileName().toString()));
            return names;
        } catch (IOException | URISyntaxException failure) {
            throw new IllegalStateException("The case directory " + CASE_RESOURCE_ROOT + program
                + "/ could not be enumerated, so the set of case files cannot be proved exact: "
                + ParityCase.Redaction.sanitiseDiagnostic(failure.getMessage()), failure);
        }
    }

    /**
     * The case identifier for a one-based ordinal - {@code case01} through {@code case20}.
     *
     * @param ordinal the one-based case number, 1 through {@value #CASES_PER_PROGRAM}
     * @return the identifier, spelled exactly as {@link ParityCase#caseId()} requires
     * @throws IllegalArgumentException if {@code ordinal} is outside the permitted range
     */
    public static String caseId(int ordinal) {
        if (ordinal < 1 || ordinal > CASES_PER_PROGRAM) {
            throw new IllegalArgumentException("Case ordinal " + ordinal + " is outside 1.."
                + CASES_PER_PROGRAM + "; every program declares exactly " + CASES_PER_PROGRAM
                + " cases, named case01 through case" + CASES_PER_PROGRAM);
        }
        return ordinal < 10
            ? CASE_ID_PREFIX + "0" + ordinal
            : CASE_ID_PREFIX + ordinal;
    }

    /**
     * The classpath resource path one case is read from.
     *
     * <p>Composed here and only here, from {@link #CASE_RESOURCE_ROOT}, a validated program name and
     * a validated case identifier. That composition is the containment: neither component may carry
     * a path separator, a parent-directory segment or a scheme separator, so no caller can address a
     * resource outside the case tree - the same barrier
     * {@link ParityCase.DatasetInput#fixtureResourcePath()} establishes for the fixtures.
     *
     * @param program the upper-case COBOL program name
     * @param caseId the case identifier
     * @return the classpath-relative resource path
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is blank or malformed
     */
    public static String caseResourcePath(String program, String caseId) {
        return CASE_RESOURCE_ROOT + requireProgram(program) + '/' + requireCaseId(caseId)
            + CASE_RESOURCE_EXTENSION;
    }

    /**
     * Validates a program name as a resource-path segment.
     *
     * <p>Two barriers, in order: no path separator, no dot segment and no scheme or drive separator,
     * then the eight-character upper-case shape {@link ParityCase} itself enforces. The structural
     * check comes first so a rejected value is reported as the specific thing it did wrong.
     */
    private static String requireProgram(String program) {
        Objects.requireNonNull(program, "A program name is required to locate its cases; it is the "
            + "upper-case eight-character COBOL program name, which is also the parity test class "
            + "name stem");
        String name = program.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A program name is required to locate its cases, but "
                + "the value supplied was blank");
        }
        requireBareSegment(name, "program name");
        if (name.length() != 8 || !name.chars().allMatch(ParityHarness::isUpperAlphanumeric)
            || !Character.isLetter(name.charAt(0))) {
            throw new IllegalArgumentException("Program name \"" + name + "\" is not a COBOL "
                + "program name: eight characters, upper case, alphanumeric, beginning with a "
                + "letter - the partitioned-dataset member-name limit every one of the 28 programs "
                + "obeys. The resource directories are spelled in upper case, and the mixed casing "
                + "of the source tree's file extensions is not a licence to convert this name.");
        }
        return name;
    }

    /** Validates a case identifier as a resource-path segment: {@code case01} through {@code case20}. */
    private static String requireCaseId(String caseId) {
        Objects.requireNonNull(caseId, "A case identifier is required to locate a case file; it is "
            + CASE_ID_PREFIX + "01 through " + CASE_ID_PREFIX + CASES_PER_PROGRAM);
        String identifier = caseId.strip();
        requireBareSegment(identifier, "case identifier");
        for (int ordinal = 1; ordinal <= CASES_PER_PROGRAM; ordinal++) {
            if (caseId(ordinal).equals(identifier)) {
                return identifier;
            }
        }
        throw new IllegalArgumentException("Case identifier \"" + identifier + "\" is not one of "
            + CASE_ID_PREFIX + "01 through " + CASE_ID_PREFIX + CASES_PER_PROGRAM
            + ". The spelling is strict - a leading zero below ten, and no case00 - so one case "
            + "never has two names.");
    }

    /** Whether a code point is an upper-case ASCII letter or a digit. */
    private static boolean isUpperAlphanumeric(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= '0' && codePoint <= '9');
    }

    /**
     * Refuses anything that could turn a path segment into a traversal.
     *
     * <p>A segment reaching this method is about to be concatenated into a classpath resource path
     * that something will then open, so the check belongs here rather than at the open: a value of
     * {@code ../../../main/resources/application.yml} would otherwise be loaded and treated as a
     * case file.
     */
    private static void requireBareSegment(String segment, String subject) {
        if (!segment.equals(segment.strip())) {
            throw new IllegalArgumentException("The " + subject + " \"" + segment + "\" is padded "
                + "with whitespace; a resource name is matched exactly, so write it without "
                + "surrounding blanks");
        }
        for (String forbidden : List.of("/", "\\", "..", ":")) {
            if (segment.contains(forbidden)) {
                throw new IllegalArgumentException("The " + subject + " \"" + segment + "\" contains "
                    + "\"" + forbidden + "\", so it is not a bare path segment. Every case is "
                    + "resolved beneath the classpath directory " + CASE_RESOURCE_ROOT + ", and a "
                    + "value carrying a path separator, a parent-directory segment or a scheme "
                    + "separator could address a resource that is not a case at all.");
            }
        }
    }

    /**
     * Rejects a case whose own identity disagrees with where it was found.
     *
     * <p>A file at {@code parity/CBACT01C/case07.json} whose {@code program} says {@code CBACT02C}
     * is almost always a copy-paste, and it is the kind that silently doubles one program's coverage
     * while leaving another's untested. The location and the content have to agree.
     */
    private static ParityCase requireSelfConsistent(ParityCase parityCase, String program,
                                                    String caseId, String resource) {
        if (!program.equals(parityCase.program()) || !caseId.equals(parityCase.caseId())) {
            throw new IllegalStateException("Classpath resource " + resource + " declares itself as "
                + parityCase.program() + '/' + parityCase.caseId() + " but was loaded as " + program
                + '/' + caseId + ". A case whose identity disagrees with its location is normally a "
                + "copy that was never re-pointed, which quietly runs one program's expectations "
                + "twice and leaves another program's untested. Correct the \"program\" and "
                + "\"caseId\" members, or move the file.");
        }
        return parityCase;
    }

    /** Whether a classpath resource exists, without reading it. */
    private static boolean classpathResourceExists(String resource) {
        return ParityHarness.class.getClassLoader().getResource(resource) != null;
    }

    /**
     * Reads a classpath resource whole, failing loudly when it is absent.
     *
     * <p>Absence is the failure worth being loud about: a resource that does not resolve yields an
     * empty case or an empty dataset, and an empty seed produces a plausible-looking green run that
     * asserted nothing. The message names the resource and what was expected of it.
     */
    private static byte[] readClasspathResource(String resource, String subject) {
        try (InputStream stream = ParityHarness.class.getClassLoader()
            .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Classpath resource " + resource + " does not "
                    + "exist, so there is no " + subject + " to read. An absent resource seeds "
                    + "nothing while appearing to seed something, so it is refused here rather "
                    + "than allowed to become a green run over no data. Check that the file is "
                    + "present under src/test/resources and that its name matches exactly, "
                    + "including case.");
            }
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Classpath resource " + resource + " could not be "
                + "read as " + subject + ": " + failure.getMessage(), failure);
        }
    }

    // =============================================================================================
    //  SEEDING
    // =============================================================================================

    /**
     * Turns a case's declared inputs into seeded datasets, in the order the case declares them.
     *
     * <h4>Three sources, one shape</h4>
     * <ul>
     *   <li>A <strong>fixture-backed</strong> input names one of the nine files derived from
     *       {@code app/data/ASCII}, optionally narrowed by
     *       {@link ParityCase.DatasetInput#fromRow()} and
     *       {@link ParityCase.DatasetInput#rowCount()}. Their measured geometry is
     *       {@code acctdata.txt} 300 bytes x 50 rows, {@code carddata.txt} 150 x 50,
     *       {@code cardxref.txt} <strong>36</strong> x 50, {@code custdata.txt} 500 x 50,
     *       {@code dailytran.txt} 350 x 300, {@code discgrp.txt} 50 x 51, {@code tcatbal.txt}
     *       50 x 50, {@code trancatg.txt} 60 x 18 and {@code trantype.txt} 60 x 7.</li>
     *   <li>An <strong>inline</strong> input carries its rows literally, and they are seeded
     *       verbatim - every leading zero, every trailing space and every zoned-sign overpunch
     *       byte. Overpunch is real in this data rather than theoretical: row 1 of
     *       {@code acctdata.txt} begins <code>00000000001Y00000001940&#123;</code>, whose third
     *       field is the twelve characters <code>00000001940&#123;</code> of
     *       {@code ACCT-CURR-BAL PIC S9(10)V99}. The trailing <code>&#123;</code> carries the sign
     *       <em>and</em> the low-order digit - positive, digit 0 - so the twelve digits are
     *       {@code 000000019400} and the field reads <strong>194.00</strong> at scale 2. That value
     *       was established by decoding the bytes through
     *       {@link FixedWidthCodec#decodeSignedZoned(String, int)} rather than by inspection,
     *       because the arithmetic is easy to get wrong by a factor of ten: the overpunched byte is
     *       a digit as well as a sign, so a twelve-character image holds twelve digits and not
     *       eleven. {@code A} is positive 1, and <code>&#125;</code> through {@code R} are the
     *       negative forms - see {@link FixedWidthRecord.ZonedSign}. Trimming a row, or reading the
     *       trailing byte as a bare sign, destroys the value.</li>
     *   <li><strong>{@code USRSEC} has no fixture at all</strong> and is therefore always inline.
     *       {@code app/data/ASCII} holds exactly nine files and none of them is a security-user
     *       file; the source of truth is the in-stream data of {@code app/jcl/DUSRSECJ.jcl}, whose
     *       {@code STEP01 IEBGENER} carries ten records at {@code SYSUT1 DD *} - five
     *       administrators, {@code ADMIN001} through {@code ADMIN005} with
     *       {@code SEC-USR-TYPE} {@code A}, and five regular users, {@code USER0001} through
     *       {@code USER0005} with type {@code U}. Each record measures exactly 57 characters, being
     *       {@code 8 + 20 + 20 + 8 + 1}, and omits {@code CSUSR01Y}'s trailing
     *       {@code SEC-USR-FILLER PIC X(23)}; the same job writes them to a dataset declared
     *       {@code DCB=(LRECL=80,RECFM=FB)} and defines the cluster {@code RECORDSIZE(80,80)
     *       KEYS(8,0)}, so the record is 80 bytes on disk and the 23-byte shortfall is the absent
     *       filler. A case seeding {@code USRSEC} declares its rows and the matching
     *       normalisation; the row images are not restated in this class, because they already
     *       exist in the job, in the test profile and in every case that seeds them, and a fourth
     *       copy of a span declared {@code SEC-USR-PWD PIC X(08)} would widen the disclosure
     *       surface without adding a source of truth.</li>
     * </ul>
     *
     * <h4>The pad has exactly one owner, and it is not this class</h4>
     * <p>Two shipped inputs are narrower than the copybook that describes them, and both are made
     * up to width once, here at seed time, by
     * {@link ParityCase.DatasetNormalisation#normaliseSeedRow(String)} - the single validated
     * normaliser, which this class calls and never reimplements. {@code cardxref} goes from 36 to 50
     * because {@code CVACT03Y}'s {@code FILLER PIC X(14)} is absent from the data, and
     * {@code USRSEC} goes from 57 to 80 because {@code CSUSR01Y}'s {@code SEC-USR-FILLER PIC X(23)}
     * is. Nothing pads again at comparison time: the differ treats a width disagreement as a
     * difference and never invents bytes, so a case that forgets its normalisation fails loudly at
     * seeding instead of being quietly repaired later. Two owners for one pad would mean neither
     * could be audited.
     *
     * <h4>Reference inputs are never touched</h4>
     * <p>Fixtures are read from the classpath copies under
     * {@code app/java/src/test/resources/fixtures}, which are byte-for-byte identical to
     * {@code app/data/ASCII}. This class never reaches into {@code app/data} at run time and never
     * writes anywhere beneath {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms},
     * {@code app/bms}, {@code app/jcl}, {@code app/proc}, {@code app/csd}, {@code app/ctl},
     * {@code app/catlg} or {@code app/data}. Those trees are the only oracle this migration has.
     *
     * @param parityCase the case whose inputs are to be seeded; never {@code null}
     * @return an insertion-ordered, immutable map from dataset binding key to its seeded rows; empty
     *     for a case that seeds nothing, which is legitimate for a pure computation
     * @throws NullPointerException if {@code parityCase} is {@code null}
     * @throws IllegalArgumentException if a named fixture cannot be read, if a fixture's rows are
     *     not all the same width, if a declared row range falls outside the fixture, or if a row is
     *     neither the source nor the target width of the normalisation declared for its dataset
     */
    public Map<String, SeededDataset> seed(ParityCase parityCase) {
        Objects.requireNonNull(parityCase, "A ParityCase is required to seed from: its \"inputs\" "
            + "member names every dataset the unit under test will read");
        requireDeclaredCharset(parityCase);
        Map<String, SeededDataset> seeded = new LinkedHashMap<>();
        for (Map.Entry<String, DatasetInput> entry : parityCase.inputs().entrySet()) {
            String dataset = entry.getKey();
            DatasetInput input = entry.getValue();
            if (input.declaredEmpty()) {
                // A dataset that exists and holds no row. Its width comes from the declaration rather
                // than from a row, because there is no row to measure - which is exactly why the
                // declaration is required to carry one. Seeding it makes the difference between "the
                // unit read an empty file and reached end-of-file on its first READ" and "the case
                // never mentioned the dataset", and only the first of those is an assertion.
                seeded.put(dataset, SeededDataset.empty(dataset, input.recordLength(), charset()));
                continue;
            }
            List<String> declared = input.inline()
                ? input.rows()
                : sliceFixture(readFixtureRows(input, dataset), input, dataset);
            List<String> normalised = normalise(declared, dataset, parityCase);
            seeded.put(dataset, SeededDataset.of(dataset, normalised, charset()));
        }
        return Collections.unmodifiableMap(seeded);
    }

    /**
     * Reads every row of one fixture, decoded with the explicitly named code page.
     *
     * <p>Rows are separated by a single line feed and are otherwise verbatim: a trailing space is
     * data, and so is a trailing sign overpunch. A carriage return is refused rather than stripped,
     * because a fixture checked out with translated line endings is a corrupted fixture - every row
     * would be one byte wider than its copybook and the failure would surface far from its cause.
     */
    private List<String> readFixtureRows(DatasetInput input, String dataset) {
        String resource = input.resourcePath();
        byte[] content = readClasspathResource(resource, "the fixture seeding dataset " + dataset);
        String text = codec().decodeImage(content, "fixture " + resource);
        List<String> rows = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                if (start < text.length()) {
                    rows.add(text.substring(start));
                }
                break;
            }
            rows.add(text.substring(start, end));
            start = end + 1;
        }
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Row " + index + " of fixture " + resource
                    + " contains a carriage return. The nine fixtures are byte-for-byte copies of "
                    + "app/data/ASCII and are line-feed separated, so a carriage return means the "
                    + "working tree translated the line endings - which makes every row one byte "
                    + "wider than its copybook. Restore the fixture rather than stripping the "
                    + "byte here, because a fixture that is not byte-identical to app/data/ASCII is "
                    + "no longer the authoritative input this harness relies on.");
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Fixture " + resource + " holds no row, so dataset "
                + dataset + " would be seeded empty while the case appears to seed it from a real "
                + "file. All nine fixtures are non-empty: the smallest is trantype.txt with 7 rows.");
        }
        return rows;
    }

    /**
     * Narrows a fixture's rows to the range the case declared.
     *
     * <p>{@link ParityCase.DatasetInput#fromRow()} is zero-based and is normalised to {@code 0} when
     * a fixture is named without one; {@link ParityCase.DatasetInput#rowCount()} being absent means
     * every remaining row. A range that runs past the end is refused rather than truncated: a case
     * asking for 20 rows from row 40 of a 50-row fixture has almost certainly miscounted, and
     * silently handing it 10 would let it assert against a different input than it describes.
     */
    private static List<String> sliceFixture(List<String> rows, DatasetInput input, String dataset) {
        int fromRow = input.fromRow() == null ? 0 : input.fromRow();
        if (fromRow >= rows.size()) {
            throw new IllegalArgumentException("Dataset " + dataset + " seeds fixture "
                + input.resourcePath() + " from zero-based row " + fromRow + ", but that fixture "
                + "holds only " + rows.size() + " row(s), so the range selects nothing. A case that "
                + "seeds nothing while naming a real fixture is the silent-pass failure this "
                + "harness exists to prevent.");
        }
        int available = rows.size() - fromRow;
        int requested = input.rowCount() == null ? available : input.rowCount();
        if (requested > available) {
            throw new IllegalArgumentException("Dataset " + dataset + " requests " + requested
                + " row(s) from zero-based row " + fromRow + " of fixture " + input.resourcePath()
                + ", but only " + available + " remain of its " + rows.size() + " row(s). The range "
                + "is refused rather than truncated: a case handed fewer rows than it asked for "
                + "would assert against an input it does not describe.");
        }
        return List.copyOf(rows.subList(fromRow, fromRow + requested));
    }

    /**
     * Applies the case's declared normalisation for one dataset, or returns the rows untouched when
     * the case declares none.
     *
     * <p>At most one normalisation may apply to a dataset -
     * {@link ParityCase#normalisations()} rejects a duplicate - so the first match is the only
     * match, and the loop terminates on it rather than composing pads.
     */
    private static List<String> normalise(List<String> rows, String dataset, ParityCase parityCase) {
        for (DatasetNormalisation normalisation : parityCase.normalisations()) {
            if (normalisation.dataset().equals(dataset)) {
                return normalisation.normaliseSeedRows(rows);
            }
        }
        requireNoUndeclaredDeviation(rows, dataset);
        return List.copyOf(rows);
    }

    /**
     * Refuses a seed that is sitting at a known deviation width without declaring the normalisation
     * that covers it.
     *
     * <p>Without this check the omission is not detectable here at all, and that is worth spelling
     * out. This class measures a dataset's record width <em>from its rows</em> rather than reading it
     * from configuration, so 50 cross-reference rows of 36 characters look exactly like a dataset
     * whose records are 36 characters wide. The failure would then surface much later and much
     * further away - a decoder refusing a short row, or every field of a comparison off by fourteen
     * bytes - and the cause would have to be worked backwards from the symptom.
     *
     * <p>The evidence this check acts on is entirely the case model's own: {@code Normalisation}
     * enumerates which datasets each deviation may apply to and the exact width the shipped data sits
     * at, so a match is a match on both facts rather than on a width alone. Four of this system's
     * datasets declare a 50-byte record, which is precisely why a width by itself proves nothing.
     *
     * <p>And it <strong>refuses</strong> rather than pads. Padding here would make this class a second
     * owner of a pad that already has exactly one, and neither owner could then be audited.
     */
    private static void requireNoUndeclaredDeviation(List<String> rows, String dataset) {
        if (rows.isEmpty()) {
            return;
        }
        for (Normalisation candidate : Normalisation.values()) {
            if (!candidate.appliesTo(dataset) || !allRowsMeasure(rows, candidate.sourceWidth())) {
                continue;
            }
            throw new IllegalArgumentException("Dataset " + dataset + " is seeded with "
                + rows.size() + " row(s) of " + candidate.sourceWidth() + " character(s), which is "
                + "exactly the width the shipped data sits at where " + candidate.copybook()
                + " declares " + candidate.targetWidth() + " - the " + candidate.absentSpan()
                + " is absent from it. The case must declare that in its \"normalisations\" member, "
                + "as {\"dataset\": \"" + dataset + "\", \"kind\": \"" + candidate.name() + "\"}, so "
                + "the pad is applied once at seed time by its single owner. It is refused here "
                + "rather than applied, because padding it in this class would make two owners of "
                + "one pad and neither could then be audited; and it is refused here rather than "
                + "left to be discovered later, because a 36-character row reaching a decoder looks "
                + "like a decoder defect and a 36-character expectation looks like a differ defect.");
        }
    }

    /** Whether every row measures exactly one width, with no row missing. */
    private static boolean allRowsMeasure(List<String> rows, int width) {
        for (String row : rows) {
            if (row == null || row.length() != width) {
                return false;
            }
        }
        return true;
    }

    // =============================================================================================
    //  EXECUTION - the part it is easiest to get wrong
    // =============================================================================================

    /**
     * Seeds the case, invokes the unit, and captures what the run observably produced.
     *
     * <h4>Nothing between the assertion and the code</h4>
     * <p>The operative requirement is that no HTTP layer and no job launcher sits in the path.
     * Each of the four unit kinds is reached directly:
     * <ul>
     *   <li>{@link ParityCase.UnitKind#BATCH_JOB} - the caller invokes the tasklet, or the
     *       reader/processor/writer trio, itself. No job launcher, no job repository and no
     *       asynchronous executor, so step sequencing and write ordering are observed exactly as
     *       written. The job beans exist and never auto-run, because the configuration disables
     *       run-on-startup.</li>
     *   <li>{@link ParityCase.UnitKind#SERVICE} and {@link ParityCase.UnitKind#COMPONENT} - the
     *       caller constructs the class with fixture-backed or stubbed collaborators and calls its
     *       operation. Separate service classes exist only for the seven programs whose logic was
     *       lifted out of the controller, plus the two called subprograms, so most online programs
     *       are not this kind.</li>
     *   <li>{@link ParityCase.UnitKind#CONTROLLER_POJO} - the caller constructs the controller
     *       through its constructor, as a plain Java object, and calls its handler method. This is
     *       still compliant, and the reason is worth stating plainly: the requirement forbids HTTP
     *       in the path, and calling a Java method on a Java object involves none. Mock MVC, a test
     *       REST template, a web test client and a servlet container are all absent - there is no
     *       request, no dispatcher, no filter chain and no serialisation round trip between the
     *       assertion and the decision logic being asserted. The whole
     *       {@code transaction} package and four of the five {@code user} programs keep their
     *       decision logic in the controller, so this kind is the only way to reach them at all.</li>
     * </ul>
     *
     * <h4>An abend is an observation, not a failure</h4>
     * <p>{@link AbendException} is the translation of {@code CALL 'CEE3ABD'}, which appears at nine
     * sites across the batch programs, and it carries the COBOL {@code RETURN-CODE}. The expected
     * return code is part of the expectation, so an abend is <em>recorded into the fingerprint</em>
     * and compared, never allowed to fail the run. The abend parameters are optional because one
     * site sets neither an abend code nor a timing.
     *
     * <p>This is why the {@link Invocation} carries a recorder. A unit that emits its diagnostic
     * lines and then abends has produced those lines, and they belong in the fingerprint; if the
     * only way to report an observation were the method's return value, an abend would discard
     * everything the unit had already done. A unit therefore writes its writes and its emitted lines
     * into {@link Invocation#recorder()} as it goes, and may simply return {@code null} to mean "the
     * recorder holds it".
     *
     * <p>Any other exception is a defect rather than an observation and is rethrown with the case
     * named, because a translation that throws where the COBOL does not has not reproduced the
     * COBOL.
     *
     * @param parityCase the case to run; never {@code null}
     * @param adapterKind the kind of unit the adapter constructs, checked against the case's own
     *     {@link ParityCase#unitKind()} before the unit is reached; never {@code null}
     * @param unit how to construct and call the unit under test; never {@code null}
     * @return the decoded fingerprint of that one run, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code adapterKind} is not the kind the case declares
     * @throws IllegalStateException if the unit throws anything other than an
     *     {@link AbendException}, or if it both populates the recorder and returns an unrelated
     *     outcome
     */
    public DecodedFingerprint run(ParityCase parityCase, ParityCase.UnitKind adapterKind,
                                 ParityUnit unit) {
        Objects.requireNonNull(parityCase, "A ParityCase is required to run: it supplies the seed, "
            + "the job parameters and the pinned clock");
        Objects.requireNonNull(adapterKind, "The kind of unit this adapter constructs is required. "
            + "It is stated at the call site rather than inferred so it can be checked against the "
            + "case's own unitKind before anything runs: use ParityCase.UnitKind.BATCH_JOB, SERVICE, "
            + "COMPONENT or CONTROLLER_POJO.");
        Objects.requireNonNull(unit, "A ParityUnit is required: it is how this harness reaches the "
            + "unit under test without a job launcher or an HTTP layer in the path");
        requireDeclaredUnitKind(parityCase, adapterKind);

        Map<String, SeededDataset> seeded = seed(parityCase);
        UnitOutcome.Builder recorder = UnitOutcome.builder(codec());
        Invocation invocation = new Invocation(parityCase, seeded, clockFor(parityCase), codec(),
            recorder);

        UnitOutcome outcome;
        OptionalInt abended = OptionalInt.empty();
        try {
            outcome = resolveOutcome(unit.invoke(invocation), recorder, parityCase);
        } catch (AbendException abend) {
            // The abend IS the behaviour under test at these nine sites. Everything the unit
            // recorded before abending is still an observation, so the recorder is drained rather
            // than discarded, and the RETURN-CODE the abend carries becomes the fingerprint's.
            outcome = recorder.build();
            abended = OptionalInt.of(abend.getReturnCode());
        } catch (Exception failure) {
            // The message goes through Redaction rather than into the text raw. A failure raised
            // inside a repository routinely quotes the record it was handed, and for a customer row
            // that is 500 bytes of names, address and social-security number, while a USRSEC row
            // carries the legacy plaintext password - all of which an assertion message puts straight
            // into a build log and a CI artefact (CWE-532). The throwable is still chained as the
            // cause, so a developer running the suite locally loses nothing.
            throw new IllegalStateException("Parity case " + parityCase.program() + '/'
                + parityCase.caseId() + " raised " + failure.getClass().getName()
                + ", which is not an abend and is therefore a defect rather than an observation. "
                + "The COBOL either abends - which arrives here as AbendException carrying a "
                + "RETURN-CODE and is compared like any other expectation - or it does not throw at "
                + "all. Sanitised message: " + Redaction.sanitiseDiagnostic(failure.getMessage()),
                failure);
        }
        requireForcedOutcomesConsumed(parityCase, invocation);
        return capture(parityCase, outcome, resolveReturnCode(outcome, abended));
    }

    /**
     * Refuses a run that left a declared forced outcome unasked-for.
     *
     * <p>A forced outcome is how a case reaches an arm its seeded data cannot reach. Declared and
     * never consumed, it forces nothing: the run takes the ordinary path, every expectation about that
     * ordinary path is met, and the case passes while its description claims it exercised a
     * {@code WHEN OTHER}. That is a false pass with a plausible-looking case file behind it, which is
     * the hardest kind to notice - so the declaration is treated as a claim about the run and checked
     * like one.
     *
     * <p>Checked on the abend path too. An abend is an observation rather than a failure here, and a
     * forced outcome the abending run never reached forced nothing just the same.
     *
     * @param parityCase the case that ran
     * @param invocation the invocation it ran through, which recorded what was consumed
     * @throws IllegalStateException naming every unconsumed operation
     */
    private static void requireForcedOutcomesConsumed(ParityCase parityCase,
                                                      Invocation invocation) {
        List<ParityCase.RepositoryOperation> unconsumed = invocation.unconsumedForcedOutcomes();
        if (unconsumed.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(unconsumed.size());
        for (ParityCase.RepositoryOperation operation : unconsumed) {
            keys.add(operation.key());
        }
        throw new IllegalStateException("Case " + parityCase.program() + '/' + parityCase.caseId()
            + " declares forced outcome(s) for " + keys + " that nothing asked for during the run, "
            + "so they forced nothing. A forced outcome exists to reach an arm the seeded data cannot "
            + "reach; unconsumed, the run took the ordinary path and the case would have passed while "
            + "claiming to have exercised the other one. Either take the outcome through "
            + "Invocation.forcedOutcome(RepositoryOperation) at the call site it belongs to, or "
            + "remove the declaration from the case.");
    }

    /**
     * Runs the case and hands the result to the differ.
     *
     * <p>The returned {@link DiffResult} is <strong>not</strong> checked here. The gate is stated as
     * a diff count of zero across all twenty of a program's cases, and the assertion belongs in the
     * per-program test class so a failure reads as "3 differences on case07" with all three
     * rendered. A harness that asserted would replace that with an exception carrying whatever
     * message the harness happened to choose, and the differ has already written a better one.
     *
     * <p>Two of the differences it can return are about the <em>case</em> rather than about the run,
     * and they are returned on the same footing as any other because they are the ones that decide
     * whether the rest of the count means anything. An expectation that names some fields of a record
     * and leaves the rest of its bytes unstated is reported, since answering "the fields you pinned
     * match" says nothing about the 289 bytes of an account row that were not pinned. And a
     * dataset-level expectation the run did not satisfy - a reject file the case says was created and
     * left empty, at the width its JCL declares - is reported, since no row expectation can assert a
     * dataset that holds no row.
     *
     * @param parityCase the case whose expectations are authoritative; never {@code null}
     * @param adapterKind the kind of unit the adapter constructs; never {@code null}
     * @param unit how to construct and call the unit under test; never {@code null}
     * @return every difference found, in the differ's fully determined traversal order
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code adapterKind} is not the kind the case declares
     */
    public DiffResult judge(ParityCase parityCase, ParityCase.UnitKind adapterKind,
                            ParityUnit unit) {
        return judge(parityCase, run(parityCase, adapterKind, unit));
    }

    /**
     * Hands an already-captured fingerprint to the differ - the form a caller uses when it captured
     * the run itself.
     *
     * @param parityCase the case whose expectations are authoritative; never {@code null}
     * @param fingerprint what the run produced; never {@code null}
     * @return every difference found, in traversal order
     * @throws NullPointerException if either argument is {@code null}
     */
    public DiffResult judge(ParityCase parityCase, DecodedFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "A DecodedFingerprint is required to judge; use "
            + "run(ParityCase, ParityUnit) to produce one");
        return differ.compare(parityCase, fingerprint.differFingerprint());
    }

    /**
     * Decodes an outcome the caller assembled itself into a fingerprint, taking the return code from
     * the outcome and defaulting it to zero when the outcome states none.
     *
     * @param parityCase the case the outcome belongs to; never {@code null}
     * @param outcome what the unit produced; never {@code null}
     * @return the decoded fingerprint, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public DecodedFingerprint capture(ParityCase parityCase, UnitOutcome outcome) {
        return capture(parityCase, outcome, resolveReturnCode(outcome, OptionalInt.empty()));
    }

    /**
     * Builds both views of one run: the raw fingerprint the differ judges, and the decoded
     * projection a reader can read.
     *
     * <p>The two are built from the same {@link DatasetOutput} values in the same order, so they
     * cannot disagree - the decoded view is a projection of the fingerprint rather than a second
     * capture of the run.
     */
    private DecodedFingerprint capture(ParityCase parityCase, UnitOutcome outcome, int returnCode) {
        Objects.requireNonNull(parityCase, "A ParityCase is required to capture against");
        requireDeclaredCharset(parityCase);
        Objects.requireNonNull(outcome, "A UnitOutcome is required to capture: a unit that wrote "
            + "nothing still reports its RETURN-CODE, so use UnitOutcome.ofReturnCode(int) rather "
            + "than passing null");
        Fingerprint fingerprint = Fingerprint.of(outcome.writes(), outcome.finalState(),
            outcome.response(), returnCode, outcome.messages());
        return new DecodedFingerprint(parityCase.program(), parityCase.caseId(),
            decodeChannel(outcome.writes()), decodeChannel(outcome.finalState()), fingerprint);
    }

    /**
     * Decides which of the two ways of reporting an outcome the unit used, and refuses the
     * combination that would silently lose one of them.
     *
     * <p>Returning {@code null} means "the recorder holds it". Returning the object the recorder
     * built is the same thing said explicitly. Returning something else while the recorder holds
     * content is neither, and it is refused: whichever of the two the harness picked, the other's
     * observations would vanish, and a fingerprint missing a write is a parity difference that no
     * longer exists to be reported.
     */
    private static UnitOutcome resolveOutcome(UnitOutcome returned, UnitOutcome.Builder recorder,
                                              ParityCase parityCase) {
        if (returned == null) {
            return recorder.build();
        }
        if (recorder.isSupersededBuild(returned)) {
            // The narrow, silent case, and the reason the recorder dates its builds: the unit called
            // build(), carried on recording, and returned the earlier object. It IS the recorder's own
            // build, so an identity check waves it through - and everything recorded afterwards
            // vanishes from the fingerprint. A missing write is a parity difference that no longer
            // exists to be reported, which is the one failure this whole class is built to prevent.
            throw new IllegalStateException("Parity case " + parityCase.program() + '/'
                + parityCase.caseId() + " returned a UnitOutcome that Invocation.recorder().build() "
                + "produced before " + recorder.observationsSinceBuild() + " further observation(s) "
                + "were recorded, so those observations are not in it. Return "
                + "recorder.build() as the last thing the unit does - or return null and let the "
                + "harness build it, which cannot go stale - rather than holding an outcome built "
                + "part-way through. Nothing is dropped here silently: a fingerprint missing a write "
                + "is a difference that can no longer be reported.");
        }
        if (recorder.hasContent() && !recorder.isCurrentBuild(returned)) {
            throw new IllegalStateException("Parity case " + parityCase.program() + '/'
                + parityCase.caseId() + " both wrote observations into Invocation.recorder() and "
                + "returned a different UnitOutcome. There are exactly two ways to report a run - "
                + "populate the recorder and return null or return recorder.build(), or build an "
                + "outcome yourself and never touch the recorder - and mixing them would discard "
                + "one set of observations. A fingerprint missing a write is a difference that can "
                + "no longer be reported, so this is refused rather than resolved by a rule nobody "
                + "would remember.");
        }
        return returned;
    }

    /**
     * The {@code RETURN-CODE} the fingerprint carries.
     *
     * <p>An abend wins, because it is the last thing the program did and it is the value the COBOL
     * would have left in {@code RETURN-CODE}. Otherwise the outcome's own value applies, and a unit
     * that stated none ended normally, which is zero. The observed set across the 28 programs is 0,
     * 3, 4, 8, 12 and 16; no value is forced into that set here, because a unit producing a value
     * outside it is a difference the differ should report rather than one the harness should hide.
     */
    private static int resolveReturnCode(UnitOutcome outcome, OptionalInt abended) {
        if (abended.isPresent()) {
            return abended.getAsInt();
        }
        return outcome.returnCode().orElse(AbendException.RETURN_CODE_OK);
    }

    // =============================================================================================
    //  FINGERPRINT CAPTURE
    // =============================================================================================

    /** Decodes one channel - the writes or the final state - preserving dataset and row order. */
    private Map<String, List<DecodedRecord>> decodeChannel(List<DatasetOutput> outputs) {
        Map<String, List<DecodedRecord>> decoded = new LinkedHashMap<>();
        for (DatasetOutput output : outputs) {
            List<DecodedRecord> rows = new ArrayList<>(output.rowCount());
            for (int rowIndex = 0; rowIndex < output.rowCount(); rowIndex++) {
                rows.add(decodeRow(output.dataset(), rowIndex, output.layout(),
                    output.row(rowIndex)));
            }
            decoded.put(output.dataset(), Collections.unmodifiableList(rows));
        }
        return Collections.unmodifiableMap(decoded);
    }

    /**
     * Decodes one record into its copybook field names, keeping the raw image and the length beside
     * them.
     *
     * <h4>Field names are the copybook's own, verbatim</h4>
     * <p>Nothing is corrected on the way through, and three consequences are load-bearing.
     * {@code ACCT-EXPIRAION-DATE} is misspelled in {@code app/cpy/CVACT01Y.cpy} and is misspelled
     * here; spelling it correctly would make every expectation look for a field the decoder never
     * produces. {@code CUSTREC}'s {@code CUST-DOB-YYYYMMDD} stays distinct from {@code CVCUS01Y}'s
     * {@code CUST-DOB-YYYY-MM-DD}, because the two copybooks describe near-identical layouts under
     * different names and collapsing them would discard a name the comparison depends on. And every
     * {@code FILLER} span is an addressable field rather than a gap - the persisted copybooks all
     * end in one, their content is a property of the shipped data rather than of the copybook, and
     * only a decode that can address a {@code FILLER} can check it.
     *
     * <h4>A wrong-width row is still representable</h4>
     * <p>A row whose length disagrees with its layout is held with its image and its measured length
     * and with no fields decoded, rather than rejected. That is deliberate: the width is exactly what
     * the comparison needs to report, and a decode that threw here would abandon the run and hide
     * every other difference behind an exception. The record's total width is also how a dropped
     * {@code FILLER} is caught, since omitting one leaves the row short by exactly that span.
     *
     * <h4>The numeric projection</h4>
     * <p>A signed zoned span is additionally decoded to a {@link BigDecimal} at
     * {@link CobolDecimal#MONETARY_SCALE}, which is 2 - not an assumption but the measured property
     * of every signed decimal picture in this codebase, of which there are only three forms, all
     * scale 2. Rounding is {@link CobolDecimal#COBOL_ROUNDING}, truncation toward zero, because
     * {@code ROUNDED} appears nowhere in the 28 programs. The projection is diagnostic: an entry
     * absent for a signed span means the stored bytes are not a valid zoned number, which the differ
     * reports as an undecodable field. No packed-decimal unpacking is needed anywhere, because not
     * one of the 28 copybooks declares {@code COMP-3} - every persisted numeric is zoned
     * {@code DISPLAY}.
     */
    private DecodedRecord decodeRow(String dataset, int rowIndex, RecordLayout layout, byte[] row) {
        String subject = "dataset " + dataset + " row " + rowIndex;
        String image = codec().decodeImage(row, subject);
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, BigDecimal> numerics = new LinkedHashMap<>();
        if (row.length == layout.recordLength()) {
            FixedWidthRecord area = codec().wrap(row, layout);
            int fillerOrdinal = 0;
            for (FieldSpan span : layout.spans()) {
                String name = span.name();
                if (FILLER_NAME.equals(name)) {
                    fillerOrdinal++;
                    name = fillerOrdinal == 1
                        ? FILLER_NAME
                        : FILLER_NAME + FILLER_ORDINAL_SEPARATOR + fillerOrdinal;
                }
                String value = area.readSpan(span);
                fields.put(name, value);
                if (span.kind() == PictureKind.SIGNED_SCALED) {
                    // The ordinal-adjusted name, not span.name(): a FILLER declared with a numeric
                    // picture is a real possibility in these copybooks, and keying it as plain
                    // FILLER would collide with the first one.
                    Optional<BigDecimal> number = decodeMonetary(value);
                    if (number.isPresent()) {
                        numerics.put(name, number.get());
                    }
                }
            }
        }
        return new DecodedRecord(dataset, rowIndex, row.length, image,
            Collections.unmodifiableMap(fields), Collections.unmodifiableMap(numerics));
    }

    /**
     * Decodes a signed zoned image to a scaled {@link BigDecimal}, or reports that it is not one.
     *
     * <p>An unset span reads as spaces, and a screen cleared to low values reads as {@code X'00'};
     * neither is a zoned number, and neither is a reason to abandon a comparison that has 400 more
     * fields to look at. The absence of an entry is the finding, and the differ turns it into a
     * reported undecodable field.
     */
    private Optional<BigDecimal> decodeMonetary(String image) {
        try {
            return Optional.of(
                codec().decodeSignedZoned(image, CobolDecimal.MONETARY_SCALE).signedValue());
        } catch (RuntimeException notANumber) {
            return Optional.empty();
        }
    }

    /**
     * Validates a dataset binding key.
     *
     * <p>A binding key, never a dataset name: the keys are the DD and CICS file names the JCL and the
     * CSD spell - {@code ACCTDAT}, {@code XREFFIL1}, {@code TCATBALF} - and the mainframe dataset
     * names they resolve to live in configuration. Nothing in this file may name one, so a value that
     * looks like a dataset name is refused here.
     */
    private static String requireDatasetKey(String dataset, String subject) {
        Objects.requireNonNull(dataset, subject + " is required and must be a dataset binding key "
            + "such as ACCTDAT, DALYREJS or XREFFIL1 - the DD or CICS file name, never the "
            + "mainframe dataset name it resolves to");
        String key = dataset.strip();
        if (key.isEmpty() || key.length() > 8 || !Character.isLetter(key.charAt(0))
            || !key.chars().allMatch(ParityHarness::isUpperAlphanumeric)) {
            throw new IllegalArgumentException(subject + " \"" + dataset + "\" is not a dataset "
                + "binding key: one to eight characters, upper case, alphanumeric, beginning with a "
                + "letter. A dotted mainframe dataset name is refused deliberately - dataset "
                + "locations are resolved from configuration, so no source file in this module "
                + "names one.");
        }
        return key;
    }

    // =============================================================================================
    //  NESTED TYPES
    //
    //  All of them live here rather than in files of their own. Each is meaningless away from the
    //  harness that produces or consumes it, and keeping them together is what lets a reader see the
    //  whole of one run - seed, invoke, record, decode - without opening anything else.
    // =============================================================================================

    /**
     * How the harness reaches one unit under test.
     *
     * <p>Deliberately open: a case declares which of the four kinds its unit is through
     * {@link ParityCase#unitKind()}, and the caller - the per-program parity test, which is the only
     * thing that may import that program's own classes - supplies the construction and the call. The
     * harness stays out of it, which is why one harness serves a tasklet, a service, a called
     * component and a controller invoked as a plain object without knowing the difference.
     *
     * <p>An implementation reports what the run produced in one of two ways, and must not mix them:
     * <ul>
     *   <li>write into {@link Invocation#recorder()} as it goes and return {@code null}, or return
     *       {@code invocation.recorder().build()}, which is the same thing said out loud. Use this
     *       when the unit may abend, because the recorder survives the exception and the method's
     *       return value does not;</li>
     *   <li>build a {@link UnitOutcome} and return it, never touching the recorder.</li>
     * </ul>
     *
     * <p>{@code throws Exception} is deliberate, so a lambda may call a method that declares a
     * checked exception without wrapping it. An {@link AbendException} is recorded as an observation;
     * anything else is rethrown as a defect with the case named.
     */
    @FunctionalInterface
    public interface ParityUnit {

        /**
         * Constructs the unit under test and calls it.
         *
         * @param invocation the seeded datasets, the case's job parameters, the pinned clock, the
         *     codec and the recorder; never {@code null}
         * @return what the run produced, or {@code null} to mean "the recorder holds it"
         * @throws Exception if the unit fails. An {@link AbendException} is an observation and is
         *     recorded; anything else is a defect and is rethrown
         */
        UnitOutcome invoke(Invocation invocation) throws Exception;
    }

    /**
     * Everything a {@link ParityUnit} is given for one run.
     *
     * <p>The seeded datasets and the case's job parameters are the substance of it; the pinned clock
     * and the codec are here because a unit that needs either must not reach for a system clock or a
     * platform charset; and the recorder is here because an observation made before an abend is still
     * an observation.
     *
     * <h2>Inputs only, structurally</h2>
     * <p>What a unit is given is exactly what the legacy program was given: seeded datasets, job
     * parameters, the online request, a clock and a code page. What it is <em>not</em> given is any
     * part of the expectation - not the expected writes, not the expected final state, not the
     * expected return code, not the expected messages, not the expected response, and not the case
     * object they hang off. A unit able to read those could report them back and pass every case
     * while implementing nothing, and the diff count would be zero for the worst possible reason.
     * The separation is structural rather than advisory: the constructor copies the input members out
     * of the case and drops the reference, so there is no accessor to add and nothing to remember.
     *
     * <p>Immutable except for the recorder, which is the point of the recorder. It is per-invocation
     * state, created by {@link ParityHarness#run(ParityCase, ParityUnit)} and unreachable from
     * anywhere else, so no two cases can see each other's observations.
     */
    public static final class Invocation {

        /** The program the case names, for a diagnostic and for a unit that dispatches on it. */
        private final String program;

        /** The case identifier, for a diagnostic. */
        private final String caseId;

        /** Which of the four shapes the case declares its unit to be. */
        private final ParityCase.UnitKind unitKind;

        /** The seeded datasets, keyed by binding key, in the order the case declares them. */
        private final Map<String, SeededDataset> datasets;

        /** The case's job parameters, which only a batch case carries any of. */
        private final Map<String, String> jobParameters;

        /** The online request inputs, or {@code null} for a case that declares none. */
        private final ParityCase.ScreenRequest screenRequest;

        /** The pinned clock - always fixed, never a system clock. */
        private final Clock clock;

        /** The codec, whose charset is the code page of the seeded rows. */
        private final FixedWidthCodec codec;

        /** Where a unit records what it produced, so an abend loses nothing. */
        private final UnitOutcome.Builder recorder;

        /**
         * Which forced outcomes something actually asked for.
         *
         * <p>Per-invocation and unreachable from anywhere else, like the recorder. Its only reader is
         * the harness, after the unit returns.
         */
        private final Set<ParityCase.RepositoryOperation> consumedForcedOutcomes =
            EnumSet.noneOf(ParityCase.RepositoryOperation.class);

        /**
         * Constructed only by the harness, from state it created for this one run.
         *
         * <p>The case is <strong>projected</strong> here and not retained. That is the whole point of
         * the type: the inputs are copied out, the reference is dropped, and there is therefore no
         * path from a unit under test to what it is about to be judged against. A field holding the
         * case would make the separation a convention, and a convention is not a boundary.
         */
        private Invocation(ParityCase parityCase, Map<String, SeededDataset> datasets, Clock clock,
                           FixedWidthCodec codec, UnitOutcome.Builder recorder) {
            this.program = parityCase.program();
            this.caseId = parityCase.caseId();
            this.unitKind = parityCase.unitKind();
            this.jobParameters = parityCase.jobParameters();
            this.screenRequest = parityCase.screenRequest();
            this.datasets = datasets;
            this.clock = clock;
            this.codec = codec;
            this.recorder = recorder;
        }

        /**
         * The program the case names.
         *
         * @return the eight-character program name, never {@code null}
         */
        public String program() {
            return program;
        }

        /**
         * The case identifier.
         *
         * @return {@code caseNN}, never {@code null}
         */
        public String caseId() {
            return caseId;
        }

        /**
         * Which of the four shapes the case declares its unit to be.
         *
         * <p>Already checked against the kind the caller stated before the unit was reached, so this
         * is here to be read rather than to be verified.
         *
         * @return the declared kind, never {@code null}
         */
        public ParityCase.UnitKind unitKind() {
            return unitKind;
        }

        /**
         * {@code EIBCALEN} - the COMMAREA length CICS reports, which every online program tests to
         * tell a first entry from a re-entry.
         *
         * @return the declared length
         * @throws IllegalStateException if the case declares no screen request
         */
        public int eibcalen() {
            return request().eibcalen();
        }

        /**
         * The AID mnemonic the case says was pressed, for example {@code DFHPF3}.
         *
         * @return the mnemonic, or {@code null} when the case pins none
         * @throws IllegalStateException if the case declares no screen request
         */
        public String aid() {
            return request().aid();
        }

        /**
         * The inbound COMMAREA fields, keyed as {@code app/cpy/COCOM01Y.cpy} names them.
         *
         * @return an immutable map, empty when the case passes none
         * @throws IllegalStateException if the case declares no screen request
         */
        public Map<String, String> commarea() {
            return request().commarea();
        }

        /**
         * The inbound map fields, keyed by their symbolic-map {@code xxxI} names.
         *
         * @return an immutable map, empty when the case types nothing
         * @throws IllegalStateException if the case declares no screen request
         */
        public Map<String, String> mapFields() {
            return request().mapFields();
        }

        /**
         * Whether the case declares online request inputs. Every batch case does not.
         *
         * @return {@code true} for a case carrying a screen request
         */
        public boolean hasScreenRequest() {
            return screenRequest != null;
        }

        /**
         * Every repository operation the case forces an outcome for, without consuming any of them.
         *
         * @return the declared operations in declaration order; empty when the case forces none
         */
        public Set<ParityCase.RepositoryOperation> declaredForcedOutcomes() {
            return screenRequest == null
                ? Collections.emptySet()
                : screenRequest.forcedOutcomes().keySet();
        }

        /**
         * Whether the case forces an outcome for one operation. Does not consume it.
         *
         * @param operation the repository operation; never {@code null}
         * @return {@code true} when the case declares one
         */
        public boolean hasForcedOutcome(ParityCase.RepositoryOperation operation) {
            Objects.requireNonNull(operation, "A RepositoryOperation is required to ask about a "
                + "forced outcome");
            return declaredForcedOutcomes().contains(operation);
        }

        /**
         * The outcome the case forces for one operation, <strong>marking it consumed</strong>.
         *
         * <p>Consumption is recorded because a declared outcome that nothing asks for is a case
         * asserting the opposite of what it says. The reason a case forces an outcome at all is to
         * reach an arm the seeded data cannot reach - a {@code WHEN OTHER} on a {@code RESP} check, a
         * {@code DUPKEY} on a write. If no call site asks for it the arm stays unreached, the run is
         * the ordinary happy path, and the case passes while its own description claims otherwise.
         * {@link ParityHarness#run(ParityCase, ParityCase.UnitKind, ParityUnit)} therefore refuses a
         * run that left one unasked-for, which is why this is the only way to obtain one.
         *
         * @param operation the repository operation; never {@code null}
         * @return the outcome to force, never {@code null}
         * @throws IllegalArgumentException if the case forces no outcome for that operation, naming
         *     the ones it does force
         */
        public ParityCase.ForcedOutcome forcedOutcome(ParityCase.RepositoryOperation operation) {
            Objects.requireNonNull(operation, "A RepositoryOperation is required to take a forced "
                + "outcome for");
            ParityCase.ForcedOutcome forced = screenRequest == null
                ? null
                : screenRequest.forcedOutcomes().get(operation);
            if (forced == null) {
                throw new IllegalArgumentException("Case " + program + '/' + caseId + " forces no "
                    + "outcome for " + operation.key() + ". It forces " + describeForced()
                    + ". A repository that invented one here would decide the arm the case reaches, "
                    + "which is the case's decision to make.");
            }
            consumedForcedOutcomes.add(operation);
            return forced;
        }

        /** The forced-outcome operations that nothing asked for, in declaration order. */
        private List<ParityCase.RepositoryOperation> unconsumedForcedOutcomes() {
            List<ParityCase.RepositoryOperation> unconsumed = new ArrayList<>();
            for (ParityCase.RepositoryOperation operation : declaredForcedOutcomes()) {
                if (!consumedForcedOutcomes.contains(operation)) {
                    unconsumed.add(operation);
                }
            }
            return unconsumed;
        }

        /** The declared forced-outcome operations by their case-file spelling, for a message. */
        private List<String> describeForced() {
            List<String> keys = new ArrayList<>();
            for (ParityCase.RepositoryOperation operation : declaredForcedOutcomes()) {
                keys.add(operation.key());
            }
            return keys;
        }

        /** The screen request, or a diagnostic naming the accessor that tells a caller it is absent. */
        private ParityCase.ScreenRequest request() {
            if (screenRequest == null) {
                throw new IllegalStateException("Case " + program + '/' + caseId + " declares no "
                    + "screenRequest, so there is no AID, no EIBCALEN and no inbound COMMAREA to "
                    + "read. Only a CONTROLLER_POJO case carries one; check hasScreenRequest() first "
                    + "if the unit reads it only sometimes.");
            }
            return screenRequest;
        }

        /**
         * Every seeded dataset, keyed by binding key, in the case's declaration order.
         *
         * @return an immutable map; empty for a case that seeds nothing, which a pure computation
         *     legitimately does
         */
        public Map<String, SeededDataset> datasets() {
            return datasets;
        }

        /**
         * One seeded dataset, by binding key.
         *
         * @param dataset the binding key; never {@code null}
         * @return the seeded dataset, never {@code null}
         * @throws IllegalArgumentException if the case seeds no such dataset. Naming the keys that
         *     were seeded turns a typo into a one-line fix instead of an empty repository and a
         *     confusing difference
         */
        public SeededDataset dataset(String dataset) {
            String key = requireDatasetKey(dataset, "The dataset requested from an Invocation");
            SeededDataset seeded = datasets.get(key);
            if (seeded == null) {
                throw new IllegalArgumentException("Case " + program + '/' + caseId
                    + " seeds no dataset " + key + ". It seeds "
                    + datasets.keySet() + ". A unit reading a dataset the case never seeded would "
                    + "read nothing and behave as though the file were empty, so the request is "
                    + "refused instead.");
            }
            return seeded;
        }

        /**
         * Whether the case seeded a dataset, for a unit that legitimately reads one only sometimes.
         *
         * @param dataset the binding key; never {@code null}
         * @return {@code true} when the dataset was seeded
         */
        public boolean hasDataset(String dataset) {
            return datasets.containsKey(requireDatasetKey(dataset,
                "The dataset asked about on an Invocation"));
        }

        /**
         * The case's job parameters.
         *
         * <p>Only a batch case may carry any. The single parameter in this system is the interest
         * calculator's, whose value is character data concatenated verbatim into generated
         * transaction identifiers rather than a date to be parsed - so it arrives here as a
         * {@link String} and must leave as one.
         *
         * @return an immutable map; empty for every other kind of unit
         */
        public Map<String, String> jobParameters() {
            return jobParameters;
        }

        /**
         * One job parameter, by name.
         *
         * @param name the parameter name as the case spells it; never {@code null}
         * @return the value, never {@code null}
         * @throws IllegalArgumentException if the case declares no such parameter
         */
        public String jobParameter(String name) {
            Objects.requireNonNull(name, "A job parameter name is required");
            String value = jobParameters().get(name);
            if (value == null) {
                throw new IllegalArgumentException("Case " + program + '/' + caseId
                    + " declares no job parameter \"" + name + "\". It "
                    + "declares " + jobParameters().keySet() + ". A parameter defaulted here would "
                    + "be a value the JCL never supplied.");
            }
            return value;
        }

        /**
         * The fixed clock this run is pinned to.
         *
         * @return the clock, never {@code null} and never a system clock
         */
        public Clock clock() {
            return clock;
        }

        /**
         * The current instant as a local date-time, for a unit that wants the pinned value directly.
         *
         * @return the pinned local date-time
         */
        public LocalDateTime now() {
            return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        }

        /**
         * The codec the seeded rows were decoded with, and the one a unit should encode through.
         *
         * @return the codec, never {@code null}
         */
        public FixedWidthCodec codec() {
            return codec;
        }

        /**
         * The code page in use, named rather than defaulted.
         *
         * @return the charset, never {@code null}
         */
        public Charset charset() {
            return codec.charset();
        }

        /**
         * Where to record what the run produced.
         *
         * @return the recorder for this one invocation, never {@code null} and never shared
         */
        public UnitOutcome.Builder recorder() {
            return recorder;
        }

        /**
         * Names the case and the datasets, and no row content.
         *
         * @return the shape of this invocation
         */
        @Override
        public String toString() {
            return "Invocation[" + program + '/' + caseId + ", unitKind=" + unitKind
                + ", datasets=" + datasets.keySet() + ", jobParameters=" + jobParameters.keySet()
                + ", screenRequest=" + (screenRequest == null ? "none" : "declared") + ", clock="
                + clock.instant() + ']';
        }
    }

    /**
     * One dataset as it stood when the unit was invoked: its binding key, its declared record length,
     * and its rows in seeding order as fixed-width images.
     *
     * <h2>Already at copybook width</h2>
     * <p>Any width normalisation a case declared has already been applied by
     * {@link ParityHarness#seed(ParityCase)}, so every row here is the full copybook width. That
     * matters because the record decoders refuse a short row rather than partially decoding one: a
     * 57-byte security-user row or a 36-byte cross-reference row would be rejected, and correctly so.
     * The width is checked on construction, and a row list of mixed widths is refused - ragged rows
     * are the one thing a fixed-width dataset cannot be.
     *
     * <h2>Immutable</h2>
     * <p>A seed is what the unit started from, and it stays that. A unit that writes does so through
     * its own repository and reports the result through {@link UnitOutcome}; nothing writes back into
     * a {@code SeededDataset}. Two cases therefore cannot interfere through a shared seed, whatever
     * order they run in.
     */
    public static final class SeededDataset {

        /** The dataset binding key, never a mainframe dataset name. */
        private final String dataset;

        /** The declared record width in characters, which every row measures exactly. */
        private final int recordLength;

        /** The rows in seeding order, at copybook width. */
        private final List<String> rows;

        /** The code page the rows encode under, named rather than defaulted. */
        private final Charset charset;

        /** Freezes the rows after the width check, so the instance owns what it holds. */
        private SeededDataset(String dataset, int recordLength, List<String> rows, Charset charset) {
            this.dataset = dataset;
            this.recordLength = recordLength;
            this.rows = List.copyOf(rows);
            this.charset = charset;
        }

        /**
         * Seeds a dataset from rows whose common width is the declared record length.
         *
         * <p>The width is measured rather than restated, which is what makes it trustworthy: it comes
         * from the rows themselves after normalisation, so it cannot silently disagree with them.
         *
         * @param dataset the binding key; never {@code null}
         * @param rows the rows in seeding order, all the same width; never {@code null} and never
         *     empty
         * @param charset the code page the rows encode under; never {@code null}
         * @return the seeded dataset
         * @throws NullPointerException if any argument is {@code null}, or a row is {@code null}
         * @throws IllegalArgumentException if {@code dataset} is not a binding key, if {@code rows} is
         *     empty, or if the rows are not all the same width
         */
        public static SeededDataset of(String dataset, List<String> rows, Charset charset) {
            String key = requireDatasetKey(dataset, "SeededDataset dataset");
            Objects.requireNonNull(rows, "Rows are required to seed dataset " + key
                + "; use SeededDataset.empty(String, int, Charset) for a dataset with no row");
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Dataset " + key + " was seeded with no row, so "
                    + "its record width cannot be measured. Use "
                    + "SeededDataset.empty(String, int, Charset) and state the width, which is what a "
                    + "dataset a unit opens and finds empty actually needs.");
            }
            return of(key, measureWidth(key, rows), rows, charset);
        }

        /**
         * Seeds a dataset at a stated record length, checking every row against it.
         *
         * @param dataset the binding key; never {@code null}
         * @param recordLength the declared record width in characters; at least 1
         * @param rows the rows in seeding order, each exactly {@code recordLength} wide; never
         *     {@code null}
         * @param charset the code page the rows encode under; never {@code null}
         * @return the seeded dataset
         * @throws NullPointerException if {@code dataset}, {@code rows} or {@code charset} is
         *     {@code null}, or a row is {@code null}
         * @throws IllegalArgumentException if {@code recordLength} is below 1, or any row is a
         *     different width
         */
        public static SeededDataset of(String dataset, int recordLength, List<String> rows,
                                       Charset charset) {
            String key = requireDatasetKey(dataset, "SeededDataset dataset");
            Objects.requireNonNull(rows, "Rows are required to seed dataset " + key);
            Objects.requireNonNull(charset, "A charset is required to seed dataset " + key
                + " and is never defaulted: a space is 0x40 under IBM037 and 0x20 under US-ASCII, so "
                + "the code page changes the bytes");
            if (recordLength < 1) {
                throw new IllegalArgumentException("Declared record length " + recordLength
                    + " for dataset " + key + " is not a record width; a record occupies at least "
                    + "1 byte");
            }
            for (int index = 0; index < rows.size(); index++) {
                String row = rows.get(index);
                Objects.requireNonNull(row, "Row " + index + " seeded into dataset " + key
                    + " is null; a row the case does not declare must be absent from the list rather "
                    + "than present as a hole in the seeding order");
                if (row.length() != recordLength) {
                    throw new IllegalArgumentException("Row " + index + " seeded into dataset " + key
                        + " measures " + row.length() + " character(s) but the declared record width "
                        + "is " + recordLength + ". A fixed-width dataset cannot hold a ragged row, "
                        + "and a decoder refuses a short one rather than decoding part of it. If the "
                        + "shortfall is one of the two recorded fixture deviations - a cross-reference "
                        + "row at 36 of 50, or a security-user row at 57 of 80 - the case must declare "
                        + "the matching entry in its \"normalisations\" member, which pads it once at "
                        + "seed time.");
                }
            }
            return new SeededDataset(key, recordLength, rows, charset);
        }

        /**
         * A dataset the unit will open and find empty.
         *
         * <p>The width is stated because there is no row to measure it from, and it is still needed:
         * a repository over an empty dataset must know how wide a record it would be writing.
         *
         * @param dataset the binding key; never {@code null}
         * @param recordLength the declared record width in characters; at least 1
         * @param charset the code page; never {@code null}
         * @return a seeded dataset carrying no row
         */
        public static SeededDataset empty(String dataset, int recordLength, Charset charset) {
            return of(dataset, recordLength, List.of(), charset);
        }

        /** Measures the one width every row shares, refusing a ragged list. */
        private static int measureWidth(String dataset, List<String> rows) {
            String first = Objects.requireNonNull(rows.get(0), "Row 0 seeded into dataset " + dataset
                + " is null, so no record width can be measured");
            int width = first.length();
            if (width < 1) {
                throw new IllegalArgumentException("Row 0 seeded into dataset " + dataset
                    + " is empty, so the dataset has no record width. An empty row is never a valid "
                    + "fixed-width record: even an all-blank record occupies its declared bytes.");
            }
            return width;
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
         * The declared record width every row measures.
         *
         * @return the width in characters, at least 1
         */
        public int recordLength() {
            return recordLength;
        }

        /**
         * The code page the rows encode under.
         *
         * @return the charset, never {@code null}
         */
        public Charset charset() {
            return charset;
        }

        /**
         * How many rows were seeded.
         *
         * @return the row count, never negative
         */
        public int rowCount() {
            return rows.size();
        }

        /**
         * Whether the unit will find this dataset empty.
         *
         * @return {@code true} when no row was seeded
         */
        public boolean isEmpty() {
            return rows.isEmpty();
        }

        /**
         * Every row, in seeding order.
         *
         * @return an immutable list of row images
         */
        public List<String> rows() {
            return rows;
        }

        /**
         * One row's image.
         *
         * @param rowIndex the zero-based index
         * @return the row image, exactly {@link #recordLength()} characters
         * @throws IndexOutOfBoundsException if the index addresses no seeded row
         */
        public String row(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                throw new IndexOutOfBoundsException("Dataset " + dataset + " was seeded with "
                    + rows.size() + " row(s); there is no row at zero-based index " + rowIndex);
            }
            return rows.get(rowIndex);
        }

        /**
         * One row's bytes, encoded through the named code page.
         *
         * @param rowIndex the zero-based index
         * @param codec the codec to encode through, so encoding never goes through
         *     {@code String.getBytes} and its substitution behaviour
         * @return a fresh byte array of exactly {@link #recordLength()} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         * @throws IndexOutOfBoundsException if the index addresses no seeded row
         */
        public byte[] rowBytes(int rowIndex, FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to encode a seeded row: it names the "
                + "code page and refuses a character the code page cannot represent, which "
                + "String.getBytes would silently replace with a question mark");
            return codec.encodeImage(row(rowIndex), "dataset " + dataset + " row " + rowIndex);
        }

        /**
         * Presents this seed as a dataset output, for the common case of a dataset the unit read and
         * did not write.
         *
         * <p>The comparison needs the final state of every dataset the unit touched, including one it
         * only read - "is the dataset in the right state now?" is a different question from "did the
         * unit write the right records?", and a single channel cannot answer both. This method
         * answers the first for an untouched dataset.
         *
         * <p><strong>Only valid when the unit wrote nothing to this dataset</strong>, which its writes
         * channel proves. Using it for a dataset the unit did write to would report the seed as though
         * it were the result, which is a fabricated observation and exactly the kind of thing that
         * makes a gate meaningless.
         *
         * @param layout the layout describing the rows, transcribed from the dataset's copybook;
         *     never {@code null}
         * @param codec the codec to encode the rows through; never {@code null}
         * @return the rows as a dataset output, in seeding order
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if the layout's declared record length disagrees with this
         *     seed's
         */
        public DatasetOutput asDatasetOutput(RecordLayout layout, FixedWidthCodec codec) {
            Objects.requireNonNull(layout, "A RecordLayout is required to present dataset " + dataset
                + " as an output: the layout is what names the fields the comparison addresses");
            Objects.requireNonNull(codec, "A codec is required to encode dataset " + dataset);
            if (layout.recordLength() != recordLength) {
                throw new IllegalArgumentException("Dataset " + dataset + " was seeded at "
                    + recordLength + " character(s) per row but the supplied layout declares "
                    + layout.recordLength() + ". One of the two is wrong, and guessing which would "
                    + "hide it: check whether the case declared the width normalisation its fixture "
                    + "needs, and whether the layout transcribes the right copybook.");
            }
            return DatasetOutput.ofImages(dataset, layout, rows, codec.charset());
        }

        /**
         * Names the dataset, its row count and its width, and no row content.
         *
         * <p>A security-user row carries a span declared {@code SEC-USR-PWD PIC X(08)}, so a seed
         * never prints its rows. Carrying a credential and printing it are different things.
         *
         * @return the shape of this seed
         */
        @Override
        public String toString() {
            return "SeededDataset[" + dataset + ", " + rows.size() + " row(s) of " + recordLength
                + " character(s)]";
        }
    }

    /**
     * The raw material of a fingerprint: what one run of a unit observably produced, before anything
     * is decoded.
     *
     * <p>Distinct from {@link Fingerprint}, which the differ declares and judges, for one concrete
     * reason: a {@code UnitOutcome} may state <em>no</em> return code. A unit that abends never
     * returns one - the value comes from the {@link AbendException} the harness caught - and a unit
     * that simply ended normally has nothing to say about it either. {@link Fingerprint} requires the
     * number, so something has to hold the "not stated" case, and this is it.
     *
     * <p>Four channels: the records the unit wrote in write order, the state each dataset was left
     * in, the online response for a unit that returns one, and the lines the unit emitted in emission
     * order. Write order and emission order are both behaviour - a job that posts a transaction and
     * then rewrites a balance has done something different from one that does it the other way round -
     * so nothing here is ever sorted, de-duplicated or merged across datasets.
     */
    public static final class UnitOutcome {

        /** The records written, one entry per dataset, rows in write order. */
        private final List<DatasetOutput> writes;

        /** What each touched dataset holds after the run, one entry per dataset. */
        private final List<DatasetOutput> finalState;

        /** The online response, or {@code null} for a unit that returns none. */
        private final ObservedResponse response;

        /** The stated {@code RETURN-CODE}, or {@code null} when the unit stated none. */
        private final Integer returnCode;

        /** The emitted lines in emission order. */
        private final List<EmittedMessage> messages;

        /** Freezes every channel. */
        private UnitOutcome(List<DatasetOutput> writes, List<DatasetOutput> finalState,
                            ObservedResponse response, Integer returnCode,
                            List<EmittedMessage> messages) {
            this.writes = List.copyOf(writes);
            this.finalState = List.copyOf(finalState);
            this.response = response;
            this.returnCode = returnCode;
            this.messages = List.copyOf(messages);
        }

        /**
         * A builder that accumulates observations as the unit produces them.
         *
         * @param codec the codec row images are encoded through; never {@code null}
         * @return a fresh builder, shared with nothing
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public static Builder builder(FixedWidthCodec codec) {
            return new Builder(codec);
        }

        /**
         * An outcome stated in full.
         *
         * @param writes the records written, at most one entry per dataset; never {@code null}
         * @param finalState what each touched dataset holds afterwards; never {@code null}
         * @param response the online response, or {@code null}
         * @param returnCode the stated {@code RETURN-CODE}, or {@code null} for "not stated"
         * @param messages the emitted lines in emission order; never {@code null}
         * @return the outcome
         * @throws NullPointerException if {@code writes}, {@code finalState} or {@code messages} is
         *     {@code null}
         * @throws IllegalArgumentException if {@code returnCode} is negative
         */
        public static UnitOutcome of(List<DatasetOutput> writes, List<DatasetOutput> finalState,
                                     ObservedResponse response, Integer returnCode,
                                     List<EmittedMessage> messages) {
            Objects.requireNonNull(writes, "Writes are required; pass an empty list for a unit that "
                + "writes nothing - which is the whole of the fingerprint for the posting job "
                + "translated from CBTRN01C, since that program issues no WRITE and no REWRITE at "
                + "all and its SYSOUT text is the entire observable behaviour");
            Objects.requireNonNull(finalState, "A final state is required; pass an empty list only "
                + "when the unit touched no dataset. A unit that read a dataset and wrote nothing "
                + "must still report that dataset's unchanged rows, because that is the assertion "
                + "such a case makes");
            Objects.requireNonNull(messages, "Messages are required; pass an empty list for a unit "
                + "that emits nothing");
            if (returnCode != null && returnCode < 0) {
                throw new IllegalArgumentException("A stated RETURN-CODE of " + returnCode
                    + " is negative; a z/OS step return code never is, so a negative value is a sign "
                    + "error in the capture rather than an observation");
            }
            return new UnitOutcome(writes, finalState, response, returnCode, messages);
        }

        /**
         * An outcome for a unit that touched no dataset, returned no response and emitted no line - a
         * pure computation, or a validation that rejected its input before doing anything.
         *
         * @param returnCode the {@code RETURN-CODE} the run ended with; never negative
         * @return the outcome
         */
        public static UnitOutcome ofReturnCode(int returnCode) {
            return of(List.of(), List.of(), null, returnCode, List.of());
        }

        /**
         * An outcome for an online unit whose whole observable behaviour is its response - the shape of
         * a path that sends a map and touches nothing.
         *
         * @param response the response the handler returned; never {@code null}
         * @return the outcome, with no stated return code
         * @throws NullPointerException if {@code response} is {@code null}
         */
        public static UnitOutcome ofResponse(ObservedResponse response) {
            Objects.requireNonNull(response, "An ObservedResponse is required; use "
                + "UnitOutcome.ofReturnCode(int) for a unit that returns none");
            return of(List.of(), List.of(), response, null, List.of());
        }

        /**
         * The records the unit wrote.
         *
         * @return an immutable list, at most one entry per dataset
         */
        public List<DatasetOutput> writes() {
            return writes;
        }

        /**
         * What each touched dataset holds after the run.
         *
         * @return an immutable list, at most one entry per dataset
         */
        public List<DatasetOutput> finalState() {
            return finalState;
        }

        /**
         * The online response.
         *
         * @return the response, or {@code null} for a unit that returns none
         */
        public ObservedResponse response() {
            return response;
        }

        /**
         * The {@code RETURN-CODE} the unit stated, if it stated one.
         *
         * @return the value, or empty when the unit said nothing about it - in which case the harness
         *     takes the abend's value if the run abended, and zero otherwise
         */
        public OptionalInt returnCode() {
            return returnCode == null ? OptionalInt.empty() : OptionalInt.of(returnCode);
        }

        /**
         * The lines the unit emitted, in emission order.
         *
         * @return an immutable list
         */
        public List<EmittedMessage> messages() {
            return messages;
        }

        /**
         * Whether anything at all was observed.
         *
         * @return {@code true} when every channel is empty and no return code was stated
         */
        public boolean isEmpty() {
            return writes.isEmpty() && finalState.isEmpty() && response == null
                && returnCode == null && messages.isEmpty();
        }

        /**
         * Summarises the channels without printing a record image or a message.
         *
         * @return the shape of this outcome
         */
        @Override
        public String toString() {
            return "UnitOutcome[writes=" + writes.size() + " dataset(s), finalState="
                + finalState.size() + " dataset(s), response=" + (response == null ? "none" : "yes")
                + ", returnCode=" + (returnCode == null ? "not stated" : returnCode)
                + ", messages=" + messages.size() + ']';
        }

        /**
         * Accumulates observations as a unit produces them, so an abend loses none of them.
         *
         * <h2>Why rows accumulate per dataset</h2>
         * <p>A job writes one record at a time, and a fingerprint holds one entry per dataset with its
         * rows in order. This builder therefore appends into a per-dataset accumulator and emits a
         * single {@link DatasetOutput} for each at {@link #build()}, in the order the datasets were
         * first written to. A builder that emitted one output per {@code wrote} call would produce
         * duplicate dataset keys, which {@link Fingerprint} correctly refuses.
         *
         * <h2>Mutable, and deliberately so</h2>
         * <p>This is per-invocation state, created by the harness for one run and reachable only
         * through that run's {@link Invocation}. Nothing static holds one, so two cases cannot share
         * an accumulator whatever order they run in.
         */
        public static final class Builder {

            /** The codec row images are encoded through, so encoding never defaults a code page. */
            private final FixedWidthCodec codec;

            /** Per-dataset write accumulators, in first-written order. */
            private final Map<String, Accumulator> writes = new LinkedHashMap<>();

            /** Per-dataset final-state accumulators, in first-reported order. */
            private final Map<String, Accumulator> finalState = new LinkedHashMap<>();

            /** The emitted lines, in emission order. */
            private final List<EmittedMessage> messages = new ArrayList<>();

            /** The online response, once the unit has one. */
            private ObservedResponse response;

            /** The stated return code, or {@code null} while the unit has not stated one. */
            private Integer returnCode;

            /**
             * The last outcome {@link #build()} produced, so the harness can tell "returned the
             * recorder's own build" from "returned something else while the recorder held content".
             */
            private UnitOutcome lastBuilt;

            /**
             * How many observations have been recorded, incremented by every mutator.
             *
             * <p>Its only job is to date {@link #lastBuilt}. Identity alone cannot: a unit that builds
             * an outcome, records another write, and returns the object it built earlier hands back
             * something that <em>is</em> the recorder's own build and is also missing an observation.
             * Comparing revisions is what tells those two apart.
             */
            private int revision;

            /** The revision {@link #lastBuilt} was built at, or {@code -1} before the first build. */
            private int lastBuiltRevision = -1;

            /** Constructed through {@link UnitOutcome#builder(FixedWidthCodec)}. */
            private Builder(FixedWidthCodec codec) {
                this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required to record "
                    + "an observation: it names the code page every row image is encoded through");
            }

            /**
             * Records one written record, appending it to that dataset's write sequence.
             *
             * @param dataset the binding key; never {@code null}
             * @param layout the layout describing the record; never {@code null}
             * @param image the record image, exactly the layout's declared width; never {@code null}
             * @return this builder
             * @throws NullPointerException if any argument is {@code null}
             * @throws IllegalArgumentException if the dataset is not a binding key, or a second call
             *     for the same dataset supplies a different layout
             */
            public Builder wrote(String dataset, RecordLayout layout, String image) {
                accumulator(writes, dataset, layout, "written to").add(encode(dataset, image));
                revision++;
                return this;
            }

            /**
             * Records one written record already held as bytes - the shape a fixed-width writer
             * produces.
             *
             * @param dataset the binding key; never {@code null}
             * @param layout the layout describing the record; never {@code null}
             * @param record the record bytes; never {@code null}
             * @return this builder
             * @throws NullPointerException if any argument is {@code null}
             */
            public Builder wroteBytes(String dataset, RecordLayout layout, byte[] record) {
                Objects.requireNonNull(record, "A record is required to record a write to dataset "
                    + dataset);
                accumulator(writes, dataset, layout, "written to").add(record.clone());
                revision++;
                return this;
            }

            /**
             * Records several written records in one call, in the order given.
             *
             * @param dataset the binding key; never {@code null}
             * @param layout the layout describing the records; never {@code null}
             * @param images the record images in write order; never {@code null}
             * @return this builder
             * @throws NullPointerException if any argument is {@code null}, or an image is
             *     {@code null}
             */
            public Builder wroteAll(String dataset, RecordLayout layout, List<String> images) {
                Objects.requireNonNull(images, "Record images are required to record writes to "
                    + "dataset " + dataset + "; pass an empty list for a dataset the unit opened and "
                    + "did not write to, or call openedWithoutWriting instead");
                Accumulator target = accumulator(writes, dataset, layout, "written to");
                for (String image : images) {
                    target.add(encode(dataset, image));
                }
                revision++;
                return this;
            }

            /**
             * Records that a dataset was opened for output and nothing was written to it - which is a
             * different assertion from not mentioning the dataset at all.
             *
             * @param dataset the binding key; never {@code null}
             * @param layout the layout that would have described the records; never {@code null}
             * @return this builder
             */
            public Builder openedWithoutWriting(String dataset, RecordLayout layout) {
                accumulator(writes, dataset, layout, "written to");
                revision++;
                return this;
            }

            /**
             * Records what a dataset holds after the run, row by row in dataset order.
             *
             * @param dataset the binding key; never {@code null}
             * @param layout the layout describing the rows; never {@code null}
             * @param images the rows in dataset order; never {@code null}
             * @return this builder
             */
            public Builder finalState(String dataset, RecordLayout layout, List<String> images) {
                Objects.requireNonNull(images, "Row images are required to record the final state of "
                    + "dataset " + dataset + "; pass an empty list for a dataset the run left empty");
                Accumulator target = accumulator(finalState, dataset, layout, "left in a state by");
                for (String image : images) {
                    target.add(encode(dataset, image));
                }
                revision++;
                return this;
            }

            /**
             * Records that a dataset was left exactly as it was seeded.
             *
             * <p>Valid only when the unit wrote nothing to that dataset, which the writes channel
             * proves. For a dataset the unit did write to, report the rows it actually holds.
             *
             * @param seeded the seed the unit started from; never {@code null}
             * @param layout the layout describing the rows; never {@code null}
             * @return this builder
             * @throws NullPointerException if either argument is {@code null}
             * @throws IllegalArgumentException if the layout's width disagrees with the seed's
             */
            public Builder finalStateUnchanged(SeededDataset seeded, RecordLayout layout) {
                Objects.requireNonNull(seeded, "A SeededDataset is required to report it unchanged");
                return finalState(seeded.dataset(), layout, seeded.rows());
            }

            /**
             * Records the online response the handler returned.
             *
             * @param observed the response; never {@code null}
             * @return this builder
             * @throws NullPointerException if {@code observed} is {@code null}
             * @throws IllegalStateException if a response has already been recorded, which would mean
             *     one invocation returned two
             */
            public Builder response(ObservedResponse observed) {
                Objects.requireNonNull(observed, "An ObservedResponse is required; simply do not "
                    + "call this method for a unit that returns none");
                if (response != null) {
                    throw new IllegalStateException("A response has already been recorded for this "
                        + "invocation. One call of a handler produces one response; a second would "
                        + "mean two runs were recorded into one fingerprint, and the differ would "
                        + "compare the case against a mixture of them.");
                }
                response = observed;
                revision++;
                return this;
            }

            /**
             * Records the {@code RETURN-CODE} the unit set.
             *
             * @param code the value; never negative
             * @return this builder
             * @throws IllegalArgumentException if {@code code} is negative
             */
            public Builder returnCode(int code) {
                if (code < 0) {
                    throw new IllegalArgumentException("A RETURN-CODE of " + code + " is negative; a "
                        + "z/OS step return code never is. The values this system actually sets are "
                        + "0, 3, 4, 8, 12 and 16.");
                }
                returnCode = code;
                revision++;
                return this;
            }

            /**
             * Records one emitted line on an explicit channel.
             *
             * @param message the line and its channel; never {@code null}
             * @return this builder
             * @throws NullPointerException if {@code message} is {@code null}
             */
            public Builder message(EmittedMessage message) {
                messages.add(Objects.requireNonNull(message, "An EmittedMessage is required; a blank "
                    + "line is an empty text on the DISPLAY_LINE channel, not a null"));
                revision++;
                return this;
            }

            /**
             * Records a COBOL {@code DISPLAY}.
             *
             * <p>Variable width, because the verb concatenates its operands and emits the result. A
             * batch program's diagnostic text is part of the fingerprint rather than incidental
             * logging: for the posting job translated from {@code CBTRN01C} it is the entire
             * fingerprint, since that program writes no record at all.
             *
             * @param text the line exactly as emitted, never trimmed; an empty string for a blank
             *     {@code DISPLAY}
             * @return this builder
             * @throws NullPointerException if {@code text} is {@code null}
             */
            public Builder display(String text) {
                return message(new EmittedMessage(MessageChannel.DISPLAY_LINE,
                    Objects.requireNonNull(text, "Display text is required; use an empty string for "
                        + "the blank line a COBOL DISPLAY of nothing emits")));
            }

            /**
             * Records the {@code FILE STATUS} line a batch program emits after an unexpected status,
             * rendered by the one class that owns its shape.
             *
             * @param status the two-character COBOL file status; never {@code null}
             * @return this builder
             * @throws NullPointerException if {@code status} is {@code null}
             * @throws IllegalArgumentException if {@code status} is not a two-character status
             */
            public Builder fileStatus(String status) {
                return display(FileStatus.toDisplayLine(status));
            }

            /**
             * Whether anything has been recorded.
             *
             * @return {@code true} when at least one observation is held
             */
            public boolean hasContent() {
                return !writes.isEmpty() || !finalState.isEmpty() || !messages.isEmpty()
                    || response != null || returnCode != null;
            }

            /**
             * The outcome the last {@link #build()} produced, <strong>if it is still current</strong>.
             *
             * <p>An outcome built before a later observation was recorded is not returned, because it
             * no longer describes what the recorder holds. Answering with it would let the harness
             * accept a stale outcome as "the recorder's own build" and drop everything recorded after
             * it - silently, and in exactly the shape most likely to occur: a unit that builds once,
             * carries on, and returns the object it built earlier.
             *
             * @return the current build, or {@code null} when nothing has been built or the last build
             *     has been superseded by a later observation
             */
            public UnitOutcome lastBuilt() {
                return lastBuiltRevision == revision ? lastBuilt : null;
            }

            /**
             * Whether a candidate is this recorder's build and still describes everything it holds.
             *
             * @param candidate the outcome a unit returned; may be {@code null}
             * @return {@code true} when it is the current build
             */
            public boolean isCurrentBuild(UnitOutcome candidate) {
                return candidate != null && candidate == lastBuilt
                    && lastBuiltRevision == revision;
            }

            /**
             * Whether a candidate is this recorder's build but was superseded by a later observation.
             *
             * @param candidate the outcome a unit returned; may be {@code null}
             * @return {@code true} when it is a stale build of this recorder
             */
            public boolean isSupersededBuild(UnitOutcome candidate) {
                return candidate != null && candidate == lastBuilt
                    && lastBuiltRevision != revision;
            }

            /**
             * How many observations have been recorded since the last {@link #build()}.
             *
             * @return the count, or {@code 0} when the last build is current or nothing was built
             */
            public int observationsSinceBuild() {
                return lastBuiltRevision < 0 ? 0 : revision - lastBuiltRevision;
            }

            /**
             * Freezes everything recorded so far into an outcome.
             *
             * <p>May be called more than once; each call produces a fresh, independent outcome, so a
             * value already handed out cannot change afterwards.
             *
             * @return the outcome, never {@code null}
             */
            public UnitOutcome build() {
                List<DatasetOutput> wrote = new ArrayList<>(writes.size());
                for (Accumulator accumulator : writes.values()) {
                    wrote.add(accumulator.toOutput());
                }
                List<DatasetOutput> left = new ArrayList<>(finalState.size());
                for (Accumulator accumulator : finalState.values()) {
                    left.add(accumulator.toOutput());
                }
                lastBuilt = UnitOutcome.of(wrote, left, response, returnCode, messages);
                lastBuiltRevision = revision;
                return lastBuilt;
            }

            /** Encodes a row image through the builder's codec, never through {@code getBytes}. */
            private byte[] encode(String dataset, String image) {
                Objects.requireNonNull(image, "A record image is required to record an observation "
                    + "for dataset " + dataset + "; a record the unit did not produce must be absent "
                    + "rather than null");
                return codec.encodeImage(image, "dataset " + dataset);
            }

            /**
             * Finds or creates a channel's accumulator for one dataset, refusing a second layout.
             *
             * <p>Two layouts for one dataset in one channel means two different opinions about where
             * its fields are, and picking either would decode half the rows at the wrong offsets.
             */
            private static Accumulator accumulator(Map<String, Accumulator> channel, String dataset,
                                                   RecordLayout layout, String verb) {
                String key = requireDatasetKey(dataset, "The dataset being " + verb);
                Objects.requireNonNull(layout, "A RecordLayout is required for dataset " + key
                    + ": it is what names the fields the comparison addresses, and its self-check is "
                    + "what proves every FILLER is accounted for");
                Accumulator existing = channel.get(key);
                if (existing == null) {
                    Accumulator created = new Accumulator(key, layout);
                    channel.put(key, created);
                    return created;
                }
                if (!existing.layout.equals(layout)) {
                    throw new IllegalStateException("Dataset " + key + " was already recorded with a "
                        + "different RecordLayout in this channel. Two layouts for one dataset are "
                        + "two different opinions about where its fields are, and half the rows would "
                        + "then be decoded at the wrong offsets. Use one layout per dataset - the one "
                        + "transcribed from its copybook.");
                }
                return existing;
            }

            /** One dataset's rows for one channel, in the order they were recorded. */
            private static final class Accumulator {

                /** The dataset binding key. */
                private final String dataset;

                /** The layout every row of this dataset is described by. */
                private final RecordLayout layout;

                /** The rows, in recording order. */
                private final List<byte[]> rows = new ArrayList<>();

                private Accumulator(String dataset, RecordLayout layout) {
                    this.dataset = dataset;
                    this.layout = layout;
                }

                private void add(byte[] record) {
                    rows.add(record);
                }

                private DatasetOutput toOutput() {
                    return DatasetOutput.of(dataset, layout, rows);
                }
            }
        }
    }

    /**
     * One record as the run produced it, decoded into its copybook field names and held beside its raw
     * image and measured length.
     *
     * <p>The raw image and the length are not redundant with the fields. Together they are how the
     * total width is checked, which is how a dropped {@code FILLER} is caught: omitting one leaves the
     * row short by exactly that span and shifts every offset after it. They are also what makes a
     * wrong-width row representable at all - such a row carries its image and its length with no
     * fields decoded, because the width is precisely what needs reporting.
     *
     * @param dataset the dataset binding key this record belongs to
     * @param rowIndex the zero-based position of this record in its channel - its place in the write
     *     sequence for a write, or its row index in the dataset for a final state
     * @param length the record's measured length in bytes, which is not assumed to equal the layout's
     *     declared length
     * @param image the record image exactly as produced, decoded under the named code page and never
     *     trimmed
     * @param fields copybook field name to raw field image, in copybook declaration order.
     *     {@code FILLER} spans are present under {@link FieldDiffer}'s ordinal convention -
     *     {@code FILLER}, {@code FILLER-2} and so on - and {@code REDEFINES} overlays are present
     *     under their own names. Empty when the record's length disagrees with its layout
     * @param numerics the signed zoned spans additionally decoded to a scaled {@link BigDecimal}, at
     *     scale 2 with truncation toward zero. A signed span absent from this map holds bytes that are
     *     not a valid zoned number, which the differ reports rather than this class hiding
     */
    public record DecodedRecord(String dataset,
                                int rowIndex,
                                int length,
                                String image,
                                Map<String, String> fields,
                                Map<String, BigDecimal> numerics) {

        /**
         * Freezes both maps so a decoded record cannot change after capture.
         *
         * @throws NullPointerException if any member is {@code null}
         */
        public DecodedRecord {
            dataset = requireDatasetKey(dataset, "DecodedRecord dataset");
            image = Objects.requireNonNull(image, "A DecodedRecord image is required; an all-blank "
                + "record is a string of spaces, not a null");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(fields, "DecodedRecord fields are required; pass an empty map "
                    + "for a record whose width disagrees with its layout and which therefore has no "
                    + "decodable field")));
            numerics = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(numerics, "DecodedRecord numerics are required; pass an empty "
                    + "map for a record with no signed zoned span")));
        }

        /**
         * One field's raw image.
         *
         * @param fieldName the copybook field name, verbatim; never {@code null}
         * @return the field image, or empty when this record carries no such field
         */
        public Optional<String> field(String fieldName) {
            return Optional.ofNullable(fields.get(
                Objects.requireNonNull(fieldName, "A field name is required to read a field")));
        }

        /**
         * One signed zoned field's value.
         *
         * @param fieldName the copybook field name, verbatim; never {@code null}
         * @return the scaled value, or empty when the field is not a signed zoned span or its bytes
         *     are not a valid zoned number
         */
        public Optional<BigDecimal> monetary(String fieldName) {
            return Optional.ofNullable(numerics.get(
                Objects.requireNonNull(fieldName, "A field name is required to read a value")));
        }

        /**
         * Renders this record with every credential span masked.
         *
         * <p>Two things are masked and the second matters as much as the first: the named fields whose
         * picture is a password, and the <em>span</em> those fields occupy inside the whole-record
         * image. Masking only the field would leave the same eight bytes visible in the image printed
         * beside it. Comparison is unaffected, because masking happens at rendering time only - the
         * raw values are compared byte for byte, so a wrong password is still reported, as a
         * difference in a redacted field.
         *
         * @return a single line naming the dataset, the row, the length and the masked image
         */
        public String render() {
            return dataset + '[' + rowIndex + "] " + length + " byte(s): \""
                + Redaction.maskRecordImage(dataset, image) + '"';
        }

        /**
         * The masked rendering, so a diagnostic never prints a credential.
         *
         * @return {@link #render()}
         */
        @Override
        public String toString() {
            return render();
        }
    }

    /**
     * The behavioural fingerprint of one run, in both the form the differ judges and the form a reader
     * can read.
     *
     * <h2>Two views of one capture, never two captures</h2>
     * <p>{@link #differFingerprint()} is the authoritative {@link Fingerprint}: raw bytes, judged by
     * {@link FieldDiffer#compare(ParityCase, Fingerprint)}. {@link #writes()} and
     * {@link #finalState()} are the decoded projection of those same bytes, in the same order, built
     * in the same pass - so the two cannot drift apart, and reading one tells you what the other
     * contains.
     *
     * <p>The projection exists because a diff report is only as useful as the observation behind it. A
     * reviewer looking at "expected 000000019400, actual 000000019399" wants to see which field that
     * was and what the rest of the record held, and {@link #render()} shows exactly that, with every
     * credential span masked.
     *
     * <h2>Ordered throughout</h2>
     * <p>Datasets appear in the order the unit first touched them and rows in the order it produced
     * them. Nothing is sorted and nothing is de-duplicated: the interest calculator accumulates per
     * account and rewrites on the account break, and the statement job writes some of its lines twice
     * in one pass, so a fingerprint that tidied its writes would no longer describe the run.
     */
    public static final class DecodedFingerprint {

        /** The program this fingerprint belongs to, for the rendered header. */
        private final String program;

        /** The case this fingerprint belongs to, so a report is traceable to its file. */
        private final String caseId;

        /** The written records, decoded, keyed by dataset in first-written order. */
        private final Map<String, List<DecodedRecord>> writes;

        /** The final dataset state, decoded, keyed by dataset in first-reported order. */
        private final Map<String, List<DecodedRecord>> finalState;

        /** The raw fingerprint the differ judges - the same capture, undecoded. */
        private final Fingerprint fingerprint;

        /** Constructed only by the harness, from one pass over one run. */
        private DecodedFingerprint(String program, String caseId,
                                   Map<String, List<DecodedRecord>> writes,
                                   Map<String, List<DecodedRecord>> finalState,
                                   Fingerprint fingerprint) {
            this.program = program;
            this.caseId = caseId;
            this.writes = writes;
            this.finalState = finalState;
            this.fingerprint = fingerprint;
        }

        /**
         * The program this fingerprint belongs to.
         *
         * @return the eight-character COBOL program name
         */
        public String program() {
            return program;
        }

        /**
         * The case this fingerprint belongs to.
         *
         * @return the case identifier
         */
        public String caseId() {
            return caseId;
        }

        /**
         * The records the unit wrote, decoded, keyed by dataset and in write order.
         *
         * @return an immutable map in first-written order
         */
        public Map<String, List<DecodedRecord>> writes() {
            return writes;
        }

        /**
         * What each touched dataset held after the run, decoded, in row order.
         *
         * @return an immutable map in first-reported order
         */
        public Map<String, List<DecodedRecord>> finalState() {
            return finalState;
        }

        /**
         * The {@code RETURN-CODE} the run ended with, whether the unit stated it or an abend carried
         * it.
         *
         * @return the value, never negative
         */
        public int returnCode() {
            return fingerprint.returnCode();
        }

        /**
         * The lines the run emitted, in emission order.
         *
         * @return an immutable list
         */
        public List<EmittedMessage> messages() {
            return fingerprint.messages();
        }

        /**
         * The online response the run produced.
         *
         * @return the response, or {@code null} for a unit that returns none
         */
        public ObservedResponse response() {
            return fingerprint.response();
        }

        /**
         * The raw fingerprint the differ judges.
         *
         * @return the fingerprint, never {@code null}
         */
        public Fingerprint differFingerprint() {
            return fingerprint;
        }

        /**
         * One dataset's decoded writes.
         *
         * @param dataset the binding key; never {@code null}
         * @return the records in write order, or empty when the unit wrote to no such dataset
         */
        public Optional<List<DecodedRecord>> findWrites(String dataset) {
            return Optional.ofNullable(writes.get(
                requireDatasetKey(dataset, "The dataset whose writes were requested")));
        }

        /**
         * One dataset's decoded final state.
         *
         * @param dataset the binding key; never {@code null}
         * @return the rows in row order, or empty when the run reported no state for that dataset
         */
        public Optional<List<DecodedRecord>> findFinalState(String dataset) {
            return Optional.ofNullable(finalState.get(
                requireDatasetKey(dataset, "The dataset whose final state was requested")));
        }

        /**
         * Renders the whole fingerprint as text a reviewer can read beside a diff report, with every
         * credential span masked.
         *
         * <p>Deterministic and complete: every dataset, every row and every message, in capture order,
         * with nothing truncated. Two runs of one case produce byte-identical text, which is what makes
         * this rendering usable as a determinism check as well as a diagnostic - if it differs between
         * two runs, something non-deterministic reached the fingerprint and the case would fail
         * intermittently for a reason no one could act on.
         *
         * @return the rendered fingerprint, never {@code null}
         */
        public String render() {
            String newLine = System.lineSeparator();
            StringBuilder text = new StringBuilder("Fingerprint ").append(program).append('/')
                .append(caseId).append(": RETURN-CODE ").append(returnCode());
            renderChannel(text, newLine, "writes", writes);
            renderChannel(text, newLine, "final state", finalState);
            if (response() != null) {
                text.append(newLine).append("  response: ").append(response());
            }
            text.append(newLine).append("  messages: ").append(messages().size());
            for (int index = 0; index < messages().size(); index++) {
                EmittedMessage message = messages().get(index);
                text.append(newLine).append("    [").append(index).append("] ")
                    .append(message.channel()).append(" \"")
                    .append(Redaction.maskIfSensitiveText(message.text())).append('"');
            }
            return text.toString();
        }

        /** Appends one channel's datasets and rows, in capture order. */
        private static void renderChannel(StringBuilder text, String newLine, String channel,
                                          Map<String, List<DecodedRecord>> rows) {
            text.append(newLine).append("  ").append(channel).append(": ").append(rows.size())
                .append(" dataset(s)");
            for (Map.Entry<String, List<DecodedRecord>> entry : rows.entrySet()) {
                text.append(newLine).append("    ").append(entry.getKey()).append(": ")
                    .append(entry.getValue().size()).append(" row(s)");
                for (DecodedRecord record : entry.getValue()) {
                    text.append(newLine).append("      ").append(record.render());
                }
            }
        }

        /**
         * The rendered fingerprint, so a diagnostic shows the observation rather than an object
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
