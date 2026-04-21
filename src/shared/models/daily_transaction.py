# ============================================================================
# Source: COBOL copybook CVTRA06Y.cpy — DALYTRAN-RECORD (RECLN 350)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Staging table for daily batch transaction processing (pre-POSTTRAN).
# Mirrors the Transaction layout exactly (same field sizes) but persists
# in a separate relational table so that incoming daily batches can be
# validated, reconciled, and posted into the authoritative ``transaction``
# table without corrupting historical data in the event of a reject.
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
"""SQLAlchemy 2.x ORM model for the ``daily_transaction`` table.

Converts the COBOL copybook ``app/cpy/CVTRA06Y.cpy`` (record layout
``DALYTRAN-RECORD``, 350-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a single incoming daily-batch
transaction staged in the CardDemo Aurora PostgreSQL database before
it is posted to the authoritative ``transaction`` table.

Purpose — Why a Separate Staging Table
--------------------------------------
The mainframe daily pipeline receives a flat DAILYTRAN file produced
outside the CardDemo application (issuer, network, merchant feeds).
``CBTRN01C`` reads this file; ``CBTRN02C`` (POSTTRAN) validates each
record through a 4-stage cascade (reject codes 100-109) and, on
successful validation, posts it as a permanent row in the TRANFILE
(``Transaction`` table). Rejected records are written to the daily
reject GDG (``DALYREJS``) — they never enter TRANFILE.

In the cloud-native rewrite, this staging model is materialised as a
dedicated relational table: ``daily_transaction``. Records land here
first via the daily transaction driver (``daily_tran_driver_job.py``,
derived from ``CBTRN01C``), are processed by the POSTTRAN Glue job
(``posttran_job.py``, derived from ``CBTRN02C``), and finally either
(a) inserted into ``transaction`` on success, or (b) written to S3
as a versioned reject file on validation failure. Keeping the two
tables physically separate preserves the mainframe's reject-safe
invariant: **a rejected daily record never contaminates the
authoritative transaction history**.

COBOL to Python Field Mapping
-----------------------------
=========================  =================  =====================  =====================
COBOL Field                COBOL Type         Python Column          SQLAlchemy Type
=========================  =================  =====================  =====================
DALYTRAN-ID                ``PIC X(16)``      ``tran_id``            ``String(16)`` — PK
DALYTRAN-TYPE-CD           ``PIC X(02)``      ``type_cd``            ``String(2)``
DALYTRAN-CAT-CD            ``PIC 9(04)``      ``cat_cd``             ``String(4)``
DALYTRAN-SOURCE            ``PIC X(10)``      ``source``             ``String(10)``
DALYTRAN-DESC              ``PIC X(100)``     ``description``        ``String(100)``
DALYTRAN-AMT               ``PIC S9(09)V99``  ``amount``             ``Numeric(15, 2)`` †
DALYTRAN-MERCHANT-ID       ``PIC 9(09)``      ``merchant_id``        ``String(9)``
DALYTRAN-MERCHANT-NAME     ``PIC X(50)``      ``merchant_name``      ``String(50)``
DALYTRAN-MERCHANT-CITY     ``PIC X(50)``      ``merchant_city``      ``String(50)``
DALYTRAN-MERCHANT-ZIP      ``PIC X(10)``      ``merchant_zip``       ``String(10)``
DALYTRAN-CARD-NUM          ``PIC X(16)``      ``card_num``           ``String(16)``
DALYTRAN-ORIG-TS           ``PIC X(26)``      ``orig_ts``            ``String(26)``
DALYTRAN-PROC-TS           ``PIC X(26)``      ``proc_ts``            ``String(26)``
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

Total RECLN (COBOL): 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 +
16 + 26 + 26 + 20 (FILLER) = **350 bytes**, matching the documented
``CVTRA06Y.cpy`` record length. The 20-byte trailing FILLER is
deliberately NOT mapped — trailing padding has no storage meaning in
a relational database.

Difference from the ``Transaction`` Entity
------------------------------------------
The ``daily_transaction`` table is intentionally the byte-for-byte
twin of the ``transaction`` table layout, so that successful POSTTRAN
records can be copied INSERT-as-SELECT-like from one to the other
without any field reshaping. However, two modelling choices differ:

* **No ``proc_ts`` B-tree index.** The authoritative ``transaction``
  table declares ``Index('ix_transaction_proc_ts', 'proc_ts')`` to
  replicate the VSAM alternate index (AIX) ``TRANFILE.AIX`` that
  supports date-range reports (TRANREPT) and statement generation
  (CREASTMT). The staging table does not serve those read patterns —
  its typical access is full-scan within a single batch run —
  so no secondary index is defined. This keeps INSERT throughput
  high during the daily driver load.
* **No optimistic-concurrency ``version_id``.** The staging table is
  written once per batch by ``daily_tran_driver_job.py`` and read
  once by ``posttran_job.py``; there is no interactive / concurrent
  update surface (unlike ``Account`` and ``Card``), so no
  version-column SYNCPOINT-ROLLBACK analogue is required.

Monetary Precision Contract
---------------------------
The single monetary field ``amount`` maps to PostgreSQL
``NUMERIC(15, 2)`` and is typed on the Python side as
:class:`decimal.Decimal`. The default value is ``Decimal("0.00")``
(never ``0`` or ``0.0``) to guarantee two-decimal-place storage on
INSERT when the caller omits an explicit value.

This preserves the COBOL ``PIC S9(09)V99`` financial semantics
required by:

* ``CBTRN01C`` / ``daily_tran_driver_job.py`` — reads the incoming
  flat DAILYTRAN file and populates ``daily_transaction``.
* ``CBTRN02C`` / ``posttran_job.py`` — reads ``daily_transaction``,
  validates each row through the 4-stage cascade (reject codes
  100-109), applies approved debits/credits to ``Account.curr_bal``
  / ``curr_cyc_credit`` / ``curr_cyc_debit``, and writes approved
  rows to the ``transaction`` table.

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models (``Account``, ``Card``, ``Customer``, ``Transaction``, etc.).
* COBOL ``PIC 9(n)`` numeric-ID fields are stored as ``String(n)`` —
  not integer types — to preserve leading zeros from migrated VSAM
  records byte-for-byte. This is the same convention used by
  ``Account.acct_id`` (``PIC 9(11)`` → ``String(11)``),
  ``Customer.cust_id`` (``PIC 9(09)`` → ``String(9)``), etc.
* Timestamp columns (``orig_ts``, ``proc_ts``) are ``String(26)`` —
  they preserve the COBOL COBOL ``PIC X(26)`` display-format
  timestamps verbatim (``YYYY-MM-DD-HH.MM.SS.NNNNNN``) so that
  round-trip fidelity to the source flat file is exact. Parsing to
  ``datetime`` occurs in the application / PySpark layers, not the
  schema layer.
* No FILLER byte is mapped — see the table above.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.2 — Batch Program Classification (``CBTRN01C`` → daily driver;
    ``CBTRN02C`` → POSTTRAN).
AAP §0.5.1 — File-by-File Transformation Plan
    (``daily_transaction.py`` entry).
AAP §0.7.1 — Refactoring-Specific Rules (4-stage validation cascade
    preservation, dual-write patterns, minimal-change clause).
AAP §0.7.2 — Financial Precision (``Decimal`` semantics on ``amount``).
``app/cpy/CVTRA06Y.cpy`` — Original COBOL record layout
    (source artifact, retained for traceability).
``app/cbl/CBTRN01C.cbl`` — Daily transaction driver (populates this
    staging table in the cloud-native rewrite).
``app/cbl/CBTRN02C.cbl`` — Transaction posting engine (POSTTRAN —
    consumer of this staging table).
``app/data/ASCII/dailytran.txt`` — Fixture flat file used to seed
    ``daily_transaction`` rows during local / CI testing.
"""

from decimal import Decimal

from sqlalchemy import Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class DailyTransaction(Base):
    """ORM entity for the ``daily_transaction`` table (from COBOL ``DALYTRAN-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``daily_transaction`` staging table, which holds incoming daily-batch
    transaction records awaiting validation and posting by the POSTTRAN
    batch job (``CBTRN02C`` → ``posttran_job.py``). The staging table
    replaces the mainframe DAILYTRAN flat dataset read by ``CBTRN01C``
    and the transient in-memory staging area of ``CBTRN02C``.

    Lifecycle of a ``DailyTransaction`` row:

    1. **Ingestion** — ``daily_tran_driver_job.py`` (derived from
       ``CBTRN01C``) reads the incoming flat DAILYTRAN file (or its
       S3 replacement) and INSERTs one row per incoming record.
    2. **Validation and posting** — ``posttran_job.py`` (derived from
       ``CBTRN02C``) reads every row and applies the 4-stage validation
       cascade (reject codes 100-109: account not active, expired,
       over credit limit, invalid xref, etc.). On success, an analogous
       row is INSERTed into the ``transaction`` table and the Account
       balances are updated in the same database transaction. On
       failure, the reject code is written to the daily-reject S3
       prefix (replacing the mainframe ``DALYREJS`` GDG).
    3. **Cycle close** — the staging table is truncated (or
       partition-dropped) at the end of the daily batch, ready for the
       next day's load. The authoritative ``transaction`` table retains
       history indefinitely; ``daily_transaction`` is ephemeral.

    Mirrors the ``Transaction`` entity layout exactly (same 13 fields,
    same PIC types, same field sizes). The only schema differences are
    (a) no ``proc_ts`` B-tree secondary index, and (b) no ``version_id``
    optimistic-concurrency column — see "Difference from the
    Transaction Entity" in the module docstring for rationale.

    Attributes
    ----------
    tran_id : str
        **Primary key.** 16-character transaction ID (from COBOL
        ``DALYTRAN-ID``, ``PIC X(16)``). Assigned upstream by the
        originating issuer / network / merchant feed. Uniqueness is
        enforced at the staging level so that duplicate daily records
        are detected at ingestion time, before POSTTRAN attempts to
        post them.
    type_cd : str
        2-character transaction-type code (from COBOL
        ``DALYTRAN-TYPE-CD``, ``PIC X(02)``). Logical foreign key into
        the ``transaction_type`` lookup table. Typical values include
        purchase, refund, payment, cash-advance, fee, and interest.
        Validated by POSTTRAN (reject code 103 — unknown type code).
    cat_cd : str
        4-character transaction-category code (from COBOL
        ``DALYTRAN-CAT-CD``, ``PIC 9(04)``). Stored as ``String(4)`` —
        not numeric — so leading zeros from migrated records are
        preserved byte-for-byte. Logical foreign key into the
        ``transaction_category`` lookup table (composite key with
        ``type_cd``). Determines which ``transaction_category_balance``
        bucket is updated during POSTTRAN and which interest rate
        applies during INTCALC.
    source : str
        10-character transaction-source code (from COBOL
        ``DALYTRAN-SOURCE``, ``PIC X(10)``). Identifies the upstream
        system that produced the record (e.g., ``'POS'``,
        ``'ATM'``, ``'ONLINE'``, ``'BATCH'``). Primarily used for
        audit and reconciliation reports.
    description : str
        Up to 100-character transaction description (from COBOL
        ``DALYTRAN-DESC``, ``PIC X(100)``). Free-text description
        printed on statements (CREASTMT) and transaction reports
        (TRANREPT). Defaults to empty string.
    amount : decimal.Decimal
        **Monetary.** Transaction amount (from COBOL
        ``DALYTRAN-AMT``, ``PIC S9(09)V99``). Stored as
        ``NUMERIC(15, 2)`` with default ``Decimal("0.00")``. Sign
        convention follows COBOL ``S9(...)`` — positive for debits
        (purchases, fees, cash advances) and negative for credits
        (payments, refunds), consistent with the authoritative
        ``Transaction`` entity. Never represented as a floating-point
        number (AAP §0.7.2).
    merchant_id : str
        9-character merchant ID (from COBOL ``DALYTRAN-MERCHANT-ID``,
        ``PIC 9(09)``). Stored as ``String(9)`` to preserve leading
        zeros. Logical foreign key into the external merchant
        directory (not a CardDemo-owned table). Defaults to empty
        string for non-merchant transactions (e.g., payments, fees).
    merchant_name : str
        Up to 50-character merchant name (from COBOL
        ``DALYTRAN-MERCHANT-NAME``, ``PIC X(50)``). Denormalised
        copy of the merchant-directory name snapshot, captured at
        transaction time so that downstream statements and reports
        render the historical name even if the directory is later
        updated. Defaults to empty string.
    merchant_city : str
        Up to 50-character merchant city (from COBOL
        ``DALYTRAN-MERCHANT-CITY``, ``PIC X(50)``). Denormalised
        like ``merchant_name``. Defaults to empty string.
    merchant_zip : str
        Up to 10-character merchant ZIP/postal code (from COBOL
        ``DALYTRAN-MERCHANT-ZIP``, ``PIC X(10)``). Accommodates both
        US 5-digit and US 9-digit ZIP+4 formats. Defaults to empty
        string.
    card_num : str
        16-character card number (from COBOL ``DALYTRAN-CARD-NUM``,
        ``PIC X(16)``). Logical foreign key into the
        ``card_cross_reference`` table — resolved by POSTTRAN to the
        associated ``acct_id`` before balance updates are applied.
        An unresolvable ``card_num`` produces reject code 104
        ("Invalid Card / Cross-Reference").
    orig_ts : str
        26-character origination timestamp (from COBOL
        ``DALYTRAN-ORIG-TS``, ``PIC X(26)``). COBOL display format
        ``YYYY-MM-DD-HH.MM.SS.NNNNNN`` preserved verbatim for
        round-trip fidelity to the source flat file. The timestamp
        denotes when the transaction originated at the upstream
        system (POS terminal, ATM, online portal, etc.). Defaults
        to empty string.
    proc_ts : str
        26-character processing timestamp (from COBOL
        ``DALYTRAN-PROC-TS``, ``PIC X(26)``). COBOL display format
        preserved verbatim. The timestamp denotes when the
        transaction was processed by the upstream system prior to
        handoff. Defaults to empty string. **Note:** Unlike the
        authoritative ``transaction`` table (which declares a
        B-tree index on ``proc_ts`` to support TRANREPT date-range
        queries), the staging table does not index ``proc_ts`` —
        its read patterns are full-scan within a single batch run.
    """

    __tablename__ = "daily_transactions"

    # ------------------------------------------------------------------
    # Primary key: 16-character daily transaction ID
    # (COBOL ``DALYTRAN-ID`` PIC X(16))
    #
    # Supplied by the upstream issuer / network / merchant feed.
    # Stored as String(16) to preserve the original character
    # representation byte-for-byte.
    # ------------------------------------------------------------------
    tran_id: Mapped[str] = mapped_column(
        String(16),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Transaction type code (COBOL ``DALYTRAN-TYPE-CD`` PIC X(02))
    #
    # Logical foreign key into ``transaction_type``. Validated by
    # POSTTRAN (reject code 103 = unknown type code).
    # ------------------------------------------------------------------
    type_cd: Mapped[str] = mapped_column(
        String(2),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Transaction category code (COBOL ``DALYTRAN-CAT-CD`` PIC 9(04))
    #
    # Stored as String(4) — not numeric — to preserve leading zeros
    # from migrated VSAM records. Logical foreign key into
    # ``transaction_category`` (composite with ``type_cd``).
    # ------------------------------------------------------------------
    cat_cd: Mapped[str] = mapped_column(
        String(4),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Transaction source (COBOL ``DALYTRAN-SOURCE`` PIC X(10))
    #
    # Identifies the upstream system that produced the record (e.g.
    # 'POS', 'ATM', 'ONLINE', 'BATCH'). Used for audit and
    # reconciliation reporting.
    # ------------------------------------------------------------------
    source: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Description (COBOL ``DALYTRAN-DESC`` PIC X(100))
    #
    # Free-text description printed on statements (CREASTMT) and
    # transaction reports (TRANREPT). Default empty string ensures
    # non-NULL storage even when the upstream feed omits a
    # description.
    # ------------------------------------------------------------------
    description: Mapped[str] = mapped_column(
        String(100),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Amount — MONETARY (COBOL ``DALYTRAN-AMT`` PIC S9(09)V99)
    #
    # NUMERIC(15, 2) preserves the COBOL PIC S9(09)V99 decimal
    # semantics exactly (11 integer digits at storage + 2 decimals
    # with headroom inside 15 total). Default Decimal("0.00")
    # guarantees two-decimal-place storage on INSERT when the caller
    # omits an explicit value. Sign convention follows COBOL S9(...)
    # — positive for debits, negative for credits. NEVER represented
    # as a floating-point number (AAP §0.7.2 — Financial Precision).
    # ------------------------------------------------------------------
    amount: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # ------------------------------------------------------------------
    # Merchant ID (COBOL ``DALYTRAN-MERCHANT-ID`` PIC 9(09))
    #
    # Stored as String(9) to preserve leading zeros. Default empty
    # string accommodates non-merchant transactions (payments, fees,
    # interest accrual rows).
    # ------------------------------------------------------------------
    merchant_id: Mapped[str] = mapped_column(
        String(9),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Merchant name (COBOL ``DALYTRAN-MERCHANT-NAME`` PIC X(50))
    #
    # Denormalised snapshot of the merchant directory name, captured
    # at transaction time so that statements and reports render the
    # historical name even if the directory is later updated.
    # ------------------------------------------------------------------
    merchant_name: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Merchant city (COBOL ``DALYTRAN-MERCHANT-CITY`` PIC X(50))
    #
    # Denormalised snapshot like ``merchant_name``.
    # ------------------------------------------------------------------
    merchant_city: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Merchant ZIP / postal code (COBOL ``DALYTRAN-MERCHANT-ZIP`` PIC X(10))
    #
    # Accommodates both US 5-digit and US 9-digit ZIP+4 formats.
    # ------------------------------------------------------------------
    merchant_zip: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Card number (COBOL ``DALYTRAN-CARD-NUM`` PIC X(16))
    #
    # Logical foreign key into ``card_cross_reference`` — resolved
    # by POSTTRAN to the associated ``acct_id`` before balance
    # updates are applied. An unresolvable ``card_num`` produces
    # reject code 104 ("Invalid Card / Cross-Reference").
    # ------------------------------------------------------------------
    card_num: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Origination timestamp (COBOL ``DALYTRAN-ORIG-TS`` PIC X(26))
    #
    # COBOL display-format timestamp (YYYY-MM-DD-HH.MM.SS.NNNNNN)
    # preserved verbatim for round-trip fidelity to the source flat
    # file. Denotes when the transaction originated at the upstream
    # system. Parsing to ``datetime`` occurs in the application /
    # PySpark layers, not the schema layer.
    # ------------------------------------------------------------------
    orig_ts: Mapped[str] = mapped_column(
        String(26),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Processing timestamp (COBOL ``DALYTRAN-PROC-TS`` PIC X(26))
    #
    # Denotes when the transaction was processed by the upstream
    # system prior to handoff. NOTE: unlike the authoritative
    # ``transaction`` table (which declares a B-tree index on
    # ``proc_ts`` to support TRANREPT date-range queries), the
    # staging table does not index ``proc_ts`` — its read patterns
    # are full-scan within a single batch run, so the index would
    # only add write overhead without any query benefit.
    # ------------------------------------------------------------------
    proc_ts: Mapped[str] = mapped_column(
        String(26),
        nullable=False,
        default="",
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
            ``DailyTransaction(tran_id='0000000000000001',
            type_cd='01', amount=Decimal('123.45'),
            card_num='4111111111111111')``.
        """
        return (
            f"DailyTransaction("
            f"tran_id={self.tran_id!r}, "
            f"type_cd={self.type_cd!r}, "
            f"amount={self.amount!r}, "
            f"card_num={self.card_num!r}"
            f")"
        )
