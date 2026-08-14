/*
 * ******************************************************************
 * Program     : AdminControllerTest.java
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Verifies the wire contract of AdminController, the REST
 *               replacement for CICS transactions CU00, CU01, CU02 and CU03
 *               and the COBOL programs app/cbl/COUSR00C.cbl,
 *               app/cbl/COUSR01C.cbl, app/cbl/COUSR02C.cbl and
 *               app/cbl/COUSR03C.cbl. Asserts that all four operations are
 *               mapped under the single path the CSD-derived authorisation rule
 *               restricts to administrators, that every control token is
 *               matched exactly rather than converted, that a delete refuses to
 *               proceed without an explicit confirmation and destroys nothing
 *               when it refuses, and that each typed failure keeps its own
 *               status and its own machine-readable code.
 * Source      : app/cbl/COUSR00C.cbl (695 lines), COUSR01C.cbl (299),
 *               COUSR02C.cbl (414), COUSR03C.cbl (359),
 *               app/csd/CARDDEMO.CSD (CU00, CU01, CU02, CU03),
 *               app/cpy-bms/COUSR00.CPY, COUSR01.CPY, COUSR02.CPY,
 *               COUSR03.CPY @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.controller.AdminController;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.UserUpdateRequest;
import com.cardemo.model.dto.UserUpdateResponse;
import com.cardemo.service.admin.UserAddService;
import com.cardemo.service.admin.UserDeleteService;
import com.cardemo.service.admin.UserListService;
import com.cardemo.service.admin.UserUpdateService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Unit tests for {@link AdminController}.
 *
 * <p>All four services are mocked. This tier owns the routing, the exact matching of every control token, the
 * confirmation gate ahead of a destructive operation, and the mapping from a typed failure to a status.</p>
 *
 * <p>Inputs: request parameters, path variables and stubbed service outcomes. Outputs: the asserted response
 * entity or the asserted rejection. Side effects: none - no record is read, written or destroyed, and the
 * refusal tests prove that by asserting the services were never touched. Error modes: each of the seven
 * declared handlers is exercised, and both halves of the confirmation gate are exercised separately because
 * they carry different rejection kinds.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AdminController - the wire contract for CU00, CU01, CU02 and CU03")
class AdminControllerTest {

    /**
     * The base path all four operations share.
     *
     * <p>It sits under the {@code /api/admin/**} prefix that the authorisation rule derived from
     * {@code app/csd/CARDDEMO.CSD} restricts to the administrator authority. One rule covering all four is
     * only sound while all four stay under this prefix, which is what the authorisation test below pins.
     */
    private static final String BASE_PATH = "/api/admin/users";

    /** The prefix the security configuration restricts, of which the base path must be a descendant. */
    private static final String RESTRICTED_PREFIX = "/api/admin/";

    /** The single-user path template, matching exactly one segment. */
    private static final String USER_PATH = "/{userId}";

    /** The request parameter naming the list action. */
    private static final String ACTION_PARAMETER = "action";

    /** The request parameter carrying the delete confirmation. */
    private static final String CONFIRMED_PARAMETER = "confirmed";

    /**
     * {@code app/cbl/COUSR03C.cbl:283-284} - the prompt the {@code DFHRESP(NORMAL)} arm of the read paints,
     * and the caption an unconfirmed delete is answered with. Note the space before the three periods.
     */
    private static final String UNCONFIRMED_DELETE_CAPTION = "Press PF5 key to delete this user ...";

    /**
     * {@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy:20-21}, the caption
     * {@code app/cbl/COUSR03C.cbl:126-129} answers a key that is not one of the five it recognises with, and
     * therefore the caption a present-but-unrecognised confirmation token is answered with. The copybook pads
     * the value to {@code PIC X(50)}; the padding is the field's, not the message's.
     */
    private static final String UNRECOGNISED_CONFIRMATION_CAPTION = "Invalid key pressed. Please see below...";

    /** The frozen delete program, read so that the two published captions are verified against the oracle. */
    private static final Path DELETE_PROGRAM_SOURCE = Path.of("app", "cbl", "COUSR03C.cbl");

    /** The frozen shared-message copybook that declares {@code CCDA-MSG-INVALID-KEY}. */
    private static final Path SHARED_MESSAGES_SOURCE = Path.of("app", "cpy", "CSMSG01Y.cpy");

    /** One of the ten identifiers seeded by {@code app/jcl/DUSRSECJ.jcl}. */
    private static final String USER_ID = "USER0001";

    /** The property carrying the machine-readable code every problem body must publish. */
    private static final String ERROR_CODE_PROPERTY = "errorCode";

    /** The property carrying the correlation identifier every problem body must publish. */
    private static final String CORRELATION_ID_PROPERTY = "correlationId";

    /** The property naming the rejected field. */
    private static final String FIELD_PROPERTY = "field";

    /** The property naming the rejection kind. */
    private static final String FAILURE_KIND_PROPERTY = "failureKind";

    /** Replacement for {@code app/cbl/COUSR00C.cbl}, mocked because this tier reaches no store. */
    @Mock
    private UserListService userListService;

    /** Replacement for {@code app/cbl/COUSR01C.cbl}. */
    @Mock
    private UserAddService userAddService;

    /** Replacement for {@code app/cbl/COUSR02C.cbl}. */
    @Mock
    private UserUpdateService userUpdateService;

    /** Replacement for {@code app/cbl/COUSR03C.cbl}. */
    @Mock
    private UserDeleteService userDeleteService;

    /**
     * Builds the controller under test.
     *
     * @return a controller wired to the four mocked services
     */
    private AdminController controller() {
        return new AdminController(this.userListService, this.userAddService, this.userUpdateService,
                this.userDeleteService);
    }

    /**
     * Reads a published problem property.
     *
     * @param problem the body to read; must not be {@code null}
     * @param name the property name; must not be {@code null}
     * @return the property value, or {@code null} when the body does not publish it
     */
    private static Object property(final ProblemDetail problem, final String name) {
        final Map<String, Object> properties = problem.getProperties();
        return properties == null ? null : properties.get(name);
    }

    /**
     * Asserts that none of the four services was touched.
     */
    private void assertNothingWasReached() {
        verifyNoInteractions(this.userListService, this.userAddService, this.userUpdateService,
                this.userDeleteService);
    }

    /**
     * The routing contract: four operations, all under the one restricted path.
     */
    @Nested
    @DisplayName("all four CSD transactions are mapped under the one restricted path")
    class RoutingContract {

        @Test
        @DisplayName("the class is mapped at /api/admin/users, which is a descendant of the restricted "
                + "/api/admin/ prefix")
        void theClassIsMappedUnderTheRestrictedPrefix() {
            assertThat(AdminController.class.getAnnotation(RequestMapping.class).value())
                    .containsExactly(BASE_PATH);
            assertThat(BASE_PATH)
                    .as("the security configuration restricts %s to the administrator authority with a "
                            + "single rule. Moving any of these four operations out from under that prefix "
                            + "would silently expose it to a standard user, because no second rule would "
                            + "catch it", RESTRICTED_PREFIX)
                    .startsWith(RESTRICTED_PREFIX);
        }

        @Test
        @DisplayName("CU00 list is a GET on the collection, CU01 add a POST on the collection, CU02 update a "
                + "PUT on one segment and CU03 delete a DELETE on one segment")
        void eachOperationUsesTheVerbAndPathItsTransactionImplies() throws NoSuchMethodException {
            final Method list = Arrays.stream(AdminController.class.getMethods())
                    .filter(method -> "listUsers".equals(method.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(list.getAnnotation(GetMapping.class))
                    .as("CU00 is a read, so it is a GET; its parameters carry the paging cursor that "
                            + "replaced the COMMAREA")
                    .isNotNull();
            assertThat(list.getAnnotation(GetMapping.class).value())
                    .as("a read of the collection has no path of its own")
                    .isEmpty();

            final Method add = Arrays.stream(AdminController.class.getMethods())
                    .filter(method -> "addUser".equals(method.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(add.getAnnotation(PostMapping.class))
                    .as("CU01 creates, so it is a POST on the collection rather than a PUT on a name the "
                            + "caller chose")
                    .isNotNull();
            assertThat(add.getAnnotation(PostMapping.class).value()).isEmpty();

            final Method update = Arrays.stream(AdminController.class.getMethods())
                    .filter(method -> "updateUser".equals(method.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(update.getAnnotation(PutMapping.class))
                    .as("CU02 replaces a named record, so it is a PUT on that one segment")
                    .isNotNull();
            assertThat(update.getAnnotation(PutMapping.class).value()).containsExactly(USER_PATH);

            final Method delete = Arrays.stream(AdminController.class.getMethods())
                    .filter(method -> "deleteUser".equals(method.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(delete.getAnnotation(DeleteMapping.class))
                    .as("CU03 destroys a named record, so it is a DELETE on that one segment")
                    .isNotNull();
            assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly(USER_PATH);
        }

        @Test
        @DisplayName("the constructor names the COBOL program behind each missing collaborator")
        void theConstructorRefusesEachNullCollaborator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AdminController(null, userAddService, userUpdateService,
                            userDeleteService))
                    .withMessageContaining("app/cbl/COUSR00C.cbl");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AdminController(userListService, null, userUpdateService,
                            userDeleteService))
                    .withMessageContaining("app/cbl/COUSR01C.cbl");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new AdminController(userListService, userAddService, null,
                            userDeleteService))
                    .withMessageContaining("app/cbl/COUSR02C.cbl");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("naming the source program in each message is what turns a wiring mistake into a "
                            + "one-line diagnosis instead of a hunt through four identical fields")
                    .isThrownBy(() -> new AdminController(userListService, userAddService, userUpdateService,
                            null))
                    .withMessageContaining("app/cbl/COUSR03C.cbl");
        }
    }

    /**
     * The authorisation surface: the restriction lives where it is actually enforced.
     */
    @Nested
    @DisplayName("the administrator restriction is not asserted with an inert annotation")
    class AuthorizationSurface {

        @Test
        @DisplayName("no handler carries a method-security annotation, because method security is not enabled "
                + "and such an annotation would be inert")
        void noHandlerCarriesAnInertMethodSecurityAnnotation() {
            for (final Method method : AdminController.class.getDeclaredMethods()) {
                assertThat(Arrays.stream(method.getAnnotations())
                        .map(annotation -> annotation.annotationType().getName())
                        .toList())
                        .as("method '%s' must not claim an authorisation it cannot enforce. The tree enables "
                                + "no method security, so an annotation here would read as a guard while "
                                + "permitting every caller the path rule admits - which is worse than no "
                                + "annotation at all", method.getName())
                        .noneMatch(name -> name.startsWith("org.springframework.security.access"))
                        .noneMatch(name -> name.endsWith("PreAuthorize"))
                        .noneMatch(name -> name.endsWith("Secured"))
                        .noneMatch(name -> name.endsWith("RolesAllowed"));
            }
        }
    }

    /**
     * The control tokens: matched exactly, never converted.
     */
    @Nested
    @DisplayName("every control token is matched exactly rather than converted")
    class ControlTokenStrictness {

        // The three declared spellings are now SUBMIT, PAGE_BACKWARD and PAGE_FORWARD - the same three
        // this API's other two list operations declare. The former lower-cased and hyphenated spellings
        // therefore belong here, among the refusals, rather than among the accepted tokens: one API
        // publishing two vocabularies for one navigation was the defect this closes.
        @ParameterizedTest
        @ValueSource(strings = {"submit", " SUBMIT", "SUBMIT ", "page_forward", "PAGE-FORWARD",
            "page-forward", "page-backward", "Submit", "1", "bogus"})
        @DisplayName("an action token that is not one of the three exact spellings is refused")
        void anInexactActionTokenIsRefused(final String token) {
            assertThatExceptionOfType(ValidationException.class)
                    .as("the framework's default converters normalise - they trim, they accept a signed "
                            + "integer and they treat several spellings as one value - and a normalising "
                            + "converter on a control token accepts instructions this operation never "
                            + "declared. Token was '%s'", token)
                    .isThrownBy(() -> controller().listUsers(token, null, null, null, null, null, null))
                    .satisfies(rejection -> {
                        assertThat(rejection.getFieldName()).isEqualTo(ACTION_PARAMETER);
                        assertThat(rejection.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            assertNothingWasReached();
        }

        @Test
        @DisplayName("an omitted confirmation refuses the delete as a blank field and destroys nothing")
        void anOmittedConfirmationRefusesTheDelete() {
            assertThatExceptionOfType(ValidationException.class)
                    .as("app/cbl/COUSR03C.cbl required a confirmation before it destroyed a record, and the "
                            + "stateless equivalent is an explicit parameter. An omission is a blank field, "
                            + "not a false assertion, because the two are answered differently")
                    .isThrownBy(() -> controller().deleteUser(USER_ID, null))
                    .satisfies(rejection -> {
                        assertThat(rejection.getFieldName()).isEqualTo(CONFIRMED_PARAMETER);
                        assertThat(rejection.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                        // Finding P4-03, Major. The refusal used to publish newly authored prose about
                        // stateless equivalence, so the one caption that told an operator a second key press
                        // was needed never reached a caller at all. The explanation now lives in the
                        // Javadoc and in docs/api-contracts.md, where a developer reads it, and the body
                        // carries the source's own text.
                        assertThat(rejection.getMessage())
                                .as("app/cbl/COUSR03C.cbl:283 is the prompt that stands on the screen until "
                                        + "PF5 is pressed, and this is the state a caller that has not "
                                        + "confirmed is in")
                                .isEqualTo(UNCONFIRMED_DELETE_CAPTION);
                    });
            assertNothingWasReached();
        }

        @ParameterizedTest
        @ValueSource(strings = {"false", "TRUE", "yes", "Y", "1", "on"})
        @DisplayName("a confirmation that is not the exact affirmative token refuses the delete and destroys "
                + "nothing")
        void anInexactConfirmationRefusesTheDelete(final String token) {
            assertThatExceptionOfType(ValidationException.class)
                    .as("only the one exact affirmative token deletes. Everything else - including the "
                            + "capitalised spelling and the six tokens a lenient boolean converter would "
                            + "accept - is refused. Token was '%s'", token)
                    .isThrownBy(() -> controller().deleteUser(USER_ID, token))
                    .satisfies(rejection -> {
                        assertThat(rejection.getFieldName()).isEqualTo(CONFIRMED_PARAMETER);
                        assertThat(rejection.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                        assertThat(rejection.getMessage())
                                .as("sending a token that is not a confirmation is pressing a key that is "
                                        + "not PF5, which app/cbl/COUSR03C.cbl:126-129 answers with "
                                        + "CCDA-MSG-INVALID-KEY")
                                .isEqualTo(UNRECOGNISED_CONFIRMATION_CAPTION);
                    });
            assertNothingWasReached();
        }

        @Test
        @DisplayName("the two refusals carry DIFFERENT captions, so the two states stay distinguishable")
        void theTwoRefusalsCarryDifferentCaptions() {
            final String omitted = assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller().deleteUser(USER_ID, null))
                    .actual()
                    .getMessage();
            final String inexact = assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller().deleteUser(USER_ID, "maybe"))
                    .actual()
                    .getMessage();

            assertThat(omitted)
                    .as("collapsing the two would erase the distinction the three-state field model exists "
                            + "to preserve, and each state has its own literal in the source")
                    .isNotEqualTo(inexact);
            assertNothingWasReached();
        }

        @Test
        @DisplayName("both published captions are verified against the frozen source, not against each other")
        void bothPublishedCaptionsComeFromTheFrozenSource() throws IOException {
            // The captions this class puts on the wire are its own published contract, so they are checked
            // against the oracle rather than against another Java file. A transcription that drifted by one
            // character would be invisible to a cross-reference between two Java constants and is caught here.
            assertThat(Files.readString(DELETE_PROGRAM_SOURCE, StandardCharsets.UTF_8))
                    .as("the prompt of :283 must exist in app/cbl/COUSR03C.cbl exactly as it is published")
                    .contains("'" + UNCONFIRMED_DELETE_CAPTION + "'");
            assertThat(Files.readString(SHARED_MESSAGES_SOURCE, StandardCharsets.UTF_8))
                    .as("CCDA-MSG-INVALID-KEY declares the invalid-key caption, padded to PIC X(50)")
                    .contains(UNRECOGNISED_CONFIRMATION_CAPTION);
            assertThat(Files.readString(DELETE_PROGRAM_SOURCE, StandardCharsets.UTF_8))
                    .as("and :128 is the arm that moves it into WS-MESSAGE, which is why this program is the "
                            + "one that publishes it")
                    .contains("CCDA-MSG-INVALID-KEY");
        }

        @Test
        @DisplayName("an update whose body identifier disagrees with the path is refused, and neither of the "
                + "two is silently preferred")
        void anUpdateWithADisagreeingIdentifierIsRefused() {
            final UserUpdateRequest request = new UserUpdateRequest("CU02", "CardDemo", "08/04/26",
                    "COUSR02C", "Update User", "10:15:30", "USER0002", "Ada", "Lovelace", "Sw0rdf1sh",
                    "U", null);

            assertThatExceptionOfType(ValidationException.class)
                    .as("the path addresses the record and the body declares the same field, so a "
                            + "disagreement is ambiguous. Preferring either one would rewrite a record the "
                            + "caller did not address, which is the worst possible way to resolve it")
                    .isThrownBy(() -> controller().updateUser(USER_ID, request))
                    .satisfies(rejection -> {
                        assertThat(rejection.getFieldName()).isEqualTo("userId");
                        assertThat(rejection.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.INVALID);
                    });
            assertNothingWasReached();
        }
    }

    /**
     * Which of the two places the addressed identifier comes from, and what happens when they disagree.
     *
     * <p><strong>Finding, severity Medium - remediated.</strong> The documented body for
     * {@code PUT /api/admin/users/&#123;userId&#125;} carries no {@code userId} - the path variable is the key -
     * and that shape was refused with {@code 'User ID can NOT be empty...'}. The operation was reachable only
     * by duplicating the identifier inside the body, which nothing documents.
     *
     * <p>The source settles which place wins. On first entry {@code COUSR02C} does not wait for an operator
     * to type the identifier: {@code app/cbl/COUSR02C.cbl:L100-L107} tests
     * {@code CDEMO-CU02-USR-SELECTED} - the value the user-list screen left in the commarea - moves it into
     * {@code USRIDINI OF COUSR2AI} and only then performs {@code PROCESS-ENTER-KEY}. The screen field carried
     * the navigation context; it did not originate it. Here the path segment is that context.
     *
     * <p><strong>Finding API-003, severity HIGH - remediated, and the reason this group tests three states
     * rather than two.</strong> An absent member and an empty one used to be handled identically, both
     * overwritten with the path identity. That made the source's own {@code USRIDINI = SPACES OR LOW-VALUES}
     * rejection - {@code :L146-L151} and {@code :L179-L185} - unreachable through this surface. The
     * substitution is now confined to the absent state, which is the only state {@code :L102-L103} models: even
     * on the first-entry leg the commarea selection was moved only when it was itself non-empty, and the
     * re-entry leg a write corresponds to back-fills nothing at all.
     */
    @Nested
    @DisplayName("the addressed identifier comes from the path, and the body may not contradict it")
    class AddressedIdentifier {

        /**
         * Builds the twelve declared members of the update screen, with the identifier under test.
         *
         * @param bodyIdentifier the identifier the body carries, or null to omit it
         * @return a populated request
         */
        private UserUpdateRequest bodyNaming(final String bodyIdentifier) {
            return new UserUpdateRequest("CU02", "CardDemo", "08/04/26", "COUSR02C", "Update User",
                    "10:15:30", bodyIdentifier, "Ada", "Lovelace", "Sw0rdf1sh", "U", null);
        }

        /**
         * Stubs the service so the update completes, and returns what it was asked to update.
         *
         * @return the captor holding the request the service received
         */
        private ArgumentCaptor<UserUpdateRequest> stubbedService() {
            when(userUpdateService.updateUser(any(), any())).thenReturn(
                    new UserUpdateService.UserUpdateScreen("CU02", "CardDemo", "08/04/26", "COUSR02C",
                            "Update User", "10:15:30", USER_ID, "Ada", "Lovelace", "U",
                            "User has been updated ...", null, null, null, false, true));
            return ArgumentCaptor.forClass(UserUpdateRequest.class);
        }

        /**
         * The documented shape works: no identifier in the body, and the service is asked to update the one
         * the path addressed.
         */
        @Test
        @DisplayName("an omitted body identifier is filled from the path - :L102-L103")
        void anOmittedBodyIdentifierIsFilledFromThePath() {
            final ArgumentCaptor<UserUpdateRequest> captured = stubbedService();

            final ResponseEntity<UserUpdateResponse> response =
                    controller().updateUser(USER_ID, bodyNaming(null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(userUpdateService).updateUser(captured.capture(), any());
            assertThat(captured.getValue().userId())
                    .as("the service must be asked to update the record the path addressed, not to reject a "
                            + "field the caller was never asked to send")
                    .isEqualTo(USER_ID);
        }

        /**
         * FINDING API-003, severity HIGH. A body that names the identifier <em>emptily</em> is a different
         * state from a body that does not name it at all, and the source distinguishes them.
         *
         * <p>This test previously asserted the opposite - that an empty value was filled from the path exactly
         * as an absent one is - and that behaviour let a caller walk past a validation branch
         * {@code app/cbl/COUSR02C.cbl} enforces twice, at {@code :L146-L151} and again at {@code :L179-L185},
         * both rejecting {@code USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES}. The expectation is realigned to
         * the frozen source rather than the source being read to fit the expectation.
         *
         * <p>Why the source draws the line here: the substitution at {@code :L102-L103} happens on the FIRST
         * ENTRY leg only, and even there only when {@code CDEMO-CU02-USR-SELECTED NOT = SPACES AND LOW-VALUES},
         * so an empty selection was never moved onto the screen. The re-entry leg at {@code :L106-L111} -
         * which is the leg a write corresponds to - receives the map as typed and back-fills nothing.
         *
         * <p>Asserted as relayed-unchanged rather than as a rejection because the rejection is the service's
         * to raise, in the source's own words;
         * {@code com.cardemo.unit.service.UserUpdateServiceTest} covers that arm over the same four fills.
         * What this class owns is that the controller does not overwrite the value before the service can see
         * it.
         *
         * @param empty the identifier the body carries: blanks or low values, the two fills COBOL treats as
         *     empty
         */
        @ParameterizedTest(name = "an identifier of [{0}] is relayed as sent")
        @ValueSource(strings = {"", " ", "        ", "\u0000", "\u0000\u0000\u0000", " \u0000 "})
        @DisplayName("an empty body identifier is relayed as sent, so the source's own rejection is reachable")
        void anEmptyBodyIdentifierIsRelayedAsSent(final String empty) {
            final ArgumentCaptor<UserUpdateRequest> captured = stubbedService();

            controller().updateUser(USER_ID, bodyNaming(empty));

            verify(userUpdateService).updateUser(captured.capture(), any());
            assertThat(captured.getValue().userId())
                    .as("filling this from the path would make 'User ID can NOT be empty...' unreachable "
                            + "through this surface, which is finding API-003")
                    .isEqualTo(empty);
        }

        /**
         * FINDING API-003, the second half. An empty identifier must not be reported as a
         * <em>disagreement</em>, because it makes no claim about which user is meant.
         *
         * <p>A low-values fill used to reach exactly that wrong branch: {@code String#isBlank()} is false for
         * {@code U+0000} - {@code Character.isWhitespace('\u0000')} is false - so a NUL-filled member escaped
         * the blank test, was compared against the path value, and was refused as naming a different user. The
         * status was right by accident and the reason was wrong.
         *
         * @param empty a fill COBOL treats as empty
         */
        @ParameterizedTest(name = "an identifier of [{0}] is not reported as a disagreement")
        @ValueSource(strings = {"", " ", "\u0000", "\u0000\u0000"})
        @DisplayName("an empty body identifier is not refused as a mismatch with the path")
        void anEmptyBodyIdentifierIsNotAMismatch(final String empty) {
            stubbedService();

            controller().updateUser(USER_ID, bodyNaming(empty));

            verify(userUpdateService).updateUser(any(), any());
        }

        /**
         * An agreeing identifier is relayed as sent. Both places are part of the contract, so neither is
         * dropped when they say the same thing.
         */
        @Test
        @DisplayName("an agreeing body identifier is relayed unchanged")
        void anAgreeingBodyIdentifierIsRelayedUnchanged() {
            final ArgumentCaptor<UserUpdateRequest> captured = stubbedService();

            controller().updateUser(USER_ID, bodyNaming(USER_ID));

            verify(userUpdateService).updateUser(captured.capture(), any());
            assertThat(captured.getValue().userId()).isEqualTo(USER_ID);
        }

        /**
         * Only the identifier is substituted. {@code :L102-L103} is one MOVE into one field; in particular it
         * does not clear the four data fields, which {@code :L158-L161} does separately and only after the
         * edit passes. A substitution that touched anything else would silently alter the caller's edit.
         */
        @Test
        @DisplayName("filling the identifier alters no other declared member")
        void fillingTheIdentifierAltersNoOtherMember() {
            final ArgumentCaptor<UserUpdateRequest> captured = stubbedService();
            final UserUpdateRequest submitted = bodyNaming(null);

            controller().updateUser(USER_ID, submitted);

            verify(userUpdateService).updateUser(captured.capture(), any());
            final UserUpdateRequest relayed = captured.getValue();
            assertThat(relayed.transactionName()).isEqualTo(submitted.transactionName());
            assertThat(relayed.title01()).isEqualTo(submitted.title01());
            assertThat(relayed.currentDate()).isEqualTo(submitted.currentDate());
            assertThat(relayed.programName()).isEqualTo(submitted.programName());
            assertThat(relayed.title02()).isEqualTo(submitted.title02());
            assertThat(relayed.currentTime()).isEqualTo(submitted.currentTime());
            assertThat(relayed.firstName()).isEqualTo(submitted.firstName());
            assertThat(relayed.lastName()).isEqualTo(submitted.lastName());
            assertThat(relayed.password()).isEqualTo(submitted.password());
            assertThat(relayed.userType()).isEqualTo(submitted.userType());
            assertThat(relayed.errorMessage()).isEqualTo(submitted.errorMessage());
        }

        /**
         * And the source's own empty-identifier rejection is not bypassed: it fires whenever the identifier
         * the service receives is blank, which is now exactly when the path is blank as well. A blank path
         * cannot match this route in the running application, so the guard is asserted at the seam rather
         * than through a request that cannot be made.
         */
        @Test
        @DisplayName("a blank path supplies nothing, so the source's own rejection still governs")
        void aBlankPathSuppliesNothing() {
            final ArgumentCaptor<UserUpdateRequest> captured = stubbedService();

            controller().updateUser("   ", bodyNaming(null));

            verify(userUpdateService).updateUser(captured.capture(), any());
            assertThat(captured.getValue().userId())
                    .as("nothing is invented. The blank travels to the service, which applies the source's "
                            + "own 'User ID can NOT be empty...' at :L146-L151")
                    .isEqualTo("   ");
        }
    }

    /**
     * The failure endings: each typed failure keeps its own status and its own code.
     */
    @Nested
    @DisplayName("every typed failure maps to its own status and code")
    class FailureEndings {

        @Test
        @DisplayName("a field rejection answers 400 and publishes the field and the kind")
        void aFieldRejectionAnswers400() {
            final ResponseEntity<ProblemDetail> response = controller().handleValidationFailure(
                    ValidationException.invalidField("userType", "User type must be A or U"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            final ProblemDetail problem = response.getBody();
            assertThat(problem.getDetail())
                    .as("the legacy caption is relayed byte for byte, which is what lets a client display "
                            + "the message the screen displayed")
                    .isEqualTo("User type must be A or U");
            assertThat(property(problem, FIELD_PROPERTY)).isEqualTo("userType");
            assertThat(property(problem, FAILURE_KIND_PROPERTY))
                    .as("the kind is published as the enum itself rather than as its name, so a client "
                            + "deserialising the body sees the same token either way")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(property(problem, ERROR_CODE_PROPERTY)).isEqualTo("CARDDEMO-VALIDATION-REJECTED");
            assertThat(property(problem, CORRELATION_ID_PROPERTY))
                    .as("the correlation identifier falls back to a literal rather than to null when no "
                            + "filter has run, so a body is never published without one")
                    .isEqualTo("unavailable");
        }

        @Test
        @DisplayName("an absent record answers 404, because here the identifier is not a secret")
        void anAbsentRecordAnswers404() {
            final ResponseEntity<ProblemDetail> response = controller().handleRecordNotFound(
                    new RecordNotFoundException("no such user", "USRSEC", USER_ID));

            assertThat(response.getStatusCode())
                    .as("an administrator is already authorised to enumerate the user list, so telling one "
                            + "that a record is absent discloses nothing that CU00 does not - which is why "
                            + "this is a 404 here and a 401 on the sign-on path")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-RECORD-NOT-FOUND");
        }

        @Test
        @DisplayName("a duplicate identifier answers 409")
        void aDuplicateIdentifierAnswers409() {
            final ResponseEntity<ProblemDetail> response = controller().handleDuplicateRecord(
                    new DuplicateRecordException("User ID already exist..."));

            assertThat(response.getStatusCode())
                    .as("FILE STATUS '22' on the add path is a collision rather than a malformed request, "
                            + "so it is 409 and not 400")
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-DUPLICATE-RECORD");
        }

        @Test
        @DisplayName("an unopenable security file answers 503")
        void anUnopenableFileAnswers503() {
            final ResponseEntity<ProblemDetail> response = controller().handleFileUnavailable(
                    new FileUnavailableException("USRSEC not open", "USRSEC", new IllegalStateException()));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-RESOURCE-UNAVAILABLE");
        }

        @Test
        @DisplayName("a physical failure answers 502, not 503, because the request will not succeed on retry")
        void aPhysicalFailureAnswers502() {
            final ResponseEntity<ProblemDetail> response = controller().handleFileAccessFailure(
                    new FileAccessException("rewrite failed", "92", "USRSEC", "REWRITE"));

            assertThat(response.getStatusCode())
                    .as("a FILE STATUS '9x' is a physical or logical failure of the store rather than a "
                            + "closed file, so this operation reports a bad gateway and does not invite a "
                            + "retry the way the 503 of the unavailable path does. The two statuses are "
                            + "deliberately different and neither may be collapsed into the other")
                    .isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY)).isEqualTo("CARDDEMO-IO-FAILURE");
            assertThat(response.getBody().getDetail())
                    .as("the four-character expanded status is an operator diagnostic and stays in the log")
                    .doesNotContain("92");
        }

        @Test
        @DisplayName("an abend answers 500 and publishes neither the culprit nor the reason")
        void anAbendAnswers500() {
            final ResponseEntity<ProblemDetail> response = controller().handleAbend(
                    new FatalProcessingException("0999", "COUSR03C", "USER DELETE FAILED", "unrecoverable"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-PROCESSING-ABEND");
            assertThat(response.getBody().getDetail())
                    .doesNotContain("COUSR03C", "USER DELETE FAILED");
        }

        @Test
        @DisplayName("an unmapped typed failure still answers 500 through the backstop")
        void anUnmappedTypedFailureAnswers500() {
            final ResponseEntity<ProblemDetail> response = controller().handleTypedFailure(
                    new com.cardemo.exception.ConcurrentUpdateException("record changed"));

            assertThat(response.getStatusCode())
                    .as("without the base-type backstop a newly added exception type would reach the "
                            + "container and be rendered by whatever default is configured")
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(property(response.getBody(), ERROR_CODE_PROPERTY))
                    .isEqualTo("CARDDEMO-INTERNAL-FAILURE");
            assertThat(response.getBody().getDetail())
                    .as("and the backstop publishes a fixed detail rather than the exception's own message, "
                            + "which is the only way it can be safe for a type it has never seen")
                    .doesNotContain("record changed");
        }
    }

    /**
     * A navigation past the first page must arrive with the cursor that addresses it.
     *
     * <p>{@code action=PAGE_FORWARD&page=5} with no cursor used to answer {@code 200} reporting
     * {@code pageNumber=5} with an empty row list, which a client cannot distinguish from an exhausted
     * browse.
     */
    @Nested
    @DisplayName("a page past the first is refused without the cursor that addresses it")
    class PagingPrecondition {

        @Test
        @DisplayName("a forward step past the first page names the missing last key")
        void aForwardStepPastTheFirstPageNamesTheLastKey() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller().listUsers("PAGE_FORWARD", null, "5", null, null, null,
                            null))
                    .satisfies(refused -> {
                        assertThat(refused.getFieldName()).isEqualTo("lastKey");
                        assertThat(refused.getFailureKind())
                                .isEqualTo(ValidationException.FailureKind.BLANK);
                    });
            verifyNoInteractions(userListService);
        }

        /**
         * The page token is refused on the enter-key arm too, although that arm does not use it. Ignoring a
         * nine-digit page on one arm while refusing it on the other two published one domain rule and
         * enforced it on two thirds of the operation.
         */
        @Test
        @DisplayName("a page outside the declared domain is refused on every arm, enter-key included")
        void aPageOutsideTheDomainIsRefusedOnEveryArm() {
            for (final String action : new String[] {null, "SUBMIT", "PAGE_FORWARD", "PAGE_BACKWARD"}) {
                assertThatExceptionOfType(ValidationException.class)
                        .as("action=%s must refuse a nine-digit page", action)
                        .isThrownBy(() -> controller().listUsers(action, null, "999999999", null, null,
                                null, null))
                        .satisfies(refused -> assertThat(refused.getFieldName()).isEqualTo("page"));
            }
            verifyNoInteractions(userListService);
        }

        @Test
        @DisplayName("a backward step past the first page names the missing first key")
        void aBackwardStepPastTheFirstPageNamesTheFirstKey() {
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> controller().listUsers("PAGE_BACKWARD", null, "5", "   ", null, null,
                            null))
                    .satisfies(refused -> assertThat(refused.getFieldName()).isEqualTo("firstKey"));
            verifyNoInteractions(userListService);
        }
    }

}
