# ============================================================================
# Source: app/cbl/COBIL00C.cbl  (Bill payment, Feature F-012)
#         + app/cpy-bms/COBIL00.CPY  (BMS symbolic map)
#         + app/cpy/CVACT01Y.cpy     (Account record layout)
#         + app/cpy/CVACT03Y.cpy     (Card cross-reference layout)
#         + app/cpy/CVTRA05Y.cpy     (Transaction record layout) —
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
"""Bill payment router. Converted from COBIL00C.cbl (572 lines, Feature F-012).

``POST /bills/pay`` performs atomic dual-write: Transaction INSERT +
Account balance UPDATE. All monetary values use ``decimal.Decimal``.

Endpoint summary
----------------
``POST /bills/pay`` — Apply a bill payment as an atomic dual-write
                     transaction (F-012, COBIL00C.cbl).

The router is a thin transport-layer shim that delegates business
logic to :class:`src.api.services.bill_service.BillService`. The
service owns the complete 9-step flow mirrored from
``COBIL00C`` PROCESS-ENTER-KEY:

1. ``2000-READACCT`` — lookup the account (``ACCTDAT`` VSAM read).
2. Zero / negative balance check.
3. ``2500-READXREF`` — lookup the owning card cross-reference.
4. Auto-generation of ``tran_id`` (COBOL's time-based sequence).
5. ``INSERT`` into ``Transaction`` (debit record).
6. ``UPDATE`` on ``Account`` (decrement ``curr_bal``).
7. ``SYNCPOINT`` (COMMIT) atomically covering steps 5 and 6.
8. On any exception: automatic rollback — neither the Transaction
   INSERT nor the Account UPDATE survive.
9. Return the positive confirmation (or the structured failure).

COBOL → HTTP mapping
--------------------
=================================================  =======================
COBOL construct                                    HTTP equivalent
=================================================  =======================
``RECEIVE MAP('COBIL00')``                         ``POST /bills/pay`` body
``READ DATASET('ACCTDAT') RIDFLD(ACCT-ID)``        :meth:`BillService.pay_bill`
                                                    step 1
``WRITE DATASET('TRANSACT')``                       :meth:`BillService.pay_bill`
                                                    step 5
``REWRITE DATASET('ACCTDAT')``                     :meth:`BillService.pay_bill`
                                                    step 6
``SYNCPOINT`` / ``SYNCPOINT ROLLBACK``             SQLAlchemy commit /
                                                    rollback
WS-CONFIRM / WS-ERRMSG                             response
                                                    ``confirm`` / ``message``
=================================================  =======================

Error surfacing
---------------
``BillService.pay_bill`` uses a two-tier error-surfacing strategy:

* **Business-rule failures** are returned as a populated
  :class:`BillPaymentResponse` with ``confirm='N'`` and ``message``
  set to a user-facing reason string (sourced from COBIL00C.cbl and
  ``CSMSG01Y.cpy``). The service has already performed any needed
  rollback by the time it returns. The router inspects ``confirm``
  and translates each known failure reason into a specific
  :class:`HTTPException`:

  ============================  ==============  =======================
  service ``message`` constant   HTTP status     COBOL source
  ============================  ==============  =======================
  ``Account not found...``      404 Not Found   COBIL00C.cbl L361 (NOTFND)
  ``Card not found...``         404 Not Found   COBIL00C.cbl L379 (NOTFND)
  ``You have nothing to pay...``  400 Bad Request COBIL00C.cbl L201
  other / unknown               500 Server Err  e.g. tran_id exhaustion
  ============================  ==============  =======================

* **Unexpected failures** (SQLAlchemyError, StaleDataError from
  optimistic-concurrency on Account.version_id, database connection
  loss, etc.) are re-raised by the service after a best-effort
  rollback. The router catches them and translates into HTTP 500
  with a stable ``"Payment processing failed"`` detail to avoid
  leaking driver-level diagnostics to callers.

Both paths produce a consistent ABEND-DATA envelope via the global
error handler (:func:`src.api.middleware.error_handler.register_exception_handlers`).

Monetary precision
------------------
All monetary values (``amount``, ``current_balance``) are
:class:`decimal.Decimal` — **never** ``float``. Pydantic v2's
``BillPaymentRequest`` / ``BillPaymentResponse`` enforce this at the
schema boundary; the service applies Banker's-rounded Decimal
arithmetic (``ROUND_HALF_EVEN``) matching COBOL ``PIC S9(10)V99``
semantics.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* AAP §0.7.1 — Preserve existing error messages exactly
* :mod:`src.api.services.bill_service` — business logic
* :mod:`src.shared.schemas.bill_schema` — request/response contracts
* :mod:`src.api.dependencies` — ``get_db``, ``get_current_user``, ``CurrentUser``
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.dependencies import (
    CurrentUser,
    get_current_user,
    get_db,
)
from src.api.services.bill_service import BillService
from src.shared.schemas.bill_schema import (
    BillPaymentRequest,
    BillPaymentResponse,
)

logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# Replaces CICS transaction CB00 (COBIL00C.cbl bill payment).
#
# APIRouter instance — mounted by ``src/api/main.py`` with prefix
# ``/bills``. No prefix is applied here so the router module remains
# mount-path agnostic (useful in tests that mount the router directly
# without a prefix).
# ----------------------------------------------------------------------------
router: APIRouter = APIRouter()


# ----------------------------------------------------------------------------
# Message-to-HTTP-status-code mapping.
#
# The BillService returns :class:`BillPaymentResponse` with
# ``confirm='N'`` for business-rule failures, and embeds a user-facing
# ``message`` drawn from the COBOL source. These constants mirror the
# module-private ``_MSG_*`` constants in bill_service.py — kept in a
# local router-scoped table rather than imported to avoid coupling
# the router to an underscore-prefixed (private) name.
#
# Any service ``message`` not present in this table is treated as an
# unexpected failure and surfaced as HTTP 500 (e.g., the "Unable to
# generate transaction ID" fallback when an existing tran_id cannot
# be parsed as an integer — see bill_service.py lines 761-775).
# ----------------------------------------------------------------------------
_MSG_ACCOUNT_NOT_FOUND: str = "Account not found..."
_MSG_XREF_NOT_FOUND: str = "Card not found..."
_MSG_ZERO_BALANCE: str = "You have nothing to pay..."

#: Fallback detail when the service re-raises an unexpected exception
#: (SQLAlchemyError, StaleDataError, connection loss, etc.). Kept
#: short and stable so the ABEND-DATA envelope in the global error
#: handler emits a deterministic error string. The underlying
#: exception is logged with ``exc_info=True`` for diagnosis.
_MSG_PAYMENT_FAILURE_DETAIL: str = "Payment processing failed"


def _map_business_failure_to_http_status(message: str) -> int:
    """Translate a service-layer business-failure message to HTTP status.

    Parameters
    ----------
    message:
        The ``message`` field from a :class:`BillPaymentResponse`
        returned with ``confirm='N'``. This is the user-facing
        reason string sourced from COBIL00C.cbl (via the
        ``_MSG_*`` constants in :mod:`src.api.services.bill_service`).

    Returns
    -------
    int
        The HTTP status code to return via :class:`HTTPException`.
        Defaults to ``500 Internal Server Error`` when the message
        is unrecognised (e.g., a non-categorised business failure
        such as "Unable to generate transaction ID" — which indicates
        data-corruption-level trouble rather than a normal validation
        failure).

    Notes
    -----
    Exact-string comparison is used intentionally. The service
    constants are byte-for-byte stable per AAP §0.7.1 (preserve
    existing error messages exactly), so substring or prefix matching
    would introduce fragility without benefit.
    """
    if message == _MSG_ACCOUNT_NOT_FOUND:
        # COBOL: READ-ACCTDAT-FILE NOTFND branch (COBIL00C.cbl L361).
        # Resource lookup miss — REST maps this to 404 Not Found.
        return status.HTTP_404_NOT_FOUND
    if message == _MSG_XREF_NOT_FOUND:
        # COBOL: READ-CXACAIX-FILE NOTFND branch (COBIL00C.cbl L379).
        # Resource lookup miss (cross-reference) — REST maps to 404.
        return status.HTTP_404_NOT_FOUND
    if message == _MSG_ZERO_BALANCE:
        # COBOL: IF ACCT-CURR-BAL <= ZEROS branch (COBIL00C.cbl L198).
        # Business-rule violation (caller cannot pay a zero bill) —
        # REST maps to 400 Bad Request.
        return status.HTTP_400_BAD_REQUEST
    # Fallback: any other ``confirm='N'`` message (including the
    # "Unable to generate transaction ID" tran_id-parse failure from
    # bill_service.py L774) is treated as a server-side anomaly.
    return status.HTTP_500_INTERNAL_SERVER_ERROR


@router.post(
    "/pay",
    response_model=BillPaymentResponse,
    status_code=status.HTTP_200_OK,
    summary="Bill Payment",
    response_description=(
        "BillPaymentResponse with confirm='Y' and a success message on "
        "successful atomic dual-write (Transaction INSERT + Account "
        "balance UPDATE). Business-rule failures return 400/404 with "
        "the COBOL-sourced reason string as detail. Unexpected failures "
        "return 500 after the service rolls back both writes."
    ),
)
async def pay_bill(
    request: BillPaymentRequest,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> BillPaymentResponse:
    """Apply a bill payment against the specified account.

    Replaces the CICS ``COBIL00C`` program's PROCESS-ENTER-KEY
    paragraph (COBIL00C.cbl lines 154-240). The service layer owns
    the complete transactional flow — this router simply:

    1. Emits a structured log line with the caller's ``user_id``,
       the target ``acct_id``, and the requested ``amount`` for
       CloudWatch audit correlation (replaces the CICS JOURNAL
       WRITE that COBIL00C would have performed for audit).
    2. Delegates to :meth:`BillService.pay_bill` to execute the
       9-step dual-write atomically (``READ ACCTDAT`` → balance check
       → ``READ CXACAIX`` → generate tran_id → ``WRITE TRANSACT`` →
       ``REWRITE ACCTDAT`` → ``SYNCPOINT``).
    3. Inspects the returned :class:`BillPaymentResponse`:

       * ``confirm='Y'`` → the dual-write committed. Return as-is.
       * ``confirm='N'`` → business-rule failure. The service has
         already handled any rollback. Translate via
         :func:`_map_business_failure_to_http_status` to the
         appropriate 400 / 404 / 500.

    4. Catches any unexpected exception re-raised by the service
       (after its best-effort rollback) and translates into HTTP
       500 with a stable detail. The underlying exception is logged
       with ``exc_info=True`` for diagnosis.

    Parameters
    ----------
    request:
        :class:`BillPaymentRequest` with ``acct_id`` (max 11 chars,
        non-empty — validated by the Pydantic ``field_validator``
        upstream, mirroring COBIL00C.cbl L161's ``ACTIDINI`` presence
        check) and ``amount`` (positive :class:`decimal.Decimal`).
    db:
        Injected :class:`AsyncSession` connected to Aurora
        PostgreSQL. Provided by :func:`src.api.dependencies.get_db`.
        Replaces the CICS file-handle context for the ``ACCTDAT``,
        ``CXACAIX``, and ``TRANSACT`` VSAM datasets.
    current_user:
        Injected :class:`CurrentUser` decoded from the Bearer JWT.
        Provided by :func:`src.api.dependencies.get_current_user`.
        Replaces the COMMAREA ``CDEMO-USER-ID`` identity from
        ``COCOM01Y.cpy`` for audit-log purposes.

    Returns
    -------
    BillPaymentResponse
        On success: ``confirm='Y'``, ``current_balance`` = new
        post-payment balance, ``message`` = "Payment successful.
        Your Transaction ID is <tran_id>." (from COBIL00C.cbl
        L527-531).

    Raises
    ------
    HTTPException
        * ``404 Not Found`` — account or card cross-reference
          missing.
        * ``400 Bad Request`` — zero / negative balance
          (nothing to pay).
        * ``500 Internal Server Error`` — unexpected database or
          driver failure, or an un-categorised business failure
          such as a tran_id parse error.
    """
    # --------------------------------------------------------------
    # Step 1: Structured log — request arrival.
    #
    # COBOL mapping: implicit CICS transaction audit trail (LOGID /
    # SMF 110 records). We replace with explicit JSON-structured
    # logging so CloudWatch Logs Insights can query by ``user_id``
    # and ``acct_id``.
    # --------------------------------------------------------------
    log_context: dict[str, object] = {
        "user_id": current_user.user_id,
        "acct_id": request.acct_id,
        "amount": str(request.amount),  # Decimal → str (never float)
        "endpoint": "bill_payment",
    }
    logger.info("POST /bills/pay initiated", extra=log_context)

    # --------------------------------------------------------------
    # Step 2: Delegate to BillService for the dual-write.
    #
    # Note on request-validation: the agent_prompt's "validate
    # request.acct_id is non-empty" guard (from COBIL00C.cbl L161's
    # ACTIDINI presence check) is already enforced by the Pydantic
    # ``BillPaymentRequest`` field_validator (see
    # src/shared/schemas/bill_schema.py). A non-empty ``acct_id`` is
    # a precondition of reaching this line.
    #
    # Note on exception handling: we catch ``HTTPException`` first
    # and re-raise unchanged to allow any HTTPException raised by
    # downstream layers (e.g., from get_current_user on token
    # expiry) to propagate with their original status. A broad
    # ``except Exception`` then maps everything else (the service's
    # re-raised SQLAlchemyError / StaleDataError / etc.) to HTTP
    # 500, consistent with CICS's ABEND on abnormal termination.
    # --------------------------------------------------------------
    service = BillService(db)
    try:
        response: BillPaymentResponse = await service.pay_bill(request)
    except HTTPException:
        # Downstream HTTPException (e.g. auth / permission) —
        # propagate without re-wrapping.
        raise
    except Exception as exc:  # noqa: BLE001  — final safety net
        # The service has already attempted rollback by this point.
        # Log with ``exc_info`` for diagnosis in CloudWatch; surface
        # a stable generic error string to the caller to avoid
        # leaking driver internals.
        logger.error(
            "Bill payment failed with unexpected exception",
            extra={**log_context, "error_type": type(exc).__name__},
            exc_info=True,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=_MSG_PAYMENT_FAILURE_DETAIL,
        ) from exc

    # --------------------------------------------------------------
    # Step 3: Inspect the service response and translate
    # business-rule failures (confirm='N') into HTTPException.
    #
    # Per AAP §0.7.1 (preserve existing error messages exactly),
    # the service's ``message`` — which was sourced byte-for-byte
    # from COBIL00C.cbl and CSMSG01Y.cpy — is forwarded verbatim as
    # the HTTPException ``detail`` so the caller sees the original
    # COBOL user-facing text.
    # --------------------------------------------------------------
    if response.confirm != "Y":
        detail: str = response.message or "Bill payment failed"
        status_code: int = _map_business_failure_to_http_status(detail)
        logger.warning(
            "Bill payment business-rule failure",
            extra={
                **log_context,
                "confirm": response.confirm,
                "detail": detail,
                "status_code": status_code,
                "current_balance": str(response.current_balance),
            },
        )
        raise HTTPException(status_code=status_code, detail=detail)

    # --------------------------------------------------------------
    # Step 4: Success — log the successful dual-write for audit.
    #
    # COBOL mapping: the positive "Payment successful. Your
    # Transaction ID is <tran_id>." screen send at
    # COBIL00C.cbl L527-531. We log at INFO so the payment event
    # is captured in CloudWatch for financial audit even when the
    # response body is not retained by intermediaries.
    # --------------------------------------------------------------
    logger.info(
        "Bill payment succeeded",
        extra={
            **log_context,
            "confirm": response.confirm,
            "current_balance": str(response.current_balance),
            "message": response.message,
        },
    )
    return response


__all__ = ["router"]
