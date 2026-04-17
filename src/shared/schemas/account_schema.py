# ============================================================================
# Source: COBOL BMS symbolic maps ``app/cpy-bms/COACTVW.CPY`` (Account View,
#         F-004) and ``app/cpy-bms/COACTUP.CPY`` (Account Update, F-005),
#         together with the VSAM record layouts ``app/cpy/CVACT01Y.cpy``
#         (Account — ACCOUNT-RECORD) and ``app/cpy/CVCUS01Y.cpy``
#         (Customer — CUSTOMER-RECORD).
# ============================================================================
# Mainframe-to-Cloud migration: CICS ``SEND MAP`` / ``RECEIVE MAP`` (3270
# BMS screens) → FastAPI REST / GraphQL JSON bodies.
#
# Replaces:
#   * ``CACTVWAI`` (Account View, 31 business fields) — previously
#     emitted by ``COACTVWC.cbl`` via ``EXEC CICS SEND MAP('COACTVWA')``.
#     The modernized endpoint is ``GET /accounts/{account_id}`` and
#     returns :class:`AccountViewResponse` as its JSON body.
#   * ``CACTUPAI`` (Account Update, 39 editable business fields) —
#     previously consumed by ``COACTUPC.cbl`` via
#     ``EXEC CICS RECEIVE MAP('COACTUPA')``. The modernized endpoint is
#     ``PUT /accounts/{account_id}`` and accepts :class:`AccountUpdateRequest`
#     in the request body; on success it returns :class:`AccountUpdateResponse`
#     (structurally identical to :class:`AccountViewResponse`).
#
# Notable differences between the two BMS symbolic maps:
#   * The Account View screen uses a SINGLE 10-character composite date
#     field per date (e.g. ADTOPENI PIC X(10) = ``YYYY-MM-DD``), a SINGLE
#     12-character composite SSN field (ACSTSSNI PIC X(12) =
#     ``NNN-NN-NNNN``), and a SINGLE 13-character composite phone field
#     per phone (ACSPHN1I / ACSPHN2I PIC X(13) = ``(AAA)BBB-CCCC``).
#   * The Account Update screen BREAKS APART each composite into its
#     editable parts: three fields per date (year / month / day), three
#     fields per SSN (3+2+4 digits), and three fields per phone (area /
#     prefix / line). This mirrors the 3270 terminal's field-by-field
#     keying convention where each cursor-addressable MDT field was a
#     separate BMS attribute.
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
"""Pydantic v2 request/response schemas for the CardDemo Account API.

Implements the JSON transport contracts for Features F-004 (Account View)
and F-005 (Account Update) — the online CICS programs ``COACTVWC.cbl``
and ``COACTUPC.cbl`` in the legacy mainframe codebase. The Account View
feature emits a read-only 3-entity join across :class:`~src.shared.models.
account.Account`, :class:`~src.shared.models.card_cross_reference.
CardCrossReference`, and :class:`~src.shared.models.customer.Customer`;
the Account Update feature performs a transactional dual-write across
both the Account and Customer tables (mirroring the original CICS
SYNCPOINT / ROLLBACK pattern in ``COACTUPC.cbl``).

BMS → Python Field Mapping (Account View — COACTVW.CPY)
-------------------------------------------------------
================================  ============  =========================
BMS Field (CACTVWAI)              Python Type   Python Field
================================  ============  =========================
ACCTSIDI  ``PIC 9(11)``           ``str``       ``account_id``
ACSTTUSI  ``PIC X(1)``            ``str``       ``active_status``
ADTOPENI  ``PIC X(10)``           ``str``       ``open_date``
ACRDLIMI  ``PIC X(15)``           ``Decimal``   ``credit_limit``
AEXPDTI   ``PIC X(10)``           ``str``       ``expiration_date``
ACSHLIMI  ``PIC X(15)``           ``Decimal``   ``cash_credit_limit``
AREISDTI  ``PIC X(10)``           ``str``       ``reissue_date``
ACURBALI  ``PIC X(15)``           ``Decimal``   ``current_balance``
ACRCYCRI  ``PIC X(15)``           ``Decimal``   ``current_cycle_credit``
AADDGRPI  ``PIC X(10)``           ``str``       ``group_id``
ACRCYDBI  ``PIC X(15)``           ``Decimal``   ``current_cycle_debit``
ACSTNUMI  ``PIC X(9)``            ``str``       ``customer_id``
ACSTSSNI  ``PIC X(12)``           ``str``       ``customer_ssn``
ACSTDOBI  ``PIC X(10)``           ``str``       ``customer_dob``
ACSTFCOI  ``PIC X(3)``            ``str``       ``customer_fico_score``
ACSFNAMI  ``PIC X(25)``           ``str``       ``customer_first_name``
ACSMNAMI  ``PIC X(25)``           ``str``       ``customer_middle_name``
ACSLNAMI  ``PIC X(25)``           ``str``       ``customer_last_name``
ACSADL1I  ``PIC X(50)``           ``str``       ``customer_addr_line_1``
ACSSTTEI  ``PIC X(2)``            ``str``       ``customer_state_cd``
ACSADL2I  ``PIC X(50)``           ``str``       ``customer_addr_line_2``
ACSZIPCI  ``PIC X(5)``            ``str``       ``customer_zip``
ACSCITYI  ``PIC X(50)``           ``str``       ``customer_city``
ACSCTRYI  ``PIC X(3)``            ``str``       ``customer_country_cd``
ACSPHN1I  ``PIC X(13)``           ``str``       ``customer_phone_1``
ACSGOVTI  ``PIC X(20)``           ``str``       ``customer_govt_id``
ACSPHN2I  ``PIC X(13)``           ``str``       ``customer_phone_2``
ACSEFTCI  ``PIC X(10)``           ``str``       ``customer_eft_account_id``
ACSPFLGI  ``PIC X(1)``            ``str``       ``customer_pri_cardholder``
INFOMSGI  ``PIC X(45)``           ``Optional``  ``info_message``
ERRMSGI   ``PIC X(78)``           ``Optional``  ``error_message``
================================  ============  =========================

BMS → Python Field Mapping (Account Update — COACTUP.CPY)
---------------------------------------------------------
================================  ============  =========================
BMS Field (CACTUPAI)              Python Type   Python Field
================================  ============  =========================
ACCTSIDI  ``PIC X(11)``           ``str``       ``account_id``
ACSTTUSI  ``PIC X(1)``            ``str``       ``active_status``
OPNYEARI  ``PIC X(4)``            ``str``       ``open_date_year``
OPNMONI   ``PIC X(2)``            ``str``       ``open_date_month``
OPNDAYI   ``PIC X(2)``            ``str``       ``open_date_day``
ACRDLIMI  ``PIC X(15)``           ``Decimal``   ``credit_limit``
EXPYEARI  ``PIC X(4)``            ``str``       ``expiration_date_year``
EXPMONI   ``PIC X(2)``            ``str``       ``expiration_date_month``
EXPDAYI   ``PIC X(2)``            ``str``       ``expiration_date_day``
ACSHLIMI  ``PIC X(15)``           ``Decimal``   ``cash_credit_limit``
RISYEARI  ``PIC X(4)``            ``str``       ``reissue_date_year``
RISMONI   ``PIC X(2)``            ``str``       ``reissue_date_month``
RISDAYI   ``PIC X(2)``            ``str``       ``reissue_date_day``
AADDGRPI  ``PIC X(10)``           ``str``       ``group_id``
ACTSSN1I  ``PIC X(3)``            ``str``       ``customer_ssn_part1``
ACTSSN2I  ``PIC X(2)``            ``str``       ``customer_ssn_part2``
ACTSSN3I  ``PIC X(4)``            ``str``       ``customer_ssn_part3``
DOBYEARI  ``PIC X(4)``            ``str``       ``customer_dob_year``
DOBMONI   ``PIC X(2)``            ``str``       ``customer_dob_month``
DOBDAYI   ``PIC X(2)``            ``str``       ``customer_dob_day``
ACSTFCOI  ``PIC X(3)``            ``str``       ``customer_fico_score``
ACSFNAMI  ``PIC X(25)``           ``str``       ``customer_first_name``
ACSMNAMI  ``PIC X(25)``           ``str``       ``customer_middle_name``
ACSLNAMI  ``PIC X(25)``           ``str``       ``customer_last_name``
ACSADL1I  ``PIC X(50)``           ``str``       ``customer_addr_line_1``
ACSSTTEI  ``PIC X(2)``            ``str``       ``customer_state_cd``
ACSADL2I  ``PIC X(50)``           ``str``       ``customer_addr_line_2``
ACSZIPCI  ``PIC X(5)``            ``str``       ``customer_zip``
ACSCITYI  ``PIC X(50)``           ``str``       ``customer_city``
ACSCTRYI  ``PIC X(3)``            ``str``       ``customer_country_cd``
ACSPH1AI  ``PIC X(3)``            ``str``       ``customer_phone_1_area``
ACSPH1BI  ``PIC X(3)``            ``str``       ``customer_phone_1_prefix``
ACSPH1CI  ``PIC X(4)``            ``str``       ``customer_phone_1_line``
ACSGOVTI  ``PIC X(20)``           ``str``       ``customer_govt_id``
ACSPH2AI  ``PIC X(3)``            ``str``       ``customer_phone_2_area``
ACSPH2BI  ``PIC X(3)``            ``str``       ``customer_phone_2_prefix``
ACSPH2CI  ``PIC X(4)``            ``str``       ``customer_phone_2_line``
ACSEFTCI  ``PIC X(10)``           ``str``       ``customer_eft_account_id``
ACSPFLGI  ``PIC X(1)``            ``str``       ``customer_pri_cardholder``
================================  ============  =========================

Design Notes
------------
* **Financial precision** — every monetary field on both the View and
  Update schemas uses :class:`decimal.Decimal`, NEVER :class:`float`.
  The COBOL VSAM layouts in ``CVACT01Y.cpy`` define these fields as
  ``PIC S9(10)V99`` (signed packed decimal, 10 integer digits + 2
  fractional digits, 12 total decimal digits). The modernized schema
  enforces ``max_digits=15`` (a small safety margin over the COBOL
  12-digit domain) and ``decimal_places=2`` (exact match to the
  COBOL V99 fractional scale) on every monetary :class:`~pydantic.Field`.
  Any arithmetic performed on these values by downstream callers must
  use ``Decimal`` arithmetic with :data:`decimal.ROUND_HALF_EVEN`
  (banker's rounding, mirroring COBOL ``ROUNDED``).
* **Identifiers as strings** — ``account_id`` and ``customer_id`` are
  typed as :class:`str` (not :class:`int`) to preserve the fixed-width
  zero-padded representation enforced by the VSAM key layout
  (``PIC 9(11)`` and ``PIC 9(09)``). Converting to ``int`` would drop
  the leading zeros and break PK lookup against the Aurora PostgreSQL
  character-primary-key columns.
* **Date fields as strings** — date fields on the View schema
  (``open_date``, ``expiration_date``, ``reissue_date``,
  ``customer_dob``) and the segmented date parts on the Update schema
  (``open_date_year``, ``open_date_month``, ``open_date_day``, …) are
  typed as :class:`str` rather than :class:`datetime.date`. This
  preserves the COBOL ``PIC X(10)`` / ``PIC X(N)`` screen-layout
  semantics: the original BMS screens displayed and accepted dates as
  pre-formatted character strings (``CCYY-MM-DD`` on view,
  segmented year/month/day on update). Full date validity (e.g. Feb 30,
  leap-year rules) is enforced at the service layer by
  :mod:`src.shared.utils.date_utils`.
* **String preservation (no stripping)** — free-text string fields
  (names, addresses, government ID, etc.) are NOT whitespace-stripped.
  The COBOL ``PIC X(N)`` columns store fixed-width space-padded values;
  preserving the trailing spaces on the API wire keeps the round-trip
  read-modify-write idempotent against the Aurora PostgreSQL ``CHAR(N)``
  columns, which would otherwise right-pad on write and produce a
  "modified" diff on the very next read.
* **``ConfigDict(from_attributes=True)``** is applied to
  :class:`AccountViewResponse` (and therefore, via inheritance, to
  :class:`AccountUpdateResponse`) so the service layer may instantiate
  either response directly from a joined SQLAlchemy result-row object
  (e.g. a ``Row`` combining ``Account``, ``Customer``, and
  ``CardCrossReference`` attributes) without an intermediate ``dict``
  conversion. The request schema (:class:`AccountUpdateRequest`) does
  NOT include this config because request payloads always arrive as
  JSON-decoded dicts from the REST / GraphQL layer.
* **Optional message fields** — ``info_message`` and ``error_message``
  on :class:`AccountViewResponse` are typed :class:`~typing.Optional`
  (``None``-able) to allow the service layer to emit a response with
  no informational payload (the common "success" case). When populated,
  they map directly to the COBOL BMS ``INFOMSGI`` / ``ERRMSGI`` fields
  and are bounded at 45 and 78 characters respectively to preserve the
  BMS screen-row widths.
* **Optimistic concurrency** — the Account Update endpoint uses the
  ``account.version`` and ``customer.version`` SQLAlchemy columns for
  ``READ UPDATE`` / ``REWRITE``-style optimistic concurrency (mirroring
  the original CICS pattern in ``COACTUPC.cbl`` and the transactional
  rollback behavior it relied on). These version columns are handled
  entirely at the repository / service layer and are NOT part of the
  wire contract, so neither appears on :class:`AccountUpdateRequest`
  or :class:`AccountUpdateResponse`.
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length / max-digits / decimal-places
  constraints and :func:`~pydantic.field_validator` for business-rule
  enforcement (exact 11-digit ``account_id``, non-negative monetary
  values).
* **Python 3.11+** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI / Uvicorn container image).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (F-004, F-005)
AAP §0.4.1 — Refactored Structure Planning (``account_schema.py`` row)
AAP §0.4.3 — Design Pattern Applications (Transactional Outbox,
             Optimistic Concurrency)
AAP §0.5.1 — File-by-File Transformation Plan (``account_schema.py`` row)
AAP §0.7.1 — Refactoring-Specific Rules (preservation of dual-write and
             optimistic-concurrency behavior)
AAP §0.7.2 — Special Instructions (Financial Precision — ``Decimal``,
             never ``float``)
"""

from decimal import Decimal
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Private module constants — COBOL PIC-clause widths and domains.
# ---------------------------------------------------------------------------
# Keeping these as private module constants (leading underscore) keeps the
# public surface of the module minimal — only the three transport schemas
# are exported via ``__all__`` at the bottom of this file. The constants
# below are named after the COBOL PIC-clause field they derive from and
# are used throughout the ``Field(max_length=...)`` declarations below so
# that each constraint is traceable to its source copybook definition.
#
# Width references:
#   * COACTVW.CPY (Account View symbolic map)
#   * COACTUP.CPY (Account Update symbolic map)
#   * CVACT01Y.cpy (Account VSAM record layout)
#   * CVCUS01Y.cpy (Customer VSAM record layout)

# ACCTSIDI PIC 9(11) / PIC X(11) — 11-character account identifier.
# Matches CVACT01Y.cpy ACCT-ID PIC 9(11) and the ``account.acct_id``
# primary-key column width in Aurora PostgreSQL.
_ACCT_ID_LEN: int = 11

# ACSTNUMI PIC X(9) — 9-character customer identifier. Matches
# CVCUS01Y.cpy CUST-ID PIC 9(09) and the ``customer.cust_id`` primary-key
# column width.
_CUST_ID_LEN: int = 9

# ACSTTUSI / ACSPFLGI PIC X(1) — 1-character active-status flag /
# primary-cardholder indicator. Both BMS fields share the width; both
# map to corresponding single-character columns on the Account /
# Customer VSAM record layouts.
_FLAG_LEN: int = 1

# ADTOPENI / AEXPDTI / AREISDTI / ACSTDOBI PIC X(10) — 10-character
# composite date fields on the View screen (``CCYY-MM-DD`` layout).
# The Update screen uses three separate year / month / day fields
# instead (see ``_DATE_YEAR_LEN`` / ``_DATE_MM_DD_LEN`` below).
_DATE_LEN: int = 10

# OPNYEARI / EXPYEARI / RISYEARI / DOBYEARI PIC X(4) — 4-character
# calendar year (``CCYY``) component on the Update screen.
_DATE_YEAR_LEN: int = 4

# OPNMONI / OPNDAYI / EXPMONI / EXPDAYI / RISMONI / RISDAYI / DOBMONI /
# DOBDAYI PIC X(2) — 2-character month or day-of-month component on
# the Update screen.
_DATE_MM_DD_LEN: int = 2

# ACRDLIMI / AEXPDTI / ACSHLIMI / ACURBALI / ACRCYCRI / ACRCYDBI
# PIC X(15) — 15-character display width reserved on the BMS View
# screen for monetary values (e.g. a formatted ``"-1234567890.12"``
# including sign and decimal point). The underlying VSAM column is
# defined by CVACT01Y.cpy as ``PIC S9(10)V99`` (signed, 10 integer
# digits + 2 fractional digits = 12 decimal digits). Applied as
# ``max_digits=_MONEY_MAX_DIGITS`` / ``decimal_places=_MONEY_DECIMALS``
# on each monetary :class:`~pydantic.Field` — the ``max_length``
# constraint is NOT applied to :class:`~decimal.Decimal` fields.
_MONEY_DISPLAY_WIDTH: int = 15  # noqa: F841 (documentation constant)

# Max digits enforced by Pydantic v2 on each monetary Decimal field.
# 15 provides a comfortable margin over the COBOL 12-decimal-digit
# domain (``S9(10)V99``) while still constraining obvious overflow
# attempts at the schema layer. Per AAP §0.7.2 "Financial Precision",
# no floating-point arithmetic is permitted on financial values.
_MONEY_MAX_DIGITS: int = 15

# Exact fractional scale of every COBOL ``PIC S9(n)V99`` monetary field
# — 2 decimal places. Must match the Aurora PostgreSQL
# ``NUMERIC(15,2)`` column definition (see ``db/migrations/V1__schema.sql``).
_MONEY_DECIMALS: int = 2

# AADDGRPI PIC X(10) — 10-character disclosure-group identifier
# (matches ``account.group_id`` and DIS-ACCT-GROUP-ID in CVTRA02Y.cpy).
_GROUP_ID_LEN: int = 10

# ACSTSSNI PIC X(12) — 12-character composite SSN display on the View
# screen (``NNN-NN-NNNN`` formatted). The Update screen exposes three
# separate fields (``ACTSSN1I`` / ``ACTSSN2I`` / ``ACTSSN3I``) for
# field-by-field editing; see ``_SSN_PART_*_LEN`` below.
_SSN_DISPLAY_LEN: int = 12

# ACTSSN1I PIC X(3) / ACTSSN2I PIC X(2) / ACTSSN3I PIC X(4) —
# segmented SSN components on the Update screen.
_SSN_PART1_LEN: int = 3
_SSN_PART2_LEN: int = 2
_SSN_PART3_LEN: int = 4

# ACSTFCOI PIC X(3) — 3-character FICO credit-score display. The
# underlying ``customer.fico_score`` column is a 3-digit integer
# (``CUST-FICO-CREDIT-SCORE PIC 9(03)`` on CVCUS01Y.cpy), but the BMS
# symbolic map transmits it as a space-padded character field.
_FICO_LEN: int = 3

# ACSFNAMI / ACSMNAMI / ACSLNAMI PIC X(25) — 25-character first /
# middle / last name. Matches CVCUS01Y.cpy CUST-FIRST-NAME /
# CUST-MIDDLE-NAME / CUST-LAST-NAME PIC X(25).
_NAME_LEN: int = 25

# ACSADL1I / ACSADL2I PIC X(50) — 50-character address line 1 / 2.
# Matches CVCUS01Y.cpy CUST-ADDR-LINE-1 / CUST-ADDR-LINE-2 PIC X(50).
_ADDR_LINE_LEN: int = 50

# ACSCITYI PIC X(50) — 50-character city name. (CVCUS01Y.cpy has
# CUST-ADDR-LINE-3 PIC X(50) which the modern schema interprets as
# city — the BMS symbolic map explicitly labels this field "CITY".)
_CITY_LEN: int = 50

# ACSSTTEI PIC X(2) — 2-character US state code. Matches CVCUS01Y.cpy
# CUST-ADDR-STATE-CD PIC X(02).
_STATE_CD_LEN: int = 2

# ACSZIPCI PIC X(5) — 5-character ZIP code. The COBOL VSAM layout
# (``CUST-ADDR-ZIP PIC X(10)``) reserves 10 characters for a full
# ZIP+4 plus separator, but the BMS View / Update screen only exposes
# the 5-character base ZIP for display / editing.
_ZIP_LEN: int = 5

# ACSCTRYI PIC X(3) — 3-character ISO country code. Matches
# CVCUS01Y.cpy CUST-ADDR-COUNTRY-CD PIC X(03).
_COUNTRY_CD_LEN: int = 3

# ACSPHN1I / ACSPHN2I PIC X(13) — 13-character composite phone display
# on the View screen (``(AAA)BBB-CCCC`` formatted). The Update screen
# breaks this into three editable parts; see ``_PHONE_*_LEN`` below.
_PHONE_DISPLAY_LEN: int = 13

# ACSPH1AI / ACSPH1BI / ACSPH2AI / ACSPH2BI PIC X(3) —
# area / prefix segments of the phone number on the Update screen.
_PHONE_AREA_LEN: int = 3
_PHONE_PREFIX_LEN: int = 3

# ACSPH1CI / ACSPH2CI PIC X(4) — line (last 4 digits) segment of the
# phone number on the Update screen.
_PHONE_LINE_LEN: int = 4

# ACSGOVTI PIC X(20) — 20-character government ID (e.g. driver's
# license, passport number). Matches CVCUS01Y.cpy
# CUST-GOVT-ISSUED-ID PIC X(20).
_GOVT_ID_LEN: int = 20

# ACSEFTCI PIC X(10) — 10-character EFT (ACH) account identifier.
# Matches CVCUS01Y.cpy CUST-EFT-ACCOUNT-ID PIC X(10).
_EFT_ACCT_LEN: int = 10

# INFOMSGI PIC X(45) — 45-character informational-message row width
# reserved on the Account View BMS screen. (Note: other CardDemo
# screens use 40 or 80 — this constant is specific to COACTVW / COACTUP.)
_INFO_MSG_LEN: int = 45

# ERRMSGI PIC X(78) — 78-character error-message row width reserved on
# the Account View BMS screen, matching the standard full-row width
# used across most CardDemo list / detail screens.
_ERR_MSG_LEN: int = 78


# ---------------------------------------------------------------------------
# AccountViewResponse — Response body for GET /accounts/{account_id} (F-004)
# ---------------------------------------------------------------------------
class AccountViewResponse(BaseModel):
    """Read-only response body for the Account View endpoint (F-004).

    Corresponds one-to-one to the legacy COBOL BMS symbolic map
    ``CACTVWAI`` emitted by ``COACTVWC.cbl`` via ``EXEC CICS SEND MAP
    ('COACTVWA')``. The modernized service layer constructs instances
    of this schema from a 3-entity SQLAlchemy join across
    :class:`~src.shared.models.account.Account`,
    :class:`~src.shared.models.card_cross_reference.CardCrossReference`,
    and :class:`~src.shared.models.customer.Customer` — the
    ``CardCrossReference`` serving the same role as the legacy
    XREF-FILE (``CXACAIX``) alternate-index lookup.

    All 31 business fields documented in the BMS symbolic map are
    represented. BMS control bytes (length, attribute, flag — the ``L``,
    ``A``, ``F`` suffixes on the original symbolic map) and the BMS
    screen-chrome fields (``TRNNAMEI``, ``TITLE01I``, ``TITLE02I``,
    ``CURDATEI``, ``CURTIMEI``, ``PGMNAMEI``) are deliberately omitted
    from the JSON contract — they are UI-layer concerns from the 3270
    era that have no counterpart in a REST / GraphQL response.

    The ``info_message`` and ``error_message`` fields preserve the
    existing COBOL UX convention of surfacing a single advisory or
    error string on the response; these map to ``INFOMSGI`` and
    ``ERRMSGI`` on the BMS symbolic map. Either or both may be
    ``None`` when no message is being surfaced (the common "success"
    case). Callers that need structured error detail (error code,
    field-level errors) should rely on the HTTP status code and the
    FastAPI exception-handler middleware (``src.api.middleware.
    error_handler``) rather than on these message strings.

    Financial Precision
    -------------------
    The five monetary fields —
    :attr:`credit_limit`, :attr:`cash_credit_limit`,
    :attr:`current_balance`, :attr:`current_cycle_credit`, and
    :attr:`current_cycle_debit` — use :class:`decimal.Decimal` to
    preserve the exact ``PIC S9(10)V99`` semantics of the underlying
    CVACT01Y.cpy VSAM record. Each is constrained to
    ``max_digits=15, decimal_places=2`` on the wire. No floating-point
    arithmetic is permitted anywhere on these values; downstream
    callers must use ``Decimal`` throughout and round with
    :data:`decimal.ROUND_HALF_EVEN` per AAP §0.7.2.

    ORM Compatibility
    -----------------
    ``model_config = ConfigDict(from_attributes=True)`` enables the
    service layer to construct responses directly from a joined
    SQLAlchemy result-row object (e.g. ``AccountViewResponse.
    model_validate(row)`` where ``row`` exposes the required
    attributes either as a mapped object or a named tuple).
    """

    # ``from_attributes=True`` enables Pydantic to read fields via
    # attribute access (``obj.account_id``) as well as dict indexing
    # (``obj["account_id"]``). This is necessary because the service
    # layer passes SQLAlchemy joined-row objects — not plain dicts —
    # when constructing responses. Without this setting the response
    # builder would have to manually convert every ORM instance to a
    # dict before validation.
    model_config = ConfigDict(from_attributes=True)

    # -- Account identity and status ---------------------------------
    account_id: str = Field(
        ...,
        max_length=_ACCT_ID_LEN,
        description=(
            "11-character zero-padded account identifier. Maps to "
            "BMS field ACCTSIDI (PIC 9(11)) and VSAM key "
            "ACCT-ID (PIC 9(11))."
        ),
    )
    active_status: str = Field(
        ...,
        max_length=_FLAG_LEN,
        description=(
            "Single-character account-active indicator ('Y' or 'N'). "
            "Maps to BMS field ACSTTUSI (PIC X(1)) and VSAM field "
            "ACCT-ACTIVE-STATUS (PIC X(01))."
        ),
    )
    # -- Account dates -----------------------------------------------
    open_date: str = Field(
        ...,
        max_length=_DATE_LEN,
        description=(
            "Account open date, pre-formatted as CCYY-MM-DD. Maps to "
            "BMS field ADTOPENI (PIC X(10)) and VSAM field "
            "ACCT-OPEN-DATE (PIC X(10))."
        ),
    )
    # -- Monetary: credit limit --------------------------------------
    credit_limit: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Total credit limit assigned to the account. Maps to BMS "
            "field ACRDLIMI and VSAM field ACCT-CREDIT-LIMIT "
            "(PIC S9(10)V99). Decimal (not float) to preserve exact "
            "COBOL fixed-point semantics; NUMERIC(15,2) in Aurora."
        ),
    )
    expiration_date: str = Field(
        ...,
        max_length=_DATE_LEN,
        description=(
            "Account expiration date, pre-formatted as CCYY-MM-DD. "
            "Maps to BMS field AEXPDTI (PIC X(10)) and VSAM field "
            "ACCT-EXPIRAION-DATE (PIC X(10)) — note the intentional "
            "preservation of the legacy COBOL typo 'EXPIRAION'."
        ),
    )
    # -- Monetary: cash credit limit ---------------------------------
    cash_credit_limit: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Cash-advance sub-limit within the total credit limit. "
            "Maps to BMS field ACSHLIMI and VSAM field "
            "ACCT-CASH-CREDIT-LIMIT (PIC S9(10)V99)."
        ),
    )
    reissue_date: str = Field(
        ...,
        max_length=_DATE_LEN,
        description=(
            "Last account reissue date, pre-formatted as CCYY-MM-DD. "
            "Maps to BMS field AREISDTI (PIC X(10)) and VSAM field "
            "ACCT-REISSUE-DATE (PIC X(10))."
        ),
    )
    # -- Monetary: current balance and cycle totals ------------------
    current_balance: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Current outstanding balance on the account. Maps to BMS "
            "field ACURBALI and VSAM field ACCT-CURR-BAL "
            "(PIC S9(10)V99)."
        ),
    )
    current_cycle_credit: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Sum of credits (payments / refunds) posted in the "
            "current billing cycle. Maps to BMS field ACRCYCRI and "
            "VSAM field ACCT-CURR-CYC-CREDIT (PIC S9(10)V99)."
        ),
    )
    group_id: str = Field(
        ...,
        max_length=_GROUP_ID_LEN,
        description=(
            "Disclosure-group identifier that drives interest-rate "
            "lookup in INTCALC (Stage 2 of the batch pipeline). Maps "
            "to BMS field AADDGRPI (PIC X(10)) and VSAM field "
            "ACCT-GROUP-ID (PIC X(10))."
        ),
    )
    current_cycle_debit: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Sum of debits (purchases / cash advances / fees) posted "
            "in the current billing cycle. Maps to BMS field ACRCYDBI "
            "and VSAM field ACCT-CURR-CYC-DEBIT (PIC S9(10)V99)."
        ),
    )
    # -- Customer identity -------------------------------------------
    customer_id: str = Field(
        ...,
        max_length=_CUST_ID_LEN,
        description=(
            "9-character zero-padded customer identifier joined via "
            "the CardCrossReference table. Maps to BMS field "
            "ACSTNUMI (PIC X(9)) and VSAM key CUST-ID (PIC 9(09))."
        ),
    )
    customer_ssn: str = Field(
        ...,
        max_length=_SSN_DISPLAY_LEN,
        description=(
            "Composite customer SSN displayed as NNN-NN-NNNN (12 "
            "characters including hyphens). Maps to BMS field "
            "ACSTSSNI (PIC X(12)); derived from the 9-digit VSAM "
            "field CUST-SSN (PIC 9(09)) by the service layer."
        ),
    )
    customer_dob: str = Field(
        ...,
        max_length=_DATE_LEN,
        description=(
            "Customer date of birth, pre-formatted as CCYY-MM-DD. "
            "Maps to BMS field ACSTDOBI (PIC X(10)) and VSAM field "
            "CUST-DOB-YYYY-MM-DD (PIC X(10))."
        ),
    )
    customer_fico_score: str = Field(
        ...,
        max_length=_FICO_LEN,
        description=(
            "Customer FICO credit score, transmitted as a 3-character "
            "space-padded string. Maps to BMS field ACSTFCOI "
            "(PIC X(3)); derived from VSAM field "
            "CUST-FICO-CREDIT-SCORE (PIC 9(03))."
        ),
    )
    # -- Customer name (first / middle / last) -----------------------
    customer_first_name: str = Field(
        ...,
        max_length=_NAME_LEN,
        description=(
            "Customer first (given) name, space-padded to 25 "
            "characters. Maps to BMS field ACSFNAMI (PIC X(25)) and "
            "VSAM field CUST-FIRST-NAME (PIC X(25))."
        ),
    )
    customer_middle_name: str = Field(
        ...,
        max_length=_NAME_LEN,
        description=(
            "Customer middle name or initial, space-padded to 25 "
            "characters. Maps to BMS field ACSMNAMI (PIC X(25)) and "
            "VSAM field CUST-MIDDLE-NAME (PIC X(25))."
        ),
    )
    customer_last_name: str = Field(
        ...,
        max_length=_NAME_LEN,
        description=(
            "Customer last (family) name, space-padded to 25 "
            "characters. Maps to BMS field ACSLNAMI (PIC X(25)) and "
            "VSAM field CUST-LAST-NAME (PIC X(25))."
        ),
    )
    # -- Customer address (line 1 / state / line 2 / zip / city /
    #    country) — order matches the COBOL BMS screen layout, not a
    #    conventional western-world address order.
    customer_addr_line_1: str = Field(
        ...,
        max_length=_ADDR_LINE_LEN,
        description=(
            "Customer address line 1, space-padded to 50 characters. "
            "Maps to BMS field ACSADL1I (PIC X(50)) and VSAM field "
            "CUST-ADDR-LINE-1 (PIC X(50))."
        ),
    )
    customer_state_cd: str = Field(
        ...,
        max_length=_STATE_CD_LEN,
        description=(
            "2-character US state code (or territory abbreviation). "
            "Maps to BMS field ACSSTTEI (PIC X(2)) and VSAM field "
            "CUST-ADDR-STATE-CD (PIC X(02))."
        ),
    )
    customer_addr_line_2: str = Field(
        ...,
        max_length=_ADDR_LINE_LEN,
        description=(
            "Customer address line 2 (apt / suite / etc.), "
            "space-padded to 50 characters. Maps to BMS field "
            "ACSADL2I (PIC X(50)) and VSAM field CUST-ADDR-LINE-2 "
            "(PIC X(50))."
        ),
    )
    customer_zip: str = Field(
        ...,
        max_length=_ZIP_LEN,
        description=(
            "5-character base ZIP code. Maps to BMS field ACSZIPCI "
            "(PIC X(5)); derived from the wider VSAM field "
            "CUST-ADDR-ZIP (PIC X(10))."
        ),
    )
    customer_city: str = Field(
        ...,
        max_length=_CITY_LEN,
        description=(
            "Customer city name, space-padded to 50 characters. "
            "Maps to BMS field ACSCITYI (PIC X(50)) and VSAM field "
            "CUST-ADDR-LINE-3 (PIC X(50)) — the COBOL VSAM layout "
            "reuses the third address line for the city name."
        ),
    )
    customer_country_cd: str = Field(
        ...,
        max_length=_COUNTRY_CD_LEN,
        description=(
            "3-character ISO country code. Maps to BMS field "
            "ACSCTRYI (PIC X(3)) and VSAM field "
            "CUST-ADDR-COUNTRY-CD (PIC X(03))."
        ),
    )
    # -- Customer phones, government ID, EFT -------------------------
    customer_phone_1: str = Field(
        ...,
        max_length=_PHONE_DISPLAY_LEN,
        description=(
            "Composite primary phone displayed as (AAA)BBB-CCCC "
            "(13 characters including parentheses and hyphen). Maps "
            "to BMS field ACSPHN1I (PIC X(13)); derived from the "
            "15-character VSAM field CUST-PHONE-NUM-1 by the "
            "service layer."
        ),
    )
    customer_govt_id: str = Field(
        ...,
        max_length=_GOVT_ID_LEN,
        description=(
            "Government-issued ID (driver's license, passport, etc.), "
            "space-padded to 20 characters. Maps to BMS field "
            "ACSGOVTI (PIC X(20)) and VSAM field "
            "CUST-GOVT-ISSUED-ID (PIC X(20))."
        ),
    )
    customer_phone_2: str = Field(
        ...,
        max_length=_PHONE_DISPLAY_LEN,
        description=(
            "Composite secondary phone displayed as (AAA)BBB-CCCC "
            "(13 characters). Maps to BMS field ACSPHN2I "
            "(PIC X(13)); derived from VSAM field "
            "CUST-PHONE-NUM-2 by the service layer."
        ),
    )
    customer_eft_account_id: str = Field(
        ...,
        max_length=_EFT_ACCT_LEN,
        description=(
            "EFT (Automated Clearing House) account identifier used "
            "for auto-payment, 10 characters. Maps to BMS field "
            "ACSEFTCI (PIC X(10)) and VSAM field "
            "CUST-EFT-ACCOUNT-ID (PIC X(10))."
        ),
    )
    customer_pri_cardholder: str = Field(
        ...,
        max_length=_FLAG_LEN,
        description=(
            "Single-character primary-cardholder indicator ('Y' or "
            "'N'). Maps to BMS field ACSPFLGI (PIC X(1)) and VSAM "
            "field CUST-PRI-CARD-HOLDER-IND (PIC X(01))."
        ),
    )
    # -- Optional advisory / error messages --------------------------
    # ``Optional[str]`` (rather than ``str | None``) is used to match
    # the existing sibling-schema convention in card_schema.py /
    # bill_schema.py / transaction_schema.py. The inline ruff
    # suppression on the next line disables the ``pyupgrade`` rule
    # (UP045) that would otherwise prefer the ``X | None`` syntax — the
    # project deliberately sticks with ``Optional[...]`` across all
    # schema modules for clarity and consistency.
    info_message: Optional[str] = Field(  # noqa: UP045
        default=None,
        max_length=_INFO_MSG_LEN,
        description=(
            "Optional advisory message (e.g. 'Account retrieved "
            "successfully'). Maps to BMS field INFOMSGI (PIC X(45)). "
            "None when no advisory is being surfaced."
        ),
    )
    error_message: Optional[str] = Field(  # noqa: UP045
        default=None,
        max_length=_ERR_MSG_LEN,
        description=(
            "Optional error message surfaced on the response body. "
            "Maps to BMS field ERRMSGI (PIC X(78)). None when no "
            "error is being surfaced. Structured error detail is "
            "conveyed via HTTP status codes and the FastAPI "
            "exception-handler middleware."
        ),
    )

    # ---- Validators ------------------------------------------------
    @field_validator("account_id")
    @classmethod
    def _validate_account_id(cls, value: str) -> str:
        """Enforce the COBOL ``PIC 9(11)`` account-id domain.

        The legacy BMS definition is ``ACCTSIDI PIC 9(11)`` — a numeric
        field that accepts only the digits ``0``-``9``. Pydantic's
        ``max_length`` alone would accept e.g. ``"12345"`` (shorter
        than 11) or ``"ABC12345678"`` (non-numeric). This validator
        additionally enforces exactly 11 characters, all digits,
        matching the underlying Aurora PostgreSQL ``CHAR(11)`` primary
        key and the VSAM ACCT-ID ``PIC 9(11)`` layout.

        Parameters
        ----------
        value : str
            The raw ``account_id`` value supplied by the caller.

        Returns
        -------
        str
            The unmodified value if valid.

        Raises
        ------
        ValueError
            If ``value`` is not a string, is not exactly 11 characters
            long, or contains any non-digit character.
        """
        if not isinstance(value, str):
            raise ValueError("account_id must be a string")
        if len(value) != _ACCT_ID_LEN:
            raise ValueError(
                f"account_id must be exactly {_ACCT_ID_LEN} "
                f"characters (got {len(value)})"
            )
        if not value.isdigit():
            raise ValueError(
                "account_id must contain only ASCII digits 0-9 "
                "(matches COBOL ACCTSIDI PIC 9(11))"
            )
        return value

    @field_validator(
        "credit_limit",
        "cash_credit_limit",
        "current_balance",
        "current_cycle_credit",
        "current_cycle_debit",
    )
    @classmethod
    def _validate_monetary_non_negative(cls, value: Decimal) -> Decimal:
        """Enforce non-negative monetary values on the View response.

        Every monetary field returned by the Account View endpoint
        represents a balance or limit that is conceptually non-negative
        by CardDemo business rules. A negative credit limit is
        meaningless; a negative current-cycle credit or debit would
        violate the POSTTRAN posting invariants; and while raw COBOL
        ``PIC S9(10)V99`` fields technically allow a sign, the
        application-level domain for each of these five fields
        excludes negatives.

        Parameters
        ----------
        value : Decimal
            The monetary value supplied by the caller / service layer.

        Returns
        -------
        Decimal
            The unmodified value if valid.

        Raises
        ------
        ValueError
            If ``value`` is not a :class:`~decimal.Decimal` instance
            (guards against accidental :class:`float` assignment) or
            is negative.
        """
        # Block ``float`` and other numeric types explicitly — the AAP
        # §0.7.2 Financial Precision rule forbids any floating-point
        # arithmetic on monetary values. ``bool`` is a subclass of
        # ``int`` (not of ``Decimal``) so this check also catches the
        # unlikely ``True`` / ``False`` input case.
        if not isinstance(value, Decimal):
            raise ValueError(
                "monetary fields must be Decimal instances "
                "(float is not permitted per AAP §0.7.2 "
                "Financial Precision)"
            )
        if value < Decimal("0"):
            raise ValueError(
                "monetary fields on AccountViewResponse must be "
                "non-negative"
            )
        return value


# ---------------------------------------------------------------------------
# AccountUpdateRequest — Request body for PUT /accounts/{account_id} (F-005)
# ---------------------------------------------------------------------------
class AccountUpdateRequest(BaseModel):
    """Request body for the Account Update endpoint (F-005).

    Corresponds one-to-one to the legacy COBOL BMS symbolic map
    ``CACTUPAI`` received by ``COACTUPC.cbl`` via ``EXEC CICS RECEIVE
    MAP ('COACTUPA')``. The COACTUP BMS map differs from COACTVW in
    that compound fields (dates, SSN, phones) are captured as
    **segmented sub-fields** on the 3270 input screen — each segment
    is an independently-addressable BMS input field with its own PIC
    clause. The modernized request body preserves that segmentation
    on the wire so that callers migrating from the legacy 3270 flow
    have a direct one-to-one field-level mapping.

    **Date segmentation** — Open date, expiration date, reissue date,
    and customer date-of-birth are each expressed as three
    independent string fields (``_year``, ``_month``, ``_day``).
    Years are 4-character strings (``CCYY``); months and days are
    2-character strings. The service layer
    (``src.api.services.account_service``) is responsible for
    assembling these into ISO ``YYYY-MM-DD`` dates and validating
    the combined date via the shared date-utility module
    (``src.shared.utils.date_utils`` — ported from ``CSUTLDTC.cbl``).

    **SSN segmentation** — Customer SSN is captured as three
    sub-fields: ``customer_ssn_part1`` (area / 3 digits),
    ``customer_ssn_part2`` (group / 2 digits), and
    ``customer_ssn_part3`` (serial / 4 digits). The service layer
    concatenates these into the 9-digit ``CUST-SSN`` VSAM field.

    **Phone segmentation** — Both primary and secondary phones are
    captured as three sub-fields: area code (3 digits), prefix (3
    digits), and line number (4 digits). The service layer composes
    these into the legacy ``(NNN)NNN-NNNN`` display format.

    Validation Philosophy
    ---------------------
    This schema performs only *structural* (length, type, digit-only
    where relevant) validation. Business-rule and cross-field
    validation — verifying the account exists, checking the
    optimistic-concurrency version stamp, enforcing domain rules
    such as ``credit_limit >= current_balance`` — is performed by the
    service layer after structural validation succeeds. This mirrors
    the original COBOL arrangement in which the BMS layer performed
    only field-level input validation and the program logic performed
    cross-record validation before ``REWRITE``.

    Optional Semantics
    ------------------
    All 39 fields are declared as required (``...``) because the
    underlying BMS flow submits every sub-field on every UPDATE
    operation — a legacy CICS RECEIVE MAP always returns the full
    set of input fields regardless of which were actually modified.
    Callers that wish to express a "no change" intent for a given
    field must submit the existing value rather than omitting the
    field. This preserves bug-for-bug behavioral parity with the
    legacy ``COACTUPC.cbl`` READ UPDATE / REWRITE flow.
    """

    # -- Account identity and status ---------------------------------
    account_id: str = Field(
        ...,
        max_length=_ACCT_ID_LEN,
        description=(
            "11-character zero-padded account identifier. Maps to "
            "BMS field ACCTSIDI (PIC 9(11)). This is the URL path "
            "parameter's body counterpart; the service layer MUST "
            "verify that the body's account_id matches the URL path."
        ),
    )
    active_status: str = Field(
        ...,
        max_length=_FLAG_LEN,
        description=(
            "Account-active indicator ('Y' or 'N'). Maps to BMS "
            "field ACSTTUSI (PIC X(1))."
        ),
    )
    # -- Open date (segmented) ---------------------------------------
    open_date_year: str = Field(
        ...,
        max_length=_DATE_YEAR_LEN,
        description=(
            "Year segment of the account open date (CCYY, 4 digits). "
            "Maps to BMS field OPNYEARI (PIC X(4))."
        ),
    )
    open_date_month: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Month segment of the account open date (MM, 2 digits). "
            "Maps to BMS field OPNMONI (PIC X(2))."
        ),
    )
    open_date_day: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Day segment of the account open date (DD, 2 digits). "
            "Maps to BMS field OPNDAYI (PIC X(2))."
        ),
    )
    # -- Credit limit (Decimal) --------------------------------------
    credit_limit: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Total credit limit to assign. Maps to BMS field "
            "ACRDLIMI and VSAM field ACCT-CREDIT-LIMIT "
            "(PIC S9(10)V99). Decimal (not float) to preserve exact "
            "fixed-point semantics; stored as NUMERIC(15,2) in "
            "Aurora."
        ),
    )
    # -- Expiration date (segmented) ---------------------------------
    expiration_date_year: str = Field(
        ...,
        max_length=_DATE_YEAR_LEN,
        description=(
            "Year segment of the account expiration date (CCYY). "
            "Maps to BMS field EXPYEARI (PIC X(4))."
        ),
    )
    expiration_date_month: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Month segment of the account expiration date (MM). "
            "Maps to BMS field EXPMONI (PIC X(2))."
        ),
    )
    expiration_date_day: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Day segment of the account expiration date (DD). "
            "Maps to BMS field EXPDAYI (PIC X(2))."
        ),
    )
    # -- Cash credit limit (Decimal) ---------------------------------
    cash_credit_limit: Decimal = Field(
        ...,
        max_digits=_MONEY_MAX_DIGITS,
        decimal_places=_MONEY_DECIMALS,
        description=(
            "Cash-advance sub-limit. Maps to BMS field ACSHLIMI and "
            "VSAM field ACCT-CASH-CREDIT-LIMIT (PIC S9(10)V99)."
        ),
    )
    # -- Reissue date (segmented) ------------------------------------
    reissue_date_year: str = Field(
        ...,
        max_length=_DATE_YEAR_LEN,
        description=(
            "Year segment of the last reissue date (CCYY). Maps to "
            "BMS field RISYEARI (PIC X(4))."
        ),
    )
    reissue_date_month: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Month segment of the last reissue date (MM). Maps to "
            "BMS field RISMONI (PIC X(2))."
        ),
    )
    reissue_date_day: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Day segment of the last reissue date (DD). Maps to "
            "BMS field RISDAYI (PIC X(2))."
        ),
    )
    # -- Disclosure group --------------------------------------------
    group_id: str = Field(
        ...,
        max_length=_GROUP_ID_LEN,
        description=(
            "Disclosure-group identifier. Maps to BMS field AADDGRPI "
            "(PIC X(10)) and VSAM field ACCT-GROUP-ID (PIC X(10))."
        ),
    )
    # -- Customer SSN (segmented) ------------------------------------
    customer_ssn_part1: str = Field(
        ...,
        max_length=_SSN_PART1_LEN,
        description=(
            "SSN area number (first 3 digits). Maps to BMS field "
            "ACTSSN1I (PIC X(3))."
        ),
    )
    customer_ssn_part2: str = Field(
        ...,
        max_length=_SSN_PART2_LEN,
        description=(
            "SSN group number (middle 2 digits). Maps to BMS field "
            "ACTSSN2I (PIC X(2))."
        ),
    )
    customer_ssn_part3: str = Field(
        ...,
        max_length=_SSN_PART3_LEN,
        description=(
            "SSN serial number (last 4 digits). Maps to BMS field "
            "ACTSSN3I (PIC X(4))."
        ),
    )
    # -- Customer date of birth (segmented) --------------------------
    customer_dob_year: str = Field(
        ...,
        max_length=_DATE_YEAR_LEN,
        description=(
            "Year segment of the customer date of birth (CCYY). "
            "Maps to BMS field DOBYEARI (PIC X(4))."
        ),
    )
    customer_dob_month: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Month segment of the customer date of birth (MM). "
            "Maps to BMS field DOBMONI (PIC X(2))."
        ),
    )
    customer_dob_day: str = Field(
        ...,
        max_length=_DATE_MM_DD_LEN,
        description=(
            "Day segment of the customer date of birth (DD). Maps "
            "to BMS field DOBDAYI (PIC X(2))."
        ),
    )
    # -- FICO + names ------------------------------------------------
    customer_fico_score: str = Field(
        ...,
        max_length=_FICO_LEN,
        description=(
            "Customer FICO credit score (3-character numeric "
            "string). Maps to BMS field ACSTFCOI (PIC X(3))."
        ),
    )
    customer_first_name: str = Field(
        ...,
        max_length=_NAME_LEN,
        description=(
            "Customer first (given) name (up to 25 characters). "
            "Maps to BMS field ACSFNAMI (PIC X(25))."
        ),
    )
    customer_middle_name: str = Field(
        ...,
        max_length=_NAME_LEN,
        description=(
            "Customer middle name or initial (up to 25 characters). "
            "Maps to BMS field ACSMNAMI (PIC X(25))."
        ),
    )
    customer_last_name: str = Field(
        ...,
        max_length=_NAME_LEN,
        description=(
            "Customer last (family) name (up to 25 characters). "
            "Maps to BMS field ACSLNAMI (PIC X(25))."
        ),
    )
    # -- Customer address (BMS screen order: line 1, state,
    #    line 2, zip, city, country) --------------------------------
    customer_addr_line_1: str = Field(
        ...,
        max_length=_ADDR_LINE_LEN,
        description=(
            "Customer address line 1 (up to 50 characters). Maps to "
            "BMS field ACSADL1I (PIC X(50))."
        ),
    )
    customer_state_cd: str = Field(
        ...,
        max_length=_STATE_CD_LEN,
        description=(
            "2-character US state code or territory abbreviation. "
            "Maps to BMS field ACSSTTEI (PIC X(2))."
        ),
    )
    customer_addr_line_2: str = Field(
        ...,
        max_length=_ADDR_LINE_LEN,
        description=(
            "Customer address line 2 (up to 50 characters). Maps to "
            "BMS field ACSADL2I (PIC X(50))."
        ),
    )
    customer_zip: str = Field(
        ...,
        max_length=_ZIP_LEN,
        description=(
            "5-character base ZIP code. Maps to BMS field ACSZIPCI "
            "(PIC X(5))."
        ),
    )
    customer_city: str = Field(
        ...,
        max_length=_CITY_LEN,
        description=(
            "Customer city name (up to 50 characters). Maps to BMS "
            "field ACSCITYI (PIC X(50))."
        ),
    )
    customer_country_cd: str = Field(
        ...,
        max_length=_COUNTRY_CD_LEN,
        description=(
            "3-character ISO country code. Maps to BMS field "
            "ACSCTRYI (PIC X(3))."
        ),
    )
    # -- Primary phone (segmented) -----------------------------------
    customer_phone_1_area: str = Field(
        ...,
        max_length=_PHONE_AREA_LEN,
        description=(
            "Primary phone area code (3 digits). Maps to BMS field "
            "ACSPH1AI (PIC X(3))."
        ),
    )
    customer_phone_1_prefix: str = Field(
        ...,
        max_length=_PHONE_PREFIX_LEN,
        description=(
            "Primary phone prefix / exchange (3 digits). Maps to "
            "BMS field ACSPH1BI (PIC X(3))."
        ),
    )
    customer_phone_1_line: str = Field(
        ...,
        max_length=_PHONE_LINE_LEN,
        description=(
            "Primary phone line number (4 digits). Maps to BMS field "
            "ACSPH1CI (PIC X(4))."
        ),
    )
    # -- Government ID -----------------------------------------------
    customer_govt_id: str = Field(
        ...,
        max_length=_GOVT_ID_LEN,
        description=(
            "Government-issued ID (up to 20 characters). Maps to "
            "BMS field ACSGOVTI (PIC X(20))."
        ),
    )
    # -- Secondary phone (segmented) ---------------------------------
    customer_phone_2_area: str = Field(
        ...,
        max_length=_PHONE_AREA_LEN,
        description=(
            "Secondary phone area code (3 digits). Maps to BMS "
            "field ACSPH2AI (PIC X(3))."
        ),
    )
    customer_phone_2_prefix: str = Field(
        ...,
        max_length=_PHONE_PREFIX_LEN,
        description=(
            "Secondary phone prefix / exchange (3 digits). Maps to "
            "BMS field ACSPH2BI (PIC X(3))."
        ),
    )
    customer_phone_2_line: str = Field(
        ...,
        max_length=_PHONE_LINE_LEN,
        description=(
            "Secondary phone line number (4 digits). Maps to BMS "
            "field ACSPH2CI (PIC X(4))."
        ),
    )
    # -- EFT account + primary-cardholder flag -----------------------
    customer_eft_account_id: str = Field(
        ...,
        max_length=_EFT_ACCT_LEN,
        description=(
            "EFT (Automated Clearing House) account identifier (10 "
            "characters). Maps to BMS field ACSEFTCI (PIC X(10))."
        ),
    )
    customer_pri_cardholder: str = Field(
        ...,
        max_length=_FLAG_LEN,
        description=(
            "Primary-cardholder indicator ('Y' or 'N'). Maps to BMS "
            "field ACSPFLGI (PIC X(1))."
        ),
    )

    # ---- Validators ------------------------------------------------
    @field_validator("account_id")
    @classmethod
    def _validate_account_id(cls, value: str) -> str:
        """Enforce the COBOL ``PIC 9(11)`` account-id domain.

        Identical semantics to
        :meth:`AccountViewResponse._validate_account_id`: the legacy
        BMS definition is ``ACCTSIDI PIC 9(11)`` (exactly 11 ASCII
        digits). See that validator for the full rationale.

        Parameters
        ----------
        value : str
            The raw ``account_id`` value supplied by the caller.

        Returns
        -------
        str
            The unmodified value if valid.

        Raises
        ------
        ValueError
            If ``value`` is not a string, is not exactly 11 characters
            long, or contains any non-digit character.
        """
        if not isinstance(value, str):
            raise ValueError("account_id must be a string")
        if len(value) != _ACCT_ID_LEN:
            raise ValueError(
                f"account_id must be exactly {_ACCT_ID_LEN} "
                f"characters (got {len(value)})"
            )
        if not value.isdigit():
            raise ValueError(
                "account_id must contain only ASCII digits 0-9 "
                "(matches COBOL ACCTSIDI PIC 9(11))"
            )
        return value

    @field_validator("credit_limit", "cash_credit_limit")
    @classmethod
    def _validate_monetary_non_negative(cls, value: Decimal) -> Decimal:
        """Enforce non-negative monetary values on the update request.

        Both monetary fields accepted by the update request — the
        total credit limit and the cash-advance sub-limit — must be
        non-negative. A negative limit would be meaningless from a
        business perspective and would also violate the implicit
        ``current_balance <= credit_limit`` invariant enforced by the
        COBOL ``COACTUPC.cbl`` REWRITE logic.

        Parameters
        ----------
        value : Decimal
            The monetary value supplied by the caller.

        Returns
        -------
        Decimal
            The unmodified value if valid.

        Raises
        ------
        ValueError
            If ``value`` is not a :class:`~decimal.Decimal` instance
            or is negative.
        """
        if not isinstance(value, Decimal):
            raise ValueError(
                "monetary fields must be Decimal instances "
                "(float is not permitted per AAP §0.7.2 "
                "Financial Precision)"
            )
        if value < Decimal("0"):
            raise ValueError(
                "monetary fields on AccountUpdateRequest must be "
                "non-negative"
            )
        return value


# ---------------------------------------------------------------------------
# AccountUpdateResponse — Response body for PUT /accounts/{account_id} (F-005)
# ---------------------------------------------------------------------------
class AccountUpdateResponse(AccountViewResponse):
    """Response body for a successful Account Update operation (F-005).

    The Account Update endpoint returns the fully-refreshed account
    view after the REWRITE completes, mirroring the legacy
    ``COACTUPC.cbl`` flow which redisplays the updated record on the
    ``COACTUP`` BMS map after a successful ``EXEC CICS REWRITE``.
    The modernized response therefore has an identical shape to
    :class:`AccountViewResponse` — same 31 business fields, same two
    optional advisory / error message fields, same ORM-compatibility
    configuration, and same validators.

    This class is deliberately empty: it inherits all fields, the
    ``model_config = ConfigDict(from_attributes=True)`` setting, and
    both validators (``_validate_account_id``,
    ``_validate_monetary_non_negative``) from the parent class. The
    only reason to define it as a separate class (rather than aliasing
    ``AccountUpdateResponse = AccountViewResponse``) is to give the
    FastAPI OpenAPI generator a distinct schema name in the generated
    ``openapi.json`` — callers and client-library generators can then
    tell apart "this is what you get from GET" versus "this is what
    you get after a PUT" even though the two shapes happen to be
    identical today. If the two shapes ever diverge (e.g. PUT begins
    returning a version stamp or an audit-trail ID), the distinction
    is already in place and only this class needs to be extended.
    """

    # Deliberately empty — all fields, config, and validators are
    # inherited from AccountViewResponse. See class docstring for the
    # rationale behind using inheritance rather than aliasing.


__all__ = [
    "AccountViewResponse",
    "AccountUpdateRequest",
    "AccountUpdateResponse",
]
