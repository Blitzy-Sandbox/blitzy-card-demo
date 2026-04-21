# ============================================================================
# Source: COBOL copybook library (app/cpy/) — Mainframe-to-Cloud migration
# ============================================================================
# SQLAlchemy 2.x ORM model registry for CardDemo Aurora PostgreSQL database.
# Maps 10 VSAM KSDS datasets + 3 AIX to 11 relational tables.
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
"""SQLAlchemy 2.x ORM model registry for the CardDemo Aurora PostgreSQL database.

This package initializer is the FOUNDATIONAL module of
``src.shared.models``. It has two responsibilities:

1. **Define the :class:`Base` declarative class** that every CardDemo
   ORM entity model inherits from. The class uses SQLAlchemy 2.x
   :class:`~sqlalchemy.orm.DeclarativeBase` to enable typed
   :class:`~sqlalchemy.orm.Mapped` annotations and the officially
   recommended modern mapping style.

2. **Eagerly import and re-export all 11 entity model classes** so
   that a single ``from src.shared.models import ...`` statement both
   gives consumers access to every model class AND guarantees every
   table is registered on ``Base.metadata`` (required for
   ``Base.metadata.create_all()``, Alembic autogenerate, Alembic
   migrations, pytest schema fixtures, and any introspection tool
   that walks the complete relational schema).

COBOL to Aurora PostgreSQL Mapping
----------------------------------
Replaces the mainframe VSAM persistence layer — 10 VSAM KSDS clusters
plus 3 Alternate Indexes (AIX) — with a normalized 11-table
PostgreSQL 16 schema on AWS Aurora (PostgreSQL-compatible edition).
Each ORM class is a faithful translation of a COBOL copybook record
layout:

============================================  ===========================  =========================
COBOL Copybook (``app/cpy/``)                 Python Module                Class
============================================  ===========================  =========================
``CVACT01Y.cpy`` (ACCOUNT-RECORD, 300B)       ``account.py``               :class:`Account`
``CVACT02Y.cpy`` (CARD-RECORD, 150B)          ``card.py``                  :class:`Card`
``CVCUS01Y.cpy`` (CUSTOMER-RECORD, 500B)      ``customer.py``              :class:`Customer`
``CVACT03Y.cpy`` (CARD-XREF, 50B)             ``card_cross_reference.py``  :class:`CardCrossReference`
``CVTRA05Y.cpy`` (TRAN-RECORD, 350B)          ``transaction.py``           :class:`Transaction`
``CVTRA01Y.cpy`` (TRAN-CAT-BAL, 50B)          ``transaction_category_      :class:`TransactionCategoryBalance`
                                              balance.py``
``CVTRA06Y.cpy`` (DAILY-TRAN, 350B)           ``daily_transaction.py``     :class:`DailyTransaction`
``CVTRA02Y.cpy`` (DIS-GRP, 50B)               ``disclosure_group.py``      :class:`DisclosureGroup`
``CVTRA03Y.cpy`` (TRAN-TYPE, 60B)             ``transaction_type.py``      :class:`TransactionType`
``CVTRA04Y.cpy`` (TRAN-CAT, 60B)              ``transaction_category.py``  :class:`TransactionCategory`
``CSUSR01Y.cpy`` (SEC-USER-DATA, 80B)         ``user_security.py``         :class:`UserSecurity`
============================================  ===========================  =========================

Design Notes
------------
* ``Base`` is defined **BEFORE** the 11 entity-model imports. Every
  entity module executes ``from src.shared.models import Base`` at
  module-import-time, and Python resolves this against the partially
  initialized ``src.shared.models`` namespace. Because ``Base`` is
  registered in the namespace before the entity imports begin, the
  circular-lookup succeeds immediately and no circular-import error
  is raised. Reordering (putting entity imports before the ``class
  Base`` statement) WILL break this file — do not change the
  import / class ordering.

* Entity imports are performed in alphabetical order so the resulting
  ``MetaData.sorted_tables`` order is stable across runs. This
  determinism matters for Alembic autogenerate (which renders CREATE
  TABLE statements in metadata order) and for any DDL-diff tooling
  that compares rendered schemas across commits.

* SQLAlchemy 2.x :class:`~sqlalchemy.orm.DeclarativeBase` is used
  (NOT the legacy ``declarative_base()`` function). This provides:

  - Typed :class:`~sqlalchemy.orm.Mapped` column annotations
    (enforced at import-time, visible to mypy / PyCharm / Pyright).
  - First-class ``python typing`` support without plugins.
  - Is the officially recommended mapping style in the SQLAlchemy
    2.x release notes.

* The ``Base`` class body is intentionally empty — no shared columns,
  mixins, or type annotations are hoisted onto the base. Each entity
  model declares its own columns so that the copybook-to-column
  mapping remains explicit and auditable (AAP §0.7.1 "minimal change
  clause" / "preserve existing functionality").

* Python 3.11+ only (aligned with AWS Glue 5.1 PySpark runtime and
  FastAPI / ECS Fargate deployment baseline — see AAP §0.6.1).

Usage Patterns
--------------
Import the declarative base and one or more entity classes from the
package (recommended for services, routers, batch jobs)::

    from src.shared.models import Base, Account, Card, Transaction

Bootstrap the full schema on an empty database — every entity table
is already registered on ``Base.metadata`` by virtue of the eager
re-exports in this file, so no explicit ``load_all_models()`` call
is needed::

    from sqlalchemy import create_engine
    from src.shared.models import Base  # triggers import of all 11 models
    engine = create_engine("postgresql+psycopg2://.../carddemo")
    Base.metadata.create_all(engine)

Alembic ``env.py`` ``target_metadata`` (autogenerate sees every
entity because importing the package eagerly registers them)::

    from src.shared.models import Base  # eager-registers all 11 tables
    target_metadata = Base.metadata

pytest schema fixture for test isolation::

    @pytest.fixture
    def db_schema(engine):
        from src.shared.models import Base  # eager-registers all tables
        Base.metadata.create_all(engine)
        yield
        Base.metadata.drop_all(engine)

See Also
--------
AAP §0.4.1 — Refactored Structure Planning.
AAP §0.5.1 — File-by-File Transformation Plan (11 entity model mappings).
AAP §0.7.1 — Refactoring-Specific Rules (minimal change / preserve logic).
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on monetary columns).
``db/migrations/V1__schema.sql`` — Flyway DDL for the 11 tables
(plural table names: ``accounts``, ``cards``, ``customers``,
``card_cross_references``, ``transactions``,
``transaction_category_balances``, ``daily_transactions``,
``disclosure_groups``, ``transaction_types``,
``transaction_categories``, ``user_security``).
"""

# ----------------------------------------------------------------------------
# SQLAlchemy 2.x declarative base.
#
# MUST be defined BEFORE the entity-module re-exports below, because every
# entity module (account.py, card.py, …) executes
#     from src.shared.models import Base
# at import-time. Placing `class Base` first ensures the name is already
# resolvable in the partially loaded `src.shared.models` namespace when
# the first entity module runs its top-level imports.
# ----------------------------------------------------------------------------
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """SQLAlchemy 2.x declarative base class for every CardDemo ORM model.

    Every ORM entity in the ``src.shared.models`` package inherits from
    this class so that all 11 tables register on a single shared
    :class:`~sqlalchemy.MetaData` instance. This enables unified
    schema management: one ``Base.metadata.create_all(engine)`` call
    materializes the full CardDemo schema, one Alembic ``env.py``
    ``target_metadata = Base.metadata`` assignment exposes every
    entity to autogenerate, and one ``Base.metadata.drop_all(engine)``
    call tears down the entire schema for test isolation.

    The class body is intentionally empty — the package deliberately
    does NOT hoist shared columns, timestamps, audit fields, or mixin
    behaviors onto the base. Each entity model declares its own
    columns verbatim so that the mapping from the originating COBOL
    copybook record layout remains explicit and audit-traceable
    against the retained source artifacts in ``app/cpy/`` (see
    AAP §0.7.1 "minimal change clause" and "preserve existing
    functionality").

    Exposes (via inheritance from :class:`~sqlalchemy.orm.DeclarativeBase`):

    metadata : sqlalchemy.MetaData
        The shared :class:`~sqlalchemy.MetaData` registry on which
        every subclass's :class:`~sqlalchemy.Table` is registered.
        Consumed by ``Base.metadata.create_all()``, Alembic
        autogenerate (``env.py`` ``target_metadata``), schema-diff
        tooling, and the pytest fixtures that drop-and-create the
        test database.
    registry : sqlalchemy.orm.registry
        The underlying SQLAlchemy :class:`~sqlalchemy.orm.registry`
        that holds the class-to-table mapping for every subclass.
        Exposed for advanced use cases (e.g., imperative mapping,
        cross-registry introspection). Ordinary application code
        rarely touches this attribute directly.
    """


# ----------------------------------------------------------------------------
# Eager re-exports of all 11 CardDemo entity model classes.
#
# These imports are placed AFTER ``class Base`` so that the entity modules
# can resolve ``from src.shared.models import Base`` successfully against
# the partially initialized ``src.shared.models`` namespace (Python's
# cyclic-import protocol: the module is already registered in
# ``sys.modules`` with ``Base`` defined, and entity modules therefore
# receive the real ``Base`` object, not a ``ImportError``).
#
# Imports are listed in alphabetical order so ``Base.metadata.sorted_tables``
# produces a stable ordering across runs. This determinism matters for:
#
# * Alembic autogenerate — renders CREATE TABLE statements in metadata
#   order; unstable ordering produces noisy, unreviewable migration
#   diffs across commits.
# * Schema-diff tooling — a stable metadata order makes rendered DDL
#   byte-reproducible so that CI can assert "no schema drift".
# * ``Base.metadata.create_all()`` / ``drop_all()`` — executes tables
#   in metadata order (respecting FK dependencies); a stable order
#   aids debugging when a single table fails to materialize.
#
# The ``noqa: E402`` directives suppress the "module-level import not
# at top of file" lint warning that would otherwise fire because these
# imports deliberately come after the ``class Base`` statement. The
# ordering is load-bearing and cannot be moved (see design note above).
# ----------------------------------------------------------------------------
from src.shared.models.account import Account  # noqa: E402
from src.shared.models.card import Card  # noqa: E402
from src.shared.models.card_cross_reference import CardCrossReference  # noqa: E402
from src.shared.models.customer import Customer  # noqa: E402
from src.shared.models.daily_transaction import DailyTransaction  # noqa: E402
from src.shared.models.disclosure_group import DisclosureGroup  # noqa: E402
from src.shared.models.transaction import Transaction  # noqa: E402
from src.shared.models.transaction_category import TransactionCategory  # noqa: E402
from src.shared.models.transaction_category_balance import (  # noqa: E402
    TransactionCategoryBalance,
)
from src.shared.models.transaction_type import TransactionType  # noqa: E402
from src.shared.models.user_security import UserSecurity  # noqa: E402

# ----------------------------------------------------------------------------
# Public re-export list.
#
# Advertises the declarative :class:`Base` and all 11 CardDemo entity
# model classes as the package's public API. Consumers (services,
# routers, batch jobs, GraphQL resolvers, tests) should import from
# this package root:
#
#     from src.shared.models import Base, Account, Card, Transaction
#
# rather than drilling into submodule paths. The 12-entry list matches
# the "Exports" schema contract (AAP / file schema): one entry for
# :class:`Base` plus one for each of the 11 entity classes.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "Base",
    "Account",
    "Card",
    "Customer",
    "CardCrossReference",
    "Transaction",
    "TransactionCategoryBalance",
    "DailyTransaction",
    "DisclosureGroup",
    "TransactionType",
    "TransactionCategory",
    "UserSecurity",
]
