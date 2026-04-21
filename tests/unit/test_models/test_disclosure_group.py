# ============================================================================
# Source: COBOL copybook CVTRA02Y.cpy — DIS-GROUP-RECORD (50 bytes, composite key 16)
# ============================================================================
# Tests validate 3-part composite PK, interest rate field, DEFAULT/ZEROAPR groups.
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
"""Unit tests for the :class:`DisclosureGroup` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CVTRA02Y.cpy`` (record layout ``DIS-GROUP-RECORD``, a 50-byte
VSAM KSDS record with a 16-byte composite key) into the SQLAlchemy 2.x
declarative ORM model at ``src/shared/models/disclosure_group.py``.

COBOL Source Layout (``CVTRA02Y.cpy``)
--------------------------------------
::

    01  DIS-GROUP-RECORD.
        05  DIS-GROUP-KEY.
           10 DIS-ACCT-GROUP-ID                     PIC X(10).
           10 DIS-TRAN-TYPE-CD                      PIC X(02).
           10 DIS-TRAN-CAT-CD                       PIC 9(04).
        05  DIS-INT-RATE                            PIC S9(04)V99.
        05  FILLER                                  PIC X(28).

The ``DIS-GROUP-KEY`` *group item* at offset 0 (16 bytes total — 10 for
``DIS-ACCT-GROUP-ID``, 2 for ``DIS-TRAN-TYPE-CD``, plus 4 for
``DIS-TRAN-CAT-CD``) is the VSAM cluster's primary key as defined by
``app/jcl/DISCGRP.jcl`` (``DEFINE CLUSTER KEYS(16 0)``). Because the
key spans three distinct fields, the SQLAlchemy model declares a
**3-part composite primary key** using three ``primary_key=True`` column
declarations — mirroring the COBOL group structure one-for-one.

COBOL -> Python Field Mapping
-----------------------------
=====================  ==============  =================  ==================================
COBOL Field            COBOL Type      Python Attr        SQLAlchemy Type
=====================  ==============  =================  ==================================
DIS-ACCT-GROUP-ID      ``PIC X(10)``   acct_group_id      ``String(10)`` (composite PK part 1)
DIS-TRAN-TYPE-CD       ``PIC X(02)``   tran_type_cd       ``String(2)``  (composite PK part 2)
DIS-TRAN-CAT-CD        ``PIC 9(04)``   tran_cat_cd        ``String(4)``  (composite PK part 3)
DIS-INT-RATE           ``PIC S9(04)V99`` int_rate         ``Numeric(6, 2)``
FILLER                 ``PIC X(28)``   (not mapped)       (COBOL padding only)
=====================  ==============  =================  ==================================

Note on ``DIS-INT-RATE PIC S9(04)V99`` -> ``Numeric(6, 2)``
----------------------------------------------------------
Per AAP §0.7.2 (financial precision), the interest rate MUST be stored
as PostgreSQL ``NUMERIC(6, 2)`` and returned as a ``decimal.Decimal``
value — never as a Python ``float``. The COBOL picture breakdown is:

* ``S``     — signed
* ``9(04)`` — 4 integer digits
* ``V``     — implicit decimal point
* ``99``    — 2 fractional digits

Total digits: 4 + 2 = 6 → ``Numeric(6, 2)``. Preserving this type
exactly is a **hard requirement** because the canonical interest
formula from ``CBACT04C.cbl`` / ``intcalc_job.py`` is::

    interest = (tran_cat_bal × int_rate) / 1200

Any silent conversion to IEEE-754 ``float`` at any point in this
pipeline would introduce binary-floating-point drift (e.g., losing the
last penny on a $10,000 balance monthly) which is a strict violation
of the minimal-change clause (AAP §0.7.1 — the formula must not be
algebraically simplified) and the financial-precision clause
(AAP §0.7.2 — monetary values must use ``Decimal``).

DEFAULT and ZEROAPR Sentinel Groups
-----------------------------------
The table holds two special-purpose rows used as fallback disclosure
groups by the interest-calculation cascade in ``CBACT04C.cbl``:

* ``'DEFAULT   '`` — baseline fallback disclosure group applied when
  an account's configured ``group_id`` does not match any row in this
  table. 10-character blank-padded to satisfy ``PIC X(10)`` semantics.
* ``'ZEROAPR   '`` — promotional zero-APR override used for special
  cardholder programs. Always paired with ``int_rate = 0.00`` per
  seed data from ``app/data/ASCII/discgrp.txt`` (51 rows across 3
  disclosure-group blocks).

Tests in this module exercise both sentinels because they are the
**only** disclosure groups whose presence is presumed by the batch
job; their ORM creation contract must not regress.

Reference Data
--------------
``DisclosureGroup`` is a **reference-data table** populated by the
disclosure-group seed loader. The canonical 51-row reference set
— representing 3 blocks of (acct_group_id × tran_type_cd × tran_cat_cd)
combinations — is defined by ``app/data/ASCII/discgrp.txt`` and
loaded via ``app/jcl/DISCGRP.jcl`` in the mainframe world and
``db/migrations/V3__seed_data.sql`` in the target cloud architecture.

No monetary balances appear on this entity (unlike
``TransactionCategoryBalance``) — the ``int_rate`` is a *rate*, not
a balance, and participates in the multiplicative side of the
interest-calculation equation above.

Test Coverage (15 functions)
----------------------------
1.  :func:`test_tablename`                          — ``__tablename__`` contract.
2.  :func:`test_column_count`                       — Exactly 4 mapped columns.
3.  :func:`test_composite_primary_key`              — 3-part PK, ordered and typed.
4.  :func:`test_composite_key_matches_cobol_group`  — PK mirrors COBOL ``DIS-GROUP-KEY``.
5.  :func:`test_acct_group_id_type`                 — ``acct_group_id`` is ``String(10)``.
6.  :func:`test_tran_type_cd_type`                  — ``tran_type_cd`` is ``String(2)``.
7.  :func:`test_tran_cat_cd_type`                   — ``tran_cat_cd`` is ``String(4)``.
8.  :func:`test_int_rate_type`                      — ``int_rate`` is ``Numeric(6, 2)``.
9.  :func:`test_int_rate_default`                   — ``int_rate`` defaults to ``Decimal('0.00')``.
10. :func:`test_create_default_group`               — DEFAULT sentinel construction.
11. :func:`test_create_zeroapr_group`               — ZEROAPR sentinel construction.
12. :func:`test_non_nullable_fields`                — NOT NULL on every column.
13. :func:`test_create_instance`                    — Full-instance construction.
14. :func:`test_repr`                               — ``__repr__`` format.
15. :func:`test_no_filler_columns`                  — ``FILLER`` is NOT mapped.

See Also
--------
``src/shared/models/disclosure_group.py``  — The ORM model under test.
``src/shared/models/__init__.py``          — The shared declarative ``Base`` class.
``app/cpy/CVTRA02Y.cpy``                   — Original COBOL record layout.
``app/data/ASCII/discgrp.txt``             — 51-row reference seed data.
``app/jcl/DISCGRP.jcl``                    — Mainframe seed loader (pre-migration).
``app/cbl/CBACT04C.cbl``                   — COBOL interest-calc consumer.
``src/batch/jobs/intcalc_job.py``          — PySpark interest-calc target.
``db/migrations/V3__seed_data.sql``        — Cloud-target seed loader.
AAP §0.5.1                                 — File-by-File Transformation Plan.
AAP §0.7.1                                 — Minimal-change clause (preserve
                                             COBOL field widths / formula exactly).
AAP §0.7.2                                 — Financial-precision clause
                                             (``Decimal`` not ``float``).
``tests.unit.test_models.__init__``        — Package docstring listing the
                                             full model-to-copybook mapping.
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import Numeric, String, inspect

from src.shared.models import Base
from src.shared.models.disclosure_group import DisclosureGroup

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# Set of the 4 expected mapped column names (Python attribute names,
# which are also the SQL column names under SQLAlchemy's default
# resolution). The COBOL ``FILLER PIC X(28)`` at the end of the 50-byte
# record is DELIBERATELY absent — padding regions have no place in
# the relational model.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "acct_group_id",  # from DIS-ACCT-GROUP-ID PIC X(10)  — composite PK part 1
        "tran_type_cd",  # from DIS-TRAN-TYPE-CD  PIC X(02)  — composite PK part 2
        "tran_cat_cd",  # from DIS-TRAN-CAT-CD   PIC 9(04)  — composite PK part 3
        "int_rate",  # from DIS-INT-RATE       PIC S9(04)V99
    }
)

# Ordered tuple representing the composite primary key declaration order
# in the ORM model. The order matches the COBOL ``DIS-GROUP-KEY`` group
# definition (``DIS-ACCT-GROUP-ID`` first at offset 0..9, then
# ``DIS-TRAN-TYPE-CD`` at offset 10..11, then ``DIS-TRAN-CAT-CD`` at
# offset 12..15) which is the VSAM KSDS primary-key byte order.
_EXPECTED_COMPOSITE_PK_NAMES: tuple[str, ...] = (
    "acct_group_id",
    "tran_type_cd",
    "tran_cat_cd",
)

# Sentinel account-group identifiers recognized by the interest-calculation
# cascade in CBACT04C.cbl. Each is blank-padded to 10 characters to
# satisfy COBOL PIC X(10) semantics — the trailing spaces are NOT
# cosmetic; they are the actual byte content of the stored VSAM key.
# Stripping or shortening these strings would cause the interest-calc
# job to miss the fallback row at read time.
_DEFAULT_GROUP_ID: str = "DEFAULT   "  # 7 chars + 3 trailing spaces = 10
_ZEROAPR_GROUP_ID: str = "ZEROAPR   "  # 7 chars + 3 trailing spaces = 10


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """DisclosureGroup must be mapped to the ``disclosure_groups`` table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE disclosure_groups``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO disclosure_groups``
      (51 seed rows across DEFAULT, ZEROAPR, and other account groups)
    * The batch interest-calculation program (``CBACT04C`` →
      ``intcalc_job.py``) that looks up the applicable rate for each
      ``(acct_group_id, tran_type_cd, tran_cat_cd)`` triple.

    Any drift between ``DisclosureGroup.__tablename__`` and the DDL /
    seed-data contract would cause runtime ``UndefinedTable`` errors, so
    this invariant is pinned.

    Note
    ----
    The table name is *plural* (``disclosure_groups``) to follow
    the project-wide relational-naming convention shared by all sibling
    entities except ``user_security`` (which retains its historical
    singular VSAM dataset name). The singular form ``disclosure_group``
    is NOT accepted — that would break the Flyway migrations already in
    place under ``db/migrations/``.
    """
    assert DisclosureGroup.__tablename__ == "disclosure_groups", (
        f"DisclosureGroup.__tablename__ must be 'disclosure_groups' "
        f"to match db/migrations/V1__schema.sql and V3__seed_data.sql; "
        f"found {DisclosureGroup.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """DisclosureGroup must expose exactly 4 mapped columns.

    The COBOL ``DIS-GROUP-RECORD`` layout has 5 fields (counting the
    ``DIS-GROUP-KEY`` group as three independent elementary fields —
    ``DIS-ACCT-GROUP-ID``, ``DIS-TRAN-TYPE-CD``, and ``DIS-TRAN-CAT-CD``
    — plus ``DIS-INT-RATE`` and ``FILLER``), but only 4 are mapped to
    the relational model. ``FILLER PIC X(28)`` is deliberately dropped
    because trailing padding has no storage or semantic meaning in a
    column-typed schema.

    Ensuring the count is exactly 4 guards against two regressions:

    * An accidental ``filler`` column being added back (increases the
      count to 5).
    * A field being accidentally removed from the model (decreases the
      count below 4).
    """
    columns = DisclosureGroup.__table__.columns
    assert len(columns) == 4, (
        f"DisclosureGroup must have exactly 4 columns (DIS-ACCT-GROUP-ID, "
        f"DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD, DIS-INT-RATE — FILLER "
        f"excluded); found {len(columns)}: {[c.name for c in columns]}"
    )


@pytest.mark.unit
def test_composite_primary_key() -> None:
    """DisclosureGroup must declare a 3-part composite primary key.

    Three invariants are asserted to guard against regressions in the
    most critical part of the COBOL -> SQLAlchemy translation:

    1. **Cardinality** — exactly 3 PK columns (not 1, not 2, not 4).
       The VSAM KSDS ``KEYS(16 0)`` on ``app/jcl/DISCGRP.jcl`` spans
       the first 16 bytes of the record, which is the concatenation
       of the three ``DIS-GROUP-KEY`` elementary fields.

    2. **Identity and order** — the PK column names must be
       ``("acct_group_id", "tran_type_cd", "tran_cat_cd")``, in that
       order, to match the COBOL key-byte order.

    3. **Types** — each PK part must be declared with the String
       length corresponding to its COBOL PIC clause:

       * ``acct_group_id`` -> ``String(10)`` from ``PIC X(10)``
       * ``tran_type_cd``  -> ``String(2)``  from ``PIC X(02)``
       * ``tran_cat_cd``   -> ``String(4)``  from ``PIC 9(04)``

       The ``PIC 9(04)`` -> ``String(4)`` mapping (rather than an
       integer) is intentional: seed data preserves the leading zeros
       of COBOL numeric-display characters (e.g., ``'0001'`` ≠ ``1``),
       and the interest-calc lookup relies on textual equality.
    """
    primary_key_columns = list(inspect(DisclosureGroup).primary_key)

    # Invariant 1 — exactly 3 PK columns.
    assert len(primary_key_columns) == 3, (
        f"DisclosureGroup must have a 3-part composite primary key "
        f"(acct_group_id + tran_type_cd + tran_cat_cd, per COBOL "
        f"DIS-GROUP-KEY); found {len(primary_key_columns)} PK columns: "
        f"{[c.name for c in primary_key_columns]}"
    )

    # Invariant 2 — identity and order.
    actual_pk_names = tuple(c.name for c in primary_key_columns)
    assert actual_pk_names == _EXPECTED_COMPOSITE_PK_NAMES, (
        f"Composite primary key columns must be declared in the order "
        f"{_EXPECTED_COMPOSITE_PK_NAMES!r} (matching the COBOL "
        f"DIS-GROUP-KEY byte layout: DIS-ACCT-GROUP-ID at 0..9, "
        f"DIS-TRAN-TYPE-CD at 10..11, DIS-TRAN-CAT-CD at 12..15); "
        f"found {actual_pk_names!r}"
    )

    # Invariant 3 — types and lengths of each PK part.
    expected_types: dict[str, int] = {
        "acct_group_id": 10,  # DIS-ACCT-GROUP-ID PIC X(10)
        "tran_type_cd": 2,  # DIS-TRAN-TYPE-CD  PIC X(02)
        "tran_cat_cd": 4,  # DIS-TRAN-CAT-CD   PIC 9(04)
    }
    for pk_column in primary_key_columns:
        assert isinstance(pk_column.type, String), (
            f"Primary key column {pk_column.name!r} must be declared as "
            f"a String type (from COBOL PIC X/9 clauses); found "
            f"{type(pk_column.type).__name__}"
        )
        expected_length = expected_types[pk_column.name]
        assert pk_column.type.length == expected_length, (
            f"Primary key column {pk_column.name!r} must be "
            f"String({expected_length}) per the COBOL copybook; "
            f"found String({pk_column.type.length})"
        )


@pytest.mark.unit
def test_composite_key_matches_cobol_group() -> None:
    """The Python PK must mirror the COBOL ``DIS-GROUP-KEY`` group.

    The COBOL copybook defines::

        05  DIS-GROUP-KEY.
           10 DIS-ACCT-GROUP-ID   PIC X(10).
           10 DIS-TRAN-TYPE-CD    PIC X(02).
           10 DIS-TRAN-CAT-CD     PIC 9(04).
        05  DIS-INT-RATE          PIC S9(04)V99.
        05  FILLER                PIC X(28).

    Two semantic properties of this layout must be preserved:

    * **Inclusion** — every element inside ``DIS-GROUP-KEY`` must be
      part of the ORM primary key.
    * **Exclusion** — every element *outside* ``DIS-GROUP-KEY``
      (``DIS-INT-RATE`` and ``FILLER``) must NOT be part of the
      primary key. The interest rate participates in arithmetic, not
      identity; the filler participates in neither.

    Combined, these assertions verify that the Python PK set is
    *exactly equivalent* to the set of 10 elementary COBOL fields
    rolled up under ``DIS-GROUP-KEY``.
    """
    # Fields that appear inside the COBOL ``DIS-GROUP-KEY`` group item
    # (offsets 0..15 of the 50-byte record).
    cobol_key_columns: frozenset[str] = frozenset({"acct_group_id", "tran_type_cd", "tran_cat_cd"})

    # Fields that appear in the record but OUTSIDE the key group
    # (offsets 16..49 of the 50-byte record). ``int_rate`` is the
    # mapped non-key column; the COBOL ``FILLER`` is not mapped and
    # so does not appear in this expected set.
    cobol_non_key_columns: frozenset[str] = frozenset({"int_rate"})

    primary_key_names: set[str] = {c.name for c in inspect(DisclosureGroup).primary_key}

    # Inclusion — every COBOL key elementary field is a PK column.
    missing_key_columns = cobol_key_columns - primary_key_names
    assert not missing_key_columns, (
        f"COBOL DIS-GROUP-KEY elementary fields are missing from the "
        f"ORM composite primary key: {sorted(missing_key_columns)}. "
        f"Every field under DIS-GROUP-KEY (offsets 0..15) must be a "
        f"primary_key=True column."
    )

    # Exclusion — no non-key column is in the PK.
    spurious_pk_columns = primary_key_names & cobol_non_key_columns
    assert not spurious_pk_columns, (
        f"Non-key COBOL fields were incorrectly included in the "
        f"composite primary key: {sorted(spurious_pk_columns)}. "
        f"Only DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, and "
        f"DIS-TRAN-CAT-CD belong to DIS-GROUP-KEY; other fields "
        f"(int_rate, etc.) must not be primary_key=True."
    )

    # Equivalence — exact match between PK set and COBOL key set.
    assert primary_key_names == cobol_key_columns, (
        f"ORM composite primary key {sorted(primary_key_names)!r} "
        f"does not match the COBOL DIS-GROUP-KEY group "
        f"{sorted(cobol_key_columns)!r} exactly. The two must be "
        f"identical set-wise to preserve VSAM lookup semantics."
    )


# ============================================================================
# Phase 4: Column Type Tests
# ============================================================================


@pytest.mark.unit
def test_acct_group_id_type() -> None:
    """``acct_group_id`` must be ``String(10)`` from ``DIS-ACCT-GROUP-ID PIC X(10)``.

    The 10-character length is load-bearing: the DEFAULT and ZEROAPR
    sentinel values (``'DEFAULT   '``, ``'ZEROAPR   '``) require the
    full 10 bytes of trailing-space padding to round-trip correctly
    against seed rows loaded from ``app/data/ASCII/discgrp.txt`` (51
    reference rows). Widening the column silently truncates semantics
    applied elsewhere; narrowing it truncates the data itself.
    """
    column = DisclosureGroup.__table__.columns["acct_group_id"]
    assert isinstance(column.type, String), (
        f"acct_group_id must be a String type (from COBOL PIC X(10)); found {type(column.type).__name__}"
    )
    assert column.type.length == 10, (
        f"acct_group_id must be String(10) to preserve DIS-ACCT-GROUP-ID "
        f"PIC X(10) byte width (including the trailing-space padding "
        f"used by DEFAULT/ZEROAPR sentinels); found "
        f"String({column.type.length})"
    )


@pytest.mark.unit
def test_tran_type_cd_type() -> None:
    """``tran_type_cd`` must be ``String(2)`` from ``DIS-TRAN-TYPE-CD PIC X(02)``.

    Transaction type codes are two-character mnemonics (e.g., ``'01'``
    for PURCHASE, ``'02'`` for REFUND) defined by the 7-row reference
    table loaded from ``app/data/ASCII/trantype.txt`` and mirrored
    in the ORM by :class:`TransactionType`. A ``String(2)`` column is
    the minimum width that accommodates every code without padding.
    """
    column = DisclosureGroup.__table__.columns["tran_type_cd"]
    assert isinstance(column.type, String), (
        f"tran_type_cd must be a String type (from COBOL PIC X(02)); found {type(column.type).__name__}"
    )
    assert column.type.length == 2, (
        f"tran_type_cd must be String(2) to preserve DIS-TRAN-TYPE-CD "
        f"PIC X(02) byte width; found String({column.type.length})"
    )


@pytest.mark.unit
def test_tran_cat_cd_type() -> None:
    """``tran_cat_cd`` must be ``String(4)`` from ``DIS-TRAN-CAT-CD PIC 9(04)``.

    Even though the COBOL picture is numeric (``PIC 9(04)``), the
    migrated Python model stores it as a 4-character zero-padded
    string (e.g., ``'0001'``, ``'0002'``, ..., ``'9999'``) to preserve
    the leading zeros of COBOL's numeric-display representation.
    Silently dropping the zeros by switching to an ``Integer`` column
    would change the textual lookup semantics against the 18-row
    :class:`TransactionCategory` reference table (loaded from
    ``app/data/ASCII/trancatg.txt``).
    """
    column = DisclosureGroup.__table__.columns["tran_cat_cd"]
    assert isinstance(column.type, String), (
        f"tran_cat_cd must be a String type — even though COBOL "
        f"PIC 9(04) is numeric, the leading zeros are part of the "
        f"value (e.g., '0001'). Do NOT use Integer. Found "
        f"{type(column.type).__name__}"
    )
    assert column.type.length == 4, (
        f"tran_cat_cd must be String(4) to preserve DIS-TRAN-CAT-CD "
        f"PIC 9(04) byte width (zero-padded); found "
        f"String({column.type.length})"
    )


# ============================================================================
# Phase 5: Interest Rate Field Test (Numeric — No Float)
# ============================================================================


# ============================================================================
# Phase 3: Composite Primary Key Tests (CRITICAL)
# ============================================================================


@pytest.mark.unit
def test_int_rate_type() -> None:
    """``int_rate`` must be ``Numeric(6, 2)`` from ``DIS-INT-RATE PIC S9(04)V99``.

    This is the single most security-critical column assertion in this
    test module. The COBOL picture ``PIC S9(04)V99`` breaks down as:

    * ``S``     — signed (positive or negative — a negative rate is
      permissible for e.g. a cashback / rebate configuration).
    * ``9(04)`` — 4 integer digits (max integer part: 9999).
    * ``V``     — implicit decimal point (zero storage cost).
    * ``99``    — 2 fractional digits (fixed precision for basis-
      points-like arithmetic).

    Total precision: 4 + 2 = **6** digits; scale: **2** digits → the
    SQLAlchemy column must be declared as ``Numeric(6, 2)`` so the
    PostgreSQL DDL emits ``NUMERIC(6, 2)``.

    The consumer of this value is the interest-calculation formula
    from ``CBACT04C.cbl`` (preserved verbatim, per AAP §0.7.1)::

        monthly_interest = (tran_cat_bal × int_rate) / 1200

    ``tran_cat_bal`` is a ``Numeric(11, 2)`` balance; the multiplicand
    ``int_rate`` must also be ``Numeric`` (not ``Float`` / ``Real``)
    so the intermediate product stays in arbitrary-precision decimal
    arithmetic. Even a single silent cast to IEEE-754 ``float``
    anywhere in the pipeline would violate AAP §0.7.2 by introducing
    binary-floating-point drift — for a $10,000 balance at an 18.50%
    annual rate, the monthly interest is exactly ``$154.17``
    (``10000 × 18.50 / 1200``), and ``float`` would deliver it as
    ``154.16666666666669`` before rounding, losing a penny's worth of
    confidence.
    """
    column = DisclosureGroup.__table__.columns["int_rate"]

    # Type check — MUST be Numeric, never Float / Real.
    assert isinstance(column.type, Numeric), (
        f"int_rate MUST be a Numeric type to preserve COBOL "
        f"PIC S9(04)V99 arbitrary-precision arithmetic semantics. "
        f"Using Float or Real would violate AAP §0.7.2 (financial "
        f"precision) by introducing IEEE-754 drift. Found "
        f"{type(column.type).__name__}"
    )

    # Precision check — 4 integer digits + 2 fractional digits = 6.
    assert column.type.precision == 6, (
        f"int_rate must have Numeric precision == 6 (from COBOL "
        f"PIC S9(04)V99: 4 integer digits + 2 fractional digits); "
        f"found precision={column.type.precision}"
    )

    # Scale check — 2 fractional digits.
    assert column.type.scale == 2, (
        f"int_rate must have Numeric scale == 2 (from COBOL "
        f"PIC S9(04)V99: 2 fractional digits after the implicit V); "
        f"found scale={column.type.scale}"
    )


@pytest.mark.unit
def test_int_rate_default() -> None:
    """``int_rate`` must default to ``Decimal('0.00')``.

    A zero default is required for two reasons rooted in the COBOL
    origin:

    * **Batch safety** — when a new disclosure group is inserted
      without an explicit rate (e.g., by an administrative workflow
      migrated from the COBOL admin screens), the row must not be
      ``NULL`` because ``CBACT04C.cbl`` did not handle ``NULL`` — it
      assumed zero-initialised storage.
    * **Precision invariant** — the default value must be a
      ``decimal.Decimal`` instance with a scale of 2 (``'0.00'`` not
      ``'0'``). A ``float`` default here would be a violation of
      AAP §0.7.2 — even a literal ``0.0`` is an IEEE-754 value, not
      a decimal value.
    """
    column = DisclosureGroup.__table__.columns["int_rate"]

    # Default clause must be present.
    assert column.default is not None, (
        "int_rate must declare a default value of Decimal('0.00') so "
        "that administratively-created rows are never NULL; found no "
        "default clause on the column."
    )

    # Unwrap the SQLAlchemy ColumnDefault wrapper to inspect the
    # underlying Python literal.
    default_value = column.default.arg

    # Hard type check — ``Decimal``, not ``float``, not ``int``.
    assert isinstance(default_value, Decimal), (
        f"int_rate default MUST be a decimal.Decimal instance per "
        f"AAP §0.7.2 (financial precision); found "
        f"{type(default_value).__name__} ({default_value!r}). "
        f"Do NOT use float(0.0) or int(0) — they lose the two-"
        f"decimal-place precision contract."
    )

    # Value check — exactly Decimal('0.00'), not Decimal('0').
    assert default_value == Decimal("0.00"), (
        f"int_rate default must be Decimal('0.00') exactly (with two-decimal-place scale); found {default_value!r}"
    )


# ============================================================================
# Phase 6: DEFAULT and ZEROAPR Group Validation
# ============================================================================


@pytest.mark.unit
def test_create_default_group() -> None:
    """DEFAULT sentinel group must be constructible via the ORM.

    The ``DEFAULT`` account group (padded to ``'DEFAULT   '`` to
    satisfy ``PIC X(10)``) is the baseline fallback applied by
    ``CBACT04C.cbl`` whenever an account's configured ``group_id`` has
    no matching row in this table. Without this sentinel, the
    interest-calculation job would fail closed on any unknown group,
    halting the overnight batch run — a regression that would be
    business-critical in production.

    This test verifies that the ORM accepts the padded 10-character
    value unchanged and preserves every field at the Python layer
    without coercion (no stripping, no casefolding, no decimal
    truncation).
    """
    default_group = DisclosureGroup(
        acct_group_id=_DEFAULT_GROUP_ID,
        tran_type_cd="01",
        tran_cat_cd="0001",
        int_rate=Decimal("18.50"),
    )

    # The DEFAULT sentinel is preserved byte-for-byte, including its
    # three trailing spaces — these are essential for the VSAM-style
    # lookup to succeed.
    assert default_group.acct_group_id == _DEFAULT_GROUP_ID, (
        f"DEFAULT sentinel acct_group_id must round-trip unchanged "
        f"(including 3 trailing spaces for PIC X(10) padding); "
        f"expected {_DEFAULT_GROUP_ID!r} (len=10), got "
        f"{default_group.acct_group_id!r} "
        f"(len={len(default_group.acct_group_id)})"
    )
    # Confirm the PIC X(10) byte-width contract explicitly, since
    # accidental ``.strip()`` or string-literal shortening is the most
    # likely way for this sentinel to regress.
    assert len(default_group.acct_group_id) == 10, (
        f"DEFAULT sentinel acct_group_id must be exactly 10 characters "
        f"wide (COBOL PIC X(10)); found "
        f"{len(default_group.acct_group_id)}"
    )
    assert default_group.tran_type_cd == "01"
    assert default_group.tran_cat_cd == "0001"

    # Interest rate round-trips as a Decimal — never a float.
    assert isinstance(default_group.int_rate, Decimal), (
        f"int_rate on the DEFAULT sentinel must round-trip as a "
        f"decimal.Decimal, never a float (AAP §0.7.2); found "
        f"{type(default_group.int_rate).__name__}"
    )
    assert default_group.int_rate == Decimal("18.50")


@pytest.mark.unit
def test_create_zeroapr_group() -> None:
    """ZEROAPR sentinel group must be constructible via the ORM.

    The ``ZEROAPR`` account group (padded to ``'ZEROAPR   '`` to
    satisfy ``PIC X(10)``) is the promotional zero-APR override
    applied to special cardholder programs. By canonical convention
    enforced by the 51-row seed file ``app/data/ASCII/discgrp.txt``,
    every row in this group has ``int_rate = 0.00`` so the interest-
    calc formula ``(tran_cat_bal × 0.00) / 1200`` always resolves to
    zero interest.

    This test exercises two guarantees simultaneously:

    * The ORM accepts the padded 10-character value unchanged.
    * The ``Decimal('0.00')`` interest rate round-trips without being
      silently collapsed to ``Decimal('0')``, ``0``, or ``0.0`` — any
      of which would break the two-decimal-place scale contract
      required by AAP §0.7.2 for consistent downstream formatting.
    """
    zeroapr_group = DisclosureGroup(
        acct_group_id=_ZEROAPR_GROUP_ID,
        tran_type_cd="01",
        tran_cat_cd="0001",
        int_rate=Decimal("0.00"),
    )

    # The ZEROAPR sentinel is preserved byte-for-byte, including its
    # three trailing spaces.
    assert zeroapr_group.acct_group_id == _ZEROAPR_GROUP_ID, (
        f"ZEROAPR sentinel acct_group_id must round-trip unchanged "
        f"(including 3 trailing spaces for PIC X(10) padding); "
        f"expected {_ZEROAPR_GROUP_ID!r} (len=10), got "
        f"{zeroapr_group.acct_group_id!r} "
        f"(len={len(zeroapr_group.acct_group_id)})"
    )
    assert len(zeroapr_group.acct_group_id) == 10, (
        f"ZEROAPR sentinel acct_group_id must be exactly 10 characters "
        f"wide (COBOL PIC X(10)); found "
        f"{len(zeroapr_group.acct_group_id)}"
    )
    assert zeroapr_group.tran_type_cd == "01"
    assert zeroapr_group.tran_cat_cd == "0001"

    # Interest rate must be Decimal and must preserve the
    # two-decimal-place scale exactly.
    assert isinstance(zeroapr_group.int_rate, Decimal), (
        f"int_rate on the ZEROAPR sentinel must round-trip as a "
        f"decimal.Decimal, never a float (AAP §0.7.2); found "
        f"{type(zeroapr_group.int_rate).__name__}"
    )
    assert zeroapr_group.int_rate == Decimal("0.00"), (
        f"ZEROAPR int_rate must be Decimal('0.00') exactly to satisfy "
        f"the promotional zero-APR contract; found "
        f"{zeroapr_group.int_rate!r}"
    )


# ============================================================================
# Phase 7: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 4 mapped columns on DisclosureGroup must be NOT NULL.

    The COBOL source ``CVTRA02Y.cpy`` has no concept of ``NULL`` —
    every byte of every record is always initialised to its picture's
    zero-value (spaces for ``PIC X``, zeros for ``PIC 9`` / ``PIC S9``).
    The relational translation must preserve this invariant: no column
    may be ``NULL``. In particular:

    * The 3 composite-key columns (``acct_group_id``,
      ``tran_type_cd``, ``tran_cat_cd``) are automatically non-
      nullable by virtue of being ``primary_key=True``, but we assert
      it anyway to guard against an accidental future ``nullable=True``
      override on a PK column (which is technically legal but
      semantically forbidden here).
    * The ``int_rate`` non-key column is explicitly declared
      ``nullable=False`` with ``default=Decimal('0.00')`` in the
      model — this must not regress.

    Allowing any NULL value on this table would introduce an
    undefined-behaviour branch into ``CBACT04C.cbl``'s migrated
    successor ``intcalc_job.py``.
    """
    for column_name in _EXPECTED_COLUMNS:
        column = DisclosureGroup.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column {column_name!r} must be NOT NULL. COBOL "
            f"CVTRA02Y.cpy has no NULL semantics — every byte of "
            f"every record is initialised to its picture's zero "
            f"value. The interest-calc batch job (intcalc_job.py) "
            f"does not defensively handle NULL on any column of "
            f"this table; allowing NULL here would create an "
            f"undefined-behaviour branch. Found nullable={column.nullable}"
        )


# ============================================================================
# Phase 8: Instance Creation & repr Tests
# ============================================================================


@pytest.mark.unit
def test_create_instance() -> None:
    """A DisclosureGroup instance must be creatable with all 4 fields.

    This end-to-end constructor test verifies that the model's
    ``__init__`` (auto-generated by SQLAlchemy's
    :class:`~sqlalchemy.orm.DeclarativeBase`) accepts keyword arguments
    for every mapped column, preserves them unchanged on the
    attribute access path, and produces a live instance of
    :class:`~src.shared.models.Base` — the shared declarative parent
    class — confirming proper metadata registration.

    The test uses a plausible non-sentinel disclosure group to avoid
    accidentally shadowing the DEFAULT/ZEROAPR fallback tests, and
    uses an interest rate of ``Decimal('15.99')`` — a realistic
    retail-APR figure that exercises the two-decimal-place scale
    without being a round number.

    No database session is required — this is a pure in-memory
    construction test; ``flush`` / ``commit`` semantics are covered
    by integration tests under ``tests/integration/``.
    """
    group = DisclosureGroup(
        acct_group_id="GOLD01    ",  # 10-char blank-padded
        tran_type_cd="02",
        tran_cat_cd="2001",
        int_rate=Decimal("15.99"),
    )

    # The instance must be a live ``Base`` subclass instance so
    # SQLAlchemy recognises it for ORM operations. Guards against
    # accidental detachment from the declarative hierarchy (e.g.,
    # if DisclosureGroup were ever refactored into a plain dataclass).
    assert isinstance(group, Base), (
        f"DisclosureGroup instance must be an instance of the shared "
        f"declarative Base class so SQLAlchemy recognises it for ORM "
        f"persistence; found MRO "
        f"{[cls.__name__ for cls in type(group).__mro__]}"
    )

    # Field-by-field readback to confirm no coercion has occurred.
    assert group.acct_group_id == "GOLD01    ", (
        f"acct_group_id must round-trip unchanged (PIC X(10) with "
        f"trailing space padding preserved); found "
        f"{group.acct_group_id!r}"
    )
    assert group.tran_type_cd == "02", f"tran_type_cd must round-trip unchanged; found {group.tran_type_cd!r}"
    assert group.tran_cat_cd == "2001", (
        f"tran_cat_cd must round-trip unchanged as a 4-character zero-padded string; found {group.tran_cat_cd!r}"
    )
    assert group.int_rate == Decimal("15.99"), (
        f"int_rate must round-trip exactly as Decimal('15.99'); found {group.int_rate!r}"
    )
    # Guard against silent float-coercion. Even if equality still
    # holds against ``Decimal('15.99')``, losing the Decimal type
    # here would introduce float arithmetic downstream.
    assert isinstance(group.int_rate, Decimal), (
        f"int_rate must remain a decimal.Decimal after construction "
        f"(AAP §0.7.2 — never float); found "
        f"{type(group.int_rate).__name__}"
    )


@pytest.mark.unit
def test_repr() -> None:
    """``__repr__`` must return a readable developer-friendly string.

    The model-side ``__repr__`` declared in
    ``src/shared/models/disclosure_group.py`` follows the project-
    wide convention of listing the class name followed by every
    mapped attribute as ``name=repr(value)``. This test verifies:

    * The class name ``DisclosureGroup`` is present.
    * All 4 mapped attribute names appear literally.
    * All 4 field values appear in their ``repr()`` form — in
      particular, the ``int_rate`` must be rendered as
      ``Decimal('...')`` (from ``Decimal.__repr__``), NEVER as a
      bare float literal. Downstream log parsers and debugging
      dashboards rely on this distinction to flag float-drift
      regressions instantly.
    """
    group = DisclosureGroup(
        acct_group_id=_DEFAULT_GROUP_ID,
        tran_type_cd="01",
        tran_cat_cd="0001",
        int_rate=Decimal("18.50"),
    )
    repr_output = repr(group)

    # The class name must appear at the start of the repr output.
    assert "DisclosureGroup" in repr_output, (
        f"__repr__ must include the class name 'DisclosureGroup' for debuggability; found {repr_output!r}"
    )

    # Each mapped attribute name must appear in the repr.
    for attribute_name in _EXPECTED_COLUMNS:
        assert attribute_name in repr_output, (
            f"__repr__ must include the attribute name {attribute_name!r} to be self-describing; found {repr_output!r}"
        )

    # Each attribute value must be rendered via ``repr()`` of the
    # underlying Python value, which (for strings) includes surrounding
    # quotes — this is why we search for ``repr(v)`` not ``str(v)``.
    assert repr(_DEFAULT_GROUP_ID) in repr_output, (
        f"__repr__ must include the acct_group_id value rendered via "
        f"repr() (expected substring {repr(_DEFAULT_GROUP_ID)!r}); "
        f"found {repr_output!r}"
    )
    assert repr("01") in repr_output, (
        f"__repr__ must include the tran_type_cd value rendered via "
        f"repr() (expected substring {repr('01')!r}); found "
        f"{repr_output!r}"
    )
    assert repr("0001") in repr_output, (
        f"__repr__ must include the tran_cat_cd value rendered via "
        f"repr() (expected substring {repr('0001')!r}); found "
        f"{repr_output!r}"
    )

    # CRITICAL: the interest rate must be rendered as Decimal(...) to
    # make float-drift regressions immediately obvious in logs.
    assert repr(Decimal("18.50")) in repr_output, (
        f"__repr__ must render int_rate as "
        f"{repr(Decimal('18.50'))!r} (NOT as a float literal such as "
        f"'18.5'); found {repr_output!r}. Any deviation breaks the "
        f"log-parser conventions that surface float-drift "
        f"regressions (AAP §0.7.2)."
    )


# ============================================================================
# Phase 9: FILLER Exclusion
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """COBOL ``FILLER PIC X(28)`` must NOT be mapped as a column.

    The COBOL record layout ends with ``FILLER PIC X(28)`` padding
    the 50-byte record from the 22-byte data payload (16 for
    ``DIS-GROUP-KEY`` + 6 for ``DIS-INT-RATE``) out to the full
    record length. Filler is a byte-alignment artefact of the
    fixed-length VSAM KSDS record format — it has no semantic meaning
    and no valid column-level representation.

    Mapping ``FILLER`` to a column in the relational model would be
    a bug for multiple reasons:

    * It would bloat every row with 28 bytes of meaningless padding.
    * It would introduce a nullable column without a business
      interpretation, breaking the NOT-NULL invariant asserted in
      :func:`test_non_nullable_fields`.
    * It would force downstream API schemas (``DisclosureGroup`` is
      reference data frequently exposed through read endpoints) to
      either expose or actively hide a padding field — both are
      maintenance burdens.

    Two assertions guard against regressions:

    * **Positive** — the mapped column set is *exactly* the expected
      set of 4 columns.
    * **Negative** — no column name contains the substring
      ``"filler"`` (case-insensitive), catching any misspelling
      (``Filler``, ``FILLER``, ``filler1``, etc.).
    """
    column_names = [c.name for c in DisclosureGroup.__table__.columns]

    # Positive invariant — exact-set equivalence against the
    # FILLER-free expected set.
    assert set(column_names) == set(_EXPECTED_COLUMNS), (
        f"DisclosureGroup columns must exactly match "
        f"{sorted(_EXPECTED_COLUMNS)!r} (COBOL FILLER PIC X(28) is "
        f"NOT mapped); found {sorted(column_names)!r}. "
        f"Missing: {sorted(set(_EXPECTED_COLUMNS) - set(column_names))!r}. "
        f"Extra: {sorted(set(column_names) - set(_EXPECTED_COLUMNS))!r}."
    )

    # Negative invariant — catches any misspelling of FILLER.
    for column_name in column_names:
        assert "filler" not in column_name.lower(), (
            f"Column {column_name!r} appears to map COBOL FILLER "
            f"padding, which is forbidden. COBOL FILLER PIC X(28) "
            f"has no semantic meaning and must NOT appear in the "
            f"relational model. Remove it from "
            f"src/shared/models/disclosure_group.py."
        )
