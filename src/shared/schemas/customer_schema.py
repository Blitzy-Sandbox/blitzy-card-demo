# ============================================================================
# Source: COBOL copybook CVCUS01Y.cpy — CUSTOMER-RECORD (RECLN 500)
# ============================================================================
# Pydantic v2 schemas for Customer entity — demographic and address data.
#
# Mainframe-to-Cloud migration: COBOL record layout → Pydantic v2 transport
# models exposed by the CardDemo REST / GraphQL API.
#
# Replaces:
#   * The CVCUS01Y.cpy CUSTOMER-RECORD (500-byte fixed-width VSAM row) whose
#     18 business fields (CUST-ID, CUST-FIRST-NAME, CUST-MIDDLE-NAME,
#     CUST-LAST-NAME, CUST-ADDR-LINE-1..3, CUST-ADDR-STATE-CD,
#     CUST-ADDR-COUNTRY-CD, CUST-ADDR-ZIP, CUST-PHONE-NUM-1..2, CUST-SSN,
#     CUST-GOVT-ISSUED-ID, CUST-DOB-YYYY-MM-DD, CUST-EFT-ACCOUNT-ID,
#     CUST-PRI-CARD-HOLDER-IND, CUST-FICO-CREDIT-SCORE) were read/written
#     by Account View (COACTVWC.cbl), Account Update (COACTUPC.cbl), and the
#     statement generation batch pipeline (CBSTM03A.CBL / CBSTM03B.CBL) via
#     EXEC CICS READ/WRITE CUSTFILE.
#   * There is no dedicated CICS customer screen; customer fields are
#     embedded within the Account View/Update BMS maps (COACTVW.CPY and
#     COACTUP.CPY). This schema file therefore provides standalone customer
#     transport contracts for reuse by the account_service and by any future
#     customer-centric endpoints (e.g., customer search, customer profile).
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
"""Pydantic v2 Customer schemas for the CardDemo REST/GraphQL API.

Converts the COBOL copybook ``app/cpy/CVCUS01Y.cpy`` (record layout
``CUSTOMER-RECORD``, 500-byte fixed-width record) into two Pydantic v2
transport models:

* :class:`CustomerResponse` — the canonical customer read model. All 18
  business fields are present; :class:`~pydantic.ConfigDict` is set with
  ``from_attributes=True`` so the service layer can construct a response
  directly from a :class:`~src.shared.models.customer.Customer` SQLAlchemy
  ORM row without an intermediate ``dict`` conversion.
* :class:`CustomerCreateRequest` — the customer create/update payload.
  ``cust_id``, ``first_name``, and ``last_name`` are required; all other
  fields are :class:`~typing.Optional` to support both CREATE operations
  (where demographic details may be supplied incrementally) and UPDATE
  operations (where only the subset of modified fields need be provided).

COBOL → Python Field Mapping
----------------------------
==============================  ==================  ==================  =========
COBOL Field                     COBOL Type          Python Field        Py Type
==============================  ==================  ==================  =========
CUST-ID                         ``PIC 9(09)``       ``cust_id``              str
CUST-FIRST-NAME                 ``PIC X(25)``       ``first_name``           str
CUST-MIDDLE-NAME                ``PIC X(25)``       ``middle_name``          str
CUST-LAST-NAME                  ``PIC X(25)``       ``last_name``            str
CUST-ADDR-LINE-1                ``PIC X(50)``       ``addr_line_1``          str
CUST-ADDR-LINE-2                ``PIC X(50)``       ``addr_line_2``          str
CUST-ADDR-LINE-3                ``PIC X(50)``       ``addr_line_3``          str
CUST-ADDR-STATE-CD              ``PIC X(02)``       ``state_cd``             str
CUST-ADDR-COUNTRY-CD            ``PIC X(03)``       ``country_cd``           str
CUST-ADDR-ZIP                   ``PIC X(10)``       ``addr_zip``             str
CUST-PHONE-NUM-1                ``PIC X(15)``       ``phone_num_1``          str
CUST-PHONE-NUM-2                ``PIC X(15)``       ``phone_num_2``          str
CUST-SSN                        ``PIC 9(09)``†      ``ssn``                  str
CUST-GOVT-ISSUED-ID             ``PIC X(20)``       ``govt_issued_id``       str
CUST-DOB-YYYY-MM-DD             ``PIC X(10)``       ``dob``                  str
CUST-EFT-ACCOUNT-ID             ``PIC X(10)``       ``eft_account_id``       str
CUST-PRI-CARD-HOLDER-IND        ``PIC X(01)``       ``pri_card_holder_ind``  str
CUST-FICO-CREDIT-SCORE          ``PIC 9(03)``       ``fico_credit_score``    int
FILLER                          ``PIC X(168)``      — (not mapped)           —
==============================  ==================  ==================  =========

† **Sensitive — SSN.** The 9-digit US Social Security Number is stored as
  :class:`str` (not :class:`int`) to preserve leading zeros from the COBOL
  ``PIC 9(09)`` source (e.g. ``'012345678'`` must round-trip without loss
  to ``12345678``). The Agent Action Plan (AAP §0.5.1 — ``customer`` entry:
  "encrypted SSN field"; AAP §0.7.2 "Security Requirements") mandates that
  this column be protected at rest. No cryptographic transformation is
  applied at the schema layer; column-level encryption / tokenization is
  the responsibility of the Aurora PostgreSQL persistence configuration
  (e.g., pgcrypto, AWS KMS-backed column encryption, or application-layer
  cryptography in the service layer). Consumers must redact this field
  before logging or emitting it into observability pipelines.

Design Notes
------------
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length constraints and
  :func:`~pydantic.field_validator` for business-rule enforcement.
* **``ConfigDict(from_attributes=True)``** is applied only to
  :class:`CustomerResponse`. This enables Pydantic v2 ORM mode so the
  service layer (e.g. ``src.api.services.account_service``) can
  instantiate a response directly from a :class:`Customer` SQLAlchemy row
  without an intermediate ``dict``. The request schema does NOT receive
  this config because request payloads always arrive as JSON-decoded
  dicts from the REST / GraphQL transport layer.
* **Identifier fields as :class:`str`** — ``cust_id`` and ``ssn`` (derived
  from COBOL numeric pictures ``PIC 9(09)``) are stored as strings to
  preserve leading zeros byte-for-byte. This preserves parity with the
  VSAM KSDS records and the fixture data in
  ``app/data/ASCII/custdata.txt`` (loaded via
  ``db/migrations/V3__seed_data.sql``).
* **``fico_credit_score`` as :class:`int`** — the 3-digit COBOL
  ``PIC 9(03)`` numeric field maps cleanly to a Python integer (and to
  PostgreSQL ``INTEGER`` at the ORM layer) because FICO scores carry no
  meaningful leading zeros (valid range is 300–850). The schema-layer
  validator still allows the 0–999 full ``PIC 9(03)`` domain to
  accommodate the ``0`` sentinel written by the seed fixture for
  "not scored" customers. Storing it as an integer enables efficient
  server-side ordering, range predicates, and aggregation queries
  without application-side string-to-integer coercion.
* **``dob`` as 10-character :class:`str`** — matches the COBOL
  ``CUST-DOB-YYYY-MM-DD`` field literally; date validation is delegated
  to :mod:`src.shared.utils.date_utils` for the authoritative
  ``CSUTLDTC``-equivalent rules. The schema-layer validator performs a
  lightweight format check (``YYYY-MM-DD``) to reject obviously malformed
  input early; it deliberately accepts the empty string (the COBOL
  zero-initialised value written by the seed fixture) so that
  round-tripping VSAM rows with no DOB does not raise.
* **String preservation** — text fields are NOT stripped by the schema
  layer so that fixed-width COBOL ``PIC X(N)`` values with trailing
  spaces flow through unchanged, preserving exact parity with the
  on-disk VSAM ``CUSTOMER-RECORD`` row layout.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI / Uvicorn container image).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (F-004 Account View,
F-005 Account Update — both consume CUSTOMER-RECORD).
AAP §0.4.1 — Refactored Structure Planning (``customer_schema.py`` row).
AAP §0.5.1 — File-by-File Transformation Plan (``customer_schema.py`` row).
AAP §0.7.1 — Refactoring-Specific Rules (preserve behavior, minimal
change clause).
AAP §0.7.2 — Security Requirements (SSN protection, encryption at rest).
:class:`src.shared.models.customer.Customer` — SQLAlchemy ORM counterpart
whose rows are converted into :class:`CustomerResponse` instances via
``ConfigDict(from_attributes=True)``.
``app/cpy/CVCUS01Y.cpy`` — Original COBOL record layout (source artifact,
retained for traceability under AAP §0.7.1).
``app/jcl/CUSTFILE.jcl`` — Original VSAM cluster definition
(RECSZ(500 500), KEYS(9 0)).
"""

from typing import Optional  # noqa: UP045  # schema requires `typing.Optional`

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Private module constants — COBOL PIC-clause field widths from CVCUS01Y.cpy
# ---------------------------------------------------------------------------
# Keeping these as private module constants (leading underscore) rather than
# inlining them into every ``Field(max_length=...)`` call centralises the
# COBOL → Python width mapping in one place and guarantees internal
# consistency between the request and response schemas. Every value below
# is derived VERBATIM from the corresponding ``PIC X(N)`` / ``PIC 9(N)``
# clause in ``app/cpy/CVCUS01Y.cpy`` and must match the
# ``src.shared.models.customer.Customer`` SQLAlchemy column widths.
_CUST_ID_LEN: int = 9  # CUST-ID                       PIC 9(09)
_FIRST_NAME_MAX_LEN: int = 25  # CUST-FIRST-NAME               PIC X(25)
_MIDDLE_NAME_MAX_LEN: int = 25  # CUST-MIDDLE-NAME              PIC X(25)
_LAST_NAME_MAX_LEN: int = 25  # CUST-LAST-NAME                PIC X(25)
_ADDR_LINE_MAX_LEN: int = 50  # CUST-ADDR-LINE-1 / -2 / -3    PIC X(50)
_STATE_CD_MAX_LEN: int = 2  # CUST-ADDR-STATE-CD            PIC X(02)
_COUNTRY_CD_MAX_LEN: int = 3  # CUST-ADDR-COUNTRY-CD          PIC X(03)
_ADDR_ZIP_MAX_LEN: int = 10  # CUST-ADDR-ZIP                 PIC X(10)
_PHONE_NUM_MAX_LEN: int = 15  # CUST-PHONE-NUM-1 / -2         PIC X(15)
_SSN_LEN: int = 9  # CUST-SSN                      PIC 9(09)
_GOVT_ID_MAX_LEN: int = 20  # CUST-GOVT-ISSUED-ID           PIC X(20)
_DOB_LEN: int = 10  # CUST-DOB-YYYY-MM-DD           PIC X(10)
_EFT_ACCT_ID_MAX_LEN: int = 10  # CUST-EFT-ACCOUNT-ID           PIC X(10)
_PRI_CARD_HOLDER_IND_LEN: int = 1  # CUST-PRI-CARD-HOLDER-IND      PIC X(01)

# FICO credit score domain — COBOL ``PIC 9(03)`` admits ``000 .. 999``. We
# therefore validate inclusively against ``0 .. 999`` rather than the
# real-world FICO range of ``300 .. 850`` so that the ``0`` sentinel used
# by the seed fixture for un-scored customers (loaded from
# ``app/data/ASCII/custdata.txt`` via ``db/migrations/V3__seed_data.sql``)
# flows through unchanged. Clamping to ``300 .. 850`` would reject legal
# on-disk values and break round-tripping.
_FICO_MIN: int = 0  # Inclusive lower bound (PIC 9(03) domain min).
_FICO_MAX: int = 999  # Inclusive upper bound (PIC 9(03) domain max).

# DOB format length — ``YYYY-MM-DD`` is exactly 10 characters: 4 (year) + 1
# (dash) + 2 (month) + 1 (dash) + 2 (day) = 10. Matches the COBOL field
# width PIC X(10) exactly.
_DOB_YEAR_LEN: int = 4
_DOB_MONTH_LEN: int = 2
_DOB_DAY_LEN: int = 2

# Calendar bounds used by the lightweight ``dob`` format validator. The
# authoritative leap-year / day-of-month rules live in
# :mod:`src.shared.utils.date_utils` (CSUTLDTC port); this schema-layer
# check only rejects values that are structurally / numerically obviously
# out of range. Service-layer code is expected to invoke the full date
# utility before persisting to the database.
_MIN_MONTH: int = 1
_MAX_MONTH: int = 12
_MIN_DAY: int = 1
_MAX_DAY: int = 31

# ---------------------------------------------------------------------------
# Shared field-validator helpers
# ---------------------------------------------------------------------------
# The three business-rule validators (``cust_id`` must be exactly 9 digits;
# ``dob`` must match ``YYYY-MM-DD``; ``fico_credit_score`` must be in
# range 0..999) are identical for both :class:`CustomerResponse` and
# :class:`CustomerCreateRequest`. To avoid code duplication while keeping
# each class self-contained, the validators below are private module-level
# helpers that BOTH class-level :func:`~pydantic.field_validator` wrappers
# delegate to. This guarantees identical behaviour on both the write-side
# (request) and read-side (response) of the API.


def _validate_cust_id_exact(value: str) -> str:
    """Enforce ``cust_id`` is a 9-character ASCII-digit string.

    Mirrors the COBOL ``PIC 9(09)`` constraint on ``CUST-ID`` — every
    character must be a decimal digit (``'0'``..``'9'``) and the total
    length must be exactly 9. Leading zeros are PRESERVED (required for
    round-trip parity with VSAM keys).

    Parameters
    ----------
    value : str
        Candidate ``cust_id`` from the request/response payload.

    Returns
    -------
    str
        The original ``value`` unchanged (no stripping / padding) so that
        byte-for-byte parity with the VSAM KSDS key is retained.

    Raises
    ------
    ValueError
        * When ``value`` is ``None``.
        * When ``value`` is not a :class:`str`.
        * When ``value`` is not exactly 9 characters long.
        * When ``value`` contains any non-digit character.
    """
    if value is None:
        raise ValueError("cust_id must not be null")
    if not isinstance(value, str):
        raise ValueError(
            f"cust_id must be a string; got {type(value).__name__}"
        )
    if len(value) != _CUST_ID_LEN:
        raise ValueError(
            f"cust_id must be exactly {_CUST_ID_LEN} characters "
            f"(COBOL PIC 9(09)); got length {len(value)}"
        )
    if not value.isdigit():
        raise ValueError(
            f"cust_id must contain only digits 0-9 "
            f"(COBOL PIC 9(09)); got {value!r}"
        )
    return value


def _validate_dob_format(value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
    """Validate that ``dob``, when provided, matches ``YYYY-MM-DD``.

    The check is deliberately lightweight — it verifies the structural
    shape (``dddd-dd-dd``) and that the month / day numerals fall within
    the calendar bounds 1..12 and 1..31 respectively. Authoritative
    calendar logic (leap-year handling, month-specific day-of-month
    ceilings, century rules) lives in :mod:`src.shared.utils.date_utils`
    which ports the ``CSUTLDTC.cbl`` program; the schema layer performs
    only the coarse-grained format gate so that obvious structural
    violations are rejected at the transport boundary rather than being
    caught deeper in the service layer.

    An *empty* string (``''``) is accepted without further checks — this
    is the canonical COBOL zero-initialised value for an unspecified
    date and is the value written by the seed fixture for customers
    without a recorded DOB. The ORM counterpart
    (:class:`src.shared.models.customer.Customer`) defaults the ``dob``
    column to the empty string (see AAP §0.7.1 "minimal change clause")
    and we preserve that round-trip fidelity here.

    ``None`` is also accepted so that :class:`CustomerCreateRequest`
    (where ``dob`` is :class:`~typing.Optional`) may omit the field on
    inbound CREATE / UPDATE requests.

    Parameters
    ----------
    value : Optional[str]
        Candidate ``dob`` string or ``None`` / ``''`` (both accepted).

    Returns
    -------
    Optional[str]
        The original ``value`` unchanged.

    Raises
    ------
    ValueError
        * When ``value`` is a non-empty string whose length is not 10.
        * When ``value`` does not match ``YYYY-MM-DD`` (dashes at
          indices 4 and 7; digits elsewhere).
        * When the month segment is not in ``01..12``.
        * When the day segment is not in ``01..31``.
    """
    # ``None`` and empty-string are valid representations of "no DOB
    # provided". Accept them without further checks so that the schema
    # does not reject legitimate round-trip data.
    if value is None or value == "":
        return value
    if not isinstance(value, str):
        raise ValueError(
            f"dob must be a string; got {type(value).__name__}"
        )
    if len(value) != _DOB_LEN:
        raise ValueError(
            f"dob must be exactly {_DOB_LEN} characters in YYYY-MM-DD "
            f"format (COBOL PIC X(10) CUST-DOB-YYYY-MM-DD); got length "
            f"{len(value)}"
        )
    # Structural positions of the two dashes in YYYY-MM-DD are fixed.
    # Indices: 0123-56-89 (dashes at indices 4 and 7).
    if value[_DOB_YEAR_LEN] != "-" or (
        value[_DOB_YEAR_LEN + 1 + _DOB_MONTH_LEN] != "-"
    ):
        raise ValueError(
            f"dob must match YYYY-MM-DD format with dashes at positions "
            f"4 and 7; got {value!r}"
        )
    year_str = value[:_DOB_YEAR_LEN]
    month_str = value[
        _DOB_YEAR_LEN + 1 : _DOB_YEAR_LEN + 1 + _DOB_MONTH_LEN
    ]
    day_str = value[_DOB_YEAR_LEN + 1 + _DOB_MONTH_LEN + 1 :]
    if not (
        year_str.isdigit() and month_str.isdigit() and day_str.isdigit()
    ):
        raise ValueError(
            f"dob year, month, and day segments must all be numeric "
            f"digits (COBOL CUST-DOB-YYYY-MM-DD PIC X(10)); got "
            f"{value!r}"
        )
    month_val = int(month_str)
    day_val = int(day_str)
    if not (_MIN_MONTH <= month_val <= _MAX_MONTH):
        raise ValueError(
            f"dob month must be between {_MIN_MONTH:02d} and "
            f"{_MAX_MONTH:02d}; got month={month_str}"
        )
    if not (_MIN_DAY <= day_val <= _MAX_DAY):
        raise ValueError(
            f"dob day must be between {_MIN_DAY:02d} and {_MAX_DAY:02d}; "
            f"got day={day_str}"
        )
    return value


def _validate_fico_range(value: Optional[int]) -> Optional[int]:  # noqa: UP045  # schema requires `typing.Optional`
    """Ensure ``fico_credit_score`` is within the COBOL ``PIC 9(03)`` domain.

    COBOL ``PIC 9(03)`` admits the unsigned-integer range ``000..999``.
    We validate inclusively against ``[_FICO_MIN, _FICO_MAX]`` rather
    than the real-world FICO-score band of ``300..850`` because the
    CardDemo seed fixture (``app/data/ASCII/custdata.txt``) stores
    ``000`` for customers without a recorded credit score, and rejecting
    ``0`` at the schema layer would break round-trip fidelity with the
    migrated VSAM data (see AAP §0.7.1 "minimal change clause").

    ``None`` is accepted so that :class:`CustomerCreateRequest`
    (where ``fico_credit_score`` is :class:`~typing.Optional`) may omit
    the field on inbound CREATE / UPDATE requests.

    :class:`bool` values are explicitly rejected even though Python
    treats ``bool`` as a subclass of :class:`int`; a ``True`` or
    ``False`` landing in a FICO field is almost certainly a caller
    error rather than a legitimate score.

    Parameters
    ----------
    value : Optional[int]
        Candidate ``fico_credit_score`` from the payload.

    Returns
    -------
    Optional[int]
        The original ``value`` unchanged.

    Raises
    ------
    ValueError
        * When ``value`` is a :class:`bool`.
        * When ``value`` is not an :class:`int` (and is not ``None``).
        * When ``value`` is outside ``[0, 999]``.
    """
    if value is None:
        return value
    # ``bool`` is a subclass of ``int`` in Python — reject explicitly so
    # a stray ``True`` / ``False`` does not silently become 1 / 0.
    if isinstance(value, bool):
        raise ValueError(
            "fico_credit_score must be an integer, not a boolean"
        )
    if not isinstance(value, int):
        raise ValueError(
            f"fico_credit_score must be an integer; got "
            f"{type(value).__name__}"
        )
    if not (_FICO_MIN <= value <= _FICO_MAX):
        raise ValueError(
            f"fico_credit_score must be between {_FICO_MIN} and "
            f"{_FICO_MAX} (COBOL CUST-FICO-CREDIT-SCORE PIC 9(03)); "
            f"got {value}"
        )
    return value


# ---------------------------------------------------------------------------
# CustomerResponse — full read model (one-to-one with VSAM CUSTOMER-RECORD)
# ---------------------------------------------------------------------------


class CustomerResponse(BaseModel):
    """Pydantic v2 response schema for the CardDemo customer entity.

    One-to-one transport-layer counterpart of the COBOL copybook
    ``app/cpy/CVCUS01Y.cpy`` record ``CUSTOMER-RECORD`` (500 bytes).
    Every one of the 18 business fields defined in that copybook is
    exposed here using the same field widths and the same string
    preservation semantics (no stripping) so that a row retrieved from
    Aurora PostgreSQL round-trips through the API with byte-for-byte
    parity to the legacy VSAM row.

    ORM Mode
    --------
    ``model_config = ConfigDict(from_attributes=True)`` enables
    Pydantic v2 "from attributes" (a.k.a. ORM) mode. The API service
    layer may therefore construct a :class:`CustomerResponse` directly
    from a :class:`~src.shared.models.customer.Customer` SQLAlchemy row:

    >>> from src.shared.models.customer import Customer  # doctest: +SKIP
    >>> customer_row: Customer  # doctest: +SKIP
    >>> response = CustomerResponse.model_validate(customer_row)  # doctest: +SKIP

    This avoids the intermediate ``dict()`` conversion that Pydantic v1
    required and matches the pattern used by the account, card, and
    transaction response schemas elsewhere in the
    :mod:`src.shared.schemas` package.

    Field Semantics
    ---------------
    * **Identifiers preserve leading zeros.** ``cust_id`` and ``ssn``
      (both COBOL ``PIC 9(09)`` fields) are typed as :class:`str` so
      values like ``'012345678'`` round-trip faithfully.
    * **Text fields are NOT stripped.** Trailing-space padding from
      fixed-width ``PIC X(N)`` COBOL fields flows through untouched so
      that the schema faithfully represents the on-disk VSAM byte
      layout.
    * **``dob`` is a raw 10-character string.** Full calendar validation
      is performed by :mod:`src.shared.utils.date_utils`
      (``CSUTLDTC``-equivalent). The empty string is a legitimate
      "no DOB recorded" sentinel — it is NOT rejected by the
      ``_validate_dob_format`` helper.
    * **``fico_credit_score`` is an :class:`int`.** Validated to
      ``0..999`` (the COBOL ``PIC 9(03)`` domain), which admits the
      ``0`` sentinel for un-scored customers.
    * **``ssn`` is sensitive.** Consumers must not log the raw value
      and must ensure that column-level encryption / tokenization is
      configured at the Aurora PostgreSQL layer (AAP §0.5.1 /
      §0.7.2).

    Attributes
    ----------
    cust_id : str
        9-character numeric customer identifier (COBOL
        ``CUST-ID PIC 9(09)``). Primary key of the ``customer`` table.
    first_name : str
        Customer first/given name (COBOL
        ``CUST-FIRST-NAME PIC X(25)``).
    middle_name : str
        Customer middle name (COBOL ``CUST-MIDDLE-NAME PIC X(25)``).
        Empty string when not supplied.
    last_name : str
        Customer last/family name (COBOL
        ``CUST-LAST-NAME PIC X(25)``).
    addr_line_1 : str
        First line of the postal address (COBOL
        ``CUST-ADDR-LINE-1 PIC X(50)``).
    addr_line_2 : str
        Second line of the postal address (COBOL
        ``CUST-ADDR-LINE-2 PIC X(50)``). Empty string when not
        supplied.
    addr_line_3 : str
        Third line of the postal address (COBOL
        ``CUST-ADDR-LINE-3 PIC X(50)``). Empty string when not
        supplied.
    state_cd : str
        2-character state code (COBOL ``CUST-ADDR-STATE-CD PIC X(02)``).
    country_cd : str
        3-character ISO country code (COBOL
        ``CUST-ADDR-COUNTRY-CD PIC X(03)``).
    addr_zip : str
        Postal ZIP / postcode (COBOL ``CUST-ADDR-ZIP PIC X(10)``).
    phone_num_1 : str
        Primary phone number (COBOL ``CUST-PHONE-NUM-1 PIC X(15)``).
    phone_num_2 : str
        Secondary phone number (COBOL
        ``CUST-PHONE-NUM-2 PIC X(15)``). Empty string when not
        supplied.
    ssn : str
        9-digit US Social Security Number (COBOL
        ``CUST-SSN PIC 9(09)``). **Sensitive** — must not be logged or
        emitted to observability pipelines; encryption at rest is
        required per AAP §0.7.2.
    govt_issued_id : str
        Government-issued ID reference (COBOL
        ``CUST-GOVT-ISSUED-ID PIC X(20)``).
    dob : str
        Date of birth in ``YYYY-MM-DD`` format (COBOL
        ``CUST-DOB-YYYY-MM-DD PIC X(10)``). Empty string indicates no
        DOB on file.
    eft_account_id : str
        Electronic Funds Transfer account identifier (COBOL
        ``CUST-EFT-ACCOUNT-ID PIC X(10)``).
    pri_card_holder_ind : str
        Primary-cardholder flag (COBOL
        ``CUST-PRI-CARD-HOLDER-IND PIC X(01)``). Typically ``'Y'`` or
        ``'N'``.
    fico_credit_score : int
        FICO credit score (COBOL
        ``CUST-FICO-CREDIT-SCORE PIC 9(03)``). Validated to
        ``0..999``; ``0`` denotes "not scored".
    """

    model_config = ConfigDict(from_attributes=True)

    # ------------------------------------------------------------------
    # Primary key & name fields
    # ------------------------------------------------------------------
    cust_id: str = Field(
        ...,
        max_length=_CUST_ID_LEN,
        description=(
            "9-digit customer identifier (primary key). "
            "Maps to COBOL CUST-ID PIC 9(09)."
        ),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description=(
            "Customer first/given name. "
            "Maps to COBOL CUST-FIRST-NAME PIC X(25)."
        ),
    )
    middle_name: str = Field(
        ...,
        max_length=_MIDDLE_NAME_MAX_LEN,
        description=(
            "Customer middle name. Empty string when not supplied. "
            "Maps to COBOL CUST-MIDDLE-NAME PIC X(25)."
        ),
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description=(
            "Customer last/family name. "
            "Maps to COBOL CUST-LAST-NAME PIC X(25)."
        ),
    )

    # ------------------------------------------------------------------
    # Address fields
    # ------------------------------------------------------------------
    addr_line_1: str = Field(
        ...,
        max_length=_ADDR_LINE_MAX_LEN,
        description=(
            "First line of the postal address. "
            "Maps to COBOL CUST-ADDR-LINE-1 PIC X(50)."
        ),
    )
    addr_line_2: str = Field(
        ...,
        max_length=_ADDR_LINE_MAX_LEN,
        description=(
            "Second line of the postal address. "
            "Empty string when not supplied. "
            "Maps to COBOL CUST-ADDR-LINE-2 PIC X(50)."
        ),
    )
    addr_line_3: str = Field(
        ...,
        max_length=_ADDR_LINE_MAX_LEN,
        description=(
            "Third line of the postal address. "
            "Empty string when not supplied. "
            "Maps to COBOL CUST-ADDR-LINE-3 PIC X(50)."
        ),
    )
    state_cd: str = Field(
        ...,
        max_length=_STATE_CD_MAX_LEN,
        description=(
            "2-character state / province code. "
            "Maps to COBOL CUST-ADDR-STATE-CD PIC X(02)."
        ),
    )
    country_cd: str = Field(
        ...,
        max_length=_COUNTRY_CD_MAX_LEN,
        description=(
            "3-character ISO country code. "
            "Maps to COBOL CUST-ADDR-COUNTRY-CD PIC X(03)."
        ),
    )
    addr_zip: str = Field(
        ...,
        max_length=_ADDR_ZIP_MAX_LEN,
        description=(
            "Postal ZIP / postcode. "
            "Maps to COBOL CUST-ADDR-ZIP PIC X(10)."
        ),
    )

    # ------------------------------------------------------------------
    # Phone fields
    # ------------------------------------------------------------------
    phone_num_1: str = Field(
        ...,
        max_length=_PHONE_NUM_MAX_LEN,
        description=(
            "Primary phone number. "
            "Maps to COBOL CUST-PHONE-NUM-1 PIC X(15)."
        ),
    )
    phone_num_2: str = Field(
        ...,
        max_length=_PHONE_NUM_MAX_LEN,
        description=(
            "Secondary phone number. Empty string when not supplied. "
            "Maps to COBOL CUST-PHONE-NUM-2 PIC X(15)."
        ),
    )

    # ------------------------------------------------------------------
    # Sensitive & identity fields
    # ------------------------------------------------------------------
    ssn: str = Field(
        ...,
        max_length=_SSN_LEN,
        description=(
            "9-digit US Social Security Number. SENSITIVE — must not "
            "be logged; encryption at rest is required per AAP §0.7.2. "
            "Maps to COBOL CUST-SSN PIC 9(09)."
        ),
    )
    govt_issued_id: str = Field(
        ...,
        max_length=_GOVT_ID_MAX_LEN,
        description=(
            "Government-issued identifier (e.g., driver's license). "
            "Maps to COBOL CUST-GOVT-ISSUED-ID PIC X(20)."
        ),
    )
    dob: str = Field(
        ...,
        max_length=_DOB_LEN,
        description=(
            "Date of birth in YYYY-MM-DD format. Empty string denotes "
            "no DOB on file. "
            "Maps to COBOL CUST-DOB-YYYY-MM-DD PIC X(10)."
        ),
    )

    # ------------------------------------------------------------------
    # Banking & cardholder fields
    # ------------------------------------------------------------------
    eft_account_id: str = Field(
        ...,
        max_length=_EFT_ACCT_ID_MAX_LEN,
        description=(
            "Electronic Funds Transfer (EFT) account identifier. "
            "Maps to COBOL CUST-EFT-ACCOUNT-ID PIC X(10)."
        ),
    )
    pri_card_holder_ind: str = Field(
        ...,
        max_length=_PRI_CARD_HOLDER_IND_LEN,
        description=(
            "Primary-cardholder flag (typically 'Y' or 'N'). "
            "Maps to COBOL CUST-PRI-CARD-HOLDER-IND PIC X(01)."
        ),
    )
    fico_credit_score: int = Field(
        ...,
        ge=_FICO_MIN,
        le=_FICO_MAX,
        description=(
            "FICO credit score (0..999); 0 denotes 'not scored'. "
            "Maps to COBOL CUST-FICO-CREDIT-SCORE PIC 9(03)."
        ),
    )

    # ------------------------------------------------------------------
    # Business-rule validators
    # ------------------------------------------------------------------
    @field_validator("cust_id")
    @classmethod
    def _validate_cust_id(cls, value: str) -> str:
        """Validate that ``cust_id`` is exactly 9 ASCII digits.

        Delegates to :func:`_validate_cust_id_exact` for identical
        behaviour on the response and request schemas.

        Parameters
        ----------
        value : str
            The candidate ``cust_id`` value.

        Returns
        -------
        str
            The validated ``cust_id`` unchanged.

        Raises
        ------
        ValueError
            If ``cust_id`` is null, not a string, not exactly 9
            characters long, or contains non-digit characters.
        """
        return _validate_cust_id_exact(value)

    @field_validator("dob")
    @classmethod
    def _validate_dob(cls, value: str) -> str:
        """Validate that ``dob`` matches ``YYYY-MM-DD`` or is empty.

        Delegates to :func:`_validate_dob_format`. On the response
        schema ``dob`` is typed as a required :class:`str` (empty
        string represents "no DOB on file") so this wrapper narrows
        the return type accordingly.

        Parameters
        ----------
        value : str
            The candidate ``dob`` value.

        Returns
        -------
        str
            The validated ``dob`` unchanged.

        Raises
        ------
        ValueError
            If ``dob`` is a non-empty string that does not conform to
            the ``YYYY-MM-DD`` format or whose month/day components
            fall outside the calendar bounds 1..12 / 1..31.
        """
        # The shared helper accepts ``Optional[str]`` but on the
        # response schema ``value`` is always a ``str``; asserting
        # (rather than silently coercing) keeps the public type
        # contract precise without any runtime overhead in the happy
        # path.
        validated = _validate_dob_format(value)
        assert validated is not None, (
            "response dob must not be None after validation"
        )
        return validated

    @field_validator("fico_credit_score", mode="before")
    @classmethod
    def _validate_fico(cls, value: int) -> int:
        """Validate that ``fico_credit_score`` is within ``0..999``.

        Delegates to :func:`_validate_fico_range`. On the response
        schema the field is required so we narrow from the shared
        helper's ``Optional[int]`` to ``int``.

        This validator runs in ``mode='before'`` so it sees the raw
        untyped input. This is deliberate — Pydantic v2's default
        ``'after'`` mode coerces :class:`bool` (a subclass of
        :class:`int`) into ``0`` or ``1`` before the validator runs,
        which would silently accept ``True``/``False`` as FICO scores.
        Running in ``'before'`` mode preserves the raw input so the
        :func:`isinstance(value, bool)` guard in
        :func:`_validate_fico_range` actually fires.

        Parameters
        ----------
        value : int
            The candidate ``fico_credit_score`` value.

        Returns
        -------
        int
            The validated ``fico_credit_score`` unchanged.

        Raises
        ------
        ValueError
            If the value is a :class:`bool`, not an :class:`int`, or
            outside the inclusive range ``[0, 999]``.
        """
        validated = _validate_fico_range(value)
        assert validated is not None, (
            "response fico_credit_score must not be None after validation"
        )
        return validated


# ---------------------------------------------------------------------------
# CustomerCreateRequest — inbound CREATE / UPDATE payload
# ---------------------------------------------------------------------------


class CustomerCreateRequest(BaseModel):
    """Pydantic v2 request schema for creating / updating a customer.

    This schema models the inbound JSON payload accepted by the future
    customer CREATE / UPDATE API endpoints. Unlike
    :class:`CustomerResponse` (the canonical read model that mirrors
    the full VSAM record), this schema marks only ``cust_id``,
    ``first_name``, and ``last_name`` as mandatory — every other
    demographic, address, phone, identity, and banking field is
    :class:`~typing.Optional` so that:

    * **CREATE requests** may provide only the minimum identifying
      information and let the service / persistence layer default the
      remaining fields to empty strings / zero as per the COBOL
      ``PIC X(N)`` / ``PIC 9(N)`` zero-initialised semantics.
    * **UPDATE requests** (implemented as partial-update semantics) may
      submit only the changed subset of fields; the service layer will
      merge the payload onto the existing row, retaining any omitted
      field's current stored value.

    The three business-rule validators (:func:`_validate_cust_id_exact`,
    :func:`_validate_dob_format`, :func:`_validate_fico_range`) are the
    SAME helpers used by :class:`CustomerResponse`, guaranteeing
    identical validation on the write-side and the read-side of the
    API.

    This class deliberately OMITS ``model_config =
    ConfigDict(from_attributes=True)`` because request payloads always
    arrive as JSON-decoded dicts, not as SQLAlchemy rows — ORM mode is
    neither needed nor meaningful on the inbound path.

    Field Semantics
    ---------------
    * **Required fields.** ``cust_id`` (the primary key), ``first_name``,
      and ``last_name`` must be supplied on every CREATE request. On
      UPDATE requests the API / service layer is expected to extract
      ``cust_id`` from the URL path parameter and validate consistency
      with the body; ``first_name`` / ``last_name`` remain required
      because a customer with blank names would be a data-integrity
      violation.
    * **Optional fields.** All 15 remaining fields (address lines,
      state, country, ZIP, phones, SSN, government ID, DOB, EFT
      account, primary-cardholder indicator, FICO score) accept
      :data:`None`. When the service layer converts the request into
      an ORM row it must map :data:`None` to the appropriate COBOL
      zero-initialised default (empty string for
      :class:`str` / ``0`` for :class:`int`) so that the persisted
      row retains the fixed-width parity with the legacy VSAM layout.
    * **Validator applicability.** The ``_validate_dob`` and
      ``_validate_fico_credit_score`` validators tolerate :data:`None`
      (no validation performed) and only enforce the format / range
      rules when a concrete value is supplied. The ``_validate_cust_id``
      validator is strict because ``cust_id`` is required.

    Attributes
    ----------
    cust_id : str
        9-digit numeric customer identifier (COBOL
        ``CUST-ID PIC 9(09)``). **Required.**
    first_name : str
        Customer first/given name (COBOL
        ``CUST-FIRST-NAME PIC X(25)``). **Required.**
    last_name : str
        Customer last/family name (COBOL
        ``CUST-LAST-NAME PIC X(25)``). **Required.**
    middle_name : Optional[str]
        Customer middle name (COBOL
        ``CUST-MIDDLE-NAME PIC X(25)``). Optional.
    addr_line_1 : Optional[str]
        First line of the postal address (COBOL
        ``CUST-ADDR-LINE-1 PIC X(50)``). Optional.
    addr_line_2 : Optional[str]
        Second line of the postal address (COBOL
        ``CUST-ADDR-LINE-2 PIC X(50)``). Optional.
    addr_line_3 : Optional[str]
        Third line of the postal address (COBOL
        ``CUST-ADDR-LINE-3 PIC X(50)``). Optional.
    state_cd : Optional[str]
        2-character state code (COBOL
        ``CUST-ADDR-STATE-CD PIC X(02)``). Optional.
    country_cd : Optional[str]
        3-character ISO country code (COBOL
        ``CUST-ADDR-COUNTRY-CD PIC X(03)``). Optional.
    addr_zip : Optional[str]
        Postal ZIP / postcode (COBOL
        ``CUST-ADDR-ZIP PIC X(10)``). Optional.
    phone_num_1 : Optional[str]
        Primary phone number (COBOL
        ``CUST-PHONE-NUM-1 PIC X(15)``). Optional.
    phone_num_2 : Optional[str]
        Secondary phone number (COBOL
        ``CUST-PHONE-NUM-2 PIC X(15)``). Optional.
    ssn : Optional[str]
        9-digit US Social Security Number (COBOL
        ``CUST-SSN PIC 9(09)``). Optional. **Sensitive** — must be
        transported over TLS only and never logged.
    govt_issued_id : Optional[str]
        Government-issued ID reference (COBOL
        ``CUST-GOVT-ISSUED-ID PIC X(20)``). Optional.
    dob : Optional[str]
        Date of birth in ``YYYY-MM-DD`` format (COBOL
        ``CUST-DOB-YYYY-MM-DD PIC X(10)``). Optional.
    eft_account_id : Optional[str]
        Electronic Funds Transfer account identifier (COBOL
        ``CUST-EFT-ACCOUNT-ID PIC X(10)``). Optional.
    pri_card_holder_ind : Optional[str]
        Primary-cardholder flag (COBOL
        ``CUST-PRI-CARD-HOLDER-IND PIC X(01)``). Optional.
    fico_credit_score : Optional[int]
        FICO credit score (COBOL
        ``CUST-FICO-CREDIT-SCORE PIC 9(03)``). Optional; validated to
        ``0..999`` when supplied.
    """

    # ------------------------------------------------------------------
    # Required identification & name fields
    # ------------------------------------------------------------------
    cust_id: str = Field(
        ...,
        max_length=_CUST_ID_LEN,
        description=(
            "9-digit customer identifier (primary key). Required. "
            "Maps to COBOL CUST-ID PIC 9(09)."
        ),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description=(
            "Customer first/given name. Required. "
            "Maps to COBOL CUST-FIRST-NAME PIC X(25)."
        ),
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description=(
            "Customer last/family name. Required. "
            "Maps to COBOL CUST-LAST-NAME PIC X(25)."
        ),
    )

    # ------------------------------------------------------------------
    # Optional name field
    # ------------------------------------------------------------------
    middle_name: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_MIDDLE_NAME_MAX_LEN,
        description=(
            "Customer middle name. Optional. "
            "Maps to COBOL CUST-MIDDLE-NAME PIC X(25)."
        ),
    )

    # ------------------------------------------------------------------
    # Optional address fields
    # ------------------------------------------------------------------
    addr_line_1: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ADDR_LINE_MAX_LEN,
        description=(
            "First line of the postal address. Optional. "
            "Maps to COBOL CUST-ADDR-LINE-1 PIC X(50)."
        ),
    )
    addr_line_2: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ADDR_LINE_MAX_LEN,
        description=(
            "Second line of the postal address. Optional. "
            "Maps to COBOL CUST-ADDR-LINE-2 PIC X(50)."
        ),
    )
    addr_line_3: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ADDR_LINE_MAX_LEN,
        description=(
            "Third line of the postal address. Optional. "
            "Maps to COBOL CUST-ADDR-LINE-3 PIC X(50)."
        ),
    )
    state_cd: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_STATE_CD_MAX_LEN,
        description=(
            "2-character state / province code. Optional. "
            "Maps to COBOL CUST-ADDR-STATE-CD PIC X(02)."
        ),
    )
    country_cd: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_COUNTRY_CD_MAX_LEN,
        description=(
            "3-character ISO country code. Optional. "
            "Maps to COBOL CUST-ADDR-COUNTRY-CD PIC X(03)."
        ),
    )
    addr_zip: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ADDR_ZIP_MAX_LEN,
        description=(
            "Postal ZIP / postcode. Optional. "
            "Maps to COBOL CUST-ADDR-ZIP PIC X(10)."
        ),
    )

    # ------------------------------------------------------------------
    # Optional phone fields
    # ------------------------------------------------------------------
    phone_num_1: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_PHONE_NUM_MAX_LEN,
        description=(
            "Primary phone number. Optional. "
            "Maps to COBOL CUST-PHONE-NUM-1 PIC X(15)."
        ),
    )
    phone_num_2: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_PHONE_NUM_MAX_LEN,
        description=(
            "Secondary phone number. Optional. "
            "Maps to COBOL CUST-PHONE-NUM-2 PIC X(15)."
        ),
    )

    # ------------------------------------------------------------------
    # Optional sensitive & identity fields
    # ------------------------------------------------------------------
    ssn: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_SSN_LEN,
        description=(
            "9-digit US Social Security Number. Optional. SENSITIVE — "
            "must be transported over TLS only and never logged. "
            "Encryption at rest is required per AAP §0.7.2. "
            "Maps to COBOL CUST-SSN PIC 9(09)."
        ),
    )
    govt_issued_id: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_GOVT_ID_MAX_LEN,
        description=(
            "Government-issued identifier (e.g., driver's license). "
            "Optional. "
            "Maps to COBOL CUST-GOVT-ISSUED-ID PIC X(20)."
        ),
    )
    dob: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_DOB_LEN,
        description=(
            "Date of birth in YYYY-MM-DD format. Optional. "
            "Maps to COBOL CUST-DOB-YYYY-MM-DD PIC X(10)."
        ),
    )

    # ------------------------------------------------------------------
    # Optional banking & cardholder fields
    # ------------------------------------------------------------------
    eft_account_id: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_EFT_ACCT_ID_MAX_LEN,
        description=(
            "Electronic Funds Transfer (EFT) account identifier. "
            "Optional. "
            "Maps to COBOL CUST-EFT-ACCOUNT-ID PIC X(10)."
        ),
    )
    pri_card_holder_ind: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_PRI_CARD_HOLDER_IND_LEN,
        description=(
            "Primary-cardholder flag (typically 'Y' or 'N'). Optional. "
            "Maps to COBOL CUST-PRI-CARD-HOLDER-IND PIC X(01)."
        ),
    )
    fico_credit_score: Optional[int] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        ge=_FICO_MIN,
        le=_FICO_MAX,
        description=(
            "FICO credit score (0..999); 0 denotes 'not scored'. "
            "Optional. "
            "Maps to COBOL CUST-FICO-CREDIT-SCORE PIC 9(03)."
        ),
    )

    # ------------------------------------------------------------------
    # Business-rule validators (shared helpers; see CustomerResponse)
    # ------------------------------------------------------------------
    @field_validator("cust_id")
    @classmethod
    def _validate_cust_id(cls, value: str) -> str:
        """Validate that ``cust_id`` is exactly 9 ASCII digits.

        Delegates to :func:`_validate_cust_id_exact` for identical
        behaviour on the request and response schemas. ``cust_id``
        is a required field on every CREATE / UPDATE payload, so
        :data:`None` is NOT an accepted value.

        Parameters
        ----------
        value : str
            The candidate ``cust_id`` value from the inbound payload.

        Returns
        -------
        str
            The validated ``cust_id`` unchanged.

        Raises
        ------
        ValueError
            If ``cust_id`` is null, not a string, not exactly 9
            characters long, or contains non-digit characters.
        """
        return _validate_cust_id_exact(value)

    @field_validator("dob")
    @classmethod
    def _validate_dob(cls, value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """Validate that ``dob``, when provided, matches ``YYYY-MM-DD``.

        Delegates to :func:`_validate_dob_format`. Because ``dob`` is
        :class:`~typing.Optional` on the request schema, :data:`None`
        and the empty string are both accepted and returned unchanged;
        format / calendar-range checks apply only when the caller has
        supplied a non-empty string.

        Parameters
        ----------
        value : Optional[str]
            The candidate ``dob`` value, or :data:`None` /
            ``''`` to indicate "no DOB supplied".

        Returns
        -------
        Optional[str]
            The validated ``dob`` unchanged (including :data:`None` /
            ``''`` pass-through).

        Raises
        ------
        ValueError
            If ``dob`` is a non-empty string that does not conform to
            the ``YYYY-MM-DD`` format or whose month/day components
            fall outside the calendar bounds 1..12 / 1..31.
        """
        return _validate_dob_format(value)

    @field_validator("fico_credit_score", mode="before")
    @classmethod
    def _validate_fico_credit_score(
        cls, value: Optional[int]  # noqa: UP045  # schema requires `typing.Optional`
    ) -> Optional[int]:  # noqa: UP045  # schema requires `typing.Optional`
        """Validate that ``fico_credit_score``, when provided, is in 0..999.

        Delegates to :func:`_validate_fico_range`. Because
        ``fico_credit_score`` is :class:`~typing.Optional` on the
        request schema, :data:`None` is accepted and returned
        unchanged; the range check applies only when the caller has
        supplied a concrete integer value.

        This validator runs in ``mode='before'`` so it sees the raw
        untyped input. This is deliberate — Pydantic v2's default
        ``'after'`` mode coerces :class:`bool` (a subclass of
        :class:`int`) into ``0`` or ``1`` before the validator runs,
        which would silently accept ``True``/``False`` as FICO scores.
        Running in ``'before'`` mode preserves the raw input so the
        :func:`isinstance(value, bool)` guard in
        :func:`_validate_fico_range` actually fires.

        Parameters
        ----------
        value : Optional[int]
            The candidate ``fico_credit_score`` value, or :data:`None`
            to indicate "no score supplied".

        Returns
        -------
        Optional[int]
            The validated ``fico_credit_score`` unchanged (including
            :data:`None` pass-through).

        Raises
        ------
        ValueError
            If the value is a :class:`bool`, is not an :class:`int`,
            or is outside the inclusive range ``[0, 999]``.
        """
        return _validate_fico_range(value)


# ---------------------------------------------------------------------------
# Explicit export surface
# ---------------------------------------------------------------------------
__all__: list[str] = [
    "CustomerResponse",
    "CustomerCreateRequest",
]
