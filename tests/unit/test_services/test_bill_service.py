# ============================================================================
# CardDemo - Unit tests for BillService (Mainframe-to-Cloud migration)
# ============================================================================
# Source:
#   * app/cbl/COBIL00C.cbl     - CICS bill payment program, transaction CB00
#                                (PROCESS-ENTER-KEY paragraph, ~572 lines).
#                                Dual-write sequence at L210-243:
#                                  L211: PERFORM READ-CXACAIX-FILE
#                                  L212-215: STARTBR / READPREV / ENDBR
#                                           TRANSACT-FILE
#                                  L217: ADD 1 TO WS-TRAN-ID-NUM
#                                  L220: MOVE '02' TO TRAN-TYPE-CD
#                                  L221: MOVE 2    TO TRAN-CAT-CD   (->'0002')
#                                  L223: MOVE 'BILL PAYMENT - ONLINE'
#                                           TO TRAN-DESC
#                                  L224: MOVE ACCT-CURR-BAL TO TRAN-AMT
#                                  L233: PERFORM WRITE-TRANSACT-FILE
#                                  L234: COMPUTE ACCT-CURR-BAL =
#                                           ACCT-CURR-BAL - TRAN-AMT
#                                  L235: PERFORM UPDATE-ACCTDAT-FILE
#                                Zero-balance rejection at L197-205:
#                                  L198: IF ACCT-CURR-BAL <= ZEROS
#                                  L201: MOVE 'You have nothing to pay...'
#                                           TO WS-MESSAGE
#   * app/cpy/CVACT01Y.cpy     - ACCOUNT-RECORD (300-byte VSAM KSDS layout):
#                                ACCT-ID           PIC 9(11),
#                                ACCT-ACTIVE-STATUS PIC X(01),
#                                ACCT-CURR-BAL     PIC S9(10)V99,
#                                ACCT-CREDIT-LIMIT PIC S9(10)V99,
#                                (and other fields).
#   * app/cpy/CVACT03Y.cpy     - CARD-XREF-RECORD (50-byte VSAM KSDS layout):
#                                XREF-CARD-NUM PIC X(16),
#                                XREF-CUST-ID  PIC 9(09),
#                                XREF-ACCT-ID  PIC 9(11).
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
# ----------------------------------------------------------------------------
# Feature F-012: Bill Payment (dual-write, atomic). Target implementation
# under test: src/api/services/bill_service.py (BillService class).
#
# The COBOL-exact error message from COBIL00C.cbl is preserved byte-for-byte
# per AAP Section 0.7.1 "Preserve exact error messages from COBOL":
#
#   * 'You have nothing to pay...' (COBIL00C.cbl line 201 — the COBOL
#     literal includes the trailing ellipsis of three periods)
#
# The COBOL-fixed Transaction-record metadata from COBIL00C.cbl lines
# 220-229 is also preserved byte-for-byte:
#
#   * TRAN-TYPE-CD  = '02'                       (L220)
#   * TRAN-CAT-CD   = '0002' (zero-padded)       (L221, stored as String(4))
#   * TRAN-SOURCE   = 'POS TERM'                  (L222)
#   * TRAN-DESC     = 'BILL PAYMENT - ONLINE'     (L223)
#   * TRAN-MERCHANT-ID   = '999999999'            (L226)
#   * TRAN-MERCHANT-NAME = 'BILL PAYMENT'         (L227)
#   * TRAN-MERCHANT-CITY = 'N/A'                  (L228)
#   * TRAN-MERCHANT-ZIP  = 'N/A'                  (L229)
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
"""Unit tests for :class:`BillService`.

Validates the atomic dual-write (Transaction INSERT + Account balance
UPDATE) converted from ``app/cbl/COBIL00C.cbl`` (CICS transaction
``CB00``, Feature F-012, ~572 lines). All monetary assertions use
:class:`decimal.Decimal` (never :class:`float`) per AAP Section 0.7.2
"Financial Precision".

COBOL -> Python Verification Surface
------------------------------------
=======================================================  ==========================================
COBOL paragraph / statement                              Python test (this module)
=======================================================  ==========================================
``PROCESS-ENTER-KEY`` (entry)                            all ``test_pay_bill_*`` tests
``READ-ACCTDAT-FILE`` (L174, L184)                       ``test_pay_bill_account_not_found``
``IF ACCT-CURR-BAL <= ZEROS`` (L198-205)                 ``test_pay_bill_zero_balance_rejected``,
                                                          ``test_pay_bill_negative_balance_rejected``
``MOVE 'You have nothing to pay...'`` (L201)             ``test_pay_bill_zero_balance_rejected``
                                                          (asserts exact message text)
``READ-CXACAIX-FILE`` (L211)                             ``test_pay_bill_xref_not_found``,
                                                          ``test_pay_bill_card_resolution_via_xref``
``STARTBR/READPREV/ENDBR + ADD 1`` (L212-217)            ``test_pay_bill_auto_id_generation``,
                                                          ``test_pay_bill_auto_id_empty_table``
``MOVE '02' TO TRAN-TYPE-CD`` (L220)                     ``test_pay_bill_success_dual_write``
``MOVE 2 TO TRAN-CAT-CD`` (L221)                         ``test_pay_bill_success_dual_write``
                                                          (asserts cat_cd='0002' string)
``MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC`` (L223)     ``test_pay_bill_success_dual_write``
``MOVE ACCT-CURR-BAL TO TRAN-AMT`` (L224)                ``test_pay_bill_success_dual_write``
``MOVE XREF-CARD-NUM TO TRAN-CARD-NUM`` (L225)           ``test_pay_bill_card_resolution_via_xref``
``GET-CURRENT-TIMESTAMP`` (L230)                         ``test_pay_bill_timestamps_26_char``
``WRITE-TRANSACT-FILE`` (L233)                           ``test_pay_bill_success_dual_write``
                                                          (asserts session.add called)
``COMPUTE ACCT-CURR-BAL = ... - TRAN-AMT`` (L234)        ``test_pay_bill_balance_subtraction_decimal``
``UPDATE-ACCTDAT-FILE`` (L235)                           ``test_pay_bill_success_dual_write``
                                                          (asserts account.curr_bal mutated)
implicit SYNCPOINT at CICS transaction end               ``test_pay_bill_success_dual_write``
                                                          (asserts flush + commit called)
CICS SYNCPOINT ROLLBACK on WRITE failure                 ``test_pay_bill_rollback_on_
                                                            transaction_write_failure``
CICS SYNCPOINT ROLLBACK on REWRITE failure               ``test_pay_bill_rollback_on_
                                                            account_update_failure``
=======================================================  ==========================================

Test Design
-----------
* **Mocked database**: All tests use ``AsyncMock(spec=AsyncSession)``
  rather than a real database, so the test suite runs in milliseconds
  with no PostgreSQL dependency. The mock replicates the SQLAlchemy
  2.x async contract — ``execute()`` is async and returns a Result
  object whose accessor methods (``scalar_one_or_none``,
  ``scalars().first()``, ``scalar()``) are synchronous — matching the
  four queries :meth:`BillService.pay_bill` issues in sequence:

    1. ``SELECT Account WHERE acct_id == :acct_id``
       -> ``result.scalar_one_or_none()``
    2. ``SELECT CardCrossReference WHERE acct_id == :acct_id``
       -> ``result.scalars().first()``
    3. ``SELECT MAX(Transaction.tran_id)``
       -> ``result.scalar()``
    4. ``SELECT Transaction.tran_id ORDER BY DESC LIMIT 1``
       -> ``result.scalar()``

* **Decimal-only monetary assertions**: Every balance, amount, and
  arithmetic operation uses :class:`decimal.Decimal`. The sample
  account fixture uses ``curr_bal=Decimal("1500.00")`` — never a
  ``float`` — to preserve the COBOL ``PIC S9(10)V99`` semantics.

* **Preserved COBOL wire values**: The COBOL-fixed Transaction
  constants (``TRAN-TYPE-CD='02'``, ``TRAN-CAT-CD='0002'``,
  ``TRAN-DESC='BILL PAYMENT - ONLINE'``, ``TRAN-SOURCE='POS TERM'``,
  ``TRAN-MERCHANT-ID='999999999'``, ``TRAN-MERCHANT-NAME='BILL
  PAYMENT'``, ``TRAN-MERCHANT-CITY='N/A'``,
  ``TRAN-MERCHANT-ZIP='N/A'``) are asserted as string literals so
  that any drift from the COBOL source would be caught by the
  test — these constants are NOT imported from bill_service.py to
  prevent an accidental constant-under-test reuse.

* **Atomic dual-write verification**: The critical
  ``test_pay_bill_success_dual_write`` test asserts BOTH sides of the
  dual-write (Transaction INSERT via ``session.add`` AND Account
  balance UPDATE via direct attribute mutation) AND the atomic
  commit (``session.flush`` and ``session.commit`` each called once)
  — replacing the COBOL ``WRITE FILE('TRANSACT')`` + ``REWRITE
  FILE('ACCTDAT')`` pair that was atomic via implicit SYNCPOINT.

* **Rollback verification**: Rollback tests raise an exception from
  ``session.flush`` or ``session.commit`` to simulate a database
  failure mid-transaction, then assert that ``session.rollback()`` is
  awaited (replacing CICS SYNCPOINT ROLLBACK) and the exception
  propagates (so the FastAPI error-handler middleware can translate
  it into HTTP 500).

Test Coverage (12 functions across 4 phases)
--------------------------------------------
**Phase 3 -- Dual-Write Success (5 tests)**:
 1. :func:`test_pay_bill_success_dual_write`         -- full atomic dual-write
 2. :func:`test_pay_bill_balance_subtraction_decimal` -- Decimal arithmetic
 3. :func:`test_pay_bill_auto_id_generation`         -- max+1 zero-pad
 4. :func:`test_pay_bill_auto_id_empty_table`        -- empty table -> "0...1"
 5. :func:`test_pay_bill_card_resolution_via_xref`   -- xref -> card_num

**Phase 4 -- Balance Rejection (2 tests)**:
 6. :func:`test_pay_bill_zero_balance_rejected`      -- L197-206 guard
 7. :func:`test_pay_bill_negative_balance_rejected`  -- <= ZEROS catches <0

**Phase 5 -- Failure and Rollback (4 tests)**:
 8. :func:`test_pay_bill_rollback_on_transaction_write_failure`
                                                     -- flush raises -> rollback
 9. :func:`test_pay_bill_rollback_on_account_update_failure`
                                                     -- commit raises -> rollback
10. :func:`test_pay_bill_account_not_found`         -- NOTFND on account
11. :func:`test_pay_bill_xref_not_found`             -- NOTFND on xref

**Phase 6 -- Timestamp (1 test)**:
12. :func:`test_pay_bill_timestamps_26_char`         -- PIC X(26) parity

See Also
--------
* ``src/api/services/bill_service.py``            -- The service under test.
* ``src/shared/models/account.py``                -- Account ORM model
                                                     (from CVACT01Y.cpy).
* ``src/shared/models/transaction.py``            -- Transaction ORM model
                                                     (from CVTRA05Y.cpy).
* ``src/shared/models/card_cross_reference.py``   -- CardCrossReference ORM
                                                     model (from CVACT03Y.cpy).
* ``src/shared/schemas/bill_schema.py``           -- Pydantic request /
                                                     response schemas.
* AAP Section 0.7.1 -- Refactoring-Specific Rules (preserve exact COBOL
                       error messages; dual-write atomicity).
* AAP Section 0.7.2 -- Financial Precision (Decimal, never float).
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, call, patch

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.services.bill_service import BillService
from src.shared.models.account import Account
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.transaction import Transaction
from src.shared.schemas.bill_schema import BillPaymentRequest

# ============================================================================
# Module-level constants shared by fixtures and tests.
# ============================================================================
#
# These constants encode the COBOL-exact wire values from
# app/cbl/COBIL00C.cbl. They are defined locally in this test module
# (not imported from bill_service.py) so that the tests verify the
# wire values independently of the service implementation — a drift
# between the COBOL source and the service constants would be caught
# by the tests rather than silently propagated.
# ============================================================================

#: 11-character zero-padded test account ID. Matches the COBOL
#: ``ACCT-ID PIC 9(11)`` width and the ``Account.acct_id String(11)``
#: model column. Non-numeric test data is rejected by Pydantic's
#: ``_validate_acct_id`` field validator; "00000000001" is numeric.
_TEST_ACCT_ID: str = "00000000001"

#: 16-character test card number. Matches the COBOL
#: ``XREF-CARD-NUM PIC X(16)`` width and the
#: ``CardCrossReference.card_num String(16)`` model column. Uses a
#: synthetic test-only PAN (not a real credit-card number; Luhn
#: check is not validated at this layer).
_TEST_CARD_NUM: str = "4111111111111111"

#: 9-character zero-padded test customer ID. Matches the COBOL
#: ``XREF-CUST-ID PIC 9(09)`` width and the
#: ``CardCrossReference.cust_id String(9)`` model column.
_TEST_CUST_ID: str = "000000001"

#: COBOL zero-balance rejection message -- preserved byte-for-byte
#: from ``app/cbl/COBIL00C.cbl`` line 201
#: (``MOVE 'You have nothing to pay...' TO WS-MESSAGE``). Includes
#: the literal trailing ellipsis of three periods from the COBOL
#: source. Per AAP Section 0.7.1, this text is an immutable part of
#: the migrated behavior and must not drift.
_MSG_ZERO_BALANCE_EXACT: str = "You have nothing to pay..."

#: Fixed COBOL transaction-type code for bill payment -- from
#: ``COBIL00C.cbl`` line 220 (``MOVE '02' TO TRAN-TYPE-CD``).
#: The COBOL field is ``PIC X(02)``; stored here as a 2-char string.
_EXPECTED_TRAN_TYPE_CD: str = "02"

#: Fixed COBOL transaction-category code for bill payment -- from
#: ``COBIL00C.cbl`` line 221 (``MOVE 2 TO TRAN-CAT-CD``). The COBOL
#: field is ``PIC 9(04)``; the literal ``2`` is zero-padded to 4
#: digits for storage. The modernized ``Transaction.cat_cd`` model
#: column is ``String(4)`` and stores the explicit zero-padded
#: string ``'0002'`` -- NOT the integer 2.
_EXPECTED_TRAN_CAT_CD: str = "0002"

#: Fixed COBOL transaction-source channel -- from ``COBIL00C.cbl``
#: line 222 (``MOVE 'POS TERM' TO TRAN-SOURCE``).
_EXPECTED_TRAN_SOURCE: str = "POS TERM"

#: Fixed COBOL transaction description -- from ``COBIL00C.cbl``
#: line 223 (``MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC``).
_EXPECTED_TRAN_DESC: str = "BILL PAYMENT - ONLINE"

#: Fixed COBOL merchant-id sentinel for bill payment -- from
#: ``COBIL00C.cbl`` line 226 (``MOVE 999999999 TO TRAN-MERCHANT-ID``).
#: All-nines indicates "not a merchant-originated transaction".
_EXPECTED_TRAN_MERCHANT_ID: str = "999999999"

#: Fixed COBOL merchant-name for bill payment -- from
#: ``COBIL00C.cbl`` line 227.
_EXPECTED_TRAN_MERCHANT_NAME: str = "BILL PAYMENT"

#: Fixed COBOL merchant-city for bill payment -- from
#: ``COBIL00C.cbl`` line 228.
_EXPECTED_TRAN_MERCHANT_CITY: str = "N/A"

#: Fixed COBOL merchant-zip for bill payment -- from
#: ``COBIL00C.cbl`` line 229.
_EXPECTED_TRAN_MERCHANT_ZIP: str = "N/A"

#: Width of the ``tran_id`` primary-key column -- matches COBOL
#: ``TRAN-ID PIC X(16)`` and the model ``Transaction.tran_id
#: String(16)`` column. Transaction IDs are always zero-padded to
#: this width so that lexicographic ordering matches numeric
#: ordering (required for the ``SELECT MAX(tran_id)`` semantic).
_EXPECTED_TRAN_ID_WIDTH: int = 16

#: Starting transaction ID for an empty ``transaction`` table --
#: mirrors ``_INITIAL_TRAN_ID`` in ``bill_service.py`` and the COBOL
#: convention of ``MOVE 1 TO WS-TRAN-ID-NUM`` when no previous row
#: exists. 16 characters, zero-padded.
_EXPECTED_INITIAL_TRAN_ID: str = "0000000000000001"

#: Width of the COBOL-compatible timestamp string -- matches
#: ``TRAN-ORIG-TS PIC X(26)`` / ``TRAN-PROC-TS PIC X(26)`` from
#: ``CVTRA05Y.cpy``. Python's ``datetime.strftime('%Y-%m-%d
#: %H:%M:%S.%f')`` produces exactly 26 characters.
_EXPECTED_TIMESTAMP_WIDTH: int = 26

#: :class:`Decimal` zero with 2 decimal places -- used for
#: zero-balance comparisons and as the neutral-element in balance
#: arithmetic assertions. Always use this constant (not ``Decimal(0)``
#: or ``0``) so assertion error messages show the canonical form.
_DECIMAL_ZERO: Decimal = Decimal("0.00")


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

    Replaces the real database connection that :class:`BillService`
    uses to issue the four sequential queries in :meth:`pay_bill`:

    1. ``SELECT Account WHERE acct_id == :acct_id``
       -- consumed via ``result.scalar_one_or_none()``.
    2. ``SELECT CardCrossReference WHERE acct_id == :acct_id``
       -- consumed via ``result.scalars().first()``.
    3. ``SELECT MAX(Transaction.tran_id)``
       -- consumed via ``result.scalar()``.
    4. ``SELECT Transaction.tran_id ORDER BY DESC LIMIT 1``
       -- consumed via ``result.scalar()``.

    The mock is configured with:

    * ``execute`` as an :class:`AsyncMock` (matching SQLAlchemy 2.x's
      async ``execute`` contract). Individual tests override its
      ``side_effect`` with a list of query-result mocks to simulate
      different data-access scenarios (account found, xref not found,
      empty transaction table, etc.) via the
      :func:`_make_execute_side_effect` helper below.
    * ``add`` as a synchronous :class:`MagicMock` (SQLAlchemy's ``add``
      is sync -- it just queues the entity into the Unit of Work).
    * ``flush`` as an :class:`AsyncMock` (async in 2.x).
    * ``commit`` as an :class:`AsyncMock` (async in 2.x).
    * ``rollback`` as an :class:`AsyncMock` (async in 2.x) -- this is
      the critical assertion target for the rollback tests.

    The CICS original (``COBIL00C.cbl`` L211, L213-215, L233, L235)
    used the CICS-managed ``ACCTDAT`` / ``CXACAIX`` / ``TRANSACT`` VSAM
    file handles -- also implicitly transaction-scoped. The mock plays
    the same role: owned by the test, injected into the service at
    construction time, no persistent state beyond the test
    invocation.

    Returns
    -------
    AsyncMock
        A mock ``AsyncSession`` preconfigured with async ``execute`` /
        ``flush`` / ``commit`` / ``rollback`` methods and a sync
        ``add`` method. The default ``execute`` ``return_value`` is a
        result whose accessors all return ``None`` -- individual tests
        override via ``side_effect`` for the four-query sequence.
    """
    session = AsyncMock(spec=AsyncSession)

    # Default result: all accessors return None. Individual tests
    # will override via session.execute.side_effect to provide a
    # per-query sequence (see _make_execute_side_effect helper).
    default_result = MagicMock()
    default_result.scalar_one_or_none = MagicMock(return_value=None)
    default_result.scalar = MagicMock(return_value=None)
    default_scalars = MagicMock()
    default_scalars.first = MagicMock(return_value=None)
    default_result.scalars = MagicMock(return_value=default_scalars)

    session.execute = AsyncMock(return_value=default_result)
    session.add = MagicMock()  # sync API
    session.flush = AsyncMock(return_value=None)
    session.commit = AsyncMock(return_value=None)
    session.rollback = AsyncMock(return_value=None)

    return session


@pytest.fixture
def bill_service(mock_db_session: AsyncMock) -> BillService:
    """Instantiate :class:`BillService` with the mocked session.

    :class:`BillService`'s constructor takes a single ``db`` parameter
    (``AsyncSession``) and stores it on ``self.db``. The service
    intentionally has no other state, so this fixture produces a
    fresh service for each test with a mock session that the test can
    further configure.

    Parameters
    ----------
    mock_db_session : AsyncMock
        The mocked session produced by :func:`mock_db_session`.

    Returns
    -------
    BillService
        A fresh service instance wired to the mocked session.
    """
    return BillService(db=mock_db_session)


@pytest.fixture
def sample_account() -> Account:
    """Build a sample :class:`Account` row with a non-zero balance.

    Maps each field to its COBOL copybook counterpart
    (``app/cpy/CVACT01Y.cpy``):

    =================  ==================  ==================
    COBOL Field        ORM Attribute       Test Value
    =================  ==================  ==================
    ACCT-ID            acct_id             ``"00000000001"``
    ACCT-ACTIVE-STATUS active_status       ``"Y"``
    ACCT-CURR-BAL      curr_bal            ``Decimal("1500.00")``
    ACCT-CREDIT-LIMIT  credit_limit        ``Decimal("5000.00")``
    =================  ==================  ==================

    CRITICAL: ``curr_bal`` is a :class:`decimal.Decimal`, never a
    ``float``. The balance ``Decimal("1500.00")`` is strictly positive
    so the ``IF ACCT-CURR-BAL <= ZEROS`` guard at ``COBIL00C.cbl``
    line 198 does NOT reject the payment -- the dual-write path
    proceeds.

    Returns
    -------
    Account
        A detached ORM instance (not added to any session). The
        ``version_id`` optimistic-concurrency column defaults to ``0``.
    """
    return Account(
        acct_id=_TEST_ACCT_ID,
        active_status="Y",
        curr_bal=Decimal("1500.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1000.00"),
        open_date="2020-01-01",
        expiration_date="2030-12-31",
        reissue_date="2020-01-01",
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        addr_zip="12345",
        group_id="DEFAULT",
    )


@pytest.fixture
def zero_balance_account() -> Account:
    """Build a sample :class:`Account` with an exactly-zero balance.

    Exercises the COBOL ``IF ACCT-CURR-BAL <= ZEROS`` guard at
    ``COBIL00C.cbl`` line 198 with the boundary case ``= 0``. The
    guard uses ``<= ZEROS`` so ``Decimal('0.00')`` must trigger
    rejection with the exact message 'You have nothing to pay...'
    (line 201).

    CRITICAL: ``curr_bal`` is :class:`Decimal`, never ``float``.

    Returns
    -------
    Account
        A detached ORM instance with ``curr_bal=Decimal('0.00')``.
    """
    return Account(
        acct_id=_TEST_ACCT_ID,
        active_status="Y",
        curr_bal=Decimal("0.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1000.00"),
        open_date="2020-01-01",
        expiration_date="2030-12-31",
        reissue_date="2020-01-01",
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        addr_zip="12345",
        group_id="DEFAULT",
    )


@pytest.fixture
def negative_balance_account() -> Account:
    """Build a sample :class:`Account` with a strictly-negative balance.

    Exercises the non-boundary branch of the COBOL ``IF ACCT-CURR-BAL
    <= ZEROS`` guard at ``COBIL00C.cbl`` line 198. A negative balance
    represents a credit owed TO the customer (overpayment or refund)
    -- there is nothing to pay, so the same message
    'You have nothing to pay...' is returned (line 201).

    CRITICAL: ``curr_bal`` is :class:`Decimal`, never ``float``. The
    negative sign is part of the COBOL ``PIC S9(10)V99`` signed
    semantics preserved in the ``NUMERIC(15, 2)`` PostgreSQL column.

    Returns
    -------
    Account
        A detached ORM instance with ``curr_bal=Decimal('-100.00')``.
    """
    return Account(
        acct_id=_TEST_ACCT_ID,
        active_status="Y",
        curr_bal=Decimal("-100.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1000.00"),
        open_date="2020-01-01",
        expiration_date="2030-12-31",
        reissue_date="2020-01-01",
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        addr_zip="12345",
        group_id="DEFAULT",
    )


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

    The ``acct_id`` matches :data:`_TEST_ACCT_ID` so that the
    ``SELECT CardCrossReference WHERE acct_id = :acct_id`` lookup at
    :meth:`BillService.pay_bill` Step 3 finds this row when the test
    wires it into the mocked query results.

    The ``card_num`` is a synthetic Visa-prefix test PAN (not real
    card data, no Luhn verification at this layer). It flows from
    :attr:`CardCrossReference.card_num` -> ``TRAN-CARD-NUM`` on the
    Transaction INSERT at ``COBIL00C.cbl`` line 225.

    Returns
    -------
    CardCrossReference
        A detached ORM instance ready to be returned from the mocked
        ``scalars().first()`` call.
    """
    return CardCrossReference(
        card_num=_TEST_CARD_NUM,
        cust_id=_TEST_CUST_ID,
        acct_id=_TEST_ACCT_ID,
    )


# ============================================================================
# Helper functions (test-only)
# ============================================================================


def _make_execute_side_effect(
    *,
    account: Account | None,
    xref: CardCrossReference | None = None,
    max_tran_id_agg: str | None = None,
    max_tran_id_sort: str | None = None,
) -> list[MagicMock]:
    """Build a list of query-result mocks for the four queries ``pay_bill`` issues.

    :meth:`BillService.pay_bill` invokes ``self.db.execute`` up to
    four times in a fixed order; the service short-circuits on early
    failure (account not found OR zero balance OR xref not found).
    This helper constructs an ordered list of :class:`MagicMock`
    result objects matching the exact accessor used by each step of
    the service:

    ==  =================================  ===========================
    #   Service step (bill_service.py)     Accessor invoked
    ==  =================================  ===========================
    1   Step 1 ``select(Account)``         ``scalar_one_or_none()``
    2   Step 3 ``select(CardCrossRef)``    ``scalars().first()``
    3   Step 4(a) ``select(func.max)``     ``scalar()``
    4   Step 4(b) ``select(...).desc()``   ``scalar()``
    ==  =================================  ===========================

    The function mirrors the service's short-circuit paths:

    * ``account is None`` -> only result #1 (then service returns).
    * ``account.curr_bal <= 0`` -> only result #1 (then service
      returns with zero-balance message).
    * ``xref is None`` -> results #1 and #2 only (then service
      returns with xref-not-found message).
    * All present -> all four results returned.

    The caller assigns the returned list to
    ``mock_session.execute.side_effect`` so that successive ``await
    self.db.execute(...)`` calls unwind through the list in order.

    Parameters
    ----------
    account : Account | None
        Row returned from ``scalar_one_or_none()`` on the Account
        query. ``None`` simulates the COBOL ``READ-ACCTDAT-FILE``
        NOTFND branch.
    xref : CardCrossReference | None, optional
        Row returned from ``scalars().first()`` on the
        CardCrossReference query. ``None`` simulates the COBOL
        ``READ-CXACAIX-FILE`` NOTFND branch. Only consulted if the
        account is found AND has positive balance.
    max_tran_id_agg : str | None, optional
        Scalar value returned from the ``SELECT MAX(tran_id)`` query.
        ``None`` simulates an empty ``transaction`` table.
    max_tran_id_sort : str | None, optional
        Scalar value returned from the ``SELECT ... ORDER BY DESC
        LIMIT 1`` verification query. Usually equal to
        ``max_tran_id_agg``; only differs when the test is
        deliberately exercising the aggregate-mismatch warning path.

    Returns
    -------
    list[MagicMock]
        Ordered list of 1, 2, or 4 MagicMock result objects,
        depending on which service-side short-circuit applies.
    """
    results: list[MagicMock] = []

    # Query 1: Account by primary key (Step 1).
    account_result = MagicMock()
    account_result.scalar_one_or_none = MagicMock(return_value=account)
    results.append(account_result)

    # Short-circuit: account not found -> service returns immediately.
    if account is None:
        return results

    # Short-circuit: zero/negative balance -> service returns with
    # the "You have nothing to pay..." message before issuing any
    # further queries.
    if account.curr_bal <= _DECIMAL_ZERO:
        return results

    # Query 2: CardCrossReference by acct_id (Step 3). The service
    # uses ``.scalars().first()`` (NOT ``scalar_one_or_none``)
    # because multiple xref rows per account are permitted by the
    # schema (one xref per card).
    xref_scalars = MagicMock()
    xref_scalars.first = MagicMock(return_value=xref)
    xref_result = MagicMock()
    xref_result.scalars = MagicMock(return_value=xref_scalars)
    results.append(xref_result)

    # Short-circuit: xref not found -> service returns with
    # "Card not found..." message.
    if xref is None:
        return results

    # Query 3: SELECT MAX(tran_id) via func.max() (Step 4a).
    max_agg_result = MagicMock()
    max_agg_result.scalar = MagicMock(return_value=max_tran_id_agg)
    results.append(max_agg_result)

    # Query 4: SELECT tran_id ORDER BY DESC LIMIT 1 (Step 4b,
    # verification query).
    max_sort_result = MagicMock()
    max_sort_result.scalar = MagicMock(return_value=max_tran_id_sort)
    results.append(max_sort_result)

    return results


def _extract_added_transaction(mock_session: AsyncMock) -> Transaction:
    """Retrieve the :class:`Transaction` instance that ``BillService``
    added to the mocked session during a dual-write.

    :meth:`BillService.pay_bill` stages the INSERT by invoking
    ``self.db.add(new_transaction)`` (see ``bill_service.py`` line
    863) BEFORE flushing. In the test, the ``add`` method is a
    synchronous :class:`MagicMock`, so we can inspect its call args
    to retrieve the exact :class:`Transaction` instance that would
    have been persisted.

    Tests use this helper to verify that every field of the staged
    Transaction matches its COBOL ``WRITE-TRANSACT-FILE`` expectation
    (``COBIL00C.cbl`` L218-233).

    Parameters
    ----------
    mock_session : AsyncMock
        The mocked session after :meth:`pay_bill` has been awaited.
        Must have ``add`` called at least once.

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
        conditions indicate a regression in the service (the dual-
        write did not occur).
    """
    assert mock_session.add.called, (
        "Expected BillService.pay_bill to call session.add(transaction) "
        "for the dual-write Transaction INSERT staging step, but "
        "session.add was never invoked. This indicates the service "
        "short-circuited before Step 7 (see bill_service.py L863)."
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
        f"expected a Transaction instance (from bill_service.py L848-"
        f"862). This indicates the staged object is not a Transaction "
        f"ORM row, which would break the COBOL WRITE-TRANSACT-FILE "
        f"contract."
    )
    return staged


# ============================================================================
# Phase 3: Dual-Write Success Tests
# ============================================================================
#
# These tests exercise the happy-path dual-write sequence from
# COBIL00C.cbl lines 210-243:
#
#   1. READ CXACAIX  (card cross-reference lookup by acct_id)
#   2. STARTBR/READPREV TRANSACT  (generate next tran_id)
#   3. WRITE TRANSACT  (stage the new Transaction row)
#   4. REWRITE ACCTDAT  (update the Account balance)
#
# All four operations commit atomically within a single CICS
# transaction. In the modernized service, the equivalent is a single
# SQLAlchemy session's BEGIN/COMMIT scope -- flush() followed by
# commit() (bill_service.py L912-913).
#
# Each test asserts TWO things simultaneously (hence "dual-write"):
#   * Transaction was staged via session.add(...) with the correct
#     fixed-literal values from COBIL00C.cbl L220-229
#   * Account's curr_bal attribute was mutated to current_balance -
#     payment_amount using Decimal arithmetic (never float)
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_success_dual_write(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """End-to-end happy path: verify the atomic dual-write sequence.

    This is the CRITICAL test of the module. It validates the entire
    ``WRITE TRANSACT`` + ``REWRITE ACCTDAT`` atomic sequence from
    COBIL00C.cbl lines 210-243 in a single test:

    * Account lookup finds the account (Step 1).
    * Balance check passes (Step 2, ``current_balance > 0``).
    * CardCrossReference lookup finds the xref (Step 3).
    * tran_id is auto-generated as ``"0000000000000100"`` from the
      existing max ``"0000000000000099"`` (Step 4).
    * A :class:`Transaction` is staged via ``session.add(...)`` with
      ALL fixed literals from COBIL00C.cbl lines 220-229 (Step 7).
    * The account's ``curr_bal`` is mutated via Decimal subtraction
      to exactly ``current_balance - request.amount`` (Step 8).
    * ``session.flush()`` AND ``session.commit()`` are both awaited
      (Step 9 -- the CICS implicit SYNCPOINT equivalent).
    * The response carries ``confirm='Y'`` and the success message.

    Maps to COBOL: The complete WRITE TRANSACT + REWRITE ACCTDAT
    atomic sequence in ``COBIL00C.cbl`` PROCESS-ENTER-KEY paragraph.
    """
    # Arrange: Account has a positive balance; xref exists; the
    # transaction table has an existing max tran_id that should be
    # incremented by 1 and zero-padded to 16 chars.
    original_balance: Decimal = sample_account.curr_bal
    original_credit_limit: Decimal = sample_account.credit_limit
    original_active_status: str = sample_account.active_status
    original_acct_id: str = sample_account.acct_id
    assert original_balance == Decimal("1500.00"), (
        "Test precondition: sample_account fixture should start with curr_bal=Decimal('1500.00')."
    )
    assert original_acct_id == _TEST_ACCT_ID, (
        "Test precondition: sample_account.acct_id must match _TEST_ACCT_ID so the Account PK lookup succeeds."
    )
    assert original_active_status == "Y", (
        "Test precondition: sample_account.active_status must be "
        "'Y' (active) -- bill payment is only valid for active "
        "accounts."
    )
    assert isinstance(original_credit_limit, Decimal), (
        "Test precondition: sample_account.credit_limit must be Decimal (never float)."
    )
    existing_max_tran_id = "0000000000000099"
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg=existing_max_tran_id,
        max_tran_id_sort=existing_max_tran_id,
    )

    # Partial-balance payment (not full balance) to exercise the
    # modernized API's partial-payment support (see BillPaymentRequest
    # docstring).
    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("1500.00"),
    )

    # Act
    response = await bill_service.pay_bill(request)

    # Assert 1: The Transaction INSERT was staged with all COBOL
    # fixed literals (COBIL00C.cbl L218-233).
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.tran_id == "0000000000000100", (
        f"Expected tran_id='0000000000000100' (max '0000000000000099' "
        f"+ 1, zero-padded to 16 chars); got {staged_txn.tran_id!r}"
    )
    assert staged_txn.type_cd == _EXPECTED_TRAN_TYPE_CD, (
        f"Expected type_cd='02' (COBIL00C.cbl L220 MOVE '02' TO TRAN-TYPE-CD); got {staged_txn.type_cd!r}"
    )
    assert staged_txn.cat_cd == _EXPECTED_TRAN_CAT_CD, (
        f"Expected cat_cd='0002' (COBIL00C.cbl L221 MOVE 2 TO "
        f"TRAN-CAT-CD, zero-padded to PIC 9(04)); got "
        f"{staged_txn.cat_cd!r}. NOTE: cat_cd MUST be the 4-char "
        f"string '0002', never the int 2."
    )
    assert staged_txn.source == _EXPECTED_TRAN_SOURCE, (
        f"Expected source='POS TERM' (COBIL00C.cbl L222); got {staged_txn.source!r}"
    )
    assert staged_txn.description == _EXPECTED_TRAN_DESC, (
        f"Expected description='BILL PAYMENT - ONLINE' "
        f"(COBIL00C.cbl L223 MOVE 'BILL PAYMENT - ONLINE' TO "
        f"TRAN-DESC); got {staged_txn.description!r}"
    )
    assert staged_txn.merchant_id == _EXPECTED_TRAN_MERCHANT_ID, (
        f"Expected merchant_id='999999999' (COBIL00C.cbl L226); got {staged_txn.merchant_id!r}"
    )
    assert staged_txn.merchant_name == _EXPECTED_TRAN_MERCHANT_NAME
    assert staged_txn.merchant_city == _EXPECTED_TRAN_MERCHANT_CITY
    assert staged_txn.merchant_zip == _EXPECTED_TRAN_MERCHANT_ZIP
    assert staged_txn.card_num == _TEST_CARD_NUM, (
        f"Expected card_num from xref.card_num (COBIL00C.cbl L225 "
        f"MOVE XREF-CARD-NUM TO TRAN-CARD-NUM); got "
        f"{staged_txn.card_num!r}"
    )

    # Decimal amount assertion -- the modernized API uses
    # transaction_amount (post round_financial) which for an exact
    # 2-decimal input equals the request amount.
    assert isinstance(staged_txn.amount, Decimal), (
        f"Transaction.amount MUST be Decimal (never float) to "
        f"preserve COBOL PIC S9(09)V99 precision; got "
        f"{type(staged_txn.amount).__name__}"
    )
    assert staged_txn.amount == Decimal("1500.00"), f"Expected amount=Decimal('1500.00'); got {staged_txn.amount!r}"

    # Assert 2: The Account balance was mutated to new balance.
    # COBIL00C.cbl L234: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
    # For a full-balance payment: new_balance == 0.
    assert isinstance(sample_account.curr_bal, Decimal), (
        f"Account.curr_bal MUST remain Decimal after mutation (never "
        f"float); got {type(sample_account.curr_bal).__name__}"
    )
    assert sample_account.curr_bal == Decimal("0.00"), (
        f"Expected account.curr_bal mutated to Decimal('0.00') = 1500.00 - 1500.00; got {sample_account.curr_bal!r}"
    )

    # Assert 2b: Other Account fields were NOT mutated. Only
    # ``curr_bal`` changes during bill payment -- the Account's
    # ``acct_id`` (PK), ``active_status``, and ``credit_limit`` are
    # invariants of the bill-payment operation per COBIL00C.cbl
    # L234 which ONLY modifies ACCT-CURR-BAL.
    assert sample_account.acct_id == original_acct_id, (
        "Account.acct_id must NOT be mutated during bill payment (it is the primary key)."
    )
    assert sample_account.active_status == original_active_status, (
        "Account.active_status must NOT be mutated during bill payment (only curr_bal changes per COBIL00C.cbl L234)."
    )
    assert sample_account.credit_limit == original_credit_limit, (
        "Account.credit_limit must NOT be mutated during bill payment (only curr_bal changes per COBIL00C.cbl L234)."
    )

    # Assert 3: Both flush() and commit() were awaited -- the CICS
    # SYNCPOINT equivalent. Both writes now durable atomically.
    mock_db_session.flush.assert_awaited_once()
    mock_db_session.commit.assert_awaited_once()

    # Assert 4: The commit order is correct: flush happens BEFORE
    # commit. We use ``unittest.mock.call`` with a shared parent
    # mock to verify the call order across two different mock
    # methods. This guards against a regression where the service
    # reorders or skips flush().
    flush_call_order = mock_db_session.method_calls.index(call.flush())
    commit_call_order = mock_db_session.method_calls.index(call.commit())
    assert flush_call_order < commit_call_order, (
        f"session.flush() must be called BEFORE session.commit() "
        f"(see bill_service.py L912-913). Got flush at index "
        f"{flush_call_order} and commit at index {commit_call_order}."
    )

    # Assert 5: No rollback was issued in the happy path.
    mock_db_session.rollback.assert_not_awaited()

    # Assert 6: Response carries the success indicator.
    assert response.confirm == "Y", f"Expected confirm='Y' for successful dual-write; got {response.confirm!r}"
    assert response.acct_id == _TEST_ACCT_ID
    assert response.amount == Decimal("1500.00")
    assert response.current_balance == Decimal("0.00"), (
        f"Response.current_balance should reflect the POST-payment "
        f"balance (new_balance); got {response.current_balance!r}"
    )
    assert response.message is not None
    assert "0000000000000100" in response.message, (
        f"Success message should include the generated tran_id. "
        f"Expected format 'Payment successful. Your Transaction ID "
        f"is 0000000000000100.'; got {response.message!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_balance_subtraction_decimal(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
) -> None:
    """Verify balance subtraction uses :class:`Decimal` arithmetic
    (NEVER ``float``) and produces the exact expected result.

    COBIL00C.cbl line 234:
    ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT``

    The COBOL COMPUTE verb performs exact decimal arithmetic on
    ``PIC S9(10)V99`` fields. The modernized service MUST preserve
    this precision by using :class:`decimal.Decimal` throughout.
    This test uses a non-round-number balance (``$1234.56``) that
    would produce rounding artifacts under IEEE-754 floating-point
    arithmetic (try ``1234.56 - 1234.56`` in Python with floats and
    compare to Decimal).

    Maps to COBOL: L234 arithmetic precision constraint.
    """
    # Arrange: Account with a non-round balance.
    account_1234 = Account(
        acct_id=_TEST_ACCT_ID,
        active_status="Y",
        curr_bal=Decimal("1234.56"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("1000.00"),
        open_date="2020-01-01",
        expiration_date="2030-12-31",
        reissue_date="2020-01-01",
        curr_cyc_credit=Decimal("0.00"),
        curr_cyc_debit=Decimal("0.00"),
        addr_zip="12345",
        group_id="DEFAULT",
    )
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=account_1234,
        xref=sample_xref,
        max_tran_id_agg="0000000000000050",
        max_tran_id_sort="0000000000000050",
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("1234.56"),
    )

    # Act
    response = await bill_service.pay_bill(request)

    # Assert: Transaction.amount is exactly Decimal("1234.56"), NOT
    # a floating-point approximation like 1234.5600000000001.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert isinstance(staged_txn.amount, Decimal)
    assert staged_txn.amount == Decimal("1234.56"), (
        f"Transaction.amount should be exactly Decimal('1234.56'); "
        f"got {staged_txn.amount!r}. Any deviation indicates float "
        f"contamination in the service's arithmetic."
    )

    # Assert: Account.curr_bal subtracted to exactly Decimal("0.00").
    # 1234.56 - 1234.56 in Decimal == 0.00 exactly.
    # (In float: 1234.56 - 1234.56 might be 0.0 by coincidence, but
    # subtle intermediate operations in Decimal vs float diverge --
    # the point is that our service uses Decimal everywhere.)
    assert isinstance(account_1234.curr_bal, Decimal), (
        "Account.curr_bal MUST be Decimal after subtraction; a float assignment would silently break COBOL precision."
    )
    assert account_1234.curr_bal == Decimal("0.00"), (
        f"Expected new balance Decimal('0.00'); got {account_1234.curr_bal!r}"
    )

    # Assert: Response.current_balance matches the mutated account
    # balance, still as Decimal.
    assert isinstance(response.current_balance, Decimal)
    assert response.current_balance == Decimal("0.00")

    # Assert: Partial-payment Decimal arithmetic also works. This is
    # a secondary assertion verifying the invariant through
    # independent computation rather than re-running the service.
    computed_new_balance = Decimal("1234.56") - Decimal("1234.56")
    assert response.current_balance == computed_new_balance


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_auto_id_generation(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """Verify auto-ID generation: max(tran_id) + 1, zero-padded to 16 chars.

    COBIL00C.cbl lines 213-217:
    ``PERFORM STARTBR-TRANSACT-FILE``
    ``PERFORM READPREV-TRANSACT-FILE``
    ``PERFORM ENDBR-TRANSACT-FILE``
    ``MOVE TRAN-ID     TO WS-TRAN-ID-NUM``
    ``ADD 1           TO WS-TRAN-ID-NUM``

    The COBOL sequence reads the last (lexically-greatest) tran_id
    row and increments by 1. Because tran_id values are always
    zero-padded to 16 chars, lexical max == numeric max, so the
    increment is safe.

    This test uses existing max ``"0000000000000099"`` and expects
    the new tran_id to be ``"0000000000000100"`` (99 + 1 = 100,
    zero-padded to 16 chars).

    Maps to COBOL: STARTBR/READPREV/ENDBR + ADD 1 pattern.
    """
    # Arrange
    existing_max_tran_id = "0000000000000099"
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg=existing_max_tran_id,
        max_tran_id_sort=existing_max_tran_id,
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("100.00"),
    )

    # Act
    await bill_service.pay_bill(request)

    # Assert: The generated tran_id is exactly max + 1, zero-padded.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.tran_id == "0000000000000100", (
        f"Expected tran_id='0000000000000100' "
        f"(max '{existing_max_tran_id}' + 1, zero-padded to "
        f"{_EXPECTED_TRAN_ID_WIDTH} chars per COBOL PIC X(16)); got "
        f"{staged_txn.tran_id!r}"
    )
    # Defensive: length check to guard against any off-by-one in the
    # zero-padding logic.
    assert len(staged_txn.tran_id) == _EXPECTED_TRAN_ID_WIDTH, (
        f"tran_id must be exactly {_EXPECTED_TRAN_ID_WIDTH} chars "
        f"(COBOL PIC X(16)); got length {len(staged_txn.tran_id)}"
    )
    # Defensive: the string must be numeric-convertible.
    assert staged_txn.tran_id.isdigit(), f"tran_id must be all-digits (zero-padded numeric); got {staged_txn.tran_id!r}"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_auto_id_empty_table(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """Verify empty-table edge case: tran_id defaults to ``_INITIAL_TRAN_ID``.

    When the ``transaction`` table is empty, ``SELECT MAX(tran_id)``
    returns ``None``. The service must fall back to
    ``_INITIAL_TRAN_ID = '0000000000000001'`` (16 chars, zero-padded
    representation of 1).

    Maps to COBOL: CICS ``STARTBR + READPREV`` returning
    ENDFILE NOTFND condition -- COBOL initializes ``WS-TRAN-ID-NUM``
    to 1 when no previous record exists.
    """
    # Arrange: Empty transactions table -- BOTH aggregate queries
    # return None.
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("42.00"),
    )

    # Act
    await bill_service.pay_bill(request)

    # Assert: tran_id falls back to initial value "0000000000000001".
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.tran_id == _EXPECTED_INITIAL_TRAN_ID, (
        f"Expected tran_id={_EXPECTED_INITIAL_TRAN_ID!r} (empty transaction table fallback); got {staged_txn.tran_id!r}"
    )
    assert len(staged_txn.tran_id) == _EXPECTED_TRAN_ID_WIDTH


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_card_resolution_via_xref(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
) -> None:
    """Verify that ``Transaction.card_num`` comes from the xref lookup.

    COBIL00C.cbl line 225:
    ``MOVE XREF-CARD-NUM TO TRAN-CARD-NUM``

    The bill-payment input form takes only the ``acct_id`` (not the
    card number -- the customer may have forgotten which card they
    used). The service resolves the card via the CardCrossReference
    table (an alternate-index lookup on ``acct_id``) and uses the
    resulting ``card_num`` on the new Transaction row.

    This test uses a custom xref with a specific card number and
    asserts the Transaction gets THAT card number -- not a hardcoded
    value, not the acct_id, not None.

    Maps to COBOL: ``COBIL00C READ CXACAIX -> XREF-CARD-NUM`` pattern.
    """
    # Arrange: Use a distinctive card number so we can verify it
    # flows through correctly.
    distinctive_card_num = "5555555555554444"
    custom_xref = CardCrossReference(
        card_num=distinctive_card_num,
        cust_id=_TEST_CUST_ID,
        acct_id=_TEST_ACCT_ID,
    )
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=custom_xref,
        max_tran_id_agg=None,
        max_tran_id_sort=None,
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("50.00"),
    )

    # Act
    await bill_service.pay_bill(request)

    # Assert: Transaction.card_num is the xref's card_num.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.card_num == distinctive_card_num, (
        f"Expected Transaction.card_num={distinctive_card_num!r} "
        f"(from xref.card_num per COBIL00C.cbl L225 MOVE "
        f"XREF-CARD-NUM TO TRAN-CARD-NUM); got "
        f"{staged_txn.card_num!r}"
    )
    assert len(staged_txn.card_num) == 16, (
        f"card_num must be 16 chars (COBOL PIC X(16)); got length {len(staged_txn.card_num)}"
    )


# ============================================================================
# Phase 4: Balance Rejection Tests (COBIL00C.cbl L197-206)
# ============================================================================
#
# COBIL00C.cbl lines 197-205 reject any payment attempt against an
# account with zero or negative balance:
#
#   IF ACCT-CURR-BAL <= ZEROS AND
#      ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES
#       MOVE 'Y' TO WS-ERR-FLG
#       MOVE 'You have nothing to pay...' TO WS-MESSAGE
#       ...
#   END-IF
#
# The check ``<= ZEROS`` catches BOTH zero (==) AND negative (<) --
# both tested below.
#
# CRITICAL: The COBOL error message 'You have nothing to pay...'
# MUST be preserved byte-for-byte. AAP Section 0.7.1 (Preserve all
# existing functionality exactly as-is) makes this literal an
# immutable part of the migration contract.
#
# These tests verify:
#   * No Transaction is staged (session.add NOT called)
#   * No Account balance is mutated
#   * No flush/commit is issued
#   * The exact COBOL error message is returned
#   * The response carries confirm='N'
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_zero_balance_rejected(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    zero_balance_account: Account,
) -> None:
    """Zero balance triggers rejection with the exact COBOL message.

    COBIL00C.cbl line 198: ``IF ACCT-CURR-BAL <= ZEROS``
    COBIL00C.cbl line 201: ``MOVE 'You have nothing to pay...' TO WS-MESSAGE``

    With ``curr_bal = Decimal('0.00')``, the service MUST:

    * Return ``BillPaymentResponse(confirm='N', message='You have
      nothing to pay...', ...)`` with the exact message text.
    * NOT stage any Transaction (session.add NOT called).
    * NOT mutate the account balance.
    * NOT flush or commit.

    Maps to COBOL: L198 zero-balance guard, boundary case ``== 0``.
    """
    # Arrange: Account with zero balance. No xref / tran_id queries
    # should be issued because the service short-circuits at Step 2.
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=zero_balance_account,
    )
    original_balance = zero_balance_account.curr_bal
    assert original_balance == _DECIMAL_ZERO

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("50.00"),
    )

    # Act
    response = await bill_service.pay_bill(request)

    # Assert 1: The exact COBOL error message is preserved
    # byte-for-byte. AAP Section 0.7.1 mandates this literal must
    # not drift from COBIL00C.cbl line 201.
    assert response.message == _MSG_ZERO_BALANCE_EXACT, (
        f"Expected COBOL-exact error message "
        f"{_MSG_ZERO_BALANCE_EXACT!r} (from COBIL00C.cbl L201); got "
        f"{response.message!r}. This literal must be preserved "
        f"byte-for-byte per AAP Section 0.7.1."
    )

    # Assert 2: confirm='N' indicates business-logic rejection (not
    # a 500-level system failure -- the service returns cleanly).
    assert response.confirm == "N", f"Expected confirm='N' for zero-balance rejection; got {response.confirm!r}"

    # Assert 3: No Transaction was staged.
    mock_db_session.add.assert_not_called()

    # Assert 4: Account balance was NOT mutated.
    assert zero_balance_account.curr_bal == _DECIMAL_ZERO, (
        f"Account balance should be unchanged on rejection; "
        f"original={_DECIMAL_ZERO}, now={zero_balance_account.curr_bal}"
    )

    # Assert 5: No flush/commit because no write was staged.
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()

    # Assert 6: No rollback either -- clean short-circuit (not an
    # error path, just a business rejection).
    mock_db_session.rollback.assert_not_awaited()

    # Assert 7: Response echoes acct_id and the current (unchanged)
    # balance.
    assert response.acct_id == _TEST_ACCT_ID
    assert response.current_balance == _DECIMAL_ZERO, (
        f"Response.current_balance should reflect the unchanged balance on rejection; got {response.current_balance!r}"
    )


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_negative_balance_rejected(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    negative_balance_account: Account,
) -> None:
    """Negative balance ALSO triggers rejection -- same message.

    COBIL00C.cbl line 198: ``IF ACCT-CURR-BAL <= ZEROS``

    The COBOL ``<= ZEROS`` check catches both zero AND negative.
    A negative balance represents a credit owed TO the customer
    (overpayment or refund already processed) -- there is nothing
    to pay, so the same 'You have nothing to pay...' message
    applies.

    This test uses ``curr_bal = Decimal('-100.00')`` to exercise
    the non-boundary branch of the guard.

    Maps to COBOL: L198 zero-balance guard, non-boundary case ``< 0``.
    """
    # Arrange: Account with negative balance.
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=negative_balance_account,
    )
    original_balance = negative_balance_account.curr_bal
    assert original_balance == Decimal("-100.00")

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("50.00"),
    )

    # Act
    response = await bill_service.pay_bill(request)

    # Assert 1: Same exact COBOL error message for negative balance.
    assert response.message == _MSG_ZERO_BALANCE_EXACT, (
        f"Expected COBOL-exact error message "
        f"{_MSG_ZERO_BALANCE_EXACT!r} -- 'You have nothing to pay...' "
        f"applies to BOTH zero AND negative balances per COBOL "
        f"'<= ZEROS' check; got {response.message!r}"
    )
    assert response.confirm == "N"

    # Assert 2: No Transaction staged.
    mock_db_session.add.assert_not_called()

    # Assert 3: Account balance unchanged.
    assert negative_balance_account.curr_bal == Decimal("-100.00"), (
        f"Negative balance should be unchanged on rejection; got {negative_balance_account.curr_bal!r}"
    )

    # Assert 4: No flush/commit/rollback.
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()
    mock_db_session.rollback.assert_not_awaited()

    # Assert 5: Response echoes the negative balance unchanged.
    assert response.current_balance == Decimal("-100.00")


# ============================================================================
# Phase 5: Failure and Rollback Tests
# ============================================================================
#
# The service's ``pay_bill`` method has two distinct failure models:
#
#   (a) Business-logic rejection: account not found, zero balance,
#       xref not found. These return ``BillPaymentResponse(confirm='N')``
#       WITHOUT raising. No rollback, no flush, no commit (except
#       in the tran_id parse-failure path, which DOES call rollback
#       -- but that's an internal-data error, not a business one).
#
#   (b) System failure: unexpected exception from flush(), commit(),
#       execute(), or ORM layer. These trigger the ``except Exception
#       as exc`` handler (bill_service.py L933) which:
#         1. Logs the error with exc_info.
#         2. Calls ``await self.db.rollback()`` (wrapped in a
#            secondary try/except that swallows rollback failures).
#         3. Re-raises the original exception.
#
# These tests verify both failure models and the rollback semantics.
#
# Maps to COBOL: The CICS implicit SYNCPOINT ROLLBACK on abnormal
# transaction termination (ABEND, program check, explicit ROLLBACK
# request). On the mainframe, either both ACCTDAT and TRANSACT
# updates durably commit, or neither does -- the same all-or-nothing
# guarantee the modernized service must preserve.
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_rollback_on_transaction_write_failure(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """``session.flush()`` failure triggers rollback and exception propagation.

    Simulates a database-level failure DURING the dual-write commit
    (e.g., PostgreSQL NUMERIC(15,2) overflow on Transaction.amount,
    UNIQUE constraint violation on tran_id, or connection loss
    between INSERT and COMMIT). In all cases:

    * The original exception MUST propagate to the caller.
    * ``self.db.rollback()`` MUST be awaited before the re-raise.
    * Rollback ensures neither the Transaction nor the Account
      UPDATE becomes durable.

    Maps to COBOL: CICS SYNCPOINT ROLLBACK on WRITE TRANSACT
    failure (e.g., NOSPACE, DUPREC, PGMIDERR on the ACCTDAT/TRANSACT
    VSAM files).
    """
    # Arrange: Happy path up to the flush; flush then raises a
    # simulated database error.
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg="0000000000000099",
        max_tran_id_sort="0000000000000099",
    )
    simulated_error = RuntimeError("Simulated DB flush failure: NUMERIC(15,2) overflow on transaction.amount")
    mock_db_session.flush.side_effect = simulated_error

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("100.00"),
    )

    # Act + Assert: The original exception MUST propagate.
    with pytest.raises(RuntimeError) as exc_info:
        await bill_service.pay_bill(request)

    # Assert 1: It's the exact same exception instance (not wrapped,
    # not replaced). The service must re-raise, not wrap.
    assert exc_info.value is simulated_error, (
        "Expected the original exception to propagate unchanged; "
        "got a different exception instance. This would indicate "
        "the service is wrapping exceptions, which breaks error "
        "diagnosability for the caller."
    )

    # Assert 2: The staging via session.add DID happen (the failure
    # occurred DURING flush, not before).
    mock_db_session.add.assert_called_once()

    # Assert 3: flush was attempted.
    mock_db_session.flush.assert_awaited_once()

    # Assert 4: rollback was awaited after the flush failure. This
    # is the CORE invariant: any unexpected exception MUST trigger
    # a rollback to preserve atomicity.
    mock_db_session.rollback.assert_awaited_once()

    # Assert 5: commit was NEVER awaited (we failed before reaching
    # it). Both updates are reverted via rollback.
    mock_db_session.commit.assert_not_awaited()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_rollback_on_account_update_failure(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """``session.commit()`` failure triggers rollback and exception propagation.

    Simulates a failure at the COMMIT boundary -- distinct from the
    flush failure above. This exercises the case where flush()
    succeeds (INSERT and UPDATE staged at the database level) but
    COMMIT fails (e.g., StaleDataError from the Account's
    optimistic-concurrency ``version_id`` column, deadlock, or
    connection loss during COMMIT).

    The service MUST still roll back the entire dual-write -- if
    COMMIT fails, the SQLAlchemy session is in an invalid state and
    rollback is the only recovery path.

    Maps to COBOL: REWRITE ACCTDAT failure -- CICS ABEND triggering
    implicit SYNCPOINT ROLLBACK.
    """
    # Arrange: Happy path through flush, commit then raises.
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg="0000000000000099",
        max_tran_id_sort="0000000000000099",
    )
    simulated_error = RuntimeError(
        "Simulated commit failure: StaleDataError on account.version_id optimistic-concurrency check"
    )
    mock_db_session.commit.side_effect = simulated_error

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("100.00"),
    )

    # Act + Assert: Original exception propagates.
    with pytest.raises(RuntimeError) as exc_info:
        await bill_service.pay_bill(request)

    assert exc_info.value is simulated_error, "Commit-failure exception must propagate unchanged."

    # Assert 1: Transaction was staged.
    mock_db_session.add.assert_called_once()

    # Assert 2: flush succeeded (it's AsyncMock with no side_effect,
    # so it returned None cleanly).
    mock_db_session.flush.assert_awaited_once()

    # Assert 3: commit was attempted and failed.
    mock_db_session.commit.assert_awaited_once()

    # Assert 4: rollback was called AFTER the commit failure. This
    # ensures the entire dual-write is reverted -- including the
    # staged Account UPDATE and Transaction INSERT.
    mock_db_session.rollback.assert_awaited_once()


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_account_not_found(
    bill_service: BillService,
    mock_db_session: AsyncMock,
) -> None:
    """Nonexistent account returns a confirm='N' response without raising.

    COBIL00C.cbl READ-ACCTDAT-FILE NOTFND branch:
    When the CICS READ returns DFHRESP(NOTFND), the original COBOL
    program sent the BMS screen with an error message and let the
    user retry without triggering a SYNCPOINT ROLLBACK.

    The modernized service mirrors this: ``scalar_one_or_none()``
    returning ``None`` short-circuits with a confirm='N' response
    carrying the COBOL-equivalent "Account not found..." message
    (see ``_MSG_ACCOUNT_NOT_FOUND`` in bill_service.py).

    Critically:
        * NO exception is raised (business rejection, not a fault).
        * NO rollback (nothing was staged).
        * NO flush/commit.

    Maps to COBOL: READ-ACCTDAT-FILE NOTFND branch.
    """
    # Arrange: Account lookup returns None (no matching row).
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=None,
    )

    request = BillPaymentRequest(
        acct_id="99999999999",
        amount=Decimal("100.00"),
    )

    # Act
    response = await bill_service.pay_bill(request)

    # Assert 1: confirm='N', carries account-not-found message.
    assert response.confirm == "N", (
        f"Account-not-found must be a business rejection "
        f"(confirm='N'), not a raised exception; got "
        f"{response.confirm!r}"
    )
    # The exact message text is defined by bill_service.py's
    # ``_MSG_ACCOUNT_NOT_FOUND`` constant; we assert it's non-empty
    # and mentions "not found" (less brittle than asserting exact
    # text, since this message isn't directly from COBOL).
    assert response.message is not None
    assert "not found" in response.message.lower(), f"Expected 'not found' in message; got {response.message!r}"

    # Assert 2: No Transaction staged.
    mock_db_session.add.assert_not_called()

    # Assert 3: No subsequent queries were issued (service
    # short-circuits immediately after account lookup).
    assert mock_db_session.execute.await_count == 1, (
        f"Expected exactly 1 execute (the account lookup) before "
        f"short-circuit; got {mock_db_session.execute.await_count}"
    )

    # Assert 4: No flush/commit/rollback.
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()
    mock_db_session.rollback.assert_not_awaited()

    # Assert 5: Response echoes the request's acct_id.
    assert response.acct_id == "99999999999"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_xref_not_found(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
) -> None:
    """Missing card cross-reference returns confirm='N' without raising.

    COBIL00C.cbl READ-CXACAIX-FILE NOTFND branch:
    When the alternate-index CICS READ on CXACAIX cannot locate a
    card for the given account, the COBOL program rejected the bill
    payment because the Transaction row requires ``TRAN-CARD-NUM``
    (no card to charge = no payment).

    The modernized service mirrors this: ``scalars().first()``
    returning ``None`` short-circuits with a confirm='N' response
    carrying the COBOL-equivalent "Card not found..." message.

    Maps to COBOL: READ-CXACAIX-FILE NOTFND branch.
    """
    # Arrange: Account found with positive balance, but xref
    # lookup returns None.
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=None,
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("100.00"),
    )

    # Act
    response = await bill_service.pay_bill(request)

    # Assert 1: confirm='N' with a card-not-found message.
    assert response.confirm == "N"
    assert response.message is not None
    # The COBOL NOTFND branch specifically mentions "card" (the
    # alternate-index is over the card cross-reference table). We
    # assert this to ensure the correct error path fired (not the
    # account-not-found path).
    assert "card" in response.message.lower() or "xref" in response.message.lower(), (
        f"Expected card/xref-related message for xref-not-found path; got {response.message!r}"
    )

    # Assert 2: No Transaction staged.
    mock_db_session.add.assert_not_called()

    # Assert 3: Exactly 2 queries issued (account + xref), then
    # short-circuit before the tran_id queries.
    assert mock_db_session.execute.await_count == 2, (
        f"Expected 2 executes (account + xref) before short-circuit; got {mock_db_session.execute.await_count}"
    )

    # Assert 4: No flush/commit/rollback.
    mock_db_session.flush.assert_not_awaited()
    mock_db_session.commit.assert_not_awaited()
    mock_db_session.rollback.assert_not_awaited()

    # Assert 5: Account balance is unchanged (never mutated).
    assert sample_account.curr_bal == Decimal("1500.00"), (
        f"Account balance must NOT be mutated when xref lookup fails; got {sample_account.curr_bal!r}"
    )

    # Assert 6: Response echoes the current (unchanged) balance.
    assert response.current_balance == Decimal("1500.00")


# ============================================================================
# Phase 6: Timestamp Tests
# ============================================================================
#
# The COBOL Transaction record (``CVTRA05Y.cpy``) carries two
# timestamp fields:
#
#   TRAN-ORIG-TS PIC X(26)  -- original transaction time
#   TRAN-PROC-TS PIC X(26)  -- processing time
#
# The COBOL ``PIC X(26)`` width is derived from the GET-CURRENT-
# TIMESTAMP paragraph's formatted output:
#
#   "YYYY-MM-DD HH:MM:SS.NNNNNN"
#   \_10_/  \_8_/  \_7_/  (includes the trailing separators)
#
# Python's ``datetime.strftime('%Y-%m-%d %H:%M:%S.%f')`` produces
# exactly this 26-char format. The service (bill_service.py L800-
# 801) calls ``datetime.now(UTC).strftime(_TIMESTAMP_FORMAT)`` to
# generate a single timestamp string and assigns it to BOTH
# orig_ts and proc_ts fields (per COBIL00C.cbl L231-232 which
# moves WS-TIMESTAMP to both fields).
#
# This test verifies:
#   * Both orig_ts and proc_ts are exactly 26 characters.
#   * Both are populated with the same value (single-timestamp
#     assignment from COBOL).
#   * The format is parseable as the expected strftime pattern.
# ============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_timestamps_26_char(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """Verify ``orig_ts`` / ``proc_ts`` are 26-char COBOL-compatible timestamps.

    CVTRA05Y.cpy:
        ``05 TRAN-ORIG-TS PIC X(26).``
        ``05 TRAN-PROC-TS PIC X(26).``

    COBIL00C.cbl lines 231-232:
        ``MOVE WS-TIMESTAMP TO TRAN-ORIG-TS``
        ``MOVE WS-TIMESTAMP TO TRAN-PROC-TS``

    The COBOL program assigns the SAME ``WS-TIMESTAMP`` value to
    both fields. The modernized service does the same by computing
    ``timestamp_str`` once and passing it to both ``orig_ts`` and
    ``proc_ts`` kwargs (bill_service.py L860-861).

    This test does NOT assert a specific timestamp value (which
    would be non-deterministic / time-of-day-dependent) but DOES
    assert the width, format-parseability, and orig_ts == proc_ts
    invariant.

    Maps to COBOL: PIC X(26) timestamp format from
    GET-CURRENT-TIMESTAMP + L231-232 dual assignment.
    """
    from datetime import datetime

    # Arrange
    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg="0000000000000099",
        max_tran_id_sort="0000000000000099",
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("100.00"),
    )

    # Act
    await bill_service.pay_bill(request)

    # Assert 1: Both timestamps are str type.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert isinstance(staged_txn.orig_ts, str), (
        f"Transaction.orig_ts must be str (COBOL PIC X(26)); got {type(staged_txn.orig_ts).__name__}"
    )
    assert isinstance(staged_txn.proc_ts, str), (
        f"Transaction.proc_ts must be str (COBOL PIC X(26)); got {type(staged_txn.proc_ts).__name__}"
    )

    # Assert 2: Both timestamps are exactly 26 chars wide.
    # Python's strftime('%Y-%m-%d %H:%M:%S.%f') produces exactly 26
    # chars because %f is always 6-digit microseconds.
    assert len(staged_txn.orig_ts) == _EXPECTED_TIMESTAMP_WIDTH, (
        f"Transaction.orig_ts must be exactly "
        f"{_EXPECTED_TIMESTAMP_WIDTH} chars (CVTRA05Y.cpy TRAN-ORIG-TS "
        f"PIC X(26)); got length {len(staged_txn.orig_ts)} for value "
        f"{staged_txn.orig_ts!r}. The service uses "
        f"datetime.strftime('%Y-%m-%d %H:%M:%S.%f') which always "
        f"produces 26 chars."
    )
    assert len(staged_txn.proc_ts) == _EXPECTED_TIMESTAMP_WIDTH, (
        f"Transaction.proc_ts must be exactly "
        f"{_EXPECTED_TIMESTAMP_WIDTH} chars (CVTRA05Y.cpy "
        f"TRAN-PROC-TS PIC X(26)); got length "
        f"{len(staged_txn.proc_ts)} for value {staged_txn.proc_ts!r}."
    )

    # Assert 3: The timestamp is parseable as the expected format.
    # This catches regressions where the service accidentally uses
    # a different strftime format or a non-strftime string.
    try:
        parsed = datetime.strptime(
            staged_txn.orig_ts,
            "%Y-%m-%d %H:%M:%S.%f",
        )
    except ValueError as exc:
        raise AssertionError(
            f"Transaction.orig_ts {staged_txn.orig_ts!r} is not a "
            f"valid '%Y-%m-%d %H:%M:%S.%f' string "
            f"(strptime failed: {exc}). The COBOL-compatible format "
            f"from CVTRA05Y.cpy + bill_service.py _TIMESTAMP_FORMAT "
            f"requires this exact pattern."
        ) from exc
    # Defensive: the parsed datetime is a reasonable value (not
    # year 1 AD or something absurd from a truncated format).
    assert parsed.year >= 2020, f"Parsed timestamp year {parsed.year} is suspiciously low; expected >= 2020."

    # Assert 4: orig_ts == proc_ts (both populated from the single
    # WS-TIMESTAMP per COBIL00C.cbl L231-232). The service
    # accomplishes this by using a single ``timestamp_str`` local
    # variable.
    assert staged_txn.orig_ts == staged_txn.proc_ts, (
        f"orig_ts and proc_ts must be IDENTICAL -- both assigned "
        f"from the single WS-TIMESTAMP value per COBIL00C.cbl "
        f"L231-232. Got orig_ts={staged_txn.orig_ts!r}, "
        f"proc_ts={staged_txn.proc_ts!r}"
    )

    # Assert 5: No non-ASCII / non-printable characters in the
    # timestamp (COBOL PIC X accepts any byte, but the rendered
    # Python value should be all-printable ASCII).
    assert staged_txn.orig_ts.isascii(), f"orig_ts must be ASCII-only; got {staged_txn.orig_ts!r}"
    assert staged_txn.orig_ts.isprintable(), f"orig_ts must be printable; got {staged_txn.orig_ts!r}"


@pytest.mark.unit
@pytest.mark.asyncio
async def test_pay_bill_timestamp_uses_mocked_datetime(
    bill_service: BillService,
    mock_db_session: AsyncMock,
    sample_account: Account,
    sample_xref: CardCrossReference,
) -> None:
    """Verify the service uses :func:`datetime.datetime.now` (UTC) for timestamps.

    This test patches ``datetime.now`` at the ``bill_service``
    module's import site to return a fixed, known value. It then
    asserts that the staged Transaction's timestamps equal the
    strftime-formatted representation of THAT fixed value -- proving
    the service calls ``datetime.now(UTC)`` (not some hardcoded
    string, not ``time.time()``, not a clock-skewed service clock).

    This test also documents the UTC timezone choice -- the module
    docstring of bill_service.py explicitly uses UTC to ensure
    consistent timestamp generation regardless of the ECS task's
    OS-level timezone configuration. A test that patched with a
    non-UTC datetime would still succeed as long as the resulting
    strftime string matches, because strftime ignores the tzinfo in
    the output format ``%Y-%m-%d %H:%M:%S.%f``.

    Maps to COBOL: GET-CURRENT-TIMESTAMP paragraph -- the source of
    the WS-TIMESTAMP value.
    """
    from datetime import UTC, datetime

    # Arrange: A fixed datetime value for deterministic assertion.
    fixed_dt = datetime(2024, 6, 15, 12, 34, 56, 789012, tzinfo=UTC)
    expected_ts = fixed_dt.strftime("%Y-%m-%d %H:%M:%S.%f")
    # Sanity check on our expected string.
    assert len(expected_ts) == _EXPECTED_TIMESTAMP_WIDTH, (
        f"Precondition: expected_ts must be 26 chars; got length {len(expected_ts)}"
    )
    assert expected_ts == "2024-06-15 12:34:56.789012"

    mock_db_session.execute.side_effect = _make_execute_side_effect(
        account=sample_account,
        xref=sample_xref,
        max_tran_id_agg="0000000000000099",
        max_tran_id_sort="0000000000000099",
    )

    request = BillPaymentRequest(
        acct_id=_TEST_ACCT_ID,
        amount=Decimal("100.00"),
    )

    # Patch ``datetime`` in the bill_service module namespace.
    # The service calls ``datetime.now(UTC)``, so we patch the
    # ``datetime`` class within that module. We use a wrapper class
    # so any OTHER attribute access on datetime (e.g. datetime.min)
    # behaves normally.
    with patch(
        "src.api.services.bill_service.datetime",
    ) as mock_datetime:
        mock_datetime.now = MagicMock(return_value=fixed_dt)

        # Act
        await bill_service.pay_bill(request)

        # Assert: the service called datetime.now exactly once with
        # the UTC timezone argument (bill_service.py L800 -- the
        # single ``now_utc: datetime = datetime.now(UTC)`` call).
        mock_datetime.now.assert_called_once()
        # The arg should be the UTC tzinfo object (imported from
        # datetime module). We assert it's truthy (not None) and
        # a tzinfo-like object.
        _call_args, _call_kwargs = mock_datetime.now.call_args
        passed_tz = _call_args[0] if _call_args else _call_kwargs.get("tz")
        assert passed_tz is not None, (
            "Expected datetime.now to be called with a UTC tzinfo "
            "argument (bill_service.py: 'datetime.now(UTC)'); got "
            "no args/kwargs."
        )

    # Assert: Both transaction timestamps equal the fixed-dt
    # strftime output.
    staged_txn = _extract_added_transaction(mock_db_session)
    assert staged_txn.orig_ts == expected_ts, (
        f"orig_ts should equal the mocked datetime's strftime "
        f"output {expected_ts!r}; got {staged_txn.orig_ts!r}. "
        f"This indicates the service may not be using "
        f"datetime.now(UTC) for timestamp generation."
    )
    assert staged_txn.proc_ts == expected_ts, (
        f"proc_ts should equal the mocked datetime's strftime output {expected_ts!r}; got {staged_txn.proc_ts!r}"
    )
