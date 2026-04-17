# ============================================================================
# Source: COBOL copybook CVTRA04Y.cpy — TRAN-CAT-RECORD (RECLN 60)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Reference-data table that catalogs every valid combination of
# ``(transaction type, transaction category)`` along with its human-readable
# description. Typically 18 rows are seeded from the fixture file
# ``app/data/ASCII/trancatg.txt`` — e.g., ``(type='01', cat='0001',
# 'Regular Sales Draft')``, ``(type='02', cat='0001', 'Cash payment')``,
# ``(type='05', cat='0001', 'Refund credit')``, etc.
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
"""SQLAlchemy 2.x ORM model for the ``transaction_category`` table.

Converts the COBOL copybook ``app/cpy/CVTRA04Y.cpy`` (record layout
``TRAN-CAT-RECORD``, 60-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a transaction-category reference-data
row in the CardDemo Aurora PostgreSQL database.

The transaction-category table is the canonical lookup for every valid
``(transaction_type, transaction_category)`` pair recognised by the
application. It is consumed by both batch programs (``CBTRN02C`` →
``posttran_job.py`` for transaction-posting validation; ``CBACT04C`` →
``intcalc_job.py`` for interest-bucket selection) and by the online
transaction-add flow (``COTRN02C`` → ``transaction_service.py``).

COBOL to Python Field Mapping
-----------------------------
==================  ==============  =================  =========================
COBOL Field         COBOL Type      Python Column      SQLAlchemy Type
==================  ==============  =================  =========================
TRAN-TYPE-CD        ``PIC X(02)``   ``type_cd``        ``String(2)`` — PK #1
TRAN-CAT-CD         ``PIC 9(04)``   ``cat_cd``         ``String(4)`` — PK #2
TRAN-CAT-TYPE-DESC  ``PIC X(50)``   ``description``    ``String(50)``
FILLER              ``PIC X(04)``   — (not mapped)     — (COBOL padding only)
==================  ==============  =================  =========================

Total RECLN: 2 + 4 + 50 + 4 = 60 bytes — matches the original COBOL
copybook record length (see ``app/cpy/CVTRA04Y.cpy``).

Composite Primary Key — ``TRAN-CAT-KEY``
----------------------------------------
The 2-part composite primary key ``(type_cd, cat_cd)`` directly mirrors
the COBOL group-level key ``TRAN-CAT-KEY`` (lines 2–4 of
``CVTRA04Y.cpy``), which is the full key used for ``READ``/``WRITE``/
``REWRITE`` operations against the originating VSAM KSDS dataset.
Preserving the two fields as composite PK components (rather than
introducing a surrogate integer ID) keeps the relational row
addressable by the same logical key as its VSAM ancestor — a
prerequisite for behaviour-preserving batch translation
(see AAP §0.7.1 "Refactoring-Specific Rules" — preserve existing
business logic without modification).

Column Naming — ``type_cd`` / ``cat_cd``
----------------------------------------
The column names ``type_cd`` and ``cat_cd`` (rather than
``tran_type_cd`` / ``tran_cat_cd``) are specified in AAP §0.5.1
"File-by-File Transformation Plan" and reflect the fact that within
the ``transaction_category`` table the ``tran_`` prefix is implicit
in the table name itself. The columns are logically foreign keys to
``transaction_type.tran_type`` (on ``type_cd``) and are themselves
referenced by the composite keys of ``disclosure_group`` (via its
``tran_type_cd`` / ``tran_cat_cd`` columns) and
``transaction_category_balance`` (via its ``tran_type_cd`` /
``tran_cat_cd`` columns). The unprefixed naming here is a schema-
documented, deliberate departure from the sibling tables' prefixed
naming and is the canonical contract that downstream code
(repositories, services, GraphQL types, Pydantic schemas) must honour.

Category-Code Fixed-Width Semantics — ``String(4)``
---------------------------------------------------
``TRAN-CAT-CD`` is declared in COBOL as ``PIC 9(04)`` (unsigned
numeric, 4 digits) but is mapped to ``String(4)`` in the relational
model to preserve leading-zero formatting (e.g., ``'0001'`` vs
``'1'``). This keeps the code semantically a label rather than a
numeric quantity — consistent with ``DisclosureGroup.tran_cat_cd``
and ``TransactionCategoryBalance.tran_cat_cd`` in sibling models —
and avoids lossy round-trips when joining against those tables on
the category-code component of their composite keys.

Reference-Data Seed Rows
------------------------
The table is seeded from the 18-row fixture
``app/data/ASCII/trancatg.txt`` during initial load (see
``db/migrations/V3__seed_data.sql``). Typical rows include:

* ``('01', '0001', 'Regular Sales Draft')``
* ``('01', '0002', 'Regular Cash Advance')``
* ``('01', '0003', 'Convenience Check Debit')``
* ``('01', '0004', 'ATM Cash Advance')``
* ``('01', '0005', 'Interest Amount')``
* ``('02', '0001', 'Cash payment')``
* ``('02', '0002', 'Electronic payment')``
* ``('02', '0003', 'Check payment')``
* ``('03', '0001', 'Credit to Account')``
* ``('03', '0002', 'Credit to Purchase balance')``
* ``('03', '0003', 'Credit to Cash balance')``
* ``('04', '0001', 'Zero dollar authorization')``
* ``('04', '0002', 'Online purchase authorization')``
* ``('04', '0003', 'Travel booking authorization')``
* ``('05', '0001', 'Refund credit')``
* ``('06', '0001', 'Fraud reversal')``
* ``('06', '0002', 'Non-fraud reversal')``
* ``('07', '0001', 'Sales draft credit adjustment')``

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models.
* No ``FILLER`` column is mapped — the trailing 4 bytes of COBOL
  padding have no relational counterpart. In PostgreSQL, column
  widths are explicit and trailing padding has no storage or
  semantic meaning.
* No monetary fields — the transaction-category record has no
  ``decimal.Decimal`` columns (the rate and balance values live in
  sibling tables ``disclosure_group`` and
  ``transaction_category_balance``, respectively).
* No relationships are declared on this reference-data table to keep
  it free of back-references and avoid circular-import risk while
  the model package is being assembled file-by-file. Consumers that
  need to join on ``type_cd`` / ``cat_cd`` do so explicitly via the
  composite-key columns on the dependent tables.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.2 — Batch Program Classification (category lookup usage in
POSTTRAN and INTCALC).
AAP §0.5.1 — File-by-File Transformation Plan
(``transaction_category.py`` entry).
AAP §0.5.1 — DB migrations: ``db/migrations/V1__schema.sql``,
``db/migrations/V3__seed_data.sql`` (seed rows from
``trancatg.txt``).
``app/cpy/CVTRA04Y.cpy`` — Original COBOL record layout (source artifact).
``app/jcl/TRANCATG.jcl`` — Original VSAM cluster definition and load job.
``app/data/ASCII/trancatg.txt`` — Original seed-data fixture (18 rows).
``src.shared.models.transaction_type.TransactionType`` — Reference-data
table for ``type_cd`` (``transaction_type.tran_type``).
``src.shared.models.disclosure_group.DisclosureGroup`` — Sibling
reference table keyed by the same ``(tran_type, tran_cat)`` pair.
``src.shared.models.transaction_category_balance.TransactionCategoryBalance``
— Runtime balance table keyed by ``(acct_id, tran_type, tran_cat)``.
"""

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class TransactionCategory(Base):
    """ORM entity for the ``transaction_category`` table (from COBOL ``TRAN-CAT-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``transaction_category`` reference table, which replaces the
    mainframe VSAM KSDS ``TRANCATG`` dataset. Each row defines a
    unique ``(transaction_type, transaction_category)`` pair and its
    human-readable description, used by:

    * **Batch posting** (``CBTRN02C`` → ``posttran_job.py``) to
      validate that an incoming daily transaction references a
      recognised ``(type, category)`` combination (reject code 105
      is raised when a transaction's category pair is not found in
      this table).
    * **Interest calculation** (``CBACT04C`` →
      ``intcalc_job.py``) to resolve the bucket selection when
      iterating ``TransactionCategoryBalance`` rows during the
      monthly interest-accrual pass.
    * **Online transaction add** (``COTRN02C`` →
      ``transaction_service.py``) to populate dropdown/autocomplete
      values and validate user-entered category pairs before
      persistence.

    Attributes
    ----------
    type_cd : str
        **Primary key part 1 of 2.** 2-character transaction-type
        code (from COBOL ``TRAN-TYPE-CD``, ``PIC X(02)``). References
        the ``transaction_type.tran_type`` reference-data table
        (e.g., ``'01'`` = Purchase, ``'02'`` = Payment, ``'03'`` =
        Credit, ``'04'`` = Debit, ``'05'`` = Refund, ``'06'`` =
        Adjustment, ``'07'`` = Fee).
    cat_cd : str
        **Primary key part 2 of 2.** 4-character transaction-category
        code (from COBOL ``TRAN-CAT-CD``, ``PIC 9(04)``). Stored as a
        fixed-width string with leading zeros (e.g., ``'0001'``,
        ``'0002'``) rather than as an integer to preserve the COBOL
        label semantics and maintain parity with
        ``DisclosureGroup.tran_cat_cd`` and
        ``TransactionCategoryBalance.tran_cat_cd`` for clean
        composite-key joins.
    description : str
        Up to 50-character category description (from COBOL
        ``TRAN-CAT-TYPE-DESC``, ``PIC X(50)``). Human-readable label
        displayed in transaction list/detail screens
        (``COTRN00``/``COTRN01``), rendered into generated statements
        (``CREASTMT``), and included in transaction reports
        (``TRANREPT``).
    """

    __tablename__ = "transaction_category"

    # ------------------------------------------------------------------
    # Primary key part 1 of 2: transaction-type code
    # (COBOL ``TRAN-TYPE-CD`` PIC X(02))
    #
    # Logical FK to ``transaction_type.tran_type``. Typical values:
    #   '01' Purchase · '02' Payment · '03' Credit · '04' Debit
    #   '05' Refund   · '06' Adjustment · '07' Fee
    # ------------------------------------------------------------------
    type_cd: Mapped[str] = mapped_column(
        String(2),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Primary key part 2 of 2: transaction-category code
    # (COBOL ``TRAN-CAT-CD`` PIC 9(04))
    #
    # Stored as String(4) with leading zeros (e.g. '0001') rather than
    # as an integer — preserves the COBOL label semantics and matches
    # the type used by ``DisclosureGroup.tran_cat_cd`` and
    # ``TransactionCategoryBalance.tran_cat_cd`` for clean joins.
    # ------------------------------------------------------------------
    cat_cd: Mapped[str] = mapped_column(
        String(4),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Category description (COBOL ``TRAN-CAT-TYPE-DESC`` PIC X(50))
    #
    # Example seed values (see ``app/data/ASCII/trancatg.txt``):
    #   ('01', '0001', 'Regular Sales Draft')
    #   ('02', '0001', 'Cash payment')
    #   ('05', '0001', 'Refund credit')
    # ------------------------------------------------------------------
    description: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
    )

    # Note: COBOL ``FILLER PIC X(04)`` — the trailing 4 bytes of
    # padding in the original 60-byte VSAM record — is deliberately NOT
    # mapped. In the relational model, column widths are explicit and
    # trailing padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        Includes all three mapped columns (the 2-part composite
        primary key and the category description).

        Returns
        -------
        str
            Representation of the form
            ``TransactionCategory(type_cd='01', cat_cd='0001',
            description='Regular Sales Draft')``.
        """
        return (
            f"TransactionCategory("
            f"type_cd={self.type_cd!r}, "
            f"cat_cd={self.cat_cd!r}, "
            f"description={self.description!r}"
            f")"
        )
