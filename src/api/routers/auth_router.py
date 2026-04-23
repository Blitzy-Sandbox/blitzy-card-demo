# ============================================================================
# Source: app/cbl/COSGN00C.cbl  (Sign-on/authentication program, Feature F-001)
#         + app/cpy-bms/COSGN00.CPY  (BMS symbolic map)
#         + app/cpy/CSUSR01Y.cpy  (SEC-USER-DATA security record)
#         + app/cpy/COCOM01Y.cpy  (CARDDEMO-COMMAREA — user identity) —
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
"""Authentication router — HTTP transport for Feature F-001 (Sign-on).

Replaces the CICS ``COSGN00C`` sign-on/authentication program with two
stateless REST endpoints:

* ``POST /auth/login``  — exchange credentials for a JWT bearer token
  (COBOL ``READ-USER-SEC-FILE`` + ``SEC-USR-PWD`` comparison, followed
  by ``EXEC CICS XCTL PROGRAM('COADM01C' | 'COMEN01C')`` via JWT claim
  ``user_type``).
* ``POST /auth/logout`` — client-side token-discard confirmation (CICS
  had no explicit logout because pseudo-conversational sessions timed
  out implicitly; we expose one for client-code clarity).

Both endpoints are explicitly listed in ``PUBLIC_PATHS`` inside
:mod:`src.api.middleware.auth` so they bypass the JWT middleware — a
request without a token is precisely what ``/auth/login`` is for.

COBOL → HTTP mapping
--------------------
============================================  =================================
COBOL construct                               HTTP equivalent
============================================  =================================
``EXEC CICS RECEIVE MAP('COSGN0A')``          Request body (:class:`SignOnRequest`)
``EXEC CICS READ DATASET('USRSEC')``          :meth:`AuthService.authenticate`
``CDEMO-USER-ID``, ``CDEMO-USER-TYPE``        JWT claims in access_token
``SEND MAP('COSGN0A')`` on success            :class:`SignOnResponse`
``SEND MAP('COSGN0A')`` with error            :class:`HTTPException` (401)
``EXEC CICS RETURN TRANSID('CC00')``          Stateless — no TRANSID needed
============================================  =================================

Error semantics
---------------
``AuthService.authenticate`` raises :class:`AuthenticationError` for
every failure mode (user not found, wrong password, unable to verify).
The router translates all of them into HTTP 401 Unauthorized, carrying
the user-facing message that COBOL would have rendered to ``ERRMSGO``.
The global exception handler (:func:`register_exception_handlers`)
wraps the 401 in the ABEND-DATA envelope (``error_code: "AUTH"``).

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.api.services.auth_service` — business logic
* :mod:`src.shared.schemas.auth_schema` — request/response contracts
* :mod:`src.api.middleware.auth` — JWT validator middleware
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.dependencies import get_db
from src.api.services.auth_service import (
    AuthenticationError,
    AuthService,
)
from src.shared.schemas.auth_schema import (
    SignOnRequest,
    SignOnResponse,
    SignOutResponse,
)

logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# APIRouter instance — consumed by ``src/api/main.py`` via
# ``app.include_router(auth_router.router, prefix="/auth", tags=["Authentication"])``.
# Prefix is provided by the mounter so this module can remain mount-path
# agnostic (useful in tests that mount the router directly without a prefix).
# ----------------------------------------------------------------------------
router: APIRouter = APIRouter()


@router.post(
    "/login",
    response_model=SignOnResponse,
    status_code=status.HTTP_200_OK,
    summary="Sign-on — exchange credentials for a JWT token (COSGN00C equivalent)",
    response_description="JWT bearer token plus authenticated user context",
)
async def login(
    request: SignOnRequest,
    db: AsyncSession = Depends(get_db),
) -> SignOnResponse:
    """Authenticate the caller and return a JWT access token.

    Mirrors the CICS pseudo-conversational flow in ``COSGN00C.cbl``
    lines 104-256:

    1. Read ``USERIDI`` and ``PASSWDI`` from the map (request body here).
    2. Validate non-empty (:class:`SignOnRequest` ``field_validator``).
    3. UPPER-CASE both (performed inside
       :meth:`AuthService.authenticate`).
    4. ``EXEC CICS READ DATASET('USRSEC')`` keyed by user_id.
    5. Compare ``SEC-USR-PWD`` to the submitted password (BCrypt here).
    6. On success: populate COMMAREA + ``EXEC CICS XCTL`` — in the
       cloud-native flow, return the JWT (caller uses it on next call).

    Parameters
    ----------
    request:
        :class:`SignOnRequest` containing ``user_id`` and ``password``.
    db:
        Injected :class:`AsyncSession` for the USRSEC lookup. Provided
        by :func:`src.api.dependencies.get_db`.

    Returns
    -------
    SignOnResponse
        Access token + token_type ("bearer") + echoed user_id +
        user_type ("A" or "U").

    Raises
    ------
    HTTPException
        ``401 Unauthorized`` on every authentication failure mode.
        The exception's ``detail`` carries the COBOL user-facing
        message ("User not found. Try again ...", "Wrong Password. Try
        again ...", or "Unable to verify the User ...").
    """
    service = AuthService(db)
    try:
        return await service.authenticate(request)
    except AuthenticationError as exc:
        # Every authentication failure — user not found, wrong password,
        # generic "unable to verify" — maps to HTTP 401. The exception's
        # message is the COBOL user-facing string from `CSMSG01Y.cpy`
        # and is safe to surface; no internal DB/hash detail leaks.
        logger.warning(
            "Authentication failed",
            extra={"user_id": request.user_id, "reason": str(exc)},
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


@router.post(
    "/logout",
    response_model=SignOutResponse,
    status_code=status.HTTP_200_OK,
    summary="Sign-out — stateless token discard confirmation",
    response_description="Confirmation payload (stateless; client discards token)",
)
async def logout() -> SignOutResponse:
    """Confirm sign-out (public endpoint — no authentication required).

    Per AAP §0.4.3 "Stateless Authentication" design pattern, JWT tokens
    replace CICS COMMAREA session state. Logout in a stateless JWT
    architecture is a client-side operation — the client simply discards
    its token. There is no server-side session to invalidate and no
    blacklist to update. This endpoint therefore accepts no authentication
    and exists purely to provide a consistent REST confirmation envelope
    for clients that expect a 200 response on the logout action.

    This endpoint is listed in ``PUBLIC_PATHS`` (see
    ``src/api/middleware/auth.py``) so the JWT middleware does not
    enforce a ``Authorization`` header check. Allowing anonymous access
    is safe because the endpoint:

    * Mutates no server-side state (no DB writes, no cache updates).
    * Reveals no user-specific information (returns a fixed literal
      message; no identifying payload).
    * Has no authorization-sensitive side effects (no audit records
      correlated to any specific user).

    COBOL mapping
    -------------
    In CICS, the pseudo-conversational session ended implicitly either
    by ``EXEC CICS RETURN`` without a COMMAREA or by RTIMOUT on the
    transaction. There was no explicit sign-out transaction, and
    transaction termination was never gated by re-authentication. The
    cloud-native equivalent is to discard the JWT bearer token — this
    endpoint exists to provide a consistent REST confirmation envelope
    for clients that expect a 200 response on logout.

    Returns
    -------
    SignOutResponse
        Fixed confirmation message per AAP Phase 4 Step 2.
    """
    # Structured log for CloudWatch Logs Insights. The ``user_id`` is
    # deliberately not recorded here because this endpoint does not
    # require — or even attempt to parse — the Authorization header.
    # Sign-out events are purely client-side in a stateless JWT
    # architecture; a correlated audit signal (if ever required) would
    # be emitted by the client immediately before token discard.
    logger.info("Sign-out acknowledged")

    # Confirmation message per AAP §0.5.1 (auth_router.py row) and
    # AAP Phase 4 "POST /auth/logout Endpoint" Step 2.
    return SignOutResponse(message="Successfully signed out")


__all__ = ["router"]
