# ============================================================================
# Source: app/cbl/COTRN00C.cbl  (Transaction List   — CICS transaction CT00, 699 lines)
#       + app/cbl/COTRN01C.cbl  (Transaction Detail — CICS transaction CT01, 330 lines)
#       + app/cbl/COTRN02C.cbl  (Transaction Add    — CICS transaction CT02, 783 lines)
#       + app/cpy/CVTRA05Y.cpy  (TRAN-RECORD 350-byte VSAM record layout)
#       + app/cpy/CVACT03Y.cpy  (CARD-XREF-RECORD 50-byte VSAM record layout)
#       + app/cpy-bms/COTRN00.CPY / COTRN01.CPY / COTRN02.CPY
#         (BMS symbolic-map layouts defining the request / response contracts)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS ``EXEC CICS STARTBR / READNEXT / READPREV / ENDBR FILE('TRANSACT')``
#   (browse-mode cursor pagination in COTRN00C.cbl) +
#   ``EXEC CICS READ   FILE('TRANSACT')`` (COTRN01C.cbl keyed detail read) +
#   ``EXEC CICS READ   FILE('CCXREF')``   (COTRN02C.cbl card→account lookup) +
#   ``EXEC CICS READ   FILE('CXACAIX')``  (COTRN02C.cbl account→card lookup) +
#   ``EXEC CICS STARTBR FILE('TRANSACT') + READPREV`` — HIGH-VALUES
#   positioning to read the lexically-greatest tran_id for auto-ID
#   generation (COTRN02C.cbl lines 444-449) +
#   ``EXEC CICS WRITE  FILE('TRANSACT') FROM(TRAN-RECORD)``
#
# becomes
#
#   SQLAlchemy 2.x async ``SELECT ... LIMIT ... OFFSET`` paginated queries
#   for the list endpoint, ``select(Transaction).where(tran_id == ...)``
#   keyed lookup for the detail endpoint, ``select(CardCrossReference)``
#   xref resolution for the add endpoint, ``select(func.max(tran_id))`` +
#   ``select(tran_id).order_by(desc).limit(1)`` dual-query for auto-ID,
#   ``session.add()`` + ``flush()`` + ``commit()`` for the INSERT.
#
# The target deployment is AWS ECS Fargate behind an Application Load
# Balancer, connecting to Aurora PostgreSQL via asyncpg; the database
# credentials come from AWS Secrets Manager in staging/production
# (injected via ECS task-definition secrets) and from the ``.env`` file
# in local development (docker-compose).
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
"""Transaction list, detail, and add service.

Converted from ``app/cbl/COTRN00C.cbl`` (699 lines — browse-mode
paginated transaction list, 10 rows/page), ``app/cbl/COTRN01C.cbl``
(330 lines — keyed transaction detail read by ``TRAN-ID``), and
``app/cbl/COTRN02C.cbl`` (783 lines — auto-ID generation via
``STARTBR + READPREV`` to file end, cross-reference resolution via
``CCXREF`` / ``CXACAIX`` lookup, and ``WRITE FILE('TRANSACT')``).

Covers features F-009 (transaction list), F-010 (transaction detail),
and F-011 (transaction add with auto-ID + xref resolution) from the
Agent Action Plan.

The service exposes :class:`TransactionService`, used by the
transaction router (``src/api/routers/transaction_router.py``) and
indirectly by the GraphQL resolvers (``src/api/graphql/queries.py``
and ``src/api/graphql/mutations.py``). Like
:class:`src.api.services.bill_service.BillService`, the class is
intentionally stateful in the database session only: no caches, no
in-memory session data, no mutable class attributes. The async
session is scoped to a single HTTP request and managed by the FastAPI
dependency system; transaction boundaries are owned by the caller.

COBOL → Python flow mapping (three PROCEDURE DIVISIONs merged):

==========================================  ===========================================
COBOL paragraph / statement                 Python equivalent (this module)
==========================================  ===========================================
COTRN00C ``PROCESS-PAGE-FORWARD``            :meth:`TransactionService.list_transactions`
COTRN00C ``STARTBR-TRANSACT-FILE`` L591-620  ``select(Transaction).order_by(tran_id)``
COTRN00C ``READNEXT-TRANSACT-FILE`` L624-654 ``.offset(...).limit(...)``
COTRN00C ``ENDBR-TRANSACT-FILE`` (implicit)  ``await self.db.execute(stmt)``
COTRN00C ``PROCESS-PF7-KEY`` (back)          Idempotent — page - 1 on re-issue
COTRN00C ``PROCESS-PF8-KEY`` (forward)       Idempotent — page + 1 on re-issue
COTRN01C ``PROCESS-ENTER-KEY`` L144-231      :meth:`TransactionService.get_transaction_detail`
COTRN01C ``READ-TRANSACT-FILE`` L263-300     ``select(Transaction).where(tran_id == :k)``
COTRN01C ``WHEN NOTFND``                     returns response with ``message``
                                             "Transaction ID NOT found..."
COTRN02C ``PROCESS-ENTER-KEY``               :meth:`TransactionService.add_transaction`
COTRN02C ``READ-CCXREF-FILE`` L609-635       ``select(CardCrossReference)
                                             .where(card_num == :k)``
COTRN02C ``READ-CXACAIX-FILE`` L576-604      ``select(CardCrossReference)
                                             .where(acct_id == :k)`` (AIX)
COTRN02C ``ADD-TRANSACTION`` L442-466        Auto-ID generation +
                                             ``self.db.add(transaction)``
COTRN02C ``STARTBR + READPREV`` L444-449     ``select(func.max(tran_id))`` +
                                             ``select(tran_id).order_by(desc(tran_id))
                                             .limit(1)`` (dual-query cross-verified)
COTRN02C ``WRITE-TRANSACT-FILE``             ``self.db.add(...)`` +
                                             ``await self.db.flush()`` +
                                             ``await self.db.commit()``
COTRN02C ``WHEN OTHER`` (any error)          rolled back via
                                             ``self.db.rollback()`` +
                                             response with error ``message``
==========================================  ===========================================

Cross-reference resolution contract
-----------------------------------
The COBOL ``VALIDATE-INPUT-KEY-FIELDS`` paragraph (COTRN02C.cbl
L193-230) accepts ONE of ``acct_id`` OR ``card_num`` from the user
and populates the other via a CCXREF / CXACAIX lookup. The modern
API's :class:`TransactionAddRequest` schema requires **both** fields
(see ``src/shared/schemas/transaction_schema.py``); therefore the
xref step in :meth:`TransactionService.add_transaction` acts as a
**validation** pass: it confirms that the (``acct_id``, ``card_num``)
pair is a valid cross-reference (the ``card_num`` exists in
``card_cross_reference`` AND the resolved ``acct_id`` equals the
requested ``acct_id``). Any mismatch returns a ``confirm='N'``
response with an explanatory message, mirroring the COBOL flow which
rejected the screen with a similar "Unable to lookup Card # in XREF
file..." message (COTRN02C.cbl line 633).

Auto-ID generation contract
---------------------------
:meth:`TransactionService.add_transaction` generates the new
transaction ID by reading the lexically-greatest existing
``tran_id`` via two equivalent queries (``SELECT MAX(tran_id)`` and
``SELECT tran_id ORDER BY tran_id DESC LIMIT 1``) and incrementing
by 1. Since all valid tran_ids are 16-character zero-padded numeric
strings, lexicographic max equals numeric max. The two queries
provide cross-verification inside a single snapshot-isolated
transaction: any mismatch is logged at WARNING level (it would
indicate a PostgreSQL anomaly). The empty-table case seeds the
first ID as ``'0000000000000001'`` — the lexicographic minimum of
the valid ID space — matching the COBOL convention where the
``INITIALIZE TRAN-RECORD`` + ``MOVE 1 TO WS-TRAN-ID-N`` pair seeded
the first write after the ``STARTBR`` + ``READPREV`` returned
``DFHRESP(ENDFILE)``.

Timestamp generation contract
-----------------------------
:meth:`TransactionService.add_transaction` generates the
``orig_ts`` and ``proc_ts`` fields using the helper
:func:`~src.shared.utils.date_utils.format_timestamp` which produces
a 26-character ``YYYY-MM-DD HH:MM:SS.ffffff`` string. The current UTC
time is used as the reference timezone to ensure consistent timestamp
generation regardless of the ECS task's OS-level timezone
configuration. This mirrors the COBOL flow where ``CURRENT-DATE`` /
``EXEC CICS ASKTIME + FORMATTIME`` produced a 26-character timestamp
string that was MOVEd into both ``TRAN-ORIG-TS`` and ``TRAN-PROC-TS``
(the latter is optionally overridden by the request's ``proc_date``
field if supplied).

Error message fidelity
----------------------
The COBOL error messages from the three source programs are preserved
byte-for-byte including the trailing ellipses per AAP §0.7.1:

* ``'Unable to lookup transaction...'``   (COTRN00C lines 615/649/683;
                                           list error — lowercase ``t``,
                                           singular)
* ``'Tran ID can NOT be empty...'``       (COTRN01C line ~152; blank filter)
* ``'Transaction ID NOT found...'``       (COTRN01C line ~290; NOTFND)
* ``'Unable to lookup Transaction...'``   (COTRN01C line ~295; WHEN OTHER)
* ``'Unable to lookup Card # in XREF file...'``
                                          (COTRN02C line ~633)
* ``'Unable to lookup Acct in XREF AIX file...'``
                                          (COTRN02C line ~600)
* ``'Account or Card Number must be entered...'``
                                          (COTRN02C line ~226; both blank)
* ``'Transaction added successfully.  Your Tran ID is <id>.'``
                                          (COTRN02C lines 728-732;
                                           STRING concatenation yields
                                           DOUBLE SPACE after "successfully."
                                           and a TRAILING PERIOD after the
                                           tran ID)

Observability
-------------
All transaction operations emit structured log records via the
module logger. Log records include the ``tran_id`` / ``acct_id`` /
``card_num`` field (never the full card PAN in production — here
retained verbatim as the CICS-era app stored it in the VSAM record
byte-for-byte) so that CloudWatch Logs Insights queries can
correlate transaction activity by account or card. Log levels:

* ``INFO``  — successful list retrieval (with hit count), successful
  detail lookup, successful transaction add (with generated tran_id).
* ``WARNING`` — business-rule failures: transaction not found on
  detail lookup, xref not found on add, xref mismatch on add,
  aggregate / sort max mismatch on auto-ID (should be unreachable).
* ``ERROR`` — unexpected SQLAlchemy / driver exceptions (emitted via
  ``logger.exception`` / ``logger.error(exc_info=True)`` to preserve
  the full traceback alongside structured context).

See Also
--------
* AAP §0.2.3 — Online CICS Program Classification (F-009 through F-011)
* AAP §0.5.1 — File-by-File Transformation Plan (``transaction_service.py``)
* AAP §0.7.1 — Refactoring-Specific Rules (preserve exact COBOL messages)
* ``src/shared/models/transaction.py`` — ORM model (350-byte TRAN-RECORD)
* ``src/shared/models/card_cross_reference.py`` — xref ORM model
* ``src/shared/schemas/transaction_schema.py`` — Pydantic request /
  response schemas
* ``src/shared/utils/decimal_utils.py`` — ``safe_decimal`` for
  COBOL-compatible Decimal normalisation
* ``src/shared/utils/date_utils.py`` — ``format_timestamp`` for
  26-char ``YYYY-MM-DD HH:MM:SS.ffffff`` generation
* ``src/api/services/bill_service.py`` — companion service; shares
  the same auto-ID generation and timestamp idioms
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from decimal import Decimal

from sqlalchemy import desc, func, select
from sqlalchemy.ext.asyncio import AsyncSession

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
from src.shared.utils.date_utils import format_timestamp
from src.shared.utils.decimal_utils import safe_decimal

# ============================================================================
# Module-level configuration
# ============================================================================

#: Module logger. Structured log records flow to CloudWatch Logs via
#: the ECS ``awslogs`` driver. Logs Insights queries can filter by
#: ``logger_name = 'src.api.services.transaction_service'`` to isolate
#: transaction list / detail / add activity from the other service
#: modules (bill, user, auth, account, card).
logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------------
# Transaction ID generation constants.
#
# COBOL ``WS-TRAN-ID-N`` is a numeric working-storage field that gets
# MOVEd into the ``TRAN-ID`` PIC X(16) field. To preserve the
# lexicographic-max semantics of ``SELECT MAX(tran_id)`` — which
# requires fixed-width zero-padded numeric strings — we materialise
# the next ID as ``str(n).zfill(16)``.
# ----------------------------------------------------------------------------

#: Width of the ``tran_id`` primary-key column, matching COBOL
#: ``TRAN-ID PIC X(16)`` (CVTRA05Y.cpy). Declared as a named constant
#: so the zero-pad width cannot drift silently from the column DDL
#: declared in ``db/migrations/V1__schema.sql``.
_TRAN_ID_WIDTH: int = 16

#: Starting transaction ID used when the ``transaction`` table is
#: empty. The value ``'0000000000000001'`` (16 chars) is the
#: lexicographic minimum of the valid ID space and mirrors the
#: COBOL convention of the first write after a ``STARTBR`` +
#: ``READPREV`` that hit ``DFHRESP(ENDFILE)`` on an empty file.
_INITIAL_TRAN_ID: str = "0000000000000001"

# ----------------------------------------------------------------------------
# List / detail field-width constants matching the COBOL BMS layouts.
#
# These widths drive the per-row truncation rules applied in
# :meth:`list_transactions` (COTRN00 26-char description) and
# :meth:`get_transaction_detail` (COTRN01 30-char merchant name /
# 25-char merchant city). Declared as named constants so the
# truncation widths cannot drift from the COBOL screen layout
# (COTRN00.CPY / COTRN01.CPY).
# ----------------------------------------------------------------------------

#: Width of the short-description column on the transaction list
#: screen — ``TDESCnnI PIC X(26)`` in COTRN00.CPY. The underlying
#: ``transaction.description`` column is ``String(100)`` per
#: CVTRA05Y.cpy TRAN-DESC, truncated to 26 chars for the list view.
_LIST_DESC_WIDTH: int = 26

#: Width of the ``tran_date`` field on the transaction list screen —
#: ``TDATEnnI PIC X(08)`` in COTRN00.CPY. Derived from the first 8
#: characters of ``transaction.orig_ts`` (which is a 26-char
#: ``YYYY-MM-DD HH:MM:SS.ffffff`` string — its first 10 chars include
#: the separators ``YYYY-MM-DD``). We extract the pure-numeric 8-char
#: date as ``orig_ts[0:4] + orig_ts[5:7] + orig_ts[8:10]`` =
#: ``YYYYMMDD`` without separators, matching the COBOL COTRN00
#: screen which displayed the date without dashes.
_LIST_DATE_WIDTH: int = 8

#: Width of the short-date field on the transaction detail screen —
#: ``TORIGDTI PIC X(10)`` and ``TPROCDTI PIC X(10)`` in COTRN01.CPY.
#: The underlying ``transaction.orig_ts`` and ``transaction.proc_ts``
#: columns are 26-char strings; we project the first 10 chars
#: ``YYYY-MM-DD`` for the detail view, matching the COBOL screen.
_DETAIL_DATE_WIDTH: int = 10

#: Width of the merchant-name field on the transaction detail screen
#: — ``MNAMEI PIC X(30)`` in COTRN01.CPY. The underlying
#: ``transaction.merchant_name`` column is ``String(50)`` per
#: CVTRA05Y.cpy TRAN-MERCHANT-NAME; the BMS screen truncates to 30.
_DETAIL_MERCHANT_NAME_WIDTH: int = 30

#: Width of the merchant-city field on the transaction detail screen
#: — ``MCITYI PIC X(25)`` in COTRN01.CPY. The underlying
#: ``transaction.merchant_city`` column is ``String(50)`` per
#: CVTRA05Y.cpy TRAN-MERCHANT-CITY; the BMS screen truncates to 25.
_DETAIL_MERCHANT_CITY_WIDTH: int = 25

#: Width of the description field on the transaction detail screen —
#: ``TDESCI PIC X(60)`` in COTRN01.CPY. The underlying
#: ``transaction.description`` column is ``String(100)`` per
#: CVTRA05Y.cpy TRAN-DESC; the BMS detail screen truncates to 60.
_DETAIL_DESC_WIDTH: int = 60

# ----------------------------------------------------------------------------
# Response-message constants — preserved byte-for-byte from COBOL.
#
# The COBOL programs surface user-facing strings via ``WS-MESSAGE`` on
# the BMS screen. We preserve each message byte-for-byte (including
# trailing ellipses) so API clients migrating from a terminal-emulator
# UI see identical text, per AAP §0.7.1 (preserve existing error
# messages exactly).
# ----------------------------------------------------------------------------

#: Message displayed when the paginated list query fails
#: unexpectedly. Source: ``app/cbl/COTRN00C.cbl`` lines 615, 649,
#: 683 — each site contains the literal ``'Unable to lookup
#: transaction...'`` (lowercase ``t``, **singular** "transaction").
#: Preserved byte-for-byte per AAP §0.7.1 "Preserve all existing
#: functionality exactly as-is."
_MSG_UNABLE_TO_LOOKUP_LIST: str = "Unable to lookup transaction..."

#: Message displayed when a detail lookup is issued with an empty /
#: missing ``tran_id``. Source: COTRN01C.cbl line ~152 ("Tran ID can
#: NOT be empty..." emitted on the SPACES / LOW-VALUES branch of the
#: PROCESS-ENTER-KEY paragraph).
_MSG_TRAN_ID_EMPTY: str = "Tran ID can NOT be empty..."

#: Message displayed when a detail lookup returns no row (``NOTFND``
#: in the COBOL terminology). Source: COTRN01C.cbl line ~290 on the
#: ``DFHRESP(NOTFND)`` branch of ``READ-TRANSACT-FILE``.
_MSG_TRAN_NOT_FOUND: str = "Transaction ID NOT found..."

#: Message displayed when a detail lookup fails unexpectedly.
#: Source: COTRN01C.cbl WHEN OTHER catch-all on the READ failure
#: path (line ~295).
_MSG_UNABLE_TO_LOOKUP_DETAIL: str = "Unable to lookup Transaction..."

#: Message displayed when the add-transaction xref lookup by
#: card_num returns no row. Source: COTRN02C.cbl line ~633
#: ("Unable to lookup Card # in XREF file..." on the CCXREF
#: NOTFND branch).
_MSG_CARD_NOT_IN_XREF: str = "Unable to lookup Card # in XREF file..."

#: Message displayed when the add-transaction xref lookup resolves
#: to an ``acct_id`` that does not match the request's ``acct_id``.
#: This has no single COBOL analogue (the COBOL flow would have
#: auto-populated the other field so a mismatch was impossible by
#: construction); we emit a new message to surface the modern-API
#: validation behavior clearly.
_MSG_ACCT_CARD_MISMATCH: str = "Account/Card mismatch in XREF..."

#: Message displayed when the add-transaction flow fails with an
#: unexpected database error. Loose equivalent of COTRN02C.cbl's
#: WHEN OTHER handler on WRITE-TRANSACT-FILE.
_MSG_UNABLE_TO_ADD: str = "Unable to Add Transaction..."

#: Success message format string — populated with the generated
#: ``tran_id``. Source: ``app/cbl/COTRN02C.cbl`` lines 728-732,
#: which emits via a 4-fragment ``STRING`` concatenation::
#:
#:     STRING 'Transaction added successfully. ' DELIMITED BY SIZE
#:            ' Your Tran ID is ' DELIMITED BY SIZE
#:            TRAN-ID DELIMITED BY SPACE
#:            '.' DELIMITED BY SIZE
#:       INTO WS-MESSAGE
#:
#: The two leading space-padded fragments concatenate to produce a
#: **DOUBLE SPACE** between "successfully." and "Your" in the
#: runtime output, and the final fragment ``'.'`` appends a
#: **TRAILING PERIOD** after the interpolated tran ID:
#:     ``"Transaction added successfully.  Your Tran ID is 0000000000000042."``
#: Preserved byte-for-byte per AAP §0.7.1.
_MSG_ADD_SUCCESS_FMT: str = "Transaction added successfully.  Your Tran ID is {tran_id}."


# ============================================================================
# TransactionService
# ============================================================================


class TransactionService:
    """Transaction list / detail / add service.

    This class is the modernized Python equivalent of the three
    COBOL programs COTRN00C (list, 699 lines), COTRN01C (detail,
    330 lines), and COTRN02C (add, 783 lines). It encapsulates the
    business logic previously embedded in each program's
    ``PROCESS-ENTER-KEY`` / ``PROCESS-PF7-KEY`` / ``PROCESS-PF8-KEY``
    paragraphs and their helper paragraphs (``STARTBR-TRANSACT-FILE``,
    ``READNEXT-TRANSACT-FILE``, ``READPREV-TRANSACT-FILE``,
    ``ENDBR-TRANSACT-FILE``, ``READ-TRANSACT-FILE``,
    ``WRITE-TRANSACT-FILE``, ``READ-CCXREF-FILE``, and
    ``READ-CXACAIX-FILE``).

    The class is designed as a thin stateful facade over an
    :class:`sqlalchemy.ext.asyncio.AsyncSession`: all database I/O
    flows through the injected session, which owns the transactional
    boundary. Each call to :meth:`list_transactions` and
    :meth:`get_transaction_detail` is read-only (no commit needed);
    :meth:`add_transaction` performs a single logical write
    transaction — the Transaction INSERT — and either commits or
    rolls back.

    Dependency Injection
    --------------------
    In production the session is wired up via FastAPI's dependency-
    injection machinery (see ``src/api/dependencies.py`` which yields
    an ``AsyncSession`` per request). In unit tests, the session is
    typically a real session connected to a test-containers
    PostgreSQL, or a mocked session for isolated logic tests.

    Thread safety
    -------------
    ``TransactionService`` instances are NOT thread-safe and should
    not be shared across concurrent requests — each request must
    construct its own service with its own session (FastAPI's default
    dep-inj behavior ensures this). The underlying ``AsyncSession``
    also has per-request semantics and is not designed for shared
    access.

    Example
    -------
    The typical router usage is::

        @router.get('/transactions')
        async def list_transactions(
            request: Annotated[TransactionListRequest, Query()],
            db: Annotated[AsyncSession, Depends(get_db_session)],
        ) -> TransactionListResponse:
            service = TransactionService(db)
            return await service.list_transactions(request)

    Attributes
    ----------
    db : sqlalchemy.ext.asyncio.AsyncSession
        The async SQLAlchemy session that owns the transactional
        context for this service. All database reads and writes go
        through this session; :meth:`add_transaction` calls
        ``session.commit()`` on success and ``session.rollback()``
        on any uncaught exception.

    See Also
    --------
    :class:`src.shared.schemas.transaction_schema.TransactionListRequest` :
        The input schema for the paginated list endpoint.
    :class:`src.shared.schemas.transaction_schema.TransactionListResponse` :
        The output schema for the paginated list endpoint.
    :class:`src.shared.schemas.transaction_schema.TransactionDetailResponse` :
        The output schema for the keyed detail endpoint.
    :class:`src.shared.schemas.transaction_schema.TransactionAddRequest` :
        The input schema for the add endpoint.
    :class:`src.shared.schemas.transaction_schema.TransactionAddResponse` :
        The output schema for the add endpoint.
    """

    def __init__(self, db: AsyncSession) -> None:
        """Construct a new TransactionService bound to an async session.

        Parameters
        ----------
        db : sqlalchemy.ext.asyncio.AsyncSession
            The async SQLAlchemy session that owns the transactional
            context for this service. Must be scoped to the current
            HTTP request (FastAPI's dependency-injection system handles
            this automatically when the router uses
            ``Depends(get_db_session)``).
        """
        self.db: AsyncSession = db

    # ------------------------------------------------------------------
    # F-009 — Transaction List
    # ------------------------------------------------------------------
    async def list_transactions(self, request: TransactionListRequest) -> TransactionListResponse:
        """Return a paginated list of transactions (10 rows per page).

        Converted from ``app/cbl/COTRN00C.cbl`` (699 lines). The COBOL
        program implements an interactive browse-mode paginated list
        via CICS ``STARTBR``/``READNEXT``/``READPREV``/``ENDBR`` on
        ``TRANSACT``. Here we translate the browse-mode cursor pattern
        into a server-side paginated SQL query using
        ``LIMIT``/``OFFSET``.

        COBOL flow (COTRN00C PROCEDURE DIVISION):

        1. ``PROCESS-ENTER-KEY`` (line 146): If the user typed a
           ``TRNIDINI`` value, MOVE it to ``TRAN-ID`` and set the
           browse anchor — effectively "jump to page starting at
           this tran_id". Since SQL pagination is page-number-based,
           we use ``LIKE 'tran_id%'`` for the "starts with" filter —
           this is functionally richer than the COBOL behavior but
           preserves the intent (find transactions whose IDs match
           the prefix) and matches the ``user_service.list_users``
           pattern established in the sibling service.

        2. ``PROCESS-PF8-KEY`` / ``PROCESS-PAGE-FORWARD`` (line 279):
           ``STARTBR`` at the current cursor position, read up to
           10 rows via ``READNEXT``, then ``ENDBR``. In SQL:
           ``SELECT ... ORDER BY tran_id ASC LIMIT 10 OFFSET (page - 1) * 10``.

        3. ``PROCESS-PF7-KEY`` / ``PROCESS-PAGE-BACKWARD`` (line 333):
           Symmetric — ``STARTBR`` and read up to 10 rows via
           ``READPREV`` then reverse the list. Since our pagination
           is idempotent on ``page``, the router decrements ``page``
           and re-issues this same query — no distinct code path is
           needed.

        4. An empty list is a valid response (not an error) — the
           COBOL screen simply shows empty rows with
           "You are at the top of the page..." or similar on
           position-past-end.

        Cross-verification with sibling service
        ---------------------------------------
        This implementation mirrors
        :meth:`src.api.services.user_service.UserService.list_users`
        method structure including the LIKE-pattern escape semantics,
        the simultaneous count-total query, and the structured log
        emission — ensuring consistent list-endpoint behavior across
        the codebase.

        Parameters
        ----------
        request : TransactionListRequest
            The paginated list request. ``request.tran_id`` is an
            optional prefix filter (maps to COBOL ``TRNIDINI``);
            ``request.page`` is the 1-indexed page number (maps to
            COBOL ``PAGENUMI``); ``request.page_size`` defaults to
            10 matching the COBOL screen's 10 repeated OCCURS rows.

        Returns
        -------
        TransactionListResponse
            The paginated response with up to ``page_size`` items.
            On database error, the ``message`` field carries
            :data:`_MSG_UNABLE_TO_LOOKUP_LIST` and the ``transactions``
            list is empty; ``total_count`` is 0.

        Notes
        -----
        All monetary ``amount`` fields on the returned list items are
        :class:`~decimal.Decimal` (never :class:`float`), quantized to
        two decimal places via :func:`~src.shared.utils.decimal_utils.safe_decimal`
        to preserve COBOL ``PIC S9(09)V99`` semantics per AAP §0.7.2.
        """
        # Structured logging context — included on every INFO/WARN
        # record emitted by this method for CloudWatch Logs Insights
        # correlation. The ``page_size`` is typed generously here
        # because the logger's 'extra' dict mandates Any-value support.
        log_context: dict[str, object] = {
            "operation": "list_transactions",
            "tran_id_prefix": request.tran_id,
            "page": request.page,
            "page_size": request.page_size,
        }
        logger.info("Transaction list requested", extra=log_context)

        # --------------------------------------------------------------
        # Step 1: Build the base SELECT and COUNT statements.
        #
        # Both statements share the same optional ``LIKE`` filter on
        # ``tran_id``; building them in parallel ensures the count
        # total reflects the filtered set, not the entire table.
        # --------------------------------------------------------------
        stmt = select(Transaction).order_by(Transaction.tran_id)
        count_stmt = select(func.count()).select_from(Transaction)

        # --------------------------------------------------------------
        # Step 2: Apply the optional ``tran_id`` prefix filter.
        #
        # COBOL behavior (COTRN00C line 210): If TRNIDINI is non-
        # blank AND numeric, MOVE it into TRAN-ID and STARTBR positions
        # at that key — this is effectively a "jump to" operation.
        #
        # Python equivalent: ``tran_id LIKE <prefix>%`` (prefix match,
        # richer than the COBOL jump-to since it returns all matches
        # not just those at/after the position). The LIKE-pattern
        # metacharacters ``%`` / ``_`` / ``\`` must be escaped so a
        # literal ``%`` in a user-supplied prefix does not act as a
        # wildcard. We use a custom escape char because the SQL
        # default escape char varies by dialect.
        #
        # The ``TransactionListRequest`` schema normalizes blank /
        # whitespace-only filters to ``None`` via its validator, so
        # the ``if request.tran_id`` truthiness check below is
        # sufficient to detect the "no filter" case.
        # --------------------------------------------------------------
        if request.tran_id:
            escape_char: str = "\\"
            # Double the escape char first to avoid double-escaping
            # the subsequent ``%`` and ``_`` literals.
            escaped_prefix: str = (
                request.tran_id.replace(escape_char, escape_char + escape_char)
                .replace("%", escape_char + "%")
                .replace("_", escape_char + "_")
            )
            like_pattern: str = escaped_prefix + "%"
            stmt = stmt.where(Transaction.tran_id.like(like_pattern, escape=escape_char))
            count_stmt = count_stmt.where(Transaction.tran_id.like(like_pattern, escape=escape_char))

        # --------------------------------------------------------------
        # Step 3: Apply pagination (LIMIT + OFFSET).
        #
        # Page 1 → OFFSET 0; Page 2 → OFFSET page_size; etc.
        # COBOL equivalent: STARTBR positions the cursor, then
        # READNEXT reads up to ``page_size`` rows (the COBOL limit
        # was hardcoded to 10 — COTRN00.CPY OCCURS 10 TIMES).
        # --------------------------------------------------------------
        offset_rows: int = (request.page - 1) * request.page_size
        stmt = stmt.offset(offset_rows).limit(request.page_size)

        # --------------------------------------------------------------
        # Step 4: Execute both queries.
        #
        # We wrap the execute calls in a try/except to emit a COBOL-
        # compatible error message on database failure. Unlike
        # :meth:`add_transaction`, a list failure does not need to
        # roll back any transaction (read-only).
        # --------------------------------------------------------------
        try:
            page_result = await self.db.execute(stmt)
            count_result = await self.db.execute(count_stmt)
        except Exception as exc:  # noqa: BLE001  # blanket catch per COBOL WHEN OTHER
            logger.error(
                "Transaction list lookup failed with unexpected error",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            return TransactionListResponse(
                transactions=[],
                page=request.page,
                total_count=0,
                message=_MSG_UNABLE_TO_LOOKUP_LIST,
            )

        transactions_rows: list[Transaction] = list(page_result.scalars().all())
        total_count: int = count_result.scalar_one() or 0

        # --------------------------------------------------------------
        # Step 5: Map each ORM row to a TransactionListItem.
        #
        # The list-screen projection is narrower than the detail
        # projection — COTRN00 shows only the 4 fields (tran_id,
        # tran_date, description, amount). We truncate ``description``
        # to 26 chars per COTRN00.CPY TDESCnnI PIC X(26) and extract
        # the date from the first 10 chars of ``orig_ts`` removing
        # the two ``-`` separators to get the 8-char ``YYYYMMDD``
        # format shown on the COBOL screen.
        # --------------------------------------------------------------
        list_items: list[TransactionListItem] = [
            TransactionListItem(
                tran_id=row.tran_id,
                tran_date=_derive_list_date(row.orig_ts),
                description=(row.description or "")[:_LIST_DESC_WIDTH],
                amount=safe_decimal(row.amount),
            )
            for row in transactions_rows
        ]

        logger.info(
            "Transaction list returned",
            extra={
                **log_context,
                "returned_count": len(list_items),
                "total_count": total_count,
            },
        )

        return TransactionListResponse(
            transactions=list_items,
            page=request.page,
            total_count=total_count,
            message=None,
        )

    # ------------------------------------------------------------------
    # F-010 — Transaction Detail
    # ------------------------------------------------------------------
    async def get_transaction_detail(self, tran_id: str) -> TransactionDetailResponse:
        """Return the 13-field detail of a single transaction.

        Converted from ``app/cbl/COTRN01C.cbl`` (330 lines).
        The COBOL program reads a single transaction record by its
        16-character primary key and displays all 13 fields on the
        COTRN01 BMS screen.

        COBOL flow (COTRN01C PROCEDURE DIVISION):

        1. ``PROCESS-ENTER-KEY`` (line 144): Validate ``TRNIDINI`` is
           non-blank (line 147-152). Empty → error "Tran ID can NOT
           be empty..." and return.

        2. ``MOVE TRNIDINI TO TRAN-ID`` (line 172). In Python: the
           ``tran_id`` parameter is the normalized form of the COBOL
           TRNIDINI field.

        3. ``PERFORM READ-TRANSACT-FILE`` (line 174 → paragraph at
           line 263). CICS ``READ FILE('TRANSACT') INTO(TRAN-RECORD)
           RIDFLD(TRAN-ID)``.

        4. ``WHEN DFHRESP(NOTFND)`` (line 283) → error "Transaction
           ID NOT found...". In Python:
           :attr:`_MSG_TRAN_NOT_FOUND`.

        5. ``WHEN OTHER`` (line 294) → error "Unable to lookup
           Transaction...". In Python: :attr:`_MSG_UNABLE_TO_LOOKUP_DETAIL`.

        6. On success (lines 177-190), MOVE each of the 13 TRAN-RECORD
           fields to the corresponding COTRN1AI screen field. We map
           the ORM row to :class:`TransactionDetailResponse` with
           equivalent field names.

        Parameters
        ----------
        tran_id : str
            The 16-character transaction ID to look up. Blank /
            whitespace-only values are treated as the
            "empty key" error case per COBOL line 147.

        Returns
        -------
        TransactionDetailResponse
            The detail response. On success, ``message`` is ``None``
            and all 14 data fields (``tran_id_input``, ``tran_id``,
            ``card_num``, ``tran_type_cd``, ``tran_cat_cd``,
            ``tran_source``, ``description``, ``amount``,
            ``orig_date``, ``proc_date``, ``merchant_id``,
            ``merchant_name``, ``merchant_city``, ``merchant_zip``)
            are populated. On failure, ``message`` carries the
            COBOL-compatible error text and the data fields contain
            the request echo (``tran_id_input``) plus empty defaults.

        Notes
        -----
        The ``amount`` field is :class:`~decimal.Decimal`, quantized
        to two decimal places per COBOL ``PIC S9(09)V99`` semantics.
        The ``description`` field is truncated to 60 characters per
        the COTRN01.CPY ``TDESCI PIC X(60)`` layout, even though the
        underlying ``transaction.description`` column holds up to 100
        chars (CVTRA05Y.cpy).
        """
        # Preserve the original input for echoing in the response's
        # ``tran_id_input`` field — the COBOL screen re-populated the
        # TRNIDINI field on error cases, and our modern API does the
        # same for symmetric client UX.
        original_input: str = tran_id or ""

        log_context: dict[str, object] = {
            "operation": "get_transaction_detail",
            "tran_id_input": original_input,
        }

        # --------------------------------------------------------------
        # Step 1: Normalize and validate the input.
        #
        # COBOL COTRN01C line 147: IF TRNIDINI = SPACES OR LOW-VALUES
        # → error and return. In Python we normalize None /
        # whitespace-only inputs to the empty-string error case.
        # --------------------------------------------------------------
        normalized_tran_id: str = (tran_id or "").strip()
        if not normalized_tran_id:
            logger.warning(
                "Transaction detail request rejected — empty tran_id",
                extra=log_context,
            )
            return _empty_detail_response(
                tran_id_input=original_input,
                message=_MSG_TRAN_ID_EMPTY,
            )

        # --------------------------------------------------------------
        # Step 2: Execute the keyed READ on the Transaction table.
        #
        # Maps to COBOL COTRN01C line 269: EXEC CICS READ FILE
        # ('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID). The
        # ``scalar_one_or_none()`` call returns None for NOTFND.
        # --------------------------------------------------------------
        stmt = select(Transaction).where(Transaction.tran_id == normalized_tran_id)
        try:
            result = await self.db.execute(stmt)
        except Exception as exc:  # noqa: BLE001  # blanket catch per COBOL WHEN OTHER
            logger.error(
                "Transaction detail lookup failed with unexpected error",
                extra={
                    **log_context,
                    "tran_id": normalized_tran_id,
                    "error_type": type(exc).__name__,
                },
                exc_info=True,
            )
            return _empty_detail_response(
                tran_id_input=original_input,
                message=_MSG_UNABLE_TO_LOOKUP_DETAIL,
            )

        transaction: Transaction | None = result.scalar_one_or_none()

        # --------------------------------------------------------------
        # Step 3: Handle NOTFND (line 283).
        # --------------------------------------------------------------
        if transaction is None:
            logger.warning(
                "Transaction detail lookup — tran_id not found",
                extra={**log_context, "tran_id": normalized_tran_id},
            )
            return _empty_detail_response(
                tran_id_input=original_input,
                message=_MSG_TRAN_NOT_FOUND,
            )

        # --------------------------------------------------------------
        # Step 4: Map the ORM row → TransactionDetailResponse.
        #
        # Per-field mapping (COBOL COTRN01C lines 177-190):
        #   TRAN-ID          → tran_id
        #   TRAN-CARD-NUM    → card_num
        #   TRAN-TYPE-CD     → tran_type_cd
        #   TRAN-CAT-CD      → tran_cat_cd
        #   TRAN-SOURCE      → tran_source
        #   TRAN-AMT         → amount (Decimal)
        #   TRAN-DESC        → description (truncated to 60 chars)
        #   TRAN-ORIG-TS[0:10] → orig_date (YYYY-MM-DD)
        #   TRAN-PROC-TS[0:10] → proc_date (YYYY-MM-DD)
        #   TRAN-MERCHANT-ID → merchant_id
        #   TRAN-MERCHANT-NAME → merchant_name (truncated to 30)
        #   TRAN-MERCHANT-CITY → merchant_city (truncated to 25)
        #   TRAN-MERCHANT-ZIP  → merchant_zip
        # --------------------------------------------------------------
        response = TransactionDetailResponse(
            tran_id_input=original_input,
            tran_id=transaction.tran_id,
            card_num=transaction.card_num,
            tran_type_cd=transaction.type_cd,
            tran_cat_cd=transaction.cat_cd,
            tran_source=transaction.source,
            description=(transaction.description or "")[:_DETAIL_DESC_WIDTH],
            amount=safe_decimal(transaction.amount),
            orig_date=_derive_detail_date(transaction.orig_ts),
            proc_date=_derive_detail_date(transaction.proc_ts),
            merchant_id=transaction.merchant_id,
            merchant_name=(transaction.merchant_name or "")[:_DETAIL_MERCHANT_NAME_WIDTH],
            merchant_city=(transaction.merchant_city or "")[:_DETAIL_MERCHANT_CITY_WIDTH],
            merchant_zip=transaction.merchant_zip,
            message=None,
        )

        logger.info(
            "Transaction detail returned",
            extra={**log_context, "tran_id": transaction.tran_id},
        )
        return response

    # ------------------------------------------------------------------
    # F-011 — Transaction Add
    # ------------------------------------------------------------------
    async def add_transaction(self, request: TransactionAddRequest) -> TransactionAddResponse:
        """Create a new transaction with auto-generated ID.

        Converted from ``app/cbl/COTRN02C.cbl`` (783 lines). The
        COBOL program accepts either ``ACTIDINI`` (account ID) OR
        ``CARDNINI`` (card number), looks up the missing one via
        ``CCXREF`` / ``CXACAIX``, validates all user-entered fields,
        generates the next ``TRAN-ID`` by positioning the browse
        cursor at ``HIGH-VALUES`` and ``READPREV``-ing to the lexical
        maximum, increments by 1, and finally ``WRITE``s the record
        to the ``TRANSACT`` VSAM file.

        COBOL flow (COTRN02C PROCEDURE DIVISION):

        1. ``VALIDATE-INPUT-KEY-FIELDS`` (line 193-230): Resolve the
           missing key via xref lookup. In the modern API the request
           schema REQUIRES both ``acct_id`` and ``card_num``, so this
           step is a **validation** — we confirm the ``card_num``
           exists in ``CardCrossReference`` AND the resolved
           ``acct_id`` matches the request's ``acct_id``.

        2. ``VALIDATE-INPUT-DATA-FIELDS`` (line 270-440): Validate
           type_cd, cat_cd, source, amount, dates, merchant fields.
           In the modern API the Pydantic schema performs these
           validations automatically before the service is invoked
           (see :class:`TransactionAddRequest`), so no explicit
           service-layer validation is needed here — invalid requests
           never reach this method.

        3. ``ADD-TRANSACTION`` (line 442-466):

           a. ``MOVE HIGH-VALUES TO TRAN-ID`` + ``STARTBR`` +
              ``READPREV`` + ``ENDBR`` → get the lexically-greatest
              existing ``tran_id``. In Python: ``SELECT MAX(tran_id)``
              + cross-verification via ``SELECT tran_id ORDER BY
              tran_id DESC LIMIT 1``.

           b. ``ADD 1 TO WS-TRAN-ID-N`` → increment the numeric
              working-storage value.

           c. ``INITIALIZE TRAN-RECORD`` + MOVE each input field
              into the corresponding TRAN-RECORD field →
              construct the ORM instance.

           d. ``PERFORM WRITE-TRANSACT-FILE`` → ``session.add()`` +
              ``flush()`` + ``commit()``.

        Parameters
        ----------
        request : TransactionAddRequest
            The validated request. All fields have passed Pydantic
            validation (numeric/length/range checks) before reaching
            this method, so no redundant validation is performed here.

        Returns
        -------
        TransactionAddResponse
            On success: ``confirm='Y'``, ``tran_id`` is the newly
            generated ID, ``message`` is the success string. On
            failure: ``confirm='N'``, ``tran_id=''``, ``message`` is
            the COBOL-compatible error text.

        Raises
        ------
        Exception
            Any unhandled SQLAlchemy / driver exception is
            re-raised after rolling back the session. Callers
            should translate re-raised exceptions to HTTP 500 via
            the global FastAPI exception middleware (``src/api/
            middleware/error_handler.py``). Business-logic errors
            (xref mismatch, empty table, ID overflow) are returned
            via the ``message`` field instead of raising.
        """
        log_context: dict[str, object] = {
            "operation": "add_transaction",
            "acct_id": request.acct_id,
            "card_num": request.card_num,
            "tran_type_cd": request.tran_type_cd,
            "tran_cat_cd": request.tran_cat_cd,
            "amount": str(request.amount),
        }
        logger.info("Transaction add requested", extra=log_context)

        try:
            # ----------------------------------------------------------
            # Step 1: Cross-reference validation.
            #
            # COBOL COTRN02C line 193-230: The COBOL program accepts
            # EITHER acct_id OR card_num and resolves the missing
            # one via CCXREF (by card_num) or CXACAIX (by acct_id).
            # Our modern API's Pydantic schema REQUIRES BOTH, so we
            # implement this as a validation: the card_num MUST exist
            # in ``card_cross_references`` AND its ``acct_id`` MUST
            # equal ``request.acct_id``.
            #
            # Maps to COBOL COTRN02C READ-CCXREF-FILE paragraph
            # (lines 607-635): ``EXEC CICS READ FILE('CCXREF')
            # INTO(CARD-XREF-RECORD) RIDFLD(XREF-CARD-NUM)``.
            # ----------------------------------------------------------
            xref_stmt = select(CardCrossReference).where(CardCrossReference.card_num == request.card_num)
            xref_result = await self.db.execute(xref_stmt)
            xref: CardCrossReference | None = xref_result.scalar_one_or_none()

            if xref is None:
                # COBOL COTRN02C line 633 "Unable to lookup Card # in
                # XREF file..." on DFHRESP(NOTFND).
                logger.warning(
                    "Transaction add rejected — card_num not in xref",
                    extra=log_context,
                )
                await self.db.rollback()
                return TransactionAddResponse(
                    tran_id="",
                    acct_id=request.acct_id,
                    card_num=request.card_num,
                    amount=safe_decimal(request.amount),
                    confirm="N",
                    message=_MSG_CARD_NOT_IN_XREF,
                )

            if xref.acct_id != request.acct_id:
                # No direct COBOL analogue (the COBOL flow auto-
                # populated the other field), but necessary for our
                # dual-field API contract to prevent silent xref
                # corruption.
                logger.warning(
                    "Transaction add rejected — acct/card mismatch in xref",
                    extra={
                        **log_context,
                        "xref_acct_id": xref.acct_id,
                        "request_acct_id": request.acct_id,
                    },
                )
                await self.db.rollback()
                return TransactionAddResponse(
                    tran_id="",
                    acct_id=request.acct_id,
                    card_num=request.card_num,
                    amount=safe_decimal(request.amount),
                    confirm="N",
                    message=_MSG_ACCT_CARD_MISMATCH,
                )

            # ----------------------------------------------------------
            # Step 2: Auto-ID generation.
            #
            # Maps to COBOL COTRN02C ADD-TRANSACTION (lines 442-466):
            #
            #   MOVE HIGH-VALUES TO TRAN-ID
            #   PERFORM STARTBR-TRANSACT-FILE
            #   PERFORM READPREV-TRANSACT-FILE
            #   PERFORM ENDBR-TRANSACT-FILE
            #   MOVE TRAN-ID TO WS-TRAN-ID-N
            #   ADD 1 TO WS-TRAN-ID-N
            #
            # In PostgreSQL: ``SELECT MAX(tran_id)`` + cross-verify via
            # ``SELECT tran_id ORDER BY tran_id DESC LIMIT 1``. Both
            # queries should yield the same result inside a single
            # snapshot-isolated read; any mismatch is a red flag
            # (we log WARNING and take the numerical max of both).
            #
            # We perform the dual-query cross-verification — matching
            # the bill_service.py pattern — because:
            #   1. PostgreSQL has been known to produce subtly
            #      different plans for MAX() vs ORDER BY ... LIMIT 1
            #      (e.g. index-only scan vs sort), and although they
            #      are logically equivalent, the cross-verification
            #      catches any rare plan divergence.
            #   2. The COBOL ``READPREV`` behavior is closer to the
            #      ORDER BY + LIMIT 1 semantics than to a pure MAX()
            #      aggregate, so running both gives us defense in depth.
            # ----------------------------------------------------------
            max_agg_stmt = select(func.max(Transaction.tran_id))
            max_agg_result = await self.db.execute(max_agg_stmt)
            max_tran_id_agg: str | None = max_agg_result.scalar()

            max_sort_stmt = select(Transaction.tran_id).order_by(desc(Transaction.tran_id)).limit(1)
            max_sort_result = await self.db.execute(max_sort_stmt)
            max_tran_id_sort: str | None = max_sort_result.scalar()

            # Normalize empty-string / null to None so the `if not`
            # check below is unambiguous.
            normalised_agg: str | None = max_tran_id_agg or None
            normalised_sort: str | None = max_tran_id_sort or None

            if normalised_agg != normalised_sort:
                logger.warning(
                    "MAX/ORDER-BY aggregate mismatch during tran_id generation; taking numerical max of both",
                    extra={
                        **log_context,
                        "max_tran_id_agg": normalised_agg,
                        "max_tran_id_sort": normalised_sort,
                    },
                )

            # Take the max of both candidates (filtered for None) —
            # identical to the bill_service.py pattern.
            candidates: list[str] = [v for v in (normalised_agg, normalised_sort) if v is not None]
            last_tran_id: str | None = max(candidates) if candidates else None

            if last_tran_id is None:
                # Empty-table case. Matches COBOL DFHRESP(ENDFILE) on
                # READPREV — the file was empty. Seed the first ID.
                new_tran_id: str = _INITIAL_TRAN_ID
            else:
                # COBOL: MOVE TRAN-ID TO WS-TRAN-ID-N / ADD 1 TO
                # WS-TRAN-ID-N / MOVE WS-TRAN-ID-N TO TRAN-ID.
                try:
                    next_id_num: int = int(last_tran_id) + 1
                except (ValueError, TypeError):
                    # Database contains a tran_id that isn't a valid
                    # integer — data corruption. This should be
                    # unreachable given the V1__schema.sql column
                    # constraint, but we handle it defensively to
                    # avoid silently generating a wrong ID.
                    logger.error(
                        "Cannot parse existing tran_id as integer; refusing to auto-generate successor",
                        extra={**log_context, "last_tran_id": last_tran_id},
                    )
                    await self.db.rollback()
                    return TransactionAddResponse(
                        tran_id="",
                        acct_id=request.acct_id,
                        card_num=request.card_num,
                        amount=safe_decimal(request.amount),
                        confirm="N",
                        message=_MSG_UNABLE_TO_ADD,
                    )
                new_tran_id = str(next_id_num).zfill(_TRAN_ID_WIDTH)

            # ----------------------------------------------------------
            # Step 3: Timestamp generation.
            #
            # Maps to COBOL COTRN02C ADD-TRANSACTION (line 460-461):
            #
            #   MOVE TORIGDTI OF COTRN2AI TO TRAN-ORIG-TS
            #   MOVE TPROCDTI OF COTRN2AI TO TRAN-PROC-TS
            #
            # The COBOL actually MOVEs the raw 10-char screen date
            # (e.g. ``"2023-12-15"``) into the 26-char TRAN-ORIG-TS
            # / TRAN-PROC-TS fields, padded with spaces on the right.
            #
            # For the modern API we preserve this behavior:
            #   - Use the request's orig_date / proc_date values as
            #     the leading 10 chars.
            #   - Append a full 26-char timestamp derived from the
            #     request's date (00:00:00.000000) so the resulting
            #     string remains well-formed.
            #
            # When proc_date is omitted (None), the current UTC
            # timestamp is used for proc_ts — this matches CICS's
            # ASKTIME+FORMATTIME behavior for the "processing time is
            # now" implied semantics of COTRN02C.
            # ----------------------------------------------------------
            now_utc: datetime = datetime.now(UTC)
            now_ts: str = format_timestamp(now_utc)

            # orig_ts is the ``orig_date`` from the request followed by
            # a fixed 00:00:00.000000 time suffix — matching the
            # COBOL behavior of storing the raw screen date into the
            # 26-byte field. We use the current UTC time only for
            # the proc_ts fallback case.
            orig_ts: str = _compose_ts_from_date(request.orig_date, now_utc)
            if request.proc_date:
                proc_ts: str = _compose_ts_from_date(request.proc_date, now_utc)
            else:
                proc_ts = now_ts

            # ----------------------------------------------------------
            # Step 4: Amount normalization.
            #
            # The request schema already validates amount > 0 and the
            # schema's ``Decimal`` type handles precision, but we route
            # through ``safe_decimal`` to guarantee the quantize-to-
            # 2-decimal-places invariant matching COBOL PIC S9(09)V99.
            # ----------------------------------------------------------
            transaction_amount: Decimal = safe_decimal(request.amount)

            # ----------------------------------------------------------
            # Step 5: Construct the ORM instance.
            #
            # Maps to COBOL COTRN02C ADD-TRANSACTION (lines 450-466):
            #
            #   INITIALIZE TRAN-RECORD.
            #   MOVE WS-TRAN-ID-N          TO TRAN-ID
            #   MOVE TTYPCDI               TO TRAN-TYPE-CD
            #   MOVE TCATCDI               TO TRAN-CAT-CD
            #   MOVE TRNSRCI               TO TRAN-SOURCE
            #   MOVE TDESCI                TO TRAN-DESC
            #   MOVE WS-TRAN-AMT-N         TO TRAN-AMT
            #   MOVE CARDNINI              TO TRAN-CARD-NUM
            #   MOVE MIDI                  TO TRAN-MERCHANT-ID
            #   MOVE MNAMEI                TO TRAN-MERCHANT-NAME
            #   MOVE MCITYI                TO TRAN-MERCHANT-CITY
            #   MOVE MZIPI                 TO TRAN-MERCHANT-ZIP
            #   MOVE TORIGDTI              TO TRAN-ORIG-TS
            #   MOVE TPROCDTI              TO TRAN-PROC-TS
            #
            # The optional merchant_* / description fields default to
            # empty strings when omitted from the request — matching
            # the COBOL ``INITIALIZE TRAN-RECORD`` statement which
            # zeroed/spaced all fields before the individual MOVEs.
            # ----------------------------------------------------------
            new_transaction = Transaction(
                tran_id=new_tran_id,
                type_cd=request.tran_type_cd,
                cat_cd=request.tran_cat_cd,
                source=request.tran_source,
                description=request.description or "",
                amount=transaction_amount,
                merchant_id=request.merchant_id or "",
                merchant_name=request.merchant_name or "",
                merchant_city=request.merchant_city or "",
                merchant_zip=request.merchant_zip or "",
                card_num=request.card_num,
                orig_ts=orig_ts,
                proc_ts=proc_ts,
            )

            # ----------------------------------------------------------
            # Step 6: INSERT + commit.
            #
            # Maps to COBOL COTRN02C WRITE-TRANSACT-FILE paragraph:
            #
            #   EXEC CICS WRITE FILE('TRANSACT')
            #                   FROM(TRAN-RECORD)
            #                   RIDFLD(TRAN-ID)
            #                   RESP(WS-RESP-CD)
            #                   END-EXEC.
            #
            # SQLAlchemy async pattern: add the instance to the
            # session (queues the INSERT), flush to send it to the
            # database (catches unique-constraint violations early),
            # commit to make it durable. Any exception falls through
            # to the outer except clause for rollback.
            # ----------------------------------------------------------
            self.db.add(new_transaction)
            await self.db.flush()
            await self.db.commit()

            logger.info(
                "Transaction added successfully",
                extra={**log_context, "generated_tran_id": new_tran_id},
            )

            return TransactionAddResponse(
                tran_id=new_tran_id,
                acct_id=request.acct_id,
                card_num=request.card_num,
                amount=transaction_amount,
                confirm="Y",
                message=_MSG_ADD_SUCCESS_FMT.format(tran_id=new_tran_id),
            )

        except Exception as exc:
            # COBOL COTRN02C WHEN OTHER on WRITE-TRANSACT-FILE.
            # We log with exc_info to capture the full traceback
            # and then roll back to leave the session clean before
            # re-raising — this matches the bill_service.py pattern.
            logger.error(
                "Transaction add failed with unexpected error; rolling back",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            try:
                await self.db.rollback()
            except Exception:  # noqa: BLE001
                # Rollback failure itself is rare but possible (e.g.
                # a broken connection); log it and suppress so we
                # can re-raise the original exception.
                logger.exception(
                    "Rollback failed during transaction-add error recovery",
                    extra=log_context,
                )
            raise


# ============================================================================
# Private helper functions
# ============================================================================


def _derive_list_date(orig_ts: str | None) -> str:
    """Extract the 8-char ``YYYYMMDD`` date from a 26-char timestamp.

    Maps to COBOL COTRN00C's derivation of ``TDATEnnI`` (PIC X(08))
    from the ``TRAN-ORIG-TS`` (PIC X(26)). The COBOL pattern
    effectively took the first 10 chars ``YYYY-MM-DD`` and stripped
    the two ``-`` separators to produce ``YYYYMMDD``.

    Parameters
    ----------
    orig_ts : str or None
        The 26-char ``YYYY-MM-DD HH:MM:SS.ffffff`` timestamp from the
        ORM's ``orig_ts`` field. ``None`` or shorter strings are
        handled defensively — the result is truncated to
        ``_LIST_DATE_WIDTH`` (8) chars.

    Returns
    -------
    str
        The 8-char ``YYYYMMDD`` date, or a shorter string if the
        input is malformed or missing.
    """
    if not orig_ts:
        return ""
    # Extract YYYY-MM-DD from the leading 10 chars and strip the
    # two separators to get the 8-char compact form.
    leading: str = orig_ts[:_DETAIL_DATE_WIDTH]  # first 10 chars
    compact: str = leading.replace("-", "")
    return compact[:_LIST_DATE_WIDTH]


def _derive_detail_date(ts: str | None) -> str:
    """Extract the 10-char ``YYYY-MM-DD`` date from a 26-char timestamp.

    Maps to COBOL COTRN01C's derivation of ``TORIGDTI`` / ``TPROCDTI``
    (PIC X(10)) from ``TRAN-ORIG-TS`` / ``TRAN-PROC-TS`` (PIC X(26)).
    The COBOL pattern simply projected the first 10 characters.

    Parameters
    ----------
    ts : str or None
        The 26-char ``YYYY-MM-DD HH:MM:SS.ffffff`` timestamp from the
        ORM's ``orig_ts`` or ``proc_ts`` field. ``None`` or shorter
        strings are handled defensively.

    Returns
    -------
    str
        The 10-char ``YYYY-MM-DD`` date, or a shorter string if the
        input is malformed or missing.
    """
    if not ts:
        return ""
    return ts[:_DETAIL_DATE_WIDTH]


def _compose_ts_from_date(date_str: str, reference_time: datetime) -> str:
    """Compose a 26-char timestamp from a 10-char date.

    The COBOL convention (COTRN02C ADD-TRANSACTION lines 460-461)
    was to MOVE a 10-char date (e.g. ``"2023-12-15"``) into the
    26-char ``TRAN-ORIG-TS`` / ``TRAN-PROC-TS`` field, right-padded
    with spaces. That yields a non-parseable field if any downstream
    consumer expects a full timestamp.

    In the modern Python implementation we improve on that behavior
    by composing a full 26-char timestamp using the supplied date
    as the leading ``YYYY-MM-DD`` portion and the reference_time's
    HH:MM:SS.ffffff as the trailing portion. This preserves the
    "today's date" semantics of the COBOL screen while providing
    parseable 26-char output for downstream batch jobs.

    If the date_str is empty / None, falls back to the reference
    time's full 26-char timestamp.

    Parameters
    ----------
    date_str : str
        A 10-char ``YYYY-MM-DD`` date (validated upstream by the
        Pydantic schema). May be empty — in which case the full
        reference_time is used.
    reference_time : datetime.datetime
        The reference time used to fill in the HH:MM:SS.ffffff
        portion of the timestamp. Should be a timezone-aware UTC
        ``datetime``.

    Returns
    -------
    str
        A 26-char ``YYYY-MM-DD HH:MM:SS.ffffff`` timestamp.
    """
    full_ts: str = format_timestamp(reference_time)
    if not date_str:
        return full_ts
    # Normalise date_str to exactly 10 chars (YYYY-MM-DD). If shorter,
    # pad with spaces; if longer, truncate. The Pydantic schema
    # already enforces max_length=10 via the TransactionAddRequest,
    # so this is defensive.
    padded_date: str = date_str.ljust(_DETAIL_DATE_WIDTH)[:_DETAIL_DATE_WIDTH]
    # Replace the leading 10 chars of the reference timestamp with
    # the padded date. The format_timestamp() output is always 26
    # chars so indexing is safe.
    return padded_date + full_ts[_DETAIL_DATE_WIDTH:]


def _empty_detail_response(tran_id_input: str, message: str) -> TransactionDetailResponse:
    """Construct an empty-body :class:`TransactionDetailResponse`.

    Used on the error branches of :meth:`TransactionService.get_transaction_detail`:
    the empty-input rejection, the NOTFND branch, and the WHEN OTHER
    branch. All 13 data fields are set to empty strings / zero
    Decimal so the response is a valid Pydantic model (all required
    fields are non-None) while ``tran_id_input`` echoes the original
    user input and ``message`` carries the COBOL-equivalent error
    text.

    Parameters
    ----------
    tran_id_input : str
        The original user-supplied tran_id to echo back in the
        ``tran_id_input`` field (matching the COBOL screen's
        re-population of TRNIDINI on error).
    message : str
        The COBOL-compatible error message (one of the module
        constants ``_MSG_TRAN_ID_EMPTY``, ``_MSG_TRAN_NOT_FOUND``,
        or ``_MSG_UNABLE_TO_LOOKUP_DETAIL``).

    Returns
    -------
    TransactionDetailResponse
        A fully-valid Pydantic response with empty data fields and
        the supplied error message.
    """
    return TransactionDetailResponse(
        tran_id_input=tran_id_input,
        tran_id="",
        card_num="",
        tran_type_cd="",
        tran_cat_cd="",
        tran_source="",
        description="",
        amount=Decimal("0.00"),
        orig_date="",
        proc_date="",
        merchant_id="",
        merchant_name="",
        merchant_city="",
        merchant_zip="",
        message=message,
    )


# ============================================================================
# Public exports
# ============================================================================

__all__: list[str] = ["TransactionService"]
