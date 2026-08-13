package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.SystemMessages.AbendData;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link SystemMessages}, the byte-exact message literals of {@code app/cpy/CSMSG01Y.cpy} and the
 * {@code ABEND-DATA} structure of {@code app/cpy/CSMSG02Y.cpy}.
 */
@DisplayName("SystemMessages - CSMSG01Y message literals and the CSMSG02Y ABEND-DATA record")
class SystemMessagesContractTest {
    @Nested
    @DisplayName("Message literals and declared widths")
    class Literals {
        @Test
        @DisplayName("the message field is PIC X(50)")
        void messageLengthIsFifty() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("the thank-you message is byte-exact and exactly 50 characters wide")
        void thankYouMessage() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .startsWith("Thank you for using CardDemo application...")
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the invalid-key message is byte-exact and exactly 50 characters wide")
        void invalidKeyMessage() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .startsWith("Invalid key pressed. Please see below...")
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("the two messages are distinct")
        void messagesAreDistinct() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the ABEND-DATA sub-field widths match CSMSG02Y and sum to the whole")
        void abendDataWidths() {
            assertThat(SystemMessages.ABEND_CODE_LENGTH).isEqualTo(4);
            assertThat(SystemMessages.ABEND_CULPRIT_LENGTH).isEqualTo(8);
            assertThat(SystemMessages.ABEND_REASON_LENGTH).isEqualTo(50);
            assertThat(SystemMessages.ABEND_MSG_LENGTH).isEqualTo(72);
            assertThat(SystemMessages.ABEND_DATA_LENGTH).isEqualTo(4 + 8 + 50 + 72).isEqualTo(134);
        }
    }

    @Nested
    @DisplayName("AbendData - the CSMSG02Y record, where absence is spaces and never null")
    class AbendDataRecord {
        @Test
        @DisplayName("spaces() yields every field space-filled at its declared width")
        void spacesFactoryFillsDeclaredWidths() {
            AbendData data = AbendData.spaces();
            assertThat(data.abendCode()).isEqualTo("    ").hasSize(4);
            assertThat(data.abendCulprit()).isBlank().hasSize(8);
            assertThat(data.abendReason()).isBlank().hasSize(50);
            assertThat(data.abendMsg()).isBlank().hasSize(72);
        }

        @Test
        @DisplayName("a fully populated record keeps the values it was given")
        void canonicalConstructorKeepsValues() {
            AbendData data = new AbendData("0999", "CBACT01C", "read error", "detail");
            assertThat(data.abendCode()).isEqualTo("0999");
            assertThat(data.abendCulprit()).isEqualTo("CBACT01C");
            assertThat(data.abendReason()).isEqualTo("read error");
            assertThat(data.abendMsg()).isEqualTo("detail");
        }

        @ParameterizedTest(name = "a null {0} is rejected")
        @CsvSource({"abendCode", "abendCulprit", "abendReason", "abendMsg"})
        @DisplayName("every field rejects null, because CSMSG02Y declares VALUE SPACES")
        void everyFieldRejectsNull(String fieldName) {
            String code = "abendCode".equals(fieldName) ? null : "0999";
            String culprit = "abendCulprit".equals(fieldName) ? null : "CBACT01C";
            String reason = "abendReason".equals(fieldName) ? null : "reason";
            String message = "abendMsg".equals(fieldName) ? null : "message";
            assertThatNullPointerException()
                    .isThrownBy(() -> new AbendData(code, culprit, reason, message))
                    .withMessageContaining(fieldName);
        }

        @Test
        @DisplayName("toDeclaredWidths() pads every short field out to its PICTURE width")
        void toDeclaredWidthsPadsShortValues() {
            AbendData padded =
                    new AbendData("99", "CB", "why", "msg").toDeclaredWidths();
            assertThat(padded.abendCode()).isEqualTo("99  ").hasSize(4);
            assertThat(padded.abendCulprit()).isEqualTo("CB      ").hasSize(8);
            assertThat(padded.abendReason()).hasSize(50).startsWith("why ");
            assertThat(padded.abendMsg()).hasSize(72).startsWith("msg ");
        }

        @Test
        @DisplayName("toDeclaredWidths() truncates every over-long field on the right")
        void toDeclaredWidthsTruncatesLongValues() {
            AbendData truncated = new AbendData("123456", "CBACT01CXX", "r".repeat(60),
                    "m".repeat(80)).toDeclaredWidths();
            assertThat(truncated.abendCode()).isEqualTo("1234");
            assertThat(truncated.abendCulprit()).isEqualTo("CBACT01C");
            assertThat(truncated.abendReason()).isEqualTo("r".repeat(50));
            assertThat(truncated.abendMsg()).isEqualTo("m".repeat(72));
        }

        @Test
        @DisplayName("toDeclaredWidths() leaves an exactly-sized field untouched")
        void toDeclaredWidthsLeavesExactValuesAlone() {
            AbendData exact = new AbendData("0999", "CBACT01C", "r".repeat(50), "m".repeat(72));
            assertThat(exact.toDeclaredWidths()).isEqualTo(exact);
        }

        @Test
        @DisplayName("withAbendCode() replaces only that field, at its declared width")
        void withAbendCode() {
            AbendData updated = AbendData.spaces().withAbendCode("12");
            assertThat(updated.abendCode()).isEqualTo("12  ").hasSize(4);
            assertThat(updated.abendCulprit()).hasSize(8).isBlank();
        }

        @Test
        @DisplayName("withAbendCulprit() replaces only that field, at its declared width")
        void withAbendCulprit() {
            AbendData updated = AbendData.spaces().withAbendCulprit("CBTRN02C");
            assertThat(updated.abendCulprit()).isEqualTo("CBTRN02C").hasSize(8);
            assertThat(updated.abendCode()).hasSize(4);
        }

        @Test
        @DisplayName("withAbendReason() replaces only that field, at its declared width")
        void withAbendReason() {
            AbendData updated = AbendData.spaces().withAbendReason("bad key");
            assertThat(updated.abendReason()).hasSize(50).startsWith("bad key");
            assertThat(updated.abendMsg()).hasSize(72);
        }

        @Test
        @DisplayName("withAbendMsg() replaces only that field, at its declared width")
        void withAbendMsg() {
            AbendData updated = AbendData.spaces().withAbendMsg("boom");
            assertThat(updated.abendMsg()).hasSize(72).startsWith("boom");
            assertThat(updated.abendReason()).hasSize(50);
        }

        @ParameterizedTest(name = "with{0}(null) is rejected")
        @CsvSource({"AbendCode", "AbendCulprit", "AbendReason", "AbendMsg"})
        @DisplayName("every wither rejects null through the shared PIC X guard")
        void withersRejectNull(String which) {
            AbendData base = AbendData.spaces();
            assertThatNullPointerException().isThrownBy(() -> {
                switch (which) {
                    case "AbendCode" -> base.withAbendCode(null);
                    case "AbendCulprit" -> base.withAbendCulprit(null);
                    case "AbendReason" -> base.withAbendReason(null);
                    default -> base.withAbendMsg(null);
                }
            });
        }

        @Test
        @DisplayName("an empty string pads to full width rather than being rejected")
        void emptyStringPadsToWidth() {
            assertThat(AbendData.spaces().withAbendCode("").abendCode()).isEqualTo("    ");
        }

        @Test
        @DisplayName("the record's value semantics hold, so records compare by field")
        void valueSemantics() {
            AbendData first = new AbendData("0999", "CBACT01C", "r", "m");
            AbendData second = new AbendData("0999", "CBACT01C", "r", "m");
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.toString()).contains("CBACT01C");
        }
    }

    @Nested
    @DisplayName("Class shape")
    class ClassShape {
        @Test
        @DisplayName("the holder is not instantiable")
        void notInstantiable() throws ReflectiveOperationException {
            Constructor<SystemMessages> constructor = SystemMessages.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThat(constructor.canAccess(null)).isTrue();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> constructor.newInstance("unexpected"));
            assertThat(constructor.newInstance()).isInstanceOf(SystemMessages.class);
        }
    }

    @Nested
    @DisplayName("Guard coverage for the shared PIC X helper")
    class SharedGuard {
        @Test
        @DisplayName("all three PIC X paths - exact, short and long - are reachable per field")
        void allThreePicXPathsAreReachable() throws InvocationTargetException {
            assertThat(AbendData.spaces().withAbendCode("1234").abendCode()).isEqualTo("1234");
            assertThat(AbendData.spaces().withAbendCode("1").abendCode()).isEqualTo("1   ");
            assertThat(AbendData.spaces().withAbendCode("123456").abendCode()).isEqualTo("1234");
        }
    }
}
