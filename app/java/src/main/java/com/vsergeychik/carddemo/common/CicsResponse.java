package com.vsergeychik.carddemo.common;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The {@code RESP} and {@code RESP2} pair a CICS file command reports, carried together.
 *
 * <h2>Why the pair travels as one value</h2>
 * Every {@code EXEC CICS} file command in the seventeen online programs names both options -
 * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} - and the programs use both. {@code COACTUPC} captures
 * them on its account read ({@code app/cbl/COACTUPC.cbl:3703-3710}) and on its account rewrite
 * ({@code :4065-4071}), and then renders them side by side into the message the operator reads:
 * {@code MOVE WS-RESP-CD TO ERROR-RESP}, {@code MOVE WS-REAS-CD TO ERROR-RESP2}, and both are strung
 * into {@code '… not found in Acct Master file.Resp:' ERROR-RESP ' Reas:' ERROR-RESP2}
 * ({@code :3722-3729}). {@code COCRDUPC} does the same at {@code :1410}.
 *
 * <p>So a repository that reports only a response has not reported what the COBOL reported. The
 * response says <em>which condition</em>; the reason code says <em>why</em>, and it is the half that
 * distinguishes two occurrences of one condition. Carrying them as a pair rather than as two loose
 * numbers means neither can be supplied without the other being considered.
 *
 * <h2>Why the response is optional and the reason code is not</h2>
 * A <strong>response</strong> may genuinely be unavailable. The batch programs test a two-character
 * {@code FILE STATUS} rather than a {@code RESP}, and only three of those statuses have a single CICS
 * counterpart - {@code '00'}, {@code '10'} and {@code '23'}. {@link FileStatus#cicsRespOfBatchStatus(String)}
 * reports that ambiguity rather than resolving it, and this type carries the same {@link OptionalInt}
 * so the ambiguity is not resolved here either.
 *
 * <p>A <strong>reason code</strong> is never unavailable, because CICS itself reports
 * {@link FileStatus#NO_REASON_CODE} when a condition has no further reason. An {@code int} is therefore
 * the honest shape: {@code 0} means "no further reason", which is a fact, whereas an absent reason code
 * would be a claim about this module rather than about the operation.
 *
 * <h2>What is never put here</h2>
 * A driver's vendor error code, a row count, and a record's byte width are all quantities from systems
 * CICS knows nothing about. Placing one in {@code resp2} produces a screen that looks authoritative and
 * means nothing - and {@code COCRDUPC} renders {@code ERROR-RESP2} verbatim, so a fabricated value would
 * be shown to a user as if it came from CICS. Where the deployment's adapter supplies no reason code,
 * {@link #ofBatchStatus(String)} reports {@link FileStatus#NO_REASON_CODE} and any backend width or count
 * worth keeping is carried in a separately labelled diagnostic instead.
 *
 * <h2>Design constraints observed</h2>
 * A record, so it is immutable and has value semantics. Pure JDK, no Spring import, because
 * {@code common/} is reachable from the web side, the batch side and the copybook models alike. No
 * mutable static state (practice B9, gate G53).
 *
 * @param resp  the CICS {@code RESP} value, or empty where the outcome has no single CICS counterpart
 * @param resp2 the CICS {@code RESP2} reason code; {@link FileStatus#NO_REASON_CODE} where the condition
 *              carries no further reason
 */
public record CicsResponse(OptionalInt resp, int resp2) {

    /**
     * Enforces the pair's invariants at construction.
     *
     * @throws NullPointerException     if {@code resp} is {@code null} - an absent response is an empty
     *                                  {@link OptionalInt}, so that no null escapes the type
     * @throws IllegalArgumentException if {@code resp2} is negative: a CICS reason code is a
     *                                  non-negative quantity, and a negative one would mean the caller
     *                                  put something else there
     */
    public CicsResponse {
        Objects.requireNonNull(resp, "A CICS response is carried as an empty OptionalInt when the "
                + "outcome has no single CICS counterpart, never as null");
        if (resp2 < 0) {
            throw new IllegalArgumentException("A CICS RESP2 reason code is " + resp2
                    + "; reason codes are non-negative, and a negative value means something that is not "
                    + "a reason code - a vendor error number, a row count, a record width - was put "
                    + "where CICS's own reason code belongs. Report "
                    + "FileStatus.NO_REASON_CODE instead, which is what CICS reports when a condition "
                    + "has no further reason.");
        }
    }

    /**
     * The pair for a batch {@code FILE STATUS}, with no reason code reported.
     *
     * <p>The response is the one-to-one translation of the status where one exists and empty where the
     * correspondence is ambiguous, exactly as {@link FileStatus#cicsRespOfBatchStatus(String)} reports it.
     * The reason code is {@link FileStatus#NO_REASON_CODE}, because a file-status outcome carries no CICS
     * reason and inventing one would be a fabrication of the kind this type exists to prevent.
     *
     * @param status the two-character batch file status
     * @return the pair
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly
     *                                  {@link FileStatus#STATUS_LENGTH} characters
     */
    public static CicsResponse ofBatchStatus(String status) {
        return new CicsResponse(FileStatus.cicsRespOfBatchStatus(status), FileStatus.NO_REASON_CODE);
    }

    /**
     * The pair a deployment's adapter actually reported.
     *
     * <p>For a backend that surfaces genuine CICS values. Both are then present and neither is derived,
     * which is the whole point: a reason code that has been derived from a response is not a reason code.
     *
     * @param resp  the reported {@code RESP}
     * @param resp2 the reported {@code RESP2}
     * @return the pair
     * @throws IllegalArgumentException if either value is negative
     */
    public static CicsResponse reported(int resp, int resp2) {
        if (resp < 0) {
            throw new IllegalArgumentException("A CICS RESP value is " + resp
                    + "; responses are non-negative - DFHRESP(NORMAL) is 0 and DFHRESP(NOTFND) is 13");
        }
        return new CicsResponse(OptionalInt.of(resp), resp2);
    }

    /**
     * The pair for an outcome whose response is known and whose condition carries no further reason.
     *
     * @param resp the {@code RESP} value
     * @return the pair, with {@link FileStatus#NO_REASON_CODE} as its reason code
     * @throws IllegalArgumentException if {@code resp} is negative
     */
    public static CicsResponse of(int resp) {
        return reported(resp, FileStatus.NO_REASON_CODE);
    }

    /** The pair for an outcome with neither a response nor a reason - a status CICS has no name for. */
    private static final CicsResponse NONE =
            new CicsResponse(OptionalInt.empty(), FileStatus.NO_REASON_CODE);

    /**
     * The pair reporting neither a response nor a reason.
     *
     * @return a pair whose response is empty and whose reason code is {@link FileStatus#NO_REASON_CODE}
     */
    public static CicsResponse none() {
        return NONE;
    }

    /**
     * Whether a {@code RESP} value is available at all.
     *
     * @return {@code true} when a response was reported or translated
     */
    public boolean hasResp() {
        return resp.isPresent();
    }

    /**
     * Whether the condition carried a reason beyond its response.
     *
     * @return {@code true} when {@link #resp2()} is anything but {@link FileStatus#NO_REASON_CODE}
     */
    public boolean hasReason() {
        return resp2 != FileStatus.NO_REASON_CODE;
    }

    /**
     * The pair rendered as the COBOL renders it, for a message or a log line.
     *
     * <p>Both numbers and nothing else. No record content can reach this, because none is carried.
     *
     * @return for example {@code "Resp:13 Reas:0"}, or {@code "Resp:none Reas:0"} where the outcome has
     *         no single CICS counterpart
     */
    public String describe() {
        return "Resp:" + (resp.isPresent() ? Integer.toString(resp.getAsInt()) : "none")
                + " Reas:" + resp2;
    }
}
