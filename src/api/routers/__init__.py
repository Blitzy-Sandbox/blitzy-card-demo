# ============================================================================
# Source: app/cbl/CO*.cbl  (18 online CICS programs) — Mainframe-to-Cloud
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
"""FastAPI routers package — one module per CardDemo feature area.

This package aggregates the 8 router modules that expose the CardDemo
REST API surface. Each router is a thin transport-layer translator
between HTTP verbs/JSON and the business services in
``src/api/services/``; the service layer is where the COBOL
PROCEDURE-DIVISION semantics live.

Module → Source mapping (AAP §0.5.1)
------------------------------------
============================  ==============================================
Module                         Original COBOL program(s)
============================  ==============================================
:mod:`.auth_router`            ``app/cbl/COSGN00C.cbl`` (F-001 Sign-on)
:mod:`.account_router`         ``app/cbl/COACTVWC.cbl`` (F-004 Account view)
                               ``app/cbl/COACTUPC.cbl`` (F-005 Account update)
:mod:`.card_router`            ``app/cbl/COCRDLIC.cbl`` (F-006 Card list)
                               ``app/cbl/COCRDSLC.cbl`` (F-007 Card detail)
                               ``app/cbl/COCRDUPC.cbl`` (F-008 Card update)
:mod:`.transaction_router`     ``app/cbl/COTRN00C.cbl`` (F-009 Trans list)
                               ``app/cbl/COTRN01C.cbl`` (F-010 Trans detail)
                               ``app/cbl/COTRN02C.cbl`` (F-011 Trans add)
:mod:`.bill_router`            ``app/cbl/COBIL00C.cbl`` (F-012 Bill payment)
:mod:`.report_router`          ``app/cbl/CORPT00C.cbl`` (F-022 Reports)
:mod:`.user_router`            ``app/cbl/COUSR00C.cbl`` (F-018 User list)
                               ``app/cbl/COUSR01C.cbl`` (F-019 User add)
                               ``app/cbl/COUSR02C.cbl`` (F-020 User update)
                               ``app/cbl/COUSR03C.cbl`` (F-021 User delete)
:mod:`.admin_router`           ``app/cbl/COADM01C.cbl`` (F-003 Admin menu)
============================  ==============================================

Each module exposes a module-level attribute named ``router`` (an
:class:`fastapi.APIRouter` instance) which is imported and mounted by
:mod:`src.api.main` via ``app.include_router(...)``.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (each router's row)
* :mod:`src.api.main` — mounts every router with an appropriate prefix
* :mod:`src.api.services` — the business-logic layer the routers delegate to
"""

from __future__ import annotations

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
