# ============================================================================
# Source: app/cbl/COUSR00C.cbl  (User list, Feature F-018)
#         app/cbl/COUSR01C.cbl  (User add, Feature F-019)
#         app/cbl/COUSR02C.cbl  (User update, Feature F-020)
#         app/cbl/COUSR03C.cbl  (User delete, Feature F-021)
#         + app/cpy-bms/COUSR00.CPY, COUSR01.CPY, COUSR02.CPY, COUSR03.CPY
#         + app/cpy/CSUSR01Y.cpy  (User security record layout) —
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
"""User-administration router — HTTP transport for F-018, F-019, F-020, F-021.

Endpoint summary
----------------
``GET    /users``             — Paginated user list (F-018, COUSR00C.cbl)
``POST   /users``             — User add with BCrypt (F-019, COUSR01C.cbl)
``PUT    /users/{user_id}``   — User update (F-020, COUSR02C.cbl)
``DELETE /users/{user_id}``   — User delete (F-021, COUSR03C.cbl)

**All four endpoints are admin-only.** The :class:`JWTAuthMiddleware`
enforces this globally via the ``/users`` prefix being listed in
``ADMIN_ONLY_PREFIXES``. Non-admin users (``user_type != 'A'``)
receive an HTTP 403 ABEND-DATA envelope before the router is reached.

The router delegates every business-logic path to
:class:`src.api.services.user_service.UserService`. Unlike the
account / card / transaction / bill / report services (which return
populated error fields on the response), the user service uses a
**typed-exception** pattern:

* :class:`UserIdAlreadyExistsError`   -> HTTP 409 Conflict
* :class:`UserNotFoundError`          -> HTTP 404 Not Found
* :class:`UserValidationError`        -> HTTP 400 Bad Request
* :class:`UserServiceError`           -> HTTP 500 (generic internal)

The router catches each of these and translates to the corresponding
HTTPException so the global ABEND-DATA handler emits the right
envelope.

COBOL → HTTP mapping
--------------------
====================================================  =======================
COBOL construct                                       HTTP equivalent
====================================================  =======================
``STARTBR`` / ``READNEXT`` over USRSEC                ``GET /users``
``WRITE FILE('USRSEC')`` + DFHRESP(DUPKEY) check      ``POST /users``
``READ UPDATE`` + conditional MOVE + ``REWRITE``      ``PUT /users/{id}``
``READ`` + ``DELETE FILE('USRSEC')``                  ``DELETE /users/{id}``
BCrypt / clear-text password handling                 all mutating endpoints
====================================================  =======================

Security note
-------------
Password values in request payloads are never logged. The service
layer hashes passwords using BCrypt before persisting (AAP §0.7.2),
mirroring the security-upgrade-in-place pattern.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.api.services.user_service` — business logic
* :mod:`src.shared.schemas.user_schema` — request/response contracts
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Path, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.dependencies import (
    CurrentUser,
    get_current_admin_user,
    get_db,
)
from src.api.services.user_service import (
    UserIdAlreadyExistsError,
    UserNotFoundError,
    UserService,
    UserServiceError,
    UserValidationError,
)
from src.shared.schemas.user_schema import (
    UserCreateRequest,
    UserCreateResponse,
    UserDeleteResponse,
    UserListRequest,
    UserListResponse,
    UserUpdateRequest,
    UserUpdateResponse,
)

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter()


# ----------------------------------------------------------------------------
# Path-parameter regex
#
# Users are keyed by an 8-character COBOL PIC X(08) SEC-USR-ID
# field. COBOL allows any X display character here; we accept
# alphanumeric + underscore / hyphen (a reasonable superset for
# user-id semantics) and defer case-sensitive comparison to the
# database. Minimum length is 1 so partial matches aren't rejected.
# ----------------------------------------------------------------------------
_USER_ID_REGEX: str = r"^[A-Za-z0-9_\-]{1,8}$"


@router.get(
    "",
    response_model=UserListResponse,
    status_code=status.HTTP_200_OK,
    summary="User list — paginated, admin-only (F-018 COUSR00C.cbl)",
    response_description=(
        "Up to 10 user rows per page (matching the original COUSR00 BMS layout) with total_count, page, and page_size."
    ),
)
async def list_users(
    user_id: str | None = Query(
        default=None,
        max_length=8,
        description=("Optional 8-character user-ID filter (prefix match). Maps to COUSR00 USRIDINI PIC X(08)."),
    ),
    page: int = Query(
        default=1,
        ge=1,
        description=("1-based page number (defaults to 1). Maps to COUSR00 PAGENUMI PIC X(08)."),
    ),
    page_size: int = Query(
        default=10,
        ge=1,
        le=100,
        description=("Rows per page (defaults to 10 — matches the 10-repeated-row COUSR00 BMS layout)."),
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> UserListResponse:
    """Return a paginated window of user rows (admin-only)."""
    logger.info(
        "GET /users initiated",
        extra={
            "admin_user": current_user.user_id,
            "user_id_filter": user_id,
            "page": page,
            "page_size": page_size,
            "endpoint": "user_list",
        },
    )
    request = UserListRequest(
        user_id=user_id,
        page=page,
        page_size=page_size,
    )
    service = UserService(db)
    try:
        return await service.list_users(request)
    except UserServiceError as exc:
        # Generic service error (e.g. DB lookup failure with
        # MSG_UNABLE_TO_LOOKUP). Translate to 500 — it's an
        # infrastructure failure, not a client-side issue.
        logger.error(
            "User list failure: %s",
            exc,
            extra={"admin_user": current_user.user_id},
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.post(
    "",
    response_model=UserCreateResponse,
    status_code=status.HTTP_201_CREATED,
    summary="User add with BCrypt password hashing (F-019 COUSR01C.cbl)",
    response_description=(
        "Echoed user metadata (password omitted) confirming the "
        "insert. 409 Conflict returned when user_id already exists."
    ),
)
async def create_user(
    request: UserCreateRequest,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> UserCreateResponse:
    """Create a new user row (admin-only).

    The service layer hashes the cleartext password via BCrypt
    (:mod:`passlib`) before persisting — the legacy COBOL field
    ``SEC-USR-PWD PIC X(08)`` is preserved in length on the wire
    contract but is never stored in cleartext in the target database.
    """
    logger.info(
        "POST /users initiated",
        extra={
            "admin_user": current_user.user_id,
            "new_user_id": request.user_id,
            "new_user_type": request.user_type,
            "endpoint": "user_create",
        },
    )
    service = UserService(db)
    try:
        return await service.create_user(request)
    except UserIdAlreadyExistsError as exc:
        # COBOL DFHRESP(DUPKEY) / DFHRESP(DUPREC) -> HTTP 409
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(exc),
        ) from exc
    except UserValidationError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        ) from exc
    except UserServiceError as exc:
        logger.error(
            "User create failure: %s",
            exc,
            extra={"admin_user": current_user.user_id},
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.put(
    "/{user_id}",
    response_model=UserUpdateResponse,
    status_code=status.HTTP_200_OK,
    summary="User update — PATCH-style partial (F-020 COUSR02C.cbl)",
    response_description=(
        "Updated user record (password omitted). 404 if not found; 400 when the patch is empty or all fields unchanged."
    ),
)
async def update_user(
    request: UserUpdateRequest,
    user_id: str = Path(
        ...,
        pattern=_USER_ID_REGEX,
        description="User ID — 1-8 alphanumeric chars (COBOL PIC X(08))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> UserUpdateResponse:
    """Apply a partial update to an existing user row (admin-only).

    Matches the COUSR02C flow: all request fields are optional; only
    non-null fields overwrite their counterparts on the existing row
    (mirroring the COBOL conditional-MOVE pattern). An all-None
    patch raises :class:`UserValidationError` -> HTTP 400.
    """
    logger.info(
        "PUT /users/%s initiated",
        user_id,
        extra={
            "admin_user": current_user.user_id,
            "target_user_id": user_id,
            "endpoint": "user_update",
        },
    )
    service = UserService(db)
    try:
        return await service.update_user(user_id, request)
    except UserNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(exc),
        ) from exc
    except UserValidationError as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(exc),
        ) from exc
    except UserServiceError as exc:
        logger.error(
            "User update failure: %s",
            exc,
            extra={"admin_user": current_user.user_id, "target_user_id": user_id},
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


@router.delete(
    "/{user_id}",
    response_model=UserDeleteResponse,
    status_code=status.HTTP_200_OK,
    summary="User delete — returns pre-DELETE snapshot (F-021 COUSR03C.cbl)",
    response_description=(
        "Echo of the deleted user's identity fields (first_name, "
        "last_name, usr_type) for visual confirmation — mirrors the "
        "COUSR03 BMS display-before-delete pattern. 404 if not found."
    ),
)
async def delete_user(
    user_id: str = Path(
        ...,
        pattern=_USER_ID_REGEX,
        description="User ID — 1-8 alphanumeric chars (COBOL PIC X(08))",
    ),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> UserDeleteResponse:
    """Delete an existing user row (admin-only).

    Returns a snapshot of the row's identity fields captured *before*
    deletion so clients can echo the deletion to the UI (mirroring
    the COUSR03 "display then delete" BMS pattern).
    """
    logger.info(
        "DELETE /users/%s initiated",
        user_id,
        extra={
            "admin_user": current_user.user_id,
            "target_user_id": user_id,
            "endpoint": "user_delete",
        },
    )
    service = UserService(db)
    try:
        return await service.delete_user(user_id)
    except UserNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(exc),
        ) from exc
    except UserServiceError as exc:
        logger.error(
            "User delete failure: %s",
            exc,
            extra={"admin_user": current_user.user_id, "target_user_id": user_id},
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=str(exc),
        ) from exc


__all__ = ["router"]
