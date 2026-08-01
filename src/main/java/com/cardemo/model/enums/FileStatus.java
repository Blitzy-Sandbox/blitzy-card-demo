/*
 * ******************************************************************
 * Program     : FileStatus.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 enumeration
 * Function    : Typed COBOL FILE STATUS values and the four-character
 *               IO-STATUS-04 rendering.
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
 * Typed representation of the COBOL {@code FILE STATUS} values that the CardDemo batch corpus tests,
 * together with the byte exact four character {@code IO-STATUS-04} rendering that the corpus writes to
 * SYSOUT whenever an input or output guard fails.
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
 * {@code failOnWarning}, so an unused import, a raw type, a switch fall through or a missing
 * {@code serialVersionUID} is a build failure rather than a warning. Build with
 * {@code mvn -B clean compile}, run the unit suite with {@code mvn -B clean test} and gate coverage with
 * {@code mvn -B verify}, which enforces an eighty percent line floor. The type adds no dependency, needs
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
 * </ul>
 *
 * @see #renderIoStatus04(String)
 */
public enum FileStatus {

    /**
     * File status {@code '00'}, the normal successful completion of an input or output operation.
     *
     * <p>Provenance: 88 literal occurrences of {@code '00'} in {@code app/cbl}, which makes it the most
     * frequently tested literal in the corpus. The canonical site is {@code app/cbl/CBTRN02C.cbl:L239},
     * {@code IF DALYTRAN-STATUS = '00'}, the success arm of the guard idiom quoted on this type. The same
     * comparison opens the lenient read guard at {@code app/cbl/CBTRN02C.cbl:L481}.
     *
     * <p>This constant asserts only that the operation reported normal completion. Whether normal
     * completion is what a particular call site wanted is not decided here.
     */
    SUCCESS("00"),

    /**
     * File status {@code '04'}, a successful operation reported with a secondary condition, accepted as
     * success at the statement generation file service call sites and nowhere else in the corpus.
     *
     * <p>Provenance: 9 literal occurrences, all of them in {@code app/cbl/CBSTM03A.CBL}, in the form
     * {@code IF WS-M03B-RC = '00' OR '04'} at L736, L748, L771, L789, L807, L862, L879, L895 and L911.
     * L736 and L748 are the open and the read of {@code 8100-TRNXFILE-OPEN}, whose paragraph begins at
     * L730 and whose else branch displays {@code 'ERROR OPENING TRNXFILE'} and abends at L739 to L741.
     *
     * <p>Two properties of this value are worth stating because they are easy to over generalise.
     * <strong>First, the tolerance is site conditional.</strong> The same program tests
     * {@code EVALUATE WS-M03B-RC} at L353, L379, L403 and L837 and there accepts {@code '00'} alone.
     * <strong>Second, the tolerance is defensive.</strong> The literal {@code '04'} does not appear even
     * once in the callee {@code app/cbl/CBSTM03B.CBL}, which simply copies each file status into
     * {@code LK-M03B-RC PIC X(02)}, declared at {@code app/cbl/CBSTM03B.CBL:L109} and assigned at L152,
     * L176, L201 and L226, so the caller is guarding against a value its callee never produces.
     *
     * <p>Because the tolerance varies by site, this constant carries no notion of being acceptable. The
     * caller decides, which is precisely why no {@code isSuccess} convenience predicate exists on this
     * type.
     */
    SUCCESS_SECONDARY("04"),

    /**
     * File status {@code '10'}, end of file. <strong>This is loop termination and not an error.</strong>
     *
     * <p>Provenance: 11 literal occurrences of {@code '10'} in {@code app/cbl}. The canonical site is
     * {@code app/cbl/CBTRN02C.cbl:L351}, {@code IF DALYTRAN-STATUS = '10'}, whose arm moves 16 into
     * {@code APPL-RESULT} at L352 so that the {@code APPL-EOF} condition name declared at
     * {@code app/cbl/CBTRN02C.cbl:L144} becomes true and the driving loop stops without reaching the
     * abend guard. The remaining sites are {@code app/cbl/CBTRN01C.cbl:L207},
     * {@code app/cbl/CBACT01C.cbl:L98}, {@code app/cbl/CBACT02C.cbl:L98},
     * {@code app/cbl/CBACT03C.cbl:L98}, {@code app/cbl/CBACT04C.cbl:L330},
     * {@code app/cbl/CBCUS01C.cbl:L98}, {@code app/cbl/CBTRN03C.cbl:L225},
     * {@code app/cbl/CBTRN03C.cbl:L254}, {@code app/cbl/CBSTM03A.CBL:L356} and
     * {@code app/cbl/CBSTM03A.CBL:L841}.
     *
     * <p>Treating this value as a failure is the single most damaging misreading of the corpus available,
     * because it would turn every normal end of input into an abend. It is nevertheless still the caller
     * that decides, since a read that reports end of file where a record was mandatory is a different
     * matter from a driving loop reaching the end of its input.
     */
    END_OF_FILE("10"),

    /**
     * File status {@code '22'}, an attempt to add a record whose key duplicates an existing one.
     *
     * <p><strong>Provenance, stated precisely because the obvious citation does not exist.</strong> There
     * are <em>zero</em> literal occurrences of {@code '22'} anywhere in {@code app/cbl}. The batch
     * programs never test it, because they either write to sequential output or accept a not found status
     * on the upsert path. The value is grounded instead in the online programs, which run under the
     * transaction monitor and test the equivalent response codes rather than a file status. Those
     * response codes are real, and these are their lines:
     * <ul>
     *   <li>{@code app/cbl/COUSR01C.cbl:L260-L261}, the canonical site, stacks
     *       {@code WHEN DFHRESP(DUPKEY)} and {@code WHEN DFHRESP(DUPREC)} on the user add write and
     *       answers both with the message {@code 'User ID already exist...'} at L263.</li>
     *   <li>{@code app/cbl/COTRN02C.cbl:L735-L736} stacks the same two and answers with
     *       {@code 'Tran ID already exist...'} at L738.</li>
     *   <li>{@code app/cbl/COBIL00C.cbl:L533-L534} stacks the same two on the bill payment write.</li>
     *   <li>{@code app/cbl/COCRDLIC.cbl:L1158}, {@code app/cbl/COCRDLIC.cbl:L1209},
     *       {@code app/cbl/COCRDLIC.cbl:L1306} and {@code app/cbl/COCRDLIC.cbl:L1334} each carry
     *       {@code WHEN DFHRESP(DUPREC)}.</li>
     * </ul>
     * The census over {@code app/cbl} is {@code DFHRESP(DUPREC)} 7 sites and {@code DFHRESP(DUPKEY)} 3
     * sites. No literal {@code '22'} citation is offered anywhere in this file, because none would be
     * true.
     */
    DUPLICATE_KEY("22"),

    /**
     * File status {@code '23'}, record not found, or an invalid or duplicate key on a random read.
     *
     * <p>Provenance: exactly 3 literal occurrences in {@code app/cbl}, and all three matter.
     * {@code app/cbl/CBTRN02C.cbl:L481} reads {@code IF TCATBALF-STATUS = '00'  OR '23'};
     * {@code app/cbl/CBACT04C.cbl:L422} reads {@code IF DISCGRP-STATUS  = '00'  OR '23'}; and
     * {@code app/cbl/CBACT04C.cbl:L436} reads {@code IF DISCGRP-STATUS  = '23'}. Two of the three are
     * leniency guards and the third is the specific test that triggers the default disclosure group
     * substitution. The online programs express the same condition as {@code DFHRESP(NOTFND)}, which
     * occurs 23 times in {@code app/cbl}.
     *
     * <p>The consequence for the caller is stated on this type under the heading about the three lenient
     * sites: at those sites this value is an accepted control path, and everywhere else it is an error
     * that reaches the abend guard. This constant does not encode which case applies, because that is
     * exactly the decision reserved for {@code com.cardemo.service.shared.FileStatusMapper}.
     */
    RECORD_NOT_FOUND("23"),

    /**
     * File status {@code '35'}, an attempt to open a file that is unavailable, most usually because it
     * does not exist.
     *
     * <p><strong>Not available.</strong> This value has no grounding whatsoever in the frozen corpus.
     * There are zero literal occurrences of {@code '35'} anywhere under {@code app/}, and the response
     * code the online programs would use for the same condition, {@code DFHRESP(NOTOPEN)}, is confirmed
     * absent as well: a search of the entire {@code app/} tree for {@code NOTOPEN} returns no results.
     * For completeness, the full response code census over {@code app/cbl} is {@code DFHRESP(NORMAL)} 43,
     * {@code DFHRESP(NOTFND)} 23, {@code DFHRESP(ENDFILE)} 8, {@code DFHRESP(DUPREC)} 7,
     * {@code DFHRESP(DUPKEY)} 3 and {@code DFHRESP(NOTOPEN)} 0. No {@code app/} line citation is offered
     * for this constant, because inventing one would be a false citation.
     *
     * <p>The value is therefore <strong>specification derived</strong>. It is present for two stated
     * reasons: it completes the six family status taxonomy the migration specification mandates, and it
     * gives {@code com.cardemo.exception.FileUnavailableException} a typed antecedent so that the
     * exception hierarchy is not the only place the condition is named.
     *
     * <p><strong>Finding, severity Medium.</strong> A constant that no source line justifies is a
     * traceability gap rather than a correctness defect: nothing in the corpus can produce it, so no
     * parity comparison can disagree about it, but equally no test derived from the corpus can prove it
     * behaves correctly. What would be needed to close the gap is precisely one of two artefacts, neither
     * of which exists at commit {@code 7756d89}: a literal {@code '35'} comparison in a COBOL program, or
     * a {@code DFHRESP(NOTOPEN)} handler in an online program. Remediation, should the gap ever need
     * closing: re-run the two searches against the frozen tree and, if they still return nothing, keep
     * this constant documented as specification derived rather than promoting it to a cited value.
     */
    FILE_UNAVAILABLE("35"),

    /**
     * The {@code '9x'} family: a physical or logical input or output error, whose first byte is
     * {@code '9'} and whose second byte carries an implementation defined subcode.
     *
     * <p><strong>This constant is a family and not an exact value.</strong> It is the only such constant
     * on this type. {@link #code()} returns an empty {@link Optional} for it, {@link #isFamily()} returns
     * {@code true}, {@link #isExactValue()} returns {@code false}, and {@link #fromCode(String)} never
     * returns it. Nothing may assume that it can be converted back into a two character string, because
     * the corpus itself never writes one: there is no literal {@code '9x'} pair anywhere.
     *
     * <p>Provenance: the corpus tests the first byte alone. The direct evidence is the guard
     * {@code IO-STAT1 = '9'} at {@code app/cbl/CBTRN02C.cbl:L716}, replicated verbatim at eight sites in
     * total, the remaining seven being {@code app/cbl/CBTRN01C.cbl:L478},
     * {@code app/cbl/CBTRN03C.cbl:L635}, {@code app/cbl/CBACT01C.cbl:L178},
     * {@code app/cbl/CBACT02C.cbl:L163}, {@code app/cbl/CBACT03C.cbl:L163},
     * {@code app/cbl/CBACT04C.cbl:L637} and {@code app/cbl/CBCUS01C.cbl:L163}. Eight sites across eight
     * programs is every program that contains the renderer, which makes the idiom universal rather than
     * incidental.
     *
     * <p>The reason the corpus singles this family out is the rendering, not the classification. A
     * {@code '9x'} status carries a binary subcode in its second byte, so the byte cannot be displayed
     * directly and is instead expanded into three decimal digits. That expansion is
     * {@link #renderIoStatus04(String)} branch A, and it is the whole purpose of the
     * {@code TWO-BYTES-BINARY} and {@code TWO-BYTES-ALPHA} redefinition declared at
     * {@code app/cbl/CBTRN02C.cbl:L134-L137}.
     */
    IO_ERROR("");

    /**
     * The exact literal that {@code app/cbl/CBTRN02C.cbl:L721} and {@code app/cbl/CBTRN02C.cbl:L725}
     * place in front of the rendered status, reproduced byte for byte at twenty characters with the
     * stray placeholder intact.
     *
     * <p><strong>Preserved legacy quirk. Do not repair this.</strong> COBOL's
     * {@code DISPLAY 'literal' identifier} concatenates its operands with no separator whatsoever. The
     * literal in the corpus is {@code FILE STATUS IS: NNNN}, which already contains the placeholder text
     * {@code NNNN}, and the four real characters of {@code IO-STATUS-04} are appended <em>after</em> it
     * rather than substituted into it. The line the legacy system emits for status {@code '23'} is
     * therefore, exactly:
     *
     * <pre>
     * FILE STATUS IS: NNNN0023
     * </pre>
     *
     * and <em>not</em> {@code FILE STATUS IS: 0023}. The stray {@code NNNN} is a placeholder the original
     * author left in the message while also appending the real value. The end to end parity gate compares
     * log output byte for byte against the legacy baseline, so removing the placeholder, inserting a
     * separator or substituting the value into the placeholder each produce a diff. All three are
     * forbidden.
     *
     * <p><strong>How to use this constant.</strong> The full legacy line is this prefix immediately
     * followed by {@link #renderIoStatus04(String)}, with nothing between them:
     *
     * <pre>
     * String line = FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04(status);
     * </pre>
     *
     * <p><strong>Warning against double prefixing.</strong> The caller must not add any file status
     * wording of its own. This constant already contains the whole of it, including the colon, the single
     * following space and the placeholder. Prepending another {@code FILE STATUS} phrase, wrapping the
     * value in a structured logging field whose name repeats the phrase, or passing the prefix through a
     * message formatter that trims or collapses whitespace will all break the byte comparison.
     *
     * <p>This constant exists so that the byte exact text lives in exactly one place in the codebase.
     * There is deliberately no method here that assembles or emits the line, because emitting it is
     * logging and this type does not log.
     */
    public static final String DISPLAY_MESSAGE_PREFIX = "FILE STATUS IS: NNNN";

    /**
     * The width of a COBOL file status field, two characters, fixed by
     * {@code app/cbl/CBTRN02C.cbl:L131-L133} where {@code IO-STATUS} is a group of two
     * {@code PIC X} items, and independently by {@code app/cbl/CBSTM03B.CBL:L109} where the file service
     * return code is declared {@code LK-M03B-RC PIC X(02)}.
     */
    public static final int STATUS_CODE_LENGTH = 2;

    /**
     * The width of the rendered status, four characters, fixed by {@code app/cbl/CBTRN02C.cbl:L138-L140}
     * where {@code IO-STATUS-04} is a group of {@code PIC 9} followed by {@code PIC 999}. Every value
     * returned by {@link #renderIoStatus04(String)} is exactly this long.
     */
    public static final int RENDERED_STATUS_LENGTH = 4;

    /**
     * The first byte that identifies the {@link #IO_ERROR} family, taken from the guard
     * {@code IO-STAT1 = '9'} at {@code app/cbl/CBTRN02C.cbl:L716}. The second byte of such a status is
     * unconstrained, which is why {@link #IO_ERROR} is a family rather than a value.
     */
    public static final char IO_ERROR_FIRST_BYTE = '9';

    /**
     * The character a COBOL {@code MOVE} into an alphanumeric item uses to pad on the right when the
     * sending item is shorter than the receiving one. Used by the fixed width normalisation that
     * {@link #renderIoStatus04(String)} applies before it reproduces the two display branches.
     */
    private static final char COBOL_FILL_CHARACTER = ' ';

    /**
     * Mask that reduces a Java {@code char} to its low order byte, reproducing the one byte
     * {@code MOVE IO-STAT2 TO TWO-BYTES-RIGHT} at {@code app/cbl/CBTRN02C.cbl:L719}. Because the result
     * is necessarily in the range 0 to 255 it also guarantees the three digit width of
     * {@code IO-STATUS-0403 PIC 999}, so no further truncation step is required or present.
     */
    private static final int LOW_ORDER_BYTE_MASK = 0xFF;

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
     * {@link #IO_ERROR}, which is a family and has no single code. Never {@code null}: the empty string is
     * used as the family marker so that no field, accessor or map entry in this type can ever be null.
     */
    private final String code;

    /**
     * Binds a constant to its exact two character code, or to the empty string when the constant denotes
     * a family.
     *
     * <p>The empty string is written as a literal at the {@link #IO_ERROR} declaration rather than being
     * referenced through a named constant, because an enum constant's arguments may not refer to a static
     * field of the enum being declared.
     *
     * @param code the exact two character status, or the empty string for a family constant
     */
    private FileStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the exact two character COBOL file status this constant represents.
     *
     * <p>Pure function; no side effect, no state read or written beyond this constant's own immutable
     * field.
     *
     * @return the two character code for one of the six exact constants, or an empty {@link Optional} for
     *         {@link #IO_ERROR}, which is a family and has no single canonical code. Never {@code null}
     */
    public Optional<String> code() {
        return this.code.isEmpty() ? Optional.empty() : Optional.of(this.code);
    }

    /**
     * Reports whether this constant stands for one exact two character value, which is true of every
     * constant except {@link #IO_ERROR}.
     *
     * <p>Pure function; no side effect.
     *
     * @return {@code true} when {@link #code()} yields a value, {@code false} for the family constant
     */
    public boolean isExactValue() {
        return !this.code.isEmpty();
    }

    /**
     * Reports whether this constant stands for a family of statuses matched on their first byte alone
     * rather than for one exact value. Only {@link #IO_ERROR} is such a family.
     *
     * <p>Pure function; no side effect.
     *
     * @return {@code true} for {@link #IO_ERROR}, {@code false} for the six exact constants
     */
    public boolean isFamily() {
        return this.code.isEmpty();
    }

    /**
     * Reports whether the supplied raw file status belongs to this constant.
     *
     * <p>For the six exact constants the test is equality against the two character code. For
     * {@link #IO_ERROR} the test is the corpus's own test, namely that the first byte equals
     * {@value #IO_ERROR_FIRST_BYTE}, exactly as {@code IO-STAT1 = '9'} at
     * {@code app/cbl/CBTRN02C.cbl:L716} does; the second byte is unconstrained.
     *
     * <p><strong>This method is strict and does not normalise its argument.</strong> Anything that is not
     * exactly {@value #STATUS_CODE_LENGTH} characters long, including {@code null}, is a miss rather than
     * a value to be coerced into shape, because silently reshaping an untrusted value before classifying
     * it is how a malformed status ends up misclassified as a success. The deliberately different, total
     * treatment applied by {@link #renderIoStatus04(String)} is explained there.
     *
     * <p>Pure function; no side effect.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return {@code true} when the status belongs to this constant, {@code false} otherwise, including
     *         for {@code null} and for any length other than {@value #STATUS_CODE_LENGTH}
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
     * <p>This is an exact value lookup and nothing more. It <strong>never</strong> returns
     * {@link #IO_ERROR}, because that constant is a family with no code to match; a {@code '9x'} status
     * therefore yields an empty result here even though it is perfectly classifiable. Use
     * {@link #classify(String)} or {@link #tryClassify(String)} when family matching is wanted.
     *
     * <p>The lookup is a single constant time query against an immutable map built once at class
     * initialisation. Pure function; no side effect.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return the matching exact constant, or an empty {@link Optional} when the value is {@code null},
     *         is not exactly {@value #STATUS_CODE_LENGTH} characters long, or is not one of the six exact
     *         codes. Never {@code null}
     */
    public static Optional<FileStatus> fromCode(String ioStatus) {
        if (!isWellFormed(ioStatus)) {
            return Optional.empty();
        }
        return Optional.ofNullable(EXACT_BY_CODE.get(ioStatus));
    }

    /**
     * Classifies a raw file status without ever raising an exception, matching the six exact codes first
     * and then the {@link #IO_ERROR} family.
     *
     * <p><strong>An empty result is meaningful and is not an error signal.</strong> It corresponds exactly
     * to the else branch of the corpus's guard idiom and to the {@code WHEN OTHER} arm of its
     * {@code EVALUATE} form: a status the legacy programs do not recognise, which they answer by
     * abending with code 999 and process return code 12 as described on this type. This method reports
     * that condition and deliberately does not act on it, because acting on it is the caller's decision.
     *
     * <p>Prefer this method wherever the input is untrusted or where an unrecognised value is an expected
     * outcome to be handled, and prefer {@link #classify(String)} where an unrecognised value would be a
     * programming error. Pure function; no side effect; never returns {@code null}.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return the matching constant, or an empty {@link Optional} when the value is {@code null}, is not
     *         exactly {@value #STATUS_CODE_LENGTH} characters long, or matches neither an exact code nor
     *         the family first byte
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
     * Classifies a raw file status, matching the six exact codes first and then the {@link #IO_ERROR}
     * family, and refuses anything it cannot classify.
     *
     * <p>Note that a numeric pair beginning with {@value #IO_ERROR_FIRST_BYTE}, such as {@code "90"},
     * classifies as {@link #IO_ERROR} rather than as an unrecognised value. That follows the corpus, whose
     * branch predicate at {@code app/cbl/CBTRN02C.cbl:L715-L716} is an inclusive or between the value not
     * being numeric and its first byte being {@code '9'}.
     *
     * <p>This method never returns {@code null} and never silently absorbs an unusable input. Pure
     * function; no side effect beyond the thrown exception's construction.
     *
     * @param ioStatus the raw two character file status, which may be {@code null}
     * @return the matching constant, never {@code null}
     * @throws IllegalArgumentException when the value is {@code null}, is not exactly
     *         {@value #STATUS_CODE_LENGTH} characters long, or matches neither an exact code nor the
     *         family first byte. The message quotes the offending value and names every recognised form
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
     * <p>Pure function; no side effect; deterministic; independent of the platform default locale, charset
     * and time zone.
     *
     * @param ioStatus the raw file status, ordinarily exactly {@value #STATUS_CODE_LENGTH} characters, and
     *                 tolerated when {@code null}, shorter or longer as described above
     * @return the four character rendering, never {@code null} and always exactly
     *         {@value #RENDERED_STATUS_LENGTH} characters long
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
     * Builds the immutable exact code index from a single pass over the constants, so that the codes are
     * declared in exactly one place, namely the enum constants themselves.
     *
     * <p>{@link #values()} is called once here, at class initialisation, and never from a loop or from a
     * request path. The family constant is skipped because it has no code. The returned map is
     * unmodifiable and is only ever queried by key.
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
     * Reports whether a raw status is already exactly {@value #STATUS_CODE_LENGTH} characters long and so
     * may be classified without being reshaped.
     *
     * @param ioStatus the raw status, which may be {@code null}
     * @return {@code true} only when the value is non null and of exactly the status width
     */
    private static boolean isWellFormed(String ioStatus) {
        return ioStatus != null && ioStatus.length() == STATUS_CODE_LENGTH;
    }

    /**
     * Reshapes an arbitrary value into the two character field that {@code IO-STATUS} declares, applying
     * the rule a COBOL {@code MOVE} into {@code PIC X(02)} applies: left justify, pad on the right with
     * spaces, truncate on the right.
     *
     * <p>A {@code null} argument is treated as an empty sender and therefore pads to two spaces. This is
     * used only by {@link #renderIoStatus04(String)}, which must be total; the classification methods
     * deliberately reject malformed input instead of reshaping it.
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
     * Applies the COBOL {@code NUMERIC} class test to an alphanumeric item: the item is numeric when every
     * one of its characters is a decimal digit. This is the {@code IO-STATUS NOT NUMERIC} half of the
     * branch predicate at {@code app/cbl/CBTRN02C.cbl:L715}.
     *
     * <p>The comparison is written against the characters {@code '0'} and {@code '9'} on purpose.
     * {@code java.lang.Character#isDigit} would also accept non ASCII decimal digits, so a status
     * containing one would be routed through the wrong branch and rendered differently from the legacy
     * baseline.
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
     * Applies the {@code IO-STAT1 = '9'} half of the branch predicate at
     * {@code app/cbl/CBTRN02C.cbl:L716}, which is also the whole of the {@link #IO_ERROR} family test.
     * The second byte is deliberately not examined, because in the corpus it carries an implementation
     * defined subcode and is unconstrained.
     *
     * @param statusField a value of exactly {@value #STATUS_CODE_LENGTH} characters, never {@code null}
     * @return {@code true} when the first character is {@value #IO_ERROR_FIRST_BYTE}
     */
    private static boolean isIoErrorFamily(String statusField) {
        return statusField.charAt(0) == IO_ERROR_FIRST_BYTE;
    }
}
