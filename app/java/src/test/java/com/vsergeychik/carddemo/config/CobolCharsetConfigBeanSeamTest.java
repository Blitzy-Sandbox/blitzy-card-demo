package com.vsergeychik.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CobolCharsetConfig}, the one place in the module where a character encoding is
 * named.
 *
 * <p>Everything here runs without an application context. The class takes its three code-page names
 * as constructor arguments, so a plain unit test can drive every published bean and the single
 * decision behind them - which is exactly the property that makes the branch-coverage gate reachable
 * deterministically.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file.
 */
@DisplayName("CobolCharsetConfig - the single seam that names a code page")
class CobolCharsetConfigBeanSeamTest {

    /** The EBCDIC code page {@code application.yml} declares. */
    private static final String EBCDIC = "IBM037";

    /** The ASCII code page {@code application.yml} declares. */
    private static final String ASCII = "US-ASCII";

    @Nested
    @DisplayName("The three published beans")
    class PublishedBeans {

        @Test
        @DisplayName("Each bean resolves the code page its own property supplied")
        void eachBeanResolvesItsOwnProperty() {
            CobolCharsetConfig config = new CobolCharsetConfig(EBCDIC, ASCII, ASCII);

            assertThat(config.carddemoEbcdicCharset()).isEqualTo(Charset.forName(EBCDIC));
            assertThat(config.carddemoAsciiCharset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(config.carddemoDatasetCharset()).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("The dataset bean is independently settable, so a site can read EBCDIC datasets")
        void theDatasetBeanIsIndependentlySettable() {
            CobolCharsetConfig config = new CobolCharsetConfig(EBCDIC, ASCII, EBCDIC);

            assertThat(config.carddemoDatasetCharset()).isEqualTo(Charset.forName(EBCDIC));
            assertThat(config.carddemoAsciiCharset()).isEqualTo(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("The ASCII bean is the cached StandardCharsets instance, not a second object")
        void theAsciiBeanIsTheCachedInstance() {
            CobolCharsetConfig config = new CobolCharsetConfig(EBCDIC, ASCII, ASCII);

            assertThat(config.carddemoAsciiCharset()).isSameAs(StandardCharsets.US_ASCII);
        }
    }

    @Nested
    @DisplayName("Resolution: the one decision this class makes")
    class Resolution {

        @Test
        @DisplayName("A supported name resolves")
        void aSupportedNameResolves() {
            assertThat(CobolCharsetConfig.resolve(ASCII,
                    CobolCharsetConfig.ASCII_CHARSET_PROPERTY))
                    .isEqualTo(StandardCharsets.US_ASCII);
            assertThat(CobolCharsetConfig.resolve(EBCDIC,
                    CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY))
                    .isEqualTo(Charset.forName(EBCDIC));
        }

        @Test
        @DisplayName("An unsupported name fails loudly, naming the property, with no fallback")
        void anUnsupportedNameFailsLoudly() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CobolCharsetConfig.resolve("IBM-NOT-A-CODE-PAGE",
                            CobolCharsetConfig.DATASET_CHARSET_PROPERTY))
                    .withMessageContaining("IBM-NOT-A-CODE-PAGE")
                    .withMessageContaining(CobolCharsetConfig.DATASET_CHARSET_PROPERTY)
                    .withMessageContaining("No fallback is applied");
        }

        @Test
        @DisplayName("A blank name is rejected too: it never stands in for a code page")
        void aBlankNameIsRejected() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> CobolCharsetConfig.resolve("",
                            CobolCharsetConfig.ASCII_CHARSET_PROPERTY));
        }

        @Test
        @DisplayName("A resolution failure surfaces through the bean method as well")
        void aResolutionFailureSurfacesThroughTheBean() {
            CobolCharsetConfig config =
                    new CobolCharsetConfig("NO-SUCH-EBCDIC", ASCII, ASCII);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(config::carddemoEbcdicCharset)
                    .withMessageContaining(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY);
        }
    }

    @Nested
    @DisplayName("The published contract: property keys and bean names")
    class PublishedContract {

        @Test
        @DisplayName("The three property keys are the ones application.yml declares")
        void thePropertyKeysAreStable() {
            assertThat(CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY)
                    .isEqualTo("carddemo.charset.ebcdic");
            assertThat(CobolCharsetConfig.ASCII_CHARSET_PROPERTY)
                    .isEqualTo("carddemo.charset.ascii");
            assertThat(CobolCharsetConfig.DATASET_CHARSET_PROPERTY)
                    .isEqualTo("carddemo.charset.dataset");
        }

        @Test
        @DisplayName("The three bean names are the ones every @Qualifier selects by")
        void theBeanNamesAreStable() {
            assertThat(CobolCharsetConfig.EBCDIC_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoEbcdicCharset");
            assertThat(CobolCharsetConfig.ASCII_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoAsciiCharset");
            assertThat(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoDatasetCharset");
        }

        @Test
        @DisplayName("EBCDIC and ASCII genuinely differ, which is why naming one is load-bearing")
        void theTwoCodePagesGenuinelyDiffer() {
            byte[] ebcdicSpace = " ".getBytes(Charset.forName(EBCDIC));
            byte[] asciiSpace = " ".getBytes(StandardCharsets.US_ASCII);

            assertThat(ebcdicSpace).containsExactly((byte) 0x40);
            assertThat(asciiSpace).containsExactly((byte) 0x20);
        }
    }
}
