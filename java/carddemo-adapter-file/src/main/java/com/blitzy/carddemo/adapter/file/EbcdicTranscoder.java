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

// AAP §0.6.5 binding constraint: no java.io.File anywhere in this module. The standard-library
// java.nio.charset.* types resolve EBCDIC / ASCII codepages by name and are thread-safe.
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;

// AAP §0.6.5 / §0.7.5: Properties is the configuration container injected at the composition
// root (carddemo-app) and Locale.ROOT is used for locale-independent case folding of dataset
// names during property-key construction. Map.copyOf() produces a defensive immutable copy of
// the relevant configuration keys so the transcoder cannot be perturbed by post-construction
// mutation of the source Properties instance.
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

// SLF4J facade (slf4j-api 2.0.16 per parent POM dependencyManagement) is used for the one-time
// INFO startup log line documenting the resolved default Charset. The concrete logging backend
// (logback-classic 1.5.19) is supplied at runtime by the composition root in carddemo-app,
// keeping this adapter free of binding to a specific backend per AAP §0.6.12.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Codepage configuration + transcoding adapter for file-backed repositories.
 * Provides two related capabilities:
 * <ul>
 *   <li><strong>Codepage resolution</strong>: {@link #charsetFor(String)},
 *       {@link #defaultCharset()}, and {@link #isEbcdic(String)} resolve a
 *       {@link Charset} for a given logical dataset name, supporting per-file
 *       overrides via {@link Properties} configuration with {@code IBM-1047}
 *       (EBCDIC) as the system-wide default.</li>
 *   <li><strong>EBCDIC↔ASCII transcoding</strong>:
 *       {@link #ebcdicToAscii(byte[], String)} and
 *       {@link #asciiToEbcdic(String, String)} (with overloaded forms keyed to
 *       the system default) perform byte/string conversion between the
 *       configured codepage and {@link java.nio.charset.StandardCharsets#US_ASCII US-ASCII}.
 *       Conversion uses an explicit {@link CodingErrorAction#REPORT} policy
 *       so any unmappable character surfaces as a checked exception rather
 *       than silently substituted, preserving byte-for-byte parity per AAP
 *       §0.6.5.</li>
 * </ul>
 *
 * <p><strong>Configuration convention</strong> (per AAP §0.6.5 and §0.7.5):
 * Keys take the form {@code carddemo.file.<dataset>.charset}, where
 * {@code <dataset>} is a lowercase logical name (e.g., {@code acctdata},
 * {@code carddata}, {@code custdata}, {@code dailytran}, {@code discgrp},
 * {@code tcatbal}, {@code trancatg}, {@code trantype}, {@code cardxref},
 * {@code usrsec}, {@code transact}). The system-wide default is read from
 * {@code carddemo.file.default.charset} or, if absent, falls back to
 * {@code IBM-1047}.
 *
 * <p><strong>Example application.properties:</strong>
 * <pre>{@code
 * # Use ASCII fixtures from app/data/ASCII for testing
 * carddemo.file.default.charset=US-ASCII
 *
 * # ...or use EBCDIC IBM-1047 for production (default)
 * # carddemo.file.default.charset=IBM-1047
 *
 * # Per-file override example:
 * # carddemo.file.acctdata.charset=IBM-037
 * }</pre>
 *
 * <p><strong>Why this matters</strong>: production files in the source z/OS
 * system are encoded in EBCDIC (typically code page 1047). The 9 ASCII
 * fixtures under {@code app/data/ASCII/*.txt} are pre-transcoded for test
 * convenience; the production code supports both EBCDIC and ASCII inputs
 * by changing only configuration. The default codepage selection here
 * directly determines whether byte-for-byte parity tests pass against
 * captured COBOL output (AAP §0.6.5 mandate).
 *
 * <p><strong>Unmappable character policy</strong>: both
 * {@link #ebcdicToAscii(byte[], String)} and
 * {@link #asciiToEbcdic(String, String)} use {@link CodingErrorAction#REPORT}
 * for both unmappable and malformed input. This raises
 * {@link CharacterCodingException} (wrapped as {@link IllegalArgumentException}
 * with a descriptive message identifying the dataset) the instant a byte or
 * character cannot be represented in the target charset. This strict policy
 * is required by AAP §0.6.5 byte-for-byte parity: silent substitution
 * (the JDK default, which replaces unmappable bytes with {@code '?'} or
 * {@code 0x6F}) would corrupt records and break golden-record tests. Callers
 * who genuinely want a replacement policy should construct their own
 * {@link CharsetEncoder} / {@link CharsetDecoder} via the
 * {@link #charsetFor(String)} accessor.
 *
 * <p><strong>Thread-safety</strong>: instances are deeply immutable. The
 * injected {@link Properties} is defensively copied into an unmodifiable
 * {@link Map} at construction so that post-construction mutation of the
 * source {@code Properties} cannot perturb this transcoder's behavior.
 * All {@link Charset} lookups flow through {@link Charset#forName(String)}
 * (documented thread-safe), and per-call {@link CharsetDecoder} /
 * {@link CharsetEncoder} instances are created on each transcoding call
 * (these JDK types are explicitly NOT thread-safe and cannot be shared
 * across threads).
 *
 * <p><strong>Error handling</strong>: {@link Charset#forName(String)} throws
 * two distinct exception types ({@link IllegalCharsetNameException} for
 * syntactically invalid names, {@link UnsupportedCharsetException} for
 * names that are syntactically valid but absent from the running JVM).
 * Both are normalised into a single {@link UnsupportedCharsetException}
 * whose message identifies <em>which</em> dataset has the bad
 * configuration — critical for diagnostics in multi-file deployments.
 *
 * <p><strong>Architectural constraints (binding per AAP §0.6.5 and §0.7.4):</strong>
 * No preview features; no reflection; no Spring; no Lombok; no
 * {@link java.io.File}. The class is intentionally tiny and dependency-free
 * (only JDK + SLF4J) so it can be the foundational element of the
 * adapter-file module.
 *
 * <p><strong>Usage from the composition root</strong>:
 * <pre>{@code
 * Properties props = new Properties();
 * try (InputStream in = Files.newInputStream(Path.of("application.properties"))) {
 *     props.load(in);
 * }
 * EbcdicTranscoder transcoder = new EbcdicTranscoder(props);
 * Path acctFile = Path.of(props.getProperty("carddemo.file.acctdata.path"));
 * AccountRepository accountRepo = new FileAccountRepository(
 *     acctFile, transcoder.charsetFor("acctdata"));
 * // ...or, for ad-hoc transcoding:
 * byte[] ebcdic = ...;
 * String ascii = transcoder.ebcdicToAscii(ebcdic, "acctdata");
 * }</pre>
 */
public final class EbcdicTranscoder {

    /**
     * Logger for the one-time INFO startup line; SLF4J facade per AAP §0.6.12.
     * Static so that all instances share the same logger; the log line itself
     * is emitted once per constructor invocation, so for a single composition
     * root the line appears exactly once at startup.
     */
    private static final Logger LOG = LoggerFactory.getLogger(EbcdicTranscoder.class);

    /**
     * The system-wide default codepage name when no override is configured.
     * Value: {@code "IBM-1047"} — the canonical z/OS EBCDIC code page used
     * by the COBOL CardDemo reference implementation per AAP §0.6.5. Resolved
     * via {@link Charset#forName(String)}; supported in every standard JDK 25
     * distribution.
     */
    public static final String DEFAULT_CHARSET_NAME = "IBM-1047";

    /**
     * The configuration key for the system-wide default override. If this
     * key is present in the injected {@link Properties}, its value supplants
     * {@link #DEFAULT_CHARSET_NAME} as the fallback for all datasets that
     * do not declare a per-file override.
     */
    public static final String DEFAULT_KEY = "carddemo.file.default.charset";

    /**
     * Prefix for per-dataset configuration keys. Append the lowercased
     * dataset name followed by {@link #DATASET_SUFFIX} to form a complete
     * key (e.g., {@code carddemo.file.acctdata.charset}).
     */
    public static final String DATASET_PREFIX = "carddemo.file.";

    /**
     * Suffix appended to {@link #DATASET_PREFIX} + {@code <dataset>} to
     * form a per-dataset configuration key. Held package-private as a
     * named constant for grep-ability and for shared use by unit tests.
     */
    static final String DATASET_SUFFIX = ".charset";

    /**
     * Defensively copied snapshot of the relevant configuration keys
     * (those matching {@link #DATASET_PREFIX} prefix and {@link #DATASET_SUFFIX}
     * suffix, or the literal {@link #DEFAULT_KEY}). Captured at construction
     * via {@link Collections#unmodifiableMap(Map)} so that subsequent
     * mutation of the source {@link Properties} cannot perturb resolution
     * behaviour. Replaces the previous live-reference design and makes
     * immutability a structural guarantee rather than a caller convention.
     */
    private final Map<String, String> charsetConfig;

    /**
     * The resolved system-wide default {@link Charset}, computed once at
     * construction time so that the cost of {@link Charset#forName(String)}
     * is paid only once per default value. Final and never null.
     */
    private final Charset defaultCharset;

    /**
     * Constructs a transcoder reading its configuration from the supplied
     * {@link Properties}. If the properties contain a {@link #DEFAULT_KEY}
     * entry, that value is used as the default; otherwise
     * {@link #DEFAULT_CHARSET_NAME} ({@code IBM-1047}) is used.
     *
     * <p>The default charset is resolved eagerly at construction so that any
     * configuration error fails fast at startup rather than at the first I/O
     * operation. This is consistent with the 12-factor configuration model
     * mandated by AAP §0.7.2: misconfiguration is a startup error, not a
     * runtime surprise.
     *
     * <p>The supplied {@link Properties} is read once and the relevant keys
     * are defensively copied into an unmodifiable map. Subsequent mutation
     * of the {@code properties} argument (or of any third party holding the
     * same reference) has no effect on this transcoder. This makes
     * immutability a structural guarantee, not a caller convention.
     *
     * <p>Exactly one INFO log line is emitted by this constructor, naming
     * the resolved default charset. This is the only side effect.
     *
     * @param properties application configuration (must not be {@code null};
     *                   may be empty)
     * @throws NullPointerException        if {@code properties} is {@code null}
     * @throws UnsupportedCharsetException if a configured charset is not
     *                                     supported by the running JVM
     */
    public EbcdicTranscoder(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        // Snapshot the relevant keys into a defensive immutable copy.
        // We iterate `stringPropertyNames()` once and pull values via
        // `getProperty()` so we get the full lookup (including the
        // `defaults` chain that Properties supports) rather than the bare
        // Hashtable entries. Only keys matching the dataset-prefix /
        // dataset-suffix convention OR the DEFAULT_KEY are retained;
        // unrelated keys are ignored to keep the snapshot small and
        // minimise memory footprint.
        Map<String, String> snapshot = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (DEFAULT_KEY.equals(key)
                || (key.startsWith(DATASET_PREFIX) && key.endsWith(DATASET_SUFFIX))) {
                String val = properties.getProperty(key);
                if (val != null) {
                    snapshot.put(key, val);
                }
            }
        }
        this.charsetConfig = Collections.unmodifiableMap(snapshot);
        String defaultName = charsetConfig.getOrDefault(DEFAULT_KEY, DEFAULT_CHARSET_NAME);
        this.defaultCharset = resolveCharset(defaultName, "default");
        LOG.info("EbcdicTranscoder initialized: defaultCharset={}", defaultCharset.name());
    }

    /**
     * Constructs a transcoder with no properties. Equivalent to
     * {@code new EbcdicTranscoder(new Properties())}: every call to
     * {@link #charsetFor(String)} returns {@link #DEFAULT_CHARSET_NAME}.
     * Useful for tests and for production deployments that accept the
     * system default ({@code IBM-1047}) for every dataset.
     */
    public EbcdicTranscoder() {
        this(new Properties());
    }

    /**
     * Returns the {@link Charset} for the given logical dataset name.
     * Resolution order:
     * <ol>
     *   <li>Look up {@code carddemo.file.<lowercased-dataset>.charset} in
     *       the configured (defensively copied) configuration map.</li>
     *   <li>If the value is {@code null} or blank, return the configured
     *       system-wide default ({@link #defaultCharset()}).</li>
     *   <li>Otherwise resolve the value via {@link Charset#forName(String)}.</li>
     * </ol>
     *
     * <p>The dataset name is normalised to lower case using
     * {@link Locale#ROOT} so that configuration keys are case-insensitive
     * (e.g., {@code ACCTDATA}, {@code AcctData}, and {@code acctdata} all
     * resolve to the same key). Using {@link Locale#ROOT} (rather than the
     * default locale) ensures portable, deployment-environment-independent
     * behaviour.
     *
     * @param dataset the logical dataset name (e.g., {@code "acctdata"});
     *                must not be {@code null} or blank
     * @return the resolved {@link Charset}; never {@code null}
     * @throws IllegalArgumentException    if {@code dataset} is {@code null}
     *                                     or blank
     * @throws UnsupportedCharsetException if the configured charset is
     *                                     unknown to the running JVM
     */
    public Charset charsetFor(String dataset) {
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException(
                "dataset name must be non-null and non-blank");
        }
        String key = DATASET_PREFIX + dataset.toLowerCase(Locale.ROOT) + DATASET_SUFFIX;
        String name = charsetConfig.get(key);
        if (name == null || name.isBlank()) {
            return defaultCharset;
        }
        return resolveCharset(name, dataset);
    }

    /**
     * Returns the system-wide default {@link Charset} resolved at
     * construction time. Used by callers (e.g., the composition root) for
     * diagnostics and for repositories that have no logical dataset name.
     *
     * @return the system-wide default {@link Charset}; never {@code null}
     */
    public Charset defaultCharset() {
        return defaultCharset;
    }

    /**
     * Returns {@code true} if the configured charset for the given dataset
     * appears to be an EBCDIC codepage. The classification is based on
     * canonical {@link Charset} name prefixes:
     * <ul>
     *   <li>{@code "IBM"} — covers both dashed and undashed canonical
     *       forms ({@code IBM-1047}, {@code IBM1047}, {@code IBM-037},
     *       {@code IBM037}, {@code IBM500}, etc.). Different JDK
     *       distributions canonicalise these aliases differently — for
     *       example, Eclipse Temurin 25 returns {@code "IBM1047"} as the
     *       canonical name for the {@code IBM-1047} alias, while other
     *       JDKs may preserve the dash. Matching the bare {@code "IBM"}
     *       prefix catches both forms.</li>
     *   <li>{@code "Cp"} — historical JDK aliases such as {@code Cp1047},
     *       {@code Cp037}</li>
     *   <li>{@code "x-IBM"} — alternate JDK names such as
     *       {@code x-IBM1047} on JDKs that surface them</li>
     * </ul>
     *
     * <p><strong>Informational only</strong>: this method is exposed as a
     * debugging / observability aid (e.g., for inclusion in a startup banner
     * or a diagnostic log line). No production code path branches on its
     * result. Production correctness depends solely on
     * {@link #charsetFor(String)} returning the right {@link Charset}.
     *
     * @param dataset the logical dataset name; must not be {@code null} or
     *                blank
     * @return {@code true} if the dataset is configured to use an EBCDIC
     *         codepage; {@code false} otherwise
     * @throws IllegalArgumentException    if {@code dataset} is {@code null}
     *                                     or blank
     * @throws UnsupportedCharsetException if the configured charset is
     *                                     unknown to the running JVM
     */
    public boolean isEbcdic(String dataset) {
        String name = charsetFor(dataset).name();
        // "IBM" (no dash) covers both Temurin-style "IBM1047" and dashed-style "IBM-1047"
        // since the latter trivially starts with the former. "Cp" covers historical aliases
        // such as Cp1047 / Cp037. "x-IBM" covers alternate JDK names. All three patterns
        // together form a strict superset of every EBCDIC code-page canonical name
        // observed in mainstream JDK distributions per AAP §0.6.5.
        return name.startsWith("IBM") || name.startsWith("Cp") || name.startsWith("x-IBM");
    }

    /**
     * Converts a byte buffer encoded in the dataset's configured charset
     * (typically EBCDIC {@code IBM-1047}) into a Java {@link String} (whose
     * char-array contents are pure Unicode and round-trip losslessly to
     * US-ASCII for the printable ASCII subset). This is the canonical
     * "EBCDIC → ASCII" conversion mandated by AAP §0.6.5.
     *
     * <p><strong>Round-trip behaviour</strong>: feeding the returned
     * {@code String} back into {@link #asciiToEbcdic(String, String)} with
     * the same {@code dataset} argument returns a {@code byte[]} that is
     * byte-for-byte identical to the input {@code source}, for any source
     * whose every byte maps to a printable ASCII / EBCDIC character. Bytes
     * that do not map cause {@link IllegalArgumentException} on this
     * decode call (NOT on the encode round-trip).
     *
     * <p><strong>Error policy</strong>: uses
     * {@link CodingErrorAction#REPORT} for both
     * {@link CharsetDecoder#onMalformedInput(CodingErrorAction)} and
     * {@link CharsetDecoder#onUnmappableCharacter(CodingErrorAction)} so an
     * unmappable byte raises {@link CharacterCodingException} (rewrapped as
     * {@link IllegalArgumentException} with the dataset name in the message).
     * Silent substitution is FORBIDDEN by AAP §0.6.5 — it would corrupt
     * records and break golden-record parity tests.
     *
     * <p><strong>Thread-safety</strong>: a fresh {@link CharsetDecoder} is
     * created on every call because {@code CharsetDecoder} instances are
     * explicitly NOT thread-safe per their Javadoc. This is the documented
     * trade-off for correctness over allocation cost; profile in production
     * before optimising.
     *
     * @param source  the bytes to decode; must not be {@code null}. Zero-length
     *                input returns the empty string.
     * @param dataset the logical dataset name whose configured charset governs
     *                the conversion (e.g., {@code "acctdata"}); must not be
     *                {@code null} or blank.
     * @return the decoded {@link String}; never {@code null}
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code dataset} is {@code null} or
     *                                  blank, or if any byte in {@code source}
     *                                  cannot be decoded under the configured
     *                                  charset
     */
    public String ebcdicToAscii(byte[] source, String dataset) {
        Objects.requireNonNull(source, "source");
        Charset cs = charsetFor(dataset);
        CharsetDecoder decoder = cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(source));
            return out.toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                "Unable to decode bytes from charset " + cs.name()
                    + " for dataset '" + dataset + "': " + e.getMessage(), e);
        }
    }

    /**
     * Convenience overload of {@link #ebcdicToAscii(byte[], String)} that
     * uses the system default charset (resolved at construction; typically
     * {@code IBM-1047}). Equivalent to
     * {@code ebcdicToAscii(source, /* uses defaultCharset directly *&#47;)}.
     *
     * <p>The error policy and thread-safety characteristics are identical
     * to the dataset-aware overload.
     *
     * @param source the bytes to decode; must not be {@code null}
     * @return the decoded {@link String}; never {@code null}
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if any byte in {@code source} cannot
     *                                  be decoded under the default charset
     */
    public String ebcdicToAscii(byte[] source) {
        Objects.requireNonNull(source, "source");
        CharsetDecoder decoder = defaultCharset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(source));
            return out.toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                "Unable to decode bytes from default charset "
                    + defaultCharset.name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Converts a Java {@link String} (whose char-array contents are Unicode)
     * into a byte buffer encoded in the dataset's configured charset
     * (typically EBCDIC {@code IBM-1047}). This is the canonical
     * "ASCII → EBCDIC" conversion mandated by AAP §0.6.5.
     *
     * <p><strong>Round-trip behaviour</strong>: feeding the returned
     * {@code byte[]} back into {@link #ebcdicToAscii(byte[], String)} with
     * the same {@code dataset} argument returns a {@code String} that is
     * code-point-for-code-point identical to the input {@code source}, for
     * any source whose every character maps to a single byte under the
     * configured charset (which is true for the printable ASCII subset
     * under {@code IBM-1047}).
     *
     * <p><strong>Error policy</strong>: uses
     * {@link CodingErrorAction#REPORT} for both
     * {@link CharsetEncoder#onMalformedInput(CodingErrorAction)} and
     * {@link CharsetEncoder#onUnmappableCharacter(CodingErrorAction)} so an
     * unmappable character (e.g., a Unicode glyph absent from the EBCDIC
     * code page) raises {@link CharacterCodingException} (rewrapped as
     * {@link IllegalArgumentException} with the dataset name in the message).
     *
     * <p><strong>Thread-safety</strong>: a fresh {@link CharsetEncoder} is
     * created on every call because {@code CharsetEncoder} instances are
     * explicitly NOT thread-safe per their Javadoc.
     *
     * @param source  the characters to encode; must not be {@code null}.
     *                Zero-length input returns an empty byte array.
     * @param dataset the logical dataset name whose configured charset governs
     *                the conversion (e.g., {@code "acctdata"}); must not be
     *                {@code null} or blank.
     * @return the encoded bytes; never {@code null}
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code dataset} is {@code null} or
     *                                  blank, or if any character in
     *                                  {@code source} cannot be encoded under
     *                                  the configured charset
     */
    public byte[] asciiToEbcdic(String source, String dataset) {
        Objects.requireNonNull(source, "source");
        Charset cs = charsetFor(dataset);
        CharsetEncoder encoder = cs.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer out = encoder.encode(CharBuffer.wrap(source));
            byte[] result = new byte[out.remaining()];
            out.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                "Unable to encode string to charset " + cs.name()
                    + " for dataset '" + dataset + "': " + e.getMessage(), e);
        }
    }

    /**
     * Convenience overload of {@link #asciiToEbcdic(String, String)} that
     * uses the system default charset (resolved at construction; typically
     * {@code IBM-1047}).
     *
     * <p>The error policy and thread-safety characteristics are identical
     * to the dataset-aware overload.
     *
     * @param source the characters to encode; must not be {@code null}
     * @return the encoded bytes; never {@code null}
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if any character in {@code source}
     *                                  cannot be encoded under the default
     *                                  charset
     */
    public byte[] asciiToEbcdic(String source) {
        Objects.requireNonNull(source, "source");
        CharsetEncoder encoder = defaultCharset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer out = encoder.encode(CharBuffer.wrap(source));
            byte[] result = new byte[out.remaining()];
            out.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                "Unable to encode string to default charset "
                    + defaultCharset.name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a charset name with structured error messaging. Wraps both
     * {@link IllegalCharsetNameException} (syntactically invalid name) and
     * {@link UnsupportedCharsetException} (syntactically valid name but
     * unsupported by the JVM) into a single {@link UnsupportedCharsetException}
     * whose message identifies the dataset (or {@code "default"}) responsible
     * for the bad configuration. This is critical for diagnostics in
     * deployments with many per-file overrides where the underlying JDK
     * exception alone would not pinpoint the responsible configuration key.
     *
     * @param name        the charset name (e.g., {@code "IBM-1047"})
     * @param contextDesc description of the configuration source for error
     *                    messages: either the literal {@code "default"} or a
     *                    dataset name such as {@code "acctdata"}
     * @return the resolved {@link Charset}; never {@code null}
     * @throws UnsupportedCharsetException with a context-augmented message if
     *                                     the name is unknown or invalid
     */
    private static Charset resolveCharset(String name, String contextDesc) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            UnsupportedCharsetException wrap = new UnsupportedCharsetException(
                "Configured charset '" + name + "' for context '"
                    + contextDesc + "' is not supported by this JVM");
            wrap.initCause(e);
            throw wrap;
        }
    }
}
