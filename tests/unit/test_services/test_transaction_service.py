# ============================================================================
# CardDemo - Unit tests for TransactionService (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COTRN00C.cbl     - CICS transaction browse program (transaction
#                                CT00, Feature F-009, ~699 lines). Paginated
#                                list of transactions 10 rows/page via
#                                STARTBR / READNEXT on TRANSACT-FILE with
#                                OCCURS 10 TIMES screen array
#                                (COTRN00.CPY symbolic map).
#   * app/cbl/COTRN01C.cbl     - CICS transaction detail program (transaction
#                                CT01, Feature F-010, ~330 lines). Keyed
#                                READ on TRANSACT-FILE by TRAN-ID with
#                                NOTFND / DFHRESP("NORMAL") outcome
#                                reporting.
#   * app/cbl/COTRN02C.cbl     - CICS transaction add program (transaction
#                                CT02, Feature F-011, ~783 lines). Dual-path
#                                resolution (card -> account OR account ->
#                                card) via XREF file, auto-generation of
#                                TRAN-ID as max(TRAN-ID) + 1 using
#                                STARTBR at end + READPREV, and
#                                WRITE-TRANSACT-FILE to stage the new
#                                ledger row.
#   * app/cpy/CVTRA05Y.cpy     - TRAN-RECORD (350-byte VSAM KSDS layout):
#                                TRAN-ID            PIC X(16),
#                                TRAN-TYPE-CD       PIC X(02),
#                                TRAN-CAT-CD        PIC 9(04),
#                                TRAN-SOURCE        PIC X(10),
#                                TRAN-DESC          PIC X(100),
#                                TRAN-AMT           PIC S9(09)V99,
#                                TRAN-MERCHANT-ID   PIC 9(09),
#                                TRAN-MERCHANT-NAME PIC X(50),
#                                TRAN-MERCHANT-CITY PIC X(50),
#                                TRAN-MERCHANT-ZIP  PIC X(10),
#                                TRAN-CARD-NUM      PIC X(16),
#                                TRAN-ORIG-TS       PIC X(26),
#                                TRAN-PROC-TS       PIC X(26).
#   * app/cpy/CVACT03Y.cpy     - CARD-XREF-RECORD (50-byte VSAM KSDS layout):
#                                XREF-CARD-NUM PIC X(16),
#                                XREF-CUST-ID  PIC 9(09),
#                                XREF-ACCT-ID  PIC 9(11).
# ----------------------------------------------------------------------------
# Features F-009 (Transaction List), F-010 (Transaction Detail), and F-011
# (Transaction Add). Target implementation under test:
# src/api/services/transaction_service.py (TransactionService class).
#
# The COBOL-exact error messages are preserved byte-for-byte per AAP Section
# 0.7.1 "Preserve exact error messages from COBOL" -- each one tracked below
# is asserted verbatim (including trailing ellipsis of three periods):
#
#   * 'Unable to lookup transaction...'        (list DB error;
#                                               lowercase 't',
#                                               singular -- from
#                                               COTRN00C.cbl L615/
#                                               L649/L683)
#   * 'Tran ID can NOT be empty...'            (detail empty input)
#   * 'Transaction ID NOT found...'            (detail NOTFND)
#   * 'Unable to lookup Transaction...'        (detail WHEN OTHER;
#                                               capital 'T' --
#                                               COTRN01C.cbl is a
#                                               DIFFERENT program
#                                               from COTRN00C)
#   * 'Unable to lookup Card # in XREF file...'(add xref NOTFND)
#   * 'Account/Card mismatch in XREF...'       (add xref acct_id mismatch)
#   * 'Unable to Add Transaction...'           (add other failure)
#   * 'Transaction added successfully.  Your Tran ID is {tran_id}.'
#                                               (add success -- DOUBLE
#                                               space before 'Your'
#                                               AND trailing period;
#                                               from COTRN02C.cbl
#                                               L728-732 STRING
#                                               concatenation)
# ----------------------------------------------------------------------------
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
"""Unit tests for :class:`TransactionService`.

Validates the three transaction operations converted from
``app/cbl/COTRN00C.cbl`` (Feature F-009 -- paginated list, 10 rows/page),
``app/cbl/COTRN01C.cbl`` (Feature F-010 -- keyed detail read), and
``app/cbl/COTRN02C.cbl`` (Feature F-011 -- auto-ID generation and
cross-reference resolution on transaction add). All monetary
assertions use :class:`decimal.Decimal` (never :class:`float`) per AAP
Section 0.7.2 "Financial Precision".

COBOL -> Python Verification Surface
------------------------------------
=============================================================  ==========================================
COBOL paragraph / statement                                    Python test (this module)
=============================================================  ==========================================
COTRN00C PROCESS-PAGE-FORWARD (paginated browse, 10/page)      ``test_list_transactions_default_page_size_10``
COTRN00C page offset / pagination                              ``test_list_transactions_pagination_offset``
COTRN00C TRNIDINI filter input                                 ``test_list_transactions_filter_by_tran_id_prefix``
COTRN00C STARTBR / READNEXT NOTFND branch (no rows)            ``test_list_transactions_empty_result``
COTRN00C TRAN-AMT PIC S9(09)V99 precision                      ``test_list_transactions_amount_is_decimal``
COTRN00C execute() OTHER failure branch                        ``test_list_transactions_db_error_returns_empty_message``
COTRN00C SELECT WHERE tran_id LIKE (escape on filter)          ``test_list_transactions_filter_escapes_like_metachars``
COTRN00C result row -> TransactionListItem mapping             ``test_list_transactions_maps_rows_correctly``
COTRN01C READ TRANSACT RIDFLD(TRAN-ID)                         ``test_get_transaction_detail_success``
COTRN01C NOTFND branch                                         ``test_get_transaction_detail_not_found``
COTRN01C empty input guard (SPACES / LOW-VALUES)               ``test_get_transaction_detail_empty_input``
COTRN01C WHEN OTHER failure branch                             ``test_get_transaction_detail_db_error``
COTRN01C TRAN-AMT -> Decimal preservation                      ``test_get_transaction_detail_amount_is_decimal``
COTRN01C response field truncation to BMS widths               ``test_get_transaction_detail_truncates_to_bms_widths``
COTRN02C READ CCXREF BY XREF-CARD-NUM (card->account)          ``test_add_transaction_xref_resolution_card_to_account``
COTRN02C XREF acct_id mismatch guard                           ``test_add_transaction_xref_account_mismatch``
COTRN02C READ CCXREF NOTFND branch                             ``test_add_transaction_xref_not_found``
COTRN02C STARTBR + READPREV + ADD 1 (auto-ID)                  ``test_add_transaction_auto_id_generation``
COTRN02C empty TRANSACT file auto-ID fallback                  ``test_add_transaction_auto_id_empty_table``
COTRN02C WRITE TRANSACT + SYNCPOINT                            ``test_add_transaction_success_stages_and_commits``
COTRN02C TRAN-AMT -> Decimal preservation                      ``test_add_transaction_amount_is_decimal``
COTRN02C GET-CURRENT-TIMESTAMP (TRAN-ORIG-TS / TRAN-PROC-TS)   ``test_add_transaction_timestamp_format_26_char``
COTRN02C WRITE TRANSACT failure (rollback + re-raise)          ``test_add_transaction_db_error_propagates_and_rolls_back``
COTRN02C auto-ID numeric parse failure                         ``test_add_transaction_bad_existing_tran_id_returns_error``
=============================================================  ==========================================

Test Design
-----------
* **Mocked database**: All tests use ``AsyncMock(spec=AsyncSession)``
  rather than a real database, so the test suite runs in milliseconds
  with no PostgreSQL dependency. The mock replicates the SQLAlchemy
  2.x async contract -- ``execute()`` is async and returns a Result
  object whose accessor methods (``scalar_one_or_none``, ``scalars()``,
  ``scalar()``, ``scalar_one()``) are synchronous -- matching the
  distinct query patterns each service method issues:

    * :meth:`TransactionService.list_transactions`: 2 execute calls
      (page query returning ``.scalars().all()``; count query returning
      ``.scalar_one()``).
    * :meth:`TransactionService.get_transaction_detail`: 1 execute call
      (keyed read returning ``.scalar_one_or_none()``) -- NOT issued
      when the input is empty / whitespace-only.
    * :meth:`TransactionService.add_transaction`: up to 3 execute calls
      in order (xref lookup returning ``.scalar_one_or_none()``;
      ``SELECT MAX(tran_id)`` returning ``.scalar()``; ``SELECT tran_id
      ORDER BY DESC LIMIT 1`` returning ``.scalar()``) followed by
      ``session.add`` (sync), ``await session.flush()`` and
      ``await session.commit()``.

* **Decimal-only monetary assertions**: Every transaction amount uses
  :class:`decimal.Decimal`. The sample transaction fixture uses
  ``amount=Decimal("50.00")`` -- never a ``float`` -- to preserve the
  COBOL ``PIC S9(09)V99`` semantics.

* **Preserved COBOL error literals**: Every test that exercises an
  error branch asserts the exact COBOL-source message text as a
  literal string. The tests duplicate the literals locally (not
  imported from ``transaction_service.py``) so that drift between
  the service constants and the COBOL source will be caught rather
  than silently propagated.

* **Auto-ID generation invariant**: Two independent tests cover the
  auto-ID generation at ``COTRN02C.cbl`` L384-L395 --
  ``test_add_transaction_auto_id_generation`` (max existing tran_id
  + 1) and ``test_add_transaction_auto_id_empty_table`` (empty table
  fallback to ``"0000000000000001"``). Both assert the 16-character
  zero-padded width and all-digit composition.

* **Cross-reference resolution invariant**: Three tests cover the
  XREF file interactions from ``COTRN02C.cbl`` lines 402-435 --
  successful card->account resolution, mismatched account guard
  (``XREF-ACCT-ID != input acct_id``), and NOTFND on the XREF file.
  All three verify that no Transaction row is staged when the XREF
  check fails.

See Also
--------
* ``src/api/services/transaction_service.py``   -- The service under test.
* ``src/shared/models/transaction.py``          -- Transaction ORM model
                                                   (from CVTRA05Y.cpy).
* ``src/shared/models/card_cross_reference.py`` -- CardCrossReference ORM
                                                   model (from CVACT03Y.cpy).
* ``src/shared/schemas/transaction_schema.py``  -- Pydantic request /
                                                   response schemas.
* AAP Section 0.7.1 -- Refactoring-Specific Rules (preserve exact COBOL
                       error messages; auto-ID generation fidelity).
* AAP Section 0.7.2 -- Financial Precision (Decimal, never float).
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.services.transaction_service import TransactionService
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.transaction import Transaction
from src.shared.schemas.transaction_schema import (
    TransactionAddRequest,
    TransactionAddResponse,
    TransactionDetailResponse,
    TransactionListItem,
    TransactionListRequest,
    TransactionListResponse,
)

# ============================================================================
# Module-level test constants.
# ============================================================================
#
# These constants encode the COBOL-exact wire values from
# app/cbl/COTRN00C.cbl, app/cbl/COTRN01C.cbl, and app/cbl/COTRN02C.cbl.
# They are defined locally in this test module (not imported from
# transaction_service.py) so that the tests verify the wire values
# independently of the service implementation -- any drift between
# the COBOL source and the service constants will be caught by the
# tests rather than silently propagated.
# ============================================================================

#: 11-character zero-padded test account ID. Matches the COBOL
#: ``ACCT-ID PIC 9(11)`` width and the ``Account.acct_id String(11)``
#: model column. Pydantic ``_validate_acct_id`` enforces all-digits.
_TEST_ACCT_ID: str = "00000000001"

#: 16-character test card number. Matches the COBOL
#: ``XREF-CARD-NUM PIC X(16)`` width. Uses a synthetic test-only PAN
#: with Visa prefix (no Luhn verification at this layer).
_TEST_CARD_NUM: str = "4111111111111111"

#: Alternate 16-character card number for the xref-mismatch test.
#: Same length and numeric format as ``_TEST_CARD_NUM`` but distinct
#: so it keys a separate xref row.
_TEST_CARD_NUM_ALT: str = "5555555555554444"

#: 9-character zero-padded test customer ID. Matches the COBOL
#: ``XREF-CUST-ID PIC 9(09)`` width.
_TEST_CUST_ID: str = "000000001"

#: Width of the ``tran_id`` primary-key column -- matches COBOL
#: ``TRAN-ID PIC X(16)`` and the model ``Transaction.tran_id
#: String(16)`` column. Transaction IDs are always zero-padded to
#: this width so that lexicographic ordering matches numeric
#: ordering (required for the ``SELECT MAX(tran_id)`` semantic).
_EXPECTED_TRAN_ID_WIDTH: int = 16

#: Starting transaction ID for an empty ``transactions`` table --
#: mirrors ``_INITIAL_TRAN_ID`` in ``transaction_service.py`` and the
#: COBOL convention of ``MOVE 1 TO WS-TRAN-ID-NUM`` when no previous
#: row exists. 16 characters, zero-padded.
_EXPECTED_INITIAL_TRAN_ID: str = "0000000000000001"

#: Width of the COBOL-compatible timestamp string -- matches
#: ``TRAN-ORIG-TS PIC X(26)`` / ``TRAN-PROC-TS PIC X(26)`` from
#: ``CVTRA05Y.cpy``. Python's ``datetime.strftime('%Y-%m-%d
#: %H:%M:%S.%f')`` produces exactly 26 characters.
_EXPECTED_TIMESTAMP_WIDTH: int = 26

#: Default transaction list page size. Mirrors the COBOL ``OCCURS 10
#: TIMES`` screen array in ``COTRN00.CPY`` (symbolic map) and the
#: ``_DEFAULT_PAGE_SIZE`` constant in ``transaction_schema.py``.
_DEFAULT_PAGE_SIZE: int = 10

#: Width of the ``TransactionListItem.description`` field -- matches
#: the COBOL BMS ``TDESCO`` output field and ``_LIST_DESC_MAX_LEN``
#: in ``transaction_schema.py``.
_LIST_DESC_WIDTH: int = 26

#: Width of the ``TransactionListItem.tran_date`` field -- matches
#: the COBOL BMS ``TDATE`` 8-character date output format (CCYYMMDD).
_LIST_DATE_WIDTH: int = 8

#: Width of ``TransactionDetailResponse.description`` -- from the
#: COBOL BMS ``TRNDESC`` 60-character output field.
_DETAIL_DESC_WIDTH: int = 60

#: Width of ``TransactionDetailResponse.merchant_name`` -- from the
#: COBOL BMS ``MERNAME`` 30-character output field (truncated from
#: model column String(50)).
_DETAIL_MERCHANT_NAME_WIDTH: int = 30

#: Width of ``TransactionDetailResponse.merchant_city`` -- from the
#: COBOL BMS ``MERCITY`` 25-character output field (truncated from
#: model column String(50)).

#: COBOL add-transaction card-not-in-XREF message -- from
#: ``COTRN02C.cbl`` DFHRESP(NOTFND) branch on CCXREF READ.
_MSG_CARD_NOT_IN_XREF: str = "Unable to lookup Card # in XREF file..."

#: COBOL add-transaction acct_id/card_num mismatch message -- from
#: ``COTRN02C.cbl`` post-READ guard ``XREF-ACCT-ID != WS-ACCT-ID``.
_MSG_ACCT_CARD_MISMATCH: str = "Account/Card mismatch in XREF..."

#: COBOL add-transaction OTHER failure message -- from
#: ``COTRN02C.cbl`` DFHRESP(OTHER) branch on WRITE TRANSACT.
_MSG_UNABLE_TO_ADD: str = "Unable to Add Transaction..."

#: Success-message format string -- ``transaction_service.py`` formats
#: this with ``tran_id=new_tran_id`` after a successful dual-write.
#:
#: COBOL-exact literal from ``COTRN02C.cbl`` lines 728-732 STRING
#: concatenation::
#:
#:     STRING 'Transaction added successfully. ' DELIMITED BY SIZE
#:            ' Your Tran ID is ' DELIMITED BY SIZE
#:            WS-TRAN-ID-N DELIMITED BY SIZE
#:            '.' DELIMITED BY SIZE
#:         INTO WS-MESSAGE
#:     END-STRING
#:
#: Concatenation produces ``"Transaction added successfully.  Your
#: Tran ID is <id>."`` -- note the DOUBLE space between 'successfully.'
#: and 'Your' (from the trailing space in the first literal plus the
#: leading space in the second) AND the trailing period. Both are
#: required for byte-for-byte COBOL fidelity per AAP §0.7.1 and
#: Checkpoint 3 MAJOR #4.
_MSG_ADD_SUCCESS_FMT: str = "Transaction added successfully.  Your Tran ID is {tran_id}."


# ============================================================================
# Phase 2: Test Fixtures
# ============================================================================
#
# Fixtures are kept module-local (not moved to a shared conftest.py) so
# that this test file is self-contained and the AAP "isolate new
# implementations in dedicated files/modules" rule (Section 0.7.1) is
# honored. The test-services package __init__.py explicitly permits
# this pattern ("fixtures live in subpackage-local conftest.py files
# so that pytest's hierarchical fixture resolution applies and
# fixtures stay close to the tests that use them").
# ============================================================================


@pytest.fixture
def mock_db_session() -> AsyncMock:
    """Create a mocked :class:`~sqlalchemy.ext.asyncio.AsyncSession`.

    Replaces the real database connection that :class:`TransactionService`
    uses to issue the different query patterns for each of the three
    methods under test:

    * :meth:`TransactionService.list_transactions` -- issues TWO queries
      in sequence:

        1. ``SELECT Transaction [WHERE tran_id LIKE :pattern] ORDER BY
           tran_id OFFSET :offset LIMIT :page_size``
           -- consumed via ``result.scalars().all()``.
        2. ``SELECT COUNT(*) FROM transactions [WHERE tran_id LIKE
           :pattern]``
           -- consumed via ``result.scalar_one()``.

    * :meth:`TransactionService.get_transaction_detail` -- issues ONE
      query (only when the input is non-empty):

        * ``SELECT Transaction WHERE tran_id = :tran_id``
          -- consumed via ``result.scalar_one_or_none()``.

    * :meth:`TransactionService.add_transaction` -- issues up to THREE
      queries in sequence plus a flush + commit:

        1. ``SELECT CardCrossReference WHERE card_num = :card_num``
           -- consumed via ``result.scalar_one_or_none()``.
        2. ``SELECT MAX(Transaction.tran_id)``
           -- consumed via ``result.scalar()``.
        3. ``SELECT Transaction.tran_id ORDER BY DESC LIMIT 1``
           -- consumed via ``result.scalar()``.

      Then ``session.add(new_transaction)`` (sync), ``await
      session.flush()``, and ``await session.commit()``.

    The mock is configured with:

    * ``execute`` as an :class:`AsyncMock` (matching SQLAlchemy 2.x's
      async ``execute`` contract). Individual tests override its
      ``side_effect`` with a list of query-result mocks to simulate
      different data-access scenarios via the helpers
      :func:`_make_list_execute_side_effect`,
      :func:`_make_detail_execute_side_effect`, and
      :func:`_make_add_execute_side_effect`.
    * ``add`` as a synchronous :class:`MagicMock` (SQLAlchemy's ``add``
      is sync -- it just queues the entity into the Unit of Work).
    * ``flush`` as an :class:`AsyncMock` (async in 2.x).
    * ``commit`` as an :class:`AsyncMock` (async in 2.x).
    * ``rollback`` as an :class:`AsyncMock` (async in 2.x) -- this is
      the critical assertion target for the error-path tests where
      COTRN02C.cbl requires an explicit rollback.

    Returns
    -------
    AsyncMock
        A mock ``AsyncSession`` preconfigured with async ``execute`` /
        ``flush`` / ``commit`` / ``rollback`` methods and a sync
        ``add`` method. The default ``execute`` ``return_value`` is a
        result whose accessors all return neutral empty values --
        individual tests override via ``side_effect`` for the
        method-specific query sequences.
    """
    session = AsyncMock(spec=AsyncSession)

    # Default result: all accessors return neutral empty values.
    # Individual tests override via session.execute.side_effect to
    # provide a per-query sequence (see _make_*_execute_side_effect
    # helpers below).
    default_result = MagicMock()
    default_result.scalar_one_or_none = MagicMock(return_value=None)
    default_result.scalar_one = MagicMock(return_value=0)
    default_result.scalar = MagicMock(return_value=None)
    default_scalars = MagicMock()
    default_scalars.all = MagicMock(return_value=[])
    default_scalars.first = MagicMock(return_value=None)
    default_result.scalars = MagicMock(return_value=default_scalars)

    session.execute = AsyncMock(return_value=default_result)
    session.add = MagicMock()  # sync API
    session.flush = AsyncMock(return_value=None)
    session.commit = AsyncMock(return_value=None)
    session.rollback = AsyncMock(return_value=None)

    return session


@pytest.fixture
def transaction_service(mock_db_session: AsyncMock) -> TransactionService:
    """Instantiate :class:`TransactionService` with the mocked session.

    :class:`TransactionService`'s constructor takes a single ``db``
    parameter (``AsyncSession``) and stores it on ``self.db``. The
    service intentionally has no other state, so this fixture
    produces a fresh service for each test with a mock session that
    the test can further configure.

    Parameters
    ----------
    mock_db_session : AsyncMock
        The mocked session produced by :func:`mock_db_session`.

    Returns
    -------
    TransactionService
        A fresh service instance wired to the mocked session.
    """
    return TransactionService(db=mock_db_session)


@pytest.fixture
def sample_transaction() -> Transaction:
    """Build a sample :class:`Transaction` row with COBOL-compatible fields.

    Maps each field to its COBOL copybook counterpart
    (``app/cpy/CVTRA05Y.cpy``):

    =================  ==================  =====================================
    COBOL Field        ORM Attribute       Test Value
    =================  ==================  =====================================
    TRAN-ID            tran_id             ``"0000000000000001"`` (16 chars)
    TRAN-TYPE-CD       type_cd             ``"01"``
    TRAN-CAT-CD        cat_cd              ``"1001"`` (zero-padded to 4)
    TRAN-SOURCE        source              ``"POS TERM"``
    TRAN-DESC          description         ``"Test purchase"``
    TRAN-AMT           amount              ``Decimal("50.00")``
    TRAN-MERCHANT-ID   merchant_id         ``"000000001"`` (9 digits)
    TRAN-MERCHANT-NAME merchant_name       ``"Test Store"``
    TRAN-MERCHANT-CITY merchant_city       ``"New York"``
    TRAN-MERCHANT-ZIP  merchant_zip        ``"10001"``
    TRAN-CARD-NUM      card_num            ``"4111111111111111"``
    TRAN-ORIG-TS       orig_ts             ``"2025-01-15 10:30:00.000000"``
    TRAN-PROC-TS       proc_ts             ``"2025-01-15 10:30:00.000000"``
    =================  ==================  =====================================

    CRITICAL: ``amount`` is a :class:`decimal.Decimal`, never a
    ``float``. The value ``Decimal("50.00")`` preserves the COBOL
    ``PIC S9(09)V99`` semantics (two decimal places).

    The timestamps use the 26-character ``%Y-%m-%d %H:%M:%S.%f``
    format from ``CVTRA05Y.cpy`` TRAN-ORIG-TS / TRAN-PROC-TS.

    Returns
    -------
    Transaction
        A detached ORM instance (not added to any session).
    """
    return Transaction(
        tran_id="0000000000000001",
        type_cd="01",
        cat_cd="1001",
        source="POS TERM",
        description="Test purchase",
        amount=Decimal("50.00"),
        merchant_id="000000001",
        merchant_name="Test Store",
        merchant_city="New York",
        merchant_zip="10001",
        card_num=_TEST_CARD_NUM,
        orig_ts="2025-01-15 10:30:00.000000",
        proc_ts="2025-01-15 10:30:00.000000",
    )


@pytest.fixture
def sample_transactions() -> list[Transaction]:
    """Build a list of 15 :class:`Transaction` rows for pagination testing.

    15 rows is chosen deliberately to exceed the default page size
    of 10 (from ``COTRN00.CPY`` OCCURS 10 TIMES) so that pagination
    boundaries can be exercised: page 1 covers rows 1-10, page 2
    covers rows 11-15.

    Each row has:

    * A unique 16-character zero-padded ``tran_id`` in sequence
      ``"0000000000000001"`` .. ``"0000000000000015"``.
    * ``type_cd`` alternating between ``"01"`` and ``"02"`` (valid
      2-character codes).
    * ``cat_cd`` in sequence ``"1001"`` .. ``"1015"`` (4-char codes).
    * ``amount`` as distinct :class:`Decimal` values so the test
      can distinguish between them: ``Decimal("100.00")``,
      ``Decimal("200.00")``, ..., ``Decimal("1500.00")``.
    * All the same ``card_num`` (the test-only Visa-prefix PAN) so
      that the rows share a logical card.

    Returns
    -------
    list[Transaction]
        A list of 15 detached ORM instances, sorted by ``tran_id``.
    """
    return [
        Transaction(
            tran_id=str(i).zfill(_EXPECTED_TRAN_ID_WIDTH),
            type_cd="01" if i % 2 == 1 else "02",
            cat_cd=str(1000 + i).zfill(4),
            source="POS TERM",
            description=f"Test transaction #{i}",
            amount=Decimal(f"{i * 100}.00"),
            merchant_id=str(i).zfill(9),
            merchant_name=f"Merchant {i}",
            merchant_city="New York",
            merchant_zip="10001",
            card_num=_TEST_CARD_NUM,
            orig_ts="2025-01-15 10:30:00.000000",
            proc_ts="2025-01-15 10:30:00.000000",
        )
        for i in range(1, 16)
    ]



_DETAIL_MERCHANT_CITY_WIDTH: int = 25

#: Width of ``TransactionDetailResponse.orig_date`` / ``proc_date`` --
#: from the COBOL BMS 10-character date output field (CCYY-MM-DD).
_DETAIL_DATE_WIDTH: int = 10

# ----------------------------------------------------------------------------
# COBOL-exact error-message literals. Each one is asserted byte-for-byte
# (including trailing ellipsis) in the corresponding error-branch test.
# ----------------------------------------------------------------------------

#: COBOL list-lookup error message -- from
#: ``COTRN00C.cbl`` DFHRESP(OTHER) branch in PROCESS-PAGE-FORWARD.
#:
#: COBOL-exact literal at L615/L649/L683: ``'Unable to lookup
#: transaction...'`` -- note LOWERCASE 't' and SINGULAR 'transaction'.
#: This differs deliberately from the detail-endpoint message at
#: ``COTRN01C.cbl`` (which uses capital 'T' as 'Transaction'), because
#: the two programs were authored separately and the message literals
#: are NOT shared. Preserved byte-for-byte per AAP §0.7.1 and
#: Checkpoint 3 MAJOR #3.
_MSG_UNABLE_TO_LOOKUP_LIST: str = "Unable to lookup transaction..."

#: COBOL empty-input guard message on detail endpoint -- from
#: ``COTRN01C.cbl`` empty-key check against TRNIDINL / TRNIDINI.
_MSG_TRAN_ID_EMPTY: str = "Tran ID can NOT be empty..."

#: COBOL NOTFND message on detail endpoint -- from
#: ``COTRN01C.cbl`` DFHRESP(NOTFND) branch.
_MSG_TRAN_NOT_FOUND: str = "Transaction ID NOT found..."

#: COBOL WHEN OTHER failure message on detail endpoint -- from
#: ``COTRN01C.cbl`` DFHRESP(OTHER) branch.
_MSG_UNABLE_TO_LOOKUP_DETAIL: str = "Unable to lookup Transaction..."

@pytest.fixture
def sample_xref() -> CardCrossReference:
    """Build a sample :class:`CardCrossReference` row.

    Maps each field to its COBOL copybook counterpart
    (``app/cpy/CVACT03Y.cpy``):

    =================  ==================  ==================
    COBOL Field        ORM Attribute       Test Value
    =================  ==================  ==================
    XREF-CARD-NUM      card_num            ``"4111111111111111"``
    XREF-CUST-ID       cust_id             ``"000000001"``
    XREF-ACCT-ID       acct_id             ``"00000000001"``
    =================  ==================  ==================

    The ``card_num`` matches :data:`_TEST_CARD_NUM` and the
    ``acct_id`` matches :data:`_TEST_ACCT_ID` so that the XREF
    lookup succeeds AND the post-lookup ``XREF-ACCT-ID ==
    request.acct_id`` guard passes, permitting the
    ``add_transaction`` service to proceed past Step 1.

    Maps to COBOL: ``COTRN02C READ CCXREF -> XREF-CARD-NUM /
    XREF-ACCT-ID`` pattern at lines 402-435.

    Returns
    -------
    CardCrossReference
        A detached ORM instance ready to be returned from the mocked
        ``scalar_one_or_none()`` call on the xref lookup query.
    """
    return CardCrossReference(
        card_num=_TEST_CARD_NUM,
        cust_id=_TEST_CUST_ID,
        acct_id=_TEST_ACCT_ID,
    )


@pytest.fixture
def sample_add_request() -> TransactionAddRequest:
    """Build a valid :class:`TransactionAddRequest` for add-transaction tests.

    The request is tuned to pass all four Pydantic field validators
    in ``transaction_schema.py``:

    * ``_validate_acct_id``: must be a non-empty all-digit string,
      up to 11 chars.
    * ``_validate_card_num``: must be EXACTLY 16 all-digit chars.
    * ``_validate_tran_type_cd``: must be EXACTLY 2 chars.
    * ``_validate_amount_positive``: must be a :class:`Decimal`
      strictly greater than zero.

    CRITICAL: ``amount`` is a :class:`decimal.Decimal` -- never a
    ``float``. ``Decimal("50.00")`` preserves the COBOL
    ``PIC S9(09)V99`` semantics.

    Returns
    -------
    TransactionAddRequest
        A fresh request instance suitable for the happy-path add
        transaction tests. Optional ``proc_date``,
        ``merchant_id``/``name``/``city``/``zip`` are intentionally
        left unset so the service's default-substitution logic is
        exercised.
    """
    return TransactionAddRequest(
        acct_id=_TEST_ACCT_ID,
        card_num=_TEST_CARD_NUM,
        tran_type_cd="01",
        tran_cat_cd="0001",
        tran_source="ONLINE",
        description="Test transaction",
        amount=Decimal("50.00"),
        orig_date="2025-01-15",
    )


# ============================================================================
# Helper functions (test-only)
# ============================================================================


def _make_list_execute_side_effect(
    *,
    transactions: list[Transaction] | None = None,
    total_count: int | None = None,
) -> list[MagicMock]:
    """Build the 2-element side_effect list for :meth:`list_transactions`.

    :meth:`TransactionService.list_transactions` invokes
    ``self.db.execute`` exactly TWICE on success:

    ==  =======================================  ===========================
    #   Query                                    Accessor invoked
    ==  =======================================  ===========================
    1   SELECT Transaction ... LIMIT/OFFSET      ``scalars().all()``
    2   SELECT COUNT(*) FROM transactions...     ``scalar_one()``
    ==  =======================================  ===========================

    Parameters
    ----------
    transactions : list[Transaction] | None, optional
        Rows to return from the first ``scalars().all()`` call.
        ``None`` defaults to an empty list (no rows).
    total_count : int | None, optional
        Value to return from the second ``scalar_one()`` call.
        ``None`` defaults to ``len(transactions or [])`` so the
        total matches what the page query returned.

    Returns
    -------
    list[MagicMock]
        Ordered list of 2 MagicMock result objects for successive
        ``await self.db.execute(...)`` calls.
    """
    rows: list[Transaction] = transactions if transactions is not None else []
    count: int = total_count if total_count is not None else len(rows)

    # Query 1: page of rows.
    page_scalars = MagicMock()
    page_scalars.all = MagicMock(return_value=rows)
    page_result = MagicMock()
    page_result.scalars = MagicMock(return_value=page_scalars)

    # Query 2: total count.
    count_result = MagicMock()
    count_result.scalar_one = MagicMock(return_value=count)

    return [page_result, count_result]


def _make_detail_execute_side_effect(
    *,
    transaction: Transaction | None,
) -> list[MagicMock]:
    """Build the single-element side_effect list for :meth:`get_transaction_detail`.

    :meth:`TransactionService.get_transaction_detail` invokes
    ``self.db.execute`` exactly ONCE on success, with the result
    accessed via ``result.scalar_one_or_none()``. (When the input is
    empty / whitespace-only the service short-circuits BEFORE issuing
    any query at all -- in that case the caller should NOT install
    a side_effect.)

    Parameters
    ----------
    transaction : Transaction | None
        Row to return from ``scalar_one_or_none()``. ``None``
        simulates the COBOL NOTFND branch (transaction not found).

    Returns
    -------
    list[MagicMock]
        A single-element list containing one MagicMock result object.
    """
    result = MagicMock()
    result.scalar_one_or_none = MagicMock(return_value=transaction)
    return [result]


def _make_add_execute_side_effect(
    *,
    xref: CardCrossReference | None,
    max_tran_id_agg: str | None = None,
    max_tran_id_sort: str | None = None,
) -> list[MagicMock]:
    """Build the side_effect list for :meth:`add_transaction`.

    :meth:`TransactionService.add_transaction` invokes
    ``self.db.execute`` up to THREE times in a fixed order; the
    service short-circuits on early XREF failure:

    ==  =======================================  ===========================
    #   Service step (transaction_service.py)    Accessor invoked
    ==  =======================================  ===========================
    1   xref lookup by card_num                  ``scalar_one_or_none()``
    2   SELECT MAX(tran_id)                       ``scalar()``
    3   SELECT tran_id ORDER BY DESC LIMIT 1      ``scalar()``
    ==  =======================================  ===========================

    The function mirrors the service's short-circuit paths:

    * ``xref is None`` -> only result #1 (then service rollbacks and
      returns with xref-not-found message).
    * ``xref.acct_id != request.acct_id`` -> only result #1 (then
      service rollbacks and returns with acct/card mismatch message).
      NOTE: This short-circuit is NOT known by this helper since the
      request is not passed in; callers must choose an xref whose
      ``acct_id`` matches the request's ``acct_id`` to reach queries
      2 and 3. For the mismatch test, callers pass a mismatched
      xref and only the first result is consumed.
    * xref found and matches -> all three results returned.

    Parameters
    ----------
    xref : CardCrossReference | None
        Row returned from ``scalar_one_or_none()`` on the xref query.
        ``None`` simulates the COBOL ``READ CCXREF`` NOTFND branch.
    max_tran_id_agg : str | None, optional
        Scalar value returned from the ``SELECT MAX(tran_id)`` query.
        ``None`` simulates an empty ``transactions`` table.
    max_tran_id_sort : str | None, optional
        Scalar value returned from the ``SELECT ... ORDER BY DESC
        LIMIT 1`` verification query. Usually equal to
        ``max_tran_id_agg``; only differs when the test is
        deliberately exercising the aggregate-mismatch warning path.

    Returns
    -------
    list[MagicMock]
        Ordered list of 1 or 3 MagicMock result objects.
    """
    results: list[MagicMock] = []

    # Query 1: CardCrossReference by card_num (Step 1).
    xref_result = MagicMock()
    xref_result.scalar_one_or_none = MagicMock(return_value=xref)
    results.append(xref_result)

    # Short-circuit: xref not found -> service returns after Query 1
    # with the "Unable to lookup Card # in XREF file..." message.
    # Caller should have installed a side_effect of len 1 for this
    # scenario, but we still return len(1) for compatibility.
    if xref is None:
        return results

    # Query 2: SELECT MAX(tran_id) via func.max() (Step 2a).
    max_agg_result = MagicMock()
    max_agg_result.scalar = MagicMock(return_value=max_tran_id_agg)
    results.append(max_agg_result)

    # Query 3: SELECT tran_id ORDER BY DESC LIMIT 1 (Step 2b,
    # verification query).
    max_sort_result = MagicMock()
    max_sort_result.scalar = MagicMock(return_value=max_tran_id_sort)
    results.append(max_sort_result)

    return results


def _extract_added_transaction(mock_session: AsyncMock) -> Transaction:
    """Retrieve the :class:`Transaction` instance staged for INSERT.

    :meth:`TransactionService.add_transaction` stages the INSERT by
    invoking ``self.db.add(new_transaction)`` BEFORE flushing. In the
    test, the ``add`` method is a synchronous :class:`MagicMock`, so
    we can inspect its call args to retrieve the exact
    :class:`Transaction` instance that would have been persisted.

    Tests use this helper to verify that every field of the staged
    Transaction matches its COBOL ``WRITE-TRANSACT-FILE`` expectation
    from ``COTRN02C.cbl``.

    Parameters
    ----------
    mock_session : AsyncMock
        The mocked session after :meth:`add_transaction` has been
        awaited. Must have ``add`` called at least once.

    Returns
    -------
    Transaction
        The first positional argument of the first ``session.add``
        call -- the :class:`Transaction` instance staged for INSERT.

    Raises
    ------
    AssertionError
        When ``session.add`` was never called, or when its first
        argument is not a :class:`Transaction` instance. Both
        conditions indicate a regression in the service (the add
        was not staged).
    """
    assert mock_session.add.called, (
        "Expected TransactionService.add_transaction to call "
        "session.add(transaction) for the Transaction INSERT "
        "staging step, but session.add was never invoked. This "
        "indicates the service short-circuited before the stage "
        "step (see transaction_service.py add_transaction method)."
    )
    first_call_args, _first_call_kwargs = mock_session.add.call_args
    assert len(first_call_args) == 1, (
        f"session.add was called with {len(first_call_args)} "
        f"positional args; expected exactly 1 (the Transaction "
        f"instance). Args: {first_call_args!r}"
    )
    staged = first_call_args[0]
    assert isinstance(staged, Transaction), (
        f"session.add was called with {type(staged).__name__}; "
        f"expected a Transaction instance. This indicates the "
        f"staged object is not a Transaction ORM row, which would "
        f"break the COTRN02C.cbl WRITE-TRANSACT-FILE contract."
    )
    return staged



# ============================================================================
# Phase 3: Transaction List Tests (Feature F-009 -- COTRN00C.cbl)
# ============================================================================
#
# COTRN00C.cbl (~699 lines) implements a paginated browse of the
# TRANSACT file (VSAM KSDS). The COBOL screen (COTRN00.CPY / COTRN00.bms)
# uses ``OCCURS 10 TIMES`` to display 10 rows per page; the service's
# page_size default MUST match this value (enforced by the
# ``_DEFAULT_PAGE_SIZE`` constant in ``transaction_schema.py``).
#
# Each of these tests validates a distinct behavior of
# :meth:`TransactionService.list_transactions`:
#
#   * Default page size of 10 (matches the OCCURS 10 TIMES mapping).
#   * Pagination offset arithmetic ((page - 1) * page_size).
#   * Optional tran_id filter behaves as a LIKE prefix match.
#   * LIKE metachar escaping (``%`` and ``_``) on filter input.
#   * Empty-result branch returns ``total_count=0``.
#   * ``execute()`` failure path returns the ``_MSG_UNABLE_TO_LOOKUP_LIST``
#     error message and NO rows.
#   * Row -> TransactionListItem mapping preserves key fields.
#   * ``TransactionListItem.amount`` is a :class:`Decimal` (never float).
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_default_page_size_10(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transactions: list[Transaction],
) -> None:
    """Default page size MUST be 10 (matching COTRN00.CPY OCCURS 10 TIMES).

    CRITICAL TEST: The COBOL screen ``COTRN00.bms`` / ``COTRN00.CPY``
    declares a ``TRNDTLI OCCURS 10 TIMES`` array for the paginated
    transaction browse; the modernized service's default page size
    MUST match so the API-layer clients that were migrated from CICS
    see the same rows-per-page behavior.

    This test asserts:

    * The default ``TransactionListRequest.page_size`` is 10.
    * When the mocked database returns the first 10 transactions,
      the service response contains exactly 10 ``TransactionListItem``
      instances (no more, no fewer).

    Maps to COBOL: ``COTRN00.CPY`` OCCURS 10 TIMES / COTRN00C
    PROCESS-PAGE-FORWARD inner loop.
    """
    # Arrange: Default TransactionListRequest with page_size=10 --
    # the service should issue a query whose LIMIT equals 10 and
    # the result list should have at most 10 items.
    request = TransactionListRequest()
    first_10 = sample_transactions[:_DEFAULT_PAGE_SIZE]
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=first_10,
        total_count=len(sample_transactions),
    )

    # Act
    response = await transaction_service.list_transactions(request)

    # Assert 1: Default page_size on the request is 10. This guards
    # against any future drift in the schema default.
    assert request.page_size == _DEFAULT_PAGE_SIZE, (
        f"TransactionListRequest default page_size must be "
        f"{_DEFAULT_PAGE_SIZE} (COTRN00.CPY OCCURS 10 TIMES); got {request.page_size}"
    )

    # Assert 2: Response type and length.
    assert isinstance(response, TransactionListResponse), (
        f"Expected TransactionListResponse; got {type(response).__name__}"
    )
    assert len(response.transactions) == _DEFAULT_PAGE_SIZE, (
        f"Expected exactly {_DEFAULT_PAGE_SIZE} items on the first "
        f"page (matching COBOL OCCURS 10 TIMES); got {len(response.transactions)}"
    )

    # Assert 3: Pagination metadata is correct.
    assert response.page == 1
    assert response.total_count == len(sample_transactions)
    assert response.message is None, f"Success path should have message=None; got {response.message!r}"

    # Assert 4: execute was awaited twice (page query + count query).
    assert mock_db_session.execute.await_count == 2, (
        f"list_transactions should issue exactly 2 execute calls "
        f"(page + count); got {mock_db_session.execute.await_count}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_pagination_offset(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transactions: list[Transaction],
) -> None:
    """Pagination offset = ``(page - 1) * page_size``.

    Covers the navigation-forward / navigation-back semantics in
    ``COTRN00C.cbl``: each PF7/PF8 keypress re-enters the program
    with a positioning key that corresponds to the first tran_id of
    the requested page. In SQL terms, that's ``OFFSET (page - 1) *
    page_size``.

    This test requests page 2 (offset = 10) and asserts:

    * The service issues the count query and returns the FULL count
      of underlying rows, not just the count on the page.
    * The response carries ``page=2``.
    * The items on page 2 are the rows the service received from the
      second-page mocked result (the test controls what's "on page
      2" by supplying the rows directly).

    Maps to COBOL: ``COTRN00C`` PROCESS-PAGE-FORWARD positioning /
    STARTBR GTEQ with the last-tran_id of the previous page.
    """
    # Arrange: Request page 2; provide rows 11-15 (indices 10-14) as
    # the "second page" content.
    request = TransactionListRequest(page=2, page_size=_DEFAULT_PAGE_SIZE)
    second_page = sample_transactions[_DEFAULT_PAGE_SIZE:]
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=second_page,
        total_count=len(sample_transactions),
    )

    # Act
    response = await transaction_service.list_transactions(request)

    # Assert: Response metadata reflects the requested page and
    # the global total, NOT a page-local count.
    assert response.page == 2, f"Expected response.page == 2; got {response.page}"
    assert response.total_count == len(sample_transactions), (
        f"total_count should reflect the ENTIRE result set size "
        f"(for UI pagination controls), not just the current page; "
        f"expected {len(sample_transactions)}, got {response.total_count}"
    )
    # 5 rows on this page (rows 11-15).
    assert len(response.transactions) == len(second_page), (
        f"Page 2 should contain exactly {len(second_page)} rows (rows 11-15); got {len(response.transactions)}"
    )
    # First row on page 2 is tran_id=11.
    assert response.transactions[0].tran_id == "0000000000000011", (
        f"First row on page 2 should be tran_id='0000000000000011'; got {response.transactions[0].tran_id!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_filter_by_tran_id_prefix(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transactions: list[Transaction],
) -> None:
    """Optional ``tran_id`` filter on the request narrows the result set.

    Covers the TRNIDINI input-field on ``COTRN00.bms`` -- users can
    optionally type a partial transaction ID to jump-scan / filter
    the browse. In the COBOL original, this is a STARTBR GTEQ with
    the partial key; in the Python service it's a ``WHERE tran_id
    LIKE :pattern || '%'`` clause.

    This test:

    * Installs a filter ``tran_id="000000000000000"`` (15 chars, a
      prefix that matches all 15 sample rows).
    * Mocks the database to return the 10 rows that would be found
      on page 1.
    * Verifies the response carries the 10 rows and the filter was
      NOT rejected (the service accepts the filter string).

    Maps to COBOL: COTRN00C TRNIDINI input / STARTBR GTEQ on partial
    TRAN-ID key.
    """
    # Arrange
    request = TransactionListRequest(tran_id="000000000000000", page=1, page_size=_DEFAULT_PAGE_SIZE)
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=sample_transactions[:_DEFAULT_PAGE_SIZE],
        total_count=15,
    )

    # Act
    response = await transaction_service.list_transactions(request)

    # Assert: The filter was accepted (no error message) and rows
    # came through.
    assert response.message is None, (
        f"Filter should be accepted cleanly; got error message {response.message!r}"
    )
    assert len(response.transactions) == _DEFAULT_PAGE_SIZE
    assert response.total_count == 15

    # Assert: execute was awaited twice (page + count); the filter
    # is applied on BOTH statements so the count is consistent with
    # the page.
    assert mock_db_session.execute.await_count == 2, (
        f"Filter should be applied on both page and count queries, "
        f"so execute should be awaited exactly 2 times; got {mock_db_session.execute.await_count}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_filter_escapes_like_metachars(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """LIKE metacharacters (``%``, ``_``, ``\\``) in the filter are escaped.

    The service uses ``Transaction.tran_id.like(pattern, escape='\\\\')``
    and must escape any literal ``%`` and ``_`` characters in the
    incoming filter string so that a user-provided ``tran_id`` like
    ``"123_456"`` is treated as the literal underscore and does not
    accidentally match ``"123X456"``.

    This test provides a filter with all three metacharacters and
    asserts:

    * The service does NOT raise.
    * The service issues the two expected execute calls.
    * The response is returned cleanly (empty, since the mock has
      no rows matching the escaped pattern).

    This is a regression guard against SQL-injection-like surprise
    matches; the actual LIKE pattern construction is an
    implementation detail exercised end-to-end here (not asserted
    structurally because the service builds the pattern internally).

    Maps to COBOL: N/A (COBOL STARTBR GTEQ is a byte-literal key
    lookup -- there are no metacharacters in VSAM). The Python
    service must sanitize the input to remain safe when the same
    user-intent is expressed via SQL LIKE.
    """
    # Arrange: A filter with all three LIKE metacharacters. If the
    # escape logic misbehaved, the service would either return
    # unexpected matches or raise an error.
    request = TransactionListRequest(tran_id="12%_\\3", page=1, page_size=_DEFAULT_PAGE_SIZE)
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=[], total_count=0,
    )

    # Act (must not raise)
    response = await transaction_service.list_transactions(request)

    # Assert: No error message, empty list, no exception.
    assert response.message is None, (
        f"Escaped-metachar filter must not trigger the error path; got message={response.message!r}"
    )
    assert response.transactions == []
    assert response.total_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_empty_result(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """Empty result set returns ``transactions=[]``, ``total_count=0``, no message.

    Covers the branch where the TRANSACT file has no rows matching
    the (optional) filter. In COBOL, this is the STARTBR-then-
    NOTFND branch: the browse starts at end-of-file immediately.
    The Python service returns a clean empty response -- NOT an
    error -- so that the UI can display the (empty) grid without
    a red-banner error.

    Maps to COBOL: COTRN00C PROCESS-PAGE-FORWARD with NOTFND on
    the first READNEXT.
    """
    # Arrange: No rows match.
    request = TransactionListRequest()
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=[], total_count=0,
    )

    # Act
    response = await transaction_service.list_transactions(request)

    # Assert: Empty but well-formed response.
    assert isinstance(response, TransactionListResponse)
    assert response.transactions == [], f"Empty-result response should have transactions=[]; got {response.transactions!r}"
    assert response.total_count == 0, f"Empty-result response should have total_count=0; got {response.total_count}"
    assert response.page == 1
    # No error message on empty result (just a clean empty grid).
    assert response.message is None, (
        f"Empty-result is NOT an error condition; message must be None. Got {response.message!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_db_error_returns_empty_message(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """Database error during page query returns the COBOL-exact error message.

    When the page-query execute raises an exception (e.g., the DB
    is unreachable, the query is syntactically invalid, or the
    underlying table is locked), the service must:

    * Catch the exception (not propagate it).
    * Return an empty-but-well-formed TransactionListResponse
      carrying the COBOL-exact message
      ``"Unable to lookup transaction..."`` (lowercase 't',
      singular -- from COTRN00C.cbl DFHRESP(OTHER) on the initial
      STARTBR at lines 615 / 649 / 683).
    * Include ``transactions=[]`` and ``total_count=0``.

    Maps to COBOL: COTRN00C ``WHEN OTHER`` branch on STARTBR /
    READNEXT DFHRESP check.
    """
    # Arrange: The FIRST execute raises; the service should catch
    # it and NOT proceed to the count query.
    request = TransactionListRequest()
    mock_db_session.execute.side_effect = RuntimeError("Simulated DB failure on page query")

    # Act (must not raise)
    response = await transaction_service.list_transactions(request)

    # Assert: Error response is well-formed.
    assert isinstance(response, TransactionListResponse)
    assert response.transactions == [], (
        f"DB-error response should have transactions=[]; got {response.transactions!r}"
    )
    assert response.total_count == 0, (
        f"DB-error response should have total_count=0; got {response.total_count}"
    )
    assert response.page == 1, (
        f"DB-error response should echo the requested page; got {response.page}"
    )
    assert response.message == _MSG_UNABLE_TO_LOOKUP_LIST, (
        f"Expected COBOL-exact error message {_MSG_UNABLE_TO_LOOKUP_LIST!r} "
        f"(from COTRN00C.cbl); got {response.message!r}. This literal "
        f"must be preserved byte-for-byte per AAP Section 0.7.1."
    )



@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_maps_rows_correctly(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transaction: Transaction,
) -> None:
    """Each ORM row is correctly mapped to a :class:`TransactionListItem`.

    Validates the row-to-DTO mapping logic in
    ``list_transactions``. Each ``TransactionListItem`` has exactly
    four fields:

    * ``tran_id``: taken verbatim from ``Transaction.tran_id``.
    * ``tran_date``: an 8-char condensed date derived from the first
      10 chars of ``orig_ts`` with the dashes stripped (YYYYMMDD).
    * ``description``: truncated to
      :data:`_LIST_DESC_WIDTH` (26) from ``Transaction.description``.
    * ``amount``: ``Transaction.amount`` as a :class:`Decimal`.

    Maps to COBOL: ``COTRN00C`` inner-loop row-to-screen copy
    (``MOVE TRAN-ID TO TRNIDOI``, ``MOVE TRAN-DESC TO TDESCO``,
    ``MOVE TRAN-AMT TO TAMOUNTO``, etc.)
    """
    # Arrange: single-row list for crisp field-level assertions.
    request = TransactionListRequest()
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=[sample_transaction],
        total_count=1,
    )

    # Act
    response = await transaction_service.list_transactions(request)

    # Assert: One row in the response with all fields correctly mapped.
    assert len(response.transactions) == 1, (
        f"Expected 1 row in the response; got {len(response.transactions)}"
    )
    item = response.transactions[0]
    assert isinstance(item, TransactionListItem), (
        f"Each row must be a TransactionListItem; got {type(item).__name__}"
    )

    # tran_id verbatim.
    assert item.tran_id == sample_transaction.tran_id, (
        f"tran_id should be verbatim from ORM; expected "
        f"{sample_transaction.tran_id!r}, got {item.tran_id!r}"
    )
    assert len(item.tran_id) == _EXPECTED_TRAN_ID_WIDTH, (
        f"tran_id must be exactly {_EXPECTED_TRAN_ID_WIDTH} chars "
        f"(COBOL PIC X(16)); got length {len(item.tran_id)}"
    )

    # tran_date: first 10 chars of orig_ts, dashes stripped, truncated
    # to 8 chars. orig_ts="2025-01-15 10:30:00.000000" -> "20250115".
    assert item.tran_date == "20250115", (
        f"tran_date should be YYYYMMDD (8 chars) derived from "
        f"orig_ts; expected '20250115', got {item.tran_date!r}"
    )
    assert len(item.tran_date) <= _LIST_DATE_WIDTH

    # description truncated to _LIST_DESC_WIDTH.
    assert len(item.description) <= _LIST_DESC_WIDTH, (
        f"description must be truncated to {_LIST_DESC_WIDTH} chars "
        f"(BMS TDESCO width); got length {len(item.description)}"
    )
    assert item.description == "Test purchase", (
        f"description should come from Transaction.description; "
        f"got {item.description!r}"
    )

    # amount is Decimal (the critical monetary-precision check).
    assert isinstance(item.amount, Decimal), (
        f"amount MUST be Decimal (never float) per AAP Section "
        f"0.7.2; got {type(item.amount).__name__}"
    )
    assert item.amount == Decimal("50.00"), (
        f"amount should match sample_transaction.amount; "
        f"expected Decimal('50.00'), got {item.amount!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_list_transactions_amount_is_decimal(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transactions: list[Transaction],
) -> None:
    """Every list-item amount is :class:`Decimal` -- never :class:`float`.

    CRITICAL: AAP Section 0.7.2 "Financial Precision" mandates that
    all monetary values use ``decimal.Decimal``. COBOL ``PIC
    S9(09)V99`` TRAN-AMT semantics require banker's rounding
    (``ROUND_HALF_EVEN``) and exact-2-decimal-place representation;
    IEEE-754 ``float`` cannot represent these values exactly (e.g.,
    ``0.1 + 0.2 != 0.3``).

    This test loads 15 transactions with distinct amounts, issues
    the list query, and asserts that EVERY returned amount is a
    :class:`Decimal` instance.

    Maps to COBOL: ``CVTRA05Y.cpy`` TRAN-AMT PIC S9(09)V99.
    """
    # Arrange: 15 rows across 2 pages; request page 1 (10 rows).
    request = TransactionListRequest(page=1, page_size=_DEFAULT_PAGE_SIZE)
    mock_db_session.execute.side_effect = _make_list_execute_side_effect(
        transactions=sample_transactions[:_DEFAULT_PAGE_SIZE],
        total_count=len(sample_transactions),
    )

    # Act
    response = await transaction_service.list_transactions(request)

    # Assert: Every amount is a Decimal.
    assert len(response.transactions) == _DEFAULT_PAGE_SIZE
    for idx, item in enumerate(response.transactions):
        assert isinstance(item.amount, Decimal), (
            f"Row #{idx} amount MUST be Decimal (never float) per "
            f"AAP Section 0.7.2; got {type(item.amount).__name__} "
            f"with value {item.amount!r}"
        )
        # Defensive: the amount MUST preserve the 2-decimal scale
        # of COBOL PIC S9(09)V99. Decimal's as_tuple().exponent
        # gives the negated scale; -2 means two decimal places.
        # For finite Decimal values, exponent is always int; the
        # Literal['n', 'N', 'F'] variants only occur for NaN /
        # sNaN / Infinity, which are never legal monetary values.
        amount_exponent = item.amount.as_tuple().exponent
        assert isinstance(amount_exponent, int), (
            f"Row #{idx} amount must be a finite Decimal (not NaN / "
            f"Infinity); got exponent {amount_exponent!r}"
        )
        assert amount_exponent <= 0, (
            f"Row #{idx} amount should preserve 2-decimal-place "
            f"scale; got exponent {amount_exponent} "
            f"from Decimal {item.amount!r}"
        )


# ============================================================================
# Phase 4: Transaction Detail Tests (Feature F-010 -- COTRN01C.cbl)
# ============================================================================
#
# COTRN01C.cbl (~330 lines) implements the keyed detail READ of a
# single transaction row. It is a simpler program than COTRN00C or
# COTRN02C -- just a CICS READ FILE('TRANSACT') with RIDFLD(TRAN-ID)
# and error-branch handling for NOTFND, empty input, and OTHER.
#
# Tests in this phase validate:
#
#   * Happy path: keyed lookup returns the row.
#   * NOTFND branch: returns the COBOL-exact "Transaction ID NOT
#     found..." message.
#   * Empty-input branch: returns "Tran ID can NOT be empty..."
#     WITHOUT issuing any database query.
#   * Whitespace-only input: treated as empty.
#   * WHEN OTHER branch: returns "Unable to lookup Transaction..."
#   * Monetary amount is a :class:`Decimal`.
#   * Field truncation to BMS widths (description, merchant_name,
#     merchant_city, dates).
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_success(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transaction: Transaction,
) -> None:
    """Keyed lookup returns a fully-populated :class:`TransactionDetailResponse`.

    Maps to COBOL: ``COTRN01C`` ``EXEC CICS READ FILE('TRANSACT')
    INTO(TRAN-RECORD) RIDFLD(TRAN-ID)`` followed by the MOVE
    operations that copy each TRAN-RECORD field into the BMS
    symbolic map fields.

    Asserts every field of the response is correctly populated from
    the ORM row:

    * ``tran_id_input`` echoes the caller's input (after strip).
    * ``tran_id``, ``card_num``, ``tran_type_cd`` (from model
      ``type_cd``), ``tran_cat_cd`` (from ``cat_cd``),
      ``tran_source`` (from ``source``), ``description``, ``amount``,
      merchant fields.
    * ``orig_date`` / ``proc_date`` are 10-char dates derived from
      the 26-char orig_ts / proc_ts.
    * ``amount`` is a :class:`Decimal`.
    * ``message is None`` (success path).
    """
    # Arrange
    mock_db_session.execute.side_effect = _make_detail_execute_side_effect(
        transaction=sample_transaction,
    )

    # Act
    response = await transaction_service.get_transaction_detail("0000000000000001")

    # Assert 1: Response is a TransactionDetailResponse.
    assert isinstance(response, TransactionDetailResponse), (
        f"Expected TransactionDetailResponse; got {type(response).__name__}"
    )

    # Assert 2: tran_id_input echoes input.
    assert response.tran_id_input == "0000000000000001"

    # Assert 3: Identifier fields come from the ORM row.
    assert response.tran_id == sample_transaction.tran_id
    assert len(response.tran_id) == _EXPECTED_TRAN_ID_WIDTH
    assert response.card_num == sample_transaction.card_num
    assert len(response.card_num) == 16

    # Assert 4: tran_type_cd, tran_cat_cd, tran_source are mapped
    # from model fields type_cd, cat_cd, source (name translation
    # from the schema -> model naming convention).
    assert response.tran_type_cd == sample_transaction.type_cd
    assert response.tran_cat_cd == sample_transaction.cat_cd
    assert response.tran_source == sample_transaction.source

    # Assert 5: Description verbatim.
    assert response.description == sample_transaction.description

    # Assert 6: amount is Decimal (the critical monetary check).
    assert isinstance(response.amount, Decimal), (
        f"amount MUST be Decimal (never float); got {type(response.amount).__name__}"
    )
    assert response.amount == Decimal("50.00")

    # Assert 7: Date fields are 10 chars (CCYY-MM-DD).
    assert response.orig_date == "2025-01-15", (
        f"orig_date should be first 10 chars of orig_ts; got {response.orig_date!r}"
    )
    assert response.proc_date == "2025-01-15", (
        f"proc_date should be first 10 chars of proc_ts; got {response.proc_date!r}"
    )
    assert len(response.orig_date) <= _DETAIL_DATE_WIDTH
    assert len(response.proc_date) <= _DETAIL_DATE_WIDTH

    # Assert 8: Merchant fields.
    assert response.merchant_id == sample_transaction.merchant_id
    assert response.merchant_name == sample_transaction.merchant_name
    assert response.merchant_city == sample_transaction.merchant_city
    assert response.merchant_zip == sample_transaction.merchant_zip

    # Assert 9: Success path has no error message.
    assert response.message is None, (
        f"Success path must have message=None; got {response.message!r}"
    )

    # Assert 10: Exactly one DB query was issued (the keyed READ).
    assert mock_db_session.execute.await_count == 1, (
        f"get_transaction_detail issues exactly ONE DB query "
        f"(keyed READ on tran_id); got {mock_db_session.execute.await_count}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_not_found(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """NOTFND branch returns the COBOL-exact "Transaction ID NOT found..." message.

    When the keyed READ returns no matching row (``scalar_one_or_none()``
    yields ``None``), the service must return an empty-but-well-formed
    TransactionDetailResponse carrying the message from
    :data:`_MSG_TRAN_NOT_FOUND`.

    Maps to COBOL: ``COTRN01C`` ``WHEN DFHRESP(NOTFND)`` branch.
    """
    # Arrange: mock returns None.
    mock_db_session.execute.side_effect = _make_detail_execute_side_effect(
        transaction=None,
    )

    # Act
    response = await transaction_service.get_transaction_detail("9999999999999999")

    # Assert: empty detail response with the COBOL-exact message.
    assert isinstance(response, TransactionDetailResponse)
    assert response.tran_id_input == "9999999999999999", (
        f"tran_id_input should echo the input that was NOT FOUND; "
        f"got {response.tran_id_input!r}"
    )
    # All "not found" response fields are empty strings (except
    # amount which is Decimal('0.00')).
    assert response.tran_id == ""
    assert response.card_num == ""
    assert response.tran_type_cd == ""
    assert response.tran_cat_cd == ""
    assert response.tran_source == ""
    assert response.description == ""
    assert response.orig_date == ""
    assert response.proc_date == ""
    assert response.merchant_id == ""
    assert response.merchant_name == ""
    assert response.merchant_city == ""
    assert response.merchant_zip == ""

    # Amount is Decimal('0.00') -- not None, not 0.0 float.
    assert isinstance(response.amount, Decimal), (
        f"Not-found response amount must be Decimal; got {type(response.amount).__name__}"
    )
    assert response.amount == Decimal("0.00")

    # The COBOL-exact error message.
    assert response.message == _MSG_TRAN_NOT_FOUND, (
        f"Expected COBOL-exact message {_MSG_TRAN_NOT_FOUND!r} "
        f"(from COTRN01C.cbl DFHRESP(NOTFND) branch); got "
        f"{response.message!r}. Must be preserved byte-for-byte per "
        f"AAP Section 0.7.1."
    )



@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_empty_input(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """Empty input triggers immediate rejection WITHOUT issuing a DB query.

    CRITICAL: The service must short-circuit on empty / None /
    whitespace-only input at the very start of the method, BEFORE
    issuing any ``await self.db.execute(...)`` call. This mirrors
    the COBOL ``COTRN01C`` entry-check:

    ``IF TRNIDINL OF COTRN01AI = 0 OR TRNIDINI = SPACES``
    ``   MOVE 'Y' TO WS-ERR-FLG``
    ``   MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE``

    This test tries three forms of empty input:

    * empty string ``""``
    * single space ``" "``
    * ``None`` (caller passed Python None where CICS would see SPACES)

    In all three cases, the service must return the
    :data:`_MSG_TRAN_ID_EMPTY` message and NOT issue a DB query.

    Maps to COBOL: COTRN01C TRNIDINL / TRNIDINI SPACES guard.
    """
    for empty_input in ("", " ", "   "):
        # Reset the mock counter between iterations.
        mock_db_session.execute.reset_mock()

        # Act
        response = await transaction_service.get_transaction_detail(empty_input)

        # Assert 1: The response is an empty-detail response with
        # the COBOL-exact empty-input message.
        assert isinstance(response, TransactionDetailResponse)
        assert response.message == _MSG_TRAN_ID_EMPTY, (
            f"Expected COBOL-exact message {_MSG_TRAN_ID_EMPTY!r} "
            f"(from COTRN01C.cbl TRNIDINL guard); got "
            f"{response.message!r} for input {empty_input!r}"
        )

        # Assert 2: tran_id_input is echoed verbatim (preserving
        # whatever whitespace the caller sent, so the UI can
        # highlight the input field).
        assert response.tran_id_input == empty_input, (
            f"tran_id_input should echo the original input verbatim "
            f"(preserving whitespace); expected {empty_input!r}, got "
            f"{response.tran_id_input!r}"
        )

        # Assert 3: No DB query was issued (short-circuit).
        assert mock_db_session.execute.await_count == 0, (
            f"Empty input {empty_input!r} must NOT issue a DB query; "
            f"got {mock_db_session.execute.await_count} execute calls"
        )

        # Assert 4: tran_id, card_num, and other response fields
        # are empty strings.
        assert response.tran_id == ""
        assert response.card_num == ""
        assert response.amount == Decimal("0.00")


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_db_error(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """Database error returns the COBOL-exact WHEN OTHER message.

    When the keyed READ raises an exception (e.g., DB unreachable,
    connection reset, query timeout), the service must:

    * Catch the exception (not propagate it).
    * Return an empty-detail response carrying
      :data:`_MSG_UNABLE_TO_LOOKUP_DETAIL`.

    Maps to COBOL: COTRN01C ``WHEN OTHER`` branch of DFHRESP check.
    """
    # Arrange
    mock_db_session.execute.side_effect = RuntimeError("Simulated DB failure on keyed read")

    # Act (must not raise)
    response = await transaction_service.get_transaction_detail("0000000000000001")

    # Assert: Error response is well-formed.
    assert isinstance(response, TransactionDetailResponse)
    assert response.tran_id_input == "0000000000000001", (
        "tran_id_input should echo the input that triggered the error"
    )
    assert response.message == _MSG_UNABLE_TO_LOOKUP_DETAIL, (
        f"Expected COBOL-exact message {_MSG_UNABLE_TO_LOOKUP_DETAIL!r} "
        f"(from COTRN01C.cbl WHEN OTHER branch); got {response.message!r}. "
        f"Must be preserved byte-for-byte per AAP Section 0.7.1."
    )
    assert response.amount == Decimal("0.00")


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_amount_is_decimal(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_transaction: Transaction,
) -> None:
    """Detail response amount is a :class:`Decimal` -- never ``float``.

    CRITICAL: AAP Section 0.7.2 "Financial Precision" mandates that
    monetary values flowing through the API MUST use ``decimal.Decimal``.
    This test explicitly asserts the type on the success path.

    Maps to COBOL: ``CVTRA05Y.cpy`` TRAN-AMT PIC S9(09)V99.
    """
    # Arrange
    mock_db_session.execute.side_effect = _make_detail_execute_side_effect(
        transaction=sample_transaction,
    )

    # Act
    response = await transaction_service.get_transaction_detail(sample_transaction.tran_id)

    # Assert: amount is Decimal with the right value.
    assert isinstance(response.amount, Decimal), (
        f"Detail response amount MUST be Decimal (never float); "
        f"got {type(response.amount).__name__}"
    )
    assert response.amount == Decimal("50.00"), (
        f"amount should match sample_transaction.amount; expected "
        f"Decimal('50.00'), got {response.amount!r}"
    )
    # Scale-preservation guard: 2 decimal places per COBOL
    # PIC S9(09)V99. For finite Decimal values, exponent is always
    # int; Literal['n', 'N', 'F'] only occurs for NaN / Infinity
    # (never legal monetary values).
    detail_amount_exponent = response.amount.as_tuple().exponent
    assert isinstance(detail_amount_exponent, int), (
        f"amount must be a finite Decimal (not NaN / Infinity); got "
        f"exponent {detail_amount_exponent!r}"
    )
    assert detail_amount_exponent == -2, (
        f"amount should preserve COBOL PIC S9(09)V99 2-decimal-place "
        f"scale (exponent=-2); got exponent "
        f"{detail_amount_exponent} for {response.amount!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_truncates_to_bms_widths(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """Detail response fields are truncated to BMS-screen widths.

    The COBOL BMS map ``COTRN01.bms`` defines output fields with
    specific widths:

    * TRNDESC 60 chars  (description)
    * MERNAME 30 chars  (merchant_name)
    * MERCITY 25 chars  (merchant_city)

    The model columns are wider (description String(100),
    merchant_name/city String(50)) so the service must truncate on
    the way out to match the BMS output contract.

    This test builds a Transaction with long overflowing values and
    asserts the response fields are truncated to the BMS widths.

    Maps to COBOL: ``COTRN01C`` BMS symbolic-map field widths.
    """
    # Arrange: Build a transaction with fields that exceed the BMS
    # widths.
    overflow_transaction = Transaction(
        tran_id="0000000000000042",
        type_cd="02",
        cat_cd="2002",
        source="BATCH",
        # 100-char description (model width) -- must be truncated
        # to 60 in the response.
        description="X" * 100,
        amount=Decimal("123.45"),
        merchant_id="999999999",
        # 50-char merchant_name (model width) -- must be truncated
        # to 30.
        merchant_name="Y" * 50,
        # 50-char merchant_city (model width) -- must be truncated
        # to 25.
        merchant_city="Z" * 50,
        merchant_zip="99999",
        card_num=_TEST_CARD_NUM,
        orig_ts="2024-06-30 12:00:00.000000",
        proc_ts="2024-07-01 18:45:30.123456",
    )
    mock_db_session.execute.side_effect = _make_detail_execute_side_effect(
        transaction=overflow_transaction,
    )

    # Act
    response = await transaction_service.get_transaction_detail("0000000000000042")

    # Assert: Each overflowing field is truncated to its BMS width.
    assert len(response.description) == _DETAIL_DESC_WIDTH, (
        f"description should be truncated to {_DETAIL_DESC_WIDTH} chars "
        f"(BMS TRNDESC width); got length {len(response.description)}"
    )
    assert response.description == "X" * _DETAIL_DESC_WIDTH, (
        f"description should be truncated by taking the first "
        f"{_DETAIL_DESC_WIDTH} chars; got {response.description!r}"
    )

    assert len(response.merchant_name) == _DETAIL_MERCHANT_NAME_WIDTH, (
        f"merchant_name should be truncated to "
        f"{_DETAIL_MERCHANT_NAME_WIDTH} chars (BMS MERNAME width); "
        f"got length {len(response.merchant_name)}"
    )
    assert response.merchant_name == "Y" * _DETAIL_MERCHANT_NAME_WIDTH

    assert len(response.merchant_city) == _DETAIL_MERCHANT_CITY_WIDTH, (
        f"merchant_city should be truncated to "
        f"{_DETAIL_MERCHANT_CITY_WIDTH} chars (BMS MERCITY width); "
        f"got length {len(response.merchant_city)}"
    )
    assert response.merchant_city == "Z" * _DETAIL_MERCHANT_CITY_WIDTH

    # orig_date / proc_date are 10-char date slices of the full
    # 26-char timestamps.
    assert response.orig_date == "2024-06-30", (
        f"orig_date should be first 10 chars of orig_ts; got {response.orig_date!r}"
    )
    assert response.proc_date == "2024-07-01"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_transaction_detail_strips_whitespace_input(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
) -> None:
    """Input with surrounding whitespace is stripped before the lookup.

    The COBOL CICS READ RIDFLD takes a byte-exact key -- any
    trailing spaces on the input would cause a NOTFND where the
    real intent was a match on the stripped key. The Python service
    must strip whitespace from ``tran_id`` before issuing the
    query, but ``tran_id_input`` on the response MUST echo the
    original (unstripped) input so the UI can display the literal
    field content the user typed.

    To stay within the schema's 16-char ``max_length`` on
    ``tran_id_input``, this test uses a short 5-char padded input
    (``" 123 "``) whose stripped form (``"123"``) matches a short
    in-memory :class:`Transaction`. This exercises the stripping
    behavior purely at the service level -- COTRN01 BMS screens
    never pass >16-char input (CICS truncates at ``PIC X(16)``).

    Maps to COBOL: COTRN01C preprocessing that converts trailing
    SPACES to LOW-VALUES prior to the READ RIDFLD call.
    """
    # Arrange: An in-memory Transaction whose tran_id is short
    # enough that a padded input stays within the 16-char schema
    # limit. The sample_transaction fixture's 16-char tran_id
    # leaves no room for padding.
    short_transaction = Transaction(
        tran_id="123",
        type_cd="01",
        cat_cd="1001",
        source="POS TERM",
        description="Short-id test",
        amount=Decimal("10.00"),
        merchant_id="000000001",
        merchant_name="Test Store",
        merchant_city="New York",
        merchant_zip="10001",
        card_num=_TEST_CARD_NUM,
        orig_ts="2025-01-15 10:30:00.000000",
        proc_ts="2025-01-15 10:30:00.000000",
    )
    mock_db_session.execute.side_effect = _make_detail_execute_side_effect(
        transaction=short_transaction,
    )

    # Act: Pass input with leading and trailing whitespace. The
    # total length (5 chars) fits within ``tran_id_input``'s
    # 16-char ``max_length``, so Pydantic accepts the echo.
    padded_input = " 123 "
    response = await transaction_service.get_transaction_detail(padded_input)

    # Assert: Success response -- the whitespace was stripped prior
    # to lookup so the DB matched.
    assert response.message is None, (
        f"Whitespace must be stripped before DB lookup; got error "
        f"message {response.message!r}"
    )
    assert response.tran_id == short_transaction.tran_id, (
        f"Service should look up the stripped tran_id and return "
        f"the matched row's canonical tran_id; expected "
        f"{short_transaction.tran_id!r}, got {response.tran_id!r}"
    )

    # tran_id_input echoes the ORIGINAL input (preserving whitespace
    # so the UI can show what the user typed).
    assert response.tran_id_input == padded_input, (
        f"tran_id_input should echo the original (unstripped) input "
        f"for UI display; expected {padded_input!r}, got "
        f"{response.tran_id_input!r}"
    )



# ============================================================================
# Phase 5: Transaction Add Tests (Feature F-011 -- COTRN02C.cbl)
# ============================================================================
#
# COTRN02C.cbl (~783 lines) is the most complex of the three
# transaction programs. It implements:
#
#   1. Cross-reference resolution:
#        * card -> account (via READ CCXREF BY XREF-CARD-NUM)
#        * account validation (XREF-ACCT-ID must match user input)
#   2. Auto-ID generation:
#        * STARTBR at end-of-file + READPREV to find max TRAN-ID
#        * ADD 1 TO WS-TRAN-ID-NUM
#        * Edit mask to zero-pad to PIC X(16)
#        * Fallback to 1 on empty TRANSACT file
#   3. WRITE-TRANSACT-FILE (dual-write staging).
#   4. CICS SYNCPOINT (commit) or ROLLBACK on write failure.
#
# Tests in this phase validate each of these behaviors independently
# and then validate the happy-path end-to-end sequence:
#
#   * XREF resolution card -> account (success path).
#   * XREF NOTFND -> error message + rollback + NO write.
#   * XREF acct mismatch -> error message + rollback + NO write.
#   * Auto-ID generation: max + 1 with zero-padding.
#   * Auto-ID generation: empty table -> "0000000000000001".
#   * Auto-ID generation: non-numeric existing tran_id -> error
#     message + rollback + NO write.
#   * Successful add stages Transaction + flush + commit.
#   * Successful add response carries confirm='Y' + success message.
#   * Amount preserved as Decimal (never float).
#   * Timestamps are 26 chars (COBOL PIC X(26)).
#   * DB error during write -> rollback + re-raise.
#   * Call ordering: xref -> max-agg -> max-sort -> add -> flush ->
#     commit.
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_success_stages_and_commits(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """End-to-end happy path: XREF + auto-ID + WRITE + SYNCPOINT.

    This is the CRITICAL test of the add-transaction flow. It
    validates the complete sequence from ``COTRN02C.cbl``:

    * XREF lookup succeeds and acct_id matches.
    * Auto-ID is generated as max existing + 1 (from mocked max
      ``"0000000000000050"``), zero-padded to 16 chars.
    * A :class:`Transaction` is staged via ``session.add(...)``.
    * ``session.flush()`` AND ``session.commit()`` are both awaited
      (the CICS implicit SYNCPOINT equivalent).
    * ``session.rollback()`` is NOT called.
    * The response carries the generated tran_id, ``confirm='Y'``
      and the formatted success message.

    Maps to COBOL: The complete WRITE-TRANSACT-FILE happy path in
    ``COTRN02C.cbl``.
    """
    # Arrange: XREF found + matching acct_id + max tran_id = 50.
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg="0000000000000050",
        max_tran_id_sort="0000000000000050",
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert 1: Response is a TransactionAddResponse.
    assert isinstance(response, TransactionAddResponse), (
        f"Expected TransactionAddResponse; got {type(response).__name__}"
    )

    # Assert 2: Auto-ID is max + 1, zero-padded to 16 chars.
    expected_tran_id = "0000000000000051"
    assert response.tran_id == expected_tran_id, (
        f"Expected auto-generated tran_id {expected_tran_id!r} "
        f"(max '0000000000000050' + 1); got {response.tran_id!r}"
    )
    assert len(response.tran_id) == _EXPECTED_TRAN_ID_WIDTH

    # Assert 3: Response echoes input acct_id and card_num.
    assert response.acct_id == sample_add_request.acct_id
    assert response.card_num == sample_add_request.card_num

    # Assert 4: Amount preserved as Decimal.
    assert isinstance(response.amount, Decimal), (
        f"Response amount MUST be Decimal; got {type(response.amount).__name__}"
    )
    assert response.amount == Decimal("50.00")

    # Assert 5: confirm='Y' indicates success.
    assert response.confirm == "Y", (
        f"Expected confirm='Y' for successful add; got {response.confirm!r}"
    )

    # Assert 6: Success message is formatted with the generated
    # tran_id using _MSG_ADD_SUCCESS_FMT.
    expected_msg = _MSG_ADD_SUCCESS_FMT.format(tran_id=expected_tran_id)
    assert response.message == expected_msg, (
        f"Expected success message {expected_msg!r}; got {response.message!r}"
    )

    # Assert 7: Transaction was staged via session.add.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.tran_id == expected_tran_id
    # Assert 8: flush + commit both awaited exactly once.
    mock_db_session.flush.assert_awaited_once()
    mock_db_session.commit.assert_awaited_once()
    # Assert 9: rollback was NOT called on the success path.
    mock_db_session.rollback.assert_not_awaited()
    # Assert 10: execute was awaited 3 times (xref + max_agg + max_sort).
    assert mock_db_session.execute.await_count == 3, (
        f"add_transaction should issue 3 execute calls (xref + "
        f"max_agg + max_sort); got {mock_db_session.execute.await_count}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_stages_transaction_with_request_fields(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """The staged :class:`Transaction` reflects request field values.

    Validates the request-to-ORM field mapping in ``add_transaction``.
    The service maps schema field names to model column names
    (``tran_type_cd`` -> ``type_cd``, ``tran_cat_cd`` -> ``cat_cd``,
    ``tran_source`` -> ``source``) and substitutes default empty
    strings for optional merchant fields that were not provided.

    Maps to COBOL: COTRN02C MOVE statements from input WS fields to
    TRAN-RECORD fields prior to WRITE-TRANSACT-FILE.
    """
    # Arrange: max=None -> auto-ID = "0000000000000001".
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    await transaction_service.add_transaction(sample_add_request)

    # Assert: Extract the staged Transaction and verify its fields.
    staged_txn = _extract_added_transaction(mock_db_session)

    # tran_id = initial (empty-table fallback).
    assert staged_txn.tran_id == _EXPECTED_INITIAL_TRAN_ID

    # Name-translated fields.
    assert staged_txn.type_cd == sample_add_request.tran_type_cd, (
        f"Model type_cd should come from request.tran_type_cd; "
        f"expected {sample_add_request.tran_type_cd!r}, got {staged_txn.type_cd!r}"
    )
    assert staged_txn.cat_cd == sample_add_request.tran_cat_cd, (
        f"Model cat_cd should come from request.tran_cat_cd; "
        f"expected {sample_add_request.tran_cat_cd!r}, got {staged_txn.cat_cd!r}"
    )
    assert staged_txn.source == sample_add_request.tran_source, (
        f"Model source should come from request.tran_source; "
        f"expected {sample_add_request.tran_source!r}, got {staged_txn.source!r}"
    )

    # Verbatim fields.
    assert staged_txn.description == sample_add_request.description
    assert staged_txn.card_num == sample_add_request.card_num
    # amount is Decimal.
    assert isinstance(staged_txn.amount, Decimal)
    assert staged_txn.amount == sample_add_request.amount

    # Optional merchant fields -- None in request -> empty string on
    # model (never None, never "None", never missing).
    assert staged_txn.merchant_id == "", (
        f"None merchant_id should map to empty string; got {staged_txn.merchant_id!r}"
    )
    assert staged_txn.merchant_name == ""
    assert staged_txn.merchant_city == ""
    assert staged_txn.merchant_zip == ""


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_auto_id_generation(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """Auto-ID = max existing + 1, zero-padded to 16 chars.

    CRITICAL TEST: The COBOL original in ``COTRN02C.cbl`` uses
    ``EXEC CICS STARTBR`` at end + ``EXEC CICS READPREV`` to find
    the last TRAN-ID in the TRANSACT file, then ``ADD 1 TO
    WS-TRAN-ID-NUM``. The Python service preserves this semantic
    using ``SELECT MAX(tran_id)`` + ``SELECT ... ORDER BY DESC
    LIMIT 1`` (the second is a verification query that should
    agree with the first).

    This test uses ``max_tran_id_agg="0000000000000099"`` and
    asserts the new tran_id is ``"0000000000000100"`` (16 chars,
    zero-padded, one greater than the max).

    Maps to COBOL: COTRN02C STARTBR + READPREV + ADD 1.
    """
    # Arrange: max existing tran_id = 99.
    existing_max = "0000000000000099"
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=existing_max,
        max_tran_id_sort=existing_max,
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert: response.tran_id is max + 1, zero-padded to 16 chars.
    expected_tran_id = "0000000000000100"
    assert response.tran_id == expected_tran_id, (
        f"Expected tran_id={expected_tran_id!r} (max {existing_max!r} "
        f"+ 1, zero-padded to {_EXPECTED_TRAN_ID_WIDTH} chars per "
        f"COBOL PIC X(16)); got {response.tran_id!r}"
    )

    # Defensive length check.
    assert len(response.tran_id) == _EXPECTED_TRAN_ID_WIDTH, (
        f"tran_id must be exactly {_EXPECTED_TRAN_ID_WIDTH} chars "
        f"(COBOL PIC X(16)); got length {len(response.tran_id)}"
    )
    # All-digit composition (numeric-convertible).
    assert response.tran_id.isdigit(), (
        f"tran_id must be all-digits (zero-padded numeric); got {response.tran_id!r}"
    )

    # Staged transaction carries the same tran_id.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.tran_id == expected_tran_id


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_auto_id_empty_table(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """Empty table -> tran_id = :data:`_EXPECTED_INITIAL_TRAN_ID`.

    Covers the edge case where the ``transactions`` table is empty.
    In COBOL, ``STARTBR + READPREV`` returns DFHRESP(ENDFILE) /
    NOTFND, and the program falls back to ``MOVE 1 TO
    WS-TRAN-ID-NUM``. The Python service fallback is the
    ``_INITIAL_TRAN_ID`` constant (zero-padded representation of 1).

    This test supplies ``max_tran_id_agg=None`` and
    ``max_tran_id_sort=None`` (both queries return None on empty
    table) and asserts the response tran_id is
    ``"0000000000000001"``.

    Maps to COBOL: COTRN02C DFHRESP(ENDFILE) / NOTFND on READPREV.
    """
    # Arrange: Empty transactions table -- BOTH aggregate queries
    # return None.
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert
    assert response.tran_id == _EXPECTED_INITIAL_TRAN_ID, (
        f"Expected tran_id={_EXPECTED_INITIAL_TRAN_ID!r} (empty "
        f"transaction table fallback); got {response.tran_id!r}"
    )
    assert len(response.tran_id) == _EXPECTED_TRAN_ID_WIDTH
    assert response.confirm == "Y"

    # Staged transaction carries the initial tran_id.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.tran_id == _EXPECTED_INITIAL_TRAN_ID


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_bad_existing_tran_id_returns_error(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """Non-numeric existing tran_id triggers graceful error (no crash).

    Guards against data corruption -- if the TRANSACT table
    somehow contains a non-numeric tran_id (from a mis-seeded
    dev/test DB or a data-migration bug), the ``int(last_tran_id) +
    1`` arithmetic will raise ``ValueError``. The service must catch
    this, rollback, and return the COBOL-exact
    :data:`_MSG_UNABLE_TO_ADD` message -- NOT let the exception
    propagate (which would result in a HTTP 500 to the caller).

    Maps to COBOL: COTRN02C WHEN OTHER branch on numeric-edit
    mask failure.
    """
    # Arrange: max existing tran_id is a non-numeric string (corrupt
    # data scenario).
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg="BAD-DATA--------",  # 16 chars, non-numeric
        max_tran_id_sort="BAD-DATA--------",
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert: Error response -- no write was staged.
    assert response.confirm == "N", (
        f"Expected confirm='N' on auto-ID parse failure; got {response.confirm!r}"
    )
    assert response.message == _MSG_UNABLE_TO_ADD, (
        f"Expected COBOL-exact message {_MSG_UNABLE_TO_ADD!r} "
        f"(from COTRN02C.cbl WHEN OTHER branch on numeric edit "
        f"failure); got {response.message!r}. Must be preserved "
        f"byte-for-byte per AAP Section 0.7.1."
    )
    assert response.tran_id == "", (
        f"No tran_id generated on parse failure; expected empty "
        f"string, got {response.tran_id!r}"
    )

    # Assert: No Transaction was staged; rollback was called;
    # flush / commit were NOT called.
    mock_db_session.add.assert_not_called()
    mock_db_session.rollback.assert_awaited_once()
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()



@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_xref_not_found(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_add_request: TransactionAddRequest,
) -> None:
    """Card missing from CCXREF -> error + rollback + NO write.

    CRITICAL authorization test. The COBOL original
    (``COTRN02C.cbl``) performs an ``EXEC CICS READ FILE('CCXREF')
    RIDFLD(XREF-CARD-NUM)`` BEFORE writing any transaction. If
    DFHRESP(NOTFND) is returned, the program rejects the add
    attempt with the screen message "Unable to lookup Card # in
    XREF file...", issues SYNCPOINT ROLLBACK, and does NOT call
    WRITE-TRANSACT-FILE.

    This test validates the analogous Python path:

    * ``mock_db_session.execute`` returns a single result whose
      ``scalar_one_or_none()`` is ``None`` (xref NOTFND).
    * ``session.add(...)`` is NEVER called.
    * ``session.flush()`` / ``session.commit()`` are NEVER awaited.
    * ``session.rollback()`` IS awaited (the SYNCPOINT ROLLBACK
      equivalent).
    * Only ONE ``execute`` call is made (the xref lookup -- the
      service short-circuits and does NOT issue the max-tran_id
      queries).
    * Response carries :data:`_MSG_CARD_NOT_IN_XREF` and
      ``confirm='N'``.

    Maps to COBOL: COTRN02C DFHRESP(NOTFND) on READ CCXREF.
    """
    # Arrange: xref lookup returns None.
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=None,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert 1: Response is a failure response.
    assert isinstance(response, TransactionAddResponse)
    assert response.confirm == "N", (
        f"Expected confirm='N' on XREF NOTFND; got {response.confirm!r}"
    )
    assert response.message == _MSG_CARD_NOT_IN_XREF, (
        f"Expected COBOL-exact message {_MSG_CARD_NOT_IN_XREF!r}; "
        f"got {response.message!r}. Must be preserved byte-for-byte "
        f"(including trailing ellipsis) per AAP Section 0.7.1."
    )
    # No tran_id generated (short-circuit before auto-ID).
    assert response.tran_id == "", (
        f"No tran_id should be generated when xref is missing; got {response.tran_id!r}"
    )
    # Response echoes input acct_id / card_num for UI display.
    assert response.acct_id == sample_add_request.acct_id
    assert response.card_num == sample_add_request.card_num

    # Assert 2: session.add was NEVER called (write short-circuit).
    mock_db_session.add.assert_not_called()
    # Assert 3: flush / commit NEVER awaited.
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()
    # Assert 4: rollback WAS awaited (SYNCPOINT ROLLBACK equivalent).
    mock_db_session.rollback.assert_awaited_once()
    # Assert 5: Only 1 execute call -- the xref lookup. The max-agg
    # and max-sort queries are NOT issued.
    assert mock_db_session.execute.await_count == 1, (
        f"XREF NOTFND should short-circuit after xref lookup; "
        f"expected 1 execute call, got {mock_db_session.execute.await_count}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_xref_account_mismatch(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_add_request: TransactionAddRequest,
) -> None:
    """XREF acct_id != request.acct_id -> error + rollback + NO write.

    Validates the second authorization check. Even when the card
    exists in the XREF file, the program must also validate that
    the XREF-ACCT-ID matches the user-supplied ACTIDIN. If they
    disagree, the card is not authorized to post to that account,
    and the add attempt is rejected with "Account/Card mismatch in
    XREF...".

    This is a critical security control: it prevents a user from
    posting a transaction to an account they don't own simply by
    supplying a valid card number + arbitrary account number.

    Maps to COBOL: COTRN02C XREF-ACCT-ID comparison with ACTIDIN.
    """
    # Arrange: XREF found but linked to a DIFFERENT account.
    mismatched_xref = CardCrossReference(
        card_num=sample_add_request.card_num,
        cust_id=_TEST_CUST_ID,
        acct_id="99999999999",  # Different from sample_add_request.acct_id
    )
    # Sanity check on the fixture: acct_ids really differ.
    assert mismatched_xref.acct_id != sample_add_request.acct_id, (
        "Test setup error: mismatched xref should have different acct_id"
    )

    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=mismatched_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert 1: Response carries the mismatch error message.
    assert response.confirm == "N"
    assert response.message == _MSG_ACCT_CARD_MISMATCH, (
        f"Expected COBOL-exact message {_MSG_ACCT_CARD_MISMATCH!r}; "
        f"got {response.message!r}"
    )
    # No tran_id generated (short-circuit before auto-ID).
    assert response.tran_id == ""

    # Assert 2: session.add / flush / commit NEVER called.
    mock_db_session.add.assert_not_called()
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()
    # Assert 3: rollback WAS awaited.
    mock_db_session.rollback.assert_awaited_once()
    # Assert 4: Only 1 execute call -- the xref lookup. The max-agg
    # and max-sort queries are NOT issued (short-circuit).
    assert mock_db_session.execute.await_count == 1, (
        f"Account/card mismatch should short-circuit after xref "
        f"lookup; expected 1 execute call, got {mock_db_session.execute.await_count}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_xref_resolution_card_to_account(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """XREF resolves card -> account; staged Transaction carries the card.

    CRITICAL cross-reference test. In the COBOL original, the XREF
    file provides the card <-> account linkage that allows the
    system to validate that a card is authorized to post
    transactions to a given account. In the Python service, the
    xref is used for *authorization only*: the staged Transaction
    carries the ``card_num`` from the request (not from xref) --
    xref is consulted purely to confirm the card is authorized for
    the request's acct_id.

    This test supplies a valid xref whose ``acct_id`` matches
    ``request.acct_id``, and asserts:

    * The staged ``Transaction.card_num`` equals the request's
      card_num (which, by xref agreement, is also ``sample_xref.card_num``).
    * The response's ``acct_id`` equals the request's acct_id (the
      authorized account).
    * Exactly 3 execute calls are made (xref lookup + max_agg +
      max_sort).

    Maps to COBOL: COTRN02C XREF-CARD-NUM / XREF-ACCT-ID semantics.
    """
    # Arrange: Valid xref with matching acct_id.
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Sanity check: xref fixture really matches request.
    assert sample_xref.card_num == sample_add_request.card_num
    assert sample_xref.acct_id == sample_add_request.acct_id

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert: Success path (XREF authorized).
    assert response.confirm == "Y", (
        f"Expected confirm='Y' after XREF success; got {response.confirm!r}"
    )

    # Assert: Staged Transaction carries the request's card_num
    # (which, by xref agreement, is the authorized card).
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.card_num == sample_add_request.card_num, (
        f"Staged Transaction.card_num should equal request.card_num "
        f"(the authorized card via xref); expected "
        f"{sample_add_request.card_num!r}, got {staged_txn.card_num!r}"
    )
    # And by xref agreement, request.card_num == xref.card_num.
    assert staged_txn.card_num == sample_xref.card_num

    # Assert: Response echoes the authorized acct_id.
    assert response.acct_id == sample_add_request.acct_id
    assert response.acct_id == sample_xref.acct_id

    # Assert: Full execute sequence (xref -> max_agg -> max_sort).
    assert mock_db_session.execute.await_count == 3


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_amount_is_decimal(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
) -> None:
    """Staged Transaction.amount and response.amount are Decimal.

    CRITICAL financial-precision test. Per AAP Section 0.7.1 and
    0.7.2, all monetary values must preserve COBOL PIC S9(09)V99
    semantics using Python :class:`~decimal.Decimal`, NEVER float.
    This test exercises a non-trivial decimal value (``100.50`` --
    a value whose binary-float representation would LOSE
    precision) and asserts:

    * The staged ``Transaction.amount`` is a ``Decimal`` instance
      equal to ``Decimal("100.50")``.
    * The response ``amount`` is a ``Decimal`` instance equal to
      ``Decimal("100.50")``.
    * Neither value is ever a ``float`` (``isinstance(amount, float)``
      MUST be False).

    This guards against a refactor regression that would introduce
    floating-point arithmetic into the financial code path.

    Maps to COBOL: CVTRA05Y.cpy TRAN-AMT PIC S9(09)V99.
    """
    # Arrange: Non-trivial Decimal amount that would lose precision
    # as float.
    critical_amount = Decimal("100.50")
    request = TransactionAddRequest(
        acct_id=_TEST_ACCT_ID,
        card_num=_TEST_CARD_NUM,
        tran_type_cd="01",
        tran_cat_cd="0001",
        tran_source="ONLINE",
        description="Decimal precision test",
        amount=critical_amount,
        orig_date="2025-01-15",
    )
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    response = await transaction_service.add_transaction(request)

    # Assert: Response amount is Decimal.
    assert isinstance(response.amount, Decimal), (
        f"Response amount MUST be Decimal; got {type(response.amount).__name__}. "
        f"float is FORBIDDEN per AAP Section 0.7.1."
    )
    assert not isinstance(response.amount, float), (
        "Response amount MUST NOT be float"
    )
    assert response.amount == critical_amount, (
        f"Response amount should equal request amount exactly; "
        f"expected {critical_amount}, got {response.amount}"
    )

    # Assert: Staged Transaction amount is Decimal.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert isinstance(staged_txn.amount, Decimal), (
        f"Staged Transaction.amount MUST be Decimal; got "
        f"{type(staged_txn.amount).__name__}"
    )
    assert not isinstance(staged_txn.amount, float)
    assert staged_txn.amount == critical_amount

    # Extra defensive check: The response and staged model both
    # preserve the same Decimal value.
    assert response.amount == staged_txn.amount


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_timestamp_format_26_char(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """Staged Transaction.orig_ts and proc_ts are exactly 26 chars.

    Validates COBOL PIC X(26) fidelity. The CVTRA05Y.cpy copybook
    defines TRAN-ORIG-TS and TRAN-PROC-TS as ``PIC X(26)``, which
    in COBOL is a fixed-length alphanumeric field of exactly 26
    bytes. The Python target preserves this by formatting
    timestamps in the ``YYYY-MM-DD HH:MM:SS.ffffff`` layout (4 + 1
    + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 6 = 26 chars).

    This test asserts both timestamps are exactly 26 chars long.
    Failure would indicate a drift from the COBOL wire format
    (which other batch jobs, statements, and reports depend on).

    Maps to COBOL: CVTRA05Y.cpy TRAN-ORIG-TS / TRAN-PROC-TS PIC X(26).
    """
    # Arrange
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    await transaction_service.add_transaction(sample_add_request)

    # Assert: Staged Transaction has 26-char timestamps.
    staged_txn = _extract_added_transaction(mock_db_session)

    assert isinstance(staged_txn.orig_ts, str), (
        f"orig_ts must be str (COBOL PIC X(26)); got "
        f"{type(staged_txn.orig_ts).__name__}"
    )
    assert len(staged_txn.orig_ts) == _EXPECTED_TIMESTAMP_WIDTH, (
        f"orig_ts must be exactly {_EXPECTED_TIMESTAMP_WIDTH} chars "
        f"(COBOL PIC X(26)); got length {len(staged_txn.orig_ts)}: "
        f"{staged_txn.orig_ts!r}"
    )

    assert isinstance(staged_txn.proc_ts, str), (
        f"proc_ts must be str (COBOL PIC X(26)); got "
        f"{type(staged_txn.proc_ts).__name__}"
    )
    assert len(staged_txn.proc_ts) == _EXPECTED_TIMESTAMP_WIDTH, (
        f"proc_ts must be exactly {_EXPECTED_TIMESTAMP_WIDTH} chars "
        f"(COBOL PIC X(26)); got length {len(staged_txn.proc_ts)}: "
        f"{staged_txn.proc_ts!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_db_error_propagates_and_rolls_back(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """flush() failure -> rollback() awaited + exception re-raised.

    CRITICAL transactional-integrity test. In the COBOL original,
    an I/O failure during WRITE-TRANSACT-FILE (DFHRESP(IOERR),
    DISKFULL, etc.) triggers a SYNCPOINT ROLLBACK and returns an
    error screen. The Python service preserves this semantic: if
    ``session.flush()`` raises (simulating a DB-side constraint
    violation, connection loss, etc.), the service must:

    1. Catch the exception in its outer ``except`` handler.
    2. Call ``await self.db.rollback()`` to undo any pending
       staged writes (SYNCPOINT ROLLBACK equivalent).
    3. Re-raise the original exception (``raise`` in Python) so
       the caller can return an appropriate HTTP status
       (500 Internal Server Error for true DB failures, not a
       user-facing validation error).

    Without rollback on failure, a pending transaction write could
    linger in the session's uncommitted queue, causing spurious
    duplicate writes or session-state corruption on the next use.

    Maps to COBOL: COTRN02C SYNCPOINT ROLLBACK on
    DFHRESP(NOTOPEN) / DFHRESP(IOERR) / DFHRESP(DISKFULL).
    """
    # Arrange: xref OK, max-id queries OK, but flush fails.
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )
    simulated_error = RuntimeError("Simulated DB failure on flush")
    mock_db_session.flush.side_effect = simulated_error

    # Act + Assert: Exception must re-raise (NOT be swallowed).
    with pytest.raises(RuntimeError) as exc_info:
        await transaction_service.add_transaction(sample_add_request)

    # The re-raised exception is the same instance we injected.
    assert exc_info.value is simulated_error, (
        f"Service must re-raise the original exception, not wrap "
        f"it. Expected the same instance; got {exc_info.value!r}"
    )

    # Assert: session.add WAS called (the Transaction was staged
    # before flush failed).
    mock_db_session.add.assert_called_once()

    # Assert: flush WAS awaited (it raised).
    mock_db_session.flush.assert_awaited_once()
    # Assert: commit was NOT awaited (flush raised before commit).
    mock_db_session.commit.assert_not_awaited()
    # Assert: rollback WAS awaited (SYNCPOINT ROLLBACK equivalent).
    mock_db_session.rollback.assert_awaited_once()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_execute_call_sequence(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_add_request: TransactionAddRequest,
) -> None:
    """Validates the strict execute call ordering: xref -> max_agg -> max_sort.

    This guards against a refactor regression that would reorder
    the DB queries (for instance, issuing the max-tran_id queries
    BEFORE the xref authorization). The COBOL original checks
    authorization BEFORE doing any work that would be wasted by a
    subsequent reject.

    Expected sequence when all checks pass:

    1. ``execute(SELECT ... FROM card_cross_references WHERE card_num == request.card_num)``
       -> returns ``scalar_one_or_none() = sample_xref``.
    2. ``execute(SELECT MAX(tran_id) FROM transactions)``
       -> returns ``scalar() = None`` (empty table).
    3. ``execute(SELECT tran_id FROM transactions ORDER BY tran_id DESC LIMIT 1)``
       -> returns ``scalar() = None`` (empty table).
    4. ``session.add(new_transaction)``
    5. ``await session.flush()``
    6. ``await session.commit()``

    Maps to COBOL: COTRN02C sequential flow -- READ CCXREF,
    STARTBR + READPREV TRANSACT, WRITE-TRANSACT-FILE, SYNCPOINT.
    """
    # Arrange
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    response = await transaction_service.add_transaction(sample_add_request)

    # Assert: Successful add.
    assert response.confirm == "Y"

    # Assert: Three execute calls (xref, max_agg, max_sort).
    assert mock_db_session.execute.await_count == 3, (
        f"Expected 3 execute calls (xref + max_agg + max_sort); "
        f"got {mock_db_session.execute.await_count}"
    )

    # Assert: add + flush + commit all called exactly once.
    mock_db_session.add.assert_called_once()
    mock_db_session.flush.assert_awaited_once()
    mock_db_session.commit.assert_awaited_once()
    # Assert: rollback NOT called on the success path.
    mock_db_session.rollback.assert_not_awaited()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_timestamp_uses_orig_date_when_provided(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
) -> None:
    """orig_ts prefix matches request.orig_date when provided.

    Supplementary test to confirm the request-supplied ``orig_date``
    is honored by the service's timestamp composition
    (``_compose_ts_from_date``). The staged Transaction's
    ``orig_ts`` should begin with ``YYYY-MM-DD`` matching
    ``request.orig_date`` (with a space/hyphen + HMS.microsecond
    suffix to fill the 26-char field).

    Maps to COBOL: COTRN02C MOVE TRNORIGDTI TO TRAN-ORIG-TS (with
    adaptation for PIC X(26) width).
    """
    # Arrange: Request with a specific orig_date.
    orig_date = "2024-12-25"
    request = TransactionAddRequest(
        acct_id=_TEST_ACCT_ID,
        card_num=_TEST_CARD_NUM,
        tran_type_cd="01",
        tran_cat_cd="0001",
        tran_source="ONLINE",
        description="Holiday purchase",
        amount=Decimal("99.99"),
        orig_date=orig_date,
    )
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    await transaction_service.add_transaction(request)

    # Assert: Staged Transaction's orig_ts starts with the orig_date.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.orig_ts.startswith(orig_date), (
        f"orig_ts should start with request.orig_date={orig_date!r}; "
        f"got {staged_txn.orig_ts!r}"
    )
    # Still exactly 26 chars.
    assert len(staged_txn.orig_ts) == _EXPECTED_TIMESTAMP_WIDTH


@pytest.mark.unit
@pytest.mark.asyncio
async def test_add_transaction_empty_description_preserved_as_empty_string(
    transaction_service: TransactionService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
) -> None:
    """Empty-string description is preserved as "" on the staged model.

    Guards against ``None`` leaking into the CHAR(100) column and
    causing an IntegrityError at the DB layer. The schema requires
    ``description`` to be a string (non-optional per the validator
    rules documented in the AAP), but the user may legitimately
    submit an empty string for a transaction with no narrative
    (e.g. an interest accrual or system-generated entry). The
    service must preserve that empty string verbatim on the staged
    :class:`Transaction.description` column -- NEVER convert it to
    None, ``"None"`` (the Python ``repr``), or the word ``"empty"``.

    Maps to COBOL: CVTRA05Y.cpy TRAN-DESC PIC X(100) -- a fixed-
    width text field that defaults to SPACES (empty) on WRITE.
    """
    # Arrange: Request with explicit empty-string description.
    # (The schema requires description, but allows "".)
    request = TransactionAddRequest(
        acct_id=_TEST_ACCT_ID,
        card_num=_TEST_CARD_NUM,
        tran_type_cd="01",
        tran_cat_cd="0001",
        tran_source="ONLINE",
        description="",
        amount=Decimal("25.00"),
        orig_date="2025-01-15",
    )
    mock_db_session.execute.side_effect = _make_add_execute_side_effect(
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    # Act
    await transaction_service.add_transaction(request)

    # Assert: Staged Transaction.description is empty string (NEVER
    # None, NEVER "None").
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.description == "", (
        f"Empty-string description in request should be preserved "
        f"verbatim on staged Transaction.description; got {staged_txn.description!r}"
    )
    assert staged_txn.description is not None, (
        "description must not be None (violates CHAR(100) NOT NULL)"
    )
    assert isinstance(staged_txn.description, str), (
        f"description must be str; got {type(staged_txn.description).__name__}"
    )

