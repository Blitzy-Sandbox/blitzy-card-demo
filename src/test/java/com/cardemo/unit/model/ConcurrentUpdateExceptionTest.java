/*
 * ******************************************************************
 * Program     : ConcurrentUpdateExceptionTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that ConcurrentUpdateException carries the four
 *               COACTUPC outcome flags as a typed enum with their exact
 *               legacy message literals, and that the ACUP-CHANGE-ACTION
 *               marker each outcome produces is reported TRUTHFULLY -
 *               including the legacy defect whereby a customer lock
 *               failure reaches the 'C' success marker through WHEN
 *               OTHER because no branch tests that flag.
 * Source      : app/cbl/COACTUPC.cbl:L517-L524 (the outcome 88-levels) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L659-L668 (ACUP-CHANGE-ACTION values) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L2604-L2615 (the dispatch EVALUATE) @ 7756d89
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
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.ConcurrentUpdateException.Outcome;

import java.io.IOException;
import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link ConcurrentUpdateException} and its nested {@link Outcome} enum - the typed form of
 * the account-update failure taxonomy.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code COACTUPC} declares four distinct failure outcomes as 88-levels over one message field
 * ({@code app/cbl/COACTUPC.cbl:L517-L524}) and a fifth state for changes that were never confirmed. Each
 * carries a literal message the screen displays verbatim. Collapsing them into a single conflict condition
 * would lose information the legacy screen showed, so this test asserts:
 *
 * <ol>
 *   <li><strong>Every legacy message literal, character for character.</strong> These strings are compared
 *       by the parity gate. Note {@code "Record changed by some one else. Please review"} - "some one" is
 *       two words in the source, and the test asserts it that way rather than correcting it.</li>
 *   <li><strong>The {@code ACUP-CHANGE-ACTION} marker each outcome produces</strong>, read from the dispatch
 *       {@code EVALUATE} at {@code L2604-L2615} rather than assumed.</li>
 *   <li><strong>The preserved legacy defect.</strong> The dispatch tests
 *       {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE}, {@code LOCKED-BUT-UPDATE-FAILED} and
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE}, then falls to {@code WHEN OTHER}. <strong>No branch tests
 *       {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}.</strong> A customer lock failure therefore lands on
 *       {@code WHEN OTHER} and sets {@code ACUP-CHANGES-OKAYED-AND-DONE}, whose value is {@code 'C'} - the
 *       <em>success</em> marker. The user is told the changes were okayed and done when in fact the customer
 *       record could not be locked. This is a real defect in the system of record and it is preserved, not
 *       repaired: parity is the contract. The test asserts {@code 'C'} deliberately and documents why, so
 *       that a future reader does not "fix" it and silently break parity.</li>
 *   <li><strong>The {@code affectedRecord} normalisation policy</strong>, which is blank-to-null
 *       <em>and</em> strip - a third policy distinct from its two sibling exception types.</li>
 *   </ol>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=ConcurrentUpdateExceptionTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. The default outcome for the two message-only constructors is {@code null}, asserted explicitly
 * rather than defaulted to a sentinel, because "no outcome recorded" and "outcome recorded as X" are
 * different states a handler must distinguish.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The {@code 'C'} assertion fails.</strong> Somebody changed
 *       {@code COULD_NOT_LOCK_CUSTOMER}'s marker to something that looks more correct. It is not a bug in
 *       this code; read {@code L2604-L2615} and revert.</li>
 *   <li><strong>A message-literal assertion fails.</strong> A legacy string was reworded or its spelling
 *       "corrected". The parity gate reads these byte for byte.</li>
 *   <li><strong>The strip assertions fail.</strong> The normalisation on {@code affectedRecord} changed.
 *       Note it is deliberately not the same policy the other payload types use.</li>
 *   </ul>
 *
 * @see ConcurrentUpdateException
 */
class ConcurrentUpdateExceptionTest {

    private static final String MESSAGE = "Could not lock account record for update";

    @Nested
    @DisplayName("the Outcome enum: exactly five states with their exact legacy literals")
    class OutcomeContract {

        @Test
        @DisplayName("there are exactly five outcomes, one per source state and no invented sixth")
        void thereAreExactlyFiveOutcomes() {
            assertThat(Outcome.values())
                    .as("four failure 88-levels at app/cbl/COACTUPC.cbl:L517-L524 plus the "
                            + "changes-not-confirmed state at L664; a sixth would have no source construct "
                            + "behind it")
                    .hasSize(5)
                    .containsExactly(
                            Outcome.COULD_NOT_LOCK_ACCOUNT,
                            Outcome.COULD_NOT_LOCK_CUSTOMER,
                            Outcome.DATA_CHANGED_BEFORE_UPDATE,
                            Outcome.LOCKED_BUT_UPDATE_FAILED,
                            Outcome.CHANGES_NOT_CONFIRMED);
        }

        @Test
        @DisplayName("the account lock message is the L518-L519 literal exactly")
        void accountLockMessageMatchesTheSource() {
            assertThat(Outcome.COULD_NOT_LOCK_ACCOUNT.getLegacyMessage())
                    .as("COULD-NOT-LOCK-ACCT-FOR-UPDATE VALUE 'Could not lock account record for update' - "
                            + "lower case after the first word, no terminating full stop")
                    .isEqualTo("Could not lock account record for update");
        }

        @Test
        @DisplayName("the customer lock message is the L520-L521 literal exactly")
        void customerLockMessageMatchesTheSource() {
            assertThat(Outcome.COULD_NOT_LOCK_CUSTOMER.getLegacyMessage())
                    .as("COULD-NOT-LOCK-CUST-FOR-UPDATE VALUE 'Could not lock customer record for update' - "
                            + "identical to the account message except for the one word, which is why the "
                            + "two must stay separate constants rather than one parameterised string")
                    .isEqualTo("Could not lock customer record for update");
        }

        @Test
        @DisplayName("the data-changed message preserves the source's two-word 'some one' spelling")
        void dataChangedMessagePreservesTheSourceSpelling() {
            assertThat(Outcome.DATA_CHANGED_BEFORE_UPDATE.getLegacyMessage())
                    .as("DATA-WAS-CHANGED-BEFORE-UPDATE VALUE 'Record changed by some one else. Please "
                            + "review' - 'some one' is two words in the source and 'review' has no "
                            + "terminating full stop; correcting either would change a string the parity "
                            + "gate compares")
                    .isEqualTo("Record changed by some one else. Please review")
                    .contains("some one")
                    .doesNotContain("someone");
        }

        @Test
        @DisplayName("the update-failed message is the L524 literal exactly")
        void updateFailedMessageMatchesTheSource() {
            assertThat(Outcome.LOCKED_BUT_UPDATE_FAILED.getLegacyMessage())
                    .isEqualTo("Update of record failed");
        }

        @Test
        @DisplayName("the changes-not-confirmed state carries an empty message, because it displays none")
        void changesNotConfirmedCarriesAnEmptyMessage() {
            assertThat(Outcome.CHANGES_NOT_CONFIRMED.getLegacyMessage())
                    .as("this is not a failure the screen reports - it is the confirm-pending state, so it "
                            + "has no 88-level message literal; empty rather than null keeps every "
                            + "accessor total")
                    .isEmpty();
        }

        @ParameterizedTest
        @EnumSource(Outcome.class)
        @DisplayName("every outcome exposes a non-null message, so no accessor can return null")
        void everyOutcomeExposesANonNullMessage(final Outcome outcome) {
            assertThat(outcome.getLegacyMessage())
                    .as("%s must not return null: a screen renderer would print the four characters "
                            + "'null' where the legacy program printed spaces", outcome.name())
                    .isNotNull();
        }

        @Test
        @DisplayName("the four failure messages are mutually distinct, so each is separately identifiable")
        void theFourFailureMessagesAreDistinct() {
            assertThat(EnumSet.complementOf(EnumSet.of(Outcome.CHANGES_NOT_CONFIRMED)).stream()
                            .map(Outcome::getLegacyMessage)
                            .toList())
                    .as("the legacy screen distinguishes all four; a duplicate literal would make two "
                            + "outcomes indistinguishable to the user")
                    .doesNotHaveDuplicates()
                    .hasSize(4);
        }
    }

    @Nested
    @DisplayName("the ACUP-CHANGE-ACTION markers read from the L2604-L2615 dispatch")
    class ChangeActionMarkers {

        @Test
        @DisplayName("an account lock failure produces 'L' per SET ACUP-CHANGES-OKAYED-LOCK-ERROR")
        void accountLockFailureProducesL() {
            assertThat(Outcome.COULD_NOT_LOCK_ACCOUNT.getChangeActionCode())
                    .as("WHEN COULD-NOT-LOCK-ACCT-FOR-UPDATE / SET ACUP-CHANGES-OKAYED-LOCK-ERROR at "
                            + "L2607-L2608, whose 88-level value is 'L' at L666")
                    .isEqualTo('L');
        }

        @Test
        @DisplayName("an update failure produces 'F' per SET ACUP-CHANGES-OKAYED-BUT-FAILED")
        void updateFailureProducesF() {
            assertThat(Outcome.LOCKED_BUT_UPDATE_FAILED.getChangeActionCode())
                    .as("WHEN LOCKED-BUT-UPDATE-FAILED / SET ACUP-CHANGES-OKAYED-BUT-FAILED at "
                            + "L2609-L2610, whose 88-level value is 'F' at L668")
                    .isEqualTo('F');
        }

        @Test
        @DisplayName("a detected change produces 'S' per SET ACUP-SHOW-DETAILS, redisplaying the screen")
        void detectedChangeProducesS() {
            assertThat(Outcome.DATA_CHANGED_BEFORE_UPDATE.getChangeActionCode())
                    .as("WHEN DATA-WAS-CHANGED-BEFORE-UPDATE / SET ACUP-SHOW-DETAILS at L2611-L2612, "
                            + "whose 88-level value is 'S' at L659 - the screen is redisplayed with the "
                            + "current values so the user can review them")
                    .isEqualTo('S');
        }

        @Test
        @DisplayName("a customer lock failure produces 'C', the SUCCESS marker - a preserved legacy defect")
        void customerLockFailureProducesTheSuccessMarker() {
            assertThat(Outcome.COULD_NOT_LOCK_CUSTOMER.getChangeActionCode())
                    .as("PRESERVED LEGACY DEFECT, asserted deliberately. The dispatch at L2604-L2615 tests "
                            + "the account-lock, update-failed and data-changed flags and then falls to "
                            + "WHEN OTHER. NO branch tests COULD-NOT-LOCK-CUST-FOR-UPDATE, so a customer "
                            + "lock failure reaches WHEN OTHER and sets ACUP-CHANGES-OKAYED-AND-DONE, "
                            + "value 'C' at L665 - the success marker. The user is told the changes were "
                            + "okayed and done. Parity is the contract, so this is reported truthfully "
                            + "rather than corrected; do not 'fix' this assertion")
                    .isEqualTo('C');
        }

        @Test
        @DisplayName("the changes-not-confirmed state produces 'N' per ACUP-CHANGES-OK-NOT-CONFIRMED")
        void changesNotConfirmedProducesN() {
            assertThat(Outcome.CHANGES_NOT_CONFIRMED.getChangeActionCode())
                    .as("SET ACUP-CHANGES-OK-NOT-CONFIRMED at L1674 and L2590, whose 88-level value is "
                            + "'N' at L664")
                    .isEqualTo('N');
        }

        @ParameterizedTest
        @EnumSource(Outcome.class)
        @DisplayName("every marker is one of the five ACUP-CHANGES-MADE values declared at L660-L662")
        void everyMarkerIsADeclaredChangeActionValue(final Outcome outcome) {
            assertThat(outcome.getChangeActionCode())
                    .as("ACUP-CHANGES-MADE VALUES 'E', 'N', 'C', 'L', 'F' at L660-L662, plus "
                            + "ACUP-SHOW-DETAILS 'S' at L659; %s must not invent a marker the field's "
                            + "88-levels do not recognise", outcome.name())
                    .isIn('E', 'N', 'C', 'L', 'F', 'S');
        }

        @Test
        @DisplayName("'E', the changes-not-OK marker, is deliberately unused by this enum")
        void theChangesNotOkMarkerIsUnused() {
            assertThat(EnumSet.allOf(Outcome.class).stream()
                            .map(Outcome::getChangeActionCode)
                            .toList())
                    .as("ACUP-CHANGES-NOT-OK 'E' at L663 is set at L1470 for a failed field edit, which is "
                            + "a ValidationException outcome and not a concurrency outcome; this enum "
                            + "correctly does not claim it")
                    .doesNotContain('E');
        }

        @Test
        @DisplayName("the markers are distinct per outcome, so the dispatch result is unambiguous")
        void theMarkersAreDistinctPerOutcome() {
            assertThat(EnumSet.allOf(Outcome.class).stream()
                            .map(Outcome::getChangeActionCode)
                            .distinct()
                            .count())
                    .as("five outcomes and five distinct markers; a collision would make two outcomes "
                            + "indistinguishable downstream")
                    .isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("construction, payload and the affectedRecord normalisation policy")
    class ConstructionAndPayload {

        @Test
        @DisplayName("the message-only constructor records no outcome and no affected record")
        void messageOnlyConstructorRecordsNoPayload() {
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(MESSAGE);

            assertThat(thrown.getOutcome())
                    .as("null means 'no outcome recorded', which a handler must be able to distinguish "
                            + "from any specific outcome; defaulting to a sentinel would assert something "
                            + "the caller did not")
                    .isNull();
            assertThat(thrown.getAffectedRecord()).isNull();
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the message-and-cause constructor records no outcome but preserves the cause")
        void messageAndCauseConstructorPreservesTheCause() {
            final IOException cause = new IOException("record held by another task");
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(MESSAGE, cause);

            assertThat(thrown.getOutcome()).isNull();
            assertThat(thrown.getAffectedRecord()).isNull();
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the outcome-and-message constructor records the outcome and leaves the record null")
        void outcomeAndMessageConstructorRecordsTheOutcome() {
            final ConcurrentUpdateException thrown =
                    new ConcurrentUpdateException(Outcome.DATA_CHANGED_BEFORE_UPDATE, MESSAGE);

            assertThat(thrown.getOutcome()).isEqualTo(Outcome.DATA_CHANGED_BEFORE_UPDATE);
            assertThat(thrown.getAffectedRecord()).isNull();
            assertThat(thrown.getCause()).isNull();
        }

        @Test
        @DisplayName("the three-argument outcome constructor delegates and leaves the record null")
        void threeArgumentOutcomeConstructorDelegates() {
            final IOException cause = new IOException("lock timeout");
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    Outcome.COULD_NOT_LOCK_CUSTOMER, MESSAGE, cause);

            assertThat(thrown.getOutcome()).isEqualTo(Outcome.COULD_NOT_LOCK_CUSTOMER);
            assertThat(thrown.getAffectedRecord())
                    .as("this constructor delegates to the four-argument form with a null record, so it "
                            + "must not fabricate one")
                    .isNull();
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the four-argument constructor records every element of the payload")
        void fourArgumentConstructorRecordsEverything() {
            final IOException cause = new IOException("customer rewrite failed");
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    Outcome.LOCKED_BUT_UPDATE_FAILED, MESSAGE, "CUSTDAT:000000009", cause);

            assertThat(thrown.getOutcome()).isEqualTo(Outcome.LOCKED_BUT_UPDATE_FAILED);
            assertThat(thrown.getAffectedRecord())
                    .as("naming which of the two datasets failed is the whole point of this field: the "
                            + "write sequence touches ACCTDAT then CUSTDAT and the rollback behaviour "
                            + "differs between them")
                    .isEqualTo("CUSTDAT:000000009");
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("the affected record is stripped of surrounding whitespace")
        void theAffectedRecordIsStripped() {
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    Outcome.COULD_NOT_LOCK_ACCOUNT, MESSAGE, "   ACCTDAT:00000000001   ", null);

            assertThat(thrown.getAffectedRecord())
                    .as("a COBOL key arrives space-padded to its PIC width, so stripping is what makes the "
                            + "value usable as a diagnostic; note this policy differs from "
                            + "RecordNotFoundException, which converts blank to null but does NOT strip")
                    .isEqualTo("ACCTDAT:00000000001");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "      ", "\t", "\n", " \t \n "})
        @DisplayName("a blank affected record becomes null, so an empty diagnostic is never reported")
        void aBlankAffectedRecordBecomesNull(final String blank) {
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    Outcome.COULD_NOT_LOCK_ACCOUNT, MESSAGE, blank, null);

            assertThat(thrown.getAffectedRecord())
                    .as("a key field that arrived as all spaces carries no information, so it is reported "
                            + "as absent rather than as an empty string a renderer would print as nothing")
                    .isNull();
        }

        @Test
        @DisplayName("a null affected record stays null without throwing")
        void aNullAffectedRecordStaysNull() {
            final ConcurrentUpdateException thrown =
                    new ConcurrentUpdateException(Outcome.COULD_NOT_LOCK_ACCOUNT, MESSAGE, null, null);

            assertThat(thrown.getAffectedRecord()).isNull();
            assertThat(thrown.getOutcome()).isEqualTo(Outcome.COULD_NOT_LOCK_ACCOUNT);
        }

        @Test
        @DisplayName("internal whitespace inside the affected record is preserved, only the edges strip")
        void internalWhitespaceIsPreserved() {
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    Outcome.COULD_NOT_LOCK_ACCOUNT, MESSAGE, "  ACCTDAT  KEY 1  ", null);

            assertThat(thrown.getAffectedRecord())
                    .as("strip() removes leading and trailing whitespace only; collapsing internal spaces "
                            + "would corrupt a composite key whose components are space-separated")
                    .isEqualTo("ACCTDAT  KEY 1");
        }

        @Test
        @DisplayName("a null outcome is accepted on the outcome constructors without substitution")
        void aNullOutcomeIsAcceptedWithoutSubstitution() {
            final ConcurrentUpdateException thrown =
                    new ConcurrentUpdateException((Outcome) null, MESSAGE);

            assertThat(thrown.getOutcome())
                    .as("observed behaviour, pinned deliberately: unlike ValidationException, which "
                            + "substitutes INVALID for a null FailureKind, this type stores a null outcome "
                            + "as null - the two siblings genuinely differ and neither policy should be "
                            + "inferred from the other")
                    .isNull();
            assertThat(thrown.getMessage()).isEqualTo(MESSAGE);
        }

        @ParameterizedTest
        @EnumSource(Outcome.class)
        @DisplayName("every outcome can be carried by the exception and read back unchanged")
        void everyOutcomeRoundTrips(final Outcome outcome) {
            final ConcurrentUpdateException thrown =
                    new ConcurrentUpdateException(outcome, outcome.getLegacyMessage());

            assertThat(thrown.getOutcome()).isSameAs(outcome);
            assertThat(thrown.getMessage())
                    .as("carrying the outcome's own legacy literal as the message is the intended usage, "
                            + "so that the screen text and the typed outcome cannot drift apart")
                    .isEqualTo(outcome.getLegacyMessage());
        }

        @Test
        @DisplayName("it is a CardDemoException, so the shared boundary handler catches it")
        void itIsACardDemoException() {
            assertThat(new ConcurrentUpdateException(MESSAGE))
                    .isInstanceOf(CardDemoException.class)
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
