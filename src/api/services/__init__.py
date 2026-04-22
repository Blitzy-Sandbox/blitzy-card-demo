# ============================================================================
# Source: COBOL online CICS programs (app/cbl/CO*.cbl)
#         — Mainframe-to-Cloud migration
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
"""Business logic service layer. Converted from 18 online CICS COBOL programs.

Each service encapsulates PROCEDURE DIVISION logic from one or more COBOL
programs, using SQLAlchemy async ORM for data access (replacing VSAM
READ/WRITE/REWRITE/DELETE operations). Source: ``app/cbl/CO*.cbl`` —
Mainframe-to-Cloud migration.

Package Role
------------
This package is the business-logic layer of the CardDemo FastAPI
application. It sits between the transport-layer routers
(``src/api/routers/``) and the shared SQLAlchemy ORM models
(``src/shared/models/``). The dependency direction is strictly
one-way::

    src.api.routers  →  src.api.services  →  src.shared.models

Routers MUST NOT bypass the service layer to touch the models
directly, and services MUST NOT import from ``src.api.routers``.
Enforcing this contract preserves the separation of concerns that
mirrors the COBOL program structure: each CICS online program had a
WORKING-STORAGE + PROCEDURE-DIVISION section (business logic) and a
SEND/RECEIVE MAP section (transport). The service class is the
business logic; the router is the transport.

Service-to-COBOL Mapping (AAP §0.5.1)
-------------------------------------
Each service module in this package corresponds to one or more online
CICS COBOL programs from ``app/cbl/CO*.cbl``. The aggregation groups
related CRUD operations into a single domain-oriented class, matching
the Service Layer pattern from AAP §0.4.3.

=========================  =======================================  =================================================================
Service class              Originating COBOL program(s)             Feature(s) implemented
=========================  =======================================  =================================================================
:class:`AuthService`       ``app/cbl/COSGN00C.cbl``                 F-001 Sign-on / authentication
:class:`AccountService`    ``app/cbl/COACTVWC.cbl`` (view)          F-004 Account view (3-entity join:
                           ``app/cbl/COACTUPC.cbl`` (update)        Account + Customer + CardCrossReference)
                                                                    F-005 Account update (SYNCPOINT ROLLBACK dual-write,
                                                                          4,236-line original COBOL program)
:class:`CardService`       ``app/cbl/COCRDLIC.cbl`` (list)          F-006 Card list (7 rows/page)
                           ``app/cbl/COCRDSLC.cbl`` (detail)        F-007 Card detail view
                           ``app/cbl/COCRDUPC.cbl`` (update)        F-008 Card update (optimistic concurrency)
:class:`TransactionService` ``app/cbl/COTRN00C.cbl`` (list)         F-009 Transaction list (10 rows/page)
                           ``app/cbl/COTRN01C.cbl`` (detail)        F-010 Transaction detail view
                           ``app/cbl/COTRN02C.cbl`` (add)           F-011 Transaction add (auto-ID generation + xref resolution)
:class:`BillService`       ``app/cbl/COBIL00C.cbl``                 F-012 Bill payment (atomic dual-write: Transaction INSERT
                                                                          + Account balance UPDATE, 572-line original)
:class:`ReportService`     ``app/cbl/CORPT00C.cbl``                 F-022 Report submission (CICS TDQ WRITEQ JOBS →
                                                                          AWS SQS FIFO message publish, 649-line original)
:class:`UserService`       ``app/cbl/COUSR00C.cbl`` (list)          F-018 User list (admin-only)
                           ``app/cbl/COUSR01C.cbl`` (add)           F-019 User add (BCrypt password hashing)
                           ``app/cbl/COUSR02C.cbl`` (update)        F-020 User update
                           ``app/cbl/COUSR03C.cbl`` (delete)        F-021 User delete
=========================  =======================================  =================================================================

Design Patterns Applied (AAP §0.4.3)
------------------------------------
* **Service Layer** — Each service class encapsulates the business
  rules of its corresponding COBOL PROCEDURE DIVISION paragraphs.
  Routers are thin adapters that translate HTTP to service calls;
  services own the transaction boundaries and side-effect ordering.

* **Repository Pattern** — SQLAlchemy ORM models (injected via
  :class:`sqlalchemy.ext.asyncio.AsyncSession` in each service
  constructor) encapsulate all data access, replacing VSAM
  ``READ`` / ``WRITE`` / ``REWRITE`` / ``DELETE``.

* **Transactional Outbox** — SQLAlchemy session context managers
  with rollback-on-exception replace CICS ``SYNCPOINT ROLLBACK``
  semantics in F-005 (Account Update) and F-012 (Bill Payment).

* **Optimistic Concurrency** — SQLAlchemy ``version_id_col`` / @Version
  column replaces the CICS ``READ UPDATE`` / ``REWRITE`` collision
  pattern in F-008 (Card Update).

* **Stateless Authentication** — JWT tokens (``python-jose``) replace
  the CICS COMMAREA session (``app/cpy/COCOM01Y.cpy``) threading of
  user identity and user type between pseudo-conversational
  transactions. Implemented in :class:`AuthService`.

* **Dependency Injection** — Every service takes an
  :class:`~sqlalchemy.ext.asyncio.AsyncSession` in its constructor
  (except :class:`ReportService` which is AWS-SQS-only), wired by
  FastAPI's :func:`~fastapi.Depends` mechanism. Replaces CICS file
  handles to VSAM datasets.

Import Conventions
------------------
This package init eagerly imports all 7 service classes so that
consumers can use a concise package-level import::

    from src.api.services import (
        AuthService,
        AccountService,
        CardService,
        TransactionService,
        BillService,
        ReportService,
        UserService,
    )

rather than drilling into submodules::

    from src.api.services.auth_service import AuthService
    from src.api.services.account_service import AccountService
    # ...

Both styles are supported; the package-level form is preferred for
brevity in router modules, while the submodule form is retained for
situations where module-level symbols (e.g., ``AuthenticationError``
in ``auth_service.py`` or ``UserNotFoundError`` in ``user_service.py``)
must be imported alongside the service class.

Circular-import Safety
----------------------
* This module imports ONLY from its own submodules. No submodule
  imports anything back from ``src.api.services``, so no circular
  resolution occurs.
* Each service module imports from ``src.shared.models``,
  ``src.shared.schemas``, ``src.shared.utils``, and
  ``src.shared.config``. None of those packages import from
  ``src.api.services``.
* Routers at ``src/api/routers/*.py`` import from this package
  (``from src.api.services import ...``); the services never import
  from routers.

Eager-vs-lazy Loading
---------------------
The eager-import approach (each service class is imported at package
load time) is chosen deliberately:

* **Consistency with other API sub-packages** — ``src.api.routers``
  and ``src.shared.models`` use the same eager pattern.
* **FastAPI startup semantics** — Uvicorn loads ``src.api.main``
  once at worker start, which triggers a cascade of imports through
  the routers and then the services. Doing the imports here rather
  than lazily has zero runtime cost because the same modules would
  be imported a few milliseconds later anyway.
* **Test discoverability** — Pytest collection walks the test tree
  and imports modules under test; eager service-class availability
  simplifies test fixture wiring.
* **No heavy side effects** — Each service module's top-level code
  is limited to class / function / constant definitions. No database
  connections, AWS clients, or Spark contexts are constructed at
  import time; that work is deferred until an instance is
  constructed with an injected :class:`AsyncSession`.

Compliance
----------
* **Python 3.11+** — Aligned with the AWS Glue 5.1 Python runtime
  and the FastAPI / ECS Fargate deployment baseline
  (``python:3.11-slim``). See AAP §0.6.1.
* **Apache License 2.0** — Inherited from the original AWS CardDemo
  mainframe reference application and the COBOL source artifacts
  this package replaces.
* **No placeholders / TODOs** — Per AAP §0.7.1 (Zero Placeholder
  Policy), every service class is a complete, production-ready
  implementation of its corresponding COBOL program(s).

See Also
--------
* AAP §0.4.1 — Refactored Structure Planning (service layer spec).
* AAP §0.4.3 — Design Pattern Applications (Service Layer,
  Repository, Transactional Outbox, Optimistic Concurrency,
  Stateless Authentication, Dependency Injection).
* AAP §0.5.1 — File-by-File Transformation Plan (online CICS →
  service mapping).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve existing
  functionality, minimal change clause, document technology-specific
  changes with clear comments).
* AAP §0.7.2 — Financial Precision, Security, Monitoring, Testing
  requirements.
* :mod:`src.api.routers` — Thin transport adapters that delegate to
  these services.
* :mod:`src.shared.models` — SQLAlchemy 2.x ORM entity models that
  services operate on.
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Service mapping from COBOL CICS programs:
# AuthService        ← COSGN00C.cbl (Sign-on, F-001)
# AccountService     ← COACTVWC.cbl (View, F-004) + COACTUPC.cbl (Update, F-005)
# CardService        ← COCRDLIC.cbl (List, F-006) + COCRDSLC.cbl (Detail, F-007)
#                      + COCRDUPC.cbl (Update, F-008)
# TransactionService ← COTRN00C.cbl (List, F-009) + COTRN01C.cbl (Detail, F-010)
#                      + COTRN02C.cbl (Add, F-011)
# BillService        ← COBIL00C.cbl (Bill Payment, F-012)
# ReportService      ← CORPT00C.cbl (Report Submission, F-022)
# UserService        ← COUSR00C-03C.cbl (User CRUD, F-018 through F-021)
# ----------------------------------------------------------------------------
# Imports below are listed alphabetically by submodule name to satisfy the
# isort / ruff I001 convention used consistently across the CardDemo
# codebase (see ``src/api/routers/__init__.py``,
# ``src/shared/models/__init__.py``). The ``__all__`` list at the bottom
# is ordered by COBOL program / feature to preserve the AAP §0.5.1
# service-to-COBOL mapping as a human-readable specification.
# ----------------------------------------------------------------------------
# AccountService — Account view and update service class converted from
# app/cbl/COACTVWC.cbl (3-entity join: Account + Customer + CardCrossReference)
# and app/cbl/COACTUPC.cbl (4,236 lines, dual-write with SYNCPOINT ROLLBACK
# semantics). Public methods: get_account_view(), update_account().
from src.api.services.account_service import AccountService

# AuthService — Authentication service class converted from
# app/cbl/COSGN00C.cbl (CICS transaction CC00). Provides BCrypt password
# verification and JWT token generation, replacing CICS COMMAREA session
# state. Public methods: authenticate(), verify_password(), hash_password(),
# verify_token().
from src.api.services.auth_service import AuthService

# BillService — Bill payment service class converted from app/cbl/COBIL00C.cbl
# (572-line original). Implements atomic dual-write (Transaction INSERT +
# Account balance UPDATE) with SYNCPOINT ROLLBACK semantics preserved via
# SQLAlchemy transactional context manager. Public method: pay_bill().
from src.api.services.bill_service import BillService

# CardService — Card list, detail, and update service class converted from
# app/cbl/COCRDLIC.cbl (paginated list, 7 rows/page), app/cbl/COCRDSLC.cbl
# (single-card detail), and app/cbl/COCRDUPC.cbl (update with optimistic
# concurrency via @Version column). Public methods: list_cards(),
# list_cards_forward(), list_cards_backward(), get_card_detail(),
# update_card().
from src.api.services.card_service import CardService

# ReportService — Report submission service class converted from
# app/cbl/CORPT00C.cbl (649-line original). Replaces CICS TDQ WRITEQ to
# the JOBS extrapartition queue with an AWS SQS FIFO message publish.
# Public method: submit_report().
from src.api.services.report_service import ReportService

# TransactionService — Transaction list, detail, and add service class
# converted from app/cbl/COTRN00C.cbl (paginated list, 10 rows/page),
# app/cbl/COTRN01C.cbl (single-transaction detail), and app/cbl/COTRN02C.cbl
# (transaction add with auto-ID generation and card cross-reference
# resolution). Public methods: list_transactions(), get_transaction_detail(),
# add_transaction().
from src.api.services.transaction_service import TransactionService

# UserService — User CRUD service class converted from app/cbl/COUSR00C.cbl
# (list, F-018), app/cbl/COUSR01C.cbl (add with BCrypt hashing, F-019),
# app/cbl/COUSR02C.cbl (update, F-020), and app/cbl/COUSR03C.cbl (delete,
# F-021). Admin-only operations. Public methods: list_users(), create_user(),
# update_user(), delete_user().
from src.api.services.user_service import UserService

# ----------------------------------------------------------------------------
# Public re-export list.
#
# Exactly 7 entries — one per service class (AAP §0.5.1 service-to-COBOL
# mapping). This list controls what ``from src.api.services import *``
# resolves to and is introspected by IDEs, linters, and documentation
# generators (Sphinx autodoc, pdoc, mkdocs-python-api) to determine the
# package's public surface.
#
# Consumers that need module-level non-class symbols (e.g.,
# ``AuthenticationError`` / ``InvalidTokenError`` in ``auth_service``,
# ``UserServiceError`` / ``UserNotFoundError`` in ``user_service``, or
# COBOL-exact error-message constants) must import them directly from
# the respective submodule:
#
#     from src.api.services.auth_service import AuthenticationError
#     from src.api.services.user_service import UserNotFoundError
#
# Keeping this list focused on the 7 service classes enforces the AAP
# contract that each service is the public-facing entry point for its
# originating CICS COBOL program(s).
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "AuthService",
    "AccountService",
    "CardService",
    "TransactionService",
    "BillService",
    "ReportService",
    "UserService",
]
