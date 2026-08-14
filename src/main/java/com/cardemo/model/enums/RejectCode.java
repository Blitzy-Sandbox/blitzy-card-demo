/*
 * ******************************************************************
 * Program     : RejectCode.java
 * Application : CardDemo
 * Type        : Java 25 / Spring Boot 3.5.11 enumeration
 * Function    : The five daily-posting reject outcomes and their verbatim
 *               descriptions.
 * Source      : app/cbl/CBTRN02C.cbl:L385-L419,L556-L558 @ 7756d89
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

import java.util.Locale;
import java.util.Optional;

/**
 * The five validation reject outcomes of the daily transaction posting program {@code app/cbl/CBTRN02C.cbl},
 * each pairing the numeric reason code with the <em>verbatim</em> description literal that the legacy program
 * moves into the reject record trailer.
 *
 * <p>This enumeration is the single source of that text for the whole Java target. The reject file it feeds is
 * compared byte for byte against the legacy baseline, so the five literals below are transcribed character for
 * character from the COBOL and must never be re-cased, re-worded, re-punctuated, pluralised, expanded or
 * grammatically "corrected".
 *
 * <h2>Reject codes are business outcomes, never exceptions</h2>
 *
 * <p><strong>A reject is a normal, expected result of validating an input record, not a failure of the
 * program.</strong> Consequently this type deliberately does <em>not</em> extend {@code Throwable}, does
 * not reference the project's typed exception hierarchy in any form, and exposes no method that converts
 * a constant into something throwable. In the COBOL the reject path is an ordinary {@code ELSE} branch
 * that increments a counter and writes a record ({@code app/cbl/CBTRN02C.cbl}:L213-L215); it is not the
 * abend path. The Java batch layer maps this enum onto a
 * {@code org.springframework.batch.core.ExitStatus} - the mapping runs in that direction only, which is
 * why no Spring or Spring Batch type is imported here.
 *
 * <p>The single exception type this enum does use is the JDK's {@code IllegalArgumentException}, and it
 * is used solely to reject a caller-supplied integer that is not a valid reason code. That is input
 * validation of this type's own arguments, not the propagation of a business outcome.
 *
 * <h2>Record geometry: 430 = 350 + 80, and 80 = 4 + 76</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl}:L176-L182 declares, verbatim:
 *
 * <pre>
 * 01 REJECT-RECORD.                                     &lt;- L176
 *    05 REJECT-TRAN-DATA          PIC X(350).           &lt;- L177
 *    05 VALIDATION-TRAILER        PIC X(80).            &lt;- L178
 *
 * 01 WS-VALIDATION-TRAILER.                             &lt;- L180
 *    05 WS-VALIDATION-FAIL-REASON      PIC 9(04).       &lt;- L181
 *    05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).       &lt;- L182
 * </pre>
 *
 * <p>So a reject record is exactly {@code 350 + 80 = 430} bytes, corroborated independently by the
 * declared record length on the reject dataset - {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at
 * {@code app/jcl/POSTTRAN.jcl}:L36 - and the trailer is exactly {@code 4 + 76 = 80} bytes. The 350-byte
 * data image is the {@code DALYTRAN-RECORD} layout of {@code app/cpy/CVTRA06Y.cpy}, whose header states
 * {@code RECLN = 350}.
 *
 * <p><strong>This type renders the 80-byte trailer only.</strong> It never assembles the 430-byte
 * record. That assembly belongs to {@code com.cardemo.batch.writers.RejectWriter}, which reproduces
 * {@code 2500-WRITE-REJECT-REC} ({@code app/cbl/CBTRN02C.cbl}:L446-L465): L447 moves the daily
 * transaction record into {@code REJECT-TRAN-DATA}, L448 moves the trailer into
 * {@code VALIDATION-TRAILER}, and L451 writes the concatenation. Keeping the two concerns apart is what
 * lets the trailer be unit-tested without any file, dataset or object store.
 *
 * <h2>Where the codes come from: a two-paragraph cascade</h2>
 *
 * <p>{@code 1500-VALIDATE-TRAN} ({@code app/cbl/CBTRN02C.cbl}:L370-L378) performs <strong>exactly
 * two</strong> lookup paragraphs, the second only when the first left the reason code at zero:
 *
 * <pre>
 * 1500-VALIDATE-TRAN.                                   &lt;- L370
 *     PERFORM 1500-A-LOOKUP-XREF.                       &lt;- L371
 *     IF WS-VALIDATION-FAIL-REASON = 0                  &lt;- L372
 *        PERFORM 1500-B-LOOKUP-ACCT                     &lt;- L373
 *     ELSE                                              &lt;- L374
 *        CONTINUE                                       &lt;- L375
 *     END-IF                                            &lt;- L376
 * ADD MORE VALIDATIONS HERE                             &lt;- L377 (comment in source)
 *     EXIT.                                             &lt;- L378
 * </pre>
 *
 * <p>Code {@code 100} is assigned by {@code 1500-A-LOOKUP-XREF}; codes {@code 101}, {@code 102} and
 * {@code 103} by {@code 1500-B-LOOKUP-ACCT}; and code {@code 109} by {@code 2800-UPDATE-ACCOUNT-REC},
 * which runs on the already-validated posting path and is discussed on that constant.
 *
 * <h2>The "no reject" state is the number zero, not a constant</h2>
 *
 * <p>Before validating each record the program clears the trailer -
 * {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl}:L208 and
 * {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC} at L209 - and then tests
 * {@code IF WS-VALIDATION-FAIL-REASON = 0} at L211 to decide between posting and rejecting. "Not
 * rejected" is therefore the <em>numeric value zero in a {@code PIC 9(04)} field</em>, not a named
 * outcome. It is modelled here as the integer {@link #NO_REJECT_REASON_CODE} and deliberately
 * <strong>not</strong> as a sixth enum constant: adding one would make {@code values().length} disagree
 * with the source's five assignable codes and would let "no reject" be passed anywhere a genuine reject
 * is expected. Use {@link #renderFailReason(int)} and {@link #noRejectTrailer()} for that state.
 *
 * <h2>Exit-code semantics (defined here, implemented by the batch layer)</h2>
 *
 * <p>After all six files close, {@code app/cbl/CBTRN02C.cbl}:L227-L231 displays the two totals and then
 * sets the step return code:
 *
 * <pre>
 * DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT   &lt;- L227
 * DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT        &lt;- L228
 * IF WS-REJECT-COUNT &gt; 0                                    &lt;- L229
 *    MOVE 4 TO RETURN-CODE                                  &lt;- L230
 * END-IF                                                    &lt;- L231
 * </pre>
 *
 * <p><strong>Return code 4 is set if and only if the reject count exceeds zero. There is no other
 * determinant.</strong> The reject counter is incremented at exactly one place, L214, inside the
 * {@code ELSE} branch of the L211 validation test - so the count, and therefore the return code, is a
 * direct function of how many records carried a non-zero reason code. Unexpected file statuses take a
 * different route entirely: {@code 9999-ABEND-PROGRAM} ({@code app/cbl/CBTRN02C.cbl}:L707-L711) moves
 * {@code 999} into {@code ABCODE} and calls the abend service, which the Java target surfaces as abend
 * code 999 with process return code 12. None of that is implemented in this file; the batch layer owns
 * the translation to {@code org.springframework.batch.core.ExitStatus}, and this Javadoc is where the
 * contract it must honour is recorded.
 *
 * <h2>Bounded metric cardinality</h2>
 *
 * <p>The "records rejected" counter of the observability layer is tagged by reject code. Because a
 * metric tag's cardinality is the number of distinct values it can take, that counter is safe only
 * while this enum has exactly five constants. <strong>Adding a sixth constant is a cardinality change,
 * not a cosmetic one</strong>, and would also break the byte-for-byte reject-file comparison if the new
 * constant were ever written.
 *
 * <h2>Error modes and purity</h2>
 *
 * <p>Every method on this type is pure: it performs no I/O, reads no configuration, consults no clock,
 * mutates no state and never returns {@code null}. The type holds no static mutable state whatsoever,
 * so all methods are inherently thread safe and every instance is immutable. Two methods reject invalid
 * input rather than guessing: {@link #requireFromCode(int)} throws {@code IllegalArgumentException}
 * naming the offending code, and {@link #renderFailReason(int)} throws the same for a value outside the
 * {@code PIC 9(04)} domain. {@link #fromCode(int)} is the total, non-throwing alternative and returns an
 * empty {@link Optional} for any unrecognised code, including {@link #NO_REJECT_REASON_CODE}.
 *
 * <p>All formatting is performed with {@code Locale.ROOT}. This is not decoration: a locale with
 * non-ASCII decimal digits would render the {@code PIC 9(04)} reason code in digits the reject file
 * cannot carry, silently corrupting a fixed-width record.
 *
 * <h2>Findings and severities</h2>
 *
 * <ul>
 *   <li><strong>High</strong> - the unguarded {@code 102} then {@code 103} sequence means {@code 103}
 *       overwrites {@code 102} when both conditions fail. Preserved deliberately; see
 *       {@link #OVERLIMIT_TRANSACTION} and {@link #TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION}.
 *       Remediation: none - preserve and log. "Repairing" it changes reject output and fails the
 *       end-to-end parity gate.</li>
 *   <li><strong>Low</strong> as an observation, <strong>Blocker</strong> if acted upon - {@code 109} is
 *       assigned on a reachable line but never consumed. Retained deliberately; see
 *       {@link #ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE}. Deleting it would break the paragraph map that
 *       the scope-coverage gate verifies.</li>
 *   <li><strong>Low</strong> - {@code 101} and {@code 109} carry byte-identical description text. This
 *       is what the source says and the duplication is reproduced, not resolved.</li>
 *   </ul>
 *
 * <p>Both findings are held in {@code DECISION_LOG.md} - the unobservable code at
 * {@code DL-PP-03}, the byte-identical description text at {@code DL-PP-13} - and both have rows in
 * {@code TRACEABILITY_MATRIX.md}. <strong>Both registers are authored at the repository root</strong>. This
 * Javadoc together with
 * {@code docs/technical-specifications.md} remains the tracking record that cannot drift from the code.
 * Neither finding is untracked residue.
 *
 * @see #toValidationTrailer()
 * @see #fromCode(int)
 */
public enum RejectCode {

    /**
     * Reject code {@code 100} - {@code INVALID CARD NUMBER FOUND}. Assigned by the cross-reference lookup
     * {@code 1500-A-LOOKUP-XREF} at {@code app/cbl/CBTRN02C.cbl:385}.
     */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /**
     * Reject code {@code 101} - {@code ACCOUNT RECORD NOT FOUND}. Assigned by the account lookup
     * {@code 1500-B-LOOKUP-ACCT} at {@code app/cbl/CBTRN02C.cbl:397} when the account is absent.
     */
    ACCOUNT_RECORD_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /**
     * Reject code {@code 102} - {@code OVERLIMIT TRANSACTION}. Assigned at
     * {@code app/cbl/CBTRN02C.cbl:410} when the credit limit is below the temporary balance.
     *
     * <p>The expiry test that follows at {@code app/cbl/CBTRN02C.cbl:413-419} is sequential and
     * <strong>unguarded</strong> - no alternative branch, no early exit - so when both conditions fail this
     * value is overwritten by {@link #TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION} and a single reject record
     * bearing 103 is written. Guarding the second test, or emitting two reject records, would diverge.
     */
    OVERLIMIT_TRANSACTION(102, "OVERLIMIT TRANSACTION"),

    /**
     * Reject code {@code 103} - {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}.
     *
     * <p>Assigned by {@code 1500-B-LOOKUP-ACCT} ({@code app/cbl/CBTRN02C.cbl}:L393-L422) inside the
     * {@code NOT INVALID KEY} branch, when the account expiry date precedes the date on which the
     * transaction originated.
     *
     * <p>Citations: the code is moved at L417; the description literal occupies L418-L419.
     *
     * <p><strong>Transcription note.</strong> The literal abbreviates the word account as
     * {@code ACCT} and uses {@code EXPIRATION}, not {@code EXPIRY}. At 42 characters it is the longest
     * of the five descriptions, still comfortably inside the 76-character
     * {@code WS-VALIDATION-FAIL-REASON-DESC} field.
     *
     * <p><strong>Severity: High - this constant silently overwrites {@code 102}.</strong> The
     * credit-limit test at L407-L413 and the expiry test at L414-L420 are two separate, sequential,
     * unguarded {@code IF ... END-IF} blocks; the source quotes in full on
     * {@link #OVERLIMIT_TRANSACTION}. Because nothing chains or gates them, an account that is both
     * over its limit <em>and</em> expired has {@code 102} assigned at L410 and then immediately
     * overwritten by {@code 103} at L417.
     *
     * <p>Three consequences follow, and each is a place where the obvious implementation diverges:
     * <ul>
     *   <li><strong>Exactly one reject record is written, and it bears {@code 103}.</strong> Not two
     *       records, and not {@code 102}. The reject write happens once per input record, at
     *       {@code app/cbl/CBTRN02C.cbl}:L215, long after both tests have run.</li>
     *   <li><strong>No set, list or bit-field of codes is modelled.</strong>
     *       {@code WS-VALIDATION-FAIL-REASON} is a single {@code PIC 9(04)} field holding one value; a
     *       collection would be able to represent a state the legacy record physically cannot.</li>
     *   <li><strong>The second test is not guarded.</strong> Adding {@code if (reason == 0)} in front of
     *       it would be the natural "fix" and is forbidden: it would make over-limit-and-expired records
     *       reject as {@code 102}, changing the reject file.</li>
     * </ul>
     *
     * <p>Remediation: <strong>none - preserve and log.</strong> It is held as {@code DL-LD-04} in
     * {@code DECISION_LOG.md} among the preserved legacy defects and a row in {@code TRACEABILITY_MATRIX.md} as a
     * {@code CBTRN02C} fidelity hot spot. Both registers are authored at the repository root; a reading of
     * 1 August 2026 that recorded neither as available is withdrawn. This Javadoc stays the record that
     * cannot drift from the constant it describes.
     *
     * <p><strong>Two further quirks of the expiry test, part of the field contract.</strong>
     * <ul>
     *   <li>The account expiry field is <em>misspelled in the source</em> as
     *       {@code ACCT-EXPIRAION-DATE} - the second {@code T} of "EXPIRATION" is missing (L414). The
     *       misspelling is part of the copybook's field contract and is carried forward rather than
     *       tidied, so that a reader can grep the Java and the COBOL for the same token.</li>
     *   <li>The comparison is a <strong>string</strong> comparison against the <strong>first ten
     *       characters of the ORIGINATING timestamp</strong>, {@code DALYTRAN-ORIG-TS (1:10)} - the date
     *       portion of the 26-character {@code PIC X(26)} field declared in
     *       {@code app/cpy/CVTRA06Y.cpy}. It is emphatically <em>not</em> the processing timestamp
     *       {@code DALYTRAN-PROC-TS}, and it is not a date-typed comparison. Substituting either would
     *       change which transactions are rejected.</li>
     * </ul>
     */
    TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),

    /**
     * Reject code {@code 109} - {@code ACCOUNT RECORD NOT FOUND}, sharing its literal with
     * {@link #ACCOUNT_RECORD_NOT_FOUND} rather than declaring its own.
     *
     * <p>It exists because {@code 2800-UPDATE-ACCOUNT-REC} assigns it at {@code app/cbl/CBTRN02C.cbl:556} on
     * the account-rewrite failure path, which is real code on a reachable path. It is nonetheless
     * <strong>never consumed as a reject outcome</strong>: that paragraph runs only inside the posting routine,
     * which is entered only when the reason code was already zero, so no reject record is written, the reject
     * count is not incremented, execution continues to the transaction write, and the value is cleared on the
     * next iteration at {@code app/cbl/CBTRN02C.cbl:208}. The constant is therefore retained for fidelity, not
     * because any reject file can contain it.
     */
    ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE(109, "ACCOUNT RECORD NOT FOUND");

    /**
     * The reason-code value meaning "this record was not rejected": zero. Cleared before every record's
     * validation at {@code app/cbl/CBTRN02C.cbl:208}.
     */
    public static final int NO_REJECT_REASON_CODE = 0;

    /**
     * Width in characters of {@code WS-VALIDATION-FAIL-REASON}, {@code PIC 9(04)}
     * ({@code app/cbl/CBTRN02C.cbl}:L181): four.
     */
    public static final int FAIL_REASON_LENGTH = 4;

    /**
     * Width in characters of {@code WS-VALIDATION-FAIL-REASON-DESC}, {@code PIC X(76)}
     * ({@code app/cbl/CBTRN02C.cbl}:L182): seventy-six.
     */
    public static final int FAIL_REASON_DESC_LENGTH = 76;

    /**
     * Width in characters of {@code VALIDATION-TRAILER}, {@code PIC X(80)} ({@code app/cbl/CBTRN02C.cbl}:L178):
     * eighty, being the four-character reason code followed by the seventy-six-character description. The sum
     * is written out rather than hard-coded so the identity {@code 4 + 76 = 80} is visible at the declaration.
     */
    public static final int VALIDATION_TRAILER_LENGTH = FAIL_REASON_LENGTH + FAIL_REASON_DESC_LENGTH;

    /**
     * Width in characters of {@code REJECT-TRAN-DATA}, {@code PIC X(350)} ({@code app/cbl/CBTRN02C.cbl}:L177):
     * three hundred and fifty - the {@code DALYTRAN-RECORD} image of {@code app/cpy/CVTRA06Y.cpy}, whose header
     * states {@code RECLN = 350}.
     */
    public static final int REJECT_TRAN_DATA_LENGTH = 350;

    /**
     * Width in characters of {@code REJECT-RECORD}, being {@code 350 + 80 = 430}
     * ({@code app/cbl/CBTRN02C.cbl}:L176-L178), corroborated independently by
     * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} at {@code app/jcl/POSTTRAN.jcl}:L36.
     */
    public static final int REJECT_RECORD_LENGTH = REJECT_TRAN_DATA_LENGTH + VALIDATION_TRAILER_LENGTH;

    /**
     * Highest value a {@code PIC 9(04)} field can hold: {@code 9999}, that is {@link #FAIL_REASON_LENGTH}
     * nines. Stated as an integer literal rather than computed, because the only concise way to derive it would
     * introduce floating-point arithmetic, and no floating-point type may appear anywhere in this migration.
     */
    private static final int MAX_FAIL_REASON_CODE = 9999;

    /**
     * Format string producing the {@code PIC 9(04)} rendering - zero-padded, right-aligned, exactly
     * {@link #FAIL_REASON_LENGTH} digits. Built once from the length constant rather than written as a literal
     * so that the width has a single definition.
     */
    private static final String FAIL_REASON_FORMAT = "%0" + FAIL_REASON_LENGTH + "d";

    /**
     * Format string producing the {@code PIC X(76)} rendering - space-padded on the right to exactly
     * {@link #FAIL_REASON_DESC_LENGTH} characters, matching the COBOL semantics of moving a shorter
     * alphanumeric literal into a longer alphanumeric field.
     */
    private static final String FAIL_REASON_DESC_FORMAT = "%-" + FAIL_REASON_DESC_LENGTH + "s";

    /**
     * The numeric reason code as moved into {@code WS-VALIDATION-FAIL-REASON}.
     */
    private final int code;

    /**
     * The verbatim description literal as moved into {@code WS-VALIDATION-FAIL-REASON-DESC}.
     */
    private final String description;

    /**
     * Binds a reject outcome to its numeric reason code and its verbatim description literal.
     *
     * @param code the four-digit reason code, one of 100, 101, 102, 103 or 109
     * @param description the reject description exactly as it appears in the COBOL literal
     */
    RejectCode(final int code, final String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the numeric reason code, as moved into {@code WS-VALIDATION-FAIL-REASON}
     * ({@code app/cbl/CBTRN02C.cbl}:L181).
     *
     * @return the four-digit reason code as an {@code int}, always one of 100, 101, 102, 103 or 109
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the reject description exactly as it appears in the COBOL literal, with no padding.
     *
     * @return the verbatim description literal, between 21 and 42 characters long
     */
    public String getDescription() {
        return description;
    }

    /**
     * Renders this outcome's reason code as the {@code PIC 9(04)} field {@code WS-VALIDATION-FAIL-REASON}
     * ({@code app/cbl/CBTRN02C.cbl}:L181): zero-padded on the left to exactly {@link #FAIL_REASON_LENGTH}
     * characters.
     *
     * @return exactly {@link #FAIL_REASON_LENGTH} characters, all ASCII digits
     */
    public String toFailReasonField() {
        return renderFailReason(code);
    }

    /**
     * Renders this outcome's description as the {@code PIC X(76)} field {@code WS-VALIDATION-FAIL-REASON-DESC}
     * ({@code app/cbl/CBTRN02C.cbl}:L182): space-padded on the right to exactly
     * {@link #FAIL_REASON_DESC_LENGTH} characters.
     *
     * @return exactly {@link #FAIL_REASON_DESC_LENGTH} characters.
     */
    public String toFailReasonDescField() {
        return padFailReasonDesc(description);
    }

    /**
     * Renders the complete {@code WS-VALIDATION-TRAILER} for this outcome
     * ({@code app/cbl/CBTRN02C.cbl}:L180-L182): the four-character reason code immediately followed by the
     * seventy-six-character description, {@link #VALIDATION_TRAILER_LENGTH} characters in total.
     *
     * @return exactly {@link #VALIDATION_TRAILER_LENGTH} characters, being {@link #toFailReasonField()}
     * concatenated with {@link #toFailReasonDescField()}
     */
    public String toValidationTrailer() {
        return toFailReasonField() + toFailReasonDescField();
    }

    /**
     * Renders an arbitrary reason code as the {@code PIC 9(04)} field {@code WS-VALIDATION-FAIL-REASON},
     * zero-padded on the left to exactly {@link #FAIL_REASON_LENGTH} characters.
     *
     * @param reasonCode the reason code to render.
     * @return exactly {@link #FAIL_REASON_LENGTH} characters, all ASCII digits
     * @throws IllegalArgumentException if {@code reasonCode} is negative or exceeds four digits.
     */
    public static String renderFailReason(final int reasonCode) {
        if (reasonCode < NO_REJECT_REASON_CODE || reasonCode > MAX_FAIL_REASON_CODE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Reason code %d cannot be rendered into WS-VALIDATION-FAIL-REASON: a PIC 9(04) "
                            + "field holds only the values %d through %d",
                    reasonCode, NO_REJECT_REASON_CODE, MAX_FAIL_REASON_CODE));
        }
        return String.format(Locale.ROOT, FAIL_REASON_FORMAT, reasonCode);
    }

    /**
     * Renders the trailer in its cleared, "no reject" state: {@code "0000"} followed by
     * {@link #FAIL_REASON_DESC_LENGTH} spaces, {@link #VALIDATION_TRAILER_LENGTH} characters in total.
     *
     * @return exactly {@link #VALIDATION_TRAILER_LENGTH} characters.
     */
    public static String noRejectTrailer() {
        return renderFailReason(NO_REJECT_REASON_CODE) + padFailReasonDesc("");
    }

    /**
     * Resolves a numeric reason code to its outcome, without throwing.
     *
     * @param reasonCode the reason code to resolve.
     * @return the matching outcome, or an empty {@link Optional} if {@code reasonCode} is not one of 100, 101,
     * 102, 103 or 109.
     */
    public static Optional<RejectCode> fromCode(final int reasonCode) {
        return switch (reasonCode) {
            case 100 -> Optional.of(INVALID_CARD_NUMBER);
            case 101 -> Optional.of(ACCOUNT_RECORD_NOT_FOUND);
            case 102 -> Optional.of(OVERLIMIT_TRANSACTION);
            case 103 -> Optional.of(TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);
            case 109 -> Optional.of(ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE);
            default -> Optional.empty();
        };
    }

    /**
     * Resolves a numeric reason code to its outcome, failing fast if the code is not one of the five.
     *
     * @param reasonCode the reason code to resolve
     * @return the matching outcome; never {@code null}
     * @throws IllegalArgumentException if {@code reasonCode} is not one of 100, 101, 102, 103 or 109, including
     * when it is {@link #NO_REJECT_REASON_CODE}
     */
    public static RejectCode requireFromCode(final int reasonCode) {
        return fromCode(reasonCode).orElseThrow(() -> new IllegalArgumentException(
                String.format(Locale.ROOT,
                        "Reason code %d is not a CBTRN02C reject outcome: the only assignable codes are "
                                + "100, 101, 102, 103 and 109 (%d means the record was not rejected)",
                        reasonCode, NO_REJECT_REASON_CODE)));
    }

    /**
     * Space-pads text on the right to exactly {@link #FAIL_REASON_DESC_LENGTH} characters, reproducing a COBOL
     * {@code MOVE} into a {@code PIC X(76)} field.
     *
     * @param text the text to place in the field, left-justified
     * @return exactly {@link #FAIL_REASON_DESC_LENGTH} characters when {@code text} is no longer than the field
     */
    private static String padFailReasonDesc(final String text) {
        return String.format(Locale.ROOT, FAIL_REASON_DESC_FORMAT, text);
    }
}
