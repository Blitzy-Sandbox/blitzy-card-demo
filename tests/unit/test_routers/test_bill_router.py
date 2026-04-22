# ============================================================================
# CardDemo — Unit tests for bill_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COBIL00C.cbl      — CICS bill-payment program, transaction
#                                 ``CB00`` (~572 lines). Implements the
#                                 9-step PROCESS-ENTER-KEY paragraph:
#                                 ``READ ACCTDAT`` -> balance check ->
#                                 ``READ CXACAIX`` -> auto-generate
#                                 ``TRAN-ID`` -> ``WRITE TRANSACT`` ->
#                                 ``REWRITE ACCTDAT`` -> ``SYNCPOINT``.
#                                 On any error: ``SYNCPOINT ROLLBACK``
#                                 discards both the Transaction INSERT
#                                 and the Account balance UPDATE so the
#                                 dual-write remains atomic.
#   * app/cpy/CVACT01Y.cpy      — ACCOUNT-RECORD layout. Critical field:
#                                 ``ACCT-CURR-BAL PIC S9(10)V99`` — the
#                                 running account balance that the bill
#                                 payment debits (COBIL00C line 234:
#                                 ``COMPUTE ACCT-CURR-BAL =
#                                 ACCT-CURR-BAL - TRAN-AMT``).
#   * app/cpy/CVACT03Y.cpy      — CARD-XREF-RECORD layout. Used at
#                                 COBIL00C lines 371-393 via
#                                 ``READ DATASET('CXACAIX')
#                                 RIDFLD(XREF-CARD-NUM)`` to resolve
#                                 the payer card -> account
#                                 cross-reference for the transaction
#                                 record construction.
#   * app/cpy/CVTRA05Y.cpy      — TRAN-RECORD layout (350-byte record).
#                                 The transaction record written at
#                                 COBIL00C lines 419-509 with fixed
#                                 fields TRAN-TYPE-CD='02',
#                                 TRAN-CAT-CD='0002',
#                                 TRAN-SOURCE='POS TERM',
#                                 TRAN-DESC='BILL PAYMENT - ONLINE',
#                                 TRAN-MERCHANT-ID='999999999'.
#   * app/cpy-bms/COBIL00.CPY   — Bill-payment BMS symbolic map
#                                 (``COBIL0AI`` / ``COBIL0AO``). Defines
#                                 ``ACTIDINI PIC X(11)`` for the
#                                 11-character account identifier,
#                                 ``CURBALI PIC X(14)`` for the
#                                 display-width current balance,
#                                 ``CONFIRMI PIC X(1)`` for the Y/N
#                                 confirmation flag, and
#                                 ``ERRMSGI PIC X(78)`` for the 78-char
#                                 error message slot.
# ----------------------------------------------------------------------------
# Feature F-012: Bill Payment. Target implementation under test:
# ``src/api/routers/bill_router.py`` — FastAPI router providing a single
# ``POST /bills/pay`` endpoint that validates the request via Pydantic,
# delegates to :class:`src.api.services.bill_service.BillService` (which
# performs the atomic dual-write: Transaction INSERT + Account balance
# UPDATE), and translates ``confirm='N'`` service responses into the
# appropriate :class:`HTTPException` (400/404/500) per AAP §0.5.1 and
# §0.7.1 (preserve existing error messages exactly).
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
"""Unit tests for :mod:`src.api.routers.bill_router`.

Validates the ``POST /bills/pay`` endpoint that replaces the CICS
``COBIL00C`` COBOL program (transaction ``CB00``, Feature F-012) per
AAP §0.5.1 (File-by-File Transformation Plan). The tests isolate the
router from the underlying Aurora PostgreSQL dual-write by patching
:class:`src.api.services.bill_service.BillService` at the router
import site so that no real ``session.execute(...)`` call is attempted
during unit testing.

COBOL -> Python Verification Surface
------------------------------------
=====================================================  ===============================================
COBOL paragraph / statement                            Python test (this module)
=====================================================  ===============================================
Successful 9-step dual-write (PROCESS-ENTER-KEY        ``test_pay_bill_success``
  happy path, COBIL00C.cbl lines 154-240, ending
  with ``SYNCPOINT`` / success screen send at L527-L531)
PIC S9(10)V99 precision on ACCT-CURR-BAL and           ``test_pay_bill_amount_is_decimal``
  TRAN-AMT (CVACT01Y.cpy + CVTRA05Y.cpy) - Decimal
  (never float) preservation in response
``READ DATASET('ACCTDAT') NOTFND`` branch              ``test_pay_bill_account_not_found``
  (COBIL00C.cbl line 361) -> "Account not found..."
``IF ACCT-CURR-BAL <= ZEROS`` zero-balance guard       ``test_pay_bill_zero_balance``
  (COBIL00C.cbl line 198) -> "You have nothing to pay..."
``IF ACCT-CURR-BAL <= ZEROS`` negative-balance         ``test_pay_bill_negative_balance``
  guard (COBIL00C.cbl line 198) -> same message
``READ DATASET('CXACAIX') NOTFND`` branch              ``test_pay_bill_xref_not_found``
  (COBIL00C.cbl line 379) -> "Card not found..."
Unexpected failure -> ``SYNCPOINT ROLLBACK`` +         ``test_pay_bill_transaction_failure``
  service re-raise -> router catches Exception ->
  HTTPException(500, "Payment processing failed")
BMS ``ACTIDINI`` presence check (COBIL00C.cbl          ``test_pay_bill_empty_acct_id``
  lines 161-163) - empty or blank acct_id
BMS ``WS-TRAN-AMT`` positivity check (CVTRA05Y)        ``test_pay_bill_invalid_amount_zero``
Bill payment is debit-only - negative amount           ``test_pay_bill_invalid_amount_negative``
  rejected by Pydantic ``_validate_amount_positive``
``EXEC CICS RECEIVE MAP('COBIL0A')`` without a         ``test_pay_bill_requires_auth``
  prior sign-on (EIBCALEN = 0) - implicit JWT 401
``MOVE 'Y' TO CONFIRMI`` + ``SEND MAP('COBIL0A')``     ``test_pay_bill_confirmation``
  on successful dual-write (COBIL00C.cbl L527-L531)
=====================================================  ===============================================

Mocking Strategy
----------------
The :class:`BillService` is patched at the router import site —
``"src.api.routers.bill_router.BillService"`` — following the pattern
used in :mod:`tests.unit.test_routers.test_user_router` and
:mod:`tests.unit.test_routers.test_report_router`. This replaces the
service instance that the router constructs inside ``pay_bill()``
(line 338 of the target module) with a :class:`unittest.mock.MagicMock`
whose ``pay_bill`` attribute is configured as an
:class:`unittest.mock.AsyncMock`.

The service's return value is shaped as a
:class:`src.shared.schemas.bill_schema.BillPaymentResponse` instance
carrying the ``acct_id`` / ``amount`` / ``current_balance`` /
``confirm`` / ``message`` fields that the router either echoes back
(on ``confirm='Y'``) or translates into an ``HTTPException`` (on
``confirm='N'``). The business-rule failure mapping is:

============================  ==============  =======================
service ``message`` constant   HTTP status     COBOL source
============================  ==============  =======================
``Account not found...``      404 Not Found   COBIL00C.cbl L361 (NOTFND)
``Card not found...``         404 Not Found   COBIL00C.cbl L379 (NOTFND)
``You have nothing to pay...``  400 Bad Request COBIL00C.cbl L201
other / unknown               500 Server Err  e.g. tran_id parse fail
============================  ==============  =======================

HTTP Status-Code Expectations
-----------------------------
================================================  ====================================
Scenario                                          Expected HTTP status
================================================  ====================================
Valid request, dual-write succeeds                ``200 OK``
Account lookup miss (ACCTDAT NOTFND)              ``404 Not Found``
Card xref lookup miss (CXACAIX NOTFND)            ``404 Not Found``
Zero or negative account balance                  ``400 Bad Request``
Unexpected exception (SQLAlchemyError, etc.)      ``500 Internal Server Error``
Request body rejected by Pydantic validator       ``422 Unprocessable Entity``
No ``Authorization`` header present               ``401 Unauthorized``
================================================  ====================================

Monetary Precision Discipline
-----------------------------
ALL monetary assertions in this module use :class:`decimal.Decimal`,
NEVER :class:`float`. This mirrors the COBOL ``PIC S9(10)V99``
semantics for ``ACCT-CURR-BAL`` (CVACT01Y.cpy) and ``TRAN-AMT``
(CVTRA05Y.cpy) - both fixed-point with exactly two fractional digits.

On the wire (JSON response body) Pydantic v2's default ``jsonable_encoder``
serializes :class:`Decimal` as a JSON **string** (e.g., ``"1000.00"``)
rather than a JSON number. This preserves COBOL precision across the
HTTP boundary without relying on IEEE-754 float round-trips. Tests that
need to verify the Decimal contract reconstruct a
:class:`BillPaymentResponse` from the parsed JSON body and then assert
``isinstance(model.amount, Decimal)`` - which exercises the schema's
runtime type-coercion path end-to-end.

Fixtures Used
-------------
From :mod:`tests.conftest`:
    * ``client``           — AsyncClient with a regular-user JWT and
                             ``get_current_user`` dependency override
                             (sufficient for all happy-path and
                             validation tests since ``/bills`` is
                             not in :data:`ADMIN_ONLY_PREFIXES`).
    * ``test_app``         — FastAPI app used to build a fresh
                             AsyncClient (without an ``Authorization``
                             header) for the HTTP 401 test.

See Also
--------
* AAP §0.5.1  — File-by-File Transformation Plan (``bill_router`` row
  and ``tests/unit/test_routers/test_bill_router.py`` row).
* AAP §0.5.3  — One-Phase Execution (this test is part of the single
  migration phase).
* AAP §0.7.1  — "Preserve all existing functionality exactly as-is"
  (COBOL error messages reproduced byte-for-byte).
* AAP §0.7.2  — Financial precision via :class:`Decimal`.
* :mod:`src.api.routers.bill_router` — the module under test.
* :mod:`src.api.services.bill_service` — the mocked collaborator.
* :mod:`src.shared.schemas.bill_schema` — request/response contracts.
* :mod:`tests.unit.test_routers.test_report_router` — reference for
  the patch-at-router-import-site mocking convention.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.shared.schemas.bill_schema import BillPaymentResponse

# ============================================================================
# Pytest marker — classify every test in this module as `unit` (fast,
# isolated, no external dependencies). Matches the convention from
# ``tests/unit/test_routers/test_user_router.py`` line 303 and aligns
# with the `markers` configuration in ``pyproject.toml`` (line 176-181).
# ============================================================================
pytestmark = pytest.mark.unit


# ============================================================================
# Test constants — tightly coupled to conftest.py fixture values
# ----------------------------------------------------------------------------
# The ``client`` fixture in conftest.py overrides ``get_current_user`` to
# return ``CurrentUser(user_id="TESTUSER", user_type="U", is_admin=False)``.
# We mirror that identity here so response-body / log-field assertions
# remain self-documenting (without importing conftest's private module
# constants which are intentionally underscore-prefixed).
#
# COBIL00C.cbl itself does not gate on user_type — any signed-in user
# (``CDEMO-USRTYP-USER`` or ``CDEMO-USRTYP-ADMIN``) could reach the
# bill-payment screen via the main menu (COMEN01C option 7), and
# ``/bills`` is intentionally absent from
# :data:`src.api.middleware.auth.ADMIN_ONLY_PREFIXES`.
# ============================================================================
_EXPECTED_USER_ID: str = "TESTUSER"


# ============================================================================
# Mock-target path — MUST patch the BillService reference bound on the
# router module, NOT the service's definition site.
# ----------------------------------------------------------------------------
# The router does ``from src.api.services.bill_service import BillService``
# at import time (line 129 of bill_router.py), creating a binding on
# ``src.api.routers.bill_router.BillService``. When ``pay_bill()`` later
# calls ``BillService(db)`` it resolves via that binding — so patching
# the original module would leave the router's binding pointing at the
# real class. This mirrors the technique used throughout
# :mod:`tests.unit.test_routers.test_user_router` and
# :mod:`tests.unit.test_routers.test_report_router`.
# ============================================================================
_BILL_SERVICE_PATCH_TARGET: str = "src.api.routers.bill_router.BillService"


# ============================================================================
# COBOL-exact failure-message literals — drawn from the service /
# router layers verbatim per AAP §0.7.1 ("preserve existing error
# messages exactly"). These strings MUST NOT be paraphrased.
#
# Cross-references:
#     * ``Account not found...`` — COBIL00C.cbl line 361 (READ-ACCTDAT-FILE
#       NOTFND branch). Surfaces in bill_service.py as _MSG_ACCOUNT_NOT_FOUND
#       and in bill_router.py as _MSG_ACCOUNT_NOT_FOUND (both exact copies).
#     * ``Card not found...`` — COBIL00C.cbl line 379 (READ-CXACAIX-FILE
#       NOTFND branch). Surfaces in bill_service.py as _MSG_XREF_NOT_FOUND
#       and in bill_router.py as _MSG_XREF_NOT_FOUND.
#     * ``You have nothing to pay...`` — COBIL00C.cbl line 201 (the
#       ``IF ACCT-CURR-BAL <= ZEROS`` branch). Surfaces in bill_service.py
#       as _MSG_ZERO_BALANCE and in bill_router.py as _MSG_ZERO_BALANCE.
#     * ``Payment processing failed`` — Router-scoped fallback detail
#       used when the service re-raises an un-categorised exception.
#       Defined in bill_router.py as _MSG_PAYMENT_FAILURE_DETAIL.
# ============================================================================
_MSG_ACCOUNT_NOT_FOUND: str = "Account not found..."
_MSG_XREF_NOT_FOUND: str = "Card not found..."
_MSG_ZERO_BALANCE: str = "You have nothing to pay..."
_MSG_PAYMENT_FAILURE_DETAIL: str = "Payment processing failed"


# ============================================================================
# Test data — Decimal monetary values for COBOL PIC S9(10)V99 parity.
# ----------------------------------------------------------------------------
# All values declared as :class:`decimal.Decimal` literals with explicit
# ``"1234.56"`` string constructors. This is the ONLY safe way to
# construct Decimal values in a test module per AAP §0.7.2 — passing
# a ``float`` to ``Decimal()`` silently leaks IEEE-754 rounding errors.
#
# The concrete values are chosen so that:
#     * ``_TEST_ORIGINAL_BALANCE - _TEST_AMOUNT == _TEST_NEW_BALANCE``
#       (Decimal arithmetic — reproduces COBIL00C.cbl line 234:
#       ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT``).
#     * Both values carry two trailing fractional digits so the JSON
#       string serialization (``"1000.00"``, ``"4000.00"``) is
#       byte-compatible with the COBOL PIC X(14) display-width
#       convention used by the BMS ``CURBALI`` field.
# ============================================================================
_TEST_ACCT_ID: str = "00000000001"
_TEST_TRAN_ID: str = "00000000000000123"  # 17-char surrogate from COBOL PIC X(16)
_TEST_AMOUNT: Decimal = Decimal("1000.00")
_TEST_ORIGINAL_BALANCE: Decimal = Decimal("5000.00")
_TEST_NEW_BALANCE: Decimal = _TEST_ORIGINAL_BALANCE - _TEST_AMOUNT  # Decimal("4000.00")

# Success-path message produced by :class:`BillService` on a successful
# dual-write (bill_service.py _SUCCESS_MSG_FMT.format(tran_id=tran_id)).
# Mirrors COBIL00C.cbl lines 527-531 where the program moved the
# success text into ``WS-MESSAGE`` before re-painting the screen.
_SUCCESS_MSG: str = f"Payment successful. Your Transaction ID is {_TEST_TRAN_ID}."


# ============================================================================
# Helper functions — encapsulate the BillPaymentResponse construction
# patterns used across the test methods. Extracting these avoids
# repeated boilerplate and makes the individual test methods focused
# on what they assert rather than on response-object assembly.
# ============================================================================
def _make_success_response(
    acct_id: str = _TEST_ACCT_ID,
    amount: Decimal = _TEST_AMOUNT,
    current_balance: Decimal = _TEST_NEW_BALANCE,
    message: str = _SUCCESS_MSG,
) -> BillPaymentResponse:
    """Construct a fully-populated success :class:`BillPaymentResponse`.

    Mirrors the service layer's success-path assembly at the end of
    :meth:`src.api.services.bill_service.BillService.pay_bill` where
    the service returns a response with ``confirm='Y'`` after the
    dual-write ``session.commit()`` (Transaction INSERT + Account
    balance UPDATE).

    The returned object is already a valid Pydantic model instance —
    there is no need for the mock to additionally validate it. All
    monetary fields are :class:`decimal.Decimal` (NEVER float) per
    AAP §0.7.2.

    Parameters
    ----------
    acct_id
        11-character account identifier to echo in the response
        (COBIL00 ``ACTIDINO PIC X(11)``). Default ``"00000000001"``.
    amount
        Payment amount applied - must equal the original request's
        ``amount``. Decimal only. Default ``Decimal("1000.00")``.
    current_balance
        Account balance AFTER the payment (``original - amount``).
        Decimal only. Default ``Decimal("4000.00")``.
    message
        Success message text. Default mirrors COBIL00C.cbl L527-531
        ("Payment successful. Your Transaction ID is ...").

    Returns
    -------
    BillPaymentResponse
        A schema-valid response with ``confirm='Y'``.
    """
    return BillPaymentResponse(
        acct_id=acct_id,
        amount=amount,
        current_balance=current_balance,
        confirm="Y",
        message=message,
    )


def _make_failure_response(
    message: str,
    acct_id: str = _TEST_ACCT_ID,
    amount: Decimal = _TEST_AMOUNT,
    current_balance: Decimal = _TEST_ORIGINAL_BALANCE,
) -> BillPaymentResponse:
    """Construct a business-rule failure :class:`BillPaymentResponse`.

    Mirrors the service layer's failure-path assembly in
    :meth:`src.api.services.bill_service.BillService.pay_bill` where
    the service returns a response with ``confirm='N'`` and a COBOL-
    sourced failure message. The service has already performed any
    needed rollback by the time it returns — no ``session.commit()``
    was issued for the would-be dual-write.

    Returning this from the mock causes the router to raise
    :class:`HTTPException` with a status determined by
    :func:`src.api.routers.bill_router._map_business_failure_to_http_status`
    (400 for ZERO_BALANCE; 404 for ACCOUNT_NOT_FOUND / XREF_NOT_FOUND;
    500 for any other un-categorised message).

    Parameters
    ----------
    message
        COBOL-exact failure reason string (e.g., ``"Account not
        found..."`` from COBIL00C.cbl L361). MUST be one of the
        module-level ``_MSG_*`` constants to exercise the known
        status-mapping branches — anything else triggers the
        router's 500 fallback.
    acct_id
        Account identifier to echo. Default ``"00000000001"``.
    amount
        Amount that was NOT applied (since ``confirm='N'``). Still
        required to be a valid Decimal by the schema. Default
        ``Decimal("1000.00")``.
    current_balance
        Account balance (unchanged since the dual-write did not
        commit). Default ``Decimal("5000.00")`` — matches
        ``_TEST_ORIGINAL_BALANCE``.

    Returns
    -------
    BillPaymentResponse
        A schema-valid response with ``confirm='N'`` and the
        requested failure message.
    """
    return BillPaymentResponse(
        acct_id=acct_id,
        amount=amount,
        current_balance=current_balance,
        confirm="N",
        message=message,
    )


# ============================================================================
# SECTION 1 — TestBillPayment — all 12 required test methods
# ----------------------------------------------------------------------------
# Covers every COBOL path in COBIL00C.cbl Feature F-012:
#   1.    Happy path — full dual-write succeeds (PROCESS-ENTER-KEY
#         lines 154-240, SYNCPOINT at L509, success SEND MAP at L527).
#   2.    Monetary Decimal precision preservation (PIC S9(10)V99
#         from CVACT01Y.cpy and CVTRA05Y.cpy).
#   3.    Account NOTFND (READ ACCTDAT at L361 returns DFHRESP(NOTFND)).
#   4.    Zero balance guard (IF ACCT-CURR-BAL <= ZEROS at L198).
#   5.    Negative balance guard (same condition - "<= ZEROS").
#   6.    Card cross-reference NOTFND (READ CXACAIX at L379).
#   7.    Unexpected exception - SYNCPOINT ROLLBACK equivalent: the
#         router catches a non-HTTPException raised by the service
#         and translates it into HTTP 500 with the stable
#         "Payment processing failed" detail. Atomicity is guaranteed
#         by SQLAlchemy's session-level rollback inside the service
#         (neither Transaction nor Account changes survive).
#   8.    Empty acct_id - BMS ACTIDINI blank (L161-163).
#   9.    Zero amount - bill payment is debit-only; the Pydantic
#         schema rejects with HTTP 422 before the service is called.
#   10.   Negative amount - same schema rejection.
#   11.   Unauthenticated request - the JWT middleware rejects with
#         HTTP 401 before the router dependency stack runs.
#   12.   Confirmation-flow structural validation of the success
#         response body (CONFIRMI/ERRMSGI fields).
# ============================================================================
class TestBillPayment:
    """Tests for the ``POST /bills/pay`` endpoint (Feature F-012)."""

    # ------------------------------------------------------------------
    # 1. Successful dual-write — happy path
    # ------------------------------------------------------------------
    async def test_pay_bill_success(self, client: AsyncClient) -> None:
        """Successful bill payment returns HTTP 200 with confirm='Y'.

        Mirrors the full COBIL00C.cbl PROCESS-ENTER-KEY happy path
        (lines 154-240): ``READ ACCTDAT`` succeeds, the balance check
        passes (``ACCT-CURR-BAL > ZEROS``), ``READ CXACAIX`` succeeds,
        the program auto-generates a ``TRAN-ID``, ``WRITE TRANSACT``
        inserts the debit record (CVTRA05Y layout, 350 bytes), and
        ``REWRITE ACCTDAT`` persists the decremented balance. The
        ``SYNCPOINT`` at L509 atomically commits both writes; the
        program then ``SEND MAP`` with the success message at L527.

        In the Python port, :meth:`BillService.pay_bill` performs the
        equivalent SQLAlchemy operations inside a single async
        transaction and returns a :class:`BillPaymentResponse` with
        ``confirm='Y'``. The router forwards it unchanged.

        Decimal Arithmetic Contract
        ---------------------------
        The response ``current_balance`` equals ``original_balance -
        amount``, computed in :class:`decimal.Decimal` arithmetic
        (NEVER float). Pre-computed here as:

        .. code-block:: python

            _TEST_ORIGINAL_BALANCE - _TEST_AMOUNT == _TEST_NEW_BALANCE
            Decimal("5000.00") - Decimal("1000.00") == Decimal("4000.00")

        Assertions:
            * HTTP 200 OK.
            * Response body contains ``acct_id``, ``amount``,
              ``current_balance``, ``confirm``, ``message``.
            * ``acct_id`` echoes the request ("00000000001").
            * ``confirm == 'Y'`` (CONFIRMI success sentinel).
            * ``message`` is the COBOL success text ("Payment
              successful. Your Transaction ID is ...").
            * Decimal arithmetic: ``current_balance ==
              original_balance - amount``.
            * :meth:`BillService.pay_bill` was called exactly once
              and received a :class:`BillPaymentRequest` with the
              original acct_id and amount preserved.
        """
        # ``amount`` MUST be a string in the JSON payload so Pydantic
        # v2's Decimal field-validator parses it as a Decimal (without
        # going through float). See bill_schema.py
        # ``_validate_amount_positive`` for the validation logic.
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),  # "1000.00"
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_success_response(),
            )

            response = await client.post("/bills/pay", json=request_body)

        # HTTP 200 — router forwarded the service's confirm='Y'
        # response unchanged (lines 370-403 of bill_router.py).
        assert response.status_code == status.HTTP_200_OK, (
            f"Successful bill payment MUST return HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Required fields per :class:`BillPaymentResponse` schema.
        for required_field in ("acct_id", "amount", "current_balance", "confirm", "message"):
            assert required_field in body, f"Response MUST include ``{required_field}``; got {body}"

        # ``acct_id`` echoes the request — the router/service does
        # not mutate the caller-supplied identifier.
        assert body["acct_id"] == _TEST_ACCT_ID, f"``acct_id`` MUST echo the request; got {body.get('acct_id')!r}"

        # COBIL00 CONFIRMI PIC X(1) = 'Y' on success. This is the
        # binary signal to the caller that the dual-write committed.
        assert body["confirm"] == "Y", (
            f"``confirm`` MUST be 'Y' on a successful dual-write (COBIL00 CONFIRMO); got {body.get('confirm')!r}"
        )

        # ``message`` carries the COBOL-exact success text from
        # COBIL00C.cbl lines 527-531. The transaction ID portion
        # varies so we substring-check the fixed prefix.
        assert isinstance(body["message"], str), (
            f"``message`` MUST be a string; got {type(body.get('message')).__name__}"
        )
        assert "Payment successful" in body["message"], (
            f"Success ``message`` MUST include 'Payment successful' "
            f"(COBIL00C.cbl L527-L531); got {body.get('message')!r}"
        )
        assert _TEST_TRAN_ID in body["message"], (
            f"Success ``message`` MUST echo the generated tran_id; got {body.get('message')!r}"
        )

        # Decimal arithmetic contract — reconstruct each monetary
        # field as Decimal(str(...)) since Pydantic v2 serializes
        # Decimal as a JSON string (e.g., "1000.00"). This preserves
        # COBOL PIC S9(10)V99 precision across the HTTP boundary
        # without relying on IEEE-754 float round-trips.
        amount_decimal = Decimal(str(body["amount"]))
        balance_decimal = Decimal(str(body["current_balance"]))

        assert amount_decimal == _TEST_AMOUNT, (
            f"``amount`` MUST equal the request amount in Decimal "
            f"arithmetic; got {amount_decimal!r}, expected {_TEST_AMOUNT!r}"
        )

        # COBOL: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
        # (COBIL00C.cbl line 234). Python Decimal arithmetic with no
        # float coercion gives exact equality.
        assert balance_decimal == _TEST_ORIGINAL_BALANCE - _TEST_AMOUNT, (
            f"``current_balance`` MUST equal original_balance - amount "
            f"in Decimal arithmetic (COBIL00C.cbl L234); got "
            f"{balance_decimal!r}, expected "
            f"{_TEST_ORIGINAL_BALANCE - _TEST_AMOUNT!r}"
        )
        assert balance_decimal == _TEST_NEW_BALANCE, (
            f"``current_balance`` MUST equal the pre-computed "
            f"_TEST_NEW_BALANCE; got {balance_decimal!r}, expected "
            f"{_TEST_NEW_BALANCE!r}"
        )

        # Verify the service was invoked with the correct request.
        mock_service_class.assert_called_once()  # BillService(db)
        mock_instance.pay_bill.assert_awaited_once()
        call_request = mock_instance.pay_bill.call_args.args[0]
        assert call_request.acct_id == _TEST_ACCT_ID, f"Service received wrong acct_id; got {call_request.acct_id!r}"
        assert call_request.amount == _TEST_AMOUNT, f"Service received wrong amount; got {call_request.amount!r}"
        # CRITICAL: service received a Decimal, NOT a float.
        assert isinstance(call_request.amount, Decimal), (
            f"Service MUST receive amount as Decimal (never float); got {type(call_request.amount).__name__}"
        )

    # ------------------------------------------------------------------
    # 2. Monetary Decimal precision preservation
    # ------------------------------------------------------------------
    async def test_pay_bill_amount_is_decimal(self, client: AsyncClient) -> None:
        """Response monetary fields preserve COBOL PIC S9(10)V99 Decimal precision.

        This is the canonical test for AAP §0.7.2 ("Financial precision
        via Decimal"). Validates that both ``amount`` and
        ``current_balance`` fields — which map to COBOL
        ``TRAN-AMT PIC +99999999.99`` (CVTRA05Y.cpy) and
        ``ACCT-CURR-BAL PIC S9(10)V99`` (CVACT01Y.cpy) respectively —
        round-trip correctly through the JSON serialization layer
        without precision loss.

        Methodology
        -----------
        1. The service returns a :class:`BillPaymentResponse` with
           Decimal values carrying exactly two fractional digits
           (matching the COBOL fixed-point convention).
        2. The router forwards the response to FastAPI, which
           serializes via ``jsonable_encoder`` (converts Decimal to
           JSON string, not JSON number, to preserve precision).
        3. The test reconstructs a :class:`BillPaymentResponse` from
           the parsed JSON body — this exercises the schema's
           runtime type-coercion and guarantees the round-trip
           preserved the Decimal contract.

        CRITICAL: This test uses values like ``Decimal("1234.56")``
        and ``Decimal("9876.54")`` chosen specifically so IEEE-754
        float coercion would NOT produce the same string. If the
        router/service ever regresses to ``float`` internally, the
        test's Decimal equality check will fail immediately.

        Assertions:
            * HTTP 200 OK.
            * Reconstructed response's ``amount`` is a Decimal
              instance with exactly two fractional digits.
            * Reconstructed response's ``current_balance`` is a
              Decimal instance with exactly two fractional digits.
            * Decimal values equal the mocked values byte-for-byte
              (no float rounding).
        """
        # Use values with non-trivial fractional parts to expose any
        # IEEE-754 precision loss that might sneak in. Decimal("1234.56")
        # is not representable exactly in binary float (1234.56 in
        # float64 -> 1234.5599999999999), so this is a strong
        # regression sentinel.
        precision_amount: Decimal = Decimal("1234.56")
        precision_original: Decimal = Decimal("9999.99")
        precision_new: Decimal = precision_original - precision_amount  # Decimal("8765.43")

        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(precision_amount),  # "1234.56"
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_success_response(
                    amount=precision_amount,
                    current_balance=precision_new,
                ),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_200_OK, (
            f"Decimal precision test expects HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Round-trip the body through the schema to exercise the
        # runtime type-coercion. If amount / current_balance were
        # serialized as floats the schema's Decimal field would
        # coerce them (losing precision); if they were serialized
        # as strings the schema constructs Decimals directly.
        reconstructed = BillPaymentResponse(**body)

        # CRITICAL: response.amount MUST be a Decimal type — NEVER float.
        # This is the cornerstone of AAP §0.7.2.
        assert isinstance(reconstructed.amount, Decimal), (
            f"response.amount MUST be :class:`decimal.Decimal` (NEVER float); got {type(reconstructed.amount).__name__}"
        )
        assert isinstance(reconstructed.current_balance, Decimal), (
            f"response.current_balance MUST be :class:`decimal.Decimal` "
            f"(NEVER float); got {type(reconstructed.current_balance).__name__}"
        )

        # Byte-exact Decimal equality — if the service returned
        # Decimal("1234.56") and the router serialized it, the
        # reconstructed Decimal MUST equal Decimal("1234.56") exactly.
        # This guards against ANY float coercion slipping in.
        assert reconstructed.amount == precision_amount, (
            f"Decimal precision lost on amount round-trip; got {reconstructed.amount!r}, expected {precision_amount!r}"
        )
        assert reconstructed.current_balance == precision_new, (
            f"Decimal precision lost on current_balance round-trip; "
            f"got {reconstructed.current_balance!r}, expected "
            f"{precision_new!r}"
        )

        # Scale check — exactly two fractional digits on both monetary
        # fields, matching PIC S9(10)V99 convention. Decimal.as_tuple()
        # exponent of -2 means two digits after the decimal point.
        assert reconstructed.amount.as_tuple().exponent == -2, (
            f"``amount`` MUST have exactly 2 fractional digits "
            f"(PIC V99); got exponent "
            f"{reconstructed.amount.as_tuple().exponent}"
        )
        assert reconstructed.current_balance.as_tuple().exponent == -2, (
            f"``current_balance`` MUST have exactly 2 fractional digits "
            f"(PIC V99); got exponent "
            f"{reconstructed.current_balance.as_tuple().exponent}"
        )

    # ------------------------------------------------------------------
    # 3. Account not found — HTTP 404
    # ------------------------------------------------------------------
    async def test_pay_bill_account_not_found(self, client: AsyncClient) -> None:
        """Unknown account surfaces as HTTP 404 with COBOL-exact message.

        Mirrors ``COBIL00C.cbl`` line 361 where ``READ DATASET('ACCTDAT')
        RIDFLD(ACCT-ID)`` returns ``DFHRESP(NOTFND)``: the program
        moves the literal ``'Account not found...'`` into
        ``WS-MESSAGE`` and re-sends the screen with ``CONFIRMI = 'N'``.

        In the Python port the service layer returns a
        :class:`BillPaymentResponse` with ``confirm='N'`` plus the
        original COBOL-exact message. The router then translates that
        into :class:`HTTPException` (404) via
        :func:`src.api.routers.bill_router._map_business_failure_to_http_status`
        — because a missing resource is the semantic equivalent of an
        HTTP 404 Not Found.

        Assertions:
            * HTTP 404 Not Found.
            * Response body contains the COBOL-exact failure message
              ("Account not found...") preserved per AAP §0.7.1.
            * Service was invoked exactly once.
        """
        request_body: dict[str, Any] = {
            "acct_id": "99999999999",  # nonexistent account
            "amount": str(_TEST_AMOUNT),
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_failure_response(
                    message=_MSG_ACCOUNT_NOT_FOUND,
                    acct_id="99999999999",
                    current_balance=Decimal("0.00"),
                ),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Account-not-found MUST surface as HTTP 404; got {response.status_code}: {response.text}"
        )

        # The error response is wrapped by the global ABEND-DATA
        # handler in ``src/api/middleware/error_handler.py`` — the
        # COBOL-exact message appears in the ``reason`` field of the
        # envelope (rather than at the top-level ``detail``). We
        # search the full response text so the assertion is resilient
        # to future envelope-shape tweaks.
        assert _MSG_ACCOUNT_NOT_FOUND in response.text, (
            f"404 response MUST carry the COBIL00C.cbl L361 literal "
            f"``{_MSG_ACCOUNT_NOT_FOUND!r}`` (AAP §0.7.1 — preserve "
            f"existing error messages exactly); got {response.text}"
        )

        # Service must have been invoked exactly once — the router
        # does not retry on its own.
        mock_instance.pay_bill.assert_awaited_once()

    # ------------------------------------------------------------------
    # 4. Zero balance — HTTP 400 ("You have nothing to pay...")
    # ------------------------------------------------------------------
    async def test_pay_bill_zero_balance(self, client: AsyncClient) -> None:
        """Zero account balance rejects the payment with HTTP 400.

        Mirrors ``COBIL00C.cbl`` lines 198-202 where the program
        checks ``IF ACCT-CURR-BAL <= ZEROS`` immediately after the
        successful ACCTDAT read. On a zero balance there is nothing
        to debit — the program skips the WRITE TRANSACT / REWRITE
        ACCTDAT sequence, moves the literal ``'You have nothing to
        pay...'`` into ``WS-MESSAGE``, and re-sends the screen with
        ``CONFIRMI = 'N'``.

        This is a business-rule failure (not a data-corruption
        failure) so the REST mapping is HTTP 400 Bad Request — the
        caller's request was well-formed but the server-side state
        makes the operation meaningless.

        Assertions:
            * HTTP 400 Bad Request.
            * Response body contains the COBOL-exact failure message
              ("You have nothing to pay...") preserved per AAP §0.7.1.
            * Service was invoked exactly once.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            # Mock returns confirm='N' with current_balance=0 — the
            # account was found but has no outstanding debit.
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_failure_response(
                    message=_MSG_ZERO_BALANCE,
                    current_balance=Decimal("0.00"),
                ),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Zero-balance payment MUST surface as HTTP 400; got {response.status_code}: {response.text}"
        )

        assert _MSG_ZERO_BALANCE in response.text, (
            f"400 response MUST carry the COBIL00C.cbl L198 literal "
            f"``{_MSG_ZERO_BALANCE!r}`` (AAP §0.7.1 — preserve "
            f"existing error messages exactly); got {response.text}"
        )

        mock_instance.pay_bill.assert_awaited_once()

    # ------------------------------------------------------------------
    # 5. Negative balance — HTTP 400 (same message as zero)
    # ------------------------------------------------------------------
    async def test_pay_bill_negative_balance(self, client: AsyncClient) -> None:
        """Negative account balance rejects the payment with HTTP 400.

        The COBOL condition at ``COBIL00C.cbl`` line 198
        (``IF ACCT-CURR-BAL <= ZEROS``) treats zero and negative
        balances identically — both skip the debit sequence with the
        same ``'You have nothing to pay...'`` message. Preserving
        this behavior exactly is an explicit requirement per AAP §0.7.1.

        A negative balance on a card account represents a credit
        position — the customer OWES nothing; in fact the card issuer
        owes the customer. A debit-only bill payment against a credit
        position is semantically incoherent, hence the rejection.

        Assertions:
            * HTTP 400 Bad Request (same as zero balance).
            * Response body carries the same COBOL-exact message
              ("You have nothing to pay...").
            * Service was invoked exactly once.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            # Negative balance — the COBOL <= ZEROS predicate fires,
            # same message as the zero case.
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_failure_response(
                    message=_MSG_ZERO_BALANCE,
                    current_balance=Decimal("-250.00"),  # credit position
                ),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Negative-balance payment MUST surface as HTTP 400 "
            f"(same as zero-balance per COBIL00C.cbl L198 "
            f"'<= ZEROS'); got {response.status_code}: {response.text}"
        )

        assert _MSG_ZERO_BALANCE in response.text, (
            f"400 response MUST carry the COBIL00C.cbl L198 literal "
            f"``{_MSG_ZERO_BALANCE!r}`` (AAP §0.7.1 — preserve "
            f"existing error messages exactly); got {response.text}"
        )

        mock_instance.pay_bill.assert_awaited_once()

    # ------------------------------------------------------------------
    # 6. Card cross-reference not found — HTTP 404
    # ------------------------------------------------------------------
    async def test_pay_bill_xref_not_found(self, client: AsyncClient) -> None:
        """Missing card cross-reference surfaces as HTTP 404.

        Mirrors ``COBIL00C.cbl`` line 379 where the second VSAM
        read — ``READ DATASET('CXACAIX') RIDFLD(XREF-CARD-NUM)`` —
        returns ``DFHRESP(NOTFND)``. The CXACAIX alternate index
        maps card numbers to accounts; a missing entry means the
        account exists but has no associated card, which is a data-
        integrity anomaly that prevents transaction-record
        construction (the TRAN-CARD-NUM field in CVTRA05Y.cpy is
        mandatory).

        The COBOL program moves the literal ``'Card not found...'``
        into ``WS-MESSAGE`` and re-sends with ``CONFIRMI = 'N'``.
        REST maps the missing resource to HTTP 404 Not Found.

        Assertions:
            * HTTP 404 Not Found.
            * Response body contains the COBOL-exact failure message
              ("Card not found...") preserved per AAP §0.7.1.
            * Service was invoked exactly once.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_failure_response(message=_MSG_XREF_NOT_FOUND),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"Card-xref-not-found MUST surface as HTTP 404; got {response.status_code}: {response.text}"
        )

        assert _MSG_XREF_NOT_FOUND in response.text, (
            f"404 response MUST carry the COBIL00C.cbl L379 literal "
            f"``{_MSG_XREF_NOT_FOUND!r}`` (AAP §0.7.1 — preserve "
            f"existing error messages exactly); got {response.text}"
        )

        mock_instance.pay_bill.assert_awaited_once()

    # ------------------------------------------------------------------
    # 7. Transaction failure (dual-write rollback) — HTTP 500
    # ------------------------------------------------------------------
    async def test_pay_bill_transaction_failure(self, client: AsyncClient) -> None:
        """Unexpected exception triggers SYNCPOINT ROLLBACK -> HTTP 500.

        This is the atomicity-contract test. Mirrors the COBOL
        pattern where an error during the ``WRITE TRANSACT`` or
        ``REWRITE ACCTDAT`` sequence (lines 505-511 of COBIL00C.cbl)
        triggers an implicit ``SYNCPOINT ROLLBACK`` that discards
        BOTH the Transaction INSERT and the Account balance UPDATE.
        No partial write is ever visible — either both records exist
        or neither does.

        In the Python port, :meth:`BillService.pay_bill` catches
        SQLAlchemy exceptions (e.g., :class:`SQLAlchemyError`,
        :class:`StaleDataError` from optimistic concurrency), issues
        an explicit ``await session.rollback()``, and then re-raises.
        The router's ``except Exception`` block (lines 345-358 of
        bill_router.py) catches the re-raised exception and
        translates it into :class:`HTTPException` (500) with the
        stable ``"Payment processing failed"`` detail to avoid
        leaking driver-level diagnostics.

        We simulate this by raising a generic :class:`Exception`
        from the mock. The specific exception type is NOT the
        contract — what matters is that ANY unhandled exception
        reaches the router's catch-all block and produces a stable
        HTTP 500 response.

        Assertions:
            * HTTP 500 Internal Server Error.
            * Response body contains the stable fallback detail
              ("Payment processing failed") — NOT the raw
              exception message (which would leak driver internals).
            * Service was invoked exactly once.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            # CRITICAL: use ``side_effect`` with an Exception to simulate
            # a re-raise from the service. The router catches this and
            # translates to HTTPException(500). If we used a confirm='N'
            # response with an un-categorised message, the result would
            # be 500 via the fallback branch of
            # _map_business_failure_to_http_status() — which is a
            # different code path (business failure vs. crash). This
            # test exercises the crash path specifically.
            mock_instance.pay_bill = AsyncMock(
                side_effect=Exception("Simulated SQLAlchemyError — Aurora PostgreSQL connection lost mid-dual-write"),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_500_INTERNAL_SERVER_ERROR, (
            f"Unexpected service exception MUST surface as HTTP 500; got {response.status_code}: {response.text}"
        )

        # The router's catch-all block injects a STABLE fallback
        # detail string, deliberately NOT the raw exception message.
        # This prevents leaking driver-level diagnostics (e.g.,
        # "connection lost" with DSN details) to the caller.
        assert _MSG_PAYMENT_FAILURE_DETAIL in response.text, (
            f"500 response MUST carry the stable fallback detail "
            f"``{_MSG_PAYMENT_FAILURE_DETAIL!r}`` (bill_router.py "
            f"_MSG_PAYMENT_FAILURE_DETAIL — does NOT leak the raw "
            f"exception message); got {response.text}"
        )

        # The raw exception message MUST NOT appear in the response —
        # defense against information leaking.
        assert "connection lost" not in response.text.lower(), (
            f"Response MUST NOT leak raw exception text; got {response.text}"
        )
        assert "sqlalchemyerror" not in response.text.lower(), (
            f"Response MUST NOT leak exception class names; got {response.text}"
        )

        # Service invoked exactly once — the router does not retry.
        mock_instance.pay_bill.assert_awaited_once()

    # ------------------------------------------------------------------
    # 8. Empty acct_id — HTTP 422
    # ------------------------------------------------------------------
    async def test_pay_bill_empty_acct_id(self, client: AsyncClient) -> None:
        """Empty acct_id is rejected with HTTP 422 before the service runs.

        Mirrors the CICS behavior in ``COBIL00C.cbl`` lines 161-163
        which rejected a bill-payment attempt with a "Please enter
        Account ID" message when ``ACTIDINI`` was blank or empty.
        In the Python port, the
        :func:`src.shared.schemas.bill_schema.BillPaymentRequest.
        _validate_acct_id` field-validator rejects empty or
        whitespace-only strings at the schema layer — BEFORE the
        service (and the database) is ever consulted.

        This is a client-error (HTTP 422 Unprocessable Entity)
        rather than a server-error (HTTP 500) or a business-rule
        failure (HTTP 400) because the caller submitted a request
        that cannot be processed as-given; no database state was
        involved.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Response body identifies ``acct_id`` as the offending
              field.
            * Service was NEVER invoked (Pydantic rejected pre-service).
        """
        request_body: dict[str, Any] = {
            "acct_id": "",  # empty — fails _validate_acct_id
            "amount": str(_TEST_AMOUNT),
        }

        # Patch the service so we can prove it was NOT called — a
        # passing test means Pydantic rejected the request body
        # before any service invocation could occur.
        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock()  # should never be called

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Empty acct_id MUST be rejected with HTTP 422 before the "
            f"service runs; got {response.status_code}: {response.text}"
        )

        # The Pydantic validation error body MUST identify the
        # offending field so UI layers can highlight the correct
        # input control. The exact error-handler envelope shape is
        # an implementation detail (see
        # src/api/middleware/error_handler.py lines 739-804), but
        # the field name is part of the stable contract.
        assert "acct_id" in response.text, (
            f"422 response MUST identify the offending field ``acct_id``; got {response.text}"
        )

        # Crucial: the service MUST NOT have been called.
        mock_instance.pay_bill.assert_not_awaited()

    # ------------------------------------------------------------------
    # 9. Zero amount — HTTP 422
    # ------------------------------------------------------------------
    async def test_pay_bill_invalid_amount_zero(self, client: AsyncClient) -> None:
        """Zero payment amount is rejected with HTTP 422.

        Bill payment is a **debit-only** transaction — the account's
        current balance decreases by exactly ``amount`` (see
        ``COBIL00C.cbl`` line 234:
        ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT``). A zero
        amount would produce a no-op: ``new_balance == old_balance``.
        Accepting this would create spurious Transaction records with
        ``TRAN-AMT = 0``, polluting the ledger and wasting auto-ID
        allocation.

        The Python port's
        :func:`src.shared.schemas.bill_schema.BillPaymentRequest.
        _validate_amount_positive` field-validator rejects
        non-positive Decimals at the schema layer (AAP §0.7.2).

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Response body identifies ``amount`` as the offending
              field.
            * Service was NEVER invoked.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": "0.00",  # zero — fails _validate_amount_positive
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock()  # should never be called

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Zero amount MUST be rejected with HTTP 422 (bill "
            f"payment is debit-only); got {response.status_code}: "
            f"{response.text}"
        )

        assert "amount" in response.text, (
            f"422 response MUST identify the offending field ``amount``; got {response.text}"
        )

        mock_instance.pay_bill.assert_not_awaited()

    # ------------------------------------------------------------------
    # 10. Negative amount — HTTP 422
    # ------------------------------------------------------------------
    async def test_pay_bill_invalid_amount_negative(self, client: AsyncClient) -> None:
        """Negative payment amount is rejected with HTTP 422.

        A negative amount would INVERT the debit into a credit —
        ``new_balance == old_balance - (-X) == old_balance + X``.
        This would enable unauthorized account top-ups via the bill-
        payment endpoint, which is a severe business-logic violation.

        The Python port's
        :func:`src.shared.schemas.bill_schema.BillPaymentRequest.
        _validate_amount_positive` field-validator uses a strict
        ``> 0`` comparison, rejecting both zero and negative
        Decimals at the schema layer. This test confirms the
        negative branch.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * Response body identifies ``amount`` as the offending
              field.
            * Service was NEVER invoked.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": "-50.00",  # negative — fails _validate_amount_positive
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock()  # should never be called

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Negative amount MUST be rejected with HTTP 422 (prevents "
            f"unauthorized account top-up via bill-payment endpoint); "
            f"got {response.status_code}: {response.text}"
        )

        assert "amount" in response.text, (
            f"422 response MUST identify the offending field ``amount``; got {response.text}"
        )

        mock_instance.pay_bill.assert_not_awaited()

    # ------------------------------------------------------------------
    # 11. Unauthenticated request — HTTP 401
    # ------------------------------------------------------------------
    async def test_pay_bill_requires_auth(self, test_app: FastAPI) -> None:
        """Request without ``Authorization`` header returns HTTP 401.

        Mirrors the CICS access-control model where an unsigned-in
        user could not reach ``COBIL00C`` — the mainframe routed them
        to ``COSGN00C`` first (``EIBCALEN = 0`` on transaction entry
        meant no prior authentication context). In the cloud-native
        port the :class:`src.api.middleware.auth.JWTAuthMiddleware`
        performs the equivalent check by looking for a bearer token
        in the ``Authorization`` header; if absent, it short-circuits
        the request with HTTP 401 BEFORE any router dependency
        resolves (crucially, before ``get_current_user`` is invoked).

        ``/bills/pay`` is NOT listed in :data:`PUBLIC_PATHS` of
        ``src/api/middleware/auth.py`` — anonymous access is
        explicitly disallowed.

        This test deliberately bypasses the conftest ``client``
        fixture — which pre-sets ``Authorization: Bearer <JWT>`` —
        and builds a fresh :class:`AsyncClient` against the same
        ``test_app`` so the middleware observes a genuinely missing
        header (the real-world attack pattern of an anonymous caller
        probing the bill-payment endpoint).

        Assertions:
            * HTTP 401 Unauthorized.
            * Response includes the ``WWW-Authenticate: Bearer``
              challenge per RFC 7235 §4.1 (emitted by the JWT
              middleware — see ``src/api/middleware/auth.py``).
        """
        # Well-formed request body so the rejection is unambiguously
        # auth-driven, not validation-driven.
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),
        }

        # Build a fresh AsyncClient against the same test_app (which
        # has its dependency overrides in place) but WITHOUT an
        # Authorization header. The middleware should reject the
        # request before any dependency (or the service) runs.
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.post("/bills/pay", json=request_body)

        # Accept either 401 or 403 — the AAP schema specifies either
        # is acceptable for unauthenticated access. In practice the
        # JWT middleware emits 401 (per RFC 7235 recommendation for
        # "authentication required"); 403 is reserved for an
        # authenticated-but-forbidden caller.
        assert response.status_code in (
            status.HTTP_401_UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN,
        ), f"Unauthenticated /bills/pay MUST return HTTP 401 or 403; got {response.status_code}: {response.text}"

        # When the status is 401 we assert the WWW-Authenticate
        # challenge per RFC 7235 §4.1 (a 401 without the challenge
        # header is malformed). We only assert this for the 401 path
        # since 403 has no comparable header contract.
        if response.status_code == status.HTTP_401_UNAUTHORIZED:
            assert "www-authenticate" in {key.lower() for key in response.headers}, (
                f"401 response MUST include WWW-Authenticate header per RFC 7235 §4.1; headers={dict(response.headers)}"
            )

    # ------------------------------------------------------------------
    # 12. Confirmation flow — structural validation of success body
    # ------------------------------------------------------------------
    async def test_pay_bill_confirmation(self, client: AsyncClient) -> None:
        """Successful payment response carries the full CONFIRMI='Y' flow.

        Mirrors the ``SEND MAP('COBIL0A') FROM(COBIL0AO)`` sequence at
        the end of ``COBIL00C.cbl`` (lines 527-531) when the dual-write
        succeeds: the program moves ``'Y'`` into ``CONFIRMI`` and the
        success message ("Payment successful. Your Transaction ID is
        ...") into ``ERRMSGI`` before re-painting the screen. The
        cloud response envelopes these as
        :class:`BillPaymentResponse` fields — this test validates the
        full fidelity of the echo-back, which is the client's signal
        that the payment was accepted by Aurora PostgreSQL.

        The happy-path structural contract validated here is:

        * ``acct_id``        — 11-char fixed-width string echoing
                               the request (COBIL00 ``ACTIDINO`` PIC
                               X(11)).
        * ``amount``         — Decimal, exactly equal to the request
                               amount (COBOL ``TRAN-AMT`` PIC
                               +99999999.99).
        * ``current_balance``— Decimal, post-payment balance (COBIL00
                               ``CURBALO`` PIC X(14) display width).
        * ``confirm``        — ``'Y'`` (COBIL00 ``CONFIRMO`` PIC X(1)
                               success sentinel).
        * ``message``        — non-empty, at most 78 characters
                               (COBIL00 ``ERRMSGO`` PIC X(78) width
                               preservation — AAP §0.7.2).

        Assertions:
            * HTTP 200 OK.
            * Response body contains all 5 required fields.
            * ``confirm == 'Y'``.
            * ``message`` is non-empty and ≤ 78 characters.
            * ``acct_id`` echoes the request value.
            * Service was invoked exactly once.
            * A failure case (confirm='N') is covered implicitly by
              tests 3-7 (account not found, zero balance, etc.) —
              this test focuses on the success-path structural
              contract.
        """
        request_body: dict[str, Any] = {
            "acct_id": _TEST_ACCT_ID,
            "amount": str(_TEST_AMOUNT),
        }

        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.pay_bill = AsyncMock(
                return_value=_make_success_response(),
            )

            response = await client.post("/bills/pay", json=request_body)

        assert response.status_code == status.HTTP_200_OK, (
            f"Confirmation-flow test expects HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()

        # Full structural check — every field from the
        # BillPaymentResponse schema MUST appear in the JSON.
        for required_field in ("acct_id", "amount", "current_balance", "confirm", "message"):
            assert required_field in body, f"Confirmation response MUST include ``{required_field}``; got {body}"

        # COBIL00 CONFIRMO PIC X(1) = 'Y' on success — this is the
        # binary signal to the client that the dual-write committed.
        assert body["confirm"] == "Y", (
            f"Confirmation MUST carry ``CONFIRMO='Y'`` per COBIL00C.cbl L527-L531; got {body.get('confirm')!r}"
        )

        # COBIL00 ERRMSGO PIC X(78) width — the schema-level constraint
        # is already enforced by the Pydantic model (max_length=78),
        # but the test double-checks the runtime width so a future
        # regression (e.g., an over-long success message sneaking
        # through) fails loudly.
        message_value: str = body["message"]
        assert isinstance(message_value, str) and message_value, (
            f"``message`` MUST be a non-empty string on success; got {message_value!r}"
        )
        assert len(message_value) <= 78, (
            f"``message`` MUST be at most 78 characters (COBIL00 "
            f"ERRMSGO PIC X(78)); got {len(message_value)} chars: "
            f"{message_value!r}"
        )

        # ``acct_id`` echoes the request — the service/router does
        # not mutate the caller-supplied identifier. Part of the
        # CONFIRMI/ACTIDINO echo-back contract.
        assert body["acct_id"] == _TEST_ACCT_ID, f"``acct_id`` MUST echo the request; got {body.get('acct_id')!r}"

        # Service was called exactly once on the happy path.
        mock_instance.pay_bill.assert_awaited_once()

        # ------------------------------------------------------------
        # Negative confirmation case — confirm the flow correctly
        # distinguishes 'Y' (success) from 'N' (business failure).
        # This supplements tests 3-7 by asserting that the same
        # structural fields appear in the failure envelope, ensuring
        # the client can parse either outcome with a single schema.
        # ------------------------------------------------------------
        with patch(_BILL_SERVICE_PATCH_TARGET) as mock_service_class_n:
            mock_instance_n: MagicMock = mock_service_class_n.return_value
            mock_instance_n.pay_bill = AsyncMock(
                return_value=_make_failure_response(
                    message=_MSG_ZERO_BALANCE,
                    current_balance=Decimal("0.00"),
                ),
            )

            response_n = await client.post("/bills/pay", json=request_body)

        # Zero-balance -> 400, but the underlying COBOL CONFIRMO='N'
        # is still the semantic parallel. We don't parse the 400
        # body (it's an ABEND envelope, not a BillPaymentResponse)
        # but we confirm the status code and that the "N" branch
        # was exercised end-to-end.
        assert response_n.status_code == status.HTTP_400_BAD_REQUEST, (
            f"Confirm='N' + zero-balance branch MUST surface as "
            f"HTTP 400; got {response_n.status_code}: {response_n.text}"
        )
        assert _MSG_ZERO_BALANCE in response_n.text, (
            f"CONFIRMO='N' envelope MUST carry the COBOL-exact reason; got {response_n.text}"
        )
