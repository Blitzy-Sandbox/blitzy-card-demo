# ============================================================================
# CardDemo — Unit tests for transaction_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COTRN00C.cbl      — CICS transaction list program (F-009,
#                                 ~699 lines). Drives the paginated
#                                 STARTBR/READNEXT browse cursor over the
#                                 TRANSACT VSAM KSDS and populates the
#                                 10-repeated-row COTRN00 BMS map.
#   * app/cbl/COTRN01C.cbl      — CICS transaction detail program (F-010,
#                                 ~330 lines). Performs a keyed
#                                 ``EXEC CICS READ DATASET('TRANSACT')
#                                 RIDFLD(TRAN-ID)`` and populates the
#                                 CTRN01AI BMS map with the full 350-byte
#                                 CVTRA05Y TRAN-RECORD layout.
#   * app/cbl/COTRN02C.cbl      — CICS transaction add program (F-011,
#                                 ~783 lines). Auto-generates the next
#                                 sequence-based ``TRAN-ID``, resolves the
#                                 ``CARD-NUM → ACCT-ID`` cross-reference
#                                 via the CXACAIX AIX path (1020-XREF-LOOKUP),
#                                 and writes the new TRAN-RECORD through a
#                                 single ``EXEC CICS WRITE
#                                 FILE('TRANSACT')`` on SYNCPOINT.
#   * app/cpy/CVTRA05Y.cpy      — TRAN-RECORD layout (350 bytes) declaring
#                                 the 12 output columns for all three
#                                 endpoints. TRAN-AMT is the critical
#                                 monetary field (``PIC S9(09)V99`` → Python
#                                 ``Decimal``).
#   * app/cpy/CVACT03Y.cpy      — CARD-XREF-RECORD layout (50 bytes) used
#                                 by COTRN02C to resolve the owning
#                                 account for a given card PAN.
#   * app/cpy-bms/COTRN00.CPY   — Transaction List BMS symbolic map (10
#                                 repeated rows: TRNIDnn, TDATEnn,
#                                 TDESCnn, TAMTnn for nn ∈ {01..10}).
#   * app/cpy-bms/COTRN01.CPY   — Transaction Detail BMS symbolic map
#                                 (15 output fields covering the full
#                                 TRAN-RECORD).
#   * app/cpy-bms/COTRN02.CPY   — Transaction Add BMS symbolic map
#                                 (13 input fields including the CONFIRMI
#                                 success sentinel and ERRMSGI error
#                                 channel).
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
"""Unit tests for :mod:`src.api.routers.transaction_router`.

These tests validate the HTTP surface of Features F-009 (transaction
list), F-010 (transaction detail view), and F-011 (transaction add)
that replace the CICS programs ``COTRN00C.cbl``, ``COTRN01C.cbl``, and
``COTRN02C.cbl`` respectively. They operate purely at the router
layer: :class:`TransactionService` is patched at the router's import
site so every test exercises parameter binding, dependency wiring,
response serialization, and error-routing logic WITHOUT touching the
database or service internals.

COBOL → Python verification matrix
----------------------------------
============================================  ==============================
COBOL construct                               HTTP equivalent asserted here
============================================  ==============================
COTRN00C STARTBR + READNEXT (10 iterations)   GET /transactions → 10/page
COTRN00C jump-to on TRNIDINI (PIC X(16))      GET /transactions?tran_id=...
COTRN00C CTRN00AI.TRNIDnn/TAMTnn (10 slots)   response.transactions[0..9]
COTRN01C READ DATASET('TRANSACT')             GET /transactions/{tran_id}
COTRN01C RESP=NOTFND → "Tran ID NOT found"    404 + _MSG_TRAN_NOT_FOUND
COTRN02C 1020-XREF-LOOKUP (CXACAIX)           POST /transactions xref path
COTRN02C RESP=NOTFND on CXACAIX               404 + _MSG_CARD_NOT_IN_XREF
COTRN02C CONFIRMO = 'Y' (success sentinel)    response.confirm == 'Y'
COTRN02C WS-TRAN-AMT > 0 guard                422 on amount <= 0
COTRN02C WRITE-TRANSACT-FILE on SYNCPOINT     201 CREATED + tran_id
============================================  ==============================

Mocking strategy
----------------
Per the router-unit-test convention established in ``test_bill_router``
and the AAP's isolation rules, every test patches
:class:`TransactionService` at the *router's import site*:

.. code-block:: python

    with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
        mock_instance = mock_service_class.return_value
        mock_instance.list_transactions = AsyncMock(return_value=...)
        ...

Patching at the import site (``src.api.routers.transaction_router
.TransactionService``) rather than the definition site
(``src.api.services.transaction_service.TransactionService``) ensures
that the router's local binding is replaced — the definition site
may be imported by other modules (batch jobs, admin tools) that
must not be affected.

HTTP status-code expectations
-----------------------------
================================  ==============  ========================
Endpoint                          Outcome         HTTP status
================================  ==============  ========================
GET /transactions                 success         200 OK
GET /transactions                 service error   400 Bad Request
GET /transactions                 unauth          401 / 403
GET /transactions/{tran_id}       success         200 OK
GET /transactions/{tran_id}       not found       404 Not Found
GET /transactions/{tran_id}       service error   400 Bad Request
GET /transactions/{tran_id}       unauth          401 / 403
POST /transactions                success         201 CREATED
POST /transactions                xref missing    404 Not Found
POST /transactions                validation      422 Unprocessable Entity
POST /transactions                business fail   400 Bad Request
POST /transactions                unauth          401 / 403
================================  ==============  ========================

Monetary precision discipline
-----------------------------
Per the AAP's Section 0.7.1 financial-precision rules and the
documented COBOL ``TRAN-AMT PIC S9(09)V99`` semantics from
``CVTRA05Y.cpy``, every monetary assertion in this file uses
:class:`decimal.Decimal` constructed from a string literal — NEVER a
float. JSON request bodies send ``amount`` as a quoted string
(``"50.00"``); response-body Decimal fields are reconstructed via
``Decimal(str(body["amount"]))`` to preserve precision across the
Pydantic serialization layer.

The canonical ``test_list_transactions_amount_is_decimal`` and the
schema round-trip in ``test_add_transaction_success`` guard against
any implicit float coercion (``as_tuple().exponent == -2`` asserts
exactly 2 fractional digits for the V99 scale).

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
  exercises the ``get_current_user`` dependency's 401/403 path.
* ``db_session`` — the SAVEPOINT-scoped :class:`AsyncSession` wired
  into the ``get_db`` override in ``test_app``. Not touched directly
  by these tests (all DB work is mocked) but part of the implicit
  contract.

See Also
--------
* :mod:`src.api.routers.transaction_router` — unit under test.
* :mod:`src.api.services.transaction_service` — the mocked collaborator.
* :mod:`src.shared.schemas.transaction_schema` — request/response
  Pydantic contracts.
* ``tests/unit/test_routers/test_bill_router.py`` — reference
  template for router unit tests.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.shared.schemas.transaction_schema import (
    TransactionAddRequest,
    TransactionAddResponse,
    TransactionDetailResponse,
    TransactionListItem,
    TransactionListResponse,
)

# ---------------------------------------------------------------------------
# pytest marker: every test in this module is a router-layer unit test.
# The ``unit`` mark is collected by pyproject.toml's pytest configuration
# and allows CI to run unit tests in isolation from the slower integration
# and e2e layers. All async tests use pytest-asyncio (auto mode).
# ---------------------------------------------------------------------------
pytestmark = pytest.mark.unit

# ---------------------------------------------------------------------------
# Module-level test constants
# ---------------------------------------------------------------------------
# Identity of the regular-user principal synthesized by the
# ``test_app`` fixture (see conftest.py::_TEST_USER_ID). The value is
# not asserted directly by any test but is documented here for
# traceability — any assertion that relies on the user's identity
# should reference this constant rather than hard-coding the string.
_EXPECTED_USER_ID: str = "TESTUSER"

# Import-site patch target for :class:`TransactionService`. Patching
# here replaces the *router module's* local binding only; the
# canonical definition in :mod:`src.api.services.transaction_service`
# remains untouched, which is important because other modules (batch
# jobs, admin tools, integration tests) may import the service from
# its definition site.
_TRANSACTION_SERVICE_PATCH_TARGET: str = "src.api.routers.transaction_router.TransactionService"

# ---------------------------------------------------------------------------
# COBOL-exact error / success message constants
# ---------------------------------------------------------------------------
# These strings MUST match the values defined in
# :mod:`src.api.services.transaction_service` byte-for-byte. The
# service constants are derived from the COBOL programs' WS-ERRMSG /
# ``MOVE '...' TO WS-MESSAGE`` paragraphs and are the contract between
# the service and the router's error-routing logic. Any drift between
# these test constants and the service constants would cause silent
# test-suite rot — grep for ``_MSG_UNABLE_TO_LOOKUP_LIST`` in the
# service to see the original COBOL line references.
# ---------------------------------------------------------------------------
# Source: COTRN00C.cbl lines 615, 649, 683 (lowercase 't', singular).
_MSG_UNABLE_TO_LOOKUP_LIST: str = "Unable to lookup transaction..."

# Source: COTRN01C.cbl ~L152 (empty-string check on WS-TRAN-ID).
_MSG_TRAN_ID_EMPTY: str = "Tran ID can NOT be empty..."

# Source: COTRN01C.cbl ~L290 (WHEN NOTFND on READ TRANSACT). This is
# the ONLY service message containing the substring "NOT found", and
# the router uses that substring as its 404-vs-400 discriminator for
# the detail endpoint — see transaction_router.py L272.
_MSG_TRAN_NOT_FOUND: str = "Transaction ID NOT found..."

# Source: COTRN01C.cbl ~L295 (WHEN OTHER — DB/DFHRESP catch-all).
# Uppercase 'T', singular "Transaction" — distinct from the list
# endpoint's lowercase-t "transaction" wording above.
_MSG_UNABLE_TO_LOOKUP_DETAIL: str = "Unable to lookup Transaction..."

# Source: COTRN02C.cbl ~L633 (WHEN NOTFND on READ CXACAIX). One of
# the two service messages containing the substring "XREF"; the
# router routes anything matching "XREF" to HTTP 404.
_MSG_CARD_NOT_IN_XREF: str = "Unable to lookup Card # in XREF file..."

# Source: modern validation rule (no direct COBOL analogue — the
# service layer asserts acct_id and card_num both resolve to the
# same CXACAIX row). Also matches the "XREF" → 404 routing.
_MSG_ACCT_CARD_MISMATCH: str = "Account/Card mismatch in XREF..."

# Source: COTRN02C.cbl WHEN OTHER on WRITE-TRANSACT-FILE. Routed to
# HTTP 400 because the substring "XREF" is absent.
_MSG_UNABLE_TO_ADD: str = "Unable to Add Transaction..."

# Source: COTRN02C.cbl lines 728-732 (STRING concat of 4 fragments).
# CRITICAL: the two spaces between "successfully." and "Your" are
# intentional — the COBOL STRING statement concatenates the four
# literals verbatim and the spaces are part of the COBOL source. Any
# normalization of the whitespace would break byte-for-byte parity
# with the original success message. There is also a TRAILING PERIOD
# after the tran_id.
_MSG_ADD_SUCCESS_FMT: str = "Transaction added successfully.  Your Tran ID is {tran_id}."

# ---------------------------------------------------------------------------
# Test data constants — deterministic values reused across multiple tests
# ---------------------------------------------------------------------------
# Each constant mirrors a COBOL field with the corresponding PIC
# declaration; the values themselves are fully valid (pass all
# validators) so they can be reused in happy-path tests, and their
# structural shape is what the validation-failure tests mutate (e.g.
# ``_TEST_CARD_NUM[:4]`` produces a too-short card number).
# ---------------------------------------------------------------------------
# Transaction ID: 16 chars, zero-padded — matches COBOL
# ``TRAN-ID PIC X(16)`` in CVTRA05Y.cpy. Primary key of the
# transactions table.
_TEST_TRAN_ID: str = "0000000000000001"

# Account ID: 11 digits — matches COBOL ``ACCT-ID PIC 9(11)`` in
# CVACT01Y.cpy. Foreign key to the accounts table.
_TEST_ACCT_ID: str = "00000000001"

# Card PAN: exactly 16 digits — matches COBOL ``CARD-NUM PIC X(16)``
# in CVACT02Y.cpy. The validator in TransactionAddRequest requires
# exactly 16 numeric characters.
_TEST_CARD_NUM: str = "4111111111111111"

# Transaction type code: exactly 2 chars — matches COBOL
# ``TRAN-TYPE-CD PIC X(02)`` in CVTRA05Y.cpy. '01' = debit/purchase
# per CVTRA03Y.cpy's type catalog.
_TEST_TRAN_TYPE_CD: str = "01"

# Transaction category code: up to 4 chars — matches COBOL
# ``TRAN-CAT-CD PIC X(04)`` in CVTRA05Y.cpy. Part of the composite
# key into CVTRA04Y.cpy's category catalog.
_TEST_TRAN_CAT_CD: str = "0001"

# Transaction source descriptor: up to 10 chars. Human-readable tag
# (e.g. "POS TERM", "ATM", "WEB") carried on the transaction row.
_TEST_TRAN_SOURCE: str = "POS TERM"

# Transaction description: up to 60 chars. Free-form description
# displayed on the statement and in the detail view.
_TEST_DESCRIPTION: str = "Test purchase"

# Monetary amount — Decimal (NEVER float). The V99 scale (exactly 2
# fractional digits) is enforced by the canonical
# ``test_list_transactions_amount_is_decimal`` test and the schema
# round-trip in ``test_add_transaction_success``.
_TEST_AMOUNT: Decimal = Decimal("50.00")

# Origination date (user-entered) — ISO 8601 format accepted by the
# max-length=10 field. The service layer parses to a proper date
# internally but the wire format is a string.
_TEST_ORIG_DATE: str = "2024-01-15"

# Processing date (optional on the request; always populated on the
# response). Matches ``TRAN-PROC-TS`` in CVTRA05Y.cpy.
_TEST_PROC_DATE: str = "2024-01-15"

# Merchant ID: 9 digits — matches COBOL
# ``TRAN-MERCHANT-ID PIC 9(09)`` in CVTRA05Y.cpy.
_TEST_MERCHANT_ID: str = "000000001"

# Merchant name: up to 30 chars on the BMS layout (truncated from
# the underlying PIC X(50) column). Matches COTRN02 ``MNAMEI``.
_TEST_MERCHANT_NAME: str = "Test Merchant"

# Merchant city: up to 25 chars on BMS (from PIC X(50) column).
_TEST_MERCHANT_CITY: str = "New York"

# Merchant ZIP: up to 10 chars — matches COTRN02 ``MZIPI PIC X(10)``.
_TEST_MERCHANT_ZIP: str = "10001"

# Canonical success message used by ``test_add_transaction_success``.
# Constructed once at module-load time so the constant can be
# asserted against verbatim in the response body (including the
# double-space between "successfully." and "Your" — see
# _MSG_ADD_SUCCESS_FMT above).
_SUCCESS_MSG: str = _MSG_ADD_SUCCESS_FMT.format(tran_id=_TEST_TRAN_ID)


# ---------------------------------------------------------------------------
# Response-builder helpers
# ---------------------------------------------------------------------------
# These helpers assemble fully-populated :class:`TransactionListResponse`,
# :class:`TransactionDetailResponse`, and :class:`TransactionAddResponse`
# instances with sensible defaults for each test scenario. They are
# the Python analogues of the COBOL ``MOVE ... TO CTRNxxAO`` stanzas
# that populate the symbolic map output area before ``SEND MAP``.
#
# Each helper accepts keyword overrides for the fields that a given
# test needs to mutate (e.g. ``confirm="N"`` and a specific
# ``message=_MSG_CARD_NOT_IN_XREF`` for the xref-failure path), while
# leaving the remaining fields at their deterministic defaults so
# the tests stay focused on the single attribute under test.
# ---------------------------------------------------------------------------
def _make_list_response(
    count: int = 5,
    page: int = 1,
    total_count: int | None = None,
    message: str | None = None,
) -> TransactionListResponse:
    """Build a :class:`TransactionListResponse` for list-endpoint tests.

    Constructs ``count`` synthetic :class:`TransactionListItem`
    records, each with a unique 16-digit zero-padded ``tran_id``
    derived from the row index (so the 1st item is
    ``"0000000000000001"``, the 2nd is ``"0000000000000002"``, etc.).
    This matches the COBOL ``MOVE TRAN-ID TO TRNIDnn`` pattern in
    ``COTRN00C.cbl`` where each of the ten screen slots receives the
    corresponding ``tran_id`` from the VSAM browse cursor.

    Parameters
    ----------
    count : int
        Number of :class:`TransactionListItem` rows to build. Default
        5; pass 10 for a full page or 0 for the empty-list test.
    page : int
        1-based page number echoed in the response. Default 1.
    total_count : Optional[int]
        Total row count across ALL pages. If ``None`` (default),
        ``total_count`` is set to ``count`` (single-page scenario);
        pass an explicit value > ``count`` for pagination tests.
    message : Optional[str]
        Error-surfacing channel (populated only on service failure).
        Default ``None`` — the success path returns ``message=None``.

    Returns
    -------
    TransactionListResponse
        A fully-populated list response suitable as an
        :class:`AsyncMock` return_value for
        :meth:`TransactionService.list_transactions`.
    """
    if total_count is None:
        total_count = count
    items: list[TransactionListItem] = [
        TransactionListItem(
            # 16-digit zero-padded tran_id, uniquely derived from the
            # row index. Matches COBOL ``TRAN-ID PIC X(16)`` and the
            # COTRN00 ``TRNIDnn PIC X(16)`` screen slots.
            tran_id=f"{i + 1:016d}",
            # Origination date in COBOL ``CCYYMMDD`` format (no dashes)
            # — matches CVTRA05Y.cpy TRAN-ORIG-TS date component.
            tran_date="20240115",
            # Shared description — tests assert structure, not content.
            description=_TEST_DESCRIPTION,
            # Shared amount — CRITICAL: Decimal (never float).
            amount=_TEST_AMOUNT,
        )
        for i in range(count)
    ]
    return TransactionListResponse(
        transactions=items,
        page=page,
        total_count=total_count,
        message=message,
    )


def _make_detail_response(
    tran_id_input: str = _TEST_TRAN_ID,
    tran_id: str = _TEST_TRAN_ID,
    amount: Decimal = _TEST_AMOUNT,
    message: str | None = None,
) -> TransactionDetailResponse:
    """Build a :class:`TransactionDetailResponse` for detail-endpoint tests.

    Populates all 15 fields of the detail schema with deterministic
    test data. Callers override ``tran_id`` to exercise the path
    parameter, ``amount`` for Decimal-precision tests, and
    ``message`` (combined with a mismatched ``tran_id_input``) for
    the not-found / lookup-failure paths.

    Note that the COBOL program echoes the user's *input* key back
    on ``TRNIDINI`` (so "you searched for X, found Y" is visible
    even when X and Y differ). This helper defaults both to the
    same ``_TEST_TRAN_ID`` — override ``tran_id_input`` separately
    if a test needs to simulate a search normalization.

    Parameters
    ----------
    tran_id_input : str
        Echoed search-input transaction ID. Default
        ``_TEST_TRAN_ID``.
    tran_id : str
        Canonical (stored) transaction ID. Default
        ``_TEST_TRAN_ID``.
    amount : Decimal
        Transaction amount. Default ``_TEST_AMOUNT``.
    message : Optional[str]
        Error-surfacing channel. ``None`` on success; pass
        ``_MSG_TRAN_NOT_FOUND`` for the 404 path, or
        ``_MSG_UNABLE_TO_LOOKUP_DETAIL`` / ``_MSG_TRAN_ID_EMPTY`` for
        the 400 paths.

    Returns
    -------
    TransactionDetailResponse
        A fully-populated detail response suitable as an
        :class:`AsyncMock` return_value for
        :meth:`TransactionService.get_transaction_detail`.
    """
    return TransactionDetailResponse(
        tran_id_input=tran_id_input,
        tran_id=tran_id,
        card_num=_TEST_CARD_NUM,
        tran_type_cd=_TEST_TRAN_TYPE_CD,
        tran_cat_cd=_TEST_TRAN_CAT_CD,
        tran_source=_TEST_TRAN_SOURCE,
        description=_TEST_DESCRIPTION,
        amount=amount,
        orig_date=_TEST_ORIG_DATE,
        proc_date=_TEST_PROC_DATE,
        merchant_id=_TEST_MERCHANT_ID,
        merchant_name=_TEST_MERCHANT_NAME,
        merchant_city=_TEST_MERCHANT_CITY,
        merchant_zip=_TEST_MERCHANT_ZIP,
        message=message,
    )


def _make_add_response(
    tran_id: str = _TEST_TRAN_ID,
    acct_id: str = _TEST_ACCT_ID,
    card_num: str = _TEST_CARD_NUM,
    amount: Decimal = _TEST_AMOUNT,
    confirm: str = "Y",
    message: str | None = None,
) -> TransactionAddResponse:
    """Build a :class:`TransactionAddResponse` for add-endpoint tests.

    Populates the 6 response fields (``tran_id``, ``acct_id``,
    ``card_num``, ``amount``, ``confirm``, ``message``) with
    deterministic test data. Successful adds use the defaults
    (``confirm="Y"``, ``message=None``); failure paths override
    ``confirm="N"`` with a specific ``message`` string.

    The router discriminates the failure paths by ``response.confirm
    != "Y"``; the substring ``"XREF"`` in the message routes to 404
    (see transaction_router.py L356).

    Parameters
    ----------
    tran_id : str
        Server-generated transaction ID. Default ``_TEST_TRAN_ID``.
    acct_id : str
        Echoed account ID. Default ``_TEST_ACCT_ID``.
    card_num : str
        Echoed 16-digit card PAN. Default ``_TEST_CARD_NUM``.
    amount : Decimal
        Echoed transaction amount. Default ``_TEST_AMOUNT``.
    confirm : str
        ``'Y'`` on success, ``'N'`` on failure. Default ``'Y'``.
    message : Optional[str]
        Error-surfacing channel. Default ``None``; set the COBOL-
        exact message constant (e.g. ``_MSG_CARD_NOT_IN_XREF``) for
        the failure-path tests.

    Returns
    -------
    TransactionAddResponse
        A fully-populated add response suitable as an
        :class:`AsyncMock` return_value for
        :meth:`TransactionService.add_transaction`.
    """
    return TransactionAddResponse(
        tran_id=tran_id,
        acct_id=acct_id,
        card_num=card_num,
        amount=amount,
        confirm=confirm,
        message=message,
    )


def _make_add_request_body() -> dict[str, Any]:
    """Build a JSON request body for ``POST /transactions``.

    Assembles a dictionary with all 13 fields that
    :class:`TransactionAddRequest` accepts. The ``amount`` field is
    encoded as the *string* ``"50.00"`` (not a float) so Pydantic's
    Decimal field-validator parses it losslessly into ``Decimal
    ("50.00")``. Using a float literal here would introduce IEEE-
    754 binary-representation drift and break the COBOL
    ``PIC S9(09)V99`` precision contract.

    Returns
    -------
    dict[str, Any]
        A dictionary suitable as the ``json=`` keyword argument to
        :meth:`httpx.AsyncClient.post`.
    """
    return {
        "acct_id": _TEST_ACCT_ID,
        "card_num": _TEST_CARD_NUM,
        "tran_type_cd": _TEST_TRAN_TYPE_CD,
        "tran_cat_cd": _TEST_TRAN_CAT_CD,
        "tran_source": _TEST_TRAN_SOURCE,
        "description": _TEST_DESCRIPTION,
        # CRITICAL: amount as a STRING — NEVER float.
        "amount": str(_TEST_AMOUNT),
        "orig_date": _TEST_ORIG_DATE,
        "proc_date": _TEST_PROC_DATE,
        "merchant_id": _TEST_MERCHANT_ID,
        "merchant_name": _TEST_MERCHANT_NAME,
        "merchant_city": _TEST_MERCHANT_CITY,
        "merchant_zip": _TEST_MERCHANT_ZIP,
    }


# ============================================================================
# TestTransactionList
# ----------------------------------------------------------------------------
# Exercises ``GET /transactions`` — replaces the CICS ``COTRN00C``
# program (Feature F-009) that used ``EXEC CICS STARTBR /
# READNEXT`` to browse the TRANSACT VSAM KSDS ten rows at a time
# and populate the COTRN00 BMS map. Tests cover the six required
# scenarios:
#
#   1. Happy path (10 rows returned, HTTP 200).
#   2. Filter pass-through (``tran_id=`` query-parameter reaches
#      :class:`TransactionListRequest.tran_id`).
#   3. Pagination (page 2 of a multi-page result set).
#   4. Empty list (no rows for the given filter).
#   5. Decimal precision preservation (V99 scale round-trip).
#   6. Unauthenticated request → 401/403.
# ============================================================================
class TestTransactionList:
    """Tests for the ``GET /transactions`` endpoint (Feature F-009)."""

    # ------------------------------------------------------------------
    # 1. Successful list — happy path, 10 rows
    # ------------------------------------------------------------------
    async def test_list_transactions_success(self, client: AsyncClient) -> None:
        """Successful transaction list returns HTTP 200 with up to 10 rows.

        Mirrors the full ``COTRN00C.cbl`` ``PROCESS-ENTER-KEY`` →
        ``STARTBR TRANSACT`` → 10 × ``READNEXT`` → ``SEND MAP
        COTRN00A`` happy path. The original program performed the
        VSAM browse in a single CICS transaction, populating the ten
        ``TRNIDnn``/``TDATEnn``/``TDESCnn``/``TAMTnn`` slot groups
        on the COTRN00 BMS map (one slot per row).

        In the Python port, :meth:`TransactionService.list_transactions`
        executes a single SQLAlchemy ``LIMIT 10 OFFSET (page-1)*10``
        query and returns a :class:`TransactionListResponse` with
        ``transactions=[...]``, ``page=1``, ``total_count=N``. The
        router forwards it unchanged.

        Assertions:
            * HTTP 200 OK.
            * Response body contains ``transactions``, ``page``,
              ``total_count``, ``message``.
            * ``transactions`` is a list with at most 10 items (the
              COBOL per-page cap).
            * Each item has the 4 required fields: ``tran_id`` (≤16
              chars), ``tran_date``, ``description``, ``amount``.
            * ``page`` echoes the requested page (default 1).
            * ``message`` is None on success (error-channel silent).
            * :meth:`TransactionService.list_transactions` was called
              exactly once with a :class:`TransactionListRequest`.
        """
        # Build a 10-item response (the per-page cap for F-009).
        mock_response = _make_list_response(
            count=10,
            page=1,
            total_count=10,
        )

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_transactions = AsyncMock(return_value=mock_response)

            response = await client.get("/transactions")

        # HTTP 200 — the list endpoint's default success status.
        assert response.status_code == status.HTTP_200_OK, (
            f"Successful list MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Required envelope fields per :class:`TransactionListResponse`.
        for required_field in ("transactions", "page", "total_count"):
            assert required_field in body, f"Response MUST include ``{required_field}``; got {body}"

        # ``transactions`` MUST be a list with ≤ 10 items (COBOL
        # browse-cap of 10 rows per COTRN00 screen).
        assert isinstance(body["transactions"], list), (
            f"``transactions`` MUST be a JSON array; got {type(body['transactions']).__name__}"
        )
        assert len(body["transactions"]) <= 10, (
            f"``transactions`` MUST contain at most 10 items (COBOL "
            f"COTRN00 10-row screen cap); got {len(body['transactions'])}"
        )
        assert len(body["transactions"]) == 10, (
            f"This test expects exactly 10 items (full page); got {len(body['transactions'])}"
        )

        # Each item must have the 4 required fields from
        # CTRN00AI (TRNIDnn, TDATEnn, TDESCnn, TAMTnn).
        first_item = body["transactions"][0]
        for required_field in ("tran_id", "tran_date", "description", "amount"):
            assert required_field in first_item, f"Each list item MUST include ``{required_field}``; got {first_item}"

        # ``tran_id`` must be ≤ 16 chars (COBOL PIC X(16)).
        assert len(first_item["tran_id"]) <= 16, (
            f"``tran_id`` MUST be at most 16 chars (COBOL PIC X(16)); got length {len(first_item['tran_id'])}"
        )

        # ``page`` echoes the default (1 — no ``?page=`` supplied).
        assert body["page"] == 1, f"``page`` MUST echo the requested page (default 1); got {body['page']}"

        # ``message`` is None on success (error channel silent).
        assert body.get("message") is None, f"Success path MUST have message=None; got {body.get('message')!r}"

        # Verify the service was invoked exactly once.
        mock_service_class.assert_called_once()  # TransactionService(db)
        mock_instance.list_transactions.assert_awaited_once()

    # ------------------------------------------------------------------
    # 2. Filter pass-through — tran_id query parameter
    # ------------------------------------------------------------------
    async def test_list_transactions_with_filter(
        self,
        client: AsyncClient,
    ) -> None:
        """``tran_id`` query parameter is forwarded to the service layer.

        Mirrors the COBOL ``PROCESS-PF7-KEY`` / ``PROCESS-ENTER-KEY``
        branch in ``COTRN00C.cbl`` (lines 310-420) where the user
        entered a partial ``TRNIDINI`` on the screen and the program
        performed an ``EXEC CICS STARTBR TRANSACT RIDFLD(WS-TRAN-ID)
        GTEQ`` to jump to the requested position in the browse
        cursor. In the Python port the same semantics are implemented
        as a SQL ``WHERE tran_id LIKE :filter || '%'`` prefix match.

        Assertions:
            * HTTP 200 OK.
            * The service received a :class:`TransactionListRequest`
              whose ``tran_id`` attribute equals the query-string
              value supplied by the client.
            * ``page`` and ``page_size`` defaulted correctly (1, 10).
        """
        filter_value = "0000000000000001"
        mock_response = _make_list_response(count=1, page=1, total_count=1)

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_transactions = AsyncMock(return_value=mock_response)

            response = await client.get(
                f"/transactions?tran_id={filter_value}",
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Filtered list MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        # Verify the service received the filter.
        mock_instance.list_transactions.assert_awaited_once()
        call_request = mock_instance.list_transactions.call_args.args[0]
        assert call_request.tran_id == filter_value, (
            f"Service MUST receive tran_id filter='{filter_value}'; got {call_request.tran_id!r}"
        )
        # Defaults: page=1, page_size=10.
        assert call_request.page == 1, f"Default page MUST be 1; got {call_request.page}"
        assert call_request.page_size == 10, (
            f"Default page_size MUST be 10 (COBOL 10-row cap); got {call_request.page_size}"
        )

    # ------------------------------------------------------------------
    # 3. Pagination — page=2 with total_count > 10
    # ------------------------------------------------------------------
    async def test_list_transactions_pagination(
        self,
        client: AsyncClient,
    ) -> None:
        """Pagination parameter ``page`` is forwarded and echoed.

        Mirrors the ``PROCESS-PF8-KEY`` / next-page branch of
        ``COTRN00C.cbl`` where the user pressed PF8 to advance the
        browse cursor. The original program re-issued ``STARTBR
        TRANSACT`` with a saved ``WS-NEXT-KEY`` to resume the scan;
        the modern SQL idiom is ``LIMIT 10 OFFSET (page-1)*10`` which
        is idempotent on ``page`` so the forward-page / backward-page
        distinction collapses into a single query path.

        Assertions:
            * HTTP 200 OK.
            * The service received a :class:`TransactionListRequest`
              with ``page=2``.
            * The response ``page`` is 2 (echoed from the mock).
            * ``total_count`` is preserved across the serialization
              boundary (25 in this test).
        """
        mock_response = _make_list_response(
            count=10,
            page=2,
            total_count=25,
        )

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_transactions = AsyncMock(return_value=mock_response)

            response = await client.get("/transactions?page=2")

        assert response.status_code == status.HTTP_200_OK, (
            f"Pagination request MUST return HTTP 200; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        assert body["page"] == 2, f"``page`` MUST echo 2; got {body['page']}"
        assert body["total_count"] == 25, f"``total_count`` MUST be preserved (25); got {body['total_count']}"

        # Verify the service received page=2.
        mock_instance.list_transactions.assert_awaited_once()
        call_request = mock_instance.list_transactions.call_args.args[0]
        assert call_request.page == 2, f"Service MUST receive page=2; got {call_request.page}"

    # ------------------------------------------------------------------
    # 4. Empty list — no matching rows
    # ------------------------------------------------------------------
    async def test_list_transactions_empty(self, client: AsyncClient) -> None:
        """Empty result set returns HTTP 200 with ``transactions=[]``.

        Mirrors the ``PROCESS-ENTER-KEY`` edge case in
        ``COTRN00C.cbl`` where the initial ``STARTBR TRANSACT`` or a
        ``READNEXT`` returned ``RESP=ENDFILE`` on the very first
        iteration — the BMS map was painted with blank slots but the
        transaction proceeded normally (no error). In the Python
        port the same condition manifests as a 0-row SQL result set
        and is considered a success (200), not an error (400/404).

        Assertions:
            * HTTP 200 OK.
            * ``transactions`` is an empty list.
            * ``total_count`` is 0.
            * ``message`` is None (no error).
        """
        mock_response = _make_list_response(count=0, page=1, total_count=0)

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_transactions = AsyncMock(return_value=mock_response)

            response = await client.get("/transactions")

        assert response.status_code == status.HTTP_200_OK, (
            f"Empty list MUST return HTTP 200 (not 404); got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        assert body["transactions"] == [], f"Empty list MUST have transactions=[]; got {body['transactions']!r}"
        assert body["total_count"] == 0, f"Empty list MUST have total_count=0; got {body['total_count']}"
        assert body.get("message") is None, (
            f"Empty list is NOT an error — message MUST be None; got {body.get('message')!r}"
        )

    # ------------------------------------------------------------------
    # 5. Decimal precision — V99 scale round-trip
    # ------------------------------------------------------------------
    async def test_list_transactions_amount_is_decimal(
        self,
        client: AsyncClient,
    ) -> None:
        """Response ``amount`` field preserves COBOL PIC S9(09)V99 precision.

        This is the canonical test for AAP §0.7.2 ("Financial
        precision via Decimal") applied to the list endpoint.
        Validates that the ``amount`` field — which maps to COBOL
        ``TRAN-AMT PIC S9(09)V99`` (CVTRA05Y.cpy) and the COTRN00
        ``TAMTnn`` screen slot — round-trips through the JSON
        serialization layer without precision loss.

        Methodology:
            1. Mock the service to return an item with a precision
               value of ``Decimal("1234.56")``.
            2. Reconstruct the response via
               :class:`TransactionListResponse` — Pydantic v2's
               Decimal validator parses the JSON value and yields a
               :class:`Decimal` instance.
            3. Assert ``isinstance(reconstructed.transactions[0].amount,
               Decimal)``.
            4. Assert ``.as_tuple().exponent == -2`` — proves exactly
               2 fractional digits (the V99 scale). Any intermediate
               float coercion would change the exponent or introduce
               binary representation drift.

        Assertions:
            * HTTP 200 OK.
            * ``reconstructed.transactions[0].amount`` is a Decimal.
            * The Decimal's exponent is exactly ``-2`` (V99 scale).
            * The Decimal value equals the original precision value.
        """
        precision_amount: Decimal = Decimal("1234.56")

        # Manually build a list response with the precision amount
        # (can't use _make_list_response because we want a specific
        # amount, not the default _TEST_AMOUNT).
        mock_response = TransactionListResponse(
            transactions=[
                TransactionListItem(
                    tran_id=_TEST_TRAN_ID,
                    tran_date="20240115",
                    description=_TEST_DESCRIPTION,
                    amount=precision_amount,
                ),
            ],
            page=1,
            total_count=1,
            message=None,
        )

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_transactions = AsyncMock(return_value=mock_response)

            response = await client.get("/transactions")

        assert response.status_code == status.HTTP_200_OK, (
            f"Precision test MUST return HTTP 200; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        assert len(body["transactions"]) == 1, f"Expected exactly 1 item; got {len(body['transactions'])}"

        # Reconstruct via the schema class — this exercises the
        # Pydantic v2 Decimal validator. If the amount field was
        # silently coerced to float anywhere in the pipeline, the
        # reconstructed instance would either error or have an
        # unexpected exponent.
        reconstructed = TransactionListResponse(**body)

        # CRITICAL: the reconstructed amount MUST be a Decimal.
        amount_field = reconstructed.transactions[0].amount
        assert isinstance(amount_field, Decimal), (
            f"Reconstructed amount MUST be Decimal (never float); got {type(amount_field).__name__}"
        )

        # CRITICAL: the reconstructed amount MUST have exactly 2
        # fractional digits (V99 scale). Exponent == -2 proves the
        # Decimal preserves the "1234.56" scale exactly.
        assert amount_field.as_tuple().exponent == -2, (
            f"Reconstructed amount MUST have exponent == -2 (V99 "
            f"scale); got exponent={amount_field.as_tuple().exponent} "
            f"for {amount_field!r}"
        )

        # Round-trip equality — no binary drift.
        assert amount_field == precision_amount, (
            f"Reconstructed amount MUST equal original; got {amount_field!r}, expected {precision_amount!r}"
        )

    # ------------------------------------------------------------------
    # 6. Authentication required — unauthenticated → 401/403
    # ------------------------------------------------------------------
    async def test_list_transactions_requires_auth(
        self,
        test_app: FastAPI,
    ) -> None:
        """Unauthenticated ``GET /transactions`` returns 401 or 403.

        Mirrors the CICS ``RETURN TRANSID(...)`` / COMMAREA session
        validation in ``COTRN00C.cbl`` (lines 92-120) where the
        program rejected any invocation lacking a valid signed-on
        user session. In the Python port the equivalent guard is
        the :func:`get_current_user` dependency in
        :mod:`src.api.dependencies`, which raises
        :class:`HTTPException(401, ...)` when no Authorization
        header is present (or the JWT is invalid/expired).

        Uses a throwaway :class:`AsyncClient` constructed directly
        from the ``test_app`` fixture (which has the dependency
        overrides registered) but WITHOUT the Authorization header
        that the ``client`` fixture adds. This forces the router
        through its 401 path.

        Assertions:
            * Status code is either 401 (canonical) or 403 (depending
              on the OAuth2 scheme's ``auto_error`` behavior).
            * If 401 is returned, the response carries a
              ``WWW-Authenticate`` header per RFC 7235.
        """
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.get("/transactions")

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), f"Unauthenticated request MUST return 401 or 403; got {response.status_code}: {response.text}"
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header (RFC 7235); got headers {dict(response.headers)!r}"
            )


# ============================================================================
# TestTransactionDetail
# ----------------------------------------------------------------------------
# Exercises ``GET /transactions/{tran_id}`` — replaces the CICS
# ``COTRN01C`` program (Feature F-010) that performed a single
# ``EXEC CICS READ DATASET('TRANSACT') RIDFLD(WS-TRAN-ID)`` to fetch
# the full 350-byte CVTRA05Y TRAN-RECORD and painted the COTRN01 BMS
# map with all 15 display fields. Tests cover:
#
#   1. Happy path (full detail record, HTTP 200).
#   2. Not found (service returns _MSG_TRAN_NOT_FOUND, HTTP 404).
#   3. Unauthenticated request → 401/403.
# ============================================================================
class TestTransactionDetail:
    """Tests for the ``GET /transactions/{tran_id}`` endpoint (F-010)."""

    # ------------------------------------------------------------------
    # 1. Successful detail lookup — happy path
    # ------------------------------------------------------------------
    async def test_get_transaction_detail_success(
        self,
        client: AsyncClient,
    ) -> None:
        """Successful detail lookup returns HTTP 200 with full TRAN-RECORD.

        Mirrors the full ``COTRN01C.cbl`` ``PROCESS-ENTER-KEY`` path
        (lines 190-310): the program validated the user's ``TRNIDINI``
        input, performed ``EXEC CICS READ DATASET('TRANSACT')
        RIDFLD(WS-TRAN-ID)`` to fetch the 350-byte CVTRA05Y record,
        populated every CTRN01AO map field (15 outputs covering
        tran_id, card_num, tran_type_cd, tran_cat_cd, tran_source,
        description, amount, orig_date, proc_date, merchant_id,
        merchant_name, merchant_city, merchant_zip), and issued
        ``SEND MAP COTRN01A``.

        In the Python port, :meth:`TransactionService
        .get_transaction_detail` performs a SQLAlchemy primary-key
        lookup in the ``transactions`` table and returns a
        :class:`TransactionDetailResponse` with all 15 fields
        populated. The router forwards it unchanged.

        Assertions:
            * HTTP 200 OK.
            * All 15 response fields are present in the body
              (including the echoed ``tran_id_input``).
            * ``tran_id`` matches the path parameter.
            * ``amount`` round-trips as Decimal.
            * ``message`` is None on success.
            * :meth:`TransactionService.get_transaction_detail` was
              called exactly once with the correct ``tran_id``.
        """
        mock_response = _make_detail_response()

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_transaction_detail = AsyncMock(
                return_value=mock_response,
            )

            response = await client.get(f"/transactions/{_TEST_TRAN_ID}")

        assert response.status_code == status.HTTP_200_OK, (
            f"Successful detail lookup MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # All 15 fields from the CVTRA05Y / COTRN01 BMS map MUST be
        # in the response body.
        required_fields = (
            "tran_id_input",
            "tran_id",
            "card_num",
            "tran_type_cd",
            "tran_cat_cd",
            "tran_source",
            "description",
            "amount",
            "orig_date",
            "proc_date",
            "merchant_id",
            "merchant_name",
            "merchant_city",
            "merchant_zip",
        )
        for required_field in required_fields:
            assert required_field in body, (
                f"Detail response MUST include ``{required_field}``; got {sorted(body.keys())}"
            )

        # ``tran_id`` equals the path parameter.
        assert body["tran_id"] == _TEST_TRAN_ID, (
            f"``tran_id`` MUST echo the path parameter ({_TEST_TRAN_ID!r}); got {body.get('tran_id')!r}"
        )

        # ``card_num`` is 16 chars (COBOL PIC X(16)).
        assert len(body["card_num"]) <= 16, (
            f"``card_num`` MUST be at most 16 chars (PIC X(16)); got length {len(body['card_num'])}"
        )

        # ``tran_type_cd`` is 2 chars (COBOL PIC X(02)).
        assert len(body["tran_type_cd"]) <= 2, (
            f"``tran_type_cd`` MUST be at most 2 chars (PIC X(02)); got length {len(body['tran_type_cd'])}"
        )

        # ``tran_cat_cd`` is 4 chars (COBOL PIC X(04)).
        assert len(body["tran_cat_cd"]) <= 4, (
            f"``tran_cat_cd`` MUST be at most 4 chars (PIC X(04)); got length {len(body['tran_cat_cd'])}"
        )

        # Reconstruct through the schema to verify Decimal round-trip.
        reconstructed = TransactionDetailResponse(**body)
        assert isinstance(reconstructed.amount, Decimal), (
            f"Reconstructed ``amount`` MUST be Decimal (never float); got {type(reconstructed.amount).__name__}"
        )
        assert reconstructed.amount == _TEST_AMOUNT, (
            f"Reconstructed ``amount`` MUST equal original; got {reconstructed.amount!r}, expected {_TEST_AMOUNT!r}"
        )

        # ``message`` is None on success.
        assert body.get("message") is None, f"Success detail MUST have message=None; got {body.get('message')!r}"

        # Verify the service was invoked with the correct tran_id.
        mock_service_class.assert_called_once()  # TransactionService(db)
        mock_instance.get_transaction_detail.assert_awaited_once_with(
            _TEST_TRAN_ID,
        )

    # ------------------------------------------------------------------
    # 2. Not found — service returns _MSG_TRAN_NOT_FOUND → 404
    # ------------------------------------------------------------------
    async def test_get_transaction_not_found(
        self,
        client: AsyncClient,
    ) -> None:
        """Non-existent tran_id returns HTTP 404 with the COBOL-exact message.

        Mirrors the ``WHEN DFHRESP(NOTFND)`` branch in
        ``COTRN01C.cbl`` (~line 290) where the READ operation
        returned RESP=NOTFND and the program moved "Transaction ID
        NOT found..." to WS-ERRMSG before calling ``SEND MAP``. The
        user saw the error text in the COTRN01 ``ERRMSGO`` area and
        the BMS map retained the user's input TRNIDINI so they could
        correct it.

        In the Python port, :meth:`TransactionService
        .get_transaction_detail` returns a
        :class:`TransactionDetailResponse` with ``message=
        _MSG_TRAN_NOT_FOUND`` (the COBOL-exact string) and all
        display fields blank/stub. The router detects the substring
        "NOT found" in the message and raises HTTPException(404).

        Assertions:
            * HTTP 404 Not Found.
            * The COBOL-exact message ``_MSG_TRAN_NOT_FOUND``
              appears in the response body.
            * :meth:`TransactionService.get_transaction_detail` was
              awaited exactly once.
        """
        # Build a detail response with all display fields blank and
        # the not-found message set. The router will detect the
        # "NOT found" substring and convert this into a 404.
        not_found_tran_id = "9999999999999999"
        mock_response = TransactionDetailResponse(
            tran_id_input=not_found_tran_id,
            tran_id="",
            card_num="",
            tran_type_cd="",
            tran_cat_cd="",
            tran_source="",
            description="",
            amount=Decimal("0.00"),
            orig_date="",
            proc_date="",
            merchant_id="",
            merchant_name="",
            merchant_city="",
            merchant_zip="",
            message=_MSG_TRAN_NOT_FOUND,
        )

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_transaction_detail = AsyncMock(
                return_value=mock_response,
            )

            response = await client.get(f"/transactions/{not_found_tran_id}")

        # 404 NOT FOUND — the "NOT found" substring in the message
        # triggers the router's 404 branch.
        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Non-existent tran_id MUST return HTTP 404; got {response.status_code}: {response.text}"
        )

        # The COBOL-exact message MUST be in the response body.
        assert _MSG_TRAN_NOT_FOUND in response.text, (
            f"Response MUST carry the COBOL-exact message {_MSG_TRAN_NOT_FOUND!r}; got {response.text!r}"
        )

        # Verify the service was invoked.
        mock_instance.get_transaction_detail.assert_awaited_once_with(
            not_found_tran_id,
        )

    # ------------------------------------------------------------------
    # 3. Authentication required — unauthenticated → 401/403
    # ------------------------------------------------------------------
    async def test_get_transaction_requires_auth(
        self,
        test_app: FastAPI,
    ) -> None:
        """Unauthenticated ``GET /transactions/{tran_id}`` returns 401 or 403.

        Same auth contract as the list endpoint — the CICS
        COMMAREA session validation in ``COTRN01C.cbl`` (lines 90-
        120) rejected any invocation lacking a signed-on session,
        and the Python port's :func:`get_current_user` dependency
        raises :class:`HTTPException(401, ...)` when the
        Authorization header is absent or carries an invalid JWT.

        Uses a throwaway :class:`AsyncClient` with no Authorization
        header (bypassing the ``client`` fixture's JWT injection).

        Assertions:
            * Status code is either 401 or 403.
            * If 401, WWW-Authenticate header is present (RFC 7235).
        """
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.get(
                f"/transactions/{_TEST_TRAN_ID}",
            )

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), f"Unauthenticated detail request MUST return 401 or 403; got {response.status_code}: {response.text}"
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header (RFC 7235); got headers {dict(response.headers)!r}"
            )


# ============================================================================
# TestTransactionAdd
# ----------------------------------------------------------------------------
# Exercises ``POST /transactions`` — replaces the CICS ``COTRN02C``
# program (Feature F-011) that:
#
#   1. Accepted the add request from the COTRN02 BMS map.
#   2. Read the CCXREF file via acct_id to resolve customer and
#      confirm card_num↔acct_id consistency.
#   3. Browsed the TRANSACT dataset to end-of-file
#      (STARTBR+READPREV) to derive the next tran_id (max + 1,
#      zero-padded to 16 chars).
#   4. Built a 350-byte CVTRA05Y TRAN-RECORD with every field.
#   5. Issued ``EXEC CICS WRITE DATASET('TRANSACT')`` with the new
#      record.
#   6. Reflected the success message
#      "Transaction added successfully.  Your Tran ID is <id>."
#      (with the literal DOUBLE SPACE between "successfully." and
#      "Your") back to the user via the map.
#
# Test cases:
#   1. Happy path (auto-ID assignment, HTTP 201, DOUBLE-SPACE message).
#   2. XREF lookup failure → 404 (_MSG_CARD_NOT_IN_XREF).
#   3. Invalid card_num (too short) → 422 (Pydantic-blocked).
#   4. Invalid acct_id (non-numeric) → 422 (Pydantic-blocked).
#   5. Zero amount → 422 (amount validator rejects amount <= 0).
#   6. Negative amount → 422.
#   7. Unauthenticated request → 401/403.
# ============================================================================
class TestTransactionAdd:
    """Tests for the ``POST /transactions`` endpoint (F-011)."""

    # ------------------------------------------------------------------
    # 1. Successful add — happy path (auto-ID, XREF resolved)
    # ------------------------------------------------------------------
    async def test_add_transaction_success(
        self,
        client: AsyncClient,
    ) -> None:
        """Successful add returns HTTP 201 with auto-generated tran_id.

        Mirrors the full success path of ``COTRN02C.cbl`` ``PROCESS-
        ENTER-KEY`` (lines ~240-620):

        1. Validate all CTRN02AI inputs (acct_id/card_num non-empty,
           type/cat codes valid, amount parseable, merchant fields
           within length limits).
        2. ``EXEC CICS READ DATASET('CCXREF') RIDFLD(WS-ACCT-ID)``
           to resolve the card↔account cross-reference record.
        3. ``EXEC CICS STARTBR DATASET('TRANSACT') RIDFLD(HIGH-
           VALUES)`` + ``EXEC CICS READPREV`` to derive the next
           tran_id (numeric increment of the last key, zero-padded
           to 16 chars).
        4. Populate the 350-byte CVTRA05Y TRAN-RECORD with the
           derived tran_id, xref-resolved card_num/acct_id, input
           type_cd/cat_cd/amount/merchant info, and current timestamps.
        5. ``EXEC CICS WRITE DATASET('TRANSACT')`` with the new record.
        6. Move the SUCCESS-MSG "Transaction added successfully. Your
           Tran ID is <id>." (with a literal DOUBLE SPACE) to the
           error/info area and repaint the map.

        In the Python port, :meth:`TransactionService.add_transaction`
        encapsulates all of steps 2-5 and returns a
        :class:`TransactionAddResponse` with ``confirm="Y"`` and the
        COBOL-exact success message. The router forwards it with HTTP
        201 CREATED (NOT 200 — POST creates a resource).

        Assertions:
            * HTTP 201 CREATED (per router decorator).
            * Response body carries ``confirm="Y"``, auto-generated
              ``tran_id`` (exactly 16 chars), echoed ``acct_id`` and
              ``card_num``, and the COBOL-exact DOUBLE-SPACE success
              message.
            * ``amount`` round-trips as Decimal (never float).
            * :meth:`TransactionService.add_transaction` was awaited
              exactly once with a :class:`TransactionAddRequest`
              whose ``amount`` is a Decimal instance (not coerced to
              float during JSON decoding).
        """
        # Build a valid request body using the helper — every field
        # satisfies the CVTRA05Y/BMS constraints and the Pydantic
        # validators on TransactionAddRequest.
        request_body = _make_add_request_body()

        # The auto-generated tran_id the service will return.
        generated_tran_id = "0000000000001234"
        success_message = _MSG_ADD_SUCCESS_FMT.format(
            tran_id=generated_tran_id,
        )
        mock_response = _make_add_response(
            tran_id=generated_tran_id,
            acct_id=_TEST_ACCT_ID,
            card_num=_TEST_CARD_NUM,
            amount=_TEST_AMOUNT,
            confirm="Y",
            message=success_message,
        )

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.add_transaction = AsyncMock(
                return_value=mock_response,
            )

            response = await client.post(
                "/transactions",
                json=request_body,
            )

        # HTTP 201 CREATED (per the router decorator
        # ``status_code=status.HTTP_201_CREATED``).
        assert response.status_code == status.HTTP_201_CREATED, (
            f"Successful add MUST return HTTP 201 CREATED; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Required fields from CVTRA05Y / TransactionAddResponse.
        required_fields = (
            "tran_id",
            "acct_id",
            "card_num",
            "amount",
            "confirm",
        )
        for required_field in required_fields:
            assert required_field in body, f"Add response MUST include ``{required_field}``; got {sorted(body.keys())}"

        # Auto-ID is 16 chars (COBOL PIC X(16) for WS-TRAN-ID).
        assert len(body["tran_id"]) == 16, (
            f"Auto-generated ``tran_id`` MUST be exactly 16 chars "
            f"(PIC X(16)); got {body['tran_id']!r} (length "
            f"{len(body['tran_id'])})"
        )
        assert body["tran_id"] == generated_tran_id, (
            f"Response ``tran_id`` MUST echo the service's generated "
            f"ID; got {body['tran_id']!r}, expected "
            f"{generated_tran_id!r}"
        )

        # Echoed acct_id and card_num.
        assert body["acct_id"] == _TEST_ACCT_ID
        assert body["card_num"] == _TEST_CARD_NUM

        # ``confirm`` MUST be "Y" on success (CICS program set
        # CONFIRMO = "Y" on successful WRITE).
        assert body["confirm"] == "Y", f"Success response MUST have confirm='Y'; got {body.get('confirm')!r}"

        # The COBOL-exact success message — with the literal
        # DOUBLE SPACE between the two sentences — MUST be
        # preserved byte-for-byte.
        assert body["message"] == success_message, (
            f"Success message MUST match COBOL-exact format "
            f"(note the DOUBLE SPACE); expected "
            f"{success_message!r}, got {body.get('message')!r}"
        )
        # Sanity check: the DOUBLE SPACE MUST be present.
        assert "successfully.  Your Tran ID is" in body["message"], (
            f"Success message MUST retain the DOUBLE SPACE between 'successfully.' and 'Your'; got {body['message']!r}"
        )

        # Decimal round-trip through the response schema.
        reconstructed = TransactionAddResponse(**body)
        assert isinstance(reconstructed.amount, Decimal), (
            f"Reconstructed ``amount`` MUST be Decimal (never float); got {type(reconstructed.amount).__name__}"
        )
        assert reconstructed.amount == _TEST_AMOUNT, (
            f"Reconstructed ``amount`` MUST equal original; got {reconstructed.amount!r}, expected {_TEST_AMOUNT!r}"
        )

        # The service was constructed once with the injected DB
        # session and add_transaction was awaited once with the
        # parsed TransactionAddRequest.
        mock_service_class.assert_called_once()
        mock_instance.add_transaction.assert_awaited_once()

        # Inspect the TransactionAddRequest that the service received
        # to confirm Pydantic parsed ``amount`` as Decimal (not
        # float) even though it came across the wire as a JSON string.
        call_request = mock_instance.add_transaction.call_args.args[0]
        # FastAPI MUST have deserialized the JSON body into a
        # TransactionAddRequest Pydantic model (not a raw dict)
        # before invoking the service — this is the router's
        # contract with the service layer.
        assert isinstance(call_request, TransactionAddRequest), (
            f"Service MUST receive a TransactionAddRequest instance "
            f"(FastAPI-deserialized); got "
            f"{type(call_request).__name__}"
        )
        assert isinstance(call_request.amount, Decimal), (
            f"Service MUST receive amount as Decimal; got {type(call_request.amount).__name__}"
        )
        assert call_request.amount == _TEST_AMOUNT, (
            f"Service MUST receive original amount; got {call_request.amount!r}, expected {_TEST_AMOUNT!r}"
        )
        assert call_request.acct_id == _TEST_ACCT_ID
        assert call_request.card_num == _TEST_CARD_NUM
        assert call_request.tran_type_cd == _TEST_TRAN_TYPE_CD
        assert call_request.tran_cat_cd == _TEST_TRAN_CAT_CD

    # ------------------------------------------------------------------
    # 2. XREF lookup failure — service sets message with "XREF" → 404
    # ------------------------------------------------------------------
    async def test_add_transaction_xref_not_found(
        self,
        client: AsyncClient,
    ) -> None:
        """Missing XREF record returns HTTP 404 with the COBOL message.

        Mirrors the ``WHEN DFHRESP(NOTFND)`` branch in
        ``COTRN02C.cbl`` after the ``EXEC CICS READ DATASET('CCXREF')
        RIDFLD(WS-ACCT-ID)`` call (~lines 430-460). When no cross-
        reference record existed for the supplied acct_id the
        program moved "Unable to lookup Card # in XREF file..." to
        WS-ERRMSG, set CONFIRMO="N", and aborted the add without
        ever writing the TRANSACT record.

        In the Python port, :meth:`TransactionService.add_transaction`
        catches the missing-xref condition and returns a
        :class:`TransactionAddResponse` with ``confirm="N"`` and
        ``message=_MSG_CARD_NOT_IN_XREF`` (which contains the
        substring "XREF" that the router uses to discriminate 404
        from 400).

        Assertions:
            * HTTP 404 Not Found (router detects "XREF" in message).
            * Response body carries the COBOL-exact
              ``_MSG_CARD_NOT_IN_XREF`` string.
            * :meth:`TransactionService.add_transaction` was
              awaited exactly once (service performed the lookup
              but could not satisfy it).
        """
        request_body = _make_add_request_body()
        # Use an acct_id that the service will treat as not in XREF.
        request_body["acct_id"] = "99999999999"

        mock_response = _make_add_response(
            tran_id="",
            acct_id=request_body["acct_id"],
            card_num=request_body["card_num"],
            amount=_TEST_AMOUNT,
            confirm="N",
            message=_MSG_CARD_NOT_IN_XREF,
        )

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.add_transaction = AsyncMock(
                return_value=mock_response,
            )

            response = await client.post(
                "/transactions",
                json=request_body,
            )

        # Router branches on "XREF" substring in message → 404.
        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"XREF-not-found MUST return HTTP 404 (router detects "
            f"'XREF' in message); got {response.status_code}: "
            f"{response.text}"
        )

        # COBOL-exact message MUST be in the response.
        assert _MSG_CARD_NOT_IN_XREF in response.text, (
            f"Response MUST carry the COBOL-exact XREF not-found "
            f"message {_MSG_CARD_NOT_IN_XREF!r}; got "
            f"{response.text!r}"
        )

        # The service did get called — it's the one that detected
        # the missing XREF record.
        mock_instance.add_transaction.assert_awaited_once()

    # ------------------------------------------------------------------
    # 3. Invalid card_num (too short) — Pydantic blocks → 422
    # ------------------------------------------------------------------
    async def test_add_transaction_invalid_card_num(
        self,
        client: AsyncClient,
    ) -> None:
        """Short ``card_num`` is rejected by Pydantic with HTTP 422.

        Mirrors the ``EDIT-CARD-NUM`` paragraph in ``COTRN02C.cbl``
        (~lines 660-690) that verified CARDNINI was exactly 16
        digits, aborting the flow with an error before the CCXREF
        lookup or TRANSACT write could occur.

        In the Python port, the :class:`TransactionAddRequest`
        ``card_num`` validator enforces exactly 16 characters and
        requires ``.isdigit()``. A request with fewer than 16
        characters is rejected by Pydantic before the endpoint
        body runs, producing HTTP 422 Unprocessable Entity.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * ``card_num`` appears in the validation error detail
              (identifies the offending field).
            * :meth:`TransactionService.add_transaction` was NOT
              awaited — proves Pydantic short-circuited before the
              service layer was reached.
        """
        request_body = _make_add_request_body()
        # "4111" is 4 chars — below the 16-char requirement.
        request_body["card_num"] = "4111"

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.add_transaction = AsyncMock(
                return_value=_make_add_response(),
            )

            response = await client.post(
                "/transactions",
                json=request_body,
            )

        # Pydantic validation failure → 422.
        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Short ``card_num`` MUST return HTTP 422; got {response.status_code}: {response.text}"
        )

        # The offending field MUST appear in the error detail so
        # clients can identify which input was invalid.
        assert "card_num" in response.text, (
            f"422 response MUST identify ``card_num`` as the offending field; got {response.text!r}"
        )

        # CRITICAL: prove Pydantic blocked the request before
        # it ever reached the service layer.
        mock_instance.add_transaction.assert_not_awaited()

    # ------------------------------------------------------------------
    # 4. Invalid acct_id (non-numeric) — Pydantic blocks → 422
    # ------------------------------------------------------------------
    async def test_add_transaction_invalid_acct_id(
        self,
        client: AsyncClient,
    ) -> None:
        """Non-numeric ``acct_id`` is rejected by Pydantic with HTTP 422.

        Mirrors the ``EDIT-ACCT-ID`` paragraph in ``COTRN02C.cbl``
        (~lines 635-660) that verified ACTIDINI was numeric (COBOL
        ``IF WS-ACCT-ID IS NUMERIC``) and aborted with an error
        before any file I/O.

        In the Python port, the :class:`TransactionAddRequest`
        ``acct_id`` validator requires ``.isdigit()`` (at most 11
        digits). A request containing alphabetic characters is
        rejected by Pydantic before the endpoint body runs.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * ``acct_id`` appears in the validation error detail.
            * :meth:`TransactionService.add_transaction` was NOT
              awaited.
        """
        request_body = _make_add_request_body()
        # "ABCDEF" is 6 alphabetic chars — not digits.
        request_body["acct_id"] = "ABCDEF"

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.add_transaction = AsyncMock(
                return_value=_make_add_response(),
            )

            response = await client.post(
                "/transactions",
                json=request_body,
            )

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Non-numeric ``acct_id`` MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        assert "acct_id" in response.text, (
            f"422 response MUST identify ``acct_id`` as the offending field; got {response.text!r}"
        )
        mock_instance.add_transaction.assert_not_awaited()

    # ------------------------------------------------------------------
    # 5. Zero amount — Pydantic amount validator → 422
    # ------------------------------------------------------------------
    async def test_add_transaction_zero_amount(
        self,
        client: AsyncClient,
    ) -> None:
        """Zero amount is rejected by Pydantic with HTTP 422.

        Mirrors the ``EDIT-TRAN-AMT`` paragraph in ``COTRN02C.cbl``
        (~lines 710-745) that checked the amount was > 0 (COBOL
        ``IF WS-TRAN-AMT IS GREATER THAN ZERO``) before proceeding
        with the write. A zero amount was a business-rule violation
        because a transaction without value has no financial effect.

        In the Python port, the :class:`TransactionAddRequest`
        ``amount`` validator raises :class:`ValueError` when
        ``value <= Decimal("0")``. Pydantic v2 converts this into a
        422 response.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * ``amount`` appears in the validation error detail.
            * :meth:`TransactionService.add_transaction` was NOT
              awaited.
            * The JSON body uses a string literal for ``amount``
              ("0.00") — never a numeric literal — to preserve
              exact Decimal precision.
        """
        request_body = _make_add_request_body()
        # Amount 0.00 is a business-rule violation — must be > 0.
        request_body["amount"] = str(Decimal("0.00"))

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.add_transaction = AsyncMock(
                return_value=_make_add_response(),
            )

            response = await client.post(
                "/transactions",
                json=request_body,
            )

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Zero amount MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        assert "amount" in response.text, (
            f"422 response MUST identify ``amount`` as the offending field; got {response.text!r}"
        )
        mock_instance.add_transaction.assert_not_awaited()

    # ------------------------------------------------------------------
    # 6. Negative amount — Pydantic amount validator → 422
    # ------------------------------------------------------------------
    async def test_add_transaction_negative_amount(
        self,
        client: AsyncClient,
    ) -> None:
        """Negative amount is rejected by Pydantic with HTTP 422.

        Mirrors the same ``EDIT-TRAN-AMT`` guard in ``COTRN02C.cbl``
        — a negative amount failed the ``IS GREATER THAN ZERO`` test
        and was rejected before the TRANSACT write.

        In the Python port, the :class:`TransactionAddRequest`
        ``amount`` validator's ``if value <= Decimal("0")`` branch
        rejects negative values exactly as it rejects zero.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * ``amount`` appears in the validation error detail.
            * :meth:`TransactionService.add_transaction` was NOT
              awaited.
            * The JSON body uses a string literal for ``amount``
              ("-50.00") — never a numeric literal.
        """
        request_body = _make_add_request_body()
        # Negative amount — same rule as zero amount.
        request_body["amount"] = str(Decimal("-50.00"))

        with patch(_TRANSACTION_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.add_transaction = AsyncMock(
                return_value=_make_add_response(),
            )

            response = await client.post(
                "/transactions",
                json=request_body,
            )

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Negative amount MUST return HTTP 422; got {response.status_code}: {response.text}"
        )
        assert "amount" in response.text, (
            f"422 response MUST identify ``amount`` as the offending field; got {response.text!r}"
        )
        mock_instance.add_transaction.assert_not_awaited()

    # ------------------------------------------------------------------
    # 7. Authentication required — unauthenticated → 401/403
    # ------------------------------------------------------------------
    async def test_add_transaction_requires_auth(
        self,
        test_app: FastAPI,
    ) -> None:
        """Unauthenticated ``POST /transactions`` returns 401 or 403.

        Same auth contract as the list and detail endpoints — the
        COMMAREA session check in ``COTRN02C.cbl`` (lines 90-125)
        rejected invocations without a signed-on session. The Python
        port's :func:`get_current_user` dependency raises
        :class:`HTTPException(401, ...)` when the Authorization header
        is missing or carries an invalid JWT.

        Uses a throwaway :class:`AsyncClient` with no Authorization
        header (bypassing the ``client`` fixture's JWT injection).

        Assertions:
            * Status code is either 401 or 403.
            * If 401, WWW-Authenticate header is present (RFC 7235).
            * No service mock is needed — the dependency injection
              short-circuits before any endpoint logic runs.
        """
        request_body = _make_add_request_body()

        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.post(
                "/transactions",
                json=request_body,
            )

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), f"Unauthenticated add request MUST return 401 or 403; got {response.status_code}: {response.text}"
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header (RFC 7235); got headers {dict(response.headers)!r}"
            )
