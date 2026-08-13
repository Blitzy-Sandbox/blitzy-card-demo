package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AbendException}, the Java equivalent of {@code CALL 'CEE3ABD'}.
 */
@DisplayName("AbendException - the CALL 'CEE3ABD' equivalent at nine sites")
class AbendExceptionSemanticsTest {
    @Nested
    @DisplayName("The eight standard sites - MOVE 0 TO TIMING, MOVE 999 TO ABCODE")
    class StandardSites {
        @Test
        @DisplayName("carries ABCODE 999 and TIMING 0 as present values")
        void carriesTheStandardAbendParameters() {
            AbendException abend = AbendException.standard("CBACT01C",
                    AbendException.RETURN_CODE_IO_ERROR);

            assertThat(abend.getProgram()).isEqualTo("CBACT01C");
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.hasTiming()).isTrue();
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(abend.getTiming()).hasValue(0);
            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
        }

        @Test
        @DisplayName("a supplied reason is retained and reaches the message")
        void aSuppliedReasonIsRetained() {
            AbendException abend = AbendException.standard("CBTRN02C",
                    AbendException.RETURN_CODE_END_OF_FILE, "DALYTRAN FILE STATUS 35");

            assertThat(abend.hasReason()).isTrue();
            assertThat(abend.getReason()).contains("DALYTRAN FILE STATUS 35");
            assertThat(abend.getMessage()).contains("DALYTRAN FILE STATUS 35");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "\t"})
        void aBlankReasonIsTreatedAsAbsent(String reason) {
            AbendException abend = AbendException.standard("CBACT04C",
                    AbendException.RETURN_CODE_ASSUMED_FAILURE, reason);

            assertThat(abend.hasReason()).isFalse();
            assertThat(abend.getReason()).isEmpty();
            assertThat(abend.getMessage()).doesNotContain(" - ");
        }

        @Test
        @DisplayName("a cause is preserved for diagnosis")
        void aCauseIsPreserved() {
            RuntimeException root = new RuntimeException("read failed");
            AbendException abend = AbendException.standard("CBACT02C",
                    AbendException.RETURN_CODE_IO_ERROR, "CARDFILE unavailable", root);

            assertThat(abend).hasCause(root);
            assertThat(abend.hasAbendCode()).isTrue();
        }
    }

    @Nested
    @DisplayName("The ninth site - CBSTM03A.CBL:923 sets neither ABCODE nor TIMING")
    class TheDivergentSite {
        @Test
        @DisplayName("ABCODE and TIMING are absent, not silently zero")
        void abendParametersAreAbsentRatherThanZero() {
            AbendException abend = AbendException.withoutAbendParameters("CBSTM03A",
                    AbendException.RETURN_CODE_ASSUMED_FAILURE);

            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.getAbendCode()).isEmpty();
            assertThat(abend.hasTiming()).isFalse();
            assertThat(abend.getTiming()).isEmpty();

            assertThat(abend.getTiming()).isNotEqualTo(
                    AbendException.standard("CBACT01C", 8).getTiming());
            assertThat(abend.getMessage()).doesNotContain("ABCODE").doesNotContain("TIMING");
        }

        @Test
        @DisplayName("reason and cause overloads behave the same way")
        void reasonAndCauseOverloadsAgree() {
            RuntimeException root = new RuntimeException("TRNXFILE open failed");

            AbendException withReason = AbendException.withoutAbendParameters("CBSTM03A", 12,
                    "WS-M03B-RC 30");
            AbendException withCause = AbendException.withoutAbendParameters("CBSTM03A", 12,
                    "WS-M03B-RC 30", root);

            assertThat(withReason.hasReason()).isTrue();
            assertThat(withReason.hasAbendCode()).isFalse();
            assertThat(withCause).hasCause(root);
            assertThat(withCause.getMessage()).isEqualTo(withReason.getMessage());
        }
    }

    @Nested
    @DisplayName("Contract - display text, return codes, guards and unchecked propagation")
    class Contract {
        @Test
        @DisplayName("the display literal is byte-exact and sixteen characters")
        void theDisplayLiteralIsByteExact() {
            assertThat(AbendException.ABEND_DISPLAY_TEXT).isEqualTo("ABENDING PROGRAM").hasSize(16);
            assertThat(AbendException.standard("CBACT03C", 0).getMessage())
                    .startsWith(AbendException.ABEND_DISPLAY_TEXT);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 4, 8, 12, 16})
        void everyObservedReturnCodeRoundTrips(int returnCode) {
            assertThat(AbendException.standard("CBCUS01C", returnCode).getReturnCode())
                    .isEqualTo(returnCode);
            assertThat(AbendException.withoutAbendParameters("CBSTM03A", returnCode).getReturnCode())
                    .isEqualTo(returnCode);
        }

        @Test
        @DisplayName("the named return-code constants match the values observed in the COBOL")
        void theReturnCodeConstantsMatchTheSource() {
            assertThat(AbendException.RETURN_CODE_OK).isZero();
            assertThat(AbendException.RETURN_CODE_WARNING).isEqualTo(4);
            assertThat(AbendException.RETURN_CODE_ASSUMED_FAILURE).isEqualTo(8);
            assertThat(AbendException.RETURN_CODE_IO_ERROR).isEqualTo(12);
            assertThat(AbendException.RETURN_CODE_END_OF_FILE).isEqualTo(16);
        }

        @Test
        @DisplayName("the abending program must be named")
        void theProgramNameIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.standard(null, 12))
                    .withMessageContaining("program must not be null");
            assertThatNullPointerException()
                    .isThrownBy(() -> AbendException.withoutAbendParameters(null, 12));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.standard("   ", 12))
                    .withMessageContaining("program must not be blank");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> AbendException.standard("", 12));
        }

        @Test
        @DisplayName("it is unchecked, so no repository signature needs a throws clause")
        void itIsUnchecked() {
            assertThat(AbendException.standard("CBTRN03C", 12)).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("the composed message is deterministic and locale-independent")
        void theMessageIsDeterministic() {
            String first = AbendException.standard("CBTRN01C", 12, "XREFFILE STATUS 23").getMessage();
            String second = AbendException.standard("CBTRN01C", 12, "XREFFILE STATUS 23").getMessage();

            assertThat(first).isEqualTo(second)
                    .contains("CBTRN01C")
                    .contains("RETURN-CODE=12")
                    .contains("ABCODE=999")
                    .contains("TIMING=0");
        }
    }
}
