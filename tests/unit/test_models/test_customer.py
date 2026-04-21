# ============================================================================
# Source: COBOL copybook CVCUS01Y.cpy — CUSTOMER-RECORD (500 bytes, VSAM KSDS)
# ============================================================================
# Unit tests for the Customer SQLAlchemy 2.x ORM model. Validates the
# translation of the 500-byte fixed-width VSAM CUSTOMER-RECORD record
# layout into a normalized Aurora PostgreSQL table. The CUST-ID field at
# offset 0 (9 bytes) is the VSAM cluster primary key as defined by
# app/jcl/CUSTFILE.jcl (DEFINE CLUSTER KEYS(9 0), RECSZ(500 500)).
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
"""Unit tests for the :class:`Customer` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CVCUS01Y.cpy`` (record layout ``CUSTOMER-RECORD``, a 500-byte
VSAM KSDS record with a 9-byte primary key) into the SQLAlchemy 2.x
declarative ORM model at ``src/shared/models/customer.py``.

COBOL Source Layout (``CVCUS01Y.cpy``)
--------------------------------------
::

    01 CUSTOMER-RECORD.
      05 CUST-ID                      PIC 9(09).
      05 CUST-FIRST-NAME              PIC X(25).
      05 CUST-MIDDLE-NAME             PIC X(25).
      05 CUST-LAST-NAME               PIC X(25).
      05 CUST-ADDR-LINE-1             PIC X(50).
      05 CUST-ADDR-LINE-2             PIC X(50).
      05 CUST-ADDR-LINE-3             PIC X(50).
      05 CUST-ADDR-STATE-CD           PIC X(02).
      05 CUST-ADDR-COUNTRY-CD         PIC X(03).
      05 CUST-ADDR-ZIP                PIC X(10).
      05 CUST-PHONE-NUM-1             PIC X(15).
      05 CUST-PHONE-NUM-2             PIC X(15).
      05 CUST-SSN                     PIC 9(09).       <-- sensitive
      05 CUST-GOVT-ISSUED-ID          PIC X(20).
      05 CUST-DOB-YYYY-MM-DD          PIC X(10).
      05 CUST-EFT-ACCOUNT-ID          PIC X(10).
      05 CUST-PRI-CARD-HOLDER-IND     PIC X(01).
      05 CUST-FICO-CREDIT-SCORE       PIC 9(03).
      05 FILLER                       PIC X(168).      <-- NOT mapped (padding)

COBOL -> Python Field Mapping
-----------------------------
==========================  ==============  =======================  ==========================
COBOL Field                 COBOL Type      Python Attr              SQLAlchemy Type
==========================  ==============  =======================  ==========================
CUST-ID                     ``PIC 9(09)``   ``cust_id``              ``String(9)`` — PK †
CUST-FIRST-NAME             ``PIC X(25)``   ``first_name``           ``String(25)``
CUST-MIDDLE-NAME            ``PIC X(25)``   ``middle_name``          ``String(25)``
CUST-LAST-NAME              ``PIC X(25)``   ``last_name``            ``String(25)``
CUST-ADDR-LINE-1            ``PIC X(50)``   ``addr_line_1``          ``String(50)``
CUST-ADDR-LINE-2            ``PIC X(50)``   ``addr_line_2``          ``String(50)``
CUST-ADDR-LINE-3            ``PIC X(50)``   ``addr_line_3``          ``String(50)``
CUST-ADDR-STATE-CD          ``PIC X(02)``   ``state_cd``             ``String(2)``
CUST-ADDR-COUNTRY-CD        ``PIC X(03)``   ``country_cd``           ``String(3)``
CUST-ADDR-ZIP               ``PIC X(10)``   ``addr_zip``             ``String(10)``
CUST-PHONE-NUM-1            ``PIC X(15)``   ``phone_num_1``          ``String(15)``
CUST-PHONE-NUM-2            ``PIC X(15)``   ``phone_num_2``          ``String(15)``
CUST-SSN                    ``PIC 9(09)``   ``ssn``                  ``String(9)`` — sensitive †
CUST-GOVT-ISSUED-ID         ``PIC X(20)``   ``govt_issued_id``       ``String(20)``
CUST-DOB-YYYY-MM-DD         ``PIC X(10)``   ``dob``                  ``String(10)``
CUST-EFT-ACCOUNT-ID         ``PIC X(10)``   ``eft_account_id``       ``String(10)``
CUST-PRI-CARD-HOLDER-IND    ``PIC X(01)``   ``pri_card_holder_ind``  ``String(1)``
CUST-FICO-CREDIT-SCORE      ``PIC 9(03)``   ``fico_credit_score``    ``Integer`` ‡
FILLER                      ``PIC X(168)``  (not mapped)             (COBOL padding only)
==========================  ==============  =======================  ==========================

† **Numeric identifiers stored as strings.** ``CUST-ID`` (``PIC 9(09)``)
and ``CUST-SSN`` (``PIC 9(09)``) are numeric in the source record layout
but map to ``String(9)`` on the Python / PostgreSQL side to preserve
leading zeros from migrated VSAM records (e.g., ``'000000001'``). Storing
these as numeric types would silently strip the leading zeros at INSERT
time, breaking byte-for-byte parity with the VSAM KSDS fixtures in
``app/data/ASCII/custdata.txt``. Byte-accurate preservation is required
by AAP §0.7.1 ("preserve all existing functionality exactly as-is").

‡ **FICO credit score is Integer.** ``CUST-FICO-CREDIT-SCORE``
(``PIC 9(03)``) maps cleanly to a PostgreSQL ``INTEGER`` because FICO
scores are always in the range 300 – 850 (never with meaningful leading
zeros). This is the *sole* non-string column in the Customer entity.
Storing it as an integer enables efficient server-side ordering, range
predicates, and aggregation queries (e.g., credit-tier reporting)
without application-side string-to-integer coercion.

Total RECLN: 9 + (25 × 3) + (50 × 3) + 2 + 3 + 10 + (15 × 2) + 9 + 20
+ 10 + 10 + 1 + 3 + 168 = **500 bytes** — matches the VSAM cluster
definition in ``app/jcl/CUSTFILE.jcl`` (``RECSZ(500 500)``).

SSN Sensitivity
---------------
The ``ssn`` column is classified as sensitive by AAP §0.5.1 (the
``customer`` transformation row: "encrypted SSN field") and AAP §0.7.2
("Security Requirements"). The ORM does NOT apply any cryptographic
transformation at this layer — column-level encryption (pgcrypto, AWS
KMS-backed column encryption, or application-layer cryptography in the
service layer) is the responsibility of the Aurora PostgreSQL
persistence configuration. The model's ``__repr__`` method deliberately
excludes the ssn value to prevent accidental leakage into log streams,
tracebacks, or interactive debugger output (verified by
:func:`test_customer_repr`).

Table-Name Contract
-------------------
``Customer.__tablename__`` MUST be ``"customers"`` (plural) to align
with:

* ``db/migrations/V1__schema.sql`` — ``CREATE TABLE customers``
* ``db/migrations/V3__seed_data.sql`` — 50-row seed from
  ``app/data/ASCII/custdata.txt``
* Cross-table joins from Account (F-004 / F-005) and CardCrossReference
  (F-006 / F-011) that reference the ``customers`` table by name in
  raw-SQL migrations and PySpark JDBC queries.

Any drift between ``Customer.__tablename__`` and the DDL / seed-data
contract would cause runtime ``UndefinedTable`` errors, so this
invariant is pinned by :func:`test_tablename`.

Test Coverage (27 functions — matches the file schema's ``exports`` contract)
-----------------------------------------------------------------------------
Phase 2 — Table & Column Metadata (3 tests):

1.  :func:`test_tablename`                       — ``__tablename__ == "customers"``.
2.  :func:`test_column_count`                    — Exactly 18 mapped columns.
3.  :func:`test_primary_key_cust_id`             — ``cust_id`` is the sole PK.

Phase 3 — Column Type Tests (18 tests, one per mapped column):

4.  :func:`test_cust_id_type`                    — ``String(9)``  (CUST-ID PIC 9(09))
5.  :func:`test_first_name_type`                 — ``String(25)`` (CUST-FIRST-NAME)
6.  :func:`test_middle_name_type`                — ``String(25)`` (CUST-MIDDLE-NAME)
7.  :func:`test_last_name_type`                  — ``String(25)`` (CUST-LAST-NAME)
8.  :func:`test_addr_line_1_type`                — ``String(50)`` (CUST-ADDR-LINE-1)
9.  :func:`test_addr_line_2_type`                — ``String(50)`` (CUST-ADDR-LINE-2)
10. :func:`test_addr_line_3_type`                — ``String(50)`` (CUST-ADDR-LINE-3)
11. :func:`test_state_cd_type`                   — ``String(2)``  (CUST-ADDR-STATE-CD)
12. :func:`test_country_cd_type`                 — ``String(3)``  (CUST-ADDR-COUNTRY-CD)
13. :func:`test_addr_zip_type`                   — ``String(10)`` (CUST-ADDR-ZIP)
14. :func:`test_phone_num_1_type`                — ``String(15)`` (CUST-PHONE-NUM-1)
15. :func:`test_phone_num_2_type`                — ``String(15)`` (CUST-PHONE-NUM-2)
16. :func:`test_ssn_type`                        — ``String(9)``  (CUST-SSN — sensitive)
17. :func:`test_govt_issued_id_type`             — ``String(20)`` (CUST-GOVT-ISSUED-ID)
18. :func:`test_dob_type`                        — ``String(10)`` (CUST-DOB-YYYY-MM-DD)
19. :func:`test_eft_account_id_type`             — ``String(10)`` (CUST-EFT-ACCOUNT-ID)
20. :func:`test_pri_card_holder_ind_type`        — ``String(1)``  (CUST-PRI-CARD-HOLDER-IND)
21. :func:`test_fico_credit_score_type`          — ``Integer``    (CUST-FICO-CREDIT-SCORE)

Phase 4 — NOT NULL & Default Tests (3 tests):

22. :func:`test_non_nullable_required_fields`    — cust_id, first_name, last_name, ssn NOT NULL.
23. :func:`test_default_values`                  — 13 optional string cols default to ``""``.
24. :func:`test_fico_default`                    — ``fico_credit_score`` defaults to ``0``.

Phase 5 — Instance Creation Tests (2 tests):

25. :func:`test_create_customer_instance`        — Full-instance construction & readback.
26. :func:`test_customer_repr`                   — ``__repr__`` format & SSN exclusion.

Phase 6 — FILLER Exclusion (1 test):

27. :func:`test_no_filler_columns`               — COBOL ``FILLER`` is NOT mapped.

See Also
--------
``src/shared/models/customer.py``       — The ORM model under test.
``app/cpy/CVCUS01Y.cpy``                — Original COBOL record layout.
``app/jcl/CUSTFILE.jcl``                — VSAM cluster definition
                                          (RECSZ(500 500), KEYS(9 0)).
``app/data/ASCII/custdata.txt``         — 50-row seed fixture.
``db/migrations/V1__schema.sql``        — ``CREATE TABLE customers`` DDL.
``db/migrations/V3__seed_data.sql``     — ``INSERT INTO customers``.
AAP §0.5.1                              — File-by-File Transformation Plan.
AAP §0.7.1                              — Refactoring-Specific Rules
                                          (minimal change clause).
AAP §0.7.2                              — Security Requirements
                                          (SSN column-level encryption).
``tests.unit.test_models.__init__``     — Package docstring listing the
                                          full model-to-copybook mapping.
"""

from __future__ import annotations

import pytest
from sqlalchemy import Integer, String, inspect

from src.shared.models import Base
from src.shared.models.customer import Customer

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# The set of 18 expected mapped column names (Python attribute names,
# also the SQL column names under SQLAlchemy's default resolution).
# The COBOL ``FILLER PIC X(168)`` region is DELIBERATELY absent —
# trailing padding has no place in the relational model.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "cust_id",  # from CUST-ID                  PIC 9(09) — PK
        "first_name",  # from CUST-FIRST-NAME       PIC X(25)
        "middle_name",  # from CUST-MIDDLE-NAME     PIC X(25)
        "last_name",  # from CUST-LAST-NAME         PIC X(25)
        "addr_line_1",  # from CUST-ADDR-LINE-1     PIC X(50)
        "addr_line_2",  # from CUST-ADDR-LINE-2     PIC X(50)
        "addr_line_3",  # from CUST-ADDR-LINE-3     PIC X(50)
        "state_cd",  # from CUST-ADDR-STATE-CD      PIC X(02)
        "country_cd",  # from CUST-ADDR-COUNTRY-CD  PIC X(03)
        "addr_zip",  # from CUST-ADDR-ZIP           PIC X(10)
        "phone_num_1",  # from CUST-PHONE-NUM-1     PIC X(15)
        "phone_num_2",  # from CUST-PHONE-NUM-2     PIC X(15)
        "ssn",  # from CUST-SSN                     PIC 9(09) — sensitive
        "govt_issued_id",  # from CUST-GOVT-ISSUED-ID PIC X(20)
        "dob",  # from CUST-DOB-YYYY-MM-DD          PIC X(10)
        "eft_account_id",  # from CUST-EFT-ACCOUNT-ID PIC X(10)
        "pri_card_holder_ind",  # from CUST-PRI-CARD-HOLDER-IND PIC X(01)
        "fico_credit_score",  # from CUST-FICO-CREDIT-SCORE PIC 9(03)
    }
)

# The subset of columns that are strictly required — i.e., have NO
# client-side default and therefore MUST be provided by the caller at
# INSERT time. These mirror the four COBOL fields whose semantic meaning
# prohibits a blank / zero default:
#
# * cust_id     — primary key; NULL PKs are rejected at the SQL level.
# * first_name  — cardholder given name; no "anonymous customer" default.
# * last_name   — cardholder surname; no blank-value convention in the
#                 copybook.
# * ssn         — 9-digit identifier; the only blank-or-zero value that
#                 would be valid is itself disallowed (blank SSNs would
#                 break downstream reconciliation and are rejected at the
#                 API layer).
_STRICTLY_REQUIRED_COLUMNS: frozenset[str] = frozenset({"cust_id", "first_name", "last_name", "ssn"})

# The 13 optional string columns that default to the empty string.
# These correspond to the COBOL fields that historically carried
# SPACES-initialized values in the VSAM records (address lines,
# secondary phone, secondary government ID, etc.). Preserving the
# default of "" rather than ``None`` maintains byte-for-byte parity
# with VSAM SPACES-initialized data when the record is populated by
# partial API requests (AAP §0.7.1 "minimal change clause").
_OPTIONAL_STRING_COLUMNS_WITH_EMPTY_DEFAULT: frozenset[str] = frozenset(
    {
        "middle_name",
        "addr_line_1",
        "addr_line_2",
        "addr_line_3",
        "state_cd",
        "country_cd",
        "addr_zip",
        "phone_num_1",
        "phone_num_2",
        "govt_issued_id",
        "dob",
        "eft_account_id",
        "pri_card_holder_ind",
    }
)


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """Customer must be mapped to the ``customers`` table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE customers``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO customers``
      (50 rows from ``app/data/ASCII/custdata.txt``)
    * The online flows that SELECT / UPDATE this table:
      F-004 Account View (``COACTVWC`` — 3-entity join with Account +
      CardCrossReference) and F-005 Account Update (``COACTUPC`` —
      dual-write atomic update).
    * The batch flow that JOINs this table:
      CREASTMT / Statement Generation (``CBSTM03A``) — 4-entity join
      including Customer for statement header rendering.

    Any drift between ``Customer.__tablename__`` and the DDL /
    seed-data contract would cause runtime ``UndefinedTable`` errors,
    so this invariant is pinned. The setup-agent's historical note
    about singular-vs-plural table-name mismatch (setup-log item #1)
    was resolved by pluralizing ``__tablename__`` values across all
    11 ORM models, matching the ``CREATE TABLE <plural>`` convention
    in ``V1__schema.sql``.
    """
    assert Customer.__tablename__ == "customers", (
        f"Customer.__tablename__ must be 'customers' to match "
        f"db/migrations/V1__schema.sql (CREATE TABLE customers) and "
        f"V3__seed_data.sql; found {Customer.__tablename__!r}"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """Customer must expose exactly 18 mapped columns.

    The COBOL ``CUSTOMER-RECORD`` layout has 19 fields, but only 18
    are mapped to the relational model. ``FILLER PIC X(168)`` is
    deliberately dropped because trailing padding — used in the
    500-byte fixed-width VSAM record to reach the ``RECSZ(500 500)``
    target (see ``app/jcl/CUSTFILE.jcl``) — has no storage or
    semantic meaning in a column-typed schema.

    Ensuring the count is exactly 18 guards against two regressions:

    * An accidental ``filler`` column being added back (would raise
      the count to 19).
    * A field being accidentally removed from the model (would drop
      the count below 18 and break API / batch contracts).
    """
    columns = Customer.__table__.columns
    assert len(columns) == 18, (
        f"Customer must have exactly 18 columns (CUST-ID, three name "
        f"fields, three address lines, state/country/zip, two phones, "
        f"SSN, gov-ID, DOB, EFT account, primary-cardholder indicator, "
        f"FICO score); found {len(columns)}: {[c.name for c in columns]}"
    )


@pytest.mark.unit
def test_primary_key_cust_id() -> None:
    """The sole primary key is ``cust_id`` (from COBOL CUST-ID).

    Maps to the VSAM KSDS primary key slot (offset 0, length 9) of
    the ``CUSTFILE`` dataset. Replaces the mainframe VSAM
    ``DEFINE CLUSTER KEYS(9 0)`` clause from ``app/jcl/CUSTFILE.jcl``.

    Verifies all three invariants:

    * :class:`sqlalchemy.inspect` reports ``cust_id`` as the (single)
      primary key column — no composite PK.
    * The PK column name is ``cust_id`` — matches the Python attribute
      naming convention used by the rest of the ORM.
    * The PK column's type is ``String(9)`` — COBOL ``PIC 9(09)`` is
      stored as a 9-character string to preserve leading zeros (e.g.,
      ``'000000001'``) when the column is populated from VSAM fixture
      data. Storing the PK as ``Integer`` would silently strip leading
      zeros at INSERT time and break joins from Account/CardCrossRef.
    """
    primary_keys = list(inspect(Customer).primary_key)

    # Exactly one PK column (no composite key for Customer).
    assert len(primary_keys) == 1, (
        f"Customer must have exactly one primary key column "
        f"(CUST-ID); found {len(primary_keys)}: "
        f"{[pk.name for pk in primary_keys]}"
    )

    pk_column = primary_keys[0]
    assert pk_column.name == "cust_id", (
        f"Primary key column must be 'cust_id' (from COBOL CUST-ID PIC 9(09)); found {pk_column.name!r}"
    )

    # PK type validation — must be String(9) to preserve leading zeros
    # from COBOL PIC 9(09) numeric-but-zero-padded identifiers.
    assert isinstance(pk_column.type, String), (
        f"Primary key 'cust_id' must be SQLAlchemy String (to preserve "
        f"leading zeros from COBOL PIC 9(09)); found "
        f"{type(pk_column.type).__name__}"
    )
    assert pk_column.type.length == 9, (
        f"Primary key 'cust_id' must be String(9) (from COBOL CUST-ID PIC 9(09)); found String({pk_column.type.length})"
    )


# ============================================================================
# Phase 3: Column Type Tests (18 columns)
# ============================================================================


@pytest.mark.unit
def test_cust_id_type() -> None:
    """``cust_id`` column is ``String(9)`` (from COBOL CUST-ID PIC 9(09)).

    Although the COBOL picture is numeric (``PIC 9(09)``), the Python
    column is a 9-character string to preserve leading-zero identifiers
    (e.g., ``'000000001'``) from the VSAM fixture
    ``app/data/ASCII/custdata.txt``. Storing this as ``Integer`` would
    silently truncate the zero padding at INSERT time and break the
    byte-for-byte parity required by AAP §0.7.1.
    """
    column = Customer.__table__.columns["cust_id"]
    assert isinstance(column.type, String), f"cust_id must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 9, (
        f"cust_id must be String(9) (from COBOL CUST-ID PIC 9(09)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_first_name_type() -> None:
    """``first_name`` column is ``String(25)`` (from COBOL CUST-FIRST-NAME PIC X(25))."""
    column = Customer.__table__.columns["first_name"]
    assert isinstance(column.type, String), f"first_name must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 25, (
        f"first_name must be String(25) (from COBOL CUST-FIRST-NAME PIC X(25)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_middle_name_type() -> None:
    """``middle_name`` column is ``String(25)`` (from COBOL CUST-MIDDLE-NAME PIC X(25))."""
    column = Customer.__table__.columns["middle_name"]
    assert isinstance(column.type, String), f"middle_name must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 25, (
        f"middle_name must be String(25) (from COBOL CUST-MIDDLE-NAME PIC X(25)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_last_name_type() -> None:
    """``last_name`` column is ``String(25)`` (from COBOL CUST-LAST-NAME PIC X(25))."""
    column = Customer.__table__.columns["last_name"]
    assert isinstance(column.type, String), f"last_name must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 25, (
        f"last_name must be String(25) (from COBOL CUST-LAST-NAME PIC X(25)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_addr_line_1_type() -> None:
    """``addr_line_1`` column is ``String(50)`` (from COBOL CUST-ADDR-LINE-1 PIC X(50))."""
    column = Customer.__table__.columns["addr_line_1"]
    assert isinstance(column.type, String), f"addr_line_1 must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 50, (
        f"addr_line_1 must be String(50) (from COBOL CUST-ADDR-LINE-1 PIC X(50)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_addr_line_2_type() -> None:
    """``addr_line_2`` column is ``String(50)`` (from COBOL CUST-ADDR-LINE-2 PIC X(50))."""
    column = Customer.__table__.columns["addr_line_2"]
    assert isinstance(column.type, String), f"addr_line_2 must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 50, (
        f"addr_line_2 must be String(50) (from COBOL CUST-ADDR-LINE-2 PIC X(50)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_addr_line_3_type() -> None:
    """``addr_line_3`` column is ``String(50)`` (from COBOL CUST-ADDR-LINE-3 PIC X(50))."""
    column = Customer.__table__.columns["addr_line_3"]
    assert isinstance(column.type, String), f"addr_line_3 must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 50, (
        f"addr_line_3 must be String(50) (from COBOL CUST-ADDR-LINE-3 PIC X(50)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_state_cd_type() -> None:
    """``state_cd`` column is ``String(2)`` (from COBOL CUST-ADDR-STATE-CD PIC X(02)).

    Holds 2-character US state abbreviations (``'NY'``, ``'CA'``,
    ``'TX'``, etc.). The 2-character width is preserved exactly from
    the COBOL picture clause per AAP §0.7.1.
    """
    column = Customer.__table__.columns["state_cd"]
    assert isinstance(column.type, String), f"state_cd must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 2, (
        f"state_cd must be String(2) (from COBOL CUST-ADDR-STATE-CD PIC X(02)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_country_cd_type() -> None:
    """``country_cd`` column is ``String(3)`` (from COBOL CUST-ADDR-COUNTRY-CD PIC X(03)).

    Holds ISO 3166-1 alpha-3 country codes (``'USA'``, ``'CAN'``,
    ``'GBR'``, etc.). The 3-character width accommodates the standard
    ISO code exactly.
    """
    column = Customer.__table__.columns["country_cd"]
    assert isinstance(column.type, String), f"country_cd must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 3, (
        f"country_cd must be String(3) (from COBOL CUST-ADDR-COUNTRY-CD PIC X(03)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_addr_zip_type() -> None:
    """``addr_zip`` column is ``String(10)`` (from COBOL CUST-ADDR-ZIP PIC X(10)).

    The 10-character width accommodates 5-digit US ZIP codes, 9-digit
    ZIP+4 with hyphen (``'12345-6789'``), and international postal
    codes. Storing as a string preserves formatting characters and
    leading zeros (e.g., Massachusetts ZIPs starting with ``'01'``).
    """
    column = Customer.__table__.columns["addr_zip"]
    assert isinstance(column.type, String), f"addr_zip must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 10, (
        f"addr_zip must be String(10) (from COBOL CUST-ADDR-ZIP PIC X(10)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_phone_num_1_type() -> None:
    """``phone_num_1`` column is ``String(15)`` (from COBOL CUST-PHONE-NUM-1 PIC X(15)).

    Stored as a free-format string to preserve human-readable
    formatting (e.g., ``'(555) 123-4567'``, ``'+1-555-1234'``).
    Numeric storage would lose formatting and make the migration
    from VSAM fixture data lossy.
    """
    column = Customer.__table__.columns["phone_num_1"]
    assert isinstance(column.type, String), f"phone_num_1 must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 15, (
        f"phone_num_1 must be String(15) (from COBOL CUST-PHONE-NUM-1 PIC X(15)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_phone_num_2_type() -> None:
    """``phone_num_2`` column is ``String(15)`` (from COBOL CUST-PHONE-NUM-2 PIC X(15))."""
    column = Customer.__table__.columns["phone_num_2"]
    assert isinstance(column.type, String), f"phone_num_2 must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 15, (
        f"phone_num_2 must be String(15) (from COBOL CUST-PHONE-NUM-2 PIC X(15)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_ssn_type() -> None:
    """``ssn`` column is ``String(9)`` (from COBOL CUST-SSN PIC 9(09)).

    **SENSITIVE FIELD.** Per AAP §0.5.1 and §0.7.2, this column is
    classified as sensitive and requires column-level encryption at
    the Aurora PostgreSQL persistence layer (e.g., pgcrypto, AWS
    KMS-backed column encryption, or application-layer cryptography
    in the service layer). The ORM intentionally stores the column
    as plain ``String(9)`` here — cryptographic transformation is
    delegated to the infrastructure boundary, keeping the COBOL
    copybook mapping byte-accurate.

    The column is stored as ``String(9)`` rather than ``Integer`` to
    preserve leading zeros from COBOL ``PIC 9(09)`` (e.g., an SSN
    starting with ``'012-34-5678'`` stores as ``'012345678'`` without
    loss). Integer storage would silently strip the leading zero.

    The :func:`test_customer_repr` test verifies that the ``ssn``
    value is deliberately excluded from ``__repr__`` output to
    prevent accidental leakage into log streams and tracebacks.
    """
    column = Customer.__table__.columns["ssn"]
    assert isinstance(column.type, String), (
        f"ssn must be SQLAlchemy String (to preserve leading zeros from "
        f"COBOL PIC 9(09) and to allow column-level encryption per AAP "
        f"§0.7.2); found {type(column.type).__name__}"
    )
    assert column.type.length == 9, (
        f"ssn must be String(9) (from COBOL CUST-SSN PIC 9(09)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_govt_issued_id_type() -> None:
    """``govt_issued_id`` column is ``String(20)`` (from COBOL CUST-GOVT-ISSUED-ID PIC X(20)).

    Holds up to 20 characters of government-issued identifier —
    driver's license number, passport number, state ID, etc. The
    20-character width is preserved exactly from the COBOL
    picture clause.
    """
    column = Customer.__table__.columns["govt_issued_id"]
    assert isinstance(column.type, String), (
        f"govt_issued_id must be SQLAlchemy String; found {type(column.type).__name__}"
    )
    assert column.type.length == 20, (
        f"govt_issued_id must be String(20) (from COBOL "
        f"CUST-GOVT-ISSUED-ID PIC X(20)); found "
        f"String({column.type.length})"
    )


@pytest.mark.unit
def test_dob_type() -> None:
    """``dob`` column is ``String(10)`` (from COBOL CUST-DOB-YYYY-MM-DD PIC X(10)).

    Stored as a 10-character string in ``YYYY-MM-DD`` format,
    matching the COBOL source layout byte-for-byte. Date validation
    is delegated to :mod:`src.shared.utils.date_utils` (preserving
    the ``CSUTLDTC`` / ``CSUTLDWY`` validation rules — see AAP §0.7.1
    "minimal change clause"). Avoiding a native ``Date`` type here
    eliminates implicit coercion and keeps VSAM-to-PostgreSQL
    migration byte-accurate.
    """
    column = Customer.__table__.columns["dob"]
    assert isinstance(column.type, String), f"dob must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 10, (
        f"dob must be String(10) in 'YYYY-MM-DD' format (from COBOL "
        f"CUST-DOB-YYYY-MM-DD PIC X(10)); found "
        f"String({column.type.length})"
    )


@pytest.mark.unit
def test_eft_account_id_type() -> None:
    """``eft_account_id`` column is ``String(10)`` (from COBOL CUST-EFT-ACCOUNT-ID PIC X(10)).

    Used by the F-012 Bill Payment flow (``COBIL00C`` →
    ``bill_service.pay()``) to source funds for bill-pay debits. The
    10-character width matches the COBOL picture clause exactly.
    """
    column = Customer.__table__.columns["eft_account_id"]
    assert isinstance(column.type, String), (
        f"eft_account_id must be SQLAlchemy String; found {type(column.type).__name__}"
    )
    assert column.type.length == 10, (
        f"eft_account_id must be String(10) (from COBOL "
        f"CUST-EFT-ACCOUNT-ID PIC X(10)); found "
        f"String({column.type.length})"
    )


@pytest.mark.unit
def test_pri_card_holder_ind_type() -> None:
    """``pri_card_holder_ind`` column is ``String(1)`` (from COBOL CUST-PRI-CARD-HOLDER-IND PIC X(01)).

    1-character primary-cardholder indicator. Typical values:

    * ``'Y'`` — primary cardholder
    * ``'N'`` — authorized user / secondary cardholder

    The single-character code is preserved as-is from the copybook so
    that migrated VSAM records retain their exact cardholder-type
    semantics without any value-translation step.
    """
    column = Customer.__table__.columns["pri_card_holder_ind"]
    assert isinstance(column.type, String), (
        f"pri_card_holder_ind must be SQLAlchemy String; found {type(column.type).__name__}"
    )
    assert column.type.length == 1, (
        f"pri_card_holder_ind must be String(1) (from COBOL "
        f"CUST-PRI-CARD-HOLDER-IND PIC X(01)); found "
        f"String({column.type.length})"
    )


@pytest.mark.unit
def test_fico_credit_score_type() -> None:
    """``fico_credit_score`` column is ``Integer`` (from COBOL CUST-FICO-CREDIT-SCORE PIC 9(03)).

    The **only** non-string column in the Customer entity. Unlike the
    numeric ``cust_id`` and ``ssn`` fields (which are stored as strings
    to preserve leading zeros), FICO scores have no meaningful
    leading-zero semantics — valid FICO range is 300 – 850, and a
    stored value of ``0`` (the default on INSERT) signals a
    missing / unscored customer.

    Storing as ``Integer`` enables efficient server-side ordering,
    range predicates (e.g., ``WHERE fico_credit_score >= 700``), and
    aggregation queries (credit-tier reporting, statement cohort
    analytics) without application-side string-to-integer coercion.
    """
    column = Customer.__table__.columns["fico_credit_score"]
    assert isinstance(column.type, Integer), (
        f"fico_credit_score must be SQLAlchemy Integer (the sole "
        f"non-string column on Customer; FICO scores 300-850 have no "
        f"leading-zero semantics); found {type(column.type).__name__}"
    )


# ============================================================================
# Phase 4: NOT NULL & Default Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_required_fields() -> None:
    """The four strictly-required fields must be declared NOT NULL.

    The COBOL ``CUSTOMER-RECORD`` layout has no ``OCCURS ... DEPENDING
    ON`` clauses and no ``REDEFINES`` — every field is present on every
    record. In addition, four of those fields have no sensible
    blank-or-zero default and MUST be supplied explicitly by the caller:

    * ``cust_id``    — primary key; NULL PKs are rejected at the SQL
      level anyway, but the invariant is asserted explicitly to guard
      against a future change that removes ``primary_key=True``.
    * ``first_name`` — cardholder's given name; no "anonymous customer"
      value is valid under the COBOL ``CUST-FIRST-NAME PIC X(25)`` field.
    * ``last_name``  — cardholder's surname; same reasoning as
      ``first_name``.
    * ``ssn``        — 9-digit Social Security Number; flagged as
      sensitive by AAP §0.7.2, and a blank / zero value would break
      downstream reconciliation and regulatory-reporting pipelines.

    SQLAlchemy automatically sets ``nullable=False`` on any column
    marked ``primary_key=True``, but this test asserts the invariant
    explicitly so that accidentally dropping ``nullable=False`` (or
    unmarking the PK) on any of these four columns triggers an obvious,
    copy-able failure message.
    """
    for column_name in _STRICTLY_REQUIRED_COLUMNS:
        column = Customer.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column {column_name!r} must be NOT NULL — the "
            f"corresponding COBOL field has no blank-value convention "
            f"and must be supplied explicitly; nullable={column.nullable}"
        )


@pytest.mark.unit
def test_default_values() -> None:
    """The 13 optional string columns default to the empty string.

    Historically, the COBOL VSAM records were created with
    SPACES-initialized fields — any address line, phone number,
    government ID, DOB, EFT account, or cardholder-indicator field
    that was unknown at record-creation time simply held ASCII spaces
    (``' '`` * n). Translating that semantic to the relational model,
    the SQLAlchemy column's client-side ``default=""`` provides the
    analogous "absent / unknown" representation so that partial INSERTs
    (e.g., from the F-019 User Add API when the caller supplies only
    the required fields) produce rows that match byte-for-byte the
    format expected by the batch pipeline (CREASTMT statement
    generation reads every Customer column and substitutes spaces for
    empty strings on the rendered statement).

    This test iterates every column in
    :data:`_OPTIONAL_STRING_COLUMNS_WITH_EMPTY_DEFAULT` and asserts
    that its SQLAlchemy ``ColumnDefault.arg`` is exactly ``""``.
    """
    for column_name in _OPTIONAL_STRING_COLUMNS_WITH_EMPTY_DEFAULT:
        column = Customer.__table__.columns[column_name]
        assert column.default is not None, (
            f"Column {column_name!r} must declare a client-side "
            f"default (empty string) — missing defaults break partial "
            f"INSERTs from the API layer"
        )
        assert column.default.arg == "", (
            f"Column {column_name!r} must default to empty string "
            f"'' (matching COBOL SPACES-initialized semantics); "
            f"found default={column.default.arg!r}"
        )


@pytest.mark.unit
def test_fico_default() -> None:
    """``fico_credit_score`` defaults to ``0``.

    The default value of ``0`` is intentional: it is OUTSIDE the valid
    FICO range (300 – 850) and therefore unambiguously signals a
    missing / unscored customer at read time. Consumers of the
    Customer model (e.g., credit-tier reporting in statement
    generation, risk-scoring queries) can filter out zero-scored rows
    with ``WHERE fico_credit_score >= 300`` — a query pattern that
    would be ambiguous if the default were, say, ``300`` (conflating
    "unscored" with "lowest-possible-score").

    Storing as ``Integer`` with ``default=0`` also preserves the
    COBOL semantics of the ``PIC 9(03)`` field, which would contain
    zeros in a VSAM record before an initial credit pull.
    """
    column = Customer.__table__.columns["fico_credit_score"]
    assert column.default is not None, (
        "fico_credit_score must declare a client-side default — "
        "the value 0 (outside the 300-850 valid range) signals an "
        "unscored customer and is required for partial INSERTs"
    )
    assert column.default.arg == 0, (
        f"fico_credit_score must default to integer 0 (outside the "
        f"300-850 valid FICO range; signals an unscored customer); "
        f"found default={column.default.arg!r}"
    )


# ============================================================================
# Phase 5: Instance Creation Tests
# ============================================================================


@pytest.mark.unit
def test_create_customer_instance() -> None:
    """A Customer instance can be constructed with all 18 fields.

    Exercises the SQLAlchemy 2.x ``__init__`` synthesized from the
    :class:`~sqlalchemy.orm.Mapped` declarations in the ORM class.
    All field values correspond 1-to-1 to the COBOL
    ``CUSTOMER-RECORD`` layout and include representative edge cases:

    * ``cust_id="000123456"``          — 9 chars with leading zeros
      (PIC 9(09) stored as String(9)).
    * ``fico_credit_score=750``        — mid-range FICO score (integer).
    * ``ssn="123456789"``              — 9-digit SSN (sensitive —
      not encrypted at this layer).
    * Full address block, both phones, government ID, DOB in
      ``YYYY-MM-DD`` format, EFT account, and primary-cardholder flag.

    After construction, every field must read back verbatim. No ORM
    session or database round-trip is required for this test — it
    exercises pure in-memory object construction. The test also
    verifies that :class:`Customer` is a subclass of the shared
    :class:`Base` so its table registers on the shared
    :class:`~sqlalchemy.MetaData` (required for
    ``Base.metadata.create_all()`` in tests and Alembic autogenerate).
    """
    customer = Customer(
        cust_id="000123456",
        first_name="Jane",
        middle_name="Q",
        last_name="Doe",
        addr_line_1="100 Main Street",
        addr_line_2="Apt 4B",
        addr_line_3="",
        state_cd="NY",
        country_cd="USA",
        addr_zip="10001-1234",
        phone_num_1="(212) 555-0100",
        phone_num_2="(212) 555-0101",
        ssn="123456789",
        govt_issued_id="D12345678",
        dob="1985-06-15",
        eft_account_id="EFT0000001",
        pri_card_holder_ind="Y",
        fico_credit_score=750,
    )

    # Verify the entity is a proper descendant of the shared
    # declarative base — this guards against accidentally re-rooting
    # the model on a different MetaData during a refactor, which
    # would cause the table to disappear from Base.metadata.sorted_tables.
    assert isinstance(customer, Base), (
        "Customer must be a subclass of src.shared.models.Base so its "
        "table registers on the shared MetaData alongside the other 10 "
        "CardDemo ORM entities."
    )

    # Field-by-field readback.
    assert customer.cust_id == "000123456", f"cust_id readback mismatch: got {customer.cust_id!r}"
    assert customer.first_name == "Jane", f"first_name readback mismatch: got {customer.first_name!r}"
    assert customer.middle_name == "Q", f"middle_name readback mismatch: got {customer.middle_name!r}"
    assert customer.last_name == "Doe", f"last_name readback mismatch: got {customer.last_name!r}"
    assert customer.addr_line_1 == "100 Main Street", f"addr_line_1 readback mismatch: got {customer.addr_line_1!r}"
    assert customer.addr_line_2 == "Apt 4B", f"addr_line_2 readback mismatch: got {customer.addr_line_2!r}"
    assert customer.addr_line_3 == "", f"addr_line_3 readback mismatch: got {customer.addr_line_3!r}"
    assert customer.state_cd == "NY", f"state_cd readback mismatch: got {customer.state_cd!r}"
    assert customer.country_cd == "USA", f"country_cd readback mismatch: got {customer.country_cd!r}"
    assert customer.addr_zip == "10001-1234", f"addr_zip readback mismatch: got {customer.addr_zip!r}"
    assert customer.phone_num_1 == "(212) 555-0100", f"phone_num_1 readback mismatch: got {customer.phone_num_1!r}"
    assert customer.phone_num_2 == "(212) 555-0101", f"phone_num_2 readback mismatch: got {customer.phone_num_2!r}"
    assert customer.ssn == "123456789", f"ssn readback mismatch: got {customer.ssn!r}"
    assert customer.govt_issued_id == "D12345678", f"govt_issued_id readback mismatch: got {customer.govt_issued_id!r}"
    assert customer.dob == "1985-06-15", f"dob readback mismatch: got {customer.dob!r}"
    assert customer.eft_account_id == "EFT0000001", f"eft_account_id readback mismatch: got {customer.eft_account_id!r}"
    assert customer.pri_card_holder_ind == "Y", (
        f"pri_card_holder_ind readback mismatch: got {customer.pri_card_holder_ind!r}"
    )
    assert customer.fico_credit_score == 750, f"fico_credit_score readback mismatch: got {customer.fico_credit_score!r}"


@pytest.mark.unit
def test_customer_repr() -> None:
    """``__repr__`` returns a developer-friendly string sans SSN.

    Contract:

    * MUST include the class name ``Customer``.
    * MUST include ``cust_id``, ``first_name``, ``last_name`` (the
      non-sensitive identity fields used for debugging and audit logs).
    * MUST NOT include ``ssn`` — either the value OR the attribute
      name — to prevent accidental leakage into log streams,
      tracebacks, debugger output, or error reports
      (AAP §0.7.2 Security Requirements).

    The repr is used by print statements, ``logging`` formatters,
    debugger inspections, traceback frame-locals dumps, and REPL
    introspection — every one of which could otherwise exfiltrate
    sensitive cardholder data.
    """
    # Sentinel SSN value — recognizable and distinct enough that its
    # presence (or absence) in the repr output is unambiguous.
    sentinel_ssn = "999887777"

    customer = Customer(
        cust_id="000999888",
        first_name="Alice",
        last_name="Testerson",
        ssn=sentinel_ssn,
    )

    repr_output = repr(customer)

    # Required inclusions — class name and non-sensitive identifiers.
    assert "Customer" in repr_output, f"__repr__ must include the class name 'Customer'; got {repr_output!r}"
    assert "000999888" in repr_output, f"__repr__ must include cust_id value '000999888'; got {repr_output!r}"
    assert "Alice" in repr_output, f"__repr__ must include first_name value 'Alice'; got {repr_output!r}"
    assert "Testerson" in repr_output, f"__repr__ must include last_name value 'Testerson'; got {repr_output!r}"

    # Required exclusions — the SSN value must not appear, and neither
    # should the 'ssn' attribute name (defence-in-depth: even mentioning
    # the attribute in the repr signals its existence to an observer).
    assert sentinel_ssn not in repr_output, (
        f"__repr__ MUST NOT include the SSN value (AAP §0.7.2 "
        f"Security Requirements — sensitive field); got {repr_output!r}"
    )
    assert "ssn" not in repr_output.lower(), (
        f"__repr__ MUST NOT mention the 'ssn' attribute at all — "
        f"defence-in-depth against accidental leakage via log "
        f"patterns, traceback frame-locals, or REPL inspection; "
        f"got {repr_output!r}"
    )


# ============================================================================
# Phase 6: FILLER Exclusion Test
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """No column maps the COBOL ``FILLER PIC X(168)`` padding.

    COBOL fixed-width records routinely use ``FILLER`` regions to pad
    to a target record length (here, 168 bytes of trailing padding so
    the overall ``CUSTOMER-RECORD`` reaches the VSAM ``RECSZ(500 500)``
    target defined in ``app/jcl/CUSTFILE.jcl``). These padding regions
    exist purely as storage artifacts — they carry no semantic data —
    and therefore have no equivalent in a typed relational schema.

    This test scans every column on the model's ``__table__.columns``
    collection and asserts:

    1. The exact set of mapped column names matches the 18-column
       contract defined by :data:`_EXPECTED_COLUMNS` (positive check).
    2. No column name contains the substring ``filler`` in any casing
       (negative check — guards against future regressions where a
       copybook-to-model translator accidentally emits a ``filler``,
       ``cust_filler``, ``record_filler``, etc. column).
    """
    column_names: list[str] = [c.name for c in Customer.__table__.columns]

    # Positive: the exact set of mapped columns must match the contract.
    assert set(column_names) == set(_EXPECTED_COLUMNS), (
        f"Column set drift detected. Expected: {sorted(_EXPECTED_COLUMNS)}; found: {sorted(column_names)}"
    )

    # Negative: no column name may contain the substring 'filler' in
    # any casing. This guards against future regressions where a
    # copybook-to-model translator accidentally emits a filler column.
    for column_name in column_names:
        assert "filler" not in column_name.lower(), (
            f"Column {column_name!r} appears to map a COBOL FILLER "
            f"region. FILLER fields (like the trailing "
            f"FILLER PIC X(168) padding in CUSTOMER-RECORD) are "
            f"storage artifacts only and MUST NOT be mapped to the "
            f"relational model."
        )
