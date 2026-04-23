# ============================================================================
# Source: app/cbl/COSGN00C.cbl (CICS sign-on / authentication, CC00 transaction)
#       + app/cpy/CSUSR01Y.cpy (SEC-USER-DATA 80-byte VSAM record layout)
#       + app/cpy/COCOM01Y.cpy (CARDDEMO-COMMAREA communication block)
#       + app/cpy/CSMSG01Y.cpy (system-message constants)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS ``EXEC CICS READ FILE('USRSEC') INTO(SEC-USER-DATA)
#   RIDFLD(WS-USER-ID)`` + cleartext ``SEC-USR-PWD = WS-USER-PWD``
#   comparison + ``MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE`` COMMAREA
#   population + ``EXEC CICS XCTL PROGRAM('COADM01C' / 'COMEN01C')``
#   transfer of control
#
# becomes
#
#   SQLAlchemy async ``SELECT`` on ``user_security`` table +
#   ``passlib.hash.bcrypt.verify()`` on the stored BCrypt digest +
#   ``jose.jwt.encode()`` of a JWT carrying ``sub``/``user_id`` and
#   ``user_type`` claims (mirroring ``CDEMO-USER-ID`` and
#   ``CDEMO-USER-TYPE`` from COCOM01Y.cpy) signed with
#   :attr:`Settings.JWT_SECRET_KEY` using HS256.
#
# The target deployment is AWS ECS Fargate behind an Application Load
# Balancer; the signing key comes from AWS Secrets Manager in
# staging/production (injected via ECS task-definition secrets) and
# from the ``.env`` file in local development (docker-compose).
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
"""Authentication service.

Converted from ``app/cbl/COSGN00C.cbl`` (CICS transaction CC00). BCrypt
password verification replaces the COBOL direct ``SEC-USR-PWD =
WS-USER-PWD`` comparison. JWT token generation replaces CICS COMMAREA
session state (``CARDDEMO-COMMAREA`` from ``app/cpy/COCOM01Y.cpy``).

The service exposes :class:`AuthService`, a small facade used by the
authentication router (``src/api/routers/auth_router.py``) and
indirectly by user-administration services (for BCrypt hashing of new
passwords — see ``user_service``). The class is intentionally stateful
in the database session only: no caches, no in-memory tokens, no
sessions. Stateless JWT tokens fully replace the CICS pseudo-
conversational COMMAREA round-trip.

COBOL → Python flow mapping (``COSGN00C.cbl`` PROCEDURE DIVISION):

=======================================  ==========================================
COBOL paragraph / statement              Python equivalent (this module)
=======================================  ==========================================
``PROCESS-ENTER-KEY`` lines 108–140      :meth:`AuthService.authenticate` (entry)
``FUNCTION UPPER-CASE(USERIDI)`` L132    pre-processing (value preserved as-is —
                                         Pydantic validator on SignOnRequest
                                         enforces length; upper-case normalization
                                         is applied at query time to match
                                         COBOL behavior)
``READ-USER-SEC-FILE`` L209-219          ``self.db.execute(select(UserSecurity)...)``
``EVALUATE WS-RESP-CD``                  ``if user is None`` / bcrypt.verify()
``WHEN 0 + SEC-USR-PWD = WS-USER-PWD``   ``pwd_context.verify(plain, hashed)``
``MOVE WS-USER-ID TO CDEMO-USER-ID``     JWT claim ``sub`` / ``user_id``
``MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE`` JWT claim ``user_type``
``EXEC CICS XCTL PROGRAM('COADM01C')``   HTTP response redirect handled by router
``WHEN 13 (NOTFND)``                     raise ``AuthenticationError`` with
                                         COBOL-exact message
                                         ``'User not found. Try again ...'``
``WHEN OTHER (unexpected RESP)``         raise ``AuthenticationError`` with
                                         COBOL-exact message
                                         ``'Unable to verify the User ...'``
``Wrong Password`` branch (L242-243)     raise ``AuthenticationError`` with
                                         COBOL-exact message
                                         ``'Wrong Password. Try again ...'``
=======================================  ==========================================

Error message fidelity
----------------------
The three COBOL error messages from ``app/cbl/COSGN00C.cbl`` are
reproduced **byte-for-byte**, including the space before the ellipsis:

* ``'Wrong Password. Try again ...'``     (COSGN00C.cbl line 242)
* ``'User not found. Try again ...'``     (COSGN00C.cbl line 249)
* ``'Unable to verify the User ...'``     (COSGN00C.cbl line 254)

These constants are the only user-facing strings owned by this module;
they are used as the ``AuthenticationError`` message argument so that
HTTP error-handler middleware can surface them verbatim in 401 / 500
responses (see AAP §0.7.1 "Preserve exact error messages from COBOL").

Observability
-------------
All authentication events emit structured log records via the module
logger. Log records include the ``user_id`` field (never the password)
so that CloudWatch Logs Insights queries can correlate successful /
failed sign-on attempts by user. Log levels follow the pattern:

* ``INFO``  — successful authentication and token issuance.
* ``WARNING`` — authentication failure (unknown user, bad password,
  invalid / expired JWT) — the WARNING level is chosen so that a
  CloudWatch metric filter can alert on anomalous failure rates
  without being drowned out by routine DEBUG traffic.
* ``ERROR`` — unexpected SQLAlchemy / JWT / BCrypt exceptions.

Security notes
--------------
* **Password handling** — the plaintext password provided by the
  client is held in memory for exactly the duration of
  :meth:`AuthService.authenticate` and is never logged, serialized,
  or persisted. Only BCrypt hashes reach the database.
* **Timing-attack resistance** — BCrypt's ``verify()`` is a constant-
  time comparison by construction. The order of the
  ``if user is None`` / ``if not verify(...)`` branches deliberately
  differs from a fully constant-time implementation (which would
  always perform a BCrypt ``verify`` even on an unknown user ID) to
  preserve COBOL semantic parity with ``EVALUATE WS-RESP-CD WHEN 13``.
  The observable latency difference is tiny relative to BCrypt's
  cost factor and is mitigated operationally by ALB request-rate
  limiting + CloudWatch anomaly detection — not a source-level
  defense.
* **JWT secret** — the signing key is read from
  :attr:`Settings.JWT_SECRET_KEY`, which Pydantic ``Settings`` loads
  from the environment (AWS Secrets Manager in production via ECS
  task definition; ``.env`` file in local development). The secret
  is never hardcoded and never logged.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (``auth_service.py`` row)
* AAP §0.7.1 — Refactoring-Specific Rules (preserve exact error messages)
* AAP §0.7.2 — Security Requirements (BCrypt, JWT, Secrets Manager, IAM)
* ``src/shared/models/user_security.py`` — ORM model queried here
* ``src/shared/schemas/auth_schema.py`` — Pydantic request / response schemas
* ``src/shared/config/settings.py`` — JWT secret / algorithm / expiry
* ``src/api/middleware/auth.py`` — verifies tokens issued here on every
  subsequent authenticated request
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from src.shared.config.settings import Settings
from src.shared.models.user_security import UserSecurity
from src.shared.schemas.auth_schema import (
    SignOnRequest,
    SignOnResponse,
    TokenPayload,
)

# ============================================================================
# Module-level configuration
# ============================================================================

#: Module logger. Structured records flow to CloudWatch Logs (via the
#: ECS awslogs driver) where Logs Insights queries can filter by
#: ``logger_name`` = ``src.api.services.auth_service`` to isolate
#: authentication activity.
logger = logging.getLogger(__name__)

#: BCrypt password-hashing context. The ``deprecated="auto"`` setting
#: tells passlib to transparently re-hash passwords using the current
#: scheme if a future migration switches from BCrypt to e.g. Argon2 —
#: a no-op today but a free safety net. Single ``bcrypt`` scheme
#: matches the security baseline from ``app/cbl/COSGN00C.cbl`` where
#: the comparison was cleartext (now strengthened to BCrypt digest).
pwd_context: CryptContext = CryptContext(schemes=["bcrypt"], deprecated="auto")

# ----------------------------------------------------------------------------
# COBOL-exact error messages from ``app/cbl/COSGN00C.cbl``.
#
# These three strings are reproduced byte-for-byte from the COBOL
# source — including the deliberate space before the ellipsis — per
# AAP §0.7.1 "Preserve exact error messages from COBOL". They are
# surfaced to API clients (HTTP 401) by the error-handler middleware.
# ----------------------------------------------------------------------------

#: Raised when no ``user_security`` row exists for the supplied user_id.
#: Mirrors COBOL ``EVALUATE WS-RESP-CD WHEN 13`` branch (``NOTFND``)
#: at ``COSGN00C.cbl`` line 249.
MSG_USER_NOT_FOUND: str = "User not found. Try again ..."

#: Raised when BCrypt verification fails for an existing user.
#: Mirrors COBOL ``IF SEC-USR-PWD = WS-USER-PWD`` ELSE branch at
#: ``COSGN00C.cbl`` lines 241-246.
MSG_WRONG_PASSWORD: str = "Wrong Password. Try again ..."

#: Raised on any unexpected database / driver failure during
#: authentication. Mirrors COBOL ``WHEN OTHER`` catch-all branch at
#: ``COSGN00C.cbl`` line 254.
MSG_UNABLE_TO_VERIFY: str = "Unable to verify the User ..."

#: COBOL-compatible user-type codes from ``app/cpy/COCOM01Y.cpy``
#: 88-level conditions (``CDEMO-USRTYP-ADMIN`` / ``CDEMO-USRTYP-USER``).
#: Module-level constants keep the "magic characters" out of method
#: bodies and make grep-able the exact values that must be preserved.
_USER_TYPE_ADMIN: str = "A"  # CDEMO-USRTYP-ADMIN
_USER_TYPE_USER: str = "U"  # CDEMO-USRTYP-USER

# ----------------------------------------------------------------------------
# Lazy Settings accessor.
#
# ``Settings`` is a Pydantic ``BaseSettings`` subclass that requires
# several environment variables (DATABASE_URL, DATABASE_URL_SYNC,
# JWT_SECRET_KEY) with no defaults. Instantiating ``Settings()`` at
# module-import time would therefore fail in any context where those
# env vars are not yet loaded (e.g., pytest collection before a
# conftest fixture sets them). Deferring instantiation to first use
# via a module-level cache keeps the import side-effect-free while
# preserving singleton semantics within the process.
# ----------------------------------------------------------------------------
_settings_cache: Settings | None = None


def _get_settings() -> Settings:
    """Return a cached :class:`Settings` instance, creating it on first use.

    The cache is a simple module-level variable (not a
    ``functools.lru_cache``) because :class:`Settings` is not hashable
    and because we want to let tests clear the cache via
    :func:`_reset_settings_cache` without relying on implementation
    details of ``lru_cache``.

    Returns
    -------
    Settings
        The singleton configuration object, instantiated from the
        current process environment on first call.

    Raises
    ------
    pydantic.ValidationError
        If required environment variables (e.g., ``JWT_SECRET_KEY``)
        are missing. This propagates up so that misconfigured
        deployments fail fast at first authentication attempt.
    """
    global _settings_cache
    if _settings_cache is None:
        _settings_cache = Settings()
    return _settings_cache


def _reset_settings_cache() -> None:
    """Reset the cached :class:`Settings` instance (test helper).

    Unit tests that mutate ``os.environ`` to inject alternate JWT
    secrets / expiry values call this helper to force re-load of
    :class:`Settings` on the next :func:`_get_settings` call. It is
    a no-op in normal runtime code.
    """
    global _settings_cache
    _settings_cache = None


# ============================================================================
# Exception hierarchy
# ============================================================================


class AuthenticationError(Exception):
    """Raised when authentication fails for a known, user-facing reason.

    Encapsulates the three COBOL error paths from
    ``app/cbl/COSGN00C.cbl`` (``EVALUATE WS-RESP-CD`` branches):

    * **User not found** (``WHEN 13 / NOTFND``) — the supplied
      ``user_id`` has no matching row in ``user_security``.
      Message: :data:`MSG_USER_NOT_FOUND`.
    * **Wrong password** (``WHEN 0`` with ``SEC-USR-PWD ≠
      WS-USER-PWD``) — the row exists but BCrypt verification
      failed. Message: :data:`MSG_WRONG_PASSWORD`.
    * **Unable to verify** (``WHEN OTHER``) — any other database /
      driver failure. Message: :data:`MSG_UNABLE_TO_VERIFY`.

    The error-handler middleware (``src/api/middleware/error_handler``)
    translates this exception to HTTP 401 with the message text as the
    response body. Callers who need programmatic access to the
    specific failure mode can compare :attr:`args[0]` against the
    ``MSG_*`` module constants.

    Parameters
    ----------
    message : str
        One of the ``MSG_*`` module constants. Kept as the sole
        positional arg so that ``str(exc) == message`` — convenient
        for structured logging.

    Examples
    --------
    >>> raise AuthenticationError(MSG_USER_NOT_FOUND)
    Traceback (most recent call last):
        ...
    src.api.services.auth_service.AuthenticationError: User not found. Try again ...
    """

    def __init__(self, message: str) -> None:
        super().__init__(message)
        #: The user-facing error message (one of the ``MSG_*``
        #: constants). Exposed as a typed attribute for callers that
        #: prefer attribute access over ``exc.args[0]``.
        self.message: str = message


class InvalidTokenError(AuthenticationError):
    """Raised when :meth:`AuthService.verify_token` rejects a JWT.

    A subclass of :class:`AuthenticationError` so that a single
    ``except AuthenticationError`` in the error-handler middleware
    can catch both sign-on failures and token-verification failures.

    Causes include expired tokens, bad / missing signatures,
    malformed JWT structure, and missing required claims
    (``sub`` / ``user_id``, ``user_type``). The single generic
    message mirrors the CICS-era ``'Unable to verify the User ...'``
    catch-all that also did not distinguish causes — see
    ``COSGN00C.cbl`` line 254.
    """

    def __init__(self, message: str = MSG_UNABLE_TO_VERIFY) -> None:
        super().__init__(message)


# ============================================================================
# AuthService
# ============================================================================


class AuthService:
    """Service facade for authentication-related operations.

    Each instance wraps a single SQLAlchemy async session — the
    database handle that replaces the CICS file handle to the
    ``USRSEC`` VSAM dataset. Sessions are managed (opened, closed,
    committed, rolled back) by the FastAPI dependency system in
    ``src/api/dependencies.py``; the service itself does not manage
    transaction boundaries.

    The facade exposes four methods aligned with the AAP export
    schema:

    * :meth:`authenticate`     — sign-on (async, database-backed)
    * :meth:`verify_password`  — BCrypt verification (sync, pure)
    * :meth:`hash_password`    — BCrypt hashing     (sync, pure)
    * :meth:`verify_token`     — JWT decoding       (static, pure)

    The three "pure" methods (:meth:`verify_password`,
    :meth:`hash_password`, :meth:`verify_token`) do NOT touch the
    database, so they remain usable in contexts without a session
    (e.g., user-service creation flows, middleware decoding). Only
    :meth:`authenticate` requires the async session injected at
    construction time.

    Parameters
    ----------
    db : AsyncSession
        The SQLAlchemy async session, injected by the FastAPI
        dependency ``src.api.dependencies.get_db``. Replaces the
        CICS file handle to the ``USRSEC`` VSAM dataset that
        ``EXEC CICS READ FILE('USRSEC')`` implicitly referenced in
        ``app/cbl/COSGN00C.cbl`` line 211-219.

    Attributes
    ----------
    db : AsyncSession
        The session held for the duration of the current request.
    """

    # ------------------------------------------------------------------
    # Construction
    # ------------------------------------------------------------------
    def __init__(self, db: AsyncSession) -> None:
        # Preserve the session reference on the instance. We deliberately
        # avoid storing any other request-scoped state (user_id, token,
        # etc.) so that the service object is safe to construct lazily
        # per-request via FastAPI's Depends().
        self.db: AsyncSession = db

    # ------------------------------------------------------------------
    # Primary API: authenticate()
    # ------------------------------------------------------------------
    async def authenticate(self, request: SignOnRequest) -> SignOnResponse:
        """Authenticate a user and issue a JWT access token.

        Maps to the COBOL sign-on flow in ``app/cbl/COSGN00C.cbl``
        (``PROCESS-ENTER-KEY`` + ``READ-USER-SEC-FILE`` paragraphs,
        lines 108-260). The four execution steps mirror the four
        CICS operations in the original:

        1. **Query the USRSEC record** — ``EXEC CICS READ FILE('USRSEC')
           RIDFLD(WS-USER-ID)`` becomes an async SQLAlchemy
           ``SELECT * FROM user_security WHERE user_id = :user_id``.
        2. **Translate the RESP code** — ``EVALUATE WS-RESP-CD`` is
           replaced by Python branching on ``user is None`` (NOTFND,
           ``WHEN 13``) and by try/except on the session
           ``.execute()`` call (catch-all for ``WHEN OTHER``). The
           ``RESP = 0`` branch falls through to step 3.
        3. **Verify the password** — ``IF SEC-USR-PWD = WS-USER-PWD``
           (lines 223) is upgraded to ``pwd_context.verify(plain,
           hashed)``. The COBOL cleartext comparison would never be
           acceptable in a cloud deployment; the BCrypt verify is a
           drop-in replacement that preserves the user-visible
           contract (same error message on failure).
        4. **Generate the JWT** — the COMMAREA population that
           followed a successful sign-on (``MOVE WS-USER-ID TO
           CDEMO-USER-ID`` / ``MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE``,
           lines 226-227) is replaced by a signed JWT whose claims
           carry the same fields. Both ``sub`` (JWT standard subject
           claim, per the AAP token-payload specification and the
           :class:`TokenPayload` schema) and ``user_id`` (the claim
           name expected by the JWT middleware in
           ``src/api/middleware/auth.py``) are populated with the
           authenticated user ID. They always hold the same value.

        Parameters
        ----------
        request : SignOnRequest
            Validated Pydantic v2 request carrying ``user_id`` and
            ``password`` — the two business-input fields of BMS map
            ``COSGN0A`` (``USERIDI`` / ``PASSWDI``, both
            ``PIC X(08)``). Length / emptiness validation is handled
            by the Pydantic field validators on
            :class:`SignOnRequest`; this method performs no further
            input validation.

        Returns
        -------
        SignOnResponse
            Success response containing a bearer JWT and the
            authenticated user's ``user_id`` / ``user_type``. The
            token is signed with :attr:`Settings.JWT_SECRET_KEY` and
            expires after :attr:`Settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES`
            minutes.

        Raises
        ------
        AuthenticationError
            * :data:`MSG_USER_NOT_FOUND` — no row in ``user_security``
              matches ``request.user_id`` (COBOL ``WHEN 13`` /
              ``NOTFND``).
            * :data:`MSG_WRONG_PASSWORD` — BCrypt verification failed
              (COBOL ``WHEN 0`` with ``SEC-USR-PWD ≠ WS-USER-PWD``).
            * :data:`MSG_UNABLE_TO_VERIFY` — any other SQLAlchemy /
              driver / BCrypt failure (COBOL ``WHEN OTHER``).

        Notes
        -----
        **UPPER-CASE normalization** — Both ``user_id`` and
        ``password`` are folded to upper-case before the database
        lookup and BCrypt verification. This mirrors
        ``app/cbl/COSGN00C.cbl`` lines 132-135::

            MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)
                    TO WS-USER-ID
            MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI)
                    TO WS-USER-PWD

        which uppercase the sign-on screen inputs before any
        file lookup or password comparison. Without this
        normalization a user who typed their credentials in
        lowercase on the original CICS terminal could log in
        successfully but the same lowercase credentials would be
        rejected by the Python API — a user-visible regression
        forbidden by AAP §0.7.1 "Preserve all existing functionality
        exactly as-is." The ten seed users
        (``db/migrations/V3__seed_data.sql``) have upper-case
        ``user_id`` and upper-case-hashed passwords, so this
        behavior is also required for the out-of-the-box seed
        data to authenticate successfully.
        """
        # ------------------------------------------------------------
        # UPPER-CASE normalization (COSGN00C.cbl lines 132-135).
        #
        # We compute upper-case variants of both inputs and use them
        # exclusively from this point forward — the ``request``
        # object is left untouched (Pydantic models are immutable
        # via ``model_config['frozen']`` in most of the codebase,
        # but even when mutable we prefer not to rewrite request
        # payloads in-place because downstream logging / auditing
        # should surface the *normalized* form rather than the raw
        # form to faithfully reproduce the COBOL behavior).
        # ------------------------------------------------------------
        user_id_upper: str = request.user_id.upper()
        password_upper: str = request.password.upper()

        # ------------------------------------------------------------
        # Step 1: Query the user_security table by user_id.
        #
        # COBOL equivalent (COSGN00C.cbl lines 211-219):
        #     EXEC CICS READ
        #          DATASET   (WS-USRSEC-FILE)
        #          INTO      (SEC-USER-DATA)
        #          LENGTH    (LENGTH OF SEC-USER-DATA)
        #          RIDFLD    (WS-USER-ID)
        #          KEYLENGTH (LENGTH OF WS-USER-ID)
        #          RESP      (WS-RESP-CD)
        #          RESP2     (WS-REAS-CD)
        #     END-EXEC.
        #
        # The Pydantic model already enforced that request.user_id is
        # non-empty and ≤ 8 characters, matching the COBOL PIC X(08)
        # constraint on SEC-USR-ID. The database primary-key lookup
        # is effectively O(1) via the B-tree index that replaced the
        # VSAM KSDS primary key.
        # ------------------------------------------------------------
        stmt = select(UserSecurity).where(UserSecurity.user_id == user_id_upper)

        try:
            result = await self.db.execute(stmt)
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            # COBOL equivalent (COSGN00C.cbl lines 252-256):
            #     WHEN OTHER
            #         MOVE 'Y'      TO WS-ERR-FLG
            #         MOVE 'Unable to verify the User ...' TO WS-MESSAGE
            # Any unexpected failure in driving the query (driver
            # disconnect, schema mismatch, etc.) is treated as the
            # COBOL WHEN OTHER catch-all with the exact COBOL message.
            logger.exception(
                "Authentication database query failed",
                extra={"user_id": user_id_upper},
            )
            raise AuthenticationError(MSG_UNABLE_TO_VERIFY) from exc

        user: UserSecurity | None = result.scalar_one_or_none()

        # ------------------------------------------------------------
        # Step 2: Translate the RESP code.
        #
        # COBOL equivalent (COSGN00C.cbl lines 247-251):
        #     WHEN 13
        #         MOVE 'Y'      TO WS-ERR-FLG
        #         MOVE 'User not found. Try again ...' TO WS-MESSAGE
        #
        # The CICS RESP=13 (NOTFND) path maps to scalar_one_or_none()
        # returning None. Same user-visible message is raised verbatim.
        # ------------------------------------------------------------
        if user is None:
            logger.warning(
                "Sign-on failed: user not found",
                extra={"user_id": user_id_upper, "reason": "user_not_found"},
            )
            raise AuthenticationError(MSG_USER_NOT_FOUND)

        # ------------------------------------------------------------
        # Step 3: BCrypt password verification.
        #
        # COBOL equivalent (COSGN00C.cbl lines 223, 241-246):
        #     IF SEC-USR-PWD = WS-USER-PWD      * Then ... issue JWT
        #     ELSE
        #         MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
        #
        # The COBOL EXACT byte-for-byte comparison of the PIC X(08)
        # cleartext password field is replaced by BCrypt verify()
        # against the 60-character BCrypt digest stored in the
        # user_security.password column. The cleartext plain_password
        # is held only in this stack frame and is never logged or
        # persisted.
        # ------------------------------------------------------------
        try:
            # ``password_upper`` is the COBOL-normalized form
            # (FUNCTION UPPER-CASE) — see the docstring and the
            # normalization block above.
            password_matches: bool = pwd_context.verify(password_upper, user.password)
        except Exception as exc:  # noqa: BLE001 — passlib can raise ValueError
            # A malformed hash in the database (corruption, a stub
            # password from an early migration, etc.) should be
            # treated as an unexpected failure rather than a
            # "Wrong Password" — surface it as WHEN OTHER per COBOL.
            logger.exception(
                "BCrypt verification raised an exception",
                extra={"user_id": user.user_id},
            )
            raise AuthenticationError(MSG_UNABLE_TO_VERIFY) from exc

        if not password_matches:
            logger.warning(
                "Sign-on failed: wrong password",
                extra={"user_id": user.user_id, "reason": "wrong_password"},
            )
            raise AuthenticationError(MSG_WRONG_PASSWORD)

        # ------------------------------------------------------------
        # Step 4: Generate the JWT token.
        #
        # COBOL equivalent (COSGN00C.cbl lines 224-228):
        #     MOVE WS-TRANID    TO CDEMO-FROM-TRANID
        #     MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
        #     MOVE WS-USER-ID   TO CDEMO-USER-ID
        #     MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        #     MOVE ZEROS        TO CDEMO-PGM-CONTEXT
        #
        # The CICS COMMAREA population that carried identity across
        # CICS program transfers (EXEC CICS XCTL) is replaced by a
        # signed JWT whose claims mirror the authentication-relevant
        # COMMAREA fields. Only the identity fields (CDEMO-USER-ID,
        # CDEMO-USER-TYPE) travel in the JWT — the per-request
        # COMMAREA context fields (CDEMO-FROM-TRANID,
        # CDEMO-FROM-PROGRAM, CDEMO-PGM-CONTEXT) are route-scoped in
        # the REST world and do not belong in a session token.
        #
        # Dual claim names — `sub` and `user_id` — both carry the
        # authenticated user ID:
        #   * `sub` is the JWT-standard subject claim specified by
        #     the AAP token-payload schema and the TokenPayload
        #     Pydantic model.
        #   * `user_id` is the claim name consumed by the JWT
        #     authentication middleware in
        #     src/api/middleware/auth.py (which enforces presence
        #     of user_id + user_type on every protected request).
        # Issuing both guarantees end-to-end compatibility without
        # requiring downstream code changes.
        # ------------------------------------------------------------
        token_claims: dict[str, Any] = {
            "sub": user.user_id,
            "user_id": user.user_id,
            "user_type": user.usr_type,
        }
        access_token: str = self._create_access_token(token_claims)

        logger.info(
            "Sign-on successful",
            extra={
                "user_id": user.user_id,
                "user_type": user.usr_type,
            },
        )

        # ------------------------------------------------------------
        # Step 5: Return the SignOnResponse envelope.
        #
        # The response mirrors what a CICS SEND MAP('COSGN0A') would
        # have shown (user_id + user_type) plus the new fields
        # required by the stateless cloud architecture (access_token,
        # token_type). No error message on success.
        # ------------------------------------------------------------
        return SignOnResponse(
            access_token=access_token,
            token_type="bearer",
            user_id=user.user_id,
            user_type=user.usr_type,
        )

    # ------------------------------------------------------------------
    # JWT helpers
    # ------------------------------------------------------------------
    def _create_access_token(self, data: dict[str, Any]) -> str:
        """Encode a signed JWT carrying the supplied claim dictionary.

        Private helper — not part of the :class:`AuthService` public
        API. Adds the standard ``exp`` (expiration) claim using
        :attr:`Settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES`, then signs
        with :attr:`Settings.JWT_SECRET_KEY` using
        :attr:`Settings.JWT_ALGORITHM` (``HS256``).

        The expiration replaces the CICS RTIMOUT transaction timeout
        that controlled session inactivity in the mainframe
        environment. All datetime arithmetic is performed in
        timezone-aware UTC to avoid subtle off-by-TZ bugs in
        :mod:`datetime.timezone.utc` ↔ epoch-seconds conversions.

        Parameters
        ----------
        data : dict[str, Any]
            The claim payload to encode — typically a two- or three-
            key dictionary from :meth:`authenticate` carrying
            ``sub`` / ``user_id`` and ``user_type``. The dict is
            copied before mutation so callers can safely reuse their
            input.

        Returns
        -------
        str
            The compact-serialized JWT string (three base64url
            segments separated by ``.``).
        """
        settings = _get_settings()

        # Copy the input to avoid mutating the caller's dict. The
        # exp claim is added here (not in authenticate()) so that
        # every JWT issued by this service has a consistent
        # expiration policy driven by Settings.
        to_encode: dict[str, Any] = dict(data)

        # Timezone-aware UTC is the ONLY acceptable timestamp for
        # JWT `exp` — naive datetimes cause jose to default to the
        # host timezone which can silently skew expiration.
        expire: datetime = datetime.now(UTC) + timedelta(minutes=settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES)
        to_encode["exp"] = expire

        encoded_jwt: str = jwt.encode(
            to_encode,
            settings.JWT_SECRET_KEY,
            algorithm=settings.JWT_ALGORITHM,
        )
        return encoded_jwt

    # ------------------------------------------------------------------
    # BCrypt helpers (exposed on the instance for consistency with
    # AAP export schema members_exposed = [..., verify_password(),
    # hash_password(), ...])
    # ------------------------------------------------------------------
    def verify_password(self, plain_password: str, hashed_password: str) -> bool:
        """Return ``True`` if ``plain_password`` matches ``hashed_password``.

        Thin wrapper over :meth:`passlib.context.CryptContext.verify`.
        Used by :meth:`authenticate` during sign-on and (indirectly,
        via the shared service layer) by change-password flows.

        Parameters
        ----------
        plain_password : str
            The cleartext password submitted by the client. Max 8
            characters by Pydantic validation on
            :class:`SignOnRequest`; longer values will verify
            incorrectly since BCrypt truncates input at 72 bytes
            (far above the COBOL PIC X(08) limit — not a concern).
        hashed_password : str
            The 60-character BCrypt digest stored in
            ``user_security.password``.

        Returns
        -------
        bool
            ``True`` on match, ``False`` otherwise. Constant-time
            w.r.t. the hash (BCrypt's ``verify`` performs a
            constant-time compare).
        """
        # pwd_context.verify is untyped (passlib has no type stubs), so we
        # explicitly coerce to bool to satisfy mypy's strict --no-any-return.
        # BCrypt always returns a plain Python bool.
        return bool(pwd_context.verify(plain_password, hashed_password))

    def hash_password(self, password: str) -> str:
        """Return a BCrypt digest of the supplied cleartext password.

        Used by the user-administration service
        (``src.api.services.user_service``) whenever a user is
        created or a password is reset. The resulting 60-character
        string fits the ``user_security.password`` column width
        (see ``src/shared/models/user_security.py`` and
        ``db/migrations/V1__schema.sql``).

        The CryptContext ``deprecated='auto'`` configuration means
        that if the scheme is ever changed (e.g., to Argon2),
        existing BCrypt hashes will be transparently migrated on
        next successful verify — passlib handles the upgrade.

        Parameters
        ----------
        password : str
            The cleartext password. Typically ≤ 8 characters to
            match the COBOL PIC X(08) contract; no upper limit is
            enforced here because the schema layer
            (:class:`SignOnRequest` and user-schema equivalents)
            owns length validation.

        Returns
        -------
        str
            The BCrypt hash — 60 characters in the ``$2b$…`` format.
        """
        # pwd_context.hash is untyped (passlib has no type stubs). BCrypt
        # always returns a str — coerce explicitly to satisfy mypy strict.
        hashed: str = pwd_context.hash(password)
        return hashed

    # ------------------------------------------------------------------
    # Token verification
    # ------------------------------------------------------------------
    @staticmethod
    def verify_token(token: str) -> TokenPayload:
        """Decode and validate a JWT, returning its parsed claims.

        Static method because token verification is a pure function
        of the token + the JWT signing secret and does not require a
        database session. This allows middleware, dependency
        resolvers, and utilities to verify tokens without first
        constructing an :class:`AuthService` instance.

        The method performs three validations, in order:

        1. **Cryptographic verification** — ``jose.jwt.decode``
           verifies the HMAC-SHA256 signature against
           :attr:`Settings.JWT_SECRET_KEY`. A bad signature raises
           :class:`jose.JWTError`.
        2. **Expiration check** — ``jose.jwt.decode`` automatically
           rejects tokens whose ``exp`` claim is in the past,
           raising :class:`jose.ExpiredSignatureError` (a subclass
           of :class:`jose.JWTError`).
        3. **Schema validation** — the decoded claims are parsed
           into a :class:`TokenPayload`, which enforces the
           presence and format of ``sub`` and ``user_type``
           (COCOM01Y.cpy 88-level: ``'A'`` or ``'U'``).

        Any failure in any step surfaces as
        :class:`InvalidTokenError` with the COBOL-exact message
        ``'Unable to verify the User ...'``. The original exception
        is chained via ``raise … from`` so that logs and
        tracebacks preserve the diagnostic detail without exposing
        it to API clients.

        Parameters
        ----------
        token : str
            The compact-serialized JWT string (three base64url
            segments separated by ``.``). Typically extracted from
            an ``Authorization: Bearer <token>`` HTTP header by the
            JWT middleware.

        Returns
        -------
        TokenPayload
            The parsed, validated token claims.

        Raises
        ------
        InvalidTokenError
            On any cryptographic, structural, or schema validation
            failure. The message is :data:`MSG_UNABLE_TO_VERIFY`,
            matching the COBOL ``WHEN OTHER`` catch-all.
        """
        settings = _get_settings()

        # ------------------------------------------------------------
        # Step 1-2: JWT decode (signature + expiration verification).
        #
        # jose.jwt.decode returns a dict of claims. It raises
        # JWTError (or a subclass like ExpiredSignatureError) on any
        # failure: bad signature, malformed header / payload, expired
        # exp claim, or signature-algorithm mismatch.
        # ------------------------------------------------------------
        try:
            payload: dict[str, Any] = jwt.decode(
                token,
                settings.JWT_SECRET_KEY,
                algorithms=[settings.JWT_ALGORITHM],
            )
        except JWTError as exc:
            # Do NOT include `exc` args in the InvalidTokenError
            # message — they may leak the specific rejection reason
            # (expired vs. bad signature vs. malformed header) which
            # aids brute-force enumeration. The single generic
            # message matches the COBOL WHEN OTHER branch and mirrors
            # the defensive design already used by
            # src/api/middleware/auth.py.
            logger.warning(
                "JWT verification failed",
                extra={"error_type": type(exc).__name__},
            )
            raise InvalidTokenError(MSG_UNABLE_TO_VERIFY) from exc

        # ------------------------------------------------------------
        # Step 3: Schema validation via TokenPayload.
        #
        # TokenPayload is a Pydantic v2 BaseModel that requires:
        #   * sub       : str (max_length=8)   — CDEMO-USER-ID
        #   * user_type : str ∈ {'A', 'U'}     — CDEMO-USER-TYPE
        #   * exp       : int (optional)       — JWT standard exp
        #
        # The model's field_validator methods enforce the COBOL
        # 88-level constraint on user_type; a token carrying an
        # invalid user_type (e.g., lowercase 'a' or the empty string)
        # will be rejected here even if its signature was valid.
        # ------------------------------------------------------------
        try:
            token_payload = TokenPayload(**payload)
        except Exception as exc:  # noqa: BLE001 — Pydantic ValidationError etc.
            # A ValidationError here indicates a correctly signed
            # token with unexpected / malformed claims — treat as
            # WHEN OTHER and map to the COBOL-exact message.
            logger.warning(
                "JWT payload failed schema validation",
                extra={"error_type": type(exc).__name__},
            )
            raise InvalidTokenError(MSG_UNABLE_TO_VERIFY) from exc

        return token_payload


# ============================================================================
# Public re-export list.
#
# Re-exports the AuthService class as well as the authentication
# error hierarchy so that routers, middleware, and test modules can
# catch failures with a single `from src.api.services.auth_service
# import AuthenticationError` regardless of whether the failure came
# from sign-on or token verification. The COBOL-exact message
# constants (MSG_*) are also exported so test suites can assert on
# them by name rather than by literal string.
# ============================================================================
__all__ = [
    "AuthService",
    "AuthenticationError",
    "InvalidTokenError",
    "MSG_USER_NOT_FOUND",
    "MSG_WRONG_PASSWORD",
    "MSG_UNABLE_TO_VERIFY",
]
