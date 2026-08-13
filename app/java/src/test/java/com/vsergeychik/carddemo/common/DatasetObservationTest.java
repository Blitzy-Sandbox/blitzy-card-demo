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
 * Unit tests for {@link DatasetObservation}, the labelled quantity that keeps a measurement out of a CICS
 * reason code.
 */
@DisplayName("DatasetObservation - a measurement, under the name of what was measured")
class DatasetObservationTest {
    @Nested
    @DisplayName("The named quantities")
    class NamedQuantities {
        @Test
        @DisplayName("a record width says it is a width, and in bytes")
        void aRecordWidthSaysSo() {
            DatasetObservation width = DatasetObservation.recordWidth(149);

            assertThat(width.value()).isEqualTo(149);
            assertThat(width.label()).isEqualTo("stored record width in bytes");
            assertThat(width.describe()).isEqualTo("stored record width in bytes = 149");
        }

        @Test
        @DisplayName("a selected-row count says which count it is")
        void aSelectedRowCountSaysWhichCountItIs() {
            assertThat(DatasetObservation.matchingRows(2).describe())
                    .isEqualTo("rows selected by the key = 2");
            assertThat(DatasetObservation.replacedRows(0).describe())
                    .isEqualTo("rows replaced by the write = 0");
        }

        @Test
        @DisplayName("zero is a measurement like any other: no row selected is exactly what it says")
        void zeroIsAMeasurement() {
            assertThat(DatasetObservation.matchingRows(0).value()).isZero();
            assertThat(DatasetObservation.recordWidth(0).describe())
                    .isEqualTo("stored record width in bytes = 0");
        }
    }

    @Nested
    @DisplayName("The guards")
    class Guards {
        @ParameterizedTest(name = "a value of {0} is refused")
        @ValueSource(longs = { -1L, -150L, Long.MIN_VALUE })
        @DisplayName("a negative value is refused, because a width and a count are not negative")
        void aNegativeValueIsRefused(long value) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatasetObservation("a count", value))
                    .withMessageContaining("non-negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DatasetObservation.recordWidth(value));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DatasetObservation.matchingRows(value));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DatasetObservation.replacedRows(value));
        }

        @Test
        @DisplayName("an absent or blank label is refused: an unlabelled number is the thing to prevent")
        void anAbsentOrBlankLabelIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new DatasetObservation(null, 1))
                    .withMessageContaining("named by what it measured");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatasetObservation("   ", 1))
                    .withMessageContaining("must say what was measured");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DatasetObservation("", 1))
                    .withMessageContaining("must say what was measured");
        }
    }

    @Nested
    @DisplayName("The rendering cannot forge a log line")
    class Rendering {
        @Test
        @DisplayName("a control character in a label is escaped, so one entry cannot become two")
        void aControlCharacterInALabelIsEscaped() {
            DatasetObservation forged =
                    new DatasetObservation("width\r\nFATAL: transfer approved", 1);

            assertThat(forged.label()).doesNotContain("\r").doesNotContain("\n");
            assertThat(forged.describe()).doesNotContain("\r").doesNotContain("\n");
            assertThat(forged.describe()).endsWith(" = 1");
        }

        @Test
        @DisplayName("a tab and a delete character are escaped too")
        void otherControlCharactersAreEscapedToo() {
            assertThat(new DatasetObservation("a\tb\u007fc", 7).label())
                    .doesNotContain("\t")
                    .doesNotContain("\u007f");
        }
    }
}
