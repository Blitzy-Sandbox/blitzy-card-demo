
# ============================================================================
# CardDemo — Unit tests for user_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COUSR00C.cbl     — CICS User List program, transaction ``CU00``
#                                (~695 lines). STARTBR/READNEXT loop against
#                                the USRSEC VSAM KSDS populating up to 10
#                                repeating rows (USRID01O..USRID10O,
#                                FNAME01O..FNAME10O, LNAME01O..LNAME10O,
#                                UTYPE01O..UTYPE10O) in the COUSR00 BMS map.
#                                Gated by COADM01C admin check. Cloud-native
#                                equivalent: ``GET /users`` with optional
#                                ``user_id`` prefix filter + ``page`` /
#                                ``page_size`` query params. Feature F-018.
#   * app/cbl/COUSR01C.cbl     — CICS User Add program, transaction ``CU01``
#                                (~299 lines). EXEC CICS WRITE to USRSEC;
#                                DFHRESP(DUPKEY)/DFHRESP(DUPREC) -> COBOL
#                                error "User ID already exist..." (note
#                                "exist" NOT "exists" — preserved verbatim).
#                                Cloud-native equivalent: ``POST /users``.
#                                Feature F-019.
#   * app/cbl/COUSR02C.cbl     — CICS User Update program, transaction
#                                ``CU02`` (~414 lines). EXEC CICS READ
#                                UPDATE / REWRITE pair against USRSEC.
#                                All fields optional on update. All-None
#                                patch raises "Please modify to update ..."
#                                (SPACE before ellipsis — preserved
#                                verbatim). Cloud-native equivalent:
#                                ``PUT /users/{user_id}``. Feature F-020.
#   * app/cbl/COUSR03C.cbl     — CICS User Delete program, transaction
#                                ``CU03`` (~359 lines). EXEC CICS READ +
#                                DELETE against USRSEC; returns the pre-
#                                delete snapshot for confirmation display
#                                (no password field — matches COUSR03.CPY
#                                which omits PASSWDI/PASSWDO). Cloud-native
#                                equivalent: ``DELETE /users/{user_id}``.
#                                Feature F-021.
#   * app/cpy/CSUSR01Y.cpy     — SEC-USER-DATA, 80-byte record: SEC-USR-ID
#                                PIC X(08), SEC-USR-FNAME PIC X(20),
#                                SEC-USR-LNAME PIC X(20), SEC-USR-PWD
#                                PIC X(08), SEC-USR-TYPE PIC X(01).
#                                Password on the wire stays at 8 chars;
#                                persistence layer stores BCrypt hash.
#   * app/cpy/COCOM01Y.cpy     — CARDDEMO-COMMAREA (96 bytes). Provides
#                                CDEMO-USER-TYPE PIC X(01) with 88-level
#                                conditions CDEMO-USRTYP-ADMIN VALUE 'A'
#                                (admin) and CDEMO-USRTYP-USER VALUE 'U'
#                                (regular). The admin condition gates
#                                ALL four of COUSR00C-03C — non-admin
#                                callers are routed to the main menu
#                                rather than the admin menu. In the
#                                cloud-native target this becomes a
#                                uniform HTTP 403 on every /users/* path
#                                emitted by JWTAuthMiddleware when the
#                                JWT user_type != 'A'.
#   * app/cpy-bms/COUSR00.CPY  — User List BMS symbolic map. 10 repeated
#                                row groups (USRID01..10, FNAME01..10,
#                                LNAME01..10, UTYPE01..10) — the cloud-
#                                native equivalent is the JSON ``users``
#                                array returned by ``GET /users`` capped
#                                at ``page_size`` (default 10) items.
#   * app/cpy-bms/COUSR01.CPY  — User Add BMS symbolic map. Defines input
#                                fields USERIDI, FNAMEI, LNAMEI, PASSWDI,
#                                USRTYPEI — request body fields of
#                                :class:`UserCreateRequest`.
#   * app/cpy-bms/COUSR02.CPY  — User Update BMS symbolic map. Same five
#                                input fields as COUSR01.CPY; the
#                                cloud-native REST contract makes each
#                                body field Optional (PATCH semantics).
#   * app/cpy-bms/COUSR03.CPY  — User Delete BMS symbolic map. Lacks
#                                PASSWDI/PASSWDO entirely — the delete
#                                confirmation never displays or echoes
#                                passwords. The cloud-native
#                                :class:`UserDeleteResponse` schema
#                                preserves this by omitting the
#                                ``password`` field from the response.
# ----------------------------------------------------------------------------
# Features F-018 (List), F-019 (Add), F-020 (Update), F-021 (Delete).
# Target implementation under test:
# ``src/api/routers/user_router.py`` — FastAPI router providing
# ``GET /users``, ``POST /users``, ``PUT /users/{user_id}`` and
# ``DELETE /users/{user_id}`` endpoints. ALL four endpoints depend on
# :func:`src.api.dependencies.get_current_admin_user` so that non-admin
# users (JWT user_type='U') receive HTTP 403 Forbidden, byte-for-byte
# mirroring the COADM01C admin-menu gate.
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
"""Unit tests for :mod:`src.api.routers.user_router`.

Validates admin-only access enforcement and CRUD behavior of the
FastAPI user router converted from ``app/cbl/COUSR00C.cbl`` (list),
``app/cbl/COUSR01C.cbl`` (add), ``app/cbl/COUSR02C.cbl`` (update) and
``app/cbl/COUSR03C.cbl`` (delete) per AAP §0.5.1 (File-by-File
Transformation Plan).

COBOL -> Python Verification Surface
------------------------------------
=====================================================  ====================================================
COBOL paragraph / statement                            Python test (this module)
=====================================================  ====================================================
``STARTBR USRSEC`` + ``READNEXT * 10``                 ``TestUserList.test_list_users_success``
(COUSR00C L350-460 — main list iteration,              ``TestUserList.test_list_users_pagination``
populates 10 display rows)                             ``TestUserList.test_list_users_with_filter``
                                                       ``TestUserList.test_list_users_empty``
``DFHRESP(NOTFND)`` on STARTBR (empty file)            ``TestUserList.test_list_users_empty``
``IF CDEMO-USRTYP-ADMIN`` gate (COADM01C)              ``TestUserList.test_list_users_requires_admin``
                                                       ``TestUserAdd.test_create_user_requires_admin``
                                                       ``TestUserUpdate.test_update_user_requires_admin``
                                                       ``TestUserDelete.test_delete_user_requires_admin``
``RECEIVE MAP`` without Authorization header           ``TestUserList.test_list_users_requires_auth``
(EIBCALEN = 0 handling, implicit 401 today)
``WRITE USRSEC`` + DFHRESP(DUPKEY/DUPREC)              ``TestUserAdd.test_create_user_duplicate``
(COUSR01C L220-258 — duplicate write detection)        (MSG_USER_ID_ALREADY_EXISTS = "User ID already
                                                       exist..." — note "exist" not "exists")
``WRITE USRSEC`` success path (COUSR01C L241)          ``TestUserAdd.test_create_user_success``
Invalid USRTYPEI (not 'A'/'U') — COCOM01Y              ``TestUserAdd.test_create_user_invalid_type``
88-level CDEMO-USRTYP-ADMIN/USER                       ``TestUserUpdate.test_update_user_invalid_type``
PIC X(08) password truncation on input                 ``TestUserAdd.test_create_user_password_max_length``
``READ UPDATE USRSEC`` / ``REWRITE USRSEC``            ``TestUserUpdate.test_update_user_success``
(COUSR02C L260-330 — update main path)                 ``TestUserUpdate.test_update_user_change_password``
``DFHRESP(NOTFND)`` on READ                            ``TestUserUpdate.test_update_user_not_found``
(COUSR02C L265 / COUSR03C L205)                        ``TestUserDelete.test_delete_user_not_found``
All-None patch — MSG_PLEASE_MODIFY_TO_UPDATE           (implicit — UserValidationError path)
("Please modify to update ..." — SPACE before ...)
``READ USRSEC`` + ``DELETE USRSEC``                    ``TestUserDelete.test_delete_user_success``
(COUSR03C L205-285 — display + delete)                 (no password field in response — COUSR03.CPY)
=====================================================  ====================================================

JWT-Based Admin Gate (replacing COMMAREA CDEMO-USER-TYPE)
---------------------------------------------------------
The CICS COMMAREA (``CARDDEMO-COMMAREA`` from ``COCOM01Y.cpy``) that
flows between programs with every ``EXEC CICS RETURN`` is replaced by
a JWT bearer token. The ``user_type`` claim in the JWT maps 1:1 to
``CDEMO-USER-TYPE PIC X(01)`` with two valid values:

* ``'A'`` (``CDEMO-USRTYP-ADMIN``) — admin user, may reach
  COUSR00C/01C/02C/03C via the /users/* REST routes.
* ``'U'`` (``CDEMO-USRTYP-USER``) — regular user; any access to
  /users/* returns HTTP 403. This is the CICS COADM01C
  ``IF CDEMO-USRTYP-ADMIN`` check translated literally.

These tests exercise the three authentication / authorization outcomes
defined by the layered middleware + dependency stack in
``src/api/middleware/auth.py`` + ``src/api/dependencies.py``:

* **HTTP 200 OK / 201 Created** — admin JWT, admin dep override
                      (``admin_client`` fixture from conftest).
* **HTTP 403 Forbidden** — regular-user JWT; triggers either the
                      middleware-layer admin-path check
                      (``ADMIN_ONLY_PREFIXES = {"/admin", "/users"}``
                      in ``src/api/middleware/auth.py``) or the
                      router-layer :func:`get_current_admin_user` 403
                      — both outcomes are equivalent for the caller.
* **HTTP 401 Unauthorized** — no ``Authorization`` header; the
                      :class:`JWTAuthMiddleware` rejects the request
                      before any dependency runs; response includes
                      the ``WWW-Authenticate: Bearer`` challenge per
                      RFC 7235 §4.1.

Service Layer Mocking
---------------------
All tests mock :class:`src.api.services.user_service.UserService` at
the router-module import site via
``patch("src.api.routers.user_router.UserService")``. This isolates
each test from the SQLAlchemy session / Aurora PostgreSQL layer so
that router-level behavior (dependency resolution, request/response
serialization, HTTP status code translation of typed exceptions) is
verified independently of DB state.

The COBOL-exact error messages are preserved verbatim in the service
layer (``MSG_USER_ID_ALREADY_EXISTS = "User ID already exist..."``
with "exist" NOT "exists"; ``MSG_PLEASE_MODIFY_TO_UPDATE = "Please
modify to update ..."`` with a SPACE before the ellipsis). Tests that
assert on these messages use the imported constants from
:mod:`src.api.services.user_service` rather than hard-coded strings
so any future drift from the COBOL contract fails compilation.

Fixtures Used
-------------
From :mod:`tests.conftest`:
    * ``admin_client``      — AsyncClient with admin JWT and admin
                              CurrentUser dependency override.
                              user_id='ADMIN001', user_type='A'.
    * ``regular_client``    — AsyncClient with regular-user JWT and
                              regular CurrentUser dependency override.
                              user_id='TESTUSER', user_type='U'.
    * ``test_app``          — FastAPI app instance used to build
                              custom AsyncClients for the no-auth
                              (401) tests.

See Also
--------
* AAP §0.5.1  — File-by-File Transformation Plan (user_router row).
* AAP §0.7.1  — "Preserve all existing functionality exactly as-is".
* :mod:`src.api.routers.user_router` — the module under test.
* :mod:`src.api.services.user_service` — the mocked service layer.
* :mod:`src.api.dependencies` —
  :func:`get_current_admin_user` (403 gate) and
  :func:`get_current_user` (401 gate).
* :mod:`src.api.middleware.auth` —
  :class:`JWTAuthMiddleware`, ``ADMIN_ONLY_PREFIXES``.
* :mod:`src.shared.schemas.user_schema` — Pydantic request/response
  models validated at the endpoint boundary.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.api.services.user_service import (
    MSG_PLEASE_MODIFY_TO_UPDATE,
    MSG_UNABLE_TO_ADD_USER,
    MSG_UNABLE_TO_LOOKUP,
    MSG_UNABLE_TO_UPDATE_USER,
    MSG_USER_ID_ALREADY_EXISTS,
    MSG_USER_ID_NOT_FOUND,
    UserIdAlreadyExistsError,
    UserNotFoundError,
    UserServiceError,
    UserValidationError,
)
from src.shared.schemas.user_schema import (
    UserCreateResponse,
    UserDeleteResponse,
    UserListItem,
    UserListResponse,
    UserUpdateResponse,
)

# ============================================================================
# Test constants — tightly coupled to conftest.py fixture values
# ============================================================================
# The admin_client fixture in conftest.py overrides get_current_user to
# return ``CurrentUser(user_id="ADMIN001", user_type="A", is_admin=True)``.
# The regular_client fixture overrides get_current_user to return
# ``CurrentUser(user_id="TESTUSER", user_type="U", is_admin=False)``.
# We mirror those identities here so response-body assertions remain
# self-documenting (rather than importing conftest private constants).
# ============================================================================
_EXPECTED_ADMIN_USER_ID: str = "ADMIN001"
_EXPECTED_REGULAR_USER_ID: str = "TESTUSER"

# ============================================================================
# COBOL domain constants — PIC field sizes from CSUSR01Y.cpy
# ============================================================================
# These are the byte lengths baked into the SEC-USER-DATA record layout
# of ``app/cpy/CSUSR01Y.cpy``. They are duplicated here (rather than
# imported from user_schema.py's private constants) because the tests
# are meant to fail if someone silently relaxes a length constraint
# that no longer matches the COBOL PIC X(n) source of truth.
# ============================================================================
_USER_ID_MAX_LEN: int = 8       # SEC-USR-ID     PIC X(08)
_FIRST_NAME_MAX_LEN: int = 20   # SEC-USR-FNAME  PIC X(20)
_LAST_NAME_MAX_LEN: int = 20    # SEC-USR-LNAME  PIC X(20)
_PASSWORD_MAX_LEN: int = 8      # SEC-USR-PWD    PIC X(08)
_USER_TYPE_ADMIN: str = "A"     # CDEMO-USRTYP-ADMIN VALUE 'A'
_USER_TYPE_REGULAR: str = "U"   # CDEMO-USRTYP-USER  VALUE 'U'

# ============================================================================
# BMS layout constants — default page size from COUSR00.CPY screen layout
# ============================================================================
# COUSR00.CPY defines exactly 10 repeating row groups (USRID01..10,
# FNAME01..10, LNAME01..10, UTYPE01..10) matching the BMS 80x24 screen
# geometry. The cloud-native REST contract preserves this as the
# ``page_size`` default in :class:`UserListRequest`.
# ============================================================================
_DEFAULT_PAGE_SIZE: int = 10


# ============================================================================
# Module-level pytest marker.
# ----------------------------------------------------------------------------
# Every test in this module is a unit test — fast, isolated (service
# layer mocked), and free of external dependencies (no real DB, no
# network). Applying the ``unit`` marker at the module level saves us
# from decorating each of the 22 test methods individually. The
# marker is declared in ``pyproject.toml`` under
# ``[tool.pytest.ini_options].markers`` alongside ``integration``,
# ``e2e``, and ``slow`` so pytest's ``--strict-markers`` does not
# reject it. Run the module in isolation with
# ``pytest -m unit tests/unit/test_routers/test_user_router.py``.
# ============================================================================
pytestmark = pytest.mark.unit


# ============================================================================
# SECTION 1 — Tests for GET /users (User List, F-018, COUSR00C.cbl)
# ----------------------------------------------------------------------------
# Covers the COUSR00C.cbl STARTBR/READNEXT list-iteration flow:
#   1. Admin user lands on GET /users -> service.list_users populates up
#      to 10 rows (mirroring OCCURS 10 in COUSR00.CPY). Optional filter
#      ``user_id`` prefix mirrors the USRIDIN START AT behavior.
#   2. Regular user reaches the same URL -> admin-gate (88-level
#      CDEMO-USRTYP-ADMIN) rejects the request; in the cloud-native
#      architecture this becomes HTTP 403 Forbidden from the
#      JWTAuthMiddleware ADMIN_ONLY_PREFIXES check.
#   3. Unauthenticated client -> JWTAuthMiddleware returns HTTP 401 with
#      WWW-Authenticate: Bearer header.
#   4. Service-layer DB failure (MSG_UNABLE_TO_LOOKUP) -> HTTP 500.
# ============================================================================
class TestUserList:
    """Tests for the ``GET /users`` endpoint (Feature F-018)."""

    async def test_list_users_success(self, admin_client: AsyncClient) -> None:
        """Admin user successfully retrieves a paginated user list.

        Mirrors the happy-path scenario in COUSR00C.cbl where STARTBR
        USRSEC followed by up to 10 READNEXT calls populates the
        USRID01O..USRID10O display row group. In the cloud-native
        target, the service layer returns a :class:`UserListResponse`
        with ``users`` list capped at ``page_size`` (default 10) and
        the router serializes it to JSON.

        Mocking: patches
        :class:`src.api.services.user_service.UserService` at the
        router import site; returns a canned 2-item response so the
        test remains isolated from the SQLAlchemy/Aurora layer.

        Assertions:
            * HTTP 200 OK.
            * Response body is JSON with ``users``, ``page``,
              ``total_count`` keys (per :class:`UserListResponse`).
            * ``users`` is a list (the 10-row BMS repeater).
            * Each row has ``user_id``, ``first_name``, ``last_name``,
              ``user_type`` — mirroring the four BMS row groups.
            * No ``password`` field is exposed anywhere in the list
              response (COUSR00.CPY has no PWD column — verified
              against the BMS source).
        """
        expected_response = UserListResponse(
            users=[
                UserListItem(
                    user_id="USER0001",
                    first_name="Alice",
                    last_name="Smith",
                    user_type=_USER_TYPE_REGULAR,
                ),
                UserListItem(
                    user_id="ADMIN001",
                    first_name="Sys",
                    last_name="Admin",
                    user_type=_USER_TYPE_ADMIN,
                ),
            ],
            page=1,
            total_count=2,
            message=None,
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_users = AsyncMock(return_value=expected_response)

            response = await admin_client.get("/users")

        assert response.status_code == status.HTTP_200_OK, (
            f"Admin GET /users MUST return HTTP 200; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # UserListResponse has the three top-level keys from the schema.
        assert "users" in body, f"Response missing ``users`` key: {body}"
        assert "page" in body, f"Response missing ``page`` key: {body}"
        assert "total_count" in body, f"Response missing ``total_count`` key: {body}"
        assert isinstance(body["users"], list), (
            f"``users`` must be a list (BMS OCCURS 10 equivalent); got {type(body['users']).__name__}"
        )
        assert len(body["users"]) == 2, (
            f"Expected 2 users (mock setup); got {len(body['users'])}"
        )
        # Page / total_count echoes
        assert body["page"] == 1
        assert body["total_count"] == 2
        # Validate each row shape matches COUSR00.CPY BMS row groups.
        for row in body["users"]:
            assert "user_id" in row, f"Row missing ``user_id``: {row}"
            assert "first_name" in row, f"Row missing ``first_name``: {row}"
            assert "last_name" in row, f"Row missing ``last_name``: {row}"
            assert "user_type" in row, f"Row missing ``user_type``: {row}"
            # CRITICAL: passwords MUST NEVER appear in list responses.
            # COUSR00.CPY BMS map has no PASSWD column — preserved.
            assert "password" not in row, (
                f"Password MUST NEVER be exposed in list responses "
                f"(COUSR00.CPY has no PWD column); got row={row}"
            )
        # Verify the service layer was invoked once (isolation check).
        mock_instance.list_users.assert_called_once()

    async def test_list_users_with_filter(self, admin_client: AsyncClient) -> None:
        """GET /users?user_id=TEST filters the list by user_id prefix.

        Mirrors the COUSR00C USRIDIN input field used to position the
        STARTBR USRSEC cursor at a specific starting key. The
        cloud-native contract accepts ``user_id`` as an optional
        query parameter forwarded into :class:`UserListRequest`
        (validated via Pydantic).

        Assertions:
            * HTTP 200 OK.
            * The service layer receives a
              :class:`UserListRequest` with ``user_id="TEST"`` (case
              preserved — COBOL PIC X(08) is case-sensitive).
        """
        expected_response = UserListResponse(
            users=[
                UserListItem(
                    user_id="TESTUSER",
                    first_name="Test",
                    last_name="User",
                    user_type=_USER_TYPE_REGULAR,
                ),
            ],
            page=1,
            total_count=1,
            message=None,
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_users = AsyncMock(return_value=expected_response)

            response = await admin_client.get("/users", params={"user_id": "TEST"})

        assert response.status_code == status.HTTP_200_OK, (
            f"Filtered GET /users MUST return HTTP 200; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        assert len(body["users"]) == 1
        assert body["users"][0]["user_id"] == "TESTUSER"
        # The filter must be propagated to the service. The service
        # receives a UserListRequest with the filter populated.
        mock_instance.list_users.assert_called_once()
        call_request = mock_instance.list_users.call_args.args[0]
        assert getattr(call_request, "user_id", None) == "TEST", (
            f"Service must receive user_id='TEST' filter; got {call_request}"
        )

    async def test_list_users_pagination(self, admin_client: AsyncClient) -> None:
        """GET /users?page=2&page_size=5 passes pagination to the service.

        Mirrors the COUSR00C PF7 (PREV) / PF8 (NEXT) paging logic that
        repositioned the STARTBR cursor by 10 records. In the
        cloud-native target, pagination is parameter-driven via
        ``page`` (ge=1) and ``page_size`` (ge=1, le=100, default=10).

        Assertions:
            * HTTP 200 OK.
            * ``page`` echoed in response.
            * Service receives UserListRequest with page=2, page_size=5.
        """
        expected_response = UserListResponse(
            users=[
                UserListItem(
                    user_id=f"USER000{i}",
                    first_name="First",
                    last_name="Last",
                    user_type=_USER_TYPE_REGULAR,
                )
                for i in range(1, 6)
            ],
            page=2,
            total_count=42,
            message=None,
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_users = AsyncMock(return_value=expected_response)

            response = await admin_client.get(
                "/users",
                params={"page": 2, "page_size": 5},
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Paginated GET /users MUST return HTTP 200; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        assert body["page"] == 2
        assert body["total_count"] == 42
        assert len(body["users"]) == 5
        # Verify pagination parameters reach the service layer.
        call_request = mock_instance.list_users.call_args.args[0]
        assert getattr(call_request, "page", None) == 2
        assert getattr(call_request, "page_size", None) == 5

    async def test_list_users_empty(self, admin_client: AsyncClient) -> None:
        """GET /users returns an empty list when USRSEC has no matches.

        Mirrors the COUSR00C DFHRESP(NOTFND) on STARTBR path — in the
        COBOL program this set a "no records found" message and
        displayed an empty screen. In the cloud-native target, the
        service returns a :class:`UserListResponse` with ``users=[]``
        and the router returns HTTP 200 (NOT 404 — an empty list is a
        valid successful response per REST conventions).

        Assertions:
            * HTTP 200 OK (not 404).
            * ``users`` is an empty list.
            * ``total_count`` is 0.
        """
        expected_response = UserListResponse(
            users=[],
            page=1,
            total_count=0,
            message=None,
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.list_users = AsyncMock(return_value=expected_response)

            response = await admin_client.get("/users")

        assert response.status_code == status.HTTP_200_OK, (
            f"Empty USRSEC result MUST return HTTP 200 (not 404); "
            f"got {response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        assert body["users"] == []
        assert body["total_count"] == 0

    async def test_list_users_requires_admin(
        self,
        regular_client: AsyncClient,
    ) -> None:
        """Regular user (user_type='U') receives HTTP 403 on GET /users.

        Mirrors the COADM01C admin-menu gate (``IF CDEMO-USRTYP-ADMIN``,
        88-level condition on CDEMO-USER-TYPE) that prevented non-admin
        users from XCTL-ing to COUSR00C. In the cloud-native target,
        this is enforced TWICE — once by
        :class:`src.api.middleware.auth.JWTAuthMiddleware`
        (``ADMIN_ONLY_PREFIXES = {"/admin", "/users"}``) and once by
        :func:`src.api.dependencies.get_current_admin_user`. For a
        non-admin JWT, the middleware short-circuits with 403 FRBD
        before the dependency layer runs — either outcome is
        equivalent for the caller.

        Assertions:
            * HTTP 403 Forbidden.
            * The UserService mock was NEVER instantiated (the admin
              gate fires before the route body runs).
        """
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await regular_client.get("/users")

        assert response.status_code == status.HTTP_403_FORBIDDEN, (
            f"Regular user GET /users MUST return HTTP 403 "
            f"(COADM01C CDEMO-USRTYP-ADMIN gate); got "
            f"{response.status_code}: {response.text}"
        )
        # Because the admin gate fires before the route body,
        # UserService MUST NOT be instantiated.
        mock_service_class.assert_not_called()

    async def test_list_users_requires_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated request to GET /users returns HTTP 401.

        The :class:`JWTAuthMiddleware` rejects the request BEFORE the
        router dependency stack runs because no ``Authorization``
        header is present. The response carries a
        ``WWW-Authenticate: Bearer`` challenge per RFC 7235 §4.1.

        Builds a custom :class:`AsyncClient` (rather than reusing the
        ``client`` / ``admin_client`` / ``regular_client`` fixtures —
        all of which pre-set an ``Authorization`` header) so the
        middleware observes a genuinely missing header — matching the
        real-world attack pattern of an anonymous caller probing an
        admin endpoint.
        """
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.get("/users")

        assert response.status_code == status.HTTP_401_UNAUTHORIZED, (
            f"Unauthenticated GET /users MUST return HTTP 401; got "
            f"{response.status_code}: {response.text}"
        )
        # RFC 7235 §4.1 — challenge MUST include WWW-Authenticate
        # header identifying the scheme. Middleware emits "Bearer".
        assert "www-authenticate" in {key.lower() for key in response.headers}, (
            f"401 response MUST include WWW-Authenticate header per "
            f"RFC 7235 §4.1; headers={dict(response.headers)}"
        )


# ============================================================================
# SECTION 2 — Tests for POST /users (User Add, F-019, COUSR01C.cbl)
# ----------------------------------------------------------------------------
# Covers the COUSR01C.cbl user-add flow:
#   1. Admin builds a :class:`UserCreateRequest` (5 required fields
#      matching CSUSR01Y.cpy record layout) and POSTs it to /users.
#   2. Service calls EXEC CICS WRITE USRSEC equivalent
#      (SQLAlchemy INSERT into user_security table).
#   3. On DFHRESP(DUPKEY)/DUPREC -> UserIdAlreadyExistsError -> HTTP 409.
#   4. On invalid user_type (not 'A'/'U') -> Pydantic validator -> 422.
#   5. On password > 8 chars -> Pydantic validator -> 422.
#   6. Non-admin caller -> HTTP 403 (admin gate).
# ============================================================================
class TestUserAdd:
    """Tests for the ``POST /users`` endpoint (Feature F-019)."""

    async def test_create_user_success(self, admin_client: AsyncClient) -> None:
        """Admin successfully creates a new user via POST /users.

        Mirrors the COUSR01C WRITE USRSEC happy path: all 5 required
        fields (USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI) are
        populated and the service persists a new user_security row
        with a BCrypt-hashed password. The cloud-native response is
        :class:`UserCreateResponse` (omits the password — a password
        is never echoed back in the response).

        Assertions:
            * HTTP 201 Created (not 200 — POST creates a resource).
            * Response contains user_id, first_name, last_name,
              user_type.
            * Response does NOT contain a password field.
            * Service.create_user was called once.
        """
        request_body = {
            "user_id": "NEWUSER1",
            "first_name": "New",
            "last_name": "User",
            "password": "NEWPASS1",
            "user_type": _USER_TYPE_REGULAR,
        }
        expected_response = UserCreateResponse(
            user_id="NEWUSER1",
            first_name="New",
            last_name="User",
            user_type=_USER_TYPE_REGULAR,
            message="User NEWUSER1 has been added ...",
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.create_user = AsyncMock(return_value=expected_response)

            response = await admin_client.post("/users", json=request_body)

        assert response.status_code == status.HTTP_201_CREATED, (
            f"POST /users MUST return HTTP 201 Created; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # Response-shape assertions mirroring UserCreateResponse.
        assert body["user_id"] == "NEWUSER1"
        assert body["first_name"] == "New"
        assert body["last_name"] == "User"
        assert body["user_type"] == _USER_TYPE_REGULAR
        # CRITICAL: passwords MUST NEVER appear in create responses.
        assert "password" not in body, (
            f"Password MUST NEVER be echoed in create response; got body={body}"
        )
        # Verify the service received the request exactly once.
        mock_instance.create_user.assert_called_once()

    async def test_create_user_duplicate(self, admin_client: AsyncClient) -> None:
        """Creating a user with an existing ID returns HTTP 409.

        Mirrors COUSR01C DFHRESP(DUPKEY) / DFHRESP(DUPREC) path: when
        the WRITE USRSEC fails because the SEC-USR-ID already exists,
        the COBOL program displays the error message
        ``"User ID already exist..."`` (note: "exist" NOT "exists" —
        this is preserved verbatim from the COBOL source).

        In the cloud-native target, the service layer raises
        :class:`UserIdAlreadyExistsError` (default message =
        :data:`MSG_USER_ID_ALREADY_EXISTS`) and the router translates
        it to HTTP 409 Conflict with the error message in
        ``detail``.

        Assertions:
            * HTTP 409 Conflict.
            * Response ``detail`` contains the COBOL-exact error text.
        """
        request_body = {
            "user_id": "EXISTING",
            "first_name": "Dup",
            "last_name": "Licate",
            "password": "PWDABC12",
            "user_type": _USER_TYPE_REGULAR,
        }
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            # Raise with the exact COBOL-derived message.
            mock_instance.create_user = AsyncMock(
                side_effect=UserIdAlreadyExistsError(MSG_USER_ID_ALREADY_EXISTS),
            )

            response = await admin_client.post("/users", json=request_body)

        assert response.status_code == status.HTTP_409_CONFLICT, (
            f"Duplicate user POST MUST return HTTP 409 "
            f"(COUSR01C DFHRESP(DUPKEY/DUPREC)); got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # The global exception handler in
        # ``src/api/middleware/error_handler.py`` wraps HTTPExceptions
        # in an ABEND-DATA envelope (mirroring COBOL's
        # ``ABEND-CODE / ABEND-CULPRIT / ABEND-REASON / ABEND-MSG``
        # 4-tuple from ``CSMSG01Y.cpy``). The HTTPException ``detail``
        # string is surfaced as ``error.reason`` (and, when no
        # status-default exists, also ``error.message``).
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        assert "reason" in error_envelope, (
            f"ABEND envelope must carry ``reason`` (ABEND-REASON PIC X(50)); "
            f"got {error_envelope}"
        )
        # CRITICAL: "exist" NOT "exists" — COBOL source verbatim.
        assert MSG_USER_ID_ALREADY_EXISTS in error_envelope["reason"], (
            f"409 reason must contain the COBOL-exact message "
            f"``{MSG_USER_ID_ALREADY_EXISTS}``; got {error_envelope['reason']!r}"
        )
        # Sanity: 409 Conflict routes through error_code='DUPR' for
        # consistency with ``_HTTP_STATUS_TO_ERROR_CODE`` mapping.
        assert error_envelope.get("error_code") == "DUPR", (
            f"409 Conflict must carry error_code='DUPR' "
            f"(duplicate-record ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )

    async def test_create_user_invalid_type(self, admin_client: AsyncClient) -> None:
        """POST /users with user_type='X' returns HTTP 422.

        COCOM01Y.cpy restricts CDEMO-USER-TYPE to two 88-level values:
        ``CDEMO-USRTYP-ADMIN VALUE 'A'`` and
        ``CDEMO-USRTYP-USER VALUE 'U'``. The cloud-native
        :class:`UserCreateRequest` encodes this rule via a Pydantic
        field_validator that rejects any value outside the frozenset
        ``{'A', 'U'}``. FastAPI returns HTTP 422 Unprocessable Entity
        (Pydantic validation error) BEFORE the endpoint runs.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * UserService was NEVER called (schema rejected first).
        """
        request_body = {
            "user_id": "NEWUSER1",
            "first_name": "New",
            "last_name": "User",
            "password": "NEWPASS1",
            "user_type": "X",  # invalid — not in {'A', 'U'}
        }
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await admin_client.post("/users", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Invalid user_type MUST return HTTP 422 (Pydantic "
            f"rejects before service call); got "
            f"{response.status_code}: {response.text}"
        )
        # Service must NOT be instantiated — Pydantic validated first.
        mock_service_class.assert_not_called()

    async def test_create_user_password_max_length(
        self,
        admin_client: AsyncClient,
    ) -> None:
        """POST /users with password > 8 chars returns HTTP 422.

        CSUSR01Y.cpy defines ``SEC-USR-PWD PIC X(08)`` — 8 bytes
        fixed. On the legacy mainframe, a longer password would
        simply be truncated by the fixed-record VSAM write. The
        cloud-native :class:`UserCreateRequest` preserves this
        8-character constraint as a Pydantic field_validator
        rejecting any password that exceeds
        :data:`_PASSWORD_MAX_LEN` (8 chars) with HTTP 422.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * UserService was NEVER called.
        """
        # Exactly one character over the COBOL PIC X(08) limit.
        too_long_password = "A" * (_PASSWORD_MAX_LEN + 1)
        assert len(too_long_password) == 9, (
            "Test precondition: password must exceed COBOL PIC X(08) limit"
        )
        request_body = {
            "user_id": "NEWUSER1",
            "first_name": "New",
            "last_name": "User",
            "password": too_long_password,
            "user_type": _USER_TYPE_REGULAR,
        }
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await admin_client.post("/users", json=request_body)

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Password > 8 chars MUST return HTTP 422 "
            f"(COBOL PIC X(08) constraint); got "
            f"{response.status_code}: {response.text}"
        )
        # Pydantic rejects before service call.
        mock_service_class.assert_not_called()

    async def test_create_user_requires_admin(
        self,
        regular_client: AsyncClient,
    ) -> None:
        """Regular user (user_type='U') receives HTTP 403 on POST /users.

        Same admin-gate logic as
        :meth:`TestUserList.test_list_users_requires_admin`: the
        JWTAuthMiddleware ADMIN_ONLY_PREFIXES check (``{"/admin",
        "/users"}``) rejects the request before the route body runs.

        Assertions:
            * HTTP 403 Forbidden.
            * UserService was NEVER instantiated.
        """
        request_body = {
            "user_id": "NEWUSER1",
            "first_name": "New",
            "last_name": "User",
            "password": "NEWPASS1",
            "user_type": _USER_TYPE_REGULAR,
        }
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await regular_client.post("/users", json=request_body)

        assert response.status_code == status.HTTP_403_FORBIDDEN, (
            f"Regular user POST /users MUST return HTTP 403 "
            f"(COADM01C CDEMO-USRTYP-ADMIN gate); got "
            f"{response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

    async def test_create_user_service_error(self, admin_client: AsyncClient) -> None:
        """Service-layer DB failure on POST /users returns HTTP 500.

        Mirrors the COUSR01C generic error path where the WRITE
        USRSEC fails for a non-duplicate reason (e.g., disk
        unavailable, catalog error). The service layer raises a bare
        :class:`UserServiceError` with
        :data:`MSG_UNABLE_TO_ADD_USER` (``"Unable to Add User..."``)
        and the router translates it to HTTP 500.

        Assertions:
            * HTTP 500 Internal Server Error.
            * Response ``detail`` contains the COBOL-exact message.
        """
        request_body = {
            "user_id": "NEWUSER1",
            "first_name": "New",
            "last_name": "User",
            "password": "NEWPASS1",
            "user_type": _USER_TYPE_REGULAR,
        }
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.create_user = AsyncMock(
                side_effect=UserServiceError(MSG_UNABLE_TO_ADD_USER),
            )

            response = await admin_client.post("/users", json=request_body)

        assert response.status_code == status.HTTP_500_INTERNAL_SERVER_ERROR, (
            f"Bare UserServiceError MUST translate to HTTP 500; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # ABEND envelope (see docstring on test_create_user_duplicate).
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        assert MSG_UNABLE_TO_ADD_USER in error_envelope["reason"], (
            f"500 reason must contain ``{MSG_UNABLE_TO_ADD_USER}``; "
            f"got {error_envelope['reason']!r}"
        )
        # Sanity: 500 carries the IOER (I/O error) ABEND-CODE — the
        # analogue of COBOL WS-ERR-FLG = 'Y' + DFHRESP paths that
        # fall through to the generic ``WS-MESSAGE`` handler in
        # COUSR01C.
        assert error_envelope.get("error_code") == "IOER", (
            f"500 Internal Server Error must carry error_code='IOER' "
            f"(I/O-error ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )


# ============================================================================
# SECTION 3 — Tests for PUT /users/{user_id} (User Update, F-020)
# ----------------------------------------------------------------------------
# Covers the COUSR02C.cbl user-update flow:
#   1. Admin sends a PATCH-style body — all fields Optional — to
#      PUT /users/{user_id}.
#   2. Service performs READ UPDATE + REWRITE USRSEC (SQLAlchemy
#      SELECT ... FOR UPDATE + UPDATE in a single transaction).
#   3. On DFHRESP(NOTFND) -> UserNotFoundError -> HTTP 404.
#   4. On all-None patch -> UserValidationError (MSG_PLEASE_MODIFY_
#      TO_UPDATE "Please modify to update ..." — note SPACE before
#      ellipsis) -> HTTP 400.
#   5. On invalid user_type ('X') -> Pydantic validator -> HTTP 422.
#   6. Non-admin caller -> HTTP 403.
#   7. Service DB failure (MSG_UNABLE_TO_UPDATE_USER) -> HTTP 500.
# ============================================================================
class TestUserUpdate:
    """Tests for the ``PUT /users/{user_id}`` endpoint (Feature F-020)."""

    async def test_update_user_success(self, admin_client: AsyncClient) -> None:
        """Admin successfully patches an existing user via PUT.

        Mirrors COUSR02C READ UPDATE + REWRITE USRSEC: only non-null
        fields overwrite their counterparts on the existing row
        (mirroring the COBOL conditional-MOVE pattern). The response
        is :class:`UserUpdateResponse` (no password echoed).

        Here we patch only the first_name — the other fields on the
        row are preserved by the service.

        Assertions:
            * HTTP 200 OK.
            * Response contains the updated identity fields.
            * Service.update_user called once with the path user_id
              and the UserUpdateRequest body.
        """
        patch_body = {"first_name": "UpdatedName"}
        expected_response = UserUpdateResponse(
            user_id="TESTUSER",
            first_name="UpdatedName",
            last_name="LastName",
            user_type=_USER_TYPE_REGULAR,
            message="User TESTUSER has been updated ...",
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_user = AsyncMock(return_value=expected_response)

            response = await admin_client.put(
                "/users/TESTUSER",
                json=patch_body,
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"PUT /users/{{user_id}} MUST return HTTP 200; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        assert body["user_id"] == "TESTUSER"
        assert body["first_name"] == "UpdatedName"
        # Password MUST NEVER appear in update responses.
        assert "password" not in body, (
            f"Password MUST NEVER be echoed in update response; got body={body}"
        )
        mock_instance.update_user.assert_called_once()
        # The path user_id is the first positional arg.
        call_args = mock_instance.update_user.call_args.args
        assert call_args[0] == "TESTUSER", (
            f"Service must receive path user_id as first arg; got {call_args}"
        )

    async def test_update_user_not_found(self, admin_client: AsyncClient) -> None:
        """PUT /users/{user_id} for non-existent user returns HTTP 404.

        Mirrors COUSR02C DFHRESP(NOTFND) on READ UPDATE USRSEC: the
        COBOL program would display ``"User ID NOT found..."`` in
        the error area. In the cloud-native target, the service
        raises :class:`UserNotFoundError` (default message =
        :data:`MSG_USER_ID_NOT_FOUND`) and the router translates it
        to HTTP 404 Not Found.

        Assertions:
            * HTTP 404 Not Found.
            * Response ``detail`` contains the COBOL-exact message.
        """
        patch_body = {"first_name": "Irrelevant"}
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_user = AsyncMock(
                side_effect=UserNotFoundError(MSG_USER_ID_NOT_FOUND),
            )

            response = await admin_client.put("/users/NOSUCH00", json=patch_body)

        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"PUT for non-existent user MUST return HTTP 404 "
            f"(COUSR02C DFHRESP(NOTFND)); got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # ABEND envelope (see docstring on test_create_user_duplicate).
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        assert MSG_USER_ID_NOT_FOUND in error_envelope["reason"], (
            f"404 reason must contain ``{MSG_USER_ID_NOT_FOUND}``; "
            f"got {error_envelope['reason']!r}"
        )
        # Sanity: 404 Not Found carries error_code='NFND' (not-found
        # ABEND-CODE), matching COBOL DFHRESP(NOTFND).
        assert error_envelope.get("error_code") == "NFND", (
            f"404 Not Found must carry error_code='NFND' "
            f"(not-found ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )

    async def test_update_user_change_password(
        self,
        admin_client: AsyncClient,
    ) -> None:
        """PUT /users/{user_id} with new password succeeds with HTTP 200.

        Mirrors the COUSR02C conditional overwrite of SEC-USR-PWD:
        when the caller supplies a new PASSWDI value, the service
        layer hashes it via BCrypt (passlib) before REWRITE. The
        router must not expose the hashed or cleartext password in
        the response (UserUpdateResponse has no ``password`` field).

        Assertions:
            * HTTP 200 OK.
            * Response does NOT echo the password.
            * Service received the new password in the body.
        """
        new_password = "NEWPWD12"
        assert len(new_password) <= _PASSWORD_MAX_LEN
        patch_body = {"password": new_password}
        expected_response = UserUpdateResponse(
            user_id="TESTUSER",
            first_name="Test",
            last_name="User",
            user_type=_USER_TYPE_REGULAR,
            message="User TESTUSER has been updated ...",
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_user = AsyncMock(return_value=expected_response)

            response = await admin_client.put(
                "/users/TESTUSER",
                json=patch_body,
            )

        assert response.status_code == status.HTTP_200_OK, (
            f"Password-change PUT MUST return HTTP 200; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        assert "password" not in body, (
            f"Password MUST NEVER be echoed in update response "
            f"(even after a password change); got body={body}"
        )
        # Service receives the new cleartext password (hashing happens
        # inside the service layer — not verified here, only at
        # service-level unit tests).
        call_args = mock_instance.update_user.call_args.args
        request_model = call_args[1]
        assert getattr(request_model, "password", None) == new_password, (
            f"Service must receive the new password in the request "
            f"body; got {request_model}"
        )

    async def test_update_user_invalid_type(
        self,
        admin_client: AsyncClient,
    ) -> None:
        """PUT /users/{user_id} with user_type='X' returns HTTP 422.

        Same Pydantic validator that guards
        :class:`UserCreateRequest` also guards
        :class:`UserUpdateRequest`: ``user_type`` (if provided) must
        be in the frozenset ``{'A', 'U'}`` (COCOM01Y.cpy 88-level
        conditions). Invalid value triggers a Pydantic validation
        error returning HTTP 422 BEFORE the endpoint runs.

        Assertions:
            * HTTP 422 Unprocessable Entity.
            * UserService was NEVER called.
        """
        patch_body = {"user_type": "X"}
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await admin_client.put(
                "/users/TESTUSER",
                json=patch_body,
            )

        assert response.status_code == status.HTTP_422_UNPROCESSABLE_ENTITY, (
            f"Invalid user_type on PUT MUST return HTTP 422; got "
            f"{response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

    async def test_update_user_empty_patch(
        self,
        admin_client: AsyncClient,
    ) -> None:
        """All-None patch returns HTTP 400 with the COBOL error message.

        Mirrors the COUSR02C "no changes provided" path: if the user
        presses Enter on the update screen without modifying any
        field, the program displays ``"Please modify to update ..."``
        (note the SPACE before the ellipsis — preserved verbatim).

        In the cloud-native target, the empty body ``{}`` passes
        Pydantic (all fields Optional) but the service layer detects
        the all-None state and raises :class:`UserValidationError`
        with :data:`MSG_PLEASE_MODIFY_TO_UPDATE`. The router
        translates to HTTP 400 Bad Request.

        Assertions:
            * HTTP 400 Bad Request.
            * Response ``detail`` contains the COBOL-exact message.
        """
        patch_body: dict[str, Any] = {}
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_user = AsyncMock(
                side_effect=UserValidationError(MSG_PLEASE_MODIFY_TO_UPDATE),
            )

            response = await admin_client.put(
                "/users/TESTUSER",
                json=patch_body,
            )

        assert response.status_code == status.HTTP_400_BAD_REQUEST, (
            f"All-None patch MUST return HTTP 400 "
            f"(MSG_PLEASE_MODIFY_TO_UPDATE); got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # ABEND envelope (see docstring on test_create_user_duplicate).
        # CRITICAL: For HTTP 400, the ``message`` field in the
        # envelope is populated with the status-default
        # ``CCDA_MSG_INVALID_KEY`` ("Invalid key pressed. Please see
        # below...") regardless of ``exc.detail``. The original
        # HTTPException ``detail`` flows through to ``reason`` only —
        # so we MUST assert against ``error.reason`` (not
        # ``error.message``) to verify the COBOL-exact
        # MSG_PLEASE_MODIFY_TO_UPDATE value.
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        # CRITICAL: SPACE before ellipsis — preserved verbatim.
        assert MSG_PLEASE_MODIFY_TO_UPDATE in error_envelope["reason"], (
            f"400 reason must contain ``{MSG_PLEASE_MODIFY_TO_UPDATE}`` "
            f"(note SPACE before ellipsis); got {error_envelope['reason']!r}"
        )
        # Sanity: 400 Bad Request carries error_code='INVR' (invalid
        # request/record ABEND-CODE), matching COBOL INVREQ.
        assert error_envelope.get("error_code") == "INVR", (
            f"400 Bad Request must carry error_code='INVR' "
            f"(invalid-request ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )

    async def test_update_user_requires_admin(
        self,
        regular_client: AsyncClient,
    ) -> None:
        """Regular user receives HTTP 403 on PUT /users/{user_id}.

        Same admin-gate logic as
        :meth:`TestUserList.test_list_users_requires_admin`.

        Assertions:
            * HTTP 403 Forbidden.
            * UserService was NEVER instantiated.
        """
        patch_body = {"first_name": "Irrelevant"}
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await regular_client.put(
                "/users/TESTUSER",
                json=patch_body,
            )

        assert response.status_code == status.HTTP_403_FORBIDDEN, (
            f"Regular user PUT /users/{{user_id}} MUST return HTTP 403 "
            f"(COADM01C CDEMO-USRTYP-ADMIN gate); got "
            f"{response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

    async def test_update_user_service_error(
        self,
        admin_client: AsyncClient,
    ) -> None:
        """Service-layer DB failure on PUT returns HTTP 500.

        Mirrors the COUSR02C generic error path where the REWRITE
        USRSEC fails for a non-NOTFND reason. The service layer
        raises a bare :class:`UserServiceError` with
        :data:`MSG_UNABLE_TO_UPDATE_USER` (``"Unable to Update
        User..."``) and the router translates to HTTP 500.

        Assertions:
            * HTTP 500 Internal Server Error.
            * Response ``detail`` contains the COBOL-exact message.
        """
        patch_body = {"first_name": "Irrelevant"}
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.update_user = AsyncMock(
                side_effect=UserServiceError(MSG_UNABLE_TO_UPDATE_USER),
            )

            response = await admin_client.put(
                "/users/TESTUSER",
                json=patch_body,
            )

        assert response.status_code == status.HTTP_500_INTERNAL_SERVER_ERROR, (
            f"Bare UserServiceError on PUT MUST translate to HTTP 500; "
            f"got {response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # ABEND envelope (see docstring on test_create_user_duplicate).
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        assert MSG_UNABLE_TO_UPDATE_USER in error_envelope["reason"], (
            f"500 reason must contain ``{MSG_UNABLE_TO_UPDATE_USER}``; "
            f"got {error_envelope['reason']!r}"
        )
        # Sanity: 500 Internal Server Error carries error_code='IOER'
        # (I/O-error ABEND-CODE), matching COBOL WS-ERR-FLG='Y'.
        assert error_envelope.get("error_code") == "IOER", (
            f"500 Internal Server Error must carry error_code='IOER' "
            f"(I/O-error ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )


# ============================================================================
# SECTION 4 — Tests for DELETE /users/{user_id} (User Delete, F-021)
# ----------------------------------------------------------------------------
# Covers the COUSR03C.cbl user-delete flow:
#   1. Admin sends DELETE /users/{user_id}; service performs EXEC CICS
#      READ + DELETE USRSEC (SQLAlchemy SELECT + DELETE).
#   2. Response returns the pre-delete snapshot of identity fields
#      (matching the COUSR03 "display then delete" BMS pattern) WITHOUT
#      a password field (COUSR03.CPY lacks PASSWDI/PASSWDO entirely).
#   3. On DFHRESP(NOTFND) -> UserNotFoundError -> HTTP 404.
#   4. Non-admin caller -> HTTP 403.
#   5. Service DB failure -> HTTP 500.
# ============================================================================
class TestUserDelete:
    """Tests for the ``DELETE /users/{user_id}`` endpoint (Feature F-021)."""

    async def test_delete_user_success(self, admin_client: AsyncClient) -> None:
        """Admin successfully deletes a user via DELETE /users/{user_id}.

        Mirrors the COUSR03C READ + DELETE USRSEC happy path. The
        response is the pre-delete snapshot:
        :class:`UserDeleteResponse` (user_id, first_name, last_name,
        user_type — NO password field, matching COUSR03.CPY which
        lacks PASSWDI/PASSWDO).

        Assertions:
            * HTTP 200 OK.
            * Response contains the deleted user's identity.
            * Response does NOT contain a password field
              (enforced by :class:`UserDeleteResponse` schema).
            * Service.delete_user called once with the path user_id.
        """
        expected_response = UserDeleteResponse(
            user_id="GONEUSER",
            first_name="Bye",
            last_name="Gone",
            user_type=_USER_TYPE_REGULAR,
            message="User GONEUSER has been deleted ...",
        )
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.delete_user = AsyncMock(return_value=expected_response)

            response = await admin_client.delete("/users/GONEUSER")

        assert response.status_code == status.HTTP_200_OK, (
            f"DELETE /users/{{user_id}} MUST return HTTP 200; got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        assert body["user_id"] == "GONEUSER"
        assert body["first_name"] == "Bye"
        assert body["last_name"] == "Gone"
        assert body["user_type"] == _USER_TYPE_REGULAR
        # CRITICAL: COUSR03.CPY lacks PASSWDI/PASSWDO — the
        # cloud-native response must likewise omit the password.
        assert "password" not in body, (
            f"Password MUST NEVER appear in delete response "
            f"(COUSR03.CPY omits PASSWDI/PASSWDO); got body={body}"
        )
        mock_instance.delete_user.assert_called_once_with("GONEUSER")

    async def test_delete_user_not_found(self, admin_client: AsyncClient) -> None:
        """DELETE for non-existent user returns HTTP 404.

        Mirrors COUSR03C DFHRESP(NOTFND) on READ USRSEC: the COBOL
        program displays ``"User ID NOT found..."``. In the
        cloud-native target, the service raises
        :class:`UserNotFoundError` and the router translates to
        HTTP 404 Not Found.

        Assertions:
            * HTTP 404 Not Found.
            * Response ``detail`` contains the COBOL-exact message.
        """
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.delete_user = AsyncMock(
                side_effect=UserNotFoundError(MSG_USER_ID_NOT_FOUND),
            )

            response = await admin_client.delete("/users/NOSUCH00")

        assert response.status_code == status.HTTP_404_NOT_FOUND, (
            f"DELETE for non-existent user MUST return HTTP 404 "
            f"(COUSR03C DFHRESP(NOTFND)); got "
            f"{response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # ABEND envelope (see docstring on test_create_user_duplicate).
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        assert MSG_USER_ID_NOT_FOUND in error_envelope["reason"], (
            f"404 reason must contain ``{MSG_USER_ID_NOT_FOUND}``; "
            f"got {error_envelope['reason']!r}"
        )
        # Sanity: 404 Not Found carries error_code='NFND' (not-found
        # ABEND-CODE), matching COBOL DFHRESP(NOTFND).
        assert error_envelope.get("error_code") == "NFND", (
            f"404 Not Found must carry error_code='NFND' "
            f"(not-found ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )

    async def test_delete_user_requires_admin(
        self,
        regular_client: AsyncClient,
    ) -> None:
        """Regular user receives HTTP 403 on DELETE /users/{user_id}.

        Same admin-gate logic as
        :meth:`TestUserList.test_list_users_requires_admin`.

        Assertions:
            * HTTP 403 Forbidden.
            * UserService was NEVER instantiated.
        """
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            response = await regular_client.delete("/users/TESTUSER")

        assert response.status_code == status.HTTP_403_FORBIDDEN, (
            f"Regular user DELETE /users/{{user_id}} MUST return HTTP 403 "
            f"(COADM01C CDEMO-USRTYP-ADMIN gate); got "
            f"{response.status_code}: {response.text}"
        )
        mock_service_class.assert_not_called()

    async def test_delete_user_service_error(
        self,
        admin_client: AsyncClient,
    ) -> None:
        """Service-layer DB failure on DELETE returns HTTP 500.

        Mirrors the COUSR03C generic error path where the DELETE
        USRSEC fails for a non-NOTFND reason (e.g., catalog
        corruption, file not open). The COBOL program re-uses the
        "Unable to Update User..." message in this path (the service
        layer also reuses :data:`MSG_UNABLE_TO_UPDATE_USER` for
        consistency — the same COBOL WS-MESSAGE field is populated
        for both update and delete error flows).

        The router translates the bare :class:`UserServiceError` to
        HTTP 500 Internal Server Error.

        Assertions:
            * HTTP 500 Internal Server Error.
            * Response ``detail`` contains the underlying message.
        """
        # The service may raise UserServiceError with a variety of
        # messages (e.g., MSG_UNABLE_TO_LOOKUP when the READ fails
        # before DELETE; MSG_UNABLE_TO_UPDATE_USER when the DELETE
        # itself fails). We exercise one representative case.
        with patch("src.api.routers.user_router.UserService") as mock_service_class:
            mock_instance: MagicMock = mock_service_class.return_value
            mock_instance.delete_user = AsyncMock(
                side_effect=UserServiceError(MSG_UNABLE_TO_LOOKUP),
            )

            response = await admin_client.delete("/users/TESTUSER")

        assert response.status_code == status.HTTP_500_INTERNAL_SERVER_ERROR, (
            f"Bare UserServiceError on DELETE MUST translate to HTTP 500; "
            f"got {response.status_code}: {response.text}"
        )
        body: dict[str, Any] = response.json()
        # ABEND envelope (see docstring on test_create_user_duplicate).
        assert "error" in body, f"Response body must contain ``error`` envelope: {body}"
        error_envelope: dict[str, Any] = body["error"]
        assert MSG_UNABLE_TO_LOOKUP in error_envelope["reason"], (
            f"500 reason must contain ``{MSG_UNABLE_TO_LOOKUP}``; "
            f"got {error_envelope['reason']!r}"
        )
        # Sanity: 500 Internal Server Error carries error_code='IOER'
        # (I/O-error ABEND-CODE), matching COBOL WS-ERR-FLG='Y'.
        assert error_envelope.get("error_code") == "IOER", (
            f"500 Internal Server Error must carry error_code='IOER' "
            f"(I/O-error ABEND-CODE); got {error_envelope.get('error_code')!r}"
        )
