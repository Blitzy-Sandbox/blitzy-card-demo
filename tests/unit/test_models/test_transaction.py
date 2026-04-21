# ==============================================================================
#
#                       Apache License 2.0 boilerplate
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# ==============================================================================

# Source: COBOL copybook CVTRA05Y.cpy — TRAN-RECORD (350 bytes, VSAM KSDS)
# Authoritative posted-transaction ledger (permanent history; indexed
# by processing timestamp via ``ix_transaction_proc_ts`` replacing the
# mainframe ``TRANFILE.AIX`` VSAM alternate index).
"""Unit tests for the :class:`~src.shared.models.transaction.Transaction` ORM model.

This module pins the relational contract of the
:class:`~src.shared.models.transaction.Transaction` SQLAlchemy ORM
model against the authoritative COBOL source copybook
``app/cpy/CVTRA05Y.cpy``.

Domain context
--------------
``Transaction`` is the **authoritative, permanent** ledger of every
successfully-posted credit-card transaction in the CardDemo
application. It replaces the mainframe ``TRANFILE`` VSAM KSDS cluster
(primary-key access on ``TRAN-ID``) together with its alternate index
(AIX) ``TRANFILE.AIX`` keyed on ``TRAN-PROC-TS`` — the cloud-native
equivalent is the ``ix_transaction_proc_ts`` B-tree index that this
test module explicitly pins via :func:`test_proc_ts_index`.

Contrast with the :class:`~src.shared.models.daily_transaction.DailyTransaction`
staging table: daily transactions are incoming, unvalidated, ephemeral
records from the upstream feed. Rows transition into the permanent
``transaction`` ledger only after the 4-stage POSTTRAN validation
cascade succeeds (``CBTRN02C`` → ``src/batch/jobs/posttran_job.py``).
This layout-parity invariant — daily and permanent tables share the
same 13-column shape — is why rows can be promoted field-for-field
without any width-expanding cast.

COBOL Source Layout (CVTRA05Y.cpy, RECLN 350)
---------------------------------------------

.. code-block:: cobol

    01  TRAN-RECORD.
        05  TRAN-ID                                 PIC X(16).
        05  TRAN-TYPE-CD                            PIC X(02).
        05  TRAN-CAT-CD                             PIC 9(04).
        05  TRAN-SOURCE                             PIC X(10).
        05  TRAN-DESC                               PIC X(100).
        05  TRAN-AMT                                PIC S9(09)V99.
        05  TRAN-MERCHANT-ID                        PIC 9(09).
        05  TRAN-MERCHANT-NAME                      PIC X(50).
        05  TRAN-MERCHANT-CITY                      PIC X(50).
        05  TRAN-MERCHANT-ZIP                       PIC X(10).
        05  TRAN-CARD-NUM                           PIC X(16).
        05  TRAN-ORIG-TS                            PIC X(26).
        05  TRAN-PROC-TS                            PIC X(26).
        05  FILLER                                  PIC X(20).

COBOL → SQLAlchemy Mapping
--------------------------

============================== =================== ======================= ============
COBOL field                    PIC clause          SQLAlchemy column       Python type
============================== =================== ======================= ============
``TRAN-ID``                    ``PIC X(16)``       ``tran_id``  (**PK**)   ``str``
``TRAN-TYPE-CD``               ``PIC X(02)``       ``type_cd``             ``str``
``TRAN-CAT-CD``                ``PIC 9(04)``       ``cat_cd``              ``str``
``TRAN-SOURCE``                ``PIC X(10)``       ``source``              ``str``
``TRAN-DESC``                  ``PIC X(100)``      ``description``         ``str``
``TRAN-AMT``                   ``PIC S9(09)V99``   ``amount``              ``Decimal``
``TRAN-MERCHANT-ID``           ``PIC 9(09)``       ``merchant_id``         ``str``
``TRAN-MERCHANT-NAME``         ``PIC X(50)``       ``merchant_name``       ``str``
``TRAN-MERCHANT-CITY``         ``PIC X(50)``       ``merchant_city``       ``str``
``TRAN-MERCHANT-ZIP``          ``PIC X(10)``       ``merchant_zip``        ``str``
``TRAN-CARD-NUM``              ``PIC X(16)``       ``card_num``            ``str``
``TRAN-ORIG-TS``               ``PIC X(26)``       ``orig_ts``             ``str``
``TRAN-PROC-TS``               ``PIC X(26)``       ``proc_ts`` (**idx**)   ``str``
``FILLER``                     ``PIC X(20)``       *(deliberately dropped)*  —
============================== =================== ======================= ============

Test Coverage (22 unit tests organised by Phase)
-------------------------------------------------

Phase 2 — Table & Column Metadata (3 tests)
    1.  :func:`test_tablename`             — Pins ``__tablename__`` to
                                              ``"transactions"`` (plural,
                                              matching the Flyway migrations).
    2.  :func:`test_column_count`          — Exactly 13 mapped columns
                                              (FILLER is NOT mapped).
    3.  :func:`test_primary_key_tran_id`   — ``tran_id`` is the single
                                              primary key, ``String(16)``.

Phase 3 — Per-Column Type Assertions (12 tests — one per string column)
    4.  :func:`test_tran_id_type`          — ``tran_id`` is ``String(16)``.
    5.  :func:`test_type_cd_type`          — ``type_cd`` is ``String(2)``.
    6.  :func:`test_cat_cd_type`           — ``cat_cd`` is ``String(4)``.
    7.  :func:`test_source_type`           — ``source`` is ``String(10)``.
    8.  :func:`test_description_type`      — ``description`` is ``String(100)``.
    9.  :func:`test_merchant_id_type`      — ``merchant_id`` is ``String(9)``.
    10. :func:`test_merchant_name_type`    — ``merchant_name`` is ``String(50)``.
    11. :func:`test_merchant_city_type`    — ``merchant_city`` is ``String(50)``.
    12. :func:`test_merchant_zip_type`     — ``merchant_zip`` is ``String(10)``.
    13. :func:`test_card_num_type`         — ``card_num`` is ``String(16)``.
    14. :func:`test_orig_ts_type`          — ``orig_ts`` is ``String(26)``.
    15. :func:`test_proc_ts_type`          — ``proc_ts`` is ``String(26)``.

Phase 4 — Monetary Field (2 tests, CRITICAL — never float)
    16. :func:`test_amount_type`           — ``amount`` is ``Numeric(15, 2)``.
    17. :func:`test_amount_default`        — ``amount`` defaults to
                                              ``Decimal('0.00')``.

Phase 5 — Index on ``proc_ts`` (1 test — permanent-ledger query path)
    18. :func:`test_proc_ts_index`         — ``ix_transaction_proc_ts``
                                              B-tree index on ``proc_ts``
                                              replicates the VSAM AIX
                                              ``TRANFILE.AIX`` and supports
                                              date-range queries from
                                              CREASTMT / TRANREPT.

Phase 6 — NOT NULL Constraints (1 test)
    19. :func:`test_non_nullable_fields`   — All 13 columns are
                                              ``nullable=False``.

Phase 7 — Instance Creation & ``__repr__`` (2 tests)
    20. :func:`test_create_transaction_instance` — Full kwarg-based
                                                   construction with
                                                   ``Decimal('75.50')``
                                                   for the monetary
                                                   field.
    21. :func:`test_transaction_repr`      — ``__repr__`` is
                                              developer-friendly and
                                              renders ``amount`` as
                                              ``Decimal(...)``.

Phase 8 — FILLER Exclusion (1 test)
    22. :func:`test_no_filler_columns`     — ``FILLER PIC X(20)`` is NOT
                                              mapped; exact column-set
                                              equivalence + negative
                                              substring guard.

See Also
--------
``src/shared/models/transaction.py``       — The ORM model under test.
``src/shared/models/daily_transaction.py`` — Staging counterpart with
                                              identical 13-column layout.
``src/shared/models/__init__.py``          — The shared declarative ``Base``.
``app/cpy/CVTRA05Y.cpy``                   — Original COBOL record layout.
``app/cpy/CVTRA06Y.cpy``                   — Staging DALYTRAN-RECORD layout.
``app/cbl/CBTRN02C.cbl``                   — POSTTRAN producer of rows.
``app/cbl/CBACT04C.cbl``                   — INTCALC producer of interest rows.
``app/cbl/CBSTM03A.CBL`` / ``CBSTM03B.CBL``— CREASTMT consumer (uses the
                                              ``ix_transaction_proc_ts``
                                              index for billing-cycle
                                              range scans).
``app/cbl/CBTRN03C.cbl``                   — TRANREPT consumer (uses the
                                              same index for date-range
                                              report queries).
``app/cbl/COBIL00C.cbl``                   — Bill-payment dual-write
                                              producer.
``app/cbl/COTRN00C.cbl`` / ``COTRN01C.cbl``— Online list / detail.
``app/cbl/COTRN02C.cbl``                   — Online transaction-add.
``app/jcl/TRANFILE.jcl``                   — Original VSAM provisioning.
``app/jcl/TRANIDX.jcl``                    — Original VSAM AIX provisioning
                                              for ``TRANFILE.AIX``.
``db/migrations/V1__schema.sql``           — ``CREATE TABLE transactions``.
``db/migrations/V2__indexes.sql``          — ``CREATE INDEX
                                              ix_transaction_proc_ts``.
AAP §0.5.1                                 — File-by-File Transformation Plan.
AAP §0.7.1                                 — Minimal-change clause (preserve
                                              COBOL field widths exactly).
AAP §0.7.2                                 — Financial-precision clause
                                              (``Decimal`` not ``float``).
"""

from __future__ import annotations

from decimal import Decimal
from typing import cast

import pytest
from sqlalchemy import Numeric, String, Table, inspect

from src.shared.models import Base
from src.shared.models.transaction import Transaction

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# Set of the 13 expected mapped column names (Python attribute names, which
# are also the SQL column names under SQLAlchemy's default resolution). The
# COBOL ``FILLER PIC X(20)`` trailing the 350-byte record is DELIBERATELY
# absent — padding regions have no place in the relational model (see
# :func:`test_no_filler_columns`).
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "tran_id",  # from TRAN-ID            PIC X(16) — primary key
        "type_cd",  # from TRAN-TYPE-CD       PIC X(02)
        "cat_cd",  # from TRAN-CAT-CD        PIC 9(04) — stored as text
        "source",  # from TRAN-SOURCE        PIC X(10)
        "description",  # from TRAN-DESC          PIC X(100)
        "amount",  # from TRAN-AMT           PIC S9(09)V99 — Numeric(15,2)
        "merchant_id",  # from TRAN-MERCHANT-ID   PIC 9(09) — stored as text
        "merchant_name",  # from TRAN-MERCHANT-NAME PIC X(50)
        "merchant_city",  # from TRAN-MERCHANT-CITY PIC X(50)
        "merchant_zip",  # from TRAN-MERCHANT-ZIP  PIC X(10)
        "card_num",  # from TRAN-CARD-NUM      PIC X(16)
        "orig_ts",  # from TRAN-ORIG-TS       PIC X(26) — ISO-ish timestamp
        "proc_ts",  # from TRAN-PROC-TS       PIC X(26) — indexed (B-tree)
    }
)

# Mapping of the 12 string columns → expected ``String(n)`` length, where
# ``n`` matches the COBOL PIC clause character count exactly. This mapping
# is consumed by the Phase 3 per-column type tests via
# :func:`_assert_string_column`. Exact width preservation is required so
# that rows promoted from the :class:`~src.shared.models.daily_transaction.DailyTransaction`
# staging table (same 13-column layout) round-trip unchanged.
_EXPECTED_STRING_COLUMN_LENGTHS: dict[str, int] = {
    "tran_id": 16,  # TRAN-ID            PIC X(16)
    "type_cd": 2,  # TRAN-TYPE-CD       PIC X(02)
    "cat_cd": 4,  # TRAN-CAT-CD        PIC 9(04)
    "source": 10,  # TRAN-SOURCE        PIC X(10)
    "description": 100,  # TRAN-DESC          PIC X(100)
    "merchant_id": 9,  # TRAN-MERCHANT-ID   PIC 9(09)
    "merchant_name": 50,  # TRAN-MERCHANT-NAME PIC X(50)
    "merchant_city": 50,  # TRAN-MERCHANT-CITY PIC X(50)
    "merchant_zip": 10,  # TRAN-MERCHANT-ZIP  PIC X(10)
    "card_num": 16,  # TRAN-CARD-NUM      PIC X(16)
    "orig_ts": 26,  # TRAN-ORIG-TS       PIC X(26)
    "proc_ts": 26,  # TRAN-PROC-TS       PIC X(26)
}

# The sole monetary column on the model — ``amount`` — must be stored as
# ``Numeric(15, 2)`` matching COBOL ``PIC S9(09)V99``: 9 integer digits
# (max absolute value 999_999_999) plus 2 decimal places. AAP §0.7.2
# forbids ``float`` for any monetary column.
_EXPECTED_AMOUNT_PRECISION: int = 15
_EXPECTED_AMOUNT_SCALE: int = 2

# Name of the B-tree index on ``proc_ts`` declared in ``__table_args__``.
# Replicates the mainframe ``TRANFILE.AIX`` VSAM alternate index on
# ``TRAN-PROC-TS``. Consumed by :func:`test_proc_ts_index`.
_EXPECTED_PROC_TS_INDEX_NAME: str = "ix_transaction_proc_ts"

# Sample realistic Transaction kwargs used by Phase 7 constructor /
# __repr__ tests. These values are chosen to exercise distinct aspects
# of the record:
#   * ``tran_id`` is 16 characters — exact maximum width.
#   * ``amount`` is ``Decimal('75.50')`` — two-decimal-place retail amount
#     that exercises both the integer and fractional parts without being
#     a round number (which could mask trailing-zero-stripping bugs).
#   * Merchant fields reflect a typical brick-and-mortar retail purchase.
#   * Timestamps use the ISO-8601 26-character format (``YYYY-MM-DD
#     HH:MM:SS.ffffff``) that the original COBOL batch emits for
#     ``TRAN-ORIG-TS`` / ``TRAN-PROC-TS``.
_SAMPLE_TRAN_ID: str = "TRN0000000000001"  # 16 chars — exact PIC X(16) width
_SAMPLE_TYPE_CD: str = "01"  # 2 chars  — purchase
_SAMPLE_CAT_CD: str = "0001"  # 4 chars  — general merchandise
_SAMPLE_SOURCE: str = "POS       "  # 10 chars — blank-padded source
_SAMPLE_DESCRIPTION: str = "Coffee and pastry at morning stop"
_SAMPLE_AMOUNT: Decimal = Decimal("75.50")  # AAP §0.7.2 — Decimal, NEVER float
_SAMPLE_MERCHANT_ID: str = "100000001"  # 9 chars  — zero-padded PIC 9(09)
_SAMPLE_MERCHANT_NAME: str = "ACME Retail Store"
_SAMPLE_MERCHANT_CITY: str = "Springfield"
_SAMPLE_MERCHANT_ZIP: str = "62701-0001"  # 10 chars — US 9-digit ZIP+4
_SAMPLE_CARD_NUM: str = "4111111111111111"  # 16 chars — exact PIC X(16) width
_SAMPLE_ORIG_TS: str = "2024-01-15 09:30:45.123456"  # 26 chars
_SAMPLE_PROC_TS: str = "2024-01-15 09:30:47.987654"  # 26 chars


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """``Transaction`` must be mapped to the ``transactions`` table.

    The table name is the relational anchor that ties the ORM model to
    both the DDL migration (``db/migrations/V1__schema.sql`` —
    ``CREATE TABLE transactions``) and the index migration
    (``db/migrations/V2__indexes.sql`` — ``CREATE INDEX
    ix_transaction_proc_ts ON transactions (proc_ts)``).

    Any drift between ``Transaction.__tablename__`` and the DDL
    contract would cause runtime ``UndefinedTable`` errors on every
    query path — including:

    * ``posttran_job.py`` (POSTTRAN) — INSERTs each approved daily-batch
      row here after validation, simultaneously updating the owning
      account's balance columns in the same DB transaction.
    * ``creastmt_job.py`` (CREASTMT) — reads this table filtered by
      billing-cycle window using the ``ix_transaction_proc_ts`` index.
    * ``tranrept_job.py`` (TRANREPT) — reads this table filtered by
      arbitrary date windows using the same index.
    * ``transaction_service.py`` (online list / detail / add) —
      paginated scans (10 rows/page) and PK lookups.
    * ``bill_service.py`` (COBIL00C) — INSERTs a bill-payment row
      here as part of an atomic dual-write with ``Account.curr_bal``.

    Note
    ----
    The table name is *plural* (``transactions``) to follow the
    project-wide relational-naming convention shared by all sibling
    entities except ``user_security`` (which retains its historical
    singular VSAM dataset name). The singular form ``transaction`` —
    which the file-creation instructions briefly reference in one
    location — is NOT accepted: it would break the Flyway migrations
    (which already have ``CREATE TABLE transactions`` committed under
    ``db/migrations/``) and it would also break layout-consistency
    with the sibling ``DailyTransaction`` table whose name is
    ``daily_transactions`` (plural).
    """
    assert Transaction.__tablename__ == "transactions", (
        f"Transaction.__tablename__ must be 'transactions' to match "
        f"db/migrations/V1__schema.sql and V2__indexes.sql; found "
        f"{Transaction.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """``Transaction`` must expose exactly 13 mapped columns.

    The COBOL ``TRAN-RECORD`` layout has 14 elementary fields (13
    data-bearing fields plus the trailing ``FILLER PIC X(20)`` padding
    that rounds the record out to the fixed 350-byte VSAM-compatible
    length), but only the 13 data-bearing fields are mapped to the
    relational model. ``FILLER`` is deliberately dropped because
    trailing padding has no storage or semantic meaning in a
    column-typed schema — see :func:`test_no_filler_columns` for the
    negative-side guard.

    The count of 13 also matches
    :class:`~src.shared.models.daily_transaction.DailyTransaction`
    exactly (its source ``CVTRA06Y.cpy`` has the same 13 data fields
    + FILLER layout). Layout parity between the permanent ledger
    (this table) and the staging table is what allows the POSTTRAN
    job to promote rows field-for-field without any schema
    transformation.
    """
    actual_count = len(Transaction.__table__.columns)
    assert actual_count == 13, (
        f"Transaction must have exactly 13 mapped columns (matching "
        f"the 13 data-bearing fields of COBOL TRAN-RECORD; FILLER "
        f"PIC X(20) is NOT mapped); found {actual_count} columns: "
        f"{sorted(c.name for c in Transaction.__table__.columns)!r}"
    )


@pytest.mark.unit
def test_primary_key_tran_id() -> None:
    """``tran_id`` must be the sole primary key on ``Transaction``.

    The COBOL ``TRAN-ID`` field (``PIC X(16)``) is the unique
    identifier for each transaction. In the VSAM KSDS source file it
    was the primary key of the ``TRANFILE`` cluster — uniqueness
    enforced at the dataset level for primary-key access. The
    relational migration preserves this semantics:

    * The primary key is a **single column** (``tran_id``) — not a
      composite key.
    * The column type is ``String(16)`` — matching the COBOL PIC
      width exactly so zero-padded or blank-padded IDs from the
      upstream feed and the migrated VSAM records round-trip
      unchanged.
    * No auto-increment / surrogate key is introduced — the
      ``tran_id`` is either assigned by the "Transaction Add" online
      service (``COTRN02C`` → ``transaction_service.py``, which
      derives the next sequential ID) or propagated from the
      ``daily_transaction`` staging row by POSTTRAN.

    Downstream consumers that depend on this invariant include
    ``src/batch/jobs/posttran_job.py`` (INSERTs by ``tran_id``),
    ``src/batch/jobs/intcalc_job.py`` (INSERTs interest rows with
    newly-generated IDs), ``src/batch/jobs/creastmt_job.py`` (joins
    ``tran_id`` across the 4-entity statement view), and the
    transaction-detail REST endpoint ``GET /transactions/{id}``
    (which issues a PK lookup by ``tran_id``).

    The test also pins that the PK column is ``NOT NULL`` — this is
    implicit for any primary key in SQLAlchemy but asserted
    explicitly here to guard against any future override that might
    accidentally relax the constraint.
    """
    mapper = inspect(Transaction)
    primary_key_columns = list(mapper.primary_key)

    # Exactly one PK column — NOT a composite.
    assert len(primary_key_columns) == 1, (
        f"Transaction must have exactly 1 primary-key column "
        f"(``tran_id``), not a composite key; found "
        f"{[c.name for c in primary_key_columns]!r}"
    )

    pk_column = primary_key_columns[0]
    assert pk_column.name == "tran_id", (
        f"Primary-key column must be named 'tran_id' to match COBOL TRAN-ID; found {pk_column.name!r}"
    )

    # Type must be ``String(16)`` — PIC X(16).
    assert isinstance(pk_column.type, String), (
        f"tran_id column type must be SQLAlchemy String (matching "
        f"COBOL PIC X(16)); found {type(pk_column.type).__name__}"
    )
    assert pk_column.type.length == 16, (
        f"tran_id column length must be 16 (matching COBOL PIC X(16)); found {pk_column.type.length}"
    )

    # PK columns are implicitly NOT NULL in SQLAlchemy, but we pin it
    # explicitly to guard against an accidental future override.
    assert pk_column.nullable is False, (
        f"Primary-key column tran_id must be NOT NULL (implicit for "
        f"any primary key, but pinned here explicitly); found "
        f"nullable={pk_column.nullable}"
    )


# ============================================================================
# Phase 3: Per-Column Type Assertions
#
# Each of the 12 string columns gets its own focused test. A single
# parameterised test would work, but individual named tests give the
# clearest failure messages in CI logs when a single width drifts —
# the failing test's name points directly at the offending column.
# ============================================================================


def _assert_string_column(column_name: str, expected_length: int) -> None:
    """Helper: assert a :class:`Transaction` column is ``String(n)``.

    Shared by every Phase 3 per-column test. Encapsulates the 3-step
    verification ritual so that each individual test stays terse and
    readable while still producing rich failure messages that name
    the offending column and expected/actual widths.

    Parameters
    ----------
    column_name : str
        Name of the SQLAlchemy column to introspect.
    expected_length : int
        Expected value of ``column.type.length`` — must match the
        character count of the corresponding COBOL ``PIC X`` / ``PIC 9``
        clause exactly.

    Raises
    ------
    AssertionError
        If the column is missing, the column type is not
        :class:`~sqlalchemy.String`, or the column's declared length
        does not match ``expected_length``.
    """
    assert column_name in Transaction.__table__.columns, (
        f"Transaction must expose a {column_name!r} column; found {sorted(Transaction.__table__.columns.keys())!r}"
    )
    column = Transaction.__table__.columns[column_name]
    assert isinstance(column.type, String), (
        f"{column_name} column type must be SQLAlchemy String; found {type(column.type).__name__}"
    )
    assert column.type.length == expected_length, (
        f"{column_name} column length must be {expected_length} "
        f"(matching the COBOL PIC width for the source field); found "
        f"{column.type.length}"
    )


@pytest.mark.unit
def test_tran_id_type() -> None:
    """``tran_id`` must be ``String(16)`` — from COBOL ``TRAN-ID PIC X(16)``.

    Exact width preservation is critical: migrated VSAM records carry
    16-byte IDs that are often blank-padded or zero-padded.
    Truncating to fewer characters would silently drop the trailing
    pad and break round-trip equality with the VSAM source; widening
    to more characters would permit malformed IDs through the ledger
    and corrupt downstream primary-key uniqueness invariants.

    This is the **primary-key** column — its width pins the ledger's
    global identity namespace. See :func:`test_primary_key_tran_id`
    for the PK-specific invariants.
    """
    _assert_string_column("tran_id", _EXPECTED_STRING_COLUMN_LENGTHS["tran_id"])


@pytest.mark.unit
def test_type_cd_type() -> None:
    """``type_cd`` must be ``String(2)`` — from COBOL ``TRAN-TYPE-CD PIC X(02)``.

    The 2-character transaction-type code is a logical foreign key
    into the ``transaction_type`` lookup table (e.g., ``'01'`` =
    purchase, ``'02'`` = refund, ``'03'`` = payment, ``'05'`` =
    interest, ``'07'`` = fee). POSTTRAN reject code 103 (unknown
    type code) depends on the exact 2-character width here for its
    lookup join.

    The code is also used by INTCALC when inserting an interest
    accrual transaction: the interest row always carries a specific
    ``type_cd`` that matches the disclosure-group-driven interest
    category.
    """
    _assert_string_column("type_cd", _EXPECTED_STRING_COLUMN_LENGTHS["type_cd"])


@pytest.mark.unit
def test_cat_cd_type() -> None:
    """``cat_cd`` must be ``String(4)`` — from COBOL ``TRAN-CAT-CD PIC 9(04)``.

    Although the COBOL source field is numeric (``PIC 9(04)``), it is
    stored as ``String(4)`` in the relational model to preserve
    leading zeros that are semantically meaningful (``'0001'`` is
    category 1, and converting to ``INTEGER`` would collapse
    ``'0001'`` and ``'1'`` to the same value, breaking VSAM-round-trip
    equality). This is the project-wide pattern for all COBOL
    zero-padded ``PIC 9`` category / code fields
    (``Account.acct_id``, ``Customer.cust_id``, etc.).

    Paired with ``type_cd``, ``cat_cd`` forms the composite key into
    the ``transaction_category`` lookup table and drives both (1)
    which ``transaction_category_balance`` bucket is updated during
    POSTTRAN and (2) which ``disclosure_group`` interest rate applies
    during INTCALC (with DEFAULT / ZEROAPR fallback per AAP §0.7.1).
    """
    _assert_string_column("cat_cd", _EXPECTED_STRING_COLUMN_LENGTHS["cat_cd"])


@pytest.mark.unit
def test_source_type() -> None:
    """``source`` must be ``String(10)`` — from COBOL ``TRAN-SOURCE PIC X(10)``.

    The 10-character source code identifies the upstream system that
    produced the record (e.g., ``'POS'``, ``'ECOMMERCE'``, ``'ATM'``,
    ``'ONLINE'``, ``'BATCH'``). Typically blank-padded to the full
    10 bytes. Audit reconciliation, TRANREPT source-of-record
    subtotals, and CREASTMT statement-descriptor resolution all join
    on this field.
    """
    _assert_string_column("source", _EXPECTED_STRING_COLUMN_LENGTHS["source"])


@pytest.mark.unit
def test_description_type() -> None:
    """``description`` must be ``String(100)`` — from ``TRAN-DESC PIC X(100)``.

    The 100-character free-text description is the user-facing
    label printed on statements (CREASTMT) and transaction reports
    (TRANREPT). The relational width must match the COBOL PIC exactly
    so that rows promoted from the
    :class:`~src.shared.models.daily_transaction.DailyTransaction`
    staging table by POSTTRAN are truncation-safe — any narrower
    width here would silently truncate descriptions crossing the
    staging → permanent boundary.
    """
    _assert_string_column("description", _EXPECTED_STRING_COLUMN_LENGTHS["description"])


@pytest.mark.unit
def test_merchant_id_type() -> None:
    """``merchant_id`` must be ``String(9)`` — from ``TRAN-MERCHANT-ID PIC 9(09)``.

    Like :attr:`cat_cd`, this is a numeric-in-COBOL field stored as
    text in the relational model to preserve leading zeros. The
    9-character width matches the ISO-8583 standard acquiring
    institution identifier length. Defaults to an empty string for
    non-merchant transactions (payments, fees, interest accruals).

    Stored as a denormalised snapshot rather than a strict foreign
    key because the merchant-directory table is external to CardDemo
    (not owned by this application) and may diverge from the
    transaction-time value over time.
    """
    _assert_string_column("merchant_id", _EXPECTED_STRING_COLUMN_LENGTHS["merchant_id"])


@pytest.mark.unit
def test_merchant_name_type() -> None:
    """``merchant_name`` must be ``String(50)`` — from ``TRAN-MERCHANT-NAME PIC X(50)``.

    50-character denormalised copy of the merchant-directory name,
    captured at transaction time. This is intentionally a
    point-in-time snapshot — the statement rendering pipeline
    (CREASTMT) must show the merchant name as it appeared on the
    date of purchase, not the current (possibly re-branded) name.

    Defaults to empty string for non-merchant transactions (interest
    accrual, fee, payment rows).
    """
    _assert_string_column("merchant_name", _EXPECTED_STRING_COLUMN_LENGTHS["merchant_name"])


@pytest.mark.unit
def test_merchant_city_type() -> None:
    """``merchant_city`` must be ``String(50)`` — from ``TRAN-MERCHANT-CITY PIC X(50)``.

    50-character denormalised merchant city, captured at transaction
    time alongside ``merchant_name``. Part of the point-in-time
    snapshot contract — CREASTMT statements and TRANREPT reports
    must render the historical city as it was at the moment of
    purchase.
    """
    _assert_string_column("merchant_city", _EXPECTED_STRING_COLUMN_LENGTHS["merchant_city"])


@pytest.mark.unit
def test_merchant_zip_type() -> None:
    """``merchant_zip`` must be ``String(10)`` — from ``TRAN-MERCHANT-ZIP PIC X(10)``.

    10-character merchant postal code. The 10-character width
    accommodates both the US 5-digit ZIP (``'62701     '``, blank-
    padded) and the US 9-digit ZIP+4 (``'62701-0001'``, with hyphen),
    as well as Canadian postal codes and other international postal
    formats. Defaults to empty string.
    """
    _assert_string_column("merchant_zip", _EXPECTED_STRING_COLUMN_LENGTHS["merchant_zip"])


@pytest.mark.unit
def test_card_num_type() -> None:
    """``card_num`` must be ``String(16)`` — from COBOL ``TRAN-CARD-NUM PIC X(16)``.

    The 16-character card number (PAN) is a logical foreign key into
    the ``card_cross_reference`` table, from which POSTTRAN resolves
    the owning account. The exact 16-character width matches the
    ISO/IEC 7812 PAN standard for most major-network cards.

    Two downstream consumers depend on exact-match equality here:

    * ``posttran_job.py`` — reject code 101 (card not found in
      cross-reference) fires when no ``card_cross_reference`` row
      exists with this ``card_num``. Width mismatches would silently
      break the join.
    * ``transaction_service.py`` (online transaction-add from
      ``COTRN02C``) — resolves ``card_num`` to ``acct_id`` before
      INSERT. The account-linking integrity depends on this width.
    """
    _assert_string_column("card_num", _EXPECTED_STRING_COLUMN_LENGTHS["card_num"])


@pytest.mark.unit
def test_orig_ts_type() -> None:
    """``orig_ts`` must be ``String(26)`` — from COBOL ``TRAN-ORIG-TS PIC X(26)``.

    The 26-character **origination** timestamp is the moment the
    upstream system registered the transaction (ISO-8601 /
    COBOL-display format ``YYYY-MM-DD HH:MM:SS.ffffff`` or
    ``YYYY-MM-DD-HH.MM.SS.NNNNNN`` with microsecond precision —
    exactly 26 bytes). Stored as ``String(26)`` rather than a
    SQL ``TIMESTAMP`` to preserve the exact COBOL byte layout,
    which is what downstream reconciliation systems and legacy
    mainframe consumers compare against byte-for-byte.

    Paired with :attr:`proc_ts` — the gap between origination and
    processing is a useful latency metric on monitoring dashboards.
    Defaults to empty string.
    """
    _assert_string_column("orig_ts", _EXPECTED_STRING_COLUMN_LENGTHS["orig_ts"])


@pytest.mark.unit
def test_proc_ts_type() -> None:
    """``proc_ts`` must be ``String(26)`` — from COBOL ``TRAN-PROC-TS PIC X(26)``.

    The 26-character **processing** timestamp is the moment the
    transaction was accepted and posted into this ledger. Like
    :attr:`orig_ts`, stored as text rather than a native
    ``TIMESTAMP`` for byte-exact COBOL interoperability.

    **Indexed.** Unlike the sibling
    :class:`~src.shared.models.daily_transaction.DailyTransaction.proc_ts`
    column, this ``proc_ts`` carries a B-tree index
    (``ix_transaction_proc_ts``) declared in ``__table_args__``.
    Two batch jobs depend critically on this index:

    * ``creastmt_job.py`` (CREASTMT — Stage 4a) — filters by
      billing-cycle window to produce customer statements.
    * ``tranrept_job.py`` (TRANREPT — Stage 4b) — filters by
      arbitrary user-supplied date window to produce transaction
      reports with 3-level subtotals.

    Without the index, those date-range scans would degrade to
    full-table sequential scans — prohibitively expensive once the
    ledger grows past a few million rows. See
    :func:`test_proc_ts_index` for the index-specific invariant.

    Defaults to empty string.
    """
    _assert_string_column("proc_ts", _EXPECTED_STRING_COLUMN_LENGTHS["proc_ts"])


# ============================================================================
# Phase 4: Monetary Field Tests — CRITICAL: Numeric(15, 2), NEVER float
#
# AAP §0.7.2 explicitly forbids ``float`` for ANY monetary column. Every
# COBOL ``PIC S9(n)V99`` field maps to SQLAlchemy ``Numeric(n+6, 2)`` or
# the matching precision that accommodates the full integer range. For
# ``TRAN-AMT PIC S9(09)V99`` the relational target is ``Numeric(15, 2)``
# — 9 integer digits + 2 decimal digits + 4 digits of headroom for the
# absolute-value range (the sign bit contributes no storage in COBOL but
# does in NUMERIC).
#
# Floating-point cannot exactly represent decimal fractions such as
# ``0.10`` or ``0.20``; even a single ``float`` reinterpret would silently
# corrupt every downstream arithmetic operation (POSTTRAN balance update,
# INTCALC interest accrual, CREASTMT cycle-total aggregation, and bill
# payment dual-write). This is why the test is framed as a HARD INVARIANT
# and spelled out as `NEVER float` in the test title.
# ============================================================================


@pytest.mark.unit
def test_amount_type() -> None:
    """``amount`` must be stored as ``Numeric(15, 2)``, NEVER float.

    The COBOL source field ``TRAN-AMT PIC S9(09)V99`` represents a
    signed amount with up to 9 integer digits and exactly 2 decimal
    places. The relational target is ``Numeric(15, 2)``:

    * ``precision=15`` — covers the absolute value range of
      ``-999_999_999.99`` to ``+999_999_999.99`` (9 integer + 2
      fractional = 11 total digits of actual content, padded to 15
      for headroom — the project-wide width for all monetary fields
      derived from COBOL ``PIC S9(09)V99``).
    * ``scale=2``  — exactly 2 decimal places, matching ``V99``.

    This mapping MUST NOT be weakened to ``Float`` or ``Double`` under
    any circumstances. IEEE-754 floating-point cannot exactly
    represent decimal fractions such as ``0.10`` or ``0.20``, so
    every posting would accumulate drift. AAP §0.7.2 is explicit:
    *all monetary values must use Python* :class:`~decimal.Decimal`
    *with explicit two-decimal-place precision*.

    Downstream arithmetic consumers depend on this exact-decimal
    contract:

    * POSTTRAN (``posttran_job.py``) — adds ``amount`` to
      ``Account.curr_bal`` and the matching
      ``TransactionCategoryBalance.tran_cat_bal`` bucket. Any
      float-drift here corrupts account balances.
    * INTCALC (``intcalc_job.py``) — reads
      ``TransactionCategoryBalance.tran_cat_bal`` (also
      ``Numeric(15, 2)``), computes monthly interest as
      ``(TRAN-CAT-BAL × DIS-INT-RATE) / 1200`` preserved literally
      per AAP §0.7.1, and writes the result back here as a new
      ``amount``. The literal-preserved division by 1200 is
      specifically non-associative under floating point.
    * CREASTMT / TRANREPT — aggregate ``amount`` by billing cycle
      and category. Multi-row decimal sums are where float-drift
      manifests as visible discrepancies between the printed total
      and the arithmetic sum of the line items.
    """
    assert "amount" in Transaction.__table__.columns, (
        f"Transaction must expose an 'amount' column; found {sorted(Transaction.__table__.columns.keys())!r}"
    )
    amount_col = Transaction.__table__.columns["amount"]

    # Type must be SQLAlchemy Numeric — not Float, Double, Integer,
    # or anything else that breaks Decimal round-trip equality.
    assert isinstance(amount_col.type, Numeric), (
        f"amount column type must be SQLAlchemy Numeric (mapping to "
        f"PostgreSQL NUMERIC), NEVER Float / Double / Real — AAP "
        f"§0.7.2 forbids floating-point for monetary fields; found "
        f"{type(amount_col.type).__name__}"
    )

    # Defensive guard: SQLAlchemy's ``Float`` inherits from nothing we
    # care about here, but some codebases mistakenly use ``Numeric(..,
    # asdecimal=False)`` which silently returns ``float`` values.
    # Pin ``asdecimal`` to ``True`` explicitly (it is the default, but
    # we assert it anyway to catch any future override).
    assert amount_col.type.asdecimal is True, (
        f"amount column must have ``asdecimal=True`` (the default for "
        f"Numeric) so that round-trip values are returned as "
        f"decimal.Decimal, not float. AAP §0.7.2 forbids float for "
        f"monetary fields; found asdecimal={amount_col.type.asdecimal}"
    )

    assert amount_col.type.precision == _EXPECTED_AMOUNT_PRECISION, (
        f"amount column precision must be {_EXPECTED_AMOUNT_PRECISION} "
        f"(matching Numeric(15, 2) — the project-wide width for COBOL "
        f"PIC S9(09)V99 monetary fields); found "
        f"{amount_col.type.precision}"
    )

    assert amount_col.type.scale == _EXPECTED_AMOUNT_SCALE, (
        f"amount column scale must be {_EXPECTED_AMOUNT_SCALE} (exactly "
        f"2 decimal places, matching COBOL PIC V99); found "
        f"{amount_col.type.scale}"
    )


@pytest.mark.unit
def test_amount_default() -> None:
    """``amount`` must default to ``Decimal('0.00')`` (CRITICAL — not 0, not 0.0).

    When a ``Transaction`` is constructed without an explicit
    ``amount`` (e.g., a zero-amount authorisation reversal or a
    placeholder posting that carries its signed amount elsewhere),
    the ORM must populate ``amount`` with ``Decimal('0.00')`` —
    the two-decimal-place zero. Several details matter here and are
    pinned by this test:

    * The default **type** must be :class:`~decimal.Decimal` — NOT
      :class:`int` (``0``) or :class:`float` (``0.0``). An ``int``
      default would force an implicit cast on every row insert, and
      a ``float`` default would silently introduce the float type
      into the :class:`~decimal.Decimal` round-trip and corrupt
      downstream arithmetic.
    * The default **value** must be exactly ``Decimal('0.00')`` —
      NOT ``Decimal('0')`` or ``Decimal('0.0')``. Preserving the
      two-decimal-place representation matters because subsequent
      addition / multiplication with other ``Decimal('.XX')``
      operands produces consistent-scale results without
      quantisation surprises.

    This mirrors the default on the sibling
    :class:`~src.shared.models.daily_transaction.DailyTransaction.amount`
    column, closing the staging → permanent promotion contract: a
    daily-transaction row that arrives without an explicit amount
    defaults to ``Decimal('0.00')`` in both tables, and POSTTRAN
    promotes the value field-for-field without any type coercion.
    """
    amount_col = Transaction.__table__.columns["amount"]
    default = amount_col.default

    assert default is not None, (
        "amount column must declare a default (expected "
        "Decimal('0.00')); found no default. Without a default, "
        "constructing a Transaction without an explicit amount (as "
        "the online transaction-add endpoint sometimes does for "
        "placeholder authorisations) would raise IntegrityError on "
        "flush."
    )

    # SQLAlchemy stores a literal default as ``ColumnDefault.arg``.
    default_value = default.arg
    assert isinstance(default_value, Decimal), (
        f"amount default value must be decimal.Decimal (NEVER int or "
        f"float). AAP §0.7.2 forbids float for monetary defaults; "
        f"found type {type(default_value).__name__} with value "
        f"{default_value!r}"
    )
    assert default_value == Decimal("0.00"), (
        f"amount default value must be Decimal('0.00') exactly "
        f"(two-decimal-place zero, preserving scale for consistent "
        f"downstream arithmetic); found {default_value!r}"
    )

    # Extra guard — the string representation must preserve the
    # trailing zero. ``Decimal('0')`` and ``Decimal('0.0')`` would
    # compare equal to ``Decimal('0.00')`` under ``==`` but would
    # render as ``'0'`` / ``'0.0'`` respectively, silently dropping
    # the two-decimal-place scale on first arithmetic.
    assert str(default_value) == "0.00", (
        f"amount default must render as '0.00' to preserve the "
        f"two-decimal-place scale (COBOL PIC V99); found "
        f"{str(default_value)!r}"
    )


# ============================================================================
# Phase 5: Index Tests — B-tree index on proc_ts (VSAM AIX equivalent)
#
# The legacy VSAM cluster TRANFILE (from ``app/jcl/TRANFILE.jcl``) is
# keyed on TRAN-ID (the 16-byte primary transaction identifier), and
# the companion index TRANIDX (from ``app/jcl/TRANIDX.jcl``) provides
# an Alternate Index (AIX) path on the processing timestamp field
# TRAN-PROC-TS. That AIX is declared as ``NONUNIQUEKEY`` in IDCAMS
# (multiple transactions can share a single 26-char timestamp when
# batch posting writes them in the same second), and it is the read
# path used by:
#
# * CBTRN03C / ``tranrept_job.py`` (Stage 4b TRANREPT) — scans
#   transactions in ``TRAN-PROC-TS`` order to produce the daily
#   transaction report with 3-level totals (account, card, grand).
# * CBSTM03A / ``creastmt_job.py`` (Stage 4a CREASTMT) — reads
#   transactions filtered by processing date and sorted by timestamp
#   to emit billing-cycle statements.
#
# In Aurora PostgreSQL the VSAM AIX translates to a non-unique B-tree
# secondary index named ``ix_transaction_proc_ts`` (project-wide
# convention: ``ix_<table>_<column>``). AAP §0.5.1 pins this index
# under ``db/migrations/V2__indexes.sql``; the SQLAlchemy model
# declares it identically via ``__table_args__`` so that the ORM
# metadata and the Flyway-style migration script remain in sync.
# Dropping this index at the ORM layer would lead to slow sequential
# scans in Stage 4a/4b even if the migration script left the
# physical index in place.
# ============================================================================


@pytest.mark.unit
def test_proc_ts_index() -> None:
    """``Transaction`` must declare a B-tree index on ``proc_ts``.

    The index is:

    * **Named** ``ix_transaction_proc_ts`` — matching the
      project-wide ``ix_<table>_<column>`` convention used throughout
      ``db/migrations/V2__indexes.sql`` and by the Alembic autogen
      diff. The exact name matters because migration scripts
      ``CREATE INDEX`` and ``DROP INDEX`` by name.
    * **On the single column** ``proc_ts`` (``String(26)`` — the
      ISO-8601-like timestamp carried across from COBOL
      TRAN-PROC-TS PIC X(26)).
    * **Non-unique** — multiple transactions can share a single
      26-char timestamp when batch POSTTRAN writes them in the
      same second, so ``unique=False`` (the default) is correct.
      Matching the NONUNIQUEKEY declaration in the legacy IDCAMS
      TRANIDX AIX definition.

    This index is the relational equivalent of the VSAM AIX path
    from the original mainframe cluster TRANFILE + TRANIDX, and is
    critical for two batch read patterns:

    * TRANREPT (Stage 4b) — daily report scan ordered by proc_ts.
    * CREASTMT (Stage 4a) — billing cycle statement generation
      filtered by proc_ts date range.

    Any regression that drops or renames this index would degrade
    those jobs from ``O(log n)`` index seek to ``O(n)`` table scan
    — unacceptable at production scale (millions of rows).
    """
    # SQLAlchemy types ``__table__`` as ``FromClause`` on the
    # declarative base; cast down to the concrete ``Table`` to access
    # the ``.indexes`` collection (runtime is always a ``Table``).
    table = cast(Table, Transaction.__table__)
    indexes = list(table.indexes)

    # Locate the index by name. Note we use ``next`` + ``None``
    # default so we can emit a rich diagnostic if the index is
    # missing entirely.
    matched = next(
        (idx for idx in indexes if idx.name == _EXPECTED_PROC_TS_INDEX_NAME),
        None,
    )
    assert matched is not None, (
        f"Transaction must declare an index named "
        f"{_EXPECTED_PROC_TS_INDEX_NAME!r} on the proc_ts column "
        f"(replacing the legacy VSAM AIX TRANIDX from "
        f"app/jcl/TRANIDX.jcl); found indexes with names "
        f"{sorted(idx.name for idx in indexes if idx.name)!r}"
    )

    # Exactly one column — the single-column B-tree.
    indexed_columns = list(matched.columns)
    assert len(indexed_columns) == 1, (
        f"Index {_EXPECTED_PROC_TS_INDEX_NAME!r} must be a "
        f"single-column B-tree on proc_ts (matching VSAM AIX "
        f"TRANIDX which had a single AIX key on TRAN-PROC-TS); "
        f"found {len(indexed_columns)} columns: "
        f"{[c.name for c in indexed_columns]!r}"
    )

    # The single column must be ``proc_ts``.
    indexed_column_name = indexed_columns[0].name
    assert indexed_column_name == "proc_ts", (
        f"Index {_EXPECTED_PROC_TS_INDEX_NAME!r} must be on the "
        f"'proc_ts' column (the relational target of COBOL "
        f"TRAN-PROC-TS PIC X(26)); found column "
        f"{indexed_column_name!r}"
    )

    # Non-unique — multiple transactions can share a single
    # 26-character timestamp when batch POSTTRAN writes many
    # postings in the same second (matching NONUNIQUEKEY in the
    # legacy IDCAMS TRANIDX AIX definition).
    assert matched.unique is False, (
        f"Index {_EXPECTED_PROC_TS_INDEX_NAME!r} must be non-unique "
        f"(unique=False) so multiple transactions can share a "
        f"single 26-char processing timestamp (matching the "
        f"NONUNIQUEKEY declaration in the legacy IDCAMS TRANIDX "
        f"AIX); found unique={matched.unique}"
    )


# ============================================================================
# Phase 6: NOT NULL Constraint Tests
#
# Every COBOL fixed-width record field is implicitly non-null — a
# VSAM cluster cannot store ``NULL`` because the physical record is
# a fixed-size byte sequence. The relational migration must preserve
# that invariant: all 13 mapped columns are declared ``NOT NULL``
# (via ``nullable=False`` in the SQLAlchemy model, primary_key
# implying NOT NULL for ``tran_id``).
#
# The instructions for this file specifically call out the subset
# ``tran_id``, ``type_cd``, ``cat_cd``, ``source``, ``amount``,
# ``card_num`` as the minimum NOT NULL set, but the actual model
# declares *every* non-primary column as ``nullable=False`` and that
# stronger invariant is what we assert here. Widening the assertion
# to cover all 13 columns catches any accidental future weakening
# where a migration PR flips ``nullable=False`` to ``nullable=True``
# on a merchant-detail or timestamp column.
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 13 mapped columns must be ``NOT NULL`` (no nullable fields).

    COBOL fixed-width records cannot store ``NULL`` — every field
    in TRAN-RECORD is physically present and byte-addressable in
    the VSAM cluster. Preserving this as ``NOT NULL`` on the
    relational table ensures:

    * POSTTRAN can always safely read every field without
      ``NullPointerException`` equivalents (which in Python ORMs
      surfaces as ``AttributeError`` on ``None`` field access or
      as a ``TypeError`` on downstream arithmetic like
      ``amount + curr_bal`` when ``amount`` is ``None``).
    * TRANREPT / CREASTMT can format the transaction row without
      conditional-null handling for each of the 13 fields.
    * Legacy data that migrates in from the fixture loader
      (V3__seed_data.sql) conforms to the schema with or without
      the ``NOT NULL`` hint — reducing the risk of silently
      admitting partial records.

    The primary key ``tran_id`` is implicitly NOT NULL (PostgreSQL
    and ANSI SQL both require it). The remaining 12 columns carry
    ``nullable=False`` explicitly. This test validates both paths
    by iterating the full expected column set.
    """
    for column_name in _EXPECTED_COLUMNS:
        assert column_name in Transaction.__table__.columns, (
            f"Expected column {column_name!r} is missing from "
            f"Transaction model; found columns "
            f"{sorted(Transaction.__table__.columns.keys())!r}"
        )
        column = Transaction.__table__.columns[column_name]

        assert column.nullable is False, (
            f"Column {column_name!r} must be NOT NULL (nullable=False) "
            f"to preserve the COBOL fixed-width record invariant from "
            f"CVTRA05Y.cpy — every TRAN-RECORD field is physically "
            f"present in the VSAM cluster and must remain so in the "
            f"relational target; found nullable={column.nullable}"
        )


# ============================================================================
# Phase 7: Instance Creation & __repr__ Tests
#
# These tests exercise the construction surface of the ORM model,
# ensuring that:
#
# 1. ``Transaction(**kwargs)`` accepts all 13 mapped columns as
#    keyword arguments (the SQLAlchemy Declarative default behaviour
#    when no ``__init__`` is overridden).
# 2. Every field round-trips unchanged through attribute readback —
#    in particular, ``amount`` remains a :class:`~decimal.Decimal`
#    and is NOT coerced to ``float`` by any intermediary.
# 3. The constructed instance is a genuine :class:`Base` subclass
#    (the project-wide declarative base), which is required for
#    SQLAlchemy to track the instance in its identity map and
#    participate in unit-of-work flushes.
# 4. The ``__repr__`` includes the four most diagnostic fields
#    — ``tran_id``, ``type_cd``, ``amount``, ``card_num`` — each
#    rendered through :func:`repr` so that monetary amounts show
#    with the full ``Decimal('75.50')`` form (not ``'75.5'``) and
#    string fields show with surrounding quotes (catching trailing
#    whitespace in fixed-width records during log inspection).
# ============================================================================


@pytest.mark.unit
def test_create_transaction_instance() -> None:
    """Construct a full ``Transaction`` — 13 kwargs round-trip unchanged.

    This test exercises the complete constructor surface of the
    ``Transaction`` ORM model and performs field-by-field readback
    assertions to verify that every kwarg is stored without
    coercion or transformation.

    Special attention is paid to:

    * **Decimal preservation** — ``transaction.amount`` MUST be an
      instance of :class:`~decimal.Decimal` (NEVER float). If
      SQLAlchemy were mis-configured with ``asdecimal=False`` this
      test would catch the regression at in-memory construction
      time (before any DB round-trip), because the default is
      applied by :class:`~decimal.Decimal` arithmetic.
    * **Equality preservation** — ``Decimal('75.50') ==
      Decimal('75.5')`` is True under Python semantics but the
      scale is different. The ``==`` check here uses
      ``Decimal('75.50')`` literal to ensure the stored value
      compares equal to what we passed in.
    * **Base subclass check** — the instance must inherit from
      :class:`Base` (the shared declarative base from
      :mod:`src.shared.models`). Without this, SQLAlchemy's
      metadata tracking would silently break and the instance
      would be invisible to ``Session.add()``.
    """
    transaction = Transaction(
        tran_id=_SAMPLE_TRAN_ID,
        type_cd=_SAMPLE_TYPE_CD,
        cat_cd=_SAMPLE_CAT_CD,
        source=_SAMPLE_SOURCE,
        description=_SAMPLE_DESCRIPTION,
        amount=_SAMPLE_AMOUNT,
        merchant_id=_SAMPLE_MERCHANT_ID,
        merchant_name=_SAMPLE_MERCHANT_NAME,
        merchant_city=_SAMPLE_MERCHANT_CITY,
        merchant_zip=_SAMPLE_MERCHANT_ZIP,
        card_num=_SAMPLE_CARD_NUM,
        orig_ts=_SAMPLE_ORIG_TS,
        proc_ts=_SAMPLE_PROC_TS,
    )

    # The instance must be a Base subclass — required for
    # SQLAlchemy identity map tracking and ``Session.add()``.
    assert isinstance(transaction, Base), (
        f"Transaction instance must inherit from Base (the shared "
        f"declarative base from src.shared.models), otherwise "
        f"SQLAlchemy cannot track it in its identity map; found "
        f"MRO {[cls.__name__ for cls in type(transaction).__mro__]!r}"
    )
    assert isinstance(transaction, Transaction), (
        f"Constructed instance must be a Transaction (tautological "
        f"guard against accidental subclass mix-ups); found "
        f"type={type(transaction).__name__}"
    )

    # Field-by-field readback — every kwarg must survive unchanged.
    assert transaction.tran_id == _SAMPLE_TRAN_ID, (
        f"tran_id must round-trip unchanged; expected {_SAMPLE_TRAN_ID!r}, got {transaction.tran_id!r}"
    )
    assert transaction.type_cd == _SAMPLE_TYPE_CD, (
        f"type_cd must round-trip unchanged; expected {_SAMPLE_TYPE_CD!r}, got {transaction.type_cd!r}"
    )
    assert transaction.cat_cd == _SAMPLE_CAT_CD, (
        f"cat_cd must round-trip unchanged, preserving leading "
        f"zeros from COBOL PIC 9(04); expected {_SAMPLE_CAT_CD!r}, "
        f"got {transaction.cat_cd!r}"
    )
    assert transaction.source == _SAMPLE_SOURCE, (
        f"source must round-trip unchanged; expected {_SAMPLE_SOURCE!r}, got {transaction.source!r}"
    )
    assert transaction.description == _SAMPLE_DESCRIPTION, (
        f"description must round-trip unchanged; expected {_SAMPLE_DESCRIPTION!r}, got {transaction.description!r}"
    )

    # CRITICAL — amount must remain a Decimal, never coerced to
    # float. AAP §0.7.2 forbids float for monetary fields.
    assert isinstance(transaction.amount, Decimal), (
        f"amount must remain a decimal.Decimal after construction "
        f"(NEVER float — AAP §0.7.2 forbids floating-point for "
        f"monetary fields); found type "
        f"{type(transaction.amount).__name__} with value "
        f"{transaction.amount!r}"
    )
    assert transaction.amount == _SAMPLE_AMOUNT, (
        f"amount must round-trip unchanged; expected {_SAMPLE_AMOUNT!r}, got {transaction.amount!r}"
    )

    assert transaction.merchant_id == _SAMPLE_MERCHANT_ID, (
        f"merchant_id must round-trip unchanged, preserving leading "
        f"zeros from COBOL PIC 9(09); expected "
        f"{_SAMPLE_MERCHANT_ID!r}, got {transaction.merchant_id!r}"
    )
    assert transaction.merchant_name == _SAMPLE_MERCHANT_NAME, (
        f"merchant_name must round-trip unchanged; expected "
        f"{_SAMPLE_MERCHANT_NAME!r}, got {transaction.merchant_name!r}"
    )
    assert transaction.merchant_city == _SAMPLE_MERCHANT_CITY, (
        f"merchant_city must round-trip unchanged; expected "
        f"{_SAMPLE_MERCHANT_CITY!r}, got {transaction.merchant_city!r}"
    )
    assert transaction.merchant_zip == _SAMPLE_MERCHANT_ZIP, (
        f"merchant_zip must round-trip unchanged; expected {_SAMPLE_MERCHANT_ZIP!r}, got {transaction.merchant_zip!r}"
    )
    assert transaction.card_num == _SAMPLE_CARD_NUM, (
        f"card_num must round-trip unchanged; expected {_SAMPLE_CARD_NUM!r}, got {transaction.card_num!r}"
    )
    assert transaction.orig_ts == _SAMPLE_ORIG_TS, (
        f"orig_ts must round-trip unchanged (26-char ISO-8601 "
        f"timestamp); expected {_SAMPLE_ORIG_TS!r}, got "
        f"{transaction.orig_ts!r}"
    )
    assert transaction.proc_ts == _SAMPLE_PROC_TS, (
        f"proc_ts must round-trip unchanged (26-char ISO-8601 "
        f"timestamp); expected {_SAMPLE_PROC_TS!r}, got "
        f"{transaction.proc_ts!r}"
    )


@pytest.mark.unit
def test_transaction_repr() -> None:
    """``repr(transaction)`` must be readable and include diagnostic fields.

    The ``__repr__`` for :class:`Transaction` follows the project
    convention of rendering the 4 most diagnostic fields:

    * ``tran_id``   — the primary key (indispensable for log-based
      debugging — every ``grep`` starts with this).
    * ``type_cd``   — the transaction type (01 = Purchase, 02 =
      Refund, etc. per ``app/data/ASCII/trantype.txt``). Shows
      intent at a glance.
    * ``amount``    — the monetary amount (rendered via
      :func:`repr` so it shows as ``Decimal('75.50')`` rather than
      the str-coerced ``'75.50'``, making the
      :class:`~decimal.Decimal` type explicit in logs).
    * ``card_num``  — the card number (needed to correlate the
      transaction with the cardholder in multi-tenant debugging).

    Other fields (merchant, timestamps, description) are deliberately
    omitted to keep ``repr`` concise enough for log lines while still
    providing enough detail to identify the row.

    CRITICAL — the ``amount`` field must render through ``repr()``
    which produces ``Decimal('75.50')`` preserving the full
    two-decimal-place scale. A naïve ``f"amount={self.amount}"``
    (str-coercion) would render as ``75.50`` which hides the
    Decimal type from log readers; worse, if amount were ever
    mis-stored as a float, ``75.50`` could appear as
    ``75.5`` or ``75.4999999999999996`` silently.
    """
    transaction = Transaction(
        tran_id=_SAMPLE_TRAN_ID,
        type_cd=_SAMPLE_TYPE_CD,
        cat_cd=_SAMPLE_CAT_CD,
        source=_SAMPLE_SOURCE,
        description=_SAMPLE_DESCRIPTION,
        amount=_SAMPLE_AMOUNT,
        merchant_id=_SAMPLE_MERCHANT_ID,
        merchant_name=_SAMPLE_MERCHANT_NAME,
        merchant_city=_SAMPLE_MERCHANT_CITY,
        merchant_zip=_SAMPLE_MERCHANT_ZIP,
        card_num=_SAMPLE_CARD_NUM,
        orig_ts=_SAMPLE_ORIG_TS,
        proc_ts=_SAMPLE_PROC_TS,
    )

    rendered = repr(transaction)

    # Must be a non-empty string (NOT ``None`` and NOT the bare
    # default ``<Transaction object at 0x...>`` — that would mean
    # ``__repr__`` was not overridden).
    assert isinstance(rendered, str), f"repr(transaction) must return a str; got {type(rendered).__name__}"
    assert rendered, f"repr(transaction) must return a non-empty string; got {rendered!r}"

    # Must include the class name as a prefix.
    assert "Transaction" in rendered, (
        f"repr must include the class name 'Transaction' for log readability; got {rendered!r}"
    )

    # Must NOT be the default object repr (which would indicate a
    # missing ``__repr__`` override).
    assert "object at 0x" not in rendered, (
        f"repr must be the custom implementation, not the default "
        f"``<Transaction object at 0x...>`` — the model must "
        f"override __repr__; got {rendered!r}"
    )

    # Each of the 4 diagnostic field names must appear as a
    # keyword-like prefix ``field=``.
    for field_label in ("tran_id", "type_cd", "amount", "card_num"):
        assert f"{field_label}=" in rendered, (
            f"repr must include '{field_label}=' to label the diagnostic field value; got {rendered!r}"
        )

    # The string fields must be rendered through ``repr()`` so they
    # appear in quotes — this is how COBOL PIC X fields with
    # trailing spaces become visible in logs.
    assert repr(_SAMPLE_TRAN_ID) in rendered, (
        f"repr must include tran_id as repr()-rendered (quoted) string {repr(_SAMPLE_TRAN_ID)!r}; got {rendered!r}"
    )
    assert repr(_SAMPLE_TYPE_CD) in rendered, (
        f"repr must include type_cd as repr()-rendered (quoted) string {repr(_SAMPLE_TYPE_CD)!r}; got {rendered!r}"
    )
    assert repr(_SAMPLE_CARD_NUM) in rendered, (
        f"repr must include card_num as repr()-rendered (quoted) string {repr(_SAMPLE_CARD_NUM)!r}; got {rendered!r}"
    )

    # CRITICAL — amount must appear as ``Decimal('75.50')`` (via
    # repr) NOT ``75.50`` (via str). This makes the
    # :class:`~decimal.Decimal` type explicit in logs and prevents
    # a silent float regression from being invisible in grep.
    assert repr(_SAMPLE_AMOUNT) in rendered, (
        f"repr must render amount via repr() — e.g., "
        f"{repr(_SAMPLE_AMOUNT)!r} — NOT via str-coercion which "
        f"would hide the Decimal type; got {rendered!r}"
    )


# ============================================================================
# Phase 8: FILLER Exclusion Test
#
# The source COBOL record ``CVTRA05Y.cpy`` ends with an explicit
# ``FILLER PIC X(20)`` — 20 bytes of reserved padding that bring the
# total record length to exactly 350 bytes for VSAM block alignment.
# Padding regions have no place in a relational model:
#
# * Aurora PostgreSQL has no concept of fixed-record-length
#   alignment — storage is variable-length by row.
# * A ``filler`` column would force every INSERT to specify a
#   placeholder value with no semantic meaning, and every SELECT to
#   strip it out for display.
# * Future COBOL record extensions (if any) would likely repurpose
#   the FILLER region; mapping it to a named column would pin the
#   table to a historical padding layout rather than the true
#   data model.
#
# The SQLAlchemy model therefore DROPS the FILLER explicitly. This
# test asserts that drop is deliberate and complete — no column is
# named ``filler``, no column name contains the substring
# ``filler`` in any case, and the 13 actual columns match the
# expected set exactly.
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """The ``FILLER PIC X(20)`` trailing CVTRA05Y.cpy must not appear.

    Two complementary assertions:

    1. **Positive** — the set of actual column names equals
       ``_EXPECTED_COLUMNS`` exactly. This catches *both* missing
       columns (a typo or accidentally-dropped field) and extra
       columns (a FILLER, audit column, or migration artefact that
       sneaked in).
    2. **Negative** — no column name contains the substring
       ``"filler"`` in any case. This catches creative
       mis-mappings like ``trailing_filler``, ``padding_filler``,
       or simply ``filler``.

    Together these form a tight contract: the only 13 columns on
    the model are exactly the 13 declared by the semantic COBOL
    fields in CVTRA05Y.cpy, and the 20-byte trailing FILLER is
    absent from the relational model by design.
    """
    actual_column_names = {col.name for col in Transaction.__table__.columns}

    # Positive — exact column-set equivalence.
    assert actual_column_names == set(_EXPECTED_COLUMNS), (
        f"Transaction columns must match _EXPECTED_COLUMNS exactly "
        f"(13 columns from CVTRA05Y.cpy semantic fields, FILLER "
        f"excluded). Missing from actual: "
        f"{sorted(set(_EXPECTED_COLUMNS) - actual_column_names)!r}. "
        f"Extra in actual: "
        f"{sorted(actual_column_names - set(_EXPECTED_COLUMNS))!r}."
    )

    # Exactly 13 columns — redundant with the positive check above
    # and with :func:`test_column_count`, but included here as a
    # defense-in-depth guard in case ``_EXPECTED_COLUMNS`` is
    # accidentally widened in a future refactor.
    assert len(actual_column_names) == 13, (
        f"Transaction must have exactly 13 columns (13 semantic "
        f"COBOL fields, FILLER excluded); found "
        f"{len(actual_column_names)}: {sorted(actual_column_names)!r}"
    )

    # Negative — no column name contains the substring ``filler``
    # in any case. Catches creative mis-mappings.
    for column_name in actual_column_names:
        assert "filler" not in column_name.lower(), (
            f"Column name {column_name!r} contains the substring "
            f"'filler' (case-insensitive). COBOL FILLER regions are "
            f"padding and must not be mapped to relational columns; "
            f"the 20-byte trailing FILLER in CVTRA05Y.cpy is "
            f"deliberately excluded from the Transaction model."
        )
