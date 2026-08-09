package com.vsergeychik.carddemo.common;

import java.util.Objects;

/**
 * The single authority on how a <strong>physical-sequential</strong> dataset's record order is recovered
 * from the backend: the ordinal the deployment's driver presents for each stored record.
 *
 * <h2>Why this exists</h2>
 * A physical-sequential dataset has no key. Its records are in the order they were written, a COBOL
 * {@code READ} returns them in that order, and every consumer in this estate depends on it:
 * <ul>
 *   <li>{@code app/jcl/POSTTRAN.jcl} feeds {@code DALYTRAN} to {@code CBTRN02C}, which posts or rejects
 *       each record as it arrives and writes its rejects in the same sequence;</li>
 *   <li>{@code app/jcl/TRANREPT.jcl:46} sorts the daily file {@code SORT FIELDS=(TRAN-CARD-NUM,A)} and
 *       {@code CBTRN03C} then groups and subtotals its report <em>by account as the records arrive</em>
 *       ({@code app/cbl/CBTRN03C.cbl:168-207}), so the physical order carries the grouping;</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:53-54} sorts into {@code SORTOUT} and {@code :61} {@code REPRO}s that
 *       file record for record, and {@code CBSTM03A}'s card-break grouping is correct only because the
 *       sequence is card number then transaction id.</li>
 * </ul>
 *
 * <p>SQL guarantees no row order without an {@code ORDER BY}. A read composed as
 * {@code SELECT * FROM <relation>} is therefore <em>arbitrarily</em> ordered, and a report built from it
 * would carry the right rows with the wrong totals - which is the worst kind of wrong, because nothing
 * fails and nobody is told.
 *
 * <h2>Why the record image is not what a physical-sequential read orders by</h2>
 * {@link DatasetRelation#selectAllInPhysicalSequence(PhysicalSequence)} exists precisely because
 * {@link DatasetRelation#selectAllAscending(String)} must <strong>not</strong> be used here. Ordering by
 * the record image orders by the record's leading bytes, which for a keyed dataset is its key and is the
 * order a browse wants - and for a physical-sequential dataset is <em>a different order from the one the
 * file is in</em>. {@code TRANREPT}'s sorted daily file is in {@code TRAN-CARD-NUM} order while its
 * record image begins with {@code TRAN-ID}, so an image ordering would silently reorder it. So a
 * physical-sequential read is ordered by an ordinal that stands for the record's <em>position</em> and by
 * nothing derived from its content.
 *
 * <h2>Why it is configuration and not a constant</h2>
 * Because the ordinal is a property of the deployment's driver, not of this module. The technical plan
 * records that the data-access driver is a deployment-time input and that production connectivity cannot
 * be exercised from this build (residual risk R-E). One gateway surfaces a physical-sequential dataset's
 * position as a relative-record-number column, another as a row identifier, another as a generated
 * sequence; all are real, and all are reachable with the same Java. What must never happen is for the
 * ordinal to be implicit, so {@value #EXPRESSION_PROPERTY} carries <strong>no default</strong>: a
 * deployment that never stated it fails at startup naming the key, rather than starting and reading
 * every physical-sequential dataset in whatever order its backend happened to scan.
 *
 * <h2>Why the expression is rendered verbatim and not as a delimited identifier</h2>
 * Every other name this module composes into a statement is delimited - {@link DatasetRelation#delimit(String)}
 * wraps a dataset name and a record-image column name in double quotes, because a mainframe dataset name
 * contains dots an SQL parser would otherwise read as qualifiers. A record ordinal is the one name that
 * <strong>cannot</strong> be delimited, and this was measured rather than assumed: against H2 2.3.232,
 * {@code ORDER BY _ROWID_ ASC} resolves and yields insertion order while {@code ORDER BY "_ROWID_" ASC}
 * fails with {@code Column "_ROWID_" not found}. A pseudo-column is not a named column of the relation, so
 * quoting it turns a working ordering into an error - and a pseudo-column is exactly what a backend that
 * exposes physical position without adding a column to the row offers.
 *
 * <p>What makes rendering it verbatim safe is that it is <strong>not free text</strong>. The value is
 * grammar-checked by {@link #of(String)} to a single bare identifier - one leading letter, underscore or
 * national character followed by up to {@value #MAX_EXPRESSION_LENGTH} of the same plus digits - so it
 * cannot carry whitespace, a quote, a comma, a parenthesis, a semicolon, a comment marker or an operator,
 * and there is no lexical route from it to a second clause or a second statement. It is operator
 * configuration, at the same trust level as the dataset names this module already renders into statement
 * text and the JDBC URL it connects with, and it is held to a stricter grammar than either.
 *
 * <p>Because it is rendered without quotes, the backend applies its own identifier folding to it. A
 * deployment whose ordinal is a real column with a case-sensitive lower-case name must therefore expose
 * it under a name expressible as a bare identifier; every gateway ordinal this estate is expected to meet
 * already is.
 *
 * <h2>What this type deliberately does not decide</h2>
 * It names <em>one</em> ordinal for the whole deployment rather than one per dataset, for the same reason
 * {@link RecordImageForm} names one representation: a gateway is one gateway, and two datasets that
 * answered the question differently would both look correct in isolation with at most one of them right.
 * It knows no dataset name, no record width and no column position - those belong to configuration and to
 * {@link DatasetRelation}.
 *
 * <p>It also does not apply to a <strong>keyed</strong> dataset. A KSDS browse is ordered by key, which
 * {@link DatasetRelation#selectAllAscending(String)} and its siblings already express over the
 * record-image column, and a keyed read is confined by a {@code LIKE} predicate over the key span. Using
 * a physical ordinal there would substitute storage order for key order.
 *
 * <h2>Design constraints observed</h2>
 * A {@code record}, so it is immutable and has value semantics. Pure JDK, with no Spring import, because
 * everything in {@code common/} is reachable from the web side, the batch side and the copybook models
 * alike. No mutable static state (practice B9, gate G53), so one instance is safely shared by every
 * repository, reader and utility port. No data-definition statement is implied or issued: this names a
 * column the deployment already presents and creates nothing (gate G44).
 *
 * @param expression the backend's physical-record ordinal, as a single bare SQL identifier
 * @see DatasetRelation#selectAllInPhysicalSequence(PhysicalSequence)
 * @see RecordImageForm
 */
public record PhysicalSequence(String expression) {

    /**
     * The configuration key that supplies the ordinal. Required, and carries no default.
     *
     * <p>Bound by {@code config/DataSourceConfig} with a bare placeholder, so removing it fails the
     * application context at startup naming this exact key.
     */
    public static final String EXPRESSION_PROPERTY = "carddemo.physical-sequence.expression";

    /** The bean name the configuration publishes the resolved ordinal under. */
    public static final String BEAN_NAME = "carddemoPhysicalSequence";

    /**
     * The longest ordinal name accepted, {@value}.
     *
     * <p>Generous enough for any real column or pseudo-column name and bounded so that a configuration
     * value that is plainly not an identifier - a pasted clause, a whole statement - is refused by length
     * before anything else has to reason about it.
     */
    public static final int MAX_EXPRESSION_LENGTH = 128;

    /**
     * The characters an ordinal name may begin with, besides an ASCII letter: the SQL underscore and the
     * three national characters {@code #}, {@code @} and {@code $} that mainframe-facing backends use.
     */
    private static final String LEADING_SPECIALS = "_#@$";

    /**
     * Resolves the configured ordinal.
     *
     * @param configured the property's value
     * @return the resolved ordinal; never {@code null}
     * @throws IllegalArgumentException if {@code configured} is {@code null}, blank, longer than
     *                                  {@value #MAX_EXPRESSION_LENGTH} characters, or is not a single
     *                                  bare SQL identifier
     */
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

    /**
     * Applies the grammar to whatever reached the canonical constructor.
     *
     * <p>Enforced here rather than only in {@link #of(String)} because a record's canonical constructor
     * is public: a call site that bypassed the factory would otherwise put unchecked text straight into
     * an {@code ORDER BY} clause.
     *
     * @throws NullPointerException     if {@code expression} is {@code null}
     * @throws IllegalArgumentException if {@code expression} is blank, longer than
     *                                  {@value #MAX_EXPRESSION_LENGTH} characters, or is not a single
     *                                  bare SQL identifier
     */
    public PhysicalSequence {
        Objects.requireNonNull(expression, "A physical-record ordinal is required; there is no "
                + "unordered physical-sequential read in this module");
        requireBareIdentifier(expression);
    }

    /**
     * The {@code ORDER BY} clause a physical-sequential read appends, ascending.
     *
     * <p>Ascending because a physical ordinal increases with position: the first record written has the
     * lowest ordinal, which is the record a COBOL {@code READ} returns first. There is no descending
     * form, because no program in this estate reads a physical-sequential dataset backwards - the two
     * {@code READPREV} sites are both keyed browses ({@code app/cbl/COCRDLIC.cbl:1273},
     * {@code app/cbl/COTRN00C.cbl}).
     *
     * @return {@code " ORDER BY <expression> ASC"}, ready to append to a select
     */
    public String orderByClause() {
        return " ORDER BY " + expression + " ASC";
    }

    /**
     * How this ordinal reads in a diagnostic, naming the key that supplied it.
     *
     * @return for example {@code "physical-record ordinal '_ROWID_' (carddemo.physical-sequence.expression)"}
     */
    public String describe() {
        return "physical-record ordinal '" + expression + "' (" + EXPRESSION_PROPERTY + ")";
    }

    /**
     * Applies the grammar: a single bare SQL identifier, at most {@value #MAX_EXPRESSION_LENGTH}
     * characters, naming the offending character's position when it refuses one.
     *
     * @param candidate the stripped value
     * @throws IllegalArgumentException if the value is not such an identifier
     */
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

    /**
     * The message every grammar rejection carries, naming the property and the reason.
     *
     * @param candidate the refused value
     * @param because   why it was refused
     * @return the message
     */
    private static String rejection(String candidate, String because) {
        return "Property '" + EXPRESSION_PROPERTY + "' is '" + candidate + "', which is not a single "
                + "bare SQL identifier: " + because + ". The value is rendered into an ORDER BY clause "
                + "without quoting - a pseudo-column such as a row identifier cannot be delimited - so "
                + "the grammar is what keeps it from reaching the statement as anything other than a "
                + "name.";
    }

    /** Whether a character may begin an ordinal name. */
    private static boolean isIdentifierInitial(char character) {
        return isAsciiLetter(character) || LEADING_SPECIALS.indexOf(character) >= 0;
    }

    /** Whether a character may appear after the first in an ordinal name. */
    private static boolean isIdentifierBody(char character) {
        return isIdentifierInitial(character) || (character >= '0' && character <= '9');
    }

    /** Whether a character is an ASCII letter; deliberately not {@code Character.isLetter}. */
    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }
}
