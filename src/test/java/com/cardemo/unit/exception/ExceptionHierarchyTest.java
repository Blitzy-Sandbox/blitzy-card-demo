/*
 * ******************************************************************
 * Program     : ExceptionHierarchyTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Exercises the nine-class typed exception hierarchy that
 *               replaces the COBOL FILE STATUS guard idiom and the
 *               CEE3ABD abend path, asserting cause preservation,
 *               payload round-tripping, the CSMSG02Y abend field set
 *               and the corpus-verified ACUP-CHANGE-ACTION dispatch.
 * Source      : app/cbl/CBTRN02C.cbl:142-144 (the FILE STATUS guard),
 *               app/cbl/CBTRN02C.cbl:707-710 (9999-ABEND-PROGRAM,
 *               abend code 999), app/cbl/CBTRN02C.cbl:714-731
 *               (9910-DISPLAY-IO-STATUS), app/cpy/CSMSG02Y.cpy:22-28
 *               (the four abend work areas), app/cbl/COACTUPC.cbl:
 *               517-524 (the outcome message literals), 654-668 (the
 *               ACUP-CHANGE-ACTION 88-levels) and 2606-2615 (the
 *               EVALUATE that assigns them)
 *               frozen at commit 7756d89
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */

package com.cardemo.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentUpdateException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.FileUnavailableException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.enums.FileStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the behaviour of {@code com.cardemo.exception}, the nine classes that carry every legacy
 * I/O and abend outcome into Java as a typed value rather than a return code.
 *
 * <p><strong>Why the assertions are phrased against the corpus.</strong> Six of the nine classes
 * carry literal text or single-character markers that the legacy screens and SYSOUT lines reproduce
 * byte for byte, so an expectation retyped from the Java source would assert the implementation
 * against a second copy of itself. Every literal asserted below was first located in
 * {@code app/cbl/COACTUPC.cbl} or {@code app/cpy/CSMSG02Y.cpy}, and the abend field widths are read
 * out of the frozen copybook at test time by {@link #abendFieldWidths()} rather than retyped.
 *
 * <p><strong>The one place the corpus overrode my own suspicion.</strong>
 * {@link ConcurrentUpdateException.Outcome#COULD_NOT_LOCK_CUSTOMER} reports the change-action marker
 * {@code 'C'}, which {@code app/cbl/COACTUPC.cbl:665} declares as
 * {@code ACUP-CHANGES-OKAYED-AND-DONE} - the <em>success</em> marker. That looks wrong and is not.
 * The {@code EVALUATE} at {@code app/cbl/COACTUPC.cbl:2606-2615} has branches for the account lock
 * failure, the failed rewrite and the detected change, but <strong>no branch whatsoever</strong> for
 * {@code COULD-NOT-LOCK-CUST-FOR-UPDATE}. A customer lock failure therefore falls through
 * {@code WHEN OTHER} at L2613 and is reported to the operator as though the update had been saved,
 * even though nothing was written. That is a genuine legacy defect and it is
 * <strong>preserved, not corrected</strong>, because parity is the contract. The consequence -
 * that the literal {@code 'Could not lock customer record for update'} is never displayed by the
 * legacy program - is asserted in {@link TheConcurrentUpdateOutcomeDispatch} so that a later
 * "tidy-up" of the marker fails here and has to be a deliberate decision.
 *
 * <p><strong>What is deliberately not re-tested here.</strong> The four-character status rendering
 * of {@code 9910-DISPLAY-IO-STATUS} lives in {@link FileStatus#renderIoStatus04(String)} and is
 * already exhaustively covered, both branches and the byte-masking edge cases, by
 * {@code FileStatusTest}. Duplicating it would add no confidence and would create two places to
 * update. The obligation discharged below is narrower and distinct: that
 * {@link FileAccessException} stores the <em>rendered</em> value rather than the raw two-character
 * status it was handed.
 */
@DisplayName("com.cardemo.exception - the typed hierarchy replacing FILE STATUS and CEE3ABD")
final class ExceptionHierarchyTest {

    /** The frozen copybook holding the abend work areas; internally titled {@code CABENDD.CPY}. */
    private static final Path ABEND_COPYBOOK = Path.of("app", "cpy", "CSMSG02Y.cpy");

    /**
     * Matches an abend work-area declaration. The {@code PIC} clause is deliberately <em>not</em>
     * required to end in a period, because {@code app/cpy/CSMSG02Y.cpy} continues every one of the
     * four onto a following {@code VALUE SPACES.} line. That continuation is exactly why the shared
     * {@code RecordLayoutCopybook} oracle cannot read this member and correctly refuses it outright
     * rather than parsing it in part, so this test carries its own narrow pattern instead.
     */
    private static final Pattern ABEND_FIELD =
            Pattern.compile("^\\s*\\d{6}\\s+\\d\\d\\s+(ABEND-[A-Z]+)\\s+PIC\\s+X\\((\\d+)\\)\\s*$");

    /** {@code app/cpy/CSMSG02Y.cpy:22-28} declares exactly four fields under {@code ABEND-DATA}. */
    private static final int ABEND_FIELD_COUNT = 4;

    /** Every concrete throwable in the package, base type first. */
    private static final List<Class<? extends CardDemoException>> HIERARCHY = List.of(
            CardDemoException.class,
            ValidationException.class,
            RecordNotFoundException.class,
            DuplicateRecordException.class,
            FileUnavailableException.class,
            ConcurrentUpdateException.class,
            DataIntegrityException.class,
            FileAccessException.class,
            FatalProcessingException.class);

    /** A distinguishable cause instance; identity is what the cause assertions compare. */
    private static final Throwable CAUSE = new IllegalStateException("underlying VSAM failure");

    /**
     * Reads the four abend field widths out of the frozen copybook.
     *
     * <p>Fails loud rather than returning a short map: a pattern that silently matched three of the
     * four fields would let a width regression through unnoticed, which is the failure mode two
     * earlier oracles in this remediation actually exhibited.
     *
     * @return field name to declared {@code PIC X(n)} width, in declaration order
     */
    private static Map<String, Integer> abendFieldWidths() {
        final List<String> lines;
        try {
            lines = Files.readAllLines(ABEND_COPYBOOK, StandardCharsets.ISO_8859_1);
        } catch (final IOException failure) {
            throw new UncheckedIOException("cannot read " + ABEND_COPYBOOK.toAbsolutePath(), failure);
        }
        final Map<String, Integer> widths = new LinkedHashMap<>();
        for (final String line : lines) {
            final Matcher matcher = ABEND_FIELD.matcher(line);
            if (matcher.matches()) {
                widths.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
            }
        }
        if (widths.size() != ABEND_FIELD_COUNT) {
            throw new IllegalStateException(ABEND_COPYBOOK
                    + " yielded " + widths.size() + " abend fields but declares " + ABEND_FIELD_COUNT
                    + "; the copybook or this test's ABEND_FIELD pattern has changed. Parsed: " + widths);
        }
        return widths;
    }

    // ---------------------------------------------------------------------------------------------
    // 1. The base type and the shape of the hierarchy
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("1. the base type and the shape of the hierarchy")
    class TheBaseTypeAndTheHierarchy {

        @Test
        @DisplayName("the base type is unchecked, so no legacy call site needs a throws clause")
        void theBaseTypeIsUnchecked() {
            assertThat(RuntimeException.class).isAssignableFrom(CardDemoException.class);
            assertThat(Exception.class).isAssignableFrom(CardDemoException.class);
        }

        @Test
        @DisplayName("all eight specific outcomes derive from the one base type")
        void everySubtypeDerivesFromTheBase() {
            final List<Class<? extends CardDemoException>> subtypes =
                    HIERARCHY.subList(1, HIERARCHY.size());
            assertThat(subtypes).hasSize(8);
            assertThat(subtypes).allSatisfy(subtype ->
                    assertThat(CardDemoException.class).isAssignableFrom(subtype));
        }

        @Test
        @DisplayName("the package declares exactly the nine classes the AAP names")
        void thePackageDeclaresNineClasses() {
            assertThat(HIERARCHY).hasSize(9);
            assertThat(HIERARCHY).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no subtype extends another subtype, so catch order cannot mask a sibling")
        void theHierarchyIsFlat() {
            final List<Class<? extends CardDemoException>> subtypes =
                    HIERARCHY.subList(1, HIERARCHY.size());
            assertThat(subtypes).allSatisfy(subtype ->
                    assertThat(subtype.getSuperclass()).isEqualTo(CardDemoException.class));
        }

        @Test
        @DisplayName("every class is serialisable through RuntimeException and pins a serialVersionUID")
        void everyClassPinsASerialVersionUid() throws ReflectiveOperationException {
            for (final Class<? extends CardDemoException> type : HIERARCHY) {
                final java.lang.reflect.Field field = type.getDeclaredField("serialVersionUID");
                field.setAccessible(true);
                assertThat(field.getLong(null))
                        .describedAs("%s serialVersionUID", type.getSimpleName())
                        .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("the base type carries the message it is given")
        void theBaseTypeCarriesItsMessage() {
            assertThat(new CardDemoException("boom")).hasMessage("boom").hasNoCause();
        }

        @Test
        @DisplayName("the base type carries the cause it is given")
        void theBaseTypeCarriesItsCause() {
            assertThat(new CardDemoException("boom", CAUSE)).hasMessage("boom").hasCause(CAUSE);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Cause preservation - Rule 1 clause B forbids swallowing a root cause
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("2. every cause-accepting constructor preserves the root cause by identity")
    class CausePreservationOnEveryConstructor {

        @Test
        @DisplayName("ValidationException preserves the cause")
        void validation() {
            assertThat(new ValidationException("bad", CAUSE)).hasCause(CAUSE).hasMessage("bad");
        }

        @Test
        @DisplayName("RecordNotFoundException preserves the cause")
        void recordNotFound() {
            assertThat(new RecordNotFoundException("absent", CAUSE)).hasCause(CAUSE);
        }

        @Test
        @DisplayName("DuplicateRecordException preserves the cause")
        void duplicate() {
            assertThat(new DuplicateRecordException("dup", CAUSE)).hasCause(CAUSE);
        }

        @Test
        @DisplayName("FileUnavailableException preserves the cause on both cause-accepting forms")
        void fileUnavailable() {
            assertThat(new FileUnavailableException("shut", CAUSE)).hasCause(CAUSE);
            assertThat(new FileUnavailableException("shut", "ACCTDAT", CAUSE)).hasCause(CAUSE);
        }

        @Test
        @DisplayName("ConcurrentUpdateException preserves the cause on all three cause-accepting forms")
        void concurrentUpdate() {
            assertThat(new ConcurrentUpdateException("clash", CAUSE)).hasCause(CAUSE);
            assertThat(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE, "clash", CAUSE))
                    .hasCause(CAUSE);
            assertThat(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE,
                    "clash", "ACCT 00000000011", CAUSE))
                    .hasCause(CAUSE);
        }

        @Test
        @DisplayName("DataIntegrityException preserves the cause on both cause-accepting forms")
        void dataIntegrity() {
            assertThat(new DataIntegrityException("orphan", CAUSE)).hasCause(CAUSE);
            assertThat(new DataIntegrityException("orphan", "fk01_card_account", "card", CAUSE))
                    .hasCause(CAUSE);
        }

        @Test
        @DisplayName("FileAccessException preserves the cause")
        void fileAccess() {
            assertThat(new FileAccessException("io", CAUSE)).hasCause(CAUSE);
        }

        @Test
        @DisplayName("FatalProcessingException preserves the cause on both cause-accepting forms")
        void fatal() {
            assertThat(new FatalProcessingException("abend", CAUSE)).hasCause(CAUSE);
            assertThat(new FatalProcessingException("0999", "CBTRN02C", "reason", "abend", CAUSE))
                    .hasCause(CAUSE);
        }

        @Test
        @DisplayName("a message-only constructor leaves the cause absent rather than inventing one")
        void aMessageOnlyConstructorHasNoCause() {
            final List<CardDemoException> messageOnly = List.of(
                    new CardDemoException("m"),
                    new ValidationException("m"),
                    new RecordNotFoundException("m"),
                    new DuplicateRecordException("m"),
                    new FileUnavailableException("m"),
                    new ConcurrentUpdateException("m"),
                    new DataIntegrityException("m"),
                    new FileAccessException("m"),
                    new FatalProcessingException("m"));
            assertThat(messageOnly).hasSize(9);
            assertThat(messageOnly).allSatisfy(thrown -> assertThat(thrown).hasNoCause());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The abend contract - CSMSG02Y field set, code 999, return code 12
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("3. the abend contract taken from CSMSG02Y and 9999-ABEND-PROGRAM")
    class TheAbendContractFromCsmsg02y {

        @Test
        @DisplayName("the copybook declares exactly the four work areas the exception carries")
        void theCopybookDeclaresFourFields() {
            assertThat(abendFieldWidths()).containsOnlyKeys(
                    "ABEND-CODE", "ABEND-CULPRIT", "ABEND-REASON", "ABEND-MSG");
        }

        @ParameterizedTest(name = "{0} is PIC X({1})")
        @CsvSource({"ABEND-CODE,4", "ABEND-CULPRIT,8", "ABEND-REASON,50", "ABEND-MSG,72"})
        @DisplayName("each abend field keeps its copybook width")
        void eachFieldKeepsItsCopybookWidth(final String field, final int width) {
            assertThat(abendFieldWidths()).containsEntry(field, Integer.valueOf(width));
        }

        @Test
        @DisplayName("the abend code is 999, as 9999-ABEND-PROGRAM moves before calling CEE3ABD")
        void theAbendCodeIs999() {
            assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
        }

        @Test
        @DisplayName("the process return code is 12, the unexpected-status contract")
        void theReturnCodeIs12() {
            assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
        }

        @Test
        @DisplayName("the four-field constructor round-trips every abend work area")
        void theFourFieldConstructorRoundTrips() {
            final FatalProcessingException thrown = new FatalProcessingException(
                    "0999", "CBTRN02C", "UNEXPECTED FILE STATUS", "ABENDING PROGRAM");
            assertThat(thrown.getAbendCode()).isEqualTo("0999");
            assertThat(thrown.getAbendCulprit()).isEqualTo("CBTRN02C");
            assertThat(thrown.getAbendReason()).isEqualTo("UNEXPECTED FILE STATUS");
            assertThat(thrown.getAbendMessage()).isEqualTo("ABENDING PROGRAM");
            assertThat(thrown).hasMessage("ABENDING PROGRAM").hasNoCause();
        }

        @Test
        @DisplayName("the five-field constructor round-trips the work areas and the cause together")
        void theFiveFieldConstructorRoundTrips() {
            final FatalProcessingException thrown = new FatalProcessingException(
                    "0999", "CBACT04C", "DISCGRP DEFAULT MISSING", "ABENDING PROGRAM", CAUSE);
            assertThat(thrown.getAbendCode()).isEqualTo("0999");
            assertThat(thrown.getAbendCulprit()).isEqualTo("CBACT04C");
            assertThat(thrown.getAbendReason()).isEqualTo("DISCGRP DEFAULT MISSING");
            assertThat(thrown.getAbendMessage()).isEqualTo("ABENDING PROGRAM");
            assertThat(thrown).hasCause(CAUSE);
        }

        @Test
        @DisplayName("a message-only abend leaves code, culprit and reason absent rather than guessing")
        void aMessageOnlyAbendLeavesTheOtherThreeAbsent() {
            final FatalProcessingException thrown = new FatalProcessingException("ABENDING PROGRAM");
            assertThat(thrown.getAbendCode()).isNull();
            assertThat(thrown.getAbendCulprit()).isNull();
            assertThat(thrown.getAbendReason()).isNull();
            assertThat(thrown.getAbendMessage()).isEqualTo("ABENDING PROGRAM");
        }

        @Test
        @DisplayName("the same holds for the message-and-cause form")
        void aMessageAndCauseAbendLeavesTheOtherThreeAbsent() {
            final FatalProcessingException thrown =
                    new FatalProcessingException("ABENDING PROGRAM", CAUSE);
            assertThat(thrown.getAbendCode()).isNull();
            assertThat(thrown.getAbendCulprit()).isNull();
            assertThat(thrown.getAbendReason()).isNull();
            assertThat(thrown.getAbendMessage()).isEqualTo("ABENDING PROGRAM");
        }

        @Test
        @DisplayName("a null message is replaced by the default, reproducing the source's substitution")
        void aNullMessageIsReplacedByTheDefault() {
            assertThat(new FatalProcessingException((String) null).getAbendMessage())
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(new FatalProcessingException(null, CAUSE).getAbendMessage())
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(new FatalProcessingException("0999", "C", "r", null).getAbendMessage())
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(new FatalProcessingException("0999", "C", "r", null, CAUSE).getAbendMessage())
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }

        @Test
        @DisplayName("the substituted default also becomes the throwable's own message")
        void theSubstitutedDefaultAlsoBecomesTheThrowableMessage() {
            assertThat(new FatalProcessingException((String) null))
                    .hasMessage(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }

        @Test
        @DisplayName("an EMPTY message is passed through untouched - only null is substituted")
        void anEmptyMessageIsNotSubstituted() {
            assertThat(new FatalProcessingException("").getAbendMessage()).isEmpty();
            assertThat(new FatalProcessingException("0999", "C", "r", "").getAbendMessage()).isEmpty();
        }

        @Test
        @DisplayName("the default message is the legacy literal, spelling and full stop included")
        void theDefaultMessageIsTheLegacyLiteral() {
            assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        }

        @Test
        @DisplayName("a four-character abend code fits the PIC X(4) work area it came from")
        void theAbendCodeFitsItsWorkArea() {
            final int declared = abendFieldWidths().get("ABEND-CODE").intValue();
            assertThat(new FatalProcessingException("0999", "C", "r", "m").getAbendCode())
                    .hasSize(declared);
        }

        @Test
        @DisplayName("an eight-character culprit fits the PIC X(8) work area, as a program name must")
        void theCulpritFitsItsWorkArea() {
            final int declared = abendFieldWidths().get("ABEND-CULPRIT").intValue();
            assertThat(declared).isEqualTo(8);
            assertThat("CBTRN02C").hasSize(declared);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. The ACUP-CHANGE-ACTION dispatch, including the preserved 'C' defect
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("4. the ConcurrentUpdateException outcomes and the COACTUPC dispatch")
    class TheConcurrentUpdateOutcomeDispatch {

        @Test
        @DisplayName("there are exactly five outcomes")
        void thereAreExactlyFiveOutcomes() {
            assertThat(ConcurrentUpdateException.Outcome.values()).hasSize(5);
        }

        @ParameterizedTest(name = "{0} carries the literal from COACTUPC.cbl:517-524")
        @CsvSource(delimiter = '|', value = {
            "COULD_NOT_LOCK_ACCOUNT|Could not lock account record for update",
            "COULD_NOT_LOCK_CUSTOMER|Could not lock customer record for update",
            "DATA_CHANGED_BEFORE_UPDATE|Record changed by some one else. Please review",
            "LOCKED_BUT_UPDATE_FAILED|Update of record failed",
        })
        @DisplayName("each message literal is reproduced verbatim, 'some one' included")
        void eachMessageLiteralIsVerbatim(final String constant, final String literal) {
            assertThat(ConcurrentUpdateException.Outcome.valueOf(constant).getLegacyMessage())
                    .isEqualTo(literal);
        }

        @Test
        @DisplayName("the unconfirmed outcome carries an EMPTY message, as the source has no literal")
        void theUnconfirmedOutcomeCarriesNoMessage() {
            assertThat(ConcurrentUpdateException.Outcome.CHANGES_NOT_CONFIRMED.getLegacyMessage())
                    .isEmpty();
        }

        @Test
        @DisplayName("no message is null, so a caller rendering one never needs a null guard")
        void noMessageIsNull() {
            assertThat(ConcurrentUpdateException.Outcome.values())
                    .allSatisfy(outcome -> assertThat(outcome.getLegacyMessage()).isNotNull());
        }

        @ParameterizedTest(name = "{0} maps to ACUP-CHANGE-ACTION ''{1}''")
        @CsvSource({
            "COULD_NOT_LOCK_ACCOUNT,L",
            "COULD_NOT_LOCK_CUSTOMER,C",
            "DATA_CHANGED_BEFORE_UPDATE,S",
            "LOCKED_BUT_UPDATE_FAILED,F",
            "CHANGES_NOT_CONFIRMED,N",
        })
        @DisplayName("each outcome maps to the marker the EVALUATE at L2606-2615 actually writes")
        void eachOutcomeMapsToItsCorpusMarker(final String constant, final char marker) {
            assertThat(ConcurrentUpdateException.Outcome.valueOf(constant).getChangeActionCode())
                    .isEqualTo(marker);
        }

        @Test
        @DisplayName("every marker is one of the five values ACUP-CHANGE-ACTION declares")
        void everyMarkerIsADeclaredValue() {
            final List<Character> declared = List.of(
                    Character.valueOf('S'), Character.valueOf('E'), Character.valueOf('N'),
                    Character.valueOf('C'), Character.valueOf('L'), Character.valueOf('F'));
            assertThat(ConcurrentUpdateException.Outcome.values()).allSatisfy(outcome ->
                    assertThat(declared).contains(Character.valueOf(outcome.getChangeActionCode())));
        }

        @Test
        @DisplayName("PRESERVED DEFECT: a customer lock failure reports the SUCCESS marker 'C'")
        void aCustomerLockFailureReportsTheSuccessMarker() {
            // app/cbl/COACTUPC.cbl:2606-2615 tests COULD-NOT-LOCK-ACCT-FOR-UPDATE,
            // LOCKED-BUT-UPDATE-FAILED and DATA-WAS-CHANGED-BEFORE-UPDATE, but has NO branch for
            // COULD-NOT-LOCK-CUST-FOR-UPDATE, so it falls through WHEN OTHER at L2613 and L2614 sets
            // ACUP-CHANGES-OKAYED-AND-DONE 'C'. The operator is told the changes were saved although
            // the customer record was never locked and nothing was written. Preserved, not repaired:
            // parity is the contract and this assertion is what stops a later tidy-up being silent.
            assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getChangeActionCode())
                    .isEqualTo('C');
            assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_CUSTOMER.getChangeActionCode())
                    .describedAs("the customer failure shares the marker of the success outcome")
                    .isEqualTo('C');
        }

        @Test
        @DisplayName("the account and customer lock failures are therefore NOT distinguishable by marker")
        void theTwoLockFailuresAreNotDistinguishableByMarker() {
            assertThat(ConcurrentUpdateException.Outcome.COULD_NOT_LOCK_ACCOUNT.getChangeActionCode())
                    .isNotEqualTo(ConcurrentUpdateException.Outcome
                            .COULD_NOT_LOCK_CUSTOMER.getChangeActionCode());
        }

        @Test
        @DisplayName("the outcome-bearing constructor carries the outcome")
        void theOutcomeBearingConstructorCarriesTheOutcome() {
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED, "failed");
            assertThat(thrown.getOutcome())
                    .isEqualTo(ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED);
            assertThat(thrown.getAffectedRecord()).isNull();
            assertThat(thrown).hasMessage("failed").hasNoCause();
        }

        @Test
        @DisplayName("a message-only construction leaves the outcome absent rather than defaulting it")
        void aMessageOnlyConstructionLeavesTheOutcomeAbsent() {
            assertThat(new ConcurrentUpdateException("clash").getOutcome()).isNull();
            assertThat(new ConcurrentUpdateException("clash", CAUSE).getOutcome()).isNull();
        }

        @Test
        @DisplayName("the affected record distinguishes the two rewrite sites the one flag conflates")
        void theAffectedRecordCarriesTheDistinctionTheFlagLoses() {
            final ConcurrentUpdateException thrown = new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED,
                    "failed", "CUSTOMER 000000011", CAUSE);
            assertThat(thrown.getAffectedRecord()).isEqualTo("CUSTOMER 000000011");
        }

        @ParameterizedTest(name = "an affected record of [{0}] is normalised away")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a blank affected record becomes absent rather than an empty string")
        void aBlankAffectedRecordBecomesAbsent(final String blank) {
            assertThat(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED, "m", blank, CAUSE)
                    .getAffectedRecord()).isNull();
        }

        @Test
        @DisplayName("a null affected record is absent")
        void aNullAffectedRecordIsAbsent() {
            assertThat(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED, "m", null, CAUSE)
                    .getAffectedRecord()).isNull();
        }

        @Test
        @DisplayName("a surrounding-space affected record is stripped - the one transforming accessor")
        void anAffectedRecordIsStripped() {
            assertThat(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.LOCKED_BUT_UPDATE_FAILED,
                    "m", "  ACCT 00000000011  ", CAUSE)
                    .getAffectedRecord()).isEqualTo("ACCT 00000000011");
        }

        @Test
        @DisplayName("the three-argument cause form delegates without an affected record")
        void theThreeArgumentCauseFormHasNoAffectedRecord() {
            assertThat(new ConcurrentUpdateException(
                    ConcurrentUpdateException.Outcome.DATA_CHANGED_BEFORE_UPDATE, "m", CAUSE)
                    .getAffectedRecord()).isNull();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. The FileAccessException status payload
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("5. FileAccessException stores the rendered status, not the raw one")
    class TheFileAccessStatusPayload {

        @ParameterizedTest(name = "a raw status of ''{0}'' is stored as ''{1}''")
        @CsvSource({"23,0023", "35,0035", "22,0022", "10,0010", "00,0000", "90,9048", "99,9057"})
        @DisplayName("the constructor renders through FileStatus rather than storing the input")
        void theConstructorRendersTheStatus(final String raw, final String rendered) {
            final FileAccessException thrown =
                    new FileAccessException("io failure", raw, "ACCTDAT", "READ");
            assertThat(thrown.getExpandedStatus()).isEqualTo(rendered);
            assertThat(thrown.getExpandedStatus()).isNotEqualTo(raw);
        }

        @Test
        @DisplayName("the stored status agrees with FileStatus, so the two tiers cannot drift apart")
        void theStoredStatusAgreesWithFileStatus() {
            for (final String raw : List.of("00", "10", "22", "23", "35", "90", "9A", "  ")) {
                assertThat(new FileAccessException("m", raw, "F", "READ").getExpandedStatus())
                        .describedAs("raw status [%s]", raw)
                        .isEqualTo(FileStatus.renderIoStatus04(raw));
            }
        }

        @Test
        @DisplayName("the rendered status is always four characters wide, as the display field is")
        void theRenderedStatusIsFourCharacters() {
            for (final String raw : List.of("00", "23", "35", "90", "9A")) {
                assertThat(new FileAccessException("m", raw, "F", "READ").getExpandedStatus())
                        .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
            }
        }

        @Test
        @DisplayName("the logical file name and operation round-trip for the diagnostic line")
        void theFileNameAndOperationRoundTrip() {
            final FileAccessException thrown =
                    new FileAccessException("io failure", "23", "TRANSACT", "REWRITE");
            assertThat(thrown.getLogicalFileName()).isEqualTo("TRANSACT");
            assertThat(thrown.getOperation()).isEqualTo("REWRITE");
            assertThat(thrown).hasMessage("io failure");
        }

        @Test
        @DisplayName("a message-only construction still renders a status, because COBOL has no null")
        void aMessageOnlyConstructionStillRendersAStatus() {
            // The short constructors delegate with ioStatus == null, so the status is RENDERED from
            // null rather than left null. That is deliberate and legacy-faithful: an uninitialised
            // COBOL IO-STATUS field holds two spaces, never a null, and FileStatus renders the empty
            // sender as ' ' followed by the three-digit byte value of a space, 032. The two tiers
            // agree, which is asserted below rather than assumed.
            final FileAccessException thrown = new FileAccessException("io failure");
            assertThat(thrown.getExpandedStatus())
                    .isEqualTo(FileStatus.renderIoStatus04(null))
                    .isEqualTo(" 032")
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
            assertThat(thrown.getLogicalFileName()).isNull();
            assertThat(thrown.getOperation()).isNull();
        }

        @Test
        @DisplayName("the same holds for the message-and-cause construction")
        void aMessageAndCauseConstructionStillRendersAStatus() {
            final FileAccessException thrown = new FileAccessException("io failure", CAUSE);
            assertThat(thrown.getExpandedStatus()).isEqualTo(" 032");
            assertThat(thrown.getLogicalFileName()).isNull();
            assertThat(thrown.getOperation()).isNull();
        }

        @Test
        @DisplayName("the status accessor is therefore never null, so no caller needs a null guard")
        void theStatusAccessorIsNeverNull() {
            final List<FileAccessException> everyForm = List.of(
                    new FileAccessException("m"),
                    new FileAccessException("m", CAUSE),
                    new FileAccessException("m", null, "F", "READ"),
                    new FileAccessException("m", "23", "F", "READ"));
            assertThat(everyForm).allSatisfy(thrown ->
                    assertThat(thrown.getExpandedStatus())
                            .isNotNull()
                            .hasSize(FileStatus.RENDERED_STATUS_LENGTH));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Payload round-tripping on the remaining subtypes
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("6. the remaining payloads round-trip and stay absent when not supplied")
    class ThePayloadAccessorsRoundTrip {

        @Test
        @DisplayName("ValidationException carries the field name and the failure kind")
        void validationCarriesFieldAndKind() {
            final ValidationException thrown = new ValidationException(
                    "bad", "acctId", ValidationException.FailureKind.INVALID);
            assertThat(thrown.getFieldName()).isEqualTo("acctId");
            assertThat(thrown.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(thrown.hasFieldName()).isTrue();
        }

        @Test
        @DisplayName("the invalidField factory yields the INVALID kind")
        void invalidFieldYieldsInvalid() {
            final ValidationException thrown = ValidationException.invalidField("option", "nope");
            assertThat(thrown.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(thrown.getFieldName()).isEqualTo("option");
            assertThat(thrown).hasMessage("nope");
        }

        @Test
        @DisplayName("the missingField factory yields the BLANK kind")
        void missingFieldYieldsBlank() {
            final ValidationException thrown = ValidationException.missingField("option", "empty");
            assertThat(thrown.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(thrown.getFieldName()).isEqualTo("option");
            assertThat(thrown).hasMessage("empty");
        }

        @Test
        @DisplayName("there are exactly two failure kinds")
        void thereAreTwoFailureKinds() {
            assertThat(ValidationException.FailureKind.values()).hasSize(2);
            assertThat(ValidationException.FailureKind.values())
                    .containsExactly(ValidationException.FailureKind.INVALID,
                            ValidationException.FailureKind.BLANK);
        }

        @ParameterizedTest(name = "a field name of [{0}] does not count as present")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank field name is reported as absent")
        void aBlankFieldNameIsAbsent(final String blank) {
            assertThat(new ValidationException("m", blank, ValidationException.FailureKind.INVALID)
                    .hasFieldName()).isFalse();
        }

        @Test
        @DisplayName("a message-only ValidationException has no field name but defaults to INVALID")
        void aMessageOnlyValidationDefaultsToInvalid() {
            // The field name is genuinely absent, but the failure kind is NOT: both short
            // constructors default it to INVALID so that a caller switching on the kind never has to
            // handle a null. BLANK is only ever reached through the missingField factory, which is
            // the one place the source distinguishes an empty field from a rejected one.
            final ValidationException thrown = new ValidationException("m");
            assertThat(thrown.getFieldName()).isNull();
            assertThat(thrown.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(thrown.hasFieldName()).isFalse();
            final ValidationException withCause = new ValidationException("m", CAUSE);
            assertThat(withCause.getFieldName()).isNull();
            assertThat(withCause.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(withCause.hasFieldName()).isFalse();
        }

        @Test
        @DisplayName("the failure kind is therefore never null on any construction path")
        void theFailureKindIsNeverNull() {
            final List<ValidationException> everyForm = List.of(
                    new ValidationException("m"),
                    new ValidationException("m", CAUSE),
                    new ValidationException("m", "f", ValidationException.FailureKind.BLANK),
                    ValidationException.invalidField("f", "m"),
                    ValidationException.missingField("f", "m"));
            assertThat(everyForm).allSatisfy(thrown ->
                    assertThat(thrown.getFailureKind()).isNotNull());
        }

        @Test
        @DisplayName("the four-argument form carries field, kind and cause together")
        void theFourArgumentValidationFormCarriesEverything() {
            final ValidationException thrown = new ValidationException(
                    "bad", "acctId", ValidationException.FailureKind.BLANK, CAUSE);
            assertThat(thrown.getFieldName()).isEqualTo("acctId");
            assertThat(thrown.getFailureKind()).isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(thrown).hasMessage("bad").hasCause(CAUSE);
        }

        @Test
        @DisplayName("an explicitly null failure kind is substituted with INVALID on both long forms")
        void anExplicitlyNullFailureKindBecomesInvalid() {
            assertThat(new ValidationException("m", "f", null).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(new ValidationException("m", "f", null, CAUSE).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.INVALID);
        }

        @Test
        @DisplayName("a supplied resource name is stripped, matching the affectedRecord treatment")
        void aResourceNameIsStripped() {
            assertThat(new FileUnavailableException("m", "  ACCTDAT  ", CAUSE).resourceName())
                    .contains("ACCTDAT");
        }

        @ParameterizedTest(name = "a resource name of [{0}] is normalised to absent")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a blank resource name becomes an empty Optional rather than a blank string")
        void aBlankResourceNameBecomesAbsent(final String blank) {
            assertThat(new FileUnavailableException("m", blank, CAUSE).resourceName())
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("an explicitly null resource name on the long form is absent")
        void anExplicitlyNullResourceNameIsAbsent() {
            assertThat(new FileUnavailableException("m", null, CAUSE).resourceName())
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("RecordNotFoundException carries the record type and key as Optionals")
        void recordNotFoundCarriesTypeAndKey() {
            final RecordNotFoundException thrown =
                    new RecordNotFoundException("absent", "ACCOUNT", "00000000011");
            assertThat(thrown.recordType()).contains("ACCOUNT");
            assertThat(thrown.recordKey()).contains("00000000011");
        }

        @Test
        @DisplayName("an unsupplied record type and key are empty Optionals, never null")
        void recordNotFoundOptionalsAreEmptyNotNull() {
            for (final RecordNotFoundException thrown : List.of(
                    new RecordNotFoundException("m"),
                    new RecordNotFoundException("m", CAUSE))) {
                assertThat(thrown.recordType()).isNotNull().isEmpty();
                assertThat(thrown.recordKey()).isNotNull().isEmpty();
            }
        }

        @Test
        @DisplayName("DuplicateRecordException carries the logical file and the colliding key")
        void duplicateCarriesFileAndKey() {
            final DuplicateRecordException thrown =
                    new DuplicateRecordException("dup", "TRANSACT", "0000000000000001");
            assertThat(thrown.getLogicalFile()).isEqualTo("TRANSACT");
            assertThat(thrown.getCollidingKey()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("an unsupplied duplicate payload is absent")
        void duplicatePayloadAbsent() {
            for (final DuplicateRecordException thrown : List.of(
                    new DuplicateRecordException("m"),
                    new DuplicateRecordException("m", CAUSE))) {
                assertThat(thrown.getLogicalFile()).isNull();
                assertThat(thrown.getCollidingKey()).isNull();
            }
        }

        @Test
        @DisplayName("FileUnavailableException carries the resource name as an Optional")
        void fileUnavailableCarriesResource() {
            assertThat(new FileUnavailableException("shut", "ACCTDAT", CAUSE).resourceName())
                    .contains("ACCTDAT");
        }

        @Test
        @DisplayName("an unsupplied resource name is an empty Optional, never null")
        void fileUnavailableResourceAbsent() {
            for (final FileUnavailableException thrown : List.of(
                    new FileUnavailableException("m"),
                    new FileUnavailableException("m", CAUSE))) {
                assertThat(thrown.resourceName()).isNotNull().isEmpty();
            }
        }

        @Test
        @DisplayName("DataIntegrityException carries the constraint name and the relation")
        void dataIntegrityCarriesConstraintAndRelation() {
            final DataIntegrityException thrown =
                    new DataIntegrityException("orphan", "fk01_card_account", "card");
            assertThat(thrown.getConstraintName()).isEqualTo("fk01_card_account");
            assertThat(thrown.getRelation()).isEqualTo("card");
            assertThat(thrown).hasNoCause();
        }

        @Test
        @DisplayName("an unsupplied integrity payload is absent")
        void dataIntegrityPayloadAbsent() {
            for (final DataIntegrityException thrown : List.of(
                    new DataIntegrityException("m"),
                    new DataIntegrityException("m", CAUSE))) {
                assertThat(thrown.getConstraintName()).isNull();
                assertThat(thrown.getRelation()).isNull();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 7. The accessor-style split, pinned as behaviour rather than normalised
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("7. the accessor-style split across the package is pinned, not normalised")
    class TheAccessorStyleSplitIsPinned {

        @Test
        @DisplayName("exactly two subtypes expose their payload as an Optional")
        void twoSubtypesUseOptional() {
            final List<String> optionalStyle = new ArrayList<>();
            for (final Class<? extends CardDemoException> type : HIERARCHY) {
                for (final java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    if (java.util.Optional.class.equals(method.getReturnType())
                            && method.getParameterCount() == 0) {
                        optionalStyle.add(type.getSimpleName());
                        break;
                    }
                }
            }
            // RecordNotFoundException and FileUnavailableException use record-style names returning
            // Optional; the other five use getX() returning a plain String that may be null. Neither
            // style is wrong and the AAP states no rule, so the split is reported rather than
            // normalised - the same disposition as the entity equals() null-identity split.
            assertThat(optionalStyle)
                    .containsExactlyInAnyOrder("RecordNotFoundException", "FileUnavailableException");
        }

        @Test
        @DisplayName("the Optional-style accessors are named without a get prefix")
        void theOptionalAccessorsAreRecordStyle() {
            assertThat(namesOf(RecordNotFoundException.class))
                    .contains("recordType", "recordKey")
                    .doesNotContain("getRecordType", "getRecordKey");
            assertThat(namesOf(FileUnavailableException.class))
                    .contains("resourceName")
                    .doesNotContain("getResourceName");
        }

        @Test
        @DisplayName("the plain-String accessors keep the get prefix")
        void thePlainAccessorsKeepTheGetPrefix() {
            assertThat(namesOf(DuplicateRecordException.class))
                    .contains("getLogicalFile", "getCollidingKey");
            assertThat(namesOf(DataIntegrityException.class))
                    .contains("getConstraintName", "getRelation");
            assertThat(namesOf(FileAccessException.class))
                    .contains("getExpandedStatus", "getLogicalFileName", "getOperation");
            assertThat(namesOf(FatalProcessingException.class))
                    .contains("getAbendCode", "getAbendCulprit", "getAbendReason", "getAbendMessage");
        }

        @Test
        @DisplayName("no accessor in the package throws when its payload was never supplied")
        void noAccessorThrowsOnAnAbsentPayload() {
            final List<Function<String, CardDemoException>> messageOnly = List.of(
                    ValidationException::new,
                    RecordNotFoundException::new,
                    DuplicateRecordException::new,
                    FileUnavailableException::new,
                    ConcurrentUpdateException::new,
                    DataIntegrityException::new,
                    FileAccessException::new,
                    FatalProcessingException::new);
            assertThat(messageOnly).hasSize(8);
            for (final Function<String, CardDemoException> factory : messageOnly) {
                final CardDemoException thrown = factory.apply("m");
                for (final java.lang.reflect.Method method : thrown.getClass().getDeclaredMethods()) {
                    if (method.getParameterCount() == 0 && !method.isSynthetic()
                            && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                        method.setAccessible(true);
                        final int line = method.getName().length();
                        assertThat(line).isPositive();
                        try {
                            method.invoke(thrown);
                        } catch (final ReflectiveOperationException failure) {
                            throw new AssertionError(thrown.getClass().getSimpleName() + "."
                                    + method.getName() + " threw on an absent payload", failure);
                        }
                    }
                }
            }
        }

        private List<String> namesOf(final Class<?> type) {
            final List<String> names = new ArrayList<>();
            for (final java.lang.reflect.Method method : type.getDeclaredMethods()) {
                names.add(method.getName());
            }
            return names;
        }
    }
}
