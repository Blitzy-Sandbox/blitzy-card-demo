# ============================================================================
# Source: app/cpy/COCOM01Y.cpy  (CARDDEMO-COMMAREA communication block)
#         app/cbl/COSGN00C.cbl  (Sign-on program / USRSEC lookup flow)
#         app/cpy/CSUSR01Y.cpy  (SEC-USER-DATA record layout)
#         → Mainframe-to-Cloud migration (AAP §0.5.1)
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
"""FastAPI dependency injection providers.

Replaces CICS COMMAREA (``app/cpy/COCOM01Y.cpy``) session state passing
with JWT-based authentication and SQLAlchemy async database session
injection.

Mainframe-to-Cloud Mapping
--------------------------
In the original z/OS CardDemo application, the CICS ``COMMAREA``
(``CARDDEMO-COMMAREA`` defined in ``app/cpy/COCOM01Y.cpy``) carried the
authenticated user's identity (``CDEMO-USER-ID`` PIC X(08)) and role
(``CDEMO-USER-TYPE`` PIC X(01), with the 88-level values ``'A'`` for
admin and ``'U'`` for user) between CICS transactions. ``COSGN00C``
(``app/cbl/COSGN00C.cbl``) populated these fields after a successful
USRSEC (``app/cpy/CSUSR01Y.cpy``) lookup, and each subsequent CICS
``XCTL`` carried the COMMAREA forward.

The cloud-native equivalent replaces COMMAREA with a stateless JWT
bearer token; this module exposes FastAPI dependencies that decode and
validate the token, exposing the authenticated identity to route
handlers in the form of a :class:`CurrentUser` dataclass.

============================================  ================================================
COBOL construct                               Python / FastAPI equivalent
============================================  ================================================
CICS COMMAREA                                 JWT bearer token (``Authorization: Bearer``)
``CDEMO-USER-ID``    PIC X(08)                JWT claim ``user_id`` (also ``sub``)
``CDEMO-USER-TYPE``  PIC X(01)                JWT claim ``user_type`` (``'A'`` or ``'U'``)
``88 CDEMO-USRTYP-ADMIN VALUE 'A'.``          :attr:`CurrentUser.is_admin`
``88 CDEMO-USRTYP-USER  VALUE 'U'.``          ``current_user.user_type == 'U'``
``EXEC CICS READ DATASET(WS-USRSEC-FILE)``    Decoded once at sign-on; stateless thereafter
CICS COMMAREA parameter passing               FastAPI ``Depends()`` injection
CICS VSAM OPEN/CLOSE pattern                  SQLAlchemy async session factory
CICS ``SYNCPOINT ROLLBACK``                   Rollback-on-exception in ``get_async_session``
``XCTL PROGRAM('COADM01C') ...`` (admin gate) 403 from :func:`get_current_admin_user`
============================================  ================================================

Public API
----------
* :class:`CurrentUser` — frozen dataclass exposing the three
  authentication-relevant COMMAREA fields.
* :data:`oauth2_scheme` — ``OAuth2PasswordBearer`` that extracts bearer
  tokens from ``Authorization`` headers.
* :func:`get_db` — dependency yielding a transactional
  :class:`~sqlalchemy.ext.asyncio.AsyncSession` bound to Aurora PostgreSQL.
* :func:`get_current_user` — decodes the JWT and returns a
  :class:`CurrentUser`; raises ``HTTPException(401)`` on failure.
* :func:`get_current_admin_user` — chained on top of
  :func:`get_current_user`; raises ``HTTPException(403)`` when the
  caller's ``user_type`` is not ``'A'``.
* :func:`get_optional_user` — non-enforcing variant that returns
  ``None`` for anonymous callers (no / invalid token).

Consistency with sibling modules
--------------------------------
* ``src/api/middleware/auth.py`` — :class:`JWTAuthMiddleware` performs
  blanket JWT authentication on every protected request and populates
  ``request.state``. This module provides ``Depends``-based access to
  the same user context inside individual route handlers.
* ``src/api/services/auth_service.py`` — canonical JWT issuer (line
  617-632 writes both ``sub`` and ``user_id`` claims). This module
  prefers ``user_id`` (matching the middleware) and falls back to
  ``sub`` defensively.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* AAP §0.7.2 — Security Requirements (JWT, BCrypt, Secrets Manager, IAM)
* ``src/api/database.py`` — :func:`~src.api.database.get_async_session`
* ``src/shared/config/settings.py`` — JWT signing parameters
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import (  # noqa: UP035  (schema-specified import sources: typing.AsyncGenerator, typing.Optional)
    Any,
    AsyncGenerator,
    Optional,
)

from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt  # type: ignore[import-untyped]
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.database import get_async_session
from src.shared.config.settings import Settings

# ----------------------------------------------------------------------------
# Module logger
#
# Structured records flow to CloudWatch Logs via the ECS awslogs driver.
# Filter by ``logger_name = "src.api.dependencies"`` in Logs Insights to
# isolate dependency-injection-level audit events (invalid tokens,
# missing claims, 403s on non-admin access, etc.). Sensitive values —
# the token string, the JWT signing key, and the decoded password —
# are NEVER written to the log.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# Public API — explicit re-export list
#
# Only the six symbols required by the AAP export schema are part of
# the public contract. ``_decode_jwt_to_current_user`` and the
# ``_oauth2_scheme_optional`` instance are internal implementation
# details and remain private (leading underscore).
# ----------------------------------------------------------------------------
__all__ = [
    "CurrentUser",
    "get_current_admin_user",
    "get_current_user",
    "get_db",
    "get_optional_user",
    "oauth2_scheme",
]


# ----------------------------------------------------------------------------
# User type sentinel values — map to the COCOM01Y.cpy 88-level domain
#
#   88 CDEMO-USRTYP-ADMIN  VALUE 'A'.
#   88 CDEMO-USRTYP-USER   VALUE 'U'.
#
# The exact single-character values ('A' / 'U') MUST be preserved. The
# UserSecurity table (→ ``usr_type`` column) stores these values
# verbatim, every batch job (AAP §0.5.1) reads them as strings, and
# ``src/api/middleware/auth.py`` enforces the same domain. Any
# divergence between these constants and the JWT issuer in
# ``src/api/services/auth_service.py`` would silently break admin
# authorization across the application.
# ----------------------------------------------------------------------------
_USER_TYPE_ADMIN: str = "A"
_USER_TYPE_USER: str = "U"
_VALID_USER_TYPES: frozenset[str] = frozenset({_USER_TYPE_ADMIN, _USER_TYPE_USER})


# ----------------------------------------------------------------------------
# JWT claim keys
#
# ``src/api/services/auth_service.py`` (line 617-632) issues every JWT
# with BOTH the JWT-standard ``sub`` claim AND a ``user_id`` claim, both
# carrying ``CDEMO-USER-ID``. ``src/api/middleware/auth.py`` reads
# ``user_id``. This module follows the middleware's lead: we prefer
# ``user_id`` and fall back to ``sub`` for defense-in-depth against
# future issuer changes.
# ----------------------------------------------------------------------------
_JWT_CLAIM_USER_ID: str = "user_id"
_JWT_CLAIM_USER_ID_FALLBACK: str = "sub"
_JWT_CLAIM_USER_TYPE: str = "user_type"


# ----------------------------------------------------------------------------
# OAuth2 scheme — enforcing
#
# ``tokenUrl="/auth/login"`` points at the sign-on endpoint in
# ``src/api/routers/auth_router.py`` (the REST replacement for
# ``app/cbl/COSGN00C.cbl``). With ``auto_error=True`` (the default),
# FastAPI raises ``HTTPException(401)`` BEFORE any handler or the
# downstream :func:`get_current_user` is invoked when the
# ``Authorization`` header is absent.
#
# The ``scheme_name`` and ``description`` are surfaced in the OpenAPI
# document (and therefore Swagger UI / ReDoc) so that engineers
# consuming the API see a human-readable explanation of the auth model.
# ----------------------------------------------------------------------------
oauth2_scheme: OAuth2PasswordBearer = OAuth2PasswordBearer(
    tokenUrl="/auth/login",
    auto_error=True,
    scheme_name="JWT",
    description="JWT bearer token obtained from POST /auth/login (COSGN00C.cbl equivalent)",
)


# ----------------------------------------------------------------------------
# OAuth2 scheme — non-enforcing
#
# Private counterpart of :data:`oauth2_scheme` with ``auto_error=False``.
# Returns ``None`` (instead of raising 401) when the Authorization
# header is absent, making it suitable for endpoints that treat
# anonymous access as valid (see :func:`get_optional_user`).
# ----------------------------------------------------------------------------
_oauth2_scheme_optional: OAuth2PasswordBearer = OAuth2PasswordBearer(
    tokenUrl="/auth/login",
    auto_error=False,
    scheme_name="JWT-Optional",
    description="Optional JWT bearer token; None for anonymous callers",
)


# ============================================================================
# CurrentUser — authenticated user DTO (replaces CICS COMMAREA auth fields)
# ============================================================================
@dataclass(frozen=True)
class CurrentUser:
    """Authenticated user context extracted from JWT claims.

    Immutable (``frozen=True``) data transfer object carrying the three
    authentication-relevant fields of ``app/cpy/COCOM01Y.cpy`` that CICS
    previously propagated between programs via COMMAREA.

    Attributes
    ----------
    user_id : str
        Authenticated user's identifier — exactly 1 to 8 characters.
        Maps to ``CDEMO-USER-ID`` PIC X(08) in ``COCOM01Y.cpy`` and to
        ``SEC-USR-ID`` PIC X(08) in ``CSUSR01Y.cpy``. Populated from
        the JWT ``user_id`` claim (fallback ``sub``).
    user_type : str
        User role — exactly ``'A'`` (admin) or ``'U'`` (user). Maps to
        ``CDEMO-USER-TYPE`` PIC X(01) in ``COCOM01Y.cpy`` and to
        ``SEC-USR-TYPE`` PIC X(01) in ``CSUSR01Y.cpy``.
    is_admin : bool
        Pre-computed ``True`` iff ``user_type == 'A'``. Maps to the
        ``88 CDEMO-USRTYP-ADMIN VALUE 'A'.`` condition name in the COBOL
        source. The boolean form lets route handlers write
        ``if current_user.is_admin`` without re-testing the string,
        avoiding the subtle bug where a lowercased or padded
        ``user_type`` (e.g., ``'a'`` or ``'A '``) silently fails the
        comparison.

    Notes
    -----
    This class intentionally omits the navigation / breadcrumb fields
    from ``COCOM01Y.cpy`` (``CDEMO-CUST-ID``, ``CDEMO-ACCT-ID``,
    ``CDEMO-CARD-NUM``, ``CDEMO-LAST-MAP``, ``CDEMO-LAST-MAPSET``,
    ``CDEMO-PGM-CONTEXT``, ``CDEMO-FROM-TRANID``, ``CDEMO-TO-TRANID``,
    etc.). Those were conversational state specific to the CICS
    transaction model; in a stateless REST/GraphQL API they are
    request-scoped parameters carried in the URL path, query string, or
    request body — never cached in session state.

    Examples
    --------
    >>> user = CurrentUser(user_id="ADMIN001", user_type="A", is_admin=True)
    >>> user.is_admin
    True
    >>> user.user_id
    'ADMIN001'
    """

    user_id: str
    user_type: str
    is_admin: bool


# ============================================================================
# Database session dependency — replaces CICS VSAM file OPEN/CLOSE
# ============================================================================
async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """Yield a transactional :class:`AsyncSession` for FastAPI handlers.

    Thin adapter over :func:`src.api.database.get_async_session` that
    wraps the lower-level async generator in a FastAPI-friendly form.
    All transactional semantics (commit-on-clean-exit, rollback-on-
    exception) are implemented by ``get_async_session`` itself — see
    ``src/api/database.py`` lines 466+ for the authoritative definition.

    This dependency replaces the CICS VSAM ``OPEN``/``CLOSE`` pattern
    defined by JCL provisioning members such as ``app/jcl/ACCTFILE.jcl``
    and ``app/jcl/CARDFILE.jcl``. The rollback-on-exception behavior
    substitutes for the CICS ``SYNCPOINT ROLLBACK`` relied upon by
    ``COACTUPC.cbl`` (Account Update — F-005) and ``COBIL00C.cbl``
    (Bill Payment — F-012).

    Yields
    ------
    AsyncSession
        An open SQLAlchemy async session bound to Aurora PostgreSQL.
        The session is automatically committed when the yielded block
        returns cleanly and rolled back on any exception propagating
        out of the block. Its underlying connection is always returned
        to the engine pool.

    Raises
    ------
    RuntimeError
        If :func:`~src.api.database.init_db` has not been called before
        the first request. Propagated from ``get_async_session``.

    Examples
    --------
    Inside a FastAPI router::

        from fastapi import Depends
        from sqlalchemy.ext.asyncio import AsyncSession
        from src.api.dependencies import get_db

        @router.get("/accounts/{account_id}")
        async def read_account(
            account_id: int,
            db: AsyncSession = Depends(get_db),
        ) -> AccountResponse:
            ...
    """
    async for session in get_async_session():
        yield session


# ============================================================================
# Internal helper — JWT decoding
#
# Shared between :func:`get_current_user` (which raises on failure)
# and :func:`get_optional_user` (which swallows failures). Keeping the
# decode path in a single function guarantees identical validation
# semantics across both public dependencies.
# ============================================================================
def _decode_jwt_to_current_user(token: str) -> CurrentUser:
    """Decode a JWT bearer token into a :class:`CurrentUser`.

    Parameters
    ----------
    token : str
        Compact-serialized JWT string as extracted by
        :class:`OAuth2PasswordBearer`.

    Returns
    -------
    CurrentUser
        Populated user context if every claim validation passes.

    Raises
    ------
    HTTPException
        Status 401 (``Unauthorized``) for any validation failure:
        signature mismatch, expired ``exp`` claim, missing required
        claims (``user_id``/``sub`` or ``user_type``), or a
        ``user_type`` value outside ``{'A', 'U'}``.
    """
    # Defensive 401 factory — reused for every failure branch so that
    # an attacker probing for valid tokens cannot distinguish between
    # the possible rejection reasons via the HTTP response. The log
    # record captures the specific reason for operational visibility.
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid or expired token",
        headers={"WWW-Authenticate": "Bearer"},
    )

    # ------------------------------------------------------------------
    # Step 1 — Load JWT signing parameters.
    #
    # Pydantic BaseSettings caches env lookups; instantiating Settings()
    # is inexpensive. The settings object is NOT cached at module scope
    # because doing so would force a synchronous env-var read at import
    # time, which fails for test environments that set JWT_SECRET_KEY
    # lazily (e.g., inside pytest fixtures).
    # ------------------------------------------------------------------
    settings: Settings = Settings()

    # ------------------------------------------------------------------
    # Step 2 — Cryptographic verification + expiration check.
    #
    # jose.jwt.decode raises JWTError (or a subclass such as
    # ExpiredSignatureError) on ANY failure: bad signature, malformed
    # header / payload, expired ``exp``, or signature-algorithm
    # mismatch. Any of these conditions means the token is
    # untrustworthy and the request must be rejected.
    # ------------------------------------------------------------------
    try:
        payload: dict[str, Any] = jwt.decode(
            token,
            settings.JWT_SECRET_KEY,
            algorithms=[settings.JWT_ALGORITHM],
        )
    except JWTError as exc:
        logger.warning(
            "JWT verification failed",
            extra={"error_type": type(exc).__name__},
        )
        raise credentials_exception from exc

    # ------------------------------------------------------------------
    # Step 3 — Required-claim extraction.
    #
    # Prefer ``user_id`` (matching src/api/middleware/auth.py and the
    # dual-claim convention in src/api/services/auth_service.py lines
    # 617-632) and fall back to ``sub`` (JWT RFC 7519 §4.1.2 subject
    # claim) if ``user_id`` is absent. Either populates CDEMO-USER-ID.
    # ------------------------------------------------------------------
    user_id_claim: Any = payload.get(_JWT_CLAIM_USER_ID)
    if user_id_claim is None:
        user_id_claim = payload.get(_JWT_CLAIM_USER_ID_FALLBACK)
    user_type_claim: Any = payload.get(_JWT_CLAIM_USER_TYPE)

    if not user_id_claim or not user_type_claim:
        logger.warning(
            "JWT missing required claims (user_id/sub and/or user_type)",
            extra={"present_claims": sorted(payload.keys())},
        )
        raise credentials_exception

    # Coerce claims to strings defensively — jose returns whatever JSON
    # encoded, but both CDEMO-USER-ID PIC X(08) and CDEMO-USER-TYPE
    # PIC X(01) are strings in the original COBOL layout. Downstream
    # code may call .strip(), .upper(), etc. without type-guards.
    user_id: str = str(user_id_claim)
    user_type: str = str(user_type_claim)

    # ------------------------------------------------------------------
    # Step 4 — Domain validation for user_type.
    #
    # COCOM01Y.cpy defines exactly two valid values via the 88-level
    # condition names:
    #   88 CDEMO-USRTYP-ADMIN VALUE 'A'.
    #   88 CDEMO-USRTYP-USER  VALUE 'U'.
    # Any other value implies a tampered or malformed token and must
    # be rejected with 401 (not 403) — the token itself is invalid.
    # ------------------------------------------------------------------
    if user_type not in _VALID_USER_TYPES:
        logger.warning(
            "JWT user_type claim outside COCOM01Y.cpy 88-level domain",
            extra={"user_id": user_id, "user_type": user_type},
        )
        raise credentials_exception

    return CurrentUser(
        user_id=user_id,
        user_type=user_type,
        is_admin=(user_type == _USER_TYPE_ADMIN),
    )


# ============================================================================
# Authenticated user dependency — replaces CICS COMMAREA read on XCTL
# ============================================================================
async def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> CurrentUser:
    """Return the authenticated :class:`CurrentUser` for the request.

    Replaces the CICS COMMAREA session-state transfer between online
    COBOL programs. The JWT ``user_id`` and ``user_type`` claims
    replicate the ``CDEMO-USER-ID`` and ``CDEMO-USER-TYPE`` fields that
    CICS previously propagated via COMMAREA ``XCTL``.

    Parameters
    ----------
    token : str
        Bearer token extracted from the ``Authorization`` header by
        :data:`oauth2_scheme`. FastAPI raises ``HTTPException(401)``
        before invoking this function if the header is missing.
    db : AsyncSession
        Async database session, injected per the AAP §0.5.1 task
        specification for :func:`get_current_user`. This parameter
        is intentionally kept in the signature as the committed
        extension point for USRSEC revalidation, even though the
        current implementation does NOT consume it (``del db``
        below, line 511). The current body uses only the stateless
        JWT claims because that faithfully mirrors ``COSGN00C.cbl``'s
        COBOL behavior — the original program reads the USRSEC VSAM
        cluster exactly once at sign-on (``EXEC CICS READ FILE
        ('USRSEC')`` at COSGN00C lines 192-204) and never re-reads
        it for subsequent transactions; CICS propagates the
        authenticated identity via COMMAREA, which the JWT now
        replaces.

        The parameter is retained (rather than removed) so that the
        following concrete, documented enhancements can be added
        WITHOUT any breaking change to the FastAPI dependency chain
        or the router signatures that depend on this function:

        1. **USRSEC revalidation on token refresh** — when the JWT
           refresh endpoint is added (a COSGN00C-equivalent
           re-sign-on flow), this dependency will issue
           ``SELECT usr_id FROM user_security WHERE usr_id = :sub``
           to confirm the user row still exists and ``sec_usr_status``
           is active, raising HTTP 401 if the account has been
           disabled since the JWT was issued.
        2. **Admin elevation check** — for the
           :func:`get_current_admin_user` chain, a future hardening
           pass will cross-check the JWT ``user_type`` claim against
           the live ``user_security.sec_usr_type`` column to defeat
           stale-token attacks where an admin's role was revoked
           but the JWT is still within its expiry window.
        3. **Audit-log write** — a future compliance requirement may
           need every authenticated request to append an audit row;
           having the session pre-injected here avoids a second
           ``Depends(get_db)`` at every router level.

        Removing this parameter now would require touching every
        router that calls :func:`get_current_user` once the
        revalidation feature is scheduled. Keeping it here is a
        one-line DI-overhead cost (a session checkout from the pool
        that is immediately returned via ``del db``; no query is
        issued and no transaction is opened) that protects the
        forward-compatibility of every router module.

    Returns
    -------
    CurrentUser
        Populated user context.

    Raises
    ------
    HTTPException
        Status 401 for any JWT validation failure (propagated from
        :func:`_decode_jwt_to_current_user`).

    Examples
    --------
    ::

        @router.get("/accounts/{account_id}")
        async def read_account(
            account_id: int,
            current_user: CurrentUser = Depends(get_current_user),
        ) -> AccountResponse:
            # current_user.user_id, current_user.user_type, current_user.is_admin
            ...
    """
    # Explicitly acknowledge the injected session without consuming it.
    # Using ``del`` (rather than silently leaving the parameter unused)
    # is the most unambiguous Python idiom for "I see this value and
    # intentionally discard it", and it also short-circuits any IDE or
    # static-analysis hint about an unused name. The session itself
    # remains managed by FastAPI's dependency machinery and is cleanly
    # returned to the pool regardless of whether this function touches
    # it — no connection leak is introduced.
    del db
    return _decode_jwt_to_current_user(token)


# ============================================================================
# Admin-only dependency — replaces 88 CDEMO-USRTYP-ADMIN role gate
# ============================================================================
async def get_current_admin_user(
    current_user: CurrentUser = Depends(get_current_user),
) -> CurrentUser:
    """Require ``user_type == 'A'`` — admin only.

    Replaces the role-dispatch at the end of ``app/cbl/COSGN00C.cbl``
    (approx. lines 230-250) where the sign-on program branches on the
    COMMAREA 88-level ``CDEMO-USRTYP-ADMIN``::

        IF CDEMO-USRTYP-ADMIN
           EXEC CICS XCTL PROGRAM('COADM01C') COMMAREA(...) END-EXEC
        ELSE
           EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(...) END-EXEC
        END-IF.

    Regular users would never reach ``COADM01C`` (the admin menu /
    ``app/cbl/COADM01C.cbl``) or its CRUD children
    (``COUSR00C``/``COUSR01C``/``COUSR02C``/``COUSR03C``). Routers that
    implement those admin programs as REST endpoints depend on this
    function to enforce the same gate.

    Parameters
    ----------
    current_user : CurrentUser
        Authenticated user, injected by :func:`get_current_user`. If
        the underlying JWT is invalid, ``HTTPException(401)`` is
        already raised by the upstream dependency before this function
        is called.

    Returns
    -------
    CurrentUser
        The unmodified :class:`CurrentUser` instance (never ``None``),
        enabling the admin route to access the identity without having
        to declare a second :func:`get_current_user` dependency.

    Raises
    ------
    HTTPException
        Status 403 (``Forbidden``) if ``current_user.is_admin`` is
        ``False``. Status 401 is propagated unchanged from the
        upstream :func:`get_current_user` dependency.

    Examples
    --------
    ::

        @admin_router.post("/users", status_code=201)
        async def create_user(
            payload: UserCreate,
            admin: CurrentUser = Depends(get_current_admin_user),
            db: AsyncSession = Depends(get_db),
        ) -> UserResponse:
            ...
    """
    if not current_user.is_admin:
        # Log with structured fields for CloudWatch Logs Insights
        # queries. Use ``extra`` (not f-string interpolation) so the
        # fields are indexed individually — ``user_id`` is especially
        # useful for detecting brute-force privilege-escalation
        # attempts.
        logger.warning(
            "Non-admin user attempted admin-only action",
            extra={
                "user_id": current_user.user_id,
                "user_type": current_user.user_type,
            },
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin privileges required",
        )
    return current_user


# ============================================================================
# Optional dependency — returns None for anonymous callers
# ============================================================================
async def get_optional_user(
    token: Optional[str] = Depends(_oauth2_scheme_optional),  # noqa: UP045  (schema-specified typing.Optional)
) -> Optional[CurrentUser]:  # noqa: UP045  (schema-specified typing.Optional)
    """Return :class:`CurrentUser` if authenticated, ``None`` otherwise.

    Non-enforcing variant of :func:`get_current_user` for endpoints
    whose behavior varies based on whether the caller is authenticated
    — for example, a public informational endpoint that surfaces
    additional detail to signed-in users. The CICS equivalent would be
    a transaction that inspects ``CDEMO-USER-ID`` without failing when
    it is spaces.

    Parameters
    ----------
    token : Optional[str]
        Bearer token or ``None`` when the ``Authorization`` header is
        absent. Extracted by the private
        :data:`_oauth2_scheme_optional` scheme (``auto_error=False``).

    Returns
    -------
    Optional[CurrentUser]
        Populated :class:`CurrentUser` if a valid token is supplied;
        ``None`` for anonymous callers OR for tokens that fail
        validation. The no-token and invalid-token cases are
        DELIBERATELY COLLAPSED — the endpoint opted into optional
        authentication, and distinguishing between "no token" and
        "bad token" would force every caller to handle two flavors of
        anonymous access, which has no functional value.

    Examples
    --------
    ::

        @router.get("/public/status")
        async def public_status(
            user: Optional[CurrentUser] = Depends(get_optional_user),
        ) -> StatusResponse:
            if user and user.is_admin:
                # surface admin-only fields
                ...
    """
    if token is None:
        # Anonymous call — the Authorization header was absent and
        # ``auto_error=False`` suppressed the automatic 401.
        return None

    try:
        return _decode_jwt_to_current_user(token)
    except HTTPException:
        # Invalid / expired token on an optional endpoint is treated
        # as anonymous access. The warning log inside
        # :func:`_decode_jwt_to_current_user` already records the
        # specific failure reason for operational visibility; we do
        # not log again here to avoid duplicate records.
        return None
