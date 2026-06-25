package com.carddemo.unit.enums;

import com.carddemo.enums.RejectReasonCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure JUnit 5 unit test for {@link RejectReasonCode}, the reject-reason vocabulary
 * produced by the COBOL daily-transaction posting engine {@code CBTRN02C} (paragraph
 * {@code 1500-VALIDATE-TRAN} plus the {@code 2800-UPDATE-ACCOUNT-REC} rewrite failure)
 * at source commit {@code 27d6c6f}.
 *
 * <p>The numeric reason codes and their description strings are
 * <strong>byte-equivalence critical</strong>: they must reproduce the COBOL baseline
 * character-for-character to satisfy Validation Gate 1 (end-to-end byte-equivalence)
 * and Gate 4 (named real-world validation). This test therefore asserts every code
 * and description literal verbatim against the values moved into the COBOL validation
 * trailer ({@code WS-VALIDATION-FAIL-REASON} / {@code WS-VALIDATION-FAIL-REASON-DESC}):</p>
 *
 * <ul>
 *   <li>{@code 100} &rarr; {@code "INVALID CARD NUMBER FOUND"} (CBTRN02C L385-386)</li>
 *   <li>{@code 101} &rarr; {@code "ACCOUNT RECORD NOT FOUND"} (CBTRN02C L397-398)</li>
 *   <li>{@code 102} &rarr; {@code "OVERLIMIT TRANSACTION"} (CBTRN02C L410-411) &mdash;
 *       {@code OVERLIMIT} is a single word</li>
 *   <li>{@code 103} &rarr; {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}
 *       (CBTRN02C L417-418) &mdash; {@code ACCT} is abbreviated, never spelled
 *       {@code ACCOUNT}</li>
 *   <li>{@code 109} &rarr; {@code "ACCOUNT RECORD NOT FOUND"} (CBTRN02C L556-557)</li>
 *   <li>{@code 0} &rarr; {@code ""} (no failure; CBTRN02C L208)</li>
 * </ul>
 *
 * <p>Codes {@code 101} and {@code 109} are <em>distinct</em> COBOL failure points
 * (account read-miss versus account rewrite-miss) that intentionally carry identical
 * description text; the test asserts both their distinctness and their shared text.</p>
 *
 * <p>No Mockito, no Spring, no Jakarta, no I/O &mdash; this is a fast, isolated POJO
 * test. The optional {@code getFormattedCode()} convenience method is exercised only
 * through reflection so the test compiles and runs whether or not the production enum
 * declares it.</p>
 */
class RejectReasonCodeTest {

    // ---------------------------------------------------------------------
    // Per-constant code + byte-exact description assertions (all 6 constants)
    // ---------------------------------------------------------------------

    /** {@code NONE} models "no failure": code {@code 0} with an empty description. */
    @Test
    void none_hasZeroCodeAndEmptyDescription() {
        assertThat(RejectReasonCode.NONE.getCode()).isEqualTo(0);
        assertThat(RejectReasonCode.NONE.getDescription()).isEqualTo("");
    }

    /** {@code CARD_NOT_FOUND}: COBOL code {@code 100}, "INVALID CARD NUMBER FOUND". */
    @Test
    void cardNotFound_hasCode100AndExactDescription() {
        assertThat(RejectReasonCode.CARD_NOT_FOUND.getCode()).isEqualTo(100);
        assertThat(RejectReasonCode.CARD_NOT_FOUND.getDescription())
                .isEqualTo("INVALID CARD NUMBER FOUND");
    }

    /** {@code ACCOUNT_NOT_FOUND}: COBOL code {@code 101}, "ACCOUNT RECORD NOT FOUND". */
    @Test
    void accountNotFound_hasCode101AndExactDescription() {
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND.getCode()).isEqualTo(101);
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND.getDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    /**
     * {@code OVER_CREDIT_LIMIT}: COBOL code {@code 102}, "OVERLIMIT TRANSACTION".
     * {@code OVERLIMIT} is a single word in the COBOL baseline.
     */
    @Test
    void overCreditLimit_hasCode102AndOneWordOverlimitDescription() {
        assertThat(RejectReasonCode.OVER_CREDIT_LIMIT.getCode()).isEqualTo(102);
        assertThat(RejectReasonCode.OVER_CREDIT_LIMIT.getDescription())
                .isEqualTo("OVERLIMIT TRANSACTION");
    }

    /**
     * {@code ACCOUNT_EXPIRED}: COBOL code {@code 103},
     * "TRANSACTION RECEIVED AFTER ACCT EXPIRATION". {@code ACCT} is abbreviated.
     */
    @Test
    void accountExpired_hasCode103AndAbbreviatedAcctDescription() {
        assertThat(RejectReasonCode.ACCOUNT_EXPIRED.getCode()).isEqualTo(103);
        assertThat(RejectReasonCode.ACCOUNT_EXPIRED.getDescription())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    /**
     * {@code ACCOUNT_NOT_FOUND_ON_UPDATE}: COBOL code {@code 109},
     * "ACCOUNT RECORD NOT FOUND" (rewrite-miss in {@code 2800-UPDATE-ACCOUNT-REC}).
     */
    @Test
    void accountNotFoundOnUpdate_hasCode109AndExactDescription() {
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getCode()).isEqualTo(109);
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    // ---------------------------------------------------------------------
    // Distinct-but-identical: 101 vs 109 share description text, differ as constants
    // ---------------------------------------------------------------------

    /**
     * Codes {@code 101} and {@code 109} are two separate COBOL failure points that
     * happen to share the description text "ACCOUNT RECORD NOT FOUND". They must
     * remain distinct enum constants while reporting identical description text.
     */
    @Test
    void accountNotFound_andOnUpdate_areDistinctConstantsSharingDescriptionText() {
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND)
                .isNotSameAs(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE);
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND.getCode()).isEqualTo(101);
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getCode()).isEqualTo(109);
        assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND.getDescription())
                .isEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription());
    }

    // ---------------------------------------------------------------------
    // fromCode(int): known codes resolve to their constant
    // ---------------------------------------------------------------------

    /**
     * {@link RejectReasonCode#fromCode(int)} resolves each known numeric code to its
     * constant. The CSV pairs are int code &rarr; constant name (both CSV-safe, no
     * embedded spaces), and the resolved constant is verified by identity, name, and
     * round-tripped code.
     *
     * @param code         the numeric reason code under test
     * @param expectedName the expected {@link Enum#name()} of the resolved constant
     */
    @ParameterizedTest
    @CsvSource({
            "0,   NONE",
            "100, CARD_NOT_FOUND",
            "101, ACCOUNT_NOT_FOUND",
            "102, OVER_CREDIT_LIMIT",
            "103, ACCOUNT_EXPIRED",
            "109, ACCOUNT_NOT_FOUND_ON_UPDATE"
    })
    void fromCode_resolvesEachKnownCodeToItsConstant(int code, String expectedName) {
        RejectReasonCode resolved = RejectReasonCode.fromCode(code);
        assertThat(resolved).isEqualTo(RejectReasonCode.valueOf(expectedName));
        assertThat(resolved.name()).isEqualTo(expectedName);
        assertThat(resolved.getCode()).isEqualTo(code);
    }

    // ---------------------------------------------------------------------
    // fromCode(int): unknown codes throw IllegalArgumentException
    // ---------------------------------------------------------------------

    /**
     * {@link RejectReasonCode#fromCode(int)} rejects any code that is not one of the
     * six defined constants by throwing {@link IllegalArgumentException}. Sampled
     * unknowns include a far-out value, a low value, a negative value, and codes that
     * sit adjacent to the valid range ({@code 104}, {@code 99}).
     *
     * @param unknownCode a numeric code that maps to no constant
     */
    @ParameterizedTest
    @ValueSource(ints = {999, 1, -1, 104, 99})
    void fromCode_unknownCode_throwsIllegalArgumentException(int unknownCode) {
        assertThatThrownBy(() -> RejectReasonCode.fromCode(unknownCode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------
    // Optional getFormattedCode() exercised via reflection
    // ---------------------------------------------------------------------

    /**
     * Exercises the optional {@code getFormattedCode()} convenience method, which
     * renders the code as the zero-padded four-digit COBOL {@code PIC 9(04)} field.
     *
     * <p>The method is accessed reflectively so this test compiles and runs whether
     * or not the production enum declares it: if the method is absent the test simply
     * returns without asserting. When present, the zero-padding is verified for a
     * three-digit code ({@code 100} &rarr; {@code "0100"}), the zero code
     * ({@code 0} &rarr; {@code "0000"}), and the highest defined code
     * ({@code 109} &rarr; {@code "0109"}). The {@link Method#invoke(Object, Object...)}
     * result is typed as {@link Object}, so the AssertJ assertions need no cast and
     * stay clean under {@code -Werror}.
     *
     * @throws Exception covering {@code NoSuchMethodException} (handled),
     *                   {@code InvocationTargetException}, and
     *                   {@code IllegalAccessException} from reflective access
     */
    @Test
    void getFormattedCode_whenPresent_rendersZeroPaddedFourDigitPic9_04() throws Exception {
        final Method formattedCode;
        try {
            formattedCode = RejectReasonCode.class.getMethod("getFormattedCode");
        } catch (NoSuchMethodException notImplemented) {
            // getFormattedCode() is an optional convenience API on the production enum.
            // When the production enum does not declare it, there is nothing to assert.
            return;
        }
        assertThat(formattedCode.invoke(RejectReasonCode.CARD_NOT_FOUND)).isEqualTo("0100");
        assertThat(formattedCode.invoke(RejectReasonCode.NONE)).isEqualTo("0000");
        assertThat(formattedCode.invoke(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE))
                .isEqualTo("0109");
    }

    // ---------------------------------------------------------------------
    // Cardinality: exactly the six defined constants, in declaration order
    // ---------------------------------------------------------------------

    /**
     * The enum defines exactly six constants. Pinning both the count and the exact
     * set/order guards against accidental additions or removals that would silently
     * break byte-equivalence parity with the COBOL baseline.
     */
    @Test
    void values_containsExactlySixConstantsInDeclarationOrder() {
        assertThat(RejectReasonCode.values()).hasSize(6);
        assertThat(RejectReasonCode.values()).containsExactly(
                RejectReasonCode.NONE,
                RejectReasonCode.CARD_NOT_FOUND,
                RejectReasonCode.ACCOUNT_NOT_FOUND,
                RejectReasonCode.OVER_CREDIT_LIMIT,
                RejectReasonCode.ACCOUNT_EXPIRED,
                RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE);
    }
}
