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
 * Seeds a parity case's datasets, runs the unit under test, captures what that run observably produced, and
 * hands the observation to {@link FieldDiffer} to be judged against the case.
 */
public final class ParityHarness {
    public static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    public static final int CASES_PER_PROGRAM = 20;

    public static final String CASE_RESOURCE_ROOT = "parity/";

    public static final String CASE_RESOURCE_EXTENSION = ".json";

    public static final String CASE_ID_PREFIX = "case";

    public static final LocalDateTime DEFAULT_PINNED_CLOCK =
        LocalDateTime.parse("2022-07-19T23:12:34");

    public static final ZoneOffset CLOCK_ZONE = ZoneOffset.UTC;

    private static final String FILLER_NAME = "FILLER";

    private static final String FILLER_ORDINAL_SEPARATOR = "-";

    private final FieldDiffer differ;

    private final ObjectMapper mapper;

    private final Clock clock;

    public ParityHarness(FieldDiffer differ, ObjectMapper mapper, Clock clock) {
        this.differ = Objects.requireNonNull(differ, "A FieldDiffer is required: the harness "
            + "captures the fingerprint and the differ judges it, and the differ's codec is also "
            + "where this harness's code page comes from");
        this.mapper = harden(Objects.requireNonNull(mapper, "An ObjectMapper is required to read a "
            + "case file; pass JsonMapper.builder().build() and it will be copied and hardened here"));
        this.clock = requireFixedClock(clock);
    }

    /**
     * The harness every shipped parity test uses: a {@code US-ASCII} differ, a default {@link JsonMapper},
     * and a clock fixed at {@link #DEFAULT_PINNED_CLOCK}.
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

    public FieldDiffer differ() {
        return differ;
    }

    public FixedWidthCodec codec() {
        return differ.codec();
    }

    public Charset charset() {
        return differ.codec().charset();
    }

    public Clock clock() {
        return clock;
    }

    public Clock clockFor(ParityCase parityCase) {
        Objects.requireNonNull(parityCase, "A ParityCase is required to resolve its clock");
        if (parityCase.screenRequest() == null) {
            return clock;
        }
        LocalDateTime pinned = parityCase.screenRequest().pinnedClockAt();
        return pinned == null ? clock : fixedClockAt(pinned);
    }

    public static Clock fixedClockAt(LocalDateTime at) {
        Objects.requireNonNull(at, "A local date-time is required to fix a clock at");
        return Clock.fixed(at.toInstant(CLOCK_ZONE), CLOCK_ZONE);
    }

    private static ObjectMapper harden(ObjectMapper supplied) {
        return supplied.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

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

        Clock frozen = Clock.fixed(candidate.instant(), candidate.getZone());
        if (!frozen.equals(candidate)) {
            throw new IllegalArgumentException("A clock that is not fixed was supplied ("
                + candidate.getClass().getName() + "). Clock.offset and Clock.tick over a system "
                + "clock tick, as does any custom implementation reading the wall time, and none of "
                + "them is equal to Clock.systemUTC() - so naming the system clocks does not catch "
                + "them. Use ParityHarness.fixedClockAt(LocalDateTime) or Clock.fixed(Instant, "
                + "ZoneId), or let the case pin its own instant through screenRequest.pinnedClock.");
        }

        return frozen;
    }

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
                + ParityCase.Redaction.sanitiseDiagnostic(failure.getMessage()),
                ParityCase.Redaction.sanitisedCause(failure));
        }
        return requireSelfConsistent(parityCase, program.strip(), caseId.strip(), resource);
    }

    public ParityCase readCase(byte[] content, String origin) throws IOException {
        Objects.requireNonNull(content, "A case body is required to read");
        Objects.requireNonNull(origin, "A description of where the case body came from is required, "
            + "so a failure says which fixture to open");
        try {
            return mapper.readValue(content, ParityCase.class);
        } catch (IllegalArgumentException | NullPointerException rejected) {
            throw new IOException("The case body from " + origin + " was refused by ParityCase's own "
                + "validation: " + ParityCase.Redaction.sanitiseDiagnostic(rejected.getMessage()),
                ParityCase.Redaction.sanitisedCause(rejected));
        }
    }

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
     * @param program the eight-character upper-case COBOL program name; never {@code null}
     * @return the program's cases in ascending case order, always of size {@value #CASES_PER_PROGRAM}
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if the program does not declare exactly {@value #CASES_PER_PROGRAM}
     *     readable cases
     */
    public static List<ParityCase> casesOf(String program) {
        return usAscii().cases(program);
    }

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
                + ParityCase.Redaction.sanitiseDiagnostic(failure.getMessage()),
                ParityCase.Redaction.sanitisedCause(failure));
        }
    }

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

    public static String caseResourcePath(String program, String caseId) {
        return CASE_RESOURCE_ROOT + requireProgram(program) + '/' + requireCaseId(caseId)
            + CASE_RESOURCE_EXTENSION;
    }

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

    private static boolean isUpperAlphanumeric(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= '0' && codePoint <= '9');
    }

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

    private static boolean classpathResourceExists(String resource) {
        return ParityHarness.class.getClassLoader().getResource(resource) != null;
    }

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
                + "read as " + subject + ": "
                + ParityCase.Redaction.sanitiseDiagnostic(failure.getMessage()),
                ParityCase.Redaction.sanitisedCause(failure));
        }
    }

    public Map<String, SeededDataset> seed(ParityCase parityCase) {
        Objects.requireNonNull(parityCase, "A ParityCase is required to seed from: its \"inputs\" "
            + "member names every dataset the unit under test will read");
        requireDeclaredCharset(parityCase);
        Map<String, SeededDataset> seeded = new LinkedHashMap<>();
        for (Map.Entry<String, DatasetInput> entry : parityCase.inputs().entrySet()) {
            String dataset = entry.getKey();
            DatasetInput input = entry.getValue();
            if (input.declaredEmpty()) {
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

    private static List<String> normalise(List<String> rows, String dataset, ParityCase parityCase) {
        for (DatasetNormalisation normalisation : parityCase.normalisations()) {
            if (normalisation.dataset().equals(dataset)) {
                return normalisation.normaliseSeedRows(rows);
            }
        }
        requireNoUndeclaredDeviation(rows, dataset);
        return List.copyOf(rows);
    }

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

    private static boolean allRowsMeasure(List<String> rows, int width) {
        for (String row : rows) {
            if (row == null || row.length() != width) {
                return false;
            }
        }
        return true;
    }

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
            outcome = recorder.build();
            abended = OptionalInt.of(abend.getReturnCode());
        } catch (Exception failure) {
            throw new IllegalStateException("Parity case " + parityCase.program() + '/'
                + parityCase.caseId() + " raised " + failure.getClass().getName()
                + ", which is not an abend and is therefore a defect rather than an observation. "
                + "The COBOL either abends - which arrives here as AbendException carrying a "
                + "RETURN-CODE and is compared like any other expectation - or it does not throw at "
                + "all. Sanitised message: " + Redaction.sanitiseDiagnostic(failure.getMessage()),
                Redaction.sanitisedCause(failure));
        }
        requireForcedOutcomesConsumed(parityCase, invocation);
        return capture(parityCase, outcome, resolveReturnCode(outcome, abended));
    }

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

    public DiffResult judge(ParityCase parityCase, ParityCase.UnitKind adapterKind,
                            ParityUnit unit) {
        return judge(parityCase, run(parityCase, adapterKind, unit));
    }

    public DiffResult judge(ParityCase parityCase, DecodedFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "A DecodedFingerprint is required to judge; use "
            + "run(ParityCase, ParityUnit) to produce one");
        return differ.compare(parityCase, fingerprint.differFingerprint());
    }

    public DecodedFingerprint capture(ParityCase parityCase, UnitOutcome outcome) {
        return capture(parityCase, outcome, resolveReturnCode(outcome, OptionalInt.empty()));
    }

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

    private static UnitOutcome resolveOutcome(UnitOutcome returned, UnitOutcome.Builder recorder,
                                              ParityCase parityCase) {
        if (returned == null) {
            return recorder.build();
        }
        if (recorder.isSupersededBuild(returned)) {
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

    private static int resolveReturnCode(UnitOutcome outcome, OptionalInt abended) {
        if (abended.isPresent()) {
            return abended.getAsInt();
        }
        return outcome.returnCode().orElse(AbendException.RETURN_CODE_OK);
    }

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

    private Optional<BigDecimal> decodeMonetary(String image) {
        try {
            return Optional.of(
                codec().decodeSignedZoned(image, CobolDecimal.MONETARY_SCALE).signedValue());
        } catch (RuntimeException notANumber) {
            return Optional.empty();
        }
    }

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

    @FunctionalInterface
    public interface ParityUnit {
        UnitOutcome invoke(Invocation invocation) throws Exception;
    }

    public static final class Invocation {
        private final String program;

        private final String caseId;

        private final ParityCase.UnitKind unitKind;

        private final Map<String, SeededDataset> datasets;

        private final Map<String, String> jobParameters;

        private final ParityCase.ScreenRequest screenRequest;

        private final ParityCase.UnitStimulus stimulus;

        private final Clock clock;

        private final FixedWidthCodec codec;

        private final UnitOutcome.Builder recorder;

        private final Set<ParityCase.RepositoryOperation> consumedForcedOutcomes =
            EnumSet.noneOf(ParityCase.RepositoryOperation.class);

        private Invocation(ParityCase parityCase, Map<String, SeededDataset> datasets, Clock clock,
                           FixedWidthCodec codec, UnitOutcome.Builder recorder) {
            this.program = parityCase.program();
            this.caseId = parityCase.caseId();
            this.unitKind = parityCase.unitKind();
            this.jobParameters = parityCase.jobParameters();
            this.screenRequest = parityCase.screenRequest();
            this.stimulus = parityCase.unitStimulus();
            this.datasets = datasets;
            this.clock = clock;
            this.codec = codec;
            this.recorder = recorder;
        }

        public String program() {
            return program;
        }

        public String caseId() {
            return caseId;
        }

        public ParityCase.UnitKind unitKind() {
            return unitKind;
        }

        public ParityCase.UnitStimulus stimulus() {
            return stimulus;
        }

        /**
         * {@code EIBCALEN} - the COMMAREA length CICS reports, which every online program tests to tell a
         * first entry from a re-entry.
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

        public Map<String, String> mapFields() {
            return request().mapFields();
        }

        public boolean hasScreenRequest() {
            return screenRequest != null;
        }

        public Set<ParityCase.RepositoryOperation> declaredForcedOutcomes() {
            return screenRequest == null
                ? Collections.emptySet()
                : screenRequest.forcedOutcomes().keySet();
        }

        public boolean hasForcedOutcome(ParityCase.RepositoryOperation operation) {
            Objects.requireNonNull(operation, "A RepositoryOperation is required to ask about a "
                + "forced outcome");
            return declaredForcedOutcomes().contains(operation);
        }

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

        private List<ParityCase.RepositoryOperation> unconsumedForcedOutcomes() {
            List<ParityCase.RepositoryOperation> unconsumed = new ArrayList<>();
            for (ParityCase.RepositoryOperation operation : declaredForcedOutcomes()) {
                if (!consumedForcedOutcomes.contains(operation)) {
                    unconsumed.add(operation);
                }
            }
            return unconsumed;
        }

        private List<String> describeForced() {
            List<String> keys = new ArrayList<>();
            for (ParityCase.RepositoryOperation operation : declaredForcedOutcomes()) {
                keys.add(operation.key());
            }
            return keys;
        }

        private ParityCase.ScreenRequest request() {
            if (screenRequest == null) {
                throw new IllegalStateException("Case " + program + '/' + caseId + " declares no "
                    + "screenRequest, so there is no AID, no EIBCALEN and no inbound COMMAREA to "
                    + "read. Only a CONTROLLER_POJO case carries one; check hasScreenRequest() first "
                    + "if the unit reads it only sometimes.");
            }
            return screenRequest;
        }

        public Map<String, SeededDataset> datasets() {
            return datasets;
        }

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

        public boolean hasDataset(String dataset) {
            return datasets.containsKey(requireDatasetKey(dataset,
                "The dataset asked about on an Invocation"));
        }

        public Map<String, String> jobParameters() {
            return jobParameters;
        }

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

        public Clock clock() {
            return clock;
        }

        public LocalDateTime now() {
            return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        }

        public FixedWidthCodec codec() {
            return codec;
        }

        public Charset charset() {
            return codec.charset();
        }

        public UnitOutcome.Builder recorder() {
            return recorder;
        }

        @Override
        public String toString() {
            return "Invocation[" + program + '/' + caseId + ", unitKind=" + unitKind
                + ", datasets=" + datasets.keySet() + ", jobParameters=" + jobParameters.keySet()
                + ", screenRequest=" + (screenRequest == null ? "none" : "declared") + ", clock="
                + clock.instant() + ']';
        }
    }

    public static final class SeededDataset {
        private final String dataset;

        private final int recordLength;

        private final List<String> rows;

        private final Charset charset;

        private SeededDataset(String dataset, int recordLength, List<String> rows, Charset charset) {
            this.dataset = dataset;
            this.recordLength = recordLength;
            this.rows = List.copyOf(rows);
            this.charset = charset;
        }

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

        public static SeededDataset empty(String dataset, int recordLength, Charset charset) {
            return of(dataset, recordLength, List.of(), charset);
        }

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

        public String dataset() {
            return dataset;
        }

        public int recordLength() {
            return recordLength;
        }

        public Charset charset() {
            return charset;
        }

        public int rowCount() {
            return rows.size();
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public List<String> rows() {
            return rows;
        }

        public String row(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                throw new IndexOutOfBoundsException("Dataset " + dataset + " was seeded with "
                    + rows.size() + " row(s); there is no row at zero-based index " + rowIndex);
            }
            return rows.get(rowIndex);
        }

        public byte[] rowBytes(int rowIndex, FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to encode a seeded row: it names the "
                + "code page and refuses a character the code page cannot represent, which "
                + "String.getBytes would silently replace with a question mark");
            return codec.encodeImage(row(rowIndex), "dataset " + dataset + " row " + rowIndex);
        }

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

        @Override
        public String toString() {
            return "SeededDataset[" + dataset + ", " + rows.size() + " row(s) of " + recordLength
                + " character(s)]";
        }
    }

    public static final class UnitOutcome {
        private final List<DatasetOutput> writes;

        private final List<DatasetOutput> finalState;

        private final ObservedResponse response;

        private final Integer returnCode;

        private final List<EmittedMessage> messages;

        private UnitOutcome(List<DatasetOutput> writes, List<DatasetOutput> finalState,
                            ObservedResponse response, Integer returnCode,
                            List<EmittedMessage> messages) {
            this.writes = List.copyOf(writes);
            this.finalState = List.copyOf(finalState);
            this.response = response;
            this.returnCode = returnCode;
            this.messages = List.copyOf(messages);
        }

        public static Builder builder(FixedWidthCodec codec) {
            return new Builder(codec);
        }

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

        public static UnitOutcome ofReturnCode(int returnCode) {
            return of(List.of(), List.of(), null, returnCode, List.of());
        }

        public static UnitOutcome ofResponse(ObservedResponse response) {
            Objects.requireNonNull(response, "An ObservedResponse is required; use "
                + "UnitOutcome.ofReturnCode(int) for a unit that returns none");
            return of(List.of(), List.of(), response, null, List.of());
        }

        public List<DatasetOutput> writes() {
            return writes;
        }

        public List<DatasetOutput> finalState() {
            return finalState;
        }

        public ObservedResponse response() {
            return response;
        }

        /**
         * The {@code RETURN-CODE} the unit stated, if it stated one.
         *
         * @return the value, or empty when the unit said nothing about it - in which case the harness takes
         *     the abend's value if the run abended, and zero otherwise
         */
        public OptionalInt returnCode() {
            return returnCode == null ? OptionalInt.empty() : OptionalInt.of(returnCode);
        }

        public List<EmittedMessage> messages() {
            return messages;
        }

        public boolean isEmpty() {
            return writes.isEmpty() && finalState.isEmpty() && response == null
                && returnCode == null && messages.isEmpty();
        }

        @Override
        public String toString() {
            return "UnitOutcome[writes=" + writes.size() + " dataset(s), finalState="
                + finalState.size() + " dataset(s), response=" + (response == null ? "none" : "yes")
                + ", returnCode=" + (returnCode == null ? "not stated" : returnCode)
                + ", messages=" + messages.size() + ']';
        }

        public static final class Builder {
            private final FixedWidthCodec codec;

            private final Map<String, Accumulator> writes = new LinkedHashMap<>();

            private final Map<String, Accumulator> finalState = new LinkedHashMap<>();

            private final List<EmittedMessage> messages = new ArrayList<>();

            private ObservedResponse response;

            private Integer returnCode;

            private UnitOutcome lastBuilt;

            private int revision;

            private int lastBuiltRevision = -1;

            private Builder(FixedWidthCodec codec) {
                this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required to record "
                    + "an observation: it names the code page every row image is encoded through");
            }

            public Builder wrote(String dataset, RecordLayout layout, String image) {
                accumulator(writes, dataset, layout, "written to").add(encode(dataset, image));
                revision++;
                return this;
            }

            public Builder wroteBytes(String dataset, RecordLayout layout, byte[] record) {
                Objects.requireNonNull(record, "A record is required to record a write to dataset "
                    + dataset);
                accumulator(writes, dataset, layout, "written to").add(record.clone());
                revision++;
                return this;
            }

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

            public Builder openedWithoutWriting(String dataset, RecordLayout layout) {
                accumulator(writes, dataset, layout, "written to");
                revision++;
                return this;
            }

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

            public Builder finalStateUnchanged(SeededDataset seeded, RecordLayout layout) {
                Objects.requireNonNull(seeded, "A SeededDataset is required to report it unchanged");
                return finalState(seeded.dataset(), layout, seeded.rows());
            }

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

            public Builder message(EmittedMessage message) {
                messages.add(Objects.requireNonNull(message, "An EmittedMessage is required; a blank "
                    + "line is an empty text on the DISPLAY_LINE channel, not a null"));
                revision++;
                return this;
            }

            /**
             * Records a COBOL {@code DISPLAY}.
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

            public Builder fileStatus(String status) {
                return display(FileStatus.toDisplayLine(status));
            }

            public boolean hasContent() {
                return !writes.isEmpty() || !finalState.isEmpty() || !messages.isEmpty()
                    || response != null || returnCode != null;
            }

            public UnitOutcome lastBuilt() {
                return lastBuiltRevision == revision ? lastBuilt : null;
            }

            public boolean isCurrentBuild(UnitOutcome candidate) {
                return candidate != null && candidate == lastBuilt
                    && lastBuiltRevision == revision;
            }

            public boolean isSupersededBuild(UnitOutcome candidate) {
                return candidate != null && candidate == lastBuilt
                    && lastBuiltRevision != revision;
            }

            public int observationsSinceBuild() {
                return lastBuiltRevision < 0 ? 0 : revision - lastBuiltRevision;
            }

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

            private byte[] encode(String dataset, String image) {
                Objects.requireNonNull(image, "A record image is required to record an observation "
                    + "for dataset " + dataset + "; a record the unit did not produce must be absent "
                    + "rather than null");
                return codec.encodeImage(image, "dataset " + dataset);
            }

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

            private static final class Accumulator {
                private final String dataset;

                private final RecordLayout layout;

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
     * @param dataset the dataset binding key this record belongs to
     * @param rowIndex the zero-based position of this record in its channel - its place in the write
     *     sequence for a write, or its row index in the dataset for a final state
     * @param length the record's measured length in bytes, which is not assumed to equal the layout's
     *     declared length
     * @param image the record image exactly as produced, decoded under the named code page and never
     *     trimmed
     * @param fields copybook field name to raw field image, in copybook declaration order
     * @param numerics the signed zoned spans additionally decoded to a scaled {@link BigDecimal}, at scale
     *     2 with truncation toward zero
     */
    public record DecodedRecord(String dataset,
                                int rowIndex,
                                int length,
                                String image,
                                Map<String, String> fields,
                                Map<String, BigDecimal> numerics) {
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

        public Optional<String> field(String fieldName) {
            return Optional.ofNullable(fields.get(
                Objects.requireNonNull(fieldName, "A field name is required to read a field")));
        }

        /**
         * One signed zoned field's value.
         *
         * @param fieldName the copybook field name, verbatim; never {@code null}
         * @return the scaled value, or empty when the field is not a signed zoned span or its bytes are not
         *     a valid zoned number
         */
        public Optional<BigDecimal> monetary(String fieldName) {
            return Optional.ofNullable(numerics.get(
                Objects.requireNonNull(fieldName, "A field name is required to read a value")));
        }

        public String render() {
            return dataset + '[' + rowIndex + "] " + length + " byte(s): \""
                + Redaction.maskRecordImage(dataset, image) + '"';
        }

        @Override
        public String toString() {
            return render();
        }
    }

    public static final class DecodedFingerprint {
        private final String program;

        private final String caseId;

        private final Map<String, List<DecodedRecord>> writes;

        private final Map<String, List<DecodedRecord>> finalState;

        private final Fingerprint fingerprint;

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

        public String program() {
            return program;
        }

        public String caseId() {
            return caseId;
        }

        public Map<String, List<DecodedRecord>> writes() {
            return writes;
        }

        public Map<String, List<DecodedRecord>> finalState() {
            return finalState;
        }

        /**
         * The {@code RETURN-CODE} the run ended with, whether the unit stated it or an abend carried it.
         *
         * @return the value, never negative
         */
        public int returnCode() {
            return fingerprint.returnCode();
        }

        public List<EmittedMessage> messages() {
            return fingerprint.messages();
        }

        public ObservedResponse response() {
            return fingerprint.response();
        }

        public Fingerprint differFingerprint() {
            return fingerprint;
        }

        public Optional<List<DecodedRecord>> findWrites(String dataset) {
            return Optional.ofNullable(writes.get(
                requireDatasetKey(dataset, "The dataset whose writes were requested")));
        }

        public Optional<List<DecodedRecord>> findFinalState(String dataset) {
            return Optional.ofNullable(finalState.get(
                requireDatasetKey(dataset, "The dataset whose final state was requested")));
        }

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

        @Override
        public String toString() {
            return render();
        }
    }
}
