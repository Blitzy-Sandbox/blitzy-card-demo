# ============================================================================
# Source: COBOL copybook CVTRA01Y.cpy — TRAN-CAT-BAL-RECORD (50 bytes, composite key 17)
# ============================================================================
# Tests validate 3-part composite PK, monetary balance field, and constraints
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
"""Unit tests for the :class:`TransactionCategoryBalance` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CVTRA01Y.cpy`` (record layout ``TRAN-CAT-BAL-RECORD``, a
50-byte VSAM KSDS record with a 17-byte composite key) into the
SQLAlchemy 2.x declarative ORM model at
``src/shared/models/transaction_category_balance.py``.

COBOL Source Layout (``CVTRA01Y.cpy``)
--------------------------------------
::

    01  TRAN-CAT-BAL-RECORD.
        05  TRAN-CAT-KEY.
           10 TRANCAT-ACCT-ID                       PIC 9(11).
           10 TRANCAT-TYPE-CD                       PIC X(02).
           10 TRANCAT-CD                            PIC 9(04).
        05  TRAN-CAT-BAL                            PIC S9(09)V99.
        05  FILLER                                  PIC X(22).

The ``TRAN-CAT-KEY`` *group item* at offset 0 (17 bytes total — 11 for
``TRANCAT-ACCT-ID`` + 2 for ``TRANCAT-TYPE-CD`` + 4 for
``TRANCAT-CD``) is the VSAM KSDS primary key declared in
``app/jcl/TCATBALF.jcl`` (``DEFINE CLUSTER ... KEYS(17 0)``). The ORM
model translates this group-level key into a 3-part composite primary
key using SQLAlchemy's ``primary_key=True`` flag on each participating
column, preserving the COBOL byte order as the Python declaration
order.

Field Mapping (COBOL -> SQLAlchemy)
-----------------------------------
=========================  ======================  ================================  ===========================
COBOL Field                SQLAlchemy Column       Column Type                       Notes
=========================  ======================  ================================  ===========================
``TRANCAT-ACCT-ID``        ``acct_id``             ``String(11)``                    Composite PK part 1 (11 B)
``TRANCAT-TYPE-CD``        ``type_cd``             ``String(2)``                     Composite PK part 2 (2 B)
``TRANCAT-CD``             ``cat_cd``              ``String(4)``                     Composite PK part 3 (4 B)
``TRAN-CAT-BAL``           ``balance``             ``Numeric(15, 2)``                Signed, 2 fractional digits
``FILLER``                 *(not mapped)*          *(n/a)*                           COBOL padding, 22 B
=========================  ======================  ================================  ===========================

The ``PIC 9(n)`` -> ``String(n)`` mapping (rather than an integer) is
intentional: seed data preserves the leading zeros of COBOL
numeric-display characters (e.g., ``'00000000001'`` ≠ ``1``), and the
interest-calculation job (``CBACT04C`` -> ``intcalc_job.py``) relies on
textual equality when joining against ``accounts.acct_id``.

The ``PIC S9(09)V99`` -> ``Numeric(15, 2)`` mapping is CRITICAL: the
``15`` digits of precision give headroom above the 11-digit COBOL
maximum (9 integer + 2 fractional); the ``2`` scale preserves the
fixed two-decimal-place contract enforced by COBOL ``V99``. Floating
point is NEVER acceptable for monetary values per AAP §0.7.2 —
``Numeric`` maps to PostgreSQL ``NUMERIC`` (arbitrary-precision
decimal), which is the only safe type for currency arithmetic.

Business Context
----------------
``TRAN-CAT-BAL-RECORD`` is the running balance accumulator for every
``(account, transaction-type, transaction-category)`` triple across
the credit-card portfolio. It is read and updated by:

* ``CBTRN02C`` (posttran_job) — applies posted transactions to the
  appropriate category balance via ``REWRITE`` after locate-or-create.
* ``CBACT04C`` (intcalc_job) — computes monthly interest as
  ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` per AAP §0.7.1; a drift in
  either factor (including the sign/scale of ``TRAN-CAT-BAL``) would
  misstate every customer's billed interest.
* ``CBSTM03A`` (creastmt_job) — projects per-category balance rollups
  onto the monthly statement.

Because the ``balance`` participates directly in the interest-calc
formula, the column type, scale, and ``Decimal`` default are all
first-class invariants — deviations propagate as recurring
financial-precision regressions.

Test Coverage (13 functions)
----------------------------
1. :func:`test_tablename`                         — ``__tablename__`` value.
2. :func:`test_column_count`                      — exact column count (4).
3. :func:`test_composite_primary_key`             — 3-part PK identity, order, types.
4. :func:`test_composite_key_matches_cobol_group` — PK mirrors ``TRAN-CAT-KEY``.
5. :func:`test_acct_id_type`                      — ``acct_id`` is ``String(11)``.
6. :func:`test_type_cd_type`                      — ``type_cd`` is ``String(2)``.
7. :func:`test_cat_cd_type`                       — ``cat_cd`` is ``String(4)``.
8. :func:`test_balance_type`                      — ``balance`` is ``Numeric(15, 2)``.
9. :func:`test_balance_default`                   — ``balance`` default is ``Decimal('0.00')``.
10. :func:`test_non_nullable_fields`              — all 4 columns ``NOT NULL``.
11. :func:`test_create_instance`                  — constructor + field readback + Base.
12. :func:`test_repr`                             — ``__repr__`` format.
13. :func:`test_no_filler_columns`                — ``FILLER PIC X(22)`` is excluded.

See Also
--------
* ``app/cpy/CVTRA01Y.cpy`` — COBOL source copybook.
* ``app/jcl/TCATBALF.jcl`` — VSAM KSDS provisioning (``KEYS(17 0)``).
* ``db/migrations/V1__schema.sql`` — ``CREATE TABLE transaction_category_balances``.
* ``db/migrations/V3__seed_data.sql`` — 50 seed rows.
* ``src/shared/models/transaction_category_balance.py`` — model under test.
* ``src/batch/jobs/posttran_job.py`` — writer.
* ``src/batch/jobs/intcalc_job.py`` — interest-calc reader.

Per AAP §0.5.1 (Shared Models), AAP §0.7.2 (Financial Precision), and
AAP §0.7.1 (Refactoring-Specific Rules).
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import Numeric, String, inspect

from src.shared.models import Base
from src.shared.models.transaction_category_balance import TransactionCategoryBalance

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# Set of the 4 expected mapped column names (Python attribute names,
# which are also the SQL column names under SQLAlchemy's default
# resolution). The COBOL ``FILLER PIC X(22)`` at the end of the 50-byte
# record is DELIBERATELY absent — padding regions have no place in the
# relational model.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "acct_id",  # from TRANCAT-ACCT-ID PIC 9(11) — composite PK part 1
        "type_cd",  # from TRANCAT-TYPE-CD PIC X(02) — composite PK part 2
        "cat_cd",  # from TRANCAT-CD      PIC 9(04) — composite PK part 3
        "balance",  # from TRAN-CAT-BAL    PIC S9(09)V99
    }
)

# Ordered tuple representing the composite primary key declaration
# order in the ORM model. The order matches the COBOL ``TRAN-CAT-KEY``
# group definition (``TRANCAT-ACCT-ID`` first at offset 0..10, then
# ``TRANCAT-TYPE-CD`` at offset 11..12, then ``TRANCAT-CD`` at offset
# 13..16) which is the VSAM KSDS primary-key byte order declared in
# ``app/jcl/TCATBALF.jcl`` as ``KEYS(17 0)``.
_EXPECTED_COMPOSITE_PK_NAMES: tuple[str, ...] = (
    "acct_id",
    "type_cd",
    "cat_cd",
)

# Example composite-key values used by :func:`test_create_instance` and
# :func:`test_repr`. Each value is zero-padded to the exact COBOL PIC
# width: 11 chars for ``acct_id``, 2 for ``type_cd``, 4 for ``cat_cd``.
# The padding is NOT cosmetic — it is the actual byte content of the
# VSAM key and must be preserved through the ORM round-trip to avoid
# join misses against ``accounts.acct_id`` in the batch pipeline.
_SAMPLE_ACCT_ID: str = "00000012345"  # 11 chars (PIC 9(11))
_SAMPLE_TYPE_CD: str = "01"  # 2 chars  (PIC X(02))
_SAMPLE_CAT_CD: str = "1001"  # 4 chars  (PIC 9(04))
_SAMPLE_BALANCE: Decimal = Decimal("500.75")  # PIC S9(09)V99 — NEVER float


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """TransactionCategoryBalance must be mapped to the correct table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` —
      ``CREATE TABLE transaction_category_balances``
    * ``db/migrations/V3__seed_data.sql`` —
      ``INSERT INTO transaction_category_balances`` (50 seed rows, one
      per account in the test-fixture portfolio).
    * The batch transaction-posting program (``CBTRN02C`` ->
      ``posttran_job.py``) that updates the running balance for each
      ``(acct_id, type_cd, cat_cd)`` triple after every posted
      transaction.
    * The batch interest-calculation program (``CBACT04C`` ->
      ``intcalc_job.py``) that reads the balance to compute monthly
      interest.

    Any drift between ``TransactionCategoryBalance.__tablename__`` and
    the DDL / seed-data contract would cause runtime
    ``UndefinedTable`` errors, so this test locks the value down as an
    explicit invariant rather than relying on the conventional
    pluralization applied across the shared-models package.
    """
    assert TransactionCategoryBalance.__tablename__ == "transaction_category_balances", (
        f"TransactionCategoryBalance must be mapped to the "
        f"'transaction_category_balances' table (matching "
        f"db/migrations/V1__schema.sql and the project-wide plural "
        f"table-naming convention); found "
        f"{TransactionCategoryBalance.__tablename__!r}. A drift here "
        f"causes runtime UndefinedTable errors on every query against "
        f"the ORM model."
    )


@pytest.mark.unit
def test_column_count() -> None:
    """The ORM model must expose exactly 4 mapped columns.

    COBOL copybook ``CVTRA01Y.cpy`` declares 5 elementary data items:

    * ``TRANCAT-ACCT-ID``  (part of ``TRAN-CAT-KEY``) -> ``acct_id``
    * ``TRANCAT-TYPE-CD``  (part of ``TRAN-CAT-KEY``) -> ``type_cd``
    * ``TRANCAT-CD``       (part of ``TRAN-CAT-KEY``) -> ``cat_cd``
    * ``TRAN-CAT-BAL``                                 -> ``balance``
    * ``FILLER``                                       -> *(not mapped)*

    The relational model drops the ``FILLER PIC X(22)`` padding (it
    only exists to pad the VSAM record from the 28-byte data payload
    out to the fixed 50-byte record length; it has no semantic role),
    leaving 4 mapped columns. Any count other than 4 indicates either
    the accidental addition of an unrelated column or the
    reintroduction of the ``FILLER`` padding as a column — both of
    which would break the single-source-of-truth invariant between
    the COBOL copybook and the SQLAlchemy model.
    """
    columns = list(TransactionCategoryBalance.__table__.columns)
    column_names = [c.name for c in columns]
    assert len(columns) == 4, (
        f"TransactionCategoryBalance must expose exactly 4 mapped "
        f"columns (acct_id, type_cd, cat_cd, balance — "
        f"FILLER PIC X(22) is NOT mapped); found "
        f"{len(columns)} columns: {column_names!r}. "
        f"Expected set: {sorted(_EXPECTED_COLUMNS)!r}."
    )


# ============================================================================
# Phase 3: Composite Primary Key Tests (CRITICAL)
# ============================================================================


@pytest.mark.unit
def test_composite_primary_key() -> None:
    """The primary key must be a 3-part composite of key fields.

    This is the single most important test in this suite because the
    VSAM KSDS lookup semantics of ``CBACT04C`` and ``CBTRN02C`` both
    depend on the primary key being the *exact* concatenation of the
    three COBOL ``TRAN-CAT-KEY`` elementary fields in their declared
    byte order.

    Three invariants are asserted to guard against regressions in the
    most critical part of the COBOL -> SQLAlchemy translation:

    1. **Cardinality** — exactly 3 PK columns (not 1, not 2, not 4).
       The VSAM KSDS ``KEYS(17 0)`` on ``app/jcl/TCATBALF.jcl`` spans
       the first 17 bytes of the record, which is the concatenation of
       the three ``TRAN-CAT-KEY`` elementary fields
       (11 + 2 + 4 = 17 bytes).

    2. **Identity and order** — the PK column names must be
       ``("acct_id", "type_cd", "cat_cd")``, in that order, to match
       the COBOL key-byte order. Order matters because PostgreSQL's
       B-tree index on the composite PK stores rows sorted by the
       first column, then the second, then the third — a different
       declaration order would change the on-disk sort order and
       silently break range queries that rely on ``acct_id`` as the
       leading key.

    3. **Types** — each PK part must be declared with the String
       length corresponding to its COBOL PIC clause:

       * ``acct_id``  -> ``String(11)`` from ``PIC 9(11)``
       * ``type_cd``  -> ``String(2)``  from ``PIC X(02)``
       * ``cat_cd``   -> ``String(4)``  from ``PIC 9(04)``

       The ``PIC 9(n)`` -> ``String(n)`` mapping (rather than an
       integer) is intentional: seed data preserves the leading zeros
       of COBOL numeric-display characters (e.g., ``'00000000001'`` ≠
       ``1``), and downstream joins against ``accounts.acct_id`` rely
       on textual equality.
    """
    primary_key_columns = list(inspect(TransactionCategoryBalance).primary_key)

    # Invariant 1 — exactly 3 PK columns.
    assert len(primary_key_columns) == 3, (
        f"TransactionCategoryBalance must have a 3-part composite "
        f"primary key (acct_id + type_cd + cat_cd, per COBOL "
        f"TRAN-CAT-KEY); found {len(primary_key_columns)} PK "
        f"columns: {[c.key for c in primary_key_columns]}"
    )

    # Invariant 2 — identity and order.
    # Use ``Column.key`` (Python attribute key) rather than
    # ``Column.name`` (DB physical column name), because the columns
    # are declared via ``mapped_column("type_code", ..., key="type_cd")``
    # and ``mapped_column("cat_code", ..., key="cat_cd")`` —
    # ``_EXPECTED_COMPOSITE_PK_NAMES`` contains the Python keys.
    actual_pk_names = tuple(c.key for c in primary_key_columns)
    assert actual_pk_names == _EXPECTED_COMPOSITE_PK_NAMES, (
        f"Composite primary key columns must be declared in the order "
        f"{_EXPECTED_COMPOSITE_PK_NAMES!r} (matching the COBOL "
        f"TRAN-CAT-KEY byte layout: TRANCAT-ACCT-ID at 0..10, "
        f"TRANCAT-TYPE-CD at 11..12, TRANCAT-CD at 13..16); "
        f"found {actual_pk_names!r}"
    )

    # Invariant 3 — types and lengths of each PK part.
    # Keyed by Python attribute name (Column.key), not DB name.
    expected_types: dict[str, int] = {
        "acct_id": 11,  # TRANCAT-ACCT-ID PIC 9(11)
        "type_cd": 2,  # TRANCAT-TYPE-CD PIC X(02)
        "cat_cd": 4,  # TRANCAT-CD      PIC 9(04)
    }
    for pk_column in primary_key_columns:
        assert isinstance(pk_column.type, String), (
            f"Primary key column {pk_column.key!r} must be declared "
            f"as a String type (from COBOL PIC X/9 clauses); found "
            f"{type(pk_column.type).__name__}"
        )
        # ``Column.key`` is typed ``str | None`` in SQLAlchemy 2.x but
        # every mapped column in this ORM model declares an explicit
        # ``key=`` so the value is guaranteed to be a string — assert
        # for mypy and for defensive runtime safety.
        assert pk_column.key is not None
        expected_length = expected_types[pk_column.key]
        assert pk_column.type.length == expected_length, (
            f"Primary key column {pk_column.key!r} must be "
            f"String({expected_length}) per the COBOL copybook; "
            f"found String({pk_column.type.length})"
        )


@pytest.mark.unit
def test_composite_key_matches_cobol_group() -> None:
    """The Python PK must mirror the COBOL ``TRAN-CAT-KEY`` group.

    The COBOL copybook defines::

        05  TRAN-CAT-KEY.
           10 TRANCAT-ACCT-ID   PIC 9(11).
           10 TRANCAT-TYPE-CD   PIC X(02).
           10 TRANCAT-CD        PIC 9(04).
        05  TRAN-CAT-BAL        PIC S9(09)V99.
        05  FILLER              PIC X(22).

    Two semantic properties of this layout must be preserved:

    * **Inclusion** — every element inside ``TRAN-CAT-KEY`` must be
      part of the ORM primary key. Omitting any would reduce the
      key's cardinality and break the uniqueness contract on which
      ``CBTRN02C``'s locate-or-create logic depends.
    * **Exclusion** — every element *outside* ``TRAN-CAT-KEY``
      (``TRAN-CAT-BAL`` and ``FILLER``) must NOT be part of the
      primary key. The running balance participates in arithmetic,
      not identity (it changes with every posted transaction); the
      filler participates in neither.

    Combined, these assertions verify that the Python PK set is
    *exactly equivalent* to the set of 3 elementary COBOL fields
    rolled up under ``TRAN-CAT-KEY`` — no more, no fewer.
    """
    # Fields that appear inside the COBOL ``TRAN-CAT-KEY`` group item
    # and MUST therefore appear in the ORM primary key.
    expected_pk_fields: frozenset[str] = frozenset(_EXPECTED_COMPOSITE_PK_NAMES)

    # Fields that appear OUTSIDE ``TRAN-CAT-KEY`` and MUST NOT appear
    # in the ORM primary key. ``balance`` is the running category
    # balance (mutates over time — cannot be a PK); ``filler`` would
    # be the FILLER padding if it were mapped (but it isn't — see
    # :func:`test_no_filler_columns`).
    expected_non_pk_fields: frozenset[str] = frozenset({"balance"})

    primary_key_columns = list(inspect(TransactionCategoryBalance).primary_key)
    # Use ``Column.key`` (Python attribute key) rather than
    # ``Column.name`` (DB physical column name), because the columns
    # are declared with distinct names and keys via ``mapped_column(
    # "type_code", ..., key="type_cd")`` — ``expected_pk_fields`` is
    # the Python-key set.  ``Column.key`` is typed ``str | None`` in
    # SQLAlchemy 2.x but every mapped column in this ORM model has an
    # explicit ``key=`` so the value is guaranteed to be a string.
    actual_pk_fields: frozenset[str] = frozenset(str(c.key) for c in primary_key_columns)

    # Inclusion — every COBOL key-group element must be in the PK.
    missing_from_pk = expected_pk_fields - actual_pk_fields
    assert not missing_from_pk, (
        f"The following COBOL TRAN-CAT-KEY fields are missing from "
        f"the ORM primary key: {sorted(missing_from_pk)!r}. The "
        f"composite PK must include ALL 3 elementary items declared "
        f"inside the COBOL TRAN-CAT-KEY group (acct_id, type_cd, "
        f"cat_cd) to preserve the VSAM KEYS(17 0) uniqueness contract."
    )

    # Exclusion — nothing outside the COBOL key-group may be in the PK.
    unexpectedly_in_pk = actual_pk_fields & expected_non_pk_fields
    assert not unexpectedly_in_pk, (
        f"The following non-key fields are unexpectedly in the ORM "
        f"primary key: {sorted(unexpectedly_in_pk)!r}. TRAN-CAT-BAL "
        f"is a mutable running balance and MUST NOT participate in "
        f"row identity — otherwise each posted transaction would "
        f"violate the PK contract on REWRITE."
    )

    # Equivalence — the PK set must be exactly the expected set.
    assert actual_pk_fields == expected_pk_fields, (
        f"ORM primary key set must exactly match the COBOL "
        f"TRAN-CAT-KEY elementary fields {sorted(expected_pk_fields)!r}; "
        f"found {sorted(actual_pk_fields)!r}"
    )


# ============================================================================
# Phase 4: Column Type Tests
# ============================================================================


@pytest.mark.unit
def test_acct_id_type() -> None:
    """``acct_id`` must be ``String(11)`` from ``TRANCAT-ACCT-ID PIC 9(11)``.

    ``TRANCAT-ACCT-ID`` is the leading byte-slice of the VSAM KSDS key
    (offset 0..10, 11 bytes) and also the foreign-key anchor to
    ``accounts.acct_id`` (``CVACT01Y.cpy`` ``ACCT-ID PIC 9(11)``).

    The mapping rules are:

    * ``PIC 9(11)`` -> ``String(11)``, NOT ``Integer`` or ``BigInt``.
      Account IDs are COBOL display-numeric (zoned decimal) strings
      with significant leading zeros (``'00000000001'`` is the seed
      row for account #1). An integer mapping would strip those zeros
      and break the textual join against ``accounts.acct_id``.

    * ``length == 11`` — an off-by-one on the length would silently
      truncate the rightmost digit of every account ID at the
      PostgreSQL layer, causing random-looking lookup misses in the
      interest-calc and posting jobs.
    """
    column = TransactionCategoryBalance.__table__.columns["acct_id"]

    assert isinstance(column.type, String), (
        f"acct_id must be a String type (from COBOL TRANCAT-ACCT-ID "
        f"PIC 9(11), stored as zoned-decimal characters); found "
        f"{type(column.type).__name__}. Do NOT map COBOL display-"
        f"numeric fields to Integer/BigInt — leading zeros would be "
        f"lost and join equality with accounts.acct_id would break."
    )
    assert column.type.length == 11, (
        f"acct_id must be String(11) (matching TRANCAT-ACCT-ID PIC 9(11), 11 bytes); found String({column.type.length})"
    )


@pytest.mark.unit
def test_type_cd_type() -> None:
    """``type_cd`` must be ``String(2)`` from ``TRANCAT-TYPE-CD PIC X(02)``.

    ``TRANCAT-TYPE-CD`` is the second byte-slice of the VSAM KSDS key
    (offset 11..12, 2 bytes) and is the foreign-key anchor to
    ``transaction_types.type_cd`` (``CVTRA03Y.cpy``
    ``TRAN-TYPE PIC X(02)``).

    The mapping rules are:

    * ``PIC X(02)`` -> ``String(2)``. COBOL ``PIC X`` is an
      alphanumeric type, so a ``String`` mapping is the only sensible
      choice.
    * ``length == 2`` — the seed data in ``app/data/ASCII/trantype.txt``
      ships 7 rows with 2-character type codes (``'01'``..``'07'``);
      any other length would either reject valid seed data or accept
      malformed codes at insert time.
    """
    column = TransactionCategoryBalance.__table__.columns["type_cd"]

    assert isinstance(column.type, String), (
        f"type_cd must be a String type (from COBOL TRANCAT-TYPE-CD PIC X(02)); found {type(column.type).__name__}"
    )
    assert column.type.length == 2, (
        f"type_cd must be String(2) (matching TRANCAT-TYPE-CD PIC X(02), 2 bytes); found String({column.type.length})"
    )


@pytest.mark.unit
def test_cat_cd_type() -> None:
    """``cat_cd`` must be ``String(4)`` from ``TRANCAT-CD PIC 9(04)``.

    ``TRANCAT-CD`` is the trailing byte-slice of the VSAM KSDS key
    (offset 13..16, 4 bytes) and is part of the foreign-key anchor
    to ``transaction_categories.cat_cd`` (``CVTRA04Y.cpy``
    ``TRAN-CAT-CD PIC 9(04)``).

    The mapping rules are:

    * ``PIC 9(04)`` -> ``String(4)``, NOT ``Integer``. Category codes
      are zoned-decimal display fields with significant leading zeros
      (``'0001'``..``'0018'`` in
      ``app/data/ASCII/trancatg.txt``). An integer mapping would
      strip the leading zeros and break textual equality with the
      4-character seed data.
    * ``length == 4`` — matches the COBOL PIC clause exactly.
    """
    column = TransactionCategoryBalance.__table__.columns["cat_cd"]

    assert isinstance(column.type, String), (
        f"cat_cd must be a String type (from COBOL TRANCAT-CD "
        f"PIC 9(04), stored as zoned-decimal characters with "
        f"significant leading zeros); found "
        f"{type(column.type).__name__}. Do NOT map COBOL display-"
        f"numeric fields to Integer — leading zeros would be lost."
    )
    assert column.type.length == 4, (
        f"cat_cd must be String(4) (matching TRANCAT-CD PIC 9(04), 4 bytes); found String({column.type.length})"
    )


# ============================================================================
# Phase 5: Monetary Balance Field Test (CRITICAL — No Float)
# ============================================================================


@pytest.mark.unit
def test_balance_type() -> None:
    """``balance`` must be ``Numeric(15, 2)`` from ``TRAN-CAT-BAL PIC S9(09)V99``.

    This is one of the most critical assertions in the suite because
    a drift to ``Float`` — even transiently — would silently
    misstate every customer's billed interest.

    Per AAP §0.7.2 (Financial Precision):

    * ALL monetary fields MUST map to SQLAlchemy ``Numeric`` (which
      compiles to PostgreSQL ``NUMERIC`` — arbitrary-precision
      decimal). Floating point is NEVER acceptable for currency
      because IEEE-754 base-2 representation cannot exactly encode
      base-10 fractions like ``0.10``.

    Three invariants are asserted:

    1. **Type** — ``isinstance(column.type, Numeric)``. A
       ``Float``/``REAL``/``DOUBLE_PRECISION`` mapping would pass
       coverage checks but silently cause rounding errors on every
       interest calculation.

    2. **Precision (== 15)** — the total number of significant
       decimal digits. COBOL ``PIC S9(09)V99`` supports 11 digits
       (9 integer + 2 fractional); the ``Numeric(15, 2)`` mapping
       adds 4 digits of headroom to guard against arithmetic
       intermediate overflow during interest-accumulation loops.
       A precision smaller than 11 would truncate valid COBOL data;
       a precision exactly 11 would leave no margin for intermediate
       sums; a precision greater than 15 would exceed the shared-
       models convention used across all other monetary fields.

    3. **Scale (== 2)** — exactly two fractional digits. The
       ``V99`` clause in COBOL declares an implicit decimal point
       after the penultimate digit, fixing the scale at 2. A scale
       of 0 or 1 would round every balance; a scale of 3+ would
       store sub-cent precision that has no corresponding real-
       world currency unit.
    """
    column = TransactionCategoryBalance.__table__.columns["balance"]

    # Invariant 1 — Numeric, NOT Float.
    assert isinstance(column.type, Numeric), (
        f"balance MUST be a Numeric type (maps to PostgreSQL NUMERIC — "
        f"arbitrary-precision decimal) per AAP §0.7.2; found "
        f"{type(column.type).__name__}. Floating-point types are "
        f"PROHIBITED for monetary fields — IEEE-754 cannot exactly "
        f"represent base-10 fractions and every interest calculation "
        f"would drift."
    )

    # Invariant 2 — precision == 15.
    assert column.type.precision == 15, (
        f"balance must have precision=15 (15 total decimal digits, "
        f"providing 4-digit headroom above the 11-digit COBOL "
        f"PIC S9(09)V99 maximum to absorb intermediate "
        f"accumulation in CBACT04C's interest-calculation loop); "
        f"found precision={column.type.precision}"
    )

    # Invariant 3 — scale == 2.
    assert column.type.scale == 2, (
        f"balance must have scale=2 (exactly 2 fractional digits, "
        f"matching the COBOL V99 clause that fixes the implicit "
        f"decimal point after the penultimate digit); found "
        f"scale={column.type.scale}"
    )


@pytest.mark.unit
def test_balance_default() -> None:
    """``balance`` must default to ``Decimal('0.00')`` (not zero-as-int or float).

    Per AAP §0.7.2 (Financial Precision), the default clause for a
    monetary column must itself be a ``decimal.Decimal`` instance —
    not a Python ``int`` (``0``), not a float (``0.0``), not a plain
    string (``"0.00"``). Each alternate is subtly wrong:

    * ``int(0)`` — would coerce to ``Decimal(0)``, which has scale 0
      and would cause repr-diffs against ``Decimal('0.00')``.
    * ``float(0.0)`` — PROHIBITED by AAP §0.7.2. Even a literal
      ``0.0`` is an IEEE-754 value, not a decimal value, and injects
      floating-point semantics into the ORM default chain.
    * ``str("0.00")`` — would rely on SQLAlchemy's implicit
      conversion at INSERT time, which is not guaranteed to preserve
      scale across dialects.

    Only ``Decimal('0.00')`` unambiguously encodes both the value
    (``0``) and the scale (``2``) required by ``PIC S9(09)V99``.
    This default ensures administratively-created rows (e.g., the
    locate-or-create path in ``CBTRN02C`` when a new
    ``(acct_id, type_cd, cat_cd)`` triple is first posted) are never
    ``NULL`` and never drift to a float representation.
    """
    column = TransactionCategoryBalance.__table__.columns["balance"]

    # Default clause must be present.
    assert column.default is not None, (
        "balance must declare a default value of Decimal('0.00') so "
        "that administratively-created rows (locate-or-create path "
        "in CBTRN02C) are never NULL; found no default clause on "
        "the column."
    )

    # Unwrap the SQLAlchemy ColumnDefault wrapper to inspect the
    # underlying Python literal.
    default_value = column.default.arg

    # Hard type check — ``Decimal``, not ``float``, not ``int``.
    assert isinstance(default_value, Decimal), (
        f"balance default MUST be a decimal.Decimal instance per "
        f"AAP §0.7.2 (financial precision); found "
        f"{type(default_value).__name__} ({default_value!r}). "
        f"Do NOT use float(0.0) or int(0) — they lose the two-"
        f"decimal-place precision contract."
    )

    # Value check — exactly Decimal('0.00'), not Decimal('0').
    assert default_value == Decimal("0.00"), (
        f"balance default must be Decimal('0.00') exactly (with two-decimal-place scale); found {default_value!r}"
    )


# ============================================================================
# Phase 6: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 4 mapped columns must be declared ``NOT NULL``.

    The COBOL record layout has no concept of NULL — every byte of
    every field is always populated (with actual data, with spaces
    for ``PIC X``, or with zeros for ``PIC 9``). Faithful translation
    requires every mapped column to carry an explicit
    ``nullable=False`` declaration:

    * **Composite PK parts** (``acct_id``, ``type_cd``, ``cat_cd``)
      — primary-key columns are implicitly NOT NULL by PostgreSQL's
      PRIMARY KEY constraint, but explicit ``nullable=False`` is
      still asserted as a safety net and a documentation signal.
    * **Running balance** (``balance``) — a NULL balance would be
      ambiguous (is there no row yet, or is the balance zero?);
      explicit ``NOT NULL`` combined with the ``Decimal('0.00')``
      default guarantees that every read returns a deterministic
      monetary value that ``CBACT04C`` can multiply by the interest
      rate without NULL-propagation.

    Iterates through :data:`_EXPECTED_COLUMNS` so that any future
    addition to the model will require explicit NOT-NULL status
    (if legitimate) or an explicit exclusion (if nullability is
    deliberately introduced).
    """
    for column_name in _EXPECTED_COLUMNS:
        column = TransactionCategoryBalance.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column {column_name!r} must be declared NOT NULL "
            f"(nullable=False) — the COBOL TRAN-CAT-BAL-RECORD has "
            f"no concept of NULL, and downstream batch jobs "
            f"(CBACT04C, CBTRN02C) assume every column always "
            f"carries a deterministic value; found "
            f"nullable={column.nullable!r}"
        )


# ============================================================================
# Phase 7: Instance Creation Tests
# ============================================================================


@pytest.mark.unit
def test_create_instance() -> None:
    """The ORM model must be constructible via keyword arguments.

    Exercises the happy-path constructor contract used by the batch
    posttran and intcalc jobs (via SQLAlchemy session INSERTs) and by
    unit/integration tests that build fixtures in-memory:

    * **All 4 columns provided** — every column is declared NOT NULL
      (see :func:`test_non_nullable_fields`), so every field must be
      explicitly supplied at construction time.
    * **Composite key uses zero-padded strings** —
      ``acct_id='00000012345'`` is 11 chars (``PIC 9(11)``),
      ``type_cd='01'`` is 2 chars (``PIC X(02)``),
      ``cat_cd='1001'`` is 4 chars (``PIC 9(04)``). The padding
      matters because ``CBTRN02C`` locates existing rows by exact-
      string equality against these keys.
    * **Balance uses Decimal, NEVER float** — per AAP §0.7.2.
    * **Inherits from Base** — the model must correctly register
      with SQLAlchemy's metadata so that ``CREATE TABLE`` is
      emitted in ``db/migrations/V1__schema.sql`` and so that
      ``select(TransactionCategoryBalance)`` works at runtime.

    The test does NOT persist to a database — it only verifies that
    the Python object model round-trips through the constructor
    without triggering ORM-level validation errors, and that each
    supplied value is readable via attribute access post-
    construction.
    """
    tcb = TransactionCategoryBalance(
        acct_id=_SAMPLE_ACCT_ID,
        type_cd=_SAMPLE_TYPE_CD,
        cat_cd=_SAMPLE_CAT_CD,
        balance=_SAMPLE_BALANCE,
    )

    # Base-class check — the model must correctly register with
    # SQLAlchemy's declarative metadata via inheritance from Base.
    assert isinstance(tcb, Base), (
        f"TransactionCategoryBalance instances must be instances of "
        f"the SQLAlchemy declarative Base class (from "
        f"src.shared.models); found MRO: "
        f"{[cls.__name__ for cls in type(tcb).__mro__]}. Without "
        f"this inheritance, the model would not participate in "
        f"Base.metadata and CREATE TABLE would not be emitted."
    )

    # Composite key — each part must round-trip unchanged through
    # the constructor, preserving zero-padding.
    assert tcb.acct_id == _SAMPLE_ACCT_ID, (
        f"acct_id must round-trip unchanged through the constructor "
        f"(expected {_SAMPLE_ACCT_ID!r} for PIC 9(11) zero-padded "
        f"account ID); found {tcb.acct_id!r}"
    )
    assert tcb.type_cd == _SAMPLE_TYPE_CD, (
        f"type_cd must round-trip unchanged through the constructor "
        f"(expected {_SAMPLE_TYPE_CD!r} for PIC X(02) type code); "
        f"found {tcb.type_cd!r}"
    )
    assert tcb.cat_cd == _SAMPLE_CAT_CD, (
        f"cat_cd must round-trip unchanged through the constructor "
        f"(expected {_SAMPLE_CAT_CD!r} for PIC 9(04) zero-padded "
        f"category code); found {tcb.cat_cd!r}"
    )

    # Balance — must preserve Decimal type AND exact value.
    assert isinstance(tcb.balance, Decimal), (
        f"balance MUST remain a decimal.Decimal instance after "
        f"construction per AAP §0.7.2; found "
        f"{type(tcb.balance).__name__} ({tcb.balance!r}). If "
        f"SQLAlchemy silently coerced the value to float or str, "
        f"downstream arithmetic in CBACT04C's interest-calc formula "
        f"would lose precision."
    )
    assert tcb.balance == _SAMPLE_BALANCE, (
        f"balance value must round-trip unchanged through the "
        f"constructor (expected {_SAMPLE_BALANCE!r} for PIC "
        f"S9(09)V99); found {tcb.balance!r}"
    )


@pytest.mark.unit
def test_repr() -> None:
    """``__repr__`` must return a readable developer-friendly string.

    The model-side ``__repr__`` declared in
    ``src/shared/models/transaction_category_balance.py`` follows the
    project-wide convention of listing the class name followed by
    every mapped attribute as ``name=repr(value)``. This test
    verifies:

    * The class name ``TransactionCategoryBalance`` is present.
    * All 4 mapped attribute names appear literally.
    * All 4 field values appear in their ``repr()`` form — in
      particular, the ``balance`` must be rendered as
      ``Decimal('...')`` (from ``Decimal.__repr__``), NEVER as a
      bare float literal. Downstream log parsers and debugging
      dashboards rely on this distinction to flag float-drift
      regressions instantly.
    """
    tcb = TransactionCategoryBalance(
        acct_id=_SAMPLE_ACCT_ID,
        type_cd=_SAMPLE_TYPE_CD,
        cat_cd=_SAMPLE_CAT_CD,
        balance=_SAMPLE_BALANCE,
    )
    repr_output = repr(tcb)

    # The class name must appear in the repr output.
    assert "TransactionCategoryBalance" in repr_output, (
        f"__repr__ must include the class name 'TransactionCategoryBalance' for debuggability; found {repr_output!r}"
    )

    # Each mapped attribute name must appear in the repr.
    for attribute_name in _EXPECTED_COLUMNS:
        assert attribute_name in repr_output, (
            f"__repr__ must include the attribute name {attribute_name!r} to be self-describing; found {repr_output!r}"
        )

    # Each attribute value must be rendered via ``repr()`` of the
    # underlying Python value, which (for strings) includes
    # surrounding quotes — this is why we search for ``repr(v)``
    # not ``str(v)``.
    assert repr(_SAMPLE_ACCT_ID) in repr_output, (
        f"__repr__ must include the acct_id value rendered via "
        f"repr() (expected substring {repr(_SAMPLE_ACCT_ID)!r}); "
        f"found {repr_output!r}"
    )
    assert repr(_SAMPLE_TYPE_CD) in repr_output, (
        f"__repr__ must include the type_cd value rendered via "
        f"repr() (expected substring {repr(_SAMPLE_TYPE_CD)!r}); "
        f"found {repr_output!r}"
    )
    assert repr(_SAMPLE_CAT_CD) in repr_output, (
        f"__repr__ must include the cat_cd value rendered via "
        f"repr() (expected substring {repr(_SAMPLE_CAT_CD)!r}); "
        f"found {repr_output!r}"
    )

    # CRITICAL: the balance must be rendered as Decimal(...) to
    # make float-drift regressions immediately obvious in logs.
    assert repr(_SAMPLE_BALANCE) in repr_output, (
        f"__repr__ must render balance as "
        f"{repr(_SAMPLE_BALANCE)!r} (NOT as a float literal such as "
        f"'500.75'); found {repr_output!r}. Any deviation breaks "
        f"the log-parser conventions that surface float-drift "
        f"regressions (AAP §0.7.2)."
    )


# ============================================================================
# Phase 8: FILLER Exclusion
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """COBOL ``FILLER PIC X(22)`` must NOT be mapped as a column.

    The COBOL record layout ends with ``FILLER PIC X(22)`` padding
    the 50-byte record from the 28-byte data payload (17 for
    ``TRAN-CAT-KEY`` + 11 for ``TRAN-CAT-BAL`` —
    ``PIC S9(09)V99`` occupies 11 display-character bytes in the
    fixed-length ASCII record) out to the full record length.
    Filler is a byte-alignment artefact of the fixed-length VSAM
    KSDS record format — it has no semantic meaning and no valid
    column-level representation.

    Mapping ``FILLER`` to a column in the relational model would be
    a bug for multiple reasons:

    * It would bloat every row with 22 bytes of meaningless padding.
    * It would introduce a nullable column without a business
      interpretation, breaking the NOT-NULL invariant asserted in
      :func:`test_non_nullable_fields`.
    * It would force downstream API schemas (if this table ever
      becomes directly exposed) to either expose or actively hide
      a padding field — both are maintenance burdens.

    Two assertions guard against regressions:

    * **Positive** — the mapped column set is *exactly* the
      expected set of 4 columns.
    * **Negative** — no column name contains the substring
      ``"filler"`` (case-insensitive), catching any misspelling
      (``Filler``, ``FILLER``, ``filler1``, etc.).
    """
    # ``_EXPECTED_COLUMNS`` holds Python-attribute names
    # (``acct_id``, ``type_cd``, ``cat_cd``, ``balance``); some DB
    # physical names differ (``type_code``, ``cat_code``,
    # ``tran_cat_bal``). Compare against ``Column.key`` for positive
    # equivalence and scan BOTH forms for 'filler' (defense in depth).
    column_keys = [c.key for c in TransactionCategoryBalance.__table__.columns]
    column_db_names = [c.name for c in TransactionCategoryBalance.__table__.columns]

    # Positive invariant — exact-set equivalence against the
    # FILLER-free expected set of Python attribute keys.
    assert set(column_keys) == set(_EXPECTED_COLUMNS), (
        f"TransactionCategoryBalance columns must exactly match "
        f"{sorted(_EXPECTED_COLUMNS)!r} (COBOL FILLER PIC X(22) is "
        f"NOT mapped); found {sorted(column_keys)!r}. "
        f"Missing: "
        f"{sorted(set(_EXPECTED_COLUMNS) - set(column_keys))!r}. "
        f"Extra: "
        f"{sorted(set(column_keys) - set(_EXPECTED_COLUMNS))!r}."
    )

    # Negative invariant — no column name (Python key OR DB column
    # name) resembles FILLER.
    for column_name in set(column_keys) | set(column_db_names):
        assert "filler" not in column_name.lower(), (
            f"Column name {column_name!r} contains the substring "
            f"'filler' (case-insensitive), which indicates the COBOL "
            f"FILLER PIC X(22) padding was unintentionally mapped. "
            f"FILLER is a byte-alignment artefact of the 50-byte "
            f"VSAM fixed-length record and must NOT appear in the "
            f"relational model."
        )
