package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link CicsResponse}, the {@code RESP}/{@code RESP2} pair a dataset outcome carries.
 *
 * <h2>What is being pinned</h2>
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L3703-L3710} issues its read with
 * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and captures <em>both</em> values, and
 * {@code L3722-L3729} renders both into the message the operator reads. The pair therefore has to be a
 * value the repositories carry, not a number one of them recomputes and another guesses at. These tests
 * pin three properties of that value:
 *
 * <ul>
 *   <li>the reason code is {@link FileStatus#NO_REASON_CODE} wherever the condition genuinely carries no
 *       further reason, rather than being left to a caller's assumption;</li>
 *   <li>a value that is not a reason code - a vendor error number, a row count, a record width - cannot
 *       be put where the reason code belongs, because those are exactly the quantities that were being
 *       reported as reason codes and are not one;</li>
 *   <li>the rendering carries two numbers and nothing else, so no record content can reach a log line
 *       through it.</li>
 * </ul>
 */
@DisplayName("CicsResponse - the RESP and RESP2 pair, carried rather than derived")
class CicsResponseTest {

    @Nested
    @DisplayName("The pair for a batch FILE STATUS")
    class FromBatchStatus {

        @ParameterizedTest(name = "status ''{0}'' translates to RESP {1}")
        @CsvSource({
            "00, 0",
            "10, 20",
            "23, 13",
        })
        @DisplayName("the response is the one-to-one translation, and the reason code is none")
        void theResponseIsTheTranslation(String status, int expectedResp) {
            CicsResponse response = CicsResponse.ofBatchStatus(status);

            assertThat(response.resp()).hasValue(expectedResp);
            assertThat(response.hasResp()).isTrue();
            assertThat(response.resp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(response.hasReason()).isFalse();
        }

        @Test
        @DisplayName("a status with no single CICS counterpart reports no response, not a wrong one")
        void anAmbiguousStatusReportsNoResponse() {
            // FileStatus.cicsRespOfBatchStatus reports the ambiguity rather than resolving it, and this
            // type carries that emptiness through instead of substituting a plausible number.
            CicsResponse response = CicsResponse.ofBatchStatus(FileStatus.DUPLICATE);

            assertThat(response.resp()).isEmpty();
            assertThat(response.hasResp()).isFalse();
            assertThat(response.resp2()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("a status that is not two characters is refused, as it is everywhere else")
        void aStatusIsTwoCharacters() {
            assertThatNullPointerException().isThrownBy(() -> CicsResponse.ofBatchStatus(null))
                    .withMessageContaining("FILE STATUS must not be null");
            assertThatIllegalArgumentException().isThrownBy(() -> CicsResponse.ofBatchStatus("0"))
                    .withMessageContaining("exactly 2 characters");
        }
    }

    @Nested
    @DisplayName("The pair a deployment's adapter reported")
    class Reported {

        @Test
        @DisplayName("both values are carried verbatim, and neither is derived from the other")
        void bothValuesAreCarriedVerbatim() {
            CicsResponse response = CicsResponse.reported(FileStatus.NOTFND, 80);

            assertThat(response.resp()).hasValue(FileStatus.NOTFND);
            assertThat(response.resp2()).isEqualTo(80);
            assertThat(response.hasResp()).isTrue();
            assertThat(response.hasReason()).isTrue();
        }

        @Test
        @DisplayName("a known response with no further reason carries the no-reason code")
        void aKnownResponseWithNoFurtherReason() {
            CicsResponse response = CicsResponse.of(FileStatus.LENGERR);

            assertThat(response.resp()).hasValue(FileStatus.LENGERR);
            assertThat(response.resp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(response.hasReason()).isFalse();
        }

        @Test
        @DisplayName("neither number may be negative, and the message names the fabrication it prevents")
        void neitherNumberMayBeNegative() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CicsResponse.reported(-1, FileStatus.NO_REASON_CODE))
                    .withMessageContaining("responses are non-negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CicsResponse.reported(FileStatus.NOTFND, -1))
                    .withMessageContaining("reason codes are non-negative")
                    .withMessageContaining("a row count, a record width")
                    .withMessageContaining("NO_REASON_CODE");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CicsResponse.of(-42))
                    .withMessageContaining("responses are non-negative");
        }

        @Test
        @DisplayName("the canonical constructor refuses a null response rather than carrying one")
        void theCanonicalConstructorRefusesANullResponse() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CicsResponse(null, FileStatus.NO_REASON_CODE))
                    .withMessageContaining("empty OptionalInt");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CicsResponse(OptionalInt.empty(), -7))
                    .withMessageContaining("reason codes are non-negative");
        }
    }

    @Nested
    @DisplayName("The pair for an outcome CICS has no name for")
    class None {

        @Test
        @DisplayName("neither a response nor a reason, and the same instance every time")
        void neitherAResponseNorAReason() {
            CicsResponse none = CicsResponse.none();

            assertThat(none.resp()).isEmpty();
            assertThat(none.hasResp()).isFalse();
            assertThat(none.resp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(none.hasReason()).isFalse();
            assertThat(CicsResponse.none()).isSameAs(none);
        }
    }

    @Nested
    @DisplayName("The rendering the COBOL message uses")
    class Rendering {

        @Test
        @DisplayName("two numbers, spelled as COACTUPC spells them")
        void twoNumbers() {
            // ' Resp:' ERROR-RESP ' Reas:' ERROR-RESP2 - app/cbl/COACTUPC.cbl:L3722-L3729.
            assertThat(CicsResponse.reported(FileStatus.NOTFND, 0).describe()).isEqualTo("Resp:13 Reas:0");
            assertThat(CicsResponse.reported(FileStatus.LENGERR, 150).describe())
                    .isEqualTo("Resp:22 Reas:150");
        }

        @Test
        @DisplayName("an absent response renders as 'none', so nothing is invented to fill the slot")
        void anAbsentResponseRendersAsNone() {
            assertThat(CicsResponse.none().describe()).isEqualTo("Resp:none Reas:0");
            assertThat(CicsResponse.ofBatchStatus(FileStatus.DUPLICATE).describe())
                    .isEqualTo("Resp:none Reas:0");
        }

        @ParameterizedTest
        @ValueSource(strings = {"00", "10", "22", "23", "97", "35"})
        @DisplayName("no status can make the rendering carry anything but digits, spaces and its labels")
        void theRenderingCarriesNothingElse(String status) {
            assertThat(CicsResponse.ofBatchStatus(status).describe())
                    .matches("Resp:(none|\\d+) Reas:\\d+");
        }
    }
}
