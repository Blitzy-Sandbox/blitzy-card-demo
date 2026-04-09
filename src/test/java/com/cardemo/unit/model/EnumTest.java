package com.cardemo.unit.model;

import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.model.enums.UserType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive unit tests for all 4 enum classes in the CardDemo model layer.
 * Exercises every enum value, factory methods (fromCode), utility booleans,
 * toString, and error handling for invalid codes.
 */
class EnumTest {

    // ══════════════════════════════════════════════════════════════════════
    // FileStatus — COBOL FILE STATUS code mappings
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FileStatus enum")
    class FileStatusTests {

        @Test
        @DisplayName("All enum values exist")
        void testAllValues() {
            FileStatus[] values = FileStatus.values();
            assertThat(values.length).isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("fromCode returns correct enum for known codes")
        void testFromCode() {
            FileStatus success = FileStatus.fromCode("00");
            assertThat(success.getCode()).isEqualTo("00");
            assertThat(success.getDescription()).isNotBlank();
            assertThat(success.isSuccess()).isTrue();
            assertThat(success.isError()).isFalse();

            FileStatus eof = FileStatus.fromCode("10");
            assertThat(eof.isEndOfFile()).isTrue();

            FileStatus notFound = FileStatus.fromCode("23");
            assertThat(notFound.isError()).isTrue();
            assertThat(notFound.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("fromCode throws for unknown code")
        void testFromCodeUnknown() {
            assertThatThrownBy(() -> FileStatus.fromCode("ZZ"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("toString returns the code")
        void testToString() {
            FileStatus success = FileStatus.fromCode("00");
            assertThat(success.toString()).isEqualTo("00");
        }

        @Test
        @DisplayName("Additional FILE STATUS codes are mapped")
        void testAdditionalCodes() {
            // FILE STATUS 22 = DUPKEY
            FileStatus dupkey = FileStatus.fromCode("22");
            assertThat(dupkey.isError()).isTrue();

            // FILE STATUS 35 = file not found
            FileStatus fileNotFound = FileStatus.fromCode("35");
            assertThat(fileNotFound.isError()).isTrue();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // RejectCode — batch validation codes 100-109
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RejectCode enum")
    class RejectCodeTests {

        @Test
        @DisplayName("All enum values exist")
        void testAllValues() {
            RejectCode[] values = RejectCode.values();
            assertThat(values.length).isGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("fromCode returns correct enum for known codes")
        void testFromCode() {
            RejectCode invalidAccount = RejectCode.fromCode(100);
            assertThat(invalidAccount.getCode()).isEqualTo(100);
            assertThat(invalidAccount.getDescription()).isNotBlank();

            RejectCode invalidCard = RejectCode.fromCode(101);
            assertThat(invalidCard.getCode()).isEqualTo(101);

            RejectCode creditLimit = RejectCode.fromCode(102);
            assertThat(creditLimit.getCode()).isEqualTo(102);

            RejectCode expiredCard = RejectCode.fromCode(103);
            assertThat(expiredCard.getCode()).isEqualTo(103);
        }

        @Test
        @DisplayName("fromCode throws for unknown code")
        void testFromCodeUnknown() {
            assertThatThrownBy(() -> RejectCode.fromCode(999))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("toString contains meaningful information")
        void testToString() {
            RejectCode code = RejectCode.fromCode(100);
            assertThat(code.toString()).contains("100");
        }

        @Test
        @DisplayName("All reject codes in range 100-109")
        void testCodeRange() {
            for (RejectCode rc : RejectCode.values()) {
                assertThat(rc.getCode()).isBetween(100, 109);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TransactionSource — transaction origination sources
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TransactionSource enum")
    class TransactionSourceTests {

        @Test
        @DisplayName("All enum values exist")
        void testAllValues() {
            TransactionSource[] values = TransactionSource.values();
            assertThat(values.length).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("fromCode returns correct enum for known codes")
        void testFromCode() {
            TransactionSource pos = TransactionSource.fromCode("POS TERM");
            assertThat(pos.getCode()).isEqualTo("POS TERM");

            TransactionSource op = TransactionSource.fromCode("OPERATOR");
            assertThat(op.getCode()).isEqualTo("OPERATOR");
        }

        @Test
        @DisplayName("fromCode trims whitespace per COBOL convention")
        void testFromCodeTrimming() {
            TransactionSource pos = TransactionSource.fromCode("POS TERM  ");
            assertThat(pos.getCode()).isEqualTo("POS TERM");
        }

        @Test
        @DisplayName("fromCode throws for unknown source")
        void testFromCodeUnknown() {
            assertThatThrownBy(() -> TransactionSource.fromCode("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("toString returns the code")
        void testToString() {
            TransactionSource pos = TransactionSource.fromCode("POS TERM");
            assertThat(pos.toString()).isEqualTo("POS TERM");
        }

        @Test
        @DisplayName("toCobolFormat pads to 10 characters")
        void testToCobolFormat() {
            TransactionSource op = TransactionSource.fromCode("OPERATOR");
            String cobol = op.toCobolFormat();
            assertThat(cobol).hasSize(10);
            assertThat(cobol).startsWith("OPERATOR");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UserType — ADMIN('A') / USER('U') role enum
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UserType enum")
    class UserTypeTests {

        @Test
        @DisplayName("ADMIN has code 'A'")
        void testAdminCode() {
            assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
        }

        @Test
        @DisplayName("USER has code 'U'")
        void testUserCode() {
            assertThat(UserType.USER.getCode()).isEqualTo("U");
        }

        @Test
        @DisplayName("fromCode for 'A' returns ADMIN")
        void testFromCodeAdmin() {
            assertThat(UserType.fromCode("A")).isEqualTo(UserType.ADMIN);
        }

        @Test
        @DisplayName("fromCode for 'U' returns USER")
        void testFromCodeUser() {
            assertThat(UserType.fromCode("U")).isEqualTo(UserType.USER);
        }

        @Test
        @DisplayName("fromCode throws for unknown code")
        void testFromCodeUnknown() {
            assertThatThrownBy(() -> UserType.fromCode("X"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("toString returns the code")
        void testToString() {
            assertThat(UserType.ADMIN.toString()).isEqualTo("A");
            assertThat(UserType.USER.toString()).isEqualTo("U");
        }

        @Test
        @DisplayName("values() returns exactly 2 values")
        void testValues() {
            assertThat(UserType.values()).hasSize(2);
        }
    }
}
