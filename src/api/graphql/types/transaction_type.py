# ============================================================================
# Source: COBOL copybook CVTRA05Y.cpy — TRAN-RECORD (350 bytes fixed-length
#         record layout — the authoritative credit-card transaction
#         history record for the CardDemo application)
#         BMS symbolic map COTRN01.CPY — Transaction detail screen
#         — Mainframe-to-Cloud migration
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
"""Strawberry GraphQL type for the Transaction entity.

Source: COBOL copybook ``app/cpy/CVTRA05Y.cpy`` — ``TRAN-RECORD``
(350-byte fixed-length record layout, the authoritative transaction
history record of the CardDemo domain model).
BMS symbolic map: ``app/cpy-bms/COTRN01.CPY`` — Transaction detail
screen, which defines the AI/AO field mapping for the COBOL online
Transaction Detail transaction (``COTRN01C``, F-010) and confirms the
per-field display formats (e.g., ``TRNIDI PIC X(16)`` for the 16-char
transaction ID, ``TRNAMTI PIC X(12)`` for the display-formatted
transaction amount, ``TDESCI PIC X(60)`` for the truncated
description rendered on the 24x80 3270 screen).

Mainframe-to-Cloud migration: VSAM KSDS ``TRANFILE`` → Aurora
PostgreSQL ``transactions`` table → GraphQL API via Strawberry.

COBOL to GraphQL Field Mapping
------------------------------
==================  =================  ================  =====================
COBOL Field         COBOL Type         GraphQL Field     Python Type
==================  =================  ================  =====================
TRAN-ID             ``PIC X(16)``      ``tran_id``       ``str``
TRAN-TYPE-CD        ``PIC X(02)``      ``type_cd``       ``str``
TRAN-CAT-CD         ``PIC 9(04)``      ``cat_cd``        ``str`` ‡
TRAN-SOURCE         ``PIC X(10)``      ``source``        ``str``
TRAN-DESC           ``PIC X(100)``     ``description``   ``str`` ¶
TRAN-AMT            ``PIC S9(09)V99``  ``amount``        ``Decimal`` †
TRAN-MERCHANT-ID    ``PIC 9(09)``      ``merchant_id``   ``str`` ‡
TRAN-MERCHANT-NAME  ``PIC X(50)``      ``merchant_name`` ``str``
TRAN-MERCHANT-CITY  ``PIC X(50)``      ``merchant_city`` ``str``
TRAN-MERCHANT-ZIP   ``PIC X(10)``      ``merchant_zip``  ``str``
TRAN-CARD-NUM       ``PIC X(16)``      ``card_num``      ``str``
TRAN-ORIG-TS        ``PIC X(26)``      ``orig_ts``       ``str`` §
TRAN-PROC-TS        ``PIC X(26)``      ``proc_ts``       ``str`` §
FILLER              ``PIC X(20)``      — (not mapped)    — (COBOL padding)
==================  =================  ================  =====================

† **Monetary field.** The single monetary field ``amount`` uses
  :class:`decimal.Decimal` — **NEVER** :class:`float`. Floating-point
  arithmetic is prohibited for financial calculations across the entire
  CardDemo codebase (see AAP §0.7.2 "Financial Precision"). The COBOL
  ``PIC S9(09)V99`` source semantics (11 integer digits + 2 decimals,
  signed) are preserved via the ORM layer's PostgreSQL ``NUMERIC(15, 2)``
  column type — which the ``asyncpg`` / ``psycopg2`` drivers materialize
  as Python ``Decimal`` instances automatically. No coercion occurs in
  this type; the ``from_model`` factory simply forwards the ORM attribute
  value unchanged.

‡ **Numeric-ID stored as string.** The COBOL fields ``TRAN-CAT-CD``
  (``PIC 9(04)``) and ``TRAN-MERCHANT-ID`` (``PIC 9(09)``) are declared
  as numeric in COBOL but are modelled as ``str`` in Python to preserve
  leading zeros from migrated VSAM records byte-for-byte. This matches
  the ORM column types ``String(4)`` and ``String(9)`` on the
  :class:`~src.shared.models.transaction.Transaction` model and the
  convention used across every CardDemo numeric identifier (account ID,
  customer ID, etc.).

§ **Full 26-char COBOL timestamp.** The GraphQL type preserves the full
  26-character COBOL display-format timestamp
  (``YYYY-MM-DD-HH.MM.SS.NNNNNN``) from ``TRAN-ORIG-TS`` and
  ``TRAN-PROC-TS``. This is intentionally WIDER than the BMS screen
  contract in ``COTRN01.CPY``, which truncates the display to 10
  characters (``TORIGDTI PIC X(10)``, ``TPROCDTI PIC X(10)`` — date
  only). The BMS truncation is a 3270 terminal rendering concern that
  does not apply to a JSON/GraphQL API; round-trip fidelity to the
  authoritative VSAM record requires the full 26-character value.

¶ **Full 100-char COBOL description.** The GraphQL type preserves the
  full 100-character COBOL ``TRAN-DESC`` (``PIC X(100)``). This is
  intentionally WIDER than the BMS screen contract in ``COTRN01.CPY``,
  which truncates the display to 60 characters
  (``TDESCI PIC X(60)``) because the mainframe 24x80 3270 terminal
  could not fit the full 100 characters on one line. GraphQL clients
  can render the full description without that constraint.

Total RECLN: 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 +
26 + 20 (FILLER) = **350 bytes** — matches the VSAM cluster definition
in ``app/jcl/TRANFILE.jcl``. The trailing 20-byte FILLER is explicitly
**not** mapped to a GraphQL field; in the relational model, column
widths are explicit and trailing padding has no storage or semantic
meaning.

Consumer Resolvers
------------------
Instances of :class:`TransactionType` are returned by the following
Strawberry resolvers:

* ``src.api.graphql.queries.Query.transaction(tran_id)`` — single
  transaction detail, corresponding to COBOL ``COTRN01C.cbl`` (F-010,
  Transaction Detail). The resolver reads a row from the Aurora
  PostgreSQL ``transactions`` table by the 16-character primary key
  and passes the ORM instance to :meth:`TransactionType.from_model`
  to produce the GraphQL response object.
* ``src.api.graphql.queries.Query.transactions(page, page_size)`` —
  paginated transaction list (default ``page_size=10`` matching the
  COBOL ``OCCURS 10 TIMES`` repeated row group in ``COTRN00.CPY``
  from the COBOL Transaction List program ``COTRN00C.cbl``, F-009).
  Each row in the result list is produced via
  :meth:`TransactionType.from_model`.
* ``src.api.graphql.mutations.Mutation.add_transaction(input)`` —
  corresponding to COBOL ``COTRN02C.cbl`` (F-011, Transaction Add,
  the online program that performs CICS STARTBR+READPREV to
  auto-assign the next sequential ``tran_id`` and CCXREF lookup to
  resolve ``card_num`` to the owning account). The mutation returns
  the newly inserted transaction as a :class:`TransactionType`
  instance, again via :meth:`TransactionType.from_model`.
* ``src.api.graphql.mutations.Mutation.pay_bill(input)`` —
  corresponding to COBOL ``COBIL00C.cbl`` (F-012, Bill Payment,
  the atomic dual-write pattern that INSERTs a payment transaction
  and UPDATEs the account balance in a single database transaction).
  The mutation returns the newly created payment transaction as a
  :class:`TransactionType` instance.

Design Notes
------------
* **snake_case field names** match the SQLAlchemy model column names
  (``tran_id``, ``type_cd``, ``cat_cd``, ``amount``, etc.) and the
  Aurora PostgreSQL DDL column names from
  ``db/migrations/V1__schema.sql``. Strawberry's default
  ``snake_case → camelCase`` transformation will surface these to
  GraphQL clients as ``tranId``, ``typeCd``, ``catCd``, ``amount``,
  etc. — a GraphQL convention standard across the ecosystem.
* **No ``version_id`` / optimistic-concurrency token** is declared
  here. The :class:`~src.shared.models.transaction.Transaction` ORM
  entity is append-only — rows are INSERTed by POSTTRAN, INTCALC,
  Transaction Add, and Bill Payment, but are never UPDATEd in place.
  No optimistic concurrency is therefore required and no counter
  needs to be exposed.
* **No FILLER mapping.** The trailing 20 bytes of COBOL padding
  (``FILLER PIC X(20)``) have no relational or GraphQL counterpart
  and are therefore not declared here. This matches the corresponding
  non-mapping on the SQLAlchemy model.
* **Python 3.11+** only. Aligned with the AWS Glue 5.1 runtime
  baseline and the FastAPI / ECS Fargate deployment target.

Financial Precision Contract
----------------------------
The single monetary field ``amount`` MUST hold a
:class:`decimal.Decimal` instance with exactly two decimal places.
The SQLAlchemy ORM guarantees this on the read path (PostgreSQL
``NUMERIC(15, 2)`` materializes as ``Decimal`` with the correct scale
via ``asyncpg`` / ``psycopg2``); the GraphQL layer never performs
arithmetic on this value and therefore never risks an accidental
``float`` coercion. Downstream consumers that do compute on
transaction amounts (POSTTRAN posting, INTCALC interest accrual,
COBIL00C bill payment dual-write) re-declare the precision contract
at the service / batch-job layer.

The sign convention follows the COBOL ``S9(09)V99`` source semantics
exactly: positive values indicate **debits** (purchases, fees, cash
advances, interest accruals) and negative values indicate **credits**
(payments, refunds). The sign is preserved end-to-end from the daily
transaction staging table through POSTTRAN validation, through the
authoritative ``transactions`` table, and into this GraphQL response
type — never coerced, never flipped.

See Also
--------
* AAP §0.2.3 — Feature mapping for F-009 (Transaction List), F-010
  (Transaction Detail), F-011 (Transaction Add), and F-012 (Bill
  Payment).
* AAP §0.5.1 — File-by-File Transformation Plan
  (``transaction_type.py`` entry).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve existing
  business logic, dual-write atomicity, interest-formula preservation).
* AAP §0.7.2 — Financial Precision (``Decimal`` semantics on
  ``amount``).
* :class:`src.shared.models.transaction.Transaction` — SQLAlchemy
  ORM model (the source of data for
  :meth:`TransactionType.from_model`).
* ``app/cpy/CVTRA05Y.cpy`` — Original COBOL record layout (source
  artifact, retained for traceability per AAP §0.7.1).
* ``app/cpy-bms/COTRN01.CPY`` — BMS symbolic map confirming the
  transaction detail screen field layout (note BMS display
  truncations that do not apply to the GraphQL type — see the
  module docstring §).
* ``src.api.graphql.queries.Query.transaction`` /
  ``Query.transactions`` — the query resolvers that return
  instances of this type.
* ``src.api.graphql.mutations.Mutation.add_transaction`` /
  ``Mutation.pay_bill`` — the mutation resolvers that return
  instances of this type.
"""

# ----------------------------------------------------------------------------
# Imports
# ----------------------------------------------------------------------------
# Python standard library Decimal — the ONLY permissible numeric type
# for the single monetary field ``amount``. Using float for this value
# is explicitly prohibited by AAP §0.7.2 ("Financial Precision"); the
# COBOL PIC S9(09)V99 source semantics demand exact decimal arithmetic,
# which only Decimal can provide in Python.
from decimal import Decimal

# Strawberry GraphQL — provides the @strawberry.type decorator that
# converts a Python class into a GraphQL schema type. The class body's
# type annotations (``tran_id: str``, ``amount: Decimal``, ...) become
# GraphQL schema fields; Strawberry reads the annotations at decoration
# time and generates the GraphQL introspection schema accordingly.
# Strawberry natively supports Decimal, rendering it as a GraphQL
# scalar that preserves the string decimal representation on the wire
# (no float round-tripping).
import strawberry

# Transaction — the SQLAlchemy 2.x ORM model representing a single row
# of the Aurora PostgreSQL ``transactions`` table (the relational
# successor to the VSAM ``TRANFILE`` KSDS dataset with its AIX on
# TRAN-PROC-TS). Used as the parameter type annotation for the
# ``from_model`` static factory below. The factory reads exactly
# thirteen attributes from this model — one per declared GraphQL
# field — corresponding to the 13 non-FILLER fields of the COBOL
# ``TRAN-RECORD`` layout.
from src.shared.models.transaction import Transaction


# ----------------------------------------------------------------------------
# TransactionType — Strawberry GraphQL type for TRAN-RECORD
# ----------------------------------------------------------------------------
@strawberry.type
class TransactionType:
    """GraphQL type representing a credit-card transaction.

    Maps COBOL ``TRAN-RECORD`` (``app/cpy/CVTRA05Y.cpy``, 350 bytes)
    to a Strawberry GraphQL schema type consumed by the ``transaction``
    / ``transactions`` query resolvers in ``src.api.graphql.queries``
    and the ``add_transaction`` / ``pay_bill`` mutation resolvers in
    ``src.api.graphql.mutations``.

    The single monetary field ``amount`` uses :class:`decimal.Decimal`
    — **NEVER** :class:`float`. This preserves the COBOL
    ``PIC S9(09)V99`` decimal semantics required by the
    financial-precision rule in AAP §0.7.2 ("no floating-point
    arithmetic is permitted for any financial calculation").

    Attributes
    ----------
    tran_id : str
        16-character transaction ID. Maps to COBOL ``TRAN-ID``
        (``PIC X(16)``) and to the SQLAlchemy ``Transaction.tran_id``
        primary-key column (``String(16)``). Auto-assigned by the
        online "Transaction Add" service (``COTRN02C`` →
        ``transaction_service.add_transaction``) for interactive
        additions — the service performs the equivalent of the COBOL
        ``EXEC CICS STARTBR`` + ``READPREV`` to locate the current
        maximum ID and zero-pads the incremented value to 16 chars —
        or propagated from the ``daily_transaction`` staging row for
        batch-posted transactions (``CBTRN02C`` → ``posttran_job``).
        This is the logical identifier used across statement
        generation (``CBSTM03A``), transaction reporting
        (``CBTRN03C``), and audit trails.
    type_cd : str
        2-character transaction-type code. Maps to COBOL
        ``TRAN-TYPE-CD`` (``PIC X(02)``) and to the
        ``Transaction.type_cd`` column (``String(2)``). Logical
        foreign key into the ``transaction_type`` lookup table.
        Typical values include purchase, refund, payment,
        cash-advance, fee, and interest. Validated by POSTTRAN
        (reject code 103 = "Unknown Type Code") and used with
        ``cat_cd`` to determine which
        ``TransactionCategoryBalance`` bucket is updated during
        posting.
    cat_cd : str
        4-character transaction-category code. Maps to COBOL
        ``TRAN-CAT-CD`` (``PIC 9(04)``) and to the
        ``Transaction.cat_cd`` column (``String(4)``). Stored as
        ``str`` rather than integer to preserve leading zeros from
        migrated VSAM records byte-for-byte (e.g., ``'0001'`` is
        distinct from ``'1'`` in the category lookup). Logical
        foreign key into the ``transaction_category`` lookup
        (composite with ``type_cd``). Drives which
        ``transaction_category_balance`` bucket is updated during
        POSTTRAN and which interest rate applies during INTCALC
        (via the ``disclosure_group`` table, with DEFAULT / ZEROAPR
        fallback per AAP §0.7.1).
    source : str
        Up to 10-character transaction-source code. Maps to COBOL
        ``TRAN-SOURCE`` (``PIC X(10)``) and to the
        ``Transaction.source`` column (``String(10)``). Identifies
        the upstream system that produced the record (e.g.,
        ``'POS'``, ``'ATM'``, ``'ONLINE'``, ``'BATCH'``). Primarily
        used for audit, reconciliation, and transaction reporting
        (TRANREPT).
    description : str
        Up to 100-character free-text transaction description. Maps
        to COBOL ``TRAN-DESC`` (``PIC X(100)``) and to the
        ``Transaction.description`` column (``String(100)``).
        Printed on customer statements (CREASTMT) and transaction
        reports (TRANREPT). **Intentionally wider than the BMS
        screen contract** — the COBOL Transaction Detail screen
        truncates the description to 60 characters
        (``TDESCI PIC X(60)`` in ``COTRN01.CPY``) to fit the 24x80
        3270 terminal line; GraphQL clients have no such constraint
        and receive the full 100-character value.
    amount : decimal.Decimal
        **Monetary.** Transaction amount. Maps to COBOL ``TRAN-AMT``
        (``PIC S9(09)V99``) and to the ``Transaction.amount`` column
        (``NUMERIC(15, 2)``). Sign convention follows COBOL
        ``S9(...)`` — positive values indicate **debits**
        (purchases, fees, cash advances, interest accruals) and
        negative values indicate **credits** (payments, refunds).
        Summed into the matching ``TransactionCategoryBalance``
        bucket during POSTTRAN and applied to ``Account.curr_bal``
        during the same database transaction. **Never** represented
        as a floating-point number (AAP §0.7.2 — Financial
        Precision).
    merchant_id : str
        9-character merchant identifier. Maps to COBOL
        ``TRAN-MERCHANT-ID`` (``PIC 9(09)``) and to the
        ``Transaction.merchant_id`` column (``String(9)``). Stored
        as ``str`` to preserve leading zeros from migrated records.
        Logical foreign key into the external merchant directory
        (not a CardDemo-owned table). Empty string for
        non-merchant transactions (e.g., payments, fees, interest
        accruals).
    merchant_name : str
        Up to 50-character merchant name. Maps to COBOL
        ``TRAN-MERCHANT-NAME`` (``PIC X(50)``) and to the
        ``Transaction.merchant_name`` column (``String(50)``).
        Denormalised snapshot of the merchant-directory name,
        captured at transaction time so that statements and
        reports render the historical name even if the directory
        is later updated. Empty string for non-merchant
        transactions.
    merchant_city : str
        Up to 50-character merchant city. Maps to COBOL
        ``TRAN-MERCHANT-CITY`` (``PIC X(50)``) and to the
        ``Transaction.merchant_city`` column (``String(50)``).
        Denormalised like ``merchant_name``. Empty string for
        non-merchant transactions.
    merchant_zip : str
        Up to 10-character merchant ZIP/postal code. Maps to COBOL
        ``TRAN-MERCHANT-ZIP`` (``PIC X(10)``) and to the
        ``Transaction.merchant_zip`` column (``String(10)``).
        Accommodates both US 5-digit and US 9-digit ZIP+4 formats
        (with or without the ``-`` separator). Empty string for
        non-merchant transactions.
    card_num : str
        16-character card number. Maps to COBOL ``TRAN-CARD-NUM``
        (``PIC X(16)``) and to the ``Transaction.card_num`` column
        (``String(16)``). Logical foreign key into the
        ``card_cross_reference`` table — resolved to the associated
        ``acct_id`` during POSTTRAN (reject code 104 if the card is
        unknown) and during online transaction-add (``COTRN02C``)
        to attach the transaction to the correct account.
    orig_ts : str
        26-character origination timestamp. Maps to COBOL
        ``TRAN-ORIG-TS`` (``PIC X(26)``) and to the
        ``Transaction.orig_ts`` column (``String(26)``). COBOL
        display format ``YYYY-MM-DD-HH.MM.SS.NNNNNN`` preserved
        verbatim for round-trip fidelity to the VSAM source.
        Denotes when the transaction originated at the upstream
        system (POS terminal, ATM, online portal, etc.).
        **Intentionally wider than the BMS screen contract** — the
        COBOL Transaction Detail screen truncates to 10 characters
        (``TORIGDTI PIC X(10)`` in ``COTRN01.CPY`` — date only); the
        GraphQL API preserves the full microsecond-precision
        timestamp.
    proc_ts : str
        26-character processing timestamp. Maps to COBOL
        ``TRAN-PROC-TS`` (``PIC X(26)``) and to the
        ``Transaction.proc_ts`` column (``String(26)``). COBOL
        display format preserved verbatim. Denotes when the
        transaction was processed and posted. **Indexed** on the
        ORM side by ``ix_transaction_proc_ts`` (B-tree) to support
        efficient date-range queries from CREASTMT (statement
        generation) and TRANREPT (transaction reports) — this
        index replicates the mainframe ``TRANFILE.AIX`` VSAM
        alternate index. **Intentionally wider than the BMS screen
        contract** — same rationale as ``orig_ts`` above.
    """

    # ------------------------------------------------------------------
    # tran_id — 16-char transaction ID
    # (COBOL ``TRAN-ID`` PIC X(16); ORM column ``tran_id`` String(16)).
    # GraphQL primary key; unique per transaction. Auto-assigned by
    # COTRN02C (online add) or propagated from the daily-batch staging
    # row (CBTRN02C). Matches BMS symbolic map field TRNIDI PIC X(16)
    # from COTRN01.CPY (full width preserved — no truncation).
    # ------------------------------------------------------------------
    tran_id: str

    # ------------------------------------------------------------------
    # type_cd — 2-char transaction type code
    # (COBOL ``TRAN-TYPE-CD`` PIC X(02); ORM column ``type_cd``
    # String(2)). Logical FK into ``transaction_type``. Validated by
    # POSTTRAN (reject code 103 = "Unknown Type Code"). Matches BMS
    # symbolic map field TTYPCDI PIC X(2) from COTRN01.CPY.
    # ------------------------------------------------------------------
    type_cd: str

    # ------------------------------------------------------------------
    # cat_cd — 4-char transaction category code
    # (COBOL ``TRAN-CAT-CD`` PIC 9(04); ORM column ``cat_cd``
    # String(4)). Stored as str — not numeric — to preserve leading
    # zeros from migrated VSAM records. Logical FK into
    # ``transaction_category`` (composite with ``type_cd``). Drives
    # which ``transaction_category_balance`` bucket is updated during
    # POSTTRAN and which interest rate applies during INTCALC.
    # Matches BMS symbolic map field TCATCDI PIC X(4) from COTRN01.CPY.
    # ------------------------------------------------------------------
    cat_cd: str

    # ------------------------------------------------------------------
    # source — 10-char transaction source
    # (COBOL ``TRAN-SOURCE`` PIC X(10); ORM column ``source``
    # String(10)). Identifies upstream system: 'POS', 'ATM', 'ONLINE',
    # 'BATCH', etc. Used for audit and reconciliation reporting on
    # TRANREPT. Matches BMS field TRNSRCI PIC X(10) from COTRN01.CPY.
    # ------------------------------------------------------------------
    source: str

    # ------------------------------------------------------------------
    # description — up to 100-char free-text description
    # (COBOL ``TRAN-DESC`` PIC X(100); ORM column ``description``
    # String(100)). Printed on customer statements (CREASTMT) and
    # transaction reports (TRANREPT). FULL 100-char width preserved
    # in the GraphQL API even though the BMS symbolic map COTRN01.CPY
    # truncates to 60 chars (TDESCI PIC X(60)) — the truncation is a
    # 24x80 3270 terminal rendering concern that does not apply to
    # JSON/GraphQL.
    # ------------------------------------------------------------------
    description: str

    # ------------------------------------------------------------------
    # amount — MONETARY (COBOL ``TRAN-AMT`` PIC S9(09)V99;
    # ORM column ``amount`` NUMERIC(15, 2)).
    # CRITICAL: Decimal type, NEVER float. Preserves COBOL
    # PIC S9(09)V99 decimal semantics exactly — AAP §0.7.2
    # "Financial Precision". Sign convention: positive = debit
    # (purchase, fee, cash advance, interest accrual), negative =
    # credit (payment, refund). Summed into TransactionCategoryBalance
    # bucket and applied to Account.curr_bal during POSTTRAN dual-write.
    # BMS display format: TRNAMTI PIC X(12) — the 12-char character
    # display is a terminal rendering concern; the API always returns
    # the Decimal value as a GraphQL scalar (string representation on
    # the wire via Strawberry's Decimal scalar).
    # ------------------------------------------------------------------
    amount: Decimal

    # ------------------------------------------------------------------
    # merchant_id — 9-char merchant ID
    # (COBOL ``TRAN-MERCHANT-ID`` PIC 9(09); ORM column ``merchant_id``
    # String(9)). Stored as str to preserve leading zeros. Default
    # empty string for non-merchant transactions (payments, fees,
    # interest accrual rows). Matches BMS field MIDI PIC X(9) from
    # COTRN01.CPY.
    # ------------------------------------------------------------------
    merchant_id: str

    # ------------------------------------------------------------------
    # merchant_name — up to 50-char merchant name
    # (COBOL ``TRAN-MERCHANT-NAME`` PIC X(50); ORM column
    # ``merchant_name`` String(50)). Denormalised snapshot of the
    # merchant directory name, captured at transaction time so that
    # statements and reports render the historical name even if the
    # directory is later updated. BMS truncates display to 30 chars
    # (MNAMEI PIC X(30) from COTRN01.CPY); the API returns the full
    # 50-char value.
    # ------------------------------------------------------------------
    merchant_name: str

    # ------------------------------------------------------------------
    # merchant_city — up to 50-char merchant city
    # (COBOL ``TRAN-MERCHANT-CITY`` PIC X(50); ORM column
    # ``merchant_city`` String(50)). Denormalised snapshot like
    # merchant_name. BMS truncates display to 25 chars (MCITYI PIC
    # X(25) from COTRN01.CPY); the API returns the full 50-char value.
    # ------------------------------------------------------------------
    merchant_city: str

    # ------------------------------------------------------------------
    # merchant_zip — up to 10-char merchant ZIP/postal code
    # (COBOL ``TRAN-MERCHANT-ZIP`` PIC X(10); ORM column
    # ``merchant_zip`` String(10)). Accommodates US 5-digit ZIP and
    # US 9-digit ZIP+4 (with or without '-'). Matches BMS field MZIPI
    # PIC X(10) from COTRN01.CPY.
    # ------------------------------------------------------------------
    merchant_zip: str

    # ------------------------------------------------------------------
    # card_num — 16-char card number
    # (COBOL ``TRAN-CARD-NUM`` PIC X(16); ORM column ``card_num``
    # String(16)). Logical FK into ``card_cross_reference`` — resolved
    # to the associated ``acct_id`` during POSTTRAN (reject code 104
    # if unknown) and during online transaction-add (COTRN02C) to
    # attach the transaction to the correct account. Matches BMS
    # field CARDNUMI PIC X(16) from COTRN01.CPY.
    # ------------------------------------------------------------------
    card_num: str

    # ------------------------------------------------------------------
    # orig_ts — 26-char origination timestamp
    # (COBOL ``TRAN-ORIG-TS`` PIC X(26); ORM column ``orig_ts``
    # String(26)). COBOL display format YYYY-MM-DD-HH.MM.SS.NNNNNN
    # preserved verbatim for round-trip fidelity to the VSAM source.
    # Denotes when the transaction originated at the upstream system.
    # FULL 26-char width preserved in the GraphQL API even though the
    # BMS symbolic map COTRN01.CPY truncates to 10 chars (TORIGDTI PIC
    # X(10), date only) — the truncation is a 3270 terminal rendering
    # concern that does not apply to JSON/GraphQL.
    # ------------------------------------------------------------------
    orig_ts: str

    # ------------------------------------------------------------------
    # proc_ts — 26-char processing timestamp
    # (COBOL ``TRAN-PROC-TS`` PIC X(26); ORM column ``proc_ts``
    # String(26) with ix_transaction_proc_ts B-tree index). COBOL
    # display format preserved verbatim. Denotes when the transaction
    # was processed and posted. The ORM-level B-tree index replicates
    # the mainframe TRANFILE.AIX VSAM alternate index and supports
    # efficient date-range queries from CREASTMT and TRANREPT. FULL
    # 26-char width preserved — see orig_ts commentary.
    # ------------------------------------------------------------------
    proc_ts: str

    # ------------------------------------------------------------------
    # NOTE (intentional omission, do not remove this comment):
    #
    # The COBOL ``FILLER`` field (``PIC X(20)``) — the trailing 20
    # bytes of padding in the original 350-byte VSAM record — is NOT
    # declared as a GraphQL field on this type. In the relational
    # model, column widths are explicit and trailing padding has no
    # storage or semantic meaning. The ORM model
    # (src.shared.models.transaction.Transaction) makes the identical
    # choice — the 13 declared GraphQL fields correspond one-to-one
    # with the 13 SQLAlchemy mapped columns on that model.
    #
    # Unlike some other CardDemo entities (e.g., Account, Card),
    # Transaction has NO optimistic-concurrency ``version_id`` column
    # because the transaction history is append-only — rows are
    # INSERTed by POSTTRAN / INTCALC / Transaction Add / Bill Payment
    # but never UPDATEd in place. No concurrency token needs to be
    # exposed or hidden.
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # from_model — factory method mapping Transaction → TransactionType.
    # ------------------------------------------------------------------
    @staticmethod
    def from_model(transaction: Transaction) -> "TransactionType":
        """Convert a SQLAlchemy :class:`Transaction` row to a GraphQL :class:`TransactionType`.

        This factory is the single allowed conversion point from the
        ORM layer to the GraphQL layer for transaction records. It is
        deliberately written to read **exactly thirteen** attributes
        from the input :class:`Transaction` instance — one per
        declared GraphQL field, corresponding to the 13 non-FILLER
        fields of the original 350-byte COBOL ``TRAN-RECORD`` layout.

        The single monetary value (``amount``) is forwarded unchanged
        from the ORM layer. The SQLAlchemy ORM's PostgreSQL
        ``NUMERIC(15, 2)`` column type is materialized as
        :class:`decimal.Decimal` by the ``asyncpg`` and ``psycopg2``
        drivers, so the factory never performs type conversion on this
        field — the AAP §0.7.2 "no floating-point arithmetic" contract
        is preserved end-to-end from Aurora PostgreSQL through the
        GraphQL response.

        Parameters
        ----------
        transaction : Transaction
            A SQLAlchemy ORM row fetched from the ``transactions``
            table. The caller is responsible for ensuring the row is
            not ``None``; this factory does not perform null checks
            (query resolvers should return ``None`` directly when the
            transaction is not found, without invoking this factory).

        Returns
        -------
        TransactionType
            A newly constructed :class:`TransactionType` instance
            containing exactly the thirteen fields that constitute
            the GraphQL transaction contract. The returned instance
            is a plain Strawberry type — it has no database session
            reference and may be safely returned from an async
            resolver without the "detached ORM row" pitfalls that
            SQLAlchemy would otherwise enforce on a session-bound
            instance.

        Notes
        -----
        The ``amount`` field is returned as a :class:`decimal.Decimal`
        unchanged — no ``float`` coercion occurs at any point in this
        factory. A future refactor that introduced, for example, a
        ``display_amount`` field formatted for BMS-style rendering
        (``TRNAMTI PIC X(12)`` style) should be added as a separate
        GraphQL field and MUST still compute its value using Decimal
        arithmetic, never via :class:`float` intermediate values.

        Timestamp fields (``orig_ts``, ``proc_ts``) are forwarded as
        26-character COBOL display-format strings
        (``YYYY-MM-DD-HH.MM.SS.NNNNNN``). Parsing to :class:`datetime`
        (if required by a client) is the client's responsibility; the
        factory never coerces timestamps to native datetime types.
        """
        # ------------------------------------------------------------------
        # Explicit field-by-field copy. Do NOT rewrite this as an
        # ``__dict__`` splat, ``**vars(transaction)`` expansion, or any
        # generic attribute-forwarding idiom — such idioms would also
        # copy SQLAlchemy-internal attributes (e.g., ``_sa_instance_state``)
        # into the returned Strawberry instance, which would fail at
        # GraphQL serialization time. Explicit is safer and more
        # auditable.
        # ------------------------------------------------------------------
        return TransactionType(
            # COBOL TRAN-ID (PIC X(16)) — 16-char transaction ID.
            tran_id=transaction.tran_id,
            # COBOL TRAN-TYPE-CD (PIC X(02)) — 2-char type code.
            type_cd=transaction.type_cd,
            # COBOL TRAN-CAT-CD (PIC 9(04)) — 4-char category code,
            # stored as str to preserve leading zeros.
            cat_cd=transaction.cat_cd,
            # COBOL TRAN-SOURCE (PIC X(10)) — source system code.
            source=transaction.source,
            # COBOL TRAN-DESC (PIC X(100)) — full 100-char description
            # (wider than the BMS 60-char display).
            description=transaction.description,
            # COBOL TRAN-AMT (PIC S9(09)V99) — Decimal, NEVER float.
            # Positive = debit; negative = credit.
            amount=transaction.amount,
            # COBOL TRAN-MERCHANT-ID (PIC 9(09)) — 9-char merchant ID,
            # stored as str to preserve leading zeros.
            merchant_id=transaction.merchant_id,
            # COBOL TRAN-MERCHANT-NAME (PIC X(50)) — full 50-char name
            # (wider than BMS 30-char display).
            merchant_name=transaction.merchant_name,
            # COBOL TRAN-MERCHANT-CITY (PIC X(50)) — full 50-char city
            # (wider than BMS 25-char display).
            merchant_city=transaction.merchant_city,
            # COBOL TRAN-MERCHANT-ZIP (PIC X(10)) — US ZIP or ZIP+4.
            merchant_zip=transaction.merchant_zip,
            # COBOL TRAN-CARD-NUM (PIC X(16)) — 16-char card number.
            card_num=transaction.card_num,
            # COBOL TRAN-ORIG-TS (PIC X(26)) — full 26-char timestamp
            # YYYY-MM-DD-HH.MM.SS.NNNNNN (wider than BMS 10-char date).
            orig_ts=transaction.orig_ts,
            # COBOL TRAN-PROC-TS (PIC X(26)) — full 26-char timestamp,
            # indexed on the ORM side by ix_transaction_proc_ts.
            proc_ts=transaction.proc_ts,
            # NOTE: COBOL FILLER (PIC X(20)) is INTENTIONALLY not
            # accessed here. Trailing padding has no storage or
            # semantic meaning in the relational / GraphQL models.
        )
