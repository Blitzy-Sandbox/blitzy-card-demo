# ============================================================================
# Source: COBOL copybook library (app/cpy/) — Mainframe-to-Cloud migration
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

This package initializer defines the :class:`Base` declarative class that ALL
CardDemo ORM entity models inherit from. It is the foundational module on
which the entire ``src.shared.models`` package is built.

Maps 10 VSAM KSDS datasets + 3 alternate indexes (AIX) to 11 relational tables
in AWS Aurora PostgreSQL (PostgreSQL-compatible edition), replacing the
mainframe VSAM persistence layer as part of the CardDemo cloud-native
modernization.

Design Notes
------------
* ``Base`` is defined BEFORE any model imports — every entity module
  (``account.py``, ``card.py``, ``customer.py``, ``user_security.py``, etc.)
  performs ``from src.shared.models import Base`` and subclasses it, so the
  Base class must exist at module load time.
* Uses SQLAlchemy 2.x :class:`~sqlalchemy.orm.DeclarativeBase` (NOT the
  legacy ``declarative_base()`` function). This provides typed
  :class:`~sqlalchemy.orm.Mapped` annotations, improved static-analysis
  support, and is the officially recommended pattern in SQLAlchemy 2.x.
* Entity model classes (``Account``, ``Card``, ``Customer``,
  ``CardCrossReference``, ``Transaction``, ``TransactionCategoryBalance``,
  ``DailyTransaction``, ``DisclosureGroup``, ``TransactionType``,
  ``TransactionCategory``, ``UserSecurity``) are intentionally NOT
  eagerly imported here. This keeps the initialization of
  ``src.shared.models`` minimal and side-effect free, avoids circular
  imports between entity modules, and mirrors the lazy-loading pattern
  used by the rest of ``src.shared``. Consumers should import entity
  classes from their specific submodule paths (e.g., ``from
  src.shared.models.user_security import UserSecurity``).
* The :func:`load_all_models` helper is provided for callers — Alembic
  ``env.py``, pytest fixtures that create/drop the test schema,
  ``Base.metadata.create_all()`` bootstrap scripts, and QA tooling —
  that need every entity registered on ``Base.metadata`` before they
  introspect or materialize the full schema. Calling it triggers the
  import of all 11 entity modules in a safe, idempotent, deterministic
  manner. See the function's own docstring for full usage examples.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan (model mappings)
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on monetary fields)
"""

from sqlalchemy import MetaData
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """SQLAlchemy 2.x declarative base class for all CardDemo ORM models.

    Every entity model in the ``src.shared.models`` package (``Account``,
    ``Card``, ``Customer``, ``CardCrossReference``, ``Transaction``,
    ``TransactionCategoryBalance``, ``DailyTransaction``, ``DisclosureGroup``,
    ``TransactionType``, ``TransactionCategory``, ``UserSecurity``) inherits
    from this class to register with a single shared
    :class:`~sqlalchemy.MetaData` instance, enabling unified schema
    management, Alembic migrations, and ``Base.metadata.create_all()``
    bootstrap.

    Intentionally empty — no shared columns, mixins, or type annotations
    are hoisted onto the base. Each entity model declares its own columns
    so that the mapping to the originating COBOL copybook record layout
    remains explicit and auditable (see AAP §0.7.1 "Refactoring-Specific
    Rules" — preserve existing functionality / minimal change clause).
    """


# ----------------------------------------------------------------------------
# load_all_models() — convenience helper for full-schema bootstrap
# ----------------------------------------------------------------------------
# QA Checkpoint 1 (Feature 10, Finding 2 [INFO]) observed that a bare
# ``from src.shared.models import Base`` leaves ``Base.metadata.tables``
# empty because no entity module has been imported. This is the
# intentional lazy-loading contract of the package — but callers that
# DO need the full schema registered (Alembic ``env.py``, pytest
# fixtures that drop-and-create the test database, ad-hoc QA tooling,
# ``Base.metadata.create_all(engine)`` bootstrap scripts) now have a
# single, documented, idempotent entry point.
#
# Imports are performed in alphabetical order so the table order on
# ``MetaData.sorted_tables`` is deterministic across runs — a property
# that Alembic autogenerate and DDL diffing tools rely on for clean,
# reproducible migrations.
# ----------------------------------------------------------------------------
def load_all_models() -> MetaData:
    """Import every CardDemo entity module and return ``Base.metadata``.

    Performs the one-time side-effect of importing all 11 ORM entity
    modules so that every mapped class registers its table on
    ``Base.metadata``. After the call returns, the metadata object
    contains the complete CardDemo relational schema and can be used
    for:

    * :meth:`sqlalchemy.MetaData.create_all` — bootstrap the schema in
      an empty database (tests, local development).
    * :meth:`sqlalchemy.MetaData.drop_all` — tear down the schema for
      test isolation.
    * Alembic ``env.py`` ``target_metadata`` — enable autogenerate to
      see the full model set when producing migration scripts.
    * Introspection tools (QA validators, schema diff utilities) that
      need to enumerate the full table set.

    The function is **idempotent**: subsequent calls are no-ops because
    Python caches imported modules in :data:`sys.modules` and SQLAlchemy
    registers each table exactly once on the shared metadata registry.
    Callers may invoke it unconditionally at startup without risking
    duplicate-table errors.

    Imports execute in alphabetical order so the resulting
    ``MetaData.sorted_tables`` order is stable across runs — important
    for Alembic autogenerate and any tool that compares rendered DDL
    across commits.

    Returns
    -------
    sqlalchemy.MetaData
        The :attr:`Base.metadata` instance, after registration of all
        11 entity tables: ``account``, ``card``, ``card_cross_reference``,
        ``customer``, ``daily_transaction``, ``disclosure_group``,
        ``transaction``, ``transaction_category``,
        ``transaction_category_balance``, ``transaction_type``,
        ``user_security``.

    Examples
    --------
    Bootstrap the full schema on an empty database::

        from sqlalchemy import create_engine
        from src.shared.models import Base, load_all_models

        load_all_models()
        engine = create_engine("postgresql+psycopg2://.../carddemo")
        Base.metadata.create_all(engine)

    Alembic ``env.py``::

        from src.shared.models import Base, load_all_models

        load_all_models()
        target_metadata = Base.metadata

    pytest fixture for a clean test database::

        @pytest.fixture
        def db_schema(engine):
            from src.shared.models import Base, load_all_models
            load_all_models()
            Base.metadata.create_all(engine)
            yield
            Base.metadata.drop_all(engine)

    See Also
    --------
    :class:`Base` — declarative base all entity models subclass.
    AAP §0.5.1 — File-by-File Transformation Plan (11 entity modules).
    """
    # Imports are intentionally local to the function body. Placing them
    # at module-import-time would defeat the lazy-loading contract this
    # package is built on. Local imports are cheap after the first call
    # because Python caches modules in ``sys.modules``.
    #
    # Each ``import ... as _`` discards the module reference — all we
    # need is the side-effect of SQLAlchemy registering each subclass
    # on ``Base.metadata``. ``noqa: F401`` suppresses the
    # unused-import lint warning that would otherwise be raised for
    # the side-effect imports.
    from src.shared.models import account as _account  # noqa: F401
    from src.shared.models import card as _card  # noqa: F401
    from src.shared.models import card_cross_reference as _card_cross_reference  # noqa: F401
    from src.shared.models import customer as _customer  # noqa: F401
    from src.shared.models import daily_transaction as _daily_transaction  # noqa: F401
    from src.shared.models import disclosure_group as _disclosure_group  # noqa: F401
    from src.shared.models import transaction as _transaction  # noqa: F401
    from src.shared.models import transaction_category as _transaction_category  # noqa: F401
    from src.shared.models import transaction_category_balance as _transaction_category_balance  # noqa: F401
    from src.shared.models import transaction_type as _transaction_type  # noqa: F401
    from src.shared.models import user_security as _user_security  # noqa: F401

    return Base.metadata


# ----------------------------------------------------------------------------
# Public re-export list.
#
# Advertises the declarative :class:`Base` and the :func:`load_all_models`
# bootstrap helper. Entity classes are NOT re-exported here — consumers
# must import them from their specific submodule path (e.g.,
# ``from src.shared.models.user_security import UserSecurity``). This
# mirrors the lazy-loading pattern used by ``src.shared.utils`` and
# ``src.shared.schemas``, and avoids creating a circular-import risk
# while the model package is being assembled file-by-file.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "Base",
    "load_all_models",
]
