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
package com.blitzy.carddemo.adapter.file;

import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.DailyTransactionRepository;
import com.blitzy.carddemo.domain.record.DalyTranRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link DailyTransactionRepository} reading
 * the {@code DALYTRAN.PS} sequential input file and appending to the
 * {@code DALYREJS} reject output file via {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the dual-file access path for {@code app/cpy/CVTRA06Y.cpy}
 * &mdash; the 350-byte {@code DALYTRAN-RECORD} layout (identical to the
 * 350-byte {@code TRAN-RECORD} of CVTRA05Y). The COBOL CBTRN02C posting
 * engine ({@code app/cbl/CBTRN02C.cbl}) opens {@code DALYTRAN INPUT}
 * sequentially and {@code DALYREJS OUTPUT} for sequential write-append.
 *
 * <h2>Reject record layout (430 bytes total)</h2>
 * Per the {@link DailyTransactionRepository#appendReject} contract and
 * the COBOL {@code REJECT-RECORD} layout at
 * {@code app/cbl/CBTRN02C.cbl:L176-L182} &mdash; AND the JCL DCB clause
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} on the {@code DALYREJS} DD
 * statement in {@code app/jcl/POSTTRAN.jcl} &mdash; each reject record
 * is exactly 430 bytes:
 * <pre>{@code
 *   bytes   0..349  -- REJECT-TRAN-DATA          (the 350-byte DalyTranRecord)
 *   bytes 350..353  -- WS-VALIDATION-FAIL-REASON (4 ASCII digits, zero-left-padded)
 *   bytes 354..429  -- WS-VALIDATION-FAIL-REASON-DESC (76 ASCII chars, right-space-padded)
 * }</pre>
 * Total: 430 bytes per appended record. The reason code is encoded as
 * exactly 4 ASCII digits via {@code String.format("%04d", reason)}; the
 * description is encoded as exactly 76 ASCII bytes (truncated if longer,
 * right-space-padded if shorter; {@code null} treated as empty string).
 *
 * <h2>Append semantics</h2>
 * Each {@link #appendReject} call opens the file with
 * {@link StandardOpenOption#WRITE} + {@link StandardOpenOption#CREATE} +
 * {@link StandardOpenOption#APPEND} so the file is created if missing
 * and the 430-byte record is atomically appended. The
 * {@link Files#write(Path, byte[], java.nio.file.OpenOption...)} call
 * is itself atomic with respect to the single record &mdash; matching
 * the COBOL single {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} verb.
 *
 * <h2>Order preservation</h2>
 * Per AAP &sect;0.6.6 &mdash; no reordering is permitted. Reject records
 * are appended in the order in which they fail validation during the
 * upstream {@link #streamSequential()} scan. The file adapter is
 * single-threaded by construction (each {@code appendReject} call is a
 * synchronous append); callers using virtual-thread fan-out must NOT
 * call {@code appendReject} concurrently because byte-level append
 * ordering would become non-deterministic.
 *
 * <h2>Codepage</h2>
 * The COBOL contract emits ASCII for both the reason code (4 digits)
 * and the description (76 chars) in alignment with the
 * {@link StandardCharsets#US_ASCII} encoding regardless of the input
 * dataset codepage. Per AAP &sect;0.7.1 idiom-for-idiom mandate, this
 * is preserved exactly.
 *
 * @see DailyTransactionRepository
 * @see DalyTranRecord
 * @see FixedWidthReader
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVTRA06Y",
        sourcePath = "app/cpy/CVTRA06Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for DALYTRAN (350-byte sequential input) + DALYREJS "
                + "(430-byte sequential output with PIC 9(04) reason + PIC X(76) description). "
                + "LRECL=430 confirmed via app/jcl/POSTTRAN.jcl DCB clause and the "
                + "DailyTransactionRepository port contract. Append uses WRITE+CREATE+APPEND "
                + "StandardOpenOption per AAP §0.6.9; single-threaded write ordering enforced."
)
public final class FileDailyTransactionRepository implements DailyTransactionRepository {

    private static final Logger LOG =
            LoggerFactory.getLogger(FileDailyTransactionRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for the DALYTRAN input dataset.
     */
    public static final String DATASET_KEY_INPUT = "dailytran";

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for the DALYREJS output dataset.
     */
    public static final String DATASET_KEY_REJECT = "dalyrejs";

    /**
     * Total byte length of a {@code REJECT-RECORD}: 350 (DALYTRAN body)
     * + 4 (WS-VALIDATION-FAIL-REASON PIC 9(04)) + 76
     * (WS-VALIDATION-FAIL-REASON-DESC PIC X(76)) = 430 bytes. Mirrors
     * the JCL {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} clause in
     * {@code app/jcl/POSTTRAN.jcl}.
     */
    public static final int REJECT_RECORD_LENGTH = 430;

    /**
     * Byte length of the {@code WS-VALIDATION-FAIL-REASON} field
     * ({@code PIC 9(04)} = 4 ASCII digits).
     */
    public static final int REASON_LENGTH = 4;

    /**
     * Byte length of the {@code WS-VALIDATION-FAIL-REASON-DESC} field
     * ({@code PIC X(76)} = 76 ASCII chars).
     */
    public static final int DESCRIPTION_LENGTH = 76;

    /**
     * Inclusive upper bound for {@code WS-VALIDATION-FAIL-REASON}:
     * {@code PIC 9(04)} permits values in {@code [0, 9999]}. Values
     * outside this range cannot be encoded in 4 digits without losing
     * information and so are rejected with
     * {@link IllegalArgumentException}.
     */
    public static final int REASON_MAX = 9_999;

    private final Path inputFile;
    private final Path rejectFile;
    private final Charset charset;
    private final FixedWidthReader reader;

    /**
     * Constructs an adapter with separate input and reject paths and the
     * IBM-1047 default codepage.
     *
     * @param inputFile  the DALYTRAN input file path; must not be
     *                   {@code null}
     * @param rejectFile the DALYREJS output file path; must not be
     *                   {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileDailyTransactionRepository(Path inputFile, Path rejectFile) {
        this(inputFile, rejectFile,
                Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     *
     * @param inputFile  the DALYTRAN input file path; must not be
     *                   {@code null}
     * @param rejectFile the DALYREJS output file path; must not be
     *                   {@code null}
     * @param charset    the charset for transcoding; must not be
     *                   {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileDailyTransactionRepository(Path inputFile, Path rejectFile, Charset charset) {
        this.inputFile = Objects.requireNonNull(inputFile, "inputFile");
        this.rejectFile = Objects.requireNonNull(rejectFile, "rejectFile");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(inputFile, DalyTranRecord.RECORD_LENGTH, charset);
        LOG.debug("FileDailyTransactionRepository configured: inputFile={}, rejectFile={}, "
                        + "charset={}, inputRecordLength={}, rejectRecordLength={}",
                inputFile, rejectFile, charset,
                DalyTranRecord.RECORD_LENGTH, REJECT_RECORD_LENGTH);
    }

    /**
     * Configured DALYTRAN input file path.
     *
     * @return the input file path; never {@code null}
     */
    public Path inputFile() {
        return inputFile;
    }

    /**
     * Configured DALYREJS reject file path.
     *
     * @return the reject file path; never {@code null}
     */
    public Path rejectFile() {
        return rejectFile;
    }

    /**
     * Configured charset.
     *
     * @return the charset; never {@code null}
     */
    public Charset charset() {
        return charset;
    }

    @Override
    public Stream<DalyTranRecord> streamSequential() {
        try {
            return reader.streamSequential().map(DalyTranRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + inputFile, e);
        }
    }

    @Override
    public void appendReject(DalyTranRecord record,
                             int validationFailReason,
                             String validationFailDescription) {
        Objects.requireNonNull(record, "record");
        validateReason(validationFailReason);

        // Assemble the 430-byte REJECT-RECORD image in a stack-allocated
        // (heap-via-new) buffer. The COBOL layout is:
        //   bytes   0..349  -- REJECT-TRAN-DATA               (350 bytes)
        //   bytes 350..353  -- WS-VALIDATION-FAIL-REASON      (PIC 9(04) → 4 ASCII digits)
        //   bytes 354..429  -- WS-VALIDATION-FAIL-REASON-DESC (PIC X(76) → 76 ASCII chars)
        byte[] rejectBuffer = new byte[REJECT_RECORD_LENGTH];

        // Region 1: 350-byte REJECT-TRAN-DATA. The COBOL group move
        //   MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA
        // is byte-for-byte equivalent to copying the encoded image.
        byte[] tranImage = record.encode();
        if (tranImage.length != DalyTranRecord.RECORD_LENGTH) {
            // Defensive: DalyTranRecord.encode() must return exactly 350
            // bytes per the contract. Failing this invariant indicates
            // a defect in the domain layer, not the adapter.
            throw new IllegalStateException(
                    "DalyTranRecord.encode() returned "
                            + tranImage.length + " bytes, expected "
                            + DalyTranRecord.RECORD_LENGTH);
        }
        System.arraycopy(tranImage, 0, rejectBuffer, 0, DalyTranRecord.RECORD_LENGTH);

        // Region 2: 4-byte WS-VALIDATION-FAIL-REASON encoded as
        // PIC 9(04) — zero-left-padded ASCII digits.
        byte[] reasonBytes = String
                .format(Locale.ROOT, "%04d", validationFailReason)
                .getBytes(StandardCharsets.US_ASCII);
        // %04d on a value in [0, 9999] always yields exactly 4 ASCII
        // digits; we already validated the range above.
        assert reasonBytes.length == REASON_LENGTH
                : "reasonBytes.length=" + reasonBytes.length
                + " for reason=" + validationFailReason;
        System.arraycopy(reasonBytes, 0,
                rejectBuffer, DalyTranRecord.RECORD_LENGTH, REASON_LENGTH);

        // Region 3: 76-byte WS-VALIDATION-FAIL-REASON-DESC encoded as
        // PIC X(76) — right-space-padded ASCII text. null is treated as
        // the empty string per the port contract.
        String description = validationFailDescription == null
                ? ""
                : validationFailDescription;
        byte[] descBytes = description.getBytes(StandardCharsets.US_ASCII);
        int descCopyLen = Math.min(descBytes.length, DESCRIPTION_LENGTH);
        int descOffset = DalyTranRecord.RECORD_LENGTH + REASON_LENGTH;
        System.arraycopy(descBytes, 0, rejectBuffer, descOffset, descCopyLen);
        // Right-space-pad the remaining bytes (ASCII 0x20).
        for (int i = descOffset + descCopyLen;
             i < descOffset + DESCRIPTION_LENGTH;
             i++) {
            rejectBuffer[i] = (byte) ' ';
        }
        if (descBytes.length > DESCRIPTION_LENGTH) {
            LOG.debug("appendReject: validationFailDescription truncated "
                            + "from {} to {} chars (PIC X({}) limit)",
                    descBytes.length, DESCRIPTION_LENGTH, DESCRIPTION_LENGTH);
        }

        // Atomic append. Files.write with WRITE+CREATE+APPEND atomically
        // appends the 430-byte image to the reject file. The COBOL
        // single WRITE FD-REJS-RECORD FROM REJECT-RECORD verb is
        // equivalent.
        try {
            // Ensure the parent directory exists so a misconfigured
            // application.properties path does not abort the entire batch.
            Path parent = rejectFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(rejectFile, rejectBuffer,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            LOG.debug("appendReject: appended 430-byte reject "
                            + "(tranId={}, reason={}) to {}",
                    record.dalytranId(), validationFailReason, rejectFile);
        } catch (IOException e) {
            // The COBOL counterpart issues PERFORM 9999-ABEND-PROGRAM
            // (catastrophic). We surface as UncheckedIOException so the
            // outer batch driver maps to JCL return code 16.
            throw new UncheckedIOException(
                    "Failed to append reject record to " + rejectFile
                            + " (tranId=" + record.dalytranId()
                            + ", reason=" + validationFailReason + ")", e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileDailyTransactionRepository closed (no-op): inputFile={}, "
                + "rejectFile={}", inputFile, rejectFile);
    }

    /**
     * Validates the {@code validationFailReason} argument against the
     * {@code PIC 9(04)} domain {@code [0, 9999]}.
     */
    private static void validateReason(int validationFailReason) {
        if (validationFailReason < 0 || validationFailReason > REASON_MAX) {
            throw new IllegalArgumentException(
                    "validationFailReason must be in [0, " + REASON_MAX
                            + "] per PIC 9(04); got " + validationFailReason);
        }
    }
}
