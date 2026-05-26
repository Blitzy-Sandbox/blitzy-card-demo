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
package com.blitzy.carddemo.domain.text;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Consolidated translation of two COBOL system-message copybooks &mdash;
 * {@code app/cpy/CSMSG01Y.cpy} (CCDA-COMMON-MESSAGES) and
 * {@code app/cpy/CSMSG02Y.cpy} (ABEND-DATA / CABENDD). This is the only
 * consolidation in the {@code com.blitzy.carddemo.domain.text} subpackage
 * (per AAP &sect;0.4.1 file-by-file transformation table); every other
 * copybook in this subpackage maps one-to-one to a Java class.
 *
 * <h2>Two semantic kinds in one container</h2>
 * The two copybooks describe distinct semantic kinds:
 * <ul>
 *   <li><strong>CCDA-COMMON-MESSAGES</strong> (CSMSG01Y) declares two
 *       {@code PIC X(50) VALUE 'literal'} entries. These are
 *       <em>compile-time CONSTANTS</em> referenced by every online program
 *       that needs to render a thank-you screen or report an invalid AID
 *       key. The idiomatic Java translation is
 *       {@code public static final String} fields on {@link SystemMessages}
 *       itself &mdash; no record, no parse/encode methods, because the
 *       strings are never serialized to or deserialized from a fixed-width
 *       binary file in this form.</li>
 *   <li><strong>ABEND-DATA</strong> (CSMSG02Y, originally CABENDD) declares
 *       a four-field 134-byte WORKING-STORAGE area populated by the abend
 *       routine at runtime. In COBOL this group is MUTABLE; in Java it is
 *       rendered as the IMMUTABLE nested {@link AbendData} record because
 *       each abend handler produces a fresh snapshot rather than mutating
 *       shared state &mdash; semantically equivalent for any caller that
 *       reads the record. {@link AbendData#parse(byte[])} and
 *       {@link AbendData#encode()} provide byte-for-byte fidelity with the
 *       COBOL layout per AAP &sect;0.6.5.</li>
 * </ul>
 *
 * <h2>Verbatim COBOL source</h2>
 * <pre>{@code
 *  01 CCDA-COMMON-MESSAGES.
 *    05 CCDA-MSG-THANK-YOU         PIC X(50) VALUE
 *         'Thank you for using CardDemo application...      '.
 *    05 CCDA-MSG-INVALID-KEY       PIC X(50) VALUE
 *         'Invalid key pressed. Please see below...         '.
 *
 *  01 ABEND-DATA.
 *    05 ABEND-CODE                            PIC X(4)  VALUE SPACES.
 *    05 ABEND-CULPRIT                         PIC X(8)  VALUE SPACES.
 *    05 ABEND-REASON                          PIC X(50) VALUE SPACES.
 *    05 ABEND-MSG                             PIC X(72) VALUE SPACES.
 * }</pre>
 *
 * <h2>50-character common-message width invariant</h2>
 * Each common-message field is COBOL {@code PIC X(50)} &mdash; exactly 50
 * characters of storage. Although the COBOL VALUE clause supplies a 49-
 * character literal in the source file, COBOL right-pads VALUE clauses
 * with ASCII space to the declared PIC width at compile time. The Java
 * constants therefore carry the <em>runtime</em> 50-byte field value (the
 * 49-character literal plus one additional trailing space) so that byte-
 * for-byte parity with COBOL screen output is preserved per AAP &sect;0.6.5.
 *
 * <p>{@link #MESSAGE_LENGTH} records the per-field width. A {@code static}
 * initializer block asserts that both constants are exactly
 * {@link #MESSAGE_LENGTH} characters; this fails fast at class-load time
 * should any future edit accidentally change a constant's width, defending
 * the byte-for-byte parity contract at the earliest possible point in the
 * program lifecycle.
 *
 * <h2>Relationship to {@code ScreenTitle.THANK_YOU}</h2>
 * The COBOL source repository deliberately contains TWO distinct "thank
 * you" strings:
 * <ul>
 *   <li>{@link #THANK_YOU_MSG} (this class) &mdash; 50 characters, derived
 *       from {@code CSMSG01Y.cpy} with the wording "Thank you for using
 *       <strong>CardDemo</strong> application...".</li>
 *   <li>{@code ScreenTitle.THANK_YOU} (sibling class) &mdash; 40 characters,
 *       derived from {@code COTTL01Y.cpy} with the wording "Thank you for
 *       using <strong>CCDA</strong> application...".</li>
 * </ul>
 * The wording difference ("CardDemo" vs "CCDA") and the width difference
 * (50 vs 40) both exist in the COBOL source and are preserved verbatim per
 * AAP &sect;0.7.1 idiom-for-idiom translation. They are
 * <strong>not</strong> consolidated.
 *
 * <h2>Charset</h2>
 * {@link AbendData#parse(byte[])} and {@link AbendData#encode()} use
 * {@link StandardCharsets#US_ASCII}. ABEND-DATA is operator-facing text
 * written into a job log; in the canonical golden-record fixtures and on
 * any non-z/OS deployment the bytes are ASCII. EBCDIC transcoding for
 * production z/OS feeds is handled by a separate {@code EbcdicTranscoder}
 * adapter in the {@code carddemo-adapter-file} module before reaching this
 * domain record &mdash; this class never deals with EBCDIC directly.
 *
 * <h2>Instantiation</h2>
 * {@code SystemMessages} is a pure static-constants holder; the
 * {@code final} modifier prevents subclassing and the private constructor
 * throws {@link AssertionError} to defend against reflective instantiation.
 * Only the nested {@link AbendData} record is intended to be constructed
 * by clients.
 *
 * <h2>Provenance</h2>
 * Original COBOL footer stamp on both source copybooks:
 * {@code Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT}.
 *
 * @see com.blitzy.carddemo.domain.text.ScreenTitle for the sibling 40-char
 *      title constants
 * @see com.blitzy.carddemo.domain.annotation.CobolProgram
 * @since 1.0.0
 */
@CobolProgram(
        value = "CSMSG01Y/CSMSG02Y",
        sourcePath = "app/cpy/CSMSG01Y.cpy + app/cpy/CSMSG02Y.cpy",
        notes = "Consolidated translation of two COBOL copybooks: CSMSG01Y "
                + "(CCDA-COMMON-MESSAGES — two PIC X(50) VALUE-literal constants for "
                + "thank-you and invalid-key screens) and CSMSG02Y (ABEND-DATA / CABENDD — "
                + "134-byte WORKING-STORAGE work area for the abend routine). The constants "
                + "translate to public static final String fields; the work area translates to "
                + "the nested immutable AbendData record with parse/encode methods. This is the "
                + "only consolidation in carddemo-domain.text per AAP §0.4.1. Source footer "
                + "version: CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19 23:15:58 CDT)."
)
public final class SystemMessages {

    // -----------------------------------------------------------------------
    // CCDA-COMMON-MESSAGES (CSMSG01Y) — user-facing 50-byte messages
    // -----------------------------------------------------------------------

    /**
     * Width of each common-message field, in characters. Mirrors the COBOL
     * declaration {@code PIC X(50)} on both 05-level entries of the
     * {@code CCDA-COMMON-MESSAGES} group. Every {@link String} constant in
     * this class is required to be exactly this many characters long; the
     * {@code static} initializer block enforces this invariant at class
     * load.
     *
     * <p>External consumers (BMS-to-DTO translation, online programs that
     * write a status line to a screen) should use this constant when
     * computing offsets or padding lengths rather than hard-coding the
     * literal {@code 50}.
     */
    public static final int MESSAGE_LENGTH = 50;

    /**
     * COBOL field {@code CCDA-MSG-THANK-YOU}, declared in
     * {@code CSMSG01Y.cpy} lines 18&ndash;19 as:
     * <pre>{@code 05 CCDA-MSG-THANK-YOU PIC X(50) VALUE
     *    'Thank you for using CardDemo application...      '.}</pre>
     *
     * <p>The COBOL source literal is 49 characters; COBOL right-pads VALUE
     * clauses with ASCII space to the declared PIC width at compile time,
     * yielding a 50-byte runtime field value. This Java constant carries
     * that runtime 50-character value (43-character message text + 7
     * trailing spaces) so the byte-for-byte parity contract holds.
     *
     * <p>Layout: {@code "Thank you for using CardDemo application..."}
     * (43 chars) + 7 trailing spaces = {@value MESSAGE_LENGTH} characters.
     *
     * <p>Rendered on screen exit by online programs that complete a
     * transaction. <strong>Not to be confused with
     * {@code ScreenTitle.THANK_YOU}</strong>, which is the 40-character
     * "CCDA"-worded sibling from {@code COTTL01Y.cpy}.
     */
    public static final String THANK_YOU_MSG = "Thank you for using CardDemo application...       ";

    /**
     * COBOL field {@code CCDA-MSG-INVALID-KEY}, declared in
     * {@code CSMSG01Y.cpy} lines 20&ndash;21 as:
     * <pre>{@code 05 CCDA-MSG-INVALID-KEY PIC X(50) VALUE
     *    'Invalid key pressed. Please see below...         '.}</pre>
     *
     * <p>The COBOL source literal is 49 characters; COBOL right-pads VALUE
     * clauses with ASCII space to the declared PIC width at compile time,
     * yielding a 50-byte runtime field value. This Java constant carries
     * that runtime 50-character value (40-character message text + 10
     * trailing spaces) so the byte-for-byte parity contract holds.
     *
     * <p>Layout: {@code "Invalid key pressed. Please see below..."}
     * (40 chars) + 10 trailing spaces = {@value MESSAGE_LENGTH} characters.
     *
     * <p>Rendered by online programs when the operator presses an AID key
     * that the current screen does not accept (see the
     * {@code com.blitzy.carddemo.domain.text.CcWorkAreas} {@code AidKey}
     * sealed hierarchy for the closed set of AID keys recognized by the
     * application).
     */
    public static final String INVALID_KEY_MSG = "Invalid key pressed. Please see below...          ";

    /*
     * Static initializer: fail-fast width check.
     *
     * Each common-message constant above MUST be exactly MESSAGE_LENGTH (50)
     * characters; this is the Java equivalent of the COBOL `PIC X(50)` width
     * declaration. If any future edit accidentally changes a constant's
     * length, the JVM will refuse to load this class with an AssertionError
     * that names the offending constant and reports the actual length. This
     * guards byte-for-byte file fidelity (AAP §0.6.5) at the earliest
     * possible point in the program lifecycle.
     */
    static {
        if (THANK_YOU_MSG.length() != MESSAGE_LENGTH) {
            throw new AssertionError(
                    "THANK_YOU_MSG must be exactly " + MESSAGE_LENGTH
                            + " characters (COBOL PIC X(50)); actual length is "
                            + THANK_YOU_MSG.length());
        }
        if (INVALID_KEY_MSG.length() != MESSAGE_LENGTH) {
            throw new AssertionError(
                    "INVALID_KEY_MSG must be exactly " + MESSAGE_LENGTH
                            + " characters (COBOL PIC X(50)); actual length is "
                            + INVALID_KEY_MSG.length());
        }
    }

    /**
     * Private constructor: this class is a pure static-constants holder
     * (and namespace for the nested {@link AbendData} record). Throws
     * {@link AssertionError} unconditionally to defend against reflective
     * instantiation attempts; the {@code final} class modifier already
     * prevents subclassing.
     *
     * @throws AssertionError always; this class must not be instantiated
     */
    private SystemMessages() {
        throw new AssertionError(
                "SystemMessages is a static constants holder; do not instantiate.");
    }

    // -----------------------------------------------------------------------
    // ABEND-DATA (CSMSG02Y / CABENDD) — 134-byte abend-routine work area
    // -----------------------------------------------------------------------

    /**
     * Immutable Java translation of the COBOL {@code ABEND-DATA} group
     * (CSMSG02Y, original copybook name CABENDD). Represents a single
     * in-memory snapshot of the abend routine's state &mdash; the abend
     * code, the culprit program name, the human-readable reason, and the
     * operator message to write to the system console. Total fixed-width
     * length is exactly 134 bytes (4 + 8 + 50 + 72) so the record can be
     * written to and read from any 134-byte fixed-width slot with byte-
     * for-byte fidelity per AAP &sect;0.6.5.
     *
     * <h2>Verbatim COBOL layout</h2>
     * <pre>{@code
     *  01 ABEND-DATA.
     *    05 ABEND-CODE     PIC X(4)  VALUE SPACES.   (4 bytes  — offset 0..3)
     *    05 ABEND-CULPRIT  PIC X(8)  VALUE SPACES.   (8 bytes  — offset 4..11)
     *    05 ABEND-REASON   PIC X(50) VALUE SPACES.   (50 bytes — offset 12..61)
     *    05 ABEND-MSG      PIC X(72) VALUE SPACES.   (72 bytes — offset 62..133)
     * }</pre>
     *
     * <h2>Mutability difference from COBOL (semantically equivalent)</h2>
     * In COBOL, {@code ABEND-DATA} is a WORKING-STORAGE group populated by
     * the abend handler at runtime (the handler {@code MOVE}s values into
     * each field, then writes the group to the operator console / job log).
     * In Java this record is immutable. The two are semantically
     * equivalent because each abend produces a fresh snapshot &mdash; no
     * caller mutates an existing {@code AbendData} in-place; instead the
     * handler constructs a new instance with the populated fields and
     * passes it to the writer. Per AAP &sect;0.7.4 (no {@code ThreadLocal},
     * no shared mutable state in new code) the immutable record is the
     * preferred translation.
     *
     * <h2>Byte-for-byte fidelity contract</h2>
     * {@link #parse(byte[])} and {@link #encode()} are inverses. For every
     * valid 134-byte buffer {@code b}, {@code Arrays.equals(
     * AbendData.parse(b).encode(), b)} returns {@code true}. Symmetrically,
     * for every record {@code r} this class can produce,
     * {@code AbendData.parse(r.encode()).equals(r)} holds. Both methods
     * use {@link StandardCharsets#US_ASCII}.
     *
     * <h2>Field padding semantics</h2>
     * Every field is COBOL {@code PIC X(n)}, which is right-padded with
     * ASCII spaces to the declared length. The {@link #encode()} method
     * preserves this convention by pre-filling the output buffer with
     * ASCII space ({@code 0x20}) before writing each field. String values
     * shorter than the field length are written verbatim followed by
     * trailing spaces (because the surrounding bytes were already
     * pre-filled). The compact canonical constructor rejects values
     * longer than the field length.
     *
     * <h2>{@code empty()} factory</h2>
     * {@link #empty()} produces the {@code VALUE SPACES} initial state
     * (every field rendered as a string of ASCII spaces at the declared
     * field length). This is the Java equivalent of the COBOL declaration
     * {@code VALUE SPACES} on every 05-level entry.
     *
     * @param code     the abend code (PIC X(4)); typically a 4-character
     *                 system or user abend identifier such as {@code "S322"}
     *                 or {@code "U999"}. Must be non-null and at most 4
     *                 characters long.
     * @param culprit  the culprit program name (PIC X(8)); typically the
     *                 8-character COBOL PROGRAM-ID of the program that
     *                 detected the abend condition. Must be non-null and
     *                 at most 8 characters long.
     * @param reason   the human-readable reason text (PIC X(50)); a short
     *                 sentence describing why the abend was raised. Must
     *                 be non-null and at most 50 characters long.
     * @param msg      the operator message (PIC X(72)); a longer message
     *                 written to the system console / job log. Must be
     *                 non-null and at most 72 characters long.
     * @see com.blitzy.carddemo.domain.annotation.CobolProgram
     */
    @CobolProgram(
            value = "CSMSG02Y",
            sourcePath = "app/cpy/CSMSG02Y.cpy",
            notes = "ABEND-DATA work area (originally copybook CABENDD); 134 bytes total "
                    + "(PIC X(4) + X(8) + X(50) + X(72)). COBOL mutable WORKING-STORAGE → "
                    + "Java immutable record. Each abend produces a fresh snapshot rather than "
                    + "mutating shared state — semantically equivalent. Source footer version: "
                    + "CardDemo_v1.0-15-g27d6c6f-68 (2022-07-19 23:15:58 CDT)."
    )
    public record AbendData(String code, String culprit, String reason, String msg) {

        // -------------------------------------------------------------------
        // Field offset and length constants (binary layout — DO NOT REORDER)
        // -------------------------------------------------------------------

        /**
         * Total fixed record length in bytes (sum of all field lengths).
         * Equals {@value #CODE_LENGTH} + {@value #CULPRIT_LENGTH} +
         * {@value #REASON_LENGTH} + {@value #MSG_LENGTH} = 134 bytes,
         * matching the COBOL {@code ABEND-DATA} group width.
         */
        public static final int RECORD_LENGTH = 134;

        /** Zero-based byte offset of {@link #code()} within the record buffer. */
        public static final int CODE_OFFSET = 0;

        /** Byte length of {@link #code()} (COBOL {@code ABEND-CODE PIC X(4)}). */
        public static final int CODE_LENGTH = 4;

        /** Zero-based byte offset of {@link #culprit()} within the record buffer. */
        public static final int CULPRIT_OFFSET = 4;

        /** Byte length of {@link #culprit()} (COBOL {@code ABEND-CULPRIT PIC X(8)}). */
        public static final int CULPRIT_LENGTH = 8;

        /** Zero-based byte offset of {@link #reason()} within the record buffer. */
        public static final int REASON_OFFSET = 12;

        /** Byte length of {@link #reason()} (COBOL {@code ABEND-REASON PIC X(50)}). */
        public static final int REASON_LENGTH = 50;

        /** Zero-based byte offset of {@link #msg()} within the record buffer. */
        public static final int MSG_OFFSET = 62;

        /** Byte length of {@link #msg()} (COBOL {@code ABEND-MSG PIC X(72)}). */
        public static final int MSG_LENGTH = 72;

        // -------------------------------------------------------------------
        // Internal constants
        // -------------------------------------------------------------------

        /** ASCII space byte (0x20) — the COBOL space-fill character used by VALUE SPACES. */
        private static final byte ASCII_SPACE = (byte) ' ';

        /*
         * Static initializer: fail-fast geometry check.
         *
         * RECORD_LENGTH MUST equal the sum of the four field lengths and the
         * offsets MUST be cumulative. If any future edit accidentally
         * mis-aligns the layout, the JVM will refuse to load this class with
         * an AssertionError. This guards byte-for-byte parity (AAP §0.6.5)
         * at the earliest possible point in the program lifecycle.
         */
        static {
            int sum = CODE_LENGTH + CULPRIT_LENGTH + REASON_LENGTH + MSG_LENGTH;
            if (sum != RECORD_LENGTH) {
                throw new AssertionError(
                        "AbendData field-length sum (" + sum + ") != RECORD_LENGTH ("
                                + RECORD_LENGTH + ")");
            }
            if (CODE_OFFSET != 0
                    || CULPRIT_OFFSET != CODE_OFFSET + CODE_LENGTH
                    || REASON_OFFSET != CULPRIT_OFFSET + CULPRIT_LENGTH
                    || MSG_OFFSET != REASON_OFFSET + REASON_LENGTH) {
                throw new AssertionError(
                        "AbendData field offsets are not cumulative: "
                                + "CODE_OFFSET=" + CODE_OFFSET
                                + ", CULPRIT_OFFSET=" + CULPRIT_OFFSET
                                + ", REASON_OFFSET=" + REASON_OFFSET
                                + ", MSG_OFFSET=" + MSG_OFFSET);
            }
        }

        // -------------------------------------------------------------------
        // Compact canonical constructor (validation + JEP 513 pattern)
        // -------------------------------------------------------------------

        /**
         * Compact canonical constructor (JEP 513-style flexible constructor
         * body). Validates {@code null} and length constraints BEFORE binding
         * fields per the Agent Action Plan §0.6.3 mandate.
         *
         * <p>Each {@link String} component must be non-{@code null} and
         * within its COBOL {@code PIC X(n)} field width. Shorter values are
         * tolerated and are right-padded with ASCII spaces at
         * {@link #encode()} time; this mirrors COBOL's {@code MOVE}
         * semantics where a shorter source is space-padded to the declared
         * length of the receiving field.
         *
         * @throws NullPointerException     if any of {@code code},
         *                                  {@code culprit}, {@code reason},
         *                                  or {@code msg} is {@code null}
         * @throws IllegalArgumentException if {@code code.length() > CODE_LENGTH},
         *                                  {@code culprit.length() > CULPRIT_LENGTH},
         *                                  {@code reason.length() > REASON_LENGTH},
         *                                  or {@code msg.length() > MSG_LENGTH}
         */
        public AbendData {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(culprit, "culprit");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(msg, "msg");
            if (code.length() > CODE_LENGTH) {
                throw new IllegalArgumentException(
                        "ABEND-CODE length " + code.length() + " exceeds PIC X("
                                + CODE_LENGTH + ")");
            }
            if (culprit.length() > CULPRIT_LENGTH) {
                throw new IllegalArgumentException(
                        "ABEND-CULPRIT length " + culprit.length() + " exceeds PIC X("
                                + CULPRIT_LENGTH + ")");
            }
            if (reason.length() > REASON_LENGTH) {
                throw new IllegalArgumentException(
                        "ABEND-REASON length " + reason.length() + " exceeds PIC X("
                                + REASON_LENGTH + ")");
            }
            if (msg.length() > MSG_LENGTH) {
                throw new IllegalArgumentException(
                        "ABEND-MSG length " + msg.length() + " exceeds PIC X("
                                + MSG_LENGTH + ")");
            }
        }

        // -------------------------------------------------------------------
        // empty() — VALUE SPACES factory
        // -------------------------------------------------------------------

        /**
         * Factory returning an {@code AbendData} that matches the COBOL
         * {@code VALUE SPACES} initialization on every 05-level entry of
         * the {@code ABEND-DATA} group. Each of the four String components
         * is a sequence of ASCII space characters at the declared COBOL
         * field length:
         * <ul>
         *   <li>{@code code} &mdash; 4 spaces</li>
         *   <li>{@code culprit} &mdash; 8 spaces</li>
         *   <li>{@code reason} &mdash; 50 spaces</li>
         *   <li>{@code msg} &mdash; 72 spaces</li>
         * </ul>
         *
         * <p>The {@link #encode()} of an empty record produces a 134-byte
         * buffer composed entirely of ASCII space ({@code 0x20}). This is
         * the canonical initial state of the abend-routine work area
         * before any abend has occurred.
         *
         * @return a fresh empty {@code AbendData}; never {@code null}
         */
        public static AbendData empty() {
            return new AbendData(
                    " ".repeat(CODE_LENGTH),
                    " ".repeat(CULPRIT_LENGTH),
                    " ".repeat(REASON_LENGTH),
                    " ".repeat(MSG_LENGTH));
        }

        // -------------------------------------------------------------------
        // parse(byte[]) — fixed-width buffer → record factory
        // -------------------------------------------------------------------

        /**
         * Parses a 134-byte fixed-width buffer into an {@code AbendData}
         * record. The buffer is interpreted as US-ASCII per the AAP
         * &sect;0.6.5 fixed-width record contract; the bytes carrying
         * each COBOL {@code PIC X(n)} field are decoded verbatim, including
         * any trailing space padding emitted by the COBOL writer.
         *
         * <p>The returned record satisfies the round-trip invariant
         * {@code Arrays.equals(parse(b).encode(), b)} for every valid
         * 134-byte buffer {@code b}.
         *
         * @param buffer the 134-byte input buffer (typically read from a
         *               job-log slot or a golden-record fixture via
         *               {@code Files.readAllBytes} at the appropriate
         *               offset). Must be non-null and exactly
         *               {@value #RECORD_LENGTH} bytes long.
         * @return a fully-populated {@code AbendData} record
         * @throws NullPointerException     if {@code buffer} is {@code null}
         * @throws IllegalArgumentException if {@code buffer.length !=
         *                                  RECORD_LENGTH}
         */
        public static AbendData parse(byte[] buffer) {
            Objects.requireNonNull(buffer, "buffer");
            if (buffer.length != RECORD_LENGTH) {
                throw new IllegalArgumentException(
                        "AbendData buffer must be exactly " + RECORD_LENGTH
                                + " bytes; got " + buffer.length);
            }
            String code    = new String(buffer, CODE_OFFSET,    CODE_LENGTH,    StandardCharsets.US_ASCII);
            String culprit = new String(buffer, CULPRIT_OFFSET, CULPRIT_LENGTH, StandardCharsets.US_ASCII);
            String reason  = new String(buffer, REASON_OFFSET,  REASON_LENGTH,  StandardCharsets.US_ASCII);
            String msg     = new String(buffer, MSG_OFFSET,     MSG_LENGTH,     StandardCharsets.US_ASCII);
            return new AbendData(code, culprit, reason, msg);
        }

        // -------------------------------------------------------------------
        // encode() — record → fixed-width buffer
        // -------------------------------------------------------------------

        /**
         * Serializes this record into a fresh 134-byte buffer suitable for
         * writing to a job-log slot or a golden-record fixture file. The
         * output buffer is first filled with ASCII spaces ({@code 0x20})
         * via {@link Arrays#fill(byte[], byte)} so that shorter String
         * components are automatically right-padded to their COBOL
         * {@code PIC X(n)} length &mdash; this mirrors the COBOL VALUE
         * SPACES initial state and {@code MOVE} field-padding semantics.
         *
         * <p>Each field is written into the buffer at its zero-based byte
         * offset using {@link StandardCharsets#US_ASCII}. The returned
         * buffer satisfies the round-trip invariant
         * {@code parse(this.encode()).equals(this)} for every valid record.
         *
         * @return a new 134-byte buffer; never {@code null}. The caller
         *         owns the returned array and may mutate it without
         *         affecting this record.
         */
        public byte[] encode() {
            byte[] buffer = new byte[RECORD_LENGTH];
            Arrays.fill(buffer, ASCII_SPACE); // pre-fill with ASCII space (COBOL PIC X right-pad)
            writeStringField(buffer, code,    CODE_OFFSET,    CODE_LENGTH);
            writeStringField(buffer, culprit, CULPRIT_OFFSET, CULPRIT_LENGTH);
            writeStringField(buffer, reason,  REASON_OFFSET,  REASON_LENGTH);
            writeStringField(buffer, msg,     MSG_OFFSET,     MSG_LENGTH);
            return buffer;
        }

        /**
         * Writes a String value into {@code buffer} at the given offset
         * using US-ASCII encoding. Bytes beyond {@code value.length()}
         * (up to the field {@code length}) are left untouched; the caller
         * is responsible for pre-filling those positions with the desired
         * pad character (ASCII space in this record). The number of bytes
         * written equals
         * {@code min(value.getBytes(US_ASCII).length, length)} &mdash;
         * oversized values are silently truncated by the {@code Math.min}
         * clamp, although the compact constructor rejects them up-front,
         * so in practice this truncation path is dead code reachable only
         * via reflection.
         *
         * @param buffer the destination byte buffer (already pre-filled
         *               with ASCII space by the caller)
         * @param value  the String value to write
         * @param offset the zero-based byte offset within {@code buffer}
         * @param length the COBOL {@code PIC X(n)} field width
         */
        private static void writeStringField(byte[] buffer, String value, int offset, int length) {
            byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
            int copyLength = Math.min(valueBytes.length, length);
            System.arraycopy(valueBytes, 0, buffer, offset, copyLength);
            // Remaining bytes already pre-filled with ASCII space by encode() — PIC X right-pad.
        }
    }
}
