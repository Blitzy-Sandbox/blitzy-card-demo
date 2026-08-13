package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link DatasetBindings#validate()}, the startup check on the dataset catalogue.
 */
@DisplayName("DatasetBindings.validate - the dataset catalogue must be addressable as configured")
class DatasetBindingsValidationTest {
    private static DatasetBinding acctdat() {
        return new DatasetBinding("AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS", "ksds", false, "FB", null,
                300, "CVACT01Y", 11, null, null, null);
    }

    private static DatasetBinding carddat() {
        return new DatasetBinding("AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS", "ksds", false, "FB", null,
                150, "CVACT02Y", 16, null, null, null);
    }

    private static DatasetBinding cardaix() {
        return new DatasetBinding("AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH", "aix-path", false, "FB",
                null, 150, "CVACT02Y", 11, 16, "CARDDAT", "CARD-ACCT-ID");
    }

    private static DatasetBinding dalytran() {
        return new DatasetBinding("AWS.M2.CARDDEMO.DALYTRAN.PS", "sequential", false, "FB", null,
                350, "CVTRA06Y", null, null, null, null);
    }

    private static DatasetBindings catalogue(Object... nameThenBinding) {
        DatasetBindings bindings = new DatasetBindings();
        for (int i = 0; i < nameThenBinding.length; i += 2) {
            bindings.put((String) nameThenBinding[i], (DatasetBinding) nameThenBinding[i + 1]);
        }
        return bindings;
    }

    @Nested
    @DisplayName("A well-formed catalogue is accepted")
    class Accepted {
        @Test
        @DisplayName("base clusters, an alternate-index path and a sequential dataset together")
        void aWellFormedCatalogueIsAccepted() {
            assertThatNoException().isThrownBy(catalogue(
                    "ACCTDAT", acctdat(),
                    "CARDDAT", carddat(),
                    "CARDAIX", cardaix(),
                    "DALYTRAN", dalytran())::validateKeyGeometry);
        }

        @Test
        @DisplayName("an empty catalogue is accepted - emptiness is a different defect, reported elsewhere")
        void anEmptyCatalogueIsAccepted() {
            assertThatNoException().isThrownBy(new DatasetBindings()::validateKeyGeometry);
        }

        @Test
        @DisplayName("a key filling the whole record is accepted - it is tight, not wrong")
        void aKeyFillingTheWholeRecordIsAccepted() {
            DatasetBinding wholeRecordKey = new DatasetBinding("X.Y", "ksds", false, "FB", null,
                    16, "CVTRA05Y", 16, 0, null, null);

            assertThatNoException().isThrownBy(catalogue("WHOLE", wholeRecordKey)::validateKeyGeometry);
        }
    }

    @Nested
    @DisplayName("Rule 1 - a keyed entry must declare a key length")
    class KeyLengthRequired {
        @ParameterizedTest(name = "organization {0}")
        @DisplayName("a KSDS or an alternate-index path with no key length is refused")
        @CsvSource({"ksds", "aix-path"})
        void aKeyedEntryWithNoKeyLengthIsRefused(String organization) {
            DatasetBinding noKey = new DatasetBinding("X.Y", organization, false, "FB", null, 300,
                    "CVACT01Y", null, null, "BASE", "ALT-KEY");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("NOKEY", noKey)::validateKeyGeometry)
                    .withMessageContainingAll("NOKEY", "no key-length", "CVACT01Y");
        }

        @Test
        @DisplayName("the diagnostic names the copybook to transcribe the width from")
        void theDiagnosticNamesTheCopybook() {
            DatasetBinding noKey = new DatasetBinding("X.Y", "ksds", false, "FB", null, 80, "CSUSR01Y",
                    null, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("USRSEC", noKey)::validateKeyGeometry)
                    .withMessageContaining("CSUSR01Y");
        }

        @Test
        @DisplayName("keySpan() on such an entry refuses rather than guessing an offset of zero")
        void keySpanRefusesWithoutADeclaredWidth() {
            DatasetBinding noKey = new DatasetBinding("X.Y", "ksds", false, "FB", null, 300,
                    "CVACT01Y", null, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(noKey::keySpan)
                    .withMessageContaining("no key-length");
        }

        @Test
        @DisplayName("the diagnostic still reads sensibly for an entry with no copybook at all")
        void theDiagnosticReadsSensiblyWithoutACopybook() {
            DatasetBinding inlineLayout = new DatasetBinding("X.Y", "ksds", false, "FB", null, 300,
                    null, null, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("INLINE", inlineLayout)::validateKeyGeometry)
                    .withMessageContaining("the copybook defining its layout")
                    .withMessageNotContaining("in null");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(inlineLayout::keySpan)
                    .withMessageContaining("its copybook")
                    .withMessageNotContaining("in null");
        }
    }

    @Nested
    @DisplayName("Rule 2 - the key span must lie inside the record")
    class KeySpanFitsTheRecord {
        @Test
        @DisplayName("a key wider than its record is refused")
        void aKeyWiderThanItsRecordIsRefused() {
            DatasetBinding tooWide = new DatasetBinding("X.Y", "ksds", false, "FB", null, 50,
                    "CVACT03Y", 64, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("TOOWIDE", tooWide)::validateKeyGeometry)
                    .withMessageContainingAll("TOOWIDE", "offset 0", "width 64", "only 50 bytes");
        }

        @Test
        @DisplayName("a key whose OFFSET pushes it past the end is refused")
        void anOffsetPastTheEndIsRefused() {
            DatasetBinding pastEnd = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 50,
                    "CVACT03Y", 11, 45, "CCXREF", "XREF-ACCT-ID");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("PASTEND", pastEnd)::validateKeyGeometry)
                    .withMessageContainingAll("offset 45", "width 11", "ends at byte 56");
        }

        @Test
        @DisplayName("the out-of-record diagnostic also reads sensibly with no copybook")
        void theOutOfRecordDiagnosticReadsSensiblyWithoutACopybook() {
            DatasetBinding inlineLayout = new DatasetBinding("X.Y", "ksds", false, "FB", null, 20,
                    null, 32, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("INLINE", inlineLayout)::validateKeyGeometry)
                    .withMessageContaining("check the offset and width against the layout")
                    .withMessageNotContaining("against null");
        }

        @ParameterizedTest(name = "key-length {0}")
        @DisplayName("a zero or negative key length is refused")
        @CsvSource({"0", "-1"})
        void aNonPositiveKeyLengthIsRefused(int keyLength) {
            DatasetBinding bad = new DatasetBinding("X.Y", "ksds", false, "FB", null, 300, "CVACT01Y",
                    keyLength, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("BAD", bad)::validateKeyGeometry)
                    .withMessageContaining("at least one byte wide");
        }

        @Test
        @DisplayName("a negative key offset is refused")
        void aNegativeKeyOffsetIsRefused() {
            DatasetBinding bad = new DatasetBinding("X.Y", "ksds", false, "FB", null, 300, "CVACT01Y",
                    11, -1, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("BAD", bad)::validateKeyGeometry)
                    .withMessageContaining("never negative");
        }
    }

    @Nested
    @DisplayName("Rule 3 - a sequential entry must not declare a key")
    class SequentialHasNoKey {
        @Test
        @DisplayName("a sequential dataset claiming a key length is refused")
        void aSequentialDatasetClaimingAKeyIsRefused() {
            DatasetBinding contradiction = new DatasetBinding("X.Y", "sequential", false, "FB", null,
                    350, "CVTRA06Y", 16, null, null, null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("DALYTRAN", contradiction)::validateKeyGeometry)
                    .withMessageContainingAll("DALYTRAN", "sequential", "has no key");
        }

        @Test
        @DisplayName("keyed() tells the two organizations apart")
        void keyedTellsTheOrganizationsApart() {
            assertThat(acctdat().keyed()).isTrue();
            assertThat(cardaix().keyed()).isTrue();
            assertThat(dalytran().keyed()).isFalse();
        }
    }

    @Nested
    @DisplayName("Rule 4 - an alternate-index path must agree with its base")
    class PathAgreesWithBase {
        @Test
        @DisplayName("a path naming no base is refused")
        void aPathWithNoBaseIsRefused() {
            DatasetBinding orphan = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 150,
                    "CVACT02Y", 11, 16, null, "CARD-ACCT-ID");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("ORPHAN", orphan)::validateKeyGeometry)
                    .withMessageContaining("names no base");
        }

        @Test
        @DisplayName("a path naming an unconfigured base is refused, listing what IS configured")
        void aPathNamingAnUnconfiguredBaseIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("CARDAIX", cardaix())::validateKeyGeometry)
                    .withMessageContainingAll("CARDAIX", "CARDDAT", "not configured");
        }

        @Test
        @DisplayName("a path over another path is refused - a path is built over a base cluster")
        void aPathOverAnotherPathIsRefused() {
            DatasetBinding pathOverPath = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 150,
                    "CVACT02Y", 11, 16, "CARDAIX", "CARD-ACCT-ID");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("CARDDAT", carddat(), "CARDAIX", cardaix(),
                            "SECOND", pathOverPath)::validateKeyGeometry)
                    .withMessageContainingAll("SECOND", "never over another");
        }

        @Test
        @DisplayName("a path whose RECORD LENGTH differs from its base is refused")
        void aPathWithADifferentRecordLengthIsRefused() {
            DatasetBinding wrongWidth = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 300,
                    "CVACT02Y", 11, 16, "CARDDAT", "CARD-ACCT-ID");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("CARDDAT", carddat(), "CARDAIX", wrongWidth)::validateKeyGeometry)
                    .withMessageContainingAll("record-length 300", "declares 150", "SAME records");
        }

        @Test
        @DisplayName("a path whose COPYBOOK differs from its base is refused")
        void aPathWithADifferentCopybookIsRefused() {
            DatasetBinding wrongCopybook = new DatasetBinding("X.Y", "aix-path", false, "FB", null,
                    150, "CVACT03Y", 11, 16, "CARDDAT", "CARD-ACCT-ID");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("CARDDAT", carddat(), "CARDAIX", wrongCopybook)::validateKeyGeometry)
                    .withMessageContainingAll("CVACT03Y", "CVACT02Y", "same layout");
        }

        @Test
        @DisplayName("a path naming no alternate key is refused")
        void aPathWithNoAlternateKeyIsRefused() {
            DatasetBinding noAltKey = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 150,
                    "CVACT02Y", 11, 16, "CARDDAT", null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("CARDDAT", carddat(), "CARDAIX", noAltKey)::validateKeyGeometry)
                    .withMessageContaining("names no alternate-key");
        }

        @Test
        @DisplayName("a blank base or alternate key is refused, not treated as absent-but-fine")
        void aBlankBaseOrAlternateKeyIsRefused() {
            DatasetBinding blankBase = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 150,
                    "CVACT02Y", 11, 16, "   ", "CARD-ACCT-ID");
            DatasetBinding blankAltKey = new DatasetBinding("X.Y", "aix-path", false, "FB", null, 150,
                    "CVACT02Y", 11, 16, "CARDDAT", "  ");

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("A", blankBase)::validateKeyGeometry)
                    .withMessageContaining("names no base");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(catalogue("CARDDAT", carddat(), "B", blankAltKey)::validateKeyGeometry)
                    .withMessageContaining("names no alternate-key");
        }
    }

    @Nested
    @DisplayName("The key span a repository addresses")
    class KeySpanExposure {
        @Test
        @DisplayName("a primary key spans from zero; an alternate key from its declared offset")
        void theKeySpanCarriesTheDeclaredGeometry() {
            assertThat(acctdat().keySpan().offset()).isZero();
            assertThat(acctdat().keySpan().length()).isEqualTo(11);
            assertThat(cardaix().keySpan().offset()).isEqualTo(16);
            assertThat(cardaix().keySpan().length()).isEqualTo(11);
        }

        @Test
        @DisplayName("an absent offset means zero, so no entry needs an explicit zero")
        void anAbsentOffsetMeansZero() {
            assertThat(acctdat().keyOffset()).isNull();
            assertThat(acctdat().keyOffsetOrZero()).isZero();
        }
    }
}
