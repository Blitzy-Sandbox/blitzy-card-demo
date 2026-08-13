package com.vsergeychik.carddemo.common;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Java equivalent of the COBOL {@code CALL 'CEE3ABD'} abend service.
 *
 * <p>Making the abend parameters mandatory would be worse still: the {@code CBSTM03A} translation would
 * have to fabricate a {@code 999} the COBOL never produces.
 */
public final class AbendException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * The literal written by {@code DISPLAY 'ABENDING PROGRAM'}, identical at all nine call sites.
     */
    public static final String ABEND_DISPLAY_TEXT = "ABENDING PROGRAM";

    /**
     * The value the eight standard paragraphs move into {@code ABCODE}: {@code MOVE 999 TO ABCODE}.
     *
     * <p>Absent by design at {@code app/cbl/CBSTM03A.CBL:923}, which never sets it.
     */
    public static final int STANDARD_ABEND_CODE = 999;

    /**
     * The value the eight standard paragraphs move into {@code TIMING}: {@code MOVE 0 TO TIMING}.
     *
     * <p>Absent by design at {@code app/cbl/CBSTM03A.CBL:923}, which never sets it.
     */
    public static final int STANDARD_TIMING = 0;

    /**
     * Successful completion: {@code 88 APPL-AOK VALUE 0.} Declared on
     * {@code 01 APPL-RESULT PIC S9(9) COMP.} in all eight programs that carry the standard abend paragraph.
     */
    public static final int RETURN_CODE_OK = 0;

    public static final int RETURN_CODE_WARNING = 4;

    public static final int RETURN_CODE_ASSUMED_FAILURE = 8;

    public static final int RETURN_CODE_IO_ERROR = 12;

    /**
     * End of file: {@code 88 APPL-EOF VALUE 16.} Recorded exactly as the COBOL declares it, on
     * {@code 01 APPL-RESULT PIC S9(9) COMP.} in all eight programs that carry the standard abend paragraph.
     */
    public static final int RETURN_CODE_END_OF_FILE = 16;

    private final String program;

    private final int returnCode;

    private final Integer abendCode;

    private final Integer timing;

    private final String reason;

    private final String sourceDiagnostic;

    private AbendException(String program,
                           int returnCode,
                           Integer abendCode,
                           Integer timing,
                           String reason,
                           String sourceDiagnostic,
                           Throwable cause) {
        super(composeMessage(requireProgram(program), returnCode, abendCode, timing,
                normalizeReason(reason)), cause);
        this.program = program;
        this.returnCode = returnCode;
        this.abendCode = abendCode;
        this.timing = timing;
        this.reason = normalizeReason(reason);
        this.sourceDiagnostic = normalizeReason(sourceDiagnostic);
    }

    /**
     * Creates the abend raised by the eight standard paragraphs, supplying {@link #STANDARD_ABEND_CODE} and
     * {@link #STANDARD_TIMING} exactly as their {@code MOVE 999 TO ABCODE} and {@code MOVE 0 TO TIMING}
     * statements do.
     *
     * @param program the abending program's COBOL {@code PROGRAM-ID}, for example {@code "CBACT01C"}; must
     *     be non-null and non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}, typically
     *     {@link #RETURN_CODE_IO_ERROR} or {@link #RETURN_CODE_ASSUMED_FAILURE}
     * @return a new abend carrying both {@code CEE3ABD} arguments
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException standard(String program, int returnCode) {
        return standard(program, returnCode, null, null);
    }

    /**
     * Creates the abend raised by the eight standard paragraphs, with the detail text the COBOL displays
     * immediately before the abend.
     *
     * @param program the abending program's COBOL {@code PROGRAM-ID}; must be non-null and non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}
     * @param reason free-text detail such as {@code "ERROR OPENING ACCTFILE"}, optionally with the
     *     already-formatted file status appended; may be {@code null}, and a blank string is treated as absent
     * @return a new abend carrying both {@code CEE3ABD} arguments
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException standard(String program, int returnCode, String reason) {
        return standard(program, returnCode, reason, null);
    }

    /**
     * Creates the abend raised by the eight standard paragraphs, retaining the underlying failure.
     *
     * @param program the abending program's COBOL {@code PROGRAM-ID}; must be non-null and non-blank
     * @param returnCode the value the program placed in {@code APPL-RESULT}
     * @param reason free-text detail; may be {@code null}, and a blank string is treated as absent
     * @param cause the underlying failure, or {@code null} when there is none
     * @return a new abend carrying both {@code CEE3ABD} arguments
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException standard(String program, int returnCode, String reason,
                                          Throwable cause) {
        return new AbendException(program, returnCode, STANDARD_ABEND_CODE, STANDARD_TIMING, reason,
                null, cause);
    }

    /**
     * Creates the abend raised by {@code app/cbl/CBSTM03A.CBL:921-923}, which sets neither {@code ABCODE}
     * nor {@code TIMING}.
     *
     * <p>Do not substitute {@link #standard(String, int)} for convenience: that would fabricate a
     * {@code 999} the COBOL never produces.
     *
     * @param program the abending program's COBOL {@code PROGRAM-ID}, in practice {@code "CBSTM03A"}; must
     *     be non-null and non-blank
     * @param returnCode the return code the program intends
     * @return a new abend with no {@code ABCODE} and no {@code TIMING}
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException withoutAbendParameters(String program, int returnCode) {
        return withoutAbendParameters(program, returnCode, null, null);
    }

    /**
     * Creates the {@code CBSTM03A}-shaped abend with the detail text the program displays immediately
     * before it.
     *
     * @param program the abending program's COBOL {@code PROGRAM-ID}; must be non-null and non-blank
     * @param returnCode the return code the program intends
     * @param reason free-text detail; may be {@code null}, and a blank string is treated as absent
     * @return a new abend with no {@code ABCODE} and no {@code TIMING}
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException withoutAbendParameters(String program, int returnCode,
                                                        String reason) {
        return withoutAbendParameters(program, returnCode, reason, null);
    }

    /**
     * Creates the {@code CBSTM03A}-shaped abend, retaining the underlying failure.
     *
     * @param program the abending program's COBOL {@code PROGRAM-ID}; must be non-null and non-blank
     * @param returnCode the return code the program intends
     * @param reason free-text detail; may be {@code null}, and a blank string is treated as absent
     * @param cause the underlying failure, or {@code null} when there is none
     * @return a new abend with no {@code ABCODE} and no {@code TIMING}
     * @throws NullPointerException if {@code program} is {@code null}
     * @throws IllegalArgumentException if {@code program} is blank
     */
    public static AbendException withoutAbendParameters(String program, int returnCode,
                                                        String reason, Throwable cause) {
        return new AbendException(program, returnCode, null, null, reason, null, cause);
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
     * @return the return code, one of {@link #RETURN_CODE_OK}, {@link #RETURN_CODE_WARNING},
     *     {@link #RETURN_CODE_ASSUMED_FAILURE}
     */
    public int getReturnCode() {
        return returnCode;
    }

    /**
     * Returns the {@code ABCODE} argument of {@code CALL 'CEE3ABD'}, if the abending paragraph set one.
     *
     * @return {@link #STANDARD_ABEND_CODE} for the eight standard sites, or an empty {@link OptionalInt}
     *     for the {@code app/cbl/CBSTM03A.CBL:923} shape, which sets no abend code at all
     */
    public OptionalInt getAbendCode() {
        return abendCode == null ? OptionalInt.empty() : OptionalInt.of(abendCode);
    }

    public boolean hasAbendCode() {
        return abendCode != null;
    }

    /**
     * Returns the {@code TIMING} argument of {@code CALL 'CEE3ABD'}, if the abending paragraph set one.
     *
     * @return {@link #STANDARD_TIMING} for the eight standard sites, or an empty {@link OptionalInt} for
     *     the {@code app/cbl/CBSTM03A.CBL:923} shape
     */
    public OptionalInt getTiming() {
        return timing == null ? OptionalInt.empty() : OptionalInt.of(timing);
    }

    public boolean hasTiming() {
        return timing != null;
    }

    /**
     * Returns the free-text detail describing why the abend was raised.
     *
     * @return the detail text, or {@link Optional#empty()} when none was supplied or the supplied text was
     *     blank
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    public boolean hasReason() {
        return reason != null;
    }

    /**
     * Returns this abend carrying the fixed-width diagnostic the COBOL itself transmits immediately before
     * abending.
     *
     * <p>Exactly one paragraph in the estate transmits one: {@code app/cbl/COCRDSLC.cbl:865-869} sends
     * {@code ABEND-DATA} - {@code CSMSG02Y}'s {@code X(4)} + {@code X(8)} + {@code X(50)} + {@code X(72)},
     * 134 bytes - to the terminal, and only then abends.
     *
     * @param diagnostic the source-authored fixed-width diagnostic; {@code null} or blank leaves the abend
     *     without one
     * @return an abend identical to this one but carrying {@code diagnostic}; never {@code null}
     */
    public AbendException withSourceDiagnostic(String diagnostic) {
        return new AbendException(program, returnCode, abendCode, timing, reason, diagnostic,
                getCause());
    }

    /**
     * Returns the fixed-width diagnostic the COBOL transmitted before abending.
     *
     * @return the transmitted diagnostic, or {@link Optional#empty()} when the abending paragraph
     *     transmitted none
     */
    public Optional<String> getSourceDiagnostic() {
        return Optional.ofNullable(sourceDiagnostic);
    }

    /**
     * Reports whether a source-authored diagnostic is present.
     *
     * @return {@code true} when the abending paragraph transmitted one, {@code false} otherwise
     */
    public boolean hasSourceDiagnostic() {
        return sourceDiagnostic != null;
    }

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

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason;
    }

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
