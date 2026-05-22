/*
 * Copyright 2022 The CardDemo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.blitzy.carddemo.adapter.file;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EbcdicTranscoder}. Validates the configuration-resolution
 * contract that determines byte-for-byte parity with COBOL z/OS output
 * (AAP §0.6.5).
 *
 * <p>These tests exercise every member listed in the file's exports schema:
 * the two constructors, the three public constants
 * ({@code DEFAULT_CHARSET_NAME}, {@code DEFAULT_KEY}, {@code DATASET_PREFIX}),
 * and the three instance methods ({@code charsetFor}, {@code defaultCharset},
 * {@code isEbcdic}).
 *
 * <p>Equality assertions on {@link Charset} use {@link Charset#equals(Object)}
 * (object identity, which uses canonical name comparison internally) rather
 * than comparing {@code .name()} strings — different JDK distributions
 * canonicalise aliases differently (e.g., Temurin canonicalises
 * {@code IBM-1047} → {@code IBM1047}), and the tests must be portable.
 */
class EbcdicTranscoderTest {

    /** Pre-resolved IBM-1047 charset for portable equality assertions. */
    private static final Charset IBM_1047 = Charset.forName("IBM-1047");

    /** Pre-resolved IBM-037 charset for portable equality assertions. */
    private static final Charset IBM_037 = Charset.forName("IBM-037");

    @Nested
    @DisplayName("Exported constants")
    class Constants {

        @Test
        @DisplayName("DEFAULT_CHARSET_NAME is the canonical z/OS EBCDIC code page IBM-1047")
        void defaultCharsetNameIsIbm1047() {
            assertThat(EbcdicTranscoder.DEFAULT_CHARSET_NAME).isEqualTo("IBM-1047");
        }

        @Test
        @DisplayName("DEFAULT_KEY follows the carddemo.file.default.charset convention")
        void defaultKeyIsCorrect() {
            assertThat(EbcdicTranscoder.DEFAULT_KEY).isEqualTo("carddemo.file.default.charset");
        }

        @Test
        @DisplayName("DATASET_PREFIX follows the carddemo.file. convention")
        void datasetPrefixIsCorrect() {
            assertThat(EbcdicTranscoder.DATASET_PREFIX).isEqualTo("carddemo.file.");
        }

        @Test
        @DisplayName("IBM-1047 is supported by the running JVM (precondition for AAP §0.6.5 parity)")
        void ibm1047IsResolvable() {
            // Sanity check: if this fails, byte-for-byte parity with COBOL is impossible
            // on this JDK distribution.
            assertThat(Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME)).isNotNull();
        }
    }

    @Nested
    @DisplayName("No-arg constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("falls back to IBM-1047 as the default charset")
        void defaultsToIbm1047() {
            EbcdicTranscoder t = new EbcdicTranscoder();
            assertThat(t.defaultCharset()).isEqualTo(IBM_1047);
        }

        @Test
        @DisplayName("returns the IBM-1047 default for every dataset name")
        void everyDatasetResolvesToDefault() {
            EbcdicTranscoder t = new EbcdicTranscoder();
            assertThat(t.charsetFor("acctdata")).isEqualTo(IBM_1047);
            assertThat(t.charsetFor("carddata")).isEqualTo(IBM_1047);
            assertThat(t.charsetFor("custdata")).isEqualTo(IBM_1047);
            assertThat(t.charsetFor("trantype")).isEqualTo(IBM_1047);
        }
    }

    @Nested
    @DisplayName("Constructor(Properties)")
    class PropertiesConstructor {

        @Test
        @DisplayName("rejects null Properties with NullPointerException")
        void rejectsNullProperties() {
            assertThatThrownBy(() -> new EbcdicTranscoder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("properties");
        }

        @Test
        @DisplayName("accepts an empty Properties (defaults to IBM-1047)")
        void acceptsEmptyProperties() {
            EbcdicTranscoder t = new EbcdicTranscoder(new Properties());
            assertThat(t.defaultCharset()).isEqualTo(IBM_1047);
        }

        @Test
        @DisplayName("honours the carddemo.file.default.charset override")
        void honoursDefaultOverride() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "US-ASCII");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.defaultCharset()).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("rejects an unsupported default charset name with UnsupportedCharsetException")
        void rejectsUnsupportedDefault() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "NOT-A-REAL-CHARSET");
            assertThatExceptionOfType(UnsupportedCharsetException.class)
                .isThrownBy(() -> new EbcdicTranscoder(props))
                .withMessageContaining("NOT-A-REAL-CHARSET")
                .withMessageContaining("default");
        }

        @Test
        @DisplayName("rejects a syntactically invalid default charset name with UnsupportedCharsetException")
        void rejectsSyntacticallyInvalidDefault() {
            // The space and slash characters are not allowed in canonical charset names;
            // Charset.forName throws IllegalCharsetNameException, which the transcoder
            // normalises to UnsupportedCharsetException with a structured message.
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "bad name with spaces");
            assertThatExceptionOfType(UnsupportedCharsetException.class)
                .isThrownBy(() -> new EbcdicTranscoder(props))
                .withMessageContaining("bad name with spaces")
                .withMessageContaining("default");
        }

        @Test
        @DisplayName("preserves the original cause for unsupported default")
        void preservesOriginalCauseForUnsupported() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "TOTALLY-FAKE-1234");
            try {
                new EbcdicTranscoder(props);
                throw new AssertionError("Expected UnsupportedCharsetException");
            } catch (UnsupportedCharsetException e) {
                assertThat(e.getCause()).isNotNull();
                // The cause is itself an UnsupportedCharsetException from Charset.forName.
                assertThat(e.getCause()).isInstanceOf(UnsupportedCharsetException.class);
            }
        }

        @Test
        @DisplayName("preserves the original cause for syntactically invalid default")
        void preservesOriginalCauseForInvalid() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "bad name");
            try {
                new EbcdicTranscoder(props);
                throw new AssertionError("Expected UnsupportedCharsetException");
            } catch (UnsupportedCharsetException e) {
                assertThat(e.getCause()).isNotNull();
                assertThat(e.getCause().getClass().getSimpleName())
                    .isEqualTo("IllegalCharsetNameException");
            }
        }
    }

    @Nested
    @DisplayName("charsetFor(dataset)")
    class CharsetFor {

        @Test
        @DisplayName("returns the configured per-file override")
        void returnsPerFileOverride() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "US-ASCII");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.charsetFor("acctdata")).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("falls back to default when no per-file override is present")
        void fallsBackToDefault() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "US-ASCII");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            // carddata has no override -> default IBM-1047
            assertThat(t.charsetFor("carddata")).isEqualTo(IBM_1047);
        }

        @Test
        @DisplayName("falls back to default when per-file override is blank")
        void blankOverrideFallsBackToDefault() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "   ");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.charsetFor("acctdata")).isEqualTo(IBM_1047);
        }

        @Test
        @DisplayName("falls back to default when per-file override is empty")
        void emptyOverrideFallsBackToDefault() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.charsetFor("acctdata")).isEqualTo(IBM_1047);
        }

        @Test
        @DisplayName("respects default override AND per-file override interactions")
        void mixedOverrides() {
            Properties props = new Properties();
            // Default is ASCII, but acctdata is configured as EBCDIC IBM-037.
            props.setProperty("carddemo.file.default.charset", "US-ASCII");
            props.setProperty("carddemo.file.acctdata.charset", "IBM-037");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.defaultCharset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(t.charsetFor("acctdata")).isEqualTo(IBM_037);
            assertThat(t.charsetFor("carddata")).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("normalises dataset name case via Locale.ROOT")
        void caseInsensitiveLookup() {
            Properties props = new Properties();
            // Configuration key uses lowercase 'acctdata' as documented; the API caller
            // may pass any casing.
            props.setProperty("carddemo.file.acctdata.charset", "US-ASCII");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.charsetFor("ACCTDATA")).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(t.charsetFor("AcctData")).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(t.charsetFor("acctdata")).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(t.charsetFor("aCcTdAtA")).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("rejects null dataset with IllegalArgumentException")
        void rejectsNullDataset() {
            EbcdicTranscoder t = new EbcdicTranscoder();
            assertThatThrownBy(() -> t.charsetFor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-null")
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("rejects blank dataset with IllegalArgumentException")
        void rejectsBlankDataset() {
            EbcdicTranscoder t = new EbcdicTranscoder();
            assertThatThrownBy(() -> t.charsetFor("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        }

        @Test
        @DisplayName("rejects empty dataset with IllegalArgumentException")
        void rejectsEmptyDataset() {
            EbcdicTranscoder t = new EbcdicTranscoder();
            assertThatThrownBy(() -> t.charsetFor(""))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects an unsupported per-file override with context-augmented message")
        void rejectsUnsupportedPerFileOverride() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "NOT-A-REAL-CHARSET");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThatExceptionOfType(UnsupportedCharsetException.class)
                .isThrownBy(() -> t.charsetFor("acctdata"))
                // Message must identify WHICH dataset is misconfigured (AAP §0.6.5 diagnostics).
                .withMessageContaining("acctdata")
                .withMessageContaining("NOT-A-REAL-CHARSET");
        }
    }

    @Nested
    @DisplayName("defaultCharset()")
    class DefaultCharset {

        @Test
        @DisplayName("returns IBM-1047 when no override")
        void noOverrideReturnsIbm1047() {
            assertThat(new EbcdicTranscoder().defaultCharset()).isEqualTo(IBM_1047);
        }

        @Test
        @DisplayName("returns the configured override")
        void returnsConfiguredOverride() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "UTF-8");
            EbcdicTranscoder t = new EbcdicTranscoder(props);
            assertThat(t.defaultCharset()).isEqualTo(StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("isEbcdic(dataset)")
    class IsEbcdic {

        @Test
        @DisplayName("returns true for the IBM-1047 default")
        void ibm1047IsEbcdic() {
            assertThat(new EbcdicTranscoder().isEbcdic("acctdata")).isTrue();
        }

        @Test
        @DisplayName("returns true for IBM-037 override")
        void ibm037IsEbcdic() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "IBM-037");
            assertThat(new EbcdicTranscoder(props).isEbcdic("acctdata")).isTrue();
        }

        @Test
        @DisplayName("returns true for Cp1047 alias regardless of canonical-name canonicalisation")
        void cp1047IsEbcdic() {
            // Cp1047 is the JDK historical alias for IBM Code Page 1047. Different JDK
            // distributions canonicalise this alias differently:
            //   - Temurin 25 canonicalises Cp1047 -> "IBM1047" (no dash)
            //   - Other JDKs may preserve "Cp1047" or use "IBM-1047" (with dash)
            // The isEbcdic prefix check accepts both "IBM" (no dash) and "Cp", which
            // together cover every observed JDK canonicalisation behaviour.
            Properties props = new Properties();
            props.setProperty("carddemo.file.acctdata.charset", "Cp1047");
            assertThat(new EbcdicTranscoder(props).isEbcdic("acctdata")).isTrue();
        }

        @Test
        @DisplayName("returns false for US-ASCII")
        void asciiIsNotEbcdic() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "US-ASCII");
            assertThat(new EbcdicTranscoder(props).isEbcdic("acctdata")).isFalse();
        }

        @Test
        @DisplayName("returns false for UTF-8")
        void utf8IsNotEbcdic() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "UTF-8");
            assertThat(new EbcdicTranscoder(props).isEbcdic("acctdata")).isFalse();
        }

        @Test
        @DisplayName("returns false for ISO-8859-1")
        void latin1IsNotEbcdic() {
            Properties props = new Properties();
            props.setProperty("carddemo.file.default.charset", "ISO-8859-1");
            assertThat(new EbcdicTranscoder(props).isEbcdic("acctdata")).isFalse();
        }

        @Test
        @DisplayName("propagates IllegalArgumentException from charsetFor for null/blank dataset")
        void propagatesIllegalArgumentForNullDataset() {
            EbcdicTranscoder t = new EbcdicTranscoder();
            assertThatThrownBy(() -> t.isEbcdic(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> t.isEbcdic(""))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Documented configuration convention")
    class ConfigurationConvention {

        @Test
        @DisplayName("composes the correct key from DATASET_PREFIX + dataset + .charset")
        void composedKeyFollowsConvention() {
            String key = EbcdicTranscoder.DATASET_PREFIX + "acctdata.charset";
            assertThat(key).isEqualTo("carddemo.file.acctdata.charset");
        }

        @Test
        @DisplayName("DEFAULT_KEY is derivable from DATASET_PREFIX + default + .charset")
        void defaultKeyDerivable() {
            String key = EbcdicTranscoder.DATASET_PREFIX + "default.charset";
            assertThat(key).isEqualTo(EbcdicTranscoder.DEFAULT_KEY);
        }
    }
}
