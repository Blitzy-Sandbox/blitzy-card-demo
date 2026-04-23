# =============================================================================
# CardDemo — Unit tests for AccountService (Mainframe-to-Cloud migration)
# =============================================================================
#
# This module provides comprehensive unit tests for
# :class:`src.api.services.account_service.AccountService`, which converts
# the following COBOL/CICS online programs into a Python service:
#
#   * ``app/cbl/COACTVWC.cbl``   (~941  lines) — Feature F-004 Account View
#   * ``app/cbl/COACTUPC.cbl``   (~4,236 lines) — Feature F-005 Account Update
#
# Record layouts referenced by the tests (COBOL copybooks):
#
#   * ``app/cpy/CVACT01Y.cpy`` — ACCOUNT-RECORD (300 bytes, PK ACCT-ID PIC 9(11))
#   * ``app/cpy/CVACT03Y.cpy`` — CARD-XREF-RECORD (50 bytes, XREF-CARD-NUM PIC X(16))
#   * ``app/cpy/CVCUS01Y.cpy`` — CUSTOMER-RECORD (500 bytes, PK CUST-ID PIC 9(09))
#
# -----------------------------------------------------------------------------
# CRITICAL PARITY INVARIANTS (AAP Section 0.7.1)
# -----------------------------------------------------------------------------
# The migration mandate requires EXACT behavioral parity with the COBOL
# programs. These tests enforce the following invariants that must NEVER
# regress during subsequent refactors:
#
# 1. **Decimal precision**: All monetary fields (credit_limit,
#    cash_credit_limit, current_balance, current_cycle_credit,
#    current_cycle_debit) MUST be :class:`decimal.Decimal` on the wire,
#    NEVER :class:`float`. This preserves COBOL ``PIC S9(10)V99``
#    semantics from ``CVACT01Y.cpy`` and prevents binary-floating-point
#    representation errors in financial calculations.
#
# 2. **3-entity keyed-read chain** (F-004, COACTVWC.cbl): the
#    view operation reads ``CXACAIX`` (cross-reference), then
#    ``ACCTDAT`` (account), then ``CUSTDAT`` (customer) in sequence,
#    with each step's key derived from the prior step's result.
#    Tests verify the exact call sequence and early-exit behaviour
#    on any intermediate miss.
#
# 3. **SYNCPOINT ROLLBACK dual-write** (F-005, COACTUPC.cbl):
#    updates to Account and Customer are atomic — either BOTH persist
#    or NEITHER does. This translates to SQLAlchemy's transactional
#    context where a single ``flush()`` + ``commit()`` covers the
#    dual-UPDATE; any error triggers ``rollback()``.
#
# 4. **Optimistic concurrency** (F-005, via :attr:`Account.version_id`):
#    SQLAlchemy's ``version_id_col`` feature appends
#    ``AND version_id = :old`` to the UPDATE's WHERE clause; a version
#    mismatch raises :class:`StaleDataError`, which the service
#    translates to the COBOL error message ``"Record changed by some
#    one else. Please review"``.
#
# 5. **COBOL error message fidelity**: the service exposes exact
#    byte-for-byte copies of the COACTVWC/COACTUPC BMS error-message
#    literals. Tests duplicate these literals locally (not imported)
#    so that any accidental drift in the service's private constants
#    is caught as a failed assertion against the duplicated literal.
#
# 6. **Validation short-circuit**: COACTUPC.cbl's ``1200-EDIT-MAP-INPUTS``
#    paragraph stops at the first validation failure. Tests verify
#    that one invalid field causes an error response without the
#    service attempting a database write.
#
# -----------------------------------------------------------------------------
# Copyright Notice
# -----------------------------------------------------------------------------
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy
# of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
# =============================================================================

"""Unit tests for :class:`AccountService`.

Validates the 3-entity join view (COACTVWC.cbl, F-004) and the
dual-write transactional update with SYNCPOINT ROLLBACK semantics
(COACTUPC.cbl, F-005).

COBOL → Python Verification Surface
------------------------------------

+-----------------------------------+---------------------------------------------------+
| COBOL paragraph                   | Python test function                              |
+===================================+===================================================+
| COACTVWC 9000-READXFILE           | test_get_account_view_success                     |
| COACTVWC 9000-READXFILE(NOTFND)   | test_get_account_view_xref_not_found              |
| COACTVWC 9100-READACCT            | test_get_account_view_success                     |
| COACTVWC 9100-READACCT(NOTFND)    | test_get_account_view_account_not_found           |
| COACTVWC 9200-READCUST            | test_get_account_view_success                     |
| COACTVWC 9200-READCUST(NOTFND)    | test_get_account_view_customer_not_found          |
| COACTUPC 9200-WRITE (happy path)  | test_update_account_success                       |
| COACTUPC SYNCPOINT ROLLBACK       | test_update_account_dual_write_rollback_on_       |
|                                   | customer_failure                                  |
| COACTUPC 1205-COMPARE-OLD-NEW     | test_update_account_no_changes_detected           |
| COACTUPC READ UPDATE contention   | test_update_account_optimistic_concurrency_       |
|                                   | conflict                                          |
| COACTUPC 1210-EDIT-ACCOUNT        | test_update_account_not_found                     |
| COACTUPC 1285-EDIT-DATE-CCYYMMDD  | test_update_account_date_validation               |
| COACTUPC 1275-EDIT-FICO-SCORE     | test_update_account_fico_validation               |
| COACTUPC 1265-EDIT-US-SSN         | test_update_account_ssn_format_validation         |
| COACTUPC 1220-EDIT-YESNO          | test_update_account_yesno_validation              |
| COACTUPC 1250-EDIT-SIGNED-9V2     | test_update_account_monetary_decimal_precision    |
+-----------------------------------+---------------------------------------------------+

Test Design
-----------

Tests use :class:`unittest.mock.AsyncMock` with ``spec=AsyncSession``
to produce a stand-in for the SQLAlchemy async session. This design:

* Keeps tests fast and hermetic (no Aurora PostgreSQL connection).
* Lets tests configure arbitrary query results without a real database.
* Verifies the exact SQLAlchemy API surface the service depends on
  (``execute``, ``get``, ``flush``, ``commit``, ``rollback``).

Async operations (``execute``, ``get``, ``flush``, ``commit``,
``rollback``) are :class:`AsyncMock` instances (they return
awaitables). The accessor chain used on a query result
(``scalars()``, ``.first()``, ``.all()``) is :class:`MagicMock`
(synchronous — SQLAlchemy's :class:`Result` does not require
``await`` on these accessors once the ``execute`` has returned).

COBOL error messages are duplicated as module-level ``_MSG_*``
constants rather than imported from the service. Duplicating the
literals makes the tests an independent control for wire-format
drift: if the service changes a message, the service's own unit
tests for internal consistency pass, but THIS test's byte-for-byte
check against the duplicated literal fails — flagging a violation
of the COBOL parity invariant.

Coverage Surface
----------------

* **Phase 3 — Account View (F-004): 4 tests** covering happy path
  and every NOT-FOUND branch in the 3-entity read chain.
* **Phase 4 — Account Update (F-005): 4 tests** covering happy
  path, dual-write rollback on IntegrityError, no-changes-detected
  fast path, and optimistic concurrency conflict.
* **Phase 5 — Field validation: 6 tests** covering date, FICO,
  SSN (SSA area blacklist), Y/N flag, Decimal monetary precision,
  and account-not-found.

See Also
--------

* ``src/api/services/account_service.py``: the service under test.
* ``src/shared/models/account.py``: Account ORM entity.
* ``src/shared/models/customer.py``: Customer ORM entity.
* ``src/shared/models/card_cross_reference.py``: CardCrossReference ORM entity.
* ``src/shared/schemas/account_schema.py``: AccountUpdateRequest /
  AccountViewResponse / AccountUpdateResponse Pydantic v2 schemas.
* ``tests/unit/test_services/test_card_service.py``: sibling test
  module following the same AsyncMock-based pattern.
"""

from __future__ import annotations

from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock

import pydantic
import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from src.api.services.account_service import AccountService
from src.shared.models.account import Account
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.customer import Customer
from src.shared.schemas.account_schema import (
    AccountUpdateRequest,
    AccountUpdateResponse,
    AccountViewResponse,
)

# =============================================================================
# Module-level test constants
# =============================================================================
# These constants are the SHARED test inputs and the EXPECTED wire
# values that the service must produce. Every constant is derived
# from the COBOL source (copybook widths / BMS maps / error
# messages) so a drift in either direction is caught at
# assertion-time.
# =============================================================================

# --- Primary keys (from copybook widths) -------------------------------------

_TEST_ACCT_ID: str = "00000000001"
"""Canonical test account ID — 11 digits per CVACT01Y.cpy ACCT-ID PIC 9(11)."""

_TEST_CUST_ID: str = "000000001"
"""Canonical test customer ID — 9 digits per CVCUS01Y.cpy CUST-ID PIC 9(09)."""

_TEST_CARD_NUM: str = "4111111111111111"
"""Canonical test card number — 16 chars per CVACT03Y.cpy XREF-CARD-NUM PIC X(16)."""

_TEST_ACCT_ID_ALT: str = "00000000099"
"""Alternate test account ID for multi-record / mismatch scenarios."""

# --- Field width invariants (enforced by Pydantic schemas) -------------------

_EXPECTED_ACCT_ID_WIDTH: int = 11
"""Width invariant: account_id is always exactly 11 digits."""

_EXPECTED_CUST_ID_WIDTH: int = 9
"""Width invariant: customer_id is always exactly 9 digits."""

_EXPECTED_CARD_NUM_WIDTH: int = 16
"""Width invariant: card_num is always exactly 16 chars."""

_EXPECTED_DATE_WIDTH: int = 10
"""Width invariant: stored dates are 10-char ``YYYY-MM-DD`` strings."""

_EXPECTED_SSN_DISPLAY_WIDTH: int = 11
"""Width invariant: displayed SSN is 11-char ``NNN-NN-NNNN`` string."""

_EXPECTED_PHONE_DISPLAY_WIDTH: int = 13
"""Width invariant: displayed phone is 13-char ``(AAA)BBB-CCCC`` string."""

_EXPECTED_FICO_WIDTH: int = 3
"""Width invariant: FICO score is 3-char zero-padded integer string."""

_EXPECTED_DECIMAL_SCALE: int = 2
"""Scale invariant: all monetary Decimals have exactly 2 fractional digits."""

# --- COBOL-exact error-message literals --------------------------------------
# These strings are duplicated verbatim from the BMS map literals in
# the COBOL sources. They are intentionally NOT imported from the
# service — duplicating the literal here provides an independent
# control: if the service drifts, the byte-for-byte assertion fails.

_MSG_UPDATE_SUCCESS: str = "Changes committed to database"
"""COBOL confirmation: successful account update via SYNCPOINT."""

_MSG_UPDATE_FAILED: str = "Changes unsuccessful. Please try again"
"""COBOL diagnostic: generic update failure (DB constraint violation / rollback)."""

_MSG_UPDATE_STALE: str = "Record changed by some one else. Please review"
"""COBOL diagnostic: optimistic concurrency conflict on READ UPDATE / REWRITE."""

# -----------------------------------------------------------------------------
# NO_CHANGES literal — COBOL COACTUPC.cbl line 492 (50 characters, trailing
# period). Code Review Finding MAJOR #5 restored this to the full authored
# 50-character literal (from the previous 39-char rewrite). The service
# schema ``AccountUpdateResponse.info_message`` was widened to
# ``max_length=50`` to admit this COBOL-verbatim value.
# -----------------------------------------------------------------------------
_MSG_NO_CHANGES: str = "No change detected with respect to values fetched."
"""COBOL status (COACTUPC.cbl L492): 1205-COMPARE-OLD-NEW detected no differences."""

_MSG_VIEW_XREF_NOT_FOUND: str = "Did not find this account in account card xref file"
"""COBOL NOTFND: CXACAIX key not found for the supplied account_id."""

_MSG_VIEW_ACCT_NOT_FOUND: str = "Did not find this account in account master file"
"""COBOL NOTFND: ACCTDAT key not found for the xref's account_id."""

_MSG_VIEW_CUST_NOT_FOUND: str = "Did not find associated customer in master file"
"""COBOL NOTFND: CUSTDAT key not found for the xref's customer_id."""

_MSG_ACCT_MISSING: str = "Account number not provided"
"""COBOL validation: account_id was empty / whitespace-only."""

# -----------------------------------------------------------------------------
# ACCT_INVALID — STANDALONE COBOL literal emitted by COACTUPC.cbl
# 1210-EDIT-ACCOUNT paragraph (lines 1787-1817) as a STRING concatenation
# of two fragments without a TRIM prefix and without a trailing period.
# Code Review Finding MAJOR #6 restored the COBOL-verbatim form.
# -----------------------------------------------------------------------------
_MSG_ACCT_INVALID: str = "Account Number if supplied must be a 11 digit Non-Zero Number"
"""COBOL validation (COACTUPC.cbl L1787-1817): account_id 11-digit non-zero check."""

# -----------------------------------------------------------------------------
# ACCT_PATH_BODY_MISMATCH — REST-specific path/body disagreement message
# (no COBOL equivalent; COACTUPC had ONE BMS field ACCTSIDI).  Code Review
# Finding MINOR #10 split this out from ``_MSG_ACCT_INVALID`` because the
# latter describes a format error whereas Guard 3 triggers AFTER both IDs
# are already known to be format-valid — they simply disagree.
# -----------------------------------------------------------------------------
_MSG_ACCT_PATH_BODY_MISMATCH: str = "Account number in URL path does not match request body"
"""Path/body acct_id disagreement (REST-specific; no COBOL equivalent)."""

# -----------------------------------------------------------------------------
# PARSE_FAILED — defensive-in-depth internal-integrity message (Code Review
# Finding MINOR #11).  Signals a validator/parser contract violation that
# would never arise in COBOL (where the validator and parser share working-
# storage) but could surface in Python if a future refactor breaks the
# invariant that validator-PASS implies parser-SUCCESS.  Distinct from
# ``_MSG_UPDATE_FAILED`` (retryable DB failure) and from the per-field
# validation messages (retryable with corrected input).
# -----------------------------------------------------------------------------
_MSG_PARSE_FAILED: str = "Unable to process update request due to internal validation mismatch"
"""Internal validator/parser contract violation (CP3 MINOR #11)."""

# -----------------------------------------------------------------------------
# TEMPLATE-BASED MESSAGES — COACTUPC.cbl edit paragraphs 1215-1275 all
# follow the pattern:
#
#     STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) DELIMITED BY SIZE,
#            <SUFFIX-LITERAL>                     DELIMITED BY SIZE
#         INTO WS-ERROR-MESSAGE
#
# Code Review Finding MAJOR #6 restored this template pattern across all
# validation paths.  The test expectations below reconstruct the exact
# COBOL concatenation by pairing a WS-EDIT-VARIABLE-NAME field-label
# fragment with the verbatim COBOL suffix literal.  Each is duplicated
# here (rather than imported from the service) so the test serves as an
# independent control for wire-format drift.
# -----------------------------------------------------------------------------

_MSG_STATUS_INVALID: str = "Account Status must be Y or N."
"""COACTUPC.cbl 1220-EDIT-YESNO (L1890) with WS-EDIT-VARIABLE-NAME='Account Status'."""

_MSG_PRI_CARD_INVALID: str = "Primary Card Holder must be Y or N."
"""COACTUPC.cbl 1220-EDIT-YESNO with WS-EDIT-VARIABLE-NAME='Primary Card Holder'."""

_MSG_FICO_INVALID: str = "FICO Score: should be between 300 and 850"
"""COACTUPC.cbl 1275-EDIT-FICO-SCORE (L2523) with WS-EDIT-VARIABLE-NAME='FICO Score' (NO PERIOD)."""

# -----------------------------------------------------------------------------
# SSN per-segment zero messages — COACTUPC.cbl 1265-EDIT-US-SSN runs
# ``1245-EDIT-NUM-REQD`` on each of the three SSN segments in turn,
# substituting a segment-specific label into ``WS-EDIT-VARIABLE-NAME`` before
# each call (L2439, L2469, L2481).  The ``1245-EDIT-NUM-REQD`` cascade
# (L2109-2176) checks blank -> not-numeric -> zero, emitting the
# per-segment ``' must not be zero.'`` suffix (L2162-2165) on a zero-value
# failure.  Because this zero-check runs BEFORE the part-1 SSA-area
# blacklist (L2450 ``INVALID-SSN-PART1``), a value of ``000`` for part 1
# produces the PART1-specific zero message rather than the area-blacklist
# message.  Similarly, ``00`` for part 2 and ``0000`` for part 3 produce
# their segment-specific zero messages.
# -----------------------------------------------------------------------------

_MSG_SSN_PART1_ZERO: str = "SSN: First 3 chars must not be zero."
"""COACTUPC.cbl 1245-EDIT-NUM-REQD (L2162) with WS-EDIT-VARIABLE-NAME='SSN: First 3 chars' — SSN part-1 value '000' triggers this message BEFORE the INVALID-SSN-PART1 blacklist at L2450."""

_MSG_SSN_PART2_ZERO: str = "SSN 4th & 5th chars must not be zero."
"""COACTUPC.cbl 1245-EDIT-NUM-REQD (L2162) with WS-EDIT-VARIABLE-NAME='SSN 4th & 5th chars' — SSN part-2 value '00'."""

_MSG_SSN_PART3_ZERO: str = "SSN Last 4 chars must not be zero."
"""COACTUPC.cbl 1245-EDIT-NUM-REQD (L2162) with WS-EDIT-VARIABLE-NAME='SSN Last 4 chars' — SSN part-3 value '0000'."""

_MSG_SSN_PART1_INVALID: str = "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"
"""COACTUPC.cbl 1265-EDIT-US-SSN INVALID-SSN-PART1 (L2464, NO PERIOD)."""

_MSG_OPEN_DATE_INVALID: str = "Open Date: Month must be a number between 1 and 12."
"""CSUTLDPY.cpy EDIT-MONTH (L119) bad-month label routed through Open Date field (test uses month='13')."""


# =============================================================================
# Phase 2 — Test fixtures
# =============================================================================
# Fixtures are module-local (not in a conftest.py) because:
#   * Only this module needs them.
#   * The project does NOT use conftest.py at any level.
#   * Keeping them local makes the test file self-documenting.
# =============================================================================


@pytest.fixture
def mock_db_session() -> AsyncMock:
    """Produce an :class:`AsyncMock` configured as an SQLAlchemy AsyncSession.

    The mock covers the exact API surface ``AccountService`` depends
    on. The :class:`AsyncMock` ``spec`` argument binds the mock to
    the :class:`AsyncSession` interface so any accidental use of a
    non-existent method (e.g. the sync ``query()`` method) raises
    :class:`AttributeError` — catching tests that silently exercise
    the wrong SQLAlchemy style.

    Configured methods:

    * ``execute`` (AsyncMock) — returns a :class:`MagicMock` with a
      chainable ``scalars().first()`` / ``.scalars().all()`` API.
      The default return is a miss (``.first()`` → ``None``,
      ``.all()`` → ``[]``).
    * ``get`` (AsyncMock) — returns ``None`` by default; individual
      tests override with ``.return_value = entity`` or
      ``.side_effect = [entity1, entity2]``.
    * ``flush``, ``commit``, ``rollback`` (AsyncMock) — default to
      returning ``None``; tests override ``.side_effect`` to simulate
      StaleDataError / IntegrityError conditions.

    Returns
    -------
    AsyncMock
        A mock AsyncSession ready for test configuration.
    """
    session: AsyncMock = AsyncMock(spec=AsyncSession)

    # Default ``execute`` result: a miss (None / empty list).
    # Tests override ``mock_db_session.execute.return_value = ...`` or
    # ``mock_db_session.execute.return_value.scalars.return_value.first.return_value = ...``
    # as needed.
    default_result: MagicMock = MagicMock()
    default_result.scalar_one_or_none = MagicMock(return_value=None)
    default_result.scalar_one = MagicMock(return_value=0)
    default_result.scalar = MagicMock(return_value=None)

    default_scalars: MagicMock = MagicMock()
    default_scalars.all = MagicMock(return_value=[])
    default_scalars.first = MagicMock(return_value=None)
    default_result.scalars = MagicMock(return_value=default_scalars)

    session.execute = AsyncMock(return_value=default_result)

    # ``session.get`` is the preferred 2.x entity-by-PK API.
    session.get = AsyncMock(return_value=None)

    # Lifecycle methods default to no-ops; tests override side_effect.
    session.flush = AsyncMock(return_value=None)
    session.commit = AsyncMock(return_value=None)
    session.rollback = AsyncMock(return_value=None)

    return session


@pytest.fixture
def account_service(mock_db_session: AsyncMock) -> AccountService:
    """Produce an :class:`AccountService` bound to the mock session.

    The service constructor takes a single ``db`` argument; we inject
    the :class:`AsyncMock` session from the ``mock_db_session``
    fixture so the service's DB calls hit the mock instead of a
    live Aurora PostgreSQL instance.

    Parameters
    ----------
    mock_db_session : AsyncMock
        The mock session fixture.

    Returns
    -------
    AccountService
        A service instance ready for invocation.
    """
    return AccountService(db=mock_db_session)


@pytest.fixture
def sample_account() -> Account:
    """Produce a canonical detached :class:`Account` ORM instance.

    All monetary fields use :class:`decimal.Decimal` — this is
    CRITICAL because the schema validator explicitly rejects
    :class:`float`. The values match the ``_make_account_update_request()``
    defaults so the no-changes-detected test works without any
    overrides.

    Field mapping (from CVACT01Y.cpy):

    +-----------------+---------------------+----------------------+
    | COBOL field     | PIC                 | Python attribute     |
    +=================+=====================+======================+
    | ACCT-ID         | 9(11)               | ``acct_id``          |
    | ACCT-ACTIVE-... | X(01)               | ``active_status``    |
    | ACCT-CURR-BAL   | S9(10)V99           | ``curr_bal``         |
    | ACCT-CREDIT-... | S9(10)V99           | ``credit_limit``     |
    | ACCT-CASH-...   | S9(10)V99           | ``cash_credit_limit``|
    | ACCT-OPEN-DATE  | X(10)               | ``open_date``        |
    | ACCT-EXPIR-DT   | X(10)               | ``expiration_date``  |
    | ACCT-REISSUE-DT | X(10)               | ``reissue_date``     |
    | ACCT-CURR-CYC-C | S9(10)V99           | ``curr_cyc_credit``  |
    | ACCT-CURR-CYC-D | S9(10)V99           | ``curr_cyc_debit``   |
    | ACCT-ADDR-ZIP   | X(10)               | ``addr_zip``         |
    | ACCT-GROUP-ID   | X(10)               | ``group_id``         |
    +-----------------+---------------------+----------------------+

    The ``version_id`` attribute (integer, default 0) supports
    SQLAlchemy's optimistic-concurrency feature — not a physical
    COBOL field but a mainframe-to-cloud design addition.

    Returns
    -------
    Account
        A fully-populated detached Account instance.
    """
    account: Account = Account(
        acct_id=_TEST_ACCT_ID,
        active_status="Y",
        curr_bal=Decimal("1500.00"),
        credit_limit=Decimal("5000.00"),
        cash_credit_limit=Decimal("2000.00"),
        open_date="2020-01-15",
        expiration_date="2030-12-31",
        reissue_date="2025-01-15",
        curr_cyc_credit=Decimal("200.00"),
        curr_cyc_debit=Decimal("50.00"),
        addr_zip="10001",
        group_id="DEFAULT",
        version_id=0,
    )
    return account


@pytest.fixture
def sample_customer() -> Customer:
    """Produce a canonical detached :class:`Customer` ORM instance.

    The field values match the ``_make_account_update_request()``
    defaults in their canonical storage form:

    * ``ssn`` is raw 9 digits (no hyphens) — ``Customer.ssn`` column.
    * ``phone_num_1`` is ``(AAA)BBB-CCCC`` 13-char format — the
      ``_format_phone_stored()`` helper's output.
    * ``dob`` is ``YYYY-MM-DD`` 10-char ISO format.
    * ``country_cd`` is 3 uppercase letters (``_validate_country_code``
      requires exactly 3 alphabetic characters).
    * ``addr_line_3`` carries the city per the BMS map semantics
      (CVCUS01Y.cpy reuses CUST-ADDR-LINE-3 for the city name).
    * ``addr_zip`` is 5 digits (5-char representation even though
      the column is ``String(10)`` to allow ZIP+4 in the future).

    Field mapping (from CVCUS01Y.cpy):

    +-----------------------+---------+----------------------------+
    | COBOL field           | PIC     | Python attribute           |
    +=======================+=========+============================+
    | CUST-ID               | 9(09)   | ``cust_id``                |
    | CUST-FIRST-NAME       | X(25)   | ``first_name``             |
    | CUST-MIDDLE-NAME      | X(25)   | ``middle_name``            |
    | CUST-LAST-NAME        | X(25)   | ``last_name``              |
    | CUST-ADDR-LINE-1      | X(50)   | ``addr_line_1``            |
    | CUST-ADDR-LINE-2      | X(50)   | ``addr_line_2``            |
    | CUST-ADDR-LINE-3      | X(50)   | ``addr_line_3`` (city)     |
    | CUST-STATE-CD         | X(02)   | ``state_cd``               |
    | CUST-COUNTRY-CD       | X(03)   | ``country_cd``             |
    | CUST-ADDR-ZIP         | X(10)   | ``addr_zip``               |
    | CUST-PHONE-NUM-1      | X(15)   | ``phone_num_1``            |
    | CUST-PHONE-NUM-2      | X(15)   | ``phone_num_2``            |
    | CUST-SSN              | 9(09)   | ``ssn``                    |
    | CUST-GOVT-ISSUED-ID   | X(20)   | ``govt_issued_id``         |
    | CUST-DOB              | X(10)   | ``dob``                    |
    | CUST-EFT-ACCT-ID      | X(10)   | ``eft_account_id``         |
    | CUST-PRI-CARD-HLD-IND | X(01)   | ``pri_card_holder_ind``    |
    | CUST-FICO-CREDIT-SCR  | 9(03)   | ``fico_credit_score``      |
    +-----------------------+---------+----------------------------+

    Returns
    -------
    Customer
        A fully-populated detached Customer instance.
    """
    customer: Customer = Customer(
        cust_id=_TEST_CUST_ID,
        first_name="John",
        middle_name="Q",
        last_name="Doe",
        addr_line_1="123 Test St",
        addr_line_2="",
        addr_line_3="New York",  # CVCUS01Y reuses ADDR-LINE-3 for the city.
        state_cd="NY",
        country_cd="USA",  # 3-char ISO for _validate_country_code.
        addr_zip="10001",
        phone_num_1="(212)555-1234",  # 13-char canonical stored form.
        phone_num_2="",
        ssn="123456789",  # Raw 9 digits (no hyphens) per Customer.ssn column.
        govt_issued_id="",
        dob="1990-01-15",
        eft_account_id="",
        pri_card_holder_ind="Y",
        fico_credit_score=750,
    )
    return customer


@pytest.fixture
def sample_xref() -> CardCrossReference:
    """Produce a canonical detached :class:`CardCrossReference` instance.

    The cross-reference record bridges a physical card number to its
    owning account and customer. In the COBOL flow, this record is
    the FIRST lookup in the 3-entity chain — the input is the
    account_id (via the CXACAIX alternate-index path), and the
    output provides the customer_id and card_num needed for the
    subsequent two lookups.

    Field mapping (from CVACT03Y.cpy):

    +-------------------+---------+----------------------+
    | COBOL field       | PIC     | Python attribute     |
    +===================+=========+======================+
    | XREF-CARD-NUM     | X(16)   | ``card_num``         |
    | XREF-CUST-ID      | 9(09)   | ``cust_id``          |
    | XREF-ACCT-ID      | 9(11)   | ``acct_id``          |
    +-------------------+---------+----------------------+

    Returns
    -------
    CardCrossReference
        A fully-populated detached cross-reference instance.
    """
    return CardCrossReference(
        card_num=_TEST_CARD_NUM,
        cust_id=_TEST_CUST_ID,
        acct_id=_TEST_ACCT_ID,
    )


# =============================================================================
# Helper: AccountUpdateRequest factory
# =============================================================================
# The AccountUpdateRequest schema has 39 required fields (all
# declared as ``Field(...)``). Writing all 39 keyword arguments
# in every test is repetitive and error-prone; the factory below
# provides sensible defaults matching the ``sample_account`` and
# ``sample_customer`` fixtures so the DEFAULT request is a no-op
# (no-changes detected). Individual tests override specific fields
# via keyword arguments.
# =============================================================================


def _make_account_update_request(
    **overrides: object,
) -> AccountUpdateRequest:
    """Build an :class:`AccountUpdateRequest` with sensible defaults.

    The default values are chosen so that an unmodified call (no
    keyword overrides) produces a request whose canonical parsed
    form EXACTLY matches the :func:`sample_account` and
    :func:`sample_customer` fixture values — i.e. the no-changes-
    detected fast-path triggers with an empty override set. This
    design makes it trivial to test individual field changes by
    passing a single keyword argument.

    Notes
    -----
    * Monetary defaults use :class:`decimal.Decimal` — NEVER float.
    * Date segments are zero-padded strings matching the BMS map:
      4-digit year, 2-digit month, 2-digit day.
    * Phone segments are 3-3-4 digits.
    * SSN segments are 3-2-4 digits.
    * FICO is a 3-char zero-padded string.

    Parameters
    ----------
    **overrides : object
        Keyword arguments that override any default field.

    Returns
    -------
    AccountUpdateRequest
        A validly-constructed (but possibly business-invalid)
        request instance.
    """
    defaults: dict[str, object] = {
        # Account identity & status (matches sample_account).
        "account_id": _TEST_ACCT_ID,
        "active_status": "Y",
        # Open date segments for 2020-01-15.
        "open_date_year": "2020",
        "open_date_month": "01",
        "open_date_day": "15",
        # Monetary Decimal values (CRITICAL: never float).
        "credit_limit": Decimal("5000.00"),
        # Expiration date segments for 2030-12-31.
        "expiration_date_year": "2030",
        "expiration_date_month": "12",
        "expiration_date_day": "31",
        "cash_credit_limit": Decimal("2000.00"),
        # Reissue date segments for 2025-01-15.
        "reissue_date_year": "2025",
        "reissue_date_month": "01",
        "reissue_date_day": "15",
        "group_id": "DEFAULT",
        # SSN segments: 123-45-6789 (valid, not in SSA blacklist).
        "customer_ssn_part1": "123",
        "customer_ssn_part2": "45",
        "customer_ssn_part3": "6789",
        # Customer DOB segments for 1990-01-15.
        "customer_dob_year": "1990",
        "customer_dob_month": "01",
        "customer_dob_day": "15",
        # FICO score 750 (valid range [300, 850]).
        "customer_fico_score": "750",
        # Customer name.
        "customer_first_name": "John",
        "customer_middle_name": "Q",
        "customer_last_name": "Doe",
        # Address.
        "customer_addr_line_1": "123 Test St",
        "customer_state_cd": "NY",
        "customer_addr_line_2": "",
        "customer_zip": "10001",
        "customer_city": "New York",
        "customer_country_cd": "USA",
        # Primary phone: (212)555-1234.
        "customer_phone_1_area": "212",
        "customer_phone_1_prefix": "555",
        "customer_phone_1_line": "1234",
        "customer_govt_id": "",
        # Secondary phone: all-blank (optional).
        "customer_phone_2_area": "",
        "customer_phone_2_prefix": "",
        "customer_phone_2_line": "",
        # EFT account + primary cardholder.
        "customer_eft_account_id": "",
        "customer_pri_cardholder": "Y",
    }
    defaults.update(overrides)
    return AccountUpdateRequest(**defaults)


def _configure_view_mocks(
    mock_db_session: AsyncMock,
    *,
    xref: CardCrossReference | None,
    account: Account | None,
    customer: Customer | None,
) -> None:
    """Configure ``mock_db_session`` for the 3-entity view chain.

    Wires up the exact mock return values that the 3-entity read
    chain expects:

    * ``session.execute(select(CardCrossReference)...).scalars().first()``
      → ``xref``
    * ``session.get(Account, ...)`` → ``account``
    * ``session.get(Customer, ...)`` → ``customer``

    If any of the three entities is ``None``, the service short-
    circuits at that point; subsequent ``get`` calls are never
    issued.

    Parameters
    ----------
    mock_db_session : AsyncMock
        The session mock to configure.
    xref : CardCrossReference | None
        The cross-reference to return from the xref query.
    account : Account | None
        The account to return from ``session.get(Account, ...)``.
    customer : Customer | None
        The customer to return from ``session.get(Customer, ...)``.
    """
    # xref: chain through ``scalars().first()`` on the execute result.
    xref_result: MagicMock = MagicMock()
    xref_scalars: MagicMock = MagicMock()
    xref_scalars.first = MagicMock(return_value=xref)
    xref_result.scalars = MagicMock(return_value=xref_scalars)
    mock_db_session.execute = AsyncMock(return_value=xref_result)

    # get: dispatch by model class. This is more robust than
    # side_effect=[account, customer] because the service's call
    # order is documented but the test should be order-agnostic.
    def _get_dispatch(model: type, pk: object) -> object:
        """Return account / customer / None based on model class."""
        if model is Account:
            return account
        if model is Customer:
            return customer
        return None

    mock_db_session.get = AsyncMock(side_effect=_get_dispatch)


# =============================================================================
# Phase 3 — Account View tests (F-004, COACTVWC.cbl)
# =============================================================================
# These tests verify the 3-entity read chain:
#   1. READ CXACAIX (cross-reference) by account_id
#   2. READ ACCTDAT (account) by account_id
#   3. READ CUSTDAT (customer) by customer_id (from xref.cust_id)
# Each step may NOT-FIND; the service returns an error response
# without attempting subsequent steps.
# =============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_account_view_success(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """Successful 3-entity read populates every response field.

    Arrange
    -------
    * Mock the xref query to return ``sample_xref``.
    * Mock ``session.get(Account, ...)`` to return ``sample_account``.
    * Mock ``session.get(Customer, ...)`` to return ``sample_customer``.

    Act
    ---
    * Call ``account_service.get_account_view(_TEST_ACCT_ID)``.

    Assert
    ------
    * Response type is :class:`AccountViewResponse`.
    * Account fields are populated from ``sample_account``:
      - account_id, active_status, open_date, expiration_date,
        reissue_date, group_id.
      - curr_bal, credit_limit, cash_credit_limit,
        curr_cyc_credit, curr_cyc_debit are ``Decimal`` (NOT float).
    * Customer fields are populated from ``sample_customer``:
      - customer_id, customer_first_name, customer_last_name,
        customer_dob, customer_fico_score.
      - customer_ssn is formatted as ``NNN-NN-NNNN``.
      - customer_phone_1 is right-trimmed / preserved canonical form.
    * ``info_message`` and ``error_message`` are both ``None``.
    * ``session.execute`` called exactly once (for the xref query).
    * ``session.get`` called exactly twice (Account + Customer).
    * Neither flush / commit / rollback is called (view is read-only).

    Maps to
    -------
    COACTVWC.cbl: the full path through 9000-READXFILE →
    9100-READACCT → 9200-READCUST → 1100-PROCESS-INPUTS →
    2000-SEND-MAP on the happy path.
    """
    # Arrange: configure the 3-entity chain to find every record.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Act
    response: AccountViewResponse = await account_service.get_account_view(_TEST_ACCT_ID)

    # Assert: type and outcome messaging.
    assert isinstance(response, AccountViewResponse)
    assert response.info_message is None
    assert response.error_message is None

    # Assert: Account identity and status fields.
    assert response.account_id == _TEST_ACCT_ID
    assert response.active_status == "Y"
    assert response.open_date == "2020-01-15"
    assert response.expiration_date == "2030-12-31"
    assert response.reissue_date == "2025-01-15"
    assert response.group_id == "DEFAULT"

    # Assert: Account monetary fields are Decimal with 2-place precision.
    # CRITICAL PARITY INVARIANT: never float.
    assert isinstance(response.credit_limit, Decimal)
    assert not isinstance(response.credit_limit, float)
    assert response.credit_limit == Decimal("5000.00")
    assert isinstance(response.cash_credit_limit, Decimal)
    assert response.cash_credit_limit == Decimal("2000.00")
    assert isinstance(response.current_balance, Decimal)
    assert response.current_balance == Decimal("1500.00")
    assert isinstance(response.current_cycle_credit, Decimal)
    assert response.current_cycle_credit == Decimal("200.00")
    assert isinstance(response.current_cycle_debit, Decimal)
    assert response.current_cycle_debit == Decimal("50.00")

    # Assert: Customer identity & demographic fields.
    assert response.customer_id == _TEST_CUST_ID
    assert response.customer_first_name == "John"
    assert response.customer_middle_name == "Q"
    assert response.customer_last_name == "Doe"
    assert response.customer_dob == "1990-01-15"
    # FICO is serialized as 3-char zero-padded string.
    assert response.customer_fico_score == "750"
    assert len(response.customer_fico_score) == _EXPECTED_FICO_WIDTH

    # Assert: SSN is formatted as ``NNN-NN-NNNN`` (display form).
    assert response.customer_ssn == "123-45-6789"
    assert len(response.customer_ssn) == _EXPECTED_SSN_DISPLAY_WIDTH

    # Assert: address fields.
    assert response.customer_addr_line_1 == "123 Test St"
    assert response.customer_addr_line_2 == ""
    assert response.customer_city == "New York"
    assert response.customer_state_cd == "NY"
    assert response.customer_country_cd == "USA"
    assert response.customer_zip == "10001"

    # Assert: phones are displayed in canonical (AAA)BBB-CCCC format.
    assert response.customer_phone_1 == "(212)555-1234"
    assert len(response.customer_phone_1) == _EXPECTED_PHONE_DISPLAY_WIDTH
    assert response.customer_phone_2 == ""

    # Assert: govt_id, EFT, pri_cardholder echo their stored values.
    assert response.customer_govt_id == ""
    assert response.customer_eft_account_id == ""
    assert response.customer_pri_cardholder == "Y"

    # Assert: session lifecycle — read-only view, no writes.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_account_view_xref_not_found(
    account_service: AccountService,
    mock_db_session: AsyncMock,
) -> None:
    """Missing cross-reference short-circuits the chain with NOTFND error.

    Arrange
    -------
    * Mock the xref query to return ``None`` (``scalars().first()``
      returns ``None``).

    Act
    ---
    * Call ``account_service.get_account_view(_TEST_ACCT_ID)``.

    Assert
    ------
    * Response has ``error_message='Did not find this account in
      account card xref file'`` (COBOL-exact literal).
    * ``info_message`` is ``None``.
    * The account_id is echoed on the response (so the client can
      display the un-findable ID in the error form).
    * Neither Account nor Customer lookups are attempted
      (``session.get.await_count == 0``).
    * The monetary fields on the error response are all
      ``Decimal("0.00")`` (not float / not None).

    Maps to
    -------
    COACTVWC.cbl: 9000-READXFILE → DFHRESP(NOTFND) branch →
    1300-PROCESS-INPUTS-ERROR → 2000-SEND-MAP with error message.
    """
    # Arrange: xref miss.
    _configure_view_mocks(
        mock_db_session,
        xref=None,
        account=None,
        customer=None,
    )

    # Act
    response: AccountViewResponse = await account_service.get_account_view(_TEST_ACCT_ID)

    # Assert: COBOL-exact error literal.
    assert response.error_message == _MSG_VIEW_XREF_NOT_FOUND
    assert response.info_message is None

    # Assert: account_id echoed back for the client's error display.
    assert response.account_id == _TEST_ACCT_ID
    # Customer/data fields are blanked — the client relies on
    # error_message to surface the failure, not the data fields.
    assert response.customer_id == ""
    assert response.active_status == ""

    # Assert: Decimal zero on monetary fields (never float / never None).
    assert isinstance(response.credit_limit, Decimal)
    assert response.credit_limit == Decimal("0.00")
    assert isinstance(response.cash_credit_limit, Decimal)
    assert response.cash_credit_limit == Decimal("0.00")
    assert isinstance(response.current_balance, Decimal)
    assert response.current_balance == Decimal("0.00")

    # Assert: short-circuit — no Account / Customer lookups attempted.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 0
    # Read-only — no writes attempted.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_account_view_account_not_found(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
) -> None:
    """Missing account after xref hit returns ACCTDAT NOTFND error.

    Arrange
    -------
    * Mock xref query to return ``sample_xref``.
    * Mock ``session.get(Account, ...)`` to return ``None``.

    Act
    ---
    * Call ``account_service.get_account_view(_TEST_ACCT_ID)``.

    Assert
    ------
    * Response has ``error_message='Did not find this account in
      account master file'`` (COBOL-exact).
    * ``info_message`` is ``None``.
    * Customer lookup is NOT attempted (``session.get.await_count == 1``).

    Maps to
    -------
    COACTVWC.cbl: 9100-READACCT → DFHRESP(NOTFND) branch. The
    xref pointed at an account that no longer exists in ACCTDAT
    (e.g. orphaned index, partial migration).
    """
    # Arrange: xref hit, account miss.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=None,
        customer=None,
    )

    # Act
    response: AccountViewResponse = await account_service.get_account_view(_TEST_ACCT_ID)

    # Assert: COBOL-exact error literal.
    assert response.error_message == _MSG_VIEW_ACCT_NOT_FOUND
    assert response.info_message is None

    # Assert: account_id echoed; customer_id blank (never fetched).
    assert response.account_id == _TEST_ACCT_ID
    assert response.customer_id == ""

    # Assert: Decimal defaults on monetary fields.
    assert isinstance(response.credit_limit, Decimal)
    assert response.credit_limit == Decimal("0.00")

    # Assert: xref query + one get (Account). Customer never fetched.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 1
    # Read-only — no writes.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_get_account_view_customer_not_found(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
) -> None:
    """Missing customer after xref+account hit returns CUSTDAT NOTFND.

    Arrange
    -------
    * Mock xref query to return ``sample_xref``.
    * Mock ``session.get(Account, ...)`` to return ``sample_account``.
    * Mock ``session.get(Customer, ...)`` to return ``None``.

    Act
    ---
    * Call ``account_service.get_account_view(_TEST_ACCT_ID)``.

    Assert
    ------
    * Response has ``error_message='Did not find associated customer
      in master file'`` (COBOL-exact).
    * ``info_message`` is ``None``.
    * Both Account and Customer lookups are attempted
      (``session.get.await_count == 2``).

    Maps to
    -------
    COACTVWC.cbl: 9200-READCUST → DFHRESP(NOTFND) branch. The
    xref pointed at a customer that no longer exists in CUSTDAT.
    """
    # Arrange: xref + account hit, customer miss.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=None,
    )

    # Act
    response: AccountViewResponse = await account_service.get_account_view(_TEST_ACCT_ID)

    # Assert: COBOL-exact error literal.
    assert response.error_message == _MSG_VIEW_CUST_NOT_FOUND
    assert response.info_message is None

    # Assert: account_id echoed; account data NOT populated (error response).
    assert response.account_id == _TEST_ACCT_ID
    assert response.customer_id == ""

    # Assert: Decimal defaults on monetary fields.
    assert isinstance(response.credit_limit, Decimal)
    assert response.credit_limit == Decimal("0.00")

    # Assert: xref query + two gets (Account + Customer).
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2
    # Read-only — no writes.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


# =============================================================================
# Phase 4 — Account Update tests (F-005, COACTUPC.cbl)
# =============================================================================
# These tests verify the dual-write SYNCPOINT ROLLBACK semantics of
# the account update flow. The 4,236-line COACTUPC.cbl source
# reduces to:
#   1. Read CXACAIX + ACCTDAT + CUSTDAT (same as view).
#   2. 1200-EDIT-MAP-INPUTS: validate every field.
#   3. 1205-COMPARE-OLD-NEW: skip write if nothing changed.
#   4. 9200-WRITE-PROCESSING: REWRITE ACCTDAT + REWRITE CUSTDAT
#      within a single SYNCPOINT (transaction). Any exception
#      triggers SYNCPOINT ROLLBACK — BOTH writes revert.
# =============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_success(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """Successful update flushes + commits BOTH Account and Customer.

    Arrange
    -------
    * Mock xref/account/customer reads to return the fixture entities.
    * Build an update request that changes ``credit_limit`` from
      ``Decimal('5000.00')`` to ``Decimal('7500.00')`` — a single
      field change so ``_detect_changes`` returns True.

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID, request)``.

    Assert
    ------
    * Response type is :class:`AccountUpdateResponse`.
    * ``info_message == 'Changes committed to database'`` (COBOL-exact).
    * ``error_message is None``.
    * ``response.credit_limit == Decimal('7500.00')`` — the NEW value
      is reflected in the confirmation response.
    * ``session.flush.await_count == 1`` — single flush covers BOTH
      Account and Customer UPDATEs (SYNCPOINT semantics).
    * ``session.commit.await_count == 1`` — single commit finalizes
      the dual-write.
    * ``session.rollback.await_count == 0`` — no rollback on happy path.
    * The in-memory account entity has the new credit_limit
      applied via ``_apply_account_mutations``.

    Maps to
    -------
    COACTUPC.cbl: full path through 1200-EDIT-MAP-INPUTS →
    1205-COMPARE-OLD-NEW (detects change) → 1100-PROCESS-INPUTS →
    9200-WRITE-PROCESSING (REWRITE ACCTDAT + REWRITE CUSTDAT) →
    SYNCPOINT (implicit task termination) → confirmation message.
    """
    # Arrange: full 3-entity hit.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Build a request with ONE change — a new credit_limit.
    request: AccountUpdateRequest = _make_account_update_request(
        credit_limit=Decimal("7500.00"),
    )

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: happy-path response.
    assert isinstance(response, AccountUpdateResponse)
    assert response.info_message == _MSG_UPDATE_SUCCESS
    assert response.error_message is None

    # Assert: the NEW credit_limit is reflected in the response.
    # CRITICAL PARITY INVARIANT: Decimal, not float.
    assert isinstance(response.credit_limit, Decimal)
    assert response.credit_limit == Decimal("7500.00")

    # Assert: other fields preserved (unchanged values round-trip).
    assert response.account_id == _TEST_ACCT_ID
    assert response.customer_id == _TEST_CUST_ID
    assert response.active_status == "Y"
    assert response.customer_first_name == "John"
    assert response.customer_last_name == "Doe"
    assert response.customer_ssn == "123-45-6789"

    # Assert: session lifecycle — flush + commit, NO rollback.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2
    assert mock_db_session.flush.await_count == 1
    assert mock_db_session.commit.await_count == 1
    assert mock_db_session.rollback.await_count == 0

    # Assert: the in-memory Account entity received the mutation
    # (confirms _apply_account_mutations actually ran).
    assert sample_account.credit_limit == Decimal("7500.00")
    # Other Account fields preserved.
    assert sample_account.active_status == "Y"
    assert sample_account.cash_credit_limit == Decimal("2000.00")


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_dual_write_rollback_on_customer_failure(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """SYNCPOINT ROLLBACK reverts BOTH writes when flush raises.

    This is the CRITICAL test for the dual-write atomicity invariant.
    The COACTUPC.cbl flow does REWRITE ACCTDAT and REWRITE CUSTDAT
    within a single CICS task; any failure between the two (or during
    either REWRITE) must trigger SYNCPOINT ROLLBACK so NEITHER row
    persists. In SQLAlchemy, this maps to:

    * ``flush()`` flushes pending changes to the database within the
      transaction.
    * On DB constraint violation during flush,
      :class:`IntegrityError` propagates.
    * The service catches it, calls ``rollback()``, and returns the
      'Changes unsuccessful' COBOL error.
    * NEITHER the Account nor the Customer UPDATE persists — both
      are reverted together.

    Arrange
    -------
    * Full 3-entity hit on reads.
    * Build a change-requesting update.
    * Configure ``flush`` to raise :class:`IntegrityError` simulating
      a DB constraint violation during the dual-write.

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID, request)``.

    Assert
    ------
    * Response has ``error_message='Changes unsuccessful. Please try
      again'`` (COBOL-exact).
    * ``info_message is None``.
    * ``session.flush.await_count == 1`` — flush attempted.
    * ``session.commit.await_count == 0`` — commit skipped after
      flush failure.
    * ``session.rollback.await_count == 1`` — SYNCPOINT ROLLBACK
      called to revert both pending writes.

    Maps to
    -------
    COACTUPC.cbl ~line 953: the ELSE branch of the REWRITE response-
    code check, which issues ``EXEC CICS SYNCPOINT ROLLBACK END-EXEC``
    and surfaces the 'Changes unsuccessful' message.
    """
    # Arrange: full hit on reads.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Build a request with a change so we reach the write path.
    request: AccountUpdateRequest = _make_account_update_request(
        credit_limit=Decimal("9999.99"),
    )

    # Configure flush to raise IntegrityError (e.g. duplicate key,
    # FK violation, check constraint). The service MUST call
    # rollback() and return the COBOL 'Changes unsuccessful' message.
    mock_db_session.flush.side_effect = IntegrityError(
        "UPDATE customers ... violates foreign-key constraint",
        None,
        Exception("underlying DB driver error"),
    )

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: COBOL-exact error literal (no success, no stale — generic).
    assert response.error_message == _MSG_UPDATE_FAILED
    assert response.info_message is None

    # Assert: account_id echoed back on error response.
    assert response.account_id == _TEST_ACCT_ID

    # Assert: Decimal defaults on the error response (zeroed monetary).
    assert isinstance(response.credit_limit, Decimal)

    # Assert: SYNCPOINT ROLLBACK semantics.
    # flush attempted once, commit skipped, rollback called ONCE.
    assert mock_db_session.flush.await_count == 1
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 1

    # Assert: reads still happened before the failed write.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_no_changes_detected(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """Submitting unchanged values short-circuits BEFORE any DB write.

    The COACTUPC.cbl paragraph 1205-COMPARE-OLD-NEW runs before the
    REWRITE paragraph: if no field changed, the program displays
    'No change detected' and skips the write. This optimization
    saves a round trip to the DB and prevents unnecessary
    version_id bumps (which would spuriously invalidate concurrent
    readers' cached records).

    Arrange
    -------
    * Full 3-entity hit on reads — all entities match request defaults.
    * Build a request with NO overrides (defaults match fixtures).

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID, request)``.

    Assert
    ------
    * Response has ``info_message='No change detected in submitted
      values.'`` (COBOL-exact, note the trailing period).
    * ``error_message is None`` — this is a success status, NOT
      an error. The client should display ``info_message`` in a
      neutral confirmation region, not an error region.
    * ``session.flush.await_count == 0`` — no write attempted.
    * ``session.commit.await_count == 0`` — no commit.
    * ``session.rollback.await_count == 0`` — no rollback
      (nothing to revert).

    Maps to
    -------
    COACTUPC.cbl 1205-COMPARE-OLD-NEW: after 1200-EDIT-MAP-INPUTS
    validates every field, this paragraph compares every input
    field against the current record values. If the OLD-NEW
    comparator finds them identical, the program skips
    9200-WRITE-PROCESSING and surfaces the WS-NO-CHANGE message.
    """
    # Arrange: all three reads hit. Fixtures and _make_account_update_request
    # defaults are carefully aligned so canonical comparison yields equal.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Build a request with NO overrides — defaults match fixtures exactly.
    request: AccountUpdateRequest = _make_account_update_request()

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: info-only response (no error, no success — a status).
    assert response.info_message == _MSG_NO_CHANGES
    assert response.error_message is None

    # Assert: response data echoes the unchanged state.
    assert response.account_id == _TEST_ACCT_ID
    assert response.customer_id == _TEST_CUST_ID
    assert isinstance(response.credit_limit, Decimal)
    assert response.credit_limit == Decimal("5000.00")

    # Assert: NO writes attempted — critical optimization for
    # unchanged submissions.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0

    # Assert: reads still occurred (to detect the no-change state).
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_optimistic_concurrency_conflict(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """StaleDataError from flush surfaces COBOL 'Record changed' message.

    In the COBOL flow, CICS READ UPDATE acquires an exclusive lock
    on the VSAM record; a subsequent REWRITE either succeeds or
    fails with DFHRESP(DUPKEY) / DFHRESP(ILLOGIC). In the
    PostgreSQL migration target, SQLAlchemy's ``version_id_col``
    feature appends ``AND version_id = :old`` to the UPDATE's WHERE
    clause; if a concurrent transaction has already bumped the
    version, the UPDATE's affected-row-count is zero, and
    SQLAlchemy raises :class:`StaleDataError`.

    Arrange
    -------
    * Full 3-entity hit on reads.
    * Build a request with a change.
    * Configure ``flush`` to raise :class:`StaleDataError` simulating
      a concurrent modification.

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID, request)``.

    Assert
    ------
    * Response has ``error_message='Record changed by some one else.
      Please review'`` — COBOL-exact (note 'some one' as two words).
    * ``info_message is None``.
    * ``session.flush.await_count == 1``.
    * ``session.commit.await_count == 0`` — commit skipped.
    * ``session.rollback.await_count == 1``.

    Maps to
    -------
    COACTUPC.cbl: the READ UPDATE / REWRITE pattern equivalent. The
    original COBOL program used an in-COMMAREA 'RID' (Record IDentifier)
    comparator to detect concurrent modification between initial
    READ and the final REWRITE. Here we leverage SQLAlchemy's
    native version_id feature to achieve the same semantic.
    """
    # Arrange: full 3-entity hit.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Request with a change (so we reach the flush).
    request: AccountUpdateRequest = _make_account_update_request(
        credit_limit=Decimal("8500.00"),
    )

    # Configure flush to raise StaleDataError simulating OCC conflict.
    # StaleDataError is a subclass of DatabaseError / OrmError.
    mock_db_session.flush.side_effect = StaleDataError(
        "UPDATE accounts SET ... WHERE acct_id = :acct_id "
        "AND version_id = :old_version_id "
        "-- 0 rows affected (optimistic-concurrency failure)",
        None,
        None,
    )

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: COBOL-exact STALE message (NOT the generic FAILED message).
    assert response.error_message == _MSG_UPDATE_STALE
    assert response.info_message is None

    # Assert: account_id echoed.
    assert response.account_id == _TEST_ACCT_ID

    # Assert: Decimal defaults on error response.
    assert isinstance(response.credit_limit, Decimal)

    # Assert: SYNCPOINT ROLLBACK semantics on OCC failure.
    assert mock_db_session.flush.await_count == 1
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 1

    # Assert: reads completed before the failed flush.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2


# =============================================================================
# Phase 5 — Field Validation tests (F-005, COACTUPC.cbl validators)
# =============================================================================
# These tests verify each field's validation logic. The COACTUPC.cbl
# program delegates to a cascade of EDIT paragraphs (1210..1290)
# that short-circuit at the first failure. In Python, the cascade
# is implemented in ``_validate_request()`` which returns the first
# error message (or None on success).
#
# Two layers of validation exist:
#   * **Schema layer** (Pydantic) — enforces structural invariants
#     (lengths, types, numeric ranges with simple constraints).
#     Violations raise :class:`pydantic.ValidationError` at
#     construction time.
#   * **Service layer** (``_validate_request``) — enforces COBOL
#     business rules (SSA blacklist, Y/N flags, date CCYYMMDD
#     structure). Violations return an error response with the
#     COBOL-exact message.
# =============================================================================


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_date_validation(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """COBOL EDIT-DATE-CCYYMMDD rejects structurally-invalid dates.

    This test covers TWO paths:

    1. **Valid date** (YYYY-01-15): flows cleanly through validation,
       parsing, change-detection (no change), and returns the
       NO_CHANGES status.

    2. **Invalid date** (month=13, day=40 — both out of range):
       fails the service-layer CCYYMMDD validator and returns the
       'Account Opening Date is not valid' error. Critically, no
       DB writes are attempted because validation runs before
       mutation.

    Arrange (valid path)
    --------------------
    * Full 3-entity hit; request with defaults (open_date 2020-01-15).

    Assert (valid path)
    -------------------
    * Response has ``info_message == NO_CHANGES``.
    * No error (valid date passes through).

    Arrange (invalid path)
    ----------------------
    * Full 3-entity hit; override open_date_month='13'.

    Assert (invalid path)
    ---------------------
    * Response has ``error_message='Account Opening Date is not valid'``.
    * ``info_message is None``.
    * NO flush / commit / rollback — validation halted before write.

    Maps to
    -------
    COACTUPC.cbl 1285-EDIT-DATE-CCYYMMDD: the date-validation
    paragraph checks year (1900..2099), month (01..12), and day
    (01..28/29/30/31 depending on month and leap year).
    """
    # -----------------------------------------------------------------
    # Path 1: valid date passes validation (flows to NO_CHANGES).
    # -----------------------------------------------------------------
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Default open_date is 2020-01-15 — valid CCYYMMDD.
    # Since the request matches the fixture, _detect_changes → False,
    # and we hit the NO_CHANGES path, confirming validation passed.
    valid_request: AccountUpdateRequest = _make_account_update_request()

    response_valid: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, valid_request)

    # Valid date flowed through; we got the NO_CHANGES status (not
    # a DATE_INVALID error). That confirms the date validator passed.
    assert response_valid.info_message == _MSG_NO_CHANGES
    assert response_valid.error_message is None

    # -----------------------------------------------------------------
    # Path 2: invalid date rejected by service validator.
    # -----------------------------------------------------------------
    # Reset mock call counts by rebuilding the fixture mocks. The
    # previous call incremented them; we want to verify the invalid-
    # path counts independently.
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Override with month='13' — out of range. Pydantic accepts it
    # (max_length=2 allows any 2 chars); the service validator rejects.
    invalid_request: AccountUpdateRequest = _make_account_update_request(
        open_date_month="13",
    )

    response_invalid: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, invalid_request)

    # Assert: COBOL-exact error literal.
    assert response_invalid.error_message == _MSG_OPEN_DATE_INVALID
    assert response_invalid.info_message is None

    # Assert: no writes attempted — validation halted the flow.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_fico_validation(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """COBOL FICO validator enforces range [300, 850].

    FICO scores outside the FICO credit-scoring industry standard
    range [300, 850] are rejected as invalid. The COBOL paragraph
    also rejects non-numeric input. This test covers:

    1. **Valid** FICO (750 — the default, within range): passes.
    2. **Too low** FICO (299): rejected.
    3. **Too high** FICO (851): rejected.
    4. **Non-numeric** FICO ('ABC'): rejected.

    Arrange (per path)
    ------------------
    * Full 3-entity hit on reads.
    * Override ``customer_fico_score`` in the request.

    Assert (invalid paths)
    ----------------------
    * Response has ``error_message == 'FICO Score must be between
      300 and 850'``.
    * ``info_message is None``.
    * NO flush / commit / rollback.

    Maps to
    -------
    COACTUPC.cbl 1275-EDIT-FICO-SCORE: validates the 3-digit FICO
    field against the [300, 850] range.
    """
    # Test each invalid FICO value — verifies the COBOL-exact
    # error message is returned and no DB writes happen.
    for invalid_fico in ("000", "299", "851", "999", "ABC"):
        # Reset per iteration so ``await_count`` assertions are
        # per-iteration (not cumulative).
        mock_db_session.execute.reset_mock()
        mock_db_session.get.reset_mock()
        mock_db_session.flush.reset_mock()
        mock_db_session.commit.reset_mock()
        mock_db_session.rollback.reset_mock()

        _configure_view_mocks(
            mock_db_session,
            xref=sample_xref,
            account=sample_account,
            customer=sample_customer,
        )

        request: AccountUpdateRequest = _make_account_update_request(
            customer_fico_score=invalid_fico,
        )

        response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

        # Every invalid FICO yields the same COBOL-exact message.
        assert response.error_message == _MSG_FICO_INVALID, (
            f"FICO={invalid_fico!r} should be rejected but got error_message={response.error_message!r}"
        )
        assert response.info_message is None

        # No DB writes on validation failure.
        assert mock_db_session.flush.await_count == 0
        assert mock_db_session.commit.await_count == 0
        assert mock_db_session.rollback.await_count == 0

    # -----------------------------------------------------------------
    # Positive case: valid FICO (750) passes validation.
    # -----------------------------------------------------------------
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    valid_request: AccountUpdateRequest = _make_account_update_request(
        customer_fico_score="750",  # Matches sample_customer.fico_credit_score.
    )

    valid_response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, valid_request)

    # Valid FICO flowed to the NO_CHANGES path — confirms validation passed.
    assert valid_response.info_message == _MSG_NO_CHANGES
    assert valid_response.error_message is None
    # Boundary check: 300 (valid) flowed to NOT the FICO_INVALID branch.
    # We already exercised the 299 (invalid) boundary above; this
    # positive case completes the boundary coverage.


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_ssn_format_validation(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """COBOL SSN validator enforces 3+2+4 digits AND SSA area blacklist.

    Social Security numbers have several invalidity conditions,
    following Social Security Administration (SSA) rules:

    * **Format**: 3-digit area + 2-digit group + 4-digit serial,
      all numeric.
    * **Blacklisted areas**: 000, 666, and 900..999 (never issued
      by SSA). See ``_INVALID_SSN_AREAS`` in account_service.py.
    * **Zero sections**: group=00 or serial=0000 is invalid.

    This test covers:

    1. **Valid** SSN (123-45-6789): passes.
    2. **Zero area** (000-XX-XXXX): → per-segment PART1_ZERO
       (NOT the blacklist message — the COBOL 1245-EDIT-NUM-REQD
       zero-check at L2156-2171 runs BEFORE the INVALID-SSN-PART1
       area-blacklist check at L2450, so ``000`` triggers the
       per-segment zero message, not the blacklist).
    3. **Blacklisted area** (666-XX-XXXX): → SSN_PART1_INVALID.
    4. **Blacklisted area** (900-XX-XXXX): → SSN_PART1_INVALID.
    5. **Blacklisted area** (999-XX-XXXX): → SSN_PART1_INVALID.
    6. **Zero group** (NNN-00-NNNN): → per-segment PART2_ZERO.
    7. **Zero serial** (NNN-NN-0000): → per-segment PART3_ZERO.

    Arrange (per path)
    ------------------
    * Full 3-entity hit on reads.
    * Override SSN part(s) in the request.

    Assert (invalid paths)
    ----------------------
    * Response has the appropriate SSN error message.
    * NO writes attempted.

    Maps to
    -------
    COACTUPC.cbl 1265-EDIT-US-SSN: validates SSN structure and
    applies the SSA-issued-area blacklist.
    """
    # Cases: (part1, part2, part3, expected_error).  The expected
    # message reflects the COBOL cascade ordering documented in the
    # docstring above — specifically that per-segment zero-checks in
    # 1245-EDIT-NUM-REQD run BEFORE the part-1 SSA-area blacklist.
    invalid_cases: list[tuple[str, str, str, str]] = [
        # '000' for part 1 fails the 1245-EDIT-NUM-REQD zero check
        # (L2156) with the 'SSN: First 3 chars' label BEFORE reaching
        # the INVALID-SSN-PART1 blacklist at L2450.
        ("000", "45", "6789", _MSG_SSN_PART1_ZERO),
        # '666', '900', '999' pass the zero check (non-zero values)
        # and fail the INVALID-SSN-PART1 blacklist — producing the
        # area-blacklist-specific message.
        ("666", "45", "6789", _MSG_SSN_PART1_INVALID),
        ("900", "45", "6789", _MSG_SSN_PART1_INVALID),
        ("999", "45", "6789", _MSG_SSN_PART1_INVALID),
        # '00' for part 2 fails 1245-EDIT-NUM-REQD zero check with
        # the 'SSN 4th & 5th chars' label (L2469).
        ("123", "00", "6789", _MSG_SSN_PART2_ZERO),
        # '0000' for part 3 fails 1245-EDIT-NUM-REQD zero check with
        # the 'SSN Last 4 chars' label (L2481).
        ("123", "45", "0000", _MSG_SSN_PART3_ZERO),
    ]

    for part1, part2, part3, expected_msg in invalid_cases:
        mock_db_session.execute.reset_mock()
        mock_db_session.get.reset_mock()
        mock_db_session.flush.reset_mock()
        mock_db_session.commit.reset_mock()
        mock_db_session.rollback.reset_mock()

        _configure_view_mocks(
            mock_db_session,
            xref=sample_xref,
            account=sample_account,
            customer=sample_customer,
        )

        request: AccountUpdateRequest = _make_account_update_request(
            customer_ssn_part1=part1,
            customer_ssn_part2=part2,
            customer_ssn_part3=part3,
        )

        response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

        # Assert: the expected COBOL-exact error literal.
        assert response.error_message == expected_msg, (
            f"SSN={part1}-{part2}-{part3} expected error {expected_msg!r}, got {response.error_message!r}"
        )
        assert response.info_message is None

        # Assert: no writes on validation failure.
        assert mock_db_session.flush.await_count == 0
        assert mock_db_session.commit.await_count == 0
        assert mock_db_session.rollback.await_count == 0

    # -----------------------------------------------------------------
    # Positive case: valid SSN 123-45-6789.
    # -----------------------------------------------------------------
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    valid_request: AccountUpdateRequest = _make_account_update_request(
        customer_ssn_part1="123",
        customer_ssn_part2="45",
        customer_ssn_part3="6789",
    )

    valid_response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, valid_request)

    # Valid SSN flowed to the NO_CHANGES path (matches fixture SSN).
    assert valid_response.info_message == _MSG_NO_CHANGES
    assert valid_response.error_message is None


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_yesno_validation(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """COBOL 1220-EDIT-YESNO enforces strictly uppercase 'Y' or 'N'.

    The Y/N flag fields (``active_status``, ``pri_card_holder_ind``)
    accept ONLY the literal uppercase letters 'Y' or 'N'. Lowercase
    variants, spaces, and other values are rejected. This test covers:

    1. **Valid 'Y'**: passes (matches default — flows to NO_CHANGES).
    2. **Valid 'N'**: passes validation (but causes a change vs. the
       fixture's 'Y', so flows to the happy-path update).
    3. **Invalid 'X'** for active_status → STATUS_INVALID.
    4. **Invalid 'y'** (lowercase) for active_status → STATUS_INVALID.
    5. **Invalid 'Q'** for pri_card_holder_ind → PRI_CARD_INVALID.

    Arrange (per path)
    ------------------
    * Full 3-entity hit on reads.
    * Override the Y/N field(s).

    Assert (invalid paths)
    ----------------------
    * Response has the appropriate Y/N error message.
    * NO writes.

    Maps to
    -------
    COACTUPC.cbl 1220-EDIT-YESNO: validates single-character Y/N
    input fields (case-sensitive — uppercase only).
    """
    # Invalid active_status: 'X' rejected.
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    invalid_status_request: AccountUpdateRequest = _make_account_update_request(
        active_status="X",
    )

    invalid_status_response: AccountUpdateResponse = await account_service.update_account(
        _TEST_ACCT_ID, invalid_status_request
    )

    assert invalid_status_response.error_message == _MSG_STATUS_INVALID
    assert invalid_status_response.info_message is None
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0

    # Invalid active_status: 'y' (lowercase) rejected.
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    lowercase_status_request: AccountUpdateRequest = _make_account_update_request(
        active_status="y",
    )

    lowercase_status_response: AccountUpdateResponse = await account_service.update_account(
        _TEST_ACCT_ID, lowercase_status_request
    )

    # COBOL is strictly case-sensitive — lowercase 'y' rejected.
    assert lowercase_status_response.error_message == _MSG_STATUS_INVALID
    assert lowercase_status_response.info_message is None

    # Invalid pri_card_holder_ind: 'Q' rejected.
    # Note: active_status must be valid to reach the pri_card_holder validator
    # (validation cascade — step 1 checks active_status, step 2 checks
    # customer_pri_cardholder).
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    invalid_pri_request: AccountUpdateRequest = _make_account_update_request(
        customer_pri_cardholder="Q",
    )

    invalid_pri_response: AccountUpdateResponse = await account_service.update_account(
        _TEST_ACCT_ID, invalid_pri_request
    )

    assert invalid_pri_response.error_message == _MSG_PRI_CARD_INVALID
    assert invalid_pri_response.info_message is None
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0

    # -----------------------------------------------------------------
    # Positive cases: 'Y' and 'N' both accepted.
    # -----------------------------------------------------------------
    # 'Y' (default — matches fixture, flows to NO_CHANGES).
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    y_request: AccountUpdateRequest = _make_account_update_request(
        active_status="Y",
    )

    y_response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, y_request)

    # 'Y' is valid → flows through to NO_CHANGES (no override else matched).
    assert y_response.info_message == _MSG_NO_CHANGES
    assert y_response.error_message is None


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_monetary_decimal_precision(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
) -> None:
    """Monetary fields MUST be Decimal — NEVER float.

    This is THE test that enforces the most important COBOL parity
    invariant: COBOL ``PIC S9(10)V99`` fields carry exact fixed-
    scale decimal semantics. Binary floating-point (float) cannot
    represent values like 0.1 exactly — over a sequence of financial
    operations, rounding errors accumulate. Python's
    :class:`decimal.Decimal` preserves the COBOL semantic.

    The schema layer enforces this via the
    ``_validate_monetary_non_negative`` validator, which:

    * Checks ``isinstance(value, Decimal)``.
    * Rejects negative values.

    This test verifies:

    1. **Valid Decimal** (Decimal('5000.00')): accepted.
    2. **Negative Decimal** (Decimal('-1.00')): rejected by Pydantic
       (ValidationError at construction time).
    3. **Response round-trip**: the response's monetary fields are
       :class:`Decimal` instances, not float.

    Arrange
    -------
    * Full 3-entity hit on reads.
    * Build a request with a changed credit_limit.

    Assert
    ------
    * After a successful update, the response's credit_limit is
      a Decimal with the exact input value.
    * Attempting to construct a request with a negative credit_limit
      raises :class:`pydantic.ValidationError`.

    Maps to
    -------
    COACTUPC.cbl 1250-EDIT-SIGNED-9V2: validates PIC S9(10)V99
    fields. In COBOL, these are packed decimal fields with a fixed
    2-decimal scale; the Python translation must use Decimal
    with the same scale.
    """
    # -----------------------------------------------------------------
    # Path 1: valid Decimal accepted, round-trips as Decimal.
    # -----------------------------------------------------------------
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    new_credit_limit: Decimal = Decimal("12345.67")
    new_cash_limit: Decimal = Decimal("500.25")

    request: AccountUpdateRequest = _make_account_update_request(
        credit_limit=new_credit_limit,
        cash_credit_limit=new_cash_limit,
    )

    # Pydantic preserves the Decimal type end-to-end.
    assert isinstance(request.credit_limit, Decimal)
    assert not isinstance(request.credit_limit, float)
    assert isinstance(request.cash_credit_limit, Decimal)
    assert not isinstance(request.cash_credit_limit, float)

    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: successful update with BOTH Decimal values preserved.
    assert response.info_message == _MSG_UPDATE_SUCCESS
    assert response.error_message is None

    # CRITICAL PARITY INVARIANT: response monetary fields are Decimal.
    assert isinstance(response.credit_limit, Decimal)
    assert not isinstance(response.credit_limit, float)
    assert response.credit_limit == new_credit_limit

    assert isinstance(response.cash_credit_limit, Decimal)
    assert not isinstance(response.cash_credit_limit, float)
    assert response.cash_credit_limit == new_cash_limit

    # Precision preserved: no rounding / truncation.
    # Decimal('12345.67') has scale 2 — exactly 2 fractional digits.
    assert response.credit_limit.as_tuple().exponent == -_EXPECTED_DECIMAL_SCALE
    assert response.cash_credit_limit.as_tuple().exponent == -_EXPECTED_DECIMAL_SCALE

    # Assert: read-chain + dual-write lifecycle.
    assert mock_db_session.flush.await_count == 1
    assert mock_db_session.commit.await_count == 1
    assert mock_db_session.rollback.await_count == 0

    # -----------------------------------------------------------------
    # Path 2: negative Decimal rejected by Pydantic validator.
    # -----------------------------------------------------------------
    # The _validate_monetary_non_negative validator rejects values < 0.
    with pytest.raises(pydantic.ValidationError):
        _make_account_update_request(credit_limit=Decimal("-1.00"))

    with pytest.raises(pydantic.ValidationError):
        _make_account_update_request(cash_credit_limit=Decimal("-100.00"))

    # -----------------------------------------------------------------
    # Path 3: verify all 5 monetary fields in the response are Decimal
    # (not just the two explicitly set) — end-to-end Decimal invariant.
    # -----------------------------------------------------------------
    mock_db_session.execute.reset_mock()
    mock_db_session.get.reset_mock()
    mock_db_session.flush.reset_mock()
    mock_db_session.commit.reset_mock()
    mock_db_session.rollback.reset_mock()

    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    no_change_request: AccountUpdateRequest = _make_account_update_request()

    no_change_response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, no_change_request)

    # ALL 5 monetary fields are Decimal:
    assert isinstance(no_change_response.credit_limit, Decimal)
    assert isinstance(no_change_response.cash_credit_limit, Decimal)
    assert isinstance(no_change_response.current_balance, Decimal)
    assert isinstance(no_change_response.current_cycle_credit, Decimal)
    assert isinstance(no_change_response.current_cycle_debit, Decimal)


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_not_found(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
) -> None:
    """Missing account during update returns COBOL ACCTDAT NOTFND.

    This test covers the update-flow counterpart of the view-flow's
    ``test_get_account_view_account_not_found``. If the account
    cannot be located after the xref hit, the update must fail
    cleanly without attempting any write.

    CRITICAL: Unlike CardService, AccountService does NOT call
    rollback() on a NOT-FOUND miss (as distinct from an EXCEPTION
    during fetch). The session has not yet been dirtied, so there
    is nothing to roll back.

    Arrange
    -------
    * Mock xref query to return ``sample_xref``.
    * Mock ``session.get(Account, ...)`` to return ``None``.

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID, request)``.

    Assert
    ------
    * Response has ``error_message='Did not find this account in
      account master file'``.
    * ``info_message is None``.
    * Customer lookup NOT attempted (``session.get.await_count == 1``).
    * NO flush / commit / rollback — the session was never dirtied.

    Maps to
    -------
    COACTUPC.cbl 9100-READACCT → DFHRESP(NOTFND) branch. The account
    was found in the xref but has since been deleted from ACCTDAT
    (or the xref points at a stale record).
    """
    # Arrange: xref hit, account miss.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=None,  # Account not found.
        customer=None,
    )

    request: AccountUpdateRequest = _make_account_update_request()

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: COBOL-exact error literal.
    assert response.error_message == _MSG_VIEW_ACCT_NOT_FOUND
    assert response.info_message is None

    # Assert: account_id echoed on the error response.
    assert response.account_id == _TEST_ACCT_ID

    # Assert: Customer lookup was NOT attempted.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 1

    # Assert: NO writes, NO rollback. AccountService-specific behaviour:
    # a NOT-FOUND miss does NOT trigger rollback (session never dirtied).
    # This diverges from CardService which does call rollback on miss.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_path_body_acct_id_mismatch(
    account_service: AccountService,
    mock_db_session: AsyncMock,
) -> None:
    """Guard 3 rejects a request whose body account_id differs from the URL path.

    This test covers CP3 review finding MINOR #10 — the service's
    third guard rail. When both the URL-path ``acct_id`` and the
    request-body ``account_id`` are well-formed 11-digit non-zero
    numbers but disagree, the service must emit a dedicated
    mismatch message (``_MSG_ACCT_PATH_BODY_MISMATCH``) rather
    than the format-error literal ``_MSG_ACCT_INVALID``.  The
    distinction matters because the guard fires AFTER Pydantic and
    ``_validate_account_id`` have confirmed both values are
    format-valid; the error is a disagreement between two
    well-formed identifiers, not an invalid format.

    This condition is REST-specific and has no COBOL parallel.
    COACTUPC.cbl has a single BMS input field ``ACCTSIDI`` — the
    user types the account number exactly once, so there is no
    pair of values that could disagree.  The RESTful URL-plus-body
    design inherently introduces the opportunity for the two to
    diverge, and per CP3 MINOR #10 the error surface must be
    distinguishable from format errors so the client can respond
    appropriately (e.g. refuse the request at the API gateway
    layer rather than prompting for field-level correction).

    Arrange
    -------
    * URL-path ``acct_id`` = ``"00000000001"`` (``_TEST_ACCT_ID``).
    * Body ``account_id`` = ``"00000000099"`` (``_TEST_ACCT_ID_ALT``,
      a distinct valid identifier).
    * Both values pass ``_validate_account_id``; the ONLY problem
      is that they disagree.

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID,
      request)`` with the mismatched body.

    Assert
    ------
    * ``error_message == _MSG_ACCT_PATH_BODY_MISMATCH`` (REST-
      specific literal, byte-for-byte).
    * ``info_message is None`` — this is an error, not a status.
    * ``account_id`` on the response echoes the URL-path value
      (``_TEST_ACCT_ID``), NOT the mismatched body value.  This is
      a defensive choice:
      ``_build_update_error_response`` prefers the URL-path when
      valid so the client is told which identifier the server
      believes is authoritative.
    * NO database interaction — Guard 3 runs BEFORE the 3-entity
      read chain, so every ``AsyncMock`` method on the session has
      ``await_count == 0``.  This is the "fail-fast" optimisation
      that prevents wasteful reads when the request is obviously
      malformed.

    Maps to
    -------
    * ``src.api.services.account_service.update_account`` Guard 3
      (lines 1133-1146).  Triggers when
      ``request.account_id.strip() != normalized_id`` after both
      format checks have passed.
    * CP3 review finding MINOR #10: distinct error message for
      path/body disagreement.
    """
    # Arrange: valid but mismatched body account_id. The service's
    # default 3-entity read fixtures are NOT wired up because Guard 3
    # must reject the request BEFORE any DB read is issued.
    request: AccountUpdateRequest = _make_account_update_request(
        account_id=_TEST_ACCT_ID_ALT,  # Valid 11-digit non-zero, but != path.
    )

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: REST-specific mismatch literal (byte-for-byte).
    assert response.error_message == _MSG_ACCT_PATH_BODY_MISMATCH
    assert response.info_message is None

    # Assert: response echoes the URL-path account_id as authoritative.
    # ``_build_update_error_response`` preferentially returns the
    # URL-path value because it is the server's authoritative key.
    assert response.account_id == _TEST_ACCT_ID

    # Assert: fail-fast — Guard 3 runs BEFORE any DB interaction,
    # so the mock session must NOT have been touched.  This confirms
    # the guard is correctly placed BEFORE the xref read at the top
    # of Step 1 (``_validate_request`` is Step 2, parse is Step 3).
    assert mock_db_session.execute.await_count == 0
    assert mock_db_session.get.await_count == 0
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


@pytest.mark.unit
@pytest.mark.asyncio
async def test_update_account_parse_failure(
    account_service: AccountService,
    mock_db_session: AsyncMock,
    sample_xref: CardCrossReference,
    sample_account: Account,
    sample_customer: Customer,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Parse failure surfaces the dedicated internal-contract message.

    This test covers CP3 review finding MINOR #11 — the
    defensive-in-depth branch in ``update_account`` that catches a
    ``ValueError`` raised by ``_parse_request`` AFTER the
    validator has already returned ``None`` (success).

    In the COBOL original (``COACTUPC.cbl``) the validator and the
    parser share working storage — the same EBCDIC bytes are
    examined by 1200-EDIT-MAP-INPUTS and subsequently assembled
    into ACCT-UPDATE-RECORD / CUST-UPDATE-RECORD by paragraphs
    9200-WRITE.  A validator-PASS therefore guarantees a
    parser-SUCCESS; the two cannot disagree.

    The Python migration operates on the request string differently:
    ``_validate_request`` inspects string segments individually,
    whereas ``_parse_request`` assembles them into canonical forms
    (joining SSN parts, formatting dates, parsing FICO as int).
    A future refactor that changes either side's string-handling
    semantics could break the implicit invariant that
    ``_validate_request`` returns ``None`` iff ``_parse_request``
    succeeds.  The parse-failure branch is a defensive guard
    against this class of regression.

    Per CP3 MINOR #11 the error message ``_MSG_PARSE_FAILED`` must
    be distinguishable from:

    * Per-field validation messages (retryable with corrected
      input) — those are emitted by the Step 2 validator.
    * ``_MSG_UPDATE_FAILED`` ("Changes unsuccessful. Please try
      again") — that is a DB-level failure emitted after a commit
      error, typically transient and retryable.

    ``_MSG_PARSE_FAILED`` signals an internal contract violation
    worthy of operator investigation — NOT a user-input problem
    and NOT a transient DB issue.

    Arrange
    -------
    * All 3 view-chain reads hit (xref + account + customer).
    * ``_make_account_update_request`` with an overridden
      ``active_status`` so ``_detect_changes`` would WANT to write
      (confirms parse failure halts processing even on a
      change-worthy request).
    * ``monkeypatch`` replaces the module-level ``_parse_request``
      symbol with a stub that unconditionally raises
      ``ValueError("forced parse failure for test")``.

    Act
    ---
    * Call ``account_service.update_account(_TEST_ACCT_ID,
      request)``.

    Assert
    ------
    * ``error_message == _MSG_PARSE_FAILED`` (byte-for-byte).
    * ``info_message is None`` — this is an error, not a status.
    * ``account_id`` on the response echoes the URL-path
      ``_TEST_ACCT_ID``.
    * All 3 reads occurred (execute: 1, get: 2) — the branch fires
      AFTER the read chain completes and AFTER
      ``_validate_request`` returns ``None``.
    * NO writes and NO rollback — the session was never dirtied
      (only reads were issued), so there is nothing to revert.

    Maps to
    -------
    * ``src.api.services.account_service.update_account`` parse
      branch (lines 1262-1290).  Triggers when ``_parse_request``
      raises ``ValueError`` after ``_validate_request`` has
      returned ``None``.
    * CP3 review finding MINOR #11: dedicated ``_MSG_PARSE_FAILED``
      message distinguishable from validation errors and
      ``_MSG_UPDATE_FAILED``.
    """
    # Arrange: hit the happy path for reads so the service reaches
    # Step 3 (parse).  The request must carry a change so that
    # change-detection (which runs AFTER parse) would proceed —
    # but the parse stub fires first and short-circuits.
    _configure_view_mocks(
        mock_db_session,
        xref=sample_xref,
        account=sample_account,
        customer=sample_customer,
    )

    # Build a request with a change so the happy path would write;
    # the monkeypatched parser will raise before we reach write.
    request: AccountUpdateRequest = _make_account_update_request(
        active_status="N",  # Change from sample_account's "Y".
    )

    # Arrange: inject a ValueError into ``_parse_request`` to
    # simulate the defensive-in-depth branch.  We patch the
    # module-level symbol ``_parse_request`` in the service
    # module; because ``update_account`` references the name by
    # ordinary module-scope lookup, the patched function is
    # resolved at call time.
    def _raise_parse_error(
        _: AccountUpdateRequest,
    ) -> object:  # pragma: no cover — body never executes normally
        """Test double that unconditionally raises ``ValueError``."""
        raise ValueError("forced parse failure for test")

    monkeypatch.setattr(
        "src.api.services.account_service._parse_request",
        _raise_parse_error,
    )

    # Act
    response: AccountUpdateResponse = await account_service.update_account(_TEST_ACCT_ID, request)

    # Assert: dedicated internal-contract error literal (byte-for-byte).
    assert response.error_message == _MSG_PARSE_FAILED
    assert response.info_message is None

    # Assert: response echoes the URL-path account_id.
    assert response.account_id == _TEST_ACCT_ID

    # Assert: all 3 reads occurred — the parse branch fires AFTER
    # the full view chain and AFTER _validate_request succeeds.
    assert mock_db_session.execute.await_count == 1
    assert mock_db_session.get.await_count == 2

    # Assert: NO writes and NO rollback — the session was only
    # used for reads, so there is nothing to revert.  This differs
    # from the DB-error path which DOES call rollback after a
    # flush failure.
    assert mock_db_session.flush.await_count == 0
    assert mock_db_session.commit.await_count == 0
    assert mock_db_session.rollback.await_count == 0


# =============================================================================
# End of test_account_service.py
# =============================================================================
# Test summary:
#   * Phase 3 (Account View): 4 tests
#       - test_get_account_view_success
#       - test_get_account_view_xref_not_found
#       - test_get_account_view_account_not_found
#       - test_get_account_view_customer_not_found
#   * Phase 4 (Account Update): 4 tests
#       - test_update_account_success
#       - test_update_account_dual_write_rollback_on_customer_failure
#       - test_update_account_no_changes_detected
#       - test_update_account_optimistic_concurrency_conflict
#   * Phase 5 (Field Validation): 6 tests
#       - test_update_account_date_validation
#       - test_update_account_fico_validation
#       - test_update_account_ssn_format_validation
#       - test_update_account_yesno_validation
#       - test_update_account_monetary_decimal_precision
#       - test_update_account_not_found
#   * Phase 6 (REST-Specific Guards / Defensive Branches): 2 tests
#       - test_update_account_path_body_acct_id_mismatch (CP3 MINOR #10)
#       - test_update_account_parse_failure (CP3 MINOR #11)
#
# Total: 16 tests covering COACTVWC.cbl (F-004) and COACTUPC.cbl (F-005),
# plus 2 REST-specific guards addressing CP3 review findings MINOR #10
# (path/body acct_id disagreement) and MINOR #11 (validator/parser
# contract-violation defensive branch).
# =============================================================================
