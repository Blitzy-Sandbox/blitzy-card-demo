# ============================================================================
# Source: COBOL copybook CVACT01Y.cpy — ACCOUNT-RECORD (80 bytes base +
#         5 monetary fields + 5 char fields = 300 bytes fixed-length record)
#         BMS symbolic map COACTVW.CPY — Account view screen
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
"""Strawberry GraphQL type for the Account entity.

Source: COBOL copybook ``app/cpy/CVACT01Y.cpy`` — ``ACCOUNT-RECORD``
(300-byte fixed-length record layout, the central financial entity of
the CardDemo domain model).
BMS symbolic map: ``app/cpy-bms/COACTVW.CPY`` — Account view screen,
which defines the AI/AO field mapping for the COBOL online Account
View transaction (``COACTVWC``, F-004) and confirms the per-field
display formats (e.g., ``ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99`` for credit
limit display, ``ACSTTUSI PIC X(1)`` for status, ``ACCTSIDI PIC
99999999999`` for the 11-digit account ID).

Mainframe-to-Cloud migration: VSAM KSDS ``ACCTFILE`` → Aurora PostgreSQL
``accounts`` table → GraphQL API via Strawberry.

COBOL to GraphQL Field Mapping
------------------------------
========================  =================  ====================  =====================
COBOL Field               COBOL Type         GraphQL Field         Python Type
========================  =================  ====================  =====================
ACCT-ID                   ``PIC 9(11)``      ``acct_id``           ``str``
ACCT-ACTIVE-STATUS        ``PIC X(01)``      ``active_status``     ``str``
ACCT-CURR-BAL             ``PIC S9(10)V99``  ``curr_bal``          ``Decimal`` †
ACCT-CREDIT-LIMIT         ``PIC S9(10)V99``  ``credit_limit``      ``Decimal`` †
ACCT-CASH-CREDIT-LIMIT    ``PIC S9(10)V99``  ``cash_credit_limit`` ``Decimal`` †
ACCT-OPEN-DATE            ``PIC X(10)``      ``open_date``         ``str``
ACCT-EXPIRAION-DATE ‡     ``PIC X(10)``      ``expiration_date``   ``str``
ACCT-REISSUE-DATE         ``PIC X(10)``      ``reissue_date``      ``str``
ACCT-CURR-CYC-CREDIT      ``PIC S9(10)V99``  ``curr_cyc_credit``   ``Decimal`` †
ACCT-CURR-CYC-DEBIT       ``PIC S9(10)V99``  ``curr_cyc_debit``    ``Decimal`` †
ACCT-ADDR-ZIP             ``PIC X(10)``      ``addr_zip``          ``str``
ACCT-GROUP-ID             ``PIC X(10)``      ``group_id``          ``str``
FILLER                    ``PIC X(178)``     — (not mapped)        — (COBOL padding)
========================  =================  ====================  =====================

† **Monetary fields.** All five monetary fields use :class:`decimal.Decimal`
  — **NEVER** :class:`float`. Floating-point arithmetic is prohibited
  for financial calculations across the entire CardDemo codebase (see
  AAP §0.7.2 "Financial Precision"). The COBOL ``PIC S9(10)V99`` source
  semantics (13 integer digits + 2 decimals, signed) are preserved via
  the ORM layer's PostgreSQL ``NUMERIC(15, 2)`` column type — which the
  ``asyncpg`` / ``psycopg2`` drivers materialize as Python ``Decimal``
  instances automatically. No coercion occurs in this type; the
  ``from_model`` factory simply forwards the ORM attribute value
  unchanged.

‡ The original COBOL field name ``ACCT-EXPIRAION-DATE`` contains a
  historical typo (should be ``-EXPIRATION-``). The Python field is
  renamed to the corrected spelling ``expiration_date`` because the
  COBOL-to-Python mapping is purely semantic (the relational schema
  has no byte-level coupling to COBOL field names). The COBOL typo is
  preserved in the retained ``app/cpy/CVACT01Y.cpy`` source artifact
  (AAP §0.7.1 "do not modify the original COBOL source files"). This
  rename matches the corresponding rename in
  :class:`src.shared.models.account.Account`, which is the source of
  data for ``AccountType.from_model()``.

Total RECLN: 11 + 1 + (12 × 5) + (10 × 5) + 178 = 300 bytes — matches
the VSAM cluster definition in ``app/jcl/ACCTFILE.jcl``
(``RECSZ(300 300)``). The trailing 178-byte FILLER is explicitly
**not** mapped to a GraphQL field; in the relational model, column
widths are explicit and trailing padding has no storage or semantic
meaning.

Consumer Resolvers
------------------
Instances of :class:`AccountType` are returned by the following
Strawberry resolvers:

* ``src.api.graphql.queries.Query.account(acct_id)`` — single account
  view, corresponding to COBOL ``COACTVWC.cbl`` (F-004, Account View).
  The resolver reads a row from the Aurora PostgreSQL ``accounts``
  table and passes the ORM instance to :meth:`AccountType.from_model`
  to produce the GraphQL response object.
* ``src.api.graphql.queries.Query.accounts(...)`` — paginated list of
  accounts. Each row in the result list is produced via
  :meth:`AccountType.from_model`.
* ``src.api.graphql.mutations.Mutation.update_account(input)`` —
  corresponding to COBOL ``COACTUPC.cbl`` (F-005, Account Update, the
  ~4,236-line dual-write program with ``SYNCPOINT ROLLBACK``). The
  mutation returns the updated account as an :class:`AccountType`,
  again via :meth:`AccountType.from_model`.

Design Notes
------------
* **snake_case field names** match the SQLAlchemy model column names
  (``acct_id``, ``active_status``, ``curr_bal``, etc.) and the Aurora
  PostgreSQL DDL column names from ``db/migrations/V1__schema.sql``.
  Strawberry's default ``snake_case → camelCase`` transformation will
  surface these to GraphQL clients as ``acctId``, ``activeStatus``,
  ``currBal``, ``creditLimit``, etc. — a GraphQL convention standard
  across the ecosystem.
* **Zero-padded 11-digit account ID as ``str``.** The COBOL ``ACCT-ID``
  field (``PIC 9(11)``) is represented as a Python ``str`` rather than
  ``int`` so that leading zeros from migrated VSAM records are
  preserved byte-for-byte. This matches the ORM column type
  ``String(11)`` on :class:`Account.acct_id` and the VSAM cluster key
  length (``KEYS(11 0)``) from ``app/jcl/ACCTFILE.jcl``.
* **Date fields as ``str``, not ``date``.** The three 10-character
  date fields (``open_date``, ``expiration_date``, ``reissue_date``)
  are represented as ``str`` (format ``YYYY-MM-DD``) to match the
  COBOL ``PIC X(10)`` source layout and the ORM ``String(10)`` column
  type. Date validation is delegated to the
  ``src.shared.utils.date_utils`` helpers, which preserve the
  ``CSUTLDTC`` validation rules. No ``date`` type coercion occurs at
  either the ORM layer or the GraphQL layer.
* **No FILLER mapping.** The trailing 178 bytes of COBOL padding
  (``FILLER PIC X(178)``) have no relational or GraphQL counterpart
  and are therefore not declared here.
* **No ``version_id`` field.** The SQLAlchemy ORM carries an additional
  ``version_id`` column for optimistic concurrency (replacing the
  CICS ``READ UPDATE`` / ``REWRITE`` protocol from ``COACTUPC.cbl``).
  This is a server-side implementation detail and is deliberately NOT
  exposed via the GraphQL API — clients should not be able to read or
  manipulate the concurrency token directly. The mutation layer
  handles version mismatches by raising a GraphQL error.
* **Python 3.11+** only. Aligned with the AWS Glue 5.1 runtime
  baseline and the FastAPI / ECS Fargate deployment target.

Financial Precision Contract
----------------------------
The five monetary fields (``curr_bal``, ``credit_limit``,
``cash_credit_limit``, ``curr_cyc_credit``, ``curr_cyc_debit``) MUST
hold :class:`decimal.Decimal` instances with exactly two decimal
places. The SQLAlchemy ORM guarantees this on the read path
(PostgreSQL ``NUMERIC(15, 2)`` materializes as ``Decimal`` with the
correct scale via ``asyncpg`` / ``psycopg2``); the GraphQL layer
never performs arithmetic on these values and therefore never risks
an accidental ``float`` coercion. Downstream consumers (batch jobs,
bill payment, account update) re-declare the precision contract at
the service layer.

The interest calculation formula ``(TRAN-CAT-BAL × DIS-INT-RATE) /
1200`` (from ``CBACT04C`` / ``intcalc_job.py``) is also strictly
``Decimal``; any conversion of these fields to or from ``float``
would violate the AAP §0.7.1 "preserve existing business logic"
requirement.

See Also
--------
* AAP §0.2.3 — Feature mapping for F-004 (Account View) and F-005
  (Account Update).
* AAP §0.5.1 — File-by-File Transformation Plan (``account_type.py``
  entry).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve existing
  business logic, dual-write atomicity).
* AAP §0.7.2 — Financial Precision and Security Requirements.
* :class:`src.shared.models.account.Account` — SQLAlchemy ORM model
  (the source of data for :meth:`AccountType.from_model`).
* ``app/cpy/CVACT01Y.cpy`` — Original COBOL record layout (source
  artifact, retained for traceability per AAP §0.7.1).
* ``app/cpy-bms/COACTVW.CPY`` — BMS symbolic map confirming the
  account view screen field layout.
* ``src.api.graphql.queries.Query.account`` / ``Query.accounts`` —
  the query resolvers that return instances of this type.
* ``src.api.graphql.mutations.Mutation.update_account`` — the
  mutation resolver that returns an updated instance of this type.
"""

# ----------------------------------------------------------------------------
# Imports
# ----------------------------------------------------------------------------
# Python standard library Decimal — the ONLY permissible numeric type
# for the five monetary fields (curr_bal, credit_limit,
# cash_credit_limit, curr_cyc_credit, curr_cyc_debit). Using float for
# these values is explicitly prohibited by AAP §0.7.2 ("Financial
# Precision"); the COBOL PIC S9(10)V99 source semantics demand exact
# decimal arithmetic, which only Decimal can provide in Python.
from decimal import Decimal

# Strawberry GraphQL — provides the @strawberry.type decorator that
# converts a Python class into a GraphQL schema type. The class body's
# type annotations (acct_id: str, curr_bal: Decimal, ...) become
# GraphQL schema fields; Strawberry reads the annotations at decoration
# time and generates the GraphQL introspection schema accordingly.
# Strawberry natively supports Decimal, rendering it as a GraphQL
# scalar that preserves the string decimal representation on the wire.
import strawberry

# Account — the SQLAlchemy 2.x ORM model representing a single row of
# the Aurora PostgreSQL ``accounts`` table (the relational successor
# to the VSAM ``ACCTFILE`` KSDS dataset). Used as the parameter type
# annotation for the ``from_model`` static factory below. The factory
# reads exactly twelve attributes from this model — one per GraphQL
# field — and deliberately does NOT read the ``version_id`` attribute
# (the optimistic-concurrency counter is a server-side implementation
# detail and must not leak across the ORM/GraphQL boundary).
from src.shared.models.account import Account


# ----------------------------------------------------------------------------
# AccountType — Strawberry GraphQL type for ACCOUNT-RECORD
# ----------------------------------------------------------------------------
@strawberry.type
class AccountType:
    """GraphQL type representing a credit card account.

    Maps COBOL ``ACCOUNT-RECORD`` (``app/cpy/CVACT01Y.cpy``, 300 bytes)
    to a Strawberry GraphQL schema type consumed by the ``account`` /
    ``accounts`` query resolvers in ``src.api.graphql.queries`` and the
    ``update_account`` mutation resolver in ``src.api.graphql.mutations``.

    All 5 monetary fields (``curr_bal``, ``credit_limit``,
    ``cash_credit_limit``, ``curr_cyc_credit``, ``curr_cyc_debit``) use
    :class:`decimal.Decimal` — **NEVER** :class:`float`. This preserves
    the COBOL ``PIC S9(10)V99`` decimal semantics required by the
    financial-precision rule in AAP §0.7.2 ("no floating-point
    arithmetic is permitted for any financial calculation").

    Attributes
    ----------
    acct_id : str
        11-character zero-padded account ID. Maps to COBOL ``ACCT-ID``
        (``PIC 9(11)``) and to the SQLAlchemy ``Account.acct_id``
        primary-key column (``String(11)``). Stored as a string rather
        than an integer to preserve leading zeros from migrated VSAM
        records byte-for-byte. This is the logical identifier used
        across every CardDemo transaction and the WHERE-clause key for
        all account-scoped queries.
    active_status : str
        1-character active-status flag. Maps to COBOL
        ``ACCT-ACTIVE-STATUS`` (``PIC X(01)``) and to the
        ``Account.active_status`` column (``String(1)``). Typical
        values: ``'Y'`` = active, ``'N'`` = inactive. Consulted by
        ``COACTVWC`` / ``COACTUPC`` online flows and the POSTTRAN batch
        reject-code cascade (reject code 100 = "Account Not Active").
    curr_bal : decimal.Decimal
        **Monetary.** Current outstanding account balance. Maps to
        COBOL ``ACCT-CURR-BAL`` (``PIC S9(10)V99``) and to the
        ``Account.curr_bal`` column (``NUMERIC(15, 2)``). Updated by
        the POSTTRAN batch, INTCALC interest accrual, COBIL00C bill
        payment, and COACTUPC account update flows. **Never**
        represented as a floating-point number.
    credit_limit : decimal.Decimal
        **Monetary.** Total credit limit on the account. Maps to COBOL
        ``ACCT-CREDIT-LIMIT`` (``PIC S9(10)V99``) and to the
        ``Account.credit_limit`` column (``NUMERIC(15, 2)``).
        Consulted by POSTTRAN during credit-limit validation (reject
        code 102 = "Over Credit Limit"). **Decimal, never float.**
    cash_credit_limit : decimal.Decimal
        **Monetary.** Cash-advance sub-limit. Maps to COBOL
        ``ACCT-CASH-CREDIT-LIMIT`` (``PIC S9(10)V99``) and to the
        ``Account.cash_credit_limit`` column (``NUMERIC(15, 2)``).
        Enforced by POSTTRAN when the transaction category code
        indicates a cash-advance transaction. **Decimal, never float.**
    open_date : str
        10-character account open date. Maps to COBOL ``ACCT-OPEN-DATE``
        (``PIC X(10)``) and to the ``Account.open_date`` column
        (``String(10)``). ISO-like format ``YYYY-MM-DD``.
    expiration_date : str
        10-character account expiration date. Maps to COBOL
        ``ACCT-EXPIRAION-DATE`` (``PIC X(10)``, note original COBOL
        typo — see module docstring) and to the
        ``Account.expiration_date`` column (``String(10)``). ISO-like
        format ``YYYY-MM-DD``. Consulted by POSTTRAN (reject code
        101 = "Account Expired").
    reissue_date : str
        10-character account reissue date. Maps to COBOL
        ``ACCT-REISSUE-DATE`` (``PIC X(10)``) and to the
        ``Account.reissue_date`` column (``String(10)``). ISO-like
        format ``YYYY-MM-DD``. Used for card reissue tracking.
    curr_cyc_credit : decimal.Decimal
        **Monetary.** Current billing cycle credit total. Maps to
        COBOL ``ACCT-CURR-CYC-CREDIT`` (``PIC S9(10)V99``) and to the
        ``Account.curr_cyc_credit`` column (``NUMERIC(15, 2)``). Sum
        of all credits (payments, refunds) posted within the current
        cycle. Zeroed at cycle close. **Decimal, never float.**
    curr_cyc_debit : decimal.Decimal
        **Monetary.** Current billing cycle debit total. Maps to COBOL
        ``ACCT-CURR-CYC-DEBIT`` (``PIC S9(10)V99``) and to the
        ``Account.curr_cyc_debit`` column (``NUMERIC(15, 2)``). Sum of
        all debits (purchases, cash advances, fees) posted within the
        current cycle. Zeroed at cycle close. **Decimal, never float.**
    addr_zip : str
        10-character ZIP/postal code. Maps to COBOL ``ACCT-ADDR-ZIP``
        (``PIC X(10)``) and to the ``Account.addr_zip`` column
        (``String(10)``). Accommodates both US 5-digit ZIP and US
        9-digit ZIP+4 formats (with or without the ``-`` separator).
    group_id : str
        10-character disclosure-group code. Maps to COBOL
        ``ACCT-GROUP-ID`` (``PIC X(10)``) and to the
        ``Account.group_id`` column (``String(10)``). Logical foreign
        key into the ``DisclosureGroup`` table — determines which
        interest-rate schedule applies during INTCALC. Common values:
        ``'DEFAULT'`` (standard APR), ``'ZEROAPR'`` (0% introductory
        rate). An empty or unknown group_id causes INTCALC to fall
        back to ``'DEFAULT'`` per AAP §0.7.1.
    """

    # ------------------------------------------------------------------
    # acct_id — 11-char zero-padded account ID
    # (COBOL ``ACCT-ID`` PIC 9(11); ORM column ``acct_id`` String(11)).
    # GraphQL primary key; unique per account. Stored as str rather
    # than int to preserve leading zeros from migrated VSAM records.
    # Matches BMS symbolic map field ACCTSIDI PIC 99999999999 (11
    # digits) from COACTVW.CPY.
    # ------------------------------------------------------------------
    acct_id: str

    # ------------------------------------------------------------------
    # active_status — 1-char active flag
    # (COBOL ``ACCT-ACTIVE-STATUS`` PIC X(01); ORM column
    # ``active_status`` String(1)). 'Y' = active, 'N' = inactive.
    # Matches BMS symbolic map field ACSTTUSI PIC X(1) from COACTVW.CPY.
    # ------------------------------------------------------------------
    active_status: str

    # ------------------------------------------------------------------
    # curr_bal — MONETARY (COBOL ``ACCT-CURR-BAL`` PIC S9(10)V99;
    # ORM column ``curr_bal`` NUMERIC(15, 2)).
    # CRITICAL: Decimal type, NEVER float. Preserves COBOL
    # PIC S9(10)V99 decimal semantics exactly — AAP §0.7.2
    # "Financial Precision".
    # Updated by POSTTRAN, INTCALC, COBIL00C bill payment, and
    # COACTUPC account update flows.
    # ------------------------------------------------------------------
    curr_bal: Decimal

    # ------------------------------------------------------------------
    # credit_limit — MONETARY (COBOL ``ACCT-CREDIT-LIMIT`` PIC S9(10)V99;
    # ORM column ``credit_limit`` NUMERIC(15, 2)).
    # CRITICAL: Decimal type, NEVER float.
    # Consulted by POSTTRAN (reject code 102 = "Over Credit Limit").
    # BMS display format: ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99.
    # ------------------------------------------------------------------
    credit_limit: Decimal

    # ------------------------------------------------------------------
    # cash_credit_limit — MONETARY
    # (COBOL ``ACCT-CASH-CREDIT-LIMIT`` PIC S9(10)V99;
    # ORM column ``cash_credit_limit`` NUMERIC(15, 2)).
    # CRITICAL: Decimal type, NEVER float.
    # Enforced by POSTTRAN for cash-advance transactions.
    # ------------------------------------------------------------------
    cash_credit_limit: Decimal

    # ------------------------------------------------------------------
    # open_date — 10-char open date
    # (COBOL ``ACCT-OPEN-DATE`` PIC X(10);
    # ORM column ``open_date`` String(10)).
    # YYYY-MM-DD format. Validation delegated to
    # src.shared.utils.date_utils (preserves CSUTLDTC rules).
    # ------------------------------------------------------------------
    open_date: str

    # ------------------------------------------------------------------
    # expiration_date — 10-char expiration date
    # (COBOL ``ACCT-EXPIRAION-DATE`` PIC X(10), note historical COBOL
    # typo; ORM column ``expiration_date`` String(10)).
    # YYYY-MM-DD format. Consulted by POSTTRAN
    # (reject code 101 = "Account Expired").
    # The COBOL typo is preserved in the retained app/cpy/CVACT01Y.cpy
    # source artifact; the Python field uses the corrected spelling.
    # ------------------------------------------------------------------
    expiration_date: str

    # ------------------------------------------------------------------
    # reissue_date — 10-char reissue date
    # (COBOL ``ACCT-REISSUE-DATE`` PIC X(10);
    # ORM column ``reissue_date`` String(10)).
    # YYYY-MM-DD format. Tracks the most recent card reissue event.
    # ------------------------------------------------------------------
    reissue_date: str

    # ------------------------------------------------------------------
    # curr_cyc_credit — MONETARY
    # (COBOL ``ACCT-CURR-CYC-CREDIT`` PIC S9(10)V99;
    # ORM column ``curr_cyc_credit`` NUMERIC(15, 2)).
    # CRITICAL: Decimal type, NEVER float.
    # Running total of cycle credits (payments, refunds).
    # Zeroed at cycle close.
    # ------------------------------------------------------------------
    curr_cyc_credit: Decimal

    # ------------------------------------------------------------------
    # curr_cyc_debit — MONETARY
    # (COBOL ``ACCT-CURR-CYC-DEBIT`` PIC S9(10)V99;
    # ORM column ``curr_cyc_debit`` NUMERIC(15, 2)).
    # CRITICAL: Decimal type, NEVER float.
    # Running total of cycle debits (purchases, cash advances, fees).
    # Zeroed at cycle close.
    # ------------------------------------------------------------------
    curr_cyc_debit: Decimal

    # ------------------------------------------------------------------
    # addr_zip — 10-char ZIP/postal code
    # (COBOL ``ACCT-ADDR-ZIP`` PIC X(10);
    # ORM column ``addr_zip`` String(10)).
    # Accommodates US 5-digit, US 9-digit ZIP+4 with or without '-'.
    # ------------------------------------------------------------------
    addr_zip: str

    # ------------------------------------------------------------------
    # group_id — 10-char disclosure-group ID
    # (COBOL ``ACCT-GROUP-ID`` PIC X(10);
    # ORM column ``group_id`` String(10)).
    # Logical FK into DisclosureGroup (CVTRA02Y.cpy). Drives INTCALC
    # interest-rate lookup. Common values: 'DEFAULT', 'ZEROAPR'.
    # Empty / unknown group IDs fall back to 'DEFAULT' per AAP §0.7.1.
    # ------------------------------------------------------------------
    group_id: str

    # ------------------------------------------------------------------
    # NOTE (intentional omissions, do not remove this comment):
    #
    # 1. The COBOL ``FILLER`` field (``PIC X(178)``) — the trailing
    #    178 bytes of padding in the original 300-byte VSAM record —
    #    is NOT declared here. In the relational model, column widths
    #    are explicit and trailing padding has no storage or semantic
    #    meaning.
    #
    # 2. The SQLAlchemy ``Account.version_id`` column
    #    (optimistic-concurrency counter, NOT from COBOL — introduced
    #    as part of the CICS READ UPDATE / REWRITE → SQLAlchemy
    #    migration) is NOT exposed as a GraphQL field. The concurrency
    #    token is a server-side implementation detail and must not
    #    leak across the ORM/GraphQL boundary. Clients do not need to
    #    see, compare, or submit this value; the mutation layer
    #    handles version mismatches by raising a GraphQL error
    #    (StaleDataError → "Record modified by another user"). This
    #    matches the CICS behavior in COACTUPC.cbl, where SYNCPOINT
    #    ROLLBACK is invoked internally on version mismatch without
    #    exposing the CICS RBA to the terminal user.
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # from_model — factory method mapping Account → AccountType.
    # ------------------------------------------------------------------
    @staticmethod
    def from_model(account: Account) -> "AccountType":
        """Convert a SQLAlchemy :class:`Account` row to a GraphQL :class:`AccountType`.

        This factory is the single allowed conversion point from the
        ORM layer to the GraphQL layer for account records. It is
        deliberately written to read **exactly twelve** attributes
        from the input :class:`Account` instance — one per declared
        GraphQL field — and to deliberately NOT read the
        ``version_id`` attribute. The optimistic-concurrency token is
        a server-side implementation detail and must not enter the
        GraphQL response path under any circumstances.

        All five monetary values (``curr_bal``, ``credit_limit``,
        ``cash_credit_limit``, ``curr_cyc_credit``, ``curr_cyc_debit``)
        are forwarded unchanged from the ORM layer. The SQLAlchemy
        ORM's PostgreSQL ``NUMERIC(15, 2)`` column type is materialized
        as :class:`decimal.Decimal` by the ``asyncpg`` and ``psycopg2``
        drivers, so the factory never performs type conversion on
        these fields — the AAP §0.7.2 "no floating-point arithmetic"
        contract is preserved end-to-end.

        Parameters
        ----------
        account : Account
            A SQLAlchemy ORM row fetched from the ``accounts`` table.
            The caller is responsible for ensuring the row is not
            ``None``; this factory does not perform null checks
            (query resolvers should return ``None`` directly when the
            account is not found, without invoking this factory).

        Returns
        -------
        AccountType
            A newly constructed :class:`AccountType` instance
            containing exactly the twelve fields that constitute the
            GraphQL account contract. The returned instance is a
            plain Strawberry type — it has no database session
            reference and may be safely returned from an async
            resolver without the "detached ORM row" pitfalls that
            SQLAlchemy would otherwise enforce on a session-bound
            instance.

        Notes
        -----
        Monetary fields are returned as :class:`decimal.Decimal`
        unchanged — no ``float`` coercion occurs at any point in this
        factory. A future refactor that introduced, for example, a
        ``display_balance`` field formatted for BMS-style rendering
        (``+ZZZ,ZZZ,ZZZ.99``) should be added as a separate GraphQL
        field and MUST still compute its value using Decimal
        arithmetic, never via :class:`float` intermediate values.
        """
        # ------------------------------------------------------------------
        # Explicit field-by-field copy. Do NOT rewrite this as an
        # ``__dict__`` splat, ``**vars(account)`` expansion, or any
        # generic attribute-forwarding idiom — those would inadvertently
        # copy ``version_id`` (and any future internal attributes) into
        # the returned object, violating the server-side-detail
        # isolation described in the module-level and class-level
        # docstrings. Explicit is safer and more auditable.
        # ------------------------------------------------------------------
        return AccountType(
            # COBOL ACCT-ID (PIC 9(11)) — 11-char zero-padded string.
            acct_id=account.acct_id,
            # COBOL ACCT-ACTIVE-STATUS (PIC X(01)) — 'Y' or 'N'.
            active_status=account.active_status,
            # COBOL ACCT-CURR-BAL (PIC S9(10)V99) — Decimal, never float.
            curr_bal=account.curr_bal,
            # COBOL ACCT-CREDIT-LIMIT (PIC S9(10)V99) — Decimal, never float.
            credit_limit=account.credit_limit,
            # COBOL ACCT-CASH-CREDIT-LIMIT (PIC S9(10)V99) — Decimal.
            cash_credit_limit=account.cash_credit_limit,
            # COBOL ACCT-OPEN-DATE (PIC X(10)) — YYYY-MM-DD string.
            open_date=account.open_date,
            # COBOL ACCT-EXPIRAION-DATE (PIC X(10), note COBOL typo) —
            # Python field uses corrected spelling 'expiration_date'.
            expiration_date=account.expiration_date,
            # COBOL ACCT-REISSUE-DATE (PIC X(10)) — YYYY-MM-DD string.
            reissue_date=account.reissue_date,
            # COBOL ACCT-CURR-CYC-CREDIT (PIC S9(10)V99) — Decimal.
            curr_cyc_credit=account.curr_cyc_credit,
            # COBOL ACCT-CURR-CYC-DEBIT (PIC S9(10)V99) — Decimal.
            curr_cyc_debit=account.curr_cyc_debit,
            # COBOL ACCT-ADDR-ZIP (PIC X(10)) — US ZIP or ZIP+4.
            addr_zip=account.addr_zip,
            # COBOL ACCT-GROUP-ID (PIC X(10)) — 'DEFAULT', 'ZEROAPR', etc.
            group_id=account.group_id,
            # NOTE: account.version_id is INTENTIONALLY not accessed
            # here. The optimistic-concurrency counter never crosses
            # the ORM/GraphQL boundary.
        )
