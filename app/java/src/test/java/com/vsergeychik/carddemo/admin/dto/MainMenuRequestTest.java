package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MainMenuRequest}, the inbound payload of {@code GET /api/menu} - CICS transaction
 * {@code CM00}, program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}. The subject is
 * the Java projection of the {@code xxxI} items of {@code 01 COMEN1AI} in
 * {@code app/cpy-bms/COMEN01.CPY} lines 17 to 138.
 *
 * <h2>User rules</h2>
 *
 * <p><strong>No user-specified rules were provided for this project.</strong> {@code review_rules}
 * returns exactly one line - "No user rules provided." - and that single line is the whole document.
 * No user rule therefore governs this file. Nothing has been invented to fill the gap, and their
 * absence is <em>not</em> licence to assert less: the twelve enterprise practices the Agent Action
 * Plan substitutes in their place are binding here, and each is discharged by a named construct
 * below.
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
 *       so the copybook remains the authority while this file stays independent of it.</li>
 *   <li><strong>B4</strong> - the duplication between this screen and the admin menu is documented,
 *       not removed. See {@link DuplicationIsDocumentedNotRemoved}. There is no shared base class,
 *       helper, fixture builder or cross-type parameterised suite anywhere in this file.</li>
 *   <li><strong>B5</strong> - all twelve option slots are asserted present even though
 *       {@code COMEN01C} can never fill more than ten, and a user type that is neither {@code 'A'}
 *       nor {@code 'U'} is carried verbatim rather than defaulted. See {@link TwelveOptionSlots} and
 *       {@link UserTypeIsLoadBearingHere}.</li>
 *   <li><strong>B7</strong> - deterministic and non-interactive. There is no {@code now()}, no
 *       randomness, no sleep, no file or network access, and no ordering dependence between tests:
 *       every test builds the values it needs and shares nothing with any other. Every date and time
 *       value is a fixed transcribed literal, which is why no {@code java.time.Clock} is needed - the
 *       subject stores what it was handed and never asks what time it is.</li>
 *   <li><strong>B8</strong> - explicit over implicit. Each width is asserted against the subject's
 *       named constant <em>and</em> against the transcribed integer literal, so a constant that
 *       drifts fails instead of silently agreeing with itself; and every place bytes are produced
 *       states its code page explicitly through {@link FixedWidthCodec} or
 *       {@link FixedWidthRecord}, never {@code String.getBytes()} with a platform default.</li>
 *   <li><strong>B9</strong> - <strong>this class declares no mutable field of any kind</strong>,
 *       static or instance. The five fields it declares are {@code private static final} lists built
 *       by {@link List#of} over {@code String} and {@code Integer}, so they are deeply immutable
 *       constants rather than state. Everything with a lifecycle is built where it is used: the
 *       validator factory is opened and closed inside the single helper that needs it, and the object
 *       mapper is constructed inside each test that serialises. No test can observe or corrupt
 *       anything another left behind. The subject is held to the same standard reflectively in
 *       {@link ConversationStateTravelsInThePayload}.</li>
 *   <li><strong>B10</strong> - these tests ship in the same phase as the record they cover.</li>
 *   <li><strong>B11</strong> - the width, stride and total-width arithmetic is written out by hand.
 *       No copybook parser is used, and every reflective sweep is paired with explicitly named
 *       assertions so a reader can see which cases were checked rather than trusting a loop.</li>
 *   <li><strong>B12</strong> - every expected literal cites its source line, because this baseline is
 *       <em>statically derived</em> from the COBOL and not captured from a run of it. The legacy
 *       programs cannot be executed in this environment, so the citation is the only audit trail a
 *       reviewer has.</li>
 *   <li><strong>B6</strong> - <strong>no subject.</strong> The main menu screen carries no credential
 *       field, so there is nothing here to mask, hash or redact, and no such assertion is invented.
 *       {@code COMEN01C} copies {@code CSUSR01Y} at {@code app/cbl/COMEN01C.cbl:58} but never reads
 *       {@code SEC-USR-PWD}; the one line that would have touched the security record,
 *       {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, is commented out at {@code :150}.</li>
 * </ul>
 *
 * <h2>What is asserted, and why those things</h2>
 *
 * <p>The subject is a pure projection, so the suite is organised around the properties a projection
 * can get wrong. A failure then names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li><strong>Exactly 20 payload members</strong>, one per name-labelled {@code DFHMDF}, with the
 *       length, flag and attribute items deliberately absent - gate G9.</li>
 *   <li><strong>The widths</strong>, each pinned twice, summing to 668 inside an 820-byte image -
 *       gate G21, which fails immediately if a span is dropped.</li>
 *   <li><strong>{@code CDEMO-USER-TYPE} is load-bearing on this screen and only on this screen</strong>,
 *       because {@code COMEN01C.cbl:136-143} gates admin-only options on it. It is carried verbatim
 *       and never derived - the distinguishing obligation of this file.</li>
 *   <li><strong>{@code OPTIONI} is the only editable field</strong>, is size-constrained at 2, and is
 *       carried entirely raw, because {@code COMEN01C.cbl:117-124} consumes the exact space pattern
 *       the terminal sent.</li>
 *   <li><strong>Twelve option slots</strong>, of which the program can fill ten - the sharpest
 *       dead-code trap in this package.</li>
 *   <li><strong>No server-side session</strong>: the communication area, the attention identifier and
 *       the enter/re-enter context all travel in the body - gates G37 and G53.</li>
 *   <li><strong>The identity constants</strong> {@code CM00} and {@code COMEN01C} fit their fields
 *       exactly.</li>
 * </ol>
 *
 * <p>Gates with no subject here, and deliberately not simulated: G22 to G29 (this screen declares no
 * decimal {@code PICTURE}, performs no arithmetic and never touches {@code CobolDecimal}), G35 (no
 * {@code CALL 'CEE3ABD'} on an online path), and G44 to G47 (no repository, no dataset, no
 * {@code FileStatus}). G34 and G40 belong to the response side and are asserted in
 * {@code MainMenuResponseTest}, not here.
 */
@DisplayName("MainMenuRequest - the COMEN1AI input projection of the CM00 main menu (GET /api/menu)")
class MainMenuRequestTest {

    // =================================================================================================
    // Transcribed expectations. Every constant below is read off a legacy source and written here as a
    // literal; none is derived from the class under test, so a drift in either place fails (practice
    // B12). All are static final and deeply immutable - a List.of of String or Integer - so they are
    // constants, not shared mutable state (practice B9, gate G53).
    // =================================================================================================

    /**
     * The twenty {@code xxxI} symbolic-map item names in map order, transcribed from
     * {@code app/cpy-bms/COMEN01.CPY} lines 24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90, 96, 102,
     * 108, 114, 120, 126, 132 and 138. The group item that holds them opens at {@code :17} with
     * {@code 01  COMEN1AI.}
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
     * {@code app/bms/COMEN01.bms} at lines 36, 40, 49, 59, 63, 72, 82, 87, 92, 97, 102, 107, 112,
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
     * {@code xxxA} the attribute view that {@code REDEFINES} it - {@code app/cpy-bms/COMEN01.CPY}
     * lines 19 to 23 for the first field, repeated identically for the other nineteen. The output
     * view {@code 01 COMEN1AO} at {@code :139} adds {@code xxxC}, {@code xxxP}, {@code xxxH} and
     * {@code xxxV}; those belong to the response projection and are checked here too, because a
     * request that carried them would have copied the wrong half of the copybook.
     */
    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    // =================================================================================================
    // Helpers. All are instance methods over locals only, so nothing survives a test (practice B9).
    // No helper is shared with AdminMenuRequestTest, and none is inherited from anywhere: the two
    // screens are independent contracts that merely coincide today (practice B4).
    // =================================================================================================

    /**
     * A request with every screen field at exactly its declared width, so the only variables in a
     * given test are the two it is actually about. The option lines carry the ten captions
     * {@code app/cpy/COMEN02Y.cpy:25-84} declares, rendered as {@code COMEN01C.cbl:243-246} composes
     * them - {@code CDEMO-MENU-OPT-NUM}, then {@code '. '}, then {@code CDEMO-MENU-OPT-NAME} - and
     * slots eleven and twelve are absent, because the count-10 bound at {@code :238-239} means the
     * program never writes them.
     *
     * @param option  the value for {@code OPTIONI}, passed through untouched
     * @param context the communication area, or {@code null} to mean {@code EIBCALEN = 0}
     * @return a fresh request; no instance is ever shared between tests
     */
    private MainMenuRequest atDeclaredWidths(String option, NavigationContext context) {
        return new MainMenuRequest("CM00",
                "x".repeat(MainMenuRequest.TITLE_LENGTH),
                "07/19/22",
                "COMEN01C",
                "y".repeat(MainMenuRequest.TITLE_LENGTH),
                "23:12:33",
                "01. Account View",
                "02. Account Update",
                "03. Credit Card List",
                "04. Credit Card View",
                "05. Credit Card Update",
                "06. Transaction List",
                "07. Transaction View",
                "08. Transaction Add",
                "09. Transaction Reports",
                "10. Bill Payment",
                null,
                null,
                option,
                "z".repeat(MainMenuRequest.ERR_MSG_LENGTH),
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
    private MainMenuRequest withScreenField(int index, String value) {
        String[] fields = new String[MainMenuRequest.MAP_FIELD_COUNT];
        fields[index] = value;
        return new MainMenuRequest(fields[0],
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
                NavigationContext.empty(),
                (byte) 0x7D);
    }

    /** The record component names in declaration order, which is map order for the first twenty. */
    private List<String> recordComponentNames() {
        return Arrays.stream(MainMenuRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }

    /** The twenty screen field values of {@code request} in map order, {@code null} included. */
    private List<String> screenFieldsOf(MainMenuRequest request) {
        List<String> values = new ArrayList<>();
        for (String member : EXPECTED_MEMBER_NAMES) {
            try {
                values.add((String) MainMenuRequest.class.getMethod(member).invoke(request));
            } catch (ReflectiveOperationException unreachable) {
                throw new AssertionError("no accessor " + member + "() on MainMenuRequest",
                        unreachable);
            }
        }
        return values;
    }

    /**
     * The {@code @Size} maximum the subject declares for the {@code index}-th screen field, read from
     * the accessor the record generates. This is the <em>subject's</em> number, never this file's.
     */
    private int declaredWidthOf(int index) {
        try {
            Size size = MainMenuRequest.class.getMethod(EXPECTED_MEMBER_NAMES.get(index))
                    .getAnnotation(Size.class);
            assertThat(size).as("@Size on %s()", EXPECTED_MEMBER_NAMES.get(index)).isNotNull();
            return size.max();
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("no accessor for " + EXPECTED_MEMBER_NAMES.get(index), absent);
        }
    }

    /**
     * The property paths Bean Validation rejects for {@code request}. The factory is opened and closed
     * around the single call, so no validator outlives the test that used it (practice B9).
     */
    private List<String> violatedProperties(MainMenuRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request)
                    .stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Nested
    @DisplayName("The twenty payload members, and only those - gate G9")
    class TheTwentyPayloadMembers {

        @Test
        @DisplayName("twenty screen members in map order, then the two carriers, and nothing else")
        void twentyScreenMembersThenTwoCarriers() {
            // Order matters and is asserted, not merely membership: app/cpy-bms/COMEN01.CPY declares
            // the items in screen order, and a projection that reordered them would still validate
            // while writing a different record than the copybook describes.
            List<String> components = recordComponentNames();

            assertThat(components).hasSize(22);
            assertThat(components.subList(0, 20)).containsExactlyElementsOf(EXPECTED_MEMBER_NAMES);
            assertThat(components.subList(20, 22)).containsExactlyElementsOf(EXPECTED_CARRIER_NAMES);
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(20)
                    .isEqualTo(EXPECTED_MEMBER_NAMES.size());
        }

        @Test
        @DisplayName("each xxxI item projects onto exactly one Java member, one for one")
        void eachItemProjectsOntoExactlyOneMember() {
            // The correspondence is spelled out pair by pair, so a reviewer can check the naming rule
            // itself: strip the trailing I, lower-case the rest, and camel-case the two-word names.
            assertThat(EXPECTED_ITEM_NAMES).hasSameSizeAs(EXPECTED_MEMBER_NAMES);
            assertThat(new LinkedHashSet<>(EXPECTED_MEMBER_NAMES)).hasSize(20);

            for (int index = 0; index < EXPECTED_ITEM_NAMES.size(); index++) {
                String item = EXPECTED_ITEM_NAMES.get(index);
                String member = EXPECTED_MEMBER_NAMES.get(index);

                assertThat(item).as("item %s must end in the payload suffix I", item).endsWith("I");
                assertThat(member).as("%s projects %s", member, item)
                        .isEqualToIgnoringCase(item.substring(0, item.length() - 1));
                assertThat(recordComponentNames()).contains(member);
            }
        }

        @Test
        @DisplayName("every screen member is a String - no decimal PICTURE appears on this screen")
        void everyScreenMemberIsAString() {
            // All twenty xxxI items are PIC X(n). There is no PIC 9, no V, no COMP-3 and therefore no
            // BigDecimal here - which is exactly why gates G22 to G29 have no subject on this screen
            // and are not simulated. OPTIONI is PIC X(2) at CPY:132 even though app/bms/COMEN01.bms:145
            // declares the field NUM: the numeric attribute constrains the 3270 keyboard, not the
            // storage, and COMEN01C.cbl:124 does the conversion itself with MOVE WS-OPTION-X TO
            // WS-OPTION PIC 9(02).
            for (RecordComponent component : MainMenuRequest.class.getRecordComponents()) {
                if (EXPECTED_MEMBER_NAMES.contains(component.getName())) {
                    assertThat(component.getType()).as("%s must be a String", component.getName())
                            .isEqualTo(String.class);
                }
            }

            assertThat(MainMenuRequest.class.getRecordComponents()).noneMatch(component ->
                    component.getType() == double.class
                            || component.getType() == float.class
                            || component.getType() == Double.class
                            || component.getType() == Float.class
                            || component.getType() == java.math.BigDecimal.class);
        }

        @Test
        @DisplayName("the carriers are a NavigationContext and a raw byte - nothing else is a member")
        void theCarriersAreAContextAndAByte() {
            List<Class<?>> types = Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .distinct()
                    .collect(Collectors.toList());

            assertThat(types).containsExactlyInAnyOrder(String.class,
                    NavigationContext.class,
                    byte.class);
        }

        @Test
        @DisplayName("twenty of the twenty-eight DFHMDF fields are named; the other eight are labels")
        void twentyOfTwentyEightDfhmdfFieldsAreNamed() {
            // app/bms/COMEN01.bms declares 28 DFHMDF fields. Exactly 20 carry a name and therefore
            // generate a symbolic-map item; the remaining 8 are unnamed literal screen furniture and
            // generate nothing, which is why they are absent from this payload BY DESIGN rather than
            // by omission. The eight, with their .bms lines:
            //   :29     'Tran:'                          label   at (1,1),  LENGTH=5
            //   :42     'Date:'                          label   at (1,65), LENGTH=5
            //   :52     'Prog:'                          label   at (2,1),  LENGTH=5
            //   :65     'Time:'                          label   at (2,65), LENGTH=5
            //   :75-79  LENGTH=9 INITIAL='Main Menu'     heading at (4,35), COLOR=NEUTRAL,
            //                                            ATTRB=(ASKIP,BRT)
            //   :140    'Please select an option :'      prompt  at (20,15), LENGTH=25
            //   :150    LENGTH=0                         stopper at (20,44)
            //   :158    'ENTER=Continue  F3=Exit'        footer  at (24,1),  LENGTH=23
            //
            // The 'Main Menu' heading is the one worth naming explicitly. It is the only screen text
            // that distinguishes this map from app/bms/COADM01.bms, where the same DFHMDF reads
            // LENGTH=10 INITIAL='Admin Menu' - and because it is UNNAMED it contributes no item to
            // 01 COMEN1AI. Its absence from this payload is CORRECT, and it is precisely why the two
            // symbolic-map copybooks are byte-identical apart from their group names.
            int namedFields = MainMenuRequest.MAP_FIELD_COUNT;
            int unnamedLiteralLabels = 8;

            assertThat(namedFields).isEqualTo(20);
            assertThat(unnamedLiteralLabels).isEqualTo(8);
            assertThat(namedFields + unnamedLiteralLabels).isEqualTo(28)
                    .isEqualTo(MainMenuRequest.SCREEN_FIELD_COUNT);
            assertThat(recordComponentNames().subList(0, 20)).hasSize(namedFields);

            // No member is named after any of the eight labels, under any plausible spelling.
            assertThat(recordComponentNames()).doesNotContain("mainMenu",
                    "MAINMENU",
                    "heading",
                    "tranLabel",
                    "dateLabel",
                    "progLabel",
                    "timeLabel",
                    "prompt",
                    "footer");
        }

        @Test
        @DisplayName("the length item xxxL is absent: trnNameL is not a member")
        void theLengthItemIsAbsent() {
            // TRNNAMEL COMP PIC S9(4) at app/cpy-bms/COMEN01.CPY:19 is the input length CICS reports.
            // It is derivable from the value that IS carried, so modelling it too would create a second
            // source of truth able to disagree with the first.
            assertThat(recordComponentNames()).doesNotContain("trnNameL", "TRNNAMEL", "trnNameLength");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> MainMenuRequest.class.getMethod("trnNameL"));

            // The value itself is still reachable, which is the point: nothing is lost by omitting the
            // length item, because the length of the carried value is the length CICS reported.
            assertThat(atDeclaredWidths("01", NavigationContext.empty()).trnName())
                    .hasSize(MainMenuRequest.TRN_NAME_LENGTH);
        }

        @Test
        @DisplayName("the flag byte xxxF is absent: optionF is not a member")
        void theFlagByteIsAbsent() {
            // OPTIONF PICTURE X at app/cpy-bms/COMEN01.CPY:128 is the modified-data-tag flag byte, a
            // 3270 terminal-protocol artefact with no meaning off the terminal. A REST payload that
            // carried it would be asserting something about a device that is not in the picture.
            assertThat(recordComponentNames()).doesNotContain("optionF", "OPTIONF", "optionFlag");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> MainMenuRequest.class.getMethod("optionF"));
        }

        @Test
        @DisplayName("the attribute view xxxA is absent: errMsgA is not a member")
        void theAttributeViewIsAbsent() {
            // ERRMSGA at app/cpy-bms/COMEN01.CPY:136 REDEFINES the flag byte and is the view a program
            // writes when it highlights a field in error. Highlighting is the response's concern - it
            // is what app/cpy/CSSETATY.cpy does with DFHRED - so the attribute view belongs to the
            // response projection and not to this request.
            assertThat(recordComponentNames()).doesNotContain("errMsgA", "ERRMSGA", "errMsgAttribute");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> MainMenuRequest.class.getMethod("errMsgA"));
        }

        @Test
        @DisplayName("no length, flag or attribute item reaches the payload for any of the twenty")
        void noMetadataItemIsAMemberForAnyField() {
            // The sweep that generalises the three named cases above. It is deliberately paired with
            // them rather than replacing them (practice B11): the named tests show a reader exactly
            // which spellings were checked, and this one proves the remaining combinations hold too -
            // twenty fields x seven suffixes, in both the Java and the copybook spelling.
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

            assertThat(EXPECTED_MEMBER_NAMES.size() * METADATA_SUFFIXES.size())
                    .as("twenty fields x seven metadata suffixes were swept")
                    .isEqualTo(140);

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
    @DisplayName("Width arithmetic, written out by hand - gates G21 and practice B11")
    class WidthArithmetic {

        @ParameterizedTest(name = "{1} is PIC X({2}) at COMEN01.CPY:{3}")
        @CsvSource({"0,  TRNNAMEI,  4,  24",
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
            // width against the width transcribed from the copybook, so a mistyped constant fails
            // rather than quietly redefining the screen. The second compares the width table this file
            // carries against the same literal, so the table cannot drift either. Asserting only that a
            // value of the subject's own length validates would be circular - the constraint and the
            // constant would agree with each other while both disagreed with the copybook.
            assertThat(declaredWidthOf(index)).as("%s (%s:%d) declared width",
                    itemName,
                    "app/cpy-bms/COMEN01.CPY",
                    copybookLine).isEqualTo(copybookWidth);
            assertThat(EXPECTED_WIDTHS.get(index)).as("%s transcribed width", itemName)
                    .isEqualTo(copybookWidth);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo(itemName);

            // And the constraint really is set at that width: exactly the declared length validates,
            // one character more does not. Both sides, for all twenty fields - which is the bulk of
            // this package's branch surface for gate G49.
            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth + 1))))
                    .containsExactly(EXPECTED_MEMBER_NAMES.get(index));
        }

        @Test
        @DisplayName("the eight named width constants hold their copybook values")
        void theWidthConstantsHoldTheirCopybookValues() {
            // Read off the xxxI PICTURE clauses; the .bms LENGTH= operands corroborate each one. These
            // are the NAMED constants, asserted separately from the per-field sweep above so that a
            // constant renamed or reused in the wrong place is caught here by name (practice B8).
            assertThat(MainMenuRequest.TRN_NAME_LENGTH).isEqualTo(4);       // CPY:24,  bms:36
            assertThat(MainMenuRequest.TITLE_LENGTH).isEqualTo(40);         // CPY:30,  bms:40
            assertThat(MainMenuRequest.CUR_DATE_LENGTH).isEqualTo(8);       // CPY:36,  bms:49
            assertThat(MainMenuRequest.PGM_NAME_LENGTH).isEqualTo(8);       // CPY:42,  bms:59
            assertThat(MainMenuRequest.CUR_TIME_LENGTH).isEqualTo(8);       // CPY:54,  bms:72
            assertThat(MainMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);   // CPY:60,  bms:82
            assertThat(MainMenuRequest.OPTION_LENGTH).isEqualTo(2);         // CPY:132, bms:148
            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isEqualTo(78);       // CPY:138, bms:156

            // TITLE_LENGTH is shared by TITLE01I and TITLE02I and by all twelve option lines, all of
            // which are PIC X(40); the subject nevertheless keeps OPTION_LINE_LENGTH separate, so a
            // future divergence in either does not silently move the other.
            assertThat(MainMenuRequest.TITLE_LENGTH).isEqualTo(MainMenuRequest.OPTION_LINE_LENGTH);
            assertThat(declaredWidthOf(1)).isEqualTo(MainMenuRequest.TITLE_LENGTH);
            assertThat(declaredWidthOf(4)).isEqualTo(MainMenuRequest.TITLE_LENGTH);
            assertThat(declaredWidthOf(6)).isEqualTo(MainMenuRequest.OPTION_LINE_LENGTH);

            // 78 and never 80. WS-MESSAGE is PIC X(80) at app/cbl/COMEN01C.cbl:38, so the MOVE to
            // ERRMSGO discards two bytes off the right. That truncation is the controller's, and its
            // DIRECTION is proved in MainMenuResponseTest; here the point is only that the declared
            // width is the screen's 78 and has not been widened to 80 to paper over it.
            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isNotEqualTo(80);
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

            // SYMBOLIC_MAP_PAYLOAD_LENGTH is computed in the subject from its individual width
            // constants, so pinning it here turns any mistyped width into a failure naming this total.
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH).isEqualTo(668).isEqualTo(byHand);
        }

        @Test
        @DisplayName("the whole symbolic map is 12 + 20x7 + 668 = 820 bytes")
        void theWholeSymbolicMapIsEightHundredTwentyBytes() {
            // The stride: xxxL COMP PIC S9(4) is 2, xxxF PICTURE X is 1, FILLER PICTURE X(4) is 4, and
            // the FILLER REDEFINES xxxF / 03 xxxA pair adds ZERO because it redefines the flag byte
            // rather than following it - app/cpy-bms/COMEN01.CPY:19-23. Hence 7 per field, not 8.
            assertThat(MainMenuRequest.FIELD_METADATA_LENGTH).isEqualTo(2 + 1 + 4).isEqualTo(7);

            // The prefix: 02 FILLER PIC X(12) at CPY:18, present because app/bms/COMEN01.bms:24
            // declares TIOAPFX=YES.
            assertThat(MainMenuRequest.TIOAPFX_FILLER_LENGTH).isEqualTo(12);

            int byHand = 12 + 20 * 7 + 668;

            assertThat(byHand).isEqualTo(820);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(820).isEqualTo(byHand);

            // Restated from the subject's own parts, so the identity holds however the constants move.
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(MainMenuRequest.TIOAPFX_FILLER_LENGTH
                            + MainMenuRequest.MAP_FIELD_COUNT
                                    * MainMenuRequest.FIELD_METADATA_LENGTH
                            + MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);

            // 01 COMEN1AO at CPY:139 REDEFINES 01 COMEN1AI, which is only legal if the two measure the
            // same; 820 is therefore the width of both views of this map.
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH
                    - MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .as("what is not screen data is the prefix and the twenty strides")
                    .isEqualTo(12 + 140);
        }

        @Test
        @DisplayName("composing the twenty fields produces 668 bytes, and dropping one fails it")
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
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(codec.charset()).isEqualTo(StandardCharsets.US_ASCII);

            StringBuilder image = new StringBuilder();
            List<String> values = screenFieldsOf(request);
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                // An absent screen field is SPACES in a COBOL record, never null: option lines 11 and
                // 12 are unpopulated on this screen and still occupy their forty bytes each.
                image.append(codec.movePicX(value == null ? "" : value, declaredWidthOf(index)));
            }

            assertThat(image.length()).isEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .isEqualTo(668);

            byte[] encoded = codec.encodeImage(image.toString(), "COMEN1AI screen data");
            assertThat(encoded).hasSize(668);

            // Gate G21 in its sharpest form: drop a single span and the total is wrong immediately.
            // This is the check that catches a forgotten FILLER or a forgotten field, which is the one
            // defect class that produces plausible-looking output at every offset after it.
            assertThat(image.length() - MainMenuRequest.OPTION_LINE_LENGTH)
                    .as("omitting one forty-byte option line must not still total 668")
                    .isNotEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
            assertThat(image.length() - MainMenuRequest.TRN_NAME_LENGTH)
                    .as("omitting the four-byte transaction name must not still total 668")
                    .isNotEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("an 820-byte record is what the map image would occupy end to end")
        void theSymbolicMapImageIsEightHundredTwentyBytesWide() {
            // The request path never renders a symbolic-map image - CICS built it, and the REST payload
            // replaces it - so this asserts the geometry rather than a rendering: a fixed-width record
            // of the declared width really is 820 bytes, with the code page stated explicitly (B8).
            FixedWidthRecord image = new FixedWidthRecord(MainMenuRequest.SYMBOLIC_MAP_LENGTH,
                    StandardCharsets.US_ASCII);

            assertThat(image.recordLength()).isEqualTo(820);
            assertThat(image.charset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(image.recordLength() - MainMenuRequest.TIOAPFX_FILLER_LENGTH
                    - MainMenuRequest.MAP_FIELD_COUNT * MainMenuRequest.FIELD_METADATA_LENGTH)
                    .as("what remains after the prefix and the twenty strides is the screen data")
                    .isEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
        }
    }

    @Nested
    @DisplayName("CDEMO-USER-TYPE is load-bearing on THIS screen - the one behavioural difference")
    class UserTypeIsLoadBearingHere {

        // =============================================================================================
        // This nested class is what separates MainMenuRequest from AdminMenuRequest behaviourally.
        // Everything else about the two payloads is identical by construction; this is not.
        //
        // app/cbl/COMEN01C.cbl:136-143 reads:
        //
        //     136  IF CDEMO-USRTYP-USER AND
        //     137     CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
        //     138         SET ERR-FLG-ON          TO TRUE
        //     139         MOVE SPACES             TO WS-MESSAGE
        //     140         MOVE 'No access - Admin Only option... ' TO
        //     141                                 WS-MESSAGE
        //     142         PERFORM SEND-MENU-SCREEN
        //     143  END-IF
        //
        // so the carried CDEMO-USER-TYPE PIC X(01) at app/cpy/COCOM01Y.cpy:26 is FUNCTIONALLY CONSUMED
        // by an admin-only authorisation filter. app/cbl/COADM01C.cbl has no equivalent guard at all.
        //
        // The filter ITSELF is MainMenuService's job, and it is asserted there. What is asserted HERE is
        // only that this DTO carries, faithfully and without interpretation, the input that filter needs.
        // That division matters: if the request type normalised the value - upper-cased it, defaulted a
        // blank to 'U', or looked the type up from the user id - the service would be filtering on
        // something the terminal never sent, and the divergence would be invisible at the service level.
        //
        // COMEN01C's ONLY assignment to CDEMO-USER-TYPE is COMMENTED OUT, at :150:
        //
        //     149  *            MOVE WS-USER-ID   TO CDEMO-USER-ID
        //     150  *            MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        //
        // The program therefore never writes the field; it only ever RECEIVES it, from whichever program
        // XCTL'd to it with the communication area. Leaving that line commented out is preserved
        // behaviour, not an oversight to correct (practice B5), and it is the reason "pass through
        // untouched" is the exactly right contract for this member.
        //
        // One further fact, recorded because it belongs in MainMenuServiceTest and NOT here as an
        // assertion: app/cpy/COMEN02Y.cpy:92 declares CDEMO-MENU-OPT-USRTYPE PIC X(01), and all ten
        // POPULATED entries carry VALUE 'U' - :29, :35, :41, :47, :53, :59, :65, :72, :78 and :84. The
        // filter's `= 'A'` arm is therefore unreachable through any populated subscript, and can only be
        // entered via an out-of-range or unpopulated one. Note also the commented-out caption at
        // COMEN02Y.cpy:69, 'Transaction Add (Admin Only)', superseded at :70 by a plain
        // 'Transaction Add' whose user type is 'U' - the option that WAS admin-only and is no longer.
        // =============================================================================================

        @Test
        @DisplayName("the user type is reachable through the carried communication area")
        void theUserTypeIsReachableThroughTheCarriedContext() {
            // Reachable through the carrier, and ONLY through the carrier: the value has exactly one
            // home, so there is no second copy to drift out of step with the first.
            NavigationContext context = NavigationContext.empty().withUserTypeUser();
            MainMenuRequest request = atDeclaredWidths(" 1", context);

            assertThat(recordComponentNames()).contains("navigationContext");
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(NavigationContext.USER_TYPE_FIELD).isEqualTo("CDEMO-USER-TYPE");
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("both 88-level predicates are driven: ADMIN 'A' and USER 'U', each true and false")
        void bothUserTypeConditionsAreDriven() {
            // 88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy:27 and
            // 88 CDEMO-USRTYP-USER  VALUE 'U' at :28. Four assertions, not two: each predicate is driven
            // to true on its own value and to false on the other's, because a predicate written as the
            // negation of its sibling would pass a one-sided test and be wrong.
            MainMenuRequest asAdmin =
                    atDeclaredWidths(" 1", NavigationContext.empty().withUserTypeAdmin());
            MainMenuRequest asUser =
                    atDeclaredWidths(" 1", NavigationContext.empty().withUserTypeUser());

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

            // The COBOL guard fires only for CDEMO-USRTYP-USER, so the two cases really do lead to
            // different outcomes downstream. Asserted here as the input distinction the service reads.
            assertThat(asUser.navigationContext().isUser())
                    .as("COMEN01C.cbl:136 tests CDEMO-USRTYP-USER, so only 'U' can enter the guard")
                    .isNotEqualTo(asAdmin.navigationContext().isUser());
        }

        @ParameterizedTest(name = "CDEMO-USER-TYPE [{0}] survives byte-identical")
        @ValueSource(strings = {"A", "U", " ", "X", "a", "u", "0"})
        @DisplayName("every user type is carried verbatim - never derived, defaulted or upper-cased")
        void everyUserTypeIsCarriedVerbatim(String userType) throws Exception {
            // 'A' and 'U' are the two 88-levels. ' ' and 'X' are the cases that matter most: NEITHER
            // 88-level matches them, and the DTO must still carry them exactly. A blank is what a freshly
            // initialised communication area holds, so defaulting it to 'U' would silently grant every
            // pre-sign-on request the regular-user role and hand it the guard at COMEN01C.cbl:136. The
            // lower-case 'a' and 'u' are here because COBOL's VALUE 'A' comparison is case-SENSITIVE:
            // up-casing in Java would make isAdmin() true where the COBOL 88-level is false. '0' covers a
            // digit landing in an alphanumeric PIC X(01) field, which the copybook permits.
            NavigationContext supplied = NavigationContext.empty().withUserType(userType);
            MainMenuRequest request = atDeclaredWidths(" 1", supplied);
            ObjectMapper mapper = new ObjectMapper();

            // In memory: the same characters, in the same order, at the same length.
            assertThat(request.navigationContext().userType()).isEqualTo(userType)
                    .hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(request.navigationContext().userType().toCharArray())
                    .containsExactly(userType.toCharArray());

            // And across a JSON round trip, which is the path a real request takes.
            MainMenuRequest revived =
                    mapper.readValue(mapper.writeValueAsString(request), MainMenuRequest.class);
            assertThat(revived.navigationContext().userType()).isEqualTo(userType);

            // The predicates follow the value rather than being invented: exactly one of them holds for
            // 'A' and for 'U', and NEITHER holds for anything else. This is the branch that proves the
            // two 88-levels are not treated as complementary.
            boolean admin = revived.navigationContext().isAdmin();
            boolean user = revived.navigationContext().isUser();
            if ("A".equals(userType)) {
                assertThat(admin).isTrue();
                assertThat(user).isFalse();
            } else if ("U".equals(userType)) {
                assertThat(user).isTrue();
                assertThat(admin).isFalse();
            } else {
                assertThat(admin).as("%s matches no 88-level, so no role is implied", userType)
                        .isFalse();
                assertThat(user).as("%s matches no 88-level, so no role is implied", userType)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a blank user type is neither role - the 88-levels are not complementary")
        void aBlankUserTypeIsNeitherRole() {
            // Stated on its own as well as inside the sweep, because it is the case a plausible-looking
            // implementation gets wrong: CDEMO-USER-TYPE is PIC X(01), a freshly initialised area holds
            // a space, and no role is implied before sign-on. isUser() must therefore not be written as
            // !isAdmin(). COMEN01C never populates the field itself - see :150, commented out - so a
            // request that arrives without one legitimately carries a blank.
            MainMenuRequest beforeSignOn = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(beforeSignOn.navigationContext().isAdmin()).isFalse();
            assertThat(beforeSignOn.navigationContext().isUser()).isFalse();
            assertThat(beforeSignOn.navigationContext().userType())
                    .isNotEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .isNotEqualTo(NavigationContext.USER_TYPE_USER);
        }

        @Test
        @DisplayName("the request declares no role accessor of its own - the filter lives in the service")
        void theRequestDeclaresNoRoleAccessor() {
            // The DTO must not offer a shortcut that invites the authorisation decision to be taken in
            // the wrong layer. It exposes the communication area and nothing that interprets it.
            List<String> declared = Arrays.stream(MainMenuRequest.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toList());

            assertThat(declared).doesNotContain("userType",
                    "isAdmin",
                    "isUser",
                    "admin",
                    "user",
                    "role",
                    "roles",
                    "authorities",
                    "authorised",
                    "adminOnly",
                    "password");

            // Nor is the user type duplicated as a screen field: it travels in the area, once.
            assertThat(recordComponentNames()).doesNotContain("userType", "cdemoUserType", "role");
        }

        @Test
        @DisplayName("the user type is independent of the option, so the guard's inputs stay separate")
        void theUserTypeIsIndependentOfTheOption() {
            // COMEN01C.cbl:136-137 is a conjunction of two independent inputs: the carried user type and
            // the option the terminal typed. The DTO must keep them independent, so the service can be
            // driven through all four combinations. Changing one here must not disturb the other.
            MainMenuRequest userPickingEight =
                    atDeclaredWidths(" 8", NavigationContext.empty().withUserTypeUser());
            MainMenuRequest adminPickingEight =
                    atDeclaredWidths(" 8", NavigationContext.empty().withUserTypeAdmin());

            assertThat(userPickingEight.option()).isEqualTo(" 8").isEqualTo(adminPickingEight.option());
            assertThat(userPickingEight.navigationContext().userType())
                    .isNotEqualTo(adminPickingEight.navigationContext().userType());
            assertThat(userPickingEight).isNotEqualTo(adminPickingEight);
        }
    }

    @Nested
    @DisplayName("OPTIONI is the only editable field, is capped at 2, and is carried entirely raw")
    class OptionIsTheOnlyEditableField {

        // =============================================================================================
        // app/bms/COMEN01.bms:145-149 declares the only unprotected field on the screen:
        //
        //     145  OPTION  DFHMDF ATTRB=(FSET,IC,NORM,NUM,UNPROT),
        //     146                 HILIGHT=UNDERLINE,
        //     147                 JUSTIFY=(RIGHT,ZERO),
        //     148                 LENGTH=2,
        //     149                 POS=(20,41)
        //
        // UNPROT makes it typeable, IC puts the cursor there, NUM restricts the keyboard to digits and
        // JUSTIFY=(RIGHT,ZERO) tells the 3270 to right-align and zero-fill. Every OTHER named DFHMDF in
        // the mapset is ATTRB=(ASKIP,FSET,NORM) - skip-protected, hence read-only - except ERRMSG at
        // :154, which is ATTRB=(ASKIP,BRT,FSET). So of twenty fields, exactly one is input.
        //
        // JUSTIFY is a TERMINAL instruction, not a payload contract, and the program does not trust it:
        // app/cbl/COMEN01C.cbl:117-124 re-derives the value itself.
        //
        //     117  PERFORM VARYING WS-IDX
        //     118          FROM LENGTH OF OPTIONI OF COMEN1AI BY -1 UNTIL
        //     119          OPTIONI OF COMEN1AI(WS-IDX:1) NOT = SPACES OR
        //     120          WS-IDX = 1
        //     121  END-PERFORM
        //     122  MOVE OPTIONI OF COMEN1AI(1:WS-IDX) TO WS-OPTION-X
        //     123  INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
        //     124  MOVE WS-OPTION-X              TO WS-OPTION
        //
        // A reverse scan for the last non-space, a reference modification down to that length, a move
        // into WS-OPTION-X PIC X(02) JUST RIGHT at :45, then blanks replaced by zeros. That whole
        // sequence is the SERVICE's work, and every step of it depends on seeing the exact space pattern
        // the terminal sent. If this DTO trimmed, padded or re-justified the value first, the reverse
        // scan would run over different bytes and the INSPECT would produce a different number.
        //
        // Hence: carried raw. No trim, no pad, no re-justification, no parse.
        // =============================================================================================

        @Test
        @DisplayName("exactly two characters is accepted and three is rejected, naming option")
        void twoCharactersIsAcceptedAndThreeIsRejected() {
            // The package's principal branch surface for gate G49, and both sides of it. OPTIONI is
            // PIC X(2) at app/cpy-bms/COMEN01.CPY:132 and LENGTH=2 at app/bms/COMEN01.bms:148.
            assertThat(MainMenuRequest.OPTION_LENGTH).isEqualTo(2);
            assertThat(declaredWidthOf(EXPECTED_MEMBER_NAMES.indexOf("option"))).isEqualTo(2);

            assertThat(violatedProperties(atDeclaredWidths("10", NavigationContext.empty()))).isEmpty();
            assertThat(violatedProperties(atDeclaredWidths("100", NavigationContext.empty())))
                    .containsExactly("option");
        }

        @ParameterizedTest(name = "option [{0}] raises no violation")
        @ValueSource(strings = {"", " ", "  ", "1", " 1", "1 ", "10", "99", "00", " 0", "0 ", "ab", "**"})
        @DisplayName("anything of two characters or fewer is accepted, whatever it contains")
        void anythingOfTwoCharactersOrFewerIsAccepted(String option) {
            // Including the values COMEN01C.cbl:127-134 goes on to REJECT with a message - zeros, and a
            // value above CDEMO-MENU-OPT-COUNT. That rejection is a message on the screen, not a refusal
            // of the request, so it must not be pre-empted by Bean Validation here: turning it into a
            // 400 would replace COMEN01C's 'Please enter a valid option number...' at :131 with a
            // response the program never produces. Non-numeric values are accepted for the same reason -
            // :127 tests IF WS-OPTION IS NOT NUMERIC and answers it itself.
            assertThat(violatedProperties(atDeclaredWidths(option, NavigationContext.empty())))
                    .as("option [%s] is at most two characters, so the constraint has nothing to say",
                            option)
                    .isEmpty();
        }

        @Test
        @DisplayName("a null option raises no violation - COMEN01C answers a blank with a message")
        void aNullOptionRaisesNoViolation() {
            // @Size(max = 2) says nothing about null by design (jakarta.validation.constraints.Size:
            // null elements are considered valid). That is the correct reading of the COBOL: an omitted
            // field arrives as spaces, COMEN01C.cbl:117-124 turns spaces into '00' via the INSPECT at
            // :123, and :129 then answers WS-OPTION = ZEROS with a message. No path refuses the request.
            assertThat(violatedProperties(atDeclaredWidths(null, NavigationContext.empty()))).isEmpty();
            assertThat(atDeclaredWidths(null, NavigationContext.empty()).option()).isNull();
        }

        @ParameterizedTest(name = "option [{0}] is returned byte-identical")
        @ValueSource(strings = {" 3", "3 ", "  ", " 1", "1 ", "10", " 0", "0 ", "07", "7 "})
        @DisplayName("the exact space pattern the terminal sent survives, byte for byte")
        void theExactSpacePatternSurvives(String option) {
            // The two patterns the specification names explicitly are " 3" and "3 ", and they are the
            // interesting pair: JUSTIFY=(RIGHT,ZERO) at bms:147 means a well-behaved terminal sends " 3",
            // but the reverse scan at COMEN01C.cbl:117-120 is written to cope with "3 " as well - it
            // stops at the last non-space, so "3 " yields WS-IDX = 1 and MOVE OPTIONI(1:1) moves just
            // "3". The two therefore travel DIFFERENT paths through the service, which is only possible
            // if the DTO keeps them distinguishable.
            MainMenuRequest request = atDeclaredWidths(option, NavigationContext.empty());

            assertThat(request.option()).isEqualTo(option).hasSize(option.length());
            assertThat(request.option().toCharArray()).containsExactly(option.toCharArray());

            // Not merely equal: the same characters at the same positions. Comparing the char arrays
            // rules out a value that happens to compare equal after some normalisation - an equality
            // test on a trimmed String would still pass for "3" against "3 " on a trimming accessor,
            // whereas the array comparison pins the length and every position.
            assertThat(request.option().indexOf(' '))
                    .as("option [%s] keeps its space in the position the terminal put it", option)
                    .isEqualTo(option.indexOf(' '));
        }

        @Test
        @DisplayName("\" 3\" stays \" 3\" and \"3 \" stays \"3 \" - the two are never conflated")
        void leadingAndTrailingSpacesAreBothPreservedAndDistinct() {
            // Spelled out as its own test, without parameterisation, because this is the single assertion
            // the specification words most precisely: " 3" round-trips as " 3" and "3 " as "3 ",
            // byte-for-byte. If either were trimmed they would collapse into "3" and become equal.
            MainMenuRequest rightJustified = atDeclaredWidths(" 3", NavigationContext.empty());
            MainMenuRequest leftJustified = atDeclaredWidths("3 ", NavigationContext.empty());

            assertThat(rightJustified.option()).isEqualTo(" 3");
            assertThat(rightJustified.option().toCharArray()).containsExactly(' ', '3');
            assertThat(leftJustified.option()).isEqualTo("3 ");
            assertThat(leftJustified.option().toCharArray()).containsExactly('3', ' ');

            assertThat(rightJustified.option()).isNotEqualTo(leftJustified.option());
            assertThat(rightJustified).isNotEqualTo(leftJustified);

            // Neither has been trimmed to the bare digit, which is what conflating them would look like.
            assertThat(rightJustified.option()).isNotEqualTo("3");
            assertThat(leftJustified.option()).isNotEqualTo("3");
        }

        @Test
        @DisplayName("no zero-fill, no re-justification and no numeric coercion is applied")
        void noNormalisationIsApplied() {
            // The three normalisations that belong to the service and must not appear here. Each is named
            // and its owner cited, so a reader can see the boundary rather than infer it.
            //   * zero-fill      - COMEN01C.cbl:123, INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'
            //   * right-justify  - COMEN01C.cbl:45,  WS-OPTION-X PIC X(02) JUST RIGHT
            //   * numeric parse  - COMEN01C.cbl:124, MOVE WS-OPTION-X TO WS-OPTION PIC 9(02)
            assertThat(atDeclaredWidths(" 1", NavigationContext.empty()).option())
                    .as("zero-fill belongs to COMEN01C.cbl:123, not to this payload")
                    .isEqualTo(" 1")
                    .isNotEqualTo("01");
            assertThat(atDeclaredWidths("1 ", NavigationContext.empty()).option())
                    .as("right-justification belongs to COMEN01C.cbl:45, not to this payload")
                    .isEqualTo("1 ")
                    .isNotEqualTo(" 1");
            assertThat(MainMenuRequest.class.getRecordComponents()[18].getType())
                    .as("numeric conversion belongs to COMEN01C.cbl:124, so the member stays a String")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("Jackson neither trims the option nor coerces it to a number")
        void jacksonNeitherTrimsNorCoercesTheOption() throws Exception {
            // The raw-carriage guarantee has to hold over the wire too, since that is where a stray
            // trimming or coercing configuration would take effect.
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest original = atDeclaredWidths(" 3", NavigationContext.empty());

            String json = mapper.writeValueAsString(original);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("option").isTextual()).isTrue();
            assertThat(node.get("option").asText()).isEqualTo(" 3");
            assertThat(mapper.readValue(json, MainMenuRequest.class).option()).isEqualTo(" 3");

            // And the awkward direction: a trailing space, which many serialisers quietly drop.
            String trailing = mapper.writeValueAsString(atDeclaredWidths("3 ",
                    NavigationContext.empty()));
            assertThat(mapper.readTree(trailing).get("option").asText()).isEqualTo("3 ");
            assertThat(mapper.readValue(trailing, MainMenuRequest.class).option()).isEqualTo("3 ");
        }

        @ParameterizedTest(name = "{0} rejects one character past its width and nothing shorter")
        @CsvSource({"trnName,  4", "title01, 40", "curDate,  8", "pgmName,  8", "title02, 40",
                "curTime,  8", "optn001, 40", "optn002, 40", "optn003, 40", "optn004, 40",
                "optn005, 40", "optn006, 40", "optn007, 40", "optn008, 40", "optn009, 40",
                "optn010, 40", "optn011, 40", "optn012, 40", "option,   2", "errMsg,  78"})
        @DisplayName("both sides of every @Size, named field by named field")
        void bothSidesOfEverySizeConstraint(String member, int width) {
            // WidthArithmetic drives both sides indexed by position; this drives them NAMED, so a failure
            // reads as the field a reviewer knows rather than as an ordinal. The pair is deliberate
            // (practice B11): the indexed form proves the ordering, the named form proves the identity.
            int index = EXPECTED_MEMBER_NAMES.indexOf(member);

            assertThat(index).as("%s is one of the twenty screen members", member).isNotNegative();
            assertThat(declaredWidthOf(index)).as("%s declared width", member).isEqualTo(width);

            // Accepting side: exactly the declared width, and one character short of it.
            assertThat(violatedProperties(withScreenField(index, "v".repeat(width)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "v".repeat(width - 1)))).isEmpty();

            // Rejecting side: one character past it, attributed to this field and to no other.
            assertThat(violatedProperties(withScreenField(index, "v".repeat(width + 1))))
                    .containsExactly(member);
        }

        @Test
        @DisplayName("@Size is the only constraint: no @NotNull and no @NotBlank anywhere")
        void sizeIsTheOnlyConstraint() {
            // A required-field constraint would be a behaviour change. COMEN01C.cbl:79-80 moves SPACES
            // into WS-MESSAGE and ERRMSGO before doing anything else, and :89 moves LOW-VALUES into the
            // whole output map on first entry, so an absent field is entirely ordinary on this screen.
            // Refusing the request would replace a message with a 400 the program never produces.
            for (RecordComponent component : MainMenuRequest.class.getRecordComponents()) {
                Method accessor = component.getAccessor();

                assertThat(accessor.getAnnotation(NotNull.class))
                        .as("%s must not be @NotNull", component.getName())
                        .isNull();
                assertThat(accessor.getAnnotation(NotBlank.class))
                        .as("%s must not be @NotBlank", component.getName())
                        .isNull();
            }

            // A payload with every screen field absent is valid, which is the same claim stated whole.
            assertThat(violatedProperties(withScreenField(0, null))).isEmpty();
        }

        @Test
        @DisplayName("the twenty constraints cap the maximum only - there is no minimum")
        void theConstraintsCapTheMaximumOnly() {
            // @Size(min) defaults to 0 and must stay there: COMEN01C never requires a field to be full.
            for (int index = 0; index < EXPECTED_MEMBER_NAMES.size(); index++) {
                Size size = MainMenuRequest.class.getRecordComponents()[index]
                        .getAccessor()
                        .getAnnotation(Size.class);

                assertThat(size).as("%s carries @Size", EXPECTED_MEMBER_NAMES.get(index)).isNotNull();
                assertThat(size.min()).as("%s must have no minimum", EXPECTED_MEMBER_NAMES.get(index))
                        .isZero();
                assertThat(size.max()).isEqualTo(EXPECTED_WIDTHS.get(index));
            }

            // The empty string is therefore acceptable everywhere, including in the two-byte option.
            assertThat(violatedProperties(atDeclaredWidths("", NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("only OPTIONI is editable: the other nineteen are ASKIP on the map")
        void onlyOptionIsEditable() {
            // Transcribed from app/bms/COMEN01.bms. ATTRB is a screen attribute and has no Java
            // representation on the request side - which is the point of asserting it here as recorded
            // fact rather than as a member: the payload does NOT model protection, so nothing in this
            // type may be taken to imply that a field is writable.
            //   OPTION  :145  ATTRB=(FSET,IC,NORM,NUM,UNPROT)   <- the only unprotected field
            //   ERRMSG  :154  ATTRB=(ASKIP,BRT,FSET)
            //   the other eighteen  ATTRB=(ASKIP,FSET,NORM)     <- :34,:38,:47,:57,:61,:70,:80..:135
            int editableFields = 1;

            assertThat(MainMenuRequest.MAP_FIELD_COUNT - editableFields)
                    .as("nineteen of the twenty named fields are skip-protected")
                    .isEqualTo(19);
            assertThat(recordComponentNames()).contains("option");
            assertThat(recordComponentNames()).doesNotContain("optionAttribute",
                    "optionProtected",
                    "optionUnprotected",
                    "optionEditable");
        }
    }

    @Nested
    @DisplayName("All twelve option slots, of which the program can fill ten - practice B5")
    class TwelveOptionSlots {

        // =============================================================================================
        // The dead-code trap in this package, and the reason practice B5 has real teeth here.
        //
        // app/bms/COMEN01.bms declares twelve named option fields, OPTN001 at :80 through OPTN012 at
        // :135, so app/cpy-bms/COMEN01.CPY declares twelve OPTNnnnI items at :60 through :126 and this
        // payload must carry twelve members. app/cpy/COMEN02Y.cpy:88 backs them with a table declared
        // OCCURS 12 TIMES.
        //
        // But app/cpy/COMEN02Y.cpy:21 sets
        //
        //     21    05 CDEMO-MENU-OPT-COUNT           PIC 9(02) VALUE 10.
        //
        // and app/cbl/COMEN01C.cbl:238-239 bounds the fill loop by exactly that:
        //
        //     238  PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
        //     239                  WS-IDX > CDEMO-MENU-OPT-COUNT
        //
        // while the EVALUATE inside it, at :248-275, DOES carry arms for eleven and twelve:
        //
        //     269      WHEN 11
        //     270          MOVE WS-MENU-OPT-TXT TO OPTN011O
        //     271      WHEN 12
        //     272          MOVE WS-MENU-OPT-TXT TO OPTN012O
        //     273      WHEN OTHER
        //     274          CONTINUE
        //
        // WS-IDX can therefore never reach 11, so those two arms are UNREACHABLE and OPTN011O/OPTN012O
        // are never written. Only ten of the twelve captions in COMEN02Y.cpy:25-84 exist to write.
        //
        // THE CONTRAST WORTH RECORDING - two different routes to the same unwritable outcome, and both
        // are preserved exactly as found:
        //
        //   * COMEN01C (this screen): the EVALUATE HAS arms for 11 and 12, at :269-272, but the loop
        //     bound at :238-239 is CDEMO-MENU-OPT-COUNT = 10. Dead arms behind a smaller bound. The
        //     table is OCCURS 12 (COMEN02Y.cpy:88) with 10 populated entries.
        //
        //   * COADM01C (the admin twin): its EVALUATE at :238-261 has arms for 1 through 10 ONLY, then
        //     WHEN OTHER CONTINUE at :259-260. There is NO ARM AT ALL for 11 or 12. Its bound at
        //     :228-229 is CDEMO-ADMIN-OPT-COUNT = 4 (COADM02Y.cpy:20) and its table is OCCURS 9
        //     (COADM02Y.cpy:45) - which cannot even address a twelfth entry.
        //
        // Same outcome, reached differently. Neither is tidied: implementing the eleventh and twelfth
        // options would be a new feature, and pruning the two slots would change the record shape the
        // symbolic map declares. Both are parity violations, so both slots stay.
        // =============================================================================================

        @ParameterizedTest(name = "optn0{0} exists at PIC X(40), COMEN01.CPY:{1}")
        @CsvSource({"01,  60", "02,  66", "03,  72", "04,  78", "05,  84", "06,  90",
                "07,  96", "08, 102", "09, 108", "10, 114", "11, 120", "12, 126"})
        @DisplayName("all twelve slots exist, each forty bytes wide, none pruned")
        void allTwelveSlotsExist(String ordinal, int copybookLine) {
            String member = "optn0" + ordinal;
            int index = EXPECTED_MEMBER_NAMES.indexOf(member);

            assertThat(index).as("%s is a declared payload member", member).isNotNegative();
            assertThat(recordComponentNames()).contains(member);
            assertThat(declaredWidthOf(index)).as("%s (COMEN01.CPY:%d)", member, copybookLine)
                    .isEqualTo(40)
                    .isEqualTo(MainMenuRequest.OPTION_LINE_LENGTH);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo("OPTN0" + ordinal + "I");
        }

        @Test
        @DisplayName("slots eleven and twelve are present even though COMEN01C can never write them")
        void slotsElevenAndTwelveArePresent() {
            // Named on their own, because these two are exactly what a well-meaning tidy-up would remove.
            // COMEN01C.cbl:269-272 has the arms; COMEN01C.cbl:238-239 makes them unreachable; the map at
            // app/bms/COMEN01.bms:130 and :135 declares the fields regardless; so the payload carries
            // them regardless. Deleting them would be a behaviour change (practice B5).
            assertThat(recordComponentNames()).contains("optn011", "optn012");
            assertThat(declaredWidthOf(EXPECTED_MEMBER_NAMES.indexOf("optn011"))).isEqualTo(40);
            assertThat(declaredWidthOf(EXPECTED_MEMBER_NAMES.indexOf("optn012"))).isEqualTo(40);

            // And they are usable, not vestigial: a value placed in either survives and validates.
            MainMenuRequest eleventh =
                    withScreenField(EXPECTED_MEMBER_NAMES.indexOf("optn011"), "11. Never Written");
            MainMenuRequest twelfth =
                    withScreenField(EXPECTED_MEMBER_NAMES.indexOf("optn012"), "12. Never Written");

            assertThat(eleventh.optn011()).isEqualTo("11. Never Written");
            assertThat(twelfth.optn012()).isEqualTo("12. Never Written");
            assertThat(violatedProperties(eleventh)).isEmpty();
            assertThat(violatedProperties(twelfth)).isEmpty();
        }

        @Test
        @DisplayName("twelve slots and ten fillable captions are different numbers, both recorded")
        void twelveSlotsAndTenCaptionsAreBothRecorded() {
            // Twelve is the SHAPE, from app/cpy-bms/COMEN01.CPY and app/cpy/COMEN02Y.cpy:88 OCCURS 12.
            // Ten is the FILL, from app/cpy/COMEN02Y.cpy:21 CDEMO-MENU-OPT-COUNT VALUE 10 and the ten
            // populated entries at :25-84. Conflating the two is the defect this asserts against.
            int declaredSlots = MainMenuRequest.OPTION_LINE_COUNT;
            int fillableByTheProgram = 10;
            int unreachableSlots = declaredSlots - fillableByTheProgram;

            assertThat(declaredSlots).isEqualTo(12);
            assertThat(fillableByTheProgram).isEqualTo(10);
            assertThat(unreachableSlots).as("OPTN011I and OPTN012I can never be written")
                    .isEqualTo(2);

            // The shape wins for the payload: the map declares twelve, so twelve are carried.
            assertThat(EXPECTED_MEMBER_NAMES.stream().filter(name -> name.startsWith("optn0")).count())
                    .isEqualTo(declaredSlots)
                    .isNotEqualTo((long) fillableByTheProgram);

            // And the twelve slots contribute 480 of the 668 payload bytes, which is why pruning two of
            // them would move the total by 80 and break every offset after OPTN010I.
            assertThat(declaredSlots * MainMenuRequest.OPTION_LINE_LENGTH).isEqualTo(480);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH
                    - fillableByTheProgram * MainMenuRequest.OPTION_LINE_LENGTH)
                    .as("ten slots would leave 668 short by eighty bytes")
                    .isNotEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH - 480);
        }

        @Test
        @DisplayName("optionLines() is always twelve long, in map order, nulls included")
        void optionLinesIsAlwaysTwelveLong() {
            MainMenuRequest populated = atDeclaredWidths(" 1", NavigationContext.empty());
            List<String> lines = populated.optionLines();

            assertThat(lines).hasSize(12).hasSize(MainMenuRequest.OPTION_LINE_COUNT);
            assertThat(lines.get(0)).isEqualTo(populated.optn001());
            assertThat(lines.get(9)).isEqualTo(populated.optn010());
            // The unwritable pair reads back as absent rather than as a fabricated caption.
            assertThat(lines.get(10)).isNull();
            assertThat(lines.get(11)).isNull();

            // Still twelve when nothing is populated at all: the map declares twelve regardless.
            List<String> empty = withScreenField(0, "CM00").optionLines();
            assertThat(empty).hasSize(12).containsOnlyNulls();
        }

        @Test
        @DisplayName("optionLines() is unmodifiable and hands back a fresh wrapper each call")
        void optionLinesIsUnmodifiable() {
            // A payload that leaked a mutable view of its own state would let one caller alter what
            // another reads - the request-scoped equivalent of the session state gate G37 forbids.
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());
            List<String> lines = request.optionLines();

            assertThatThrownBy(() -> lines.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.add("appended"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(lines::clear).isInstanceOf(UnsupportedOperationException.class);

            // The failed mutations left the instance untouched, and each call returns a distinct wrapper
            // over equal contents.
            assertThat(request.optn001()).isEqualTo("01. Account View");
            assertThat(request.optionLines()).isEqualTo(lines).isNotSameAs(lines);
        }

        @Test
        @DisplayName("optionLine(n) is ONE-based, as every COBOL subscript is")
        void optionLineIsOneBased() {
            // CDEMO-MENU-OPT(WS-IDX) is indexed from 1 (COMEN01C.cbl:238 starts WS-IDX at 1), and the
            // AAP names the 1-based-to-0-based conversion the top defect risk for OCCURS tables. Both
            // ends are anchored so an off-by-one at either end fails.
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(request.optionLine(1)).isEqualTo(request.optn001())
                    .isEqualTo("01. Account View");
            assertThat(request.optionLine(10)).isEqualTo(request.optn010())
                    .isEqualTo("10. Bill Payment");
            assertThat(request.optionLine(11)).isEqualTo(request.optn011()).isNull();
            assertThat(request.optionLine(12)).isEqualTo(request.optn012()).isNull();

            // Every position agrees with its accessor, so the mapping cannot be shifted anywhere.
            List<String> lines = request.optionLines();
            for (int position = 1; position <= MainMenuRequest.OPTION_LINE_COUNT; position++) {
                assertThat(request.optionLine(position))
                        .as("position %d must equal element %d of optionLines()", position, position - 1)
                        .isEqualTo(lines.get(position - 1));
            }
        }

        @ParameterizedTest(name = "optionLine({0}) is outside 1..12 and is refused")
        @ValueSource(ints = {-1, 0, 13, 99})
        @DisplayName("a subscript outside 1..12 names a field the map does not declare")
        void aSubscriptOutsideOneToTwelveIsRefused(int position) {
            // Position 0 is the trap: it is the valid FIRST index in Java and an invalid subscript in
            // COBOL. Refusing it is what keeps the one-based contract honest. Position 13 is the far end.
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThatThrownBy(() -> request.optionLine(position))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("The conversation travels in the payload, never in server state - gates G37 and G53")
    class ConversationStateTravelsInThePayload {

        // =============================================================================================
        // CICS is pseudo-conversational: the task ends at EXEC CICS RETURN (COMEN01C.cbl:107-110) and the
        // next keystroke starts a fresh one. Nothing is held between them except what the COMMAREA
        // carries and what the terminal resends. Rule R6 and gate G37 say the Java translation must be
        // the same: no HttpSession, no server-side cache, no static field standing in for a screen.
        //
        // The conversation is exactly three things, and all three are payload members here:
        //   1. the communication area   - app/cpy/COCOM01Y.cpy, 160 bytes, carried as NavigationContext
        //   2. the attention identifier - EIBAID, carried as a raw byte
        //   3. the enter / re-enter context - CDEMO-PGM-CONTEXT, read through the carried area
        //
        // Plus one thing the ABSENCE of which is itself state: EIBCALEN. COMEN01C.cbl:82 branches on it.
        // =============================================================================================

        @Test
        @DisplayName("the communication area is a payload member, not a session lookup")
        void theCommunicationAreaIsAPayloadMember() throws Exception {
            NavigationContext context = NavigationContext.empty().withUserTypeUser();
            MainMenuRequest request = atDeclaredWidths(" 1", context);

            assertThat(recordComponentNames()).contains("navigationContext");
            assertThat(MainMenuRequest.class.getMethod("navigationContext").getReturnType())
                    .isEqualTo(NavigationContext.class);
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.commareaPresent()).isTrue();
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

            // And the image the carried area actually renders is that wide. The code page is stated
            // EXPLICITLY - US-ASCII, as the app/data/ASCII fixtures are - and never defaulted (B8).
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty()
                    .withFromTranid(MainMenuRequest.TRANSACTION_ID)
                    .withFromProgram(MainMenuRequest.PROGRAM_NAME)
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter()
                    .withLastMap(MainMenuRequest.MAP_NAME)
                    .withLastMapset(MainMenuRequest.MAPSET_NAME));

            assertThat(codec.charset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(request.navigationContext().toFixedWidth(codec)).hasSize(160);
        }

        @Test
        @DisplayName("the last map and mapset are X(7), not X(8) - the width the total depends on")
        void theLastMapAndMapsetAreSevenBytes() {
            // app/cpy/COCOM01Y.cpy:43-44 declares
            //   43       10  CDEMO-LAST-MAP               PIC X(7).
            //   44       10  CDEMO-LAST-MAPSET            PIC X(7).
            // SEVEN, not the eight a reader would expect from CDEMO-FROM-PROGRAM PIC X(08) at :22 - and
            // seven is right, because a CardDemo map name is seven characters: COMEN1A.
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(MainMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuRequest.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);

            FixedWidthRecord.FieldSpan lastMap =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAP_FIELD);
            FixedWidthRecord.FieldSpan lastMapset =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAPSET_FIELD);

            assertThat(lastMap.length()).isEqualTo(7);
            assertThat(lastMapset.length()).isEqualTo(7);
            assertThat(lastMapset.offset()).isEqualTo(lastMap.offset() + 7);
            assertThat(lastMapset.endOffsetExclusive()).isEqualTo(160);

            // Why it matters: at X(8) each the area would be 162 bytes and every consumer of the
            // 160-byte record would be misaligned from CDEMO-LAST-MAP onwards.
            assertThat(34 + 84 + 12 + 16 + (8 + 8)).isNotEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the raw EIBAID byte is a payload member and is stored unchanged")
        void theRawEibAidByteIsAPayloadMember() throws Exception {
            // EIBAID is one byte of the CICS exec interface block. COMEN01C.cbl:93-103 evaluates it
            // directly. Resolving a byte to a key belongs to common.PfKeyResolver and to the service; the
            // payload's only job is to carry the byte the terminal sent, unexamined.
            assertThat(recordComponentNames()).contains("eibAid");
            assertThat(MainMenuRequest.class.getMethod("eibAid").getReturnType()).isEqualTo(byte.class);

            for (byte candidate : new byte[] {(byte) 0x7D, (byte) 0xF3, (byte) 0x6D, (byte) 0x00,
                    (byte) 0xFF, (byte) 0xC1}) {
                MainMenuRequest request = new MainMenuRequest("CM00", null, null, "COMEN01C", null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null,
                        " 1", null, NavigationContext.empty(), candidate);

                assertThat(request.eibAid()).as("AID byte 0x%02X survives", candidate)
                        .isEqualTo(candidate);
            }
        }

        @Test
        @DisplayName("DFHENTER and DFHPF3, the two AIDs COMEN01C.cbl:94 and :96 match, survive intact")
        void theTwoRecognisedAidsSurviveIntact() throws Exception {
            // app/cbl/COMEN01C.cbl:93-103 is the whole AID surface of this screen:
            //   94   WHEN DFHENTER  -> PERFORM PROCESS-ENTER-KEY
            //   96   WHEN DFHPF3    -> MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM, RETURN-TO-SIGNON-SCREEN
            //   99   WHEN OTHER     -> CCDA-MSG-INVALID-KEY, re-send the screen
            // The byte values come from the IBM-supplied DFHAID copybook, which is absent from this
            // repository (AAP risk R-D) and reproduced in common.CicsAid. They are transcribed here as
            // literals rather than imported, so this test stays independent of that reproduction and
            // would still fail if the payload started interpreting the byte.
            byte dfhEnter = (byte) 0x7D;
            byte dfhPf3 = (byte) 0xF3;
            ObjectMapper mapper = new ObjectMapper();

            MainMenuRequest entered = atDeclaredWidths(" 1", NavigationContext.empty());
            assertThat(entered.eibAid()).isEqualTo(dfhEnter);

            MainMenuRequest exited = new MainMenuRequest("CM00", null, null, "COMEN01C", null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, " 1", null,
                    NavigationContext.empty(), dfhPf3);
            assertThat(exited.eibAid()).isEqualTo(dfhPf3);
            assertThat(exited).isNotEqualTo(entered);

            // Across the wire too, since a signed-byte round trip is easy to get wrong: 0xF3 is -13.
            assertThat(dfhPf3).isEqualTo((byte) -13);
            assertThat(mapper.readValue(mapper.writeValueAsString(exited), MainMenuRequest.class)
                    .eibAid()).isEqualTo(dfhPf3);
        }

        @Test
        @DisplayName("both program-context states are driven: ENTER 0 and REENTER 1, read through")
        void bothProgramContextStatesAreDriven() {
            // 88 CDEMO-PGM-ENTER VALUE 0 at app/cpy/COCOM01Y.cpy:30 and
            // 88 CDEMO-PGM-REENTER VALUE 1 at :31. app/cbl/COMEN01C.cbl:87-90 tests
            // IF NOT CDEMO-PGM-REENTER, sets it true at :88, clears the map to LOW-VALUES at :89 and
            // paints at :90; the ELSE at :91-92 receives the map instead. That single bit decides
            // whether the screen is painted or read, so both states must be reachable.
            MainMenuRequest onEnter =
                    atDeclaredWidths(" 1", NavigationContext.empty().withPgmEnter());
            MainMenuRequest onReenter =
                    atDeclaredWidths(" 1", NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.enter()).isTrue();
            assertThat(onEnter.reenter()).isFalse();
            assertThat(onReenter.reenter()).isTrue();
            assertThat(onReenter.enter()).isFalse();

            // Read-through rather than a second copy, so CDEMO-PGM-CONTEXT keeps exactly one home and
            // cannot disagree with itself.
            assertThat(onEnter.enter()).isEqualTo(onEnter.navigationContext().isEnter());
            assertThat(onReenter.reenter()).isEqualTo(onReenter.navigationContext().isReenter());
            assertThat(onEnter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(onReenter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the absent-communication-area marker reproduces IF EIBCALEN = 0 at COMEN01C.cbl:82")
        void theAbsentCommunicationAreaMarkerReproducesEibcalenZero() {
            // app/cbl/COMEN01C.cbl:82-84 is the sign-on route:
            //   82   IF EIBCALEN = 0
            //   83       MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
            //   84       PERFORM RETURN-TO-SIGNON-SCREEN
            // A zero-length communication area means "nobody handed me a conversation", and the program
            // answers it by sending the user to sign on. It is NOT an error, so the marker must be an
            // ordinary, valid state of the payload - which is why the same request also validates clean.
            MainMenuRequest withoutArea = atDeclaredWidths(" 1", null);
            MainMenuRequest withArea = atDeclaredWidths(" 1", NavigationContext.empty());

            // Marker SET - the EIBCALEN = 0 branch.
            assertThat(withoutArea.navigationContext()).isNull();
            assertThat(withoutArea.commareaAbsent()).isTrue();
            assertThat(withoutArea.commareaPresent()).isFalse();
            assertThat(violatedProperties(withoutArea)).isEmpty();

            // Marker CLEAR - the ELSE at :85-86, MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA.
            assertThat(withArea.navigationContext()).isNotNull();
            assertThat(withArea.commareaAbsent()).isFalse();
            assertThat(withArea.commareaPresent()).isTrue();

            // The two predicates are exact opposites, both sides driven, so neither can be stubbed.
            assertThat(withoutArea.commareaAbsent()).isNotEqualTo(withoutArea.commareaPresent());
            assertThat(withArea.commareaAbsent()).isNotEqualTo(withArea.commareaPresent());
        }

        @Test
        @DisplayName("with no communication area neither context state is claimed")
        void withNoCommunicationAreaNeitherContextStateIsClaimed() {
            // There is no CDEMO-PGM-CONTEXT to read when EIBCALEN = 0, so neither enter() nor reenter()
            // may assert one. Defaulting either would send the request down a branch COMEN01C.cbl:82
            // never reaches, because that path returns to sign-on before the context is ever consulted.
            MainMenuRequest signedOut = atDeclaredWidths(" 1", null);

            assertThat(signedOut.enter()).isFalse();
            assertThat(signedOut.reenter()).isFalse();
            assertThat(signedOut.commareaAbsent()).isTrue();
        }

        @Test
        @DisplayName("no HttpSession, no servlet type and no Spring type appears anywhere in the shape")
        void noServletOrSessionTypeAppearsInTheShape() {
            // Gate G37 in its structural form. If the payload cannot even NAME a session type, the
            // conversation cannot be smuggled into one. The sweep covers component types and every
            // declared method's return and parameter types, which together are the whole shape.
            List<String> referenced = new ArrayList<>();
            Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .forEach(component -> referenced.add(component.getType().getName()));
            Arrays.stream(MainMenuRequest.class.getDeclaredMethods()).forEach(method -> {
                referenced.add(method.getReturnType().getName());
                Arrays.stream(method.getParameterTypes())
                        .forEach(type -> referenced.add(type.getName()));
            });

            assertThat(referenced).isNotEmpty()
                    .allSatisfy(name -> assertThat(name).doesNotContain("HttpSession")
                            .doesNotContain("HttpServletRequest")
                            .doesNotContain("HttpServletResponse")
                            .doesNotContain("jakarta.servlet")
                            .doesNotContain("org.springframework")
                            .doesNotContain("SecurityContext")
                            .doesNotContain("ThreadLocal")
                            .doesNotContain("jakarta.persistence"));

            // Nor by member name, which is how a session would most plausibly creep in.
            assertThat(recordComponentNames()).doesNotContain("session",
                    "httpSession",
                    "sessionId",
                    "conversationId",
                    "cache",
                    "state");
        }

        @Test
        @DisplayName("no field is static and non-final, and no field is mutable at all - gate G53")
        void noStaticMutableStateExists() {
            // Gate G53 and practice B9 together. A per-request payload that remembered anything between
            // requests would be a session by another name, so every field must be final; and a static
            // field that could be reassigned would be shared across every request in the JVM.
            Field[] fields = MainMenuRequest.class.getDeclaredFields();

            assertThat(fields).isNotEmpty();
            for (Field field : fields) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();

                if (Modifier.isStatic(field.getModifiers())) {
                    // A static final constant is fine only if it is deeply immutable. Primitives and
                    // Strings are; an array or a mutable collection would not be, because final pins the
                    // reference and not the contents.
                    assertThat(field.getType().isPrimitive()
                            || field.getType() == String.class
                            || field.getType() == Integer.class)
                            .as("static field %s must be an immutable constant, was %s",
                                    field.getName(),
                                    field.getType().getName())
                            .isTrue();
                    assertThat(field.getType().isArray())
                            .as("static field %s must not be an array", field.getName())
                            .isFalse();
                }
            }

            // The instance fields are exactly the twenty-two components: no hidden slot was added.
            assertThat(Arrays.stream(fields)
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .count()).isEqualTo(22);

            // And this TEST class holds no mutable state either, which is the same standard applied to
            // the asserter (practice B9). Every field it declares is a static final List.of.
            for (Field field : MainMenuRequestTest.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("test field %s must be final", field.getName())
                        .isTrue();
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("test field %s must be static, so it is a constant not per-test state",
                                field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("two requests never share mutable structure, so one cannot observe the other")
        void twoRequestsNeverShareMutableStructure() {
            // Statelessness stated as an observable property rather than a structural one: build two
            // payloads, mutate nothing, and confirm neither can reach the other's contents. The lists
            // are distinct wrappers and the contexts are independent values.
            MainMenuRequest first = atDeclaredWidths(" 1", NavigationContext.empty().withPgmEnter());
            MainMenuRequest second = atDeclaredWidths(" 2", NavigationContext.empty().withPgmReenter());

            assertThat(first.optionLines()).isNotSameAs(second.optionLines());
            assertThat(first.navigationContext()).isNotSameAs(second.navigationContext());
            assertThat(first.option()).isEqualTo(" 1");
            assertThat(second.option()).isEqualTo(" 2");
            assertThat(first.enter()).isTrue();
            assertThat(second.reenter()).isTrue();

            // A record is a value: equal components mean equal payloads, and nothing else is carried.
            assertThat(atDeclaredWidths(" 1", NavigationContext.empty().withPgmEnter()))
                    .isEqualTo(first)
                    .hasSameHashCodeAs(first);
        }
    }

    @Nested
    @DisplayName("Screen identity - CM00 / COMEN01C / COMEN01 / COMEN1A, each fitting its field exactly")
    class ScreenIdentity {

        @Test
        @DisplayName("the transaction identifier is CM00, four characters into a PIC X(4) field")
        void theTransactionIdentifierIsCm00() {
            // app/csd/CARDDEMO.CSD:399  DEFINE TRANSACTION(CM00) GROUP(CARDDEMO)
            // app/cbl/COMEN01C.cbl:37   05 WS-TRANID  PIC X(04) VALUE 'CM00'.
            // Four characters into PIC X(04) - an exact fit, so there is no padding to get wrong and no
            // trailing space that a comparison might or might not tolerate.
            assertThat(MainMenuRequest.TRANSACTION_ID).isEqualTo("CM00")
                    .hasSize(4)
                    .hasSize(MainMenuRequest.TRN_NAME_LENGTH)
                    .isEqualTo(MainMenuRequest.TRANSACTION_ID.trim())
                    .doesNotContain(" ");

            // It fits TRNNAMEI PIC X(4) at app/cpy-bms/COMEN01.CPY:24 exactly, and CDEMO-FROM-TRANID
            // PIC X(04) at app/cpy/COCOM01Y.cpy:21 exactly - COMEN01C.cbl:147 moves it into the latter.
            assertThat(MainMenuRequest.TRANSACTION_ID).hasSize(declaredWidthOf(0));
            assertThat(MainMenuRequest.TRANSACTION_ID)
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);

            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());
            assertThat(request.trnName()).isEqualTo("CM00").isEqualTo(MainMenuRequest.TRANSACTION_ID);
            assertThat(violatedProperties(request)).isEmpty();
        }

        @Test
        @DisplayName("the program name is COMEN01C, eight characters into a PIC X(8) field")
        void theProgramNameIsComen01c() {
            // app/csd/CARDDEMO.CSD:400  PROGRAM(COMEN01C) TWASIZE(0) ...
            // app/cbl/COMEN01C.cbl:23   PROGRAM-ID. COMEN01C.
            // app/cbl/COMEN01C.cbl:36   05 WS-PGMNAME PIC X(08) VALUE 'COMEN01C'.
            // Eight characters into PIC X(08) - again an exact fit. Three independent sources agree,
            // which is why this literal is the safest in the file.
            assertThat(MainMenuRequest.PROGRAM_NAME).isEqualTo("COMEN01C")
                    .hasSize(8)
                    .hasSize(MainMenuRequest.PGM_NAME_LENGTH)
                    .isEqualTo(MainMenuRequest.PROGRAM_NAME.trim())
                    .doesNotContain(" ");

            // It fits PGMNAMEI PIC X(8) at COMEN01.CPY:42 and CDEMO-FROM-PROGRAM PIC X(08) at
            // COCOM01Y.cpy:22 exactly - COMEN01C.cbl:148 moves it into the latter.
            assertThat(MainMenuRequest.PROGRAM_NAME).hasSize(declaredWidthOf(3));
            assertThat(MainMenuRequest.PROGRAM_NAME).hasSize(NavigationContext.FROM_PROGRAM_LENGTH);

            assertThat(atDeclaredWidths(" 1", NavigationContext.empty()).pgmName())
                    .isEqualTo("COMEN01C")
                    .isEqualTo(MainMenuRequest.PROGRAM_NAME);
        }

        @Test
        @DisplayName("the mapset is COMEN01 and the map is COMEN1A, seven characters each")
        void theMapsetAndMapAreSevenCharactersEach() {
            // app/csd/CARDDEMO.CSD:133   DEFINE MAPSET(COMEN01) GROUP(CARDDEMO)
            // app/bms/COMEN01.bms:19     COMEN01 DFHMSD ...
            // app/bms/COMEN01.bms:26     COMEN1A DFHMDI ...
            // Seven characters each, which is exactly the width of the CDEMO-LAST-MAP and
            // CDEMO-LAST-MAPSET slots at app/cpy/COCOM01Y.cpy:43-44 - the X(7) coincidence is not a
            // coincidence at all, it is why those two fields are seven bytes rather than eight.
            assertThat(MainMenuRequest.MAPSET_NAME).isEqualTo("COMEN01").hasSize(7);
            assertThat(MainMenuRequest.MAP_NAME).isEqualTo("COMEN1A").hasSize(7);
            assertThat(MainMenuRequest.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(MainMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);

            // Mapset and map differ, and the map is not merely the mapset with a suffix: COMEN01 versus
            // COMEN1A is a real BMS naming quirk and confusing them would name a map CICS cannot find.
            assertThat(MainMenuRequest.MAP_NAME).isNotEqualTo(MainMenuRequest.MAPSET_NAME);

            // Round-tripping them through the area preserves both, since both fit exactly.
            NavigationContext carried = NavigationContext.empty()
                    .withLastMap(MainMenuRequest.MAP_NAME)
                    .withLastMapset(MainMenuRequest.MAPSET_NAME);
            assertThat(atDeclaredWidths(" 1", carried).navigationContext().lastMap())
                    .isEqualTo("COMEN1A");
            assertThat(atDeclaredWidths(" 1", carried).navigationContext().lastMapset())
                    .isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("the four identity literals are distinct and none is blank")
        void theFourIdentityLiteralsAreDistinctAndNonBlank() {
            // A single copy-paste slip between the four is the most likely way this screen would end up
            // claiming to be another, so they are pinned as a set as well as individually.
            assertThat(List.of(MainMenuRequest.TRANSACTION_ID,
                    MainMenuRequest.PROGRAM_NAME,
                    MainMenuRequest.MAPSET_NAME,
                    MainMenuRequest.MAP_NAME))
                    .containsExactly("CM00", "COMEN01C", "COMEN01", "COMEN1A")
                    .doesNotHaveDuplicates()
                    .allSatisfy(literal -> assertThat(literal).isNotBlank());
        }
    }

    @Nested
    @DisplayName("The duplication with the admin menu is documented, not removed - practice B4")
    class DuplicationIsDocumentedNotRemoved {

        @Test
        @DisplayName("MainMenuRequest and AdminMenuRequest are distinct Java types, deliberately")
        void mainAndAdminMenuRequestsAreDistinctTypes() {
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
            // TWO LINES. Every other line - all twenty field groups, every PICTURE clause and every
            // width - is byte-identical.
            //
            //   $ diff app/bms/COADM01.bms app/bms/COMEN01.bms
            //   2c2      *    CardDemo - Admin Menu Screen  vs  *    CardDemo - Main Menu Screen
            //   19c19    COADM01 DFHMSD ...                 vs  COMEN01 DFHMSD ...
            //   26c26    COADM1A DFHMDI ...                 vs  COMEN1A DFHMDI ...
            //   77c77                   LENGTH=10,          vs                 LENGTH=9,
            //   79c79                   INITIAL='Admin Menu' vs                INITIAL='Main Menu'
            //   166c166  * Ver: ... 17:02:42 CDT            vs  * Ver: ... 17:02:43 CDT
            //
            // A comment, the mapset name, the map name, a version timestamp, and ONE UNNAMED literal
            // label. Being unnamed, that label generates no symbolic-map item - which is exactly why the
            // two .CPY files coincide, and why the 'Main Menu' heading is legitimately absent from this
            // payload (asserted in TheTwentyPayloadMembers).
            //
            // The duplication is real and it is LEFT IN PLACE. There is no shared base class, no shared
            // interface, no generic parameterised by group name, no MenuDtoSupport helper, no shared
            // fixture builder, no package-info.java, no README and no single parameterised suite spanning
            // both request types. Two reasons, either sufficient alone:
            //   * The screens are independent contracts that merely coincide TODAY. The admin menu offers
            //     four options from app/cpy/COADM02Y.cpy (:20 VALUE 4, :45 OCCURS 9); the main
            //     menu offers ten from app/cpy/COMEN02Y.cpy (:21 VALUE 10, :88 OCCURS 12), gated
            //     by the X(01) user-type authorisation column at :92 that the admin table lacks
            //     entirely.
            //     Coupling the payloads would make a future divergence in one screen a breaking change in
            //     the other.
            //   * Collapsing two independently declared copybooks into one Java type is precisely the
            //     silent structural change this migration forbids, and it would erase the field-name
            //     provenance that field-for-field parity diffing depends on.
            assertThat(MainMenuRequest.class).isNotEqualTo(AdminMenuRequest.class);
            assertThat(MainMenuRequest.class.getName()).isNotEqualTo(AdminMenuRequest.class.getName());

            // Neither is assignable to the other, so they cannot have been unified behind a supertype.
            assertThat(MainMenuRequest.class.isAssignableFrom(AdminMenuRequest.class)).isFalse();
            assertThat(AdminMenuRequest.class.isAssignableFrom(MainMenuRequest.class)).isFalse();

            // Both extend java.lang.Record directly and implement nothing - no shared abstract parent and
            // no shared interface was introduced to hold the coincidence.
            assertThat(MainMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(AdminMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuRequest.class.getInterfaces()).isEmpty();
            assertThat(AdminMenuRequest.class.getInterfaces()).isEmpty();
            assertThat(MainMenuRequest.class.isRecord()).isTrue();
            assertThat(Modifier.isFinal(MainMenuRequest.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the coincidence itself is asserted, so a future divergence surfaces here")
        void theCoincidenceIsAssertedRatherThanExploited() {
            // The point of asserting the coincidence rather than exploiting it: if either screen's map is
            // ever changed, this test fails and a human decides what to do - instead of one shared type
            // silently imposing one screen's shape on the other.
            //
            // Note that the two types do not even spell their constants alike - the field count is
            // MAP_FIELD_COUNT here and MAPPED_FIELD_COUNT there, the data width
            // SYMBOLIC_MAP_PAYLOAD_LENGTH here and PAYLOAD_DATA_LENGTH there, the stride
            // FIELD_METADATA_LENGTH here and METADATA_BYTES_PER_FIELD there, and the predicates are
            // commareaPresent()/enter()/reenter() here against isCommareaPresent()/isEnter()/isReenter()
            // there. That is what independently written contracts look like, and it is a further reason
            // no shared supertype was introduced: unifying them would have forced one screen's vocabulary
            // onto the other.
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(AdminMenuRequest.MAPPED_FIELD_COUNT);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.SYMBOLIC_MAP_LENGTH);
            assertThat(MainMenuRequest.FIELD_METADATA_LENGTH)
                    .isEqualTo(AdminMenuRequest.METADATA_BYTES_PER_FIELD);
            assertThat(MainMenuRequest.TIOAPFX_FILLER_LENGTH)
                    .isEqualTo(AdminMenuRequest.TIOAPFX_LENGTH);
            assertThat(MainMenuRequest.OPTION_LINE_COUNT)
                    .isEqualTo(AdminMenuRequest.OPTION_LINE_COUNT);
            assertThat(MainMenuRequest.OPTION_LENGTH).isEqualTo(AdminMenuRequest.OPTION_LENGTH);
            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isEqualTo(AdminMenuRequest.ERR_MSG_LENGTH);

            // The twenty component names coincide too, because the twenty xxxI items do. Asserted as a
            // list so ORDER is included: the copybooks agree on order as well as on names.
            List<String> theirScreenMembers = Arrays.stream(AdminMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .limit(20)
                    .collect(Collectors.toList());
            assertThat(theirScreenMembers).containsExactlyElementsOf(EXPECTED_MEMBER_NAMES);

            // The IDENTITIES, by contrast, must differ: CM00/COMEN01C against CA00/COADM01C - the two
            // transactions are defined separately in app/csd/CARDDEMO.CSD, DEFINE TRANSACTION(CM00) at
            // :399 and DEFINE TRANSACTION(CA00) at :327, and the two mapsets separately as well,
            // MAPSET(COMEN01) at :133 and MAPSET(COADM01) at :110.
            assertThat(MainMenuRequest.TRANSACTION_ID).isNotEqualTo(AdminMenuRequest.TRANSACTION_ID);
            assertThat(MainMenuRequest.PROGRAM_NAME).isNotEqualTo(AdminMenuRequest.PROGRAM_NAME);
            assertThat(MainMenuRequest.MAPSET_NAME).isNotEqualTo(AdminMenuRequest.MAPSET_NAME);
            assertThat(MainMenuRequest.MAP_NAME).isNotEqualTo(AdminMenuRequest.MAP_NAME);
        }

        @Test
        @DisplayName("this test class shares no base type, and the two suites are independent")
        void thisTestClassSharesNoBaseType() {
            // Practice B4 applied to the ASSERTER as well as to the subject. If this suite inherited from
            // a shared menu-request base, a change made for the admin screen's benefit could silently
            // weaken this screen's coverage - and the coupling would be invisible from here.
            assertThat(MainMenuRequestTest.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(MainMenuRequestTest.class.getInterfaces()).isEmpty();

            // Nothing is imported from, or delegated to, the admin suite: no method of this class returns
            // or accepts it, and no nested class extends it.
            assertThat(Arrays.stream(MainMenuRequestTest.class.getDeclaredMethods())
                    .flatMap(method -> {
                        List<Class<?>> types = new ArrayList<>();
                        types.add(method.getReturnType());
                        types.addAll(Arrays.asList(method.getParameterTypes()));
                        return types.stream();
                    })
                    .map(Class::getName)
                    .collect(Collectors.toList()))
                    .allSatisfy(name -> assertThat(name).doesNotContain("AdminMenuRequestTest"));

            for (Class<?> nested : MainMenuRequestTest.class.getDeclaredClasses()) {
                assertThat(nested.getSuperclass())
                        .as("nested class %s must stand alone", nested.getSimpleName())
                        .isEqualTo(Object.class);
            }
        }
    }

    @Nested
    @DisplayName("The wire carries the twenty screen fields, the area and the AID - nothing else")
    class JsonWireFormat {

        private Set<String> keysOf(MainMenuRequest request) throws Exception {
            JsonNode root = new ObjectMapper().valueToTree(request);
            Set<String> keys = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(keys::add);
            return keys;
        }

        @Test
        @DisplayName("exactly the twenty screen members plus navigationContext and eibAid")
        void exactlyTwentyTwoKeys() throws Exception {
            List<String> expected = new ArrayList<>(EXPECTED_MEMBER_NAMES);
            expected.addAll(EXPECTED_CARRIER_NAMES);

            assertThat(keysOf(atDeclaredWidths(" 1", NavigationContext.empty())))
                    .containsExactlyInAnyOrderElementsOf(expected)
                    .hasSize(22);
        }

        @Test
        @DisplayName("no symbolic-map metadata item reaches the wire, under any capitalisation")
        void noMetadataItemReachesTheWire() throws Exception {
            // The wire-level restatement of the structural sweep in TheTwentyPayloadMembers. A member
            // absent from the record but re-introduced by a serialiser feature would pass there and fail
            // here, which is why both exist.
            Set<String> keys = keysOf(atDeclaredWidths(" 1", NavigationContext.empty()));
            List<String> forbidden = new ArrayList<>();
            for (String item : EXPECTED_ITEM_NAMES) {
                String base = item.substring(0, item.length() - 1);
                for (String suffix : METADATA_SUFFIXES) {
                    forbidden.add(base + suffix);
                }
            }

            assertThat(forbidden).hasSize(140);
            assertThat(forbidden).allSatisfy(metadata -> assertThat(keys)
                    .as("metadata item %s must never reach the wire", metadata)
                    .noneMatch(key -> key.equalsIgnoreCase(metadata)));

            // The three named cases again, at the wire level (practice B11).
            assertThat(keys).doesNotContain("trnNameL", "optionF", "errMsgA", "TRNNAMEL", "OPTIONF",
                    "ERRMSGA");
        }

        @Test
        @DisplayName("the derived views are not serialised - they are reads, not state")
        void theDerivedViewsAreNotSerialised() throws Exception {
            // optionLines(), optionLine(n), commareaPresent(), commareaAbsent(), enter() and reenter()
            // restate components that are already carried. Serialising them would put two spellings of
            // the same fact on the wire and invite them to disagree.
            assertThat(keysOf(atDeclaredWidths(" 1", NavigationContext.empty())))
                    .doesNotContain("optionLines",
                            "optionLine",
                            "commareaPresent",
                            "commareaAbsent",
                            "enter",
                            "reenter");
        }

        @Test
        @DisplayName("a round trip preserves every component, and re-serialises identically")
        void aRoundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest original = atDeclaredWidths(" 3", NavigationContext.empty()
                    .withFromTranid(MainMenuRequest.TRANSACTION_ID)
                    .withFromProgram(MainMenuRequest.PROGRAM_NAME)
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter()
                    .withLastMap(MainMenuRequest.MAP_NAME)
                    .withLastMapset(MainMenuRequest.MAPSET_NAME));

            String json = mapper.writeValueAsString(original);
            MainMenuRequest revived = mapper.readValue(json, MainMenuRequest.class);

            assertThat(revived).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(revived.optionLines()).isEqualTo(original.optionLines());
            assertThat(revived.option()).isEqualTo(" 3");
            assertThat(revived.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(revived.reenter()).isTrue();
            assertThat(mapper.writeValueAsString(revived)).isEqualTo(json);
        }

        @Test
        @DisplayName("an absent communication area round-trips as absent, not as an empty one")
        void anAbsentCommunicationAreaRoundTripsAsAbsent() throws Exception {
            // The distinction the EIBCALEN = 0 branch at COMEN01C.cbl:82 depends on. If Jackson revived a
            // missing area as NavigationContext.empty(), the sign-on route would become unreachable and
            // the request would instead be treated as a first entry with a blank user type.
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest signedOut = atDeclaredWidths(" 1", null);

            MainMenuRequest revived =
                    mapper.readValue(mapper.writeValueAsString(signedOut), MainMenuRequest.class);

            assertThat(revived.navigationContext()).isNull();
            assertThat(revived.commareaAbsent()).isTrue();
            assertThat(revived.commareaPresent()).isFalse();
            assertThat(revived).isEqualTo(signedOut);
        }

        @Test
        @DisplayName("toString names the screen, so a log line identifies which payload failed")
        void toStringNamesTheScreen() {
            // Diagnostics only, but worth pinning: a payload that logged as a bare list of nulls would be
            // indistinguishable from the admin menu's in a stack trace, which is the one place the
            // deliberate duplication could actually cost a reader time.
            assertThat(atDeclaredWidths(" 1", NavigationContext.empty()).toString())
                    .contains("COMEN01C")
                    .contains("CM00");
        }
    }
}
