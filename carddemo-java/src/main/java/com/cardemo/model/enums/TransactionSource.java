package com.cardemo.model.enums;

/**
 * Type-safe representation of the CardDemo daily-transaction origination source.
 *
 * <p>This enum is the Java 25 replacement for the free-text COBOL
 * {@code DALYTRAN-SOURCE} field that records where each daily transaction
 * originated in the legacy AWS CardDemo mainframe application. The field is
 * declared as {@code 05 DALYTRAN-SOURCE PIC X(10)} within the
 * {@code DALYTRAN-RECORD} layout ({@code app/cpy/CVTRA06Y.cpy} line 8, byte
 * positions 23-32 of the 350-byte record) and is carried verbatim into the
 * persisted transaction record by the batch posting program
 * {@code app/cbl/CBTRN02C.cbl} (paragraph {@code 2000-POST-TRANSACTION},
 * line 428: {@code MOVE DALYTRAN-SOURCE TO TRAN-SOURCE}). The COBOL never
 * branches on the value &mdash; it is a descriptive provenance token copied
 * through to the transaction store unchanged.</p>
 *
 * <p><strong>Authoritative, exhaustive value set.</strong> A full sweep of the
 * COBOL estate confirms that {@code DALYTRAN-SOURCE} (and the matching
 * {@code TRAN-SOURCE} in {@code app/cpy/CVTRA05Y.cpy}) is a plain
 * {@code PIC X(10)} field with no {@code 88}-level condition names and no
 * {@code VALUE} enumeration anywhere; the value set is therefore defined solely
 * by the canonical fixture {@code app/data/ASCII/dailytran.txt}. Across all 300
 * records of that fixture exactly two distinct source tokens occur:</p>
 *
 * <ul>
 *   <li>{@code "POS TERM  "} &mdash; trimmed token {@code "POS TERM"}, present
 *       on 250 of the 300 records (point-of-sale terminal purchases).</li>
 *   <li>{@code "OPERATOR  "} &mdash; trimmed token {@code "OPERATOR"}, present
 *       on the remaining 50 records (operator-entered returns).</li>
 * </ul>
 *
 * <p>Per the migration's Minimal Change Clause the value set is reproduced
 * <strong>exactly</strong>: there are precisely these two sources and no others.
 * Speculative channels (for example {@code ATM}, {@code WEB}, {@code MOBILE} or
 * {@code BATCH}) are deliberately <strong>not</strong> introduced, because they
 * appear in neither the COBOL programs nor the fixture data and adding them
 * would break the external interface contract. The pairing observed in the
 * fixture between source and transaction-type code ({@code "POS TERM"} with type
 * {@code '01'} purchases, {@code "OPERATOR"} with type {@code '03'} returns) is
 * incidental fixture data, <em>not</em> a COBOL business rule, and is
 * intentionally not encoded here so this enum stays a pure value catalogue.</p>
 *
 * <p><strong>External-interface contract.</strong> The stored {@link #getCode()
 * code} preserves the exact trimmed token, including the significant internal
 * space in {@code "POS TERM"}. Because the underlying field is a fixed-width
 * {@code PIC X(10)} value, input arrives right-padded with spaces;
 * {@link #fromCode(String)} therefore trims before matching, while
 * {@link #toFixedWidth()} reproduces the byte-faithful 10-character padded form
 * for output, so the value round-trips against the external interface contract.</p>
 *
 * <p><strong>Role in the architecture.</strong> This enum is a deliberately
 * dependency-free leaf: it depends on nothing beyond {@code java.lang}, holds no
 * mutable state and performs no I/O. It is consumed by the
 * {@code DailyTransaction} / {@code Transaction} entity mapping, the
 * daily-transaction batch reader/processor/writer, and any DTO that exposes the
 * transaction source.</p>
 *
 * <p><strong>Traceability:</strong> derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL source is read-only reference and is
 * never copied into this repository.</p>
 */
public enum TransactionSource {

    /**
     * Point-of-sale terminal source. The most common origination source: 250 of
     * the 300 fixture records carry this value (point-of-sale terminal
     * purchases). Maps the COBOL {@code DALYTRAN-SOURCE} token {@code "POS TERM"}
     * (stored on disk padded as {@code "POS TERM  "}).
     */
    // COBOL substitution: DALYTRAN-SOURCE 'POS TERM  ' (PIC X(10)) from
    // app/data/ASCII/dailytran.txt (250/300 records); internal space preserved.
    POS_TERM("POS TERM"),

    /**
     * Operator-entered source. The remaining 50 of the 300 fixture records carry
     * this value (operator-entered returns). Maps the COBOL
     * {@code DALYTRAN-SOURCE} token {@code "OPERATOR"} (stored on disk padded as
     * {@code "OPERATOR  "}).
     */
    // COBOL substitution: DALYTRAN-SOURCE 'OPERATOR  ' (PIC X(10)) from
    // app/data/ASCII/dailytran.txt (50/300 records).
    OPERATOR("OPERATOR");

    /**
     * The exact trimmed source token, preserving byte fidelity with the COBOL
     * {@code DALYTRAN-SOURCE} / {@code TRAN-SOURCE} field.
     *
     * <p>The meaningful token is stored without its fixed-width padding (for
     * example {@code "POS TERM"} rather than {@code "POS TERM  "}), but the
     * significant internal space of {@code "POS TERM"} is retained. The padded
     * 10-character external form is reconstructed on demand by
     * {@link #toFixedWidth()}.</p>
     */
    // COBOL substitution: the PIC X(10) DALYTRAN-SOURCE field is represented by
    // its trimmed token; toFixedWidth() restores the 10-byte padded form.
    private final String code;

    /**
     * Binds each constant to its byte-faithful COBOL source token. Enum
     * constructors are implicitly private.
     *
     * @param code the exact trimmed token persisted in the COBOL
     *             {@code DALYTRAN-SOURCE} / {@code TRAN-SOURCE} field
     */
    TransactionSource(final String code) {
        this.code = code;
    }

    /**
     * Returns the exact trimmed source token for this constant.
     *
     * @return {@code "POS TERM"} (internal space preserved) for {@link #POS_TERM}
     *         or {@code "OPERATOR"} for {@link #OPERATOR}
     */
    public String getCode() {
        return code;
    }

    /**
     * Resolves a {@code TransactionSource} from a raw COBOL
     * {@code DALYTRAN-SOURCE} value.
     *
     * <p>Because the underlying field is a fixed-width {@code PIC X(10)} value,
     * the raw input typically arrives right-padded with spaces (for example
     * {@code "POS TERM  "}). The value is therefore trimmed before matching, and
     * comparison against the stored token is exact and case-sensitive, mirroring
     * the byte-exact COBOL data (which is upper-case). Both the padded form
     * {@code "POS TERM  "} and the already-trimmed token {@code "POS TERM"}
     * resolve to {@link #POS_TERM}. A {@code null}, blank or unrecognized value
     * is rejected, because the field only ever holds {@code "POS TERM"} or
     * {@code "OPERATOR"}.</p>
     *
     * @param raw the raw source value, optionally space-padded, to resolve
     * @return the matching {@code TransactionSource}
     * @throws IllegalArgumentException if {@code raw} is {@code null} or, after
     *         trimming, matches no known transaction source
     */
    public static TransactionSource fromCode(final String raw) {
        if (raw != null) {
            final String token = raw.trim();
            for (final TransactionSource source : values()) {
                if (source.code.equals(token)) {
                    return source;
                }
            }
        }
        throw new IllegalArgumentException("Unknown transaction source: '" + raw + "'");
    }

    /**
     * Returns the byte-faithful fixed-width {@code PIC X(10)} external form of
     * this source token.
     *
     * <p>The trimmed {@link #getCode() token} is right-padded with spaces to the
     * 10-character width of the COBOL {@code DALYTRAN-SOURCE} /
     * {@code TRAN-SOURCE} field, reproducing the exact on-disk representation
     * (for example {@code "POS TERM  "} and {@code "OPERATOR  "}). This is the
     * inverse of the padding-tolerant {@link #fromCode(String)} and exists so
     * writers can round-trip the value to the external interface contract
     * without re-deriving the field width.</p>
     *
     * @return the 10-character, left-justified, space-padded source code
     */
    public String toFixedWidth() {
        // COBOL substitution: reproduce the PIC X(10) fixed-width external form
        // (left-justified, space-filled) of the DALYTRAN-SOURCE / TRAN-SOURCE field.
        return String.format("%-10s", code);
    }
}
