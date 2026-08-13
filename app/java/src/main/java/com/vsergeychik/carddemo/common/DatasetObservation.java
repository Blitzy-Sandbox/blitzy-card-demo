package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * A quantity a dataset operation measured, carried under the name of what it measured.
 *
 * @param label what was measured, as a short noun phrase - never a record's content
 * @param value the measurement
 */
public record DatasetObservation(String label, long value) {
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
     * declares.
     *
     * @param bytes the observed width
     * @return the labelled observation
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public static DatasetObservation recordWidth(long bytes) {
        return new DatasetObservation("stored record width in bytes", bytes);
    }

    public static DatasetObservation matchingRows(long rows) {
        return new DatasetObservation("rows selected by the key", rows);
    }

    public static DatasetObservation replacedRows(long rows) {
        return new DatasetObservation("rows replaced by the write", rows);
    }

    /**
     * The observation rendered for a log line or a message.
     *
     * @return for example {@code "stored record width in bytes = 149"}
     */
    public String describe() {
        return label + " = " + value;
    }
}
