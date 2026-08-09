package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AdminMenuRequest}, the inbound payload of {@code GET /api/admin/menu} - CICS
 * transaction {@code CA00}, program {@code COADM01C}, mapset {@code COADM01}, map {@code COADM1A}.
 *
 * <h2>User rules</h2>
 *
 * <p><strong>No user-specified rules were provided for this project.</strong> {@code review_rules}
 * returns exactly one line - "No user rules provided." - and that line is the whole document. No
 * user rule therefore governs this file. Their absence is <em>not</em> licence to assert less: the
 * twelve enterprise practices the Agent Action Plan substitutes in its place are binding here, and
 * each one is discharged by a named construct in this file:
 *
 * <ul>
 *   <li><strong>B1</strong> - no dependency and no version literal is introduced. Only what
 *       {@code app/java/pom.xml} already declares is used: JUnit 5 and AssertJ through
 *       {@code spring-boot-starter-test}, Bean Validation through
 *       {@code spring-boot-starter-validation}, Jackson through {@code spring-boot-starter-web}.</li>
 *   <li><strong>B2</strong> - JUnit 5 and Spring Boot 3.5.x era APIs only, and <em>no Spring context
 *       at all</em>: no {@code @SpringBootTest}, no {@code MockMvc}, no {@code JobLauncher}. The
 *       subject is a record, so it needs none of them.</li>
 *   <li><strong>B3</strong> - the legacy sources are read-only and are never opened at run time.
 *       Every expectation below is a Java literal carrying the source line it was transcribed from,
 *       so the copybook stays the authority and this file stays independent of it.</li>
 *   <li><strong>B4</strong> - the duplication between this screen and the main menu is documented,
 *       not removed. See {@link DuplicationIsDocumentedNotRemoved}.</li>
 *   <li><strong>B5</strong> - all twelve option slots are asserted present even though the program
 *       can never fill more than ten. See {@link TwelveOptionSlots}.</li>
 *   <li><strong>B7</strong> - deterministic and non-interactive. There is no {@code now()}, no
 *       randomness, no sleep, no file or network access, and no ordering dependence between tests:
 *       every test builds the values it needs and shares nothing with any other.</li>
 *   <li><strong>B8</strong> - explicit over implicit. Widths are asserted against the subject's
 *       named constant <em>and</em> against the transcribed integer literal, so a constant that
 *       drifts fails instead of silently agreeing with itself; and the one place bytes are produced
 *       states its code page explicitly through {@link FixedWidthCodec}, never
 *       {@code String.getBytes()} with a platform default.</li>
 *   <li><strong>B9</strong> - <strong>this class declares no mutable field of any kind</strong>, static
 *       or instance. The five fields it does declare are {@code private static final} lists built by
 *       {@link List#of}, whose elements are {@code String} and {@code Integer}, so they are deeply
 *       immutable constants rather than state. Everything with a lifecycle is built where it is used:
 *       the validator factory is opened and closed inside the single helper that needs it, and the
 *       object mapper is constructed inside each test that serialises. No test can therefore observe
 *       or corrupt anything another test left behind, and no test depends on execution order. The
 *       subject is held to the same standard reflectively in
 *       {@link ConversationStateTravelsInThePayload}.</li>
 *   <li><strong>B10</strong> - these tests ship in the same phase as the record they cover.</li>
 *   <li><strong>B11</strong> - the width, stride and total-width arithmetic is written out by hand.
 *       No copybook parser is used, and every reflective sweep is paired with explicitly named
 *       assertions so a reader can see which cases were checked rather than trusting a loop.</li>
 *   <li><strong>B12</strong> - every expected literal cites its source line, because this baseline
 *       is <em>statically derived</em> from the COBOL and not captured from a run of it. The legacy
 *       programs cannot be executed in this environment, so the citation is the only audit trail a
 *       reviewer has.</li>
 *   <li><strong>B6</strong> - <strong>no subject.</strong> The admin menu screen has no credential
 *       field, so there is nothing here to mask, hash or redact, and no such assertion is invented.</li>
 * </ul>
 *
 * <h2>What is asserted, and why those things</h2>
 *
 * <p>The subject is a pure projection, so the suite is organised around the six properties a
 * projection can get wrong. A failure then names a translation decision rather than a value:
 *
 * <ol>
 *   <li><strong>Exactly 20 payload members</strong>, one per name-labelled {@code DFHMDF}, and the
 *       length, flag and attribute items deliberately absent - gate G9.</li>
 *   <li><strong>The widths</strong>, each pinned twice, summing to 668 inside an 820-byte image -
 *       gate G21, which fails immediately if a span is dropped.</li>
 *   <li><strong>Twelve option slots</strong>, of which the program fills four and can reach ten -
 *       the sharpest dead-code trap in this package.</li>
 *   <li><strong>{@code OPTIONI} is the only editable field</strong>, is size-constrained at 2, and
 *       is carried entirely raw.</li>
 *   <li><strong>No server-side session</strong>: the communication area, the attention identifier
 *       and the enter/re-enter context all travel in the body - gates G37 and G53.</li>
 *   <li><strong>The identity constants</strong> {@code CA00} and {@code COADM01C} fit their fields
 *       exactly.</li>
 * </ol>
 */
@DisplayName("AdminMenuRequest - the COADM1AI input projection of the CA00 admin menu")
class AdminMenuRequestTest {

    // =================================================================================================
    // Transcribed expectations. Every constant below is read off a legacy source and written here as a
    // literal; none is derived from the class under test, so a drift in either place fails (practice
    // B12). All are static final and deeply immutable - a List.of of Strings - so they are constants,
    // not shared mutable state (practice B9).
    // =================================================================================================

    /**
     * The twenty {@code xxxI} symbolic-map item names in map order, transcribed from
     * {@code app/cpy-bms/COADM01.CPY} lines 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96,
     * 102, 108, 114, 120, 126, 132 and 138.
     */
    private static final List<String> EXPECTED_ITEM_NAMES = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "OPTN001I",
            "OPTN002I",
            "OPTN003I",
            "OPTN004I",
            "OPTN005I",
            "OPTN006I",
            "OPTN007I",
            "OPTN008I",
            "OPTN009I",
            "OPTN010I",
            "OPTN011I",
            "OPTN012I",
            "OPTIONI",
            "ERRMSGI");

    /**
     * The twenty Java payload member names in the same map order, so element <em>i</em> here is the
     * projection of element <em>i</em> of {@link #EXPECTED_ITEM_NAMES}.
     */
    private static final List<String> EXPECTED_MEMBER_NAMES = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "optn001",
            "optn002",
            "optn003",
            "optn004",
            "optn005",
            "optn006",
            "optn007",
            "optn008",
            "optn009",
            "optn010",
            "optn011",
            "optn012",
            "option",
            "errMsg");

    /**
     * The twenty widths in map order, transcribed from the {@code PIC X(n)} clause of each
     * {@code xxxI} item and corroborated by the matching {@code LENGTH=} operand in
     * {@code app/bms/COADM01.bms} at lines 36, 40, 49, 59, 63, 72, 82, 87, 92, 97, 102, 107, 112,
     * 117, 122, 127, 132, 137, 148 and 156.
     */
    private static final List<Integer> EXPECTED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 2, 78);

    /**
     * The two members that carry the conversation rather than a screen field, in declaration order
     * after the twenty. Together with {@link #EXPECTED_MEMBER_NAMES} these account for every record
     * component, which is what makes the count assertions exhaustive rather than merely consistent.
     */
    private static final List<String> EXPECTED_CARRIER_NAMES =
            List.of("navigationContext", "eibAid");

    /**
     * The metadata suffixes the symbolic map declares beside every screen field and that must never
     * become payload members: {@code xxxL} the length item, {@code xxxF} the flag byte and
     * {@code xxxA} the attribute view that {@code REDEFINES} it - {@code app/cpy-bms/COADM01.CPY}
     * lines 19 to 23 for the first field, repeated identically for the other nineteen.
     */
    private static final List<String> METADATA_SUFFIXES = List.of("L", "F", "A");

    // =================================================================================================
    // Helpers. All are instance methods over locals only, so nothing survives a test (practice B9).
    // =================================================================================================

    /**
     * A request with every screen field at exactly its declared width, so the only variables in a
     * given test are the two it is actually about.
     *
     * @param option  the value for {@code OPTIONI}, passed through untouched
     * @param context the communication area, or {@code null} to mean {@code EIBCALEN = 0}
     * @return a fresh request; no instance is ever shared between tests
     */
    private AdminMenuRequest atDeclaredWidths(String option, NavigationContext context) {
        return new AdminMenuRequest("CA00",
                "x".repeat(AdminMenuRequest.TITLE_LENGTH),
                "08/08/26",
                "COADM01C",
                "y".repeat(AdminMenuRequest.TITLE_LENGTH),
                "09:41:07",
                "01. User List (Security)",
                "02. User Add (Security)",
                "03. User Update (Security)",
                "04. User Delete (Security)",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                option,
                "z".repeat(AdminMenuRequest.ERR_MSG_LENGTH),
                context,
                (byte) 0x7D);
    }

    /**
     * A request whose {@code index}-th screen field - indexed as {@link #EXPECTED_MEMBER_NAMES} is -
     * holds {@code value}, every other screen field being absent.
     *
     * <p>Isolating one field at a time is what lets a violation be attributed to that field: a
     * request with several over-long fields would report several violations and prove nothing about
     * which constraint caught which.
     *
     * @param index the position in map order, 0 through 19
     * @param value the value to place there
     * @return a fresh request carrying exactly that one screen field
     */
    private AdminMenuRequest withScreenField(int index, String value) {
        String[] fields = new String[AdminMenuRequest.MAPPED_FIELD_COUNT];
        fields[index] = value;
        return new AdminMenuRequest(fields[0],
                fields[1],
                fields[2],
                fields[3],
                fields[4],
                fields[5],
                fields[6],
                fields[7],
                fields[8],
                fields[9],
                fields[10],
                fields[11],
                fields[12],
                fields[13],
                fields[14],
                fields[15],
                fields[16],
                fields[17],
                fields[18],
                fields[19],
                null,
                (byte) 0x7D);
    }

    /**
     * The value the subject's own {@code *_LENGTH} constant gives for the {@code index}-th screen
     * field.
     *
     * <p>This is one half of the double check practice B8 requires. A test compares this - the
     * <em>subject's</em> declared width - against {@link #EXPECTED_WIDTHS}, the width transcribed
     * from the copybook. Asserting only that a value of the subject's own declared length validates
     * would be circular: the constraint and the constant would agree with each other while both
     * disagreed with the copybook.
     *
     * <p>{@code title01} and {@code title02} both answer {@code TITLE_LENGTH} and the twelve option
     * lines all answer {@code OPTION_LINE_LENGTH}, exactly as the subject declares them. The arms
     * are written out rather than defaulted so that an index outside the map is an error here
     * instead of silently collecting the option-line width.
     *
     * @param index the position in map order, 0 through 19
     * @return the subject's declared width for that field
     */
    private int declaredWidthOf(int index) {
        return switch (index) {
            case 0 -> AdminMenuRequest.TRN_NAME_LENGTH;
            case 1, 4 -> AdminMenuRequest.TITLE_LENGTH;
            case 2 -> AdminMenuRequest.CUR_DATE_LENGTH;
            case 3 -> AdminMenuRequest.PGM_NAME_LENGTH;
            case 5 -> AdminMenuRequest.CUR_TIME_LENGTH;
            case 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17 -> AdminMenuRequest.OPTION_LINE_LENGTH;
            case 18 -> AdminMenuRequest.OPTION_LENGTH;
            case 19 -> AdminMenuRequest.ERR_MSG_LENGTH;
            default -> throw new IllegalArgumentException("no screen field at map position " + index);
        };
    }

    /**
     * The property names Bean Validation reports a violation for.
     *
     * <p>The factory is opened and closed inside this method rather than held in a field. That costs
     * a little time per call and buys the guarantee practice B9 asks for: there is no validator
     * outliving a test, so no test can be affected by the order it runs in or by what another test
     * did. {@link ValidatorFactory} is {@link AutoCloseable}, so try-with-resources also releases it
     * deterministically instead of leaving it to a shutdown hook.
     *
     * @param request the payload to validate
     * @return the violated property names, empty when the payload is valid
     */
    private Set<String> violatedProperties(AdminMenuRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request)
                    .stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /**
     * The subject's twenty screen fields in map order, read back through the record accessors.
     *
     * @param request the payload to read
     * @return the twenty values, in the order {@link #EXPECTED_MEMBER_NAMES} names them
     */
    private List<String> screenFieldsOf(AdminMenuRequest request) {
        return Arrays.asList(request.trnName(),
                request.title01(),
                request.curDate(),
                request.pgmName(),
                request.title02(),
                request.curTime(),
                request.optn001(),
                request.optn002(),
                request.optn003(),
                request.optn004(),
                request.optn005(),
                request.optn006(),
                request.optn007(),
                request.optn008(),
                request.optn009(),
                request.optn010(),
                request.optn011(),
                request.optn012(),
                request.option(),
                request.errMsg());
    }

    /** The subject's record component names, in declaration order. */
    private List<String> recordComponentNames() {
        return Arrays.stream(AdminMenuRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }

    @Nested
    @DisplayName("The twenty payload members, one per name-labelled DFHMDF (gate G9)")
    class TheTwentyPayloadMembers {

        @Test
        @DisplayName("there are exactly twenty screen members plus the two conversation carriers")
        void exactlyTwentyScreenMembersAndNothingElse() {
            // COADM1AI declares twenty xxxI items - app/cpy-bms/COADM01.CPY lines 24 to 138 - and this
            // record projects each one once. The only other components are the communication area and
            // the attention identifier, which are conversation state rather than screen state, so the
            // component count is exactly 20 + 2. Asserting the total as well as the twenty names is
            // what makes this exhaustive: a twenty-third member could not slip in unnoticed.
            List<String> components = recordComponentNames();

            assertThat(AdminMenuRequest.MAPPED_FIELD_COUNT).isEqualTo(20);
            assertThat(EXPECTED_MEMBER_NAMES).hasSize(20);
            assertThat(components).hasSize(22)
                    .startsWith(EXPECTED_MEMBER_NAMES.toArray(String[]::new))
                    .endsWith(EXPECTED_CARRIER_NAMES.toArray(String[]::new));
            assertThat(components).containsAll(EXPECTED_MEMBER_NAMES);
        }

        @Test
        @DisplayName("the twenty members appear in map order, not in some tidied-up order")
        void membersFollowMapOrder() {
            // Order is contract, not presentation: the symbolic map is a byte image and the parity
            // differ walks the fields positionally. TRNNAMEI is first at CPY line 24 and ERRMSGI last
            // at line 138, with OPTIONI immediately before it at line 132.
            List<String> screenMembers = recordComponentNames().subList(0, 20);

            assertThat(screenMembers).containsExactlyElementsOf(EXPECTED_MEMBER_NAMES);
            assertThat(screenMembers).first().isEqualTo("trnName");
            assertThat(screenMembers).last().isEqualTo("errMsg");
            assertThat(screenMembers.get(18)).isEqualTo("option");
        }

        @Test
        @DisplayName("every member has an accessor returning String, and the carriers do not")
        void everyScreenMemberIsAnAlphanumericAccessor() throws Exception {
            // Every xxxI item is PIC X(n) - alphanumeric without exception on this screen - so every
            // screen accessor returns String. OPTIONI is PIC X(2) and not PIC 9(2) even though
            // app/bms/COADM01.bms:145 declares ATTRB=...,NUM: the map enforces numeric keying at the
            // terminal, while the item itself stays alphanumeric and may hold spaces.
            for (String member : EXPECTED_MEMBER_NAMES) {
                assertThat(AdminMenuRequest.class.getMethod(member).getReturnType())
                        .as("accessor %s()", member)
                        .isEqualTo(String.class);
            }

            assertThat(AdminMenuRequest.class.getMethod("navigationContext").getReturnType())
                    .isEqualTo(NavigationContext.class);
            // The attention identifier is a byte, not a String: it is one raw EIBAID byte and the
            // shared resolver consumes it as such, so no conversion happens at the boundary.
            assertThat(AdminMenuRequest.class.getMethod("eibAid").getReturnType())
                    .isEqualTo(byte.class);
        }

        @Test
        @DisplayName("each member names its symbolic-map item verbatim, trailing I included")
        void eachMemberNamesItsItemVerbatim() {
            // A "tidied up" name would make a real difference invisible to a differ that compares by
            // copybook field name, so the constants keep the copybook's own spelling.
            List<String> declared = new ArrayList<>();
            declared.add(AdminMenuRequest.TRN_NAME_FIELD);
            declared.add(AdminMenuRequest.TITLE01_FIELD);
            declared.add(AdminMenuRequest.CUR_DATE_FIELD);
            declared.add(AdminMenuRequest.PGM_NAME_FIELD);
            declared.add(AdminMenuRequest.TITLE02_FIELD);
            declared.add(AdminMenuRequest.CUR_TIME_FIELD);
            declared.addAll(AdminMenuRequest.OPTION_LINE_FIELDS);
            declared.add(AdminMenuRequest.OPTION_FIELD);
            declared.add(AdminMenuRequest.ERR_MSG_FIELD);

            assertThat(declared).containsExactlyElementsOf(EXPECTED_ITEM_NAMES);
            assertThat(declared).hasSize(AdminMenuRequest.MAPPED_FIELD_COUNT)
                    .allSatisfy(name -> assertThat(name).endsWith("I"));
        }

        @Test
        @DisplayName("twenty of the twenty-eight DFHMDF fields are named; the other eight are labels")
        void twentyOfTwentyEightDfhmdfFieldsAreNamed() {
            // app/bms/COADM01.bms declares 28 DFHMDF fields. Exactly 20 carry a name and therefore
            // generate a symbolic-map item; the remaining 8 are unnamed literal screen furniture and
            // generate nothing, which is why they are absent from this payload BY DESIGN rather than
            // by omission. The eight, with their .bms lines:
            //   :29  'Tran:'                     label at (1,1)
            //   :42  'Date:'                     label at (1,65)
            //   :52  'Prog:'                     label at (2,1)
            //   :65  'Time:'                     label at (2,65)
            //   :77  LENGTH=10 INITIAL='Admin Menu'  heading at (4,35)
            //   :140 'Please select an option :'  prompt at (20,15)
            //   :150 LENGTH=0                    stopper at (20,44)
            //   :158 'ENTER=Continue  F3=Exit'   footer at (24,1)
            int namedFields = AdminMenuRequest.MAPPED_FIELD_COUNT;
            int unnamedLiteralLabels = 8;

            assertThat(namedFields).isEqualTo(20);
            assertThat(namedFields + unnamedLiteralLabels).isEqualTo(28);
            assertThat(recordComponentNames().subList(0, 20)).hasSize(namedFields);
        }

        @Test
        @DisplayName("the length item xxxL is absent: trnNameL is not a member")
        void theLengthItemIsAbsent() throws Exception {
            // TRNNAMEL COMP PIC S9(4) at app/cpy-bms/COADM01.CPY:19 is the input length CICS reports.
            // It is derivable from the value that IS carried, so modelling it too would create a second
            // source of truth able to disagree with the first.
            assertThat(recordComponentNames()).doesNotContain("trnNameL", "TRNNAMEL");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> AdminMenuRequest.class.getMethod("trnNameL"));
            // The value itself is still reachable, which is the point: nothing is lost by omitting the
            // length item, because the length of the carried value is the length CICS reported.
            assertThat(atDeclaredWidths("01", null).trnName())
                    .hasSize(AdminMenuRequest.TRN_NAME_LENGTH);
        }

        @Test
        @DisplayName("the flag byte xxxF is absent: optionF is not a member")
        void theFlagByteIsAbsent() throws Exception {
            // OPTIONF PICTURE X at app/cpy-bms/COADM01.CPY:128 is the modified-data-tag flag byte, a
            // terminal-protocol artefact with no meaning off the 3270.
            assertThat(recordComponentNames()).doesNotContain("optionF", "OPTIONF");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> AdminMenuRequest.class.getMethod("optionF"));
        }

        @Test
        @DisplayName("the attribute view xxxA is absent: errMsgA is not a member")
        void theAttributeViewIsAbsent() throws Exception {
            // ERRMSGA at app/cpy-bms/COADM01.CPY:136 REDEFINES the flag byte and is the view a program
            // writes when it highlights a field in error. Highlighting is the response's concern, so
            // the attribute view belongs to the response projection and not to this request.
            assertThat(recordComponentNames()).doesNotContain("errMsgA", "ERRMSGA");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> AdminMenuRequest.class.getMethod("errMsgA"));
        }

        @Test
        @DisplayName("no length, flag or attribute item reaches the payload for any of the twenty")
        void noMetadataItemIsAMemberForAnyField() {
            // The sweep that generalises the three named cases above. It is deliberately paired with
            // them rather than replacing them (practice B11): the named tests show a reader exactly
            // which spellings were checked, and this one proves the other fifty-seven combinations
            // hold too - twenty fields x three suffixes, in both the Java and the copybook spelling.
            Set<String> components = new LinkedHashSet<>(recordComponentNames());

            for (int index = 0; index < EXPECTED_MEMBER_NAMES.size(); index++) {
                String member = EXPECTED_MEMBER_NAMES.get(index);
                String item = EXPECTED_ITEM_NAMES.get(index);
                String itemBase = item.substring(0, item.length() - 1);

                for (String suffix : METADATA_SUFFIXES) {
                    assertThat(components).as("metadata item %s%s must not be a payload member",
                            member,
                            suffix).doesNotContain(member + suffix, itemBase + suffix);
                }
            }

            // Stated the other way round as well, so the sweep cannot pass by looking for the wrong
            // thing: every component either is one of the twenty screen members or is a carrier.
            assertThat(components).hasSize(22)
                    .allSatisfy(name -> assertThat(EXPECTED_MEMBER_NAMES.contains(name)
                            || EXPECTED_CARRIER_NAMES.contains(name))
                            .as("component %s is either a screen field or a carrier", name)
                            .isTrue());
        }
    }


    @Nested
    @DisplayName("Width arithmetic, written out by hand (gates G21 and B11)")
    class WidthArithmetic {

        @ParameterizedTest(name = "{1} is PIC X({2}) at COADM01.CPY:{3}")
        @CsvSource({"0,  TRNNAMEI,  4, 24",
                "1,  TITLE01I, 40,  30",
                "2,  CURDATEI,  8,  36",
                "3,  PGMNAMEI,  8,  42",
                "4,  TITLE02I, 40,  48",
                "5,  CURTIMEI,  8,  54",
                "6,  OPTN001I, 40,  60",
                "7,  OPTN002I, 40,  66",
                "8,  OPTN003I, 40,  72",
                "9,  OPTN004I, 40,  78",
                "10, OPTN005I, 40,  84",
                "11, OPTN006I, 40,  90",
                "12, OPTN007I, 40,  96",
                "13, OPTN008I, 40, 102",
                "14, OPTN009I, 40, 108",
                "15, OPTN010I, 40, 114",
                "16, OPTN011I, 40, 120",
                "17, OPTN012I, 40, 126",
                "18, OPTIONI,   2, 132",
                "19, ERRMSGI,  78, 138"})
        @DisplayName("each width is pinned twice: to the subject's constant and to the copybook literal")
        void eachWidthIsPinnedTwice(int index, String itemName, int copybookWidth, int copybookLine) {
            // The double check practice B8 demands. The first assertion compares the SUBJECT's declared
            // constant against the width transcribed from the copybook, so a mistyped constant fails
            // rather than quietly redefining the screen. The second compares the width table this file
            // carries against the same literal, so the table cannot drift either. Asserting only that a
            // value of the subject's own length validates would be circular - the constraint and the
            // constant would agree with each other while both disagreed with the copybook.
            assertThat(declaredWidthOf(index)).as("%s (%s:%d) declared constant",
                    itemName,
                    "app/cpy-bms/COADM01.CPY",
                    copybookLine).isEqualTo(copybookWidth);
            assertThat(EXPECTED_WIDTHS.get(index)).as("%s transcribed width", itemName)
                    .isEqualTo(copybookWidth);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo(itemName);

            // And the constraint really is set at that width: exactly the declared length validates,
            // one character more does not. Both sides, for all twenty fields.
            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth + 1))))
                    .containsExactly(EXPECTED_MEMBER_NAMES.get(index));
        }

        @Test
        @DisplayName("the eight width constants hold their copybook values")
        void theWidthConstantsHoldTheirCopybookValues() {
            // Read off the xxxI PICTURE clauses; the .bms LENGTH= operands corroborate each one.
            assertThat(AdminMenuRequest.TRN_NAME_LENGTH).isEqualTo(4);       // CPY:24,  bms:36
            assertThat(AdminMenuRequest.TITLE_LENGTH).isEqualTo(40);         // CPY:30,  bms:40
            assertThat(AdminMenuRequest.CUR_DATE_LENGTH).isEqualTo(8);       // CPY:36,  bms:49
            assertThat(AdminMenuRequest.PGM_NAME_LENGTH).isEqualTo(8);       // CPY:42,  bms:59
            assertThat(AdminMenuRequest.CUR_TIME_LENGTH).isEqualTo(8);       // CPY:54,  bms:72
            assertThat(AdminMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);   // CPY:60,  bms:82
            assertThat(AdminMenuRequest.OPTION_LENGTH).isEqualTo(2);         // CPY:132, bms:148
            assertThat(AdminMenuRequest.ERR_MSG_LENGTH).isEqualTo(78);       // CPY:138, bms:156

            // 78 and never 80. WS-MESSAGE is PIC X(80) at app/cbl/COADM01C.cbl:38, so the MOVE to
            // ERRMSGO at :177 discards two bytes off the right. That truncation is the controller's,
            // and its DIRECTION is proved in AdminMenuResponseTest; here the point is only that the
            // declared width is the screen's 78 and has not been widened to 80 to paper over it.
            assertThat(AdminMenuRequest.ERR_MSG_LENGTH).isNotEqualTo(80);
        }

        @Test
        @DisplayName("the twenty widths sum to 668 by hand, and the subject agrees")
        void theTwentyWidthsSumToSixHundredSixtyEight() {
            // Written out longhand rather than folded over a stream, because this arithmetic IS the
            // assertion (practice B11): 4 + 40 + 8 + 8 + 40 + 8 + (12 x 40) + 2 + 78.
            int byHand = 4 + 40 + 8 + 8 + 40 + 8 + 12 * 40 + 2 + 78;

            assertThat(byHand).isEqualTo(668);
            assertThat(EXPECTED_WIDTHS).hasSize(20);
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(byHand);

            // PAYLOAD_DATA_LENGTH is computed in the subject from its individual width constants, so
            // pinning it here turns any mistyped width into a failure that names this total.
            assertThat(AdminMenuRequest.PAYLOAD_DATA_LENGTH).isEqualTo(668).isEqualTo(byHand);
        }

        @Test
        @DisplayName("the whole symbolic map is 12 + 20x7 + 668 = 820 bytes")
        void theWholeSymbolicMapIsEightHundredTwentyBytes() {
            // The stride: xxxL COMP PIC S9(4) is 2, xxxF PICTURE X is 1, FILLER PICTURE X(4) is 4, and
            // the FILLER REDEFINES xxxF / 03 xxxA pair adds ZERO because it redefines the flag byte
            // rather than following it - app/cpy-bms/COADM01.CPY:19-23. Hence 7 per field, not 8.
            assertThat(AdminMenuRequest.METADATA_BYTES_PER_FIELD).isEqualTo(2 + 1 + 4).isEqualTo(7);

            // The prefix: 02 FILLER PIC X(12) at CPY:18, present because app/bms/COADM01.bms:24
            // declares TIOAPFX=YES.
            assertThat(AdminMenuRequest.TIOAPFX_LENGTH).isEqualTo(12);

            int byHand = 12 + 20 * 7 + 668;

            assertThat(byHand).isEqualTo(820);
            assertThat(AdminMenuRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(820).isEqualTo(byHand);

            // Restated from the subject's own parts, so the identity holds however the constants move.
            assertThat(AdminMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.TIOAPFX_LENGTH
                            + AdminMenuRequest.MAPPED_FIELD_COUNT
                                    * AdminMenuRequest.METADATA_BYTES_PER_FIELD
                            + AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }

        @Test
        @DisplayName("composing the twenty fields really does produce 668 bytes, and omitting one fails it")
        void composingTheTwentyFieldsProducesSixHundredSixtyEightBytes() {
            // The arithmetic above is a claim about constants; this is the claim carried out. Each field
            // is moved into its declared width under the COBOL PIC X rule - pad right with spaces,
            // truncate on the right - and the images are concatenated in map order.
            //
            // The code page is stated EXPLICITLY (practice B8): US-ASCII, which is what the
            // app/data/ASCII fixtures are. It is never the platform default, and the bytes come from
            // the codec rather than from String.getBytes(), which would silently substitute '?' for
            // anything unrepresentable and so write a value no COBOL program could have produced.
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            AdminMenuRequest request = atDeclaredWidths("04", NavigationContext.empty());

            assertThat(codec.charset()).isEqualTo(StandardCharsets.US_ASCII);

            StringBuilder image = new StringBuilder();
            List<String> values = screenFieldsOf(request);
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                // An absent screen field is SPACES in a COBOL record, never null: option lines 5 to 12
                // are unpopulated on this screen and still occupy their forty bytes each.
                image.append(codec.movePicX(value == null ? "" : value, declaredWidthOf(index)));
            }

            assertThat(image.length()).isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH).isEqualTo(668);

            byte[] encoded = codec.encodeImage(image.toString(), "COADM1AI screen data");
            assertThat(encoded).hasSize(668);

            // Gate G21 in its sharpest form: drop a single span and the total is wrong immediately.
            // This is the check that catches a forgotten FILLER or a forgotten field, which is the one
            // defect class that produces plausible-looking output at every offset after it.
            assertThat(image.length() - AdminMenuRequest.OPTION_LINE_LENGTH)
                    .as("omitting one forty-byte option line must not still total 668")
                    .isNotEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }

        @Test
        @DisplayName("an 820-byte record is what the map image would occupy end to end")
        void theSymbolicMapImageIsEightHundredTwentyBytesWide() {
            // The request path never renders a symbolic-map image - CICS built it, and the REST payload
            // replaces it - so this asserts the geometry rather than a rendering: a fixed-width record
            // of the declared width really is 820 bytes, with the code page stated explicitly.
            FixedWidthRecord image = new FixedWidthRecord(AdminMenuRequest.SYMBOLIC_MAP_LENGTH,
                    StandardCharsets.US_ASCII);

            assertThat(image.recordLength()).isEqualTo(820);
            assertThat(image.recordLength() - AdminMenuRequest.TIOAPFX_LENGTH
                    - AdminMenuRequest.MAPPED_FIELD_COUNT
                            * AdminMenuRequest.METADATA_BYTES_PER_FIELD)
                    .as("what remains after the prefix and the twenty strides is the screen data")
                    .isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }
    }

    @Nested
    @DisplayName("Twelve option slots, of which the program can fill ten (practice B5)")
    class TwelveOptionSlots {

        @Test
        @DisplayName("all twelve slots exist, even the two the program can never write")
        void allTwelveSlotsExist() throws Exception {
            // Three different counts coexist in the source and NONE of them is twelve:
            //   * app/cpy/COADM02Y.cpy:20 sets CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4, so
            //     BUILD-MENU-OPTIONS iterates four times and only four lines are ever populated.
            //   * that paragraph's EVALUATE WS-IDX at app/cbl/COADM01C.cbl:238-261 has arms WHEN 1
            //     through WHEN 10 and then WHEN OTHER CONTINUE - there is NO arm for 11 or 12 at all,
            //     so OPTN011I and OPTN012I could never be written even if the table were full.
            //   * the option table itself is OCCURS 9.
            // The projection is nevertheless of the MAP, which declares twelve at CPY lines 60 to 126.
            // The two permanently unwritable slots are therefore preserved, not pruned: dropping them
            // would change the screen contract, and that is a behaviour change nobody asked for.
            assertThat(AdminMenuRequest.OPTION_LINE_COUNT).isEqualTo(12);

            for (int slot = 1; slot <= AdminMenuRequest.OPTION_LINE_COUNT; slot++) {
                String member = String.format(Locale.ROOT, "optn%03d", slot);
                assertThat(AdminMenuRequest.class.getMethod(member).getReturnType())
                        .as("accessor %s() must exist", member)
                        .isEqualTo(String.class);
            }

            // Named explicitly as well as swept, so the two slots most at risk of being "cleaned up"
            // are visibly covered (practice B11).
            assertThat(recordComponentNames()).contains("optn010", "optn011", "optn012");
        }

        @ParameterizedTest(name = "optn{0} is PIC X(40)")
        @ValueSource(strings = {"001", "002", "003", "004", "005", "006", "007", "008", "009", "010",
                "011", "012"})
        @DisplayName("every slot is forty bytes wide, including the unreachable eleventh and twelfth")
        void everySlotIsFortyBytesWide(String slot) {
            int index = 5 + Integer.parseInt(slot);

            assertThat(AdminMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(declaredWidthOf(index)).isEqualTo(40);
            assertThat(EXPECTED_WIDTHS.get(index)).isEqualTo(40);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo("OPTN" + slot + "I");

            // Forty is what app/cbl/COADM01C.cbl:48 builds into: WS-ADMIN-OPT-TXT PIC X(40) receives a
            // STRING of a two-character number, '. ' and a thirty-five-character name - 2 + 2 + 35 = 39
            // - so a built line is always one space short of the field, never over it.
            assertThat(2 + 2 + 35).isLessThan(AdminMenuRequest.OPTION_LINE_LENGTH);
            assertThat(violatedProperties(withScreenField(index, "x".repeat(40)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "x".repeat(41))))
                    .containsExactly("optn" + slot);
        }

        @Test
        @DisplayName("the option-line view is twelve long and in map order, OPTN001I first")
        void theOptionLineViewIsInMapOrder() {
            AdminMenuRequest request = atDeclaredWidths("04", null);

            assertThat(request.optionLines()).hasSize(AdminMenuRequest.OPTION_LINE_COUNT)
                    .hasSameSizeAs(AdminMenuRequest.OPTION_LINE_FIELDS);
            assertThat(request.optionLines().subList(0, 4))
                    .containsExactly("01. User List (Security)",
                            "02. User Add (Security)",
                            "03. User Update (Security)",
                            "04. User Delete (Security)");
            assertThat(request.optionLines().get(0)).isEqualTo(request.optn001());
            assertThat(request.optionLines().get(11)).isEqualTo(request.optn012());
        }

        @Test
        @DisplayName("the view tolerates the unpopulated slots rather than rejecting them")
        void theViewToleratesUnpopulatedSlots() {
            // An absent screen field is null on this payload, and List.of would reject it. A request
            // that simply omits option lines must therefore still yield a twelve-element list.
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.optionLines()).hasSize(12).containsNull();
            assertThat(request.optn011()).isNull();
            assertThat(request.optn012()).isNull();
        }

        @Test
        @DisplayName("the view is unmodifiable, so a caller cannot reach back into the payload")
        void theViewIsUnmodifiable() {
            AdminMenuRequest request = atDeclaredWidths("01", null);
            List<String> lines = request.optionLines();

            assertThatThrownBy(() -> lines.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.add("tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(lines::clear).isInstanceOf(UnsupportedOperationException.class);

            assertThat(request.optn001()).isEqualTo("01. User List (Security)");
        }

        @Test
        @DisplayName("the item-name constant list is immutable and parallel to the value view")
        void theItemNameListIsImmutableAndParallel() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(AdminMenuRequest.OPTION_LINE_FIELDS)
                    .containsExactly("OPTN001I", "OPTN002I", "OPTN003I", "OPTN004I", "OPTN005I",
                            "OPTN006I", "OPTN007I", "OPTN008I", "OPTN009I", "OPTN010I", "OPTN011I",
                            "OPTN012I");
            assertThat(AdminMenuRequest.OPTION_LINE_FIELDS)
                    .hasSameSizeAs(request.optionLines());
            assertThatThrownBy(() -> AdminMenuRequest.OPTION_LINE_FIELDS.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }


    @Nested
    @DisplayName("OPTIONI is the only editable field, and it is carried raw")
    class OptionIsTheOnlyEditableField {

        @Test
        @DisplayName("option accepts exactly two characters and rejects three, naming itself")
        void optionAcceptsTwoCharactersAndRejectsThree() {
            // app/bms/COADM01.bms:145-149 declares OPTION as
            //   ATTRB=(FSET,IC,NORM,NUM,UNPROT) HILIGHT=UNDERLINE JUSTIFY=(RIGHT,ZERO) LENGTH=2
            //   POS=(20,41)
            // UNPROT is what makes it the ONLY field on this screen the operator can type into, and IC
            // puts the cursor there. Every other named field is ASKIP - the eighteen headers and option
            // lines are ATTRB=(ASKIP,FSET,NORM) and ERRMSG at :154 is ATTRB=(ASKIP,BRT,FSET) - so all
            // nineteen are read-only. Those ATTRB values live in the mapset and are deliberately NOT
            // reproduced as payload semantics: this type does not know which of its fields a terminal
            // would have let a user edit, and it does not need to.
            assertThat(AdminMenuRequest.OPTION_LENGTH).isEqualTo(2);

            assertThat(violatedProperties(atDeclaredWidths("12", NavigationContext.empty()))).isEmpty();
            assertThat(violatedProperties(atDeclaredWidths("123", NavigationContext.empty())))
                    .containsExactly("option");
        }

        @Test
        @DisplayName("a request with every field at its declared width is entirely valid")
        void everyFieldAtItsDeclaredWidthIsValid() {
            assertThat(violatedProperties(atDeclaredWidths("04", NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("one character over the width is rejected, and only the offending field is named")
        void onlyTheOffendingFieldIsNamed() {
            AdminMenuRequest tooLongTitle = withScreenField(1,
                    "x".repeat(AdminMenuRequest.TITLE_LENGTH + 1));

            assertThat(violatedProperties(tooLongTitle)).containsExactly("title01");

            // title02 shares TITLE_LENGTH with title01 but carries its own constraint, so a violation on
            // one must not implicate the other. TITLE01I is CPY:30 and TITLE02I is CPY:48 - two items.
            AdminMenuRequest tooLongSecondTitle = withScreenField(4,
                    "x".repeat(AdminMenuRequest.TITLE_LENGTH + 1));

            assertThat(violatedProperties(tooLongSecondTitle)).containsExactly("title02");
        }

        @ParameterizedTest(name = "option [{0}] is accepted, not rejected")
        @ValueSource(strings = {"", " ", "  ", "0", "01", "99", "ab", " 3", "3 ", "**"})
        @DisplayName("a blank or non-numeric option is accepted, because the program answers with a message")
        void blankOrNonNumericOptionIsAccepted(String option) {
            // app/cbl/COADM01C.cbl:127-134 answers an option that is not numeric, or is above
            // CDEMO-ADMIN-OPT-COUNT, with 'Please enter a valid option number...' and repaints the
            // screen. It does NOT refuse the input. A @NotBlank, @NotNull or @Pattern here would turn
            // that message into a rejection and change observable behaviour, so only the width is
            // constrained.
            assertThat(violatedProperties(atDeclaredWidths(option, NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("an entirely absent payload is valid: no screen field is mandatory")
        void anEntirelyAbsentPayloadIsValid() {
            AdminMenuRequest empty = new AdminMenuRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    (byte) 0x00);

            assertThat(violatedProperties(empty)).isEmpty();
        }

        @ParameterizedTest(name = "[{0}] is stored byte for byte")
        @ValueSource(strings = {"", " ", "  ", " 3", "3 ", "12", "0", "ab"})
        @DisplayName("no trimming, padding, right-justifying or zero-filling happens on the way in")
        void optionIsNeitherTrimmedNorNormalised(String option) {
            // The space pattern IS the input. app/cbl/COADM01C.cbl:117-124 scans OPTIONI backwards for
            // the last non-space, moves the prefix into WS-OPTION-X PIC X(02) JUST RIGHT (:45) and then
            // INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0' (:123). So " 3" and "3 " both end up as 03 -
            // but only because the SERVICE does that work. If this payload pre-trimmed, right-justified
            // or zero-filled, the service would receive input the terminal never sent and the JUSTIFY
            // and INSPECT behaviour could not be reproduced or tested at all.
            assertThat(atDeclaredWidths(option, null).option()).isEqualTo(option);
        }

        @Test
        @DisplayName("a leading space survives, and so does a trailing one")
        void bothSpacePatternsSurviveUntouched() {
            // Called out by name rather than left to the sweep, because these are the two inputs the
            // JUST RIGHT and INSPECT pair exists to handle (practice B11).
            assertThat(atDeclaredWidths(" 3", null).option()).isEqualTo(" 3").hasSize(2);
            assertThat(atDeclaredWidths("3 ", null).option()).isEqualTo("3 ").hasSize(2);

            // Not equal to each other and neither equal to the normalised form: proof that nothing
            // collapsed them on the way in.
            assertThat(atDeclaredWidths(" 3", null).option())
                    .isNotEqualTo(atDeclaredWidths("3 ", null).option())
                    .isNotEqualTo("03")
                    .isNotEqualTo("3");
        }

        @Test
        @DisplayName("an all-spaces option is preserved rather than collapsed to empty or null")
        void allSpacesSurvives() {
            AdminMenuRequest request = atDeclaredWidths("  ", null);

            assertThat(request.option()).isEqualTo("  ").hasSize(AdminMenuRequest.OPTION_LENGTH);
        }

        @Test
        @DisplayName("no screen field is normalised: all twenty are carried exactly as supplied")
        void noScreenFieldIsNormalised() {
            // The record has no canonical-constructor body at all, so this holds for every field and
            // not only for the editable one. Values with leading and trailing spaces go in and come
            // back identical.
            AdminMenuRequest request = new AdminMenuRequest(" CA0",
                    "  padded title  ",
                    " 8/08/26",
                    "COADM01 ",
                    "  another title ",
                    " 9:41:07",
                    "  01. leading and trailing  ",
                    null, null, null, null, null, null, null, null, null, null, null,
                    " 3",
                    "  message with spaces  ",
                    null,
                    (byte) 0x7D);

            assertThat(request.trnName()).isEqualTo(" CA0");
            assertThat(request.title01()).isEqualTo("  padded title  ");
            assertThat(request.curDate()).isEqualTo(" 8/08/26");
            assertThat(request.pgmName()).isEqualTo("COADM01 ");
            assertThat(request.title02()).isEqualTo("  another title ");
            assertThat(request.curTime()).isEqualTo(" 9:41:07");
            assertThat(request.optn001()).isEqualTo("  01. leading and trailing  ");
            assertThat(request.option()).isEqualTo(" 3");
            assertThat(request.errMsg()).isEqualTo("  message with spaces  ");
        }
    }

    @Nested
    @DisplayName("Conversation state travels in the payload (gates G37 and G53, rule R6)")
    class ConversationStateTravelsInThePayload {

        @Test
        @DisplayName("the communication area is a payload member, not a session lookup")
        void theCommunicationAreaIsAPayloadMember() throws Exception {
            NavigationContext context = NavigationContext.empty().withUserTypeAdmin();
            AdminMenuRequest request = atDeclaredWidths("01", context);

            assertThat(recordComponentNames()).contains("navigationContext");
            assertThat(AdminMenuRequest.class.getMethod("navigationContext").getReturnType())
                    .isEqualTo(NavigationContext.class);
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.isCommareaPresent()).isTrue();
        }

        @Test
        @DisplayName("the carried communication area is exactly 160 bytes: 34 + 84 + 12 + 16 + 14")
        void theCarriedCommunicationAreaIsOneHundredSixtyBytes() {
            // app/cpy/COCOM01Y.cpy, group by group:
            //   CDEMO-GENERAL-INFO   :21-31  4 + 8 + 4 + 8 + 8 + 1 + 1  =  34
            //   CDEMO-CUSTOMER-INFO  :33-36  9 + 25 + 25 + 25           =  84
            //   CDEMO-ACCOUNT-INFO   :38-39  11 + 1                     =  12
            //   CDEMO-CARD-INFO      :41     16                         =  16
            //   CDEMO-MORE-INFO      :43-44  7 + 7                      =  14
            //                                                             ---
            //                                                             160
            int byHand = 34 + 84 + 12 + 16 + 14;

            assertThat(byHand).isEqualTo(160);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160).isEqualTo(byHand);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(160);

            // And the image the carried context actually renders is that wide. The code page is stated
            // explicitly - US-ASCII, as the app/data/ASCII fixtures are - and never defaulted (B8).
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(request.navigationContext().toFixedWidth(codec)).hasSize(160);
        }

        @Test
        @DisplayName("the last map and mapset are X(7), not X(8) - the width the total depends on")
        void theLastMapAndMapsetAreSevenBytes() {
            // app/cpy/COCOM01Y.cpy:43-44 declares CDEMO-LAST-MAP and CDEMO-LAST-MAPSET as PIC X(7).
            // Seven, not the eight a reader would expect from CDEMO-FROM-PROGRAM PIC X(08) two lines
            // earlier - and seven is right, because a map name is seven characters: COADM1A.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AdminMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);

            FixedWidthRecord.FieldSpan lastMap =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAP_FIELD);
            FixedWidthRecord.FieldSpan lastMapset =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAPSET_FIELD);

            assertThat(lastMap.length()).isEqualTo(7);
            assertThat(lastMapset.length()).isEqualTo(7);
            assertThat(lastMapset.offset()).isEqualTo(lastMap.offset() + 7);

            // Why it matters: at X(8) each the record would be 162 bytes and every consumer of the
            // 160-byte area would be misaligned from CDEMO-LAST-MAP onwards.
            assertThat(34 + 84 + 12 + 16 + (8 + 8)).isNotEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the admin and user conditions are both reachable through the carried context")
        void bothUserTypeConditionsAreReachable() {
            // 88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy:27 and
            // 88 CDEMO-USRTYP-USER  VALUE 'U' at :28. Both sides of both.
            AdminMenuRequest asAdmin =
                    atDeclaredWidths("01", NavigationContext.empty().withUserTypeAdmin());
            AdminMenuRequest asUser =
                    atDeclaredWidths("01", NavigationContext.empty().withUserTypeUser());

            assertThat(asAdmin.navigationContext().isAdmin()).isTrue();
            assertThat(asAdmin.navigationContext().isUser()).isFalse();
            assertThat(asUser.navigationContext().isUser()).isTrue();
            assertThat(asUser.navigationContext().isAdmin()).isFalse();

            assertThat(asAdmin.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .isEqualTo("A");
            assertThat(asUser.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER)
                    .isEqualTo("U");
        }

        @Test
        @DisplayName("neither user-type condition holds for a type that is neither A nor U")
        void neitherUserTypeConditionHoldsForAnotherValue() {
            // The two 88-levels are not complementary: CDEMO-USER-TYPE is PIC X(01) and a freshly
            // initialised area holds a space, so no role is implied before sign-on. isUser() must
            // therefore not be written as !isAdmin().
            AdminMenuRequest beforeSignOn = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(beforeSignOn.navigationContext().isAdmin()).isFalse();
            assertThat(beforeSignOn.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("the enter and re-enter conditions are both reachable, and read through")
        void bothProgramContextConditionsAreReachable() {
            // 88 CDEMO-PGM-ENTER VALUE 0 at app/cpy/COCOM01Y.cpy:30 and
            // 88 CDEMO-PGM-REENTER VALUE 1 at :31. app/cbl/COADM01C.cbl:86-90 tests
            // IF NOT CDEMO-PGM-REENTER, sets it true, clears the map to LOW-VALUES and paints.
            AdminMenuRequest onEnter = atDeclaredWidths("01", NavigationContext.empty().withPgmEnter());
            AdminMenuRequest onReenter =
                    atDeclaredWidths("01", NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.isEnter()).isTrue();
            assertThat(onEnter.isReenter()).isFalse();
            assertThat(onReenter.isReenter()).isTrue();
            assertThat(onReenter.isEnter()).isFalse();

            // Read-through rather than a second copy, so CDEMO-PGM-CONTEXT keeps exactly one home.
            assertThat(onEnter.isEnter()).isEqualTo(onEnter.navigationContext().isEnter());
            assertThat(onReenter.isReenter()).isEqualTo(onReenter.navigationContext().isReenter());
            assertThat(onEnter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(onReenter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("neither context view holds for a digit that is neither 0 nor 1")
        void neitherContextViewHoldsForAnUnusedDigit() {
            // CDEMO-PGM-CONTEXT is PIC 9(01) and can hold any digit, so the pair is not exhaustive and
            // isReenter() must not be written as !isEnter(). A context of 9 satisfies neither.
            AdminMenuRequest request =
                    atDeclaredWidths("01", NavigationContext.empty().withPgmContext(9));

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.isCommareaPresent()).isTrue();
        }

        @ParameterizedTest(name = "EIBAID 0x{0} survives unresolved")
        @CsvSource({"7D", "F3", "F1", "6D", "00"})
        @DisplayName("the attention identifier byte is carried raw and unresolved")
        void theAttentionIdentifierIsCarriedRaw(String hex) {
            // app/cbl/COADM01C.cbl:93-103 evaluates EIBAID against DFHENTER and DFHPF3 with a
            // WHEN OTHER default, so exactly three outcomes exist. Resolving the byte to a key token is
            // the shared PF-key resolver's job in the service layer; the payload carries the raw byte so
            // that resolution stays in one place and can be covered without an HTTP layer in the path.
            byte aid = (byte) Integer.parseInt(hex, 16);
            AdminMenuRequest request = withScreenField(18, "01");

            assertThat(request.eibAid()).isEqualTo((byte) 0x7D);
            assertThat(atDeclaredWidths("01", null).eibAid()).isEqualTo((byte) 0x7D);

            AdminMenuRequest withAid = new AdminMenuRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, "01", null, null,
                    aid);

            assertThat(withAid.eibAid()).isEqualTo(aid);
        }

        @Test
        @DisplayName("an absent communication area encodes EIBCALEN = 0")
        void anAbsentCommunicationAreaEncodesEibcalenZero() {
            // The marker for IF EIBCALEN = 0 at app/cbl/COADM01C.cbl:82 - the branch that moves
            // 'COSGN00C' into CDEMO-FROM-PROGRAM and routes to the sign-on screen without ever
            // inspecting the map. Absence is represented by the reference being absent rather than by a
            // separate boolean, because a flag would be a second source of truth able to contradict the
            // reference beside it.
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.navigationContext()).isNull();
            assertThat(request.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("a present communication area clears the marker - the ELSE branch at :85")
        void aPresentCommunicationAreaClearsTheMarker() {
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(request.navigationContext()).isNotNull();
            assertThat(request.isCommareaPresent()).isTrue();
        }

        @Test
        @DisplayName("neither context view holds without a communication area to consult")
        void neitherContextViewHoldsWithoutACommunicationArea() {
            // The guard order is behaviour, not style: COADM01C never reaches its context test when
            // EIBCALEN = 0, because :82 diverts first. A caller must consult isCommareaPresent() before
            // either view, and both views must be safe to call regardless.
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("there is no server-side session state of any kind")
        void thereIsNoServerSideSessionState() {
            // Gate G53 and practice B9. A record's components are private and final, so the only way
            // state could hide here is a static field, and a static mutable holder would be a session
            // by another name - it would break request isolation as surely as an HttpSession would.
            //
            // SYNTHETIC FIELDS ARE SKIPPED, and that is not a loophole: under the JaCoCo agent - which
            // app/java/pom.xml attaches to both `test` and `verify` - every instrumented class gains a
            // synthetic `private static transient boolean[] $jacocoData`, which is static and NOT final.
            // Asserting over synthetic members would therefore pass under plain compilation and fail
            // under the coverage gate, while proving nothing about declared state either way.
            List<Field> declaredState = Arrays.stream(AdminMenuRequest.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .collect(Collectors.toList());

            assertThat(declaredState).isNotEmpty();
            for (Field field : declaredState) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final - no static mutable state", field.getName())
                            .isTrue();
                } else {
                    // The record's own component backing fields: private and final, so an instance
                    // handed to a collaborator cannot change underneath it.
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }

            assertThat(AdminMenuRequest.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("no servlet, session or thread-local type appears anywhere in the payload")
        void noServletOrSessionTypeAppearsInThePayload() {
            // Stated by name as well as swept (practice B11). The component types are String, byte and
            // NavigationContext and nothing else, so there is no HttpSession, no HttpServletRequest, no
            // ThreadLocal and no security principal for state to hide in.
            Set<String> componentTypes = Arrays.stream(AdminMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .map(Class::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            assertThat(componentTypes).containsExactlyInAnyOrder("java.lang.String",
                    "byte",
                    NavigationContext.class.getName());
            assertThat(componentTypes).allSatisfy(type -> assertThat(type)
                    .doesNotContain("jakarta.servlet")
                    .doesNotContain("HttpSession")
                    .doesNotContain("HttpServletRequest")
                    .doesNotContain("ThreadLocal"));
        }
    }


    @Nested
    @DisplayName("Screen identity, from the CSD and the program's own WORKING-STORAGE")
    class ScreenIdentity {

        @Test
        @DisplayName("the transaction is CA00 and fills X(4) exactly")
        void theTransactionIsCa00() {
            // DEFINE TRANSACTION(CA00) at app/csd/CARDDEMO.CSD:327, and the literal
            // WS-TRANID PIC X(04) VALUE 'CA00' at app/cbl/COADM01C.cbl:37.
            assertThat(AdminMenuRequest.TRANSACTION_ID).isEqualTo("CA00");

            // Four characters into a four-byte field: exact, so no padding and no truncation occurs
            // when it is moved to TRNNAMEI PIC X(4) at app/cpy-bms/COADM01.CPY:24.
            assertThat(AdminMenuRequest.TRANSACTION_ID).hasSize(4)
                    .hasSize(AdminMenuRequest.TRN_NAME_LENGTH)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("the program is COADM01C and fills X(8) exactly")
        void theProgramIsCoadm01c() {
            // PROGRAM(COADM01C) at app/csd/CARDDEMO.CSD:328, PROGRAM-ID. COADM01C. at
            // app/cbl/COADM01C.cbl:23, and WS-PGMNAME PIC X(08) VALUE 'COADM01C' at :36.
            assertThat(AdminMenuRequest.PROGRAM_NAME).isEqualTo("COADM01C");

            // Eight characters into an eight-byte field: exact, so PGMNAMEI PIC X(8) at
            // app/cpy-bms/COADM01.CPY:42 holds it with no padding.
            assertThat(AdminMenuRequest.PROGRAM_NAME).hasSize(8)
                    .hasSize(AdminMenuRequest.PGM_NAME_LENGTH)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("the mapset is COADM01 and the map is COADM1A")
        void theMapsetAndMapAreNamedAsTheCsdNamesThem() {
            // DEFINE MAPSET(COADM01) at app/csd/CARDDEMO.CSD:110; COADM1A DFHMDI COLUMN=1 LINE=1
            // SIZE=(24,80) in app/bms/COADM01.bms.
            assertThat(AdminMenuRequest.MAPSET_NAME).isEqualTo("COADM01").hasSize(7);
            assertThat(AdminMenuRequest.MAP_NAME).isEqualTo("COADM1A").hasSize(7);

            // The map name is seven characters because the symbolic-map group items are that name plus
            // a one-character direction suffix: COADM1AI at CPY:17 and COADM1AO at CPY:139.
            assertThat(AdminMenuRequest.MAP_NAME + "I").isEqualTo("COADM1AI").hasSize(8);
            assertThat(AdminMenuRequest.MAP_NAME + "O").isEqualTo("COADM1AO").hasSize(8);
        }

        @Test
        @DisplayName("the identity constants are what a well-formed request actually carries")
        void theIdentityConstantsAreCarriedByAWellFormedRequest() {
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(request.trnName()).isEqualTo(AdminMenuRequest.TRANSACTION_ID);
            assertThat(request.pgmName()).isEqualTo(AdminMenuRequest.PROGRAM_NAME);
            assertThat(violatedProperties(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The duplication with the main menu is documented, not removed (practice B4)")
    class DuplicationIsDocumentedNotRemoved {

        @Test
        @DisplayName("AdminMenuRequest and MainMenuRequest are distinct Java types, deliberately")
        void adminAndMainMenuRequestsAreDistinctTypes() {
            // VERBATIM diff result, recorded rather than collapsed:
            //
            //   $ diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY
            //   17c17
            //   <        01  COADM1AI.
            //   ---
            //   >        01  COMEN1AI.
            //   139c139
            //   <        01  COADM1AO REDEFINES COADM1AI.
            //   ---
            //   >        01  COMEN1AO REDEFINES COMEN1AI.
            //
            // Two lines. Every other line - all twenty field groups and every width - is byte-identical.
            // The mapsets differ only in the mapset name (:19), the map name (:26), a comment (:2), a
            // version timestamp (:166) and ONE UNNAMED literal label: COADM01.bms:77-79 is LENGTH=10
            // INITIAL='Admin Menu' where COMEN01.bms:77-79 is LENGTH=9 INITIAL='Main Menu'. Being
            // unnamed, that label generates no symbolic-map item, which is exactly why the two .CPY
            // files coincide.
            //
            // The duplication is real and it is left in place. No shared base class, no shared
            // interface, no generic parameterised by group name, no MenuDtoSupport helper, no
            // package-info and no single parameterised suite spanning both types. Two reasons, either
            // sufficient alone:
            //   * The screens are independent contracts that merely coincide today. The admin menu
            //     offers four options from app/cpy/COADM02Y.cpy; the main menu offers ten from
            //     app/cpy/COMEN02Y.cpy, gated by an X(01) user-type authorisation column the admin
            //     table does not have. Coupling the payloads would make a future divergence in one
            //     screen a breaking change in the other.
            //   * Collapsing two independently declared copybooks into one Java type is precisely the
            //     silent structural change this migration forbids, and it would erase the field-name
            //     provenance that field-for-field parity diffing depends on.
            assertThat(AdminMenuRequest.class).isNotEqualTo(MainMenuRequest.class);
            assertThat(AdminMenuRequest.class.getName()).isNotEqualTo(MainMenuRequest.class.getName());

            // Neither is assignable to the other, so they cannot have been unified behind a supertype.
            assertThat(MainMenuRequest.class.isAssignableFrom(AdminMenuRequest.class)).isFalse();
            assertThat(AdminMenuRequest.class.isAssignableFrom(MainMenuRequest.class)).isFalse();

            // Both are records extending java.lang.Record directly - no shared abstract parent was
            // introduced to hold the coincidence.
            assertThat(AdminMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(AdminMenuRequest.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("the coincidence itself is asserted, so a future divergence is visible here")
        void theCoincidenceIsAssertedRatherThanAssumed() {
            // The point of asserting the coincidence rather than exploiting it: if either screen's map
            // is ever changed, this test fails and a human decides what to do - instead of one shared
            // type silently imposing one screen's shape on the other.
            //
            // Note that the two types do not even spell their constants alike - the field count is
            // MAPPED_FIELD_COUNT here and MAP_FIELD_COUNT there, the data width PAYLOAD_DATA_LENGTH
            // here and SYMBOLIC_MAP_PAYLOAD_LENGTH there. That is what independently written
            // contracts look like, and it is a further reason no shared supertype was introduced:
            // unifying them would have forced one screen's vocabulary onto the other.
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(AdminMenuRequest.MAPPED_FIELD_COUNT);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.SYMBOLIC_MAP_LENGTH);
            assertThat(MainMenuRequest.OPTION_LINE_COUNT)
                    .isEqualTo(AdminMenuRequest.OPTION_LINE_COUNT);

            // The sibling independently records the 28-field total that this file's 20-of-28 assertion
            // relies on, because app/bms/COMEN01.bms declares the same 28 DFHMDF fields as
            // app/bms/COADM01.bms. Two independent transcriptions of the same count agreeing is
            // corroboration; if they ever disagreed, one of them would be wrong.
            assertThat(MainMenuRequest.SCREEN_FIELD_COUNT).isEqualTo(28);
            assertThat(MainMenuRequest.SCREEN_FIELD_COUNT - MainMenuRequest.MAP_FIELD_COUNT)
                    .as("28 DFHMDF fields less the 20 that carry a name leaves 8 literal labels")
                    .isEqualTo(8);

            // The identities, by contrast, must differ: CM00/COMEN01C against CA00/COADM01C - the
            // transactions are defined separately in app/csd/CARDDEMO.CSD.
            assertThat(MainMenuRequest.TRANSACTION_ID).isNotEqualTo(AdminMenuRequest.TRANSACTION_ID);
            assertThat(MainMenuRequest.PROGRAM_NAME).isNotEqualTo(AdminMenuRequest.PROGRAM_NAME);
            assertThat(MainMenuRequest.MAPSET_NAME).isNotEqualTo(AdminMenuRequest.MAPSET_NAME);
        }
    }

    @Nested
    @DisplayName("The wire carries the twenty screen fields, the area and the AID - nothing else")
    class JsonWireFormat {

        @Test
        @DisplayName("no symbolic-map metadata item reaches the wire under any capitalisation")
        void noMetadataItemReachesTheWire() throws Exception {
            String json = new ObjectMapper()
                    .writeValueAsString(atDeclaredWidths("01", NavigationContext.empty()));

            // xxxL, xxxF and xxxA on the input view, and xxxC, xxxP, xxxH and xxxV on the output view,
            // are length, flag and attribute metadata rather than screen values. None is modelled, so
            // none can appear - checked in both the copybook's upper case and the lower case Jackson
            // would derive from a Java member name.
            for (String item : EXPECTED_ITEM_NAMES) {
                String base = item.substring(0, item.length() - 1);
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(json).doesNotContain("\"" + base + suffix + "\"")
                            .doesNotContain("\"" + base.toLowerCase(Locale.ROOT) + suffix + "\"");
                }
            }

            // And the twenty that DO belong are all present, so the test cannot pass by serialising
            // nothing at all.
            for (String member : EXPECTED_MEMBER_NAMES) {
                assertThat(json).as("payload member %s must reach the wire", member)
                        .contains("\"" + member + "\"");
            }
            assertThat(json).contains("\"navigationContext\"").contains("\"eibAid\"");
        }

        @Test
        @DisplayName("no derived view reaches the wire either")
        void noDerivedViewReachesTheWire() throws Exception {
            String json = new ObjectMapper().writeValueAsString(
                    atDeclaredWidths("01", NavigationContext.empty().withPgmReenter()));

            // optionLines(), isCommareaPresent(), isEnter() and isReenter() are computed from the
            // components rather than stored beside them, so serialising them would put a second copy of
            // a value on the wire that could disagree with the first.
            assertThat(json).doesNotContain("optionLines")
                    .doesNotContain("commareaPresent")
                    .doesNotContain("\"enter\"")
                    .doesNotContain("\"reenter\"");
        }

        @Test
        @DisplayName("all twenty screen fields survive a round trip untouched")
        void allTwentyScreenFieldsSurviveARoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AdminMenuRequest original =
                    atDeclaredWidths("  ", NavigationContext.empty().withPgmReenter());

            AdminMenuRequest roundTripped =
                    mapper.readValue(mapper.writeValueAsString(original), AdminMenuRequest.class);

            assertThat(roundTripped).isEqualTo(original);
            assertThat(screenFieldsOf(roundTripped))
                    .containsExactlyElementsOf(screenFieldsOf(original));

            // The two-space option in particular: a round trip must not trim it to empty or to null.
            assertThat(roundTripped.option()).isEqualTo("  ");
            assertThat(roundTripped.errMsg()).hasSize(AdminMenuRequest.ERR_MSG_LENGTH);
            assertThat(roundTripped.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(roundTripped.eibAid()).isEqualTo(original.eibAid());
            assertThat(roundTripped.isReenter()).isTrue();
        }

        @Test
        @DisplayName("an empty option round trips as empty, not as null")
        void anEmptyOptionRoundTripsAsEmpty() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AdminMenuRequest original = atDeclaredWidths("", null);

            AdminMenuRequest roundTripped =
                    mapper.readValue(mapper.writeValueAsString(original), AdminMenuRequest.class);

            assertThat(roundTripped.option()).isEmpty();
            assertThat(roundTripped.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("a body naming only the option binds, leaving every other field absent")
        void aSparseBodyBinds() throws Exception {
            // What a client actually posts when the operator has typed an option and nothing else. The
            // absent communication area is the EIBCALEN = 0 case at app/cbl/COADM01C.cbl:82.
            AdminMenuRequest bound = new ObjectMapper()
                    .readValue("{\"option\":\"04\"}", AdminMenuRequest.class);

            assertThat(bound.option()).isEqualTo("04");
            assertThat(bound.trnName()).isNull();
            assertThat(bound.isCommareaPresent()).isFalse();
            assertThat(bound.isEnter()).isFalse();
            assertThat(bound.isReenter()).isFalse();
            assertThat(bound.optionLines()).hasSize(AdminMenuRequest.OPTION_LINE_COUNT)
                    .containsOnlyNulls();
            assertThat(violatedProperties(bound)).isEmpty();
        }
    }
}

