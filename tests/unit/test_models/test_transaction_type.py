# ============================================================================
# Source: COBOL copybook CVTRA03Y.cpy — TRAN-TYPE-RECORD (RECLN 60, key 2)
# ============================================================================
# Reference data: 7 transaction types (Purchase through Adjustment).
# Seed data lives at ``app/data/ASCII/trantype.txt`` and is loaded into the
# target Aurora PostgreSQL schema by ``db/migrations/V3__seed_data.sql``.
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
"""Unit tests for the :class:`TransactionType` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CVTRA03Y.cpy`` (record layout ``TRAN-TYPE-RECORD``, a 60-byte
VSAM KSDS record with a 2-byte key) into the SQLAlchemy 2.x
declarative ORM model at ``src/shared/models/transaction_type.py``.

COBOL Source Layout (``CVTRA03Y.cpy``)
--------------------------------------
::

    01  TRAN-TYPE-RECORD.
        05  TRAN-TYPE                               PIC X(02).
        05  TRAN-TYPE-DESC                          PIC X(50).
        05  FILLER                                  PIC X(08).

The ``TRAN-TYPE`` field at offset 0 (2 bytes) is the VSAM cluster's
primary key as defined by ``app/jcl/TRANTYPE.jcl``
(``DEFINE CLUSTER KEYS(2 0)``). The SQLAlchemy model declares a
**single-column primary key** mirroring the COBOL key definition
one-for-one.

COBOL -> Python Field Mapping
-----------------------------
====================  ==============  =============  =============================
COBOL Field           COBOL Type      Python Attr    SQLAlchemy Type
====================  ==============  =============  =============================
TRAN-TYPE             ``PIC X(02)``   tran_type      ``String(2)`` (single-col PK)
TRAN-TYPE-DESC        ``PIC X(50)``   description    ``String(50)``
FILLER                ``PIC X(08)``   (not mapped)   (COBOL padding only)
====================  ==============  =============  =============================

Total RECLN: 2 + 50 + 8 = 60 bytes — matches the VSAM cluster
``RECSZ(60 60)`` clause in ``app/jcl/TRANTYPE.jcl``.

Reference Data
--------------
``TransactionType`` is a **reference-data table** populated by the
transaction-type seed loader. The canonical 7-row reference set is
defined by ``app/data/ASCII/trantype.txt`` and loaded via
``app/jcl/TRANTYPE.jcl`` in the mainframe world and
``db/migrations/V3__seed_data.sql`` in the target cloud architecture::

    01  Purchase
    02  Payment
    03  Credit
    04  Authorization
    05  Refund
    06  Reversal
    07  Adjustment

No monetary / financial fields appear on this entity — it is purely a
descriptive lookup consumed by the batch posting (``CBTRN02C`` ->
``posttran_job.py``), interest calculation (``CBACT04C`` ->
``intcalc_job.py``), statement generation (``CBSTM03A`` ->
``creastmt_job.py``), transaction reporting (``CBTRN03C`` ->
``tranrept_job.py``), and the online transaction add / list / detail
flows (``COTRN00C``, ``COTRN01C``, ``COTRN02C`` ->
``transaction_service.py``).

Consumer References
-------------------
The ``tran_type`` code appears as a foreign-key-equivalent in the
following sibling entities (composite-key participation documented in
their respective model modules):

* ``Transaction`` (``CVTRA05Y.cpy``) — ``TRAN-TYPE-CD``.
* ``DailyTransaction`` (``CVTRA06Y.cpy``) — ``DALYTRAN-TYPE-CD``.
* ``TransactionCategory`` (``CVTRA04Y.cpy``) — composite PK part
  (``type_cd`` + ``cat_cd``).
* ``TransactionCategoryBalance`` (``CVTRA01Y.cpy``) — composite PK
  part (``acct_id`` + ``type_cd`` + ``cat_cd``).
* ``DisclosureGroup`` (``CVTRA02Y.cpy``) — composite PK part
  (``acct_group_id`` + ``tran_type_cd`` + ``tran_cat_cd``).

Test Coverage (10 functions — matches the file schema's ``exports`` contract)
-----------------------------------------------------------------------------
1.  :func:`test_tablename`                        — ``__tablename__`` contract.
2.  :func:`test_column_count`                     — Exactly 2 mapped columns.
3.  :func:`test_primary_key_tran_type`            — ``tran_type`` is sole PK.
4.  :func:`test_tran_type_type`                   — ``tran_type`` is ``String(2)``.
5.  :func:`test_description_type`                 — ``description`` is ``String(50)``.
6.  :func:`test_non_nullable_fields`              — NOT NULL on every column.
7.  :func:`test_create_transaction_type_instance` — Full-instance construction.
8.  :func:`test_transaction_type_repr`            — ``__repr__`` format.
9.  :func:`test_valid_type_codes`                 — All 7 seed codes accepted.
10. :func:`test_no_filler_columns`                — ``FILLER`` is NOT mapped.

See Also
--------
``src/shared/models/transaction_type.py``  — The ORM model under test.
``app/cpy/CVTRA03Y.cpy``                   — Original COBOL record layout.
``app/data/ASCII/trantype.txt``            — 7-row reference seed data.
``app/jcl/TRANTYPE.jcl``                   — Mainframe seed loader (pre-migration).
``db/migrations/V3__seed_data.sql``        — Cloud-target seed loader.
AAP §0.5.1                                 — File-by-File Transformation Plan.
AAP §0.7.1                                 — Minimal-change clause (preserve
                                              COBOL field widths exactly).
``tests.unit.test_models.__init__``        — Package docstring listing the
                                              full model-to-copybook mapping.
"""

from __future__ import annotations

import pytest
from sqlalchemy import String, inspect

from src.shared.models import Base
from src.shared.models.transaction_type import TransactionType

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# Set of the 2 expected mapped column names (Python attribute names,
# which are also the SQL column names under SQLAlchemy's default
# resolution). The COBOL ``FILLER`` at the end of the 60-byte record
# is DELIBERATELY absent — padding regions have no place in the
# relational model.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "tran_type",  # from TRAN-TYPE      PIC X(02) — single-column PK
        "description",  # from TRAN-TYPE-DESC PIC X(50)
    }
)

# Canonical 7-row seed data from ``app/data/ASCII/trantype.txt`` — the
# complete reference set that POSTTRAN, INTCALC, COMBTRAN, CREASTMT,
# TRANREPT, and every online transaction flow must handle. Each tuple
# is ``(tran_type, description)`` with widths matching the COBOL
# copybook (2 chars and up to 50 chars respectively).
_CANONICAL_TRANSACTION_TYPES: tuple[tuple[str, str], ...] = (
    ("01", "Purchase"),
    ("02", "Payment"),
    ("03", "Credit"),
    ("04", "Authorization"),
    ("05", "Refund"),
    ("06", "Reversal"),
    ("07", "Adjustment"),
)


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """TransactionType must be mapped to the ``transaction_types`` table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE transaction_types``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO transaction_types``
    * The batch (POSTTRAN, INTCALC, COMBTRAN, CREASTMT, TRANREPT) and
      online (COTRN00/01/02) programs that resolve transaction-type
      descriptions for display, reporting, and validation.

    Any drift between ``TransactionType.__tablename__`` and the DDL /
    seed-data contract would cause runtime ``UndefinedTable`` errors,
    so this invariant is pinned.

    Note
    ----
    The table name is *plural* (``transaction_types``) to follow the
    project-wide relational-naming convention shared by all sibling
    entities (except ``user_security`` which retains its historical
    singular VSAM dataset name). The singular form ``transaction_type``
    is NOT accepted — that would break the Flyway migrations already in
    place under ``db/migrations/`` and the sibling
    ``TransactionCategory`` / ``TransactionCategoryBalance`` entities
    whose foreign-key-equivalent references are phrased against the
    plural table.
    """
    assert TransactionType.__tablename__ == "transaction_types", (
        f"TransactionType.__tablename__ must be 'transaction_types' "
        f"to match db/migrations/V1__schema.sql and V3__seed_data.sql; "
        f"found {TransactionType.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """TransactionType must expose exactly 2 mapped columns.

    The COBOL ``TRAN-TYPE-RECORD`` layout has 3 fields, but only 2 are
    mapped to the relational model. ``FILLER PIC X(08)`` is deliberately
    dropped because trailing padding has no storage or semantic meaning
    in a column-typed schema.

    This is the **simplest model in the CardDemo system** — only a
    2-character primary key and a 50-character description. Ensuring
    the count is exactly 2 guards against two regressions:

    * An accidental ``filler`` column being added back (increases the
      count to 3).
    * A field being accidentally removed from the model (decreases the
      count below 2).
    """
    columns = TransactionType.__table__.columns
    assert len(columns) == 2, (
        f"TransactionType must have exactly 2 columns (TRAN-TYPE, "
        f"TRAN-TYPE-DESC — FILLER excluded); "
        f"found {len(columns)}: {[c.name for c in columns]}"
    )


@pytest.mark.unit
def test_primary_key_tran_type() -> None:
    """The sole primary key is ``tran_type`` (from COBOL TRAN-TYPE).

    Maps to the VSAM KSDS 2-byte primary-key slot (offset 0) of the
    ``TRANTYPE`` cluster. Replaces the mainframe VSAM ``DEFINE CLUSTER
    KEYS(2 0)`` clause from ``app/jcl/TRANTYPE.jcl``.

    Verifies via :func:`sqlalchemy.inspect` that:

    * ``tran_type`` is the sole (single) primary-key column (no
      composite key — the COBOL source defines only one key field).
    * The PK column's type is ``String(2)`` — matching the COBOL
      ``PIC X(02)`` original width.

    The single-column PK is the **simplest** PK shape among the 11
    CardDemo entities — this test's primary value is catching
    regressions where a well-meaning refactor accidentally adds a
    second PK column (turning the primary key into a composite).
    """
    primary_keys = list(inspect(TransactionType).primary_key)

    # Exactly one PK column (no composite key for TransactionType).
    assert len(primary_keys) == 1, (
        f"TransactionType must have exactly one primary key column "
        f"(TRAN-TYPE); found {len(primary_keys)}: "
        f"{[pk.key for pk in primary_keys]}"
    )

    # Use ``Column.key`` (Python attribute key) rather than
    # ``Column.name`` (DB physical column name). The column is
    # declared via ``mapped_column("type_code", ..., key="tran_type")``
    # so ``Column.name`` is ``"type_code"`` but the Python ORM
    # attribute is ``TransactionType.tran_type``.
    pk_column = primary_keys[0]
    assert pk_column.key == "tran_type", (
        f"Primary key column must be 'tran_type' (from COBOL TRAN-TYPE PIC X(02)); found '{pk_column.key}'"
    )

    # PK type validation — must be String(2) to match COBOL PIC X(02).
    assert isinstance(pk_column.type, String), (
        f"Primary key 'tran_type' must be SQLAlchemy String; found {type(pk_column.type).__name__}"
    )
    assert pk_column.type.length == 2, (
        f"Primary key 'tran_type' must be String(2) (from COBOL "
        f"TRAN-TYPE PIC X(02)); found String({pk_column.type.length})"
    )


# ============================================================================
# Phase 3: Column Type Tests
# ============================================================================


@pytest.mark.unit
def test_tran_type_type() -> None:
    """``tran_type`` column is ``String(2)`` (from COBOL TRAN-TYPE PIC X(02)).

    Preserves the original mainframe 2-character transaction-type code
    width so that existing records migrated from VSAM remain
    addressable with their historical identifiers (``01`` Purchase,
    ``02`` Payment, ``03`` Credit, ``04`` Authorization, ``05``
    Refund, ``06`` Reversal, ``07`` Adjustment).

    This column is also the single primary key — the type check here
    is complementary to the PK test above and catches the case where
    the column type was changed on the declaration without updating
    the PK definition.
    """
    column = TransactionType.__table__.columns["tran_type"]
    assert isinstance(column.type, String), f"tran_type must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 2, (
        f"tran_type must be String(2) (from COBOL TRAN-TYPE PIC X(02)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_description_type() -> None:
    """``description`` column is ``String(50)`` (from COBOL TRAN-TYPE-DESC PIC X(50)).

    Matches the COBOL ``TRAN-TYPE-DESC PIC X(50)`` field exactly. The
    50-character width accommodates the longest descriptions seen in
    the ``trantype.txt`` reference fixture (all 7 current descriptions
    are well under 50 characters, leaving substantial headroom for
    future type additions — e.g., ``'Balance Transfer'``,
    ``'Cash Advance'``, or longer regulatory type labels).

    This is the only non-PK column on the model — making its presence
    and type a direct test of the value-carrying payload of the
    reference table.
    """
    column = TransactionType.__table__.columns["description"]
    assert isinstance(column.type, String), f"description must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 50, (
        f"description must be String(50) (from COBOL TRAN-TYPE-DESC PIC X(50)); found String({column.type.length})"
    )


# ============================================================================
# Phase 4: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """Both mapped columns must be declared NOT NULL.

    The COBOL ``TRAN-TYPE-RECORD`` layout has no ``OCCURS ... DEPENDING
    ON`` clauses and no ``REDEFINES`` — every field is present on
    every record. Translating that semantics to the relational model
    means every column is mandatory (``nullable=False``):

    * ``tran_type``   — required (primary key; NULL PKs are rejected
      at the SQL level anyway, but the explicit assertion here catches
      accidental ``primary_key=True`` removal regressions).
    * ``description`` — required (every transaction type MUST have a
      human-readable label — empty types would render transaction
      statements and reports illegible).

    SQLAlchemy automatically sets ``nullable=False`` on any column
    marked ``primary_key=True``, but this test asserts the invariant
    explicitly on every column so that accidentally dropping
    ``nullable=False`` (or unmarking the PK) triggers an obvious
    failure. For ``description``, the check is a direct sanity-check
    of the explicit ``nullable=False`` kwarg on the ``mapped_column``.
    """
    for column_name in _EXPECTED_COLUMNS:
        column = TransactionType.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column '{column_name}' must be NOT NULL "
            f"(every COBOL TRAN-TYPE-RECORD field is mandatory); "
            f"nullable={column.nullable}"
        )


# ============================================================================
# Phase 5: Instance Creation Tests
# ============================================================================


@pytest.mark.unit
def test_create_transaction_type_instance() -> None:
    """A TransactionType instance can be constructed with both fields.

    Exercises the SQLAlchemy 2.x ``__init__`` synthesized from the
    :class:`~sqlalchemy.orm.Mapped` declarations in the ORM class.
    All field values correspond 1-to-1 to the COBOL
    ``TRAN-TYPE-RECORD`` record layout:

    * ``tran_type='01'``     — 2 chars (TRAN-TYPE PIC X(02)) — the
      first of the 7 seed codes (Purchase).
    * ``description='Purchase'`` — within 50 chars
      (TRAN-TYPE-DESC PIC X(50)) — matches the first row of
      ``app/data/ASCII/trantype.txt``.

    After construction, every field must read back verbatim. No ORM
    session or database round-trip is required for this test — it
    exercises pure in-memory object construction.

    Also verifies that the constructed instance is a proper descendant
    of the shared declarative :class:`Base` — guarding against
    accidentally re-rooting the model on a different ``MetaData``
    during a refactor, which would de-register its table from the
    shared schema.
    """
    transaction_type = TransactionType(
        tran_type="01",
        description="Purchase",
    )

    # The entity must descend from the shared declarative base so that
    # its table registers on the shared MetaData used by Alembic /
    # Flyway / test fixtures.
    assert isinstance(transaction_type, Base), (
        "TransactionType must be a subclass of src.shared.models.Base "
        "so that its table registers on the shared MetaData."
    )

    # Field-by-field readback.
    assert transaction_type.tran_type == "01", f"tran_type readback mismatch: got {transaction_type.tran_type!r}"
    assert transaction_type.description == "Purchase", (
        f"description readback mismatch: got {transaction_type.description!r}"
    )


@pytest.mark.unit
def test_transaction_type_repr() -> None:
    """``__repr__`` returns a developer-friendly string including both fields.

    Contract:

    * MUST include the class name ``TransactionType``.
    * MUST include the ``tran_type`` value.
    * MUST include the ``description`` value.

    Unlike ``UserSecurity.__repr__`` (which deliberately hides the
    password field), ``TransactionType`` has no credential or PII
    content — the full field set is safe to expose in log output and
    debugger inspections. A developer reading a traceback mentioning
    this entity should be able to identify the offending row by its
    type code and description without having to cross-reference a
    separate SELECT against the reference table.

    The assertion uses ``'01'`` (the quoted literal) rather than the
    bare character ``01`` to reduce the risk of false positives — the
    expected ``__repr__`` format produced by the model is
    ``TransactionType(tran_type='01', description='Purchase')`` so
    both the value and its repr quoting should be present.
    """
    transaction_type = TransactionType(
        tran_type="01",
        description="Purchase",
    )

    repr_output = repr(transaction_type)

    # Required inclusions.
    assert "TransactionType" in repr_output, (
        f"__repr__ must include the class name 'TransactionType'; got {repr_output!r}"
    )
    assert "01" in repr_output, f"__repr__ must include tran_type value '01'; got {repr_output!r}"
    assert "Purchase" in repr_output, f"__repr__ must include description value 'Purchase'; got {repr_output!r}"


@pytest.mark.unit
def test_valid_type_codes() -> None:
    """All 7 canonical transaction type codes from trantype.txt are accepted.

    The canonical 7-row seed set is defined by
    ``app/data/ASCII/trantype.txt`` and loaded into the Aurora
    PostgreSQL ``transaction_types`` table by
    ``db/migrations/V3__seed_data.sql``:

    =========  ===============
    tran_type  description
    =========  ===============
    ``01``     Purchase
    ``02``     Payment
    ``03``     Credit
    ``04``     Authorization
    ``05``     Refund
    ``06``     Reversal
    ``07``     Adjustment
    =========  ===============

    Every type code is **exactly 2 characters** (zero-padded) matching
    COBOL ``TRAN-TYPE PIC X(02)``. Every description is **within 50
    characters** matching COBOL ``TRAN-TYPE-DESC PIC X(50)``. This
    test constructs one instance per row, verifies field readback,
    and confirms the type-code width invariant in a single pass.

    The test is authoritative for the cloud-target schema because
    every downstream consumer (POSTTRAN ``posttran_job.py``, INTCALC
    ``intcalc_job.py``, CREASTMT ``creastmt_job.py``, TRANREPT
    ``tranrept_job.py``, online transaction services) resolves types
    exclusively through this set of 7 codes.
    """
    # Sanity-check the in-module canonical set against the declared size
    # so that accidentally shortening the seed list triggers an obvious
    # failure before the loop runs.
    assert len(_CANONICAL_TRANSACTION_TYPES) == 7, (
        f"Canonical transaction-type set must have exactly 7 rows "
        f"(matching app/data/ASCII/trantype.txt); found "
        f"{len(_CANONICAL_TRANSACTION_TYPES)}"
    )

    # Every tran_type code must be unique — the VSAM cluster has
    # ``KEYS(2 0)`` meaning the 2-byte key is the primary key; duplicate
    # codes would violate the PK constraint at INSERT time.
    seen_codes: set[str] = set()

    for tran_type_code, description in _CANONICAL_TRANSACTION_TYPES:
        # Width invariants — COBOL PIC X(02) and PIC X(50).
        assert len(tran_type_code) == 2, (
            f"tran_type code '{tran_type_code}' must be exactly 2 "
            f"characters (COBOL TRAN-TYPE PIC X(02)); "
            f"found {len(tran_type_code)}"
        )
        assert len(description) <= 50, (
            f"description '{description}' must fit in 50 characters "
            f"(COBOL TRAN-TYPE-DESC PIC X(50)); "
            f"found {len(description)}"
        )

        # Uniqueness — no duplicate primary-key codes across the seed set.
        assert tran_type_code not in seen_codes, (
            f"Duplicate tran_type code '{tran_type_code}' in canonical "
            f"seed set — primary-key constraint would reject this at "
            f"INSERT time."
        )
        seen_codes.add(tran_type_code)

        # Instance construction — the ORM class must accept every
        # canonical seed row without raising.
        instance = TransactionType(
            tran_type=tran_type_code,
            description=description,
        )

        # Readback — every field must round-trip verbatim.
        assert instance.tran_type == tran_type_code, (
            f"tran_type readback mismatch for seed row "
            f"({tran_type_code!r}, {description!r}): "
            f"got {instance.tran_type!r}"
        )
        assert instance.description == description, (
            f"description readback mismatch for seed row "
            f"({tran_type_code!r}, {description!r}): "
            f"got {instance.description!r}"
        )

    # All 7 codes were unique — final post-condition sanity check.
    assert len(seen_codes) == 7, f"Expected 7 unique tran_type codes; found {len(seen_codes)}: {sorted(seen_codes)}"


# ============================================================================
# Phase 6: FILLER Exclusion Test
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """No column maps the COBOL ``FILLER PIC X(08)`` padding.

    COBOL fixed-width records routinely use ``FILLER`` regions to pad
    to a target record length (here, 8 bytes so the overall record
    reaches 60 — ``TRAN-TYPE`` 2 + ``TRAN-TYPE-DESC`` 50 +
    ``FILLER`` 8 = 60). These padding regions exist purely as storage
    artifacts of the fixed-width on-disk format — they carry no
    semantic data — and therefore have no equivalent in a typed
    relational schema.

    This test scans every column on the model's ``__table__.columns``
    collection and asserts that none contains the substring
    ``filler`` (case-insensitive). The substring check catches common
    naming variants including ``filler``, ``tran_type_filler``,
    ``type_filler``, etc.

    It also performs a positive assertion that the *exact* expected
    column set is present — catching accidental additions (extra
    columns) and removals (missing columns) in one check. The total
    column count (2) makes this the simplest model in the CardDemo
    schema, so a set-equality check is both feasible and informative.
    """
    # ``_EXPECTED_COLUMNS`` holds Python-attribute names
    # (``tran_type``, ``description``); the DB physical column names
    # differ (``type_code``, ``tran_type_desc``). Compare against
    # ``Column.key`` for positive equivalence and scan BOTH forms
    # for 'filler' (defense in depth).
    column_keys: list[str] = [c.key for c in TransactionType.__table__.columns]
    column_db_names: list[str] = [c.name for c in TransactionType.__table__.columns]

    # Positive: the exact set of mapped columns must match the contract.
    assert set(column_keys) == set(_EXPECTED_COLUMNS), (
        f"Column set drift detected. Expected: {sorted(_EXPECTED_COLUMNS)}; found: {sorted(column_keys)}"
    )

    # Negative: no column name (Python key OR DB column name) may
    # contain the substring 'filler' in any casing. This guards
    # against future regressions where a copybook-to-model
    # translator accidentally emits a filler column in either form.
    for column_name in set(column_keys) | set(column_db_names):
        assert "filler" not in column_name.lower(), (
            f"Column '{column_name}' appears to map a COBOL FILLER "
            f"region. FILLER fields (like the trailing PIC X(08) in "
            f"CVTRA03Y.cpy) are padding only and MUST NOT be mapped "
            f"to the relational model."
        )
