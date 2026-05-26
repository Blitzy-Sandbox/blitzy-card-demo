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
 */
package com.blitzy.carddemo.application.statement;

import module java.base;  // JEP 511 — Module Import Declaration finalized in Java 25

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.record.AccountRecord;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.CustomerLegacyRecord;
import com.blitzy.carddemo.domain.record.TrnxRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java translation of the {@code CBSTM03B} COBOL callable file-services
 * subroutine at {@code app/cbl/CBSTM03B.CBL}.
 *
 * <h2>Program purpose</h2>
 * <p>CBSTM03B is a <em>file-services dispatcher</em> called by
 * {@link CbStm03A} (the statement-generation driver) to {@code OPEN},
 * {@code READ}, {@code READ-K} (random by key), and {@code CLOSE} four
 * logical datasets:
 * <ul>
 *   <li>{@code TRNXFILE} &mdash; transaction-by-card sequential
 *       (350-byte records, COSTM01 layout)</li>
 *   <li>{@code XREFFILE} &mdash; card cross-reference sequential
 *       (50-byte records, CVACT03Y layout)</li>
 *   <li>{@code CUSTFILE} &mdash; customer random-by-key
 *       (500-byte records, CUSTREC layout, keyed by FD-CUST-ID)</li>
 *   <li>{@code ACCTFILE} &mdash; account random-by-key
 *       (300-byte records, CVACT01Y layout, keyed by FD-ACCT-ID)</li>
 * </ul>
 *
 * <h2>COBOL LINKAGE SECTION (LK-M03B-AREA)</h2>
 * <pre>{@code
 *   05 LK-M03B-DD          PIC X(08).        file id ("TRNXFILE" etc.)
 *   05 LK-M03B-OPER        PIC X(01).        operation code
 *       88 M03B-OPEN       VALUE 'O'.
 *       88 M03B-CLOSE      VALUE 'C'.
 *       88 M03B-READ       VALUE 'R'.
 *       88 M03B-READ-K     VALUE 'K'.
 *       88 M03B-WRITE      VALUE 'W'.
 *       88 M03B-REWRITE    VALUE 'Z'.
 *   05 LK-M03B-RC          PIC X(02).        return code
 *   05 LK-M03B-KEY         PIC X(25).        random-access key
 *   05 LK-M03B-KEY-LN      PIC S9(4).
 *   05 LK-M03B-FLDT        PIC X(1000).      payload buffer
 * }</pre>
 *
 * <h2>Java translation strategy</h2>
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.8 this class is translated as a
 * <em>utility class</em>. The COBOL WS-M03B-AREA dispatch parameter
 * (operation, file id, key, return code) is decomposed into typed public
 * methods (per AAP &sect;0.4.2 "Static <code>CALL 'CBSTM03B' USING ...</code>
 * &rarr; direct method call on constructor-injected collaborator"):
 * <ul>
 *   <li>{@link #openFile(String)} / {@link #openFile(Cbstm03BFileId)}
 *       &mdash; OPEN dispatch (replaces {@code MOVE 'O' TO LK-M03B-OPER}
 *       followed by {@code CALL 'CBSTM03B'}).</li>
 *   <li>{@link #closeFile(String)} / {@link #closeFile(Cbstm03BFileId)}
 *       &mdash; CLOSE dispatch.</li>
 *   <li>{@link #readNextTransaction()} / {@link #readNextXref()} &mdash;
 *       sequential READ for TRNXFILE / XREFFILE. Returns {@link Optional}
 *       to model "found vs end-of-file" without an integer RC.</li>
 *   <li>{@link #readCustomerByKey(long)} /
 *       {@link #readAccountByKey(long)} &mdash; keyed (random) READ-K for
 *       CUSTFILE / ACCTFILE. Returns {@link Optional} to model "found
 *       vs not-found".</li>
 *   <li>{@link #dispatch(Cbstm03BFileId, Cbstm03BOperation)} &mdash;
 *       generic dispatcher returning an {@code int} RC, for call sites
 *       that need to mirror the WS-M03B-AREA pattern verbatim (e.g.,
 *       dynamic-dispatch test harnesses).</li>
 * </ul>
 *
 * <h2>Closed taxonomies</h2>
 * <p>Per AAP &sect;0.1.3 (closed business taxonomies are exhaustive) the
 * COBOL LK-M03B-OPER 88-levels and LK-M03B-DD discrete file identifiers
 * become sealed interfaces &mdash; {@link Cbstm03BOperation} and
 * {@link Cbstm03BFileId} &mdash; so the Java compiler enforces every
 * pattern-matching switch site.
 *
 * <h2>Lifecycle</h2>
 * <p>CBSTM03B is a SUBROUTINE called by CBSTM03A &mdash; its lifetime and
 * state are owned by the caller. The Java translation uses a regular
 * class instance whose state (open channels, in-memory key indexes) is
 * reset between calls via close+open cycles. Mutators on the four
 * mutable fields ({@code trnxChannel}, {@code xrefChannel},
 * {@code custIndex}, {@code acctIndex}) are documented at each callsite;
 * the class is not thread-safe and must be confined to a single thread
 * per instance (matching the COBOL single-task call model).
 *
 * <h2>Forbidden idioms (AAP &sect;0.6.7, &sect;0.7.4)</h2>
 * <ul>
 *   <li>No Spring &mdash; constructor injection only.</li>
 *   <li>No {@link java.io.File} &mdash; {@link java.nio.file.Path} +
 *       {@link java.nio.file.Files} only.</li>
 *   <li>No {@link java.util.Date} / {@link java.util.Calendar} &mdash;
 *       {@link java.time} only (no date/time fields in this file
 *       anyway).</li>
 *   <li>No {@code ThreadLocal} &mdash; no cross-method context propagation
 *       is required here; if it were, {@code ScopedValue} would be used.</li>
 *   <li>No reflection, no dynamic proxies.</li>
 *   <li>No {@code default} branches on sealed-type switches &mdash;
 *       compiler-enforced exhaustiveness is the safety guarantee. The
 *       one {@code default} in {@link Cbstm03BFileId#fromTag(String)} is
 *       defensive validation over an arbitrary {@link String} input
 *       (open universe), not a sealed-type switch.</li>
 * </ul>
 *
 * @see CbStm03A
 * @see Cbstm03BOperation
 * @see Cbstm03BFileId
 * @since 1.0.0
 */
@CobolProgram(
        value = "CBSTM03B",
        sourcePath = "app/cbl/CBSTM03B.CBL",
        translationDate = "2025-01-15",
        notes = "Callable file-services subroutine providing OPEN/READ/READ-K/CLOSE primitives "
              + "for TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE. Translated as a utility class per AAP §0.4.1. "
              + "Encapsulates direct java.nio.file I/O for the four files because: "
              + "(1) no TrnxRepository port exists for TRNXFILE (350-byte COSTM01 layout); "
              + "(2) the COBOL CBSTM03B owns its own FD declarations independent of the application's "
              + "repository abstractions, and preserving that architectural property maintains byte-fidelity. "
              + "The WS-M03B-AREA dispatch parameter (operation, file id, key, return code) is decomposed "
              + "into typed public methods (openFile/readNext*/readByKey*/closeFile) per AAP §0.4.2. "
              + "Sealed types Cbstm03BOperation and Cbstm03BFileId model the closed taxonomies for "
              + "compiler-enforced exhaustiveness checking."
)
public final class CbStm03B {

    // =====================================================================
    // Class-level constants — identity, file tags, operation codes, return
    // codes, and fixed-width record lengths. All are public so that callers
    // and tests can reference them symbolically (e.g., openFile(FILE_TAG_TRNXFILE)).
    // =====================================================================

    /**
     * Program identity literal from CBSTM03B.CBL line 2 ({@code PROGRAM-ID.
     * CBSTM03B.}). Exposed for diagnostic logging and for callers that
     * need to identify the subroutine in their own log lines.
     */
    public static final String LIT_THIS_PGM = "CBSTM03B";

    // ---- File-name dispatch tags (LK-M03B-DD literal values) ------------
    // The COBOL caller (CBSTM03A) sets LK-M03B-DD to one of these 8-character
    // values before invoking CBSTM03B. The values are matched verbatim by the
    // 0000-START EVALUATE in CBSTM03B.CBL lines 118-128.

    /** COBOL DD name for TRNXFILE (CBSTM03B.CBL line 119). */
    public static final String FILE_TAG_TRNXFILE = "TRNXFILE";
    /** COBOL DD name for XREFFILE (CBSTM03B.CBL line 121). */
    public static final String FILE_TAG_XREFFILE = "XREFFILE";
    /** COBOL DD name for CUSTFILE (CBSTM03B.CBL line 123). */
    public static final String FILE_TAG_CUSTFILE = "CUSTFILE";
    /** COBOL DD name for ACCTFILE (CBSTM03B.CBL line 125). */
    public static final String FILE_TAG_ACCTFILE = "ACCTFILE";

    // ---- Operation codes (LK-M03B-OPER 88-level character values) -------
    // From CBSTM03B.CBL lines 102-108:
    //   88  M03B-OPEN       VALUE 'O'.
    //   88  M03B-CLOSE      VALUE 'C'.
    //   88  M03B-READ       VALUE 'R'.
    //   88  M03B-READ-K     VALUE 'K'.
    //   88  M03B-WRITE      VALUE 'W'.
    //   88  M03B-REWRITE    VALUE 'Z'.

    /** Operation code: open file ({@code M03B-OPEN VALUE 'O'}). */
    public static final char OPER_OPEN     = 'O';
    /** Operation code: close file ({@code M03B-CLOSE VALUE 'C'}). */
    public static final char OPER_CLOSE    = 'C';
    /** Operation code: sequential read ({@code M03B-READ VALUE 'R'}). */
    public static final char OPER_READ     = 'R';
    /** Operation code: random read by key ({@code M03B-READ-K VALUE 'K'}). */
    public static final char OPER_READ_KEY = 'K';
    /**
     * Operation code: write ({@code M03B-WRITE VALUE 'W'}). Declared in the
     * COBOL LK-M03B-OPER 88-level set but never dispatched by any of the
     * four paragraphs (1000-TRNXFILE-PROC, 2000-XREFFILE-PROC,
     * 3000-CUSTFILE-PROC, 4000-ACCTFILE-PROC); preserved for completeness.
     */
    public static final char OPER_WRITE    = 'W';
    /**
     * Operation code: rewrite ({@code M03B-REWRITE VALUE 'Z'}). Declared in
     * the COBOL LK-M03B-OPER 88-level set but never dispatched; preserved
     * for completeness.
     */
    public static final char OPER_REWRITE  = 'Z';

    // ---- Return codes ---------------------------------------------------
    // The COBOL CBSTM03B copies the relevant FILE STATUS (a 2-char field)
    // into LK-M03B-RC at each paragraph exit. The Java translation maps
    // FILE STATUS semantics to ints:
    //   "00" success         -> RC_OK         = 0
    //   "10" end-of-file     -> RC_EOF        = 16  (per file-status to int convention)
    //   "23" record not found -> RC_NOT_FOUND  = 23
    //   other I/O failure    -> RC_ERROR      = 12

    /** Return code: success ({@code FILE STATUS '00'}). */
    public static final int RC_OK         = 0;
    /** Return code: end-of-file ({@code FILE STATUS '10'}). */
    public static final int RC_EOF        = 16;
    /** Return code: record not found ({@code FILE STATUS '23'}). */
    public static final int RC_NOT_FOUND  = 23;
    /** Return code: I/O error or unsupported operation. */
    public static final int RC_ERROR      = 12;

    // ---- Fixed-width record lengths (from FD declarations in CBSTM03B.CBL)
    // CBSTM03B.CBL lines 58-78:
    //   FD-TRNXFILE-REC = TRNX-KEY (32) + FD-ACCT-DATA PIC X(318) = 350
    //   FD-XREFFILE-REC = FD-XREF-CARD-NUM PIC X(16) + FD-XREF-DATA PIC X(34) = 50
    //   FD-CUSTFILE-REC = FD-CUST-ID PIC X(09) + FD-CUST-DATA PIC X(491) = 500
    //   FD-ACCTFILE-REC = FD-ACCT-ID PIC 9(11) + FD-ACCT-DATA PIC X(289) = 300

    /** Fixed record length of TRNXFILE (350 bytes per COSTM01 layout). */
    public static final int TRNX_RECORD_LENGTH = 350;
    /** Fixed record length of XREFFILE (50 bytes per CVACT03Y layout). */
    public static final int XREF_RECORD_LENGTH = 50;

    /**
     * Alternative XREFFILE record length used by the ASCII fixture
     * {@code app/data/ASCII/cardxref.txt} which omits the trailing
     * 14-byte {@code FILLER PIC X(14)} group from
     * {@code app/cpy/CVACT03Y.cpy}. The fixture records are
     * {@code XREF-CARD-NUM PIC X(16)} + {@code XREF-CUST-ID PIC 9(09)}
     * + {@code XREF-ACCT-ID PIC 9(11)} = 36 bytes. Detected at
     * {@link #openXrefFile()} time; records are space-padded to
     * {@link #XREF_RECORD_LENGTH} (50) before being handed to
     * {@link CardXrefRecord#parse(byte[])}.
     */
    private static final int XREF_FIXTURE_RECORD_LENGTH = 36;
    /** Fixed record length of CUSTFILE (500 bytes per CUSTREC layout). */
    public static final int CUST_RECORD_LENGTH = 500;
    /** Fixed record length of ACCTFILE (300 bytes per CVACT01Y layout). */
    public static final int ACCT_RECORD_LENGTH = 300;

    // =====================================================================
    // Sealed-type taxonomies — closed business taxonomies per AAP §0.1.3
    // and §0.6.10. Translated from the COBOL LK-M03B-OPER 88-level family
    // (Cbstm03BOperation) and LK-M03B-DD discrete file identifiers
    // (Cbstm03BFileId). Defined as nested public sealed interfaces so they
    // can access the OPER_* / FILE_TAG_* enclosing constants directly.
    // =====================================================================

    /**
     * Closed taxonomy of CBSTM03B operations, modeling the COBOL
     * {@code LK-M03B-OPER} 88-levels at CBSTM03B.CBL lines 102&ndash;108.
     *
     * <p>Per AAP &sect;0.1.3 ("closed business taxonomies are exhaustive")
     * and &sect;0.6.10 (sealed-type hierarchies introduced), translated as
     * a sealed interface with one record permit per operation. The COBOL
     * declares six operation codes ({@code O}, {@code C}, {@code R},
     * {@code K}, {@code W}, {@code Z}) but only OPEN / CLOSE / READ /
     * READ-K are dispatched anywhere in CBSTM03B; per AAP &sect;0.7.1
     * ("make only minimal necessary changes; do not speculate") only the
     * four <em>actually dispatched</em> operations are permitted here.
     * WRITE and REWRITE are tracked as character constants
     * ({@link #OPER_WRITE}, {@link #OPER_REWRITE}) for byte-fidelity but
     * are not modelled as permitted alternatives until a real caller
     * needs them.
     *
     * <h2>Usage</h2>
     * <pre>{@code
     * int rc = cbStm03B.dispatch(
     *     Cbstm03BFileId.TrnxFile.INSTANCE,
     *     Cbstm03BOperation.Open.INSTANCE);
     * }</pre>
     *
     * @see Cbstm03BFileId
     */
    public sealed interface Cbstm03BOperation
            permits Cbstm03BOperation.Open,
                    Cbstm03BOperation.Read,
                    Cbstm03BOperation.ReadByKey,
                    Cbstm03BOperation.Close {

        /**
         * Returns the single-character COBOL operation code (the
         * {@code LK-M03B-OPER} 88-level VALUE).
         *
         * @return the operation character (one of {@code 'O'}, {@code 'R'},
         *         {@code 'K'}, {@code 'C'})
         */
        char code();

        /**
         * The OPEN operation ({@code M03B-OPEN VALUE 'O'} &mdash;
         * CBSTM03B.CBL line 103). Acquires a file handle for the target
         * file via {@link Files#newByteChannel(java.nio.file.Path,
         * java.nio.file.OpenOption...)} (sequential files) or by building
         * an in-memory keyed index (random-access files).
         */
        record Open() implements Cbstm03BOperation {
            /**
             * Canonical singleton. The Open operation carries no payload,
             * so a single immutable instance is sufficient.
             */
            public static final Open INSTANCE = new Open();
            @Override public char code() { return OPER_OPEN; }
        }

        /**
         * The sequential READ operation ({@code M03B-READ VALUE 'R'} &mdash;
         * CBSTM03B.CBL line 105). Valid for TRNXFILE and XREFFILE only;
         * CUSTFILE and ACCTFILE are RANDOM access in the COBOL FD
         * declarations and reject this operation.
         */
        record Read() implements Cbstm03BOperation {
            /** Canonical singleton. */
            public static final Read INSTANCE = new Read();
            @Override public char code() { return OPER_READ; }
        }

        /**
         * The keyed READ-K operation ({@code M03B-READ-K VALUE 'K'} &mdash;
         * CBSTM03B.CBL line 106). Valid for CUSTFILE (key &equiv;
         * FD-CUST-ID, PIC X(09)) and ACCTFILE (key &equiv; FD-ACCT-ID,
         * PIC 9(11)). The {@code key} field carries the lookup key as a
         * trimmed numeric string &mdash; the dispatcher parses it to
         * {@code long}.
         *
         * @param key the lookup key (will be parsed to {@code long});
         *            must not be {@code null}
         */
        record ReadByKey(String key) implements Cbstm03BOperation {
            /**
             * Compact canonical constructor enforcing non-null key per
             * AAP &sect;0.7.1 (preserve COBOL guard semantics).
             */
            public ReadByKey {
                Objects.requireNonNull(key, "key");
            }
            @Override public char code() { return OPER_READ_KEY; }
        }

        /**
         * The CLOSE operation ({@code M03B-CLOSE VALUE 'C'} &mdash;
         * CBSTM03B.CBL line 104). Releases the file handle (sequential
         * files) or drops the in-memory keyed index (random-access files).
         */
        record Close() implements Cbstm03BOperation {
            /** Canonical singleton. */
            public static final Close INSTANCE = new Close();
            @Override public char code() { return OPER_CLOSE; }
        }
    }

    /**
     * Closed taxonomy of file identifiers handled by CBSTM03B, modeling the
     * COBOL {@code LK-M03B-DD} discrete values dispatched at
     * CBSTM03B.CBL lines 118&ndash;128 (the {@code 0000-START EVALUATE
     * LK-M03B-DD} statement).
     *
     * <p>Per AAP &sect;0.1.3 ("closed business taxonomies are exhaustive")
     * translated as a sealed interface with one record permit per file.
     * The {@code WHEN OTHER GO TO 9999-GOBACK} fall-through clause in the
     * COBOL is realized by the {@code default} branch in
     * {@link #fromTag(String)}, which throws {@link IllegalArgumentException}
     * for an unknown tag. Note: that {@code default} is defensive
     * validation over an arbitrary {@link String} (open universe), NOT a
     * sealed-type switch &mdash; sealed-type pattern-matching switches
     * over the typed {@link Cbstm03BFileId} instances themselves must NOT
     * have {@code default} (per AAP &sect;0.6.7).
     *
     * <h2>Singleton instances</h2>
     * <p>Each permitted record exposes a public {@code INSTANCE} constant
     * because the records carry no payload &mdash; reusing the singleton
     * avoids allocation churn for what is fundamentally an enum-like
     * dispatch token.
     *
     * @see Cbstm03BOperation
     */
    public sealed interface Cbstm03BFileId
            permits Cbstm03BFileId.TrnxFile,
                    Cbstm03BFileId.XrefFile,
                    Cbstm03BFileId.CustFile,
                    Cbstm03BFileId.AcctFile {

        /**
         * Returns the 8-character COBOL DD name for this file (one of
         * {@link CbStm03B#FILE_TAG_TRNXFILE},
         * {@link CbStm03B#FILE_TAG_XREFFILE},
         * {@link CbStm03B#FILE_TAG_CUSTFILE},
         * {@link CbStm03B#FILE_TAG_ACCTFILE}).
         *
         * @return the COBOL DD name (never {@code null})
         */
        String tag();

        /** TRNXFILE identifier (matches {@link CbStm03B#FILE_TAG_TRNXFILE}). */
        record TrnxFile() implements Cbstm03BFileId {
            /** Canonical singleton. */
            public static final TrnxFile INSTANCE = new TrnxFile();
            @Override public String tag() { return FILE_TAG_TRNXFILE; }
        }

        /** XREFFILE identifier (matches {@link CbStm03B#FILE_TAG_XREFFILE}). */
        record XrefFile() implements Cbstm03BFileId {
            /** Canonical singleton. */
            public static final XrefFile INSTANCE = new XrefFile();
            @Override public String tag() { return FILE_TAG_XREFFILE; }
        }

        /** CUSTFILE identifier (matches {@link CbStm03B#FILE_TAG_CUSTFILE}). */
        record CustFile() implements Cbstm03BFileId {
            /** Canonical singleton. */
            public static final CustFile INSTANCE = new CustFile();
            @Override public String tag() { return FILE_TAG_CUSTFILE; }
        }

        /** ACCTFILE identifier (matches {@link CbStm03B#FILE_TAG_ACCTFILE}). */
        record AcctFile() implements Cbstm03BFileId {
            /** Canonical singleton. */
            public static final AcctFile INSTANCE = new AcctFile();
            @Override public String tag() { return FILE_TAG_ACCTFILE; }
        }

        /**
         * Factory mapping a COBOL 8-character file tag (with optional
         * trailing spaces, case-insensitive) to its sealed-type instance.
         *
         * <p>This factory exists because the COBOL {@code 0000-START}
         * paragraph dispatches on the literal {@code LK-M03B-DD} content,
         * which is an arbitrary 8-character buffer at the COBOL call
         * boundary. The Java equivalent is to accept the raw string from
         * the caller and resolve it to a typed identifier.
         *
         * <p>Normalization rules:
         * <ul>
         *   <li>Trailing whitespace is stripped via
         *       {@link String#stripTrailing()} (the COBOL field is
         *       fixed-width and may carry space padding).</li>
         *   <li>The result is uppercased via
         *       {@link String#toUpperCase(java.util.Locale)} with
         *       {@link Locale#ROOT} to avoid locale-sensitive Turkic
         *       behaviors.</li>
         * </ul>
         *
         * <p>The {@code default} branch in the inner switch is defensive
         * validation over an arbitrary {@link String} (open universe).
         * Sealed-type pattern-matching switches over typed
         * {@link Cbstm03BFileId} instances elsewhere in this file have NO
         * {@code default} branch (compiler-enforced exhaustiveness is the
         * safety guarantee per AAP &sect;0.6.7).
         *
         * @param tag the COBOL DD name (must not be {@code null}); leading
         *            spaces are NOT stripped (they would change the tag)
         * @return the resolved sealed-type instance
         * @throws NullPointerException if {@code tag} is {@code null}
         * @throws IllegalArgumentException if {@code tag} (after
         *         normalization) does not match any of the four known
         *         CBSTM03B file identifiers
         */
        static Cbstm03BFileId fromTag(String tag) {
            Objects.requireNonNull(tag, "tag");
            String normalized = tag.stripTrailing().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case FILE_TAG_TRNXFILE -> TrnxFile.INSTANCE;
                case FILE_TAG_XREFFILE -> XrefFile.INSTANCE;
                case FILE_TAG_CUSTFILE -> CustFile.INSTANCE;
                case FILE_TAG_ACCTFILE -> AcctFile.INSTANCE;
                default -> throw new IllegalArgumentException(
                        "Unknown file tag: '" + tag + "'");
            };
        }
    }


    // =====================================================================
    // Logger, immutable configuration (file paths), and mutable state
    // (open handles + in-memory keyed indexes).
    //
    // The class is intentionally NOT thread-safe — it mirrors the COBOL
    // CBSTM03B subroutine which is called by a single CBSTM03A driver
    // task. Callers that want concurrent access should hold one CbStm03B
    // instance per thread.
    // =====================================================================

    private static final Logger log = LoggerFactory.getLogger(CbStm03B.class);

    /** Path to TRNXFILE (COSTM01 layout, sequential). Immutable. */
    private final Path trnxFilePath;
    /** Path to XREFFILE (CVACT03Y layout, sequential). Immutable. */
    private final Path xrefFilePath;
    /** Path to CUSTFILE (CUSTREC layout, random-by-key FD-CUST-ID). Immutable. */
    private final Path custFilePath;
    /** Path to ACCTFILE (CVACT01Y layout, random-by-key FD-ACCT-ID). Immutable. */
    private final Path acctFilePath;

    /**
     * Open handle to TRNXFILE for sequential reading. {@code null} when
     * closed. Mutated only by {@link #openTrnxFile()} and
     * {@link #closeTrnxFile()}.
     */
    private SeekableByteChannel trnxChannel;
    /**
     * Open handle to XREFFILE for sequential reading. {@code null} when
     * closed. Mutated only by {@link #openXrefFile()} and
     * {@link #closeXrefFile()}.
     */
    private SeekableByteChannel xrefChannel;

    /**
     * Effective per-record byte length for the currently-open XREFFILE.
     * Resolved at {@link #openXrefFile()} time to either
     * {@link #XREF_RECORD_LENGTH} (50, the canonical CVACT03Y layout)
     * or {@link #XREF_FIXTURE_RECORD_LENGTH} (36, the ASCII fixture
     * layout without trailing FILLER). Used by {@link #readNextXref()}
     * to read the correct number of bytes; records shorter than
     * {@link #XREF_RECORD_LENGTH} are space-padded to 50 before being
     * handed to {@link CardXrefRecord#parse(byte[])}. Zero when no
     * XREFFILE is currently open.
     */
    private int xrefFileRecordLength;
    /**
     * In-memory index for CUSTFILE keyed by {@code FD-CUST-ID}. Built
     * during {@link #openCustFile()} by reading every record into a
     * {@link HashMap}; this matches the COBOL {@code ACCESS MODE IS
     * RANDOM RECORD KEY IS FD-CUST-ID} semantics while leveraging Java's
     * O(1) hash lookup. {@code null} when closed. Acceptable for batch
     * use given the small file sizes documented in {@code
     * app/data/ASCII/}.
     */
    private Map<Long, CustomerLegacyRecord> custIndex;
    /**
     * In-memory index for ACCTFILE keyed by {@code FD-ACCT-ID}. Same
     * design and rationale as {@link #custIndex}.
     */
    private Map<Long, AccountRecord> acctIndex;

    /**
     * Constructs a CbStm03B file-services instance with the configured
     * paths for the four fixed-width files corresponding to
     * {@code FD-TRNXFILE-REC}, {@code FD-XREFFILE-REC},
     * {@code FD-CUSTFILE-REC}, {@code FD-ACCTFILE-REC} in the original
     * COBOL (CBSTM03B.CBL lines 58&ndash;78).
     *
     * <p>Per AAP &sect;0.7.2 (12-factor configuration) the composition
     * root in {@code carddemo-app} resolves these paths from
     * {@code application.properties} (or environment-variable overrides)
     * and passes them in at construction time. The class itself contains
     * no path discovery, no classpath lookup, and no
     * {@code System.getProperty} fallback.
     *
     * <p>All four paths are required (must be non-null). Whether the
     * underlying files actually <em>exist</em> at construction time is
     * not checked here &mdash; that check is deferred to {@code openFile}
     * so the caller controls the open lifecycle exactly as the COBOL
     * caller does.
     *
     * @param trnxFilePath path to TRNXFILE (COSTM01 layout, 350
     *                     bytes/record, sequential)
     * @param xrefFilePath path to XREFFILE (CVACT03Y layout, 50
     *                     bytes/record, sequential)
     * @param custFilePath path to CUSTFILE (CUSTREC layout, 500
     *                     bytes/record, random/keyed by FD-CUST-ID)
     * @param acctFilePath path to ACCTFILE (CVACT01Y layout, 300
     *                     bytes/record, random/keyed by FD-ACCT-ID)
     * @throws NullPointerException if any path is {@code null}
     */
    public CbStm03B(Path trnxFilePath,
                    Path xrefFilePath,
                    Path custFilePath,
                    Path acctFilePath) {
        this.trnxFilePath = Objects.requireNonNull(trnxFilePath, "trnxFilePath");
        this.xrefFilePath = Objects.requireNonNull(xrefFilePath, "xrefFilePath");
        this.custFilePath = Objects.requireNonNull(custFilePath, "custFilePath");
        this.acctFilePath = Objects.requireNonNull(acctFilePath, "acctFilePath");
    }

    // =====================================================================
    // Public API — typed methods replacing the COBOL WS-M03B-AREA
    // dispatch. These are the primary call surface; the generic
    // dispatch(...) method below is for call sites that need to mirror
    // the WS-M03B-AREA pattern verbatim.
    // =====================================================================

    /**
     * Opens the file identified by the given tag. Mirrors the COBOL
     * sequence {@code MOVE 'TRNXFILE' TO LK-M03B-DD} (or another tag)
     * &middot; {@code MOVE 'O' TO LK-M03B-OPER} &middot;
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA}.
     *
     * <p>Tag values: {@value #FILE_TAG_TRNXFILE},
     * {@value #FILE_TAG_XREFFILE}, {@value #FILE_TAG_CUSTFILE},
     * {@value #FILE_TAG_ACCTFILE}. The tag is normalized via
     * {@link Cbstm03BFileId#fromTag(String)} (case-insensitive, trailing
     * space tolerant).
     *
     * @param fileTag the COBOL DD name (must not be {@code null}; must
     *                resolve to one of the four known files)
     * @return {@link #RC_OK} on success; {@link #RC_ERROR} on
     *         {@link IOException} from the underlying file system
     * @throws NullPointerException if {@code fileTag} is {@code null}
     * @throws IllegalArgumentException if {@code fileTag} does not
     *         resolve to a known file
     */
    public int openFile(String fileTag) {
        Cbstm03BFileId fileId = Cbstm03BFileId.fromTag(fileTag);
        return openFile(fileId);
    }

    /**
     * Opens the file identified by the given sealed-type identifier.
     * Strongly-typed counterpart of {@link #openFile(String)}.
     *
     * <p>The switch over {@code fileId} has no {@code default} branch:
     * the sealed-type permits clause guarantees exhaustiveness, which is
     * the mandated pattern per AAP &sect;0.6.7.
     *
     * @param fileId the typed file identifier (must not be {@code null})
     * @return {@link #RC_OK} on success; {@link #RC_ERROR} on I/O failure
     * @throws NullPointerException if {@code fileId} is {@code null}
     */
    public int openFile(Cbstm03BFileId fileId) {
        Objects.requireNonNull(fileId, "fileId");
        try {
            return switch (fileId) {
                case Cbstm03BFileId.TrnxFile t -> openTrnxFile();
                case Cbstm03BFileId.XrefFile x -> openXrefFile();
                case Cbstm03BFileId.CustFile c -> openCustFile();
                case Cbstm03BFileId.AcctFile a -> openAcctFile();
            };
        } catch (IOException e) {
            log.error("Failed to open {}", fileId.tag(), e);
            return RC_ERROR;
        }
    }

    /**
     * Closes the file identified by the given tag. Mirrors the COBOL
     * sequence {@code MOVE 'TRNXFILE' TO LK-M03B-DD} (or another tag)
     * &middot; {@code MOVE 'C' TO LK-M03B-OPER} &middot;
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA}.
     *
     * <p>The method is idempotent: closing an already-closed file is a
     * no-op and returns {@link #RC_OK}.
     *
     * @param fileTag the COBOL DD name (must not be {@code null})
     * @return {@link #RC_OK} on success; {@link #RC_ERROR} on I/O failure
     * @throws NullPointerException if {@code fileTag} is {@code null}
     * @throws IllegalArgumentException if {@code fileTag} does not
     *         resolve to a known file
     */
    public int closeFile(String fileTag) {
        Cbstm03BFileId fileId = Cbstm03BFileId.fromTag(fileTag);
        return closeFile(fileId);
    }

    /**
     * Closes the file identified by the given sealed-type identifier.
     * Strongly-typed counterpart of {@link #closeFile(String)}.
     *
     * @param fileId the typed file identifier (must not be {@code null})
     * @return {@link #RC_OK} on success; {@link #RC_ERROR} on I/O failure
     * @throws NullPointerException if {@code fileId} is {@code null}
     */
    public int closeFile(Cbstm03BFileId fileId) {
        Objects.requireNonNull(fileId, "fileId");
        try {
            return switch (fileId) {
                case Cbstm03BFileId.TrnxFile t -> closeTrnxFile();
                case Cbstm03BFileId.XrefFile x -> closeXrefFile();
                case Cbstm03BFileId.CustFile c -> closeCustFile();
                case Cbstm03BFileId.AcctFile a -> closeAcctFile();
            };
        } catch (IOException e) {
            log.error("Failed to close {}", fileId.tag(), e);
            return RC_ERROR;
        }
    }


    // =====================================================================
    // TRNXFILE operations — translation of paragraph 1000-TRNXFILE-PROC
    // (CBSTM03B.CBL lines 133-155). TRNXFILE is declared in COBOL as
    // ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL (line 32-33).
    // The Java translation uses a SeekableByteChannel and exposes a
    // sequential reader that returns Optional<TrnxRecord>.
    //
    // The COBOL READ statement reads INTO LK-M03B-FLDT (a 1000-byte
    // buffer); the Java translation parses the 350-byte buffer into a
    // strongly-typed TrnxRecord on the spot (no intermediate buffer
    // copy). Byte-for-byte fidelity is preserved by TrnxRecord.parse(...)
    // / TrnxRecord.encode() (AAP §0.6.5).
    // =====================================================================

    /**
     * Opens TRNXFILE for sequential reading. Translates the COBOL
     * 1000-TRNXFILE-PROC M03B-OPEN branch at lines 135-138:
     * <pre>{@code
     *   IF M03B-OPEN
     *       OPEN INPUT TRNX-FILE
     *       GO TO 1900-EXIT
     *   END-IF.
     * }</pre>
     *
     * <p>If TRNXFILE is already open this method closes the prior handle
     * before reopening, logging a warning. This preserves the COBOL
     * convention that {@code OPEN INPUT} on an already-open file is an
     * application error but does not crash the runtime (the FILE STATUS
     * would reflect the abnormal state in COBOL; the Java translation
     * forces a clean reopen).
     *
     * @return {@link #RC_OK}
     * @throws IOException if the file system rejects the open
     */
    private int openTrnxFile() throws IOException {
        if (trnxChannel != null) {
            log.warn("TRNXFILE already open; closing prior handle");
            trnxChannel.close();
        }
        trnxChannel = Files.newByteChannel(trnxFilePath, StandardOpenOption.READ);
        log.debug("Opened TRNXFILE at {}", trnxFilePath);
        return RC_OK;
    }

    /**
     * Reads the next TRNX record from TRNXFILE in sequential order.
     * Translates the COBOL 1000-TRNXFILE-PROC M03B-READ branch at lines
     * 140-144:
     * <pre>{@code
     *   IF M03B-READ
     *       READ TRNX-FILE INTO LK-M03B-FLDT
     *       END-READ
     *       GO TO 1900-EXIT
     *   END-IF.
     * }</pre>
     *
     * <p>The COBOL READ has implicit {@code AT END} semantics &mdash; on
     * end-of-file the FILE STATUS becomes {@code "10"} and the caller
     * decides whether to treat that as a normal termination. The Java
     * translation surfaces this as {@link Optional#empty()}, which the
     * caller pattern-matches.
     *
     * <p>This method does <strong>not</strong> declare {@code throws
     * IOException} because the schema-mandated return type is
     * {@code Optional<TrnxRecord>} and adding a checked exception would
     * propagate up to every call site for what is effectively a "the
     * file is corrupt" panic. Instead, {@link IOException} is wrapped in
     * {@link UncheckedIOException} and rethrown.
     *
     * @return the next {@link TrnxRecord} or {@link Optional#empty()} at
     *         end-of-file
     * @throws IllegalStateException if {@link #openFile(Cbstm03BFileId)
     *         openFile(TrnxFile)} has not been called (or the file has
     *         been closed since)
     * @throws UncheckedIOException if a low-level I/O error occurs (the
     *         file is corrupt, the disk is unavailable, etc.)
     */
    public Optional<TrnxRecord> readNextTransaction() {
        if (trnxChannel == null) {
            throw new IllegalStateException(
                    "TRNXFILE not open; call openFile(TRNXFILE) first");
        }
        try {
            byte[] buffer = readFixedWidthRecord(trnxChannel, TRNX_RECORD_LENGTH);
            if (buffer == null) {
                return Optional.empty();  // Clean EOF at record boundary
            }
            return Optional.of(TrnxRecord.parse(buffer));
        } catch (IOException e) {
            log.error("Failed to read next TRNX record", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Closes TRNXFILE. Translates the COBOL 1000-TRNXFILE-PROC M03B-CLOSE
     * branch at lines 146-149:
     * <pre>{@code
     *   IF M03B-CLOSE
     *       CLOSE TRNX-FILE
     *       GO TO 1900-EXIT
     *   END-IF.
     * }</pre>
     *
     * @return {@link #RC_OK} (idempotent: closing a closed file is a no-op)
     * @throws IOException if the file system rejects the close
     */
    private int closeTrnxFile() throws IOException {
        if (trnxChannel != null) {
            trnxChannel.close();
            trnxChannel = null;
            log.debug("Closed TRNXFILE");
        }
        return RC_OK;
    }

    // =====================================================================
    // XREFFILE operations — translation of paragraph 2000-XREFFILE-PROC
    // (CBSTM03B.CBL lines 157-179). XREFFILE is declared in COBOL as
    // ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL (lines 38-39).
    // Same translation strategy as TRNXFILE.
    // =====================================================================

    /**
     * Opens XREFFILE for sequential reading. Translates the COBOL
     * 2000-XREFFILE-PROC M03B-OPEN branch at lines 159-162.
     *
     * @return {@link #RC_OK}
     * @throws IOException if the file system rejects the open
     */
    private int openXrefFile() throws IOException {
        if (xrefChannel != null) {
            log.warn("XREFFILE already open; closing prior handle");
            xrefChannel.close();
        }
        xrefChannel = Files.newByteChannel(xrefFilePath, StandardOpenOption.READ);
        // Detect whether the XREFFILE is the canonical 50-byte CVACT03Y
        // layout or the 36-byte ASCII fixture layout (FILLER omitted).
        // See FileCardXrefRepository.detectFileRecordLength(...) for
        // the rationale; we use the same probe rules here so that
        // CbStm03B and FileCardXrefRepository behave identically when
        // given the same input file.
        xrefFileRecordLength = detectXrefRecordLength(xrefFilePath);
        log.debug("Opened XREFFILE at {} (recordLength={})",
                xrefFilePath, xrefFileRecordLength);
        return RC_OK;
    }

    /**
     * Probes {@code path} to decide whether its records are
     * {@link #XREF_RECORD_LENGTH} (50) or
     * {@link #XREF_FIXTURE_RECORD_LENGTH} (36) bytes wide. The decision
     * is permanent for the duration of the open file handle and is
     * stored in {@link #xrefFileRecordLength}.
     *
     * <p>Detection rules (in priority order):
     * <ol>
     *   <li>File is missing or empty &rarr; return 50 (assume canonical
     *       layout; subsequent reads will simply return EOF).</li>
     *   <li>Byte at offset 36 is LF (0x0A) &rarr; return 36 (LF-terminated
     *       36-byte fixture).</li>
     *   <li>Bytes at offsets 36 and 37 are CRLF (0x0D 0x0A) &rarr; return
     *       36 (CRLF-terminated 36-byte fixture).</li>
     *   <li>{@code size % 37 == 0 && size % 50 != 0} &rarr; return 36
     *       (pure 37-byte-stride fixture).</li>
     *   <li>{@code size % 36 == 0 && size % 50 != 0} &rarr; return 36
     *       (binary 36-byte stride, no separators).</li>
     *   <li>Otherwise &rarr; return 50 (canonical layout).</li>
     * </ol>
     *
     * @param path the XREFFILE to probe
     * @return {@link #XREF_RECORD_LENGTH} (50) or
     *         {@link #XREF_FIXTURE_RECORD_LENGTH} (36)
     * @throws IOException if reading {@code path} fails
     */
    private static int detectXrefRecordLength(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0L) {
            return XREF_RECORD_LENGTH;
        }
        long size = Files.size(path);
        try (SeekableByteChannel probe = Files.newByteChannel(path,
                StandardOpenOption.READ)) {
            if (size >= XREF_FIXTURE_RECORD_LENGTH + 1) {
                probe.position(XREF_FIXTURE_RECORD_LENGTH);
                ByteBuffer one = ByteBuffer.allocate(2);
                int n = probe.read(one);
                one.flip();
                if (n >= 1 && one.get(0) == (byte) 0x0A) {
                    return XREF_FIXTURE_RECORD_LENGTH;
                }
                if (n >= 2 && one.get(0) == (byte) 0x0D
                        && one.get(1) == (byte) 0x0A) {
                    return XREF_FIXTURE_RECORD_LENGTH;
                }
            }
        }
        if (size % (XREF_FIXTURE_RECORD_LENGTH + 1) == 0
                && size % XREF_RECORD_LENGTH != 0) {
            return XREF_FIXTURE_RECORD_LENGTH;
        }
        if (size % XREF_FIXTURE_RECORD_LENGTH == 0
                && size % XREF_RECORD_LENGTH != 0) {
            return XREF_FIXTURE_RECORD_LENGTH;
        }
        return XREF_RECORD_LENGTH;
    }

    /**
     * Reads the next CARD-XREF record from XREFFILE in sequential order.
     * Translates the COBOL 2000-XREFFILE-PROC M03B-READ branch at lines
     * 164-168.
     *
     * @return the next {@link CardXrefRecord} or {@link Optional#empty()}
     *         at end-of-file
     * @throws IllegalStateException if {@link #openFile(Cbstm03BFileId)
     *         openFile(XrefFile)} has not been called (or the file has
     *         been closed since)
     * @throws UncheckedIOException if a low-level I/O error occurs
     */
    public Optional<CardXrefRecord> readNextXref() {
        if (xrefChannel == null) {
            throw new IllegalStateException(
                    "XREFFILE not open; call openFile(XREFFILE) first");
        }
        try {
            // Read using the detected per-file record length
            // (xrefFileRecordLength is set by openXrefFile()) so that
            // both 50-byte CVACT03Y exports and 36-byte ASCII fixtures
            // are handled correctly.
            byte[] buffer = readFixedWidthRecord(xrefChannel,
                    xrefFileRecordLength);
            if (buffer == null) {
                return Optional.empty();
            }
            // If the file uses the 36-byte fixture format, pad the
            // record with ASCII spaces (0x20) to the canonical 50-byte
            // length expected by CardXrefRecord.parse(byte[]). The
            // padded suffix represents the trailing FILLER PIC X(14)
            // group from CVACT03Y, which a COBOL READ would supply as
            // X'40' (EBCDIC space, 0x20 in ASCII).
            if (buffer.length != XREF_RECORD_LENGTH) {
                byte[] padded = new byte[XREF_RECORD_LENGTH];
                Arrays.fill(padded, (byte) 0x20);
                System.arraycopy(buffer, 0, padded, 0, buffer.length);
                buffer = padded;
            }
            return Optional.of(CardXrefRecord.parse(buffer));
        } catch (IOException e) {
            log.error("Failed to read next XREF record", e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Closes XREFFILE. Translates the COBOL 2000-XREFFILE-PROC M03B-CLOSE
     * branch at lines 170-173.
     *
     * @return {@link #RC_OK} (idempotent)
     * @throws IOException if the file system rejects the close
     */
    private int closeXrefFile() throws IOException {
        if (xrefChannel != null) {
            xrefChannel.close();
            xrefChannel = null;
            xrefFileRecordLength = 0;
            log.debug("Closed XREFFILE");
        }
        return RC_OK;
    }

    // =====================================================================
    // CUSTFILE operations — translation of paragraph 3000-CUSTFILE-PROC
    // (CBSTM03B.CBL lines 181-204). CUSTFILE is declared in COBOL as
    // ORGANIZATION IS INDEXED, ACCESS MODE IS RANDOM, RECORD KEY IS
    // FD-CUST-ID (lines 43-47). The Java translation loads the entire
    // file into an in-memory HashMap<Long, CustomerLegacyRecord> at open
    // time, giving O(1) keyed lookup (acceptable for batch use given the
    // small file size).
    //
    // CRITICAL: Uses CustomerLegacyRecord (from app/cpy/CUSTREC.cpy), NOT
    // CustomerRecord (from app/cpy/CVCUS01Y.cpy). Per the agent_prompt:
    // "the COBOL CBSTM03B FD-CUSTFILE-REC corresponds to the CUSTREC
    // layout used by its caller CBSTM03A". The two records share a
    // 500-byte total length but differ in DOB field semantics.
    // =====================================================================

    /**
     * Opens CUSTFILE for keyed (random) reading. Translates the COBOL
     * 3000-CUSTFILE-PROC M03B-OPEN branch at lines 183-186:
     * <pre>{@code
     *   IF M03B-OPEN
     *       OPEN INPUT CUST-FILE
     *       GO TO 3900-EXIT
     *   END-IF.
     * }</pre>
     *
     * <p>The COBOL {@code OPEN INPUT} on a {@code ACCESS MODE IS RANDOM
     * RECORD KEY IS FD-CUST-ID} file lets subsequent reads target any
     * record by key. The Java equivalent is to build a complete in-memory
     * index from {@code FD-CUST-ID} ({@link CustomerLegacyRecord#custId()})
     * to the record, so subsequent {@link #readCustomerByKey(long)} calls
     * are O(1). This is acceptable for batch use given the small file
     * sizes documented in {@code app/data/ASCII/}.
     *
     * <p>If CUSTFILE was already opened, the prior index is replaced
     * with a fresh one (logging a warning). This preserves the COBOL
     * convention that a re-open builds a new view of the file.
     *
     * @return {@link #RC_OK}
     * @throws IOException if the file cannot be opened or any record
     *         cannot be parsed
     */
    private int openCustFile() throws IOException {
        if (custIndex != null) {
            log.warn("CUSTFILE already open; replacing prior index");
        }
        Map<Long, CustomerLegacyRecord> index = new HashMap<>();
        try (SeekableByteChannel ch = Files.newByteChannel(custFilePath, StandardOpenOption.READ)) {
            byte[] buffer;
            while ((buffer = readFixedWidthRecord(ch, CUST_RECORD_LENGTH)) != null) {
                CustomerLegacyRecord rec = CustomerLegacyRecord.parse(buffer);
                // Key is FD-CUST-ID (PIC 9(09) in CUSTREC) — the customer ID
                // field on the record. CustomerLegacyRecord.custId() returns
                // the typed long matching the COBOL PIC 9(09).
                index.put(rec.custId(), rec);
            }
        }
        this.custIndex = index;
        log.debug("Opened CUSTFILE at {}; indexed {} records",
                custFilePath, custIndex.size());
        return RC_OK;
    }

    /**
     * Reads a customer record by key. Translates the COBOL
     * 3000-CUSTFILE-PROC M03B-READ-K branch at lines 188-193:
     * <pre>{@code
     *   IF M03B-READ-K
     *       MOVE LK-M03B-KEY (1:LK-M03B-KEY-LN) TO FD-CUST-ID
     *       READ CUST-FILE INTO LK-M03B-FLDT
     *       END-READ
     *       GO TO 3900-EXIT
     *   END-IF.
     * }</pre>
     *
     * <p>The COBOL {@code READ ... INTO LK-M03B-FLDT} on a random-access
     * file looks up the record whose RECORD KEY equals the buffer; the
     * Java equivalent is {@link Map#get(Object)} on the keyed index built
     * by {@link #openCustFile()}.
     *
     * @param custId the FD-CUST-ID key to look up
     * @return the {@link CustomerLegacyRecord} or {@link Optional#empty()}
     *         if no record with that key exists in the file
     * @throws IllegalStateException if {@link #openFile(Cbstm03BFileId)
     *         openFile(CustFile)} has not been called (or the file has
     *         been closed since)
     */
    public Optional<CustomerLegacyRecord> readCustomerByKey(long custId) {
        if (custIndex == null) {
            throw new IllegalStateException(
                    "CUSTFILE not open; call openFile(CUSTFILE) first");
        }
        return Optional.ofNullable(custIndex.get(custId));
    }

    /**
     * Closes CUSTFILE. Translates the COBOL 3000-CUSTFILE-PROC
     * M03B-CLOSE branch at lines 195-198. Drops the in-memory index and
     * makes it eligible for garbage collection.
     *
     * @return {@link #RC_OK} (idempotent)
     * @throws IOException declared for symmetry with the sequential-file
     *         close methods; the random-access close cannot actually
     *         throw because there is no underlying channel after open
     *         time
     */
    private int closeCustFile() throws IOException {
        if (custIndex != null) {
            custIndex = null;
            log.debug("Closed CUSTFILE");
        }
        return RC_OK;
    }

    // =====================================================================
    // ACCTFILE operations — translation of paragraph 4000-ACCTFILE-PROC
    // (CBSTM03B.CBL lines 206-229). ACCTFILE is declared in COBOL as
    // ORGANIZATION IS INDEXED, ACCESS MODE IS RANDOM, RECORD KEY IS
    // FD-ACCT-ID (lines 49-53). Same translation strategy as CUSTFILE.
    // =====================================================================

    /**
     * Opens ACCTFILE for keyed (random) reading. Translates the COBOL
     * 4000-ACCTFILE-PROC M03B-OPEN branch at lines 208-211. Builds an
     * in-memory index from {@code FD-ACCT-ID}
     * ({@link AccountRecord#acctId()}) to {@link AccountRecord}.
     *
     * @return {@link #RC_OK}
     * @throws IOException if the file cannot be opened or any record
     *         cannot be parsed
     */
    private int openAcctFile() throws IOException {
        if (acctIndex != null) {
            log.warn("ACCTFILE already open; replacing prior index");
        }
        Map<Long, AccountRecord> index = new HashMap<>();
        try (SeekableByteChannel ch = Files.newByteChannel(acctFilePath, StandardOpenOption.READ)) {
            byte[] buffer;
            while ((buffer = readFixedWidthRecord(ch, ACCT_RECORD_LENGTH)) != null) {
                AccountRecord rec = AccountRecord.parse(buffer);
                // Key is FD-ACCT-ID (PIC 9(11) in CVACT01Y).
                index.put(rec.acctId(), rec);
            }
        }
        this.acctIndex = index;
        log.debug("Opened ACCTFILE at {}; indexed {} records",
                acctFilePath, acctIndex.size());
        return RC_OK;
    }

    /**
     * Reads an account record by key. Translates the COBOL
     * 4000-ACCTFILE-PROC M03B-READ-K branch at lines 213-218.
     *
     * @param acctId the FD-ACCT-ID key to look up
     * @return the {@link AccountRecord} or {@link Optional#empty()} if no
     *         record with that key exists in the file
     * @throws IllegalStateException if {@link #openFile(Cbstm03BFileId)
     *         openFile(AcctFile)} has not been called (or the file has
     *         been closed since)
     */
    public Optional<AccountRecord> readAccountByKey(long acctId) {
        if (acctIndex == null) {
            throw new IllegalStateException(
                    "ACCTFILE not open; call openFile(ACCTFILE) first");
        }
        return Optional.ofNullable(acctIndex.get(acctId));
    }

    /**
     * Closes ACCTFILE. Translates the COBOL 4000-ACCTFILE-PROC
     * M03B-CLOSE branch at lines 220-223.
     *
     * @return {@link #RC_OK} (idempotent)
     * @throws IOException declared for symmetry with the sequential-file
     *         close methods; cannot actually throw
     */
    private int closeAcctFile() throws IOException {
        if (acctIndex != null) {
            acctIndex = null;
            log.debug("Closed ACCTFILE");
        }
        return RC_OK;
    }


    // =====================================================================
    // Fixed-width record reader helper — used by every sequential read
    // path (TRNXFILE, XREFFILE) and during in-memory indexing (CUSTFILE,
    // ACCTFILE).
    // =====================================================================

    /**
     * Reads exactly {@code recordLength} bytes from the channel, returning
     * the byte array or {@code null} at end-of-file. Mirrors COBOL
     * fixed-width sequential read semantics:
     * <ul>
     *   <li>A complete record (channel returns {@code recordLength} or
     *       more bytes across one or more underlying reads) returns the
     *       record as a {@code byte[]}.</li>
     *   <li>A clean end-of-file at a record boundary (channel returns
     *       {@code -1} before any bytes are read) returns {@code null}.
     *       This is the "AT END" condition in COBOL.</li>
     *   <li>A truncated record (channel returns {@code -1} after some
     *       bytes have been read) throws {@link IOException} &mdash;
     *       this indicates file corruption, not a normal termination.</li>
     * </ul>
     *
     * <p>The loop handles the fact that
     * {@link SeekableByteChannel#read(java.nio.ByteBuffer)} is allowed to
     * return fewer bytes than requested even when more are available
     * (true for {@link Files#newByteChannel}-backed channels on some
     * platforms). It loops until the buffer is filled or EOF is signaled.
     *
     * @param channel the open {@link SeekableByteChannel} positioned at
     *                the start of a record
     * @param recordLength the fixed record length in bytes
     * @return byte array of exactly {@code recordLength} bytes, or
     *         {@code null} at clean EOF
     * @throws IOException on a low-level read failure or a partial
     *         record (file corruption)
     */
    private static byte[] readFixedWidthRecord(SeekableByteChannel channel,
                                               int recordLength) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(recordLength);
        int totalRead = 0;
        while (totalRead < recordLength) {
            int n = channel.read(buf);
            if (n < 0) {
                // EOF
                if (totalRead == 0) {
                    return null;  // Clean EOF at record boundary
                }
                throw new IOException(
                        "Partial record at EOF: expected " + recordLength
                                + " bytes, got " + totalRead);
            }
            totalRead += n;
        }
        // LF/CRLF-tolerant separator consumption: the input file may have
        // been REPRO'd from an ASCII fixture (e.g.
        // app/data/ASCII/custdata.txt) which uses LF terminators
        // (recordLength-byte record + 1-byte LF = stride of
        // recordLength+1), or may be a pure-binary KSDS export
        // (stride of recordLength). After reading exactly recordLength
        // bytes, probe for an optional LF or CRLF separator and consume
        // it if present; otherwise rewind the channel to just past the
        // record &mdash; matching the
        // {@code FixedWidthReader.consumeOptionalRecordSeparator}
        // contract documented in
        // {@code carddemo-adapter-file/src/main/java/com/blitzy/carddemo/adapter/file/FixedWidthReader.java}.
        consumeOptionalRecordSeparator(channel);
        return buf.array();
    }

    /**
     * Consumes an optional one-byte LF (0x0A) or two-byte CRLF
     * (0x0D 0x0A) record separator from {@code channel} at its current
     * position. If the next bytes are not a recognized separator (or
     * EOF is reached), the channel position is left unchanged or at
     * EOF, respectively.
     *
     * <p>This helper mirrors
     * {@code FixedWidthReader.consumeOptionalRecordSeparator(...)}
     * (in carddemo-adapter-file) and is duplicated here because
     * CbStm03B opens its own {@link SeekableByteChannel}s directly
     * (the COBOL program uses verbatim {@code OPEN}/{@code READ}/
     * {@code CLOSE} on its own DD-named files) rather than going
     * through the adapter-file repositories. Keeping the two
     * implementations behaviourally identical is essential for
     * byte-for-byte parity with the COBOL baseline regardless of
     * which code path the file was loaded through.
     *
     * @param channel the open channel; must be positioned just after
     *                a complete record. Modified by side-effect to
     *                skip over any LF/CRLF separator found.
     * @throws IOException if reading or repositioning {@code channel}
     *                     fails
     */
    private static void consumeOptionalRecordSeparator(SeekableByteChannel channel)
            throws IOException {
        long beforeSep = channel.position();
        ByteBuffer sep = ByteBuffer.allocate(2);
        int sepRead = channel.read(sep);
        if (sepRead <= 0) {
            // EOF or empty read — nothing to do; channel is at EOF.
            return;
        }
        sep.flip();
        byte b0 = sep.get(0);
        if (b0 == (byte) 0x0A) {
            // LF: consumed 1 byte. Rewind any extra byte (if read).
            channel.position(beforeSep + 1);
        } else if (sepRead >= 2
                && b0 == (byte) 0x0D
                && sep.get(1) == (byte) 0x0A) {
            // CRLF: consumed 2 bytes.
            channel.position(beforeSep + 2);
        } else {
            // No separator: rewind to before the probe.
            channel.position(beforeSep);
        }
    }

    // =====================================================================
    // Generic dispatcher — mirrors the COBOL "CALL 'CBSTM03B' USING
    // WS-M03B-AREA" pattern for call sites that need verbatim semantics
    // (dynamic-dispatch test harnesses, etc.). Most call sites should
    // prefer the typed methods above which return strongly-typed
    // Optional<T> results.
    // =====================================================================

    /**
     * Generic dispatcher mirroring the COBOL pattern
     * {@code CALL 'CBSTM03B' USING WS-M03B-AREA}. Most call sites should
     * prefer the typed methods ({@link #readNextTransaction()},
     * {@link #readCustomerByKey(long)}, etc.) which return strongly-typed
     * {@link Optional} results.
     *
     * <p>The switch over {@code operation} has no {@code default} branch:
     * the sealed-type permits clause guarantees exhaustiveness (per AAP
     * &sect;0.6.7).
     *
     * @param fileId the target file (must not be {@code null})
     * @param operation the operation to perform (must not be {@code null})
     * @return a return code per the {@code RC_*} constants
     *         ({@link #RC_OK}, {@link #RC_EOF}, {@link #RC_NOT_FOUND},
     *         {@link #RC_ERROR})
     * @throws NullPointerException if either argument is {@code null}
     */
    public int dispatch(Cbstm03BFileId fileId, Cbstm03BOperation operation) {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(operation, "operation");
        return switch (operation) {
            case Cbstm03BOperation.Open o -> openFile(fileId);
            case Cbstm03BOperation.Close c -> closeFile(fileId);
            case Cbstm03BOperation.Read r -> dispatchSequentialRead(fileId);
            case Cbstm03BOperation.ReadByKey k -> dispatchKeyedRead(fileId, k.key());
        };
    }

    /**
     * Routes a generic sequential READ ({@link Cbstm03BOperation.Read}) to
     * the appropriate typed reader, translating its {@link Optional} into
     * an {@code int} return code.
     *
     * <p>{@link Cbstm03BFileId.CustFile} and
     * {@link Cbstm03BFileId.AcctFile} are RANDOM-access in the COBOL FD
     * declarations; dispatching a sequential READ to them returns
     * {@link #RC_ERROR} with a warning log (preserves COBOL behavior
     * which would set FILE STATUS to an error code).
     *
     * <p>The switch has no {@code default} branch.
     *
     * @param fileId the target file
     * @return {@link #RC_OK} (record read), {@link #RC_EOF} (no more
     *         records), or {@link #RC_ERROR} (operation not valid for
     *         the file)
     */
    private int dispatchSequentialRead(Cbstm03BFileId fileId) {
        return switch (fileId) {
            case Cbstm03BFileId.TrnxFile t ->
                    readNextTransaction().isPresent() ? RC_OK : RC_EOF;
            case Cbstm03BFileId.XrefFile x ->
                    readNextXref().isPresent() ? RC_OK : RC_EOF;
            case Cbstm03BFileId.CustFile c -> {
                log.warn("Sequential READ not defined for CUSTFILE in CBSTM03B (RANDOM access only)");
                yield RC_ERROR;
            }
            case Cbstm03BFileId.AcctFile a -> {
                log.warn("Sequential READ not defined for ACCTFILE in CBSTM03B (RANDOM access only)");
                yield RC_ERROR;
            }
        };
    }

    /**
     * Routes a generic keyed READ-K ({@link Cbstm03BOperation.ReadByKey})
     * to the appropriate typed reader. Parses the textual {@code key}
     * argument to {@code long}; on parse failure returns
     * {@link #RC_ERROR}.
     *
     * <p>{@link Cbstm03BFileId.TrnxFile} and
     * {@link Cbstm03BFileId.XrefFile} are SEQUENTIAL-access in the COBOL
     * FD declarations; dispatching a keyed READ-K to them returns
     * {@link #RC_ERROR} with a warning log.
     *
     * <p>The switch has no {@code default} branch.
     *
     * @param fileId the target file
     * @param key the textual key (parsed to {@code long} for CUSTFILE /
     *            ACCTFILE)
     * @return {@link #RC_OK} (record found), {@link #RC_NOT_FOUND}
     *         (record absent), or {@link #RC_ERROR} (operation not valid
     *         or key not parseable)
     */
    private int dispatchKeyedRead(Cbstm03BFileId fileId, String key) {
        return switch (fileId) {
            case Cbstm03BFileId.TrnxFile t -> {
                log.warn("Keyed READ not defined for TRNXFILE in CBSTM03B (sequential only)");
                yield RC_ERROR;
            }
            case Cbstm03BFileId.XrefFile x -> {
                log.warn("Keyed READ not defined for XREFFILE in CBSTM03B (sequential only)");
                yield RC_ERROR;
            }
            case Cbstm03BFileId.CustFile c -> {
                try {
                    long custId = Long.parseLong(key.trim());
                    yield readCustomerByKey(custId).isPresent() ? RC_OK : RC_NOT_FOUND;
                } catch (NumberFormatException nfe) {
                    log.error("Invalid custId key: '{}'", key, nfe);
                    yield RC_ERROR;
                }
            }
            case Cbstm03BFileId.AcctFile a -> {
                try {
                    long acctId = Long.parseLong(key.trim());
                    yield readAccountByKey(acctId).isPresent() ? RC_OK : RC_NOT_FOUND;
                } catch (NumberFormatException nfe) {
                    log.error("Invalid acctId key: '{}'", key, nfe);
                    yield RC_ERROR;
                }
            }
        };
    }
}

