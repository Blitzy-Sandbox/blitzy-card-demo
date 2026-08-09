/*
 * ******************************************************************
 * Program     : AccountControllerTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Wire-contract guard for AccountController. Pins the properties a
 *               reader cannot confirm from the service tier: that the nine
 *               protected customer values never leave, that the as-displayed
 *               snapshot is server-issued and header-borne, that the confirm
 *               parameter admits exactly two spellings, that a 404 is not an
 *               existence oracle, and that one file status yields one status code.
 * Source      : app/cbl/COACTVWC.cbl (941 lines), app/cbl/COACTUPC.cbl (4,236),
 *               app/csd/CARDDEMO.CSD, app/cpy-bms/COACTVW.CPY, COACTUP.CPY
 *               @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.controller.AccountController;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.dto.AccountUpdateResponse;
import com.cardemo.model.dto.AccountViewResponse;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Customer;
import com.cardemo.service.account.AccountUpdateService;
import com.cardemo.service.account.AccountViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Unit tests for {@link AccountController}, the REST replacement for CICS transactions {@code CAVW} and
 * {@code CAUP} at traceability anchor commit {@code 7756d89}.
 *
 * <p><strong>1. What it does.</strong> This class asserts the wire contract and nothing else; the two
 * services own the business behaviour and are pinned by their own suites. Five properties are asserted here,
 * each of which a service-tier test is structurally unable to observe.</p>
 *
 * <ul>
 *   <li><em>The nine protected customer values never leave.</em> The social security number, the date of
 *       birth, the three names, both telephone numbers, the government-issued identifier and the electronic
 *       funds account identifier are withheld from the read response, and the whole submitted map is withheld
 *       from the write response.</li>
 *   <li><em>The as-displayed snapshot travels in the request body, sealed.</em> The read publishes it as a
 *       single opaque {@code snapshot} member that {@code AccountUpdateService.sealSnapshotForUpdate}
 *       produced, and the write echoes that value back unaltered, per transformation Rule 7. It is sealed
 *       rather than readable for two independent reasons: the group carries nine protected customer values,
 *       and the comparison at {@code app/cbl/COACTUPC.cbl:4109-4193} exists to detect that the record moved
 *       under the operator, which it cannot do if the operator supplies its own second operand.</li>
 *   <li><em>The confirmation admits exactly two spellings.</em> The framework's {@code Boolean} binding
 *       accepts six; on the one parameter that decides whether two datasets are written, that is not
 *       acceptable.</li>
 *   <li><em>A {@code 404} is not an existence oracle.</em> The record key is logged, never returned.</li>
 *   <li><em>One file status yields one status code.</em> {@code 502} for the {@code '9x'} family, as on the
 *       four other resources.</li>
 *   </ul>
 *
 * <p><strong>2. How to run, build and test.</strong> Bound to the Surefire tier by its {@code *Test} name
 * under {@code src/test/java/com/cardemo/unit}.</p>
 *
 * <pre>
 * ./mvnw -B -ntp test -Dtest=AccountControllerTest
 * ./mvnw -B -ntp clean verify
 * </pre>
 *
 * <p><strong>3. Key configuration and defaults.</strong> Both services are mocked and no Spring context is
 * started: every handler and every exception handler is a plain method call. What this class tests is where
 * the sealed value travels and that it arrives unaltered; the sealing itself, and the comparison it feeds,
 * are pinned by {@code SnapshotTokenServiceTest} and {@code AccountUpdateServiceTest}.</p>
 *
 * <p><strong>4. Common failure modes.</strong> A failure in the privacy group means a protected customer
 * value reached a diagnostic rendering or the serialised response. A failure in the snapshot group means the
 * read stopped sealing the group, started publishing it readably, or the write stopped relaying the sealed
 * value unaltered - the first and third refuse every update, the second discloses nine protected values. A
 * failure in the confirmation group means the parameter is being coerced again. A failure in the disclosure
 * group means a {@code 404} is naming keys.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("AccountController - the wire contract for CAVW and CAUP")
class AccountControllerTest {

    /** {@code ACCTSIDI PIC X(11)} at {@code app/cpy-bms/COACTVW.CPY}. */
    private static final String ACCOUNT_ID = "00000000011";

    /** {@code CUST-SSN PIC 9(09)}, a value no response may carry. */
    private static final String SSN = "123456789";

    /** {@code CUST-DOB} in its ten-character form, likewise withheld. */
    private static final String DATE_OF_BIRTH = "1980-01-15";

    /** {@code CUST-GOVT-ISSUED-ID}, likewise. */
    private static final String GOVERNMENT_ID = "WA-DL-9988776";

    /**
     * {@code CUST-EFT-ACCOUNT-ID PIC X(10)}, likewise.
     *
     * <p>Deliberately not a digit run. An all-numeric fixture such as {@code "0000000001"} is a substring of
     * the eleven-digit account identifier, so a "does not contain" assertion over it would fail against a
     * response that leaked nothing - a false alarm that would be read as a real leak.</p>
     */
    private static final String EFT_ACCOUNT_ID = "EFT8877665";

    /** {@code CUST-PHONE-NUM-1}, likewise. */
    private static final String PHONE_1 = "(206)555-0100";

    /**
     * A stand-in for the sealed as-displayed snapshot.
     *
     * <p>An arbitrary base64url run rather than a real sealed value, because the update service is mocked
     * here and never opens it. What this tier asserts is that the value the read publishes is the value the
     * write relays, unaltered and by reference; {@code SnapshotTokenServiceTest} owns the sealing itself and
     * {@code AccountUpdateServiceTest} owns the round trip through the bean.</p>
     */
    private static final String SEALED_SNAPSHOT = "c2VhbGVkLWFjY291bnQtdXBkYXRlLXNuYXBzaG90";

    /**
     * The authenticated principal {@link #principal} presents, which the controller must relay to both
     * service entry points so that the sealed value is bound to the operator who was shown the screen.
     */
    private static final String SUBJECT = "USER0001";

    /** The account-view service, mocked because this tier reaches no database. */
    @Mock
    private AccountViewService accountViewService;

    /** The account-update service, mocked on the same terms. */
    @Mock
    private AccountUpdateService accountUpdateService;

    /** The controller under test, rebuilt before every test. */
    private AccountController controller;

    /** A principal that satisfies the operation's authentication guard. */
    private Authentication principal;

    /**
     * Builds the controller and an authenticated principal, so no state survives a test.
     */
    @BeforeEach
    void createControllerUnderTest() {
        controller = new AccountController(accountViewService, accountUpdateService);
        principal = new TestingAuthenticationToken(SUBJECT, "n/a", "ROLE_USER");
    }

    /**
     * Builds the thirty-seven-component projection the view service produces, populating every one of the
     * nine protected members so that a leak would be detectable.
     *
     * @return a populated projection
     */
    private static AccountDto viewProjection() {
        return new AccountDto("CAVW", "CardDemo", "08/03/26", "COACTVWC", "View Account", "10:15:30",
                ACCOUNT_ID, "Y", "2000-01-01", "2020.00", "2025-12-31", "1020.00", "2020-06-15",
                "194.00", "0.00", "ZEROBAL", "0.00", "000000001", SSN, DATE_OF_BIRTH, "750",
                "MARGARET", "A", "GOLD", "100 MAIN ST", "WA", "APT 1", "98101", "SEATTLE", "USA",
                PHONE_1, GOVERNMENT_ID, "(425)555-0199", EFT_ACCOUNT_ID, "Y", null, null);
    }

    /**
     * Builds what one traversal of the cross-reference, account and customer chain hands back: the
     * projection plus the two master records the sealed snapshot is projected from.
     *
     * <p>The records travel because the {@code GET} answers with the projection <em>and</em> with the
     * snapshot, and both are projections of the same three rows. Reading them twice - once for the screen
     * and once for the snapshot - opened two read-only transactions and issued the identical three
     * statements again for one request.
     *
     * @return the projection and its two records
     */
    private static AccountViewService.AccountViewRecords viewRecords() {
        final Account account = new Account(1L, "Y", new BigDecimal("194.00"), new BigDecimal("2020.00"),
                new BigDecimal("1020.00"), "2000-01-01", "2025-12-31", "2020-06-15",
                BigDecimal.ZERO, BigDecimal.ZERO, "98101", "ZEROBAL");
        final Customer customer = new Customer(1L, "MARGARET", "A", "GOLD", "100 MAIN ST", "APT 1",
                "SEATTLE", "WA", "USA", "98101", PHONE_1, "(425)555-0199", SSN, GOVERNMENT_ID,
                DATE_OF_BIRTH, EFT_ACCOUNT_ID, "Y", "750");
        return new AccountViewService.AccountViewRecords(viewProjection(), account, customer);
    }

    /**
     * Builds a submitted map, with the sealed snapshot either present or absent.
     *
     * @param snapshot the sealed as-displayed snapshot, or null to omit it
     * @return a populated request
     */
    private static AccountUpdateRequest updateRequest(final String snapshot) {
        // The record is immutable and declares fifty-four screen fields plus the two groups, so the
        // fixture supplies the account identifier - the one field these assertions read - and leaves the
        // rest absent. The service is mocked here, so no edit runs over them.
        return new AccountUpdateRequest(null, null, null, null, null, null,
                ACCOUNT_ID,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                snapshot, null);
    }

    /**
     * Builds a snapshot group carrying one protected value, so a body-carried snapshot is realistic.
     *
     * @return a populated snapshot group
     */
    private static AccountUpdateRequest.OldDetails suppliedSnapshot() {
        return new AccountUpdateRequest.OldDetails(ACCOUNT_ID, "Y", "000000019400", "000000202000",
                "000000102000", "20000101", "20251231", "20200615", "000000000000", "000000000000",
                "ZEROBAL", "000000001", "MARGARET", "A", "GOLD", "100 MAIN ST", "APT 1", "SEATTLE",
                "WA", "USA", "98101", PHONE_1, "(425)555-0199", SSN, GOVERNMENT_ID, "19800115",
                EFT_ACCOUNT_ID, "Y", "750");
    }

    /**
     * Builds the service outcome the write projects onto its response.
     *
     * @param changeAction the recorded outcome
     * @return a populated result whose {@code screen} carries the protected values, so a leak is detectable
     */
    private static AccountUpdateService.AccountUpdateResult updateResult(
            final AccountUpdateService.ChangeAction changeAction) {
        return new AccountUpdateService.AccountUpdateResult(
                AccountUpdateService.ResponseKind.MAP,
                changeAction,
                updateRequest(SEALED_SNAPSHOT).withOldDetails(suppliedSnapshot()),
                new AccountUpdateService.Navigation("CAUP", "COACTUPC", "CM00", "COMEN01C", "COACTUP",
                        "CACTUPA"),
                List.of(new AccountUpdateService.FieldAttribute("ACSTTUS", "attribute", "DFHBMPRF")),
                "Changes validated.Press F5 to save",
                null);
    }

    /**
     * Nine customer values may never leave, and neither may the submitted map.
     */
    @Nested
    @DisplayName("privacy - the nine protected values and the whole submitted map stay server side")
    class PrivacyContract {

        /**
         * The read response declares no component named for any withheld value, and carries none of them.
         * Both halves matter: the first is a structural guarantee that survives refactoring, the second
         * proves this particular projection honours it.
         */
        @Test
        @DisplayName("the read response neither declares nor carries any of the nine withheld values")
        void theReadResponseWithholdsTheNineProtectedValues() {
            when(accountViewService.viewAccountWithRecords(ACCOUNT_ID)).thenReturn(viewRecords());
            when(accountUpdateService.sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT)))
                    .thenReturn(SEALED_SNAPSHOT);

            final AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, principal).getBody();

            assertThat(body).isNotNull();
            assertThat(Arrays.stream(AccountViewResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .doesNotContainAnyElementsOf(AccountViewResponse.WITHHELD_COMPONENTS);
            assertThat(body.toString())
                    .doesNotContain(SSN)
                    .doesNotContain(DATE_OF_BIRTH)
                    .doesNotContain(GOVERNMENT_ID)
                    .doesNotContain(EFT_ACCOUNT_ID)
                    .doesNotContain(PHONE_1)
                    .doesNotContain("MARGARET")
                    .doesNotContain("GOLD");
        }

        /**
         * What the read does carry is what an account screen needs: the identifier, the balances, the dates
         * and the address. Asserted so that withholding is not mistaken for emptying.
         */
        @Test
        @DisplayName("the read response still carries every value the account screen needs")
        void theReadResponseCarriesWhatTheScreenNeeds() {
            when(accountViewService.viewAccountWithRecords(ACCOUNT_ID)).thenReturn(viewRecords());
            when(accountUpdateService.sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT)))
                    .thenReturn(SEALED_SNAPSHOT);

            final AccountViewResponse body = controller.viewAccount(ACCOUNT_ID, principal).getBody();

            assertThat(body).isNotNull();
            assertThat(body.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(body.accountStatus()).isEqualTo("Y");
            assertThat(body.currentBalance()).isEqualTo("194.00");
            assertThat(body.creditLimit()).isEqualTo("2020.00");
            assertThat(body.customerFicoScore()).isEqualTo("750");
            assertThat(body.addressZip()).isEqualTo("98101");
        }

        /**
         * The write response carries four members and none of the three that would leak: the submitted map,
         * the CICS navigation and the 3270 field attributes.
         */
        @Test
        @DisplayName("the write response carries no submitted map, no navigation and no 3270 attributes")
        void theWriteResponseIsApiNative() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE));

            final AccountUpdateResponse body = controller
                    .updateAccount(updateRequest(null), "true", principal)
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(Arrays.stream(AccountUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsExactly("accountId", "changeAction", "applied", "informationMessage",
                            "errorMessage");
            assertThat(body.toString())
                    .doesNotContain(SSN)
                    .doesNotContain(GOVERNMENT_ID)
                    .doesNotContain("COACTUPC")
                    .doesNotContain("DFHBMPRF");
        }

        /**
         * {@code applied} is true for exactly one change action, and false for the two failure markers, so a
         * client cannot read a lock failure as a completed write.
         */
        @Test
        @DisplayName("applied is true only for CHANGES_OKAYED_AND_DONE")
        void appliedIsTrueOnlyForTheCompletedOutcome() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_LOCK_ERROR))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_BUT_FAILED));

            assertThat(applyOnce().applied()).isTrue();
            assertThat(applyOnce().applied()).isFalse();
            assertThat(applyOnce().applied()).isFalse();
        }

        /**
         * The change action reaches the response by name, which is what preserves the source's outcome
         * vocabulary over HTTP.
         */
        @Test
        @DisplayName("the change action is reported by name")
        void theChangeActionIsReportedByName() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(AccountUpdateService.ChangeAction.SHOW_DETAILS));

            assertThat(applyOnce().changeAction()).isEqualTo("SHOW_DETAILS");
        }

        /**
         * Runs one confirmed write and returns its body.
         *
         * @return the response body, never null
         */
        private AccountUpdateResponse applyOnce() {
            return controller
                    .updateAccount(updateRequest(null), "true", principal)
                    .getBody();
        }
    }

    /**
     * The as-displayed snapshot is sealed by the read, travels in the update's request body as one opaque
     * member, and is relayed to the service byte for byte.
     */
    @Nested
    @DisplayName("the update precondition travels in the request body, sealed")
    class SnapshotContract {

        /**
         * The read publishes the sealed value the matching write must echo. Without it the field-by-field
         * comparison of {@code app/cbl/COACTUPC.cbl:4109-4193} would have no operands and every write
         * would be refused, so this is what makes the update usable at all.
         */
        @Test
        @DisplayName("the read publishes the sealed as-displayed snapshot, exactly as the service issued it")
        void theReadPublishesTheSealedSnapshot() {
            when(accountViewService.viewAccountWithRecords(ACCOUNT_ID)).thenReturn(viewRecords());
            when(accountUpdateService.sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT)))
                    .thenReturn(SEALED_SNAPSHOT);

            final ResponseEntity<AccountViewResponse> response =
                    controller.viewAccount(ACCOUNT_ID, principal);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().snapshot())
                    .as("the value is relayed unaltered: the write must present the same bytes")
                    .isEqualTo(SEALED_SNAPSHOT);
        }

        /**
         * The read seals for the <em>authenticated</em> operator, not for the account alone. That binding is
         * what makes a snapshot issued to one operator useless to another, so the controller must relay the
         * principal rather than a constant or the path variable.
         */
        @Test
        @DisplayName("the read seals for the authenticated principal, not for the account alone")
        void theReadSealsForTheAuthenticatedPrincipal() {
            when(accountViewService.viewAccountWithRecords(ACCOUNT_ID)).thenReturn(viewRecords());
            when(accountUpdateService.sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT)))
                    .thenReturn(SEALED_SNAPSHOT);

            controller.viewAccount(ACCOUNT_ID, principal);

            verify(accountUpdateService).sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT));
        }

        /**
         * The whole reason the value is sealed rather than published as a group: the group carries nine
         * protected customer values, and a readable member would put every one of them on the wire on a
         * plain read. Asserted against the <em>serialised</em> form rather than {@code toString}, because
         * serialisation is what a client actually receives and is the only rendering a suppressed accessor
         * cannot quietly reappear in.
         */
        @Test
        @DisplayName("no protected value appears in the serialised read response")
        void theSerialisedReadResponseCarriesNoProtectedValue() throws Exception {
            when(accountViewService.viewAccountWithRecords(ACCOUNT_ID)).thenReturn(viewRecords());
            when(accountUpdateService.sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT)))
                    .thenReturn(SEALED_SNAPSHOT);

            final String json = new ObjectMapper()
                    .writeValueAsString(controller.viewAccount(ACCOUNT_ID, principal).getBody());

            assertThat(json)
                    .as("the sealed member is what travels, and it is the only thing that travels")
                    .contains("\"snapshot\":\"" + SEALED_SNAPSHOT + "\"")
                    .doesNotContain("oldDetails")
                    .doesNotContain(SSN)
                    .doesNotContain(DATE_OF_BIRTH)
                    .doesNotContain("19800115")
                    .doesNotContain(GOVERNMENT_ID)
                    .doesNotContain(EFT_ACCOUNT_ID)
                    .doesNotContain(PHONE_1)
                    .doesNotContain("(425)555-0199")
                    .doesNotContain("MARGARET")
                    .doesNotContain("GOLD");
        }

        /**
         * A failure to seal the snapshot is not suppressed. Returning {@code 200} with no value would answer
         * with a response the client cannot update from, which is the gap this contract closes.
         */
        @Test
        @DisplayName("a failure to seal the snapshot propagates rather than yielding a valueless 200")
        void aFailedSnapshotAcquisitionPropagates() {
            when(accountViewService.viewAccountWithRecords(ACCOUNT_ID)).thenReturn(viewRecords());
            when(accountUpdateService.sealSnapshotForUpdate(any(AccountViewService.AccountViewRecords.class), eq(SUBJECT)))
                    .thenThrow(new RecordNotFoundException("account not found", "account", ACCOUNT_ID));

            assertThatThrownBy(() -> controller.viewAccount(ACCOUNT_ID, principal))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        /**
         * A body that carries the sealed snapshot is <em>accepted</em>, which is the frozen contract of
         * transformation Rule 7: the value the preceding read issued is echoed back in the request body,
         * because a stateless server keeps no COMMAREA between the two turns of the pseudo-conversation.
         */
        @Test
        @DisplayName("a body-carried sealed snapshot is accepted and reaches the service")
        void aBodyCarriedSnapshotIsAccepted() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE));

            final ResponseEntity<AccountUpdateResponse> response =
                    controller.updateAccount(updateRequest(SEALED_SNAPSHOT), "true", principal);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(accountUpdateService).updateAccount(any(), eq(SUBJECT));
        }

        /**
         * The sealed value reaches the service exactly as bound. Nothing is trimmed, re-encoded or
         * re-padded on the way through: authenticated encryption fails closed on a single altered byte, so
         * any normalisation here would refuse every write.
         */
        @Test
        @DisplayName("the submitted sealed value reaches the service byte for byte, by reference")
        void theSubmittedSnapshotReachesTheServiceUnaltered() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE));
            final AccountUpdateRequest request = updateRequest(SEALED_SNAPSHOT);

            controller.updateAccount(request, "true", principal);

            final ArgumentCaptor<AccountUpdateRequest> relayed =
                    ArgumentCaptor.forClass(AccountUpdateRequest.class);
            verify(accountUpdateService).updateAccount(relayed.capture(), eq(SUBJECT));
            assertThat(relayed.getValue()).isSameAs(request);
            assertThat(relayed.getValue().getSnapshot()).isEqualTo(SEALED_SNAPSHOT);
        }

        /**
         * No readable group is bound from the body, whatever the caller sends. The submitted request reaches
         * the service with {@code getOldDetails()} null, so the only group the comparison can ever see is one
         * the service opened for itself.
         */
        @Test
        @DisplayName("no readable snapshot group is bound from the body")
        void noReadableGroupIsBoundFromTheBody() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE));

            controller.updateAccount(updateRequest(SEALED_SNAPSHOT), "true", principal);

            final ArgumentCaptor<AccountUpdateRequest> relayed =
                    ArgumentCaptor.forClass(AccountUpdateRequest.class);
            verify(accountUpdateService).updateAccount(relayed.capture(), eq(SUBJECT));
            assertThat(relayed.getValue().getOldDetails())
                    .as("the group is server-side only; a caller cannot supply the comparison's operand")
                    .isNull();
        }

        /**
         * An absent sealed member relays null, which the service reports as an unmet precondition. The
         * controller does not fabricate one and does not pre-empt the service's own outcome.
         */
        @Test
        @DisplayName("an absent sealed member relays null rather than a fabricated value")
        void anAbsentSnapshotRelaysNull() {
            when(accountUpdateService.updateAccount(any(), eq(SUBJECT)))
                    .thenReturn(updateResult(
                            AccountUpdateService.ChangeAction.CHANGES_OKAYED_AND_DONE));

            controller.updateAccount(updateRequest(null), "true", principal);

            final ArgumentCaptor<AccountUpdateRequest> relayed =
                    ArgumentCaptor.forClass(AccountUpdateRequest.class);
            verify(accountUpdateService).updateAccount(relayed.capture(), eq(SUBJECT));
            assertThat(relayed.getValue().getSnapshot()).isNull();
        }
    }

    /**
     * The confirmation parameter is the gate between validating a payload and writing two datasets.
     */
    @Nested
    @DisplayName("the confirmation admits exactly true and false")
    class ConfirmationContract {

        /**
         * An absent parameter means not confirmed, which is the {@code CONTINUE} arm of
         * {@code app/cbl/COACTUPC.cbl:L2620-L2621} and is answered without a write.
         */
        @Test
        @DisplayName("an absent confirmation is answered as unconfirmed and writes nothing")
        void anAbsentConfirmationWritesNothing() {
            final ConcurrentUpdateException failure = catchThrowableOfType(
                    ConcurrentUpdateException.class,
                    () -> controller.updateAccount(updateRequest(null), null, principal));

            assertThat(failure.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED);
            verifyNoInteractions(accountUpdateService);
        }

        /**
         * An explicit {@code false} is the same condition, and is not read charitably as a confirmation.
         */
        @Test
        @DisplayName("an explicit false is unconfirmed and writes nothing")
        void anExplicitFalseWritesNothing() {
            assertThatThrownBy(
                    () -> controller.updateAccount(updateRequest(null), "false", principal))
                    .isInstanceOf(ConcurrentUpdateException.class);
            verifyNoInteractions(accountUpdateService);
        }

        /**
         * Every alias the framework's {@code Boolean} binding would have accepted is refused, one assertion
         * each so that a regression names the alias that returned.
         */
        @Test
        @DisplayName("yes, on, 1 and their negatives are all refused as confirmations")
        void everyBooleanAliasIsRefused() {
            assertRefused("yes");
            assertRefused("on");
            assertRefused("1");
            assertRefused("no");
            assertRefused("off");
            assertRefused("0");
            assertRefused("TRUE");
            assertRefused("True");
            verifyNoInteractions(accountUpdateService);
        }

        /**
         * An empty parameter is a blank failure rather than an invalid one, keeping the two-state
         * discriminator of {@code app/cpy/CSSETATY.cpy:L18-L19} observable.
         */
        @Test
        @DisplayName("an empty confirmation reports BLANK, not INVALID")
        void anEmptyConfirmationReportsBlank() {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> controller.updateAccount(updateRequest(null), "", principal));

            assertThat(failure.getFieldName()).isEqualTo("confirm");
            assertThat(failure.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
        }

        /**
         * Asserts that one spelling of the confirmation parameter is refused.
         *
         * @param token the spelling to submit
         */
        private void assertRefused(final String token) {
            assertThatThrownBy(
                    () -> controller.updateAccount(updateRequest(null), token, principal))
                    .as("confirm=%s must be refused rather than coerced", token)
                    .isInstanceOf(ValidationException.class);
        }
    }

    /**
     * A diagnostic must not become an existence oracle, and one file status must yield one status code.
     */
    @Nested
    @DisplayName("failure translation discloses no key and agrees with the other four resources")
    class DisclosureContract {

        /**
         * Neither the record type nor the key reaches the response. The type names a logical file or an
         * alternate-index path and the key enables enumeration, so both go to the WARN log and the body
         * carries the resource code a caller can branch on instead.
         */
        @Test
        @DisplayName("a 404 reports neither the record type nor the key")
        void aNotFoundReportsTheTypeAndNeverTheKey() {
            final ResponseEntity<ProblemDetail> response = controller.handleRecordNotFound(
                    new RecordNotFoundException("Customer:000000001 not found", "customer",
                            "000000001"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            // The key on the customer link is one the caller never supplied - it is resolved server side
            // from the cross-reference - so echoing it on a 404 hands out a customer identifier for an
            // account identifier. The type is a logical file or alternate-index path name. Both are logged.
            assertThat(response.getBody().getProperties())
                    .containsEntry("code", "ACCOUNT_RECORD_NOT_FOUND")
                    .containsKey("correlationId")
                    .doesNotContainKeys("recordType", "recordKey");
        }

        /**
         * An input-output failure answers {@code 502}, carrying the four-character expanded status that
         * {@code 9910-DISPLAY-IO-STATUS} produced.
         */
        @Test
        @DisplayName("an input-output failure answers 502 with the envelope and no internals")
        void anInputOutputFailureAnswers502() {
            final FileAccessException failure =
                    new FileAccessException("read failed", "37", "ACCTDAT", "READ");

            final ResponseEntity<ProblemDetail> response = controller.handleFileAccessFailure(failure);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isNotNull();
            // The expanded status, the logical file and the operation are no longer relayed to the caller.
            // Each described the server's internals - a legacy file status, a dataset name and a VSAM verb -
            // and all three now go to the ERROR log, where the correlation identifier in this body is how a
            // caller reporting the failure reaches them. The body carries the stable envelope instead, and
            // the absence is asserted as well as the presence so neither can drift back.
            assertThat(response.getBody().getProperties())
                    .containsEntry("errorCode", "CARDDEMO-IO-FAILURE")
                    .containsKey("correlationId")
                    .doesNotContainKeys("expandedStatus", "ioStatus", "logicalFileName", "operation");
            assertThat(response.getBody().getDetail())
                    .as("a 9x status is an internal storage condition, so its text is not relayed")
                    .doesNotContain("ACCTDAT", failure.getExpandedStatus());
        }
    }
}
