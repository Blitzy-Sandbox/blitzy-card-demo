# ============================================================================
# Source: COBOL copybook CVTRA05Y.cpy — TRAN-RECORD (RECLN 350)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Authoritative credit-card transaction history table for the CardDemo
# application. Replaces the mainframe ``TRANFILE`` VSAM KSDS cluster
# (primary-key access on ``TRAN-ID``) and its alternate index (AIX)
# ``TRANFILE.AIX`` keyed on ``TRAN-PROC-TS`` supporting date-range
# queries.
#
# All monetary columns use PostgreSQL ``NUMERIC(15, 2)`` to preserve the
# exact COBOL ``PIC S9(09)V99`` decimal semantics — no floating-point
# arithmetic is permitted on these fields (see AAP §0.7.2 "Financial
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
"""SQLAlchemy 2.x ORM model for the ``transaction`` table.

Converts the COBOL copybook ``app/cpy/CVTRA05Y.cpy`` (record layout
``TRAN-RECORD``, 350-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a single posted credit-card
transaction in the CardDemo Aurora PostgreSQL database.

Purpose — The Authoritative Transaction History Ledger
-------------------------------------------------------
This entity is the **authoritative** transaction ledger for the
CardDemo application — every successfully-posted transaction is stored
exactly once in this table for the entire life of the account. It is
the cloud-native replacement for the mainframe ``TRANFILE`` VSAM KSDS
cluster (RECLN 350), together with its alternate index (AIX)
``TRANFILE.AIX`` on ``TRAN-PROC-TS``.

Contrast with ``daily_transaction``: the daily-batch staging table
(``DailyTransaction``, from ``CVTRA06Y.cpy``) holds **unvalidated
incoming** records for the current day; this ``transaction`` table
holds the **validated, posted** history. A record transitions from
the former to the latter only after the 4-stage validation cascade
in the POSTTRAN Glue job (``posttran_job.py``, derived from
``CBTRN02C``) succeeds.

Producers and Consumers
-----------------------
Producers (write the ``transaction`` table):

* ``posttran_job.py`` (from ``CBTRN02C``) — INSERTs each approved
  daily-batch row after the 4-stage validation cascade
  (reject codes 100-109) passes. The POSTTRAN job also applies the
  matching debit/credit to ``Account.curr_bal`` and increments the
  relevant cycle-credit or cycle-debit column in the same database
  transaction (dual-write pattern, see AAP §0.7.1).
* ``bill_service.py`` (from ``COBIL00C``) — INSERTs a single bill
  payment transaction while simultaneously updating the account
  balance in the same database transaction (atomic dual-write).
* ``transaction_service.py`` (from ``COTRN02C``) — INSERTs
  transactions added interactively through the online "Transaction
  Add" screen. Auto-assigns the next ``tran_id`` and resolves the
  ``card_num`` via ``card_cross_reference`` to attach the owning
  account.

Consumers (read the ``transaction`` table):

* ``creastmt_job.py`` (from ``CBSTM03A.CBL`` / ``CBSTM03B.CBL``) —
  Stage 4a of the batch pipeline. Reads all transactions within a
  billing cycle to produce customer statements (text + HTML) using
  a 4-entity join (``Account`` × ``Customer`` ×
  ``CardCrossReference`` × ``Transaction``). Uses the
  ``ix_transaction_proc_ts`` B-tree index for efficient date-range
  filtering.
* ``tranrept_job.py`` (from ``CBTRN03C``) — Stage 4b of the batch
  pipeline. Produces date-filtered transaction reports with 3-level
  subtotals (account / card / transaction-type). Also uses the
  ``ix_transaction_proc_ts`` index.
* ``transaction_service.py`` (from ``COTRN00C`` / ``COTRN01C``) —
  Serves the online "Transaction List" (10 rows per page) and
  "Transaction Detail" screens by primary-key lookups and paginated
  scans.

COBOL to Python Field Mapping
-----------------------------
=========================  =================  =====================  =====================
COBOL Field                COBOL Type         Python Column          SQLAlchemy Type
=========================  =================  =====================  =====================
TRAN-ID                    ``PIC X(16)``      ``tran_id``            ``String(16)`` — PK
TRAN-TYPE-CD               ``PIC X(02)``      ``type_cd``            ``String(2)``
TRAN-CAT-CD                ``PIC 9(04)``      ``cat_cd``             ``String(4)``
TRAN-SOURCE                ``PIC X(10)``      ``source``             ``String(10)``
TRAN-DESC                  ``PIC X(100)``     ``description``        ``String(100)``
TRAN-AMT                   ``PIC S9(09)V99``  ``amount``             ``Numeric(15, 2)`` †
TRAN-MERCHANT-ID           ``PIC 9(09)``      ``merchant_id``        ``String(9)``
TRAN-MERCHANT-NAME         ``PIC X(50)``      ``merchant_name``      ``String(50)``
TRAN-MERCHANT-CITY         ``PIC X(50)``      ``merchant_city``      ``String(50)``
TRAN-MERCHANT-ZIP          ``PIC X(10)``      ``merchant_zip``       ``String(10)``
TRAN-CARD-NUM              ``PIC X(16)``      ``card_num``           ``String(16)``
TRAN-ORIG-TS               ``PIC X(26)``      ``orig_ts``            ``String(26)``
TRAN-PROC-TS               ``PIC X(26)``      ``proc_ts``            ``String(26)`` ‡
FILLER                     ``PIC X(20)``      — (not mapped)         — (COBOL padding only)
=========================  =================  =====================  =====================

† **Monetary field.** ``Numeric(15, 2)`` maps to PostgreSQL
  ``NUMERIC(15, 2)``: 15 total digits with exactly 2 decimal places,
  which accommodates the full signed range of COBOL ``PIC S9(09)V99``
  (11 integer digits at storage + 2 decimals) with headroom. All
  financial arithmetic on this field uses :class:`decimal.Decimal`
  with banker's rounding (``ROUND_HALF_EVEN``) to preserve the COBOL
  ``ROUNDED`` clause semantics. Floating-point arithmetic is **never**
  permitted (AAP §0.7.2).

‡ **Indexed.** ``proc_ts`` is indexed by ``ix_transaction_proc_ts``
  (B-tree) to replicate the VSAM alternate index (AIX)
  ``TRANFILE.AIX``. This index supports date-range queries used by
  CREASTMT (statement generation) and TRANREPT (transaction reports)
  — see the "Indexes" section below.

Total RECLN (COBOL): 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 +
16 + 26 + 26 + 20 (FILLER) = **350 bytes**, matching the documented
``CVTRA05Y.cpy`` record length. The 20-byte trailing FILLER is
deliberately NOT mapped — trailing padding has no storage meaning in
a relational database.

Indexes
-------
``ix_transaction_proc_ts`` (non-unique B-tree on ``proc_ts``)
    Replicates the mainframe ``TRANFILE.AIX`` VSAM alternate index on
    ``TRAN-PROC-TS``. Supports efficient date-range queries issued by
    the batch jobs ``creastmt_job.py`` (CREASTMT) and
    ``tranrept_job.py`` (TRANREPT) that filter by processing-timestamp
    window (typically one billing cycle or one business day). Without
    this index, those queries would perform a full-table scan of the
    authoritative transaction history — prohibitively expensive once
    the ledger grows past a few million rows.

    The index is declared non-unique because multiple transactions can
    share the same 26-character processing timestamp when posted within
    the same microsecond (and, historically, the COBOL 26-character
    display format does not encode tie-breakers beyond microseconds).
    The primary key on ``tran_id`` already enforces the identity
    uniqueness invariant.

Monetary Precision Contract
---------------------------
The single monetary field ``amount`` maps to PostgreSQL
``NUMERIC(15, 2)`` and is typed on the Python side as
:class:`decimal.Decimal`. The default value is ``Decimal("0.00")``
(never ``0`` or ``0.0``) to guarantee two-decimal-place storage on
INSERT when the caller omits an explicit value.

This preserves the COBOL ``PIC S9(09)V99`` financial semantics
required by:

* ``CBTRN02C`` / ``posttran_job.py`` — reads ``daily_transaction``,
  validates each row through the 4-stage cascade, and writes approved
  rows here while simultaneously updating ``Account.curr_bal``,
  ``Account.curr_cyc_credit``, and ``Account.curr_cyc_debit`` in the
  same database transaction.
* ``CBACT04C`` / ``intcalc_job.py`` (INTCALC) — reads interest-rate
  disclosure groups, computes monthly interest as
  ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` (formula preserved
  literally per AAP §0.7.1, no algebraic simplification), and posts
  the result as an interest transaction row here.
* ``COBIL00C`` / ``bill_service.py`` — writes a bill-payment
  transaction and applies the matching credit to the account balance
  in a single atomic database transaction (dual-write pattern).

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models (``Account``, ``Card``, ``Customer``, ``DailyTransaction``,
  ``UserSecurity``, etc.).
* COBOL ``PIC 9(n)`` numeric-ID fields are stored as ``String(n)`` —
  not integer types — to preserve leading zeros from migrated VSAM
  records byte-for-byte. This is the same convention used by
  ``Account.acct_id`` (``PIC 9(11)`` → ``String(11)``),
  ``Customer.cust_id`` (``PIC 9(09)`` → ``String(9)``), etc.
* Timestamp columns (``orig_ts``, ``proc_ts``) are ``String(26)`` —
  they preserve the COBOL ``PIC X(26)`` display-format timestamps
  verbatim (``YYYY-MM-DD-HH.MM.SS.NNNNNN``) so that round-trip
  fidelity to the VSAM source is exact. Parsing to ``datetime``
  occurs in the application / PySpark layers, not the schema layer.
* No FILLER byte is mapped — see the table above.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (transaction CRUD
    endpoints and their COBOL origins).
AAP §0.5.1 — File-by-File Transformation Plan
    (``transaction.py`` entry).
AAP §0.7.1 — Refactoring-Specific Rules (interest formula preservation,
    dual-write patterns, minimal-change clause).
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on ``amount``).
``app/cpy/CVTRA05Y.cpy`` — Original COBOL record layout
    (source artifact, retained for traceability).
``app/cbl/CBTRN02C.cbl`` — Transaction posting engine (POSTTRAN —
    primary producer of rows in this table).
``app/cbl/CBACT04C.cbl`` — Interest calculation job (INTCALC —
    writes interest accrual rows).
``app/cbl/CBSTM03A.CBL`` / ``CBSTM03B.CBL`` — Statement generation
    (CREASTMT — consumer using ``proc_ts`` index).
``app/cbl/CBTRN03C.cbl`` — Transaction reporting (TRANREPT —
    consumer using ``proc_ts`` index).
``app/cbl/COBIL00C.cbl`` — Bill payment (interactive producer via
    atomic dual-write with ``Account.curr_bal``).
``app/cbl/COTRN00C.cbl`` / ``COTRN01C.cbl`` / ``COTRN02C.cbl`` —
    Online transaction list / detail / add endpoints.
``app/jcl/TRANFILE.jcl`` — Original VSAM provisioning JCL
    (replaced by ``db/migrations/V1__schema.sql``).
``app/jcl/TRANIDX.jcl`` — Original VSAM AIX provisioning JCL
    (replaced by ``db/migrations/V2__indexes.sql`` and the
    ``__table_args__`` Index declaration below).
"""

from decimal import Decimal

from sqlalchemy import Index, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class Transaction(Base):
    """ORM entity for the ``transaction`` table (from COBOL ``TRAN-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``transaction`` table — the authoritative, permanent history of
    every successfully-posted credit-card transaction for the life of
    each account. The table replaces the mainframe ``TRANFILE`` VSAM
    KSDS cluster (primary-key access on ``TRAN-ID``) together with its
    alternate index ``TRANFILE.AIX`` on ``TRAN-PROC-TS`` (cloud-native
    replacement realised as the ``ix_transaction_proc_ts`` B-tree
    index declared on this model).

    Lifecycle of a ``Transaction`` row:

    1. **Origination** — An incoming daily-batch record lands first in
       the ``daily_transaction`` staging table (see ``DailyTransaction``
       for semantics). For interactive sources, a POS terminal / ATM /
       online portal submits the transaction via the
       ``POST /transactions`` REST endpoint (``COTRN02C`` →
       ``transaction_service.py``), which validates and persists
       directly here.
    2. **Posting (batch path)** — ``posttran_job.py`` (derived from
       ``CBTRN02C``) reads every row of ``daily_transaction`` and
       applies the 4-stage validation cascade (reject codes 100-109:
       card not active, card expired, over credit limit, invalid
       cross-reference, etc.). On success, the row is INSERTed here
       and the ``Account`` balance columns (``curr_bal``,
       ``curr_cyc_credit``, ``curr_cyc_debit``) are atomically updated
       in the same database transaction. On failure, the row is
       written to the daily-reject S3 prefix (replacing the mainframe
       ``DALYREJS`` GDG) and is **never** inserted here.
    3. **Interest accrual (batch path)** — ``intcalc_job.py``
       (derived from ``CBACT04C``) computes monthly interest for each
       ``TransactionCategoryBalance`` as
       ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` (formula preserved
       literally per AAP §0.7.1, no algebraic simplification) and
       posts the resulting interest transaction as a new row here.
    4. **Reporting** — ``creastmt_job.py`` (CREASTMT, billing cycle
       statements) and ``tranrept_job.py`` (TRANREPT, date-range
       reports) read rows filtered by ``proc_ts`` window using the
       ``ix_transaction_proc_ts`` index.
    5. **Online display** — ``transaction_service.py`` (from
       ``COTRN00C`` / ``COTRN01C``) serves the paginated transaction
       list (10 rows per page) and detail-view endpoints.

    The 13 column layout mirrors ``DailyTransaction`` exactly (same
    PIC types, same field sizes, same logical semantics) so that a
    successful POSTTRAN validation can copy the daily record here
    field-for-field without any reshaping. The two modelling
    differences between this authoritative table and the staging table
    are:

    * **``ix_transaction_proc_ts`` B-tree index.** Declared on this
      model (via ``__table_args__``) to support date-range scans from
      CREASTMT and TRANREPT. The staging table does not carry this
      index — its access pattern is full-scan within one batch run,
      so the secondary index would add write overhead without query
      benefit.
    * **Retention.** ``transaction`` rows persist indefinitely (entire
      account history). ``daily_transaction`` rows are truncated at
      the end of each daily batch.

    Attributes
    ----------
    tran_id : str
        **Primary key.** 16-character transaction ID (from COBOL
        ``TRAN-ID``, ``PIC X(16)``). Auto-assigned by
        ``transaction_service.py`` for online additions (derived from
        ``COTRN02C`` behaviour — next sequential ID) or propagated
        from the daily-batch staging row for batch-posted
        transactions. Uniqueness is enforced at the database level.
    type_cd : str
        2-character transaction-type code (from COBOL
        ``TRAN-TYPE-CD``, ``PIC X(02)``). Logical foreign key into
        the ``transaction_type`` lookup table. Typical values include
        purchase, refund, payment, cash-advance, fee, and interest.
        Validated by POSTTRAN (reject code 103 — unknown type code).
    cat_cd : str
        4-character transaction-category code (from COBOL
        ``TRAN-CAT-CD``, ``PIC 9(04)``). Stored as ``String(4)`` —
        not numeric — so leading zeros from migrated records are
        preserved byte-for-byte. Logical foreign key into the
        ``transaction_category`` lookup table (composite key with
        ``type_cd``). Determines which ``transaction_category_balance``
        bucket is updated during POSTTRAN and which interest rate
        applies during INTCALC (via the ``disclosure_group`` table).
    source : str
        10-character transaction-source code (from COBOL
        ``TRAN-SOURCE``, ``PIC X(10)``). Identifies the upstream
        system that produced the record (e.g., ``'POS'``,
        ``'ATM'``, ``'ONLINE'``, ``'BATCH'``). Primarily used for
        audit, reconciliation, and transaction reporting.
    description : str
        Up to 100-character transaction description (from COBOL
        ``TRAN-DESC``, ``PIC X(100)``). Free-text description printed
        on statements (CREASTMT) and transaction reports (TRANREPT).
        Defaults to empty string.
    amount : decimal.Decimal
        **Monetary.** Transaction amount (from COBOL
        ``TRAN-AMT``, ``PIC S9(09)V99``). Stored as
        ``NUMERIC(15, 2)`` with default ``Decimal("0.00")``. Sign
        convention follows COBOL ``S9(...)`` — positive for debits
        (purchases, fees, cash advances, interest accruals) and
        negative for credits (payments, refunds). Summed into the
        matching ``TransactionCategoryBalance`` bucket and applied
        to ``Account.curr_bal`` during POSTTRAN. Never represented
        as a floating-point number (AAP §0.7.2).
    merchant_id : str
        9-character merchant ID (from COBOL ``TRAN-MERCHANT-ID``,
        ``PIC 9(09)``). Stored as ``String(9)`` to preserve leading
        zeros. Logical foreign key into the external merchant
        directory (not a CardDemo-owned table). Defaults to empty
        string for non-merchant transactions (e.g., payments, fees,
        interest accruals).
    merchant_name : str
        Up to 50-character merchant name (from COBOL
        ``TRAN-MERCHANT-NAME``, ``PIC X(50)``). Denormalised copy of
        the merchant-directory name snapshot, captured at transaction
        time so that downstream statements and reports render the
        historical name even if the directory is later updated.
        Defaults to empty string.
    merchant_city : str
        Up to 50-character merchant city (from COBOL
        ``TRAN-MERCHANT-CITY``, ``PIC X(50)``). Denormalised like
        ``merchant_name``. Defaults to empty string.
    merchant_zip : str
        Up to 10-character merchant ZIP/postal code (from COBOL
        ``TRAN-MERCHANT-ZIP``, ``PIC X(10)``). Accommodates both US
        5-digit and US 9-digit ZIP+4 formats. Defaults to empty
        string.
    card_num : str
        16-character card number (from COBOL ``TRAN-CARD-NUM``,
        ``PIC X(16)``). Logical foreign key into the
        ``card_cross_reference`` table — resolved to the associated
        ``acct_id`` during POSTTRAN (reject code 104 if the card is
        unknown) and used during online transaction-add
        (``COTRN02C``) to attach the transaction to the correct
        account.
    orig_ts : str
        26-character origination timestamp (from COBOL
        ``TRAN-ORIG-TS``, ``PIC X(26)``). COBOL display format
        ``YYYY-MM-DD-HH.MM.SS.NNNNNN`` preserved verbatim for
        round-trip fidelity to the source flat file. The timestamp
        denotes when the transaction originated at the upstream
        system (POS terminal, ATM, online portal, etc.). Defaults
        to empty string.
    proc_ts : str
        26-character processing timestamp (from COBOL
        ``TRAN-PROC-TS``, ``PIC X(26)``). COBOL display format
        preserved verbatim. The timestamp denotes when the
        transaction was processed and posted here. **Indexed** by
        ``ix_transaction_proc_ts`` (B-tree) to support efficient
        date-range queries from CREASTMT (statement generation) and
        TRANREPT (transaction reports) — this index replicates the
        mainframe ``TRANFILE.AIX`` VSAM alternate index.
        Defaults to empty string.
    """

    __tablename__ = "transactions"

    # ------------------------------------------------------------------
    # Table-level constraints and indexes
    # ------------------------------------------------------------------
    # ``ix_transaction_proc_ts`` (non-unique B-tree on ``proc_ts``)
    #     Replicates the mainframe ``TRANFILE.AIX`` VSAM alternate
    #     index on ``TRAN-PROC-TS``. Supports efficient date-range
    #     queries issued by the batch jobs ``creastmt_job.py``
    #     (CREASTMT — statement generation, filters by billing-cycle
    #     window) and ``tranrept_job.py`` (TRANREPT — date-filtered
    #     transaction reports, filters by arbitrary user-supplied
    #     date window). Without this index, those queries would
    #     perform a full-table scan of the authoritative transaction
    #     history — prohibitively expensive once the ledger grows
    #     past a few million rows.
    #
    #     Declared non-unique because multiple transactions can share
    #     the same 26-character processing timestamp when posted
    #     within the same microsecond. Identity uniqueness is already
    #     enforced by the primary key on ``tran_id``.
    # ------------------------------------------------------------------
    __table_args__ = (Index("ix_transaction_proc_ts", "proc_ts"),)

    # ------------------------------------------------------------------
    # Primary key: 16-character transaction ID
    # (COBOL ``TRAN-ID`` PIC X(16))
    #
    # Auto-assigned by the online "Transaction Add" service
    # (``COTRN02C`` → ``transaction_service.py``) for interactive
    # additions, or propagated from the ``daily_transaction`` staging
    # row for batch-posted transactions. Stored as String(16) to
    # preserve the original character representation byte-for-byte.
    #
    # DB column name: ``tran_id`` (matches the Python attribute —
    # PK retains the ``tran_`` prefix without redundant renaming).
    # ------------------------------------------------------------------
    tran_id: Mapped[str] = mapped_column(
        String(16),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Transaction type code (COBOL ``TRAN-TYPE-CD`` PIC X(02))
    #
    # Logical foreign key into ``transaction_type``. Validated by
    # POSTTRAN (reject code 103 = unknown type code). Determines
    # whether the transaction is a debit (purchase, fee, cash
    # advance, interest accrual) or credit (payment, refund) for
    # balance-update direction.
    #
    # DB column name: ``tran_type_cd`` (per V1__schema.sql — all
    # columns in the ``transactions`` table carry the ``tran_`` prefix).
    # ------------------------------------------------------------------
    type_cd: Mapped[str] = mapped_column(
        "tran_type_cd",
        String(2),
        nullable=False,
        key="type_cd",
    )

    # ------------------------------------------------------------------
    # Transaction category code (COBOL ``TRAN-CAT-CD`` PIC 9(04))
    #
    # Stored as String(4) — not numeric — to preserve leading zeros
    # from migrated VSAM records. Logical foreign key into
    # ``transaction_category`` (composite with ``type_cd``). Drives
    # which ``transaction_category_balance`` bucket is updated
    # during POSTTRAN and which interest rate applies during INTCALC
    # (via the ``disclosure_group`` table, with DEFAULT / ZEROAPR
    # fallback per AAP §0.7.1).
    #
    # DB column name: ``tran_cat_cd``.
    # ------------------------------------------------------------------
    cat_cd: Mapped[str] = mapped_column(
        "tran_cat_cd",
        String(4),
        nullable=False,
        key="cat_cd",
    )

    # ------------------------------------------------------------------
    # Transaction source (COBOL ``TRAN-SOURCE`` PIC X(10))
    #
    # Identifies the upstream system that produced the record (e.g.
    # 'POS', 'ATM', 'ONLINE', 'BATCH'). Used for audit and
    # reconciliation reporting on TRANREPT.
    #
    # DB column name: ``tran_source``.
    # ------------------------------------------------------------------
    source: Mapped[str] = mapped_column(
        "tran_source",
        String(10),
        nullable=False,
        key="source",
    )

    # ------------------------------------------------------------------
    # Description (COBOL ``TRAN-DESC`` PIC X(100))
    #
    # Free-text description printed on statements (CREASTMT) and
    # transaction reports (TRANREPT). Default empty string ensures
    # non-NULL storage even when the producer omits a description.
    #
    # DB column name: ``tran_desc``.
    # ------------------------------------------------------------------
    description: Mapped[str] = mapped_column(
        "tran_desc",
        String(100),
        nullable=False,
        default="",
        key="description",
    )

    # ------------------------------------------------------------------
    # Amount — MONETARY (COBOL ``TRAN-AMT`` PIC S9(09)V99)
    #
    # NUMERIC(15, 2) preserves the COBOL PIC S9(09)V99 decimal
    # semantics exactly (11 integer digits at storage + 2 decimals
    # with headroom inside 15 total). Default Decimal("0.00")
    # guarantees two-decimal-place storage on INSERT when the caller
    # omits an explicit value. Sign convention follows COBOL S9(...)
    # — positive for debits, negative for credits. Summed into the
    # matching ``TransactionCategoryBalance`` bucket and applied to
    # ``Account.curr_bal`` during POSTTRAN. NEVER represented as a
    # floating-point number (AAP §0.7.2 — Financial Precision).
    #
    # DB column name: ``tran_amt``.
    # ------------------------------------------------------------------
    amount: Mapped[Decimal] = mapped_column(
        "tran_amt",
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
        key="amount",
    )

    # ------------------------------------------------------------------
    # Merchant ID (COBOL ``TRAN-MERCHANT-ID`` PIC 9(09))
    #
    # Stored as String(9) to preserve leading zeros. Default empty
    # string accommodates non-merchant transactions (payments, fees,
    # interest accrual rows).
    #
    # DB column name: ``tran_merchant_id``.
    # ------------------------------------------------------------------
    merchant_id: Mapped[str] = mapped_column(
        "tran_merchant_id",
        String(9),
        nullable=False,
        default="",
        key="merchant_id",
    )

    # ------------------------------------------------------------------
    # Merchant name (COBOL ``TRAN-MERCHANT-NAME`` PIC X(50))
    #
    # Denormalised snapshot of the merchant directory name, captured
    # at transaction time so that statements and reports render the
    # historical name even if the directory is later updated.
    #
    # DB column name: ``tran_merchant_name``.
    # ------------------------------------------------------------------
    merchant_name: Mapped[str] = mapped_column(
        "tran_merchant_name",
        String(50),
        nullable=False,
        default="",
        key="merchant_name",
    )

    # ------------------------------------------------------------------
    # Merchant city (COBOL ``TRAN-MERCHANT-CITY`` PIC X(50))
    #
    # Denormalised snapshot like ``merchant_name``.
    #
    # DB column name: ``tran_merchant_city``.
    # ------------------------------------------------------------------
    merchant_city: Mapped[str] = mapped_column(
        "tran_merchant_city",
        String(50),
        nullable=False,
        default="",
        key="merchant_city",
    )

    # ------------------------------------------------------------------
    # Merchant ZIP / postal code (COBOL ``TRAN-MERCHANT-ZIP`` PIC X(10))
    #
    # Accommodates both US 5-digit and US 9-digit ZIP+4 formats.
    #
    # DB column name: ``tran_merchant_zip``.
    # ------------------------------------------------------------------
    merchant_zip: Mapped[str] = mapped_column(
        "tran_merchant_zip",
        String(10),
        nullable=False,
        default="",
        key="merchant_zip",
    )

    # ------------------------------------------------------------------
    # Card number (COBOL ``TRAN-CARD-NUM`` PIC X(16))
    #
    # Logical foreign key into ``card_cross_reference`` — resolved
    # to the associated ``acct_id`` during POSTTRAN (reject code 104
    # if unknown) and during online transaction-add (``COTRN02C``)
    # to attach the transaction to the correct account. Stored as
    # String(16) to preserve the exact VSAM byte layout.
    #
    # DB column name: ``tran_card_num``.
    # ------------------------------------------------------------------
    card_num: Mapped[str] = mapped_column(
        "tran_card_num",
        String(16),
        nullable=False,
        key="card_num",
    )

    # ------------------------------------------------------------------
    # Origination timestamp (COBOL ``TRAN-ORIG-TS`` PIC X(26))
    #
    # COBOL display-format timestamp (YYYY-MM-DD-HH.MM.SS.NNNNNN)
    # preserved verbatim for round-trip fidelity to the VSAM source.
    # Denotes when the transaction originated at the upstream system.
    # Parsing to ``datetime`` occurs in the application / PySpark
    # layers, not the schema layer.
    #
    # DB column name: ``tran_orig_ts``.
    # ------------------------------------------------------------------
    orig_ts: Mapped[str] = mapped_column(
        "tran_orig_ts",
        String(26),
        nullable=False,
        default="",
        key="orig_ts",
    )

    # ------------------------------------------------------------------
    # Processing timestamp (COBOL ``TRAN-PROC-TS`` PIC X(26))
    #
    # Denotes when the transaction was processed and posted here.
    # **Indexed** by ``ix_transaction_proc_ts`` (B-tree, declared in
    # ``__table_args__`` above) to replicate the mainframe
    # ``TRANFILE.AIX`` VSAM alternate index. Supports efficient
    # date-range queries from CREASTMT (statement generation) and
    # TRANREPT (transaction reports).
    #
    # DB column name: ``tran_proc_ts``.
    # ------------------------------------------------------------------
    proc_ts: Mapped[str] = mapped_column(
        "tran_proc_ts",
        String(26),
        nullable=False,
        default="",
        key="proc_ts",
    )

    # Note: COBOL ``FILLER PIC X(20)`` — the trailing 20 bytes of
    # padding in the original 350-byte VSAM record — is deliberately
    # NOT mapped. In the relational model, column widths are explicit
    # and trailing padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        The four most diagnostically useful columns are included:
        the primary key (``tran_id``), the transaction-type code
        (``type_cd``), the monetary amount (``amount``), and the
        card number (``card_num``) that resolves to the owning
        account via ``card_cross_reference``. Denormalised merchant
        and timestamp fields are deliberately omitted to keep log
        output concise.

        Returns
        -------
        str
            Representation of the form
            ``Transaction(tran_id='0000000000000001',
            type_cd='01', amount=Decimal('123.45'),
            card_num='4111111111111111')``.
        """
        return (
            f"Transaction("
            f"tran_id={self.tran_id!r}, "
            f"type_cd={self.type_cd!r}, "
            f"amount={self.amount!r}, "
            f"card_num={self.card_num!r}"
            f")"
        )
