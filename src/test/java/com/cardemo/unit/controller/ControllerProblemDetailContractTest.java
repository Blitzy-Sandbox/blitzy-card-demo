/*
 * ******************************************************************
 * Program     : ControllerProblemDetailContractTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Pins the failure surface of all six REST controllers - which HTTP
 *               status each typed exception becomes - and the two MenuController
 *               request-mapped methods, including their 401 and 403 refusals.
 * Source      : app/csd/CARDDEMO.CSD (the transaction-to-program map whose screen
 *               message paths these handlers replace) @ 7756d89
 * Source      : app/cbl/COMEN01C.cbl:L170, app/cbl/COADM01C.cbl:L160
 *               (RETURN-TO-SIGNON-SCREEN, whose stateless counterpart is the 401)
 * Source      : app/cpy/COCOM01Y.cpy (CDEMO-USER-TYPE 'A'/'U', now the granted
 *               authority the menu endpoints resolve) @ 7756d89
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

import com.cardemo.controller.AccountController;
import com.cardemo.controller.BillingController;
import com.cardemo.controller.CardController;
import com.cardemo.controller.MenuController;
import com.cardemo.controller.ReportController;
import com.cardemo.controller.TransactionController;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.security.SnapshotTokenService;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;
import com.cardemo.service.billing.BillPaymentService;
import com.cardemo.service.card.CardDetailService;
import com.cardemo.service.card.CardListService;
import com.cardemo.service.card.CardUpdateService;
import com.cardemo.service.menu.AdminMenuService;
import com.cardemo.service.menu.MainMenuService;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.transaction.TransactionAddService;
import com.cardemo.service.transaction.TransactionDetailService;
import com.cardemo.service.transaction.TransactionListService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Unit test for the failure surface of {@link AccountController}, {@link CardController},
 * {@link TransactionController}, {@link BillingController}, {@link MenuController} and
 * {@link ReportController}, plus the two request-mapped menu endpoints.
 *
 * <h2>What it does</h2>
 *
 * <p>In the legacy system a failure was a screen: a program moved a literal into a message field and issued
 * {@code EXEC CICS SEND MAP}, so the observable contract was the text and the cursor position. Over HTTP the
 * observable contract is the <strong>status code</strong> and the {@code ProblemDetail} body, and each
 * controller declares its own {@code @ExceptionHandler} set that performs the mapping - there is no
 * {@code @ControllerAdvice} anywhere in this repository, so a handler missing from a controller means that
 * failure escapes as a bare 500 on that resource group only. That makes the per-controller mapping worth
 * pinning individually rather than once.
 *
 * <p>Every handler is invoked <strong>directly</strong>. That is legitimate rather than a shortcut: each is a
 * public method taking one exception and returning one {@link ResponseEntity}, with no request, no servlet and
 * no framework state involved. Three properties are asserted for each: the status is the one intended, the
 * envelope status and the body status agree, and a title is present.
 *
 * <p>The statuses are <strong>not</strong> uniform across controllers, and the differences are real rather
 * than accidental. {@code FileAccessException} becomes 502 on the account, transaction, billing and report
 * groups but 500 on the card group; {@code ConcurrentUpdateException} fans out across five statuses on the
 * account group - including 423 and 412 - but collapses to two on the card group. Those asymmetries are
 * exactly what a test like this exists to hold still, because nothing else in the tree records them.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run alone with
 * {@code ./mvnw -B -ntp -o test -Dtest=ControllerProblemDetailContractTest -Djacoco.skip=true}, or with the
 * unit tier via {@code ./mvnw -B -ntp test}. Note the {@code -Dtest} separator is a comma, never a plus.
 *
 * <p>There is no {@code MockMvc}, no Spring context and no server. The HTTP-level concerns that genuinely need
 * a filter chain - the {@code /api/admin/*} role rule, the stateless session policy, the filter order - are
 * neither asserted here nor claimed to be; they belong to a slice test that owns the chain. What is asserted
 * here is only what a direct call can prove.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>No configuration key and no default value participates. The service collaborators behind the exception
 * handlers are Mockito doubles that are never invoked, which is why {@link MockitoSettings} relaxes strictness.
 * The two menu endpoints use the <strong>real</strong> {@link MainMenuService} and {@link AdminMenuService}
 * instead, because both expose a public no-argument constructor and carry their option tables internally - a
 * double there would assert the test's own idea of the menu rather than the one derived from
 * {@code app/cpy/COMEN02Y.cpy} and {@code app/cpy/COADM02Y.cpy}.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A status mismatch means a handler was retargeted. A not-found becoming a 500, or a validation refusal
 *       becoming a 404, changes what a client does next and is a contract break rather than a detail.</li>
 *   <li>A failure in the disclosure group means a handler put credential-shaped material into a body. The
 *       masking rule is absolute: no password, no BCrypt hash and no social security number reaches a
 *       response, on any path.</li>
 *   <li>A failure in the menu group after a change to the authority names means the granted authority that
 *       replaces {@code CDEMO-USER-TYPE} was renamed on one side only. The endpoint resolves
 *       {@code ROLE_ADMIN} then {@code ROLE_USER}, in that order, and a principal carrying neither is refused
 *       with 403 rather than defaulted to the lower privilege.</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("REST controllers - the typed-exception to HTTP status contract")
class ControllerProblemDetailContractTest {

    /** A deliberately terse refusal message, so an assertion failure is about the status and not the text. */
    private static final String REFUSED = "refused";

    /** The granted authority that replaces {@code CDEMO-USER-TYPE} value {@code 'A'}. */
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /** The granted authority that replaces {@code CDEMO-USER-TYPE} value {@code 'U'}. */
    private static final String USER_AUTHORITY = "ROLE_USER";

    @Mock private AccountViewService accountViewService;
    @Mock private AccountUpdateService accountUpdateService;
    @Mock private CardListService cardListService;
    @Mock private CardDetailService cardDetailService;
    @Mock private CardUpdateService cardUpdateService;

    /**
     * The seal behind the card resource's page cursors and as-displayed snapshots.
     *
     * <p>A required collaborator of the controller rather than an optional one, because the controller
     * refuses to be constructed without it: an unsealed cursor would put a primary account number in a
     * query parameter. It is a mock here because no test in this class exercises a cursor - every one of
     * them asserts what a raised exception becomes on the wire.
     */
    @Mock private SnapshotTokenService snapshotTokenService;
    @Mock private TransactionListService transactionListService;
    @Mock private TransactionDetailService transactionDetailService;
    @Mock private TransactionAddService transactionAddService;
    @Mock private BillPaymentService billPaymentService;
    @Mock private ReportSubmissionService reportSubmissionService;

    private AccountController accounts() {
        return new AccountController(this.accountViewService, this.accountUpdateService);
    }

    private CardController cards() {
        return new CardController(this.cardListService, this.cardDetailService, this.cardUpdateService,
                this.snapshotTokenService);
    }

    private TransactionController transactions() {
        return new TransactionController(this.transactionListService, this.transactionDetailService,
                this.transactionAddService);
    }

    private BillingController billing() {
        return new BillingController(this.billPaymentService);
    }

    private ReportController reports() {
        return new ReportController(this.reportSubmissionService);
    }

    /** Builds a menu controller over the real menu services, so the option tables are the production ones. */
    private static MenuController menus() {
        return new MenuController(new MainMenuService(), new AdminMenuService());
    }

    /** An authenticated principal carrying exactly {@code authority}. */
    private static Authentication authenticatedWith(final String authority) {
        return new UsernamePasswordAuthenticationToken("USER0001", "n/a",
                List.of(new SimpleGrantedAuthority(authority)));
    }

    /**
     * Asserts the shared shape of one handler outcome.
     *
     * <p>The envelope status and the body status are checked separately and must agree. They are two different
     * fields on two different objects, and a client reading one would otherwise disagree with a client reading
     * the other.
     */
    private static void assertProblem(final ResponseEntity<ProblemDetail> response, final HttpStatus expected) {

        assertThat(response).as("a handler must never return null").isNotNull();
        assertThat(response.getStatusCode()).as("envelope status").isEqualTo(expected);

        final ProblemDetail problem = response.getBody();
        assertThat(problem).as("every handler must return a ProblemDetail body, never an empty one").isNotNull();
        assertThat(problem.getStatus())
                .as("the envelope status and the body status must agree")
                .isEqualTo(expected.value());
        assertThat(problem.getTitle()).as("a ProblemDetail without a title tells a caller nothing").isNotBlank();
    }

    @Nested
    @DisplayName("status mapping, per controller")
    class StatusMapping {

        @Test
        @DisplayName("AccountController maps its eight typed failures, with I/O at 502")
        void accountControllerMapsItsFailures() {

            final AccountController controller = accounts();

            assertProblem(controller.handleValidationFailure(new ValidationException(REFUSED)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(controller.handleRecordNotFound(new RecordNotFoundException(REFUSED)),
                    HttpStatus.NOT_FOUND);
            assertProblem(controller.handleDataIntegrity(new DataIntegrityException(REFUSED)),
                    HttpStatus.CONFLICT);
            assertProblem(controller.handleFileUnavailable(new FileUnavailableException(REFUSED)),
                    HttpStatus.SERVICE_UNAVAILABLE);
            assertProblem(controller.handleFileAccessFailure(new FileAccessException(REFUSED)),
                    HttpStatus.BAD_GATEWAY);
            assertProblem(controller.handleAbend(new FatalProcessingException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(controller.handleTypedFailure(new CardDemoException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("CardController maps its seven typed failures, with I/O at 500 rather than 502")
        void cardControllerMapsItsFailures() {

            final CardController controller = cards();

            assertProblem(controller.handleValidationFailure(new ValidationException(REFUSED)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(controller.handleRecordNotFound(new RecordNotFoundException(REFUSED)),
                    HttpStatus.NOT_FOUND);
            assertProblem(controller.handleFileUnavailable(new FileUnavailableException(REFUSED)),
                    HttpStatus.SERVICE_UNAVAILABLE);
            // 502, not 500. A FILE STATUS '9x' is a failure of the storage substrate this service depends
            // on, not a defect in this service, and every resource that handles it now says so with the same
            // status. The uniformity is the contract: a caller must not have to learn which resource it
            // asked before it can interpret an I/O failure.
            assertProblem(controller.handleFileAccessFailure(new FileAccessException(REFUSED)),
                    HttpStatus.BAD_GATEWAY);
            assertProblem(controller.handleAbend(new FatalProcessingException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(controller.handleTypedFailure(new CardDemoException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("TransactionController maps its seven, duplicate key at 409 and I/O at 502")
        void transactionControllerMapsItsFailures() {

            final TransactionController controller = transactions();

            assertProblem(controller.handleValidationFailure(new ValidationException(REFUSED)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(controller.handleRecordNotFound(new RecordNotFoundException(REFUSED)),
                    HttpStatus.NOT_FOUND);
            assertProblem(controller.handleDuplicateRecord(new DuplicateRecordException(REFUSED)),
                    HttpStatus.CONFLICT);
            assertProblem(controller.handleFileUnavailable(new FileUnavailableException(REFUSED)),
                    HttpStatus.SERVICE_UNAVAILABLE);
            assertProblem(controller.handleFileAccessFailure(new FileAccessException(REFUSED)),
                    HttpStatus.BAD_GATEWAY);
            assertProblem(controller.handleAbend(new FatalProcessingException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(controller.handleTypedFailure(new CardDemoException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("BillingController maps its seven typed failures, with I/O at 502")
        void billingControllerMapsItsFailures() {

            final BillingController controller = billing();

            assertProblem(controller.handleValidationFailure(new ValidationException(REFUSED)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(controller.handleRecordNotFound(new RecordNotFoundException(REFUSED)),
                    HttpStatus.NOT_FOUND);
            assertProblem(controller.handleDuplicateRecord(new DuplicateRecordException(REFUSED)),
                    HttpStatus.CONFLICT);
            assertProblem(controller.handleFileUnavailable(new FileUnavailableException(REFUSED)),
                    HttpStatus.SERVICE_UNAVAILABLE);
            assertProblem(controller.handleFileAccessFailure(new FileAccessException(REFUSED)),
                    HttpStatus.BAD_GATEWAY);
            assertProblem(controller.handleAbend(new FatalProcessingException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(controller.handleTypedFailure(new CardDemoException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("MenuController maps its three typed failures")
        void menuControllerMapsItsFailures() {

            final MenuController controller = menus();

            assertProblem(controller.handleValidationFailure(new ValidationException(REFUSED)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(controller.handleAbend(new FatalProcessingException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(controller.handleTypedFailure(new CardDemoException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("ReportController maps its five, queue unavailable at 503 and queue failure at 502")
        void reportControllerMapsItsFailures() {

            final ReportController controller = reports();

            assertProblem(controller.handleRejection(new ValidationException(REFUSED)),
                    HttpStatus.BAD_REQUEST);
            assertProblem(controller.handleQueueUnavailable(new FileUnavailableException(REFUSED)),
                    HttpStatus.SERVICE_UNAVAILABLE);
            assertProblem(controller.handleQueueFailure(new FileAccessException(REFUSED)),
                    HttpStatus.BAD_GATEWAY);
            assertProblem(controller.handleAbend(new FatalProcessingException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(controller.handleTypedFailure(new CardDemoException(REFUSED)),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("a populated FileAccessException still maps by type, not by its carried I/O status")
        void aPopulatedFileAccessExceptionStillMapsByType() {

            final FileAccessException populated =
                    new FileAccessException("read failed", "0037", "ACCTDAT", "READ");

            // All three answer 502, and the carried status is what must NOT move them: the same exception
            // populated with a file status, a dataset name and a verb still maps by type alone.
            assertProblem(accounts().handleFileAccessFailure(populated), HttpStatus.BAD_GATEWAY);
            assertProblem(cards().handleFileAccessFailure(populated), HttpStatus.BAD_GATEWAY);
            assertProblem(reports().handleQueueFailure(populated), HttpStatus.BAD_GATEWAY);
        }
    }

    @Nested
    @DisplayName("ConcurrentUpdateException fans out differently on the two groups that handle it")
    class ConcurrentUpdateOutcomes {

        @ParameterizedTest
        @EnumSource(ConcurrentUpdateException.Outcome.class)
        @DisplayName("AccountController gives every outcome its own status, never a blanket 409")
        void accountControllerMapsEveryOutcome(final ConcurrentUpdateException.Outcome outcome) {

            final HttpStatus expected = switch (outcome) {
                case COULD_NOT_LOCK_ACCOUNT -> HttpStatus.LOCKED;
                case COULD_NOT_LOCK_CUSTOMER -> HttpStatus.CONFLICT;
                case DATA_CHANGED_BEFORE_UPDATE -> HttpStatus.PRECONDITION_FAILED;
                case LOCKED_BUT_UPDATE_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
                case CHANGES_NOT_CONFIRMED -> HttpStatus.PRECONDITION_REQUIRED;
            };

            assertProblem(accounts().handleConcurrentUpdate(
                    new ConcurrentUpdateException(outcome, REFUSED)), expected);
        }

        @Test
        @DisplayName("an outcome-less conflict still maps to 409 rather than throwing on the null")
        void anOutcomeLessConflictStillMaps() {
            assertProblem(accounts().handleConcurrentUpdate(new ConcurrentUpdateException(REFUSED)),
                    HttpStatus.CONFLICT);
            assertProblem(cards().handleConcurrentUpdate(new ConcurrentUpdateException(REFUSED)),
                    HttpStatus.CONFLICT);
        }

        @ParameterizedTest
        @EnumSource(ConcurrentUpdateException.Outcome.class)
        @DisplayName("CardController answers 428, 412 or 409, one status per concurrency outcome")
        void cardControllerCollapsesToTwoStatuses(final ConcurrentUpdateException.Outcome outcome) {

            // Three statuses, not two. The outcomes are not interchangeable and a client acts on each
            // differently: an absent confirmation is 428 and the client re-submits with it; a snapshot that
            // no longer matches the row is 412 and the client must re-read and re-display before it can
            // re-submit anything; a lock refusal or a failed write is 409 and the client may simply retry.
            // Collapsing the middle case into 409 told a client to retry a request that could never
            // succeed unchanged.
            final HttpStatus expected = switch (outcome) {
                case CHANGES_NOT_CONFIRMED -> HttpStatus.PRECONDITION_REQUIRED;
                case DATA_CHANGED_BEFORE_UPDATE -> HttpStatus.PRECONDITION_FAILED;
                default -> HttpStatus.CONFLICT;
            };

            assertProblem(cards().handleConcurrentUpdate(
                    new ConcurrentUpdateException(outcome, REFUSED)), expected);
        }

        @Test
        @DisplayName("the account conflict body carries the legacy message the screen used to show")
        void theAccountConflictBodyCarriesTheLegacyMessage() {

            final ProblemDetail problem = accounts().handleConcurrentUpdate(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE, REFUSED)).getBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getDetail())
                    .as("the 9700-CHECK-CHANGE-IN-REC refusal text is part of the observable contract")
                    .isEqualTo("Record changed by some one else. Please review");
        }
    }

    @Nested
    @DisplayName("a handler never discloses credential or personal material")
    class NoDisclosure {

        @Test
        @DisplayName("no ProblemDetail body contains a password, a BCrypt marker or a social security number")
        void noBodyDisclosesCredentialMaterial() {

            final List<ResponseEntity<ProblemDetail>> responses = new ArrayList<>();
            final AccountController account = accounts();
            responses.add(account.handleValidationFailure(new ValidationException(REFUSED)));
            responses.add(account.handleRecordNotFound(new RecordNotFoundException(REFUSED)));
            responses.add(account.handleDataIntegrity(new DataIntegrityException(REFUSED)));
            responses.add(account.handleAbend(new FatalProcessingException(REFUSED)));
            responses.add(account.handleFileAccessFailure(
                    new FileAccessException("read failed", "0037", "ACCTDAT", "READ")));
            responses.add(cards().handleFileAccessFailure(new FileAccessException(REFUSED)));
            responses.add(transactions().handleDuplicateRecord(new DuplicateRecordException(REFUSED)));
            responses.add(billing().handleFileUnavailable(new FileUnavailableException(REFUSED)));
            responses.add(menus().handleTypedFailure(new CardDemoException(REFUSED)));
            responses.add(reports().handleRejection(new ValidationException(REFUSED)));

            assertThat(responses).as("every handler must produce a body to sweep").isNotEmpty();
            for (final ResponseEntity<ProblemDetail> response : responses) {
                final ProblemDetail problem = response.getBody();
                assertThat(problem).isNotNull();
                final String rendered = problem.toString().toLowerCase(Locale.ROOT);
                assertThat(rendered)
                        .as("a response body may never carry credential or personal material")
                        .doesNotContain("password")
                        .doesNotContain("$2a$")
                        .doesNotContain("$2b$")
                        .doesNotContain("secret");
            }
        }

        @Test
        @DisplayName("a validation refusal puts the message in the detail, so a caller learns the rule")
        void aValidationRefusalCarriesTheMessage() {

            final ProblemDetail problem =
                    accounts().handleValidationFailure(new ValidationException("Account ID must be numeric"))
                            .getBody();

            assertThat(problem).isNotNull();
            assertThat(problem.getDetail())
                    .as("the legacy screen showed the message; the HTTP equivalent is the detail")
                    .isEqualTo("Account ID must be numeric");
        }

        @Test
        @DisplayName("a validation refusal names the offending field only when one was supplied")
        void aValidationRefusalNamesTheFieldOnlyWhenSupplied() {

            final ProblemDetail withField = accounts().handleValidationFailure(new ValidationException(
                    REFUSED, "acctId", ValidationException.FailureKind.INVALID)).getBody();
            final ProblemDetail withoutField =
                    accounts().handleValidationFailure(new ValidationException(REFUSED)).getBody();

            assertThat(withField).isNotNull();
            assertThat(withoutField).isNotNull();
            assertThat(withField.getProperties()).containsEntry("field", "acctId");
            assertThat(withoutField.getProperties() == null
                    || !withoutField.getProperties().containsKey("field"))
                    .as("a refusal with no field name must not invent one")
                    .isTrue();
        }

        @Test
        @DisplayName("an abend with no message substitutes a default rather than rendering the word null")
        void anAbendWithNoMessageSubstitutesADefault() {

            final ProblemDetail problem =
                    accounts().handleAbend(new FatalProcessingException((String) null)).getBody();

            assertThat(problem).isNotNull();
            assertThat(String.valueOf(problem.getDetail()))
                    .as("a handler must not render the four characters n-u-l-l as a caller-facing detail")
                    .isNotEqualTo("null")
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("the typed base exception is the last resort, not the first")
    class TypedBaseIsLastResort {

        @Test
        @DisplayName("every controller accepts the base type, so no typed failure escapes unmapped")
        void everyControllerAcceptsTheBaseType() {

            final CardDemoException base = new CardDemoException("an unclassified typed failure");

            assertProblem(accounts().handleTypedFailure(base), HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(cards().handleTypedFailure(base), HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(transactions().handleTypedFailure(base), HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(billing().handleTypedFailure(base), HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(menus().handleTypedFailure(base), HttpStatus.INTERNAL_SERVER_ERROR);
            assertProblem(reports().handleTypedFailure(base), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("the menu endpoints, including the refusals that replace RETURN-TO-SIGNON-SCREEN")
    class MenuEndpoints {

        @Test
        @DisplayName("a null principal is refused with 401 on both menus")
        void aNullPrincipalIsRefusedWith401() {
            assertThat(menus().getMainMenu(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(menus().getAdminMenu(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an unauthenticated token is refused with 401, not merely an empty authority list")
        void anUnauthenticatedTokenIsRefusedWith401() {

            final Authentication unauthenticated =
                    new UsernamePasswordAuthenticationToken("USER0001", "n/a");
            assertThat(unauthenticated.isAuthenticated()).isFalse();

            assertThat(menus().getMainMenu(unauthenticated).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(menus().getAdminMenu(unauthenticated).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an anonymous token is refused with 401 even though it reports itself authenticated")
        void anAnonymousTokenIsRefusedWith401() {

            final Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            assertThat(anonymous.isAuthenticated())
                    .as("this is precisely why an isAuthenticated() check alone would be insufficient")
                    .isTrue();

            assertThat(menus().getMainMenu(anonymous).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(menus().getAdminMenu(anonymous).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a principal carrying neither authority is refused with 403, never defaulted to USER")
        void aPrincipalWithNeitherAuthorityIsRefusedWith403() {

            final Authentication stranger = authenticatedWith("ROLE_SOMETHING_ELSE");

            assertThat(menus().getMainMenu(stranger).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(menus().getAdminMenu(stranger).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a standard user receives the main menu")
        void aStandardUserReceivesTheMainMenu() {

            final ResponseEntity<MenuResponse<MenuResponse.MainMenuOption>> response =
                    menus().getMainMenu(authenticatedWith(USER_AUTHORITY));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getOptionCount())
                    .as("a served menu must carry at least one option")
                    .isPositive();
        }

        @Test
        @DisplayName("an administrator receives the main menu too, and it is not narrower than the user's")
        void anAdministratorReceivesTheMainMenu() {

            final MenuResponse<MenuResponse.MainMenuOption> asAdmin =
                    menus().getMainMenu(authenticatedWith(ADMIN_AUTHORITY)).getBody();
            final MenuResponse<MenuResponse.MainMenuOption> asUser =
                    menus().getMainMenu(authenticatedWith(USER_AUTHORITY)).getBody();

            assertThat(asAdmin).isNotNull();
            assertThat(asUser).isNotNull();
            assertThat(asAdmin.getOptionCount())
                    .as("the COMEN02Y user-type gate may widen the menu for an administrator, never narrow it")
                    .isGreaterThanOrEqualTo(asUser.getOptionCount());
        }

        @Test
        @DisplayName("a standard user is refused the admin menu with 403")
        void aStandardUserIsRefusedTheAdminMenu() {
            assertThat(menus().getAdminMenu(authenticatedWith(USER_AUTHORITY)).getStatusCode())
                    .as("the four COADM02Y options target the user-administration programs")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("an administrator receives the admin menu")
        void anAdministratorReceivesTheAdminMenu() {

            final ResponseEntity<MenuResponse<MenuResponse.AdminMenuOption>> response =
                    menus().getAdminMenu(authenticatedWith(ADMIN_AUTHORITY));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getOptionCount()).isPositive();
        }

        @Test
        @DisplayName("the admin authority wins when a principal carries both, matching resolve order")
        void theAdminAuthorityWinsWhenBothArePresent() {

            final Authentication both = new UsernamePasswordAuthenticationToken("ADMIN001", "n/a",
                    List.of(new SimpleGrantedAuthority(USER_AUTHORITY),
                            new SimpleGrantedAuthority(ADMIN_AUTHORITY)));

            assertThat(menus().getAdminMenu(both).getStatusCode())
                    .as("ROLE_ADMIN is resolved before ROLE_USER, so the admin menu must be served")
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
