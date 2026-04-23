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
inspects ``error_message`` after every call and translates the message
into :class:`HTTPException` with the most semantically appropriate
HTTP status code so the global error handler can wrap it in the
ABEND-DATA envelope. The mapping is driven by the service's
module-level ``_MSG_*`` string constants (imported directly below) —
this yields an exact-match discrimination that is more precise than
substring matching and resistant to future message text drift:

==========================================  ===============================
Service ``error_message`` constant          HTTP status code
==========================================  ===============================
``_MSG_VIEW_XREF_NOT_FOUND``                404 Not Found
``_MSG_VIEW_ACCT_NOT_FOUND``                404 Not Found
``_MSG_VIEW_CUST_NOT_FOUND``                404 Not Found
``_MSG_UPDATE_STALE`` (OCC conflict)        409 Conflict
All other ``error_message`` values          400 Bad Request
==========================================  ===============================

The 404 mapping aligns with REST conventions for missing resources;
the 409 mapping aligns with W3C RFC 7231 §6.5.8 for optimistic-
concurrency conflicts. All other service-layer failures (validation,
path/body mismatch, no-change-detected, zip-state inconsistency,
update-failed) remain HTTP 400. Successful responses
(``error_message is None``) are returned as-is with HTTP 200.

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
from src.api.services.account_service import (
    _MSG_UPDATE_STALE,
    _MSG_VIEW_ACCT_NOT_FOUND,
    _MSG_VIEW_CUST_NOT_FOUND,
    _MSG_VIEW_XREF_NOT_FOUND,
    AccountService,
)
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


# ----------------------------------------------------------------------------
# Error-message → HTTP status code mapping
#
# Maps the service layer's module-level ``_MSG_*`` error string constants to
# semantically appropriate HTTP status codes. The three "NOT FOUND" messages
# map to HTTP 404 (missing-resource convention per RFC 7231 §6.5.4) and the
# optimistic-concurrency stale-record message maps to HTTP 409 (conflict
# convention per RFC 7231 §6.5.8). Any ``error_message`` not listed here
# defaults to HTTP 400 Bad Request via :func:`_map_error_to_status`.
#
# Using module-level constants from ``account_service`` (rather than inline
# substring matching) keeps the mapping table precise: two messages with
# overlapping substrings cannot collide, and any future message-text drift
# in the service will surface as a ``SyntaxError`` / ``ImportError`` at
# module load time rather than a silent mis-routing.
# ----------------------------------------------------------------------------
_ERROR_MESSAGE_STATUS_MAP: dict[str, int] = {
    _MSG_VIEW_XREF_NOT_FOUND: status.HTTP_404_NOT_FOUND,
    _MSG_VIEW_ACCT_NOT_FOUND: status.HTTP_404_NOT_FOUND,
    _MSG_VIEW_CUST_NOT_FOUND: status.HTTP_404_NOT_FOUND,
    _MSG_UPDATE_STALE: status.HTTP_409_CONFLICT,
}


def _map_error_to_status(error_message: str) -> int:
    """Return the HTTP status code for a service-layer ``error_message``.

    Looks up ``error_message`` in :data:`_ERROR_MESSAGE_STATUS_MAP` and
    returns the mapped status code; falls back to HTTP 400 for any message
    not registered in the map. The lookup is an exact string-equality
    match — there is no substring or prefix matching, so two distinct
    service messages cannot collide even if they share a common substring.

    Parameters
    ----------
    error_message:
        The ``error_message`` field from the service response. MUST be a
        populated (non-empty) string; callers MUST check
        ``if response.error_message`` before invoking this function.

    Returns
    -------
    int
        The HTTP status code to use in the raised :class:`HTTPException`.
        One of 400, 404, or 409.
    """
    return _ERROR_MESSAGE_STATUS_MAP.get(error_message, status.HTTP_400_BAD_REQUEST)


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
async def get_account(
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
        # Translate the service's response-message into an HTTPException
        # with the most semantically appropriate HTTP status code. The
        # three "NOT FOUND" messages (xref, acct, cust) map to 404; all
        # other error messages (validation failures, invalid account IDs,
        # etc.) map to 400. See :data:`_ERROR_MESSAGE_STATUS_MAP` for the
        # full table. The global error handler wraps the HTTPException in
        # the ABEND-DATA envelope.
        http_status = _map_error_to_status(response.error_message)
        log_level = logger.info if http_status == status.HTTP_404_NOT_FOUND else logger.warning
        log_level(
            "GET /accounts/%s returned service error: %s (HTTP %d)",
            acct_id,
            response.error_message,
            http_status,
            extra={
                "user_id": current_user.user_id,
                "acct_id": acct_id,
                "endpoint": "account_view",
                "http_status": http_status,
            },
        )
        raise HTTPException(
            status_code=http_status,
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
        # Translate the service's response-message into an HTTPException
        # with the most semantically appropriate HTTP status code:
        #
        #   * _MSG_VIEW_*_NOT_FOUND  -> 404 (missing target record)
        #   * _MSG_UPDATE_STALE      -> 409 (optimistic-concurrency
        #                                conflict; RFC 7231 §6.5.8)
        #   * All other messages     -> 400 (validation, path/body
        #                                mismatch, no-change-detected,
        #                                zip/state inconsistency, etc.)
        #
        # See :data:`_ERROR_MESSAGE_STATUS_MAP` for the exact table.
        # The transaction has already been rolled back by the service.
        http_status = _map_error_to_status(response.error_message)
        log_level = logger.info if http_status == status.HTTP_404_NOT_FOUND else logger.warning
        log_level(
            "PUT /accounts/%s returned service error: %s (HTTP %d)",
            acct_id,
            response.error_message,
            http_status,
            extra={
                "user_id": current_user.user_id,
                "acct_id": acct_id,
                "endpoint": "account_update",
                "http_status": http_status,
            },
        )
        raise HTTPException(
            status_code=http_status,
            detail=response.error_message,
        )
    return response


__all__ = ["router"]
