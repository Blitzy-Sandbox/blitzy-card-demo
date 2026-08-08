package com.vsergeychik.carddemo.common;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * One status vocabulary for the batch {@code FILE STATUS} and the online CICS {@code RESP}.
 *
 * <h2>Why this class exists</h2>
 * <p>The CardDemo COBOL estate reports the outcome of an I/O operation two different ways. The
 * eleven non-CICS programs declare a two-byte {@code FILE STATUS} group per file and compare it
 * against character literals; the seventeen CICS online programs test a binary {@code RESP} value
 * against {@code DFHRESP(...)} condition names. A faithful translation has to collapse both onto a
 * single vocabulary so that <em>the caller's branch structure is unchanged</em> - a repository
 * method must be able to report the same discriminated outcome whether the legacy code behind it
 * was batch or online. That collapse is mandated by the migration plan's COBOL-to-Java semantic
 * mapping and its VSAM-access-parity analysis, and this class is where it happens.
 *
 * <h2>The renderer is a verbatim translation, not a re-design</h2>
 * <p>{@link #toStatusImage(String)} and {@link #toDisplayLine(String)} reproduce a single COBOL
 * paragraph that is duplicated - with an identical body - across <strong>eight</strong> batch
 * programs. It is spelled {@code 9910-DISPLAY-IO-STATUS} in six of them
 * ({@code app/cbl/CBACT01C.cbl:176}, {@code CBACT02C.cbl:161}, {@code CBACT03C.cbl:161},
 * {@code CBACT04C.cbl:635}, {@code CBTRN02C.cbl:714}, {@code CBTRN03C.cbl:633}) and
 * {@code Z-DISPLAY-IO-STATUS} in the remaining two ({@code CBCUS01C.cbl:161},
 * {@code CBTRN01C.cbl:476}); only {@code CBTRN03C}'s copy differs, and only in COBOL indentation
 * that carries no meaning. Each copy contains two {@code DISPLAY} statements, so the estate holds
 * sixteen textually identical emission sites. Centralising them here is the entire reason this
 * class exists: every job class must call this renderer rather than re-implement it.
 *
 * <p>The COBOL, from {@code app/cbl/CBTRN02C.cbl}, reads:
 * <pre>
 * 9910-DISPLAY-IO-STATUS.
 *     IF  IO-STATUS NOT NUMERIC
 *     OR  IO-STAT1 = '9'
 *         MOVE IO-STAT1 TO IO-STATUS-04(1:1)
 *         MOVE 0        TO TWO-BYTES-BINARY
 *         MOVE IO-STAT2 TO TWO-BYTES-RIGHT
 *         MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
 *         DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
 *     ELSE
 *         MOVE '0000' TO IO-STATUS-04
 *         MOVE IO-STATUS TO IO-STATUS-04(3:2)
 *         DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
 *     END-IF
 *     EXIT.
 * </pre>
 *
 * <p>over these declarations ({@code app/cbl/CBTRN02C.cbl:131-140}):
 * <pre>
 * 01  IO-STATUS.
 *     05  IO-STAT1            PIC X.
 *     05  IO-STAT2            PIC X.
 * 01  TWO-BYTES-BINARY        PIC 9(4) BINARY.
 * 01  TWO-BYTES-ALPHA         REDEFINES TWO-BYTES-BINARY.
 *     05  TWO-BYTES-LEFT      PIC X.
 *     05  TWO-BYTES-RIGHT     PIC X.
 * 01  IO-STATUS-04.
 *     05  IO-STATUS-0401      PIC 9   VALUE 0.
 *     05  IO-STATUS-0403      PIC 999 VALUE 0.
 * </pre>
 *
 * <h2>Why the status is a String and never an int</h2>
 * <p>A file status <em>looks</em> like a number and is not one. The first branch above moves
 * {@code IO-STAT2} - a single {@code PIC X} <em>character</em> - into the low-order byte of a
 * {@code PIC 9(4) BINARY} through a {@code REDEFINES}, reinterpreting that byte as an unsigned
 * integer in 0..255, and then prints it as three decimal digits. A representation that stored the
 * status as an {@code int} could not reproduce that, and would diverge from the COBOL on exactly
 * the error paths where a correct diagnostic matters most. The two-character form is therefore the
 * primary representation throughout this class.
 *
 * <h2>{@code NNNN} is part of the COBOL literal - it is not a defect</h2>
 * <p>{@link #DISPLAY_PREFIX} genuinely ends in the four characters {@code NNNN}. COBOL emits the
 * literal and then the field, so a real output line reads {@code FILE STATUS IS: NNNN0000}. The
 * placeholder text was never substituted in the original and is reproduced here unchanged: this is
 * a like-for-like migration, so an observable oddity in the legacy output is preserved rather than
 * tidied away.
 *
 * <h2>{@code '22'} is defined but never compared in batch code</h2>
 * <p>{@link #DUPLICATE} is the standard COBOL duplicate-key status and the migration plan requires
 * it in this constant set. A search of all twenty-eight programs in {@code app/cbl} finds
 * <strong>no</strong> literal {@code '22'} comparison anywhere: the batch programs never test it,
 * and the online programs express the same condition through the CICS {@link #DUPREC} and
 * {@link #DUPKEY} responses instead. The constant is kept and the observation recorded here rather
 * than being "resolved" by deleting it or by inventing a consumer for it.
 *
 * <h2>This class formats and classifies; it never terminates</h2>
 * <p>The universal COBOL idiom on an unexpected status is
 * {@code DISPLAY 'ERROR ...' / MOVE <FILE>-STATUS TO IO-STATUS /
 * PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM}
 * (see {@code app/cbl/CBTRN02C.cbl:247-250}). The two halves are deliberately kept apart in Java:
 * this class renders and classifies, and the sibling abend type terminates. Nothing in this file
 * imports another type from this application, which keeps the dependency graph acyclic - this
 * class is a graph root - and lets each half be unit-tested on its own.
 *
 * <h2>Provenance of the CICS response values</h2>
 * <p>The {@code int} constants {@link #NORMAL}, {@link #NOTFND}, {@link #DUPREC},
 * {@link #DUPKEY}, {@link #INVREQ}, {@link #NOTOPEN}, {@link #ENDFILE} and {@link #LENGERR} carry
 * the standard numeric values that the CICS translator substitutes for {@code DFHRESP(...)}. They
 * are sourced from <strong>IBM CICS Transaction Server documentation</strong> ("RESP and RESP2
 * options" and the {@code EIBRESP} condition table) and <strong>not</strong> from any copybook in
 * this repository, because no {@code DFHRESP} copybook is present here - the same situation as the
 * absent {@code DFHAID}, {@code DFHBMSCA} and {@code DFHATTR} copybooks. That gap is a recorded
 * environmental limitation, documented rather than absorbed silently.
 *
 * <h2>Thread safety</h2>
 * <p>Immutable and stateless. Every member is {@code static final} and every value is either a
 * primitive, an interned {@code String} or an enum constant; there is no mutable static state and
 * no array constant. All methods are pure functions of their arguments and are safe to call
 * concurrently from any number of threads.
 *
 * @see #toStatusImage(String)
 * @see #toDisplayLine(String)
 * @see Outcome
 */
public final class FileStatus {

    // ---------------------------------------------------------------------------------------
    // Batch FILE STATUS values - the two-character form
    //
    // Every non-CICS program declares one two-byte status group per file, for example
    // app/cbl/CBTRN02C.cbl:103-105
    //     01  DALYTRAN-STATUS.
    //         05  DALYTRAN-STAT1      PIC X.
    //         05  DALYTRAN-STAT2      PIC X.
    // and compares it against character literals. Each constant below is exactly two characters
    // wide because the COBOL field is exactly two bytes wide.
    // ---------------------------------------------------------------------------------------

    /**
     * Successful completion, COBOL {@code '00'}.
     *
     * <p>The most frequently tested status in the estate; every {@code OPEN}, {@code READ},
     * {@code WRITE}, {@code REWRITE} and {@code CLOSE} guard begins with it - for example
     * {@code IF DALYTRAN-STATUS = '00'} at {@code app/cbl/CBTRN02C.cbl:239} and again at
     * {@code :347}.
     *
     * <p>Note that this is the {@code String} constant {@code "00"}. The similarly named
     * {@link Outcome#OK} is the enum constant that classifies it; the two are distinct members and
     * are never interchangeable.
     */
    public static final String OK = "00";

    /**
     * End of file, COBOL {@code '10'}.
     *
     * <p>Reached at the end of every sequential browse. {@code app/cbl/CBTRN02C.cbl:351} tests
     * {@code IF DALYTRAN-STATUS = '10'} and responds by moving {@code 16} into
     * {@code APPL-RESULT}, which is the {@link #APPL_EOF} condition - the end of the input is an
     * expected outcome, not an error.
     */
    public static final String END_OF_FILE = "10";

    /**
     * Duplicate key, COBOL {@code '22'}.
     *
     * <p><strong>No batch program in this repository compares against this value.</strong> A
     * search of all twenty-eight programs in {@code app/cbl} finds zero literal {@code '22'}
     * comparisons. The constant is nevertheless required by the migration plan's status set, and
     * it is the status a duplicate-key condition would legitimately produce, so it is defined here
     * and the absence of a consumer is documented rather than corrected. The online programs
     * express the same condition through {@link #DUPREC} and {@link #DUPKEY}.
     */
    public static final String DUPLICATE = "22";

    /**
     * Record not found on a keyed read, COBOL {@code '23'}.
     *
     * <p>Critically, not-found is <em>not</em> always an error. Two programs treat it as a normal
     * outcome and fall back to a default:
     * <ul>
     *   <li>{@code app/cbl/CBACT04C.cbl:422} - {@code IF DISCGRP-STATUS = '00' OR '23'} accepts
     *       both, and {@code :436} then substitutes the {@code 'DEFAULT'} disclosure group when
     *       the account's own group is absent.</li>
     *   <li>{@code app/cbl/CBTRN02C.cbl:481} - {@code IF TCATBALF-STATUS = '00' OR '23'} accepts
     *       both, and the missing category-balance record is subsequently created.</li>
     * </ul>
     * {@link #isOkOrNotFound(String)} exists precisely so that a caller can express that compound
     * test without collapsing not-found into a generic error.
     */
    public static final String NOT_FOUND = "23";

    /**
     * Width of a COBOL {@code FILE STATUS} field, in characters: exactly {@code 2}.
     *
     * <p>Fixed by the declaration {@code 05 IO-STAT1 PIC X. 05 IO-STAT2 PIC X.} - two
     * single-byte items and nothing else. Every status accepted by this class is validated
     * against this width.
     */
    public static final int STATUS_LENGTH = 2;

    /**
     * Width of the rendered {@code IO-STATUS-04} image, in characters: exactly {@code 4}.
     *
     * <p>Fixed by the declaration {@code 05 IO-STATUS-0401 PIC 9. 05 IO-STATUS-0403 PIC 999.} -
     * one digit followed by three digits.
     */
    public static final int STATUS_IMAGE_LENGTH = 4;

    /**
     * Masks a {@code char} down to the one byte a COBOL {@code PIC X} item actually holds.
     *
     * <p>{@code IO-STAT1} and {@code IO-STAT2} are each {@code PIC X}, so each carries exactly one
     * byte with an unsigned value of 0 to 255. A Java {@code char} is sixteen bits wide and can hold
     * more than that, so every status character is narrowed through this mask before it is either
     * classified or rendered. Applying it to one character and not the other is what allowed
     * classification and rendering to disagree, which is precisely the defect this constant removes.
     */
    private static final int BYTE_MASK = 0xFF;

    // ---------------------------------------------------------------------------------------
    // CICS RESP values - the online form
    //
    // The seventeen online programs pass RESP (and sometimes RESP2) on EXEC CICS READ, STARTBR,
    // READNEXT, REWRITE, WRITE and DELETE, then test the result against DFHRESP(...) condition
    // names. The conditions actually tested in this repository, with their occurrence counts, are
    // NORMAL (43 sites), NOTFND (23), ENDFILE (8), DUPREC (7) and DUPKEY (3) - eighty-four tests
    // in total. NOTOPEN, LENGERR and INVREQ are additionally named here because the migration plan
    // requires them in this constant set; they are not tested anywhere in the current COBOL, and
    // that distinction is stated rather than glossed over.
    //
    // Values are from IBM CICS Transaction Server documentation, since no DFHRESP copybook exists
    // in this repository.
    // ---------------------------------------------------------------------------------------

    /**
     * CICS {@code DFHRESP(NORMAL)} = {@code 0}: the command completed successfully.
     *
     * <p>The online equivalent of the batch {@link #OK} status, and the most-tested response in
     * the estate at forty-three sites. Value from IBM CICS documentation.
     */
    public static final int NORMAL = 0;

    /**
     * CICS {@code DFHRESP(NOTFND)} = {@code 13}: the requested record does not exist.
     *
     * <p>The online equivalent of the batch {@link #NOT_FOUND} status; twenty-three test sites.
     * Value from IBM CICS documentation.
     */
    public static final int NOTFND = 13;

    /**
     * CICS {@code DFHRESP(DUPREC)} = {@code 14}: a record with that key already exists on
     * {@code WRITE}.
     *
     * <p>One of the two online spellings of the batch {@link #DUPLICATE} status; seven test sites.
     * Value from IBM CICS documentation.
     */
    public static final int DUPREC = 14;

    /**
     * CICS {@code DFHRESP(DUPKEY)} = {@code 15}: another record with the same alternate key
     * exists.
     *
     * <p>The second online spelling of the batch {@link #DUPLICATE} status; three test sites, all
     * on alternate-index access paths. Value from IBM CICS documentation.
     */
    public static final int DUPKEY = 15;

    /**
     * CICS {@code DFHRESP(INVREQ)} = {@code 16}: the request is invalid for the file as defined.
     *
     * <p>Named because the migration plan requires it in this set. It is <strong>not</strong>
     * tested by any program in {@code app/cbl}, and it has no two-character batch equivalent.
     * Value from IBM CICS documentation.
     */
    public static final int INVREQ = 16;

    /**
     * CICS {@code DFHRESP(NOTOPEN)} = {@code 19}: the file is not open.
     *
     * <p>Named because the migration plan requires it in this set. It is <strong>not</strong>
     * tested by any program in {@code app/cbl}, and it has no two-character batch equivalent.
     * Value from IBM CICS documentation.
     */
    public static final int NOTOPEN = 19;

    /**
     * CICS {@code DFHRESP(ENDFILE)} = {@code 20}: the browse reached the end of the file.
     *
     * <p>The online equivalent of the batch {@link #END_OF_FILE} status; eight test sites, all on
     * {@code READNEXT}. Value from IBM CICS documentation.
     */
    public static final int ENDFILE = 20;

    /**
     * CICS {@code DFHRESP(LENGERR)} = {@code 22}: a length error occurred.
     *
     * <p>Named because the migration plan requires it in this set. It is <strong>not</strong>
     * tested by any program in {@code app/cbl}, and it has no two-character batch equivalent.
     *
     * <p>Its numeric value {@code 22} coincides with the <em>text</em> of the unrelated batch
     * status {@link #DUPLICATE} ({@code "22"}). That collision is a coincidence of two independent
     * numbering schemes and carries no meaning; it is one more reason the batch status is modelled
     * as a {@code String} and the CICS response as an {@code int}, so the two can never be
     * confused by an accidental widening. Value from IBM CICS documentation.
     */
    public static final int LENGERR = 22;

    // ---------------------------------------------------------------------------------------
    // APPL-RESULT condition values
    //
    // app/cbl/CBTRN02C.cbl:142-144
    //     01  APPL-RESULT             PIC S9(9)   COMP.
    //         88  APPL-AOK            VALUE 0.
    //         88  APPL-EOF            VALUE 16.
    // ---------------------------------------------------------------------------------------

    /**
     * The {@code 88 APPL-AOK VALUE 0} condition: the operation succeeded.
     *
     * <p>Every batch guard funnels its status test into {@code APPL-RESULT} and then branches on
     * {@code IF APPL-AOK}, so this value is the batch programs' internal "carry on" signal. It is
     * distinct from {@link #OK}: that is the two-character status the file system reported, this
     * is the numeric condition the program derived from it.
     */
    public static final int APPL_AOK = 0;

    /**
     * The {@code 88 APPL-EOF VALUE 16} condition: the operation hit end of file.
     *
     * <p>Set by moving {@code 16} into {@code APPL-RESULT} when the status is
     * {@link #END_OF_FILE}, which lets the guard chain distinguish an expected end of input from a
     * genuine failure before deciding whether to abend.
     */
    public static final int APPL_EOF = 16;

    // ---------------------------------------------------------------------------------------
    // The display literal
    // ---------------------------------------------------------------------------------------

    /**
     * The literal emitted by the COBOL {@code DISPLAY}, byte for byte:
     * {@code "FILE STATUS IS: NNNN"} - twenty characters, ending in the four characters
     * {@code NNNN}.
     *
     * <p>The trailing {@code NNNN} is <strong>genuinely part of the COBOL literal</strong> and is
     * not a placeholder awaiting substitution. {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}
     * writes the literal and then the four-character field, so a real line of output reads
     * {@code FILE STATUS IS: NNNN0000}. Reproducing the oddity is required; "correcting" it would
     * change observable output and break byte-level comparison against the legacy behaviour.
     *
     * @see #toDisplayLine(String)
     */
    public static final String DISPLAY_PREFIX = "FILE STATUS IS: NNNN";

    /**
     * Not instantiable: this is a holder for constants and pure static helpers, and no instance of
     * it ever carries state. Declared private so the compiler does not supply a public default
     * constructor.
     */
    private FileStatus() {
        // No instance state, and no instance is ever required. Intentionally empty.
    }

    // ---------------------------------------------------------------------------------------
    // The renderer - a line-by-line translation of 9910-DISPLAY-IO-STATUS
    // ---------------------------------------------------------------------------------------

    /**
     * Renders a two-character file status into the four-character {@code IO-STATUS-04} image,
     * exactly as {@code 9910-DISPLAY-IO-STATUS} composes it.
     *
     * <p>Convenience overload of {@link #toStatusImage(char, char)} that splits the status into its
     * two bytes. Examples, all verified against the COBOL:
     * <table border="1">
     *   <caption>Rendered images</caption>
     *   <tr><th>status</th><th>image</th><th>branch</th></tr>
     *   <tr><td>{@code "00"}</td><td>{@code "0000"}</td><td>numeric</td></tr>
     *   <tr><td>{@code "10"}</td><td>{@code "0010"}</td><td>numeric</td></tr>
     *   <tr><td>{@code "22"}</td><td>{@code "0022"}</td><td>numeric</td></tr>
     *   <tr><td>{@code "23"}</td><td>{@code "0023"}</td><td>numeric</td></tr>
     *   <tr><td>{@code "9\u005Cu0000"}</td><td>{@code "9000"}</td><td>extended</td></tr>
     *   <tr><td>{@code "9\u005Cu000A"}</td><td>{@code "9010"}</td><td>extended</td></tr>
     *   <tr><td>{@code "AB"}</td><td>{@code "A066"}</td><td>extended (non-numeric)</td></tr>
     * </table>
     *
     * @param status the two-character file status; must be non-{@code null} and exactly
     *               {@link #STATUS_LENGTH} characters long
     * @return the four-character image, never {@code null}, always
     *         {@link #STATUS_IMAGE_LENGTH} characters long
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static String toStatusImage(final String status) {
        final String checked = requireTwoCharacterStatus(status);
        return toStatusImage(checked.charAt(0), checked.charAt(1));
    }

    /**
     * Renders the two status bytes into the four-character {@code IO-STATUS-04} image.
     *
     * <p>This is the reviewable core of the class: it is written out statement by statement so that
     * it can be diffed against the COBOL paragraph quoted in the class documentation, and it uses
     * no generic formatting machinery that would obscure the correspondence.
     *
     * <p><strong>Extended branch</strong> - taken when the status is not numeric, or when its first
     * character is {@code '9'} (the VSAM extended-status convention). The COBOL is:
     * <pre>
     * MOVE IO-STAT1 TO IO-STATUS-04(1:1)
     * MOVE 0        TO TWO-BYTES-BINARY
     * MOVE IO-STAT2 TO TWO-BYTES-RIGHT
     * MOVE TWO-BYTES-BINARY TO IO-STATUS-0403
     * </pre>
     * The first character of the image is {@code stat1} verbatim. Then {@code stat2} is deposited
     * into the low-order byte of a zeroed {@code PIC 9(4) BINARY} through the
     * {@code TWO-BYTES-ALPHA REDEFINES}, which reinterprets that raw byte as an unsigned integer in
     * 0..255, and the result fills the {@code PIC 999} tail as three zero-padded decimal digits.
     * Because the maximum is 255 and the receiving field holds three digits, that move never
     * truncates. So {@code '9'} followed by byte {@code 0x0A} renders {@code "9010"}, and
     * {@code '9'} followed by byte {@code 0xFF} renders {@code "9255"} - not a negative number,
     * which is why the byte is masked to eight unsigned bits rather than read as a signed value.
     *
     * <p><strong>Numeric branch</strong> - taken only when the status is numeric <em>and</em> its
     * first character is not {@code '9'}. The COBOL is:
     * <pre>
     * MOVE '0000' TO IO-STATUS-04
     * MOVE IO-STATUS TO IO-STATUS-04(3:2)
     * </pre>
     * so the image is {@code "0000"} with the two status characters overlaid at one-based positions
     * three and four, which is simply {@code "00"} followed by the status.
     *
     * <p>The numeric test follows the COBOL class condition {@code IO-STATUS NOT NUMERIC}. Because
     * {@code IO-STATUS} is a group of two {@code PIC X} items it is alphanumeric, so the class
     * condition holds only when every byte is a digit and no sign is present. See
     * {@link #isSingleByteDigit(char)} for why that is tested explicitly rather than with a Unicode
     * digit test.
     *
     * @param stat1 the first status byte, {@code IO-STAT1}; only its low-order eight bits are
     *              significant, matching a single-byte COBOL {@code PIC X}
     * @param stat2 the second status byte, {@code IO-STAT2}; only its low-order eight bits are
     *              significant
     * @return the four-character image, never {@code null}, always
     *         {@link #STATUS_IMAGE_LENGTH} characters long
     */
    public static String toStatusImage(final char stat1, final char stat2) {
        // BOTH operands are narrowed to their low-order eight bits FIRST, once, and only the narrowed
        // values are used below - for the class condition, for the '9' test and for the rendering
        // alike. IO-STAT1 and IO-STAT2 are PIC X, that is one byte each, so a caller handing over a
        // char above 0xFF has supplied something COBOL storage cannot hold. Narrowing one operand and
        // not the other would make the two halves of this method disagree about what the status is:
        // a char whose low byte is a digit would be rendered on the numeric arm but classified on the
        // non-numeric one, or vice versa. This is the single normalisation the whole method reads
        // from, which is why it happens before the first test rather than beside the second.
        final char firstByte = (char) (stat1 & BYTE_MASK);
        final char secondByte = (char) (stat2 & BYTE_MASK);

        // IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
        //
        // The class condition is evaluated over both bytes of the group; the '9' test looks only at
        // the first. Both operands are kept as named locals so each half of the condition is
        // separately visible to a reviewer and separately reachable by a test.
        final boolean statusIsNumeric = isSingleByteDigit(firstByte) && isSingleByteDigit(secondByte);
        final StringBuilder image = new StringBuilder(STATUS_IMAGE_LENGTH);

        if (!statusIsNumeric || firstByte == '9') {
            // MOVE IO-STAT1 TO IO-STATUS-04(1:1) - the first byte passes through as the single byte
            // COBOL storage holds.
            image.append(firstByte);

            // MOVE 0 TO TWO-BYTES-BINARY, then MOVE IO-STAT2 TO TWO-BYTES-RIGHT. Zeroing the
            // two-byte binary and then overwriting only its low-order byte leaves the field holding
            // that byte's unsigned value, which is why the operand was narrowed above: a raw 0xFF
            // must read as 255, never as -1.
            final int rightByte = secondByte;

            // MOVE TWO-BYTES-BINARY TO IO-STATUS-0403 - a PIC 999 receiver, so exactly three
            // zero-padded decimal digits. Written out digit by digit so the padding rule is
            // explicit in the source rather than delegated to a format string.
            final char hundreds = (char) ('0' + (rightByte / 100));
            final char tens = (char) ('0' + ((rightByte / 10) % 10));
            final char units = (char) ('0' + (rightByte % 10));
            image.append(hundreds).append(tens).append(units);
        } else {
            // MOVE '0000' TO IO-STATUS-04 - positions one and two keep their zeros, because the
            // overlay that follows starts at position three.
            image.append('0').append('0');

            // MOVE IO-STATUS TO IO-STATUS-04(3:2) - the two status characters land on the
            // one-based positions three and four.
            image.append(firstByte).append(secondByte);
        }

        return image.toString();
    }

    /**
     * Builds the complete line that the COBOL {@code DISPLAY} writes: {@link #DISPLAY_PREFIX}
     * immediately followed by the four-character image.
     *
     * <p>{@code toDisplayLine("00")} returns {@code "FILE STATUS IS: NNNN0000"}. Job classes must
     * emit this string rather than assembling their own, so that all sixteen legacy emission sites
     * collapse onto one byte-identical implementation.
     *
     * @param status the two-character file status; must be non-{@code null} and exactly
     *               {@link #STATUS_LENGTH} characters long
     * @return the full display line, never {@code null}
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static String toDisplayLine(final String status) {
        return DISPLAY_PREFIX + toStatusImage(status);
    }

    /**
     * Builds the complete display line from the two status bytes.
     *
     * <p>The byte-pair form of {@link #toDisplayLine(String)}, for the extended-status case where a
     * caller holds a raw byte rather than a printable character.
     *
     * @param stat1 the first status byte, {@code IO-STAT1}
     * @param stat2 the second status byte, {@code IO-STAT2}
     * @return the full display line, never {@code null}
     */
    public static String toDisplayLine(final char stat1, final char stat2) {
        return DISPLAY_PREFIX + toStatusImage(stat1, stat2);
    }

    // ---------------------------------------------------------------------------------------
    // Predicates - one per status test that the COBOL actually performs
    // ---------------------------------------------------------------------------------------

    /**
     * Tests {@code IF <FILE>-STATUS = '00'}: did the operation succeed?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #OK}
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isOk(final String status) {
        return OK.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '10'}: has the browse reached the end of the file?
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #END_OF_FILE}
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isEndOfFile(final String status) {
        return END_OF_FILE.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '23'}: was the keyed record absent?
     *
     * <p>Mirrors {@code app/cbl/CBACT04C.cbl:436}, where a not-found disclosure group triggers the
     * substitution of the {@code 'DEFAULT'} group rather than a failure.
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #NOT_FOUND}
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isNotFound(final String status) {
        return NOT_FOUND.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests {@code IF <FILE>-STATUS = '22'}: was a duplicate key rejected?
     *
     * <p>Provided for completeness of the status set. As documented on {@link #DUPLICATE}, no batch
     * program in this repository performs this comparison; the online programs use
     * {@link #DUPREC} and {@link #DUPKEY} instead.
     *
     * @param status the two-character file status
     * @return {@code true} if the status is exactly {@link #DUPLICATE}
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isDuplicate(final String status) {
        return DUPLICATE.equals(requireTwoCharacterStatus(status));
    }

    /**
     * Tests the compound COBOL condition {@code IF <FILE>-STATUS = '00' OR '23'}: the record was
     * either found or legitimately absent, and either way processing continues.
     *
     * <p>This predicate exists as a first-class member rather than being left to callers to compose,
     * because the compound form is a real and load-bearing idiom in the estate and getting it wrong
     * silently turns a success path into an abend:
     * <ul>
     *   <li>{@code app/cbl/CBACT04C.cbl:422} - {@code IF DISCGRP-STATUS = '00' OR '23'} in the
     *       interest calculation, which falls back to the {@code 'DEFAULT'} disclosure group.</li>
     *   <li>{@code app/cbl/CBTRN02C.cbl:481} - {@code IF TCATBALF-STATUS = '00' OR '23'} in
     *       transaction posting, which then creates the missing category-balance record.</li>
     * </ul>
     * Both sites move {@code 0} into {@code APPL-RESULT} for either status, so not-found must never
     * be folded into a generic error outcome.
     *
     * @param status the two-character file status
     * @return {@code true} if the status is {@link #OK} or {@link #NOT_FOUND}, {@code false}
     *         otherwise - including for {@link #END_OF_FILE} and {@link #DUPLICATE}
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters long
     */
    public static boolean isOkOrNotFound(final String status) {
        final String checked = requireTwoCharacterStatus(status);
        return OK.equals(checked) || NOT_FOUND.equals(checked);
    }

    // ---------------------------------------------------------------------------------------
    // Conversions between the two vocabularies
    //
    // All of these are written as explicit if-chains rather than as switch constructs. Two reasons,
    // both deliberate. First, the chain reads in the same order as the COBOL EVALUATE it replaces,
    // so a reviewer can follow it against the source without decoding a different control shape.
    // Second, a switch over String compiles to a synthetic hashCode lookup followed by a chain of
    // equals calls, whose generated branch count does not correspond one-to-one with the reachable
    // cases; the plain chain has exactly one two-way branch per arm, every one of which a test can
    // drive, which is what the branch-coverage gate requires.
    // ---------------------------------------------------------------------------------------

    /**
     * Classifies a two-character batch file status into the shared {@link Outcome} vocabulary.
     *
     * <p>The arms are ordered and the fall-through is explicit, mirroring the dispatch convention
     * that {@code app/cbl/CBSTM03A.CBL:353-362} states outright:
     * <pre>
     * EVALUATE WS-M03B-RC
     *     WHEN '00' CONTINUE
     *     WHEN '10' MOVE 'Y' TO END-OF-FILE
     *     WHEN OTHER  &lt;display then abend&gt;
     * END-EVALUATE
     * </pre>
     * The same program also uses a two-armed form - {@code WHEN '00'} then {@code WHEN OTHER}, with
     * no end-of-file arm, at {@code :379-386} and {@code :403-410}. Because {@link Outcome} always
     * yields one of five constants, a caller can write an exhaustive {@code switch} for either
     * shape and still have a {@code default}-complete path for the abend case.
     *
     * @param status the two-character file status
     * @return {@link Outcome#OK}, {@link Outcome#END_OF_FILE}, {@link Outcome#NOT_FOUND} or
     *         {@link Outcome#DUPLICATE} for the four recognised statuses, and
     *         {@link Outcome#OTHER} for every other well-formed two-character value
     * @throws NullPointerException     if {@code status} is {@code null}
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
        // WHEN OTHER. Every unrecognised status - a VSAM extended '9x' status, a permanent I/O
        // error, anything at all - lands here, so the caller's fatal path stays reachable and the
        // switch over the result stays exhaustive.
        return Outcome.OTHER;
    }

    /**
     * Classifies a CICS {@code RESP} value into the same {@link Outcome} vocabulary the batch
     * statuses use, so that an online repository and a batch repository report outcomes
     * indistinguishably.
     *
     * <p>The mapping is {@link #NORMAL} to {@link Outcome#OK}, {@link #ENDFILE} to
     * {@link Outcome#END_OF_FILE}, {@link #NOTFND} to {@link Outcome#NOT_FOUND}, and both
     * {@link #DUPREC} and {@link #DUPKEY} to {@link Outcome#DUPLICATE}. Every other response -
     * including {@link #INVREQ}, {@link #NOTOPEN} and {@link #LENGERR}, none of which has a
     * two-character batch equivalent - classifies as {@link Outcome#OTHER}.
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
        // DUPREC and DUPKEY are distinct CICS conditions - a duplicate on the base key and a
        // duplicate on an alternate key - that collapse onto the single batch notion of a duplicate.
        if (cicsResp == DUPREC || cicsResp == DUPKEY) {
            return Outcome.DUPLICATE;
        }
        return Outcome.OTHER;
    }

    /**
     * Translates a CICS {@code RESP} value into the equivalent two-character batch file status.
     *
     * <p>The documented correspondence is {@link #NORMAL} to {@link #OK}, {@link #ENDFILE} to
     * {@link #END_OF_FILE}, {@link #NOTFND} to {@link #NOT_FOUND}, and both {@link #DUPREC} and
     * {@link #DUPKEY} to {@link #DUPLICATE}.
     *
     * <p>The result is empty for every other response. {@link #INVREQ}, {@link #NOTOPEN} and
     * {@link #LENGERR} are deliberately among those: they are real CICS conditions with no
     * two-character batch counterpart, and returning a fabricated status for them would invent
     * behaviour the COBOL never had. An empty result says "this response has no batch equivalent",
     * which is the truthful answer.
     *
     * @param cicsResp the value CICS placed in the {@code RESP} field
     * @return the equivalent two-character status, or an empty {@code Optional} when the response
     *         has no batch equivalent
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
     * Translates a two-character batch file status back into the equivalent CICS {@code RESP} value,
     * for those statuses where the correspondence is one-to-one.
     *
     * <p>Three of the four statuses round-trip exactly: {@link #OK} to {@link #NORMAL},
     * {@link #END_OF_FILE} to {@link #ENDFILE}, and {@link #NOT_FOUND} to {@link #NOTFND}.
     *
     * <p>{@link #DUPLICATE} <strong>does not</strong>, and the result is empty for it. Two distinct
     * CICS conditions - {@link #DUPREC}, a duplicate on the base key, and {@link #DUPKEY}, a
     * duplicate on an alternate key - both map forward onto {@code '22'}, so the reverse direction
     * is genuinely ambiguous. Nominating one of them as canonical would be a fabrication, so this
     * method reports the ambiguity instead of resolving it. A caller that needs a specific CICS
     * condition must name {@link #DUPREC} or {@link #DUPKEY} directly.
     *
     * @param status the two-character file status
     * @return the equivalent CICS {@code RESP} value where the mapping is one-to-one, otherwise an
     *         empty {@code OptionalInt} - for {@link #DUPLICATE} because the reverse is ambiguous,
     *         and for any unrecognised status because no correspondence exists
     * @throws NullPointerException     if {@code status} is {@code null}
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

    // ---------------------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Validates that a value really is a COBOL {@code FILE STATUS} - non-{@code null} and exactly
     * {@link #STATUS_LENGTH} characters - and returns it unchanged.
     *
     * <p>Every public method that accepts a status routes through here, so the two guards live in
     * exactly one place and behave identically everywhere. Failing fast is the correct response: a
     * COBOL {@code FILE STATUS} field is two bytes by declaration and can never be absent or a
     * different width, so either condition is a defect in the calling Java code and not a data
     * condition the COBOL ever had to handle.
     *
     * @param status the candidate status
     * @return {@code status}, unchanged
     * @throws NullPointerException     if {@code status} is {@code null}
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

    /**
     * Tests whether a single byte is one of the ten decimal digits, as the COBOL {@code NUMERIC}
     * class condition does for an alphanumeric item.
     *
     * <p>Written out as an explicit range test rather than delegated to a Unicode-aware digit test,
     * and the difference is not cosmetic: a Unicode digit test also accepts digits from other
     * scripts, which the COBOL class condition would reject. Accepting them would let a status the
     * COBOL considers non-numeric take the numeric rendering branch, and the output would silently
     * diverge. A {@code PIC X} byte in this estate is a single-byte digit or it is not numeric.
     *
     * @param character the byte to test
     * @return {@code true} if the byte is one of {@code '0'} through {@code '9'}
     */
    private static boolean isSingleByteDigit(final char character) {
        return character >= '0' && character <= '9';
    }

    // ---------------------------------------------------------------------------------------
    // The shared outcome vocabulary
    // ---------------------------------------------------------------------------------------

    /**
     * The discriminated outcome of a dataset operation, shared by the batch and online sides.
     *
     * <p>This is the vocabulary repositories report in, so that a caller's branch structure is the
     * same whichever legacy program it was translated from. It is deliberately small: the COBOL only
     * ever distinguishes success, end of file, absence, duplication, and everything-else-is-fatal,
     * and adding finer distinctions here would invent behaviour that no program in the estate has.
     *
     * <p>{@link #OTHER} is load-bearing rather than a catch-all afterthought. It makes a
     * {@code switch} over this enum exhaustive with a {@code default}-complete final arm, which is
     * exactly what the {@code WHEN OTHER} abend path at {@code app/cbl/CBSTM03A.CBL:353-362}
     * requires:
     * <pre>
     * switch (FileStatus.outcomeOfStatus(rc)) {
     *     case OK          -&gt; { }                      // WHEN '00' CONTINUE
     *     case END_OF_FILE -&gt; endOfFile = true;         // WHEN '10' MOVE 'Y' TO END-OF-FILE
     *     case NOT_FOUND, DUPLICATE, OTHER -&gt; abend(); // WHEN OTHER  display then abend
     * }
     * </pre>
     *
     * <p>Note that {@link #NOT_FOUND} is a separate constant and is <em>not</em> folded into
     * {@link #OTHER}. Two programs treat a not-found record as a normal outcome with a fallback -
     * see {@link FileStatus#isOkOrNotFound(String)} - so a caller must be able to accept it without
     * accepting every other failure along with it.
     */
    public enum Outcome {

        /** Success: the batch status {@code '00'}, or the CICS response {@code NORMAL}. */
        OK(FileStatus.OK),

        /** End of file: the batch status {@code '10'}, or the CICS response {@code ENDFILE}. */
        END_OF_FILE(FileStatus.END_OF_FILE),

        /**
         * The keyed record was absent: the batch status {@code '23'}, or the CICS response
         * {@code NOTFND}. Not necessarily an error - see
         * {@link FileStatus#isOkOrNotFound(String)}.
         */
        NOT_FOUND(FileStatus.NOT_FOUND),

        /**
         * A duplicate key was rejected: the batch status {@code '22'}, or the CICS responses
         * {@code DUPREC} and {@code DUPKEY}. As documented on {@link FileStatus#DUPLICATE}, no
         * batch program in this repository tests the {@code '22'} status directly.
         */
        DUPLICATE(FileStatus.DUPLICATE),

        /**
         * Any other result, and therefore fatal in every COBOL guard chain in the estate: the
         * {@code WHEN OTHER} arm. It carries no two-character status, because it does not stand for
         * one particular value - it stands for every value the programs did not enumerate.
         */
        OTHER;

        /**
         * The two-character batch status this outcome stands for, or {@code null} for
         * {@link #OTHER}, which stands for no single status. Exposed only through
         * {@link #batchStatus()}, so no {@code null} escapes the type.
         */
        private final String batchStatus;

        /**
         * Constructs the {@link #OTHER} constant, which has no single corresponding batch status.
         */
        Outcome() {
            this.batchStatus = null;
        }

        /**
         * Constructs an outcome that corresponds to exactly one two-character batch status.
         *
         * @param batchStatus the corresponding status, one of the constants declared on
         *                    {@link FileStatus}
         */
        Outcome(final String batchStatus) {
            this.batchStatus = batchStatus;
        }

        /**
         * The two-character batch {@code FILE STATUS} this outcome corresponds to.
         *
         * <p>This is the reverse of {@link FileStatus#outcomeOfStatus(String)} for the four
         * enumerated outcomes. It is empty for {@link #OTHER} alone, which by definition covers
         * many statuses rather than one.
         *
         * @return the corresponding status, or an empty {@code Optional} for {@link #OTHER}
         */
        public Optional<String> batchStatus() {
            return Optional.ofNullable(this.batchStatus);
        }
    }
}
