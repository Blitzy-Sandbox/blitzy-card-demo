# ============================================================================
# Source: app/cbl/COBIL00C.cbl  (Bill payment, Feature F-012)
#         + app/cpy-bms/COBIL00.CPY  (BMS symbolic map)
#         + app/cpy/CVACT01Y.cpy     (Account record layout)
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
"""Bill payment router — HTTP transport for Feature F-012.

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
The service uses a "confirm Y/N + message" response pattern (not
``error_message``):

* **Success** — ``confirm='Y'``, ``message`` usually ``None`` or a
  positive confirmation string. Router returns the response as-is
  with HTTP 200.
* **Business failure** — ``confirm='N'``, ``message`` populated
  with the user-facing reason (e.g. "Account has zero balance,
  nothing to pay", "Account not found"). Router translates to
  :class:`HTTPException` (400) so the global ABEND-DATA handler can
  wrap it in a consistent error envelope.
* **Unexpected DB / driver failure** — the service re-raises the
  underlying :class:`~sqlalchemy.exc.SQLAlchemyError` after issuing
  a rollback. The global handler catches it and emits the DBIO
  ABEND-DATA payload.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.api.services.bill_service` — business logic
* :mod:`src.shared.schemas.bill_schema` — request/response contracts
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

router: APIRouter = APIRouter()


@router.post(
    "/pay",
    response_model=BillPaymentResponse,
    status_code=status.HTTP_200_OK,
    summary="Bill payment — atomic dual-write (F-012 COBIL00C.cbl)",
    response_description=(
        "confirm='Y' on successful payment application; confirm='N' "
        "with a descriptive message on business-rule failures "
        "(zero balance, missing account, missing xref). Either way "
        "the database state is consistent — either both the "
        "Transaction INSERT and the Account UPDATE committed "
        "atomically, or neither did."
    ),
)
async def pay_bill(
    request: BillPaymentRequest,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> BillPaymentResponse:
    """Apply a bill payment against the specified account.

    Replaces the CICS ``COBIL00C`` program's PROCESS-ENTER-KEY
    paragraph. The service layer owns the complete transactional
    flow — this router simply:

    1. Passes the validated request through to
       :meth:`BillService.pay_bill`.
    2. Translates a ``confirm='N'`` response into an HTTP 400 so the
       global error handler emits ABEND-DATA (the service's
       response shape is preserved as the HTTPException ``detail``).
    3. Returns successful responses (``confirm='Y'``) unchanged.
    """
    logger.info(
        "POST /bills/pay initiated",
        extra={
            "user_id": current_user.user_id,
            "acct_id": request.acct_id,
            "amount": str(request.amount),
            "endpoint": "bill_payment",
        },
    )
    service = BillService(db)
    response = await service.pay_bill(request)
    if response.confirm != "Y":
        # Business-level failure (zero balance, account not found,
        # xref mismatch, etc.) — the service has already rolled back.
        # Re-surface as HTTPException(400) for the global ABEND-DATA
        # handler.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.message or "Bill payment failed",
        )
    return response


__all__ = ["router"]
