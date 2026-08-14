/*
 * ******************************************************************
 * Program     : FatalProcessingExceptionTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that FatalProcessingException reproduces the
 *               legacy abend contract exactly - the four CABENDD work
 *               areas as its payload, abend code 999 and return code 12
 *               as the batch termination pair, and the LOW-VALUES
 *               default-message substitution reproduced as a
 *               null-only substitution rather than a blank-or-null one,
 *               because SPACES is not LOW-VALUES in the source.
 * Source      : app/cpy/CSMSG02Y.cpy:L22-L28 (internally CABENDD.CPY) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L2634-L2638, L4205-L4209 @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L707-L711 (9999-ABEND-PROGRAM) @ 7756d89
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
import com.cardemo.exception.FatalProcessingException;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link FatalProcessingException}, the Java form of the legacy abend path.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>The legacy corpus terminates abnormally in two distinct ways, and this type has to carry both without
 * conflating them:
 *
 * <ul>
 *   <li><strong>The online abend</strong> populates four work areas from {@code app/cpy/CSMSG02Y.cpy} -
 *       {@code ABEND-CODE PIC X(4)}, {@code ABEND-CULPRIT PIC X(8)}, {@code ABEND-REASON PIC X(50)} and
 *       {@code ABEND-MSG PIC X(72)} - then sends the block to the terminal
 *       ({@code app/cbl/COACTUPC.cbl:L2634-L2638}). Those are <em>character display</em> fields, which is
 *       why the four payload accessors return {@link String}. The source moves the literal {@code '0001'}
 *       into {@code ABEND-CODE}, i.e. a zero-padded four-character value, not an integer.</li>
 *   <li><strong>The batch abend</strong> is a different mechanism entirely:
 *       {@code MOVE 999 TO ABCODE} followed by {@code CALL 'CEE3ABD'}
 *       ({@code app/cbl/CBTRN02C.cbl:L707-L711}). {@code ABCODE} is the Language Environment abend-code
 *       parameter, a number, which is why {@link FatalProcessingException#BATCH_ABEND_CODE} is an
 *       {@code int} while {@code getAbendCode()} is a {@code String}. This test asserts both
 *       representations side by side precisely so that the two are never merged - they are not the same
 *       field and they do not have the same width.</li>
 *   </ul>
 *
 * <p>The subtle assertion in this class concerns the default-message substitution. The source reads
 * {@code IF ABEND-MSG EQUAL LOW-VALUES / MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG}
 * ({@code app/cbl/COACTUPC.cbl:L4205-L4206}). {@code LOW-VALUES} is binary zeros - the state of a field
 * that was never assigned - and it is <strong>not</strong> {@code SPACES}. A COBOL {@code ABEND-MSG} filled
 * with blanks therefore does <em>not</em> get the default message. The faithful Java translation is
 * consequently a substitution on {@code null} only, and an empty or blank message must survive
 * unsubstituted. This test pins that in both directions, because "helpfully" extending the substitution to
 * blank strings is the obvious change to make and it would be a parity defect.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=FatalProcessingExceptionTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. The only default in play is {@link FatalProcessingException#DEFAULT_ABEND_MESSAGE}, which is a
 * compile-time constant transcribed from the source literal.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The blank-message assertions fail.</strong> Somebody widened
 *       {@code substituteAbendMessage} from a null check to a {@code isBlank()} check. That is a behaviour
 *       change against {@code L4205}; revert it.</li>
 *   <li><strong>The abend-code type assertions fail.</strong> The {@code X(4)} display field and the
 *       numeric {@code ABCODE} parameter were merged. They are different fields in different mechanisms.</li>
 *   <li><strong>A width assertion fails.</strong> The payload no longer fits the CABENDD field it
 *       represents, so rendering it into the legacy 4/8/50/72 layout would truncate.</li>
 *   </ul>
 *
 * @see FatalProcessingException
 */
class FatalProcessingExceptionTest {

    /** {@code ABEND-CODE PIC X(4)} - app/cpy/CSMSG02Y.cpy:L22. */
    private static final int ABEND_CODE_WIDTH = 4;

    /** {@code ABEND-CULPRIT PIC X(8)} - app/cpy/CSMSG02Y.cpy:L24. */
    private static final int ABEND_CULPRIT_WIDTH = 8;

    /** {@code ABEND-REASON PIC X(50)} - app/cpy/CSMSG02Y.cpy:L26. */
    private static final int ABEND_REASON_WIDTH = 50;

    /** {@code ABEND-MSG PIC X(72)} - app/cpy/CSMSG02Y.cpy:L28. */
    private static final int ABEND_MSG_WIDTH = 72;

    /** The literal moved into ABEND-CODE by the online abend path - app/cbl/COACTUPC.cbl:L2635. */
    private static final String LEGACY_ABEND_CODE = "0001";

    /** An eight-character program name, the width ABEND-CULPRIT allows. */
    private static final String CULPRIT = "COACTUPC";

    private static final String REASON = "ACCOUNT LOCK FAILED DURING WRITE PROCESSING";

    private static final String MESSAGE = "UNABLE TO REWRITE ACCOUNT RECORD.";

    @Nested
    @DisplayName("the batch termination pair, which is a different mechanism from the display fields")
    class BatchTerminationPair {

        @Test
        @DisplayName("BATCH_ABEND_CODE is the integer 999 transcribed from MOVE 999 TO ABCODE")
        void batchAbendCodeIs999() {
            assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                    .as("app/cbl/CBTRN02C.cbl:L709 moves the literal 999 into ABCODE immediately before "
                            + "CALL 'CEE3ABD'; any other value would abend with a code operations do not "
                            + "recognise")
                    .isEqualTo(999);
        }

        @Test
        @DisplayName("BATCH_RETURN_CODE is 12, the process exit status an unexpected status must produce")
        void batchReturnCodeIs12() {
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("the batch exit-code contract is RC 4 when and only when rejects exceed zero, and "
                            + "RC 12 on an unexpected file status; JCL COND gating reads this value")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the numeric abend code is an int, kept distinct from the X(4) display field")
        void theNumericAbendCodeIsAnInt() throws ReflectiveOperationException {
            assertThat(FatalProcessingException.class.getDeclaredField("BATCH_ABEND_CODE").getType())
                    .as("ABCODE is a Language Environment numeric parameter, not the four-character "
                            + "ABEND-CODE work area; merging them would lose the leading zeros of a value "
                            + "like '0001'")
                    .isEqualTo(int.class);
            assertThat(FatalProcessingException.class.getMethod("getAbendCode").getReturnType())
                    .as("ABEND-CODE is PIC X(4), a display field, so it must stay a String")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("999 renders into the four-character ABEND-CODE field without truncation")
        void the999CodeFitsTheDisplayField() {
            final String rendered = String.format("%04d", FatalProcessingException.BATCH_ABEND_CODE);

            assertThat(rendered)
                    .as("were the numeric code ever moved into ABEND-CODE it would occupy four characters, "
                            + "exactly the declared width; a five-digit code such as 9999 plus a sign "
                            + "would not fit")
                    .isEqualTo("0999")
                    .hasSize(ABEND_CODE_WIDTH);
        }
    }

    @Nested
    @DisplayName("the LOW-VALUES default-message substitution, which is null-only and not blank-or-null")
    class DefaultMessageSubstitution {

        @Test
        @DisplayName("DEFAULT_ABEND_MESSAGE is the source literal, character for character")
        void defaultMessageIsTheSourceLiteral() {
            assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                    .as("app/cbl/COACTUPC.cbl:L4206 moves the literal 'UNEXPECTED ABEND OCCURRED.' - "
                            + "including the terminating full stop, which the parity comparison reads")
                    .isEqualTo("UNEXPECTED ABEND OCCURRED.");
        }

        @Test
        @DisplayName("DEFAULT_ABEND_MESSAGE fits the 72-character ABEND-MSG field")
        void defaultMessageFitsTheField() {
            assertThat(FatalProcessingException.DEFAULT_ABEND_MESSAGE.length())
                    .as("ABEND-MSG is PIC X(72); a default longer than that could not be moved into the "
                            + "field the source sends to the terminal")
                    .isLessThanOrEqualTo(ABEND_MSG_WIDTH);
        }

        @Test
        @DisplayName("a null message IS substituted, reproducing the LOW-VALUES branch")
        void nullMessageIsSubstituted() {
            final FatalProcessingException thrown = new FatalProcessingException((String) null);

            assertThat(thrown.getAbendMessage())
                    .as("null is the Java analogue of LOW-VALUES, a field never assigned, which is the "
                            + "exact condition at app/cbl/COACTUPC.cbl:L4205")
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(thrown.getMessage())
                    .as("the substitution must reach getMessage() too, because that is what the log and "
                            + "the operator see")
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("an empty or blank message is NOT substituted, because SPACES is not LOW-VALUES")
        void blankMessageIsNotSubstituted(final String blank) {
            final FatalProcessingException thrown = new FatalProcessingException(blank);

            assertThat(thrown.getAbendMessage())
                    .as("the source tests LOW-VALUES, i.e. binary zeros - a blank-filled ABEND-MSG does "
                            + "NOT take the default branch, so widening the check to isBlank() would be a "
                            + "parity defect")
                    .isEqualTo(blank)
                    .isNotEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }

        @Test
        @DisplayName("substitution applies on the four-argument constructor as well, not just the short one")
        void substitutionAppliesOnTheFourArgumentConstructor() {
            final FatalProcessingException thrown =
                    new FatalProcessingException(LEGACY_ABEND_CODE, CULPRIT, REASON, null);

            assertThat(thrown.getAbendMessage())
                    .as("every construction path routes through substituteAbendMessage, so the operator "
                            + "never sees an empty diagnostic regardless of which constructor was used")
                    .isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(thrown.getMessage()).isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
        }

        @Test
        @DisplayName("substitution applies on the five-argument constructor, preserving the cause")
        void substitutionAppliesOnTheFiveArgumentConstructor() {
            final IOException cause = new IOException("VSAM logic error");
            final FatalProcessingException thrown =
                    new FatalProcessingException(LEGACY_ABEND_CODE, CULPRIT, REASON, null, cause);

            assertThat(thrown.getAbendMessage()).isEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE);
            assertThat(thrown.getCause())
                    .as("the substitution must not cost the root cause")
                    .isSameAs(cause);
        }

        @Test
        @DisplayName("a supplied message is carried through verbatim with no trimming or casing")
        void suppliedMessageIsCarriedVerbatim() {
            final String awkward = "  MIXED Case  message  ";
            final FatalProcessingException thrown = new FatalProcessingException(awkward);

            assertThat(thrown.getAbendMessage())
                    .as("the abend text is a literal the parity comparison reads byte for byte; trimming "
                            + "or upper-casing it would shift the comparison")
                    .isEqualTo(awkward);
        }
    }

    @Nested
    @DisplayName("the four CABENDD display fields as the exception payload")
    class AbendDisplayFields {

        @Test
        @DisplayName("the four-argument constructor records code, culprit, reason and message")
        void fourArgumentConstructorRecordsEveryField() {
            final FatalProcessingException thrown =
                    new FatalProcessingException(LEGACY_ABEND_CODE, CULPRIT, REASON, MESSAGE);

            assertThat(thrown.getAbendCode())
                    .as("ABEND-CODE carries the literal '0001' at app/cbl/COACTUPC.cbl:L2635, so leading "
                            + "zeros must survive - which they cannot if the field is an int")
                    .isEqualTo(LEGACY_ABEND_CODE);
            assertThat(thrown.getAbendCulprit())
                    .as("ABEND-CULPRIT receives LIT-THISPGM, the failing program's own name")
                    .isEqualTo(CULPRIT);
            assertThat(thrown.getAbendReason()).isEqualTo(REASON);
            assertThat(thrown.getAbendMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the message-only constructor leaves code, culprit and reason null, not blank")
        void messageOnlyConstructorLeavesTheOtherThreeNull() {
            final FatalProcessingException thrown = new FatalProcessingException(MESSAGE);

            assertThat(thrown.getAbendCode())
                    .as("an unpopulated work area must be distinguishable from one deliberately set to "
                            + "spaces - the source does MOVE SPACES TO ABEND-REASON at L2636 as a "
                            + "positive act, which null does not claim to represent")
                    .isNull();
            assertThat(thrown.getAbendCulprit()).isNull();
            assertThat(thrown.getAbendReason()).isNull();
            assertThat(thrown.getAbendMessage()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName("the message-and-cause constructor also leaves the other three null")
        void messageAndCauseConstructorLeavesTheOtherThreeNull() {
            final IOException cause = new IOException("device not ready");
            final FatalProcessingException thrown = new FatalProcessingException(MESSAGE, cause);

            assertThat(thrown.getAbendCode()).isNull();
            assertThat(thrown.getAbendCulprit()).isNull();
            assertThat(thrown.getAbendReason()).isNull();
            assertThat(thrown.getAbendMessage()).isEqualTo(MESSAGE);
            assertThat(thrown.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("SPACES in the reason field is preserved as spaces, distinct from an absent value")
        void spacesInTheReasonFieldArePreserved() {
            final String spaces = " ".repeat(ABEND_REASON_WIDTH);
            final FatalProcessingException thrown =
                    new FatalProcessingException(LEGACY_ABEND_CODE, CULPRIT, spaces, MESSAGE);

            assertThat(thrown.getAbendReason())
                    .as("app/cbl/COACTUPC.cbl:L2636 moves SPACES into ABEND-REASON deliberately; "
                            + "normalising that to null would erase the distinction between a field the "
                            + "program cleared and one it never touched")
                    .isEqualTo(spaces)
                    .hasSize(ABEND_REASON_WIDTH);
        }

        @Test
        @DisplayName("the payload is not normalised: values are stored exactly as supplied")
        void thePayloadIsNotNormalised() {
            final FatalProcessingException thrown =
                    new FatalProcessingException("  1 ", "  cobol ", "  reason  ", MESSAGE);

            assertThat(thrown.getAbendCode())
                    .as("observed behaviour, pinned deliberately: this type applies no strip and no "
                            + "blank-to-null conversion to its three code fields, unlike "
                            + "FileUnavailableException which strips and RecordNotFoundException which "
                            + "converts blank to null - the three policies genuinely differ and each is "
                            + "asserted where it lives")
                    .isEqualTo("  1 ");
            assertThat(thrown.getAbendCulprit()).isEqualTo("  cobol ");
            assertThat(thrown.getAbendReason()).isEqualTo("  reason  ");
        }

        @Test
        @DisplayName("each legacy field width is wide enough for the values the source moves into it")
        void eachLegacyFieldWidthAccommodatesItsSourceValue() {
            assertThat(LEGACY_ABEND_CODE.length())
                    .as("ABEND-CODE PIC X(4) at app/cpy/CSMSG02Y.cpy:L22")
                    .isEqualTo(ABEND_CODE_WIDTH);
            assertThat(CULPRIT.length())
                    .as("ABEND-CULPRIT PIC X(8) at app/cpy/CSMSG02Y.cpy:L24 holds a program name, and "
                            + "COBOL program names are at most eight characters")
                    .isEqualTo(ABEND_CULPRIT_WIDTH);
            assertThat(REASON.length())
                    .as("ABEND-REASON PIC X(50) at app/cpy/CSMSG02Y.cpy:L26")
                    .isLessThanOrEqualTo(ABEND_REASON_WIDTH);
            assertThat(MESSAGE.length())
                    .as("ABEND-MSG PIC X(72) at app/cpy/CSMSG02Y.cpy:L28")
                    .isLessThanOrEqualTo(ABEND_MSG_WIDTH);
        }

        @Test
        @DisplayName("the payload renders into the fixed-width CABENDD block at exactly 134 bytes")
        void thePayloadRendersIntoTheFixedWidthBlock() {
            final FatalProcessingException thrown =
                    new FatalProcessingException(LEGACY_ABEND_CODE, CULPRIT, REASON, MESSAGE);
            final String block = pad(thrown.getAbendCode(), ABEND_CODE_WIDTH)
                    + pad(thrown.getAbendCulprit(), ABEND_CULPRIT_WIDTH)
                    + pad(thrown.getAbendReason(), ABEND_REASON_WIDTH)
                    + pad(thrown.getAbendMessage(), ABEND_MSG_WIDTH);

            assertThat(block)
                    .as("4 + 8 + 50 + 72 = 134; the source sends this block to the terminal as one "
                            + "contiguous area, so the payload must round-trip to that exact geometry")
                    .hasSize(ABEND_CODE_WIDTH + ABEND_CULPRIT_WIDTH + ABEND_REASON_WIDTH + ABEND_MSG_WIDTH)
                    .hasSize(134)
                    .startsWith(LEGACY_ABEND_CODE + CULPRIT);
        }

        private String pad(final String value, final int width) {
            final String safe = value == null ? "" : value;
            return safe.length() >= width ? safe.substring(0, width) : safe + " ".repeat(width - safe.length());
        }
    }

    @Nested
    @DisplayName("hierarchy placement, so a boundary handler can map it to a fatal outcome")
    class HierarchyPlacement {

        @Test
        @DisplayName("it is a CardDemoException, so the shared boundary handler catches it")
        void itIsACardDemoException() {
            assertThat(new FatalProcessingException(MESSAGE))
                    .as("every typed I/O failure funnels through the base; the fatal type is the terminal "
                            + "case of that funnel, not a parallel hierarchy")
                    .isInstanceOf(CardDemoException.class)
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("it is the type an unexpected file status maps to, per the status vocabulary")
        void itIsTheTypeAnUnexpectedStatusMapsTo() {
            final FatalProcessingException thrown = new FatalProcessingException(
                    "0999", "CBTRN02C", "UNEXPECTED FILE STATUS ON DALYTRAN", "ABENDING PROGRAM");

            assertThat(thrown.getAbendCulprit())
                    .as("the guard at app/cbl/CBTRN02C.cbl:L707-L711 names the failing program before "
                            + "calling CEE3ABD, and that name is what an operator needs first")
                    .isEqualTo("CBTRN02C");
            assertThat(thrown.getMessage()).isEqualTo("ABENDING PROGRAM");
        }
    }
}
