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
package com.blitzy.carddemo.domain.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Traceability annotation tying a translated Java element back to its original
 * COBOL source. Apply this annotation to every Java type (class, record, sealed
 * interface, enum), package, or method that was derived from a COBOL
 * {@code PROGRAM-ID}, copybook 01-level group, or named paragraph.
 *
 * <p>This annotation has <strong>no runtime behavior</strong>. Its sole purpose
 * is to document the provenance of translated code for human readers, IDE
 * tooling, javadoc-generated reports, and static analyzers. It satisfies the
 * user mandate from the Agent Action Plan (AAP) &sect;0.7.1: <em>"Document every
 * translated program with a Javadoc header citing the original PROGRAM-ID,
 * source file path, and the date of translation."</em>
 *
 * <h2>Example &mdash; class-level annotation on a translated program</h2>
 * <pre>{@code
 * @CobolProgram(
 *     value = "CBACT01C",
 *     sourcePath = "app/cbl/CBACT01C.cbl",
 *     translationDate = "2025-10-15"
 * )
 * public final class CbAct01C {
 *     // translated paragraphs as private methods ...
 * }
 * }</pre>
 *
 * <h2>Example &mdash; method-level annotation on a translated paragraph</h2>
 * <pre>{@code
 * @CobolProgram(
 *     value = "1500-VALIDATE-TRAN",
 *     sourcePath = "app/cbl/CBTRN02C.cbl",
 *     notes = "Paragraph from CBTRN02C; see lines L236-L300"
 * )
 * private ValidationResult validateTransaction(DalyTranRecord input) {
 *     // ...
 * }
 * }</pre>
 *
 * <h2>Example &mdash; annotation on a translated copybook record</h2>
 * <pre>{@code
 * @CobolProgram(
 *     value = "CVACT01Y",
 *     sourcePath = "app/cpy/CVACT01Y.cpy",
 *     translationDate = "2025-10-15",
 *     notes = "300-byte ACCOUNT-RECORD with 5 BigDecimal monetary fields"
 * )
 * public record AccountRecord(
 *         long acctId,
 *         char acctActiveStatus,
 *         BigDecimal acctCurrBal
 *         // ... remaining fields elided for brevity
 * ) {
 *     // ...
 * }
 * }</pre>
 *
 * <h2>Example &mdash; shorthand on the required member</h2>
 * The single required member is named {@code value}, which enables the
 * shorthand form (per JLS &sect;9.7.3):
 * <pre>{@code
 * @CobolProgram("COSGN00C")
 * public final class CoSgn00C {
 *     // ... translated paragraphs ...
 * }
 * }</pre>
 *
 * <h2>Retention</h2>
 * This annotation uses {@link RetentionPolicy#SOURCE SOURCE} retention. It is
 * available to the compiler and to source-level tooling (IDEs, javadoc, static
 * analyzers) but is <strong>not retained in compiled class files</strong>. This
 * is the conservative choice: this annotation triggers no runtime behavior and
 * should not bloat bytecode. If a future requirement needs runtime reflection
 * (for example, a self-documenting boot banner that enumerates every
 * translated program), the retention can be promoted to
 * {@link RetentionPolicy#RUNTIME RUNTIME} in a controlled change.
 *
 * <h2>Targets</h2>
 * The annotation may be applied to {@link ElementType#TYPE types} (classes,
 * interfaces, records, enums), {@link ElementType#PACKAGE packages} (via
 * {@code package-info.java}), and {@link ElementType#METHOD methods}. This
 * breadth supports:
 * <ul>
 *   <li>Class-level annotation for translated programs (one COBOL
 *       {@code PROGRAM-ID} per Java class, per AAP &sect;0.1.1).</li>
 *   <li>Method-level annotation for translated paragraphs (each public method
 *       maps to an entry paragraph; each private method maps to an internal
 *       paragraph, per AAP &sect;0.1.2).</li>
 *   <li>Package-level annotation (via {@code package-info.java}) when an
 *       entire Java package represents a translated COBOL module group
 *       (e.g., the {@code account} package corresponds to several COBOL
 *       account programs).</li>
 *   <li>Record-level annotation for translated copybook 01-level groups,
 *       per AAP &sect;0.3.2.</li>
 *   <li>Sealed-interface annotation for translated {@code REDEFINES} or
 *       88-level taxonomies, per AAP &sect;0.6.2 and &sect;0.6.10.</li>
 * </ul>
 *
 * <h2>Why not {@code @Inherited}?</h2>
 * This annotation is intentionally <strong>not</strong> marked
 * {@link java.lang.annotation.Inherited @Inherited}. Subclasses of a translated
 * program rarely exist (the migration uses composition over inheritance), and
 * if a subclass appears it most likely represents a derived concept that
 * deserves its own provenance annotation, not the parent's.
 *
 * <h2>Why not {@code @Repeatable}?</h2>
 * A single COBOL artifact per Java element is the common case. When a Java
 * class derives from multiple COBOL artifacts (e.g., a use case that fuses
 * paragraphs from two programs), record the primary artifact in
 * {@link #value()} and the secondary references in {@link #notes()}. This
 * keeps the annotation simple and avoids the complexity of container
 * annotations.
 *
 * @see <a href="https://cf-workers-proxy-9e9.pages.dev/aws-mainframe-modernization/aws-card-demo">Original
 *      CardDemo COBOL source</a>
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.METHOD})
public @interface CobolProgram {

    /**
     * The COBOL {@code PROGRAM-ID}, copybook name, or paragraph identifier
     * that this Java element was translated from. This is the only required
     * member.
     *
     * <p>Examples: {@code "CBACT01C"}, {@code "COSGN00C"}, {@code "CVACT01Y"},
     * {@code "1500-VALIDATE-TRAN"}.
     *
     * <p>Use the exact COBOL identifier (uppercase) so that text searches for
     * a COBOL name will find both the original source and every Java
     * translation site.
     *
     * @return the original COBOL PROGRAM-ID, copybook name, or paragraph name
     */
    String value();

    /**
     * The repository-relative path to the original COBOL source file.
     * Optional; defaults to an empty string when the path can be derived
     * unambiguously from {@link #value()} (e.g., a program named
     * {@code CBACT01C} is always at {@code app/cbl/CBACT01C.cbl}).
     *
     * <p>Examples: {@code "app/cbl/CBACT01C.cbl"},
     * {@code "app/cpy/CVACT01Y.cpy"}, {@code "app/bms/COSGN00.bms"}.
     *
     * <p>The path is forward-slash separated and uses the same casing as the
     * actual file on disk (the COBOL source repository contains both
     * {@code .cbl} (lowercase) and {@code .CBL} (uppercase) files; preserve
     * the exact casing).
     *
     * @return the repository-relative source path, or empty string if not
     *         specified
     */
    String sourcePath() default "";

    /**
     * The ISO-8601 date (YYYY-MM-DD) on which this element was translated.
     * Optional; defaults to an empty string when translation date is not
     * tracked at the element level.
     *
     * <p>Examples: {@code "2025-10-15"}, {@code "2025-11-03"}.
     *
     * <p>This date is informational. It supports a future audit/migration
     * report that can list translation cadence per program. It is
     * <strong>not</strong> a build timestamp and is <strong>not</strong>
     * parsed at compile time; it is a free-form string that callers are
     * expected to format per ISO-8601 by convention.
     *
     * @return the ISO-8601 translation date, or empty string if not specified
     */
    String translationDate() default "";

    /**
     * Free-form notes about the translation. Optional; defaults to an empty
     * string.
     *
     * <p>Use this member to document:
     * <ul>
     *   <li>Deviations from idiom-for-idiom translation
     *       (e.g., {@code "DEVIATION: legacy z/OS TIOT inspection is a no-op
     *       in Java"}).</li>
     *   <li>Suspected COBOL bugs that have been translated faithfully
     *       (e.g., {@code "SUSPECTED BUG: dataset name mismatch between
     *       DELETE and DEFINE; preserved as-is, see MIGRATION_NOTES.md"}).</li>
     *   <li>Paragraph-level references for method-level annotations
     *       (e.g., {@code "Translated from paragraph 2700-UPDATE-TCATBAL"}).</li>
     *   <li>Cross-references to other translated artifacts
     *       (e.g., {@code "Calls into CBSTM03B; see CbStm03B"}).</li>
     *   <li>Secondary COBOL artifacts when a Java element fuses several
     *       (e.g., {@code "Also incorporates CSMSG02Y constants"}).</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.7.1, all such deviations and suspected bugs MUST also
     * be logged in {@code java/MIGRATION_NOTES.md}; this {@code notes} member
     * is a pointer back from the code to the migration log.
     *
     * @return free-form translator notes, or empty string if not specified
     */
    String notes() default "";
}
