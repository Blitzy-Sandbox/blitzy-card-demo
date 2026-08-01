/*
 * ******************************************************************
 * Program     : RecordLayoutCopybookTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the record-layout copybook oracle before any
 *               entity test is allowed to rely on it.
 * Source      : app/cpy/*.cpy  (11 record layouts)
 *               app/catlg/LISTCAT.txt  (catalogued record lengths)
 *               app/jcl/DUSRSECJ.jcl:65-66  (USRSEC, defined in JCL)
 *               frozen at commit 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves {@link RecordLayoutCopybook} before the entity tests are allowed to depend on it.
 *
 * <p>An oracle that under-reports produces tests that pass for the wrong reason, so this class checks the
 * parser against evidence it does not itself produce. The strongest such check is the record length: the
 * sum of every leaf width, {@code FILLER} included, must equal the length catalogued for that cluster in
 * {@code app/catlg/LISTCAT.txt}. Those two figures come from entirely separate declarations - one a COBOL
 * picture list, the other a VSAM catalogue listing - so a dropped or misread field breaks the equality.
 * The catalogued figures are quoted here as literals with their source named, exactly as a COBOL locator
 * would be.
 */
@DisplayName("RecordLayoutCopybook: the record-layout oracle, proved against the VSAM catalogue")
final class RecordLayoutCopybookTest {

    /**
     * Every record layout with the record length its cluster declares.
     *
     * <p>Ten come from {@code app/catlg/LISTCAT.txt}. {@code CSUSR01Y} is the exception: {@code USRSEC} is
     * not catalogued there but is defined in {@code app/jcl/DUSRSECJ.jcl:65-66} as
     * {@code KEYS(8,0) RECORDSIZE(80,80) REUSE INDEXED}.
     *
     * @return member name paired with its catalogued record length
     */
    static Stream<Arguments> layoutsWithCataloguedLength() {
        return Stream.of(
                Arguments.of("CVACT01Y", 300),
                Arguments.of("CVACT02Y", 150),
                Arguments.of("CVACT03Y", 50),
                Arguments.of("CVCUS01Y", 500),
                Arguments.of("CVTRA01Y", 50),
                Arguments.of("CVTRA02Y", 50),
                Arguments.of("CVTRA03Y", 60),
                Arguments.of("CVTRA04Y", 60),
                Arguments.of("CVTRA05Y", 350),
                Arguments.of("CVTRA06Y", 350),
                Arguments.of("CSUSR01Y", 80));
    }

    /**
     * Every record layout member name.
     *
     * @return the eleven member names
     */
    static Stream<String> allLayouts() {
        return layoutsWithCataloguedLength().map(arguments -> (String) arguments.get()[0]);
    }

    @Nested
    @DisplayName("1. The record length corroborates the parse, layout by layout")
    final class RecordLength {

        @ParameterizedTest(name = "{0} sums to {1}")
        @MethodSource("com.cardemo.unit.model.RecordLayoutCopybookTest#layoutsWithCataloguedLength")
        @DisplayName("every leaf width sums to the catalogued record length")
        void sumsToTheCataloguedLength(final String member, final int cataloguedLength) {
            assertThat(RecordLayoutCopybook.of(member).recordLength())
                    .as("%s leaf widths must sum to the length its cluster declares", member)
                    .isEqualTo(cataloguedLength);
        }

        @Test
        @DisplayName("all eleven layouts are accounted for")
        void allElevenLayoutsAreAccountedFor() {
            assertThat(allLayouts()).hasSize(11);
        }
    }

    @Nested
    @DisplayName("2. The parser refuses to skip what it cannot decode")
    final class RefusesToSkip {

        @Test
        @DisplayName("an unrecognised picture form raises rather than being passed over")
        void anUnrecognisedPictureRaises(@TempDir final Path probeDirectory) throws IOException {
            // The defect this class of oracle is prone to is silently dropping an unanticipated picture
            // form, which makes it under-report without ever failing. That is exactly the bug the sibling
            // BMS oracle carried. Proving the refusal is real needs a layout carrying such a form, and
            // app/cpy is frozen, so the probe is written to a temporary directory instead.
            final Path probe = probeDirectory.resolve("PROBE01Y.cpy");
            Files.writeString(probe, """
                          01  PROBE-RECORD.
                              05  PROBE-NAME                        PIC X(10).
                              05  PROBE-PACKED                      PIC S9(7) COMP-3.
                    """, StandardCharsets.ISO_8859_1);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> RecordLayoutCopybook.of("PROBE01Y", probeDirectory))
                    .withMessageContaining("PROBE01Y.PROBE-PACKED")
                    .withMessageContaining("Silently skipping it would make the oracle under-report");
        }

        @Test
        @DisplayName("a layout with no leaf fields raises rather than returning an empty oracle")
        void anEmptyLayoutRaises(@TempDir final Path probeDirectory) throws IOException {
            final Path probe = probeDirectory.resolve("PROBE02Y.cpy");
            Files.writeString(probe, "      *    a banner and nothing else\n",
                    StandardCharsets.ISO_8859_1);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> RecordLayoutCopybook.of("PROBE02Y", probeDirectory))
                    .withMessageContaining("declares no leaf fields");
        }

        @Test
        @DisplayName("every form the corpus does use is decoded, so the refusal never fires on real input")
        void everyCorpusFormIsDecoded() {
            // The complement of the refusal: all eleven layouts must parse without raising. Combined with
            // the record-length corroboration in group 1, that means the three recognised picture forms
            // cover the corpus exactly - nothing is rejected, and nothing is silently skipped either.
            assertThat(allLayouts())
                    .allSatisfy(member -> assertThat(RecordLayoutCopybook.of(member).fieldNames())
                            .as("%s must parse", member)
                            .isNotEmpty());
        }

        @Test
        @DisplayName("a field the layout does not declare is reported with the full declared list")
        void anUndeclaredFieldIsReported() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> RecordLayoutCopybook.of("CVACT02Y").widthOf("CARD-NOT-A-FIELD"))
                    .withMessageContaining("declares no field named CARD-NOT-A-FIELD")
                    .withMessageContaining("CARD-NUM");
        }
    }

    @Nested
    @DisplayName("3. Geometry is decoded from the picture, not guessed")
    final class Geometry {

        @Test
        @DisplayName("an alphanumeric picture yields its width and no numeric domain")
        void alphanumericPicture() {
            final RecordLayoutCopybook card = RecordLayoutCopybook.of("CVACT02Y");
            final RecordLayoutCopybook.Geometry geometry = card.geometry("CARD-NUM");

            assertThat(geometry.picture()).isEqualTo("X(16)");
            assertThat(geometry.width()).isEqualTo(16);
            assertThat(geometry.digits()).isZero();
            assertThat(geometry.scale()).isZero();
            assertThat(geometry.signed()).isFalse();
            assertThat(geometry.numeric()).isFalse();
        }

        @Test
        @DisplayName("an unsigned integer picture yields equal width and digit count")
        void unsignedIntegerPicture() {
            final RecordLayoutCopybook.Geometry geometry =
                    RecordLayoutCopybook.of("CVACT02Y").geometry("CARD-ACCT-ID");

            assertThat(geometry.picture()).isEqualTo("9(11)");
            assertThat(geometry.width()).isEqualTo(11);
            assertThat(geometry.digits()).isEqualTo(11);
            assertThat(geometry.scale()).isZero();
            assertThat(geometry.numeric()).isTrue();
            assertThat(geometry.signed()).isFalse();
        }

        @Test
        @DisplayName("a signed decimal picture separates integer digits from scale")
        void signedDecimalPicture() {
            // ACCT-CURR-BAL is PIC S9(10)V99, so twelve positions carrying ten integer digits and two
            // decimals - the NUMERIC(12,2) the schema declares.
            final RecordLayoutCopybook.Geometry geometry =
                    RecordLayoutCopybook.of("CVACT01Y").geometry("ACCT-CURR-BAL");

            assertThat(geometry.picture()).isEqualTo("S9(10)V99");
            assertThat(geometry.digits()).isEqualTo(10);
            assertThat(geometry.scale()).isEqualTo(2);
            assertThat(geometry.width()).isEqualTo(12);
            assertThat(geometry.signed()).isTrue();
            assertThat(geometry.numeric()).isTrue();
        }

        @Test
        @DisplayName("the two precisions that differ from the common case are decoded correctly")
        void theTwoUnusualPrecisions() {
            // These are the two places a blanket NUMERIC(12,2) assumption would be wrong.
            assertThat(RecordLayoutCopybook.of("CVTRA01Y").geometry("TRAN-CAT-BAL").digits())
                    .as("TRAN-CAT-BAL is S9(09)V99, so NUMERIC(11,2) and not (12,2)")
                    .isEqualTo(9);
            assertThat(RecordLayoutCopybook.of("CVTRA02Y").geometry("DIS-INT-RATE").digits())
                    .as("DIS-INT-RATE is S9(04)V99, so NUMERIC(6,2)")
                    .isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("4. FILLER is counted but never exposed")
    final class FillerHandling {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.RecordLayoutCopybookTest#allLayouts")
        @DisplayName("no layout exposes FILLER as a field")
        void fillerIsNeverExposed(final String member) {
            assertThat(RecordLayoutCopybook.of(member).fieldNames()).doesNotContain("FILLER");
            assertThat(RecordLayoutCopybook.of(member).declares("FILLER")).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CVACT02Y", "CVTRA03Y", "CVTRA04Y"})
        @DisplayName("the named fields alone fall short of the record length, so FILLER really is counted")
        void fillerIsCountedTowardTheRecordLength(final String member) {
            final RecordLayoutCopybook layout = RecordLayoutCopybook.of(member);
            final int named = layout.fieldNames().stream().mapToInt(layout::widthOf).sum();

            assertThat(named)
                    .as("%s named fields must not already account for the whole record", member)
                    .isLessThan(layout.recordLength());
        }
    }

    @Nested
    @DisplayName("5. Declaration order is preserved")
    final class DeclarationOrder {

        @Test
        @DisplayName("the card layout's fields come back in copybook order")
        void cardLayoutOrder() {
            assertThat(RecordLayoutCopybook.of("CVACT02Y").fieldNames())
                    .containsExactly("CARD-NUM", "CARD-ACCT-ID", "CARD-CVV-CD", "CARD-EMBOSSED-NAME",
                            "CARD-EXPIRAION-DATE", "CARD-ACTIVE-STATUS");
        }

        @Test
        @DisplayName("a nested group's leaves are flattened in order, so a composite key reads correctly")
        void nestedGroupLeavesAreFlattened() {
            // TRAN-CAT-KEY is an 05 group containing two 10 leaves. The composite key's Java field order
            // has to match this, so the flattening order is load-bearing rather than incidental.
            assertThat(RecordLayoutCopybook.of("CVTRA04Y").fieldNames())
                    .containsExactly("TRAN-TYPE-CD", "TRAN-CAT-CD", "TRAN-CAT-TYPE-DESC");
        }

        @Test
        @DisplayName("the member name is reported back")
        void memberNameIsReported() {
            assertThat(RecordLayoutCopybook.of("CVACT02Y").member()).isEqualTo("CVACT02Y");
        }
    }
}
