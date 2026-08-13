package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * The single authority on how a physical-sequential dataset's record order is recovered from the backend:
 * the ordinal the deployment's driver presents for each stored record.
 *
 * <p>{@code TRANREPT}'s sorted daily file is in {@code TRAN-CARD-NUM} order while its record image begins
 * with {@code TRAN-ID}, so an image ordering would silently reorder it.
 *
 * @param expression the backend's physical-record ordinal, as a single bare SQL identifier
 */
public record PhysicalSequence(String expression) {
    public static final String EXPRESSION_PROPERTY = "carddemo.physical-sequence.expression";

    public static final String BEAN_NAME = "carddemoPhysicalSequence";

    public static final int MAX_EXPRESSION_LENGTH = 128;

    private static final String LEADING_SPECIALS = "_#@$";

    public static PhysicalSequence of(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Property '" + EXPRESSION_PROPERTY + "' is required and "
                    + "was " + (configured == null ? "not set" : "blank")
                    + ". It names the ordinal this deployment's driver presents for a stored record's "
                    + "physical position, and there is no safe default: SQL guarantees no row order "
                    + "without an ORDER BY, so a physical-sequential dataset read without it arrives in "
                    + "whatever order the backend scanned - and every consumer of DALYTRAN, of the "
                    + "sorted daily file and of the sorted statement file depends on the order the "
                    + "records were written in.");
        }
        return new PhysicalSequence(configured.strip());
    }

    public PhysicalSequence {
        Objects.requireNonNull(expression, "A physical-record ordinal is required; there is no "
                + "unordered physical-sequential read in this module");
        requireBareIdentifier(expression);
    }

    /**
     * The {@code ORDER BY} clause a physical-sequential read appends, ascending.
     *
     * @return {@code " ORDER BY <expression> ASC"}, ready to append to a select
     */
    public String orderByClause() {
        return " ORDER BY " + expression + " ASC";
    }

    /**
     * How this ordinal reads in a diagnostic, naming the key that supplied it.
     *
     * @return for example
     *     {@code "physical-record ordinal '_ROWID_' (carddemo.physical-sequence.expression)"}
     */
    public String describe() {
        return "physical-record ordinal '" + expression + "' (" + EXPRESSION_PROPERTY + ")";
    }

    private static void requireBareIdentifier(String candidate) {
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException(rejection(candidate,
                    "it is empty, and a name has at least one character"));
        }
        if (candidate.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(rejection(candidate, "it is " + candidate.length()
                    + " characters where a name is at most " + MAX_EXPRESSION_LENGTH));
        }
        for (int position = 0; position < candidate.length(); position++) {
            char character = candidate.charAt(position);
            boolean acceptable = position == 0
                    ? isIdentifierInitial(character)
                    : isIdentifierBody(character);
            if (!acceptable) {
                throw new IllegalArgumentException(rejection(candidate,
                        "the character at 0-based position " + position + " is not "
                                + (position == 0
                                        ? "a letter or one of " + LEADING_SPECIALS
                                        : "a letter, a digit or one of " + LEADING_SPECIALS)));
            }
        }
    }

    private static String rejection(String candidate, String because) {
        return "Property '" + EXPRESSION_PROPERTY + "' is '" + candidate + "', which is not a single "
                + "bare SQL identifier: " + because + ". The value is rendered into an ORDER BY clause "
                + "without quoting - a pseudo-column such as a row identifier cannot be delimited - so "
                + "the grammar is what keeps it from reaching the statement as anything other than a "
                + "name.";
    }

    private static boolean isIdentifierInitial(char character) {
        return isAsciiLetter(character) || LEADING_SPECIALS.indexOf(character) >= 0;
    }

    private static boolean isIdentifierBody(char character) {
        return isIdentifierInitial(character) || (character >= '0' && character <= '9');
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }
}
