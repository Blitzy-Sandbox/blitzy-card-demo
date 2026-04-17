# ============================================================================
# Source: COBOL copybook CVTRA02Y.cpy — DIS-GROUP-RECORD (RECLN 50)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Contains DEFAULT and ZEROAPR disclosure groups for interest calculation
# fallback. Used by the INTCALC batch job (``CBACT04C`` →
# ``src/batch/jobs/intcalc_job.py``) as the rate-lookup table for the
# canonical interest formula ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200``.
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
"""SQLAlchemy 2.x ORM model for the ``disclosure_group`` table.

Converts the COBOL copybook ``app/cpy/CVTRA02Y.cpy`` (record layout
``DIS-GROUP-RECORD``, 50-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a disclosure-group (interest-rate
disclosure) row in the CardDemo Aurora PostgreSQL database.

The disclosure-group table is a reference-data table that defines the
applicable interest rate for every combination of account group,
transaction type, and transaction category. It is the rate-lookup
table consumed by the interest-calculation batch stage (Stage 2 of the
batch pipeline, ``INTCALC``).

COBOL to Python Field Mapping
-----------------------------
====================  ================  ====================  =========================
COBOL Field           COBOL Type        Python Column         SQLAlchemy Type
====================  ================  ====================  =========================
DIS-ACCT-GROUP-ID     ``PIC X(10)``     ``acct_group_id``     ``String(10)`` — PK #1
DIS-TRAN-TYPE-CD      ``PIC X(02)``     ``tran_type_cd``      ``String(2)``  — PK #2
DIS-TRAN-CAT-CD       ``PIC 9(04)``     ``tran_cat_cd``       ``String(4)``  — PK #3
DIS-INT-RATE          ``PIC S9(04)V99`` ``int_rate``          ``Numeric(6, 2)``
FILLER                ``PIC X(28)``     — (not mapped)        — (COBOL padding only)
====================  ================  ====================  =========================

Total RECLN: 10 + 2 + 4 + 6 + 28 = 50 bytes — matches the VSAM cluster
definition in ``app/jcl/DISCGRP.jcl`` (``RECSZ(50 50)``).

Composite Primary Key — ``DIS-GROUP-KEY``
-----------------------------------------
The 3-part composite primary key ``(acct_group_id, tran_type_cd,
tran_cat_cd)`` directly mirrors the COBOL group-level key
``DIS-GROUP-KEY`` (lines 3–5 of ``CVTRA02Y.cpy``), which is the full
key used for ``READ``/``WRITE``/``REWRITE`` operations against the
VSAM KSDS ``DISCGRP`` dataset. Preserving the three fields as PK
components (rather than introducing a surrogate integer ID) keeps the
relational row addressable by the same logical key as its VSAM
ancestor — a prerequisite for behaviour-preserving batch
translation.

Disclosure-Group Semantics — DEFAULT / ZEROAPR Fallback
-------------------------------------------------------
The ``acct_group_id`` column is a 10-character account-group code.
Two sentinel values are special-cased by the interest-calculation
logic (see ``CBACT04C.cbl`` → ``intcalc_job.py``):

* ``'DEFAULT   '`` — The baseline disclosure group applied to any
  account whose explicit ``acct_group_id`` is not found. This row
  provides the default interest rate across all
  ``(tran_type_cd, tran_cat_cd)`` combinations.
* ``'ZEROAPR   '`` — A zero-percent APR override disclosure group.
  Accounts enrolled in a promotional zero-APR plan receive this
  ``acct_group_id`` and their interest is computed against the
  zero-rate rows in this table (``int_rate = 0.00``) — yielding no
  interest accrual even when balances are non-zero.

Both strings are **blank-padded to 10 characters** because COBOL
``PIC X(10)`` stores fixed-width right-padded text; the application
logic matches on the padded form rather than the stripped form, so
fixture data and Python comparisons must use the padded form
(``'DEFAULT   '``, not ``'DEFAULT'``).

Financial Precision — ``Numeric(6, 2)``
---------------------------------------
The ``int_rate`` column uses PostgreSQL ``NUMERIC(6, 2)`` — a
fixed-precision decimal with 6 total digits and 2 fractional digits.
This exactly matches the COBOL declaration ``PIC S9(04)V99`` (4
integer digits + 2 fractional digits, implicit decimal point,
signed). SQLAlchemy returns this column as a ``decimal.Decimal``
value, never a Python ``float``. Floating-point arithmetic is
**forbidden** for any financial value in the CardDemo application
per AAP §0.7.2 "Financial Precision".

Interest-Calculation Formula
----------------------------
This rate is consumed by the canonical interest-calculation formula
from ``CBACT04C.cbl`` (must not be algebraically simplified per AAP
§0.7.1 "Refactoring-Specific Rules")::

    monthly_interest = (tran_cat_bal * int_rate) / 1200

where ``1200`` represents 12 months × 100 percent (the annual rate
is divided by 100 to convert from percent to fraction, and by 12 to
produce the monthly increment). The literal ``1200`` is preserved as
specified in the AAP — it must not be expressed as ``(12 * 100)`` or
any other algebraic rearrangement.

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models.
* No ``FILLER`` column is mapped — the trailing 28 bytes of COBOL
  padding have no relational counterpart. In PostgreSQL, column
  widths are explicit and trailing padding has no storage or
  semantic meaning.
* ``DIS-TRAN-CAT-CD`` is declared in COBOL as ``PIC 9(04)`` (unsigned
  numeric, 4 digits) but is mapped to ``String(4)`` in the relational
  model to preserve leading-zero formatting (e.g., ``'0001'`` vs
  ``'1'``). This keeps the code semantically a label rather than a
  numeric quantity — consistent with ``TransactionCategory.tran_cat_cd``
  and ``TransactionCategoryBalance.tran_cat_cd`` in sibling models,
  and avoids lossy round-trips when joining against those tables.
* The ``int_rate`` column is declared ``nullable=False`` with
  ``default=Decimal("0.00")``. A missing rate would break the
  interest formula; defaulting to zero yields a safe, non-accruing
  row consistent with the ZEROAPR semantics.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.2 — Batch Program Classification (INTCALC → CBACT04C).
AAP §0.5.1 — File-by-File Transformation Plan
(``disclosure_group.py`` entry; ``intcalc_job.py`` consumer).
AAP §0.7.1 — Refactoring-Specific Rules (preserve ``(balance × rate)
/ 1200`` formula unmodified).
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on monetary
and rate fields; no floating-point arithmetic permitted).
``app/cpy/CVTRA02Y.cpy`` — Original COBOL record layout (source artifact).
``app/jcl/DISCGRP.jcl`` — Original VSAM cluster definition and load job.
``app/data/ASCII/discgrp.txt`` — Original seed-data fixture
(51 rows across 3 disclosure-group blocks).
``app/cbl/CBACT04C.cbl`` — Original COBOL interest-calculation program
(consumer of this rate table).
"""

from decimal import Decimal

from sqlalchemy import Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class DisclosureGroup(Base):
    """ORM entity for the ``disclosure_group`` table (from COBOL ``DIS-GROUP-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``disclosure_group`` reference table, which replaces the mainframe
    VSAM KSDS ``DISCGRP`` dataset. Each row defines the applicable
    interest rate for a unique combination of
    ``(acct_group_id, tran_type_cd, tran_cat_cd)`` and is consumed by
    the Stage 2 interest-calculation batch job (``CBACT04C`` →
    ``intcalc_job.py``).

    The table holds both default (``'DEFAULT   '``) and promotional
    zero-APR (``'ZEROAPR   '``) disclosure groups used as fallback
    rates by the interest-calculation cascade when an account's
    explicit disclosure group is absent or overridden.

    Attributes
    ----------
    acct_group_id : str
        **Primary key part 1 of 3.** 10-character account-group
        identifier (from COBOL ``DIS-ACCT-GROUP-ID``, ``PIC X(10)``).
        Special sentinel values ``'DEFAULT   '`` and ``'ZEROAPR   '``
        (blank-padded to 10 characters) are recognized by the interest
        calculator for fallback and zero-APR behaviour, respectively.
    tran_type_cd : str
        **Primary key part 2 of 3.** 2-character transaction-type
        code (from COBOL ``DIS-TRAN-TYPE-CD``, ``PIC X(02)``).
        References the ``transaction_type.tran_type`` reference-data
        table (e.g., ``'01'`` = Purchase, ``'02'`` = Payment).
    tran_cat_cd : str
        **Primary key part 3 of 3.** 4-character transaction-category
        code (from COBOL ``DIS-TRAN-CAT-CD``, ``PIC 9(04)``). Stored
        as a fixed-width string with leading zeros (e.g., ``'0001'``)
        to preserve the COBOL label semantics and maintain parity
        with ``TransactionCategory.tran_cat_cd``.
    int_rate : Decimal
        Interest rate for this disclosure-group / transaction-type /
        transaction-category combination (from COBOL
        ``DIS-INT-RATE``, ``PIC S9(04)V99`` — 4 integer digits + 2
        fractional digits, signed). Stored as PostgreSQL
        ``NUMERIC(6, 2)`` and returned as a ``decimal.Decimal``
        value — never as a Python ``float`` — so that the canonical
        interest formula ``(tran_cat_bal × int_rate) / 1200`` from
        ``CBACT04C.cbl`` preserves exact decimal precision. Defaults
        to ``Decimal("0.00")`` for safe fallback semantics.
    """

    __tablename__ = "disclosure_group"

    # ------------------------------------------------------------------
    # Primary key part 1 of 3: account-group identifier
    # (COBOL ``DIS-ACCT-GROUP-ID`` PIC X(10))
    #
    # Special sentinel values recognized by interest-calculation logic:
    #   'DEFAULT   ' — baseline fallback disclosure group (10 chars,
    #                  blank-padded — COBOL PIC X(10) semantics)
    #   'ZEROAPR   ' — promotional zero-APR override disclosure group
    # ------------------------------------------------------------------
    acct_group_id: Mapped[str] = mapped_column(
        String(10),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Primary key part 2 of 3: transaction-type code
    # (COBOL ``DIS-TRAN-TYPE-CD`` PIC X(02))
    #
    # References ``transaction_type.tran_type``. Typical values:
    #   '01' Purchase · '02' Payment · '03' Credit · '04' Debit
    #   '05' Refund   · '06' Adjustment · '07' Fee
    # ------------------------------------------------------------------
    tran_type_cd: Mapped[str] = mapped_column(
        String(2),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Primary key part 3 of 3: transaction-category code
    # (COBOL ``DIS-TRAN-CAT-CD`` PIC 9(04))
    #
    # Stored as String(4) with leading zeros (e.g. '0001') rather than
    # as an integer — preserves the COBOL label semantics and matches
    # the type used by ``TransactionCategory.tran_cat_cd`` and
    # ``TransactionCategoryBalance.tran_cat_cd`` for clean joins.
    # ------------------------------------------------------------------
    tran_cat_cd: Mapped[str] = mapped_column(
        String(4),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Interest rate (COBOL ``DIS-INT-RATE`` PIC S9(04)V99)
    #
    # COBOL picture breakdown:
    #   S       — signed
    #   9(04)   — 4 integer digits
    #   V       — implicit decimal point
    #   99      — 2 fractional digits
    # Total digits: 4 + 2 = 6  →  PostgreSQL NUMERIC(6, 2)
    #
    # FINANCIAL-PRECISION CRITICAL (AAP §0.7.2):
    #   * SQLAlchemy returns this column as ``decimal.Decimal``.
    #   * Do NOT cast to ``float`` at any point in the interest-
    #     calculation pipeline — floating-point arithmetic is
    #     forbidden for financial calculations.
    #   * Default ``Decimal("0.00")`` ensures the canonical formula
    #     ``(tran_cat_bal × int_rate) / 1200`` yields a safe,
    #     non-accruing result when a rate row is newly inserted
    #     without an explicit value (consistent with ZEROAPR
    #     fallback semantics).
    # ------------------------------------------------------------------
    int_rate: Mapped[Decimal] = mapped_column(
        Numeric(6, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # Note: COBOL ``FILLER PIC X(28)`` — the trailing 28 bytes of
    # padding in the original 50-byte VSAM record — is deliberately NOT
    # mapped. In the relational model, column widths are explicit and
    # trailing padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        Includes all four mapped columns (the 3-part composite primary
        key and the interest rate). The ``int_rate`` is formatted via
        ``repr()`` which preserves full ``Decimal`` precision — no
        rounding, no scientific notation, and no implicit conversion
        to ``float``.

        Returns
        -------
        str
            Representation of the form
            ``DisclosureGroup(acct_group_id='DEFAULT   ',
            tran_type_cd='01', tran_cat_cd='0001',
            int_rate=Decimal('18.50'))``.
        """
        return (
            f"DisclosureGroup("
            f"acct_group_id={self.acct_group_id!r}, "
            f"tran_type_cd={self.tran_type_cd!r}, "
            f"tran_cat_cd={self.tran_cat_cd!r}, "
            f"int_rate={self.int_rate!r}"
            f")"
        )
