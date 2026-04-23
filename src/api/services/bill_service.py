# ============================================================================
# Source: app/cbl/COBIL00C.cbl (CICS bill payment program, CB00 transaction)
#       + app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD 300-byte VSAM record layout)
#       + app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD 50-byte VSAM record layout)
#       + app/cpy/CVTRA05Y.cpy (TRAN-RECORD 350-byte VSAM record layout)
#       + app/cpy-bms/COBIL00.CPY (BMS symbolic map for COBIL0A screen)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS ``EXEC CICS READ FILE('ACCTDAT') INTO(ACCOUNT-RECORD)
#   RIDFLD(ACCT-ID) UPDATE`` + ``EXEC CICS READ FILE('CXACAIX')
#   INTO(CARD-XREF-RECORD) RIDFLD(XREF-ACCT-ID)`` +
#   ``EXEC CICS STARTBR FILE('TRANSACT')`` / ``READPREV`` /
#   ``ENDBR`` + ``EXEC CICS WRITE FILE('TRANSACT')
#   FROM(TRAN-RECORD)`` + ``EXEC CICS REWRITE FILE('ACCTDAT')
#   FROM(ACCOUNT-RECORD)``
#
# becomes
#
#   SQLAlchemy async ``SELECT`` on ``account`` / ``card_cross_reference`` /
#   ``transaction`` tables + ``session.add()`` + dirty-attribute
#   mutation + ``await session.flush() / commit()`` — implementing the
#   same atomic dual-write contract (Transaction INSERT + Account
#   balance UPDATE) that CICS SYNCPOINT provided on the mainframe.
#
# The target deployment is AWS ECS Fargate behind an Application Load
# Balancer, connecting to Aurora PostgreSQL via asyncpg; the database
# credentials come from AWS Secrets Manager in staging / production
# (injected via ECS task-definition secrets) and from ``.env`` file in
# local development (docker-compose).
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
"""Bill payment service.

Converted from ``app/cbl/COBIL00C.cbl`` (CICS transaction CB00, ~572
lines of COBOL). Implements the atomic dual-write pattern — Transaction
INSERT + Account balance UPDATE — replacing the CICS ``WRITE FILE
('TRANSACT')`` + ``REWRITE FILE('ACCTDAT')`` pair that was previously
atomic via an implicit SYNCPOINT at transaction end. All monetary
values use :class:`decimal.Decimal` (never :class:`float`) to preserve
the COBOL ``PIC S9(10)V99`` semantics of ``ACCT-CURR-BAL`` and
``TRAN-AMT``.

Service contract
----------------
The public entry point is :meth:`BillService.pay_bill`, which consumes
a :class:`~src.shared.schemas.bill_schema.BillPaymentRequest` and
returns a :class:`~src.shared.schemas.bill_schema.BillPaymentResponse`.

The dual-write is performed inside a single SQLAlchemy ``AsyncSession``
transaction:

1. ``SELECT`` the Account by primary key (``acct_id``);
2. Validate the account's current balance is strictly positive
   (mirrors the ``IF ACCT-CURR-BAL <= ZEROS`` branch at COBIL00C line
   198);
3. Resolve the owning card via CardCrossReference lookup (``SELECT
   card_cross_reference WHERE acct_id = :acct_id``);
4. Read the current highest ``tran_id`` (via both ``func.max`` and
   ``ORDER BY ... DESC LIMIT 1`` — equivalent but cross-verified to
   detect concurrency anomalies);
5. ``INSERT`` a new Transaction row carrying the COBIL00C-fixed
   metadata (``type_cd='02'``, ``cat_cd='0002'``, ``source='POS
   TERM'``, ``description='BILL PAYMENT - ONLINE'``,
   ``merchant_id='999999999'``, ``merchant_name='BILL PAYMENT'``,
   ``merchant_city='N/A'``, ``merchant_zip='N/A'``) plus the
   request-specific amount, card_num (from xref), and UTC timestamp;
6. ``UPDATE`` the account balance using the
   :func:`~src.shared.utils.decimal_utils.subtract` helper
   (Banker's-rounded Decimal subtraction);
7. ``await session.commit()`` commits INSERT + UPDATE atomically
   (replacing implicit CICS SYNCPOINT);
8. On any exception: ``await session.rollback()`` aborts both writes
   (replacing explicit CICS SYNCPOINT ROLLBACK).

The returned :class:`BillPaymentResponse` carries the echoed
``acct_id``, the ``amount`` applied, the **new** ``current_balance``
(post-deduction), a ``confirm`` flag (``'Y'`` on success / ``'N'`` on
business-logic failure such as unknown account or zero balance), and a
human-readable ``message`` suitable for display on the BMS-equivalent
JSON response.

COBOL → Python flow mapping (``COBIL00C.cbl`` PROCEDURE DIVISION):

=================================================  ==========================================
COBOL paragraph / statement                        Python equivalent (this module)
=================================================  ==========================================
``PROCESS-ENTER-KEY`` (entry)                      :meth:`BillService.pay_bill`
``READ-ACCTDAT-FILE`` (L174, L184)                 ``select(Account).where(acct_id == ...)``
``IF ACCT-CURR-BAL <= ZEROS`` (L198-205)           ``if account.curr_bal <= Decimal('0')``
``READ-CXACAIX-FILE`` (L211)                       ``select(CardCrossReference).where(...)``
``STARTBR-TRANSACT-FILE`` (L213)                   — absorbed into ``select(func.max())``
``READPREV-TRANSACT-FILE`` (L214)                  ``select(...).order_by(desc()).limit(1)``
``ENDBR-TRANSACT-FILE`` (L215)                     — (no-op; cursor auto-closes)
``ADD 1 TO WS-TRAN-ID-NUM`` (L217)                 ``int(max_tran_id) + 1``
``INITIALIZE TRAN-RECORD`` (L218)                  ``Transaction(...)`` fresh instance
``MOVE '02' TO TRAN-TYPE-CD`` (L220)               ``type_cd=_TRAN_TYPE_CD_BILL_PAYMENT``
``MOVE 2 TO TRAN-CAT-CD`` (L221)                   ``cat_cd=_TRAN_CAT_CD_BILL_PAYMENT``
``MOVE 'POS TERM' TO TRAN-SOURCE`` (L222)          ``source=_TRAN_SOURCE_POS_TERMINAL``
``MOVE 'BILL PAYMENT - ONLINE' TO`` (L223)         ``description=_TRAN_DESC_BILL_PAYMENT``
``MOVE ACCT-CURR-BAL TO TRAN-AMT`` (L224)          ``amount=request.amount`` (see note)
``MOVE XREF-CARD-NUM TO TRAN-CARD-NUM`` (L225)     ``card_num=xref.card_num``
``MOVE 999999999 TO TRAN-MERCHANT-ID`` (L226)      ``merchant_id=_TRAN_MERCHANT_ID_BILL_PAY``
``MOVE 'BILL PAYMENT' TO TRAN-...-NAME`` (L227)    ``merchant_name=_TRAN_MERCHANT_NAME_BILL_PAY``
``MOVE 'N/A' TO TRAN-MERCHANT-CITY`` (L228)        ``merchant_city=_TRAN_MERCHANT_CITY_BILL_PAY``
``MOVE 'N/A' TO TRAN-MERCHANT-ZIP`` (L229)         ``merchant_zip=_TRAN_MERCHANT_ZIP_BILL_PAY``
``GET-CURRENT-TIMESTAMP`` (L230, L249-267)         ``datetime.now(UTC).strftime(...)``
``WRITE-TRANSACT-FILE`` (L233)                     ``self.db.add(new_transaction)``
``COMPUTE ACCT-CURR-BAL = ... - TRAN-AMT`` (L234)  ``account.curr_bal = subtract(curr_bal, amt)``
``UPDATE-ACCTDAT-FILE`` (L235)                     — (dirty-tracked, flushed on commit)
implicit SYNCPOINT (transaction end)               ``await self.db.commit()``
any CICS ABEND / ROLLBACK path                     ``await self.db.rollback()`` (except block)
=================================================  ==========================================

.. note::

   The COBOL source unconditionally sets ``TRAN-AMT = ACCT-CURR-BAL``
   (line 224) — i.e., the mainframe always paid the full outstanding
   balance. The modernized API contract in
   :class:`~src.shared.schemas.bill_schema.BillPaymentRequest` instead
   accepts an explicit positive ``amount`` field so the caller can
   make partial payments. This is a deliberate broadening of the
   business contract (documented in ``bill_schema.py``) — the dual-
   write atomicity and the monetary-precision invariants are
   preserved unchanged.

Decimal precision
-----------------
Every monetary quantity handled by this module is a
:class:`decimal.Decimal` with 2 decimal places, matching the COBOL
``PIC S9(n)V99`` fixed-point semantics. Arithmetic operations use
:func:`~src.shared.utils.decimal_utils.subtract` (Banker's-rounded
subtraction, matching COBOL ``ROUNDED`` keyword) rather than raw
Python ``-`` to guarantee ``ROUND_HALF_EVEN`` behavior. The
:func:`~src.shared.utils.decimal_utils.safe_decimal` and
:func:`~src.shared.utils.decimal_utils.round_financial` helpers are
applied to defensive-normalise caller-supplied amounts before they
enter the dual-write code path.

Observability
-------------
All bill-payment events emit structured log records via the module
logger. Log fields include ``acct_id``, ``tran_id``, ``amount``, and
``new_balance`` so that CloudWatch Logs Insights queries can
correlate payment activity by account or reconstruct dual-write
histories after an incident. Log levels:

* ``INFO``  — successful payment committed to the database.
* ``WARNING`` — business-logic rejection (account not found,
  zero / negative balance, missing card cross-reference). These are
  expected outcomes for invalid client input; the service returns a
  :class:`BillPaymentResponse` with ``confirm='N'`` rather than
  raising.
* ``ERROR`` — unexpected database / driver failure. The session is
  rolled back and the exception is re-raised for the FastAPI error-
  handler middleware to translate into an HTTP 500 response.

Atomicity guarantees
--------------------
The dual-write is performed inside a single ``AsyncSession``
transaction. ``session.add(new_transaction)`` stages the INSERT;
``account.curr_bal = ...`` mutates the ORM-managed Account entity
(which SQLAlchemy dirty-tracks into an UPDATE); ``session.commit()``
issues both statements in a single database round-trip within a
single PostgreSQL ``BEGIN/COMMIT`` transaction. If either statement
fails (constraint violation, StaleDataError from the Account's
``version_id`` optimistic-concurrency column, network error, etc.),
PostgreSQL rolls back the entire transaction automatically and the
``except`` block in :meth:`pay_bill` invokes ``session.rollback()``
to reset the SQLAlchemy session state.

See Also
--------
* AAP §0.5.1 — File-by-File Transformation Plan (``bill_service.py`` row).
* AAP §0.7.1 — Refactoring-Specific Rules (dual-write atomicity, Decimal).
* AAP §0.7.2 — Special Instructions (Financial Precision, Security).
* ``src/shared/models/account.py`` — ORM model (has ``version_id`` OCC).
* ``src/shared/models/transaction.py`` — ORM model (has
  ``ix_transaction_proc_ts`` B-tree index).
* ``src/shared/models/card_cross_reference.py`` — ORM model
  (has ``ix_card_cross_reference_acct_id`` B-tree index).
* ``src/shared/schemas/bill_schema.py`` — request / response schemas.
* ``src/shared/utils/decimal_utils.py`` — COBOL-compatible Decimal
  arithmetic helpers.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import desc, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from src.shared.models.account import Account
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.transaction import Transaction
from src.shared.schemas.bill_schema import (
    BillPaymentRequest,
    BillPaymentResponse,
)
from src.shared.utils.decimal_utils import (
    round_financial,
    safe_decimal,
    subtract,
)

# ============================================================================
# Module-level configuration
# ============================================================================

#: Module logger. Structured log records flow to CloudWatch Logs via
#: the ECS ``awslogs`` driver. Logs Insights queries can filter by
#: ``logger_name = 'src.api.services.bill_service'`` to isolate bill-
#: payment activity across the API fleet.
logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# COBOL-fixed Transaction-record metadata from ``app/cbl/COBIL00C.cbl``.
#
# These constants encode the *fixed* values that the COBOL program
# unconditionally wrote to every bill-payment Transaction row
# (lines 220-229). Lifting them out of the method body to module
# constants makes them grep-able by COBOL field name and keeps the
# ``pay_bill`` implementation free of magic strings.
# ----------------------------------------------------------------------------

#: Transaction type code for a bill payment — ``TRAN-TYPE-CD`` PIC X(02).
#: Source: COBIL00C.cbl line 220 (``MOVE '02' TO TRAN-TYPE-CD``).
_TRAN_TYPE_CD_BILL_PAYMENT: str = "02"

#: Transaction category code for a bill payment — ``TRAN-CAT-CD``.
#: The COBOL field is declared as ``PIC 9(04)`` (4-digit numeric) and
#: the source assigns the literal ``2`` (COBIL00C.cbl line 221), which
#: COBOL zero-pads to the 4-digit storage width as ``0002``. The
#: modernized Python model (:class:`Transaction.cat_cd`) is declared
#: as ``String(4)`` to preserve leading zeros; we therefore store the
#: explicit ``'0002'`` string to match the COBOL wire format exactly.
_TRAN_CAT_CD_BILL_PAYMENT: str = "0002"

#: Transaction source channel — ``TRAN-SOURCE`` PIC X(10).
#: Source: COBIL00C.cbl line 222 (``MOVE 'POS TERM' TO TRAN-SOURCE``).
#: The COBOL literal ``'POS TERM'`` is 8 characters; COBOL pads it to
#: the 10-char storage width with trailing spaces. The Python model
#: column is ``String(10)`` so we store the same 8-char string and let
#: PostgreSQL ``VARCHAR`` semantics treat trailing-space parity as
#: equivalent for comparison (the column is not indexed and is not
#: used in any WHERE clause, so trailing-space handling is moot).
_TRAN_SOURCE_POS_TERMINAL: str = "POS TERM"

#: Transaction description — ``TRAN-DESC`` PIC X(100).
#: Source: COBIL00C.cbl line 223
#: (``MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC``).
_TRAN_DESC_BILL_PAYMENT: str = "BILL PAYMENT - ONLINE"

#: Fixed merchant ID for bill-payment synthetic transactions —
#: ``TRAN-MERCHANT-ID`` PIC 9(09). COBOL assigns the literal
#: ``999999999`` (COBIL00C.cbl line 226) as a sentinel value
#: indicating "not a merchant-originated transaction — this is an
#: internal bill payment". The Python model column is ``String(9)``
#: so we store ``"999999999"`` to preserve leading zeros if any
#: (here, none; the value is all 9s).
_TRAN_MERCHANT_ID_BILL_PAYMENT: str = "999999999"

#: Fixed merchant name — ``TRAN-MERCHANT-NAME`` PIC X(50).
#: Source: COBIL00C.cbl line 227.
_TRAN_MERCHANT_NAME_BILL_PAYMENT: str = "BILL PAYMENT"

#: Fixed merchant city — ``TRAN-MERCHANT-CITY`` PIC X(50).
#: Source: COBIL00C.cbl line 228.
_TRAN_MERCHANT_CITY_BILL_PAYMENT: str = "N/A"

#: Fixed merchant ZIP — ``TRAN-MERCHANT-ZIP`` PIC X(10).
#: Source: COBIL00C.cbl line 229.
_TRAN_MERCHANT_ZIP_BILL_PAYMENT: str = "N/A"

# ----------------------------------------------------------------------------
# Transaction ID generation constants.
#
# COBOL ``WS-TRAN-ID-NUM`` is a numeric working-storage field that
# gets MOVEd into the ``TRAN-ID`` PIC X(16) field. To preserve the
# lexicographic-max semantics of ``SELECT MAX(tran_id)`` — which
# requires fixed-width zero-padded numeric strings — we materialise
# the next ID as ``str(n).zfill(16)``.
# ----------------------------------------------------------------------------

#: Width of the ``tran_id`` primary-key column, matching COBOL
#: ``TRAN-ID PIC X(16)``. Declared as a named constant so the
#: zero-pad width cannot drift silently from the column DDL.
_TRAN_ID_WIDTH: int = 16

#: Starting transaction ID used when the ``transaction`` table is
#: empty. The value ``'0000000000000001'`` (16 chars) is the
#: lexicographic minimum of the valid ID space and mirrors the
#: COBOL convention of ``MOVE 1 TO WS-TRAN-ID-NUM`` when no previous
#: transaction exists.
_INITIAL_TRAN_ID: str = "0000000000000001"

# ----------------------------------------------------------------------------
# Response-message constants.
#
# The COBOL program surfaces user-facing strings via ``WS-MESSAGE`` on
# the BMS screen. We preserve the key message byte-for-byte so that
# API clients migrating from a terminal-emulator UI see identical
# text. The success message is constructed at runtime to interpolate
# the generated ``tran_id``.
# ----------------------------------------------------------------------------

#: Message displayed when the account's current balance is zero or
#: negative. Source: COBIL00C.cbl line 201-202 (``MOVE 'You have
#: nothing to pay...' TO WS-MESSAGE``). Preserved byte-for-byte
#: including the trailing ellipsis per AAP §0.7.1 (preserve existing
#: error messages exactly).
_MSG_ZERO_BALANCE: str = "You have nothing to pay..."

#: Message displayed when the requested account does not exist in the
#: ``account`` table. The COBOL ``READ-ACCTDAT-FILE`` paragraph
#: surfaces a similar "Account not found" message via the NOTFND
#: branch at COBIL00C.cbl line 332 (``MOVE 'Account not found...' TO
#: WS-MESSAGE``).
_MSG_ACCOUNT_NOT_FOUND: str = "Account not found..."

#: Message displayed when no ``card_cross_reference`` row exists for
#: the supplied account. COBIL00C surfaces "Card not found" via the
#: READ-CXACAIX NOTFND branch (line 379).
_MSG_XREF_NOT_FOUND: str = "Card not found..."

#: Success message format string. The COBIL00C program displays
#: "Payment successful. Your Transaction ID is <tran_id>." via the
#: WRITE-TRANSACT-FILE response-handling logic (line 458-459).
_MSG_PAYMENT_SUCCESS_FMT: str = "Payment successful. Your Transaction ID is {tran_id}."

# ----------------------------------------------------------------------------
# Timestamp formatting constants.
#
# The COBOL ``WS-TIMESTAMP`` layout from ``GET-CURRENT-TIMESTAMP``
# (COBIL00C.cbl lines 249-267, produced via ``EXEC CICS ASKTIME`` +
# ``EXEC CICS FORMATTIME``) is a 26-character fixed-width string:
#
#     Positions 01-10:  YYYY-MM-DD   (from WS-CUR-DATE-X10)
#     Position  11:     space        (INITIALIZE default for FILLER)
#     Positions 12-19:  HH:MM:SS     (from WS-CUR-TIME-X08)
#     Position  20:     '.'          (INITIALIZE default for FILLER)
#     Positions 21-26:  NNNNNN       (6-digit microsecond; COBOL sets
#                                     to ZEROS, Python uses real μs)
#
# Python's ``datetime.strftime('%Y-%m-%d %H:%M:%S.%f')`` produces
# exactly this 26-character format. The COBOL zero-initialised the
# microsecond field; the Python implementation uses the actual
# microsecond value from the system clock which is richer but remains
# the same 26-char width (enforced by the ``String(26)`` column).
# ----------------------------------------------------------------------------

#: :func:`datetime.datetime.strftime` format string producing a
#: 26-character COBOL-compatible timestamp. The trailing ``%f`` emits
#: a 6-digit zero-padded microsecond, matching the COBOL
#: ``WS-TIMESTAMP-TM-MS6 PIC 9(06)`` storage.
_TIMESTAMP_FORMAT: str = "%Y-%m-%d %H:%M:%S.%f"

#: Decimal constant ``0.00`` for zero-balance comparison. Using a
#: module-level constant avoids constructing a new :class:`Decimal`
#: on every ``pay_bill`` invocation and makes grep-able the exact
#: threshold used to mirror the COBOL ``<= ZEROS`` test.
_ZERO_BALANCE: Decimal = Decimal("0.00")


# ============================================================================
# BillService
# ============================================================================


class BillService:
    """Bill-payment service implementing the atomic dual-write contract.

    This class is the modernized Python equivalent of the COBIL00C.cbl
    CICS program. It encapsulates the business logic previously
    embedded in the ``PROCESS-ENTER-KEY`` paragraph and its helper
    paragraphs (``READ-ACCTDAT-FILE``, ``READ-CXACAIX-FILE``,
    ``STARTBR-TRANSACT-FILE``, ``READPREV-TRANSACT-FILE``,
    ``ENDBR-TRANSACT-FILE``, ``WRITE-TRANSACT-FILE``,
    ``UPDATE-ACCTDAT-FILE``, ``GET-CURRENT-TIMESTAMP``).

    The class is designed as a thin stateful facade over an
    :class:`sqlalchemy.ext.asyncio.AsyncSession`: all database I/O
    flows through the injected session, which owns the transactional
    boundary. Each call to :meth:`pay_bill` performs exactly one
    logical transaction — the Transaction INSERT + Account UPDATE
    dual-write — and either commits both or rolls both back.

    Dependency Injection
    --------------------
    In production the session is wired up via FastAPI's dependency-
    injection machinery (see ``src/api/dependencies.py`` which yields
    an ``AsyncSession`` per request). In unit tests, the session is
    typically a real session connected to a test-containers
    PostgreSQL, or a mocked session for isolated logic tests.

    Thread safety
    -------------
    ``BillService`` instances are NOT thread-safe and should not be
    shared across concurrent requests — each request must construct
    its own service with its own session (FastAPI's default dep-inj
    behavior ensures this). The underlying ``AsyncSession`` also has
    per-request semantics and is not designed for shared access.

    Example
    -------
    The typical router usage is::

        @router.post('/bills/pay')
        async def pay_bill(
            request: BillPaymentRequest,
            db: Annotated[AsyncSession, Depends(get_db_session)],
        ) -> BillPaymentResponse:
            service = BillService(db)
            return await service.pay_bill(request)

    Attributes
    ----------
    db : sqlalchemy.ext.asyncio.AsyncSession
        The async SQLAlchemy session that owns the transactional
        context for this service. All database reads and writes go
        through this session; :meth:`pay_bill` calls
        ``session.commit()`` on success and ``session.rollback()``
        on any uncaught exception.

    See Also
    --------
    :class:`src.shared.schemas.bill_schema.BillPaymentRequest` :
        The input payload schema.
    :class:`src.shared.schemas.bill_schema.BillPaymentResponse` :
        The output payload schema.
    """

    def __init__(self, db: AsyncSession) -> None:
        """Construct a :class:`BillService` bound to an async session.

        Stores the supplied session as an instance attribute; does not
        validate it, does not open a transaction, and does not perform
        any I/O. The session is expected to be already configured with
        a live connection to Aurora PostgreSQL (or a test database) —
        this is the responsibility of the caller (typically FastAPI's
        dependency-injection framework via ``src/api/dependencies.py``).

        Parameters
        ----------
        db : sqlalchemy.ext.asyncio.AsyncSession
            An open async SQLAlchemy session. Owned by the caller —
            the service does not close the session on its own; FastAPI
            dep-inj closes it when the request scope exits.
        """
        self.db: AsyncSession = db

    # -----------------------------------------------------------------
    # Public API
    # -----------------------------------------------------------------

    async def pay_bill(
        self,
        request: BillPaymentRequest,
    ) -> BillPaymentResponse:
        """Apply a bill payment as an atomic dual-write transaction.

        Implements the 9-step flow documented in the module docstring
        (see "Service contract" section above). Mirrors the
        ``PROCESS-ENTER-KEY`` paragraph from ``COBIL00C.cbl`` including
        the zero-balance check, the card cross-reference lookup, the
        tran_id auto-generation pattern, and the Transaction-INSERT +
        Account-UPDATE dual-write with automatic rollback on error.

        Business-logic failures (account not found, zero / negative
        balance, missing card cross-reference) return a
        :class:`BillPaymentResponse` with ``confirm='N'`` and a
        human-readable ``message``. These failures do NOT raise —
        they are expected outcomes of invalid input and are surfaced
        through the response schema so the API can return HTTP 200
        with the structured failure payload. This mirrors the COBOL
        behavior where such errors were displayed on the BMS screen
        rather than abending the transaction.

        Unexpected database or driver failures (constraint violations,
        StaleDataError from Account's optimistic-concurrency
        ``version_id`` column, network errors, etc.) trigger a
        ``session.rollback()`` and are re-raised as-is for the FastAPI
        error-handler middleware to translate into an HTTP 500
        response. Any partial writes (Transaction INSERT without the
        Account UPDATE, or vice versa) are impossible because
        PostgreSQL rolls back the entire BEGIN/COMMIT transaction on
        any error.

        Parameters
        ----------
        request : BillPaymentRequest
            Validated request payload carrying the target ``acct_id``
            (11-char string) and the ``amount`` to debit (positive
            :class:`decimal.Decimal`). Pydantic has already enforced
            the ``max_length=11`` and ``> Decimal('0')`` constraints
            before this method is invoked.

        Returns
        -------
        BillPaymentResponse
            Structured response containing:

            * ``acct_id`` : str — echoed from request;
            * ``amount`` : Decimal — applied payment amount (==
              request.amount on success; echoed from request on
              business-logic failure);
            * ``current_balance`` : Decimal — NEW balance after the
              debit (= old balance - amount) on success; current
              balance on business-logic failure; ``Decimal('0.00')``
              when the account was not found;
            * ``confirm`` : str — ``'Y'`` on successful dual-write
              commit, ``'N'`` on business-logic failure;
            * ``message`` : Optional[str] — human-readable status.

        Raises
        ------
        sqlalchemy.exc.SQLAlchemyError
            Any database error not classified as a business-logic
            failure (e.g., constraint violation on
            ``transaction.tran_id`` PK collision from a concurrent
            INSERT, StaleDataError from ``account.version_id``
            optimistic-concurrency check, connection loss, etc.). The
            session is rolled back before the exception propagates.
        Exception
            Any other unexpected exception. The session is rolled back
            before the exception propagates so the caller (FastAPI
            middleware) can safely retry or return an HTTP 500.

        Notes
        -----
        All monetary arithmetic uses :class:`decimal.Decimal` —
        never :class:`float` — via the
        :func:`~src.shared.utils.decimal_utils.subtract`,
        :func:`~src.shared.utils.decimal_utils.safe_decimal`, and
        :func:`~src.shared.utils.decimal_utils.round_financial`
        helpers which apply Banker's rounding to 2 decimal places
        (matching COBOL ``ROUNDED``).
        """
        # Structured-logging context common to every log line from this
        # invocation. Populating ``extra`` dict entries lets CloudWatch
        # Logs Insights queries group records by ``acct_id`` without
        # parsing the free-text message.
        log_context: dict[str, object] = {
            "acct_id": request.acct_id,
            "request_amount": str(request.amount),
        }
        logger.info("Bill payment requested", extra=log_context)

        try:
            # -------------------------------------------------------
            # Step 1: Read the Account row by primary key.
            #
            # COBOL mapping:
            #   PERFORM READ-ACCTDAT-FILE   (COBIL00C.cbl L174, L184)
            #   EXEC CICS READ FILE('ACCTDAT')
            #     INTO(ACCOUNT-RECORD)
            #     RIDFLD(ACCT-ID)
            #     UPDATE
            #   END-EXEC
            #
            # In SQLAlchemy, the equivalent is a SELECT by PK. The
            # UPDATE intent flag on the CICS READ — which on the
            # mainframe acquires an exclusive record lock — is
            # preserved implicitly: the subsequent Account mutation
            # will be serialised by PostgreSQL's row-level locking
            # when the commit is issued (and further by the
            # optimistic-concurrency ``version_id`` column declared
            # on the Account ORM class).
            # -------------------------------------------------------
            account_stmt = select(Account).where(Account.acct_id == request.acct_id)
            account_result = await self.db.execute(account_stmt)
            account = account_result.scalar_one_or_none()

            if account is None:
                logger.warning(
                    "Bill payment rejected: account not found",
                    extra=log_context,
                )
                return BillPaymentResponse(
                    acct_id=request.acct_id,
                    amount=request.amount,
                    current_balance=_ZERO_BALANCE,
                    confirm="N",
                    message=_MSG_ACCOUNT_NOT_FOUND,
                )

            # Defensive normalisation: convert the ORM Decimal via
            # ``safe_decimal`` to guarantee we have a 2-decimal
            # :class:`Decimal` even in edge cases where the ORM could
            # return a different scale (e.g., legacy data). This is
            # purely defensive — the PostgreSQL NUMERIC(15,2) column
            # enforces the scale at storage time.
            current_balance: Decimal = safe_decimal(account.curr_bal)
            log_context["current_balance"] = str(current_balance)

            # -------------------------------------------------------
            # Step 2: Validate account balance is strictly positive.
            #
            # COBOL mapping:
            #   IF ACCT-CURR-BAL <= ZEROS AND
            #      ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES
            #       MOVE 'Y'     TO WS-ERR-FLG
            #       MOVE 'You have nothing to pay...' TO WS-MESSAGE
            #       MOVE -1       TO ACTIDINL OF COBIL0AI
            #       PERFORM SEND-BILLPAY-SCREEN
            #   END-IF                      (COBIL00C.cbl L198-205)
            #
            # The ``ACTIDINI NOT = SPACES AND LOW-VALUES`` guard is
            # already enforced upstream by the Pydantic validator on
            # ``BillPaymentRequest.acct_id`` (rejects empty / blank
            # strings); we therefore only need the balance check
            # here. Comparison uses Decimal (never float) — the
            # module-level ``_ZERO_BALANCE`` constant makes the
            # threshold grep-able.
            # -------------------------------------------------------
            if current_balance <= _ZERO_BALANCE:
                logger.warning(
                    "Bill payment rejected: zero or negative balance",
                    extra=log_context,
                )
                return BillPaymentResponse(
                    acct_id=request.acct_id,
                    amount=request.amount,
                    current_balance=current_balance,
                    confirm="N",
                    message=_MSG_ZERO_BALANCE,
                )

            # -------------------------------------------------------
            # Step 3: Resolve the owning card via CardCrossReference.
            #
            # COBOL mapping:
            #   PERFORM READ-CXACAIX-FILE  (COBIL00C.cbl L211)
            #   EXEC CICS READ FILE('CXACAIX')
            #     INTO(CARD-XREF-RECORD)
            #     RIDFLD(XREF-ACCT-ID)
            #   END-EXEC
            #
            # ``CXACAIX`` is the alternate-index path over the
            # ``CARDXREF`` KSDS — a non-unique index on XREF-ACCT-ID.
            # In the modernized schema this maps to the
            # ``ix_card_cross_reference_acct_id`` B-tree index on the
            # ``card_cross_reference.acct_id`` column. An account
            # theoretically has multiple cards (one xref row per
            # card); we pick the first match via
            # ``.scalars().first()`` to mirror the CICS READ-by-AIX
            # semantic which returns the first matching record.
            # -------------------------------------------------------
            xref_stmt = select(CardCrossReference).where(CardCrossReference.acct_id == request.acct_id)
            xref_result = await self.db.execute(xref_stmt)
            xref = xref_result.scalars().first()

            if xref is None:
                logger.warning(
                    "Bill payment rejected: no card cross-reference found for account",
                    extra=log_context,
                )
                return BillPaymentResponse(
                    acct_id=request.acct_id,
                    amount=request.amount,
                    current_balance=current_balance,
                    confirm="N",
                    message=_MSG_XREF_NOT_FOUND,
                )

            card_num: str = xref.card_num
            log_context["card_num"] = card_num

            # -------------------------------------------------------
            # Step 4: Generate the next transaction ID.
            #
            # COBOL mapping:
            #   MOVE HIGH-VALUES TO TRAN-ID
            #   PERFORM STARTBR-TRANSACT-FILE        (L213)
            #   PERFORM READPREV-TRANSACT-FILE       (L214)
            #   PERFORM ENDBR-TRANSACT-FILE          (L215)
            #   MOVE TRAN-ID     TO WS-TRAN-ID-NUM
            #   ADD 1 TO WS-TRAN-ID-NUM              (L217)
            #
            # The CICS ``STARTBR-with-HIGH-VALUES`` +
            # ``READPREV`` idiom reads the last record in the
            # ``TRANSACT`` file (the row with the lexically-greatest
            # tran_id). SQL offers two equivalent expressions of
            # this semantic:
            #
            #   (a) SELECT MAX(tran_id) FROM transactions     — func.max()
            #   (b) SELECT tran_id FROM transactions
            #       ORDER BY tran_id DESC LIMIT 1            — desc()
            #
            # Both return identical values within a single
            # transaction's snapshot isolation. We issue (a) first
            # (cheaper — PostgreSQL can use the PK B-tree index to
            # return the max in O(1)) and (b) as a defensive
            # re-read whose result is cross-checked against (a);
            # any mismatch is logged but does not abort the
            # transaction (it would indicate a PostgreSQL
            # implementation bug, not a user-facing issue).
            # -------------------------------------------------------
            # (a) Primary query: aggregate MAX via func.max()
            max_agg_stmt = select(func.max(Transaction.tran_id))
            max_agg_result = await self.db.execute(max_agg_stmt)
            max_tran_id_agg = max_agg_result.scalar()

            # (b) Verification query: ORDER BY ... DESC LIMIT 1 via desc()
            max_sort_stmt = select(Transaction.tran_id).order_by(desc(Transaction.tran_id)).limit(1)
            max_sort_result = await self.db.execute(max_sort_stmt)
            max_tran_id_sort = max_sort_result.scalar()

            # Normalise the two readings — treat ``None`` and empty-
            # string as "no previous row" (empty table).
            normalised_agg = max_tran_id_agg or None
            normalised_sort = max_tran_id_sort or None

            if normalised_agg != normalised_sort:
                # Should be unreachable inside a snapshot-consistent
                # transaction — log at WARNING for observability.
                logger.warning(
                    "MAX/ORDER-BY aggregate mismatch during tran_id generation; using max of both",
                    extra={
                        **log_context,
                        "max_via_func_max": normalised_agg,
                        "max_via_order_by_desc": normalised_sort,
                    },
                )

            # Take the lexically-greatest of the two readings.
            # Because ``tran_id`` values are always 16-char zero-
            # padded numeric strings, lexical max == numeric max.
            candidates = [v for v in (normalised_agg, normalised_sort) if v is not None]
            last_tran_id: str | None = max(candidates) if candidates else None

            # Increment to generate the next ID, or start from the
            # initial ID if the table is empty.
            if last_tran_id is None:
                new_tran_id = _INITIAL_TRAN_ID
            else:
                try:
                    next_id_num = int(last_tran_id) + 1
                except (ValueError, TypeError):
                    # Unparseable existing ID (legacy non-numeric
                    # data). Log at ERROR and reject the payment —
                    # we cannot safely generate a collision-free
                    # successor.
                    logger.error(
                        "Cannot parse existing tran_id as integer; refusing to auto-generate successor",
                        extra={
                            **log_context,
                            "last_tran_id": last_tran_id,
                        },
                    )
                    await self.db.rollback()
                    return BillPaymentResponse(
                        acct_id=request.acct_id,
                        amount=request.amount,
                        current_balance=current_balance,
                        confirm="N",
                        message="Unable to generate transaction ID",
                    )
                new_tran_id = str(next_id_num).zfill(_TRAN_ID_WIDTH)

            log_context["tran_id"] = new_tran_id

            # -------------------------------------------------------
            # Step 5: Generate the COBOL-compatible 26-char timestamp.
            #
            # COBOL mapping:
            #   PERFORM GET-CURRENT-TIMESTAMP        (L230, L249-267)
            #   EXEC CICS ASKTIME ABSTIME(WS-ABS-TIME)
            #   EXEC CICS FORMATTIME
            #     ABSTIME(WS-ABS-TIME)
            #     YYYYMMDD(WS-CUR-DATE-X10) DATESEP('-')
            #     TIME(WS-CUR-TIME-X08)      TIMESEP(':')
            #   END-EXEC
            #
            # Produces the 26-char ``YYYY-MM-DD HH:MM:SS.NNNNNN``
            # format described in the module docstring. UTC is used
            # as the reference timezone to ensure consistent
            # timestamp generation regardless of the ECS task's
            # OS-level timezone configuration (the ECS Fargate
            # default is UTC, but this guards against misconfigured
            # environments).
            # -------------------------------------------------------
            now_utc: datetime = datetime.now(UTC)
            timestamp_str: str = now_utc.strftime(_TIMESTAMP_FORMAT)
            log_context["timestamp"] = timestamp_str

            # -------------------------------------------------------
            # Step 6: Prepare the payment amount with defensive
            # Decimal normalisation.
            #
            # ``safe_decimal`` guarantees a 2-decimal :class:`Decimal`
            # even if the caller bypassed Pydantic validation (e.g.,
            # a direct unit test). ``round_financial`` then applies
            # Banker's rounding to exactly 2 decimal places — matching
            # the COBOL ``PIC S9(09)V99`` storage scale for
            # ``TRAN-AMT`` — before the value is persisted to the
            # ``transaction.amount`` NUMERIC(15,2) column.
            # -------------------------------------------------------
            payment_amount: Decimal = safe_decimal(request.amount)
            transaction_amount: Decimal = round_financial(payment_amount)
            log_context["payment_amount"] = str(payment_amount)
            log_context["transaction_amount"] = str(transaction_amount)

            # -------------------------------------------------------
            # Step 7: Stage the Transaction INSERT.
            #
            # COBOL mapping:
            #   INITIALIZE TRAN-RECORD                (L218)
            #   MOVE WS-TRAN-ID-NUM       TO TRAN-ID  (L219)
            #   MOVE '02'                 TO TRAN-TYPE-CD  (L220)
            #   MOVE 2                    TO TRAN-CAT-CD   (L221)
            #   MOVE 'POS TERM'           TO TRAN-SOURCE   (L222)
            #   MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC  (L223)
            #   MOVE ACCT-CURR-BAL        TO TRAN-AMT      (L224)
            #   MOVE XREF-CARD-NUM        TO TRAN-CARD-NUM (L225)
            #   MOVE 999999999            TO TRAN-MERCHANT-ID
            #   MOVE 'BILL PAYMENT'       TO TRAN-MERCHANT-NAME
            #   MOVE 'N/A'                TO TRAN-MERCHANT-CITY
            #   MOVE 'N/A'                TO TRAN-MERCHANT-ZIP
            #   MOVE WS-TIMESTAMP         TO TRAN-ORIG-TS
            #                                TRAN-PROC-TS  (L231-232)
            #   PERFORM WRITE-TRANSACT-FILE             (L233)
            #
            # See the module-level ``_TRAN_*_BILL_PAYMENT`` constants
            # for the fixed-value fields. Note that line 224's
            # ``MOVE ACCT-CURR-BAL TO TRAN-AMT`` is replaced by
            # ``amount=transaction_amount`` — the modernized API
            # allows partial payments per the BillPaymentRequest
            # contract (see module docstring note).
            # -------------------------------------------------------
            new_transaction = Transaction(
                tran_id=new_tran_id,
                type_cd=_TRAN_TYPE_CD_BILL_PAYMENT,
                cat_cd=_TRAN_CAT_CD_BILL_PAYMENT,
                source=_TRAN_SOURCE_POS_TERMINAL,
                description=_TRAN_DESC_BILL_PAYMENT,
                amount=transaction_amount,
                merchant_id=_TRAN_MERCHANT_ID_BILL_PAYMENT,
                merchant_name=_TRAN_MERCHANT_NAME_BILL_PAYMENT,
                merchant_city=_TRAN_MERCHANT_CITY_BILL_PAYMENT,
                merchant_zip=_TRAN_MERCHANT_ZIP_BILL_PAYMENT,
                card_num=card_num,
                orig_ts=timestamp_str,
                proc_ts=timestamp_str,
            )
            self.db.add(new_transaction)

            # -------------------------------------------------------
            # Step 8: Mutate the Account balance (dirty-tracked UPDATE).
            #
            # COBOL mapping:
            #   COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT  (L234)
            #   PERFORM UPDATE-ACCTDAT-FILE                        (L235)
            #   EXEC CICS REWRITE FILE('ACCTDAT')
            #     FROM(ACCOUNT-RECORD)
            #   END-EXEC
            #
            # The SQLAlchemy equivalent is a direct attribute mutation
            # on the ORM-managed Account entity; the session's dirty-
            # tracker converts this into an UPDATE statement at
            # commit time. We use the :func:`subtract` helper from
            # :mod:`src.shared.utils.decimal_utils` to guarantee
            # Banker's-rounded Decimal subtraction to exactly 2
            # decimal places — matching COBOL ``COMPUTE ... ROUNDED``
            # semantics.
            #
            # Note on Account's optimistic-concurrency ``version_id``
            # column: SQLAlchemy will append ``AND version_id =
            # :old_version`` to the generated UPDATE. If a concurrent
            # writer committed an update between our SELECT (Step 1)
            # and this COMMIT, the UPDATE affects zero rows and
            # SQLAlchemy raises :class:`StaleDataError`. This
            # exception propagates out of :meth:`pay_bill` (after
            # rollback via the ``except`` block below), allowing the
            # caller to retry with a fresh Account read.
            # -------------------------------------------------------
            new_balance: Decimal = subtract(current_balance, payment_amount)
            account.curr_bal = new_balance
            log_context["new_balance"] = str(new_balance)

            # -------------------------------------------------------
            # Step 9: Commit the dual-write atomically.
            #
            # COBOL mapping:
            #   (implicit SYNCPOINT at CICS transaction end)
            #
            # ``session.flush()`` executes any pending INSERT/UPDATE
            # statements against the database so constraint failures
            # surface at this call site (rather than being deferred
            # until commit). ``session.commit()`` then finalises the
            # PostgreSQL BEGIN/COMMIT transaction, making both the
            # Transaction INSERT and the Account UPDATE durable
            # atomically.
            # -------------------------------------------------------
            await self.db.flush()
            await self.db.commit()

            logger.info("Bill payment succeeded", extra=log_context)

            # -------------------------------------------------------
            # Return the success response.
            #
            # COBOL mapping:
            #   WRITE-TRANSACT-FILE NORMAL response branch
            #   constructs the message "Payment successful. Your
            #   Transaction ID is <tran_id>." (COBIL00C.cbl L458-459).
            # -------------------------------------------------------
            return BillPaymentResponse(
                acct_id=request.acct_id,
                amount=transaction_amount,
                current_balance=new_balance,
                confirm="Y",
                message=_MSG_PAYMENT_SUCCESS_FMT.format(tran_id=new_tran_id),
            )

        except Exception as exc:
            # Unexpected error path — any exception (StaleDataError
            # from Account's version_id, PK collision on tran_id,
            # NUMERIC overflow, connection loss, …) triggers a full
            # rollback of both the staged INSERT and the staged
            # UPDATE. This mirrors the implicit CICS SYNCPOINT
            # ROLLBACK on abnormal transaction termination.
            logger.error(
                "Bill payment failed with unexpected error; rolling back dual-write",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            # Best-effort rollback. If the session is already
            # invalidated by the underlying connection, a secondary
            # exception from rollback() is logged but swallowed so
            # the original exception (which is more useful for
            # diagnosis) propagates to the caller.
            try:
                await self.db.rollback()
            except Exception:  # noqa: BLE001  — best-effort cleanup
                logger.exception(
                    "Rollback failed during bill-payment error recovery",
                    extra=log_context,
                )
            # Re-raise the original exception for the FastAPI error-
            # handler middleware to translate into an HTTP 500.
            raise


__all__: list[str] = [
    "BillService",
]
