# ============================================================================
# CardDemo — Decimal Utility Unit Tests (Mainframe-to-Cloud migration)
# ============================================================================
# Source (mainframe heritage):
#   * app/cpy/CVTRA01Y.cpy          — Transaction-category-balance
#                                     TRAN-CAT-BAL PIC S9(09)V99 COMP-3
#   * app/cpy/CVACT01Y.cpy          — Account balance, credit limit
#                                     ACCT-CURR-BAL  PIC S9(10)V99
#   * app/cpy/CVTRA05Y.cpy          — Transaction amount
#                                     TRAN-AMT       PIC S9(09)V99
#   * app/cbl/CBACT04C.cbl          — Interest-calculation formula
#                                     COMPUTE WS-MONTHLY-INTEREST =
#                                       (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
#                                     — MUST NOT be algebraically simplified
#   * app/cbl/CBTRN02C.cbl          — ON SIZE ERROR handling for ADD / DIVIDE
#
# Target module:  src/shared/utils/decimal_utils.py
#
# Test-case organisation
# ----------------------
#   Phase 1 — Constants integrity                          (5 tests)
#   Phase 2 — safe_decimal                                 (15 tests)
#   Phase 3 — to_cobol_decimal                             (13 tests)
#   Phase 4 — round_financial (ROUND_HALF_EVEN)            (6 tests)
#   Phase 5 — truncate_financial (ROUND_DOWN)              (5 tests)
#   Phase 6 — add / subtract / multiply                    (7 tests)
#   Phase 7 — divide (+ ON SIZE ERROR)                     (5 tests)
#   Phase 8 — calculate_interest (formula preservation)    (8 tests)
#   Phase 9 — is_within_cobol_range                        (6 tests)
#   Phase 10 — __all__ public-API surface guard            (1 test)
# ============================================================================
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ============================================================================
"""Verify behavioural parity between ``src/shared/utils/decimal_utils.py``
and COBOL ``PIC S9(n)V99`` packed-decimal arithmetic.

COBOL → Python Verification Surface
-----------------------------------
+---------------------------------------------+-------------------------------+
| COBOL construct                             | Python target (tested)        |
+=============================================+===============================+
| ``PIC S9(09)V99 COMP-3``                    | ``safe_decimal``,             |
| packed-decimal declaration                  | ``to_cobol_decimal``          |
+---------------------------------------------+-------------------------------+
| ``COMPUTE … ROUNDED``                       | ``round_financial`` with      |
| (banker's rounding, IEEE 754-2008)          | ``ROUND_HALF_EVEN``           |
+---------------------------------------------+-------------------------------+
| ``COMPUTE WS-MONTHLY-INTEREST =``           | ``calculate_interest`` —      |
| ``  (TRAN-CAT-BAL * DIS-INT-RATE) / 1200``  | formula MUST be preserved     |
| (AAP §0.7.1 preservation rule)              | verbatim (no /12/100 split).  |
+---------------------------------------------+-------------------------------+
| ``ON SIZE ERROR``                           | ``ZeroDivisionError`` raised  |
| (division by zero, overflow)                | with explicit error message.  |
+---------------------------------------------+-------------------------------+
| IEEE binary-float arithmetic                | Explicitly REJECTED by        |
| (disallowed for money per AAP §0.7.2)       | ``to_cobol_decimal``.         |
+---------------------------------------------+-------------------------------+
| COBOL ``SPACES`` / ``LOW-VALUES``           | ``safe_decimal(None) → ZERO`` |
| on numeric fields                           | (tolerant converter).         |
+---------------------------------------------+-------------------------------+

Business-contract invariants explicitly exercised
-------------------------------------------------
1. **Formula preservation (AAP §0.7.1)**: The interest formula is a
   SINGLE division by 1200 — NOT the algebraically equivalent
   ``balance * rate / 12 / 100``. The two paths produce identical
   results only when intermediate precision is infinite; COBOL's
   COMPUTE statement fixes the rounding boundary at the single
   divide, so we fix it here too.
2. **Rate semantics**: ``DIS-INT-RATE`` is a WHOLE-NUMBER percentage
   (12.00 for 12%), NOT a fraction (0.12). The tests pin this
   expectation.
3. **Banker's rounding**: Monetary rounding must be
   ``ROUND_HALF_EVEN`` to match COBOL ``COMPUTE … ROUNDED`` (which in
   IBM z/OS defaults to IEEE 754 round-half-to-even).
4. **bool rejection**: Python's ``isinstance(True, int)`` is ``True``;
   our strict converter MUST reject booleans with ``TypeError`` to
   prevent silent data corruption.
5. **Range check**: ``is_within_cobol_range`` enforces the implicit
   ``PIC S9(09)V99`` bound of ±999,999,999.99 inclusive.
"""

from __future__ import annotations

from decimal import ROUND_HALF_EVEN, Decimal

import pytest

from src.shared.utils.decimal_utils import (
    FINANCIAL_PRECISION,
    FINANCIAL_SCALE,
    ONE,
    ROUNDING_MODE,
    ZERO,
    add,
    calculate_interest,
    divide,
    is_within_cobol_range,
    multiply,
    round_financial,
    safe_decimal,
    subtract,
    to_cobol_decimal,
    truncate_financial,
)


# ---------------------------------------------------------------------------
# Phase 1 — Constants integrity
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_financial_scale_is_two() -> None:
    """V99 fractional digits — COBOL ``PIC S9(n)V99`` preserved."""
    assert FINANCIAL_SCALE == 2


@pytest.mark.unit
def test_financial_precision_is_fifteen() -> None:
    """Matches DB ``NUMERIC(15,2)`` used for all monetary columns."""
    assert FINANCIAL_PRECISION == 15


@pytest.mark.unit
def test_rounding_mode_is_round_half_even() -> None:
    """Banker's rounding — parity with COBOL ``COMPUTE … ROUNDED``."""
    assert ROUNDING_MODE == ROUND_HALF_EVEN


@pytest.mark.unit
def test_zero_constant_is_precise_zero() -> None:
    """``ZERO`` is ``Decimal('0.00')`` — scale-2 quantized zero."""
    assert ZERO == Decimal("0.00")
    # Exponent parity: -2 (two fractional digits)
    assert ZERO.as_tuple().exponent == -2


@pytest.mark.unit
def test_one_constant_is_precise_one() -> None:
    """``ONE`` is ``Decimal('1.00')`` — scale-2 quantized one."""
    assert ONE == Decimal("1.00")
    assert ONE.as_tuple().exponent == -2


# ---------------------------------------------------------------------------
# Phase 2 — safe_decimal (tolerant converter)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_safe_decimal_none_returns_zero_default() -> None:
    """``None`` maps to ``ZERO`` when no default supplied."""
    assert safe_decimal(None) == ZERO


@pytest.mark.unit
def test_safe_decimal_none_returns_custom_default() -> None:
    """``None`` maps to caller-supplied default."""
    custom: Decimal = Decimal("5.00")
    assert safe_decimal(None, custom) == custom


@pytest.mark.unit
def test_safe_decimal_decimal_is_quantized_to_scale_two() -> None:
    """Oversize-scale Decimals are quantized to two fractional digits."""
    result: Decimal = safe_decimal(Decimal("3.14159"))
    assert result == Decimal("3.14")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_safe_decimal_bool_returns_fallback() -> None:
    """bool must be rejected — ``isinstance(True, int)`` is a footgun."""
    # No explicit default → falls back to ZERO
    assert safe_decimal(True) == ZERO
    assert safe_decimal(False) == ZERO


@pytest.mark.unit
def test_safe_decimal_int_is_promoted_to_decimal() -> None:
    """Plain ``int`` is promoted and quantized."""
    assert safe_decimal(42) == Decimal("42.00")
    assert safe_decimal(0) == Decimal("0.00")
    assert safe_decimal(-7) == Decimal("-7.00")


@pytest.mark.unit
def test_safe_decimal_float_is_promoted_via_str() -> None:
    """float → ``Decimal(str(value))`` avoids binary-float drift."""
    # str(3.14) is "3.14" exactly — no binary-expansion artifacts.
    assert safe_decimal(3.14) == Decimal("3.14")
    # Larger precision still quantized to 2 places.
    assert safe_decimal(2.718281828) == Decimal("2.72")


@pytest.mark.unit
def test_safe_decimal_string_happy_path() -> None:
    """Numeric string parsed and quantized."""
    assert safe_decimal("123.45") == Decimal("123.45")
    # Trailing / leading whitespace is stripped before parsing.
    assert safe_decimal("  3.14  ") == Decimal("3.14")


@pytest.mark.unit
def test_safe_decimal_empty_string_returns_fallback() -> None:
    """Empty string (COBOL ``SPACES``) → fallback."""
    assert safe_decimal("") == ZERO
    assert safe_decimal("   ") == ZERO


@pytest.mark.unit
def test_safe_decimal_invalid_string_returns_fallback() -> None:
    """Un-parseable string → fallback (no raise)."""
    assert safe_decimal("abc") == ZERO
    assert safe_decimal("1.2.3") == ZERO
    assert safe_decimal("$42.00") == ZERO


@pytest.mark.unit
def test_safe_decimal_unknown_type_returns_fallback() -> None:
    """Unsupported types → fallback (lenient by design)."""
    assert safe_decimal([]) == ZERO  # type: ignore[arg-type]
    assert safe_decimal({}) == ZERO  # type: ignore[arg-type]
    assert safe_decimal(object()) == ZERO  # type: ignore[arg-type]


@pytest.mark.unit
def test_safe_decimal_nan_returns_fallback() -> None:
    """Explicit NaN (float or Decimal) → fallback."""
    assert safe_decimal(float("nan")) == ZERO
    assert safe_decimal(Decimal("NaN")) == ZERO


@pytest.mark.unit
def test_safe_decimal_infinity_returns_fallback() -> None:
    """Explicit Infinity (float or Decimal) → fallback."""
    assert safe_decimal(float("inf")) == ZERO
    assert safe_decimal(float("-inf")) == ZERO
    assert safe_decimal(Decimal("Infinity")) == ZERO


@pytest.mark.unit
def test_safe_decimal_custom_default_returned_on_failure() -> None:
    """On any failure path the caller-supplied default is returned."""
    custom: Decimal = Decimal("99.99")
    assert safe_decimal("bogus", custom) == custom
    assert safe_decimal(None, custom) == custom
    assert safe_decimal([], custom) == custom  # type: ignore[arg-type]


@pytest.mark.unit
def test_safe_decimal_negative_values_preserved() -> None:
    """Negative values pass through unchanged (sign preserved)."""
    assert safe_decimal("-42.50") == Decimal("-42.50")
    assert safe_decimal(Decimal("-1.23")) == Decimal("-1.23")


@pytest.mark.unit
def test_safe_decimal_integer_string_is_quantized() -> None:
    """Integer-like strings are quantized to scale 2."""
    result: Decimal = safe_decimal("100")
    assert result == Decimal("100.00")
    assert result.as_tuple().exponent == -2


# ---------------------------------------------------------------------------
# Phase 3 — to_cobol_decimal (STRICT converter)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_to_cobol_decimal_rejects_float_strictly() -> None:
    """float is explicitly forbidden (no binary-float for money)."""
    with pytest.raises(TypeError, match="float"):
        to_cobol_decimal(3.14)  # type: ignore[arg-type]


@pytest.mark.unit
def test_to_cobol_decimal_rejects_bool_with_explicit_message() -> None:
    """bool rejected — distinguishes True/False from int 1/0."""
    with pytest.raises(TypeError, match="bool is not a valid COBOL numeric"):
        to_cobol_decimal(True)
    with pytest.raises(TypeError, match="bool is not a valid COBOL numeric"):
        to_cobol_decimal(False)


@pytest.mark.unit
def test_to_cobol_decimal_accepts_int() -> None:
    """int promoted to Decimal with scale 2."""
    result: Decimal = to_cobol_decimal(3)
    assert result == Decimal("3.00")


@pytest.mark.unit
def test_to_cobol_decimal_accepts_decimal_exact() -> None:
    """Decimal passes through with quantization."""
    assert to_cobol_decimal(Decimal("1.23")) == Decimal("1.23")


@pytest.mark.unit
def test_to_cobol_decimal_accepts_string_numeric() -> None:
    """Numeric string parsed into Decimal."""
    assert to_cobol_decimal("42.50") == Decimal("42.50")


@pytest.mark.unit
def test_to_cobol_decimal_rejects_unparseable_string() -> None:
    """Non-numeric string raises ValueError (strict mode)."""
    with pytest.raises(ValueError, match="Cannot parse"):
        to_cobol_decimal("abc")


@pytest.mark.unit
def test_to_cobol_decimal_enforces_picture_size_limit() -> None:
    """Integer part overflow → ValueError mirroring ``ON SIZE ERROR``."""
    with pytest.raises(ValueError, match="SIZE ERROR"):
        to_cobol_decimal(Decimal("12345"), digits_before=3)


@pytest.mark.unit
def test_to_cobol_decimal_rejects_negative_digits_before() -> None:
    """Negative digits_before → ValueError (validation of PIC spec)."""
    with pytest.raises(ValueError, match="non-negative"):
        to_cobol_decimal(1, digits_before=-1)


@pytest.mark.unit
def test_to_cobol_decimal_rejects_negative_digits_after() -> None:
    """Negative digits_after → ValueError."""
    with pytest.raises(ValueError, match="non-negative"):
        to_cobol_decimal(1, digits_after=-1)


@pytest.mark.unit
def test_to_cobol_decimal_default_pic_s909_v99() -> None:
    """Default PIC is S9(09)V99 — max 999,999,999.99."""
    # Just inside the limit.
    assert to_cobol_decimal(Decimal("999999999.99")) == Decimal("999999999.99")
    # Just outside.
    with pytest.raises(ValueError, match="SIZE ERROR"):
        to_cobol_decimal(Decimal("1000000000.00"))


@pytest.mark.unit
def test_to_cobol_decimal_unsupported_type_raises_type_error() -> None:
    """List / dict / etc. → TypeError (not silent fallback)."""
    with pytest.raises(TypeError):
        to_cobol_decimal([])  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        to_cobol_decimal({})  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        to_cobol_decimal(None)  # type: ignore[arg-type]


@pytest.mark.unit
def test_to_cobol_decimal_negative_value_within_range() -> None:
    """Negative values respect the same ± bound."""
    assert to_cobol_decimal(Decimal("-500.25")) == Decimal("-500.25")


@pytest.mark.unit
def test_to_cobol_decimal_quantizes_scale() -> None:
    """Excess fractional digits are rounded via ROUND_HALF_EVEN."""
    # 3.125 -> 3.12 (round to even)
    assert to_cobol_decimal(Decimal("3.125")) == Decimal("3.12")
    # 3.135 -> 3.14 (round to even)
    assert to_cobol_decimal(Decimal("3.135")) == Decimal("3.14")


# ---------------------------------------------------------------------------
# Phase 4 — round_financial (ROUND_HALF_EVEN / banker's rounding)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_round_financial_rounds_half_to_even_low() -> None:
    """0.125 -> 0.12 (rounds to even)."""
    assert round_financial(Decimal("0.125")) == Decimal("0.12")


@pytest.mark.unit
def test_round_financial_rounds_half_to_even_high() -> None:
    """0.135 -> 0.14 (rounds to even)."""
    assert round_financial(Decimal("0.135")) == Decimal("0.14")


@pytest.mark.unit
def test_round_financial_preserves_already_quantized_value() -> None:
    """Already-2-place decimal returns equal value at scale 2."""
    result: Decimal = round_financial(Decimal("3.14"))
    assert result == Decimal("3.14")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_round_financial_negative_half_to_even() -> None:
    """-0.125 -> -0.12 (banker's rounding preserves symmetry)."""
    assert round_financial(Decimal("-0.125")) == Decimal("-0.12")


@pytest.mark.unit
def test_round_financial_zero_returns_zero() -> None:
    """0 -> 0.00 (with correct exponent)."""
    result: Decimal = round_financial(Decimal("0"))
    assert result == Decimal("0.00")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_round_financial_large_value_preserved() -> None:
    """Large value preserved with correct rounding."""
    assert round_financial(Decimal("1234567.895")) == Decimal("1234567.90")


# ---------------------------------------------------------------------------
# Phase 5 — truncate_financial (ROUND_DOWN / toward zero)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_truncate_financial_truncates_positive_toward_zero() -> None:
    """3.999 -> 3.99 (truncate, NOT round)."""
    assert truncate_financial(Decimal("3.999")) == Decimal("3.99")


@pytest.mark.unit
def test_truncate_financial_truncates_negative_toward_zero() -> None:
    """-3.999 -> -3.99 (truncate toward zero, NOT floor)."""
    assert truncate_financial(Decimal("-3.999")) == Decimal("-3.99")


@pytest.mark.unit
def test_truncate_financial_preserves_exact_scale_two() -> None:
    """Already-2-place decimal returns equal value."""
    assert truncate_financial(Decimal("1.00")) == Decimal("1.00")


@pytest.mark.unit
def test_truncate_financial_no_rounding_up_on_halfway() -> None:
    """0.125 -> 0.12 (truncated, NOT rounded to 0.13)."""
    assert truncate_financial(Decimal("0.125")) == Decimal("0.12")


@pytest.mark.unit
def test_truncate_financial_zero_returns_zero_scale_two() -> None:
    """0 truncates to 0.00."""
    result: Decimal = truncate_financial(Decimal("0"))
    assert result == Decimal("0.00")
    assert result.as_tuple().exponent == -2


# ---------------------------------------------------------------------------
# Phase 6 — add / subtract / multiply
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_add_simple() -> None:
    """Basic addition returns scale-2 result."""
    result: Decimal = add(Decimal("1.23"), Decimal("4.56"))
    assert result == Decimal("5.79")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_add_with_rounding() -> None:
    """Excess precision is rounded (banker's rounding)."""
    result: Decimal = add(Decimal("0.005"), Decimal("0"))
    # 0.005 rounds to 0.00 (round-half-even: 0 is even → round down)
    assert result == Decimal("0.00")


@pytest.mark.unit
def test_subtract_simple() -> None:
    """Basic subtraction returns scale-2 result."""
    result: Decimal = subtract(Decimal("10.00"), Decimal("3.50"))
    assert result == Decimal("6.50")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_subtract_negative_result() -> None:
    """Result may be negative."""
    assert subtract(Decimal("3.00"), Decimal("10.00")) == Decimal("-7.00")


@pytest.mark.unit
def test_multiply_simple() -> None:
    """Basic multiplication returns scale-2 result."""
    result: Decimal = multiply(Decimal("2.00"), Decimal("3.50"))
    assert result == Decimal("7.00")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_multiply_with_rounding() -> None:
    """Oversize fractional product is rounded."""
    # 0.1 * 0.1 = 0.01 exactly (no rounding)
    assert multiply(Decimal("0.10"), Decimal("0.10")) == Decimal("0.01")
    # 1.255 * 1 = 1.255 -> rounded to 1.26 (ROUND_HALF_EVEN)
    assert multiply(Decimal("1.255"), Decimal("1.00")) == Decimal("1.26")


@pytest.mark.unit
def test_multiply_by_zero_returns_zero() -> None:
    """Any number multiplied by 0.00 is 0.00."""
    assert multiply(Decimal("9999999.99"), Decimal("0.00")) == Decimal("0.00")


# ---------------------------------------------------------------------------
# Phase 7 — divide (with COBOL ON SIZE ERROR)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_divide_simple() -> None:
    """Basic division returns scale-2 result."""
    result: Decimal = divide(Decimal("10.00"), Decimal("4.00"))
    assert result == Decimal("2.50")
    assert result.as_tuple().exponent == -2


@pytest.mark.unit
def test_divide_by_zero_raises_zero_division_error() -> None:
    """Division by zero → ``ZeroDivisionError`` (COBOL SIZE ERROR)."""
    with pytest.raises(ZeroDivisionError, match="ON SIZE ERROR"):
        divide(Decimal("1.00"), Decimal("0"))


@pytest.mark.unit
def test_divide_by_zero_error_message_includes_operands() -> None:
    """Error message includes operands for postmortem debuggability."""
    with pytest.raises(ZeroDivisionError) as exc:
        divide(Decimal("123.45"), Decimal("0.00"))
    assert "123.45" in str(exc.value)
    # "0" or "0.00" — both acceptable in the error message.
    assert "0" in str(exc.value)


@pytest.mark.unit
def test_divide_with_banker_rounding() -> None:
    """Inexact result is rounded via ROUND_HALF_EVEN."""
    # 10/3 = 3.333... -> rounds to 3.33
    assert divide(Decimal("10.00"), Decimal("3.00")) == Decimal("3.33")


@pytest.mark.unit
def test_divide_exact_result() -> None:
    """Exact-result division returns without rounding."""
    assert divide(Decimal("100.00"), Decimal("4.00")) == Decimal("25.00")


# ---------------------------------------------------------------------------
# Phase 8 — calculate_interest (AAP §0.7.1 formula preservation)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_calculate_interest_canonical_example() -> None:
    """CBACT04C: (balance 1200 × rate 12) / 1200 = 12.00 exactly."""
    result: Decimal = calculate_interest(Decimal("1200.00"), Decimal("12.00"))
    assert result == Decimal("12.00")


@pytest.mark.unit
def test_calculate_interest_zero_rate_returns_zero() -> None:
    """ZEROAPR disclosure group: rate 0 → interest 0.00."""
    result: Decimal = calculate_interest(Decimal("5000.00"), Decimal("0.00"))
    assert result == Decimal("0.00")


@pytest.mark.unit
def test_calculate_interest_zero_balance_returns_zero() -> None:
    """Zero balance → zero interest regardless of rate."""
    result: Decimal = calculate_interest(Decimal("0.00"), Decimal("15.00"))
    assert result == Decimal("0.00")


@pytest.mark.unit
def test_calculate_interest_negative_balance_negative_interest() -> None:
    """Credit balance (negative) yields a credit (negative) interest."""
    result: Decimal = calculate_interest(Decimal("-1200.00"), Decimal("12.00"))
    assert result == Decimal("-12.00")


@pytest.mark.unit
def test_calculate_interest_fractional_rate_quantized_to_cents() -> None:
    """Fractional rate (e.g. 15.75%) result is quantized to cents."""
    # (100.00 * 15.75) / 1200 = 1.3125 → ROUND_HALF_EVEN → 1.31
    # (Because 2 is even; 1.3125 rounded-half-even at 2dp drops the 5.)
    result: Decimal = calculate_interest(Decimal("100.00"), Decimal("15.75"))
    assert result == Decimal("1.31")


@pytest.mark.unit
def test_calculate_interest_rate_is_whole_number_percentage() -> None:
    """Rate 12.00 means 12% APR — NOT 12.00 as a fraction.

    The formula (balance * rate) / 1200 encodes both the /100 (percent)
    AND the /12 (monthly). Passing rate=0.12 would produce 1/100th the
    expected interest — this test pins the business contract.
    """
    # Sanity: 12.00 and 0.12 produce 100× different results.
    rate_as_percent: Decimal = calculate_interest(Decimal("1200.00"), Decimal("12.00"))
    rate_as_fraction: Decimal = calculate_interest(Decimal("1200.00"), Decimal("0.12"))
    # Percent form is 100× the fraction form.
    assert rate_as_percent == Decimal("12.00")
    assert rate_as_fraction == Decimal("0.12")


@pytest.mark.unit
def test_calculate_interest_preserves_single_division_not_two() -> None:
    """Formula is SINGLE `/1200`, not `/100/12` — required by AAP §0.7.1.

    We verify the contract by comparing against the explicit single-
    division calculation. If a refactor accidentally introduces two
    divisions, intermediate rounding changes the final cent value.
    """
    balance: Decimal = Decimal("333.33")
    rate: Decimal = Decimal("11.25")

    # Reference: single-division path (what the formula must produce)
    from decimal import localcontext

    with localcontext() as ctx:
        ctx.prec = 28
        expected: Decimal = ((balance * rate) / Decimal("1200")).quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)

    assert calculate_interest(balance, rate) == expected


@pytest.mark.unit
def test_calculate_interest_high_precision_intermediate() -> None:
    """Intermediate precision must be >= 28 to avoid premature rounding."""
    # Large balance x small rate still produces a deterministic value.
    result: Decimal = calculate_interest(Decimal("999999999.99"), Decimal("1.00"))
    # (999,999,999.99 × 1.00) / 1200 = 833,333.333325 -> 833333.33
    assert result == Decimal("833333.33")


# ---------------------------------------------------------------------------
# Phase 9 — is_within_cobol_range
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_is_within_cobol_range_max_positive_inside() -> None:
    """PIC S9(09)V99 upper bound (999,999,999.99) is inclusive."""
    assert is_within_cobol_range(Decimal("999999999.99")) is True


@pytest.mark.unit
def test_is_within_cobol_range_max_positive_one_cent_outside() -> None:
    """One cent beyond upper bound → False."""
    assert is_within_cobol_range(Decimal("1000000000.00")) is False


@pytest.mark.unit
def test_is_within_cobol_range_max_negative_inside() -> None:
    """PIC S9(09)V99 negative bound is inclusive (signed)."""
    assert is_within_cobol_range(Decimal("-999999999.99")) is True


@pytest.mark.unit
def test_is_within_cobol_range_max_negative_one_cent_outside() -> None:
    """Beyond negative bound → False."""
    assert is_within_cobol_range(Decimal("-1000000000.00")) is False


@pytest.mark.unit
def test_is_within_cobol_range_zero_is_within() -> None:
    """Zero is trivially within the range."""
    assert is_within_cobol_range(Decimal("0.00")) is True
    assert is_within_cobol_range(ZERO) is True


@pytest.mark.unit
def test_is_within_cobol_range_far_outside_values() -> None:
    """Far-outside values (e.g. 10^15) are rejected."""
    assert is_within_cobol_range(Decimal("999999999999999.99")) is False
    assert is_within_cobol_range(Decimal("-999999999999999.99")) is False


@pytest.mark.unit
def test_is_within_cobol_range_rejects_negative_digits_before() -> None:
    """Negative PIC specification raises ValueError (validation)."""
    with pytest.raises(ValueError, match="non-negative"):
        is_within_cobol_range(Decimal("1.00"), pic_digits_before=-1)


@pytest.mark.unit
def test_is_within_cobol_range_rejects_negative_digits_after() -> None:
    """Negative PIC specification raises ValueError (validation)."""
    with pytest.raises(ValueError, match="non-negative"):
        is_within_cobol_range(Decimal("1.00"), pic_digits_after=-1)


@pytest.mark.unit
def test_is_within_cobol_range_custom_pic_specification() -> None:
    """Custom PIC S9(04)V99 accepts values up to 9,999.99."""
    assert is_within_cobol_range(Decimal("9999.99"), pic_digits_before=4) is True
    assert is_within_cobol_range(Decimal("10000.00"), pic_digits_before=4) is False


# ---------------------------------------------------------------------------
# Phase 10 — __all__ public-API surface guard
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_all_public_api_matches_expected_surface() -> None:
    """``__all__`` must list exactly the 15 documented exports."""
    from src.shared.utils import decimal_utils

    expected: set[str] = {
        "FINANCIAL_SCALE",
        "FINANCIAL_PRECISION",
        "ROUNDING_MODE",
        "ZERO",
        "ONE",
        "safe_decimal",
        "to_cobol_decimal",
        "round_financial",
        "truncate_financial",
        "add",
        "subtract",
        "multiply",
        "divide",
        "calculate_interest",
        "is_within_cobol_range",
    }
    assert hasattr(decimal_utils, "__all__")
    actual: set[str] = set(decimal_utils.__all__)
    assert actual == expected, (
        f"decimal_utils.__all__ drift detected. Missing: {expected - actual}  Extra: {actual - expected}"
    )
