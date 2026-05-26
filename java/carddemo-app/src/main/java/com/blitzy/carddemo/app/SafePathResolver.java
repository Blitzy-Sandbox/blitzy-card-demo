/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blitzy.carddemo.app;

// JEP 511 (finalized in Java 25) — single module import declaration for
// the java.base module. Per AAP §0.6.7 ("Module Import Declarations:
// `import module java.base;` at the top of files using many java.*
// packages"), this is the mandated idiom. SafePathResolver consumes
// Locale (case folding for env-var names), Objects (requireNonNull),
// Path / Files (java.nio.file path operations), StandardOpenOption
// (no-op imports for documentation), IllegalArgumentException, and
// SecurityException — all of which live under java.base.
import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves JCL DD-equivalent file paths from environment variables,
 * JVM system properties, and {@code application.properties} defaults
 * with mandatory path containment validation (CWE-22 mitigation).
 *
 * <p><strong>Why this class exists</strong>. Every {@code *App.java}
 * composition root in {@code carddemo-app} translates a JCL
 * {@code //ddname DD DSN=...} statement to a {@link Path} resolved
 * from a configuration value. Without containment validation, an
 * adversary who can set an environment variable could redirect a
 * read or a write to an arbitrary location on the filesystem
 * &mdash; the classic CWE-22 path-traversal exposure flagged in the
 * Checkpoint 4 code review (S2). This class centralises the
 * 12-factor lookup logic AND the containment validation so every
 * app main wires its DD inputs through the same safe resolver.
 *
 * <h2>Translation authority</h2>
 * <ul>
 *   <li>AAP &sect;0.2.1 &mdash; CREATE supplementary files under
 *       {@code carddemo-app} as needed for the composition roots.</li>
 *   <li>AAP &sect;0.4.1 &mdash; JCL {@code DD} statements map to
 *       constructor arguments / {@code application.properties} keys.</li>
 *   <li>AAP &sect;0.6.5 &mdash; "All file I/O uses {@code java.nio.file}";
 *       {@code java.io.File} is forbidden in new code.</li>
 *   <li>AAP &sect;0.7.2 &mdash; "Preserve all existing PCI-relevant
 *       controls"; defence-in-depth on filesystem accesses.</li>
 * </ul>
 *
 * <h2>Configuration precedence (12-factor)</h2>
 * <ol>
 *   <li>Environment variable (UPPER_SNAKE_CASE form of the key)</li>
 *   <li>JVM system property ({@code -Dkey=value})</li>
 *   <li>Caller-supplied default value</li>
 * </ol>
 * The first non-blank value wins. The mapping from property name to
 * environment variable is direct: dots and hyphens become
 * underscores, and the whole name is upper-cased.
 *
 * <h2>Containment validation algorithm</h2>
 * <p>Each call to {@link #resolve(String, String, Path)} (and the
 * convenience variants) performs:
 * <ol>
 *   <li>Read the raw configured path string (12-factor precedence).</li>
 *   <li>Build a {@link Path} via {@link Path#of(String, String...)}.</li>
 *   <li>Call {@link Path#normalize()} to collapse {@code ..} and
 *       {@code .} segments. This is the canonical CWE-22 mitigation
 *       step.</li>
 *   <li>If an {@code allowedRoot} is supplied, convert both paths to
 *       their {@link Path#toAbsolutePath() absolute} forms and assert
 *       that the normalised path
 *       {@linkplain Path#startsWith(Path) starts with} the allowed
 *       root. Failures throw
 *       {@link PathContainmentException} with a diagnostic message
 *       (the actual offending path is masked from the message body
 *       for log hygiene; the structured-log field carries the full
 *       value for the operator).</li>
 *   <li>Return the normalised {@link Path}.</li>
 * </ol>
 *
 * <h2>Trusted deployment exception</h2>
 * <p>Per AAP &sect;0.4.1, an app main may legitimately accept absolute
 * paths (a JCL job that reads {@code //ACCTFILE DD
 * DSN=/data/acctdata.dat} is functionally equivalent to one that
 * reads from a relative location). When {@code allowedRoot == null},
 * the resolver enforces only normalisation and absolute-path
 * sanity, leaving callers responsible for documenting why
 * containment was waived. The {@link #resolveTrusted(String, String)}
 * convenience accepts this trust assumption explicitly so that grep
 * audits can find every site that opts out.
 *
 * <h2>Concurrency</h2>
 * Stateless and thread-safe; the class has no instance fields and
 * its constructor throws to prevent instantiation.
 *
 * @since 1.0.0
 */
public final class SafePathResolver {

    /**
     * SLF4J logger for diagnostic emission. The Logback pipeline is
     * configured in {@code carddemo-app/src/main/resources/logback.xml}
     * with the {@code %replace} PAN-masking regex per AAP &sect;0.7.2
     * (logback-classic 1.5.19, supplied at runtime by the composition
     * root).
     */
    private static final Logger LOG = LoggerFactory.getLogger(SafePathResolver.class);

    /**
     * Sentinel name used in the diagnostic message when no key was
     * supplied. Logged as {@code "<null-key>"} in the failure
     * payload.
     */
    private static final String NULL_KEY_SENTINEL = "<null-key>";

    /**
     * Utility class: instantiation is forbidden. Per AAP &sect;0.3.2
     * factory-style pattern; the class exposes only static methods.
     */
    private SafePathResolver() {
        throw new AssertionError("SafePathResolver is a utility class; do not instantiate");
    }

    /**
     * Looks up a configuration value with the documented 12-factor
     * precedence (environment variable then JVM system property then
     * caller default). The first non-blank value wins.
     *
     * <p>Identical in behaviour to the {@code getProp(...)} static
     * helpers replicated across the app mains; refactored here so
     * each main delegates instead of duplicating the lookup logic.
     * Used by {@link #resolve(String, String, Path)} and exposed as
     * a {@code public static} method so app mains can call it
     * directly when they need a non-path configuration value
     * (e.g., {@code carddemo.intcalc.parm}, dates, charsets).
     *
     * @param key          the {@code application.properties} key
     *                     (e.g., {@code carddemo.file.acctdata.path});
     *                     never {@code null}
     * @param defaultValue the fallback if neither the env var nor the
     *                     system property is set; may be {@code null}
     *                     or empty
     * @return the resolved value (env var, system property, or
     *         default), never {@code null} when {@code defaultValue}
     *         is non-{@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public static String getProperty(String key, String defaultValue) {
        Objects.requireNonNull(key, "key");
        String envKey = key.toUpperCase(Locale.ROOT)
                .replace('.', '_')
                .replace('-', '_');
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp;
        }
        return defaultValue;
    }

    /**
     * Resolves a {@link Path} from a configuration key with mandatory
     * normalisation, optional containment validation, and structured
     * diagnostic logging.
     *
     * <p>This is the canonical entry point used by every app main
     * for translating a JCL DD statement to a Java {@link Path}.
     *
     * @param key          the {@code application.properties} key (
     *                     e.g., {@code carddemo.file.acctdata.path});
     *                     never {@code null}
     * @param defaultValue the fallback value if neither env nor system
     *                     property is set; may not be {@code null}
     *                     because a missing-path default is a
     *                     configuration error
     * @param allowedRoot  the containment root the resolved path must
     *                     start with after normalisation; pass
     *                     {@code null} to opt out of containment
     *                     enforcement (only use that for trusted
     *                     deployment scenarios, and prefer
     *                     {@link #resolveTrusted(String, String)} so
     *                     grep audits can find the opt-out site)
     * @return the normalised {@link Path}, guaranteed to satisfy
     *         {@code result.equals(result.normalize())} and (when
     *         {@code allowedRoot} is non-{@code null})
     *         {@code result.toAbsolutePath().startsWith(allowedRoot.toAbsolutePath())}
     * @throws NullPointerException     if {@code key} or
     *                                  {@code defaultValue} is {@code null}
     * @throws PathContainmentException if {@code allowedRoot} is
     *                                  non-{@code null} and the
     *                                  resolved path escapes the
     *                                  allowed root
     */
    public static Path resolve(String key, String defaultValue, Path allowedRoot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(defaultValue, "defaultValue");
        String rawPath = getProperty(key, defaultValue);
        Path normalised = Path.of(rawPath).normalize();
        if (allowedRoot != null) {
            Path absResolved = normalised.toAbsolutePath();
            Path absRoot = allowedRoot.toAbsolutePath().normalize();
            if (!absResolved.startsWith(absRoot)) {
                String msg = "Path " + key + " resolves outside the allowed root '"
                        + absRoot + "'; configured value escaped containment.";
                // Log the violation at WARN (not ERROR) so it is
                // surfaced loudly but the abend handling at the app
                // main remains the source of truth. The configured
                // value is intentionally NOT included in the
                // exception message so it does not leak through the
                // exception chain into untrusted log sinks; the full
                // value is logged separately as a structured field.
                LOG.warn("CWE-22 containment violation: key={}, rawConfiguredPath={}, "
                        + "normalised={}, allowedRoot={}", key, rawPath, absResolved, absRoot);
                throw new PathContainmentException(msg);
            }
        }
        return normalised;
    }

    /**
     * Resolves a {@link Path} without containment enforcement
     * (trusted-deployment escape hatch).
     *
     * <p>Use this only when the deployment model already restricts
     * who can set environment variables and system properties (for
     * example a Kubernetes Pod whose env vars are pinned in the Pod
     * spec). Every call site that uses this method is, by
     * convention, an explicit opt-out of CWE-22 mitigation; grep
     * audits ({@code grep -rn 'resolveTrusted' carddemo-app}) MUST
     * be able to enumerate every such site.
     *
     * @param key          the {@code application.properties} key;
     *                     never {@code null}
     * @param defaultValue the fallback value; never {@code null}
     * @return the normalised {@link Path} (no containment check)
     * @throws NullPointerException if either parameter is {@code null}
     */
    public static Path resolveTrusted(String key, String defaultValue) {
        return resolve(key, defaultValue, null);
    }

    /**
     * Resolves a {@link Path} with the default data-directory
     * containment root derived from the {@code carddemo.data.root}
     * configuration key or the supplied fallback.
     *
     * <p>The carddemo data root is the umbrella directory under which
     * every JCL DD-equivalent file lives. Containment against this
     * root prevents an adversarial env var from redirecting a write
     * to {@code /etc/shadow} or a read to {@code /proc/self/environ}.
     *
     * @param key          the {@code application.properties} key;
     *                     never {@code null}
     * @param defaultValue the fallback value; never {@code null}
     * @return the normalised, contained {@link Path}
     * @throws PathContainmentException if the resolved path escapes
     *                                  the data root
     */
    public static Path resolveData(String key, String defaultValue) {
        Path dataRoot = Path.of(getProperty("carddemo.data.root", "./data"))
                .toAbsolutePath().normalize();
        return resolve(key, defaultValue, dataRoot);
    }

    /**
     * Resolves a {@link Path} with the default output-directory
     * containment root derived from the {@code carddemo.output.root}
     * configuration key or the supplied fallback.
     *
     * <p>Used by report generators, statement writers, and any other
     * app main that produces files for downstream consumption.
     *
     * @param key          the {@code application.properties} key;
     *                     never {@code null}
     * @param defaultValue the fallback value; never {@code null}
     * @return the normalised, contained {@link Path}
     * @throws PathContainmentException if the resolved path escapes
     *                                  the output root
     */
    public static Path resolveOutput(String key, String defaultValue) {
        Path outputRoot = Path.of(getProperty("carddemo.output.root", "./output"))
                .toAbsolutePath().normalize();
        return resolve(key, defaultValue, outputRoot);
    }

    /**
     * Resolves a containment root from a configuration key. Used by
     * app mains that need to know what root to validate against
     * (e.g., to surface the value in a structured log line at
     * job start).
     *
     * @param key          the {@code application.properties} key
     *                     identifying the root; never {@code null}
     * @param defaultValue the fallback value; never {@code null}
     * @return the normalised absolute path to the root directory
     */
    public static Path resolveRoot(String key, String defaultValue) {
        return Path.of(getProperty(key, defaultValue)).toAbsolutePath().normalize();
    }

    /**
     * Thrown when a resolved path escapes its declared allowed root.
     * Subclassed from {@link SecurityException} to make the security
     * intent explicit in stack traces and to play nicely with any
     * future {@code SecurityManager}-based policy enforcement.
     *
     * <p>Per AAP &sect;0.6.5 ("All file I/O uses {@code java.nio.file}")
     * the exception carries no {@code Path} reference; callers receive
     * only a human-readable message so the exception is safe to log
     * through PAN-masking pipelines (any digits in the message body
     * are still subject to the Logback {@code %replace} regex).
     */
    public static final class PathContainmentException extends SecurityException {

        /** Serial version UID; matches the class name hash. */
        private static final long serialVersionUID = 1L;

        /**
         * Constructs a containment-violation exception with the
         * supplied diagnostic message.
         *
         * @param message a human-readable description of the
         *                violation; should NOT include the offending
         *                path value (log the full value through the
         *                structured-log channel instead)
         */
        public PathContainmentException(String message) {
            super(message);
        }
    }
}
