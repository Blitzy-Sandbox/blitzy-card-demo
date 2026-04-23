# ============================================================================
# Source: app/cbl/COSGN00C.cbl (Sign-on/authentication program, Feature F-001)
#         + app/cpy/COCOM01Y.cpy (CARDDEMO-COMMAREA communication block)
#         + app/cpy/CSUSR01Y.cpy (SEC-USER-DATA security record layout) —
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
"""JWT validation middleware for CardDemo API.

Converted from ``app/cbl/COSGN00C.cbl`` (Sign-on/authentication program,
Feature F-001) and ``app/cpy/COCOM01Y.cpy`` (COMMAREA communication
block). Replaces CICS pseudo-conversational session management with
stateless JWT verification performed on every protected HTTP request.

Mainframe → Cloud Mapping
-------------------------
The original CICS flow from ``COSGN00C.cbl`` is:

1. Program checks ``EIBCALEN`` (COMMAREA length) — if zero, it's the
   first entry (no session yet); the sign-on screen is displayed.
2. On ``DFHENTER`` (user pressed Enter), ``PROCESS-ENTER-KEY`` is
   invoked: ``USERIDI`` and ``PASSWDI`` are read from the BMS map,
   whitespace-validated, upper-cased, and stored in ``WS-USER-ID`` /
   ``WS-USER-PWD`` plus ``CDEMO-USER-ID`` in COMMAREA.
3. ``READ-USER-SEC-FILE`` performs ``EXEC CICS READ DATASET('USRSEC')``
   to retrieve ``SEC-USER-DATA`` (CSUSR01Y.cpy). On success it compares
   ``SEC-USR-PWD`` to ``WS-USER-PWD`` and, on match, populates COMMAREA
   fields (``CDEMO-FROM-TRANID``, ``CDEMO-FROM-PROGRAM``,
   ``CDEMO-USER-ID``, ``CDEMO-USER-TYPE`` from ``SEC-USR-TYPE``) and
   ``EXEC CICS XCTL`` transfers to either ``COADM01C`` (admin) or
   ``COMEN01C`` (regular user) based on ``88 CDEMO-USRTYP-ADMIN VALUE
   'A'``.
4. ``EXEC CICS RETURN TRANSID('CC00') COMMAREA(CARDDEMO-COMMAREA)``
   preserves the session state for the next pseudo-conversational
   interaction.

The cloud-native equivalent splits this flow across two files:

* **``src/api/services/auth_service.py``** performs step 3 — BCrypt
  password verification and JWT issuance (the JWT payload carries
  ``user_id`` and ``user_type`` claims mirroring the COMMAREA fields).
* **This file (``src/api/middleware/auth.py``)** performs the stateless
  equivalent of step 4 on every subsequent request: the ``Authorization:
  Bearer <token>`` header is extracted, the JWT is decoded and
  verified with ``jose.jwt.decode`` against ``Settings.JWT_SECRET_KEY``
  / ``Settings.JWT_ALGORITHM``, and the resulting claims are attached to
  ``request.state`` for downstream FastAPI handlers.

COMMAREA (``COCOM01Y.cpy``) → JWT Claim Mapping
-----------------------------------------------
===============================  ============================  ==============
COBOL field                      PIC                           JWT claim
===============================  ============================  ==============
``CDEMO-USER-ID``                ``X(08)``                     ``user_id``
``CDEMO-USER-TYPE``              ``X(01)``                     ``user_type``
                                 (``'A'``=admin, ``'U'``=user) (same value)
``CDEMO-FROM-TRANID``            ``X(04)``                     (replaced by
                                                               API routing)
``CDEMO-FROM-PROGRAM``           ``X(08)``                     (replaced by
                                                               API routing)
``CDEMO-PGM-CONTEXT``            ``9(01)``                     (stateless)
``CDEMO-CUST-ID``                ``9(09)``                     (per-request)
``CDEMO-ACCT-ID``                ``9(11)``                     (per-request)
``CDEMO-CARD-NUM``               ``9(16)``                     (per-request)
===============================  ============================  ==============

Security Properties
-------------------
* **No plaintext secrets** — the JWT is signed with a symmetric HMAC key
  (HS256 by default, see ``Settings.JWT_ALGORITHM``). The signing key is
  sourced from AWS Secrets Manager in production (``Settings.JWT_SECRET_KEY``)
  and is never logged.
* **Fail-closed** — any failure during token decoding (malformed token,
  invalid signature, expired, missing required claim) returns HTTP 401
  and the request is short-circuited before reaching route handlers.
* **No sensitive data in logs** — successful and failed authentication
  events are logged with ``user_id``, ``path``, ``method``, but **never**
  the JWT token, password, or signature bytes.
* **Admin routing** — the ``88 CDEMO-USRTYP-ADMIN VALUE 'A'`` check in
  ``COSGN00C.cbl`` lines 230-239 (which selects ``XCTL`` to
  ``COADM01C``) is replicated here as a path-prefix check against
  ``ADMIN_ONLY_PREFIXES``; non-admin users receive HTTP 403.

See Also
--------
AAP §0.5.1 — File-by-File Transformation Plan (this file's row)
AAP §0.6.1 — Key Public Packages (python-jose, fastapi, starlette)
AAP §0.7.2 — Security Requirements (JWT, Secrets Manager, IAM)
``src/shared/config/settings.py`` — Settings.JWT_SECRET_KEY, JWT_ALGORITHM
``src/api/services/auth_service.py`` — issues the JWT this module validates
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

from fastapi import HTTPException, Request, status
from jose import JWTError, jwt
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import Response

from src.api.middleware.error_handler import build_abend_response
from src.shared.config.settings import Settings

# ----------------------------------------------------------------------------
# Module-level structured logger.
#
# All authentication events emit a structured `extra` dict so that the
# CloudWatch awslogs driver (attached to the ECS Fargate task definition)
# can index fields like `user_id`, `path`, and `method` as searchable
# attributes. The module name `src.api.middleware.auth` is the natural
# log-source identifier.
#
# CRITICAL: The logger MUST NEVER receive a JWT token, a password, or
# raw `Authorization` header bytes as a field. Helper functions below
# (`_extract_bearer_token`, `decode_jwt_token`) extract only non-secret
# claims before anything is logged.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Phase 2 — Public and admin path constants
# ============================================================================

#: Paths that do NOT require authentication.
#:
#: These mirror the ``EIBCALEN = 0`` branch of ``COSGN00C.cbl`` line 80,
#: where the program detects "first entry" (no COMMAREA / no session yet)
#: and sends the sign-on screen without requiring prior credentials:
#:
#: * ``/auth/login``   — the sign-on endpoint (equivalent to COSGN00C's
#:                       ``SEND-SIGNON-SCREEN`` paragraph on initial entry).
#: * ``/auth/logout``  — the sign-out endpoint. JWT is stateless — the
#:                       server holds no session state to invalidate. Sign-
#:                       out is purely a client-side token discard; this
#:                       endpoint exists to provide a consistent REST
#:                       confirmation envelope (200 OK + "Successfully
#:                       signed out") for clients. In CICS the equivalent
#:                       session termination happened implicitly via
#:                       ``EXEC CICS RETURN`` without a COMMAREA or RTIMOUT
#:                       on the transaction — there was never an explicit
#:                       sign-out transaction requiring authentication.
#: * ``/health``       — liveness probe used by the ECS Fargate task
#:                       (target group health checks) and Kubernetes-
#:                       style readiness probes.
#: * ``/docs``         — FastAPI's interactive Swagger UI (only served
#:                       when ``Settings.DEBUG`` is True, but listed
#:                       here so it's accessible in any environment).
#: * ``/redoc``        — FastAPI's ReDoc API documentation renderer.
#: * ``/openapi.json`` — the OpenAPI schema used by both Swagger UI and
#:                       ReDoc; must be publicly readable so that the
#:                       docs endpoints can load it.
#: * ``/``             — root path (used by some health-check proxies).
#:
#: **GraphQL endpoint (``/graphql``) authentication strategy**
#:
#: The ``/graphql`` endpoint is deliberately *not* in this set. The
#: ``JWTAuthMiddleware`` therefore requires a valid
#: ``Authorization: Bearer <jwt>`` header for every GraphQL POST —
#: matching the CICS model where every transaction that invokes a
#: program (CT00, CM00, CA00, etc.) must present a valid COMMAREA
#: session. Schema introspection queries (``__schema``, ``__type``)
#: are covered by this same middleware-level check; any public
#: introspection, if ever required, is an explicit future design
#: decision that would add ``/graphql`` to ``PUBLIC_PATHS`` and
#: delegate authorization to per-resolver guards inside the
#: Strawberry schema. This matches AAP §0.7.2 "IAM roles (no access
#: keys)" for the API boundary and preserves the COSGN00C.cbl
#: security posture that *all* business transactions require
#: authentication. (Code Review Finding INFO / `S2` — documented
#: strategy for CP5 GraphQL integration.)
PUBLIC_PATHS: set[str] = {
    "/",
    "/auth/login",
    "/auth/logout",
    "/health",
    "/docs",
    "/redoc",
    "/openapi.json",
}

#: Path prefixes that accept *any* authenticated user OR no authentication
#: (documentation browsing assets, favicon, static). These are matched
#: via ``path.startswith(prefix)`` rather than exact equality — which is
#: why they are stored separately from ``PUBLIC_PATHS``.
#:
#: The ``/docs`` prefix covers the static assets that Swagger UI fetches
#: (``/docs/oauth2-redirect``); ``/static`` covers any served asset
#: bundle; ``/openapi`` covers both ``/openapi.json`` and any future
#: alternative renderers (e.g., ``/openapi.yaml``).
_PUBLIC_PREFIXES: tuple[str, ...] = (
    "/docs",
    "/redoc",
    "/openapi",
    "/static",
)

#: Path prefixes restricted to administrator users (``user_type == 'A'``).
#:
#: This mirrors ``COSGN00C.cbl`` lines 230-239 where
#: ``IF CDEMO-USRTYP-ADMIN`` (88-level, VALUE 'A') issues
#: ``EXEC CICS XCTL PROGRAM('COADM01C')`` to transfer control to the
#: admin menu. Regular users (88 CDEMO-USRTYP-USER VALUE 'U') are
#: routed to ``COMEN01C`` instead and never reach the ``COADM01C``
#: admin-only menu. The Python equivalent enforces this via an HTTP
#: 403 Forbidden response for non-admin users attempting to hit any
#: path under the listed prefixes.
#:
#: * ``/admin``  — Administrative endpoints (COADM01C.cbl admin menu
#:                 plus the user-management endpoints COUSR00C-COUSR03C
#:                 from the admin router).
#: * ``/users``  — User CRUD endpoints (COUSR00C-COUSR03C); admin-only
#:                 because the original CICS admin menu (COADM01C) was
#:                 the only entry point to these programs.
ADMIN_ONLY_PREFIXES: set[str] = {
    "/admin",
    "/users",
}


# ============================================================================
# Phase 5 — Private helper functions
# ============================================================================


def _extract_bearer_token(authorization: str | None) -> str | None:
    """Extract the raw JWT from a standard ``Authorization: Bearer …`` header.

    Replaces the ``EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')``
    pattern in ``COSGN00C.cbl`` lines 110-115 that read the user's
    credentials from the BMS sign-on screen. In the cloud-native
    architecture, the client's HTTP layer performs credential
    presentation by sending a previously-issued JWT in the
    ``Authorization`` header, and this function is the first step in
    unpacking it.

    Parameters
    ----------
    authorization:
        Raw ``Authorization`` header value, or ``None`` if the client
        omitted the header entirely.

    Returns
    -------
    str | None
        The opaque JWT string (everything after the ``Bearer `` prefix)
        when the header is well-formed, or ``None`` when the header is
        absent, empty, whitespace-only, missing the ``Bearer`` scheme,
        or trails no token after the scheme. The ``None`` return is
        treated by callers as equivalent to ``USERIDI = SPACES`` in
        COBOL — an explicit authentication failure.

    Notes
    -----
    Per RFC 7235 §2.1 the challenge scheme is case-insensitive
    (``Bearer``, ``BEARER``, ``bearer`` are all valid). We uppercase
    only the scheme portion for comparison; the opaque token itself
    (which is Base64URL-encoded per RFC 7519) is returned verbatim and
    MUST NOT be altered.
    """
    if authorization is None:
        return None

    # Strip leading/trailing whitespace but NOT internal whitespace —
    # a JWT contains two '.' separators and never any spaces, so any
    # internal whitespace signals a malformed header which we treat
    # as missing.
    trimmed = authorization.strip()
    if not trimmed:
        return None

    # RFC 7235 challenge scheme is case-insensitive; split on the first
    # space only so that the token portion (which should never contain
    # whitespace) is preserved intact.
    parts = trimmed.split(" ", 1)
    if len(parts) != 2:
        return None

    scheme, token = parts
    if scheme.lower() != "bearer":
        return None

    token = token.strip()
    if not token:
        return None

    return token


def _is_public_path(path: str) -> bool:
    """Return ``True`` when ``path`` does not require JWT authentication.

    Replaces the ``IF EIBCALEN = 0`` first-entry check in
    ``COSGN00C.cbl`` line 80 — where the COBOL program recognizes that
    no session exists yet and therefore skips the credential-validation
    flow to send the initial sign-on screen. The cloud-native
    equivalent recognizes that a small, fixed set of endpoints
    (documentation, health, login) must be reachable without a prior
    JWT so that clients can obtain one in the first place.

    Parameters
    ----------
    path:
        URL path from ``request.url.path``. FastAPI normalizes trailing
        slashes and ensures the path begins with ``/``.

    Returns
    -------
    bool
        ``True`` if the path is in :data:`PUBLIC_PATHS` exactly OR if it
        begins with one of the documented public prefixes
        (``/docs``, ``/redoc``, ``/openapi``, ``/static``). ``False``
        otherwise, meaning the middleware MUST verify a JWT before
        allowing the request to proceed.
    """
    if path in PUBLIC_PATHS:
        return True

    return any(path.startswith(prefix) for prefix in _PUBLIC_PREFIXES)


def _is_admin_only_path(path: str) -> bool:
    """Return ``True`` when ``path`` is reachable only by admin users.

    Replaces the ``IF CDEMO-USRTYP-ADMIN`` branch in ``COSGN00C.cbl``
    line 230 (88-level condition on ``CDEMO-USER-TYPE`` VALUE 'A') that
    selected ``EXEC CICS XCTL PROGRAM('COADM01C')`` over the regular
    user's ``EXEC CICS XCTL PROGRAM('COMEN01C')``. Regular users never
    reached the COADM01C admin menu; the Python equivalent enforces
    the same restriction by returning HTTP 403 Forbidden for non-admin
    users attempting to hit any path under the listed prefixes.

    Parameters
    ----------
    path:
        URL path from ``request.url.path``.

    Returns
    -------
    bool
        ``True`` if ``path`` starts with any prefix in
        :data:`ADMIN_ONLY_PREFIXES`; ``False`` otherwise.
    """
    return any(path.startswith(prefix) for prefix in ADMIN_ONLY_PREFIXES)


# ============================================================================
# Phase 3 — JWT token decoding
# ============================================================================


def decode_jwt_token(token: str, secret_key: str, algorithm: str) -> dict[str, Any]:
    """Verify and decode a JWT access token, returning its claims.

    Replaces ``READ-USER-SEC-FILE`` in ``COSGN00C.cbl`` lines 209-257 —
    the ``EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
    RIDFLD(WS-USER-ID)`` that fetched the user record from VSAM, plus
    the subsequent password comparison and COMMAREA population (lines
    223-228). In the stateless cloud-native flow the VSAM read is
    replaced by JWT signature verification: the signing secret acts as
    a proof-of-issuance credential, and the claims carry the same user
    identity fields (``CDEMO-USER-ID``, ``CDEMO-USER-TYPE``) that the
    COBOL program previously stored in COMMAREA.

    The function performs three checks (any one fails → HTTP 401):

    1. **Signature verification** — ``jose.jwt.decode`` re-computes the
       HMAC-SHA256 signature over the JWT header + payload using
       ``secret_key`` and compares against the signature in the token.
    2. **Expiration check** — the ``exp`` claim is validated in two
       layers: (a) when an ``exp`` claim IS present, ``jose.jwt.decode``
       automatically raises ``ExpiredSignatureError`` (a subclass of
       ``JWTError``) if the timestamp has elapsed; (b) when an ``exp``
       claim is ABSENT, the explicit required-claim check below rejects
       the token. This two-layer defense is required because
       ``jose.jwt.decode`` silently accepts tokens without ``exp`` —
       which would allow forged non-expiring tokens if the signing key
       were ever disclosed (CWE-613: Insufficient Session Expiration).
       Addresses QA Checkpoint 6 Finding #3 (MAJOR).
    3. **Required-claim presence** — the returned payload must contain
       ``user_id`` (maps to ``CDEMO-USER-ID`` PIC X(08)), ``user_type``
       (maps to ``CDEMO-USER-TYPE`` PIC X(01), with values ``'A'`` for
       admin / ``'U'`` for regular user), AND ``exp`` (POSIX timestamp
       of token expiration, per RFC 7519 §4.1.4).

    Parameters
    ----------
    token:
        The opaque JWT string extracted from the ``Authorization``
        header by :func:`_extract_bearer_token`.
    secret_key:
        HMAC signing key. Sourced from
        :attr:`Settings.JWT_SECRET_KEY`, which is populated from AWS
        Secrets Manager in production.
    algorithm:
        JWT signing algorithm. Typically ``"HS256"`` — sourced from
        :attr:`Settings.JWT_ALGORITHM`.

    Returns
    -------
    dict[str, Any]
        The decoded claims dictionary. Guaranteed to contain:

        * ``user_id`` (str) — maps to ``CDEMO-USER-ID`` PIC X(08).
        * ``user_type`` (str) — maps to ``CDEMO-USER-TYPE`` PIC X(01)
          with values preserved exactly from COBOL (``'A'`` or ``'U'``).
        * ``exp`` (int) — POSIX timestamp of token expiration
          (validated automatically by ``jose.jwt.decode``).

        Additional claims (``iat``, ``sub``, custom claims issued by
        the auth service) are passed through unchanged.

    Raises
    ------
    fastapi.HTTPException
        HTTP 401 Unauthorized with ``WWW-Authenticate: Bearer``
        challenge header when the token is malformed, the signature
        fails, the token has expired, or the required claims are
        missing. The ``detail`` message mirrors COBOL patterns from
        ``COSGN00C.cbl`` line 254 ("Unable to verify the User ...").
    """
    # `options={"require": ["user_id", "user_type", "exp"]}` would also
    # work but python-jose versions diverge on whether `require` is
    # honored. We therefore validate required claims explicitly below,
    # keeping behavior deterministic across jose 3.3.x → 3.5.x.
    try:
        payload: dict[str, Any] = jwt.decode(
            token,
            secret_key,
            algorithms=[algorithm],
        )
    except JWTError as exc:
        # Do NOT include `exc` args in the `detail` — they may leak the
        # specific reason the token was rejected (expired vs. bad
        # signature vs. malformed header), which aids brute-force
        # enumeration. The single generic message matches the COBOL
        # "Unable to verify the User ..." error (COSGN00C.cbl line 254)
        # that also did not distinguish causes.
        logger.warning(
            "JWT decoding failed",
            extra={
                "error_type": type(exc).__name__,
            },
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    # Required-claim validation. Maps to COSGN00C.cbl lines 223-228
    # which populate CDEMO-USER-ID and CDEMO-USER-TYPE in COMMAREA only
    # after a successful USRSEC read. If any of user_id / user_type /
    # exp is missing we treat the token as invalid.
    #
    # The explicit ``"exp" not in payload`` check is REQUIRED to address
    # CWE-613 (Insufficient Session Expiration). python-jose silently
    # accepts tokens that have no ``exp`` claim at all (only tokens
    # WITH an ``exp`` that is in the past raise ExpiredSignatureError).
    # Without this check an attacker who obtained the signing secret
    # could forge a non-expiring admin token and bypass the 30-minute
    # session window defined by Settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES.
    # Addresses QA Checkpoint 6 Finding #3 (MAJOR).
    if "user_id" not in payload or "user_type" not in payload or "exp" not in payload:
        logger.warning(
            "JWT missing required claims",
            extra={
                "present_claims": sorted(payload.keys()),
            },
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Normalize claim types defensively: jose returns whatever the JSON
    # payload encoded, but both user_id (CDEMO-USER-ID PIC X(08)) and
    # user_type (CDEMO-USER-TYPE PIC X(01)) are strings in the original
    # COBOL. Coerce non-string claims to strings so downstream consumers
    # can rely on `request.state.user_id.strip()` etc. without type
    # guards. The COBOL uppercase convention (FUNCTION UPPER-CASE on
    # WS-USER-ID at COSGN00C.cbl line 132) is NOT re-applied here —
    # the auth service is the canonical place that uppercases during
    # sign-on and the JWT carries the already-normalized value.
    payload["user_id"] = str(payload["user_id"])
    payload["user_type"] = str(payload["user_type"])

    return payload


# ============================================================================
# Phase 4 — JWT authentication middleware class
# ============================================================================


class JWTAuthMiddleware(BaseHTTPMiddleware):
    """Starlette middleware that enforces JWT authentication on protected paths.

    Attached to the FastAPI application in ``src/api/main.py`` via
    ``app.add_middleware(JWTAuthMiddleware)``. The middleware runs
    before every route handler and is responsible for:

    * **Skipping public endpoints** — login, health, and documentation
      paths pass through without JWT inspection
      (:func:`_is_public_path`).
    * **Extracting the bearer token** — from the standard
      ``Authorization: Bearer <jwt>`` header
      (:func:`_extract_bearer_token`).
    * **Verifying signature and expiration** — via
      :func:`decode_jwt_token` which delegates to
      ``jose.jwt.decode``.
    * **Enforcing admin routing** — paths under
      :data:`ADMIN_ONLY_PREFIXES` require ``user_type == 'A'``,
      mirroring the ``CDEMO-USRTYP-ADMIN`` branch in COSGN00C.cbl.
    * **Injecting user context** — the claims ``user_id``, ``user_type``,
      and a derived ``is_admin`` boolean are attached to
      ``request.state`` so that downstream FastAPI handlers can read
      them via :func:`src.api.dependencies.get_current_user` without
      re-decoding the JWT.

    The class intentionally performs all of this work without any
    dependency on the FastAPI dependency-injection system — middleware
    executes before ``Depends`` resolution. Keeping the middleware
    self-contained also means it can reject unauthenticated requests
    before any database session or other expensive resource is
    acquired.

    Parameters
    ----------
    app:
        The ASGI application instance. Passed automatically by Starlette
        when the middleware is registered via
        ``app.add_middleware(JWTAuthMiddleware)``.
    settings:
        Optional pre-instantiated :class:`Settings`. When ``None``
        (the default — which is what ``app.add_middleware`` will pass
        since ``add_middleware`` does not evaluate constructor
        keyword defaults), a fresh :class:`Settings` is instantiated on
        first use. Tests pass an override directly to simplify mocking
        without monkey-patching env vars.

    Notes
    -----
    The middleware mutates ``request.state`` but MUST NOT mutate
    ``request.headers`` or the URL — downstream logging middleware and
    the FastAPI router rely on the request being otherwise untouched.
    """

    def __init__(
        self,
        app: Any,
        settings: Settings | None = None,
    ) -> None:
        """Initialize the middleware and capture the signing configuration.

        Parameters
        ----------
        app:
            ASGI application instance forwarded to
            :class:`BaseHTTPMiddleware`.
        settings:
            Optional :class:`Settings` override. When ``None`` (the
            production default) a fresh :class:`Settings` is
            instantiated on first use so that environment variables
            loaded after process start are picked up correctly.
        """
        super().__init__(app)
        # Cache a Settings instance so we don't re-read environment
        # variables on every request. The instance is created lazily
        # (see `_get_settings`) so that test overrides passed via the
        # constructor take precedence over the on-demand fallback.
        self._settings: Settings | None = settings

    def _get_settings(self) -> Settings:
        """Return the cached :class:`Settings` instance, creating it on first use.

        A fresh :class:`Settings()` reads the environment once and
        Pydantic validates required fields at instantiation. We defer
        the first instantiation until the first request so that the
        middleware can be constructed at import time (e.g., inside
        ``create_app()``) before test fixtures set the env vars.
        """
        if self._settings is None:
            self._settings = Settings()
        return self._settings

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        """Validate the JWT and forward the request, or short-circuit with 401/403.

        Parameters
        ----------
        request:
            Incoming HTTP request. This method reads
            ``request.url.path``, ``request.headers['Authorization']``,
            and ``request.method`` (for logging) and mutates
            ``request.state`` on success.
        call_next:
            Starlette-provided callable that invokes the next
            middleware or the route handler. Must be awaited. Returns
            a :class:`starlette.responses.Response`.

        Returns
        -------
        starlette.responses.Response
            Either the downstream handler's response (on successful
            authentication, on a public path, or on an admin-authorized
            request), or an ABEND-DATA-shaped :class:`JSONResponse`
            (built via :func:`build_abend_response`) carrying HTTP 401
            Unauthorized / HTTP 403 Forbidden when authentication or
            authorization fails. See QA Checkpoint 2 Issue 6 for the
            rationale behind the unified error-response envelope.
        """
        path: str = request.url.path
        method: str = request.method

        # --------------------------------------------------------------
        # Step 1 — Public path bypass.
        #
        # Mirrors COSGN00C.cbl line 80 (`IF EIBCALEN = 0` first-entry
        # branch): when no session exists yet, the sign-on screen (and
        # the equivalent API-docs / health endpoints today) must still
        # be reachable. See `_is_public_path` for the full list.
        # --------------------------------------------------------------
        if _is_public_path(path):
            response: Response = await call_next(request)
            return response

        # --------------------------------------------------------------
        # Step 2 — Bearer token extraction.
        #
        # Equivalent to `EXEC CICS RECEIVE MAP('COSGN0A')` at
        # COSGN00C.cbl lines 110-115 that read USERIDI/PASSWDI from the
        # BMS screen. A missing/malformed Authorization header maps to
        # the COBOL `USERIDI OF COSGN0AI = SPACES OR LOW-VALUES` check
        # at lines 117-122 which returned 'Please enter User ID ...'.
        # --------------------------------------------------------------
        authorization: str | None = request.headers.get("Authorization")
        token: str | None = _extract_bearer_token(authorization)
        if token is None:
            logger.warning(
                "Authentication failed: missing or malformed Authorization header",
                extra={
                    "path": path,
                    "method": method,
                    "reason": "missing_bearer_token",
                },
            )
            # Shape the 401 response with the ABEND-DATA envelope used
            # by the global exception handler, so every 4xx/5xx
            # response across the API shares a single, consistent JSON
            # structure. See QA Checkpoint 2 Issue 6 for the original
            # format-inconsistency finding.
            return build_abend_response(
                status_code=status.HTTP_401_UNAUTHORIZED,
                error_code="AUTH",
                culprit="JWTAUTH",
                reason="Authentication required",
                message="Please enter User ID ...",
                request_path=path,
                headers={"WWW-Authenticate": "Bearer"},
            )

        # --------------------------------------------------------------
        # Step 3 — JWT signature + expiration + claim verification.
        #
        # `decode_jwt_token` raises HTTPException(401) on any failure.
        # We catch it explicitly to turn the HTTPException into an
        # ABEND-DATA-shaped JSONResponse (via `build_abend_response`)
        # *at middleware layer* — FastAPI's built-in HTTPException
        # handler only runs AFTER the middleware chain, so raising
        # here would bypass the consistent error envelope we want
        # across all 401 responses.
        #
        # Equivalent to READ-USER-SEC-FILE at COSGN00C.cbl lines 209-257.
        # --------------------------------------------------------------
        settings = self._get_settings()
        try:
            payload: dict[str, Any] = decode_jwt_token(
                token=token,
                secret_key=settings.JWT_SECRET_KEY,
                algorithm=settings.JWT_ALGORITHM,
            )
        except HTTPException as exc:
            # decode_jwt_token already logged the failure with reason.
            # Re-emit a terse warning here with the request context so
            # the log entry correlates with the path/method.
            logger.warning(
                "Authentication failed: invalid or expired JWT",
                extra={
                    "path": path,
                    "method": method,
                    "reason": "invalid_token",
                },
            )
            headers: dict[str, str] = {"WWW-Authenticate": "Bearer"}
            if exc.headers:
                headers.update(exc.headers)
            # Shape the 401 response with the ABEND-DATA envelope used
            # by the global exception handler. See QA Checkpoint 2
            # Issue 6. The preserved `exc.detail` from
            # decode_jwt_token becomes the user-facing `message`
            # field; `reason` captures the generic category for log
            # correlation and consistency with other 401 responses.
            detail_text: str = str(exc.detail) if exc.detail else "Invalid or expired token"
            return build_abend_response(
                status_code=exc.status_code,
                error_code="AUTH",
                culprit="JWTAUTH",
                reason="Invalid or expired token",
                message=detail_text,
                request_path=path,
                headers=headers,
            )

        user_id: str = payload["user_id"]
        user_type: str = payload["user_type"]
        is_admin: bool = user_type == "A"  # 88 CDEMO-USRTYP-ADMIN VALUE 'A'.

        # --------------------------------------------------------------
        # Step 4 — Admin path authorization.
        #
        # Mirrors COSGN00C.cbl lines 230-239: only users whose
        # SEC-USR-TYPE (→ CDEMO-USER-TYPE → `user_type` JWT claim)
        # equals 'A' were XCTL'd to COADM01C. Non-admin users were
        # routed to COMEN01C and never reached the admin menu.
        # --------------------------------------------------------------
        if _is_admin_only_path(path) and not is_admin:
            logger.warning(
                "Authorization failed: non-admin access to admin path",
                extra={
                    "user_id": user_id,
                    "user_type": user_type,
                    "path": path,
                    "method": method,
                    "reason": "not_admin",
                },
            )
            # Shape the 403 response with the ABEND-DATA envelope
            # used by the global exception handler. See QA Checkpoint
            # 2 Issue 6. The error_code is "FRBD" (Forbidden) rather
            # than "AUTH" (Authentication) because the caller *is*
            # authenticated — they simply lack the admin privilege
            # required for the requested path. This semantic
            # distinction mirrors HTTP's 401 vs 403 split and aligns
            # with the abend-code taxonomy in
            # src/shared/constants/messages.py.
            return build_abend_response(
                status_code=status.HTTP_403_FORBIDDEN,
                error_code="FRBD",
                culprit="JWTAUTH",
                reason="Admin privileges required",
                message="Admin privileges required",
                request_path=path,
            )

        # --------------------------------------------------------------
        # Step 5 — Attach user context to request.state.
        #
        # The COBOL equivalent is COSGN00C.cbl lines 223-228 populating
        # CDEMO-USER-ID, CDEMO-USER-TYPE (and the 88-level
        # CDEMO-USRTYP-ADMIN condition) in COMMAREA before XCTL. In the
        # stateless cloud-native flow, request.state is the per-request
        # carrier of this identity, consumed by `get_current_user()` in
        # `src/api/dependencies.py`.
        # --------------------------------------------------------------
        request.state.user_id = user_id  # CDEMO-USER-ID   PIC X(08)
        request.state.user_type = user_type  # CDEMO-USER-TYPE PIC X(01)
        request.state.is_admin = is_admin  # 88 CDEMO-USRTYP-ADMIN VALUE 'A'

        logger.info(
            "Authentication successful",
            extra={
                "user_id": user_id,
                "user_type": user_type,
                "path": path,
                "method": method,
            },
        )

        # --------------------------------------------------------------
        # Step 6 — Dispatch to the route handler.
        # --------------------------------------------------------------
        return await call_next(request)


# ----------------------------------------------------------------------------
# Public re-export list.
#
# Only the four schema-declared exports are part of the public API of
# this module. Private helpers (_extract_bearer_token, _is_public_path,
# _is_admin_only_path) and the internal _PUBLIC_PREFIXES tuple are
# intentionally omitted.
# ----------------------------------------------------------------------------
__all__ = [
    "JWTAuthMiddleware",
    "decode_jwt_token",
    "PUBLIC_PATHS",
    "ADMIN_ONLY_PREFIXES",
]
