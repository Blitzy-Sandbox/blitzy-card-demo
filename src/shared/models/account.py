# ============================================================================
# Source: COBOL copybook CVACT01Y.cpy — ACCOUNT-RECORD (RECLN 300)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Replaces the mainframe ACCTFILE VSAM KSDS cluster (see app/jcl/ACCTFILE.jcl)
# with a relational PostgreSQL table persisting credit card account records.
# All monetary columns use PostgreSQL NUMERIC(15,2) to preserve the exact
# COBOL PIC S9(10)V99 decimal semantics — no floating-point arithmetic is
# permitted on these fields (see AAP §0.7.2 "Financial Precision").
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
"""SQLAlchemy 2.x ORM model for the ``account`` table.

Converts the COBOL copybook ``app/cpy/CVACT01Y.cpy`` (record layout
``ACCOUNT-RECORD``, 300-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a single credit card account in the
CardDemo Aurora PostgreSQL database.

COBOL to Python Field Mapping
-----------------------------
========================  =================  ====================  =====================
COBOL Field               COBOL Type         Python Column         SQLAlchemy Type
========================  =================  ====================  =====================
ACCT-ID                   ``PIC 9(11)``      ``acct_id``           ``String(11)`` — PK
ACCT-ACTIVE-STATUS        ``PIC X(01)``      ``active_status``     ``String(1)``
ACCT-CURR-BAL             ``PIC S9(10)V99``  ``curr_bal``          ``Numeric(15, 2)`` †
ACCT-CREDIT-LIMIT         ``PIC S9(10)V99``  ``credit_limit``      ``Numeric(15, 2)`` †
ACCT-CASH-CREDIT-LIMIT    ``PIC S9(10)V99``  ``cash_credit_limit`` ``Numeric(15, 2)`` †
ACCT-OPEN-DATE            ``PIC X(10)``      ``open_date``         ``String(10)``
ACCT-EXPIRAION-DATE ‡     ``PIC X(10)``      ``expiration_date``   ``String(10)``
ACCT-REISSUE-DATE         ``PIC X(10)``      ``reissue_date``      ``String(10)``
ACCT-CURR-CYC-CREDIT      ``PIC S9(10)V99``  ``curr_cyc_credit``   ``Numeric(15, 2)`` †
ACCT-CURR-CYC-DEBIT       ``PIC S9(10)V99``  ``curr_cyc_debit``    ``Numeric(15, 2)`` †
ACCT-ADDR-ZIP             ``PIC X(10)``      ``addr_zip``          ``String(10)``
ACCT-GROUP-ID             ``PIC X(10)``      ``group_id``          ``String(10)``
FILLER                    ``PIC X(178)``     — (not mapped)        — (COBOL padding only)
(new)                     —                  ``version_id``        ``Integer`` — OCC
========================  =================  ====================  =====================

† **Monetary fields.** ``Numeric(15, 2)`` maps to PostgreSQL
  ``NUMERIC(15, 2)``: 15 total digits with exactly 2 decimal places,
  which accommodates the full signed range of COBOL ``PIC S9(10)V99``
  (13 integer digits at storage + 2 decimals) with headroom. All
  financial arithmetic on these fields uses :class:`decimal.Decimal`
  with banker's rounding (``ROUND_HALF_EVEN``) to preserve the COBOL
  ``ROUNDED`` clause semantics. Floating-point arithmetic is **never**
  permitted (AAP §0.7.2).

‡ The original COBOL field name ``ACCT-EXPIRAION-DATE`` contains a
  historical typo (missing an ``-AT-`` — should be ``-EXPIRATION-``).
  The Python column is renamed to the correct spelling
  ``expiration_date`` because the copybook-to-Python mapping is purely
  semantic (relational PostgreSQL has no schema coupling to COBOL
  field names). The COBOL typo is called out here for auditability
  and is preserved in the ``app/cpy/CVACT01Y.cpy`` source-of-truth
  retained in the repository under AAP §0.7.1 ("do not modify the
  original COBOL source files").

Total RECLN: 11 + 1 + (12 × 5) + (10 × 5) + 178 = 300 bytes — matches
the VSAM cluster definition in ``app/jcl/ACCTFILE.jcl``
(``RECSZ(300 300)``).

Monetary Precision Contract
---------------------------
The 5 monetary fields (``curr_bal``, ``credit_limit``,
``cash_credit_limit``, ``curr_cyc_credit``, ``curr_cyc_debit``) each
map to PostgreSQL ``NUMERIC(15, 2)`` and are typed on the Python side
as :class:`decimal.Decimal`. Default values are ``Decimal("0.00")``
(never ``0`` or ``0.0``) to guarantee two-decimal-place storage on
INSERT when the caller omits an explicit value.

This preserves the COBOL ``PIC S9(10)V99`` financial semantics
required by:

* ``CBTRN02C`` / ``posttran_job.py`` — transaction posting updates
  ``curr_bal`` and maintains ``curr_cyc_credit`` / ``curr_cyc_debit``.
* ``CBACT04C`` / ``intcalc_job.py`` — interest calculation reads
  ``curr_bal`` and ``group_id`` (linking to ``DisclosureGroup``) to
  apply the formula ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200``.
* ``COBIL00C`` / ``bill_service.py`` — bill payment dual-write
  updates ``curr_bal`` atomically with a new ``Transaction`` row.
* ``COACTUPC`` / ``account_service.py`` — account update flow with
  SYNCPOINT ROLLBACK semantics (see optimistic concurrency below).

Optimistic Concurrency
----------------------
The ``version_id`` column (``Integer``, default ``0``) is wired to
SQLAlchemy's built-in optimistic-locking feature via
``__mapper_args__ = {"version_id_col": version_id}``. On every UPDATE,
SQLAlchemy appends ``AND version_id = :old_version`` to the WHERE
clause and increments the column. A stale read results in zero rows
affected, which SQLAlchemy raises as
:class:`sqlalchemy.orm.exc.StaleDataError`. This replaces the
CICS ``READ UPDATE`` / ``REWRITE`` locking protocol used in
``app/cbl/COACTUPC.cbl`` (4,236 lines with ``EXEC CICS SYNCPOINT
ROLLBACK`` on version mismatch) — see AAP §0.5.1 (account_service
entry) and AAP §0.7.1 ("dual-write patterns in Account Update
(F-005) must remain atomic").

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models.
* Date columns (``open_date``, ``expiration_date``, ``reissue_date``)
  are stored as 10-character strings matching the COBOL ``PIC X(10)``
  layout (format ``YYYY-MM-DD``). No ``DATE`` type coercion occurs at
  the ORM layer; date validation is delegated to the
  ``src.shared.utils.date_utils`` helpers so that the COBOL
  ``CSUTLDTC`` validation rules are faithfully preserved.
* The 11-digit account ID is stored as ``String(11)`` (not numeric)
  so that leading zeros in migrated records are preserved byte-for-byte
  against the COBOL ``PIC 9(11)`` source field.
* No FILLER column is mapped — the trailing 178 bytes of COBOL padding
  in the 300-byte VSAM record have no relational counterpart. In
  PostgreSQL, column widths are explicit and trailing padding carries
  no storage or semantic meaning.
* ``group_id`` is the logical foreign-key reference into the
  ``DisclosureGroup`` table (``CVTRA02Y.cpy``) for interest-rate
  lookups. The FK is NOT declared at the ORM level here to avoid
  introducing a circular import between ``Account`` and
  ``DisclosureGroup``; the join is materialized at the service layer
  (see ``intcalc_job.py``).
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.3 — Feature mapping for F-004 (Account View) and F-005
(Account Update).
AAP §0.5.1 — File-by-File Transformation Plan (``account.py`` entry).
AAP §0.7.1 — Refactoring-Specific Rules (optimistic concurrency,
dual-write preservation).
AAP §0.7.2 — Financial Precision and Security Requirements.
``app/cpy/CVACT01Y.cpy`` — Original COBOL record layout
(source artifact, retained for traceability).
``app/jcl/ACCTFILE.jcl`` — Original VSAM cluster definition
(RECSZ(300 300), KEYS(11 0)).
``app/data/ASCII/acctdata.txt`` — 50-row seed fixture loaded via
``db/migrations/V3__seed_data.sql``.
"""

from decimal import Decimal

from sqlalchemy import Integer, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class Account(Base):
    """ORM entity for the ``account`` table (from COBOL ``ACCOUNT-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``account`` table, which replaces the mainframe VSAM KSDS
    ``ACCTFILE`` dataset. Each row corresponds to one credit card
    account and is the central financial entity of the CardDemo
    domain model: balances, credit limits, cycle totals, and
    disclosure-group membership all live here.

    Participates in the following batch and online flows:

    * **Batch — POSTTRAN** (``CBTRN02C`` → ``posttran_job.py``):
      reads account, validates credit limit, updates ``curr_bal``,
      ``curr_cyc_credit`` / ``curr_cyc_debit``.
    * **Batch — INTCALC** (``CBACT04C`` → ``intcalc_job.py``):
      reads ``curr_bal`` and ``group_id``, joins to DisclosureGroup,
      applies ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200``.
    * **Batch — CREASTMT** (``CBSTM03A`` → ``creastmt_job.py``):
      reads account as one of 4 joined entities for statement
      generation.
    * **Online — F-004 Account View** (``COACTVWC`` →
      ``account_service.view()``): 3-entity join with Customer and
      CardCrossReference for display.
    * **Online — F-005 Account Update** (``COACTUPC`` →
      ``account_service.update()``): dual-write atomic update with
      SYNCPOINT ROLLBACK semantics, protected by
      ``version_id`` optimistic concurrency.
    * **Online — F-012 Bill Payment** (``COBIL00C`` →
      ``bill_service.pay()``): atomic update of ``curr_bal``
      concurrent with INSERT of a payment Transaction.

    Attributes
    ----------
    acct_id : str
        **Primary key.** 11-character zero-padded account ID (from
        COBOL ``ACCT-ID``, ``PIC 9(11)``). Stored as ``String(11)``
        rather than numeric to preserve leading zeros from migrated
        VSAM records byte-for-byte. Matches the VSAM cluster key
        length documented in ``app/jcl/ACCTFILE.jcl`` (``KEYS(11 0)``).
    active_status : str
        1-character account-active flag (from COBOL
        ``ACCT-ACTIVE-STATUS``, ``PIC X(01)``). Typical values:
        ``'Y'`` = active, ``'N'`` = inactive. Consumed by
        ``COACTVWC`` / ``COACTUPC`` online flows and the POSTTRAN
        batch reject-code cascade (reject code 100 =
        "Account Not Active").
    curr_bal : decimal.Decimal
        **Monetary.** Current outstanding account balance (from COBOL
        ``ACCT-CURR-BAL``, ``PIC S9(10)V99``). Stored as
        ``NUMERIC(15, 2)`` with default ``Decimal("0.00")``. Updated
        by the POSTTRAN batch, INTCALC interest accrual, COBIL00C
        bill payment, and COACTUPC account update flows. Never
        represented as a floating-point number.
    credit_limit : decimal.Decimal
        **Monetary.** Total credit limit (from COBOL
        ``ACCT-CREDIT-LIMIT``, ``PIC S9(10)V99``). Stored as
        ``NUMERIC(15, 2)``. Consulted by POSTTRAN during credit-limit
        validation (reject code 102 = "Over Credit Limit").
    cash_credit_limit : decimal.Decimal
        **Monetary.** Cash-advance sub-limit (from COBOL
        ``ACCT-CASH-CREDIT-LIMIT``, ``PIC S9(10)V99``). Stored as
        ``NUMERIC(15, 2)``. Enforced by POSTTRAN when the transaction
        category code indicates a cash-advance transaction.
    open_date : str
        10-character account open date (from COBOL ``ACCT-OPEN-DATE``,
        ``PIC X(10)``). ISO-like format ``YYYY-MM-DD``. Validated by
        ``src.shared.utils.date_utils`` helpers (which preserve the
        ``CSUTLDTC`` validation rules).
    expiration_date : str
        10-character account expiration date (from COBOL
        ``ACCT-EXPIRAION-DATE``, ``PIC X(10)`` — note original COBOL
        typo). ISO-like format ``YYYY-MM-DD``. Consulted by POSTTRAN
        (reject code 101 = "Account Expired").
    reissue_date : str
        10-character account reissue date (from COBOL
        ``ACCT-REISSUE-DATE``, ``PIC X(10)``). ISO-like format
        ``YYYY-MM-DD``. Used for card reissue tracking.
    curr_cyc_credit : decimal.Decimal
        **Monetary.** Current billing cycle credit total (from COBOL
        ``ACCT-CURR-CYC-CREDIT``, ``PIC S9(10)V99``). Stored as
        ``NUMERIC(15, 2)``. Sum of all credits (payments, refunds)
        posted to the account within the current cycle. Zeroed at
        cycle close.
    curr_cyc_debit : decimal.Decimal
        **Monetary.** Current billing cycle debit total (from COBOL
        ``ACCT-CURR-CYC-DEBIT``, ``PIC S9(10)V99``). Stored as
        ``NUMERIC(15, 2)``. Sum of all debits (purchases, cash
        advances, fees) posted to the account within the current
        cycle. Zeroed at cycle close.
    addr_zip : str
        10-character ZIP/postal code (from COBOL ``ACCT-ADDR-ZIP``,
        ``PIC X(10)``). Accommodates both US 5-digit and US 9-digit
        ZIP+4 formats (with or without the ``-`` separator).
    group_id : str
        10-character account disclosure-group code (from COBOL
        ``ACCT-GROUP-ID``, ``PIC X(10)``). Logical foreign key into
        the ``DisclosureGroup`` table — determines which interest-rate
        schedule applies during INTCALC. Common values: ``'DEFAULT'``
        (standard APR), ``'ZEROAPR'`` (0% introductory rate).
        Defaults to empty string; INTCALC treats empty / unknown
        group IDs by falling back to the ``'DEFAULT'`` schedule (see
        AAP §0.7.1 "DEFAULT/ZEROAPR disclosure group fallback").
    version_id : int
        Optimistic-concurrency counter (not from COBOL — introduced
        as part of the CICS → SQLAlchemy migration). Incremented by
        SQLAlchemy on every UPDATE; participates in the WHERE clause
        so that a stale read-then-write raises
        :class:`sqlalchemy.orm.exc.StaleDataError`. This replaces the
        CICS ``READ UPDATE`` / ``REWRITE`` enqueue protocol used in
        ``COACTUPC.cbl`` with ``SYNCPOINT ROLLBACK`` on version
        mismatch. See AAP §0.7.1 (Account Update must be atomic).
    """

    __tablename__ = "account"

    # ------------------------------------------------------------------
    # Primary key: 11-character zero-padded account ID
    # (COBOL ``ACCT-ID`` PIC 9(11))
    #
    # Stored as String(11) — not numeric — so leading zeros from
    # migrated VSAM records are preserved byte-for-byte. Matches the
    # VSAM cluster key length (KEYS(11 0)) in app/jcl/ACCTFILE.jcl.
    # ------------------------------------------------------------------
    acct_id: Mapped[str] = mapped_column(
        String(11),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Active-status flag (COBOL ``ACCT-ACTIVE-STATUS`` PIC X(01))
    #
    # 'Y' = active, 'N' = inactive. Checked by POSTTRAN (reject code
    # 100 = "Account Not Active") and by COACTVWC / COACTUPC online.
    # ------------------------------------------------------------------
    active_status: Mapped[str] = mapped_column(
        String(1),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Current balance — MONETARY (COBOL ``ACCT-CURR-BAL`` PIC S9(10)V99)
    #
    # NUMERIC(15, 2) preserves the COBOL PIC S9(10)V99 decimal
    # semantics exactly. Default Decimal("0.00") guarantees that an
    # INSERT with no explicit value persists the canonical zero
    # balance with two decimal places (never 0 or 0.0).
    # ------------------------------------------------------------------
    curr_bal: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # ------------------------------------------------------------------
    # Credit limit — MONETARY (COBOL ``ACCT-CREDIT-LIMIT`` PIC S9(10)V99)
    #
    # NUMERIC(15, 2). Consulted by POSTTRAN during credit-limit
    # validation (reject code 102 = "Over Credit Limit").
    # ------------------------------------------------------------------
    credit_limit: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # ------------------------------------------------------------------
    # Cash credit sub-limit — MONETARY
    # (COBOL ``ACCT-CASH-CREDIT-LIMIT`` PIC S9(10)V99)
    #
    # NUMERIC(15, 2). Enforced by POSTTRAN for cash-advance
    # transactions.
    # ------------------------------------------------------------------
    cash_credit_limit: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # ------------------------------------------------------------------
    # Open date (COBOL ``ACCT-OPEN-DATE`` PIC X(10))
    #
    # 10-character YYYY-MM-DD string. Validation delegated to
    # src.shared.utils.date_utils (preserves CSUTLDTC rules).
    # ------------------------------------------------------------------
    open_date: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Expiration date (COBOL ``ACCT-EXPIRAION-DATE`` PIC X(10))
    #
    # The original COBOL field name contains a historical typo
    # ("EXPIRAION" should be "EXPIRATION"). The Python column is
    # corrected to ``expiration_date`` because the mapping is
    # semantic rather than byte-copy. The COBOL typo is preserved in
    # the retained app/cpy/CVACT01Y.cpy source artifact for
    # traceability (AAP §0.7.1 — do not modify original COBOL source).
    #
    # Consulted by POSTTRAN (reject code 101 = "Account Expired").
    # ------------------------------------------------------------------
    expiration_date: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Reissue date (COBOL ``ACCT-REISSUE-DATE`` PIC X(10))
    #
    # 10-character YYYY-MM-DD string tracking the most recent card
    # reissue event for this account.
    # ------------------------------------------------------------------
    reissue_date: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Current cycle credit total — MONETARY
    # (COBOL ``ACCT-CURR-CYC-CREDIT`` PIC S9(10)V99)
    #
    # NUMERIC(15, 2). Running total of credits (payments, refunds)
    # posted within the current billing cycle. Zeroed at cycle close.
    # ------------------------------------------------------------------
    curr_cyc_credit: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # ------------------------------------------------------------------
    # Current cycle debit total — MONETARY
    # (COBOL ``ACCT-CURR-CYC-DEBIT`` PIC S9(10)V99)
    #
    # NUMERIC(15, 2). Running total of debits (purchases, cash
    # advances, fees) posted within the current billing cycle.
    # Zeroed at cycle close.
    # ------------------------------------------------------------------
    curr_cyc_debit: Mapped[Decimal] = mapped_column(
        Numeric(15, 2),
        nullable=False,
        default=Decimal("0.00"),
    )

    # ------------------------------------------------------------------
    # ZIP/postal code (COBOL ``ACCT-ADDR-ZIP`` PIC X(10))
    #
    # 10 chars accommodates US 5-digit ZIP, US ZIP+4 with or without
    # the '-' separator, and left/right-padded variants of the same.
    # ------------------------------------------------------------------
    addr_zip: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Disclosure-group ID (COBOL ``ACCT-GROUP-ID`` PIC X(10))
    #
    # Logical foreign key into the DisclosureGroup table
    # (CVTRA02Y.cpy). Drives INTCALC interest-rate lookup. The FK is
    # NOT declared at the ORM layer here — it is resolved at the
    # service layer to avoid a circular import between Account and
    # DisclosureGroup. Common values: 'DEFAULT' (standard APR),
    # 'ZEROAPR' (0% introductory rate). An empty or unknown group_id
    # causes INTCALC to fall back to 'DEFAULT' per AAP §0.7.1.
    # ------------------------------------------------------------------
    group_id: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Optimistic concurrency counter (NOT from COBOL — new column).
    #
    # SQLAlchemy increments this on every UPDATE and appends it to
    # the WHERE clause so that a stale read-then-write raises
    # sqlalchemy.orm.exc.StaleDataError. This replaces the CICS
    # READ UPDATE / REWRITE enqueue protocol used in COACTUPC.cbl
    # (4,236 lines with EXEC CICS SYNCPOINT ROLLBACK on version
    # mismatch). See __mapper_args__ below — the mapping wires this
    # column to SQLAlchemy's version_id_col feature.
    # ------------------------------------------------------------------
    version_id: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
    )

    # Note: COBOL ``FILLER PIC X(178)`` — the trailing 178 bytes of
    # padding in the original 300-byte VSAM record — is deliberately
    # NOT mapped. In the relational model, column widths are explicit
    # and trailing padding has no storage or semantic meaning.

    # ------------------------------------------------------------------
    # SQLAlchemy mapper options.
    #
    # ``version_id_col`` enables SQLAlchemy's built-in optimistic-
    # locking feature: on UPDATE, SQLAlchemy appends
    # ``AND version_id = :old_version`` to the WHERE clause and
    # increments the column. A stale write results in zero rows
    # affected, which SQLAlchemy raises as StaleDataError. This
    # replaces the CICS READ UPDATE / REWRITE locking protocol used
    # in COACTUPC.cbl (AAP §0.7.1 — Account Update must be atomic).
    # ------------------------------------------------------------------
    __mapper_args__ = {
        "version_id_col": version_id,
    }

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        The three most diagnostically useful columns are included:
        the primary key (``acct_id``), the active-status flag
        (``active_status``), and the current balance (``curr_bal``).
        Sensitive PII-adjacent fields such as ZIP code and all
        credit-limit / cycle fields are deliberately omitted to keep
        log output concise and readable.

        Returns
        -------
        str
            Representation of the form
            ``Account(acct_id='00000000001', active_status='Y',
            curr_bal=Decimal('1234.56'))``.
        """
        return f"Account(acct_id={self.acct_id!r}, active_status={self.active_status!r}, curr_bal={self.curr_bal!r})"
