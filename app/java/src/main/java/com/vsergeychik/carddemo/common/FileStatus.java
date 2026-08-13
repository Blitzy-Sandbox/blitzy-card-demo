package com.vsergeychik.carddemo.common;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One status vocabulary for the batch {@code FILE STATUS} and the online CICS {@code RESP}.
 *
 * <p>A representation that stored the status as an {@code int} could not reproduce that, and would diverge
 * from the COBOL on exactly the error paths where a correct diagnostic matters most.
 */
public final class FileStatus {
    // Each constant below is exactly two characters wide because the COBOL field is exactly two bytes wide.

    /**
     * Successful completion, COBOL {@code '00'}.
     */
    public static final String OK = "00";

    /**
     * Record-length conflict, COBOL {@code '04'}.
     */
    public static final String RECORD_LENGTH_CONFLICT = "04";

    /**
     * End of file, COBOL {@code '10'}.
     */
    public static final String END_OF_FILE = "10";

    /**
     * Duplicate key, COBOL {@code '22'}.
     */
    public static final String DUPLICATE = "22";

    /**
     * Record not found on a keyed read, COBOL {@code '23'}.
     */
    public static final String NOT_FOUND = "23";

    /**
     * Open attempted in a mode the file's attributes will not support, COBOL {@code '37'}.
     */
    public static final String OPEN_MODE_CONFLICT = "37";

    /**
     * Width of a COBOL {@code FILE STATUS} field, in characters: exactly {@code 2}.
     */
    public static final int STATUS_LENGTH = 2;

    /**
     * Width of the rendered {@code IO-STATUS-04} image, in characters: exactly {@code 4}.
     */
    public static final int STATUS_IMAGE_LENGTH = 4;

    private static final int BYTE_MASK = 0xFF;

    /**
     * CICS {@code DFHRESP(NORMAL)} = {@code 0}: the command completed successfully.
     */
    public static final int NORMAL = 0;

    /**
     * CICS {@code DFHRESP(NOTFND)} = {@code 13}: the requested record does not exist.
     */
    public static final int NOTFND = 13;

    /**
     * CICS {@code DFHRESP(DUPREC)} = {@code 14}: a record with that key already exists on {@code WRITE}.
     */
    public static final int DUPREC = 14;

    /**
     * CICS {@code DFHRESP(DUPKEY)} = {@code 15}: another record with the same alternate key exists.
     */
    public static final int DUPKEY = 15;

    /**
     * CICS {@code DFHRESP(INVREQ)} = {@code 16}: the request is invalid for the file as defined.
     */
    public static final int INVREQ = 16;

    /**
     * CICS {@code DFHRESP(NOTOPEN)} = {@code 19}: the file is not open.
     */
    public static final int NOTOPEN = 19;

    /**
     * CICS {@code DFHRESP(ENDFILE)} = {@code 20}: the browse reached the end of the file.
     */
    public static final int ENDFILE = 20;

    /**
     * CICS {@code DFHRESP(LENGERR)} = {@code 22}: a length error occurred.
     */
    public static final int LENGERR = 22;

    /**
     * The CICS {@code RESP2} value meaning "no reason code": {@code 0}.
     */
    public static final int NO_REASON_CODE = 0;

    /**
     * The value recorded in {@code WS-RESP-CD} when a command reported no CICS {@code RESP} at all:
     * {@code -1}.
     */
    public static final int RESP_NOT_REPORTED = -1;

    private static final char RESP_NOT_REPORTED_FILL = '*';

    /**
     * The {@code 88 APPL-AOK VALUE 0} condition: the operation succeeded.
     */
    public static final int APPL_AOK = 0;

    /**
     * The {@code 88 APPL-EOF VALUE 16} condition: the operation hit end of file.
     */
    public static final int APPL_EOF = 16;

    /**
     * The literal emitted by the COBOL {@code DISPLAY}, byte for byte: {@code "FILE STATUS IS: NNNN"} -
     * twenty characters, ending in the four characters {@code NNNN}.
     */
    public static final String DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    private FileStatus() {
    }

    /**
     * Renders a two-character file status into the four-character {@code IO-STATUS-04} image, exactly as
     * {@code 9910-DISPLAY-IO-STATUS} composes it.
     *
     * @param status the two-character file status; must be non-{@code null} and exactly
     *     {@link #STATUS_LENGTH} characters long
     * @return the four-character image, never {@code null}, always {@link #STATUS_IMAGE_LENGTH} characters
     *     long
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static String toStatusImage(final String status) {
        final String checked = requireTwoCharacterStatus(status);
        return toStatusImage(checked.charAt(0), checked.charAt(1));
    }

    /**
     * Renders the two status bytes into the four-character {@code IO-STATUS-04} image.
     *
     * @param stat1 the first status byte, {@code IO-STAT1}; only its low-order eight bits are significant,
     *     matching a single-byte COBOL {@code PIC X}
     * @param stat2 the second status byte, {@code IO-STAT2}; only its low-order eight bits are significant
     * @return the four-character image, never {@code null}, always {@link #STATUS_IMAGE_LENGTH} characters
     *     long
     */
    public static String toStatusImage(final char stat1, final char stat2) {
        // IO-STAT1 and IO-STAT2 are PIC X, that is one byte each, so a caller handing over a char above
        // 0xFF has supplied something COBOL storage cannot hold. This is the single normalisation the whole
        // method reads from, which is why it happens before the first test rather than beside the second.
        final char firstByte = (char) (stat1 & BYTE_MASK);
        final char secondByte = (char) (stat2 & BYTE_MASK);

        final boolean statusIsNumeric = isSingleByteDigit(firstByte) && isSingleByteDigit(secondByte);
        final StringBuilder image = new StringBuilder(STATUS_IMAGE_LENGTH);

        if (!statusIsNumeric || firstByte == '9') {
            image.append(firstByte);

            final int rightByte = secondByte;

            // MOVE TWO-BYTES-BINARY TO IO-STATUS-0403 - a PIC 999 receiver, so exactly three zero-padded
            // decimal digits. Written out digit by digit so the padding rule is explicit in the source
            // rather than delegated to a format string.
            final char hundreds = (char) ('0' + (rightByte / 100));
            final char tens = (char) ('0' + ((rightByte / 10) % 10));
            final char units = (char) ('0' + (rightByte % 10));
            image.append(hundreds).append(tens).append(units);
        } else {
            image.append('0').append('0');

            image.append(firstByte).append(secondByte);
        }

        return image.toString();
    }

    /**
     * Builds the complete line that the COBOL {@code DISPLAY} writes: {@link #DISPLAY_PREFIX} immediately
     * followed by the four-character image.
     *
     * @param status the two-character file status; must be non-{@code null} and exactly
     *     {@link #STATUS_LENGTH} characters long
     * @return the full display line, never {@code null}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static String toDisplayLine(final String status) {
        return DISPLAY_PREFIX + toStatusImage(status);
    }

    /**
     * Builds the complete display line from the two status bytes.
     *
     * @param stat1 the first status byte, {@code IO-STAT1}
     * @param stat2 the second status byte, {@code IO-STAT2}
     * @return the full display line, never {@code null}
     */
    public static String toDisplayLine(final char stat1, final char stat2) {
        return DISPLAY_PREFIX + toStatusImage(stat1, stat2);
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '00'}: did the operation succeed?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #OK}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isOk(final String status) {
        return OK.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Whether a response code is one a command actually reported, as opposed to {@link #RESP_NOT_REPORTED}.
     *
     * @param resp the value held in {@code WS-RESP-CD}
     * @return {@code true} unless {@code resp} is {@link #RESP_NOT_REPORTED}
     */
    public static boolean respReported(final int resp) {
        return resp != RESP_NOT_REPORTED;
    }

    /**
     * The image a {@link #RESP_NOT_REPORTED} response code is rendered as: {@code digits} asterisks.
     *
     * <p>{@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} concatenates its operands at their declared
     * widths, so a substitute of any other length would shift every character after it and change a line
     * the parity contract covers.
     *
     * @param digits the receiver's declared digit count; at least 1
     * @return exactly {@code digits} asterisks
     * @throws IllegalArgumentException if {@code digits} is below 1
     */
    public static String respNotReportedImage(final int digits) {
        if (digits < 1) {
            throw new IllegalArgumentException("A response-code field has at least one digit position, "
                    + "so an image of " + digits + " character(s) cannot be one. WS-RESP-CD is "
                    + "PIC S9(09) COMP in every online program that declares it.");
        }
        return String.valueOf(RESP_NOT_REPORTED_FILL).repeat(digits);
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '04'}: did the read succeed while transferring a record whose length
     * does not conform to the file's fixed attributes?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #RECORD_LENGTH_CONFLICT}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isRecordLengthConflict(final String status) {
        return RECORD_LENGTH_CONFLICT.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '10'}: has the browse reached the end of the file?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #END_OF_FILE}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isEndOfFile(final String status) {
        return END_OF_FILE.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '23'}: was the keyed record absent?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #NOT_FOUND}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isNotFound(final String status) {
        return NOT_FOUND.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '22'}: was a duplicate key rejected?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #DUPLICATE}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isDuplicate(final String status) {
        return DUPLICATE.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests the compound COBOL condition {@code IF <FILE>-STATUS = '00' OR '23'}: the record was either
     * found or legitimately absent, and either way processing continues.
     *
     * @param status the two-character file status
     * @return {@code true} if the status is {@link #OK} or {@link #NOT_FOUND}, {@code false} otherwise -
     *     including for {@link #END_OF_FILE} and {@link #DUPLICATE}
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isOkOrNotFound(final String status) {
        final String checked = requireTwoCharacterStatus(status);
        return OK.equals(checked) || NOT_FOUND.equals(checked);
    }

    /**
     * Classifies a two-character batch file status into the shared {@link Outcome} vocabulary.
     *
     * @param status the two-character file status
     * @return {@link Outcome#OK}, {@link Outcome#END_OF_FILE}, {@link Outcome#NOT_FOUND} or
     *     {@link Outcome#DUPLICATE} for the four recognised statuses
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static Outcome outcomeOfStatus(final String status) {
        final String checked = requireTwoCharacterStatus(status);

        if (OK.equals(checked)) {
            return Outcome.OK;
        }
        if (END_OF_FILE.equals(checked)) {
            return Outcome.END_OF_FILE;
        }
        if (NOT_FOUND.equals(checked)) {
            return Outcome.NOT_FOUND;
        }
        if (DUPLICATE.equals(checked)) {
            return Outcome.DUPLICATE;
        }
        return Outcome.OTHER;
    }

    /**
     * Classifies a CICS {@code RESP} value into the same {@link Outcome} vocabulary the batch statuses use,
     * so that an online repository and a batch repository report outcomes indistinguishably.
     *
     * @param cicsResp the value CICS placed in the {@code RESP} field
     * @return the corresponding outcome, never {@code null}
     */
    public static Outcome outcomeOfCicsResp(final int cicsResp) {
        if (cicsResp == NORMAL) {
            return Outcome.OK;
        }
        if (cicsResp == ENDFILE) {
            return Outcome.END_OF_FILE;
        }
        if (cicsResp == NOTFND) {
            return Outcome.NOT_FOUND;
        }
        if (cicsResp == DUPREC || cicsResp == DUPKEY) {
            return Outcome.DUPLICATE;
        }
        return Outcome.OTHER;
    }

    /**
     * Translates a CICS {@code RESP} value into the equivalent two-character batch file status.
     *
     * <p>{@link #INVREQ}, {@link #NOTOPEN} and {@link #LENGERR} are deliberately among those: they are real
     * CICS conditions with no two-character batch counterpart, and returning a fabricated status for them
     * would invent behaviour the COBOL never had.
     *
     * @param cicsResp the value CICS placed in the {@code RESP} field
     * @return the equivalent two-character status, or an empty {@code Optional} when the response has no
     *     batch equivalent
     */
    public static Optional<String> batchStatusOfCicsResp(final int cicsResp) {
        if (cicsResp == NORMAL) {
            return Optional.of(OK);
        }
        if (cicsResp == ENDFILE) {
            return Optional.of(END_OF_FILE);
        }
        if (cicsResp == NOTFND) {
            return Optional.of(NOT_FOUND);
        }
        if (cicsResp == DUPREC || cicsResp == DUPKEY) {
            return Optional.of(DUPLICATE);
        }
        return Optional.empty();
    }

    /**
     * Translates a two-character batch file status back into the equivalent CICS {@code RESP} value, for
     * those statuses where the correspondence is one-to-one.
     *
     * <p>A caller that needs a specific CICS condition must name {@link #DUPREC} or {@link #DUPKEY}
     * directly.
     *
     * @param status the two-character file status
     * @return the equivalent CICS {@code RESP} value where the mapping is one-to-one, otherwise an empty
     *     {@code OptionalInt} - for {@link #DUPLICATE} because the reverse is ambiguous
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static OptionalInt cicsRespOfBatchStatus(final String status) {
        final String checked = requireTwoCharacterStatus(status);

        if (OK.equals(checked)) {
            return OptionalInt.of(NORMAL);
        }
        if (END_OF_FILE.equals(checked)) {
            return OptionalInt.of(ENDFILE);
        }
        if (NOT_FOUND.equals(checked)) {
            return OptionalInt.of(NOTFND);
        }
        return OptionalInt.empty();
    }

    /**
     * Validates that a value really is a COBOL {@code FILE STATUS} - non-{@code null} and exactly
     * {@link #STATUS_LENGTH} characters - and returns it unchanged.
     *
     * @param status the candidate status
     * @return {@code status}, unchanged
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    private static String requireTwoCharacterStatus(final String status) {
        if (status == null) {
            throw new NullPointerException(
                    "COBOL FILE STATUS must not be null; it is a two-byte field declared as "
                            + "'05 IO-STAT1 PIC X. 05 IO-STAT2 PIC X.'");
        }
        if (status.length() != STATUS_LENGTH) {
            throw new IllegalArgumentException(
                    "COBOL FILE STATUS must be exactly " + STATUS_LENGTH + " characters, but was "
                            + status.length() + ": \"" + status + "\"");
        }
        return status;
    }

    private static boolean isSingleByteDigit(final char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * The discriminated outcome of a dataset operation, shared by the batch and online sides.
     */
    public enum Outcome {
        /**
         * Success: the batch status {@code '00'}, or the CICS response {@code NORMAL}.
         */
        OK(FileStatus.OK),

        /**
         * End of file: the batch status {@code '10'}, or the CICS response {@code ENDFILE}.
         */
        END_OF_FILE(FileStatus.END_OF_FILE),

        /**
         * The keyed record was absent: the batch status {@code '23'}, or the CICS response {@code NOTFND}.
         */
        NOT_FOUND(FileStatus.NOT_FOUND),

        /**
         * A duplicate key was rejected: the batch status {@code '22'}, or the CICS responses {@code DUPREC}
         * and {@code DUPKEY}.
         */
        DUPLICATE(FileStatus.DUPLICATE),

        /**
         * Any other result, and therefore fatal in every COBOL guard chain in the estate: the
         * {@code WHEN OTHER} arm.
         */
        OTHER;

        private final String batchStatus;

        Outcome() {
            this.batchStatus = null;
        }

        Outcome(final String batchStatus) {
            this.batchStatus = batchStatus;
        }

        /**
         * The two-character batch {@code FILE STATUS} this outcome corresponds to.
         *
         * @return the corresponding status, or an empty {@code Optional} for {@link #OTHER}
         */
        public Optional<String> batchStatus() {
            return Optional.ofNullable(this.batchStatus);
        }
    }
}
