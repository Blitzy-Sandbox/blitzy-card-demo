# =============================================================================
# Source: COBOL copybook CVACT02Y.cpy — CARD-RECORD (150 bytes, VSAM KSDS)
# Tests validate field types, constraints, PK, optimistic concurrency,
#        and the ix_card_acct_id B-tree index (VSAM AIX replacement).
# =============================================================================
"""Unit tests for :class:`src.shared.models.card.Card`.

This module exercises every observable contract of the SQLAlchemy 2.x
``Card`` ORM model, which is the relational translation of the legacy
COBOL copybook ``app/cpy/CVACT02Y.cpy`` (``CARD-RECORD``).

COBOL Source Record Layout — ``app/cpy/CVACT02Y.cpy``
-----------------------------------------------------

The original VSAM KSDS record is 150 bytes long with the 16-byte
``CARD-NUM`` field as the primary key (offset 0, length 16). The
copybook's ``CARD-RECORD`` 01-level group defines the following
fields::

    01  CARD-RECORD.
        05  CARD-NUM                         PIC X(16).
        05  CARD-ACCT-ID                     PIC 9(11).
        05  CARD-CVV-CD                      PIC 9(03).
        05  CARD-EMBOSSED-NAME               PIC X(50).
        05  CARD-EXPIRAION-DATE              PIC X(10).  [sic — typo in source]
        05  CARD-ACTIVE-STATUS               PIC X(01).
        05  FILLER                           PIC X(59).

                                     Total: 150 bytes (RECLN=150)

The field typo ``CARD-EXPIRAION-DATE`` (missing the second 'T') is
preserved *verbatim* in the COBOL source. The Python translation
uses the corrected name ``expiration_date`` because the relational
schema is the canonical forward-going contract, and because this
column name is used across the online program ``COCRDUPC.cbl``
(Card Update) and batch program ``CBSTM03A.CBL`` (Statement
Generation) regardless of the copybook declaration typo.

COBOL -> Python Field Mapping
-----------------------------

+----------------------+--------------+----------------------+-------+
| COBOL Field          | PIC Clause   | Python Column        | Type  |
+======================+==============+======================+=======+
| CARD-NUM             | PIC X(16)    | ``card_num`` (PK)    | str   |
+----------------------+--------------+----------------------+-------+
| CARD-ACCT-ID         | PIC 9(11)    | ``acct_id``          | str   |
+----------------------+--------------+----------------------+-------+
| CARD-CVV-CD          | PIC 9(03)    | ``cvv_cd``           | str   |
+----------------------+--------------+----------------------+-------+
| CARD-EMBOSSED-NAME   | PIC X(50)    | ``embossed_name``    | str   |
+----------------------+--------------+----------------------+-------+
| CARD-EXPIRAION-DATE  | PIC X(10)    | ``expiration_date``  | str   |
+----------------------+--------------+----------------------+-------+
| CARD-ACTIVE-STATUS   | PIC X(01)    | ``active_status``    | str   |
+----------------------+--------------+----------------------+-------+
| FILLER               | PIC X(59)    | *(not mapped)*       | —     |
+----------------------+--------------+----------------------+-------+
| *(new — Python)*     | —            | ``version_id``       | int   |
+----------------------+--------------+----------------------+-------+

Rationale for String-Over-Integer on Numeric PIC Fields
-------------------------------------------------------

Three COBOL fields — ``CARD-ACCT-ID`` (``PIC 9(11)``), ``CARD-CVV-CD``
(``PIC 9(03)``), and the implicit numeric-intent in
``CARD-ACTIVE-STATUS`` when encoded ``0``/``1`` — are mapped to
fixed-width ``String`` columns rather than ``Integer``. This is a
deliberate, carefully-considered decision:

1. **Leading-zero preservation.** COBOL ``PIC 9(n)`` fields store
   values with leading zeros filling the declared width. A CVV code
   of ``007`` must be stored and returned as the three-character
   string ``"007"``, not as the integer ``7`` that would
   lose the two leading zeros in round-trip marshalling. The same
   applies to 11-digit account IDs (``"00000012345"`` vs. ``12345``).
2. **Cross-system identifier stability.** Account IDs and card
   numbers flow through multiple downstream systems (PySpark batch
   jobs, external statement renderers, regulatory reporting
   pipelines). Keeping them as fixed-width strings eliminates any
   risk of implicit integer coercion discarding leading zeros.
3. **Behavioural equivalence with COBOL.** Any COBOL program
   performing a ``MOVE CARD-CVV-CD TO WS-DISPLAY`` or a keyed
   ``READ CARDFILE`` depends on the 3-character width being
   preserved exactly.

Test Coverage — 17 Functions
----------------------------

Phase 2: Table & column metadata
  1. :func:`test_tablename`                 — ``cards`` (plural)
  2. :func:`test_column_count`              — exactly 7 columns
  3. :func:`test_primary_key_card_num`      — ``card_num`` VARCHAR(16)

Phase 3: Column type fidelity (COBOL PIC -> SQLAlchemy type)
  4. :func:`test_card_num_type`             — String(16)
  5. :func:`test_acct_id_type`              — String(11)
  6. :func:`test_cvv_cd_type`               — String(3)
  7. :func:`test_embossed_name_type`        — String(50)
  8. :func:`test_expiration_date_type`      — String(10)
  9. :func:`test_active_status_type`        — String(1)

Phase 4: Optimistic concurrency (replaces CICS READ UPDATE / REWRITE)
  10. :func:`test_version_id_exists`        — Integer column present
  11. :func:`test_version_id_default`       — default is 0
  12. :func:`test_optimistic_concurrency_configured`
                                            — ``__mapper_args__`` bound

Phase 5: Index coverage (VSAM AIX replacement)
  13. :func:`test_acct_id_index`            — ``ix_card_acct_id``

Phase 6: NOT NULL constraint coverage
  14. :func:`test_non_nullable_fields`      — all 7 columns NOT NULL

Phase 7: Instance construction & repr contract
  15. :func:`test_create_card_instance`     — kwargs round-trip
  16. :func:`test_card_repr`                — PII-safe repr
                                              (CVV excluded)

Phase 8: FILLER exclusion
  17. :func:`test_no_filler_columns`        — 59-byte FILLER dropped

Assertions are rich with COBOL-PIC-clause context to make any
regression failure immediately traceable back to the legacy source.
"""

from __future__ import annotations

import pytest
from sqlalchemy import Integer, String, inspect

from src.shared.models import Base
from src.shared.models.card import Card

# =============================================================================
# Module-Level Constants
# =============================================================================
#
# These module-level constants centralise "magic values" so that any
# future change to the Card schema (e.g., adding a new column, or
# renaming an index) requires only a single constant update rather
# than hunting through seventeen test bodies. They also double as
# executable documentation of the expected schema.

# The exact, complete set of columns the Card model must expose.
# This is intentionally a ``frozenset`` — it is immutable, hashable,
# and supports symmetric-difference diagnostics in assertion
# failures. Order is irrelevant for column-*name* assertions; column
# *ordering* is covered separately by :func:`test_column_count`.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        # --- COBOL CARD-RECORD 01-level group ---
        "card_num",  # CARD-NUM             PIC X(16)
        "acct_id",  # CARD-ACCT-ID         PIC 9(11)
        "cvv_cd",  # CARD-CVV-CD          PIC 9(03)
        "embossed_name",  # CARD-EMBOSSED-NAME   PIC X(50)
        "expiration_date",  # CARD-EXPIRAION-DATE  PIC X(10)
        "active_status",  # CARD-ACTIVE-STATUS   PIC X(01)
        # --- new Python-side column ---
        "version_id",  # Integer, OCC control column (default 0)
    }
)

# The name of the ``acct_id`` B-tree index. This matches the
# project-wide ``ix_<entity_singular>_<column>`` convention used in
# ``db/migrations/V2__indexes.sql`` and by Alembic autogenerate. The
# *exact* name matters because migration scripts ``CREATE INDEX``
# and ``DROP INDEX`` by name: any rename would cause Alembic
# autogenerate to emit a spurious DROP/CREATE cycle and potentially
# orphan the index on production upgrades. This index replaces the
# legacy VSAM alternate index ``CARDFILE.CARDAIX.PATH`` (see
# ``app/jcl/CARDFILE.jcl`` / ``app/catlg/LISTCAT.txt``).
_EXPECTED_ACCT_ID_INDEX_NAME: str = "ix_card_acct_id"

# The expected plural table name. Matches ``db/migrations/V1__schema.sql``
# ``CREATE TABLE cards`` and the SQLAlchemy convention applied across
# all 11 ORM models in ``src/shared/models/`` (accounts, cards,
# customers, card_cross_references, transactions,
# transaction_category_balances, daily_transactions,
# disclosure_groups, transaction_types, transaction_categories,
# user_security).
_EXPECTED_TABLE_NAME: str = "cards"

# The expected column count: 6 COBOL-derived columns + 1 Python-side
# ``version_id`` column = 7 columns. This count explicitly excludes
# the COBOL FILLER PIC X(59) which must NOT be mapped.
_EXPECTED_COLUMN_COUNT: int = 7

# Sample values for :func:`test_create_card_instance`. These match
# realistic production-shaped data while conforming to the
# fixed-width character constraints:
#
#   * ``_SAMPLE_CARD_NUM`` — 16 characters, Visa-style BIN prefix
#     (4-series). Conforms to CARD-NUM PIC X(16).
#   * ``_SAMPLE_ACCT_ID`` — 11 characters with leading zeros,
#     matching CARD-ACCT-ID PIC 9(11) which left-pads to the
#     declared width.
#   * ``_SAMPLE_CVV`` — 3 characters, matching CARD-CVV-CD PIC 9(03).
#     The value ``"911"`` is chosen deliberately: it is NOT a
#     lexical substring of ``_SAMPLE_CARD_NUM`` (which contains
#     ``"123456789"`` but not ``"911"``) and NOT of
#     ``_SAMPLE_ACCT_ID`` (which contains only digits 0-5). This
#     distinctness matters because :func:`test_card_repr` checks
#     that the CVV value does NOT appear anywhere in the repr
#     output — using a value that could coincidentally appear as
#     a substring elsewhere would cause spurious failures.
#     The leading-zero concern for general CVV handling is
#     documented in the module docstring; leading-zero behaviour
#     is covered by the type-level test, not this sample.
#   * ``_SAMPLE_NAME`` — free-form character data fitting in 50
#     characters, matching CARD-EMBOSSED-NAME PIC X(50).
#   * ``_SAMPLE_EXPIRATION`` — ISO-ish ``YYYY-MM-DD`` shape in 10
#     characters, matching CARD-EXPIRAION-DATE PIC X(10). The
#     ``"2030-06-15"`` value is chosen so none of its digit
#     sequences collide with ``_SAMPLE_CVV``.
#   * ``_SAMPLE_STATUS`` — single-character flag, matching
#     CARD-ACTIVE-STATUS PIC X(01). ``"Y"`` means active (the
#     COBOL programs CBTRN02C / COCRDUPC read this as Y/N).
_SAMPLE_CARD_NUM: str = "4000123456789010"
_SAMPLE_ACCT_ID: str = "00000012345"
_SAMPLE_CVV: str = "911"
_SAMPLE_NAME: str = "JOHN Q. CARDHOLDER"
_SAMPLE_EXPIRATION: str = "2030-06-15"
_SAMPLE_STATUS: str = "Y"


# =============================================================================
# Phase 2: Table & Column Metadata Tests
# =============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """Card must map to the plural ``cards`` PostgreSQL table.

    The convention used throughout ``src/shared/models/`` is the
    plural English form (``accounts``, ``cards``, ``customers``,
    etc.). This matches:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE cards (...)``
    * ``db/migrations/V2__indexes.sql`` — ``CREATE INDEX ... ON cards``
    * Alembic autogenerate's natural output for a model class
      named ``Card`` with SQLAlchemy's standard pluralisation
      conventions.

    A mismatch here (singular ``card`` vs. plural ``cards``) would
    cause every ORM query to fail at runtime with
    ``UndefinedTable`` because the ORM would emit SQL against the
    non-existent ``card`` table while the real table exists as
    ``cards``. This is not a theoretical concern: the original
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
    assert isinstance(Card.__tablename__, str), (
        f"Card.__tablename__ must be a string; got {type(Card.__tablename__).__name__}: {Card.__tablename__!r}"
    )

    assert Card.__tablename__ == _EXPECTED_TABLE_NAME, (
        f"Card.__tablename__ must be {_EXPECTED_TABLE_NAME!r} "
        f"(plural form, matching db/migrations/V1__schema.sql's "
        f"'CREATE TABLE cards' and the project-wide "
        f"pluralisation convention); got "
        f"{Card.__tablename__!r}. A singular value here would "
        f"cause ORM queries to fail at runtime with "
        f"UndefinedTable because the real table is 'cards'."
    )


@pytest.mark.unit
def test_column_count() -> None:
    """Card must expose exactly 7 columns — 6 COBOL + 1 ``version_id``.

    The 6 COBOL-derived columns are ``card_num``, ``acct_id``,
    ``cvv_cd``, ``embossed_name``, ``expiration_date``,
    ``active_status`` (translations of the six named 05-level
    fields in ``CARD-RECORD``). The 7th column, ``version_id``,
    is a Python-side addition that implements optimistic
    concurrency (the relational equivalent of CICS READ UPDATE /
    REWRITE semantics used by ``COCRDUPC.cbl`` Card Update).

    The COBOL FILLER PIC X(59) byte region — which padded the
    VSAM record to its 150-byte RECLN — has deliberately NOT
    been mapped: padding serves no purpose in a columnar
    relational store. A regression that accidentally maps FILLER
    would cause the column count to balloon to 8 (or more), so
    this count-based test catches that regression early.
    """
    # SQLAlchemy exposes the full ordered column collection via
    # ``__table__.columns`` on any declaratively-mapped class.
    columns = list(Card.__table__.columns)

    assert len(columns) == _EXPECTED_COLUMN_COUNT, (
        f"Card must declare exactly {_EXPECTED_COLUMN_COUNT} "
        f"columns (6 COBOL-derived + 1 version_id); found "
        f"{len(columns)}: {[c.name for c in columns]!r}. If this "
        f"count has grown, confirm that the COBOL FILLER PIC "
        f"X(59) has not been accidentally mapped; if it has "
        f"shrunk, a required column has been dropped."
    )

    # As a second-level guard, verify the column *names* match
    # the expected set exactly. This catches renames (which would
    # not change the count) and spelling regressions.
    #
    # NOTE: We compare against ``Column.key`` (Python attribute
    # name) rather than ``Column.name`` (DB column name, e.g.
    # ``card_acct_id``). ``_EXPECTED_COLUMNS`` uses Python-style
    # names — the DB column names are separately verified by the
    # SQL migration tests.
    actual_names = frozenset(c.key for c in columns)
    assert actual_names == _EXPECTED_COLUMNS, (
        f"Card column names must be exactly "
        f"{sorted(_EXPECTED_COLUMNS)!r}; found "
        f"{sorted(actual_names)!r}. Differences — "
        f"missing: {sorted(_EXPECTED_COLUMNS - actual_names)!r}, "
        f"unexpected: {sorted(actual_names - _EXPECTED_COLUMNS)!r}"
    )


@pytest.mark.unit
def test_primary_key_card_num() -> None:
    """``card_num`` must be the sole primary key, String(16).

    Rationale and lineage:

    * The legacy VSAM KSDS ``CARDFILE`` cluster uses ``CARD-NUM``
      (offset 0, length 16) as its primary key — see
      ``app/jcl/CARDFILE.jcl`` and ``app/catlg/LISTCAT.txt``.
    * COBOL declares it as ``PIC X(16)`` — a 16-character
      fixed-width string (NOT numeric).
    * The Python translation preserves this 1:1 as a
      ``String(16)`` primary-key column.
    * ``card_num`` is a *natural* primary key (business-meaningful)
      rather than a surrogate integer. This is deliberate: card
      numbers are the stable identifier referenced externally
      by card networks, POS systems, and downstream batch jobs.
      Introducing a surrogate PK would force every consumer to
      translate through an additional lookup for no benefit.

    This test verifies all three facets at once:
      1. PK count — exactly 1 (no composite key)
      2. PK name  — ``card_num``
      3. PK type  — SQLAlchemy ``String`` with length 16
    """
    # ``inspect()`` returns a :class:`Mapper` whose ``primary_key``
    # attribute is a tuple of the primary-key column objects.
    primary_key_columns = list(inspect(Card).primary_key)

    # Exactly one PK column — no composite key on Card.
    assert len(primary_key_columns) == 1, (
        f"Card must have exactly one primary-key column "
        f"(card_num, matching VSAM CARDFILE key offset=0 "
        f"length=16); found {len(primary_key_columns)}: "
        f"{[c.name for c in primary_key_columns]!r}"
    )

    pk_column = primary_key_columns[0]

    # The PK column must be named ``card_num``.
    assert pk_column.name == "card_num", (
        f"Card primary key must be 'card_num' (from CARD-NUM PIC X(16)); found {pk_column.name!r}"
    )

    # The PK type must be ``String``.
    assert isinstance(pk_column.type, String), (
        f"Card.card_num must be a String column (from CARD-NUM "
        f"PIC X(16) — a fixed-width character field, NOT "
        f"numeric); found type {type(pk_column.type).__name__}: "
        f"{pk_column.type!r}"
    )

    # The PK type must declare length exactly 16.
    assert pk_column.type.length == 16, (
        f"Card.card_num must be String(16) (from CARD-NUM "
        f"PIC X(16) — 16 characters wide); found length "
        f"{pk_column.type.length!r}"
    )

    # Additional guard — PK columns must always be NOT NULL
    # (SQLAlchemy enforces this automatically but we assert it
    # anyway as a defence-in-depth check).
    assert pk_column.nullable is False, (
        f"Card.card_num as the primary key must be NOT NULL; found nullable={pk_column.nullable!r}"
    )


# =============================================================================
# Phase 3: Column Type Tests (COBOL PIC -> SQLAlchemy Type fidelity)
# =============================================================================
#
# These six tests each validate one COBOL-derived column's type
# and declared length. They all follow the identical "isinstance
# + .length" pattern to keep the test structure uniform and the
# review cost low. Any deviation here (wrong type, wrong length)
# is a direct regression against the COBOL copybook contract.


@pytest.mark.unit
def test_card_num_type() -> None:
    """``card_num`` must be declared as ``String(16)``.

    COBOL source: ``CARD-NUM    PIC X(16)`` in
    ``app/cpy/CVACT02Y.cpy``.

    The 16-character fixed width is industry-standard for card
    numbers (ISO 7812 assigns 19 digits maximum; the vast
    majority of card issuers use 16 including Visa and
    Mastercard). The ``X`` PIC clause denotes alphanumeric /
    character data — so while payment card numbers are
    conventionally numeric, COBOL intentionally declares them
    as a character field to preserve leading zeros and
    eliminate any risk of arithmetic coercion.
    """
    column = Card.__table__.columns["card_num"]

    # Must be ``String`` — alphanumeric character type.
    assert isinstance(column.type, String), (
        f"Card.card_num must be String (from CARD-NUM PIC X(16) "
        f"— a fixed-width character field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 16.
    assert column.type.length == 16, (
        f"Card.card_num must be String(16) (from CARD-NUM "
        f"PIC X(16) — 16 characters wide); found length "
        f"{column.type.length!r}"
    )


@pytest.mark.unit
def test_acct_id_type() -> None:
    """``acct_id`` must be declared as ``String(11)``.

    COBOL source: ``CARD-ACCT-ID    PIC 9(11)`` in
    ``app/cpy/CVACT02Y.cpy``.

    Although the COBOL field uses ``PIC 9(11)`` (numeric), the
    Python translation stores it as ``String(11)`` — a
    deliberate, well-reasoned choice documented in the module
    docstring:

    * 11-digit account IDs frequently start with one or more
      zeros (e.g., ``"00000012345"``). COBOL's numeric PIC
      clause zero-pads to the declared width on display;
      Python integers would silently discard those leading
      zeros.
    * Account IDs flow through multiple downstream systems
      (PySpark batch, statement rendering, regulatory
      reporting) where any loss of fixed-width formatting
      would cause downstream mismatches.
    * CARD-ACCT-ID is a foreign-key reference to the 11-digit
      ACCT-ID primary key on :class:`Account` — the two must
      have matching representations to enable joins. Account
      (``CVACT01Y.cpy``'s ACCT-ID) is also String(11) for the
      same reason.
    """
    column = Card.__table__.columns["acct_id"]

    # Must be ``String`` — NOT Integer.
    assert isinstance(column.type, String), (
        f"Card.acct_id must be String (NOT Integer) to preserve "
        f"leading zeros on 11-digit account IDs (e.g., "
        f"'00000012345'). CARD-ACCT-ID PIC 9(11) is mapped to "
        f"String(11) so that leading-zero identifiers survive "
        f"round-trip marshalling; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 11.
    assert column.type.length == 11, (
        f"Card.acct_id must be String(11) (from CARD-ACCT-ID "
        f"PIC 9(11) — 11 digits wide, matching Account.acct_id "
        f"PK width); found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_cvv_cd_type() -> None:
    """``cvv_cd`` must be declared as ``String(3)``.

    COBOL source: ``CARD-CVV-CD    PIC 9(03)`` in
    ``app/cpy/CVACT02Y.cpy``.

    The Card Verification Value (CVV) — also known as CVC or
    CV2 — is the 3-digit security code printed on the back of
    a payment card. It is declared as ``PIC 9(03)`` in COBOL
    but stored as ``String(3)`` in Python for the critical
    reason of leading-zero preservation: CVV codes like
    ``"007"`` must be stored and returned literally, not as
    the integer ``7`` that would lose both leading zeros in
    round-trip marshalling.

    Security note: The CVV should NEVER appear in a
    :meth:`__repr__` output or log message. The Card.__repr__
    implementation deliberately omits this field — a contract
    separately verified by :func:`test_card_repr`.
    """
    column = Card.__table__.columns["cvv_cd"]

    # Must be ``String`` — NOT Integer.
    assert isinstance(column.type, String), (
        f"Card.cvv_cd must be String (NOT Integer) to preserve "
        f"leading zeros on CVV codes (e.g., '007' not 7). "
        f"CARD-CVV-CD PIC 9(03) is mapped to String(3) so that "
        f"3-digit codes with leading zeros survive round-trip "
        f"marshalling; found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 3.
    assert column.type.length == 3, (
        f"Card.cvv_cd must be String(3) (from CARD-CVV-CD "
        f"PIC 9(03) — 3 digits wide, the industry-standard CVV "
        f"code length); found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_embossed_name_type() -> None:
    """``embossed_name`` must be declared as ``String(50)``.

    COBOL source: ``CARD-EMBOSSED-NAME    PIC X(50)`` in
    ``app/cpy/CVACT02Y.cpy``.

    The embossed name is the cardholder name as physically
    embossed / printed on the face of the payment card. The
    50-character width accommodates the longest names in the
    target data set: ``"FIRST MIDDLE LAST-HYPHENATED SUFFIX"``
    and international names with multiple components.

    This is straightforward alphanumeric character data with
    no leading-zero concern — the type-fidelity decision here
    is purely COBOL ``PIC X(50)`` -> SQLAlchemy ``String(50)``.
    """
    column = Card.__table__.columns["embossed_name"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Card.embossed_name must be String (from "
        f"CARD-EMBOSSED-NAME PIC X(50) — a fixed-width "
        f"character field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 50.
    assert column.type.length == 50, (
        f"Card.embossed_name must be String(50) (from "
        f"CARD-EMBOSSED-NAME PIC X(50) — 50 characters wide, "
        f"accommodating the longest cardholder names); found "
        f"length {column.type.length!r}"
    )


@pytest.mark.unit
def test_expiration_date_type() -> None:
    """``expiration_date`` must be declared as ``String(10)``.

    COBOL source: ``CARD-EXPIRAION-DATE    PIC X(10)`` in
    ``app/cpy/CVACT02Y.cpy`` (note: the COBOL field name
    contains the typo ``EXPIRAION`` — missing the second 'T'
    — which is preserved verbatim in the source but *not*
    replicated in the Python column name. The relational
    schema uses the correctly-spelled ``expiration_date``).

    The 10-character width matches ISO-8601-like date strings
    (``YYYY-MM-DD``). COBOL represents this as a character
    field rather than a numeric date type (which COBOL doesn't
    natively support) — Python preserves the character-based
    storage to retain full formatting fidelity with
    downstream consumers that expect a string date.

    Note: Although semantically a date, this column stays a
    fixed-width string to minimise transformation risk during
    the mainframe-to-cloud migration. A future enhancement
    could migrate this to :class:`sqlalchemy.Date` once all
    downstream consumers are verified to handle typed dates,
    but that is an explicit *enhancement* — not within the
    scope of the behaviour-preserving refactor.
    """
    column = Card.__table__.columns["expiration_date"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Card.expiration_date must be String (from "
        f"CARD-EXPIRAION-DATE PIC X(10) — a fixed-width "
        f"character field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 10.
    assert column.type.length == 10, (
        f"Card.expiration_date must be String(10) (from "
        f"CARD-EXPIRAION-DATE PIC X(10) — 10 characters wide, "
        f"matching YYYY-MM-DD ISO-8601-like date strings); "
        f"found length {column.type.length!r}"
    )


@pytest.mark.unit
def test_active_status_type() -> None:
    """``active_status`` must be declared as ``String(1)``.

    COBOL source: ``CARD-ACTIVE-STATUS    PIC X(01)`` in
    ``app/cpy/CVACT02Y.cpy``.

    The active-status flag is a single-character Y/N indicator
    (or similar two-valued flag). It is read by:

    * ``COCRDUPC.cbl`` (F-008 Card Update) for validity
      checks on the card being updated.
    * ``CBTRN02C.cbl`` (POSTTRAN Stage 1, transaction
      posting): an inactive card triggers reject code 104
      (card inactive) in the 4-stage validation cascade.
    * ``COTRN02C.cbl`` (F-011 Transaction Add): an inactive
      card prevents new transactions from being created.

    The Python translation preserves the single-character
    width to maintain behavioural parity with these COBOL
    programs and their service-layer Python equivalents.
    """
    column = Card.__table__.columns["active_status"]

    # Must be ``String``.
    assert isinstance(column.type, String), (
        f"Card.active_status must be String (from "
        f"CARD-ACTIVE-STATUS PIC X(01) — a fixed-width "
        f"character field); found type "
        f"{type(column.type).__name__}: {column.type!r}"
    )

    # Must declare length 1.
    assert column.type.length == 1, (
        f"Card.active_status must be String(1) (from "
        f"CARD-ACTIVE-STATUS PIC X(01) — 1 character wide, a "
        f"single Y/N flag); found length "
        f"{column.type.length!r}"
    )


# =============================================================================
# Phase 4: Optimistic Concurrency Tests (version_id + __mapper_args__)
# =============================================================================
#
# These three tests verify the Python-side optimistic-concurrency-
# control (OCC) column and its SQLAlchemy wiring. OCC is the
# relational equivalent of the CICS READ UPDATE / REWRITE protocol
# used by the legacy online program ``COCRDUPC.cbl`` (F-008 Card
# Update). Under OCC, SQLAlchemy adds a ``WHERE version_id = :old``
# clause to every UPDATE statement and raises
# :exc:`sqlalchemy.orm.exc.StaleDataError` if the row's version has
# been bumped by a concurrent writer — preventing lost-update bugs
# without the pessimistic locking overhead of ``SELECT ... FOR
# UPDATE``.
#
# The COBOL original achieved this via the CICS ``READ UPDATE`` /
# ``REWRITE`` pair: ``READ UPDATE`` held an exclusive lock on the
# VSAM record, and the subsequent ``REWRITE`` was guaranteed to
# see no intervening modifications. The Python/SQLAlchemy OCC
# approach is semantically equivalent but non-blocking: concurrent
# reads proceed in parallel, and conflicts are detected at write
# time rather than prevented at read time.


@pytest.mark.unit
def test_version_id_exists() -> None:
    """Card must declare a ``version_id`` Integer column.

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
    columns = Card.__table__.columns

    # Must have a ``version_id`` column at all.
    assert "version_id" in columns, (
        f"Card must declare a 'version_id' column to enable "
        f"optimistic concurrency (replacing CICS READ UPDATE / "
        f"REWRITE from COCRDUPC.cbl); found columns: "
        f"{sorted(c.name for c in columns)!r}"
    )

    version_column = columns["version_id"]

    # Must be of type ``Integer``.
    assert isinstance(version_column.type, Integer), (
        f"Card.version_id must be an Integer column (SQLAlchemy "
        f"OCC increments this counter on every UPDATE, so it "
        f"must be an integer type); found type "
        f"{type(version_column.type).__name__}: "
        f"{version_column.type!r}"
    )


@pytest.mark.unit
def test_version_id_default() -> None:
    """Card.version_id must default to ``0``.

    A newly-created Card instance that has never been persisted
    must have ``version_id`` initialised to ``0`` (or the
    database-level default must resolve to ``0`` on INSERT).
    This matters because:

    * The first UPDATE after INSERT increments to 1, establishing
      a clean, predictable version sequence.
    * A null or missing default would cause the first UPDATE to
      fail because SQLAlchemy emits ``WHERE version_id IS NULL``
      (which matches zero rows) and then raises ``StaleDataError``
      even though no concurrent modification has occurred.
    * Seed-data loaders that INSERT without explicit ``version_id``
      rely on this default to produce well-formed rows.

    The test accesses the default via
    ``column.default.arg`` — SQLAlchemy wraps scalar defaults in
    a :class:`ScalarElementColumnDefault` whose ``arg`` attribute
    holds the literal default value.
    """
    version_column = Card.__table__.columns["version_id"]

    # Must have a client-side default at all.
    assert version_column.default is not None, (
        "Card.version_id must declare a default value so that "
        "newly-created instances have version_id=0 on INSERT "
        "(otherwise the first UPDATE will emit WHERE version_id "
        "IS NULL and raise StaleDataError); found default=None"
    )

    # The default must be scalar (not a callable, not a
    # ``Sequence``, not a server-side ``DefaultClause``). A
    # callable default would work at runtime but is unusual and
    # not what this model declares.
    assert version_column.default.is_scalar, (
        f"Card.version_id default must be scalar (a literal "
        f"integer, not a callable or server-side default); "
        f"found is_scalar={version_column.default.is_scalar!r} "
        f"default={version_column.default!r}"
    )

    # The scalar default value must be exactly 0 — the canonical
    # OCC starting version.
    assert version_column.default.arg == 0, (
        f"Card.version_id default must be 0 (the canonical OCC "
        f"starting version — the first UPDATE increments to 1, "
        f"the second to 2, etc.); found default={version_column.default.arg!r}"
    )


@pytest.mark.unit
def test_optimistic_concurrency_configured() -> None:
    """Card.__mapper_args__ must wire version_id for OCC.

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

    This test verifies the binding exists and points to the
    ``version_id`` column (by name), catching any regression
    where the mapper-arg is accidentally removed or typoed.
    """
    # ``__mapper_args__`` is always a dict on a properly-
    # configured DeclarativeBase subclass. It may be empty, but
    # it must at least be present.
    assert hasattr(Card, "__mapper_args__"), (
        "Card must declare __mapper_args__ to configure "
        "optimistic concurrency (version_id_col binding); "
        "found no __mapper_args__ attribute at all"
    )

    mapper_args = Card.__mapper_args__
    assert isinstance(mapper_args, dict), (
        f"Card.__mapper_args__ must be a dict; got {type(mapper_args).__name__}: {mapper_args!r}"
    )

    # The ``version_id_col`` key must be present.
    assert "version_id_col" in mapper_args, (
        f"Card.__mapper_args__ must contain 'version_id_col' to "
        f"activate SQLAlchemy optimistic concurrency (the "
        f"relational equivalent of CICS READ UPDATE / REWRITE "
        f"from COCRDUPC.cbl); found keys: "
        f"{sorted(mapper_args.keys())!r}"
    )

    version_id_col = mapper_args["version_id_col"]

    # The bound column must expose a ``.name`` attribute
    # resolving to ``"version_id"``. We identify by name rather
    # than by object identity because SQLAlchemy 2.x's
    # ``mapped_column()`` helper returns a :class:`MappedColumn`
    # instance which wraps — but is not identical to — the
    # eventual :class:`Column` object accessible via
    # ``Card.__table__.columns["version_id"]``. The ``.name``
    # attribute is stable across this wrapping.
    bound_name = getattr(version_id_col, "name", None)
    assert bound_name == "version_id", (
        f"Card.__mapper_args__['version_id_col'] must bind to "
        f"the 'version_id' column; found a "
        f"{type(version_id_col).__name__} object whose "
        f".name attribute is {bound_name!r}"
    )


# =============================================================================
# Phase 5: Index Tests (VSAM AIX replacement)
# =============================================================================


@pytest.mark.unit
def test_acct_id_index() -> None:
    """Card must declare a B-tree index on ``acct_id``.

    The index is:

    * **Named** ``ix_card_acct_id`` — matching the project-wide
      ``ix_<entity_singular>_<column>`` convention used in
      ``db/migrations/V2__indexes.sql`` and by Alembic
      autogenerate. The exact name matters because migration
      scripts ``CREATE INDEX`` and ``DROP INDEX`` by name: any
      rename would cause Alembic autogenerate to emit a spurious
      DROP/CREATE cycle and potentially orphan the index on
      production upgrades.
    * **On the single column** ``acct_id`` — the owning-account
      ID, enabling efficient "find all cards owned by account N"
      lookups.
    * **Non-unique** — a single account can legitimately own
      multiple cards (primary, authorised-user, replacement,
      lost/stolen reissue, etc.), so ``unique=False`` (the
      SQLAlchemy default) is correct. This matches the
      ``NONUNIQUEKEY`` flag on the legacy VSAM alternate index
      ``CARDFILE.CARDAIX.PATH`` documented in
      ``app/catlg/LISTCAT.txt`` and defined in
      ``app/jcl/CARDFILE.jcl``.

    This index is the relational equivalent of the VSAM
    alternate index from the original mainframe cluster and is
    critical for the following access patterns:

    * **F-004 Account View** (``COACTVWC.cbl`` ->
      ``account_service.view()``): discovers all cards on an
      account to assemble the consolidated 3-entity view
      (Account + Customer + Card).
    * **F-006 Card List** (``COCRDLIC.cbl`` ->
      ``card_service.list_by_account()``): produces the
      paginated 7-rows-per-page card listing scoped to the
      currently-selected account.
    * **F-007 Card Detail View** (``COCRDSLC.cbl``) and
      **F-008 Card Update** (``COCRDUPC.cbl``): resolve
      account-scoped card lookups during card maintenance.

    Any regression that drops or renames this index would
    degrade those queries from ``O(log n)`` index seek to
    ``O(n)`` table scan — unacceptable at production scale.
    """
    # ``Card.__table__`` is typed as the abstract
    # :class:`sqlalchemy.sql.expression.FromClause` by the
    # SQLAlchemy 2.x declarative base, but at runtime is always
    # a concrete :class:`sqlalchemy.Table` (which carries the
    # ``.indexes`` collection). The ``attr-defined`` type-ignore
    # here is the canonical workaround for this well-known
    # SQLAlchemy 2.x typing gap.
    indexes = list(Card.__table__.indexes)  # type: ignore[attr-defined]

    # Locate the index by name. Use ``next`` + ``None`` default
    # so we can emit a rich diagnostic if the index is missing
    # entirely.
    matched = next(
        (idx for idx in indexes if idx.name == _EXPECTED_ACCT_ID_INDEX_NAME),
        None,
    )
    assert matched is not None, (
        f"Card must declare an index named "
        f"{_EXPECTED_ACCT_ID_INDEX_NAME!r} on the acct_id column "
        f"(replacing the legacy VSAM AIX CARDFILE.CARDAIX.PATH "
        f"from app/jcl/CARDFILE.jcl / app/catlg/LISTCAT.txt); "
        f"found indexes with names "
        f"{sorted(idx.name for idx in indexes if idx.name)!r}"
    )

    # Exactly one column — the single-column B-tree.
    indexed_columns = list(matched.columns)
    assert len(indexed_columns) == 1, (
        f"Index {_EXPECTED_ACCT_ID_INDEX_NAME!r} must be a "
        f"single-column B-tree on acct_id (matching VSAM AIX "
        f"CARDFILE.CARDAIX.PATH which had a single AIX key on "
        f"CARD-ACCT-ID); found {len(indexed_columns)} columns: "
        f"{[c.name for c in indexed_columns]!r}"
    )

    # The single column must be ``acct_id`` (Python attribute key).
    # Column.key is the Python-side access key (e.g. ``acct_id``),
    # while Column.name is the DB-side physical column name
    # (``card_acct_id``). The Index is declared on the Python key
    # via ``Index('ix_card_acct_id', 'acct_id')`` in
    # ``src/shared/models/card.py`` so the expected match here is
    # the key, not the physical DB column name.
    indexed_column_key = indexed_columns[0].key
    assert indexed_column_key == "acct_id", (
        f"Index {_EXPECTED_ACCT_ID_INDEX_NAME!r} must be on the "
        f"'acct_id' column (the relational target of COBOL "
        f"CARD-ACCT-ID PIC 9(11)); found column "
        f"{indexed_column_key!r}"
    )

    # Non-unique — a single account can own multiple cards,
    # matching NONUNIQUEKEY in the legacy IDCAMS
    # CARDFILE.CARDAIX.PATH alternate index definition.
    # ``unique=False`` is the default for SQLAlchemy
    # ``Index(...)`` when no ``unique=`` kwarg is supplied, so
    # on a correctly-declared model this attribute will be
    # either exactly ``False`` or ``None`` (both of which mean
    # non-unique).
    assert not matched.unique, (
        f"Index {_EXPECTED_ACCT_ID_INDEX_NAME!r} must be "
        f"non-unique (a single account can own multiple cards "
        f"— primary, authorised-user, replacement, etc. — "
        f"matching NONUNIQUEKEY in the legacy IDCAMS "
        f"CARDFILE.CARDAIX.PATH AIX); found "
        f"unique={matched.unique}"
    )


# =============================================================================
# Phase 6: NOT NULL Constraint Tests
# =============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """Every Card column must be declared NOT NULL.

    All 7 columns on the Card table carry a NOT NULL
    constraint. This faithfully preserves the COBOL copybook
    contract: every 05-level field in ``CARD-RECORD`` is
    implicitly non-null because VSAM records are written as
    fixed-width binary blobs with no concept of "null" — an
    unpopulated field would be initialised to spaces (for
    ``PIC X``) or zeros (for ``PIC 9``), never null.

    The 7 columns and their NOT NULL justifications:

    1. ``card_num`` — Primary key; PKs are always NOT NULL.
    2. ``acct_id`` — Foreign-key reference to the owning
       account. A card without an owning account is
       logically meaningless.
    3. ``cvv_cd`` — Security code; required for transaction
       authorisation. COBOL programs unconditionally read
       this field.
    4. ``embossed_name`` — Cardholder name; required for
       card issuance.
    5. ``expiration_date`` — Required for validity checks.
    6. ``active_status`` — Required for the POSTTRAN Stage
       1 reject-code 104 (card inactive) check.
    7. ``version_id`` — OCC control column; a null version
       would defeat optimistic concurrency.

    This test catches any regression where a column's
    ``nullable=False`` kwarg is accidentally removed or
    negated. A single nullable column would cause SQLAlchemy
    to emit the column without a NOT NULL constraint, and
    PostgreSQL would then silently accept NULL values —
    violating the COBOL contract at the database level.
    """
    columns = Card.__table__.columns

    # Collect every column that is nullable (should be empty)
    # to produce a single rich diagnostic if any column is
    # incorrectly nullable, rather than asserting one at a
    # time (which would fail on the first and obscure the
    # rest).
    nullable_columns = [c.name for c in columns if c.nullable is not False]
    assert not nullable_columns, (
        f"All Card columns must be NOT NULL (matching the "
        f"fixed-width VSAM CARD-RECORD contract where every "
        f"field is always populated); found nullable columns: "
        f"{sorted(nullable_columns)!r}. Every one of the 7 "
        f"columns (card_num, acct_id, cvv_cd, embossed_name, "
        f"expiration_date, active_status, version_id) must "
        f"declare nullable=False."
    )

    # Defence in depth: explicitly verify each of the 7
    # expected columns individually. This produces clearer
    # diagnostics for the common case of a single regression,
    # and double-guards against a column being silently
    # dropped (which the generic loop above would not catch).
    for column_name in _EXPECTED_COLUMNS:
        column = columns[column_name]
        assert column.nullable is False, f"Card.{column_name} must be NOT NULL; found nullable={column.nullable!r}"


# =============================================================================
# Phase 7: Instance Creation & Repr Tests
# =============================================================================


@pytest.mark.unit
def test_create_card_instance() -> None:
    """A Card instance must accept all 6 data columns as kwargs.

    This test exercises the fundamental instance-construction
    contract:

    1. Card(...) accepts the 6 COBOL-derived columns as
       keyword arguments.
    2. Each kwarg value round-trips through the descriptor:
       setting ``card_num=X`` and reading ``instance.card_num``
       returns ``X`` (modulo any Pydantic/SQLAlchemy-level
       validation, of which this model declares none at the
       Python-object level — validation happens at the
       database layer via the NOT NULL constraints and
       fixed-width VARCHAR types).
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
    """
    # Construct with all 6 COBOL-derived columns. ``version_id``
    # is omitted so that the default-value test can independently
    # verify defaulting behaviour.
    card = Card(
        card_num=_SAMPLE_CARD_NUM,
        acct_id=_SAMPLE_ACCT_ID,
        cvv_cd=_SAMPLE_CVV,
        embossed_name=_SAMPLE_NAME,
        expiration_date=_SAMPLE_EXPIRATION,
        active_status=_SAMPLE_STATUS,
    )

    # The instance must be a Card.
    assert isinstance(card, Card), f"Card(...) must produce a Card instance; got {type(card).__name__}"

    # The instance must be a Base (verifies the declarative
    # base is wired correctly — any regression here would
    # break ORM metadata registration).
    assert isinstance(card, Base), (
        f"Card instances must inherit from Base (the shared "
        f"DeclarativeBase in src.shared.models); got an "
        f"instance of {type(card).__name__} which does NOT "
        f"inherit from Base. This would break ORM metadata "
        f"registration and cause Table creation to fail."
    )

    # Round-trip verify every kwarg came through cleanly.
    # A mismatch here would indicate that the descriptor has
    # been accidentally reconfigured (e.g., with a validator,
    # a property wrapper, or an SQLAlchemy `TypeDecorator` that
    # transforms input values).
    assert card.card_num == _SAMPLE_CARD_NUM, (
        f"card.card_num must round-trip the constructor kwarg; set {_SAMPLE_CARD_NUM!r}, got {card.card_num!r}"
    )
    assert card.acct_id == _SAMPLE_ACCT_ID, (
        f"card.acct_id must round-trip the constructor kwarg "
        f"and preserve leading zeros; set {_SAMPLE_ACCT_ID!r}, "
        f"got {card.acct_id!r}"
    )
    assert card.cvv_cd == _SAMPLE_CVV, (
        f"card.cvv_cd must round-trip the constructor kwarg; set {_SAMPLE_CVV!r}, got {card.cvv_cd!r}"
    )
    assert card.embossed_name == _SAMPLE_NAME, (
        f"card.embossed_name must round-trip the constructor kwarg; set {_SAMPLE_NAME!r}, got {card.embossed_name!r}"
    )
    assert card.expiration_date == _SAMPLE_EXPIRATION, (
        f"card.expiration_date must round-trip the constructor "
        f"kwarg; set {_SAMPLE_EXPIRATION!r}, got "
        f"{card.expiration_date!r}"
    )
    assert card.active_status == _SAMPLE_STATUS, (
        f"card.active_status must round-trip the constructor kwarg; set {_SAMPLE_STATUS!r}, got {card.active_status!r}"
    )


@pytest.mark.unit
def test_card_repr() -> None:
    """Card.__repr__ must produce a human-readable, PII-safe string.

    The ``__repr__`` contract has two distinct concerns:

    **Readability** — the output should include enough identifying
    information to make a log line or debugger inspection
    immediately useful. At minimum, the card number (the primary
    key) and a couple of disambiguating fields should appear.

    **Security / PII safety** — the CVV code (``cvv_cd``) is a
    sensitive security element (PCI DSS considers it SAD —
    Sensitive Authentication Data — and prohibits its storage
    post-authorisation). It MUST NOT appear in any log line,
    repr output, exception message, or similar diagnostic
    surface. Card numbers and embossed names have weaker
    sensitivity but are still PII; for this project the
    repr includes the card number (necessary for debugging and
    correlation) but omits the embossed name.

    This test verifies both concerns:

    1. The repr string starts with ``"Card("`` (clear type tag).
    2. The card_num value appears (identification).
    3. The CVV value does NOT appear (security).
    4. The embossed name does NOT appear (PII minimisation).
    5. The acct_id and active_status appear (per the model's
       declared repr signature, which aids account-level
       diagnostic correlation).
    """
    card = Card(
        card_num=_SAMPLE_CARD_NUM,
        acct_id=_SAMPLE_ACCT_ID,
        cvv_cd=_SAMPLE_CVV,
        embossed_name=_SAMPLE_NAME,
        expiration_date=_SAMPLE_EXPIRATION,
        active_status=_SAMPLE_STATUS,
    )

    repr_str = repr(card)

    # Must be a non-empty string.
    assert isinstance(repr_str, str) and repr_str, (
        f"Card.__repr__ must return a non-empty string; got {type(repr_str).__name__}: {repr_str!r}"
    )

    # Must start with the class-name tag ``Card(`` — standard
    # Python repr convention, and enables tooling to identify
    # the object type without executing ``type(...)``.
    assert repr_str.startswith("Card("), (
        f"Card.__repr__ must start with 'Card(' (standard Python repr convention); got {repr_str!r}"
    )

    # Must INCLUDE the card number — the primary key is the
    # natural correlation id for debugging.
    assert _SAMPLE_CARD_NUM in repr_str, (
        f"Card.__repr__ must include the card_num value for "
        f"debugging / log correlation; did not find "
        f"{_SAMPLE_CARD_NUM!r} in {repr_str!r}"
    )

    # Must INCLUDE the account id — secondary correlation key
    # for account-level debugging workflows.
    assert _SAMPLE_ACCT_ID in repr_str, (
        f"Card.__repr__ must include the acct_id value for "
        f"account-level correlation; did not find "
        f"{_SAMPLE_ACCT_ID!r} in {repr_str!r}"
    )

    # Must INCLUDE the active status — a single character that
    # answers the common question "is this card active?".
    assert _SAMPLE_STATUS in repr_str, (
        f"Card.__repr__ must include the active_status flag; did not find {_SAMPLE_STATUS!r} in {repr_str!r}"
    )

    # Must EXCLUDE the ``cvv_cd`` attribute name — PCI DSS SAD,
    # never log / repr. This is the single most critical
    # assertion in this test: any regression that adds CVV to
    # the repr is a security incident waiting to happen because
    # reprs appear in exception tracebacks, log statements,
    # debugger inspection snapshots, etc.
    #
    # Checking the attribute *name* (``"cvv_cd"``) is more
    # robust than checking the value because: (a) it catches
    # any canonical attribute-style output like
    # ``cvv_cd='...'`` regardless of the value, and (b) it is
    # not subject to coincidental substring collisions between
    # sample values (e.g., a CVV value that happens to appear
    # as a substring of the card number).
    assert "cvv_cd" not in repr_str, (
        f"Card.__repr__ must NEVER include the cvv_cd "
        f"attribute (PCI DSS classifies CVV as Sensitive "
        f"Authentication Data — SAD — which must not appear in "
        f"logs, repr output, or any other diagnostic surface); "
        f"found 'cvv_cd' in {repr_str!r}"
    )

    # Belt-and-braces: verify the CVV *value* also does not
    # appear in the repr. ``_SAMPLE_CVV`` is specifically
    # chosen (``"911"``) so it does NOT occur as a lexical
    # substring of any other sample value — so any appearance
    # here is a true positive.
    assert _SAMPLE_CVV not in repr_str, (
        f"Card.__repr__ must NEVER include the CVV value "
        f"(PCI DSS Sensitive Authentication Data); found "
        f"{_SAMPLE_CVV!r} in {repr_str!r}"
    )

    # Must EXCLUDE the embossed name — PII minimisation.
    # The embossed cardholder name is personally identifying
    # information; including it in every repr would leak it
    # into every log line touching this record.
    assert _SAMPLE_NAME not in repr_str, (
        f"Card.__repr__ must NOT include the embossed_name "
        f"(PII minimisation — cardholder names are personally "
        f"identifying data); found {_SAMPLE_NAME!r} in "
        f"{repr_str!r}"
    )

    # Must EXCLUDE the ``embossed_name`` attribute marker as
    # additional defence against accidental PII leakage.
    assert "embossed_name" not in repr_str, (
        f"Card.__repr__ must NOT include the embossed_name "
        f"attribute (PII minimisation); found 'embossed_name' "
        f"in {repr_str!r}"
    )


# =============================================================================
# Phase 8: FILLER Field Exclusion Test
# =============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """COBOL FILLER PIC X(59) must NOT be mapped to any column.

    The legacy ``CARD-RECORD`` in ``app/cpy/CVACT02Y.cpy``
    concludes with::

        05  FILLER                           PIC X(59).

    This 59-byte padding region exists solely to bring the
    total VSAM record length to the declared RECLN of 150
    bytes (16 + 11 + 3 + 50 + 10 + 1 + 59 = 150). It carries
    no business semantics — it is literally un-used bytes.

    The Python translation correctly drops this field: there
    is no ``filler`` column on the :class:`Card` model, no
    ``filler_`` prefix, and no column named anything
    suggestive of padding / reserved bytes. This test enforces
    that contract at two levels:

    1. **Exact-match level**: the column set is exactly the
       7 expected names documented in the module constant
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
    columns = Card.__table__.columns
    # Level 1 compares against ``_EXPECTED_COLUMNS`` (Python attribute
    # names), so use ``Column.key``. Level 2 scans BOTH Python key
    # AND DB column name for ``'filler'``.
    column_keys = frozenset(c.key for c in columns)
    column_db_names = frozenset(c.name for c in columns)

    # Level 1 — exact set match (Python attribute names).
    assert column_keys == _EXPECTED_COLUMNS, (
        f"Card column set must be exactly "
        f"{sorted(_EXPECTED_COLUMNS)!r} (7 columns: 6 COBOL "
        f"named fields + 1 Python version_id). COBOL FILLER "
        f"PIC X(59) at the end of CARD-RECORD must NOT be "
        f"mapped — it is purely structural padding to bring "
        f"the VSAM record to RECLN=150. Found "
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
            f"Card must not expose any FILLER-style column "
            f"(COBOL FILLER PIC X(59) is padding and carries "
            f"no business meaning); found column "
            f"{column_name!r} whose name contains 'filler'"
        )
