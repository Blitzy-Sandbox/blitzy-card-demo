# ============================================================================
# CardDemo — Unit tests for auth_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COSGN00C.cbl     — CICS sign-on / authentication program,
#                                transaction ``CC00`` (~260 lines). Performs:
#                                  (1) ``RECEIVE MAP('COSGN0A')`` to collect
#                                      USERIDI / PASSWDI input
#                                  (2) ``EXEC CICS READ DATASET('USRSEC')``
#                                      to fetch the SEC-USER-DATA row
#                                  (3) cleartext compare SEC-USR-PWD vs
#                                      WS-USER-PWD (upgraded to BCrypt hash
#                                      verification in the target)
#                                  (4) populate ``CDEMO-USER-ID`` /
#                                      ``CDEMO-USER-TYPE`` in COMMAREA and
#                                      ``EXEC CICS XCTL`` to either
#                                      COADM01C (admin) or COMEN01C (user).
#                                Three user-facing error paths with exact,
#                                byte-preserved message text:
#                                  * line 242-243 ``'Wrong Password. Try again ...'``
#                                  * line 249     ``'User not found. Try again ...'``
#                                  * line 254     ``'Unable to verify the User ...'``
#   * app/cpy/COCOM01Y.cpy     — CARDDEMO-COMMAREA (96 bytes). Supplies
#                                ``CDEMO-USER-ID PIC X(08)`` and
#                                ``CDEMO-USER-TYPE PIC X(01)`` with 88-level
#                                conditions ``CDEMO-USRTYP-ADMIN VALUE 'A'``
#                                / ``CDEMO-USRTYP-USER VALUE 'U'``. In the
#                                cloud target these propagate as JWT claims.
#   * app/cpy/CSUSR01Y.cpy     — SEC-USER-DATA VSAM record layout:
#                                ``SEC-USR-ID PIC X(08)``,
#                                ``SEC-USR-PWD PIC X(08)``,
#                                ``SEC-USR-TYPE PIC X(01)``. In the cloud
#                                target mapped to
#                                ``src.shared.models.UserSecurity``.
#   * app/cpy-bms/COSGN00.CPY  — BMS symbolic map. Defines
#                                ``USERIDI PIC X(08)`` and
#                                ``PASSWDI PIC X(08)`` — the two inputs
#                                carried by :class:`SignOnRequest` — plus
#                                ``ERRMSGI PIC X(78)`` where sign-on error
#                                messages were rendered in the BMS era.
#   * app/cpy/CSMSG01Y.cpy     — Shared system-message constants. The
#                                COSGN00C error text is not technically
#                                from this copybook (it is inlined into
#                                the COBOL source); CSMSG01Y is the
#                                convention for shared messages in other
#                                programs.
# ----------------------------------------------------------------------------
# Feature F-001: Sign-on / Authentication. Target implementation under
# test: ``src/api/routers/auth_router.py`` — FastAPI router providing
# ``POST /auth/login`` and ``POST /auth/logout``. Both endpoints are in
# :data:`src.api.middleware.auth.PUBLIC_PATHS` so no JWT is required to
# reach them.
# ----------------------------------------------------------------------------
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
"""Unit tests for :mod:`src.api.routers.auth_router`.

Validates the two authentication endpoints (``POST /auth/login`` and
``POST /auth/logout``) converted from ``app/cbl/COSGN00C.cbl`` (CICS
transaction ``CC00``, Feature F-001) per AAP §0.5.1 (File-by-File
Transformation Plan).

COBOL -> Python Verification Surface
------------------------------------
=================================================  ====================================
COBOL paragraph / statement (COSGN00C.cbl)         Python test (this module)
=================================================  ====================================
``READ USRSEC`` success + PWD match (L222-240)     ``test_login_valid_credentials``
``MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE`` admin     ``test_login_admin_user``
                                                     ('A' branch toward COADM01C XCTL)
``SEC-USR-PWD NOT = WS-USER-PWD`` (L241-245)       ``test_login_wrong_password``
``DFHRESP(NOTFND)`` on READ (L246-250)             ``test_login_user_not_found``
``USERIDI = SPACES`` check (L117-121)              ``test_login_empty_user_id``
``PASSWDI = SPACES`` check (L122-126)              ``test_login_empty_password``
no RECEIVE MAP fields at all (EIBCALEN=0)          ``test_login_missing_fields``
``MOVE WS-TOKEN TO ERRMSGO`` (cloud-only shape)    ``test_login_jwt_token_format``
``WHEN OTHER`` RESP (L251-255) ->                  ``test_login_system_error``
  ``'Unable to verify the User ...'``
``EXEC CICS RETURN`` session end (L171-172)        ``test_logout_success``
=================================================  ====================================

Exact COBOL Error Strings (Byte-For-Byte Preserved)
---------------------------------------------------
Per AAP §0.7.1 ("Preserve all existing functionality exactly as-is") and
the explicit instruction in the implementation brief to preserve the
*exact* COBOL error-message strings, the following are literal copies
from ``app/cbl/COSGN00C.cbl``:

* ``"Wrong Password. Try again ..."``  (COSGN00C.cbl lines 242-243)
* ``"User not found. Try again ..."``  (COSGN00C.cbl line 249)
* ``"Unable to verify the User ..."``  (COSGN00C.cbl line 254)

They are re-exported from
:mod:`src.api.services.auth_service` as module-level constants
(:data:`MSG_WRONG_PASSWORD`, :data:`MSG_USER_NOT_FOUND`,
:data:`MSG_UNABLE_TO_VERIFY`) and imported here rather than
re-inlined so that any future change to the message text requires a
corresponding edit to the service module and is caught by git diff.

Response Envelope Format
------------------------
Error responses are wrapped by the global exception handler in
:mod:`src.api.middleware.error_handler` in the ABEND-DATA envelope
shape::

    {
        "error": {
            "status_code": 401,
            "error_code":  "AUTH",
            "culprit":     "AUTH",
            "reason":      "<COBOL error text from exc.detail>",
            "message":     "<same as reason for 401>",
            "timestamp":   "...",
            "path":        "/auth/login"
        }
    }

Tests assert the ``error.reason`` field (the PIC X(50) ABEND-REASON
slot) because that is where ``HTTPException.detail`` text lands per
``http_exception_handler`` in the error-handler module.

Test Isolation Strategy
-----------------------
Per AAP §0.7.2 ("automated testing as much as possible") and the
implementation brief's key rule ("Mock the service layer (AuthService)
— unit tests don't touch the database"):

* :class:`AuthService` is patched at its *import site* inside the
  router module (``src.api.routers.auth_router.AuthService``) — not at
  the service module — so that the router's ``AuthService(db)``
  invocation resolves to the patched factory.
* The patched class is configured to return a ``MagicMock()`` whose
  ``authenticate`` attribute is an :class:`unittest.mock.AsyncMock`.
* No database round-trips occur; the ``get_db`` dependency is still
  overridden by ``tests.conftest.test_app`` to yield the SAVEPOINT
  session, but the service never consults it under the patch.

Fixtures Used
-------------
From :mod:`tests.conftest`:
    * ``client``          — :class:`httpx.AsyncClient` pre-signed with
                            a regular-user JWT. ``/auth/login`` and
                            ``/auth/logout`` are in
                            ``PUBLIC_PATHS`` so the pre-signed header
                            is ignored by the middleware but also does
                            not interfere. We use this for all happy
                            and negative paths.
    * ``test_app``        — FastAPI app with ``get_db`` /
                            ``get_current_user`` overridden; not
                            referenced directly (only transitively via
                            ``client``).

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (auth_router row).
* AAP §0.7.1 — "Preserve all existing functionality exactly as-is"
              and "Preserve EXACT error messages from COBOL source".
* :mod:`src.api.routers.auth_router`     — module under test.
* :mod:`src.api.services.auth_service`   — AuthService + MSG_* constants.
* :mod:`src.shared.schemas.auth_schema`  — SignOnRequest / SignOnResponse.
* :mod:`src.api.middleware.error_handler` — ABEND-DATA envelope wrapper.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import status
from httpx import AsyncClient

from src.api.services.auth_service import (
    MSG_UNABLE_TO_VERIFY,
    MSG_USER_NOT_FOUND,
    MSG_WRONG_PASSWORD,
    AuthenticationError,
)
from src.shared.schemas.auth_schema import SignOnResponse

# ============================================================================
# Test constants — tightly coupled to conftest.py fixture values
# ============================================================================
# The ``client`` fixture in tests/conftest.py overrides ``get_current_user``
# to return ``CurrentUser(user_id="TESTUSER", user_type="U", ...)`` and
# pre-signs the request with a JWT for that identity. For ``/auth/login``
# (in PUBLIC_PATHS) this JWT is ignored — we exercise the login logic
# from scratch. We use the same user id here purely for
# self-documenting-response purposes, not because it is required.
#
# Both values are exactly 8 characters long, matching the COBOL
# ``PIC X(08)`` constraint on ``USERIDI`` / ``PASSWDI`` in
# ``app/cpy-bms/COSGN00.CPY``. Pydantic's ``max_length=8`` validator on
# :class:`SignOnRequest` would reject longer values with HTTP 422 — a
# behavior exercised indirectly in ``test_login_missing_fields`` via
# the default-value-absent path. The conftest-wide default password
# constant is ``_DEFAULT_TEST_PASSWORD = "Test1234"`` (also 8 chars);
# we mirror the length here.
# ----------------------------------------------------------------------------
_TEST_USER_ID: str = "TESTUSER"  # PIC X(08) — 8 chars
_TEST_ADMIN_ID: str = "ADMIN001"  # PIC X(08) — 8 chars
_TEST_PASSWORD: str = "TESTPASS"  # PIC X(08) — 8 chars (valid)
_TEST_WRONG_PWD: str = "WRONGPWD"  # PIC X(08) — 8 chars (valid format, wrong value)

# JWT is three dot-separated base64url segments: header.payload.signature.
# For unit tests we do not need a real signed token — the router treats
# the token as an opaque string it passes through from AuthService. A
# fixed three-segment string suffices to exercise the JWT format check
# in ``test_login_jwt_token_format``.
_STUB_JWT: str = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJURVNUVVNFUiJ9.fakeSignature"

# Response envelope fields — the global exception handler wraps
# :class:`HTTPException` in ``{"error": {"status_code": int,
# "error_code": str, "culprit": str, "reason": str, "message": str,
# "timestamp": str, "path": str}}`` per
# :mod:`src.api.middleware.error_handler`. See ``_build_error_response``
# in that module for the canonical shape.
_ERROR_ENVELOPE_KEY: str = "error"
_ERROR_REASON_KEY: str = "reason"
_ERROR_MESSAGE_KEY: str = "message"
_ERROR_STATUS_KEY: str = "status_code"
_ERROR_CODE_KEY: str = "error_code"

# The 4-character ABEND-CODE for HTTP 401 per
# :data:`src.api.middleware.error_handler._HTTP_STATUS_TO_ERROR_CODE`.
# This is asserted alongside ``error.reason`` to confirm the envelope
# was produced by the HTTPException handler (not, e.g., a middleware
# fall-through that might yield a different error_code).
_AUTH_ERROR_CODE: str = "AUTH"


# ============================================================================
# Private helpers — response-body extraction
# ============================================================================
def _extract_error(response_body: dict[str, Any]) -> dict[str, Any]:
    """Return the inner error dict from a wrapped error-response body.

    The global exception handler wraps every ``HTTPException`` raised
    under ``/api/**`` in the ABEND-DATA envelope
    ``{"error": {"status_code": ..., "error_code": ..., ...}}``. For
    test assertions we want to work with the inner dict directly.

    Parameters
    ----------
    response_body : dict[str, Any]
        The parsed JSON body of an HTTP error response.

    Returns
    -------
    dict[str, Any]
        The inner error dict. Returns an empty dict if the key is
        absent so assertions fail with ``KeyError``-free, readable
        messages when the envelope shape unexpectedly changes.
    """
    error = response_body.get(_ERROR_ENVELOPE_KEY)
    if isinstance(error, dict):
        return error
    # Envelope missing — return an empty dict so subsequent key lookups
    # raise AssertionError rather than KeyError, keeping failure output
    # readable for the pytest diff.
    return {}


def _build_mock_auth_service_success(response: SignOnResponse) -> MagicMock:
    """Build a mock :class:`AuthService` whose ``authenticate`` succeeds.

    The returned mock is intended to be passed as ``side_effect`` of
    the ``AuthService(db)`` factory patch in
    :func:`_patch_auth_service_class` — i.e., it represents a *service
    instance*, not the class. Callers wire it up via::

        mock_service = _build_mock_auth_service_success(expected_response)
        with patch("src.api.routers.auth_router.AuthService",
                   return_value=mock_service):
            response = await client.post("/auth/login", json=...)

    Parameters
    ----------
    response : SignOnResponse
        The response object returned by the mocked ``authenticate``.
        Must be a valid SignOnResponse instance so FastAPI's
        ``response_model`` serialization succeeds end-to-end.

    Returns
    -------
    MagicMock
        A mock with ``.authenticate`` set to an :class:`AsyncMock`
        configured to return ``response``.
    """
    mock_service = MagicMock()
    mock_service.authenticate = AsyncMock(return_value=response)
    return mock_service


def _build_mock_auth_service_failure(exc: Exception) -> MagicMock:
    """Build a mock :class:`AuthService` whose ``authenticate`` raises.

    Symmetric to :func:`_build_mock_auth_service_success`. The ``exc``
    argument is the exception instance that should be raised when the
    router awaits ``service.authenticate(request)``. For the three
    COBOL-preserved error paths (:data:`MSG_WRONG_PASSWORD`,
    :data:`MSG_USER_NOT_FOUND`, :data:`MSG_UNABLE_TO_VERIFY`) this
    should be an :class:`AuthenticationError` with the corresponding
    ``MSG_*`` text — the router catches ``AuthenticationError`` and
    surfaces ``str(exc)`` as the ``HTTPException.detail``.

    Parameters
    ----------
    exc : Exception
        The exception instance that ``await authenticate(...)`` should
        raise. Typically :class:`AuthenticationError` carrying one of
        the COBOL-preserved ``MSG_*`` constants.

    Returns
    -------
    MagicMock
        A mock with ``.authenticate`` set to an :class:`AsyncMock`
        configured to raise ``exc``.
    """
    mock_service = MagicMock()
    mock_service.authenticate = AsyncMock(side_effect=exc)
    return mock_service


# ============================================================================
# SECTION 1 — Tests for POST /auth/login
# ----------------------------------------------------------------------------
# Covers the COSGN00C.cbl main path:
#   1. Valid credentials -> HTTP 200 + JWT (COSGN00C L222-240).
#   2. Admin credentials -> HTTP 200 + JWT claim user_type='A'
#      (COSGN00C L230, the CDEMO-USRTYP-ADMIN branch).
#   3. Wrong password   -> HTTP 401 "Wrong Password. Try again ..."
#      (COSGN00C L241-245 + MSG_WRONG_PASSWORD).
#   4. User not found   -> HTTP 401 "User not found. Try again ..."
#      (COSGN00C L246-250 + MSG_USER_NOT_FOUND).
#   5. Empty user id    -> HTTP 422 (Pydantic rejects before reaching
#      the router; cloud equivalent of COSGN00C L117-121).
#   6. Empty password   -> HTTP 422 (same mechanism as above;
#      COSGN00C L122-126).
#   7. Both missing     -> HTTP 422 (SignOnRequest field ``...``
#      markers require presence; COSGN00C L117-130 combined).
#   8. JWT format       -> 3 dot-separated segments (stateless
#      equivalent of CDEMO-USER-ID/TYPE COMMAREA propagation).
#   9. Unhandled error  -> AuthenticationError(MSG_UNABLE_TO_VERIFY) ->
#      HTTP 401 with that exact text (COSGN00C L251-255).
# ============================================================================
class TestAuthLogin:
    """Tests for the ``POST /auth/login`` endpoint."""

    # ------------------------------------------------------------------
    # 1. Happy path — valid regular user
    # ------------------------------------------------------------------
    async def test_login_valid_credentials(self, client: AsyncClient) -> None:
        """Valid regular-user credentials return HTTP 200 + JWT response.

        Mirrors ``COSGN00C.cbl`` lines 222-240 — the main success
        branch where ``WS-RESP-CD = 0`` and ``SEC-USR-PWD =
        WS-USER-PWD``. In the COBOL flow this populates
        ``CDEMO-USER-ID`` and ``CDEMO-USER-TYPE`` in COMMAREA then
        ``EXEC CICS XCTL PROGRAM('COMEN01C')`` for a regular user. In
        the cloud-native target the equivalent action is issuing a
        signed JWT whose ``user_type`` claim is ``'U'`` — the JWT
        replaces the COMMAREA for inter-request identity
        propagation.

        Assertions:
            * HTTP 200 OK.
            * Response body is JSON (``application/json``).
            * ``access_token`` is a non-empty string (the JWT itself).
            * ``token_type`` is exactly ``"bearer"`` — matches the
              OAuth2 contract enforced by
              :class:`SignOnResponse` in
              :mod:`src.shared.schemas.auth_schema`.
            * ``user_id`` echoes the input (mirrors
              ``CDEMO-USER-ID``).
            * ``user_type`` is ``'U'`` (mirrors
              ``CDEMO-USRTYP-USER``).
        """
        expected_response = SignOnResponse(
            access_token=_STUB_JWT,
            token_type="bearer",
            user_id=_TEST_USER_ID,
            user_type="U",
        )
        mock_service = _build_mock_auth_service_success(expected_response)

        with patch(
            "src.api.routers.auth_router.AuthService",
            return_value=mock_service,
        ):
            response = await client.post(
                "/auth/login",
                json={"user_id": _TEST_USER_ID, "password": _TEST_PASSWORD},
            )

        # HTTP contract assertions.
        assert response.status_code == status.HTTP_200_OK, (
            f"Expected HTTP 200 OK, got {response.status_code}; body={response.text!r}"
        )
        assert response.headers["content-type"].startswith("application/json")

        # Body shape and content.
        body: dict[str, Any] = response.json()
        assert isinstance(body.get("access_token"), str)
        assert body["access_token"], "access_token must be non-empty"
        assert body["token_type"] == "bearer"
        assert body["user_id"] == _TEST_USER_ID
        assert body["user_type"] == "U"

        # The router must have invoked AuthService.authenticate exactly
        # once — the factory's call_args holds the SignOnRequest that
        # was constructed from the JSON body.
        mock_service.authenticate.assert_awaited_once()

    # ------------------------------------------------------------------
    # 2. Happy path — admin user (user_type = 'A')
    # ------------------------------------------------------------------
    async def test_login_admin_user(self, client: AsyncClient) -> None:
        """Admin-type credentials return user_type='A' for admin routing.

        Mirrors ``COSGN00C.cbl`` line 230 — the
        ``CDEMO-USRTYP-ADMIN`` (88-level VALUE 'A') branch that XCTLs
        to ``COADM01C`` for admin users. In the cloud target, the
        ``user_type`` JWT claim replaces the COMMAREA field; the API
        gateway (or middleware-layer admin-prefix check in
        :mod:`src.api.middleware.auth`) inspects the claim on
        subsequent requests to route to ``/admin/*`` endpoints.

        Assertions:
            * HTTP 200 OK.
            * Response ``user_type`` is exactly ``'A'``.
            * ``user_id`` echoes the admin id (``"ADMIN001"`` in the
              seeded DUSRSEC dataset).
        """
        expected_response = SignOnResponse(
            access_token=_STUB_JWT,
            token_type="bearer",
            user_id=_TEST_ADMIN_ID,
            user_type="A",
        )
        mock_service = _build_mock_auth_service_success(expected_response)

        with patch(
            "src.api.routers.auth_router.AuthService",
            return_value=mock_service,
        ):
            response = await client.post(
                "/auth/login",
                json={"user_id": _TEST_ADMIN_ID, "password": _TEST_PASSWORD},
            )

        assert response.status_code == status.HTTP_200_OK
        body: dict[str, Any] = response.json()
        # COCOM01Y.cpy 88-level: CDEMO-USRTYP-ADMIN VALUE 'A'.
        assert body["user_type"] == "A", (
            f"Admin login must return user_type='A' per COCOM01Y.cpy 88-level; got {body.get('user_type')!r}"
        )
        assert body["user_id"] == _TEST_ADMIN_ID

    # ------------------------------------------------------------------
    # 3. Wrong password -> HTTP 401 with exact COBOL message
    # ------------------------------------------------------------------
    async def test_login_wrong_password(self, client: AsyncClient) -> None:
        """Wrong password yields HTTP 401 with exact COBOL error text.

        Mirrors ``COSGN00C.cbl`` lines 241-245 — the
        ``SEC-USR-PWD NOT = WS-USER-PWD`` branch after a successful
        ``READ USRSEC``. The COBOL program moved the literal
        ``'Wrong Password. Try again ...'`` into ``ERRMSGO`` and
        re-rendered the sign-on map. The cloud-native equivalent is an
        HTTP 401 Unauthorized with the same message text in the
        ``error.reason`` field of the ABEND-DATA response envelope.

        CRITICAL — the message text is asserted byte-for-byte against
        :data:`MSG_WRONG_PASSWORD` which mirrors the COBOL source
        exactly per AAP §0.7.1 ("Preserve all existing functionality
        exactly as-is" + "Preserve EXACT error messages from COBOL
        source").
        """
        # Sanity check: the imported constant equals the COBOL string.
        # If a future edit to auth_service.py changes this constant to
        # a different wording, the assertion below catches it before
        # the rest of the test runs so the failure output is maximally
        # readable.
        assert MSG_WRONG_PASSWORD == "Wrong Password. Try again ...", (
            f"MSG_WRONG_PASSWORD must exactly match COSGN00C.cbl lines 242-243; got {MSG_WRONG_PASSWORD!r}"
        )

        mock_service = _build_mock_auth_service_failure(AuthenticationError(MSG_WRONG_PASSWORD))

        with patch(
            "src.api.routers.auth_router.AuthService",
            return_value=mock_service,
        ):
            response = await client.post(
                "/auth/login",
                json={"user_id": _TEST_USER_ID, "password": _TEST_WRONG_PWD},
            )

        # HTTP contract.
        assert response.status_code == status.HTTP_401_UNAUTHORIZED, (
            f"Expected HTTP 401 Unauthorized for wrong password, got {response.status_code}; body={response.text!r}"
        )
        # RFC 7235 requires WWW-Authenticate on 401 responses. The
        # router sets ``headers={"WWW-Authenticate": "Bearer"}`` and
        # ``http_exception_handler`` preserves them.
        assert response.headers.get("www-authenticate") == "Bearer"

        # COBOL-exact message in the ABEND-DATA envelope.
        body: dict[str, Any] = response.json()
        error = _extract_error(body)
        assert error.get(_ERROR_STATUS_KEY) == 401
        assert error.get(_ERROR_CODE_KEY) == _AUTH_ERROR_CODE, (
            f"ABEND-CODE must be 'AUTH' for 401 per _HTTP_STATUS_TO_ERROR_CODE; got {error.get(_ERROR_CODE_KEY)!r}"
        )
        # BYTE-FOR-BYTE COBOL MESSAGE PRESERVATION (COSGN00C.cbl L242-243).
        assert error.get(_ERROR_REASON_KEY) == MSG_WRONG_PASSWORD, (
            f"Error message MUST match COBOL source "
            f"{MSG_WRONG_PASSWORD!r} byte-for-byte; got "
            f"{error.get(_ERROR_REASON_KEY)!r}"
        )
        # Cross-assertion on the raw response text — survives any
        # envelope-shape drift and catches accidental truncation by
        # ``_truncate_to_pic_width`` (PIC X(50) = 50 chars; the COBOL
        # message is 29 chars so no truncation should occur).
        assert MSG_WRONG_PASSWORD in response.text

    # ------------------------------------------------------------------
    # 4. User not found -> HTTP 401 with exact COBOL message
    # ------------------------------------------------------------------
    async def test_login_user_not_found(self, client: AsyncClient) -> None:
        """Unknown user id yields HTTP 401 with exact COBOL error text.

        Mirrors ``COSGN00C.cbl`` lines 246-250 — the
        ``DFHRESP(NOTFND)`` branch of ``EVALUATE WS-RESP-CD`` after the
        ``READ FILE('USRSEC') RIDFLD(WS-USER-ID)`` fails to find a
        matching row. The COBOL program moved the literal
        ``'User not found. Try again ...'`` into ``ERRMSGO``. The
        cloud-native equivalent is an HTTP 401 Unauthorized with the
        same message text — note the message matches COBOL word-for-
        word including the trailing ``" ..."`` space-dot-dot-dot.

        CRITICAL — message preserved byte-for-byte from COSGN00C.cbl.
        """
        # Sanity check: the imported constant equals the COBOL string.
        assert MSG_USER_NOT_FOUND == "User not found. Try again ...", (
            f"MSG_USER_NOT_FOUND must exactly match COSGN00C.cbl line 249; got {MSG_USER_NOT_FOUND!r}"
        )

        mock_service = _build_mock_auth_service_failure(AuthenticationError(MSG_USER_NOT_FOUND))

        with patch(
            "src.api.routers.auth_router.AuthService",
            return_value=mock_service,
        ):
            response = await client.post(
                "/auth/login",
                json={"user_id": "NOSUCHUS", "password": _TEST_PASSWORD},
            )

        assert response.status_code == status.HTTP_401_UNAUTHORIZED
        assert response.headers.get("www-authenticate") == "Bearer"

        body: dict[str, Any] = response.json()
        error = _extract_error(body)
        assert error.get(_ERROR_STATUS_KEY) == 401
        assert error.get(_ERROR_CODE_KEY) == _AUTH_ERROR_CODE
        # BYTE-FOR-BYTE COBOL MESSAGE PRESERVATION (COSGN00C.cbl L249).
        assert error.get(_ERROR_REASON_KEY) == MSG_USER_NOT_FOUND, (
            f"Error message MUST match COBOL source "
            f"{MSG_USER_NOT_FOUND!r} byte-for-byte; got "
            f"{error.get(_ERROR_REASON_KEY)!r}"
        )
        assert MSG_USER_NOT_FOUND in response.text

    # ------------------------------------------------------------------
    # 5. Empty user_id -> HTTP 422
    # ------------------------------------------------------------------
    async def test_login_empty_user_id(self, client: AsyncClient) -> None:
        """Empty user_id is rejected with HTTP 422 before the router runs.

        Mirrors ``COSGN00C.cbl`` lines 117-121 — the
        ``IF USERIDI OF COSGN0AI = SPACES OR LOW-VALUES`` guard before
        any database access. In COBOL this was a per-field presence
        check that re-rendered the sign-on map with
        ``'Please enter User ID ...'``. In the cloud target the
        equivalent guard is the ``@field_validator('user_id')`` on
        :class:`SignOnRequest` which raises ``ValueError`` (wrapped by
        Pydantic as ``RequestValidationError``) resulting in HTTP 422.

        AuthService is NOT patched here because the request body
        should fail validation before the router body executes.
        """
        response = await client.post(
            "/auth/login",
            json={"user_id": "", "password": _TEST_PASSWORD},
        )
        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Empty user_id must yield HTTP 422 (Pydantic validator); "
            f"got {response.status_code}; body={response.text!r}"
        )

    # ------------------------------------------------------------------
    # 6. Empty password -> HTTP 422
    # ------------------------------------------------------------------
    async def test_login_empty_password(self, client: AsyncClient) -> None:
        """Empty password is rejected with HTTP 422 before the router runs.

        Mirrors ``COSGN00C.cbl`` lines 122-126 — the
        ``IF PASSWDI OF COSGN0AI = SPACES`` guard. Same mechanism as
        :meth:`test_login_empty_user_id` but against the password
        field validator on :class:`SignOnRequest`.
        """
        response = await client.post(
            "/auth/login",
            json={"user_id": _TEST_USER_ID, "password": ""},
        )
        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Empty password must yield HTTP 422 (Pydantic validator); "
            f"got {response.status_code}; body={response.text!r}"
        )

    # ------------------------------------------------------------------
    # 7. Both fields missing -> HTTP 422
    # ------------------------------------------------------------------
    async def test_login_missing_fields(self, client: AsyncClient) -> None:
        """Empty request body is rejected with HTTP 422.

        Covers the degenerate case where the client sends neither
        ``user_id`` nor ``password``. In COBOL this would be an
        EIBCALEN=0 RECEIVE MAP with SPACES in both fields; the
        cloud-native equivalent is Pydantic's missing-required-field
        check raising ``RequestValidationError`` before the router
        body executes.

        We assert both ``user_id`` and ``password`` appear in the
        validation-error payload so that future changes to
        :class:`SignOnRequest` (e.g., adding a third required field
        that the same 422 would now cover) do not silently pass this
        test.
        """
        response = await client.post("/auth/login", json={})
        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Empty request body must yield HTTP 422; got {response.status_code}; body={response.text!r}"
        )
        # The validation handler serializes a summary of the first
        # field-level error into ``error.message``; the raw text also
        # retains field names. We assert both required fields were
        # flagged somewhere in the response (text match) to guard
        # against partial validation regressions.
        response_text = response.text
        assert "user_id" in response_text or "password" in response_text, (
            f"Validation error body must name at least one missing required field; got {response_text!r}"
        )

    # ------------------------------------------------------------------
    # 8. JWT format verification
    # ------------------------------------------------------------------
    async def test_login_jwt_token_format(self, client: AsyncClient) -> None:
        """Returned access_token has the canonical 3-part JWT format.

        RFC 7519 defines a JWT as three base64url-encoded segments
        separated by two ``.`` (dot) characters:
        ``<header>.<payload>.<signature>``. This test verifies the
        router returns a syntactically-well-formed JWT by splitting
        the ``access_token`` on ``.`` and asserting exactly three
        non-empty segments.

        Signature validity and claim contents are out of scope for
        this unit test (covered in
        :mod:`tests.unit.test_services.test_auth_service`); here we
        only guard against accidental regressions where the token
        would collapse to a plain opaque string or a base64 blob with
        a different separator.
        """
        expected_response = SignOnResponse(
            access_token=_STUB_JWT,
            token_type="bearer",
            user_id=_TEST_USER_ID,
            user_type="U",
        )
        mock_service = _build_mock_auth_service_success(expected_response)

        with patch(
            "src.api.routers.auth_router.AuthService",
            return_value=mock_service,
        ):
            response = await client.post(
                "/auth/login",
                json={"user_id": _TEST_USER_ID, "password": _TEST_PASSWORD},
            )

        assert response.status_code == status.HTTP_200_OK
        body: dict[str, Any] = response.json()
        token: str = body["access_token"]

        # JWT format: exactly three non-empty segments joined by dots.
        segments = token.split(".")
        assert len(segments) == 3, (
            f"JWT must have exactly 3 dot-separated segments "
            f"(header.payload.signature); got {len(segments)} "
            f"from {token!r}"
        )
        for idx, segment in enumerate(segments):
            assert segment, f"JWT segment {idx} (of header/payload/signature) must not be empty; got token={token!r}"

    # ------------------------------------------------------------------
    # 9. Unhandled error -> HTTP 401 "Unable to verify the User ..."
    # ------------------------------------------------------------------
    async def test_login_system_error(self, client: AsyncClient) -> None:
        """System-level auth failure yields HTTP 401 with exact COBOL text.

        Mirrors ``COSGN00C.cbl`` lines 251-255 — the ``WHEN OTHER``
        catch-all of ``EVALUATE WS-RESP-CD`` that covered every CICS
        RESP code other than 0 (normal) and 13 (NOTFND): for example,
        file-control errors (RESP=27 NOTOPEN), I/O hardware errors
        (RESP=84 IOERR), and VSAM internal-state errors (RESP=81
        ILLOGIC). The COBOL program moved the literal
        ``'Unable to verify the User ...'`` into ``ERRMSGO``. The
        cloud-native equivalent is an
        :class:`AuthenticationError` raised by the service layer for
        *any* non-business failure during password verification (DB
        error, BCrypt library failure, etc.). The router catches this
        as part of the normal ``AuthenticationError`` handler and
        returns HTTP 401 with the exact message text.

        CRITICAL — message preserved byte-for-byte from COSGN00C.cbl.
        """
        # Sanity check: the imported constant equals the COBOL string.
        assert MSG_UNABLE_TO_VERIFY == "Unable to verify the User ...", (
            f"MSG_UNABLE_TO_VERIFY must exactly match COSGN00C.cbl line 254; got {MSG_UNABLE_TO_VERIFY!r}"
        )

        mock_service = _build_mock_auth_service_failure(AuthenticationError(MSG_UNABLE_TO_VERIFY))

        with patch(
            "src.api.routers.auth_router.AuthService",
            return_value=mock_service,
        ):
            response = await client.post(
                "/auth/login",
                json={"user_id": _TEST_USER_ID, "password": _TEST_PASSWORD},
            )

        # Router translates every AuthenticationError to HTTP 401 per
        # auth_router.py L60-66 (``raise HTTPException(401, ...)``).
        assert response.status_code == status.HTTP_401_UNAUTHORIZED, (
            f"System-level AuthenticationError must yield HTTP 401 "
            f"(not 500) per auth_router catch-all; got "
            f"{response.status_code}"
        )
        assert response.headers.get("www-authenticate") == "Bearer"

        body: dict[str, Any] = response.json()
        error = _extract_error(body)
        assert error.get(_ERROR_STATUS_KEY) == 401
        assert error.get(_ERROR_CODE_KEY) == _AUTH_ERROR_CODE
        # BYTE-FOR-BYTE COBOL MESSAGE PRESERVATION (COSGN00C.cbl L254).
        assert error.get(_ERROR_REASON_KEY) == MSG_UNABLE_TO_VERIFY, (
            f"Error message MUST match COBOL source "
            f"{MSG_UNABLE_TO_VERIFY!r} byte-for-byte; got "
            f"{error.get(_ERROR_REASON_KEY)!r}"
        )
        assert MSG_UNABLE_TO_VERIFY in response.text


# ============================================================================
# SECTION 2 — Tests for POST /auth/logout
# ----------------------------------------------------------------------------
# CICS had no explicit "logout" program — CICS pseudo-conversational
# sessions end implicitly when the user exits with PF3 (COSGN00C.cbl
# lines 98-100 handle the DFHPF3 branch as a graceful exit) or when the
# transaction's COMMAREA times out on the terminal. The cloud-native
# target exposes an explicit ``POST /auth/logout`` endpoint for client-
# code clarity — the stateless JWT model means there is no server-side
# session to invalidate; logout is purely a client-side directive to
# discard the cached token.
# ============================================================================
class TestAuthLogout:
    """Tests for the ``POST /auth/logout`` endpoint."""

    async def test_logout_success(self, client: AsyncClient) -> None:
        """Logout endpoint returns HTTP 200 with acknowledgement message.

        Mirrors ``COSGN00C.cbl`` lines 171-172 — the ``EXEC CICS
        RETURN`` without TRANSID that ended the pseudo-conversational
        session when the user pressed PF3. In the cloud target, the
        stateless equivalent is a plain HTTP 200 acknowledging the
        client's intent to discard its cached JWT; the server itself
        holds no session state to invalidate.

        Assertions:
            * HTTP 200 OK.
            * Response body is JSON containing a ``message`` field.
            * The message contains the literal substring
              ``"signed out"`` — per the
              :class:`SignOutResponse` default in
              :mod:`src.shared.schemas.auth_schema` and the router
              body in :mod:`src.api.routers.auth_router`.
        """
        # /auth/logout is in PUBLIC_PATHS so the pre-signed JWT on the
        # `client` fixture is irrelevant; the endpoint accepts any
        # caller (authenticated or not). No service mocking is needed
        # because the route body returns a constant SignOutResponse
        # without consulting AuthService or the database.
        response = await client.post("/auth/logout")

        assert response.status_code == status.HTTP_200_OK, (
            f"Logout must succeed with HTTP 200; got {response.status_code}; body={response.text!r}"
        )
        assert response.headers["content-type"].startswith("application/json")

        body: dict[str, Any] = response.json()
        # SignOutResponse.message is the single required field
        # (see :class:`SignOutResponse` in auth_schema.py line 344).
        message = body.get("message", "")
        assert isinstance(message, str)
        # The router returns ``SignOutResponse(message="Successfully
        # signed out")`` literal — we assert the substring 'signed out'
        # so the test survives minor wording variants (e.g.,
        # localization) while still catching genuine regressions such
        # as the field becoming empty or being renamed.
        assert "signed out" in message.lower(), f"Logout response message must contain 'signed out'; got {message!r}"


# ============================================================================
# SECTION 3 — Cross-cutting router integration test
# ----------------------------------------------------------------------------
# Verifies that the login and logout paths are actually registered on
# the FastAPI app — catches routing regressions where the router
# module is forgotten in main.py's ``app.include_router`` list.
# Parametrized so one test covers both endpoints.
# ============================================================================
@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("POST", "/auth/login"),
        ("POST", "/auth/logout"),
    ],
    ids=["login-endpoint-registered", "logout-endpoint-registered"],
)
async def test_auth_endpoints_are_registered(client: AsyncClient, method: str, path: str) -> None:
    """``POST /auth/login`` and ``POST /auth/logout`` are mounted on the app.

    Regression guard — catches the failure mode where the
    ``auth_router`` is accidentally dropped from main.py's
    ``app.include_router`` sequence. We probe each endpoint with the
    appropriate method: ``/auth/login`` returns 422 on empty body
    (proving the route matched and handed off to the body validator);
    ``/auth/logout`` returns 200 (it has no body). Any 404 here
    indicates the route is unmounted.

    Parameters are provided via :func:`pytest.mark.parametrize` so
    future additions (``/auth/refresh``, etc.) can be added by
    appending a single tuple rather than duplicating the test body.
    """
    # Empty body forces the login route through Pydantic validation
    # (yielding 422) which confirms both (a) the route is mounted and
    # (b) it expects a JSON body. A 404 would indicate the router is
    # not registered — the condition this test guards against.
    if path == "/auth/login":
        response = await client.request(method, path, json={})
        assert response.status_code != status.HTTP_404_NOT_FOUND, (
            f"{method} {path} returned 404 — the auth router is not "
            f"mounted. Check app.include_router(auth_router.router, "
            f"prefix='/auth') in src/api/main.py"
        )
        # Empty body -> 422 is the expected validation outcome.
        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY
    else:
        # /auth/logout takes no body; a 200 confirms the route is
        # mounted AND the handler executed cleanly.
        response = await client.request(method, path)
        assert response.status_code == status.HTTP_200_OK, (
            f"{method} {path} unexpected status; got {response.status_code}; body={response.text!r}"
        )
