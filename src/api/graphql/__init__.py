# ============================================================================
# Source: Online CICS COBOL programs (app/cbl/CO*.cbl) — primary context:
#         app/cbl/COMEN01C.cbl (Main Menu dispatch entry point)
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
"""CardDemo GraphQL API layer (Strawberry).

Provides GraphQL query and mutation resolvers alongside the REST API,
mounted at the ``/graphql`` endpoint in the FastAPI application
(``src.api.main``). This package exposes an alternative — but behaviorally
equivalent — dispatch surface to the CardDemo cloud-native API, sharing
the same SQLAlchemy service layer, the same JWT authentication middleware,
and the same Aurora PostgreSQL persistence backend as the REST routers in
``src.api.routers``.

Converted from 18 online CICS COBOL programs (``app/cbl/CO*.cbl``).
Query resolvers map CICS ``READ`` / ``STARTBR`` / ``READNEXT`` patterns;
mutation resolvers map CICS ``WRITE`` / ``REWRITE`` / ``DELETE`` patterns.

Source: ``app/cbl/CO*.cbl``, ``app/cpy/*.cpy``, ``app/cpy-bms/*.CPY``
— Mainframe-to-Cloud migration.

COBOL-to-GraphQL Dispatch Mapping
---------------------------------
The original CardDemo online application dispatched CICS transactions
through the Main Menu program (``app/cbl/COMEN01C.cbl``), which read a
numeric option code (``WS-OPTION PIC 9(02)``) from the BMS screen and
invoked the appropriate transaction program via ``EXEC CICS XCTL``. In
the cloud-native architecture this dispatch surface is replaced by two
equivalent, stateless interfaces exposed by the same FastAPI app:

1. **REST** — path-and-verb routing via ``src.api.routers`` (HTTP
   method + URL path selects the endpoint).
2. **GraphQL** — single-endpoint schema routing via this package (a
   root ``Query`` or ``Mutation`` field selects the resolver).

Both interfaces delegate to the same business-logic services in
``src.api.services``, so any CICS program's behavior is accessible
identically from either surface.

Subpackages and Submodules
--------------------------
schema
    Strawberry schema stitching — combines the root ``Query`` and
    ``Mutation`` classes from ``queries`` and ``mutations`` into a
    single ``strawberry.Schema`` that can be mounted as a FastAPI
    ``GraphQLRouter``. Exposes the compiled schema object consumed by
    ``src.api.main``.

types
    Strawberry ``@strawberry.type`` / ``@strawberry.input`` definitions
    for the four core entity domains:

    * ``account_type``     — ``Account``, joining the customer and
      card-cross-reference entities (``COACTVWC``, ``CVACT01Y``,
      ``COACTVW.CPY``).
    * ``card_type``        — ``Card`` with optimistic-concurrency
      version field (``COCRDSLC``, ``CVACT02Y``, ``COCRDSL.CPY``).
    * ``transaction_type`` — ``Transaction`` with ``PIC S9(n)V99``
      amount fields modeled as ``Decimal`` (``COTRN01C``, ``CVTRA05Y``,
      ``COTRN01.CPY``).
    * ``user_type``        — ``User`` (BCrypt-hashed password omitted
      from GraphQL output per the security rule in AAP §0.7.2)
      (``COUSR00C``, ``CSUSR01Y``, ``COUSR00.CPY``).

queries
    Root ``Query`` class with resolver functions for the 4 read-oriented
    CICS programs (``COACTVWC``, ``COCRDLIC``, ``COTRN00C``, ``COUSR00C``
    and their detail variants). Each resolver delegates to the
    corresponding ``src.api.services`` method and returns a Strawberry
    type from ``src.api.graphql.types``.

mutations
    Root ``Mutation`` class with resolver functions for the
    write-oriented CICS programs (``COACTUPC``, ``COCRDUPC``,
    ``COTRN02C``, ``COBIL00C``). Each resolver delegates to the
    corresponding ``src.api.services`` method and returns the updated
    Strawberry type.

Design Notes
------------
* **No eager imports**: This ``__init__.py`` performs NO imports of its
  submodules. Consumers must import what they need explicitly, e.g.::

      from src.api.graphql.schema import schema
      from src.api.graphql.types.account_type import AccountType
      from src.api.graphql.queries import Query
      from src.api.graphql.mutations import Mutation

  Eagerly importing the Strawberry schema from this file would pull the
  entire ``strawberry`` + ``sqlalchemy`` + service-layer dependency
  graph on any ``import src.api.graphql`` call — an antipattern that
  would lengthen test-collection time, create circular-import risk
  between resolver modules and service modules, and conflict with the
  package layout contract established by the sibling ``src.api`` init.

* **Single FastAPI mount point**: The compiled Strawberry schema is
  mounted exactly once in ``src.api.main`` via
  ``app.include_router(GraphQLRouter(schema))`` at ``/graphql``. Tests
  that need to exercise GraphQL directly should use
  ``strawberry.Schema.execute_sync()`` / ``execute()`` on the imported
  ``schema`` object rather than spinning up a full FastAPI test client,
  unless end-to-end HTTP behavior is the specific subject under test.

* **Shared service layer**: Every resolver in ``queries`` and
  ``mutations`` delegates to the same service methods used by the REST
  routers in ``src.api.routers`` (e.g., ``AccountService.get_view``,
  ``CardService.update``, ``TransactionService.add``,
  ``BillService.pay``). This preserves the "one business-logic
  implementation, two protocol surfaces" contract and guarantees that
  GraphQL and REST produce identical side effects — including the
  dual-write atomicity of Account Update (F-005) and Bill Payment
  (F-012), and the optimistic-concurrency check of Card Update (F-008).

* **Authentication**: GraphQL requests flow through the same JWT
  middleware (``src.api.middleware.auth``) as REST requests. The
  resolved JWT subject is injected into each resolver's Strawberry
  ``Info.context`` via the FastAPI dependency chain, so no resolver
  needs to parse the ``Authorization`` header directly. Unauthenticated
  access is rejected before any resolver runs — matching the
  ``COSGN00C`` sign-on gate in the original CICS application.

* **Financial precision**: All monetary fields in the Strawberry types
  (``Transaction.amount``, ``Account.balance``, etc.) are typed as
  Python ``decimal.Decimal`` and serialized by Strawberry's built-in
  ``Decimal`` scalar. No float conversion occurs in the GraphQL layer,
  preserving the COBOL ``PIC S9(n)V99`` ``ROUND_HALF_EVEN`` contract.

* **Python 3.11+**: Aligned with the FastAPI / ECS Fargate runtime
  (``python:3.11-slim`` base image) and with the rest of the CardDemo
  source tree. Uses PEP 604 union syntax and PEP 585 generic collection
  types throughout.

* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/api/graphql/`` layout)
AAP §0.5.1 — File-by-File Transformation Plan (GraphQL file mappings)
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic exactly)
AAP §0.7.2 — Security Requirements (JWT, BCrypt, financial precision)
"""

# ----------------------------------------------------------------------------
# Package version.
#
# Tracks the contract compatibility of the GraphQL schema surface —
# Strawberry type definitions, root Query/Mutation field names, and
# resolver return-type stability — so that GraphQL clients (web UIs,
# mobile apps, integration tests, ``introspection`` consumers) can
# detect breaking changes.
#
# Semantic versioning: MAJOR.MINOR.PATCH
#   MAJOR — Breaking changes to the GraphQL schema (removed fields,
#           renamed types, changed required arguments, changed return
#           types).
#   MINOR — Backward-compatible additions (new Query/Mutation fields,
#           new optional input arguments, new Strawberry types, new
#           nullable response fields).
#   PATCH — Resolver implementation fixes with no schema-level changes.
#
# This version MUST remain synchronized with ``src.api.__version__`` at
# major/minor boundaries — the GraphQL surface and the REST surface are
# co-published from the same FastAPI application and must advertise a
# consistent overall API contract to operators and clients.
# ----------------------------------------------------------------------------
__version__: str = "1.0.0"

# Explicit re-export list — only ``__version__`` is considered part of
# the public API of this package module. All other symbols (the
# compiled ``schema``, the root ``Query`` and ``Mutation`` classes, the
# individual Strawberry types) must be imported from their specific
# submodules, e.g.::
#
#     from src.api.graphql.schema import schema
#     from src.api.graphql.queries import Query
#     from src.api.graphql.mutations import Mutation
#     from src.api.graphql.types.account_type import AccountType
#     from src.api.graphql.types.card_type import CardType
#     from src.api.graphql.types.transaction_type import TransactionType
#     from src.api.graphql.types.user_type import UserType
#
# ``from src.api.graphql import *`` imports only ``__version__``.
__all__: list[str] = ["__version__"]
