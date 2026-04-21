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

# Source: COBOL copybook CVTRA06Y.cpy — DALYTRAN-RECORD (350 bytes, sequential)
# Staging entity for daily batch processing (pre-POSTTRAN job).
"""Unit tests for the :class:`~src.shared.models.daily_transaction.DailyTransaction` ORM model.

This module pins the relational contract of the
:class:`~src.shared.models.daily_transaction.DailyTransaction` SQLAlchemy ORM
model against the authoritative COBOL source copybook
``app/cpy/CVTRA06Y.cpy``.

Domain context
--------------
``DailyTransaction`` is the **staging** entity that receives raw, unposted
transaction records from the upstream daily feed (``CBTRN01C`` — the daily
transaction driver) *before* they are validated and applied to master
balances by the POSTTRAN batch stage (``CBTRN02C`` →
``src/batch/jobs/posttran_job.py``). By design it mirrors the permanent
:class:`~src.shared.models.transaction.Transaction` record layout exactly,
preserving every COBOL field width, type, and ordering so that rows can
be promoted from the staging table to the permanent table without any
schema transformation — a property this test module actively pins via
:func:`test_mirrors_transaction_layout`.

COBOL Source Layout (CVTRA06Y.cpy, RECLN 350)
---------------------------------------------

.. code-block:: cobol

    01  DALYTRAN-RECORD.
        05  DALYTRAN-ID                             PIC X(16).
        05  DALYTRAN-TYPE-CD                        PIC X(02).
        05  DALYTRAN-CAT-CD                         PIC 9(04).
        05  DALYTRAN-SOURCE                         PIC X(10).
        05  DALYTRAN-DESC                           PIC X(100).
        05  DALYTRAN-AMT                            PIC S9(09)V99.
        05  DALYTRAN-MERCHANT-ID                    PIC 9(09).
        05  DALYTRAN-MERCHANT-NAME                  PIC X(50).
        05  DALYTRAN-MERCHANT-CITY                  PIC X(50).
        05  DALYTRAN-MERCHANT-ZIP                   PIC X(10).
        05  DALYTRAN-CARD-NUM                       PIC X(16).
        05  DALYTRAN-ORIG-TS                        PIC X(26).
        05  DALYTRAN-PROC-TS                        PIC X(26).
        05  FILLER                                  PIC X(20).

COBOL → SQLAlchemy Mapping
--------------------------

============================== =================== ======================= ============
COBOL field                    PIC clause          SQLAlchemy column       Python type
============================== =================== ======================= ============
``DALYTRAN-ID``                ``PIC X(16)``       ``tran_id``  (**PK**)   ``str``
``DALYTRAN-TYPE-CD``           ``PIC X(02)``       ``type_cd``             ``str``
``DALYTRAN-CAT-CD``            ``PIC 9(04)``       ``cat_cd``              ``str``
``DALYTRAN-SOURCE``            ``PIC X(10)``       ``source``              ``str``
``DALYTRAN-DESC``              ``PIC X(100)``      ``description``         ``str``
``DALYTRAN-AMT``               ``PIC S9(09)V99``   ``amount``              ``Decimal``
``DALYTRAN-MERCHANT-ID``       ``PIC 9(09)``       ``merchant_id``         ``str``
``DALYTRAN-MERCHANT-NAME``     ``PIC X(50)``       ``merchant_name``       ``str``
``DALYTRAN-MERCHANT-CITY``     ``PIC X(50)``       ``merchant_city``       ``str``
``DALYTRAN-MERCHANT-ZIP``      ``PIC X(10)``       ``merchant_zip``        ``str``
``DALYTRAN-CARD-NUM``          ``PIC X(16)``       ``card_num``            ``str``
``DALYTRAN-ORIG-TS``           ``PIC X(26)``       ``orig_ts``             ``str``
``DALYTRAN-PROC-TS``           ``PIC X(26)``       ``proc_ts``             ``str``
``FILLER``                     ``PIC X(20)``       *(deliberately dropped)*  —
============================== =================== ======================= ============

Test Coverage (22 unit tests organised by Phase)
-------------------------------------------------

Phase 2 — Table & Column Metadata (3 tests)
    1.  :func:`test_tablename`             — Pins ``__tablename__`` to
                                              ``"daily_transactions"`` (plural,
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

Phase 5 — NOT NULL Constraints (1 test)
    18. :func:`test_non_nullable_fields`   — All 13 columns are
                                              ``nullable=False``.

Phase 6 — Layout Parity with ``Transaction`` (1 test — staging ≡ permanent)
    19. :func:`test_mirrors_transaction_layout` — ``DailyTransaction``
                                                   and ``Transaction`` share
                                                   identical column names
                                                   and types for all 13
                                                   mapped fields.

Phase 7 — Instance Creation & ``__repr__`` (2 tests)
    20. :func:`test_create_daily_transaction_instance` — Full kwarg-based
                                                          construction with
                                                          ``Decimal('75.50')``
                                                          for the monetary
                                                          field.
    21. :func:`test_daily_transaction_repr`            — ``__repr__`` is
                                                          developer-friendly
                                                          and renders
                                                          ``amount`` as
                                                          ``Decimal(...)``.

Phase 8 — FILLER Exclusion (1 test)
    22. :func:`test_no_filler_columns`     — ``FILLER PIC X(20)`` is NOT
                                              mapped; exact column-set
                                              equivalence + negative
                                              substring guard.

See Also
--------
``src/shared/models/daily_transaction.py`` — The ORM model under test.
``src/shared/models/transaction.py``       — Layout-parity counterpart.
``src/shared/models/__init__.py``          — The shared declarative ``Base``.
``app/cpy/CVTRA06Y.cpy``                   — Original COBOL record layout.
``app/cpy/CVTRA05Y.cpy``                   — Permanent TRAN-RECORD layout.
``app/cbl/CBTRN01C.cbl``                   — COBOL daily-transaction driver.
``app/cbl/CBTRN02C.cbl``                   — COBOL POSTTRAN consumer.
``src/batch/jobs/posttran_job.py``         — PySpark POSTTRAN target.
``db/migrations/V1__schema.sql``           — ``CREATE TABLE daily_transactions``.
AAP §0.5.1                                 — File-by-File Transformation Plan.
AAP §0.7.1                                 — Minimal-change clause (preserve
                                             COBOL field widths exactly).
AAP §0.7.2                                 — Financial-precision clause
                                             (``Decimal`` not ``float``).
"""

from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import Numeric, String, inspect

from src.shared.models import Base
from src.shared.models.daily_transaction import DailyTransaction
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
        "tran_id",  # from DALYTRAN-ID            PIC X(16) — primary key
        "type_cd",  # from DALYTRAN-TYPE-CD       PIC X(02)
        "cat_cd",  # from DALYTRAN-CAT-CD        PIC 9(04) — stored as text
        "source",  # from DALYTRAN-SOURCE        PIC X(10)
        "description",  # from DALYTRAN-DESC          PIC X(100)
        "amount",  # from DALYTRAN-AMT           PIC S9(09)V99 — Numeric(15,2)
        "merchant_id",  # from DALYTRAN-MERCHANT-ID   PIC 9(09) — stored as text
        "merchant_name",  # from DALYTRAN-MERCHANT-NAME PIC X(50)
        "merchant_city",  # from DALYTRAN-MERCHANT-CITY PIC X(50)
        "merchant_zip",  # from DALYTRAN-MERCHANT-ZIP  PIC X(10)
        "card_num",  # from DALYTRAN-CARD-NUM      PIC X(16)
        "orig_ts",  # from DALYTRAN-ORIG-TS       PIC X(26) — ISO-ish timestamp
        "proc_ts",  # from DALYTRAN-PROC-TS       PIC X(26) — ISO-ish timestamp
    }
)

# Mapping of the 12 string columns → expected ``String(n)`` length, where
# ``n`` matches the COBOL PIC clause character count exactly. This mapping
# is consumed by Phase 3 per-column type tests and also by the layout-parity
# test in Phase 6 to confirm that the staging model and the permanent
# :class:`~src.shared.models.transaction.Transaction` model agree on every
# width — a contract that MUST hold because POSTTRAN simply copies rows
# between the two tables without any width-expanding cast.
_EXPECTED_STRING_COLUMN_LENGTHS: dict[str, int] = {
    "tran_id": 16,  # DALYTRAN-ID            PIC X(16)
    "type_cd": 2,  # DALYTRAN-TYPE-CD       PIC X(02)
    "cat_cd": 4,  # DALYTRAN-CAT-CD        PIC 9(04)
    "source": 10,  # DALYTRAN-SOURCE        PIC X(10)
    "description": 100,  # DALYTRAN-DESC          PIC X(100)
    "merchant_id": 9,  # DALYTRAN-MERCHANT-ID   PIC 9(09)
    "merchant_name": 50,  # DALYTRAN-MERCHANT-NAME PIC X(50)
    "merchant_city": 50,  # DALYTRAN-MERCHANT-CITY PIC X(50)
    "merchant_zip": 10,  # DALYTRAN-MERCHANT-ZIP  PIC X(10)
    "card_num": 16,  # DALYTRAN-CARD-NUM      PIC X(16)
    "orig_ts": 26,  # DALYTRAN-ORIG-TS       PIC X(26)
    "proc_ts": 26,  # DALYTRAN-PROC-TS       PIC X(26)
}

# The sole monetary column on the model — ``amount`` — must be stored as
# ``Numeric(15, 2)`` matching COBOL ``PIC S9(09)V99``: 9 integer digits
# (max absolute value 999_999_999) plus 2 decimal places. AAP §0.7.2
# forbids ``float`` for any monetary column.
_EXPECTED_AMOUNT_PRECISION: int = 15
_EXPECTED_AMOUNT_SCALE: int = 2

# Sample realistic DailyTransaction kwargs used by Phase 7 constructor /
# __repr__ tests. These values are chosen to exercise distinct aspects of
# the record:
#   * ``tran_id`` is 16 characters — exact maximum width.
#   * ``amount`` is ``Decimal('75.50')`` — two-decimal-place retail amount
#     that exercises both the integer and fractional parts without being
#     a round number (which could mask trailing-zero-stripping bugs).
#   * Merchant fields reflect a typical brick-and-mortar retail purchase.
#   * Timestamps use the ISO-8601 26-character format (``YYYY-MM-DD
#     HH:MM:SS.ffffff``) that the original COBOL batch emits for
#     ``DALYTRAN-ORIG-TS`` / ``DALYTRAN-PROC-TS``.
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
    """``DailyTransaction`` must be mapped to the ``daily_transactions`` table.

    The table name is the relational anchor that ties the ORM model to
    both the DDL migration (``db/migrations/V1__schema.sql`` —
    ``CREATE TABLE daily_transactions``) and the seed-data migration
    (``db/migrations/V3__seed_data.sql`` — ``INSERT INTO
    daily_transactions`` loads 300 rows from ``app/data/ASCII/dailytran.txt``).

    Any drift between ``DailyTransaction.__tablename__`` and the DDL
    contract would cause runtime ``UndefinedTable`` errors on every
    query path — including the POSTTRAN batch job
    (``src/batch/jobs/posttran_job.py``) that *reads* this table as the
    source of unposted transactions, so this invariant is pinned.

    Note
    ----
    The table name is *plural* (``daily_transactions``) to follow the
    project-wide relational-naming convention shared by all sibling
    entities except ``user_security`` (which retains its historical
    singular VSAM dataset name). The singular form ``daily_transaction``
    — which the AAP instructions briefly reference in one location —
    is NOT accepted: it would break the Flyway migrations that already
    have ``CREATE TABLE daily_transactions`` committed under
    ``db/migrations/``, and it would also break the layout-parity
    contract with :class:`~src.shared.models.transaction.Transaction`
    whose table name is ``transactions`` (plural).
    """
    assert DailyTransaction.__tablename__ == "daily_transactions", (
        f"DailyTransaction.__tablename__ must be 'daily_transactions' "
        f"to match db/migrations/V1__schema.sql and V3__seed_data.sql; "
        f"found {DailyTransaction.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """``DailyTransaction`` must expose exactly 13 mapped columns.

    The COBOL ``DALYTRAN-RECORD`` layout has 14 elementary fields (13
    data-bearing fields plus the trailing ``FILLER PIC X(20)`` padding
    that rounds the record out to the fixed 350-byte VSAM-compatible
    length), but only the 13 data-bearing fields are mapped to the
    relational model. ``FILLER`` is deliberately dropped because
    trailing padding has no storage or semantic meaning in a
    column-typed schema — see :func:`test_no_filler_columns` for the
    negative-side guard.

    The count of 13 also matches
    :class:`~src.shared.models.transaction.Transaction` exactly (its
    source ``CVTRA05Y.cpy`` has the same 13 data fields + FILLER layout),
    which is the invariant pinned in
    :func:`test_mirrors_transaction_layout`.
    """
    actual_count = len(DailyTransaction.__table__.columns)
    assert actual_count == 13, (
        f"DailyTransaction must have exactly 13 mapped columns (matching "
        f"the 13 data-bearing fields of COBOL DALYTRAN-RECORD; FILLER "
        f"PIC X(20) is NOT mapped); found {actual_count} columns: "
        f"{sorted(c.name for c in DailyTransaction.__table__.columns)!r}"
    )


@pytest.mark.unit
def test_primary_key_tran_id() -> None:
    """``tran_id`` must be the sole primary key on ``DailyTransaction``.

    The COBOL ``DALYTRAN-ID`` field (``PIC X(16)``) is the unique
    identifier assigned upstream by the originating issuer / network /
    merchant feed. In the VSAM KSDS source file it was the primary
    key of the ``DALYTRAN`` cluster — uniqueness guaranteed by the
    upstream feed, not generated by the staging layer.

    The relational migration preserves this semantics:

    * The primary key is a **single column** (``tran_id``) — not a
      composite key.
    * The column type is ``String(16)`` — matching the COBOL PIC
      width exactly so zero-padded or blank-padded IDs from the
      upstream feed round-trip unchanged.
    * No auto-increment / surrogate key is introduced — the ``tran_id``
      comes in pre-assigned from the feed.

    Downstream consumers that rely on this invariant include
    ``src/batch/jobs/posttran_job.py`` (which reads and deletes
    by ``tran_id``) and the transaction-detail REST endpoint
    ``GET /transactions/{id}`` (which references the promoted
    ``transactions.tran_id``).
    """
    mapper = inspect(DailyTransaction)
    primary_key_columns = list(mapper.primary_key)

    # Exactly one PK column — NOT a composite.
    assert len(primary_key_columns) == 1, (
        f"DailyTransaction must have exactly 1 primary-key column "
        f"(``tran_id``), not a composite key; found "
        f"{[c.name for c in primary_key_columns]!r}"
    )

    pk_column = primary_key_columns[0]
    assert pk_column.name == "tran_id", (
        f"Primary-key column must be named 'tran_id' to match COBOL DALYTRAN-ID; found {pk_column.name!r}"
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
    """Helper: assert a :class:`DailyTransaction` column is ``String(n)``.

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
    assert column_name in DailyTransaction.__table__.columns, (
        f"DailyTransaction must expose a {column_name!r} column; "
        f"found {sorted(DailyTransaction.__table__.columns.keys())!r}"
    )
    column = DailyTransaction.__table__.columns[column_name]
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
    """``tran_id`` must be ``String(16)`` — from COBOL ``DALYTRAN-ID PIC X(16)``.

    Exact width preservation is critical: the upstream daily feed emits
    16-byte IDs that are often blank-padded or zero-padded. Truncating
    to fewer characters would silently drop the trailing pad and break
    round-trip equality with the VSAM source; widening to more
    characters would permit malformed IDs through the staging layer
    and corrupt downstream POSTTRAN reject-code routing.
    """
    _assert_string_column("tran_id", 16)


@pytest.mark.unit
def test_type_cd_type() -> None:
    """``type_cd`` must be ``String(2)`` — from COBOL ``DALYTRAN-TYPE-CD PIC X(02)``.

    The 2-character transaction-type code is a logical foreign key
    into the ``transaction_type`` lookup table (e.g., ``'01'`` =
    purchase, ``'02'`` = refund, ``'03'`` = payment). POSTTRAN reject
    code 103 (unknown type code) depends on the exact 2-character
    width here for its lookup join.
    """
    _assert_string_column("type_cd", 2)


@pytest.mark.unit
def test_cat_cd_type() -> None:
    """``cat_cd`` must be ``String(4)`` — from COBOL ``DALYTRAN-CAT-CD PIC 9(04)``.

    Although the COBOL source field is numeric (``PIC 9(04)``), it is
    stored as ``String(4)`` in the relational model to preserve
    leading zeros that are semantically meaningful (``'0001'`` is
    category 1, and converting to ``INTEGER`` would collapse
    ``'0001'`` and ``'1'`` to the same value, breaking VSAM-round-trip
    equality). This is the project-wide pattern for all COBOL
    zero-padded ``PIC 9`` category / code fields.
    """
    _assert_string_column("cat_cd", 4)


@pytest.mark.unit
def test_source_type() -> None:
    """``source`` must be ``String(10)`` — from COBOL ``DALYTRAN-SOURCE PIC X(10)``.

    The 10-character source code identifies the upstream system that
    produced the record (e.g., ``'POS'``, ``'ECOMMERCE'``, ``'ATM'``,
    ``'IVR'``). Typically blank-padded to the full 10 bytes. Audit
    and reconciliation reports join on this field.
    """
    _assert_string_column("source", 10)


@pytest.mark.unit
def test_description_type() -> None:
    """``description`` must be ``String(100)`` — from ``DALYTRAN-DESC PIC X(100)``.

    The 100-character free-text description is printed on statements
    (CREASTMT) and transaction reports (TRANREPT). The relational
    width must match the COBOL PIC exactly so that the permanent
    :class:`~src.shared.models.transaction.Transaction` row promoted
    from this staging record is truncation-safe.
    """
    _assert_string_column("description", 100)


@pytest.mark.unit
def test_merchant_id_type() -> None:
    """``merchant_id`` must be ``String(9)`` — from ``DALYTRAN-MERCHANT-ID PIC 9(09)``.

    Like :attr:`cat_cd`, this is a numeric-in-COBOL field stored as
    text in the relational model to preserve leading zeros. The
    9-character width matches the ISO-8583 standard acquiring
    institution identifier length. Defaults to an empty string for
    non-merchant transactions (payments, fees, etc.).
    """
    _assert_string_column("merchant_id", 9)


@pytest.mark.unit
def test_merchant_name_type() -> None:
    """``merchant_name`` must be ``String(50)`` — from ``DALYTRAN-MERCHANT-NAME PIC X(50)``.

    50-character denormalised copy of the merchant-directory name,
    captured at transaction time. This is intentionally a
    point-in-time snapshot — the statement rendering pipeline
    (CREASTMT) must show the merchant name as it appeared on the
    date of purchase, not the current (possibly re-branded) name.
    """
    _assert_string_column("merchant_name", 50)


@pytest.mark.unit
def test_merchant_city_type() -> None:
    """``merchant_city`` must be ``String(50)`` — from ``DALYTRAN-MERCHANT-CITY PIC X(50)``.

    50-character denormalised merchant city, captured at transaction
    time alongside ``merchant_name``. Part of the point-in-time
    snapshot contract.
    """
    _assert_string_column("merchant_city", 50)


@pytest.mark.unit
def test_merchant_zip_type() -> None:
    """``merchant_zip`` must be ``String(10)`` — from ``DALYTRAN-MERCHANT-ZIP PIC X(10)``.

    10-character merchant postal code. The 10-character width
    accommodates both the US 5-digit ZIP (``'62701     '``, blank-
    padded) and the US 9-digit ZIP+4 (``'62701-0001'``, with hyphen),
    as well as Canadian postal codes.
    """
    _assert_string_column("merchant_zip", 10)


@pytest.mark.unit
def test_card_num_type() -> None:
    """``card_num`` must be ``String(16)`` — from COBOL ``DALYTRAN-CARD-NUM PIC X(16)``.

    The 16-character card number (PAN) is a logical foreign key into
    the ``card_cross_reference`` table, from which POSTTRAN resolves
    the owning account. The exact 16-character width matches the
    ISO/IEC 7812 PAN standard for most major-network cards. POSTTRAN
    reject code 101 (card not found in cross-reference) depends on
    exact-match equality on this field.
    """
    _assert_string_column("card_num", 16)


@pytest.mark.unit
def test_orig_ts_type() -> None:
    """``orig_ts`` must be ``String(26)`` — from COBOL ``DALYTRAN-ORIG-TS PIC X(26)``.

    The 26-character **origination** timestamp is the moment the
    upstream system registered the transaction (ISO-8601
    ``YYYY-MM-DD HH:MM:SS.ffffff`` with microsecond precision —
    exactly 26 bytes). Stored as ``String(26)`` rather than a
    SQL ``TIMESTAMP`` to preserve the exact COBOL byte layout,
    which is what downstream reconciliation systems compare
    against.
    """
    _assert_string_column("orig_ts", 26)


@pytest.mark.unit
def test_proc_ts_type() -> None:
    """``proc_ts`` must be ``String(26)`` — from COBOL ``DALYTRAN-PROC-TS PIC X(26)``.

    The 26-character **processing** timestamp is the moment the
    transaction was accepted for posting by the daily driver. Paired
    with :attr:`orig_ts` — the gap between them is a useful latency
    metric on reporting dashboards. Like ``orig_ts``, stored as text
    rather than a native ``TIMESTAMP`` for byte-exact COBOL
    interoperability.

    Note that on the permanent :class:`~src.shared.models.transaction.Transaction`
    model (but NOT on ``DailyTransaction``), ``proc_ts`` carries a
    B-tree index (``ix_transaction_proc_ts``) because the
    transaction-list REST endpoint and the TRANREPT batch job both
    filter on it. The staging table is short-lived (rows are deleted
    as soon as POSTTRAN promotes them) and therefore does not need
    the same index.
    """
    _assert_string_column("proc_ts", 26)


# ============================================================================
# Phase 4: Monetary Field Tests — CRITICAL: Numeric(15, 2), NEVER float
#
# AAP §0.7.2 explicitly forbids ``float`` for ANY monetary column. Every
# COBOL ``PIC S9(n)V99`` field maps to SQLAlchemy ``Numeric(n+6, 2)`` or
# the matching precision that accommodates the full integer range. For
# ``DALYTRAN-AMT PIC S9(09)V99`` the relational target is ``Numeric(15, 2)``
# — 9 integer digits + 2 decimal digits + 4 digits of headroom for the
# absolute-value range (the sign bit contributes no storage in COBOL but
# does in NUMERIC).
# ============================================================================


@pytest.mark.unit
def test_amount_type() -> None:
    """``amount`` must be stored as ``Numeric(15, 2)``, NEVER float.

    The COBOL source field ``DALYTRAN-AMT PIC S9(09)V99`` represents a
    signed amount with up to 9 integer digits and exactly 2 decimal
    places. The relational target is ``Numeric(15, 2)``:

    * ``precision=15`` — covers the absolute value range of
      ``-999_999_999.99`` to ``+999_999_999.99`` (9 integer + 2
      fractional = 11 total digits of actual content, padded to 15
      for headroom as with the sibling ``Transaction.amount`` column).
    * ``scale=2``  — exactly 2 decimal places, matching ``V99``.

    This mapping MUST NOT be weakened to ``Float`` or ``Double`` under
    any circumstances. IEEE-754 floating-point cannot exactly
    represent decimal fractions such as ``0.10`` or ``0.20``, so
    every posting would accumulate drift. AAP §0.7.2 is explicit:
    *all monetary values must use Python* :class:`~decimal.Decimal`
    *with explicit two-decimal-place precision*.
    """
    assert "amount" in DailyTransaction.__table__.columns, (
        f"DailyTransaction must expose an 'amount' column; found {sorted(DailyTransaction.__table__.columns.keys())!r}"
    )
    amount_col = DailyTransaction.__table__.columns["amount"]

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

    When a ``DailyTransaction`` is constructed without an explicit
    ``amount`` (e.g., a reject record written by the daily driver
    before the amount is parsed), the ORM must populate ``amount``
    with ``Decimal('0.00')`` — the two-decimal-place zero. Several
    details matter here and are pinned by this test:

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

    This mirrors the default on the permanent
    :class:`~src.shared.models.transaction.Transaction.amount` column,
    closing the staging → permanent promotion contract.
    """
    amount_col = DailyTransaction.__table__.columns["amount"]
    default = amount_col.default

    assert default is not None, (
        "amount column must declare a default (expected "
        "Decimal('0.00')); found no default. Without a default, "
        "constructing a DailyTransaction without an explicit amount "
        "(as CBTRN01C sometimes does for unparsable reject records) "
        "would raise IntegrityError on flush."
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
# Phase 5: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 13 mapped columns on ``DailyTransaction`` must be NOT NULL.

    The COBOL source ``CVTRA06Y.cpy`` has no concept of ``NULL`` —
    every byte of every 350-byte ``DALYTRAN-RECORD`` is always
    initialised to its picture's zero-value (spaces for ``PIC X``,
    zeros for ``PIC 9`` / ``PIC S9``). The relational translation
    must preserve this invariant: no column may be ``NULL``.

    This test iterates the full expected column set (including
    ``tran_id`` which is implicitly NOT NULL as a primary key, and
    ``amount`` which is explicitly ``nullable=False`` with a
    ``Decimal('0.00')`` default) and pins every column's
    ``nullable`` flag to ``False``.

    The staging → permanent promotion performed by
    ``src/batch/jobs/posttran_job.py`` does not defensively handle
    ``NULL`` on any column of this table; allowing ``NULL`` here
    would create an undefined-behaviour branch in the POSTTRAN
    validation cascade and could corrupt downstream transaction
    totals or statement generation.

    Note on defaults vs nullability:

    * 4 columns — ``tran_id``, ``type_cd``, ``cat_cd``, ``source``,
      ``card_num`` — have no default. They MUST be provided by the
      caller at construction time.
    * 8 columns have a Python-side default. ``amount`` defaults to
      ``Decimal('0.00')``; the 7 remaining text columns default to
      the empty string ``""``.

    Either way, all 13 columns are ``nullable=False`` — the presence
    of a default does NOT relax the NOT NULL constraint, it merely
    satisfies it without caller input.
    """
    for column_name in _EXPECTED_COLUMNS:
        assert column_name in DailyTransaction.__table__.columns, (
            f"_EXPECTED_COLUMNS references {column_name!r} but the "
            f"DailyTransaction model does not expose such a column. "
            f"Found columns: "
            f"{sorted(DailyTransaction.__table__.columns.keys())!r}"
        )
        column = DailyTransaction.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column {column_name!r} must be NOT NULL. COBOL "
            f"CVTRA06Y.cpy has no NULL semantics — every byte of "
            f"every DALYTRAN-RECORD is initialised to its picture's "
            f"zero value. The POSTTRAN batch job "
            f"(src/batch/jobs/posttran_job.py) does not defensively "
            f"handle NULL on any column of this table; allowing "
            f"NULL here would create an undefined-behaviour branch. "
            f"Found nullable={column.nullable}"
        )


# ============================================================================
# Phase 6: Layout Parity with Transaction Model
#
# The DALYTRAN-RECORD (CVTRA06Y.cpy, 350 bytes) is byte-for-byte
# identical in layout to the permanent TRAN-RECORD (CVTRA05Y.cpy, 350
# bytes). The only differences between the two COBOL records are the
# field-name prefixes (``DALYTRAN-`` vs ``TRAN-``) and the target VSAM
# cluster. This invariant is what makes the POSTTRAN promotion step
# possible: a row can be inserted from ``daily_transactions`` into
# ``transactions`` with no column-level transformation — just a simple
# mapping of column names by position. This test pins that invariant
# at the Python ORM layer.
# ============================================================================


@pytest.mark.unit
def test_mirrors_transaction_layout() -> None:
    """``DailyTransaction`` and ``Transaction`` must share identical column layouts.

    Specifically, for every column on ``DailyTransaction``:

    1. A column with the **same name** must exist on ``Transaction``.
    2. The column **type class** (``String`` / ``Numeric``) must match.
    3. For ``String`` columns, the ``.length`` must match.
    4. For ``Numeric`` columns, both ``.precision`` and ``.scale``
       must match.

    This contract mirrors the COBOL-level invariant that
    ``DALYTRAN-RECORD`` (``CVTRA06Y.cpy``) and ``TRAN-RECORD``
    (``CVTRA05Y.cpy``) have identical layouts — only the field-name
    prefix differs (``DALYTRAN-`` vs ``TRAN-``). Preserving it at
    the Python layer is what allows the POSTTRAN promotion logic
    (``src/batch/jobs/posttran_job.py``) to use a simple
    column-by-column ``INSERT INTO transactions SELECT ... FROM
    daily_transactions`` without any width-expanding casts or
    type-conversion scaffolding.

    We do NOT require the two tables to be *entirely* identical:

    * ``Transaction`` may declare additional ``__table_args__`` such
      as indexes (``ix_transaction_proc_ts``). ``DailyTransaction``
      does not need these because the staging table is short-lived —
      rows are deleted as soon as POSTTRAN promotes them, so an
      index would only add write-amplification without query-time
      benefit.
    * The table names deliberately differ: ``daily_transactions`` vs
      ``transactions``.
    * The class docstrings and ``__repr__`` implementations differ.

    What we DO require: the set of column names is identical, and
    each column's SQLAlchemy type is structurally equivalent.
    """
    daily_columns = DailyTransaction.__table__.columns
    trans_columns = Transaction.__table__.columns

    daily_names = {c.name for c in daily_columns}
    trans_names = {c.name for c in trans_columns}

    # The column-name sets must be identical — same 13 names on both
    # tables. This is the "pure rename" contract between
    # DALYTRAN-RECORD and TRAN-RECORD.
    assert daily_names == trans_names, (
        f"DailyTransaction and Transaction must have identical column "
        f"names — the COBOL layouts DALYTRAN-RECORD (CVTRA06Y.cpy) "
        f"and TRAN-RECORD (CVTRA05Y.cpy) differ only by the field "
        f"prefix (DALYTRAN- vs TRAN-) and are byte-for-byte "
        f"structurally identical. This invariant must be preserved "
        f"at the Python layer so POSTTRAN can promote rows with a "
        f"simple SELECT-INSERT. "
        f"Missing from DailyTransaction: {sorted(trans_names - daily_names)!r}. "
        f"Extra on DailyTransaction: {sorted(daily_names - trans_names)!r}."
    )

    # Exactly 13 columns on each side — no more, no less.
    assert len(daily_columns) == 13, (
        f"DailyTransaction must have exactly 13 columns (for layout "
        f"parity with Transaction and the 13-field DALYTRAN-RECORD); "
        f"found {len(daily_columns)}"
    )
    assert len(trans_columns) == 13, (
        f"Transaction must have exactly 13 columns (for layout parity "
        f"with DailyTransaction and the 13-field TRAN-RECORD); found "
        f"{len(trans_columns)}"
    )

    # Per-column structural equivalence.
    for column_name in _EXPECTED_COLUMNS:
        daily_col = daily_columns[column_name]
        trans_col = trans_columns[column_name]

        # Same SQLAlchemy type class (String vs Numeric vs ...).
        assert type(daily_col.type) is type(trans_col.type), (
            f"Column {column_name!r} has mismatched type classes: "
            f"DailyTransaction declares "
            f"{type(daily_col.type).__name__} but Transaction "
            f"declares {type(trans_col.type).__name__}. The COBOL "
            f"source fields (DALYTRAN-* and TRAN-*) must have "
            f"identical PIC clauses."
        )

        # For String columns, the declared length must match. The
        # second isinstance assertion on the Transaction side is not
        # strictly necessary for correctness — the ``type(...) is
        # type(...)`` equality check above already guarantees both
        # are the same class — but it is required for mypy's type
        # narrowing to grant ``.length`` access on ``trans_col.type``.
        if isinstance(daily_col.type, String):
            assert isinstance(trans_col.type, String), (
                f"Column {column_name!r} type class agreement already "
                f"asserted above, but mypy narrowing requires an "
                f"explicit isinstance check on the Transaction side; "
                f"unexpected type {type(trans_col.type).__name__}"
            )
            assert daily_col.type.length == trans_col.type.length, (
                f"Column {column_name!r} has mismatched String "
                f"lengths: DailyTransaction declares String("
                f"{daily_col.type.length}) but Transaction declares "
                f"String({trans_col.type.length}). COBOL PIC widths "
                f"must match between DALYTRAN-RECORD and TRAN-RECORD."
            )

        # For Numeric columns, both precision AND scale must match.
        # As with the String branch above, the second isinstance
        # assertion is for mypy's benefit — SQLAlchemy's
        # :class:`~sqlalchemy.types.TypeEngine` base does not expose
        # ``.precision`` / ``.scale``, so narrowing is mandatory.
        if isinstance(daily_col.type, Numeric):
            assert isinstance(trans_col.type, Numeric), (
                f"Column {column_name!r} type class agreement already "
                f"asserted above, but mypy narrowing requires an "
                f"explicit isinstance check on the Transaction side; "
                f"unexpected type {type(trans_col.type).__name__}"
            )
            assert daily_col.type.precision == trans_col.type.precision, (
                f"Column {column_name!r} has mismatched Numeric "
                f"precision: DailyTransaction declares precision="
                f"{daily_col.type.precision} but Transaction declares "
                f"precision={trans_col.type.precision}. Monetary "
                f"columns must have identical widths across staging "
                f"and permanent tables to guarantee promotion "
                f"without truncation."
            )
            assert daily_col.type.scale == trans_col.type.scale, (
                f"Column {column_name!r} has mismatched Numeric "
                f"scale: DailyTransaction declares scale="
                f"{daily_col.type.scale} but Transaction declares "
                f"scale={trans_col.type.scale}. Monetary scale must "
                f"be identical across staging and permanent tables."
            )

        # Nullability must also match — both sides are NOT NULL.
        assert daily_col.nullable == trans_col.nullable, (
            f"Column {column_name!r} has mismatched nullability: "
            f"DailyTransaction.{column_name}.nullable="
            f"{daily_col.nullable} vs Transaction.{column_name}."
            f"nullable={trans_col.nullable}. Both columns must be "
            f"NOT NULL."
        )


# ============================================================================
# Phase 7: Instance Creation & __repr__ Tests
# ============================================================================


@pytest.mark.unit
def test_create_daily_transaction_instance() -> None:
    """A ``DailyTransaction`` instance must be creatable with all 13 fields.

    This end-to-end constructor test verifies that the model's
    ``__init__`` (auto-generated by SQLAlchemy's
    :class:`~sqlalchemy.orm.DeclarativeBase`) accepts keyword
    arguments for every mapped column, preserves them unchanged on
    the attribute access path, and produces a live instance of
    :class:`~src.shared.models.Base` — the shared declarative parent
    class — confirming proper metadata registration.

    The test constructs a realistic point-of-sale transaction with
    :data:`_SAMPLE_AMOUNT` set to ``Decimal('75.50')`` — a plausible
    retail figure that exercises the two-decimal-place scale without
    being a round number (which could mask trailing-zero-stripping
    bugs). AAP §0.7.2 requires :class:`~decimal.Decimal`, NEVER
    :class:`float`, for monetary values.

    Readback verification is explicit field-by-field to catch any
    silent coercion (e.g., an accidental ``str`` → ``int`` cast on
    ``merchant_id``, or a ``Decimal`` → ``float`` drop on
    ``amount``).

    No database session is required — this is a pure in-memory
    construction test; ``flush`` / ``commit`` semantics are covered
    by integration tests under ``tests/integration/``.
    """
    daily_tran = DailyTransaction(
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

    # The instance must be a live ``Base`` subclass instance so
    # SQLAlchemy recognises it for ORM operations. Guards against
    # accidental detachment from the declarative hierarchy (e.g.,
    # if DailyTransaction were ever refactored into a plain
    # dataclass).
    assert isinstance(daily_tran, Base), (
        f"DailyTransaction instance must be an instance of the shared "
        f"declarative Base class so SQLAlchemy recognises it for ORM "
        f"persistence; found MRO "
        f"{[cls.__name__ for cls in type(daily_tran).__mro__]}"
    )

    # Field-by-field readback — confirms that every keyword argument
    # round-trips unchanged through SQLAlchemy's attribute
    # instrumentation.
    assert daily_tran.tran_id == _SAMPLE_TRAN_ID, (
        f"tran_id must round-trip unchanged (PIC X(16)); found {daily_tran.tran_id!r}"
    )
    assert daily_tran.type_cd == _SAMPLE_TYPE_CD, (
        f"type_cd must round-trip unchanged (PIC X(02)); found {daily_tran.type_cd!r}"
    )
    assert daily_tran.cat_cd == _SAMPLE_CAT_CD, (
        f"cat_cd must round-trip unchanged as a 4-character "
        f"zero-padded string (PIC 9(04) stored as String(4)); found "
        f"{daily_tran.cat_cd!r}"
    )
    assert daily_tran.source == _SAMPLE_SOURCE, (
        f"source must round-trip unchanged including any trailing "
        f"blank padding (PIC X(10)); found {daily_tran.source!r}"
    )
    assert daily_tran.description == _SAMPLE_DESCRIPTION, (
        f"description must round-trip unchanged (PIC X(100)); found {daily_tran.description!r}"
    )
    assert daily_tran.amount == _SAMPLE_AMOUNT, (
        f"amount must round-trip exactly as Decimal('75.50'); found {daily_tran.amount!r}"
    )
    # Guard against silent float-coercion. Even if equality still
    # holds against ``Decimal('75.50')``, losing the Decimal type
    # here would introduce float arithmetic downstream.
    assert isinstance(daily_tran.amount, Decimal), (
        f"amount must remain a decimal.Decimal after construction "
        f"(AAP §0.7.2 — never float); found "
        f"{type(daily_tran.amount).__name__}"
    )
    assert daily_tran.merchant_id == _SAMPLE_MERCHANT_ID, (
        f"merchant_id must round-trip unchanged as a 9-character zero-padded string; found {daily_tran.merchant_id!r}"
    )
    assert daily_tran.merchant_name == _SAMPLE_MERCHANT_NAME, (
        f"merchant_name must round-trip unchanged (PIC X(50)); found {daily_tran.merchant_name!r}"
    )
    assert daily_tran.merchant_city == _SAMPLE_MERCHANT_CITY, (
        f"merchant_city must round-trip unchanged (PIC X(50)); found {daily_tran.merchant_city!r}"
    )
    assert daily_tran.merchant_zip == _SAMPLE_MERCHANT_ZIP, (
        f"merchant_zip must round-trip unchanged (PIC X(10)); found {daily_tran.merchant_zip!r}"
    )
    assert daily_tran.card_num == _SAMPLE_CARD_NUM, (
        f"card_num must round-trip unchanged (PIC X(16), 16 chars exact); found {daily_tran.card_num!r}"
    )
    assert daily_tran.orig_ts == _SAMPLE_ORIG_TS, (
        f"orig_ts must round-trip unchanged as a 26-character "
        f"ISO-timestamp string (PIC X(26)); found "
        f"{daily_tran.orig_ts!r}"
    )
    assert daily_tran.proc_ts == _SAMPLE_PROC_TS, (
        f"proc_ts must round-trip unchanged as a 26-character "
        f"ISO-timestamp string (PIC X(26)); found "
        f"{daily_tran.proc_ts!r}"
    )


@pytest.mark.unit
def test_daily_transaction_repr() -> None:
    """``__repr__`` must return a readable developer-friendly string.

    The model-side ``__repr__`` declared in
    ``src/shared/models/daily_transaction.py`` is intentionally
    selective — it includes only the 4 most diagnostically useful
    columns (``tran_id``, ``type_cd``, ``amount``, ``card_num``)
    rather than all 13. Including every column would clutter log
    lines and obscure the information most useful for debugging
    POSTTRAN failures.

    This test verifies:

    * The class name ``DailyTransaction`` is present — this
      distinguishes the repr from the permanent
      :class:`~src.shared.models.transaction.Transaction.__repr__`
      in mixed logs (layout parity tests would otherwise make them
      indistinguishable from their attribute content alone).
    * The 4 diagnostic attribute values appear in their ``repr()``
      form — strings rendered with their quotes, and in particular
      ``amount`` rendered as ``Decimal('...')`` (from
      :meth:`Decimal.__repr__`), NEVER as a bare float literal.
      Downstream log parsers and debugging dashboards rely on this
      distinction to flag float-drift regressions instantly.

    The 9 denormalised / timestamp fields (``cat_cd``, ``source``,
    ``description``, ``merchant_*``, ``orig_ts``, ``proc_ts``) are
    NOT required to appear in the repr — they are deliberately
    omitted to keep the output concise.
    """
    daily_tran = DailyTransaction(
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
    repr_output = repr(daily_tran)

    # The class name must appear at the start of the repr output.
    # This also disambiguates it from ``Transaction.__repr__``
    # which has an identical column layout but a different class
    # name.
    assert "DailyTransaction" in repr_output, (
        f"__repr__ must include the class name 'DailyTransaction' "
        f"for debuggability (and to disambiguate from the "
        f"permanent Transaction model which shares the same "
        f"column layout); found {repr_output!r}"
    )

    # Each of the 4 diagnostic attribute names must appear in the
    # repr. We do NOT assert the presence of the other 9 column
    # names — they are deliberately omitted by the model's
    # selective repr.
    for attribute_name in ("tran_id", "type_cd", "amount", "card_num"):
        assert attribute_name in repr_output, (
            f"__repr__ must include the attribute name "
            f"{attribute_name!r} (one of the 4 diagnostic columns); "
            f"found {repr_output!r}"
        )

    # Each diagnostic attribute value must be rendered via ``repr()``
    # of the underlying Python value, which (for strings) includes
    # surrounding quotes — this is why we search for ``repr(v)`` not
    # ``str(v)``.
    assert repr(_SAMPLE_TRAN_ID) in repr_output, (
        f"__repr__ must include the tran_id value rendered via "
        f"repr() (expected substring {repr(_SAMPLE_TRAN_ID)!r}); "
        f"found {repr_output!r}"
    )
    assert repr(_SAMPLE_TYPE_CD) in repr_output, (
        f"__repr__ must include the type_cd value rendered via "
        f"repr() (expected substring {repr(_SAMPLE_TYPE_CD)!r}); "
        f"found {repr_output!r}"
    )
    assert repr(_SAMPLE_CARD_NUM) in repr_output, (
        f"__repr__ must include the card_num value rendered via "
        f"repr() (expected substring {repr(_SAMPLE_CARD_NUM)!r}); "
        f"found {repr_output!r}"
    )

    # CRITICAL: the monetary amount must be rendered as Decimal(...)
    # to make float-drift regressions immediately obvious in logs.
    assert repr(_SAMPLE_AMOUNT) in repr_output, (
        f"__repr__ must render amount as "
        f"{repr(_SAMPLE_AMOUNT)!r} (NOT as a float literal such as "
        f"'75.5'); found {repr_output!r}. Any deviation breaks the "
        f"log-parser conventions that surface float-drift "
        f"regressions (AAP §0.7.2)."
    )


# ============================================================================
# Phase 8: FILLER Exclusion
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """COBOL ``FILLER PIC X(20)`` must NOT be mapped as a column.

    The COBOL ``DALYTRAN-RECORD`` ends with ``FILLER PIC X(20)``
    padding that rounds the 330-byte data payload (16 + 2 + 4 + 10 +
    100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 = 330) out to the
    fixed 350-byte VSAM-compatible record length. Filler is a
    byte-alignment artefact of the fixed-length VSAM KSDS record
    format — it has no semantic meaning and no valid column-level
    representation.

    Mapping ``FILLER`` to a column in the relational model would be
    a bug for multiple reasons:

    * It would bloat every row with 20 bytes of meaningless padding.
    * It would introduce a nullable-or-default column without a
      business interpretation, breaking the NOT-NULL invariant
      asserted in :func:`test_non_nullable_fields`.
    * It would break the layout-parity contract with
      :class:`~src.shared.models.transaction.Transaction`, whose
      sibling ``CVTRA05Y.cpy`` source also has ``FILLER`` that is
      correctly dropped. :func:`test_mirrors_transaction_layout`
      would fail — only one of the two sides could have the bogus
      filler column.
    * It would break the POSTTRAN promotion contract, because
      ``INSERT INTO transactions SELECT ... FROM
      daily_transactions`` would have a source column with no
      matching destination.

    Two assertions guard against regressions:

    * **Positive** — the mapped column set is *exactly* the
      expected set of 13 columns.
    * **Negative** — no column name contains the substring
      ``"filler"`` (case-insensitive), catching any misspelling
      (``Filler``, ``FILLER``, ``filler1``, ``filler_padding``,
      etc.).
    """
    column_names = [c.name for c in DailyTransaction.__table__.columns]

    # Positive invariant — exact-set equivalence against the
    # FILLER-free expected set.
    assert set(column_names) == set(_EXPECTED_COLUMNS), (
        f"DailyTransaction columns must exactly match "
        f"{sorted(_EXPECTED_COLUMNS)!r} (COBOL FILLER PIC X(20) is "
        f"NOT mapped); found {sorted(column_names)!r}. "
        f"Missing: "
        f"{sorted(set(_EXPECTED_COLUMNS) - set(column_names))!r}. "
        f"Extra:   "
        f"{sorted(set(column_names) - set(_EXPECTED_COLUMNS))!r}."
    )

    # Negative invariant — catches any misspelling of FILLER (Filler,
    # FILLER, filler1, filler_padding, ...).
    for column_name in column_names:
        assert "filler" not in column_name.lower(), (
            f"Column {column_name!r} appears to map COBOL FILLER "
            f"padding, which is forbidden. COBOL FILLER PIC X(20) "
            f"has no semantic meaning and must NOT appear in the "
            f"relational model. Remove it from "
            f"src/shared/models/daily_transaction.py."
        )
