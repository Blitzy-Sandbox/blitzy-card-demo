package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.Ct02Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.ScreenField;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TransactionViewRequest}, the inbound REST payload of CSD transaction
 * {@code CT02}: program {@code COTRN02C} (783 lines), mapset {@code COTRN02}, map {@code COTRN2A}.
 * The three CSD definitions are {@code app/csd/CARDDEMO.CSD:153} ({@code DEFINE MAPSET(COTRN02)}),
 * {@code :271} ({@code DEFINE PROGRAM(COTRN02C)}) and {@code :439} ({@code DEFINE TRANSACTION(CT02)}).
 *
 * <h2>Risk R-B: this type is named "View" but carries the <em>Add</em> map</h2>
 *
 * <p>The build prompt mandates the class name {@code TransactionViewRequest}, yet the COBOL program it
 * is paired with <strong>adds</strong> a transaction rather than viewing one. Three independent sources
 * agree, and none of them agrees with the name:
 *
 * <ol>
 *   <li>{@code app/cbl/COTRN02C.cbl:5} reads
 *       {@code * Function    : Add a new Transaction to TRANSACT file}.</li>
 *   <li>{@code README.md:224} tabulates
 *       {@code | | CT02 | COTRN02 | COTRN02C | Transaction Add |}, while {@code README.md:223} gives
 *       {@code CT01}/{@code COTRN01C} as {@code Transaction View} - the exact inverse of the mandated
 *       names.</li>
 *   <li>The map's own shape. {@code ACTIDIN X(11)}, {@code CARDNIN X(16)} and {@code CONFIRM X(1)} are
 *       present while {@code TRNIDIN} and {@code TRNID} are <em>absent</em>, and
 *       {@code app/bms/COTRN02.bms} marks 14 of the 21 named fields {@code UNPROT}. Fourteen
 *       input-capable fields plus a confirmation flag is the signature of a data-entry form; a display
 *       screen would carry a transaction id to look up and would protect everything else.</li>
 * </ol>
 *
 * <p>Rule <strong>R1</strong> of the Agent Action Plan governs the resolution: <em>the name comes from
 * the prompt, the behaviour comes from the source</em>. So the class name is kept verbatim, the field
 * set is taken from the copybook, and the divergence is <em>documented rather than corrected</em> -
 * bind <strong>B4</strong> forbids silently fixing it. {@code TransactionAddRequest} in this same
 * package is the inverse case: mandated "Add", sourced from {@code COTRN01C}, which views. These tests
 * therefore assert the {@code COTRN02} field set, and assert as a <em>negative contract</em> that no
 * transaction-id field was helpfully added to make the name true.
 *
 * <h2>Governing constraints</h2>
 *
 * <p>{@code review_rules} returns exactly one line, "No user rules provided", and that single line is
 * the whole document - so <strong>no user rule governs this file</strong>. Per the Agent Action Plan's
 * section 0.10 that absence is explicitly not permission to lower the bar, so the plan's own binds are
 * the rulings applied here: <strong>R1</strong> (name from the prompt, behaviour from the source),
 * <strong>R2</strong>/<strong>G24</strong> (truncation, never rounding - {@code ROUNDED} appears zero
 * times in all 28 programs), <strong>R4</strong>/<strong>G22</strong> (never {@code double} or
 * {@code float}), <strong>R5</strong> (fixed width is the wire format), <strong>R6</strong>/
 * <strong>G37</strong> (statelessness), <strong>B3</strong> (reference inputs immutable - the copybook,
 * mapset, program, CSD and README are read, and the expected names and widths are encoded here as test
 * constants so nothing is written back, per <strong>G5</strong>), <strong>B4</strong> (no silent scope
 * creep), <strong>B7</strong> (deterministic and non-interactive - no clock, locale, timezone or
 * default charset is consulted), <strong>B8</strong>/<strong>G52</strong> (explicit over implicit - every
 * import above is named, none is a wildcard), <strong>B9</strong>/<strong>G53</strong> (no static mutable
 * state), <strong>B11</strong> (hand-written codecs, no third-party copybook parser),
 * <strong>G9</strong> (every payload field traces to a {@code DFHMDF}), <strong>G21</strong>
 * ({@code FILLER} is a first-class span), <strong>G34</strong> ({@code REDEFINES} pairs round-trip),
 * <strong>G44</strong> (no DDL, entity annotation or version column) and <strong>G49</strong>
 * ({@code BRANCH} coverage at or above 0.90 for this package, which {@code app/java/pom.xml} enforces
 * with a {@code PACKAGE}-scoped {@code jacoco-maven-plugin} rule in addition to a {@code BUNDLE} one).
 *
 * <p><strong>G33 does not apply to this map and its absence is a finding, not an omission.</strong>
 * {@code 01 COTRN2AI} declares no {@code OCCURS} table, so there is no one-based-to-zero-based index
 * conversion here to get wrong. That holds for every symbolic map in {@code app/cpy-bms/}, none of which
 * declares {@code OCCURS} at all: BMS generates a flat, individually named item per screen field, which
 * is why even the paginated {@code COTRN00} list map spells its ten rows out longhand as
 * {@code TRNID01I} through {@code TRNID10I} rather than as a table. The {@code OCCURS} tables in this
 * codebase are in the data copybooks - {@code COMEN02Y} and {@code COADM02Y}, the menu option tables -
 * and are modelled outside this package, so G33 is verified there and simply has no subject here.
 *
 * <h2>Self-contained by construction</h2>
 *
 * <p>This class follows the structural shape of {@code TransactionListRequestTest} in this package but
 * shares no code with it: there is no base class, no fixture helper and no cross-test ordering. Every
 * expected name, width and offset below is written out literally from
 * {@code app/cpy-bms/COTRN02.CPY:17} and cross-checked against {@code app/bms/COTRN02.bms}, so these
 * assertions are independent of the production constants they check. A transcription slip on either
 * side fails the build rather than cancelling out.
 */
class TransactionViewRequestTest {

    /**
     * The charset is always named, never inherited from the platform (bind B7/B8). {@code US-ASCII} is
     * the encoding of the {@code app/data/ASCII} fixtures; the EBCDIC datasets are read as
     * {@code IBM037} elsewhere, and neither is ever the JVM default.
     */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The hand-written codec that owns the {@code PICTURE} move rules (bind B11). */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /** {@code 01 COTRN2AI} is 555 bytes: a 12-byte TIOAPFX prefix, 21 x 7 metadata, 396 payload. */
    private static final int EXPECTED_GROUP_LENGTH = 555;

    /** Sum of the 21 {@code xxxI} {@code PICTURE} widths. */
    private static final int EXPECTED_PAYLOAD_TOTAL = 396;

    /** {@code TIOAPFX=YES} on the {@code DFHMSD}, so the group opens with {@code FILLER PIC X(12)}. */
    private static final int EXPECTED_TIOAPFX_LENGTH = 12;

    /** Per field: {@code xxxL} 2 + {@code xxxF} 1 + {@code FILLER X(4)} 4. */
    private static final int EXPECTED_METADATA_PREFIX = 7;

    /**
     * {@code 01 COTRN1AI} of the {@code COTRN01} map is 575 bytes - 20 wider - even though it also
     * declares 21 fields. The two maps share 18 fields at byte-identical widths, and the whole difference
     * is the remaining three on each side: {@code COTRN01} carries {@code TRNID X(16)} +
     * {@code TRNIDIN X(16)} + {@code CARDNUM X(16)} = 48, where {@code COTRN02} carries
     * {@code ACTIDIN X(11)} + {@code CARDNIN X(16)} + {@code CONFIRM X(1)} = 28. 48 - 28 = 20 exactly.
     * Equal field counts are not equal images, which is why the total is asserted rather than assumed.
     */
    private static final int COTRN01_GROUP_LENGTH_FOR_CONTRAST = 575;

    /** {@code TRNID} + {@code TRNIDIN} + {@code CARDNUM}, the three fields only {@code COTRN01} has. */
    private static final int COTRN01_EXCLUSIVE_WIDTH = 16 + 16 + 16;

    /** {@code ACTIDIN} + {@code CARDNIN} + {@code CONFIRM}, the three fields only {@code COTRN02} has. */
    private static final int COTRN02_EXCLUSIVE_WIDTH = 11 + 16 + 1;

    /** {@code app/bms/COTRN02.bms} declares 61 {@code DFHMDF} entries in total. */
    private static final int TOTAL_DFHMDF_ENTRIES = 61;

    /** 21 of those 61 carry a name label; the other 40 are unnamed literals. */
    private static final int NAMED_DFHMDF_ENTRIES = 21;

    /** {@code COTRN02C} performs {@code MOVE -1 TO <field>L} at 35 sites. */
    private static final int MOVE_MINUS_ONE_SITES = 35;

    /** The 21 verbatim {@code xxxI} item names of {@code 01 COTRN2AI}, in copybook declaration order. */
    private static final List<String> EXPECTED_INPUT_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "ACTIDINI", "CARDNINI", "TTYPCDI", "TCATCDI", "TRNSRCI", "TDESCI",
            "TRNAMTI", "TORIGDTI", "TPROCDTI", "MIDI", "MNAMEI", "MCITYI",
            "MZIPI", "CONFIRMI", "ERRMSGI");

    /**
     * The 14 {@code UNPROT} labels of {@code app/bms/COTRN02.bms}, in mapset order. {@code ACTIDIN} at
     * {@code :85} additionally carries {@code IC}, so it holds the initial cursor. This count is itself
     * the strongest structural evidence for R-B.
     */
    private static final List<String> EXPECTED_INPUT_CAPABLE = List.of(
            "ACTIDIN", "CARDNIN", "TTYPCD", "TCATCD", "TRNSRC", "TDESC", "TRNAMT",
            "TORIGDT", "TPROCDT", "MID", "MNAME", "MCITY", "MZIP", "CONFIRM");

    /** The 7 {@code ASKIP} output-only labels: screen furniture plus the error line at {@code :293}. */
    private static final List<String> EXPECTED_OUTPUT_ONLY = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ERRMSG");

    /** label -&gt; declared width, from {@code COTRN02.CPY:17} cross-checked against {@code COTRN02.bms}. */
    private static final Map<String, Integer> EXPECTED_WIDTHS = buildExpectedWidths();

    /** label -&gt; absolute offset of the {@code xxxL} item, that is the {@code k} of the field overlay. */
    private static final Map<String, Integer> EXPECTED_GROUP_OFFSETS = buildExpectedGroupOffsets();

    private static Map<String, Integer> buildExpectedWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("TRNNAME", 4);
        widths.put("TITLE01", 40);
        widths.put("CURDATE", 8);
        widths.put("PGMNAME", 8);
        widths.put("TITLE02", 40);
        widths.put("CURTIME", 8);
        widths.put("ACTIDIN", 11);
        widths.put("CARDNIN", 16);
        widths.put("TTYPCD", 2);
        widths.put("TCATCD", 4);
        widths.put("TRNSRC", 10);
        widths.put("TDESC", 60);
        widths.put("TRNAMT", 12);
        widths.put("TORIGDT", 10);
        widths.put("TPROCDT", 10);
        widths.put("MID", 9);
        widths.put("MNAME", 30);
        widths.put("MCITY", 25);
        widths.put("MZIP", 10);
        widths.put("CONFIRM", 1);
        widths.put("ERRMSG", 78);
        // Collections.unmodifiableMap over a LinkedHashMap, deliberately, not Map.copyOf: copyOf gives an
        // immutable map whose iteration order is unspecified, and this map's order IS the copybook's
        // declaration order, which every offset below is derived from.
        return Collections.unmodifiableMap(widths);
    }

    /**
     * The {@code k} of each field, derived here from the copybook's own stride rather than copied from
     * production: the first group starts after the 12-byte TIOAPFX prefix, and each subsequent group
     * starts 7 metadata bytes plus the previous payload width further on.
     */
    private static Map<String, Integer> buildExpectedGroupOffsets() {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int cursor = EXPECTED_TIOAPFX_LENGTH;
        for (Map.Entry<String, Integer> field : EXPECTED_WIDTHS.entrySet()) {
            offsets.put(field.getKey(), cursor);
            cursor += EXPECTED_METADATA_PREFIX + field.getValue();
        }
        return Collections.unmodifiableMap(offsets);
    }

    /** The label order of {@code EXPECTED_WIDTHS}, which is copybook declaration order. */
    private static List<String> expectedLabels() {
        return List.copyOf(EXPECTED_WIDTHS.keySet());
    }

    /** A request with every payload field set to a distinguishable, width-exact value. */
    private static TransactionViewRequest populated() {
        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.setPayloadValue(field, sampleOf(field));
        }
        return request;
    }

    /**
     * A deterministic value of exactly the field's declared width: the label, then dots. No clock and no
     * randomness, so a failure reproduces byte for byte (bind B7).
     */
    private static String sampleOf(ScreenField field) {
        return CODEC.movePicX(field.label(), field.length()).replace(' ', '.');
    }

    // =============================================================================================
    // Section 2 - structure and widths. Rule R5, gates G9 and G21.
    // =============================================================================================

    @Test
    void projectsExactlyTwentyOnePayloadFieldsInCopybookOrder() {
        assertThat(ScreenField.values()).hasSize(NAMED_DFHMDF_ENTRIES);
        assertThat(TransactionViewRequest.PAYLOAD_FIELD_COUNT).isEqualTo(NAMED_DFHMDF_ENTRIES);

        List<String> actual = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            actual.add(field.inputItem());
        }
        // Order matters as much as membership: the copybook's declaration order is the byte order.
        assertThat(actual).containsExactlyElementsOf(EXPECTED_INPUT_ITEMS);
    }

    @Test
    void everyPayloadFieldTracesToANamedDfhmdfEntry() {
        // G9. app/bms/COTRN02.bms declares 61 DFHMDF entries. Exactly 21 carry a name label and become
        // payload fields; the other 40 are unnamed literals - the screen's captions, separators and the
        // "F3=Exit" style footer - which CICS paints but never returns, so they have no symbolic-map
        // items and cannot be payload members. 21 + 40 = 61 accounts for every entry in the mapset.
        assertThat(NAMED_DFHMDF_ENTRIES + 40).isEqualTo(TOTAL_DFHMDF_ENTRIES);

        List<String> labels = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            labels.add(field.label());
        }
        assertThat(labels).containsExactlyElementsOf(expectedLabels());
        // Every label is either input-capable or output-only, with no field in both sets and none in
        // neither, so the projection covers the named entries exactly once.
        assertThat(labels).hasSameElementsAs(concat(EXPECTED_INPUT_CAPABLE, EXPECTED_OUTPUT_ONLY));
        assertThat(EXPECTED_INPUT_CAPABLE).doesNotContainAnyElementsOf(EXPECTED_OUTPUT_ONLY);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> joined = new ArrayList<>(first);
        joined.addAll(second);
        return joined;
    }

    @Test
    void carriesNeitherTrnidinNorTrnidBecauseThisIsTheAddMap() {
        // The R-B negative contract, and the structural heart of it. COTRN02C generates the new
        // transaction id itself by browsing TRANSACT backward - STARTBR, then READPREV to reach the
        // highest existing key, then ENDBR - and incrementing it. The operator never types an id, so the
        // map has no field to receive one. Adding TRNIDIN or TRNID to make the class name read true would
        // invent a screen field that CICS would never transmit and would move every later byte offset.
        Set<String> projected = new LinkedHashSet<>();
        for (ScreenField field : ScreenField.values()) {
            projected.add(field.label());
            projected.add(field.inputItem());
            projected.add(field.outputItem());
        }
        assertThat(projected).doesNotContain("TRNID", "TRNIDI", "TRNIDO", "TRNIDIN", "TRNIDINI",
                "TRNIDINO");

        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDI")).isFalse();
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDINI")).isFalse();

        // COTRN01 also names its card field CARDNUM, where this map names it CARDNIN. Both are X(16), so a
        // mix-up would compile, pass a width check and land the value in the right span - and still be
        // wrong, because the item name is what a field-for-field diff keys on.
        assertThat(projected).doesNotContain("CARDNUM", "CARDNUMI", "CARDNUMO");
        assertThat(projected).contains("CARDNIN", "CARDNINI", "CARDNINO");
    }

    @Test
    void carriesTheAccountKeyCardKeyAndConfirmFlagThatOnlyTheAddMapHas() {
        // The R-B positive contract. None of these three exists on COTRN01: an account key to post
        // against, a card key to post against, and a confirmation flag to gate the write.
        assertThat(TransactionViewRequest.ACTIDIN_LENGTH).isEqualTo(11);
        assertThat(TransactionViewRequest.CARDNIN_LENGTH).isEqualTo(16);
        assertThat(TransactionViewRequest.CONFIRM_LENGTH).isEqualTo(1);

        assertThat(ScreenField.ACTIDIN.length()).isEqualTo(11);
        assertThat(ScreenField.CARDNIN.length()).isEqualTo(16);
        assertThat(ScreenField.CONFIRM.length()).isEqualTo(1);

        // All three are input-capable, which is what makes them keys the operator supplies rather than
        // values the program displays.
        assertThat(ScreenField.ACTIDIN.unprotectedField()).isTrue();
        assertThat(ScreenField.CARDNIN.unprotectedField()).isTrue();
        assertThat(ScreenField.CONFIRM.unprotectedField()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void everyFieldMatchesItsDeclaredWidthAndGroupOffset(ScreenField field) {
        assertThat(field.length()).isEqualTo(EXPECTED_WIDTHS.get(field.label()));
        assertThat(field.groupOffset()).isEqualTo(EXPECTED_GROUP_OFFSETS.get(field.label()));
        // payloadOffset is k+7: past the 2-byte length item, the 1-byte flag and the 4-byte filler.
        assertThat(field.payloadOffset())
                .isEqualTo(field.groupOffset() + EXPECTED_METADATA_PREFIX);
    }

    @Test
    void byteTotalsMatchTheCopybookArithmetic() {
        int summedWidths = 0;
        for (ScreenField field : ScreenField.values()) {
            summedWidths += field.length();
        }
        assertThat(summedWidths).isEqualTo(EXPECTED_PAYLOAD_TOTAL);
        assertThat(TransactionViewRequest.PAYLOAD_WIDTH_TOTAL).isEqualTo(EXPECTED_PAYLOAD_TOTAL);

        assertThat(TransactionViewRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(EXPECTED_TIOAPFX_LENGTH);
        assertThat(TransactionViewRequest.LENGTH_ITEM_LENGTH).isEqualTo(2);
        assertThat(TransactionViewRequest.FLAG_ITEM_LENGTH).isEqualTo(1);
        assertThat(TransactionViewRequest.RESERVED_FILLER_LENGTH).isEqualTo(4);
        assertThat(TransactionViewRequest.METADATA_PREFIX_LENGTH).isEqualTo(EXPECTED_METADATA_PREFIX);

        // 12 + 21x7 + 396 = 555, spelled out so the identity is visible rather than asserted as a bare
        // constant.
        assertThat(EXPECTED_TIOAPFX_LENGTH
                + NAMED_DFHMDF_ENTRIES * EXPECTED_METADATA_PREFIX
                + EXPECTED_PAYLOAD_TOTAL).isEqualTo(EXPECTED_GROUP_LENGTH);
        assertThat(TransactionViewRequest.AI_GROUP_LENGTH).isEqualTo(EXPECTED_GROUP_LENGTH);
        assertThat(TransactionViewRequest.AI_LAYOUT.recordLength()).isEqualTo(EXPECTED_GROUP_LENGTH);
    }

    @Test
    void equalFieldCountsAreNotEqualImagesWhichIsWhyTheTotalIsAsserted() {
        // COTRN01 declares 21 fields too, and its group image is 575 - twenty bytes wider. Asserting the
        // difference keeps a future edit from "harmonising" the two maps on the strength of the matching
        // field count.
        assertThat(COTRN01_GROUP_LENGTH_FOR_CONTRAST - EXPECTED_GROUP_LENGTH).isEqualTo(20);
        assertThat(TransactionViewRequest.AI_GROUP_LENGTH)
                .isNotEqualTo(COTRN01_GROUP_LENGTH_FOR_CONTRAST);

        // And the 20 bytes are accounted for exactly: 18 fields are shared at identical widths, so the
        // delta is entirely the three exclusive fields on each side. 48 - 28 = 20.
        assertThat(COTRN01_EXCLUSIVE_WIDTH).isEqualTo(48);
        assertThat(COTRN02_EXCLUSIVE_WIDTH).isEqualTo(28);
        assertThat(COTRN01_EXCLUSIVE_WIDTH - COTRN02_EXCLUSIVE_WIDTH).isEqualTo(20);

        // This map's three exclusive fields are present at exactly those widths, and 18 + 3 = 21.
        assertThat(ScreenField.ACTIDIN.length()
                + ScreenField.CARDNIN.length()
                + ScreenField.CONFIRM.length()).isEqualTo(COTRN02_EXCLUSIVE_WIDTH);
        assertThat(18 + 3).isEqualTo(NAMED_DFHMDF_ENTRIES);
        // The three COTRN01-exclusive names appear nowhere in this projection.
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDI")).isFalse();
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("TRNIDINI")).isFalse();
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("CARDNUMI")).isFalse();
    }

    @Test
    void layoutSelfCheckProvesTheGroupGeometryIsGapless() {
        // Walk the declared spans in offset order and require them to abut with no gap and no overlap.
        // A missing FILLER would show up here as a gap long before it showed up as a wrong total.
        List<FieldSpan> spans = new ArrayList<>(TransactionViewRequest.AI_LAYOUT.storageSpans());
        spans.sort((left, right) -> Integer.compare(left.offset(), right.offset()));

        int cursor = 0;
        for (FieldSpan span : spans) {
            assertThat(span.offset())
                    .as("span %s must begin exactly where the previous span ended", span.name())
                    .isEqualTo(cursor);
            cursor = span.endOffsetExclusive();
        }
        assertThat(cursor).isEqualTo(EXPECTED_GROUP_LENGTH);
    }

    @Test
    void rendersExactlyFiveHundredAndFiftyFiveBytes() {
        assertThat(populated().toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
        // The default instance renders the same width: an unfilled request is still a full group.
        assertThat(new TransactionViewRequest().toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
    }

    @Test
    void everyFieldLandsAtItsTrueOffsetInTheImage() {
        TransactionViewRequest request = populated();
        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image).hasSize(EXPECTED_GROUP_LENGTH);

        for (ScreenField field : ScreenField.values()) {
            int start = EXPECTED_GROUP_OFFSETS.get(field.label()) + EXPECTED_METADATA_PREFIX;
            assertThat(image.substring(start, start + field.length()))
                    .as("payload of %s at offset %d", field.inputItem(), start)
                    .isEqualTo(sampleOf(field));
        }
    }

    @Test
    void rejectsAWorkAreaOfTheWrongWidth() {
        FixedWidthRecord tooNarrow = new FixedWidthRecord(EXPECTED_GROUP_LENGTH - 1, ASCII);
        assertThatThrownBy(() -> populated().writeInto(tooNarrow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(EXPECTED_GROUP_LENGTH));
        assertThatThrownBy(() -> TransactionViewRequest.readFrom(tooNarrow))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeIntoAndReadFromAgreeWithTheCodecPath() {
        TransactionViewRequest request = populated();

        FixedWidthRecord area = FixedWidthRecord.forLayout(TransactionViewRequest.AI_LAYOUT, ASCII);
        request.writeInto(area);

        // The record-area path and the byte-array path must produce identical bytes, or the two entry
        // points have drifted.
        assertThat(area.toByteArray()).isEqualTo(request.toFixedWidth(ASCII));

        TransactionViewRequest viaRecord = TransactionViewRequest.readFrom(area);
        TransactionViewRequest viaBytes =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        for (ScreenField field : ScreenField.values()) {
            assertThat(viaRecord.payloadValue(field)).isEqualTo(sampleOf(field));
            assertThat(viaBytes.payloadValue(field)).isEqualTo(sampleOf(field));
        }
    }

    @Test
    void spacePaddedValuesSurviveAFixedWidthRoundTripUntrimmed() {
        // R5: the wire format is fixed width, so trailing spaces are data. Nothing in the read path may
        // trim them, or a field that CICS transmitted as blanks would come back shorter than declared.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc("GROCERY");
        request.setMname("ACME");

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);

        assertThat(restored.getTdesc())
                .hasSize(TransactionViewRequest.TDESC_LENGTH)
                .isEqualTo("GROCERY" + " ".repeat(TransactionViewRequest.TDESC_LENGTH - 7));
        assertThat(restored.getMname())
                .hasSize(TransactionViewRequest.MNAME_LENGTH)
                .startsWith("ACME")
                .endsWith(" ");
    }


    // =============================================================================================
    // Section 3 - the metadata boundary. AAP 0.6.3: payload names and widths come from the xxxI items
    // only; xxxL, xxxF and xxxA are validation and highlight metadata and never JSON members.
    // =============================================================================================

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void derivesTheFourSymbolicMapItemNamesTheWayBmsDoes(ScreenField field) {
        // BMS forms every symbolic-map item by suffixing the DFHMDF label, so the label is the single
        // source of all five names and none of them can drift independently.
        assertThat(field.inputItem()).isEqualTo(field.label() + "I");
        assertThat(field.outputItem()).isEqualTo(field.label() + "O");
        assertThat(field.lengthItem()).isEqualTo(field.label() + "L");
        assertThat(field.flagItem()).isEqualTo(field.label() + "F");
        assertThat(field.attributeItem()).isEqualTo(field.label() + "A");
    }

    @Test
    void metadataAndDerivedPredicatesNeverAppearOnTheWire() throws Exception {
        TransactionViewRequest request = populated();
        request.requestCursor(ScreenField.TRNAMT);
        request.metadata(ScreenField.CONFIRM).setAttribute('*');

        String json = new ObjectMapper().writeValueAsString(request);

        // All 21 payload keys are present...
        for (String inputItem : EXPECTED_INPUT_ITEMS) {
            String property = payloadPropertyOf(inputItem);
            assertThat(json)
                    .as("payload property for %s", inputItem)
                    .contains("\"" + property + "\"");
        }
        // ...and none of the 63 metadata item names is, in any casing a mapper might choose.
        for (ScreenField field : ScreenField.values()) {
            assertThat(json).doesNotContain(field.lengthItem(), field.flagItem(),
                    field.attributeItem());
            assertThat(json.toLowerCase(Locale.ROOT))
                    .doesNotContain(field.lengthItem().toLowerCase(Locale.ROOT));
        }
        // The metadata map itself and the derived predicates are absent too, so no client can mistake a
        // cursor request or a context test for part of the payload.
        assertThat(json).doesNotContain("fieldMetadata", "cursorRequested", "enterContext",
                "reenterContext", "commareaLength", "navigationContextPresent");
    }

    /** {@code TRNNAMEI} is carried as the bean property {@code trnname}: the item name less its suffix. */
    private static String payloadPropertyOf(String inputItem) {
        String stem = inputItem.substring(0, inputItem.length() - 1);
        return stem.toLowerCase(Locale.ROOT);
    }

    @Test
    void jsonRoundTripPreservesTrailingSpacesAndEquality() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TransactionViewRequest request = populated();
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        request.setAid("PF03 ");

        String json = mapper.writeValueAsString(request);
        TransactionViewRequest restored = mapper.readValue(json, TransactionViewRequest.class);

        for (ScreenField field : ScreenField.values()) {
            assertThat(restored.payloadValue(field))
                    .as("%s survives JSON byte for byte", field.inputItem())
                    .isEqualTo(sampleOf(field));
        }
        assertThat(restored.getAid()).isEqualTo("PF03 ");
        // R6/G37: the conversation state travelled in the payload, so it is still there afterwards.
        assertThat(restored.getNavigationContext()).isEqualTo(request.getNavigationContext());
        assertThat(restored.isReenterContext()).isTrue();
    }

    @Test
    void lengthItemIsSignedAndHoldsMinusOneWithoutClamping() {
        // COTRN02C performs MOVE -1 TO <field>L at 35 sites - more than any other program in this
        // package, and a direct consequence of its 14 input-capable fields each needing to claim the
        // cursor on its own validation failure. xxxL is COMP PIC S9(4), a signed binary halfword, so -1
        // must round-trip as -1 and never as an unsigned reinterpretation such as 65535.
        assertThat(MOVE_MINUS_ONE_SITES).isEqualTo(35);
        assertThat(TransactionViewRequest.CURSOR_REQUEST).isEqualTo(-1);

        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.requestCursor(field);
            assertThat(request.metadata(field).length()).isEqualTo(-1);
            assertThat(request.metadata(field).length()).isNotEqualTo(65_535);
            assertThat(request.isCursorRequested(field)).isTrue();
            // A cursor request is not input: -1 is a position claim, not a keyed length.
            assertThat(request.metadata(field).hasInput()).isFalse();
        }

        FieldMetadata metadata = new FieldMetadata();
        metadata.setLength(-1);
        assertThat(metadata.isCursorRequested()).isTrue();
        metadata.setLength(Short.MIN_VALUE);
        assertThat(metadata.length()).isEqualTo(Short.MIN_VALUE);
        metadata.setLength(Short.MAX_VALUE);
        assertThat(metadata.length()).isEqualTo(Short.MAX_VALUE);
    }

    @Test
    void lengthItemRejectsValuesOutsideASignedHalfword() {
        FieldMetadata metadata = new FieldMetadata();
        assertThatThrownBy(() -> metadata.setLength(Short.MAX_VALUE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S9(4)");
        assertThatThrownBy(() -> metadata.setLength(Short.MIN_VALUE - 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataDefaultsToNoInputAndResetsBackToIt() {
        TransactionViewRequest request = populated();
        for (ScreenField field : ScreenField.values()) {
            assertThat(request.metadata(field).length()).isEqualTo(FieldMetadata.NO_INPUT_LENGTH);
            assertThat(request.metadata(field).hasInput()).isFalse();
            assertThat(request.metadata(field).attribute()).isEqualTo(FieldMetadata.UNSET_ATTRIBUTE);
        }

        request.metadata(ScreenField.TRNAMT).setLength(12);
        assertThat(request.metadata(ScreenField.TRNAMT).hasInput()).isTrue();
        request.metadata(ScreenField.TDESC).setAttribute('*');
        request.requestCursor(ScreenField.CONFIRM);

        request.resetMetadata();
        for (ScreenField field : ScreenField.values()) {
            assertThat(request.metadata(field).hasInput()).isFalse();
            assertThat(request.isCursorRequested(field)).isFalse();
            assertThat(request.metadata(field).attribute()).isEqualTo(FieldMetadata.UNSET_ATTRIBUTE);
        }
    }

    // =============================================================================================
    // Section 4 - REDEFINES round-trips and the per-field byte overlay. Gates G34 and G21.
    //
    // At each field's offset k the AI view lays out:
    //   k   .. k+1     xxxL   COMP PIC S9(4)             2 bytes, signed
    //   k+2            xxxF   PICTURE X                  1 byte
    //   k+2            xxxA   via FILLER REDEFINES xxxF  the SAME byte, adding 0 to the width
    //   k+3 .. k+6     FILLER PICTURE X(4)               4 bytes
    //   k+7 .. k+6+n   xxxI   PIC X(n)                   n bytes
    // The AO view redefines the same span as FILLER X(3), then xxxC/xxxP/xxxH/xxxV, then xxxO X(n) -
    // so xxxO occupies byte-for-byte the span the AI view calls xxxI.
    // =============================================================================================

    @Test
    void attributeAndFlagAreOneByteViewedThroughTwoNames() {
        // G34. FILLER REDEFINES TRNNAMEF adds nothing to the group: xxxA is a second name for the single
        // byte at k+2, not a second byte. Writing through either name must be visible through the other.
        TransactionViewRequest request = new TransactionViewRequest();
        FieldMetadata metadata = request.metadata(ScreenField.ACTIDIN);

        metadata.setFlag('X');
        assertThat(metadata.attribute()).isEqualTo('X');
        metadata.setAttribute('Y');
        assertThat(metadata.flag()).isEqualTo('Y');

        // Round-trip every value a single byte can hold through both accessors.
        for (char candidate : new char[] {'\u0000', ' ', '*', 'A', 'z', '\u00ff'}) {
            metadata.setFlag(candidate);
            assertThat(metadata.attribute()).isEqualTo(candidate);
            metadata.setAttribute(candidate);
            assertThat(metadata.flag()).isEqualTo(candidate);
        }

        // The redefinition adds no width: the group stays 555 whatever the attribute byte holds.
        assertThat(request.toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void metadataSpansAccountForEveryPrefixByteAndAreFiller(ScreenField field) {
        // G21. The three prefix spans are first-class FILLER, not gaps to be skipped. If any were
        // dropped the group total would fall below 555 and every later field would shift left.
        List<FieldSpan> spans = field.metadataSpans();
        assertThat(spans).hasSize(3);

        int k = field.groupOffset();
        assertThat(spans.get(0).offset()).isEqualTo(k);
        assertThat(spans.get(0).length()).isEqualTo(2);          // xxxL at k..k+1
        assertThat(spans.get(1).offset()).isEqualTo(k + 2);
        assertThat(spans.get(1).length()).isEqualTo(1);          // xxxF, and xxxA, at k+2
        assertThat(spans.get(2).offset()).isEqualTo(k + 3);
        assertThat(spans.get(2).length()).isEqualTo(4);          // FILLER X(4) at k+3..k+6

        for (FieldSpan span : spans) {
            assertThat(span.kind().filler())
                    .as("prefix span at offset %d is FILLER", span.offset())
                    .isTrue();
        }
        assertThat(spans.get(2).endOffsetExclusive()).isEqualTo(field.payloadOffset());
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void payloadSpanOccupiesTheIdenticalBytesTheOutputViewCallsXxxO(ScreenField field) {
        FieldSpan payload = field.payloadSpan();
        assertThat(payload.name()).isEqualTo(field.inputItem());
        assertThat(payload.offset()).isEqualTo(field.groupOffset() + EXPECTED_METADATA_PREFIX);
        assertThat(payload.length()).isEqualTo(field.length());
        assertThat(payload.endOffsetExclusive())
                .isEqualTo(field.groupOffset() + EXPECTED_METADATA_PREFIX + field.length());

        // The layout resolves the span by its verbatim copybook name, and to the same bytes.
        FieldSpan resolved = TransactionViewRequest.AI_LAYOUT.span(field.inputItem());
        assertThat(resolved.offset()).isEqualTo(payload.offset());
        assertThat(resolved.length()).isEqualTo(payload.length());
    }

    @Test
    void leadingTioapfxFillerIsPresentAndSpaceFilled() {
        // TIOAPFX=YES on the DFHMSD, so the group opens with FILLER PIC X(12). Omitting it would shift
        // every one of the 21 fields twelve bytes left and change the total to 543.
        FieldSpan prefix = null;
        for (FieldSpan span : TransactionViewRequest.AI_LAYOUT.storageSpans()) {
            if (span.offset() == 0) {
                prefix = span;
            }
        }
        assertThat(prefix).isNotNull();
        assertThat(prefix.length()).isEqualTo(EXPECTED_TIOAPFX_LENGTH);
        assertThat(prefix.kind().filler()).isTrue();

        String image = new String(populated().toFixedWidth(ASCII), ASCII);
        assertThat(image.substring(0, EXPECTED_TIOAPFX_LENGTH))
                .isEqualTo(" ".repeat(EXPECTED_TIOAPFX_LENGTH));
    }

    @Test
    void perFieldFillerBytesAreSpaceFilledInTheRenderedImage() {
        String image = new String(populated().toFixedWidth(ASCII), ASCII);
        for (ScreenField field : ScreenField.values()) {
            int k = field.groupOffset();
            // The 4-byte reserved FILLER at k+3..k+6 renders as spaces, so it is emitted rather than
            // skipped. Its presence is what keeps xxxI at k+7.
            assertThat(image.substring(k + 3, k + 7))
                    .as("reserved FILLER of %s", field.label())
                    .isEqualTo("    ");
        }
    }

    @Test
    void nullFieldRendersAsLowValuesReproducingAFieldCicsDidNotTransmit() {
        // A null member is not an empty string: CICS leaves an untransmitted field at LOW-VALUES, and the
        // codec reproduces that rather than substituting spaces, because the two are different bytes.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc(null);

        String image = new String(request.toFixedWidth(ASCII), ASCII);
        int start = ScreenField.TDESC.payloadOffset();
        assertThat(image.substring(start, start + TransactionViewRequest.TDESC_LENGTH))
                .isEqualTo("\u0000".repeat(TransactionViewRequest.TDESC_LENGTH));

        assertThat(TransactionViewRequest.lowValues(3)).isEqualTo("\u0000\u0000\u0000");
        assertThat(TransactionViewRequest.spaces(3)).isEqualTo("   ");
        assertThat(TransactionViewRequest.spaces(0)).isEmpty();
        assertThat(TransactionViewRequest.lowValues(0)).isEmpty();
    }

    @Test
    void figurativeConstantsRejectANegativeWidth() {
        assertThatThrownBy(() -> TransactionViewRequest.spaces(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPACES");
        assertThatThrownBy(() -> TransactionViewRequest.lowValues(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOW-VALUES");
    }

    @Test
    void payloadImagesAreKeyedByVerbatimCopybookNames() {
        Map<String, String> images = populated().payloadImages();
        assertThat(images.keySet()).containsExactlyElementsOf(EXPECTED_INPUT_ITEMS);
        for (ScreenField field : ScreenField.values()) {
            assertThat(images.get(field.inputItem()))
                    .hasSize(field.length())
                    .isEqualTo(sampleOf(field));
        }
    }


    // =============================================================================================
    // Section 5 - the positional string masks and the confirm topology.
    //
    // COTRN02C:339-352 validates TRNAMT with an ordered EVALUATE TRUE whose four WHEN clauses each test
    // a substring POSITION of the same PIC X(12) field:
    //
    //     WHEN TRNAMTI OF COTRN2AI(1:1)  NOT EQUAL '-' AND '+'
    //     WHEN TRNAMTI OF COTRN2AI(2:8)  NOT NUMERIC
    //     WHEN TRNAMTI OF COTRN2AI(10:1) NOT = '.'
    //     WHEN TRNAMTI OF COTRN2AI(11:2) IS NOT NUMERIC
    //     WHEN OTHER  CONTINUE
    //
    // failing with 'Amount should be in format -99999999.99'. Positions 1 + 2-9 + 10 + 11-12 cover all
    // twelve bytes. COTRN02C:354-360 validates TORIGDT X(10) the same way - (1:4) numeric, (5:1) '-',
    // (6:2) numeric, (8:1) '-', (9:2) numeric - and :368-380 repeats it verbatim for TPROCDT.
    //
    // These are POSITIONAL STRING contracts, never numeric parses. The DTO holds the raw 12- or
    // 10-character value and the shape is checked position by position, which is precisely why the member
    // must stay String (R4/G22). Note COBOL's 1-based (start:length) reference against Java's 0-based
    // substring: (2:8) is characters 2 through 9, that is substring(1, 9).
    // =============================================================================================

    /** Mirrors {@code (start:length)} exactly: COBOL counts from 1, Java from 0. */
    private static String refMod(String value, int oneBasedStart, int length) {
        return value.substring(oneBasedStart - 1, oneBasedStart - 1 + length);
    }

    /** COBOL's {@code NUMERIC} class test for a {@code PIC X} span: every character a digit. */
    private static boolean isNumeric(String span) {
        if (span.isEmpty()) {
            return false;
        }
        for (int index = 0; index < span.length(); index++) {
            if (span.charAt(index) < '0' || span.charAt(index) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Clause 1 of the amount mask: {@code (1:1) NOT EQUAL '-' AND '+'}. */
    private static boolean amountSignClauseFails(String amount) {
        String sign = refMod(amount, 1, 1);
        return !"-".equals(sign) && !"+".equals(sign);
    }

    /** Clause 2: {@code (2:8) NOT NUMERIC} - the eight integer digits. */
    private static boolean amountIntegerClauseFails(String amount) {
        return !isNumeric(refMod(amount, 2, 8));
    }

    /** Clause 3: {@code (10:1) NOT = '.'} - the decimal point. */
    private static boolean amountPointClauseFails(String amount) {
        return !".".equals(refMod(amount, 10, 1));
    }

    /** Clause 4: {@code (11:2) IS NOT NUMERIC} - the two fraction digits. */
    private static boolean amountFractionClauseFails(String amount) {
        return !isNumeric(refMod(amount, 11, 2));
    }

    /** The whole ordered EVALUATE: the first failing clause wins, WHEN OTHER continues. */
    private static boolean amountMaskRejects(String amount) {
        return amountSignClauseFails(amount)
                || amountIntegerClauseFails(amount)
                || amountPointClauseFails(amount)
                || amountFractionClauseFails(amount);
    }

    /** The five-clause {@code YYYY-MM-DD} mask shared by {@code TORIGDT} and {@code TPROCDT}. */
    private static boolean dateMaskRejects(String date) {
        return !isNumeric(refMod(date, 1, 4))
                || !"-".equals(refMod(date, 5, 1))
                || !isNumeric(refMod(date, 6, 2))
                || !"-".equals(refMod(date, 8, 1))
                || !isNumeric(refMod(date, 9, 2));
    }

    @Test
    void amountIsTwelveCharactersMatchingTheEditMask() {
        // WS-TRAN-AMT at COTRN02C:53 is PIC +99999999.99: a sign, eight integer digits, a point and two
        // fraction digits - twelve characters, which is exactly TRNAMT's declared width.
        assertThat(TransactionViewRequest.TRNAMT_LENGTH).isEqualTo(12);
        assertThat("+99999999.99").hasSize(TransactionViewRequest.TRNAMT_LENGTH);
        assertThat(1 + 8 + 1 + 2).isEqualTo(TransactionViewRequest.TRNAMT_LENGTH);
    }

    @Test
    void theFourAmountClausesPartitionAllTwelveBytes() {
        // Positions 1, 2-9, 10 and 11-12 tile the field with no gap and no overlap, so no byte of TRNAMT
        // escapes validation. If the partition had a hole, a junk character could pass unnoticed.
        boolean[] covered = new boolean[TransactionViewRequest.TRNAMT_LENGTH];
        markCovered(covered, 1, 1);
        markCovered(covered, 2, 8);
        markCovered(covered, 10, 1);
        markCovered(covered, 11, 2);
        for (int index = 0; index < covered.length; index++) {
            assertThat(covered[index]).as("byte %d of TRNAMT is validated", index + 1).isTrue();
        }
    }

    private static void markCovered(boolean[] covered, int oneBasedStart, int length) {
        for (int offset = 0; offset < length; offset++) {
            int index = oneBasedStart - 1 + offset;
            assertThat(covered[index]).as("byte %d is claimed once only", index + 1).isFalse();
            covered[index] = true;
        }
    }

    @ParameterizedTest
    @CsvSource({
        // value,        sign,  integer, point, fraction   - each clause's own outcome
        "'+00000123.45', false, false,   false, false",   // all four pass: WHEN OTHER, CONTINUE
        "'-00000123.45', false, false,   false, false",   // a minus sign passes clause 1 too
        "' 00000123.45', true,  false,   false, false",   // clause 1 fails: blank sign
        "'*00000123.45', true,  false,   false, false",   // clause 1 fails: junk sign
        "'+0000012A.45', false, true,    false, false",   // clause 2 fails: letter among the digits
        "'+0000012 .45', false, true,    false, false",   // clause 2 fails: embedded blank
        "'+00000123,45', false, false,   true,  false",   // clause 3 fails: comma, not a point
        "'+00000123x45', false, false,   true,  false",   // clause 3 fails: junk separator
        "'+00000123.4A', false, false,   false, true",    // clause 4 fails: letter in the fraction
        "'+00000123.  ', false, false,   false, true"     // clause 4 fails: blank fraction
    })
    void everyAmountClauseIsDrivenToBothOutcomes(String amount,
                                                 boolean signFails,
                                                 boolean integerFails,
                                                 boolean pointFails,
                                                 boolean fractionFails) {
        assertThat(amount).hasSize(TransactionViewRequest.TRNAMT_LENGTH);

        assertThat(amountSignClauseFails(amount)).isEqualTo(signFails);
        assertThat(amountIntegerClauseFails(amount)).isEqualTo(integerFails);
        assertThat(amountPointClauseFails(amount)).isEqualTo(pointFails);
        assertThat(amountFractionClauseFails(amount)).isEqualTo(fractionFails);

        boolean rejected = signFails || integerFails || pointFails || fractionFails;
        assertThat(amountMaskRejects(amount)).isEqualTo(rejected);

        // The DTO carries the value verbatim either way. Validation is the program's job, not the
        // payload's: a rejected amount still has to survive the round trip so COTRN02C can redisplay it
        // on the error screen with the cursor on the field.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(amount);
        assertThat(request.getTrnamt()).isEqualTo(amount);

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getTrnamt()).isEqualTo(amount);
        assertThat(amountMaskRejects(restored.getTrnamt())).isEqualTo(rejected);
    }

    @Test
    void theAllPassAmountIsAcceptedAndKeptCharacterForCharacter() {
        String accepted = "+00000123.45";
        assertThat(amountMaskRejects(accepted)).isFalse();

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(accepted);
        // No normalisation, no reformatting, no leading-zero stripping: the edited image is the value.
        assertThat(request.payloadValue(ScreenField.TRNAMT)).isEqualTo(accepted);
        assertThat(request.payloadImages().get("TRNAMTI")).isEqualTo(accepted);
    }

    @Test
    void aNaiveNumericParseWouldAcceptWhatThePositionalMaskRejects() {
        // This is the concrete reason TRNAMT must stay a String. "+00000123,45" is a perfectly good
        // decimal in a comma-decimal locale, and a locale-aware numeric parse would accept it and hand
        // back 123.45. The COBOL does not parse: it tests position 10 for '.', so the comma fails clause
        // 3 and the operator gets 'Amount should be in format -99999999.99'. Promoting the member to
        // BigDecimal would silently move that boundary and change observable behaviour.
        String commaDecimal = "+00000123,45";
        assertThat(amountPointClauseFails(commaDecimal)).isTrue();
        assertThat(amountMaskRejects(commaDecimal)).isTrue();

        // A locale-aware parse of the same characters succeeds, which is the divergence being guarded.
        String asNumberInCommaLocale = commaDecimal.replace(',', '.');
        assertThat(new BigDecimal(asNumberInCommaLocale.substring(1)))
                .isEqualByComparingTo(new BigDecimal("123.45"));

        // The same trap on the date mask: '2022/07/18' is a date any lenient parser takes, and the
        // positional test rejects it because position 5 is not '-'.
        assertThat(dateMaskRejects("2022/07/18")).isTrue();
        assertThat(dateMaskRejects("2022-07-18")).isFalse();

        // And the DTO stores both verbatim, because rejecting at the payload boundary would pre-empt the
        // COBOL's own editing and change which message the operator sees.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(commaDecimal);
        request.setTorigdt("2022/07/18");
        assertThat(request.getTrnamt()).isEqualTo(commaDecimal);
        assertThat(request.getTorigdt()).isEqualTo("2022/07/18");
    }

    @ParameterizedTest
    @CsvSource({
        // value,       year,  dash1, month, dash2, day
        "'2022-07-18', false, false, false, false, false",   // all five pass
        "'20A2-07-18', true,  false, false, false, false",   // clause 1 fails: non-numeric year
        "'2022/07-18', false, true,  false, false, false",   // clause 2 fails: wrong first separator
        "'2022-A7-18', false, false, true,  false, false",   // clause 3 fails: non-numeric month
        "'2022-07/18', false, false, false, true,  false",   // clause 4 fails: wrong second separator
        "'2022-07-1A', false, false, false, false, true",    // clause 5 fails: non-numeric day
        "'    -  -  ', true,  false, true,  false, true"     // an all-blank date fails three clauses
    })
    void everyDateClauseIsDrivenToBothOutcomesForBothDateFields(String date,
                                                               boolean yearFails,
                                                               boolean dash1Fails,
                                                               boolean monthFails,
                                                               boolean dash2Fails,
                                                               boolean dayFails) {
        assertThat(date).hasSize(TransactionViewRequest.TORIGDT_LENGTH);
        assertThat(TransactionViewRequest.TORIGDT_LENGTH)
                .isEqualTo(TransactionViewRequest.TPROCDT_LENGTH);

        assertThat(!isNumeric(refMod(date, 1, 4))).isEqualTo(yearFails);
        assertThat(!"-".equals(refMod(date, 5, 1))).isEqualTo(dash1Fails);
        assertThat(!isNumeric(refMod(date, 6, 2))).isEqualTo(monthFails);
        assertThat(!"-".equals(refMod(date, 8, 1))).isEqualTo(dash2Fails);
        assertThat(!isNumeric(refMod(date, 9, 2))).isEqualTo(dayFails);

        boolean rejected = yearFails || dash1Fails || monthFails || dash2Fails || dayFails;
        assertThat(dateMaskRejects(date)).isEqualTo(rejected);

        // TPROCDT repeats TORIGDT's clauses verbatim at COTRN02C:368-380, so both fields carry the value
        // unchanged and both are checked by the same shape.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTorigdt(date);
        request.setTprocdt(date);

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getTorigdt()).isEqualTo(date);
        assertThat(restored.getTprocdt()).isEqualTo(date);
        assertThat(dateMaskRejects(restored.getTorigdt())).isEqualTo(rejected);
        assertThat(dateMaskRejects(restored.getTprocdt())).isEqualTo(rejected);
    }

    @Test
    void theFiveDateClausesPartitionAllTenBytes() {
        boolean[] covered = new boolean[TransactionViewRequest.TORIGDT_LENGTH];
        markCovered(covered, 1, 4);
        markCovered(covered, 5, 1);
        markCovered(covered, 6, 2);
        markCovered(covered, 8, 1);
        markCovered(covered, 9, 2);
        for (int index = 0; index < covered.length; index++) {
            assertThat(covered[index]).as("byte %d of the date is validated", index + 1).isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The CONFIRM topology. COTRN02C:169 reads EVALUATE CONFIRMI OF COTRN2AI with seven WHEN clauses
    // that collapse to three outcomes:
    //
    //   'Y' or 'y'                        -> PERFORM ADD-TRANSACTION
    //   'N', 'n', SPACES or LOW-VALUES    -> 'Confirm to add this transaction...'
    //   anything else                     -> 'Invalid value. Valid values are (Y/N)...'
    //
    // The non-obvious point is that BLANK GROUPS WITH 'N'/'n': an empty confirm is "not yet confirmed",
    // not an invalid value, so the operator is prompted rather than scolded.
    //
    // ReportRequestRequest's CONFIRM in this same package has a DIFFERENT topology - CORPT00C handles
    // blank in a separate prior IF, before its own EVALUATE - so the two must not be unified behind a
    // shared helper even though both fields are a one-character Y/N flag.
    // ---------------------------------------------------------------------------------------------

    /** The three outcomes of the confirm EVALUATE, in source order. */
    private enum ConfirmOutcome { PROCEED, NOT_YET_CONFIRMED, INVALID }

    private static ConfirmOutcome confirmOutcome(String confirm) {
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            return ConfirmOutcome.PROCEED;
        }
        // SPACES and LOW-VALUES share this arm with 'N' and 'n'.
        if ("N".equals(confirm) || "n".equals(confirm)
                || " ".equals(confirm) || "\u0000".equals(confirm) || confirm == null) {
            return ConfirmOutcome.NOT_YET_CONFIRMED;
        }
        return ConfirmOutcome.INVALID;
    }

    @ParameterizedTest
    @CsvSource({
        "Y, PROCEED",
        "y, PROCEED",
        "N, NOT_YET_CONFIRMED",
        "n, NOT_YET_CONFIRMED",
        "X, INVALID",
        "1, INVALID",
        "*, INVALID"
    })
    void confirmCollapsesSevenClausesIntoThreeOutcomes(String confirm, String expected) {
        assertThat(confirm).hasSize(TransactionViewRequest.CONFIRM_LENGTH);
        assertThat(confirmOutcome(confirm)).isEqualTo(ConfirmOutcome.valueOf(expected));

        TransactionViewRequest request = new TransactionViewRequest();
        request.setConfirm(confirm);
        assertThat(request.getConfirm()).isEqualTo(confirm);

        // Case is preserved on the wire: the program tests 'Y' and 'y' separately rather than folding
        // case, so upshifting here would collapse two distinct WHEN clauses into one.
        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getConfirm()).isEqualTo(confirm);
        assertThat(confirmOutcome(restored.getConfirm()))
                .isEqualTo(ConfirmOutcome.valueOf(expected));
    }

    @Test
    void blankConfirmGroupsWithNoRatherThanBeingInvalid() {
        // SPACES and LOW-VALUES both take the 'N' arm. This is the branch most likely to be "tidied" into
        // the invalid arm by someone reading the field as required, which would replace the prompt
        // 'Confirm to add this transaction...' with 'Invalid value. Valid values are (Y/N)...'.
        String spaces = TransactionViewRequest.spaces(TransactionViewRequest.CONFIRM_LENGTH);
        String lowValues = TransactionViewRequest.lowValues(TransactionViewRequest.CONFIRM_LENGTH);

        assertThat(confirmOutcome(spaces)).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
        assertThat(confirmOutcome(lowValues)).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
        assertThat(confirmOutcome("N")).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
        assertThat(confirmOutcome(spaces)).isEqualTo(confirmOutcome("N"));
        assertThat(confirmOutcome(lowValues)).isEqualTo(confirmOutcome("n"));

        // Neither is the invalid outcome, and neither proceeds with the add.
        assertThat(confirmOutcome(spaces)).isNotEqualTo(ConfirmOutcome.INVALID);
        assertThat(confirmOutcome(lowValues)).isNotEqualTo(ConfirmOutcome.PROCEED);

        // A default request is blank, so a freshly painted screen is "not yet confirmed".
        TransactionViewRequest fresh = new TransactionViewRequest();
        assertThat(fresh.getConfirm()).isEqualTo(spaces);
        assertThat(confirmOutcome(fresh.getConfirm())).isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);

        // An untransmitted field arrives as LOW-VALUES and lands on the same arm.
        TransactionViewRequest untransmitted = new TransactionViewRequest();
        untransmitted.setConfirm(null);
        String image = new String(untransmitted.toFixedWidth(ASCII), ASCII);
        int start = ScreenField.CONFIRM.payloadOffset();
        assertThat(image.substring(start, start + 1)).isEqualTo(lowValues);
        assertThat(confirmOutcome(TransactionViewRequest
                .fromFixedWidth(untransmitted.toFixedWidth(ASCII), ASCII).getConfirm()))
                .isEqualTo(ConfirmOutcome.NOT_YET_CONFIRMED);
    }

    @Test
    void allThreeConfirmOutcomesAreReachable() {
        // Exhaustive over the outcome enum, so an outcome that became unreachable would fail here.
        Set<ConfirmOutcome> reached = new LinkedHashSet<>();
        for (String candidate : new String[] {"Y", "y", "N", "n", " ", "\u0000", "X", "?", "0"}) {
            reached.add(confirmOutcome(candidate));
        }
        assertThat(reached).containsExactlyInAnyOrder(ConfirmOutcome.values());
    }


    // =============================================================================================
    // Section 6 - the commarea extension, statelessness, cross-width MOVE and the structural negatives.
    //
    // COTRN02C:72-80 declares CDEMO-CT02-INFO immediately after COPY COCOM01Y:
    //   CDEMO-CT02-TRNID-FIRST   PIC X(16)
    //   CDEMO-CT02-TRNID-LAST    PIC X(16)
    //   CDEMO-CT02-PAGE-NUM      PIC 9(08)
    //   CDEMO-CT02-NEXT-PAGE-FLG PIC X(01) VALUE 'N'  with 88 levels 'Y' / 'N'
    //   CDEMO-CT02-TRN-SEL-FLG   PIC X(01)
    //   CDEMO-CT02-TRN-SELECTED  PIC X(16)
    // = 58 bytes, so this screen's passed commarea is 160 + 58 = 218.
    // =============================================================================================

    @Test
    void theCommareaExtensionIsFiftyEightBytesFieldByField() {
        assertThat(Ct02Info.TRNID_FIRST_LENGTH).isEqualTo(16);
        assertThat(Ct02Info.TRNID_LAST_LENGTH).isEqualTo(16);
        assertThat(Ct02Info.PAGE_NUM_LENGTH).isEqualTo(8);
        assertThat(Ct02Info.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
        assertThat(Ct02Info.TRN_SEL_FLG_LENGTH).isEqualTo(1);
        assertThat(Ct02Info.TRN_SELECTED_LENGTH).isEqualTo(16);

        // 16 + 16 + 8 + 1 + 1 + 16 = 58, spelled out rather than asserted as a bare constant.
        assertThat(16 + 16 + 8 + 1 + 1 + 16).isEqualTo(58);
        assertThat(Ct02Info.CT02_INFO_LENGTH).isEqualTo(58);

        // The offsets abut in declaration order with no gap.
        assertThat(Ct02Info.TRNID_FIRST_OFFSET).isZero();
        assertThat(Ct02Info.TRNID_LAST_OFFSET).isEqualTo(16);
        assertThat(Ct02Info.PAGE_NUM_OFFSET).isEqualTo(32);
        assertThat(Ct02Info.NEXT_PAGE_FLG_OFFSET).isEqualTo(40);
        assertThat(Ct02Info.TRN_SEL_FLG_OFFSET).isEqualTo(41);
        assertThat(Ct02Info.TRN_SELECTED_OFFSET).isEqualTo(42);
        assertThat(Ct02Info.TRN_SELECTED_OFFSET + Ct02Info.TRN_SELECTED_LENGTH)
                .isEqualTo(Ct02Info.CT02_INFO_LENGTH);

        assertThat(Ct02Info.LAYOUT.recordLength()).isEqualTo(58);
    }

    @Test
    void commareaFieldNamesAreVerbatimCobolNames() {
        assertThat(Ct02Info.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT02-TRNID-FIRST");
        assertThat(Ct02Info.TRNID_LAST_FIELD).isEqualTo("CDEMO-CT02-TRNID-LAST");
        assertThat(Ct02Info.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT02-PAGE-NUM");
        assertThat(Ct02Info.NEXT_PAGE_FLG_FIELD).isEqualTo("CDEMO-CT02-NEXT-PAGE-FLG");
        assertThat(Ct02Info.TRN_SEL_FLG_FIELD).isEqualTo("CDEMO-CT02-TRN-SEL-FLG");
        assertThat(Ct02Info.TRN_SELECTED_FIELD).isEqualTo("CDEMO-CT02-TRN-SELECTED");
    }

    @Test
    void navigationContextStaysAtOneHundredAndSixtyBytesAndIsNeverWidened() {
        // COCOM01Y is 34 + 84 + 12 + 16 + 14 = 160. The CT02 extension is carried alongside it as a
        // separate nested group, never by widening the shared carrier - widening it would change the
        // commarea of all 17 online programs at once.
        assertThat(NavigationContext.GENERAL_INFO_LENGTH
                + NavigationContext.CUSTOMER_INFO_LENGTH
                + NavigationContext.ACCOUNT_INFO_LENGTH
                + NavigationContext.CARD_INFO_LENGTH
                + NavigationContext.MORE_INFO_LENGTH).isEqualTo(160);
        assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        assertThat(NavigationContext.empty().toFixedWidth(CODEC)).hasSize(160);

        // 160 + 58 = 218.
        assertThat(NavigationContext.COMMAREA_LENGTH + Ct02Info.CT02_INFO_LENGTH).isEqualTo(218);
        assertThat(Ct02Info.COMMAREA_TOTAL_LENGTH).isEqualTo(218);
    }

    @Test
    void theCommareaExtensionIsANestedStaticTypeNotAWidenedCarrier() {
        Class<?> nested = Ct02Info.class;
        assertThat(nested.getEnclosingClass()).isEqualTo(TransactionViewRequest.class);
        assertThat(Modifier.isStatic(nested.getModifiers()))
                .as("Ct02Info must be static so it carries no hidden reference to its enclosing request")
                .isTrue();
        // It is not a subtype of the shared carrier, and does not extend it.
        assertThat(NavigationContext.class.isAssignableFrom(nested)).isFalse();
    }

    @Test
    void commareaLengthIsTwoHundredAndEighteenOnlyWhenAnAreaWasPassed() {
        // COTRN02C:115 tests EIBCALEN = 0 to detect "nothing was passed". A request with no carrier
        // reports length 0, which is that state; one with a carrier reports the full 218.
        TransactionViewRequest fresh = new TransactionViewRequest();
        assertThat(fresh.hasNavigationContext()).isFalse();
        assertThat(fresh.commareaLength()).isZero();

        fresh.setNavigationContext(NavigationContext.empty());
        assertThat(fresh.hasNavigationContext()).isTrue();
        assertThat(fresh.commareaLength()).isEqualTo(218);

        fresh.setNavigationContext(null);
        assertThat(fresh.hasNavigationContext()).isFalse();
        assertThat(fresh.commareaLength()).isZero();
    }

    @Test
    void bothEightyEightLevelsAreReachableAndNeitherIsTheOthersNegation() {
        // 88 NEXT-PAGE-YES VALUE 'Y' and 88 NEXT-PAGE-NO VALUE 'N' are not exhaustive over a PIC X(01),
        // so a third value satisfies neither. Modelling one as the negation of the other would invent a
        // totality the copybook does not declare.
        Ct02Info cursor = new Ct02Info();
        assertThat(Ct02Info.NEXT_PAGE_YES).isEqualTo("Y");
        assertThat(Ct02Info.NEXT_PAGE_NO).isEqualTo("N");

        // The VALUE clause default is 'N'.
        assertThat(Ct02Info.NEXT_PAGE_DEFAULT).isEqualTo("N");
        assertThat(cursor.getNextPageFlg()).isEqualTo("N");
        assertThat(cursor.isNextPageNo()).isTrue();
        assertThat(cursor.isNextPageYes()).isFalse();

        cursor.setNextPageYes();
        assertThat(cursor.isNextPageYes()).isTrue();
        assertThat(cursor.isNextPageNo()).isFalse();

        cursor.setNextPageNo();
        assertThat(cursor.isNextPageNo()).isTrue();
        assertThat(cursor.isNextPageYes()).isFalse();

        // The third state: neither 88 level is satisfied.
        cursor.setNextPageFlg(" ");
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.isNextPageNo()).isFalse();

        cursor.setNextPageFlg(null);
        assertThat(cursor.isNextPageYes()).isFalse();
        assertThat(cursor.isNextPageNo()).isFalse();
    }

    @Test
    void bothProgramContextStatesAreDrivenAndNeitherIsTheOthersNegation() {
        // 88 CDEMO-PGM-ENTER VALUE 0 and 88 CDEMO-PGM-REENTER VALUE 1 over PIC 9(01). ENTER paints the
        // screen; REENTER validates what was typed and is the only state in which the CSSETATY error
        // highlight applies. A third digit satisfies neither.
        assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
        assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

        TransactionViewRequest request = new TransactionViewRequest();
        // With no carrier at all, neither predicate holds: there is no context to be in.
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmEnter());
        assertThat(request.isEnterContext()).isTrue();
        assertThat(request.isReenterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(request.isReenterContext()).isTrue();
        assertThat(request.isEnterContext()).isFalse();

        request.setNavigationContext(NavigationContext.empty().withPgmContext(9));
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();
    }

    @Test
    void theCursorGroupRendersFiftyEightBytesAndRoundTrips() {
        Ct02Info cursor = new Ct02Info("0000000000000001", "0000000000000042", 7, "Y", "S",
                "0000000000000013");

        byte[] image = cursor.toFixedWidth(ASCII);
        assertThat(image).hasSize(58);

        String text = new String(image, ASCII);
        assertThat(text.substring(0, 16)).isEqualTo("0000000000000001");
        assertThat(text.substring(16, 32)).isEqualTo("0000000000000042");
        // PIC 9(08) is zero-filled on the left, the opposite of a PIC X receiver.
        assertThat(text.substring(32, 40)).isEqualTo("00000007");
        assertThat(text.substring(40, 41)).isEqualTo("Y");
        assertThat(text.substring(41, 42)).isEqualTo("S");
        assertThat(text.substring(42, 58)).isEqualTo("0000000000000013");

        Ct02Info restored = Ct02Info.fromFixedWidth(image, ASCII);
        assertThat(restored).isEqualTo(cursor);
        assertThat(restored.hashCode()).isEqualTo(cursor.hashCode());
        assertThat(restored.getPageNum()).isEqualTo(7);
        assertThat(restored.isNextPageYes()).isTrue();
    }

    @Test
    void theCursorGroupRendersNullMembersAsSpaces() {
        Ct02Info cursor = new Ct02Info(null, null, 0, null, null, null);
        String text = new String(cursor.toFixedWidth(ASCII), ASCII);
        assertThat(text).hasSize(58);
        assertThat(text.substring(0, 32)).isEqualTo(" ".repeat(32));
        assertThat(text.substring(32, 40)).isEqualTo("00000000");
        assertThat(text.substring(40, 42)).isEqualTo("  ");
        assertThat(text.substring(42, 58)).isEqualTo(" ".repeat(16));
    }

    @Test
    void pageNumberIsAnUnsignedEightDigitField() {
        Ct02Info cursor = new Ct02Info();
        cursor.setPageNum(99_999_999);
        assertThat(cursor.getPageNum()).isEqualTo(99_999_999);
        cursor.setPageNum(0);
        assertThat(cursor.getPageNum()).isZero();

        // PIC 9(08) is unsigned and eight digits wide, so neither a negative nor a ninth digit fits.
        assertThatThrownBy(() -> cursor.setPageNum(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsigned");
        assertThatThrownBy(() -> cursor.setPageNum(100_000_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 digits");
    }

    @Test
    void aNullCursorGroupIsReplacedByAFreshOneRatherThanLeftAbsent() {
        // COTRN02C reads the CT02 group only on the branch where an area was passed, so the group has no
        // absence semantics of its own - unlike the commarea, whose absence is EIBCALEN = 0.
        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.getCt02Info()).isNotNull();

        request.setCt02Info(null);
        assertThat(request.getCt02Info()).isNotNull();
        assertThat(request.getCt02Info().getNextPageFlg()).isEqualTo("N");

        Ct02Info supplied = new Ct02Info();
        supplied.setPageNum(3);
        request.setCt02Info(supplied);
        assertThat(request.getCt02Info()).isSameAs(supplied);
    }

    // ---------------------------------------------------------------------------------------------
    // R6 / G37 - statelessness, and G53 - no static mutable state.
    // ---------------------------------------------------------------------------------------------

    @Test
    void carriesNoServerSideSessionStateOfAnyKind() {
        // R6/G37. All conversation state travels in the payload: the 58-byte CT02 group and the 160-byte
        // commarea are request members, not server-side objects. Nothing may reference a session, a
        // thread-local or a request-scoped holder, because a stateless REST projection of a
        // pseudo-conversational transaction is the whole point.
        for (Class<?> type : typesUnderTest()) {
            for (Field field : type.getDeclaredFields()) {
                String fieldType = field.getType().getName();
                assertThat(fieldType)
                        .as("%s.%s must not hold server-side conversation state", type.getSimpleName(),
                                field.getName())
                        .doesNotContain("HttpSession")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("HttpServletRequest")
                        .doesNotContain("RequestContextHolder")
                        .doesNotContain("SecurityContext");
            }
        }
    }

    @Test
    void holdsNoStaticMutableState() {
        // G53/B9. COBOL WORKING-STORAGE must never become static Java fields: that would leak one
        // request's screen into the next and make the tests order-dependent. Every static is a final
        // constant; every mutable field is an instance field.
        for (Class<?> type : typesUnderTest()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static %s.%s must be final", type.getSimpleName(), field.getName())
                            .isTrue();
                    // A final reference to a mutable collection would still be shared state.
                    assertThat(Map.class.isAssignableFrom(field.getType())
                            || List.class.isAssignableFrom(field.getType())
                            || Set.class.isAssignableFrom(field.getType()))
                            .as("static %s.%s must not be a shared mutable collection",
                                    type.getSimpleName(), field.getName())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void twoRequestsShareNoState() {
        // The practical consequence of holding no static state: two instances are fully independent.
        TransactionViewRequest first = new TransactionViewRequest();
        TransactionViewRequest second = new TransactionViewRequest();

        first.setTrnamt("+00000123.45");
        first.requestCursor(ScreenField.TRNAMT);
        first.getCt02Info().setPageNum(5);

        assertThat(second.getTrnamt())
                .isEqualTo(TransactionViewRequest.spaces(TransactionViewRequest.TRNAMT_LENGTH));
        assertThat(second.isCursorRequested(ScreenField.TRNAMT)).isFalse();
        assertThat(second.getCt02Info().getPageNum()).isZero();
        assertThat(second.getCt02Info()).isNotSameAs(first.getCt02Info());
    }

    // ---------------------------------------------------------------------------------------------
    // Rule R5 - cross-width MOVE. Every move from the 350-byte TRAN-RECORD of app/cpy/CVTRA05Y.cpy goes
    // through FixedWidthCodec.movePicX so the direction of truncation is deliberate rather than accidental.
    // ---------------------------------------------------------------------------------------------

    @Test
    void crossWidthMovesTruncateOnTheRightAndKeepTheLeadingCharacters() {
        // TRAN-DESC X(100) -> TDESCI X(60) keeps the FIRST 60 characters. A PIC X receiver is filled from
        // its leftmost position and the overflow is discarded, so "the first 60" - never the last 60.
        String desc100 = "D".repeat(59) + "X" + "Z".repeat(40);
        assertThat(desc100).hasSize(100);

        String moved = CODEC.movePicX(desc100, TransactionViewRequest.TDESC_LENGTH);
        assertThat(moved).hasSize(60).isEqualTo("D".repeat(59) + "X").doesNotContain("Z");

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTdesc(moved);
        assertThat(request.payloadImages().get("TDESCI")).isEqualTo(moved);
    }

    @ParameterizedTest
    @CsvSource({
        // sending field from CVTRA05Y, sending width, receiving xxxI, receiving width
        "TRAN-DESC,          100, TDESCI,   60",
        "TRAN-MERCHANT-NAME,  50, MNAMEI,   30",
        "TRAN-MERCHANT-CITY,  50, MCITYI,   25",
        "TRAN-ORIG-TS,        26, TORIGDTI, 10",
        "TRAN-PROC-TS,        26, TPROCDTI, 10"
    })
    void narrowingMovesKeepTheLeadingBytes(String sender, int senderWidth, String receiver,
                                           int receiverWidth) {
        assertThat(senderWidth).isGreaterThan(receiverWidth);
        assertThat(TransactionViewRequest.AI_LAYOUT.span(receiver).length()).isEqualTo(receiverWidth);

        // A ruler makes the surviving span self-evident: position i holds the digit i mod 10.
        StringBuilder ruler = new StringBuilder();
        for (int index = 0; index < senderWidth; index++) {
            ruler.append((char) ('0' + index % 10));
        }
        String moved = CODEC.movePicX(ruler.toString(), receiverWidth);

        assertThat(moved).hasSize(receiverWidth);
        assertThat(moved).isEqualTo(ruler.substring(0, receiverWidth));
        assertThat(sender).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
        // exact-width moves: no truncation, no padding, the value passes through untouched
        "TRAN-CARD-NUM,     CARDNINI, 16",
        "TRAN-SOURCE,       TRNSRCI,  10",
        "TRAN-TYPE-CD,      TTYPCDI,   2",
        "TRAN-MERCHANT-ZIP, MZIPI,    10"
    })
    void exactWidthMovesPassThroughUntouched(String sender, String receiver, int width) {
        assertThat(TransactionViewRequest.AI_LAYOUT.span(receiver).length()).isEqualTo(width);

        String value = "9".repeat(width);
        assertThat(CODEC.movePicX(value, width)).isEqualTo(value).hasSize(width);
        assertThat(sender).isNotBlank();
    }

    @Test
    void numericSendersLandInAlphanumericReceiversAsCharacters() {
        // TRAN-CAT-CD 9(04) -> TCATCDI X(4) and TRAN-MERCHANT-ID 9(09) -> MIDI X(9). The receiver is
        // alphanumeric, so the digits arrive as characters and no numeric type is introduced anywhere on
        // the payload boundary.
        assertThat(TransactionViewRequest.TCATCD_LENGTH).isEqualTo(4);
        assertThat(TransactionViewRequest.MID_LENGTH).isEqualTo(9);

        // A PIC 9 sender is zero-filled on the LEFT - the opposite direction to a PIC X receiver.
        assertThat(CODEC.movePic9("42", 4)).isEqualTo("0042");
        assertThat(CODEC.movePic9(123_456_789L, 9)).isEqualTo("123456789");

        TransactionViewRequest request = new TransactionViewRequest();
        request.setTcatcd(CODEC.movePic9("42", TransactionViewRequest.TCATCD_LENGTH));
        request.setMid(CODEC.movePic9(7L, TransactionViewRequest.MID_LENGTH));
        assertThat(request.getTcatcd()).isEqualTo("0042");
        assertThat(request.getMid()).isEqualTo("000000007");

        // The members are still String: a numeric source does not make a numeric member.
        assertThat(request.getTcatcd()).isInstanceOf(String.class);
        assertThat(request.getMid()).isInstanceOf(String.class);
    }

    @Test
    void theAmountIsNeverAssignedFromTheScaledFieldDirectly() {
        // TRAN-AMT is PIC S9(09)V99 in the record, but TRNAMT on the screen is the 12-character edited
        // image under +99999999.99. The DTO exposes no scaled accessor at all, so there is no path by
        // which a BigDecimal could be assigned to the field without being rendered through the mask first.
        for (Method method : TransactionViewRequest.class.getMethods()) {
            if (method.getName().equalsIgnoreCase("getTrnamt")
                    || method.getName().equalsIgnoreCase("setTrnamt")) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter).isEqualTo(String.class);
                }
                if (method.getName().startsWith("get")) {
                    assertThat(method.getReturnType()).isEqualTo(String.class);
                }
            }
        }

        // Rendering the mask by hand and storing the image is the only route, and it preserves the width.
        String rendered = "+00000123.45";
        assertThat(rendered).hasSize(TransactionViewRequest.TRNAMT_LENGTH);
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnamt(rendered);
        assertThat(request.getTrnamt()).isEqualTo(rendered);
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void shortValuesAreRightSpacePaddedToWidthNeitherShortNorNull(ScreenField field) {
        // Never left short, never null on the wire: the image is always the declared width, padded on the
        // right for a PIC X receiver.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setPayloadValue(field, "A");

        String image = request.payloadImages().get(field.inputItem());
        assertThat(image).isNotNull().hasSize(field.length()).startsWith("A");
        if (field.length() > 1) {
            assertThat(image).isEqualTo("A" + " ".repeat(field.length() - 1)).endsWith(" ");
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void overWideValuesTruncateOnTheRightPerThePicXRule(ScreenField field) {
        TransactionViewRequest request = new TransactionViewRequest();
        String tooLong = "Q".repeat(field.length() + 5) + "TAIL";
        request.setPayloadValue(field, tooLong);

        String image = request.payloadImages().get(field.inputItem());
        assertThat(image).hasSize(field.length()).isEqualTo("Q".repeat(field.length()));
        assertThat(image).doesNotContain("TAIL");
    }

    // ---------------------------------------------------------------------------------------------
    // The structural negatives: no masking, all-String members, no rounding surface, no persistence.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theAccountKeyCardKeyAndMerchantIdAreCarriedUnmasked() {
        // COTRN02C displays ACTIDIN, CARDNIN and MID in full. Masking, redacting or partially obscuring
        // any of the three on the payload path would be a behaviour change and a parity violation.
        String acct = "00000000011";
        String card = "4111111111111111";
        String merchant = "000000042";

        TransactionViewRequest request = new TransactionViewRequest();
        request.setActidin(acct);
        request.setCardnin(card);
        request.setMid(merchant);

        // Getters, the enum-keyed accessor, the rendered images and the wire bytes all agree, in full.
        assertThat(request.getActidin()).isEqualTo(acct);
        assertThat(request.getCardnin()).isEqualTo(card);
        assertThat(request.getMid()).isEqualTo(merchant);
        assertThat(request.payloadValue(ScreenField.ACTIDIN)).isEqualTo(acct);
        assertThat(request.payloadValue(ScreenField.CARDNIN)).isEqualTo(card);
        assertThat(request.payloadValue(ScreenField.MID)).isEqualTo(merchant);
        assertThat(request.payloadImages().get("ACTIDINI")).isEqualTo(acct);
        assertThat(request.payloadImages().get("CARDNINI")).isEqualTo(card);
        assertThat(request.payloadImages().get("MIDI")).isEqualTo(merchant);

        String image = new String(request.toFixedWidth(ASCII), ASCII);
        assertThat(image).contains(acct).contains(card).contains(merchant);
        // No mask character and no redaction sentinel has been substituted anywhere in the payload. The
        // masking helper writes '*' and one of two "[redacted]" spellings, so all three are excluded.
        assertThat(image).doesNotContain("*");
        assertThat(image.toLowerCase(Locale.ROOT)).doesNotContain("redacted");

        TransactionViewRequest restored =
                TransactionViewRequest.fromFixedWidth(request.toFixedWidth(ASCII), ASCII);
        assertThat(restored.getCardnin()).isEqualTo(card);
    }

    @Test
    void theWirePathIsUnmaskedEvenThoughTheDiagnosticPathRedacts() {
        // A deliberate distinction, recorded rather than papered over. toString is a diagnostic channel
        // and routes ACTIDIN as an identifier and CARDNIN as a PAN, so a log line does not leak them; MID
        // and every other field render plainly. That redaction is a logging concern only - it never
        // touches the payload, the rendered images or the 555-byte wire image, all of which are asserted
        // unmasked above. Documenting the boundary is what bind B4 requires; unifying the two channels in
        // either direction would either leak a PAN into the log or corrupt the wire.
        String card = "4111111111111111";
        TransactionViewRequest request = new TransactionViewRequest();
        request.setCardnin(card);
        request.setActidin("00000000011");
        request.setMid("000000042");

        assertThat(request.toString()).doesNotContain(card);
        // The wire image of the very same instance still carries it in full.
        assertThat(new String(request.toFixedWidth(ASCII), ASCII)).contains(card);
        // MID is classified plain, so diagnostics show it and parity failures stay readable.
        assertThat(request.toString()).contains("000000042");
        // The rendering is keyed by the copybook item names, so a diff reads against the copybook.
        assertThat(request.toString()).contains("MIDI=").contains("CONFIRMI=").contains("TRNAMTI=");
    }

    @Test
    void theDiagnosticRenderingMarksTheCursorFieldAndCarriesBothStateObjects() {
        // Which field claimed the cursor is part of what a request means, so a diagnostic that omitted it
        // would be unreadable on exactly the failures that matter - the 35 MOVE -1 sites.
        TransactionViewRequest request = populated();

        // Without a cursor request, no field is marked.
        assertThat(request.toString()).doesNotContain("(cursor)");

        request.requestCursor(ScreenField.TRNAMT);
        String marked = request.toString();
        assertThat(marked).contains("(cursor)");
        // The marker follows the field that claimed it, not some other field.
        assertThat(marked.indexOf("(cursor)")).isGreaterThan(marked.indexOf("TRNAMTI="));
        assertThat(marked.indexOf("(cursor)")).isLessThan(marked.indexOf("TORIGDTI="));

        // Both carried state objects appear, in the absent case and the present case.
        assertThat(request.toString()).contains("navigationContext=null");
        request.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(request.toString())
                .contains("navigationContext=")
                .doesNotContain("navigationContext=null")
                .contains("ct02Info=");

        // The identity prefix names the transaction, the program and the symbolic group.
        assertThat(request.toString()).startsWith("TransactionViewRequest[CT02/COTRN02C/COTRN2AI");
    }

    @Test
    void everyPayloadMemberIsAStringAndNoneIsDoubleOrFloat() throws ReflectiveOperationException {
        // R4/G22. Every PIC X field is a String; no member anywhere in the type or its nested types is a
        // double or a float, whether primitive or boxed. The only numeric member in the whole projection
        // is CDEMO-CT02-PAGE-NUM, an int, because PIC 9(08) is a scale-free integer.
        for (ScreenField field : ScreenField.values()) {
            Method getter = TransactionViewRequest.class
                    .getMethod("get" + field.label().charAt(0)
                            + field.label().substring(1).toLowerCase(Locale.ROOT));
            assertThat(getter.getReturnType())
                    .as("accessor for %s returns String", field.inputItem())
                    .isEqualTo(String.class);
        }

        for (Class<?> type : typesUnderTest()) {
            for (Field member : type.getDeclaredFields()) {
                assertThat(member.getType())
                        .as("%s.%s", type.getSimpleName(), member.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }
    }

    @Test
    void exposesNoRoundingSurfaceAtAllSoNoRoundingModeCanBeApplied() {
        // R2/G24. ROUNDED appears zero times in all 28 programs, so COBOL truncates and RoundingMode.DOWN
        // is the only faithful choice for the monetary paths that have one. This DTO has none: TRNAMT is
        // the edited 12-character image, so no BigDecimal, BigInteger or RoundingMode appears anywhere on
        // its surface. That is the strongest available form of the negative - HALF_UP, HALF_EVEN, CEILING
        // and FLOOR are not merely unused here, they have nothing to act on.
        for (Class<?> type : typesUnderTest()) {
            for (Field member : type.getDeclaredFields()) {
                assertThat(member.getType().getName())
                        .as("%s.%s", type.getSimpleName(), member.getName())
                        .doesNotContain("BigDecimal")
                        .doesNotContain("RoundingMode")
                        .doesNotContain("MathContext");
            }
            for (Method method : type.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("%s.%s return type", type.getSimpleName(), method.getName())
                        .doesNotContain("BigDecimal")
                        .doesNotContain("RoundingMode");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(parameter.getName())
                            .as("%s.%s parameter", type.getSimpleName(), method.getName())
                            .doesNotContain("BigDecimal")
                            .doesNotContain("RoundingMode");
                }
            }
        }
    }

    @Test
    void carriesNoPersistenceAnnotationOrVersionColumn() {
        // G44. No DDL, no entity mapping and no optimistic-locking column. COTRN02C reaches TRANSACT
        // through VSAM, and the migration adds no schema of any kind - so a persistence annotation here
        // would be inventing a table that does not exist.
        Set<String> forbidden = Set.of("Entity", "Table", "Column", "Id", "Version", "GeneratedValue",
                "JoinColumn", "Embeddable", "MappedSuperclass");

        for (Class<?> type : typesUnderTest()) {
            assertAnnotationsAreAllowed(forbidden, type.getSimpleName(), type.getAnnotations());
            for (Field member : type.getDeclaredFields()) {
                assertAnnotationsAreAllowed(forbidden, type.getSimpleName() + "." + member.getName(),
                        member.getAnnotations());
            }
            for (Method method : type.getDeclaredMethods()) {
                assertAnnotationsAreAllowed(forbidden, type.getSimpleName() + "." + method.getName(),
                        method.getAnnotations());
            }
        }
    }

    private static void assertAnnotationsAreAllowed(Set<String> forbidden, String subject,
                                                    Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String simpleName = annotation.annotationType().getSimpleName();
            assertThat(forbidden)
                    .as("%s carries @%s", subject, simpleName)
                    .doesNotContain(simpleName);
            assertThat(annotation.annotationType().getName())
                    .as("%s carries a persistence annotation", subject)
                    .doesNotContain("persistence");
        }
    }

    @Test
    void exactlyFourteenFieldsAreInputCapableWhichIsWhatMakesThisAnAddScreen() {
        // The single strongest structural argument for R-B, asserted as a set rather than a count so a
        // substitution could not pass. 14 UNPROT + 7 ASKIP = 21 named fields.
        List<String> inputCapable = new ArrayList<>();
        List<String> outputOnly = new ArrayList<>();
        for (ScreenField field : ScreenField.values()) {
            if (field.unprotectedField()) {
                inputCapable.add(field.label());
            } else {
                outputOnly.add(field.label());
            }
        }

        assertThat(inputCapable).containsExactlyElementsOf(EXPECTED_INPUT_CAPABLE).hasSize(14);
        assertThat(outputOnly).containsExactlyElementsOf(EXPECTED_OUTPUT_ONLY).hasSize(7);
        assertThat(inputCapable.size() + outputOnly.size()).isEqualTo(NAMED_DFHMDF_ENTRIES);

        // A display screen would protect nearly everything; two thirds of this map is keyable.
        assertThat(inputCapable).hasSizeGreaterThan(outputOnly.size());
    }

    @Test
    void theInitialCursorFieldIsTheAccountKey() {
        // ACTIDIN at app/bms/COTRN02.bms:85 is ATTRB=(FSET,IC,NORM,UNPROT) - the only field declaring IC,
        // so it holds the initial cursor. It is also the first input-capable field in the group.
        assertThat(EXPECTED_INPUT_CAPABLE.get(0)).isEqualTo("ACTIDIN");
        assertThat(ScreenField.ACTIDIN.unprotectedField()).isTrue();

        ScreenField firstUnprotected = null;
        for (ScreenField field : ScreenField.values()) {
            if (field.unprotectedField()) {
                firstUnprotected = field;
                break;
            }
        }
        assertThat(firstUnprotected).isEqualTo(ScreenField.ACTIDIN);
    }

    @Test
    void theErrorLineIsSeventyEightCharactersAndOutputOnly() {
        // ERRMSG at app/bms/COTRN02.bms:293 is ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1).
        assertThat(TransactionViewRequest.ERRMSG_LENGTH).isEqualTo(78);
        assertThat(ScreenField.ERRMSG.length()).isEqualTo(78);
        assertThat(ScreenField.ERRMSG.unprotectedField()).isFalse();
        // It is the last field in the group, so it closes the 555 bytes.
        ScreenField[] fields = ScreenField.values();
        assertThat(fields[fields.length - 1]).isEqualTo(ScreenField.ERRMSG);
        assertThat(ScreenField.ERRMSG.payloadOffset() + ScreenField.ERRMSG.length())
                .isEqualTo(EXPECTED_GROUP_LENGTH);
    }

    /** The type and its nested types, which together are the whole surface under test. */
    private static List<Class<?>> typesUnderTest() {
        List<Class<?>> types = new ArrayList<>();
        types.add(TransactionViewRequest.class);
        types.add(Ct02Info.class);
        types.add(FieldMetadata.class);
        types.add(ScreenField.class);
        return types;
    }


    // =============================================================================================
    // Section 7 - identity, value semantics and defensive contracts.
    // =============================================================================================

    @Test
    void identityConstantsMatchTheCsdAndMapset() {
        // app/csd/CARDDEMO.CSD:439 DEFINE TRANSACTION(CT02) -> PROGRAM(COTRN02C); :271 DEFINE
        // PROGRAM(COTRN02C); :153 DEFINE MAPSET(COTRN02). The map inside the mapset is COTRN2A, so the
        // symbolic groups are COTRN2AI and COTRN2AO.
        assertThat(TransactionViewRequest.TRANSACTION_ID).isEqualTo("CT02");
        assertThat(TransactionViewRequest.PROGRAM_NAME).isEqualTo("COTRN02C");
        assertThat(TransactionViewRequest.MAPSET_NAME).isEqualTo("COTRN02");
        assertThat(TransactionViewRequest.MAP_NAME).isEqualTo("COTRN2A");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP).isEqualTo("COTRN2AI");
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_OUTPUT_GROUP).isEqualTo("COTRN2AO");
        // The output group is the input group's name with the trailing I replaced by O, exactly as BMS
        // generates it.
        assertThat(TransactionViewRequest.SYMBOLIC_MAP_OUTPUT_GROUP)
                .isEqualTo(TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP
                        .substring(0, TransactionViewRequest.SYMBOLIC_MAP_INPUT_GROUP.length() - 1)
                        + "O");
    }

    @ParameterizedTest
    @EnumSource(ScreenField.class)
    void enumKeyedAccessorsAgreeWithTheBeanAccessors(ScreenField field) {
        TransactionViewRequest request = new TransactionViewRequest();
        request.setPayloadValue(field, "probe");
        assertThat(request.payloadValue(field)).isEqualTo("probe");

        // Round-tripping through the enum-keyed pair reaches the same storage as the named pair, so
        // neither route can drift from the other.
        request.setPayloadValue(field, sampleOf(field));
        assertThat(request.payloadImages().get(field.inputItem())).isEqualTo(sampleOf(field));
    }

    @Test
    void enumKeyedAccessorsRejectANullField() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThatThrownBy(() -> request.payloadValue(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.setPayloadValue(null, "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.metadata(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.requestCursor(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request.isCursorRequested(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyBeanAccessorPairIsReachableAndIndependent() {
        // Drives all 21 setter/getter pairs by name, so a copy-paste slip that wired two members to the
        // same field would show up as a value appearing where it does not belong.
        TransactionViewRequest request = new TransactionViewRequest();
        request.setTrnname("CT02");
        request.setTitle01("T1");
        request.setCurdate("07/18/22");
        request.setPgmname("COTRN02C");
        request.setTitle02("T2");
        request.setCurtime("11:22:33");
        request.setActidin("00000000011");
        request.setCardnin("4111111111111111");
        request.setTtypcd("01");
        request.setTcatcd("0001");
        request.setTrnsrc("POS");
        request.setTdesc("GROCERY");
        request.setTrnamt("+00000123.45");
        request.setTorigdt("2022-07-18");
        request.setTprocdt("2022-07-19");
        request.setMid("000000042");
        request.setMname("ACME");
        request.setMcity("SEATTLE");
        request.setMzip("98101");
        request.setConfirm("Y");
        request.setErrmsg("");

        assertThat(request.getTrnname()).isEqualTo("CT02");
        assertThat(request.getTitle01()).isEqualTo("T1");
        assertThat(request.getCurdate()).isEqualTo("07/18/22");
        assertThat(request.getPgmname()).isEqualTo("COTRN02C");
        assertThat(request.getTitle02()).isEqualTo("T2");
        assertThat(request.getCurtime()).isEqualTo("11:22:33");
        assertThat(request.getActidin()).isEqualTo("00000000011");
        assertThat(request.getCardnin()).isEqualTo("4111111111111111");
        assertThat(request.getTtypcd()).isEqualTo("01");
        assertThat(request.getTcatcd()).isEqualTo("0001");
        assertThat(request.getTrnsrc()).isEqualTo("POS");
        assertThat(request.getTdesc()).isEqualTo("GROCERY");
        assertThat(request.getTrnamt()).isEqualTo("+00000123.45");
        assertThat(request.getTorigdt()).isEqualTo("2022-07-18");
        assertThat(request.getTprocdt()).isEqualTo("2022-07-19");
        assertThat(request.getMid()).isEqualTo("000000042");
        assertThat(request.getMname()).isEqualTo("ACME");
        assertThat(request.getMcity()).isEqualTo("SEATTLE");
        assertThat(request.getMzip()).isEqualTo("98101");
        assertThat(request.getConfirm()).isEqualTo("Y");
        assertThat(request.getErrmsg()).isEmpty();

        // CURDATE and CURTIME are opaque X(8) tokens: no clock is consulted and no format is imposed, so
        // the test stays deterministic (bind B7).
        assertThat(TransactionViewRequest.CURDATE_LENGTH).isEqualTo(8);
        assertThat(TransactionViewRequest.CURTIME_LENGTH).isEqualTo(8);
    }

    @Test
    void allArgsConstructorStoresEveryFieldExactlyAsSupplied() {
        NavigationContext carrier = NavigationContext.empty().withPgmReenter();
        Ct02Info cursor = new Ct02Info();
        cursor.setPageNum(2);

        TransactionViewRequest request = new TransactionViewRequest(
                "CT02", "T1", "07/18/22", "COTRN02C", "T2", "11:22:33",
                "00000000011", "4111111111111111", "01", "0001", "POS", "GROCERY",
                "+00000123.45", "2022-07-18", "2022-07-19", "000000042", "ACME", "SEATTLE",
                "98101", "Y", "", carrier, cursor);

        assertThat(request.getTrnname()).isEqualTo("CT02");
        assertThat(request.getConfirm()).isEqualTo("Y");
        assertThat(request.getTrnamt()).isEqualTo("+00000123.45");
        // The supplied state objects are kept, not copied and not replaced.
        assertThat(request.getNavigationContext()).isSameAs(carrier);
        assertThat(request.getCt02Info()).isSameAs(cursor);
        assertThat(request.isReenterContext()).isTrue();
        // Metadata is initialised for every field even on the all-args path.
        for (ScreenField field : ScreenField.values()) {
            assertThat(request.metadata(field)).isNotNull();
            assertThat(request.isCursorRequested(field)).isFalse();
        }
    }

    @Test
    void allArgsConstructorSubstitutesAFreshCursorWhenNoneIsSupplied() {
        // The two carried state objects are treated differently on purpose, and both arms matter. A null
        // commarea is a real state - EIBCALEN = 0, nothing was passed - so it is kept as null. A null CT02
        // group is not a state the program can observe, so a fresh one carrying its declared VALUE 'N' is
        // substituted, exactly as the no-arg constructor does.
        TransactionViewRequest request = new TransactionViewRequest(
                "CT02", "T1", "07/18/22", "COTRN02C", "T2", "11:22:33",
                "00000000011", "4111111111111111", "01", "0001", "POS", "GROCERY",
                "+00000123.45", "2022-07-18", "2022-07-19", "000000042", "ACME", "SEATTLE",
                "98101", "N", "", null, null);

        assertThat(request.getNavigationContext()).isNull();
        assertThat(request.hasNavigationContext()).isFalse();
        assertThat(request.commareaLength()).isZero();
        assertThat(request.isEnterContext()).isFalse();
        assertThat(request.isReenterContext()).isFalse();

        assertThat(request.getCt02Info()).isNotNull();
        assertThat(request.getCt02Info().getNextPageFlg()).isEqualTo(Ct02Info.NEXT_PAGE_DEFAULT);
        assertThat(request.getCt02Info().isNextPageNo()).isTrue();
        assertThat(request.getCt02Info().getPageNum()).isZero();

        // The payload still renders its full width, and the AID starts at no-key-resolved.
        assertThat(request.toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
        assertThat(request.getAid()).isEqualTo("     ");
    }

    @Test
    void copyConstructorSharesNoMutableState() {
        TransactionViewRequest original = populated();
        original.requestCursor(ScreenField.CONFIRM);
        original.getCt02Info().setPageNum(4);
        original.setNavigationContext(NavigationContext.empty().withPgmEnter());

        TransactionViewRequest copy = new TransactionViewRequest(original);
        assertThat(copy).isEqualTo(original);

        // Mutating the copy's carried cursor must not reach the original.
        copy.getCt02Info().setPageNum(9);
        assertThat(original.getCt02Info().getPageNum()).isEqualTo(4);
        assertThat(copy.getCt02Info()).isNotSameAs(original.getCt02Info());

        // Nor must mutating the copy's metadata.
        copy.resetMetadata();
        assertThat(original.isCursorRequested(ScreenField.CONFIRM)).isTrue();
        assertThat(copy.isCursorRequested(ScreenField.CONFIRM)).isFalse();

        // The FieldMetadata copy constructor is likewise a real copy.
        FieldMetadata source = new FieldMetadata(12, '*');
        FieldMetadata clone = new FieldMetadata(source);
        assertThat(clone).isEqualTo(source);
        clone.reset();
        assertThat(source.length()).isEqualTo(12);
        assertThat(source.attribute()).isEqualTo('*');

        // And so is the Ct02Info one.
        Ct02Info cursorSource = new Ct02Info();
        cursorSource.setPageNum(6);
        Ct02Info cursorClone = new Ct02Info(cursorSource);
        assertThat(cursorClone).isEqualTo(cursorSource);
        cursorClone.setPageNum(7);
        assertThat(cursorSource.getPageNum()).isEqualTo(6);
    }

    @Test
    void copyConstructorsRejectNull() {
        assertThatThrownBy(() -> new TransactionViewRequest((TransactionViewRequest) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldMetadata((FieldMetadata) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Ct02Info((Ct02Info) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fixedWidthEntryPointsRejectNullArguments() {
        assertThatThrownBy(() -> TransactionViewRequest.fromFixedWidth(null, ASCII))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionViewRequest.readFrom(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> populated().writeInto(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Ct02Info.fromFixedWidth(null, ASCII))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityAccountsForMetadataBecauseACursorRequestIsPartOfTheRequest() {
        TransactionViewRequest left = populated();
        TransactionViewRequest right = populated();
        assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);

        // Two requests with identical payloads but different cursor positions are different requests:
        // COTRN02C sends the cursor to the offending field, and which field that is is observable.
        right.requestCursor(ScreenField.TRNAMT);
        assertThat(left).isNotEqualTo(right);

        right.resetMetadata();
        assertThat(left).isEqualTo(right);

        // A differing payload field also breaks equality.
        right.setConfirm("Y");
        assertThat(left).isNotEqualTo(right);
    }

    @Test
    void requestEqualsDistinguishesBothCarriedStateObjects() {
        TransactionViewRequest base = populated();

        TransactionViewRequest differentCarrier = new TransactionViewRequest(base);
        differentCarrier.setNavigationContext(NavigationContext.empty().withPgmReenter());
        assertThat(base).isNotEqualTo(differentCarrier);

        TransactionViewRequest differentCursor = new TransactionViewRequest(base);
        differentCursor.getCt02Info().setPageNum(11);
        assertThat(base).isNotEqualTo(differentCursor);

        TransactionViewRequest differentAid = new TransactionViewRequest(base);
        differentAid.setAid("PF03 ");
        assertThat(base).isNotEqualTo(differentAid);
    }

    @Test
    void requestEqualsCoversEveryShortCircuit() {
        TransactionViewRequest request = populated();
        assertThat(request).isEqualTo(request);          // identity
        assertThat(request).isNotEqualTo(null);          // null
        assertThat(request).isNotEqualTo("COTRN2AI");    // wrong type
        assertThat(request).isEqualTo(populated());      // full comparison
    }

    @Test
    void fieldMetadataEqualsCoversEveryShortCircuit() {
        FieldMetadata metadata = new FieldMetadata(5, 'A');
        assertThat(metadata).isEqualTo(metadata);
        assertThat(metadata).isNotEqualTo(null);
        assertThat(metadata).isNotEqualTo("5");
        assertThat(metadata).isEqualTo(new FieldMetadata(5, 'A'))
                .hasSameHashCodeAs(new FieldMetadata(5, 'A'));
        assertThat(metadata).isNotEqualTo(new FieldMetadata(6, 'A'));   // length differs
        assertThat(metadata).isNotEqualTo(new FieldMetadata(5, 'B'));   // attribute differs
        assertThat(metadata.toString()).contains("length=5").contains("attribute=0x");
    }

    @Test
    void cursorEqualsCoversEveryShortCircuit() {
        Ct02Info cursor = new Ct02Info("F", "L", 1, "Y", "S", "T");
        assertThat(cursor).isEqualTo(cursor);
        assertThat(cursor).isNotEqualTo(null);
        assertThat(cursor).isNotEqualTo("CDEMO-CT02-INFO");
        assertThat(cursor).isEqualTo(new Ct02Info("F", "L", 1, "Y", "S", "T"))
                .hasSameHashCodeAs(new Ct02Info("F", "L", 1, "Y", "S", "T"));

        assertThat(cursor).isNotEqualTo(new Ct02Info("X", "L", 1, "Y", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "X", 1, "Y", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 2, "Y", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 1, "N", "S", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 1, "Y", "X", "T"));
        assertThat(cursor).isNotEqualTo(new Ct02Info("F", "L", 1, "Y", "S", "X"));

        assertThat(cursor.toString()).contains("CDEMO-CT02");
    }

    @Test
    void everyCursorAccessorPairIsReachable() {
        Ct02Info cursor = new Ct02Info();
        cursor.setTrnidFirst("0000000000000001");
        cursor.setTrnidLast("0000000000000099");
        cursor.setPageNum(3);
        cursor.setNextPageFlg("Y");
        cursor.setTrnSelFlg("S");
        cursor.setTrnSelected("0000000000000007");

        assertThat(cursor.getTrnidFirst()).isEqualTo("0000000000000001");
        assertThat(cursor.getTrnidLast()).isEqualTo("0000000000000099");
        assertThat(cursor.getPageNum()).isEqualTo(3);
        assertThat(cursor.getNextPageFlg()).isEqualTo("Y");
        assertThat(cursor.getTrnSelFlg()).isEqualTo("S");
        assertThat(cursor.getTrnSelected()).isEqualTo("0000000000000007");
    }

    @Test
    void theAidTokenIsFiveCharactersAndDefaultsToNoKeyResolved() {
        // EIBAID travels in the payload as a PIC X(5) token rather than being read from a CICS control
        // block, which is what makes the projection stateless (R6/G37).
        assertThat(TransactionViewRequest.AID_LENGTH).isEqualTo(5);
        assertThat(TransactionViewRequest.AID_FIELD).isEqualTo("EIBAID");

        TransactionViewRequest request = new TransactionViewRequest();
        assertThat(request.getAid())
                .hasSize(TransactionViewRequest.AID_LENGTH)
                .isEqualTo("     ");

        // A resolved token is already padded to the declared width, so it is stored verbatim.
        for (String token : new String[] {"ENTER", "PF03 ", "PF04 ", "CLEAR", "PA1  "}) {
            assertThat(token).hasSize(TransactionViewRequest.AID_LENGTH);
            request.setAid(token);
            assertThat(request.getAid()).isEqualTo(token);
        }

        // Null means "no key resolved", which is spaces rather than a null on the wire.
        request.setAid(null);
        assertThat(request.getAid()).isEqualTo("     ");
    }

    @Test
    void anOverLongAidTokenIsRefusedByNameWithoutEchoingIt() {
        TransactionViewRequest request = new TransactionViewRequest();
        assertThatThrownBy(() -> request.setAid("PF13TOOLONG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(TransactionViewRequest.AID_FIELD)
                .hasMessageNotContaining("PF13TOOLONG");
    }

    @Test
    void theAidIsNotAScreenFieldAndDoesNotWidenTheProjection() {
        // EIBAID is not a DFHMDF field, so it is carried outside the 21-field projection and adds nothing
        // to the 555-byte group image.
        for (ScreenField field : ScreenField.values()) {
            assertThat(field.label()).isNotEqualTo(TransactionViewRequest.AID_FIELD);
        }
        assertThat(TransactionViewRequest.AI_LAYOUT.hasSpan("EIBAID")).isFalse();

        TransactionViewRequest request = populated();
        request.setAid("ENTER");
        assertThat(request.toFixedWidth(ASCII)).hasSize(EXPECTED_GROUP_LENGTH);
        assertThat(request.payloadImages()).hasSize(NAMED_DFHMDF_ENTRIES);
    }

    // ---------------------------------------------------------------------------------------------
    // Bean Validation. The declared widths become @Size ceilings; nothing pre-empts the COBOL's own
    // editing, because doing so would change which message the operator sees.
    // ---------------------------------------------------------------------------------------------

    @Test
    void widthConstraintsAcceptTheDeclaredWidthAndRejectOnlyOverflow() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            // Exactly the declared width is valid for every field.
            TransactionViewRequest atWidth = populated();
            assertThat(validator.validate(atWidth)).isEmpty();

            // One character over on a single field is the only violation.
            TransactionViewRequest tooWide = populated();
            tooWide.setTrnamt("+000000123.45");
            Set<ConstraintViolation<TransactionViewRequest>> violations = validator.validate(tooWide);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("trnamt");
        }
    }

    @Test
    void nullFieldsAreAcceptedBecauseAnAbsentScreenFieldIsLegal() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            TransactionViewRequest sparse = new TransactionViewRequest();
            for (ScreenField field : ScreenField.values()) {
                sparse.setPayloadValue(field, null);
            }
            // CICS simply does not transmit an unmodified field, so null is a legal state and @Size must
            // not turn it into a violation. @NotNull anywhere here would reject a valid screen.
            assertThat(validator.validate(sparse)).isEmpty();
        }
    }

    @Test
    void noNumericOrDateConstraintPreEmptsTheCobolsOwnEditing() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            TransactionViewRequest malformed = new TransactionViewRequest();
            // Every one of these fails the COBOL's positional masks, and every one must still pass Bean
            // Validation - otherwise the framework would reject the request before COTRN02C could produce
            // 'Amount should be in format -99999999.99' and put the cursor on the field.
            malformed.setTrnamt("NOT-A-NUM   ");
            malformed.setTorigdt("2022/07/18");
            malformed.setTprocdt("XX-XX-XXXX");
            malformed.setActidin("ABCDEFGHIJK");
            malformed.setCardnin("NOT-A-CARD-NUM  ");
            malformed.setConfirm("?");

            assertThat(validator.validate(malformed)).isEmpty();
            assertThat(amountMaskRejects(malformed.getTrnamt())).isTrue();
            assertThat(dateMaskRejects(malformed.getTorigdt())).isTrue();
            assertThat(confirmOutcome(malformed.getConfirm())).isEqualTo(ConfirmOutcome.INVALID);
        }
    }

    @Test
    void nestedCursorConstraintsCascade() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            TransactionViewRequest request = populated();
            Ct02Info cursor = new Ct02Info();
            cursor.setTrnidFirst("0".repeat(Ct02Info.TRNID_FIRST_LENGTH + 1));
            request.setCt02Info(cursor);

            // @Valid on the nested group means its own @Size ceilings are enforced through the parent.
            Set<ConstraintViolation<TransactionViewRequest>> violations = validator.validate(request);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo("ct02Info.trnidFirst");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"US-ASCII", "IBM037"})
    void theCharsetIsAlwaysNamedNeverInheritedFromThePlatform(String charsetName) {
        // Bind B7/B8: the encoding is an argument, never a default. The same request renders 555 bytes in
        // either encoding and round-trips through the one it was written with - and the two images differ,
        // which is exactly why relying on the platform default would be a defect.
        Charset charset = Charset.forName(charsetName);
        TransactionViewRequest request = populated();

        byte[] image = request.toFixedWidth(charset);
        assertThat(image).hasSize(EXPECTED_GROUP_LENGTH);

        TransactionViewRequest restored = TransactionViewRequest.fromFixedWidth(image, charset);
        for (ScreenField field : ScreenField.values()) {
            assertThat(restored.payloadValue(field)).isEqualTo(sampleOf(field));
        }

        if ("IBM037".equals(charsetName)) {
            // EBCDIC and ASCII are genuinely different bytes for the same characters.
            assertThat(image).isNotEqualTo(request.toFixedWidth(StandardCharsets.US_ASCII));
        }
    }

    @Test
    void theRenderedImageIsPureAsciiSoNoTranscodingSurpriseIsHidden() {
        byte[] image = populated().toFixedWidth(ASCII);
        for (byte rendered : image) {
            assertThat(rendered).as("every rendered byte is 7-bit").isBetween((byte) 0, (byte) 127);
        }
    }
}
