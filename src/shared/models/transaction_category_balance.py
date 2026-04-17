# ============================================================================
# Source: COBOL copybook CVTRA01Y.cpy — TRAN-CAT-BAL-RECORD (RECLN 50)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Replaces the mainframe TCATBALF VSAM KSDS cluster (see
# ``app/jcl/TCATBALF.jcl``) with a relational PostgreSQL table persisting
# per-account, per-(transaction-type, transaction-category) balance rows.
# The single monetary column ``balance`` uses PostgreSQL NUMERIC(15, 2) to
# preserve the exact COBOL PIC S9(09)V99 decimal semantics — no floating-
# point arithmetic is permitted on this field (see AAP §0.7.2 "Financial
# Precision").
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
"""SQLAlchemy 2.x ORM model for the ``transaction_category_balance`` table.

Converts the COBOL copybook ``app/cpy/CVTRA01Y.cpy`` (record layout
``TRAN-CAT-BAL-RECORD``, 50-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a per-account running balance keyed
by ``(account, transaction-type, transaction-category)`` in the CardDemo
Aurora PostgreSQL database.

The transaction-category-balance table is the runtime accrual ledger for
credit-card account balances broken down by transaction-type and
transaction-category — a normalized replacement for the mainframe VSAM
KSDS cluster ``TCATBALF``. It is consumed by:

* **Batch posting** (``CBTRN02C`` → ``src/batch/jobs/posttran_job.py``)
  to apply successful transaction postings by adding (or subtracting,
  per the transaction type) the transaction amount to the relevant
  category balance during Stage 1 of the nightly pipeline.
* **Interest calculation** (``CBACT04C`` →
  ``src/batch/jobs/intcalc_job.py``) to iterate all category balances
  for every account and apply the canonical interest formula
  ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` from the disclosure-group
  rate table — Stage 2 of the nightly pipeline.
* **Statement generation** (``CBSTM03A`` →
  ``src/batch/jobs/creastmt_job.py``) to emit per-category balance
  subtotals into the generated statements — Stage 4a of the nightly
  pipeline.

COBOL to Python Field Mapping
-----------------------------
==================  =================  ==================  =========================
COBOL Field         COBOL Type         Python Column       SQLAlchemy Type
==================  =================  ==================  =========================
TRANCAT-ACCT-ID     ``PIC 9(11)``      ``acct_id``         ``String(11)`` — PK #1
TRANCAT-TYPE-CD     ``PIC X(02)``      ``type_cd``         ``String(2)``  — PK #2
TRANCAT-CD          ``PIC 9(04)``      ``cat_cd``          ``String(4)``  — PK #3
TRAN-CAT-BAL        ``PIC S9(09)V99``  ``balance``         ``Numeric(15, 2)`` †
FILLER              ``PIC X(22)``      — (not mapped)      — (COBOL padding only)
==================  =================  ==================  =========================

† **Monetary field.** ``Numeric(15, 2)`` maps to PostgreSQL
  ``NUMERIC(15, 2)``: 15 total digits with exactly 2 decimal places,
  which accommodates the full signed range of COBOL ``PIC S9(09)V99``
  (11 digits total = 9 integer + 2 decimal) with comfortable headroom
  for any downstream widening of the monetary precision. All
  arithmetic on this field uses :class:`decimal.Decimal` with banker's
  rounding (``ROUND_HALF_EVEN``) to preserve the COBOL ``ROUNDED``
  clause semantics. Floating-point arithmetic is **never** permitted
  on this column (AAP §0.7.2 "Financial Precision").

Total RECLN: 11 + 2 + 4 + 11 + 22 = 50 bytes — matches the VSAM
cluster definition in ``app/jcl/TCATBALF.jcl`` (``RECSZ(50 50)``) and
the original COBOL copybook record length.

Composite Primary Key — ``TRAN-CAT-KEY``
----------------------------------------
The 3-part composite primary key ``(acct_id, type_cd, cat_cd)``
directly mirrors the COBOL group-level key ``TRAN-CAT-KEY``
(lines 5–8 of ``CVTRA01Y.cpy``), which is the full key used for
``READ``/``WRITE``/``REWRITE``/``DELETE`` operations against the
originating VSAM KSDS dataset ``TCATBALF``. Preserving the three
fields as composite PK components (rather than introducing a surrogate
integer ID) keeps the relational row addressable by the same logical
key as its VSAM ancestor — a prerequisite for behaviour-preserving
batch translation (see AAP §0.7.1 "Refactoring-Specific Rules" —
preserve existing business logic without modification).

The key ordering ``(acct_id, type_cd, cat_cd)`` is chosen deliberately:
the high-cardinality ``acct_id`` leads the key so that per-account
range scans (performed by both the posting and interest-calculation
jobs to fetch an account's full balance breakdown in a single
indexed read) are served by the primary key's B-tree without
requiring a secondary index.

Financial Precision — ``Numeric(15, 2)``
----------------------------------------
The ``balance`` column uses PostgreSQL ``NUMERIC(15, 2)`` — a
fixed-precision decimal with 15 total digits and 2 fractional digits.
This exactly represents (and provides headroom above) the COBOL
declaration ``PIC S9(09)V99`` (9 integer digits + 2 fractional
digits, implicit decimal point, signed). SQLAlchemy returns this
column as a ``decimal.Decimal`` value, never a Python ``float``.
Floating-point arithmetic is **forbidden** on any financial value in
the CardDemo application per AAP §0.7.2 "Financial Precision".

The column is declared ``nullable=False`` with
``default=Decimal("0.00")``. A missing balance would break the
downstream posting and interest-accrual formulas; defaulting to zero
yields a safe, arithmetically-neutral row when a brand-new
(account, type, category) combination is first written.

Interest-Calculation Consumer
-----------------------------
The stored balance is consumed by the canonical interest formula
from ``CBACT04C.cbl`` — which, per AAP §0.7.1 "Refactoring-Specific
Rules", must not be algebraically simplified::

    monthly_interest = (tran_cat_bal * dis_int_rate) / 1200

where ``tran_cat_bal`` is this column (``balance``) and ``dis_int_rate``
is the matching rate from ``disclosure_group.int_rate``. The literal
``1200`` — representing 12 months × 100 percent — is preserved as
specified in the AAP.

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models — enabling unified schema management, Alembic migrations,
  and ``Base.metadata.create_all()`` bootstrap.
* No ``FILLER`` column is mapped — the trailing 22 bytes of COBOL
  padding have no relational counterpart. In PostgreSQL, column
  widths are explicit and trailing padding has no storage or
  semantic meaning.
* ``TRANCAT-ACCT-ID`` is declared in COBOL as ``PIC 9(11)`` (unsigned
  numeric, 11 digits) but is mapped to ``String(11)`` in the
  relational model to preserve leading-zero formatting (e.g.,
  ``'00000000001'`` vs ``'1'``). This keeps the account-id
  semantically a label rather than a numeric quantity — consistent
  with ``Account.acct_id`` in the sibling model — and avoids lossy
  round-trips when joining against the ``account`` table.
* ``TRANCAT-CD`` is declared in COBOL as ``PIC 9(04)`` (unsigned
  numeric, 4 digits) but is mapped to ``String(4)`` in the
  relational model to preserve leading-zero formatting (e.g.,
  ``'0001'`` vs ``'1'``). This keeps the code semantically a label
  rather than a numeric quantity — consistent with
  ``DisclosureGroup.tran_cat_cd`` and ``TransactionCategory.cat_cd``
  in sibling models — and avoids lossy round-trips when joining
  against those tables.
* Column names (``acct_id``, ``type_cd``, ``cat_cd``, ``balance``)
  are specified in AAP §0.5.1 "File-by-File Transformation Plan"
  and intentionally drop the ``tran_`` prefix of the COBOL field
  names because within the ``transaction_category_balance`` table
  the ``tran_`` prefix is implicit in the table name itself.
* No relationships (``relationship()``) are declared on this entity
  to keep it free of back-references and avoid circular-import risk
  while the model package is being assembled file-by-file.
  Consumers that need to join on the composite key do so explicitly
  via the PK columns against ``account.acct_id``,
  ``transaction_type.tran_type``, and the composite
  ``(type_cd, cat_cd)`` of ``transaction_category``.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.2 — Batch Program Classification (POSTTRAN, INTCALC,
CREASTMT consumers of this table).
AAP §0.5.1 — File-by-File Transformation Plan
(``transaction_category_balance.py`` entry;
``posttran_job.py``/``intcalc_job.py``/``creastmt_job.py``
consumers).
AAP §0.7.1 — Refactoring-Specific Rules (preserve
``(balance × rate) / 1200`` formula unmodified; preserve the
3-part composite primary key matching ``TRAN-CAT-KEY``).
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on the
``balance`` column; no floating-point arithmetic permitted).
``app/cpy/CVTRA01Y.cpy`` — Original COBOL record layout (source artifact).
``app/jcl/TCATBALF.jcl`` — Original VSAM cluster definition and
provisioning job.
``app/data/ASCII/tcatbal.txt`` — Original seed-data fixture
(50 category-balance rows).
``app/cbl/CBTRN02C.cbl`` — Original COBOL transaction-posting program
(writer of this balance table).
``app/cbl/CBACT04C.cbl`` — Original COBOL interest-calculation program
(reader of this balance table).
``app/cbl/CBSTM03A.CBL`` — Original COBOL statement-generation program
(reader of this balance table for per-category subtotals).
``src.shared.models.disclosure_group.DisclosureGroup`` — Rate-lookup
table that pairs with this balance row via
``(tran_type_cd, tran_cat_cd)`` during interest calculation.
``src.shared.models.transaction_category.TransactionCategory`` —
Reference-data table defining the valid ``(type_cd, cat_cd)`` pairs
that can appear in this balance table.
``src.shared.models.account.Account`` — Parent account record for
the ``acct_id`` composite-key component.
"""

from decimal import Decimal

from sqlalchemy import Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class TransactionCategoryBalance(Base):
    """ORM entity for ``transaction_category_balance`` (from COBOL ``TRAN-CAT-BAL-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``transaction_category_balance`` table, which replaces the
    mainframe VSAM KSDS ``TCATBALF`` dataset. Each row records the
    running balance for a single
    ``(account, transaction-type, transaction-category)`` tuple and
    is read/written by:

    * **Stage 1 — POSTTRAN** (``CBTRN02C`` →
      ``posttran_job.py``). For every valid posting, the
      transaction amount is added (or subtracted per type) to the
      corresponding ``(acct_id, type_cd, cat_cd)`` row's
      ``balance``, creating the row with a zero default if it does
      not already exist.
    * **Stage 2 — INTCALC** (``CBACT04C`` → ``intcalc_job.py``).
      For each row, the monthly interest is computed as
      ``(balance × disclosure_group.int_rate) / 1200`` and applied
      to the account's current balance. The literal ``1200`` — 12
      months × 100 percent — is preserved verbatim in the batch
      code per AAP §0.7.1.
    * **Stage 4a — CREASTMT** (``CBSTM03A`` →
      ``creastmt_job.py``). Per-category balance subtotals are
      rolled up per account and rendered into the generated
      statement (text and HTML variants).

    Attributes
    ----------
    acct_id : str
        **Primary key part 1 of 3.** 11-character account identifier
        (from COBOL ``TRANCAT-ACCT-ID``, ``PIC 9(11)``). Stored as
        ``String(11)`` with leading zeros preserved (e.g.,
        ``'00000000001'``) to match the fixed-width label semantics
        of ``Account.acct_id`` for clean composite-key joins.
    type_cd : str
        **Primary key part 2 of 3.** 2-character transaction-type
        code (from COBOL ``TRANCAT-TYPE-CD``, ``PIC X(02)``).
        Logically references the ``transaction_type.tran_type``
        reference-data table (e.g., ``'01'`` = Purchase,
        ``'02'`` = Payment, ``'03'`` = Credit, ``'04'`` = Debit,
        ``'05'`` = Refund, ``'06'`` = Adjustment, ``'07'`` = Fee).
    cat_cd : str
        **Primary key part 3 of 3.** 4-character transaction-category
        code (from COBOL ``TRANCAT-CD``, ``PIC 9(04)``). Stored as a
        fixed-width string with leading zeros (e.g., ``'0001'``,
        ``'0002'``) rather than as an integer to preserve the COBOL
        label semantics and maintain parity with
        ``DisclosureGroup.tran_cat_cd`` and
        ``TransactionCategory.cat_cd`` for clean composite-key
        joins.
    balance : Decimal
        Running balance for this (account, type, category) tuple
        (from COBOL ``TRAN-CAT-BAL``, ``PIC S9(09)V99`` — 9 integer
        digits + 2 fractional digits, signed). Stored as PostgreSQL
        ``NUMERIC(15, 2)`` and returned as a ``decimal.Decimal``
        value — **never** as a Python ``float`` — so that the
        canonical interest formula ``(balance × int_rate) / 1200``
        from ``CBACT04C.cbl`` preserves exact decimal precision.
        Defaults to ``Decimal("0.00")`` for safe arithmetic-neutral
        semantics when a brand-new ``(acct_id, type_cd, cat_cd)``
        combination is first written by the POSTTRAN job.
    """

    __tablename__ = "transaction_category_balance"

    # ------------------------------------------------------------------
    # Primary key part 1 of 3: account identifier
    # (COBOL ``TRANCAT-ACCT-ID`` PIC 9(11))
    #
    # Stored as String(11) — NOT as an integer — to preserve leading-
    # zero formatting (e.g., '00000000001') consistent with
    # ``Account.acct_id``. This keeps the column semantically a
    # fixed-width label rather than a numeric quantity and avoids
    # lossy round-trips when joining against the ``account`` table.
    # ------------------------------------------------------------------
    acct_id: Mapped[str] = mapped_column(
        String(11),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Primary key part 2 of 3: transaction-type code
    # (COBOL ``TRANCAT-TYPE-CD`` PIC X(02))
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
    # Primary key part 3 of 3: transaction-category code
    # (COBOL ``TRANCAT-CD`` PIC 9(04))
    #
    # Stored as String(4) with leading zeros (e.g. '0001') rather than
    # as an integer — preserves the COBOL label semantics and matches
    # the type used by ``TransactionCategory.cat_cd`` and
    # ``DisclosureGroup.tran_cat_cd`` for clean composite-key joins.
    # Together with ``type_cd`` this forms a logical FK to
    # ``transaction_category.(type_cd, cat_cd)``.
    # ------------------------------------------------------------------
    cat_cd: Mapped[str] = mapped_column(
        String(4),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Running balance (COBOL ``TRAN-CAT-BAL`` PIC S9(09)V99)
    #
    # COBOL picture breakdown:
    #   S       — signed
    #   9(09)   — 9 integer digits
    #   V       — implicit decimal point
    #   99      — 2 fractional digits
    # Total digits: 9 + 2 = 11  →  PostgreSQL NUMERIC(15, 2)
    #   (15 digits gives generous headroom above the COBOL 11-digit
    #   maximum for any downstream widening of the monetary range.)
    #
    # FINANCIAL-PRECISION CRITICAL (AAP §0.7.2):
    #   * SQLAlchemy returns this column as ``decimal.Decimal``.
    #   * Do NOT cast to ``float`` at any point in the posting,
    #     interest-calculation, or statement-generation pipelines —
    #     floating-point arithmetic is forbidden for financial
    #     calculations.
    #   * Default ``Decimal("0.00")`` ensures a new
    #     ``(acct_id, type_cd, cat_cd)`` row — created when POSTTRAN
    #     encounters a never-before-seen category combination for
    #     an account — is arithmetic-neutral and produces correct
    #     posting/interest results from the first transaction on.
    # ------------------------------------------------------------------
    balance: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # Note: COBOL ``FILLER PIC X(22)`` — the trailing 22 bytes of
    # padding in the original 50-byte VSAM record — is deliberately NOT
    # mapped. In the relational model, column widths are explicit and
    # trailing padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        Includes all four mapped columns (the 3-part composite
        primary key and the running balance). The ``balance`` is
        formatted via ``repr()`` which preserves full ``Decimal``
        precision — no rounding, no scientific notation, and no
        implicit conversion to ``float``.

        Returns
        -------
        str
            Representation of the form
            ``TransactionCategoryBalance(acct_id='00000000001',
            type_cd='01', cat_cd='0001',
            balance=Decimal('1234.56'))``.
        """
        return (
            f"TransactionCategoryBalance("
            f"acct_id={self.acct_id!r}, "
            f"type_cd={self.type_cd!r}, "
            f"cat_cd={self.cat_cd!r}, "
            f"balance={self.balance!r}"
            f")"
        )
