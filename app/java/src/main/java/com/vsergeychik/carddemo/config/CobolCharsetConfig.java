package com.vsergeychik.carddemo.config;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * The single place in the CardDemo Java module where a character encoding is named.
 */
@Configuration
public class CobolCharsetConfig {
    /**
     * Configuration key naming the EBCDIC code page: {@code carddemo.charset.ebcdic}, declared with the
     * value {@code IBM037} in both {@code application.yml} and {@code application-test.yml}.
     */
    public static final String EBCDIC_CHARSET_PROPERTY = "carddemo.charset.ebcdic";

    /**
     * Configuration key naming the ASCII code page: {@code carddemo.charset.ascii}, declared with the value
     * {@code US-ASCII} in both {@code application.yml} and {@code application-test.yml}.
     */
    public static final String ASCII_CHARSET_PROPERTY = "carddemo.charset.ascii";

    /**
     * Configuration key naming the active dataset code page: {@code carddemo.charset.dataset}.
     */
    public static final String DATASET_CHARSET_PROPERTY = "carddemo.charset.dataset";

    /**
     * Bean name of the EBCDIC {@link Charset}: {@code carddemoEbcdicCharset}.
     */
    public static final String EBCDIC_CHARSET_BEAN_NAME = "carddemoEbcdicCharset";

    public static final String ASCII_CHARSET_BEAN_NAME = "carddemoAsciiCharset";

    public static final String DATASET_CHARSET_BEAN_NAME = "carddemoDatasetCharset";

    private final String ebcdicCharsetName;

    private final String asciiCharsetName;

    private final String datasetCharsetName;

    /**
     * Binds the three configured code-page names by constructor injection.
     *
     * @param ebcdicCharsetName value of {@link #EBCDIC_CHARSET_PROPERTY}, expected to be {@code IBM037}
     * @param asciiCharsetName value of {@link #ASCII_CHARSET_PROPERTY}, expected to be {@code US-ASCII}
     * @param datasetCharsetName value of {@link #DATASET_CHARSET_PROPERTY}, required and never defaulted -
     *     {@code IBM037} under the default profile and {@code US-ASCII} under the {@code test} profile
     */
    public CobolCharsetConfig(
            @Value("${" + EBCDIC_CHARSET_PROPERTY + "}") String ebcdicCharsetName,
            @Value("${" + ASCII_CHARSET_PROPERTY + "}") String asciiCharsetName,
            @Value("${" + DATASET_CHARSET_PROPERTY + "}") String datasetCharsetName) {
        this.ebcdicCharsetName = ebcdicCharsetName;
        this.asciiCharsetName = asciiCharsetName;
        this.datasetCharsetName = datasetCharsetName;
    }

    /**
     * The EBCDIC code page, published as bean {@link #EBCDIC_CHARSET_BEAN_NAME} and resolved from
     * {@link #EBCDIC_CHARSET_PROPERTY}.
     *
     * @return the configured EBCDIC {@link Charset}
     * @throws IllegalStateException if this JVM does not support the configured name
     */
    @Bean(EBCDIC_CHARSET_BEAN_NAME)
    public Charset carddemoEbcdicCharset() {
        return resolve(ebcdicCharsetName, EBCDIC_CHARSET_PROPERTY);
    }

    /**
     * The ASCII code page, published as bean {@link #ASCII_CHARSET_BEAN_NAME} and resolved from
     * {@link #ASCII_CHARSET_PROPERTY}.
     *
     * @return the configured ASCII {@link Charset}
     * @throws IllegalStateException if this JVM does not support the configured name
     */
    @Bean(ASCII_CHARSET_BEAN_NAME)
    public Charset carddemoAsciiCharset() {
        return resolve(asciiCharsetName, ASCII_CHARSET_PROPERTY);
    }

    /**
     * The active dataset code page, published as bean {@link #DATASET_CHARSET_BEAN_NAME} and resolved from
     * {@link #DATASET_CHARSET_PROPERTY}, which every profile must state explicitly.
     *
     * @return the configured active dataset {@link Charset}
     * @throws IllegalStateException if the configured name is blank, syntactically illegal, or a legal name
     *     this JVM does not support
     */
    @Bean(DATASET_CHARSET_BEAN_NAME)
    public Charset carddemoDatasetCharset() {
        return resolve(datasetCharsetName, DATASET_CHARSET_PROPERTY);
    }

    static Charset resolve(String charsetName, String propertyKey) {
        if (!StringUtils.hasText(charsetName)) {
            throw new IllegalStateException(rejected(charsetName, propertyKey)
                    + " A charset name is required and is never defaulted: the platform default "
                    + "charset is never substituted, because decoding a fixed-width mainframe "
                    + "record with the wrong code page corrupts it silently. Set '" + propertyKey
                    + "' to the code page this deployment's datasets are actually written in - "
                    + "IBM037 for the EBCDIC datasets, US-ASCII for the text fixtures.");
        }
        try {
            if (!Charset.isSupported(charsetName)) {
                throw new IllegalStateException(rejected(charsetName, propertyKey)
                        + " It is a legal charset name, but it is not supported by this JVM. No "
                        + "fallback is applied and the platform default charset is never "
                        + "substituted: every CardDemo dataset charset is named explicitly, because "
                        + "decoding a fixed-width mainframe record with the wrong code page corrupts "
                        + "it silently. If the name is IBM037, note that EBCDIC code pages are "
                        + "supplied by the JDK's jdk.charsets module, which a minimal jlink runtime "
                        + "image omits - run a full JDK or JRE, or include jdk.charsets in the "
                        + "image. Otherwise correct the value of '" + propertyKey + "' to a charset "
                        + "this JVM supports.");
            }
        } catch (IllegalCharsetNameException illegalName) {
            throw new IllegalStateException(rejected(charsetName, propertyKey)
                    + " It is not a syntactically legal charset name: a charset name may contain "
                    + "only letters, digits and the characters '-', '+', '.', ':' and '_', and must "
                    + "begin with a letter or digit. Correct the value of '" + propertyKey
                    + "' - and if it looks like an unresolved ${...} placeholder, the key it refers "
                    + "to is the one that is missing.", illegalName);
        }
        return Charset.forName(charsetName);
    }

    private static String rejected(String charsetName, String propertyKey) {
        return "Charset '" + charsetName + "' configured by property '" + propertyKey
                + "' cannot be used.";
    }
}
