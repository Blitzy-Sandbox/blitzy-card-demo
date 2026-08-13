package com.vsergeychik.carddemo.common;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The {@code RESP} and {@code RESP2} pair a CICS file command reports, carried together.
 *
 * <p>A reason code is never unavailable, because CICS itself reports {@link FileStatus#NO_REASON_CODE} when
 * a condition has no further reason.
 *
 * @param resp the CICS {@code RESP} value, or empty where the outcome has no single CICS counterpart
 * @param resp2 the CICS {@code RESP2} reason code; {@link FileStatus#NO_REASON_CODE} where the condition
 *     carries no further reason
 */
public record CicsResponse(OptionalInt resp, int resp2) {
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
     * @param status the two-character batch file status
     * @return the pair
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly {@link FileStatus#STATUS_LENGTH}
     *     characters
     */
    public static CicsResponse ofBatchStatus(String status) {
        return new CicsResponse(FileStatus.cicsRespOfBatchStatus(status), FileStatus.NO_REASON_CODE);
    }

    /**
     * The pair a deployment's adapter actually reported.
     *
     * @param resp the reported {@code RESP}
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
     * @return for example {@code "Resp:13 Reas:0"}, or {@code "Resp:none Reas:0"} where the outcome has no
     *     single CICS counterpart
     */
    public String describe() {
        return "Resp:" + (resp.isPresent() ? Integer.toString(resp.getAsInt()) : "none")
                + " Reas:" + resp2;
    }
}
