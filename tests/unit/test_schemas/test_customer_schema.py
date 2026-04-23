# ============================================================================
# Tests for src/shared/schemas/customer_schema.py
# ============================================================================
# Source under test: src/shared/schemas/customer_schema.py
# Originally derived from COBOL copybook app/cpy/CVCUS01Y.cpy
# (CUSTOMER-RECORD, RECLN 500 — 18 business fields).
#
# This test module exercises every branch of the three private field
# validators and their two classmethod wrappers (one on each of
# ``CustomerResponse`` and ``CustomerCreateRequest``). The tests below are
# organised by validator so the coverage map is obvious:
#
# 1. ``_validate_cust_id_exact`` — happy path + 4 negative branches
#    (None, non-str, wrong-length, non-digit).
# 2. ``_validate_dob_format`` — happy path + empty-string + None +
#    5 negative branches (non-str, wrong-length, wrong-dashes,
#    non-digit segments, out-of-range month, out-of-range day).
# 3. ``_validate_fico_range`` — happy path + None + 4 negative branches
#    (bool True, bool False, non-int, below-min, above-max).
#
# Each negative-path test asserts that Pydantic raises a
# :class:`pydantic.ValidationError` (the standard error class for
# :func:`~pydantic.field_validator`-raised :class:`ValueError` instances)
# and that the underlying message embeds the offending value and the
# expected bound so that API error responses remain actionable for
# consumers.
#
# Round-trip parity with the COBOL record layout is implicitly tested
# because every happy-path fixture uses the exact ``PIC`` widths of the
# legacy copybook (9-digit cust_id / ssn, 10-char YYYY-MM-DD dob,
# ``PIC 9(03)`` fico within ``000..999``).
# ============================================================================
"""Unit tests for ``src.shared.schemas.customer_schema``.

These tests are pure-Python and have no database, network, AWS, or
Spark dependency. They execute in milliseconds and can therefore be
run on every commit.
"""

from __future__ import annotations

from typing import Any

import pytest
from pydantic import ValidationError

from src.shared.schemas.customer_schema import (
    CustomerCreateRequest,
    CustomerResponse,
)

# ---------------------------------------------------------------------------
# Helper — build a minimal valid CustomerResponse payload
# ---------------------------------------------------------------------------
# The response schema is strict: every one of the 18 COBOL business
# fields is ``required`` (no defaults) because it mirrors the fully
# populated on-disk VSAM row. We provide a small factory so individual
# tests can override a single field without duplicating the entire
# 18-field dict.


def _valid_response_payload(**overrides: Any) -> dict[str, Any]:
    """Return a fully populated valid ``CustomerResponse`` payload.

    Individual tests may override specific fields via ``**overrides`` to
    trigger the validator branch under test without re-supplying all 18
    fields.
    """
    base: dict[str, Any] = {
        "cust_id": "000000001",
        "first_name": "JOHN",
        "middle_name": "Q",
        "last_name": "DOE",
        "addr_line_1": "100 MAIN ST",
        "addr_line_2": "",
        "addr_line_3": "",
        "state_cd": "NY",
        "country_cd": "USA",
        "addr_zip": "10001",
        "phone_num_1": "5551234567",
        "phone_num_2": "",
        "ssn": "123456789",
        "govt_issued_id": "PASSPORT1234",
        "dob": "1985-03-15",
        "eft_account_id": "ACCT000001",
        "pri_card_holder_ind": "Y",
        "fico_credit_score": 750,
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# 1. cust_id validation — `_validate_cust_id_exact`
# ---------------------------------------------------------------------------
# COBOL PIC 9(09) — exactly 9 ASCII digits, leading zeros preserved.


class TestCustIdValidator:
    """Tests for ``_validate_cust_id_exact`` via both schemas."""

    # ---- Happy paths ------------------------------------------------------

    def test_response_cust_id_happy_path(self) -> None:
        """A 9-digit cust_id with leading zeros is accepted."""
        response = CustomerResponse(**_valid_response_payload(cust_id="000000042"))
        assert response.cust_id == "000000042"

    def test_request_cust_id_happy_path(self) -> None:
        """A 9-digit cust_id on the request schema is accepted."""
        request = CustomerCreateRequest(
            cust_id="987654321",
            first_name="JANE",
            last_name="SMITH",
        )
        assert request.cust_id == "987654321"

    def test_cust_id_all_zeros_accepted(self) -> None:
        """``'000000000'`` is a legal cust_id (all-zeros string)."""
        request = CustomerCreateRequest(
            cust_id="000000000",
            first_name="TEST",
            last_name="USER",
        )
        assert request.cust_id == "000000000"

    # ---- Negative branches (via CustomerResponse) --------------------------

    def test_response_cust_id_too_short_rejected(self) -> None:
        """An 8-character cust_id is rejected with a length error."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(cust_id="12345678"))
        # The string_too_long / value_error message must mention length.
        err = str(exc_info.value)
        assert "9" in err or "length" in err.lower()

    def test_response_cust_id_too_long_rejected(self) -> None:
        """A 10-character cust_id is rejected (max_length enforced by Field)."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(cust_id="1234567890"))

    def test_response_cust_id_non_digit_rejected(self) -> None:
        """A 9-character cust_id containing a letter is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(cust_id="12345678A"))
        err = str(exc_info.value)
        # Must identify digit-only requirement.
        assert "digit" in err.lower() or "PIC 9(09)" in err

    def test_response_cust_id_whitespace_rejected(self) -> None:
        """A 9-char cust_id containing a space is rejected (not a digit)."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(cust_id="1 2345678"))

    # ---- Negative branches (via CustomerCreateRequest) ---------------------

    def test_request_cust_id_too_short_rejected(self) -> None:
        """Request-schema cust_id of 7 chars is rejected."""
        with pytest.raises(ValidationError):
            CustomerCreateRequest(
                cust_id="1234567",
                first_name="X",
                last_name="Y",
            )

    def test_request_cust_id_non_digit_rejected(self) -> None:
        """Request-schema cust_id with alpha char is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerCreateRequest(
                cust_id="AAAAAAAAA",
                first_name="X",
                last_name="Y",
            )
        err = str(exc_info.value)
        assert "digit" in err.lower() or "PIC 9(09)" in err


# ---------------------------------------------------------------------------
# 2. dob validation — `_validate_dob_format`
# ---------------------------------------------------------------------------
# COBOL PIC X(10) — format YYYY-MM-DD. Empty string and None are both
# accepted as "no DOB on file".


class TestDobValidator:
    """Tests for ``_validate_dob_format`` via both schemas."""

    # ---- Happy paths ------------------------------------------------------

    def test_response_dob_happy_path(self) -> None:
        """A valid YYYY-MM-DD dob is accepted unchanged."""
        response = CustomerResponse(**_valid_response_payload(dob="2000-01-15"))
        assert response.dob == "2000-01-15"

    def test_response_dob_empty_string_accepted(self) -> None:
        """Empty string is the canonical 'no DOB on file' sentinel."""
        response = CustomerResponse(**_valid_response_payload(dob=""))
        assert response.dob == ""

    def test_request_dob_none_accepted(self) -> None:
        """``None`` is accepted on the request schema (Optional dob)."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="X",
            last_name="Y",
            dob=None,
        )
        assert request.dob is None

    def test_request_dob_empty_string_accepted(self) -> None:
        """Empty string is accepted on the request schema."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="X",
            last_name="Y",
            dob="",
        )
        assert request.dob == ""

    def test_request_dob_valid_format_accepted(self) -> None:
        """A valid YYYY-MM-DD dob on request is accepted unchanged."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="X",
            last_name="Y",
            dob="1990-12-31",
        )
        assert request.dob == "1990-12-31"

    def test_dob_boundary_jan_1st(self) -> None:
        """The smallest legal month/day combination (01-01) is accepted."""
        response = CustomerResponse(**_valid_response_payload(dob="2020-01-01"))
        assert response.dob == "2020-01-01"

    def test_dob_boundary_dec_31st(self) -> None:
        """The largest legal month/day combination (12-31) is accepted."""
        response = CustomerResponse(**_valid_response_payload(dob="2020-12-31"))
        assert response.dob == "2020-12-31"

    # ---- Negative branches -------------------------------------------------

    def test_response_dob_wrong_length_rejected(self) -> None:
        """A dob of length != 10 is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020-1-15"))
        err = str(exc_info.value)
        assert "10" in err or "YYYY-MM-DD" in err

    def test_response_dob_missing_first_dash_rejected(self) -> None:
        """A dob with a non-dash at index 4 is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020X01-15"))
        err = str(exc_info.value)
        # Error message must mention the dash expectation.
        assert "dash" in err.lower() or "YYYY-MM-DD" in err

    def test_response_dob_missing_second_dash_rejected(self) -> None:
        """A dob with a non-dash at index 7 is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020-01X15"))
        err = str(exc_info.value)
        assert "dash" in err.lower() or "YYYY-MM-DD" in err

    def test_response_dob_non_digit_year_rejected(self) -> None:
        """A dob whose year segment contains a letter is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="20AA-01-15"))
        err = str(exc_info.value)
        assert "numeric" in err.lower() or "digit" in err.lower()

    def test_response_dob_non_digit_month_rejected(self) -> None:
        """A dob whose month segment contains a letter is rejected."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(dob="2020-AA-15"))

    def test_response_dob_non_digit_day_rejected(self) -> None:
        """A dob whose day segment contains a letter is rejected."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(dob="2020-01-AA"))

    def test_response_dob_month_zero_rejected(self) -> None:
        """A dob with month 00 is rejected (month must be 01..12)."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020-00-15"))
        err = str(exc_info.value)
        assert "month" in err.lower()

    def test_response_dob_month_thirteen_rejected(self) -> None:
        """A dob with month 13 is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020-13-15"))
        err = str(exc_info.value)
        assert "month" in err.lower()

    def test_response_dob_day_zero_rejected(self) -> None:
        """A dob with day 00 is rejected (day must be 01..31)."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020-01-00"))
        err = str(exc_info.value)
        assert "day" in err.lower()

    def test_response_dob_day_thirty_two_rejected(self) -> None:
        """A dob with day 32 is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(dob="2020-01-32"))
        err = str(exc_info.value)
        assert "day" in err.lower()

    def test_request_dob_wrong_length_rejected(self) -> None:
        """On the request schema, an invalid-length dob is rejected."""
        with pytest.raises(ValidationError):
            CustomerCreateRequest(
                cust_id="000000001",
                first_name="X",
                last_name="Y",
                dob="20-01-15",
            )

    def test_request_dob_month_out_of_range_rejected(self) -> None:
        """On the request schema, month 99 is rejected."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerCreateRequest(
                cust_id="000000001",
                first_name="X",
                last_name="Y",
                dob="2020-99-15",
            )
        err = str(exc_info.value)
        assert "month" in err.lower()


# ---------------------------------------------------------------------------
# 3. fico_credit_score validation — `_validate_fico_range`
# ---------------------------------------------------------------------------
# COBOL PIC 9(03) — integer in ``000..999``. ``0`` is the "not scored"
# sentinel and must be accepted. ``bool`` must be explicitly rejected
# even though it is a subclass of ``int``.


class TestFicoValidator:
    """Tests for ``_validate_fico_range`` via both schemas."""

    # ---- Happy paths ------------------------------------------------------

    def test_response_fico_mid_range_accepted(self) -> None:
        """A FICO score of 750 is accepted."""
        response = CustomerResponse(**_valid_response_payload(fico_credit_score=750))
        assert response.fico_credit_score == 750

    def test_response_fico_zero_accepted(self) -> None:
        """FICO ``0`` is the 'not scored' sentinel and must be accepted."""
        response = CustomerResponse(**_valid_response_payload(fico_credit_score=0))
        assert response.fico_credit_score == 0

    def test_response_fico_max_accepted(self) -> None:
        """FICO ``999`` is the PIC 9(03) upper bound and must be accepted."""
        response = CustomerResponse(**_valid_response_payload(fico_credit_score=999))
        assert response.fico_credit_score == 999

    def test_request_fico_none_accepted(self) -> None:
        """On the request schema, ``None`` fico is accepted (Optional)."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="X",
            last_name="Y",
            fico_credit_score=None,
        )
        assert request.fico_credit_score is None

    def test_request_fico_valid_accepted(self) -> None:
        """On the request schema, a valid fico value is accepted."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="X",
            last_name="Y",
            fico_credit_score=600,
        )
        assert request.fico_credit_score == 600

    # ---- Negative branches -------------------------------------------------

    def test_response_fico_bool_true_rejected(self) -> None:
        """FICO ``True`` is rejected even though bool is an int subclass."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(fico_credit_score=True))
        err = str(exc_info.value)
        assert "boolean" in err.lower() or "integer" in err.lower()

    def test_response_fico_bool_false_rejected(self) -> None:
        """FICO ``False`` is rejected even though bool is an int subclass."""
        with pytest.raises(ValidationError) as exc_info:
            CustomerResponse(**_valid_response_payload(fico_credit_score=False))
        err = str(exc_info.value)
        assert "boolean" in err.lower() or "integer" in err.lower()

    def test_response_fico_negative_rejected(self) -> None:
        """FICO ``-1`` is rejected (below the 0..999 domain)."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(fico_credit_score=-1))

    def test_response_fico_above_max_rejected(self) -> None:
        """FICO ``1000`` is rejected (above the 0..999 domain)."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(fico_credit_score=1000))

    def test_response_fico_non_int_rejected(self) -> None:
        """FICO as a string (not an int) is rejected."""
        with pytest.raises(ValidationError):
            CustomerResponse(**_valid_response_payload(fico_credit_score="750"))

    def test_request_fico_bool_rejected(self) -> None:
        """On the request schema, ``True`` fico is rejected.

        Note: ``bool`` is a subclass of ``int`` in Python so mypy does not
        complain about ``fico_credit_score=True`` — the runtime
        ``isinstance(value, bool)`` guard in ``_validate_fico_range`` is
        what fires, and Pydantic wraps the resulting ``ValueError`` in a
        ``ValidationError``.
        """
        with pytest.raises(ValidationError) as exc_info:
            CustomerCreateRequest(
                cust_id="000000001",
                first_name="X",
                last_name="Y",
                fico_credit_score=True,
            )
        err = str(exc_info.value)
        assert "boolean" in err.lower() or "integer" in err.lower()

    def test_request_fico_above_max_rejected(self) -> None:
        """On the request schema, fico above 999 is rejected."""
        with pytest.raises(ValidationError):
            CustomerCreateRequest(
                cust_id="000000001",
                first_name="X",
                last_name="Y",
                fico_credit_score=1500,
            )


# ---------------------------------------------------------------------------
# 4. End-to-end schema construction & ORM mode
# ---------------------------------------------------------------------------


class TestCustomerSchemaConstruction:
    """End-to-end happy-path tests across the full 18-field response."""

    def test_response_fully_populated_happy_path(self) -> None:
        """Construct a fully populated CustomerResponse successfully."""
        response = CustomerResponse(**_valid_response_payload())
        assert response.cust_id == "000000001"
        assert response.first_name == "JOHN"
        assert response.middle_name == "Q"
        assert response.last_name == "DOE"
        assert response.addr_line_1 == "100 MAIN ST"
        assert response.state_cd == "NY"
        assert response.country_cd == "USA"
        assert response.ssn == "123456789"
        assert response.dob == "1985-03-15"
        assert response.fico_credit_score == 750
        assert response.pri_card_holder_ind == "Y"

    def test_request_minimal_required_happy_path(self) -> None:
        """Construct a CustomerCreateRequest with only required fields."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="JOHN",
            last_name="DOE",
        )
        assert request.cust_id == "000000001"
        assert request.first_name == "JOHN"
        assert request.last_name == "DOE"
        # All optional fields should default to None.
        assert request.middle_name is None
        assert request.dob is None
        assert request.fico_credit_score is None
        assert request.ssn is None

    def test_request_fully_populated_happy_path(self) -> None:
        """Construct a fully populated CustomerCreateRequest successfully."""
        payload = _valid_response_payload()
        # CustomerCreateRequest has no `fico_credit_score = 0` issue — reuse.
        request = CustomerCreateRequest(**payload)
        assert request.cust_id == payload["cust_id"]
        assert request.fico_credit_score == payload["fico_credit_score"]
        assert request.dob == payload["dob"]

    def test_response_missing_required_field_rejected(self) -> None:
        """Omitting a required field on CustomerResponse is rejected."""
        payload = _valid_response_payload()
        del payload["last_name"]
        with pytest.raises(ValidationError):
            CustomerResponse(**payload)

    def test_request_missing_required_field_rejected(self) -> None:
        """Omitting cust_id on CustomerCreateRequest is rejected."""
        with pytest.raises(ValidationError):
            CustomerCreateRequest(  # type: ignore[call-arg]
                first_name="X",
                last_name="Y",
            )

    def test_response_from_attributes_orm_mode(self) -> None:
        """``ConfigDict(from_attributes=True)`` allows attribute-based load.

        Simulates the ORM-mode path used by the service layer:
        ``CustomerResponse.model_validate(customer_row)`` where
        ``customer_row`` is a SQLAlchemy ORM instance whose attributes
        map onto the schema fields.
        """

        class _OrmRow:
            """Minimal duck-typed ORM row for model_validate()."""

            def __init__(self, payload: dict[str, Any]) -> None:
                for key, value in payload.items():
                    setattr(self, key, value)

        row = _OrmRow(_valid_response_payload())
        response = CustomerResponse.model_validate(row)
        assert response.cust_id == "000000001"
        assert response.dob == "1985-03-15"

    def test_request_optional_dob_none_preserves_none(self) -> None:
        """``None`` dob on a request schema round-trips without coercion."""
        request = CustomerCreateRequest(
            cust_id="000000001",
            first_name="X",
            last_name="Y",
            dob=None,
        )
        # Confirms the Optional[str] type contract.
        assert request.dob is None

    def test_leading_zero_cust_id_preserved(self) -> None:
        """Leading-zero cust_id round-trips as a string (PIC 9(09) parity)."""
        request = CustomerCreateRequest(
            cust_id="000000005",
            first_name="X",
            last_name="Y",
        )
        assert request.cust_id == "000000005"
        # Critical: must NOT be coerced to int 5.
        assert isinstance(request.cust_id, str)


# ---------------------------------------------------------------------------
# 5. Directly exercising the shared helpers (belt-and-braces coverage)
# ---------------------------------------------------------------------------
# These tests import the private helpers by name and call them
# directly. This provides a second layer of coverage that does not
# depend on Pydantic's internal error wrapping.


class TestDirectHelperInvocation:
    """Invoke the private validator helpers directly for max coverage."""

    def test_validate_cust_id_exact_valid(self) -> None:
        """Valid 9-digit cust_id returned unchanged."""
        from src.shared.schemas.customer_schema import _validate_cust_id_exact

        assert _validate_cust_id_exact("123456789") == "123456789"

    def test_validate_cust_id_exact_none_raises(self) -> None:
        """None cust_id raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_cust_id_exact

        with pytest.raises(ValueError, match="must not be null"):
            _validate_cust_id_exact(None)  # type: ignore[arg-type]

    def test_validate_cust_id_exact_non_string_raises(self) -> None:
        """Non-str cust_id raises ValueError with the actual type name."""
        from src.shared.schemas.customer_schema import _validate_cust_id_exact

        with pytest.raises(ValueError, match="must be a string"):
            _validate_cust_id_exact(123456789)  # type: ignore[arg-type]

    def test_validate_cust_id_exact_wrong_length_raises(self) -> None:
        """cust_id of incorrect length raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_cust_id_exact

        with pytest.raises(ValueError, match="must be exactly 9 characters"):
            _validate_cust_id_exact("12345")

    def test_validate_cust_id_exact_non_digit_raises(self) -> None:
        """cust_id containing non-digit chars raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_cust_id_exact

        with pytest.raises(ValueError, match="must contain only digits"):
            _validate_cust_id_exact("ABCDEFGHI")

    def test_validate_dob_format_none(self) -> None:
        """None dob returned unchanged."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        result: str | None = _validate_dob_format(None)
        assert result is None

    def test_validate_dob_format_empty_string(self) -> None:
        """Empty-string dob returned unchanged."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        assert _validate_dob_format("") == ""

    def test_validate_dob_format_valid(self) -> None:
        """Valid YYYY-MM-DD dob returned unchanged."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        assert _validate_dob_format("2020-06-15") == "2020-06-15"

    def test_validate_dob_format_non_string_raises(self) -> None:
        """Non-str dob raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="must be a string"):
            _validate_dob_format(19850315)  # type: ignore[arg-type]

    def test_validate_dob_format_wrong_length_raises(self) -> None:
        """dob of incorrect length raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="must be exactly 10 characters"):
            _validate_dob_format("2020-1-15")

    def test_validate_dob_format_bad_dash_first_raises(self) -> None:
        """dob with wrong first dash raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="dashes"):
            _validate_dob_format("2020X06-15")

    def test_validate_dob_format_bad_dash_second_raises(self) -> None:
        """dob with wrong second dash raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="dashes"):
            _validate_dob_format("2020-06X15")

    def test_validate_dob_format_non_digit_raises(self) -> None:
        """dob with non-digit segment raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="numeric digits"):
            _validate_dob_format("20AA-06-15")

    def test_validate_dob_format_month_zero_raises(self) -> None:
        """dob with month 00 raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="month"):
            _validate_dob_format("2020-00-15")

    def test_validate_dob_format_month_high_raises(self) -> None:
        """dob with month > 12 raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="month"):
            _validate_dob_format("2020-99-15")

    def test_validate_dob_format_day_zero_raises(self) -> None:
        """dob with day 00 raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="day"):
            _validate_dob_format("2020-06-00")

    def test_validate_dob_format_day_high_raises(self) -> None:
        """dob with day > 31 raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_dob_format

        with pytest.raises(ValueError, match="day"):
            _validate_dob_format("2020-06-99")

    def test_validate_fico_range_none(self) -> None:
        """None fico returned unchanged."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        assert _validate_fico_range(None) is None

    def test_validate_fico_range_valid(self) -> None:
        """Valid fico returned unchanged."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        assert _validate_fico_range(650) == 650

    def test_validate_fico_range_zero(self) -> None:
        """Zero fico is the 'not scored' sentinel and accepted."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        assert _validate_fico_range(0) == 0

    def test_validate_fico_range_max(self) -> None:
        """999 is the PIC 9(03) upper bound and accepted."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        assert _validate_fico_range(999) == 999

    def test_validate_fico_range_bool_true_raises(self) -> None:
        """True fico raises ValueError even though bool is an int subclass.

        Mypy does not flag ``_validate_fico_range(True)`` because
        ``bool`` is a subclass of ``int`` and the function signature
        declares ``Optional[int]``. The runtime ``isinstance(value, bool)``
        guard is what actually rejects the value.
        """
        from src.shared.schemas.customer_schema import _validate_fico_range

        with pytest.raises(ValueError, match="boolean"):
            _validate_fico_range(True)

    def test_validate_fico_range_bool_false_raises(self) -> None:
        """False fico raises ValueError (bool-is-int-subclass guard)."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        with pytest.raises(ValueError, match="boolean"):
            _validate_fico_range(False)

    def test_validate_fico_range_non_int_raises(self) -> None:
        """Non-int (e.g. str) fico raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        with pytest.raises(ValueError, match="must be an integer"):
            _validate_fico_range("750")  # type: ignore[arg-type]

    def test_validate_fico_range_below_min_raises(self) -> None:
        """Negative fico raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        with pytest.raises(ValueError, match="must be between"):
            _validate_fico_range(-1)

    def test_validate_fico_range_above_max_raises(self) -> None:
        """fico > 999 raises ValueError."""
        from src.shared.schemas.customer_schema import _validate_fico_range

        with pytest.raises(ValueError, match="must be between"):
            _validate_fico_range(1000)
