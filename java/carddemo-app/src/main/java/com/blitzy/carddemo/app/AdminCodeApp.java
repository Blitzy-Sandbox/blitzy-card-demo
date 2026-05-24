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
package com.blitzy.carddemo.app;

// JEP 511 (finalized in Java 25): brings java.util.List (java.util) and
// ScopedValue (java.lang, JEP 506 final) into scope without per-class imports.
// Note: ScopedValue was moved from its preview-status location
// (java.util.concurrent.ScopedValue) to java.lang when JEP 506 was finalized
// in Java 25; java.lang is part of java.base and is therefore available via
// this single module import.
import module java.base;

import com.blitzy.carddemo.batch.BatchRunContext;
import com.blitzy.carddemo.domain.annotation.CobolProgram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the Java translation of
 * {@code app/jcl/CBADMCDJ.jcl} &mdash; the {@code DFHCSDUP} job that
 * updates the CICS CSD (Cluster System Definition) with
 * {@code LIBRARY}, {@code MAPSET}, {@code PROGRAM}, and
 * {@code TRANSACTION} entries for the {@code CARDDEMO} group, followed
 * by a {@code LIST GROUP(CARDDEMO)} verification step.
 *
 * <h2>Source artifact</h2>
 * <p>{@code app/jcl/CBADMCDJ.jcl} step {@code STEP1} invokes
 * {@code PGM=DFHCSDUP} with {@code PARM='CSD(READWRITE),PAGESIZE(60),NOCOMPAT'}
 * and feeds inline CSD-update commands via {@code //SYSIN DD *}. The
 * commands {@code DEFINE} every resource that the on-line CardDemo
 * transactions depend on and finish with {@code LIST GROUP(CARDDEMO)}.
 *
 * <h2>Translation strategy &mdash; idiom-for-idiom (AAP &sect;0.7.1)</h2>
 * <p>Per the architectural override in AAP &sect;0.6.12, there is
 * <strong>no CICS region</strong> in the Java target architecture: the
 * use-case classes in {@code carddemo-application} are invoked directly
 * (or composed by batch drivers in {@code carddemo-batch}) rather than
 * dispatched as CICS transactions. Consequently, there is no CSD to
 * update.
 *
 * <p>To preserve the JCL-step inventory mandated by AAP &sect;0.4.1
 * ("JCL jobs &rarr; Java main classes (all CREATE)") and to provide a
 * faithful, auditable record of the CSD definitions that the COBOL
 * deployment relied on, this main translates the inline DFHCSDUP
 * commands into an immutable in-memory catalog
 * ({@link #CSD_ENTRIES}) and logs every entry at runtime. The catalog
 * preserves declaration order, transaction-ID associations, and
 * duplicate entries verbatim.
 *
 * <h2>Duplicate entries preserved per AAP &sect;0.7.1</h2>
 * <p>The source CSD intentionally defines several resources twice
 * &mdash; once for the account-management menu and once for the
 * card-management menu &mdash; because both menus dispatch the same
 * underlying programs and mapsets but document the dual purpose with
 * separate {@code DESCRIPTION} clauses:
 * <ul>
 *   <li>MAPSETs duplicated: {@code COACT00S}, {@code COACTVWS},
 *       {@code COACTUPS}, {@code COACTDES} (each appears twice).</li>
 *   <li>PROGRAMs duplicated: {@code COACT00C}, {@code COACTVWC},
 *       {@code COACTUPC}, {@code COACTDEC} (each appears twice).</li>
 * </ul>
 * Per AAP &sect;0.7.1 ("If a COBOL paragraph contains dead code or
 * obvious bugs, translate it faithfully and flag it in
 * MIGRATION_NOTES.md; do not 'fix' it in this refactor") the duplicates
 * are kept and the catalog comment marks the second occurrence of each
 * pair. The deduplication decision belongs to a follow-on effort.
 *
 * <h2>Transaction-ID bindings preserved verbatim</h2>
 * <p>The CSD associates five {@code TRANSACTION} entries
 * ({@code CCDM}, {@code CCT1}, {@code CCT2}, {@code CCT3},
 * {@code CCT4}) with their target {@code PROGRAM}s and additionally
 * embeds the {@code TRANSID} of three {@code PROGRAM} definitions
 * ({@code COSGN00C}&rarr;{@code CC00}, {@code COADM00C}&rarr;{@code CCAD},
 * {@code COTSTP1C}&rarr;{@code CCT1}, {@code COTSTP2C}&rarr;{@code CCT2},
 * {@code COTSTP3C}&rarr;{@code CCT3}, {@code COTSTP4C}&rarr;{@code CCT4}).
 * All bindings are preserved exactly as they appear in the JCL.
 *
 * <h2>TDQUEUE definitions intentionally omitted</h2>
 * <p>The source JCL contains commented-out {@code TDQUEUE} definitions
 * ({@code CSSD}, {@code IRDC}). They are <strong>not translated</strong>
 * because they were already disabled in the source; preserving the
 * COBOL behavior means leaving them out, matching the documented
 * convention.
 *
 * <h2>Pattern-matching switch (JEP 441) over a sealed interface</h2>
 * <p>The translation uses a {@code sealed interface CsdEntry} with one
 * record permit per CSD command type ({@code LIBRARY}, {@code MAPSET},
 * {@code PROGRAM}, {@code TRANSACTION}, {@code LIST GROUP}). The
 * dispatch loop uses pattern matching with record patterns and
 * <strong>no {@code default} branch</strong> &mdash; exhaustiveness is
 * enforced by the compiler per AAP &sect;0.6.7 and &sect;0.7.3. Adding
 * a new CSD entry kind will require adding a permit and a matching
 * case, which is the desired behavior.
 *
 * <h2>{@code ScopedValue} batch-run context (JEP 506, AAP &sect;0.6.6)</h2>
 * <p>{@link #main(String[])} materializes a {@link BatchRunContext} via
 * {@link BatchRunContext#fromEnvironment()} and binds it through
 * {@code ScopedValue.where(BATCH_CTX, ctx).call(AdminCodeApp::execute)}
 * so the {@link #execute()} callee and any virtual threads it spawns
 * can read the context via {@link #BATCH_CTX BATCH_CTX.get()}. Per
 * AAP &sect;0.7.4, {@link ThreadLocal} is <strong>forbidden</strong> in
 * new CardDemo Java code.
 *
 * <h2>Exit-code semantics</h2>
 * <p>JCL traditionally reports return codes 0, 4, 8, 12, and 16. This
 * main returns {@code 0} on success and {@code 16} on any uncaught
 * exception, clamping any out-of-range value to {@code 16} (the
 * conventional JCL severity ceiling for terminal failures).
 *
 * <h2>Why not just no-op?</h2>
 * <p>Even though no CICS CSD update is performed, logging the catalog
 * has practical value:
 * <ul>
 *   <li>Operational diagnostics: a single command (e.g.
 *       {@code java -jar carddemo-admin-code.jar}) prints the full
 *       expected program/mapset/transaction registry.</li>
 *   <li>Migration audit: log scrapes can compare the Java-side
 *       inventory with the original CSD line-by-line.</li>
 *   <li>Stable extension point: if a future deployment ever wires a
 *       genuine resource registry (e.g.&nbsp;Hazelcast, Kubernetes
 *       ConfigMap), the iteration site is already in place.</li>
 * </ul>
 *
 * <h2>Non-goals (AAP &sect;0.2.2, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No actual CICS administration &mdash; the CICS region is out
 *       of scope per AAP &sect;0.6.12.</li>
 *   <li>No Spring, Lombok, or Apache Commons.</li>
 *   <li>No reflection.</li>
 *   <li>No preview features (no {@code --enable-preview} required).
 *       In particular, JEP 507 primitive patterns are forbidden, so
 *       integer return-code clamping uses a plain {@code switch} with
 *       a {@code default} branch (not pattern-matching on the boxed
 *       Integer).</li>
 *   <li>No {@link ThreadLocal} &mdash; {@link java.lang.ScopedValue} only,
 *       per AAP &sect;0.6.6.</li>
 * </ul>
 *
 * <h2>Shaded artifact</h2>
 * <p>Per AAP &sect;0.4.1, this composition root is packaged as a single
 * shaded jar (e.g.&nbsp;{@code carddemo-admin-code.jar}) by the
 * {@code maven-shade-plugin} configuration in
 * {@code java/carddemo-app/pom.xml}.
 *
 * @see com.blitzy.carddemo.batch.BatchRunContext
 * @see CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "DFHCSDUP (CBADMCDJ.jcl)",
        sourcePath = "app/jcl/CBADMCDJ.jcl",
        translationDate = "2025-10-24",
        notes = "JCL translation of the CICS CSD-update job. The source JCL invokes "
                + "PGM=DFHCSDUP and feeds DEFINE LIBRARY/MAPSET/PROGRAM/TRANSACTION "
                + "and LIST GROUP commands via SYSIN. Per AAP §0.6.12, the Java "
                + "target architecture has no CICS region, so this main translates "
                + "the CSD commands into an immutable in-memory catalog and logs "
                + "every entry at runtime — preserving declaration order, transaction-"
                + "ID bindings, and duplicate entries verbatim per AAP §0.7.1. "
                + "Commented-out TDQUEUE entries in the source JCL are intentionally "
                + "not translated. Exit code 0 on success, 16 on uncaught exception.")
public final class AdminCodeApp {

    // ------------------------------------------------------------------
    // Logger
    // ------------------------------------------------------------------

    /**
     * SLF4J logger for this main. All catalog entries and lifecycle
     * messages are written here at {@code INFO}. Uncaught exceptions
     * are logged at {@code ERROR} prior to the {@code System.exit(16)}
     * call in {@link #main(String[])}.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AdminCodeApp.class);

    // ------------------------------------------------------------------
    // ScopedValue for batch-run context (AAP §0.6.6 / JEP 506 Final)
    // ------------------------------------------------------------------

    /**
     * Thread-scoped binding holding the active {@link BatchRunContext}
     * for the current {@code AdminCodeApp} run. Established at
     * {@link #main(String[])} entry via
     * {@code ScopedValue.where(BATCH_CTX, ctx).call(...)} and read by
     * {@link #execute()} via {@link #BATCH_CTX BATCH_CTX.get()}.
     *
     * <p>Per AAP &sect;0.6.6 and &sect;0.7.4, this {@link ScopedValue}
     * <strong>replaces {@link ThreadLocal} entirely</strong>; the
     * binding is automatically cleared when the
     * {@code ScopedValue.where(...).call(...)} call returns.
     *
     * <p>Although the {@link BatchRunContext} class itself exposes a
     * sibling {@link BatchRunContext#BATCH_CTX} field used by the
     * shared {@code carddemo-batch} drivers, this app declares its own
     * {@code ScopedValue} so that the lifecycle of the
     * {@code AdminCodeApp} binding is scoped strictly to the
     * {@code AdminCodeApp} call chain &mdash; per AAP &sect;0.4.1's
     * one-class-per-JCL-step pattern. Both bindings are populated with
     * the same {@link BatchRunContext} value within
     * {@link #main(String[])} so callees that read either field
     * observe identical context.
     */
    public static final ScopedValue<BatchRunContext> BATCH_CTX = ScopedValue.newInstance();

    // ------------------------------------------------------------------
    // CSD entry model (sealed for compile-time exhaustiveness)
    // ------------------------------------------------------------------

    /**
     * A single DFHCSDUP command translated from
     * {@code app/jcl/CBADMCDJ.jcl}. The hierarchy is sealed so the
     * dispatch {@code switch} in {@link #execute()} is exhaustive at
     * compile time without a {@code default} branch.
     */
    private sealed interface CsdEntry
            permits CsdEntry.Library,
                    CsdEntry.Mapset,
                    CsdEntry.Program,
                    CsdEntry.Transaction,
                    CsdEntry.ListGroup {

        /** The CSD resource name (e.g.&nbsp;{@code "COACT00S"}). */
        String name();

        /**
         * A {@code DEFINE LIBRARY(name) GROUP(groupName) DSNAME01(dsname01)}
         * entry. There is exactly one such entry in the source JCL
         * ({@code COM2DOLL} pointing at {@code &HLQ..LOADLIB}).
         */
        record Library(String name, String groupName, String dsname01) implements CsdEntry {}

        /**
         * A {@code DEFINE MAPSET(name) GROUP(groupName)} entry.
         * Duplicates are permitted (the source JCL defines several
         * mapsets twice with distinct {@code DESCRIPTION} clauses).
         */
        record Mapset(String name, String groupName) implements CsdEntry {}

        /**
         * A {@code DEFINE PROGRAM(name) GROUP(groupName) [TRANSID(...)]}
         * entry. Empty {@code transid} means no implicit transaction ID
         * is bound by the {@code DEFINE PROGRAM} clause (the program
         * may still be the target of an explicit {@code DEFINE
         * TRANSACTION}).
         */
        record Program(String name, String groupName, String transid) implements CsdEntry {}

        /**
         * A {@code DEFINE TRANSACTION(name) GROUP(groupName)
         * PROGRAM(programName)} entry. Always carries the target
         * program name (the source JCL never defines a transaction
         * without a program binding).
         */
        record Transaction(String name, String groupName, String programName) implements CsdEntry {}

        /**
         * A {@code LIST GROUP(name)} verification step. The source JCL
         * issues a single {@code LIST GROUP(CARDDEMO)} at the end of
         * the script.
         */
        record ListGroup(String name) implements CsdEntry {}
    }

    // ------------------------------------------------------------------
    // Compile-time constants from the source JCL
    // ------------------------------------------------------------------

    /**
     * High-level qualifier supplied by the source JCL line
     * {@code //   SET HLQ=AWS.M2.CARDDEMO}. Used to build the
     * {@code DSNAME01} of the {@code LIBRARY(COM2DOLL)} entry.
     */
    private static final String HLQ = "AWS.M2.CARDDEMO";

    /**
     * CSD group name shared by every entry in the source JCL
     * ({@code GROUP(CARDDEMO)}).
     */
    private static final String GROUP = "CARDDEMO";

    /**
     * The complete CSD entry catalog translated from
     * {@code app/jcl/CBADMCDJ.jcl}, in source declaration order.
     *
     * <p>Per AAP &sect;0.7.1 the list preserves the duplicate
     * {@code MAPSET} and {@code PROGRAM} entries that appear in the
     * source JCL for the account-menu vs.&nbsp;card-menu wiring:
     * {@code COACT00S}, {@code COACTVWS}, {@code COACTUPS},
     * {@code COACTDES} (4 mapset duplicates) and {@code COACT00C},
     * {@code COACTVWC}, {@code COACTUPC}, {@code COACTDEC} (4 program
     * duplicates). The deduplication decision belongs to a follow-on
     * effort and is flagged in {@code java/MIGRATION_NOTES.md}.
     *
     * <p>Transaction-ID bindings preserved verbatim:
     * {@code COSGN00C&rarr;CC00}, {@code COADM00C&rarr;CCAD},
     * {@code COTSTP1C&rarr;CCT1}, {@code COTSTP2C&rarr;CCT2},
     * {@code COTSTP3C&rarr;CCT3}, {@code COTSTP4C&rarr;CCT4}, plus the
     * five explicit {@code DEFINE TRANSACTION} entries
     * {@code CCDM}, {@code CCT1}, {@code CCT2}, {@code CCT3},
     * {@code CCT4}.
     */
    private static final List<CsdEntry> CSD_ENTRIES = List.of(
            // ---------- LIBRARY ----------
            new CsdEntry.Library("COM2DOLL", GROUP, HLQ + ".LOADLIB"),

            // ---------- MAPSETs (19 entries; 4 deliberate duplicates) ----------
            new CsdEntry.Mapset("COSGN00M", GROUP),
            new CsdEntry.Mapset("COACT00S", GROUP),
            new CsdEntry.Mapset("COACT00S", GROUP),    // duplicate (card menu)
            new CsdEntry.Mapset("COACTVWS", GROUP),
            new CsdEntry.Mapset("COACTVWS", GROUP),    // duplicate (view card)
            new CsdEntry.Mapset("COACTUPS", GROUP),
            new CsdEntry.Mapset("COACTUPS", GROUP),    // duplicate (update card)
            new CsdEntry.Mapset("COACTDES", GROUP),
            new CsdEntry.Mapset("COACTDES", GROUP),    // duplicate (deactivate card)
            new CsdEntry.Mapset("COTRN00S", GROUP),
            new CsdEntry.Mapset("COTRNVWS", GROUP),
            new CsdEntry.Mapset("COTRNVDS", GROUP),
            new CsdEntry.Mapset("COTRNATS", GROUP),
            new CsdEntry.Mapset("COBIL00S", GROUP),
            new CsdEntry.Mapset("COADM00S", GROUP),
            new CsdEntry.Mapset("COTSTP1S", GROUP),
            new CsdEntry.Mapset("COTSTP2S", GROUP),
            new CsdEntry.Mapset("COTSTP3S", GROUP),
            new CsdEntry.Mapset("COTSTP4S", GROUP),

            // ---------- PROGRAMs (19 entries; 4 deliberate duplicates) ----------
            new CsdEntry.Program("COSGN00C", GROUP, "CC00"),
            new CsdEntry.Program("COACT00C", GROUP, ""),    // account menu
            new CsdEntry.Program("COACT00C", GROUP, ""),    // duplicate (card menu)
            new CsdEntry.Program("COACTVWC", GROUP, ""),    // view account
            new CsdEntry.Program("COACTVWC", GROUP, ""),    // duplicate (view card)
            new CsdEntry.Program("COACTUPC", GROUP, ""),    // update account
            new CsdEntry.Program("COACTUPC", GROUP, ""),    // duplicate (update card)
            new CsdEntry.Program("COACTDEC", GROUP, ""),    // deactivate account
            new CsdEntry.Program("COACTDEC", GROUP, ""),    // duplicate (deactivate card)
            new CsdEntry.Program("COTRN00C", GROUP, ""),
            new CsdEntry.Program("COTRNVWC", GROUP, ""),
            new CsdEntry.Program("COTRNVDC", GROUP, ""),
            new CsdEntry.Program("COTRNATC", GROUP, ""),
            new CsdEntry.Program("COBIL00C", GROUP, ""),
            new CsdEntry.Program("COADM00C", GROUP, "CCAD"),
            new CsdEntry.Program("COTSTP1C", GROUP, "CCT1"),
            new CsdEntry.Program("COTSTP2C", GROUP, "CCT2"),
            new CsdEntry.Program("COTSTP3C", GROUP, "CCT3"),
            new CsdEntry.Program("COTSTP4C", GROUP, "CCT4"),

            // ---------- TRANSACTIONs ----------
            new CsdEntry.Transaction("CCDM", GROUP, "COADM00C"),
            new CsdEntry.Transaction("CCT1", GROUP, "COTSTP1C"),
            new CsdEntry.Transaction("CCT2", GROUP, "COTSTP2C"),
            new CsdEntry.Transaction("CCT3", GROUP, "COTSTP3C"),
            new CsdEntry.Transaction("CCT4", GROUP, "COTSTP4C"),

            // ---------- LIST ----------
            new CsdEntry.ListGroup(GROUP)
    );

    // ------------------------------------------------------------------
    // Construction policy
    // ------------------------------------------------------------------

    /**
     * Private constructor to prevent instantiation. {@code AdminCodeApp}
     * is a process entry point invoked exclusively via
     * {@link #main(String[])}; it carries no per-instance state.
     */
    private AdminCodeApp() {
        // utility class — instantiation is meaningless
    }

    // ------------------------------------------------------------------
    // Process entry point
    // ------------------------------------------------------------------

    /**
     * Process entry point for the shaded
     * {@code carddemo-admin-code.jar}. Materializes a
     * {@link BatchRunContext} from JVM properties and environment
     * variables, binds it through {@link #BATCH_CTX}, and dispatches
     * the CSD-catalog log via {@link #execute()}.
     *
     * <p>Exit codes:
     * <ul>
     *   <li>{@code 0} &mdash; success (the canonical case for this
     *       CSD-logging job).</li>
     *   <li>{@code 4}, {@code 8}, {@code 12}, {@code 16} &mdash;
     *       reserved for future severity levels; pass through
     *       unchanged when returned by {@link #execute()}.</li>
     *   <li>{@code 16} &mdash; any uncaught exception, or any
     *       {@code rc} value returned by {@link #execute()} that is
     *       outside {@code [0, 16]}. Per JCL convention {@code 16} is
     *       the conventional terminal-failure severity.</li>
     * </ul>
     *
     * <p>Args are accepted but currently ignored; the future evolution
     * is intentionally left open (e.g.&nbsp;a {@code --dump-catalog}
     * flag could format the catalog as JSON for downstream tooling).
     *
     * @param args command-line arguments; currently ignored
     */
    public static void main(String[] args) {
        // 1. Materialize the batch-run context from JVM properties + env vars.
        //    fromEnvironment() never returns null and never throws on
        //    missing configuration; it generates a UUID-based runId and
        //    defaults processingDate to LocalDate.now() per the documented
        //    contract in BatchRunContext.
        BatchRunContext ctx = BatchRunContext.fromEnvironment();

        // 2. Bind the context through both ScopedValues (this app's own
        //    BATCH_CTX and the shared BatchRunContext.BATCH_CTX) so any
        //    callee — whether it reads our binding or the shared one —
        //    observes identical state. Nesting the where(...) carriers
        //    is the AAP §0.6.6 / JEP 506 idiom; both bindings have the
        //    same lifetime as the .call(...) invocation.
        int rc;
        try {
            rc = ScopedValue
                    .where(BATCH_CTX, ctx)
                    .where(BatchRunContext.BATCH_CTX, ctx)
                    .call(AdminCodeApp::execute);
        } catch (Exception e) {
            // Catch-all: any unchecked exception thrown by execute() or any
            // checked exception declared by ScopedValue.call(...) lands here.
            LOG.error("CBADMCDJ job failed with uncaught exception", e);
            rc = 16;
        }

        // 3. Clamp the return code to the conventional JCL severity range
        //    [0, 16]. Implemented as a plain `int` switch with a default
        //    branch — NOT a pattern-matching switch on Integer — because
        //    JEP 507 (Primitive Patterns) is a preview feature and is
        //    explicitly FORBIDDEN by AAP §0.7.4. The semantics of this
        //    switch are equivalent to:
        //        (rc < 0 || rc > 16) ? 16 : rc
        //    but the switch form makes the conventional COBOL/JCL return
        //    codes (0, 4, 8, 12, 16) explicit at the call site.
        int exitCode = switch (rc) {
            case 0, 4, 8, 12, 16 -> rc;
            default -> {
                if (rc > 0 && rc < 16) {
                    // Non-standard but in-range: pass through unchanged so
                    // a future caller that returns, e.g., rc=2 is not
                    // silently clobbered.
                    yield rc;
                }
                // Negative or > 16: clamp to the JCL terminal-failure
                // severity ceiling.
                yield 16;
            }
        };
        System.exit(exitCode);
    }

    // ------------------------------------------------------------------
    // Scoped-value callee
    // ------------------------------------------------------------------

    /**
     * Logs the full CSD catalog. Reads the active
     * {@link BatchRunContext} from {@link #BATCH_CTX} and emits one
     * structured INFO line per CSD entry, then a single summary line
     * with counts per entry kind.
     *
     * <p>Returns {@code 0} on success. (No partial-failure path exists
     * today because the catalog is an immutable in-memory literal that
     * cannot fail to read; the method signature preserves the
     * {@code int}-return convention so a future implementation that
     * actually talks to an external registry can return non-zero
     * severities without changing the caller.)
     *
     * @return {@code 0} on success
     */
    private static int execute() {
        BatchRunContext ctx = BATCH_CTX.get();
        LOG.info(
                "CBADMCDJ job starting; runId={}, processingDate={}, tenant={}, hlq={}, group={}",
                ctx.runId(), ctx.processingDate(), ctx.tenant(), HLQ, GROUP);

        int librarySeen = 0;
        int mapsetSeen = 0;
        int programSeen = 0;
        int transactionSeen = 0;
        int listSeen = 0;

        for (CsdEntry entry : CSD_ENTRIES) {
            // Pattern-matching switch with record patterns (JEP 441,
            // finalized in Java 21). The sealed CsdEntry hierarchy makes
            // this switch exhaustive at compile time; NO default branch
            // is permitted per AAP §0.6.7 — adding a new permit will
            // force every dispatch site (including this one) to add a
            // matching case.
            switch (entry) {
                case CsdEntry.Library(String n, String g, String ds) -> {
                    LOG.info("DEFINE LIBRARY name={}, group={}, dsname01={}", n, g, ds);
                    librarySeen++;
                }
                case CsdEntry.Mapset(String n, String g) -> {
                    LOG.info("DEFINE MAPSET name={}, group={}", n, g);
                    mapsetSeen++;
                }
                case CsdEntry.Program(String n, String g, String tid) -> {
                    LOG.info(
                            "DEFINE PROGRAM name={}, group={}, transid={}",
                            n, g, tid.isEmpty() ? "<none>" : tid);
                    programSeen++;
                }
                case CsdEntry.Transaction(String n, String g, String p) -> {
                    LOG.info("DEFINE TRANSACTION name={}, group={}, program={}", n, g, p);
                    transactionSeen++;
                }
                case CsdEntry.ListGroup(String n) -> {
                    LOG.info("LIST GROUP({})", n);
                    listSeen++;
                }
                // NO default branch — exhaustiveness enforced by the
                // sealed CsdEntry interface per AAP §0.6.7.
            }
        }

        LOG.info(
                "CBADMCDJ job complete; library={}, mapsets={}, programs={}, transactions={}, list={}; rc=0",
                librarySeen, mapsetSeen, programSeen, transactionSeen, listSeen);
        return 0;
    }
}
