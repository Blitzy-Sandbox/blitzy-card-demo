# ============================================================================
# Source: app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL PIC S9(09)V99 pattern) and the
#         broader COBOL ``PIC S9(n)V99`` monetary-field conventions used
#         across the CardDemo copybook library — e.g. ACCT-CURR-BAL,
#         ACCT-CREDIT-LIMIT, TRAN-AMT, DIS-INT-RATE. Interest formula from
#         app/cbl/CBACT04C.cbl paragraph 1300-COMPUTE-INTEREST.
#         — Mainframe-to-Cloud migration
# ============================================================================
# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ============================================================================
"""COBOL-compatible :mod:`decimal` arithmetic for CardDemo financial calculations.

This module is the single source of truth for **all** monetary arithmetic in
the CardDemo modernized application (both the FastAPI API layer on AWS ECS
and the PySpark batch jobs on AWS Glue). It replicates COBOL
``PIC S9(n)V99`` semantics in Python using :class:`decimal.Decimal` — never
``float`` — and exposes a small, well-typed surface area for downstream
services and jobs.

Why this module exists
----------------------
The legacy CardDemo code represents every monetary field (account balance,
credit limit, transaction amount, interest rate, fees, cycle totals, etc.)
as a COBOL ``PIC S9(n)V99`` binary-coded-decimal field. The two key
guarantees of that representation are:

1. **Exact base-10 precision** — no floating-point rounding error.
2. **Fixed two-decimal-place scale** — every stored monetary value is
   quantized to the cent.

Python's native ``float`` type cannot preserve either property. For example,
``0.1 + 0.2`` in IEEE-754 binary float equals ``0.30000000000000004``; this
is unacceptable for financial calculations and would produce mismatches
against the COBOL baseline.

This module enforces the cloud equivalent by:

* Using :class:`decimal.Decimal` exclusively for all arithmetic.
* Rounding with :data:`decimal.ROUND_HALF_EVEN` (Banker's rounding) to match
  the COBOL ``ROUNDED`` keyword semantics.
* Quantizing all results to exactly two decimal places (``V99``).
* Constructing ``Decimal`` instances from string representations whenever
  a ``float`` input sneaks in, so that the binary float's imprecise bits
  never enter the computation pipeline.

The module also preserves the exact COBOL interest formula
``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` from
``app/cbl/CBACT04C.cbl`` (paragraph ``1300-COMPUTE-INTEREST``) **without
algebraic simplification** — AAP §0.7.1 explicitly forbids simplifying
business logic during this migration.

Source mapping
--------------
* ``TRAN-CAT-BAL`` (PIC S9(09)V99) — ``app/cpy/CVTRA01Y.cpy`` line 9
* ``DIS-INT-RATE`` (PIC S9(04)V99) — ``app/cpy/CVTRA02Y.cpy`` line 9
* ``ACCT-CURR-BAL`` / ``ACCT-CREDIT-LIMIT`` (PIC S9(10)V99) —
  ``app/cpy/CVACT01Y.cpy``
* ``TRAN-AMT`` (PIC S9(09)V99) — ``app/cpy/CVTRA05Y.cpy``
* Interest formula (line 464-465) — ``app/cbl/CBACT04C.cbl``

**CRITICAL RULE**: ``float`` is accepted **only** as an input type to
:func:`safe_decimal` for convenience at module boundaries (e.g., JSON
payload parsing); it is never used internally. All internal arithmetic
works exclusively on :class:`decimal.Decimal` instances.

Public API
----------
Constants
~~~~~~~~~
* :data:`FINANCIAL_SCALE` — ``2``, matches COBOL ``V99``.
* :data:`FINANCIAL_PRECISION` — ``15``, matches PIC S9(13)V99 total digit
  capacity, also used as the Decimal context precision upper bound.
* :data:`ROUNDING_MODE` — :data:`decimal.ROUND_HALF_EVEN`, matches COBOL
  ``ROUNDED`` keyword behavior (Banker's rounding).
* :data:`ZERO` — pre-constructed ``Decimal('0.00')`` for bulk initialization.
* :data:`ONE` — pre-constructed ``Decimal('1.00')``.

Constructors
~~~~~~~~~~~~
* :func:`safe_decimal` — tolerant constructor that accepts ``str``, ``int``,
  ``float``, ``Decimal``, or ``None`` and always returns a properly-scaled
  ``Decimal``.
* :func:`to_cobol_decimal` — strict constructor that validates against a
  COBOL PIC field capacity, raising :class:`ValueError` on overflow
  (equivalent to COBOL ``ON SIZE ERROR``).

Rounding
~~~~~~~~
* :func:`round_financial` — Banker's rounding to a specified scale.
* :func:`truncate_financial` — truncation (no rounding) to a specified scale.

Arithmetic (COBOL ROUNDED equivalents)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
* :func:`add` — addition with result rounding.
* :func:`subtract` — subtraction with result rounding.
* :func:`multiply` — multiplication with result rounding.
* :func:`divide` — division with result rounding; raises
  :class:`ZeroDivisionError` on zero divisor.

Business logic
~~~~~~~~~~~~~~
* :func:`calculate_interest` — COBOL-faithful monthly interest calculation.
* :func:`is_within_cobol_range` — COBOL PIC-field capacity validation.

See Also
--------
AAP §0.7.1 — Refactoring-Specific Rules (business-logic preservation)
AAP §0.7.2 — Financial Precision (``Decimal`` + Banker's rounding)
``app/cbl/CBACT04C.cbl`` — Original interest calculation program (INTCALC).
``app/cpy/CVTRA01Y.cpy`` — Transaction category balance record layout.
"""

from decimal import (
    ROUND_DOWN,
    ROUND_HALF_EVEN,
    Decimal,
    InvalidOperation,
    getcontext,
    localcontext,
)
from typing import Optional, Union

# ----------------------------------------------------------------------------
# Module-level decimal context configuration.
#
# Python's default :class:`decimal.Context` precision is already 28 digits,
# which is more than sufficient for every COBOL ``PIC S9(n)V99`` field used
# in CardDemo (the widest is ``PIC S9(13)V99`` with 15 total digits). We
# set ``prec = 28`` explicitly so that downstream code that may mutate the
# context (e.g., libraries that prefer lower precision for performance)
# cannot silently reduce the precision used by this module at import time.
#
# NOTE: :class:`decimal.Context` is thread-local, so this setting applies to
# the importing thread. Functions that need guaranteed precision even in the
# face of third-party context mutation use :func:`decimal.localcontext`
# (see :func:`calculate_interest`).
# ----------------------------------------------------------------------------
getcontext().prec = 28


# ============================================================================
# Constants
# ============================================================================

FINANCIAL_SCALE: int = 2
"""Number of decimal places for all monetary values.

Matches the COBOL ``V99`` fractional portion used on every ``PIC S9(n)V99``
monetary field across the CardDemo copybook library (balances, credit
limits, transaction amounts, interest rates, fees, cycle totals).
"""

FINANCIAL_PRECISION: int = 15
"""Total digit capacity for monetary Decimals.

Accommodates the widest COBOL field encountered in CardDemo
(``ACCT-CURR-BAL PIC S9(10)V99`` → 12 digits; the 15-digit ceiling leaves
headroom for intermediate products such as ``balance × rate`` before the
final quantize-to-two-decimals step).
"""

ROUNDING_MODE: str = ROUND_HALF_EVEN
"""Rounding mode for every financial quantize operation.

Set to :data:`decimal.ROUND_HALF_EVEN` (Banker's rounding), which matches
the behavior of the COBOL ``ROUNDED`` keyword on IBM mainframes. Banker's
rounding rounds halves to the nearest even digit, avoiding the positive
bias introduced by ``ROUND_HALF_UP`` — essential for financial calculations
at scale.

Examples:

* ``Decimal('0.125')`` → ``Decimal('0.12')`` (rounded to even: 2)
* ``Decimal('0.135')`` → ``Decimal('0.14')`` (rounded to even: 4)
"""

ZERO: Decimal = Decimal("0.00")
"""Pre-constructed zero monetary value with correct scale (``0.00``).

Using this constant avoids the cost (and risk of scale mismatch) of
constructing ``Decimal('0.00')`` repeatedly in hot loops like batch job
iteration and default-value initialization.
"""

ONE: Decimal = Decimal("1.00")
"""Pre-constructed unit monetary value with correct scale (``1.00``)."""


# ============================================================================
# Constructors
# ============================================================================


def safe_decimal(
    value: Union[str, int, float, Decimal, None],  # noqa: UP007
    default: Optional[Decimal] = None,  # noqa: UP045
) -> Decimal:
    """Construct a 2-decimal-place :class:`~decimal.Decimal` from any input.

    Tolerant converter used at module boundaries (JSON payloads, database
    results, configuration values) where the incoming value may be of any
    numeric-adjacent type, may be ``None``, or may be an invalid string.
    Always returns a ``Decimal`` quantized to :data:`FINANCIAL_SCALE` (2
    decimal places) with :data:`ROUNDING_MODE` (Banker's rounding).

    **CRITICAL**: when ``value`` is a ``float``, it is converted via
    :func:`str` first so that the binary float's imprecise bits never enter
    the :class:`~decimal.Decimal` pipeline. For example,
    ``safe_decimal(0.1)`` returns ``Decimal('0.10')``, **not**
    ``Decimal('0.1000000000000000055511151231257827021181583404541015625')``.

    Parameters
    ----------
    value:
        The value to convert. May be ``str`` (e.g. ``"123.45"``),
        ``int`` (e.g. ``100``), ``float`` (converted via string),
        ``Decimal``, or ``None``.
    default:
        The value to return when ``value`` is ``None`` or cannot be parsed.
        When ``default`` is itself ``None``, :data:`ZERO` (i.e.
        ``Decimal('0.00')``) is used.

    Returns
    -------
    Decimal
        The input quantized to two decimal places using Banker's rounding,
        or ``default`` (or :data:`ZERO`) for ``None`` / invalid input.

    Examples
    --------
    >>> safe_decimal(None)
    Decimal('0.00')
    >>> safe_decimal('123.456')
    Decimal('123.46')
    >>> safe_decimal(0.1)
    Decimal('0.10')
    >>> safe_decimal('  42  ')
    Decimal('42.00')
    >>> safe_decimal('not a number')
    Decimal('0.00')
    >>> safe_decimal(None, default=Decimal('99.99'))
    Decimal('99.99')
    """
    fallback: Decimal = ZERO if default is None else default

    if value is None:
        return fallback

    try:
        result: Decimal
        if isinstance(value, Decimal):
            # Pass through — but still quantize below so the result always
            # has exactly 2 decimal places regardless of the input's scale.
            result = value
        elif isinstance(value, bool):
            # ``bool`` is a subclass of ``int`` in Python; treat it as a
            # programming error rather than silently mapping to 0/1.
            return fallback
        elif isinstance(value, int):
            result = Decimal(value)
        elif isinstance(value, float):
            # CRITICAL: route through ``str`` so that the binary float's
            # imprecise bits are replaced by the repr's shortest exact
            # decimal representation. Never pass a ``float`` directly to
            # ``Decimal()``.
            result = Decimal(str(value))
        elif isinstance(value, str):
            stripped = value.strip()
            if not stripped:
                return fallback
            result = Decimal(stripped)
        else:
            # Unknown type — reject rather than risk silent corruption.
            return fallback

        # Reject NaN and ±Infinity explicitly. Quiet NaN silently propagates
        # through ``quantize`` in the default context, so ``is_finite`` must
        # be checked before returning.
        if not result.is_finite():
            return fallback

        # Quantize to exactly 2 decimal places using Banker's rounding.
        return result.quantize(Decimal("0.01"), rounding=ROUNDING_MODE)
    except (InvalidOperation, ValueError, TypeError):
        # ``InvalidOperation`` — invalid Decimal string, signaling NaN.
        # ``ValueError``      — rarely raised by Decimal but guarded for
        #                        defensive safety.
        # ``TypeError``       — unexpected input types slipping past the
        #                        ``isinstance`` chain.
        return fallback


def to_cobol_decimal(
    value: Union[str, int, Decimal],  # noqa: UP007
    digits_before: int = 9,
    digits_after: int = 2,
) -> Decimal:
    """Construct a :class:`~decimal.Decimal` matching a specific COBOL PIC layout.

    Strict constructor used when a value must conform to a known COBOL
    field definition. Unlike :func:`safe_decimal`, this function raises
    :class:`ValueError` if the value does not fit the declared PIC field
    (the Python equivalent of COBOL's ``ON SIZE ERROR`` condition).

    Default arguments correspond to the most common CardDemo monetary
    layout, ``PIC S9(09)V99`` — used for ``TRAN-CAT-BAL``, ``TRAN-AMT``, and
    most cycle/total fields.

    Parameters
    ----------
    value:
        The value to convert. Accepts ``str``, ``int``, or ``Decimal`` (no
        ``float`` — use :func:`safe_decimal` at module boundaries, then
        pass the resulting ``Decimal`` here).
    digits_before:
        Number of digits before the implied decimal point (the ``n`` in
        ``PIC S9(n)V99``). Default ``9`` matches ``PIC S9(09)V99``.
    digits_after:
        Number of digits after the implied decimal point. Default ``2``
        matches ``V99``.

    Returns
    -------
    Decimal
        The value quantized to the specified scale with Banker's rounding.

    Raises
    ------
    ValueError
        If the value exceeds the declared COBOL field capacity
        (SIZE ERROR), if the input string cannot be parsed, or if the
        input is of an unsupported type.
    TypeError
        If ``value`` is of an unsupported Python type (``float``,
        ``bool``, etc.).

    Examples
    --------
    >>> to_cobol_decimal('123.45')
    Decimal('123.45')
    >>> to_cobol_decimal(1_000_000_000)  # exceeds PIC S9(09)V99 range
    Traceback (most recent call last):
    ...
    ValueError: Value ... exceeds COBOL PIC S9(09)V99 capacity ...
    >>> to_cobol_decimal('99.99', digits_before=4, digits_after=2)
    Decimal('99.99')
    """
    if digits_before < 0 or digits_after < 0:
        raise ValueError(
            f"digits_before and digits_after must be non-negative; "
            f"got digits_before={digits_before}, digits_after={digits_after}"
        )

    # Convert the input to Decimal without scale coercion.
    dec_value: Decimal
    if isinstance(value, Decimal):
        dec_value = value
    elif isinstance(value, bool):
        # Reject ``bool`` explicitly — it's a subclass of ``int`` and
        # would otherwise be silently accepted.
        raise TypeError("bool is not a valid COBOL numeric input; use int or Decimal")
    elif isinstance(value, int):
        dec_value = Decimal(value)
    elif isinstance(value, str):
        try:
            dec_value = Decimal(value.strip())
        except InvalidOperation as exc:
            raise ValueError(f"Cannot parse {value!r} as a COBOL-compatible Decimal") from exc
    else:
        raise TypeError(
            f"Unsupported type for to_cobol_decimal: {type(value).__name__!r}; expected str, int, or Decimal"
        )

    # Validate against COBOL PIC field capacity. For ``PIC S9(n)V99`` the
    # maximum absolute value is ``10**n - 10**-v99`` (e.g., for S9(09)V99,
    # the max is 999999999.99).
    max_value = Decimal(10) ** digits_before - Decimal(10) ** -digits_after
    if abs(dec_value) > max_value:
        raise ValueError(
            f"Value {dec_value} exceeds COBOL "
            f"PIC S9({digits_before:02d})V{'9' * digits_after} "
            f"capacity (max={max_value}) — SIZE ERROR"
        )

    # Quantize to the declared scale (``V99`` → 0.01 quantum).
    quantum = Decimal(10) ** -digits_after
    return dec_value.quantize(quantum, rounding=ROUNDING_MODE)


# ============================================================================
# Rounding
# ============================================================================


def round_financial(value: Decimal, scale: int = FINANCIAL_SCALE) -> Decimal:
    """Round a :class:`~decimal.Decimal` to the given scale using Banker's rounding.

    This function is the cornerstone of financial precision across
    CardDemo: every arithmetic wrapper in this module
    (:func:`add`, :func:`subtract`, :func:`multiply`, :func:`divide`,
    :func:`calculate_interest`) terminates with a call to
    :func:`round_financial` to guarantee the result is quantized to exactly
    two decimal places.

    Parameters
    ----------
    value:
        The value to round.
    scale:
        Number of decimal places to round to. Defaults to
        :data:`FINANCIAL_SCALE` (``2``), matching COBOL ``V99``.

    Returns
    -------
    Decimal
        The value quantized to ``scale`` decimal places with
        :data:`ROUNDING_MODE` (Banker's rounding — matches COBOL
        ``ROUNDED``).

    Examples
    --------
    >>> round_financial(Decimal('0.125'))
    Decimal('0.12')
    >>> round_financial(Decimal('0.135'))
    Decimal('0.14')
    >>> round_financial(Decimal('99.999'), scale=2)
    Decimal('100.00')
    """
    quantum = Decimal(10) ** -scale
    return value.quantize(quantum, rounding=ROUNDING_MODE)


def truncate_financial(value: Decimal, scale: int = FINANCIAL_SCALE) -> Decimal:
    """Truncate a :class:`~decimal.Decimal` to the given scale (no rounding).

    Performs a toward-zero truncation, equivalent to COBOL's implicit
    truncation when the ``ROUNDED`` keyword is **absent**. Use this when
    matching a specific COBOL statement that did not specify ``ROUNDED``.

    Parameters
    ----------
    value:
        The value to truncate.
    scale:
        Number of decimal places to keep. Defaults to
        :data:`FINANCIAL_SCALE` (``2``).

    Returns
    -------
    Decimal
        The value quantized to ``scale`` decimal places with
        :data:`decimal.ROUND_DOWN`.

    Examples
    --------
    >>> truncate_financial(Decimal('0.125'))
    Decimal('0.12')
    >>> truncate_financial(Decimal('0.135'))
    Decimal('0.13')
    >>> truncate_financial(Decimal('-1.99'))
    Decimal('-1.99')
    """
    quantum = Decimal(10) ** -scale
    return value.quantize(quantum, rounding=ROUND_DOWN)


# ============================================================================
# Arithmetic (COBOL ROUNDED equivalents)
# ============================================================================


def add(a: Decimal, b: Decimal) -> Decimal:
    """Add two :class:`~decimal.Decimal` values with 2-decimal-place rounding.

    Python equivalent of the COBOL statement::

        ADD A TO B GIVING C ROUNDED

    The result is always quantized to :data:`FINANCIAL_SCALE` places.

    Examples
    --------
    >>> add(Decimal('100.00'), Decimal('23.45'))
    Decimal('123.45')
    >>> add(Decimal('0.1'), Decimal('0.2'))  # no binary float drift
    Decimal('0.30')
    """
    return round_financial(a + b)


def subtract(a: Decimal, b: Decimal) -> Decimal:
    """Subtract ``b`` from ``a`` with 2-decimal-place rounding.

    Python equivalent of the COBOL statement::

        SUBTRACT B FROM A GIVING C ROUNDED

    Examples
    --------
    >>> subtract(Decimal('100.00'), Decimal('23.45'))
    Decimal('76.55')
    >>> subtract(Decimal('50.00'), Decimal('50.001'))
    Decimal('0.00')
    """
    return round_financial(a - b)


def multiply(a: Decimal, b: Decimal) -> Decimal:
    """Multiply two :class:`~decimal.Decimal` values with 2-decimal-place rounding.

    Python equivalent of the COBOL statement::

        MULTIPLY A BY B GIVING C ROUNDED

    Examples
    --------
    >>> multiply(Decimal('10.00'), Decimal('3.50'))
    Decimal('35.00')
    >>> multiply(Decimal('2.50'), Decimal('0.125'))
    Decimal('0.31')
    """
    return round_financial(a * b)


def divide(a: Decimal, b: Decimal) -> Decimal:
    """Divide ``a`` by ``b`` with 2-decimal-place rounding.

    Python equivalent of the COBOL statement::

        DIVIDE A BY B GIVING C ROUNDED ON SIZE ERROR ...

    Raises :class:`ZeroDivisionError` on a zero divisor, matching the
    COBOL ``ON SIZE ERROR`` branch (lifted from a program-level imperative
    handler to a Python exception at the call site).

    Parameters
    ----------
    a:
        Dividend.
    b:
        Divisor.

    Returns
    -------
    Decimal
        The quotient quantized to 2 decimal places using Banker's rounding.

    Raises
    ------
    ZeroDivisionError
        If ``b`` is zero.

    Examples
    --------
    >>> divide(Decimal('100.00'), Decimal('4.00'))
    Decimal('25.00')
    >>> divide(Decimal('1.00'), Decimal('3.00'))
    Decimal('0.33')
    >>> divide(Decimal('10.00'), Decimal('0.00'))
    Traceback (most recent call last):
    ...
    ZeroDivisionError: Division by zero: cannot divide ...
    """
    if b == 0:
        raise ZeroDivisionError(f"Division by zero: cannot divide {a} by {b} (COBOL ON SIZE ERROR equivalent)")
    return round_financial(a / b)


# ============================================================================
# Business logic
# ============================================================================


def calculate_interest(balance: Decimal, annual_rate: Decimal) -> Decimal:
    """Compute monthly interest using the exact COBOL formula from CBACT04C.

    Implements paragraph ``1300-COMPUTE-INTEREST`` from
    ``app/cbl/CBACT04C.cbl`` (line 464-465)::

        COMPUTE WS-MONTHLY-INT
         = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200

    **CRITICAL**: per AAP §0.7.1 the business rule must not be
    algebraically simplified — for example, this implementation does
    **not** refactor into ``balance * rate / 12 / 100`` or
    ``balance * rate * Decimal('0.000833...')``. The division by
    ``1200`` occurs exactly once, after the full product is computed,
    because any algebraic rearrangement can produce different
    intermediate rounding behavior against the COBOL baseline.

    The intermediate multiplication is performed inside a
    :func:`decimal.localcontext` block with 28-digit precision so that
    the computation is isolated from any third-party context mutation
    that may have occurred after module import.

    Parameters
    ----------
    balance:
        The category balance (``TRAN-CAT-BAL``, ``PIC S9(09)V99``) in
        dollars.
    annual_rate:
        The annual interest rate (``DIS-INT-RATE``, ``PIC S9(04)V99``)
        as a whole-number percentage — e.g. ``Decimal('12.00')`` for
        12% APR, **not** ``Decimal('0.12')``. This matches the COBOL
        convention where the rate field stores the percentage value
        directly and division by ``1200`` (= 12 months × 100 percent)
        performs both the annualize-to-monthly and the
        percent-to-decimal conversion in a single step.

    Returns
    -------
    Decimal
        The monthly interest amount rounded to 2 decimal places using
        Banker's rounding (matching the COBOL ``ROUNDED`` keyword on
        the original ``COMPUTE`` statement).

    Examples
    --------
    >>> calculate_interest(Decimal('1000.00'), Decimal('12.00'))
    Decimal('10.00')
    >>> calculate_interest(Decimal('2500.00'), Decimal('18.00'))
    Decimal('37.50')
    >>> calculate_interest(Decimal('0.00'), Decimal('15.00'))
    Decimal('0.00')
    >>> calculate_interest(Decimal('1000.00'), Decimal('0.00'))
    Decimal('0.00')
    """
    # Explicit local Decimal context guarantees precision for the intermediate
    # multiplication regardless of any module-external context changes.
    with localcontext() as ctx:
        ctx.prec = 28
        # Step 1: ( TRAN-CAT-BAL * DIS-INT-RATE )  — preserve parenthesization.
        product = balance * annual_rate
        # Step 2: / 1200  — single division, not / 12 / 100.
        monthly_interest = product / Decimal("1200")

    # Step 3: Apply COBOL ROUNDED — Banker's rounding to 2 decimal places.
    return round_financial(monthly_interest)


def is_within_cobol_range(
    value: Decimal,
    pic_digits_before: int = 9,
    pic_digits_after: int = 2,
) -> bool:
    """Check whether a :class:`~decimal.Decimal` fits in a COBOL PIC field.

    Validates the signed range for a ``PIC S9(n)V99``-style field. The
    maximum absolute value is ``10 ** pic_digits_before - 10 ** -pic_digits_after``;
    the minimum is the negative of the maximum (signed field).

    Use this function before persisting a computed value to a database
    column whose width is derived from a COBOL copybook, or before
    returning a value to a batch job that will write it to a fixed-width
    S3 output file.

    Parameters
    ----------
    value:
        The value to check.
    pic_digits_before:
        Number of digits before the implied decimal point (``n`` in
        ``PIC S9(n)V99``). Default ``9`` — the most common CardDemo
        layout.
    pic_digits_after:
        Number of digits after the implied decimal point. Default ``2``
        (``V99``).

    Returns
    -------
    bool
        ``True`` when ``min_value <= value <= max_value``, else ``False``.

    Examples
    --------
    >>> is_within_cobol_range(Decimal('999999999.99'))
    True
    >>> is_within_cobol_range(Decimal('1000000000.00'))
    False
    >>> is_within_cobol_range(Decimal('-999999999.99'))
    True
    >>> is_within_cobol_range(Decimal('-1000000000.00'))
    False
    >>> is_within_cobol_range(Decimal('0.00'))
    True
    >>> is_within_cobol_range(Decimal('9999.99'), pic_digits_before=4)
    True
    """
    if pic_digits_before < 0 or pic_digits_after < 0:
        raise ValueError(
            f"pic_digits_before and pic_digits_after must be non-negative; "
            f"got pic_digits_before={pic_digits_before}, "
            f"pic_digits_after={pic_digits_after}"
        )

    max_value = Decimal(10) ** pic_digits_before - Decimal(10) ** -pic_digits_after
    min_value = -max_value
    return min_value <= value <= max_value


__all__ = [
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
]
