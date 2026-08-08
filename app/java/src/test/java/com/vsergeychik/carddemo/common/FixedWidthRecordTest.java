package com.vsergeychik.carddemo.common;

import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link FixedWidthRecord}, the byte-span layer beneath the fixed-width codec.
 *
 * <p>These are plain JUnit 5 tests with no Spring context, because every decision in the class under
 * test is reachable without one. The layouts declared here are transcribed <strong>by hand from the
 * copybooks</strong> under {@code app/cpy}, field for field and offset for offset, so that the
 * arithmetic asserted below is the copybooks' own arithmetic rather than a restatement of the
 * implementation. Field names, including the misspelled {@code ACCT-EXPIRAION-DATE} and
 * {@code CARD-EXPIRAION-DATE}, are carried verbatim.
 *
 * <p>Two charsets are used throughout, both named explicitly and never defaulted: {@code IBM037} for
 * EBCDIC and {@code US-ASCII} for the text fixtures.
 */
@DisplayName("FixedWidthRecord - the COBOL record area as an offset-addressed byte span")
class FixedWidthRecordTest {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final byte ASCII_SPACE = 0x20;
    private static final byte ASCII_ZERO = 0x30;
    private static final byte EBCDIC_SPACE = 0x40;
    private static final byte EBCDIC_ZERO = (byte) 0xF0;

    // ---------------------------------------------------------------------------------------------
    // Layouts transcribed from the copybooks. Each is the authoritative statement of that record's
    // geometry, and each one existing at all is proof that its self-check passed.
    // ---------------------------------------------------------------------------------------------

    /** {@code app/cpy/CVACT01Y.cpy}, {@code 01 ACCOUNT-RECORD}, documented {@code RECLN 300}. */
    private static RecordLayout accountRecordLayout() {
        return RecordLayout.of(300,
                FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2),
                FieldSpan.signedScaled("ACCT-CREDIT-LIMIT", 24, 10, 2),
                FieldSpan.signedScaled("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2),
                FieldSpan.alphanumeric("ACCT-OPEN-DATE", 48, 10),
                // Misspelling preserved exactly as app/cpy/CVACT01Y.cpy declares it.
                FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 58, 10),
                FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 68, 10),
                FieldSpan.signedScaled("ACCT-CURR-CYC-CREDIT", 78, 10, 2),
                FieldSpan.signedScaled("ACCT-CURR-CYC-DEBIT", 90, 10, 2),
                FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 102, 10),
                FieldSpan.alphanumeric("ACCT-GROUP-ID", 112, 10),
                FieldSpan.filler(122, 178));
    }

    /** {@code app/cpy/CVTRA05Y.cpy}, {@code 01 TRAN-RECORD}, documented {@code RECLN = 350}. */
    private static RecordLayout tranRecordLayout() {
        return RecordLayout.of(350,
                FieldSpan.alphanumeric("TRAN-ID", 0, 16),
                FieldSpan.alphanumeric("TRAN-TYPE-CD", 16, 2),
                FieldSpan.unsignedNumeric("TRAN-CAT-CD", 18, 4),
                FieldSpan.alphanumeric("TRAN-SOURCE", 22, 10),
                FieldSpan.alphanumeric("TRAN-DESC", 32, 100),
                FieldSpan.signedScaled("TRAN-AMT", 132, 9, 2),
                FieldSpan.unsignedNumeric("TRAN-MERCHANT-ID", 143, 9),
                FieldSpan.alphanumeric("TRAN-MERCHANT-NAME", 152, 50),
                FieldSpan.alphanumeric("TRAN-MERCHANT-CITY", 202, 50),
                FieldSpan.alphanumeric("TRAN-MERCHANT-ZIP", 252, 10),
                FieldSpan.alphanumeric("TRAN-CARD-NUM", 262, 16),
                FieldSpan.alphanumeric("TRAN-ORIG-TS", 278, 26),
                FieldSpan.alphanumeric("TRAN-PROC-TS", 304, 26),
                FieldSpan.filler(330, 20));
    }

    /** {@code app/cpy/CVACT02Y.cpy}, {@code 01 CARD-RECORD}, documented {@code RECLN 150}. */
    private static RecordLayout cardRecordLayout() {
        return RecordLayout.of(150,
                FieldSpan.alphanumeric("CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("CARD-ACCT-ID", 16, 11),
                FieldSpan.unsignedNumeric("CARD-CVV-CD", 27, 3),
                FieldSpan.alphanumeric("CARD-EMBOSSED-NAME", 30, 50),
                // The same missing T as CVACT01Y; both are part of the migration contract.
                FieldSpan.alphanumeric("CARD-EXPIRAION-DATE", 80, 10),
                FieldSpan.alphanumeric("CARD-ACTIVE-STATUS", 90, 1),
                FieldSpan.filler(91, 59));
    }

    /** {@code app/cpy/CVACT03Y.cpy}, {@code 01 CARD-XREF-RECORD}, documented {@code RECLN 50}. */
    private static RecordLayout cardXrefLayout() {
        return RecordLayout.of(50,
                FieldSpan.alphanumeric("XREF-CARD-NUM", 0, 16),
                FieldSpan.unsignedNumeric("XREF-CUST-ID", 16, 9),
                FieldSpan.unsignedNumeric("XREF-ACCT-ID", 25, 11),
                FieldSpan.filler(36, 14));
    }

    /** {@code app/cpy/CVCUS01Y.cpy}, {@code 01 CUSTOMER-RECORD}, documented {@code RECLN 500}. */
    private static RecordLayout customerRecordLayout() {
        return RecordLayout.of(500,
                FieldSpan.unsignedNumeric("CUST-ID", 0, 9),
                FieldSpan.alphanumeric("CUST-FIRST-NAME", 9, 25),
                FieldSpan.alphanumeric("CUST-MIDDLE-NAME", 34, 25),
                FieldSpan.alphanumeric("CUST-LAST-NAME", 59, 25),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-1", 84, 50),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-2", 134, 50),
                FieldSpan.alphanumeric("CUST-ADDR-LINE-3", 184, 50),
                FieldSpan.alphanumeric("CUST-ADDR-STATE-CD", 234, 2),
                FieldSpan.alphanumeric("CUST-ADDR-COUNTRY-CD", 236, 3),
                FieldSpan.alphanumeric("CUST-ADDR-ZIP", 239, 10),
                FieldSpan.alphanumeric("CUST-PHONE-NUM-1", 249, 15),
                FieldSpan.alphanumeric("CUST-PHONE-NUM-2", 264, 15),
                FieldSpan.unsignedNumeric("CUST-SSN", 279, 9),
                FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID", 288, 20),
                FieldSpan.alphanumeric("CUST-DOB-YYYY-MM-DD", 308, 10),
                FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID", 318, 10),
                FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 1),
                FieldSpan.unsignedNumeric("CUST-FICO-CREDIT-SCORE", 329, 3),
                FieldSpan.filler(332, 168));
    }

    /**
     * {@code app/cpy/CSDAT01Y.cpy}, {@code 05 WS-CURDATE-MM-DD-YY} - eight bytes, two of which are
     * {@code FILLER PIC X(01) VALUE '/'}. This is the group that proves a {@code FILLER} emits its
     * declared literal rather than a space.
     */
    private static RecordLayout curdateMmDdYyLayout() {
        return RecordLayout.of(8,
                FieldSpan.unsignedNumeric("WS-CURDATE-MM", 0, 2),
                FieldSpan.filler(2, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-DD", 3, 2),
                FieldSpan.filler(5, 1, "/"),
                FieldSpan.unsignedNumeric("WS-CURDATE-YY", 6, 2));
    }

    // =============================================================================================
    @Nested
    @DisplayName("Layout self-check - gates G19 and G21 as a run-time assertion")
    class LayoutSelfCheck {

        @DisplayName("every copybook layout sums to exactly its documented RECLN")
        @ParameterizedTest(name = "{0} totals {1} bytes")
        @CsvSource({
                "CVACT01Y ACCOUNT-RECORD, 300",
                "CVTRA05Y TRAN-RECORD, 350",
                "CVACT02Y CARD-RECORD, 150",
                "CVACT03Y CARD-XREF-RECORD, 50",
                "CVCUS01Y CUSTOMER-RECORD, 500"
        })
        void copybookLayoutsTotalTheirDeclaredRecordLength(String copybook, int expected) {
            RecordLayout layout = switch (expected) {
                case 300 -> accountRecordLayout();
                case 350 -> tranRecordLayout();
                case 150 -> cardRecordLayout();
                case 50 -> cardXrefLayout();
                default -> customerRecordLayout();
            };

            int summed = 0;
            for (FieldSpan span : layout.storageSpans()) {
                summed += span.length();
            }

            assertThat(layout.recordLength())
                    .as("%s declared record length", copybook)
                    .isEqualTo(expected);
            assertThat(summed)
                    .as("%s storage spans must sum to the declared length", copybook)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("CVACT01Y offsets match the copybook field for field, FILLER included")
        void accountLayoutOffsetsMatchTheCopybook() {
            RecordLayout layout = accountRecordLayout();

            assertThat(layout.span("ACCT-ID").offset()).isZero();
            assertThat(layout.span("ACCT-ACTIVE-STATUS").offset()).isEqualTo(11);
            assertThat(layout.span("ACCT-CURR-BAL").offset()).isEqualTo(12);
            assertThat(layout.span("ACCT-CREDIT-LIMIT").offset()).isEqualTo(24);
            assertThat(layout.span("ACCT-CASH-CREDIT-LIMIT").offset()).isEqualTo(36);
            assertThat(layout.span("ACCT-OPEN-DATE").offset()).isEqualTo(48);
            assertThat(layout.span("ACCT-EXPIRAION-DATE").offset()).isEqualTo(58);
            assertThat(layout.span("ACCT-REISSUE-DATE").offset()).isEqualTo(68);
            assertThat(layout.span("ACCT-CURR-CYC-CREDIT").offset()).isEqualTo(78);
            assertThat(layout.span("ACCT-CURR-CYC-DEBIT").offset()).isEqualTo(90);
            assertThat(layout.span("ACCT-ADDR-ZIP").offset()).isEqualTo(102);
            assertThat(layout.span("ACCT-GROUP-ID").offset()).isEqualTo(112);

            List<FieldSpan> spans = layout.storageSpans();
            FieldSpan trailingFiller = spans.get(spans.size() - 1);
            assertThat(trailingFiller.name()).isEqualTo("FILLER");
            assertThat(trailingFiller.offset()).isEqualTo(122);
            assertThat(trailingFiller.length()).isEqualTo(178);
            assertThat(trailingFiller.endOffsetExclusive()).isEqualTo(300);
        }

        @Test
        @DisplayName("gate G21: dropping the trailing FILLER X(178) fails and names the shortfall")
        void droppingTheTrailingFillerIsRejected() {
            List<FieldSpan> withoutFiller = new ArrayList<>(accountRecordLayout().storageSpans());
            withoutFiller.remove(withoutFiller.size() - 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(300, withoutFiller))
                    .withMessageContaining("declares 122 byte(s)")
                    .withMessageContaining("record length is 300")
                    .withMessageContaining("178 byte(s) short")
                    .withMessageContaining("dropped trailing FILLER");
        }

        @Test
        @DisplayName("gate G19: reserving a sign byte on the five S9(10)V99 fields makes 305 and fails")
        void reservingASignByteForEverySignedFieldIsRejected() {
            // Exactly the mistake this layer exists to catch: five signed fields, each given a
            // thirteenth byte for a sign that is really overpunched into the trailing byte. The
            // layout is written out in full so the skewed arithmetic is visible rather than derived.
            List<FieldSpan> withSignBytes = List.of(
                    FieldSpan.unsignedNumeric("ACCT-ID", 0, 11),
                    FieldSpan.alphanumeric("ACCT-ACTIVE-STATUS", 11, 1),
                    new FieldSpan("ACCT-CURR-BAL", 12, 13, PictureKind.SIGNED_SCALED, null, false),
                    new FieldSpan("ACCT-CREDIT-LIMIT", 25, 13, PictureKind.SIGNED_SCALED, null, false),
                    new FieldSpan("ACCT-CASH-CREDIT-LIMIT", 38, 13, PictureKind.SIGNED_SCALED, null,
                            false),
                    FieldSpan.alphanumeric("ACCT-OPEN-DATE", 51, 10),
                    FieldSpan.alphanumeric("ACCT-EXPIRAION-DATE", 61, 10),
                    FieldSpan.alphanumeric("ACCT-REISSUE-DATE", 71, 10),
                    new FieldSpan("ACCT-CURR-CYC-CREDIT", 81, 13, PictureKind.SIGNED_SCALED, null,
                            false),
                    new FieldSpan("ACCT-CURR-CYC-DEBIT", 94, 13, PictureKind.SIGNED_SCALED, null,
                            false),
                    FieldSpan.alphanumeric("ACCT-ADDR-ZIP", 107, 10),
                    FieldSpan.alphanumeric("ACCT-GROUP-ID", 117, 10),
                    FieldSpan.filler(127, 178));

            int summed = 0;
            for (FieldSpan span : withSignBytes) {
                summed += span.length();
            }
            assertThat(summed).as("a reserved sign byte on each of the five signed fields")
                    .isEqualTo(305);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(300, withSignBytes))
                    .withMessageContaining("declares 305 byte(s)")
                    .withMessageContaining("5 byte(s) too long")
                    .withMessageContaining("sign byte reserved for a PIC S9 field");
        }

        @Test
        @DisplayName("signedScaled derives p + s, so no sign byte can be reintroduced by hand")
        void signedScaledWidthIsTheSumOfTheDigitCounts() {
            assertThat(FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2).length()).isEqualTo(12);
            assertThat(FieldSpan.signedScaled("TRAN-AMT", 132, 9, 2).length()).isEqualTo(11);
            assertThat(FieldSpan.signedScaled("DIS-INT-RATE", 0, 4, 2).length()).isEqualTo(6);
            assertThat(FieldSpan.signedScaled("SCALELESS", 0, 5, 0).length()).isEqualTo(5);
        }

        @Test
        @DisplayName("an overlapping non-REDEFINES pair is rejected")
        void overlappingStorageSpansAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(20,
                            FieldSpan.alphanumeric("FIRST", 0, 12),
                            FieldSpan.alphanumeric("SECOND", 8, 12)))
                    .withMessageContaining("Layout overlap at SECOND")
                    .withMessageContaining("overlaps 4 byte(s)")
                    .withMessageContaining("REDEFINES");
        }

        @Test
        @DisplayName("a gap between descriptors is rejected and must be declared as FILLER")
        void gapsBetweenStorageSpansAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(20,
                            FieldSpan.alphanumeric("FIRST", 0, 8),
                            FieldSpan.alphanumeric("SECOND", 12, 8)))
                    .withMessageContaining("Layout gap of 4 byte(s) before SECOND")
                    .withMessageContaining("declared as FILLER");
        }

        @Test
        @DisplayName("a layout that starts after offset 0 is a gap, not an implicit prefix")
        void aLayoutMustStartAtOffsetZero() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(10, FieldSpan.alphanumeric("ONLY", 2, 8)))
                    .withMessageContaining("Layout gap of 2 byte(s) before ONLY");
        }

        @Test
        @DisplayName("a repeated referable name is rejected, but FILLER may repeat freely")
        void duplicateReferableNamesAreRejectedWhileFillerMayRepeat() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(8,
                            FieldSpan.alphanumeric("SAME", 0, 4),
                            FieldSpan.alphanumeric("SAME", 4, 4)))
                    .withMessageContaining("more than once")
                    .withMessageContaining("only FILLER may repeat");

            RecordLayout manyFillers = curdateMmDdYyLayout();
            assertThat(manyFillers.storageSpans()).hasSize(5);
        }

        @Test
        @DisplayName("an empty span list and a non-positive record length are both rejected")
        void degenerateLayoutsAreRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordLayout(10, List.of()))
                    .withMessageContaining("declares no spans");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(0, FieldSpan.alphanumeric("ONLY", 0, 1)))
                    .withMessageContaining("not a valid record width");

            assertThatNullPointerException()
                    .isThrownBy(() -> new RecordLayout(10, null))
                    .withMessageContaining("requires its span list");
            assertThatNullPointerException()
                    .isThrownBy(() -> RecordLayout.of(10, (FieldSpan[]) null))
                    .withMessageContaining("requires its span list");
        }

        @Test
        @DisplayName("span lookup finds referable names, skips FILLER and reports the unknown")
        void spanLookupBehaviour() {
            RecordLayout layout = cardXrefLayout();

            assertThat(layout.hasSpan("XREF-ACCT-ID")).isTrue();
            assertThat(layout.span("XREF-ACCT-ID").length()).isEqualTo(11);
            assertThat(layout.hasSpan("FILLER")).as("FILLER is not a referable COBOL name").isFalse();
            assertThat(layout.hasSpan("xref-acct-id")).as("names are case-sensitive").isFalse();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> layout.span("NO-SUCH-FIELD"))
                    .withMessageContaining("declares no field named 'NO-SUCH-FIELD'")
                    .withMessageContaining("FILLER is not referable");

            assertThatNullPointerException().isThrownBy(() -> layout.span(null));
        }

        @Test
        @DisplayName("the layout's span list is immutable and defensively copied")
        void layoutSpanListIsDefensivelyCopied() {
            List<FieldSpan> mutable = new ArrayList<>(List.of(
                    FieldSpan.alphanumeric("ONLY", 0, 4)));
            RecordLayout layout = new RecordLayout(4, mutable);

            mutable.clear();

            assertThat(layout.spans()).hasSize(1);
            assertThat(layout.storageSpans()).hasSize(1);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("FieldSpan declaration guards")
    class FieldSpanGuards {

        @Test
        @DisplayName("null name and null kind are both rejected with an explanatory message")
        void nullReferencesAreRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldSpan(null, 0, 1, PictureKind.ALPHANUMERIC, null, false))
                    .withMessageContaining("copybook item name verbatim");
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldSpan("NAME", 0, 1, null, null, false))
                    .withMessageContaining("PICTURE category");
            assertThatNullPointerException()
                    .isThrownBy(() -> FieldSpan.filler(0, 1, null))
                    .withMessageContaining("FILLER VALUE literal is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("NAME", 0, 1).withInitialValue(null))
                    .withMessageContaining("VALUE literal is required");
        }

        @ParameterizedTest(name = "a blank name [{0}] is rejected")
        @ValueSource(strings = {"", " ", "\t"})
        void blankNamesAreRejected(String blank) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric(blank, 0, 1))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("a negative offset and a length below one are rejected")
        void geometryGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("NAME", -1, 4))
                    .withMessageContaining("negative offset -1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.alphanumeric("NAME", 0, 0))
                    .withMessageContaining("declares length 0");
        }

        @Test
        @DisplayName("a VALUE literal wider than its span is rejected at declaration time")
        void anOverWideLiteralIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.filler(0, 1, "//"))
                    .withMessageContaining("VALUE literal of 2 character(s)")
                    .withMessageContaining("only 1 byte(s) wide");
        }

        @Test
        @DisplayName("signedScaled rejects p below one and a negative s")
        void signedScaledDigitCountGuards() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.signedScaled("NAME", 0, 0, 2))
                    .withMessageContaining("p of at least 1");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FieldSpan.signedScaled("NAME", 0, 9, -1))
                    .withMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("hasInitialValue distinguishes a declared literal from none")
        void initialValuePresence() {
            assertThat(FieldSpan.filler(0, 4).hasInitialValue()).isFalse();
            assertThat(FieldSpan.filler(0, 1, "/").hasInitialValue()).isTrue();
            assertThat(FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11).withInitialValue("   ")
                    .hasInitialValue()).isTrue();
        }

        @Test
        @DisplayName("describe names the field, kind, offset, length and overlay status")
        void describeIsPrecise() {
            assertThat(FieldSpan.signedScaled("ACCT-CURR-BAL", 12, 10, 2).describe())
                    .isEqualTo("ACCT-CURR-BAL (SIGNED_SCALED, offset 12, length 12)");
            assertThat(FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11)
                    .redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC).describe())
                    .isEqualTo("CC-ACCT-ID-N (UNSIGNED_NUMERIC, offset 0, length 11, REDEFINES overlay)");
        }

        @Test
        @DisplayName("PictureKind states its own alignment and filler status")
        void pictureKindProperties() {
            assertThat(PictureKind.ALPHANUMERIC.numericDisplay()).isFalse();
            assertThat(PictureKind.FILLER.numericDisplay()).isFalse();
            assertThat(PictureKind.UNSIGNED_NUMERIC.numericDisplay()).isTrue();
            assertThat(PictureKind.SIGNED_SCALED.numericDisplay()).isTrue();

            assertThat(PictureKind.ALPHANUMERIC.leftJustified()).isTrue();
            assertThat(PictureKind.UNSIGNED_NUMERIC.leftJustified()).isFalse();

            assertThat(PictureKind.FILLER.filler()).isTrue();
            assertThat(PictureKind.ALPHANUMERIC.filler()).isFalse();
            assertThat(PictureKind.values()).hasSize(4);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Construction, charset handling and the defensive copy")
    class ConstructionAndCharset {

        @Test
        @DisplayName("a fresh area is space-filled with the charset's own space byte")
        void freshAreaIsSpaceFilledPerCharset() {
            byte[] ascii = new FixedWidthRecord(50, ASCII).toByteArray();
            byte[] ebcdic = new FixedWidthRecord(50, EBCDIC).toByteArray();

            assertThat(ascii).hasSize(50).containsOnly(ASCII_SPACE);
            assertThat(ebcdic).hasSize(50).containsOnly(EBCDIC_SPACE);
        }

        @Test
        @DisplayName("the pad bytes are derived from the charset, never hard-coded")
        void padBytesComeFromTheCharset() {
            FixedWidthRecord ascii = new FixedWidthRecord(4, ASCII);
            FixedWidthRecord ebcdic = new FixedWidthRecord(4, EBCDIC);

            assertThat(ascii.spacePadByte()).isEqualTo(ASCII_SPACE);
            assertThat(ascii.zeroPadByte()).isEqualTo(ASCII_ZERO);
            assertThat(ebcdic.spacePadByte()).isEqualTo(EBCDIC_SPACE);
            assertThat(ebcdic.zeroPadByte()).isEqualTo(EBCDIC_ZERO);

            assertThat(ebcdic.padByteFor(PictureKind.ALPHANUMERIC)).isEqualTo(EBCDIC_SPACE);
            assertThat(ebcdic.padByteFor(PictureKind.FILLER)).isEqualTo(EBCDIC_SPACE);
            assertThat(ebcdic.padByteFor(PictureKind.UNSIGNED_NUMERIC)).isEqualTo(EBCDIC_ZERO);
            assertThat(ebcdic.padByteFor(PictureKind.SIGNED_SCALED)).isEqualTo(EBCDIC_ZERO);

            assertThatNullPointerException().isThrownBy(() -> ascii.padByteFor(null))
                    .withMessageContaining("PICTURE kind is required");
        }

        @Test
        @DisplayName("recordLength and charset are exposed exactly as supplied")
        void geometryAndCharsetAreExposed() {
            FixedWidthRecord record = new FixedWidthRecord(300, EBCDIC);

            assertThat(record.recordLength()).isEqualTo(300);
            assertThat(record.charset()).isEqualTo(EBCDIC);
            assertThat(record).hasToString("FixedWidthRecord[recordLength=300, charset=IBM037]");
        }

        @Test
        @DisplayName("a null charset and a non-positive record length are rejected")
        void constructionGuards() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new FixedWidthRecord(10, null))
                    .withMessageContaining("charset must be supplied explicitly");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthRecord(0, ASCII))
                    .withMessageContaining("not a valid record width");
        }

        @Test
        @DisplayName("a multi-byte code page is rejected: an offset-addressed area cannot use one")
        void multiByteCharsetsAreRejected() {
            Charset utf16 = Charset.forName("UTF-16");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new FixedWidthRecord(10, utf16))
                    .withMessageContaining("encodes ' ' to")
                    .withMessageContaining("exactly one byte");
        }

        @Test
        @DisplayName("copyOf round-trips the supplied bytes identically")
        void copyOfRoundTripsBytes() {
            byte[] source = "1234567890A".getBytes(ASCII);

            FixedWidthRecord record = FixedWidthRecord.copyOf(source, 11, ASCII);

            assertThat(record.toByteArray()).isEqualTo(source);
            assertThat(record.readString(0, 11)).isEqualTo("1234567890A");
        }

        @Test
        @DisplayName("copyOf rejects a row that is not exactly the declared width")
        void copyOfRejectsAWrongWidthRow() {
            // The real case this guards: app/data/ASCII/cardxref.txt rows are 36 bytes where
            // CVACT03Y declares 50, because the fixture omits the trailing FILLER X(14).
            byte[] shortRow = new byte[36];

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(shortRow, 50, ASCII))
                    .withMessageContaining("Supplied 36 byte(s)")
                    .withMessageContaining("declared as 50 byte(s)");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(new byte[51], 50, ASCII))
                    .withMessageContaining("Supplied 51 byte(s)");

            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.copyOf(null, 50, ASCII))
                    .withMessageContaining("Record bytes are required");
        }

        @Test
        @DisplayName("mutating the array returned by toByteArray does not change the record")
        void toByteArrayReturnsADefensiveCopy() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);
            record.writeString(0, 4, "ABCD");

            byte[] exported = record.toByteArray();
            exported[0] = (byte) 'Z';

            assertThat(record.readString(0, 4)).isEqualTo("ABCD");
            assertThat(record.toByteArray()[0]).isEqualTo((byte) 'A');
        }

        @Test
        @DisplayName("mutating the array returned by readBytes does not change the record")
        void readBytesReturnsADefensiveCopy() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);
            record.writeString(0, 4, "ABCD");

            byte[] slice = record.readBytes(1, 2);
            assertThat(slice).containsExactly((byte) 'B', (byte) 'C');
            slice[0] = (byte) 'Z';

            assertThat(record.readString(0, 4)).isEqualTo("ABCD");
        }

        @Test
        @DisplayName("two charsets produce different bytes for the same non-ASCII character")
        void theCharsetIsGenuinelyHonoured() {
            String accented = "\u00e9";

            FixedWidthRecord asciiRecord = new FixedWidthRecord(1, ASCII);
            FixedWidthRecord ebcdicRecord = new FixedWidthRecord(1, EBCDIC);
            asciiRecord.writeString(0, 1, accented);
            ebcdicRecord.writeString(0, 1, accented);

            byte[] asciiBytes = asciiRecord.toByteArray();
            byte[] ebcdicBytes = ebcdicRecord.toByteArray();

            assertThat(asciiBytes).as("US-ASCII cannot map the character and substitutes")
                    .containsExactly((byte) '?');
            assertThat(ebcdicBytes).as("IBM037 maps it to a real code point")
                    .containsExactly((byte) 0x51);
            assertThat(ebcdicBytes).isNotEqualTo(asciiBytes);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Raw span primitives and their bounds")
    class RawPrimitives {

        @Test
        @DisplayName("a short value is left justified and space-padded to the full span")
        void writeStringPadsOnTheRight() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            record.writeString(0, 10, "AB");

            assertThat(record.readString(0, 10))
                    .as("padding is part of a PIC X value and is never trimmed away")
                    .isEqualTo("AB        ")
                    .hasSize(10);
        }

        @Test
        @DisplayName("the explicit form right justifies and zero-pads for a numeric span")
        void writeStringCanRightJustify() {
            FixedWidthRecord record = new FixedWidthRecord(11, ASCII);

            record.writeString(0, 11, "42", false, record.zeroPadByte());

            assertThat(record.readString(0, 11)).isEqualTo("00000000042");
        }

        @Test
        @DisplayName("an exactly-fitting value neither pads nor shifts")
        void writeStringHandlesAnExactFit() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            record.writeString(0, 4, "WXYZ");

            assertThat(record.readString(0, 4)).isEqualTo("WXYZ");
        }

        @Test
        @DisplayName("an over-wide value is rejected, never silently truncated")
        void writeStringRefusesToTruncate() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeString(0, 4, "TOO-LONG"))
                    .withMessageContaining("encodes to 8 byte(s)")
                    .withMessageContaining("only 4 byte(s) wide")
                    .withMessageContaining("never truncates");

            assertThat(record.readString(0, 4)).as("the failed write left no partial state")
                    .isEqualTo("    ");
        }

        @Test
        @DisplayName("writeBytes replaces a sub-span and readBytes extracts one")
        void subSpanReplacementAndExtraction() {
            FixedWidthRecord record = new FixedWidthRecord(8, ASCII);
            record.writeString(0, 8, "ABCDEFGH");

            record.writeBytes(2, "xy".getBytes(ASCII));

            assertThat(record.readString(0, 8)).isEqualTo("ABxyEFGH");
            assertThat(record.readBytes(2, 2)).isEqualTo("xy".getBytes(ASCII));
        }

        @Test
        @DisplayName("fill blanks a span with a chosen byte")
        void fillOverwritesASpan() {
            FixedWidthRecord record = new FixedWidthRecord(6, ASCII);
            record.writeString(0, 6, "ABCDEF");

            record.fill(1, 4, record.zeroPadByte());

            assertThat(record.readString(0, 6)).isEqualTo("A0000F");
        }

        @Test
        @DisplayName("null values and null byte arrays are rejected")
        void nullPayloadsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(4, ASCII);

            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeString(0, 4, null))
                    .withMessageContaining("A value is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeBytes(0, null))
                    .withMessageContaining("Replacement bytes are required");
        }

        @Test
        @DisplayName("a negative offset is rejected and names the record length")
        void negativeOffsetsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(300, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readBytes(-1, 4))
                    .withMessageContaining("Offset -1 is negative")
                    .withMessageContaining("record length 300");
        }

        @Test
        @DisplayName("a span of zero or negative length is rejected")
        void nonPositiveLengthsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(300, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readBytes(0, 0))
                    .withMessageContaining("Length 0 at offset 0")
                    .withMessageContaining("record length 300");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.writeBytes(0, new byte[0]))
                    .withMessageContaining("Length 0 at offset 0");
        }

        @Test
        @DisplayName("an offset past the end, and a length running past it, are both rejected")
        void spansOutsideTheRecordAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readString(50, 1))
                    .withMessageContaining("offset 50 of length 1")
                    .withMessageContaining("reaches byte 51")
                    .withMessageContaining("length 50");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readString(45, 10))
                    .withMessageContaining("reaches byte 55");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.fill(Integer.MAX_VALUE, 4, ASCII_SPACE))
                    .as("the bound is written so integer overflow cannot defeat it")
                    .withMessageContaining("past the end");
        }

        @Test
        @DisplayName("the last byte of the record is addressable")
        void theFinalByteIsReachable() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            record.writeString(49, 1, "Z");

            assertThat(record.readString(49, 1)).isEqualTo("Z");
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Descriptor-driven access")
    class DescriptorDrivenAccess {

        @Test
        @DisplayName("writeSpan takes its alignment and pad from the descriptor's kind")
        void writeSpanUsesTheDescriptorsAlignment() {
            RecordLayout layout = accountRecordLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.writeSpan(layout.span("ACCT-GROUP-ID"), "GRP1");
            record.writeSpan(layout.span("ACCT-ID"), "42");

            assertThat(record.readSpan(layout.span("ACCT-GROUP-ID")))
                    .as("PIC X is left justified and space-padded")
                    .isEqualTo("GRP1      ");
            assertThat(record.readSpan(layout.span("ACCT-ID")))
                    .as("PIC 9 is right justified and zero-padded")
                    .isEqualTo("00000000042");
        }

        @Test
        @DisplayName("readSpanBytes and writeSpanBytes move a span's raw bytes")
        void spanBytesRoundTrip() {
            RecordLayout layout = cardXrefLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);
            FieldSpan cardNum = layout.span("XREF-CARD-NUM");

            record.writeSpanBytes(cardNum, "4111111111111111".getBytes(ASCII));

            assertThat(record.readSpanBytes(cardNum)).isEqualTo("4111111111111111".getBytes(ASCII));
            assertThat(record.readSpan(cardNum)).isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("a descriptor-driven byte write must match the span width exactly")
        void spanBytesWriteRequiresAnExactWidth() {
            RecordLayout layout = cardXrefLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);
            FieldSpan cardNum = layout.span("XREF-CARD-NUM");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.writeSpanBytes(cardNum, new byte[15]))
                    .withMessageContaining("Supplied 15 byte(s)")
                    .withMessageContaining("XREF-CARD-NUM (ALPHANUMERIC, offset 0, length 16)")
                    .withMessageContaining("match the span width exactly");
        }

        @Test
        @DisplayName("null descriptors are rejected on every descriptor-driven entry point")
        void nullDescriptorsAreRejected() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            assertThatNullPointerException().isThrownBy(() -> record.readSpan(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException().isThrownBy(() -> record.writeSpan(null, "A"))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException().isThrownBy(() -> record.readSpanBytes(null))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException().isThrownBy(() -> record.writeSpanBytes(null, new byte[1]))
                    .withMessageContaining("field descriptor is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> record.writeSpanBytes(FieldSpan.alphanumeric("A", 0, 1), null))
                    .withMessageContaining("Replacement bytes are required for span 'A'");
        }

        @Test
        @DisplayName("a descriptor that overruns the record is rejected by the bounds check")
        void aDescriptorBeyondTheRecordIsRejected() {
            FixedWidthRecord record = new FixedWidthRecord(10, ASCII);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> record.readSpan(FieldSpan.alphanumeric("WIDE", 4, 12)))
                    .withMessageContaining("reaches byte 16")
                    .withMessageContaining("length 10");
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Initialisation - a FILLER emits its VALUE, not a blanket space")
    class Initialisation {

        @Test
        @DisplayName("CSDAT01Y's WS-CURDATE-MM-DD-YY initialises to 00/00/00, separators intact")
        void fillerLiteralsSurviveInitialisation() {
            RecordLayout layout = curdateMmDdYyLayout();

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.recordLength()).isEqualTo(8);
            assertThat(record.readString(0, 8))
                    .as("a blanket space-fill would have produced '  /  /  ' or blanked the slashes")
                    .isEqualTo("00/00/00");
            assertThat(record.readSpan(layout.span("WS-CURDATE-MM"))).isEqualTo("00");
            assertThat(record.readBytes(2, 1)).containsExactly((byte) '/');
            assertThat(record.readBytes(5, 1)).containsExactly((byte) '/');
        }

        @Test
        @DisplayName("the same group initialises correctly under EBCDIC, with EBCDIC pad bytes")
        void fillerLiteralsSurviveInitialisationUnderEbcdic() {
            FixedWidthRecord record = curdateMmDdYyLayout().newRecord(EBCDIC);

            assertThat(record.readString(0, 8)).isEqualTo("00/00/00");
            assertThat(record.toByteArray()[0])
                    .as("the EBCDIC zero byte, not the ASCII one")
                    .isEqualTo(EBCDIC_ZERO);
            assertThat(record.toByteArray()[2]).isEqualTo("/".getBytes(EBCDIC)[0]);
        }

        @Test
        @DisplayName("a FILLER with no VALUE initialises to spaces")
        void aFillerWithoutALiteralIsSpaceFilled() {
            RecordLayout layout = accountRecordLayout();

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.readString(122, 178)).isEqualTo(" ".repeat(178)).hasSize(178);
            assertThat(record.readBytes(122, 178)).containsOnly(ASCII_SPACE);
        }

        @Test
        @DisplayName("numeric spans take the zero byte and character spans the space byte")
        void theInitializeConventionIsApplied() {
            RecordLayout layout = accountRecordLayout();

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.readSpan(layout.span("ACCT-ID"))).isEqualTo("00000000000");
            assertThat(record.readSpan(layout.span("ACCT-CURR-BAL"))).isEqualTo("000000000000");
            assertThat(record.readSpan(layout.span("ACCT-OPEN-DATE"))).isEqualTo("          ");
            assertThat(record.recordLength()).isEqualTo(300);
            assertThat(record.toByteArray()).hasSize(300);
        }

        @Test
        @DisplayName("a named field's VALUE literal is honoured too, right-justified when numeric")
        void namedFieldLiteralsAreHonoured() {
            // app/cpy/CVCRD01Y.cpy declares CC-ACCT-ID PIC X(11) VALUE SPACES, and
            // app/cpy/COMEN02Y.cpy declares FILLER PIC 9(02) VALUE 1 - a numeric filler literal.
            RecordLayout layout = RecordLayout.of(13,
                    FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11).withInitialValue(" "),
                    new FieldSpan("FILLER", 11, 2, PictureKind.UNSIGNED_NUMERIC, "1", false));

            FixedWidthRecord record = layout.newRecord(ASCII);

            assertThat(record.readString(0, 11)).isEqualTo("           ");
            assertThat(record.readString(11, 2))
                    .as("a numeric literal is right justified and zero-padded, giving 01 not 1 ")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("initialise refuses a layout of a different declared length")
        void initialiseRejectsAMismatchedLayout() {
            FixedWidthRecord record = new FixedWidthRecord(50, ASCII);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> record.initialise(accountRecordLayout()))
                    .withMessageContaining("Layout declares a record length of 300")
                    .withMessageContaining("this record is 50 byte(s) wide");

            assertThatNullPointerException().isThrownBy(() -> record.initialise(null))
                    .withMessageContaining("record layout is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.forLayout(null, ASCII))
                    .withMessageContaining("record layout is required");
        }

        @Test
        @DisplayName("initialise resets a dirty area and skips REDEFINES overlays")
        void initialiseIsIdempotentAndSkipsOverlays() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);
            RecordLayout layout = RecordLayout.of(11,
                    acctId,
                    acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC));

            FixedWidthRecord record = layout.newRecord(ASCII);
            assertThat(record.readString(0, 11))
                    .as("the character span decides the fill; the overlay is skipped, "
                            + "so the span is not re-filled with zeros")
                    .isEqualTo("           ");

            record.writeString(0, 11, "DIRTY");
            record.initialise(layout);

            assertThat(record.readString(0, 11)).isEqualTo("           ");
            assertThat(layout.redefinitions()).hasSize(1);
            assertThat(layout.storageSpans()).hasSize(1);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("REDEFINES - two views over one backing span")
    class Redefines {

        @Test
        @DisplayName("CVCRD01Y's CC-ACCT-ID / CC-ACCT-ID-N pair reads the same eleven bytes")
        void anOverlayReadsTheSameBytes() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);
            FieldSpan acctIdN = acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC);
            RecordLayout layout = RecordLayout.of(11, acctId, acctIdN);
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.writeSpan(acctId, "00000000042");

            assertThat(acctIdN.offset()).isEqualTo(acctId.offset());
            assertThat(acctIdN.length()).isEqualTo(acctId.length());
            assertThat(acctIdN.redefinition()).isTrue();
            assertThat(acctId.redefinition()).isFalse();
            assertThat(record.readSpan(acctIdN)).isEqualTo(record.readSpan(acctId));
        }

        @Test
        @DisplayName("a write through either view is visible through the other - one span, not a copy")
        void writesArrivedThroughBothViews() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);
            FieldSpan acctIdN = acctId.redefinedAs("CC-ACCT-ID-N", PictureKind.UNSIGNED_NUMERIC);
            FixedWidthRecord record = RecordLayout.of(11, acctId, acctIdN).newRecord(ASCII);

            record.writeSpan(acctIdN, "7");

            assertThat(record.readSpan(acctIdN))
                    .as("written through the numeric view: right justified, zero-padded")
                    .isEqualTo("00000000007");
            assertThat(record.readSpan(acctId))
                    .as("and immediately visible through the character view")
                    .isEqualTo("00000000007");

            record.writeSpan(acctId, "ABC");

            assertThat(record.readSpan(acctIdN)).isEqualTo("ABC        ");
        }

        @Test
        @DisplayName("a narrower overlay is allowed; a wider one is rejected")
        void overlayWidthIsValidated() {
            FieldSpan acctId = FieldSpan.alphanumeric("CC-ACCT-ID", 0, 11);

            assertThat(acctId.redefinedAs("PREFIX", PictureKind.UNSIGNED_NUMERIC, 4).length())
                    .isEqualTo(4);
            assertThat(acctId.redefinedAs("WHOLE", PictureKind.UNSIGNED_NUMERIC, 11).length())
                    .isEqualTo(11);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> acctId.redefinedAs("TOO-WIDE", PictureKind.ALPHANUMERIC, 12))
                    .withMessageContaining("overlay 'TOO-WIDE' is 12 byte(s) wide")
                    .withMessageContaining("only 11 byte(s) wide")
                    .withMessageContaining("must not exceed the span it redefines");
        }

        @Test
        @DisplayName("CSDAT01Y's WS-CURDATE-N redefines a whole group of three elementary items")
        void aGroupRedefinitionIsAccepted() {
            RecordLayout layout = RecordLayout.of(8,
                    FieldSpan.unsignedNumeric("WS-CURDATE-YEAR", 0, 4),
                    FieldSpan.unsignedNumeric("WS-CURDATE-MONTH", 4, 2),
                    FieldSpan.unsignedNumeric("WS-CURDATE-DAY", 6, 2),
                    FieldSpan.redefining("WS-CURDATE-N", 0, 8, PictureKind.UNSIGNED_NUMERIC));

            FixedWidthRecord record = layout.newRecord(ASCII);
            record.writeSpan(layout.span("WS-CURDATE-YEAR"), "2022");
            record.writeSpan(layout.span("WS-CURDATE-MONTH"), "7");
            record.writeSpan(layout.span("WS-CURDATE-DAY"), "18");

            assertThat(record.readSpan(layout.span("WS-CURDATE-N"))).isEqualTo("20220718");
            assertThat(layout.storageSpans()).hasSize(3);
            assertThat(layout.redefinitions()).hasSize(1);
        }

        @Test
        @DisplayName("an overlay outside already-declared storage is rejected")
        void anOverlayMustRedefineExistingStorage() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(8,
                            FieldSpan.redefining("EARLY", 0, 8, PictureKind.UNSIGNED_NUMERIC),
                            FieldSpan.unsignedNumeric("REAL", 0, 8)))
                    .withMessageContaining("REDEFINES overlay EARLY")
                    .withMessageContaining("only 0 byte(s) of storage are declared ahead of it");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> RecordLayout.of(8,
                            FieldSpan.unsignedNumeric("REAL", 0, 4),
                            FieldSpan.redefining("BEYOND", 0, 8, PictureKind.UNSIGNED_NUMERIC),
                            FieldSpan.unsignedNumeric("SECOND", 4, 4)))
                    .withMessageContaining("reaches byte 8 but only 4 byte(s)");
        }

        @Test
        @DisplayName("an overlay is skipped by initialisation but still resolvable by name")
        void overlaysAreAddressableButNotInitialised() {
            FieldSpan text = FieldSpan.alphanumeric("CC-CARD-NUM", 0, 16);
            RecordLayout layout = RecordLayout.of(16, text,
                    text.redefinedAs("CC-CARD-NUM-N", PictureKind.UNSIGNED_NUMERIC));

            assertThat(layout.hasSpan("CC-CARD-NUM-N")).isTrue();
            assertThat(layout.span("CC-CARD-NUM-N").redefinition()).isTrue();
            assertThat(layout.newRecord(ASCII).readString(0, 16))
                    .as("only the storage span drives the fill")
                    .isEqualTo("                ");
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("OCCURS - COBOL subscripts are 1-based")
    class Occurs {

        @Test
        @DisplayName("gate G33: index 1 is the first element and index n the last")
        void firstAndLastElementsResolveCorrectly() {
            int base = 40;
            int elementLength = 12;
            int occurs = 9;

            assertThat(FixedWidthRecord.occursElementOffsetOneBased(base, elementLength, occurs, 1))
                    .as("the first element sits at the table's base offset")
                    .isEqualTo(40);
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(base, elementLength, occurs, 9))
                    .as("the last element sits at base + (n - 1) * elementLength")
                    .isEqualTo(40 + 8 * 12);
        }

        @DisplayName("every subscript in range maps to base + (index - 1) * length")
        @ParameterizedTest(name = "subscript {0} resolves to offset {1}")
        @CsvSource({"1, 0", "2, 5", "3, 10", "4, 15", "5, 20"})
        void everySubscriptInRangeResolves(int oneBasedIndex, int expectedOffset) {
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(0, 5, 5, oneBasedIndex))
                    .isEqualTo(expectedOffset);
        }

        @Test
        @DisplayName("index 0 and index n+1 are both rejected")
        void outOfRangeSubscriptsAreRejected() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 12, 9, 0))
                    .withMessageContaining("subscript 0 is outside 1..9")
                    .withMessageContaining("there is no index 0");

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 12, 9, 10))
                    .withMessageContaining("subscript 10 is outside 1..9");
        }

        @Test
        @DisplayName("a negative base, a zero element width and a zero count are rejected")
        void tableGeometryIsValidated() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(-1, 12, 9, 1))
                    .withMessageContaining("base offset -1 is negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 0, 9, 1))
                    .withMessageContaining("element length 0");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0, 12, 0, 1))
                    .withMessageContaining("count 0");
        }

        @Test
        @DisplayName("occursElementSpan derives an element descriptor for the first and last entry")
        void elementSpansAreDerivedFromTheTableSpan() {
            // COADM02Y shape: OCCURS 9, each option 4 bytes wide, table starting at offset 4.
            FieldSpan table = FieldSpan.alphanumeric("CDEMO-ADMIN-OPT", 4, 36);

            FieldSpan first = FixedWidthRecord.occursElementSpan(table, 9, 1,
                    "CDEMO-ADMIN-OPT-NUM", PictureKind.UNSIGNED_NUMERIC);
            FieldSpan last = FixedWidthRecord.occursElementSpan(table, 9, 9,
                    "CDEMO-ADMIN-OPT-NUM", PictureKind.UNSIGNED_NUMERIC);

            assertThat(first.offset()).isEqualTo(4);
            assertThat(first.length()).isEqualTo(4);
            assertThat(last.offset()).isEqualTo(4 + 8 * 4);
            assertThat(last.endOffsetExclusive()).isEqualTo(40);
            assertThat(first.redefinition()).isFalse();
        }

        @Test
        @DisplayName("an element of an overlay table is itself an overlay")
        void overlayTablesPropagateTheOverlayFlag() {
            FieldSpan overlayTable = FieldSpan.redefining("TABLE-N", 0, 20,
                    PictureKind.UNSIGNED_NUMERIC);

            FieldSpan element = FixedWidthRecord.occursElementSpan(overlayTable, 4, 2, "ELEM",
                    PictureKind.UNSIGNED_NUMERIC);

            assertThat(element.redefinition()).isTrue();
            assertThat(element.offset()).isEqualTo(5);
            assertThat(element.length()).isEqualTo(5);
        }

        @Test
        @DisplayName("a table width that is not a whole multiple of the count is rejected")
        void anInexactTableWidthIsRejected() {
            FieldSpan table = FieldSpan.alphanumeric("TABLE", 0, 37);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementSpan(table, 9, 1, "ELEM",
                            PictureKind.ALPHANUMERIC))
                    .withMessageContaining("37 byte(s) wide")
                    .withMessageContaining("not a whole multiple of its 9 occurrence(s)");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementSpan(table, 0, 1, "ELEM",
                            PictureKind.ALPHANUMERIC))
                    .withMessageContaining("count 0");

            assertThatNullPointerException()
                    .isThrownBy(() -> FixedWidthRecord.occursElementSpan(null, 9, 1, "ELEM",
                            PictureKind.ALPHANUMERIC))
                    .withMessageContaining("whole OCCURS table span is required");
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("End-to-end: a real record serialises back to its exact declared width")
    class EndToEnd {

        @Test
        @DisplayName("an account record round-trips through 300 bytes with FILLER intact")
        void accountRecordRoundTrip() {
            RecordLayout layout = accountRecordLayout();
            FixedWidthRecord record = layout.newRecord(ASCII);

            record.writeSpan(layout.span("ACCT-ID"), "10000000001");
            record.writeSpan(layout.span("ACCT-ACTIVE-STATUS"), "Y");
            record.writeSpan(layout.span("ACCT-CURR-BAL"), "000000123456");
            record.writeSpan(layout.span("ACCT-OPEN-DATE"), "2022-07-19");
            record.writeSpan(layout.span("ACCT-EXPIRAION-DATE"), "2025-07-19");
            record.writeSpan(layout.span("ACCT-GROUP-ID"), "ZEROPCT");

            byte[] serialised = record.toByteArray();
            assertThat(serialised).as("gate G19: the record is exactly its copybook width")
                    .hasSize(300);

            FixedWidthRecord reread = FixedWidthRecord.copyOf(serialised, 300, ASCII);
            assertThat(reread.readSpan(layout.span("ACCT-ID"))).isEqualTo("10000000001");
            assertThat(reread.readSpan(layout.span("ACCT-ACTIVE-STATUS"))).isEqualTo("Y");
            assertThat(reread.readSpan(layout.span("ACCT-CURR-BAL"))).isEqualTo("000000123456");
            assertThat(reread.readSpan(layout.span("ACCT-OPEN-DATE"))).isEqualTo("2022-07-19");
            assertThat(reread.readSpan(layout.span("ACCT-EXPIRAION-DATE"))).isEqualTo("2025-07-19");
            assertThat(reread.readSpan(layout.span("ACCT-GROUP-ID"))).isEqualTo("ZEROPCT   ");
            assertThat(reread.readString(122, 178))
                    .as("gate G21: the trailing FILLER is emitted, space-filled")
                    .isEqualTo(" ".repeat(178));
            assertThat(reread.toByteArray()).isEqualTo(serialised);
        }

        @Test
        @DisplayName("a transaction record round-trips through 350 bytes")
        void tranRecordRoundTrip() {
            RecordLayout layout = tranRecordLayout();
            FixedWidthRecord record = layout.newRecord(EBCDIC);

            record.writeSpan(layout.span("TRAN-ID"), "0000000000000001");
            record.writeSpan(layout.span("TRAN-AMT"), "00000012345");
            record.writeSpan(layout.span("TRAN-DESC"), "Interest for a/c 10000000001");

            assertThat(record.toByteArray()).hasSize(350);
            assertThat(record.readSpan(layout.span("TRAN-AMT")))
                    .as("11 bytes, because S9(09)V99 overpunches its sign")
                    .isEqualTo("00000012345")
                    .hasSize(11);
            assertThat(layout.span("TRAN-AMT").length()).isEqualTo(11);
            assertThat(record.readString(330, 20)).isEqualTo(" ".repeat(20));
        }
    }
}
