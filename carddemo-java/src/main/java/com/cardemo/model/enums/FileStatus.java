package com.cardemo.model.enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Type-safe catalogue of the COBOL/VSAM two-character {@code FILE STATUS} codes
 * handled by the CardDemo estate.
 *
 * <p>This enum is the Java 25 replacement for the scattered
 * {@code IF *-STATUS = '00'} / {@code '10'} / {@code '23'} comparisons that the
 * legacy AWS CardDemo mainframe application performed after every VSAM
 * {@code OPEN}/{@code READ}/{@code WRITE}/{@code REWRITE} operation. The
 * authoritative source for the handled value set is the batch posting program
 * {@code app/cbl/CBTRN02C.cbl}, where each file's two-byte status field is
 * declared as a pair of {@code PIC X} sub-fields (for example
 * {@code 01 DALYTRAN-STATUS} / {@code 05 DALYTRAN-STAT1 PIC X} /
 * {@code 05 DALYTRAN-STAT2 PIC X} at lines 103-105) and compared against the
 * literal status values throughout the procedure division.</p>
 *
 * <p>Within {@code CBTRN02C.cbl} the only two-character status literals the
 * program ever compares against are {@code '00'} (successful I/O, checked
 * pervasively &mdash; for example at lines 239, 257, 276, 294, 312, 330 and
 * 347), {@code '10'} (end-of-file on a sequential {@code READ}, line 351, which
 * drives the {@code APPL-EOF} / {@code END-OF-FILE = 'Y'} branch) and
 * {@code '23'} (record not found on the {@code INVALID KEY} path, line 481:
 * {@code IF TCATBALF-STATUS = '00' OR '23'}). The remaining two constants,
 * {@code '22'} (duplicate key on {@code WRITE}) and {@code '35'} (file not
 * available on {@code OPEN}), are the standard VSAM status codes that the
 * migration's typed exception hierarchy maps, as recorded in the authoritative
 * blueprint ({@code 00} = success, {@code 23} = record not found, {@code 35} =
 * file not found, {@code 22} = duplicate key). Per the migration's Minimal
 * Change Clause no further IBM status codes are enumerated speculatively.</p>
 *
 * <p><strong>External-interface contract.</strong> The two-character codes are
 * part of the external interface contract and are preserved verbatim, including
 * the significant leading zero (for example {@code "00"} is never collapsed to
 * {@code "0"}). The codes therefore round-trip byte-faithfully against the
 * COBOL two-byte status fields.</p>
 *
 * <p><strong>Role in the architecture.</strong> This enum is the seam between
 * low-level VSAM status handling and the typed
 * {@code com.cardemo.exception.*} hierarchy. It is a deliberately
 * dependency-free leaf: it depends on nothing beyond {@code java.lang} and
 * {@code java.util}, holds no mutable state and performs no I/O. The
 * status&rarr;exception translation (for example {@code "23"} &rarr;
 * {@code RecordNotFoundException}, {@code "22"} &rarr;
 * {@code DuplicateRecordException}) is owned by the downstream
 * {@code com.cardemo.service.shared.FileStatusMapper}, <em>not</em> by this
 * enum, so that readers, writers and repositories can reuse this value set
 * without pulling in the exception package.</p>
 *
 * <p><strong>Traceability:</strong> derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference and is
 * never copied into this repository.</p>
 */
public enum FileStatus {

    /**
     * Successful completion of a VSAM I/O operation.
     *
     * <p>Maps the COBOL {@code FILE STATUS} value {@code '00'}, the canonical
     * "all OK" outcome that {@code CBTRN02C.cbl} checks after every
     * {@code OPEN} and {@code READ} (for example {@code IF DALYTRAN-STATUS =
     * '00'} at line 239 and line 347) before continuing normal processing.</p>
     */
    // COBOL substitution: FILE STATUS '00' observed pervasively in
    // app/cbl/CBTRN02C.cbl (e.g. lines 239/257/276/294/312/330/347) as the
    // IF *-STATUS = '00' -> continue (APPL-AOK) branch.
    SUCCESS("00", "Successful completion"),

    /**
     * End-of-file reached on a sequential {@code READ}.
     *
     * <p>Maps the COBOL {@code FILE STATUS} value {@code '10'}. In
     * {@code CBTRN02C.cbl} line 351, {@code IF DALYTRAN-STATUS = '10'} sets the
     * {@code APPL-EOF} result and the {@code END-OF-FILE = 'Y'} flag that
     * terminates the daily-transaction read loop.</p>
     */
    // COBOL substitution: FILE STATUS '10' observed in app/cbl/CBTRN02C.cbl
    // line 351 (IF DALYTRAN-STATUS = '10' -> APPL-EOF / END-OF-FILE = 'Y').
    END_OF_FILE("10", "End of file"),

    /**
     * Duplicate key detected on a {@code WRITE}.
     *
     * <p>Maps the standard VSAM {@code FILE STATUS} value {@code '22'}, raised
     * when a {@code WRITE} would create a duplicate primary key (the
     * {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} write paths in the
     * online add programs such as {@code COTRN02C} and {@code COUSR01C}). The
     * downstream {@code FileStatusMapper} pairs this code with
     * {@code DuplicateRecordException}.</p>
     */
    // COBOL substitution: standard VSAM FILE STATUS '22' (duplicate key on
    // WRITE); paired by FileStatusMapper with com.cardemo.exception
    // DuplicateRecordException per blueprint FILE STATUS mapping.
    DUPLICATE_KEY("22", "Duplicate key"),

    /**
     * Record not found / {@code INVALID KEY} on a keyed {@code READ}.
     *
     * <p>Maps the COBOL {@code FILE STATUS} value {@code '23'}. In
     * {@code CBTRN02C.cbl} the keyed {@code READ TCATBAL-FILE} (line 474) takes
     * the {@code INVALID KEY} branch (line 475) and is then treated as
     * acceptable by {@code IF TCATBALF-STATUS = '00' OR '23'} (line 481). The
     * downstream {@code FileStatusMapper} pairs this code with
     * {@code RecordNotFoundException}.</p>
     */
    // COBOL substitution: FILE STATUS '23' observed in app/cbl/CBTRN02C.cbl
    // line 481 (IF TCATBALF-STATUS = '00' OR '23'; INVALID KEY at line 475);
    // paired by FileStatusMapper with RecordNotFoundException.
    RECORD_NOT_FOUND("23", "Record not found"),

    /**
     * File / dataset not available on {@code OPEN}.
     *
     * <p>Maps the standard VSAM {@code FILE STATUS} value {@code '35'}, raised
     * when an {@code OPEN} references a dataset that does not exist. In
     * {@code CBTRN02C.cbl} a non-{@code '00'} {@code OPEN} status drives the
     * {@code 9999-ABEND-PROGRAM} failure path. The downstream
     * {@code FileStatusMapper} pairs this code with a file-not-found
     * exception.</p>
     */
    // COBOL substitution: standard VSAM FILE STATUS '35' (file not available on
    // OPEN); the non-'00' OPEN status in app/cbl/CBTRN02C.cbl routes to
    // 9999-ABEND-PROGRAM. Paired by FileStatusMapper with a file-not-found type.
    FILE_NOT_FOUND("35", "File not found");

    /**
     * The exact two-character COBOL {@code FILE STATUS} code.
     *
     * <p>This value preserves the leading zero and is never trimmed or
     * normalized, because the two-character form is part of the external
     * interface contract with the legacy VSAM status fields.</p>
     */
    // COBOL substitution: the two-byte FILE STATUS field (e.g. 01 DALYTRAN-STATUS
    // with 05 *-STAT1 PIC X / 05 *-STAT2 PIC X) is represented as an exact
    // two-character String to preserve byte fidelity with the external contract.
    private final String code;

    /** Short, human-readable description of the status, used in diagnostics. */
    private final String description;

    /**
     * Binds each constant to its byte-faithful COBOL status code and a
     * human-readable description. Enum constructors are implicitly private.
     *
     * @param code        the exact two-character COBOL {@code FILE STATUS}
     *                    value (leading zero preserved)
     * @param description a short, human-readable description of the status
     */
    FileStatus(final String code, final String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Immutable {@code code -> FileStatus} index used for O(1) lookup by
     * {@link #fromCode(String)}.
     *
     * <p>The map is populated in a static initializer that runs after every
     * enum constant has been constructed (avoiding the enum-constructor /
     * static-field ordering pitfall), and is wrapped with
     * {@link Collections#unmodifiableMap(Map)} so no mutable static state is
     * exposed. The code literals therefore live in exactly one place &mdash; on
     * the enum constants themselves.</p>
     */
    private static final Map<String, FileStatus> BY_CODE;

    static {
        final Map<String, FileStatus> lookup = new HashMap<>();
        for (final FileStatus status : values()) {
            lookup.put(status.code, status);
        }
        BY_CODE = Collections.unmodifiableMap(lookup);
    }

    /**
     * Returns the exact two-character COBOL {@code FILE STATUS} code for this
     * constant.
     *
     * @return the two-character status code (for example {@code "00"} or
     *         {@code "23"}), with the leading zero preserved
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the short, human-readable description of this status.
     *
     * @return the status description (for example {@code "Successful
     *         completion"})
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves a {@code FileStatus} from its exact two-character COBOL
     * {@code FILE STATUS} code.
     *
     * <p>Matching is exact: the supplied value is compared against the stored
     * two-character codes without trimming or normalization, so the significant
     * leading zero is honored (for example {@code "00"} resolves to
     * {@link #SUCCESS}, whereas {@code "0"} does not match). A {@code null} or
     * unrecognized code is rejected, because it indicates a status value the
     * estate does not handle.</p>
     *
     * @param code the two-character {@code FILE STATUS} code to resolve
     * @return the matching {@code FileStatus}
     * @throws IllegalArgumentException if {@code code} is {@code null} or
     *         matches no known file status
     */
    public static FileStatus fromCode(final String code) {
        final FileStatus status = BY_CODE.get(code);
        if (status == null) {
            throw new IllegalArgumentException("Unknown file status code: " + code);
        }
        return status;
    }

    /**
     * Indicates whether this status represents a successful I/O operation.
     *
     * <p>Mirrors the COBOL {@code IF *-STATUS = '00'} &rarr; continue branch
     * that {@code CBTRN02C.cbl} uses to proceed with normal processing.</p>
     *
     * @return {@code true} if this is {@link #SUCCESS}; {@code false} otherwise
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Indicates whether this status represents end-of-file on a sequential
     * read.
     *
     * <p>Mirrors the COBOL {@code IF DALYTRAN-STATUS = '10'} &rarr; end-of-file
     * branch (CBTRN02C.cbl line 351) that terminates a sequential read
     * loop.</p>
     *
     * @return {@code true} if this is {@link #END_OF_FILE}; {@code false}
     *         otherwise
     */
    public boolean isEndOfFile() {
        return this == END_OF_FILE;
    }
}
