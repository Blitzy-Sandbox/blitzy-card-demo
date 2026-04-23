# ============================================================================
# Source: app/cbl/COTRN00C.cbl  (Transaction list, Feature F-009)
#         app/cbl/COTRN01C.cbl  (Transaction detail view, Feature F-010)
#         app/cbl/COTRN02C.cbl  (Transaction add, Feature F-011)
#         + app/cpy-bms/COTRN00.CPY, COTRN01.CPY, COTRN02.CPY
#         + app/cpy/CVTRA05Y.cpy  (Transaction record layout, 350B) —
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
"""Transaction router — HTTP transport for Features F-009, F-010, F-011.

Endpoint summary
----------------
``GET  /transactions``                — Paginated transaction list
                                        (F-009, COTRN00C.cbl)
``GET  /transactions/{tran_id}``      — Transaction detail view
                                        (F-010, COTRN01C.cbl)
``POST /transactions``                — Transaction add with
                                        xref-resolved account lookup
                                        and auto-generated tran_id
                                        (F-011, COTRN02C.cbl)

The router delegates every business-logic path to
:class:`src.api.services.transaction_service.TransactionService`. The
service owns:

* 10-rows-per-page pagination via server-side ``LIMIT/OFFSET`` (the
  modern SQL analogue of COBOL's ``STARTBR``/``READNEXT`` browse);
* ``LIKE`` prefix-match filter on ``tran_id`` (mirrors the COBOL
  "jump-to" semantics on the ``TRNIDINI`` input);
* CARDAIX cross-reference resolution on ``card_num`` -> ``acct_id``
  during transaction creation (``COTRN02C`` paragraph ``1020-XREF-LOOKUP``);
* server-generated transaction ID (COBOL's ``ASSIGN`` + sequence);
* atomic single-table insert (no dual-write for F-011).

COBOL → HTTP mapping
--------------------
======================================================  =======================
COBOL construct                                         HTTP equivalent
======================================================  =======================
``RECEIVE MAP('COTRN00')`` + STARTBR / READNEXT         ``GET /transactions``
``RECEIVE MAP('COTRN01')`` + READ FILE('TRANSACT')      ``GET /transactions/{id}``
``RECEIVE MAP('COTRN02')`` + WRITE FILE('TRANSACT')     ``POST /transactions``
WS-ERRMSG / WS-CONFIRM                                  response ``message``
                                                        / ``confirm``
======================================================  =======================

Error surfacing
---------------
``TransactionService`` uses two slightly different surfacing
patterns — both handled uniformly here:

* **List** (GET ``/transactions``): on failure the service returns a
  response with ``message`` populated (e.g.
  ``_MSG_UNABLE_TO_LOOKUP_LIST``) and an empty ``transactions``
  list. The router translates a populated ``message`` into
  :class:`HTTPException` (400).
* **Detail** (GET ``/transactions/{tran_id}``): on failure the
  service returns a response with ``message`` populated. The router
  differentiates:

    * ``_MSG_TRAN_NOT_FOUND`` ("Transaction ID NOT found...") →
      :class:`HTTPException` (404).
    * ``_MSG_TRAN_ID_EMPTY``, ``_MSG_UNABLE_TO_LOOKUP_DETAIL`` →
      :class:`HTTPException` (400).

* **Add** (POST ``/transactions``): the service returns
  :class:`TransactionAddResponse` with ``confirm='Y'`` on success,
  ``confirm='N'`` on failure (with ``message`` set to the reason).
  The router differentiates:

    * ``_MSG_CARD_NOT_IN_XREF``, ``_MSG_ACCT_CARD_MISMATCH``
      (card↔account cross-reference failure) →
      :class:`HTTPException` (404).
    * ``_MSG_UNABLE_TO_ADD`` and other validation errors →
      :class:`HTTPException` (400).

In all cases the global ABEND-DATA handler wraps the HTTP error for a
consistent client-facing envelope.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.api.services.transaction_service` — business logic
* :mod:`src.shared.schemas.transaction_schema` — request/response contracts
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Path, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.dependencies import (
    CurrentUser,
    get_current_user,
    get_db,
)
from src.api.services.transaction_service import TransactionService
from src.shared.schemas.transaction_schema import (
    TransactionAddRequest,
    TransactionAddResponse,
    TransactionDetailResponse,
    TransactionListRequest,
    TransactionListResponse,
)

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter()


# ----------------------------------------------------------------------------
# Path-parameter regex
#
# Transactions are keyed by a 16-character COBOL PIC X(16) TRAN-ID
# field. The field is fixed-width but may contain non-digit
# characters (e.g. space-padded short values). We accept 1-16
# characters of any printable, non-whitespace character and rely on
# the service for deeper validation.
# ----------------------------------------------------------------------------
_TRAN_ID_REGEX: str = r"^[A-Za-z0-9_\-]{1,16}$"


@router.get(
    "",
    response_model=TransactionListResponse,
    status_code=status.HTTP_200_OK,
    summary="Transaction list — paginated (F-009 COTRN00C.cbl)",
    response_description=(
        "Up to 10 transaction rows per page (matching the original "
        "COTRN00 BMS map layout) with total_count and page number."
    ),
)
async def list_transactions(
    tran_id: str | None = Query(
        default=None,
        max_length=16,
        description=(
            "Optional 16-character transaction ID prefix filter. Maps "
            "to COTRN00 TRNIDINI PIC X(16). Uses LIKE 'tran_id%' "
            "(starts-with) semantics per the 'jump-to' behavior of "
            "the original COBOL browse cursor."
        ),
    ),
    page: int = Query(
        default=1,
        ge=1,
        description=("1-based page number (defaults to 1). Maps to COTRN00 PAGENUMI PIC X(08)."),
    ),
    page_size: int = Query(
        default=10,
        ge=1,
        le=100,
        description=(
            "Rows per page (defaults to 10 — matches the 10-repeated-row COTRN00 BMS layout). Bounded to [1, 100]."
        ),
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> TransactionListResponse:
    """Return a paginated window of transactions (10 per page by default).

    Replaces the CICS ``COTRN00C`` program's ``STARTBR``/``READNEXT``
    browse cursor over the ``TRANSACT`` dataset. Since SQL pagination
    is idempotent on ``page``, the COBOL forward-page / backward-page
    distinction collapses into a single query path here: the client
    simply increments or decrements ``page``.
    """
    logger.info(
        "GET /transactions initiated",
        extra={
            "user_id": current_user.user_id,
            "tran_id_filter": tran_id,
            "page": page,
            "page_size": page_size,
            "endpoint": "transaction_list",
        },
    )
    request = TransactionListRequest(
        tran_id=tran_id,
        page=page,
        page_size=page_size,
    )
    service = TransactionService(db)
    response = await service.list_transactions(request)
    if response.message:
        # The service uses response.message as the error-surfacing
        # channel. A populated message on a GET-list response always
        # indicates a failure condition (e.g. DB error — see
        # _MSG_UNABLE_TO_LOOKUP_LIST). A successful list returns
        # message=None and transactions=[...].
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.message,
        )
    return response


@router.get(
    "/{tran_id}",
    response_model=TransactionDetailResponse,
    status_code=status.HTTP_200_OK,
    summary="Transaction detail view (F-010 COTRN01C.cbl)",
    response_description=(
        "Full 350-byte transaction record with merchant, dates, amount, description (CVTRA05Y layout)."
    ),
)
async def get_transaction(
    tran_id: str = Path(
        ...,
        pattern=_TRAN_ID_REGEX,
        min_length=1,
        max_length=16,
        description="Transaction ID — up to 16 characters (COBOL PIC X(16))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> TransactionDetailResponse:
    """Return the full transaction record for ``tran_id``.

    Replaces the CICS ``COTRN01C`` program's ``READ-TRANSACT``
    paragraph that performed a single ``EXEC CICS READ
    DATASET('TRANSACT') RIDFLD(TRAN-ID)`` and populated the
    ``CTRN01AI`` BMS map. The modernized equivalent is a SQLAlchemy
    primary-key lookup in :meth:`TransactionService.get_transaction_detail`.

    Status-code semantics
    ---------------------
    * **200 OK** — record found; full 350-byte record returned.
    * **404 Not Found** — service returned ``_MSG_TRAN_NOT_FOUND``
      ("Transaction ID NOT found..."). The router uses the presence
      of "NOT found" in the message as the discriminator (the only
      service message that contains that phrase).
    * **400 Bad Request** — any other service-returned error
      (empty ID, DB lookup failure, etc.).
    """
    logger.info(
        "GET /transactions/%s initiated",
        tran_id,
        extra={
            "user_id": current_user.user_id,
            "tran_id": tran_id,
            "endpoint": "transaction_detail",
        },
    )
    service = TransactionService(db)
    response = await service.get_transaction_detail(tran_id)
    if response.message:
        # A populated message indicates failure. Differentiate:
        #   * "Transaction ID NOT found..." (_MSG_TRAN_NOT_FOUND)
        #     → 404 (semantically correct HTTP for missing resource).
        #   * Everything else (_MSG_TRAN_ID_EMPTY,
        #     _MSG_UNABLE_TO_LOOKUP_DETAIL) → 400.
        # The substring "NOT found" is unique to _MSG_TRAN_NOT_FOUND
        # among the service's detail-path messages.
        if "NOT found" in response.message:
            logger.info(
                "Transaction not found",
                extra={
                    "user_id": current_user.user_id,
                    "tran_id": tran_id,
                    "endpoint": "transaction_detail",
                },
            )
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=response.message,
            )
        logger.warning(
            "Transaction detail lookup failed",
            extra={
                "user_id": current_user.user_id,
                "tran_id": tran_id,
                "endpoint": "transaction_detail",
                "service_message": response.message,
            },
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.message,
        )
    return response


@router.post(
    "",
    response_model=TransactionAddResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Transaction add (F-011 COTRN02C.cbl)",
    response_description=(
        "Created transaction with server-generated tran_id. confirm='Y' "
        "on success; confirm='N' with message on business failure."
    ),
)
async def add_transaction(
    request: TransactionAddRequest,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> TransactionAddResponse:
    """Create a new transaction with auto-ID generation.

    Replaces the CICS ``COTRN02C`` program's add-transaction flow:

    1. ``RECEIVE MAP('COTRN02')`` — request body here.
    2. ``1020-XREF-LOOKUP`` — look up ``CARD-NUM`` on the CXACAIX AIX
       path to resolve the owning ``ACCT-ID``.
    3. ``1030-ASSIGN-TRAN-ID`` — derive the next sequence.
    4. ``1040-WRITE-TRANSACT`` — ``EXEC CICS WRITE
       DATASET('TRANSACT')``.
    5. ``SYNCPOINT`` on success; on failure, write-error returns
       ``ERRMSGO`` and the transaction aborts.

    The service layer performs the xref lookup via SQLAlchemy and the
    insert inside a single transaction. A successful insert returns
    ``confirm='Y'``; any business failure (e.g. card not in xref,
    xref mismatch, DB error) returns ``confirm='N'`` with the
    failure reason in ``message``.
    """
    logger.info(
        "POST /transactions initiated",
        extra={
            "user_id": current_user.user_id,
            "card_num": request.card_num,
            "endpoint": "transaction_add",
        },
    )
    service = TransactionService(db)
    response = await service.add_transaction(request)
    if response.confirm != "Y":
        # Business-level failure. The service has already rolled back
        # and populated response.message with the specific reason.
        # Differentiate by service message:
        #   * _MSG_CARD_NOT_IN_XREF  ("Unable to lookup Card # in XREF...")
        #   * _MSG_ACCT_CARD_MISMATCH ("Account/Card mismatch in XREF...")
        #     → 404 (missing / mismatched cross-reference resource).
        #   * _MSG_UNABLE_TO_ADD and other validation errors → 400.
        # The substring "XREF" is unique to the cross-reference
        # failure messages in the service.
        detail_message = response.message or "Transaction add failed"
        if "XREF" in detail_message:
            logger.info(
                "Transaction add rejected: cross-reference lookup failed",
                extra={
                    "user_id": current_user.user_id,
                    "card_num": request.card_num,
                    "acct_id": request.acct_id,
                    "endpoint": "transaction_add",
                    "service_message": detail_message,
                },
            )
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=detail_message,
            )
        logger.warning(
            "Transaction add failed",
            extra={
                "user_id": current_user.user_id,
                "card_num": request.card_num,
                "endpoint": "transaction_add",
                "service_message": detail_message,
            },
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=detail_message,
        )
    logger.info(
        "Transaction added successfully",
        extra={
            "user_id": current_user.user_id,
            "tran_id": response.tran_id,
            "card_num": response.card_num,
            "acct_id": response.acct_id,
            "endpoint": "transaction_add",
        },
    )
    return response


__all__ = ["router"]
