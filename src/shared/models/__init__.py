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
* Entity model re-exports (``Account``, ``Card``, ``Customer``,
  ``CardCrossReference``, ``Transaction``, ``TransactionCategoryBalance``,
  ``DailyTransaction``, ``DisclosureGroup``, ``TransactionType``,
  ``TransactionCategory``, ``UserSecurity``) are added by the model files
  as they land; consumers should import entity classes from their specific
  submodule paths (e.g., ``from src.shared.models.user_security import
  UserSecurity``) until the full package registry is assembled.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan (model mappings)
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on monetary fields)
"""

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
# Public re-export list.
#
# Only the ``Base`` declarative class is advertised at this stage. Individual
# entity model classes must be imported from their specific submodule path
# (e.g., ``from src.shared.models.user_security import UserSecurity``).
# This mirrors the lazy-loading pattern used by ``src.shared.utils`` and
# avoids creating a circular-import risk while the model package is being
# assembled file-by-file.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "Base",
]
