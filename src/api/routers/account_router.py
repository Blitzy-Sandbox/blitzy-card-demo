# ============================================================================
# Source: app/cbl/COACTVWC.cbl  (Account view, Feature F-004)
#         app/cbl/COACTUPC.cbl  (Account update, Feature F-005)
#         + app/cpy-bms/COACTVW.CPY, COACTUP.CPY  (BMS symbolic maps)
#         + app/cpy/CVACT01Y.cpy  (Account record layout) —
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
"""Account router — HTTP transport for Features F-004 and F-005.

Endpoint summary
----------------
``GET  /accounts/{acct_id}``   — Account view (F-004, COACTVWC.cbl)
``PUT  /accounts/{acct_id}``   — Account update (F-005, COACTUPC.cbl)

The router is a thin transport-layer shim — every business-logic path
(3-entity join across Account + CardCrossReference + Customer, the
field-level validation cascade, the path/body consistency check, the
optimistic-concurrency version guard, the dual-write transactional
rollback on failure) lives in :mod:`src.api.services.account_service`.

COBOL → HTTP mapping
--------------------
============================================  =================================
COBOL construct                               HTTP equivalent
============================================  =================================
``EXEC CICS RECEIVE MAP('COACTVW')``          Path parameter ``acct_id``
``EXEC CICS READ DATASET('ACCTFILE')``        :meth:`AccountService.get_account_view`
``EXEC CICS SEND MAP('COACTVW')``             :class:`AccountViewResponse`
``EXEC CICS REWRITE DATASET('ACCTFILE')``     :meth:`AccountService.update_account`
``WS-INFOMSG / WS-ERRMSG``                    :attr:`AccountViewResponse.info_message`
                                              / :attr:`error_message`
``CEE3ABD on RESP != NORMAL``                 HTTPException -> handler
============================================  =================================

Error surfacing
---------------
The service layer follows a "response-message" pattern: on business
failures (e.g. field validation, concurrency conflict, no-changes-
detected) it returns a populated response with ``error_message`` set
and the transaction rolled back — it does **not** raise. The router
inspects ``error_message`` after every call; when present the payload
is translated into :class:`HTTPException` with HTTP 400 so the global
error handler can wrap it in the ABEND-DATA envelope. Successful
responses (``error_message is None``) are returned as-is.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.api.services.account_service` — business logic
* :mod:`src.shared.schemas.account_schema` — request/response contracts
* :mod:`src.api.dependencies` — ``get_db``, ``get_current_user``, ``CurrentUser``
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Path, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.dependencies import (
    CurrentUser,
    get_current_user,
    get_db,
)
from src.api.services.account_service import AccountService
from src.shared.schemas.account_schema import (
    AccountUpdateRequest,
    AccountUpdateResponse,
    AccountViewResponse,
)

logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# APIRouter instance — mounted by ``src/api/main.py`` with prefix ``/accounts``.
# ----------------------------------------------------------------------------
router: APIRouter = APIRouter()


# ----------------------------------------------------------------------------
# Path-parameter regex
#
# Accounts are keyed by the 11-digit COBOL PIC 9(11) ACCT-ID field.
# We constrain the path parameter with a regex so that malformed
# account IDs are rejected by the framework (422 Unprocessable) before
# the service layer is hit. This is a lightweight transport-layer
# check; the authoritative validation remains in the service.
# ----------------------------------------------------------------------------
_ACCT_ID_REGEX: str = r"^[0-9]{11}$"


@router.get(
    "/{acct_id}",
    response_model=AccountViewResponse,
    status_code=status.HTTP_200_OK,
    summary="Account view — retrieve full account record (F-004 COACTVWC.cbl)",
    response_description=(
        "Account with joined Card xref and Customer fields (31 business fields "
        "from the legacy CACTVWAI BMS symbolic map)."
    ),
)
async def view_account(
    acct_id: str = Path(
        ...,
        pattern=_ACCT_ID_REGEX,
        description="Account ID — exactly 11 digits (COBOL PIC 9(11))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> AccountViewResponse:
    """Retrieve the full account record for ``acct_id``.

    Replaces the CICS ``COACTVWC`` program's ``EVALUATE-FILE-READ``
    branch that performed three sequential VSAM reads:
    ``READ-ACCOUNT-FILE`` (ACCTFILE), ``READ-CARD-XREF-FILE``
    (CXACAIX AIX path on ACCT-ID), ``READ-CUSTOMER-FILE`` (CUSTFILE)
    — and populated the ``CACTVWAI`` BMS map fields. The service layer
    performs the equivalent 3-entity SQLAlchemy join in a single
    transaction.
    """
    logger.info(
        "GET /accounts/%s initiated",
        acct_id,
        extra={
            "user_id": current_user.user_id,
            "acct_id": acct_id,
            "endpoint": "account_view",
        },
    )
    service = AccountService(db)
    response = await service.get_account_view(acct_id)
    if response.error_message:
        # Service surfaces user-facing errors (e.g., "Account not found")
        # via error_message. We translate to 400 so the global handler
        # wraps it in ABEND-DATA; 404 would be more semantic for the
        # specific NOTFND case, but the service's response-message
        # pattern does not differentiate reasons in the response body,
        # so we default to 400 here (caller can read the message body
        # for the specific reason). Per AAP §0.7.1 minimal-change, the
        # service's pattern is preserved untouched.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.error_message,
        )
    return response


@router.put(
    "/{acct_id}",
    response_model=AccountUpdateResponse,
    status_code=status.HTTP_200_OK,
    summary="Account update — replace-style update (F-005 COACTUPC.cbl)",
    response_description=(
        "Updated account record, or same record with error_message set "
        "when validation / concurrency / change-detection fails."
    ),
)
async def update_account(
    request: AccountUpdateRequest,
    acct_id: str = Path(
        ...,
        pattern=_ACCT_ID_REGEX,
        description="Account ID — exactly 11 digits (COBOL PIC 9(11))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> AccountUpdateResponse:
    """Update the account record for ``acct_id``.

    Replaces the CICS ``COACTUPC`` program's ``EVALUATE-ALT-KEY-UPDATE``
    flow (COACTUPC.cbl, 4,236 lines) that performed:

    * ``RECEIVE MAP('COACTUP')`` (request body here)
    * Field-level validation (mirrored in the service's
      ``_ParsedRequest`` private helper)
    * ``READ DATASET('ACCTFILE') UPDATE`` with VSAM record-level lock
    * Change-detection vs the just-read record (COACTUPC lines 473-509)
    * ``REWRITE DATASET('ACCTFILE')`` + ``REWRITE DATASET('CUSTFILE')``
      (dual-write, atomic across both)
    * ``SYNCPOINT`` on success, ``SYNCPOINT ROLLBACK`` on failure

    The cloud-native equivalent wraps the dual-write in a single
    SQLAlchemy transaction; the service commits on success and relies
    on :func:`get_db` to roll back on exception. Optimistic
    concurrency is enforced via ``Account.version_id`` (AAP §0.4.3).
    """
    logger.info(
        "PUT /accounts/%s initiated",
        acct_id,
        extra={
            "user_id": current_user.user_id,
            "acct_id": acct_id,
            "endpoint": "account_update",
        },
    )
    service = AccountService(db)
    response = await service.update_account(acct_id, request)
    if response.error_message:
        # Service-layer business failures (validation, optimistic
        # concurrency violation, no-change-detected, path/body
        # inconsistency) are surfaced via error_message with the
        # transaction already rolled back. Translate to HTTP 400 for
        # the global error handler.
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.error_message,
        )
    return response


__all__ = ["router"]
