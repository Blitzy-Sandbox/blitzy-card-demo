# ============================================================================
# Source: app/cbl/COADM01C.cbl  (Admin menu, Feature F-003)
#         + app/cpy-bms/COADM01.CPY  (BMS symbolic map)
#         + app/cpy/COADM02Y.cpy     (Admin menu options table) —
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
"""Admin router — HTTP transport for Feature F-003 (Admin menu).

Endpoint summary
----------------
``GET /admin/menu``   — Return the admin-menu option list
                       (F-003, COADM01C.cbl).
``GET /admin/status`` — Lightweight admin-liveness probe (cloud-
                       native addition — the equivalent of a CICS
                       "enter the admin region" landing page).

Both endpoints are admin-only. The :class:`JWTAuthMiddleware`
enforces this globally via the ``/admin`` prefix being listed in
``ADMIN_ONLY_PREFIXES``.

COBOL → HTTP mapping
--------------------
======================================================  =======================
COBOL construct                                         HTTP equivalent
======================================================  =======================
``SEND MAP('COADM01') FROM(ADMIN-MENU-RECORD)``         ``GET /admin/menu``
``EVALUATE CDEMO-ADMIN-OPT-NUM``                        Client chooses option;
                                                        follows ``route`` in
                                                        the JSON payload
``XCTL PROGRAM('COUSR0nC')``                            Equivalent REST
                                                        endpoint under /users
``RECEIVE MAP('COADM01')``                              N/A (REST is
                                                        stateless)
======================================================  =======================

Design note — why these are meta endpoints
-------------------------------------------
The COBOL admin-menu screen is a **navigation** screen: it merely
lists the four admin operations (User List, User Add, User Update,
User Delete) and waits for the user to type an option number, at
which point the CICS ``XCTL`` transfers control to the chosen
program. In a REST architecture the "navigation" is client-side —
the client already knows the four endpoints. But the menu JSON is
still useful for:

* Self-describing clients (React / Vue / CLI) that render a menu
  programmatically from the server's payload;
* Preserving the functional parity with COADM01C (AAP §0.7.1
  "Preserve all existing functionality exactly as-is"); and
* Supporting future permission-gated menus (return only the options
  the current admin user is authorized to invoke).

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan
* :mod:`src.shared.constants.menu_options` — the single source of
  truth for admin menu rows (converted from COADM02Y.cpy).
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, status

from src.api.dependencies import CurrentUser, get_current_admin_user
from src.shared.config import get_settings
from src.shared.constants.menu_options import (
    ADMIN_MENU_OPT_COUNT,
    ADMIN_MENU_OPTIONS,
    PROGRAM_TO_API_ROUTE,
)

logger = logging.getLogger(__name__)

router: APIRouter = APIRouter()


@router.get(
    "/menu",
    status_code=status.HTTP_200_OK,
    summary="Admin menu — list of admin operations (F-003 COADM01C.cbl)",
    response_description=(
        "Array of admin menu options with option_num, option_name, "
        "legacy program_name, and the target REST route so clients "
        "can invoke the matching endpoint directly."
    ),
)
async def admin_menu(
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> dict[str, Any]:
    """Return the list of admin menu options.

    Equivalent of the COADM01C ``SEND MAP('COADM01')`` refresh. The
    server is the single source of truth for the menu contents;
    clients render the payload without hard-coding option labels.

    The returned payload includes the legacy ``program_name`` for
    traceability plus the ``route`` that the REST caller should hit
    when that option is selected. The ``route`` is looked up from
    :data:`PROGRAM_TO_API_ROUTE` — the same mapping used by
    ``graphql/queries.py`` for GraphQL menu queries.
    """
    logger.info(
        "GET /admin/menu initiated",
        extra={
            "admin_user": current_user.user_id,
            "endpoint": "admin_menu",
        },
    )
    options: list[dict[str, Any]] = [
        {
            "option_num": opt["option_num"],
            "option_name": opt["option_name"],
            "program_name": opt["program_name"],
            "route": PROGRAM_TO_API_ROUTE.get(opt["program_name"], ""),
        }
        for opt in ADMIN_MENU_OPTIONS
    ]
    return {
        "title": "Admin Menu",
        "option_count": ADMIN_MENU_OPT_COUNT,
        "options": options,
        "user_id": current_user.user_id,
    }


@router.get(
    "/status",
    status_code=status.HTTP_200_OK,
    summary="Admin-liveness probe (admin-only)",
    response_description=("Reports the admin-user identity, the current environment, and the server's UTC timestamp."),
)
async def admin_status(
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> dict[str, Any]:
    """Return the admin liveness / environment probe.

    Distinct from ``GET /health`` (which is public and DB-independent):
    this endpoint requires an admin JWT and reports admin-only
    context (environment, admin identity, build metadata). Useful for
    admin-dashboard landing pages and on-call runbooks.
    """
    settings = get_settings()
    logger.info(
        "GET /admin/status initiated",
        extra={
            "admin_user": current_user.user_id,
            "endpoint": "admin_status",
        },
    )
    return {
        "status": "ok",
        "admin_user": current_user.user_id,
        "user_type": current_user.user_type,
        "app_name": settings.APP_NAME,
        "app_version": settings.APP_VERSION,
        "debug": settings.DEBUG,
        "timestamp": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
    }


__all__ = ["router"]
