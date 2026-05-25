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
import com.blitzy.carddemo.domain.port.CardRepository;
import com.blitzy.carddemo.domain.record.CardRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed implementation of {@link CardRepository} reading and writing
 * the {@code CARDDATA.VSAM.KSDS} dataset as a fixed-width binary file via
 * {@link java.nio.file}.
 *
 * <h2>Source artefact</h2>
 * Implements the access path for the {@code CARD-RECORD} 01-level group
 * defined in {@code app/cpy/CVACT02Y.cpy} (150-byte fixed-width record,
 * 16-byte {@code CARD-NUM} primary key at offset 0). The COBOL programs
 * that consume this dataset (CBACT02C, COCRDLIC, COCRDSLC, COCRDUPC)
 * issue {@code EXEC CICS READ DATASET(CARDFILE)} for random key lookup
 * and {@code STARTBR}/{@code READNEXT} for sequential browse.
 *
 * <h2>Record layout</h2>
 * Each record is exactly {@value CardRecord#RECORD_LENGTH} bytes; the
 * 16-byte {@code CARD-NUM} field is the KSDS primary key at offset 0.
 * See {@link CardRecord} for the complete byte-by-byte layout, including
 * the 50-byte embossed name, 10-byte expiration date, and PAN/CVV that
 * are masked in {@link CardRecord#toString()} per AAP &sect;0.7.2.
 *
 * <h2>Adapter responsibilities</h2>
 * <ul>
 *   <li>Translate {@link CardRecord} domain objects to and from the
 *       150-byte fixed-width byte image.</li>
 *   <li>Look up by 16-character card number via
 *       {@link FixedWidthReader#findByKey}.</li>
 *   <li>Stream all records in file (KSDS key) order via
 *       {@link FixedWidthReader#streamSequential()}.</li>
 *   <li>Browse from a starting key (inclusive) via
 *       {@link FixedWidthReader#streamFromKey}.</li>
 *   <li>Persist card updates via {@link FixedWidthWriter#upsert}.</li>
 *   <li>Delete records via {@link FixedWidthWriter#deleteByKey}, mirroring
 *       the COBOL {@code EXEC CICS DELETE} verb.</li>
 * </ul>
 *
 * <h2>PAN masking in logs</h2>
 * Per AAP &sect;0.7.2 no PAN is logged in full. {@link CardRecord#toString()}
 * already masks the PAN to last-4; this adapter relies on that property
 * and logs only the {@code cardNumber} parameter via the masked
 * {@link #maskPan(String)} helper to ensure no full PAN reaches any log
 * surface.
 *
 * @see CardRepository
 * @see CardRecord
 * @see FixedWidthReader
 * @see FixedWidthWriter
 * @since 1.0.0
 */
@CobolProgram(
        value = "CVACT02Y",
        sourcePath = "app/cpy/CVACT02Y.cpy",
        translationDate = "2025-10-24",
        notes = "File-backed adapter for the CARDDATA VSAM KSDS (150-byte CARD-RECORD; "
                + "16-byte CARD-NUM primary key at offset 0). PAN masking applied to all "
                + "log lines per AAP §0.7.2; CardRecord.toString() masks PAN to last-4."
)
public final class FileCardRepository implements CardRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FileCardRepository.class);

    /**
     * The configuration key used by {@link EbcdicTranscoder} to look up
     * the per-file codepage override for this dataset.
     */
    public static final String DATASET_KEY = "carddata";

    private final Path file;
    private final Charset charset;
    private final FixedWidthReader reader;
    private final FixedWidthWriter writer;

    /**
     * Constructs an adapter with the IBM-1047 default codepage.
     *
     * @param file the file path; must not be {@code null}
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public FileCardRepository(Path file) {
        this(file, Charset.forName(EbcdicTranscoder.DEFAULT_CHARSET_NAME));
    }

    /**
     * Constructs an adapter with an explicit charset.
     *
     * @param file    the file path; must not be {@code null}
     * @param charset the charset; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public FileCardRepository(Path file, Charset charset) {
        this.file = Objects.requireNonNull(file, "file");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.reader = new FixedWidthReader(file, CardRecord.RECORD_LENGTH, charset);
        this.writer = new FixedWidthWriter(file, CardRecord.RECORD_LENGTH, charset);
        LOG.debug("FileCardRepository configured: file={}, charset={}, recordLength={}",
                file, charset, CardRecord.RECORD_LENGTH);
    }

    /**
     * Configured file path.
     *
     * @return the file path; never {@code null}
     */
    public Path file() {
        return file;
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
    public Optional<CardRecord> findByCardNumber(String cardNumber) {
        validateCardNumber(cardNumber);
        byte[] key = encodeKey(cardNumber);
        try {
            return reader.findByKey(key, CardRecord.CARD_NUM_OFFSET)
                    .map(CardRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read card record cardNumber=" + maskPan(cardNumber)
                            + " from " + file, e);
        }
    }

    @Override
    public Stream<CardRecord> streamSequential() {
        try {
            return reader.streamSequential().map(CardRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open sequential stream over " + file, e);
        }
    }

    @Override
    public Stream<CardRecord> streamFrom(String startCardNumber) {
        // Null or blank means "start at the lowest key" (COBOL STARTBR with
        // space-filled RIDFLD positions at the first physical record).
        if (startCardNumber == null || startCardNumber.isBlank()) {
            return streamSequential();
        }
        byte[] key = encodeKey(startCardNumber);
        try {
            return reader.streamFromKey(key, CardRecord.CARD_NUM_OFFSET)
                    .map(CardRecord::parse);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open ranged stream from startCardNumber="
                            + maskPan(startCardNumber) + " over " + file, e);
        }
    }

    @Override
    public void save(CardRecord card) {
        Objects.requireNonNull(card, "card");
        byte[] buffer = card.encode();
        byte[] key = encodeKey(card.cardNum());
        try {
            writer.upsert(key, CardRecord.CARD_NUM_OFFSET, buffer);
            LOG.debug("FileCardRepository.save: cardNum={} written to {}",
                    maskPan(card.cardNum()), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save card record cardNum=" + maskPan(card.cardNum())
                            + " to " + file, e);
        }
    }

    @Override
    public void delete(String cardNumber) {
        validateCardNumber(cardNumber);
        byte[] key = encodeKey(cardNumber);
        try {
            boolean removed = writer.deleteByKey(key, CardRecord.CARD_NUM_OFFSET);
            if (!removed) {
                throw new java.util.NoSuchElementException(
                        "Card record not found for cardNumber=" + maskPan(cardNumber));
            }
            LOG.debug("FileCardRepository.delete: cardNum={} removed from {}",
                    maskPan(cardNumber), file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to delete card record cardNumber=" + maskPan(cardNumber)
                            + " from " + file, e);
        }
    }

    @Override
    public void close() {
        LOG.debug("FileCardRepository closed (no-op): file={}", file);
    }

    /**
     * Encodes a 16-character {@code CARD-NUM} as a fixed 16-byte
     * ASCII key, padding with trailing spaces if shorter and rejecting
     * over-length values. This mirrors the COBOL convention of right-
     * space-padding {@code PIC X(16)} fields.
     *
     * @param cardNumber the card number; must be non-{@code null} and
     *                   no longer than 16 characters
     * @return a 16-byte ASCII key buffer
     */
    private static byte[] encodeKey(String cardNumber) {
        byte[] key = new byte[CardRecord.CARD_NUM_LENGTH];
        byte[] src = cardNumber.getBytes(StandardCharsets.US_ASCII);
        int copyLen = Math.min(src.length, key.length);
        System.arraycopy(src, 0, key, 0, copyLen);
        // Right-space-pad to 16 bytes per COBOL PIC X(16) convention.
        for (int i = copyLen; i < key.length; i++) {
            key[i] = (byte) ' ';
        }
        return key;
    }

    /**
     * Validates the {@code cardNumber} argument: non-null, non-blank, and
     * within the 16-byte PIC X(16) limit. Throws unchecked exceptions
     * matching the port contract.
     *
     * @param cardNumber the value to validate
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} is blank or
     *                                  exceeds 16 characters
     */
    private static void validateCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "cardNumber");
        if (cardNumber.isBlank()) {
            throw new IllegalArgumentException("cardNumber must not be blank");
        }
        if (cardNumber.length() > CardRecord.CARD_NUM_LENGTH) {
            throw new IllegalArgumentException(
                    "cardNumber must be <= " + CardRecord.CARD_NUM_LENGTH
                            + " chars; got length " + cardNumber.length());
        }
    }

    /**
     * Masks a card number to last-4 for safe logging per AAP &sect;0.7.2.
     * For inputs shorter than 4 chars, returns a fully-masked string.
     *
     * @param cardNumber the raw card number (may be null)
     * @return a masked representation, never the full PAN
     */
    private static String maskPan(String cardNumber) {
        if (cardNumber == null) {
            return "null";
        }
        int len = cardNumber.length();
        if (len <= 4) {
            return "*".repeat(len);
        }
        return "*".repeat(len - 4) + cardNumber.substring(len - 4);
    }
}
