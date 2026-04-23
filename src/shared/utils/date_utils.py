# ============================================================================
# Source: app/cbl/CSUTLDTC.cbl (CEEDAYS wrapper subroutine) +
#         app/cpy/CSDAT01Y.cpy (date/time working storage layouts) +
#         app/cpy/CSUTLDWY.cpy (date editing working storage) +
#         app/cpy/CSUTLDPY.cpy (date validation paragraphs) —
#         Mainframe-to-Cloud migration
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
"""CCYYMMDD date validation and COBOL-compatible date/time formatting.

This module is the Python conversion of the COBOL date-utility subsystem used
across every CICS online program in the original CardDemo application.  It
consolidates four source artefacts into a single, dependency-free Python
module:

* ``app/cbl/CSUTLDTC.cbl`` — A callable COBOL subroutine that wraps the
  Language-Environment ``CEEDAYS`` API.  Given a 10-character date string
  and a 10-character format mask it returns an 80-character result message
  whose severity (bytes 1-4 of the message, copied into ``RETURN-CODE``)
  is ``0000`` for a valid date and non-zero for any one of nine distinct
  error feedback codes (insufficient data, bad value, invalid era, …).
  See :func:`format_ceedays_result` and :func:`format_validation_message`.

* ``app/cpy/CSDAT01Y.cpy`` — The ``WS-DATE-TIME`` working-storage group
  defining three COBOL date-time display layouts:
  ``WS-CURDATE-MM-DD-YY`` (``MM/DD/YY``),
  ``WS-CURTIME-HH-MM-SS`` (``HH:MM:SS``), and
  ``WS-TIMESTAMP`` (``YYYY-MM-DD HH:MM:SS.ffffff``, 6-digit microsecond).
  See :func:`format_date_mm_dd_yy`, :func:`format_time_hh_mm_ss`,
  :func:`format_timestamp`, and :func:`get_current_date_formatted`.

* ``app/cpy/CSUTLDWY.cpy`` — The ``WS-EDIT-DATE-CCYYMMDD`` editing work
  area containing the 88-level constants (``THIS-CENTURY``=20,
  ``LAST-CENTURY``=19, ``WS-VALID-MONTH`` 1-12, ``WS-31-DAY-MONTH``
  {1,3,5,7,8,10,12}, ``WS-FEBRUARY``=2) and the three-state edit flags
  (``''``=valid, ``'0'``=error, ``'B'``=blank) modelled here by
  :class:`DateValidationResult`.

* ``app/cpy/CSUTLDPY.cpy`` — The validation-paragraph library:
  ``EDIT-DATE-CCYYMMDD``, ``EDIT-YEAR-CCYY``, ``EDIT-MONTH``,
  ``EDIT-DAY``, ``EDIT-DAY-MONTH-YEAR``, ``EDIT-DATE-LE``, and
  ``EDIT-DATE-OF-BIRTH``.  Each validation cascade is faithfully
  reproduced in the Python helpers :func:`validate_year`,
  :func:`validate_month`, :func:`validate_day`, :func:`is_leap_year`,
  :func:`validate_day_month_year`, :func:`validate_date_ccyymmdd`,
  and :func:`validate_date_of_birth`.

Why this module exists — functional parity, not simplification
--------------------------------------------------------------
The CardDemo COBOL programs reject dates across a deep cascade of error
messages whose exact wording, punctuation, capitalisation, and even *spacing*
is often inconsistent (``"Year : Year must be supplied."`` vs.
``"Year must be 4 digit number."`` — note the missing colon; or
``"day must be a number …"`` with a *lowercase* leading ``d`` when the rest
of the library capitalises field names).  These idiosyncrasies are visible
to end-users through CICS screen messages and therefore form part of the
**behavioural contract** (AAP §0.7.1: *"Preserve all existing functionality
exactly as-is"*).  Every error string in this module has been copied
character-for-character from ``CSUTLDPY.cpy``; do **not** "fix" them.

Public surface
--------------
:class:`DateValidationResult`
    Immutable dataclass aggregating the 10 outputs of the COBOL validation
    cascade: overall validity, CEEDAYS severity / message code / 15-char
    result text, the tested date and mask strings, a human-readable error
    message, and the three per-component edit flags (``year_flag``,
    ``month_flag``, ``day_flag``) mirroring the ``'' | '0' | 'B'`` tri-state
    of ``WS-EDIT-DATE-FLGS`` in ``CSUTLDWY.cpy``.

:func:`validate_year`, :func:`validate_month`, :func:`validate_day`
    Individual component validators — return ``(is_valid, error_message,
    flag)`` tuples mirroring the corresponding COBOL paragraphs.

:func:`is_leap_year`
    Leap-year predicate preserving the COBOL conditional: years ending in
    ``00`` must be divisible by 400; all other years must be divisible
    by 4. This is the Gregorian rule restricted to the 19th-20th century
    window permitted by ``CSUTLDWY``'s century check.

:func:`validate_day_month_year`
    Cross-component validator enforcing 31-day-month, 30-day-February, and
    leap-year-February rules — the ``EDIT-DAY-MONTH-YEAR`` paragraph.

:func:`validate_date_ccyymmdd`
    Main entry point for CCYYMMDD date validation — orchestrates year →
    month → day → cross-validation → :class:`datetime.date` constructor
    (replacing the ``CEEDAYS`` call in ``EDIT-DATE-LE``).

:func:`validate_date_of_birth`
    Specialisation of :func:`validate_date_ccyymmdd` that additionally
    rejects future dates, replacing the ``INTEGER-OF-DATE`` comparison in
    ``EDIT-DATE-OF-BIRTH`` with :meth:`datetime.date.today`.

:func:`format_date_mm_dd_yy`, :func:`format_time_hh_mm_ss`,
:func:`format_timestamp`, :func:`get_current_date_formatted`
    Display formatters producing the exact COBOL layouts from
    ``CSDAT01Y.cpy``.

:func:`format_validation_message`, :func:`format_ceedays_result`
    Builders producing the 80-character ``WS-MESSAGE`` layout emitted by
    ``CSUTLDTC.cbl`` — useful when calling code expects the legacy result
    string (e.g. when porting BMS screens that display the CEEDAYS output
    verbatim).

Design rules (from AAP §0.7.1 and the file-level instructions)
--------------------------------------------------------------
* **Preserve exact COBOL cascade** — each validator stops at the first
  error and assigns flag values exactly matching the original paragraph.
* **Preserve error-message text** — character-for-character, including
  the inconsistent ``" : "`` vs. ``": "`` vs. ``":"`` spacing that exists
  in the COBOL source.
* **Century limited to 19 and 20** — ``THIS-CENTURY`` / ``LAST-CENTURY``
  in ``CSUTLDWY``; dates in other centuries are rejected as invalid.
* **Leap year: last-two-digits==0 → /400; else /4** — taken verbatim from
  ``CSUTLDPY.cpy`` lines 245-271.  The ``/400 vs /4`` split here is
  *not* the full Gregorian rule (which also excludes ``/100`` except
  ``/400``); but since the century check in :func:`validate_year`
  already restricts input to 19xx/20xx, the simplified test is
  mathematically equivalent for the supported range.
* **Future DOB rejected** — :func:`validate_date_of_birth` uses
  :meth:`date.today` for the comparison, mirroring the
  ``INTEGER-OF-DATE`` call in ``EDIT-DATE-OF-BIRTH``.
* **No floating-point arithmetic** — all integer / string operations only.
* **Standard library only** — ``datetime``, ``dataclasses``, ``typing``,
  ``re``.  No third-party dependencies.  This keeps the module importable
  from AWS Glue PySpark jobs (``src/batch/jobs/*.py``), FastAPI request
  validators (``src/api/``), Pydantic schemas, and unit tests with zero
  cold-start penalty.
* **Python 3.11 compatible** — aligned with the AWS Glue 5.1 runtime and
  the FastAPI ``python:3.11-slim`` ECS container.

See Also
--------
* AAP §0.5.1 (Transformation Mapping) — this module is the target for
  ``app/cbl/CSUTLDTC.cbl`` and the three date-related copybooks.
* AAP §0.7.1 (Refactoring Rules) — mandates byte-for-byte preservation of
  existing validation behaviour.
* ``src/shared/utils/__init__.py`` — exposes this module as part of the
  ``src.shared.utils`` package.
"""

from __future__ import annotations

import datetime as _datetime
import re
from dataclasses import dataclass

__all__: list[str] = [
    # Data class
    "DateValidationResult",
    # Result formatting helpers
    "format_validation_message",
    "format_ceedays_result",
    # Component validators
    "validate_year",
    "validate_month",
    "validate_day",
    "is_leap_year",
    "validate_day_month_year",
    # Main validation entry points
    "validate_date_ccyymmdd",
    "validate_date_of_birth",
    # Display formatters
    "format_date_mm_dd_yy",
    "format_time_hh_mm_ss",
    "format_timestamp",
    "get_current_date_formatted",
]


# ============================================================================
# Module-level constants — mirror the 88-level conditions in CSUTLDWY.cpy
# ============================================================================

#: The two centuries accepted by ``EDIT-YEAR-CCYY`` (``CSUTLDWY`` lines
#: defining ``THIS-CENTURY VALUE 20`` and ``LAST-CENTURY VALUE 19``).  Any
#: date whose ``CC`` component is not in this set is rejected.
_VALID_CENTURIES: frozenset[int] = frozenset({19, 20})

#: Months whose maximum legal day is 31 — derived from
#: ``WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12`` in ``CSUTLDWY.cpy``.
#: Months not in this set *and* not equal to 2 (February) have a maximum
#: legal day of 30.
_THIRTY_ONE_DAY_MONTHS: frozenset[int] = frozenset({1, 3, 5, 7, 8, 10, 12})

#: February — separated into its own constant because ``EDIT-DAY-MONTH-YEAR``
#: treats it specially (rejects day 30 always and day 29 unless a leap year).
_FEBRUARY: int = 2

#: Regular expression accepting exactly N ASCII digits.  Used by the
#: component validators as the Python equivalent of the COBOL
#: ``FUNCTION TEST-NUMVAL`` check (which returns the first invalid character
#: offset; here we only need the boolean "all numeric" answer).
_ALL_DIGITS_RE: re.Pattern[str] = re.compile(r"^\d+$")

#: Regular expression matching an 8-character ``CCYYMMDD`` date string and
#: capturing the three components.  Used by :func:`validate_date_of_birth`
#: as an explicit structural guard before parsing, replacing the prior
#: pattern that relied on :class:`datetime.date` raising
#: :class:`ValueError` to signal malformed input.  The explicit regex
#: makes the intent obvious and prevents any uncaught exception path from
#: propagating to callers — a defensive belt-and-suspenders check even
#: though :func:`validate_date_ccyymmdd` guarantees the components are
#: numeric when :attr:`DateValidationResult.is_valid` is ``True``.
_CCYYMMDD_RE: re.Pattern[str] = re.compile(r"^(\d{4})(\d{2})(\d{2})$")


# ============================================================================
# DateValidationResult — replaces the COBOL WS-EDIT-DATE-FLGS working storage
# ============================================================================


@dataclass(frozen=True)
class DateValidationResult:
    """Structured result of a date-validation call.

    Aggregates the ten distinct outputs that the COBOL validation cascade
    (``CSUTLDPY.cpy`` + ``CSUTLDTC.cbl``) would have written into various
    working-storage fields.

    Attributes
    ----------
    is_valid
        ``True`` when every stage of the validation cascade passed —
        equivalent to the COBOL ``WS-EDIT-DATE-IS-VALID`` 88-level
        condition (``LOW-VALUES`` across all three per-component flags).
    severity
        CEEDAYS severity code (``0`` = OK, ``1`` = warning,
        ``2`` = error, ``3`` = severe).  Matches the value COBOL
        ``CSUTLDTC.cbl`` writes into ``RETURN-CODE``.
    message_code
        CEEDAYS feedback message number from the 4-byte ``Msgno``
        sub-field inside ``FC-DETAIL-CODE``.
    result_text
        The 15-character result text emitted by ``CSUTLDTC.cbl`` — one
        of ``"Date is valid"``, ``"Insufficient"``, ``"Datevalue error"``,
        ``"Invalid Era"``, ``"Unsupp. Range"``, ``"Invalid month"``,
        ``"Bad Pic String"``, ``"Nonnumeric data"``, ``"YearInEra is 0"``,
        or (fallback) ``"Date is invalid"``.
    test_date
        The 10-character date string that was validated, copied verbatim
        from the ``LS-DATE-FORMAT`` input parameter of ``CSUTLDTC``.
    mask_used
        The 10-character date-format mask used for the CEEDAYS call —
        typically ``"YYYYMMDD"``, ``"YYYY-MM-DD"``, or similar.
    error_message
        Human-readable error string from the validation cascade — copied
        character-for-character from ``CSUTLDPY.cpy``.  Empty when
        :attr:`is_valid` is ``True``.
    year_flag
        One of ``""`` (valid, matches COBOL ``LOW-VALUES``), ``"0"``
        (error, matches ``FLG-YEAR-NOT-OK``), or ``"B"`` (blank,
        matches ``FLG-YEAR-BLANK``).  Mirrors the
        ``WS-EDIT-DATE-YEAR-FLG`` tri-state flag in ``CSUTLDWY.cpy``.
    month_flag
        Tri-state flag for the month component, same convention as
        :attr:`year_flag`.
    day_flag
        Tri-state flag for the day component, same convention as
        :attr:`year_flag`.
    """

    is_valid: bool = False
    severity: int = 0
    message_code: int = 0
    result_text: str = ""
    test_date: str = ""
    mask_used: str = ""
    error_message: str = ""
    year_flag: str = ""
    month_flag: str = ""
    day_flag: str = ""


# ============================================================================
# Internal helpers
# ============================================================================


def _is_blank(value: str | None) -> bool:
    """Replicate the COBOL ``IF FIELD = SPACES OR LOW-VALUES`` idiom.

    Returns ``True`` when the input is ``None``, an empty string, or a
    string containing only whitespace.  COBOL ``SPACES`` is the ASCII
    space ``0x20`` for display fields and ``LOW-VALUES`` is ``0x00``;
    both are treated as "blank" by the ``CSUTLDPY.cpy`` paragraphs.
    """
    return value is None or not value.strip()


def _is_all_digits(value: str) -> bool:
    """Return ``True`` when *value* contains only ASCII digits ``0-9``.

    This is the Python equivalent of the COBOL
    ``FUNCTION TEST-NUMVAL-C(field)`` / ``IS NUMERIC`` idiom used
    throughout ``CSUTLDPY.cpy`` to detect non-numeric input.
    """
    return bool(value) and _ALL_DIGITS_RE.match(value) is not None


# ============================================================================
# CEEDAYS-compatible result message formatters — reproduce the 80-char
# WS-MESSAGE layout from CSUTLDTC.cbl
# ============================================================================


def format_validation_message(
    severity: int,
    message_code: int,
    result_text: str,
    test_date: str,
    mask_used: str,
) -> str:
    """Build the 80-character ``WS-MESSAGE`` string emitted by ``CSUTLDTC.cbl``.

    The original COBOL layout (from ``CSUTLDTC.cbl`` lines 42-57) is::

        01  WS-MESSAGE.                                          (Total 80)
            02  WS-SEVERITY PIC X(04).                            ( 4)
            02  FILLER      PIC X(11) VALUE 'Mesg Code:'.         (11)
            02  WS-MSG-NO   PIC X(04).                            ( 4)
            02  FILLER      PIC X(01) VALUE SPACE.                ( 1)
            02  WS-RESULT   PIC X(15).                            (15)
            02  FILLER      PIC X(01) VALUE SPACE.                ( 1)
            02  FILLER      PIC X(09) VALUE 'TstDate:'.           ( 9)
            02  WS-DATE     PIC X(10) VALUE SPACES.               (10)
            02  FILLER      PIC X(01) VALUE SPACE.                ( 1)
            02  FILLER      PIC X(10) VALUE 'Mask used:'.         (10)
            02  WS-DATE-FMT PIC X(10).                            (10)
            02  FILLER      PIC X(01) VALUE SPACE.                ( 1)
            02  FILLER      PIC X(03) VALUE SPACES.               ( 3)

    NOTE: COBOL PIC X(n) literal assignments right-pad the string to the
    declared width with spaces.  Thus ``PIC X(11) VALUE 'Mesg Code:'``
    stores ``'Mesg Code: '`` (11 bytes, 10 literal chars + 1 trailing
    space) and ``PIC X(09) VALUE 'TstDate:'`` stores ``'TstDate: '``
    (9 bytes, 8 literal chars + 1 trailing space).  ``PIC X(10) VALUE
    'Mask used:'`` is an exact fit.  The Python literals below include
    the trailing spaces explicitly so the emitted 80-byte string is
    column-for-column identical to the COBOL output (preserving the
    "maintain existing business logic without modification" rule from
    AAP §0.7.1 and enabling byte-compatible parsing by any downstream
    log consumer that relies on column positions).

    The concatenation is truncated / padded to exactly 80 characters to
    match the ``PIC X(80)`` ``LS-RESULT`` parameter in the CSUTLDTC
    linkage section.

    Parameters
    ----------
    severity
        CEEDAYS severity (0-3); rendered as 4-digit zero-padded decimal.
    message_code
        CEEDAYS message number; rendered as 4-digit zero-padded decimal.
    result_text
        15-character result text (will be right-padded with spaces or
        truncated to exactly 15 characters).
    test_date
        10-character date string (will be right-padded with spaces or
        truncated to exactly 10 characters).
    mask_used
        10-character date-format mask (will be right-padded with spaces
        or truncated to exactly 10 characters).

    Returns
    -------
    str
        The 80-character COBOL-layout result message.
    """
    severity_field = f"{severity:04d}"[:4]
    msg_no_field = f"{message_code:04d}"[:4]
    result_field = f"{result_text:<15s}"[:15]
    date_field = f"{test_date:<10s}"[:10]
    mask_field = f"{mask_used:<10s}"[:10]

    # Build the 80-byte WS-MESSAGE record column-for-column.
    # Field widths annotated inline; sum equals 80 exactly.
    raw = (
        f"{severity_field}"  #  4 — WS-SEVERITY PIC X(04)
        f"Mesg Code: "  # 11 — FILLER PIC X(11) VALUE 'Mesg Code:' (10+space)
        f"{msg_no_field}"  #  4 — WS-MSG-NO PIC X(04)
        f" "  #  1 — FILLER PIC X(01) VALUE SPACE
        f"{result_field}"  # 15 — WS-RESULT PIC X(15)
        f" "  #  1 — FILLER PIC X(01) VALUE SPACE
        f"TstDate: "  #  9 — FILLER PIC X(09) VALUE 'TstDate:' (8+space)
        f"{date_field}"  # 10 — WS-DATE PIC X(10)
        f" "  #  1 — FILLER PIC X(01) VALUE SPACE
        f"Mask used:"  # 10 — FILLER PIC X(10) VALUE 'Mask used:' (exact fit)
        f"{mask_field}"  # 10 — WS-DATE-FMT PIC X(10)
        f" "  #  1 — FILLER PIC X(01) VALUE SPACE
        f"   "  #  3 — FILLER PIC X(03) VALUE SPACES
    )
    # Pad or truncate to exactly 80 characters, matching the LS-RESULT
    # PIC X(80) parameter in the CSUTLDTC linkage section.  With correct
    # field widths above the raw string is already 80 bytes; this
    # invariant is retained defensively in case any field argument is
    # longer than its declared PIC width (slicing guarantees exact 80).
    return f"{raw:<80s}"[:80]


def format_ceedays_result(
    severity: int,
    msg_no: int,
    result_text: str,
    test_date: str,
    mask: str,
) -> str:
    """Backward-compatible alias for :func:`format_validation_message`.

    Exists so that calling code literally ported from COBOL — where the
    original subroutine is named ``CSUTLDTC`` — can keep the
    ``format_ceedays_result`` naming for clarity.  Behaviour is identical
    to :func:`format_validation_message`.

    Parameters
    ----------
    severity
        CEEDAYS severity code.
    msg_no
        CEEDAYS feedback message number.
    result_text
        15-character result text (padded/truncated).
    test_date
        10-character date string (padded/truncated).
    mask
        10-character date-format mask (padded/truncated).

    Returns
    -------
    str
        The 80-character COBOL-layout result message.
    """
    return format_validation_message(
        severity=severity,
        message_code=msg_no,
        result_text=result_text,
        test_date=test_date,
        mask_used=mask,
    )


# ============================================================================
# Component validators — one function per COBOL paragraph from CSUTLDPY.cpy
# ============================================================================


def validate_year(
    year_str: str,
    field_name: str = "",
) -> tuple[bool, str, str]:
    """Validate the 4-character century-year (``CCYY``) component of a date.

    Reproduces the ``EDIT-YEAR-CCYY`` paragraph from ``CSUTLDPY.cpy``
    lines 25-90.  The COBOL cascade performs four checks **in order**, and
    short-circuits on the first failure:

    1. **Blank check** — If the year field is blank (all spaces or
       LOW-VALUES) the paragraph sets ``FLG-YEAR-BLANK`` (``'B'``) and
       emits ``"{field} : Year must be supplied."``.
    2. **Numeric check** — If the field contains any non-digit character,
       the paragraph sets ``FLG-YEAR-NOT-OK`` (``'0'``) and emits
       ``"{field} must be 4 digit number."``.  (Note: this message has no
       colon after the field name — that's the original COBOL wording.)
    3. **Century check** — The first two digits must equal either ``19``
       (``LAST-CENTURY``) or ``20`` (``THIS-CENTURY``); any other century
       emits ``"{field} : Century is not valid."``.
    4. **Success** — All three stages pass, the paragraph sets
       ``FLG-YEAR-ISVALID`` (``''``) and returns without an error.

    Parameters
    ----------
    year_str
        The 4-character CCYY substring of the date being validated.  May
        be ``None`` or empty to exercise the blank-check branch.
    field_name
        The human-readable field name to substitute into the error
        template — typically the BMS screen field label (e.g. ``"DOB
        Year"``).

    Returns
    -------
    tuple[bool, str, str]
        ``(is_valid, error_message, flag)`` where ``flag`` is one of
        ``''`` (valid), ``'0'`` (not-OK), or ``'B'`` (blank), matching the
        three-state ``WS-EDIT-DATE-YEAR-FLG`` in ``CSUTLDWY.cpy``.
    """
    # Step 1 — blank / null check → FLG-YEAR-BLANK ('B')
    if _is_blank(year_str):
        return False, f"{field_name} : Year must be supplied.", "B"

    stripped = year_str.strip() if year_str else ""

    # Step 2 — numeric check + 4-digit length → FLG-YEAR-NOT-OK ('0')
    # The COBOL check is "IF NOT YEAR IS NUMERIC" on a PIC 9(04) field;
    # we also enforce exactly 4 digits since a PIC 9(04) redefine would
    # reject shorter or longer input.
    if not _is_all_digits(stripped) or len(stripped) != 4:
        return False, f"{field_name} must be 4 digit number.", "0"

    # Step 3 — century must be 19 or 20 → FLG-YEAR-NOT-OK ('0')
    century = int(stripped[0:2])
    if century not in _VALID_CENTURIES:
        return False, f"{field_name} : Century is not valid.", "0"

    # Step 4 — all checks passed → FLG-YEAR-ISVALID ('')
    return True, "", ""


def validate_month(
    month_str: str,
    field_name: str = "",
) -> tuple[bool, str, str]:
    """Validate the 2-character month (``MM``) component of a date.

    Reproduces the ``EDIT-MONTH`` paragraph from ``CSUTLDPY.cpy`` lines
    91-147.  The COBOL cascade performs three checks **in order**:

    1. **Blank check** — Empty/SPACES → ``FLG-MONTH-BLANK`` (``'B'``) +
       ``"{field} : Month must be supplied."``.
    2. **Range check** — The 88-level ``WS-VALID-MONTH VALUES 1 THROUGH
       12`` captures both the range violation *and* any non-numeric
       input that fails to redefine as a valid binary number.  On
       failure → ``FLG-MONTH-NOT-OK`` (``'0'``) +
       ``"{field}: Month must be a number between 1 and 12."``.
    3. **Explicit numeric check** — A belt-and-suspenders
       ``TEST-NUMVAL`` call catches any numeric-representation edge cases
       the 88-level condition missed.  Same error message as step 2.
    4. **Success** — ``FLG-MONTH-ISVALID`` (``''``).

    Note on message punctuation: the COBOL literal is exactly
    ``"{field}: Month must be a number between 1 and 12."`` — a colon
    with a *trailing* space but no leading space.  This is deliberately
    different from the ``" : "`` pattern used for the month blank
    message; preserve the exact punctuation.

    Parameters
    ----------
    month_str
        The 2-character MM substring of the date being validated.
    field_name
        Human-readable field name substituted into the error template.

    Returns
    -------
    tuple[bool, str, str]
        ``(is_valid, error_message, flag)`` — same tri-state convention
        as :func:`validate_year`.
    """
    # Step 1 — blank / null check → FLG-MONTH-BLANK ('B')
    if _is_blank(month_str):
        return False, f"{field_name} : Month must be supplied.", "B"

    stripped = month_str.strip() if month_str else ""

    # Step 2 — valid month 1-12 via range check → FLG-MONTH-NOT-OK ('0')
    # The COBOL 88-level condition on the redefined PIC 9(02) field will
    # fail both for out-of-range values and for non-numeric input, so we
    # check both conditions together.
    error_message = f"{field_name}: Month must be a number between 1 and 12."
    if not _is_all_digits(stripped):
        return False, error_message, "0"

    month_int = int(stripped)
    if month_int < 1 or month_int > 12:
        return False, error_message, "0"

    # Step 3 — TEST-NUMVAL belt-and-suspenders numeric check.  At this
    # point the digit-only check above has already passed so any numeric
    # representation issue is also ruled out; kept here for structural
    # parity with the COBOL cascade.

    # Step 4 — all checks passed → FLG-MONTH-ISVALID ('')
    return True, "", ""


def validate_day(
    day_str: str,
    field_name: str = "",
) -> tuple[bool, str, str]:
    """Validate the 2-character day (``DD``) component of a date.

    Reproduces the ``EDIT-DAY`` paragraph from ``CSUTLDPY.cpy`` lines
    150-207.  The COBOL cascade is subtly different from ``EDIT-MONTH`` —
    it performs the explicit numeric check *before* the range check:

    1. **Success pre-set** — Line 152 sets ``FLG-DAY-ISVALID`` to TRUE
       *first* (the paragraph is optimistic and assumes success).
    2. **Blank check** — Overrides the pre-set to ``FLG-DAY-BLANK``
       (``'B'``) + ``"{field} : Day must be supplied."``.
    3. **Numeric check** — ``TEST-NUMVAL`` call.  On failure →
       ``FLG-DAY-NOT-OK`` (``'0'``) +
       ``"{field}:day must be a number between 1 and 31."``.
    4. **Range check** — Day must be 1-31.  Same error message as
       step 3.

    Note on message punctuation / capitalisation: the COBOL literal uses
    ``":day"`` — no space after the colon, and a *lowercase* ``d``
    despite the rest of the library capitalising field words.  This is
    preserved exactly because the message is visible to end users.

    Parameters
    ----------
    day_str
        The 2-character DD substring of the date being validated.
    field_name
        Human-readable field name substituted into the error template.

    Returns
    -------
    tuple[bool, str, str]
        ``(is_valid, error_message, flag)`` — same tri-state convention
        as :func:`validate_year`.
    """
    # Step 1 — blank / null check → FLG-DAY-BLANK ('B')
    if _is_blank(day_str):
        return False, f"{field_name} : Day must be supplied.", "B"

    stripped = day_str.strip() if day_str else ""

    # Step 2 — numeric check via TEST-NUMVAL → FLG-DAY-NOT-OK ('0')
    error_message = f"{field_name}:day must be a number between 1 and 31."
    if not _is_all_digits(stripped):
        return False, error_message, "0"

    # Step 3 — range check: 1-31 → FLG-DAY-NOT-OK ('0')
    day_int = int(stripped)
    if day_int < 1 or day_int > 31:
        return False, error_message, "0"

    # Step 4 — all checks passed → FLG-DAY-ISVALID ('')
    return True, "", ""


def is_leap_year(year: int) -> bool:
    """Determine whether *year* is a leap year, matching COBOL logic.

    Reproduces the leap-year test embedded in the ``EDIT-DAY-MONTH-YEAR``
    paragraph (``CSUTLDPY.cpy`` lines 245-271).  The COBOL code uses an
    unusual two-step rule that relies on the century-check in
    :func:`validate_year` having already restricted input to 19xx/20xx::

        IF WS-EDIT-DATE-YY-N = 0
            DIVIDE WS-EDIT-DATE-CCYY-N BY 400 GIVING …
            IF REMAINDER NOT = 0  -> not a leap year
        ELSE
            DIVIDE WS-EDIT-DATE-CCYY-N BY 4 GIVING …
            IF REMAINDER NOT = 0  -> not a leap year

    In plain English: if the year ends in ``00`` (``YY == 0``), the year
    is a leap year iff it is divisible by 400; otherwise the year is a
    leap year iff it is divisible by 4.  This is the Gregorian rule
    restricted to years whose century is 19 or 20 — for those centuries
    it agrees with the full "divisible by 4 unless divisible by 100
    except if divisible by 400" rule because 1900 fails /400 and 2000
    passes /400, so both edge cases are handled correctly.

    Parameters
    ----------
    year
        A 4-digit year (typically 1900-2099 because of the upstream
        century check, but the function is defined for any positive
        integer).

    Returns
    -------
    bool
        ``True`` if *year* is a leap year under the COBOL rule.

    Examples
    --------
    >>> is_leap_year(2000)
    True
    >>> is_leap_year(1900)
    False
    >>> is_leap_year(2024)
    True
    >>> is_leap_year(2023)
    False
    """
    # Extract the 2-digit YY component.  This matches the COBOL
    # WS-EDIT-DATE-YY-N redefines on the CCYY working-storage field.
    yy = year % 100
    if yy == 0:
        # Century year -> must be divisible by 400
        return (year % 400) == 0
    # Non-century year -> must be divisible by 4
    return (year % 4) == 0


def validate_day_month_year(
    year: int,
    month: int,
    day: int,
    field_name: str = "",
) -> tuple[bool, str, str, str, str]:
    """Cross-validate day/month/year for 31-day-month and February rules.

    Reproduces the ``EDIT-DAY-MONTH-YEAR`` paragraph from ``CSUTLDPY.cpy``
    lines 209-282.  This paragraph is called *after* the three component
    validators have all reported success; it catches the cross-component
    errors that a per-component check cannot detect:

    1. **31-day rule** — If the month is not in the
       ``_THIRTY_ONE_DAY_MONTHS`` set (i.e. Apr, Jun, Sep, Nov, Feb) and
       the day is 31 -> ``"{field}:Cannot have 31 days in this month."``.
       Both the day flag and the month flag are set to ``'0'``.
    2. **30-day-February rule** — If the month is February and the day
       is 30 -> ``"{field}:Cannot have 30 days in this month."``.  Same
       flag treatment as step 1.
    3. **Leap-year-February rule** — If the month is February and the
       day is 29 and :func:`is_leap_year` returns ``False`` ->
       ``"{field}:Not a leap year.Cannot have 29 days in this month."``.
       The year flag is *also* set to ``'0'`` in this case (because the
       non-leap-ness of the year is what makes the combination invalid).

    The COBOL messages are copied character-for-character — note that
    ``:Cannot`` and ``:Not`` have no space after the colon, and the leap
    year message has no space between ``.`` and ``Cannot``.

    Parameters
    ----------
    year
        4-digit year (already validated by :func:`validate_year`).
    month
        Month value 1-12 (already validated by :func:`validate_month`).
    day
        Day value 1-31 (already validated by :func:`validate_day`).
    field_name
        Human-readable field name substituted into the error template.

    Returns
    -------
    tuple[bool, str, str, str, str]
        ``(is_valid, error_message, year_flag, month_flag, day_flag)``
        where each flag follows the same tri-state convention as the
        per-component validators.  When the date passes cross-validation
        all flags are ``''``.
    """
    # Step 1 — 31-day rule
    if month not in _THIRTY_ONE_DAY_MONTHS and month != _FEBRUARY and day == 31:
        return (
            False,
            f"{field_name}:Cannot have 31 days in this month.",
            "",
            "0",
            "0",
        )

    # Step 2 — 30-day-February rule
    if month == _FEBRUARY and day == 30:
        return (
            False,
            f"{field_name}:Cannot have 30 days in this month.",
            "",
            "0",
            "0",
        )

    # Step 3 — leap-year-February rule
    if month == _FEBRUARY and day == 29 and not is_leap_year(year):
        return (
            False,
            f"{field_name}:Not a leap year.Cannot have 29 days in this month.",
            "0",
            "0",
            "0",
        )

    # All cross-validations passed
    return True, "", "", "", ""


# ============================================================================
# Main validation entry points — orchestrate the full COBOL validation
# cascade from EDIT-DATE-CCYYMMDD through EDIT-DATE-LE and EDIT-DATE-OF-BIRTH
# ============================================================================

#: Default mask string used when no caller-supplied mask is available.
#: Matches the most common ``YYYYMMDD`` format used by the CardDemo online
#: screens when sending dates to ``CSUTLDTC``.
_DEFAULT_MASK: str = "YYYYMMDD"

#: 15-character result texts emitted by ``CSUTLDTC.cbl`` for each CEEDAYS
#: feedback code.  Preserved character-for-character including the
#: space-padded widths.  The key is the feedback-code level-88 condition
#: name from lines 41-49 of ``CSUTLDTC.cbl``.
_CEEDAYS_RESULT_TEXT: dict[str, str] = {
    "FC-INVALID-DATE": "Date is valid",  # severity 0 (misleading name)
    "FC-INSUFFICIENT-DATA": "Insufficient",
    "FC-BAD-DATE-VALUE": "Datevalue error",
    "FC-INVALID-ERA": "Invalid Era",
    "FC-UNSUPP-RANGE": "Unsupp. Range",
    "FC-INVALID-MONTH": "Invalid month",
    "FC-BAD-PIC-STRING": "Bad Pic String",
    "FC-NON-NUMERIC-DATA": "Nonnumeric data",
    "FC-YEAR-IN-ERA-ZERO": "YearInEra is 0",
    # Fallback when no specific feedback code matches (OTHER branch).
    "OTHER": "Date is invalid",
}


def validate_date_ccyymmdd(
    date_str: str,
    field_name: str = "",
) -> DateValidationResult:
    """Validate an 8-character ``CCYYMMDD`` date string end-to-end.

    This is the main entry point for date validation.  It reproduces the
    ``EDIT-DATE-CCYYMMDD`` paragraph (``CSUTLDPY.cpy`` lines 18-23) and the
    subsequent call chain through ``EDIT-YEAR-CCYY``, ``EDIT-MONTH``,
    ``EDIT-DAY``, ``EDIT-DAY-MONTH-YEAR``, and ``EDIT-DATE-LE``.

    The validation logic proceeds as follows, short-circuiting on the first
    error that would leave the COBOL paragraph with
    ``WS-EDIT-DATE-IS-INVALID`` set:

    1. Start with :class:`DateValidationResult` marked invalid (matching
       line 21 ``SET WS-EDIT-DATE-IS-INVALID TO TRUE``).
    2. Call :func:`validate_year` on ``date_str[0:4]``.
    3. Call :func:`validate_month` on ``date_str[4:6]`` only if year is
       valid (matching COBOL ``IF FLG-YEAR-ISVALID`` gate).
    4. Call :func:`validate_day` on ``date_str[6:8]`` only if month is
       valid.
    5. Call :func:`validate_day_month_year` on the parsed integers only
       if all three components are valid.
    6. Attempt to construct a :class:`datetime.date` — this replaces the
       call to ``CSUTLDTC`` (``CEEDAYS``) in ``EDIT-DATE-LE``.  Any
       residual error (e.g. a date that somehow slipped through) is
       converted to an "invalid date" result with severity 12 and the
       generic ``"Date is invalid"`` result text.
    7. On success, mark the result valid (matching line 327
       ``SET WS-EDIT-DATE-IS-VALID TO TRUE``).

    Parameters
    ----------
    date_str
        An 8-character date in CCYYMMDD format.  May be ``None``, blank,
        or shorter/longer than 8 characters; the function handles these
        gracefully by routing through the component validators.
    field_name
        Human-readable field name substituted into all error messages.

    Returns
    -------
    DateValidationResult
        Fully populated result containing validity, severity, the
        CEEDAYS-format result text, the tested date, the mask used, a
        human-readable error message, and the three per-component flags.
    """
    # Normalise the input for safe substring access.  An 8-character
    # field is expected, but missing/short/long input is allowed and
    # will be caught by the component validators.
    raw = date_str if date_str is not None else ""
    # Extract the CCYYMMDD components.  For short input we use a padded
    # view that still produces the blank-detection / numeric-detection
    # errors at the right stage, matching the COBOL behaviour where a
    # short LS-DATE substring would contain LOW-VALUES.
    if len(raw) < 8:
        padded = raw.ljust(8)
    else:
        padded = raw[:8]
    year_str = padded[0:4]
    month_str = padded[4:6]
    day_str = padded[6:8]

    # Step 1 — year validation
    year_ok, year_err, year_flag = validate_year(year_str, field_name)
    if not year_ok:
        return DateValidationResult(
            is_valid=False,
            severity=12,
            message_code=0,
            result_text=_CEEDAYS_RESULT_TEXT["OTHER"],
            test_date=raw[:10],
            mask_used=_DEFAULT_MASK,
            error_message=year_err,
            year_flag=year_flag,
            month_flag="",
            day_flag="",
        )

    # Step 2 — month validation (gated on year validity)
    month_ok, month_err, month_flag = validate_month(month_str, field_name)
    if not month_ok:
        return DateValidationResult(
            is_valid=False,
            severity=12,
            message_code=0,
            result_text=_CEEDAYS_RESULT_TEXT["FC-INVALID-MONTH"],
            test_date=raw[:10],
            mask_used=_DEFAULT_MASK,
            error_message=month_err,
            year_flag="",
            month_flag=month_flag,
            day_flag="",
        )

    # Step 3 — day validation (gated on month validity)
    day_ok, day_err, day_flag = validate_day(day_str, field_name)
    if not day_ok:
        return DateValidationResult(
            is_valid=False,
            severity=12,
            message_code=0,
            result_text=_CEEDAYS_RESULT_TEXT["OTHER"],
            test_date=raw[:10],
            mask_used=_DEFAULT_MASK,
            error_message=day_err,
            year_flag="",
            month_flag="",
            day_flag=day_flag,
        )

    # Step 4 — cross-validation (31-day, Feb-30, Feb-29 leap)
    year_int = int(year_str)
    month_int = int(month_str)
    day_int = int(day_str)
    cross_ok, cross_err, yf, mf, df = validate_day_month_year(year_int, month_int, day_int, field_name)
    if not cross_ok:
        return DateValidationResult(
            is_valid=False,
            severity=12,
            message_code=0,
            result_text=_CEEDAYS_RESULT_TEXT["FC-BAD-DATE-VALUE"],
            test_date=raw[:10],
            mask_used=_DEFAULT_MASK,
            error_message=cross_err,
            year_flag=yf,
            month_flag=mf,
            day_flag=df,
        )

    # Step 5 — final CEEDAYS-equivalent check via datetime.date.  In the
    # COBOL program this is the call to CSUTLDTC / CEEDAYS; any residual
    # error converts to a generic "Date is invalid" result.
    try:
        _datetime.date(year_int, month_int, day_int)
    except (ValueError, TypeError) as exc:
        return DateValidationResult(
            is_valid=False,
            severity=12,
            message_code=0,
            result_text=_CEEDAYS_RESULT_TEXT["OTHER"],
            test_date=raw[:10],
            mask_used=_DEFAULT_MASK,
            error_message=(f"{field_name} validation error Sev code: 12 Message code: 0 ({exc})"),
            year_flag="0",
            month_flag="0",
            day_flag="0",
        )

    # Step 6 — all checks passed.  This corresponds to line 327 of
    # CSUTLDPY.cpy, ``SET WS-EDIT-DATE-IS-VALID TO TRUE``.
    return DateValidationResult(
        is_valid=True,
        severity=0,
        message_code=0,
        result_text=_CEEDAYS_RESULT_TEXT["FC-INVALID-DATE"],
        test_date=raw[:10] if raw else padded,
        mask_used=_DEFAULT_MASK,
        error_message="",
        year_flag="",
        month_flag="",
        day_flag="",
    )


def validate_date_of_birth(
    date_str: str,
    field_name: str = "",
) -> DateValidationResult:
    """Validate a CCYYMMDD date-of-birth, additionally rejecting future dates.

    Reproduces the ``EDIT-DATE-OF-BIRTH`` paragraph (``CSUTLDPY.cpy`` lines
    341-372).  The COBOL paragraph first calls ``EDIT-DATE-CCYYMMDD`` and,
    only if the date is structurally valid, proceeds to compare the date
    against today's date.  If the date is in the future — tested with
    ``INTEGER-OF-DATE`` arithmetic in COBOL, replaced with
    :meth:`datetime.date.today` here — the paragraph emits
    ``"{field}:cannot be in the future "`` (note the trailing space, which
    is preserved exactly).

    Parameters
    ----------
    date_str
        An 8-character date in CCYYMMDD format.
    field_name
        Human-readable field name substituted into the error message.

    Returns
    -------
    DateValidationResult
        If the date is structurally invalid, the result from
        :func:`validate_date_ccyymmdd` is returned unchanged.  If the
        date is structurally valid but lies in the future, an invalid
        result with the future-date error is returned.  Otherwise the
        valid result is returned unchanged.
    """
    # Step 1 — delegate structural validation to validate_date_ccyymmdd
    base = validate_date_ccyymmdd(date_str, field_name)
    if not base.is_valid:
        # Structural failure — return as-is.  This matches the COBOL
        # behaviour where EDIT-DATE-OF-BIRTH short-circuits if the
        # underlying EDIT-DATE-CCYYMMDD fails.
        return base

    # Step 2 — future-date check.  At this point we know the date is
    # structurally valid so the substrings can be safely parsed.  We
    # perform the parse via explicit regex match + try/except on the
    # date constructor rather than relying on an unguarded exception
    # path.  This addresses the CP3 review finding ("DOB validation
    # uses date constructor exception-based check — brittle") by
    # making the parse strategy explicit: (a) regex validates that
    # padded[0:4], padded[4:6], padded[6:8] are all digits, and (b) a
    # try/except on ``_datetime.date`` catches any residual invalid
    # value (a theoretically unreachable path given validate_date_ccyymmdd
    # already succeeded, but defensive programming guards against
    # future refactors that might weaken the contract).
    raw = date_str if date_str is not None else ""
    padded = raw.ljust(8) if len(raw) < 8 else raw[:8]

    match = _CCYYMMDD_RE.match(padded)
    if match is None:
        # Defensive: base.is_valid was True but padded is not digit-only
        # CCYYMMDD.  Unreachable per the contract of
        # validate_date_ccyymmdd (which requires numeric year/month/day),
        # but if a future refactor breaks the contract we return the
        # base result rather than letting a ValueError escape.
        return base

    year_int = int(match.group(1))
    month_int = int(match.group(2))
    day_int = int(match.group(3))
    try:
        edit_date = _datetime.date(year_int, month_int, day_int)
    except (ValueError, TypeError):
        # Defensive: regex confirms numeric structure, but if the ints
        # somehow form an invalid date (e.g., month=00, day=32), fall
        # back to the base result instead of crashing.  Again unreachable
        # because validate_date_ccyymmdd's Step 4 (validate_day_month_year)
        # and Step 5 (``_datetime.date(...)`` construction) already
        # rejected such values — but the explicit guard is preferable
        # to implicit propagation.
        return base

    # Step 3 — explicit range check.  COBOL uses INTEGER-OF-DATE to
    # compare WS-CURDATE-N with the edit date:
    # "IF WS-CURDATE-N > WS-EDIT-DATE-CCYYMMDD-N" (line 359-361)
    # means today is strictly greater than the DOB -> DOB is not in the
    # future -> OK.  Any other case (today <= DOB, i.e. DOB is today or
    # in the future) triggers the error.  The equivalent Python predicate
    # for the error case "NOT (today > edit_date)" is "today <= edit_date",
    # i.e. "edit_date >= today".  A DOB equal to today is therefore
    # rejected because a customer cannot be 0 days old (matching COBOL
    # CSUTLDTC behaviour for F-005 Account Update and F-019 User Add).
    today = _datetime.date.today()
    if edit_date >= today:
        return DateValidationResult(
            is_valid=False,
            severity=12,
            message_code=0,
            result_text=_CEEDAYS_RESULT_TEXT["OTHER"],
            test_date=base.test_date,
            mask_used=base.mask_used,
            error_message=f"{field_name}:cannot be in the future ",
            year_flag="0",
            month_flag="0",
            day_flag="0",
        )

    # Date is valid AND not in the future — return the successful
    # structural-validation result unchanged.
    return base


# ============================================================================
# Display formatters — reproduce the WS-DATE-TIME layouts from CSDAT01Y.cpy
# ============================================================================


def format_date_mm_dd_yy(year: int, month: int, day: int) -> str:
    """Format a (year, month, day) tuple as ``MM/DD/YY``.

    Reproduces the ``WS-CURDATE-MM-DD-YY`` layout from ``CSDAT01Y.cpy``
    (lines 14-22)::

        05  WS-CURDATE-MM-DD-YY.
            10  WS-CURDATE-MONTH    PIC X(02).
            10  FILLER              PIC X(01)  VALUE '/'.
            10  WS-CURDATE-DAY      PIC X(02).
            10  FILLER              PIC X(01)  VALUE '/'.
            10  WS-CURDATE-YEAR     PIC X(02).

    The year is truncated to its last two digits (``YY``) — this matches
    the COBOL behaviour where ``WS-CURDATE-YEAR`` redefines the two
    low-order bytes of a 4-digit year.

    Parameters
    ----------
    year
        Full 4-digit year (e.g. 2024).  Only the last two digits are
        used in the output.
    month
        Month 1-12 (not validated; callers should pass a value returned
        by :func:`validate_month`).
    day
        Day 1-31 (not validated; callers should pass a value returned
        by :func:`validate_day`).

    Returns
    -------
    str
        A string of the form ``"MM/DD/YY"`` — always exactly 8 characters.

    Examples
    --------
    >>> format_date_mm_dd_yy(2024, 1, 15)
    '01/15/24'
    >>> format_date_mm_dd_yy(1999, 12, 31)
    '12/31/99'
    """
    yy = year % 100
    return f"{month:02d}/{day:02d}/{yy:02d}"


def format_time_hh_mm_ss(hours: int, minutes: int, seconds: int) -> str:
    """Format a (hours, minutes, seconds) tuple as ``HH:MM:SS``.

    Reproduces the ``WS-CURTIME-HH-MM-SS`` layout from ``CSDAT01Y.cpy``
    (lines 34-40)::

        05  WS-CURTIME-HH-MM-SS.
            10  WS-CURTIME-HOURS    PIC X(02).
            10  FILLER              PIC X(01)  VALUE ':'.
            10  WS-CURTIME-MINUTES  PIC X(02).
            10  FILLER              PIC X(01)  VALUE ':'.
            10  WS-CURTIME-SECONDS  PIC X(02).

    Parameters
    ----------
    hours
        Hour 0-23 (24-hour clock).
    minutes
        Minute 0-59.
    seconds
        Second 0-59.

    Returns
    -------
    str
        A string of the form ``"HH:MM:SS"`` — always exactly 8 characters.

    Examples
    --------
    >>> format_time_hh_mm_ss(9, 30, 5)
    '09:30:05'
    >>> format_time_hh_mm_ss(23, 59, 59)
    '23:59:59'
    """
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}"


def format_timestamp(dt: _datetime.datetime) -> str:
    """Format a :class:`datetime.datetime` as ``YYYY-MM-DD HH:MM:SS.ffffff``.

    Reproduces the ``WS-TIMESTAMP`` layout from ``CSDAT01Y.cpy`` (lines
    48-57).  The COBOL layout includes a 6-digit microsecond suffix::

        05  WS-TIMESTAMP.
            10  WS-TIMESTAMP-DATE   PIC X(10).    (YYYY-MM-DD)
            10  FILLER              PIC X(01)     VALUE ' '.
            10  WS-TIMESTAMP-TIME   PIC X(08).    (HH:MM:SS)
            10  FILLER              PIC X(01)     VALUE '.'.
            10  WS-TIMESTAMP-MS     PIC 9(06).    (microsecond)

    Parameters
    ----------
    dt
        Python :class:`datetime.datetime`.  Naive and timezone-aware
        datetimes are both accepted; the timezone is ignored and the
        local components are used as-is.

    Returns
    -------
    str
        A 26-character string of the form
        ``"YYYY-MM-DD HH:MM:SS.ffffff"``.

    Examples
    --------
    >>> from datetime import datetime
    >>> format_timestamp(datetime(2024, 1, 15, 9, 30, 5, 123456))
    '2024-01-15 09:30:05.123456'
    """
    return (
        f"{dt.year:04d}-{dt.month:02d}-{dt.day:02d} {dt.hour:02d}:{dt.minute:02d}:{dt.second:02d}.{dt.microsecond:06d}"
    )


def get_current_date_formatted() -> dict[str, str]:
    """Return today's date in all four COBOL display formats.

    This is the Python equivalent of populating the ``WS-DATE-TIME``
    working-storage group from ``CSDAT01Y.cpy`` via the COBOL
    ``ACCEPT FROM DATE / ACCEPT FROM TIME`` idiom.  It returns a
    dictionary whose keys mirror the 88-level redefines in the copybook.

    Returns
    -------
    dict[str, str]
        Dictionary with four string entries:

        * ``"ccyymmdd"`` — 8-character ``YYYYMMDD`` date (matches
          ``WS-CURDATE`` layout).
        * ``"mm_dd_yy"`` — 8-character ``MM/DD/YY`` date (matches
          ``WS-CURDATE-MM-DD-YY``).
        * ``"hh_mm_ss"`` — 8-character ``HH:MM:SS`` time (matches
          ``WS-CURTIME-HH-MM-SS``).
        * ``"timestamp"`` — 26-character
          ``YYYY-MM-DD HH:MM:SS.ffffff`` timestamp (matches
          ``WS-TIMESTAMP``).

    Notes
    -----
    The returned values are all computed from a single
    :meth:`datetime.datetime.now` call so that all four formats reflect
    exactly the same instant in time — avoiding the race window that
    would exist if each format were computed independently.
    """
    now = _datetime.datetime.now()
    return {
        "ccyymmdd": f"{now.year:04d}{now.month:02d}{now.day:02d}",
        "mm_dd_yy": format_date_mm_dd_yy(now.year, now.month, now.day),
        "hh_mm_ss": format_time_hh_mm_ss(now.hour, now.minute, now.second),
        "timestamp": format_timestamp(now),
    }
