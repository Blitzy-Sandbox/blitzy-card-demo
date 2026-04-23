# ============================================================================
# CardDemo — Unit tests for card_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COCRDLIC.cbl      — CICS card list program (F-006, ~1,459
#                                 lines). Drives the paginated
#                                 STARTBR/READNEXT browse cursor over the
#                                 CARDDAT VSAM KSDS SEVEN rows at a time
#                                 and populates the COCRDLI BMS map. Note
#                                 the page size is 7 (NOT 10) — this is
#                                 the single most important behavioral
#                                 invariant for the list tests below.
#   * app/cbl/COCRDSLC.cbl      — CICS card detail program (F-007, ~887
#                                 lines). Performs a keyed
#                                 ``EXEC CICS READ DATASET('CARDDAT')
#                                 RIDFLD(CARD-NUM)`` to fetch the full
#                                 150-byte CVACT02Y CARD-RECORD layout
#                                 and populates the CCRDSLAI BMS map.
#   * app/cbl/COCRDUPC.cbl      — CICS card update program (F-008, ~1,560
#                                 lines). Performs ``READ UPDATE`` /
#                                 ``REWRITE`` with change-detection
#                                 against a pre-read snapshot to detect
#                                 concurrent modification — the direct
#                                 COBOL analogue of SQLAlchemy
#                                 optimistic-concurrency control.
#   * app/cpy/CVACT02Y.cpy      — CARD-RECORD layout (150 bytes)
#                                 declaring the 6 business fields for
#                                 all three endpoints. Critical PIC
#                                 declarations: CARD-NUM PIC X(16),
#                                 CARD-ACCT-ID PIC 9(11), CARD-EMBOSSED-
#                                 NAME PIC X(50), CARD-ACTIVE-STATUS
#                                 PIC X(01).
#   * app/cpy/CVACT03Y.cpy      — CARD-XREF-RECORD layout (50 bytes) —
#                                 only referenced indirectly for
#                                 structural context.
#   * app/cpy-bms/COCRDLI.CPY   — Card List BMS symbolic map with 7
#                                 repeated row groups (CRDSELn,
#                                 ACCTNOn, CRDNUMn, CRDSTSn for
#                                 n ∈ {1..7}).
#   * app/cpy-bms/COCRDSL.CPY   — Card Detail BMS symbolic map (8
#                                 output fields: ACCTSIDI, CARDSIDI,
#                                 CRDNAMEI, CRDSTCDI, EXPMONI,
#                                 EXPYEARI, INFOMSGI, ERRMSGI).
#   * app/cpy-bms/COCRDUP.CPY   — Card Update BMS symbolic map —
#                                 identical to COCRDSL plus EXPDAYI
#                                 PIC X(02) for full-date editing.
# ----------------------------------------------------------------------------
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
"""Unit tests for :mod:`src.api.routers.card_router`.

These tests validate the HTTP surface of Features F-006 (card list),
F-007 (card detail view), and F-008 (card update with optimistic
concurrency) that replace the CICS programs ``COCRDLIC.cbl``,
``COCRDSLC.cbl``, and ``COCRDUPC.cbl`` respectively. They operate
purely at the router layer: :class:`CardService` is patched at the
router's import site so every test exercises parameter binding,
dependency wiring, response serialization, and error-routing logic
WITHOUT touching the database or service internals.

COBOL → Python verification matrix
----------------------------------
============================================  ==============================
COBOL construct                               HTTP equivalent asserted here
============================================  ==============================
COCRDLIC STARTBR + READNEXT (7 iterations)    GET /cards → 7/page (NOT 10)
COCRDLIC ACCTSIDI PIC X(11) filter            GET /cards?account_id=...
COCRDLIC CARDSIDI PIC X(16) filter            GET /cards?card_number=...
COCRDLIC COCRDLI CRDNUMn/ACCTNOn (7 slots)    response.cards[0..6]
COCRDLIC "NO RECORDS FOUND" (L122)            200 OK + error_message via svc
COCRDSLC READ DATASET('CARDDAT')              GET /cards/{card_num}
COCRDSLC RESP=NOTFND "Did not find cards..."  400 Bad Request + detail
COCRDUPC READ UPDATE / REWRITE + snapshot     PUT /cards/{card_num}
COCRDUPC "Record changed by some one else"    400 Bad Request + detail
COCRDUPC "Changes committed to database"      200 OK + info_message
_CARD_NUM_REGEX r"^[0-9]{16}$" (router L109)  422 UNPROCESSABLE on malformed
============================================  ==============================

Mocking strategy
----------------
Per the router-unit-test convention established in ``test_bill_router``
and ``test_transaction_router`` and the AAP's isolation rules, every
test patches :class:`CardService` at the *router's import site*:

.. code-block:: python

    with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
        mock_instance = mock_service_class.return_value
        mock_instance.list_cards = AsyncMock(return_value=...)
        ...

Patching at the import site
(``src.api.routers.card_router.CardService``) rather than the
definition site (``src.api.services.card_service.CardService``)
ensures that the router's local binding is replaced — the definition
site may be imported by other modules (batch jobs, admin tools,
integration tests) that must not be affected.

HTTP status-code expectations
-----------------------------
================================  ==============  ========================
Endpoint                          Outcome         HTTP status
================================  ==============  ========================
GET /cards                        success         200 OK
GET /cards                        service error   400 Bad Request
GET /cards                        unauth          401 / 403
GET /cards/{card_num}             success         200 OK
GET /cards/{card_num}             not found       404 Not Found
GET /cards/{card_num}             bad path        422 Unprocessable
GET /cards/{card_num}             unauth          401 / 403
PUT /cards/{card_num}             success         200 OK
PUT /cards/{card_num}             not found       404 Not Found
PUT /cards/{card_num}             concurrency     409 Conflict
PUT /cards/{card_num}             validation      422 Unprocessable
PUT /cards/{card_num}             unauth          401 / 403
================================  ==============  ========================

Critical behavioral invariants
------------------------------
1. **Page size is 7** (NOT 10). The original COCRDLI.CPY BMS layout
   has EXACTLY 7 repeated row groups (CRDSEL1..CRDSEL7,
   ACCTNO1..ACCTNO7, CRDNUM1..CRDNUM7, CRDSTS1..CRDSTS7), and the
   COCRDLIC program performs STARTBR + 7 × READNEXT for each
   forward scroll. This is the single most important invariant for
   F-006.
2. **Business errors route to HTTP 400 / 404 / 409 based on the
   service's ``error_message`` constant.** The router looks up the
   exact error-message string in the module-level mapping
   :data:`src.api.routers.card_router._ERROR_MESSAGE_STATUS_MAP`
   and raises :class:`HTTPException` with the registered status
   code. The mapping is:

   * ``_MSG_DETAIL_NOT_FOUND`` / ``_MSG_UPDATE_NOT_FOUND``
     (both carry the literal
     ``"Did not find cards for this search condition"`` — card_service
     defines the symbols separately for readability / future
     divergence, but they collapse to a single dict entry)
     -> HTTP **404 Not Found** (RFC 7231 §6.5.4).
   * ``_MSG_UPDATE_STALE``
     (``"Record changed by some one else. Please review"``)
     -> HTTP **409 Conflict** (RFC 7231 §6.5.8 — optimistic
     concurrency conflict).
   * Any other populated ``error_message`` (list-path "no records",
     generic I/O failure, validation failures, etc.) falls through to
     the default HTTP **400 Bad Request** bucket.

   The ``list_cards`` handler keeps the uniform HTTP 400 route for
   ALL business errors because no not-found / concurrency literals
   reach it — the list path only ever surfaces ``_MSG_LIST_NO_RECORDS``
   / ``_MSG_LIST_NO_MORE`` / generic lookup errors.
   This mapping-driven pattern differs from ``transaction_router``'s
   substring-based approach and from ``user_router``'s typed-
   exception approach; all three are intentional per-feature designs.
3. **Card number is exactly 16 digits**. The path regex
   ``^[0-9]{16}$`` rejects any malformed ``card_num`` path parameter
   with FastAPI's automatic HTTP 422 *before* the service runs.
4. **Authentication is enforced by middleware** (not by the
   ``get_current_user`` dependency alone). The :class:`JWTAuthMiddleware`
   runs BEFORE FastAPI dependency resolution and returns an
   ABEND-DATA-shaped 401 response (with ``WWW-Authenticate: Bearer``
   header per RFC 7235) for any request to a non-public path that
   lacks a valid bearer token. This is why the ``*_requires_auth``
   tests can assert HTTP 401 deterministically.

Fixtures used
-------------
The following fixtures are sourced from ``tests/conftest.py`` and
injected per-test by pytest:

* ``client`` — :class:`httpx.AsyncClient` pre-configured with a
  regular-user JWT Authorization header and bound to a fresh
  ``test_app`` instance. Used by every successful-path / business-
  failure / validation-failure test.
* ``test_app`` — the bare :class:`fastapi.FastAPI` app without the
  JWT header. Used by the three ``*_requires_auth`` tests to build a
  throwaway :class:`AsyncClient` with no Authorization header, which
  exercises the middleware's 401 path.
* ``db_session`` — the SAVEPOINT-scoped :class:`AsyncSession` wired
  into the ``get_db`` override in ``test_app``. Not touched directly
  by these tests (all DB work is mocked) but part of the implicit
  contract.

See Also
--------
* :mod:`src.api.routers.card_router` — unit under test.
* :mod:`src.api.services.card_service` — the mocked collaborator.
* :mod:`src.shared.schemas.card_schema` — request/response Pydantic
  contracts.
* ``tests/unit/test_routers/test_transaction_router.py`` — reference
  template for list/detail router unit tests.
* ``tests/unit/test_routers/test_bill_router.py`` — reference template
  for update/mutation router unit tests.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.shared.schemas.card_schema import (
    CardDetailResponse,
    CardListItem,
    CardListResponse,
    CardUpdateResponse,
)

# ---------------------------------------------------------------------------
# pytest marker: every test in this module is a router-layer unit test.
# The ``unit`` mark is collected by pyproject.toml's pytest configuration
# and allows CI to run unit tests in isolation from the slower integration
# and e2e layers. All async tests use pytest-asyncio (auto mode).
# ---------------------------------------------------------------------------
pytestmark = pytest.mark.unit


# ===========================================================================
# Module-level test constants
# ===========================================================================
# Two categories live here:
#
#   1. Test-infrastructure constants — the expected user ID that the
#      ``test_app`` fixture's ``get_current_user`` override emits, and
#      the import-site patch target that ``unittest.mock.patch``
#      rewires for every test. These MUST stay in sync with
#      ``tests/conftest.py``'s ``_fake_get_current_user`` and with
#      ``src/api/routers/card_router.py``'s ``from
#      src.api.services.card_service import CardService`` statement.
#
#   2. COBOL-exact, byte-for-byte test-data constants — including
#      domain-business invariants (page size = 7), the seven
#      service-layer error/info messages lifted directly from the
#      original COBOL source files, and realistic sample field values
#      for CARD-RECORD fields.
#
# Every string here that corresponds to a COBOL literal is documented
# with the source file and line number so that a reviewer can jump
# straight to the COBOL program to verify byte-for-byte parity. Any
# drift between these constants and the service layer is a failure
# mode that breaks production clients parsing ``detail`` strings.
# ---------------------------------------------------------------------------

# -- Test-infrastructure constants -----------------------------------------

# Expected authenticated user ID emitted by the conftest test_app
# fixture's ``get_current_user`` override. Matches the ``user_id``
# attribute on the injected ``CurrentUser`` dataclass and the ``sub``
# claim of the JWT token minted by ``create_test_token``.
_EXPECTED_USER_ID: str = "TESTUSER"

# Fully-qualified patch target for :class:`CardService`. We patch at
# the IMPORT SITE (card_router) rather than the DEFINITION SITE
# (card_service) because the router imports the class with
# ``from src.api.services.card_service import CardService``. A test
# that patched the definition-site would have no effect because the
# router's local binding still references the real class. This
# invariant MUST match the actual ``import`` line in the router and
# is verified every time a test patches it.
_CARD_SERVICE_PATCH_TARGET: str = "src.api.routers.card_router.CardService"

# -- Domain-business invariants --------------------------------------------

# The card list page size — EXACTLY seven rows, NOT the more common
# ten. This is a foundational invariant of Feature F-006 that
# originates in two places in the original COBOL codebase:
#
#   * The COCRDLI BMS map (app/cpy-bms/COCRDLI.CPY) defines SEVEN
#     discrete row slots — CRDSEL1..CRDSEL7, ACCTNO1..ACCTNO7,
#     CRDNUM1..CRDNUM7, CRDSTS1..CRDSTS7 — one for each on-screen
#     row.
#   * The COCRDLIC.cbl ``1000-SEND-MAP`` paragraph iterates the
#     ``WS-CARDS-PER-PAGE`` work-area field set to ``+7`` and calls
#     ``STARTBR`` / ``READNEXT`` exactly seven times to fill those
#     slots.
#
# Any change to this value would require a coordinated change to the
# BMS map, the router, the service layer, and this constant — and
# would break every deployed client that assumes the 7-per-page
# semantics. The strict assertion below ensures test drift is
# impossible without a simultaneous change to this constant.
_CARDS_PER_PAGE: int = 7

# -- COBOL-exact service-layer message constants ---------------------------

# COCRDLIC.cbl L122 (``MOVE 'NO RECORDS FOUND FOR THIS SEARCH
# CONDITION.' TO WS-ERR-MESSAGE``) — the empty-list path emitted by
# the original COBOL program when STARTBR returns DFHRESP(NOTFND) or
# the READNEXT loop completes without any matching rows. The trailing
# period is part of the literal and MUST be preserved.
_MSG_LIST_NO_RECORDS: str = "NO RECORDS FOUND FOR THIS SEARCH CONDITION."

# COCRDLIC.cbl L1219 and L1239 (``MOVE 'NO MORE RECORDS TO SHOW' TO
# WS-INFO-MSG``) — the end-of-list info message emitted when the
# user has browsed past the final page of available records. Used
# in tests that exercise pagination-end semantics.
_MSG_LIST_NO_MORE: str = "NO MORE RECORDS TO SHOW"

# COCRDSLC.cbl L154 (``MOVE 'Did not find cards for this search
# condition' TO WS-ERR-MESSAGE``) — the detail-lookup not-found
# message. The same literal appears in COCRDUPC.cbl L204 as the
# pre-UPDATE existence check's not-found branch. Note the mixed-case
# style differs from the LIST version ("NO RECORDS FOUND") — these
# are COBOL source-code divergences that MUST be preserved
# byte-for-byte to maintain parity with the COBOL-era help text.
_MSG_DETAIL_NOT_FOUND: str = "Did not find cards for this search condition"

# COCRDSLC.cbl L156 and COCRDUPC.cbl L212 (``MOVE 'Error reading
# Card Data File' TO WS-ERR-MESSAGE``) — the detail-lookup
# generic-error message. Covers any DFHRESP value other than NORMAL
# or NOTFND on the CARDDAT READ.
_MSG_DETAIL_LOOKUP_ERROR: str = "Error reading Card Data File"

# COCRDUPC.cbl L169 (``MOVE 'Changes committed to database' TO
# WS-INFO-MSG``) — the successful-update success banner. Emitted
# immediately after the ``EXEC CICS REWRITE`` that commits the new
# CARD-RECORD field values.
_MSG_UPDATE_SUCCESS: str = "Changes committed to database"

# COCRDUPC.cbl L208 (``MOVE 'Record changed by some one else. Please
# review' TO WS-ERR-MESSAGE``) — the optimistic-concurrency-conflict
# error message. Triggered when the ``9300-CHECK-CHANGE-IN-REC``
# paragraph detects a mismatch between the hidden old-value fields
# on the incoming COMMAREA and the freshly-read CARDDAT record
# (indicating another user modified the record between the initial
# SELECT and this UPDATE). NOTE the mixed-case "some one else"
# (two words) — this is the exact COBOL source, not a typo.
_MSG_UPDATE_CONCURRENCY: str = "Record changed by some one else. Please review"

# COCRDUPC.cbl L210 (``MOVE 'Update of record failed' TO
# WS-ERR-MESSAGE``) — the generic update-failure message. Emitted
# when the REWRITE returns a DFHRESP value other than NORMAL.
_MSG_UPDATE_FAILED: str = "Update of record failed"

# -- COBOL-realistic test-data constants -----------------------------------
#
# These values are intentionally plausible against the seed data
# loaded by ``db/migrations/V3__seed_data.sql``:
#
#   * ``_TEST_CARD_NUM`` is a 16-digit numeric string that would
#     pass the ``_CARD_NUM_REGEX`` path-pattern on the router
#     (``^[0-9]{16}$``). The first 6 digits ``411111`` are Visa's
#     test-BIN prefix and widely used in financial-system test
#     fixtures; the last 10 digits are filler.
#   * ``_TEST_ACCT_ID`` is an 11-digit numeric string (PIC 9(11))
#     matching the minimum-width pattern used throughout the
#     original COBOL (CARD-ACCT-ID, CVACT03Y XREF-ACCT-ID). The
#     specific value ``00000000001`` is the first seeded account in
#     V3__seed_data.sql.
#   * ``_TEST_EMBOSSED_NAME`` is a typical cardholder name
#     (uppercase) fitting in CVACT02Y CARD-EMBOSSED-NAME PIC X(50).
#   * ``_TEST_STATUS_CODE = "Y"`` is the active-status value used
#     for healthy cards (CVACT02Y CARD-ACTIVE-STATUS PIC X(01)).
#   * ``_TEST_EXPIRY_MONTH`` / ``_TEST_EXPIRY_YEAR`` /
#     ``_TEST_EXPIRY_DAY`` together form a plausible CVACT02Y
#     CARD-EXPIRAION-DATE PIC X(10) value — note the mis-spelling
#     "EXPIRAION" is in the original COBOL source, not a typo here.

_TEST_CARD_NUM: str = "4111111111111111"
_TEST_ACCT_ID: str = "00000000001"
_TEST_EMBOSSED_NAME: str = "JOHN Q PUBLIC"
_TEST_STATUS_CODE: str = "Y"
_TEST_EXPIRY_MONTH: str = "12"
_TEST_EXPIRY_YEAR: str = "2028"
_TEST_EXPIRY_DAY: str = "31"



# ---------------------------------------------------------------------------
# Response-builder helpers
# ---------------------------------------------------------------------------
# These helpers assemble fully-populated :class:`CardListResponse`,
# :class:`CardDetailResponse`, and :class:`CardUpdateResponse`
# instances with sensible defaults for each test scenario. They are
# the Python analogues of the COBOL ``MOVE ... TO CCRDLIAO`` /
# ``MOVE ... TO CCRDSLAO`` / ``MOVE ... TO CCRDUPAO`` stanzas that
# populate the symbolic-map output area before ``SEND MAP``.
#
# Each helper accepts keyword overrides for the fields that a given
# test needs to mutate (e.g. ``error_message=_MSG_LIST_NO_RECORDS``
# for the empty-list path, or ``account_id=_TEST_ACCT_ID`` for the
# filter-pass-through path), while leaving the remaining fields at
# their deterministic defaults so the tests stay focused on the
# single attribute under test.
# ---------------------------------------------------------------------------
def _make_list_response(
    count: int = 5,
    page_number: int = 1,
    total_pages: int | None = None,
    info_message: str | None = None,
    error_message: str | None = None,
    account_id: str = _TEST_ACCT_ID,
    card_number_prefix: str = "411111111111",
) -> CardListResponse:
    """Build a :class:`CardListResponse` for list-endpoint tests.

    Constructs ``count`` synthetic :class:`CardListItem` records,
    each with a unique 16-digit card number derived from
    ``card_number_prefix`` (12 chars) + a 4-digit zero-padded row
    index. This matches the COBOL ``MOVE CARD-NUM TO CRDNUMnI``
    pattern in ``COCRDLIC.cbl`` where each of the seven screen slots
    receives the corresponding ``card_num`` from the VSAM browse
    cursor.

    Parameters
    ----------
    count : int
        Number of :class:`CardListItem` rows to build. Default 5;
        pass 7 for a full page or 0 for the empty-list test. Values
        > 7 are accepted by the helper (the page-cap invariant is
        asserted by the calling test, not enforced here) to permit
        deliberately-wrong mock responses when debugging.
    page_number : int
        1-based page number echoed in the response. Default 1.
    total_pages : Optional[int]
        Total number of pages across ALL filter results. If ``None``
        (default), ``total_pages`` is set to ``1`` when ``count > 0``
        and ``0`` when ``count == 0`` (single-page / empty scenario);
        pass an explicit value > 1 for pagination tests.
    info_message : Optional[str]
        Info-message channel, max 45 chars per COCRDLI INFOMSGI PIC
        X(45). Default None.
    error_message : Optional[str]
        Error-surfacing channel, max 78 chars per COCRDLI ERRMSGI PIC
        X(78). Default ``None`` — the success path returns
        ``error_message=None``. Populate with ``_MSG_LIST_NO_RECORDS``
        to simulate the empty-filter scenario, which the router
        converts into HTTP 400.
    account_id : str
        11-char account_id used for every synthesized row. Default
        ``_TEST_ACCT_ID``. Overridable per-test for
        filter-pass-through assertions.
    card_number_prefix : str
        12-char prefix used to construct each row's 16-char card_num.
        Default ``"411111111111"`` (standard Visa test prefix).

    Returns
    -------
    CardListResponse
        A fully-populated list response suitable as an
        :class:`AsyncMock` return_value for
        :meth:`CardService.list_cards`.
    """
    if total_pages is None:
        total_pages = 1 if count > 0 else 0
    if len(card_number_prefix) != 12:
        # Defensive assertion — the helper guarantees a 16-char
        # card_num output, which requires an exactly-12-char prefix
        # plus a 4-digit suffix. Any drift here would produce
        # Pydantic-validation failures on the CardListItem itself.
        raise ValueError(
            f"card_number_prefix must be exactly 12 chars; "
            f"got {len(card_number_prefix)}"
        )
    items: list[CardListItem] = [
        CardListItem(
            # 1-char selection flag — blank on read-only list
            # responses (matches COCRDLI CRDSELnI PIC X(01)).
            selected=" ",
            # 11-char owning account ID — shared across all rows
            # for filter-pass-through tests that pin on a specific
            # acct_id.
            account_id=account_id,
            # 16-char card_num — prefix + 4-digit zero-padded index
            # ensures uniqueness across rows while preserving the
            # 16-char PIC X(16) domain.
            card_number=f"{card_number_prefix}{i + 1:04d}",
            # 1-char status — all synthesized rows are active.
            # Overridden at the per-test level if status-specific
            # assertions are needed.
            card_status="Y",
        )
        for i in range(count)
    ]
    return CardListResponse(
        cards=items,
        page_number=page_number,
        total_pages=total_pages,
        info_message=info_message,
        error_message=error_message,
    )


def _make_detail_response(
    card_number: str = _TEST_CARD_NUM,
    account_id: str = _TEST_ACCT_ID,
    embossed_name: str = _TEST_EMBOSSED_NAME,
    status_code: str = _TEST_STATUS_CODE,
    expiry_month: str = _TEST_EXPIRY_MONTH,
    expiry_year: str = _TEST_EXPIRY_YEAR,
    info_message: str | None = None,
    error_message: str | None = None,
) -> CardDetailResponse:
    """Build a :class:`CardDetailResponse` for detail-endpoint tests.

    Populates all six business fields of the detail schema plus the
    two message channels with deterministic test data. Callers
    override ``card_number`` to exercise the path parameter,
    ``status_code`` for status-specific assertions, and
    ``error_message`` (combined with realistic default values for
    the other fields, as the service layer MUST populate them even
    on the error path per the "response-message" pattern) for the
    not-found / lookup-failure paths.

    Note that the COBOL COCRDSLC program populated BOTH the
    account/card fields AND the error message on a failure — it did
    not clear the business fields. The Python port preserves this
    behavior.

    Parameters
    ----------
    card_number : str
        16-char card PAN. Default ``_TEST_CARD_NUM``.
    account_id : str
        11-char owning account ID. Default ``_TEST_ACCT_ID``.
    embossed_name : str
        Embossed name, max 50 chars. Default ``_TEST_EMBOSSED_NAME``.
    status_code : str
        1-char active/inactive status ('Y'/'N'). Default 'Y'.
    expiry_month : str
        2-char month ('01'..'12'). Default '12'.
    expiry_year : str
        4-char year (CCYY). Default '2028'.
    info_message : Optional[str]
        Max 40 chars per COCRDSL INFOMSGI PIC X(40). Default None.
    error_message : Optional[str]
        Max 80 chars per COCRDSL ERRMSGI PIC X(80). Default ``None``;
        pass ``_MSG_DETAIL_NOT_FOUND`` for the 404-equivalent path or
        ``_MSG_DETAIL_LOOKUP_ERROR`` for the catch-all path.

    Returns
    -------
    CardDetailResponse
        A fully-populated detail response suitable as an
        :class:`AsyncMock` return_value for
        :meth:`CardService.get_card_detail`.
    """
    return CardDetailResponse(
        account_id=account_id,
        card_number=card_number,
        embossed_name=embossed_name,
        status_code=status_code,
        expiry_month=expiry_month,
        expiry_year=expiry_year,
        info_message=info_message,
        error_message=error_message,
    )


def _make_update_response(
    card_number: str = _TEST_CARD_NUM,
    account_id: str = _TEST_ACCT_ID,
    embossed_name: str = _TEST_EMBOSSED_NAME,
    status_code: str = _TEST_STATUS_CODE,
    expiry_month: str = _TEST_EXPIRY_MONTH,
    expiry_year: str = _TEST_EXPIRY_YEAR,
    info_message: str | None = _MSG_UPDATE_SUCCESS,
    error_message: str | None = None,
) -> CardUpdateResponse:
    """Build a :class:`CardUpdateResponse` for update-endpoint tests.

    Structurally identical to :class:`CardDetailResponse` (inheritance),
    but defaults ``info_message`` to the
    ``"Changes committed to database"`` success string so the happy-
    path test can verify the success sentinel without additional
    setup. Failure-path tests override ``info_message=None`` and set
    ``error_message`` to the appropriate COBOL-exact string.

    Parameters
    ----------
    card_number : str
        16-char card PAN. Default ``_TEST_CARD_NUM``.
    account_id : str
        11-char owning account ID. Default ``_TEST_ACCT_ID``.
    embossed_name : str
        Embossed name, max 50 chars. Default ``_TEST_EMBOSSED_NAME``.
    status_code : str
        1-char status. Default 'Y'.
    expiry_month : str
        2-char month. Default '12'.
    expiry_year : str
        4-char year. Default '2028'.
    info_message : Optional[str]
        Max 40 chars. Default ``_MSG_UPDATE_SUCCESS`` (happy path
        sentinel) — override to None on failure-path tests.
    error_message : Optional[str]
        Max 80 chars. Default ``None``; populate with
        ``_MSG_DETAIL_NOT_FOUND`` for the not-found path,
        ``_MSG_UPDATE_CONCURRENCY`` for the optimistic-concurrency
        conflict path, or ``_MSG_UPDATE_FAILED`` for the generic
        update-failure path.

    Returns
    -------
    CardUpdateResponse
        A fully-populated update response suitable as an
        :class:`AsyncMock` return_value for
        :meth:`CardService.update_card`.
    """
    return CardUpdateResponse(
        account_id=account_id,
        card_number=card_number,
        embossed_name=embossed_name,
        status_code=status_code,
        expiry_month=expiry_month,
        expiry_year=expiry_year,
        info_message=info_message,
        error_message=error_message,
    )


def _make_update_request_body(
    card_number: str = _TEST_CARD_NUM,
    account_id: str = _TEST_ACCT_ID,
    embossed_name: str = _TEST_EMBOSSED_NAME,
    status_code: str = _TEST_STATUS_CODE,
    expiry_month: str = _TEST_EXPIRY_MONTH,
    expiry_year: str = _TEST_EXPIRY_YEAR,
    expiry_day: str = _TEST_EXPIRY_DAY,
) -> dict[str, Any]:
    """Build a JSON request body for ``PUT /cards/{card_num}``.

    Assembles a dictionary with all 7 fields that
    :class:`CardUpdateRequest` accepts. Defaults produce a body that
    passes every schema-layer validator (exact lengths for
    ``card_number`` / ``status_code`` / ``expiry_month``, range check
    for ``expiry_month`` ∈ '01'..'12', etc.), so validation-failure
    tests need only override the single field under test.

    The request body is deliberately built as a *dict* rather than a
    :class:`CardUpdateRequest` Pydantic instance so that
    validation-failure tests can send structurally invalid payloads
    (e.g. 3-character ``status_code``, ``expiry_month="13"``) that
    would be rejected by the Pydantic model's constructor. The
    router-layer validation is then exercised end-to-end via the
    HTTP request.

    Parameters
    ----------
    card_number : str
        16-char card PAN. Default ``_TEST_CARD_NUM``.
    account_id : str
        11-char owning account ID. Default ``_TEST_ACCT_ID``.
    embossed_name : str
        Name to emboss, max 50 chars. Default ``_TEST_EMBOSSED_NAME``.
    status_code : str
        1-char status ('Y'/'N'). Default 'Y'.
    expiry_month : str
        2-char month ('01'..'12'). Default '12'.
    expiry_year : str
        4-char year (CCYY). Default '2028'.
    expiry_day : str
        2-char day ('01'..'31'). Default '31'. Present on the Update
        screen only (absent from the Detail schema).

    Returns
    -------
    dict[str, Any]
        A dictionary suitable as the ``json=`` keyword argument to
        :meth:`httpx.AsyncClient.put`.
    """
    return {
        "account_id": account_id,
        "card_number": card_number,
        "embossed_name": embossed_name,
        "status_code": status_code,
        "expiry_month": expiry_month,
        "expiry_year": expiry_year,
        "expiry_day": expiry_day,
    }



# ============================================================================
# TestCardList
# ----------------------------------------------------------------------------
# Exercises ``GET /cards`` — replaces the CICS ``COCRDLIC`` program
# (Feature F-006) that used ``EXEC CICS STARTBR / READNEXT`` to
# browse the CARDDAT VSAM KSDS SEVEN rows at a time and populate the
# COCRDLI BMS map. Tests cover the six required scenarios from the
# AAP:
#
#   1. Happy path (up to 7 rows returned, HTTP 200).
#   2. Account-ID filter pass-through (``account_id=`` query parameter
#      reaches :class:`CardListRequest.account_id`).
#   3. Card-number filter pass-through (``card_number=`` query
#      parameter reaches :class:`CardListRequest.card_number`).
#   4. Pagination (page 2 of a multi-page result set).
#   5. Empty list (no rows for the given filter).
#   6. Unauthenticated request → 401/403.
# ============================================================================
class TestCardList:
    """Tests for the ``GET /cards`` endpoint (Feature F-006)."""

    # ------------------------------------------------------------------
    # 1. Successful list — happy path, 7 rows per page
    # ------------------------------------------------------------------
    async def test_list_cards_success(self, client: AsyncClient) -> None:
        """Successful card list returns HTTP 200 with up to 7 rows.

        Mirrors the full ``COCRDLIC.cbl`` ``PROCESS-ENTER-KEY`` →
        ``STARTBR CARDDAT`` → 7 × ``READNEXT`` → ``SEND MAP
        COCRDLIA`` happy path. The original program performed the
        VSAM browse in a single CICS transaction, populating the
        SEVEN ``CRDSELnI``/``ACCTNOnI``/``CRDNUMnI``/``CRDSTSnI``
        slot groups on the COCRDLI BMS map (one slot per row).

        In the Python port, :meth:`CardService.list_cards` executes
        a SQLAlchemy ``LIMIT 7 OFFSET (page_number-1)*7`` query and
        returns a :class:`CardListResponse` with
        ``cards=[...]``, ``page_number=1``, ``total_pages=N``. The
        router forwards it unchanged.

        Assertions:
            * HTTP 200 OK.
            * Response body contains ``cards``, ``page_number``,
              ``total_pages``, ``info_message``, ``error_message``.
            * ``cards`` is a list with at most 7 items (the
              COBOL per-page cap — this is the single most important
              invariant for F-006).
            * Each item has the 4 required fields: ``selected``,
              ``account_id`` (11), ``card_number`` (16),
              ``card_status`` (1).
            * ``page_number`` echoes the requested page (default 1).
            * ``error_message`` is None on success.
            * :meth:`CardService.list_cards` was called exactly once
              with a :class:`CardListRequest` carrying the same
              parameters.
            * The :class:`CardService` constructor was invoked (with
              the overridden db session).
        """
        # Build a full-page response (count=7, the per-page cap for F-006).
        mock_response = _make_list_response(
            count=_CARDS_PER_PAGE,
            page_number=1,
            total_pages=1,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_cards = AsyncMock(return_value=mock_response)

            response = await client.get("/cards")

        # HTTP 200 — the list endpoint's default success status.
        assert response.status_code == status.HTTP_200_OK, (
            f"Successful list MUST return HTTP 200 OK; "
            f"got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Required envelope fields per :class:`CardListResponse`.
        for required_field in ("cards", "page_number", "total_pages"):
            assert required_field in body, (
                f"Response MUST include ``{required_field}``; got {sorted(body.keys())}"
            )

        # ``cards`` MUST be a list with ≤ 7 items (COBOL browse-cap
        # of 7 rows per COCRDLI screen — this is the critical F-006
        # invariant that distinguishes the card list from the
        # transaction list's 10-row pagination).
        assert isinstance(body["cards"], list), (
            f"``cards`` MUST be a list; got {type(body['cards']).__name__}"
        )
        assert len(body["cards"]) <= _CARDS_PER_PAGE, (
            f"``cards`` MUST contain at most {_CARDS_PER_PAGE} items per page "
            f"(COCRDLI BMS browse-cap); got {len(body['cards'])} items"
        )

        # Each row has the 4 required business fields with the correct
        # PIC X widths from COCRDLI.CPY.
        for idx, item in enumerate(body["cards"]):
            for required_field in ("selected", "account_id", "card_number", "card_status"):
                assert required_field in item, (
                    f"cards[{idx}] MUST include ``{required_field}``; "
                    f"got {sorted(item.keys())}"
                )
            # COCRDLI CRDSELnI PIC X(01) — exactly 1 char.
            assert len(item["selected"]) <= 1, (
                f"cards[{idx}].selected MUST be ≤ 1 char (PIC X(01)); "
                f"got length {len(item['selected'])}"
            )
            # COCRDLI ACCTNOnI PIC X(11) — exactly 11 chars.
            assert len(item["account_id"]) <= 11, (
                f"cards[{idx}].account_id MUST be ≤ 11 chars (PIC X(11)); "
                f"got length {len(item['account_id'])}"
            )
            # COCRDLI CRDNUMnI PIC X(16) — exactly 16 chars.
            assert len(item["card_number"]) <= 16, (
                f"cards[{idx}].card_number MUST be ≤ 16 chars (PIC X(16)); "
                f"got length {len(item['card_number'])}"
            )
            # COCRDLI CRDSTSnI PIC X(01) — exactly 1 char.
            assert len(item["card_status"]) <= 1, (
                f"cards[{idx}].card_status MUST be ≤ 1 char (PIC X(01)); "
                f"got length {len(item['card_status'])}"
            )

        # ``page_number`` echoes the default page (1).
        assert body["page_number"] == 1, (
            f"``page_number`` MUST echo the default page (1); "
            f"got {body['page_number']}"
        )

        # ``error_message`` is None on success.
        assert body.get("error_message") is None, (
            f"Success list MUST have error_message=None; "
            f"got {body.get('error_message')!r}"
        )

        # Verify the service was instantiated and invoked correctly.
        mock_service_class.assert_called_once()  # CardService(db)
        mock_instance.list_cards.assert_awaited_once()
        call_request = mock_instance.list_cards.call_args.args[0]
        # The request arg is a CardListRequest with default (None)
        # filters and page_number=1.
        assert call_request.account_id is None, (
            f"CardListRequest.account_id MUST be None when no filter "
            f"supplied; got {call_request.account_id!r}"
        )
        assert call_request.card_number is None, (
            f"CardListRequest.card_number MUST be None when no filter "
            f"supplied; got {call_request.card_number!r}"
        )
        assert call_request.page_number == 1, (
            f"CardListRequest.page_number MUST default to 1; "
            f"got {call_request.page_number}"
        )

    # ------------------------------------------------------------------
    # 2. Account-ID filter — account_id query param reaches service
    # ------------------------------------------------------------------
    async def test_list_cards_with_account_filter(self, client: AsyncClient) -> None:
        """``account_id`` query parameter is forwarded to the service.

        Mirrors the ``COCRDLIC.cbl`` ``2210-EDIT-ACCOUNT`` paragraph
        that validates the ``ACCTSIDI`` PIC X(11) input on the
        COCRDLI map and restricts the browse cursor to the
        corresponding owning account. In the Python port, this
        becomes an ``account_id=...`` query parameter that the
        router passes into :class:`CardListRequest` and the service
        uses as a ``WHERE acct_id = :account_id`` clause.

        Assertions:
            * HTTP 200 OK.
            * Response body's returned ``cards`` all carry the
              filtered ``account_id`` (the mock helper synthesizes
              rows with the filter value).
            * The service was invoked with ``CardListRequest
              .account_id == _TEST_ACCT_ID`` — the actual filter
              pass-through guarantee.
        """
        mock_response = _make_list_response(
            count=3,
            page_number=1,
            total_pages=1,
            account_id=_TEST_ACCT_ID,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_cards = AsyncMock(return_value=mock_response)

            response = await client.get(
                "/cards",
                params={"account_id": _TEST_ACCT_ID},
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Filtered list MUST return HTTP 200 OK; "
            f"got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # All returned rows share the filtered account_id — this is
        # the "correctness" signal that the mock was configured for
        # the filter. (The mock *could* return mixed-account rows;
        # the production service would not, and our helper does
        # not.)
        for idx, item in enumerate(body["cards"]):
            assert item["account_id"] == _TEST_ACCT_ID, (
                f"cards[{idx}].account_id MUST match filter "
                f"({_TEST_ACCT_ID!r}); got {item['account_id']!r}"
            )

        # Verify the service was called with the expected filter —
        # this is the actual "pass-through" guarantee that the
        # query-parameter binding is wired correctly. The router
        # could otherwise silently drop the filter and still return
        # the mock data.
        mock_service_class.assert_called_once()
        mock_instance.list_cards.assert_awaited_once()
        call_request = mock_instance.list_cards.call_args.args[0]
        assert call_request.account_id == _TEST_ACCT_ID, (
            f"CardListRequest.account_id MUST be {_TEST_ACCT_ID!r}; "
            f"got {call_request.account_id!r}"
        )
        assert call_request.card_number is None, (
            f"CardListRequest.card_number MUST be None (only account_id "
            f"filter supplied); got {call_request.card_number!r}"
        )

    # ------------------------------------------------------------------
    # 3. Card-number filter — card_number query param reaches service
    # ------------------------------------------------------------------
    async def test_list_cards_with_card_filter(self, client: AsyncClient) -> None:
        """``card_number`` query parameter is forwarded to the service.

        Mirrors the ``COCRDLIC.cbl`` ``2220-EDIT-CARD`` paragraph
        (roughly lines 280-350) that validates the ``CARDSIDI`` PIC
        X(16) input on the COCRDLI map and positions the browse
        cursor at the corresponding card record via ``STARTBR
        RIDFLD(CARD-NUM)``. In the Python port, this becomes a
        ``card_number=...`` query parameter used by the service to
        locate the "jump-to" row.

        Assertions:
            * HTTP 200 OK.
            * The service was invoked with ``CardListRequest
              .card_number == _TEST_CARD_NUM``.
        """
        mock_response = _make_list_response(
            count=1,
            page_number=1,
            total_pages=1,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_cards = AsyncMock(return_value=mock_response)

            response = await client.get(
                "/cards",
                params={"card_number": _TEST_CARD_NUM},
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Filtered list MUST return HTTP 200 OK; "
            f"got {response.status_code}: {response.text}"
        )

        # Verify the service was called with the expected card_number
        # filter — the router's query-parameter binding MUST forward
        # the 16-char PAN into CardListRequest.card_number.
        mock_service_class.assert_called_once()
        mock_instance.list_cards.assert_awaited_once()
        call_request = mock_instance.list_cards.call_args.args[0]
        assert call_request.card_number == _TEST_CARD_NUM, (
            f"CardListRequest.card_number MUST be {_TEST_CARD_NUM!r}; "
            f"got {call_request.card_number!r}"
        )
        assert call_request.account_id is None, (
            f"CardListRequest.account_id MUST be None (only card_number "
            f"filter supplied); got {call_request.account_id!r}"
        )

    # ------------------------------------------------------------------
    # 4. Pagination — page_number=2 reaches service; total_pages > 1
    # ------------------------------------------------------------------
    async def test_list_cards_pagination(self, client: AsyncClient) -> None:
        """``page_number=2`` produces a second-page response.

        Mirrors the ``COCRDLIC.cbl`` ``1100-PROCESS-NEXT-KEY`` /
        ``1200-PROCESS-PREV-KEY`` paragraphs that handled PF7/PF8
        forward/backward navigation on the 3270 screen. The COBOL
        program maintained the cursor position in ``WS-NEXT-START-BRK``
        and WS-PREV-START-BRK`` work-storage fields; the Python port
        uses explicit ``page_number`` and ``total_pages`` fields
        computed from ``ceil(total_count / 7)``.

        Assertions:
            * HTTP 200 OK.
            * Response body's ``page_number`` echoes the requested
              page (2).
            * Response body's ``total_pages`` > 1 (multi-page
              scenario).
            * :meth:`CardService.list_cards` was invoked with
              ``page_number=2``.
        """
        mock_response = _make_list_response(
            count=_CARDS_PER_PAGE,  # full page
            page_number=2,
            total_pages=3,  # 3-page result set (15-21 rows total)
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_cards = AsyncMock(return_value=mock_response)

            response = await client.get(
                "/cards",
                params={"page_number": 2},
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Paginated list MUST return HTTP 200 OK; "
            f"got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        assert body["page_number"] == 2, (
            f"``page_number`` MUST echo the requested page (2); "
            f"got {body['page_number']}"
        )
        assert body["total_pages"] > 1, (
            f"``total_pages`` MUST be > 1 on a multi-page response; "
            f"got {body['total_pages']}"
        )
        # Full page 2 contains the per-page cap.
        assert len(body["cards"]) == _CARDS_PER_PAGE, (
            f"Full page 2 MUST contain exactly {_CARDS_PER_PAGE} rows "
            f"(per-page cap); got {len(body['cards'])}"
        )

        # Verify the service was invoked with the correct page.
        mock_service_class.assert_called_once()
        mock_instance.list_cards.assert_awaited_once()
        call_request = mock_instance.list_cards.call_args.args[0]
        assert call_request.page_number == 2, (
            f"CardListRequest.page_number MUST be 2; "
            f"got {call_request.page_number}"
        )

    # ------------------------------------------------------------------
    # 5. Empty results — mock returns empty list; service populates
    #    error_message with the COBOL-exact "NO RECORDS FOUND" string.
    #    Router's ``list_cards`` handler converts this to HTTP 400
    #    (the list path does NOT consult ``_ERROR_MESSAGE_STATUS_MAP``
    #    because no not-found / concurrency literals reach it).
    # ------------------------------------------------------------------
    async def test_list_cards_empty_results(self, client: AsyncClient) -> None:
        """Empty-filter list yields HTTP 400 with the COBOL-exact message.

        Mirrors the ``COCRDLIC.cbl`` first-read branch at line 122
        (``MOVE 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.' TO
        WS-MESSAGE``) that populated the COCRDLI ``ERRMSGI`` field
        when the initial STARTBR + READNEXT found no rows matching
        the filter. The original CICS program then issued a ``SEND
        MAP`` with an empty row area — the modern service layer
        returns a ``CardListResponse`` with ``cards=[]`` and
        ``error_message`` set, which the router converts to
        HTTP 400.

        The list endpoint surfaces ``_MSG_LIST_NO_RECORDS`` /
        ``_MSG_LIST_NO_MORE`` / generic lookup failures — none of
        which are registered in
        :data:`_ERROR_MESSAGE_STATUS_MAP`. They therefore fall
        through to the default HTTP 400 bucket. This is distinct
        from the ``get_card`` / ``update_card`` handlers, which
        map ``_MSG_*_NOT_FOUND`` → 404 and ``_MSG_UPDATE_STALE``
        → 409.

        CRITICAL: the router status is 400 (not 200 with an empty
        list, not 404). 200-with-empty-list is explicitly rejected
        to preserve the COBOL BMS semantics of surfacing
        ``_MSG_LIST_NO_RECORDS`` as a message, and 404 would be
        inappropriate because the collection endpoint itself exists
        — only the filtered result set is empty.

        Assertions:
            * HTTP 400 Bad Request (NOT 200 with empty list,
              NOT 404).
            * The ``detail`` field of the error response is the
              COBOL-exact ``_MSG_LIST_NO_RECORDS`` string, preserved
              byte-for-byte.
            * :meth:`CardService.list_cards` was invoked exactly
              once (to produce the empty response).
        """
        # Mock returns an empty-cards response with the COBOL error
        # message populated. The service-layer logic is out of scope
        # for this router-unit test — we only verify that whatever
        # the service returns in ``error_message`` is reflected in
        # the router's 400 detail.
        mock_response = _make_list_response(
            count=0,
            page_number=1,
            total_pages=0,
            error_message=_MSG_LIST_NO_RECORDS,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_cards = AsyncMock(return_value=mock_response)

            response = await client.get("/cards")

        # HTTP 400 — ``_MSG_LIST_NO_RECORDS`` is NOT registered in
        # ``_ERROR_MESSAGE_STATUS_MAP``, so the ``list_cards``
        # handler falls through to its default 400 branch.
        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Empty-filter list with populated error_message MUST "
            f"return HTTP 400 (default bucket — "
            f"``_MSG_LIST_NO_RECORDS`` is not in the status map); "
            f"got {response.status_code}: {response.text}"
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler in ``src/api/middleware/error_handler.py`` — the
        # COBOL-exact message appears in the ``reason`` field of the
        # envelope (rather than at the top-level ``detail``). Following
        # the established pattern from test_bill_router.py /
        # test_transaction_router.py, we search the full response text
        # for the literal so the assertion is resilient to future
        # envelope-shape tweaks and byte-for-byte matches the COBOL
        # literal.
        assert _MSG_LIST_NO_RECORDS in response.text, (
            f"400 response MUST carry the COCRDLIC.cbl L122 literal "
            f"{_MSG_LIST_NO_RECORDS!r} byte-for-byte (AAP §0.7.1 — "
            f"preserve existing error messages exactly); got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.list_cards.assert_awaited_once()

    # ------------------------------------------------------------------
    # 6. Authentication required — unauthenticated → 401/403
    # ------------------------------------------------------------------
    async def test_list_cards_requires_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated ``GET /cards`` returns 401 or 403.

        Mirrors the CICS ``RETURN TRANSID(...)`` / COMMAREA session
        validation in ``COCRDLIC.cbl`` where the program rejected
        any invocation lacking a valid signed-on user session
        (CICS ``ASSIGN USERID`` + SEC-USR-ID check). In the Python
        port the equivalent guard is the :class:`JWTAuthMiddleware`
        in :mod:`src.api.middleware.auth`, which runs BEFORE FastAPI
        dependency resolution and returns an ABEND-DATA-shaped
        HTTP 401 response with a ``WWW-Authenticate: Bearer`` header
        (per RFC 7235) when no Authorization header is present.

        Uses a throwaway :class:`AsyncClient` constructed directly
        from the ``test_app`` fixture (which has the dependency
        overrides registered — including get_db — but does NOT
        inject an Authorization header) so the middleware's 401
        path is exercised.

        Assertions:
            * Status code is either 401 (canonical — the middleware's
              default) or 403 (defensive — some deployments may
              upgrade to 403 under different OAuth2 scheme
              configurations).
            * If 401 is returned, the response carries a
              ``WWW-Authenticate`` header per RFC 7235.
        """
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.get("/cards")

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), (
            f"Unauthenticated request MUST return 401 or 403; "
            f"got {response.status_code}: {response.text}"
        )
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header "
                f"(RFC 7235); got headers {dict(response.headers)!r}"
            )



# ============================================================================
# TestCardDetail
# ----------------------------------------------------------------------------
# Exercises ``GET /cards/{card_num}`` — replaces the CICS
# ``COCRDSLC`` program (Feature F-007) that performed a single
# ``EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-NUM)`` to fetch
# the full 150-byte CVACT02Y CARD-RECORD and painted the COCRDSL
# BMS map with the 6 display fields + 2 message fields. Tests cover:
#
#   1. Happy path (full detail record, HTTP 200).
#   2. Not found (service returns _MSG_DETAIL_NOT_FOUND; router
#      looks up the literal in ``_ERROR_MESSAGE_STATUS_MAP`` and
#      converts to HTTP 404 per RFC 7231 §6.5.4).
#   3. Path-regex violation (malformed card_num triggers FastAPI's
#      automatic 422).
#   4. Unauthenticated request → 401/403.
# ============================================================================
class TestCardDetail:
    """Tests for the ``GET /cards/{card_num}`` endpoint (Feature F-007)."""

    # ------------------------------------------------------------------
    # 1. Successful detail lookup — happy path
    # ------------------------------------------------------------------
    async def test_get_card_detail_success(self, client: AsyncClient) -> None:
        """Successful detail lookup returns HTTP 200 with full CARD-RECORD.

        Mirrors the full ``COCRDSLC.cbl`` ``9000-READ-DATA`` path
        (lines 140-180 in the original): the program validated the
        user's ``CARDSIDI`` input, performed ``EXEC CICS READ
        DATASET('CARDDAT') RIDFLD(WS-CARD-NUM)`` to fetch the
        150-byte CVACT02Y record, populated every CCRDSLAO map
        output (6 business fields: ACCTSIDI/CARDSIDI/CRDNAMEI/
        CRDSTCDI/EXPMONI/EXPYEARI), and issued ``SEND MAP
        COCRDSLA``.

        In the Python port, :meth:`CardService.get_card_detail`
        performs a SQLAlchemy primary-key lookup in the ``cards``
        table and returns a :class:`CardDetailResponse` with all 6
        business fields populated. The router forwards it unchanged.

        Assertions:
            * HTTP 200 OK.
            * All 6 business fields are present in the response body
              (``account_id``, ``card_number``, ``embossed_name``,
              ``status_code``, ``expiry_month``, ``expiry_year``).
            * Each field matches the corresponding COBOL PIC X
              maximum width from CVACT02Y.cpy.
            * ``card_number`` equals the path parameter
              (``_TEST_CARD_NUM``).
            * ``account_id`` is 11 chars (CVACT02Y CARD-ACCT-ID PIC
              9(11)).
            * ``status_code`` is 1 char (CVACT02Y CARD-ACTIVE-STATUS
              PIC X(01)).
            * ``expiry_month`` is 2 chars, ``expiry_year`` is 4 chars.
            * ``error_message`` is None on success.
            * :meth:`CardService.get_card_detail` was called exactly
              once with the correct card_num path parameter.
        """
        mock_response = _make_detail_response()

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_card_detail = AsyncMock(return_value=mock_response)

            response = await client.get(f"/cards/{_TEST_CARD_NUM}")

        assert response.status_code == status.HTTP_200_OK, (
            f"Successful detail lookup MUST return HTTP 200 OK; "
            f"got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # All 6 business fields from the CVACT02Y / COCRDSL BMS map
        # MUST be in the response body.
        required_fields = (
            "account_id",
            "card_number",
            "embossed_name",
            "status_code",
            "expiry_month",
            "expiry_year",
        )
        for required_field in required_fields:
            assert required_field in body, (
                f"Detail response MUST include ``{required_field}``; "
                f"got {sorted(body.keys())}"
            )

        # ``card_number`` equals the path parameter — the echo-back
        # guarantee that the service received the correct key.
        assert body["card_number"] == _TEST_CARD_NUM, (
            f"``card_number`` MUST echo the path parameter "
            f"({_TEST_CARD_NUM!r}); got {body.get('card_number')!r}"
        )

        # ``account_id`` is 11 chars (CVACT02Y CARD-ACCT-ID PIC 9(11)).
        assert len(body["account_id"]) <= 11, (
            f"``account_id`` MUST be at most 11 chars (PIC 9(11)); "
            f"got length {len(body['account_id'])}"
        )

        # ``embossed_name`` is at most 50 chars (CVACT02Y
        # CARD-EMBOSSED-NAME PIC X(50)).
        assert len(body["embossed_name"]) <= 50, (
            f"``embossed_name`` MUST be at most 50 chars (PIC X(50)); "
            f"got length {len(body['embossed_name'])}"
        )

        # ``status_code`` is exactly 1 char (CVACT02Y
        # CARD-ACTIVE-STATUS PIC X(01)).
        assert len(body["status_code"]) <= 1, (
            f"``status_code`` MUST be at most 1 char (PIC X(01)); "
            f"got length {len(body['status_code'])}"
        )

        # ``expiry_month`` is exactly 2 chars (COCRDSL EXPMONI PIC
        # X(02)) and ``expiry_year`` is exactly 4 chars (COCRDSL
        # EXPYEARI PIC X(04)).
        assert len(body["expiry_month"]) <= 2, (
            f"``expiry_month`` MUST be at most 2 chars (PIC X(02)); "
            f"got length {len(body['expiry_month'])}"
        )
        assert len(body["expiry_year"]) <= 4, (
            f"``expiry_year`` MUST be at most 4 chars (PIC X(04)); "
            f"got length {len(body['expiry_year'])}"
        )

        # ``error_message`` is None on success.
        assert body.get("error_message") is None, (
            f"Success detail MUST have error_message=None; "
            f"got {body.get('error_message')!r}"
        )

        # Reconstruct through the schema to verify round-trip fidelity.
        reconstructed = CardDetailResponse(**body)
        assert reconstructed.card_number == _TEST_CARD_NUM
        assert reconstructed.account_id == _TEST_ACCT_ID

        # Verify the service was invoked with the correct card_num.
        mock_service_class.assert_called_once()  # CardService(db)
        mock_instance.get_card_detail.assert_awaited_once_with(_TEST_CARD_NUM)

    # ------------------------------------------------------------------
    # 2. Not found — service returns _MSG_DETAIL_NOT_FOUND → HTTP 404
    #    (per the router's ``_ERROR_MESSAGE_STATUS_MAP`` lookup,
    #    aligned with RFC 7231 §6.5.4).
    # ------------------------------------------------------------------
    async def test_get_card_not_found(self, client: AsyncClient) -> None:
        """Non-existent card returns HTTP 404 with the COBOL-exact message.

        Mirrors the ``COCRDSLC.cbl`` ``9000-READ-DATA`` branch at
        line 154 (``MOVE 'Did not find cards for this search
        condition' TO WS-ERR-MESSAGE``) that was taken on
        ``DFHRESP(NOTFND)`` from the CARDDAT read. The COBOL program
        then issued a ``SEND MAP`` with the error-channel populated;
        the modern service layer returns a
        :class:`CardDetailResponse` with
        ``error_message = _MSG_DETAIL_NOT_FOUND``. The router looks
        up this literal in :data:`_ERROR_MESSAGE_STATUS_MAP` and
        raises ``HTTPException`` with HTTP 404 Not Found per
        RFC 7231 §6.5.4. The COBOL-exact error text is preserved
        byte-for-byte in the response body.

        RELATIONSHIP TO ``_MSG_UPDATE_NOT_FOUND``:
        ``card_service`` defines two symbols —
        ``_MSG_DETAIL_NOT_FOUND`` and ``_MSG_UPDATE_NOT_FOUND`` —
        but both carry the identical literal
        ``"Did not find cards for this search condition"``. The
        router's status map is keyed on the *string value*, so
        registering both symbols yields a single dict entry that
        serves both endpoints. Both the ``get_card`` and
        ``update_card`` handlers therefore return HTTP 404 for this
        literal.

        Assertions:
            * HTTP 404 Not Found (per ``_ERROR_MESSAGE_STATUS_MAP``
              in ``src/api/routers/card_router.py``, aligned with
              RFC 7231 §6.5.4).
            * The ``detail`` field is the COBOL-exact
              ``_MSG_DETAIL_NOT_FOUND`` string, preserved
              byte-for-byte.
            * :meth:`CardService.get_card_detail` was invoked exactly
              once with the requested card_num.
        """
        # Service returns a response with the not-found message
        # populated. Note that the other business fields are still
        # populated — the COBOL program did not clear the map on
        # error, and the Python port preserves this behavior.
        mock_response = _make_detail_response(
            error_message=_MSG_DETAIL_NOT_FOUND,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_card_detail = AsyncMock(return_value=mock_response)

            response = await client.get(f"/cards/{_TEST_CARD_NUM}")

        # HTTP 404 — ``_MSG_DETAIL_NOT_FOUND`` is mapped to 404 in
        # ``_ERROR_MESSAGE_STATUS_MAP`` per RFC 7231 §6.5.4.
        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Not-found detail MUST return HTTP 404 "
            f"(``_MSG_DETAIL_NOT_FOUND`` → 404 per "
            f"``_ERROR_MESSAGE_STATUS_MAP``); got "
            f"{response.status_code}: {response.text}"
        )
        # Explicit negative assertion to lock out accidental drift
        # BACK to the legacy uniform-400 pattern.
        assert response.status_code != status.HTTP_400_BAD_REQUEST, (
            "Router MUST NOT use 400 for card-not-found; the "
            "response-message mapping requires _MSG_DETAIL_NOT_FOUND "
            "→ HTTP 404 per RFC 7231 §6.5.4."
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler — the COBOL-exact message appears in the ``reason``
        # field of the envelope. Following the established pattern
        # from test_bill_router.py, we search the full response text
        # for the COBOL literal (byte-for-byte). This is resilient to
        # envelope-shape tweaks and enforces the COBOL parity promise.
        assert _MSG_DETAIL_NOT_FOUND in response.text, (
            f"404 response MUST carry the COCRDSLC.cbl L154 literal "
            f"{_MSG_DETAIL_NOT_FOUND!r} byte-for-byte (AAP §0.7.1 — "
            f"preserve existing error messages exactly); got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.get_card_detail.assert_awaited_once_with(_TEST_CARD_NUM)

    # ------------------------------------------------------------------
    # 3. Path-regex violation — malformed card_num triggers 422
    # ------------------------------------------------------------------
    async def test_get_card_invalid_length(self, client: AsyncClient) -> None:
        """Malformed card_num path triggers FastAPI's automatic HTTP 422.

        The ``_CARD_NUM_REGEX`` path pattern ``^[0-9]{16}$`` on the
        ``card_num`` Path parameter (card_router.py L109, L194)
        rejects any malformed key with FastAPI's automatic
        HTTP 422 response BEFORE the service layer runs. The COBOL
        analogue is the ``1210-EDIT-CARDLEN`` paragraph in
        ``COCRDSLC.cbl`` (lines 240-280) that validated the
        ``CARDSIDI`` length ahead of the CARDDAT read and populated
        ``WS-ERR-MSG`` with a "Card number must be 16 digits" style
        error without issuing the file read at all.

        Three representative malformed inputs exercise the regex:

        * ``"1234"`` — 4 digits, too short.
        * ``"12345678901234567"`` — 17 digits, too long.
        * ``"41111111111111AB"`` — 16 chars but contains non-digits.

        Note that ``AsyncClient.get`` URL-encodes the path before
        sending, so special characters in the path do not accidentally
        match the regex. Also note that the service is patched only
        to catch the negative assertion that it was NEVER invoked —
        the path regex rejects the request before dependency resolution.

        Assertions:
            * HTTP 422 Unprocessable Entity for each malformed input.
            * :meth:`CardService.get_card_detail` was NEVER invoked
              (path validation rejected the request before dependency
              resolution completed).
        """
        # Three independently-malformed inputs that each violate a
        # different aspect of the regex.
        malformed_inputs = [
            "1234",                      # too short (4 < 16)
            "12345678901234567",         # too long (17 > 16)
            "41111111111111AB",          # 16 chars but non-numeric
        ]

        for malformed in malformed_inputs:
            with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
                mock_instance: MagicMock = mock_service_class.return_value
                # Configure the mock even though we expect it NOT to
                # be called — this ensures that if the regex were
                # accidentally loosened, the test would fail on the
                # positive assertion (on status code) rather than
                # producing a less-informative ``MagicMock has no
                # attribute`` error.
                mock_instance.get_card_detail = AsyncMock(
                    return_value=_make_detail_response(),
                )

                response = await client.get(f"/cards/{malformed}")

            assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
                f"Malformed card_num ({malformed!r}) MUST return HTTP 422 "
                f"(FastAPI path-regex auto-reject); got "
                f"{response.status_code}: {response.text}"
            )

            # The service was NEVER invoked — the regex rejects the
            # request before dependency resolution completes.
            mock_service_class.assert_not_called()
            mock_instance.get_card_detail.assert_not_called()

    # ------------------------------------------------------------------
    # 4. Authentication required — unauthenticated → 401/403
    # ------------------------------------------------------------------
    async def test_get_card_requires_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated ``GET /cards/{card_num}`` returns 401 or 403.

        Same pattern as ``test_list_cards_requires_auth`` — the
        :class:`JWTAuthMiddleware` rejects the request at the
        middleware layer (before dependency resolution) because no
        Authorization header is present. Uses a throwaway
        :class:`AsyncClient` from the ``test_app`` fixture.

        Assertions:
            * Status code is 401 or 403.
            * 401 carries a ``WWW-Authenticate`` header per RFC 7235.
        """
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.get(f"/cards/{_TEST_CARD_NUM}")

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), (
            f"Unauthenticated request MUST return 401 or 403; "
            f"got {response.status_code}: {response.text}"
        )
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header "
                f"(RFC 7235); got headers {dict(response.headers)!r}"
            )



# ============================================================================
# TestCardUpdate
# ----------------------------------------------------------------------------
# Exercises ``PUT /cards/{card_num}`` — replaces the CICS
# ``COCRDUPC`` program (Feature F-008) that performed a full
# 3-step optimistic-concurrency dance against CARDDAT:
#
#   1. ``EXEC CICS READ UPDATE DATASET('CARDDAT') RIDFLD(WS-CARD-NUM)``
#      — read with an exclusive lock, returning the current record.
#   2. Compare the user-supplied input fields against the freshly
#      read record; if any mismatch is found between the "old"
#      (hidden) copy on the screen and the record on disk, it means
#      another user has modified the record between SELECT and this
#      UPDATE — emit "Record changed by some one else. Please review"
#      and send the map back without rewriting.
#   3. ``EXEC CICS REWRITE DATASET('CARDDAT') FROM(CARD-RECORD)``
#      — commit the new values.
#
# Tests cover:
#
#   1. Happy path (HTTP 200, ``info_message`` contains
#      ``_MSG_UPDATE_SUCCESS`` "Changes committed to database").
#   2. Not-found (service returns ``_MSG_UPDATE_NOT_FOUND`` — same
#      literal as ``_MSG_DETAIL_NOT_FOUND``, collapsed to a single
#      entry in ``_ERROR_MESSAGE_STATUS_MAP``; router converts to
#      HTTP 404 per RFC 7231 §6.5.4, mirroring the COCRDUPC
#      ``DFHRESP(NOTFND)`` branch at L204).
#   3. Optimistic-concurrency conflict (service returns
#      ``_MSG_UPDATE_CONCURRENCY`` "Record changed by some one else.
#      Please review"; router looks up the literal in
#      ``_ERROR_MESSAGE_STATUS_MAP`` and converts to HTTP 409 per
#      RFC 7231 §6.5.8).
#   4. Pydantic validation failure (invalid status_code,
#      expiry_month, or card_number) → HTTP 422, before the service
#      is invoked.
#   5. Unauthenticated request → 401/403.
# ============================================================================
class TestCardUpdate:
    """Tests for the ``PUT /cards/{card_num}`` endpoint (Feature F-008)."""

    # ------------------------------------------------------------------
    # 1. Successful update — happy path
    # ------------------------------------------------------------------
    async def test_update_card_success(self, client: AsyncClient) -> None:
        """Successful update returns HTTP 200 with ``info_message`` populated.

        Mirrors the ``COCRDUPC.cbl`` ``9200-WRITE-PROCESSING``
        paragraph (lines 160-170): on a clean optimistic-concurrency
        check, the program executes ``EXEC CICS REWRITE
        DATASET('CARDDAT')``, then sets ``MOVE 'Changes committed to
        database' TO WS-INFO-MSG`` (L169) and returns the updated
        screen with the success banner.

        In the Python port, :meth:`CardService.update_card` performs
        a SELECT-then-UPDATE within a SQLAlchemy transaction. On
        success, it returns a :class:`CardUpdateResponse` with all 6
        business fields populated (echoing the post-update state)
        and ``info_message = _MSG_UPDATE_SUCCESS``.

        Assertions:
            * HTTP 200 OK.
            * Response body contains all 6 business fields.
            * ``info_message`` equals the COBOL-exact success
              message byte-for-byte.
            * ``error_message`` is None.
            * :meth:`CardService.update_card` was invoked exactly
              once with the path ``card_num`` as the first positional
              argument and a :class:`CardUpdateRequest` as the
              second, with all 7 fields pass-through-identical to
              the JSON body.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=_MSG_UPDATE_SUCCESS,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_card = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/cards/{_TEST_CARD_NUM}",
                json=request_body,
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Successful update MUST return HTTP 200 OK; "
            f"got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # All 6 business fields are echoed back (CardUpdateResponse
        # inherits directly from CardDetailResponse with no new
        # fields — confirmed at card_schema.py line 987).
        required_fields = (
            "account_id",
            "card_number",
            "embossed_name",
            "status_code",
            "expiry_month",
            "expiry_year",
        )
        for required_field in required_fields:
            assert required_field in body, (
                f"Update response MUST include ``{required_field}``; "
                f"got {sorted(body.keys())}"
            )

        # Success banner is the COBOL-exact "Changes committed to
        # database" message byte-for-byte.
        assert body.get("info_message") == _MSG_UPDATE_SUCCESS, (
            f"``info_message`` on success MUST be the COBOL-exact "
            f"{_MSG_UPDATE_SUCCESS!r}; got {body.get('info_message')!r}"
        )

        # ``error_message`` is None on success.
        assert body.get("error_message") is None, (
            f"Success update MUST have error_message=None; "
            f"got {body.get('error_message')!r}"
        )

        # Schema round-trip.
        reconstructed = CardUpdateResponse(**body)
        assert reconstructed.info_message == _MSG_UPDATE_SUCCESS

        # Verify the service was invoked correctly.
        mock_service_class.assert_called_once()  # CardService(db)
        mock_instance.update_card.assert_awaited_once()

        call_args = mock_instance.update_card.call_args
        # First positional argument is the path ``card_num``.
        assert call_args.args[0] == _TEST_CARD_NUM, (
            f"update_card first positional arg MUST be the path "
            f"card_num ({_TEST_CARD_NUM!r}); got "
            f"{call_args.args[0]!r}"
        )
        # Second positional argument is the CardUpdateRequest with
        # all 7 fields matching the JSON body byte-for-byte.
        call_request = call_args.args[1]
        assert call_request.card_number == request_body["card_number"]
        assert call_request.account_id == request_body["account_id"]
        assert call_request.embossed_name == request_body["embossed_name"]
        assert call_request.status_code == request_body["status_code"]
        assert call_request.expiry_month == request_body["expiry_month"]
        assert call_request.expiry_year == request_body["expiry_year"]
        assert call_request.expiry_day == request_body["expiry_day"]

    # ------------------------------------------------------------------
    # 2. Not-found — service returns _MSG_DETAIL_NOT_FOUND → HTTP 404
    # ------------------------------------------------------------------
    async def test_update_card_not_found(self, client: AsyncClient) -> None:
        """Update targeting a non-existent card returns HTTP 404.

        Mirrors the ``COCRDUPC.cbl`` ``9100-GETCARD-BYACCTCARD``
        paragraph at line 204: the pre-UPDATE check that verifies
        the record exists before REWRITE. On ``DFHRESP(NOTFND)``,
        the program sets ``MOVE 'Did not find cards for this search
        condition' TO WS-ERR-MESSAGE`` and skips the rewrite.

        The Python service returns a :class:`CardUpdateResponse`
        with ``error_message = _MSG_DETAIL_NOT_FOUND`` (card_service
        exposes both ``_MSG_DETAIL_NOT_FOUND`` and
        ``_MSG_UPDATE_NOT_FOUND`` which share the identical string
        literal). The router looks up the literal in
        :data:`_ERROR_MESSAGE_STATUS_MAP` and raises
        ``HTTPException`` with HTTP 404 Not Found per
        RFC 7231 §6.5.4. The COBOL-exact error text is preserved
        byte-for-byte in the response body.

        Assertions:
            * HTTP 404 Not Found (per ``_ERROR_MESSAGE_STATUS_MAP``
              in ``src/api/routers/card_router.py``, aligned with
              RFC 7231 §6.5.4).
            * ``detail`` equals the COBOL-exact not-found message
              byte-for-byte.
            * Service was invoked exactly once with the correct
              parameters.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_DETAIL_NOT_FOUND,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_card = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/cards/{_TEST_CARD_NUM}",
                json=request_body,
            )

        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Update of non-existent card MUST return HTTP 404 "
            f"(``_MSG_DETAIL_NOT_FOUND`` → 404 per "
            f"``_ERROR_MESSAGE_STATUS_MAP``); got "
            f"{response.status_code}: {response.text}"
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler — the COBOL-exact message appears in the ``reason``
        # field of the envelope. Following the established pattern
        # from test_bill_router.py, we search the full response text
        # for the COBOL literal (byte-for-byte).
        assert _MSG_DETAIL_NOT_FOUND in response.text, (
            f"404 response MUST carry the COCRDUPC.cbl L204 literal "
            f"{_MSG_DETAIL_NOT_FOUND!r} byte-for-byte (AAP §0.7.1 — "
            f"preserve existing error messages exactly); got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_card.assert_awaited_once()

    # ------------------------------------------------------------------
    # 3. Optimistic-concurrency conflict — HTTP 409 (RFC 7231 §6.5.8)
    # ------------------------------------------------------------------
    async def test_update_card_concurrent_modification(
        self, client: AsyncClient
    ) -> None:
        """Concurrency conflict returns HTTP 409 with the COBOL-exact message.

        This is the signature behavior of Feature F-008 — the
        direct translation of COCRDUPC's optimistic-concurrency
        pattern. The COBOL program's approach was:

        1. On initial SELECT, the 6 business fields from CARDDAT
           are sent to the user as both display fields (CRDNAMEI,
           etc.) AND as hidden "old-value" fields on the BMS map.
        2. When the user submits the UPDATE, the program re-reads
           the record with ``READ UPDATE`` (exclusive lock) and
           compares the hidden old-values on the incoming COMMAREA
           against the just-read record's fields (COCRDUPC paragraph
           ``9300-CHECK-CHANGE-IN-REC`` at L260-320).
        3. If ANY field differs, another user modified the record
           between SELECT and UPDATE. The program sets
           ``MOVE 'Record changed by some one else. Please review'
           TO WS-ERR-MESSAGE`` (L208) and returns WITHOUT rewriting,
           releasing the lock via ``UNLOCK``.

        The Python port uses SQLAlchemy's row-versioning to detect
        the same race: a ``StaleDataError`` on UPDATE is caught by
        :meth:`CardService.update_card`, which returns a response
        with ``error_message = _MSG_UPDATE_CONCURRENCY``
        (identical to ``_MSG_UPDATE_STALE`` in the router's map —
        both carry the same literal ``"Record changed by some one
        else. Please review"``). The router looks up this literal
        in :data:`_ERROR_MESSAGE_STATUS_MAP` and raises
        ``HTTPException`` with HTTP 409 Conflict per
        RFC 7231 §6.5.8 (Conflict). The COBOL-exact error text is
        preserved byte-for-byte in the response body, so downstream
        clients that match on the ``detail`` substring continue to
        work unchanged.

        Assertions:
            * HTTP 409 Conflict (per ``_ERROR_MESSAGE_STATUS_MAP``
              in ``src/api/routers/card_router.py``, aligned with
              RFC 7231 §6.5.8).
            * ``detail`` equals the COBOL-exact concurrency message
              "Record changed by some one else. Please review"
              byte-for-byte (46 chars).
            * Service was invoked exactly once.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_UPDATE_CONCURRENCY,
        )

        with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_card = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/cards/{_TEST_CARD_NUM}",
                json=request_body,
            )

        # HTTP 409 Conflict — ``_MSG_UPDATE_CONCURRENCY`` / the
        # shared "Record changed by some one else..." literal is
        # mapped to 409 in ``_ERROR_MESSAGE_STATUS_MAP`` per
        # RFC 7231 §6.5.8.
        assert response.status_code == status.HTTP_409_CONFLICT, (
            f"Optimistic-concurrency conflict MUST return HTTP 409 "
            f"(``_MSG_UPDATE_CONCURRENCY`` → 409 per RFC 7231 §6.5.8); "
            f"got {response.status_code}: {response.text}"
        )
        # Explicit negative assertion to lock out accidental drift
        # BACK to the legacy uniform-400 behavior.
        assert response.status_code != status.HTTP_400_BAD_REQUEST, (
            "Router MUST NOT use 400 for optimistic-concurrency "
            "conflict; RFC 7231 §6.5.8 mandates 409 Conflict for "
            "version-mismatch errors."
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler — the COBOL-exact concurrency message appears in
        # the ``reason`` field of the envelope. Following the
        # established pattern from test_bill_router.py, we search
        # the full response text for the COBOL literal byte-for-byte.
        # Note the mixed-case "some one else" (two words) is the
        # exact COBOL source, not a typo (COCRDUPC.cbl L208).
        assert _MSG_UPDATE_CONCURRENCY in response.text, (
            f"409 response MUST carry the COCRDUPC.cbl L208 literal "
            f"{_MSG_UPDATE_CONCURRENCY!r} byte-for-byte (AAP §0.7.1 — "
            f"preserve existing error messages exactly); got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_card.assert_awaited_once()

    # ------------------------------------------------------------------
    # 4. Pydantic validation failures — HTTP 422 before service runs
    # ------------------------------------------------------------------
    async def test_update_card_validation_errors(
        self, client: AsyncClient
    ) -> None:
        """Invalid request body triggers FastAPI's HTTP 422.

        The :class:`CardUpdateRequest` Pydantic model carries three
        strict validators (card_schema.py L729-860):

        * ``_validate_card_number_exact`` — card_number MUST be
          EXACTLY 16 characters (else ``ValueError``).
        * ``_validate_status_code_exact`` — status_code MUST be
          EXACTLY 1 character (else ``ValueError``).
        * ``_validate_expiry_month_range`` — expiry_month MUST be
          exactly 2 digits in range '01'..'12' (else ``ValueError``).

        Each ``ValueError`` raised by a validator is caught by
        FastAPI's request-body parser and translated into an
        HTTP 422 Unprocessable Entity response BEFORE the router
        function runs — meaning the service is NEVER invoked.

        This matches the COBOL input-edit behavior in COCRDUPC's
        ``2000-PROCESS-INPUTS`` paragraph (L320-500) that validated
        all input fields before the READ UPDATE — the pre-edit
        layer.

        Three representative invalid payloads exercise each
        validator independently:

        * ``status_code = ""`` — empty (violates exactly-1-char).
        * ``expiry_month = "13"`` — out of range (violates
          '01'..'12').
        * ``expiry_month = "XX"`` — non-digit (violates digits-only).

        Assertions:
            * HTTP 422 Unprocessable Entity for each invalid payload.
            * ``CardService.update_card`` was NEVER invoked.
        """
        # Three independently-invalid payloads, each failing a
        # different strict validator.
        invalid_payloads: list[dict[str, Any]] = [
            # status_code = "" — violates _validate_status_code_exact
            # (must be exactly 1 char).
            _make_update_request_body(status_code=""),
            # expiry_month = "13" — violates
            # _validate_expiry_month_range (must be '01'..'12').
            _make_update_request_body(expiry_month="13"),
            # expiry_month = "XX" — violates
            # _validate_expiry_month_range (digits-only).
            _make_update_request_body(expiry_month="XX"),
        ]

        for invalid_payload in invalid_payloads:
            with patch(_CARD_SERVICE_PATCH_TARGET) as mock_service_class:
                mock_instance: MagicMock = mock_service_class.return_value
                # Configure the mock even though we expect it NOT to
                # be called. If Pydantic validation were accidentally
                # loosened, this surfaces on the status assertion
                # rather than with a cryptic AttributeError.
                mock_instance.update_card = AsyncMock(
                    return_value=_make_update_response(),
                )

                response = await client.put(
                    f"/cards/{_TEST_CARD_NUM}",
                    json=invalid_payload,
                )

            assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
                f"Invalid payload ({invalid_payload!r}) MUST return HTTP "
                f"422 (Pydantic strict validator rejection); got "
                f"{response.status_code}: {response.text}"
            )

            # The service was NEVER invoked — Pydantic rejected the
            # request at body-parsing time.
            mock_service_class.assert_not_called()
            mock_instance.update_card.assert_not_called()

    # ------------------------------------------------------------------
    # 5. Authentication required — unauthenticated → 401/403
    # ------------------------------------------------------------------
    async def test_update_card_requires_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated ``PUT /cards/{card_num}`` returns 401 or 403.

        Same pattern as the list and detail unauth tests — the
        :class:`JWTAuthMiddleware` rejects the request at the
        middleware layer (before dependency resolution) because no
        Authorization header is present. Uses a throwaway
        :class:`AsyncClient` from the ``test_app`` fixture.

        Note: we send a well-formed JSON body to ensure that the
        middleware auth-gate triggers, rather than accidentally
        exercising a 422 body-validation path ahead of the
        middleware. Technically the middleware should run before
        body parsing, but this guards against any future ordering
        change in the FastAPI middleware stack.

        Assertions:
            * Status code is 401 or 403.
            * 401 carries a ``WWW-Authenticate`` header per RFC 7235.
        """
        request_body = _make_update_request_body()

        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.put(
                f"/cards/{_TEST_CARD_NUM}",
                json=request_body,
            )

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), (
            f"Unauthenticated PUT MUST return 401 or 403; "
            f"got {response.status_code}: {response.text}"
        )
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header "
                f"(RFC 7235); got headers {dict(response.headers)!r}"
            )

