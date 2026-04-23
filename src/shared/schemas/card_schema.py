# ============================================================================
# Source: COBOL BMS symbolic map copybooks
#         * app/cpy-bms/COCRDLI.CPY — Card List  (Feature F-006)
#         * app/cpy-bms/COCRDSL.CPY — Card Detail View (Feature F-007)
#         * app/cpy-bms/COCRDUP.CPY — Card Update (Feature F-008)
#         * app/cpy/CVACT02Y.cpy    — Card VSAM record layout (150-byte KSDS)
# ============================================================================
# Mainframe-to-Cloud migration: CICS SEND/RECEIVE MAP COCRDLIA/COCRDSLA/
# COCRDUPA → REST JSON request/response bodies on AWS ECS Fargate.
#
# Replaces:
#   * The BMS symbolic-map input fields consumed by CICS programs
#     COCRDLIC.cbl (list), COCRDSLC.cbl (detail) and COCRDUPC.cbl (update)
#     — previously populated by CICS RECEIVE MAP and sent to the 3270
#     terminal via CICS SEND MAP.
#   * The 7-row repeated screen layout on COCRDLI (CRDSEL1..CRDSEL7,
#     ACCTNO1..ACCTNO7, CRDNUM1..CRDNUM7, CRDSTS1..CRDSTS7) — now a
#     variable-length ``List[CardListItem]`` in JSON.
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
"""Pydantic v2 card schemas for the CardDemo REST/GraphQL API.

Converts three COBOL BMS symbolic-map copybooks (and the underlying VSAM
record layout ``CVACT02Y.cpy``) into six Pydantic v2 request/response
models backing the Card REST endpoints:

* ``GET  /cards``           (F-006, :class:`CardListRequest`
                                    → :class:`CardListResponse`)
* ``GET  /cards/{card_num}`` (F-007, → :class:`CardDetailResponse`)
* ``PUT  /cards/{card_num}`` (F-008, :class:`CardUpdateRequest`
                                    → :class:`CardUpdateResponse`)

COBOL → Python Field Mapping
----------------------------
===============================  ======================  ======================
COBOL BMS Field                  Python Class            Python Field
===============================  ======================  ======================
**COCRDLI.CPY (Card List)**
ACCTSIDI   PIC X(11)             CardListRequest         ``account_id``
CARDSIDI   PIC X(16)             CardListRequest         ``card_number``
PAGENOI    PIC X(03)             CardListRequest         ``page_number``
CRDSELnI   PIC X(01)             CardListItem            ``selected``
ACCTNOnI   PIC X(11)             CardListItem            ``account_id``
CRDNUMnI   PIC X(16)             CardListItem            ``card_number``
CRDSTSnI   PIC X(01)             CardListItem            ``card_status``
INFOMSGI   PIC X(45)             CardListResponse        ``info_message``
ERRMSGI    PIC X(78)             CardListResponse        ``error_message``
**COCRDSL.CPY (Card Detail)**
ACCTSIDI   PIC X(11)             CardDetailResponse      ``account_id``
CARDSIDI   PIC X(16)             CardDetailResponse      ``card_number``
CRDNAMEI   PIC X(50)             CardDetailResponse      ``embossed_name``
CRDSTCDI   PIC X(01)             CardDetailResponse      ``status_code``
EXPMONI    PIC X(02)             CardDetailResponse      ``expiry_month``
EXPYEARI   PIC X(04)             CardDetailResponse      ``expiry_year``
INFOMSGI   PIC X(40)             CardDetailResponse      ``info_message``
ERRMSGI    PIC X(80)             CardDetailResponse      ``error_message``
**COCRDUP.CPY (Card Update)**
ACCTSIDI   PIC X(11)             CardUpdateRequest       ``account_id``
CARDSIDI   PIC X(16)             CardUpdateRequest       ``card_number``
CRDNAMEI   PIC X(50)             CardUpdateRequest       ``embossed_name``
CRDSTCDI   PIC X(01)             CardUpdateRequest       ``status_code``
EXPMONI    PIC X(02)             CardUpdateRequest       ``expiry_month``
EXPYEARI   PIC X(04)             CardUpdateRequest       ``expiry_year``
EXPDAYI    PIC X(02)             CardUpdateRequest       ``expiry_day``
===============================  ======================  ======================

VSAM Record Layout (``CVACT02Y.cpy`` — 150-byte CARDDAT KSDS record)
--------------------------------------------------------------------
* ``CARD-NUM``             PIC X(16)   — primary key (16-digit card PAN)
* ``CARD-ACCT-ID``         PIC 9(11)   — owning account (alternate index)
* ``CARD-CVV-CD``          PIC 9(03)   — 3-digit CVV (never returned in the
                                         API response per PCI-DSS guidance)
* ``CARD-EMBOSSED-NAME``   PIC X(50)   — name embossed on the card
* ``CARD-EXPIRAION-DATE``  PIC X(10)   — ``YYYY-MM-DD`` (mainframe typo
                                         preserved — the COBOL field name
                                         is ``CARD-EXPIRAION-DATE``)
* ``CARD-ACTIVE-STATUS``   PIC X(01)   — 'Y' = active, 'N' = inactive
* ``FILLER``               PIC X(59)   — reserved, not mapped

Design Notes
------------
* **Max-length constraints** match the COBOL ``PIC X(N)`` widths exactly.
  The BMS layouts COCRDSL and COCRDUP share an identical 50-char embossed
  name / 2-char month / 4-char year, but differ in that COCRDUP adds an
  extra ``EXPDAYI`` PIC X(02) day component (day-of-month); and the two
  screens independently specify a 40-char ``INFOMSGI`` and 80-char
  ``ERRMSGI`` — distinct from the 45/78-char widths on the List screen.
* **7 rows per page** — :class:`CardListRequest` preserves the original
  COCRDLI screen's 7 repeated row groups (CRDSEL1 through CRDSEL7) as
  the page size. The response uses a variable-length list so the final
  (partial) page is not zero-padded.
* **Exact-length validators** — ``account_id`` (11 digits),
  ``card_number`` (16 chars), ``status_code`` (1 char), ``expiry_month``
  ('01' to '12'), and (on Update) ``expiry_day`` / ``expiry_year``
  widths are enforced at the schema layer. These are in addition to
  Pydantic's implicit ``max_length`` constraint.
* **``ConfigDict(from_attributes=True)``** is applied to every
  response/list-item schema (:class:`CardListItem`,
  :class:`CardListResponse`, :class:`CardDetailResponse`,
  :class:`CardUpdateResponse`) so the service layer may instantiate
  them directly from SQLAlchemy ORM rows (e.g. an ``src.shared.models
  .card.Card`` instance) without an intermediate dict conversion. The
  two request schemas (:class:`CardListRequest`,
  :class:`CardUpdateRequest`) do NOT enable ORM mode because request
  payloads always arrive as JSON-decoded dicts from the REST /
  GraphQL layer.
* **Optimistic concurrency** — :class:`CardUpdateRequest` intentionally
  does NOT carry a version / ETag field. Optimistic-concurrency control
  for card updates (F-008 — matches the COCRDUPC.cbl CICS READ UPDATE /
  REWRITE pattern) is enforced at the service / repository layer
  against the ``card.version`` SQLAlchemy column, not at the schema
  layer. The service layer reads the current row, compares domain
  fields and, on conflict, raises a 409 to the router. This keeps the
  wire contract identical to the original BMS screen which carried no
  version token.
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length constraints and
  :func:`~pydantic.field_validator` for business-rule enforcement.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI/Uvicorn container image).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (F-006, F-007, F-008)
AAP §0.4.1 — Refactored Structure Planning (``card_schema.py`` row)
AAP §0.5.1 — File-by-File Transformation Plan (``card_schema.py``)
AAP §0.7.1 — Refactoring-Specific Rules (functional parity)
"""

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

# ACCTSIDI / ACCTNOnI PIC X(11) — 11-character account ID. Matches the
# ``account.acct_id`` PK width in Aurora PostgreSQL and the ACCT-ID
# PIC 9(11) domain on CVACT01Y.cpy.
_ACCT_ID_LEN: int = 11

# CARDSIDI / CRDNUMnI PIC X(16) — 16-character card number (PAN).
# Matches the ``card.card_num`` PK width and the CARD-NUM PIC X(16)
# domain on CVACT02Y.cpy. Preserved as a string to retain leading
# zeros if present in the 16-digit PAN.
_CARD_NUM_LEN: int = 16

# CRDNAMEI PIC X(50) — 50-character embossed name (matches
# CARD-EMBOSSED-NAME PIC X(50) on CVACT02Y.cpy).
_EMBOSSED_NAME_MAX_LEN: int = 50

# CRDSELnI / CRDSTSnI / CRDSTCDI PIC X(01) — 1-character selection
# flag / status indicator / card active status code.
_STATUS_LEN: int = 1

# PAGENOI PIC X(03) — 3-character page number display field on COCRDLI.
# The modern API exposes this as an ``int`` (not a string) — see
# :class:`CardListRequest.page_number`. Retained for traceability.
_PAGE_NUM_DISPLAY_WIDTH: int = 3  # noqa: F841  (documentation constant)

# EXPMONI PIC X(02) / EXPDAYI PIC X(02) — 2-character expiry month /
# expiry day components. Stored as strings to preserve leading-zero
# formatting (e.g. '01'..'12' for month, '01'..'31' for day).
_EXPIRY_MM_DD_LEN: int = 2

# EXPYEARI PIC X(04) — 4-character expiry year (CCYY, e.g. '2026').
_EXPIRY_YEAR_LEN: int = 4

# INFOMSGI on COCRDLI PIC X(45) — 45-character info-message line on the
# List screen. The Detail/Update screens use a narrower 40-char info
# line (see ``_INFO_MSG_DETAIL_MAX_LEN``).
_INFO_MSG_LIST_MAX_LEN: int = 45

# INFOMSGI on COCRDSL / COCRDUP PIC X(40) — 40-character info-message
# line on the Detail / Update screens.
_INFO_MSG_DETAIL_MAX_LEN: int = 40

# ERRMSGI on COCRDLI PIC X(78) — 78-character error-message line on
# the List screen.
_ERR_MSG_LIST_MAX_LEN: int = 78

# ERRMSGI on COCRDSL / COCRDUP PIC X(80) — 80-character error-message
# line on the Detail / Update screens.
_ERR_MSG_DETAIL_MAX_LEN: int = 80

# Pagination — the original COBOL List screen hard-coded 7 rows per
# page (CRDSEL1..CRDSEL7 + ACCTNO1..ACCTNO7 + CRDNUM1..CRDNUM7 +
# CRDSTS1..CRDSTS7 repeated row groups). The modern API preserves 7 as
# the page size (service layer slices accordingly).
_PAGE_SIZE: int = 7  # noqa: F841  (documentation constant referenced in docstrings)


# ---------------------------------------------------------------------------
# CardListRequest — query parameters for ``GET /cards``
# ---------------------------------------------------------------------------
class CardListRequest(BaseModel):
    """Query parameters for the paginated card list (F-006).

    Replaces the incoming BMS input fields on the COCRDLI Card List
    screen that were previously received via CICS RECEIVE MAP
    ('COCRDLIA') in ``COCRDLIC.cbl``. The original COBOL program
    accepted three pieces of client input on each refresh:

    * an 11-character ``ACCTSIDI`` account-ID filter used to restrict
      the list to cards owned by a specific account;
    * a 16-character ``CARDSIDI`` card-number filter used to locate
      (jump to) a specific card;
    * a 3-character ``PAGENOI`` page-number indicator used to navigate
      forward / backward through the 7-row screens.

    The modernized API exposes all three as HTTP query parameters on
    ``GET /cards``.

    Attributes
    ----------
    account_id : Optional[str]
        Optional filter — when provided, restrict the list to cards
        owned by this account. Exactly 11 digits (COBOL
        ``PIC X(11)`` / ``PIC 9(11)`` constraint from ACCTSIDI on
        COCRDLI.CPY). ``None`` means "no account filter".
    card_number : Optional[str]
        Optional filter — when provided, restrict the list to this
        specific card (or locate-and-jump-to). Exactly 16 characters
        (COBOL ``PIC X(16)`` constraint from CARDSIDI on COCRDLI.CPY).
        ``None`` means "no card filter".
    page_number : int
        1-based page number. Defaults to ``1``. Must be ``>= 1``.
        Mirrors the original ``PAGENOI`` field on COCRDLI.CPY which
        was populated by the forward/backward navigation keys (``PF7``,
        ``PF8``) in the CICS program.

    Raises
    ------
    pydantic.ValidationError
        * When ``account_id`` is not exactly 11 digits (and not ``None``).
        * When ``card_number`` is not exactly 16 characters
          (and not ``None``).
        * When ``page_number`` is less than 1.
    """

    # Request schemas do NOT set ``from_attributes=True`` because
    # requests always arrive as JSON-decoded dicts (never SQLAlchemy
    # objects) from the REST / GraphQL layer.

    account_id: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ACCT_ID_LEN,
        description=(
            "Optional account ID filter — exactly 11 digits when provided. Maps to COCRDLI ACCTSIDI PIC X(11)."
        ),
    )
    card_number: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_CARD_NUM_LEN,
        description=(
            "Optional card number filter — exactly 16 characters when provided. Maps to COCRDLI CARDSIDI PIC X(16)."
        ),
    )
    page_number: int = Field(
        default=1,
        ge=1,
        description=("1-based page number (defaults to 1; must be >= 1). Maps to COCRDLI PAGENOI PIC X(03)."),
    )

    # ---------------------------------------------------------------
    # Field-level validators
    # ---------------------------------------------------------------
    @field_validator("account_id")
    @classmethod
    def _validate_account_id(
        cls,
        value: Optional[str],  # noqa: UP045  # schema requires typing.Optional
    ) -> Optional[str]:  # noqa: UP045  # schema requires typing.Optional
        """Enforce exact 11-digit account-ID format when supplied.

        The original COBOL program treated a blank ``ACCTSIDI`` field
        as "no account filter". This validator normalizes blank /
        whitespace-only values to ``None`` and, when a non-blank
        value is supplied, enforces the 11-digit COBOL
        ``PIC 9(11)`` / ``PIC X(11)`` constraint.

        Parameters
        ----------
        value
            Candidate ``account_id`` from the query string, or ``None``.

        Returns
        -------
        Optional[str]
            ``None`` when ``value`` is ``None``, empty, or
            whitespace-only; otherwise the original ``value``
            unchanged (exactly 11 digits).

        Raises
        ------
        ValueError
            When ``value`` is a non-blank string that is not exactly
            11 characters long, or contains any non-digit character.
        """
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError(f"account_id must be a string or None; got {type(value).__name__}")
        # Normalize blank / whitespace-only input to None (matches the
        # COBOL screen's behavior where a blank ACCTSIDI was ignored).
        stripped = value.strip()
        if not stripped:
            return None
        if len(stripped) != _ACCT_ID_LEN:
            raise ValueError(f"account_id must be exactly {_ACCT_ID_LEN} digits; got {len(stripped)} characters")
        if not stripped.isdigit():
            raise ValueError("account_id must contain only digits (0-9); got a non-numeric character")
        return stripped

    @field_validator("card_number")
    @classmethod
    def _validate_card_number_filter(
        cls,
        value: Optional[str],  # noqa: UP045  # schema requires typing.Optional
    ) -> Optional[str]:  # noqa: UP045  # schema requires typing.Optional
        """Enforce exact 16-character card-number format when supplied.

        The original COBOL program treated a blank ``CARDSIDI`` field
        as "no card filter". This validator normalizes blank /
        whitespace-only values to ``None`` and, when a non-blank
        value is supplied, enforces the 16-character COBOL
        ``PIC X(16)`` constraint on the CARD-NUM primary key.

        Parameters
        ----------
        value
            Candidate ``card_number`` from the query string, or ``None``.

        Returns
        -------
        Optional[str]
            ``None`` when ``value`` is ``None``, empty, or
            whitespace-only; otherwise the original ``value``
            unchanged (exactly 16 characters).

        Raises
        ------
        ValueError
            When ``value`` is a non-blank string that is not exactly
            16 characters long.
        """
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError(f"card_number must be a string or None; got {type(value).__name__}")
        # Normalize blank / whitespace-only input to None (matches the
        # COBOL screen's behavior where a blank CARDSIDI was ignored).
        stripped = value.strip()
        if not stripped:
            return None
        if len(stripped) != _CARD_NUM_LEN:
            raise ValueError(f"card_number must be exactly {_CARD_NUM_LEN} characters; got {len(stripped)} characters")
        return stripped


# ---------------------------------------------------------------------------
# CardListItem — one repeated row from COCRDLI (rows 1..7)
# ---------------------------------------------------------------------------
class CardListItem(BaseModel):
    """A single card row within the paginated list (F-006).

    Mirrors the structure of the 7 repeated row groups (suffix ``1``
    through ``7``) on the COCRDLI Card List BMS screen. Each row on
    the original 3270 terminal screen displayed four columns:

    ==========================  =================  ============
    BMS Field (COCRDLI)         Python Field       Width
    ==========================  =================  ============
    CRDSELnI    PIC X(01)       ``selected``        1 char
    ACCTNOnI    PIC X(11)       ``account_id``     11 chars
    CRDNUMnI    PIC X(16)       ``card_number``    16 chars
    CRDSTSnI    PIC X(01)       ``card_status``     1 char
    ==========================  =================  ============

    The API emits an array of these items in
    :attr:`CardListResponse.cards`, so the number of populated rows
    equals ``min(7, total_count - offset)`` rather than always being
    exactly 7 (the final page may be partial).

    Attributes
    ----------
    selected : str
        1-character selection / bookmark indicator — typically blank
        (``' '``) on read-only list responses; the COBOL CICS
        program originally used this flag to drive follow-on
        navigation (e.g. selecting a row to jump to the detail
        screen). Preserved for screen-level parity with COCRDLI
        CRDSELnI PIC X(01).
    account_id : str
        11-character owning account ID. Maps to COCRDLI ACCTNOnI
        PIC X(11) and the ``card.acct_id`` foreign-key column
        (CVACT02Y.cpy CARD-ACCT-ID PIC 9(11)).
    card_number : str
        16-character card number (PAN). Maps to COCRDLI CRDNUMnI
        PIC X(16) and the ``card.card_num`` primary key column
        (CVACT02Y.cpy CARD-NUM PIC X(16)).
    card_status : str
        1-character active/inactive status ('Y' = active, 'N' =
        inactive). Maps to COCRDLI CRDSTSnI PIC X(01) and the
        ``card.active_status`` column (CVACT02Y.cpy
        CARD-ACTIVE-STATUS PIC X(01)).

    Raises
    ------
    pydantic.ValidationError
        * When any of ``selected`` / ``card_status`` is longer than 1
          character.
        * When ``account_id`` is longer than 11 characters.
        * When ``card_number`` is longer than 16 characters.
    """

    # Enables direct construction from SQLAlchemy ORM rows (e.g.
    # ``CardListItem.model_validate(card_row)``), which the service
    # layer uses to assemble the list efficiently without building an
    # intermediate dict for each row.
    model_config = ConfigDict(from_attributes=True)

    selected: str = Field(
        ...,
        max_length=_STATUS_LEN,
        description=(
            "1-char selection flag — typically blank on read-only list responses. Maps to COCRDLI CRDSELnI PIC X(01)."
        ),
    )
    account_id: str = Field(
        ...,
        max_length=_ACCT_ID_LEN,
        description=("11-char owning account ID. Maps to COCRDLI ACCTNOnI PIC X(11) and card.acct_id."),
    )
    card_number: str = Field(
        ...,
        max_length=_CARD_NUM_LEN,
        description=("16-char card number (PAN — primary key). Maps to COCRDLI CRDNUMnI PIC X(16) and card.card_num."),
    )
    card_status: str = Field(
        ...,
        max_length=_STATUS_LEN,
        description=(
            "1-char active/inactive status ('Y'/'N'). Maps to COCRDLI CRDSTSnI PIC X(01) and card.active_status."
        ),
    )


# ---------------------------------------------------------------------------
# CardListResponse — paged envelope around CardListItem
# ---------------------------------------------------------------------------
class CardListResponse(BaseModel):
    """Response envelope for ``GET /cards`` (F-006).

    Replaces the CICS ``SEND MAP ('COCRDLIA')`` screen refresh that
    terminated a page-navigation transaction in ``COCRDLIC.cbl``. The
    original BMS screen returned:

    * seven :class:`CardListItem`-shaped row groups (suffix ``1``
      through ``7``), of which the unused rows were blank-filled;
    * the current ``PAGENOI`` page number (echoed from the request);
    * the 45-character ``INFOMSGI`` informational-message row and the
      78-character ``ERRMSGI`` error-message row.

    The modern API envelope swaps the fixed 7-row array for a
    variable-length :class:`~typing.List`, adds an explicit
    ``total_pages`` count so the client can render a pager without a
    second round-trip, and promotes the ``INFOMSGI`` / ``ERRMSGI``
    fields to optional strings.

    Attributes
    ----------
    cards : List[CardListItem]
        The list of card rows returned on the current page. Empty
        list on a zero-result page (e.g. when both ``account_id`` and
        ``card_number`` filters match no cards). Max length is ``7``
        (matches the 7 repeated row groups on COCRDLI.CPY).
    page_number : int
        1-based current page number. Echoes the request's
        ``page_number`` field. Maps to COCRDLI PAGENOI PIC X(03).
    total_pages : int
        Total number of pages available for the current filter.
        Computed as ``ceil(total_count / 7)``. Non-negative integer;
        ``0`` when the filter matches no cards.
    info_message : Optional[str]
        Optional 45-character informational message echoed from the
        service layer (e.g. "No cards found for account 00000000123").
        Maps to COCRDLI INFOMSGI PIC X(45).
    error_message : Optional[str]
        Optional 78-character error message echoed from the service
        layer (e.g. validation / database-error text). Maps to
        COCRDLI ERRMSGI PIC X(78).

    Raises
    ------
    pydantic.ValidationError
        * When ``info_message`` is longer than 45 characters.
        * When ``error_message`` is longer than 78 characters.
        * When ``page_number`` or ``total_pages`` is negative.
    """

    # Enables direct construction from SQLAlchemy ORM rows via
    # ``model_validate(...)``. The router / service layer may pass an
    # object with matching attributes (``.cards``, ``.page_number``,
    # etc.) rather than a dict.
    model_config = ConfigDict(from_attributes=True)

    cards: List[CardListItem] = Field(  # noqa: UP006  # schema requires typing.List
        ...,
        description=("Paginated card rows (max 7 per page, matching the 7 repeated row groups on COCRDLI.CPY)."),
    )
    page_number: int = Field(
        ...,
        ge=1,
        description=("1-based current page number, echoed from the request. Maps to COCRDLI PAGENOI PIC X(03)."),
    )
    total_pages: int = Field(
        ...,
        ge=0,
        description=("Total number of pages available for the current filter (computed as ceil(total_count / 7))."),
    )
    info_message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_INFO_MSG_LIST_MAX_LEN,
        description=("Optional info message, max 45 chars. Maps to COCRDLI INFOMSGI PIC X(45)."),
    )
    error_message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERR_MSG_LIST_MAX_LEN,
        description=("Optional error message, max 78 chars. Maps to COCRDLI ERRMSGI PIC X(78)."),
    )


# ---------------------------------------------------------------------------
# CardDetailResponse — response body for ``GET /cards/{card_num}``
# ---------------------------------------------------------------------------
class CardDetailResponse(BaseModel):
    """Response body for the Card Detail endpoint (F-007).

    Replaces the CICS ``SEND MAP ('COCRDSLA')`` screen refresh that
    terminated the Card Detail transaction in ``COCRDSLC.cbl``. The
    original BMS screen returned six business fields plus two message
    lines, all of which are preserved on the modern JSON response.

    Note that the Card Detail screen splits the 10-character
    ``CARD-EXPIRAION-DATE`` VSAM field (CVACT02Y.cpy) into separate
    display-only month and year components — the day portion is NOT
    displayed on the Detail screen (it is only displayed on the
    Update screen, which surfaces it via :class:`CardUpdateRequest`
    to permit full-date editing).

    Attributes
    ----------
    account_id : str
        11-character owning account ID. Maps to COCRDSL ACCTSIDI
        PIC X(11) and the ``card.acct_id`` column.
    card_number : str
        16-character card number (PAN — primary key). Maps to COCRDSL
        CARDSIDI PIC X(16) and the ``card.card_num`` column.
    embossed_name : str
        Name embossed on the physical card, max 50 characters. Maps
        to COCRDSL CRDNAMEI PIC X(50) and the
        ``card.embossed_name`` column (CVACT02Y.cpy
        CARD-EMBOSSED-NAME PIC X(50)).
    status_code : str
        1-character card active/inactive status ('Y' = active,
        'N' = inactive). Maps to COCRDSL CRDSTCDI PIC X(01) and the
        ``card.active_status`` column (CVACT02Y.cpy
        CARD-ACTIVE-STATUS PIC X(01)).
    expiry_month : str
        2-character expiry month ('01'..'12'). Maps to COCRDSL
        EXPMONI PIC X(02) — the month component extracted from
        the 10-char CARD-EXPIRAION-DATE field.
    expiry_year : str
        4-character expiry year (CCYY, e.g. '2026'). Maps to COCRDSL
        EXPYEARI PIC X(04) — the year component extracted from the
        10-char CARD-EXPIRAION-DATE field.
    info_message : Optional[str]
        Optional 40-character informational message echoed from the
        service layer. Maps to COCRDSL INFOMSGI PIC X(40). Note the
        width differs from the List screen's 45-char INFOMSGI.
    error_message : Optional[str]
        Optional 80-character error message echoed from the service
        layer. Maps to COCRDSL ERRMSGI PIC X(80). Note the width
        differs from the List screen's 78-char ERRMSGI.

    Raises
    ------
    pydantic.ValidationError
        * When any field exceeds its declared ``max_length``.
    """

    # Enables direct construction from SQLAlchemy ORM rows (e.g.
    # ``CardDetailResponse.model_validate(card_row)``), supporting
    # zero-copy conversion at the service layer.
    model_config = ConfigDict(from_attributes=True)

    account_id: str = Field(
        ...,
        max_length=_ACCT_ID_LEN,
        description=("11-char owning account ID. Maps to COCRDSL ACCTSIDI PIC X(11) and card.acct_id."),
    )
    card_number: str = Field(
        ...,
        max_length=_CARD_NUM_LEN,
        description=("16-char card number (PAN — primary key). Maps to COCRDSL CARDSIDI PIC X(16) and card.card_num."),
    )
    embossed_name: str = Field(
        ...,
        max_length=_EMBOSSED_NAME_MAX_LEN,
        description=(
            "Name embossed on the card, max 50 chars. Maps to COCRDSL "
            "CRDNAMEI PIC X(50) and card.embossed_name "
            "(CVACT02Y.cpy CARD-EMBOSSED-NAME)."
        ),
    )
    status_code: str = Field(
        ...,
        max_length=_STATUS_LEN,
        description=(
            "1-char card status ('Y' = active, 'N' = inactive). Maps "
            "to COCRDSL CRDSTCDI PIC X(01) and card.active_status."
        ),
    )
    expiry_month: str = Field(
        ...,
        max_length=_EXPIRY_MM_DD_LEN,
        description=("2-char expiry month ('01'..'12'). Maps to COCRDSL EXPMONI PIC X(02)."),
    )
    expiry_year: str = Field(
        ...,
        max_length=_EXPIRY_YEAR_LEN,
        description=("4-char expiry year (CCYY, e.g. '2026'). Maps to COCRDSL EXPYEARI PIC X(04)."),
    )
    info_message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_INFO_MSG_DETAIL_MAX_LEN,
        description=("Optional info message, max 40 chars. Maps to COCRDSL INFOMSGI PIC X(40)."),
    )
    error_message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERR_MSG_DETAIL_MAX_LEN,
        description=("Optional error message, max 80 chars. Maps to COCRDSL ERRMSGI PIC X(80)."),
    )


# ---------------------------------------------------------------------------
# CardUpdateRequest — request body for ``PUT /cards/{card_num}``
# ---------------------------------------------------------------------------
class CardUpdateRequest(BaseModel):
    """Request body for the Card Update endpoint (F-008).

    Replaces the incoming BMS input fields on the COCRDUP Card Update
    screen that were previously received via CICS RECEIVE MAP
    ('COCRDUPA') in ``COCRDUPC.cbl``. The original COBOL program
    accepted seven mutable business fields on each submission; unlike
    the Card Detail screen, the Update screen additionally surfaces
    the day-of-month portion of the expiry date (``EXPDAYI``) so the
    user may edit the full 10-character CARD-EXPIRAION-DATE.

    Optimistic-concurrency control (matching the original
    ``READ UPDATE`` / ``REWRITE`` CICS pattern in COCRDUPC.cbl) is
    enforced at the service / repository layer against the
    ``card.version`` SQLAlchemy column — it is NOT part of the wire
    contract. A 409 Conflict response is returned if the row has been
    modified since the client last read it.

    Attributes
    ----------
    account_id : str
        11-character owning account ID. Maps to COCRDUP ACCTSIDI
        PIC X(11). The service layer verifies this matches the
        existing ``card.acct_id`` — it is not editable.
    card_number : str
        Exactly 16-character card number (PAN — primary key). Maps to
        COCRDUP CARDSIDI PIC X(16). Not editable — must match the
        path parameter.
    embossed_name : str
        Name to emboss on the card, max 50 characters. Maps to
        COCRDUP CRDNAMEI PIC X(50). Editable field.
    status_code : str
        1-character card active/inactive status ('Y' = active,
        'N' = inactive). Maps to COCRDUP CRDSTCDI PIC X(01).
        Editable field.
    expiry_month : str
        Exactly 2-character expiry month ('01'..'12'). Maps to
        COCRDUP EXPMONI PIC X(02). Editable field. Validated at the
        schema layer.
    expiry_year : str
        Exactly 4-character expiry year (CCYY, e.g. '2026'). Maps to
        COCRDUP EXPYEARI PIC X(04). Editable field.
    expiry_day : str
        Exactly 2-character expiry day ('01'..'31'). Maps to COCRDUP
        EXPDAYI PIC X(02) — present on the Update screen but NOT on
        the Detail screen (which shows only month and year). Editable
        field. Calendar-day validity (e.g. Feb 30) is enforced at
        the service layer, not the schema layer.

    Raises
    ------
    pydantic.ValidationError
        * When ``card_number`` is not exactly 16 characters.
        * When ``status_code`` is not exactly 1 character.
        * When ``expiry_month`` is not one of '01'..'12'.
        * When any field exceeds its declared ``max_length``.
    """

    # Request schemas do NOT set ``from_attributes=True`` because
    # requests always arrive as JSON-decoded dicts (never SQLAlchemy
    # objects) from the REST / GraphQL layer.

    account_id: str = Field(
        ...,
        max_length=_ACCT_ID_LEN,
        description=(
            "11-char owning account ID. Maps to COCRDUP ACCTSIDI "
            "PIC X(11). Not editable — validated by the service "
            "against the existing card.acct_id."
        ),
    )
    card_number: str = Field(
        ...,
        max_length=_CARD_NUM_LEN,
        description=(
            "16-char card number (PAN — primary key). Maps to COCRDUP "
            "CARDSIDI PIC X(16). Not editable — must match the path "
            "parameter."
        ),
    )
    embossed_name: str = Field(
        ...,
        max_length=_EMBOSSED_NAME_MAX_LEN,
        description=("Name to emboss on the card, max 50 chars. Maps to COCRDUP CRDNAMEI PIC X(50)."),
    )
    status_code: str = Field(
        ...,
        max_length=_STATUS_LEN,
        description=("1-char card status ('Y' = active, 'N' = inactive). Maps to COCRDUP CRDSTCDI PIC X(01)."),
    )
    expiry_month: str = Field(
        ...,
        max_length=_EXPIRY_MM_DD_LEN,
        description=("2-char expiry month ('01'..'12'). Maps to COCRDUP EXPMONI PIC X(02)."),
    )
    expiry_year: str = Field(
        ...,
        max_length=_EXPIRY_YEAR_LEN,
        description=("4-char expiry year (CCYY, e.g. '2026'). Maps to COCRDUP EXPYEARI PIC X(04)."),
    )
    expiry_day: str = Field(
        ...,
        max_length=_EXPIRY_MM_DD_LEN,
        description=(
            "2-char expiry day ('01'..'31'). Maps to COCRDUP EXPDAYI PIC X(02) — present on the Update screen only."
        ),
    )

    # ---------------------------------------------------------------
    # Field-level validators
    # ---------------------------------------------------------------
    @field_validator("card_number")
    @classmethod
    def _validate_card_number_exact(cls, value: str) -> str:
        """Enforce exact 16-character card-number format.

        The COBOL ``CARD-NUM PIC X(16)`` domain (CVACT02Y.cpy) and
        the BMS ``CARDSIDI PIC X(16)`` field on COCRDUP.CPY both
        require exactly 16 characters (not "up to 16"). This
        validator rejects any shorter or longer value at the schema
        layer so the service layer can rely on exact-width inputs.

        Parameters
        ----------
        value
            Candidate ``card_number`` from the request body.

        Returns
        -------
        str
            The input ``value`` unchanged (exactly 16 characters).

        Raises
        ------
        ValueError
            When ``value`` is not exactly 16 characters long.
        """
        if not isinstance(value, str):
            raise ValueError(f"card_number must be a string; got {type(value).__name__}")
        if len(value) != _CARD_NUM_LEN:
            raise ValueError(f"card_number must be exactly {_CARD_NUM_LEN} characters; got {len(value)} characters")
        return value

    @field_validator("status_code")
    @classmethod
    def _validate_status_code_exact(cls, value: str) -> str:
        """Enforce exact 1-character status-code format.

        The COBOL ``CARD-ACTIVE-STATUS PIC X(01)`` domain (CVACT02Y
        .cpy) and the BMS ``CRDSTCDI PIC X(01)`` field on COCRDUP.CPY
        both require exactly 1 character. This validator rejects any
        value that is not exactly one character (e.g. empty string or
        multi-character input).

        Parameters
        ----------
        value
            Candidate ``status_code`` from the request body.

        Returns
        -------
        str
            The input ``value`` unchanged (exactly 1 character).

        Raises
        ------
        ValueError
            When ``value`` is not exactly 1 character long.
        """
        if not isinstance(value, str):
            raise ValueError(f"status_code must be a string; got {type(value).__name__}")
        if len(value) != _STATUS_LEN:
            raise ValueError(f"status_code must be exactly {_STATUS_LEN} character; got {len(value)} characters")
        return value

    @field_validator("expiry_month")
    @classmethod
    def _validate_expiry_month_range(cls, value: str) -> str:
        """Enforce expiry month in '01'..'12' (calendar months).

        The BMS ``EXPMONI PIC X(02)`` field on COCRDUP.CPY accepts
        any two characters, but the CICS application logic in
        COCRDUPC.cbl rejected any month outside the ``01``..``12``
        range. This validator enforces the same check at the schema
        layer, rejecting values like '00', '13', '1', 'AA', or any
        non-digit two-character string.

        Parameters
        ----------
        value
            Candidate ``expiry_month`` from the request body.

        Returns
        -------
        str
            The input ``value`` unchanged (exactly 2 digits in the
            range '01'..'12').

        Raises
        ------
        ValueError
            * When ``value`` is not exactly 2 characters long.
            * When ``value`` contains non-digit characters.
            * When the numeric value is not between 1 and 12
              (inclusive).
        """
        if not isinstance(value, str):
            raise ValueError(f"expiry_month must be a string; got {type(value).__name__}")
        if len(value) != _EXPIRY_MM_DD_LEN:
            raise ValueError(
                f"expiry_month must be exactly {_EXPIRY_MM_DD_LEN} characters; got {len(value)} characters"
            )
        if not value.isdigit():
            raise ValueError("expiry_month must contain only digits (0-9); got a non-numeric character")
        month_int = int(value)
        if month_int < 1 or month_int > 12:
            raise ValueError(f"expiry_month must be in the range '01'..'12'; got '{value}'")
        return value


# ---------------------------------------------------------------------------
# CardUpdateResponse — response body for ``PUT /cards/{card_num}``
# ---------------------------------------------------------------------------
class CardUpdateResponse(CardDetailResponse):
    """Response body for the Card Update endpoint (F-008).

    Inherits every field from :class:`CardDetailResponse` — the
    original CICS ``SEND MAP ('COCRDUPA')`` screen refresh in
    ``COCRDUPC.cbl`` returned the same business fields as the Detail
    screen, with the ``INFOMSGI`` message typically containing the
    success confirmation ("Card updated successfully") and the
    ``ERRMSGI`` field containing any failure text.

    The modern API emits the same payload shape: on a successful
    200 OK response the service layer populates ``info_message``
    with a confirmation string and leaves ``error_message`` as
    ``None``. On a validation or concurrency failure the service
    layer raises an HTTP error (4xx / 5xx) rather than returning
    this schema with ``error_message`` populated — the ``error_message``
    field is retained for parity with the COBOL screen but the
    primary error-signaling mechanism is the HTTP status code.

    This class intentionally introduces no new fields; it exists as
    a named type so the OpenAPI / GraphQL schema distinguishes the
    "after update" response from the "read detail" response at the
    documentation layer even though the two payload shapes are
    identical.

    See Also
    --------
    CardDetailResponse — the parent class from which all fields are
    inherited.
    """

    # No additional fields — CardUpdateResponse is structurally
    # identical to CardDetailResponse and inherits ``model_config =
    # ConfigDict(from_attributes=True)``. The empty class body is
    # intentional.


# ---------------------------------------------------------------------------
# Public export list
# ---------------------------------------------------------------------------
# Explicit ``__all__`` declaration — only the six schema classes are
# part of the public API surface of this module. Private constants
# (leading underscore) are intentionally excluded; they are an
# implementation detail backing the ``max_length`` constraints and are
# not meant to be referenced by the service / router layers.
__all__ = [
    "CardListRequest",
    "CardListItem",
    "CardListResponse",
    "CardDetailResponse",
    "CardUpdateRequest",
    "CardUpdateResponse",
]
