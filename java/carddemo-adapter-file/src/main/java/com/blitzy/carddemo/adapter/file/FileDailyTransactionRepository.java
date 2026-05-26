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
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.blitzy.carddemo.adapter.file;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.record.DalyTranRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link DailyTransactionRepository}, reading
 * the {@code DALYTRAN} sequential input dataset and appending to the
 * {@code DALYREJS} sequential append-only reject dataset via
 * {@link java.nio.file}. This adapter is the {@code java.nio.file}-backed
 * translation target for the dual-file COBOL access path used by the
 * batch posting engine.
 *
 * <h2>Source artefact lineage</h2>
 * Translated from:
 * <ul>
 *   <li>{@code app/cpy/CVTRA06Y.cpy} &mdash; the 350-byte
 *       {@code DALYTRAN-RECORD} 01-level group; byte-identical layout to
 *       {@code TRAN-RECORD} (CVTRA05Y) translated as
 *       {@link DalyTranRecord}.</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl} &mdash; the daily-transaction loader
 *       which declares {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN
 *       ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL} at
 *       {@code app/cbl/CBTRN01C.cbl:L29-L32} and drives the main loop via
 *       {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} in paragraph
 *       {@code 1000-DALYTRAN-GET-NEXT}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; the full posting engine
 *       and the SOLE writer of {@code DALYREJS}. It declares
 *       {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN ORGANIZATION IS
 *       SEQUENTIAL ACCESS MODE IS SEQUENTIAL} at
 *       {@code app/cbl/CBTRN02C.cbl:L29-L32} and
 *       {@code SELECT DALYREJS-FILE ASSIGN TO DALYREJS ORGANIZATION IS
 *       SEQUENTIAL ACCESS MODE IS SEQUENTIAL} at
 *       {@code app/cbl/CBTRN02C.cbl:L46-L49}. The reject writer
 *       paragraph {@code 2500-WRITE-REJECT-REC} (lines L385/L397/L410/
 *       L417 inside {@code 1500-A-LOOKUP-XREF}, {@code 1500-B-LOOKUP-ACCT},
 *       and the over-limit / post-expiration branches) issues a single
 *       {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} per rejected
 *       daily transaction.</li>
 *   <li>{@code app/data/ASCII/dailytran.txt} &mdash; the 9-record
 *       ASCII fixture used by the golden-record harness; line 1 contains
 *       {@code DALYTRAN-AMT="0000005047G"} encoding {@code +50.47} and
 *       line 2 contains {@code "0000009190}"} encoding {@code -91.90} as
 *       zoned-decimal with sign overpunch on the last byte.</li>
 * </ul>
 *
 * <h2>Reject record layout (430 bytes total)</h2>
 * Per the {@link DailyTransactionRepository#appendReject} contract and
 * the COBOL {@code REJECT-RECORD} layout at
 * {@code app/cbl/CBTRN02C.cbl:L176-L182}, each appended reject record
 * is exactly 430 bytes:
 * <pre>{@code
 *   bytes   0..349  -- REJECT-TRAN-DATA               (350 bytes  PIC X(350))
 *   bytes 350..353  -- WS-VALIDATION-FAIL-REASON      (  4 ASCII digits, PIC 9(04))
 *   bytes 354..429  -- WS-VALIDATION-FAIL-REASON-DESC ( 76 ASCII chars, PIC X(76))
 * }</pre>
 * The first 350 bytes are produced by {@link DalyTranRecord#encode()}
 * &mdash; the byte-for-byte encode of the offending input record per
 * AAP &sect;0.6.5 fidelity invariant. The reason code is encoded as
 * exactly 4 ASCII digits via {@code String.format("%04d", reason)}; the
 * description is encoded as exactly 76 ASCII bytes (truncated if longer,
 * right-space-padded if shorter; a {@code null} description is treated
 * as the empty string per the port contract).
 *
 * <h2>Observed reject reason codes (CBTRN02C source)</h2>
 * <ul>
 *   <li>{@code 100} &mdash; {@code 'INVALID CARD NUMBER FOUND'} (XREF
 *       lookup miss; {@code app/cbl/CBTRN02C.cbl:L385-L387}).</li>
 *   <li>{@code 101} &mdash; {@code 'ACCOUNT RECORD NOT FOUND'} (account
 *       lookup miss; {@code app/cbl/CBTRN02C.cbl:L397-L399}).</li>
 *   <li>{@code 102} &mdash; {@code 'OVERLIMIT TRANSACTION'} (computed
 *       running balance exceeds {@code ACCT-CREDIT-LIMIT};
 *       {@code app/cbl/CBTRN02C.cbl:L410-L412}).</li>
 *   <li>{@code 103} &mdash; {@code 'TRANSACTION RECEIVED AFTER ACCT
 *       EXPIRATION'} (transaction date is after account expiration
 *       date; {@code app/cbl/CBTRN02C.cbl:L417-L419}).</li>
 * </ul>
 * The Java translation MUST preserve these exact codes and exact
 * descriptions byte-for-byte per AAP &sect;0.7.1. New application-layer
 * code must NOT invent additional reason codes or alter the description
 * strings without first logging the change in {@code MIGRATION_NOTES.md}.
 *
 * <h2>Sequential access only</h2>
 * Both {@code DALYTRAN} and {@code DALYREJS} are declared
 * {@code ORGANIZATION IS SEQUENTIAL ACCESS MODE IS SEQUENTIAL} in the
 * COBOL source &mdash; neither file supports random key access,
 * {@code STARTBR} cursors, {@code REWRITE}, or {@code DELETE}. The port
 * surface intentionally exposes only {@link #streamSequential()} (read)
 * and {@link #appendReject(DalyTranRecord, int, String)} (write); there
 * is no {@code findById}, no {@code save}, no {@code update}, no
 * {@code delete}.
 *
 * <h2>Append semantics (CBTRN02C 2500-WRITE-REJECT-REC parity)</h2>
 * Each {@link #appendReject} call opens a fresh
 * {@link SeekableByteChannel} on {@code rejectFile} via
 * {@link Files#newByteChannel(Path, java.nio.file.OpenOption...)
 * Files.newByteChannel} with the option triplet
 * <code>{@link StandardOpenOption#WRITE WRITE} +
 * {@link StandardOpenOption#CREATE CREATE} +
 * {@link StandardOpenOption#APPEND APPEND}</code>, writes the assembled
 * 430-byte {@link ByteBuffer}, and closes the channel via
 * try-with-resources. The APPEND open option guarantees that each write
 * appends atomically at the current end-of-file regardless of any
 * concurrent appenders in other JVMs &mdash; matching the COBOL single
 * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} verb (430 bytes is
 * well under {@code PIPE_BUF}, so POSIX guarantees atomicity).
 *
 * <h2>Order preservation</h2>
 * Per AAP &sect;0.6.6 and the port interface contract: reject records
 * are appended in the order in which transactions fail validation
 * during the {@link #streamSequential()} scan. The
 * {@link #appendReject} entry point is serialised by a per-instance
 * monitor ({@code writeLock}) to guarantee call-order preservation
 * across concurrent threads &mdash; a JVM-level guarantee on top of
 * the OS-level atomicity. Virtual-thread fan-out at the application
 * layer that reorders the reject append sequence would change the
 * byte-for-byte content of {@code DALYREJS} and is FORBIDDEN per the
 * port interface contract.
 *
 * <h2>PCI / PAN-aware logging (AAP &sect;0.7.2)</h2>
 * The {@code DalyTranRecord} primary account number
 * ({@link DalyTranRecord#dalytranCardNum()}) must NEVER appear in
 * plaintext in logs. Every diagnostic log statement in this adapter
 * uses {@link DalyTranRecord#dalytranId()} (the 16-byte transaction
 * identifier; not card-sensitive) and/or
 * {@link DalyTranRecord#maskedPan()} (asterisk-leading, last-4-visible
 * representation of the PAN). The unmasked PAN must never be emitted to
 * SLF4J nor included in exception messages that may cross the
 * application / operator boundary.
 *
 * <h2>Codepage</h2>
 * The {@code charset} constructor argument is held for symmetry with
 * the read path (forwarded to {@link FixedWidthReader}). The
 * reject-record codec uses the same charset for the 4-digit reason
 * code and 76-character description so that production deployments
 * configured for an alternate codepage (e.g., IBM-1047 per AAP
 * &sect;0.6.5) emit consistent bytes throughout the validation
 * trailer. Composition roots that wire a non-ASCII charset must also
 * ensure {@link DalyTranRecord#encode()} produces matching bytes for
 * the leading 350-byte region; this is the responsibility of the
 * domain layer and is enforced by the golden-record harness in
 * {@code carddemo-tests}.
 *
 * <h2>Architectural compliance</h2>
 * Per AAP &sect;0.6.5 (file I/O exactness) and &sect;0.7.4 (forbidden
 * features):
 * <ul>
 *   <li>{@link java.nio.file} exclusively; {@link java.io.File},
 *       {@link java.io.FileInputStream}, {@link java.io.FileOutputStream},
 *       and {@link java.io.RandomAccessFile} are forbidden.</li>
 *   <li>No Spring / Spring Boot / Spring Data / Lombok.</li>
 *   <li>No {@code ThreadLocal} (use {@code ScopedValue} per AAP
 *       &sect;0.6.6); no preview features; no {@code double} or
 *       {@code float} for monetary values.</li>
 * </ul>
 *
 * @see DailyTransactionRepository
 * @see DalyTranRecord
 * @see FixedWidthReader
 * @since 1.0.0
 */
@CobolProgram(
        value = "DALYTRAN/DALYREJS",
        sourcePath = "app/cpy/CVTRA06Y.cpy",
        notes = "Sequential input (DALYTRAN, 350-byte records) + append-only "
                + "reject output (DALYREJS, 350 + 4-byte PIC 9(04) reason + 76-byte "
                + "PIC X(76) desc = 430 bytes per app/cbl/CBTRN02C.cbl:L176-L182). "
                + "Reject reasons 100-103 per CBTRN02C 2500-WRITE-REJECT-REC. "
                + "Implements ByteBuffer + SeekableByteChannel pattern per AAP §0.6.5. "
                + "PAN masking applies to all log statements (DalyTranRecord.maskedPan())."
)
public final class FileDailyTransactionRepository implements DailyTransactionRepository {

    /**
     * SLF4J logger for this adapter. Used for low-volume DEBUG-level
     * diagnostics on {@link #appendReject} (one log line per appended
     * reject record, masking the PAN per AAP &sect;0.7.2) and
     * {@link #close} (a single line documenting the no-op semantics).
     * The concrete logging backend (logback-classic 1.5.19) is provided
     * by the composition root in {@code carddemo-app}; this adapter
     * binds only to the SLF4J API per AAP &sect;0.6.12.
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(FileDailyTransactionRepository.class);

    /**
     * Fixed length of a {@code DALYTRAN-RECORD}: 350 bytes. Sourced
     * symbolically from {@link DalyTranRecord#RECORD_LENGTH} so the two
     * artefacts cannot drift &mdash; if the domain record's length
     * changes the adapter constant follows automatically. The COBOL
     * source of truth is {@code app/cpy/CVTRA06Y.cpy} where the 14
     * fields of the 01-level group sum to 350 bytes (16+2+4+10+100+11+
     * 9+50+50+10+16+26+26+20 = 350).
     */
    private static final int DALYTRAN_RECORD_LENGTH = DalyTranRecord.RECORD_LENGTH;

    /**
     * Byte length of the {@code WS-VALIDATION-FAIL-REASON} field:
     * {@code PIC 9(04)} &rArr; 4 ASCII digits. Sourced from
     * {@code app/cbl/CBTRN02C.cbl:L181}
     * ({@code 05 WS-VALIDATION-FAIL-REASON PIC 9(04)}).
     */
    private static final int REASON_CODE_LENGTH = 4;

    /**
     * Byte length of the {@code WS-VALIDATION-FAIL-REASON-DESC} field:
     * {@code PIC X(76)} &rArr; 76 ASCII characters. Sourced from
     * {@code app/cbl/CBTRN02C.cbl:L182}
     * ({@code 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}).
     */
    private static final int REASON_DESC_LENGTH = 76;

    /**
     * Composite length of one reject record:
     * {@value #DALYTRAN_RECORD_LENGTH} + {@value #REASON_CODE_LENGTH} +
     * {@value #REASON_DESC_LENGTH} = 430 bytes. Mirrors the JCL DCB
     * clause {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} on the
     * {@code DALYREJS} DD statement in {@code app/jcl/POSTTRAN.jcl}
     * and matches the formal port-interface contract at
     * {@link DailyTransactionRepository#appendReject}.
     */
    private static final int REJECT_RECORD_LENGTH =
            DALYTRAN_RECORD_LENGTH + REASON_CODE_LENGTH + REASON_DESC_LENGTH;

    /**
     * Inclusive upper bound for {@code WS-VALIDATION-FAIL-REASON}:
     * {@code PIC 9(04)} permits values in {@code [0, 9999]}. Values
     * outside this range cannot be encoded in 4 digits without losing
     * information and are rejected with {@link IllegalArgumentException}
     * per the port contract at
     * {@link DailyTransactionRepository#appendReject}.
     */
    private static final int REASON_MAX = 9_999;

    /**
     * ASCII space byte (0x20) used to right-pad the description field
     * when the supplied description is shorter than
     * {@value #REASON_DESC_LENGTH} bytes. Matches the COBOL
     * {@code MOVE 'literal' TO WS-VALIDATION-FAIL-REASON-DESC} group-move
     * semantics where the source literal is automatically padded to the
     * destination {@code PIC X(76)} width with trailing spaces.
     */
    private static final byte ASCII_SPACE = (byte) ' ';

    /**
     * {@code DALYTRAN} sequential input file. Held as a {@link Path}
     * (per AAP &sect;0.6.5 mandate to avoid {@link java.io.File}).
     * Used as the constructor argument forwarded to
     * {@link FixedWidthReader} and appears in error messages from
     * {@link #streamSequential()}.
     */
    private final Path inputFile;

    /**
     * {@code DALYREJS} sequential append-only output file. Held as a
     * {@link Path}. Opened by {@link #appendReject} with
     * {@code WRITE + CREATE + APPEND} on every call.
     */
    private final Path rejectFile;

    /**
     * Configured {@link Charset}, forwarded to {@link FixedWidthReader}
     * for read-path symmetry and used by {@link #appendReject} to encode
     * the 4-digit reason code and the 76-byte description. IBM-1047
     * is the default per AAP &sect;0.6.5; per-file overrides are
     * configurable through the composition root's
     * {@code application.properties}.
     */
    private final Charset charset;

    /**
     * Foundational sequential reader for the {@code DALYTRAN} input
     * file. Constructed once per repository instance to amortise the
     * configuration cost; the reader itself is stateless and may
     * service multiple concurrent {@link #streamSequential()} calls.
     */
    private final FixedWidthReader reader;

    /**
     * Per-instance write monitor. Held by {@link #appendReject} to
     * serialise concurrent reject appends across virtual threads,
     * preserving the COBOL single-writer call ordering contract. The
     * lock is local to one JVM instance only; cross-process atomicity
     * is provided by the OS-level {@code O_APPEND} semantics on the
     * underlying {@link SeekableByteChannel}.
     */
    private final Object writeLock = new Object();

    /**
     * Convenience constructor that defaults the character set to the
     * EBCDIC code page mandated by AAP &sect;0.6.5
     * ({@value com.blitzy.carddemo.adapter.file.EbcdicTranscoder#DEFAULT_CHARSET_NAME}).
     * Delegates to the canonical
     * {@link #FileDailyTransactionRepository(Path, Path, Charset)}
     * constructor.
     *
     * <p>This overload matches the established codebase idiom shared by
     * the sibling file-backed adapters
     * ({@link FileAccountRepository}, {@link FileCardXrefRepository},
     * {@link FileTransactionRepository},
     * {@link FileTransactionCategoryBalanceRepository}) so that the
     * composition root in {@code carddemo-app} can wire all repositories
     * uniformly without each call site re-specifying the IBM-1047
     * default. The canonical 3-argument constructor remains the
     * preferred entry point when the composition root resolves a
     * per-file charset override from {@code application.properties}
     * (e.g., {@code carddemo.file.dailytran.charset}).
     *
     * @param inputFile  the {@code DALYTRAN} sequential input file path;
     *                   must not be {@code null}
     * @param rejectFile the {@code DALYREJS} sequential append-only
     *                   reject output file path; must not be
     *                   {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws java.nio.charset.UnsupportedCharsetException
     *         if the JVM does not provide the {@code IBM-1047} code
     *         page (always present in standard JDK distributions; this
     *         clause documents the contract rather than warning about
     *         a realistic failure mode)
     */
    public FileDailyTransactionRepository(Path inputFile, Path rejectFile) {
        this(inputFile, rejectFile, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs a new file-backed adapter bound to the given input and
     * reject paths. The {@code DALYTRAN} input file need not exist at
     * construction time &mdash; {@link FixedWidthReader} treats a
     * missing file as functionally equivalent to an empty file (zero
     * records) per the NOTFND parity contract documented on that class.
     * The {@code DALYREJS} reject file is created on the first call to
     * {@link #appendReject} via {@link StandardOpenOption#CREATE} and
     * does not need to exist beforehand.
     *
     * @param inputFile  the {@code DALYTRAN} sequential input file path;
     *                   must not be {@code null}
     * @param rejectFile the {@code DALYREJS} sequential append-only
     *                   reject output file path; must not be
     *                   {@code null}
     * @param charset    character set forwarded to the
     *                   {@link FixedWidthReader} read path and used to
     *                   encode the reason code and description bytes
     *                   in the reject record. Defaults to IBM-1047 per
     *                   AAP &sect;0.6.5 when supplied by the composition
     *                   root; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileDailyTransactionRepository(Path inputFile, Path rejectFile, Charset charset) {
        this.inputFile = Objects.requireNonNull(inputFile, "inputFile");
        this.rejectFile = Objects.requireNonNull(rejectFile, "rejectFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(inputFile, DALYTRAN_RECORD_LENGTH, charset);
        if (LOG.isDebugEnabled()) {
            LOG.debug("FileDailyTransactionRepository configured: inputFile={}, "
                            + "rejectFile={}, charset={}, dalytranRecordLength={}, "
                            + "rejectRecordLength={}",
                    inputFile, rejectFile, charset,
                    DALYTRAN_RECORD_LENGTH, REJECT_RECORD_LENGTH);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link FixedWidthReader#streamSequential()} and
     * maps each raw 350-byte buffer through
     * {@link DalyTranRecord#parse(byte[])} to produce a typed stream.
     * The returned stream is lazy: a fresh
     * {@link SeekableByteChannel} is held open by the underlying
     * {@link FixedWidthReader} until the stream is closed or fully
     * consumed. Callers MUST close the stream (typically via
     * try-with-resources) to release the file channel.
     *
     * <p>An {@link IOException} thrown when opening the underlying
     * {@code DALYTRAN} file is wrapped in {@link UncheckedIOException}
     * because {@link Stream} return types cannot declare checked
     * exceptions. This mirrors the COBOL
     * {@code PERFORM 9999-ABEND-PROGRAM} branch in
     * {@code app/cbl/CBTRN01C.cbl:L350-L370} which abends the entire
     * batch step on an unrecoverable open error. A missing
     * {@code DALYTRAN} file, however, is NOT an error: per the
     * {@link FixedWidthReader} NOTFND-parity contract it yields an
     * empty stream and the wrapping {@link UncheckedIOException} is
     * never thrown.
     *
     * <p>Records are returned in the physical order they appear in the
     * source file &mdash; reordering would change the observable
     * output of any downstream {@link #appendReject} sequence and is
     * FORBIDDEN per AAP &sect;0.6.6 and the port-interface ordering
     * contract.
     *
     * @return a lazy, ordered, single-use {@link Stream} of every
     *         {@link DalyTranRecord} in the underlying
     *         {@code DALYTRAN} file, in physical input order. Never
     *         {@code null}; the empty stream represents an empty (or
     *         missing) {@code DALYTRAN} file, which is a valid COBOL
     *         terminating state.
     */
    @Override
    public Stream<DalyTranRecord> streamSequential() {
        try {
            return reader.streamSequential().map(DalyTranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Error opening DALYTRAN for sequential read: " + inputFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Assembles the 430-byte reject record image in a
     * {@link ByteBuffer} from three regions:
     * <ol>
     *   <li>{@code record.encode()} &mdash; the 350-byte
     *       {@link DalyTranRecord} byte image, asserted to be exactly
     *       {@value #DALYTRAN_RECORD_LENGTH} bytes by the
     *       record's encode contract.</li>
     *   <li>{@code String.format("%04d", validationFailReason)} byte
     *       image &mdash; exactly 4 ASCII digits, zero-left-padded.
     *       Validated to be in {@code [0, 9999]} before formatting.</li>
     *   <li>{@code padOrTruncate(validationFailDescription,
     *       REASON_DESC_LENGTH)} byte image &mdash; exactly 76 ASCII
     *       characters, right-space-padded if shorter, truncated if
     *       longer; a {@code null} description is normalised to the
     *       empty string per the port contract.</li>
     * </ol>
     * The assembled buffer is flipped and written via a fresh
     * {@link SeekableByteChannel} opened with
     * {@link StandardOpenOption#WRITE} + {@link StandardOpenOption#CREATE}
     * + {@link StandardOpenOption#APPEND}. The channel is closed via
     * try-with-resources on each call so the adapter holds zero
     * persistent file handles between calls.
     *
     * <p>The synchronized {@code writeLock} guarantees that concurrent
     * appends from multiple threads in the same JVM are serialised in
     * call order. Cross-process atomicity is provided independently by
     * the OS-level {@code O_APPEND} semantics on the underlying file
     * descriptor.
     *
     * <p>Any {@link IOException} raised by the channel open or the
     * channel write is wrapped in {@link UncheckedIOException},
     * mirroring the COBOL {@code PERFORM 9999-ABEND-PROGRAM} branch
     * in paragraph {@code 2500-WRITE-REJECT-REC}
     * ({@code app/cbl/CBTRN02C.cbl:L454-L463}) which abends the
     * entire batch step on an unrecoverable write error.
     *
     * @param record                    the offending daily transaction
     *                                  record; never {@code null}. Its
     *                                  byte image is appended verbatim
     *                                  into the {@code REJECT-TRAN-DATA}
     *                                  region (bytes 0..349) of the
     *                                  430-byte reject record.
     * @param validationFailReason      the 4-digit numeric reason code
     *                                  corresponding to the COBOL
     *                                  {@code WS-VALIDATION-FAIL-REASON
     *                                  PIC 9(04)} field. Must be in
     *                                  the range {@code [0, 9999]}
     *                                  inclusive.
     * @param validationFailDescription the description text
     *                                  corresponding to the COBOL
     *                                  {@code WS-VALIDATION-FAIL-REASON-DESC
     *                                  PIC X(76)} field. Truncated to
     *                                  76 chars if longer,
     *                                  right-space-padded if shorter;
     *                                  {@code null} is treated as the
     *                                  empty string per the port
     *                                  contract.
     * @throws NullPointerException     if {@code record} is {@code null}.
     * @throws IllegalArgumentException if {@code validationFailReason}
     *                                  is outside the
     *                                  {@code [0, 9999]} range.
     * @throws IllegalStateException    if
     *                                  {@code DalyTranRecord.encode()}
     *                                  returns a buffer whose length
     *                                  is not exactly
     *                                  {@value #DALYTRAN_RECORD_LENGTH}
     *                                  bytes (a domain-layer defect).
     * @throws UncheckedIOException     on any underlying
     *                                  {@link IOException} from the
     *                                  channel open or write
     *                                  (corresponds to the COBOL
     *                                  {@code 9999-ABEND-PROGRAM}
     *                                  branch).
     */
    @Override
    public void appendReject(DalyTranRecord record,
                             int validationFailReason,
                             String validationFailDescription) {
        Objects.requireNonNull(record, "record");
        if (validationFailReason < 0 || validationFailReason > REASON_MAX) {
            throw new IllegalArgumentException(
                    "validationFailReason must fit PIC 9(04) [0.." + REASON_MAX
                            + "]: " + validationFailReason);
        }

        // Region 1: encode the 350-byte DALYTRAN body. The encode()
        // contract guarantees DALYTRAN_RECORD_LENGTH bytes; the
        // assertion below catches a future domain-layer regression.
        byte[] recBytes = record.encode();
        if (recBytes.length != DALYTRAN_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "DalyTranRecord.encode() returned " + recBytes.length
                            + " bytes; expected " + DALYTRAN_RECORD_LENGTH);
        }

        // Region 2: 4-byte PIC 9(04) reason code, zero-left-padded.
        // Locale.ROOT keeps the formatter independent of any platform
        // locale (avoids exotic digit substitutions on non-Western
        // JVMs).
        byte[] reasonCodeBytes =
                String.format(Locale.ROOT, "%04d", validationFailReason)
                        .getBytes(charset);

        // Region 3: 76-byte PIC X(76) description, right-space-padded
        // or truncated. null is normalised to "" per the port contract.
        String description = validationFailDescription == null
                ? ""
                : validationFailDescription;
        byte[] reasonDescBytes = padOrTruncate(description, REASON_DESC_LENGTH);

        // Assemble the 430-byte image in a ByteBuffer. Three put() calls
        // emit the contiguous regions in order; flip() prepares the
        // buffer for the SeekableByteChannel.write() call.
        ByteBuffer buffer = ByteBuffer.allocate(REJECT_RECORD_LENGTH);
        buffer.put(recBytes);
        buffer.put(reasonCodeBytes);
        buffer.put(reasonDescBytes);
        buffer.flip();

        // Serialise concurrent appenders in the same JVM. The OS-level
        // O_APPEND semantics provide cross-process atomicity; this
        // JVM-level lock provides deterministic call-order preservation
        // matching the COBOL single-writer model.
        synchronized (writeLock) {
            // Ensure the parent directory exists so a fresh deployment
            // with no pre-created reject directory does not abort the
            // entire batch on the first reject. Files.createDirectories
            // is idempotent: it is a no-op when the directory already
            // exists.
            try {
                Path parent = rejectFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Error creating parent directory for DALYREJS reject file: "
                                + rejectFile, e);
            }

            try (SeekableByteChannel channel = Files.newByteChannel(
                    rejectFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                channel.write(buffer);
            } catch (IOException e) {
                // PAN-safe error message: cite the 16-byte
                // dalytranId (not card-sensitive) and the reason
                // code, never the dalytranCardNum.
                throw new UncheckedIOException(
                        "Error appending DALYREJS reject for tranId="
                                + record.dalytranId() + " reason="
                                + validationFailReason, e);
            }
        }

        // Diagnostic DEBUG log uses tranId (not card-sensitive) and
        // maskedPan (asterisks + last 4 digits) to satisfy AAP §0.7.2
        // PCI mandate. Guarded by isDebugEnabled() so the maskedPan()
        // computation is skipped at production INFO level.
        if (LOG.isDebugEnabled()) {
            LOG.debug("appendReject tranId={} pan={} reason={} status=ok",
                    record.dalytranId(), record.maskedPan(),
                    validationFailReason);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation is a no-op: each {@link #appendReject}
     * call manages its own {@link SeekableByteChannel} lifecycle via
     * try-with-resources, and {@link #streamSequential()} returns a
     * {@link Stream} whose {@code onClose} hook (installed by
     * {@link FixedWidthReader}) releases the underlying read channel.
     * Therefore the repository instance itself holds no persistent
     * file handles between method calls and has nothing to release on
     * {@link #close()}.
     *
     * <p>Per the port-interface contract on
     * {@link DailyTransactionRepository#close()}, this method is
     * idempotent: multiple invocations have no observable effect
     * beyond the single DEBUG log line. This mirrors the COBOL
     * {@code CLOSE DALYTRAN-FILE} verb in paragraph
     * {@code 9000-DALYTRAN-CLOSE} of CBTRN01C/CBTRN02C and the
     * {@code CLOSE DALYREJS-FILE} verb in paragraph
     * {@code 9300-DALYREJS-CLOSE} of CBTRN02C &mdash; both unconditional
     * end-of-program cleanup steps that succeed regardless of
     * upstream state.
     */
    @Override
    public void close() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("FileDailyTransactionRepository.close() no-op: "
                            + "inputFile={}, rejectFile={}",
                    inputFile, rejectFile);
        }
    }

    /**
     * Pads {@code value} with ASCII spaces ({@code 0x20}) to exactly
     * {@code length} bytes, or truncates if longer. Used to enforce
     * the fixed {@code PIC X(76)} width of the reject-record
     * description region.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If {@code value.getBytes(charset).length >= length}: copies
     *       the first {@code length} bytes verbatim. Truncation is a
     *       normal outcome per the port contract and does not raise
     *       an error.</li>
     *   <li>Otherwise: copies all of {@code value}'s bytes into the
     *       leading region of the output and right-pads the remainder
     *       with ASCII spaces.</li>
     * </ul>
     *
     * <p>The output array is freshly allocated on every call to avoid
     * any aliasing or accidental retention of the input value across
     * calls.
     *
     * @param value  the string to encode and pad; never {@code null}
     *               (the caller normalises {@code null} to the empty
     *               string before invocation)
     * @param length the exact fixed length of the output array in
     *               bytes; must be {@code > 0}
     * @return freshly allocated {@code byte[length]} containing the
     *         encoded value, right-space-padded or truncated to
     *         exactly {@code length} bytes
     */
    private byte[] padOrTruncate(String value, int length) {
        byte[] valueBytes = value.getBytes(charset);
        byte[] result = new byte[length];
        if (valueBytes.length >= length) {
            System.arraycopy(valueBytes, 0, result, 0, length);
        } else {
            System.arraycopy(valueBytes, 0, result, 0, valueBytes.length);
            // Fill remaining region with ASCII space (0x20). The COBOL
            // group-move semantics for MOVE 'literal' TO PIC X(76)
            // produce the same right-space-padded result.
            for (int i = valueBytes.length; i < length; i++) {
                result[i] = ASCII_SPACE;
            }
        }
        return result;
    }
}
