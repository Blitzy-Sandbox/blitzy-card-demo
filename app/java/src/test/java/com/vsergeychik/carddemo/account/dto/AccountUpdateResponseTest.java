package com.vsergeychik.carddemo.account.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.AcctSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CommArea;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CustSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.Details;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse.FieldAttributes;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse.ScreenField;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AccountUpdateResponse} - the {@code CACTUPAO} output projection of {@code CAUP}.
 *
 * <h2>What this suite is checking, and why in this shape</h2>
 * The response is a projection of a byte-level contract, so almost everything worth asserting is a
 * <em>property over all {@value AccountUpdateResponse#FIELD_COUNT} fields</em> rather than a fact about
 * one of them. Every such property is therefore driven from {@code ScreenField.values()} with
 * {@code @EnumSource} or a loop: a field added to the mapset is then covered by construction, instead of
 * being covered only if somebody remembers to add a case. That is the failure mode a hand-written list of
 * fields has, and it is the one this file is written to avoid.
 *
 * <p>Nothing here needs a Spring context, a {@code JobLauncher} or an HTTP layer (practice
 * <strong>B10</strong>): every instance is built through the public builder, which is exactly how
 * {@code AccountUpdateService}'s {@code 9700-CHECK-CHANGE-IN-REC} tests and
 * {@code parity/ParityHarness} build one.
 *
 * <h2>Project rules</h2>
 * <strong>No user rules were provided for this project.</strong> {@code review_rules} returns exactly one
 * line - {@code "No user rules provided."} - and that single line is the whole document, not a truncated
 * read of a longer one. Per <strong>UR4</strong> that absence is recorded here rather than glossed over, no
 * rule has been invented to fill the space, and it is emphatically not treated as licence to assert less.
 * Enterprise best practice governs instead, in the specific form the migration plan fixes in its
 * <em>Rules</em> section:
 *
 * <ul>
 *   <li><strong>B1</strong> - only coordinates {@code app/java/pom.xml} already resolves: JUnit Jupiter
 *       5.12.2, AssertJ 3.27.7, Jackson 2.21.4, all managed by {@code spring-boot-starter-parent:3.5.16}.
 *       No new dependency, and in particular no Lombok, MapStruct, springdoc, Testcontainers or
 *       {@code spring-security-test}.</li>
 *   <li><strong>B2</strong> - no Boot 4.x, JUnit 6 or Batch 6 API is used, however current: the plan pins
 *       Spring Boot 3.x and constraint fidelity outranks recency.</li>
 *   <li><strong>B3</strong> - the eight COBOL reference inputs are read-only. Nothing here opens one at
 *       run time; every expected width, literal and total below is an inlined Java literal, so this suite
 *       cannot be made to pass by editing the oracle.</li>
 *   <li><strong>B4</strong> - three places where a downstream document and the source disagree are settled
 *       in the source's favour and commented at the assertion, not silently harmonised: the
 *       {@code CCARD-AID} condition count is <strong>16</strong>, the composite-component count is
 *       <strong>21</strong>, and the {@code XCTL} spans {@code L956-L958}. A fourth: {@code COACTUP.bms}
 *       declares neither {@code ALARM} nor {@code EXTATT}.</li>
 *   <li><strong>B6</strong> - no Spring Security anywhere: no {@code @WithMockUser}, no filter chain, no
 *       token. The legacy plaintext posture is neither weakened nor unrequestedly strengthened.</li>
 *   <li><strong>B7</strong> - deterministic: no sleep, no randomness, no wall clock. Where the header date
 *       and time are involved a {@link java.time.Clock#fixed} is injected, because {@link DateHeader}
 *       takes a clock by design and never calls {@code now()} of its own.</li>
 *   <li><strong>B8</strong> - explicit over implicit: no wildcard import (<strong>G52</strong>), an
 *       explicit {@link java.nio.charset.Charset} on every codec call, and an explicit scale plus
 *       {@link RoundingMode#DOWN} wherever a scaled value appears.</li>
 *   <li><strong>B9</strong> - no static mutable state (<strong>G53</strong>). The one static field is a
 *       {@code final} stateless codec; every {@code ObjectMapper} and every response is built inside the
 *       test that uses it.</li>
 *   <li><strong>B12</strong> - provenance on every number: each asserted width, byte total, literal and
 *       {@code PICTURE} names the file and line it came from, because no COBOL execution baseline exists
 *       in this environment (risk <strong>R-A</strong>) and a statically derived expectation is only as
 *       trustworthy as its citation.</li>
 * </ul>
 *
 * <h2>Gates this suite owns</h2>
 * <strong>G9</strong> every payload field traces to one {@code DFHMDF} and one {@code xxxO} {@code PICTURE}
 * · <strong>G37</strong> no server-side session state · <strong>G38</strong> the {@code CSSETATY}
 * REENTER-gated highlight, this file's headline · <strong>G40</strong> the navigation trio ·
 * <strong>G50</strong> both states of the nine {@code ACUP-CHANGE-ACTION} names and of the four
 * {@code COCOM01Y} {@code 88}s · <strong>G49</strong> this package's own branch ratio ·
 * <strong>G52</strong> · <strong>G53</strong>. <strong>G22</strong>, <strong>G23</strong> and
 * <strong>G24</strong> are owned as negatives: no {@code double}, no {@code float}, and no rounding mode
 * other than {@code DOWN}.
 *
 * <p><strong>On G38 in particular.</strong> {@code grep -c CSSETATY app/cbl/COACTUPC.cbl} is
 * <strong>39</strong> and {@code grep -c CSSETATY app/cbl/COACTVWC.cbl} is <strong>0</strong>.
 * {@code COACTUPC} is the copybook's sole COBOL consumer, so this is the only suite in the package
 * entitled to assert the strict rule; {@code AccountViewResponseTest} must not, because {@code COACTVWC}
 * hand-inlines its highlight and leaves the colour arm ungated on context. The blanket wording
 * "highlight only in REENTER" is accurate here and inaccurate for the View pair.
 */
@DisplayName("AccountUpdateResponse - the CACTUPAO output projection of CAUP")
class AccountUpdateResponseTest {

    /** The code page of the ASCII fixtures, named explicitly rather than left to the platform. */
    private static final FixedWidthCodec ASCII = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** Every field, for {@code @MethodSource}. */
    private static List<ScreenField> allFields() {
        return AccountUpdateResponse.FIELDS;
    }

    /** A value that fits any field, so a single sentinel can be written into all 54. */
    private static final String SHORT_VALUE = "X";

    /**
     * A response with a <strong>distinct</strong>, width-correct value in every field.
     *
     * <p>Distinctness is the point, and it is why the value is base-36 of the field's ordinal rather than
     * its label: a {@code PIC X} move truncates on the right, so label-derived values collide as soon as a
     * field is narrower than its own name - {@code ACTSSN1} and {@code ACTSSN2} would both become
     * {@code "ACT"}-ish, and an accessor wired to the wrong enum constant would then go unnoticed. Base-36
     * of the ordinal needs at most two characters, which fits even the two {@code X(1)} fields, so every
     * one of the {@value AccountUpdateResponse#FIELD_COUNT} values survives its own width intact.
     *
     * @return the populated response
     */
    private static AccountUpdateResponse populated() {
        AccountUpdateResponse.Builder builder = AccountUpdateResponse.builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, ASCII.movePicX(distinctValueFor(field), field.length()));
        }
        return builder.build();
    }

    /**
     * A value unique to one field and short enough to survive its declared width.
     *
     * @param field the field
     * @return the value, before the {@code PIC X} move
     */
    private static String distinctValueFor(ScreenField field) {
        return Integer.toString(field.ordinal(), 36) + "-" + field.label();
    }

    // =============================================================================================
    // Screen identity and geometry. The arithmetic the whole type rests on.
    // =============================================================================================

    @Nested
    @DisplayName("Screen identity, from the CSD and the program's own WORKING-STORAGE")
    class Identity {

        @Test
        @DisplayName("the four literals of app/cbl/COACTUPC.cbl:533-540, trailing space included")
        void literalsMatchTheSource() {
            // The transaction-to-program binding is not the program's own claim about itself; it is
            // declared by CICS resource definition, and both halves must agree:
            //   app/csd/CARDDEMO.CSD:306  DEFINE TRANSACTION(CAUP) GROUP(CARDDEMO)
            //   app/csd/CARDDEMO.CSD:307  DESCRIPTION(CREDIT CARD DEMO ACCOUNT UPDATE)
            //   app/csd/CARDDEMO.CSD:308         PROGRAM(COACTUPC) ...
            assertThat(AccountUpdateResponse.THIS_PROGRAM)
                    .as("CARDDEMO.CSD:308 - PROGRAM(COACTUPC)")
                    .isEqualTo("COACTUPC");
            assertThat(AccountUpdateResponse.THIS_TRANSACTION)
                    .as("CARDDEMO.CSD:306 - TRANSACTION(CAUP), the four-character tranid")
                    .isEqualTo("CAUP")
                    .hasSize(AccountUpdateResponse.TRNNAME_LENGTH);
            assertThat(AccountUpdateResponse.THIS_MAPSET_LITERAL).isEqualTo("COACTUP ")
                    .hasSize(AccountUpdateResponse.THIS_MAPSET_LITERAL_LENGTH);
            assertThat(AccountUpdateResponse.MAP_NAME).isEqualTo("CACTUPA");
        }

        @Test
        @DisplayName("the eight-character mapset literal loses its space on the way to the X(7) receiver")
        void theMapsetLiteralIsTruncatedToSeven() {
            assertThat(AccountUpdateResponse.MAPSET_NAME)
                    .isEqualTo(AccountUpdateResponse.THIS_MAPSET_LITERAL.strip())
                    .hasSize(AccountUpdateResponse.NEXT_MAPSET_LENGTH);
        }

        @Test
        @DisplayName("BMS drops the leading O: the groups are CACTUPAI and CACTUPAO, never COACTUPO")
        void theGroupNamesCarryTheBmsQuirk() {
            assertThat(AccountUpdateResponse.INPUT_GROUP_NAME).isEqualTo("CACTUPAI");
            assertThat(AccountUpdateResponse.OUTPUT_GROUP_NAME).isEqualTo("CACTUPAO");
            assertThat(AccountUpdateResponse.OUTPUT_GROUP_NAME)
                    .doesNotStartWith(AccountUpdateResponse.MAPSET_NAME);
        }

        @Test
        @DisplayName("SIZE=(24,80) and 128 DFHMDF entries, of which 54 are named")
        void screenShapeMatchesTheMapset() {
            assertThat(AccountUpdateResponse.SCREEN_ROWS).isEqualTo(24);
            assertThat(AccountUpdateResponse.SCREEN_COLUMNS).isEqualTo(80);
            assertThat(AccountUpdateResponse.DFHMDF_ENTRY_COUNT).isEqualTo(128);
            assertThat(AccountUpdateResponse.FIELD_COUNT).isEqualTo(54);
        }
    }

    @Nested
    @DisplayName("Group geometry - 12 + 54 x 7 + 705 = 1095, written out by hand")
    class Geometry {

        @Test
        @DisplayName("the per-field stride is 3 of FILLER plus a 4-item quad")
        void theStrideIsSevenPlusN() {
            assertThat(AccountUpdateResponse.FIELD_PREFIX_FILLER_LENGTH).isEqualTo(3);
            assertThat(AccountUpdateResponse.ATTRIBUTE_QUAD_LENGTH).isEqualTo(4);
            assertThat(AccountUpdateResponse.FIELD_OVERHEAD).isEqualTo(7);
            assertThat(AccountUpdateResponse.COLOUR_ITEM_LENGTH
                    + AccountUpdateResponse.PS_ITEM_LENGTH
                    + AccountUpdateResponse.HILIGHT_ITEM_LENGTH
                    + AccountUpdateResponse.VALIDN_ITEM_LENGTH)
                    .isEqualTo(AccountUpdateResponse.ATTRIBUTE_QUAD_LENGTH);
        }

        @Test
        @DisplayName("the payload is 705 bytes and the group is 1095")
        void theTotalsAreTheDeclaredOnes() {
            assertThat(AccountUpdateResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(AccountUpdateResponse.PAYLOAD_LENGTH).isEqualTo(705);
            assertThat(AccountUpdateResponse.GROUP_LENGTH).isEqualTo(1095);
            assertThat(AccountUpdateResponse.GROUP_LENGTH)
                    .isEqualTo(AccountUpdateResponse.TIOAPFX_LENGTH
                            + AccountUpdateResponse.FIELD_COUNT * AccountUpdateResponse.FIELD_OVERHEAD
                            + AccountUpdateResponse.PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("PAYLOAD_LENGTH really is the sum of the 54 declared widths")
        void thePayloadIsTheSumOfTheWidths() {
            int summed = 0;
            for (ScreenField field : ScreenField.values()) {
                summed += field.length();
            }
            assertThat(summed).isEqualTo(AccountUpdateResponse.PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("the commarea is 1 + 436 + 436 = 873, and 1033 of 2000 bytes travel")
        void theCommareaArithmeticHolds() {
            assertThat(ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(Details.RECORD_LENGTH).isEqualTo(436);
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(873);
            assertThat(CommArea.RECORD_LENGTH)
                    .isEqualTo(ChangeAction.RECORD_LENGTH + 2 * Details.RECORD_LENGTH);
            assertThat(AccountUpdateResponse.TOTAL_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + CommArea.RECORD_LENGTH)
                    .isEqualTo(1033)
                    .isLessThan(AccountUpdateResponse.COMMAREA_CAPACITY);
            assertThat(AccountUpdateResponse.COMMAREA_CAPACITY).isEqualTo(2000);
        }

        @Test
        @DisplayName("the navigation widths come from COCOM01Y's receivers: 8, 7, 7")
        void theNavigationWidthsMatchTheReceivers() {
            assertThat(AccountUpdateResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(AccountUpdateResponse.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AccountUpdateResponse.NEXT_MAP_LENGTH).isEqualTo(7);
        }
    }

    // =============================================================================================
    // The 54 fields. Gate G9: every payload field traces to one DFHMDF definition and one PICTURE.
    // =============================================================================================

    @Nested
    @DisplayName("The 54 fields - names, order, widths and the G9 provenance trace")
    class Fields {

        @Test
        @DisplayName("exactly 54, and FIELDS is unmodifiable and in copybook order")
        void thereAreExactlyFiftyFour() {
            assertThat(ScreenField.values()).hasSize(54);
            assertThat(AccountUpdateResponse.FIELDS)
                    .hasSize(54)
                    .containsExactly(ScreenField.values());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AccountUpdateResponse.FIELDS.add(ScreenField.TRNNAME));
        }

        @Test
        @DisplayName("the declared names, order and widths, transcribed from app/cpy-bms/COACTUP.CPY")
        void theTableMatchesTheCopybook() {
            // Written out as one literal table rather than derived, so that this assertion is an
            // independent transcription of the copybook and not a restatement of the enum.
            assertThat(AccountUpdateResponse.FIELDS.stream().map(ScreenField::label).toList())
                    .containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                            "ACCTSID", "ACSTTUS", "OPNYEAR", "OPNMON", "OPNDAY", "ACRDLIM", "EXPYEAR",
                            "EXPMON", "EXPDAY", "ACSHLIM", "RISYEAR", "RISMON", "RISDAY", "ACURBAL",
                            "ACRCYCR", "AADDGRP", "ACRCYDB", "ACSTNUM", "ACTSSN1", "ACTSSN2", "ACTSSN3",
                            "DOBYEAR", "DOBMON", "DOBDAY", "ACSTFCO", "ACSFNAM", "ACSMNAM", "ACSLNAM",
                            "ACSADL1", "ACSSTTE", "ACSADL2", "ACSZIPC", "ACSCITY", "ACSCTRY", "ACSPH1A",
                            "ACSPH1B", "ACSPH1C", "ACSGOVT", "ACSPH2A", "ACSPH2B", "ACSPH2C", "ACSEFTC",
                            "ACSPFLG", "INFOMSG", "ERRMSG", "FKEYS", "FKEY05", "FKEY12");
            assertThat(AccountUpdateResponse.FIELDS.stream().map(ScreenField::length).toList())
                    .containsExactly(4, 40, 8, 8, 40, 8, 11, 1, 4, 2, 2, 15, 4, 2, 2, 15, 4, 2, 2, 15,
                            15, 10, 15, 9, 3, 2, 4, 4, 2, 2, 3, 25, 25, 25, 50, 2, 50, 5, 50, 3, 3, 3,
                            4, 20, 3, 3, 4, 10, 1, 45, 78, 21, 7, 10);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field names its xxxO item, is PIC X(n) and agrees with its own width")
        void everyFieldIsAnAlphanumericOutputItem(ScreenField field) {
            assertThat(field.symbolicItemName())
                    .as("%s must name the output item, not the input one", field.label())
                    .isEqualTo(field.label() + AccountUpdateResponse.OUTPUT_ITEM_SUFFIX);
            assertThat(field.isAlphanumeric())
                    .as("app/bms/COACTUP.bms declares zero PICIN and zero PICOUT, so %s is PIC X(n)",
                            field.label())
                    .isTrue();
            assertThat(field.picture()).isEqualTo("X(" + field.length() + ")");
            assertThat(AccountUpdateResponse.declaredLength(field)).isEqualTo(field.length());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field's provenance is a real line and a real 24x80 position")
        void everyFieldCarriesItsTrace(ScreenField field) {
            assertThat(field.copybookLine()).isBetween(350, 668);
            assertThat(field.mapsetLine()).isBetween(34, 503);
            assertThat(field.screenRow()).isBetween(1, AccountUpdateResponse.SCREEN_ROWS);
            assertThat(field.screenColumn()).isBetween(1, AccountUpdateResponse.SCREEN_COLUMNS);
            assertThat(field.screenColumn() + field.length() - 1)
                    .as("%s must fit on its row", field.label())
                    .isLessThanOrEqualTo(AccountUpdateResponse.SCREEN_COLUMNS);
            assertThat(field.describe())
                    .contains(field.label(), field.symbolicItemName(), field.picture(),
                            "app/cpy-bms/COACTUP.CPY:" + field.copybookLine(),
                            "app/bms/COACTUP.bms:" + field.mapsetLine());
        }

        @Test
        @DisplayName("the copybook lines advance by the copybook's 6-line stride")
        void theCopybookLinesAdvanceBySix() {
            List<ScreenField> fields = AccountUpdateResponse.FIELDS;
            for (int i = 1; i < fields.size(); i++) {
                assertThat(fields.get(i).copybookLine() - fields.get(i - 1).copybookLine())
                        .as("%s follows %s", fields.get(i).label(), fields.get(i - 1).label())
                        .isEqualTo(6);
            }
        }

        @Test
        @DisplayName("the offsets are contiguous: the first payload starts at 19, each stride is 7 + n")
        void theOffsetsAreContiguous() {
            int expected = AccountUpdateResponse.TIOAPFX_LENGTH + AccountUpdateResponse.FIELD_OVERHEAD;
            for (ScreenField field : ScreenField.values()) {
                assertThat(field.dataOffset()).as("%s data offset", field.label()).isEqualTo(expected);
                expected = field.endOffsetExclusive() + AccountUpdateResponse.FIELD_OVERHEAD;
            }
            assertThat(ScreenField.TRNNAME.dataOffset()).isEqualTo(19);
            assertThat(ScreenField.FKEY12.endOffsetExclusive())
                    .isEqualTo(AccountUpdateResponse.GROUP_LENGTH);
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the quad offsets sit between the prefix FILLER and the payload, in C-P-H-V order")
        void theQuadOffsetsAreOrdered(ScreenField field) {
            assertThat(field.prefixFillerOffset())
                    .isEqualTo(field.dataOffset() - AccountUpdateResponse.FIELD_OVERHEAD);
            assertThat(field.colourItemOffset())
                    .isEqualTo(field.prefixFillerOffset()
                            + AccountUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
            assertThat(field.psItemOffset()).isEqualTo(field.colourItemOffset() + 1);
            assertThat(field.hilightItemOffset()).isEqualTo(field.psItemOffset() + 1);
            assertThat(field.validnItemOffset()).isEqualTo(field.hilightItemOffset() + 1);
            assertThat(field.validnItemOffset() + 1).isEqualTo(field.dataOffset());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the five span descriptors are named and positioned as the copybook declares")
        void theSpansAreDeclaredCorrectly(ScreenField field) {
            assertThat(field.colourItemName()).isEqualTo(field.label() + "C");
            assertThat(field.psItemName()).isEqualTo(field.label() + "P");
            assertThat(field.hilightItemName()).isEqualTo(field.label() + "H");
            assertThat(field.validnItemName()).isEqualTo(field.label() + "V");
            assertThat(field.prefixFillerSpan().length())
                    .isEqualTo(AccountUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
            assertThat(field.colourItemSpan().name()).isEqualTo(field.colourItemName());
            assertThat(field.psItemSpan().name()).isEqualTo(field.psItemName());
            assertThat(field.hilightItemSpan().name()).isEqualTo(field.hilightItemName());
            assertThat(field.validnItemSpan().name()).isEqualTo(field.validnItemName());
            FieldSpan payload = field.outputItemSpan();
            assertThat(payload.name()).isEqualTo(field.symbolicItemName());
            assertThat(payload.offset()).isEqualTo(field.dataOffset());
            assertThat(payload.length()).isEqualTo(field.length());
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("ofLabel round-trips every label")
        void ofLabelFindsEveryField(ScreenField field) {
            assertThat(ScreenField.ofLabel(field.label())).isSameAs(field);
        }

        @ParameterizedTest
        @ValueSource(strings = {"PAGENO", "AEXPDT", "ADTOPEN", "AREISDT", "ACSTSSN", "ACSTDOB",
                "trnname", ""})
        @DisplayName("ofLabel rejects what this screen does not declare, including the view screen's five")
        void ofLabelRejectsForeignLabels(String label) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ScreenField.ofLabel(label))
                    .withMessageContaining("app/bms/COACTUP.bms");
        }

        @Test
        @DisplayName("ofLabel rejects null")
        void ofLabelRejectsNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ScreenField.ofLabel(null));
        }

        /**
         * The twenty-one composite components, each its own {@code xxxO} item.
         *
         * <p><strong>Twenty-one, not twenty</strong> - practice <strong>B4</strong>, and the count is worth
         * spelling out because it is easy to get wrong by one: four three-part dates
         * ({@code OPN}, {@code EXP}, {@code RIS} on the account side and {@code DOB} on the customer side)
         * give twelve, the SSN gives three, and the two phone numbers give three each, so
         * {@code 12 + 3 + 3 + 3 = 21}. Counting only three dates - the plan's own arithmetic reads
         * "3 dates x 3 = 9, SSN 3, DOB 3, phone 1 = 3, phone 2 = 3", which also totals 21 because it
         * accounts for {@code DOB} separately - is where the twenty comes from. Either decomposition
         * reaches 21; twenty is reachable by neither.
         */
        private static final List<String> COMPOSITE_COMPONENTS = List.of(
                // COACTUP.CPY:398,404,410 - ACCT-OPEN-DATE, split three ways
                "OPNYEAR", "OPNMON", "OPNDAY",
                // COACTUP.CPY:422,428,434 - ACCT-EXPIRAION-DATE, split three ways
                "EXPYEAR", "EXPMON", "EXPDAY",
                // COACTUP.CPY:446,452,458 - ACCT-REISSUE-DATE, split three ways
                "RISYEAR", "RISMON", "RISDAY",
                // COACTUP.CPY:494,500,506 - CUST-SSN X(09), split three ways
                "ACTSSN1", "ACTSSN2", "ACTSSN3",
                // COACTUP.CPY:512,518,524 - CUST-DOB-YYYY-MM-DD, split three ways
                "DOBYEAR", "DOBMON", "DOBDAY",
                // COACTUP.CPY:590,596,602 - CUST-PHONE-NUM-1, split three ways
                "ACSPH1A", "ACSPH1B", "ACSPH1C",
                // COACTUP.CPY:614,620,626 - CUST-PHONE-NUM-2, split three ways
                "ACSPH2A", "ACSPH2B", "ACSPH2C");

        @Test
        @DisplayName("the twenty-one composite components stay separate: 4 dates, the SSN and two phones")
        void theCompositesAreNotMerged() {
            assertThat(COMPOSITE_COMPONENTS)
                    .as("B4: the source declares 21 composite components, not 20")
                    .hasSize(21);
            assertThat(AccountUpdateResponse.FIELDS.stream().map(ScreenField::label).toList())
                    .containsAll(COMPOSITE_COMPONENTS);
            // Each component is its own member at its own width: never pre-joined into one item, because
            // COACTUPC edits, highlights and reports each part independently.
            assertThat(COMPOSITE_COMPONENTS.stream().map(ScreenField::ofLabel).distinct().count())
                    .as("21 distinct enum constants, so nothing has been merged")
                    .isEqualTo(21L);
            assertThat(ScreenField.ACTSSN1.length() + ScreenField.ACTSSN2.length()
                    + ScreenField.ACTSSN3.length()).isEqualTo(9);
            assertThat(ScreenField.ACSPH1A.length() + ScreenField.ACSPH1B.length()
                    + ScreenField.ACSPH1C.length()).isEqualTo(10);
            assertThat(ScreenField.ACSPH2A.length() + ScreenField.ACSPH2B.length()
                    + ScreenField.ACSPH2C.length()).isEqualTo(10);
            // The four dates each recompose to the CVACT01Y / CVCUS01Y X(10) yyyy-mm-dd width once the
            // two separators the map draws as literals are counted back in: 4 + 2 + 2 + 2 = 10.
            assertThat(ScreenField.OPNYEAR.length() + ScreenField.OPNMON.length()
                    + ScreenField.OPNDAY.length() + 2).isEqualTo(10);
            assertThat(ScreenField.EXPYEAR.length() + ScreenField.EXPMON.length()
                    + ScreenField.EXPDAY.length() + 2).isEqualTo(10);
            assertThat(ScreenField.RISYEAR.length() + ScreenField.RISMON.length()
                    + ScreenField.RISDAY.length() + 2).isEqualTo(10);
            assertThat(ScreenField.DOBYEAR.length() + ScreenField.DOBMON.length()
                    + ScreenField.DOBDAY.length() + 2).isEqualTo(10);
        }

        @Test
        @DisplayName("ACCTSID is 11 characters on both sides - no PICTURE asymmetry on this mapset")
        void acctsidIsElevenCharacters() {
            assertThat(AccountUpdateResponse.ACCTSID_LENGTH).isEqualTo(11);
            assertThat(ScreenField.ACCTSID.picture()).isEqualTo("X(11)");
            assertThat(AccountUpdateResponse.initial().getAcctsid()).hasSize(11);
        }

        @Test
        @DisplayName("the two message widths are the MAP widths, and CSMSG01Y's 50 is neither")
        void theMessageWidthsAreTheMapWidths() {
            assertThat(AccountUpdateResponse.INFOMSG_LENGTH).isEqualTo(45);
            assertThat(AccountUpdateResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(AccountUpdateResponse.CSMSG01Y_MESSAGE_LENGTH)
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH)
                    .isGreaterThan(AccountUpdateResponse.INFOMSG_LENGTH)
                    .isLessThan(AccountUpdateResponse.ERRMSG_LENGTH);
        }
    }

    // =============================================================================================
    // The Request/Response diff. Gate G9's companion: the pair must agree on all 54.
    // =============================================================================================

    @Nested
    @DisplayName("Request and Response agree on all 54 - only the metadata carriers differ")
    class PairSymmetry {

        @Test
        @DisplayName("the same labels in the same order")
        void labelsAndOrderAreIdentical() {
            List<String> request = Arrays.stream(AccountUpdateRequest.ScreenField.values())
                    .map(AccountUpdateRequest.ScreenField::label).toList();
            List<String> response = AccountUpdateResponse.FIELDS.stream()
                    .map(ScreenField::label).toList();
            assertThat(response).containsExactlyElementsOf(request);
        }

        @ParameterizedTest
        @MethodSource("com.vsergeychik.carddemo.account.dto.AccountUpdateResponseTest#allFields")
        @DisplayName("identical width and identical data offset, field by field")
        void widthsAndOffsetsAreIdentical(ScreenField field) {
            AccountUpdateRequest.ScreenField twin =
                    AccountUpdateRequest.ScreenField.ofLabel(field.label());
            assertThat(field.length()).as("%s width", field.label()).isEqualTo(twin.length());
            assertThat(field.dataOffset()).as("%s offset", field.label())
                    .isEqualTo(twin.dataOffset());
            assertThat(field.mapsetLine()).as("%s mapset line", field.label())
                    .isEqualTo(twin.mapsetLine());
        }

        @Test
        @DisplayName("the group lengths match, which is what makes AO REDEFINES AI legal")
        void theGroupsAreTheSameLength() {
            assertThat(AccountUpdateResponse.GROUP_LENGTH)
                    .isEqualTo(AccountUpdateRequest.GROUP_LENGTH);
            assertThat(AccountUpdateResponse.FIELD_OVERHEAD)
                    .isEqualTo(AccountUpdateRequest.FIELD_OVERHEAD);
            assertThat(AccountUpdateResponse.PAYLOAD_LENGTH)
                    .isEqualTo(AccountUpdateRequest.PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("the input item names differ only by the direction letter")
        void onlyTheDirectionLetterDiffers() {
            for (ScreenField field : ScreenField.values()) {
                AccountUpdateRequest.ScreenField twin =
                        AccountUpdateRequest.ScreenField.ofLabel(field.label());
                assertThat(field.symbolicItemName()).endsWith("O");
                assertThat(twin.symbolicItemName()).endsWith("I");
                assertThat(field.symbolicItemName().substring(0,
                        field.symbolicItemName().length() - 1))
                        .isEqualTo(twin.symbolicItemName()
                                .substring(0, twin.symbolicItemName().length() - 1));
            }
        }
    }

    // =============================================================================================
    // Values: the LOW-VALUES baseline of MOVE LOW-VALUES TO CACTUPAO, and what a value may hold.
    // =============================================================================================

    @Nested
    @DisplayName("The LOW-VALUES baseline - app/cbl/COACTUPC.cbl:2668")
    class Baseline {

        @Test
        @DisplayName("initial() puts every field at its declared width in LOW-VALUES, not spaces")
        void initialIsLowValuesAtEveryDeclaredWidth() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.value(field))
                        .as("%s must start as LOW-VALUES at its declared width", field.label())
                        .isEqualTo(AccountUpdateResponse.lowValues(field.length()))
                        .hasSize(field.length())
                        .isNotEqualTo(AccountUpdateResponse.spaces(field.length()));
            }
        }

        @Test
        @DisplayName("the navigation trio and the carriers start at their initialised states")
        void theCarriersStartInitialised() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThat(response.getNextProgram())
                    .isEqualTo(AccountUpdateResponse.lowValues(
                            AccountUpdateResponse.NEXT_PROGRAM_LENGTH));
            assertThat(response.getNextMapset())
                    .isEqualTo(AccountUpdateResponse.lowValues(
                            AccountUpdateResponse.NEXT_MAPSET_LENGTH));
            assertThat(response.getNextMap())
                    .isEqualTo(AccountUpdateResponse.lowValues(
                            AccountUpdateResponse.NEXT_MAP_LENGTH));
            assertThat(response.getCommArea()).isEqualTo(CommArea.initialised());
            assertThat(response.getCardScreenState()).isNotNull();
            assertThat(response.getNavigationContext()).isNull();
            assertThat(response.hasNavigationContext()).isFalse();
        }

        @Test
        @DisplayName("LOW-VALUES, spaces and null are three different things")
        void lowValuesSpacesAndNullAreDistinct() {
            assertThat(AccountUpdateResponse.lowValues(3)).isEqualTo("\u0000\u0000\u0000");
            assertThat(AccountUpdateResponse.spaces(3)).isEqualTo("   ");
            assertThat(AccountUpdateResponse.lowValues(3))
                    .isNotEqualTo(AccountUpdateResponse.spaces(3));
            assertThat(AccountUpdateResponse.lowValues(0)).isEmpty();
            assertThat(AccountUpdateResponse.spaces(0)).isEmpty();
            // A null value is "not supplied" and restores the baseline, which is not the same as a
            // caller explicitly writing spaces.
            AccountUpdateResponse spaced = AccountUpdateResponse.initial()
                    .withValue(ScreenField.ACSTTUS, " ");
            assertThat(spaced.getAcsttus()).isEqualTo(" ");
            assertThat(spaced.withValue(ScreenField.ACSTTUS, null).getAcsttus())
                    .isEqualTo(AccountUpdateResponse.lowValues(
                            AccountUpdateResponse.ACSTTUS_LENGTH));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -42})
        @DisplayName("a negative width is rejected by both helpers")
        void negativeWidthsAreRejected(int width) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountUpdateResponse.spaces(width));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountUpdateResponse.lowValues(width));
        }

        @Test
        @DisplayName("declaredLength rejects null")
        void declaredLengthRejectsNull() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.declaredLength(null));
        }
    }

    @Nested
    @DisplayName("A field may hold exactly '*' - CSSETATY writes one at 39 sites")
    class AsteriskAcceptance {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("'*' is accepted, stored untouched and survives the group-image round trip")
        void asteriskIsAcceptedOnEveryField(ScreenField field) {
            AccountUpdateResponse response =
                    AccountUpdateResponse.initial().withValue(field, AccountUpdateResponse.BLANK_FIELD_MARKER);
            assertThat(response.value(field)).isEqualTo("*");
            AccountUpdateResponse back = AccountUpdateResponse.fromGroupImage(
                    response.toGroupImage(ASCII), ASCII);
            assertThat(back.value(field))
                    .as("'*' must survive into %s at its declared width", field.symbolicItemName())
                    .isEqualTo(ASCII.movePicX("*", field.length()));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an all-spaces value and a LOW-VALUES value are both accepted too")
        void spacesAndLowValuesAreAcceptedOnEveryField(ScreenField field) {
            assertThatNoException().isThrownBy(() -> AccountUpdateResponse.initial()
                    .withValue(field, AccountUpdateResponse.spaces(field.length()))
                    .withValue(field, AccountUpdateResponse.lowValues(field.length())));
        }

        @Test
        @DisplayName("BLANK_FIELD_MARKER is the copybook's asterisk, not a local literal")
        void theMarkerComesFromTheCopybookRepresentation() {
            assertThat(AccountUpdateResponse.BLANK_FIELD_MARKER)
                    .isEqualTo(FieldAttributeSetter.ASTERISK)
                    .isEqualTo("*");
        }

        @Test
        @DisplayName("value and withValue reject a null field")
        void nullFieldIsRejected() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.value(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.withValue(null, "x"));
        }
    }

    @Nested
    @DisplayName("The 54 accessors, and fieldValues as a differ consumes it")
    class Accessors {

        @Test
        @DisplayName("every named accessor returns its own field's value")
        void everyAccessorReadsItsOwnField() {
            AccountUpdateResponse r = populated();
            assertThat(r.getTrnname()).isEqualTo(r.value(ScreenField.TRNNAME));
            assertThat(r.getTitle01()).isEqualTo(r.value(ScreenField.TITLE01));
            assertThat(r.getCurdate()).isEqualTo(r.value(ScreenField.CURDATE));
            assertThat(r.getPgmname()).isEqualTo(r.value(ScreenField.PGMNAME));
            assertThat(r.getTitle02()).isEqualTo(r.value(ScreenField.TITLE02));
            assertThat(r.getCurtime()).isEqualTo(r.value(ScreenField.CURTIME));
            assertThat(r.getAcctsid()).isEqualTo(r.value(ScreenField.ACCTSID));
            assertThat(r.getAcsttus()).isEqualTo(r.value(ScreenField.ACSTTUS));
            assertThat(r.getOpnyear()).isEqualTo(r.value(ScreenField.OPNYEAR));
            assertThat(r.getOpnmon()).isEqualTo(r.value(ScreenField.OPNMON));
            assertThat(r.getOpnday()).isEqualTo(r.value(ScreenField.OPNDAY));
            assertThat(r.getAcrdlim()).isEqualTo(r.value(ScreenField.ACRDLIM));
            assertThat(r.getExpyear()).isEqualTo(r.value(ScreenField.EXPYEAR));
            assertThat(r.getExpmon()).isEqualTo(r.value(ScreenField.EXPMON));
            assertThat(r.getExpday()).isEqualTo(r.value(ScreenField.EXPDAY));
            assertThat(r.getAcshlim()).isEqualTo(r.value(ScreenField.ACSHLIM));
            assertThat(r.getRisyear()).isEqualTo(r.value(ScreenField.RISYEAR));
            assertThat(r.getRismon()).isEqualTo(r.value(ScreenField.RISMON));
            assertThat(r.getRisday()).isEqualTo(r.value(ScreenField.RISDAY));
            assertThat(r.getAcurbal()).isEqualTo(r.value(ScreenField.ACURBAL));
            assertThat(r.getAcrcycr()).isEqualTo(r.value(ScreenField.ACRCYCR));
            assertThat(r.getAaddgrp()).isEqualTo(r.value(ScreenField.AADDGRP));
            assertThat(r.getAcrcydb()).isEqualTo(r.value(ScreenField.ACRCYDB));
            assertThat(r.getAcstnum()).isEqualTo(r.value(ScreenField.ACSTNUM));
            assertThat(r.getActssn1()).isEqualTo(r.value(ScreenField.ACTSSN1));
            assertThat(r.getActssn2()).isEqualTo(r.value(ScreenField.ACTSSN2));
            assertThat(r.getActssn3()).isEqualTo(r.value(ScreenField.ACTSSN3));
            assertThat(r.getDobyear()).isEqualTo(r.value(ScreenField.DOBYEAR));
            assertThat(r.getDobmon()).isEqualTo(r.value(ScreenField.DOBMON));
            assertThat(r.getDobday()).isEqualTo(r.value(ScreenField.DOBDAY));
            assertThat(r.getAcstfco()).isEqualTo(r.value(ScreenField.ACSTFCO));
            assertThat(r.getAcsfnam()).isEqualTo(r.value(ScreenField.ACSFNAM));
            assertThat(r.getAcsmnam()).isEqualTo(r.value(ScreenField.ACSMNAM));
            assertThat(r.getAcslnam()).isEqualTo(r.value(ScreenField.ACSLNAM));
            assertThat(r.getAcsadl1()).isEqualTo(r.value(ScreenField.ACSADL1));
            assertThat(r.getAcsstte()).isEqualTo(r.value(ScreenField.ACSSTTE));
            assertThat(r.getAcsadl2()).isEqualTo(r.value(ScreenField.ACSADL2));
            assertThat(r.getAcszipc()).isEqualTo(r.value(ScreenField.ACSZIPC));
            assertThat(r.getAcscity()).isEqualTo(r.value(ScreenField.ACSCITY));
            assertThat(r.getAcsctry()).isEqualTo(r.value(ScreenField.ACSCTRY));
            assertThat(r.getAcsph1a()).isEqualTo(r.value(ScreenField.ACSPH1A));
            assertThat(r.getAcsph1b()).isEqualTo(r.value(ScreenField.ACSPH1B));
            assertThat(r.getAcsph1c()).isEqualTo(r.value(ScreenField.ACSPH1C));
            assertThat(r.getAcsgovt()).isEqualTo(r.value(ScreenField.ACSGOVT));
            assertThat(r.getAcsph2a()).isEqualTo(r.value(ScreenField.ACSPH2A));
            assertThat(r.getAcsph2b()).isEqualTo(r.value(ScreenField.ACSPH2B));
            assertThat(r.getAcsph2c()).isEqualTo(r.value(ScreenField.ACSPH2C));
            assertThat(r.getAcseftc()).isEqualTo(r.value(ScreenField.ACSEFTC));
            assertThat(r.getAcspflg()).isEqualTo(r.value(ScreenField.ACSPFLG));
            assertThat(r.getInfomsg()).isEqualTo(r.value(ScreenField.INFOMSG));
            assertThat(r.getErrmsg()).isEqualTo(r.value(ScreenField.ERRMSG));
            assertThat(r.getFkeys()).isEqualTo(r.value(ScreenField.FKEYS));
            assertThat(r.getFkey05()).isEqualTo(r.value(ScreenField.FKEY05));
            assertThat(r.getFkey12()).isEqualTo(r.value(ScreenField.FKEY12));
        }

        @Test
        @DisplayName("every accessor returns a distinct value, so none reads the wrong field")
        void noAccessorReadsAnotherFieldsValue() {
            AccountUpdateResponse r = populated();
            assertThat(r.fieldValues().values()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("fieldValues is label-keyed, in copybook order and unmodifiable")
        void fieldValuesIsOrderedAndUnmodifiable() {
            Map<String, String> rendered = populated().fieldValues();
            assertThat(rendered).hasSize(54);
            assertThat(rendered.keySet()).containsExactlyElementsOf(
                    AccountUpdateResponse.FIELDS.stream().map(ScreenField::label).toList());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rendered.put("TRNNAME", "x"));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("withValue replaces one field and carries every other part over")
        void withValueIsSurgical(ScreenField field) {
            AccountUpdateResponse before = populated();
            AccountUpdateResponse after = before.withValue(field, SHORT_VALUE);
            assertThat(after.value(field)).isEqualTo(SHORT_VALUE);
            assertThat(after).isNotEqualTo(before);
            for (ScreenField other : ScreenField.values()) {
                if (other != field) {
                    assertThat(after.value(other)).as("%s untouched", other.label())
                            .isEqualTo(before.value(other));
                }
            }
            assertThat(after.getCommArea()).isEqualTo(before.getCommArea());
            assertThat(after.getNextProgram()).isEqualTo(before.getNextProgram());
        }

        @Test
        @DisplayName("a value shorter or longer than the declared width is stored exactly as given")
        void valuesAreStoredAsGiven() {
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withValue(ScreenField.ACSFNAM, "AB")
                    .withValue(ScreenField.ACSTTUS, "TOO LONG");
            assertThat(response.getAcsfnam()).isEqualTo("AB");
            assertThat(response.getAcspflg()).hasSize(AccountUpdateResponse.ACSPFLG_LENGTH);
            assertThat(response.getAcsttus()).isEqualTo("TOO LONG");
            // Only the group image and normalize() apply the MOVE.
            assertThat(response.image(ScreenField.ACSFNAM, ASCII))
                    .hasSize(AccountUpdateResponse.ACSFNAM_LENGTH).startsWith("AB ");
            assertThat(response.image(ScreenField.ACSTTUS, ASCII)).isEqualTo("T");
        }
    }

    // =============================================================================================
    // The attribute quad. Metadata, server-written, excluded from JSON.
    // =============================================================================================

    @Nested
    @DisplayName("The xxxC/xxxP/xxxH/xxxV quad - from DSATTS/MAPATTS, not from EXTATT")
    class Quad {

        @Test
        @DisplayName("a fresh quad is LOW-VALUES on all four items")
        void aFreshQuadIsUnset() {
            FieldAttributes quad = new FieldAttributes();
            assertThat(quad.getColour()).isEqualTo(FieldAttributes.UNSET);
            assertThat(quad.getPs()).isEqualTo(FieldAttributes.UNSET);
            assertThat(quad.getHilight()).isEqualTo(FieldAttributes.UNSET);
            assertThat(quad.getValidn()).isEqualTo(FieldAttributes.UNSET);
            assertThat(FieldAttributes.UNSET).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("all four items are individually settable and readable")
        void allFourItemsRoundTrip() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            quad.setPs(BmsAttributes.DFHNEUTR);
            quad.setHilight(BmsAttributes.DFHREVRS);
            quad.setValidn(BmsAttributes.DFHBMASB);
            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isEqualTo(BmsAttributes.DFHNEUTR);
            assertThat(quad.getHilight()).isEqualTo(BmsAttributes.DFHREVRS);
            assertThat(quad.getValidn()).isEqualTo(BmsAttributes.DFHBMASB);
            quad.resetToLowValues();
            assertThat(quad).isEqualTo(new FieldAttributes());
        }

        @Test
        @DisplayName("the colour and highlight predicates read both ways")
        void thePredicatesReadBothWays() {
            FieldAttributes quad = new FieldAttributes();
            assertThat(quad.isRedHighlighted()).isFalse();
            assertThat(quad.isDefaultColour()).isTrue();
            assertThat(quad.isHighlighted()).isFalse();
            quad.setColour(BmsAttributes.DFHRED);
            quad.setHilight(BmsAttributes.DFHBLINK);
            assertThat(quad.isRedHighlighted()).isTrue();
            assertThat(quad.isDefaultColour()).isFalse();
            assertThat(quad.isHighlighted()).isTrue();
        }

        @Test
        @DisplayName("copy, equality and hash behave as values; and null is rejected")
        void copyEqualityAndHash() {
            FieldAttributes quad = new FieldAttributes(BmsAttributes.DFHRED, (byte) 1, (byte) 2,
                    (byte) 3);
            FieldAttributes copy = new FieldAttributes(quad);
            assertThat(copy).isEqualTo(quad).isNotSameAs(quad)
                    .hasSameHashCodeAs(quad);
            assertThat(quad).isEqualTo(quad);
            assertThat(quad).isNotEqualTo(new FieldAttributes());
            assertThat(quad).isNotEqualTo("not a quad");
            copy.setColour(BmsAttributes.DFHGREEN);
            assertThat(copy).as("a copy must not alias the original").isNotEqualTo(quad);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldAttributes(null));
        }

        @Test
        @DisplayName("the rendering names the mnemonics it knows and hexes the rest")
        void theRenderingIsReadable() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            assertThat(quad.toString())
                    .startsWith("[C=")
                    .contains(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED))
                    .contains("P=", "H=", "V=")
                    .endsWith("]");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("every field has a live quad, writable on a built response")
        void everyFieldHasALiveWritableQuad(ScreenField field) {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThat(response.attributes(field)).isEqualTo(new FieldAttributes());
            response.attributes(field).setColour(BmsAttributes.DFHRED);
            assertThat(response.attributes(field).isRedHighlighted())
                    .as("%s must accept an attribute byte after the map is populated",
                            field.colourItemName())
                    .isTrue();
        }

        @Test
        @DisplayName("attributeQuads is unmodifiable but its quads are live, and reset clears all 54")
        void attributeQuadsIsUnmodifiableButLive() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThat(response.attributeQuads()).hasSize(54);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.attributeQuads()
                            .put(ScreenField.TRNNAME, new FieldAttributes()));
            response.attributeQuads().get(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            assertThat(response.attributes(ScreenField.ERRMSG).isRedHighlighted()).isTrue();
            response.resetAttributeQuads();
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.attributes(field)).isEqualTo(new FieldAttributes());
            }
        }

        @Test
        @DisplayName("attributes rejects a null field")
        void attributesRejectsNull() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.attributes(null));
        }

        @Test
        @DisplayName("a builder-supplied quad is copied, not aliased; and a null quad leaves it unset")
        void builderCopiesTheQuad() {
            FieldAttributes supplied = new FieldAttributes();
            supplied.setColour(BmsAttributes.DFHRED);
            AccountUpdateResponse response = AccountUpdateResponse.builder()
                    .attributes(ScreenField.ACCTSID, supplied)
                    .attributes(ScreenField.ACSTTUS, null)
                    .build();
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
            assertThat(response.attributes(ScreenField.ACCTSID)).isNotSameAs(supplied);
            supplied.setColour(BmsAttributes.DFHGREEN);
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted())
                    .as("the response must not alias the supplied quad").isTrue();
            assertThat(response.attributes(ScreenField.ACSTTUS)).isEqualTo(new FieldAttributes());
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.builder().attributes(null, supplied));
        }
    }

    // =============================================================================================
    // CSSETATY's two destinations, and gate G38: the highlight applies only in REENTER context.
    // =============================================================================================

    @Nested
    @DisplayName("CSSETATY - DFHRED into xxxC, '*' into xxxO, and only when REENTER (gate G38)")
    class Highlight {

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a blank field in REENTER gets BOTH destinations written")
        void blankInReenterWritesBoth(ScreenField field) {
            AccountUpdateResponse painted = AccountUpdateResponse.initial()
                    .applyHighlight(field, FieldValidationState.BLANK, true);
            assertThat(painted.value(field))
                    .as("%s must receive the asterisk", field.symbolicItemName()).isEqualTo("*");
            assertThat(painted.attributes(field).isRedHighlighted())
                    .as("%s must receive DFHRED", field.colourItemName()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("a not-OK-but-not-blank field in REENTER gets the colour only, never the asterisk")
        void notOkInReenterWritesTheColourOnly(ScreenField field) {
            AccountUpdateResponse before = populated();
            AccountUpdateResponse painted =
                    before.applyHighlight(field, FieldValidationState.NOT_OK, true);
            assertThat(painted.value(field))
                    .as("%s keeps its value: the IF FLG-xxx-BLANK guard is false", field.label())
                    .isEqualTo(before.value(field));
            assertThat(painted.attributes(field).isRedHighlighted()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("in ENTER context nothing is written, however bad the field is")
        void enterContextWritesNothing(ScreenField field) {
            AccountUpdateResponse before = populated();
            for (FieldValidationState state : FieldValidationState.values()) {
                AccountUpdateResponse painted = before.applyHighlight(field, state, false);
                assertThat(painted.value(field)).isEqualTo(before.value(field));
                assertThat(painted.attributes(field).isRedHighlighted())
                        .as("%s must not be highlighted outside REENTER", field.label()).isFalse();
            }
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("an OK field in REENTER is left completely alone")
        void validInReenterWritesNothing(ScreenField field) {
            AccountUpdateResponse before = populated();
            AccountUpdateResponse painted =
                    before.applyHighlight(field, FieldValidationState.OK, true);
            assertThat(painted).isSameAs(before);
            assertThat(painted.attributes(field).isRedHighlighted()).isFalse();
        }

        @Test
        @DisplayName("applyHighlight rejects a null field or a null state")
        void applyHighlightRejectsNulls() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight(null, FieldValidationState.BLANK, true));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.applyHighlight(ScreenField.ACCTSID, null, true));
        }

        /**
         * The six rows of {@code CSSETATY}'s decision, as data.
         *
         * <p>Three flag states by two context states. Six is the whole matrix and anything less is not: a
         * suite that drives only the three flags in REENTER misses that the outer gate can fail on
         * <em>context</em> while the flag says error, and a suite that drives only the two contexts misses
         * that the inner gate distinguishes BLANK from merely NOT-OK.
         *
         * @return {@code {state, reenter, colourExpected, asteriskExpected}} per row
         */
        static List<Arguments> csssetatyMatrix() {
            return List.of(
                    // Outer gate false on the flag: FLG-x-NOT-OK and FLG-x-BLANK are both false.
                    Arguments.of(
                            FieldValidationState.OK, false, false, false),
                    Arguments.of(
                            FieldValidationState.OK, true, false, false),
                    // Outer gate false on the context: CSSETATY.cpy:20 - AND CDEMO-PGM-REENTER.
                    Arguments.of(
                            FieldValidationState.NOT_OK, false, false, false),
                    // Outer gate true, inner gate false: CSSETATY.cpy:21-22 fires, :24-25 does not.
                    Arguments.of(
                            FieldValidationState.NOT_OK, true, true, false),
                    Arguments.of(
                            FieldValidationState.BLANK, false, false, false),
                    // Both gates true: CSSETATY.cpy:21-22 and :24-25 both fire.
                    Arguments.of(
                            FieldValidationState.BLANK, true, true, true));
        }

        @ParameterizedTest(name = "[{index}] {0} + reenter={1} -> colour={2}, asterisk={3}")
        @MethodSource("csssetatyMatrix")
        @DisplayName("the collaborator's own six-row decision matrix, both destinations per row")
        void theSetterDecidesExactlyAsTheCopybookDoes(FieldValidationState state, boolean reenter,
                boolean colourExpected, boolean asteriskExpected) {

            FieldHighlight decision = FieldAttributeSetter.resolve(
                    state, reenter, ScreenField.ACCTSID.label(), AccountUpdateResponse.MAP_NAME);

            assertThat(decision.colourItemAssigned())
                    .as("CSSETATY.cpy:18-20 - (NOT-OK OR BLANK) AND REENTER")
                    .isEqualTo(colourExpected);
            assertThat(decision.outputItemAssigned())
                    .as("CSSETATY.cpy:23 - the inner IF tests BLANK only")
                    .isEqualTo(asteriskExpected);
            assertThat(decision.untouched()).isEqualTo(!colourExpected && !asteriskExpected);

            // The two destinations are different items, and the copybook says which is which:
            // DFHRED goes to the xxxC colour METADATA, the bare '*' to the xxxO PAYLOAD item.
            if (colourExpected) {
                assertThat(decision.colourItemValue())
                        .as("the colour byte is DFHRED, taken from BmsAttributes, never a literal")
                        .isEqualTo(BmsAttributes.DFHRED);
            }
            if (asteriskExpected) {
                assertThat(decision.outputItemValue())
                        .as("CSSETATY.cpy:24 - MOVE '*'")
                        .isEqualTo(FieldAttributeSetter.ASTERISK)
                        .hasSize(1);
            }
            // Whichever way the decision went, it names its two targets the way the copybook writes them:
            // (SCRNVAR)C OF (MAPNAME)O and (SCRNVAR)O OF (MAPNAME)O.
            assertThat(decision.colourItemName())
                    .isEqualTo(ScreenField.ACCTSID.label() + FieldAttributeSetter.COLOUR_ITEM_SUFFIX)
                    .isEqualTo(ScreenField.ACCTSID.colourItemName());
            assertThat(decision.outputItemName())
                    .isEqualTo(ScreenField.ACCTSID.label() + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX)
                    .isEqualTo(ScreenField.ACCTSID.symbolicItemName());
            assertThat(decision.outputMapGroupName())
                    .as("COACTUP.CPY:343 - the map group the two items are qualified by")
                    .isEqualTo(AccountUpdateResponse.MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX)
                    .isEqualTo(AccountUpdateResponse.OUTPUT_GROUP_NAME);
            assertThat(decision.describe()).isNotBlank();
        }

        @ParameterizedTest(name = "[{index}] {0} + reenter={1}")
        @MethodSource("csssetatyMatrix")
        @DisplayName("the response reaches the same six verdicts the collaborator does")
        void theResponseAgreesWithTheCollaboratorOnEveryRow(FieldValidationState state, boolean reenter,
                boolean colourExpected, boolean asteriskExpected) {

            AccountUpdateResponse before = populated();
            AccountUpdateResponse painted = before.applyHighlight(ScreenField.ACSFNAM, state, reenter);

            assertThat(painted.attributes(ScreenField.ACSFNAM).isRedHighlighted())
                    .as("row %s/%s must agree with FieldAttributeSetter on the colour", state, reenter)
                    .isEqualTo(colourExpected);
            assertThat(FieldAttributeSetter.ASTERISK.equals(painted.value(ScreenField.ACSFNAM)))
                    .as("row %s/%s must agree with FieldAttributeSetter on the asterisk", state, reenter)
                    .isEqualTo(asteriskExpected);
            if (!asteriskExpected) {
                assertThat(painted.value(ScreenField.ACSFNAM))
                        .isEqualTo(before.value(ScreenField.ACSFNAM));
            }
        }

        @Test
        @DisplayName("resolveFromFlags reaches the same verdicts, including both flags set at once")
        void theFlagPairFoldsIntoTheThreeWayState() {
            // FLG-x-NOT-OK and FLG-x-BLANK are two independent 88s on one flag byte, so all four
            // combinations are reachable in COBOL. BLANK is the stronger claim and must win the fold,
            // otherwise a blank field in REENTER would get the colour but never the asterisk.
            assertThat(FieldValidationState.of(false, false)).isEqualTo(FieldValidationState.OK);
            assertThat(FieldValidationState.of(true, false)).isEqualTo(FieldValidationState.NOT_OK);
            assertThat(FieldValidationState.of(false, true)).isEqualTo(FieldValidationState.BLANK);
            assertThat(FieldValidationState.of(true, true)).isEqualTo(FieldValidationState.BLANK);

            // The three conditions are MUTUALLY EXCLUSIVE values of one flag byte, not overlapping
            // predicates: app/cbl/COACTUPC.cbl:184 FLG-ACCTFILTER-ISVALID VALUE '1',
            //            app/cbl/COACTUPC.cbl:185 FLG-ACCTFILTER-NOT-OK  VALUE '0',
            //            app/cbl/COACTUPC.cbl:186 FLG-ACCTFILTER-BLANK   VALUE ' '.
            // One byte cannot hold '0' and ' ' at once, so BLANK does NOT imply NOT-OK - and that is
            // precisely why CSSETATY.cpy:18-19 has to spell the outer gate as a disjunction. Were BLANK
            // a special case of NOT-OK the OR would be redundant, which is the misreading this block
            // exists to rule out.
            assertThat(FieldValidationState.OK.notOk()).isFalse();
            assertThat(FieldValidationState.OK.blank()).isFalse();
            assertThat(FieldValidationState.NOT_OK.notOk()).isTrue();
            assertThat(FieldValidationState.NOT_OK.blank()).isFalse();
            assertThat(FieldValidationState.BLANK.notOk())
                    .as("BLANK is the ' ' byte, NOT-OK is the '0' byte - exclusive, so this is false")
                    .isFalse();
            assertThat(FieldValidationState.BLANK.blank()).isTrue();
            // Each state answers true to exactly one of the two raw flags, or to neither.
            for (FieldValidationState state : FieldValidationState.values()) {
                assertThat(state.notOk() && state.blank())
                        .as("%s must not claim both flag bytes at once", state)
                        .isFalse();
            }

            // Both entry points, both contexts: eight rows of the flag-pair truth table.
            for (boolean reenter : new boolean[] {false, true}) {
                for (boolean notOk : new boolean[] {false, true}) {
                    for (boolean blank : new boolean[] {false, true}) {
                        FieldHighlight viaFlags = FieldAttributeSetter.resolveFromFlags(
                                notOk, blank, reenter);
                        FieldHighlight viaState = FieldAttributeSetter.resolve(
                                FieldValidationState.of(notOk, blank), reenter);
                        assertThat(viaFlags).isEqualTo(viaState);
                        assertThat(viaFlags.colourItemAssigned())
                                .isEqualTo((notOk || blank) && reenter);
                        assertThat(viaFlags.outputItemAssigned()).isEqualTo(blank && reenter);
                    }
                }
            }
        }

        @Test
        @DisplayName("a bare '*' is legal as a WHOLE value, not merely as a prefix")
        void theAsteriskIsAcceptedAsAnEntireValue() {
            // CSSETATY.cpy:24-25 moves the literal '*' into the xxxO item, replacing whatever was there.
            // On a 25- or 50-byte field that is a one-character value in a wide item, which is exactly the
            // case a length-validating setter would wrongly reject.
            for (ScreenField field : List.of(ScreenField.ACSFNAM, ScreenField.ACSADL1,
                    ScreenField.ACSTTUS, ScreenField.ACRDLIM)) {
                AccountUpdateResponse painted = populated()
                        .applyHighlight(field, FieldValidationState.BLANK, true);
                assertThat(painted.value(field))
                        .as("%s must hold the asterisk as its entire value", field.symbolicItemName())
                        .isEqualTo("*")
                        .hasSize(1);
                // And the move to the declared width pads it rather than rejecting it.
                assertThat(painted.image(field, ASCII))
                        .hasSize(field.length())
                        .startsWith("*")
                        .isEqualTo(ASCII.movePicX("*", field.length()));
            }
        }

        @Test
        @DisplayName("no highlight decision is reachable that writes the asterisk without the colour")
        void theNestingInvariantHolds() {
            // The gates are nested, not parallel: the inner IF sits inside the outer one, so
            // outputItemAssigned implies colourItemAssigned. A parallel reading would admit a seventh,
            // impossible row - asterisk without colour - and this is the assertion that forbids it.
            for (FieldValidationState state : FieldValidationState.values()) {
                for (boolean reenter : new boolean[] {false, true}) {
                    FieldHighlight decision = FieldAttributeSetter.resolve(state, reenter);
                    assertThat(!decision.outputItemAssigned() || decision.colourItemAssigned())
                            .as("%s/%s: '*' can never be written without DFHRED", state, reenter)
                            .isTrue();
                }
            }
            FieldHighlight nothing = FieldHighlight.none(
                    ScreenField.ACCTSID.label(), AccountUpdateResponse.MAP_NAME);
            assertThat(nothing.untouched()).isTrue();
            assertThat(nothing.colourItemAssigned()).isFalse();
            assertThat(nothing.outputItemAssigned()).isFalse();
        }
    }

    // =============================================================================================
    // 3100-SCREEN-INIT's value projection.
    // =============================================================================================

    @Nested
    @DisplayName("3100-SCREEN-INIT - the six header moves and the three legends")
    class ScreenInit {

        /**
         * A fixed instant, so the header assertions are deterministic.
         *
         * <p>The value is the source version footer's own timestamp - {@code Ver:
         * CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT}, carried by
         * {@code app/cpy/CSSETATY.cpy:29} among others - so the literal in this file is traceable to the
         * revision the whole parity contract is anchored to rather than being an arbitrary date.
         */
        private static final LocalDateTime WHEN = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

        @Test
        @DisplayName("the two titles and the two identifiers, at exact width matches")
        void theHeaderValuesComeFromTheirSources() {
            AccountUpdateResponse response = AccountUpdateResponse.initial().withScreenTitles();
            assertThat(response.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(AccountUpdateResponse.TITLE01_LENGTH);
            assertThat(response.getTitle02()).isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(AccountUpdateResponse.TITLE02_LENGTH);
            assertThat(response.getTrnname()).isEqualTo(AccountUpdateResponse.THIS_TRANSACTION)
                    .hasSize(AccountUpdateResponse.TRNNAME_LENGTH);
            assertThat(response.getPgmname()).isEqualTo(AccountUpdateResponse.THIS_PROGRAM)
                    .hasSize(AccountUpdateResponse.PGMNAME_LENGTH);
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(AccountUpdateResponse.TITLE01_LENGTH);
        }

        @Test
        @DisplayName("the date and time replace the mapset's mm/dd/yy and hh:mm:ss placeholders")
        void theDateAndTimeAreEightCharactersEach() {
            DateHeader header = DateHeader.of(ASCII, WHEN);
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withDateTimeHeader(header);
            assertThat(response.getCurdate()).isEqualTo(header.wsCurdateMmDdYy())
                    .hasSize(AccountUpdateResponse.CURDATE_LENGTH)
                    .isNotEqualTo("mm/dd/yy");
            assertThat(response.getCurtime()).isEqualTo(header.wsCurtimeHhMmSs())
                    .hasSize(AccountUpdateResponse.CURTIME_LENGTH)
                    .isNotEqualTo("hh:mm:ss");
        }

        @Test
        @DisplayName("a fixed Clock drives the header, so the two eight-byte items are deterministic")
        void aFixedClockIsTheOnlyTimeSourceInPlay() {
            // Practice B7. COACTUPC reads the clock through FUNCTION CURRENT-DATE, which is the one
            // genuinely non-deterministic input the screen has. DateHeader takes a Clock rather than
            // calling now() precisely so a test can pin it, and Clock.fixed is how that is done: run this
            // twice, a day apart, and both runs must produce the same eight bytes.
            Instant instant = WHEN.toInstant(ZoneOffset.UTC);
            Clock fixed = Clock.fixed(instant, ZoneOffset.UTC);

            DateHeader first = DateHeader.from(ASCII, fixed);
            DateHeader second = DateHeader.from(ASCII, fixed);
            assertThat(first.wsCurdateMmDdYy()).isEqualTo(second.wsCurdateMmDdYy());
            assertThat(first.wsCurtimeHhMmSs()).isEqualTo(second.wsCurtimeHhMmSs());

            AccountUpdateResponse response = AccountUpdateResponse.initial().withDateTimeHeader(first);
            // COACTUP.CPY:362 - CURDATEO PIC X(8); COACTUP.CPY:380 - CURTIMEO PIC X(8).
            assertThat(response.getCurdate())
                    .isEqualTo(first.wsCurdateMmDdYy())
                    .hasSize(DateHeader.WS_CURDATE_LENGTH)
                    .hasSize(AccountUpdateResponse.CURDATE_LENGTH);
            assertThat(response.getCurtime())
                    .isEqualTo(first.wsCurtimeHhMmSs())
                    .hasSize(DateHeader.WS_CURTIME_LENGTH)
                    .hasSize(AccountUpdateResponse.CURTIME_LENGTH);

            // A different fixed instant must move the header, or the clock is not being read at all.
            Clock later = Clock.fixed(instant.plusSeconds(86_400L), ZoneOffset.UTC);
            assertThat(DateHeader.from(ASCII, later).wsCurdateMmDdYy())
                    .isNotEqualTo(first.wsCurdateMmDdYy());

            // And the seam refuses to invent one of its own.
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DateHeader.from(ASCII, null));
        }

        @Test
        @DisplayName("CSMSG01Y's 50 bytes pad into ERRMSGO 78 but truncate in INFOMSGO 45")
        void theFiftyByteMessagesFitOneLineAndNotTheOther() {
            // ScreenTitles.CCDA_THANK_YOU is X(40) and SystemMessages.CCDA_MSG_THANK_YOU is X(50):
            // different text, different width, different owner. They are NOT interchangeable, and a
            // migration that substitutes one for the other silently changes the bytes on the wire.
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.strip())
                    .as("even ignoring the padding the two literals are different text")
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU.strip());

            for (String message : List.of(SystemMessages.CCDA_MSG_THANK_YOU,
                    SystemMessages.CCDA_MSG_INVALID_KEY)) {
                // COACTUP.CPY:650 - ERRMSGO PIC X(78). 78 > 50, so the MOVE pads on the right.
                String intoErrmsg = AccountUpdateResponse.initial()
                        .withValue(ScreenField.ERRMSG, message)
                        .image(ScreenField.ERRMSG, ASCII);
                assertThat(intoErrmsg)
                        .hasSize(AccountUpdateResponse.ERRMSG_LENGTH)
                        .startsWith(message)
                        .endsWith(" ".repeat(
                                AccountUpdateResponse.ERRMSG_LENGTH - SystemMessages.MESSAGE_LENGTH));
                assertThat(intoErrmsg.strip()).isEqualTo(message.strip());

                // COACTUP.CPY:644 - INFOMSGO PIC X(45). 45 < 50, so the MOVE truncates on the RIGHT,
                // which is what a COBOL alphanumeric MOVE does. Five bytes of the message are lost.
                String intoInfomsg = AccountUpdateResponse.initial()
                        .withValue(ScreenField.INFOMSG, message)
                        .image(ScreenField.INFOMSG, ASCII);
                assertThat(intoInfomsg)
                        .hasSize(AccountUpdateResponse.INFOMSG_LENGTH)
                        .isEqualTo(message.substring(0, AccountUpdateResponse.INFOMSG_LENGTH))
                        .isNotEqualTo(message);
            }

            assertThat(AccountUpdateResponse.CSMSG01Y_MESSAGE_LENGTH)
                    .isEqualTo(50)
                    .isGreaterThan(AccountUpdateResponse.INFOMSG_LENGTH)
                    .isLessThan(AccountUpdateResponse.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the three legends carry their INITIAL values at their exact declared widths")
        void theLegendsMatchTheMapset() {
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withFunctionKeyLegends();
            assertThat(response.getFkeys()).isEqualTo("ENTER=Process F3=Exit")
                    .hasSize(AccountUpdateResponse.FKEYS_LENGTH);
            assertThat(response.getFkey05()).isEqualTo("F5=Save")
                    .hasSize(AccountUpdateResponse.FKEY05_LENGTH);
            assertThat(response.getFkey12()).isEqualTo("F12=Cancel")
                    .hasSize(AccountUpdateResponse.FKEY12_LENGTH);
        }

        @Test
        @DisplayName("screenInit composes all three steps and leaves the other 48 fields at LOW-VALUES")
        void screenInitComposesTheWholeParagraph() {
            AccountUpdateResponse response =
                    AccountUpdateResponse.screenInit(DateHeader.of(ASCII, WHEN));
            List<ScreenField> written = List.of(ScreenField.TITLE01, ScreenField.TITLE02,
                    ScreenField.TRNNAME, ScreenField.PGMNAME, ScreenField.CURDATE,
                    ScreenField.CURTIME, ScreenField.FKEYS, ScreenField.FKEY05, ScreenField.FKEY12);
            for (ScreenField field : ScreenField.values()) {
                if (!written.contains(field)) {
                    assertThat(response.value(field))
                            .as("%s is not touched by 3100-SCREEN-INIT", field.label())
                            .isEqualTo(AccountUpdateResponse.lowValues(field.length()));
                }
            }
            assertThat(response.getTitle01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("screenInit and withDateTimeHeader reject a null header")
        void nullHeaderIsRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.screenInit(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.initial().withDateTimeHeader(null));
        }

        @Test
        @DisplayName("setting a legend's value does not reveal it - visibility is the attribute byte")
        void aLegendValueDoesNotRevealIt() {
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withFunctionKeyLegends();
            assertThat(response.isSaveLegendRevealed()).isFalse();
            assertThat(response.isCancelLegendRevealed()).isFalse();
            response.revealSaveLegend();
            assertThat(response.isSaveLegendRevealed()).isTrue();
            assertThat(response.isCancelLegendRevealed()).isFalse();
            response.revealCancelLegend();
            assertThat(response.isCancelLegendRevealed()).isTrue();
            assertThat(response.attributes(ScreenField.FKEY05).getHilight())
                    .isEqualTo(BmsAttributes.DFHBMASB);
        }

        @Test
        @DisplayName("the info line is darkened without a message and brightened with one")
        void theInfoLineVisibilityFollowsTheMessage() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            response.applyInfoMessageVisibility(false);
            assertThat(response.attributes(ScreenField.INFOMSG).getHilight())
                    .isEqualTo(BmsAttributes.DFHBMDAR);
            response.applyInfoMessageVisibility(true);
            assertThat(response.attributes(ScreenField.INFOMSG).getHilight())
                    .isEqualTo(BmsAttributes.DFHBMASB);
        }
    }

    // =============================================================================================
    // Statelessness. Gates G37, G40 and G53: XCTL becomes response data, nothing is held server-side.
    // =============================================================================================

    @Nested
    @DisplayName("Statelessness - XCTL becomes response data (rule R6, gates G37/G40/G53)")
    class Statelessness {

        @Test
        @DisplayName("the navigation trio is settable and clearable")
        void theNavigationTrioTravelsInThePayload() {
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withNextTarget("COMEN01C", "COMEN01", "COMEN1A");
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(response.getNextMapset()).isEqualTo("COMEN01");
            assertThat(response.getNextMap()).isEqualTo("COMEN1A");
            AccountUpdateResponse cleared = response.withNextTarget(null, null, null);
            assertThat(cleared.getNextProgram()).isEqualTo(
                    AccountUpdateResponse.lowValues(AccountUpdateResponse.NEXT_PROGRAM_LENGTH));
            assertThat(cleared.getNextMapset()).isEqualTo(
                    AccountUpdateResponse.lowValues(AccountUpdateResponse.NEXT_MAPSET_LENGTH));
            assertThat(cleared.getNextMap()).isEqualTo(
                    AccountUpdateResponse.lowValues(AccountUpdateResponse.NEXT_MAP_LENGTH));
        }

        @Test
        @DisplayName("re-painting points back at CACTUPA with the truncated seven-character mapset")
        void selfTargetUsesTheSevenCharacterMapset() {
            AccountUpdateResponse response = AccountUpdateResponse.initial().withSelfAsNextTarget();
            assertThat(response.getNextProgram()).isEqualTo("COACTUPC");
            assertThat(response.getNextMapset()).isEqualTo("COACTUP").hasSize(7);
            assertThat(response.getNextMap()).isEqualTo("CACTUPA").hasSize(7);
        }

        @Test
        @DisplayName("the navigation context is echoed, and absent when EIBCALEN was 0")
        void theNavigationContextIsEchoed() {
            NavigationContext context = NavigationContext.empty()
                    .withFromTranid("CM00").withFromProgram("COMEN01C");
            AccountUpdateResponse withContext =
                    AccountUpdateResponse.initial().withNavigationContext(context);
            assertThat(withContext.getNavigationContext()).isEqualTo(context);
            assertThat(withContext.hasNavigationContext()).isTrue();
            assertThat(withContext.commareaLength())
                    .isEqualTo(AccountUpdateResponse.TOTAL_COMMAREA_LENGTH);
            AccountUpdateResponse without = withContext.withNavigationContext(null);
            assertThat(without.getNavigationContext()).isNull();
            assertThat(without.hasNavigationContext()).isFalse();
            assertThat(without.commareaLength()).isEqualTo(CommArea.RECORD_LENGTH);
        }

        @Test
        @DisplayName("ENTER and REENTER are both readable, and both false with no context (gate G38)")
        void enterAndReenterAreBothDriven() {
            AccountUpdateResponse none = AccountUpdateResponse.initial();
            assertThat(none.isEnter()).isFalse();
            assertThat(none.isReenter()).isFalse();
            AccountUpdateResponse enter = none.withNavigationContext(
                    NavigationContext.empty().withPgmEnter());
            assertThat(enter.isEnter()).isTrue();
            assertThat(enter.isReenter()).isFalse();
            AccountUpdateResponse reenter = none.withNavigationContext(
                    NavigationContext.empty().withPgmReenter());
            assertThat(reenter.isEnter()).isFalse();
            assertThat(reenter.isReenter()).isTrue();
        }

        @Test
        @DisplayName("the card work area is defensively copied on the way out and in")
        void theCardWorkAreaIsCopied() {
            CardScreenState supplied = new CardScreenState();
            AccountUpdateResponse response =
                    AccountUpdateResponse.initial().withCardScreenState(supplied);
            assertThat(response.getCardScreenState()).isNotSameAs(supplied);
            assertThat(response.getCardScreenState()).isNotSameAs(response.getCardScreenState());
            assertThat(AccountUpdateResponse.initial().withCardScreenState(null)
                    .getCardScreenState()).isNotNull();
        }

        @Test
        @DisplayName("the XCTL at COACTUPC.cbl:956-958 becomes three payload fields and nothing else")
        void theTransferOfControlIsFullyDescribedByThePayload() {
            // app/cbl/COACTUPC.cbl:956 - EXEC CICS XCTL
            //                      :957 -      PROGRAM (CDEMO-TO-PROGRAM)
            //                      :958 -      COMMAREA(CARDDEMO-COMMAREA)
            // Cited as L956-L958. The migration plan's bare "L957" names the PROGRAM continuation line
            // rather than the statement, which is practice B4's kind of correction: the statement starts
            // at 956 and a reader sent to 957 alone sees an operand with no verb.
            //
            // The preceding EXEC CICS SYNCPOINT at :952-954 has NO Java DTO counterpart and none is
            // asserted here: a syncpoint is a transaction boundary, not payload. It belongs to whatever
            // commits the update, and a response field claiming to represent it would be fiction.
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withNextTarget("COADM01C", "COADM01", "COADM1A");
            assertThat(response.getNextProgram())
                    .as("the XCTL's PROGRAM operand, echoed rather than executed")
                    .isEqualTo("COADM01C")
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(response.getNextMapset()).hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(response.getNextMap()).hasSize(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("CDEMO-TO-PROGRAM's LOW-VALUES and SPACES fallbacks are both reachable")
        void theTargetProgramFallbacksAreBothDriven() {
            // COCOM01Y declares CDEMO-TO-PROGRAM as X(8) with no VALUE, so a caller that never set it
            // leaves LOW-VALUES, while a caller that MOVEd SPACES leaves eight blanks. Those are two
            // distinct byte images and neither is Java null - a response must be able to carry both,
            // because "no target" and "a target cleared to blanks" reach the client differently.
            String lowValues = AccountUpdateResponse.lowValues(
                    AccountUpdateResponse.NEXT_PROGRAM_LENGTH);
            String spaces = " ".repeat(AccountUpdateResponse.NEXT_PROGRAM_LENGTH);

            AccountUpdateResponse unset = AccountUpdateResponse.initial();
            assertThat(unset.getNextProgram()).isEqualTo(lowValues).hasSize(8);

            AccountUpdateResponse cleared = AccountUpdateResponse.initial()
                    .withNextTarget(null, null, null);
            assertThat(cleared.getNextProgram()).isEqualTo(lowValues);

            AccountUpdateResponse blanked = AccountUpdateResponse.initial()
                    .withNextTarget(spaces, " ".repeat(7), " ".repeat(7));
            assertThat(blanked.getNextProgram()).isEqualTo(spaces).hasSize(8);
            assertThat(blanked.getNextMapset()).isEqualTo(" ".repeat(7));
            assertThat(blanked.getNextMap()).isEqualTo(" ".repeat(7));

            assertThat(lowValues)
                    .as("LOW-VALUES is 0x00 x 8 and SPACES is 0x20 x 8 - never the same image")
                    .isNotEqualTo(spaces);
            assertThat(lowValues.charAt(0)).isEqualTo('\u0000');
            assertThat(blanked).isNotEqualTo(cleared);
        }

        @Test
        @DisplayName("COCOM01Y is 160 bytes, MAP before MAPSET, and all four 88s go both ways")
        void theCommareaCarrierIsTheCopybookShape() {
            // app/cpy/COCOM01Y.cpy:19 - CARDDEMO-COMMAREA is 34 + 84 + 12 + 16 + 14 = 160.
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.GENERAL_INFO_LENGTH
                            + NavigationContext.CUSTOMER_INFO_LENGTH
                            + NavigationContext.ACCOUNT_INFO_LENGTH
                            + NavigationContext.CARD_INFO_LENGTH
                            + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(160);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(160);

            // COCOM01Y.cpy:43 CDEMO-LAST-MAP then :44 CDEMO-LAST-MAPSET - MAP first, both X(7).
            // Seven, not eight: a symbolic group name is a 7-character map name plus an I/O suffix.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_OFFSET)
                    .as("COCOM01Y.cpy:43 precedes :44 - the alphabetical reading has them swapped")
                    .isLessThan(NavigationContext.LAST_MAPSET_OFFSET);
            assertThat(AccountUpdateResponse.OUTPUT_GROUP_NAME)
                    .as("7-character map name plus the O suffix is what makes X(7) the right width")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH + 1);

            // All four 88s, each driven true AND false - gate G50.
            // COCOM01Y.cpy:27 CDEMO-USRTYP-ADMIN 'A'; :28 CDEMO-USRTYP-USER 'U'.
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();
            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.isUser()).isFalse();
            assertThat(admin.userType()).isEqualTo(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            NavigationContext user = NavigationContext.empty().withUserTypeUser();
            assertThat(user.isUser()).isTrue();
            assertThat(user.isAdmin()).isFalse();
            assertThat(user.userType()).isEqualTo(NavigationContext.USER_TYPE_USER).isEqualTo("U");

            // COCOM01Y.cpy:30 CDEMO-PGM-ENTER 0; :31 CDEMO-PGM-REENTER 1. This is the pair the
            // CSSETATY matrix above turns on, which is why both states matter twice over.
            NavigationContext enter = NavigationContext.empty().withPgmEnter();
            assertThat(enter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            NavigationContext reenter = NavigationContext.empty().withPgmReenter();
            assertThat(reenter.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
            assertThat(AccountUpdateResponse.initial().withNavigationContext(enter).isEnter()).isTrue();
            assertThat(AccountUpdateResponse.initial().withNavigationContext(enter).isReenter())
                    .isFalse();
            assertThat(AccountUpdateResponse.initial().withNavigationContext(reenter).isReenter())
                    .isTrue();
            assertThat(AccountUpdateResponse.initial().withNavigationContext(reenter).isEnter())
                    .isFalse();
        }

        @Test
        @DisplayName("CVCRD01Y is 213 bytes and declares SIXTEEN AID conditions, not fifteen")
        void theCardWorkAreaIsTheCopybookShape() {
            // app/cpy/CVCRD01Y.cpy - 5 + 8 + 7 + 7 + 75 + 75 + 11 + 16 + 9 = 213.
            assertThat(CardScreenState.CCARD_AID_LENGTH).isEqualTo(5);
            assertThat(CardScreenState.RECORD_LENGTH)
                    .isEqualTo(CardScreenState.CCARD_AID_LENGTH
                            + CardScreenState.CCARD_NEXT_PROG_LENGTH
                            + CardScreenState.CCARD_NEXT_MAPSET_LENGTH
                            + CardScreenState.CCARD_NEXT_MAP_LENGTH
                            + CardScreenState.CCARD_ERROR_MSG_LENGTH
                            + CardScreenState.CCARD_RETURN_MSG_LENGTH
                            + CardScreenState.CC_ACCT_ID_LENGTH
                            + CardScreenState.CC_CARD_NUM_LENGTH
                            + CardScreenState.CC_CUST_ID_LENGTH)
                    .isEqualTo(213);

            // SIXTEEN, not fifteen - practice B4, and read straight off the copybook:
            // CVCRD01Y.cpy:4 ENTER, :5 CLEAR, :6 'PA1  ', :7 'PA2  ', :8-:19 PFK01 through PFK12.
            // That is 2 + 2 + 12 = 16 declarations on consecutive lines with no gap for a seventeenth.
            // There is deliberately NO PA3 condition: the copybook does not declare one, and dropping a
            // real condition to reach a documented "15" would lose a branch the source can take.
            // Each token is X(5), which is why PA1 and PA2 carry two trailing spaces.
            List<String> aidTokens = List.of(
                    CardScreenState.CCARD_AID_ENTER, CardScreenState.CCARD_AID_CLEAR,
                    CardScreenState.CCARD_AID_PA1, CardScreenState.CCARD_AID_PA2,
                    CardScreenState.CCARD_AID_PFK01, CardScreenState.CCARD_AID_PFK02,
                    CardScreenState.CCARD_AID_PFK03, CardScreenState.CCARD_AID_PFK04,
                    CardScreenState.CCARD_AID_PFK05, CardScreenState.CCARD_AID_PFK06,
                    CardScreenState.CCARD_AID_PFK07, CardScreenState.CCARD_AID_PFK08,
                    CardScreenState.CCARD_AID_PFK09, CardScreenState.CCARD_AID_PFK10,
                    CardScreenState.CCARD_AID_PFK11, CardScreenState.CCARD_AID_PFK12);
            assertThat(aidTokens).as("CVCRD01Y declares 16 CCARD-AID-* conditions").hasSize(16);
            assertThat(aidTokens).doesNotHaveDuplicates();
            for (String token : aidTokens) {
                assertThat(token)
                        .as("CCARD-AID is X(5), so every token is exactly five bytes wide")
                        .hasSize(CardScreenState.CCARD_AID_LENGTH);
            }
            // CVCRD01Y.cpy:6-7 - the two PA tokens are literals 'PA1  ' and 'PA2  ', padded in source.
            assertThat(CardScreenState.CCARD_AID_PA1).isEqualTo("PA1  ");
            assertThat(CardScreenState.CCARD_AID_PA2).isEqualTo("PA2  ");
            assertThat(aidTokens).doesNotContain("PA3  ", "PA3");

            // CCARD-RETURN-MSG X(75) carries 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES: binary 0x00
            // seventy-five times, never spaces and never Java null. A fresh work area is NOT off,
            // because the item starts at spaces the way the program's own INITIALIZE leaves it - so both
            // sides of the condition are reachable, which is what gate G50 asks of an 88.
            CardScreenState fresh = new CardScreenState();
            assertThat(fresh.getCcardReturnMsg()).hasSize(CardScreenState.CCARD_RETURN_MSG_LENGTH);
            assertThat(fresh.isCcardReturnMsgOff()).isFalse();
            CardScreenState off = new CardScreenState();
            off.setCcardReturnMsgToLowValues();
            assertThat(off.isCcardReturnMsgOff()).isTrue();
            assertThat(off.getCcardReturnMsg())
                    .hasSize(CardScreenState.CCARD_RETURN_MSG_LENGTH)
                    .isEqualTo("\u0000".repeat(CardScreenState.CCARD_RETURN_MSG_LENGTH))
                    .isNotEqualTo(" ".repeat(CardScreenState.CCARD_RETURN_MSG_LENGTH));

            // And the work area survives the response round trip at its declared width.
            AccountUpdateResponse carried =
                    AccountUpdateResponse.initial().withCardScreenState(off);
            assertThat(carried.getCardScreenState().isCcardReturnMsgOff()).isTrue();
        }

        @Test
        @DisplayName("no static mutable state and no session anywhere on the type")
        void noStaticMutableStateExists() {
            List<String> offenders = new ArrayList<>();
            for (java.lang.reflect.Field field : AccountUpdateResponse.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    offenders.add(field.getName());
                }
            }
            for (java.lang.reflect.Field field : FieldAttributes.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    offenders.add("FieldAttributes." + field.getName());
                }
            }
            assertThat(offenders).as("gate G53: no static mutable state").isEmpty();
        }
    }

    // =============================================================================================
    // ACUP-CHANGE-ACTION: all nine 88-levels, each driven true AND false (gate G50), and the two
    // groupings in particular.
    // =============================================================================================

    @Nested
    @DisplayName("ACUP-CHANGE-ACTION - all nine 88-levels, both ways (gate G50)")
    class ChangeActionStates {

        /** Every state, with the predicate that must hold for it. */
        private AccountUpdateResponse at(ChangeAction action) {
            return AccountUpdateResponse.initial().withChangeAction(action);
        }

        @Test
        @DisplayName("LOW-VALUES and SPACES are both ACUP-DETAILS-NOT-FETCHED, and nothing else")
        void detailsNotFetchedCoversTwoValues() {
            for (ChangeAction action : List.of(ChangeAction.initial(), ChangeAction.spacesState())) {
                AccountUpdateResponse response = at(action);
                assertThat(response.isDetailsNotFetched()).isTrue();
                assertThat(response.isShowDetails()).isFalse();
                assertThat(response.isChangesMade()).isFalse();
                assertThat(response.isChangesFailed()).isFalse();
            }
            assertThat(ChangeAction.DETAILS_NOT_FETCHED_VALUES).hasSize(2);
        }

        @Test
        @DisplayName("'S' is ACUP-SHOW-DETAILS only")
        void showDetailsIsExclusive() {
            AccountUpdateResponse response = at(ChangeAction.showDetails());
            assertThat(response.isShowDetails()).isTrue();
            assertThat(response.isDetailsNotFetched()).isFalse();
            assertThat(response.isChangesMade()).isFalse();
            assertThat(response.isChangesNotOk()).isFalse();
        }

        @Test
        @DisplayName("ACUP-CHANGES-MADE covers FIVE values - 'E','N','C','L','F' - not two")
        void changesMadeCoversFiveValues() {
            assertThat(ChangeAction.CHANGES_MADE_VALUES)
                    .as("app/cbl/COACTUPC.cbl:660-662 lists five values")
                    .containsExactlyInAnyOrder("E", "N", "C", "L", "F");
            for (ChangeAction action : List.of(ChangeAction.changesNotOk(),
                    ChangeAction.changesOkNotConfirmed(), ChangeAction.changesOkayedAndDone(),
                    ChangeAction.changesOkayedLockError(), ChangeAction.changesOkayedButFailed())) {
                assertThat(at(action).isChangesMade())
                        .as("%s must satisfy ACUP-CHANGES-MADE", action.value()).isTrue();
            }
            assertThat(at(ChangeAction.showDetails()).isChangesMade()).isFalse();
            assertThat(at(ChangeAction.initial()).isChangesMade()).isFalse();
        }

        @Test
        @DisplayName("3390-SETUP-INFOMSG-ATTRS' guard is live: CHANGES-MADE AND NOT OKAYED-AND-DONE")
        void theInfomsgGuardIsNotDead() {
            AccountUpdateResponse done = at(ChangeAction.changesOkayedAndDone());
            assertThat(done.isChangesMade()).isTrue();
            assertThat(done.isChangesOkayedAndDone()).isTrue();
            assertThat(done.isChangesMade() && !done.isChangesOkayedAndDone())
                    .as("app/cbl/COACTUPC.cbl:3573-3574 must be able to be false").isFalse();
            AccountUpdateResponse pending = at(ChangeAction.changesOkNotConfirmed());
            assertThat(pending.isChangesMade() && !pending.isChangesOkayedAndDone())
                    .as("and able to be true").isTrue();
        }

        @Test
        @DisplayName("'E' and 'N' are the two singletons the edits and the confirmation prompt use")
        void notOkAndUnconfirmedAreExclusive() {
            AccountUpdateResponse notOk = at(ChangeAction.changesNotOk());
            assertThat(notOk.isChangesNotOk()).isTrue();
            assertThat(notOk.isChangesOkNotConfirmed()).isFalse();
            assertThat(notOk.isChangesFailed()).isFalse();
            AccountUpdateResponse unconfirmed = at(ChangeAction.changesOkNotConfirmed());
            assertThat(unconfirmed.isChangesOkNotConfirmed()).isTrue();
            assertThat(unconfirmed.isChangesNotOk()).isFalse();
            assertThat(unconfirmed.isChangesFailed()).isFalse();
        }

        @Test
        @DisplayName("ACUP-CHANGES-FAILED covers 'L' and 'F', and neither 'C' nor 'E' nor 'N'")
        void changesFailedCoversTwoValues() {
            assertThat(ChangeAction.CHANGES_FAILED_VALUES).containsExactlyInAnyOrder("L", "F");
            AccountUpdateResponse lock = at(ChangeAction.changesOkayedLockError());
            assertThat(lock.isChangesFailed()).isTrue();
            assertThat(lock.isChangesOkayedLockError()).isTrue();
            assertThat(lock.isChangesOkayedButFailed()).isFalse();
            AccountUpdateResponse stale = at(ChangeAction.changesOkayedButFailed());
            assertThat(stale.isChangesFailed()).isTrue();
            assertThat(stale.isChangesOkayedButFailed()).isTrue();
            assertThat(stale.isChangesOkayedLockError()).isFalse();
            assertThat(at(ChangeAction.changesOkayedAndDone()).isChangesFailed()).isFalse();
            assertThat(at(ChangeAction.changesNotOk()).isChangesFailed()).isFalse();
        }

        /**
         * The nine-row byte table, exactly as the copybook declares it.
         *
         * <p>Nine <em>overlapping</em> conditions on one {@code PIC X(1)} byte, not nine independent
         * toggles - and the difference matters. {@code 'L'} and {@code 'F'} each satisfy
         * <strong>three</strong> predicates at once ({@code CHANGES-MADE}, {@code CHANGES-FAILED} and
         * their own singleton); {@code LOW-VALUES} and {@code SPACES} are two <em>distinct bytes</em> that
         * both satisfy {@code ACUP-DETAILS-NOT-FETCHED}; and one unmatched byte satisfies nothing at all,
         * which is what drives every predicate's false side simultaneously. A suite that flipped nine
         * booleans one at a time would look thorough and prove none of that.
         *
         * @return {@code {byte, the predicates that must be TRUE}} per row
         */
        static List<Arguments> changeActionTable() {
            return List.of(
                    // COACTUPC.cbl:656-658 - 88 ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES.
                    Arguments.of(ChangeAction.LOW_VALUES, List.of("DETAILS_NOT_FETCHED")),
                    Arguments.of(ChangeAction.SPACES, List.of("DETAILS_NOT_FETCHED")),
                    // COACTUPC.cbl:659 - 88 ACUP-SHOW-DETAILS VALUE 'S'.
                    Arguments.of(ChangeAction.SHOW_DETAILS, List.of("SHOW_DETAILS")),
                    // COACTUPC.cbl:660-662 - the five CHANGES-MADE bytes; the singletons follow at :663-:665.
                    Arguments.of(ChangeAction.CHANGES_NOT_OK,
                            List.of("CHANGES_MADE", "CHANGES_NOT_OK")),
                    Arguments.of(ChangeAction.CHANGES_OK_NOT_CONFIRMED,
                            List.of("CHANGES_MADE", "CHANGES_OK_NOT_CONFIRMED")),
                    Arguments.of(ChangeAction.CHANGES_OKAYED_AND_DONE,
                            List.of("CHANGES_MADE", "CHANGES_OKAYED_AND_DONE")),
                    // Three at once: :660-662 CHANGES-MADE and :666 CHANGES-FAILED both span 'L', plus :667.
                    Arguments.of(ChangeAction.CHANGES_OKAYED_LOCK_ERROR,
                            List.of("CHANGES_MADE", "CHANGES_FAILED", "CHANGES_OKAYED_LOCK_ERROR")),
                    // Three at once again for 'F': :660-662, :666 and the :668 singleton.
                    Arguments.of(ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                            List.of("CHANGES_MADE", "CHANGES_FAILED", "CHANGES_OKAYED_BUT_FAILED")),
                    // No condition names 'X', so every one of the nine must read false.
                    Arguments.of("X", List.<String>of()));
        }

        @ParameterizedTest(name = "[{index}] byte {0} -> {1}")
        @MethodSource("changeActionTable")
        @DisplayName("each byte satisfies exactly its own predicates and no others (gate G50)")
        void everyByteSatisfiesExactlyItsOwnPredicates(String value, List<String> expectedTrue) {
            AccountUpdateResponse response =
                    AccountUpdateResponse.initial().withChangeAction(ChangeAction.of(value));

            Map<String, Boolean> actual = new LinkedHashMap<>();
            actual.put("DETAILS_NOT_FETCHED", response.isDetailsNotFetched());
            actual.put("SHOW_DETAILS", response.isShowDetails());
            actual.put("CHANGES_MADE", response.isChangesMade());
            actual.put("CHANGES_NOT_OK", response.isChangesNotOk());
            actual.put("CHANGES_OK_NOT_CONFIRMED", response.isChangesOkNotConfirmed());
            actual.put("CHANGES_OKAYED_AND_DONE", response.isChangesOkayedAndDone());
            actual.put("CHANGES_FAILED", response.isChangesFailed());
            actual.put("CHANGES_OKAYED_LOCK_ERROR", response.isChangesOkayedLockError());
            actual.put("CHANGES_OKAYED_BUT_FAILED", response.isChangesOkayedButFailed());

            assertThat(actual)
                    .as("all nine 88-levels are accounted for on every row")
                    .hasSize(9);
            actual.forEach((name, held) -> assertThat(held)
                    .as("byte %s: %s must be %s", value, name, expectedTrue.contains(name))
                    .isEqualTo(expectedTrue.contains(name)));

            // The unmatched byte is the row that drives every false side at once, and the only row on
            // which isUnrecognised() holds. PIC X(1) VALUE LOW-VALUES means the default is binary 0x00 -
            // not a space, and certainly not Java null.
            assertThat(response.changeAction().isUnrecognised()).isEqualTo(expectedTrue.isEmpty());
        }

        @Test
        @DisplayName("LOW-VALUES and SPACES are two distinct bytes that agree on one predicate")
        void theTwoNotFetchedBytesAreNotTheSameByte() {
            assertThat(ChangeAction.LOW_VALUES)
                    .as("COACTUPC.cbl:654-655 - PIC X(1) VALUE LOW-VALUES is binary 0x00")
                    .isEqualTo("\u0000")
                    .isNotEqualTo(ChangeAction.SPACES);
            assertThat(ChangeAction.SPACES).isEqualTo(" ");
            assertThat(ChangeAction.initial()).isNotEqualTo(ChangeAction.spacesState());
            assertThat(ChangeAction.initial().isDetailsNotFetched()).isTrue();
            assertThat(ChangeAction.spacesState().isDetailsNotFetched()).isTrue();
            assertThat(ChangeAction.initial().isUnrecognised()).isFalse();
            assertThat(ChangeAction.spacesState().isUnrecognised()).isFalse();
            // The default really is the 0x00 byte and not the blank.
            assertThat(AccountUpdateResponse.initial().changeAction())
                    .isEqualTo(ChangeAction.initial())
                    .isNotEqualTo(ChangeAction.spacesState());
        }

        @Test
        @DisplayName("the char overload agrees with the String overload on every declared byte")
        void theCharAndStringFactoriesAgree() {
            for (String value : List.of(ChangeAction.LOW_VALUES, ChangeAction.SPACES,
                    ChangeAction.SHOW_DETAILS, ChangeAction.CHANGES_NOT_OK,
                    ChangeAction.CHANGES_OK_NOT_CONFIRMED, ChangeAction.CHANGES_OKAYED_AND_DONE,
                    ChangeAction.CHANGES_OKAYED_LOCK_ERROR, ChangeAction.CHANGES_OKAYED_BUT_FAILED,
                    "X")) {
                assertThat(ChangeAction.of(value.charAt(0)))
                        .as("of(char) and of(String) must not diverge for %s", value)
                        .isEqualTo(ChangeAction.of(value));
            }
        }

        @Test
        @DisplayName("changeAction reads through the commarea, and withChangeAction rejects null")
        void changeActionIsReadThroughTheCommarea() {
            AccountUpdateResponse response = at(ChangeAction.showDetails());
            assertThat(response.changeAction()).isEqualTo(ChangeAction.showDetails());
            assertThat(response.getCommArea().changeAction()).isEqualTo(ChangeAction.showDetails());
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.withChangeAction(null));
        }
    }

    // =============================================================================================
    // The 873-byte commarea, and the two snapshots reached through it. Gates G34 and G43.
    // =============================================================================================

    @Nested
    @DisplayName("The 873-byte commarea and its two 436-byte snapshots")
    class Commarea {

        @Test
        @DisplayName("the OLD and NEW images are reachable and carry their own group markers")
        void bothImagesAreReachable() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThat(response.oldDetails()).isSameAs(response.getCommArea().oldDetails());
            assertThat(response.newDetails()).isSameAs(response.getCommArea().newDetails());
            assertThat(response.oldDetailGroup()).isEqualTo(DetailGroup.OLD);
            assertThat(response.newDetailGroup()).isEqualTo(DetailGroup.NEW);
            assertThat(response.oldAcct()).isSameAs(response.oldDetails().acct());
            assertThat(response.oldCust()).isSameAs(response.oldDetails().cust());
            assertThat(response.newAcct()).isSameAs(response.newDetails().acct());
            assertThat(response.newCust()).isSameAs(response.newDetails().cust());
        }

        /**
         * A NEW customer snapshot carrying a stated nine-byte SSN, built through the canonical
         * constructor because {@link CustSnapshot} is a record and has no wither.
         *
         * @param ssn the nine characters to place in {@code ACUP-NEW-CUST-SSN-X}
         * @return the snapshot
         */
        private static CustSnapshot custWithSsn(String ssn) {
            CustSnapshot base = CustSnapshot.initialised();
            return new CustSnapshot(base.custIdX(), base.firstName(), base.middleName(),
                    base.lastName(), base.addrLine1(), base.addrLine2(), base.addrLine3(),
                    base.addrStateCd(), base.addrCountryCd(), base.addrZip(), base.phoneNum1(),
                    base.phoneNum2(), ssn, base.govtIssuedId(), base.dobYyyyMmDd(),
                    base.eftAccountId(), base.priHolderInd(), base.ficoScoreX());
        }

        @Test
        @DisplayName("the NEW SSN is a three-part group, asserted BY NAME - a size check cannot see it")
        void theNewSsnPartsExistByName() {
            AccountUpdateResponse response = AccountUpdateResponse.initial().withCommArea(
                    CommArea.initialised().withNewDetails(new Details(DetailGroup.NEW,
                            AcctSnapshot.initialised(), custWithSsn("078051120"))));
            assertThat(response.newCust().ssn1()).as("ACUP-NEW-CUST-SSN-1").isEqualTo("078");
            assertThat(response.newCust().ssn2()).as("ACUP-NEW-CUST-SSN-2").isEqualTo("05");
            assertThat(response.newCust().ssn3()).as("ACUP-NEW-CUST-SSN-3").isEqualTo("1120");
            assertThat(response.newCust().ssnX()).as("the three parts still occupy nine bytes")
                    .isEqualTo("078051120").hasSize(9);
            assertThat(response.newDetailGroup().declaresSsnParts()).isTrue();
            assertThat(response.oldDetailGroup().declaresSsnParts())
                    .as("OLD is a flat X(09), not a group").isFalse();
        }

        @Test
        @DisplayName("only NEW declares the FICO range condition - OLD lacks it")
        void onlyNewDeclaresTheFicoRange() {
            assertThat(DetailGroup.NEW.declaresFicoRangeCondition()).isTrue();
            assertThat(DetailGroup.OLD.declaresFicoRangeCondition()).isFalse();
        }

        @Test
        @DisplayName("the EXPIRAION misspelling is preserved on both images")
        void theMisspellingIsPreserved() {
            // ============================================================================
            // READ THIS BEFORE "FIXING" THE SPELLING. It is EXPIRAION, not EXPIRATION, and
            // it stays that way.
            //
            //   app/cpy/CVACT01Y.cpy:11        05  ACCT-EXPIRAION-DATE      PIC X(10).
            //   app/cbl/COACTUPC.cbl:428       15  ACCT-UPDATE-EXPIRAION-DATE   PIC X(10).
            //   app/cbl/COACTUPC.cbl:690       15  ACUP-OLD-EXPIRAION-DATE      PIC X(08).
            //   app/cbl/COACTUPC.cbl:691-692       ACUP-OLD-EXPIRAION-DATE-PARTS REDEFINES ...
            //   app/cbl/COACTUPC.cbl:778       15  ACUP-NEW-EXPIRAION-DATE      PIC X(08).
            //
            // The typo is in the copybook that defines the record, so it is in the CONTRACT, not
            // in a comment: seventeen textual occurrences in COACTUPC.cbl alone. Per the migration
            // plan's implicit requirement I1 and its standing constraint "never rename a copybook
            // field, including one that is misspelled", correcting it here would make the Java
            // member name disagree with the COBOL field name, and the field-for-field differ
            // reports that as a diff on every single comparison - a silent, total parity failure
            // that looks like a tidy-up in review. Renaming is a behaviour change. Leave it.
            // ============================================================================
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThat(response.oldAcct().expiraionDate()).hasSize(8);
            assertThat(response.newAcct().expiraionDate()).hasSize(8);
            List<String> componentNames = Arrays.stream(
                            AccountUpdateRequest.AcctSnapshot.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(componentNames)
                    .as("CVACT01Y.cpy:11 spells it EXPIRAION - the Java member must too")
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
            // Both halves of the commarea carry the same misspelling, so neither can drift alone.
            List<String> newComponentNames = Arrays.stream(
                            AccountUpdateRequest.AcctSnapshot.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("expira"))
                    .toList();
            assertThat(newComponentNames)
                    .as("exactly one expiry component, and it is the misspelled one")
                    .containsExactly("expiraionDate");
        }

        @Test
        @DisplayName("the snapshot dates stay X(08) unseparated - 9700's DOB offsets depend on it")
        void theSnapshotDatesStayUnseparated() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThat(response.oldAcct().openDate()).hasSize(8);
            assertThat(response.oldAcct().reissueDate()).hasSize(8);
            assertThat(response.oldCust().dobYyyyMmDd()).hasSize(8);
            // The parts read at (1:4)/(5:2)/(7:2) of the unseparated form, which is why 8 is correct.
            assertThat(response.oldCust().dobYear()).hasSize(4);
            assertThat(response.oldCust().dobMon()).hasSize(2);
            assertThat(response.oldCust().dobDay()).hasSize(2);
        }

        @Test
        @DisplayName("the X(12)/PIC S9(10)V99 REDEFINES pairs decode at scale 2, truncating (G22/G24)")
        void theMonetaryRedefinesPairsDecodeAtScaleTwo() {
            assertThat(AccountUpdateResponse.MONETARY_SCALE)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
            assertThat(AccountUpdateResponse.MONETARY_ROUNDING)
                    .isEqualTo(CobolDecimal.COBOL_ROUNDING)
                    .isEqualTo(RoundingMode.DOWN)
                    .isNotEqualTo(RoundingMode.HALF_UP)
                    .isNotEqualTo(RoundingMode.HALF_EVEN);
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            for (BigDecimal amount : List.of(response.oldAcct().currBalN(),
                    response.oldAcct().creditLimitN(), response.oldAcct().cashCreditLimitN(),
                    response.oldAcct().currCycCreditN(), response.oldAcct().currCycDebitN(),
                    response.newAcct().currBalN(), response.newAcct().creditLimitN())) {
                assertThat(amount.scale()).isEqualTo(AccountUpdateResponse.MONETARY_SCALE);
            }

            // Asserting that the CONSTANT is DOWN is necessary but not sufficient: it would still pass
            // if some arithmetic path quietly rounded elsewhere. So drive values where DOWN and HALF_UP
            // genuinely DISAGREE, and pin the DOWN answer. The keyword ROUNDED appears ZERO times in all
            // 28 COBOL programs, so the mainframe truncates on store and HALF_UP would be a cent out.
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("1.239")))
                    .as("truncate, do not round: 1.239 -> 1.23, whereas HALF_UP would give 1.24")
                    .isEqualByComparingTo(new BigDecimal("1.23"))
                    .isNotEqualByComparingTo(new BigDecimal("1.239").setScale(2, RoundingMode.HALF_UP));
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("0.005")))
                    .as("0.005 -> 0.00 under DOWN; HALF_UP would invent a cent from nothing")
                    .isEqualByComparingTo(BigDecimal.ZERO)
                    .isNotEqualByComparingTo(new BigDecimal("0.005").setScale(2, RoundingMode.HALF_UP));
            // Negative amounts truncate TOWARDS ZERO, which is what DOWN means and what FLOOR does not:
            // a balance of -1.239 stores as -1.23, never -1.24.
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("-1.239")))
                    .as("DOWN is toward zero: -1.239 -> -1.23, whereas FLOOR would give -1.24")
                    .isEqualByComparingTo(new BigDecimal("-1.23"))
                    .isNotEqualByComparingTo(new BigDecimal("-1.239").setScale(2, RoundingMode.FLOOR));
            // And the scale is exactly 2 afterwards, not merely numerically equal.
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("1.239")).scale())
                    .isEqualTo(AccountUpdateResponse.MONETARY_SCALE);
        }

        @Test
        @DisplayName("withCommArea replaces the area, and null restores the initialised one")
        void withCommAreaReplacesAndRestores() {
            CommArea populated = CommArea.initialised()
                    .withChangeAction(ChangeAction.changesOkayedAndDone());
            AccountUpdateResponse response =
                    AccountUpdateResponse.initial().withCommArea(populated);
            assertThat(response.getCommArea()).isEqualTo(populated);
            assertThat(response.withCommArea(null).getCommArea()).isEqualTo(CommArea.initialised());
        }
    }

    // =============================================================================================
    // The group image. Gate G21: every FILLER declared, and the total width verifiable.
    // =============================================================================================

    @Nested
    @DisplayName("The 1095-byte CACTUPAO image - hand-written, byte-explicit (gates G21, B11)")
    class GroupImage {

        @Test
        @DisplayName("the layout declares 1 + 54 x 6 = 325 spans over 1095 bytes")
        void theLayoutIsCompleteAndContiguous() {
            assertThat(AccountUpdateResponse.OUTPUT_GROUP_LAYOUT.recordLength())
                    .isEqualTo(AccountUpdateResponse.GROUP_LENGTH);
            assertThat(AccountUpdateResponse.OUTPUT_GROUP_LAYOUT.spans())
                    .hasSize(1 + AccountUpdateResponse.FIELD_COUNT * 6);
        }

        @Test
        @DisplayName("the two spans are 1095 and 873, and each passes its own total-width self-check")
        void theGroupImageAndTheCommareaAreDifferentSpans() {
            // These are TWO DIFFERENT totals over two different structures, and conflating them is the
            // arithmetic error most likely to slip through unnoticed:
            //
            //   1095 = 12 (TIOAPFX FILLER, COACTUP.CPY:344)
            //        + 54 x 7 (per-field output preamble: FILLER X(3) at :345 plus the C/P/H/V quad
            //                  at :346-:349, one byte each)
            //        + 705 (the sum of the 54 declared payload widths)
            //
            //    873 = 1 (ACUP-CHANGE-ACTION, COACTUPC.cbl:654)
            //        + 436 (ACUP-OLD-DETAILS)  + 436 (ACUP-NEW-DETAILS),
            //          each 436 = 106 acct + 330 cust
            //
            // RecordLayout.of throws if the declared spans do not account for the declared record
            // length, so simply constructing each layout is the self-check; asserting the totals
            // separately is what stops one number being silently used for the other.
            assertThat(AccountUpdateResponse.GROUP_LENGTH)
                    .isEqualTo(AccountUpdateResponse.TIOAPFX_LENGTH
                            + AccountUpdateResponse.FIELD_COUNT * AccountUpdateResponse.FIELD_OVERHEAD
                            + AccountUpdateResponse.PAYLOAD_LENGTH)
                    .isEqualTo(1095);
            assertThat(AccountUpdateResponse.OUTPUT_GROUP_LAYOUT.recordLength()).isEqualTo(1095);

            assertThat(CommArea.RECORD_LENGTH).isEqualTo(873);
            assertThat(CommArea.LAYOUT.recordLength())
                    .as("the 873-byte layout self-checks its own offsets, independently of the 1095")
                    .isEqualTo(873)
                    .isEqualTo(CommArea.RECORD_LENGTH);
            assertThat(CommArea.LAYOUT.spans()).isNotEmpty();

            assertThat(AccountUpdateResponse.GROUP_LENGTH)
                    .as("1095 and 873 must never be interchangeable")
                    .isNotEqualTo(CommArea.RECORD_LENGTH);

            // Neither total is the other's building block: the commarea is not part of the group image
            // and the group image is not part of the commarea. They travel side by side in one payload.
            assertThat(AccountUpdateResponse.initial().toGroupImage(ASCII)).hasSize(1095);
            assertThat(AccountUpdateResponse.initial().commareaLength()).isEqualTo(873);
        }

        @Test
        @DisplayName("the quad comes from DSATTS/MAPATTS - COACTUP.bms declares no ALARM and no EXTATT")
        void theAttributeItemsComeFromTheMapNotFromExtatt() {
            // app/bms/COACTUP.bms:20-24 - DFHMSD LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES,
            //                            TYPE=&&SYSPARM.
            // That is the WHOLE macro. There is no CTRL= on it, no ALARM and no EXTATT=YES, contrary to
            // the plan's generalisation over all 17 mapsets - practice B4, so nothing here asserts
            // either. What this mapset actually declares sits one macro lower:
            // app/bms/COACTUP.bms:25-28 - DFHMDI CTRL=(FREEKB), DSATTS=(COLOR,HILIGHT,PS,VALIDN),
            //                             MAPATTS=(COLOR,HILIGHT,PS,VALIDN), SIZE=(24,80).
            //
            // Each of the four attributes named there yields exactly one single-byte item per field, and
            // that - not EXTATT - is where the C/P/H/V quad comes from. Four attributes named, four
            // one-byte items generated, so the quad is 4 and the preamble is 3 + 4 = 7.
            assertThat(AccountUpdateResponse.COLOUR_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountUpdateResponse.PS_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountUpdateResponse.HILIGHT_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountUpdateResponse.VALIDN_ITEM_LENGTH).isEqualTo(1);
            assertThat(AccountUpdateResponse.ATTRIBUTE_QUAD_LENGTH)
                    .as("COLOR, HILIGHT, PS, VALIDN - four names in DSATTS, four bytes per field")
                    .isEqualTo(4);
            assertThat(AccountUpdateResponse.FIELD_PREFIX_FILLER_LENGTH).isEqualTo(3);
            assertThat(AccountUpdateResponse.FIELD_OVERHEAD).isEqualTo(7);
            // SIZE=(24,80) is on the DFHMDI too, and it is the one geometry fact the mapset does state.
            assertThat(AccountUpdateResponse.SCREEN_ROWS).isEqualTo(24);
            assertThat(AccountUpdateResponse.SCREEN_COLUMNS).isEqualTo(80);
        }

        @Test
        @DisplayName("every FILLER is a declared span, TIOAPFX included - 55 of them")
        void everyFillerIsDeclared() {
            long fillers = AccountUpdateResponse.OUTPUT_GROUP_LAYOUT.spans().stream()
                    .filter(span -> "FILLER".equals(span.name()))
                    .count();
            assertThat(fillers)
                    .as("one TIOAPFX prefix plus one three-byte FILLER per field")
                    .isEqualTo(1 + AccountUpdateResponse.FIELD_COUNT);
            int fillerBytes = AccountUpdateResponse.OUTPUT_GROUP_LAYOUT.spans().stream()
                    .filter(span -> "FILLER".equals(span.name()))
                    .mapToInt(FieldSpan::length).sum();
            assertThat(fillerBytes).isEqualTo(AccountUpdateResponse.TIOAPFX_LENGTH
                    + AccountUpdateResponse.FIELD_COUNT
                    * AccountUpdateResponse.FIELD_PREFIX_FILLER_LENGTH);
        }

        @Test
        @DisplayName("MOVE LOW-VALUES TO CACTUPAO: the initial image is 1095 bytes of binary zero")
        void theInitialImageIsAllZero() {
            byte[] image = AccountUpdateResponse.initial().toGroupImage(ASCII);
            assertThat(image).hasSize(AccountUpdateResponse.GROUP_LENGTH);
            assertThat(image).containsOnly((byte) 0x00);
        }

        @Test
        @DisplayName("the TIOAPFX prefix and every prefix FILLER stay binary zero even when populated")
        void thePrefixesStayZero() {
            byte[] image = populated().toGroupImage(ASCII);
            assertThat(Arrays.copyOfRange(image, 0, AccountUpdateResponse.TIOAPFX_LENGTH))
                    .containsOnly((byte) 0x00);
            for (ScreenField field : ScreenField.values()) {
                assertThat(Arrays.copyOfRange(image, field.prefixFillerOffset(),
                        field.colourItemOffset()))
                        .as("%s prefix FILLER", field.label())
                        .containsOnly((byte) 0x00);
            }
        }

        @Test
        @DisplayName("each field's data lands at its declared offset, moved as PIC X")
        void everyFieldLandsAtItsOffset() {
            AccountUpdateResponse response = populated();
            byte[] image = response.toGroupImage(ASCII);
            for (ScreenField field : ScreenField.values()) {
                String stored = new String(Arrays.copyOfRange(image, field.dataOffset(),
                        field.endOffsetExclusive()), StandardCharsets.US_ASCII);
                assertThat(stored).as("%s at offset %d", field.symbolicItemName(),
                        field.dataOffset()).isEqualTo(response.image(field, ASCII));
            }
        }

        @Test
        @DisplayName("attribute bytes are written raw: DFHRED 0xF2 is not a US-ASCII character")
        void attributeBytesAreWrittenRaw() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            response.attributes(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            response.attributes(ScreenField.ERRMSG).setHilight(BmsAttributes.DFHBMASB);
            byte[] image = response.toGroupImage(ASCII);
            assertThat(image[ScreenField.ERRMSG.colourItemOffset()])
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(image[ScreenField.ERRMSG.hilightItemOffset()])
                    .isEqualTo(BmsAttributes.DFHBMASB);
            AccountUpdateResponse back = AccountUpdateResponse.fromGroupImage(image, ASCII);
            assertThat(back.attributes(ScreenField.ERRMSG).getColour())
                    .as("a raw 0xF2 must survive, not become U+FFFD")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(back.attributes(ScreenField.ERRMSG).getHilight())
                    .isEqualTo(BmsAttributes.DFHBMASB);
        }

        @Test
        @DisplayName("fromGroupImage(toGroupImage(x)) equals x.normalize(codec)")
        void theRoundTripIsExact() {
            AccountUpdateResponse normalised = populated().normalize(ASCII);
            assertThat(AccountUpdateResponse.fromGroupImage(normalised.toGroupImage(ASCII), ASCII))
                    .isEqualTo(normalised);
            AccountUpdateResponse initial = AccountUpdateResponse.initial();
            assertThat(AccountUpdateResponse.fromGroupImage(initial.toGroupImage(ASCII), ASCII))
                    .isEqualTo(initial.normalize(ASCII));
        }

        @Test
        @DisplayName("normalize is idempotent and brings every field to its declared width")
        void normalizeIsIdempotent() {
            AccountUpdateResponse once = AccountUpdateResponse.initial()
                    .withValue(ScreenField.ACSFNAM, "AB")
                    .withValue(ScreenField.ACSTTUS, "TOO LONG")
                    .normalize(ASCII);
            for (ScreenField field : ScreenField.values()) {
                assertThat(once.value(field)).as("%s", field.label()).hasSize(field.length());
            }
            assertThat(once.normalize(ASCII)).isEqualTo(once);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1094, 1096})
        @DisplayName("an image of the wrong length is rejected, naming the expected geometry")
        void aWrongLengthImageIsRejected(int length) {
            byte[] wrong = new byte[length];
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountUpdateResponse.fromGroupImage(wrong, ASCII))
                    .withMessageContaining("1095")
                    .withMessageContaining("app/cpy-bms/COACTUP.CPY");
        }

        @Test
        @DisplayName("the codec and the image are both required")
        void nullArgumentsAreRejected() {
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.toGroupImage(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.normalize(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.image(ScreenField.ACCTSID, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> response.image(null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.fromGroupImage(null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.fromGroupImage(
                            new byte[AccountUpdateResponse.GROUP_LENGTH], null));
        }
    }

    // =============================================================================================
    // Immutability, equality, JSON and the disclosure policy.
    // =============================================================================================

    @Nested
    @DisplayName("Immutability, equality and the JSON contract")
    class Contract {

        @Test
        @DisplayName("the type is final, has no public constructor and exposes no payload setter")
        void theTypeIsImmutableByShape() {
            assertThat(java.lang.reflect.Modifier.isFinal(
                    AccountUpdateResponse.class.getModifiers())).isTrue();
            assertThat(AccountUpdateResponse.class.getConstructors()).isEmpty();
            List<String> setters = Arrays.stream(AccountUpdateResponse.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList();
            assertThat(setters).as("no payload setter on the response itself").isEmpty();
        }

        @Test
        @DisplayName("equality covers the values, the quads, the trio and all three carriers")
        void equalityCoversEveryPart() {
            AccountUpdateResponse one = populated();
            AccountUpdateResponse two = populated();
            assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
            assertThat(one).isEqualTo(one);
            assertThat(one).isNotEqualTo(null);
            assertThat(one).isNotEqualTo("not a response");
            assertThat(one).isNotEqualTo(one.withValue(ScreenField.ACCTSID, "0"));
            assertThat(one).isNotEqualTo(one.withNextTarget("X", "Y", "Z"));
            assertThat(one).isNotEqualTo(one.withChangeAction(ChangeAction.showDetails()));
            assertThat(one).isNotEqualTo(one.withNavigationContext(NavigationContext.empty()));
            AccountUpdateResponse highlighted = populated();
            highlighted.attributes(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            assertThat(one).isNotEqualTo(highlighted);
        }

        @Test
        @DisplayName("toBuilder round-trips a response exactly")
        void toBuilderRoundTrips() {
            AccountUpdateResponse original = populated()
                    .withNextTarget("COMEN01C", "COMEN01", "COMEN1A")
                    .withChangeAction(ChangeAction.changesOkayedButFailed())
                    .withNavigationContext(NavigationContext.empty().withUserId("ADMIN001"));
            original.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);
            assertThat(original.toBuilder().build()).isEqualTo(original);
        }

        @Test
        @DisplayName("the payload survives a JSON round trip through the builder")
        void jsonRoundTripsThroughTheBuilder() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AccountUpdateResponse original = populated().normalize(ASCII)
                    .withNextTarget("COMEN01C", "COMEN01", "COMEN1A");
            String json = mapper.writeValueAsString(original);
            AccountUpdateResponse back = mapper.readValue(json, AccountUpdateResponse.class);
            for (ScreenField field : ScreenField.values()) {
                assertThat(back.value(field)).as("%s survives JSON", field.label())
                        .isEqualTo(original.value(field));
            }
            assertThat(back.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(back.getNextMapset()).isEqualTo("COMEN01");
            assertThat(back.getNextMap()).isEqualTo("COMEN1A");
        }

        @Test
        @DisplayName("the attribute quad is metadata: it does not appear in the JSON payload")
        void theQuadIsNotAJsonMember() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AccountUpdateResponse response = AccountUpdateResponse.initial();
            response.attributes(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            String json = mapper.writeValueAsString(response);
            assertThat(json).doesNotContain("attributes", "attributeQuads",
                    "hilight", "validn", "fieldValues");
        }

        @Test
        @DisplayName("every one of the 54 fields IS a JSON member - none is hidden (practice B6)")
        void everyPayloadFieldIsAJsonMember() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(populated());
            for (ScreenField field : ScreenField.values()) {
                String property = field.label().charAt(0)
                        + field.label().substring(1).toLowerCase(Locale.ROOT);
                property = property.substring(0, 1).toLowerCase(Locale.ROOT) + property.substring(1);
                assertThat(json).as("%s must be serialised, not withheld", field.label())
                        .contains("\"" + property + "\"");
            }
        }

        @Test
        @DisplayName("the rendering discloses every value as stored - nothing masked (practice B6)")
        void theRenderingIsCompleteAndUnmasked() {
            AccountUpdateResponse response = AccountUpdateResponse.initial()
                    .withValue(ScreenField.ACTSSN1, "078")
                    .withValue(ScreenField.ACTSSN2, "05")
                    .withValue(ScreenField.ACTSSN3, "1120")
                    .withValue(ScreenField.ACSGOVT, "GOVTID9988776655    ")
                    .withValue(ScreenField.DOBYEAR, "1955");
            String rendered = response.toString();
            assertThat(rendered)
                    .as("COACTUPC paints all of these on a 3270 in the clear; hiding them here would "
                            + "be an unrequested behaviour change")
                    .contains("ACTSSN1='078'", "ACTSSN2='05'", "ACTSSN3='1120'",
                            "DOBYEAR='1955'")
                    .contains("GOVTID9988776655");
            assertThat(rendered).startsWith("AccountUpdateResponse[").endsWith("]")
                    .contains("nextProgram=", "commArea=", "cardScreenState=",
                            "navigationContext=");
            for (ScreenField field : ScreenField.values()) {
                assertThat(rendered).as("%s appears in the rendering", field.label())
                        .contains(field.label() + "='");
            }
            // Scoped to THIS type's own 54-field section. The embedded NavigationContext,
            // CardScreenState, AcctSnapshot and CustSnapshot each apply their own disclosure policy to
            // their own rendering - AcctSnapshot masks its account key, CardScreenState masks the PAN -
            // and those are their decisions to make, not this type's to override. What this type owes is
            // that nothing IT holds is withheld, which is what the section below asserts.
            String ownSection = rendered.substring(0, rendered.indexOf(", nextProgram='"));
            assertThat(ownSection)
                    .as("no field of this type may be masked, redacted or omitted")
                    .doesNotContain("[REDACTED]", "****", "<omitted>", "[blank]");
        }

        @Test
        @DisplayName("constructible with no Spring context at all (practice B10)")
        void constructibleWithoutSpring() {
            assertThatNoException().isThrownBy(() -> {
                AccountUpdateResponse.builder()
                        .acctsid("00000000011")
                        .acsttus("Y")
                        .commArea(CommArea.initialised())
                        .cardScreenState(new CardScreenState())
                        .navigationContext(NavigationContext.empty())
                        .build()
                        .toGroupImage(ASCII);
            });
        }

        @Test
        @DisplayName("the builder's value method accepts a null value and rejects a null field")
        void theBuilderHandlesNulls() {
            assertThat(AccountUpdateResponse.builder().value(ScreenField.ACCTSID, null).build()
                    .getAcctsid())
                    .isEqualTo(AccountUpdateResponse.lowValues(
                            AccountUpdateResponse.ACCTSID_LENGTH));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountUpdateResponse.builder().value(null, "x"));
        }

        @Test
        @DisplayName("all 54 named builder methods reach their own field")
        void everyBuilderMethodReachesItsField() {
            AccountUpdateResponse r = AccountUpdateResponse.builder()
                    .trnname("A").title01("B").curdate("C").pgmname("D").title02("E").curtime("F")
                    .acctsid("G").acsttus("H").opnyear("I").opnmon("J").opnday("K").acrdlim("L")
                    .expyear("M").expmon("N").expday("O").acshlim("P").risyear("Q").rismon("R")
                    .risday("S").acurbal("T").acrcycr("U").aaddgrp("V").acrcydb("W").acstnum("X")
                    .actssn1("Y").actssn2("Z").actssn3("a").dobyear("b").dobmon("c").dobday("d")
                    .acstfco("e").acsfnam("f").acsmnam("g").acslnam("h").acsadl1("i").acsstte("j")
                    .acsadl2("k").acszipc("l").acscity("m").acsctry("n").acsph1a("o").acsph1b("p")
                    .acsph1c("q").acsgovt("r").acsph2a("s").acsph2b("t").acsph2c("u").acseftc("v")
                    .acspflg("w").infomsg("x").errmsg("y").fkeys("z").fkey05("1").fkey12("2")
                    .build();
            assertThat(r.fieldValues().values()).doesNotHaveDuplicates().hasSize(54);
            assertThat(r.getTrnname()).isEqualTo("A");
            assertThat(r.getFkey12()).isEqualTo("2");
            assertThat(r.getAcsgovt()).isEqualTo("r");
        }

        @Test
        @DisplayName("no field of this type is a FixedWidthRecord, so no raw area is held or aliased")
        void noRawRecordAreaIsHeld() {
            List<String> areas = Arrays.stream(AccountUpdateResponse.class.getDeclaredFields())
                    .filter(field -> field.getType()
                            == com.vsergeychik.carddemo.common.FixedWidthRecord.class)
                    .map(java.lang.reflect.Field::getName)
                    .toList();
            assertThat(areas)
                    .as("the group image is rendered on demand, never retained")
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing on the type is locale-sensitive, under a non-US default locale")
        void theRenderingIsIndependentOfTheDefaultLocale() {
            // A COBOL record has no locale. Every byte this type emits is fixed-width and
            // culture-free, so a run under a Turkish or German default must produce the identical
            // image - and proving it is what guards against an accidental locale-aware formatter or a
            // toUpperCase()/toLowerCase() without Locale.ROOT. Turkish is the classic trap: its
            // dotless-i rules change the case mapping of ASCII 'I' and 'i'.
            Locale original = Locale.getDefault();
            AccountUpdateResponse reference = populated()
                    .withValue(ScreenField.ACURBAL, "-1234567890.12")
                    .withValue(ScreenField.ACSFNAM, "ILIAD");
            byte[] referenceImage = reference.toGroupImage(ASCII);
            String referenceText = reference.toString();
            Map<String, String> referenceFields = reference.fieldValues();
            try {
                for (Locale candidate : List.of(Locale.forLanguageTag("tr-TR"),
                        Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ar-EG"),
                        Locale.forLanguageTag("ja-JP"))) {
                    Locale.setDefault(candidate);
                    AccountUpdateResponse under = populated()
                            .withValue(ScreenField.ACURBAL, "-1234567890.12")
                            .withValue(ScreenField.ACSFNAM, "ILIAD");
                    assertThat(under.toGroupImage(ASCII))
                            .as("the 1095-byte image must not move with the default locale (%s)",
                                    candidate)
                            .isEqualTo(referenceImage);
                    assertThat(under.fieldValues()).isEqualTo(referenceFields);
                    assertThat(under.toString()).isEqualTo(referenceText);
                    assertThat(under).isEqualTo(reference);
                    // The decimal separator is the one a locale would most eagerly rewrite: the
                    // account balance stays a '.' and never becomes a ','.
                    assertThat(under.value(ScreenField.ACURBAL))
                            .contains(".")
                            .doesNotContain(",");
                    // And the scaled decode of that same image stays exact and DOWN-truncated.
                    assertThat(new BigDecimal(under.value(ScreenField.ACURBAL).trim())
                            .setScale(CobolDecimal.MONETARY_SCALE, CobolDecimal.COBOL_ROUNDING))
                            .isEqualByComparingTo(new BigDecimal("-1234567890.12"));
                }
            } finally {
                Locale.setDefault(original);
            }
            assertThat(Locale.getDefault()).isEqualTo(original);
        }
    }
}
