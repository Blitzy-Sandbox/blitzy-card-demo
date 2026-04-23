# =============================================================================
# Source: COBOL copybook CVACT01Y.cpy — ACCOUNT-RECORD (300 bytes, VSAM KSDS)
# Tests validate field types, constraints, defaults, PK, optimistic
#        concurrency, NOT NULL constraints, __repr__ behavior, and FILLER
#        column exclusion.
# =============================================================================
"""Unit tests for :class:`src.shared.models.account.Account`.

This module exercises every observable contract of the SQLAlchemy 2.x
``Account`` ORM model, which is the relational translation of the legacy
COBOL copybook ``app/cpy/CVACT01Y.cpy`` (``ACCOUNT-RECORD``).

COBOL Source Record Layout — ``app/cpy/CVACT01Y.cpy``
-----------------------------------------------------

The original VSAM KSDS record is 300 bytes long with the 11-digit
``ACCT-ID`` field as the primary key (offset 0, length 11). The copybook's
``ACCOUNT-RECORD`` 01-level group defines the following fields::

    01  ACCOUNT-RECORD.
        05  ACCT-ID                           PIC 9(11).
        05  ACCT-ACTIVE-STATUS                PIC X(01).
        05  ACCT-CURR-BAL                     PIC S9(10)V99.
        05  ACCT-CREDIT-LIMIT                 PIC S9(10)V99.
        05  ACCT-CASH-CREDIT-LIMIT            PIC S9(10)V99.
        05  ACCT-OPEN-DATE                    PIC X(10).
        05  ACCT-EXPIRAION-DATE               PIC X(10).  [sic — typo in source]
        05  ACCT-REISSUE-DATE                 PIC X(10).
        05  ACCT-CURR-CYC-CREDIT              PIC S9(10)V99.
        05  ACCT-CURR-CYC-DEBIT               PIC S9(10)V99.
        05  ACCT-ADDR-ZIP                     PIC X(10).
        05  ACCT-GROUP-ID                     PIC X(10).
        05  FILLER                            PIC X(178).

                                     Total: 300 bytes (RECLN=300)

The field typo ``ACCT-EXPIRAION-DATE`` (missing the second 'T') is
preserved *verbatim* in the COBOL source. The Python translation uses
the corrected name ``expiration_date`` because the relational schema
is the canonical forward-going contract, and because this column name
is used across the online program ``COACTUPC.cbl`` (Account Update),
the batch programs ``CBTRN02C.cbl`` (POSTTRAN), and ``CBSTM03A.CBL``
(Statement Generation) regardless of the copybook declaration typo.
Per AAP §0.7.1 "do not modify original COBOL source", the typo is
retained in ``app/cpy/CVACT01Y.cpy`` for traceability, while the
Python column name is normalised.

COBOL -> Python Field Mapping
-----------------------------

+--------------------------+----------------+------------------------+----------+
| COBOL Field              | PIC Clause     | Python Column          | SA Type  |
+==========================+================+========================+==========+
| ACCT-ID                  | PIC 9(11)      | ``acct_id`` (PK)       | String   |
+--------------------------+----------------+------------------------+----------+
| ACCT-ACTIVE-STATUS       | PIC X(01)      | ``active_status``      | String   |
+--------------------------+----------------+------------------------+----------+
| ACCT-CURR-BAL            | PIC S9(10)V99  | ``curr_bal``           | Numeric  |
+--------------------------+----------------+------------------------+----------+
| ACCT-CREDIT-LIMIT        | PIC S9(10)V99  | ``credit_limit``       | Numeric  |
+--------------------------+----------------+------------------------+----------+
| ACCT-CASH-CREDIT-LIMIT   | PIC S9(10)V99  | ``cash_credit_limit``  | Numeric  |
+--------------------------+----------------+------------------------+----------+
| ACCT-OPEN-DATE           | PIC X(10)      | ``open_date``          | String   |
+--------------------------+----------------+------------------------+----------+
| ACCT-EXPIRAION-DATE      | PIC X(10)      | ``expiration_date``    | String   |
+--------------------------+----------------+------------------------+----------+
| ACCT-REISSUE-DATE        | PIC X(10)      | ``reissue_date``       | String   |
+--------------------------+----------------+------------------------+----------+
| ACCT-CURR-CYC-CREDIT     | PIC S9(10)V99  | ``curr_cyc_credit``    | Numeric  |
+--------------------------+----------------+------------------------+----------+
| ACCT-CURR-CYC-DEBIT      | PIC S9(10)V99  | ``curr_cyc_debit``     | Numeric  |
+--------------------------+----------------+------------------------+----------+
| ACCT-ADDR-ZIP            | PIC X(10)      | ``addr_zip``           | String   |
+--------------------------+----------------+------------------------+----------+
| ACCT-GROUP-ID            | PIC X(10)      | ``group_id``           | String   |
+--------------------------+----------------+------------------------+----------+
| FILLER                   | PIC X(178)     | *(not mapped)*         | —        |
+--------------------------+----------------+------------------------+----------+
| *(new — Python)*         | —              | ``version_id``         | Integer  |
+--------------------------+----------------+------------------------+----------+

Monetary Field Precision — Why ``Numeric(15, 2)`` Never ``Float``
-----------------------------------------------------------------

The 5 monetary fields (``curr_bal``, ``credit_limit``,
``cash_credit_limit``, ``curr_cyc_credit``, ``curr_cyc_debit``) all
derive from COBOL ``PIC S9(10)V99`` — a signed decimal with 10
integer digits and exactly 2 decimal places. The Python translation
uses :class:`sqlalchemy.Numeric` with ``precision=15`` and ``scale=2``:

* ``precision=15`` = 10 integer digits + 2 decimal places + 3 buffer
  digits for SQL computation head-room (industry convention for
  financial columns originally declared as ``PIC S9(10)V99``). The
  buffer prevents overflow during arithmetic expressions such as
  ``curr_bal + curr_cyc_credit - curr_cyc_debit`` in the POSTTRAN
  batch job — each operand has 10 integer digits, but the sum can
  carry into an 11th digit.
* ``scale=2`` = exactly 2 decimal places, matching COBOL's implicit
  decimal point in ``V99``.

Float storage is explicitly FORBIDDEN per AAP §0.7.2: binary
floating-point cannot represent many decimal values exactly (e.g.,
``0.1 + 0.2 == 0.30000000000000004`` in IEEE-754). Monetary
calculations in ``CBACT04C.cbl`` interest calculation
(``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200``) and ``COACTUPC.cbl``
credit-limit validation require exact decimal arithmetic. Python's
:class:`decimal.Decimal` with fixed scale=2 and
``ROUND_HALF_EVEN`` (banker's rounding, matching COBOL's ROUNDED
clause) preserves this semantic exactly.

Rationale for String-Over-Integer on ``acct_id``
------------------------------------------------

Although the COBOL field ``ACCT-ID`` uses ``PIC 9(11)`` (numeric),
the Python translation stores it as ``String(11)`` — a deliberate,
well-reasoned choice:

1. **Leading-zero preservation.** COBOL ``PIC 9(11)`` fields store
   values with leading zeros filling the declared 11-digit width. An
   account ID of ``"00000012345"`` must be stored and returned with
   all 6 leading zeros intact — Python integers would silently
   discard those leading zeros, corrupting the primary key.
2. **Cross-system identifier stability.** Account IDs flow through
   multiple downstream systems (PySpark batch, statement rendering,
   regulatory reporting). Keeping them as fixed-width strings
   eliminates any risk of implicit integer coercion discarding
   leading zeros.
3. **Foreign-key compatibility.** The same 11-character account ID
   is referenced by ``Card.acct_id`` (from ``CARD-ACCT-ID``) and
   ``CardCrossReference.acct_id`` (from ``XREF-ACCT-ID``). All three
   must use identical representations to enable joins; all three are
   ``String(11)``.

Test Coverage — 23 Functions
----------------------------

Phase 2: Table & column metadata (4 tests)
  1. :func:`test_tablename`                    — ``accounts`` (plural)
  2. :func:`test_column_count`                 — exactly 13 columns
  3. :func:`test_primary_key_acct_id`          — ``acct_id`` VARCHAR(11)
  4. :func:`test_acct_id_type`                 — String(11)

Phase 3: Monetary field type fidelity (6 tests)
  5. :func:`test_curr_bal_type`                — Numeric(15, 2)
  6. :func:`test_credit_limit_type`            — Numeric(15, 2)
  7. :func:`test_cash_credit_limit_type`       — Numeric(15, 2)
  8. :func:`test_curr_cyc_credit_type`         — Numeric(15, 2)
  9. :func:`test_curr_cyc_debit_type`          — Numeric(15, 2)
  10. :func:`test_monetary_defaults`           — all default to Decimal("0.00")

Phase 4: String field type fidelity (6 tests)
  11. :func:`test_active_status_type`          — String(1)
  12. :func:`test_open_date_type`              — String(10)
  13. :func:`test_expiration_date_type`        — String(10)
  14. :func:`test_reissue_date_type`           — String(10)
  15. :func:`test_addr_zip_type`               — String(10)
  16. :func:`test_group_id_type`               — String(10)

Phase 5: Optimistic concurrency (3 tests)
  17. :func:`test_version_id_exists`           — Integer column present
  18. :func:`test_version_id_default`          — default is 0
  19. :func:`test_optimistic_concurrency_configured`
                                               — ``__mapper_args__`` bound

Phase 6: NOT NULL constraint coverage (1 test)
  20. :func:`test_non_nullable_fields`         — all 13 columns NOT NULL

Phase 7: Instance construction & repr contract (2 tests)
  21. :func:`test_create_account_instance`     — kwargs round-trip
  22. :func:`test_account_repr`                — readable repr string

Phase 8: FILLER exclusion (1 test)
  23. :func:`test_no_filler_columns`           — 178-byte FILLER dropped

Assertions are rich with COBOL-PIC-clause context to make any
regression failure immediately traceable back to the legacy source.
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import Integer, Numeric, String, inspect

from src.shared.models import Base
from src.shared.models.account import Account

# =============================================================================
# Module-Level Constants
# =============================================================================
#
# These module-level constants centralise "magic values" so that any
# future change to the Account schema (e.g., adding a new column, or
# renaming a field) requires only a single constant update rather
# than hunting through twenty-three test bodies. They also double as
# executable documentation of the expected schema.

# The exact, complete set of columns the Account model must expose.
# This is intentionally a ``frozenset`` — it is immutable, hashable,
# and supports symmetric-difference diagnostics in assertion
# failures. Order is irrelevant for column-*name* assertions; column
# *ordering* is covered separately by :func:`test_column_count`.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        # --- COBOL ACCOUNT-RECORD 01-level group ---
        "acct_id",  # ACCT-ID                    PIC 9(11)
        "active_status",  # ACCT-ACTIVE-STATUS         PIC X(01)
        "curr_bal",  # ACCT-CURR-BAL              PIC S9(10)V99
        "credit_limit",  # ACCT-CREDIT-LIMIT          PIC S9(10)V99
        "cash_credit_limit",  # ACCT-CASH-CREDIT-LIMIT     PIC S9(10)V99
        "open_date",  # ACCT-OPEN-DATE             PIC X(10)
        "expiration_date",  # ACCT-EXPIRAION-DATE[sic]   PIC X(10)
        "reissue_date",  # ACCT-REISSUE-DATE          PIC X(10)
        "curr_cyc_credit",  # ACCT-CURR-CYC-CREDIT       PIC S9(10)V99
        "curr_cyc_debit",  # ACCT-CURR-CYC-DEBIT        PIC S9(10)V99
        "addr_zip",  # ACCT-ADDR-ZIP              PIC X(10)
        "group_id",  # ACCT-GROUP-ID              PIC X(10)
        # --- new Python-side column ---
        "version_id",  # Integer, OCC control column (default 0)
    }
)

# The complete list of the 5 monetary fields. These are all
# :class:`sqlalchemy.Numeric` columns with ``precision=15`` and
# ``scale=2`` (matching COBOL ``PIC S9(10)V99``). The list is used
# by :func:`test_monetary_defaults` to iterate through every
# monetary column and verify the ``Decimal("0.00")`` default
# uniformly rather than duplicating the assertion 5 times.
_MONETARY_COLUMN_NAMES: tuple[str, ...] = (
    "curr_bal",
    "credit_limit",
    "cash_credit_limit",
    "curr_cyc_credit",
    "curr_cyc_debit",
)

# The expected plural table name. Matches ``db/migrations/V1__schema.sql``
# ``CREATE TABLE accounts`` and the SQLAlchemy convention applied
# across all 11 ORM models in ``src/shared/models/`` (accounts,
# cards, customers, card_cross_references, transactions,
# transaction_category_balances, daily_transactions,
# disclosure_groups, transaction_types, transaction_categories,
# user_security).
_EXPECTED_TABLE_NAME: str = "accounts"

# The expected column count: 12 COBOL-derived columns + 1 Python-side
# ``version_id`` column = 13 columns. This count explicitly excludes
# the COBOL FILLER PIC X(178) which must NOT be mapped.
_EXPECTED_COLUMN_COUNT: int = 13

# Sample values for :func:`test_create_account_instance`. These match
# realistic production-shaped data while conforming to the
# fixed-width character and decimal-precision constraints:
#
#   * ``_SAMPLE_ACCT_ID`` — 11 characters with leading zeros,
#     matching ACCT-ID PIC 9(11) which left-pads to the declared
#     width. The value demonstrates that zero-padded identifiers
#     round-trip through the ORM without losing leading zeros.
#   * ``_SAMPLE_STATUS`` — single-character flag, matching
#     ACCT-ACTIVE-STATUS PIC X(01). ``"Y"`` means active (the COBOL
#     programs CBTRN02C / COACTUPC read this as Y/N).
#   * ``_SAMPLE_CURR_BAL`` and the other 4 monetary samples are all
#     :class:`decimal.Decimal` literals with exactly 2 decimal
#     places — matching COBOL PIC S9(10)V99. Float literals are
#     FORBIDDEN here per AAP §0.7.2 (monetary values must use
#     Decimal). Each sample has a deliberately-distinct value so
#     that repr-output assertions can disambiguate which field
#     leaked if a regression causes PII-like over-sharing.
#   * Date samples (``_SAMPLE_OPEN_DATE``, ``_SAMPLE_EXPIRATION``,
#     ``_SAMPLE_REISSUE``) use ISO-8601 ``YYYY-MM-DD`` shape in 10
#     characters, matching ACCT-*-DATE PIC X(10). The three dates
#     are distinct so any swap between them would be catchable.
#   * ``_SAMPLE_ADDR_ZIP`` — 10-character ZIP+4 with separator,
#     demonstrating the full width is usable (matches
#     ACCT-ADDR-ZIP PIC X(10)).
#   * ``_SAMPLE_GROUP_ID`` — 10-character disclosure-group code
#     matching ACCT-GROUP-ID PIC X(10). ``"DEFAULT   "`` (padded to
#     10 chars) is the canonical default disclosure group referenced
#     by INTCALC's ``DEFAULT/ZEROAPR`` fallback logic (AAP §0.7.1).
#     We use the un-padded shorter form here since the ORM column
#     is String(10) (variable-length up to 10 chars) rather than
#     fixed CHAR(10).
_SAMPLE_ACCT_ID: str = "00000012345"
_SAMPLE_STATUS: str = "Y"
_SAMPLE_CURR_BAL: Decimal = Decimal("1234.56")
_SAMPLE_CREDIT_LIMIT: Decimal = Decimal("5000.00")
_SAMPLE_CASH_CREDIT_LIMIT: Decimal = Decimal("1000.00")
_SAMPLE_CURR_CYC_CREDIT: Decimal = Decimal("200.25")
_SAMPLE_CURR_CYC_DEBIT: Decimal = Decimal("350.75")
_SAMPLE_OPEN_DATE: str = "2020-01-15"
_SAMPLE_EXPIRATION: str = "2030-06-30"
_SAMPLE_REISSUE: str = "2025-01-15"
_SAMPLE_ADDR_ZIP: str = "98101-1234"
_SAMPLE_GROUP_ID: str = "DEFAULT"


# =============================================================================
# Phase 2: Table & Column Metadata Tests
# =============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """Account must map to the plural ``accounts`` PostgreSQL table.

    The convention used throughout ``src/shared/models/`` is the
    plural English form (``accounts``, ``cards``, ``customers``,
    etc.). This matches:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE accounts (...)``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO accounts ...``
    * Alembic autogenerate's natural output for a model class
      named ``Account`` with SQLAlchemy's standard pluralisation
      conventions.

    A mismatch here (singular ``account`` vs. plural ``accounts``)
    would cause every ORM query to fail at runtime with
    ``UndefinedTable`` because the ORM would emit SQL against the
    non-existent ``account`` table while the real table exists as
    ``accounts``. This is not a theoretical concern: the original
    setup log for this project explicitly documented this bug
    class as the #1 source-code issue before it was resolved by
    pluralising ``__tablename__`` values across all 11 ORM models.
    """
    # ``__tablename__`` is a class-level attribute on any
    # DeclarativeBase subclass. It must be a string (not None, not
    # a callable) or SQLAlchemy will fail to build the Table
    # metadata — but we still double-check the type here to
    # fail-fast with a clear message rather than a cryptic
    # type-error in a later assertion.
    assert isinstance(Account.__tablename__, str), (
        f"Account.__tablename__ must be a string; got {type(Account.__tablename__).__name__}: {Account.__tablename__!r}"
    )

    assert Account.__tablename__ == _EXPECTED_TABLE_NAME, (
        f"Account.__tablename__ must be {_EXPECTED_TABLE_NAME!r} "
        f"(plural form, matching db/migrations/V1__schema.sql's "
        f"'CREATE TABLE accounts' and the project-wide "
        f"pluralisation convention); got "
        f"{Account.__tablename__!r}. A singular value here would "
        f"cause ORM queries to fail at runtime with "
        f"UndefinedTable because the real table is 'accounts'."
    )


@pytest.mark.unit
def test_column_count() -> None:
    """Account must expose exactly 13 columns — 12 COBOL + 1 ``version_id``.

    The 12 COBOL-derived columns are ``acct_id``, ``active_status``,
    ``curr_bal``, ``credit_limit``, ``cash_credit_limit``,
    ``open_date``, ``expiration_date``, ``reissue_date``,
    ``curr_cyc_credit``, ``curr_cyc_debit``, ``addr_zip``,
    ``group_id`` (translations of the twelve named 05-level fields
    in ``ACCOUNT-RECORD``). The 13th column, ``version_id``, is a
    Python-side addition that implements optimistic concurrency
    (the relational equivalent of CICS READ UPDATE / REWRITE
    semantics used by ``COACTUPC.cbl`` Account Update, a
    4,236-line program with ``EXEC CICS SYNCPOINT ROLLBACK`` on
    concurrency conflict).

    The COBOL ``FILLER PIC X(178)`` byte region — which padded the
    VSAM record to its 300-byte RECLN — has deliberately NOT been
    mapped: padding serves no purpose in a columnar relational
    store. A regression that accidentally maps FILLER would cause
    the column count to balloon to 14 (or more), so this
    count-based test catches that regression early.
    """
    # SQLAlchemy exposes the full ordered column collection via
    # ``__table__.columns`` on any declaratively-mapped class.
    columns = list(Account.__table__.columns)

    assert len(columns) == _EXPECTED_COLUMN_COUNT, (
        f"Account must declare exactly {_EXPECTED_COLUMN_COUNT} "
        f"columns (12 COBOL-derived + 1 version_id); found "
        f"{len(columns)}: {[c.name for c in columns]!r}. If this "
        f"count has grown, confirm that the COBOL FILLER PIC "
        f"X(178) has not been accidentally mapped; if it has "
        f"shrunk, a required column has been dropped."
    )

    # As a second-level guard, verify the column *names* match
    # the expected set exactly. This catches renames (which would
    # not change the count) and spelling regressions.
    #
    # NOTE: We compare against ``Column.key`` (the Python attribute
    # name used in ``__table__.columns[...]`` access and in ORM
    # ``row._mapping[...]`` lookups) rather than ``Column.name``
    # (the physical DB column name, e.g. ``acct_curr_bal``).
    # ``_EXPECTED_COLUMNS`` is intentionally written in Python-style
    # attribute form (e.g. ``curr_bal``) because that is the public
    # ORM contract. The DB-side column name is explicitly decoupled
    # via the ``mapped_column("acct_curr_bal", key="curr_bal", ...)``
    # pattern in ``src/shared/models/account.py``.
    actual_names = frozenset(c.key for c in columns)
    assert actual_names == _EXPECTED_COLUMNS, (
        f"Account column names must be exactly "
        f"{sorted(_EXPECTED_COLUMNS)!r}; found "
        f"{sorted(actual_names)!r}. Differences — "
        f"missing: {sorted(_EXPECTED_COLUMNS - actual_names)!r}, "
        f"unexpected: {sorted(actual_names - _EXPECTED_COLUMNS)!r}"
    )


@pytest.mark.unit
def test_primary_key_acct_id() -> None:
    """``acct_id`` must be the sole primary key, String(11).

    Rationale and lineage:

    * The legacy VSAM KSDS ``ACCTFILE`` cluster uses ``ACCT-ID``
      (offset 0, length 11) as its primary key — see
      ``app/jcl/ACCTFILE.jcl`` (``KEYS(11 0)``) and
      ``app/catlg/LISTCAT.txt``.
    * COBOL declares it as ``PIC 9(11)`` — an 11-digit numeric
      field — but the Python translation uses ``String(11)`` to
      preserve leading zeros (e.g., ``"00000012345"``).
    * The Python translation preserves this 1:1 as a
      ``String(11)`` primary-key column.
    * ``acct_id`` is a *natural* primary key (business-meaningful)
      rather than a surrogate integer. This is deliberate: account
      IDs are the stable identifier referenced externally by
      downstream batch jobs, statement renderers, and regulatory
      reporting. Introducing a surrogate PK would force every
      consumer to translate through an additional lookup for no
      benefit.

    This test verifies all three facets at once:
      1. PK count — exactly 1 (no composite key)
      2. PK name  — ``acct_id``
      3. PK type  — SQLAlchemy ``String`` with length 11
    """
    # ``inspect()`` returns a :class:`Mapper` whose ``primary_key``
    # attribute is a tuple of the primary-key column objects.
    primary_key_columns = list(inspect(Account).primary_key)

    # Exactly one PK column — no composite key on Account.
    assert len(primary_key_columns) == 1, (
        f"Account must have exactly one primary-key column "
        f"(acct_id, matching VSAM ACCTFILE key offset=0 "
        f"length=11); found {len(primary_key_columns)}: "
        f"{[c.name for c in primary_key_columns]!r}"
    )

    pk_column = primary_key_columns[0]

    # The PK column must be named ``acct_id``.
    assert pk_column.name == "acct_id", (
        f"Account primary key must be 'acct_id' (from ACCT-ID PIC 9(11)); found {pk_column.name!r}"
    )

    # The PK type must be ``String`` — NOT Integer, to preserve
    # leading zeros on zero-padded 11-digit account IDs.
    assert isinstance(pk_column.type, String), (
        f"Account.acct_id must be a String column (NOT Integer) "
        f"to preserve leading zeros on 11-digit account IDs "
        f"(e.g., '00000012345'). ACCT-ID PIC 9(11) is mapped to "
        f"String(11) so that zero-padded identifiers survive "
        f"round-trip marshalling; found type "
        f"{type(pk_column.type).__name__}: {pk_column.type!r}"
    )

    # The PK type must declare length exactly 11.
    assert pk_column.type.length == 11, (
        f"Account.acct_id must be String(11) (from ACCT-ID "
        f"PIC 9(11) — 11 digits wide); found length "
        f"{pk_column.type.length!r}"
    )

    # Additional guard — PK columns must always be NOT NULL
    # (SQLAlchemy enforces this automatically but we assert it
    # anyway as a defence-in-depth check).
    assert pk_column.nullable is False, (
        f"Account.acct_id as the primary key must be NOT NULL; found nullable={pk_column.nullable!r}"
    )


@pytest.mark.unit
def test_acct_id_type() -> None:
    """``acct_id`` must be declared as ``String(11)``.

    COBOL source: ``ACCT-ID    PIC 9(11)`` in
    ``app/cpy/CVACT01Y.cpy``.

    Although the COBOL field uses ``PIC 9(11)`` (numeric), the
    Python translation stores it as ``String(11)`` — a deliberate,
    well-reasoned choice documented in the module docstring:

    * 11-digit account IDs frequently start with one or more
      zeros (e.g., ``"00000012345"``). COBOL's numeric PIC clause
      zero-pads to the declared width on display; Python integers
      would silently discard those leading zeros.
    * Account IDs flow through multiple downstream systems
      (PySpark batch, statement rendering, regulatory reporting)
      where any loss of fixed-width formatting would cause
      downstream mismatches.
    * The same 11-character representation is used by
      :class:`src.shared.models.card.Card.acct_id` (from
      ``CARD-ACCT-ID``) and
      :class:`src.shared.models.card_cross_reference.CardCrossReference.acct_id`
      (from ``XREF-ACCT-ID``). All three must have identical
      representations to enable joins.
    """
    column = Account.__table__.columns["acct_id"]

    # Must be ``String`` — NOT Integer.
    assert isinstance(column.type, String), (
        f"Account.acct_id must be String (NOT Integer) to "
        f"preserve leading zeros on 11-digit account IDs (e.g., "
        f"'00000012345'). ACCT-ID PIC 9(11) is mapped to "
        f"String(11) so that leading-zero identifiers survive "
        f"round-trip marshalling; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 11.
    assert column.type.length == 11, (
        f"Account.acct_id must be String(11) (from ACCT-ID "
        f"PIC 9(11) — 11 digits wide); found length "
        f"{column.type.length!r}"
    )


# =============================================================================
# Phase 3: Monetary Field Tests (COBOL PIC S9(10)V99 -> Numeric(15, 2))
# =============================================================================
#
# These six tests (5 type assertions + 1 default-value aggregation)
# validate every monetary field in the Account model. All five fields
# derive from COBOL ``PIC S9(10)V99`` — a signed decimal with 10
# integer digits and exactly 2 decimal places — and all five map to
# :class:`sqlalchemy.Numeric` with ``precision=15`` and ``scale=2``.
#
# **CRITICAL: No floating-point storage is permitted.** Binary
# floating-point cannot represent many decimal values exactly
# (e.g., ``0.1 + 0.2 != 0.3`` in IEEE-754). Monetary calculations
# such as ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` (interest
# calculation in CBACT04C.cbl / intcalc_job.py) require exact
# decimal arithmetic. :class:`decimal.Decimal` with fixed scale=2
# and ``ROUND_HALF_EVEN`` (banker's rounding, matching COBOL's
# ROUNDED clause) preserves this semantic exactly.
#
# All five tests follow the identical "isinstance + .precision +
# .scale" pattern to keep the test structure uniform. A single
# module-level helper function or parameterised test could
# collapse the five into one, but the explicit per-field functions
# make each test independently discoverable, independently
# skippable, and produce the clearest failure output for a
# regression on any single monetary column.


@pytest.mark.unit
def test_curr_bal_type() -> None:
    """``curr_bal`` must be declared as ``Numeric(15, 2)``.

    COBOL source: ``ACCT-CURR-BAL    PIC S9(10)V99`` in
    ``app/cpy/CVACT01Y.cpy``.

    The current outstanding account balance — the most
    operationally-critical monetary field on the Account entity.
    Updated by:

    * ``CBTRN02C.cbl`` (POSTTRAN Stage 1, transaction posting):
      each posted transaction adds to ``curr_bal`` and to one of
      ``curr_cyc_credit`` / ``curr_cyc_debit``.
    * ``CBACT04C.cbl`` (INTCALC Stage 2, interest accrual):
      interest is calculated as ``(TRAN-CAT-BAL × DIS-INT-RATE)
      / 1200`` and added to ``curr_bal``.
    * ``COBIL00C.cbl`` (F-012 Bill Payment): dual-write pattern —
      INSERT of a payment Transaction concurrent with UPDATE of
      ``curr_bal``.
    * ``COACTUPC.cbl`` (F-005 Account Update): administrator-
      initiated balance correction, protected by ``version_id``
      optimistic concurrency.

    Because every one of these flows uses ``curr_bal`` as the
    arithmetic target, any precision loss (float semantics, wrong
    scale, truncation) would cascade across the entire batch
    pipeline. This test guarantees the base precision contract.
    """
    column = Account.__table__.columns["curr_bal"]

    # Must be ``Numeric`` — NOT Float / Real / Double.
    assert isinstance(column.type, Numeric), (
        f"Account.curr_bal must be Numeric (NOT Float) to "
        f"preserve exact decimal precision matching COBOL "
        f"PIC S9(10)V99. Binary floating-point cannot represent "
        f"many decimal values exactly (0.1 + 0.2 != 0.3 in "
        f"IEEE-754), which would corrupt balance arithmetic in "
        f"POSTTRAN / INTCALC / COBIL00C / COACTUPC flows; found "
        f"type {type(column.type).__name__}: {column.type!r}"
    )

    # ``precision`` = total number of significant digits.
    # 15 = 10 integer digits from PIC S9(10) + 2 decimal places
    # from V99 + 3 buffer digits for SQL arithmetic head-room.
    assert column.type.precision == 15, (
        f"Account.curr_bal must be Numeric(15, 2): precision 15 "
        f"= 10 integer digits (from PIC S9(10)) + 2 decimal "
        f"places (from V99) + 3 buffer digits for SQL "
        f"arithmetic head-room; found precision "
        f"{column.type.precision!r}"
    )

    # ``scale`` = number of digits right of the decimal point.
    # Exactly 2, matching COBOL's implicit decimal point in V99.
    assert column.type.scale == 2, (
        f"Account.curr_bal must be Numeric(15, 2): scale 2 "
        f"matches COBOL's implicit decimal point in PIC "
        f"S9(10)V99 (two decimal places, i.e., cents); found "
        f"scale {column.type.scale!r}"
    )


@pytest.mark.unit
def test_credit_limit_type() -> None:
    """``credit_limit`` must be declared as ``Numeric(15, 2)``.

    COBOL source: ``ACCT-CREDIT-LIMIT    PIC S9(10)V99`` in
    ``app/cpy/CVACT01Y.cpy``.

    The total credit limit for the account. Consulted by:

    * ``CBTRN02C.cbl`` (POSTTRAN Stage 1, transaction posting):
      validates that ``new_balance <= credit_limit`` before
      posting, rejecting with code 102 ("Over Credit Limit") if
      the proposed transaction would exceed the limit.
    * ``COACTUPC.cbl`` (F-005 Account Update): administrator
      adjustment via the account-update screen.

    The precision contract matches ``curr_bal`` and all other
    monetary fields on the Account entity — this uniformity is
    deliberate because the four fields appear together in
    arithmetic expressions (e.g., credit-limit-check compares
    ``curr_bal + curr_cyc_credit - curr_cyc_debit`` against
    ``credit_limit``), so differing precisions would risk
    silent truncation during the comparison.
    """
    column = Account.__table__.columns["credit_limit"]

    # Must be ``Numeric``.
    assert isinstance(column.type, Numeric), (
        f"Account.credit_limit must be Numeric (NOT Float) to "
        f"preserve exact decimal precision matching COBOL "
        f"PIC S9(10)V99; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # precision=15.
    assert column.type.precision == 15, (
        f"Account.credit_limit must be Numeric(15, 2): precision 15; found {column.type.precision!r}"
    )

    # scale=2.
    assert column.type.scale == 2, (
        f"Account.credit_limit must be Numeric(15, 2): scale 2 "
        f"(matching COBOL's V99 implicit decimal point); found "
        f"scale {column.type.scale!r}"
    )


@pytest.mark.unit
def test_cash_credit_limit_type() -> None:
    """``cash_credit_limit`` must be declared as ``Numeric(15, 2)``.

    COBOL source: ``ACCT-CASH-CREDIT-LIMIT    PIC S9(10)V99`` in
    ``app/cpy/CVACT01Y.cpy``.

    The cash-advance sub-limit — a subset of the total
    ``credit_limit`` available for cash-advance transactions
    specifically. Consumer payment cards typically set this at
    20-50% of the total credit limit due to the higher risk
    profile of cash advances.

    Enforced by ``CBTRN02C.cbl`` (POSTTRAN Stage 1) when the
    incoming transaction's category code indicates a cash-advance
    transaction. Rejected with code 103 ("Cash-Advance Limit
    Exceeded") if the proposed cash advance would push
    ``curr_cyc_debit`` (cash-advance subset) above
    ``cash_credit_limit``.

    The precision contract matches the other four monetary
    fields on the Account entity.
    """
    column = Account.__table__.columns["cash_credit_limit"]

    # Must be ``Numeric``.
    assert isinstance(column.type, Numeric), (
        f"Account.cash_credit_limit must be Numeric (NOT "
        f"Float) to preserve exact decimal precision matching "
        f"COBOL PIC S9(10)V99; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # precision=15.
    assert column.type.precision == 15, (
        f"Account.cash_credit_limit must be Numeric(15, 2): precision 15; found {column.type.precision!r}"
    )

    # scale=2.
    assert column.type.scale == 2, (
        f"Account.cash_credit_limit must be Numeric(15, 2): "
        f"scale 2 (matching COBOL's V99 implicit decimal "
        f"point); found scale {column.type.scale!r}"
    )


@pytest.mark.unit
def test_curr_cyc_credit_type() -> None:
    """``curr_cyc_credit`` must be declared as ``Numeric(15, 2)``.

    COBOL source: ``ACCT-CURR-CYC-CREDIT    PIC S9(10)V99`` in
    ``app/cpy/CVACT01Y.cpy``.

    The current billing cycle credit total — sum of all credits
    (payments, refunds, credit adjustments) posted to the
    account within the current billing cycle. Zeroed at cycle
    close during the CREASTMT (Statement Generation) batch job.

    Updated by ``CBTRN02C.cbl`` (POSTTRAN) — each posted credit
    transaction atomically increments this field. Consumed by
    ``CBSTM03A.CBL`` (CREASTMT) as one of the summary lines on
    the generated statement.

    Participates in the same credit-limit-check arithmetic as
    ``curr_bal``: ``available_credit = credit_limit - curr_bal
    - curr_cyc_credit + curr_cyc_debit`` (or the algebraic
    equivalent). Precision-matching across all five monetary
    fields is therefore essential.
    """
    column = Account.__table__.columns["curr_cyc_credit"]

    # Must be ``Numeric``.
    assert isinstance(column.type, Numeric), (
        f"Account.curr_cyc_credit must be Numeric (NOT Float) "
        f"to preserve exact decimal precision matching COBOL "
        f"PIC S9(10)V99; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # precision=15.
    assert column.type.precision == 15, (
        f"Account.curr_cyc_credit must be Numeric(15, 2): precision 15; found {column.type.precision!r}"
    )

    # scale=2.
    assert column.type.scale == 2, (
        f"Account.curr_cyc_credit must be Numeric(15, 2): "
        f"scale 2 (matching COBOL's V99 implicit decimal "
        f"point); found scale {column.type.scale!r}"
    )


@pytest.mark.unit
def test_curr_cyc_debit_type() -> None:
    """``curr_cyc_debit`` must be declared as ``Numeric(15, 2)``.

    COBOL source: ``ACCT-CURR-CYC-DEBIT    PIC S9(10)V99`` in
    ``app/cpy/CVACT01Y.cpy``.

    The current billing cycle debit total — sum of all debits
    (purchases, cash advances, fees, interest charges) posted
    to the account within the current billing cycle. Zeroed at
    cycle close during the CREASTMT batch job.

    Updated by ``CBTRN02C.cbl`` (POSTTRAN) — each posted debit
    transaction atomically increments this field. Updated by
    ``CBACT04C.cbl`` (INTCALC) — accrued interest is added as a
    debit-category entry. Consumed by ``CBSTM03A.CBL``
    (CREASTMT) as one of the summary lines on the generated
    statement.

    The precision contract matches ``curr_cyc_credit`` exactly
    because the two fields appear together in every summary
    calculation (e.g., ``net_cycle_activity = curr_cyc_debit -
    curr_cyc_credit``).
    """
    column = Account.__table__.columns["curr_cyc_debit"]

    # Must be ``Numeric``.
    assert isinstance(column.type, Numeric), (
        f"Account.curr_cyc_debit must be Numeric (NOT Float) "
        f"to preserve exact decimal precision matching COBOL "
        f"PIC S9(10)V99; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # precision=15.
    assert column.type.precision == 15, (
        f"Account.curr_cyc_debit must be Numeric(15, 2): precision 15; found {column.type.precision!r}"
    )

    # scale=2.
    assert column.type.scale == 2, (
        f"Account.curr_cyc_debit must be Numeric(15, 2): "
        f"scale 2 (matching COBOL's V99 implicit decimal "
        f"point); found scale {column.type.scale!r}"
    )


@pytest.mark.unit
def test_monetary_defaults() -> None:
    """Every monetary field must default to ``Decimal("0.00")``.

    All 5 monetary fields (``curr_bal``, ``credit_limit``,
    ``cash_credit_limit``, ``curr_cyc_credit``, ``curr_cyc_debit``)
    must have a client-side default of exactly
    ``Decimal("0.00")`` — a :class:`decimal.Decimal` with scale 2.

    Why this matters:

    1. **Type uniformity.** A scalar default of ``0`` (int) or
       ``0.0`` (float) would cause a newly-constructed Account
       instance's monetary attributes to have a different
       Python type than persisted-then-reloaded instances
       (which always return ``Decimal``). Such type-flapping
       would trip service-layer code that asserts ``isinstance(
       acct.curr_bal, Decimal)``.
    2. **Scale preservation.** ``Decimal("0")`` has scale 0;
       ``Decimal("0.00")`` has scale 2. Arithmetic between a
       scale-0 Decimal and a scale-2 Decimal produces a scale-2
       result — which is fine for most cases — but the absence
       of an explicit scale on new rows makes database-level
       arithmetic behave subtly differently for never-persisted
       vs. round-tripped instances, which is unnecessary
       cognitive overhead.
    3. **No-float contract.** ``Decimal("0.00")`` is the only
       literal that satisfies the "no floating-point" rule in
       AAP §0.7.2. A default of ``0.0`` would be a float at
       the source-level literal, violating the rule even if
       SQLAlchemy happens to coerce it correctly.

    This aggregated test iterates through all 5 monetary
    columns and asserts the contract for each, producing a
    single rich diagnostic if any field has the wrong default.
    Per-field tests (``test_curr_bal_type`` etc.) cover the
    column-type dimension; this test covers the orthogonal
    default-value dimension.
    """
    columns = Account.__table__.columns

    # Collect any fields that violate the default contract so
    # we can report them all at once rather than failing on the
    # first and obscuring the others.
    violations: list[str] = []

    for field_name in _MONETARY_COLUMN_NAMES:
        column = columns[field_name]

        # A client-side default must exist. If ``default`` is
        # ``None``, SQLAlchemy will NOT populate the attribute
        # on instance construction, so a freshly-built Account
        # would have ``acct.curr_bal is None`` and any
        # arithmetic using the field would raise TypeError.
        if column.default is None:
            violations.append(f"{field_name} has no default (default=None)")
            continue

        # The default must be scalar (not a callable, not a
        # Sequence, not a server-side DefaultClause). A scalar
        # default is applied at Python-object construction time
        # — exactly when we need it.
        if not column.default.is_scalar:
            violations.append(
                f"{field_name} default is not scalar "
                f"(is_scalar={column.default.is_scalar!r}, "
                f"default={column.default!r})"
            )
            continue

        default_value = column.default.arg

        # The default must be a :class:`Decimal` — NOT int,
        # NOT float. This is the most critical assertion in
        # this test: a float default would violate AAP §0.7.2
        # even if the numerical value is correct.
        if not isinstance(default_value, Decimal):
            violations.append(
                f"{field_name} default is "
                f"{type(default_value).__name__} "
                f"(value={default_value!r}), must be Decimal — "
                f"int/float defaults violate AAP §0.7.2 "
                f"(monetary values must use Decimal)"
            )
            continue

        # The default value must equal Decimal("0.00") exactly
        # — both numerically (== 0) and by scale (two decimal
        # places).
        if default_value != Decimal("0.00"):
            violations.append(f"{field_name} default is {default_value!r} (numeric value); must be Decimal('0.00')")
            continue

        # Tuple-form equality (value, exponent/scale) to ensure
        # the default has exactly 2 decimal places. Decimal("0")
        # compares equal to Decimal("0.00") numerically but has
        # a different scale — we explicitly require scale 2 here.
        #
        # :meth:`Decimal.as_tuple` returns a named tuple with an
        # ``exponent`` field: for ``Decimal("0.00")`` the exponent
        # is -2 (meaning 2 decimal places). We check the exponent
        # is exactly -2 to enforce the "0.00" spelling.
        default_exponent = default_value.as_tuple().exponent
        if default_exponent != -2:
            violations.append(
                f"{field_name} default has exponent "
                f"{default_exponent!r} (expected -2 for two "
                f"decimal places); must be Decimal('0.00') "
                f"spelled with two trailing zeros, not "
                f"{default_value!r}"
            )

    assert not violations, (
        f"All 5 monetary fields on Account must default to "
        f"Decimal('0.00') (scale 2, matching COBOL "
        f"PIC S9(10)V99 implicit decimal point). Violations: "
        f"{violations!r}"
    )


# =============================================================================
# Phase 4: String Field Tests (COBOL PIC X(n) -> String(n))
# =============================================================================
#
# These six tests validate every non-PK string-typed column on
# the Account model. Each derives from a COBOL ``PIC X(n)`` field
# and maps to :class:`sqlalchemy.String` with the declared length.
# Unlike the monetary fields (where precision/scale are the
# dominant concern), string fields are straightforward: the type
# is ``String`` and the length matches the COBOL declared width.
#
# All six tests follow the identical "isinstance + .length"
# pattern to keep the test structure uniform and the review cost
# low. Any deviation here (wrong type, wrong length) is a direct
# regression against the COBOL copybook contract.


@pytest.mark.unit
def test_active_status_type() -> None:
    """``active_status`` must be declared as ``String(1)``.

    COBOL source: ``ACCT-ACTIVE-STATUS    PIC X(01)`` in
    ``app/cpy/CVACT01Y.cpy``.

    The active-status flag is a single-character Y/N indicator
    (or similar two-valued flag). It is read by:

    * ``COACTVWC.cbl`` (F-004 Account View): shown on the
      account-view screen to indicate whether the account is
      currently usable for transactions.
    * ``COACTUPC.cbl`` (F-005 Account Update): administrator
      can toggle this field as part of account-maintenance
      workflows.
    * ``CBTRN02C.cbl`` (POSTTRAN Stage 1, transaction posting):
      an inactive account triggers reject code 100 ("Account
      Not Active") in the 4-stage validation cascade.

    The Python translation preserves the single-character width
    to maintain behavioural parity with these COBOL programs
    and their service-layer Python equivalents.
    """
    column = Account.__table__.columns["active_status"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Account.active_status must be String (from "
        f"ACCT-ACTIVE-STATUS PIC X(01) — a fixed-width "
        f"character field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 1.
    assert column.type.length == 1, (
        f"Account.active_status must be String(1) (from "
        f"ACCT-ACTIVE-STATUS PIC X(01) — 1 character wide, a "
        f"single Y/N flag); found length "
        f"{column.type.length!r}"
    )


@pytest.mark.unit
def test_open_date_type() -> None:
    """``open_date`` must be declared as ``String(10)``.

    COBOL source: ``ACCT-OPEN-DATE    PIC X(10)`` in
    ``app/cpy/CVACT01Y.cpy``.

    The 10-character width matches ISO-8601-like date strings
    (``YYYY-MM-DD``). COBOL represents this as a character
    field rather than a numeric date type (which COBOL doesn't
    natively support) — Python preserves the character-based
    storage to retain full formatting fidelity with downstream
    consumers that expect a string date.

    This field records the date on which the account was
    originally opened. It is display-only for most online
    flows (``COACTVWC.cbl`` Account View) and is consulted by
    INTCALC (``CBACT04C.cbl``) when determining the first-year
    vs. standard disclosure group for new accounts.

    Note: Although semantically a date, this column stays a
    fixed-width string to minimise transformation risk during
    the mainframe-to-cloud migration. Validation of the
    YYYY-MM-DD shape is delegated to
    :mod:`src.shared.utils.date_utils` (which preserves the
    CSUTLDTC COBOL validation rules).
    """
    column = Account.__table__.columns["open_date"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Account.open_date must be String (from "
        f"ACCT-OPEN-DATE PIC X(10) — a fixed-width character "
        f"field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 10.
    assert column.type.length == 10, (
        f"Account.open_date must be String(10) (from "
        f"ACCT-OPEN-DATE PIC X(10) — 10 characters wide, "
        f"matching YYYY-MM-DD ISO-8601-like date strings); "
        f"found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_expiration_date_type() -> None:
    """``expiration_date`` must be declared as ``String(10)``.

    COBOL source: ``ACCT-EXPIRAION-DATE    PIC X(10)`` in
    ``app/cpy/CVACT01Y.cpy`` (note: the COBOL field name
    contains the typo ``EXPIRAION`` — missing the second 'T'
    — which is preserved *verbatim* in the source but NOT
    replicated in the Python column name. The relational
    schema uses the correctly-spelled ``expiration_date``).

    Per AAP §0.7.1 ("Do not modify code not directly impacted
    by the technology transition"), the COBOL source file
    ``app/cpy/CVACT01Y.cpy`` retains the original typo — the
    Python column name is normalised to ``expiration_date``
    because:

    1. The relational schema is the canonical forward-going
       contract; adopting the COBOL typo would propagate it
       into every downstream Python consumer.
    2. The typo would cause IDE auto-complete confusion and
       would inevitably be mis-typed by developers unfamiliar
       with the legacy source.
    3. The downstream COBOL programs themselves (``COACTUPC``,
       ``CBSTM03A``) use a mix of ``EXPIRAION-DATE`` and
       ``EXPIRATION-DATE`` references, so matching only the
       copybook spelling would be arbitrary.

    The 10-character width matches ISO-8601-like date strings
    (``YYYY-MM-DD``). Consulted by ``CBTRN02C.cbl`` (POSTTRAN)
    during the expiration check (reject code 101 = "Account
    Expired").
    """
    column = Account.__table__.columns["expiration_date"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Account.expiration_date must be String (from "
        f"ACCT-EXPIRAION-DATE PIC X(10) — a fixed-width "
        f"character field; note COBOL typo preserved in "
        f"source copybook but corrected in Python column "
        f"name); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 10.
    assert column.type.length == 10, (
        f"Account.expiration_date must be String(10) (from "
        f"ACCT-EXPIRAION-DATE PIC X(10) — 10 characters wide, "
        f"matching YYYY-MM-DD ISO-8601-like date strings); "
        f"found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_reissue_date_type() -> None:
    """``reissue_date`` must be declared as ``String(10)``.

    COBOL source: ``ACCT-REISSUE-DATE    PIC X(10)`` in
    ``app/cpy/CVACT01Y.cpy``.

    The 10-character ``YYYY-MM-DD`` date tracking the most
    recent card reissue event for this account. A single
    account can own multiple cards over its lifetime (primary
    card, replacement for lost/stolen, reissue on expiration,
    upgrade to premium product); this field records the
    most-recent such event across all the account's cards.

    Display-only in the ``COACTVWC.cbl`` (Account View) flow
    and ``COACTUPC.cbl`` (Account Update) flow. The
    ``CBSTM03A.CBL`` (Statement Generation) batch job may
    include this date in the statement header.

    As with ``open_date`` and ``expiration_date``, this
    remains a fixed-width string to minimise migration risk —
    any type change to :class:`sqlalchemy.Date` would be an
    explicit *enhancement* outside the scope of the
    behaviour-preserving refactor.
    """
    column = Account.__table__.columns["reissue_date"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Account.reissue_date must be String (from "
        f"ACCT-REISSUE-DATE PIC X(10) — a fixed-width "
        f"character field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 10.
    assert column.type.length == 10, (
        f"Account.reissue_date must be String(10) (from "
        f"ACCT-REISSUE-DATE PIC X(10) — 10 characters wide, "
        f"matching YYYY-MM-DD ISO-8601-like date strings); "
        f"found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_addr_zip_type() -> None:
    """``addr_zip`` must be declared as ``String(10)``.

    COBOL source: ``ACCT-ADDR-ZIP    PIC X(10)`` in
    ``app/cpy/CVACT01Y.cpy``.

    The 10-character width accommodates the full spectrum of
    US postal-code formats:

    * US 5-digit ZIP ``"98101"`` (5 chars used, 5 trailing
      spaces or nulls).
    * US ZIP+4 with hyphen ``"98101-1234"`` (10 chars used).
    * US ZIP+4 without hyphen ``"981011234"`` (9 chars used,
      1 trailing space).

    The field is ``PIC X`` (alphanumeric) rather than ``PIC 9``
    (numeric) specifically to accommodate the hyphen separator
    in ZIP+4 format and to allow for future international
    postal-code extensions.

    Displayed in the ``COACTVWC.cbl`` (Account View) flow and
    editable in the ``COACTUPC.cbl`` (Account Update) flow.
    Used by ``CBSTM03A.CBL`` (Statement Generation) as part of
    the statement header's mailing address block (alongside
    the Customer entity's address fields).
    """
    column = Account.__table__.columns["addr_zip"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Account.addr_zip must be String (from "
        f"ACCT-ADDR-ZIP PIC X(10) — a fixed-width character "
        f"field, NOT numeric, to accommodate ZIP+4 hyphen "
        f"separator); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 10.
    assert column.type.length == 10, (
        f"Account.addr_zip must be String(10) (from "
        f"ACCT-ADDR-ZIP PIC X(10) — 10 characters wide, "
        f"accommodating US ZIP+4 with hyphen separator); "
        f"found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_group_id_type() -> None:
    """``group_id`` must be declared as ``String(10)``.

    COBOL source: ``ACCT-GROUP-ID    PIC X(10)`` in
    ``app/cpy/CVACT01Y.cpy``.

    The 10-character account disclosure-group code. Logical
    foreign key into the ``DisclosureGroup`` table (from
    ``app/cpy/CVTRA02Y.cpy``), determining which interest-rate
    schedule applies to this account during INTCALC.

    Common values:

    * ``"DEFAULT   "`` (padded to 10 chars) — standard APR
      disclosure group applied to most accounts.
    * ``"ZEROAPR   "`` (padded to 10 chars) — zero-percent
      introductory rate for new accounts during their
      promotional period.

    Per AAP §0.7.1, INTCALC's DEFAULT/ZEROAPR fallback logic
    must be preserved exactly: an empty or unknown group_id
    causes ``CBACT04C.cbl`` (INTCALC Stage 2) to fall back to
    the ``"DEFAULT"`` schedule rather than erroring or using
    a zero rate.

    Note: The foreign-key relationship is NOT declared at the
    ORM layer to avoid a circular import between
    :class:`Account` and :class:`DisclosureGroup`. FK
    resolution happens at the service layer during
    interest-calculation joins.
    """
    column = Account.__table__.columns["group_id"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Account.group_id must be String (from "
        f"ACCT-GROUP-ID PIC X(10) — a fixed-width character "
        f"field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 10.
    assert column.type.length == 10, (
        f"Account.group_id must be String(10) (from "
        f"ACCT-GROUP-ID PIC X(10) — 10 characters wide, "
        f"matching DisclosureGroup.group_id PK width for "
        f"FK-style joins); found length {column.type.length!r}"
    )


# =============================================================================
# Phase 5: Optimistic Concurrency Tests (version_id + __mapper_args__)
# =============================================================================
#
# These three tests verify the Python-side optimistic-concurrency-
# control (OCC) column and its SQLAlchemy wiring. OCC is the
# relational equivalent of the CICS READ UPDATE / REWRITE protocol
# used by the legacy online program ``COACTUPC.cbl`` (F-005 Account
# Update) — a 4,236-line program with ``EXEC CICS SYNCPOINT
# ROLLBACK`` on concurrency conflict. Under OCC, SQLAlchemy adds a
# ``WHERE version_id = :old`` clause to every UPDATE statement and
# raises :exc:`sqlalchemy.orm.exc.StaleDataError` if the row's
# version has been bumped by a concurrent writer — preventing
# lost-update bugs without the pessimistic locking overhead of
# ``SELECT ... FOR UPDATE``.
#
# The COBOL original achieved this via the CICS ``READ UPDATE`` /
# ``REWRITE`` pair: ``READ UPDATE`` held an exclusive lock on the
# VSAM record, and the subsequent ``REWRITE`` was guaranteed to
# see no intervening modifications. The Python/SQLAlchemy OCC
# approach is semantically equivalent but non-blocking: concurrent
# reads proceed in parallel, and conflicts are detected at write
# time rather than prevented at read time.
#
# Account participates in two dual-write flows that critically
# depend on OCC:
#   * F-005 Account Update (COACTUPC.cbl): administrator-initiated
#     correction that updates curr_bal / credit_limit /
#     cash_credit_limit atomically.
#   * F-012 Bill Payment (COBIL00C.cbl): atomic Transaction INSERT
#     concurrent with Account curr_bal UPDATE.
# A missing OCC configuration here would allow lost-update bugs
# in both flows, potentially causing silent financial loss.


@pytest.mark.unit
def test_version_id_exists() -> None:
    """Account must declare a ``version_id`` Integer column.

    The ``version_id`` column is the Python-side addition that
    implements optimistic concurrency. It is:

    * Named exactly ``version_id`` — this is the SQLAlchemy
      convention for OCC columns and is the name referenced by
      ``__mapper_args__["version_id_col"]``.
    * Typed as :class:`sqlalchemy.Integer` — SQLAlchemy increments
      this on every UPDATE, so an integer counter is the natural
      type (and exactly what the documentation recommends).
    * NOT NULL — a nullable version column would be nonsensical;
      OCC requires every row to have a defined version at all
      times. NOT NULL enforcement is separately covered by
      :func:`test_non_nullable_fields`.

    This test focuses on *existence* and *type*. The default
    value and the mapper-arg wiring are covered in the two
    following tests.
    """
    columns = Account.__table__.columns

    # Must have a ``version_id`` column at all.
    assert "version_id" in columns, (
        f"Account must declare a 'version_id' column to enable "
        f"optimistic concurrency (replacing CICS READ UPDATE / "
        f"REWRITE from COACTUPC.cbl's 4,236-line account-update "
        f"flow with SYNCPOINT ROLLBACK on concurrency "
        f"conflict); found columns: "
        f"{sorted(c.name for c in columns)!r}"
    )

    version_column = columns["version_id"]

    # Must be of type ``Integer``.
    assert isinstance(version_column.type, Integer), (
        f"Account.version_id must be an Integer column "
        f"(SQLAlchemy OCC increments this counter on every "
        f"UPDATE, so it must be an integer type); found type "
        f"{type(version_column.type).__name__}: "
        f"{version_column.type!r}"
    )


@pytest.mark.unit
def test_version_id_default() -> None:
    """Account.version_id must default to ``0``.

    A newly-created Account instance that has never been
    persisted must have ``version_id`` initialised to ``0`` (or
    the database-level default must resolve to ``0`` on INSERT).
    This matters because:

    * The first UPDATE after INSERT increments to 1, establishing
      a clean, predictable version sequence.
    * A null or missing default would cause the first UPDATE to
      fail because SQLAlchemy emits ``WHERE version_id IS NULL``
      (which matches zero rows) and then raises ``StaleDataError``
      even though no concurrent modification has occurred.
    * Seed-data loaders (``db/migrations/V3__seed_data.sql``) that
      INSERT without explicit ``version_id`` rely on this default
      to produce well-formed rows.

    The test accesses the default via
    ``column.default.arg`` — SQLAlchemy wraps scalar defaults in
    a :class:`ScalarElementColumnDefault` whose ``arg`` attribute
    holds the literal default value.
    """
    version_column = Account.__table__.columns["version_id"]

    # Must have a client-side default at all.
    assert version_column.default is not None, (
        "Account.version_id must declare a default value so "
        "that newly-created instances have version_id=0 on "
        "INSERT (otherwise the first UPDATE will emit WHERE "
        "version_id IS NULL and raise StaleDataError); found "
        "default=None"
    )

    # The default must be scalar (not a callable, not a
    # ``Sequence``, not a server-side ``DefaultClause``). A
    # callable default would work at runtime but is unusual and
    # not what this model declares.
    assert version_column.default.is_scalar, (
        f"Account.version_id default must be scalar (a literal "
        f"integer, not a callable or server-side default); "
        f"found is_scalar={version_column.default.is_scalar!r} "
        f"default={version_column.default!r}"
    )

    # The scalar default value must be exactly 0 — the canonical
    # OCC starting version.
    assert version_column.default.arg == 0, (
        f"Account.version_id default must be 0 (the canonical "
        f"OCC starting version — the first UPDATE increments to "
        f"1, the second to 2, etc.); found "
        f"default={version_column.default.arg!r}"
    )


@pytest.mark.unit
def test_optimistic_concurrency_configured() -> None:
    """Account.__mapper_args__ must wire version_id for OCC.

    Merely declaring a ``version_id`` Integer column is not
    sufficient — SQLAlchemy also requires an explicit
    ``__mapper_args__["version_id_col"]`` binding to activate
    OCC. Without this binding:

    * UPDATE statements would NOT include the ``WHERE version_id
      = :old_version`` predicate.
    * ``INSERT`` would NOT automatically populate version_id
      from the default (unless the client-side default itself
      is set, which we cover separately).
    * Concurrent writers could silently overwrite each other's
      changes — the exact lost-update bug OCC exists to prevent.

    For Account specifically, losing OCC would be particularly
    harmful because the entity is the target of F-005 Account
    Update (dual-write with Customer) and F-012 Bill Payment
    (dual-write with Transaction INSERT). Both flows have
    SYNCPOINT ROLLBACK semantics in the COBOL original; OCC is
    the SQLAlchemy-native equivalent of that rollback-on-
    conflict behaviour.

    This test verifies the binding exists and points to the
    ``version_id`` column (by name), catching any regression
    where the mapper-arg is accidentally removed or typoed.
    """
    # ``__mapper_args__`` is always a dict on a properly-
    # configured DeclarativeBase subclass. It may be empty, but
    # it must at least be present.
    assert hasattr(Account, "__mapper_args__"), (
        "Account must declare __mapper_args__ to configure "
        "optimistic concurrency (version_id_col binding); "
        "found no __mapper_args__ attribute at all"
    )

    mapper_args = Account.__mapper_args__
    assert isinstance(mapper_args, dict), (
        f"Account.__mapper_args__ must be a dict; got {type(mapper_args).__name__}: {mapper_args!r}"
    )

    # The ``version_id_col`` key must be present.
    assert "version_id_col" in mapper_args, (
        f"Account.__mapper_args__ must contain 'version_id_col' "
        f"to activate SQLAlchemy optimistic concurrency (the "
        f"relational equivalent of CICS READ UPDATE / REWRITE "
        f"from COACTUPC.cbl); found keys: "
        f"{sorted(mapper_args.keys())!r}"
    )

    version_id_col = mapper_args["version_id_col"]

    # The bound column must expose a ``.name`` attribute
    # resolving to ``"version_id"``. We identify by name rather
    # than by object identity because SQLAlchemy 2.x's
    # ``mapped_column()`` helper returns a :class:`MappedColumn`
    # instance which wraps — but is not identical to — the
    # eventual :class:`Column` object accessible via
    # ``Account.__table__.columns["version_id"]``. The ``.name``
    # attribute is stable across this wrapping.
    bound_name = getattr(version_id_col, "name", None)
    assert bound_name == "version_id", (
        f"Account.__mapper_args__['version_id_col'] must bind "
        f"to the 'version_id' column; found a "
        f"{type(version_id_col).__name__} object whose .name "
        f"attribute is {bound_name!r}"
    )


# =============================================================================
# Phase 6: NOT NULL Constraint Tests
# =============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """Every Account column must be declared NOT NULL.

    All 13 columns on the Account table carry a NOT NULL
    constraint. This faithfully preserves the COBOL copybook
    contract: every 05-level field in ``ACCOUNT-RECORD`` is
    implicitly non-null because VSAM records are written as
    fixed-width binary blobs with no concept of "null" — an
    unpopulated field would be initialised to spaces (for
    ``PIC X``) or zeros (for ``PIC 9`` / ``PIC S9(n)V99``),
    never null.

    The 13 columns and their NOT NULL justifications:

    1. ``acct_id``          — Primary key; PKs are always NOT NULL.
    2. ``active_status``    — Required by POSTTRAN (reject code
                              100 check). A null status would
                              bypass the activity check entirely.
    3. ``curr_bal``         — Monetary. A null balance would cause
                              every arithmetic expression to
                              NULL-propagate and return null,
                              breaking balance-update logic.
    4. ``credit_limit``     — Monetary. Required by POSTTRAN
                              (reject code 102 check).
    5. ``cash_credit_limit``— Monetary. Required by POSTTRAN
                              (reject code 103 check).
    6. ``open_date``        — Required for disclosure-group
                              first-year vs. standard-year
                              determination.
    7. ``expiration_date``  — Required by POSTTRAN (reject code
                              101 check).
    8. ``reissue_date``     — Required for card-reissue tracking.
    9. ``curr_cyc_credit``  — Monetary. Required by POSTTRAN /
                              CREASTMT cycle-summary arithmetic.
    10. ``curr_cyc_debit``  — Monetary. Required by POSTTRAN /
                              CREASTMT cycle-summary arithmetic.
    11. ``addr_zip``        — Required for statement mailing
                              address block.
    12. ``group_id``        — Required for INTCALC interest-rate
                              lookup (with DEFAULT fallback).
    13. ``version_id``      — OCC control column; a null version
                              would defeat optimistic concurrency.

    This test catches any regression where a column's
    ``nullable=False`` kwarg is accidentally removed or
    negated. A single nullable column would cause SQLAlchemy
    to emit the column without a NOT NULL constraint, and
    PostgreSQL would then silently accept NULL values —
    violating the COBOL contract at the database level.
    """
    columns = Account.__table__.columns

    # Collect every column that is nullable (should be empty)
    # to produce a single rich diagnostic if any column is
    # incorrectly nullable, rather than asserting one at a
    # time (which would fail on the first and obscure the
    # rest).
    nullable_columns = [c.name for c in columns if c.nullable is not False]
    assert not nullable_columns, (
        f"All Account columns must be NOT NULL (matching the "
        f"fixed-width VSAM ACCOUNT-RECORD contract where every "
        f"field is always populated — PIC X fields default to "
        f"spaces, PIC 9 fields to zeros, never null); found "
        f"nullable columns: {sorted(nullable_columns)!r}. "
        f"Every one of the 13 columns must declare "
        f"nullable=False."
    )

    # Defence in depth: explicitly verify each of the 13
    # expected columns individually. This produces clearer
    # diagnostics for the common case of a single regression,
    # and double-guards against a column being silently
    # dropped (which the generic loop above would not catch).
    for column_name in _EXPECTED_COLUMNS:
        column = columns[column_name]
        assert column.nullable is False, f"Account.{column_name} must be NOT NULL; found nullable={column.nullable!r}"


# =============================================================================
# Phase 7: Instance Creation & Repr Tests
# =============================================================================


@pytest.mark.unit
def test_create_account_instance() -> None:
    """An Account instance must accept all 12 data columns as kwargs.

    This test exercises the fundamental instance-construction
    contract:

    1. ``Account(...)`` accepts the 12 COBOL-derived columns as
       keyword arguments.
    2. Each kwarg value round-trips through the descriptor:
       setting ``curr_bal=Decimal("1234.56")`` and reading
       ``instance.curr_bal`` returns the exact same Decimal
       value (modulo any Pydantic/SQLAlchemy-level validation,
       of which this model declares none at the Python-object
       level — validation happens at the database layer via
       NOT NULL constraints and fixed-width VARCHAR / NUMERIC
       types).
    3. The ``version_id`` kwarg is intentionally omitted —
       the default (0) should apply, but the default is
       applied at the *database* layer (on INSERT), not at
       object construction. Before INSERT, the Python-side
       ``version_id`` attribute will be ``None`` — this is
       expected SQLAlchemy behaviour and is NOT a defect.

    The test also validates that the :class:`Base` inheritance
    is correct by checking ``isinstance(instance, Base)``.
    This catches the regression class where a model is
    mistakenly declared inheriting from ``object`` (or from
    a different ``DeclarativeBase``), which would cause the
    ORM metadata to fail to pick up the model.

    **Critical: all monetary kwargs use Decimal, never float.**
    Per AAP §0.7.2, float literals for monetary values are
    forbidden. This test is the primary functional check that
    the model accepts Decimal values on construction and
    returns them unchanged.
    """
    # Construct with all 12 COBOL-derived columns. ``version_id``
    # is omitted so that the default-value test
    # (:func:`test_version_id_default`) can independently
    # verify defaulting behaviour at the schema level.
    account = Account(
        acct_id=_SAMPLE_ACCT_ID,
        active_status=_SAMPLE_STATUS,
        curr_bal=_SAMPLE_CURR_BAL,
        credit_limit=_SAMPLE_CREDIT_LIMIT,
        cash_credit_limit=_SAMPLE_CASH_CREDIT_LIMIT,
        open_date=_SAMPLE_OPEN_DATE,
        expiration_date=_SAMPLE_EXPIRATION,
        reissue_date=_SAMPLE_REISSUE,
        curr_cyc_credit=_SAMPLE_CURR_CYC_CREDIT,
        curr_cyc_debit=_SAMPLE_CURR_CYC_DEBIT,
        addr_zip=_SAMPLE_ADDR_ZIP,
        group_id=_SAMPLE_GROUP_ID,
    )

    # The instance must be an Account.
    assert isinstance(account, Account), f"Account(...) must produce an Account instance; got {type(account).__name__}"

    # The instance must be a Base (verifies the declarative
    # base is wired correctly — any regression here would
    # break ORM metadata registration).
    assert isinstance(account, Base), (
        f"Account instances must inherit from Base (the shared "
        f"DeclarativeBase in src.shared.models); got an "
        f"instance of {type(account).__name__} which does NOT "
        f"inherit from Base. This would break ORM metadata "
        f"registration and cause Table creation to fail."
    )

    # Round-trip verify every kwarg came through cleanly.
    # A mismatch here would indicate that the descriptor has
    # been accidentally reconfigured (e.g., with a validator,
    # a property wrapper, or an SQLAlchemy `TypeDecorator` that
    # transforms input values).

    # --- acct_id: must preserve leading zeros ---
    assert account.acct_id == _SAMPLE_ACCT_ID, (
        f"account.acct_id must round-trip the constructor "
        f"kwarg and preserve leading zeros; set "
        f"{_SAMPLE_ACCT_ID!r}, got {account.acct_id!r}"
    )

    # --- active_status ---
    assert account.active_status == _SAMPLE_STATUS, (
        f"account.active_status must round-trip the "
        f"constructor kwarg; set {_SAMPLE_STATUS!r}, got "
        f"{account.active_status!r}"
    )

    # --- curr_bal: Decimal identity ---
    assert account.curr_bal == _SAMPLE_CURR_BAL, (
        f"account.curr_bal must round-trip the Decimal "
        f"constructor kwarg exactly (no float coercion); set "
        f"{_SAMPLE_CURR_BAL!r}, got {account.curr_bal!r}"
    )
    # Defence in depth: verify the type is still Decimal after
    # round-trip. A regression that coerces to float via a
    # validator would fail this assertion.
    assert isinstance(account.curr_bal, Decimal), (
        f"account.curr_bal must be a Decimal instance after "
        f"round-trip (AAP §0.7.2: monetary values must use "
        f"Decimal, NEVER float); found "
        f"{type(account.curr_bal).__name__}: "
        f"{account.curr_bal!r}"
    )

    # --- credit_limit ---
    assert account.credit_limit == _SAMPLE_CREDIT_LIMIT, (
        f"account.credit_limit must round-trip the Decimal "
        f"constructor kwarg exactly; set "
        f"{_SAMPLE_CREDIT_LIMIT!r}, got "
        f"{account.credit_limit!r}"
    )
    assert isinstance(account.credit_limit, Decimal), (
        f"account.credit_limit must be a Decimal instance "
        f"after round-trip (AAP §0.7.2); found "
        f"{type(account.credit_limit).__name__}: "
        f"{account.credit_limit!r}"
    )

    # --- cash_credit_limit ---
    assert account.cash_credit_limit == _SAMPLE_CASH_CREDIT_LIMIT, (
        f"account.cash_credit_limit must round-trip the "
        f"Decimal constructor kwarg exactly; set "
        f"{_SAMPLE_CASH_CREDIT_LIMIT!r}, got "
        f"{account.cash_credit_limit!r}"
    )
    assert isinstance(account.cash_credit_limit, Decimal), (
        f"account.cash_credit_limit must be a Decimal "
        f"instance after round-trip (AAP §0.7.2); found "
        f"{type(account.cash_credit_limit).__name__}: "
        f"{account.cash_credit_limit!r}"
    )

    # --- open_date ---
    assert account.open_date == _SAMPLE_OPEN_DATE, (
        f"account.open_date must round-trip the constructor kwarg; set {_SAMPLE_OPEN_DATE!r}, got {account.open_date!r}"
    )

    # --- expiration_date ---
    assert account.expiration_date == _SAMPLE_EXPIRATION, (
        f"account.expiration_date must round-trip the "
        f"constructor kwarg; set {_SAMPLE_EXPIRATION!r}, got "
        f"{account.expiration_date!r}"
    )

    # --- reissue_date ---
    assert account.reissue_date == _SAMPLE_REISSUE, (
        f"account.reissue_date must round-trip the "
        f"constructor kwarg; set {_SAMPLE_REISSUE!r}, got "
        f"{account.reissue_date!r}"
    )

    # --- curr_cyc_credit ---
    assert account.curr_cyc_credit == _SAMPLE_CURR_CYC_CREDIT, (
        f"account.curr_cyc_credit must round-trip the Decimal "
        f"constructor kwarg exactly; set "
        f"{_SAMPLE_CURR_CYC_CREDIT!r}, got "
        f"{account.curr_cyc_credit!r}"
    )
    assert isinstance(account.curr_cyc_credit, Decimal), (
        f"account.curr_cyc_credit must be a Decimal instance "
        f"after round-trip (AAP §0.7.2); found "
        f"{type(account.curr_cyc_credit).__name__}: "
        f"{account.curr_cyc_credit!r}"
    )

    # --- curr_cyc_debit ---
    assert account.curr_cyc_debit == _SAMPLE_CURR_CYC_DEBIT, (
        f"account.curr_cyc_debit must round-trip the Decimal "
        f"constructor kwarg exactly; set "
        f"{_SAMPLE_CURR_CYC_DEBIT!r}, got "
        f"{account.curr_cyc_debit!r}"
    )
    assert isinstance(account.curr_cyc_debit, Decimal), (
        f"account.curr_cyc_debit must be a Decimal instance "
        f"after round-trip (AAP §0.7.2); found "
        f"{type(account.curr_cyc_debit).__name__}: "
        f"{account.curr_cyc_debit!r}"
    )

    # --- addr_zip ---
    assert account.addr_zip == _SAMPLE_ADDR_ZIP, (
        f"account.addr_zip must round-trip the constructor kwarg; set {_SAMPLE_ADDR_ZIP!r}, got {account.addr_zip!r}"
    )

    # --- group_id ---
    assert account.group_id == _SAMPLE_GROUP_ID, (
        f"account.group_id must round-trip the constructor kwarg; set {_SAMPLE_GROUP_ID!r}, got {account.group_id!r}"
    )


@pytest.mark.unit
def test_account_repr() -> None:
    """Account.__repr__ must produce a human-readable, identifying string.

    The ``__repr__`` contract:

    **Readability** — the output should include enough
    identifying information to make a log line or debugger
    inspection immediately useful. At minimum, the account ID
    (the primary key), the active-status flag, and the current
    balance should appear — these three fields answer the three
    most common debugging questions for an Account instance:

    * "Which account is this?" -> ``acct_id``
    * "Is it active?"         -> ``active_status``
    * "What's the balance?"    -> ``curr_bal``

    **Conciseness** — the repr does NOT include every column;
    in particular the 4 remaining monetary fields
    (``credit_limit``, ``cash_credit_limit``, ``curr_cyc_credit``,
    ``curr_cyc_debit``) and the 5 non-monetary fields
    (``open_date``, ``expiration_date``, ``reissue_date``,
    ``addr_zip``, ``group_id``) are omitted to keep log output
    concise. Developers needing more detail should query the
    fields directly rather than relying on the repr.

    This test verifies:

    1. The repr string starts with ``"Account("`` (clear type
       tag — standard Python convention).
    2. The ``acct_id`` value appears (primary-key identification).
    3. The ``active_status`` value appears.
    4. The ``curr_bal`` value appears (in some form — the
       ``Decimal('1234.56')`` repr form is used).

    The docstring on :meth:`Account.__repr__` documents the
    exact format string:
    ``Account(acct_id='...', active_status='...', curr_bal=Decimal('...'))``.
    """
    account = Account(
        acct_id=_SAMPLE_ACCT_ID,
        active_status=_SAMPLE_STATUS,
        curr_bal=_SAMPLE_CURR_BAL,
        credit_limit=_SAMPLE_CREDIT_LIMIT,
        cash_credit_limit=_SAMPLE_CASH_CREDIT_LIMIT,
        open_date=_SAMPLE_OPEN_DATE,
        expiration_date=_SAMPLE_EXPIRATION,
        reissue_date=_SAMPLE_REISSUE,
        curr_cyc_credit=_SAMPLE_CURR_CYC_CREDIT,
        curr_cyc_debit=_SAMPLE_CURR_CYC_DEBIT,
        addr_zip=_SAMPLE_ADDR_ZIP,
        group_id=_SAMPLE_GROUP_ID,
    )

    repr_str = repr(account)

    # Must be a non-empty string.
    assert isinstance(repr_str, str) and repr_str, (
        f"Account.__repr__ must return a non-empty string; got {type(repr_str).__name__}: {repr_str!r}"
    )

    # Must start with the class-name tag ``Account(`` — standard
    # Python repr convention, and enables tooling to identify
    # the object type without executing ``type(...)``.
    assert repr_str.startswith("Account("), (
        f"Account.__repr__ must start with 'Account(' (standard Python repr convention); got {repr_str!r}"
    )

    # Must INCLUDE the account ID — the primary key is the
    # natural correlation id for debugging.
    assert _SAMPLE_ACCT_ID in repr_str, (
        f"Account.__repr__ must include the acct_id value for "
        f"debugging / log correlation; did not find "
        f"{_SAMPLE_ACCT_ID!r} in {repr_str!r}"
    )

    # Must INCLUDE the active status — a single character that
    # answers the common question "is this account active?".
    assert _SAMPLE_STATUS in repr_str, (
        f"Account.__repr__ must include the active_status flag; did not find {_SAMPLE_STATUS!r} in {repr_str!r}"
    )

    # Must INCLUDE the current balance value. Because the repr
    # uses ``{self.curr_bal!r}``, the rendered form is
    # ``Decimal('1234.56')`` — not just ``1234.56``. We search
    # for the numeric substring which appears in both forms to
    # make the assertion robust against minor repr-format
    # tweaks (e.g., removing the ``Decimal(...)`` wrapper).
    curr_bal_numeric_substring = str(_SAMPLE_CURR_BAL)
    assert curr_bal_numeric_substring in repr_str, (
        f"Account.__repr__ must include the curr_bal value "
        f"(as a numeric substring, e.g., '1234.56' or "
        f"'Decimal(\\'1234.56\\')'); did not find "
        f"{curr_bal_numeric_substring!r} in {repr_str!r}"
    )


# =============================================================================
# Phase 8: FILLER Field Exclusion Test
# =============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """COBOL FILLER PIC X(178) must NOT be mapped to any column.

    The legacy ``ACCOUNT-RECORD`` in ``app/cpy/CVACT01Y.cpy``
    concludes with::

        05  FILLER                            PIC X(178).

    This 178-byte padding region exists solely to bring the
    total VSAM record length to the declared RECLN of 300
    bytes::

        11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300

    (The COBOL ``PIC S9(10)V99`` fields each occupy 12 bytes
    on disk — 10 digits + 2 decimal-place digits; sign is
    overlaid on the final digit and does not add a byte for
    signed DISPLAY storage.)

    FILLER carries no business semantics — it is literally
    un-used bytes. The Python translation correctly drops this
    field: there is no ``filler`` column on the :class:`Account`
    model, no ``filler_`` prefix, and no column named anything
    suggestive of padding / reserved bytes.

    This test enforces that contract at two levels:

    1. **Exact-match level**: the column set is exactly the
       13 expected names documented in the module constant
       ``_EXPECTED_COLUMNS``. Any extra column — whether named
       "filler", "reserved", "padding", or anything else —
       fails this assertion.
    2. **Keyword-search level**: as a belt-and-braces check,
       scan every column name for the substring ``"filler"``
       (case-insensitive). This catches exotic regressions
       where the expected-column set is also updated to
       include a mistakenly-mapped FILLER-shaped column.

    The two levels are both necessary: level 1 guards against
    extraneous columns in general, level 2 specifically
    guards against the FILLER anti-pattern.
    """
    columns = Account.__table__.columns
    # Level 1 compares against ``_EXPECTED_COLUMNS`` (Python attribute
    # names), so use ``Column.key`` rather than ``Column.name``.
    # Level 2 scans BOTH the Python key AND the DB column name for
    # ``'filler'`` — a regression could appear in either form.
    column_keys = frozenset(c.key for c in columns)
    column_db_names = frozenset(c.name for c in columns)

    # Level 1 — exact set match (against Python attribute names).
    assert column_keys == _EXPECTED_COLUMNS, (
        f"Account column set must be exactly "
        f"{sorted(_EXPECTED_COLUMNS)!r} (13 columns: 12 COBOL "
        f"named fields + 1 Python version_id). COBOL FILLER "
        f"PIC X(178) at the end of ACCOUNT-RECORD must NOT be "
        f"mapped — it is purely structural padding to bring "
        f"the VSAM record to RECLN=300. Found "
        f"{sorted(column_keys)!r}; unexpected: "
        f"{sorted(column_keys - _EXPECTED_COLUMNS)!r}; "
        f"missing: {sorted(_EXPECTED_COLUMNS - column_keys)!r}"
    )

    # Level 2 — keyword-search for FILLER-style names. Scan
    # every column name (both Python key AND DB column name) for
    # substrings that would suggest an accidentally-mapped padding
    # field.
    for column_name in column_keys | column_db_names:
        assert "filler" not in column_name.lower(), (
            f"Account must not expose any FILLER-style column "
            f"(COBOL FILLER PIC X(178) is padding and carries "
            f"no business meaning); found column "
            f"{column_name!r} whose name contains 'filler'"
        )
