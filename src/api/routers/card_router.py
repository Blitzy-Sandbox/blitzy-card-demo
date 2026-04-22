# ============================================================================
# Source: app/cbl/COCRDLIC.cbl  (Card list, Feature F-006)
#         app/cbl/COCRDSLC.cbl  (Card detail, Feature F-007)
#         app/cbl/COCRDUPC.cbl  (Card update, Feature F-008)
#         + app/cpy-bms/COCRDLI.CPY, COCRDSL.CPY, COCRDUP.CPY
#         + app/cpy/CVACT02Y.cpy  (Card record layout) —
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
"""Card router — HTTP transport for Features F-006, F-007, and F-008.

Endpoint summary
----------------
``GET  /cards``                — Paginated card list (F-006, COCRDLIC.cbl)
``GET  /cards/{card_num}``     — Card detail view (F-007, COCRDSLC.cbl)
``PUT  /cards/{card_num}``     — Card update with optimistic
                                 concurrency (F-008, COCRDUPC.cbl)

The router is a thin transport-layer shim that delegates to
:class:`src.api.services.card_service.CardService`. The service
owns every business-logic path:

* 7-rows-per-page pagination using 4 STARTBR/READNEXT window slots
  (mirrors the original CICS page-size of 7);
* CARDDAT dataset read-by-primary-key for the detail screen;
* optimistic concurrency via :class:`~sqlalchemy.orm.Mapped`
  ``version`` column (matching the CICS ``READ UPDATE`` / ``REWRITE``
  semantic in COCRDUPC);
* dual-field change detection against the pre-read snapshot to
  detect concurrent modification.

COBOL → HTTP mapping
--------------------
====================================================  =======================
COBOL construct                                       HTTP equivalent
====================================================  =======================
``RECEIVE MAP('COCRDLI')`` + STARTBR / READNEXT       ``GET /cards``
``RECEIVE MAP('COCRDSL')`` + READ FILE('CARDDAT')     ``GET /cards/{card}``
``READ UPDATE`` / ``REWRITE`` + change-detect         ``PUT /cards/{card}``
WS-INFOMSG / WS-ERRMSG                                response ``info_message``
                                                      / ``error_message``
====================================================  =======================

Error surfacing
---------------
Same "response-message" pattern as ``account_router``: services
return a populated response with ``error_message`` set when a
business failure occurs — router translates to HTTPException 400 for
the global ABEND-DATA wrapper. When ``error_message`` is ``None``
the response is returned as-is.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.api.services.card_service` — business logic
* :mod:`src.shared.schemas.card_schema` — request/response contracts
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
from src.api.services.card_service import CardService
from src.shared.schemas.card_schema import (
    CardDetailResponse,
    CardListRequest,
    CardListResponse,
    CardUpdateRequest,
    CardUpdateResponse,
)

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter()


# ----------------------------------------------------------------------------
# Path-parameter regex
#
# Cards are keyed by the 16-digit COBOL PIC X(16) CARD-NUM field.
# COBOL allows any X display character here, but real-world card
# numbers are always all-numeric. We constrain the path parameter
# to exactly 16 digits so malformed keys are rejected by the
# framework with 422 before the service runs. The service layer
# independently repeats this validation.
# ----------------------------------------------------------------------------
_CARD_NUM_REGEX: str = r"^[0-9]{16}$"


@router.get(
    "",
    response_model=CardListResponse,
    status_code=status.HTTP_200_OK,
    summary="Card list — paginated (F-006 COCRDLIC.cbl)",
    response_description=(
        "Up to 7 card rows per page (matching original BMS map size); "
        "navigation flags indicate whether previous/next pages exist."
    ),
)
async def list_cards(
    account_id: str | None = Query(
        default=None,
        max_length=11,
        description=(
            "Optional 11-digit account ID filter. Maps to COCRDLI "
            "ACCTSIDI PIC X(11). When supplied, restricts the list "
            "to cards owned by this account."
        ),
    ),
    card_number: str | None = Query(
        default=None,
        max_length=16,
        description=(
            "Optional 16-character card-number filter used to locate "
            "(jump to) a specific card. Maps to COCRDLI CARDSIDI "
            "PIC X(16)."
        ),
    ),
    page_number: int = Query(
        default=1,
        ge=1,
        description=("1-based page number (defaults to 1). Maps to COCRDLI PAGENOI PIC X(03)."),
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> CardListResponse:
    """Return a paginated window of card rows for the card list screen.

    The modernized query accepts the same three filter inputs as the
    COCRDLIC BMS screen (account_id, card_number, page_number) via
    HTTP query parameters and returns the same 7-rows-per-page
    window. The service layer is responsible for the forward /
    backward STARTBR / READNEXT pagination logic.
    """
    logger.info(
        "GET /cards initiated",
        extra={
            "user_id": current_user.user_id,
            "account_id_filter": account_id,
            "card_number_filter": card_number,
            "page_number": page_number,
            "endpoint": "card_list",
        },
    )
    request = CardListRequest(
        account_id=account_id,
        card_number=card_number,
        page_number=page_number,
    )
    service = CardService(db)
    response = await service.list_cards(request)
    if response.error_message:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.error_message,
        )
    return response


@router.get(
    "/{card_num}",
    response_model=CardDetailResponse,
    status_code=status.HTTP_200_OK,
    summary="Card detail view (F-007 COCRDSLC.cbl)",
    response_description=(
        "Full 150-byte card record with cardholder name, expiry, CVV, and active status (CVACT02Y layout)."
    ),
)
async def view_card(
    card_num: str = Path(
        ...,
        pattern=_CARD_NUM_REGEX,
        description="Card number — exactly 16 digits (COBOL PIC X(16))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> CardDetailResponse:
    """Return the full card record for ``card_num``.

    Replaces the CICS ``COCRDSLC`` program's ``READ-CARDDAT``
    paragraph that performed a single ``EXEC CICS READ
    DATASET('CARDDAT') RIDFLD(CARD-NUM)`` and populated the
    ``CCRDSLAI`` BMS map. The equivalent SQLAlchemy primary-key
    lookup is inside :meth:`CardService.get_card_detail`.
    """
    logger.info(
        "GET /cards/%s initiated",
        card_num,
        extra={
            "user_id": current_user.user_id,
            "card_num": card_num,
            "endpoint": "card_detail",
        },
    )
    service = CardService(db)
    response = await service.get_card_detail(card_num)
    if response.error_message:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.error_message,
        )
    return response


@router.put(
    "/{card_num}",
    response_model=CardUpdateResponse,
    status_code=status.HTTP_200_OK,
    summary="Card update with optimistic concurrency (F-008 COCRDUPC.cbl)",
    response_description=(
        "Updated card record reflecting committed changes. A populated "
        "error_message indicates validation, no-change, or concurrency "
        "conflict failure (transaction rolled back)."
    ),
)
async def update_card(
    request: CardUpdateRequest,
    card_num: str = Path(
        ...,
        pattern=_CARD_NUM_REGEX,
        description="Card number — exactly 16 digits (COBOL PIC X(16))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
) -> CardUpdateResponse:
    """Update the mutable fields of a card.

    Replaces the CICS ``COCRDUPC`` program's ``9200-WRITE-PROCESSING``
    / ``9300-CHECK-CHANGE-IN-REC`` paragraphs (COCRDUPC.cbl lines
    1420-1498+). The COBOL flow was:

    1. ``READ FILE('CARDDAT') UPDATE`` (enqueue / lock the record).
    2. Compare the just-read record against
       ``CARD-RECORD-BEFORE-UPDATE`` (the snapshot captured on the
       initial display) to detect concurrent modification.
    3. ``REWRITE`` on success, ``SYNCPOINT``; else
       ``UNLOCK`` + ``SYNCPOINT ROLLBACK``.

    The modernized equivalent uses SQLAlchemy optimistic-concurrency
    control (``Card.version`` mapped column) inside a single
    transaction. A version mismatch results in a rolled-back
    response with ``error_message`` populated.
    """
    logger.info(
        "PUT /cards/%s initiated",
        card_num,
        extra={
            "user_id": current_user.user_id,
            "card_num": card_num,
            "endpoint": "card_update",
        },
    )
    service = CardService(db)
    response = await service.update_card(card_num, request)
    if response.error_message:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=response.error_message,
        )
    return response


__all__ = ["router"]
