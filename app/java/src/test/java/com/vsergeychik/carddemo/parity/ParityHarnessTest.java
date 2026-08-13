package com.vsergeychik.carddemo.parity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.parity.ParityCase.DatasetInput;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedRecord;
import com.vsergeychik.carddemo.parity.ParityCase.ExpectedResponse;
import com.vsergeychik.carddemo.parity.ParityCase.ForcedOutcome;
import com.vsergeychik.carddemo.parity.ParityCase.RepositoryOperation;
import com.vsergeychik.carddemo.parity.ParityCase.ScreenRequest;
import com.vsergeychik.carddemo.parity.ParityCase.Termination;
import com.vsergeychik.carddemo.parity.ParityCase.Redaction;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises {@link ParityHarness} - the half of the parity gate that seeds a case, reaches the unit under
 * test and captures what the run produced.
 */
@DisplayName("ParityHarness - seeding, reaching the unit, and what gets written down")
class ParityHarnessTest {
    private static final String PROGRAM = "CBACT01C";

    private static final String CASE_ID = "case01";

    private static final String SECRET = "SECRET99";

    private static ParityCase batchCase() {
        return new ParityCase(PROGRAM, CASE_ID, "a minimal valid case", UnitKind.BATCH_JOB,
            Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());
    }

    private static final RecordLayout LAYOUT = RecordLayout.of(12,
        FieldSpan.alphanumeric("ACCT-GROUP-ID", 0, 10), FieldSpan.filler(10, 2));

    private static final String ROW = "PREMIUM     ";

    private static ScreenRequest minimalRequest() {
        return new ScreenRequest(0, "DFHENTER", null, "US-ASCII", Map.of(), Map.of(), Map.of());
    }

    private static ExpectedResponse minimalResponse() {
        return new ExpectedResponse("COADM01C", null, null, Map.of(), List.of(), null,
            Termination.XCTL);
    }

    private static ParityHarness harnessWithClock(Clock clock) {
        return new ParityHarness(FieldDiffer.forCharset(StandardCharsets.US_ASCII),
            JsonMapper.builder().build(), clock);
    }

    private static ParityCase seedingCase(Map<String, DatasetInput> datasets) {
        return new ParityCase(PROGRAM, CASE_ID, "a seeded case", UnitKind.BATCH_JOB, datasets,
            Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());
    }

    @Nested
    @DisplayName("Strict oracle loading: a fixture cannot assert less than it appears to")
    class StrictLoading {
        private static final String SHIPPED_PROGRAM = "COUSR02C";

        private ParityCase read(String json) throws IOException {
            return ParityHarness.usAscii().readCase(json.getBytes(StandardCharsets.UTF_8),
                "an inline case body");
        }

        @Test
        @DisplayName("a duplicate member is refused rather than resolved to the last occurrence")
        void aDuplicateMemberIsRefused() {
            String json = shippedCaseBody().replace("\"expectedReturnCode\": 0",
                "\"expectedReturnCode\": 8, \"expectedReturnCode\": 0");

            Assertions.assertThatThrownBy(() -> read(json))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expectedReturnCode");
        }

        @Test
        @DisplayName("a trailing token is refused, so half a fixture cannot load as a whole one")
        void aTrailingTokenIsRefused() {
            Assertions.assertThatThrownBy(() -> read(shippedCaseBody() + " {\"program\": \"CBACT01C\"}"))
                .as("everything after the first complete value would otherwise be ignored")
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("an unknown member is refused even though the caller's mapper allowed it")
        void anUnknownMemberIsRefusedThroughALenientMapper() throws IOException {
            ObjectMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
            ParityHarness harness = new ParityHarness(FieldDiffer.forCharset(StandardCharsets.US_ASCII),
                lenient, ParityHarness.fixedClockAt(ParityHarness.DEFAULT_PINNED_CLOCK));
            String json = shippedCaseBody().replace("\"unitKind\"", "\"expectedReturnCodes\": 4, "
                + "\"unitKind\"");

            Assertions.assertThatThrownBy(() -> harness.readCase(
                    json.getBytes(StandardCharsets.UTF_8), "an inline case body"))
                .as("a typo in a member name must fail rather than be dropped")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expectedReturnCodes");
            Assertions.assertThat(lenient.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .as("the caller's own mapper must not have been mutated by the harness")
                .isFalse();
        }

        @Test
        @DisplayName("an omitted expectedReturnCode is refused instead of binding to zero")
        void anOmittedReturnCodeIsRefused() {
            String json = shippedCaseBody().replace("\"expectedReturnCode\": 0,", "");

            Assertions.assertThatThrownBy(() -> read(json))
                .as("zero is the most common correct answer, so a defaulted zero is the wrong value "
                    + "hardest to notice")
                .isInstanceOf(IOException.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasMessageContaining("expectedReturnCode");
        }

        @Test
        @DisplayName("an omitted rowIndex is refused instead of silently addressing row 0")
        void anOmittedRowIndexIsRefused() {
            String json = shippedCaseBody().replaceFirst("\"rowIndex\": \\d+,", "");

            Assertions.assertThatThrownBy(() -> read(json))
                .isInstanceOf(IOException.class)
                .hasRootCauseInstanceOf(NullPointerException.class)
                .hasMessageContaining("rowIndex");
        }

        @Test
        @DisplayName("a null against a mandatory member is refused as firmly as an absent one")
        void anExplicitNullIsRefused() {
            Assertions.assertThatThrownBy(
                    () -> read(shippedCaseBody().replace("\"expectedReturnCode\": 0",
                        "\"expectedReturnCode\": null")))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("the twenty shipped cases load, and the set is proved exact rather than sufficient")
        void theShippedSetLoadsAndIsExact() {
            List<ParityCase> cases = ParityHarness.casesOf(SHIPPED_PROGRAM);

            Assertions.assertThat(cases).hasSize(ParityHarness.CASES_PER_PROGRAM);
            Assertions.assertThat(cases).extracting(ParityCase::caseId)
                .containsExactly(caseIds());
            Assertions.assertThat(cases).allSatisfy(one -> Assertions.assertThat(one.program())
                .isEqualTo(SHIPPED_PROGRAM));
        }

        @ParameterizedTest(name = "a stray {0} in the case directory fails the enumeration")
        @DisplayName("a resource the enumeration will never read is refused by name")
        @ValueSource(strings = {"case21.json", "case00.json", "Case07.json", "case7.json",
            "case07.JSON", "case07.json.bak", "notes.txt"})
        void aStrayResourceIsRefused(String strayName) throws IOException {
            Path stray = caseDirectory().resolve(strayName);
            Files.writeString(stray, shippedCaseBody());
            try {
                Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> ParityHarness.casesOf(SHIPPED_PROGRAM))
                    .withMessageContaining(strayName)
                    .withMessageContaining("nobody runs");
            } finally {
                Files.deleteIfExists(stray);
            }
            Assertions.assertThat(ParityHarness.casesOf(SHIPPED_PROGRAM))
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        }

        private static String[] caseIds() {
            String[] ids = new String[ParityHarness.CASES_PER_PROGRAM];
            for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
                ids[ordinal - 1] = ParityHarness.caseId(ordinal);
            }
            return ids;
        }

        private static Path caseDirectory() {
            URL directory = ParityHarnessTest.class.getClassLoader()
                .getResource(ParityHarness.CASE_RESOURCE_ROOT + SHIPPED_PROGRAM);
            Assertions.assertThat(directory)
                .as("the shipped cases must be on the test classpath for this suite to mean anything")
                .isNotNull();
            try {
                return Path.of(directory.toURI());
            } catch (URISyntaxException impossible) {
                throw new IllegalStateException("a classpath directory URL that is not a URI",
                    impossible);
            }
        }

        private static String shippedCaseBody() {
            try {
                return Files.readString(caseDirectory().resolve("case01.json"),
                    StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException("the shipped case body could not be read", failure);
            }
        }
    }

    @Nested
    @DisplayName("Execution integrity: what the unit may see, what is kept, and what time it is")
    class ExecutionIntegrity {
        @Test
        @DisplayName("a unit is handed the case's inputs and no part of the expectation")
        void aUnitCannotReachTheExpectation() {
            List<String> exposed = new ArrayList<>();
            for (Method method : ParityHarness.Invocation.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    exposed.add(method.getName());
                }
            }

            Assertions.assertThat(exposed)
                .as("the inputs a legacy program was given, and the seams a unit records through")
                .contains("program", "caseId", "unitKind", "datasets", "dataset", "hasDataset",
                    "jobParameters", "jobParameter", "eibcalen", "aid", "commarea", "mapFields",
                    "hasScreenRequest", "declaredForcedOutcomes", "hasForcedOutcome",
                    "forcedOutcome", "clock", "now", "codec", "charset", "recorder");
            Assertions.assertThat(exposed)
                .as("and nothing through which the answer could be read")
                .doesNotContain("parityCase", "expectedWrites", "expectedFinalState",
                    "expectedReturnCode", "expectedMessages", "expectedResponse",
                    "expectedDatasets");
            Assertions.assertThat(ParityHarness.Invocation.class.getDeclaredFields())
                .as("no field holds the case either, so there is no accessor to add by accident")
                .noneMatch(field -> field.getType() == ParityCase.class);
        }

        @Test
        @DisplayName("the inputs that ARE exposed are the ones a unit needs, and they are correct")
        void theExposedInputsAreCorrect() {
            ParityCase parityCase = new ParityCase(PROGRAM, CASE_ID, "an online case",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(300, "DFHPF3", null, "US-ASCII", Map.of("CDEMO-USER-ID", "ADMIN001"),
                    Map.of("ACCTSIDI", "00000000011"), Map.of()),
                new ExpectedResponse("COADM01C", null, null, Map.of(), List.of(), null,
                    Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of());

            ParityHarness.usAscii().run(parityCase, UnitKind.CONTROLLER_POJO, invocation -> {
                Assertions.assertThat(invocation.program()).isEqualTo(PROGRAM);
                Assertions.assertThat(invocation.caseId()).isEqualTo(CASE_ID);
                Assertions.assertThat(invocation.unitKind()).isEqualTo(UnitKind.CONTROLLER_POJO);
                Assertions.assertThat(invocation.hasScreenRequest()).isTrue();
                Assertions.assertThat(invocation.aid()).isEqualTo("DFHPF3");
                Assertions.assertThat(invocation.eibcalen()).isEqualTo(300);
                Assertions.assertThat(invocation.mapFields())
                    .containsEntry("ACCTSIDI", "00000000011");
                Assertions.assertThat(invocation.commarea())
                    .containsEntry("CDEMO-USER-ID", "ADMIN001");
                return UnitOutcome.ofReturnCode(0);
            });
        }

        @Test
        @DisplayName("a batch case has no screen request, and asking says so rather than throwing NPE")
        void aBatchCaseHasNoScreenRequest() {
            ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                Assertions.assertThat(invocation.hasScreenRequest()).isFalse();
                Assertions.assertThatIllegalStateException()
                    .isThrownBy(invocation::aid)
                    .withMessageContainingAll("declares no screenRequest", "hasScreenRequest");
                Assertions.assertThatIllegalStateException()
                    .isThrownBy(invocation::eibcalen)
                    .withMessageContaining("declares no screenRequest");
                Assertions.assertThat(invocation.declaredForcedOutcomes())
                    .as("a batch case forces nothing, and asking is not an error")
                    .isEmpty();
                return UnitOutcome.ofReturnCode(0);
            });
        }

        @Test
        @DisplayName("a case run through the wrong adapter is refused before the unit is reached")
        void aWrongAdapterIsRefusedBeforeExecution() {
            boolean[] reached = {false};

            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ParityHarness.usAscii().run(batchCase(), UnitKind.SERVICE,
                    invocation -> {
                        reached[0] = true;
                        return UnitOutcome.ofReturnCode(0);
                    }))
                .withMessageContainingAll(PROGRAM + '/' + CASE_ID, "BATCH_JOB", "SERVICE",
                    "only a BATCH_JOB may carry job parameters");

            Assertions.assertThat(reached[0])
                .as("the unit must not run at all: a mismatch is a case-authoring defect, and "
                    + "whatever the wrong adapter did first would only obscure it")
                .isFalse();
        }

        @ParameterizedTest(name = "{0} case through a CONTROLLER_POJO adapter")
        @EnumSource(value = UnitKind.class, names = {"BATCH_JOB", "SERVICE", "COMPONENT"})
        @DisplayName("every kind is checked, not just the one the harness happens to see most")
        void everyKindIsChecked(UnitKind declared) {
            ParityCase parityCase = new ParityCase(PROGRAM, CASE_ID, "a case of one kind", declared,
                Map.of(), Map.of(), null, null, List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ParityHarness.usAscii().run(parityCase, UnitKind.CONTROLLER_POJO,
                    invocation -> UnitOutcome.ofReturnCode(0)))
                .withMessageContainingAll(declared.name(), "CONTROLLER_POJO");
        }

        @Test
        @DisplayName("an observation recorded after an early build is never dropped")
        void anObservationAfterAnEarlyBuildIsNeverDropped() {
            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB,
                    invocation -> {
                        invocation.recorder().wrote("TRANSACT", LAYOUT, ROW);
                        UnitOutcome early = invocation.recorder().build();
                        invocation.recorder().wrote("TRANSACT", LAYOUT, ROW);
                        return early;
                    }))
                .withMessageContainingAll("before 1 further observation(s) were recorded",
                    "recorder.build() as the last thing the unit does");
        }

        @Test
        @DisplayName("building last, or returning null, keeps everything - both remain legitimate")
        void buildingLastKeepsEverything() {
            var built = ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                invocation.recorder().wrote("TRANSACT", LAYOUT, ROW);
                invocation.recorder().wrote("TRANSACT", LAYOUT, ROW);
                return invocation.recorder().build();
            });
            Assertions.assertThat(built.differFingerprint().writes().get("TRANSACT").rowCount())
                .isEqualTo(2);

            var returnedNull = ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB,
                invocation -> {
                    invocation.recorder().wrote("TRANSACT", LAYOUT, ROW);
                    invocation.recorder().build();
                    invocation.recorder().display("still counted");
                    return null;
                });
            Assertions.assertThat(returnedNull.differFingerprint().messages())
                .as("returning null means the harness builds, so a mid-run build cannot go stale")
                .hasSize(1);
        }

        @Test
        @DisplayName("every kind of mutation dates the build, not just a write")
        void everyMutationDatesTheBuild() {
            UnitOutcome.Builder recorder = UnitOutcome.builder(
                ParityHarness.usAscii().codec());
            UnitOutcome first = recorder.build();

            Assertions.assertThat(recorder.isCurrentBuild(first)).isTrue();
            recorder.display("a diagnostic line");
            Assertions.assertThat(recorder.isCurrentBuild(first))
                .as("an emitted line is an observation like any other")
                .isFalse();
            Assertions.assertThat(recorder.isSupersededBuild(first)).isTrue();
            Assertions.assertThat(recorder.lastBuilt())
                .as("a superseded build is not handed back as though it were current")
                .isNull();
            Assertions.assertThat(recorder.observationsSinceBuild()).isEqualTo(1);

            UnitOutcome second = recorder.build();
            Assertions.assertThat(recorder.isCurrentBuild(second)).isTrue();
            recorder.returnCode(8);
            Assertions.assertThat(recorder.isCurrentBuild(second))
                .as("so is a RETURN-CODE")
                .isFalse();
        }

        @Test
        @DisplayName("a clock that ticks is refused however it was derived")
        void aTickingClockIsRefused() {
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> harnessWithClock(
                    Clock.offset(Clock.systemUTC(), Duration.ofHours(1))))
                .withMessageContainingAll("not fixed", "fixedClockAt");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> harnessWithClock(
                    Clock.tick(Clock.systemUTC(), Duration.ofSeconds(1))))
                .withMessageContaining("not fixed");
            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> harnessWithClock(Clock.systemUTC()))
                .withMessageContaining("A system clock was supplied");
        }

        @Test
        @DisplayName("a fixed clock is accepted and reaches the unit at exactly its instant")
        void aFixedClockReachesTheUnit() {
            LocalDateTime pinned = LocalDateTime.of(2022, 7, 18, 9, 30, 15);

            harnessWithClock(ParityHarness.fixedClockAt(pinned))
                .run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                    Assertions.assertThat(invocation.now()).isEqualTo(pinned);
                    Assertions.assertThat(invocation.clock().instant())
                        .as("and reading it twice gives the same answer, which is the whole point")
                        .isEqualTo(invocation.clock().instant());
                    return UnitOutcome.ofReturnCode(0);
                });
        }

        @Test
        @DisplayName("a case declaring a code page this harness does not use is refused")
        void aMismatchedCharsetIsRefused() {
            ParityCase ebcdic = new ParityCase(PROGRAM, CASE_ID, "an EBCDIC case",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(0, "DFHENTER", null, "IBM037", Map.of(), Map.of(), Map.of()),
                new ExpectedResponse("COADM01C", null, null, Map.of(), List.of(), null,
                    Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ParityHarness.usAscii().run(ebcdic, UnitKind.CONTROLLER_POJO,
                    invocation -> UnitOutcome.ofReturnCode(0)))
                .withMessageContainingAll("IBM037", "US-ASCII", "ParityHarness.forCharset");
        }

        @Test
        @DisplayName("the same case run under the code page it declares is honoured")
        void theDeclaredCharsetIsHonoured() {
            ParityCase ebcdic = new ParityCase(PROGRAM, CASE_ID, "an EBCDIC case",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(0, "DFHENTER", null, "IBM037", Map.of(), Map.of(), Map.of()),
                new ExpectedResponse("COADM01C", null, null, Map.of(), List.of(), null,
                    Termination.XCTL),
                List.of(), List.of(), 0, List.of(), List.of());

            ParityHarness.forCharset(Charset.forName("IBM037")).run(ebcdic,
                UnitKind.CONTROLLER_POJO, invocation -> {
                    Assertions.assertThat(invocation.charset()).isEqualTo(Charset.forName("IBM037"));
                    return UnitOutcome.ofReturnCode(0);
                });
        }

        @Test
        @DisplayName("a US-ASCII declaration on a US-ASCII harness is accepted, as every shipped case is")
        void aMatchingDeclarationIsAccepted() {
            ParityCase online = new ParityCase(PROGRAM, CASE_ID, "an ASCII case",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(), minimalRequest(), minimalResponse(),
                List.of(), List.of(), 0, List.of(), List.of());

            Assertions.assertThatNoException().isThrownBy(() -> ParityHarness.usAscii()
                .run(online, UnitKind.CONTROLLER_POJO,
                    invocation -> UnitOutcome.ofReturnCode(0)));
        }
    }

    @Nested
    @DisplayName("Forced outcomes: a closed set, and a declaration that has to be used")
    class ForcedOutcomes {
        private ParityCase forcing(Map<RepositoryOperation, ForcedOutcome> forced) {
            return new ParityCase(PROGRAM, CASE_ID, "a case forcing an outcome",
                UnitKind.CONTROLLER_POJO, Map.of(), Map.of(),
                new ScreenRequest(300, "DFHENTER", null, "US-ASCII", Map.of(), Map.of(), forced),
                minimalResponse(), List.of(), List.of(), 0, List.of(), List.of());
        }

        private ForcedOutcome notFound() {
            return new ForcedOutcome(FileStatus.Outcome.NOT_FOUND, null, null);
        }

        @Test
        @DisplayName("a forced outcome reaches the call site that asks for it")
        void aForcedOutcomeReachesItsCallSite() {
            ParityHarness.usAscii().run(
                forcing(Map.of(RepositoryOperation.READ_FOR_UPDATE, notFound())),
                UnitKind.CONTROLLER_POJO, invocation -> {
                    Assertions.assertThat(
                            invocation.hasForcedOutcome(RepositoryOperation.READ_FOR_UPDATE))
                        .isTrue();
                    Assertions.assertThat(invocation
                            .forcedOutcome(RepositoryOperation.READ_FOR_UPDATE).outcome())
                        .isEqualTo(FileStatus.Outcome.NOT_FOUND);
                    return UnitOutcome.ofReturnCode(0);
                });
        }

        @Test
        @DisplayName("a declaration nothing asks for fails the run rather than forcing nothing")
        void anUnconsumedDeclarationFailsTheRun() {
            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(
                    forcing(Map.of(RepositoryOperation.REWRITE, notFound())),
                    UnitKind.CONTROLLER_POJO,
                    invocation -> UnitOutcome.ofReturnCode(0)))
                .withMessageContainingAll(PROGRAM + '/' + CASE_ID, "rewrite",
                    "nothing asked for", "Invocation.forcedOutcome");
        }

        @Test
        @DisplayName("asking whether one is declared is not asking for it")
        void askingIsNotConsuming() {
            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(
                    forcing(Map.of(RepositoryOperation.REWRITE, notFound())),
                    UnitKind.CONTROLLER_POJO, invocation -> {
                        Assertions.assertThat(
                                invocation.hasForcedOutcome(RepositoryOperation.REWRITE))
                            .isTrue();
                        Assertions.assertThat(invocation.declaredForcedOutcomes())
                            .containsExactly(RepositoryOperation.REWRITE);
                        return UnitOutcome.ofReturnCode(0);
                    }))
                .as("a unit that looked and did not use it forced nothing, exactly as one that "
                    + "never looked")
                .withMessageContaining("rewrite");
        }

        @Test
        @DisplayName("every declaration must be used, not just one of them")
        void everyDeclarationMustBeUsed() {
            Map<RepositoryOperation, ForcedOutcome> two = new LinkedHashMap<>();
            two.put(RepositoryOperation.READ_FOR_UPDATE, notFound());
            two.put(RepositoryOperation.REWRITE, notFound());

            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(forcing(two),
                    UnitKind.CONTROLLER_POJO, invocation -> {
                        invocation.forcedOutcome(RepositoryOperation.READ_FOR_UPDATE);
                        return UnitOutcome.ofReturnCode(0);
                    }))
                .withMessageContaining("[rewrite]")
                .satisfies(failure -> Assertions.assertThat(failure.getMessage())
                    .as("the one that WAS used is not reported")
                    .doesNotContain("readForUpdate"));
        }

        @Test
        @DisplayName("an unconsumed declaration fails on the abend path too")
        void anUnconsumedDeclarationFailsAfterAnAbend() {
            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(
                    forcing(Map.of(RepositoryOperation.REWRITE, notFound())),
                    UnitKind.CONTROLLER_POJO, invocation -> {
                        throw AbendException.standard(PROGRAM, 12,
                            "abending before the rewrite");
                    }))
                .withMessageContaining("rewrite");
        }

        @Test
        @DisplayName("asking for an outcome the case does not force is refused, naming what it forces")
        void askingForAnUndeclaredOutcomeIsRefused() {
            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(
                    forcing(Map.of(RepositoryOperation.REWRITE, notFound())),
                    UnitKind.CONTROLLER_POJO, invocation -> {
                        invocation.forcedOutcome(RepositoryOperation.DELETE);
                        return UnitOutcome.ofReturnCode(0);
                    }))
                .withMessageContainingAll("forces no outcome for delete", "[rewrite]")
                .withCauseInstanceOf(ParityCase.Redaction.SanitisedCause.class);
        }

        @Test
        @DisplayName("a case forcing nothing runs cleanly, which is every ordinary case")
        void aCaseForcingNothingRunsCleanly() {
            Assertions.assertThatNoException().isThrownBy(() -> ParityHarness.usAscii().run(
                forcing(Map.of()), UnitKind.CONTROLLER_POJO,
                invocation -> UnitOutcome.ofReturnCode(0)));
        }
    }

    @Nested
    @DisplayName("A dataset that exists and holds no row is a seed, not an omission")
    class DeclaredEmptyInputs {
        private static final String ACCTFILE = "ACCTFILE";

        @Test
        @DisplayName("a declared-empty input reaches the unit as an existing dataset with no rows")
        void aDeclaredEmptyInputIsSeeded() {
            ParityHarness.usAscii().run(
                seedingCase(Map.of(ACCTFILE, DatasetInput.ofEmpty(300, "CVACT01Y"))), UnitKind.BATCH_JOB,
                invocation -> {
                    Assertions.assertThat(invocation.hasDataset(ACCTFILE))
                        .as("the dataset exists: OPEN must succeed")
                        .isTrue();
                    Assertions.assertThat(invocation.dataset(ACCTFILE).isEmpty())
                        .as("and holds nothing: the first READ must meet end-of-file")
                        .isTrue();
                    Assertions.assertThat(invocation.dataset(ACCTFILE).rowCount()).isZero();
                    Assertions.assertThat(invocation.dataset(ACCTFILE).rows()).isEmpty();
                    return UnitOutcome.ofReturnCode(0);
                });
        }

        @Test
        @DisplayName("an omitted dataset is absent, which is not the same observation")
        void anOmittedDatasetIsAbsent() {
            ParityHarness.usAscii().run(seedingCase(Map.of()), UnitKind.BATCH_JOB, invocation -> {
                Assertions.assertThat(invocation.hasDataset(ACCTFILE))
                    .as("a dataset the case never named is not an empty dataset - a unit that "
                        + "cannot tell the two apart cannot be tested on either")
                    .isFalse();
                Assertions.assertThat(invocation.datasets()).isEmpty();
                return UnitOutcome.ofReturnCode(0);
            });
        }

        @Test
        @DisplayName("the declared width is the seeded width, since no row is there to measure")
        void theDeclaredWidthIsTheSeededWidth() {
            ParityHarness.usAscii().run(
                seedingCase(Map.of("DALYREJS", DatasetInput.ofEmpty(430, "CVTRA06Y"))), UnitKind.BATCH_JOB,
                invocation -> {
                    Assertions.assertThat(invocation.dataset("DALYREJS").recordLength())
                        .isEqualTo(430);
                    Assertions.assertThat(invocation.dataset("DALYREJS").charset())
                        .isEqualTo(StandardCharsets.US_ASCII);
                    return UnitOutcome.ofReturnCode(0);
                });
        }

        @Test
        @DisplayName("an empty seed sits beside a populated one, so a mixed case is expressible")
        void anEmptySeedSitsBesideAPopulatedOne() {
            ParityHarness.usAscii().run(
                seedingCase(Map.of(
                    "DALYTRAN", DatasetInput.ofRows(List.of("a row of the daily file")),
                    "DALYREJS", DatasetInput.ofEmpty(430, "CVTRA06Y"))), UnitKind.BATCH_JOB,
                invocation -> {
                    Assertions.assertThat(invocation.dataset("DALYTRAN").rowCount()).isOne();
                    Assertions.assertThat(invocation.dataset("DALYREJS").rowCount()).isZero();
                    return UnitOutcome.ofReturnCode(0);
                });
        }
    }

    @Nested
    @DisplayName("Diagnostic safety: a failure is quoted through the redaction policy, never raw")
    class DiagnosticSafety {
        @Test
        @DisplayName("a non-abend failure's message is sanitised before it reaches the assertion text")
        void aNonAbendFailureIsSanitised() {
            String leakyMessage = "could not rewrite row 'ADMIN001Margaret            GOLD"
                + "                PASSWORDA' - password=" + SECRET;

            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                    throw new IllegalStateException(leakyMessage);
                }))
                .withMessageContaining(PROGRAM + '/' + CASE_ID)
                .withMessageContaining(IllegalStateException.class.getName())
                .withMessageNotContaining("PASSWORD")
                .withMessageNotContaining(SECRET)
                .withCauseInstanceOf(ParityCase.Redaction.SanitisedCause.class);
        }

        @Test
        @DisplayName("the chained cause is a surrogate: the raw text survives in neither rendering")
        void theChainedCauseCarriesNoRawText() {
            String leaky = "row 'USER0001LAWRENCE            THOMAS              PASSWORDU' rejected"
                + " - secret=" + SECRET;
            IllegalStateException raised = new IllegalStateException(leaky);

            Throwable thrown = Assertions.catchThrowable(
                () -> ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                    throw raised;
                }));

            Assertions.assertThat(thrown).isInstanceOf(IllegalStateException.class);
            Assertions.assertThat(thrown.getCause())
                .as("the cause is a surrogate, never the throwable itself")
                .isInstanceOf(ParityCase.Redaction.SanitisedCause.class)
                .isNotSameAs(raised);
            Assertions.assertThat(thrown.getCause().getCause())
                .as("and the surrogate chains nothing further, so there is no deeper rendering for "
                    + "the raw message to reappear in")
                .isNull();
            Assertions.assertThat(thrown.getCause().getSuppressed())
                .as("nor is the original suppressed onto it, which the runner would also print")
                .isEmpty();

            String rendered = renderLikeARunner(thrown);
            Assertions.assertThat(rendered)
                .as("the fully rendered stack output, which is what reaches a build log and a CI "
                    + "artefact. Rendered:%n%s", rendered)
                .doesNotContain(SECRET)
                .doesNotContain("PASSWORD");
            Assertions.assertThat(countOccurrences(rendered, "secret=" + ParityCase.Redaction.MASK))
                .as("the scrubbed form appears in both renderings, which is how it is known that the "
                    + "second rendering exists and was sanitised too rather than merely absent")
                .isEqualTo(2);

            Assertions.assertThat(rendered)
                .as("names inside a short quoted row are bounded, not masked - the documented "
                    + "third rule - so they are expected here and are not what this test judges")
                .contains("LAWRENCE");
        }

        private static int countOccurrences(String haystack, String needle) {
            int count = 0;
            for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
                count++;
            }
            return count;
        }

        @Test
        @DisplayName("the surrogate keeps the type and the throw site, so nothing actionable is lost")
        void theSurrogateKeepsTheTypeAndTheThrowSite() {
            IllegalArgumentException raised = new IllegalArgumentException("width 57 of 80");

            Throwable thrown = Assertions.catchThrowable(
                () -> ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                    throw raised;
                }));

            Assertions.assertThat(thrown)
                .as("the type is the part a reader acts on and carries no data, so it is named in "
                    + "full; the message is bounded and scrubbed but not discarded")
                .hasMessageContaining(IllegalArgumentException.class.getName())
                .hasMessageContaining("width 57 of 80");

            ParityCase.Redaction.SanitisedCause surrogate =
                (ParityCase.Redaction.SanitisedCause) thrown.getCause();
            Assertions.assertThat(surrogate.originalType())
                .as("the original type is retained as metadata, because a class name carries no data "
                    + "and is what a reader dispatches on")
                .isEqualTo(IllegalArgumentException.class.getName());
            Assertions.assertThat(surrogate.getMessage())
                .as("and it names that type in its own message, so the Caused by: line reads like the "
                    + "one it replaces")
                .contains(IllegalArgumentException.class.getName())
                .contains("width 57 of 80");
            Assertions.assertThat(surrogate.getStackTrace())
                .as("the stack frames are copied from the original, frame for frame: a frame is a "
                    + "class name, a method name and a line number, so it locates the throw site "
                    + "exactly and carries no record content")
                .isNotEmpty()
                .containsExactly(raised.getStackTrace());
        }

        private static String renderLikeARunner(Throwable thrown) {
            StringBuilder rendered = new StringBuilder();
            for (Throwable link = thrown; link != null; link = link.getCause()) {
                rendered.append(link.getClass().getName()).append(": ").append(link.getMessage())
                    .append(System.lineSeparator());
                for (StackTraceElement frame : link.getStackTrace()) {
                    rendered.append("\tat ").append(frame).append(System.lineSeparator());
                }
                for (Throwable suppressed : link.getSuppressed()) {
                    rendered.append("\tSuppressed: ").append(suppressed.getClass().getName())
                        .append(": ").append(suppressed.getMessage())
                        .append(System.lineSeparator());
                }
            }
            return rendered.toString();
        }

        @Test
        @DisplayName("a failure with no message at all says so rather than printing null")
        void aFailureWithoutAMessageSaysSo() {
            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                    throw new IllegalStateException();
                }))
                .withMessageContaining(Redaction.NO_MESSAGE);
        }

        @Test
        @DisplayName("a failure quoting a whole record is bounded rather than reproduced")
        void aFailureQuotingAWholeRecordIsBounded() {
            String customerRow = "Margaret".repeat(80);

            Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> ParityHarness.usAscii().run(batchCase(), UnitKind.BATCH_JOB, invocation -> {
                    throw new IllegalStateException(customerRow);
                }))
                .withMessageContaining(Redaction.TRUNCATION_MARKER)
                .withMessageNotContaining(customerRow)
                .satisfies(failure -> Assertions.assertThat(failure.getMessage())
                    .as("no more of the row than the bound allows may survive")
                    .doesNotContain("Margaret".repeat(
                        Redaction.MAX_DIAGNOSTIC_LENGTH / "Margaret".length() + 1)));
        }
    }
}
