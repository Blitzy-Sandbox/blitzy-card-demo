# ============================================================================
# Source: app/cbl/COADM01C.cbl  (Admin menu, CICS transaction CA00, F-003)
#         + app/cpy/COADM02Y.cpy    (Admin menu options table)
#         + app/cpy/COCOM01Y.cpy    (CARDDEMO-COMMAREA — CDEMO-USER-TYPE)
#         + app/cpy-bms/COADM01.CPY (BMS symbolic map — OPTIONI/ERRMSGI)
#         -> Mainframe-to-Cloud migration (AAP Sec 0.5.1)
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
"""Admin menu router.

Converted from ``app/cbl/COADM01C.cbl`` (CICS transaction ``CA00``,
Feature F-003). Provides admin-only endpoints requiring
``user_type == 'A'`` (from ``app/cpy/COCOM01Y.cpy`` 88-level
``CDEMO-USRTYP-ADMIN``). The original 4-option ``XCTL`` dispatch to
``COUSR00C``/``COUSR01C``/``COUSR02C``/``COUSR03C`` is handled by the
user_router; this router provides admin metadata and navigation.

Mainframe-to-Cloud Mapping
--------------------------
The legacy ``COADM01C.cbl`` program is a pseudo-conversational CICS
program that renders the admin menu (BMS mapset ``COADM01``) and
dispatches to one of four user-management programs via
``EXEC CICS XCTL PROGRAM('COUSR0nC')``. The transformation pattern
applied here is:

============================================================  ====================================
COBOL / CICS construct                                        FastAPI / HTTP equivalent
============================================================  ====================================
``EXEC CICS RETURN TRANSID('CA00') COMMAREA(...)``            Stateless REST (no TRANSID)
``IF CDEMO-USRTYP-ADMIN ...``  (88-level VALUE 'A')           :func:`get_current_admin_user`
                                                              dependency -> HTTP 403 when false
``SEND MAP('COADM1A') MAPSET('COADM01')``                     ``GET /admin/menu`` JSON response
``RECEIVE MAP('COADM1A')`` + ``EVALUATE WS-OPTION``           Client selects option; calls the
                                                              ``endpoint``+``method`` from payload
``EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(IDX))``      Client invokes REST endpoint under
                                                              ``/users`` (user_router.py)
``PERFORM POPULATE-HEADER-INFO`` (title/date/time fields)     Embedded in ``menu_title`` + JSON
                                                              payload (clients render their own
                                                              chrome)
``ERRMSGO OF COADM1AO`` (error message display)               ``HTTPException`` with detail text
                                                              (via global exception handler)
============================================================  ====================================

The 4 admin menu rows originate from ``app/cpy/COADM02Y.cpy`` where
``CDEMO-ADMIN-OPT-COUNT = 4`` and each ``CDEMO-ADMIN-OPT`` occurrence
carries a numeric option, a 35-character label, and an 8-character
COBOL program name. In this cloud-native replacement we drop the
legacy program names from the payload (they are implementation
details of the retired CICS architecture) and surface the equivalent
REST route/method pair instead so that self-describing clients can
invoke the target endpoint directly.

Endpoint Summary
----------------
``GET /admin/menu``   -> Admin-menu option list (replaces
                         ``SEND MAP('COADM1A')`` in COADM01C).
``GET /admin/status`` -> Admin-only liveness probe (cloud-native
                         addition; the equivalent of a CICS "enter
                         the admin region" landing screen).

Security
--------
Every endpoint in this module depends on
:func:`src.api.dependencies.get_current_admin_user`, which:

1. Decodes and validates the JWT bearer token (raises 401 on
   failure -- corresponds to CICS refusing the transaction when
   COMMAREA is missing or malformed).
2. Enforces the ``CDEMO-USRTYP-ADMIN VALUE 'A'`` 88-level condition
   by raising HTTP 403 Forbidden when ``current_user.is_admin`` is
   ``False`` (the JWT's ``user_type`` claim was ``'U'``).

Non-admin users therefore receive 403 Forbidden from every route in
this module, matching the COADM01C behavior of never dispatching to
admin-only programs for regular users.

See Also
--------
* AAP Sec 0.5.1 -- File-by-File Transformation Plan (admin_router row)
* AAP Sec 0.7.1 -- Preserve all existing functionality exactly as-is
* :mod:`src.api.dependencies` -- :func:`get_current_admin_user`,
  :class:`CurrentUser` (JWT-based replacement for CICS COMMAREA)
* :mod:`src.api.routers.user_router` -- implements the 4 CRUD endpoints
  that COADM01C's 4 ``XCTL`` targets (``COUSR00C``/``COUSR01C``/
  ``COUSR02C``/``COUSR03C``) correspond to in the target architecture
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends

from src.api.dependencies import CurrentUser, get_current_admin_user

# ----------------------------------------------------------------------------
# Module logger.
#
# Structured records flow to CloudWatch Logs via the ECS awslogs driver
# (AAP Sec 0.7.2 -- Monitoring Requirements). Filter by
# ``logger_name = "src.api.routers.admin_router"`` in Logs Insights to
# isolate admin menu / admin status audit events. The ``extra`` kwargs
# passed to each ``logger.info(...)`` call below are indexed
# individually by CloudWatch, so admin-specific dashboards can filter
# on ``user_id`` and ``endpoint`` fields without parsing the message.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# Admin router instance.
#
# Replaces CICS transaction CA00 (COADM01C admin menu for admin users).
#
# NOTE: No prefix here. The mount-site ``src/api/main.py`` applies
# ``prefix="/admin"`` via ``app.include_router(admin_router.router,
# prefix="/admin", ...)`` so this module remains mount-path agnostic --
# useful for tests that mount the router directly without a prefix.
# ----------------------------------------------------------------------------
router: APIRouter = APIRouter()


# ============================================================================
# GET /admin/menu -- Admin menu options
# ============================================================================
@router.get("/menu", summary="Admin Menu Options")
async def get_admin_menu(
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> dict[str, Any]:
    """Return the 4-option admin menu as JSON.

    Replaces the COADM01C ``SEND MAP('COADM1A') MAPSET('COADM01')``
    refresh loop. The original program built 12 ``OPTNnnnO`` output
    fields by iterating the ``CDEMO-ADMIN-OPTIONS`` table
    (``app/cpy/COADM02Y.cpy`` lines 22-48) and calling
    ``EXEC CICS SEND MAP``; this endpoint performs the same
    responsibility in REST form.

    The admin menu option table (``app/cpy/COADM02Y.cpy``) is
    preserved exactly with 4 entries corresponding to the 4 user-
    management CICS programs:

    ====== ================== ==================== ========
    Option Label              Endpoint             Method
    ====== ================== ==================== ========
    1      User List          /users               GET
    2      User Add           /users               POST
    3      User Update        /users/{user_id}     PUT
    4      User Delete        /users/{user_id}     DELETE
    ====== ================== ==================== ========

    In the cloud-native architecture, option selection is fully
    client-side: the caller reads the ``options`` array, picks an
    entry, and issues the declared ``method`` against ``endpoint``.
    There is no server-side ``EVALUATE`` (COADM01C
    ``PROCESS-ENTER-KEY``) because the server does not retain
    conversational state between requests.

    Parameters
    ----------
    current_user : :class:`CurrentUser`
        Authenticated admin user injected by
        :func:`get_current_admin_user`. The dependency has already
        validated the JWT and enforced the ``user_type == 'A'`` gate
        -- non-admin callers never reach this function body (403
        Forbidden is raised upstream). Captured in the audit log
        below so CloudWatch Logs Insights can trace admin-menu
        access per user.

    Returns
    -------
    dict[str, Any]
        JSON payload with the admin menu title and the 4-option
        navigation list. Shape::

            {
              "menu_title": "Administrative Menu",
              "options": [
                {"option": 1, "label": "User List",
                 "endpoint": "/users",           "method": "GET"},
                {"option": 2, "label": "User Add",
                 "endpoint": "/users",           "method": "POST"},
                {"option": 3, "label": "User Update",
                 "endpoint": "/users/{user_id}", "method": "PUT"},
                {"option": 4, "label": "User Delete",
                 "endpoint": "/users/{user_id}", "method": "DELETE"}
              ]
            }

    Raises
    ------
    fastapi.HTTPException
        Status 401 (propagated from :func:`get_current_admin_user`
        via :func:`src.api.dependencies.get_current_user`) if the
        JWT is missing, malformed, or expired.
    fastapi.HTTPException
        Status 403 (raised by :func:`get_current_admin_user`) if the
        authenticated user's ``user_type`` claim is ``'U'`` (regular
        user) instead of ``'A'`` (admin).
    """
    # ------------------------------------------------------------------
    # Audit the admin menu access. Using ``extra`` (not f-string
    # interpolation) so the fields are indexed individually by
    # CloudWatch Logs Insights for precise admin-behavior analytics.
    # Sensitive values (JWT, password) are NEVER written -- only the
    # identity and endpoint marker.
    # ------------------------------------------------------------------
    logger.info(
        "GET /admin/menu accessed",
        extra={
            "user_id": current_user.user_id,
            "endpoint": "/admin/menu",
            "cobol_source": "COADM01C.cbl",
            "feature": "F-003",
        },
    )

    # ------------------------------------------------------------------
    # Build the 4-entry options list. The content is a faithful
    # translation of ``app/cpy/COADM02Y.cpy`` rows 24-42 (the 4
    # populated entries of the ``CDEMO-ADMIN-OPTIONS-DATA`` table).
    # Each entry carries the legacy option number as declared in the
    # COBOL ``PIC 9(02) VALUE n.`` literal, the human-readable label
    # (trimmed of the trailing space padding the COBOL ``PIC X(35)``
    # field imposes), and the equivalent REST endpoint/method pair
    # replacing the legacy ``PIC X(08)`` program name.
    # ------------------------------------------------------------------
    options: list[dict[str, Any]] = [
        # Option 1 -> COADM02Y.cpy lines 24-27 (was XCTL to COUSR00C,
        # COBOL label "User List (Security)")
        {
            "option": 1,
            "label": "User List",
            "endpoint": "/users",
            "method": "GET",
        },
        # Option 2 -> COADM02Y.cpy lines 29-32 (was XCTL to COUSR01C,
        # COBOL label "User Add (Security)")
        {
            "option": 2,
            "label": "User Add",
            "endpoint": "/users",
            "method": "POST",
        },
        # Option 3 -> COADM02Y.cpy lines 34-37 (was XCTL to COUSR02C,
        # COBOL label "User Update (Security)")
        {
            "option": 3,
            "label": "User Update",
            "endpoint": "/users/{user_id}",
            "method": "PUT",
        },
        # Option 4 -> COADM02Y.cpy lines 39-42 (was XCTL to COUSR03C,
        # COBOL label "User Delete (Security)")
        {
            "option": 4,
            "label": "User Delete",
            "endpoint": "/users/{user_id}",
            "method": "DELETE",
        },
    ]

    # The top-level "menu_title" preserves the COBOL title string the
    # terminal user would have seen on the BMS map -- the
    # ``CCDA-TITLE01`` / ``CCDA-TITLE02`` fields populated by
    # ``POPULATE-HEADER-INFO`` in COADM01C lines 202-221. We omit the
    # run-time date/time header fields because they are now rendered
    # client-side (the ``CURDATEO`` / ``CURTIMEO`` values were terminal
    # chrome, not business data).
    return {
        "menu_title": "Administrative Menu",
        "options": options,
    }


# ============================================================================
# GET /admin/status -- Admin system status
# ============================================================================
@router.get("/status", summary="Admin System Status")
async def get_admin_status(
    current_user: CurrentUser = Depends(get_current_admin_user),
) -> dict[str, Any]:
    """Return the admin system status envelope.

    Cloud-native addition -- there is no direct COBOL equivalent. In
    the legacy architecture the admin "system oversight" information
    (file open/close state, CICS region health) was surfaced via
    separate operations utilities (``CEMT``, ``CICS`` system
    transactions) that are not part of CardDemo. Here we expose a
    minimal admin-only liveness probe that:

    * Confirms the admin JWT is valid (via
      :func:`get_current_admin_user`).
    * Returns the authenticated admin's user_id so dashboards and
      on-call runbooks can verify the signed-in identity at a glance.
    * Distinguishes from ``GET /health`` (public, DB-independent) by
      requiring admin privileges.

    Parameters
    ----------
    current_user : :class:`CurrentUser`
        Authenticated admin user injected by
        :func:`get_current_admin_user`. Only the ``user_id`` is
        reflected in the response; ``user_type`` and ``is_admin`` are
        implicit (both are always ``'A'`` / ``True`` here).

    Returns
    -------
    dict[str, Any]
        JSON payload with two keys::

            {
              "status": "operational",
              "user":   "<admin user_id>"
            }

        The ``status`` value is a fixed literal for forward
        compatibility -- additional operational fields (database
        connectivity, worker queue depth, etc.) may be added in a
        future revision without breaking existing clients.

    Raises
    ------
    fastapi.HTTPException
        Status 401 or 403, as described on
        :func:`get_admin_menu`.
    """
    # Audit the admin status check. Same structured-logging approach
    # as /admin/menu so the two admin endpoints produce comparable
    # CloudWatch Logs Insights queries.
    logger.info(
        "GET /admin/status accessed",
        extra={
            "user_id": current_user.user_id,
            "endpoint": "/admin/status",
            "cobol_source": "COADM01C.cbl",
            "feature": "F-003",
        },
    )
    return {
        "status": "operational",
        "user": current_user.user_id,
    }


# ----------------------------------------------------------------------------
# Public API -- the single ``router`` attribute consumed by
# :mod:`src.api.main` via ``app.include_router(admin_router.router,
# prefix="/admin", ...)`` and re-exported by
# :mod:`src.api.routers.__init__`. Keeping ``__all__`` explicit guards
# against accidental inclusion of private helpers in future revisions.
# ----------------------------------------------------------------------------
__all__ = ["router"]
