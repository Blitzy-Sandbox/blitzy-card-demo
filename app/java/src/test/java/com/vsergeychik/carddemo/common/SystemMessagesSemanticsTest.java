package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.SystemMessages.AbendData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SystemMessages}, which merges {@code app/cpy/CSMSG01Y.cpy}'s two message literals with
 * {@code app/cpy/CSMSG02Y.cpy}'s {@code ABEND-DATA} work area.
 */
@DisplayName("SystemMessages - CSMSG01Y message literals and the CSMSG02Y ABEND-DATA area")
class SystemMessagesSemanticsTest {
    @Nested
    @DisplayName("CSMSG01Y - the two PIC X(50) messages")
    class Messages {
        @Test
        @DisplayName("both messages are 50 bytes, not the 49 the source literal shows")
        void bothMessagesAreFiftyBytes() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
        }

        @Test
        @DisplayName("the content matches the copybook and keeps its trailing pad")
        void theContentMatchesTheCopybook() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.strip())
                    .isEqualTo("Thank you for using CardDemo application...");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.strip())
                    .isEqualTo("Invalid key pressed. Please see below...");

            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).endsWith(" ").doesNotEndWith(".");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).endsWith(" ");
        }

        @Test
        @DisplayName("this thank-you says CardDemo, never CCDA application")
        void theThankYouIsTheCardDemoOneNotTheScreenTitlesOne() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).doesNotContain("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSizeGreaterThan(40);
        }
    }

    @Nested
    @DisplayName("CSMSG02Y ABEND-DATA - 4 + 8 + 50 + 72 = 134 bytes, all VALUE SPACES")
    class AbendDataArea {
        @Test
        @DisplayName("the declared widths sum to 134")
        void theWidthsSumToOneHundredAndThirtyFour() {
            assertThat(SystemMessages.ABEND_CODE_LENGTH).isEqualTo(4);
            assertThat(SystemMessages.ABEND_CULPRIT_LENGTH).isEqualTo(8);
            assertThat(SystemMessages.ABEND_REASON_LENGTH).isEqualTo(50);
            assertThat(SystemMessages.ABEND_MSG_LENGTH).isEqualTo(72);
            assertThat(SystemMessages.ABEND_DATA_LENGTH).isEqualTo(134);
        }

        @Test
        @DisplayName("VALUE SPACES means a run of spaces of the declared width, never empty or null")
        void theDefaultIsSpacesOfTheDeclaredWidth() {
            AbendData defaults = AbendData.spaces();

            assertThat(defaults.abendCode()).hasSize(4).isBlank().isNotEmpty();
            assertThat(defaults.abendCulprit()).hasSize(8).isBlank().isNotEmpty();
            assertThat(defaults.abendReason()).hasSize(50).isBlank().isNotEmpty();
            assertThat(defaults.abendMsg()).hasSize(72).isBlank().isNotEmpty();

            int total = defaults.abendCode().length() + defaults.abendCulprit().length()
                    + defaults.abendReason().length() + defaults.abendMsg().length();
            assertThat(total).isEqualTo(SystemMessages.ABEND_DATA_LENGTH);
        }

        @Test
        @DisplayName("every component is required, because the absent value is spaces and not null")
        void everyComponentIsRequired() {
            String four = "    ";
            String eight = "        ";
            String fifty = " ".repeat(50);
            String seventyTwo = " ".repeat(72);

            assertThatNullPointerException()
                    .isThrownBy(() -> new AbendData(null, eight, fifty, seventyTwo))
                    .withMessageContaining("ABEND-CODE");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AbendData(four, null, fifty, seventyTwo))
                    .withMessageContaining("ABEND-CULPRIT");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AbendData(four, eight, null, seventyTwo))
                    .withMessageContaining("ABEND-REASON");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AbendData(four, eight, fifty, null))
                    .withMessageContaining("ABEND-MSG");
        }

        @Test
        @DisplayName("PIC X pads and truncates on the right, and an exact width is left alone")
        void picXPadsAndTruncatesOnTheRight() {
            AbendData shortened = AbendData.spaces().withAbendCode("12");
            assertThat(shortened.abendCode()).isEqualTo("12  ").hasSize(4);

            AbendData exact = AbendData.spaces().withAbendCode("0999");
            assertThat(exact.abendCode()).isEqualTo("0999").hasSize(4);

            AbendData truncated = AbendData.spaces().withAbendCode("099999");
            assertThat(truncated.abendCode()).isEqualTo("0999").hasSize(4);
        }

        @Test
        @DisplayName("each wither targets exactly its own component")
        void eachWitherTargetsItsOwnComponent() {
            AbendData populated = AbendData.spaces()
                    .withAbendCode("0999")
                    .withAbendCulprit("CBACT04C")
                    .withAbendReason("TCATBALF read failed")
                    .withAbendMsg("ABENDING PROGRAM");

            assertThat(populated.abendCode()).isEqualTo("0999");
            assertThat(populated.abendCulprit()).isEqualTo("CBACT04C");
            assertThat(populated.abendReason()).startsWith("TCATBALF read failed").hasSize(50);
            assertThat(populated.abendMsg()).startsWith("ABENDING PROGRAM").hasSize(72);
        }

        @Test
        @DisplayName("a wither rejects null, matching the constructor's contract")
        void aWitherRejectsNull() {
            AbendData defaults = AbendData.spaces();

            assertThatNullPointerException().isThrownBy(() -> defaults.withAbendCode(null));
            assertThatNullPointerException().isThrownBy(() -> defaults.withAbendCulprit(null));
            assertThatNullPointerException().isThrownBy(() -> defaults.withAbendReason(null));
            assertThatNullPointerException().isThrownBy(() -> defaults.withAbendMsg(null));
        }

        @Test
        @DisplayName("toDeclaredWidths() normalises every component at once")
        void toDeclaredWidthsNormalisesEveryComponent() {
            AbendData ragged = new AbendData("9", "CB", "reason", "message");
            AbendData normalised = ragged.toDeclaredWidths();

            assertThat(normalised.abendCode()).hasSize(4);
            assertThat(normalised.abendCulprit()).hasSize(8);
            assertThat(normalised.abendReason()).hasSize(50);
            assertThat(normalised.abendMsg()).hasSize(72);
            assertThat(normalised.toDeclaredWidths()).isEqualTo(normalised);
        }

        @Test
        @DisplayName("the area is an immutable value: equal inputs give equal instances")
        void theAreaIsAnImmutableValue() {
            assertThat(AbendData.spaces()).isEqualTo(AbendData.spaces())
                    .hasSameHashCodeAs(AbendData.spaces());
            assertThat(AbendData.spaces().withAbendCode("0999")).isNotEqualTo(AbendData.spaces());
        }
    }
}
