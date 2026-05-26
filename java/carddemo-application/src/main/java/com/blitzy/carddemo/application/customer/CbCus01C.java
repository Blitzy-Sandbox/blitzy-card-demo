// SPDX-License-Identifier: Apache-2.0
// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// Java translation of COBOL CardDemo program CBCUS01C (app/cbl/CBCUS01C.cbl).
package com.blitzy.carddemo.application.customer;

import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CustomerRepository;
import com.blitzy.carddemo.domain.record.CustomerRecord;
// M17 — integrate sealed FileStatus hierarchy at the use-case boundary.
// The COBOL CUSTFILE-STATUS (2-char PIC X(02)) is preserved verbatim as
// the ioStatus field (for byte-fidelity DISPLAY 'FILE STATUS IS: NNNN'
// output), but read-loop dispatch now goes through FileStatus.fromCobolCode
// + an exhaustive pattern switch on the sealed permits per AAP §0.6.10.
// The non-recoverable abend (CALL 'CEE3ABD') translates to a typed
// FileStatusException carrying FileStatus.IoError as its first-class payload.
import com.blitzy.carddemo.domain.status.FileStatus;
import com.blitzy.carddemo.domain.status.FileStatusException;

/**
 * Java translation of the COBOL batch program {@code CBCUS01C} &mdash; a sequential reader
 * and display utility for the CUSTFILE customer master dataset.
 *
 * <p>The original COBOL opens CUSTFILE (KSDS, INDEXED organization, SEQUENTIAL access),
 * iterates records by ascending {@code FD-CUST-ID PIC 9(09)} key, displays each 500-byte
 * {@code CUSTOMER-RECORD} (from copybook {@code CVCUS01Y}), and closes the file. On any
 * I/O error (FILE STATUS not '00' on OPEN/CLOSE, or not '00'/'10' on READ) it displays
 * the FILE STATUS and abends via {@code CALL 'CEE3ABD'} with ABCODE=999.</p>
 *
 * <h2>Hexagonal architecture note</h2>
 * <p>This class depends only on the {@link CustomerRepository} port from
 * {@code carddemo-domain}; the actual file-backed implementation lives in
 * {@code carddemo-adapter-file} and is wired by the composition root in
 * {@code carddemo-app}. The COBOL paragraphs {@code 0000-CUSTFILE-OPEN},
 * {@code 1000-CUSTFILE-GET-NEXT}, and {@code 9000-CUSTFILE-CLOSE} translate to private
 * methods that delegate the OPEN/READ/CLOSE primitives to the port's
 * {@link CustomerRepository#streamSequential()} returning an {@code AutoCloseable Stream}.
 * Per AAP &sect;0.3.6 the application ring depends ONLY on {@code carddemo-domain}, never
 * on the adapter rings.</p>
 *
 * <h2>Preserved COBOL anomaly</h2>
 * <p>(Per AAP &sect;0.7.1 &mdash; translate faithfully, do not "fix" in this refactor.)
 * The original COBOL displays {@code CUSTOMER-RECORD} TWICE per successful read &mdash;
 * once in paragraph {@code 1000-CUSTFILE-GET-NEXT} at {@code app/cbl/CBCUS01C.cbl:L96},
 * and once in the main loop at {@code app/cbl/CBCUS01C.cbl:L78}. The Java translation
 * faithfully reproduces this double display. This anomaly is logged in
 * {@code java/MIGRATION_NOTES.md} for follow-up consideration outside the migration
 * scope.</p>
 *
 * <h2>WORKING-STORAGE semantics</h2>
 * <p>COBOL {@code WORKING-STORAGE} is per-execution mutable state. The Java translation
 * models this as private mutable fields initialized by {@link #initializeWorkingStorage()}
 * at the top of {@link #run()}. The class is NOT thread-safe and is NOT shared across
 * virtual threads (AAP &sect;0.1.2). A new instance per invocation is the recommended
 * composition pattern in {@code carddemo-app}.</p>
 *
 * <h2>FILE STATUS handling</h2>
 * <p>The COBOL paragraph error path uses both a 2-character {@code FILE STATUS} code
 * and an internal {@code APPL-RESULT} sentinel (0=AOK, 16=EOF, 12=error, 8=pending).
 * The Java translation preserves both surfaces &mdash; the FILE STATUS as
 * {@link #ioStatus} consumed by {@link #zDisplayIoStatus()}, and the APPL-RESULT as
 * {@link #applResult} driving the control-flow branches. Adapter-side
 * {@link RuntimeException}s are mapped to the synthetic FILE STATUS code {@code '30'}
 * (IBM Enterprise COBOL "permanent error") combined with {@code APPL-RESULT = 12}.</p>
 *
 * <h2>Mandated Java 25 idioms (per AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>JEP 511 Module Import Declarations</b> &mdash; the single
 *       {@code import module java.base;} statement at the top of this file replaces
 *       what would otherwise be four separate {@code java.util.*} /
 *       {@code java.util.stream.*} imports.</li>
 *   <li><b>{@link Objects#requireNonNull(Object, String)}</b> for the constructor
 *       null guard.</li>
 *   <li><b>{@link Locale#ROOT}</b> for locale-insensitive number formatting in
 *       {@link #zDisplayIoStatus()}.</li>
 *   <li><b>{@code final} class</b> &mdash; CBCUS01C is not designed for extension.</li>
 * </ul>
 *
 * @see <a href="file:../../../../../../../../../../../app/cbl/CBCUS01C.cbl">app/cbl/CBCUS01C.cbl (source)</a>
 * @see <a href="file:../../../../../../../../../../../app/cpy/CVCUS01Y.cpy">app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD layout, 500 bytes)</a>
 * @see CustomerRecord
 * @see CustomerRepository
 */
@CobolProgram(
        value = "CBCUS01C",
        sourcePath = "app/cbl/CBCUS01C.cbl",
        translationDate = "2026-05-25",
        notes = "Sequential CUSTFILE reader; preserves duplicate-DISPLAY anomaly at COBOL lines 78/96."
)
public final class CbCus01C {

    // ─────────────────────────────────────────────────────────────────────────
    //  Constants — translate COBOL VALUE / 88-level / literal constants
    // ─────────────────────────────────────────────────────────────────────────

    /** SLF4J logger replaces COBOL {@code DISPLAY}. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CbCus01C.class);

    // ─── COBOL FILE STATUS code constants ──────────────────────────────────

    /** COBOL FILE STATUS '00' — successful operation. */
    private static final String STATUS_OK = "00";

    /** COBOL FILE STATUS '10' — end of file reached on READ. */
    private static final String STATUS_EOF = "10";

    /**
     * Synthetic FILE STATUS '30' used by the Java translation when the
     * {@link CustomerRepository} port throws an unexpected
     * {@link RuntimeException}. '30' is the IBM Enterprise COBOL convention for a
     * "permanent error"; the original COBOL never explicitly sets it but the value
     * is consistent with the {@code APPL-RESULT = 12} non-EOF, non-success branch.
     */
    private static final String STATUS_PERMANENT_ERROR = "30";

    // ─── APPL-RESULT constants (COBOL 88-level conditions) ─────────────────

    /** COBOL 88-level {@code APPL-AOK VALUE 0}. */
    private static final int APPL_AOK = 0;

    /** COBOL 88-level {@code APPL-EOF VALUE 16}. */
    private static final int APPL_EOF = 16;

    /** Non-AOK, non-EOF result value (COBOL literal 12 at lines 101, 124, 142). */
    private static final int APPL_ERROR = 12;

    /**
     * Initial sentinel value before OPEN/CLOSE (COBOL literal 8 at lines 119, 137).
     * COBOL: {@code MOVE 8 TO APPL-RESULT} / {@code ADD 8 TO ZERO GIVING APPL-RESULT}.
     */
    private static final int APPL_PENDING = 8;

    // ─── Z-ABEND-PROGRAM constants (CALL 'CEE3ABD' arguments) ──────────────

    /** COBOL line 156: {@code MOVE 0 TO TIMING}. */
    private static final int CEE3ABD_TIMING = 0;

    /** COBOL line 157: {@code MOVE 999 TO ABCODE}. */
    private static final int CEE3ABD_ABCODE = 999;

    // ─────────────────────────────────────────────────────────────────────────
    //  Fields — translate COBOL WORKING-STORAGE
    // ─────────────────────────────────────────────────────────────────────────

    // ─── Collaborator (constructor-injected port) ──────────────────────────

    /** Customer repository port abstracting CUSTFILE OPEN/READ/CLOSE. */
    private final CustomerRepository customerRepository;

    // ─── COBOL WORKING-STORAGE equivalents (mutable per-execution state) ───

    /** COBOL {@code CUSTFILE-STATUS} (2 chars) — lines 46-48 of CBCUS01C.cbl. */
    private String custfileStatus;

    /** COBOL {@code IO-STATUS} (2 chars), used by {@link #zDisplayIoStatus()} — lines 50-52. */
    private String ioStatus;

    /** COBOL {@code APPL-RESULT PIC S9(9) COMP} — line 61 of CBCUS01C.cbl. */
    private int applResult;

    /** COBOL {@code END-OF-FILE PIC X(01) VALUE 'N'} — line 65 of CBCUS01C.cbl. */
    private char endOfFile;

    /** COBOL {@code CUSTOMER-RECORD} from copybook CVCUS01Y (COPY at line 45). */
    private CustomerRecord customerRecord;

    // ─── Java-side encapsulation of OPEN/READ/CLOSE (no COBOL counterpart) ─

    /**
     * Holds the open stream between {@link #custFileOpen()} and
     * {@link #custFileClose()}. Java's port-based abstraction replaces the COBOL
     * implicit FD file handle.
     */
    private Stream<CustomerRecord> customerStream;

    /** Iterator over {@link #customerStream}, used by {@link #custFileGetNext()}. */
    private Iterator<CustomerRecord> customerIterator;

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a {@code CbCus01C} bound to the given {@link CustomerRepository} port.
     *
     * <p>Per AAP &sect;0.1.1 / &sect;0.3.6 the wiring uses plain constructor injection;
     * there is no DI container (no Spring, no Guice, no service locator).</p>
     *
     * @param customerRepository the port implementation; must not be {@code null}
     * @throws NullPointerException if {@code customerRepository} is {@code null}
     */
    public CbCus01C(CustomerRepository customerRepository) {
        this.customerRepository = Objects.requireNonNull(customerRepository, "customerRepository");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public entry point — run() ← PROCEDURE DIVISION (lines 70-87)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the CBCUS01C program. Mirrors the COBOL PROCEDURE DIVISION
     * (lines 70-87 of {@code app/cbl/CBCUS01C.cbl}):
     *
     * <pre>{@code
     *   DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
     *   PERFORM 0000-CUSTFILE-OPEN.
     *   PERFORM UNTIL END-OF-FILE = 'Y'
     *       IF  END-OF-FILE = 'N'
     *           PERFORM 1000-CUSTFILE-GET-NEXT
     *           IF  END-OF-FILE = 'N'
     *               DISPLAY CUSTOMER-RECORD
     *           END-IF
     *       END-IF
     *   END-PERFORM.
     *   PERFORM 9000-CUSTFILE-CLOSE.
     *   DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.
     *   GOBACK.
     * }</pre>
     *
     * <p>The try/finally block is a defensive Java idiom (no direct COBOL counterpart)
     * that ensures the underlying {@link Stream} is closed even if
     * {@link #custFileGetNext()} throws a runtime abend exception. Under normal flow,
     * {@link #custFileClose()} handles closure; the {@code finally} block only fires
     * after an abend has already torn the flow down.</p>
     *
     * @throws IllegalStateException if any FILE STATUS error causes the COBOL
     *         {@code Z-ABEND-PROGRAM} path (translates COBOL {@code CALL 'CEE3ABD'}
     *         non-recoverable abend)
     */
    public void run() {
        initializeWorkingStorage();

        // COBOL line 71: DISPLAY 'START OF EXECUTION OF PROGRAM CBCUS01C'.
        LOGGER.info("START OF EXECUTION OF PROGRAM CBCUS01C");

        // COBOL line 72: PERFORM 0000-CUSTFILE-OPEN.
        custFileOpen();

        try {
            // COBOL lines 74-81: PERFORM UNTIL END-OF-FILE = 'Y'
            while (endOfFile != 'Y') {
                // COBOL line 75: IF END-OF-FILE = 'N'
                if (endOfFile == 'N') {
                    // COBOL line 76: PERFORM 1000-CUSTFILE-GET-NEXT
                    custFileGetNext();
                    // COBOL lines 77-79: IF END-OF-FILE = 'N' DISPLAY CUSTOMER-RECORD
                    if (endOfFile == 'N') {
                        // PRESERVED COBOL ANOMALY (line 78): displays CUSTOMER-RECORD
                        // again, duplicating the line-96 display inside
                        // 1000-CUSTFILE-GET-NEXT. See class Javadoc and
                        // MIGRATION_NOTES.md for details.
                        LOGGER.info("{}", customerRecord);
                    }
                }
            }

            // COBOL line 83: PERFORM 9000-CUSTFILE-CLOSE.
            custFileClose();
        } finally {
            // Defensive cleanup if an abend exception interrupts the normal close
            // path. No COBOL counterpart; required because Stream is AutoCloseable
            // per the CustomerRepository port contract.
            closeStreamQuietly();
        }

        // COBOL line 85: DISPLAY 'END OF EXECUTION OF PROGRAM CBCUS01C'.
        LOGGER.info("END OF EXECUTION OF PROGRAM CBCUS01C");
        // COBOL line 87: GOBACK — Java return is implicit.
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Java-side helper — initializeWorkingStorage()
    //  (no direct COBOL counterpart; COBOL gets fresh WORKING-STORAGE per
    //   program activation, Java must explicitly reset state)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initializes COBOL WORKING-STORAGE defaults. Translates the implicit
     * {@code VALUE} clauses declared in lines 46-67 of {@code app/cbl/CBCUS01C.cbl}.
     *
     * <p>This helper exists because, unlike COBOL programs which receive freshly
     * zeroed WORKING-STORAGE on each invocation, a Java object instance carries
     * mutable state across method calls. Resetting here guarantees that a single
     * {@code CbCus01C} instance may be re-{@link #run()} reliably.</p>
     */
    private void initializeWorkingStorage() {
        this.custfileStatus = "  ";       // COBOL: uninitialized PIC X(02) — spaces
        this.ioStatus = "  ";             // COBOL: uninitialized PIC X(02) — spaces
        this.applResult = 0;              // COBOL: PIC S9(9) COMP — default 0
        this.endOfFile = 'N';             // COBOL line 65: VALUE 'N'
        this.customerRecord = null;
        this.customerStream = null;
        this.customerIterator = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Paragraph translations (1:1 paragraph → private method)
    //  Methods placed in COBOL source order (1000-, 0000-, 9000-, Z-) per
    //  AAP §0.7.1 idiom-for-idiom translation.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Translates COBOL paragraph {@code 1000-CUSTFILE-GET-NEXT} (lines 92-116 of
     * {@code app/cbl/CBCUS01C.cbl}). Reads the next customer record from the open
     * stream. On success, displays the record (this is the FIRST of two displays
     * per record &mdash; see the preserved COBOL anomaly note in the class Javadoc).
     *
     * <pre>{@code
     *   1000-CUSTFILE-GET-NEXT.
     *       READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
     *       IF  CUSTFILE-STATUS = '00'
     *           MOVE 0 TO APPL-RESULT
     *           DISPLAY CUSTOMER-RECORD
     *       ELSE
     *           IF  CUSTFILE-STATUS = '10'
     *               MOVE 16 TO APPL-RESULT
     *           ELSE
     *               MOVE 12 TO APPL-RESULT
     *           END-IF
     *       END-IF
     *       IF  APPL-AOK
     *           CONTINUE
     *       ELSE
     *           IF  APPL-EOF
     *               MOVE 'Y' TO END-OF-FILE
     *           ELSE
     *               DISPLAY 'ERROR READING CUSTOMER FILE'
     *               MOVE CUSTFILE-STATUS TO IO-STATUS
     *               PERFORM Z-DISPLAY-IO-STATUS
     *               PERFORM Z-ABEND-PROGRAM
     *           END-IF
     *       END-IF
     *       EXIT.
     * }</pre>
     */
    private void custFileGetNext() {
        // COBOL line 93: READ CUSTFILE-FILE INTO CUSTOMER-RECORD.
        try {
            if (customerIterator.hasNext()) {
                this.customerRecord = customerIterator.next();
                this.custfileStatus = STATUS_OK;
                // COBOL line 95: MOVE 0 TO APPL-RESULT
                this.applResult = APPL_AOK;
                // COBOL line 96: DISPLAY CUSTOMER-RECORD  (first of duplicate displays)
                LOGGER.info("{}", customerRecord);
            } else {
                // COBOL FILE STATUS '10' — end of file
                this.custfileStatus = STATUS_EOF;
                // COBOL line 99: MOVE 16 TO APPL-RESULT
                this.applResult = APPL_EOF;
            }
        } catch (RuntimeException ex) {
            // Adapter-side I/O exceptions map to synthetic FILE STATUS '30' +
            // APPL-RESULT = 12 (matches COBOL line 101 "MOVE 12 TO APPL-RESULT").
            this.custfileStatus = STATUS_PERMANENT_ERROR;
            this.applResult = APPL_ERROR;
        }

        // M17 — Use exhaustive pattern matching on the sealed FileStatus
        // hierarchy to dispatch read outcomes (CONTINUE / END-OF-FILE /
        // ABEND), replacing the COBOL APPL-RESULT integer cascade with a
        // typed switch per AAP §0.6.10. The COBOL APPL-RESULT (still
        // updated above for byte-for-byte WS field fidelity) and the
        // FileStatus permit are equivalent — APPL-AOK ↔ Ok, APPL-EOF ↔
        // EndOfFile, APPL-ERROR ↔ IoError — and the typed switch makes
        // the closed taxonomy compile-time checked. No `default` arm is
        // permitted (sealed exhaustiveness; AAP §0.7.3).
        FileStatus status = FileStatus.fromCobolCode(custfileStatus);
        switch (status) {
            case FileStatus.Ok ok -> {
                // COBOL line 105: CONTINUE — fall through, no action
            }
            case FileStatus.EndOfFile eof -> {
                // COBOL line 108: MOVE 'Y' TO END-OF-FILE
                this.endOfFile = 'Y';
            }
            case FileStatus.NotFound nf -> {
                // FILE STATUS '23' on a SEQUENTIAL read is not expected
                // from COBOL semantics (it's a keyed-read code), but
                // exhaustiveness on the sealed type forces handling.
                // Mirror the COBOL "DISPLAY + ABEND" error path.
                LOGGER.error("ERROR READING CUSTOMER FILE");
                this.ioStatus = custfileStatus;
                zDisplayIoStatus();
                zAbendProgram();
            }
            case FileStatus.DuplicateKey dk -> {
                // FILE STATUS '22' on a SEQUENTIAL read is also not
                // expected (it's a WRITE/REWRITE code), but again
                // exhaustiveness forces handling. Mirror the COBOL
                // "DISPLAY + ABEND" error path.
                LOGGER.error("ERROR READING CUSTOMER FILE");
                this.ioStatus = custfileStatus;
                zDisplayIoStatus();
                zAbendProgram();
            }
            case FileStatus.IoError ioErr -> {
                // The catch-all error branch (covers '30' permanent error
                // and any other non-Ok/non-EOF code).
                // COBOL line 110: DISPLAY 'ERROR READING CUSTOMER FILE'
                LOGGER.error("ERROR READING CUSTOMER FILE");
                // COBOL line 111: MOVE CUSTFILE-STATUS TO IO-STATUS
                this.ioStatus = custfileStatus;
                // COBOL line 112: PERFORM Z-DISPLAY-IO-STATUS
                zDisplayIoStatus();
                // COBOL line 113: PERFORM Z-ABEND-PROGRAM
                zAbendProgram();
            }
        }
        // EXIT (line 116) — Java return implicit.
    }

    /**
     * Translates COBOL paragraph {@code 0000-CUSTFILE-OPEN} (lines 118-134 of
     * {@code app/cbl/CBCUS01C.cbl}). Acquires the sequential stream from the
     * {@link CustomerRepository} port; treats successful stream creation as FILE
     * STATUS '00' (COBOL {@code APPL-AOK}), any {@link RuntimeException} as FILE
     * STATUS '30' (synthetic permanent error).
     *
     * <pre>{@code
     *   0000-CUSTFILE-OPEN.
     *       MOVE 8 TO APPL-RESULT.
     *       OPEN INPUT CUSTFILE-FILE
     *       IF  CUSTFILE-STATUS = '00'
     *           MOVE 0 TO APPL-RESULT
     *       ELSE
     *           MOVE 12 TO APPL-RESULT
     *       END-IF
     *       IF  APPL-AOK
     *           CONTINUE
     *       ELSE
     *           DISPLAY 'ERROR OPENING CUSTFILE'
     *           MOVE CUSTFILE-STATUS TO IO-STATUS
     *           PERFORM Z-DISPLAY-IO-STATUS
     *           PERFORM Z-ABEND-PROGRAM
     *       END-IF
     *       EXIT.
     * }</pre>
     */
    private void custFileOpen() {
        // COBOL line 119: MOVE 8 TO APPL-RESULT — sentinel "operation pending".
        applResult = APPL_PENDING;

        // COBOL line 120: OPEN INPUT CUSTFILE-FILE
        try {
            this.customerStream = customerRepository.streamSequential();
            this.customerIterator = this.customerStream.iterator();
            this.custfileStatus = STATUS_OK;
            // COBOL lines 121-122: IF CUSTFILE-STATUS = '00' MOVE 0 TO APPL-RESULT
            this.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            // Adapter-side I/O exceptions map to synthetic FILE STATUS '30'.
            this.custfileStatus = STATUS_PERMANENT_ERROR;
            // COBOL line 124: MOVE 12 TO APPL-RESULT
            this.applResult = APPL_ERROR;
        }

        // COBOL lines 126-133: IF APPL-AOK CONTINUE ELSE display + abend
        if (applResult != APPL_AOK) {
            // COBOL line 129: DISPLAY 'ERROR OPENING CUSTFILE'
            LOGGER.error("ERROR OPENING CUSTFILE");
            // COBOL line 130: MOVE CUSTFILE-STATUS TO IO-STATUS
            this.ioStatus = custfileStatus;
            // COBOL line 131: PERFORM Z-DISPLAY-IO-STATUS
            zDisplayIoStatus();
            // COBOL line 132: PERFORM Z-ABEND-PROGRAM
            zAbendProgram();
        }
        // EXIT (line 134) — Java return implicit.
    }

    /**
     * Translates COBOL paragraph {@code 9000-CUSTFILE-CLOSE} (lines 136-152 of
     * {@code app/cbl/CBCUS01C.cbl}). Closes the open stream and clears the iterator.
     *
     * <pre>{@code
     *   9000-CUSTFILE-CLOSE.
     *       ADD 8 TO ZERO GIVING APPL-RESULT.
     *       CLOSE CUSTFILE-FILE
     *       IF  CUSTFILE-STATUS = '00'
     *           SUBTRACT APPL-RESULT FROM APPL-RESULT
     *       ELSE
     *           ADD 12 TO ZERO GIVING APPL-RESULT
     *       END-IF
     *       IF  APPL-AOK
     *           CONTINUE
     *       ELSE
     *           DISPLAY 'ERROR CLOSING CUSTOMER FILE'
     *           MOVE CUSTFILE-STATUS TO IO-STATUS
     *           PERFORM Z-DISPLAY-IO-STATUS
     *           PERFORM Z-ABEND-PROGRAM
     *       END-IF
     *       EXIT.
     * }</pre>
     */
    private void custFileClose() {
        // COBOL line 137: ADD 8 TO ZERO GIVING APPL-RESULT — sentinel "operation pending".
        applResult = APPL_PENDING;

        // COBOL line 138: CLOSE CUSTFILE-FILE
        try {
            if (customerStream != null) {
                customerStream.close();
                // Prevent double-close by closeStreamQuietly() in run()'s finally block.
                this.customerStream = null;
                this.customerIterator = null;
            }
            this.custfileStatus = STATUS_OK;
            // COBOL line 140: SUBTRACT APPL-RESULT FROM APPL-RESULT — zero it
            this.applResult = APPL_AOK;
        } catch (RuntimeException ex) {
            this.custfileStatus = STATUS_PERMANENT_ERROR;
            // COBOL line 142: ADD 12 TO ZERO GIVING APPL-RESULT
            this.applResult = APPL_ERROR;
        }

        // COBOL lines 144-151: IF APPL-AOK CONTINUE ELSE display + abend
        if (applResult != APPL_AOK) {
            // COBOL line 147: DISPLAY 'ERROR CLOSING CUSTOMER FILE'
            LOGGER.error("ERROR CLOSING CUSTOMER FILE");
            // COBOL line 148: MOVE CUSTFILE-STATUS TO IO-STATUS
            this.ioStatus = custfileStatus;
            // COBOL line 149: PERFORM Z-DISPLAY-IO-STATUS
            zDisplayIoStatus();
            // COBOL line 150: PERFORM Z-ABEND-PROGRAM
            zAbendProgram();
        }
        // EXIT (line 152) — Java return implicit.
    }

    /**
     * Translates COBOL paragraph {@code Z-ABEND-PROGRAM} (lines 154-158 of
     * {@code app/cbl/CBCUS01C.cbl}). Issues the non-recoverable abend that ends
     * program execution. The original COBOL invokes {@code CALL 'CEE3ABD'}, a
     * Language Environment (LE) service that performs an immediate, non-recoverable
     * abend with the given ABCODE.
     *
     * <p>In Java, this is modeled as a typed unchecked exception per AAP
     * &sect;0.6.10 (M17 restoration). The exception is a
     * {@link FileStatusException} that carries a {@link FileStatus.IoError}
     * payload built from the current {@link #ioStatus} value, allowing
     * downstream catch sites to pattern-match on the typed FILE STATUS
     * rather than parsing the exception message string. This preserves
     * "identical observable outcomes" (AAP &sect;0.7.1) &mdash; program
     * halts with an unrecoverable error and the same diagnostic
     * information that COBOL would have produced.</p>
     *
     * <pre>{@code
     *   Z-ABEND-PROGRAM.
     *       DISPLAY 'ABENDING PROGRAM'
     *       MOVE 0 TO TIMING
     *       MOVE 999 TO ABCODE
     *       CALL 'CEE3ABD'.
     * }</pre>
     *
     * <p>The Java exception's {@code ioError()} payload exposes the typed
     * permit so callers can do {@code switch (ex.ioError()) { case
     * IoError(int code, String desc) -> ... }} per the sealed-type
     * pattern (AAP &sect;0.3.2).</p>
     *
     * @throws FileStatusException always &mdash; translation of {@code CALL 'CEE3ABD'}
     */
    private void zAbendProgram() {
        // COBOL line 155: DISPLAY 'ABENDING PROGRAM'
        LOGGER.error("ABENDING PROGRAM");
        // COBOL lines 156-157 (MOVE 0 TO TIMING; MOVE 999 TO ABCODE) are folded
        // into the typed payload below for diagnostic traceability.
        //
        // M17 — Build the FileStatus.IoError permit from the current
        // ioStatus (the byte-fidelity 2-char COBOL FILE STATUS). For the
        // CEE3ABD abend path the description carries the ABCODE+TIMING
        // diagnostic so catch sites that don't pattern-match still see
        // the original information in the exception message.
        FileStatus current = FileStatus.fromCobolCode(ioStatus);
        FileStatus.IoError payload = (current instanceof FileStatus.IoError existing)
                ? new FileStatus.IoError(
                        existing.code(),
                        "CBCUS01C: CEE3ABD non-recoverable abend"
                                + " (ABCODE=" + CEE3ABD_ABCODE
                                + " TIMING=" + CEE3ABD_TIMING + ")")
                : new FileStatus.IoError(
                        // Non-IoError permit at abend time is unexpected
                        // (the read-loop only invokes zAbendProgram on
                        // error paths), but we synthesize a permanent
                        // error (-1) for defensive completeness.
                        -1,
                        "CBCUS01C: CEE3ABD non-recoverable abend"
                                + " (ABCODE=" + CEE3ABD_ABCODE
                                + " TIMING=" + CEE3ABD_TIMING
                                + " FILE_STATUS=" + ioStatus + ")");
        // COBOL line 158 (CALL 'CEE3ABD') translates to throwing the typed
        // unchecked exception below — the JVM main wrapper in carddemo-app
        // maps this to a non-zero process exit code mirroring an MVS user
        // abend, AND downstream catch sites can pattern-match on the
        // exposed FileStatus.IoError payload.
        throw new FileStatusException(payload);
    }

    /**
     * Translates COBOL paragraph {@code Z-DISPLAY-IO-STATUS} (lines 161-174 of
     * {@code app/cbl/CBCUS01C.cbl}). Formats {@link #ioStatus} into the 4-character
     * {@code IO-STATUS-04} display form ("NNNN") and emits a single log line.
     *
     * <p>The COBOL implementation uses a binary halfword overlay
     * ({@code TWO-BYTES-BINARY} REDEFINES with {@code TWO-BYTES-LEFT}/
     * {@code TWO-BYTES-RIGHT}) to convert the {@code IO-STAT2} character to its
     * numeric byte value. The Java translation reads
     * {@code ((int) ioStatus.charAt(1)) & 0xFF} which produces the same value
     * when the underlying execution environment uses ASCII (the assumed
     * environment for this Java translation per AAP &sect;0.6.5; EBCDIC support
     * is delegated to the {@code EbcdicTranscoder} in
     * {@code carddemo-adapter-file}).</p>
     *
     * <pre>{@code
     *   Z-DISPLAY-IO-STATUS.
     *       IF  IO-STATUS NOT NUMERIC
     *       OR  IO-STAT1 = '9'
     *           MOVE IO-STAT1 TO IO-STATUS-04(1:1)
     *           MOVE 0        TO TWO-BYTES-BINARY
     *           MOVE IO-STAT2 TO TWO-BYTES-RIGHT
     *           MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
     *           DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *       ELSE
     *           MOVE '0000' TO IO-STATUS-04
     *           MOVE IO-STATUS TO IO-STATUS-04(3:2)
     *           DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
     *       END-IF
     *       EXIT.
     * }</pre>
     */
    private void zDisplayIoStatus() {
        // Defensive guard: ioStatus should always be exactly 2 chars per COBOL
        // PIC X(02), but tolerate null / short input for robustness on the error
        // path. Pad with spaces if needed; truncate if longer.
        String safe;
        if (ioStatus == null) {
            safe = "  ";
        } else if (ioStatus.length() >= 2) {
            safe = ioStatus.substring(0, 2);
        } else {
            safe = (ioStatus + "  ").substring(0, 2);
        }
        char stat1 = safe.charAt(0);
        char stat2 = safe.charAt(1);

        // COBOL line 162-163: IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
        boolean numeric = isAsciiDigit(stat1) && isAsciiDigit(stat2);
        String formatted;
        if (!numeric || stat1 == '9') {
            // COBOL lines 164-167:
            //   MOVE IO-STAT1 TO IO-STATUS-04(1:1)
            //   MOVE 0 TO TWO-BYTES-BINARY
            //   MOVE IO-STAT2 TO TWO-BYTES-RIGHT  (low-order byte of halfword)
            //   MOVE TWO-BYTES-BINARY TO IO-STATUS-0403  (3-digit zoned decimal)
            // Net effect: position 1 = stat1, positions 2-4 = byte value (0-255)
            // of stat2 formatted as a 3-digit zero-padded decimal.
            int byteValue = ((int) stat2) & 0xFF;
            formatted = String.format(Locale.ROOT, "%c%03d", stat1, byteValue);
        } else {
            // COBOL lines 170-171:
            //   MOVE '0000' TO IO-STATUS-04
            //   MOVE IO-STATUS TO IO-STATUS-04(3:2)
            // Net effect: positions 1-2 = "00", positions 3-4 = IO-STATUS.
            formatted = "00" + safe;
        }
        // COBOL lines 168 / 172: DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
        LOGGER.info("FILE STATUS IS: NNNN{}", formatted);
        // EXIT (line 174) — Java return implicit.
    }

    /**
     * Returns {@code true} if {@code c} is a US-ASCII digit (a character in the
     * inclusive range {@code '0'..'9'}). Used by {@link #zDisplayIoStatus()} to
     * match COBOL {@code NUMERIC} class-test semantics on the {@code IO-STATUS}
     * field (PIC X(02), where the NUMERIC test passes iff both characters are
     * digit characters under the execution-environment encoding &mdash; ASCII
     * for this Java translation per AAP &sect;0.6.5).
     *
     * @param c the character to test
     * @return {@code true} iff {@code c} is one of {@code '0'..'9'}
     */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Java-side defensive cleanup — closeStreamQuietly()
    //  (no COBOL counterpart; purely a resource-safety measure)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Defensive helper invoked from {@link #run()}'s {@code finally} block. Closes
     * {@link #customerStream} silently if it is still open (e.g., because an abend
     * exception was thrown from inside the read loop before {@link #custFileClose()}
     * could run). Has no COBOL counterpart &mdash; purely a Java resource-safety
     * measure to avoid leaking OS file handles in the failure path.
     *
     * <p>Any exception raised during the close is intentionally swallowed: at this
     * point in the flow either {@link #zAbendProgram()} has already thrown the
     * abend (which we are unwinding) or the program is on its way out, and
     * shadowing the original abend with a close-failure exception would degrade
     * diagnostic clarity.</p>
     */
    private void closeStreamQuietly() {
        if (customerStream != null) {
            try {
                customerStream.close();
            } catch (RuntimeException ignored) {
                // Intentionally suppressed — see Javadoc.
            }
            this.customerStream = null;
            this.customerIterator = null;
        }
    }
}
