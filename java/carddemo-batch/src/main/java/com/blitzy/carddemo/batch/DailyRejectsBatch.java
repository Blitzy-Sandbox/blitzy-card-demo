/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generation-Data-Group (GDG) equivalent versioning driver for the
 * {@code DALYREJS} rejected-records dataset, translated from
 * {@code app/jcl/DALYREJS.jcl}.
 *
 * <h2>Source lineage</h2>
 * <p>The originating JCL job defines a z/OS Generation Data Group via IDCAMS:
 * {@snippet lang = "jcl":
 * //STEP05 EXEC PGM=IDCAMS
 * //SYSPRINT DD   SYSOUT=*
 * //SYSIN    DD   *
 *    DEFINE GENERATIONDATAGROUP -
 *    (NAME(AWS.M2.CARDDEMO.DALYREJS) -
 *     LIMIT(5) -
 *     SCRATCH -
 *    )
 * }
 *
 * <p>The three GDG attributes that drive the Java translation are:
 * <ul>
 *   <li><strong>NAME</strong> {@code AWS.M2.CARDDEMO.DALYREJS} &mdash; the
 *       dataset base name. On a normal filesystem the leading
 *       {@code AWS.M2.CARDDEMO.} qualifier is dropped and only the last
 *       qualifier ({@code DALYREJS} by default) is used as the
 *       {@code datasetBaseName} prefix for generation files.</li>
 *   <li><strong>LIMIT(5)</strong> &mdash; retain at most 5 generations. Older
 *       generations are SCRATCHed (deleted) when a new generation rolls in.
 *       This is the {@link #DEFAULT_LIMIT} value and is enforced by
 *       {@link #rollGenerations()}.</li>
 *   <li><strong>SCRATCH</strong> &mdash; physically delete generations beyond
 *       the LIMIT. {@link #rollGenerations()} uses {@link Files#delete(Path)}
 *       to honour this verbatim; nothing is archived.</li>
 * </ul>
 *
 * <p>This driver has <strong>NO underlying COBOL program</strong> &mdash; it
 * is a pure JCL/IDCAMS translation. Hence no {@code @CobolProgram} annotation
 * is applied to this class.
 *
 * <h2>Filesystem translation</h2>
 * <p>Per AAP &sect;0.1.2 mapping table ("z/OS GDG &rarr; Versioned files on a
 * normal filesystem; same naming conventions preserved") and AAP &sect;0.1.3
 * (file naming conventions are byte-for-byte preserved), generation files are
 * named:
 * <pre>
 *   &lt;datasetBaseName&gt;.G&lt;NNNN&gt;V&lt;VV&gt;
 * </pre>
 * where {@code NNNN} is the 4-digit zero-padded generation sequence number
 * (1-based, monotonically increasing) and {@code VV} is the 2-digit version
 * number. On z/OS the version is almost always {@code 00} unless the dataset
 * is explicitly re-versioned; this translation fixes the version segment at
 * {@value #DEFAULT_VERSION} (see {@link #DEFAULT_VERSION}).
 *
 * <p>Example filenames for the {@code DALYREJS} dataset:
 * <pre>
 *   DALYREJS.G0001V00   &larr; first generation ever written
 *   DALYREJS.G0002V00   &larr; second generation
 *   DALYREJS.G0003V00   &larr; current generation (most recent)
 * </pre>
 *
 * <h2>Methods provided</h2>
 * <ul>
 *   <li>{@link #resolveCurrentGeneration()} &mdash; JCL {@code DSN=<base>(0)}
 *       semantics: the most recent existing generation, or
 *       {@link Optional#empty()} if none exist.</li>
 *   <li>{@link #resolveNextGeneration()} &mdash; JCL {@code DSN=<base>(+1)}
 *       semantics: the next generation path. The file does <strong>not</strong>
 *       yet exist; the caller writes to the returned path.</li>
 *   <li>{@link #resolveGeneration(int)} &mdash; general JCL {@code DSN=<base>(n)}
 *       semantics: any relative offset (typically {@code -4..+1} when
 *       {@code LIMIT(5)} applies).</li>
 *   <li>{@link #rollGenerations()} &mdash; enforce LIMIT(n) SCRATCH semantics:
 *       physically delete any generation beyond the retention limit.</li>
 * </ul>
 *
 * <h2>Consumed by {@code PostTransactionsBatch}</h2>
 * <p>When {@code PostTransactionsBatch} writes daily rejects, the composition
 * root in {@code carddemo-app} calls {@link #resolveNextGeneration()} to get
 * the target path, hands that to the file adapter that backs the use case,
 * and after the use case completes calls {@link #rollGenerations()} to
 * enforce the retention LIMIT. The driver is therefore a small, focused
 * utility consumed by any batch step that needs GDG-equivalent versioning;
 * it does not itself run any COBOL-translated logic.
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.1.2 (Mapping table) &mdash; "z/OS GDG &rarr; Versioned
 *       files on a normal filesystem; same naming conventions preserved"</li>
 *   <li>AAP &sect;0.1.3 (Surfaced implicit requirements) &mdash; "All file
 *       naming conventions, sort orders, and batch sequencing" preserved
 *       as-is</li>
 *   <li>AAP &sect;0.6.5 (File I/O exactness) &mdash; {@code java.nio.file}
 *       only; the legacy {@code File} API is forbidden in new code</li>
 *   <li>AAP &sect;0.7.4 (Explicitly forbidden) &mdash; the legacy
 *       {@code File} API in new code</li>
 *   <li>{@code app/jcl/DALYREJS.jcl} &mdash; original GDG definition</li>
 * </ul>
 *
 * <h2>Immutability and thread safety</h2>
 * <p>All instance fields are {@code final}; the class itself is {@code final}
 * to prevent subclassing. The driver therefore presents an immutable
 * configuration surface, but it intentionally exposes side-effecting methods
 * ({@link #rollGenerations()} deletes files; {@link #resolveNextGeneration()}
 * inspects the filesystem) whose ordering relative to other writers of the
 * same dataset must be coordinated by the caller. In particular, the typical
 * usage pattern (resolve-then-write then roll) is not internally synchronized;
 * single-writer semantics are assumed.
 *
 * @since 1.0.0
 */
public final class DailyRejectsBatch {

    // -----------------------------------------------------------------------
    // Static constants
    // -----------------------------------------------------------------------

    /**
     * SLF4J logger for this driver. Used by {@link #rollGenerations()} to
     * record each SCRATCH operation at {@code INFO} level. The concrete
     * logging backend (logback-classic) is supplied at runtime by the
     * composition root in {@code carddemo-app}; this module declares only
     * the facade (slf4j-api) per AAP &sect;0.5.1.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DailyRejectsBatch.class);

    /**
     * Default GDG retention limit &mdash; matches {@code DALYREJS.jcl
     * LIMIT(5)}. When the convenience two-argument constructor is used the
     * driver applies this limit; the three-argument constructor accepts an
     * explicit override for callers that translate other GDG definitions
     * (different {@code LIMIT(n)} values).
     */
    public static final int DEFAULT_LIMIT = 5;

    /**
     * Pattern for parsing the z/OS GDG generation suffix
     * {@code .G<4-digit-seq>V<2-digit-ver>} appended to the dataset base
     * name. {@link Matcher#group(int) group(1)} captures the 4-digit
     * sequence and {@link Matcher#group(int) group(2)} captures the 2-digit
     * version. The pattern is anchored at the end of the string with
     * {@code $} so that only the trailing generation suffix is matched.
     */
    private static final Pattern GEN_PATTERN = Pattern.compile("\\.G(\\d{4})V(\\d{2})$");

    /**
     * Fixed version segment for generated filenames. z/OS GDGs use
     * {@code V00} unless the dataset is explicitly re-versioned via
     * {@code IDCAMS ALTER}; the CardDemo translation matches that default
     * verbatim. Two characters by convention.
     */
    private static final String DEFAULT_VERSION = "00";

    // -----------------------------------------------------------------------
    // Instance fields (all final)
    // -----------------------------------------------------------------------

    /**
     * Parent directory holding the generation files for this dataset. For
     * example, on a Unix host this might be
     * {@code /var/lib/carddemo/data/dalyrejs/}. Files matching
     * {@code <datasetBaseName>.G<NNNN>V<VV>} inside this directory are
     * treated as generations of this dataset.
     */
    private final Path baseDirectory;

    /**
     * The dataset base name (no generation suffix), e.g. {@code "DALYREJS"}.
     * Used both as a filter prefix when enumerating generation files in
     * {@link #listGenerationsDescending()} and as the static portion of
     * the filename produced by {@link #formatGenerationName(int)}.
     */
    private final String datasetBaseName;

    /**
     * The retention limit (GDG LIMIT) for this dataset. Generations beyond
     * the most recent {@code retentionLimit} are physically deleted when
     * {@link #rollGenerations()} runs. Defaults to {@link #DEFAULT_LIMIT}
     * (=5) when the two-argument convenience constructor is used.
     */
    private final int retentionLimit;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Constructs a driver with the default retention limit
     * ({@value #DEFAULT_LIMIT}), matching {@code DALYREJS.jcl LIMIT(5)}.
     *
     * <p>This is the canonical entry point when translating
     * {@code app/jcl/DALYREJS.jcl} verbatim. For any other GDG with a
     * different LIMIT, use the three-argument
     * {@link #DailyRejectsBatch(Path, String, int)} constructor.
     *
     * @param baseDirectory   parent directory holding the generation files;
     *                        must be non-{@code null}
     * @param datasetBaseName dataset base name (no generation suffix), e.g.
     *                        {@code "DALYREJS"}; must be non-{@code null}
     *                        and non-blank
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code datasetBaseName} is blank
     */
    public DailyRejectsBatch(Path baseDirectory, String datasetBaseName) {
        this(baseDirectory, datasetBaseName, DEFAULT_LIMIT);
    }

    /**
     * Constructs a driver with an explicit retention limit.
     *
     * <p>The arguments are validated eagerly: {@code baseDirectory} and
     * {@code datasetBaseName} must be non-{@code null};
     * {@code datasetBaseName} must additionally be non-blank;
     * {@code retentionLimit} must be {@code >= 1}. The constructor does NOT
     * verify that {@code baseDirectory} exists or is a directory &mdash;
     * that check is deferred to first use so that callers can create the
     * driver before the directory is provisioned (a legitimate scenario
     * when the directory is created by a sibling job step).
     *
     * @param baseDirectory   parent directory holding the generation files;
     *                        must be non-{@code null}
     * @param datasetBaseName dataset base name (no generation suffix);
     *                        must be non-{@code null} and non-blank
     * @param retentionLimit  max number of generations to retain (older
     *                        ones are SCRATCHed on roll); must be
     *                        {@code >= 1}
     * @throws NullPointerException     if {@code baseDirectory} or
     *                                  {@code datasetBaseName} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code datasetBaseName} is blank
     *                                  or {@code retentionLimit < 1}
     */
    public DailyRejectsBatch(Path baseDirectory, String datasetBaseName, int retentionLimit) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
        this.datasetBaseName = Objects.requireNonNull(datasetBaseName, "datasetBaseName");
        if (datasetBaseName.isBlank()) {
            throw new IllegalArgumentException("datasetBaseName must not be blank");
        }
        if (retentionLimit < 1) {
            throw new IllegalArgumentException(
                    "retentionLimit must be >= 1, got " + retentionLimit);
        }
        this.retentionLimit = retentionLimit;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Resolves the current (most recent) generation of the dataset,
     * equivalent to the JCL DD reference {@code DSN=<base>(0)}. Returns
     * {@link Optional#empty()} when no generation exists.
     *
     * <p>"Most recent" is defined by the 4-digit sequence number embedded
     * in the filename: the file with the highest sequence wins, regardless
     * of filesystem modification time. This matches z/OS GDG semantics
     * exactly &mdash; generations are ordered by their explicit sequence
     * number, not by an implicit clock.
     *
     * @return path to the current generation, or {@link Optional#empty()}
     *         if none exist
     * @throws UncheckedIOException if {@link #baseDirectory} cannot be
     *                              enumerated (wraps the underlying
     *                              {@link IOException})
     */
    public Optional<Path> resolveCurrentGeneration() {
        List<Path> descending = listGenerationsDescending();
        return descending.isEmpty() ? Optional.empty() : Optional.of(descending.get(0));
    }

    /**
     * Resolves the next generation path, equivalent to the JCL DD reference
     * {@code DSN=<base>(+1)}. Does NOT create the file; the caller writes
     * to the returned path using
     * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)}, or
     * via the file adapter that backs the use case.
     *
     * <p>Generation numbering starts at {@code 1} and is monotonically
     * increasing across the lifetime of the dataset; sequence numbers are
     * never reused, even after old generations are SCRATCHed. When no
     * generation currently exists, the next is {@code .G0001V00}.
     *
     * <p>Callers MUST call {@link #rollGenerations()} after a successful
     * write to enforce the LIMIT. The driver does not roll automatically
     * because writing the new generation might fail and leaving an unrolled
     * directory is preferable to leaving an unwritten dataset.
     *
     * @return path for the next-generation file (file does not yet exist);
     *         never {@code null}
     * @throws UncheckedIOException if {@link #baseDirectory} cannot be
     *                              enumerated to determine the highest
     *                              existing sequence number
     */
    public Path resolveNextGeneration() {
        int currentMaxSeq = listGenerationsDescending().stream()
                .findFirst()
                .map(DailyRejectsBatch::parseSeq)
                .orElse(0);
        int nextSeq = currentMaxSeq + 1;
        return baseDirectory.resolve(formatGenerationName(nextSeq));
    }

    /**
     * Resolves a relative generation. Offset {@code 0} means current,
     * {@code +1} means next (not yet created), {@code -1} means previous
     * (one before current). Negative offsets beyond the available history
     * return {@link Optional#empty()}.
     *
     * <p>This method honours the verbatim z/OS JCL DD reference convention
     * {@code DSN=<base>(n)} where {@code n} is a signed integer with the
     * following meanings:
     * <ul>
     *   <li>{@code n == +1} &rarr; the next generation, equivalent to
     *       {@link #resolveNextGeneration()}. The returned path always
     *       refers to a file that does not yet exist.</li>
     *   <li>{@code n == 0} &rarr; the current (most recent) generation,
     *       equivalent to {@link #resolveCurrentGeneration()}. Returns
     *       {@link Optional#empty()} if no generation exists.</li>
     *   <li>{@code n < 0} &rarr; a prior generation, computed by
     *       subtracting {@code |n|} from the current sequence number. If
     *       the computed sequence is less than {@code 1} or the file does
     *       not exist on the filesystem (because it has already been
     *       SCRATCHed), {@link Optional#empty()} is returned.</li>
     * </ul>
     *
     * <p>Values of {@code n > 1} are rejected with
     * {@link IllegalArgumentException}: JCL only supports {@code (+1)} as
     * the forward reference; {@code (+2)}, {@code (+3)}, etc. are not
     * defined.
     *
     * @param relativeOffset relative generation index (typically
     *                       {@code -4..+1} for {@code LIMIT(5)})
     * @return path to the resolved generation, or {@link Optional#empty()}
     *         when {@code relativeOffset <= 0} and no matching generation
     *         is present on the filesystem
     * @throws IllegalArgumentException if {@code relativeOffset > 1}
     * @throws UncheckedIOException     if {@link #baseDirectory} cannot be
     *                                  enumerated
     */
    public Optional<Path> resolveGeneration(int relativeOffset) {
        if (relativeOffset == 1) {
            return Optional.of(resolveNextGeneration());
        }
        if (relativeOffset > 1) {
            throw new IllegalArgumentException(
                    "relativeOffset > 1 is undefined; use 1 for next-generation (+1)");
        }
        List<Path> descending = listGenerationsDescending();
        if (descending.isEmpty()) {
            return Optional.empty();
        }
        int currentSeq = parseSeq(descending.get(0));
        int targetSeq = currentSeq + relativeOffset;  // relativeOffset is <= 0 here
        if (targetSeq < 1) {
            return Optional.empty();
        }
        Path candidate = baseDirectory.resolve(formatGenerationName(targetSeq));
        return Files.exists(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    /**
     * Enforces the GDG LIMIT by deleting (SCRATCHing) any generations
     * beyond the retention limit, retaining only the most recent
     * {@link #retentionLimit} generations.
     *
     * <p>Files are deleted using {@link Files#delete(Path)}; never via the
     * legacy {@code File} API (which is forbidden by AAP &sect;0.7.4).
     * Each successful deletion is logged at {@code INFO} level via the
     * SLF4J facade {@link #LOG} so that downstream observability can
     * detect retention enforcement in flight.
     *
     * <p>Behaviour matches IDCAMS {@code SCRATCH} verbatim: physical
     * deletion is unconditional; nothing is archived; no SCRATCH-skip
     * checks are performed. If a deletion fails (for example, because the
     * file is held open by another process), the underlying
     * {@link IOException} is wrapped in {@link UncheckedIOException} and
     * propagated; any generations deleted before the failure remain
     * deleted, preserving partial-progress semantics that match the
     * behaviour of the COBOL/JCL baseline.
     *
     * @return the number of generations physically removed; {@code 0} if
     *         the directory contains at most {@link #retentionLimit}
     *         generations
     * @throws UncheckedIOException if enumeration of {@link #baseDirectory}
     *                              fails, or if any deletion fails
     */
    public int rollGenerations() {
        List<Path> descending = listGenerationsDescending();
        if (descending.size() <= retentionLimit) {
            return 0;
        }
        List<Path> toRemove = descending.subList(retentionLimit, descending.size());
        int removed = 0;
        for (Path stale : toRemove) {
            try {
                Files.delete(stale);
                LOG.info("DALYREJS SCRATCH: removed stale generation {}", stale.getFileName());
                removed++;
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to SCRATCH stale generation " + stale, e);
            }
        }
        return removed;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Enumerates all generation files in {@link #baseDirectory} and
     * returns them sorted by sequence number, highest first.
     *
     * <p>Filter rule: a {@link Path} is treated as a generation of this
     * dataset if and only if its filename
     * <ol>
     *   <li>begins with {@link #datasetBaseName} immediately followed by
     *       {@code ".G"} (i.e., starts with {@code "DALYREJS.G"} for the
     *       canonical case), AND</li>
     *   <li>ends with the GDG suffix matched by {@link #GEN_PATTERN}.</li>
     * </ol>
     * The filter explicitly excludes files such as {@code DALYREJS.txt} or
     * unrelated files that happen to live in the same directory.
     *
     * <p>The {@link Stream} returned by {@link Files#list(Path)} is closed
     * via try-with-resources (AAP &sect;0.6.5 mandate). When the base
     * directory does not exist or is not a directory, an empty list is
     * returned without throwing; this matches the JCL convention that a
     * non-existent GDG base translates to "no current generation"
     * silently.
     *
     * @return generation files, sorted by sequence number descending;
     *         empty if no generations exist or if {@link #baseDirectory}
     *         is not a directory
     * @throws UncheckedIOException if directory enumeration fails for a
     *                              reason other than non-existence
     */
    private List<Path> listGenerationsDescending() {
        if (!Files.isDirectory(baseDirectory)) {
            return List.of();
        }
        String prefix = datasetBaseName + ".G";
        try (Stream<Path> stream = Files.list(baseDirectory)) {
            List<Path> generations = new ArrayList<>();
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(prefix) && GEN_PATTERN.matcher(name).find();
            }).forEach(generations::add);
            generations.sort(Comparator.comparingInt(DailyRejectsBatch::parseSeq).reversed());
            return generations;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to enumerate generations in " + baseDirectory, e);
        }
    }

    /**
     * Formats a generation filename from a 1-based sequence number.
     *
     * <p>Example: {@code formatGenerationName(7)} for the canonical
     * dataset name {@code "DALYREJS"} returns {@code "DALYREJS.G0007V00"}.
     * The sequence number is zero-padded to 4 digits via {@code %04d}; the
     * version segment is fixed at {@link #DEFAULT_VERSION} ({@code "00"}).
     *
     * @param seq the 1-based generation sequence number; must be {@code >=
     *            0} and small enough to fit in 4 digits ({@code <= 9999})
     *            for the canonical case
     * @return the generation filename (just the last path segment)
     */
    private String formatGenerationName(int seq) {
        return String.format("%s.G%04dV%s", datasetBaseName, seq, DEFAULT_VERSION);
    }

    /**
     * Parses the 4-digit sequence number out of a generation file path.
     *
     * <p>Implementation detail: this method uses {@link Matcher#find()}
     * (not {@link Matcher#matches()}) so the {@link #GEN_PATTERN} can
     * locate the suffix anywhere at the trailing end of the filename. The
     * pattern itself is anchored with {@code $} so {@code find()} still
     * only accepts the trailing suffix.
     *
     * @param generationPath a path whose final name segment ends in the
     *                       GDG suffix matched by {@link #GEN_PATTERN}
     * @return the parsed sequence number
     * @throws IllegalArgumentException if {@code generationPath} does not
     *                                  match {@link #GEN_PATTERN}
     */
    private static int parseSeq(Path generationPath) {
        String name = generationPath.getFileName().toString();
        Matcher m = GEN_PATTERN.matcher(name);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "Path does not match generation pattern: " + generationPath);
        }
        return Integer.parseInt(m.group(1));
    }
}
