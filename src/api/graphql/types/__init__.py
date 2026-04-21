# ============================================================================
# Source: COBOL copybook library (app/cpy/) — Mainframe-to-Cloud migration
#         - app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD,  300B) -> AccountType
#         - app/cpy/CVACT02Y.cpy (CARD-RECORD,     150B) -> CardType
#         - app/cpy/CVTRA05Y.cpy (TRAN-RECORD,     350B) -> TransactionType
#         - app/cpy/CSUSR01Y.cpy (SEC-USER-DATA,    80B) -> UserType
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
"""Strawberry GraphQL type definitions for CardDemo entities.

Re-exports all 4 Strawberry ``@strawberry.type`` classes for the core
CardDemo entities, converted from COBOL copybook record layouts:

* :class:`AccountType`     — from ``app/cpy/CVACT01Y.cpy``
  (``ACCOUNT-RECORD``, 300 bytes). Twelve fields including five
  :class:`decimal.Decimal` monetary fields (``curr_bal``,
  ``credit_limit``, ``cash_credit_limit``, ``curr_cyc_credit``,
  ``curr_cyc_debit``) matching COBOL ``PIC S9(10)V99`` semantics.
* :class:`CardType`        — from ``app/cpy/CVACT02Y.cpy``
  (``CARD-RECORD``, 150 bytes). Six fields; CVV code is ``str`` to
  preserve leading zeros (e.g., ``'007'`` would otherwise collapse
  to ``7`` if stored as ``int``).
* :class:`TransactionType` — from ``app/cpy/CVTRA05Y.cpy``
  (``TRAN-RECORD``, 350 bytes). Thirteen fields including one
  :class:`decimal.Decimal` amount field matching COBOL
  ``PIC S9(09)V99``.
* :class:`UserType`        — from ``app/cpy/CSUSR01Y.cpy``
  (``SEC-USER-DATA``, 80 bytes). Four fields; the ``SEC-USR-PWD``
  password field is deliberately OMITTED from the GraphQL surface
  for security (AAP §0.7.2).

Mainframe-to-Cloud migration
----------------------------
VSAM KSDS datasets (``ACCTFILE``, ``CARDFILE``, ``TRANFILE``,
``USRSEC``) -> Aurora PostgreSQL tables (``accounts``, ``cards``,
``transactions``, ``user_security``) -> GraphQL API via Strawberry.

Consumer modules
----------------
The 4 Strawberry types exposed by this package are consumed by:

* ``src.api.graphql.queries`` — root ``Query`` resolvers for
  single-entity fetches and paginated lists (corresponding to the
  CICS ``READ`` / ``STARTBR`` / ``READNEXT`` patterns in the COBOL
  online programs ``COACTVWC``, ``COCRDLIC``, ``COCRDSLC``,
  ``COTRN00C``, ``COTRN01C``, ``COUSR00C``).
* ``src.api.graphql.mutations`` — root ``Mutation`` resolvers for
  create / update / delete operations (corresponding to the CICS
  ``WRITE`` / ``REWRITE`` / ``DELETE`` patterns in ``COACTUPC``,
  ``COCRDUPC``, ``COTRN02C``, ``COBIL00C``, ``COUSR01C``,
  ``COUSR02C``, ``COUSR03C``).
* ``src.api.graphql.schema`` — Strawberry schema stitching, which
  combines the ``Query`` and ``Mutation`` classes into a single
  ``strawberry.Schema`` object mounted at ``/graphql`` by
  ``src.api.main``.

Importing convenience
---------------------
This package initializer re-exports the 4 Strawberry types so that
resolver modules can use a concise single-line import::

    from src.api.graphql.types import (
        AccountType,
        CardType,
        TransactionType,
        UserType,
    )

rather than four separate submodule imports. The same types remain
importable from their individual submodules for more surgical imports
(e.g., ``from src.api.graphql.types.account_type import AccountType``).

Design notes
------------
* **No circular imports.** Each Strawberry type module imports only
  from :mod:`src.shared.models` (the SQLAlchemy ORM layer) and
  :mod:`strawberry` — NOT from any sibling module within this
  package — so re-exporting them here cannot introduce an import
  cycle with :mod:`src.api.graphql.queries` or
  :mod:`src.api.graphql.mutations`.
* **Type-class identity is preserved.** ``AccountType is
  account_type.AccountType`` is guaranteed: this module performs a
  simple ``from ... import AccountType`` (name re-binding), not a
  copy, so Strawberry's schema registry and the ``isinstance``
  checks in the resolver layer see the exact same class object from
  either import path.
* **Python 3.11+** only. Aligned with the AWS Glue 5.1 runtime
  baseline (Python 3.11, Spark 3.5.6) and the FastAPI / ECS Fargate
  deployment target.

See Also
--------
* AAP §0.4.1 — Target architecture overview
  (``src/api/graphql/types/`` package).
* AAP §0.5.1 — File-by-File Transformation Plan
  (``__init__.py`` entry for this package).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve existing
  functionality exactly; do not modify the original COBOL source
  files in ``app/cpy/``).
* AAP §0.7.2 — Financial Precision and Security Requirements
  (Decimal monetary fields; omit password from GraphQL output).
* :mod:`src.api.graphql.queries` — query resolvers consuming these
  types.
* :mod:`src.api.graphql.mutations` — mutation resolvers consuming
  these types.
* :mod:`src.api.graphql.schema` — compiled Strawberry schema.
"""

# ----------------------------------------------------------------------------
# Re-exports — import the 4 Strawberry GraphQL type classes from their
# respective submodules so they can be imported from this package root.
#
# Each submodule is self-contained: its only non-standard-library
# imports are :mod:`strawberry` and a single SQLAlchemy model from
# :mod:`src.shared.models` (for the ``from_model()`` classmethod
# factory). None of the submodules imports from a sibling submodule,
# so this __init__ module cannot introduce a circular-import cycle.
#
# The imports below MUST remain at module top level (not guarded by
# ``TYPE_CHECKING`` or a lazy ``__getattr__`` hook) because Strawberry
# requires every ``@strawberry.type`` class to be fully constructed
# before the root ``Query`` / ``Mutation`` classes reference it via
# type annotations. Any lazy-loading scheme here would defer that
# construction past the point where Strawberry builds the schema,
# breaking ``src.api.graphql.schema`` at import time.
# ----------------------------------------------------------------------------
from src.api.graphql.types.account_type import AccountType
from src.api.graphql.types.card_type import CardType
from src.api.graphql.types.transaction_type import TransactionType
from src.api.graphql.types.user_type import UserType

# ----------------------------------------------------------------------------
# Package version.
#
# Tracks the contract compatibility of the four Strawberry GraphQL
# type surfaces exposed by this package — field names, Python type
# annotations, ``from_model()`` classmethod signatures, and
# Strawberry-derived GraphQL schema attributes — so that GraphQL
# clients (web UIs, mobile apps, integration tests, introspection
# consumers) can detect breaking changes at the sub-package
# granularity.
#
# Semantic versioning: MAJOR.MINOR.PATCH
#   MAJOR — Breaking changes to any type (removed / renamed fields,
#           changed field Python types, changed nullability).
#   MINOR — Backward-compatible additions (new optional fields on an
#           existing type, new type added to the package).
#   PATCH — Documentation fixes, comment-only edits, changes that
#           leave the serialized GraphQL schema identical.
#
# This version MUST remain synchronized with
# ``src.api.graphql.__version__`` (the parent package) and with
# ``src.api.__version__`` at major/minor boundaries — the four
# Strawberry types are mounted into the same FastAPI GraphQL
# endpoint and must advertise a consistent overall API contract to
# operators and clients.
# ----------------------------------------------------------------------------
__version__: str = "1.0.0"

# ----------------------------------------------------------------------------
# Public re-export list.
#
# The explicit ``__all__`` declaration serves three purposes:
#
# 1. It marks the four imported type classes as intentional
#    re-exports, satisfying the ``ruff`` ``F401`` ("unused import")
#    lint rule without the need for per-line suppression directives
#    of the form ``noqa:F401``.
# 2. It makes ``from src.api.graphql.types import *`` expose exactly
#    the four Strawberry type classes listed below and nothing else
#    (no accidental leakage of :mod:`strawberry`, :mod:`decimal`, or
#    the SQLAlchemy ``Account`` / ``Card`` / ``Transaction`` /
#    ``UserSecurity`` model classes that the submodules import for
#    their ``from_model()`` factories).
# 3. It documents the complete public API surface of the package in
#    one place, mirroring the ``exports`` block declared for this
#    file in the AAP §0.5.1 transformation plan.
#
# ``__version__`` is a module-level attribute available to consumers
# via ``src.api.graphql.types.__version__`` but is intentionally NOT
# included in ``__all__`` — its presence in ``__all__`` is neither
# required nor conventional for package version markers, and the
# agent prompt for this file explicitly mandates exactly four entries
# (one per Strawberry type class).
#
# Entries are listed in the same order as the AAP transformation
# table for consistent cross-referencing:
# AccountType, CardType, TransactionType, UserType.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "AccountType",
    "CardType",
    "TransactionType",
    "UserType",
]
