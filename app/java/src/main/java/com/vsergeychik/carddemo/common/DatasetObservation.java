package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * A quantity a dataset operation measured, carried under the name of what it measured.
 *
 * <h2>Why a labelled quantity rather than a bare number</h2>
 *
 * <p>A CICS operation reports two numbers: {@code RESP}, the condition, and {@code RESP2}, the reason
 * for that condition. Both come from CICS, and {@code app/cbl/COCRDUPC.cbl:1410} renders the second one
 * verbatim onto an operator's screen. That makes {@code RESP2} a tempting place to put any other number
 * that happens to be interesting at the moment a failure is reported - the width a row turned out to be,
 * the number of rows an {@code UPDATE} affected, a driver's own error number - and every one of those
 * substitutions produces a screen that looks authoritative and means nothing, because the reader has no
 * way to know the number is not the reason code it is labelled as.
 *
 * <p>This type is where such a number goes instead. It travels beside the response pair rather than
 * inside it, and it carries the name of the quantity with it, so a reader of a log line or a test
 * assertion is told <em>what</em> was measured rather than being left to infer it from context. A
 * {@code RESP2} of {@code 149} is indistinguishable from a genuine reason code; a
 * {@code "record width = 149 byte(s)"} is not.
 *
 * <h2>Carries no record content</h2>
 *
 * <p>A label and a number. There is no span for a key, an image or a field value, which is what makes it
 * safe to render anywhere a status is rendered - the property {@link SensitiveDiagnostics} exists to
 * protect, obtained here by construction rather than by redaction.
 *
 * @param label what was measured, as a short noun phrase - never a record's content
 * @param value the measurement
 */
public record DatasetObservation(String label, long value) {

    /**
     * Enforces the invariants at construction.
     *
     * @throws NullPointerException     if {@code label} is {@code null}
     * @throws IllegalArgumentException if {@code label} is blank, or if {@code value} is negative
     */
    public DatasetObservation {
        Objects.requireNonNull(label, "An observation is named by what it measured; an unlabelled "
                + "number is the very thing this type exists to prevent");
        if (label.isBlank()) {
            throw new IllegalArgumentException("An observation's label must say what was measured; a "
                    + "blank one leaves the number as anonymous as a fabricated reason code.");
        }
        if (value < 0) {
            throw new IllegalArgumentException("An observation's value is " + value
                    + "; the quantities this type carries - a record width, a row count - are "
                    + "non-negative, so a negative one means something else was measured.");
        }
        label = DiagnosticText.singleLine(label);
    }

    /**
     * The width a stored record image turned out to be.
     *
     * <p>For the case a fixed-width dataset cannot serve: a row that is not the width its copybook
     * declares. The width belongs in the diagnostic because it is what tells an operator whether the
     * backend is trimming, truncating or serving a different layout altogether - and it belongs
     * <em>here</em> rather than in {@code RESP2} because CICS reports {@code LENGERR} for the condition
     * and does not report the width as its reason.
     *
     * @param bytes the observed width
     * @return the labelled observation
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public static DatasetObservation recordWidth(long bytes) {
        return new DatasetObservation("stored record width in bytes", bytes);
    }

    /**
     * How many rows a key selected.
     *
     * <p>For a keyed operation that found more or fewer rows than the key names. A KSDS primary key is
     * unique, so any count but one says the relation is not the dataset the copybook describes, and the
     * count is what says how badly.
     *
     * @param rows the observed count
     * @return the labelled observation
     * @throws IllegalArgumentException if {@code rows} is negative
     */
    public static DatasetObservation matchingRows(long rows) {
        return new DatasetObservation("rows selected by the key", rows);
    }

    /**
     * How many rows a write actually changed.
     *
     * @param rows the observed count
     * @return the labelled observation
     * @throws IllegalArgumentException if {@code rows} is negative
     */
    public static DatasetObservation replacedRows(long rows) {
        return new DatasetObservation("rows replaced by the write", rows);
    }

    /**
     * The observation rendered for a log line or a message.
     *
     * <p>The unit lives in the label rather than in this method, so the rendering carries no rule about
     * which quantity gets which suffix and there is no branch here to get wrong.
     *
     * @return for example {@code "stored record width in bytes = 149"}
     */
    public String describe() {
        return label + " = " + value;
    }
}
