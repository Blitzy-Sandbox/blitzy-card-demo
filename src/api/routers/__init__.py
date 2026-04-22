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
"""FastAPI REST router layer. Converted from 18 online CICS COBOL programs.

Each router module replaces one or more CICS transaction programs with REST
endpoints. Source: ``app/cbl/CO*.cbl`` — Mainframe-to-Cloud migration (AAP
§0.5.1 ``src/api/routers/*.py`` rows).

This package initializer re-exports the eight ``fastapi.APIRouter`` instances
produced by the underlying router modules so that :mod:`src.api.main` can
register them with a single flat ``from src.api.routers import ...`` import
(no need to reach into each submodule's ``router`` attribute).

Router mapping from COBOL CICS programs (COMEN02Y + COADM02Y menu tables)
========================================================================

Main Menu (``app/cpy/COMEN02Y.cpy`` — 10 options, all ``USRTYPE='U'``):

* Option  1 ``COACTVWC`` → ``account_router``     (F-004 Account view)
* Option  2 ``COACTUPC`` → ``account_router``     (F-005 Account update)
* Option  3 ``COCRDLIC`` → ``card_router``        (F-006 Card list, 7/page)
* Option  4 ``COCRDSLC`` → ``card_router``        (F-007 Card detail)
* Option  5 ``COCRDUPC`` → ``card_router``        (F-008 Card update)
* Option  6 ``COTRN00C`` → ``transaction_router`` (F-009 Transaction list)
* Option  7 ``COTRN01C`` → ``transaction_router`` (F-010 Transaction detail)
* Option  8 ``COTRN02C`` → ``transaction_router`` (F-011 Transaction add)
* Option  9 ``CORPT00C`` → ``report_router``      (F-022 Report submission)
* Option 10 ``COBIL00C`` → ``bill_router``        (F-012 Bill payment)

Admin Menu (``app/cpy/COADM02Y.cpy`` — 4 options, requires user_type='A'):

* Option 1 ``COUSR00C`` → ``user_router``  (F-018 User list)
* Option 2 ``COUSR01C`` → ``user_router``  (F-019 User add, BCrypt)
* Option 3 ``COUSR02C`` → ``user_router``  (F-020 User update)
* Option 4 ``COUSR03C`` → ``user_router``  (F-021 User delete)

Plus the sign-on program ``COSGN00C`` (F-001) → ``auth_router`` and the admin
menu program ``COADM01C`` (F-003) → ``admin_router`` — these are reachable
outside the two option tables.

Legend
------
+----------------------+--------------------------------------------------+
| Re-exported symbol   | Underlying module (``router`` attribute source)   |
+======================+==================================================+
| ``auth_router``      | :mod:`.auth_router`                               |
| ``account_router``   | :mod:`.account_router`                            |
| ``card_router``      | :mod:`.card_router`                               |
| ``transaction_router`` | :mod:`.transaction_router`                      |
| ``bill_router``      | :mod:`.bill_router`                               |
| ``report_router``    | :mod:`.report_router`                             |
| ``user_router``      | :mod:`.user_router`                               |
| ``admin_router``     | :mod:`.admin_router`                              |
+----------------------+--------------------------------------------------+

Usage (from :mod:`src.api.main`)
--------------------------------
.. code-block:: python

    from src.api.routers import (
        account_router,
        admin_router,
        auth_router,
        bill_router,
        card_router,
        report_router,
        transaction_router,
        user_router,
    )

    app.include_router(auth_router, prefix="/auth", tags=["Authentication"])
    app.include_router(account_router, prefix="/accounts", tags=["Accounts"])
    # ...

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (each router's row)
* :mod:`src.api.main` — mounts every router with an appropriate prefix
* :mod:`src.api.services` — the business-logic layer the routers delegate to
"""

# ---------------------------------------------------------------------------
# Router re-exports.
#
# Each submodule defines a module-level ``router`` of type
# ``fastapi.APIRouter`` (see per-module schemas). We rename each one to a
# feature-area-qualified alias so that callers do not need to reach into
# ``.router`` on every import site and so that the eight aliases are safe to
# pass directly to ``app.include_router(...)`` in :mod:`src.api.main`.
#
# IMPORTANT: The alias name is identical to the submodule name, which
# deliberately shadows the submodule attribute on the package object. That is
# the desired behaviour: after ``from src.api.routers import auth_router`` the
# caller receives the ``APIRouter`` instance, not the Python module.
#
# Source COBOL mapping (see module docstring for the full table):
#   auth_router        ← COSGN00C.cbl                      (F-001)
#   account_router     ← COACTVWC.cbl + COACTUPC.cbl       (F-004, F-005)
#   card_router        ← COCRDLIC.cbl + COCRDSLC.cbl
#                        + COCRDUPC.cbl                    (F-006, F-007, F-008)
#   transaction_router ← COTRN00C.cbl + COTRN01C.cbl
#                        + COTRN02C.cbl                    (F-009, F-010, F-011)
#   bill_router        ← COBIL00C.cbl                      (F-012)
#   report_router      ← CORPT00C.cbl                      (F-022)
#   user_router        ← COUSR00C-COUSR03C.cbl             (F-018, F-019,
#                                                           F-020, F-021)
#   admin_router       ← COADM01C.cbl                      (F-003)
# ---------------------------------------------------------------------------

from __future__ import annotations

from src.api.routers.account_router import router as account_router
from src.api.routers.admin_router import router as admin_router
from src.api.routers.auth_router import router as auth_router
from src.api.routers.bill_router import router as bill_router
from src.api.routers.card_router import router as card_router
from src.api.routers.report_router import router as report_router
from src.api.routers.transaction_router import router as transaction_router
from src.api.routers.user_router import router as user_router

# Public re-export surface. Keeping ``__all__`` explicit (rather than relying
# on the ``from ... import`` bindings above) makes the contract with
# :mod:`src.api.main` and the AAP schema unambiguous: exactly eight router
# instances are exposed, one per COBOL feature area.
__all__ = [
    "account_router",
    "admin_router",
    "auth_router",
    "bill_router",
    "card_router",
    "report_router",
    "transaction_router",
    "user_router",
]
