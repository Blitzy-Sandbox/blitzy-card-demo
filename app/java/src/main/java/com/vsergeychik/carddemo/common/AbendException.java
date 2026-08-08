package com.vsergeychik.carddemo.common;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Java equivalent of the COBOL {@code CALL 'CEE3ABD'} abend service.
 *
 * <p>{@code CEE3ABD} is the IBM Language Environment callable service that terminates the enclave
 * immediately and unconditionally. In the CardDemo batch programs it is the last statement of the
 * abend paragraph, reached only after an unexpected file status has already been displayed. There is
 * no recovery path: control never returns to the caller. Throwing this exception is the faithful
 * translation, and the batch layer converts it into the process exit code so that JCL-equivalent
 * {@code COND=(0,NE)} gating (see {@code app/jcl/CREASTMT.JCL}) keeps working unchanged.</p>
 *
 * <h2>The nine call sites this class replaces</h2>
 *
 * <p>Every {@code CALL 'CEE3ABD'} in the repository is listed below, with the line number of the
 * call and the name of the paragraph that contains it. There are exactly nine, one per program.</p>
 *
 * <ul>
 *   <li>{@code app/cbl/CBACT01C.cbl:173} - paragraph {@code 9999-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBACT02C.cbl:158} - paragraph {@code 9999-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBACT03C.cbl:158} - paragraph {@code 9999-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBACT04C.cbl:632} - paragraph {@code 9999-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl:158} - paragraph {@code Z-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBSTM03A.CBL:923} - paragraph {@code 9999-ABEND-PROGRAM} (see the divergence
 *       below)</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl:473} - paragraph {@code Z-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl:711} - paragraph {@code 9999-ABEND-PROGRAM}</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl:630} - paragraph {@code 9999-ABEND-PROGRAM}</li>
 * </ul>
 *
 * <p>The paragraph is called {@code 9999-ABEND-PROGRAM} in seven programs and
 * {@code Z-ABEND-PROGRAM} in {@code CBCUS01C} and {@code CBTRN01C}. The two names carry exactly the
 * same body, so the difference is purely cosmetic and has no behavioural effect; it is recorded here
 * only so the mapping from COBOL paragraph to Java call site stays traceable. Note that the naming
 * and the behaviour are independent axes: {@code CBSTM03A} uses the {@code 9999-} name yet is the one
 * program whose body differs, as described next.</p>
 *
 * <h2>Eight sites share one shape</h2>
 *
 * <p>Eight of the nine paragraphs are byte-identical:</p>
 *
 * <pre>
 * 9999-ABEND-PROGRAM.
 *     DISPLAY 'ABENDING PROGRAM'
 *     MOVE 0 TO TIMING
 *     MOVE 999 TO ABCODE
 *     CALL 'CEE3ABD'.
 * </pre>
 *
 * <p>Use {@link #standard(String, int, String)} for those eight. It supplies
 * {@link #STANDARD_ABEND_CODE} and {@link #STANDARD_TIMING} exactly as the {@code MOVE} statements
 * above do.</p>
 *
 * <h2>The ninth site diverges - do not normalise it</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL:921-923} is the sole exception. Its paragraph does only this:</p>
 *
 * <pre>
 * 9999-ABEND-PROGRAM.
 *     DISPLAY 'ABENDING PROGRAM'
 *     CALL 'CEE3ABD'.
 * </pre>
 *
 * <p>There is no {@code MOVE 999 TO ABCODE} and no {@code MOVE 0 TO TIMING}. The divergence is
 * structural rather than accidental-looking: {@code CBSTM03A.CBL} does not even declare
 * {@code ABCODE} or {@code TIMING} in its {@code WORKING-STORAGE SECTION}, so those two values
 * genuinely do not exist for that program. {@link #withoutAbendParameters(String, int, String)}
 * models it, and for instances built that way {@link #getAbendCode()} and {@link #getTiming()}
 * return an empty {@link OptionalInt} rather than zero.</p>
 *
 * <p>The empty result matters. Returning a silent {@code 0} would be indistinguishable from the
 * genuine {@code TIMING = 0} that the other eight sites set, which would make the divergence
 * invisible to the parity harness. Making the abend parameters mandatory would be worse still: the
 * {@code CBSTM03A} translation would have to fabricate a {@code 999} the COBOL never produces. This
 * behaviour is preserved deliberately and must not be tidied away.</p>
 *
 * <h2>Field provenance</h2>
 *
 * <p>{@code ABCODE} and {@code TIMING} are declared as {@code 01 ABCODE PIC S9(9) BINARY.} and
 * {@code 01 TIMING PIC S9(9) BINARY.} in each of the eight programs that declare them
 * ({@code CBACT01C} lines 66-67, {@code CBACT02C} 66-67, {@code CBACT03C} 66-67, {@code CBACT04C}
 * 138-139, {@code CBCUS01C} 66-67, {@code CBTRN01C} 147-148, {@code CBTRN02C} 147-148,
 * {@code CBTRN03C} 155-156). They are scale-free binary integers, so they map to Java {@code int};
 * no fixed-point or fractional type is involved.</p>
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li><strong>Unchecked.</strong> {@code CALL 'CEE3ABD'} is an unconditional process abend, not a
 *       recoverable condition. A checked exception would push a {@code throws} clause through every
 *       repository signature and distort the layering, so this extends
 *       {@link RuntimeException}.</li>
 *   <li><strong>Immutable.</strong> Every field is {@code private final}, there is no mutator, and
 *       the only static members are compile-time constants. Instances are safe to share across
 *       threads.</li>
 *   <li><strong>Framework-free.</strong> This class deliberately references nothing from Spring and
 *       nothing from any other package in this codebase - it is a root of the dependency graph. The
 *       translation of this exception into a Spring Batch exit status belongs to the batch
 *       configuration and the job classes, not here.</li>
 *   <li><strong>Decoupled from file-status formatting.</strong> In seven programs the abend is
 *       reached through {@code PERFORM 9910-DISPLAY-IO-STATUS} (named
 *       {@code Z-DISPLAY-IO-STATUS} in {@code CBCUS01C} and {@code CBTRN01C}), which renders the
 *       two-character file status before the abend runs; {@code CBSTM03A} instead displays
 *       {@code 'RETURN CODE: '} followed by the subroutine status returned by {@code CBSTM03B}.
 *       Either way the caller has already produced that text, so this class accepts it as a free-text
 *       reason and never imports the sibling {@code FileStatus} class. The dependency graph stays
 *       acyclic and both classes stay independently testable.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <p>The eight standard sites, for example the failed {@code OPEN INPUT ACCTFILE} at
 * {@code app/cbl/CBACT01C.cbl:144-147}:</p>
 *
 * <pre>
 * throw AbendException.standard("CBACT01C", AbendException.RETURN_CODE_IO_ERROR,
 *         "ERROR OPENING ACCTFILE");
 * </pre>
 *
 * <p>The {@code CBSTM03A} site, for example the unexpected subroutine status at
 * {@code app/cbl/CBSTM03A.CBL:359-361}:</p>
 *
 * <pre>
 * throw AbendException.withoutAbendParameters("CBSTM03A", AbendException.RETURN_CODE_IO_ERROR,
 *         "ERROR READING XREFFILE");
 * </pre>
 */
public final class AbendException extends RuntimeException {

    /**
     * Serialization identity. Declared explicitly because {@link RuntimeException} is
     * {@link java.io.Serializable}; every field of this class ({@code int}, {@link Integer} and
     * {@link String}) is itself serializable, so no field needs to be transient.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The literal written by {@code DISPLAY 'ABENDING PROGRAM'}, identical at all nine call sites.
     *
     * <p>Exactly sixteen characters, with no trailing space. It is exposed as a constant so the job
     * classes emit the same text the COBOL emits instead of each inventing its own wording, and so
     * the parity harness can assert on one shared value.</p>
     */
    public static final String ABEND_DISPLAY_TEXT = "ABENDING PROGRAM";

    /**
     * The value the eight standard paragraphs move into {@code ABCODE}: {@code MOVE 999 TO ABCODE}.
     *
     * <p>Absent by design at {@code app/cbl/CBSTM03A.CBL:923}, which never sets it.</p>
     */
    public static final int STANDARD_ABEND_CODE = 999;

    /**
     * The value the eight standard paragraphs move into {@code TIMING}: {@code MOVE 0 TO TIMING}.
     *
     * <p>Absent by design at {@code app/cbl/CBSTM03A.CBL:923}, which never sets it. Because the
     * value happens to be zero, absence must be represented as an empty {@link OptionalInt} and
     * never as a plain {@code 0} - otherwise the divergence cannot be observed.</p>
     */
    public static final int STANDARD_TIMING = 0;

    /**
     * Successful completion: {@code 88 APPL-AOK VALUE 0.}
     *
     * <p>Declared on {@code 01 APPL-RESULT PIC S9(9) COMP.} in all eight programs that carry the
     * standard abend paragraph. Never the return code of an abend - it is defined here so the
     * complete set of observed COBOL codes lives in one place, and so a caller can compare against
     * it rather than against a bare literal.</p>
     */
    public static final int RETURN_CODE_OK = 0;

    /**
     * Warning-level completion: {@code 4}.
     *
     * <p>Verified at {@code app/cbl/CBTRN02C.cbl:229-231}, where
     * {@code IF WS-REJECT-COUNT &gt; 0 / MOVE 4 TO RETURN-CODE} sets it after the daily transaction
     * run has rejected at least one record. That is a normal, non-abending end of job, so this code
     * reaches the process exit through the batch layer rather than through this exception. It is
     * listed here because the batch exit codes the migration must preserve are 0, 4, 8 and 12, and
     * splitting that set across two classes would invite one of the values to be dropped.</p>
     */
    public static final int RETURN_CODE_WARNING = 4;

    /**
     * The assumed-failure value: {@code 8}.
     *
     * <p>Named for what the COBOL actually does with it rather than for a severity level. It is
     * placed in {@code APPL-RESULT} <em>before</em> a file operation is attempted - written both as
     * {@code MOVE 8 TO APPL-RESULT} and as {@code ADD 8 TO ZERO GIVING APPL-RESULT} - so the
     * operation has to clear it to zero to be treated as successful. Every {@code OPEN} guard and
     * every {@code CLOSE} guard in the batch programs pre-sets it this way, and one {@code WRITE}
     * guard does too, which is why it is not called an open failure. In conventional job-step terms
     * it is the "error" severity that sits between {@link #RETURN_CODE_WARNING} and
     * {@link #RETURN_CODE_IO_ERROR}.</p>
     */
    public static final int RETURN_CODE_ASSUMED_FAILURE = 8;

    /**
     * The unexpected-file-status value: {@code 12}.
     *
     * <p>The most frequent code on the abend path, and in conventional job-step terms the "severe
     * error" severity. Every {@code OPEN}, {@code CLOSE}, {@code READ}, {@code WRITE} and
     * {@code REWRITE} guard in the batch programs moves it into {@code APPL-RESULT} when the file
     * status is neither {@code '00'} nor an expected end of file, and the very next statements
     * display the status and abend.</p>
     */
    public static final int RETURN_CODE_IO_ERROR = 12;

    /**
     * End of file: {@code 88 APPL-EOF VALUE 16.}
     *
     * <p>Recorded exactly as the COBOL declares it, on {@code 01 APPL-RESULT PIC S9(9) COMP.} in all
     * eight programs that carry the standard abend paragraph. Two facts about it are in tension and
     * both are documented here rather than reconciled by discarding one of them: the migration's
     * batch exit-code requirement names 0, 4, 8 and 12, whereas {@code 16} is verifiably moved into
     * {@code APPL-RESULT} and tested through the {@code APPL-EOF} condition name. The resolution is
     * that {@code 16} is an internal {@code APPL-RESULT} sentinel meaning "end of file reached", not
     * a process exit code: reaching it sets {@code END-OF-FILE} to {@code 'Y'} and ends the read loop
     * normally instead of abending. It is defined here so a caller can name it, and it is
     * deliberately not removed from the set.</p>
     */
    public static final int RETURN_CODE_END_OF_FILE = 16;

    /**
     * The COBOL {@code PROGRAM-ID} of the abending program, for example {@code "CBACT01C"}.
     *
     * <p>Never {@code null} and never blank; carried so a parity fingerprint or a log line can
     * identify which of the nine sites produced the abend.</p>
     */
    private final String program;

    /**
     * The value the abending program had placed in {@code APPL-RESULT}, or otherwise intends as its
     * return code.
     *
     * <p>A plain {@code int} rather than an enumeration: an enumeration would reject any value the
     * COBOL could legitimately produce but that this migration has not yet observed, and rejecting a
     * legitimate value would itself be a behaviour change.</p>
     */
    private final int returnCode;

    /**
     * The {@code ABCODE} argument of {@code CALL 'CEE3ABD'}, or {@code null} when the abending
     * paragraph never set one.
     *
     * <p>Boxed precisely so that absence is representable. {@code app/cbl/CBSTM03A.CBL:921-923}
     * performs no {@code MOVE 999 TO ABCODE} and does not even declare the field, so for that site
     * this is {@code null} and {@link #getAbendCode()} yields an empty {@link OptionalInt}. The
     * other eight sites carry {@link #STANDARD_ABEND_CODE}.</p>
     */
    private final Integer abendCode;

    /**
     * The {@code TIMING} argument of {@code CALL 'CEE3ABD'}, or {@code null} when the abending
     * paragraph never set one.
     *
     * <p>Boxed for the same reason as {@link #abendCode}, and the reason is sharper here: the eight
     * standard sites set {@code TIMING} to zero, so an unboxed field would make the
     * {@code CBSTM03A} absence indistinguishable from a real value.</p>
     */
    private final Integer timing;

    /**
     * Free-text detail describing why the abend was raised, or {@code null} when none was supplied.
     *
     * <p>This is where the caller passes the text the COBOL displays immediately before the abend -
     * the {@code 'ERROR OPENING ACCTFILE'} style message and the already-formatted file status. It
     * has no COBOL counterpart inside {@code CALL 'CEE3ABD'} itself, which takes no such argument,
     * so it never participates in record-level parity; it exists to keep this class decoupled from
     * file-status formatting. A {@code null} or blank argument is normalised to {@code null} so that
     * {@link #getMessage()} can never end in a dangling separator.</p>
     */
    private final String reason;

    /**
     * The single canonical constructor. All public construction goes through the two factory
     * families so that the two COBOL shapes stay self-documenting at the call site.
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}; must be non-null and
     *                   non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}
     * @param abendCode  the {@code ABCODE} argument, or {@code null} when the paragraph set none
     * @param timing     the {@code TIMING} argument, or {@code null} when the paragraph set none
     * @param reason     free-text detail, or {@code null}; a blank string is treated as absent
     * @param cause      the underlying failure that triggered the abend, or {@code null} when the
     *                   abend was raised from a file-status check rather than from another exception
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    private AbendException(String program,
                           int returnCode,
                           Integer abendCode,
                           Integer timing,
                           String reason,
                           Throwable cause) {
        super(composeMessage(requireProgram(program), returnCode, abendCode, timing,
                normalizeReason(reason)), cause);
        this.program = program;
        this.returnCode = returnCode;
        this.abendCode = abendCode;
        this.timing = timing;
        this.reason = normalizeReason(reason);
    }

    /**
     * Creates the abend raised by the eight standard paragraphs, supplying
     * {@link #STANDARD_ABEND_CODE} and {@link #STANDARD_TIMING} exactly as their
     * {@code MOVE 999 TO ABCODE} and {@code MOVE 0 TO TIMING} statements do.
     *
     * <p>Applies to {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C}, {@code CBACT04C},
     * {@code CBCUS01C}, {@code CBTRN01C}, {@code CBTRN02C} and {@code CBTRN03C}. It must never be
     * used for {@code CBSTM03A} - see {@link #withoutAbendParameters(String, int)}.</p>
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}, for example
     *                   {@code "CBACT01C"}; must be non-null and non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}, typically
     *                   {@link #RETURN_CODE_IO_ERROR} or {@link #RETURN_CODE_ASSUMED_FAILURE}
     * @return a new abend carrying both {@code CEE3ABD} arguments
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException standard(String program, int returnCode) {
        return standard(program, returnCode, null, null);
    }

    /**
     * Creates the abend raised by the eight standard paragraphs, with the detail text the COBOL
     * displays immediately before the abend.
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}; must be non-null and
     *                   non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}
     * @param reason     free-text detail such as {@code "ERROR OPENING ACCTFILE"}, optionally with
     *                   the already-formatted file status appended; may be {@code null}, and a blank
     *                   string is treated as absent
     * @return a new abend carrying both {@code CEE3ABD} arguments
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException standard(String program, int returnCode, String reason) {
        return standard(program, returnCode, reason, null);
    }

    /**
     * Creates the abend raised by the eight standard paragraphs, retaining the underlying failure.
     *
     * <p>The COBOL abend carries no cause, because COBOL has no exception chain. The parameter
     * exists for the Java translation only: when a repository call fails with a data-access or
     * input-output exception, keeping that exception as the cause preserves the diagnostic trail
     * without altering any observable COBOL behaviour.</p>
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}; must be non-null and
     *                   non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}
     * @param reason     free-text detail; may be {@code null}, and a blank string is treated as
     *                   absent
     * @param cause      the underlying failure, or {@code null} when there is none
     * @return a new abend carrying both {@code CEE3ABD} arguments
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException standard(String program, int returnCode, String reason,
                                          Throwable cause) {
        return new AbendException(program, returnCode, STANDARD_ABEND_CODE, STANDARD_TIMING, reason,
                cause);
    }

    /**
     * Creates the abend raised by {@code app/cbl/CBSTM03A.CBL:921-923}, which sets neither
     * {@code ABCODE} nor {@code TIMING}.
     *
     * <p>That paragraph displays {@link #ABEND_DISPLAY_TEXT} and calls {@code CEE3ABD} with nothing
     * else, and the program does not even declare the two fields. Instances created here therefore
     * report both as absent, which is what keeps the divergence from the other eight sites visible.
     * Do not substitute {@link #standard(String, int)} for convenience: that would fabricate a
     * {@code 999} the COBOL never produces.</p>
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}, in practice
     *                   {@code "CBSTM03A"}; must be non-null and non-blank
     * @param returnCode the return code the program intends
     * @return a new abend with no {@code ABCODE} and no {@code TIMING}
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException withoutAbendParameters(String program, int returnCode) {
        return withoutAbendParameters(program, returnCode, null, null);
    }

    /**
     * Creates the {@code CBSTM03A}-shaped abend with the detail text the program displays
     * immediately before it.
     *
     * <p>{@code CBSTM03A} displays {@code 'ERROR READING XREFFILE'} and then
     * {@code 'RETURN CODE: '} followed by the status returned by its {@code CBSTM03B} subroutine
     * call, for example at {@code app/cbl/CBSTM03A.CBL:359-361}. Both parts belong in
     * {@code reason}.</p>
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}; must be non-null and
     *                   non-blank
     * @param returnCode the return code the program intends
     * @param reason     free-text detail; may be {@code null}, and a blank string is treated as
     *                   absent
     * @return a new abend with no {@code ABCODE} and no {@code TIMING}
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException withoutAbendParameters(String program, int returnCode,
                                                        String reason) {
        return withoutAbendParameters(program, returnCode, reason, null);
    }

    /**
     * Creates the {@code CBSTM03A}-shaped abend, retaining the underlying failure.
     *
     * @param program    the abending program's COBOL {@code PROGRAM-ID}; must be non-null and
     *                   non-blank
     * @param returnCode the return code the program intends
     * @param reason     free-text detail; may be {@code null}, and a blank string is treated as
     *                   absent
     * @param cause      the underlying failure, or {@code null} when there is none
     * @return a new abend with no {@code ABCODE} and no {@code TIMING}
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException withoutAbendParameters(String program, int returnCode,
                                                        String reason, Throwable cause) {
        return new AbendException(program, returnCode, null, null, reason, cause);
    }

    /**
     * Returns the COBOL {@code PROGRAM-ID} of the abending program.
     *
     * @return the program name, never {@code null} and never blank
     */
    public String getProgram() {
        return program;
    }

    /**
     * Returns the return code the abending program carries.
     *
     * <p>This is the value the batch layer turns into the Spring Batch exit status and the process
     * exit code, so that JCL-equivalent {@code COND} gating behaves as it does on the mainframe. It
     * is deliberately unconstrained: any value the COBOL can place in {@code APPL-RESULT} survives
     * the round trip unchanged.</p>
     *
     * @return the return code, one of {@link #RETURN_CODE_OK}, {@link #RETURN_CODE_WARNING},
     *         {@link #RETURN_CODE_ASSUMED_FAILURE}, {@link #RETURN_CODE_IO_ERROR} or
     *         {@link #RETURN_CODE_END_OF_FILE} for the values observed in the source, though not
     *         restricted to them
     */
    public int getReturnCode() {
        return returnCode;
    }

    /**
     * Returns the {@code ABCODE} argument of {@code CALL 'CEE3ABD'}, if the abending paragraph set
     * one.
     *
     * @return {@link #STANDARD_ABEND_CODE} for the eight standard sites, or an empty
     *         {@link OptionalInt} for the {@code app/cbl/CBSTM03A.CBL:923} shape, which sets no
     *         abend code at all
     */
    public OptionalInt getAbendCode() {
        return abendCode == null ? OptionalInt.empty() : OptionalInt.of(abendCode);
    }

    /**
     * Reports whether an {@code ABCODE} is present.
     *
     * <p>Provided so a caller can test presence without unwrapping the {@link OptionalInt}, and so
     * the {@code CBSTM03A} divergence can be asserted directly.</p>
     *
     * @return {@code true} for the eight standard sites, {@code false} for the {@code CBSTM03A}
     *         shape
     */
    public boolean hasAbendCode() {
        return abendCode != null;
    }

    /**
     * Returns the {@code TIMING} argument of {@code CALL 'CEE3ABD'}, if the abending paragraph set
     * one.
     *
     * <p>Read the empty result carefully: the eight standard sites set {@code TIMING} to
     * {@link #STANDARD_TIMING}, which is zero, so an empty {@link OptionalInt} and a present zero
     * mean genuinely different things and must not be collapsed.</p>
     *
     * @return {@link #STANDARD_TIMING} for the eight standard sites, or an empty
     *         {@link OptionalInt} for the {@code app/cbl/CBSTM03A.CBL:923} shape
     */
    public OptionalInt getTiming() {
        return timing == null ? OptionalInt.empty() : OptionalInt.of(timing);
    }

    /**
     * Reports whether a {@code TIMING} value is present.
     *
     * @return {@code true} for the eight standard sites, {@code false} for the {@code CBSTM03A}
     *         shape
     */
    public boolean hasTiming() {
        return timing != null;
    }

    /**
     * Returns the free-text detail describing why the abend was raised.
     *
     * <p>Typically the message the COBOL displays immediately before the abend, together with the
     * file status that the caller has already formatted.</p>
     *
     * @return the detail text, or {@link Optional#empty()} when none was supplied or the supplied
     *         text was blank
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Reports whether free-text detail is present.
     *
     * @return {@code true} when a non-blank reason was supplied, {@code false} otherwise
     */
    public boolean hasReason() {
        return reason != null;
    }

    /**
     * Validates the program name.
     *
     * <p>Written out rather than delegated so the guard, and the branch it creates, are visible at
     * the point of use. A missing or empty program name would defeat the whole purpose of carrying
     * it - identifying which of the nine abend sites fired - so it is rejected rather than
     * defaulted.</p>
     *
     * @param program the candidate program name
     * @return {@code program} unchanged, never normalised or trimmed
     * @throws NullPointerException     if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is empty or contains only whitespace
     */
    private static String requireProgram(String program) {
        if (program == null) {
            throw new NullPointerException(
                    "program must not be null: name the abending COBOL program, for example "
                            + "\"CBACT01C\"");
        }
        if (program.isBlank()) {
            throw new IllegalArgumentException(
                    "program must not be blank: name the abending COBOL program, for example "
                            + "\"CBACT01C\"");
        }
        return program;
    }

    /**
     * Treats a {@code null} or blank reason as no reason at all.
     *
     * <p>The reason has no counterpart in {@code CALL 'CEE3ABD'}, which takes no such argument, so
     * collapsing blank to absent cannot affect parity. It keeps {@link #getMessage()} free of a
     * dangling separator and makes {@link #hasReason()} mean exactly one thing. Non-blank text is
     * returned untouched - never trimmed, never reformatted.</p>
     *
     * @param reason the candidate detail text, possibly {@code null}
     * @return the text unchanged, or {@code null} when it is {@code null} or blank
     */
    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason;
    }

    /**
     * Builds the detail message handed to {@link RuntimeException}, so that
     * {@link #getMessage()} is fixed at construction time and identical for identical inputs.
     *
     * <p>The layout is the display literal, then the program name, then {@code RETURN-CODE=} and the
     * code. {@code ABCODE=} and {@code TIMING=} follow, each only when the abending paragraph
     * actually set that argument, and a {@code -} separator with the detail text comes last when
     * detail text was supplied. So a standard site reads
     * {@code ABENDING PROGRAM CBACT01C RETURN-CODE=12 ABCODE=999 TIMING=0 - ERROR OPENING ACCTFILE}
     * while the {@code CBSTM03A} site reads
     * {@code ABENDING PROGRAM CBSTM03A RETURN-CODE=12 - ERROR READING XREFFILE}. Omitting the abend
     * parameters there is intentional: the divergence is then legible in a log line and not only
     * through {@link #hasAbendCode()}.</p>
     *
     * <p>Every segment is formatted with {@link Locale#ROOT} so the text never varies with the
     * platform locale.</p>
     *
     * @param program    the validated, non-blank program name
     * @param returnCode the return code
     * @param abendCode  the {@code ABCODE} argument, or {@code null} when absent
     * @param timing     the {@code TIMING} argument, or {@code null} when absent
     * @param reason     the already-normalised detail text, or {@code null} when absent
     * @return the composed, locale-independent message
     */
    private static String composeMessage(String program,
                                         int returnCode,
                                         Integer abendCode,
                                         Integer timing,
                                         String reason) {
        StringBuilder message = new StringBuilder(ABEND_DISPLAY_TEXT.length() + 64)
                .append(String.format(Locale.ROOT, "%s %s RETURN-CODE=%d", ABEND_DISPLAY_TEXT,
                        program, returnCode));
        if (abendCode != null) {
            message.append(String.format(Locale.ROOT, " ABCODE=%d", abendCode));
        }
        if (timing != null) {
            message.append(String.format(Locale.ROOT, " TIMING=%d", timing));
        }
        if (reason != null) {
            message.append(String.format(Locale.ROOT, " - %s", reason));
        }
        return message.toString();
    }
}
