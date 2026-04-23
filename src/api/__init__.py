# ============================================================================
# Source: Online CICS COBOL programs (app/cbl/CO*.cbl, app/cbl/CS*.cbl)
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
"""CardDemo REST/GraphQL API layer.

Converted from 18 online CICS COBOL programs to FastAPI endpoints
deployed on AWS ECS Fargate. Source: ``app/cbl/CO*.cbl``,
``app/cbl/CS*.cbl`` — Mainframe-to-Cloud migration.

The API package replaces the online CICS transaction environment of the
original z/OS CardDemo application with a stateless, containerized
Python 3.11 web service. The module that previously served as the
navigation entry point — ``app/cbl/COMEN01C.cbl`` (Main Menu for regular
users) — is functionally subsumed by the ``src.api.main`` FastAPI
application instance plus the router aggregation it performs; the
``src.api`` package itself exists only as the explicit package marker
(PEP 328) under which every router, service, middleware, and GraphQL
type for the cloud-native API is organized.

CICS-to-FastAPI Program Mapping (AAP §0.5.1)
--------------------------------------------
The 18 online CICS COBOL programs map to FastAPI routers/services as
follows:

* ``COSGN00C``   → ``auth_router``    / ``auth_service``    — Sign-on / JWT login.
* ``COMEN01C``   → ``main``           (FastAPI app entry)   — Main menu / navigation.
* ``COADM01C``   → ``admin_router``   / admin service       — Admin menu.
* ``COACTVWC``   → ``account_router`` / ``account_service`` — Account view (3-entity join).
* ``COACTUPC``   → ``account_router`` / ``account_service`` — Account update (SYNCPOINT ROLLBACK).
* ``COCRDLIC``   → ``card_router``    / ``card_service``    — Card list (7 rows/page).
* ``COCRDSLC``   → ``card_router``    / ``card_service``    — Card detail.
* ``COCRDUPC``   → ``card_router``    / ``card_service``    — Card update (optimistic concurrency).
* ``COTRN00C``   → ``transaction_router`` / ``transaction_service`` — Transaction list (10 rows/page).
* ``COTRN01C``   → ``transaction_router`` / ``transaction_service`` — Transaction detail.
* ``COTRN02C``   → ``transaction_router`` / ``transaction_service`` — Transaction add.
* ``COBIL00C``   → ``bill_router``    / ``bill_service``    — Bill payment (dual-write).
* ``CORPT00C``   → ``report_router``  / ``report_service``  — Report submission (SQS FIFO).
* ``COUSR00C``   → ``user_router``    / ``user_service``    — User list.
* ``COUSR01C``   → ``user_router``    / ``user_service``    — User add (BCrypt).
* ``COUSR02C``   → ``user_router``    / ``user_service``    — User update.
* ``COUSR03C``   → ``user_router``    / ``user_service``    — User delete.
* ``CSUTLDTC``   → ``src.shared.utils.date_utils``          — Date validation (shared).

Subpackages
-----------
middleware
    Cross-cutting request/response handlers: JWT authentication
    (``auth.py`` — replaces CICS COMMAREA session state) and the global
    exception handler (``error_handler.py``) that normalizes COBOL-era
    error codes from ``app/cpy/CSMSG01Y.cpy`` / ``CSMSG02Y.cpy``.

routers
    FastAPI ``APIRouter`` modules exposing REST endpoints — one router
    per COBOL feature area (auth, account, card, transaction, bill,
    report, user, admin). Each router delegates business logic to a
    corresponding service in ``src.api.services``.

services
    Business-logic layer encapsulating the PROCEDURE DIVISION paragraphs
    of the original COBOL programs. Services perform database I/O via
    ``src.shared.models`` (SQLAlchemy ORM) and AWS service I/O via
    ``src.shared.config.aws_config`` (boto3 clients). Transactional
    integrity — including the ``CICS SYNCPOINT ROLLBACK`` semantics of
    Account Update (F-005) and the dual-write of Bill Payment (F-012) —
    is enforced via SQLAlchemy session context managers.

graphql
    Strawberry-GraphQL types, queries, and mutations exposed alongside
    REST under the same FastAPI application. Shares the same service
    layer so GraphQL and REST operations are behaviorally identical.

Runtime Architecture
--------------------
The ``src.api`` package is packaged as a Docker container (see
``Dockerfile`` at the repository root) running Uvicorn as the ASGI
server and FastAPI 0.115.x as the web framework. The container is
deployed on AWS ECS Fargate behind an Application Load Balancer, with
Aurora PostgreSQL as the single relational persistence layer (via
SQLAlchemy 2.x async ORM + ``asyncpg`` driver) and AWS Secrets Manager
for credential retrieval. Stateless session management is implemented
via JWT tokens (``python-jose`` + HS256), replacing the CICS COMMAREA
session context propagated by the original online programs.

Design Notes
------------
* **No eager imports**: This package module performs NO imports of
  submodules. Consumers must import what they need explicitly
  (e.g., ``from src.api.main import app``,
  ``from src.api.routers.auth_router import router``). Eagerly
  importing the submodules from ``__init__.py`` would pull the entire
  FastAPI/SQLAlchemy/Strawberry dependency graph on any
  ``import src.api`` call — an antipattern that adversely affects test
  collection time and creates circular-import risk between routers,
  services, and GraphQL type modules.
* **Package marker only**: This ``__init__.py`` contains no executable
  logic beyond declaring ``__version__``. It exists solely to establish
  ``src.api`` as an explicit package (PEP 328) so that imports of the
  form ``from src.api.routers.account_router import router`` resolve
  unambiguously under every execution context: local ``uvicorn`` runs,
  ``pytest`` test collection, Docker container execution, and ECS
  Fargate task startup.
* **Stateless sessions**: JWT tokens replace the CICS COMMAREA session
  context (``app/cpy/COCOM01Y.cpy``). No server-side session affinity
  is required, enabling horizontal scaling via ECS service auto-scaling.
* **Financial precision**: All monetary arithmetic uses
  ``decimal.Decimal`` with ``ROUND_HALF_EVEN`` (banker's rounding) to
  preserve the COBOL ``PIC S9(n)V99`` ``ROUNDED`` semantics of the
  original programs. This discipline is enforced throughout
  ``src.shared.utils.decimal_utils`` and is consumed by every service
  module that performs monetary calculations.
* **Python 3.11+**: The API layer targets Python 3.11 exactly —
  aligning with the Python version installed into the ECS Fargate
  container image (``python:3.11-slim`` base) and with the shared
  ``src.shared`` and ``src.batch`` packages.
* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic exactly)
AAP §0.7.2 — Security Requirements (JWT, BCrypt, Secrets Manager, IAM)
"""

# ----------------------------------------------------------------------------
# Package version.
#
# Distinct from the distribution version declared in ``pyproject.toml``
# (``carddemo``). This ``__version__`` tracks the contract compatibility
# of the API layer — REST endpoint paths, request/response schemas, and
# GraphQL schema — so that downstream consumers (ECS task definitions,
# API client SDKs, CloudWatch synthetic monitors, integration tests) can
# detect breaking changes.
#
# Semantic versioning: MAJOR.MINOR.PATCH
#   MAJOR — Breaking changes to REST endpoint paths, request/response
#           schemas, authentication flow, or GraphQL schema contracts.
#   MINOR — Backward-compatible additions (new endpoints, new optional
#           request fields, new response fields, new GraphQL types).
#   PATCH — Bug fixes with no external contract changes.
#
# This version MUST remain synchronized with ``src.shared.__version__``
# and ``src.batch.__version__`` at major/minor boundaries to guarantee
# that the three packages always present a consistent cross-cutting
# contract to operators and clients.
# ----------------------------------------------------------------------------
__version__: str = "1.0.0"

# Explicit re-export list — only ``__version__`` is considered part of
# the public API of this package module. All other symbols must be
# imported from their specific submodules (e.g.,
# ``from src.api.main import app``,
# ``from src.api.routers.account_router import router``,
# ``from src.api.services.auth_service import AuthService``).
#
# ``from src.api import *`` imports only ``__version__``.
__all__ = ["__version__"]
