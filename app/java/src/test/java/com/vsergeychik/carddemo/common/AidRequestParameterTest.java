package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vsergeychik.carddemo.card.CardListController;
import com.vsergeychik.carddemo.card.CardSelectController;
import com.vsergeychik.carddemo.transaction.ReportRequestController;
import com.vsergeychik.carddemo.transaction.TransactionAddController;
import com.vsergeychik.carddemo.transaction.TransactionMenuController;
import com.vsergeychik.carddemo.transaction.TransactionViewController;
import com.vsergeychik.carddemo.user.SignOnController;
import com.vsergeychik.carddemo.user.UserAddController;
import com.vsergeychik.carddemo.user.UserDeleteController;
import com.vsergeychik.carddemo.user.UserMenuController;
import com.vsergeychik.carddemo.user.UserUpdateController;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@link AidRequestParameter} - the shared name of the {@code EIBAID} query parameter, and the rule for
 * reading it.
 *
 * <h2>What this class is defending</h2>
 * Runtime testing found that the five online routes which accepted the attention identifier as a query
 * parameter had each spelled the parameter for themselves - three {@code eibaid}, two {@code eibAid} -
 * and Spring MVC discards a query parameter no handler declares. A caller that used a sibling screen's
 * spelling therefore had its key <strong>silently ignored</strong>: the request executed as
 * {@link CicsAid#DFHENTER}, so an operator's {@code PF3} exit came back as a validation screen with
 * nothing in the response saying the key had not been understood. On
 * {@code GET /api/cards/{cardNum}} that was the whole story, because that screen's request record
 * declares no {@code CCARD-AID} member and the query parameter is its only channel for the key.
 *
 * <p>So there are two things to hold, and this class holds both:
 *
 * <ul>
 *   <li>the folding rule itself - which of two spellings carried the value, and what happens when they
 *       disagree;</li>
 *   <li><strong>uniformity across the surface</strong> - every handler that binds the parameter binds
 *       both accepted names and no third spelling. That is the assertion which stops the defect coming
 *       back on the next screen, and it is deliberately written against the live
 *       {@code @RequestParam} annotations rather than against a list of names kept in a test.</li>
 * </ul>
 *
 * <p>Nothing here asserts anything about an AID <em>value</em>. Whether a byte names a key a screen
 * handles is the program's decision: {@code app/cbl/COCRDSLC.cbl:299-308} and
 * {@code app/cbl/COCRDLIC.cbl:378-380} both coerce an unrecognised identifier to {@code ENTER} rather
 * than rejecting it, and each controller's own test suite covers that.
 */
@DisplayName("AidRequestParameter - one name for EIBAID across the whole online surface")
final class AidRequestParameterTest {

    /**
     * Every handler that binds the {@code EIBAID} query parameter, named by the route it serves.
     *
     * <p>Twelve entries, which is every route that declares the parameter. Five already had it; the
     * other seven gained it when the attention identifier stopped being read from a folded
     * {@code CCARD-AID} token - the token cannot express {@code PF13} to {@code PF24}, because
     * {@code app/cpy/CSSTRPFY.cpy:54-77} folds them onto {@code PFK01} to {@code PFK12}, and none of
     * those programs copies that copybook, so each of them tests {@code EIBAID} inline and therefore
     * treats {@code PF15} differently from {@code PF3} on the mainframe. A raw byte is the only carrier
     * that can say which was pressed.
     *
     * <p>A thirteenth screen that binds the parameter and is not listed here is not a failure of this
     * test - the reflection below reads the annotations of whatever is listed - but adding the entry is
     * how the next screen inherits the guard.
     */
    private static final Map<String, Method> HANDLERS = handlers();

    private static Map<String, Method> handlers() {
        try {
            return Map.ofEntries(
                    Map.entry("GET /api/cards",
                            CardListController.class.getDeclaredMethod("getCards",
                                    com.vsergeychik.carddemo.card.dto.CardListRequest.class,
                                    Integer.class, Integer.class)),
                    Map.entry("GET /api/cards/{cardNum}",
                            CardSelectController.class.getDeclaredMethod("viewCardDetail",
                                    String.class,
                                    com.vsergeychik.carddemo.card.dto.CardSelectRequest.class,
                                    Integer.class, Integer.class, Integer.class)),
                    Map.entry("GET /api/users",
                            UserMenuController.class.getDeclaredMethod("getUsers",
                                    com.vsergeychik.carddemo.user.dto.UserListRequest.class,
                                    Integer.class, Integer.class)),
                    Map.entry("POST /api/users",
                            UserAddController.class.getDeclaredMethod("addUser",
                                    com.vsergeychik.carddemo.user.dto.UserAddRequest.class,
                                    Integer.class, Integer.class, Integer.class)),
                    Map.entry("GET /api/transactions",
                            TransactionMenuController.class.getDeclaredMethod("getTransactions",
                                    com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.class,
                                    Integer.class, Integer.class)),
                    Map.entry("GET /api/transactions/{tranId}",
                            TransactionAddController.class.getDeclaredMethod("viewTransaction",
                                    String.class,
                                    com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.class,
                                    Integer.class, Integer.class)),

                    // The seven whose only channel for the key used to be the lossy token.
                    Map.entry("POST /api/signon",
                            SignOnController.class.getDeclaredMethod("signOn",
                                    com.vsergeychik.carddemo.user.dto.SignOnRequest.class,
                                    Integer.class, Integer.class)),
                    Map.entry("POST /api/billpay",
                            com.vsergeychik.carddemo.billing.BillPaymentController.class
                                    .getDeclaredMethod("payBill",
                                            com.vsergeychik.carddemo.billing.dto
                                                    .BillPaymentRequest.class,
                                            Integer.class, Integer.class)),
                    Map.entry("POST /api/reports",
                            ReportRequestController.class.getDeclaredMethod("submitReportRequest",
                                    com.vsergeychik.carddemo.transaction.dto.ReportRequestRequest.class,
                                    Integer.class, Integer.class)),
                    Map.entry("POST /api/transactions",
                            TransactionViewController.class.getDeclaredMethod("addTransaction",
                                    com.vsergeychik.carddemo.transaction.dto.TransactionViewRequest.class,
                                    Integer.class, Integer.class)),
                    Map.entry("PUT /api/users/{userId}",
                            UserUpdateController.class.getDeclaredMethod("updateUser",
                                    String.class,
                                    com.vsergeychik.carddemo.user.dto.UserUpdateRequest.class,
                                    Integer.class, Integer.class, Integer.class)),
                    Map.entry("DELETE /api/users/{userId}",
                            UserDeleteController.class.getDeclaredMethod("deleteUser",
                                    String.class,
                                    com.vsergeychik.carddemo.user.dto.UserDeleteRequest.class,
                                    Integer.class, Integer.class)));
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("a route that binds the EIBAID parameter has changed shape", absent);
        }
    }

    /** Feeds the parameterized uniformity tests. */
    private static List<String> routes() {
        return HANDLERS.keySet().stream().sorted().toList();
    }

    /** The names bound by {@code @RequestParam} on one handler, in declaration order. */
    private static List<String> boundParameterNames(String route) {
        List<String> names = new ArrayList<>();
        for (Parameter parameter : HANDLERS.get(route).getParameters()) {
            RequestParam bound = parameter.getAnnotation(RequestParam.class);
            if (bound != null) {
                names.add(bound.name());
            }
        }
        return names;
    }

    @Nested
    @DisplayName("the names themselves")
    class Names {

        @Test
        @DisplayName("the canonical name is the all-lower-case spelling three routes already required")
        void canonicalNameIsTheMajorityForm() {
            assertThat(AidRequestParameter.CANONICAL_NAME).isEqualTo("eibaid");
            assertThat(AidRequestParameter.CANONICAL_NAME)
                    .as("it reads like eibcalen, the other exec interface block value accepted as a "
                            + "query parameter")
                    .isEqualTo(AidRequestParameter.CANONICAL_NAME.toLowerCase(Locale.ROOT));
        }

        @Test
        @DisplayName("the alternate name is the spelling two routes shipped, and it is still accepted")
        void alternateNameIsTheOneAlreadyInService() {
            assertThat(AidRequestParameter.ALTERNATE_NAME).isEqualTo("eibAid");
            assertThat(AidRequestParameter.ALTERNATE_NAME)
                    .isNotEqualTo(AidRequestParameter.CANONICAL_NAME);
        }

        @Test
        @DisplayName("the two names differ only in case, which is exactly why the wrong one looked right")
        void theNamesAreCaseVariantsOfEachOther() {
            assertThat(AidRequestParameter.ALTERNATE_NAME.toLowerCase(Locale.ROOT))
                    .isEqualTo(AidRequestParameter.CANONICAL_NAME);
        }

        @Test
        @DisplayName("ACCEPTED_NAMES holds both, canonical first, and cannot be added to")
        void acceptedNamesIsBothAndImmutable() {
            assertThat(AidRequestParameter.ACCEPTED_NAMES)
                    .containsExactly(AidRequestParameter.CANONICAL_NAME,
                            AidRequestParameter.ALTERNATE_NAME);

            assertThatThrownBy(() -> AidRequestParameter.ACCEPTED_NAMES.add("eibaidToo"))
                    .as("a shared constant that could be added to would be mutable static state "
                            + "(gate G53)")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("the class is a constant holder: final, and not instantiable")
        void theClassIsNotInstantiable() throws NoSuchMethodException {
            assertThat(Modifier.isFinal(AidRequestParameter.class.getModifiers())).isTrue();

            Constructor<AidRequestParameter> constructor =
                    AidRequestParameter.class.getDeclaredConstructor();
            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
            constructor.setAccessible(true);
            assertThatThrownBy(constructor::newInstance)
                    .isInstanceOf(InvocationTargetException.class)
                    .hasRootCauseInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("no field of this class is non-final, so there is nothing to share between requests")
        void noMutableStaticState() {
            assertThat(Arrays.stream(AidRequestParameter.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .toList())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("resolve - folding two spellings into the one value the caller stated")
    class Resolve {

        @Test
        @DisplayName("neither name present is null, which every caller already reads as 'no key named'")
        void neitherPresentIsNull() {
            assertThat(AidRequestParameter.resolve(null, null)).isNull();
        }

        @Test
        @DisplayName("the canonical name alone carries the value")
        void canonicalAlone() {
            assertThat(AidRequestParameter.resolve(243, null)).isEqualTo(243);
        }

        @Test
        @DisplayName("the alternate name alone carries the value - the case the defect turned into ENTER")
        void alternateAlone() {
            assertThat(AidRequestParameter.resolve(null, 243)).isEqualTo(243);
        }

        @Test
        @DisplayName("both present and equal is one statement made twice, not a contradiction")
        void bothPresentAndEqual() {
            assertThat(AidRequestParameter.resolve(243, 243)).isEqualTo(243);
        }

        @Test
        @DisplayName("both present and equal by value rather than by identity")
        void bothPresentAndEqualAcrossTheIntegerCache() {
            // Above 127 the two boxes are distinct objects, so a reference comparison would have made
            // this a contradiction. 243 is DFHPF3, the very key the defect was found with.
            Integer canonical = Integer.valueOf(243);
            Integer alternate = Integer.valueOf(243);
            assertThat(canonical).isNotSameAs(alternate);

            assertThat(AidRequestParameter.resolve(canonical, alternate)).isEqualTo(243);
        }

        @Test
        @DisplayName("both present and different is refused, because a terminal presents one AID")
        void bothPresentAndDifferentIsRefused() {
            assertThatThrownBy(() -> AidRequestParameter.resolve(243, 244))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(AidRequestParameter.CANONICAL_NAME)
                    .hasMessageContaining(AidRequestParameter.ALTERNATE_NAME)
                    .hasMessageContaining("243")
                    .hasMessageContaining("244");
        }

        @ParameterizedTest(name = "canonical={0}, alternate={1} resolves to {2}")
        @CsvSource(nullValues = "NONE", value = {
            "NONE, NONE, NONE",
            "0,    NONE, 0",
            "NONE, 0,    0",
            "255,  NONE, 255",
            "NONE, 255,  255",
            "125,  125,  125",
            "-14,  NONE, -14",
            "NONE, 999,  999"
        })
        @DisplayName("the value is handed on unexamined - range is the controller's decision, not this "
                + "class's")
        void theValueIsNotJudgedHere(Integer canonical, Integer alternate, Integer expected) {
            assertThat(AidRequestParameter.resolve(canonical, alternate)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("uniformity - the assertion that stops the divergence coming back")
    class Uniformity {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.AidRequestParameterTest#routes")
        @DisplayName("the route binds both accepted spellings of the EIBAID parameter")
        void bothSpellingsAreBound(String route) {
            assertThat(boundParameterNames(route))
                    .as("a spelling this handler does not declare is discarded by Spring, and the "
                            + "request then runs as ENTER with nothing saying the key was not understood")
                    .containsAll(AidRequestParameter.ACCEPTED_NAMES);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.AidRequestParameterTest#routes")
        @DisplayName("the route binds no third spelling of the AID, and each name exactly once")
        void noThirdSpellingExists(String route) {
            List<String> aidNames = boundParameterNames(route).stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT)
                            .contains(AidRequestParameter.CANONICAL_NAME))
                    .toList();

            assertThat(aidNames)
                    .containsExactlyInAnyOrderElementsOf(AidRequestParameter.ACCEPTED_NAMES)
                    .doesNotHaveDuplicates();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.common.AidRequestParameterTest#routes")
        @DisplayName("both AID parameters are optional, so the cold start needs neither")
        void bothSpellingsAreOptional(String route) {
            for (Parameter parameter : HANDLERS.get(route).getParameters()) {
                RequestParam bound = parameter.getAnnotation(RequestParam.class);
                if (bound != null && AidRequestParameter.ACCEPTED_NAMES.contains(bound.name())) {
                    assertThat(bound.required())
                            .as("%s on %s", bound.name(), route)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("every route in the survey is covered, and they agree on the canonical name")
        void theSurveyIsComplete() {
            assertThat(routes()).containsExactly(
                    "DELETE /api/users/{userId}",
                    "GET /api/cards",
                    "GET /api/cards/{cardNum}",
                    "GET /api/transactions",
                    "GET /api/transactions/{tranId}",
                    "GET /api/users",
                    "POST /api/billpay",
                    "POST /api/reports",
                    "POST /api/signon",
                    "POST /api/transactions",
                    "POST /api/users",
                    "PUT /api/users/{userId}");

            // Read from the live annotations rather than from each controller's constant: the constants
            // are package-private, as they should be, and the annotation is what Spring actually binds
            // by. Every route must name the canonical spelling character for character.
            for (String route : routes()) {
                assertThat(boundParameterNames(route))
                        .as("the canonical name is stated once, in AidRequestParameter, and never "
                                + "per screen - %s", route)
                        .contains(AidRequestParameter.CANONICAL_NAME);
            }
        }

        @Test
        @DisplayName("no route names a query parameter this contract does not know about")
        void everyBoundParameterIsAccountedFor() {
            for (String route : routes()) {
                List<String> unexpected = boundParameterNames(route).stream()
                        .filter(name -> !AidRequestParameter.ACCEPTED_NAMES.contains(name))
                        // eibcalen is the other exec interface block value this module accepts, and it
                        // was already uniform across every route that takes it.
                        .filter(name -> !"eibcalen".equals(name))
                        .toList();

                assertThat(unexpected).as("unexpected query parameters on %s", route).isEmpty();
            }
        }

        @Test
        @DisplayName("the AID parameters are typed Integer, so absence stays distinguishable from zero")
        void absenceIsRepresentable() {
            for (String route : routes()) {
                for (Parameter parameter : HANDLERS.get(route).getParameters()) {
                    RequestParam bound = parameter.getAnnotation(RequestParam.class);
                    if (bound != null && AidRequestParameter.ACCEPTED_NAMES.contains(bound.name())) {
                        assertThat(parameter.getType())
                                .as("%s on %s: int cannot carry 'the caller named no key', and 0 is a "
                                        + "real AID byte", bound.name(), route)
                                .isEqualTo(Integer.class);
                    }
                }
            }
        }

        @Test
        @DisplayName("the handler map is built from live reflection, so a renamed handler fails loudly")
        void theHandlerMapIsLive() {
            assertThat(HANDLERS).hasSize(12);
            assertThat(HANDLERS.values()).allSatisfy(handler ->
                    assertThat(Objects.requireNonNull(handler).getParameterCount())
                            .isGreaterThanOrEqualTo(3));
        }
    }

    @Nested
    @DisplayName("requireAidByte - narrowing the stated value to the one byte it names")
    class RequireAidByte {

        @ParameterizedTest(name = "{0} narrows to the byte 0x{1}")
        @CsvSource({"0, 00", "1, 01", "13, 0D", "127, 7F", "128, 80", "193, C1", "243, F3",
            "252, FC", "255, FF"})
        @DisplayName("every value in 0-255 narrows to the unsigned reinterpretation of that byte")
        void theWholeOneByteSpaceNarrows(int stated, String expectedHex) {
            assertThat(AidRequestParameter.requireAidByte(stated))
                    .isEqualTo((byte) Integer.parseInt(expectedHex, 16));
        }

        @Test
        @DisplayName("the AIDs above 127 arrive unsigned and compare equal to their CicsAid constants")
        void theHighAidsSurviveTheNarrowing() {
            // This is the whole reason the parameter is unsigned: DFHPF13 is 0xC1, which Java's byte
            // renders as -63 and a query string cannot spell.
            assertThat(AidRequestParameter.requireAidByte(Byte.toUnsignedInt(CicsAid.DFHPF13)))
                    .isEqualTo(CicsAid.DFHPF13);
            assertThat(AidRequestParameter.requireAidByte(Byte.toUnsignedInt(CicsAid.DFHPF17)))
                    .isEqualTo(CicsAid.DFHPF17);
            assertThat(AidRequestParameter.requireAidByte(Byte.toUnsignedInt(CicsAid.DFHPF24)))
                    .isEqualTo(CicsAid.DFHPF24);
            assertThat(AidRequestParameter.requireAidByte(Byte.toUnsignedInt(CicsAid.DFHENTER)))
                    .isEqualTo(CicsAid.DFHENTER);
        }

        @ParameterizedTest(name = "{0} is refused")
        @CsvSource({"-1", "-63", "256", "259", "1000", "-2147483648", "2147483647"})
        @DisplayName("a value outside 0-255 is refused rather than narrowed, because (byte) 259 is 3")
        void anOutOfRangeValueIsRefused(int stated) {
            assertThatThrownBy(() -> AidRequestParameter.requireAidByte(stated))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining(AidRequestParameter.CANONICAL_NAME)
                    .hasMessageContaining("0")
                    .hasMessageContaining("255")
                    .as("and never the rejected value")
                    .hasMessageNotContaining(String.valueOf(stated));
        }

        @Test
        @DisplayName("the refusal is classified OUTSIDE_RANGE, so its published text is the fixed one")
        void theRefusalIsClassified() {
            ScreenInputRejectedException refusal = (ScreenInputRejectedException)
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> AidRequestParameter.requireAidByte(256));

            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.OUTSIDE_RANGE);
            assertThat(refusal.publicDetail())
                    .contains(AidRequestParameter.CANONICAL_NAME)
                    .doesNotContain("255")
                    .doesNotContain("EIBAID");
        }

        @Test
        @DisplayName("absence is the caller's decision, so null is a programming error and not a refusal")
        void absenceIsNotNarrowed() {
            assertThatThrownBy(() -> AidRequestParameter.requireAidByte(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the bounds are the inclusive one-byte range and are published as constants")
        void theBoundsAreTheOneByteRange() {
            assertThat(AidRequestParameter.MIN_AID_VALUE).isZero();
            assertThat(AidRequestParameter.MAX_AID_VALUE).isEqualTo(255);
        }
    }

    @Nested
    @DisplayName("requireTokenAgreement - a payload token may restate the byte, never contradict it")
    class RequireTokenAgreement {

        /** The code page is stated explicitly, never taken from the platform (practice B8). */
        private final FixedWidthCodec codec =
                new FixedWidthCodec(java.nio.charset.StandardCharsets.US_ASCII);

        @Test
        @DisplayName("no token at all agrees: the byte is then the request's only statement of the key")
        void anAbsentTokenAgrees() {
            AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHPF3, null, codec);
        }

        @ParameterizedTest(name = "a token of \"{0}\" states no key and agrees")
        @CsvSource({"'     '", "''", "'  '"})
        @DisplayName("an all-spaces token is an unpainted field and states no key")
        void anAllSpacesTokenAgrees(String token) {
            AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHPF17, token, codec);
        }

        @Test
        @DisplayName("an all-LOW-VALUES token is a field RECEIVE MAP never touched, and states no key")
        void anAllLowValuesTokenAgrees() {
            AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHPF17,
                    "\u0000".repeat(PfKeyResolver.AID_TOKEN_LENGTH), codec);
        }

        @ParameterizedTest(name = "{0}")
        @org.junit.jupiter.params.provider.EnumSource(PfKeyResolver.AidKey.class)
        @DisplayName("a token restating the byte's own key agrees, for all sixteen keys")
        void aTokenRestatingTheSameKeyAgrees(PfKeyResolver.AidKey key) {
            AidRequestParameter.requireTokenAgreement("aid", key.primaryAid(), key.token(), codec);
        }

        @ParameterizedTest(name = "DFHPF{0} with the folded token PFK{1} agrees")
        @CsvSource({"13, 01", "15, 03", "17, 05", "24, 12"})
        @DisplayName("a folded upper key agrees with the token CSSTRPFY itself would store for it")
        void aFoldedByteAgreesWithItsFoldedToken(int pfNumber, String foldedOnto) {
            // The token is a value the copybook produces, so 'PFK05' is a truthful restatement of PF17.
            // The byte still wins, which is what lets COUSR03C reach WHEN OTHER - see its own suite.
            byte upper = aidByte("DFHPF" + pfNumber);
            AidRequestParameter.requireTokenAgreement("aid", upper,
                    PfKeyResolver.AidKey.valueOf("PFK" + foldedOnto).token(), codec);
        }

        @Test
        @DisplayName("a token naming a different key is refused, and neither key is echoed")
        void aDisagreeingTokenIsRefused() {
            assertThatThrownBy(() -> AidRequestParameter.requireTokenAgreement("aid",
                    CicsAid.DFHPF3, PfKeyResolver.AidKey.PFK05.token(), codec))
                    .isInstanceOf(ScreenInputRejectedException.class)
                    .hasMessageContaining("aid")
                    .hasMessageContaining(AidRequestParameter.CANONICAL_NAME)
                    .hasMessageNotContaining("PFK05")
                    .hasMessageNotContaining("PFK03");
        }

        @Test
        @DisplayName("a byte no resolver tests stores no token, so any token beside it disagrees")
        void aTokenBesideAnUntestedByteIsRefused() {
            assertThatThrownBy(() -> AidRequestParameter.requireTokenAgreement("aid",
                    CicsAid.DFHPA3, PfKeyResolver.AidKey.ENTER.token(), codec))
                    .isInstanceOf(ScreenInputRejectedException.class);
            // But no token beside it is still fine: nothing is being contradicted.
            AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHPA3, null, codec);
        }

        @Test
        @DisplayName("a token that is not a token at all is refused rather than ignored")
        void aNonsenseTokenIsRefused() {
            assertThatThrownBy(() -> AidRequestParameter.requireTokenAgreement("aid",
                    CicsAid.DFHENTER, "hello", codec))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @Test
        @DisplayName("the comparison is at PIC X(5) and never by trimming, so padding is honoured")
        void theComparisonIsAtTheDeclaredWidth() {
            // 'PA1  ' carries two significant trailing spaces; a trimming comparison would accept
            // 'PA1' for PA2's byte just as readily.
            AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHPA1, "PA1", codec);
            assertThatThrownBy(() -> AidRequestParameter.requireTokenAgreement("aid",
                    CicsAid.DFHPA2, "PA1", codec))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }

        @Test
        @DisplayName("the refusal is classified CONTRADICTORY_SPELLINGS, so no new public text appears")
        void theRefusalReusesTheContradictionReason() {
            ScreenInputRejectedException refusal = (ScreenInputRejectedException)
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHPF3,
                                    PfKeyResolver.AidKey.PFK05.token(), codec));

            assertThat(refusal.reason())
                    .isEqualTo(ScreenInputRejectedException.Reason.CONTRADICTORY_SPELLINGS);
            assertThat(refusal.publicDetail())
                    .contains("aid")
                    .doesNotContain("PFK")
                    .doesNotContain("EIBAID")
                    .doesNotContain("CSSTRPFY");
        }

        @Test
        @DisplayName("the member name and the codec are both required")
        void bothCollaboratorsAreRequired() {
            assertThatThrownBy(() -> AidRequestParameter.requireTokenAgreement(null, CicsAid.DFHENTER,
                    "ENTER", codec)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> AidRequestParameter.requireTokenAgreement("aid", CicsAid.DFHENTER,
                    "ENTER", null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("requireStatedAid - the composed rule the six routes call")
    class RequireStatedAid {

        private final FixedWidthCodec codec =
                new FixedWidthCodec(java.nio.charset.StandardCharsets.US_ASCII);

        @Test
        @DisplayName("it narrows and then cross-checks, handing back the byte to act on")
        void itNarrowsThenCrossChecks() {
            assertThat(AidRequestParameter.requireStatedAid("aid",
                    Byte.toUnsignedInt(CicsAid.DFHPF17), null, codec))
                    .isEqualTo(CicsAid.DFHPF17);
            assertThat(AidRequestParameter.requireStatedAid("aid",
                    Byte.toUnsignedInt(CicsAid.DFHPF3), "PFK03", codec))
                    .isEqualTo(CicsAid.DFHPF3);
        }

        @Test
        @DisplayName("the range is checked first, because an impossible value names no key to compare to")
        void theRangeIsCheckedFirst() {
            ScreenInputRejectedException refusal = (ScreenInputRejectedException)
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> AidRequestParameter.requireStatedAid("aid", 999, "PFK05", codec));

            assertThat(refusal.reason())
                    .as("out of range, not a contradiction - the order of the two guards is observable")
                    .isEqualTo(ScreenInputRejectedException.Reason.OUTSIDE_RANGE);
        }

        @Test
        @DisplayName("a disagreeing token is refused even though the byte itself is valid")
        void aDisagreementStillRefuses() {
            assertThatThrownBy(() -> AidRequestParameter.requireStatedAid("aid",
                    Byte.toUnsignedInt(CicsAid.DFHPF3), "PFK05", codec))
                    .isInstanceOf(ScreenInputRejectedException.class);
        }
    }

    /** The {@link CicsAid} constant of a given name, read reflectively so the name is the source. */
    private static byte aidByte(String constantName) {
        try {
            return CicsAid.class.getDeclaredField(constantName).getByte(null);
        } catch (ReflectiveOperationException absent) {
            throw new AssertionError("CicsAid does not declare " + constantName, absent);
        }
    }
}
