# ============================================================================
# Source: COBOL copybook CVACT03Y.cpy — CARD-XREF-RECORD (50 bytes, VSAM KSDS)
# ============================================================================
# Tests validate cross-reference linking card -> account -> customer.
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
"""Unit tests for the :class:`CardCrossReference` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CVACT03Y.cpy`` (record layout ``CARD-XREF-RECORD``, a 50-byte
VSAM KSDS record with a 16-byte primary key and one NONUNIQUE
alternate index by account ID) into the SQLAlchemy 2.x declarative
ORM model at ``src/shared/models/card_cross_reference.py``.

COBOL Source Layout (``CVACT03Y.cpy``)
--------------------------------------
::

    01 CARD-XREF-RECORD.
        05  XREF-CARD-NUM                     PIC X(16).
        05  XREF-CUST-ID                      PIC 9(09).
        05  XREF-ACCT-ID                      PIC 9(11).
        05  FILLER                            PIC X(14).

The ``XREF-CARD-NUM`` field at offset 0 (16 bytes) is the VSAM cluster's
primary key as defined by ``app/jcl/XREFFILE.jcl``
(``DEFINE CLUSTER KEYS(16 0)``). The SQLAlchemy model declares a
**single-column primary key** mirroring the COBOL key definition
one-for-one. The ``XREF-ACCT-ID`` field (bytes 25..35) is additionally
the key of the VSAM alternate index ``XREFFILE.XREFAI02.PATH``
(NONUNIQUEKEY) — migrated to a non-unique B-tree index named
``ix_card_cross_reference_acct_id`` on the ``acct_id`` column.

COBOL -> Python Field Mapping
-----------------------------
==============  ==============  ==============  =============================
COBOL Field     COBOL Type      Python Attr     SQLAlchemy Type
==============  ==============  ==============  =============================
XREF-CARD-NUM   ``PIC X(16)``   card_num        ``String(16)`` (single-col PK)
XREF-CUST-ID    ``PIC 9(09)``   cust_id         ``String(9)`` †
XREF-ACCT-ID    ``PIC 9(11)``   acct_id         ``String(11)`` † (indexed)
FILLER          ``PIC X(14)``   (not mapped)    (COBOL padding only)
==============  ==============  ==============  =============================

† **Numeric identifiers stored as strings.** The COBOL fields
``XREF-CUST-ID`` (``PIC 9(09)``) and ``XREF-ACCT-ID`` (``PIC 9(11)``)
are numeric in the source record layout, but both are mapped to
``String(n)`` columns on the Python / PostgreSQL side to preserve
leading zeros from migrated VSAM records (e.g., customer ID
``'000000001'`` or account ID ``'00000000001'``). Storing these as
numeric types would silently strip the leading zeros at INSERT time,
breaking the logical joins to :class:`~src.shared.models.customer.Customer`
(``cust_id``) and :class:`~src.shared.models.account.Account`
(``acct_id``). Byte-for-byte preservation of the original VSAM
representation is required by AAP §0.7.1 ("preserve all existing
functionality exactly as-is").

Total RECLN: 16 + 9 + 11 + 14 = 50 bytes — matches the VSAM cluster
definition in ``app/jcl/XREFFILE.jcl`` (``RECSZ(50 50)``).

Alternate Index Preservation
----------------------------
The VSAM cluster ``XREFFILE`` defined an alternate index
``XREFFILE.XREFAI02.PATH`` keyed on ``XREF-ACCT-ID`` (NONUNIQUEKEY).
This alternate index enabled efficient "find all cards mapped to
account N" lookups from the following COBOL programs:

* ``COACTVWC.cbl`` (F-004 Account View) — 3-entity join.
* ``COCRDLIC.cbl`` (F-006 Card List) — paginated card listing by
  account.
* ``COTRN02C.cbl`` (F-011 Transaction Add) — resolves card number
  to owning account at transaction creation.
* ``CBTRN02C.cbl`` (POSTTRAN Stage 1) — bulk card-to-account
  resolution during daily transaction posting; failures are
  rejected with reject code 102.

To preserve this access pattern with Aurora PostgreSQL, the ORM
model declares a non-unique B-tree index
(``ix_card_cross_reference_acct_id``) on the ``acct_id`` column via
``__table_args__``. The index is intentionally non-unique because a
single account can own multiple cards (primary, authorised user,
replacement, reissue, etc.) — mirroring the NONUNIQUEKEY property of
the VSAM AIX (see ``app/catlg/LISTCAT.txt``).

Test Coverage (11 functions — matches the file schema's ``exports`` contract)
-----------------------------------------------------------------------------
1.  :func:`test_tablename`             — ``__tablename__`` contract.
2.  :func:`test_column_count`          — Exactly 3 mapped columns.
3.  :func:`test_primary_key_card_num`  — ``card_num`` is sole PK, ``String(16)``.
4.  :func:`test_card_num_type`         — ``card_num`` is ``String(16)``.
5.  :func:`test_cust_id_type`          — ``cust_id`` is ``String(9)``.
6.  :func:`test_acct_id_type`          — ``acct_id`` is ``String(11)``.
7.  :func:`test_acct_id_index`         — ``ix_card_cross_reference_acct_id``
                                         non-unique B-tree on ``acct_id``.
8.  :func:`test_non_nullable_fields`   — All 3 columns are NOT NULL.
9.  :func:`test_create_xref_instance`  — Full-instance construction with
                                         leading-zero preservation.
10. :func:`test_xref_repr`             — ``__repr__`` format.
11. :func:`test_no_filler_columns`     — COBOL ``FILLER`` NOT mapped.

See Also
--------
``src/shared/models/card_cross_reference.py`` — ORM model under test.
``src/shared/models/__init__.py``             — The shared declarative ``Base``.
``app/cpy/CVACT03Y.cpy``                      — Original COBOL record layout.
``app/jcl/XREFFILE.jcl``                      — Original VSAM cluster
                                                 definition (``RECSZ(50 50)``,
                                                 ``KEYS(16 0)``).
``app/catlg/LISTCAT.txt``                     — IDCAMS catalog entry for
                                                 ``XREFFILE.XREFAI02.PATH``
                                                 alternate index.
``app/data/ASCII/cardxref.txt``               — 50-row seed fixture.
``db/migrations/V1__schema.sql``              — ``CREATE TABLE
                                                 card_cross_references``.
``db/migrations/V2__indexes.sql``             — ``CREATE INDEX
                                                 ix_card_cross_reference_acct_id``.
``db/migrations/V3__seed_data.sql``           — Seed rows from
                                                 ``cardxref.txt``.
AAP §0.5.1                                    — File-by-File Transformation Plan.
AAP §0.7.1                                    — Minimal-change clause
                                                 (preserve COBOL field widths
                                                 exactly, preserve leading
                                                 zeros on numeric IDs).
"""

from __future__ import annotations

import pytest
from sqlalchemy import String, inspect

from src.shared.models import Base
from src.shared.models.card_cross_reference import CardCrossReference

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# Set of the 3 expected mapped column names (Python attribute names, which
# are also the SQL column names under SQLAlchemy's default resolution). The
# COBOL ``FILLER PIC X(14)`` trailing the 50-byte record is DELIBERATELY
# absent — padding regions have no place in the relational model (see
# :func:`test_no_filler_columns`).
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "card_num",  # from XREF-CARD-NUM  PIC X(16) — single-column PK
        "cust_id",  # from XREF-CUST-ID   PIC 9(09) — String(9) preserves leading zeros
        "acct_id",  # from XREF-ACCT-ID   PIC 9(11) — String(11), indexed
    }
)

# Expected B-tree index name on the ``acct_id`` column. Replicates the
# mainframe VSAM alternate index ``XREFFILE.XREFAI02.PATH`` (NONUNIQUEKEY)
# documented in ``app/catlg/LISTCAT.txt``. The project-wide convention is
# ``ix_<table_name_singular>_<column>`` — confirmed by:
#
#   * ``db/migrations/V2__indexes.sql`` — ``CREATE INDEX
#     ix_card_cross_reference_acct_id``
#   * ``src/shared/models/card_cross_reference.py`` — ``Index(
#     "ix_card_cross_reference_acct_id", "acct_id")``
#
# Any drift between the ORM model's index name and the Flyway DDL would
# cause Alembic autogenerate to emit a spurious DROP/CREATE cycle and
# potentially orphan the index on production upgrades.
_EXPECTED_ACCT_ID_INDEX_NAME: str = "ix_card_cross_reference_acct_id"

# Sample realistic CardCrossReference kwargs used by Phase 6 constructor /
# __repr__ tests. These values exercise leading-zero preservation for the
# two zero-padded numeric identifiers (COBOL PIC 9(n) -> SQLAlchemy
# String(n)) and a typical 16-digit PAN format for the card number.
#
# * card_num = "4000123456789010" — 16 chars, plausible test PAN.
# * cust_id  = "000123456"        — 9 chars, leading-zero-padded.
# * acct_id  = "00000012345"      — 11 chars, leading-zero-padded.
#
# All three widths match the COBOL PIC clause character counts, making
# these values legal byte-for-byte records for the 50-byte fixed-width
# VSAM layout (with 14 trailing bytes implicitly blank as FILLER).
_SAMPLE_CARD_NUM: str = "4000123456789010"
_SAMPLE_CUST_ID: str = "000123456"
_SAMPLE_ACCT_ID: str = "00000012345"


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """CardCrossReference must be mapped to the ``card_cross_references`` table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE card_cross_references``
    * ``db/migrations/V2__indexes.sql`` — ``CREATE INDEX
      ix_card_cross_reference_acct_id ON card_cross_references(acct_id)``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO
      card_cross_references`` (50 seed rows from
      ``app/data/ASCII/cardxref.txt``)
    * The online programs (``COACTVWC``, ``COCRDLIC``, ``COTRN02C``)
      and batch stage (``CBTRN02C`` POSTTRAN) that resolve a card
      number to its owning account ID via this cross-reference table.

    Any drift between ``CardCrossReference.__tablename__`` and the DDL
    or seed-data contract would cause runtime ``UndefinedTable`` errors
    in services and Glue jobs, so this invariant is pinned.

    Note
    ----
    The table name is *plural* (``card_cross_references``) following
    the project-wide relational-naming convention shared by every
    sibling entity except :class:`~src.shared.models.user_security.UserSecurity`
    (which retains its historical singular VSAM dataset name). The
    singular form ``card_cross_reference`` is NOT accepted — that
    would break the Flyway migrations already in place under
    ``db/migrations/`` and cause runtime ``UndefinedTable`` errors in
    every dependent service and Glue job. The index name
    ``ix_card_cross_reference_acct_id`` does intentionally use the
    singular entity form per the ``ix_<entity>_<column>`` naming
    pattern — this is a naming convention for the index, not for the
    table.
    """
    assert CardCrossReference.__tablename__ == "card_cross_references", (
        f"CardCrossReference.__tablename__ must be 'card_cross_references' "
        f"to match db/migrations/V1__schema.sql, V2__indexes.sql, and "
        f"V3__seed_data.sql; found {CardCrossReference.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """CardCrossReference must expose exactly 3 mapped columns.

    The COBOL ``CARD-XREF-RECORD`` layout has 4 fields, but only 3 are
    mapped to the relational model. ``FILLER PIC X(14)`` is deliberately
    dropped because trailing padding has no storage or semantic meaning
    in a column-typed schema.

    This is the **simplest entity in the CardDemo system** — just a
    16-character primary key plus two zero-padded numeric identifiers.
    Ensuring the count is exactly 3 guards against two regressions:

    * An accidental ``filler`` column being added back (would increase
      the count to 4).
    * A field being accidentally removed from the model (would
      decrease the count below 3, breaking the card-account-customer
      linkage that F-004 / F-006 / F-011 / POSTTRAN depend on).
    """
    columns = CardCrossReference.__table__.columns
    assert len(columns) == 3, (
        f"CardCrossReference must have exactly 3 columns (XREF-CARD-NUM, "
        f"XREF-CUST-ID, XREF-ACCT-ID — FILLER excluded); "
        f"found {len(columns)}: {[c.name for c in columns]}"
    )


@pytest.mark.unit
def test_primary_key_card_num() -> None:
    """The sole primary key is ``card_num`` (from COBOL ``XREF-CARD-NUM``).

    Maps to the VSAM KSDS 16-byte primary-key slot (offset 0) of the
    ``XREFFILE`` cluster. Replaces the mainframe VSAM ``DEFINE CLUSTER
    KEYS(16 0)`` clause from ``app/jcl/XREFFILE.jcl`` one-for-one.

    Verifies via :func:`sqlalchemy.inspect` that:

    * ``card_num`` is the sole (single) primary-key column — no
      composite key. The COBOL source defines only one key field for
      the XREFFILE cluster.
    * The PK column name is exactly ``card_num``.
    * The PK column's type is ``String(16)`` — matching the COBOL
      ``PIC X(16)`` original width exactly. Stored as text so that the
      original 16-character card-number representation is preserved
      byte-for-byte (numeric / integer types would risk losing leading
      zeros or overflowing on 16-digit PAN values, which exceed
      ``int32`` range).

    The single-column PK is the simplest primary key shape among the
    11 CardDemo entities. This test's primary value is catching
    regressions where a well-meaning refactor accidentally adds a
    second PK column (turning the primary key into a composite) or
    changes the column type / width.
    """
    primary_keys = list(inspect(CardCrossReference).primary_key)

    # Exactly one PK column (no composite key for CardCrossReference).
    assert len(primary_keys) == 1, (
        f"CardCrossReference must have exactly one primary key column "
        f"(XREF-CARD-NUM); found {len(primary_keys)}: "
        f"{[pk.name for pk in primary_keys]}"
    )

    pk_column = primary_keys[0]
    assert pk_column.name == "card_num", (
        f"Primary key column must be 'card_num' (from COBOL XREF-CARD-NUM PIC X(16)); found '{pk_column.name}'"
    )

    # PK type validation — must be String(16) to match COBOL PIC X(16).
    assert isinstance(pk_column.type, String), (
        f"Primary key 'card_num' must be SQLAlchemy String; found {type(pk_column.type).__name__}"
    )
    assert pk_column.type.length == 16, (
        f"Primary key 'card_num' must be String(16) (from COBOL "
        f"XREF-CARD-NUM PIC X(16)); found String({pk_column.type.length})"
    )


# ============================================================================
# Phase 3: Column Type Tests
# ============================================================================


@pytest.mark.unit
def test_card_num_type() -> None:
    """``card_num`` column is ``String(16)`` (from COBOL XREF-CARD-NUM PIC X(16)).

    Preserves the original 16-character card-number field width
    byte-for-byte. Matches:

    * The COBOL copybook ``CVACT03Y.cpy`` ``XREF-CARD-NUM PIC X(16)``.
    * The VSAM cluster key length declared in ``app/jcl/XREFFILE.jcl``
      (``KEYS(16 0)``).
    * The sibling :class:`~src.shared.models.card.Card` entity's
      ``card_num`` PK column (``CVACT02Y.cpy`` ``CARD-NUM PIC X(16)``)
      — enabling joins between the cross-reference and the card master
      table without type coercion.

    The 16-character width accommodates 16-digit credit-card PAN
    numbers (Mastercard, Visa, Discover) with no truncation or
    reformat. Storing the PAN as text (rather than integer) is
    required because:

    * 16-digit PAN values exceed the 10-digit ``int32`` range and the
      middle of the ``int64`` range where precision begins to matter
      for checksums (Luhn validation).
    * Leading-zero card numbers (rare in practice but legal per
      ISO/IEC 7812) must be preserved exactly.
    * Byte-for-byte fidelity to VSAM representations is required by
      AAP §0.7.1 ("preserve all existing functionality exactly
      as-is").

    This column is also the single primary key — this type check is
    complementary to :func:`test_primary_key_card_num` and catches the
    case where the column type was changed on the declaration without
    updating the PK definition.
    """
    column = CardCrossReference.__table__.columns["card_num"]
    assert isinstance(column.type, String), f"card_num must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 16, (
        f"card_num must be String(16) (from COBOL XREF-CARD-NUM PIC X(16)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_cust_id_type() -> None:
    """``cust_id`` column is ``String(9)`` (from COBOL XREF-CUST-ID PIC 9(09)).

    Although COBOL ``PIC 9(09)`` declares a **numeric** field, the
    SQLAlchemy model maps it to ``String(9)`` rather than ``Integer``.
    This is a deliberate design choice to preserve leading zeros in
    zero-padded customer IDs migrated from VSAM records (e.g., a
    customer ID of ``'000000001'`` would render as ``1`` if stored as
    integer, breaking the byte-for-byte round-trip to the
    ``app/data/ASCII/cardxref.txt`` seed fixture and the companion
    :class:`~src.shared.models.customer.Customer.cust_id` PK which is
    also ``String(9)``).

    Width is exactly 9 characters — matching:

    * The COBOL ``PIC 9(09)`` character count in ``CVACT03Y.cpy``.
    * The PK width of the :class:`~src.shared.models.customer.Customer`
      sibling entity (``CVCUS01Y.cpy`` ``CUST-ID PIC 9(09)``) — enables
      join queries between the cross-reference and the customer master
      table without type coercion.

    Note that this column is NOT declared as a SQL-level
    ``ForeignKey`` to ``customers.cust_id``. FK enforcement is managed
    by the service layer and the Flyway migrations in
    ``db/migrations/`` (see the "Design Notes" section of the ORM
    model module docstring). Removing the declarative FK keeps this
    module and its referenced peers independently loadable.

    The convention of "numeric COBOL ID -> String column" is applied
    uniformly across CardDemo: ``Account.acct_id``,
    ``Customer.cust_id``, ``TransactionCategoryBalance.acct_id``,
    ``DisclosureGroup.tran_cat_cd``, and so on. See the companion
    :func:`test_acct_id_type` for the matching argument on
    ``XREF-ACCT-ID``.
    """
    column = CardCrossReference.__table__.columns["cust_id"]
    assert isinstance(column.type, String), f"cust_id must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 9, (
        f"cust_id must be String(9) (from COBOL XREF-CUST-ID PIC 9(09), "
        f"stored as text to preserve leading zeros); "
        f"found String({column.type.length})"
    )


@pytest.mark.unit
def test_acct_id_type() -> None:
    """``acct_id`` column is ``String(11)`` (from COBOL XREF-ACCT-ID PIC 9(11)).

    Although COBOL ``PIC 9(11)`` declares a **numeric** field, the
    SQLAlchemy model maps it to ``String(11)`` rather than ``Integer``
    or ``BigInteger``. This is a deliberate design choice to preserve
    leading zeros in zero-padded account IDs migrated from VSAM
    records (e.g., ``'00000000001'`` would render as ``1`` if stored
    as integer, breaking the byte-for-byte round-trip to the
    ``app/data/ASCII/cardxref.txt`` seed fixture and the companion
    :class:`~src.shared.models.account.Account.acct_id` PK which is
    also ``String(11)``).

    Width is exactly 11 characters — matching:

    * The COBOL ``PIC 9(11)`` character count in ``CVACT03Y.cpy``.
    * The PK width of the :class:`~src.shared.models.account.Account`
      sibling entity (``CVACT01Y.cpy`` ``ACCT-ID PIC 9(11)``) —
      enables join queries between the cross-reference and the
      account master table without type coercion.
    * The legacy VSAM AIX key width (``XREFFILE.XREFAI02.PATH``
      NONUNIQUEKEY) carried across from the mainframe world.

    This column is also the target of the non-unique B-tree index
    ``ix_card_cross_reference_acct_id`` — see
    :func:`test_acct_id_index`. The combination of type +
    index directly replicates the VSAM alternate-index access pattern
    that underpins F-004 (Account View), F-006 (Card List), F-011
    (Transaction Add), and POSTTRAN batch card-to-account resolution.

    Note that this column is NOT declared as a SQL-level
    ``ForeignKey`` to ``accounts.acct_id`` — FK enforcement is managed
    at the service layer and by the Flyway migrations. See the
    "Design Notes" section of the ORM model module docstring.
    """
    column = CardCrossReference.__table__.columns["acct_id"]
    assert isinstance(column.type, String), f"acct_id must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 11, (
        f"acct_id must be String(11) (from COBOL XREF-ACCT-ID PIC 9(11), "
        f"stored as text to preserve leading zeros); "
        f"found String({column.type.length})"
    )


# ============================================================================
# Phase 4: Index Tests (VSAM AIX replacement)
# ============================================================================


@pytest.mark.unit
def test_acct_id_index() -> None:
    """CardCrossReference must declare a B-tree index on ``acct_id``.

    The index is:

    * **Named** ``ix_card_cross_reference_acct_id`` — matching the
      project-wide ``ix_<entity_singular>_<column>`` convention used
      throughout ``db/migrations/V2__indexes.sql`` and by the Alembic
      autogenerate diff. The exact name matters because migration
      scripts ``CREATE INDEX`` and ``DROP INDEX`` by name: a rename
      would cause Alembic autogenerate to emit a spurious DROP/CREATE
      cycle and potentially orphan the index on production upgrades.
    * **On the single column** ``acct_id`` — the owning-account ID
      which enables efficient "find all cards mapped to account N"
      lookups.
    * **Non-unique** — a single account can legitimately own multiple
      cards (primary, authorised user, replacement card, lost/stolen
      reissue, etc.), so ``unique=False`` (the default) is correct.
      This matches the ``NONUNIQUEKEY`` flag on the legacy VSAM
      alternate index ``XREFFILE.XREFAI02.PATH`` documented in
      ``app/catlg/LISTCAT.txt``.

    This index is the relational equivalent of the VSAM alternate
    index from the original mainframe cluster, and is critical for
    the following access patterns:

    * **F-004 Account View** (``COACTVWC.cbl`` ->
      ``account_service.view()``): discovers all cards on an account
      to assemble the consolidated 3-entity view (Account + Customer
      + Card).
    * **F-006 Card List** (``COCRDLIC.cbl`` ->
      ``card_service.list_by_account()``): produces the paginated
      7-rows-per-page card listing scoped to the currently-selected
      account.
    * **F-011 Transaction Add** (``COTRN02C.cbl`` ->
      ``transaction_service.add()``): resolves the user-supplied card
      number to its owning account ID before the new transaction is
      INSERTed.
    * **POSTTRAN Stage 1** (``CBTRN02C.cbl`` -> ``posttran_job.py``):
      bulk card-to-account resolution during daily transaction
      posting. Unresolved card numbers are rejected with reject
      code 102 as part of the 4-stage validation cascade.

    Any regression that drops or renames this index would degrade
    those queries from ``O(log n)`` index seek to ``O(n)`` table scan
    — unacceptable at production scale (the 50-row seed fixture
    becomes millions of rows in production with heavy access
    patterns from both online UI traffic and nightly batch jobs).
    """
    # ``CardCrossReference.__table__`` is typed as the abstract
    # :class:`sqlalchemy.sql.expression.FromClause` by the SQLAlchemy
    # 2.x declarative base, but at runtime it is always a concrete
    # :class:`sqlalchemy.Table` (which carries the ``.indexes``
    # collection). The ``attr-defined`` type-ignore here is the
    # canonical workaround for this well-known SQLAlchemy 2.x typing
    # gap — the alternative (casting to ``Table``) would require an
    # additional import (``from sqlalchemy import Table``) that is
    # not declared in this test module's external-imports schema.
    indexes = list(CardCrossReference.__table__.indexes)  # type: ignore[attr-defined]

    # Locate the index by name. Use ``next`` + ``None`` default so we
    # can emit a rich diagnostic if the index is missing entirely.
    matched = next(
        (idx for idx in indexes if idx.name == _EXPECTED_ACCT_ID_INDEX_NAME),
        None,
    )
    assert matched is not None, (
        f"CardCrossReference must declare an index named "
        f"{_EXPECTED_ACCT_ID_INDEX_NAME!r} on the acct_id column "
        f"(replacing the legacy VSAM AIX XREFFILE.XREFAI02.PATH "
        f"from app/jcl/XREFFILE.jcl / app/catlg/LISTCAT.txt); "
        f"found indexes with names "
        f"{sorted(idx.name for idx in indexes if idx.name)!r}"
    )

    # Exactly one column — the single-column B-tree.
    indexed_columns = list(matched.columns)
    assert len(indexed_columns) == 1, (
        f"Index {_EXPECTED_ACCT_ID_INDEX_NAME!r} must be a "
        f"single-column B-tree on acct_id (matching VSAM AIX "
        f"XREFFILE.XREFAI02.PATH which had a single AIX key on "
        f"XREF-ACCT-ID); found {len(indexed_columns)} columns: "
        f"{[c.name for c in indexed_columns]!r}"
    )

    # The single column must be ``acct_id``.
    indexed_column_name = indexed_columns[0].name
    assert indexed_column_name == "acct_id", (
        f"Index {_EXPECTED_ACCT_ID_INDEX_NAME!r} must be on the "
        f"'acct_id' column (the relational target of COBOL "
        f"XREF-ACCT-ID PIC 9(11)); found column "
        f"{indexed_column_name!r}"
    )

    # Non-unique — a single account can own multiple cards, matching
    # NONUNIQUEKEY in the legacy IDCAMS XREFFILE.XREFAI02.PATH
    # alternate index definition. ``unique=False`` is the default for
    # SQLAlchemy ``Index(...)`` when no ``unique=`` kwarg is supplied,
    # so on a correctly-declared model this attribute will be either
    # exactly ``False`` or ``None`` (both of which mean non-unique).
    assert not matched.unique, (
        f"Index {_EXPECTED_ACCT_ID_INDEX_NAME!r} must be non-unique "
        f"(a single account can own multiple cards — primary, "
        f"authorised user, replacement, etc. — matching NONUNIQUEKEY "
        f"in the legacy IDCAMS XREFFILE.XREFAI02.PATH AIX); "
        f"found unique={matched.unique}"
    )


# ============================================================================
# Phase 5: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 3 mapped columns must be declared NOT NULL.

    The COBOL ``CARD-XREF-RECORD`` layout has no ``OCCURS ... DEPENDING
    ON`` clauses and no ``REDEFINES`` — every field is present on
    every record. Translating that semantics to the relational model
    means every column is mandatory (``nullable=False``):

    * ``card_num`` — required (primary key; NULL PKs are rejected at
      the SQL level anyway, but the explicit assertion here catches
      accidental ``primary_key=True`` removal regressions).
    * ``cust_id``  — required (every card MUST link to an owning
      customer — a cross-reference row with a NULL customer ID would
      orphan the card and break F-004 Account View's 3-entity join).
    * ``acct_id``  — required (every card MUST link to an owning
      account — a cross-reference row with a NULL account ID would
      break POSTTRAN's card-to-account resolution and F-011
      Transaction Add, and would leave the ``acct_id`` B-tree index
      with a non-indexable tombstone).

    SQLAlchemy automatically sets ``nullable=False`` on any column
    marked ``primary_key=True``, but this test asserts the invariant
    explicitly on every column so that accidentally dropping
    ``nullable=False`` (or unmarking the PK) triggers an obvious
    failure. For ``cust_id`` and ``acct_id``, the check is a direct
    sanity-check of the explicit ``nullable=False`` kwarg on the
    ``mapped_column`` declaration.
    """
    for column_name in _EXPECTED_COLUMNS:
        column = CardCrossReference.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column '{column_name}' must be NOT NULL "
            f"(every COBOL CARD-XREF-RECORD field is mandatory); "
            f"nullable={column.nullable}"
        )


# ============================================================================
# Phase 6: Instance Creation Tests
# ============================================================================


@pytest.mark.unit
def test_create_xref_instance() -> None:
    """A CardCrossReference instance can be constructed with all 3 fields.

    Exercises the SQLAlchemy 2.x ``__init__`` synthesized from the
    :class:`~sqlalchemy.orm.Mapped` declarations in the ORM class.
    All field values correspond 1-to-1 to the COBOL
    ``CARD-XREF-RECORD`` record layout:

    * ``card_num='4000123456789010'`` — 16 chars (COBOL
      ``XREF-CARD-NUM PIC X(16)``) — a plausible test PAN in the
      Visa BIN range, well within the standard 13-19 digit PAN
      window specified by ISO/IEC 7812.
    * ``cust_id='000123456'``         — 9 chars (COBOL
      ``XREF-CUST-ID PIC 9(09)``) — includes three leading zeros to
      prove the ``String(9)`` type preserves zero padding, which an
      ``Integer`` column would silently strip.
    * ``acct_id='00000012345'``       — 11 chars (COBOL
      ``XREF-ACCT-ID PIC 9(11)``) — includes six leading zeros to
      prove the ``String(11)`` type preserves zero padding, required
      for correct joins to ``accounts.acct_id`` which is also
      ``String(11)``.

    After construction, every field must read back **verbatim** —
    bit-for-bit equal to the input string. No ORM session or database
    round-trip is required for this test — it exercises pure
    in-memory object construction, which is the fastest possible
    smoke-test of the ORM class's Mapped-column synthesis.

    Also verifies that the constructed instance is a proper descendant
    of the shared declarative :class:`Base` — guarding against
    accidentally re-rooting the model on a different ``MetaData``
    during a refactor, which would de-register its table from the
    shared schema and break ``Base.metadata.create_all()``, Alembic
    autogenerate, and every sibling test that relies on the shared
    metadata.
    """
    xref = CardCrossReference(
        card_num=_SAMPLE_CARD_NUM,
        cust_id=_SAMPLE_CUST_ID,
        acct_id=_SAMPLE_ACCT_ID,
    )

    # The entity must descend from the shared declarative base so that
    # its table registers on the shared MetaData used by Alembic /
    # Flyway / test fixtures.
    assert isinstance(xref, Base), (
        "CardCrossReference must be a subclass of src.shared.models.Base "
        "so that its table registers on the shared MetaData."
    )

    # Field-by-field readback — every value must round-trip verbatim.
    assert xref.card_num == _SAMPLE_CARD_NUM, (
        f"card_num readback mismatch: expected {_SAMPLE_CARD_NUM!r}, got {xref.card_num!r}"
    )
    assert xref.cust_id == _SAMPLE_CUST_ID, (
        f"cust_id readback mismatch: expected {_SAMPLE_CUST_ID!r}, got {xref.cust_id!r}"
    )
    assert xref.acct_id == _SAMPLE_ACCT_ID, (
        f"acct_id readback mismatch: expected {_SAMPLE_ACCT_ID!r}, got {xref.acct_id!r}"
    )

    # Leading-zero preservation invariants — the raison d'etre of the
    # ``String(n)`` mapping choice for the two numeric-COBOL IDs.
    # These assertions would fail if the model accidentally used
    # ``Integer`` or ``BigInteger`` columns (which would silently
    # strip leading zeros at construction time).
    assert xref.cust_id.startswith("000"), (
        f"cust_id must preserve leading zeros (stored as String(9), not integer); got {xref.cust_id!r}"
    )
    assert xref.acct_id.startswith("00000"), (
        f"acct_id must preserve leading zeros (stored as String(11), not integer); got {xref.acct_id!r}"
    )

    # Width invariants — the 3 values must exactly match the COBOL
    # PIC clause character counts to remain round-trip-compatible
    # with migrated VSAM records.
    assert len(xref.card_num) == 16, f"card_num must be exactly 16 chars (PIC X(16)); got len={len(xref.card_num)}"
    assert len(xref.cust_id) == 9, f"cust_id must be exactly 9 chars (PIC 9(09)); got len={len(xref.cust_id)}"
    assert len(xref.acct_id) == 11, f"acct_id must be exactly 11 chars (PIC 9(11)); got len={len(xref.acct_id)}"


@pytest.mark.unit
def test_xref_repr() -> None:
    """``__repr__`` returns a developer-friendly string including all 3 fields.

    Contract (enforced by ``CardCrossReference.__repr__`` in
    ``src/shared/models/card_cross_reference.py``):

    * MUST include the class name ``CardCrossReference``.
    * MUST include the ``card_num`` value (the primary key — the
      first identifying attribute a developer reading a traceback
      needs to know).
    * MUST include the ``cust_id`` value (so debugging output reveals
      the linked customer).
    * MUST include the ``acct_id`` value (so debugging output reveals
      the linked account).

    Unlike :class:`~src.shared.models.user_security.UserSecurity`
    (which deliberately hides the password hash in its ``__repr__``),
    ``CardCrossReference`` carries no credentials or PII — the full
    field set is safe to expose in log output and debugger
    inspections. A developer reading a traceback mentioning this
    entity should be able to identify the offending row by its
    card / customer / account triple without having to cross-reference
    a separate SELECT against the database.

    The assertion verifies the quoted form (e.g., ``'000123456'``
    rather than the bare ``000123456``) to reduce the risk of false
    positives — the expected ``__repr__`` format produced by the
    model's ``f"CardCrossReference(card_num={self.card_num!r}, ..."``
    is::

        CardCrossReference(card_num='4000123456789010',
                           cust_id='000123456',
                           acct_id='00000012345')

    so both the values and their repr quoting should be present.
    """
    xref = CardCrossReference(
        card_num=_SAMPLE_CARD_NUM,
        cust_id=_SAMPLE_CUST_ID,
        acct_id=_SAMPLE_ACCT_ID,
    )

    repr_output = repr(xref)

    # The output must be a non-empty string.
    assert isinstance(repr_output, str), f"__repr__ must return a str; got {type(repr_output).__name__}"
    assert repr_output, "__repr__ must return a non-empty string"

    # Required inclusions — the class name must appear so developers
    # scanning logs or tracebacks can identify the entity class.
    assert "CardCrossReference" in repr_output, (
        f"__repr__ must include the class name 'CardCrossReference'; got {repr_output!r}"
    )

    # Each field value must appear (with repr quoting) so developers
    # can identify the specific row without a database round-trip.
    assert _SAMPLE_CARD_NUM in repr_output, (
        f"__repr__ must include the card_num value {_SAMPLE_CARD_NUM!r}; got {repr_output!r}"
    )
    assert _SAMPLE_CUST_ID in repr_output, (
        f"__repr__ must include the cust_id value {_SAMPLE_CUST_ID!r}; got {repr_output!r}"
    )
    assert _SAMPLE_ACCT_ID in repr_output, (
        f"__repr__ must include the acct_id value {_SAMPLE_ACCT_ID!r}; got {repr_output!r}"
    )


# ============================================================================
# Phase 7: FILLER Exclusion Test
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """No column maps the COBOL ``FILLER PIC X(14)`` padding.

    COBOL fixed-width records routinely use ``FILLER`` regions to pad
    to a target record length. In ``CVACT03Y.cpy`` the trailing
    ``FILLER PIC X(14)`` brings the total record length to:

        XREF-CARD-NUM (16) + XREF-CUST-ID (9) + XREF-ACCT-ID (11) + FILLER (14) = 50

    matching the VSAM cluster's ``RECSZ(50 50)`` declaration in
    ``app/jcl/XREFFILE.jcl`` and the IDCAMS catalog entry in
    ``app/catlg/LISTCAT.txt``.

    These padding regions exist purely as storage artifacts of the
    fixed-width on-disk VSAM format — they carry no semantic data and
    have no equivalent in a typed relational schema (PostgreSQL's
    row-storage layout is tuple-based, and column widths are explicit
    per-column rather than a single fixed per-row total).

    This test performs two checks:

    1. **Positive set-equality**: the exact set of mapped columns
       matches the expected 3-element contract
       (``{card_num, cust_id, acct_id}``). This catches accidental
       additions (extra columns) and removals (missing columns) in
       one pass. Because the model has only 3 columns, a full
       set-equality check is both feasible and informative.

    2. **Negative substring guard**: no column name contains the
       substring ``filler`` (case-insensitive). This catches common
       naming variants including ``filler``, ``xref_filler``,
       ``record_filler``, etc. — any of which would indicate the
       translator re-introduced the COBOL padding field by mistake.
    """
    column_names: list[str] = [c.name for c in CardCrossReference.__table__.columns]

    # Positive: the exact set of mapped columns must match the
    # 3-element contract.
    assert set(column_names) == set(_EXPECTED_COLUMNS), (
        f"Column set drift detected. Expected: {sorted(_EXPECTED_COLUMNS)}; found: {sorted(column_names)}"
    )

    # Negative: no column name may contain the substring 'filler' in
    # any casing. This guards against future regressions where a
    # copybook-to-model translator accidentally emits a filler column.
    for column_name in column_names:
        assert "filler" not in column_name.lower(), (
            f"Column '{column_name}' appears to map a COBOL FILLER "
            f"region. FILLER fields (like the trailing PIC X(14) in "
            f"CVACT03Y.cpy) are padding only and MUST NOT be mapped "
            f"to the relational model."
        )
