/*
 * ******************************************************************
 * Program     : FileStatus.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 enumeration
 * Function    : Typed COBOL FILE STATUS values, the byte-exact
 *               four-character IO-STATUS-04 rendering, and the
 *               log-safe encoded rendering used for diagnostics.
 * Source      : app/cbl/CBTRN02C.cbl:L131-L144,L714-L727 @ 7756d89
 *               app/cbl/CBSTM03A.CBL:L736,L748 @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.model.enums;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Typed representation of the COBOL {@code FILE STATUS} values that the CardDemo batch corpus tests, together
 * with the byte exact four character {@code IO-STATUS-04} rendering that the corpus writes to SYSOUT whenever
 * an input or output guard fails.
 *
 * <h2>What this type does, and what it deliberately refuses to do</h2>
 *
 * <p><strong>This enumeration classifies; it does not decide.</strong> It answers exactly one question,
 * namely which status family a raw two character value belongs to, and it renders that value the way the
 * legacy programs render it. It deliberately does not map a status onto an exception, does not raise a
 * domain exception on a caller's behalf, and does not know which call site tolerates which status. Those
 * are decisions rather than classifications, they vary from one call site to the next, and they live
 * exactly once in {@code com.cardemo.service.shared.FileStatusMapper}. Consequently this type depends on
 * no other CardDemo type, performs no input or output, reads no configuration, holds no mutable state,
 * emits no log record and produces no metric. Every method on it is a pure function of its arguments.
 *
 * <p>Cross package types are referenced with {@code @code} rather than {@code @link} throughout, because
 * the referenced types are authored by sibling units of this same migration and an unresolved link would
 * fail a documentation build rather than merely warn.
 *
 * <h2>The universal input and output guard idiom</h2>
 *
 * <p>Every {@code OPEN}, {@code READ}, {@code WRITE}, {@code REWRITE} and {@code CLOSE} in the batch
 * corpus is wrapped in one single shape. The working storage that shape needs is declared at
 * {@code app/cbl/CBTRN02C.cbl:L131-L144}:
 *
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
 *
 * 01  APPL-RESULT             PIC S9(9)   COMP.
 *     88  APPL-AOK            VALUE 0.
 *     88  APPL-EOF            VALUE 16.
 * </pre>
 *
 * <p>Note that {@code APPL-EOF} is sixteen and not twelve. The two condition names are the only declared
 * values; the numbers 8 and 12 that appear in the procedure division are working values assigned to
 * {@code APPL-RESULT} and are not condition names. The idiom moves 8 in before the verb, performs the
 * verb, moves 0 when the status is {@code '00'} and 12 otherwise, and then either continues or displays a
 * message, copies the file status into {@code IO-STATUS}, renders it and abends. The canonical instance is
 * {@code 0000-DALYTRAN-OPEN} at {@code app/cbl/CBTRN02C.cbl:L236-L252}:
 *
 * <pre>
 * 0000-DALYTRAN-OPEN.
 *     MOVE 8 TO APPL-RESULT.
 *     OPEN INPUT DALYTRAN-FILE
 *     IF  DALYTRAN-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         MOVE 12 TO APPL-RESULT
 *     END-IF
 *     IF  APPL-AOK
 *         CONTINUE
 *     ELSE
 *         DISPLAY 'ERROR OPENING DALYTRAN'
 *         MOVE DALYTRAN-STATUS TO IO-STATUS
 *         PERFORM 9910-DISPLAY-IO-STATUS
 *         PERFORM 9999-ABEND-PROGRAM
 *     END-IF
 *     EXIT.
 * </pre>
 *
 * <p><strong>This is one idiom, not hundreds of independent checks, and the corpus proves it.</strong>
 * Recognising that is the whole justification for a single typed status vocabulary here and a single
 * central translation in {@code com.cardemo.service.shared.FileStatusMapper} rather than a bespoke check
 * per call site. The evidence, measured at commit {@code 7756d89}:
 * <ul>
 *   <li>The renderer body is byte identical in eight batch programs, and it appears under two different
 *       paragraph labels. It is called {@code 9910-DISPLAY-IO-STATUS} at
 *       {@code app/cbl/CBACT01C.cbl:L176}, {@code app/cbl/CBACT02C.cbl:L161},
 *       {@code app/cbl/CBACT03C.cbl:L161}, {@code app/cbl/CBACT04C.cbl:L635},
 *       {@code app/cbl/CBTRN02C.cbl:L714} and {@code app/cbl/CBTRN03C.cbl:L633}, and
 *       {@code Z-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN01C.cbl:L476} and
 *       {@code app/cbl/CBCUS01C.cbl:L161}. The two names describe the same fourteen lines.</li>
 *   <li>Seventy eight guard sites invoke it: 62 occurrences of
 *       {@code PERFORM 9910-DISPLAY-IO-STATUS} and 16 of {@code PERFORM Z-DISPLAY-IO-STATUS}.</li>
 *   <li>The {@code IO-STATUS-04} declaration recurs verbatim in all eight programs, for example at
 *       {@code app/cbl/CBTRN01C.cbl:L138-L140}, {@code app/cbl/CBCUS01C.cbl:L57-L59},
 *       {@code app/cbl/CBACT01C.cbl:L57-L59} and {@code app/cbl/CBTRN03C.cbl:L146-L148}, each of them
 *       the group header followed by its {@code PIC 9} and {@code PIC 999} items.</li>
 * </ul>
 *
 * <h2>Exact values against the one family</h2>
 *
 * <p>Six constants stand for exact two character values and are looked up by equality. The seventh,
 * {@link #IO_ERROR}, is a <em>family</em>: the corpus never compares a whole two character literal for it
 * but instead tests the first byte alone, {@code IO-STAT1 = '9'}, leaving the second byte free to carry a
 * physical or logical error subcode. {@link #IO_ERROR} therefore has no single canonical two character
 * code and {@link #code()} returns an empty {@link Optional} for it. Use {@link #isExactValue()} and
 * {@link #isFamily()} to tell the two shapes apart, and never assume that a constant of this enumeration
 * can be turned back into a two character string.
 *
 * <h2>Three sites where a record not found or a secondary status is success</h2>
 *
 * <p>Everywhere else in the corpus a record not found status is an error that reaches the abend guard. At
 * exactly three sites it is not, and the classification offered here is deliberately neutral about that so
 * the decision can stay with the caller:
 * <ol>
 *   <li>{@code app/cbl/CBTRN02C.cbl:L481}, inside {@code 2700-UPDATE-TCATBAL} which spans L467 to L501,
 *       reads {@code IF TCATBALF-STATUS = '00'  OR '23'}. This is the transaction category balance upsert:
 *       on {@code INVALID KEY} the read displays a not found message and a creating message at L476 and
 *       L477, sets the create flag at L478, and the paragraph then dispatches either to the create branch
 *       at L503 to L524 or to the rewrite branch at L526 to L542, both of which add the transaction
 *       amount. The leniency is scoped to the read guard only: the subsequent {@code WRITE} verification
 *       at L512 and {@code REWRITE} verification at L530 accept {@code '00'} and nothing else.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:L422} reads {@code IF DISCGRP-STATUS  = '00'  OR '23'} for the
 *       disclosure group interest rate lookup, so a missing row is not fatal there. The specific test at
 *       {@code app/cbl/CBACT04C.cbl:L436}, {@code IF DISCGRP-STATUS  = '23'}, is what substitutes the
 *       literal default group identifier and retries through {@code 1200-A-GET-DEFAULT-INT-RATE} at L443.
 *       That retry's guard at L446 accepts {@code '00'} only, so a missing default row abends the job.</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:L736} and {@code app/cbl/CBSTM03A.CBL:L748} read
 *       {@code IF WS-M03B-RC = '00' OR '04'} and abend otherwise. Those are the open and the read of
 *       {@code 8100-TRNXFILE-OPEN}, which begins at L730 and whose else branch displays
 *       {@code 'ERROR OPENING TRNXFILE'} and performs the abend paragraph at L739 to L741. The same
 *       acceptance recurs at L771, L789, L807, L862, L879, L895 and L911, nine sites in total. Note that
 *       this same program carries a second and stricter idiom: four {@code EVALUATE WS-M03B-RC} sites at
 *       L353, L379, L403 and L837 accept {@code '00'} alone, treat {@code '10'} as end of file and send
 *       everything else to the error path.</li>
 * </ol>
 *
 * <h2>Unmapped statuses</h2>
 *
 * <p>A status this type cannot classify is exactly the corpus's else branch, and the corpus answers it by
 * abending. {@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L707-L711} displays
 * {@code 'ABENDING PROGRAM'}, zeroes the timing field, moves <strong>999</strong> into the abend code and
 * calls {@code 'CEE3ABD'}; the process return code is 12. None of that is implemented here. It belongs to
 * {@code com.cardemo.exception.FatalProcessingException} and to the batch layer, and it is described only
 * so that the meaning of an unclassifiable status is unambiguous.
 *
 * <h2>Determinism</h2>
 *
 * <p>Nothing in this type can vary with the platform default locale, charset or time zone. The single
 * formatting call passes {@link Locale#ROOT} explicitly, so the three digit expansion always emits ASCII
 * digits rather than a locale specific numbering system. The numeric class test is written as an explicit
 * comparison against the characters {@code '0'} through {@code '9'} and deliberately does not use
 * {@code java.lang.Character#isDigit}, which also accepts non ASCII decimal digits such as the Arabic
 * Indic and Devanagari forms and would therefore route input through the wrong branch. No method performs
 * a case conversion, so no Turkish locale hazard exists. The one internal lookup map is immutable and is
 * never iterated, so no behaviour depends on hash iteration order.
 *
 * <h2>Build and test</h2>
 *
 * <p>This type is compiled by the root {@code pom.xml} for Java 25 with {@code -Xlint:all -Werror} and
 * {@code failOnWarning}, so a raw type, a switch fall through or a missing {@code serialVersionUID} is a
 * build failure rather than a warning. An unused import is not - {@code javac} 25.0.3 publishes no lint
 * key for one, so Rule 1 Clause B's prohibition on it is enforced by review. Build with
 * {@code ./mvnw -B clean compile}, run the unit suite with {@code ./mvnw -B clean test} and gate coverage with
 * {@code ./mvnw -B verify}, which enforces an eighty percent line floor. The type adds no dependency, needs
 * no annotation processor and does not use Lombok. Its unit tests belong in
 * {@code src/test/java/com/cardemo/unit/model} and must cover both rendering branches, every constant and
 * the malformed input cases, because the rendering is compared byte for byte against the legacy baseline.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None. This type reads no property, no environment variable and no classpath resource. Its only
 * constants are the status codes themselves, the four character rendered width, the family first byte and
 * the twenty character display literal, all of which are fixed by the frozen corpus and none of which is
 * configurable by design.
 *
 * <h2>Serialization</h2>
 *
 * <p>This type deliberately does not implement {@code java.io.Serializable}. Enum constants serialize by
 * name through the platform's built in enum support when an enclosing serializable graph requires it, so
 * declaring the interface would add nothing while introducing a {@code serialVersionUID} obligation. There
 * is no {@code ObjectInputStream} path anywhere in the class and nothing here may be used to deserialize
 * untrusted input.
 *
 * <h2>Common failure modes</h2>
 *
 * <ul>
 *   <li>An {@link IllegalArgumentException} from {@link #classify(String)} means the value was not one of
 *       the six exact codes and did not begin with the family byte. The message quotes the offending
 *       value. Remediation: either the status is genuinely unmapped, in which case the caller should abend
 *       exactly as the corpus does, or the value was never a two character file status at all, which
 *       usually means it was trimmed, upper cased, parsed or wrapped somewhere upstream.</li>
 *   <li>A rendering that is not four characters long is impossible by construction and would be a defect
 *       in this class rather than in its input; every path returns exactly
 *       {@value #RENDERED_STATUS_LENGTH} characters.</li>
 *   <li>A log line reading {@code FILE STATUS IS: NNNN} with nothing after it means the caller emitted
 *       {@link #DISPLAY_MESSAGE_PREFIX} without appending {@link #renderIoStatus04(String)}.</li>
 *   <li>A log line reading {@code FILE STATUS IS: 0023} rather than {@code FILE STATUS IS: NNNN0023}
 *       means a caller substituted the rendering into the placeholder instead of appending after it. That
 *       is a parity diff; see the note on {@link #DISPLAY_MESSAGE_PREFIX}.</li>
 *   <li>{@link #classify(String)} returning {@link #IO_ERROR} for a value such as {@code "90"} is correct
 *       and not a bug: the corpus's branch predicate is an inclusive or, so a first byte of {@code '9'}
 *       wins even when the pair is entirely numeric.</li>
 *   <li>A diagnostic record that appears to have been split in two, or that ends earlier than the message
 *       that produced it, means a raw status byte reached it. Remediation: the caller used
 *       {@link #renderIoStatus04(String)} where it needed
 *       {@link #renderIoStatus04ForDiagnostics(String)}. See the choice below.</li>
 *   <li>A diagnostic reading {@code \\u0009010} is not a defect. That is the encoded form of a tab, and it
 *       is what {@link #renderIoStatus04ForDiagnostics(String)} is for.</li>
 * </ul>
 *
 * <h2>Choosing between the two renderings</h2>
 *
 * <p>This class owns two renderings of a status and the choice between them is not stylistic:
 *
 * <ul>
 *   <li>Use {@link #renderIoStatus04(String)} when reproducing the legacy {@code DISPLAY} line, whose
 *       bytes the end to end parity gate compares against the baseline. It copies malformed bytes through
 *       unaltered, because that is what the corpus does.</li>
 *   <li>Use {@link #renderIoStatus04ForDiagnostics(String)} for every log line, exception message and
 *       stored diagnostic field. It encodes anything that is not printable ASCII, and for every status the
 *       corpus actually produces its output is character for character identical to the parity
 *       rendering.</li>
 * </ul>
 *
 * <p>If both are needed at one site, emit the parity line and carry the encoded form in the message; they
 * are not alternatives to each other and neither is a replacement for the other.
 *
 * @see #renderIoStatus04(String)
 * @see #renderIoStatus04ForDiagnostics(String)
 */
public enum FileStatus {

    /**
     * File status {@code '00'}, the normal successful completion of an input or output operation. The most
     * frequently tested literal in the corpus; the canonical site is {@code IF DALYTRAN-STATUS = '00'} at
     * {@code app/cbl/CBTRN02C.cbl:239}, the success arm of the guard idiom this type exists to replace.
     */
    SUCCESS("00"),

    /**
     * File status {@code '04'}, a successful operation reported with a secondary condition, accepted as success
     * at the statement generation file service call sites and nowhere else in the corpus. Every occurrence is
     * the form {@code IF WS-M03B-RC = '00' OR '04'} in {@code app/cbl/CBSTM03A.CBL}, at L736, L748, L771, L789,
     * L807, L862, L879, L895 and L911. The tolerance is defensive: the callee {@code app/cbl/CBSTM03B.cbl}
     * never produces this value, so the caller accepts a status it cannot in practice receive.
     */
    SUCCESS_SECONDARY("04"),

    /**
     * File status {@code '10'}, end of file. <strong>This is loop termination and not an error.</strong> The
     * canonical site is {@code IF DALYTRAN-STATUS = '10'} at {@code app/cbl/CBTRN02C.cbl:351}, whose arm makes
     * the {@code APPL-EOF} condition name declared at {@code app/cbl/CBTRN02C.cbl:144} true so that the driving
     * loop stops without reaching the abend guard.
     */
    END_OF_FILE("10"),

    /**
     * File status {@code '22'}, an attempt to add a record whose key duplicates an existing one. The corpus
     * never writes the literal; the online programs express the same condition as a CICS response code, and the
     * canonical site stacks {@code WHEN DFHRESP(DUPKEY)} and {@code WHEN DFHRESP(DUPREC)} on the user add write
     * at {@code app/cbl/COUSR01C.cbl:260-261}, answering both with one message.
     */
    DUPLICATE_KEY("22"),

    /**
     * File status {@code '23'}, record not found, or an invalid or duplicate key on a random read. All three
     * literal occurrences matter: {@code IF TCATBALF-STATUS = '00' OR '23'} at {@code app/cbl/CBTRN02C.cbl:481}
     * and {@code IF DISCGRP-STATUS = '00' OR '23'} at {@code app/cbl/CBACT04C.cbl:422} accept it as success,
     * while {@code IF DISCGRP-STATUS = '23'} at {@code app/cbl/CBACT04C.cbl:436} selects the default group
     * retry. The online programs express the same condition as {@code DFHRESP(NOTFND)}.
     */
    RECORD_NOT_FOUND("23"),

    /**
     * File status {@code '35'}, an attempt to open a file that is unavailable, most usually because it does not
     * exist. The frozen corpus never tests this condition: neither the literal {@code '35'} nor
     * {@code DFHRESP(NOTOPEN)} occurs anywhere under {@code app/}. It is carried because the status contract
     * names it and the Java layer must represent an unreachable datasource, bucket or queue, so no source line
     * is cited for it.
     */
    FILE_UNAVAILABLE("35"),

    /**
     * The {@code '9x'} family: a physical or logical input or output error, whose first byte is {@code '9'} and
     * whose second byte carries an implementation defined subcode. The guard is {@code IO-STAT1 = '9'} at
     * {@code app/cbl/CBTRN02C.cbl:716}, and the subcode expansion it selects is the reason the
     * {@code TWO-BYTES-BINARY} over {@code TWO-BYTES-ALPHA} redefinition at
     * {@code app/cbl/CBTRN02C.cbl:134-137} exists.
     */
    IO_ERROR("");

    /**
     * The exact literal that {@code app/cbl/CBTRN02C.cbl:L721} and {@code app/cbl/CBTRN02C.cbl:L725} place in
     * front of the rendered status, reproduced byte for byte at twenty characters with the stray placeholder
     * intact.
     */
    public static final String DISPLAY_MESSAGE_PREFIX = "FILE STATUS IS: NNNN";

    /**
     * The width of a COBOL file status field, two characters, fixed by {@code app/cbl/CBTRN02C.cbl:L131-L133}
     * where {@code IO-STATUS} is a group of two {@code PIC X} items, and independently by
     * {@code app/cbl/CBSTM03B.CBL:L109} where the file service return code is declared
     * {@code LK-M03B-RC PIC X(02)}.
     */
    public static final int STATUS_CODE_LENGTH = 2;

    /**
     * The width of the rendered status, four characters, fixed by {@code app/cbl/CBTRN02C.cbl:L138-L140} where
     * {@code IO-STATUS-04} is a group of {@code PIC 9} followed by {@code PIC 999}. Every value returned by
     * {@link #renderIoStatus04(String)} is exactly this long.
     */
    public static final int RENDERED_STATUS_LENGTH = 4;

    /**
     * The first byte that identifies the {@link #IO_ERROR} family, taken from the guard {@code IO-STAT1 = '9'}
     * at {@code app/cbl/CBTRN02C.cbl:L716}. The second byte of such a status is unconstrained, which is why
     * {@link #IO_ERROR} is a family rather than a value.
     */
    public static final char IO_ERROR_FIRST_BYTE = '9';

    /**
     * The character a COBOL {@code MOVE} into an alphanumeric item uses to pad on the right when the sending
     * item is shorter than the receiving one. Used by the fixed width normalisation that
     * {@link #renderIoStatus04(String)} applies before it reproduces the two display branches.
     */
    private static final char COBOL_FILL_CHARACTER = ' ';

    /**
     * Mask that reduces a Java {@code char} to its low order byte, reproducing the one byte
     * {@code MOVE IO-STAT2 TO TWO-BYTES-RIGHT} at {@code app/cbl/CBTRN02C.cbl:L719}. Because the result is
     * necessarily in the range 0 to 255 it also guarantees the three digit width of
     * {@code IO-STATUS-0403 PIC 999}, so no further truncation step is required or present.
     */
    private static final int LOW_ORDER_BYTE_MASK = 0xFF;

    /**
     * Lowest character {@link #escapeForDiagnostics(String)} passes through unaltered, the ASCII space at
     * {@code 0x20}. Every character below it is a control character, and a control character reaching a log
     * record can terminate it early or begin a forged one.
     */
    private static final char PRINTABLE_ASCII_MIN = ' ';

    /**
     * Highest character {@link #escapeForDiagnostics(String)} passes through unaltered, the ASCII tilde at
     * {@code 0x7E}. This deliberately excludes {@code DEL} at {@code 0x7F} and everything above it, so the
     * C1 control range and every high bit byte are encoded rather than emitted.
     */
    private static final char PRINTABLE_ASCII_MAX = '~';

    /**
     * The character that introduces an escape produced by {@link #escapeForDiagnostics(String)}, and which
     * is itself doubled so that the encoding is injective. See that method for why the doubling is
     * load bearing rather than cosmetic.
     */
    private static final char DIAGNOSTIC_ESCAPE_CHARACTER = '\\';

    /**
     * Spare capacity given to the buffer in {@link #escapeForDiagnostics(String)}. Sized so that the
     * common case - a well formed status of {@value #STATUS_CODE_LENGTH} characters, or its
     * {@value #RENDERED_STATUS_LENGTH} character rendering, neither of which needs any escape - never
     * reallocates, without over allocating for a value that needs no encoding at all.
     */
    private static final int ESCAPE_HEADROOM = 8;

    /**
     * Immutable index from an exact two character code to its constant, built once at class
     * initialisation from a single pass over {@link #values()} so that the codes have one and only one
     * declaration site, namely the constants above.
     *
     * <p>{@link #IO_ERROR} is excluded, because a family has no code to index. The map is created through
     * {@code Map.copyOf} and is therefore unmodifiable, and it is only ever queried by key: no code path
     * iterates it, so nothing depends on its iteration order.
     */
    private static final Map<String, FileStatus> EXACT_BY_CODE = buildExactIndex();

    /**
     * The exact two character COBOL file status this constant represents, or the empty string for
     * {@link #IO_ERROR}, which is a family and has no single code. Never {@code null}: the empty string is used
     * as the family marker so that no field, accessor or map entry in this type can ever be null.
     */
    private final String code;

    /**
     * Binds a constant to its exact two character code, or to the empty string when the constant denotes a
     * family.
     *
     * @param code the exact two character status, or the empty string for a family constant
     */
    private FileStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the exact two character COBOL file status this constant represents.
     *
     * @return the two character code for one of the six exact constants, or an empty {@link Optional} for
     * {@link #IO_ERROR}, which is a family and has no single canonical code.
     */
    public Optional<String> code() {
        return this.code.isEmpty() ? Optional.empty() : Optional.of(this.code);
    }

    /**
     * Reports whether this constant stands for one exact two character value, which is true of every constant
     * except {@link #IO_ERROR}.
     *
     * @return {@code true} when {@link #code()} yields a value, {@code false} for the family constant
     */
    public boolean isExactValue() {
        return !this.code.isEmpty();
    }

    /**
     * Reports whether this constant stands for a family of statuses matched on their first byte alone rather
     * than for one exact value. Only {@link #IO_ERROR} is such a family.
     *
     * @return {@code true} for {@link #IO_ERROR}, {@code false} for the six exact constants
     */
    public boolean isFamily() {
        return this.code.isEmpty();
    }

    /**
     * Reports whether the supplied raw file status belongs to this constant.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return {@code true} when the status belongs to this constant, {@code false} otherwise, including for
     * {@code null} and for any length other than {@value #STATUS_CODE_LENGTH}
     */
    public boolean matches(String ioStatus) {
        if (!isWellFormed(ioStatus)) {
            return false;
        }
        return this.isFamily() ? isIoErrorFamily(ioStatus) : this.code.equals(ioStatus);
    }

    /**
     * Looks up the constant whose exact two character code equals the supplied value.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return the matching exact constant, or an empty {@link Optional} when the value is {@code null}, is not
     * exactly {@value #STATUS_CODE_LENGTH} characters long, or is not one of the six exact codes.
     */
    public static Optional<FileStatus> fromCode(String ioStatus) {
        if (!isWellFormed(ioStatus)) {
            return Optional.empty();
        }
        return Optional.ofNullable(EXACT_BY_CODE.get(ioStatus));
    }

    /**
     * Classifies a raw file status without ever raising an exception, matching the six exact codes first and
     * then the {@link #IO_ERROR} family.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return the matching constant, or an empty {@link Optional} when the value is {@code null}, is not
     * exactly {@value #STATUS_CODE_LENGTH} characters long, or matches neither an exact code nor the family
     * first byte
     */
    public static Optional<FileStatus> tryClassify(String ioStatus) {
        if (!isWellFormed(ioStatus)) {
            return Optional.empty();
        }
        FileStatus exact = EXACT_BY_CODE.get(ioStatus);
        if (exact != null) {
            return Optional.of(exact);
        }
        return isIoErrorFamily(ioStatus) ? Optional.of(IO_ERROR) : Optional.empty();
    }

    /**
     * Classifies a raw file status, matching the six exact codes first and then the {@link #IO_ERROR} family,
     * and refuses anything it cannot classify.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return the matching constant, never {@code null}
     * @throws IllegalArgumentException when the value is {@code null}, is not exactly
     * {@value #STATUS_CODE_LENGTH} characters long, or matches neither an exact code nor the family first byte.
     */
    public static FileStatus classify(String ioStatus) {
        return tryClassify(ioStatus).orElseThrow(() -> new IllegalArgumentException(
                "Unrecognised COBOL FILE STATUS (IO-STATUS PIC X(02)): [" + ioStatus + "]. Recognised "
                        + "values are the exact codes '00', '04', '10', '22', '23' and '35', and the '9x' "
                        + "family identified by a first byte of '" + IO_ERROR_FIRST_BYTE + "'. The frozen "
                        + "corpus answers an unrecognised status by abending with code 999 and process "
                        + "return code 12; see app/cbl/CBTRN02C.cbl:L707-L711."));
    }

    /**
     * Renders a raw file status into the four character {@code IO-STATUS-04} form, reproducing
     * {@code 9910-DISPLAY-IO-STATUS} at {@code app/cbl/CBTRN02C.cbl:L714-L727} byte for byte.
     *
     * <p>The source paragraph, verbatim:
     *
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
     * <p><strong>Branch A</strong> is taken when the two character status is not numeric
     * <strong>or</strong> its first byte is {@value #IO_ERROR_FIRST_BYTE}. The predicate is an inclusive
     * or: it is neither an and, nor a test of non numeric alone, so an entirely numeric {@code '9x'} pair
     * still takes this branch. Character 1 is the first status byte copied through unchanged, which is why
     * the result can legitimately contain a non digit even though {@code IO-STATUS-0401} is declared
     * {@code PIC 9}; the corpus writes it through reference modification of the group, which treats the
     * receiving area as alphanumeric. Characters 2 to 4 are the second status byte expanded to three
     * decimal digits: the corpus zeroes {@code TWO-BYTES-BINARY} and then moves the raw byte into
     * {@code TWO-BYTES-RIGHT}, the low order byte of that halfword through the
     * {@code TWO-BYTES-ALPHA} redefinition at {@code app/cbl/CBTRN02C.cbl:L134-L137}, so the resulting
     * number is the unsigned value of the byte, from 0 to 255, which {@code PIC 999} always renders in
     * exactly three digits.
     *
     * <p><strong>Branch B</strong> is taken otherwise, that is when the status is numeric and its first
     * byte is not {@value #IO_ERROR_FIRST_BYTE}. The field is set to four zeros and then characters 3 and
     * 4 are overwritten with the two status characters, so the first two characters are always
     * {@code '0'}.
     *
     * <p>Worked examples, which are also the assertions the unit tests make:
     *
     * <pre>
     * "00"  -&gt;  "0000"      branch B
     * "04"  -&gt;  "0004"      branch B
     * "10"  -&gt;  "0010"      branch B
     * "22"  -&gt;  "0022"      branch B
     * "23"  -&gt;  "0023"      branch B
     * "35"  -&gt;  "0035"      branch B
     * "90"  -&gt;  "9048"      branch A, numeric yet taken because the first byte is '9'; '0' is 48
     * "9A"  -&gt;  "9065"      branch A; 'A' is 65
     * "A1"  -&gt;  "A049"      branch A, taken because the pair is not numeric; '1' is 49
     * null  -&gt;  " 032"      branch A, the pair normalises to two spaces; a space is 32
     * ""    -&gt;  " 032"      branch A, as above
     * "1"   -&gt;  "1032"      branch A, the pair normalises to "1 "
     * "023" -&gt;  "0002"      branch B, the pair normalises to "02"
     * </pre>
     *
     * <p><strong>This method is total and never throws</strong>, which is a deliberate and load bearing
     * asymmetry with the strict behaviour of {@link #classify(String)} and {@link #matches(String)}. It
     * runs on a path where something has already failed and a diagnostic line is being assembled;
     * throwing from there would destroy the root cause it exists to report, which is precisely the
     * swallowing of context that must be avoided. Malformed input is therefore reshaped rather than
     * rejected, using the very rule the receiving COBOL field applies: a {@code MOVE} into
     * {@code PIC X(02)} left justifies, pads on the right with spaces when the sender is shorter and
     * truncates on the right when it is longer. A {@code null} argument has no COBOL counterpart at all
     * and is treated as the empty sender, which pads to two spaces; that is the same value an
     * uninitialised {@code IO-STATUS} would hold, and because two spaces are not numeric it renders
     * through branch A.
     *
     * <p>On the byte values in branch A: the expansion masks the character to its low order byte, so the
     * three digits are the numeric value of that byte in the character set the value arrives in. On the
     * mainframe that set is EBCDIC, where a space is 64 rather than 32; in this target the statuses
     * originate as Java text, so the values above are the ASCII ones. The distinction can only ever affect
     * the diagnostic expansion of a non digit second byte, never any status the corpus actually produces
     * and compares, and it is recorded here rather than papered over.
     *
     * <p>The return value is <strong>always exactly {@value #RENDERED_STATUS_LENGTH} characters</strong>.
     * That is a hard contract: the end to end parity gate compares the emitted line byte for byte against
     * the legacy baseline, so a three or five character rendering is a diff.
     *
     * <p>This method renders the four characters only. It does not log, and it does not prepend
     * {@link #DISPLAY_MESSAGE_PREFIX}; see that constant for the full legacy line, which is the prefix
     * immediately followed by this rendering with no separator, and for the preserved placeholder quirk
     * that makes the line for status {@code '23'} read {@code FILE STATUS IS: NNNN0023}.
     *
     * <p><strong>This rendering is for the parity line, not for a log message.</strong> Branch A copies the
     * first status byte through unaltered and branch B copies both, so a malformed status containing a tab,
     * a newline or a high bit byte is reproduced verbatim - which is exactly right for a byte comparison
     * against the baseline and exactly wrong for a diagnostic record, where such a byte can truncate the
     * record or forge a second one. Any log line, exception message or stored diagnostic field must use
     * {@link #renderIoStatus04ForDiagnostics(String)} instead; for every status the corpus actually
     * produces the two return identical text, so the safe choice costs nothing.
     *
     * <p>Pure function; no side effect; deterministic; independent of the platform default locale, charset
     * and time zone.
     *
     * @param ioStatus the raw file status, ordinarily exactly {@value #STATUS_CODE_LENGTH} characters, and
     * tolerated when {@code null}, shorter or longer, in which case it is first normalised to exactly
     * {@value #STATUS_CODE_LENGTH} characters
     * @return the four character rendering, never {@code null} and always exactly
     *         {@value #RENDERED_STATUS_LENGTH} characters long
     * @see #renderIoStatus04ForDiagnostics(String)
     */
    public static String renderIoStatus04(String ioStatus) {
        String field = normaliseToStatusField(ioStatus);
        char stat1 = field.charAt(0);
        char stat2 = field.charAt(1);
        if (!isCobolNumeric(field) || stat1 == IO_ERROR_FIRST_BYTE) {
            return String.format(Locale.ROOT, "%c%03d", stat1, stat2 & LOW_ORDER_BYTE_MASK);
        }
        return String.format(Locale.ROOT, "00%c%c", stat1, stat2);
    }

    /**
     * Renders the status for a <strong>log line or an exception message</strong>, encoding anything that
     * is not printable ASCII.
     *
     * <p>This is the log safe counterpart of {@link #renderIoStatus04(String)} and the two are
     * deliberately separate methods rather than one method with a flag. They answer different questions
     * and are compared against different things:
     *
     * <ul>
     *   <li>{@link #renderIoStatus04(String)} answers <em>what would the legacy program have displayed</em>.
     *       Its output is a byte for byte parity contract, so it copies the first status byte through
     *       unaltered - including a tab, a newline or a high bit byte - because that is what
     *       {@code MOVE IO-STAT1 TO IO-STATUS-0401} at {@code app/cbl/CBTRN02C.cbl:L718} does. It must
     *       never be changed.</li>
     *   <li>This method answers <em>what may safely be written into a diagnostic record</em>. A status is
     *       not always well formed: it can arrive from a store or adapter layer that this corpus does not
     *       control, and a control character reaching a log can terminate the record early, forge a second
     *       record, or drive a terminal escape sequence. Encoding removes that possibility.</li>
     * </ul>
     *
     * <p>Worked examples, showing that the encoding is a <strong>no operation for every status the corpus
     * actually produces</strong> and differs only for malformed input:
     *
     * <pre>
     * "00"    -&gt;  "0000"              identical to the parity rendering
     * "23"    -&gt;  "0023"              identical
     * "9A"    -&gt;  "9065"              identical; the expansion is already ASCII digits
     * "\t\n"  -&gt;  "\\u0009010"        the parity rendering is "\t010", a raw tab in a log line
     * "\u00ff\u00ff" -&gt; "\\u00ff255"  the parity rendering starts with a raw 0xFF byte
     * null    -&gt;  " 032"              identical; a space is printable
     * </pre>
     *
     * <p>Because the corpus statuses are {@code "00"}, {@code "04"}, {@code "10"}, {@code "22"},
     * {@code "23"}, {@code "35"} and the {@code '9'} family, every legitimate value renders identically
     * through both methods. The two therefore diverge only where divergence is the point.
     *
     * <p>Total and never throws, for the same reason {@link #renderIoStatus04(String)} is: it runs where
     * something has already failed, and throwing would destroy the root cause it exists to report.
     *
     * <p>Pure function; no side effect; deterministic; independent of the platform default locale, charset
     * and time zone. This method does not log - it renders text for a caller that does.
     *
     * @param ioStatus the raw file status, tolerated when {@code null}, shorter or longer
     * @return the encoded rendering, never {@code null}, containing printable ASCII only. Exactly
     *         {@value #RENDERED_STATUS_LENGTH} characters when the status is well formed, and longer only
     *         when a byte had to be encoded
     */
    public static String renderIoStatus04ForDiagnostics(String ioStatus) {
        return escapeForDiagnostics(renderIoStatus04(ioStatus));
    }

    /**
     * Encodes every character that is not printable ASCII, so that untrusted text cannot forge or corrupt
     * a diagnostic record.
     *
     * <p>This is the one place the encoding rule is defined, so that no caller invents a second one that
     * drifts from it. The rule is deliberately an allow list rather than a block list of known offenders:
     * a character passes only when it is in the inclusive range {@code 0x20} to {@code 0x7E}, and
     * everything else - control characters, the {@code DEL} byte, the C1 range, high bit bytes and every
     * non ASCII code point - is replaced by a {@code \\uXXXX} escape of four lowercase hexadecimal digits.
     * An allow list cannot be outflanked by a character nobody thought to block.
     *
     * <p>The backslash is itself encoded, as {@code \\\\}. That is not decoration: without it a value
     * containing the literal seven characters {@code \\u000a} would render indistinguishably from a real
     * newline that this method had encoded, which is precisely the ambiguity an attacker would exploit to
     * make a forged record look like a sanitised one. Encoding the backslash makes the mapping injective,
     * so the original value can always be recovered by inspection and never confused with another.
     *
     * <p>Nothing is trimmed, folded, collapsed or truncated: the value's length and content remain fully
     * determinable from the output. This is an encoding, not a filter, so no information is lost and no
     * root cause is obscured - which is the whole reason a diagnostic exists.
     *
     * <p>Pure function; no side effect; deterministic; consults no locale, charset or clock. Total: it
     * never throws, and a {@code null} argument yields the empty string rather than the four characters
     * {@code null}, which would otherwise become indistinguishable from a status that literally read
     * {@code "nu"}.
     *
     * @param value the text to encode, which may be {@code null}, empty, or of any length
     * @return the encoded text, never {@code null} and containing printable ASCII only
     */
    public static String escapeForDiagnostics(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(value.length() + ESCAPE_HEADROOM);
        for (int position = 0; position < value.length(); position++) {
            char character = value.charAt(position);
            if (character == DIAGNOSTIC_ESCAPE_CHARACTER) {
                encoded.append(DIAGNOSTIC_ESCAPE_CHARACTER).append(DIAGNOSTIC_ESCAPE_CHARACTER);
            } else if (character < PRINTABLE_ASCII_MIN || character > PRINTABLE_ASCII_MAX) {
                encoded.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
                encoded.append(character);
            }
        }
        return encoded.toString();
    }

    /**
     * Builds the immutable exact code index from a single pass over the constants, so that the codes are
     * declared in exactly one place, namely the enum constants themselves.
     *
     * @return an unmodifiable map from each exact two character code to its constant
     */
    private static Map<String, FileStatus> buildExactIndex() {
        Map<String, FileStatus> index = new LinkedHashMap<>();
        for (FileStatus status : values()) {
            if (status.isExactValue()) {
                index.put(status.code, status);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * Reports whether a raw status is already exactly {@value #STATUS_CODE_LENGTH} characters long and so may
     * be classified without being reshaped.
     *
     * @param ioStatus the raw status, which may be {@code null}
     * @return {@code true} only when the value is non null and of exactly the status width
     */
    private static boolean isWellFormed(String ioStatus) {
        return ioStatus != null && ioStatus.length() == STATUS_CODE_LENGTH;
    }

    /**
     * Reshapes an arbitrary value into the two character field that {@code IO-STATUS} declares, applying the
     * rule a COBOL {@code MOVE} into {@code PIC X(02)} applies: left justify, pad on the right with spaces,
     * truncate on the right.
     *
     * @param ioStatus the raw status, which may be {@code null}, shorter or longer than the field
     * @return a string of exactly {@value #STATUS_CODE_LENGTH} characters, never {@code null}
     */
    private static String normaliseToStatusField(String ioStatus) {
        String sender = ioStatus == null ? "" : ioStatus;
        if (sender.length() == STATUS_CODE_LENGTH) {
            return sender;
        }
        if (sender.length() > STATUS_CODE_LENGTH) {
            return sender.substring(0, STATUS_CODE_LENGTH);
        }
        StringBuilder receiver = new StringBuilder(STATUS_CODE_LENGTH).append(sender);
        while (receiver.length() < STATUS_CODE_LENGTH) {
            receiver.append(COBOL_FILL_CHARACTER);
        }
        return receiver.toString();
    }

    /**
     * Applies the COBOL {@code NUMERIC} class test to an alphanumeric item: the item is numeric when every one
     * of its characters is a decimal digit. This is the {@code IO-STATUS NOT NUMERIC} half of the branch
     * predicate at {@code app/cbl/CBTRN02C.cbl:L715}.
     *
     * @param statusField a value of exactly {@value #STATUS_CODE_LENGTH} characters, never {@code null}
     * @return {@code true} when every character is an ASCII decimal digit
     */
    private static boolean isCobolNumeric(String statusField) {
        for (int position = 0; position < statusField.length(); position++) {
            char character = statusField.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the {@code IO-STAT1 = '9'} half of the branch predicate at {@code app/cbl/CBTRN02C.cbl:L716},
     * which is also the whole of the {@link #IO_ERROR} family test. The second byte is deliberately not
     * examined, because in the corpus it carries an implementation defined subcode and is unconstrained.
     *
     * @param statusField a value of exactly {@value #STATUS_CODE_LENGTH} characters, never {@code null}
     * @return {@code true} when the first character is {@value #IO_ERROR_FIRST_BYTE}
     */
    private static boolean isIoErrorFamily(String statusField) {
        return statusField.charAt(0) == IO_ERROR_FIRST_BYTE;
    }
}
