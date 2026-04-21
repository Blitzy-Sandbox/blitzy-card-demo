# ============================================================================
# Source: COBOL copybook CVTRA04Y.cpy — TRAN-CAT-RECORD (RECLN 60, composite key 6)
# ============================================================================
# Tests validate 2-part composite PK and category description.
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
"""Unit tests for the :class:`TransactionCategory` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CVTRA04Y.cpy`` (record layout ``TRAN-CAT-RECORD``, a 60-byte
VSAM KSDS record with a 6-byte composite key) into the SQLAlchemy 2.x
declarative ORM model at ``src/shared/models/transaction_category.py``.

COBOL Source Layout (``CVTRA04Y.cpy``)
--------------------------------------
::

    01  TRAN-CAT-RECORD.
        05  TRAN-CAT-KEY.
           10  TRAN-TYPE-CD                         PIC X(02).
           10  TRAN-CAT-CD                          PIC 9(04).
        05  TRAN-CAT-TYPE-DESC                      PIC X(50).
        05  FILLER                                  PIC X(04).

The ``TRAN-CAT-KEY`` *group item* at offset 0 (6 bytes total — 2 for
``TRAN-TYPE-CD`` plus 4 for ``TRAN-CAT-CD``) is the VSAM cluster's
primary key as defined by ``app/jcl/TCATBALF.jcl`` /
``app/jcl/TRANCATG.jcl`` (``DEFINE CLUSTER KEYS(6 0)``). Because the
key spans two distinct fields, the SQLAlchemy model declares a
**2-part composite primary key** using two ``primary_key=True`` column
declarations — mirroring the COBOL group structure one-for-one.

COBOL -> Python Field Mapping
-----------------------------
=====================  ==============  =============  =============================
COBOL Field            COBOL Type      Python Attr    SQLAlchemy Type
=====================  ==============  =============  =============================
TRAN-TYPE-CD           ``PIC X(02)``   type_cd        ``String(2)`` (composite PK)
TRAN-CAT-CD            ``PIC 9(04)``   cat_cd         ``String(4)`` (composite PK)
TRAN-CAT-TYPE-DESC     ``PIC X(50)``   description    ``String(50)``
FILLER                 ``PIC X(04)``   (not mapped)   (COBOL padding only)
=====================  ==============  =============  =============================

Note on ``TRAN-CAT-CD PIC 9(04)`` -> ``String(4)``
--------------------------------------------------
Although ``PIC 9(04)`` is a numeric COBOL type, the field is
deliberately mapped to ``String(4)`` rather than ``Integer`` to
**preserve leading zeros** (e.g., ``'0001'`` rather than ``1``). This
matches the storage convention used by the composite-key joins from
``DisclosureGroup.tran_cat_cd`` and
``TransactionCategoryBalance.tran_cat_cd``, and also mirrors the seed
data format in ``app/data/ASCII/trancatg.txt`` where category codes
appear as zero-padded 4-character strings. Any drift away from
``String(4)`` would break the foreign-key-in-fact relationships with
those sibling tables and silently mangle the 18 reference rows loaded
by ``db/migrations/V3__seed_data.sql``.

Reference Data
--------------
``TransactionCategory`` is a **reference-data table** populated by
the transaction-category seed loader. The canonical 18-row reference
set — representing the cartesian combinations of 7 transaction types
(``01``..``07``) crossed with the categories available for each type
— is defined by ``app/data/ASCII/trancatg.txt`` and loaded via
``app/jcl/TRANCATG.jcl`` in the mainframe world and
``db/migrations/V3__seed_data.sql`` in the target cloud architecture.

No monetary / financial fields appear on this entity (unlike
``TransactionCategoryBalance``) — it is purely a descriptive lookup.

Test Coverage (11 functions)
----------------------------
1.  :func:`test_tablename`                          — ``__tablename__`` contract.
2.  :func:`test_column_count`                       — Exactly 3 mapped columns.
3.  :func:`test_composite_primary_key`              — 2-part PK, ordered and typed.
4.  :func:`test_composite_key_matches_cobol_group`  — PK mirrors COBOL ``TRAN-CAT-KEY``.
5.  :func:`test_type_cd_type`                       — ``type_cd`` is ``String(2)``.
6.  :func:`test_cat_cd_type`                        — ``cat_cd`` is ``String(4)``.
7.  :func:`test_description_type`                   — ``description`` is ``String(50)``.
8.  :func:`test_non_nullable_fields`                — NOT NULL on every column.
9.  :func:`test_create_instance`                    — Full-instance construction.
10. :func:`test_repr`                               — ``__repr__`` format.
11. :func:`test_no_filler_columns`                  — ``FILLER`` is NOT mapped.

See Also
--------
``src/shared/models/transaction_category.py``  — The ORM model under test.
``app/cpy/CVTRA04Y.cpy``                       — Original COBOL record layout.
``app/data/ASCII/trancatg.txt``                — 18-row reference seed data.
``app/jcl/TRANCATG.jcl``                       — Mainframe seed loader (pre-migration).
``db/migrations/V3__seed_data.sql``            — Cloud-target seed loader.
AAP §0.5.1                                     — File-by-File Transformation Plan.
AAP §0.7.1                                     — Minimal-change clause (preserve
                                                  COBOL field widths exactly).
``tests.unit.test_models.__init__``            — Package docstring listing the
                                                  full model-to-copybook mapping.
"""

from __future__ import annotations

import pytest
from sqlalchemy import String, inspect

from src.shared.models import Base
from src.shared.models.transaction_category import TransactionCategory

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# Set of the 3 expected mapped column names (Python attribute names,
# which are also the SQL column names under SQLAlchemy's default
# resolution). The COBOL ``FILLER`` at the end of the 60-byte record
# is DELIBERATELY absent — padding regions have no place in the
# relational model.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "type_cd",  # from TRAN-TYPE-CD       PIC X(02) — composite PK part 1
        "cat_cd",  # from TRAN-CAT-CD        PIC 9(04) — composite PK part 2
        "description",  # from TRAN-CAT-TYPE-DESC PIC X(50)
    }
)

# Ordered tuple representing the composite primary key declaration order
# in the ORM model. The order matches the COBOL ``TRAN-CAT-KEY`` group
# definition (``TRAN-TYPE-CD`` first, then ``TRAN-CAT-CD``) which is
# the VSAM KSDS primary-key byte order (offsets 0..1, then 2..5).
_EXPECTED_COMPOSITE_PK_NAMES: tuple[str, ...] = ("type_cd", "cat_cd")


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """TransactionCategory must be mapped to the ``transaction_categories`` table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE transaction_categories``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO transaction_categories``
    * The batch (POSTTRAN, TRANREPT, CREASTMT) and online (COTRN00/01/02)
      programs that resolve transaction category descriptions.

    Any drift between ``TransactionCategory.__tablename__`` and the DDL /
    seed-data contract would cause runtime ``UndefinedTable`` errors, so
    this invariant is pinned.

    Note
    ----
    The table name is *plural* (``transaction_categories``) to follow
    the project-wide relational-naming convention shared by all sibling
    entities except ``user_security`` (which retains its historical
    singular VSAM dataset name). The singular form ``transaction_category``
    is NOT accepted — that would break the Flyway migrations already in
    place under ``db/migrations/``.
    """
    assert TransactionCategory.__tablename__ == "transaction_categories", (
        f"TransactionCategory.__tablename__ must be 'transaction_categories' "
        f"to match db/migrations/V1__schema.sql and V3__seed_data.sql; "
        f"found {TransactionCategory.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """TransactionCategory must expose exactly 3 mapped columns.

    The COBOL ``TRAN-CAT-RECORD`` layout has 4 fields (counting the
    ``TRAN-CAT-KEY`` group as two independent elementary fields —
    ``TRAN-TYPE-CD`` and ``TRAN-CAT-CD``), but only 3 are mapped to
    the relational model. ``FILLER PIC X(04)`` is deliberately dropped
    because trailing padding has no storage or semantic meaning in a
    column-typed schema.

    Ensuring the count is exactly 3 guards against two regressions:

    * An accidental ``filler`` column being added back (increases the
      count to 4).
    * A field being accidentally removed from the model (decreases the
      count below 3).
    """
    columns = TransactionCategory.__table__.columns
    assert len(columns) == 3, (
        f"TransactionCategory must have exactly 3 columns (TRAN-TYPE-CD, "
        f"TRAN-CAT-CD, TRAN-CAT-TYPE-DESC — FILLER excluded); "
        f"found {len(columns)}: {[c.name for c in columns]}"
    )


# ============================================================================
# Phase 3: Composite Primary Key Tests (CRITICAL)
# ============================================================================


@pytest.mark.unit
def test_composite_primary_key() -> None:
    """The primary key is a 2-part composite of ``type_cd`` + ``cat_cd``.

    Maps to the VSAM KSDS 6-byte primary key slot (offsets 0..5) of the
    ``TRANCATG`` cluster. Replaces the mainframe VSAM ``DEFINE CLUSTER
    KEYS(6 0)`` clause — 6 bytes at offset 0 — where the first 2 bytes
    are ``TRAN-TYPE-CD`` and the next 4 bytes are ``TRAN-CAT-CD``.

    Verifies three invariants via :func:`sqlalchemy.inspect`:

    * The PK has exactly 2 column parts (composite, not scalar).
    * The parts are named ``type_cd`` and ``cat_cd`` in that order —
      mirroring the byte order of the composite key on the VSAM
      cluster and the declaration order on the Python class.
    * Both parts are ``String`` columns with the COBOL-dictated widths
      (``String(2)`` and ``String(4)`` respectively).
    """
    primary_keys = list(inspect(TransactionCategory).primary_key)

    # 1. Composite, not scalar: exactly 2 PK parts.
    assert len(primary_keys) == 2, (
        f"TransactionCategory must have a 2-part composite primary key "
        f"(TRAN-TYPE-CD + TRAN-CAT-CD = 6 bytes); found "
        f"{len(primary_keys)}: {[pk.name for pk in primary_keys]}"
    )

    # 2. Names and order match the COBOL TRAN-CAT-KEY byte layout.
    pk_names = tuple(pk.name for pk in primary_keys)
    assert pk_names == _EXPECTED_COMPOSITE_PK_NAMES, (
        f"Composite PK columns must be ordered "
        f"{_EXPECTED_COMPOSITE_PK_NAMES!r} (matching COBOL "
        f"TRAN-CAT-KEY byte order: TRAN-TYPE-CD then TRAN-CAT-CD); "
        f"found {pk_names!r}"
    )

    # 3a. Part 1 (type_cd) is String(2).
    type_cd_pk = primary_keys[0]
    assert isinstance(type_cd_pk.type, String), (
        f"Composite PK part 1 'type_cd' must be SQLAlchemy String; found {type(type_cd_pk.type).__name__}"
    )
    assert type_cd_pk.type.length == 2, (
        f"Composite PK part 1 'type_cd' must be String(2) (from COBOL "
        f"TRAN-TYPE-CD PIC X(02)); found String({type_cd_pk.type.length})"
    )

    # 3b. Part 2 (cat_cd) is String(4).
    cat_cd_pk = primary_keys[1]
    assert isinstance(cat_cd_pk.type, String), (
        f"Composite PK part 2 'cat_cd' must be SQLAlchemy String; found {type(cat_cd_pk.type).__name__}"
    )
    assert cat_cd_pk.type.length == 4, (
        f"Composite PK part 2 'cat_cd' must be String(4) (from COBOL "
        f"TRAN-CAT-CD PIC 9(04), stored as string to preserve leading "
        f"zeros); found String({cat_cd_pk.type.length})"
    )


@pytest.mark.unit
def test_composite_key_matches_cobol_group() -> None:
    """The composite PK mirrors the COBOL ``TRAN-CAT-KEY`` group item.

    In the source COBOL ``CVTRA04Y.cpy``, the primary key is a 2-level
    group::

        05  TRAN-CAT-KEY.
           10  TRAN-TYPE-CD   PIC X(02).
           10  TRAN-CAT-CD    PIC 9(04).

    The group definition serves three purposes in COBOL:

    * It is the VSAM cluster's primary key (``KEYS(6 0)``).
    * It is the ``RECORD KEY`` in COBOL ``FD`` file definitions for
      READ / WRITE / REWRITE / DELETE on the ``TRANCATG`` dataset.
    * It is the join anchor between transactional rows and the
      transaction-category reference data.

    In the relational target, each of those three roles becomes the
    composite PRIMARY KEY of the ``transaction_categories`` table. This
    test asserts that:

    * Every column named in the COBOL ``TRAN-CAT-KEY`` group is a
      primary key column on the ORM model — no field was demoted from
      the key.
    * Every column not in ``TRAN-CAT-KEY`` (here, ``description``) is
      NOT a primary key column — no field was accidentally promoted.

    Together these invariants guarantee the relational PK is **exactly
    equivalent** to the COBOL composite key.
    """
    # Columns that were part of the COBOL TRAN-CAT-KEY group item.
    cobol_key_columns: frozenset[str] = frozenset({"type_cd", "cat_cd"})

    # Columns NOT part of the COBOL TRAN-CAT-KEY group item.
    cobol_non_key_columns: frozenset[str] = frozenset({"description"})

    primary_key_names: frozenset[str] = frozenset(pk.name for pk in inspect(TransactionCategory).primary_key)

    # Inclusion: every COBOL key field must appear in the PK.
    missing_key_parts = cobol_key_columns - primary_key_names
    assert not missing_key_parts, (
        f"Composite PK is missing COBOL TRAN-CAT-KEY field(s): "
        f"{sorted(missing_key_parts)}. The relational PK must mirror "
        f"the COBOL group TRAN-CAT-KEY (TRAN-TYPE-CD + TRAN-CAT-CD) "
        f"exactly, so every part of that group must be a primary key."
    )

    # Exclusion: no non-key COBOL field may have leaked into the PK.
    leaked_into_key = primary_key_names & cobol_non_key_columns
    assert not leaked_into_key, (
        f"Non-key COBOL field(s) leaked into the composite PK: "
        f"{sorted(leaked_into_key)}. Only COBOL TRAN-CAT-KEY members "
        f"(TRAN-TYPE-CD, TRAN-CAT-CD) may be primary-key columns. "
        f"TRAN-CAT-TYPE-DESC must remain a plain attribute."
    )

    # Equivalence: the PK is *exactly* the COBOL key set — no more, no less.
    assert primary_key_names == cobol_key_columns, (
        f"Composite PK must be exactly {sorted(cobol_key_columns)} "
        f"(matching COBOL TRAN-CAT-KEY); found {sorted(primary_key_names)}"
    )


# ============================================================================
# Phase 4: Column Type Tests
# ============================================================================


@pytest.mark.unit
def test_type_cd_type() -> None:
    """``type_cd`` column is ``String(2)`` (from COBOL TRAN-TYPE-CD PIC X(02)).

    Preserves the original mainframe 2-character transaction-type code
    width so that existing category rows migrated from VSAM remain
    addressable with their historical identifiers (``01`` Purchase,
    ``02`` Payment, ``03`` Credit, ``04`` Debit, ``05`` Refund,
    ``06`` Adjustment, ``07`` Fee).

    This column is also the first part of the composite primary key
    — the type check here is complementary to the composite-key test
    above and catches the case where the type was changed on the column
    declaration without updating the PK definition.
    """
    column = TransactionCategory.__table__.columns["type_cd"]
    assert isinstance(column.type, String), f"type_cd must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 2, (
        f"type_cd must be String(2) (from COBOL TRAN-TYPE-CD PIC X(02)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_cat_cd_type() -> None:
    """``cat_cd`` column is ``String(4)`` (from COBOL TRAN-CAT-CD PIC 9(04)).

    **Critical width + type rationale.**

    The COBOL source type is *numeric* (``PIC 9(04)``) but the Python
    target is *textual* (``String(4)``). The textual mapping is
    deliberate — it preserves leading zeros in codes like ``'0001'``
    that would be silently stripped if the field were stored as an
    integer. Zero-padded category codes are the canonical form in:

    * The seed fixture ``app/data/ASCII/trancatg.txt``.
    * The sibling tables ``disclosure_groups.tran_cat_cd`` and
      ``transaction_category_balances.tran_cat_cd`` (both ``String(4)``
      for clean composite-key joins without explicit CAST).
    * The transaction-reporting and statement-generation PySpark jobs
      that format category codes for human-readable output.

    Any deviation from ``String(4)`` (most likely a well-meaning
    refactor to ``Integer``) would silently corrupt 18 reference rows
    AND break composite-key joins with 2 sibling tables — catastrophic.
    """
    column = TransactionCategory.__table__.columns["cat_cd"]
    assert isinstance(column.type, String), (
        f"cat_cd must be SQLAlchemy String (not Integer!) to preserve "
        f"leading zeros in COBOL PIC 9(04) values; found "
        f"{type(column.type).__name__}"
    )
    assert column.type.length == 4, (
        f"cat_cd must be String(4) (from COBOL TRAN-CAT-CD PIC 9(04), "
        f"stored as string to preserve leading zeros like '0001'); "
        f"found String({column.type.length})"
    )


@pytest.mark.unit
def test_description_type() -> None:
    """``description`` column is ``String(50)`` (from COBOL TRAN-CAT-TYPE-DESC PIC X(50)).

    Matches the COBOL ``TRAN-CAT-TYPE-DESC PIC X(50)`` field exactly.
    The 50-character width accommodates the longest descriptions seen
    in the ``trancatg.txt`` reference fixture (e.g.,
    ``'Regular Sales Draft'``) with substantial headroom for future
    category additions.

    This is the only non-PK column on the model — making its presence
    and type a direct test of the value-carrying payload of the
    reference table.
    """
    column = TransactionCategory.__table__.columns["description"]
    assert isinstance(column.type, String), f"description must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 50, (
        f"description must be String(50) (from COBOL TRAN-CAT-TYPE-DESC PIC X(50)); found String({column.type.length})"
    )


# ============================================================================
# Phase 5: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 3 mapped columns must be declared NOT NULL.

    The COBOL ``TRAN-CAT-RECORD`` layout has no ``OCCURS ... DEPENDING
    ON`` clauses and no ``REDEFINES`` — every field is present on
    every record. Translating that semantics to the relational model
    means every column is mandatory (``nullable=False``):

    * ``type_cd``     — required (composite PK part 1; NULL PKs are
      rejected at the SQL level anyway).
    * ``cat_cd``      — required (composite PK part 2; same rationale).
    * ``description`` — required (every category MUST have a
      human-readable label — empty categories are meaningless as
      reference data).

    SQLAlchemy automatically sets ``nullable=False`` on any column
    marked ``primary_key=True``, but this test asserts the invariant
    explicitly on every column so that accidentally dropping
    ``nullable=False`` (or unmarking the PK) triggers an obvious
    failure. For ``description``, the check is a direct sanity-check
    of the explicit ``nullable=False`` kwarg on the ``mapped_column``.
    """
    for column_name in _EXPECTED_COLUMNS:
        column = TransactionCategory.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column '{column_name}' must be NOT NULL "
            f"(every COBOL TRAN-CAT-RECORD field is mandatory); "
            f"nullable={column.nullable}"
        )


# ============================================================================
# Phase 6: Instance Creation Tests
# ============================================================================


@pytest.mark.unit
def test_create_instance() -> None:
    """A TransactionCategory instance can be constructed with all 3 fields.

    Exercises the SQLAlchemy 2.x ``__init__`` synthesized from the
    :class:`~sqlalchemy.orm.Mapped` declarations in the ORM class.
    All field values correspond 1-to-1 to the COBOL
    ``TRAN-CAT-RECORD`` record layout:

    * ``type_cd='01'``                 — 2 chars (TRAN-TYPE-CD PIC X(02))
    * ``cat_cd='5001'``                — 4 chars (TRAN-CAT-CD PIC 9(04),
      String-encoded — preserves leading zeros)
    * ``description='Retail Purchase'`` — within 50 chars
      (TRAN-CAT-TYPE-DESC PIC X(50))

    After construction, every field must read back verbatim. No ORM
    session or database round-trip is required for this test — it
    exercises pure in-memory object construction.

    Also verifies that the constructed instance is a proper descendant
    of the shared declarative :class:`Base` — guarding against
    accidentally re-rooting the model on a different ``MetaData``
    during a refactor, which would de-register its table from the
    shared schema.
    """
    category = TransactionCategory(
        type_cd="01",
        cat_cd="5001",
        description="Retail Purchase",
    )

    # The entity must descend from the shared declarative base so that
    # its table registers on the shared MetaData used by Alembic /
    # Flyway / test fixtures.
    assert isinstance(category, Base), (
        "TransactionCategory must be a subclass of src.shared.models.Base "
        "so that its table registers on the shared MetaData."
    )

    # Field-by-field readback.
    assert category.type_cd == "01", f"type_cd readback mismatch: got {category.type_cd!r}"
    assert category.cat_cd == "5001", f"cat_cd readback mismatch: got {category.cat_cd!r}"
    assert category.description == "Retail Purchase", f"description readback mismatch: got {category.description!r}"


@pytest.mark.unit
def test_repr() -> None:
    """``__repr__`` returns a developer-friendly string including all 3 fields.

    Contract:

    * MUST include the class name ``TransactionCategory``.
    * MUST include the ``type_cd`` value.
    * MUST include the ``cat_cd`` value.
    * MUST include the ``description`` value.

    Unlike ``UserSecurity.__repr__`` (which deliberately hides the
    password field), ``TransactionCategory`` has no credential or
    PII content — the full field set is safe to expose in log output
    and debugger inspections. A developer reading a traceback
    mentioning this entity should be able to identify the offending
    row by its composite key and description without having to cross-
    reference a separate SELECT.
    """
    category = TransactionCategory(
        type_cd="01",
        cat_cd="0001",
        description="Regular Sales Draft",
    )

    repr_output = repr(category)

    # Required inclusions.
    assert "TransactionCategory" in repr_output, (
        f"__repr__ must include the class name 'TransactionCategory'; got {repr_output!r}"
    )
    assert "01" in repr_output, f"__repr__ must include type_cd value '01'; got {repr_output!r}"
    assert "0001" in repr_output, f"__repr__ must include cat_cd value '0001'; got {repr_output!r}"
    assert "Regular Sales Draft" in repr_output, (
        f"__repr__ must include description value 'Regular Sales Draft'; got {repr_output!r}"
    )


# ============================================================================
# Phase 7: FILLER Exclusion Test
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """No column maps the COBOL ``FILLER PIC X(04)`` padding.

    COBOL fixed-width records routinely use ``FILLER`` regions to pad
    to a target record length (here, 4 bytes so the overall record
    reaches 60 — ``TRAN-CAT-KEY`` 6 + ``TRAN-CAT-TYPE-DESC`` 50 +
    ``FILLER`` 4 = 60). These padding regions exist purely as storage
    artifacts of the fixed-width on-disk format — they carry no
    semantic data — and therefore have no equivalent in a typed
    relational schema.

    This test scans every column on the model's ``__table__.columns``
    collection and asserts that none contains the substring
    ``filler`` (case-insensitive). The substring check catches common
    naming variants including ``filler``, ``tran_cat_filler``,
    ``cat_filler``, etc.

    It also performs a positive assertion that the *exact* expected
    column set is present — catching accidental additions (extra
    columns) and removals (missing columns) in one check.
    """
    column_names: list[str] = [c.name for c in TransactionCategory.__table__.columns]

    # Positive: the exact set of mapped columns must match the contract.
    assert set(column_names) == set(_EXPECTED_COLUMNS), (
        f"Column set drift detected. Expected: {sorted(_EXPECTED_COLUMNS)}; found: {sorted(column_names)}"
    )

    # Negative: no column name may contain the substring 'filler' in
    # any casing. This guards against future regressions where a
    # copybook-to-model translator accidentally emits a filler column.
    for column_name in column_names:
        assert "filler" not in column_name.lower(), (
            f"Column '{column_name}' appears to map a COBOL FILLER "
            f"region. FILLER fields (like the trailing PIC X(04) in "
            f"CVTRA04Y.cpy) are padding only and MUST NOT be mapped "
            f"to the relational model."
        )
