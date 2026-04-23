# ============================================================================
# CardDemo - Unit tests for AuthService (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COSGN00C.cbl     - CICS sign-on program, transaction CC00
#                                (PROCESS-ENTER-KEY + READ-USER-SEC-FILE
#                                paragraphs, lines 108-260).
#   * app/cpy/CSUSR01Y.cpy     - SEC-USER-DATA record layout (80-byte VSAM
#                                KSDS): SEC-USR-ID PIC X(08),
#                                SEC-USR-FNAME PIC X(20),
#                                SEC-USR-LNAME PIC X(20),
#                                SEC-USR-PWD   PIC X(08),
#                                SEC-USR-TYPE  PIC X(01),
#                                SEC-USR-FILLER PIC X(23).
#   * app/cpy/COCOM01Y.cpy     - CARDDEMO-COMMAREA (96 bytes). Provides
#                                CDEMO-USER-ID PIC X(08) +
#                                CDEMO-USER-TYPE PIC X(01) with 88-level
#                                conditions: 88 CDEMO-USRTYP-ADMIN VALUE 'A',
#                                88 CDEMO-USRTYP-USER VALUE 'U'.
#   * app/cpy/CSMSG01Y.cpy     - System message constants
#                                (CCDA-MSG-THANK-YOU, CCDA-MSG-INVALID-KEY).
# ----------------------------------------------------------------------------
# Feature F-001: Sign-On / Authentication. Target implementation under
# test: src/api/services/auth_service.py (AuthService class). The three
# COBOL-exact error messages from COSGN00C.cbl are preserved byte-for-byte
# per AAP Section 0.7.1 "Preserve exact error messages from COBOL":
#
#   * 'Wrong Password. Try again ...'  (COSGN00C.cbl line 242, includes
#                                       the literal space before ellipsis)
#   * 'User not found. Try again ...'  (COSGN00C.cbl line 249)
#   * 'Unable to verify the User ...'  (COSGN00C.cbl line 254)
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
"""Unit tests for :class:`AuthService`.

Validates BCrypt password verification, JWT token generation, token
verification, and authentication failure handling converted from
``app/cbl/COSGN00C.cbl`` (CICS transaction ``CC00``, Feature F-001).

COBOL -> Python Verification Surface
------------------------------------
=============================================  ==========================================
COBOL paragraph / statement                    Python test (this module)
=============================================  ==========================================
``PROCESS-ENTER-KEY`` L108-140                 ``test_authenticate_success_*`` (entry)
``READ-USER-SEC-FILE`` L209-219                ``test_authenticate_user_not_found``
                                                (mocks ``scalar_one_or_none() is None``)
``EVALUATE WS-RESP-CD WHEN 0`` L222-239        ``test_authenticate_success_*``,
                                                ``test_jwt_token_*``
``SEC-USR-PWD = WS-USER-PWD`` L223             ``test_authenticate_wrong_password``
                                                (mocks BCrypt verify returning False)
``MOVE WS-USER-ID TO CDEMO-USER-ID`` L226       ``test_jwt_token_contains_correct_claims``
``MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE`` L227   ``test_jwt_token_contains_correct_claims``,
                                                ``test_authenticate_success_admin_user``
``XCTL PROGRAM('COADM01C')`` admin route L231   ``test_authenticate_success_admin_user``
``XCTL PROGRAM('COMEN01C')`` user  route L234   ``test_authenticate_success_regular_user``
``WHEN 13 (NOTFND)`` L247-251                  ``test_authenticate_user_not_found``
``Wrong Password`` L241-246                    ``test_authenticate_wrong_password``
``WHEN OTHER`` L252-256                        ``test_authenticate_unexpected_error``
``USERIDI = SPACES`` guard L117-122            ``test_authenticate_empty_user_id``
``PASSWDI = SPACES`` guard L123-127            ``test_authenticate_empty_password``
=============================================  ==========================================

JWT Token Payload Verification (replacing COMMAREA propagation)
---------------------------------------------------------------
The CICS COMMAREA (``CARDDEMO-COMMAREA`` from ``COCOM01Y.cpy``) that
previously carried ``CDEMO-USER-ID`` / ``CDEMO-USER-TYPE`` across program
transfers (``EXEC CICS XCTL PROGRAM('...')``) is replaced by a signed
JWT. The test suite verifies that:

* The JWT ``sub`` claim carries ``CDEMO-USER-ID`` (the authenticated
  user's ID).
* The custom ``user_type`` claim carries ``CDEMO-USER-TYPE`` -- either
  ``'A'`` (``CDEMO-USRTYP-ADMIN``) or ``'U'`` (``CDEMO-USRTYP-USER``).
* The standard ``exp`` claim is in the future, approximately
  :attr:`Settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES` minutes from issuance.

Test Design
-----------
* **Mocked database**: All tests use ``AsyncMock(spec=AsyncSession)``
  rather than a real database, so the test suite runs in milliseconds
  with no PostgreSQL dependency. The mock replicates the SQLAlchemy 2.x
  async contract (``execute()`` -> result object with
  ``scalar_one_or_none()``) that :class:`AuthService.authenticate` uses
  in place of ``EXEC CICS READ FILE('USRSEC')``.
* **Real BCrypt hashes**: ``sample_user`` and ``admin_user`` fixtures
  hold genuine BCrypt-hashed test passwords (via passlib's
  :class:`~passlib.context.CryptContext`), so the password verification
  path is exercised end-to-end without any mocking of
  :func:`~passlib.context.CryptContext.verify`.
* **Environment isolation**: An ``autouse`` fixture sets the minimum
  required Pydantic ``Settings`` environment variables (``DATABASE_URL``,
  ``DATABASE_URL_SYNC``, ``JWT_SECRET_KEY``) and resets the lazy
  ``_settings_cache`` before each test, so each test sees a consistent
  JWT signing key regardless of test execution order.

Test Coverage (16 functions across 4 phases)
--------------------------------------------
**Phase 3 -- Authentication Success (4 tests)**:
 1. :func:`test_authenticate_success_regular_user`   -- usr_type='U' path
 2. :func:`test_authenticate_success_admin_user`     -- usr_type='A' path
 3. :func:`test_jwt_token_contains_correct_claims`   -- sub/user_type/exp
 4. :func:`test_jwt_token_expiry`                    -- exp window

**Phase 4 -- Authentication Failure (5 tests)**:
 5. :func:`test_authenticate_user_not_found`         -- RESP=13 NOTFND
 6. :func:`test_authenticate_wrong_password`         -- BCrypt mismatch
 7. :func:`test_authenticate_unexpected_error`       -- RESP=OTHER
 8. :func:`test_authenticate_empty_user_id`          -- SPACES guard
 9. :func:`test_authenticate_empty_password`         -- SPACES guard

**Phase 5 -- Token Verification (3 tests)**:
10. :func:`test_verify_valid_token`                  -- happy path
11. :func:`test_verify_expired_token`                -- ExpiredSignature
12. :func:`test_verify_invalid_token`                -- garbage input

**Phase 6 -- Password Hashing (4 tests)**:
13. :func:`test_hash_password`                       -- BCrypt format
14. :func:`test_hash_password_differs_from_plaintext` -- hash != plain
15. :func:`test_verify_password_correct`             -- True on match
16. :func:`test_verify_password_incorrect`           -- False on mismatch

See Also
--------
* ``src/api/services/auth_service.py``   -- The service under test.
* ``src/shared/models/user_security.py`` -- ORM model queried by the
  service (from ``app/cpy/CSUSR01Y.cpy``).
* ``src/shared/schemas/auth_schema.py``  -- Pydantic request / response
  / token schemas (from ``app/cpy-bms/COSGN00.CPY`` +
  ``app/cpy/COCOM01Y.cpy``).
* AAP Section 0.7.1 -- Refactoring-Specific Rules (preserve exact
  error messages from COBOL).
* AAP Section 0.7.2 -- Security Requirements (BCrypt, JWT, IAM,
  Secrets Manager).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest
from jose import jwt
from passlib.context import CryptContext
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.services.auth_service import (
    MSG_UNABLE_TO_VERIFY,
    MSG_USER_NOT_FOUND,
    MSG_WRONG_PASSWORD,
    AuthenticationError,
    AuthService,
    InvalidTokenError,
    _reset_settings_cache,
)
from src.shared.models.user_security import UserSecurity
from src.shared.schemas.auth_schema import (
    SignOnRequest,
    SignOnResponse,
    TokenPayload,
)

# ============================================================================
# Module-level constants shared by fixtures and tests.
# ============================================================================

#: Deterministic JWT signing key used for every test. Must be non-empty
#: (Pydantic Settings rejects empty strings for required fields) but
#: otherwise is irrelevant to the assertions -- the same key is used for
#: both encoding (via :meth:`AuthService._create_access_token`) and
#: decoding (via :meth:`AuthService.verify_token`). Replaces the JWT
#: secret that in production is injected from AWS Secrets Manager
#: through the ECS task-definition ``secrets`` block.
_TEST_JWT_SECRET_KEY: str = "test-secret-key-for-unit-tests-only-not-for-production"

#: The HMAC-SHA256 algorithm used by :mod:`python-jose` to sign the JWT.
#: Mirrors :attr:`Settings.JWT_ALGORITHM`'s default value so that tokens
#: issued inside tests decode cleanly via the same algorithm.
_TEST_JWT_ALGORITHM: str = "HS256"

#: JWT access-token expiration in minutes. Mirrors the Settings default
#: (30) so that :func:`test_jwt_token_expiry` can assert the exp claim
#: lands inside the expected window without coupling to env state.
_TEST_JWT_EXPIRE_MINUTES: int = 30

#: Cleartext test password shared by :func:`sample_user` and
#: :func:`admin_user` fixtures. 8 characters to match the COBOL
#: ``SEC-USR-PWD PIC X(08)`` constraint that still applies at the
#: schema layer (``SignOnRequest.password`` has ``max_length=8``).
#: Safe to hard-code in tests -- corresponds to no real credential.
_TEST_PLAINTEXT_PASSWORD: str = "PASSWORD"

#: 8-character test user identifier matching ``SEC-USR-ID PIC X(08)``.
#: Right-padded like a VSAM KSDS key would be. Non-admin scenarios use
#: this value as-is; admin scenarios use ``_TEST_ADMIN_USER_ID`` below.
_TEST_USER_ID: str = "TESTUSER"

#: 8-character admin-user identifier. Exercises the
#: ``CDEMO-USRTYP-ADMIN`` (value ``'A'``) path in :class:`AuthService`
#: which in the COBOL original (``COSGN00C.cbl`` line 231) issues
#: ``EXEC CICS XCTL PROGRAM('COADM01C')`` -- now expressed as
#: ``user_type='A'`` in the response / JWT claim.
_TEST_ADMIN_USER_ID: str = "ADMIN001"

#: A dedicated BCrypt context for the tests. Uses exactly the same
#: scheme (``bcrypt``) that :mod:`src.api.services.auth_service`
#: configures on its module-level ``pwd_context``, so hashes produced
#: here verify cleanly against hashes produced there.
_TEST_PWD_CONTEXT: CryptContext = CryptContext(schemes=["bcrypt"], deprecated="auto")


# ============================================================================
# Phase 2: Test Fixtures
# ============================================================================
#
# Fixtures are kept module-local (not moved to a ``conftest.py``) so
# that this file is self-contained and the AAP "isolate new
# implementations in dedicated files/modules" rule (Section 0.7.1) is
# honored. The test-services package ``__init__.py`` explicitly
# permits this pattern ("fixtures live in ... subpackage-local
# conftest.py files so that pytest's hierarchical fixture resolution
# applies and fixtures stay close to the tests that use them").
# ============================================================================


@pytest.fixture(autouse=True)
def _set_jwt_settings_env(monkeypatch: pytest.MonkeyPatch) -> Any:
    """Set the Settings env vars required by :class:`AuthService` under test.

    The :class:`src.shared.config.settings.Settings` Pydantic model
    declares ``DATABASE_URL``, ``DATABASE_URL_SYNC``, and
    ``JWT_SECRET_KEY`` as **required** fields with no defaults (see
    Settings docstring, AAP Section 0.7.2 CWE-798 protection). Without
    these env vars in place, the lazy :func:`_get_settings` call from
    ``AuthService._create_access_token`` / ``AuthService.verify_token``
    would raise ``pydantic.ValidationError`` at first use.

    The ``autouse=True`` marker makes this fixture apply to every test
    in the module, so individual tests need not care about environment
    state. The accompanying :func:`_reset_settings_cache` call flushes
    the module-level Settings singleton before each test, ensuring each
    test sees a freshly loaded Settings instance that reflects the
    pinned values below.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        pytest fixture for safe environment mutation. All patched
        values are automatically restored at test teardown.
    """
    # Pinned JWT parameters -- consumed by AuthService._create_access_token
    # (signing) and AuthService.verify_token (decoding) via the lazy
    # _get_settings() accessor.
    monkeypatch.setenv("JWT_SECRET_KEY", _TEST_JWT_SECRET_KEY)
    monkeypatch.setenv("JWT_ALGORITHM", _TEST_JWT_ALGORITHM)
    monkeypatch.setenv(
        "JWT_ACCESS_TOKEN_EXPIRE_MINUTES",
        str(_TEST_JWT_EXPIRE_MINUTES),
    )
    # Placeholder database URLs -- required by Settings schema but never
    # actually used by the mocked AsyncSession in these unit tests.
    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql+asyncpg://test:test@localhost:5432/test",
    )
    monkeypatch.setenv(
        "DATABASE_URL_SYNC",
        "postgresql+psycopg2://test:test@localhost:5432/test",
    )
    # Flush the Settings singleton before AND after each test so that
    # other test modules that run in the same pytest session do not
    # inherit this test module's configuration.
    _reset_settings_cache()
    yield
    _reset_settings_cache()


@pytest.fixture
def mock_db_session() -> AsyncMock:
    """Create a mocked :class:`~sqlalchemy.ext.asyncio.AsyncSession`.

    Replaces the real database connection that :class:`AuthService`
    uses to look up ``user_security`` rows. The mock's ``execute()``
    method is configured as an ``AsyncMock`` so that
    ``await self.db.execute(stmt)`` inside
    :meth:`AuthService.authenticate` resolves without a real round
    trip. The returned mock result object exposes
    ``scalar_one_or_none()`` as a synchronous :class:`MagicMock`,
    matching the real SQLAlchemy 2.x async API (``execute()`` is
    ``async``; ``scalar_one_or_none()`` on the Result is sync).

    The COBOL original (``COSGN00C.cbl`` line 211-219) used the
    CICS-managed ``USRSEC`` VSAM file handle -- also implicitly
    session-scoped. The mock plays the same role: it is owned by the
    test, injected into the service at construction time, and has no
    persistent state beyond the test invocation.

    Returns
    -------
    AsyncMock
        A mock ``AsyncSession`` with ``execute()`` configured to
        return a result object whose ``scalar_one_or_none()`` is
        itself a mock -- individual tests override this mock's return
        value to simulate user-found, user-not-found, or driver-error
        scenarios.
    """
    session = AsyncMock(spec=AsyncSession)

    # Configure execute() to return a result object. The result has a
    # synchronous scalar_one_or_none() method (matching SQLAlchemy
    # 2.x's Result API), so we use plain MagicMock for it.
    result = MagicMock()
    result.scalar_one_or_none = MagicMock(return_value=None)
    session.execute = AsyncMock(return_value=result)
    return session


@pytest.fixture
def auth_service(mock_db_session: AsyncMock) -> AuthService:
    """Instantiate :class:`AuthService` with the mocked session.

    :class:`AuthService`'s constructor takes a single ``db``
    parameter (``AsyncSession``) and stores it on ``self.db``. The
    service intentionally has no other state, so this fixture
    produces a fresh service for each test with a mock session that
    the test can configure via ``mock_db_session.execute.return_value
    .scalar_one_or_none.return_value = ...``.

    Parameters
    ----------
    mock_db_session : AsyncMock
        The mocked session produced by :func:`mock_db_session`.

    Returns
    -------
    AuthService
        A fresh service instance wired to the mocked session.
    """
    return AuthService(db=mock_db_session)


@pytest.fixture
def sample_user() -> UserSecurity:
    """Build a sample regular-user :class:`UserSecurity` row.

    Maps each field to its COBOL copybook counterpart:

    =================  ==================  ==================
    COBOL Field        ORM Attribute       Test Value
    =================  ==================  ==================
    SEC-USR-ID         user_id             ``"TESTUSER"``
    SEC-USR-FNAME      first_name          ``"Test"``
    SEC-USR-LNAME      last_name           ``"User"``
    SEC-USR-PWD (hash) password            BCrypt of PASSWORD
    SEC-USR-TYPE       usr_type            ``"U"``
    =================  ==================  ==================

    The ``usr_type='U'`` value exercises the ``CDEMO-USRTYP-USER``
    88-level path from ``app/cpy/COCOM01Y.cpy``. In the COBOL
    original (``COSGN00C.cbl`` line 234) this branch issued
    ``EXEC CICS XCTL PROGRAM('COMEN01C')`` (main menu); the Python
    equivalent sets ``user_type='U'`` in the JWT + response.

    Returns
    -------
    UserSecurity
        A detached ORM instance (not added to any session).
    """
    return UserSecurity(
        user_id=_TEST_USER_ID,
        first_name="Test",
        last_name="User",
        # Real BCrypt hash, produced at fixture-construction time so
        # that AuthService.verify_password (which calls pwd_context
        # .verify under the hood) has a genuine digest to work with.
        password=_TEST_PWD_CONTEXT.hash(_TEST_PLAINTEXT_PASSWORD),
        usr_type="U",  # CDEMO-USRTYP-USER from COCOM01Y.cpy
    )


@pytest.fixture
def admin_user() -> UserSecurity:
    """Build a sample admin :class:`UserSecurity` row.

    Identical to :func:`sample_user` except for the one-character
    ``usr_type`` field, which is set to ``'A'`` to exercise the
    ``CDEMO-USRTYP-ADMIN`` 88-level path from COCOM01Y.cpy. In the
    COBOL original (``COSGN00C.cbl`` line 231) this branch issued
    ``EXEC CICS XCTL PROGRAM('COADM01C')`` (admin menu); the Python
    equivalent sets ``user_type='A'`` in the JWT + response, and the
    router / middleware enforces admin-only access on the
    ``/admin/*`` endpoints.

    Returns
    -------
    UserSecurity
        A detached ORM instance with ``usr_type='A'``.
    """
    return UserSecurity(
        user_id=_TEST_ADMIN_USER_ID,
        first_name="Admin",
        last_name="User",
        password=_TEST_PWD_CONTEXT.hash(_TEST_PLAINTEXT_PASSWORD),
        usr_type="A",  # CDEMO-USRTYP-ADMIN from COCOM01Y.cpy
    )


# ============================================================================
# Helper functions (test-only)
# ============================================================================


def _configure_db_user(mock_session: AsyncMock, user: UserSecurity | None) -> None:
    """Wire ``mock_session.execute(...).scalar_one_or_none()`` to return ``user``.

    Small helper that consolidates the mock-setup boilerplate so that
    each test reads as ``arrange / act / assert`` rather than three
    lines of MagicMock chaining. The triple-drill-down (``execute``
    -> result -> ``scalar_one_or_none``) mirrors the real SQLAlchemy
    2.x async call sequence executed inside
    :meth:`AuthService.authenticate` step 1.

    Parameters
    ----------
    mock_session : AsyncMock
        The mocked session (typically from :func:`mock_db_session`).
    user : UserSecurity | None
        The row to return from ``scalar_one_or_none()`` -- ``None``
        simulates the COBOL ``RESP=13 (NOTFND)`` branch.
    """
    result = MagicMock()
    result.scalar_one_or_none = MagicMock(return_value=user)
    mock_session.execute = AsyncMock(return_value=result)


# ============================================================================
# Phase 3: Authentication Success Tests
# ============================================================================
#
# These tests exercise the COBOL "WHEN 0 (RESP=0)" + "SEC-USR-PWD =
# WS-USER-PWD" success paths from ``COSGN00C.cbl`` (lines 222-239).
# They verify:
#   * End-to-end round-trip: request -> DB lookup -> BCrypt verify ->
#     JWT issuance -> SignOnResponse envelope.
#   * COMMAREA replacement: JWT claims carry CDEMO-USER-ID and
#     CDEMO-USER-TYPE as ``sub`` / ``user_id`` / ``user_type``.
#   * User-type branching: 'U' (COMEN01C path) vs 'A' (COADM01C path).
#   * Token expiration: ``exp`` claim is JWT_ACCESS_TOKEN_EXPIRE_MINUTES
#     in the future.
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_success_regular_user(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Sign-on succeeds for a regular user (``usr_type='U'``).

    Arrange
        The mocked database returns ``sample_user`` (a
        :class:`UserSecurity` row with ``usr_type='U'``).
    Act
        ``auth_service.authenticate(SignOnRequest(...))`` is called
        with the matching cleartext password.
    Assert
        The service returns a :class:`SignOnResponse` with:

        * ``access_token`` -- a non-empty JWT string (three
          base64url-encoded segments separated by ``.``).
        * ``token_type == "bearer"``.
        * ``user_id`` = the authenticated user's ID.
        * ``user_type == "U"`` (``CDEMO-USRTYP-USER``).

    COBOL mapping
    -------------
    Mirrors the success path through ``COSGN00C.cbl`` lines 222-239:
    ``EVALUATE WS-RESP-CD WHEN 0`` -> ``IF SEC-USR-PWD = WS-USER-PWD`` ->
    ``MOVE WS-USER-ID TO CDEMO-USER-ID`` -> ``MOVE SEC-USR-TYPE TO
    CDEMO-USER-TYPE`` -> ``EXEC CICS XCTL PROGRAM('COMEN01C')``. The
    CICS XCTL to ``COMEN01C`` (main menu) is replaced in the cloud-
    native architecture by the router returning the SignOnResponse
    with ``user_type='U'``; the client routes accordingly.
    """
    # Arrange: configure the mock DB to return a regular user.
    _configure_db_user(mock_db_session, sample_user)
    request = SignOnRequest(
        user_id=_TEST_USER_ID,
        password=_TEST_PLAINTEXT_PASSWORD,
    )

    # Act: invoke the authenticate method under test.
    response = await auth_service.authenticate(request)

    # Assert: response structure and field values.
    assert isinstance(response, SignOnResponse), (
        "authenticate() must return a SignOnResponse envelope (maps "
        "to the CICS SEND MAP('COSGN0AO') response post-sign-on)."
    )
    assert isinstance(response.access_token, str) and response.access_token, (
        "access_token must be a non-empty JWT string (JWT replaces CICS COMMAREA session state from COCOM01Y.cpy)."
    )
    # Compact JWT serialization produces exactly three dot-separated
    # base64url segments: header.payload.signature. Anything else is
    # malformed -- validate the shape without decoding.
    assert response.access_token.count(".") == 2, (
        f"access_token must be a compact-serialized JWT (three "
        f"base64url segments separated by '.'); got "
        f"{response.access_token!r}"
    )
    assert response.token_type == "bearer", f"token_type must be OAuth2-compliant 'bearer'; got {response.token_type!r}"
    assert response.user_id == _TEST_USER_ID, (
        f"user_id must echo the authenticated user (from CDEMO-USER-ID PIC X(08)); got {response.user_id!r}"
    )
    assert response.user_type == "U", (
        f"user_type must be 'U' for a regular user (CDEMO-USRTYP-USER 88-level); got {response.user_type!r}"
    )

    # Verify the DB was actually queried (proves authenticate()
    # reached step 1 / READ-USER-SEC-FILE paragraph).
    assert mock_db_session.execute.await_count == 1, (
        "authenticate() must call db.execute() exactly once per "
        "sign-on attempt (mirrors EXEC CICS READ FILE('USRSEC'))."
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_success_admin_user(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    admin_user: UserSecurity,
) -> None:
    """Sign-on succeeds for an admin user (``usr_type='A'``).

    Arrange
        The mocked database returns ``admin_user`` (a
        :class:`UserSecurity` row with ``usr_type='A'``).
    Act
        ``auth_service.authenticate(SignOnRequest(...))`` is called
        with the matching cleartext password.
    Assert
        ``response.user_type == "A"`` (``CDEMO-USRTYP-ADMIN``).

    COBOL mapping
    -------------
    Mirrors the admin branch in ``COSGN00C.cbl`` lines 229-231:
    ``IF CDEMO-USRTYP-ADMIN`` -> ``EXEC CICS XCTL PROGRAM('COADM01C')``.
    The CICS XCTL to ``COADM01C`` (admin menu) is replaced by the
    router surfacing ``user_type='A'`` to the client, which then
    routes to admin-only endpoints.
    """
    # Arrange: configure the mock DB to return an admin user.
    _configure_db_user(mock_db_session, admin_user)
    request = SignOnRequest(
        user_id=_TEST_ADMIN_USER_ID,
        password=_TEST_PLAINTEXT_PASSWORD,
    )

    # Act.
    response = await auth_service.authenticate(request)

    # Assert.
    assert response.user_id == _TEST_ADMIN_USER_ID, f"user_id must echo the admin user's ID; got {response.user_id!r}"
    assert response.user_type == "A", (
        f"user_type must be 'A' for an admin user (CDEMO-USRTYP-ADMIN 88-level); got {response.user_type!r}"
    )
    assert response.access_token, "access_token must be issued for admin users too."
    assert response.token_type == "bearer"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_jwt_token_contains_correct_claims(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """JWT carries ``sub`` / ``user_id`` / ``user_type`` claims correctly.

    This is the CORE test for COMMAREA replacement: the JWT must
    encode the same identity fields that ``CARDDEMO-COMMAREA`` (from
    ``COCOM01Y.cpy``) carried across CICS program transfers.

    Arrange
        The mocked DB returns ``sample_user``.
    Act
        Call ``authenticate()`` and then decode the resulting JWT
        with the test secret key.
    Assert
        * ``sub == user_id`` (JWT-standard subject claim =
          ``CDEMO-USER-ID``).
        * ``user_id == user_id`` (duplicate claim consumed by the
          JWT middleware in ``src/api/middleware/auth.py``).
        * ``user_type == "U"`` (``CDEMO-USER-TYPE``).
        * ``exp`` exists, is an ``int``, and is in the future
          (Unix epoch seconds).

    COBOL mapping
    -------------
    Replaces the CICS COMMAREA population at ``COSGN00C.cbl`` lines
    224-228:
        ``MOVE WS-USER-ID    TO CDEMO-USER-ID``
        ``MOVE SEC-USR-TYPE  TO CDEMO-USER-TYPE``
    The JWT claims carry the same two fields so that downstream
    endpoints (formerly CICS programs reached via XCTL) can read the
    authenticated identity statelessly.
    """
    # Arrange.
    _configure_db_user(mock_db_session, sample_user)
    request = SignOnRequest(
        user_id=_TEST_USER_ID,
        password=_TEST_PLAINTEXT_PASSWORD,
    )

    # Act.
    response = await auth_service.authenticate(request)

    # Decode the JWT using the same secret + algorithm that
    # AuthService._create_access_token signed it with.
    decoded: dict[str, Any] = jwt.decode(
        response.access_token,
        _TEST_JWT_SECRET_KEY,
        algorithms=[_TEST_JWT_ALGORITHM],
    )

    # Assert each claim individually for clearer failure messages.
    assert decoded.get("sub") == _TEST_USER_ID, (
        f"JWT 'sub' claim must equal CDEMO-USER-ID from COCOM01Y.cpy "
        f"(COSGN00C.cbl line 226: MOVE WS-USER-ID TO CDEMO-USER-ID); "
        f"got {decoded.get('sub')!r}"
    )
    assert decoded.get("user_id") == _TEST_USER_ID, (
        f"JWT 'user_id' claim must equal the authenticated user's ID "
        f"(consumed by src/api/middleware/auth.py); got "
        f"{decoded.get('user_id')!r}"
    )
    assert decoded.get("user_type") == "U", (
        f"JWT 'user_type' claim must equal CDEMO-USER-TYPE from "
        f"COCOM01Y.cpy (COSGN00C.cbl line 227: MOVE SEC-USR-TYPE TO "
        f"CDEMO-USER-TYPE); got {decoded.get('user_type')!r}"
    )
    assert "exp" in decoded, (
        "JWT must carry a standard 'exp' expiration claim (RFC 7519 "
        "4.1.4). Replaces the CICS RTIMOUT transaction timeout."
    )
    assert isinstance(decoded["exp"], int), (
        f"JWT 'exp' claim must be an int (epoch seconds); got {type(decoded['exp']).__name__}"
    )
    # The exp claim must be in the future when the token is fresh.
    now_epoch = int(datetime.now(UTC).timestamp())
    assert decoded["exp"] > now_epoch, (
        f"JWT 'exp' claim must be in the future at issuance time; got exp={decoded['exp']}, now={now_epoch}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_jwt_token_expiry(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """JWT ``exp`` claim lands within the expected expiration window.

    Arrange
        Record ``now`` just before issuing the token so we can bracket
        the expected ``exp`` value.
    Act
        Call ``authenticate()`` and decode the resulting JWT.
    Assert
        The ``exp`` claim is within 60 seconds of
        ``now + JWT_ACCESS_TOKEN_EXPIRE_MINUTES`` (the 60-second
        slack accommodates test-machine latency and the few-ms delay
        between computing ``now`` here and inside the service).

    COBOL mapping
    -------------
    No direct COBOL equivalent -- in the mainframe architecture,
    session lifetime was controlled by the CICS region's RTIMOUT /
    TASKDATAKEY settings on the CSD transaction definition, not by
    the program. JWT ``exp`` provides the same mechanism in the cloud
    environment and is pinned by
    :attr:`Settings.JWT_ACCESS_TOKEN_EXPIRE_MINUTES`.
    """
    _configure_db_user(mock_db_session, sample_user)
    request = SignOnRequest(
        user_id=_TEST_USER_ID,
        password=_TEST_PLAINTEXT_PASSWORD,
    )

    # Record a timestamp just before the service computes its own
    # ``datetime.now(UTC)``. The gap is at most the time to await
    # ``authenticate()``, which in a pure unit test (mocked DB +
    # mocked session) is sub-millisecond.
    before_issue = datetime.now(UTC)

    # Act.
    response = await auth_service.authenticate(request)

    after_issue = datetime.now(UTC)

    decoded = jwt.decode(
        response.access_token,
        _TEST_JWT_SECRET_KEY,
        algorithms=[_TEST_JWT_ALGORITHM],
    )

    # Compute the expected window for the exp claim.
    exp_minutes = timedelta(minutes=_TEST_JWT_EXPIRE_MINUTES)
    # The exp MUST be at least `before_issue + exp_minutes` and at
    # most `after_issue + exp_minutes` (jose converts the datetime
    # to int seconds, so we compare in int seconds).
    min_expected_exp = int((before_issue + exp_minutes).timestamp())
    max_expected_exp = int((after_issue + exp_minutes).timestamp())
    actual_exp = decoded["exp"]

    # Allow 1-second slack on both sides to absorb float -> int
    # truncation that jose performs on the exp claim.
    assert min_expected_exp - 1 <= actual_exp <= max_expected_exp + 1, (
        f"JWT 'exp' claim must fall within "
        f"[{min_expected_exp - 1}, {max_expected_exp + 1}] "
        f"(approximately {_TEST_JWT_EXPIRE_MINUTES} minutes from "
        f"issuance); got {actual_exp}"
    )


# ============================================================================
# Phase 4: Authentication Failure Tests
# ============================================================================
#
# These tests exercise the COBOL failure branches from
# ``COSGN00C.cbl``:
#   * RESP=13 (NOTFND)  -> MSG_USER_NOT_FOUND (lines 247-251)
#   * Password mismatch -> MSG_WRONG_PASSWORD (lines 241-246)
#   * RESP=OTHER        -> MSG_UNABLE_TO_VERIFY (lines 252-256)
#   * Empty input       -> request-level validation (COBOL lines 117-127)
#
# Every error-message assertion in these tests compares against the
# ``MSG_*`` constants imported from the service module, which in turn
# hold the byte-exact COBOL-equivalent text from
# ``src/api/services/auth_service.py`` lines 198, 203, 208. Changing
# those strings will simultaneously break these tests AND any
# downstream code reading ``AuthenticationError.message`` -- which is
# exactly the protection we want for AAP Section 0.7.1 "preserve
# existing business logic without modification".
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_user_not_found(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
) -> None:
    """``AuthenticationError(MSG_USER_NOT_FOUND)`` raised when user absent.

    Arrange
        The mocked DB returns ``None`` from
        ``scalar_one_or_none()`` (default behavior of the
        ``mock_db_session`` fixture).
    Act
        Call ``authenticate()`` with any user_id/password combination.
    Assert
        * :class:`AuthenticationError` is raised.
        * ``exc.message`` is exactly ``MSG_USER_NOT_FOUND``
          (``"User not found. Try again ..."``).
        * ``str(exc) == MSG_USER_NOT_FOUND`` (service docstring:
          "``str(exc) == message`` -- convenient for structured
          logging").
        * ``exc.args[0]`` is also the message (backwards-compat
          path documented in the service class).
        * The HTTP 401 mapping is handled by
          ``src/api/middleware/error_handler.py`` and is NOT tested
          here (that layer has its own tests).

    COBOL mapping
    -------------
    Replaces ``COSGN00C.cbl`` lines 247-251::

        WHEN DFHRESP(NOTFND)
            MOVE 'User not found. Try again ...' TO WS-MESSAGE
            PERFORM SEND-SIGNON-SCREEN

    In the CICS world this caused the sign-on screen to re-display
    with the error in the WS-MESSAGE area. In the cloud-native
    architecture the router catches :class:`AuthenticationError` and
    maps it to HTTP 401 with the same message in the response body.
    """
    # Arrange: scalar_one_or_none() already returns None by default
    # (set in mock_db_session fixture). Being explicit here documents
    # the test intent.
    mock_db_session.execute.return_value.scalar_one_or_none.return_value = None

    request = SignOnRequest(
        user_id="NOBODY",
        password="ANYPWD",
    )

    # Act / Assert: pytest.raises captures the AuthenticationError.
    with pytest.raises(AuthenticationError) as exc_info:
        await auth_service.authenticate(request)

    # Verify the message is exactly MSG_USER_NOT_FOUND (byte-for-byte
    # equal to the COBOL WS-MESSAGE literal at COSGN00C.cbl line 249).
    assert exc_info.value.message == MSG_USER_NOT_FOUND, (
        f"AuthenticationError.message must be MSG_USER_NOT_FOUND "
        f"(COSGN00C.cbl line 249: 'User not found. Try again ...'); "
        f"got {exc_info.value.message!r}"
    )
    assert exc_info.value.message == "User not found. Try again ...", (
        "MSG_USER_NOT_FOUND constant drifted from the COBOL-exact "
        "text. This is a regression of AAP Section 0.7.1 (preserve "
        "existing business logic without modification)."
    )
    # str(exc) is used in server-side logs; per the class docstring
    # it must equal the message text verbatim.
    assert str(exc_info.value) == MSG_USER_NOT_FOUND, (
        f"str(AuthenticationError) must equal its .message for structured logging; got {str(exc_info.value)!r}"
    )
    # args[0] path documented in the service class as an alternative
    # to attribute access.
    assert exc_info.value.args[0] == MSG_USER_NOT_FOUND, (
        f"AuthenticationError.args[0] must also be the message "
        f"(callers may compare args[0] against MSG_* constants); "
        f"got {exc_info.value.args[0]!r}"
    )

    # Verify the DB WAS queried before the error was raised
    # (proves we didn't short-circuit the lookup).
    assert mock_db_session.execute.await_count == 1


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_wrong_password(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """``AuthenticationError(MSG_WRONG_PASSWORD)`` raised on bad password.

    Arrange
        The mocked DB returns ``sample_user`` (a real
        :class:`UserSecurity` with a real BCrypt-hashed
        ``"PASSWORD"``).
    Act
        Call ``authenticate()`` with ``password="WRONGPWD"``.
    Assert
        * :class:`AuthenticationError` is raised.
        * ``exc.message == MSG_WRONG_PASSWORD``
          (``"Wrong Password. Try again ..."``).
        * The HTTP 401 mapping is handled by
          ``src/api/middleware/error_handler.py`` and is NOT
          tested here.

    COBOL mapping
    -------------
    Replaces ``COSGN00C.cbl`` lines 241-246::

        IF SEC-USR-PWD = WS-USER-PWD
            ...  (success branch)
        ELSE
            MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
            PERFORM SEND-SIGNON-SCREEN
        END-IF

    This test uses a REAL BCrypt hash (via the ``sample_user``
    fixture which hashes ``"PASSWORD"`` through
    ``CryptContext(schemes=['bcrypt']).hash``) so it exercises the
    actual ``pwd_context.verify()`` path rather than a mock. That
    catches drift between ``pwd_context`` configuration and the
    hashing scheme used at user creation time.
    """
    # Arrange.
    _configure_db_user(mock_db_session, sample_user)
    request = SignOnRequest(
        user_id=_TEST_USER_ID,
        password="WRONGPWD",
    )

    # Act / Assert.
    with pytest.raises(AuthenticationError) as exc_info:
        await auth_service.authenticate(request)

    assert exc_info.value.message == MSG_WRONG_PASSWORD, (
        f"AuthenticationError.message must be MSG_WRONG_PASSWORD "
        f"(COSGN00C.cbl line 243: 'Wrong Password. Try again ...'); "
        f"got {exc_info.value.message!r}"
    )
    assert exc_info.value.message == "Wrong Password. Try again ...", (
        "MSG_WRONG_PASSWORD constant drifted from the COBOL-exact text. This violates AAP Section 0.7.1."
    )
    # str(exc) must surface the message for structured logging.
    assert str(exc_info.value) == MSG_WRONG_PASSWORD
    # Confirm the DB was queried once (not twice -- proves
    # authenticate() did not retry after the password mismatch).
    assert mock_db_session.execute.await_count == 1


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_unexpected_error(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
) -> None:
    """``AuthenticationError(MSG_UNABLE_TO_VERIFY)`` on DB failure.

    Arrange
        The mocked DB's ``execute()`` raises a generic
        :class:`RuntimeError` (stand-in for any driver-level,
        network, or connection-pool error).
    Act
        Call ``authenticate()``.
    Assert
        * :class:`AuthenticationError` is raised (NOT the original
          RuntimeError -- the service must wrap it).
        * ``exc.message == MSG_UNABLE_TO_VERIFY``
          (``"Unable to verify the User ..."``).
        * ``exc.__cause__`` is the original RuntimeError (preserves
          the stack for server-side diagnostics while hiding
          database internals from the client).
        * The HTTP 401 mapping is handled by
          ``src/api/middleware/error_handler.py`` and is NOT
          tested here.

    COBOL mapping
    -------------
    Replaces ``COSGN00C.cbl`` lines 252-256::

        WHEN OTHER
            MOVE 'Unable to verify the User ...' TO WS-MESSAGE
            PERFORM SEND-SIGNON-SCREEN

    In CICS, ``WHEN OTHER`` caught any non-zero non-NOTFND RESP-CD
    from ``EXEC CICS READ FILE('USRSEC')``. In the Python port, any
    exception escaping ``self.db.execute(stmt)`` is similarly
    collapsed into a generic "Unable to verify" to avoid leaking
    DB schema or connection details to the client.
    """
    # Arrange: make the DB raise to simulate RESP=OTHER.
    db_failure = RuntimeError("simulated database connection error")
    mock_db_session.execute.side_effect = db_failure

    request = SignOnRequest(
        user_id=_TEST_USER_ID,
        password=_TEST_PLAINTEXT_PASSWORD,
    )

    # Act / Assert.
    with pytest.raises(AuthenticationError) as exc_info:
        await auth_service.authenticate(request)

    assert exc_info.value.message == MSG_UNABLE_TO_VERIFY, (
        f"AuthenticationError.message must be MSG_UNABLE_TO_VERIFY "
        f"(COSGN00C.cbl line 254: 'Unable to verify the User ...'); "
        f"got {exc_info.value.message!r}"
    )
    assert exc_info.value.message == "Unable to verify the User ...", (
        "MSG_UNABLE_TO_VERIFY constant drifted from the COBOL-exact text. This violates AAP Section 0.7.1."
    )
    # str(exc) must surface the message for structured logging.
    assert str(exc_info.value) == MSG_UNABLE_TO_VERIFY
    # Exception chaining ('raise ... from exc') must preserve the
    # original cause for server logs.
    assert exc_info.value.__cause__ is db_failure, (
        "AuthenticationError must chain the original DB exception via "
        "'raise AuthenticationError(...) from exc' so that "
        "server-side logs can show the root cause (the client-facing "
        "message stays generic)."
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_empty_user_id(
    mock_db_session: AsyncMock,
) -> None:
    """Empty ``user_id`` is rejected at the :class:`SignOnRequest` layer.

    Arrange
        N/A -- the validation fires during Pydantic model
        construction, so we never even reach :meth:`AuthService.authenticate`.
    Act
        Attempt to construct ``SignOnRequest(user_id="", password=...)``.
    Assert
        :class:`ValueError` / :class:`pydantic.ValidationError` is
        raised (Pydantic v2 raises its own ValidationError which is a
        subclass of ValueError).

    COBOL mapping
    -------------
    Replaces ``COSGN00C.cbl`` lines 117-122::

        IF (USERIDI = SPACES OR LOW-VALUES)
            MOVE 'Please enter User ID ...' TO WS-MESSAGE
            PERFORM SEND-SIGNON-SCREEN
        END-IF

    The COBOL-side check on the BMS field USERIDI is replaced by
    Pydantic field validation on ``SignOnRequest.user_id`` (see
    :mod:`src.shared.schemas.auth_schema`). The validation happens
    BEFORE the service sees the request, which is the modern
    equivalent of a BMS ATTRIBUTE-driven field validation firing
    pre-dispatch.
    """
    # Act / Assert: constructing the request with an empty user_id
    # must fail. Pydantic v2's ValidationError is a subclass of
    # ValueError, so catching ValueError is portable across Pydantic
    # versions and captures whatever SignOnRequest's validators
    # raise.
    with pytest.raises(ValueError) as exc_info:
        SignOnRequest(
            user_id="",
            password=_TEST_PLAINTEXT_PASSWORD,
        )

    # The validator message should reference the empty/blank rule so
    # that a developer reading the failure can trace it back to
    # USERIDI = SPACES. We don't pin the exact text (it belongs to
    # auth_schema.py) but we do confirm user_id is named in the error.
    assert "user_id" in str(exc_info.value).lower(), (
        f"Validation error must mention the offending field ('user_id'); got {exc_info.value!s}"
    )

    # Secondary guarantee: if somehow validation were skipped (e.g.,
    # a regression relaxed the validator), the service layer must
    # still refuse to issue a token. We exercise that by giving
    # authenticate() a request with whitespace-only user_id via a
    # MagicMock-like object that bypasses Pydantic. However, since
    # Pydantic is the contract, we stop here: the pytest.raises above
    # is the authoritative check.


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_empty_password(
    mock_db_session: AsyncMock,
) -> None:
    """Empty ``password`` is rejected at the :class:`SignOnRequest` layer.

    Arrange
        N/A -- validation fires during model construction.
    Act
        Attempt to construct ``SignOnRequest(user_id=..., password="")``.
    Assert
        :class:`ValueError` / :class:`pydantic.ValidationError` is
        raised.

    COBOL mapping
    -------------
    Replaces ``COSGN00C.cbl`` lines 123-127::

        IF (PASSWDI = SPACES OR LOW-VALUES)
            MOVE 'Please enter Password ...' TO WS-MESSAGE
            PERFORM SEND-SIGNON-SCREEN
        END-IF

    As with :func:`test_authenticate_empty_user_id`, the COBOL-side
    check on BMS field ``PASSWDI`` is promoted to a Pydantic
    validator on :attr:`SignOnRequest.password`.
    """
    # Act / Assert.
    with pytest.raises(ValueError) as exc_info:
        SignOnRequest(
            user_id=_TEST_USER_ID,
            password="",
        )

    assert "password" in str(exc_info.value).lower(), (
        f"Validation error must mention the offending field ('password'); got {exc_info.value!s}"
    )


# ============================================================================
# Phase 4: UPPER-CASE Normalization Regression Tests
# ============================================================================
#
# These tests protect against regression of MAJOR finding #2 from the
# Checkpoint 3 code review, which flagged that the original Python
# implementation did NOT apply ``FUNCTION UPPER-CASE`` to ``user_id``
# and ``password`` before the database lookup and BCrypt verification.
# The COBOL original (``app/cbl/COSGN00C.cbl`` lines 132-135) does::
#
#     MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)
#             TO WS-USER-ID
#     MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI)
#             TO WS-USER-PWD
#
# before the ``EXEC CICS READ FILE('USRSEC')`` call. The seed-data
# loader in ``db/migrations/V3__seed_data.sql`` persists user IDs in
# upper-case AND BCrypt-hashes upper-case plaintext passwords --
# without UPPER-CASE normalization on the request side, legacy users
# accustomed to typing lowercase on the CICS terminal would be locked
# out. That is an AAP §0.7.1 "Preserve all existing functionality
# exactly as-is" violation. The guard below catches any future edit
# that removes the ``.upper()`` calls in
# :meth:`AuthService.authenticate` (lines 499-500).
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_lowercase_credentials_succeed(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Lowercase ``user_id`` + ``password`` authenticate successfully.

    This is the PRIMARY regression guard for Checkpoint 3 MAJOR #2
    (UPPER-CASE normalization). It proves BOTH of the
    :func:`str.upper` calls in :meth:`AuthService.authenticate`
    (``src/api/services/auth_service.py`` lines 499-500) fire before
    the database lookup (``WHERE user_id = :user_id_upper``) and
    before the BCrypt verification (``pwd_context.verify(password_upper,
    user.password)``).

    Arrange
        * ``sample_user`` has ``user_id="TESTUSER"`` (upper-case) and
          ``password`` = BCrypt hash of ``_TEST_PLAINTEXT_PASSWORD``
          which is the literal string ``"PASSWORD"`` (upper-case).
          This mirrors ``db/migrations/V3__seed_data.sql`` which
          persists user IDs in upper-case and BCrypt-hashes
          upper-case plaintext passwords.
        * The mocked session's ``execute(...).scalar_one_or_none()``
          returns ``sample_user`` regardless of the compiled SQL's
          WHERE-clause literal -- the mock does not filter by
          parameter binding.
    Act
        Construct a :class:`SignOnRequest` with ALL-LOWERCASE
        ``user_id="testuser"`` and ``password="password"``, then
        invoke ``auth_service.authenticate(request)``.
    Assert
        * A :class:`SignOnResponse` is returned (success path), with
          ``user_id == "TESTUSER"`` (upper-case, from the DB row --
          NOT the raw lowercase request).
        * ``user_type == "U"`` (regular user).
        * ``access_token`` is a non-empty compact-serialized JWT.
        * ``mock_db_session.execute`` was awaited exactly once.
        * The compiled SQL for that ``execute`` call -- when rendered
          with :class:`literal_binds` -- contains the upper-case
          literal ``"TESTUSER"``. This proves ``user_id.upper()``
          was applied before the SQLAlchemy ``select`` was built.
        * The compiled SQL does NOT contain the raw lowercase
          ``"testuser"`` literal (guards against a future edit that
          accidentally lower-cases the bound parameter).

    Why BCrypt success proves the password UPPER-CASE applied
    ---------------------------------------------------------
    The BCrypt hash in ``sample_user.password`` was produced at
    fixture-construction time from ``_TEST_PWD_CONTEXT.hash(
    _TEST_PLAINTEXT_PASSWORD)`` which hashes the literal bytes
    ``"PASSWORD"`` (upper-case, see line 206). BCrypt is a
    cryptographic hash -- a 1:1 mapping between plaintext and digest
    with no case-insensitivity. If the service had NOT upper-cased
    the request's password ``"password"`` before calling
    :func:`pwd_context.verify`, BCrypt would reject the match and
    the service would raise :class:`AuthenticationError` with
    :data:`MSG_WRONG_PASSWORD`. A :class:`SignOnResponse` reaching
    the caller therefore proves the ``.upper()`` call on line 500
    of ``auth_service.py`` fired.

    COBOL mapping
    -------------
    Defends the translation of ``COSGN00C.cbl`` lines 132-135::

        MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI)
                TO WS-USER-ID
        MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI)
                TO WS-USER-PWD

    Without this pattern, users migrated from the legacy CICS
    terminal (which is case-insensitive by convention for sign-on)
    would be unable to authenticate into the cloud-native API --
    a user-visible regression forbidden by AAP §0.7.1.
    """
    # Arrange: configure the mock to return the upper-case seed user.
    _configure_db_user(mock_db_session, sample_user)
    # Lowercase both fields -- the service MUST upper-case them
    # before the DB query and the BCrypt verify.
    lowercase_user_id: str = _TEST_USER_ID.lower()  # "testuser"
    lowercase_password: str = _TEST_PLAINTEXT_PASSWORD.lower()  # "password"
    # Sanity: our lowercase inputs differ from the canonical upper-case
    # forms. If this ever fails, the test is no longer exercising
    # what its name claims.
    assert lowercase_user_id != _TEST_USER_ID, (
        "Test precondition: lowercased user_id must differ from canonical."
    )
    assert lowercase_password != _TEST_PLAINTEXT_PASSWORD, (
        "Test precondition: lowercased password must differ from canonical."
    )
    request = SignOnRequest(
        user_id=lowercase_user_id,
        password=lowercase_password,
    )

    # Act.
    response = await auth_service.authenticate(request)

    # Assert: success envelope.
    assert isinstance(response, SignOnResponse), (
        "authenticate() must return a SignOnResponse when both "
        ".upper() calls in auth_service.py L499-500 fire. A "
        "failure here would mean MAJOR #2 has regressed."
    )
    assert response.user_id == _TEST_USER_ID, (
        "Response user_id must echo the upper-case form stored in "
        "the DB row (COBOL: CDEMO-USER-ID from the SEC-USER-DATA "
        f"record); got {response.user_id!r}, expected {_TEST_USER_ID!r}."
    )
    assert response.user_type == "U", (
        f"Response user_type must be 'U' for CDEMO-USRTYP-USER path; got {response.user_type!r}"
    )
    assert isinstance(response.access_token, str) and response.access_token, (
        "access_token must be a non-empty JWT (success path issues "
        "the replacement for the CICS COMMAREA session state)."
    )
    assert response.access_token.count(".") == 2, (
        f"access_token must be a compact-serialized JWT; got {response.access_token!r}"
    )
    assert response.token_type == "bearer", (
        f"token_type must be 'bearer' (OAuth2 compliance); got {response.token_type!r}"
    )

    # Assert: DB was queried once (mirrors EXEC CICS READ FILE('USRSEC')).
    assert mock_db_session.execute.await_count == 1, (
        "authenticate() must call db.execute() exactly once per "
        f"sign-on attempt; got {mock_db_session.execute.await_count}."
    )

    # Assert: the compiled SQL contains the UPPER-CASE user_id literal.
    # This is the authoritative proof that ``user_id.upper()`` was
    # applied to the ``where(UserSecurity.user_id == user_id_upper)``
    # parameter at auth_service.py line 522. We render the statement
    # with ``literal_binds=True`` so bound parameters appear inline as
    # quoted string literals in the compiled SQL text.
    executed_stmt = mock_db_session.execute.await_args[0][0]
    compiled_sql: str = str(
        executed_stmt.compile(compile_kwargs={"literal_binds": True})
    )
    assert _TEST_USER_ID in compiled_sql, (
        f"Compiled WHERE clause must contain the upper-case user_id "
        f"literal {_TEST_USER_ID!r}. If the assertion fails, "
        f"AuthService.authenticate() likely dropped the .upper() "
        f"normalization from line 499 of auth_service.py -- MAJOR "
        f"#2 regression. Full compiled SQL:\n{compiled_sql}"
    )
    assert lowercase_user_id not in compiled_sql, (
        f"Compiled WHERE clause must NOT contain the raw lowercase "
        f"user_id literal {lowercase_user_id!r} -- its presence "
        f"would indicate the request payload was bound directly "
        f"without the FUNCTION UPPER-CASE normalization. Full "
        f"compiled SQL:\n{compiled_sql}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_authenticate_mixed_case_credentials_succeed(
    auth_service: AuthService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Mixed-case ``user_id`` + ``password`` authenticate successfully.

    Secondary UPPER-CASE regression guard -- exercises inputs that
    are neither fully lowercase nor fully uppercase, protecting
    against a partial fix (e.g. a future edit that lowercases inputs
    first then compares against an upper-case stored value, which
    would pass the all-lowercase test but fail on mixed-case input).

    Arrange
        ``sample_user`` has upper-case ``user_id="TESTUSER"`` and a
        BCrypt hash of the upper-case plaintext ``"PASSWORD"``.
    Act
        Submit ``SignOnRequest(user_id="TestUser", password="Password")``
        (mixed case on both fields) to ``authenticate()``.
    Assert
        Success envelope with ``user_id="TESTUSER"`` returned -- the
        COBOL ``FUNCTION UPPER-CASE`` folds any case mixture to the
        canonical upper-case form before the file lookup and
        password comparison.

    COBOL mapping
    -------------
    Same as :func:`test_authenticate_lowercase_credentials_succeed`:
    ``COSGN00C.cbl`` lines 132-135 apply
    ``FUNCTION UPPER-CASE`` unconditionally regardless of which
    case the terminal operator typed in.
    """
    # Arrange.
    _configure_db_user(mock_db_session, sample_user)
    # Mixed case: first character upper, rest lower.
    mixed_user_id: str = "TestUser"  # Differs from both "TESTUSER" and "testuser"
    mixed_password: str = "Password"  # Differs from both "PASSWORD" and "password"
    assert mixed_user_id != _TEST_USER_ID and mixed_user_id != _TEST_USER_ID.lower()
    assert mixed_password != _TEST_PLAINTEXT_PASSWORD and mixed_password != _TEST_PLAINTEXT_PASSWORD.lower()
    request = SignOnRequest(user_id=mixed_user_id, password=mixed_password)

    # Act.
    response = await auth_service.authenticate(request)

    # Assert: success path.
    assert isinstance(response, SignOnResponse), (
        "authenticate() must accept mixed-case credentials and fold "
        "them via UPPER-CASE (COBOL COSGN00C.cbl L132-135)."
    )
    assert response.user_id == _TEST_USER_ID, (
        f"Response user_id must be the upper-case canonical form {_TEST_USER_ID!r}; got {response.user_id!r}"
    )
    assert response.user_type == "U"
    assert response.access_token and response.access_token.count(".") == 2

    # Assert: the compiled SQL contains the UPPER-CASE user_id literal.
    executed_stmt = mock_db_session.execute.await_args[0][0]
    compiled_sql: str = str(
        executed_stmt.compile(compile_kwargs={"literal_binds": True})
    )
    assert _TEST_USER_ID in compiled_sql, (
        f"Compiled WHERE clause must contain the upper-case canonical "
        f"user_id literal {_TEST_USER_ID!r} even when the request "
        f"supplied the mixed-case {mixed_user_id!r}. MAJOR #2 "
        f"regression guard. Full compiled SQL:\n{compiled_sql}"
    )
    assert mixed_user_id not in compiled_sql, (
        f"Compiled WHERE clause must NOT contain the raw mixed-case "
        f"user_id literal {mixed_user_id!r}. Full compiled SQL:\n"
        f"{compiled_sql}"
    )


# ============================================================================
# Phase 5: Token Verification Tests (verify_token)
# ============================================================================
#
# :meth:`AuthService.verify_token` is a @staticmethod that decodes a
# JWT and returns a :class:`TokenPayload`. It is called by the auth
# middleware (``src/api/middleware/auth.py``) on every request that
# targets a protected endpoint. In the mainframe architecture the
# equivalent validation happened implicitly: each CICS transaction
# trusted the COMMAREA because CICS had already validated the
# principal at sign-on via RACF. In the stateless cloud-native
# architecture the middleware must re-validate every request, so
# ``verify_token()`` is on the hot path and MUST:
#
#   * Return a :class:`TokenPayload` for valid tokens.
#   * Raise :class:`InvalidTokenError` (with the COBOL-exact
#     MSG_UNABLE_TO_VERIFY message) for expired tokens.
#   * Raise :class:`InvalidTokenError` for structurally invalid
#     tokens (garbage, wrong signature, wrong algorithm, tampered).
# ============================================================================


@pytest.mark.unit
def test_verify_valid_token() -> None:
    """``verify_token`` returns a :class:`TokenPayload` for a valid token.

    Arrange
        Encode a JWT directly with :func:`jwt.encode` using the same
        secret/algorithm that the service's :func:`_get_settings`
        will read from the env (set by the ``_set_jwt_settings_env``
        autouse fixture). Claims: ``sub``, ``user_id``,
        ``user_type``, ``exp`` in the future.
    Act
        Call ``AuthService.verify_token(token)``.
    Assert
        * Result is a :class:`TokenPayload`.
        * ``result.sub`` / ``result.user_type`` match the encoded
          claims.
        * ``result.exp`` equals the encoded epoch seconds.

    COBOL mapping
    -------------
    No direct COBOL equivalent -- this is the inverse of the
    COMMAREA population at :func:`authenticate` sign-on. Where CICS
    trusted COMMAREA implicitly across XCTL/LINK boundaries, the
    cloud port re-verifies the JWT on every HTTP hop.
    """
    # Arrange: craft a valid token with a future exp.
    future_exp = int((datetime.now(UTC) + timedelta(minutes=15)).timestamp())
    payload_claims: dict[str, Any] = {
        "sub": _TEST_USER_ID,
        "user_id": _TEST_USER_ID,
        "user_type": "U",
        "exp": future_exp,
    }
    token = jwt.encode(
        payload_claims,
        _TEST_JWT_SECRET_KEY,
        algorithm=_TEST_JWT_ALGORITHM,
    )

    # Act.
    payload = AuthService.verify_token(token)

    # Assert.
    assert isinstance(payload, TokenPayload), f"verify_token must return TokenPayload; got {type(payload).__name__}"
    assert payload.sub == _TEST_USER_ID, f"TokenPayload.sub must equal the encoded 'sub' claim; got {payload.sub!r}"
    assert payload.user_type == "U", (
        f"TokenPayload.user_type must equal the encoded 'user_type' claim; got {payload.user_type!r}"
    )
    assert payload.exp == future_exp, (
        f"TokenPayload.exp must equal the encoded 'exp' epoch seconds; got {payload.exp} (expected {future_exp})"
    )


@pytest.mark.unit
def test_verify_valid_token_admin_user_type() -> None:
    """``verify_token`` preserves ``user_type='A'`` for admin tokens.

    A smoke test complementing :func:`test_verify_valid_token` to
    confirm both user_type values ('A' and 'U') round-trip through
    the JWT verify path. Crucial because
    :attr:`TokenPayload.user_type` has a validator that rejects any
    value outside the frozenset {'A', 'U'}; if the validator or the
    :class:`AuthService.verify_token` error handling regressed,
    admin tokens would be silently rejected.

    COBOL mapping
    -------------
    Mirrors the admin branch of ``CDEMO-USRTYP-ADMIN`` from
    ``COCOM01Y.cpy``.
    """
    # Arrange.
    future_exp = int((datetime.now(UTC) + timedelta(minutes=15)).timestamp())
    token = jwt.encode(
        {
            "sub": _TEST_ADMIN_USER_ID,
            "user_id": _TEST_ADMIN_USER_ID,
            "user_type": "A",
            "exp": future_exp,
        },
        _TEST_JWT_SECRET_KEY,
        algorithm=_TEST_JWT_ALGORITHM,
    )

    # Act.
    payload = AuthService.verify_token(token)

    # Assert.
    assert payload.sub == _TEST_ADMIN_USER_ID
    assert payload.user_type == "A", (
        f"TokenPayload.user_type must preserve 'A' for admin tokens (CDEMO-USRTYP-ADMIN); got {payload.user_type!r}"
    )


@pytest.mark.unit
def test_verify_expired_token() -> None:
    """Expired JWT raises :class:`InvalidTokenError`.

    Arrange
        Encode a JWT with an ``exp`` claim 60 seconds in the past.
    Act
        Call ``AuthService.verify_token(token)``.
    Assert
        * :class:`InvalidTokenError` is raised.
        * The error message is ``MSG_UNABLE_TO_VERIFY`` (the
          service intentionally does NOT distinguish expired vs
          malformed tokens at the message level, to avoid leaking
          token state to attackers).
        * :class:`InvalidTokenError` is a subclass of
          :class:`AuthenticationError`.

    COBOL mapping
    -------------
    Same mainframe equivalent as the ``WHEN OTHER`` branch of
    ``COSGN00C.cbl`` lines 252-256: any unverifiable credential
    collapses to ``MSG_UNABLE_TO_VERIFY``. We rely on the
    middleware to translate this into HTTP 401.
    """
    # Arrange: 60 seconds in the past is well beyond any clock-skew
    # tolerance jose allows (jose's default leeway is 0 seconds).
    past_exp = int((datetime.now(UTC) - timedelta(seconds=60)).timestamp())
    token = jwt.encode(
        {
            "sub": _TEST_USER_ID,
            "user_id": _TEST_USER_ID,
            "user_type": "U",
            "exp": past_exp,
        },
        _TEST_JWT_SECRET_KEY,
        algorithm=_TEST_JWT_ALGORITHM,
    )

    # Act / Assert.
    with pytest.raises(InvalidTokenError) as exc_info:
        AuthService.verify_token(token)

    # InvalidTokenError inherits from AuthenticationError, which
    # means middleware handlers catching AuthenticationError will
    # also catch this. Explicitly assert the subclass relationship
    # so a future refactor can't accidentally break the hierarchy.
    assert isinstance(exc_info.value, AuthenticationError), (
        "InvalidTokenError must inherit from AuthenticationError so "
        "middleware catching AuthenticationError also catches "
        "expired-token errors."
    )
    # The message must be MSG_UNABLE_TO_VERIFY -- same text as the
    # generic DB-failure case. This is a deliberate design choice:
    # do not leak 'token expired' vs 'token tampered' to the client.
    assert exc_info.value.message == MSG_UNABLE_TO_VERIFY, (
        f"Expired token must surface MSG_UNABLE_TO_VERIFY (not a "
        f"more specific 'expired' message -- see AAP Section 0.7.2 "
        f"Security Requirements: do not leak token state); got "
        f"{exc_info.value.message!r}"
    )
    # str(exc) must surface the message for structured logging.
    assert str(exc_info.value) == MSG_UNABLE_TO_VERIFY


@pytest.mark.unit
def test_verify_invalid_token() -> None:
    """Garbage / malformed JWT raises :class:`InvalidTokenError`.

    Arrange
        A string that is obviously not a compact-serialized JWT.
    Act
        Call ``AuthService.verify_token(token)``.
    Assert
        * :class:`InvalidTokenError` is raised.
        * Message is ``MSG_UNABLE_TO_VERIFY``.

    COBOL mapping
    -------------
    Same semantic as an unparseable COMMAREA in the CICS era (which
    would have caused an ASRA abend). In Python the library's
    :class:`JWTError` is caught and wrapped in the service's
    COBOL-preserved error message.
    """
    garbage_token = "this.is.not.a.valid.jwt.at.all"

    with pytest.raises(InvalidTokenError) as exc_info:
        AuthService.verify_token(garbage_token)

    assert exc_info.value.message == MSG_UNABLE_TO_VERIFY, (
        f"Garbage token must surface MSG_UNABLE_TO_VERIFY; got {exc_info.value.message!r}"
    )
    # The __cause__ should chain the underlying JWTError from jose
    # so server-side logs can see why parsing failed. We don't pin
    # the specific exception type because jose's internal hierarchy
    # has churned between versions -- just confirm we preserved a
    # cause for forensics.
    assert exc_info.value.__cause__ is not None, (
        "InvalidTokenError for a garbage token must chain the "
        "underlying jose.JWTError via 'raise ... from exc' so that "
        "the original parse failure is preserved in logs."
    )


@pytest.mark.unit
def test_verify_token_signed_with_wrong_secret() -> None:
    """JWT signed with a different secret raises :class:`InvalidTokenError`.

    Arrange
        Encode a structurally-valid JWT using a SECRET that does
        NOT match the service's :attr:`Settings.JWT_SECRET_KEY`.
    Act
        ``AuthService.verify_token(token)``.
    Assert
        :class:`InvalidTokenError` with ``MSG_UNABLE_TO_VERIFY``.

    This is a critical security test: it ensures the HMAC signature
    is actually verified and that we reject tokens forged with
    a leaked or guessed secret from another environment.

    COBOL mapping
    -------------
    No direct mainframe equivalent; the cloud analog of ensuring a
    forged RACF credential (issued by a different security plex)
    cannot be replayed into this region.
    """
    # Arrange: a fully-valid JWT, but signed with the wrong secret.
    future_exp = int((datetime.now(UTC) + timedelta(minutes=15)).timestamp())
    wrong_secret_token = jwt.encode(
        {
            "sub": _TEST_USER_ID,
            "user_id": _TEST_USER_ID,
            "user_type": "U",
            "exp": future_exp,
        },
        "an-attacker-controlled-secret-that-is-NOT-our-JWT_SECRET_KEY",
        algorithm=_TEST_JWT_ALGORITHM,
    )

    # Act / Assert.
    with pytest.raises(InvalidTokenError) as exc_info:
        AuthService.verify_token(wrong_secret_token)

    assert exc_info.value.message == MSG_UNABLE_TO_VERIFY, (
        "Tokens signed with a foreign secret must be rejected with "
        "the generic MSG_UNABLE_TO_VERIFY (do not reveal that the "
        "issue was signature verification specifically)."
    )


# ============================================================================
# Phase 6: Password Hashing Tests (hash_password / verify_password)
# ============================================================================
#
# :meth:`AuthService.hash_password` and :meth:`AuthService.verify_password`
# wrap the module-level ``pwd_context`` (``CryptContext(schemes=['bcrypt'])``).
# They are used during :class:`UserSecurity` creation (hash) and during
# the sign-on flow (verify).
#
# The source COBOL stored passwords in clear in the VSAM USRSEC file
# (``CSUSR01Y.cpy`` field SEC-USR-PWD PIC X(08)). The migration
# deliberately upgrades to BCrypt-hashed storage. AAP Section 0.7.2
# (Security Requirements): "BCrypt password hashing must be
# maintained for user authentication (matching existing COBOL
# behavior)". These tests verify both the hash format and the
# round-trip correctness so that sign-on continues to work after the
# schema migration.
#
# BCrypt identifier conventions:
#   $2a$ -- original BCrypt (Python bcrypt >= 3.1 can still verify
#           these)
#   $2b$ -- "corrected" BCrypt, the standard emitted by modern
#           passlib+bcrypt releases -- what we expect here
#   $2y$ -- PHP-specific variant, not expected
# ============================================================================


@pytest.mark.unit
def test_hash_password(auth_service: AuthService) -> None:
    """``hash_password`` returns a BCrypt-format hash string.

    Arrange
        N/A.
    Act
        ``auth_service.hash_password(_TEST_PLAINTEXT_PASSWORD)``.
    Assert
        * Result is a :class:`str`.
        * Result starts with ``"$2b$"`` (modern BCrypt identifier).
        * Result length is 60 characters (BCrypt canonical length,
          which is exactly what :attr:`UserSecurity.password`'s
          ``String(60)`` column is sized for).
        * The hash is NOT the plaintext (trivial regression guard).

    COBOL mapping
    -------------
    Converts the ``CSUSR01Y.cpy`` SEC-USR-PWD PIC X(08) cleartext
    storage into a BCrypt-hashed value stored in
    ``user_security.password`` (String(60)). During the VSAM->
    PostgreSQL seed migration, every cleartext password was run
    through this same ``hash_password`` so the on-disk format is
    consistent.
    """
    # Act.
    hashed = auth_service.hash_password(_TEST_PLAINTEXT_PASSWORD)

    # Assert.
    assert isinstance(hashed, str), (
        f"hash_password must return a str (for direct assignment to UserSecurity.password); got {type(hashed).__name__}"
    )
    assert hashed.startswith("$2b$"), (
        f"hash_password must emit modern BCrypt hashes with '$2b$' "
        f"identifier (matches passlib's default for bcrypt 4.x); "
        f"got prefix={hashed[:4]!r}"
    )
    assert len(hashed) == 60, (
        f"BCrypt hashes are canonically 60 characters; this is also "
        f"the exact width of UserSecurity.password (String(60)). "
        f"Got len={len(hashed)} for hash {hashed!r}"
    )
    assert hashed != _TEST_PLAINTEXT_PASSWORD, (
        "hash_password must transform its input -- returning the plaintext would be a catastrophic regression."
    )


@pytest.mark.unit
def test_hash_password_differs_from_plaintext(
    auth_service: AuthService,
) -> None:
    """Successive hashes of the same plaintext differ (salt randomness).

    Act
        Hash the same plaintext twice.
    Assert
        The two hashes are NOT equal.

    This tests that the BCrypt salt is genuinely random per call.
    If ``hash_password`` ever stopped salting (e.g., switched to a
    deterministic scheme), two sign-ups with the same password
    would collide in ``user_security.password``, making password
    reuse trivially detectable by anyone with read access to the
    table.

    COBOL mapping
    -------------
    No COBOL equivalent -- the mainframe stored cleartext, so
    duplicate passwords were trivially matchable. The migration
    deliberately introduces per-user salting to close that gap.
    """
    hash_one = auth_service.hash_password(_TEST_PLAINTEXT_PASSWORD)
    hash_two = auth_service.hash_password(_TEST_PLAINTEXT_PASSWORD)

    assert hash_one != hash_two, (
        "Two BCrypt hashes of the same plaintext must differ "
        "because BCrypt applies a fresh random salt per call. If "
        "they match, the salt generator is broken or seeded "
        "deterministically -- this is a security-critical "
        "regression."
    )
    # Both must still be valid BCrypt hashes.
    assert hash_one.startswith("$2b$")
    assert hash_two.startswith("$2b$")


@pytest.mark.unit
def test_verify_password_correct(auth_service: AuthService) -> None:
    """``verify_password`` returns ``True`` for the correct plaintext.

    Arrange
        Hash ``_TEST_PLAINTEXT_PASSWORD`` using the service's own
        :meth:`hash_password`.
    Act
        Pass the plaintext + hash back into
        :meth:`verify_password`.
    Assert
        Result is :class:`bool`  ``True``.

    COBOL mapping
    -------------
    Replaces the COBOL plaintext equality check::

        IF SEC-USR-PWD = WS-USER-PWD

    (``COSGN00C.cbl`` line 241) with a BCrypt constant-time
    verification. Returning ``True`` here is the authenticate()
    success branch; returning ``False`` is the MSG_WRONG_PASSWORD
    branch.
    """
    hashed = auth_service.hash_password(_TEST_PLAINTEXT_PASSWORD)

    # Act.
    result = auth_service.verify_password(
        _TEST_PLAINTEXT_PASSWORD,
        hashed,
    )

    # Assert.
    assert result is True, (
        f"verify_password(plain, hash) must return True when the plaintext matches the hash; got {result!r}"
    )
    # Guarantee the bool coercion is a real bool, not a truthy
    # int / MagicMock -- the service signature declares `-> bool`.
    assert isinstance(result, bool), (
        f"verify_password must return a bool (service signature is '-> bool'); got {type(result).__name__}"
    )


@pytest.mark.unit
def test_verify_password_incorrect(auth_service: AuthService) -> None:
    """``verify_password`` returns ``False`` for a mismatched plaintext.

    Arrange
        Hash ``_TEST_PLAINTEXT_PASSWORD``.
    Act
        Call ``verify_password("WRONG", hashed)``.
    Assert
        Result is :class:`bool`  ``False``.

    COBOL mapping
    -------------
    Replaces the COBOL ELSE branch at ``COSGN00C.cbl`` line 244
    (``ELSE MOVE 'Wrong Password. Try again ...'``) at the
    verify-check layer. The calling :meth:`authenticate` method
    translates a :class:`False` return into
    :class:`AuthenticationError` with MSG_WRONG_PASSWORD (verified
    separately by :func:`test_authenticate_wrong_password`).
    """
    hashed = auth_service.hash_password(_TEST_PLAINTEXT_PASSWORD)

    # Act.
    result = auth_service.verify_password("WRONGPWD", hashed)

    # Assert.
    assert result is False, (
        f"verify_password(plain, hash) must return False when the plaintext does NOT match the hash; got {result!r}"
    )
    assert isinstance(result, bool), f"verify_password must return a bool; got {type(result).__name__}"


@pytest.mark.unit
def test_verify_password_against_externally_hashed_value(
    auth_service: AuthService,
) -> None:
    """Cross-vendor BCrypt interop: verify a hash produced outside the service.

    Arrange
        Hash ``_TEST_PLAINTEXT_PASSWORD`` using a FRESH
        :class:`passlib.context.CryptContext` (simulating the
        seed-data loader in ``db/migrations/V3__seed_data.sql`` or
        any external tool that hashes users for bulk load).
    Act
        Pass the externally-produced hash into
        ``auth_service.verify_password``.
    Assert
        Returns :class:`True`.

    This protects against drift between :mod:`passlib` versions /
    configurations used during seed-data generation vs. runtime
    authentication. If the two ever drift, legacy users migrated
    from the VSAM USRSEC file would be unable to sign on even with
    the correct password.

    COBOL mapping
    -------------
    Models the migration path from ``app/data/ASCII/`` (VSAM seed
    data) through Flyway V3 (``V3__seed_data.sql``) into the
    running system.
    """
    # Arrange: use a completely separate CryptContext instance
    # (this simulates the seed-script environment where a different
    # passlib version could theoretically emit a different hash
    # shape -- in practice they remain cross-compatible).
    external_ctx = CryptContext(schemes=["bcrypt"], deprecated="auto")
    external_hash = external_ctx.hash(_TEST_PLAINTEXT_PASSWORD)

    # Act.
    result = auth_service.verify_password(
        _TEST_PLAINTEXT_PASSWORD,
        external_hash,
    )

    # Assert.
    assert result is True, (
        "verify_password must accept BCrypt hashes produced by any "
        "passlib CryptContext configured with the 'bcrypt' scheme. "
        "If this fails, legacy users seeded via V3__seed_data.sql "
        "would be locked out."
    )
