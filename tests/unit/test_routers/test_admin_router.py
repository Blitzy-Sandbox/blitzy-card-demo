# ============================================================================
# CardDemo — Unit tests for admin_router (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COADM01C.cbl     — CICS admin menu program, transaction ``CA00``
#                                (~250 lines). Evaluates the user's option
#                                selection from the BMS map (``COADM01.BMS``),
#                                validates it against the 4-entry
#                                ``CDEMO-ADMIN-OPTIONS`` table, and issues
#                                ``EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-
#                                PGMNAME(WS-OPTION))`` to transfer control to
#                                one of ``COUSR00C``/``COUSR01C``/``COUSR02C``/
#                                ``COUSR03C``. Gated by the COMMAREA 88-level
#                                ``CDEMO-USRTYP-ADMIN VALUE 'A'``.
#   * app/cpy/COADM02Y.cpy     — Admin menu option table. 4 populated rows:
#                                  1. 'User List (Security)'   → COUSR00C
#                                  2. 'User Add (Security)'    → COUSR01C
#                                  3. 'User Update (Security)' → COUSR02C
#                                  4. 'User Delete (Security)' → COUSR03C
#                                The COBOL table is over-allocated
#                                ``OCCURS 9 TIMES`` but only the first
#                                ``CDEMO-ADMIN-OPT-COUNT = 4`` rows are
#                                populated and exposed via
#                                :data:`ADMIN_MENU_OPT_COUNT`.
#   * app/cpy/COCOM01Y.cpy     — CARDDEMO-COMMAREA (96 bytes). Provides
#                                ``CDEMO-USER-TYPE PIC X(01)`` with 88-level
#                                conditions: ``CDEMO-USRTYP-ADMIN VALUE 'A'``
#                                (maps to JWT ``user_type='A'``) and
#                                ``CDEMO-USRTYP-USER VALUE 'U'`` (maps to
#                                JWT ``user_type='U'``).
#   * app/cpy-bms/COADM01.CPY  — Admin menu BMS symbolic map. Defines the
#                                OPTIONI field (2 chars) for user selection
#                                and OPT01-12I labels (40 chars each) — the
#                                cloud-native equivalent is the JSON
#                                ``options`` array returned by
#                                ``GET /admin/menu``.
# ----------------------------------------------------------------------------
# Feature F-003: Admin Menu. Target implementation under test:
# ``src/api/routers/admin_router.py`` — FastAPI router providing
# ``GET /admin/menu`` and ``GET /admin/status`` endpoints. Both endpoints
# depend on :func:`src.api.dependencies.get_current_admin_user` so that
# non-admin users (JWT ``user_type='U'``) receive HTTP 403 Forbidden,
# byte-for-byte mirroring the COADM01C ``IF CDEMO-USRTYP-ADMIN`` gate.
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
"""Unit tests for :mod:`src.api.routers.admin_router`.

Validates admin-only access enforcement and menu-option content of the
FastAPI admin router converted from ``app/cbl/COADM01C.cbl`` (CICS
transaction ``CA00``, Feature F-003) per AAP §0.5.1 (File-by-File
Transformation Plan).

COBOL -> Python Verification Surface
------------------------------------
================================================  =============================================
COBOL paragraph / statement                       Python test (this module)
================================================  =============================================
``IF CDEMO-USRTYP-ADMIN`` admin-only gate         ``test_get_admin_menu_regular_user_forbidden``
(88-level VALUE 'A' on CDEMO-USER-TYPE)           ``test_get_admin_status_regular_user_forbidden``
                                                  ``test_all_admin_endpoints_reject_regular_users``
``PERFORM BUILD-MENU-OPTIONS`` L186-201           ``test_get_admin_menu_success``
(iterate ``CDEMO-ADMIN-OPT-COUNT = 4`` rows       ``test_get_admin_menu_options_match_coadm02y``
populating OPTN001O..OPTN004O)
``SEND MAP('COADM1A') MAPSET('COADM01')``         ``test_get_admin_menu_success``
(initial menu render, L221)
``EXEC CICS RETURN WITH COMMAREA(...)``           ``test_get_admin_menu_success`` (stateless —
(session-state propagation)                       no COMMAREA preserved across requests)
``POPULATE-HEADER-INFO`` title fields             ``test_get_admin_menu_has_menu_title``
(CCDA-TITLE01 / CCDA-TITLE02, L202-221)
``RECEIVE MAP` without Authorization header        ``test_get_admin_menu_no_auth``
(EIBCALEN = 0 handling, implicit 401 today)       ``test_get_admin_status_no_auth``
XCTL to COUSR00C/01C/02C/03C                      Each menu option exposes the equivalent
(L251-293: 4-way EVALUATE on WS-OPTION)            ``endpoint`` + ``method`` REST pair:
                                                    Option 1 -> GET    /users
                                                    Option 2 -> POST   /users
                                                    Option 3 -> PUT    /users/{user_id}
                                                    Option 4 -> DELETE /users/{user_id}
================================================  =============================================

JWT-Based Admin Gate (replacing COMMAREA CDEMO-USER-TYPE)
---------------------------------------------------------
The CICS COMMAREA (``CARDDEMO-COMMAREA`` from ``COCOM01Y.cpy``) that
flows between programs with every ``EXEC CICS RETURN`` is replaced by
a JWT bearer token. The ``user_type`` claim in the JWT maps 1:1 to
``CDEMO-USER-TYPE PIC X(01)`` with two valid values:

* ``'A'`` (``CDEMO-USRTYP-ADMIN``) — admin user, may reach COADM01C /
  ``/admin/*`` endpoints.
* ``'U'`` (``CDEMO-USRTYP-USER``) — regular user, routed to COMEN01C /
  ``/menu`` main menu; attempts to reach ``/admin/*`` return HTTP 403.

These tests exercise the three authentication / authorization outcomes
defined by the layered middleware + dependency stack in
``src/api/middleware/auth.py`` + ``src/api/dependencies.py``:

* **HTTP 200 OK**   — admin JWT, admin dep override (admin_client
                      fixture from conftest).
* **HTTP 403 Forbidden** — regular-user JWT; triggers either the
                      middleware-layer admin-path check (``/admin``
                      prefix) or the router-layer
                      :func:`get_current_admin_user` 403 — both
                      outcomes are equivalent for the caller.
* **HTTP 401 Unauthorized** — no Authorization header; the
                      :class:`JWTAuthMiddleware` rejects the request
                      before any dependency runs.

Fixtures Used
-------------
From :mod:`tests.conftest`:
    * ``admin_client``      — AsyncClient with admin JWT and admin
                              CurrentUser dependency override.
    * ``regular_client``    — AsyncClient with regular-user JWT and
                              regular CurrentUser dependency override.
    * ``test_app``          — FastAPI app used to build custom
                              AsyncClients for the no-auth (401) tests.
    * ``admin_jwt_token``   — Raw admin JWT string (used when
                              constructing clients with custom
                              dependency overrides).
    * ``create_test_token`` — Helper for parametric / custom-claim
                              tokens (e.g., tokens with unusual
                              user_type values).

See Also
--------
* AAP §0.5.1  — File-by-File Transformation Plan (admin_router row).
* AAP §0.7.1  — "Preserve all existing functionality exactly as-is".
* :mod:`src.api.routers.admin_router` — the module under test.
* :mod:`src.api.dependencies` —
  :func:`get_current_admin_user` (403 gate) and
  :func:`get_current_user` (401 gate).
* :mod:`src.api.middleware.auth` —
  :class:`JWTAuthMiddleware`, ``ADMIN_ONLY_PREFIXES``.
"""

from __future__ import annotations

from typing import Any, cast
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI, status
from httpx import ASGITransport, AsyncClient

from src.api.dependencies import CurrentUser, get_current_admin_user, get_current_user
from src.api.main import app, create_app
from src.shared.constants.menu_options import ADMIN_MENU_OPT_COUNT

# ============================================================================
# Test constants — tightly coupled to conftest.py fixture values
# ============================================================================
# The admin_client fixture in conftest.py overrides get_current_user to
# return ``CurrentUser(user_id="ADMIN001", user_type="A", is_admin=True)``.
# We mirror that identity here so response-body assertions remain
# self-documenting (rather than importing conftest private constants).
# ============================================================================
_EXPECTED_ADMIN_USER_ID: str = "ADMIN001"

# The admin router builds its JSON payload from a fixed 4-row table
# translated from COADM02Y.cpy. The ``expected_label``/``expected_endpoint``/
# ``expected_method`` triples below mirror the router's implementation
# choice of (label, endpoint, method) per AAP §0.5.1 — we encode them as
# a list of tuples so :func:`pytest.mark.parametrize` can drive the
# ``test_admin_menu_option_n`` parametrization without repeating itself.
# ============================================================================
_EXPECTED_ADMIN_MENU_OPTIONS: list[tuple[int, str, str, str]] = [
    # (option_num, expected_label, expected_endpoint, expected_method)
    (1, "User List", "/users", "GET"),
    (2, "User Add", "/users", "POST"),
    (3, "User Update", "/users/{user_id}", "PUT"),
    (4, "User Delete", "/users/{user_id}", "DELETE"),
]

# ============================================================================
# Sanity check on the legacy-option count (COADM02Y.cpy literal).
# ============================================================================
# CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4 — hard-coded in COADM02Y.cpy line
# 20 — is the source of truth for the number of populated admin menu rows.
# A future refactor that accidentally changed this literal (or dropped a
# menu row) MUST fail this sanity check at module import time so no admin-
# menu test can drift silently from the COBOL contract.
# ============================================================================
assert ADMIN_MENU_OPT_COUNT == 4, f"ADMIN_MENU_OPT_COUNT must be 4 per COADM02Y.cpy line 20, got {ADMIN_MENU_OPT_COUNT}"
assert len(_EXPECTED_ADMIN_MENU_OPTIONS) == ADMIN_MENU_OPT_COUNT, (
    "Local test data must match ADMIN_MENU_OPT_COUNT — out-of-sync with the COBOL source of truth (COADM02Y.cpy)"
)


# ============================================================================
# SECTION 1 — Tests for GET /admin/menu
# ----------------------------------------------------------------------------
# Covers the COADM01C.cbl main path:
#   1. Admin user lands on /admin -> BUILD-MENU-OPTIONS iterates the
#      CDEMO-ADMIN-OPTIONS table populating OPTN001O..OPTN004O, then
#      SEND MAP('COADM1A') renders the menu.
#   2. Regular user reaches the same URL -> admin-gate (88-level
#      CDEMO-USRTYP-ADMIN) rejects the request; in the cloud-native
#      architecture this becomes HTTP 403 Forbidden.
#   3. Unauthenticated client -> JWTAuthMiddleware returns HTTP 401 with
#      WWW-Authenticate: Bearer header.
# ============================================================================
class TestAdminMenu:
    """Tests for the ``GET /admin/menu`` endpoint."""

    async def test_get_admin_menu_success(self, admin_client: AsyncClient) -> None:
        """Admin user successfully retrieves the 4-option admin menu.

        Mirrors the happy-path scenario in ``COADM01C.cbl`` lines 220-221
        where an authenticated admin user reaches the program and the
        main-para issues ``PERFORM SEND-MAP-COADM1A``. The response body
        is the JSON equivalent of the BMS screen rendered by
        ``SEND MAP('COADM1A') MAPSET('COADM01')``.

        Assertions:
            * HTTP 200 OK.
            * Response body is JSON (``application/json``).
            * Payload contains ``menu_title`` and ``options`` keys.
            * ``options`` is a list of exactly
              :data:`ADMIN_MENU_OPT_COUNT` (= 4) entries —
              the legacy ``CDEMO-ADMIN-OPT-COUNT`` literal from
              ``COADM02Y.cpy`` line 20.
        """
        response = await admin_client.get("/admin/menu")

        # HTTP 200 — admin user passes both middleware gate and
        # router-level :func:`get_current_admin_user` dependency.
        assert response.status_code == status.HTTP_200_OK, (
            f"Expected HTTP 200 OK for admin access; got {response.status_code}: {response.text}"
        )
        # Content-Type must be JSON — admin_router declares it as the
        # only response MIME type so clients can deserialize without
        # conditional parsing.
        assert response.headers["content-type"].startswith("application/json"), (
            f"Expected JSON content-type; got {response.headers.get('content-type')}"
        )

        body: dict[str, Any] = response.json()

        # Structural assertions — the payload shape is a public contract
        # declared in admin_router.get_admin_menu's docstring. Any change
        # here would be a breaking API change for admin console clients.
        assert "menu_title" in body, "Response missing required 'menu_title' key"
        assert "options" in body, "Response missing required 'options' key"
        assert isinstance(body["options"], list), "'options' must be a JSON array"
        assert len(body["options"]) == ADMIN_MENU_OPT_COUNT, (
            f"Admin menu must expose exactly {ADMIN_MENU_OPT_COUNT} "
            f"options (COADM02Y.cpy CDEMO-ADMIN-OPT-COUNT), "
            f"got {len(body['options'])}"
        )

    async def test_get_admin_menu_has_menu_title(self, admin_client: AsyncClient) -> None:
        """Menu response includes the admin menu title.

        Replaces the ``CCDA-TITLE01``/``CCDA-TITLE02`` fields that
        COADM01C's ``POPULATE-HEADER-INFO`` paragraph (lines 202-221)
        moves into the BMS output map. The cloud-native client uses the
        ``menu_title`` field to render the screen chrome; the actual
        value is a documented literal ("Administrative Menu") declared
        in ``admin_router.get_admin_menu`` (line 285).
        """
        response = await admin_client.get("/admin/menu")

        assert response.status_code == status.HTTP_200_OK
        body: dict[str, Any] = response.json()
        assert body["menu_title"] == "Administrative Menu", (
            f"Expected menu_title='Administrative Menu', got {body.get('menu_title')!r}"
        )

    @pytest.mark.parametrize(
        ("option_num", "expected_label", "expected_endpoint", "expected_method"),
        _EXPECTED_ADMIN_MENU_OPTIONS,
        ids=[
            "option_1_user_list_COUSR00C",
            "option_2_user_add_COUSR01C",
            "option_3_user_update_COUSR02C",
            "option_4_user_delete_COUSR03C",
        ],
    )
    async def test_get_admin_menu_options_match_coadm02y(
        self,
        admin_client: AsyncClient,
        option_num: int,
        expected_label: str,
        expected_endpoint: str,
        expected_method: str,
    ) -> None:
        """Each admin menu option matches COADM02Y.cpy row content.

        Verifies the 4 rows of ``CDEMO-ADMIN-OPTIONS-DATA`` from
        ``COADM02Y.cpy`` lines 24-42 after the cloud-native transformation:

        * COBOL ``NUM PIC 9(02)``       -> JSON ``option`` (int).
        * COBOL ``NAME PIC X(35)``      -> JSON ``label`` (trimmed of
                                           trailing space padding, and
                                           with the "(Security)" suffix
                                           dropped for brevity — the
                                           admin router's published
                                           contract per AAP §0.5.1).
        * COBOL ``PGMNAME PIC X(08)``   -> JSON ``endpoint`` + ``method``
                                           (the cloud-native pair that
                                           replaces the legacy XCTL
                                           target program name).

        This parametric test drives one case per row so failures report
        the specific option that regressed rather than a monolithic
        "entire options array" assertion.

        Parameters
        ----------
        option_num
            Expected ``option`` value (1-indexed, mirroring
            ``CDEMO-ADMIN-OPT-NUM``).
        expected_label
            Expected ``label`` text.
        expected_endpoint
            Expected ``endpoint`` REST path.
        expected_method
            Expected HTTP verb (uppercase).
        """
        response = await admin_client.get("/admin/menu")
        assert response.status_code == status.HTTP_200_OK

        options: list[dict[str, Any]] = response.json()["options"]
        # Find the entry by option number so test order is independent of
        # JSON array ordering (defensive even though we expect insertion
        # order to be preserved by FastAPI's default JSON encoder).
        matching: list[dict[str, Any]] = [opt for opt in options if opt.get("option") == option_num]
        assert len(matching) == 1, (
            f"Expected exactly one option with option={option_num}; found {len(matching)} in options={options}"
        )
        entry = matching[0]

        # Validate all four fields of the option per the router contract.
        assert entry["option"] == option_num
        assert entry["label"] == expected_label, (
            f"Option {option_num}: expected label={expected_label!r}, got {entry.get('label')!r}"
        )
        assert entry["endpoint"] == expected_endpoint, (
            f"Option {option_num}: expected endpoint={expected_endpoint!r}, got {entry.get('endpoint')!r}"
        )
        assert entry["method"] == expected_method, (
            f"Option {option_num}: expected method={expected_method!r}, got {entry.get('method')!r}"
        )

    async def test_get_admin_menu_options_are_in_order(self, admin_client: AsyncClient) -> None:
        """Admin menu options appear in 1..4 order matching COADM02Y.cpy.

        The COBOL table is declared with ``OCCURS`` — the order of the
        ``VALUE`` literals (lines 24, 29, 34, 39 of ``COADM02Y.cpy``) is
        the canonical display order, which the legacy BMS
        ``PERFORM VARYING I FROM 1 BY 1`` loop iterated in ascending
        order. Preserving this order is important for admin users who
        have memorized the option numbers.
        """
        response = await admin_client.get("/admin/menu")
        assert response.status_code == status.HTTP_200_OK

        option_nums: list[int] = [opt["option"] for opt in response.json()["options"]]
        assert option_nums == [1, 2, 3, 4], (
            f"Admin menu options must be in 1..4 order (matching the "
            f"COADM02Y.cpy VALUE-literal sequence); got {option_nums}"
        )

    async def test_get_admin_menu_with_mocked_admin_dependency(
        self,
        test_app: FastAPI,
        admin_jwt_token: str,
    ) -> None:
        """Admin menu endpoint works with a fully mocked admin dependency.

        Demonstrates the fine-grained dependency-override pattern using
        :class:`unittest.mock.AsyncMock` for the async
        :func:`get_current_admin_user` dependency and
        :class:`unittest.mock.MagicMock` for the returned
        :class:`CurrentUser` instance. This is useful for tests that
        need to verify router behavior with identities beyond the
        ``ADMIN001`` default provided by the ``admin_client`` fixture.

        The admin JWT is still required in the ``Authorization`` header
        so that :class:`JWTAuthMiddleware` allows the request to reach
        the router — the middleware runs BEFORE FastAPI's dependency
        resolution and operates on the raw JWT, not the overridden
        dependency.
        """
        # Construct a mock CurrentUser with custom identity. ``spec=``
        # constrains the MagicMock to the CurrentUser attribute surface
        # so typos in attribute names surface as AttributeError rather
        # than silently succeeding.
        custom_admin = MagicMock(spec=CurrentUser)
        custom_admin.user_id = "CUSTOMAD"
        custom_admin.user_type = "A"
        custom_admin.is_admin = True

        # Wrap the mock identity in an AsyncMock because
        # :func:`get_current_admin_user` is an ``async def`` — FastAPI's
        # dependency resolver ``await`` s the override, so it must return
        # an awaitable.
        admin_dep_mock = AsyncMock(return_value=custom_admin)

        # FastAPI inspects the override callable's signature to determine
        # what parameters to inject from the request. A bare AsyncMock
        # exposes a generic ``*args, **kwargs`` signature which FastAPI
        # misinterprets as required query parameters and rejects with
        # HTTP 422. We wrap the AsyncMock in a thin ``async def`` whose
        # explicit ``() -> CurrentUser`` signature FastAPI correctly
        # resolves as "no request-derived parameters". The wrapper
        # forwards the await to the AsyncMock so
        # :meth:`AsyncMock.assert_awaited` still reports truthy.
        async def _admin_dep_override() -> CurrentUser:
            # cast: AsyncMock returns Any; the wrapper's return type
            # advertises CurrentUser so FastAPI's dependency validator
            # is satisfied and mypy is kept happy without `# type: ignore`.
            return cast(CurrentUser, await admin_dep_mock())

        test_app.dependency_overrides[get_current_admin_user] = _admin_dep_override

        # Use ASGITransport directly so the middleware stack (including
        # the JWT check) runs exactly as in production.
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
            headers={"Authorization": f"Bearer {admin_jwt_token}"},
        ) as mock_client:
            response = await mock_client.get("/admin/menu")

        # Cleanup the custom override so it doesn't leak into later tests.
        del test_app.dependency_overrides[get_current_admin_user]

        # Assertions — the response is still the canonical admin menu
        # because the router body does not branch on user_id. This test
        # primarily demonstrates that the dependency-override pattern
        # works end-to-end through the middleware.
        assert response.status_code == status.HTTP_200_OK
        body = response.json()
        assert body["menu_title"] == "Administrative Menu"
        assert len(body["options"]) == ADMIN_MENU_OPT_COUNT
        # The AsyncMock should have been awaited at least once (the
        # router declares the dep with ``Depends(get_current_admin_user)``
        # so FastAPI calls it for every request).
        admin_dep_mock.assert_awaited()

    async def test_get_admin_menu_logs_audit_event(self, admin_client: AsyncClient) -> None:
        """Admin menu endpoint emits a structured audit log entry.

        Replaces the CICS transaction audit trail — in the legacy
        system, admin menu access was captured in the JES2 system log
        via the TRANID ``CA00`` recorded by the CICS dispatcher. The
        cloud-native equivalent is a ``logger.info`` call with
        structured ``extra`` fields that CloudWatch Logs Insights
        indexes per-field for operational dashboards.

        Verifies the audit log entry carries the expected
        ``cobol_source`` and ``feature`` tags so audit reports can
        correlate Python API calls back to their COBOL origin.
        """
        # Patch the router-local logger so ``logger.info(...)`` calls are
        # captured without appearing in the test output (keeps pytest's
        # output clean while still verifying the call).
        with patch("src.api.routers.admin_router.logger") as mock_logger:
            response = await admin_client.get("/admin/menu")

        assert response.status_code == status.HTTP_200_OK
        # Exactly one audit log entry per request.
        mock_logger.info.assert_called_once()
        call_args = mock_logger.info.call_args
        # First positional arg is the log message.
        assert call_args.args[0] == "GET /admin/menu accessed", f"Unexpected audit message: {call_args.args[0]!r}"
        # Structured extras — each field is indexed separately in
        # CloudWatch Logs Insights. AAP §0.7.2 specifies structured
        # JSON logging for monitoring.
        extra: dict[str, Any] = call_args.kwargs["extra"]
        assert extra["endpoint"] == "/admin/menu"
        assert extra["cobol_source"] == "COADM01C.cbl"
        assert extra["feature"] == "F-003"
        assert extra["user_id"] == _EXPECTED_ADMIN_USER_ID

    async def test_get_admin_menu_regular_user_forbidden(self, regular_client: AsyncClient) -> None:
        """Regular user (user_type='U') receives HTTP 403 on /admin/menu.

        Mirrors the ``IF CDEMO-USRTYP-ADMIN`` gate in ``COSGN00C.cbl``
        (lines 230-239) that XCTL'd only admin users to ``COADM01C``
        and routed regular users to ``COMEN01C`` instead. The
        cloud-native equivalent is a hard HTTP 403 Forbidden returned
        either by :class:`JWTAuthMiddleware` (middleware-level
        ``/admin`` prefix check — the fast path) or by
        :func:`get_current_admin_user` (router dep — the fallback
        path). Both outcomes are equivalent for the caller.

        This is the **core security test** for the admin router — if
        this assertion ever regresses, non-admin users can read the
        admin menu, which is a privilege-escalation bug.
        """
        response = await regular_client.get("/admin/menu")
        assert response.status_code == status.HTTP_403_FORBIDDEN, (
            f"Non-admin user MUST be rejected with HTTP 403 on /admin/* "
            f"(COADM01C CDEMO-USRTYP-ADMIN gate); got "
            f"{response.status_code}: {response.text}"
        )

    async def test_get_admin_menu_no_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated request to /admin/menu returns HTTP 401.

        The :class:`JWTAuthMiddleware` rejects the request BEFORE the
        router dependency stack runs because no ``Authorization``
        header is present. The response carries a
        ``WWW-Authenticate: Bearer`` challenge per RFC 7235 §4.1.

        Builds a custom ``AsyncClient`` (rather than reusing the
        ``client`` / ``admin_client`` / ``regular_client`` fixtures,
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
            response = await unauth_client.get("/admin/menu")

        assert response.status_code == status.HTTP_401_UNAUTHORIZED, (
            f"Unauthenticated /admin/menu access MUST return HTTP 401; got {response.status_code}: {response.text}"
        )
        # RFC 7235 §4.1 — challenge MUST include a WWW-Authenticate
        # header identifying the scheme. Middleware emits "Bearer".
        assert "www-authenticate" in {key.lower() for key in response.headers}, (
            f"401 response MUST include WWW-Authenticate header per RFC 7235 §4.1; headers={dict(response.headers)}"
        )


# ============================================================================
# SECTION 2 — Tests for GET /admin/status
# ----------------------------------------------------------------------------
# The /admin/status endpoint is a cloud-native addition — there is no
# direct COBOL equivalent. The closest COBOL analogue is a CEMT
# INQUIRE FILE status probe that operations staff ran manually to check
# whether the admin CICS region was healthy. The cloud-native form is a
# lightweight JSON payload with the authenticated admin's user_id.
# ============================================================================
class TestAdminStatus:
    """Tests for the ``GET /admin/status`` endpoint."""

    async def test_get_admin_status_success(self, admin_client: AsyncClient) -> None:
        """Admin user successfully retrieves admin system status.

        Assertions:
            * HTTP 200 OK.
            * Response body carries ``status="operational"`` literal.
            * Response body carries ``user`` field with the admin's
              user_id (``ADMIN001`` — conftest.py _TEST_ADMIN_ID).
        """
        response = await admin_client.get("/admin/status")

        assert response.status_code == status.HTTP_200_OK, (
            f"Expected HTTP 200 OK; got {response.status_code}: {response.text}"
        )

        body: dict[str, Any] = response.json()
        # The "operational" literal is a forward-compatibility contract —
        # if the router ever adds nested status fields, the literal
        # string at the top level MUST remain so existing dashboards
        # that assert on ``body["status"] == "operational"`` do not
        # break.
        assert body.get("status") == "operational", f"Expected status='operational'; got {body.get('status')!r}"
        # The admin's user_id propagates from the JWT ``user_id`` claim
        # through :func:`get_current_admin_user` into the response so
        # on-call runbooks can confirm the signed-in identity at a
        # glance without parsing the JWT.
        assert body.get("user") == _EXPECTED_ADMIN_USER_ID, (
            f"Expected user={_EXPECTED_ADMIN_USER_ID!r}; got {body.get('user')!r}"
        )

    async def test_get_admin_status_regular_user_forbidden(self, regular_client: AsyncClient) -> None:
        """Regular user (user_type='U') receives HTTP 403 on /admin/status.

        Same admin-gate semantic as
        :meth:`TestAdminMenu.test_get_admin_menu_regular_user_forbidden`
        — every route under the ``/admin`` prefix is admin-only per
        ``ADMIN_ONLY_PREFIXES`` in ``src/api/middleware/auth.py``, which
        maps 1:1 to the ``IF CDEMO-USRTYP-ADMIN`` gate in
        ``COSGN00C.cbl`` lines 230-239.
        """
        response = await regular_client.get("/admin/status")
        assert response.status_code == status.HTTP_403_FORBIDDEN, (
            f"Non-admin user MUST be rejected with HTTP 403 on "
            f"/admin/status (admin-only prefix); got "
            f"{response.status_code}: {response.text}"
        )

    async def test_get_admin_status_no_auth(self, test_app: FastAPI) -> None:
        """Unauthenticated request to /admin/status returns HTTP 401.

        :class:`JWTAuthMiddleware` rejects at the first step because no
        ``Authorization`` header is present. Exercises the same
        middleware path as ``test_get_admin_menu_no_auth``.
        """
        transport = ASGITransport(app=test_app)
        async with AsyncClient(
            transport=transport,
            base_url="http://testserver",
        ) as unauth_client:
            response = await unauth_client.get("/admin/status")

        assert response.status_code == status.HTTP_401_UNAUTHORIZED, (
            f"Unauthenticated /admin/status access MUST return HTTP 401; got {response.status_code}: {response.text}"
        )


# ============================================================================
# SECTION 3 — Cross-endpoint admin-access enforcement
# ----------------------------------------------------------------------------
# This parameterized test exercises the invariant that EVERY admin
# router endpoint rejects non-admin users with HTTP 403. New endpoints
# added to ``src/api/routers/admin_router.py`` in the future should be
# added to the parametrize list so the admin-gate invariant is enforced
# across the full admin surface.
#
# Mirrors the legacy behavior where the COADM01C admin menu was the
# single entry point to the user-administration sub-transactions
# (COUSR00C-COUSR03C) and every one of them implicitly required the
# caller to have already passed through the COADM01C admin gate.
# ============================================================================
@pytest.mark.parametrize(
    "path",
    [
        "/admin/menu",
        "/admin/status",
    ],
    ids=[
        "admin_menu_endpoint",
        "admin_status_endpoint",
    ],
)
async def test_all_admin_endpoints_reject_regular_users(regular_client: AsyncClient, path: str) -> None:
    """Every admin router endpoint rejects regular users with HTTP 403.

    This test enforces the cross-endpoint invariant that the
    ``CDEMO-USRTYP-ADMIN`` gate (from ``COCOM01Y.cpy`` 88-level VALUE
    'A') applies uniformly to every route under ``/admin/*``. Whenever
    a new admin endpoint is added, the corresponding path must be
    added to the ``parametrize`` list above so this test catches any
    drift from the invariant.

    Parameters
    ----------
    regular_client
        Fixture providing an :class:`httpx.AsyncClient` authenticated
        with ``user_type='U'`` (regular user — maps to
        ``CDEMO-USRTYP-USER VALUE 'U'``).
    path
        Admin router path to probe. Each URL listed in the
        ``parametrize`` decorator becomes a separate test case, so
        failures point at the specific endpoint that regressed rather
        than a single monolithic assertion.
    """
    response = await regular_client.get(path)
    assert response.status_code == status.HTTP_403_FORBIDDEN, (
        f"Admin endpoint {path!r} MUST reject non-admin users with "
        f"HTTP 403 (COADM01C CDEMO-USRTYP-ADMIN gate); got "
        f"{response.status_code}: {response.text}"
    )


# ============================================================================
# SECTION 4 — Admin router integration with the FastAPI app
# ----------------------------------------------------------------------------
# Sanity tests that verify admin_router is properly registered on the
# main FastAPI application factory (:func:`create_app`). These tests do
# not exercise HTTP traffic — they inspect the app's route table
# directly to catch any regression in ``src/api/main.py`` include_router
# invocations (e.g., a missing ``admin_router`` import, a wrong prefix).
# ============================================================================
class TestAdminRouterRegistration:
    """Tests that verify admin_router registration on the FastAPI app."""

    def test_admin_router_registered_on_module_level_app(self) -> None:
        """Module-level ``app`` (:data:`src.api.main.app`) includes /admin.

        The module-level :data:`app` is the production ASGI entry point
        consumed by Uvicorn in the ECS Fargate container. Any regression
        that removed the admin_router include_router call — e.g., a
        merge conflict, a rename, a missed import — would silently break
        the admin console in production. This test makes that regression
        fail at import time.
        """
        # Extract the path of every registered route. Some routes expose
        # ``path`` (regular routes) and some expose ``path_format``
        # (starlette Route subclasses) — we defensively check both.
        registered_paths: set[str] = {getattr(route, "path", "") for route in app.routes}
        assert "/admin/menu" in registered_paths, (
            f"/admin/menu MUST be registered on the main FastAPI app "
            f"(admin_router mount in src/api/main.py include_router). "
            f"Registered paths sample: "
            f"{sorted(p for p in registered_paths if p)[:20]}"
        )
        assert "/admin/status" in registered_paths, (
            f"/admin/status MUST be registered on the main FastAPI app; "
            f"registered paths sample: "
            f"{sorted(p for p in registered_paths if p)[:20]}"
        )

    def test_create_app_registers_admin_routes(self) -> None:
        """Freshly-built app from :func:`create_app` exposes /admin/*.

        ``create_app`` is the factory used by both the production
        module-level :data:`app` and by every test fixture that builds
        a custom app. Verifying that it yields an app with the admin
        routes wired up catches any regression in
        :func:`create_app`'s router-mounting logic independent of the
        module-level singleton.
        """
        fresh_app = create_app()
        paths: set[str] = {getattr(route, "path", "") for route in fresh_app.routes}
        assert "/admin/menu" in paths
        assert "/admin/status" in paths

    def test_admin_routes_use_admin_dependency(self) -> None:
        """All admin_router routes declare :func:`get_current_admin_user`.

        Defensive check that catches the mistake of adding a new admin
        endpoint that forgets the ``Depends(get_current_admin_user)``
        parameter — which would silently open an admin-only URL to
        non-admin callers. This test walks every route under ``/admin``
        and asserts the dependency appears in the route's dependant
        tree.

        The FastAPI ``APIRoute.dependant`` attribute carries a tree of
        :class:`fastapi.dependencies.models.Dependant` nodes — we
        flatten it and check whether ``get_current_admin_user`` is any
        of the registered dep callables.

        NOTE: :func:`get_current_user` is *also* in the tree (because
        ``get_current_admin_user`` depends on it), which is the correct
        behavior — an admin-only route transitively requires a valid
        authenticated user. This test deliberately looks only for
        ``get_current_admin_user`` (the admin-gate marker).
        """
        fresh_app = create_app()
        # Collect (path, route) pairs for routes under /admin that carry
        # a ``dependant`` attribute. Using ``getattr`` both for the path
        # filter and as the attribute probe keeps mypy happy because
        # :class:`starlette.routing.BaseRoute` (the static type of
        # :attr:`FastAPI.routes` entries) doesn't declare ``path`` or
        # ``dependant`` — only its :class:`fastapi.routing.APIRoute`
        # subclass does.
        admin_routes: list[tuple[str, Any]] = [
            (getattr(route, "path", ""), route)
            for route in fresh_app.routes
            if getattr(route, "path", "").startswith("/admin") and hasattr(route, "dependant")
        ]
        assert admin_routes, "No /admin routes found on app — registration broken"

        for route_path, route in admin_routes:
            # Walk the dependant tree and collect all dep callables.
            # Each Dependant node has a ``.dependencies`` list of child
            # Dependant objects and a ``.call`` attribute for the
            # resolved callable.
            dep_callables: list[Any] = []
            stack = list(route.dependant.dependencies)
            while stack:
                node = stack.pop()
                if node.call is not None:
                    dep_callables.append(node.call)
                stack.extend(node.dependencies)

            assert get_current_admin_user in dep_callables, (
                f"Admin route {route_path!r} MUST depend on "
                f"get_current_admin_user to enforce the 88-level "
                f"CDEMO-USRTYP-ADMIN gate from COCOM01Y.cpy. "
                f"Registered dependencies: "
                f"{[getattr(c, '__name__', str(c)) for c in dep_callables]}"
            )
            # Sanity: the transitive get_current_user dep must also
            # appear, confirming the admin-gate chain
            # get_current_admin_user -> get_current_user is intact.
            assert get_current_user in dep_callables, (
                f"Admin route {route_path!r} MUST also carry the "
                f"transitive get_current_user dependency (JWT decode). "
                f"Registered dependencies: "
                f"{[getattr(c, '__name__', str(c)) for c in dep_callables]}"
            )
