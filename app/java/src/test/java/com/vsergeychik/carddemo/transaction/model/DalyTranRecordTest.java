package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.FixedWidthRecord.ZonedSign;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link DalyTranRecord} - the single Java type for {@code app/cpy/CVTRA06Y.cpy},
 * {@code 01 DALYTRAN-RECORD} - against the copybook that declares it and against the behaviour its two
 * consuming COBOL programs actually depend on.
 */
@DisplayName("DalyTranRecord - CVTRA06Y, 350 bytes")
class DalyTranRecordTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final int DECLARED_RECORD_LENGTH = 350;

    private static final int AMOUNT_SIGN_BYTE_INDEX = 142;

    private static final String ROW_1 =
            "0000000000683580"
            + "01"
            + "0001"
            + "POS TERM  "
            + "Purchase at Abshire-Lowe" + " ".repeat(76)
            + "0000005047G"
            + "800000000"
            + "Abshire-Lowe" + " ".repeat(38)
            + "North Enoshaven" + " ".repeat(35)
            + "72112     "
            + "4859452612877065"
            + "2022-06-10 19:27:53.000000"
            + " ".repeat(26)
            + " ".repeat(20);

    private static final String ROW_2 =
            "0000000001774260"
            + "03"
            + "0001"
            + "OPERATOR  "
            + "Return item at Nitzsche, Nicolas and Lowe"
            + " ".repeat(59)
            + "0000009190}"
            + "800000000"
            + "Nitzsche, Nicolas and Lowe"
            + " ".repeat(24)
            + "Fidelshire" + " ".repeat(40)
            + "53378     "
            + "0927987108636232"
            + "2022-06-10 19:27:53.000000"
            + " ".repeat(26)
            + " ".repeat(20);

    private static final String ROW_WITH_ZERO_FILLER =
            "0000000000683580"
            + "01"
            + "0001"
            + "POS TERM  "
            + "Purchase at Abshire-Lowe" + " ".repeat(76)
            + "0000005047G"
            + "800000000"
            + "Abshire-Lowe" + " ".repeat(38)
            + "North Enoshaven" + " ".repeat(35)
            + "72112     "
            + "4859452612877065"
            + "2022-06-10 19:27:53.000000"
            + " ".repeat(26)
            + "0".repeat(20);

    private static FieldSpan spanNamed(String name) {
        for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
            if (span.name().equals(name)) {
                return span;
            }
        }
        throw new IllegalArgumentException("CVTRA06Y declares no span named " + name);
    }

    private static List<String> declaredFieldNamesContaining(Class<?> type, String fragment) {
        List<String> matches = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().contains(fragment)) {
                matches.add(field.getName());
            }
        }
        return matches;
    }

    private static List<FieldSpan> declaredSpanConstants() {
        List<FieldSpan> spans = new ArrayList<>();
        for (Field field : DalyTranRecord.class.getDeclaredFields()) {
            if (field.getType() == FieldSpan.class) {
                try {
                    spans.add((FieldSpan) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("DalyTranRecord's span constant " + field.getName()
                            + " should be publicly readable", e);
                }
            }
        }
        return spans;
    }

    @Nested
    @DisplayName("declared geometry - B11, offsets justified by addition")
    class DeclaredGeometry {
        @Test
        @DisplayName("G19: the record is the 350 bytes the copybook's RECLN comment declares")
        void recordLengthIs350() {
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DalyTranRecord.LAYOUT.recordLength()).isEqualTo(DECLARED_RECORD_LENGTH);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("B11: the fourteen PICTURE widths add up to 350, restated as an addition")
        void theCopybookArithmeticAddsUpTo350() {
            assertThat(16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20)
                    .as("the copybook arithmetic, written out")
                    .isEqualTo(DECLARED_RECORD_LENGTH);

            assertThat(DalyTranRecord.DALYTRAN_ID_LENGTH
                    + DalyTranRecord.DALYTRAN_TYPE_CD_LENGTH
                    + DalyTranRecord.DALYTRAN_CAT_CD_LENGTH
                    + DalyTranRecord.DALYTRAN_SOURCE_LENGTH
                    + DalyTranRecord.DALYTRAN_DESC_LENGTH
                    + DalyTranRecord.DALYTRAN_AMT_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_ID_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_NAME_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_CITY_LENGTH
                    + DalyTranRecord.DALYTRAN_MERCHANT_ZIP_LENGTH
                    + DalyTranRecord.DALYTRAN_CARD_NUM_LENGTH
                    + DalyTranRecord.DALYTRAN_ORIG_TS_LENGTH
                    + DalyTranRecord.DALYTRAN_PROC_TS_LENGTH
                    + DalyTranRecord.FILLER_LENGTH)
                    .as("the same addition over the declared length constants")
                    .isEqualTo(DECLARED_RECORD_LENGTH);

            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths()).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the fourteen spans are contiguous from byte 0 with no gap and no overlap")
        void spansAreContiguousAndSumTo350() {
            assertThat(DalyTranRecord.LAYOUT.storageSpans()).hasSize(14);
            int next = 0;
            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(span.offset()).as("%s starts where the previous span ends", span.name())
                        .isEqualTo(next);
                next += span.length();
            }
            assertThat(next).isEqualTo(DECLARED_RECORD_LENGTH);
        }

        @ParameterizedTest(name = "{0} at 0-based {1} for {2} bytes")
        @DisplayName("R5: every span sits at its absolute offset, under its copybook name verbatim")
        @CsvSource({
            "DALYTRAN-ID,0,16",
            "DALYTRAN-TYPE-CD,16,2",
            "DALYTRAN-CAT-CD,18,4",
            "DALYTRAN-SOURCE,22,10",
            "DALYTRAN-DESC,32,100",
            "DALYTRAN-AMT,132,11",
            "DALYTRAN-MERCHANT-ID,143,9",
            "DALYTRAN-MERCHANT-NAME,152,50",
            "DALYTRAN-MERCHANT-CITY,202,50",
            "DALYTRAN-MERCHANT-ZIP,252,10",
            "DALYTRAN-CARD-NUM,262,16",
            "DALYTRAN-ORIG-TS,278,26",
            "DALYTRAN-PROC-TS,304,26",
            "FILLER,330,20"})
        void everySpanSitsWhereTheCopybookPutsIt(String name, int offset, int length) {
            FieldSpan span = spanNamed(name);
            assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
            assertThat(span.length()).as("%s length", name).isEqualTo(length);
            assertThat(span.endOffsetExclusive()).as("%s end", name).isEqualTo(offset + length);

            assertThat(DalyTranRecord.decode(ROW_1, ASCII).rawSpan(span)).hasSize(length);
        }

        @ParameterizedTest(name = "{0} is referable by name")
        @DisplayName("the thirteen named items are referable through the layout")
        @CsvSource({
            "DALYTRAN-ID", "DALYTRAN-TYPE-CD", "DALYTRAN-CAT-CD", "DALYTRAN-SOURCE",
            "DALYTRAN-DESC", "DALYTRAN-AMT", "DALYTRAN-MERCHANT-ID", "DALYTRAN-MERCHANT-NAME",
            "DALYTRAN-MERCHANT-CITY", "DALYTRAN-MERCHANT-ZIP", "DALYTRAN-CARD-NUM",
            "DALYTRAN-ORIG-TS", "DALYTRAN-PROC-TS"})
        void theThirteenNamedItemsAreReferable(String name) {
            assertThat(DalyTranRecord.LAYOUT.hasSpan(name)).isTrue();
            assertThat(DalyTranRecord.LAYOUT.span(name)).isEqualTo(spanNamed(name));
        }

        @Test
        @DisplayName("G21: the trailing span is named FILLER, without the DALYTRAN- prefix")
        void theFillerSpanKeepsTheCopybooksOwnUnprefixedName() {
            assertThat(DalyTranRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(DalyTranRecord.FILLER_OFFSET).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(DalyTranRecord.LAYOUT.hasSpan("DALYTRAN-FILLER")).isFalse();
        }

        @Test
        @DisplayName("G21: FILLER occupies its twenty bytes yet stays unreferable by name, as in COBOL")
        void fillerIsPresentInStorageButNotReferableByName() {
            assertThat(DalyTranRecord.LAYOUT.storageSpans()).contains(DalyTranRecord.FILLER);
            assertThat(DalyTranRecord.FILLER.offset()).isEqualTo(330);
            assertThat(DalyTranRecord.FILLER.length()).isEqualTo(20);

            assertThat(DalyTranRecord.LAYOUT.hasSpan("FILLER")).isFalse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.LAYOUT.span("FILLER"));
        }

        @Test
        @DisplayName("DALYTRAN-AMT occupies p+s = 11 bytes: no COMP-3 exists in app/cpy at all")
        void amountOccupiesElevenBytes() {
            assertThat(DalyTranRecord.DALYTRAN_AMT_LENGTH).isEqualTo(11);
            assertThat(DalyTranRecord.DALYTRAN_AMT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(DalyTranRecord.DALYTRAN_AMT_SCALE).isEqualTo(2);
            assertThat(DalyTranRecord.DALYTRAN_AMT_INTEGER_DIGITS
                    + DalyTranRecord.DALYTRAN_AMT_SCALE).isEqualTo(11);
            assertThat(DalyTranRecord.DALYTRAN_AMT.length()).isEqualTo(11);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmtImage()).hasSize(11);

            assertThat(DalyTranRecord.DALYTRAN_AMT_OFFSET + DalyTranRecord.DALYTRAN_AMT_LENGTH - 1)
                    .isEqualTo(AMOUNT_SIGN_BYTE_INDEX);
        }

        @Test
        @DisplayName("R3: the scale comes from the PICTURE, through one central policy constant")
        void monetaryScaleIsCentralised() {
            assertThat(DalyTranRecord.DALYTRAN_AMT_SCALE)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("R2: the rounding policy is DOWN, because ROUNDED appears zero times in the source")
        void roundingPolicyIsTruncation() {
            assertThat(CobolDecimal.COBOL_ROUNDING).isSameAs(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("G34: the ORIG-DT slice shares DALYTRAN-ORIG-TS's offset, per CBTRN02C:414")
        void origDateSliceSharesTheTimestampsOffset() {
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_OFFSET)
                    .isEqualTo(DalyTranRecord.DALYTRAN_ORIG_TS_OFFSET)
                    .isEqualTo(278);
            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_LENGTH).isEqualTo(10);

            assertThat(DalyTranRecord.DALYTRAN_ORIG_DT_LENGTH)
                    .isLessThan(DalyTranRecord.DALYTRAN_ORIG_TS_LENGTH);
        }

        @Test
        @DisplayName("G50: the width self-check REJECTS a set of spans that drops the trailing FILLER")
        void theWidthSelfCheckRejectsADroppedFiller() {
            FieldSpan[] withoutFiller = spansExcept(DalyTranRecord.FILLER);
            assertThat(withoutFiller).hasSize(13);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH, withoutFiller))
                    .withMessageContaining("330")
                    .withMessageContaining("20");
        }

        @Test
        @DisplayName("G50: the width self-check REJECTS a span set that reserves a separate sign byte")
        void theWidthSelfCheckRejectsAReservedSignByte() {
            List<FieldSpan> spans = new ArrayList<>(DalyTranRecord.LAYOUT.storageSpans());
            spans.add(FieldSpan.filler(DECLARED_RECORD_LENGTH, 1));
            FieldSpan[] oneByteTooLong = spans.toArray(new FieldSpan[0]);
            assertThat(oneByteTooLong).hasSize(15);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH, oneByteTooLong))
                    .withMessageContaining("351");
        }

        @Test
        @DisplayName("G50: the width self-check REJECTS a gap left by a dropped middle item")
        void theWidthSelfCheckRejectsAGap() {
            FieldSpan[] withoutTypeCd = spansExcept(DalyTranRecord.DALYTRAN_TYPE_CD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(DECLARED_RECORD_LENGTH, withoutTypeCd))
                    .withMessageContaining("DALYTRAN-CAT-CD");
        }

        @Test
        @DisplayName("no key is declared: POSTTRAN.jcl:31 binds DALYTRAN to a sequential PS dataset")
        void noKeyIsDeclaredBecauseTheDatasetIsSequential() {
            assertThat(declaredFieldNamesContaining(DalyTranRecord.class, "KEY"))
                    .as("CVTRA06Y is read sequentially from a PS dataset, so it has no key")
                    .isEmpty();
            assertThat(declaredFieldNamesContaining(TranRecord.class, "KEY"))
                    .as("CVTRA05Y's TRANSACT record is a KSDS and does declare its key width")
                    .isNotEmpty();
        }

        private FieldSpan[] spansExcept(FieldSpan excluded) {
            List<FieldSpan> remaining = new ArrayList<>(DalyTranRecord.LAYOUT.storageSpans());
            remaining.remove(excluded);
            return remaining.toArray(new FieldSpan[0]);
        }
    }

    @Nested
    @DisplayName("R1: a standalone type, deliberately duplicating CVTRA05Y's geometry")
    class StandaloneTypeDecision {
        @Test
        @DisplayName("the type extends nothing: its superclass is Object and it implements no interface")
        void theTypeHasNoSharedBase() {
            assertThat(DalyTranRecord.class.getSuperclass())
                    .as("no shared base class with the CVTRA05Y model")
                    .isEqualTo(Object.class);
            assertThat(DalyTranRecord.class.getInterfaces())
                    .as("no shared interface either - a common interface would reintroduce the "
                            + "name-collapsing risk through the back door")
                    .isEmpty();
        }

        @Test
        @DisplayName("neither type is assignable to the other, in either direction")
        void neitherTypeIsAssignableToTheOther() {
            assertThat(TranRecord.class.isAssignableFrom(DalyTranRecord.class))
                    .as("a DalyTranRecord is not a TranRecord")
                    .isFalse();
            assertThat(DalyTranRecord.class.isAssignableFrom(TranRecord.class))
                    .as("a TranRecord is not a DalyTranRecord")
                    .isFalse();
            assertThat(DalyTranRecord.class).isNotEqualTo(TranRecord.class);
        }

        @Test
        @DisplayName("every declared span name carries the DALYTRAN- prefix, except un-prefixed FILLER")
        void everySpanNameCarriesTheDalytranPrefix() {
            List<FieldSpan> spans = declaredSpanConstants();
            assertThat(spans).hasSize(14);

            int prefixed = 0;
            int filler = 0;
            for (FieldSpan span : spans) {
                assertThat(span.name())
                        .as("%s must not be reported under a CVTRA05Y name", span.name())
                        .doesNotStartWith("TRAN-");
                if ("FILLER".equals(span.name())) {
                    filler++;
                } else {
                    assertThat(span.name()).startsWith("DALYTRAN-");
                    prefixed++;
                }
            }
            assertThat(prefixed).as("thirteen prefixed items").isEqualTo(13);
            assertThat(filler).as("one un-prefixed reserved span").isEqualTo(1);
        }

        @Test
        @DisplayName("the two layouts are byte-compatible yet name-incompatible, item for item")
        void theTwoLayoutsAreByteCompatibleButNameIncompatible() {
            List<FieldSpan> daily = DalyTranRecord.LAYOUT.storageSpans();
            List<FieldSpan> master = TranRecord.LAYOUT.storageSpans();

            assertThat(daily).hasSameSizeAs(master);
            assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(TranRecord.RECORD_LENGTH);
            for (int i = 0; i < daily.size(); i++) {
                FieldSpan d = daily.get(i);
                FieldSpan m = master.get(i);
                assertThat(d.offset()).as("item %d offset", i + 1).isEqualTo(m.offset());
                assertThat(d.length()).as("item %d length", i + 1).isEqualTo(m.length());
                assertThat(d.kind()).as("item %d PICTURE category", i + 1).isEqualTo(m.kind());
            }

            int differing = 0;
            int shared = 0;
            for (int i = 0; i < daily.size(); i++) {
                if (daily.get(i).name().equals(master.get(i).name())) {
                    assertThat(daily.get(i).name()).isEqualTo("FILLER");
                    shared++;
                } else {
                    assertThat(daily.get(i).name()).startsWith("DALYTRAN-");
                    assertThat(master.get(i).name()).startsWith("TRAN-");
                    differing++;
                }
            }
            assertThat(differing).as("thirteen names diverge").isEqualTo(13);
            assertThat(shared).as("only the un-prefixed FILLER is shared").isEqualTo(1);

            assertThat(daily).doesNotContainAnyElementsOf(
                    master.stream().filter(s -> !"FILLER".equals(s.name())).toList());
        }

        @Test
        @DisplayName("the field-by-field rendering labels every value with a DALYTRAN- name")
        void theRenderingLabelsEveryValueWithADalytranName() {
            String rendered = DalyTranRecord.decode(ROW_1, ASCII).toString();

            assertThat(rendered)
                    .as("no value may be reported under a CVTRA05Y name")
                    .doesNotContain(", TRAN-");
            for (FieldSpan span : declaredSpanConstants()) {
                if (!"FILLER".equals(span.name())) {
                    assertThat(rendered).as("%s is labelled", span.name()).contains(span.name());
                }
            }
        }
    }

    @Nested
    @DisplayName("G44: no persistence artefact of any kind")
    class NoPersistenceArtefacts {
        @Test
        @DisplayName("the type carries no annotation at all - not JPA, not Spring, not validation")
        void theTypeCarriesNoAnnotations() {
            assertThat(DalyTranRecord.class.getAnnotations())
                    .as("a plain data type: no @Entity, no @Table, no @Component, no annotation at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("no member carries a jakarta.persistence or javax.persistence annotation")
        void noMemberCarriesAPersistenceAnnotation() {
            List<String> offenders = new ArrayList<>();
            collectPersistenceAnnotations(DalyTranRecord.class.getAnnotations(), "the type", offenders);
            for (Field field : DalyTranRecord.class.getDeclaredFields()) {
                collectPersistenceAnnotations(field.getAnnotations(), field.getName(), offenders);
            }
            for (Method method : DalyTranRecord.class.getDeclaredMethods()) {
                collectPersistenceAnnotations(method.getAnnotations(), method.getName(), offenders);
            }
            assertThat(offenders)
                    .as("JDBC to the existing dataset, with no schema and no ORM")
                    .isEmpty();
        }

        @Test
        @DisplayName("no version column: concurrency is not modelled on the record type")
        void noVersionColumnIsDeclared() {
            assertThat(declaredFieldNamesContaining(DalyTranRecord.class, "VERSION")).isEmpty();
            assertThat(declaredFieldNamesContaining(DalyTranRecord.class, "version")).isEmpty();
        }

        private void collectPersistenceAnnotations(Annotation[] annotations, String owner,
                List<String> offenders) {
            for (Annotation annotation : annotations) {
                String declaring = annotation.annotationType().getName();
                if (declaring.startsWith("jakarta.persistence")
                        || declaring.startsWith("javax.persistence")) {
                    offenders.add(owner + " carries " + declaring);
                }
            }
        }
    }

    @Nested
    @DisplayName("an initialised area - the WORKING-STORAGE equivalent")
    class InitialisedArea {
        @Test
        @DisplayName("G21: a fresh area is 350 bytes with FILLER space-filled at offset 330")
        void aFreshAreaHasSpaceFilledFiller() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(record.filler())
                    .as("FILLER X(20) at 0-based 330 initialises to the charset's space byte")
                    .isEqualTo(" ".repeat(20))
                    .hasSize(20);

            assertThat(record.displayImage().substring(330, 350)).isEqualTo(" ".repeat(20));
            for (int i = 330; i < DECLARED_RECORD_LENGTH; i++) {
                assertThat(record.rawImage()[i]).as("byte %d of FILLER", i).isEqualTo((byte) ' ');
            }
        }

        @Test
        @DisplayName("text spans blank, unsigned numerics zero-filled, the amount a signed zero")
        void initialisesEverySpanAccordingToItsPictureCategory() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            assertThat(record.dalytranId()).isEqualTo(" ".repeat(16));
            assertThat(record.dalytranTypeCd()).isEqualTo("  ");
            assertThat(record.dalytranSource()).isEqualTo(" ".repeat(10));
            assertThat(record.dalytranDesc()).isEqualTo(" ".repeat(100));
            assertThat(record.dalytranMerchantName()).isEqualTo(" ".repeat(50));
            assertThat(record.dalytranMerchantCity()).isEqualTo(" ".repeat(50));
            assertThat(record.dalytranMerchantZip()).isEqualTo(" ".repeat(10));
            assertThat(record.dalytranCardNum()).isEqualTo(" ".repeat(16));
            assertThat(record.dalytranOrigTs()).isEqualTo(" ".repeat(26));
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));

            assertThat(record.dalytranCatCdImage()).isEqualTo("0000");
            assertThat(record.dalytranCatCd()).isZero();
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.dalytranMerchantId()).isZero();
            assertThat(record.dalytranAmt()).isEqualByComparingTo(CobolDecimal.monetaryZero());
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);
            assertThat(record.hasZeroDalytranAmt()).isTrue();

            assertThat(record.charset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("initialises identically under EBCDIC, using that code page's own pad bytes")
        void initialisesUnderEbcdicToo() {
            DalyTranRecord record = new DalyTranRecord(EBCDIC);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.dalytranCatCd()).isZero();
            assertThat(record.charset()).isEqualTo(EBCDIC);

            assertThat(record.rawImage()[330]).isEqualTo((byte) 0x40);
        }

        @Test
        @DisplayName("a charset is never assumed - there is no platform default on any entry point")
        void aCharsetIsAlwaysRequired() {
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1.getBytes(ASCII), null));
            assertThatNullPointerException().isThrownBy(() -> DalyTranRecord.decode(ROW_1, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1, ASCII).encode(null));
        }
    }

    @Nested
    @DisplayName("decoding a stored row - untrimmed, at absolute offsets")
    class FixtureDecode {
        @Test
        @DisplayName("every embedded literal is exactly 350 characters, as the fixture rows are")
        void embeddedRowsAreWellFormed() {
            assertThat(ROW_1).as("fixture row 1").hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ROW_2).as("fixture row 2").hasSize(DECLARED_RECORD_LENGTH);
            assertThat(ROW_WITH_ZERO_FILLER).as("synthetic zero-FILLER row")
                    .hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("fixture row 1 decodes all fourteen items through the copybook offsets")
        void rowOneDecodesFieldForField() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
            assertThat(record.dalytranTypeCd()).isEqualTo("01");
            assertThat(record.dalytranCatCd()).isEqualTo(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ");
            assertThat(record.dalytranDesc())
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(record.dalytranMerchantId()).isEqualTo(800000000);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.dalytranMerchantName()).isEqualTo("Abshire-Lowe" + " ".repeat(38));
            assertThat(record.dalytranMerchantCity()).isEqualTo("North Enoshaven" + " ".repeat(35));
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ");
            assertThat(record.dalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(record.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a decode is untrimmed: trailing spaces are data, not formatting")
        void aDecodeIsUntrimmed() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ").hasSize(10)
                    .isNotEqualTo("POS TERM");
            assertThat(record.dalytranDesc()).hasSize(100).endsWith(" ");
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ").hasSize(10);

            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(record.rawSpan(span)).as(span.name()).hasSize(span.length());
            }
        }

        @Test
        @DisplayName("DALYTRAN-TYPE-CD is a String and DALYTRAN-CAT-CD is an int, per their PICTUREs")
        void theTypeCodeIsTextAndTheCategoryCodeIsNumeric() throws Exception {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(DalyTranRecord.class.getMethod("dalytranTypeCd").getReturnType())
                    .isEqualTo(String.class);
            assertThat(record.dalytranTypeCd()).isEqualTo("01");

            assertThat(DalyTranRecord.class.getMethod("dalytranCatCd").getReturnType())
                    .isEqualTo(int.class);
            assertThat(record.dalytranCatCd()).isEqualTo(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
        }

        @Test
        @DisplayName("R4: the amount is a BigDecimal, never a binary floating-point type")
        void theAmountIsNeverABinaryFloatingPointType() throws Exception {
            assertThat(DalyTranRecord.class.getMethod("dalytranAmt").getReturnType())
                    .isEqualTo(BigDecimal.class);
            assertThat(DalyTranRecord.class.getMethod("moveDalytranAmt", BigDecimal.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("R3/G23: every decoded amount carries scale exactly 2, from the PICTURE")
        void everyDecodedAmountCarriesScaleTwo() {
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(new DalyTranRecord(ASCII).dalytranAmt().scale()).isEqualTo(2);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranAmt())
                    .isEqualTo(new BigDecimal("504.77"));
        }

        @ParameterizedTest(name = "fixture line {0}: {1} decodes to {2}")
        @DisplayName("all SIX negative-zero-digit rows in the fixture decode to their real amounts")
        @CsvSource({
            "2,0000009190},-919.00",
            "55,0000002430},-243.00",
            "87,0000007630},-763.00",
            "150,0000009070},-907.00",
            "165,0000003720},-372.00",
            "210,0000004350},-435.00"})
        void everyNegativeZeroDigitFixtureRowDecodesToItsRealAmount(int line, String image,
                String expected) {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage(image);

            assertThat(record.dalytranAmt())
                    .as("fixture line %d", line)
                    .isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.dalytranAmt().scale()).as("line %d scale", line).isEqualTo(2);
            assertThat(record.dalytranAmt().signum()).as("line %d sign", line).isNegative();
            assertThat(record.hasZeroDalytranAmt()).as("line %d is not zero", line).isFalse();
            assertThat(record.dalytranAmtImage()).as("line %d image is preserved", line)
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("fixture row 2 decodes its '}' amount as negative and reads its other fields")
        void rowTwoDecodesANegativeAmount() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);

            assertThat(record.dalytranId()).isEqualTo("0000000001774260");
            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(record.dalytranAmt().signum()).isNegative();
            assertThat(record.hasZeroDalytranAmt()).isFalse();
            assertThat(record.dalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(record.dalytranTypeCd()).isEqualTo("03");
            assertThat(record.dalytranCardNum()).isEqualTo("0927987108636232");
            assertThat(record.dalytranMerchantCity()).isEqualTo("Fidelshire" + " ".repeat(40));
        }

        @Test
        @DisplayName("DALYTRAN-PROC-TS is blank on the daily file, as it is in all 300 fixture rows")
        void theProcessingTimestampIsBlankOnTheDailyFile() {
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranProcTs()).isEqualTo(" ".repeat(26));
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranProcTs()).isEqualTo(" ".repeat(26));

            DalyTranRecord synthetic = DalyTranRecord.decode(ROW_1, ASCII);
            synthetic.moveDalytranProcTs("2022-07-19 23:16:01.000000");
            assertThat(synthetic.dalytranProcTs()).isEqualTo("2022-07-19 23:16:01.000000").hasSize(26);
            assertThat(synthetic.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
            assertThat(synthetic.filler()).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the fixture's own coverage is narrow, and that is recorded rather than assumed")
        void theFixtureCoverageIsNarrowerThanTheFieldsAllow() {
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranTypeCd()).isEqualTo("01");
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranTypeCd()).isEqualTo("03");
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).dalytranCatCdImage()).isEqualTo("0001");
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).dalytranCatCdImage()).isEqualTo("0001");

            DalyTranRecord widest = new DalyTranRecord(ASCII);
            widest.writeDalytranAmtImage("0000009997G");
            assertThat(widest.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999.77"));

            DalyTranRecord full = new DalyTranRecord(ASCII);
            full.moveDalytranAmt(new BigDecimal("999999999.99"));
            assertThat(full.dalytranAmtImage()).isEqualTo("9999999999I").hasSize(11);
            assertThat(full.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999999999.99"));
            assertThat(full.dalytranAmt().scale()).isEqualTo(2);

            DalyTranRecord mostNegative = new DalyTranRecord(ASCII);
            mostNegative.moveDalytranAmt(new BigDecimal("-999999999.99"));
            assertThat(mostNegative.dalytranAmtImage()).isEqualTo("9999999999R").hasSize(11);
            assertThat(mostNegative.dalytranAmt())
                    .isEqualByComparingTo(new BigDecimal("-999999999.99"));

        }

        @Test
        @DisplayName("a signed amount too wide for S9(09)V99 truncates on the LEFT, silently")
        void anOverWideAmountTruncatesHighOrderDigitsSilently() {
            DalyTranRecord justFits = new DalyTranRecord(ASCII);
            justFits.moveDalytranAmt(new BigDecimal("999999999.99"));
            assertThat(justFits.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999999999.99"));

            DalyTranRecord oneDigitTooWide = new DalyTranRecord(ASCII);
            oneDigitTooWide.moveDalytranAmt(new BigDecimal("1000000000.00"));
            assertThat(oneDigitTooWide.dalytranAmt())
                    .as("the leading 1 is discarded, leaving nine zero digits")
                    .isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(oneDigitTooWide.dalytranAmtImage()).isEqualTo("0000000000{");

            DalyTranRecord negativeTooWide = new DalyTranRecord(ASCII);
            negativeTooWide.moveDalytranAmt(new BigDecimal("-1000000000.00"));
            assertThat(negativeTooWide.dalytranAmt()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(negativeTooWide.dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(negativeTooWide.hasZeroDalytranAmt()).isTrue();

            DalyTranRecord farTooWide = new DalyTranRecord(ASCII);
            farTooWide.moveDalytranAmt(new BigDecimal("9999999999.99"));
            assertThat(farTooWide.dalytranAmt()).isEqualByComparingTo(new BigDecimal("999999999.99"));
            assertThat(farTooWide.dalytranAmtImage()).isEqualTo("9999999999I");

            assertThat(farTooWide.dalytranAmtImage()).hasSize(11);
            assertThat(farTooWide.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a row of the wrong width is rejected rather than decoded at shifted offsets")
        void aRowOfTheWrongWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(new byte[349], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(new byte[351], ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1.substring(1), ASCII));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DalyTranRecord.decode(ROW_1 + " ", ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode((byte[]) null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> DalyTranRecord.decode((String) null, ASCII));
        }
    }

    @Nested
    @DisplayName("G34: DALYTRAN-ORIG-TS and its (1:10) slice, two accessors over one span")
    class OrigDateSlice {
        @Test
        @DisplayName("the slice is the first ten characters of the timestamp - the date")
        void theSliceIsTheFirstTenCharacters() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000").hasSize(26);
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10").hasSize(10);

            assertThat(record.dalytranOrigDt()).isEqualTo(record.dalytranOrigTs().substring(0, 10));
            assertThat(record.dalytranOrigDt())
                    .isEqualTo(record.displayImage().substring(278, 288));
        }

        @Test
        @DisplayName("the slice is a VIEW over the span: mutate the timestamp and the slice follows")
        void theSliceTracksAMutationOfTheSpan() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10");

            record.moveDalytranOrigTs("1999-12-31 00:00:00.000000");
            assertThat(record.dalytranOrigDt())
                    .as("the slice reads the span, so it moved with it")
                    .isEqualTo("1999-12-31");
            assertThat(record.dalytranOrigTs()).isEqualTo("1999-12-31 00:00:00.000000");

            record.moveDalytranOrigTs("2022-06-10 19:27:53.000000");
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10");

            record.moveDalytranProcTs("2022-07-19 23:16:01.000000");
            assertThat(record.dalytranOrigDt()).isEqualTo("2022-06-10");
            assertThat(record.dalytranOrigTs()).isEqualTo("2022-06-10 19:27:53.000000");
        }

        @Test
        @DisplayName("the slice is character data, never parsed - a blank date yields blanks")
        void theSliceIsNeverParsed() {
            DalyTranRecord blank = new DalyTranRecord(ASCII);
            assertThat(blank.dalytranOrigDt()).isEqualTo(" ".repeat(10)).hasSize(10);

            DalyTranRecord partial = new DalyTranRecord(ASCII);
            partial.moveDalytranOrigTs("2022-06");
            assertThat(partial.dalytranOrigDt()).isEqualTo("2022-06   ").hasSize(10);

            assertThat("2022-06-10").isGreaterThan("2022-06-09").isLessThan("2022-06-11");
        }
    }

    @Nested
    @DisplayName("G34: the raw 350-byte image, a view over the whole record")
    class VerbatimImageAccess {
        @Test
        @DisplayName("rawImage is the stored bytes verbatim, for a row whose sign byte is '}'")
        void rawImageIsByteVerbatimForANegativeZeroDigitRow() {
            byte[] stored = ROW_2.getBytes(ASCII);
            DalyTranRecord record = DalyTranRecord.decode(stored, ASCII);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH).isEqualTo(stored);
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.displayImage()).hasSize(DECLARED_RECORD_LENGTH).isEqualTo(ROW_2);
            assertThat(record.displayImage().charAt(AMOUNT_SIGN_BYTE_INDEX)).isEqualTo('}');
        }

        @Test
        @DisplayName("a decode then re-encode is byte-identical, for row 1 and for a '}' row alike")
        void decodeThenReEncodeIsByteIdentical() {
            byte[] positiveRow = ROW_1.getBytes(ASCII);
            assertThat(DalyTranRecord.decode(positiveRow, ASCII).encode(ASCII))
                    .as("row 1, positive 'G' overpunch")
                    .isEqualTo(positiveRow);

            byte[] negativeRow = ROW_2.getBytes(ASCII);
            assertThat(DalyTranRecord.decode(negativeRow, ASCII).encode(ASCII))
                    .as("row 2, negative '}' overpunch")
                    .isEqualTo(negativeRow);

            assertThat(DalyTranRecord.decode(ROW_1, ASCII).displayImage()).isEqualTo(ROW_1);
            assertThat(DalyTranRecord.decode(ROW_2, ASCII).displayImage()).isEqualTo(ROW_2);
        }

        @Test
        @DisplayName("rawImage hands out a copy, so a caller cannot reach the backing area")
        void rawImageIsDefensivelyCopied() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            byte[] handedOut = record.rawImage();
            handedOut[0] = (byte) '9';

            assertThat(record.rawImage()[0]).isEqualTo((byte) '0');
            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
        }

        @Test
        @DisplayName("copy duplicates the bytes rather than rebuilding from decoded values")
        void copyIsByteVerbatimAndIndependent() {
            DalyTranRecord original = DalyTranRecord.decode(ROW_2, ASCII);
            DalyTranRecord duplicate = original.copy();

            assertThat(duplicate.rawImage()).isEqualTo(original.rawImage());
            assertThat(duplicate).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(duplicate.charset()).isEqualTo(original.charset());

            duplicate.moveDalytranSource("CHANGED   ");
            assertThat(original.dalytranSource()).isEqualTo("OPERATOR  ");
            assertThat(duplicate.dalytranSource()).isEqualTo("CHANGED   ");
            assertThat(duplicate).isNotEqualTo(original);
        }

        @Test
        @DisplayName("encoding to the record's own code page returns the stored bytes unchanged")
        void encodingToTheSameCodePageIsTheStoredImage() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);
            assertThat(record.encode(ASCII)).isEqualTo(record.rawImage());
        }

        @Test
        @DisplayName("transcoding to another code page changes the byte but keeps the character")
        void encodingToAnotherCodePagePreservesTheSignCharacter() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_2, ASCII);

            byte[] transcoded = record.encode(EBCDIC);
            assertThat(transcoded).hasSize(DECLARED_RECORD_LENGTH).isNotEqualTo(record.rawImage());

            DalyTranRecord roundTripped = DalyTranRecord.decode(transcoded, EBCDIC);
            assertThat(roundTripped.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(roundTripped.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(roundTripped.dalytranAmt().scale()).isEqualTo(2);
            assertThat(roundTripped.dalytranCardNum()).isEqualTo("0927987108636232");
            assertThat(roundTripped.displayImage()).isEqualTo(ROW_2);
            assertThat(roundTripped.charset()).isEqualTo(EBCDIC);
        }

        @Test
        @DisplayName("a raw span is readable for every declared field, at its full stored width")
        void rawSpanReadsEveryDeclaredField() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            for (FieldSpan span : DalyTranRecord.LAYOUT.storageSpans()) {
                assertThat(record.rawSpan(span)).as(span.name()).hasSize(span.length());
                assertThat(record.rawSpanBytes(span)).as(span.name()).hasSize(span.length());
                assertThat(record.rawSpan(span)).as("%s matches the image slice", span.name())
                        .isEqualTo(record.displayImage()
                                .substring(span.offset(), span.endOffsetExclusive()));
            }

            assertThat(record.rawSpan(DalyTranRecord.DALYTRAN_AMT)).isEqualTo("0000005047G");
            assertThat(record.rawSpan(DalyTranRecord.FILLER)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("a span descriptor from the other copybook is refused, not silently honoured")
        void aForeignSpanIsRejected() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            FieldSpan foreign = TranRecord.TRAN_ID;
            assertThat(foreign.offset()).isEqualTo(DalyTranRecord.DALYTRAN_ID.offset());
            assertThat(foreign.length()).isEqualTo(DalyTranRecord.DALYTRAN_ID.length());
            assertThatIllegalArgumentException().isThrownBy(() -> record.rawSpan(foreign));
            assertThatIllegalArgumentException().isThrownBy(() -> record.rawSpanBytes(foreign));

            assertThatNullPointerException().isThrownBy(() -> record.rawSpan(null));
            assertThatNullPointerException().isThrownBy(() -> record.rawSpanBytes(null));
        }
    }

    @Nested
    @DisplayName("R2: the zoned sign overpunch, both halves of the table")
    class ZonedOverpunch {
        @ParameterizedTest(name = "{1} -> {3}")
        @DisplayName("all twenty overpunch characters decode with the right sign and magnitude")
        @CsvSource({
            "0,{,0000001230{,123.00",
            "1,A,0000001231A,123.11",
            "2,B,0000001232B,123.22",
            "3,C,0000001233C,123.33",
            "4,D,0000001234D,123.44",
            "5,E,0000001235E,123.55",
            "6,F,0000001236F,123.66",
            "7,G,0000001237G,123.77",
            "8,H,0000001238H,123.88",
            "9,I,0000001239I,123.99",
            "0,},0000001230},-123.00",
            "1,J,0000001231J,-123.11",
            "2,K,0000001232K,-123.22",
            "3,L,0000001233L,-123.33",
            "4,M,0000001234M,-123.44",
            "5,N,0000001235N,-123.55",
            "6,O,0000001236O,-123.66",
            "7,P,0000001237P,-123.77",
            "8,Q,0000001238Q,-123.88",
            "9,R,0000001239R,-123.99"})
        void everyOverpunchCharacterDecodes(int digit, char overpunch, String image, String expected) {
            boolean negative = expected.startsWith("-");

            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage(image);

            assertThat(image).hasSize(11).endsWith(String.valueOf(overpunch));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);
            assertThat(record.dalytranAmt().signum() < 0).isEqualTo(negative);
            assertThat(record.hasZeroDalytranAmt()).isFalse();

            assertThat(record.dalytranAmtImage()).isEqualTo(image);
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) overpunch);

            DalyTranRecord rewritten = new DalyTranRecord(ASCII);
            rewritten.moveDalytranAmt(record.dalytranAmt());
            assertThat(rewritten.dalytranAmtImage())
                    .as("%s round-trips through its value", overpunch)
                    .isEqualTo(image);

            assertThat(ZonedSign.overpunch(digit, negative)).isEqualTo(overpunch);
            assertThat(ZonedSign.digitOf(overpunch)).isEqualTo(digit);
            assertThat(ZonedSign.isNegative(overpunch)).isEqualTo(negative);
        }

        @Test
        @DisplayName("the table this test asserts is the codec's own, in the documented order")
        void theTableMatchesTheCodecsDeclaredOrder() {
            assertThat(ZonedSign.POSITIVE_DIGITS).isEqualTo("{ABCDEFGHI").hasSize(10);
            assertThat(ZonedSign.NEGATIVE_DIGITS).isEqualTo("}JKLMNOPQR").hasSize(10);
            assertThat(ZonedSign.POSITIVE_ZERO).isEqualTo('{');
            assertThat(ZonedSign.NEGATIVE_ZERO).isEqualTo('}');
            assertThat(ZonedSign.POSITIVE_DIGITS.charAt(0)).isEqualTo(ZonedSign.POSITIVE_ZERO);
            assertThat(ZonedSign.NEGATIVE_DIGITS.charAt(0)).isEqualTo(ZonedSign.NEGATIVE_ZERO);
        }

        @Test
        @DisplayName("a POSITIVE value ending in zero encodes as '{', never as the digit '0'")
        void aPositiveValueEndingInZeroEncodesAsPositiveZero() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(new BigDecimal("325.00"));

            assertThat(record.dalytranAmtImage()).isEqualTo("0000003250{");
            assertThat(record.dalytranAmtImage()).endsWith("{").doesNotEndWith("0");
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '{');
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("325.00"));
        }

        @Test
        @DisplayName("a NEGATIVE value ending in zero encodes as '}', never as '{'")
        void aNegativeValueEndingInZeroEncodesAsNegativeZero() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(new BigDecimal("-919.00"));

            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(record.dalytranAmtImage()).endsWith("}").doesNotEndWith("{");
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));

            assertThat(record.dalytranAmtImage())
                    .isEqualTo(DalyTranRecord.decode(ROW_2, ASCII).dalytranAmtImage());
        }

        @Test
        @DisplayName("an ordinary '}' amount survives even a numeric round-trip")
        void anOrdinaryNegativeSurvivesANumericRoundTrip() {
            DalyTranRecord stored = DalyTranRecord.decode(ROW_2, ASCII);

            DalyTranRecord rebuilt = new DalyTranRecord(ASCII);
            rebuilt.moveDalytranAmt(stored.dalytranAmt());

            assertThat(rebuilt.dalytranAmtImage()).isEqualTo("0000009190}");
            assertThat(rebuilt.dalytranAmtImage()).isEqualTo(stored.dalytranAmtImage());
        }

        @Test
        @DisplayName("a whole-value negative zero survives the raw path, and ONLY the raw path")
        void aWholeValueNegativeZeroNeedsTheRawPath() {
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.writeDalytranAmtImage("0000000000}");

            assertThat(record.dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.copy().dalytranAmtImage()).isEqualTo("0000000000}");
            assertThat(record.rawImage()[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.encode(ASCII)[AMOUNT_SIGN_BYTE_INDEX]).isEqualTo((byte) '}');
            assertThat(record.displayImage().charAt(AMOUNT_SIGN_BYTE_INDEX)).isEqualTo('}');
            assertThat(DalyTranRecord.decode(record.rawImage(), ASCII).dalytranAmtImage())
                    .isEqualTo("0000000000}");

            assertThat(record.dalytranAmt()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);
            assertThat(record.hasZeroDalytranAmt()).isTrue();

            DalyTranRecord viaValue = new DalyTranRecord(ASCII);
            viaValue.moveDalytranAmt(record.dalytranAmt());
            assertThat(viaValue.dalytranAmtImage()).isEqualTo("0000000000{");
            assertThat(viaValue.dalytranAmt()).isEqualByComparingTo(record.dalytranAmt());
            assertThat(viaValue).isNotEqualTo(record);
        }

        @Test
        @DisplayName("an amount image is stored at exactly eleven characters, never padded or trimmed")
        void anAmountImageMustBeExactlyEleven() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            assertThatIllegalArgumentException()
                    .as("ten characters, one short")
                    .isThrownBy(() -> record.writeDalytranAmtImage("0000000000"));
            assertThatIllegalArgumentException()
                    .as("twelve characters, as if a sign byte were reserved")
                    .isThrownBy(() -> record.writeDalytranAmtImage("0000000000{0"));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeDalytranAmtImage(null));

            record.writeDalytranAmtImage("0000005047G");
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
        }
    }

    @Nested
    @DisplayName("COBOL MOVE semantics - the direction of truncation is not a detail")
    class MoveSemantics {
        @Test
        @DisplayName("PIC X: a short sender is space-padded on the RIGHT")
        void picXPadsOnTheRight() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranSource("POS TERM");
            assertThat(record.dalytranSource()).isEqualTo("POS TERM  ").hasSize(10);

            record.moveDalytranMerchantZip("72112");
            assertThat(record.dalytranMerchantZip()).isEqualTo("72112     ").hasSize(10);

            record.moveDalytranDesc("Purchase at Abshire-Lowe");
            assertThat(record.dalytranDesc())
                    .isEqualTo("Purchase at Abshire-Lowe" + " ".repeat(76)).hasSize(100);

            record.moveDalytranProcTs("");
            assertThat(record.dalytranProcTs()).isEqualTo(" ".repeat(26));
        }

        @Test
        @DisplayName("PIC X: an over-long sender is truncated on the RIGHT, keeping the first bytes")
        void picXTruncatesOnTheRight() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranTypeCd("0199");
            assertThat(record.dalytranTypeCd()).isEqualTo("01").hasSize(2);

            record.moveDalytranSource("ABCDEFGHIJKL");
            assertThat(record.dalytranSource()).isEqualTo("ABCDEFGHIJ").hasSize(10);

            record.moveDalytranId("0000000000683580EXTRA");
            assertThat(record.dalytranId()).isEqualTo("0000000000683580").hasSize(16);

            assertThat(record.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("PIC 9: a short sender is zero-filled on the LEFT - '05' stores 0005, not 0500")
        void picNineZeroFillsOnTheLeft() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranCatCd("05");
            assertThat(record.dalytranCatCdImage()).isEqualTo("0005").isNotEqualTo("0500");
            assertThat(record.dalytranCatCd()).isEqualTo(5);

            record.moveDalytranCatCd(1);
            assertThat(record.dalytranCatCdImage()).isEqualTo("0001");
            assertThat(record.dalytranCatCd()).isEqualTo(1);

            record.moveDalytranMerchantId(0L);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
            assertThat(record.dalytranMerchantId()).isZero();

            record.moveDalytranMerchantId("800000000");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("800000000");
            assertThat(record.dalytranMerchantId()).isEqualTo(800000000);
        }

        @Test
        @DisplayName("PIC 9: an over-long sender is truncated on the LEFT, keeping the LOW-order digits")
        void picNineTruncatesOnTheLeft() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranCatCd("123456");
            assertThat(record.dalytranCatCdImage()).isEqualTo("3456").isNotEqualTo("1234");

            record.moveDalytranCatCd(123456);
            assertThat(record.dalytranCatCdImage()).isEqualTo("3456");
            assertThat(record.dalytranCatCd()).isEqualTo(3456);

            record.moveDalytranMerchantId("1234567890123");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("567890123");
            assertThat(record.dalytranMerchantId()).isEqualTo(567890123);

            record.moveDalytranMerchantId(1234567890123L);
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("567890123");
        }

        @Test
        @DisplayName("PIC 9 is unsigned, so a negative sender and a non-digit sender are both refused")
        void picNineRefusesWhatItCannotRepresent() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> record.moveDalytranCatCd(-1));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.moveDalytranMerchantId(-1L));

            assertThatIllegalArgumentException().isThrownBy(() -> record.moveDalytranCatCd("00A1"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.moveDalytranMerchantId("8000 0000"));
            assertThatNullPointerException().isThrownBy(() -> record.moveDalytranCatCd(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> record.moveDalytranMerchantId((String) null));

            assertThat(record.dalytranCatCdImage()).isEqualTo("0000");
            assertThat(record.dalytranMerchantIdImage()).isEqualTo("000000000");
        }

        @Test
        @DisplayName("every character field has a MOVE path, and together they rebuild row 1 exactly")
        void everyFieldHasAMovePathThatRebuildsRowOne() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranId("0000000000683580");
            record.moveDalytranTypeCd("01");
            record.moveDalytranCatCd("0001");
            record.moveDalytranSource("POS TERM  ");
            record.moveDalytranDesc("Purchase at Abshire-Lowe");
            record.writeDalytranAmtImage("0000005047G");
            record.moveDalytranMerchantId("800000000");
            record.moveDalytranMerchantName("Abshire-Lowe");
            record.moveDalytranMerchantCity("North Enoshaven");
            record.moveDalytranMerchantZip("72112");
            record.moveDalytranCardNum("4859452612877065");
            record.moveDalytranOrigTs("2022-06-10 19:27:53.000000");
            record.moveDalytranProcTs("");

            assertThat(record.displayImage()).isEqualTo(ROW_1);
            assertThat(record.rawImage()).isEqualTo(ROW_1.getBytes(ASCII));
            assertThat(record).isEqualTo(DalyTranRecord.decode(ROW_1, ASCII));
        }

        @Test
        @DisplayName("R2/G24: the amount TRUNCATES excess fraction digits, positive and negative alike")
        void theAmountTruncatesRatherThanRounds() {
            DalyTranRecord record = new DalyTranRecord(ASCII);

            record.moveDalytranAmt(new BigDecimal("1.239"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("1.23"));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);

            record.moveDalytranAmt(new BigDecimal("1.999"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("1.99"));

            record.moveDalytranAmt(new BigDecimal("504.7799"));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");

            record.moveDalytranAmt(new BigDecimal("-1.239"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-1.23"));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);

            record.moveDalytranAmt(new BigDecimal("-1.999"));
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("-1.99"));

            record.moveDalytranAmt(new BigDecimal("-919.0099"));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000009190}");

            record.moveDalytranAmt(new BigDecimal("7"));
            assertThat(record.dalytranAmtImage()).isEqualTo("0000000070{");
            assertThat(record.dalytranAmt()).isEqualByComparingTo(new BigDecimal("7.00"));
            assertThat(record.dalytranAmt().scale()).isEqualTo(2);

            assertThatNullPointerException().isThrownBy(() -> record.moveDalytranAmt(null));
        }

        @Test
        @DisplayName("the truncating store agrees with the central policy, applied independently")
        void theTruncatingStoreAgreesWithTheCentralPolicy() {
            BigDecimal overPrecise = new BigDecimal("123.456");
            DalyTranRecord record = new DalyTranRecord(ASCII);
            record.moveDalytranAmt(overPrecise);

            assertThat(record.dalytranAmt())
                    .isEqualByComparingTo(CobolDecimal.storeMonetary(overPrecise))
                    .isEqualByComparingTo(overPrecise.setScale(2, CobolDecimal.COBOL_ROUNDING));

            BigDecimal negative = new BigDecimal("-123.456");
            record.moveDalytranAmt(negative);
            assertThat(record.dalytranAmt())
                    .isEqualByComparingTo(CobolDecimal.storeMonetary(negative))
                    .isEqualByComparingTo(negative.setScale(2, CobolDecimal.COBOL_ROUNDING));
        }
    }

    @Nested
    @DisplayName("G21: the FILLER read/write asymmetry - both sides, and why they agree")
    class FillerAsymmetry {
        @Test
        @DisplayName("write path: a fresh area's FILLER is twenty spaces at offset 330")
        void writePathInitialisesFillerToSpaces() {
            DalyTranRecord fresh = new DalyTranRecord(ASCII);

            assertThat(fresh.filler()).isEqualTo(" ".repeat(20)).hasSize(20);
            assertThat(fresh.rawSpan(DalyTranRecord.FILLER)).isEqualTo(" ".repeat(20));
            assertThat(fresh.displayImage().substring(330, 350)).isEqualTo(" ".repeat(20));

            fresh.moveDalytranId("0000000000683580");
            fresh.moveDalytranCardNum("4859452612877065");
            assertThat(fresh.filler()).isEqualTo(" ".repeat(20));
            assertThat(fresh.rawImage()).hasSize(DECLARED_RECORD_LENGTH);
        }

        @Test
        @DisplayName("read path: a decode then re-encode preserves NON-SPACE FILLER bytes exactly")
        void readPathPreservesTheOriginalFillerBytes() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_WITH_ZERO_FILLER, ASCII);

            assertThat(record.filler())
                    .as("the stored FILLER bytes are adopted, not replaced")
                    .isEqualTo("0".repeat(20)).hasSize(20);

            assertThat(record.encode(ASCII)).isEqualTo(ROW_WITH_ZERO_FILLER.getBytes(ASCII));
            assertThat(record.displayImage()).isEqualTo(ROW_WITH_ZERO_FILLER);
            assertThat(record.rawImage()[330]).isEqualTo((byte) '0');
            assertThat(record.rawImage()[349]).isEqualTo((byte) '0');

            record.moveDalytranSource("OPERATOR  ");
            assertThat(record.filler()).isEqualTo("0".repeat(20));
            record.moveDalytranAmt(new BigDecimal("-919.00"));
            assertThat(record.filler()).isEqualTo("0".repeat(20));

            assertThat(record.copy().filler()).isEqualTo("0".repeat(20));

            DalyTranRecord transcoded = DalyTranRecord.decode(record.encode(EBCDIC), EBCDIC);
            assertThat(transcoded.filler()).isEqualTo("0".repeat(20));
        }

        @Test
        @DisplayName("FILLER is why the record is 350 and not 330 bytes")
        void fillerIsWhatMakesTheRecordThreeHundredAndFifty() {
            assertThat(DECLARED_RECORD_LENGTH - DalyTranRecord.FILLER_LENGTH).isEqualTo(330);
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths()
                    - DalyTranRecord.FILLER_LENGTH).isEqualTo(330);

            assertThat(DalyTranRecord.decode(ROW_1, ASCII).rawImage()).hasSize(350);
            assertThat(350 + 80).as("REJECT-TRAN-DATA X(350) + VALIDATION-TRAILER X(80)")
                    .isEqualTo(430);
        }
    }

    @Nested
    @DisplayName("identity and diagnostics")
    class IdentityAndDiagnostics {
        @Test
        @DisplayName("equality is by bytes and charset, so equal VALUES are not equal RECORDS")
        void equalityIsByBytesAndCharset() {
            DalyTranRecord one = DalyTranRecord.decode(ROW_1, ASCII);
            DalyTranRecord same = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(one).isEqualTo(one);
            assertThat(one).isEqualTo(same);
            assertThat(same).isEqualTo(one);
            assertThat(one).hasSameHashCodeAs(same);

            assertThat(one).isNotEqualTo(DalyTranRecord.decode(ROW_2, ASCII));

            assertThat(one).isNotEqualTo(null);
            assertThat(one).isNotEqualTo("not a record");
            assertThat(one).isNotEqualTo(new Object());

            assertThat(one).isNotEqualTo(DalyTranRecord.decode(ROW_1, EBCDIC));

            DalyTranRecord positiveZero = new DalyTranRecord(ASCII);
            positiveZero.writeDalytranAmtImage("0000000000{");
            DalyTranRecord negativeZero = new DalyTranRecord(ASCII);
            negativeZero.writeDalytranAmtImage("0000000000}");

            assertThat(positiveZero.dalytranAmt())
                    .isEqualByComparingTo(negativeZero.dalytranAmt());
            assertThat(positiveZero)
                    .as("equal values, different records - the parity defect this prevents")
                    .isNotEqualTo(negativeZero);
        }

        @Test
        @DisplayName("the rendering names every field, describes the sensitive ones, and stays on one "
                + "line")
        void theRenderingNamesEveryFieldWithoutDisclosingTheActivity() {
            String rendered = DalyTranRecord.decode(ROW_1, ASCII).toString();

            assertThat(rendered)
                    .contains("DALYTRAN-ID=")
                    .contains("DALYTRAN-TYPE-CD=")
                    .contains("DALYTRAN-CAT-CD=")
                    .contains("DALYTRAN-SOURCE=")
                    .contains("DALYTRAN-DESC=")
                    .contains("DALYTRAN-AMT=")
                    .contains("DALYTRAN-MERCHANT-ID=")
                    .contains("DALYTRAN-MERCHANT-NAME=")
                    .contains("DALYTRAN-MERCHANT-CITY=")
                    .contains("DALYTRAN-MERCHANT-ZIP=")
                    .contains("DALYTRAN-ORIG-TS=")
                    .contains("DALYTRAN-PROC-TS=")
                    .contains("charset=US-ASCII");

            assertThat(rendered)
                    .contains("01")
                    .contains("0001")
                    .contains("POS TERM")
                    .contains("2022-06-10 19:27:53.000000");

            assertThat(rendered)
                    .contains("3580")
                    .doesNotContain("0000000000683580")
                    .doesNotContain("800000000");

            assertThat(rendered)
                    .doesNotContain("Abshire-Lowe")
                    .doesNotContain("North Enoshaven");

            assertThat(rendered)
                    .doesNotContain("0000005047G")
                    .doesNotContain("504.77");

            assertThat(rendered).contains("FILLER.length=20");

            assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the byte-exact paths still carry every value the rendering withholds")
        void theByteExactPathsStillCarryEverything() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);

            assertThat(record.dalytranId()).isEqualTo("0000000000683580");
            assertThat(record.dalytranAmt().toPlainString()).isEqualTo("504.77");
            assertThat(record.dalytranAmtImage()).isEqualTo("0000005047G");
            assertThat(record.dalytranMerchantName()).startsWith("Abshire-Lowe");
            assertThat(record.displayImage())
                    .contains("0000000000683580")
                    .contains("Abshire-Lowe");
        }

        @Test
        @DisplayName("a control character in a stored span cannot forge a second log line")
        void aControlCharacterCannotForgeALogLine() {
            String injected = ROW_1.substring(0, DalyTranRecord.DALYTRAN_SOURCE.offset())
                    + "A\r\nB      "
                    + ROW_1.substring(DalyTranRecord.DALYTRAN_SOURCE.offset()
                            + DalyTranRecord.DALYTRAN_SOURCE.length());

            String rendered = DalyTranRecord.decode(injected, ASCII).toString();

            assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
            assertThat(rendered.lines()).hasSize(1);
        }

        @Test
        @DisplayName("the rendering masks the card number, and the byte-exact paths do not")
        void theRenderingMasksThePanButTheByteExactPathsDoNot() {
            DalyTranRecord record = DalyTranRecord.decode(ROW_1, ASCII);
            String rendered = record.toString();

            assertThat(rendered).contains("DALYTRAN-CARD-NUM=************7065")
                    .doesNotContain("4859452612877065");

            assertThat(record.dalytranCardNum()).isEqualTo("4859452612877065");
            assertThat(record.rawSpan(DalyTranRecord.DALYTRAN_CARD_NUM))
                    .isEqualTo("4859452612877065");
            assertThat(record.displayImage()).contains("4859452612877065");
            assertThat(new String(record.rawImage(), ASCII)).contains("4859452612877065");
            assertThat(new String(record.encode(ASCII), ASCII)).contains("4859452612877065");
            assertThat(new String(record.rawSpanBytes(DalyTranRecord.DALYTRAN_CARD_NUM), ASCII))
                    .isEqualTo("4859452612877065");
        }

        @Test
        @DisplayName("the record reports the charset it was built with, never a platform default")
        void theRecordReportsItsOwnCharset() {
            assertThat(new DalyTranRecord(ASCII).charset()).isEqualTo(ASCII);
            assertThat(new DalyTranRecord(EBCDIC).charset()).isEqualTo(EBCDIC);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).charset()).isEqualTo(ASCII);
            assertThat(DalyTranRecord.decode(ROW_1, ASCII).copy().charset()).isEqualTo(ASCII);
        }
    }
}
