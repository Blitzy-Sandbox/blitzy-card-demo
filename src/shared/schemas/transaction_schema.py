# ============================================================================
# Source: COBOL BMS maps COTRN00.CPY, COTRN01.CPY, COTRN02.CPY
#         + copybook CVTRA05Y.cpy (TRAN-RECORD 350-byte layout)
# ============================================================================
# Pydantic v2 schemas for Transaction List (F-009), Detail (F-010), Add (F-011)
#
# Mainframe-to-Cloud migration: the BMS symbolic-map input/output fields of
# the three CICS transaction screens are translated to a set of six Pydantic
# request/response models that drive the FastAPI REST endpoints under
# ``/transactions`` and their Strawberry GraphQL counterparts.
#
# Replaces:
#   * BMS symbolic-map input/output copybooks (``app/cpy-bms``):
#       - COTRN00.CPY — Transaction List screen (F-009)
#           TRNIDINI PIC X(16) (filter)          -> TransactionListRequest.tran_id
#           PAGENUMI PIC X(08)                   -> TransactionListRequest.page
#           TRNIDnnI PIC X(16) (rows 01-10)      -> TransactionListItem.tran_id
#           TDATEnnI PIC X(08)                   -> TransactionListItem.tran_date
#           TDESCnnI PIC X(26)                   -> TransactionListItem.description
#           TAMTnnnI PIC X(12)                   -> TransactionListItem.amount
#           ERRMSGI  PIC X(78)                   -> TransactionListResponse.message
#       - COTRN01.CPY — Transaction Detail screen (F-010) - 15 fields
#           TRNIDINI PIC X(16) (search input)    -> TransactionDetailResponse.tran_id_input
#           TRNIDI   PIC X(16) (echo)            -> TransactionDetailResponse.tran_id
#           CARDNUMI PIC X(16)                   -> TransactionDetailResponse.card_num
#           TTYPCDI  PIC X(02)                   -> TransactionDetailResponse.tran_type_cd
#           TCATCDI  PIC X(04)                   -> TransactionDetailResponse.tran_cat_cd
#           TRNSRCI  PIC X(10)                   -> TransactionDetailResponse.tran_source
#           TDESCI   PIC X(60)                   -> TransactionDetailResponse.description
#           TRNAMTI  PIC X(12)                   -> TransactionDetailResponse.amount
#           TORIGDTI PIC X(10)                   -> TransactionDetailResponse.orig_date
#           TPROCDTI PIC X(10)                   -> TransactionDetailResponse.proc_date
#           MIDI     PIC X(09)                   -> TransactionDetailResponse.merchant_id
#           MNAMEI   PIC X(30)                   -> TransactionDetailResponse.merchant_name
#           MCITYI   PIC X(25)                   -> TransactionDetailResponse.merchant_city
#           MZIPI    PIC X(10)                   -> TransactionDetailResponse.merchant_zip
#           ERRMSGI  PIC X(78)                   -> TransactionDetailResponse.message
#       - COTRN02.CPY — Transaction Add screen (F-011)
#           ACTIDINI PIC X(11)                   -> TransactionAddRequest.acct_id
#           CARDNINI PIC X(16)                   -> TransactionAddRequest.card_num
#           TTYPCDI  PIC X(02)                   -> TransactionAddRequest.tran_type_cd
#           TCATCDI  PIC X(04)                   -> TransactionAddRequest.tran_cat_cd
#           TRNSRCI  PIC X(10)                   -> TransactionAddRequest.tran_source
#           TDESCI   PIC X(60)                   -> TransactionAddRequest.description
#           TRNAMTI  PIC X(12)                   -> TransactionAddRequest.amount
#           TORIGDTI PIC X(10)                   -> TransactionAddRequest.orig_date
#           TPROCDTI PIC X(10)                   -> TransactionAddRequest.proc_date
#           MIDI     PIC X(09)                   -> TransactionAddRequest.merchant_id
#           MNAMEI   PIC X(30)                   -> TransactionAddRequest.merchant_name
#           MCITYI   PIC X(25)                   -> TransactionAddRequest.merchant_city
#           MZIPI    PIC X(10)                   -> TransactionAddRequest.merchant_zip
#           CONFIRMI PIC X(01)                   -> TransactionAddResponse.confirm
#           ERRMSGI  PIC X(78)                   -> TransactionAddResponse.message
#   * CVTRA05Y.cpy TRAN-RECORD 350-byte layout — reference only. The
#     underlying DB column widths in ``src/shared/models/transaction.py``
#     derive from that copybook; the API contract widths derive from the
#     BMS maps above (both agree on the key fields — e.g. TRAN-ID
#     PIC X(16) in CVTRA05Y equals TRNIDI PIC X(16) in COTRN01.CPY).
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
"""Pydantic v2 schemas for the CardDemo Transaction APIs (F-009, F-010, F-011).

Converts the BMS symbolic-map copybooks for the three Transaction screens
(``app/cpy-bms/COTRN00.CPY``, ``app/cpy-bms/COTRN01.CPY``,
``app/cpy-bms/COTRN02.CPY``) into six transport schemas that drive the
Transaction REST and GraphQL endpoints:

* :class:`TransactionListRequest` — query parameters for
  ``GET /transactions`` (filter by ``tran_id`` plus pagination);
* :class:`TransactionListItem` — single row of the 10-per-page list
  returned by ``GET /transactions`` (mirrors the repeated
  ``TRNIDnn``/``TDATEnn``/``TDESCnn``/``TAMTnnn`` row groups in
  COTRN00.CPY);
* :class:`TransactionListResponse` — paged envelope around a list of
  :class:`TransactionListItem` objects, plus the ``ERRMSGI`` message
  placeholder;
* :class:`TransactionDetailResponse` — the full 15-field detail view
  returned by ``GET /transactions/{tran_id}`` (F-010);
* :class:`TransactionAddRequest` — the 13-field body of
  ``POST /transactions`` (F-011) including required identifiers
  (``acct_id``, ``card_num``, ``tran_type_cd``, etc.) and optional
  merchant attributes;
* :class:`TransactionAddResponse` — the confirmation envelope returned
  by ``POST /transactions`` with the server-assigned ``tran_id``
  (auto-generated per F-011) and the confirmation flag.

BMS → Python Field Mapping (Summary)
------------------------------------
============================  ==========================  =================================
BMS / COBOL Field             Schema                      Python Field
============================  ==========================  =================================
COTRN00 TRNIDINI X(16)        TransactionListRequest      ``tran_id``
COTRN00 PAGENUMI X(08)        TransactionListRequest      ``page``
COTRN00 TRNIDnnI X(16)        TransactionListItem         ``tran_id``
COTRN00 TDATEnnI X(08)        TransactionListItem         ``tran_date``
COTRN00 TDESCnnI X(26)        TransactionListItem         ``description``
COTRN00 TAMTnnnI X(12)        TransactionListItem         ``amount`` (Decimal)
COTRN00 ERRMSGI X(78)         TransactionListResponse     ``message``
COTRN01 TRNIDINI X(16)        TransactionDetailResponse   ``tran_id_input``
COTRN01 TRNIDI   X(16)        TransactionDetailResponse   ``tran_id``
COTRN01 CARDNUMI X(16)        TransactionDetailResponse   ``card_num``
COTRN01 TTYPCDI  X(02)        TransactionDetailResponse   ``tran_type_cd``
COTRN01 TCATCDI  X(04)        TransactionDetailResponse   ``tran_cat_cd``
COTRN01 TRNSRCI  X(10)        TransactionDetailResponse   ``tran_source``
COTRN01 TDESCI   X(60)        TransactionDetailResponse   ``description``
COTRN01 TRNAMTI  X(12)        TransactionDetailResponse   ``amount`` (Decimal)
COTRN01 TORIGDTI X(10)        TransactionDetailResponse   ``orig_date``
COTRN01 TPROCDTI X(10)        TransactionDetailResponse   ``proc_date``
COTRN01 MIDI     X(09)        TransactionDetailResponse   ``merchant_id``
COTRN01 MNAMEI   X(30)        TransactionDetailResponse   ``merchant_name``
COTRN01 MCITYI   X(25)        TransactionDetailResponse   ``merchant_city``
COTRN01 MZIPI    X(10)        TransactionDetailResponse   ``merchant_zip``
COTRN01 ERRMSGI  X(78)        TransactionDetailResponse   ``message``
COTRN02 ACTIDINI X(11)        TransactionAddRequest       ``acct_id``
COTRN02 CARDNINI X(16)        TransactionAddRequest       ``card_num``
COTRN02 (shared with COTRN01) TransactionAddRequest       ``tran_type_cd`` ... ``merchant_zip``
COTRN02 CONFIRMI X(01)        TransactionAddResponse      ``confirm``
COTRN02 ERRMSGI  X(78)        TransactionAddResponse      ``message``
============================  ==========================  =================================

Design Notes
------------
* **Financial precision** — every monetary ``amount`` field across ALL
  schemas uses :class:`decimal.Decimal`, NEVER :class:`float`. This
  preserves the exact COBOL ``PIC S9(n)V99`` semantics required by
  AAP §0.7.2 ("Financial Precision") and prevents IEEE-754
  representation errors on transaction amounts. Any arithmetic
  performed on these values in the service layer must use
  :data:`decimal.ROUND_HALF_EVEN` (banker's rounding, mirroring COBOL
  ``ROUNDED``).
* **Identifiers as strings** — ``tran_id``, ``acct_id``, and
  ``card_num`` are typed as :class:`str` (not :class:`int`) to preserve
  the fixed-width leading-zero representation from the COBOL
  ``PIC X(n)`` fields. Stripping leading zeros would break the key
  lookups against the Aurora PostgreSQL primary-key columns.
* **Max-length constraints** match the COBOL PIC X(N) sizes exactly
  (e.g. ``Field(..., max_length=16)`` for a ``PIC X(16)`` field). This
  mirrors the DB column widths declared in ``V1__schema.sql`` and
  ensures the service layer never attempts to persist an over-long
  string that would be rejected by PostgreSQL.
* **Page size default** — :class:`TransactionListRequest` defaults to
  ``page_size = 10`` to match the 10 repeated row groups (01 through
  10) on the original COTRN00 BMS screen (see F-009).
* **Optional merchant fields** on :class:`TransactionAddRequest` —
  merchant attributes (``merchant_id``, ``merchant_name``,
  ``merchant_city``, ``merchant_zip``) and ``proc_date`` are optional
  on the add request because the underlying COBOL program COTRN02C
  tolerates blank merchant fields and auto-populates ``TRAN-PROC-TS``
  from the current date/time at insert (see AAP §0.2.3 for the COTRN02C
  auto-ID + xref resolution behavior).
* **Positive-amount constraint** — :class:`TransactionAddRequest`
  enforces ``amount > 0`` at the schema layer, rejecting zero and
  negative values before they reach the service layer. CardDemo
  transaction semantics require a strictly positive amount; the sign
  of the balance delta is derived from ``tran_type_cd`` in the
  business logic.
* **Numeric identifier constraints** — ``acct_id`` (11 chars),
  ``card_num`` (exactly 16 chars), and ``tran_type_cd`` (exactly 2
  chars) are validated for character-class conformance in
  :class:`TransactionAddRequest`. ``card_num`` must be all-digits per
  the COBOL ``CARD-NUM PIC X(16)`` domain (16-digit PAN); ``acct_id``
  must be all-digits matching the COBOL ``PIC 9(11)`` domain on the
  underlying ``CVACT01Y.cpy`` record layout.
* **``ConfigDict(from_attributes=True)``** is applied to every
  response/list-item schema (:class:`TransactionListItem`,
  :class:`TransactionListResponse`, :class:`TransactionDetailResponse`,
  :class:`TransactionAddResponse`) so the service layer may instantiate
  them directly from SQLAlchemy ORM rows (e.g. an ``src.shared.models
  .transaction.Transaction`` instance) without an intermediate
  ``dict`` conversion. The two request schemas
  (:class:`TransactionListRequest`, :class:`TransactionAddRequest`) do
  NOT enable ORM mode because request payloads always arrive as
  JSON-decoded dicts from the REST / GraphQL layer.
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length/default constraints and
  :func:`~pydantic.field_validator` for business-rule enforcement.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI/Uvicorn container image).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (F-009, F-010, F-011)
AAP §0.4.1 — Refactored Structure Planning (``transaction_schema.py`` row)
AAP §0.5.1 — File-by-File Transformation Plan (``transaction_schema.py``)
AAP §0.7.1 — Refactoring-Specific Rules (functional parity)
AAP §0.7.2 — Special Instructions (Financial Precision)
"""

from decimal import Decimal
from typing import List, Optional  # noqa: UP035  # schema requires `typing.List` / `typing.Optional`

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Private module constants — COBOL PIC-clause widths from the BMS maps
# ---------------------------------------------------------------------------
# Leading-underscore constants keep the public surface of the module
# minimal — only the six transport schemas are exported via ``__all__``
# at the bottom of this file. The constants below are named after the
# COBOL PIC X(n) field they derive from and are used in
# ``Field(max_length=...)`` declarations throughout the schemas.

# TRNIDI / TRNIDINI / TRNIDnnI / CARDNUMI / CARDNINI PIC X(16) —
# 16-character fixed-width transaction ID or card PAN. Preserved as a
# string in Python to retain leading zeros; 16 == BMS layout width for
# both the transaction ID surrogate key and the 16-digit card number.
_TRAN_ID_MAX_LEN: int = 16
_CARD_NUM_MAX_LEN: int = 16

# ACTIDINI PIC X(11) — 11-character account ID (COTRN02 add-transaction
# request). Matches the ``account.acct_id`` PK width in Aurora
# PostgreSQL and the ACCT-ID PIC 9(11) domain on CVACT01Y.cpy.
_ACCT_ID_MAX_LEN: int = 11

# TTYPCDI PIC X(02) — 2-character transaction type code
# (e.g. '01'=debit, '02'=credit — see CVTRA03Y.cpy TRAN-TYPE).
_TRAN_TYPE_CD_LEN: int = 2

# TCATCDI PIC X(04) — 4-character transaction category code
# (see CVTRA04Y.cpy).
_TRAN_CAT_CD_MAX_LEN: int = 4

# TRNSRCI PIC X(10) — 10-character transaction source descriptor.
_TRAN_SOURCE_MAX_LEN: int = 10

# TDESCnnI PIC X(26) — list-row description width (short truncated form).
_LIST_DESC_MAX_LEN: int = 26

# TDESCI PIC X(60) — full description width (COTRN01 detail, COTRN02 add).
_DESC_MAX_LEN: int = 60

# TAMTnnnI / TRNAMTI PIC X(12) — 12-character amount display width. The
# underlying value is a Decimal (not a string); the BMS layout reserved
# 12 display characters for the formatted amount. The max_length
# constraint is NOT applied to Decimal fields — this constant is
# retained for documentation/traceability only.
_AMOUNT_DISPLAY_WIDTH: int = 12  # noqa: F841 (documentation constant)

# TDATEnnI PIC X(08) — 8-character date display (CCYYMMDD) on the list
# row. The underlying format is ``YYYYMMDD`` with no separator.
_LIST_DATE_LEN: int = 8

# TORIGDTI / TPROCDTI PIC X(10) — 10-character date display on the
# Detail and Add screens. Supports ``YYYY-MM-DD`` (ISO 8601) or
# ``CCYYMMDD`` with a trailing space — the API accepts either form and
# normalizes at the service layer.
_DATE_MAX_LEN: int = 10

# MIDI PIC X(09) — 9-character merchant ID.
_MERCHANT_ID_MAX_LEN: int = 9

# MNAMEI PIC X(30) — 30-character merchant name.
_MERCHANT_NAME_MAX_LEN: int = 30

# MCITYI PIC X(25) — 25-character merchant city.
_MERCHANT_CITY_MAX_LEN: int = 25

# MZIPI PIC X(10) — 10-character merchant ZIP.
_MERCHANT_ZIP_MAX_LEN: int = 10

# CONFIRMI PIC X(01) — 1-character confirmation indicator.
_CONFIRM_MAX_LEN: int = 1

# ERRMSGI PIC X(78) — 78-character info/error message (consistent
# across every CardDemo BMS screen).
_ERRMSG_MAX_LEN: int = 78

# Pagination bounds — the original COBOL list screen hard-coded 10 rows
# per page (the 10 repeated row groups on COTRN00). The modern API
# preserves 10 as the default but permits callers to request any
# positive size up to 100 to support dashboards / exports.
_DEFAULT_PAGE_SIZE: int = 10
_MAX_PAGE_SIZE: int = 100


# ---------------------------------------------------------------------------
# TransactionListRequest — query parameters for ``GET /transactions``
# ---------------------------------------------------------------------------
class TransactionListRequest(BaseModel):
    """Query parameters for the paginated transaction list (F-009).

    Replaces the incoming BMS input fields on the COTRN00 Transaction
    List screen that were previously received via CICS RECEIVE MAP
    ('COTRN0A') in ``COTRN00C.cbl``. The original COBOL program accepted
    two pieces of client input on each refresh:

    * a 16-character ``TRNIDINI`` transaction-ID filter that, when
      non-blank, restricts the result set to the matching transaction;
    * an 8-character ``PAGENUMI`` page-number indicator used to
      navigate forward / backward through the 10-row screens.

    The modernized API exposes both as HTTP query parameters on
    ``GET /transactions``, plus an additional ``page_size`` parameter
    that defaults to the COBOL screen's 10-rows-per-page behavior but
    allows callers to request larger pages for dashboard / export use
    cases.

    Attributes
    ----------
    tran_id : Optional[str]
        Optional filter — when provided, restrict the list to the
        transaction with this ID. 16-character fixed-width string
        (leading zeros preserved). Max 16 characters (COBOL
        ``PIC X(16)`` constraint from the original ``TRNIDINI`` field
        on COTRN00.CPY). ``None`` means "return all matching
        transactions" (paginated).
    page : int
        1-based page number. Defaults to ``1``. Must be >= 1. Mirrors
        the original ``PAGENUMI`` field on COTRN00.CPY which was
        populated by the forward/backward navigation keys (``PF7``,
        ``PF8``) in the CICS program.
    page_size : int
        Number of rows per page. Defaults to ``10`` to preserve the
        10-repeated-row layout of the original COTRN00 BMS screen.
        Bounded to ``[1, 100]`` — larger pages would require re-design
        of the list envelope.

    Raises
    ------
    pydantic.ValidationError
        * When ``tran_id`` is longer than 16 characters.
        * When ``page`` is less than 1.
        * When ``page_size`` is less than 1 or greater than 100.
    """

    # Request schemas do NOT set ``from_attributes=True`` because
    # requests always arrive as JSON-decoded dicts (never SQLAlchemy
    # objects) from the REST / GraphQL layer.

    tran_id: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_TRAN_ID_MAX_LEN,
        description=("Optional transaction ID filter — max 16 chars. Maps to COTRN00 TRNIDINI PIC X(16)."),
    )
    page: int = Field(
        default=1,
        ge=1,
        description=("1-based page number (defaults to 1). Maps to COTRN00 PAGENUMI PIC X(08)."),
    )
    page_size: int = Field(
        default=_DEFAULT_PAGE_SIZE,
        ge=1,
        le=_MAX_PAGE_SIZE,
        description=(
            "Rows per page (defaults to 10 — matches the 10 repeated row groups on COTRN00.CPY). Bounded to [1, 100]."
        ),
    )

    # ---------------------------------------------------------------
    # Field-level validators
    # ---------------------------------------------------------------
    @field_validator("tran_id")
    @classmethod
    def _validate_tran_id_filter(
        cls,
        value: Optional[str],  # noqa: UP045  # schema requires `typing.Optional`
    ) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """Normalize blank ``tran_id`` filters to ``None``.

        The original COBOL program treated a blank ``TRNIDINI`` field
        identically to "no filter supplied". This validator converts
        empty strings and whitespace-only strings to ``None`` so the
        service layer can rely on a single predicate
        (``tran_id is None``) to decide between the filtered and
        unfiltered query paths.

        Parameters
        ----------
        value
            Candidate ``tran_id`` from the query string, or ``None``.

        Returns
        -------
        Optional[str]
            ``None`` when ``value`` is ``None``, empty, or
            whitespace-only; otherwise the original ``value``
            unchanged (with leading zeros preserved).

        Raises
        ------
        ValueError
            When ``value`` is not ``None`` and not a string.
        """
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError(f"tran_id must be a string or None; got {type(value).__name__}")
        # Normalize blank / whitespace-only input to None (matches the
        # COBOL screen's behavior where a blank TRNIDINI was ignored).
        if not value.strip():
            return None
        # Pydantic's ``max_length`` already enforces the 16-char limit.
        return value


# ---------------------------------------------------------------------------
# TransactionListItem — one repeated row from COTRN00 (rows 01-10)
# ---------------------------------------------------------------------------
class TransactionListItem(BaseModel):
    """A single transaction row within the paginated list (F-009).

    Mirrors the structure of the 10 repeated row groups (suffix ``01``
    through ``10``) on the COTRN00 Transaction List BMS screen. Each
    row on the original 3270 terminal screen displayed four columns:

    ======================  ================  ===========
    BMS Field (COTRN00)     Python Field      Width
    ======================  ================  ===========
    TRNIDnnI   PIC X(16)    ``tran_id``       16 chars
    TDATEnnI   PIC X(08)    ``tran_date``      8 chars
    TDESCnnI   PIC X(26)    ``description``   26 chars
    TAMTnnnI   PIC X(12)    ``amount``        Decimal
    ======================  ================  ===========

    The API emits an array of these items in
    :class:`TransactionListResponse.transactions`, so the number of
    populated rows equals ``min(page_size, total_count - offset)``
    rather than always being exactly 10.

    Attributes
    ----------
    tran_id : str
        16-character transaction identifier — primary key from the
        ``transaction.tran_id`` column. Preserved as a string to
        retain leading zeros (CVTRA05Y.cpy TRAN-ID PIC X(16)).
    tran_date : str
        Transaction origination date as ``YYYYMMDD`` (8 characters, no
        separator). Derived from the first 8 characters of
        ``transaction.orig_ts`` (``TRAN-ORIG-TS`` PIC X(26)).
    description : str
        Short (truncated) transaction description — max 26 characters.
        On the COBOL screen this was the left-truncation of the full
        60-character ``TRAN-DESC`` to fit the list-row layout; the
        modern API emits the same 26-char truncation at the service
        layer to preserve screen-level parity.
    amount : Decimal
        Transaction amount — Decimal (never float) for COBOL
        ``PIC S9(n)V99`` precision. Maps to the ``transaction.tran_amt``
        column (see ``CVTRA05Y.cpy`` TRAN-AMT PIC S9(09)V99).

    Raises
    ------
    pydantic.ValidationError
        * When ``tran_id`` is longer than 16 characters.
        * When ``tran_date`` is longer than 8 characters.
        * When ``description`` is longer than 26 characters.
    """

    # Enables direct construction from SQLAlchemy ORM rows (e.g.
    # ``TransactionListItem.model_validate(txn_row)``), which the
    # service layer uses to assemble the list efficiently without
    # building an intermediate dict for each row.
    model_config = ConfigDict(from_attributes=True)

    tran_id: str = Field(
        ...,
        max_length=_TRAN_ID_MAX_LEN,
        description=("16-char transaction ID (primary key). Maps to COTRN00 TRNIDnnI PIC X(16)."),
    )
    tran_date: str = Field(
        ...,
        max_length=_LIST_DATE_LEN,
        description=(
            "Transaction date (CCYYMMDD, 8 chars, no separator). Maps "
            "to COTRN00 TDATEnnI PIC X(08) — derived from the first "
            "8 chars of transaction.orig_ts."
        ),
    )
    description: str = Field(
        ...,
        max_length=_LIST_DESC_MAX_LEN,
        description=(
            "Short transaction description (truncated to 26 chars for list view). Maps to COTRN00 TDESCnnI PIC X(26)."
        ),
    )
    amount: Decimal = Field(
        ...,
        description=(
            "Transaction amount — Decimal (never float) for COBOL "
            "PIC S9(n)V99 parity. Maps to COTRN00 TAMTnnnI PIC X(12) "
            "and underlying transaction.tran_amt column."
        ),
    )


# ---------------------------------------------------------------------------
# TransactionListResponse — paged envelope around TransactionListItem
# ---------------------------------------------------------------------------
class TransactionListResponse(BaseModel):
    """Response envelope for ``GET /transactions`` (F-009).

    Replaces the CICS ``SEND MAP ('COTRN0A')`` screen refresh that
    terminated a page-navigation transaction in ``COTRN00C.cbl``. The
    original BMS screen returned:

    * ten :class:`TransactionListItem`-shaped row groups (suffix ``01``
      through ``10``), of which the unused rows were blank-filled;
    * the current ``PAGENUMO`` page number (echoed from the request);
    * the standard 78-character ``ERRMSGO`` info/error message row.

    The modern API envelope swaps the fixed 10-row array for a
    variable-length :class:`List`, adds an unambiguous ``total_count``
    so the client can compute the total number of pages without a
    round-trip, and promotes the ``ERRMSGO`` message to an optional
    string field.

    Attributes
    ----------
    transactions : List[TransactionListItem]
        The list of rows returned on the current page. Empty list on a
        zero-result page (e.g. when the ``tran_id`` filter matches no
        transactions). Max length equals the
        :attr:`TransactionListRequest.page_size` supplied on the
        request (defaults to 10 — preserving the 10-repeated-row
        COBOL screen layout).
    page : int
        1-based current page number. Echoes the request's ``page``
        field. Maps to COTRN00 ``PAGENUMO`` PIC X(08).
    total_count : int
        Total number of transactions matching the request filter,
        across ALL pages (i.e. the unpaged cardinality). Enables the
        client to compute ``total_pages = ceil(total_count /
        page_size)`` without a second call. Non-negative integer.
        This field has no direct analogue in the original BMS layout
        (the COBOL screen simply displayed a "More..." indicator
        instead of a count); it's a new-to-modern addition that
        matches common REST-pagination conventions.
    message : Optional[str]
        Optional info/error message — up to 78 characters. ``None``
        when the request succeeded and no remarks are required;
        populated on error conditions (e.g. an invalid ``tran_id``
        filter). Maps directly to COTRN00 ``ERRMSGO`` PIC X(78).

    Raises
    ------
    pydantic.ValidationError
        * When ``message`` exceeds 78 characters.
        * When ``page`` is negative.
        * When ``total_count`` is negative.
    """

    # Enables the service layer to construct this envelope directly
    # from an object with matching attribute names (e.g. a lightweight
    # ``PagedResult`` dataclass populated from a SQLAlchemy query
    # result), without an intermediate dict conversion.
    model_config = ConfigDict(from_attributes=True)

    transactions: List[TransactionListItem] = Field(  # noqa: UP006  # schema requires `typing.List`
        ...,
        description=(
            "List of transactions on the current page (max page_size "
            "items; defaults to 10 to match COTRN00's 10-row layout)."
        ),
    )
    page: int = Field(
        ...,
        ge=1,
        description=("1-based current page number. Echoes the request page. Maps to COTRN00 PAGENUMO PIC X(08)."),
    )
    total_count: int = Field(
        ...,
        ge=0,
        description=(
            "Total transactions matching the filter across all pages "
            "(unpaged cardinality). Enables client-side total-pages "
            "computation without a second call."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=("Optional info/error message, max 78 chars. Maps to COTRN00 ERRMSGO PIC X(78)."),
    )


# ---------------------------------------------------------------------------
# TransactionDetailResponse — full detail view from COTRN01 (F-010)
# ---------------------------------------------------------------------------
class TransactionDetailResponse(BaseModel):
    """Full transaction detail view returned by ``GET /transactions/{tran_id}``.

    Replaces the CICS ``SEND MAP ('COTRN1A')`` screen refresh in
    ``COTRN01C.cbl`` which displayed the full attribute set of a single
    transaction row. The response contains every field from the
    COTRN01.CPY symbolic map:

    * the search-input field ``tran_id_input`` (echoed from the
      request — corresponds to the user-entered ``TRNIDINI`` field
      on the BMS screen);
    * the twelve display fields that identified the transaction
      (``tran_id``, ``card_num``, ``tran_type_cd``, ``tran_cat_cd``,
      ``tran_source``, ``description``, ``amount``, ``orig_date``,
      ``proc_date``, ``merchant_id``, ``merchant_name``,
      ``merchant_city``, ``merchant_zip``);
    * the standard ``ERRMSGI``/``ERRMSGO`` ``message`` field.

    All fifteen fields are declared in the order the COTRN01 BMS map
    lays them out, making field-by-field traceability to the original
    screen straightforward.

    Attributes
    ----------
    tran_id_input : str
        Search-input transaction identifier echoed from the request.
        Distinct from ``tran_id`` so the client can display "you
        searched for X, found Y" even when the service performed a
        normalization (e.g. zero-padding). Maps to COTRN01 ``TRNIDINI``
        PIC X(16).
    tran_id : str
        Canonical transaction identifier (primary key) — the value
        actually stored in ``transaction.tran_id``. Maps to COTRN01
        ``TRNIDI`` PIC X(16).
    card_num : str
        16-digit card PAN associated with the transaction. Maps to
        COTRN01 ``CARDNUMI`` PIC X(16) and
        ``transaction.card_num`` column (``CVTRA05Y.cpy``
        TRAN-CARD-NUM PIC X(16)).
    tran_type_cd : str
        2-character transaction type code (e.g. ``'01'`` for debit,
        ``'02'`` for credit). Maps to COTRN01 ``TTYPCDI`` PIC X(02)
        and ``transaction.tran_type_cd`` column. See CVTRA03Y.cpy for
        the full type-code catalog.
    tran_cat_cd : str
        Transaction category code (up to 4 characters). Maps to
        COTRN01 ``TCATCDI`` PIC X(04) and
        ``transaction.tran_cat_cd`` column. See CVTRA04Y.cpy for the
        category catalog.
    tran_source : str
        Transaction source descriptor (up to 10 characters). Maps to
        COTRN01 ``TRNSRCI`` PIC X(10) and
        ``transaction.tran_source`` column.
    description : str
        Full transaction description (up to 60 characters). Maps to
        COTRN01 ``TDESCI`` PIC X(60) and
        ``transaction.tran_desc`` column (CVTRA05Y.cpy TRAN-DESC
        PIC X(100) is truncated to 60 on-screen).
    amount : Decimal
        Transaction amount — Decimal (never float) for COBOL
        ``PIC S9(n)V99`` precision. Maps to COTRN01 ``TRNAMTI``
        PIC X(12) and ``transaction.tran_amt`` column.
    orig_date : str
        Origination date (``YYYY-MM-DD`` or ``CCYYMMDD`` with trailing
        space). Maps to COTRN01 ``TORIGDTI`` PIC X(10) — derived from
        the date component of ``transaction.orig_ts`` (CVTRA05Y.cpy
        TRAN-ORIG-TS PIC X(26)).
    proc_date : str
        Processing date (``YYYY-MM-DD`` or ``CCYYMMDD`` with trailing
        space). Maps to COTRN01 ``TPROCDTI`` PIC X(10) — derived from
        ``transaction.proc_ts`` (CVTRA05Y.cpy TRAN-PROC-TS
        PIC X(26)).
    merchant_id : str
        9-digit merchant identifier. Maps to COTRN01 ``MIDI``
        PIC X(09) and ``transaction.merchant_id`` column
        (CVTRA05Y.cpy TRAN-MERCHANT-ID PIC 9(09)).
    merchant_name : str
        Merchant name (up to 30 characters for the screen view — the
        underlying ``transaction.merchant_name`` column is PIC X(50)
        per CVTRA05Y.cpy, but the BMS layout truncates to 30).
    merchant_city : str
        Merchant city (up to 25 characters for the screen view — the
        underlying column is PIC X(50) per CVTRA05Y.cpy, truncated to
        25 by BMS).
    merchant_zip : str
        Merchant ZIP code (up to 10 characters). Maps to COTRN01
        ``MZIPI`` PIC X(10) and ``transaction.merchant_zip`` column.
    message : Optional[str]
        Optional info/error message, up to 78 characters. ``None`` on
        a successful detail lookup; populated with an explanatory
        message on failure (e.g. "Transaction ID not found"). Maps to
        COTRN01 ``ERRMSGI`` PIC X(78).

    Raises
    ------
    pydantic.ValidationError
        Any field exceeding its declared ``max_length`` constraint.
    """

    # Enables direct construction from SQLAlchemy ORM rows — e.g. the
    # service layer can ``TransactionDetailResponse.model_validate(
    # transaction_row)`` after fetching a ``Transaction`` entity by PK,
    # without an intermediate dict conversion.
    model_config = ConfigDict(from_attributes=True)

    tran_id_input: str = Field(
        ...,
        max_length=_TRAN_ID_MAX_LEN,
        description=("Echoed search-input transaction ID — 16 chars. Maps to COTRN01 TRNIDINI PIC X(16)."),
    )
    tran_id: str = Field(
        ...,
        max_length=_TRAN_ID_MAX_LEN,
        description=(
            "Canonical transaction ID (primary key) — 16 chars. Maps "
            "to COTRN01 TRNIDI PIC X(16) and transaction.tran_id."
        ),
    )
    card_num: str = Field(
        ...,
        max_length=_CARD_NUM_MAX_LEN,
        description=("16-digit card PAN. Maps to COTRN01 CARDNUMI PIC X(16) and transaction.card_num."),
    )
    tran_type_cd: str = Field(
        ...,
        max_length=_TRAN_TYPE_CD_LEN,
        description=("2-char transaction type code (see CVTRA03Y.cpy). Maps to COTRN01 TTYPCDI PIC X(02)."),
    )
    tran_cat_cd: str = Field(
        ...,
        max_length=_TRAN_CAT_CD_MAX_LEN,
        description=(
            "Transaction category code (up to 4 chars — see CVTRA04Y.cpy). Maps to COTRN01 TCATCDI PIC X(04)."
        ),
    )
    tran_source: str = Field(
        ...,
        max_length=_TRAN_SOURCE_MAX_LEN,
        description=("Transaction source descriptor (up to 10 chars). Maps to COTRN01 TRNSRCI PIC X(10)."),
    )
    description: str = Field(
        ...,
        max_length=_DESC_MAX_LEN,
        description=("Full transaction description (up to 60 chars). Maps to COTRN01 TDESCI PIC X(60)."),
    )
    amount: Decimal = Field(
        ...,
        description=(
            "Transaction amount — Decimal (never float) for COBOL "
            "PIC S9(n)V99 parity. Maps to COTRN01 TRNAMTI PIC X(12)."
        ),
    )
    orig_date: str = Field(
        ...,
        max_length=_DATE_MAX_LEN,
        description=("Origination date (YYYY-MM-DD or CCYYMMDD, up to 10 chars). Maps to COTRN01 TORIGDTI PIC X(10)."),
    )
    proc_date: str = Field(
        ...,
        max_length=_DATE_MAX_LEN,
        description=("Processing date (YYYY-MM-DD or CCYYMMDD, up to 10 chars). Maps to COTRN01 TPROCDTI PIC X(10)."),
    )
    merchant_id: str = Field(
        ...,
        max_length=_MERCHANT_ID_MAX_LEN,
        description=("9-digit merchant ID. Maps to COTRN01 MIDI PIC X(09)."),
    )
    merchant_name: str = Field(
        ...,
        max_length=_MERCHANT_NAME_MAX_LEN,
        description=("Merchant name (up to 30 chars). Maps to COTRN01 MNAMEI PIC X(30)."),
    )
    merchant_city: str = Field(
        ...,
        max_length=_MERCHANT_CITY_MAX_LEN,
        description=("Merchant city (up to 25 chars). Maps to COTRN01 MCITYI PIC X(25)."),
    )
    merchant_zip: str = Field(
        ...,
        max_length=_MERCHANT_ZIP_MAX_LEN,
        description=("Merchant ZIP (up to 10 chars). Maps to COTRN01 MZIPI PIC X(10)."),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=("Optional info/error message, max 78 chars. Maps to COTRN01 ERRMSGI PIC X(78)."),
    )


# ---------------------------------------------------------------------------
# TransactionAddRequest — POST /transactions body from COTRN02 (F-011)
# ---------------------------------------------------------------------------
class TransactionAddRequest(BaseModel):
    """Payload for ``POST /transactions`` — creates a new transaction row.

    Replaces the CICS ``RECEIVE MAP ('COTRN2A')`` input handler in
    ``COTRN02C.cbl``. The BMS map COTRN02.CPY captures thirteen
    user-input fields covering every column of the ``transaction``
    table that is not server-generated (``tran_id`` is auto-assigned
    by the service layer via the sequence-number allocation logic
    described in F-011).

    Two fields are intentionally declared as ``Optional``:

    * ``proc_date`` — the processing date can be left blank on input;
      the service layer auto-populates it with the current system
      date when creating the row (matching the COBOL behavior where
      ``TRAN-PROC-TS`` is assigned from CURRENT-DATE at add time).
    * ``merchant_id``, ``merchant_name``, ``merchant_city``,
      ``merchant_zip`` — all four merchant fields are optional on
      input; if all are blank/None the service layer treats the
      transaction as non-merchant (e.g. an interest accrual).

    Validators
    ----------
    Four explicit ``@field_validator`` rules are enforced per the
    AAP's §0.7 refactoring rules and the file-schema agent prompt:

    1. ``acct_id``: up to 11 characters, all numeric. Maps to
       COBOL ``ACCT-ID PIC 9(11)`` (CVACT01Y.cpy) / COTRN02
       ``ACTIDINI PIC X(11)``.
    2. ``card_num``: exactly 16 characters, all numeric. Maps to
       COBOL ``CARD-NUM PIC X(16)`` (CVACT02Y.cpy) / COTRN02
       ``CARDNINI PIC X(16)``.
    3. ``tran_type_cd``: exactly 2 characters. Maps to COBOL
       ``TRAN-TYPE-CD PIC X(02)`` (CVTRA05Y.cpy) / COTRN02
       ``TTYPCDI PIC X(02)``.
    4. ``amount``: must be strictly greater than 0 (Decimal
       comparison) — matching the COBOL ``IF WS-TRAN-AMT NUMERIC
       AND WS-TRAN-AMT > 0`` guard that ``COTRN02C`` performs
       before inserting the row.

    Attributes
    ----------
    acct_id : str
        Account ID (up to 11 digits — numeric). Maps to COTRN02
        ``ACTIDINI`` PIC X(11).
    card_num : str
        16-digit card PAN. Maps to COTRN02 ``CARDNINI`` PIC X(16).
    tran_type_cd : str
        Exactly 2-char transaction type code. Maps to COTRN02
        ``TTYPCDI`` PIC X(02).
    tran_cat_cd : str
        Transaction category code (up to 4 chars). Maps to COTRN02
        ``TCATCDI`` PIC X(04).
    tran_source : str
        Transaction source descriptor (up to 10 chars). Maps to
        COTRN02 ``TRNSRCI`` PIC X(10).
    description : str
        Full transaction description (up to 60 chars). Maps to
        COTRN02 ``TDESCI`` PIC X(60).
    amount : Decimal
        Transaction amount — Decimal (never float) > 0. Maps to
        COTRN02 ``TRNAMTI`` PIC X(12).
    orig_date : str
        Origination date (``YYYY-MM-DD`` or ``CCYYMMDD``, up to 10
        chars). Maps to COTRN02 ``TORIGDTI`` PIC X(10).
    proc_date : Optional[str]
        Optional processing date — auto-populated by the service
        layer when omitted. Maps to COTRN02 ``TPROCDTI`` PIC X(10).
    merchant_id : Optional[str]
        Optional 9-digit merchant ID. Maps to COTRN02 ``MIDI``
        PIC X(09).
    merchant_name : Optional[str]
        Optional merchant name (up to 30 chars). Maps to COTRN02
        ``MNAMEI`` PIC X(30).
    merchant_city : Optional[str]
        Optional merchant city (up to 25 chars). Maps to COTRN02
        ``MCITYI`` PIC X(25).
    merchant_zip : Optional[str]
        Optional merchant ZIP (up to 10 chars). Maps to COTRN02
        ``MZIPI`` PIC X(10).

    Raises
    ------
    pydantic.ValidationError
        * ``acct_id`` blank, non-numeric, or > 11 characters.
        * ``card_num`` not exactly 16 numeric digits.
        * ``tran_type_cd`` not exactly 2 characters.
        * ``amount`` <= 0.
        * Any field exceeding its ``max_length``.
    """

    acct_id: str = Field(
        ...,
        max_length=_ACCT_ID_MAX_LEN,
        description=("Account ID (up to 11 digits — numeric). Maps to COTRN02 ACTIDINI PIC X(11)."),
    )
    card_num: str = Field(
        ...,
        max_length=_CARD_NUM_MAX_LEN,
        description=("16-digit card PAN. Maps to COTRN02 CARDNINI PIC X(16)."),
    )
    tran_type_cd: str = Field(
        ...,
        max_length=_TRAN_TYPE_CD_LEN,
        description=("2-char transaction type code. Maps to COTRN02 TTYPCDI PIC X(02)."),
    )
    tran_cat_cd: str = Field(
        ...,
        max_length=_TRAN_CAT_CD_MAX_LEN,
        description=("Transaction category code (up to 4 chars). Maps to COTRN02 TCATCDI PIC X(04)."),
    )
    tran_source: str = Field(
        ...,
        max_length=_TRAN_SOURCE_MAX_LEN,
        description=("Transaction source descriptor (up to 10 chars). Maps to COTRN02 TRNSRCI PIC X(10)."),
    )
    description: str = Field(
        ...,
        max_length=_DESC_MAX_LEN,
        description=("Full transaction description (up to 60 chars). Maps to COTRN02 TDESCI PIC X(60)."),
    )
    amount: Decimal = Field(
        ...,
        description=("Transaction amount — Decimal (never float), strictly > 0. Maps to COTRN02 TRNAMTI PIC X(12)."),
    )
    orig_date: str = Field(
        ...,
        max_length=_DATE_MAX_LEN,
        description=("Origination date (YYYY-MM-DD or CCYYMMDD, up to 10 chars). Maps to COTRN02 TORIGDTI PIC X(10)."),
    )
    proc_date: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_DATE_MAX_LEN,
        description=(
            "Optional processing date (up to 10 chars) — auto-populated "
            "by the service layer when omitted. Maps to COTRN02 "
            "TPROCDTI PIC X(10)."
        ),
    )
    merchant_id: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_MERCHANT_ID_MAX_LEN,
        description=("Optional 9-digit merchant ID. Maps to COTRN02 MIDI PIC X(09)."),
    )
    merchant_name: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_MERCHANT_NAME_MAX_LEN,
        description=("Optional merchant name (up to 30 chars). Maps to COTRN02 MNAMEI PIC X(30)."),
    )
    merchant_city: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_MERCHANT_CITY_MAX_LEN,
        description=("Optional merchant city (up to 25 chars). Maps to COTRN02 MCITYI PIC X(25)."),
    )
    merchant_zip: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_MERCHANT_ZIP_MAX_LEN,
        description=("Optional merchant ZIP (up to 10 chars). Maps to COTRN02 MZIPI PIC X(10)."),
    )

    @field_validator("acct_id")
    @classmethod
    def _validate_acct_id(cls, value: str) -> str:
        """Ensure ``acct_id`` is a non-empty numeric string up to 11 chars.

        Matches the COBOL constraint ``ACCT-ID PIC 9(11)`` (CVACT01Y.cpy)
        — the account identifier is a numeric value stored in an 11-digit
        field. COTRN02 accepts it as PIC X(11) on the screen but the
        service layer requires a numeric value for the account lookup.

        Parameters
        ----------
        value : str
            Raw string supplied on the request body.

        Returns
        -------
        str
            The original string, unchanged, once validated. Padding and
            canonicalization (e.g. left zero-fill to 11 digits) are the
            responsibility of the service layer, not the schema.

        Raises
        ------
        ValueError
            If ``value`` is ``None``, not a string, empty (after strip),
            longer than 11 characters, or contains any non-digit
            character.
        """
        if value is None:
            raise ValueError("acct_id is required (COTRN02 ACTIDINI PIC X(11)).")
        if not isinstance(value, str):
            raise ValueError("acct_id must be a string (COTRN02 ACTIDINI PIC X(11)).")
        if not value.strip():
            raise ValueError("acct_id must not be blank (COTRN02 ACTIDINI PIC X(11)).")
        if len(value) > _ACCT_ID_MAX_LEN:
            raise ValueError(
                f"acct_id must be at most {_ACCT_ID_MAX_LEN} characters (COBOL ACCT-ID PIC 9(11)); got {len(value)}."
            )
        if not value.isdigit():
            raise ValueError("acct_id must contain only digits 0-9 (COBOL ACCT-ID PIC 9(11)).")
        return value

    @field_validator("card_num")
    @classmethod
    def _validate_card_num(cls, value: str) -> str:
        """Ensure ``card_num`` is exactly 16 numeric digits.

        Matches the COBOL constraint for the card PAN — ``CARD-NUM
        PIC X(16)`` (CVACT02Y.cpy) — where the system mandates a fixed
        16-digit numeric primary account number. The screen field
        ``CARDNINI PIC X(16)`` in COTRN02 permits any characters, so the
        schema enforces the numeric-length rule that ``COTRN02C.cbl``
        applies in its ``EDIT-CARD-NUMBER`` paragraph.

        Parameters
        ----------
        value : str
            Raw string supplied on the request body.

        Returns
        -------
        str
            The original string, unchanged, once validated.

        Raises
        ------
        ValueError
            If ``value`` is ``None``, not a string, not exactly 16
            characters long, or contains any non-digit character.
        """
        if value is None:
            raise ValueError("card_num is required (COTRN02 CARDNINI PIC X(16)).")
        if not isinstance(value, str):
            raise ValueError("card_num must be a string (COTRN02 CARDNINI PIC X(16)).")
        if len(value) != _CARD_NUM_MAX_LEN:
            raise ValueError(
                f"card_num must be exactly {_CARD_NUM_MAX_LEN} characters (COBOL CARD-NUM PIC X(16)); got {len(value)}."
            )
        if not value.isdigit():
            raise ValueError("card_num must contain only digits 0-9 (COBOL CARD-NUM PIC X(16) — 16-digit PAN).")
        return value

    @field_validator("tran_type_cd")
    @classmethod
    def _validate_tran_type_cd(cls, value: str) -> str:
        """Ensure ``tran_type_cd`` is exactly 2 characters.

        Matches the COBOL constraint ``TRAN-TYPE-CD PIC X(02)`` in
        CVTRA05Y.cpy — the transaction-type catalog (CVTRA03Y.cpy) is
        keyed on a fixed 2-character code (e.g. ``'01'`` = debit,
        ``'02'`` = credit). Empty or differently-sized values are
        rejected at the API boundary rather than being passed through
        to the type-code lookup.

        Parameters
        ----------
        value : str
            Raw string supplied on the request body.

        Returns
        -------
        str
            The original string, unchanged, once validated. Casing and
            leading-zero normalization (if any) are the responsibility
            of the service/lookup layer.

        Raises
        ------
        ValueError
            If ``value`` is ``None``, not a string, or not exactly 2
            characters long.
        """
        if value is None:
            raise ValueError("tran_type_cd is required (COTRN02 TTYPCDI PIC X(02)).")
        if not isinstance(value, str):
            raise ValueError("tran_type_cd must be a string (COTRN02 TTYPCDI PIC X(02)).")
        if len(value) != _TRAN_TYPE_CD_LEN:
            raise ValueError(
                f"tran_type_cd must be exactly {_TRAN_TYPE_CD_LEN} "
                f"characters (COBOL TRAN-TYPE-CD PIC X(02)); got "
                f"{len(value)}."
            )
        return value

    @field_validator("amount")
    @classmethod
    def _validate_amount_positive(cls, value: Decimal) -> Decimal:
        """Ensure ``amount`` is a positive Decimal (> 0).

        Matches the COBOL guard ``IF WS-TRAN-AMT NUMERIC AND
        WS-TRAN-AMT > 0`` that ``COTRN02C.cbl`` enforces before
        inserting a transaction row. Zero or negative amounts are a
        category error — refunds/reversals are represented by a
        different ``TRAN-TYPE-CD`` rather than a negative amount on
        this schema.

        The Decimal comparison is exact (no float conversion) which
        preserves COBOL ``PIC S9(n)V99`` semantics; values such as
        ``Decimal('0.01')`` pass while ``Decimal('0')`` and
        ``Decimal('-1.00')`` are rejected.

        Parameters
        ----------
        value : Decimal
            Decimal amount parsed by Pydantic from the request body.

        Returns
        -------
        Decimal
            The original Decimal, unchanged, once validated.

        Raises
        ------
        ValueError
            If ``value`` is ``None`` or not strictly greater than 0.
        """
        if value is None:
            raise ValueError("amount is required (COTRN02 TRNAMTI PIC X(12)).")
        # Decimal supports direct comparison with integers via the
        # standard arithmetic protocol; Decimal('0.01') > 0 evaluates
        # exactly, without any float round-trip.
        if value <= Decimal("0"):
            raise ValueError("amount must be greater than 0 (COBOL guard IF WS-TRAN-AMT > 0 in COTRN02C).")
        return value


# ---------------------------------------------------------------------------
# TransactionAddResponse — ``POST /transactions`` success envelope (F-011)
# ---------------------------------------------------------------------------
class TransactionAddResponse(BaseModel):
    """Response payload returned by ``POST /transactions``.

    Replaces the CICS ``SEND MAP ('COTRN2A') ... MAPSET('COTRN02')``
    success-refresh in ``COTRN02C.cbl``, which echoed the confirmation
    fields back to the user with the newly-assigned transaction ID. The
    server-assigned ``tran_id`` is the most important field: the
    service layer allocates it via the sequence-number logic described
    in F-011 (auto-ID + cross-reference resolution) and returns it here
    so the client can surface it to the user.

    The ``confirm`` field mirrors the BMS ``CONFIRMI PIC X(01)``
    single-character flag — ``'Y'`` on a successful add (matching the
    COBOL path that sets ``CONFIRMO = 'Y'`` after a successful
    ``EXEC CICS WRITE FILE('TRANSACT')``), ``'N'`` when the add was
    rejected.

    Attributes
    ----------
    tran_id : str
        Server-generated transaction identifier (16 chars). Populated
        by the service layer from the next value of the transaction
        sequence. Maps to ``transaction.tran_id`` (CVTRA05Y.cpy
        TRAN-ID PIC X(16)) / COTRN02 ``TRNIDI``/``TRNIDO`` (not a
        user-input field on COTRN02 — displayed on success refresh).
    acct_id : str
        Echoed account ID (up to 11 digits). Maps to COTRN02
        ``ACTIDINI``/``ACTIDINO`` PIC X(11).
    card_num : str
        Echoed 16-digit card PAN. Maps to COTRN02
        ``CARDNINI``/``CARDNINO`` PIC X(16).
    amount : Decimal
        Echoed transaction amount (Decimal, never float). Maps to
        COTRN02 ``TRNAMTI``/``TRNAMTO`` PIC X(12).
    confirm : str
        Single-character confirmation indicator (``'Y'``/``'N'``).
        Maps to COTRN02 ``CONFIRMI``/``CONFIRMO`` PIC X(01).
    message : Optional[str]
        Optional info/error message, up to 78 characters. ``None`` on
        a successful add; populated on failure with an explanatory
        message (e.g. "Card not found in CXACAIX"). Maps to COTRN02
        ``ERRMSGI``/``ERRMSGO`` PIC X(78).

    Raises
    ------
    pydantic.ValidationError
        Any field exceeding its declared ``max_length`` constraint.
    """

    # Enables direct construction from the SQLAlchemy row returned by
    # the add-transaction service — ``TransactionAddResponse
    # .model_validate(new_row, from_attributes=True)`` works out of
    # the box once the service sets ``confirm``/``message`` on the
    # row object (or via a small adapter).
    model_config = ConfigDict(from_attributes=True)

    tran_id: str = Field(
        ...,
        max_length=_TRAN_ID_MAX_LEN,
        description=(
            "Server-generated transaction ID (16 chars). Maps to transaction.tran_id (CVTRA05Y TRAN-ID PIC X(16))."
        ),
    )
    acct_id: str = Field(
        ...,
        max_length=_ACCT_ID_MAX_LEN,
        description=("Echoed account ID (up to 11 digits). Maps to COTRN02 ACTIDINO PIC X(11)."),
    )
    card_num: str = Field(
        ...,
        max_length=_CARD_NUM_MAX_LEN,
        description=("Echoed 16-digit card PAN. Maps to COTRN02 CARDNINO PIC X(16)."),
    )
    amount: Decimal = Field(
        ...,
        description=("Echoed transaction amount — Decimal (never float). Maps to COTRN02 TRNAMTO PIC X(12)."),
    )
    confirm: str = Field(
        ...,
        max_length=_CONFIRM_MAX_LEN,
        description=("Single-character confirmation indicator ('Y'/'N'). Maps to COTRN02 CONFIRMO PIC X(01)."),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=("Optional info/error message, max 78 chars. Maps to COTRN02 ERRMSGO PIC X(78)."),
    )


# ---------------------------------------------------------------------------
# Public export list
# ---------------------------------------------------------------------------
# Explicit ``__all__`` declaration — only the six schema classes are
# part of the public API surface of this module. Private constants
# (leading underscore) are intentionally excluded; they are an
# implementation detail backing the ``max_length`` constraints and are
# not meant to be referenced by the service/router layers.
__all__ = [
    "TransactionListRequest",
    "TransactionListItem",
    "TransactionListResponse",
    "TransactionDetailResponse",
    "TransactionAddRequest",
    "TransactionAddResponse",
]
