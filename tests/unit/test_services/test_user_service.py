# ============================================================================
# CardDemo - Unit tests for UserService (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COUSR00C.cbl     - CICS User List program, transaction CU00
#                                (PROCESS-ENTER-KEY + STARTBR-USER-SEC-FILE +
#                                 READNEXT-USER-SEC-FILE + READPREV-USER-SEC-FILE
#                                 paragraphs - lines ~108-695, browse-mode
#                                 cursor pagination over VSAM ``USRSEC``).
#   * app/cbl/COUSR01C.cbl     - CICS User Add program, transaction CU01
#                                (PROCESS-ENTER-KEY + ADD-USER-SEC-FILE
#                                 paragraphs - lines ~108-299, WRITE to VSAM
#                                 ``USRSEC`` with DUPKEY/DUPREC detection).
#   * app/cbl/COUSR02C.cbl     - CICS User Update program, transaction CU02
#                                (PROCESS-ENTER-KEY + READ-USER-SEC-FILE +
#                                 UPDATE-USER-SEC-FILE paragraphs - lines
#                                 ~108-414, READ UPDATE + REWRITE).
#   * app/cbl/COUSR03C.cbl     - CICS User Delete program, transaction CU03
#                                (PROCESS-ENTER-KEY + READ-USER-SEC-FILE +
#                                 DELETE-USER-SEC-FILE paragraphs - lines
#                                 ~108-359, READ + DELETE).
#   * app/cpy/CSUSR01Y.cpy     - SEC-USER-DATA record layout (80-byte VSAM
#                                KSDS): SEC-USR-ID PIC X(08),
#                                SEC-USR-FNAME PIC X(20),
#                                SEC-USR-LNAME PIC X(20),
#                                SEC-USR-PWD   PIC X(08) (now BCrypt 60-char),
#                                SEC-USR-TYPE  PIC X(01) ('A' or 'U'),
#                                SEC-USR-FILLER PIC X(23).
#   * app/cpy/COCOM01Y.cpy     - CARDDEMO-COMMAREA user-type 88-level
#                                conditions: 88 CDEMO-USRTYP-ADMIN VALUE 'A',
#                                88 CDEMO-USRTYP-USER  VALUE 'U'.
# ----------------------------------------------------------------------------
# Features exercised:
#   * F-018 User list         (COUSR00C.cbl)
#   * F-019 User add          (COUSR01C.cbl)
#   * F-020 User update       (COUSR02C.cbl)
#   * F-021 User delete       (COUSR03C.cbl)
#
# Target implementation under test: src/api/services/user_service.py
# (the UserService class, which consolidates the PROCEDURE DIVISION logic of
# all four CICS programs into a single service facade). Per AAP Section 0.7.1
# "Preserve exact error messages from COBOL", the following error strings are
# reproduced byte-for-byte and asserted verbatim by the tests below:
#
#   * 'Unable to lookup User...'      (COUSR00C L610, COUSR02C L349, COUSR03C L296)
#   * 'User ID already exist...'      (COUSR01C L263 - note "exist" not "exists")
#   * 'Unable to Add User...'         (COUSR01C L270)
#   * 'User ID NOT found...'          (COUSR02C L342, COUSR03C L289)
#   * 'Please modify to update ...'   (COUSR02C L239 - note space before ellipsis)
#   * 'Unable to Update User...'      (COUSR02C L386 and COUSR03C L332 - the
#                                      Delete flow preserves "Update" verbatim
#                                      as a benign CICS code-sharing artifact)
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
"""Unit tests for :class:`UserService`.

Validates full CRUD (list / add / update / delete) for the user
administration service with BCrypt password hashing. Converted from
the four CICS COBOL programs that implement Features F-018 through
F-021 (``app/cbl/COUSR00C.cbl`` through ``COUSR03C.cbl``).

COBOL -> Python Verification Surface
------------------------------------
=============================================  ==========================================
COBOL paragraph / statement                    Python test (this module)
=============================================  ==========================================
COUSR00C ``STARTBR-USER-SEC-FILE`` L583-615    ``test_list_users_default_page_size_10``
COUSR00C ``READNEXT-USER-SEC-FILE`` L618-650   ``test_list_users_pagination``
COUSR00C ``USRIDINI`` filter input L158-165    ``test_list_users_filter_by_user_id_prefix``
COUSR00C ``WHEN ENDFILE`` L644-650             ``test_list_users_empty_result``
COUSR00.CPY (no PASSWDI field)                 ``test_list_users_no_password_in_response``
COUSR01C ``ADD-USER-SEC-FILE`` L236-275        ``test_create_user_success``
COUSR01C ``MOVE PASSWDI TO SEC-USR-PWD`` L220  ``test_create_user_password_hashed_with_bcrypt``
COUSR01C ``WHEN DUPKEY / DUPREC`` L261-268     ``test_create_user_duplicate_rejected``
COCOM01Y ``CDEMO-USRTYP-ADMIN`` ('A')          ``test_create_user_type_admin``
COCOM01Y ``CDEMO-USRTYP-USER`` ('U')           ``test_create_user_type_regular``
COCOM01Y 88-level (A/U only)                   ``test_create_user_invalid_type_rejected``
COUSR02C ``UPDATE-USER-SEC-FILE`` L358-392     ``test_update_user_success``
COUSR02C ``UPDATE-USER-INFO`` (field-by-field) ``test_update_user_partial_update``
COUSR02C ``MOVE PASSWDI TO SEC-USR-PWD``       ``test_update_user_password_rehashed``
COUSR02C ``WHEN NOTFND`` L342                  ``test_update_user_not_found``
COUSR02C re-check of user_type                 ``test_update_user_type_validation``
COUSR03C ``DELETE-USER-SEC-FILE`` L304-338     ``test_delete_user_success``
COUSR03C ``WHEN NOTFND`` L289                  ``test_delete_user_not_found``
COUSR03.CPY (no PASSWDI / PASSWDO)             ``test_delete_user_no_password_in_response``
=============================================  ==========================================

Test Design
-----------
* **Mocked database**: Every test uses ``AsyncMock(spec=AsyncSession)``
  rather than a real database, so the suite runs in milliseconds with
  no PostgreSQL dependency. The mock replicates the SQLAlchemy 2.x
  async contract (``execute()`` -> result object with
  ``scalars()`` / ``scalar_one_or_none()`` / ``scalar_one()``) that
  :class:`UserService` uses in place of the CICS-managed
  ``USRSEC`` VSAM file handle referenced by every
  ``EXEC CICS <verb> FILE('USRSEC')`` call in the four COBOL sources.

* **Real BCrypt hashes**: ``sample_user`` / ``sample_admin`` /
  ``sample_users_list`` fixtures hold genuine BCrypt-hashed test
  passwords (via :class:`passlib.context.CryptContext`). The create
  / update path is therefore exercised end-to-end without mocking
  :meth:`CryptContext.hash` or :meth:`CryptContext.verify`. Tests
  that assert on the resulting hash verify (a) it starts with the
  BCrypt ``$2b$`` prefix, (b) its length is within the expected
  60-character envelope, and (c) ``pwd_context.verify(plain, hash)``
  round-trips correctly.

* **Bypassing Pydantic for defence-in-depth**: the ``user_type``
  domain is enforced by **both** the Pydantic schema (field validator)
  and the service layer (explicit re-check). Tests that exercise the
  service-layer re-check (e.g. ``test_create_user_invalid_type_rejected``)
  use :meth:`~pydantic.BaseModel.model_construct` to bypass the
  schema validator so that an invalid value reaches the service.

* **COBOL-exact message assertions**: every error assertion references
  the ``MSG_*`` module constants from :mod:`src.api.services.user_service`
  (``MSG_UNABLE_TO_LOOKUP`` etc.) rather than re-typing the literal
  strings, so the tests catch any accidental drift from the
  byte-for-byte COBOL wording mandated by AAP Section 0.7.1.

Test Coverage (19 functions across 4 feature groups)
----------------------------------------------------
**Phase 3 -- F-018 User List (5 tests)**:
 1. :func:`test_list_users_default_page_size_10`          -- 10 rows/page
 2. :func:`test_list_users_pagination`                    -- page 2 OFFSET
 3. :func:`test_list_users_filter_by_user_id_prefix`      -- USRIDINI filter
 4. :func:`test_list_users_empty_result`                  -- ENDFILE equivalent
 5. :func:`test_list_users_no_password_in_response`       -- password omitted

**Phase 4 -- F-019 User Add (6 tests)**:
 6. :func:`test_create_user_success`                      -- WRITE equivalent
 7. :func:`test_create_user_password_hashed_with_bcrypt`  -- BCrypt mandated
 8. :func:`test_create_user_duplicate_rejected`           -- DUPKEY/DUPREC
 9. :func:`test_create_user_type_admin`                   -- usr_type='A'
10. :func:`test_create_user_type_regular`                 -- usr_type='U'
11. :func:`test_create_user_invalid_type_rejected`        -- 88-level

**Phase 5 -- F-020 User Update (5 tests)**:
12. :func:`test_update_user_success`                      -- REWRITE equivalent
13. :func:`test_update_user_partial_update`               -- PATCH semantics
14. :func:`test_update_user_password_rehashed`            -- BCrypt rehash
15. :func:`test_update_user_not_found`                    -- NOTFND
16. :func:`test_update_user_type_validation`              -- 88-level re-check

**Phase 6 -- F-021 User Delete (3 tests)**:
17. :func:`test_delete_user_success`                      -- DELETE equivalent
18. :func:`test_delete_user_not_found`                    -- NOTFND
19. :func:`test_delete_user_no_password_in_response`      -- password omitted

See Also
--------
* ``src/api/services/user_service.py``      -- The service under test.
* ``src/shared/models/user_security.py``    -- ORM model queried by the
  service (from ``app/cpy/CSUSR01Y.cpy`` SEC-USER-DATA 80-byte layout).
* ``src/shared/schemas/user_schema.py``     -- Pydantic request / response
  schemas (from ``app/cpy-bms/COUSR00.CPY`` / ``COUSR01.CPY`` /
  ``COUSR02.CPY`` / ``COUSR03.CPY``).
* ``tests/unit/test_services/test_auth_service.py`` -- Companion test
  module for :class:`AuthService`; shares the BCrypt-hashing + mocked-
  AsyncSession patterns used here.
* AAP Section 0.7.1 -- Refactoring-Specific Rules (preserve exact
  error messages from COBOL).
* AAP Section 0.7.2 -- Security Requirements (BCrypt hashing mandated
  for all persisted passwords; plaintext never touches disk).
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest
from passlib.context import CryptContext
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.services.user_service import (
    MSG_PLEASE_MODIFY_TO_UPDATE,
    MSG_USER_ADDED_TEMPLATE,
    MSG_USER_DELETED_TEMPLATE,
    MSG_USER_ID_ALREADY_EXISTS,
    MSG_USER_ID_NOT_FOUND,
    MSG_USER_UPDATED_TEMPLATE,
    UserIdAlreadyExistsError,
    UserNotFoundError,
    UserService,
    UserValidationError,
)
from src.shared.models.user_security import UserSecurity
from src.shared.schemas.user_schema import (
    UserCreateRequest,
    UserCreateResponse,
    UserDeleteResponse,
    UserListItem,
    UserListRequest,
    UserListResponse,
    UserUpdateRequest,
    UserUpdateResponse,
)

# ============================================================================
# Module-level test constants
# ============================================================================
#
# These constants are shared across fixtures and tests to guarantee a
# single source of truth for every test-value string. They correspond to
# the fixed-width COBOL PIC X(N) widths in ``CSUSR01Y.cpy`` (the
# SEC-USER-DATA 80-byte record) so that test inputs respect the same
# length envelopes the service / schema layers enforce at runtime.
# ============================================================================

#: 8-character regular-user identifier matching ``SEC-USR-ID PIC X(08)``.
#: Used for the ``sample_user`` fixture (``usr_type='U'``). In the COBOL
#: original, this value exercised the ``CDEMO-USRTYP-USER`` 88-level
#: condition from ``COCOM01Y.cpy``.
_TEST_USER_ID: str = "TESTUSER"

#: 8-character admin-user identifier. Exercises the
#: ``CDEMO-USRTYP-ADMIN`` (value ``'A'``) 88-level path from
#: ``COCOM01Y.cpy``. In the COBOL original, admin sign-on routed to
#: ``COADM01C`` via ``EXEC CICS XCTL PROGRAM('COADM01C')``; here the
#: admin is only used to assert that the service correctly persists /
#: returns ``usr_type='A'`` when that value is supplied in the
#: :class:`UserCreateRequest` payload.
_TEST_ADMIN_USER_ID: str = "ADMIN001"

#: Cleartext test password for the ``sample_user`` / ``sample_admin``
#: fixtures. 8 characters to match the COBOL ``SEC-USR-PWD PIC X(08)``
#: constraint that still applies at the schema layer
#: (``UserCreateRequest.password`` has ``max_length=8``). Safe to
#: hard-code in tests -- corresponds to no real credential.
_TEST_PLAINTEXT_PASSWORD: str = "PASSWORD"

#: Second cleartext password used in the update flow. Differs from the
#: first to exercise the BCrypt-rehash path in
#: :meth:`UserService.update_user`. 8 chars max (PIC X(08) ceiling).
_TEST_NEW_PASSWORD: str = "NEWPASS1"

#: Dedicated BCrypt context for test fixtures. Uses exactly the same
#: scheme (``bcrypt``) that :mod:`src.api.services.user_service`
#: configures on its module-level :data:`pwd_context`, so hashes


# ============================================================================
# Test fixtures
# ============================================================================
#
# Fixtures in this module are intentionally *local* (not promoted to a
# :mod:`conftest.py`). This pattern matches
# :mod:`tests.unit.test_services.test_auth_service` and keeps every
# fixture definition co-located with the tests that rely on it, at the
# modest cost of re-declaring the same :data:`mock_db_session` /
# :data:`user_service` fixtures in multiple modules. The trade-off is
# worthwhile for unit tests: each service class has its own fixture
# semantics (e.g. :class:`AuthService` fixtures hash ``ADMIN`` twice by
# default while :class:`UserService` fixtures are keyed off
# ``TESTUSER``), and keeping them local avoids action-at-a-distance
# fixture sharing.
#
# ``pytest-asyncio`` is configured with ``asyncio_mode = "auto"`` in
# :file:`pyproject.toml`, so async fixture resolution is automatic.
# Async tests still explicitly use ``@pytest.mark.asyncio`` for
# readability and defensiveness in case the mode changes.
# ============================================================================


@pytest.fixture
def mock_db_session() -> AsyncMock:
    """Return an :class:`AsyncMock` styled as an SQLAlchemy async session.

    The mock is specced against :class:`AsyncSession` so that attribute
    access obeys the real class's surface: ``execute`` / ``add`` /
    ``delete`` / ``flush`` / ``scalars`` / ``commit`` / ``rollback`` /
    ``close`` are all present; any typo (e.g. ``adds``) raises
    :class:`AttributeError` at test time, preventing silent drift.

    The underlying :meth:`UserService.list_users` implementation calls
    ``await self.db.execute(stmt)`` twice per invocation (once for the
    page query, once for the count query); per-test setup must supply
    a ``side_effect`` list with two elements. :meth:`create_user`,
    :meth:`update_user` and :meth:`delete_user` each call ``execute``
    exactly once, followed by :meth:`add` / :meth:`delete` and
    :meth:`flush`.

    Returns
    -------
    AsyncMock
        A fresh mock per test (pytest creates a new instance for each
        test that depends on this fixture), so tests never cross-
        contaminate via shared state.

    Notes
    -----
    * In the COBOL original every CRUD program opened the ``USRSEC``
      VSAM file via CICS-managed access (``EXEC CICS READ / WRITE /
      REWRITE / DELETE FILE('USRSEC')``). The Python migration
      delegates that file-handle role to the SQLAlchemy
      :class:`AsyncSession`, which is exactly what this mock stands in
      for.
    """

    session: AsyncMock = AsyncMock(spec=AsyncSession)
    # Explicitly mark methods that are awaitable so AsyncMock wires the
    # coroutine protocol for them. ``AsyncMock(spec=...)`` already does
    # this for the async methods defined on AsyncSession, but we
    # re-assert ``execute`` / ``flush`` / ``delete`` here so that tests
    # can override ``side_effect`` / ``return_value`` without worrying
    # about the default spec behaviour.
    session.execute = AsyncMock()
    session.flush = AsyncMock()
    session.delete = AsyncMock()
    # ``add`` is synchronous on the real class, so it stays a MagicMock.
    session.add = MagicMock()
    return session


@pytest.fixture
def user_service(mock_db_session: AsyncMock) -> UserService:
    """Return a :class:`UserService` wired to the mocked async session.

    This is the System Under Test: every CRUD assertion runs against
    the real :class:`UserService` implementation and only the database
    boundary is mocked.
    """

    return UserService(db=mock_db_session)


@pytest.fixture
def pwd_context() -> CryptContext:
    """Return a :class:`CryptContext` for BCrypt-hashing test passwords.

    This is the same context type used by
    :data:`src.api.services.user_service.pwd_context`, so hashes
    produced here are verifiable by the service's :meth:`verify`
    without any additional configuration.

    The fixture returns a **module-shared** context (the constant
    :data:`_TEST_PWD_CONTEXT`) so that every test pays the BCrypt
    ``$2b$`` initialisation cost exactly once per test session. BCrypt
    hashing is intentionally expensive (~100 ms per :meth:`hash` call
    at default cost factor 12), so amortising fixture setup matters.
    """

    return _TEST_PWD_CONTEXT


@pytest.fixture
def sample_user(pwd_context: CryptContext) -> UserSecurity:
    """Return a regular-user fixture (``usr_type='U'``) with BCrypt hash.

    Field mapping -- the Python kwargs align to the
    ``SEC-USER-DATA`` 80-byte VSAM record defined in
    ``CSUSR01Y.cpy`` as follows:

    ========================  ==================================  ===========
    Python kwarg              COBOL field (CSUSR01Y.cpy)          PIC clause
    ========================  ==================================  ===========
    ``user_id='TESTUSER'``    ``SEC-USR-ID``                      ``PIC X(08)``
    ``first_name='Test'``     ``SEC-USR-FNAME``                   ``PIC X(20)``
    ``last_name='User'``      ``SEC-USR-LNAME``                   ``PIC X(20)``
    ``password=<BCrypt hash>``  ``SEC-USR-PWD``                   ``PIC X(08)``*
    ``usr_type='U'``          ``SEC-USR-TYPE``                    ``PIC X(01)``
    ========================  ==================================  ===========

    * The COBOL field width was 8 characters (plaintext); the Python
      migration widens the column to ``VARCHAR(60)`` to accommodate
      the BCrypt digest. Cleartext is never persisted.
    """

    return UserSecurity(
        user_id=_TEST_USER_ID,
        first_name="Test",
        last_name="User",
        password=pwd_context.hash(_TEST_PLAINTEXT_PASSWORD),
        usr_type="U",
    )


@pytest.fixture
def sample_admin(pwd_context: CryptContext) -> UserSecurity:
    """Return an admin-user fixture (``usr_type='A'``) with BCrypt hash.

    Exercises the ``88 CDEMO-USRTYP-ADMIN VALUE 'A'`` path from
    ``COCOM01Y.cpy``. In COBOL, setting this bit routed sign-on to
    ``COADM01C`` via ``EXEC CICS XCTL``; in Python the service
    merely persists ``usr_type='A'`` and it is the caller's
    responsibility (downstream middleware / router) to authorise
    admin-only endpoints accordingly.
    """

    return UserSecurity(
        user_id=_TEST_ADMIN_USER_ID,
        first_name="Admin",
        last_name="User",
        password=pwd_context.hash(_TEST_PLAINTEXT_PASSWORD),
        usr_type="A",
    )


@pytest.fixture
def sample_users_list(pwd_context: CryptContext) -> list[UserSecurity]:
    """Return 15 :class:`UserSecurity` instances for pagination tests.

    The list is deliberately sized at 15 (one-and-a-half pages at the
    default 10-per-page pagination) to exercise both the first full
    page (indexes 0-9, 10 rows) and the second partial page (indexes
    10-14, 5 rows). This parallels the COBOL behaviour where
    ``COUSR00C`` filled ``COUSR00.CPY``'s 10 repeated row groups
    (USRID01-10 + FNAME01-10 + LNAME01-10 + UTYPE01-10) then set
    ``MORE-USRDATA-RECORDS`` to trigger a next-page indicator.

    All 15 users have distinct identifiers
    (``USER0001`` through ``USER0015``) and alternate between
    ``usr_type='U'`` and ``usr_type='A'`` so paginated responses
    contain both types.
    """

    shared_hash: str = pwd_context.hash(_TEST_PLAINTEXT_PASSWORD)
    users: list[UserSecurity] = []
    for index in range(1, 16):
        users.append(
            UserSecurity(
                user_id=f"USER{index:04d}",
                first_name=f"First{index:02d}",
                last_name=f"Last{index:02d}",
                password=shared_hash,
                # Alternate type so pagination output includes both types.
                usr_type="A" if index % 2 == 0 else "U",
            )
        )
    return users


# ============================================================================
# Helpers
# ============================================================================
#
# These helpers de-duplicate the mock-wiring boilerplate that every
# test would otherwise repeat. They translate a small Python-native
# specification (e.g. "the next execute() call should return a single
# user") into the SQLAlchemy 2.x result-object contract
# (``result.scalars().all()``, ``result.scalar_one()`` etc.) that the
# :class:`UserService` production code exercises.
# ============================================================================


def _build_page_result(rows: list[UserSecurity]) -> MagicMock:
    """Return a mock ``Result`` for a ``SELECT`` page query.

    Replicates the SQLAlchemy 2.x API contract used by
    :meth:`UserService.list_users`::

        page_result = await self.db.execute(stmt)
        users_rows = list(page_result.scalars().all())

    i.e. ``result.scalars()`` returns a ``ScalarResult`` whose
    :meth:`all` method yields the projected ORM rows. The returned
    :class:`MagicMock` faithfully exposes that sub-call chain.

    Parameters
    ----------
    rows
        The ORM rows the page query should yield. The same list is
        returned verbatim from ``scalars().all()``.
    """

    page_result: MagicMock = MagicMock()
    scalars_proxy: MagicMock = MagicMock()
    scalars_proxy.all = MagicMock(return_value=list(rows))
    page_result.scalars = MagicMock(return_value=scalars_proxy)
    return page_result


def _build_count_result(total_count: int) -> MagicMock:
    """Return a mock ``Result`` for a ``SELECT COUNT(*)`` query.

    Replicates the SQLAlchemy 2.x API contract used by
    :meth:`UserService.list_users`::

        count_result = await self.db.execute(count_stmt)
        total_count = count_result.scalar_one() or 0

    i.e. ``result.scalar_one()`` returns the single integer count.
    """

    count_result: MagicMock = MagicMock()
    count_result.scalar_one = MagicMock(return_value=total_count)
    return count_result


def _build_single_user_result(user: UserSecurity | None) -> MagicMock:
    """Return a mock ``Result`` for a lookup-by-primary-key query.

    Replicates the SQLAlchemy 2.x API contract used by
    :meth:`UserService.create_user`, :meth:`UserService.update_user`
    and :meth:`UserService.delete_user`::

        result = await self.db.execute(stmt)
        user = result.scalar_one_or_none()

    i.e. ``result.scalar_one_or_none()`` returns either the single
    matched row or ``None`` if no row matched.

    Parameters
    ----------
    user
        The user to return, or ``None`` to simulate a NOTFND /
        duplicate-absent response from the database.
    """

    result: MagicMock = MagicMock()
    result.scalar_one_or_none = MagicMock(return_value=user)
    return result


#: produced here verify cleanly against hashes produced there. Shared
#: with :mod:`tests.unit.test_services.test_auth_service` (same
#: BCrypt parameters).
_TEST_PWD_CONTEXT: CryptContext = CryptContext(schemes=["bcrypt"], deprecated="auto")

#: Expected BCrypt hash prefix (algorithm identifier ``$2b$``) followed
#: by the cost-factor separator. Every hash produced by the ``bcrypt``
#: scheme starts with this prefix; we assert on it in the password-
#: hashing tests to verify the COBOL ``PIC X(08)`` plaintext has been
#: properly replaced by a BCrypt digest before INSERT / UPDATE.
_BCRYPT_HASH_PREFIX: str = "$2b$"

#: Canonical BCrypt hash length (60 characters). Every passlib BCrypt
#: hash is exactly this length regardless of the cleartext size. Used
#: in assertions to verify the service has rehashed (not stored
#: plaintext).
_BCRYPT_HASH_LENGTH: int = 60

# ============================================================================
# Phase 3 -- User List Tests (Feature F-018, COUSR00C.cbl)
# ============================================================================
#
# The COBOL source is ``app/cbl/COUSR00C.cbl`` (transaction CU00, ~695
# lines). Its ``PROCESS-ENTER-KEY`` paragraph (L179-219) drives a
# browse-mode cursor over the ``USRSEC`` VSAM KSDS via
# ``STARTBR-USER-SEC-FILE`` (L583-615) +
# ``READNEXT-USER-SEC-FILE`` (L618-650), then hand-unrolls 10
# ``USRID`` / ``FNAME`` / ``LNAME`` / ``UTYPE`` fields from
# ``COUSR00.CPY`` (the symbolic map copybook) into the output screen.
# The Python translation replaces the STARTBR / READNEXT dance with a
# pair of SQLAlchemy SELECTs (rows + count) whose OFFSET / LIMIT
# clauses mirror the browse-cursor semantics, and whose LIKE filter
# replaces the COBOL ``USRIDINI`` partial-key input.
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_users_default_page_size_10(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_users_list: list[UserSecurity],
) -> None:
    """Default pagination returns at most 10 rows (``COUSR00.CPY`` layout).

    **Arrange** -- the database is primed to return 10 users for the
    page query and 15 for the count query (because the fixture list
    contains 15 rows total).

    **Act** -- invoke :meth:`UserService.list_users` with a default
    :class:`UserListRequest` (``page=1``, ``page_size=10``, no filter).

    **Assert** -- (a) the response is a :class:`UserListResponse`,
    (b) exactly 10 items are returned, (c) each item is a
    :class:`UserListItem`, (d) the ``total_count`` reflects the real
    table size (15) not the page size (10), and (e) the response is on
    page 1 (the request default).

    COBOL mapping
    -------------
    The fixed-size ``COUSR00.CPY`` symbolic map has exactly 10
    repeated row groups (``USRID01`` through ``USRID10``,
    ``FNAME01-10``, ``LNAME01-10``, ``UTYPE01-10``). When
    ``COUSR00C`` filled these 10 slots it set
    ``88 USER-SEC-EOF`` and emitted the screen; one screen == one page
    == 10 rows. The Python ``page_size`` default preserves this
    exact cadence.
    """

    # Arrange: 10 rows for page 1, total count 15.
    first_ten_rows: list[UserSecurity] = sample_users_list[:10]
    mock_db_session.execute.side_effect = [
        _build_page_result(first_ten_rows),
        _build_count_result(15),
    ]

    # Act
    request: UserListRequest = UserListRequest()  # all defaults
    response: UserListResponse = await user_service.list_users(request)

    # Assert -- response shape
    assert isinstance(response, UserListResponse)
    assert len(response.users) == 10
    assert all(isinstance(item, UserListItem) for item in response.users)
    # Assert -- pagination metadata
    assert response.page == 1
    assert response.total_count == 15
    # Assert -- the service made exactly two DB calls (page + count).
    assert mock_db_session.execute.await_count == 2


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_users_pagination(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_users_list: list[UserSecurity],
) -> None:
    """Page 2 applies the correct OFFSET (``(page-1)*page_size``).

    **Arrange** -- prime the database with the second "page" (rows
    index 10-14, 5 rows) and the full count (15). Capture the actual
    compiled SELECT statements that the service emits so the test can
    introspect the OFFSET / LIMIT clauses.

    **Act** -- invoke :meth:`UserService.list_users` with
    ``UserListRequest(page=2, page_size=10)``.

    **Assert** -- (a) the response carries the 5 rows from the second
    page, (b) the response's ``page`` field echoes the request
    (``page=2``), and (c) the compiled SELECT statement's OFFSET
    clause equals ``10`` (computed as ``(2-1) * 10``).

    COBOL mapping
    -------------
    The original ``COUSR00C`` browse-cursor restart was implemented
    by STARTBR GTEQ / READNEXT loops that skipped the first
    ``PAGE-SIZE-NUM`` records (L618-650). OFFSET / LIMIT gives the
    same behaviour declaratively.
    """

    # Arrange: capture the SQL statements as they are passed to execute().
    captured_statements: list[Any] = []

    # Use a manual side_effect function so we can both capture the
    # statement and still return the expected mock result.
    result_queue: list[MagicMock] = [
        _build_page_result(sample_users_list[10:15]),  # 5 rows (page 2)
        _build_count_result(15),
    ]

    async def capture_execute(stmt: Any, *args: Any, **kwargs: Any) -> MagicMock:
        captured_statements.append(stmt)
        return result_queue.pop(0)

    mock_db_session.execute.side_effect = capture_execute

    # Act
    request: UserListRequest = UserListRequest(page=2, page_size=10)
    response: UserListResponse = await user_service.list_users(request)

    # Assert -- response carries page 2 rows.
    assert len(response.users) == 5
    assert response.page == 2
    assert response.total_count == 15

    # Assert -- the first captured statement (the page SELECT) has
    # OFFSET=10 and LIMIT=10. SQLAlchemy stores these on the compiled
    # Select object's private ``_offset_clause`` / ``_limit_clause``
    # attributes; we read them via the public ``offset`` / ``limit``
    # methods by compiling to SQL.
    assert len(captured_statements) == 2
    page_select: Any = captured_statements[0]
    compiled_sql: str = str(page_select.compile(compile_kwargs={"literal_binds": True}))
    # The compiled string contains ``OFFSET 10`` (and ``LIMIT 10``)
    # because literal_binds inlines the numeric parameters.
    assert "OFFSET 10" in compiled_sql.upper()
    assert "LIMIT 10" in compiled_sql.upper()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_users_filter_by_user_id_prefix(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_users_list: list[UserSecurity],
) -> None:
    """The ``user_id`` filter generates a ``WHERE user_id LIKE 'prefix%'``.

    **Arrange** -- prime the database to return any result (the
    content is unimportant; we only assert on the **query** shape).
    Capture the SELECT statement so the test can inspect its WHERE
    clause.

    **Act** -- invoke :meth:`UserService.list_users` with
    ``UserListRequest(user_id='USER')``.

    **Assert** -- the compiled WHERE clause contains the LIKE pattern
    ``USER%`` (with appropriate escape handling).

    COBOL mapping
    -------------
    In ``COUSR00C`` the user entered a partial identifier in the
    ``USRIDINI`` input field (L158-165); the program then used
    ``MOVE USRIDINI TO WS-USER-ID`` followed by STARTBR GTEQ
    (L583-608) to position the browse cursor at the first matching
    key. The Python translation replaces the prefix-STARTBR with a
    parametrised LIKE, which is both more portable (no VSAM-specific
    positioning) and safer (escape metacharacters explicitly).
    """

    # Arrange: minimal result set (content doesn't matter for this test).
    captured_statements: list[Any] = []
    result_queue: list[MagicMock] = [
        _build_page_result(sample_users_list[:3]),
        _build_count_result(3),
    ]

    async def capture_execute(stmt: Any, *args: Any, **kwargs: Any) -> MagicMock:
        captured_statements.append(stmt)
        return result_queue.pop(0)

    mock_db_session.execute.side_effect = capture_execute

    # Act
    request: UserListRequest = UserListRequest(user_id="USER")
    response: UserListResponse = await user_service.list_users(request)

    # Assert -- response returned without error.
    assert isinstance(response, UserListResponse)
    assert response.total_count == 3

    # Assert -- the first captured statement (page query) contains a
    # LIKE clause with the requested prefix. The second captured
    # statement (count query) should also contain the same filter.
    assert len(captured_statements) == 2
    page_sql: str = str(captured_statements[0].compile(compile_kwargs={"literal_binds": True})).upper()
    count_sql: str = str(captured_statements[1].compile(compile_kwargs={"literal_binds": True})).upper()
    # LIKE with prefix pattern should appear in both statements.
    assert "LIKE" in page_sql
    assert "USER%" in page_sql
    assert "LIKE" in count_sql
    assert "USER%" in count_sql


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_users_empty_result(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """An empty table returns an empty list (``WHEN ENDFILE`` equivalent).

    **Arrange** -- prime the database to return zero rows on the page
    query and ``0`` on the count query.

    **Act** -- invoke :meth:`UserService.list_users` with defaults.

    **Assert** -- (a) the response is a :class:`UserListResponse`,
    (b) ``users`` is an empty list (not ``None``), (c)
    ``total_count`` is ``0``.

    COBOL mapping
    -------------
    In ``COUSR00C`` an empty ``USRSEC`` file caused
    ``EXEC CICS STARTBR ... RESP(WS-RESP-CD)`` to return
    ``DFHRESP(NOTFND)``; the ``WHEN ENDFILE`` / ``WHEN NOTFND`` arms
    (L644-650) then displayed "You are at the top of the page..." or
    similar. The Python translation simply returns an empty
    ``users`` array -- the caller (router layer) is free to translate
    that to the COBOL-equivalent message.
    """

    # Arrange: empty result set, zero count.
    mock_db_session.execute.side_effect = [
        _build_page_result([]),
        _build_count_result(0),
    ]

    # Act
    request: UserListRequest = UserListRequest()
    response: UserListResponse = await user_service.list_users(request)

    # Assert
    assert isinstance(response, UserListResponse)
    assert response.users == []  # Empty list, not None.
    assert response.total_count == 0
    assert response.page == 1  # default


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_users_no_password_in_response(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_users_list: list[UserSecurity],
) -> None:
    """List response items NEVER expose the password (hash) field.

    **CRITICAL SECURITY TEST**: validates AAP Section 0.7.2 (Security
    Requirements, BCrypt mandated for passwords) -- the hashed
    password is a privileged attribute that must never leak via the
    list API. The :class:`UserListItem` Pydantic schema has **no**
    ``password`` field, and this test verifies:

    1. The service-produced item does not carry a ``password``
       attribute when serialised via :meth:`model_dump`.
    2. The raw response model dump has no top-level ``password`` key.

    **Arrange** -- return the first 5 fixture users (they each have a
    real BCrypt hash in their ``password`` column; we want to prove
    that hash does *not* appear in the API response).

    **Act** -- invoke :meth:`UserService.list_users`.

    **Assert** -- no item has a ``password`` key when dumped.

    COBOL mapping
    -------------
    The COBOL ``COUSR00.CPY`` symbolic map is the ultimate authority
    here: it contains ``USRID01-10``, ``FNAME01-10``, ``LNAME01-10``,
    ``UTYPE01-10`` but **no** ``PASSWD01-10`` field. The 3270 terminal
    could therefore never display a password in the user-list screen,
    and the Python API preserves that invariant by construction.
    """

    # Arrange: 5 users, each with a real BCrypt hash in .password.
    five_users: list[UserSecurity] = sample_users_list[:5]
    for user in five_users:
        # Paranoia: confirm the fixture really has a BCrypt hash.
        assert user.password.startswith(_BCRYPT_HASH_PREFIX)
    mock_db_session.execute.side_effect = [
        _build_page_result(five_users),
        _build_count_result(5),
    ]

    # Act
    response: UserListResponse = await user_service.list_users(UserListRequest())

    # Assert -- 5 items, none carrying a password.
    assert len(response.users) == 5
    for item in response.users:
        dumped: dict[str, Any] = item.model_dump()
        assert "password" not in dumped, f"UserListItem accidentally exposed password field: {dumped!r}"
        # Belt-and-braces: the model class itself must not declare the
        # field. This catches any future regression that adds a
        # ``password`` attribute to UserListItem.
        assert "password" not in type(item).model_fields, (
            "UserListItem schema must not declare a 'password' field (AAP Section 0.7.2 Security Requirements)."
        )

    # Belt-and-braces: the full response dump is also password-free.
    response_dump: dict[str, Any] = response.model_dump()
    for item_dump in response_dump["users"]:
        assert "password" not in item_dump


# ============================================================================
# Phase 4 -- User Add Tests (Feature F-019, COUSR01C.cbl)
# ============================================================================
#
# The COBOL source is ``app/cbl/COUSR01C.cbl`` (transaction CU01, ~299
# lines). The ``ADD-USER-SEC-FILE`` paragraph (L236-275) implements
# the WRITE flow with DUPREC / DUPKEY detection:
#
#     EXEC CICS WRITE
#         FILE     ('USRSEC')
#         FROM     (SEC-USER-DATA)
#         RIDFLD   (SEC-USR-ID)
#         KEYLENGTH(LENGTH OF SEC-USR-ID)
#         RESP     (WS-RESP-CD)
#     END-EXEC.
#     IF  WS-RESP-CD = DFHRESP(NORMAL)
#         MOVE 'User ... has been added ...' TO WS-MESSAGE
#     WHEN (WS-RESP-CD = DFHRESP(DUPKEY)) OR
#          (WS-RESP-CD = DFHRESP(DUPREC))
#         MOVE 'User ID already exist...' TO WS-MESSAGE
#
# The Python translation replaces the WRITE with a SELECT-then-INSERT
# sequence (pre-flight duplicate check + ``session.add`` +
# ``session.flush``). The DUPREC arm is exercised by
# ``test_create_user_duplicate_rejected``. Password hashing (which
# the COBOL original did *not* do -- it stored the 8-char plaintext
# directly) is asserted by
# ``test_create_user_password_hashed_with_bcrypt``, implementing the
# mandated migration from cleartext to BCrypt (AAP Section 0.7.2).
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_create_user_success(
    user_service: UserService,
    mock_db_session: AsyncMock,
    pwd_context: CryptContext,
) -> None:
    """Successful INSERT happy path (``WHEN NORMAL`` after WRITE).

    **Arrange** -- prime the duplicate-check SELECT to return ``None``
    (i.e. the target ``user_id`` is unused), so the service proceeds
    to INSERT.

    **Act** -- invoke :meth:`UserService.create_user` with a fully
    valid :class:`UserCreateRequest` (all fields populated, valid
    user_type).

    **Assert** -- (a) the response is a :class:`UserCreateResponse`,
    (b) its ``user_id`` / ``first_name`` / ``last_name`` /
    ``user_type`` echo the request, (c) the success message matches
    the exact ``MSG_USER_ADDED_TEMPLATE`` wording (byte-for-byte
    COBOL fidelity from ``COUSR01C`` line 269), (d)
    :meth:`AsyncSession.add` was called exactly once with a
    :class:`UserSecurity` instance carrying the hashed password, and
    (e) :meth:`AsyncSession.flush` was awaited exactly once.

    COBOL mapping
    -------------
    ``COUSR01C`` lines 236-275 (``ADD-USER-SEC-FILE`` paragraph):
    the EXEC CICS WRITE becomes ``session.add`` + ``session.flush``;
    the ``WHEN NORMAL`` success arm becomes the successful return of
    a :class:`UserCreateResponse` with the correctly templated
    message.
    """

    # Arrange: duplicate check returns None (no existing user).
    mock_db_session.execute.return_value = _build_single_user_result(None)

    # Act
    request: UserCreateRequest = UserCreateRequest(
        user_id="NEWUSER1",
        first_name="New",
        last_name="User",
        password=_TEST_PLAINTEXT_PASSWORD,
        user_type="U",
    )
    response: UserCreateResponse = await user_service.create_user(request)

    # Assert -- response shape.
    assert isinstance(response, UserCreateResponse)
    assert response.user_id == "NEWUSER1"
    assert response.first_name == "New"
    assert response.last_name == "User"
    assert response.user_type == "U"
    # Byte-for-byte COBOL message fidelity.
    assert response.message == MSG_USER_ADDED_TEMPLATE.format(user_id="NEWUSER1")

    # Assert -- the service made exactly one SELECT (duplicate check).
    assert mock_db_session.execute.await_count == 1

    # Assert -- session.add was called with a single UserSecurity row.
    mock_db_session.add.assert_called_once()
    added_user: UserSecurity = mock_db_session.add.call_args.args[0]
    assert isinstance(added_user, UserSecurity)
    assert added_user.user_id == "NEWUSER1"
    assert added_user.first_name == "New"
    assert added_user.last_name == "User"
    assert added_user.usr_type == "U"  # ORM attribute name is usr_type
    # Password must be hashed (not plaintext).
    assert added_user.password != _TEST_PLAINTEXT_PASSWORD
    assert added_user.password.startswith(_BCRYPT_HASH_PREFIX)
    # Hash should verify against the original plaintext.
    assert pwd_context.verify(_TEST_PLAINTEXT_PASSWORD, added_user.password)

    # Assert -- flush was awaited exactly once.
    mock_db_session.flush.assert_awaited_once()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_create_user_password_hashed_with_bcrypt(
    user_service: UserService,
    mock_db_session: AsyncMock,
    pwd_context: CryptContext,
) -> None:
    """Password is stored as BCrypt hash, NOT plaintext.

    **CRITICAL SECURITY TEST** (AAP Section 0.7.2 Security
    Requirements): the COBOL original stored ``SEC-USR-PWD`` as
    8-character plaintext in VSAM; the Python migration MUST hash it
    with BCrypt before INSERT. This test verifies that invariant.

    **Arrange** -- duplicate check returns ``None``.

    **Act** -- :meth:`UserService.create_user` with
    ``password='PASSWORD'``.

    **Assert** -- the ``password`` field on the
    :class:`UserSecurity` instance passed to :meth:`session.add`:

    1. Does **NOT** equal the plaintext ``'PASSWORD'``.
    2. **Starts** with the BCrypt algorithm prefix ``'$2b$'``.
    3. Is exactly 60 characters long (standard BCrypt hash length).
    4. Round-trips via :meth:`CryptContext.verify` against the
       original plaintext.

    COBOL mapping
    -------------
    Original COBOL: ``MOVE PASSWDI TO SEC-USR-PWD`` (COUSR01C L220)
    -- direct copy of the 8-character plaintext password. Python
    target: hash first, then INSERT. The ``SEC-USR-PWD`` column is
    accordingly widened from ``PIC X(08)`` to ``VARCHAR(60)`` to
    accommodate the digest.
    """

    # Arrange
    mock_db_session.execute.return_value = _build_single_user_result(None)

    # Act
    request: UserCreateRequest = UserCreateRequest(
        user_id="HASHTEST",
        first_name="Hash",
        last_name="Tester",
        password=_TEST_PLAINTEXT_PASSWORD,
        user_type="U",
    )
    await user_service.create_user(request)

    # Assert: inspect the UserSecurity row that was handed to session.add.
    mock_db_session.add.assert_called_once()
    added_user: UserSecurity = mock_db_session.add.call_args.args[0]

    # (1) Not plaintext.
    assert added_user.password != _TEST_PLAINTEXT_PASSWORD, (
        "Password was stored as plaintext! BCrypt hashing is mandatory per AAP Section 0.7.2."
    )

    # (2) BCrypt prefix.
    assert added_user.password.startswith(_BCRYPT_HASH_PREFIX), (
        f"Stored password must begin with BCrypt prefix {_BCRYPT_HASH_PREFIX!r}; got {added_user.password!r}"
    )

    # (3) Standard BCrypt hash length (60 characters).
    assert len(added_user.password) == _BCRYPT_HASH_LENGTH, (
        f"Stored BCrypt hash length is {len(added_user.password)} (expected {_BCRYPT_HASH_LENGTH})."
    )

    # (4) Round-trip verification.
    assert pwd_context.verify(_TEST_PLAINTEXT_PASSWORD, added_user.password), (
        "BCrypt verify() failed to match the original plaintext."
    )
    # Negative round-trip: a different plaintext must NOT verify.
    assert not pwd_context.verify("WRONGPWD", added_user.password)


@pytest.mark.unit
@pytest.mark.asyncio
async def test_create_user_duplicate_rejected(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Creating a user with an existing ``user_id`` raises ``UserIdAlreadyExistsError``.

    **CRITICAL**: exercises the ``DUPKEY/DUPREC`` arm of the COBOL
    WRITE logic (``COUSR01C`` L261-268) which must be preserved
    byte-for-byte in the Python error message per AAP Section 0.7.1.

    **Arrange** -- prime the duplicate-check SELECT to return an
    existing :class:`UserSecurity` row (``sample_user``).

    **Act** -- invoke :meth:`UserService.create_user` with the
    same ``user_id``.

    **Assert** -- (a) :class:`UserIdAlreadyExistsError` is raised,
    (b) its ``.message`` attribute equals
    :data:`MSG_USER_ID_ALREADY_EXISTS` verbatim (note: "exist",
    not "exists" -- COBOL-fidelity preserves the original grammar),
    (c) :meth:`session.add` was **not** called, (d)
    :meth:`session.flush` was **not** awaited.

    COBOL mapping
    -------------
    ``COUSR01C`` L261-268:

    .. code-block:: cobol

        WHEN (WS-RESP-CD = DFHRESP(DUPKEY)) OR
             (WS-RESP-CD = DFHRESP(DUPREC))
            MOVE 'User ID already exist...' TO WS-MESSAGE

    The Python equivalent is the single raise statement in
    :meth:`UserService.create_user`.
    """

    # Arrange: duplicate check returns an existing user.
    mock_db_session.execute.return_value = _build_single_user_result(sample_user)

    # Act + Assert: the duplicate error propagates.
    request: UserCreateRequest = UserCreateRequest(
        user_id=_TEST_USER_ID,  # same ID as sample_user
        first_name="Another",
        last_name="Request",
        password=_TEST_PLAINTEXT_PASSWORD,
        user_type="U",
    )
    with pytest.raises(UserIdAlreadyExistsError) as exc_info:
        await user_service.create_user(request)

    # Exact COBOL message fidelity.
    assert exc_info.value.message == MSG_USER_ID_ALREADY_EXISTS
    # The COBOL grammar uses "exist" (singular) not "exists"; confirm.
    assert "already exist" in MSG_USER_ID_ALREADY_EXISTS.lower()

    # The duplicate check was performed (1 execute call) but no INSERT
    # or flush occurred.
    assert mock_db_session.execute.await_count == 1
    mock_db_session.add.assert_not_called()
    mock_db_session.flush.assert_not_awaited()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_create_user_type_admin(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """``user_type='A'`` is persisted and echoed (``CDEMO-USRTYP-ADMIN``).

    **Arrange** -- duplicate check returns ``None``.

    **Act** -- create a user with ``user_type='A'``.

    **Assert** -- (a) the row passed to :meth:`session.add` has
    ``usr_type == 'A'`` (note ORM attribute spelling),
    (b) the response's ``user_type`` field equals ``'A'``.

    COBOL mapping
    -------------
    ``COCOM01Y.cpy`` defines ``88 CDEMO-USRTYP-ADMIN VALUE 'A'``.
    Administrators are routed by the sign-on program (``COSGN00C``)
    to the admin menu (``COADM01C``) instead of the user menu
    (``COMEN01C``); the ``SEC-USR-TYPE`` byte is the sole
    discriminator.
    """

    # Arrange
    mock_db_session.execute.return_value = _build_single_user_result(None)

    # Act
    request: UserCreateRequest = UserCreateRequest(
        user_id="ADMIN999",
        first_name="Admin",
        last_name="Super",
        password=_TEST_PLAINTEXT_PASSWORD,
        user_type="A",
    )
    response: UserCreateResponse = await user_service.create_user(request)

    # Assert
    mock_db_session.add.assert_called_once()
    added_user: UserSecurity = mock_db_session.add.call_args.args[0]
    # ORM attribute is ``usr_type`` (COBOL SEC-USR-TYPE).
    assert added_user.usr_type == "A"
    # Response uses the schema attribute ``user_type`` (no usr_ prefix).
    assert response.user_type == "A"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_create_user_type_regular(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """``user_type='U'`` is persisted and echoed (``CDEMO-USRTYP-USER``).

    Counterpart to :func:`test_create_user_type_admin`. Regular
    users route via ``COSGN00C`` to ``COMEN01C`` (user menu) rather
    than ``COADM01C``.

    **Arrange** -- duplicate check returns ``None``.

    **Act** -- create a user with ``user_type='U'``.

    **Assert** -- (a) ``usr_type == 'U'`` on the ORM row,
    (b) ``user_type == 'U'`` on the response.

    COBOL mapping
    -------------
    ``COCOM01Y.cpy`` defines ``88 CDEMO-USRTYP-USER VALUE 'U'``.
    """

    # Arrange
    mock_db_session.execute.return_value = _build_single_user_result(None)

    # Act
    request: UserCreateRequest = UserCreateRequest(
        user_id="REGUSER9",
        first_name="Reg",
        last_name="Ular",
        password=_TEST_PLAINTEXT_PASSWORD,
        user_type="U",
    )
    response: UserCreateResponse = await user_service.create_user(request)

    # Assert
    mock_db_session.add.assert_called_once()
    added_user: UserSecurity = mock_db_session.add.call_args.args[0]
    assert added_user.usr_type == "U"
    assert response.user_type == "U"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_create_user_invalid_type_rejected(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """Service layer rejects invalid ``user_type`` (defence-in-depth).

    **CRITICAL**: the ``user_type`` field is validated at **two**
    layers: (1) the Pydantic :class:`UserCreateRequest` schema
    (``_validate_user_type`` field validator) and (2) the
    :class:`UserService` itself (defence-in-depth re-check before
    the duplicate SELECT). This test exercises layer 2 by bypassing
    layer 1 via :meth:`model_construct`, then asserts that a
    :class:`UserValidationError` is raised before any database
    interaction.

    **Arrange** -- build a :class:`UserCreateRequest` with
    ``user_type='X'`` via :meth:`model_construct` (bypasses Pydantic
    validation).

    **Act + Assert** -- the service raises
    :class:`UserValidationError`; no database call occurs.

    COBOL mapping
    -------------
    ``COCOM01Y.cpy`` declares only two 88-level conditions:
    ``CDEMO-USRTYP-ADMIN`` (``'A'``) and ``CDEMO-USRTYP-USER``
    (``'U'``). Any other byte value would have failed the COBOL
    VALIDATE-USER-TYPE subroutine (``COUSR01C`` L204-208). The
    Python service preserves this invariant.
    """

    # Arrange: bypass Pydantic validation so 'X' reaches the service.
    # model_construct skips validators and is the canonical way to
    # build a "malicious" input for defence-in-depth testing.
    bad_request: UserCreateRequest = UserCreateRequest.model_construct(
        user_id="BADTYPE1",
        first_name="Bad",
        last_name="Type",
        password=_TEST_PLAINTEXT_PASSWORD,
        user_type="X",  # NOT in {'A', 'U'}
    )

    # Act + Assert
    with pytest.raises(UserValidationError) as exc_info:
        await user_service.create_user(bad_request)

    # The error message should reference the invalid user_type value.
    assert "user_type" in exc_info.value.message.lower()
    assert "'X'" in exc_info.value.message or '"X"' in exc_info.value.message

    # No DB interaction occurred -- validation halts before SELECT.
    mock_db_session.execute.assert_not_awaited()
    mock_db_session.add.assert_not_called()
    mock_db_session.flush.assert_not_awaited()


# ============================================================================
# Phase 5 -- User Update Tests (Feature F-020, COUSR02C.cbl)
# ============================================================================
#
# The COBOL source is ``app/cbl/COUSR02C.cbl`` (transaction CU02, ~414
# lines). The ``READ-USER-SEC-FILE`` paragraph (L330-361) performs a
# READ UPDATE and the ``UPDATE-USER-SEC-FILE`` paragraph (L358-392)
# performs the REWRITE; both execute under a CICS logical unit of
# work so that a failed REWRITE rolls back the READ UPDATE lock.
#
#     EXEC CICS READ FILE ('USRSEC') INTO (SEC-USER-DATA)
#         LENGTH(LENGTH OF SEC-USER-DATA) RIDFLD(SEC-USR-ID)
#         KEYLENGTH(LENGTH OF SEC-USR-ID)
#         UPDATE RESP(WS-RESP-CD) END-EXEC.
#
#     EXEC CICS REWRITE FILE ('USRSEC') FROM (SEC-USER-DATA)
#         LENGTH(LENGTH OF SEC-USER-DATA)
#         RESP(WS-RESP-CD) END-EXEC.
#
# The Python translation replaces the READ UPDATE / REWRITE pair with
# a SELECT + in-memory mutation + FLUSH; SQLAlchemy's session
# manages the optimistic / transactional semantics. The COBOL
# ``VALIDATE-UPDATE-FIELDS`` guard that rejected "no change" screens
# (L202-240) is preserved as
# :data:`MSG_PLEASE_MODIFY_TO_UPDATE`, which is raised when **every**
# field on the :class:`UserUpdateRequest` is ``None`` (empty PATCH).
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_user_success(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Successful update of ``first_name`` and ``last_name`` (REWRITE path).

    **Arrange** -- lookup returns the ``sample_user``
    (``user_id='TESTUSER'``).

    **Act** -- invoke :meth:`UserService.update_user` with a
    :class:`UserUpdateRequest` that changes both ``first_name`` and
    ``last_name``.

    **Assert** -- (a) ``sample_user.first_name`` / ``last_name`` are
    mutated in place, (b) the response carries the new values,
    (c) the success message equals
    :data:`MSG_USER_UPDATED_TEMPLATE` templated with the user id,
    (d) :meth:`session.flush` was awaited exactly once.

    COBOL mapping
    -------------
    ``COUSR02C`` L358-392 (``UPDATE-USER-SEC-FILE`` paragraph):
    REWRITE with the mutated copy of ``SEC-USER-DATA``. The Python
    translation mutates the attached ORM row; SQLAlchemy's unit-of-
    work detects the changes at flush time and emits the UPDATE
    statement.
    """

    # Arrange
    mock_db_session.execute.return_value = _build_single_user_result(sample_user)

    # Act
    request: UserUpdateRequest = UserUpdateRequest(
        first_name="Updated",
        last_name="Name",
    )
    response: UserUpdateResponse = await user_service.update_user(user_id=_TEST_USER_ID, request=request)

    # Assert -- in-place mutation of the ORM row.
    assert sample_user.first_name == "Updated"
    assert sample_user.last_name == "Name"
    # Unchanged fields remain intact.
    assert sample_user.usr_type == "U"  # from fixture
    assert sample_user.user_id == _TEST_USER_ID

    # Assert -- response content.
    assert isinstance(response, UserUpdateResponse)
    assert response.user_id == _TEST_USER_ID
    assert response.first_name == "Updated"
    assert response.last_name == "Name"
    assert response.user_type == "U"
    assert response.message == MSG_USER_UPDATED_TEMPLATE.format(user_id=_TEST_USER_ID)

    # Assert -- exactly one SELECT (lookup) and one FLUSH.
    assert mock_db_session.execute.await_count == 1
    mock_db_session.flush.assert_awaited_once()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_user_partial_update(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Only the provided fields are updated (PATCH semantics).

    **Arrange** -- lookup returns ``sample_user`` with
    ``first_name='Test'``, ``last_name='User'``, ``usr_type='U'``
    and a known BCrypt hash.

    **Act** -- invoke :meth:`UserService.update_user` with **only**
    ``first_name='Renamed'`` (``last_name``, ``password``,
    ``user_type`` all omitted / ``None``).

    **Assert** -- (a) ``first_name`` changes, (b) ``last_name``,
    ``password`` and ``usr_type`` remain untouched.

    COBOL mapping
    -------------
    ``COUSR02C`` applied changes field-by-field in
    ``UPDATE-USER-INFO`` (L262-280) using guarded MOVEs -- a blank /
    unchanged field in the screen simply left ``SEC-USER-DATA``
    alone before REWRITE. The Python translation preserves this
    exactly with ``if request.<field> is not None`` guards.
    """

    # Capture original state so we can verify non-mutation.
    original_last_name: str = sample_user.last_name
    original_password: str = sample_user.password
    original_usr_type: str = sample_user.usr_type

    # Arrange
    mock_db_session.execute.return_value = _build_single_user_result(sample_user)

    # Act
    request: UserUpdateRequest = UserUpdateRequest(first_name="Renamed")
    response: UserUpdateResponse = await user_service.update_user(user_id=_TEST_USER_ID, request=request)

    # Assert -- only first_name changed.
    assert sample_user.first_name == "Renamed"
    assert sample_user.last_name == original_last_name
    assert sample_user.password == original_password
    assert sample_user.usr_type == original_usr_type

    # Response mirrors the mutated ORM state.
    assert response.first_name == "Renamed"
    assert response.last_name == original_last_name
    assert response.user_type == original_usr_type


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_user_password_rehashed(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
    pwd_context: CryptContext,
) -> None:
    """Changing the password produces a fresh BCrypt digest.

    **Arrange** -- the ``sample_user`` has a BCrypt hash of
    :data:`_TEST_PLAINTEXT_PASSWORD`; capture its value for
    comparison.

    **Act** -- update the user with a **different** plaintext
    (:data:`_TEST_NEW_PASSWORD`).

    **Assert** -- (a) the stored hash changes, (b) the new hash is a
    valid BCrypt digest (``$2b$`` prefix, 60 chars), (c) the new hash
    verifies against the new plaintext, (d) the new hash does **not**
    verify against the old plaintext.

    COBOL mapping
    -------------
    In the COBOL original (``COUSR02C`` L275) ``MOVE PASSWDI TO
    SEC-USR-PWD`` simply overwrote the 8-character field; no
    hashing. The Python migration re-hashes because the column is
    now a BCrypt digest (AAP Section 0.7.2).
    """

    # Arrange -- capture the pre-existing hash.
    original_hash: str = sample_user.password
    assert original_hash.startswith(_BCRYPT_HASH_PREFIX)
    assert pwd_context.verify(_TEST_PLAINTEXT_PASSWORD, original_hash)

    mock_db_session.execute.return_value = _build_single_user_result(sample_user)

    # Act -- provide a new plaintext password.
    request: UserUpdateRequest = UserUpdateRequest(password=_TEST_NEW_PASSWORD)
    await user_service.update_user(user_id=_TEST_USER_ID, request=request)

    # Assert -- a fresh hash was stored.
    new_hash: str = sample_user.password
    assert new_hash != original_hash, (
        "Password hash did not change -- UserService may have skipped the rehash path for a distinct plaintext."
    )
    # Format checks.
    assert new_hash.startswith(_BCRYPT_HASH_PREFIX)
    assert len(new_hash) == _BCRYPT_HASH_LENGTH
    # New hash verifies against new plaintext.
    assert pwd_context.verify(_TEST_NEW_PASSWORD, new_hash)
    # New hash must NOT verify against the old plaintext.
    assert not pwd_context.verify(_TEST_PLAINTEXT_PASSWORD, new_hash)

    # Ensure flush was awaited.
    mock_db_session.flush.assert_awaited_once()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_user_not_found(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """Updating an absent user raises :class:`UserNotFoundError`.

    **Arrange** -- lookup returns ``None``.

    **Act + Assert** -- :meth:`UserService.update_user` raises
    :class:`UserNotFoundError` with the exact COBOL message
    :data:`MSG_USER_ID_NOT_FOUND` (byte-for-byte from
    ``COUSR02C`` L342: "User ID NOT found...").

    COBOL mapping
    -------------
    ``COUSR02C`` L335-348 (``READ-USER-SEC-FILE`` paragraph):

    .. code-block:: cobol

        WHEN DFHRESP(NOTFND)
            MOVE 'User ID NOT found...' TO WS-MESSAGE
            MOVE DFHRED TO ERRMSGC OF COUSR2AO
            PERFORM SEND-USRUPD-SCREEN
    """

    # Arrange: lookup returns None.
    mock_db_session.execute.return_value = _build_single_user_result(None)

    # Act + Assert
    request: UserUpdateRequest = UserUpdateRequest(first_name="Any")
    with pytest.raises(UserNotFoundError) as exc_info:
        await user_service.update_user(user_id="MISSING9", request=request)

    # Byte-for-byte COBOL message fidelity.
    assert exc_info.value.message == MSG_USER_ID_NOT_FOUND
    assert "NOT found" in MSG_USER_ID_NOT_FOUND  # confirm the message shape

    # No FLUSH occurred because we never got past the lookup.
    mock_db_session.flush.assert_not_awaited()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_user_type_validation(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """Service layer rejects invalid ``user_type`` on update.

    **CRITICAL**: mirror of
    :func:`test_create_user_invalid_type_rejected` for the UPDATE
    path. The ``user_type`` invariant is enforced at both the
    Pydantic schema and the service layer; this test bypasses the
    schema (via :meth:`model_construct`) to exercise the service-
    layer re-check.

    **Arrange** -- build a :class:`UserUpdateRequest` with
    ``user_type='X'`` via :meth:`model_construct`.

    **Act + Assert** -- :class:`UserValidationError` is raised
    **before** any database call (so no lookup, no flush).

    COBOL mapping
    -------------
    ``COCOM01Y.cpy`` only declares ``CDEMO-USRTYP-ADMIN`` (``'A'``)
    and ``CDEMO-USRTYP-USER`` (``'U'``). Any other byte would have
    failed the COBOL VALIDATE-USER-TYPE check.

    Also verifies that :data:`MSG_PLEASE_MODIFY_TO_UPDATE` preserves
    its exact COBOL wording (note the space before the ellipsis --
    "Please modify to update ...").
    """

    # Arrange: bypass Pydantic to send an invalid user_type.
    bad_request: UserUpdateRequest = UserUpdateRequest.model_construct(
        first_name=None,
        last_name=None,
        password=None,
        user_type="X",  # NOT in {'A', 'U'}
    )

    # Act + Assert
    with pytest.raises(UserValidationError) as exc_info:
        await user_service.update_user(user_id=_TEST_USER_ID, request=bad_request)

    # The error message references the invalid user_type.
    assert "user_type" in exc_info.value.message.lower()
    assert "'X'" in exc_info.value.message or '"X"' in exc_info.value.message

    # No DB interaction -- validation halts before SELECT.
    mock_db_session.execute.assert_not_awaited()
    mock_db_session.flush.assert_not_awaited()

    # Bonus: verify that the empty-patch guard message preserves its
    # COBOL-exact wording (space before ellipsis).
    assert "Please modify to update " in MSG_PLEASE_MODIFY_TO_UPDATE, (
        "MSG_PLEASE_MODIFY_TO_UPDATE must preserve the exact COBOL "
        "wording from COUSR02C L239 including the space before '...'."
    )


# ============================================================================
# Phase 6 -- User Delete Tests (Feature F-021, COUSR03C.cbl)
# ============================================================================
#
# The COBOL source is ``app/cbl/COUSR03C.cbl`` (transaction CU03, ~359
# lines). The ``DELETE-USER-SEC-FILE`` paragraph (L304-338)
# performs the DELETE after a preceding READ:
#
#     EXEC CICS READ FILE('USRSEC') INTO(SEC-USER-DATA)
#         LENGTH(LENGTH OF SEC-USER-DATA)
#         RIDFLD(SEC-USR-ID)
#         KEYLENGTH(LENGTH OF SEC-USR-ID)
#         RESP(WS-RESP-CD) END-EXEC.
#     ...
#     EXEC CICS DELETE FILE('USRSEC')
#         RIDFLD(SEC-USR-ID)
#         KEYLENGTH(LENGTH OF SEC-USR-ID)
#         RESP(WS-RESP-CD) END-EXEC.
#
# The Python translation replaces the READ + DELETE with a SELECT +
# ORM ``session.delete`` + FLUSH. Notably the COBOL DELETE flow
# preserved the same "Unable to Update User..." error wording as the
# UPDATE flow (a benign CICS code-sharing artifact); the Python
# migration preserves that quirk by using
# :data:`MSG_UNABLE_TO_UPDATE_USER` in the DELETE error path, per
# AAP Section 0.7.1.
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_delete_user_success(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Happy-path DELETE: the row is deleted and a summary is returned.

    **Arrange** -- lookup returns ``sample_user``
    (``user_id='TESTUSER'``, ``usr_type='U'``).

    **Act** -- invoke :meth:`UserService.delete_user('TESTUSER')`.

    **Assert** -- (a) the response is a :class:`UserDeleteResponse`,
    (b) its four attribute fields mirror the pre-delete user snapshot
    (critical: the service captures the user's field values
    **before** calling ``session.delete`` because the ORM row is
    invalidated after DELETE), (c) the success message equals
    :data:`MSG_USER_DELETED_TEMPLATE` templated with the user id,
    (d) :meth:`session.delete` was awaited exactly once with the
    user ORM row, (e) :meth:`session.flush` was awaited exactly once.

    COBOL mapping
    -------------
    ``COUSR03C`` L304-338 (``DELETE-USER-SEC-FILE`` paragraph):
    EXEC CICS DELETE with RIDFLD. The Python translation captures
    the user's fields before calling :meth:`AsyncSession.delete`
    because after the flush the ORM row's attribute access may
    trigger an ``ObjectDeletedError``. The return-value projection
    thus uses the pre-delete snapshot.
    """

    # Arrange: lookup returns a user.
    mock_db_session.execute.return_value = _build_single_user_result(sample_user)

    # Act
    response: UserDeleteResponse = await user_service.delete_user(_TEST_USER_ID)

    # Assert -- response shape with all four identification fields.
    assert isinstance(response, UserDeleteResponse)
    assert response.user_id == _TEST_USER_ID
    assert response.first_name == "Test"
    assert response.last_name == "User"
    assert response.user_type == "U"
    assert response.message == MSG_USER_DELETED_TEMPLATE.format(user_id=_TEST_USER_ID)

    # Assert -- session.delete was awaited with the user row.
    mock_db_session.delete.assert_awaited_once_with(sample_user)
    mock_db_session.flush.assert_awaited_once()

    # Assert -- exactly one SELECT (lookup); the delete statement is
    # issued via session.delete() not via execute().
    assert mock_db_session.execute.await_count == 1


@pytest.mark.unit
@pytest.mark.asyncio
async def test_delete_user_not_found(
    user_service: UserService,
    mock_db_session: AsyncMock,
) -> None:
    """Deleting an absent user raises :class:`UserNotFoundError`.

    **Arrange** -- lookup returns ``None``.

    **Act + Assert** -- :meth:`UserService.delete_user` raises
    :class:`UserNotFoundError` with the exact COBOL message
    :data:`MSG_USER_ID_NOT_FOUND`.

    Additionally verify:

    * :meth:`session.delete` was **not** awaited (no DELETE emitted).
    * :meth:`session.flush` was **not** awaited.

    COBOL mapping
    -------------
    ``COUSR03C`` L285-296 (``READ-USER-SEC-FILE`` paragraph):

    .. code-block:: cobol

        WHEN DFHRESP(NOTFND)
            MOVE 'User ID NOT found...' TO WS-MESSAGE
    """

    # Arrange: lookup returns None.
    mock_db_session.execute.return_value = _build_single_user_result(None)

    # Act + Assert
    with pytest.raises(UserNotFoundError) as exc_info:
        await user_service.delete_user("MISSING9")

    # Byte-for-byte message fidelity.
    assert exc_info.value.message == MSG_USER_ID_NOT_FOUND

    # No DELETE or FLUSH occurred.
    mock_db_session.delete.assert_not_awaited()
    mock_db_session.flush.assert_not_awaited()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_delete_user_no_password_in_response(
    user_service: UserService,
    mock_db_session: AsyncMock,
    sample_user: UserSecurity,
) -> None:
    """Delete response NEVER exposes the password (hash) field.

    **CRITICAL SECURITY TEST**: the deleted-user summary is
    display-only and must not leak credentials. The
    :class:`UserDeleteResponse` Pydantic schema has no ``password``
    field by design; this test enforces that invariant.

    **Arrange** -- lookup returns a user whose ``password`` column
    holds a BCrypt hash.

    **Act** -- delete the user.

    **Assert** -- (a) the response dump (via :meth:`model_dump`)
    does NOT contain a ``password`` key, (b) the response class does
    not declare a ``password`` field.

    COBOL mapping
    -------------
    The COBOL ``COUSR03.CPY`` symbolic map has no ``PASSWDI`` or
    ``PASSWDO`` field -- the delete screen only displayed
    ``USRIDINI``, ``FNAMEI``, ``LNAMEI``, ``USRTYPEI``. The Python
    response schema preserves exactly those four fields plus a
    ``message``.
    """

    # Paranoia check: fixture really has a BCrypt hash.
    assert sample_user.password.startswith(_BCRYPT_HASH_PREFIX)

    # Arrange
    mock_db_session.execute.return_value = _build_single_user_result(sample_user)

    # Act
    response: UserDeleteResponse = await user_service.delete_user(_TEST_USER_ID)

    # Assert -- no password in the serialised response.
    dumped: dict[str, Any] = response.model_dump()
    assert "password" not in dumped, f"UserDeleteResponse accidentally exposed password field: {dumped!r}"
    # Positive assertion: the expected four display fields ARE present.
    assert dumped["user_id"] == _TEST_USER_ID
    assert dumped["first_name"] == "Test"
    assert dumped["last_name"] == "User"
    assert dumped["user_type"] == "U"

    # Schema-level invariant: the class must not declare 'password'.
    assert "password" not in UserDeleteResponse.model_fields, (
        "UserDeleteResponse schema must not declare a 'password' field "
        "(AAP Section 0.7.2 Security Requirements; COUSR03.CPY has no "
        "PASSWDI/PASSWDO field)."
    )
