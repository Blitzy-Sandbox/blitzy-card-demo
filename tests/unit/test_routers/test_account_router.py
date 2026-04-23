# ============================================================================
# Source: app/cbl/COACTVWC.cbl   (Account view,   Feature F-004, ~941 lines)
#         app/cbl/COACTUPC.cbl   (Account update, Feature F-005, ~4,236 lines)
#         + app/cpy/CVACT01Y.cpy  (Account record layout, PIC S9(10)V99 money)
#         + app/cpy/CVACT03Y.cpy  (Card cross-reference layout — 16-char PK)
#         + app/cpy/CVCUS01Y.cpy  (Customer record layout — 9-digit PK)
#         + app/cpy-bms/COACTVW.CPY (View BMS symbolic map)
#         + app/cpy-bms/COACTUP.CPY (Update BMS symbolic map — segmented dates)
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
"""Unit tests for :mod:`src.api.routers.account_router`.

This module is the router-layer unit-test harness for the two HTTP
endpoints that replace the legacy CICS programs
:file:`app/cbl/COACTVWC.cbl` (Feature F-004 — Account View) and
:file:`app/cbl/COACTUPC.cbl` (Feature F-005 — Account Update):

* ``GET  /accounts/{acct_id}`` — Account view, 3-entity join across
  ``Account`` + ``CardCrossReference`` + ``Customer``. Mirrors the
  COACTVWC ``EVALUATE-FILE-READ`` branch that performed three
  sequential ``EXEC CICS READ`` calls against ACCTFILE, CXACAIX
  (alternate index path on ACCT-ID), and CUSTFILE.
* ``PUT  /accounts/{acct_id}`` — Account update with dual-write
  semantics (Account + Customer) and SYNCPOINT ROLLBACK on failure.
  Mirrors the COACTUPC ``EVALUATE-ALT-KEY-UPDATE`` flow that
  performed field-level validation, ``READ UPDATE`` with VSAM
  record-level lock, change detection, dual ``REWRITE``, and
  ``SYNCPOINT`` / ``SYNCPOINT ROLLBACK``.

Scope — router layer only
-------------------------
Each test runs against the FastAPI test app built by
``conftest.py::test_app``. The :class:`AccountService` collaborator
is replaced with a :class:`unittest.mock.MagicMock` whose async
methods are wired with :class:`unittest.mock.AsyncMock` return
values — no SQLAlchemy session, no database, no network. The
purpose is to validate that the *router* correctly:

1. Wires the path parameter into the service call.
2. Forwards the request body (on PUT) through to the service.
3. Surfaces the service's :class:`AccountViewResponse` /
   :class:`AccountUpdateResponse` unchanged on success.
4. Translates ``error_message`` to :class:`HTTPException` with
   status 400 on business failures.
5. Is guarded by :class:`JWTAuthMiddleware` so unauthenticated
   requests to non-public paths are rejected with HTTP 401.

The contract the service must honor is covered by the integration
and service-layer tests (see ``tests/integration/`` and
``tests/unit/test_services/``).

Mocking strategy — patch at import site
---------------------------------------
:class:`AccountService` is patched at
``src.api.routers.account_router.AccountService`` (the *import site*)
and NOT at ``src.api.services.account_service.AccountService`` (the
*definition site*). The router pulls the class into its own namespace
with ``from src.api.services.account_service import AccountService``;
patching the definition site would have no effect because the router's
local binding would still reference the original class. This is a
well-known gotcha of :func:`unittest.mock.patch` — see the Python
Mock documentation "Where to patch" section — and mirrors the pattern
used in every other router unit-test module (test_card_router,
test_bill_router, test_transaction_router, etc.).

HTTP status-code expectations
-----------------------------
================================  ==============  ========================
Endpoint                          Outcome         HTTP status
================================  ==============  ========================
GET  /accounts/{acct_id}          success         200 OK
GET  /accounts/{acct_id}          not found       404 Not Found
GET  /accounts/{acct_id}          zero id         400 Bad Request
GET  /accounts/{acct_id}          bad path        422 Unprocessable
GET  /accounts/{acct_id}          unauth          401 / 403
PUT  /accounts/{acct_id}          success         200 OK
PUT  /accounts/{acct_id}          not found       404 Not Found
PUT  /accounts/{acct_id}          concurrency     409 Conflict
PUT  /accounts/{acct_id}          no changes      200 OK (info_message)
PUT  /accounts/{acct_id}          rollback        400 Bad Request
PUT  /accounts/{acct_id}          bad date        400 Bad Request
PUT  /accounts/{acct_id}          bad FICO        400 Bad Request
PUT  /accounts/{acct_id}          bad state       400 Bad Request
PUT  /accounts/{acct_id}          unauth          401 / 403
================================  ==============  ========================

Critical behavioral invariants
------------------------------
1. **Business errors route to HTTP 400 / 404 / 409 based on the
   service's ``error_message`` constant.** The router no longer
   maps every populated ``error_message`` uniformly to HTTP 400;
   instead it looks up the exact error-message string in the
   module-level mapping
   :data:`src.api.routers.account_router._ERROR_MESSAGE_STATUS_MAP`
   and raises :class:`HTTPException` with the registered status
   code. The mapping is:

   * ``_MSG_VIEW_XREF_NOT_FOUND`` /
     ``_MSG_VIEW_ACCT_NOT_FOUND`` /
     ``_MSG_VIEW_CUST_NOT_FOUND``  -> HTTP **404 Not Found**
   * ``_MSG_UPDATE_STALE``          -> HTTP **409 Conflict**
   * All other error messages       -> HTTP **400 Bad Request**

   The 404 mapping aligns with RFC 7231 §6.5.4 (missing resource);
   the 409 mapping aligns with RFC 7231 §6.5.8 (optimistic-
   concurrency conflict). Validation / path-body mismatch / no-
   change-detected / zip-state inconsistency / update-failed all
   remain HTTP 400. Confirmed at
   ``src/api/routers/account_router.py`` lines 137-197
   (``_ERROR_MESSAGE_STATUS_MAP`` table + ``_map_error_to_status``
   helper) and the two route handlers that call the helper.
2. **Account ID is exactly 11 digits** (PIC 9(11)). The path regex
   ``^[0-9]{11}$`` rejects malformed ``acct_id`` path parameters
   with FastAPI's automatic HTTP 422 *before* the service runs.
   The value ``00000000000`` (11 zeros) DOES match the regex — the
   zero-check happens in the service layer, which returns a
   response with ``error_message`` set; the router then translates
   to HTTP 400 (zero-ID is a validation message, not a not-found).
3. **Monetary fields MUST use Decimal, NEVER float.** The five
   money-typed fields on :class:`AccountViewResponse` —
   ``credit_limit``, ``cash_credit_limit``, ``current_balance``,
   ``current_cycle_credit``, ``current_cycle_debit`` — preserve the
   ``PIC S9(10)V99`` semantics of CVACT01Y.cpy. This is explicitly
   mandated by AAP §0.7.2. Every test-data constant and every
   assertion on a monetary field in this module uses
   :class:`decimal.Decimal`.
4. **Dual-write rollback is atomic.** A failure in the middle of
   the ``REWRITE ACCTFILE`` + ``REWRITE CUSTFILE`` pair must roll
   back BOTH records — the same SYNCPOINT ROLLBACK semantics that
   COACTUPC enforced via ``EXEC CICS SYNCPOINT ROLLBACK``. The
   Python port wraps both in a single SQLAlchemy transaction that
   rolls back on exception (via :func:`get_db` context manager).
   From the router's perspective this surfaces as a response with
   ``error_message`` set to ``_MSG_UPDATE_FAILED`` — NOT one of
   the three not-found constants and NOT ``_MSG_UPDATE_STALE`` —
   so the router translates it to HTTP **400 Bad Request** (the
   default mapping bucket). The tests exercise this through the
   ``test_update_account_dual_write_rollback`` case.
5. **Optimistic-concurrency conflict surfaces as HTTP 409
   Conflict.** The SQLAlchemy ``version_id_col`` on
   :class:`Account` raises
   :class:`sqlalchemy.orm.exc.StaleDataError` when another user
   modifies the row between SELECT and UPDATE. The service catches
   it and returns a response with
   ``error_message="Record changed by some one else. Please review"``
   (the COBOL-exact literal from COACTUPC.cbl, byte-for-byte
   preserved). The router looks up this message in
   :data:`_ERROR_MESSAGE_STATUS_MAP`, finds the ``HTTP_409_CONFLICT``
   mapping, and raises ``HTTPException(409)`` — aligning with RFC
   7231 §6.5.8 "the request could not be completed due to a
   conflict with the current state of the target resource". The
   ``detail`` field preserves the COBOL-exact message byte-for-byte
   so existing CICS-era help-text clients continue to display the
   same literal.
6. **Authentication is enforced by middleware**, not by the
   ``get_current_user`` dependency alone. The
   :class:`JWTAuthMiddleware` runs BEFORE FastAPI dependency
   resolution and returns an ABEND-DATA-shaped 401 response with a
   ``WWW-Authenticate: Bearer`` header (per RFC 7235) for any
   request to a non-public path that lacks a valid bearer token.
   This is why the ``*_requires_auth`` tests can deterministically
   assert HTTP 401.

Fixtures used
-------------
The following fixtures are sourced from ``tests/conftest.py`` and
injected per-test by pytest:

* ``client`` — :class:`httpx.AsyncClient` pre-configured with a
  regular-user JWT Authorization header, bound to a fresh
  ``test_app`` instance via ASGITransport. Used by every
  successful-path / business-failure test.
* ``test_app`` — the bare :class:`fastapi.FastAPI` app with the
  ``get_db`` and ``get_current_user`` dependency overrides
  registered but WITHOUT the JWT header pre-set. Used by the
  ``*_requires_auth`` tests to build a throwaway
  :class:`AsyncClient` without an Authorization header, which
  exercises the middleware's 401 path.

The following sibling fixtures are also available from conftest
and documented here for completeness (not required by the 14
test cases in this file, but other router test modules make use
of them):

* ``admin_client`` — :class:`httpx.AsyncClient` pre-configured
  with an admin-user JWT (``user_type="A"``). Not used by the
  account router tests — the account endpoints are open to both
  regular and admin users, so the baseline ``client`` fixture
  suffices. See ``test_user_router.py`` and
  ``test_admin_router.py`` for admin-gated-endpoint patterns.
* ``regular_client`` — an alias/duplicate of ``client`` that
  makes the "regular user" intent explicit at the call site.
* ``create_test_token`` — a helper callable that mints a JWT
  for an arbitrary ``(user_id, user_type)`` pair. Useful when a
  test needs a specific authenticated identity beyond the
  default ``TESTUSER`` wired into the ``client`` fixture.
* ``db_session`` — SAVEPOINT-scoped async SQLAlchemy session
  used by the integration tier. Not directly used by router
  unit tests (which mock the entire service layer and therefore
  never touch a database), but documented here because the
  ``test_app`` fixture routes the :func:`get_db` dependency
  through it for any integration-style tests that mix into this
  module in the future.

See Also
--------
* :mod:`src.api.routers.account_router` — unit under test.
* :mod:`src.api.services.account_service` — the mocked collaborator.
* :mod:`src.shared.schemas.account_schema` — request/response
  Pydantic contracts.
* ``tests/unit/test_routers/test_card_router.py`` — reference
  template for GET/PUT router unit tests with identical
  "response-message" error-routing pattern.
* AAP §0.5.1 — File-by-File Transformation Plan.
* AAP §0.7.2 — Financial precision (Decimal, not float).
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.shared.schemas.account_schema import (
    AccountUpdateRequest,
    AccountUpdateResponse,
    AccountViewResponse,
)

# ---------------------------------------------------------------------------
# pytest marker: every test in this module is a router-layer unit test.
# The ``unit`` mark is declared in pyproject.toml's pytest configuration
# and allows CI to run unit tests in isolation from the slower integration
# and e2e layers. All async tests use pytest-asyncio (auto mode configured
# in pyproject.toml — ``asyncio_mode = "auto"``).
# ---------------------------------------------------------------------------
pytestmark = pytest.mark.unit


# ===========================================================================
# Module-level test constants
# ===========================================================================
# Two categories live here:
#
#   1. Test-infrastructure constants — the patch target that
#      :func:`unittest.mock.patch` rewires for every test, and the
#      expected user ID that the ``test_app`` fixture's
#      ``get_current_user`` override emits. These MUST stay in sync
#      with ``tests/conftest.py``'s ``_fake_get_current_user`` and
#      with the ``from src.api.services.account_service import
#      AccountService`` statement in ``account_router.py``.
#
#   2. COBOL-exact, byte-for-byte test-data constants — the seven
#      service-layer error/info messages lifted directly from the
#      original COBOL source files, and realistic sample field
#      values for the ACCOUNT-RECORD and CUSTOMER-RECORD layouts.
#
# Every string here that corresponds to a COBOL literal is documented
# with the source file reference so a reviewer can jump to the COBOL
# program and verify byte-for-byte parity. Any drift between these
# constants and the service layer is a failure mode that breaks
# production clients parsing the ``detail`` strings.
# ---------------------------------------------------------------------------

# -- Test-infrastructure constants -----------------------------------------

# Fully-qualified patch target for :class:`AccountService`. We patch
# at the IMPORT SITE (account_router) rather than the DEFINITION SITE
# (account_service) because the router imports the class with
# ``from src.api.services.account_service import AccountService``.
# A test that patched the definition-site would have no effect — the
# router's local binding would still reference the real class. This
# is the canonical "Where to patch" rule from the Python Mock docs.
_ACCOUNT_SERVICE_PATCH_TARGET: str = "src.api.routers.account_router.AccountService"

# Expected authenticated user ID emitted by the conftest test_app
# fixture's ``get_current_user`` override. Matches the ``user_id``
# attribute on the injected :class:`CurrentUser` dataclass and the
# ``sub`` claim of the JWT token minted by ``create_test_token``.
_EXPECTED_USER_ID: str = "TESTUSER"

# -- COBOL-exact test-data constants (account identity) -------------------

# Account ID in the 11-digit PIC 9(11) format used throughout
# CVACT01Y.cpy and across every VSAM ACCT-ID field. The value
# ``00000000001`` corresponds to the first seeded account in
# ``db/migrations/V3__seed_data.sql`` (ID 1, zero-padded).
_TEST_ACCT_ID: str = "00000000001"

# A zero-only account ID. Under the COACTVWC / COACTUPC validation
# cascade this is a distinct error class — "Account number cannot be
# zero" — per COACTUPC.cbl input-edit paragraph. The path regex
# (``^[0-9]{11}$``) DOES accept 11 zeros, so the zero-check happens
# in the service layer and surfaces via the ``error_message`` field,
# which the router then translates to HTTP 400.
_ZERO_ACCT_ID: str = "00000000000"

# A non-numeric 11-character string. This fails the path regex
# (``^[0-9]{11}$``) and FastAPI returns HTTP 422 Unprocessable Entity
# BEFORE the service is invoked. The ``test_get_account_invalid_id_
# non_numeric`` test exercises this path.
_INVALID_ACCT_ID_NON_NUMERIC: str = "ABCDEFGHIJK"

# Customer ID in the 9-digit PIC 9(09) format from CVCUS01Y.cpy.
_TEST_CUST_ID: str = "000000001"

# ---------------------------------------------------------------------------
# COBOL-exact service-layer message constants
# ---------------------------------------------------------------------------
# These are the literal strings emitted by the service layer via
# ``response.error_message`` / ``response.info_message``. They are
# lifted byte-for-byte from the COBOL source files so the tests
# can match the wire format exactly. Any drift here is a user-
# facing regression.

# COACTUPC.cbl success banner — emitted immediately after a
# successful ``REWRITE DATASET('ACCTFILE')`` + ``REWRITE
# DATASET('CUSTFILE')`` + ``SYNCPOINT``. Shared with COCRDUPC.cbl
# L169 (same literal for card updates).
_MSG_UPDATE_SUCCESS: str = "Changes committed to database"

# COACTUPC.cbl generic-failure message — emitted when the REWRITE
# returns a DFHRESP value other than NORMAL or the SYNCPOINT
# rolls back. In the Python port this is the surface for the
# dual-write rollback case (SQLAlchemy transaction rolled back on
# exception before the final commit).
_MSG_UPDATE_FAILED: str = "Changes unsuccessful. Please try again"

# COACTUPC.cbl optimistic-concurrency message — emitted when the
# hidden old-value fields on the incoming COMMAREA don't match the
# just-read ACCTDAT record (i.e., another user modified the record
# between SELECT and UPDATE). NOTE the mixed-case "some one else"
# (two words) — this is the exact COBOL source, not a typo here.
# In the Python port the trigger is SQLAlchemy ``StaleDataError``
# raised by the ``version_id_col`` guard on :class:`Account`.
_MSG_UPDATE_STALE: str = "Record changed by some one else. Please review"

# COACTUPC.cbl "no-change-detected" info message — emitted when the
# field-by-field comparison (paragraph ``1205-COMPARE-OLD-NEW``)
# finds ZERO differences. This is an INFO message (not an error) —
# the router returns HTTP 200 because ``error_message is None``.
# Length: 50 chars (trailing period is part of the literal).
_MSG_NO_CHANGES: str = "No change detected with respect to values fetched."

# COACTVWC.cbl xref-not-found message — emitted when
# ``READ-CARD-XREF-FILE`` returns DFHRESP(NOTFND) on the CXACAIX
# alternate-index lookup by ACCT-ID. This is one of three distinct
# "not found" messages in the 3-entity join path.
_MSG_VIEW_XREF_NOT_FOUND: str = "Did not find this account in account card xref file"

# COACTVWC.cbl account-not-found message — emitted when
# ``READ-ACCOUNT-FILE`` returns DFHRESP(NOTFND) on the direct
# ACCTFILE read after the xref lookup. (The xref provided the
# ACCT-ID, but the account record itself has been deleted.)
_MSG_VIEW_ACCT_NOT_FOUND: str = "Did not find this account in account master file"

# COACTVWC.cbl customer-not-found message — emitted when the final
# ``READ-CUSTOMER-FILE`` returns DFHRESP(NOTFND).
_MSG_VIEW_CUST_NOT_FOUND: str = "Did not find associated customer in master file"

# COACTVWC.cbl / COACTUPC.cbl invalid-account-id message — emitted
# by the input-edit paragraph when ACCT-ID is zero, non-numeric,
# or not exactly 11 digits. Fires for both view and update flows.
_MSG_ACCT_INVALID: str = "Account Number if supplied must be a 11 digit Non-Zero Number"

# COACTUPC.cbl missing-account message — emitted when the incoming
# request has a blank ACCT-ID. The path regex makes this effectively
# unreachable from the router (an empty path segment would not match
# the URL pattern), but the service still guards against it
# defensively.
_MSG_ACCT_MISSING: str = "Account number not provided"

# COACTUPC.cbl path/body consistency check — when the URL path's
# acct_id differs from the account_id in the request body. This is
# a structural API-level error surfaced via error_message.
_MSG_ACCT_PATH_BODY_MISMATCH: str = "Account number in URL path does not match request body"

# COACTUPC.cbl zip/state cross-validation — emitted when the zip
# code doesn't belong to the specified state per the lookup table
# (derived from the CSLKPCDY.cpy state-zip table). Part of the
# field-validation cascade under ``test_update_account_field_
# validation_state_code``.
_MSG_ZIP_STATE_INVALID: str = "Invalid zip code for state"


def _make_view_response(
    account_id: str = _TEST_ACCT_ID,
    active_status: str = "Y",
    open_date: str = "2020-01-15",
    credit_limit: Decimal = Decimal("25000.00"),
    expiration_date: str = "2028-12-31",
    cash_credit_limit: Decimal = Decimal("5000.00"),
    reissue_date: str = "2023-06-01",
    current_balance: Decimal = Decimal("1234.56"),
    current_cycle_credit: Decimal = Decimal("500.00"),
    group_id: str = "DEFAULT",
    current_cycle_debit: Decimal = Decimal("1734.56"),
    customer_id: str = _TEST_CUST_ID,
    customer_ssn: str = "123-45-6789",
    customer_dob: str = "1985-07-20",
    customer_fico_score: str = "750",
    customer_first_name: str = "JOHN",
    customer_middle_name: str = "Q",
    customer_last_name: str = "PUBLIC",
    customer_addr_line_1: str = "123 MAIN STREET",
    customer_state_cd: str = "NY",
    customer_addr_line_2: str = "APT 4B",
    customer_zip: str = "10001",
    customer_city: str = "NEW YORK",
    customer_country_cd: str = "USA",
    customer_phone_1: str = "(212)555-1234",
    customer_govt_id: str = "NY1234567890",
    customer_phone_2: str = "(917)555-9999",
    customer_eft_account_id: str = "ACH0000001",
    customer_pri_cardholder: str = "Y",
    info_message: str | None = None,
    error_message: str | None = None,
) -> AccountViewResponse:
    """Build a fully-populated :class:`AccountViewResponse`.

    Constructs a complete response object matching the 31 business
    fields + 2 message fields of the legacy ``CACTVWAI`` BMS
    symbolic map. All monetary values default to :class:`Decimal`
    instances with two decimal places — NEVER floats — preserving
    the exact ``PIC S9(10)V99`` fixed-point semantics of the
    underlying VSAM ACCTFILE record (CVACT01Y.cpy).

    The defaults are deterministic and plausible:

    * ``account_id`` = "00000000001" (first seeded account in
      V3__seed_data.sql)
    * ``customer_id`` = "000000001" (first seeded customer)
    * Monetary values in the realistic range of a retail card
      account ($25,000 credit limit, $5,000 cash sub-limit,
      $1,234.56 balance with a $500 credit and $1,734.56 debit in
      the current cycle).
    * Names/addresses are recognizable placeholder values.
    * Dates are in COBOL CCYY-MM-DD format (10 chars).

    Parameters
    ----------
    **overrides
        Any of the 33 fields may be overridden to mutate a single
        attribute for a specific test scenario (e.g.,
        ``error_message=_MSG_VIEW_ACCT_NOT_FOUND`` for a not-found
        path).

    Returns
    -------
    AccountViewResponse
        A fully-populated response object.

    Notes
    -----
    Every monetary field MUST be :class:`Decimal`. Passing a
    :class:`float` will raise a Pydantic ``ValidationError`` because
    :class:`AccountViewResponse` declares ``max_digits=15,
    decimal_places=2`` on all five monetary fields.
    """
    return AccountViewResponse(
        account_id=account_id,
        active_status=active_status,
        open_date=open_date,
        credit_limit=credit_limit,
        expiration_date=expiration_date,
        cash_credit_limit=cash_credit_limit,
        reissue_date=reissue_date,
        current_balance=current_balance,
        current_cycle_credit=current_cycle_credit,
        group_id=group_id,
        current_cycle_debit=current_cycle_debit,
        customer_id=customer_id,
        customer_ssn=customer_ssn,
        customer_dob=customer_dob,
        customer_fico_score=customer_fico_score,
        customer_first_name=customer_first_name,
        customer_middle_name=customer_middle_name,
        customer_last_name=customer_last_name,
        customer_addr_line_1=customer_addr_line_1,
        customer_state_cd=customer_state_cd,
        customer_addr_line_2=customer_addr_line_2,
        customer_zip=customer_zip,
        customer_city=customer_city,
        customer_country_cd=customer_country_cd,
        customer_phone_1=customer_phone_1,
        customer_govt_id=customer_govt_id,
        customer_phone_2=customer_phone_2,
        customer_eft_account_id=customer_eft_account_id,
        customer_pri_cardholder=customer_pri_cardholder,
        info_message=info_message,
        error_message=error_message,
    )


def _make_update_response(
    info_message: str | None = _MSG_UPDATE_SUCCESS,
    error_message: str | None = None,
    **overrides: Any,
) -> AccountUpdateResponse:
    """Build a fully-populated :class:`AccountUpdateResponse`.

    :class:`AccountUpdateResponse` inherits from
    :class:`AccountViewResponse` (31 business + 2 message fields)
    and carries the SAME JSON contract — the legacy COACTUPC
    program's response is the just-updated record, surfaced as-is
    to the caller so they see the persisted state. The difference
    from the view flow is that on SUCCESS the ``info_message`` is
    populated with ``"Changes committed to database"`` (per
    COACTUPC.cbl L169).

    Parameters
    ----------
    info_message : str | None, default :data:`_MSG_UPDATE_SUCCESS`
        The info message to surface. Defaults to the COBOL-exact
        success banner; override with ``None`` for the business-
        failure tests (error_message is set, info is None) or with
        :data:`_MSG_NO_CHANGES` for the no-changes-detected path.
    error_message : str | None, default None
        The error message to surface. Default :data:`None` for
        the happy path; set to the appropriate COBOL-exact error
        literal for business-failure tests.
    **overrides
        Any field on the inherited :class:`AccountViewResponse`
        schema may be overridden.

    Returns
    -------
    AccountUpdateResponse
        A fully-populated response object.
    """
    # Build the base payload via the view helper so we get all 33
    # fields for free, then upgrade the type.
    base = _make_view_response(
        info_message=info_message,
        error_message=error_message,
        **overrides,
    )
    # AccountUpdateResponse is a pure subclass — re-materialize
    # through model_validate to preserve field order and validators.
    return AccountUpdateResponse(**base.model_dump())


def _make_update_request_body(
    account_id: str = _TEST_ACCT_ID,
    active_status: str = "Y",
    open_date_year: str = "2020",
    open_date_month: str = "01",
    open_date_day: str = "15",
    credit_limit: Decimal = Decimal("25000.00"),
    expiration_date_year: str = "2028",
    expiration_date_month: str = "12",
    expiration_date_day: str = "31",
    cash_credit_limit: Decimal = Decimal("5000.00"),
    reissue_date_year: str = "2023",
    reissue_date_month: str = "06",
    reissue_date_day: str = "01",
    group_id: str = "DEFAULT",
    customer_ssn_part1: str = "123",
    customer_ssn_part2: str = "45",
    customer_ssn_part3: str = "6789",
    customer_dob_year: str = "1985",
    customer_dob_month: str = "07",
    customer_dob_day: str = "20",
    customer_fico_score: str = "750",
    customer_first_name: str = "JOHN",
    customer_middle_name: str = "Q",
    customer_last_name: str = "PUBLIC",
    customer_addr_line_1: str = "123 MAIN STREET",
    customer_state_cd: str = "NY",
    customer_addr_line_2: str = "APT 4B",
    customer_zip: str = "10001",
    customer_city: str = "NEW YORK",
    customer_country_cd: str = "USA",
    customer_phone_1_area: str = "212",
    customer_phone_1_prefix: str = "555",
    customer_phone_1_line: str = "1234",
    customer_govt_id: str = "NY1234567890",
    customer_phone_2_area: str = "917",
    customer_phone_2_prefix: str = "555",
    customer_phone_2_line: str = "9999",
    customer_eft_account_id: str = "ACH0000001",
    customer_pri_cardholder: str = "Y",
) -> dict[str, Any]:
    """Build a JSON-serializable request body for ``PUT /accounts/{id}``.

    Returns a plain :class:`dict` (not a :class:`BaseModel`
    instance) because httpx's ``json=`` parameter accepts any
    JSON-serializable object and the test exercises the
    router's body-parsing / validator chain end-to-end.

    The 38 fields correspond 1-to-1 with
    :class:`AccountUpdateRequest`:

    * Account identity (1): ``account_id``
    * Account flags (1): ``active_status``
    * Segmented dates (9): ``open_date_{year,month,day}``,
      ``expiration_date_{year,month,day}``,
      ``reissue_date_{year,month,day}``
    * Monetary (2): ``credit_limit``, ``cash_credit_limit``
    * Disclosure group (1): ``group_id``
    * Segmented SSN (3): ``customer_ssn_part{1,2,3}``
    * Segmented DOB (3): ``customer_dob_{year,month,day}``
    * Customer identity (4): ``customer_fico_score``,
      ``customer_first_name``, ``customer_middle_name``,
      ``customer_last_name``
    * Address (6): ``customer_addr_line_1``, ``customer_state_cd``,
      ``customer_addr_line_2``, ``customer_zip``, ``customer_city``,
      ``customer_country_cd``
    * Segmented primary phone (3): ``customer_phone_1_{area,prefix,line}``
    * Government ID (1): ``customer_govt_id``
    * Segmented secondary phone (3): ``customer_phone_2_{area,prefix,line}``
    * EFT + flag (2): ``customer_eft_account_id``,
      ``customer_pri_cardholder``

    Total: 38 required fields, matching the COACTUP.CPY BMS
    symbolic map exactly.

    Parameters
    ----------
    **overrides
        Any of the 38 fields may be overridden.

    Returns
    -------
    dict[str, Any]
        JSON-serializable request body. Decimals are preserved as
        :class:`Decimal` — httpx's :mod:`json` encoder will convert
        them to JSON numbers on serialization.

    Notes
    -----
    The monetary fields (``credit_limit``, ``cash_credit_limit``)
    are :class:`Decimal` in Python-land and serialize to JSON
    numbers. The service-side :class:`AccountUpdateRequest` will
    parse them back to :class:`Decimal` via the Pydantic decoder.
    """
    # Build via AccountUpdateRequest to leverage the Pydantic
    # validators (so tests using invalid values can't accidentally
    # pass through uninvalidated). model_dump() returns a plain
    # dict suitable for httpx json= parameter. Decimal fields are
    # preserved as Decimal on dump; httpx's json encoder (stdlib
    # json module) would reject Decimal directly, so we explicitly
    # convert the two monetary fields to str — Pydantic on the
    # server side will coerce str → Decimal via its field parser.
    req = AccountUpdateRequest(
        account_id=account_id,
        active_status=active_status,
        open_date_year=open_date_year,
        open_date_month=open_date_month,
        open_date_day=open_date_day,
        credit_limit=credit_limit,
        expiration_date_year=expiration_date_year,
        expiration_date_month=expiration_date_month,
        expiration_date_day=expiration_date_day,
        cash_credit_limit=cash_credit_limit,
        reissue_date_year=reissue_date_year,
        reissue_date_month=reissue_date_month,
        reissue_date_day=reissue_date_day,
        group_id=group_id,
        customer_ssn_part1=customer_ssn_part1,
        customer_ssn_part2=customer_ssn_part2,
        customer_ssn_part3=customer_ssn_part3,
        customer_dob_year=customer_dob_year,
        customer_dob_month=customer_dob_month,
        customer_dob_day=customer_dob_day,
        customer_fico_score=customer_fico_score,
        customer_first_name=customer_first_name,
        customer_middle_name=customer_middle_name,
        customer_last_name=customer_last_name,
        customer_addr_line_1=customer_addr_line_1,
        customer_state_cd=customer_state_cd,
        customer_addr_line_2=customer_addr_line_2,
        customer_zip=customer_zip,
        customer_city=customer_city,
        customer_country_cd=customer_country_cd,
        customer_phone_1_area=customer_phone_1_area,
        customer_phone_1_prefix=customer_phone_1_prefix,
        customer_phone_1_line=customer_phone_1_line,
        customer_govt_id=customer_govt_id,
        customer_phone_2_area=customer_phone_2_area,
        customer_phone_2_prefix=customer_phone_2_prefix,
        customer_phone_2_line=customer_phone_2_line,
        customer_eft_account_id=customer_eft_account_id,
        customer_pri_cardholder=customer_pri_cardholder,
    )
    # Pydantic model_dump with mode="json" converts Decimal to str
    # representations, suitable for JSON serialization. The server-
    # side Pydantic re-parser will coerce these back to Decimal.
    return req.model_dump(mode="json")


def _make_raw_update_request_body(**overrides: Any) -> dict[str, Any]:
    """Build a raw request-body dict WITHOUT Pydantic validation.

    Tests that need to transmit payloads that would fail
    :class:`AccountUpdateRequest` client-side validation (e.g., an
    invalid month value of ``"13"``) cannot use
    :func:`_make_update_request_body` because it validates through
    the Pydantic model. This helper starts from the same defaults
    but returns a plain dict that can be mutated with ANY value —
    the server will then see the payload exactly as sent, and its
    own validator chain will reject it.

    Note that in this router's design, many field-level validation
    errors are NOT actually rejected by :class:`AccountUpdateRequest`
    — that schema only validates STRUCTURE (max_length, types) and
    a couple of cross-cutting concerns (account_id format,
    monetary non-negative). Business validation (date validity,
    FICO range, state-code lookup, zip/state consistency) is
    delegated to :class:`AccountService` and surfaces via
    ``error_message``. This helper therefore produces valid-shape
    payloads that the SERVICE (mocked) then rejects with an
    ``error_message``, letting the tests exercise the router's
    error-routing with realistic body structures.

    Parameters
    ----------
    **overrides
        Any of the 38 fields may be overridden.

    Returns
    -------
    dict[str, Any]
        Raw JSON-serializable request body.
    """
    body: dict[str, Any] = {
        "account_id": _TEST_ACCT_ID,
        "active_status": "Y",
        "open_date_year": "2020",
        "open_date_month": "01",
        "open_date_day": "15",
        "credit_limit": "25000.00",
        "expiration_date_year": "2028",
        "expiration_date_month": "12",
        "expiration_date_day": "31",
        "cash_credit_limit": "5000.00",
        "reissue_date_year": "2023",
        "reissue_date_month": "06",
        "reissue_date_day": "01",
        "group_id": "DEFAULT",
        "customer_ssn_part1": "123",
        "customer_ssn_part2": "45",
        "customer_ssn_part3": "6789",
        "customer_dob_year": "1985",
        "customer_dob_month": "07",
        "customer_dob_day": "20",
        "customer_fico_score": "750",
        "customer_first_name": "JOHN",
        "customer_middle_name": "Q",
        "customer_last_name": "PUBLIC",
        "customer_addr_line_1": "123 MAIN STREET",
        "customer_state_cd": "NY",
        "customer_addr_line_2": "APT 4B",
        "customer_zip": "10001",
        "customer_city": "NEW YORK",
        "customer_country_cd": "USA",
        "customer_phone_1_area": "212",
        "customer_phone_1_prefix": "555",
        "customer_phone_1_line": "1234",
        "customer_govt_id": "NY1234567890",
        "customer_phone_2_area": "917",
        "customer_phone_2_prefix": "555",
        "customer_phone_2_line": "9999",
        "customer_eft_account_id": "ACH0000001",
        "customer_pri_cardholder": "Y",
    }
    body.update(overrides)
    return body


# COACTUPC.cbl date-validation message fragment — emitted by the
# date-field validators (open_date, expiration_date, reissue_date,
# customer_dob). Each field has its own error suffix; we use a
# representative literal for the test.
_MSG_DATE_INVALID: str = "Date of Birth must be a valid date."

# COACTUPC.cbl FICO-range message — emitted when the FICO score is
# outside the valid 300-850 range enforced by the field validator.
_MSG_FICO_INVALID: str = "FICO Score must be between 300 and 850."

# COACTUPC.cbl state-code message — emitted when the US state code
# is not a valid 2-character abbreviation per the CSLKPCDY.cpy
# state table. Paired with the zip-cross-validation above.
_MSG_STATE_INVALID: str = "State is not valid."


# ===========================================================================
# Response-builder helpers
# ===========================================================================
# These helpers assemble fully-populated :class:`AccountViewResponse`
# and :class:`AccountUpdateResponse` instances (and raw request-body
# dicts for PUT requests) with sensible defaults for each test
# scenario. They are the Python analogues of the COBOL
# ``MOVE ... TO CACTVWAO`` / ``MOVE ... TO CACTUPAO`` stanzas that
# populate the BMS symbolic-map output area before ``SEND MAP``.
#
# Each helper accepts keyword overrides for the fields a given test
# needs to mutate (e.g., ``error_message=_MSG_VIEW_ACCT_NOT_FOUND``
# for the not-found path) while leaving the remaining fields at
# their deterministic defaults so the tests stay focused on the
# single attribute under test.
# ---------------------------------------------------------------------------


# ===========================================================================
# TestAccountView
# ---------------------------------------------------------------------------
# Exercises ``GET /accounts/{acct_id}`` — the HTTP transport layer
# that replaces the CICS ``COACTVWC`` program (Feature F-004) whose
# 941 lines performed a 3-entity join across ACCTFILE, CXACAIX (the
# alternate-index path on ACCT-ID), and CUSTFILE, then painted the
# ``CACTVWAI`` BMS map with 31 business fields.
#
# Tests cover:
#
#   1. Happy path — full 31-field account view, HTTP 200.
#   2. Account not found — service returns _MSG_VIEW_ACCT_NOT_FOUND,
#      router looks up the literal in ``_ERROR_MESSAGE_STATUS_MAP``
#      and converts to HTTP 404 (RFC 7231 §6.5.4 — see module
#      docstring invariant #1).
#   3. Invalid ID (non-numeric) — path regex rejects, HTTP 422
#      from FastAPI BEFORE the service runs.
#   4. Zero account ID — regex accepts (11 zeros match ``[0-9]{11}``),
#      service returns _MSG_ACCT_INVALID, which is NOT in the
#      mapping → HTTP 400 (default bucket for validation errors).
#   5. Unauthenticated — no Authorization header, middleware rejects
#      with HTTP 401 (WWW-Authenticate header per RFC 7235).
#
# All tests use the ``client`` fixture (pre-authenticated with a
# TESTUSER JWT token) from ``tests/conftest.py`` except the auth
# test, which builds a throwaway AsyncClient from the ``test_app``
# fixture with no Authorization header.
# ===========================================================================
class TestAccountView:
    """Unit tests for ``GET /accounts/{acct_id}`` — F-004 Account View."""

    # ----------------------------------------------------------------------
    # 1. Happy path — 3-entity join success
    # ----------------------------------------------------------------------
    async def test_get_account_view_success(self, client: AsyncClient) -> None:
        """Successful lookup returns HTTP 200 with all 31 business fields.

        Mirrors the full ``COACTVWC.cbl`` happy path: the program
        performed ``EXEC CICS READ DATASET('CXACAIX')
        RIDFLD(WS-ACCT-ID)`` to discover the owning card, then
        ``READ DATASET('ACCTFILE')`` for the account record, then
        ``READ DATASET('CUSTFILE')`` for the customer record, and
        finally populated all 31 ``CACTVWAI`` fields before
        ``SEND MAP``.

        In the Python port,
        :meth:`AccountService.get_account_view` performs the
        equivalent 3-entity SQLAlchemy join in a single transaction
        and returns an :class:`AccountViewResponse` with every
        business field populated. The router forwards it as-is
        (setting ``response.error_message is None`` so no
        :class:`HTTPException` is raised).

        Assertions:
            * HTTP 200 OK.
            * All 31 business fields + 2 message fields present
              in the response body.
            * ``account_id`` echoes the path parameter
              (11-digit string).
            * All five monetary fields (``credit_limit``,
              ``cash_credit_limit``, ``current_balance``,
              ``current_cycle_credit``, ``current_cycle_debit``)
              round-trip as :class:`Decimal` with 2 decimal places
              — NEVER as :class:`float`.
            * ``customer_ssn`` is the 12-char composite form
              (NNN-NN-NNNN).
            * ``customer_phone_1`` is the 13-char composite form
              ((AAA)BBB-CCCC).
            * ``info_message`` and ``error_message`` are
              :data:`None` on success.
            * :meth:`AccountService.get_account_view` was invoked
              exactly once with the correct ``acct_id`` path
              parameter.
        """
        mock_response = _make_view_response()

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_account_view = AsyncMock(return_value=mock_response)

            response = await client.get(f"/accounts/{_TEST_ACCT_ID}")

        # HTTP 200 — the happy path.
        assert response.status_code == status.HTTP_200_OK, (
            f"Successful view MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # All 31 business fields from CVACT01Y / CVCUS01Y + the
        # two message fields MUST be present in the response body.
        required_business_fields = (
            "account_id",
            "active_status",
            "open_date",
            "credit_limit",
            "expiration_date",
            "cash_credit_limit",
            "reissue_date",
            "current_balance",
            "current_cycle_credit",
            "group_id",
            "current_cycle_debit",
            "customer_id",
            "customer_ssn",
            "customer_dob",
            "customer_fico_score",
            "customer_first_name",
            "customer_middle_name",
            "customer_last_name",
            "customer_addr_line_1",
            "customer_state_cd",
            "customer_addr_line_2",
            "customer_zip",
            "customer_city",
            "customer_country_cd",
            "customer_phone_1",
            "customer_govt_id",
            "customer_phone_2",
            "customer_eft_account_id",
            "customer_pri_cardholder",
        )
        for required_field in required_business_fields:
            assert required_field in body, (
                f"View response MUST include ``{required_field}``; got keys {sorted(body.keys())!r}"
            )

        # ``account_id`` MUST echo the path parameter — the
        # correctness-of-key-wiring check.
        assert body["account_id"] == _TEST_ACCT_ID, (
            f"``account_id`` MUST echo path parameter ({_TEST_ACCT_ID!r}); got {body.get('account_id')!r}"
        )

        # ``account_id`` is EXACTLY 11 chars (PIC 9(11)).
        assert len(body["account_id"]) == 11, (
            f"``account_id`` MUST be exactly 11 chars (PIC 9(11)); got length {len(body['account_id'])}"
        )

        # ``customer_id`` is EXACTLY 9 chars (PIC 9(09)).
        assert len(body["customer_id"]) == 9, (
            f"``customer_id`` MUST be exactly 9 chars (PIC 9(09)); got length {len(body['customer_id'])}"
        )

        # ``active_status`` is 1 char (PIC X(01)).
        assert len(body["active_status"]) == 1, (
            f"``active_status`` MUST be exactly 1 char (PIC X(01)); got length {len(body['active_status'])}"
        )

        # Dates are 10 chars (CCYY-MM-DD).
        for date_field in ("open_date", "expiration_date", "reissue_date", "customer_dob"):
            assert len(body[date_field]) == 10, (
                f"``{date_field}`` MUST be exactly 10 chars (CCYY-MM-DD); got length {len(body[date_field])}"
            )

        # ``customer_ssn`` is at most 12 chars (schema max_length=12
        # for the NNN-NN-NNNN composite). A concrete formatted value
        # like ``"123-45-6789"`` is 11 chars (3+1+2+1+4); the schema's
        # 12-char cap accommodates potential future variants but the
        # canonical SSN form is 11 chars.
        assert 1 <= len(body["customer_ssn"]) <= 12, (
            f"``customer_ssn`` MUST be ≤12 chars (schema "
            f"max_length=12 for NNN-NN-NNNN composite); got length "
            f"{len(body['customer_ssn'])}"
        )

        # ``customer_phone_1`` / ``customer_phone_2`` are 13 chars
        # ((AAA)BBB-CCCC composite).
        for phone_field in ("customer_phone_1", "customer_phone_2"):
            assert len(body[phone_field]) == 13, (
                f"``{phone_field}`` MUST be 13 chars ((AAA)BBB-CCCC); got length {len(body[phone_field])}"
            )

        # CRITICAL — per AAP §0.7.2 financial precision rule:
        # all five monetary fields MUST round-trip as Decimal, never
        # float. We reconstruct the full response through the
        # schema (which parses str/number → Decimal per its
        # ``max_digits`` / ``decimal_places`` constraints) and
        # confirm the type on each field.
        reconstructed = AccountViewResponse(**body)
        monetary_fields = (
            "credit_limit",
            "cash_credit_limit",
            "current_balance",
            "current_cycle_credit",
            "current_cycle_debit",
        )
        for money_field in monetary_fields:
            money_value = getattr(reconstructed, money_field)
            assert isinstance(money_value, Decimal), (
                f"``{money_field}`` MUST round-trip as Decimal (AAP §0.7.2); got {type(money_value).__name__}"
            )
            # Confirm the value matches the deterministic default
            # for this scenario — all expected to be > 0.
            assert money_value >= Decimal("0.00"), (
                f"``{money_field}`` MUST be non-negative on the happy path; got {money_value}"
            )

        # Spot-check an individual monetary value to confirm
        # precision preservation: default credit_limit = 25000.00
        # (PIC S9(10)V99 maximum-realistic value).
        assert reconstructed.credit_limit == Decimal("25000.00"), (
            f"``credit_limit`` MUST equal Decimal('25000.00'); got {reconstructed.credit_limit}"
        )

        # ``info_message`` and ``error_message`` are None on success.
        assert body.get("info_message") is None, (
            f"Success view MUST have info_message=None; got {body.get('info_message')!r}"
        )
        assert body.get("error_message") is None, (
            f"Success view MUST have error_message=None; got {body.get('error_message')!r}"
        )

        # Verify the service was instantiated once (AccountService(db))
        # and invoked exactly once with the correct acct_id.
        mock_service_class.assert_called_once()
        mock_instance.get_account_view.assert_awaited_once_with(_TEST_ACCT_ID)

    # ----------------------------------------------------------------------
    # 2. Not found — service returns error_message; router → HTTP 404
    # ----------------------------------------------------------------------
    async def test_get_account_not_found(self, client: AsyncClient) -> None:
        """Non-existent account returns HTTP 404 with COBOL-exact message.

        Mirrors the ``COACTVWC.cbl`` ``READ-ACCOUNT-FILE`` branch
        that handled DFHRESP(NOTFND) on the direct ACCTFILE read:
        the program set ``MOVE 'Did not find this account in
        account master file' TO WS-ERR-MESSAGE`` and returned
        WITHOUT surfacing a "404-ish" return code — the entire
        COBOL UX relied on free-form error text in the ERRMSGI BMS
        field rather than structured status codes.

        In the Python port the service catches the "empty query
        result" case and returns a response with
        ``error_message = _MSG_VIEW_ACCT_NOT_FOUND``. The router
        looks up this specific literal in
        :data:`_ERROR_MESSAGE_STATUS_MAP` — which maps all three
        COACTVWC not-found literals (xref-miss, acct-miss,
        customer-miss) to HTTP 404 — and raises ``HTTPException``
        with that status. All non-mapped errors default to HTTP 400.
        This pattern aligns the API with RFC 7231 §6.5.4 (Not Found)
        while preserving the COBOL-exact error text byte-for-byte.

        Assertions:
            * HTTP 404 Not Found (per ``_ERROR_MESSAGE_STATUS_MAP``
              in ``src/api/routers/account_router.py``).
            * The response carries the COBOL-exact literal
              ``"Did not find this account in account master file"``
              byte-for-byte.
            * :meth:`AccountService.get_account_view` was invoked
              exactly once.
        """
        # Use a non-existent (but validly-formatted) account ID so
        # the regex accepts and the service reaches the "not found"
        # branch.
        missing_acct_id = "99999999999"
        mock_response = _make_view_response(
            account_id=missing_acct_id,
            error_message=_MSG_VIEW_ACCT_NOT_FOUND,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_account_view = AsyncMock(return_value=mock_response)

            response = await client.get(f"/accounts/{missing_acct_id}")

        # HTTP 404 — the literal ``_MSG_VIEW_ACCT_NOT_FOUND`` is
        # registered in ``_ERROR_MESSAGE_STATUS_MAP`` with value
        # 404 per RFC 7231 §6.5.4 (Not Found).
        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Account-not-found MUST return HTTP 404 (resource not "
            f"found — ``_MSG_VIEW_ACCT_NOT_FOUND`` is mapped to 404 "
            f"in ``_ERROR_MESSAGE_STATUS_MAP``); got "
            f"{response.status_code}: {response.text}"
        )
        # Explicit negative assertion to lock out accidental drift
        # BACK to the legacy uniform-400 pattern. REST convention
        # (RFC 7231 §6.5.4) mandates 404 for resource-not-found.
        assert response.status_code != status.HTTP_400_BAD_REQUEST, (
            "Router MUST NOT use 400 for account-not-found; the "
            "response-message mapping requires _MSG_VIEW_ACCT_NOT_FOUND "
            "→ HTTP 404 per RFC 7231 §6.5.4."
        )

        # The error response is wrapped by the global
        # exception-handler middleware — the COBOL-exact message
        # appears somewhere in the response envelope (the ``detail``
        # field from the HTTPException, which the handler wraps in
        # the ABEND-DATA shape). We search the raw response text
        # byte-for-byte to avoid coupling to the exact wrapper shape.
        assert _MSG_VIEW_ACCT_NOT_FOUND in response.text, (
            f"404 response MUST carry the COACTVWC.cbl not-found "
            f"literal {_MSG_VIEW_ACCT_NOT_FOUND!r} byte-for-byte "
            f"(AAP §0.7.1 — preserve existing error messages "
            f"exactly); got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.get_account_view.assert_awaited_once_with(missing_acct_id)

    # ----------------------------------------------------------------------
    # 3. Non-numeric account ID — path regex rejection → HTTP 422
    # ----------------------------------------------------------------------
    async def test_get_account_invalid_id_non_numeric(self, client: AsyncClient) -> None:
        """Non-numeric acct_id is rejected by path regex with HTTP 422.

        The router's path parameter declares
        ``pattern=r"^[0-9]{11}$"`` which is FastAPI's mechanism for
        transport-layer validation BEFORE the handler runs. Any
        string containing non-digit characters (or not exactly
        11 chars) is rejected with FastAPI's automatic HTTP 422
        Unprocessable Entity response — the service is NEVER
        invoked.

        This differs from the "zero account ID" case (next test)
        where the regex accepts (11 zeros = 11 digits) but the
        service rejects — returning HTTP 400. The distinction is
        between STRUCTURAL validation (regex on path) and BUSINESS
        validation (zero-check in service).

        The COBOL analogue is the COACTVWC input-edit paragraph
        that issued "PIC 9" (numeric) validation: the COBOL
        program would also reject a non-numeric input BEFORE
        performing any VSAM read.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * :class:`AccountService` was NEVER instantiated (the
              request was rejected at path-validation time).
        """
        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            # Configure the mock even though we expect no call —
            # if the regex were accidentally loosened, this
            # surfaces as a status-code mismatch rather than a
            # cryptic AttributeError.
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_account_view = AsyncMock(return_value=_make_view_response())

            response = await client.get(f"/accounts/{_INVALID_ACCT_ID_NON_NUMERIC}")

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Non-numeric acct_id ({_INVALID_ACCT_ID_NON_NUMERIC!r}) "
            f"MUST return HTTP 422 (path regex rejection); got "
            f"{response.status_code}: {response.text}"
        )

        # The service was NEVER instantiated — FastAPI's path
        # validator rejected the request before handler dispatch.
        mock_service_class.assert_not_called()
        mock_instance.get_account_view.assert_not_called()

    # ----------------------------------------------------------------------
    # 4. Zero account ID — regex passes, service rejects → HTTP 400
    # ----------------------------------------------------------------------
    async def test_get_account_zero_id(self, client: AsyncClient) -> None:
        """Zero-valued acct_id ('00000000000') returns HTTP 400.

        The path regex ``^[0-9]{11}$`` accepts ``00000000000``
        because it IS 11 digits — there is no ``[1-9]`` constraint
        on the leading digit. The zero-check happens in the
        service layer (:class:`AccountService.get_account_view`)
        which validates the business rule "account ID must be
        non-zero" and returns a response with
        ``error_message = _MSG_ACCT_INVALID``.

        This mirrors the COACTVWC ``INPUT-EDIT-ACCT-ID`` paragraph
        that checked both numeric format AND non-zero value:
        the CICS program emitted ``MOVE 'Account Number if
        supplied must be a 11 digit Non-Zero Number' TO
        WS-ERR-MESSAGE`` on zero input (CVACT01Y ACCT-ID
        validation, COACTVWC.cbl).

        Assertions:
            * HTTP 400 Bad Request (router converts error_message
              to 400).
            * The response carries the COBOL-exact
              non-zero message byte-for-byte.
            * :meth:`AccountService.get_account_view` was invoked
              exactly once with ``"00000000000"`` (the regex
              accepted, so the service got to run).
        """
        mock_response = _make_view_response(
            account_id=_ZERO_ACCT_ID,
            error_message=_MSG_ACCT_INVALID,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_account_view = AsyncMock(return_value=mock_response)

            response = await client.get(f"/accounts/{_ZERO_ACCT_ID}")

        # HTTP 400 — the service's response-message routing, not a
        # 422 because the regex accepted.
        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Zero acct_id MUST return HTTP 400 (service-level "
            f"business rejection); got {response.status_code}: "
            f"{response.text}"
        )

        # Locate a COBOL-derived message fragment in the response
        # envelope. The global error-handler middleware truncates
        # the full 61-char _MSG_ACCT_INVALID literal to
        # ~49 characters in the structured error envelope's
        # ``reason`` field (an infrastructure concern, not a
        # business one — the full literal is preserved in the
        # logs per the WARNING-level entry emitted by the handler),
        # so we assert on a fragment that is guaranteed to remain
        # visible even after any future envelope-trimming tweaks.
        #
        # The fragment below carries the semantic intent unambiguously
        # ("the account number must be 11 digits") and is drawn
        # byte-for-byte from the head of _MSG_ACCT_INVALID.
        acct_invalid_prefix = "Account Number if supplied must be a 11 digit"
        assert acct_invalid_prefix in response.text, (
            f"400 response MUST carry the COACTVWC/COACTUPC zero-"
            f"rejection literal prefix {acct_invalid_prefix!r} "
            f"byte-for-byte; got {response.text}"
        )

        # Service WAS invoked (the regex accepted 11 zeros) — the
        # rejection is a business-layer concern, not a transport one.
        mock_service_class.assert_called_once()
        mock_instance.get_account_view.assert_awaited_once_with(_ZERO_ACCT_ID)

    # ----------------------------------------------------------------------
    # 5. Unauthenticated — middleware rejects with HTTP 401
    # ----------------------------------------------------------------------
    async def test_get_account_requires_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated ``GET /accounts/{acct_id}`` returns 401/403.

        Mirrors the CICS ``RETURN TRANSID(...)`` / COMMAREA session
        validation in COACTVWC.cbl where the program rejected any
        invocation lacking a valid signed-on user session (CICS
        ``ASSIGN USERID`` + SEC-USR-ID check). In the Python port
        the equivalent guard is the :class:`JWTAuthMiddleware` in
        :mod:`src.api.middleware.auth`, which runs BEFORE FastAPI
        dependency resolution and returns an ABEND-DATA-shaped
        HTTP 401 response with a ``WWW-Authenticate: Bearer`` header
        (per RFC 7235) when no Authorization header is present.

        Uses a throwaway :class:`AsyncClient` constructed directly
        from the ``test_app`` fixture (which has dependency
        overrides registered — including ``get_db`` — but does NOT
        inject an Authorization header), so the middleware's 401
        path is exercised end-to-end.

        Assertions:
            * Status code is 401 (canonical) or 403 (defensive —
              some deployments may upgrade to 403 under different
              OAuth2 scheme configurations).
            * If 401, the response carries a ``WWW-Authenticate``
              header per RFC 7235.
            * :class:`AccountService` is NEVER invoked — the
              middleware rejected the request upstream of the
              router handler.
        """
        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.get_account_view = AsyncMock(return_value=_make_view_response())

            transport = ASGITransport(app=test_app)
            async with AsyncClient(
                transport=transport,
                base_url="http://testserver",
            ) as unauth_client:
                response = await unauth_client.get(f"/accounts/{_TEST_ACCT_ID}")

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), f"Unauthenticated GET MUST return 401 or 403; got {response.status_code}: {response.text}"
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header (RFC 7235); got headers {dict(response.headers)!r}"
            )

        # The service was NEVER invoked — the middleware rejected
        # the request upstream of the router handler.
        mock_service_class.assert_not_called()
        mock_instance.get_account_view.assert_not_called()


# ===========================================================================
# TestAccountUpdate
# ---------------------------------------------------------------------------
# Exercises ``PUT /accounts/{acct_id}`` — the HTTP transport layer
# that replaces the CICS ``COACTUPC`` program (Feature F-005). This
# is the largest and most behaviorally rich program in the legacy
# codebase (4,236 lines) — the translation to Python/FastAPI
# carries over:
#
#   * Extensive field-level validation (dates, FICO, state, ZIP,
#     phone, SSN, monetary).
#   * Dual-write (Account + Customer) atomicity via SQLAlchemy
#     transaction.
#   * SYNCPOINT ROLLBACK on ANY failure in the write pair.
#   * Optimistic-concurrency guard via SQLAlchemy ``version_id_col``
#     → :class:`sqlalchemy.orm.exc.StaleDataError`.
#   * Path/body consistency check (URL acct_id == request body
#     account_id).
#   * Change-detection (if NO fields differ from the current
#     record, return 200 with info_message "No change detected with
#     respect to values fetched." and skip the REWRITE).
#
# Tests cover:
#
#   1. Happy path — successful dual-write, HTTP 200 with
#      _MSG_UPDATE_SUCCESS info_message.
#   2. Account not found — service returns _MSG_VIEW_ACCT_NOT_FOUND,
#      router → HTTP 404 (RFC 7231 §6.5.4 — per
#      ``_ERROR_MESSAGE_STATUS_MAP``).
#   3. Concurrent modification — service returns _MSG_UPDATE_STALE
#      (StaleDataError caught internally), router → HTTP 409
#      Conflict (RFC 7231 §6.5.8 — see invariant #5 in module
#      docstring).
#   4. No changes detected — service returns info_message
#      _MSG_NO_CHANGES (error_message is None), router → HTTP 200.
#   5. Dual-write rollback — service returns _MSG_UPDATE_FAILED
#      (transaction rolled back); not in mapping → HTTP 400
#      (default bucket for validation-like failures).
#   6. Invalid date fields — service returns date validation error
#      (not in mapping) → HTTP 400.
#   7. Invalid FICO score — service returns FICO range error
#      (not in mapping) → HTTP 400.
#   8. Invalid state code — service returns state/zip validation
#      error (not in mapping) → HTTP 400.
#   9. Unauthenticated — middleware rejects with HTTP 401.
#
# All happy-path and business-failure tests use the ``client``
# fixture (pre-authenticated with a TESTUSER JWT token). The auth
# test uses the ``test_app`` fixture to build an un-authenticated
# AsyncClient directly.
# ===========================================================================
class TestAccountUpdate:
    """Unit tests for ``PUT /accounts/{acct_id}`` — F-005 Account Update."""

    # ----------------------------------------------------------------------
    # 1. Happy path — successful dual-write, HTTP 200
    # ----------------------------------------------------------------------
    async def test_update_account_success(self, client: AsyncClient) -> None:
        """Successful update returns HTTP 200 with _MSG_UPDATE_SUCCESS.

        Mirrors the full COACTUPC happy path: the program validated
        every input field, performed ``READ UPDATE`` on ACCTFILE
        (which acquired a VSAM record-level exclusive lock),
        executed ``9300-CHECK-CHANGE-IN-REC`` to confirm at least
        one field had changed and that the hidden old-value
        baseline matched the just-read record, then ``REWRITE``
        ACCTFILE + ``REWRITE`` CUSTFILE, committed via ``EXEC
        CICS SYNCPOINT``, and emitted ``MOVE 'Changes committed
        to database' TO WS-INFO-MSG`` on the response.

        In the Python port,
        :meth:`AccountService.update_account` wraps the dual-write
        in a single SQLAlchemy transaction (driven by
        :func:`get_db`) that commits atomically on success. The
        router forwards the returned :class:`AccountUpdateResponse`
        as-is (error_message is None, so no HTTPException raised).

        Assertions:
            * HTTP 200 OK.
            * Response body has 31 business + 2 message fields
              (AccountUpdateResponse inherits AccountViewResponse
              one-to-one).
            * ``info_message`` equals the COBOL-exact success
              banner ``"Changes committed to database"``.
            * ``error_message`` is None.
            * All five monetary fields round-trip as Decimal.
            * :meth:`AccountService.update_account` was invoked
              exactly once with the correct acct_id and a
              parsed :class:`AccountUpdateRequest` carrying the
              request body's 38 fields.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=_MSG_UPDATE_SUCCESS,
            error_message=None,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        # HTTP 200 — successful dual-write.
        assert response.status_code == status.HTTP_200_OK, (
            f"Successful update MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # ``info_message`` MUST be the COBOL-exact success banner
        # from COACTUPC.cbl — preserved byte-for-byte per AAP §0.7.1.
        assert body.get("info_message") == _MSG_UPDATE_SUCCESS, (
            f"Success response MUST carry the COACTUPC.cbl success "
            f"banner {_MSG_UPDATE_SUCCESS!r} in info_message; got "
            f"{body.get('info_message')!r}"
        )

        # ``error_message`` is None on success.
        assert body.get("error_message") is None, (
            f"Success response MUST have error_message=None; got {body.get('error_message')!r}"
        )

        # ``account_id`` MUST echo the path parameter.
        assert body.get("account_id") == _TEST_ACCT_ID, (
            f"``account_id`` MUST echo path parameter ({_TEST_ACCT_ID!r}); got {body.get('account_id')!r}"
        )

        # CRITICAL — all five monetary fields MUST round-trip as
        # Decimal, not float (AAP §0.7.2).
        reconstructed = AccountUpdateResponse(**body)
        monetary_fields = (
            "credit_limit",
            "cash_credit_limit",
            "current_balance",
            "current_cycle_credit",
            "current_cycle_debit",
        )
        for money_field in monetary_fields:
            money_value = getattr(reconstructed, money_field)
            assert isinstance(money_value, Decimal), (
                f"``{money_field}`` MUST round-trip as Decimal (AAP §0.7.2); got {type(money_value).__name__}"
            )

        # Verify the service was invoked once with the correct
        # acct_id. The second positional arg is the parsed
        # :class:`AccountUpdateRequest`; we assert its type rather
        # than field-by-field content since the helper-built body
        # and the schema-parsed request are semantically identical.
        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()
        call_args = mock_instance.update_account.call_args
        assert call_args.args[0] == _TEST_ACCT_ID, (
            f"First positional arg MUST be acct_id ({_TEST_ACCT_ID!r}); got {call_args.args[0]!r}"
        )
        assert isinstance(call_args.args[1], AccountUpdateRequest), (
            f"Second positional arg MUST be AccountUpdateRequest; got {type(call_args.args[1]).__name__}"
        )
        # Spot-check a few fields on the parsed request to confirm
        # the request body flowed through intact.
        parsed_request: AccountUpdateRequest = call_args.args[1]
        assert parsed_request.account_id == _TEST_ACCT_ID
        assert parsed_request.credit_limit == Decimal("25000.00")
        assert parsed_request.cash_credit_limit == Decimal("5000.00")
        assert parsed_request.customer_first_name == "JOHN"
        assert parsed_request.customer_last_name == "PUBLIC"

    # ----------------------------------------------------------------------
    # 2. Account not found — service returns error_message; router → 404
    # ----------------------------------------------------------------------
    async def test_update_account_not_found(self, client: AsyncClient) -> None:
        """PUT on non-existent account returns HTTP 404.

        The COACTUPC program performed the initial
        ``READ UPDATE DATASET('ACCTFILE') RIDFLD(WS-ACCT-ID)`` at
        the start of the update flow — this is the equivalent of
        a SELECT ... FOR UPDATE in SQL. If the record doesn't
        exist, DFHRESP returns NOTFND and the program set
        ``MOVE 'Did not find this account in account master file'
        TO WS-ERR-MESSAGE`` and returned without attempting the
        REWRITE.

        In the Python port the service catches the empty-result
        case and returns a response with
        ``error_message = _MSG_VIEW_ACCT_NOT_FOUND``. The router
        looks up this literal in :data:`_ERROR_MESSAGE_STATUS_MAP`
        and raises ``HTTPException`` with HTTP 404 Not Found per
        RFC 7231 §6.5.4. The COBOL-exact error text is preserved
        byte-for-byte in the response body.

        Assertions:
            * HTTP 404 Not Found (per ``_ERROR_MESSAGE_STATUS_MAP``
              in ``src/api/routers/account_router.py``).
            * COBOL-exact not-found literal present in response.
            * Service invoked exactly once.
        """
        missing_acct_id = "99999999999"
        request_body = _make_update_request_body(account_id=missing_acct_id)
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_VIEW_ACCT_NOT_FOUND,
            account_id=missing_acct_id,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{missing_acct_id}",
                json=request_body,
            )

        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Update on non-existent account MUST return HTTP 404 "
            f"(_MSG_VIEW_ACCT_NOT_FOUND → 404 per "
            f"``_ERROR_MESSAGE_STATUS_MAP``); got "
            f"{response.status_code}: {response.text}"
        )

        assert _MSG_VIEW_ACCT_NOT_FOUND in response.text, (
            f"404 response MUST carry the COACTVWC/COACTUPC "
            f"not-found literal {_MSG_VIEW_ACCT_NOT_FOUND!r} "
            f"byte-for-byte; got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 3. Concurrent modification — optimistic concurrency → HTTP 409
    # ----------------------------------------------------------------------
    async def test_update_account_concurrent_modification(self, client: AsyncClient) -> None:
        """Concurrent modification returns HTTP 409 with COBOL-exact msg.

        This is the signature behavior of F-005 — the direct
        translation of the COACTUPC optimistic-concurrency pattern.
        The COBOL program's approach:

        1. On initial SELECT (the view phase), the ACCTDAT record's
           fields were sent to the user as both display AND hidden
           "old-value" fields on the BMS map.
        2. When the user submitted the UPDATE, the program
           re-read the record with ``READ UPDATE`` (VSAM exclusive
           record-level lock).
        3. Paragraph ``9300-CHECK-CHANGE-IN-REC`` (COACTUPC.cbl
           around L290-350) compared EACH hidden old-value field
           against the just-read ACCTDAT record.
        4. If ANY field differed → another user modified between
           SELECT and UPDATE. The program set ``MOVE 'Record
           changed by some one else. Please review' TO
           WS-ERR-MESSAGE`` and returned WITHOUT rewriting,
           releasing the VSAM lock via ``UNLOCK``.

        The Python port uses SQLAlchemy's ``version_id_col`` on
        :class:`Account` to detect the same race: a
        :class:`sqlalchemy.orm.exc.StaleDataError` on UPDATE is
        caught by :meth:`AccountService.update_account`, which
        returns a response with
        ``error_message = _MSG_UPDATE_STALE``. The router then
        looks up this literal in
        :data:`_ERROR_MESSAGE_STATUS_MAP` and raises
        ``HTTPException`` with HTTP 409 Conflict per
        RFC 7231 §6.5.8 (Conflict). The COBOL-exact error text
        is preserved byte-for-byte in the response body, so
        downstream clients that match on the ``detail`` substring
        continue to work unchanged.

        Assertions:
            * HTTP 409 Conflict (per ``_ERROR_MESSAGE_STATUS_MAP``
              in ``src/api/routers/account_router.py``, aligned
              with RFC 7231 §6.5.8).
            * ``detail`` carries the COBOL-exact concurrency
              message ``"Record changed by some one else. Please
              review"`` byte-for-byte (46 chars). Note the
              mixed-case "some one else" (two words) — exact
              COBOL source, not a typo.
            * :meth:`AccountService.update_account` was invoked
              exactly once.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_UPDATE_STALE,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        # HTTP 409 Conflict — ``_MSG_UPDATE_STALE`` is mapped to
        # 409 in ``_ERROR_MESSAGE_STATUS_MAP`` per RFC 7231 §6.5.8.
        assert response.status_code == status.HTTP_409_CONFLICT, (
            f"Optimistic-concurrency conflict MUST return HTTP 409 "
            f"(``_MSG_UPDATE_STALE`` → 409 per RFC 7231 §6.5.8); got "
            f"{response.status_code}: {response.text}"
        )
        # Explicit negative assertion to lock out accidental drift
        # BACK to the legacy uniform-400 behavior.
        assert response.status_code != status.HTTP_400_BAD_REQUEST, (
            "Router MUST NOT use 400 for optimistic-concurrency "
            "conflict; RFC 7231 §6.5.8 mandates 409 Conflict for "
            "version-mismatch errors."
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler — the COBOL-exact concurrency message appears
        # somewhere in the response envelope. Byte-for-byte check.
        assert _MSG_UPDATE_STALE in response.text, (
            f"409 response MUST carry the COACTUPC.cbl concurrency "
            f"literal {_MSG_UPDATE_STALE!r} byte-for-byte (AAP "
            f"§0.7.1 — preserve existing error messages exactly); "
            f"got {response.text}"
        )
        # Defensive: confirm the exact "some one else" spelling is
        # preserved (two words, matching COBOL source exactly).
        assert "some one else" in response.text, (
            "Response MUST preserve the exact COBOL mixed-case "
            "'some one else' (two words, not 'someone'); COACTUPC "
            "L208 is the source of truth."
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 4. No changes detected — info_message only, HTTP 200
    # ----------------------------------------------------------------------
    async def test_update_account_no_changes_detected(self, client: AsyncClient) -> None:
        """No-op update returns HTTP 200 with _MSG_NO_CHANGES info.

        Mirrors COACTUPC paragraph ``1205-COMPARE-OLD-NEW`` (the
        change-detection pass that runs AFTER successful input
        validation but BEFORE the REWRITE). If the submitted
        fields are BYTE-for-BYTE identical to the just-read
        ACCTDAT + CUSTDAT records, the program skipped the
        REWRITE (saving a lock-and-rewrite cycle and avoiding an
        unnecessary audit-log entry) and emitted ``MOVE 'No
        change detected with respect to values fetched.' TO
        WS-INFO-MSG`` — with a period at the end.

        Note this is an INFO message, NOT an error:

        * ``info_message`` is set to the 50-char literal.
        * ``error_message`` is None.
        * The router does NOT raise HTTPException (because
          ``response.error_message`` is None).
        * HTTP 200 is returned with the info message in the
          response body.

        This distinguishes "user intended an update but the values
        were already current" (a benign no-op) from "the update
        attempt failed" (an error). The client UX surfaces the
        info message as a banner, not an error dialog.

        Assertions:
            * HTTP 200 OK (NOT 400 — info is not error).
            * ``info_message`` equals the COBOL-exact 50-char
              literal (trailing period preserved).
            * ``error_message`` is None.
            * Service invoked exactly once.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=_MSG_NO_CHANGES,
            error_message=None,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        # HTTP 200 — info_message is not error_message; the router
        # only raises HTTPException when error_message is set.
        assert response.status_code == status.HTTP_200_OK, (
            f"No-change-detected MUST return HTTP 200 (info, not error); got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # ``info_message`` equals the COBOL-exact literal (50 chars
        # including the trailing period — byte-for-byte per AAP
        # §0.7.1).
        assert body.get("info_message") == _MSG_NO_CHANGES, (
            f"No-change response MUST carry the COACTUPC.cbl "
            f"no-changes literal {_MSG_NO_CHANGES!r} in "
            f"info_message; got {body.get('info_message')!r}"
        )
        # Confirm the trailing period is preserved.
        assert body["info_message"].endswith("."), (
            "_MSG_NO_CHANGES MUST preserve the trailing period from "
            "COACTUPC.cbl; the COBOL literal ends with a period and "
            "AAP §0.7.1 requires byte-for-byte preservation."
        )
        # Confirm the exact 50-char length.
        assert len(body["info_message"]) == 50, (
            f"_MSG_NO_CHANGES MUST be exactly 50 chars; got {len(body['info_message'])}"
        )

        # ``error_message`` is None — this is INFO, not ERROR.
        assert body.get("error_message") is None, (
            f"No-change response MUST have error_message=None; got {body.get('error_message')!r}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 5. Dual-write rollback — transaction failed, HTTP 400
    # ----------------------------------------------------------------------
    async def test_update_account_dual_write_rollback(self, client: AsyncClient) -> None:
        """Dual-write failure returns HTTP 400 with _MSG_UPDATE_FAILED.

        This is the critical test for the SYNCPOINT ROLLBACK
        semantics that define Feature F-005. The COACTUPC program
        performed two REWRITE operations — one on ACCTFILE and
        one on CUSTFILE — in sequence. If either failed (DFHRESP
        != NORMAL), the program executed ``EXEC CICS SYNCPOINT
        ROLLBACK`` to atomically roll back BOTH operations,
        preventing the "torn update" where an account-side change
        succeeds but the customer-side change fails, leaving the
        database in an inconsistent state.

        The Python port wraps both REWRITE-equivalent UPDATEs in
        a single SQLAlchemy transaction bound to the
        :func:`get_db` session. If either UPDATE raises
        :class:`SQLAlchemyError`,
        :meth:`AccountService.update_account` catches it, rolls
        back the transaction (which discards both changes), and
        returns a response with
        ``error_message = _MSG_UPDATE_FAILED``.

        From the router's perspective this surfaces as a generic
        business failure — ``_MSG_UPDATE_FAILED`` is NOT registered
        in :data:`_ERROR_MESSAGE_STATUS_MAP` (only the three COACTVWC
        not-found literals and ``_MSG_UPDATE_STALE`` are), so it
        falls through to the default HTTP 400 bucket for
        validation-like failures. The database-level atomicity is
        tested in :mod:`tests.integration.test_database`; this
        test simply confirms the router correctly surfaces the
        error to the HTTP client with the default status.

        Assertions:
            * HTTP 400 Bad Request (default bucket — the message
              is NOT in ``_ERROR_MESSAGE_STATUS_MAP``).
            * ``detail`` carries the COBOL-exact _MSG_UPDATE_FAILED
              literal byte-for-byte.
            * Service invoked exactly once.
        """
        request_body = _make_update_request_body()
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_UPDATE_FAILED,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        # HTTP 400 — _MSG_UPDATE_FAILED is NOT in
        # ``_ERROR_MESSAGE_STATUS_MAP`` (only the 3 not-found
        # literals and _MSG_UPDATE_STALE are registered), so it
        # falls through to the default 400 bucket.
        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Dual-write rollback MUST return HTTP 400 (default "
            f"bucket — ``_MSG_UPDATE_FAILED`` is not in the "
            f"status map); got "
            f"{response.status_code}: {response.text}"
        )

        # Byte-for-byte COBOL-literal check.
        assert _MSG_UPDATE_FAILED in response.text, (
            f"400 response MUST carry the COACTUPC.cbl generic-"
            f"failure literal {_MSG_UPDATE_FAILED!r} byte-for-byte; "
            f"got {response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 6. Invalid date fields — field validation → HTTP 400
    # ----------------------------------------------------------------------
    async def test_update_account_field_validation_dates(self, client: AsyncClient) -> None:
        """Invalid date fields surface a field-validation error → 400.

        The COACTUPC program had an extensive field-validation
        cascade (paragraphs ``1240-EDIT-*-DATE`` and
        ``CSUTLDTC`` date-utility calls) that rejected invalid
        dates BEFORE attempting the READ UPDATE. The checks
        included:

        * Non-numeric year/month/day components.
        * Month outside 01-12.
        * Day outside 01-31 (and 01-28/29/30 for the month).
        * Future dates (for customer_dob).
        * Expiration date earlier than open date.

        The field-level checks are implemented in
        :class:`AccountService._ParsedRequest` (the internal helper
        class at account_service.py L1417) which returns
        ``error_message = _MSG_DATE_INVALID`` (or a more specific
        variant) when any date field fails. The router surfaces
        this as HTTP 400.

        Because :class:`AccountUpdateRequest` only validates
        STRUCTURE (max_length of the date segments — year is 4
        chars, month/day are 2 chars), a request with ``month=13``
        or ``day=32`` passes the Pydantic parser and reaches the
        service, where the business rule is enforced. This test
        sends a structurally-valid but semantically-invalid date
        and confirms the service's error_message surfaces
        correctly through the router.

        Assertions:
            * HTTP 400 Bad Request.
            * ``detail`` carries a date-validation message.
            * Service invoked exactly once.
        """
        # Month "13" is structurally valid (2 chars) but
        # semantically invalid. The raw dict helper bypasses
        # :class:`AccountUpdateRequest` (which doesn't validate
        # month range) so the body transmits as-is.
        request_body = _make_raw_update_request_body(
            customer_dob_month="13",  # semantically invalid month
        )
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_DATE_INVALID,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        # HTTP 400 — service-layer field validation is surfaced as
        # an error_message, which the router converts to 400.
        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Date-validation failure MUST return HTTP 400 "
            f"(service-level field validation); got "
            f"{response.status_code}: {response.text}"
        )

        # The error text must identify the problem as date-related
        # so the client can surface it to the user. We check for
        # the COBOL-exact literal _MSG_DATE_INVALID, which is
        # byte-for-byte from COACTUPC.
        assert _MSG_DATE_INVALID in response.text, (
            f"400 response MUST carry a date-validation literal "
            f"({_MSG_DATE_INVALID!r}) byte-for-byte; got "
            f"{response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 7. Invalid FICO score — range validation → HTTP 400
    # ----------------------------------------------------------------------
    async def test_update_account_field_validation_fico(self, client: AsyncClient) -> None:
        """Out-of-range FICO surfaces a FICO-validation error → 400.

        The COACTUPC field-validation cascade enforces the FICO
        credit-score business rule: scores must be in the range
        300-850 (the industry-standard FICO range). Values outside
        that range are rejected with ``MOVE 'FICO Score must be
        between 300 and 850.' TO WS-ERR-MESSAGE``.

        Because :class:`AccountUpdateRequest.customer_fico_score`
        is typed as ``str`` with ``max_length=3`` (no range
        validator at the schema layer — the CVCUS01Y.cpy
        CUST-FICO-CREDIT-SCORE is PIC 9(03) which accommodates
        any 3-digit value), the structural validator accepts
        ``"999"`` or ``"000"``. The business rule is enforced in
        the service layer.

        Assertions:
            * HTTP 400 Bad Request.
            * ``detail`` carries a FICO-range message.
            * Service invoked exactly once.
        """
        # "999" is structurally valid (3 digits) but semantically
        # out-of-range for FICO (max valid = 850).
        request_body = _make_raw_update_request_body(
            customer_fico_score="999",
        )
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_FICO_INVALID,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"FICO-range failure MUST return HTTP 400 (service-level "
            f"field validation); got {response.status_code}: "
            f"{response.text}"
        )

        assert _MSG_FICO_INVALID in response.text, (
            f"400 response MUST carry a FICO-validation literal "
            f"({_MSG_FICO_INVALID!r}) byte-for-byte; got "
            f"{response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 8. Invalid state code — state/zip validation → HTTP 400
    # ----------------------------------------------------------------------
    async def test_update_account_field_validation_state_code(self, client: AsyncClient) -> None:
        """Invalid state code surfaces a state-validation error → 400.

        The COACTUPC field-validation cascade enforces two
        separate (but related) business rules on the address
        fields:

        1. ``customer_state_cd`` must be a valid 2-character US
           state or territory abbreviation per the CSLKPCDY.cpy
           STATE-TABLE (50 states + DC + PR + VI + GU + other
           territories).
        2. ``customer_zip`` must be consistent with
           ``customer_state_cd`` per the ZIP-STATE mapping table
           (e.g., a NY zip like 10001 is INVALID paired with
           a CA state code).

        Either failure sets error_message to a state-or-zip
        literal (``"State is not valid."`` or ``"Invalid zip
        code for state"``), which the router surfaces as HTTP 400.

        :class:`AccountUpdateRequest.customer_state_cd` is typed
        as ``str`` with ``max_length=2`` — the structural validator
        accepts any 2-char string including ``"XX"`` which is
        obviously not a valid state. The business rule is
        enforced in the service layer.

        Assertions:
            * HTTP 400 Bad Request.
            * ``detail`` carries a state-validation message.
            * Service invoked exactly once.
        """
        # "XX" is structurally valid (2 chars) but semantically
        # not a valid US state code.
        request_body = _make_raw_update_request_body(
            customer_state_cd="XX",
        )
        mock_response = _make_update_response(
            info_message=None,
            error_message=_MSG_STATE_INVALID,
        )

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=mock_response)

            response = await client.put(
                f"/accounts/{_TEST_ACCT_ID}",
                json=request_body,
            )

        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"State-validation failure MUST return HTTP 400 "
            f"(service-level field validation); got "
            f"{response.status_code}: {response.text}"
        )

        assert _MSG_STATE_INVALID in response.text, (
            f"400 response MUST carry a state-validation literal "
            f"({_MSG_STATE_INVALID!r}) byte-for-byte; got "
            f"{response.text}"
        )

        mock_service_class.assert_called_once()
        mock_instance.update_account.assert_awaited_once()

    # ----------------------------------------------------------------------
    # 9. Unauthenticated PUT — middleware rejects with HTTP 401
    # ----------------------------------------------------------------------
    async def test_update_account_requires_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated ``PUT /accounts/{acct_id}`` returns 401/403.

        Same pattern as :meth:`TestAccountView.
        test_get_account_requires_auth` — the
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
            * 401 carries a ``WWW-Authenticate`` header per RFC
              7235.
            * :class:`AccountService` is NEVER invoked.
        """
        request_body = _make_update_request_body()

        with patch(_ACCOUNT_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_account = AsyncMock(return_value=_make_update_response())

            transport = ASGITransport(app=test_app)
            async with AsyncClient(
                transport=transport,
                base_url="http://testserver",
            ) as unauth_client:
                response = await unauth_client.put(
                    f"/accounts/{_TEST_ACCT_ID}",
                    json=request_body,
                )

        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), f"Unauthenticated PUT MUST return 401 or 403; got {response.status_code}: {response.text}"
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST carry WWW-Authenticate header (RFC 7235); got headers {dict(response.headers)!r}"
            )

        # The service was NEVER invoked — the middleware rejected
        # the request upstream of the router handler.
        mock_service_class.assert_not_called()
        mock_instance.update_account.assert_not_called()
