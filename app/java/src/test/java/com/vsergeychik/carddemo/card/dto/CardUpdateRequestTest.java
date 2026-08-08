package com.vsergeychik.carddemo.card.dto;

import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.FieldMetadata;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link CardUpdateRequest} against its sources: {@code app/cpy-bms/COCRDUP.CPY},
 * {@code app/bms/COCRDUP.bms} and {@code app/cbl/COCRDUPC.cbl}.
 *
 * <p>Every assertion is traceable to a declaration in one of those three files. The suite is
 * organised so a reviewer can check a group of assertions against a single source construct rather
 * than hunting through one flat list.
 */
@DisplayName("CardUpdateRequest - COCRDUP/CCRDUPA, 17 named DFHMDF fields of 34, "
        + "and the 329-byte WS-THIS-PROGCOMMAREA")
class CardUpdateRequestTest {

    /** The text fixtures' code page. Named explicitly; the platform default is never used. */
    private static final FixedWidthCodec ASCII = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** The EBCDIC datasets' code page, so every byte assertion is checked in both. */
    private static final Charset IBM037 = Charset.forName("IBM037");

    /** A codec over {@link #IBM037}. */
    private static final FixedWidthCodec EBCDIC = new FixedWidthCodec(IBM037);

    /** The space byte under {@code IBM037}, which is {@code 0x40} rather than {@code 0x20}. */
    private static final byte EBCDIC_SPACE = 0x40;

    @Nested
    @DisplayName("Declared geometry - the 17 xxxI items, and the 353 and 484 totals")
    class DeclaredGeometry {

        @Test
        @DisplayName("exactly 17 name-labelled fields of 34 DFHMDF entries, in declaration order")
        void seventeenFieldsInDeclarationOrder() {
            assertThat(CardUpdateRequest.NAMED_FIELD_COUNT).isEqualTo(17);
            assertThat(CardUpdateRequest.TOTAL_DFHMDF_COUNT).isEqualTo(34);
            assertThat(CardUpdateRequest.FIELD_NAMES).containsExactly("TRNNAME", "TITLE01",
                    "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "ACCTSID", "CARDSID", "CRDNAME",
                    "CRDSTCD", "EXPMON", "EXPYEAR", "EXPDAY", "INFOMSG", "ERRMSG", "FKEYS",
                    "FKEYSC");
        }

        @Test
        @DisplayName("every width comes from its xxxI PICTURE clause")
        void everyWidthComesFromItsPictureClause() {
            assertThat(CardUpdateRequest.FIELD_LENGTHS).containsExactly(
                    Map.entry("TRNNAME", 4), Map.entry("TITLE01", 40), Map.entry("CURDATE", 8),
                    Map.entry("PGMNAME", 8), Map.entry("TITLE02", 40), Map.entry("CURTIME", 8),
                    Map.entry("ACCTSID", 11), Map.entry("CARDSID", 16), Map.entry("CRDNAME", 50),
                    Map.entry("CRDSTCD", 1), Map.entry("EXPMON", 2), Map.entry("EXPYEAR", 4),
                    Map.entry("EXPDAY", 2), Map.entry("INFOMSG", 40), Map.entry("ERRMSG", 80),
                    Map.entry("FKEYS", 21), Map.entry("FKEYSC", 18));
        }

        @Test
        @DisplayName("the widths sum to 353 and the whole CCRDUPAI group is 484 bytes")
        void widthArithmeticIs353And484() {
            int sum = CardUpdateRequest.FIELD_LENGTHS.values().stream()
                    .mapToInt(Integer::intValue).sum();
            assertThat(sum).isEqualTo(353);
            assertThat(CardUpdateRequest.NAMED_FIELD_TOTAL_LENGTH).isEqualTo(353);
            assertThat(CardUpdateRequest.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardUpdateRequest.FIELD_METADATA_LENGTH).isEqualTo(7);
            assertThat(CardUpdateRequest.GROUP_LENGTH)
                    .isEqualTo(CardUpdateRequest.TIOAPFX_LENGTH
                            + CardUpdateRequest.NAMED_FIELD_COUNT
                            * CardUpdateRequest.FIELD_METADATA_LENGTH
                            + CardUpdateRequest.NAMED_FIELD_TOTAL_LENGTH)
                    .isEqualTo(484);
        }

        @Test
        @DisplayName("the screen identity matches the CSD and the BMS SIZE=(24,80)")
        void screenIdentityMatchesTheSource() {
            assertThat(CardUpdateRequest.TRANSACTION_ID).isEqualTo("CCUP");
            assertThat(CardUpdateRequest.PROGRAM_NAME).isEqualTo("COCRDUPC");
            assertThat(CardUpdateRequest.MAPSET_NAME).isEqualTo("COCRDUP");
            assertThat(CardUpdateRequest.MAP_NAME).isEqualTo("CCRDUPA");
            assertThat(CardUpdateRequest.SCREEN_ROWS).isEqualTo(24);
            assertThat(CardUpdateRequest.SCREEN_COLUMNS).isEqualTo(80);
        }

        @Test
        @DisplayName("declaredLength answers for every label and rejects anything else")
        void declaredLengthAnswersForEveryLabel() {
            CardUpdateRequest.FIELD_NAMES.forEach(name ->
                    assertThat(CardUpdateRequest.declaredLength(name)).as(name)
                            .isEqualTo(CardUpdateRequest.FIELD_LENGTHS.get(name)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateRequest.declaredLength("NOSUCH"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRequest.declaredLength(null));
        }

        @Test
        @DisplayName("spaces() honours a declared width and rejects a width below one")
        void spacesHonoursADeclaredWidth() {
            assertThat(CardUpdateRequest.spaces(21)).isEqualTo(" ".repeat(21));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateRequest.spaces(0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardUpdateRequest.spaces(-1));
        }
    }

    @Nested
    @DisplayName("The FKEYSC trap - a genuine field, not FKEYS plus a colour suffix")
    class FkeyscTrap {

        @Test
        @DisplayName("FKEYS is 21 bytes and FKEYSC is 18, and they are separate labels")
        void twoDistinctFieldsOfTwentyOneAndEighteen() {
            assertThat(CardUpdateRequest.FKEYS_FIELD).isEqualTo("FKEYS");
            assertThat(CardUpdateRequest.FKEYSC_FIELD).isEqualTo("FKEYSC");
            assertThat(CardUpdateRequest.FKEYS_LENGTH).isEqualTo(21);
            assertThat(CardUpdateRequest.FKEYSC_LENGTH).isEqualTo(18);
            assertThat(CardUpdateRequest.declaredLength("FKEYS")).isEqualTo(21);
            assertThat(CardUpdateRequest.declaredLength("FKEYSC")).isEqualTo(18);
            assertThat(CardUpdateRequest.FIELD_NAMES).containsSequence("FKEYS", "FKEYSC");
        }

        @Test
        @DisplayName("each holds its own value and projects to its own width")
        void eachHoldsItsOwnValueAndWidth() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setFkeys("F3=Back");
            request.setFkeysc("F5=Save");
            assertThat(request.getFkeys()).isEqualTo("F3=Back");
            assertThat(request.getFkeysc()).isEqualTo("F5=Save");
            assertThat(request.fieldImage("FKEYS", ASCII)).hasSize(21);
            assertThat(request.fieldImage("FKEYSC", ASCII)).hasSize(18);
            assertThat(request.fieldValues()).containsKeys("FKEYS", "FKEYSC");
        }

        @Test
        @DisplayName("FKEYSC's attribute item is addressable, as COCRDUPC:1316 requires")
        void fkeyscAttributeItemIsAddressable() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.putFieldMetadata(
                    FieldMetadata.notTransmitted("FKEYSC").withAttributeItem("\u00d8"));
            assertThat(request.metadataFor("FKEYSC").attributeItem()).isEqualTo("\u00d8");
            assertThat(request.metadataFor("FKEYS").attributeItem()).isEqualTo("\u0000");
            assertThat(request.metadataFor("FKEYSC").declaredLength()).isEqualTo(18);
            assertThat(request.metadataFor("FKEYS").declaredLength()).isEqualTo(21);
        }
    }

    @Nested
    @DisplayName("Screen fields - null normalisation, verbatim storage and the PIC X projection")
    class ScreenFields {

        @Test
        @DisplayName("a new request holds every field at its declared width in spaces")
        void newRequestHoldsSpacesAtEveryDeclaredWidth() {
            CardUpdateRequest request = new CardUpdateRequest();
            assertThat(request.fieldValues()).hasSize(17);
            request.fieldValues().forEach((name, value) -> {
                assertThat(value).as(name).hasSize(CardUpdateRequest.declaredLength(name));
                assertThat(value.isBlank()).as(name).isTrue();
            });
            assertThat(request.getCommArea().changeAction().isDetailsNotFetched()).isTrue();
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
        }

        @Test
        @DisplayName("every setter round-trips its own field and touches no other")
        void everySetterRoundTripsItsOwnField() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setTrnname("CCUP");
            request.setTitle01("TITLE ONE");
            request.setCurdate("01/02/26");
            request.setPgmname("COCRDUPC");
            request.setTitle02("TITLE TWO");
            request.setCurtime("10:20:30");
            request.setAcctsid("00000000011");
            request.setCardsid("4111111111111111");
            request.setCrdname("A CARDHOLDER");
            request.setCrdstcd("Y");
            request.setExpmon("12");
            request.setExpyear("2029");
            request.setExpday("31");
            request.setInfomsg("an informational prompt");
            request.setErrmsg("an error");
            request.setFkeys("F3=Back");
            request.setFkeysc("F5=Save");
            assertThat(request.getTrnname()).isEqualTo("CCUP");
            assertThat(request.getTitle01()).isEqualTo("TITLE ONE");
            assertThat(request.getCurdate()).isEqualTo("01/02/26");
            assertThat(request.getPgmname()).isEqualTo("COCRDUPC");
            assertThat(request.getTitle02()).isEqualTo("TITLE TWO");
            assertThat(request.getCurtime()).isEqualTo("10:20:30");
            assertThat(request.getAcctsid()).isEqualTo("00000000011");
            assertThat(request.getCardsid()).isEqualTo("4111111111111111");
            assertThat(request.getCrdname()).isEqualTo("A CARDHOLDER");
            assertThat(request.getCrdstcd()).isEqualTo("Y");
            assertThat(request.getExpmon()).isEqualTo("12");
            assertThat(request.getExpyear()).isEqualTo("2029");
            assertThat(request.getExpday()).isEqualTo("31");
            assertThat(request.getInfomsg()).isEqualTo("an informational prompt");
            assertThat(request.getErrmsg()).isEqualTo("an error");
            assertThat(request.getFkeys()).isEqualTo("F3=Back");
            assertThat(request.getFkeysc()).isEqualTo("F5=Save");
        }

        @Test
        @DisplayName("null becomes that field's declared width in spaces, never Java null")
        void nullBecomesSpacesAtTheDeclaredWidth() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setTrnname(null);
            request.setTitle01(null);
            request.setCurdate(null);
            request.setPgmname(null);
            request.setTitle02(null);
            request.setCurtime(null);
            request.setAcctsid(null);
            request.setCardsid(null);
            request.setCrdname(null);
            request.setCrdstcd(null);
            request.setExpmon(null);
            request.setExpyear(null);
            request.setExpday(null);
            request.setInfomsg(null);
            request.setErrmsg(null);
            request.setFkeys(null);
            request.setFkeysc(null);
            request.fieldValues().forEach((name, value) -> assertThat(value).as(name)
                    .isEqualTo(" ".repeat(CardUpdateRequest.declaredLength(name))));
        }

        @Test
        @DisplayName("the three carriers accept a supplied instance and normalise null")
        void carriersAcceptAnInstanceAndNormaliseNull() {
            CardUpdateRequest request = new CardUpdateRequest();
            CommArea supplied = CommArea.initialised()
                    .withChangeAction(ChangeAction.showDetails());
            CardScreenState workArea = new CardScreenState();
            workArea.setCcardNextProg("COCRDLIC");
            NavigationContext context = NavigationContext.empty().withUserId("USER0001");

            request.setCommArea(supplied);
            request.setCardScreenState(workArea);
            request.setNavigationContext(context);
            assertThat(request.getCommArea()).isSameAs(supplied);
            assertThat(request.getCardScreenState()).isSameAs(workArea);
            assertThat(request.getNavigationContext()).isSameAs(context);

            request.setCommArea(null);
            request.setCardScreenState(null);
            request.setNavigationContext(null);
            assertThat(request.getCommArea())
                    .as("WS-THIS-PROGCOMMAREA is the program's own storage - INITIALIZE at "
                            + "COCRDUPC.cbl:391-392 - so it always exists")
                    .isEqualTo(CommArea.initialised());
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.getNavigationContext())
                    .as("DFHCOMMAREA is what the caller passed, and it may not have been passed at "
                            + "all; EIBCALEN = 0 is the first disjunct of COCRDUPC.cbl:388")
                    .isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();
        }

        @Test
        @DisplayName("fieldImages pads on the right, truncates on the right, and totals 353")
        void fieldImagesApplyTheCobolPicXMoveRule() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setAcctsid("123");
            request.setTrnname("CCUPXX");
            Map<String, String> images = request.fieldImages(ASCII);
            assertThat(images).hasSize(17);
            assertThat(images.get("ACCTSID")).isEqualTo("123        ");
            assertThat(images.get("TRNNAME")).isEqualTo("CCUP");
            assertThat(images.values().stream().mapToInt(String::length).sum()).isEqualTo(353);
            images.forEach((name, image) -> assertThat(image).as(name)
                    .hasSize(CardUpdateRequest.declaredLength(name)));
        }

        @Test
        @DisplayName("only @Size is applied - no digit, pattern or presence check pre-empts "
                + "COCRDUPC's own edits")
        void onlySizeIsApplied() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                assertThat(validator.validate(new CardUpdateRequest())).isEmpty();

                CardUpdateRequest tooWide = new CardUpdateRequest();
                tooWide.setFkeysc("X".repeat(19));
                assertThat(validator.validate(tooWide)).hasSize(1);

                CardUpdateRequest nonNumericAndBlank = new CardUpdateRequest();
                nonNumericAndBlank.setAcctsid("");
                nonNumericAndBlank.setCardsid("NOT A NUMBER");
                nonNumericAndBlank.setExpmon("ZZ");
                nonNumericAndBlank.setCrdstcd("?");
                assertThat(validator.validate(nonNumericAndBlank)).isEmpty();
            }
        }

        @Test
        @DisplayName("the full constructor populates all 20 members and defaults every null")
        void fullConstructorPopulatesEverythingAndDefaultsNull() {
            CommArea commArea = CommArea.initialised()
                    .withChangeAction(ChangeAction.showDetails());
            CardUpdateRequest request = new CardUpdateRequest("CCUP", "T1", "01/02/26",
                    "COCRDUPC", "T2", "10:20:30", "00000000011", "4111111111111111",
                    "A CARDHOLDER", "Y", "12", "2029", "31", "info", "err", "F3=Back", "F5=Save",
                    commArea, new CardScreenState(), NavigationContext.empty());
            assertThat(request.getTrnname()).isEqualTo("CCUP");
            assertThat(request.getExpday()).isEqualTo("31");
            assertThat(request.getFkeysc()).isEqualTo("F5=Save");
            assertThat(request.getCommArea().changeAction().isShowDetails()).isTrue();

            CardUpdateRequest defaulted = new CardUpdateRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
            assertThat(defaulted.fieldValues()).hasSize(17);
            assertThat(defaulted.getCommArea()).isEqualTo(CommArea.initialised());
            assertThat(defaulted.getCardScreenState()).isNotNull();
            assertThat(defaulted.getNavigationContext())
                    .as("a null commarea argument means none was passed, which the constructor keeps")
                    .isNull();
        }

        @Test
        @DisplayName("the copy constructor snapshots the values and deep-copies the work area")
        void copyConstructorSnapshotsAndDeepCopiesTheWorkArea() {
            CardUpdateRequest original = new CardUpdateRequest();
            original.setCardsid("4111111111111111");
            original.putFieldMetadata(FieldMetadata.cursorAt("CRDNAME"));
            original.getCardScreenState().setCcardNextProg("COCRDLIC");
            CardUpdateRequest copy = new CardUpdateRequest(original);
            original.setCardsid("9999999999999999");
            original.getCardScreenState().setCcardNextProg("COMEN01C");
            assertThat(copy.getCardsid()).isEqualTo("4111111111111111");
            assertThat(copy.getCardScreenState().getCcardNextProg()).isEqualTo("COCRDLIC");
            assertThat(copy.metadataFor("CRDNAME").cursorRequested()).isTrue();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardUpdateRequest((CardUpdateRequest) null));
        }

        @Test
        @DisplayName("ENTER and REENTER are carried by the navigation context, never a session")
        void enterAndReenterAreCarriedInThePayload() {
            CardUpdateRequest request = new CardUpdateRequest();
            assertThat(request.isEnter())
                    .as("with no communication area there is no CDEMO-PGM-CONTEXT to test, so the "
                            + "condition name is false rather than true - three states, not two")
                    .isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);

            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.isEnter()).isTrue();
            assertThat(request.commareaLength()).isEqualTo(NavigationContext.COMMAREA_LENGTH);

            request.setNavigationContext(NavigationContext.empty()
                    .withPgmContext(NavigationContext.PGM_CONTEXT_REENTER));
            assertThat(request.isReenter()).isTrue();
            assertThat(request.isEnter()).isFalse();

            request.setNavigationContext(NavigationContext.empty().withPgmContext(4));
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(4);
        }

        @Test
        @DisplayName("the disclosure policy names the card number, the account key and the embossed name")
        void theDisclosurePolicyNamesEverySensitiveField() {
            assertThat(CardUpdateRequest.disclosureOf("CARDSID"))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.PAN);
            assertThat(CardUpdateRequest.disclosureOf("ACCTSID"))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.IDENTIFIER);
            assertThat(CardUpdateRequest.disclosureOf("CRDNAME"))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.TEXT);
            assertThat(CardUpdateRequest.disclosureOf("CRDSTCD"))
                    .as("a status code identifies nobody and is needed for validation parity")
                    .isEqualTo(SensitiveDiagnostics.Disclosure.PLAIN);
            assertThat(CardUpdateRequest.disclosureOf(null))
                    .as("an unnamed field is withheld, not published")
                    .isEqualTo(SensitiveDiagnostics.Disclosure.REDACTED_VALUE);
        }

        @Test
        @DisplayName("toString names the map and masks no value")
        void toStringNamesTheMapAndMasksNothing() {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setCardsid("4111111111111111");
            assertThat(request.toString()).startsWith("CCRDUPA[")
                    .doesNotContain("4111111111111111")
                    .contains("CARDSID='************1111'")
                    .contains("FKEYSC=")
                    .contains("commArea=")
                    .contains("cardScreenState=")
                    .contains("navigationContext=");
        }

        @Test
        @DisplayName("the accessors and the payload carry the identifiers in the clear")
        void theAccessorsAndPayloadCarryTheIdentifiersInTheClear() throws Exception {
            CardUpdateRequest request = new CardUpdateRequest();
            request.setCardsid("4111111111111111");
            request.setAcctsid("00000000011");

            assertThat(request.getCardsid()).isEqualTo("4111111111111111");
            assertThat(request.fieldValues())
                    .containsEntry("CARDSID", "4111111111111111")
                    .containsEntry("ACCTSID", "00000000011");
            assertThat(new ObjectMapper().writeValueAsString(request))
                    .as("the 3270 shows both in the clear and the payload must too")
                    .contains("4111111111111111")
                    .contains("00000000011")
                    .doesNotContain("REDACTED");
        }
    }

    @Nested
    @DisplayName("CCUP-CHANGE-ACTION - one byte and all nine of its 88-levels")
    class ChangeActionConditions {

        @Test
        @DisplayName("condition 1: DETAILS-NOT-FETCHED is true for LOW-VALUES and for SPACES")
        void detailsNotFetchedCoversLowValuesAndSpaces() {
            assertThat(ChangeAction.RECORD_LENGTH).isEqualTo(1);
            assertThat(ChangeAction.FIELD_NAME).isEqualTo("CCUP-CHANGE-ACTION");
            assertThat(ChangeAction.initial().value()).isEqualTo("\u0000");
            assertThat(ChangeAction.initial().isDetailsNotFetched()).isTrue();
            assertThat(ChangeAction.spacesState().isDetailsNotFetched()).isTrue();
            assertThat(ChangeAction.initial()).isNotEqualTo(ChangeAction.spacesState());
            assertThat(ChangeAction.DETAILS_NOT_FETCHED_VALUES).containsExactly("\u0000", " ");
            for (String other : List.of("S", "E", "N", "C", "L", "F", "X")) {
                assertThat(ChangeAction.of(other).isDetailsNotFetched()).as(other).isFalse();
            }
        }

        @Test
        @DisplayName("conditions 2, 4, 5, 6, 8 and 9: the single-valued levels")
        void everySingleValuedLevel() {
            assertThat(ChangeAction.showDetails().value()).isEqualTo("S");
            assertThat(ChangeAction.showDetails().isShowDetails()).isTrue();
            assertThat(ChangeAction.changesNotOk().value()).isEqualTo("E");
            assertThat(ChangeAction.changesNotOk().isChangesNotOk()).isTrue();
            assertThat(ChangeAction.changesOkNotConfirmed().value()).isEqualTo("N");
            assertThat(ChangeAction.changesOkNotConfirmed().isChangesOkNotConfirmed()).isTrue();
            assertThat(ChangeAction.changesOkayedAndDone().value()).isEqualTo("C");
            assertThat(ChangeAction.changesOkayedAndDone().isChangesOkayedAndDone()).isTrue();
            assertThat(ChangeAction.changesOkayedLockError().value()).isEqualTo("L");
            assertThat(ChangeAction.changesOkayedLockError().isChangesOkayedLockError()).isTrue();
            assertThat(ChangeAction.changesOkayedButFailed().value()).isEqualTo("F");
            assertThat(ChangeAction.changesOkayedButFailed().isChangesOkayedButFailed()).isTrue();
            assertThat(ChangeAction.of('S').isShowDetails()).isTrue();
        }

        @Test
        @DisplayName("condition 3: CHANGES-MADE groups exactly E, N, C, L and F")
        void changesMadeGroupsFiveValues() {
            assertThat(ChangeAction.CHANGES_MADE_VALUES)
                    .containsExactly("E", "N", "C", "L", "F");
            for (String value : List.of("E", "N", "C", "L", "F")) {
                assertThat(ChangeAction.of(value).isChangesMade()).as(value).isTrue();
            }
            for (String value : List.of("\u0000", " ", "S", "X")) {
                assertThat(ChangeAction.of(value).isChangesMade()).as(value).isFalse();
            }
        }

        @Test
        @DisplayName("condition 7: CHANGES-FAILED groups exactly L and F")
        void changesFailedGroupsTwoValues() {
            assertThat(ChangeAction.CHANGES_FAILED_VALUES).containsExactly("L", "F");
            for (String value : List.of("L", "F")) {
                assertThat(ChangeAction.of(value).isChangesFailed()).as(value).isTrue();
            }
            for (String value : List.of("\u0000", " ", "S", "E", "N", "C", "X")) {
                assertThat(ChangeAction.of(value).isChangesFailed()).as(value).isFalse();
            }
        }

        @Test
        @DisplayName("the overlap between the grouping levels and the single levels is preserved")
        void theOverlapIsPreserved() {
            ChangeAction lockError = ChangeAction.changesOkayedLockError();
            assertThat(lockError.isChangesMade()).isTrue();
            assertThat(lockError.isChangesFailed()).isTrue();
            assertThat(lockError.isChangesOkayedLockError()).isTrue();
            assertThat(lockError.isChangesOkayedButFailed()).isFalse();

            ChangeAction notOk = ChangeAction.changesNotOk();
            assertThat(notOk.isChangesMade()).isTrue();
            assertThat(notOk.isChangesFailed()).isFalse();
        }

        @Test
        @DisplayName("an unrecognised byte is accepted, so 2000-DECIDE-ACTION's WHEN OTHER "
                + "abend stays reachable")
        void anUnrecognisedByteIsAccepted() {
            ChangeAction unexpected = ChangeAction.of('X');
            assertThat(unexpected.isRecognised()).isFalse();
            assertThat(unexpected.isDetailsNotFetched()).isFalse();
            assertThat(unexpected.isShowDetails()).isFalse();
            assertThat(unexpected.isChangesMade()).isFalse();
            assertThat(ChangeAction.initial().isRecognised()).isTrue();
            assertThat(ChangeAction.spacesState().isRecognised()).isTrue();
            assertThat(ChangeAction.showDetails().isRecognised()).isTrue();
            assertThat(ChangeAction.changesNotOk().isRecognised()).isTrue();
        }

        @Test
        @DisplayName("toString names LOW-VALUES and quotes any other byte")
        void toStringNamesLowValues() {
            assertThat(ChangeAction.initial().toString())
                    .isEqualTo("CCUP-CHANGE-ACTION=LOW-VALUES");
            assertThat(ChangeAction.showDetails().toString())
                    .isEqualTo("CCUP-CHANGE-ACTION='S'");
            assertThat(ChangeAction.spacesState().toString())
                    .isEqualTo("CCUP-CHANGE-ACTION=' '");
        }

        @Test
        @DisplayName("null and anything other than one byte are rejected")
        void nullAndMultiByteAreRejected() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ChangeAction.of((String) null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ChangeAction.of("SS"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> ChangeAction.of(""));
        }
    }

    @Nested
    @DisplayName("CCUP-OLD-DETAILS and CCUP-NEW-DETAILS - 89 bytes each, same shape, "
            + "different names")
    class Snapshots {

        @Test
        @DisplayName("the group is 89 bytes and its declared offsets are contiguous")
        void theGroupIsEightyNineBytes() {
            assertThat(CardDetails.RECORD_LENGTH).isEqualTo(89);
            assertThat(CardDetails.ACCTID_OFFSET).isZero();
            assertThat(CardDetails.ACCTID_LENGTH).isEqualTo(11);
            assertThat(CardDetails.CARDID_OFFSET).isEqualTo(11);
            assertThat(CardDetails.CARDID_LENGTH).isEqualTo(16);
            assertThat(CardDetails.CVV_CD_OFFSET).isEqualTo(27);
            assertThat(CardDetails.CVV_CD_LENGTH).isEqualTo(3);
            assertThat(CardDetails.CRDNAME_OFFSET).isEqualTo(30);
            assertThat(CardDetails.CRDNAME_LENGTH).isEqualTo(50);
            assertThat(CardDetails.EXPYEAR_OFFSET).isEqualTo(80);
            assertThat(CardDetails.EXPMON_OFFSET).isEqualTo(84);
            assertThat(CardDetails.EXPDAY_OFFSET).isEqualTo(86);
            assertThat(CardDetails.CRDSTCD_OFFSET).isEqualTo(88);
            assertThat(CardDetails.CARDDATA_OFFSET).isEqualTo(30);
            assertThat(CardDetails.CARDDATA_LENGTH).isEqualTo(59);
            assertThat(CardDetails.EXPIRAION_DATE_OFFSET).isEqualTo(80);
            assertThat(CardDetails.EXPIRAION_DATE_LENGTH).isEqualTo(8);
            assertThat(11 + 16 + 3 + 50 + 4 + 2 + 2 + 1).isEqualTo(CardDetails.RECORD_LENGTH);
        }

        @Test
        @DisplayName("each group carries its own verbatim item names, misspelling included")
        void eachGroupCarriesItsOwnVerbatimNames() {
            assertThat(DetailGroup.OLD.prefix()).isEqualTo("CCUP-OLD-");
            assertThat(DetailGroup.NEW.prefix()).isEqualTo("CCUP-NEW-");
            assertThat(DetailGroup.OLD.groupName()).isEqualTo("CCUP-OLD-DETAILS");
            assertThat(DetailGroup.NEW.groupName()).isEqualTo("CCUP-NEW-DETAILS");
            assertThat(DetailGroup.OLD.acctidSpan().name()).isEqualTo("CCUP-OLD-ACCTID");
            assertThat(DetailGroup.OLD.cardidSpan().name()).isEqualTo("CCUP-OLD-CARDID");
            assertThat(DetailGroup.OLD.cvvCdSpan().name()).isEqualTo("CCUP-OLD-CVV-CD");
            assertThat(DetailGroup.OLD.crdnameSpan().name()).isEqualTo("CCUP-OLD-CRDNAME");
            assertThat(DetailGroup.OLD.expyearSpan().name()).isEqualTo("CCUP-OLD-EXPYEAR");
            assertThat(DetailGroup.OLD.expmonSpan().name()).isEqualTo("CCUP-OLD-EXPMON");
            assertThat(DetailGroup.OLD.expdaySpan().name()).isEqualTo("CCUP-OLD-EXPDAY");
            assertThat(DetailGroup.OLD.crdstcdSpan().name()).isEqualTo("CCUP-OLD-CRDSTCD");
            assertThat(DetailGroup.OLD.carddataSpan().name()).isEqualTo("CCUP-OLD-CARDDATA");
            assertThat(DetailGroup.OLD.expiraionDateSpan().name())
                    .isEqualTo("CCUP-OLD-EXPIRAION-DATE");
            assertThat(DetailGroup.NEW.expiraionDateSpan().name())
                    .isEqualTo("CCUP-NEW-EXPIRAION-DATE");
            assertThat(DetailGroup.NEW.qualify("CVV-CD")).isEqualTo("CCUP-NEW-CVV-CD");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> DetailGroup.OLD.qualify(null));
        }

        @Test
        @DisplayName("the layout declares eight storage spans and two group overlays")
        void theLayoutDeclaresEightSpansAndTwoOverlays() {
            for (DetailGroup group : DetailGroup.values()) {
                assertThat(group.layout().recordLength()).as(group.name()).isEqualTo(89);
                assertThat(group.layout().storageSpans()).as(group.name()).hasSize(8);
                assertThat(group.layout().redefinitions()).as(group.name()).hasSize(2);
                assertThat(group.layout().storageSpans().stream()
                        .mapToInt(FieldSpan::length).sum()).as(group.name()).isEqualTo(89);
            }
        }

        @Test
        @DisplayName("89 bytes round-trip losslessly in US-ASCII and in IBM037")
        void roundTripsInBothCodePages() {
            CardDetails details = CardDetails.initialised(DetailGroup.NEW)
                    .withAcctid("00000000011")
                    .withCardid("4111111111111111")
                    .withCvvCd("123")
                    .withCrdname("A CARDHOLDER")
                    .withExpyear("2029")
                    .withExpmon("12")
                    .withExpday("31")
                    .withCrdstcd("Y");
            assertThat(details.encode(ASCII)).hasSize(89);
            assertThat(details.encode(StandardCharsets.US_ASCII)).hasSize(89);
            assertThat(details.encode(IBM037)).hasSize(89);
            assertThat(CardDetails.decode(details.encode(ASCII), DetailGroup.NEW, ASCII))
                    .isEqualTo(details);
            assertThat(CardDetails.decode(details.encode(EBCDIC), DetailGroup.NEW, EBCDIC))
                    .isEqualTo(details);
            FixedWidthRecord area = details.toFixedWidthRecord(ASCII);
            assertThat(area.recordLength()).isEqualTo(89);
            assertThat(CardDetails.decode(area, DetailGroup.NEW, ASCII)).isEqualTo(details);
            assertThat(details.acctid()).hasSize(11);
            assertThat(details.crdname()).hasSize(50);
            assertThat(details.cvvCd()).isEqualTo("123");
        }

        @Test
        @DisplayName("the snapshot date is eight bytes with no separators, and CARDDATA is 59")
        void theSnapshotDateHasNoSeparators() {
            CardDetails details = CardDetails.initialised(DetailGroup.OLD)
                    .withExpyear("2029").withExpmon("12").withExpday("31");
            assertThat(details.ccupExpiraionDate()).isEqualTo("20291231").hasSize(8)
                    .doesNotContain("-");
            assertThat(details.ccupCarddata()).hasSize(59)
                    .endsWith("20291231 ");
        }

        @Test
        @DisplayName("itemValues keys on the verbatim names and includes both group items")
        void itemValuesKeysOnVerbatimNames() {
            assertThat(CardDetails.initialised(DetailGroup.OLD).itemValues()).hasSize(10)
                    .containsKeys("CCUP-OLD-ACCTID", "CCUP-OLD-CARDID", "CCUP-OLD-CVV-CD",
                            "CCUP-OLD-CRDNAME", "CCUP-OLD-EXPYEAR", "CCUP-OLD-EXPMON",
                            "CCUP-OLD-EXPDAY", "CCUP-OLD-CRDSTCD", "CCUP-OLD-CARDDATA",
                            "CCUP-OLD-EXPIRAION-DATE");
            assertThat(CardDetails.initialised(DetailGroup.NEW).itemValues())
                    .containsKeys("CCUP-NEW-EXPIRAION-DATE");
            assertThat(CardDetails.initialised(DetailGroup.OLD).itemName("CVV-CD"))
                    .isEqualTo("CCUP-OLD-CVV-CD");
        }

        @Test
        @DisplayName("asGroup relabels the same bytes and is identity for its own group")
        void asGroupRelabelsTheSameBytes() {
            CardDetails newDetails = CardDetails.initialised(DetailGroup.NEW).withCvvCd("123");
            CardDetails relabelled = newDetails.asGroup(DetailGroup.OLD);
            assertThat(relabelled.group()).isEqualTo(DetailGroup.OLD);
            assertThat(relabelled.cvvCd()).isEqualTo("123");
            assertThat(relabelled.encode(ASCII)).isEqualTo(newDetails.encode(ASCII));
            assertThat(newDetails.asGroup(DetailGroup.NEW)).isSameAs(newDetails);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> newDetails.asGroup(null));
        }

        @Test
        @DisplayName("every with* copy replaces one item and preserves the rest")
        void everyWithCopyReplacesOneItem() {
            CardDetails base = CardDetails.initialised(DetailGroup.OLD);
            assertThat(base.withAcctid("00000000011").acctid()).startsWith("00000000011");
            assertThat(base.withCardid("4111111111111111").cardid())
                    .isEqualTo("4111111111111111");
            assertThat(base.withCvvCd("123").cvvCd()).isEqualTo("123");
            assertThat(base.withCrdname("A NAME").crdname()).startsWith("A NAME");
            assertThat(base.withExpyear("2029").expyear()).isEqualTo("2029");
            assertThat(base.withExpmon("12").expmon()).isEqualTo("12");
            assertThat(base.withExpday("31").expday()).isEqualTo("31");
            assertThat(base.withCrdstcd("Y").crdstcd()).isEqualTo("Y");
            assertThat(base.withCvvCd("1").cvvCd()).isEqualTo("1  ");
        }

        @Test
        @DisplayName("an over-wide item and a null group are rejected")
        void overWideAndNullGroupAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> CardDetails.initialised(DetailGroup.OLD).withCvvCd("1234"));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> CardDetails.initialised(DetailGroup.OLD).withCrdname("X".repeat(51)));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardDetails.initialised(null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardDetails.initialised(DetailGroup.OLD).encode((FixedWidthCodec) null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardDetails.decode((byte[]) null, DetailGroup.OLD, ASCII));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardDetails.decode(new byte[89], null, ASCII));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardDetails.decode(new byte[89], DetailGroup.OLD, null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardDetails.decode((FixedWidthRecord) null, DetailGroup.OLD, ASCII));
        }
    }

    @Nested
    @DisplayName("CARD-UPDATE-RECORD - 150 bytes, modelled inline, FILLER emitted")
    class UpdateRecord {

        @Test
        @DisplayName("the record is 150 bytes with a seven-span layout ending in FILLER X(59)")
        void theRecordIsOneFiftyBytes() {
            assertThat(CardUpdateRecord.RECORD_LENGTH).isEqualTo(150);
            assertThat(CardUpdateRecord.CARD_UPDATE_NUM_OFFSET).isZero();
            assertThat(CardUpdateRecord.CARD_UPDATE_ACCT_ID_OFFSET).isEqualTo(16);
            assertThat(CardUpdateRecord.CARD_UPDATE_CVV_CD_OFFSET).isEqualTo(27);
            assertThat(CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME_OFFSET).isEqualTo(30);
            assertThat(CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE_OFFSET).isEqualTo(80);
            assertThat(CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS_OFFSET).isEqualTo(90);
            assertThat(CardUpdateRecord.FILLER_OFFSET).isEqualTo(91);
            assertThat(CardUpdateRecord.FILLER_LENGTH).isEqualTo(59);
            assertThat(CardUpdateRecord.LAYOUT.recordLength()).isEqualTo(150);
            assertThat(CardUpdateRecord.LAYOUT.spans()).hasSize(7);
            assertThat(CardUpdateRecord.FILLER.kind().filler()).isTrue();
            assertThat(16 + 11 + 3 + 50 + 10 + 1 + 59).isEqualTo(150);
        }

        @Test
        @DisplayName("the trailing FILLER is present and space-filled in both code pages")
        void theTrailingFillerIsPresentAndSpaceFilled() {
            CardUpdateRecord record = populated();
            byte[] ascii = record.encode(ASCII);
            assertThat(ascii).hasSize(150);
            String image = new String(ascii, StandardCharsets.US_ASCII);
            assertThat(image.substring(91)).isEqualTo(" ".repeat(59));
            assertThat(image.substring(16, 27)).isEqualTo("00000000011");
            assertThat(image.substring(27, 30)).isEqualTo("123");
            assertThat(image.substring(80, 90)).isEqualTo("2029-12-31");

            byte[] ebcdic = record.encode(IBM037);
            assertThat(ebcdic).hasSize(150);
            for (int index = 91; index < 150; index++) {
                assertThat(ebcdic[index]).as("IBM037 filler byte " + index)
                        .isEqualTo(EBCDIC_SPACE);
            }
        }

        @Test
        @DisplayName("150 bytes round-trip losslessly in US-ASCII and in IBM037")
        void roundTripsInBothCodePages() {
            CardUpdateRecord record = populated();
            assertThat(CardUpdateRecord.decode(record.encode(ASCII), ASCII)).isEqualTo(record);
            assertThat(CardUpdateRecord.decode(record.encode(IBM037), IBM037)).isEqualTo(record);
            assertThat(CardUpdateRecord.decode(record.encode(EBCDIC), EBCDIC)).isEqualTo(record);
            FixedWidthRecord area = record.toFixedWidthRecord(ASCII);
            assertThat(area.recordLength()).isEqualTo(150);
            assertThat(CardUpdateRecord.decode(area, ASCII)).isEqualTo(record);
        }

        @Test
        @DisplayName("the item names are the CARD-UPDATE-* set, with the EXPIRAION misspelling")
        void theItemNamesAreTheCardUpdateSet() {
            assertThat(CardUpdateRecord.CARD_UPDATE_NUM.name()).isEqualTo("CARD-UPDATE-NUM");
            assertThat(CardUpdateRecord.CARD_UPDATE_ACCT_ID.name())
                    .isEqualTo("CARD-UPDATE-ACCT-ID");
            assertThat(CardUpdateRecord.CARD_UPDATE_CVV_CD.name())
                    .isEqualTo("CARD-UPDATE-CVV-CD");
            assertThat(CardUpdateRecord.CARD_UPDATE_EMBOSSED_NAME.name())
                    .isEqualTo("CARD-UPDATE-EMBOSSED-NAME");
            assertThat(CardUpdateRecord.CARD_UPDATE_EXPIRAION_DATE.name())
                    .isEqualTo("CARD-UPDATE-EXPIRAION-DATE");
            assertThat(CardUpdateRecord.CARD_UPDATE_ACTIVE_STATUS.name())
                    .isEqualTo("CARD-UPDATE-ACTIVE-STATUS");
            assertThat(CardUpdateRecord.LAYOUT.spans().stream().map(FieldSpan::name))
                    .noneMatch(name -> name.contains("EXPIRATION"));
        }

        @Test
        @DisplayName("the two numeric items are PIC 9 DISPLAY and render as zoned images")
        void theTwoNumericItemsAreZonedDisplay() {
            assertThat(CardUpdateRecord.CARD_UPDATE_ACCT_ID.kind().numericDisplay()).isTrue();
            assertThat(CardUpdateRecord.CARD_UPDATE_CVV_CD.kind().numericDisplay()).isTrue();
            CardUpdateRecord record = CardUpdateRecord.initialised()
                    .withCardUpdateAcctId(11L).withCardUpdateCvvCd(7);
            assertThat(record.cardUpdateAcctIdImage(ASCII)).isEqualTo("00000000011");
            assertThat(record.cardUpdateCvvCdImage(ASCII)).isEqualTo("007");
            assertThat(record.itemValues(ASCII)).hasSize(6).doesNotContainKey("FILLER")
                    .containsKeys("CARD-UPDATE-NUM", "CARD-UPDATE-ACCT-ID", "CARD-UPDATE-CVV-CD",
                            "CARD-UPDATE-EMBOSSED-NAME", "CARD-UPDATE-EXPIRAION-DATE",
                            "CARD-UPDATE-ACTIVE-STATUS");
        }

        @Test
        @DisplayName("initialised() is the INITIALIZE state: PIC X spaces and PIC 9 zeroes")
        void initialisedIsTheInitializeState() {
            CardUpdateRecord record = CardUpdateRecord.initialised();
            assertThat(record.cardUpdateNum()).isEqualTo(" ".repeat(16));
            assertThat(record.cardUpdateEmbossedName()).isEqualTo(" ".repeat(50));
            assertThat(record.cardUpdateExpiraionDate()).isEqualTo(" ".repeat(10));
            assertThat(record.cardUpdateActiveStatus()).isEqualTo(" ");
            assertThat(record.cardUpdateAcctId()).isZero();
            assertThat(record.cardUpdateCvvCd()).isZero();
            assertThat(record.encode(ASCII)).hasSize(150);
        }

        @Test
        @DisplayName("staging reproduces 9200-WRITE-PROCESSING, separators and CVV cast included")
        void stagingReproducesWriteProcessing() {
            CardDetails newDetails = newSnapshot();
            CardUpdateRecord staged = CardUpdateRecord.staging(newDetails, 11L, ASCII);
            assertThat(staged.cardUpdateNum()).isEqualTo("4111111111111111");
            assertThat(staged.cardUpdateAcctId()).isEqualTo(11L);
            assertThat(staged.cardUpdateCvvCd()).isEqualTo(123);
            assertThat(staged.cardUpdateEmbossedName()).startsWith("A CARDHOLDER").hasSize(50);
            assertThat(staged.cardUpdateExpiraionDate()).isEqualTo("2029-12-31");
            assertThat(staged.cardUpdateActiveStatus()).isEqualTo("Y");
            assertThat(CardUpdateRecord.compose(newDetails, ASCII)).isEqualTo("2029-12-31");
            assertThat(CardUpdateRecord.EXPIRAION_DATE_SEPARATOR).isEqualTo("-");
            assertThat(staged.encode(ASCII)).hasSize(150);
        }

        @Test
        @DisplayName("staging and matchesSnapshot refuse a snapshot from the wrong group")
        void stagingRefusesTheWrongGroup() {
            CardDetails newDetails = newSnapshot();
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    CardUpdateRecord.staging(newDetails.asGroup(DetailGroup.OLD), 11L, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.staging(null, 11L, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.staging(newDetails, 11L, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.compose(null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.compose(newDetails, null));
        }

        @Test
        @DisplayName("the reference-modification slices translate (1:4), (6:2) and (9:2)")
        void referenceModificationSlices() {
            CardUpdateRecord record = CardUpdateRecord.initialised()
                    .withCardUpdateExpiraionDate("2029-12-31");
            assertThat(record.cardUpdateExpiraionDateYear()).isEqualTo("2029");
            assertThat(record.cardUpdateExpiraionDateMonth()).isEqualTo("12");
            assertThat(record.cardUpdateExpiraionDateDay()).isEqualTo("31");
            assertThat(CardUpdateRecord.EXPIRAION_YEAR_BEGIN_INDEX).isZero();
            assertThat(CardUpdateRecord.EXPIRAION_YEAR_END_INDEX).isEqualTo(4);
            assertThat(CardUpdateRecord.EXPIRAION_MONTH_BEGIN_INDEX).isEqualTo(5);
            assertThat(CardUpdateRecord.EXPIRAION_MONTH_END_INDEX).isEqualTo(7);
            assertThat(CardUpdateRecord.EXPIRAION_DAY_BEGIN_INDEX).isEqualTo(8);
            assertThat(CardUpdateRecord.EXPIRAION_DAY_END_INDEX).isEqualTo(10);
        }

        @Test
        @DisplayName("matchesSnapshot compares the six items of 9300-CHECK-CHANGE-IN-REC "
                + "and no others")
        void matchesSnapshotComparesSixItems() {
            CardDetails oldDetails = newSnapshot().asGroup(DetailGroup.OLD);
            CardUpdateRecord unchanged = CardUpdateRecord.initialised()
                    .withCardUpdateCvvCd(123)
                    .withCardUpdateEmbossedName("A CARDHOLDER")
                    .withCardUpdateExpiraionDate("2029-12-31")
                    .withCardUpdateActiveStatus("Y");
            assertThat(unchanged.matchesSnapshot(oldDetails, ASCII)).isTrue();
            assertThat(unchanged.withCardUpdateCvvCd(124).matchesSnapshot(oldDetails, ASCII))
                    .isFalse();
            assertThat(unchanged.withCardUpdateEmbossedName("B CARDHOLDER")
                    .matchesSnapshot(oldDetails, ASCII)).isFalse();
            assertThat(unchanged.withCardUpdateExpiraionDate("2030-12-31")
                    .matchesSnapshot(oldDetails, ASCII)).isFalse();
            assertThat(unchanged.withCardUpdateExpiraionDate("2029-11-31")
                    .matchesSnapshot(oldDetails, ASCII)).isFalse();
            assertThat(unchanged.withCardUpdateExpiraionDate("2029-12-30")
                    .matchesSnapshot(oldDetails, ASCII)).isFalse();
            assertThat(unchanged.withCardUpdateActiveStatus("N")
                    .matchesSnapshot(oldDetails, ASCII)).isFalse();
            assertThat(unchanged.withCardUpdateNum("9999999999999999")
                    .matchesSnapshot(oldDetails, ASCII)).isTrue();
            assertThat(unchanged.withCardUpdateAcctId(99L)
                    .matchesSnapshot(oldDetails, ASCII)).isTrue();
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    unchanged.matchesSnapshot(oldDetails.asGroup(DetailGroup.NEW), ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> unchanged.matchesSnapshot(null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> unchanged.matchesSnapshot(oldDetails, null));
        }

        @Test
        @DisplayName("every with* copy replaces one item and preserves the rest")
        void everyWithCopyReplacesOneItem() {
            CardUpdateRecord base = CardUpdateRecord.initialised();
            assertThat(base.withCardUpdateNum("4111").cardUpdateNum()).startsWith("4111");
            assertThat(base.withCardUpdateAcctId(11L).cardUpdateAcctId()).isEqualTo(11L);
            assertThat(base.withCardUpdateCvvCd(123).cardUpdateCvvCd()).isEqualTo(123);
            assertThat(base.withCardUpdateEmbossedName("N").cardUpdateEmbossedName())
                    .startsWith("N");
            assertThat(base.withCardUpdateExpiraionDate("2029-12-31")
                    .cardUpdateExpiraionDate()).isEqualTo("2029-12-31");
            assertThat(base.withCardUpdateActiveStatus("Y").cardUpdateActiveStatus())
                    .isEqualTo("Y");
        }

        @Test
        @DisplayName("negative, over-wide and over-long values are rejected, never truncated")
        void impossibleValuesAreRejected() {
            assertThat(CardUpdateRecord.CARD_UPDATE_ACCT_ID_EXCLUSIVE_LIMIT)
                    .isEqualTo(100_000_000_000L);
            assertThat(CardUpdateRecord.CARD_UPDATE_CVV_CD_EXCLUSIVE_LIMIT).isEqualTo(1_000);
            CardUpdateRecord base = CardUpdateRecord.initialised();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateAcctId(-1L));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateAcctId(100_000_000_000L));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateCvvCd(-1));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateCvvCd(1000));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateNum("X".repeat(17)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateEmbossedName("X".repeat(51)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateExpiraionDate("X".repeat(11)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> base.withCardUpdateActiveStatus("YY"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> base.encode((FixedWidthCodec) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> base.toFixedWidthRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> base.itemValues(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> base.cardUpdateAcctIdImage(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> base.cardUpdateCvvCdImage(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.decode((byte[]) null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.decode(new byte[150],
                            (FixedWidthCodec) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardUpdateRecord.decode((FixedWidthRecord) null, ASCII));
        }

        private CardUpdateRecord populated() {
            return CardUpdateRecord.initialised()
                    .withCardUpdateNum("4111111111111111")
                    .withCardUpdateAcctId(11L)
                    .withCardUpdateCvvCd(123)
                    .withCardUpdateEmbossedName("A CARDHOLDER")
                    .withCardUpdateExpiraionDate("2029-12-31")
                    .withCardUpdateActiveStatus("Y");
        }
    }

    @Nested
    @DisplayName("WS-THIS-PROGCOMMAREA - 329 bytes as 1 + 89 + 89 + 150")
    class ProgramCommArea {

        @Test
        @DisplayName("the declared decomposition sums to 329 and the layout enforces it")
        void theDecompositionSumsTo329() {
            assertThat(CommArea.RECORD_LENGTH).isEqualTo(329);
            assertThat(CommArea.CHANGE_ACTION_OFFSET).isZero();
            assertThat(CommArea.OLD_DETAILS_OFFSET).isEqualTo(1);
            assertThat(CommArea.NEW_DETAILS_OFFSET).isEqualTo(90);
            assertThat(CommArea.CARD_UPDATE_RECORD_OFFSET).isEqualTo(179);
            assertThat(ChangeAction.RECORD_LENGTH + CardDetails.RECORD_LENGTH
                    + CardDetails.RECORD_LENGTH + CardUpdateRecord.RECORD_LENGTH)
                    .isEqualTo(CommArea.RECORD_LENGTH);
            assertThat(CommArea.LAYOUT.recordLength()).isEqualTo(329);
            assertThat(CommArea.LAYOUT.storageSpans().stream()
                    .mapToInt(FieldSpan::length).sum()).isEqualTo(329);
        }

        @Test
        @DisplayName("all four 05-level group names have a descriptor")
        void allFourGroupNamesHaveADescriptor() {
            assertThat(CommArea.SCREEN_DATA_GROUP_NAME).isEqualTo("CARD-UPDATE-SCREEN-DATA");
            assertThat(CommArea.CARD_UPDATE_RECORD_GROUP_NAME).isEqualTo("CARD-UPDATE-RECORD");
            assertThat(CommArea.CCUP_CHANGE_ACTION.name()).isEqualTo("CCUP-CHANGE-ACTION");
            assertThat(CommArea.LAYOUT.hasSpan("CARD-UPDATE-SCREEN-DATA")).isTrue();
            assertThat(CommArea.LAYOUT.hasSpan("CCUP-OLD-DETAILS")).isTrue();
            assertThat(CommArea.LAYOUT.hasSpan("CCUP-NEW-DETAILS")).isTrue();
            assertThat(CommArea.LAYOUT.hasSpan("CARD-UPDATE-RECORD")).isTrue();
            assertThat(CommArea.LAYOUT.redefinitions()).hasSize(8);
        }

        @Test
        @DisplayName("the initial commarea satisfies CCUP-DETAILS-NOT-FETCHED")
        void theInitialCommAreaIsNotFetched() {
            CommArea commArea = CommArea.initialised();
            assertThat(commArea.changeAction().isDetailsNotFetched()).isTrue();
            assertThat(commArea.oldDetails().group()).isEqualTo(DetailGroup.OLD);
            assertThat(commArea.newDetails().group()).isEqualTo(DetailGroup.NEW);
            assertThat(commArea.cardUpdateRecord()).isEqualTo(CardUpdateRecord.initialised());
            assertThat(commArea.encode(ASCII)).hasSize(329);
            assertThat(commArea.encode(StandardCharsets.US_ASCII)).hasSize(329);
        }

        @Test
        @DisplayName("each sub-image lands at its own offset and the whole thing round-trips")
        void eachSubImageLandsAtItsOwnOffset() {
            CommArea commArea = CommArea.initialised()
                    .withChangeAction(ChangeAction.changesOkNotConfirmed())
                    .withOldDetails(CardDetails.initialised(DetailGroup.OLD).withCvvCd("123"))
                    .withNewDetails(CardDetails.initialised(DetailGroup.NEW).withCvvCd("456"))
                    .withCardUpdateRecord(
                            CardUpdateRecord.initialised().withCardUpdateCvvCd(456));
            byte[] image = commArea.encode(ASCII);
            assertThat(image).hasSize(329);
            String rendered = new String(image, StandardCharsets.US_ASCII);
            assertThat(rendered.charAt(0)).isEqualTo('N');
            assertThat(rendered.substring(1 + 27, 1 + 30)).isEqualTo("123");
            assertThat(rendered.substring(90 + 27, 90 + 30)).isEqualTo("456");
            assertThat(rendered.substring(179 + 27, 179 + 30)).isEqualTo("456");
            assertThat(rendered.substring(179 + 91)).isEqualTo(" ".repeat(59));
            assertThat(CommArea.decode(image, ASCII)).isEqualTo(commArea);
            assertThat(CommArea.decode(commArea.encode(IBM037), IBM037)).isEqualTo(commArea);
            assertThat(CommArea.decode(commArea.encode(EBCDIC), EBCDIC)).isEqualTo(commArea);
            assertThat(commArea.toFixedWidthRecord(ASCII).recordLength()).isEqualTo(329);
            assertThat(commArea.itemValues(ASCII)).containsKeys("CCUP-CHANGE-ACTION",
                    "CCUP-OLD-CVV-CD", "CCUP-NEW-CVV-CD", "CARD-UPDATE-CVV-CD",
                    "CCUP-OLD-EXPIRAION-DATE", "CCUP-NEW-EXPIRAION-DATE",
                    "CARD-UPDATE-EXPIRAION-DATE");
        }

        @Test
        @DisplayName("a LOW-VALUES change action survives the byte round trip in both code pages")
        void lowValuesSurvivesTheRoundTrip() {
            CommArea commArea = CommArea.initialised();
            assertThat(commArea.encode(ASCII)[0]).isZero();
            assertThat(commArea.encode(IBM037)[0]).isZero();
            assertThat(CommArea.decode(commArea.encode(ASCII), ASCII).changeAction().value())
                    .isEqualTo("\u0000");
            CommArea spaced = commArea.withChangeAction(ChangeAction.spacesState());
            assertThat(spaced.encode(IBM037)[0]).isEqualTo(EBCDIC_SPACE);
            assertThat(CommArea.decode(spaced.encode(EBCDIC), EBCDIC)).isEqualTo(spaced);
        }

        @Test
        @DisplayName("a snapshot stored under the wrong group names is refused")
        void aSnapshotUnderTheWrongNamesIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    CommArea.initialised()
                            .withOldDetails(CardDetails.initialised(DetailGroup.NEW)));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                    CommArea.initialised()
                            .withNewDetails(CardDetails.initialised(DetailGroup.OLD)));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().withChangeAction(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().withOldDetails(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().withNewDetails(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().withCardUpdateRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().encode((FixedWidthCodec) null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().toFixedWidthRecord(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.initialised().itemValues(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.decode(null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CommArea.decode(new byte[329], (FixedWidthCodec) null));
        }
    }

    @Nested
    @DisplayName("The xxxL / xxxF / xxxA metadata - modelled, but never a payload member")
    class Metadata {

        @Test
        @DisplayName("one entry per field, each declaring that field's copybook width")
        void oneEntryPerFieldAtTheCopybookWidth() {
            CardUpdateRequest request = new CardUpdateRequest();
            assertThat(request.fieldMetadata()).hasSize(17);
            request.fieldMetadata().forEach((name, metadata) -> {
                assertThat(metadata.fieldName()).isEqualTo(name);
                assertThat(metadata.declaredLength())
                        .isEqualTo(CardUpdateRequest.declaredLength(name));
                assertThat(metadata.lengthItem()).isZero();
                assertThat(metadata.flagItem()).isEqualTo("\u0000");
                assertThat(metadata.attributeItem()).isEqualTo(metadata.flagItem());
                assertThat(metadata.fieldUnmodified()).isTrue();
                assertThat(metadata.cursorRequested()).isFalse();
                assertThat(metadata.fieldTransmitted()).isFalse();
            });
        }

        @Test
        @DisplayName("xxxL carries a transmitted count, or -1 for the CICS cursor request")
        void lengthItemCarriesACountOrTheCursorRequest() {
            assertThat(FieldMetadata.CURSOR_LENGTH_ITEM).isEqualTo(-1);
            assertThat(FieldMetadata.LENGTH_ITEM_NOT_TRANSMITTED).isZero();
            assertThat(FieldMetadata.FLAG_ITEM_NOT_MODIFIED).isEqualTo("\u0000");
            assertThat(FieldMetadata.FLAG_ITEM_LENGTH).isEqualTo(1);

            CardUpdateRequest request = new CardUpdateRequest();
            request.putFieldMetadata(FieldMetadata.cursorAt("CRDNAME"));
            assertThat(request.metadataFor("CRDNAME").cursorRequested()).isTrue();
            assertThat(request.metadataFor("CRDNAME").lengthItem()).isEqualTo(-1);
            assertThat(request.metadataFor("CRDNAME").fieldTransmitted()).isFalse();
            request.putFieldMetadata(FieldMetadata.transmitted("ACCTSID", 11));
            assertThat(request.metadataFor("ACCTSID").fieldTransmitted()).isTrue();
            assertThat(FieldMetadata.notTransmitted("FKEYSC").declaredLength()).isEqualTo(18);
            assertThat(FieldMetadata.notTransmitted("FKEYS").withLengthItem(5).lengthItem())
                    .isEqualTo(5);
            assertThat(FieldMetadata.notTransmitted("FKEYS").withAttributeItem("A")
                    .fieldUnmodified()).isFalse();
        }

        @Test
        @DisplayName("an unknown label or an impossible metadata value is rejected")
        void unknownLabelsAndImpossibleValuesAreRejected() {
            CardUpdateRequest request = new CardUpdateRequest();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> request.metadataFor("NOSUCH"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> request.metadataFor(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> request.putFieldMetadata(null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> request.putFieldMetadata(
                            new FieldMetadata("FKEYS", 18, 0, "\u0000")));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FieldMetadata("FKEYS", 21, 22, "\u0000"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FieldMetadata("FKEYS", 21, -2, "\u0000"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FieldMetadata("FKEYS", 0, 0, "\u0000"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new FieldMetadata("FKEYS", 21, 0, "xx"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldMetadata(null, 21, 0, "\u0000"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new FieldMetadata("FKEYS", 21, 0, null));
        }
    }

    @Nested
    @DisplayName("The serialised surface, statelessness and hygiene")
    class SerialisedSurfaceAndHygiene {

        @Test
        @DisplayName("the JSON surface is exactly the 17 screen fields plus the 3 carriers")
        void jsonSurfaceIsSeventeenPlusThree() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode tree = mapper.readTree(mapper.writeValueAsString(new CardUpdateRequest()));
            List<String> names = new ArrayList<>();
            tree.fieldNames().forEachRemaining(names::add);
            assertThat(names).containsExactlyInAnyOrder("trnname", "title01", "curdate",
                    "pgmname", "title02", "curtime", "acctsid", "cardsid", "crdname", "crdstcd",
                    "expmon", "expyear", "expday", "infomsg", "errmsg", "fkeys", "fkeysc",
                    "commArea", "cardScreenState", "navigationContext");
            assertThat(names).hasSize(20)
                    .doesNotContain("fieldMetadata", "fieldValues", "fieldImages", "enter",
                            "reenter");
        }

        @Test
        @DisplayName("a request deserialises without Spring and without a session")
        void deserialisesWithoutSpring() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = "{\"acctsid\":\"00000000011\",\"fkeys\":\"F3=Back\","
                    + "\"fkeysc\":\"F5=Save\"}";
            CardUpdateRequest request = mapper.readValue(json, CardUpdateRequest.class);
            assertThat(request.getAcctsid()).isEqualTo("00000000011");
            assertThat(request.getFkeys()).isEqualTo("F3=Back");
            assertThat(request.getFkeysc()).isEqualTo("F5=Save");
            assertThat(request.getCommArea()).isNotNull();
            assertThat(request.getCardScreenState()).isNotNull();
            assertThat(request.getNavigationContext())
                    .as("an omitted commarea is an absent one, not an initialised one")
                    .isNull();
            assertThat(request.hasNavigationContext()).isFalse();
        }

        @Test
        @DisplayName("no static mutable state in the type or any of its six nested types")
        void noStaticMutableState() {
            List<Class<?>> types = List.of(CardUpdateRequest.class, FieldMetadata.class,
                    ChangeAction.class, DetailGroup.class, CardDetails.class,
                    CardUpdateRecord.class, CommArea.class);
            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as(type.getSimpleName() + "." + field.getName()).isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("every collection handed out is unmodifiable")
        void everyCollectionHandedOutIsUnmodifiable() {
            CardUpdateRequest request = new CardUpdateRequest();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> request.fieldValues().put("X", "Y"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> request.fieldImages(ASCII).put("X", "Y"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> request.fieldMetadata().clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateRequest.FIELD_NAMES.add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateRequest.FIELD_LENGTHS.put("X", 1));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> ChangeAction.CHANGES_MADE_VALUES.add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> ChangeAction.CHANGES_FAILED_VALUES.add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> ChangeAction.DETAILS_NOT_FETCHED_VALUES.add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardDetails.initialised(DetailGroup.OLD).itemValues()
                            .put("X", "Y"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CardUpdateRecord.initialised().itemValues(ASCII)
                            .put("X", "Y"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> CommArea.initialised().itemValues(ASCII).put("X", "Y"));
        }

        @Test
        @DisplayName("a codec and a field label are required wherever one is projected")
        void aCodecAndALabelAreRequired() {
            CardUpdateRequest request = new CardUpdateRequest();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> request.fieldImages(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> request.fieldImage(null, ASCII));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> request.fieldImage("FKEYS", null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> request.fieldImage("NOSUCH", ASCII));
        }
    }

    /** The {@code CCUP-NEW-DETAILS} snapshot every staging and comparison test starts from. */
    private static CardDetails newSnapshot() {
        return CardDetails.initialised(DetailGroup.NEW)
                .withAcctid("00000000011")
                .withCardid("4111111111111111")
                .withCvvCd("123")
                .withCrdname("A CARDHOLDER")
                .withExpyear("2029")
                .withExpmon("12")
                .withExpday("31")
                .withCrdstcd("Y");
    }
}
