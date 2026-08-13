package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AbendException}, the translation of the nine {@code CALL 'CEE3ABD'} sites.
 */
@DisplayName("AbendException - the CEE3ABD equivalent and its RETURN-CODE")
class AbendExceptionContractTest {
    @Nested
    @DisplayName("Return-code constants")
    class ReturnCodes {
        @Test
        @DisplayName("the documented COBOL return codes are 0, 4, 8, 12 and 16")
        void returnCodeConstants() {
            assertThat(AbendException.RETURN_CODE_OK).isZero();
            assertThat(AbendException.RETURN_CODE_WARNING).isEqualTo(4);
            assertThat(AbendException.RETURN_CODE_ASSUMED_FAILURE).isEqualTo(8);
            assertThat(AbendException.RETURN_CODE_IO_ERROR).isEqualTo(12);
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isEqualTo(16);
        }

        @Test
        @DisplayName("the standard abend parameters match the COBOL CEE3ABD call")
        void standardAbendParameters() {
            assertThat(AbendException.STANDARD_ABEND_CODE).isEqualTo(999);
            assertThat(AbendException.STANDARD_TIMING).isZero();
            assertThat(AbendException.ABEND_DISPLAY_TEXT).isEqualTo("ABENDING PROGRAM");
        }
    }

    @Nested
    @DisplayName("standard() - the CEE3ABD shape that passes abend code and timing")
    class StandardFactory {
        @Test
        @DisplayName("carries the program, the return code and the standard abend parameters")
        void carriesProgramAndReturnCode() {
            AbendException abend = AbendException.standard("CBACT01C", 12);
            assertThat(abend.getProgram()).isEqualTo("CBACT01C");
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.hasTiming()).isTrue();
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @Test
        @DisplayName("with no reason supplied, the reason is absent rather than empty")
        void noReasonMeansAbsent() {
            AbendException abend = AbendException.standard("CBACT01C", 12);
            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
        }

        @Test
        @DisplayName("a supplied reason is retained and appears in the message")
        void reasonIsRetained() {
            AbendException abend = AbendException.standard("CBTRN02C", 8, "ACCTFILE read failed");
            assertThat(abend.hasReason()).isTrue();
            assertThat(abend.getReason()).contains("ACCTFILE read failed");
            assertThat(abend.getMessage()).contains("ACCTFILE read failed");
        }

        @Test
        @DisplayName("a cause is retained for diagnosis without altering the return code")
        void causeIsRetained() {
            IOException cause = new IOException("disk");
            AbendException abend = AbendException.standard("CBSTM03A", 12, "write failed", cause);
            assertThat(abend).hasCause(cause);
            assertThat(abend.getReturnCode()).isEqualTo(12);
        }

        @Test
        @DisplayName("the message names the program, the return code and both abend parameters")
        void messageShape() {
            AbendException abend = AbendException.standard("CBACT04C", 12);
            assertThat(abend.getMessage())
                    .startsWith("ABENDING PROGRAM CBACT04C RETURN-CODE=12")
                    .contains("ABCODE=999")
                    .contains("TIMING=0");
        }
    }

    @Nested
    @DisplayName("withoutAbendParameters() - the shape that omits abend code and timing")
    class WithoutAbendParametersFactory {
        @Test
        @DisplayName("both abend parameters are absent, and that is distinguishable from zero")
        void abendParametersAreAbsent() {
            AbendException abend = AbendException.withoutAbendParameters("CBTRN01C", 8);
            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.getAbendCode()).isEmpty();
            assertThat(abend.hasTiming()).isFalse();
            assertThat(abend.getTiming()).isEmpty();
        }

        @Test
        @DisplayName("the message omits ABCODE and TIMING entirely when they are absent")
        void messageOmitsAbsentParameters() {
            AbendException abend = AbendException.withoutAbendParameters("CBTRN01C", 8);
            assertThat(abend.getMessage())
                    .isEqualTo("ABENDING PROGRAM CBTRN01C RETURN-CODE=8")
                    .doesNotContain("ABCODE")
                    .doesNotContain("TIMING");
        }

        @Test
        @DisplayName("a reason is appended when supplied")
        void reasonIsAppended() {
            AbendException abend =
                    AbendException.withoutAbendParameters("CBCUS01C", 12, "CUSTFILE status 35");
            assertThat(abend.getMessage()).endsWith(" - CUSTFILE status 35");
            assertThat(abend.getReason()).contains("CUSTFILE status 35");
        }

        @Test
        @DisplayName("a cause is retained")
        void causeIsRetained() {
            IllegalStateException cause = new IllegalStateException("nope");
            AbendException abend =
                    AbendException.withoutAbendParameters("CBACT02C", 12, "reason", cause);
            assertThat(abend).hasCause(cause);
        }
    }

    @Nested
    @DisplayName("Reason normalisation - blank is treated as absent")
    class ReasonNormalisation {
        @ParameterizedTest(name = "a reason of [{0}] normalises to absent")
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        @DisplayName("a null, empty or whitespace-only reason is normalised to absent")
        void blankReasonBecomesAbsent(String blank) {
            AbendException abend = AbendException.standard("CBACT01C", 12, blank);
            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getMessage()).doesNotContain(" - ");
        }

        @Test
        @DisplayName("an explicit null reason is normalised to absent")
        void nullReasonBecomesAbsent() {
            AbendException abend = AbendException.standard("CBACT01C", 12, null);
            assertThat(abend.hasReason()).isFalse();
        }

        @Test
        @DisplayName("a non-blank reason survives normalisation untouched")
        void nonBlankReasonSurvives() {
            assertThat(AbendException.standard("CBACT01C", 12, " kept ").getReason())
                    .contains(" kept ");
        }
    }

    @Nested
    @DisplayName("Program-name guards")
    class ProgramGuards {
        @Test
        @DisplayName("a null program name is rejected")
        void nullProgramRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.standard(null, 12))
                    .withMessageContaining("program");
        }

        @ParameterizedTest(name = "a blank program name [{0}] is rejected")
        @ValueSource(strings = {"", " ", "   ", "\t"})
        @DisplayName("a blank program name is rejected - the abend must name its culprit")
        void blankProgramRejected(String blank) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.standard(blank, 12))
                    .withMessageContaining("program");
        }

        @Test
        @DisplayName("the same guards apply to the withoutAbendParameters shape")
        void guardsApplyToBothFactories() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters(null, 8));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters("  ", 8));
        }
    }

    @Nested
    @DisplayName("Exception shape")
    class ExceptionShape {
        @Test
        @DisplayName("it is an unchecked exception, so COBOL abend paths need no checked plumbing")
        void isUnchecked() {
            assertThat(AbendException.standard("CBACT01C", 12)).isInstanceOf(RuntimeException.class);
        }

        @ParameterizedTest(name = "return code {0} survives onto the exception")
        @ValueSource(ints = {0, 4, 8, 12, 16})
        @DisplayName("every documented return code round-trips, so batch exit status can match")
        void everyReturnCodeRoundTrips(int returnCode) {
            assertThat(AbendException.standard("CBACT01C", returnCode).getReturnCode())
                    .isEqualTo(returnCode);
        }
    }
}
