# ============================================================================
# CardDemo — Date Utility Unit Tests (Mainframe-to-Cloud migration)
# ============================================================================
# Source (mainframe heritage):
#   * app/cbl/CSUTLDTC.cbl          — Date validation utility
#                                     (wraps Language Environment CEEDAYS).
#   * app/cpy/CSDAT01Y.cpy          — Date formats / mask constants.
#   * app/cpy/CSUTLDWY.cpy          — Date utility work area (edit flags).
#   * app/cpy/CSUTLDPY.cpy          — EDIT-DATE-CCYYMMDD paragraph
#                                     orchestrating the validation cascade.
#
# Target module:  src/shared/utils/date_utils.py
#
# Test-case organisation
# ----------------------
#   Phase 1  — DateValidationResult dataclass contract        (3 tests)
#   Phase 2  — validate_year                                  (7 tests)
#   Phase 3  — validate_month                                 (6 tests)
#   Phase 4  — validate_day                                   (6 tests)
#   Phase 5  — is_leap_year                                   (9 tests)
#   Phase 6  — validate_day_month_year                        (8 tests)
#   Phase 7  — validate_date_ccyymmdd                         (9 tests)
#   Phase 8  — validate_date_of_birth                         (5 tests)
#   Phase 9  — format_date_mm_dd_yy                           (5 tests)
#   Phase 10 — format_time_hh_mm_ss                           (4 tests)
#   Phase 11 — format_timestamp                               (5 tests)
#   Phase 12 — get_current_date_formatted                     (5 tests)
#   Phase 13 — format_validation_message / format_ceedays_result (6 tests)
#   Phase 14 — __all__ public-API surface guard               (1 test)
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
"""Verify behavioural parity between ``src/shared/utils/date_utils.py``
and the COBOL date-validation subsystem (CSUTLDTC.cbl + CSUTLDPY.cpy).

COBOL → Python Verification Surface
-----------------------------------
+---------------------------------------------+-------------------------------+
| COBOL construct                             | Python target (tested)        |
+=============================================+===============================+
| ``EDIT-YEAR-CCYY`` (CSUTLDPY.cpy L24-62)    | ``validate_year``             |
+---------------------------------------------+-------------------------------+
| ``EDIT-MONTH`` (CSUTLDPY.cpy L64-93)        | ``validate_month``            |
+---------------------------------------------+-------------------------------+
| ``EDIT-DAY`` (CSUTLDPY.cpy L95-124)         | ``validate_day``              |
+---------------------------------------------+-------------------------------+
| ``EDIT-DAY-MONTH-YEAR``                     | ``validate_day_month_year``   |
| (CSUTLDPY.cpy L126-240)                     |                               |
+---------------------------------------------+-------------------------------+
| ``EDIT-DATE-CCYYMMDD``                      | ``validate_date_ccyymmdd``    |
| (CSUTLDPY.cpy L18-23)                       |                               |
+---------------------------------------------+-------------------------------+
| ``EDIT-DATE-OF-BIRTH``                      | ``validate_date_of_birth``    |
| (CSUTLDPY.cpy L242-287)                     |                               |
+---------------------------------------------+-------------------------------+
| CEEDAYS feedback-code formatting            | ``format_validation_message`` |
| (CSUTLDTC.cbl LINKAGE SECTION)              | / ``format_ceedays_result``   |
+---------------------------------------------+-------------------------------+
| Leap-year rule: ``IF YY MOD 100 = 0 THEN``  | ``is_leap_year`` (Gregorian). |
| ``YY MOD 400 = 0 ELSE YY MOD 4 = 0``        |                               |
+---------------------------------------------+-------------------------------+

Business-contract invariants explicitly exercised
-------------------------------------------------
1. **Tri-state flag convention** — ``""`` (valid), ``"0"`` (error),
   ``"B"`` (blank) — mirrored byte-for-byte from ``WS-EDIT-YEAR-FLGS`` /
   ``WS-EDIT-MONTH-FLGS`` / ``WS-EDIT-DAY-FLGS`` in CSUTLDWY.cpy.
2. **Century gate** — only centuries 19 and 20 are accepted
   (i.e. years 1900-2099), matching the COBOL constraint in
   ``EDIT-YEAR-CCYY``.
3. **Error-message punctuation** (verbatim from CSUTLDPY.cpy):
   ``"<field> : Year must be supplied."``        (space before ':')
   ``"<field> must be 4 digit number."``
   ``"<field> : Century is not valid."``
   ``"<field> : Month must be supplied."``
   ``"<field>: Month must be a number between 1 and 12."``
   ``"<field> : Day must be supplied."``
   ``"<field>:day must be a number between 1 and 31."``  (lowercase 'd')
   ``"<field>:Cannot have 31 days in this month."``
   ``"<field>:Cannot have 30 days in this month."``
   ``"<field>:Not a leap year.Cannot have 29 days in this month."``
   ``"<field>:cannot be in the future "``  (NOTE: trailing space!)
4. **DOB equal to today is REJECTED** — matching the COBOL
   ``IF WS-CURR-DATE-N >= WS-EDIT-DATE-BINARY`` rejection path.
5. **Length-8 format invariants** — ``format_date_mm_dd_yy`` always
   returns 8 chars ("MM/DD/YY"); ``format_time_hh_mm_ss`` always
   returns 8 chars ("HH:MM:SS"); ``format_timestamp`` always returns
   26 chars ("YYYY-MM-DD HH:MM:SS.ffffff").
6. **Tri-state flag convention** on DOB future rejection — all three
   flags are "0" because the date is structurally valid but
   chronologically invalid.
"""

from __future__ import annotations

from dataclasses import FrozenInstanceError
from datetime import UTC, date, datetime, timedelta

import pytest

from src.shared.utils.date_utils import (
    DateValidationResult,
    format_ceedays_result,
    format_date_mm_dd_yy,
    format_time_hh_mm_ss,
    format_timestamp,
    format_validation_message,
    get_current_date_formatted,
    is_leap_year,
    validate_date_ccyymmdd,
    validate_date_of_birth,
    validate_day,
    validate_day_month_year,
    validate_month,
    validate_year,
)


# ---------------------------------------------------------------------------
# Phase 1 — DateValidationResult dataclass contract
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_date_validation_result_default_values() -> None:
    """Default-constructed result is invalid with empty strings / zero ints.

    Matches COBOL ``WS-EDIT-DATE-IS-INVALID`` pre-validation state.
    """
    r = DateValidationResult()
    assert r.is_valid is False
    assert r.severity == 0
    assert r.message_code == 0
    assert r.result_text == ""
    assert r.test_date == ""
    assert r.mask_used == ""
    assert r.error_message == ""
    # Tri-state flags start blank (meaning "not yet evaluated")
    assert r.year_flag == ""
    assert r.month_flag == ""
    assert r.day_flag == ""


@pytest.mark.unit
def test_date_validation_result_is_immutable_frozen() -> None:
    """Dataclass is frozen — any post-construction mutation must raise."""
    r = DateValidationResult(is_valid=True)
    with pytest.raises(FrozenInstanceError):
        # Bypass type-check to exercise the runtime frozen contract.
        r.is_valid = False  # type: ignore[misc]


@pytest.mark.unit
def test_date_validation_result_allows_all_kwargs() -> None:
    """All 10 attributes are constructable via keyword arguments."""
    r = DateValidationResult(
        is_valid=True,
        severity=12,
        message_code=2518,
        result_text="Date is valid",
        test_date="20240229",
        mask_used="YYYYMMDD",
        error_message="",
        year_flag="",
        month_flag="",
        day_flag="",
    )
    assert r.is_valid is True
    assert r.severity == 12
    assert r.message_code == 2518
    assert r.result_text == "Date is valid"
    assert r.test_date == "20240229"
    assert r.mask_used == "YYYYMMDD"


# ---------------------------------------------------------------------------
# Phase 2 — validate_year
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_validate_year_accepts_valid_year_in_20th_century() -> None:
    """Year within centuries 19-20 is accepted (CSUTLDPY.cpy L24-62)."""
    ok, err, flag = validate_year("1999", "date")
    assert ok is True
    assert err == ""
    assert flag == ""


@pytest.mark.unit
def test_validate_year_accepts_valid_year_in_21st_century() -> None:
    """Years in century 20 (2000-2099) are accepted."""
    ok, err, flag = validate_year("2024", "date")
    assert ok is True
    assert err == ""
    assert flag == ""


@pytest.mark.unit
def test_validate_year_rejects_blank_with_b_flag() -> None:
    """Empty / whitespace year returns 'B' flag (blank sentinel)."""
    ok, err, flag = validate_year("", "date")
    assert ok is False
    assert err == "date : Year must be supplied."
    assert flag == "B"

    ok2, err2, flag2 = validate_year("   ", "date")
    assert ok2 is False
    assert err2 == "date : Year must be supplied."
    assert flag2 == "B"


@pytest.mark.unit
def test_validate_year_rejects_short_year_with_zero_flag() -> None:
    """Year < 4 chars returns '0' flag (non-blank error)."""
    ok, err, flag = validate_year("12", "date")
    assert ok is False
    assert err == "date must be 4 digit number."
    assert flag == "0"


@pytest.mark.unit
def test_validate_year_rejects_non_numeric() -> None:
    """Non-numeric year returns '0' flag with same message as short."""
    ok, err, flag = validate_year("abcd", "date")
    assert ok is False
    assert err == "date must be 4 digit number."
    assert flag == "0"


@pytest.mark.unit
def test_validate_year_rejects_pre_19th_century() -> None:
    """Century < 19 rejected (e.g. 1800s, 1700s)."""
    ok, err, flag = validate_year("1800", "date")
    assert ok is False
    assert err == "date : Century is not valid."
    assert flag == "0"


@pytest.mark.unit
def test_validate_year_rejects_post_20th_century() -> None:
    """Century > 20 rejected (e.g. 2100+)."""
    ok, err, flag = validate_year("2100", "date")
    assert ok is False
    assert err == "date : Century is not valid."
    assert flag == "0"


# ---------------------------------------------------------------------------
# Phase 3 — validate_month
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_validate_month_accepts_valid_months() -> None:
    """Months 01-12 are accepted."""
    for m in ["01", "02", "06", "09", "12"]:
        ok, err, flag = validate_month(m, "date")
        assert ok is True, f"month {m} should be valid"
        assert err == ""
        assert flag == ""


@pytest.mark.unit
def test_validate_month_rejects_blank_with_b_flag() -> None:
    """Blank month returns 'B' flag."""
    ok, err, flag = validate_month("", "date")
    assert ok is False
    assert err == "date : Month must be supplied."
    assert flag == "B"


@pytest.mark.unit
def test_validate_month_rejects_zero_month() -> None:
    """Month 00 rejected with '0' flag."""
    ok, err, flag = validate_month("00", "date")
    assert ok is False
    # Note: "date:" with no space before colon — exact COBOL punctuation.
    assert err == "date: Month must be a number between 1 and 12."
    assert flag == "0"


@pytest.mark.unit
def test_validate_month_rejects_above_twelve() -> None:
    """Month > 12 rejected."""
    ok, err, flag = validate_month("13", "date")
    assert ok is False
    assert err == "date: Month must be a number between 1 and 12."
    assert flag == "0"


@pytest.mark.unit
def test_validate_month_rejects_non_numeric() -> None:
    """Non-numeric month rejected with same message as out-of-range."""
    ok, err, flag = validate_month("ab", "date")
    assert ok is False
    assert err == "date: Month must be a number between 1 and 12."
    assert flag == "0"


@pytest.mark.unit
def test_validate_month_boundary_values() -> None:
    """Month 01 and 12 are boundary-valid."""
    ok1, _, _ = validate_month("01", "date")
    ok12, _, _ = validate_month("12", "date")
    assert ok1 is True
    assert ok12 is True


# ---------------------------------------------------------------------------
# Phase 4 — validate_day
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_validate_day_accepts_valid_days() -> None:
    """Days 01-31 are accepted (inter-month check is separate)."""
    for d in ["01", "15", "28", "29", "30", "31"]:
        ok, err, flag = validate_day(d, "date")
        assert ok is True, f"day {d} should pass bounds check"
        assert err == ""
        assert flag == ""


@pytest.mark.unit
def test_validate_day_rejects_blank_with_b_flag() -> None:
    """Blank day returns 'B' flag."""
    ok, err, flag = validate_day("", "date")
    assert ok is False
    assert err == "date : Day must be supplied."
    assert flag == "B"


@pytest.mark.unit
def test_validate_day_rejects_zero_day() -> None:
    """Day 00 rejected — note lowercase 'day' + no space after colon."""
    ok, err, flag = validate_day("00", "date")
    assert ok is False
    # EXACT PUNCTUATION: "date:day must be..." (lowercase 'd', no space)
    assert err == "date:day must be a number between 1 and 31."
    assert flag == "0"


@pytest.mark.unit
def test_validate_day_rejects_above_thirty_one() -> None:
    """Day > 31 rejected."""
    ok, err, flag = validate_day("32", "date")
    assert ok is False
    assert err == "date:day must be a number between 1 and 31."
    assert flag == "0"


@pytest.mark.unit
def test_validate_day_rejects_non_numeric() -> None:
    """Non-numeric day rejected."""
    ok, err, flag = validate_day("ab", "date")
    assert ok is False
    assert err == "date:day must be a number between 1 and 31."
    assert flag == "0"


@pytest.mark.unit
def test_validate_day_boundary_values() -> None:
    """Day 01 and 31 are boundary-valid."""
    ok1, _, _ = validate_day("01", "date")
    ok31, _, _ = validate_day("31", "date")
    assert ok1 is True
    assert ok31 is True


# ---------------------------------------------------------------------------
# Phase 5 — is_leap_year
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_is_leap_year_century_divisible_by_400() -> None:
    """2000 IS a leap year (2000 % 400 == 0)."""
    assert is_leap_year(2000) is True


@pytest.mark.unit
def test_is_leap_year_century_not_divisible_by_400() -> None:
    """1900 is NOT a leap year (1900 % 100 == 0 but not % 400)."""
    assert is_leap_year(1900) is False


@pytest.mark.unit
def test_is_leap_year_divisible_by_4_not_century() -> None:
    """2024 IS a leap year (divisible by 4, not a century)."""
    assert is_leap_year(2024) is True


@pytest.mark.unit
def test_is_leap_year_not_divisible_by_4() -> None:
    """2023 is NOT a leap year."""
    assert is_leap_year(2023) is False


@pytest.mark.unit
def test_is_leap_year_year_2004_is_leap() -> None:
    """2004 IS a leap year (straightforward %4 case)."""
    assert is_leap_year(2004) is True


@pytest.mark.unit
def test_is_leap_year_year_2100_is_not_leap() -> None:
    """2100 is NOT a leap year (century, not divisible by 400)."""
    assert is_leap_year(2100) is False


@pytest.mark.unit
def test_is_leap_year_year_1999_is_not_leap() -> None:
    """1999 is NOT a leap year."""
    assert is_leap_year(1999) is False


@pytest.mark.unit
def test_is_leap_year_year_1996_is_leap() -> None:
    """1996 IS a leap year."""
    assert is_leap_year(1996) is True


@pytest.mark.unit
def test_is_leap_year_year_zero_handled_without_raising() -> None:
    """Year 0 does not raise (handled by the Gregorian rule)."""
    # Year 0 is divisible by 400, so the rule says leap.
    assert is_leap_year(0) is True


# ---------------------------------------------------------------------------
# Phase 6 — validate_day_month_year
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_validate_day_month_year_leap_year_feb_29_valid() -> None:
    """2024-02-29 is valid (leap year)."""
    ok, err, y_flag, m_flag, d_flag = validate_day_month_year(2024, 2, 29, "date")
    assert ok is True
    assert err == ""
    # All flags empty when valid.
    assert y_flag == ""
    assert m_flag == ""
    assert d_flag == ""


@pytest.mark.unit
def test_validate_day_month_year_non_leap_feb_29_rejected() -> None:
    """2023-02-29 rejected — "Not a leap year" message with all '0' flags."""
    ok, err, y_flag, m_flag, d_flag = validate_day_month_year(2023, 2, 29, "date")
    assert ok is False
    # Exact punctuation: "Not a leap year.Cannot..." (no space after period)
    assert err == "date:Not a leap year.Cannot have 29 days in this month."
    # All three flags set to '0' because the error crosses Y/M/D.
    assert y_flag == "0"
    assert m_flag == "0"
    assert d_flag == "0"


@pytest.mark.unit
def test_validate_day_month_year_thirty_days_month_day_31_rejected() -> None:
    """April 31 rejected (April has 30 days)."""
    ok, err, y_flag, m_flag, d_flag = validate_day_month_year(2024, 4, 31, "date")
    assert ok is False
    assert err == "date:Cannot have 31 days in this month."
    # Year flag is "", month and day flags are "0".
    assert y_flag == ""
    assert m_flag == "0"
    assert d_flag == "0"


@pytest.mark.unit
def test_validate_day_month_year_thirty_one_day_month_day_31_valid() -> None:
    """January 31 is valid (Jan has 31 days)."""
    ok, err, _, _, _ = validate_day_month_year(2024, 1, 31, "date")
    assert ok is True
    assert err == ""


@pytest.mark.unit
def test_validate_day_month_year_feb_30_rejected() -> None:
    """February 30 rejected (Feb never has 30 days)."""
    ok, err, y_flag, m_flag, d_flag = validate_day_month_year(2024, 2, 30, "date")
    assert ok is False
    assert err == "date:Cannot have 30 days in this month."
    assert y_flag == ""
    assert m_flag == "0"
    assert d_flag == "0"


@pytest.mark.unit
def test_validate_day_month_year_june_30_valid() -> None:
    """June 30 is valid (June has 30 days)."""
    ok, _, _, _, _ = validate_day_month_year(2024, 6, 30, "date")
    assert ok is True


@pytest.mark.unit
def test_validate_day_month_year_all_30_day_months() -> None:
    """April, June, September, November — day 30 valid, day 31 invalid."""
    for m in [4, 6, 9, 11]:
        ok_30, _, _, _, _ = validate_day_month_year(2024, m, 30, "date")
        assert ok_30 is True, f"month {m} day 30 should be valid"
        ok_31, _, _, _, _ = validate_day_month_year(2024, m, 31, "date")
        assert ok_31 is False, f"month {m} day 31 should be invalid"


@pytest.mark.unit
def test_validate_day_month_year_all_31_day_months() -> None:
    """Jan, Mar, May, Jul, Aug, Oct, Dec — day 31 valid."""
    for m in [1, 3, 5, 7, 8, 10, 12]:
        ok, _, _, _, _ = validate_day_month_year(2024, m, 31, "date")
        assert ok is True, f"month {m} day 31 should be valid"


# ---------------------------------------------------------------------------
# Phase 7 — validate_date_ccyymmdd (end-to-end)
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_validate_date_ccyymmdd_valid_leap_date() -> None:
    """2024-02-29 is valid and result_text is 'Date is valid'."""
    r = validate_date_ccyymmdd("20240229", "DOB")
    assert r.is_valid is True
    assert r.error_message == ""
    # Success result_text equals CEEDAYS FC-INVALID-DATE text.
    assert r.result_text == "Date is valid"
    assert r.test_date == "20240229"


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_non_leap_feb_29() -> None:
    """Not-a-leap-year Feb 29 is rejected with correct error message."""
    r = validate_date_ccyymmdd("20230229", "DOB")
    assert r.is_valid is False
    assert r.error_message == ("DOB:Not a leap year.Cannot have 29 days in this month.")
    # result_text is set to 'Datevalue error' when the calendar check
    # fails (matches CEEDAYS FC-BAD-DATE-VALUE).
    assert r.result_text == "Datevalue error"


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_bad_month() -> None:
    """Month 13 rejected with component error message."""
    r = validate_date_ccyymmdd("20241301", "DOB")
    assert r.is_valid is False
    assert r.error_message == ("DOB: Month must be a number between 1 and 12.")


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_empty_input() -> None:
    """Empty string returns blank-year error (first validator in cascade)."""
    r = validate_date_ccyymmdd("", "DOB")
    assert r.is_valid is False
    assert r.error_message == "DOB : Year must be supplied."
    # Year flag should be 'B' (blank) since we hit the first guard.
    assert r.year_flag == "B"


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_all_alpha() -> None:
    """Alphabetic input fails year validation first."""
    r = validate_date_ccyymmdd("abcdefgh", "DOB")
    assert r.is_valid is False
    assert r.error_message == "DOB must be 4 digit number."


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_day_out_of_range_for_month() -> None:
    """Feb 31 rejected via datetime round-trip check."""
    r = validate_date_ccyymmdd("20240231", "DOB")
    assert r.is_valid is False
    # The datetime() construction catches this as 'Date is invalid'
    # with severity 12 — exact message includes the sev/msg breakdown.
    assert "day is out of range for month" in r.error_message
    assert "Sev code" in r.error_message


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_bad_day_characters() -> None:
    """Non-numeric character in day field fails day-range check."""
    r = validate_date_ccyymmdd("2024022W", "DOB")
    assert r.is_valid is False
    assert r.error_message == "DOB:day must be a number between 1 and 31."


@pytest.mark.unit
def test_validate_date_ccyymmdd_rejects_surrounding_whitespace() -> None:
    """Leading/trailing whitespace is NOT stripped — treats as year error.

    The validator splits positions 0-3 for year; a leading space is
    picked up as non-numeric year character.
    """
    r = validate_date_ccyymmdd(" 20240229 ", "DOB")
    assert r.is_valid is False
    assert r.error_message == "DOB must be 4 digit number."


@pytest.mark.unit
def test_validate_date_ccyymmdd_populates_test_date_field() -> None:
    """The ``test_date`` attribute mirrors the normalised input (up to 8)."""
    r = validate_date_ccyymmdd("20240229", "DOB")
    assert r.test_date == "20240229"


# ---------------------------------------------------------------------------
# Phase 8 — validate_date_of_birth
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_validate_date_of_birth_past_date_accepted() -> None:
    """A past date (1985-06-15) is a valid DOB."""
    r = validate_date_of_birth("19850615", "DOB")
    assert r.is_valid is True
    assert r.error_message == ""


@pytest.mark.unit
def test_validate_date_of_birth_today_is_rejected_with_trailing_space() -> None:
    """DOB equal to today is REJECTED — parity with COBOL guard.

    NOTE: The error message has a TRAILING SPACE after 'future'
    because the COBOL literal contains that space. This is a
    behavioural contract — do not strip.
    """
    today = date.today()
    today_str = f"{today.year:04d}{today.month:02d}{today.day:02d}"
    r = validate_date_of_birth(today_str, "DOB")
    assert r.is_valid is False
    # Exact punctuation with trailing space after 'future'.
    assert r.error_message == "DOB:cannot be in the future "


@pytest.mark.unit
def test_validate_date_of_birth_tomorrow_rejected_with_all_zero_flags() -> None:
    """Future DOB has ALL three flags set to '0' (structural error spanning Y/M/D)."""
    tomorrow = date.today() + timedelta(days=1)
    tomorrow_str = f"{tomorrow.year:04d}{tomorrow.month:02d}{tomorrow.day:02d}"
    r = validate_date_of_birth(tomorrow_str, "DOB")
    assert r.is_valid is False
    assert r.error_message == "DOB:cannot be in the future "
    assert r.year_flag == "0"
    assert r.month_flag == "0"
    assert r.day_flag == "0"


@pytest.mark.unit
def test_validate_date_of_birth_invalid_date_propagates_error() -> None:
    """Invalid calendar DOB propagates through without hitting future-check."""
    r = validate_date_of_birth("20230229", "DOB")
    assert r.is_valid is False
    # Should be the leap-year error, NOT the future error.
    assert "Not a leap year" in r.error_message


@pytest.mark.unit
def test_validate_date_of_birth_far_past_still_valid() -> None:
    """Very old DOB (1920) still accepted (centuries 19 and 20 both OK)."""
    r = validate_date_of_birth("19200115", "DOB")
    assert r.is_valid is True
    assert r.error_message == ""


# ---------------------------------------------------------------------------
# Phase 9 — format_date_mm_dd_yy
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_format_date_mm_dd_yy_basic() -> None:
    """2024-03-15 → '03/15/24' (length exactly 8)."""
    result = format_date_mm_dd_yy(2024, 3, 15)
    assert result == "03/15/24"
    assert len(result) == 8


@pytest.mark.unit
def test_format_date_mm_dd_yy_year_boundary_2000() -> None:
    """2000-01-01 → '01/01/00' (century boundary — '00' not '100')."""
    result = format_date_mm_dd_yy(2000, 1, 1)
    assert result == "01/01/00"
    assert len(result) == 8


@pytest.mark.unit
def test_format_date_mm_dd_yy_year_boundary_1999() -> None:
    """1999-12-31 → '12/31/99'."""
    result = format_date_mm_dd_yy(1999, 12, 31)
    assert result == "12/31/99"
    assert len(result) == 8


@pytest.mark.unit
def test_format_date_mm_dd_yy_single_digit_components_padded() -> None:
    """Single-digit month/day are zero-padded."""
    result = format_date_mm_dd_yy(2024, 1, 5)
    assert result == "01/05/24"


@pytest.mark.unit
def test_format_date_mm_dd_yy_always_returns_8_chars() -> None:
    """Length is always exactly 8 characters regardless of inputs."""
    cases = [
        (2024, 1, 1),
        (2024, 12, 31),
        (1999, 6, 15),
        (2000, 2, 29),
    ]
    for y, m, d in cases:
        assert len(format_date_mm_dd_yy(y, m, d)) == 8


# ---------------------------------------------------------------------------
# Phase 10 — format_time_hh_mm_ss
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_format_time_hh_mm_ss_basic() -> None:
    """9:05:03 → '09:05:03' (all components zero-padded)."""
    result = format_time_hh_mm_ss(9, 5, 3)
    assert result == "09:05:03"
    assert len(result) == 8


@pytest.mark.unit
def test_format_time_hh_mm_ss_end_of_day() -> None:
    """23:59:59 → '23:59:59'."""
    assert format_time_hh_mm_ss(23, 59, 59) == "23:59:59"


@pytest.mark.unit
def test_format_time_hh_mm_ss_midnight() -> None:
    """0:0:0 → '00:00:00'."""
    assert format_time_hh_mm_ss(0, 0, 0) == "00:00:00"


@pytest.mark.unit
def test_format_time_hh_mm_ss_always_returns_8_chars() -> None:
    """Length is always exactly 8 characters."""
    for h, m, s in [(0, 0, 0), (1, 1, 1), (12, 12, 12), (23, 59, 59)]:
        assert len(format_time_hh_mm_ss(h, m, s)) == 8


# ---------------------------------------------------------------------------
# Phase 11 — format_timestamp
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_format_timestamp_naive_datetime() -> None:
    """Naive datetime formatted as 'YYYY-MM-DD HH:MM:SS.ffffff' (26 chars)."""
    dt = datetime(2024, 3, 15, 14, 30, 0, 123456)
    result = format_timestamp(dt)
    assert result == "2024-03-15 14:30:00.123456"
    assert len(result) == 26


@pytest.mark.unit
def test_format_timestamp_always_26_chars() -> None:
    """Length is always exactly 26 characters."""
    cases = [
        datetime(2024, 3, 15, 14, 30, 0, 123456),
        datetime(2024, 1, 1, 0, 0, 0, 0),
        datetime(2099, 12, 31, 23, 59, 59, 999999),
    ]
    for dt in cases:
        assert len(format_timestamp(dt)) == 26


@pytest.mark.unit
def test_format_timestamp_tz_aware_datetime_ignores_tz() -> None:
    """Timezone info is discarded — formatted as local components."""
    dt_aware = datetime(2024, 3, 15, 14, 30, 0, 123456, tzinfo=UTC)
    result = format_timestamp(dt_aware)
    # NO timezone suffix — the naive/tz-aware outputs are identical.
    assert result == "2024-03-15 14:30:00.123456"


@pytest.mark.unit
def test_format_timestamp_zero_microseconds_padded() -> None:
    """Zero microseconds → '.000000' suffix."""
    dt = datetime(2024, 1, 1, 0, 0, 0)
    assert format_timestamp(dt) == "2024-01-01 00:00:00.000000"


@pytest.mark.unit
def test_format_timestamp_end_of_year() -> None:
    """End-of-year datetime formatted correctly."""
    dt = datetime(2099, 12, 31, 23, 59, 59, 999999)
    assert format_timestamp(dt) == "2099-12-31 23:59:59.999999"


# ---------------------------------------------------------------------------
# Phase 12 — get_current_date_formatted
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_get_current_date_formatted_returns_all_four_keys() -> None:
    """Return dict contains exactly the four expected keys."""
    result = get_current_date_formatted()
    assert set(result.keys()) == {"ccyymmdd", "mm_dd_yy", "hh_mm_ss", "timestamp"}


@pytest.mark.unit
def test_get_current_date_formatted_ccyymmdd_length_8() -> None:
    """``ccyymmdd`` key is 8 chars (CCYYMMDD format)."""
    result = get_current_date_formatted()
    assert len(result["ccyymmdd"]) == 8
    # First 4 chars are a year — sanity check it's reasonable.
    year = int(result["ccyymmdd"][:4])
    assert 2020 <= year <= 2100


@pytest.mark.unit
def test_get_current_date_formatted_mm_dd_yy_length_8() -> None:
    """``mm_dd_yy`` key is 8 chars (MM/DD/YY format with slashes)."""
    result = get_current_date_formatted()
    assert len(result["mm_dd_yy"]) == 8
    # Structural check: slash at positions 2 and 5.
    assert result["mm_dd_yy"][2] == "/"
    assert result["mm_dd_yy"][5] == "/"


@pytest.mark.unit
def test_get_current_date_formatted_hh_mm_ss_length_8() -> None:
    """``hh_mm_ss`` key is 8 chars (HH:MM:SS format with colons)."""
    result = get_current_date_formatted()
    assert len(result["hh_mm_ss"]) == 8
    assert result["hh_mm_ss"][2] == ":"
    assert result["hh_mm_ss"][5] == ":"


@pytest.mark.unit
def test_get_current_date_formatted_timestamp_length_26() -> None:
    """``timestamp`` key is 26 chars (YYYY-MM-DD HH:MM:SS.ffffff)."""
    result = get_current_date_formatted()
    assert len(result["timestamp"]) == 26


# ---------------------------------------------------------------------------
# Phase 13 — format_validation_message / format_ceedays_result
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_format_validation_message_success_layout() -> None:
    """Valid date produces 80-char layout starting with '0000'."""
    result = format_validation_message(
        severity=0,
        message_code=0,
        result_text="Date is valid",
        test_date="20240229",
        mask_used="YYYYMMDD",
    )
    # 80 char total length (COBOL output layout).
    assert len(result) == 80
    # Begins with zero-padded severity.
    assert result.startswith("0000")


@pytest.mark.unit
def test_format_validation_message_error_layout() -> None:
    """Error date produces 80-char layout with severity 12."""
    result = format_validation_message(
        severity=12,
        message_code=2518,
        result_text="Datevalue error",
        test_date="20230229",
        mask_used="YYYYMMDD",
    )
    assert len(result) == 80
    # Begins with zero-padded severity '0012'.
    assert result.startswith("0012")
    # Contains 'Mesg Code: 2518' token.
    assert "Mesg Code: 2518" in result


@pytest.mark.unit
def test_format_validation_message_contains_test_date() -> None:
    """The test_date field is embedded in the layout."""
    result = format_validation_message(
        severity=0,
        message_code=0,
        result_text="Date is valid",
        test_date="20240229",
        mask_used="YYYYMMDD",
    )
    assert "20240229" in result


@pytest.mark.unit
def test_format_validation_message_contains_mask_used() -> None:
    """The mask_used field is embedded in the layout."""
    result = format_validation_message(
        severity=0,
        message_code=0,
        result_text="Date is valid",
        test_date="20240229",
        mask_used="YYYYMMDD",
    )
    assert "YYYYMMDD" in result


@pytest.mark.unit
def test_format_ceedays_result_is_alias_for_format_validation_message() -> None:
    """``format_ceedays_result`` is an alias — output must match."""
    a = format_validation_message(
        severity=0,
        message_code=0,
        result_text="Date is valid",
        test_date="20240229",
        mask_used="YYYYMMDD",
    )
    b = format_ceedays_result(
        severity=0,
        msg_no=0,
        result_text="Date is valid",
        test_date="20240229",
        mask="YYYYMMDD",
    )
    assert a == b


@pytest.mark.unit
def test_format_validation_message_always_80_chars() -> None:
    """Output is always exactly 80 characters regardless of inputs."""
    cases = [
        (0, 0, "", "", ""),
        (12, 99, "Bad Pic String", "abcd", "YYYYMMDD"),
        (16, 2520, "Invalid month", "20241301", "YYYYMMDD"),
    ]
    for sev, code, rt, td, mk in cases:
        out = format_validation_message(sev, code, rt, td, mk)
        assert len(out) == 80


# ---------------------------------------------------------------------------
# Phase 14 — __all__ public-API surface guard
# ---------------------------------------------------------------------------
@pytest.mark.unit
def test_all_public_api_matches_expected_surface() -> None:
    """``__all__`` must list exactly the 14 documented exports."""
    from src.shared.utils import date_utils

    expected: set[str] = {
        "DateValidationResult",
        "format_validation_message",
        "format_ceedays_result",
        "validate_year",
        "validate_month",
        "validate_day",
        "is_leap_year",
        "validate_day_month_year",
        "validate_date_ccyymmdd",
        "validate_date_of_birth",
        "format_date_mm_dd_yy",
        "format_time_hh_mm_ss",
        "format_timestamp",
        "get_current_date_formatted",
    }
    assert hasattr(date_utils, "__all__")
    actual: set[str] = set(date_utils.__all__)
    assert actual == expected, (
        f"date_utils.__all__ drift detected. Missing: {expected - actual}  Extra: {actual - expected}"
    )
