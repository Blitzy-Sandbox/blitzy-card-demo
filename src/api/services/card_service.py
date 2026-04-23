# ============================================================================
# Source: app/cbl/COCRDLIC.cbl  (Card List   — CICS transaction CCLI, 1,459 lines)
#       + app/cbl/COCRDSLC.cbl  (Card Detail — CICS transaction CCDL,   887 lines)
#       + app/cbl/COCRDUPC.cbl  (Card Update — CICS transaction CCUP, 1,560 lines)
#       + app/cpy/CVACT02Y.cpy  (CARD-RECORD 150-byte VSAM record layout — PK card_num)
#       + app/cpy/CVACT03Y.cpy  (CARD-XREF-RECORD 50-byte VSAM record layout)
#       + app/cpy-bms/COCRDLI.CPY / COCRDSL.CPY / COCRDUP.CPY
#         (BMS symbolic-map layouts defining the request / response contracts)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS ``EXEC CICS STARTBR / READNEXT / READPREV / ENDBR FILE('CARDDAT')``
#   (browse-mode cursor pagination in COCRDLIC.cbl — 7-row-per-page
#   screen with OCCURS 7 TIMES) +
#   ``EXEC CICS READ       FILE('CARDDAT')``
#   (COCRDSLC.cbl keyed detail read by CARD-NUM) +
#   ``EXEC CICS READ       FILE('CARDDAT') UPDATE`` +
#   ``EXEC CICS REWRITE    FILE('CARDDAT') FROM(CARD-UPDATE-RECORD)``
#   (COCRDUPC.cbl optimistic-concurrency update with RESP(NOTFND /
#   NOTOPEN / LENGERR / OTHER) response-code branches).
#
# becomes
#
#   SQLAlchemy 2.x async ``SELECT ... LIMIT ... OFFSET`` paginated
#   queries with ``SELECT COUNT(*)`` total-page computation for the
#   list endpoint; ``SELECT`` keyed lookup via
#   ``session.get(Card, card_num)`` for the detail endpoint; and
#   ``session.get(Card, card_num)`` + attribute-level mutation +
#   ``session.flush()`` + ``session.commit()`` with the Card model's
#   ``version_id_col`` optimistic-concurrency column, catching
#   :class:`sqlalchemy.orm.exc.StaleDataError` on concurrent-write
#   detection (replaces the CICS ``RESP(OTHER)`` RESP2 locking-
#   failure codes).
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
"""Card list, detail, and update service.

Converted from ``app/cbl/COCRDLIC.cbl`` (1,459 lines — browse-mode
paginated card list, 7 rows/page), ``app/cbl/COCRDSLC.cbl`` (887
lines — keyed card detail read by ``CARD-NUM``), and
``app/cbl/COCRDUPC.cbl`` (1,560 lines — optimistic-concurrency card
update via ``READ UPDATE`` + ``REWRITE`` with RESP code checks).

Covers features F-006 (card list), F-007 (card detail), and F-008
(card update with optimistic concurrency) from the Agent Action Plan.

The service exposes :class:`CardService`, used by the card router
(``src/api/routers/card_router.py``) and indirectly by the GraphQL
resolvers (``src/api/graphql/queries.py`` and
``src/api/graphql/mutations.py``). Like
:class:`src.api.services.bill_service.BillService` and
:class:`src.api.services.transaction_service.TransactionService`, the
class is intentionally stateful in the database session only: no
caches, no in-memory session data, no mutable class attributes. The
async session is scoped to a single HTTP request and managed by the
FastAPI dependency system; transaction boundaries are owned by the
caller for read-only flows (list, detail) and by the service for the
write flow (update).

COBOL → Python flow mapping (three PROCEDURE DIVISIONs merged):

============================================  ===============================================
COBOL paragraph / statement                   Python equivalent (this module)
============================================  ===============================================
COCRDLIC ``2100-PROCESS-ENTER-KEY``            :meth:`CardService.list_cards`
COCRDLIC ``2210-EDIT-ACCOUNT`` L1003-1033      (schema-level validation on
                                                ``CardListRequest.account_id``)
COCRDLIC ``2220-EDIT-CARD``    L1036-1066      (schema-level validation on
                                                ``CardListRequest.card_number``)
COCRDLIC ``9000-READ-FORWARD`` L1123-1263      :meth:`CardService.list_cards_forward`
                                                — cursor-based ``card_num > last_card_num``
                                                ``ORDER BY card_num ASC LIMIT 7``
COCRDLIC ``9100-READ-BACKWARDS`` L1264-1380    :meth:`CardService.list_cards_backward`
                                                — cursor-based ``card_num < first_card_num``
                                                ``ORDER BY card_num DESC LIMIT 7`` (reversed)
COCRDLIC ``9500-FILTER-RECORDS`` L1382-1459    inline ``.where()`` clauses on the
                                                SQLAlchemy query
COCRDLIC ``STARTBR FILE('CARDDAT') GTEQ``      ``select(Card).where(...).order_by(...)``
COCRDLIC ``READNEXT FILE('CARDDAT')``          ``.limit(7).offset((page-1)*7)``
COCRDLIC ``READPREV FILE('CARDDAT')``          ``.where(card_num < :pivot).order_by(desc)``
COCRDLIC ``ENDBR FILE('CARDDAT')``             (implicit — ``await self.db.execute(stmt)``)
COCRDLIC ``WS-NO-RECORDS-FOUND`` (empty)       ``info_message='NO RECORDS FOUND FOR THIS
                                                SEARCH CONDITION.'``
COCRDSLC ``9000-READ-DATA``                    :meth:`CardService.get_card_detail`
COCRDSLC ``9100-GETCARD-BYACCTCARD`` L736-777  ``await self.db.get(Card, card_num)``
COCRDSLC ``WHEN DFHRESP(NOTFND)``              returns response with
                                                ``error_message='Did not find cards for
                                                this search condition'``
COCRDSLC ``WHEN OTHER`` (lookup failure)       returns response with
                                                ``error_message='Error reading Card Data File'``
COCRDUPC ``9200-WRITE-PROCESSING`` L1420-1496  :meth:`CardService.update_card`
COCRDUPC ``READ FILE('CARDDAT') UPDATE``       ``await self.db.get(Card, card_num)``
COCRDUPC ``9300-CHECK-CHANGE-IN-REC`` L1498    attribute-level field mutation — SQLAlchemy
                                                auto-tracks dirty columns on flush
COCRDUPC ``REWRITE FILE('CARDDAT')``           ``await self.db.flush()`` +
                                                ``await self.db.commit()``
COCRDUPC ``DATA-WAS-CHANGED-BEFORE-UPDATE``    ``except StaleDataError`` →
                                                ``error_message='Record changed by some one
                                                else. Please review'``
COCRDUPC ``CONFIRM-UPDATE-SUCCESS``            ``info_message='Changes committed to database'``
COCRDUPC ``LOCKED-BUT-UPDATE-FAILED``          ``error_message='Update of record failed'``
COCRDUPC ``DID-NOT-FIND-ACCTCARD-COMBO``       ``error_message='Did not find cards for
                                                this search condition'``
============================================  ===============================================

Pagination page-size contract
-----------------------------
The COBOL COCRDLI.CPY BMS screen uses ``OCCURS 7 TIMES`` row groups
(CRDSEL1..CRDSEL7, ACCTNO1..ACCTNO7, CRDNUM1..CRDNUM7,
CRDSTS1..CRDSTS7) — i.e., 7 cards per page. The modern API preserves
7 as the page size via the :attr:`_PAGE_SIZE` module constant. This is
a card-specific width and differs from the transaction list's 10
rows/page (COTRN00.CPY OCCURS 10 TIMES). Clients MUST NOT assume a
page size of 10 for the card list endpoint.

Optimistic-concurrency contract
-------------------------------
The Card ORM model declares a ``version_id`` integer column wired to
SQLAlchemy's ``version_id_col`` mapper option (see
``src/shared/models/card.py``). On every UPDATE, SQLAlchemy:

1. Appends ``AND version_id = :old_version`` to the WHERE clause.
2. Increments the ``version_id`` column as part of the SET clause.

A stale read-then-write — where another transaction has already
incremented ``version_id`` between our read and write — results in
ZERO rows affected, which SQLAlchemy raises as
:class:`sqlalchemy.orm.exc.StaleDataError`. This replaces the CICS
``READ UPDATE`` / ``REWRITE`` enqueue-based locking protocol in
``COCRDUPC.cbl`` (see AAP §0.7.1 — "The optimistic concurrency check
in Card Update (F-008) must be maintained").

When :class:`StaleDataError` is caught, the service rolls back the
session and returns a :class:`CardUpdateResponse` with
``error_message='Record changed by some one else. Please review'``
— matching the COBOL ``DATA-WAS-CHANGED-BEFORE-UPDATE`` case on
lines 1498+ of ``COCRDUPC.cbl``.

Error message fidelity
----------------------
The COBOL error messages from the three source programs are preserved
byte-for-byte per AAP §0.7.1:

* ``'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'``
                                           (COCRDLIC.cbl L121-122; list empty)
* ``'NO MORE RECORDS TO SHOW'``            (COCRDLIC.cbl L1219; end of list)
* ``'Did not find cards for this search condition'``
                                           (COCRDSLC.cbl L153-154 /
                                           COCRDUPC.cbl L203-204; NOTFND)
* ``'Error reading Card Data File'``       (COCRDSLC.cbl XREF-READ-ERROR; OTHER lookup error)
* ``'Changes committed to database'``      (COCRDUPC.cbl L168-169; update success)
* ``'Update of record failed'``            (COCRDUPC.cbl L209-210; LOCKED-BUT-UPDATE-FAILED)
* ``'Record changed by some one else. Please review'``
                                           (COCRDUPC.cbl L207-208; DATA-WAS-CHANGED-BEFORE-UPDATE)

Observability
-------------
All card operations emit structured log records via the module
logger. Log records include the ``card_num`` / ``acct_id`` field
(never the full CVV in production — the ``cvv_cd`` column is
exclude-listed from all log messages by this module's convention) so
CloudWatch Logs Insights queries can correlate card-management
activity by card number or owning account. Log levels:

* ``INFO``    — successful list retrieval (with hit count), successful
  detail lookup, successful update (with new version_id).
* ``WARNING`` — business-rule failures: card not found on detail
  lookup or update, stale-data detection on update (concurrent
  modification by another client).
* ``ERROR``   — unexpected SQLAlchemy / driver exceptions (emitted via
  ``logger.exception`` / ``logger.error(exc_info=True)`` to preserve
  the full traceback alongside structured context).

See Also
--------
* AAP §0.2.3 — Online CICS Program Classification (F-006, F-007, F-008)
* AAP §0.5.1 — File-by-File Transformation Plan (``card_service.py``)
* AAP §0.7.1 — Refactoring-Specific Rules (preserve exact COBOL
  messages; optimistic concurrency must be maintained for F-008)
* ``src/shared/models/card.py`` — ORM model (150-byte CARD-RECORD)
  with ``version_id_col`` optimistic-concurrency column
* ``src/shared/models/card_cross_reference.py`` — xref ORM model
  used for account-based card filtering
* ``src/shared/schemas/card_schema.py`` — Pydantic request /
  response schemas (6 classes) and COBOL-sourced width constants
* ``src/api/services/transaction_service.py`` — sibling service;
  shares the same pagination / structured-logging idioms (but with
  10 rows/page instead of 7)
"""

from __future__ import annotations

import logging

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from src.shared.models.card import Card
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.schemas.card_schema import (
    CardDetailResponse,
    CardListItem,
    CardListRequest,
    CardListResponse,
    CardUpdateRequest,
    CardUpdateResponse,
)

logger: logging.Logger = logging.getLogger(__name__)
"""Module-scoped logger.

Emits structured events for card list, detail, and update
operations. Uses the fully-qualified module path so log routing
(e.g. ``src.api.services`` level thresholds) can isolate
card-service chatter from other services. Correlation IDs added
by the FastAPI request-scope middleware propagate automatically
through :func:`logging.getLogger` context filters.
"""


# ---------------------------------------------------------------------------
# Module-private constants
# ---------------------------------------------------------------------------
# Values preserved verbatim from the three source COBOL programs so the
# wire-format responses produced by this service are byte-for-byte
# identical to the pre-migration mainframe output (per AAP §0.7.1 —
# "Preserve all existing functionality exactly as-is" + "Maintain
# existing business logic without modification").

_PAGE_SIZE: int = 7
"""Fixed page size for the card list endpoint.

Derived from the COBOL BMS screen ``COCRDLI.bms`` and its symbolic
map ``COCRDLI.CPY``, both of which use ``OCCURS 7 TIMES`` row groups
(``CRDSEL1..CRDSEL7``, ``ACCTNO1..ACCTNO7``, ``CRDNUM1..CRDNUM7``,
``CRDSTS1..CRDSTS7``) — the 3270 terminal layout allocated exactly 7
rows for card records. This is a CARD-SPECIFIC page size and
differs from the transaction list's 10 rows/page (defined in
``COTRN00.CPY``). See also ``_PAGE_SIZE`` in
``src/shared/schemas/card_schema.py`` which must remain in sync
with this constant.

The COBOL source also declares this page height as
``WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`` at line 119 of
``COCRDLIC.cbl``.
"""

# ---------------------------------------------------------------------------
# User-facing messages (preserved byte-for-byte from COBOL source)
# ---------------------------------------------------------------------------
# Each message is accompanied by its COBOL source location and maximum
# permitted length. Lengths are cross-checked against the Pydantic
# ``max_length=`` validators in ``src/shared/schemas/card_schema.py``:
#   * info_message on list responses (COCRDLI.CPY INFOMSGI): 45 chars
#   * error_message on list responses (COCRDLI.CPY ERRMSGI): 78 chars
#   * info_message on detail / update responses (COCRDSL.CPY INFOMSGI): 40 chars
#   * error_message on detail / update responses (COCRDSL.CPY ERRMSGI): 80 chars

_MSG_LIST_NO_RECORDS: str = "NO RECORDS FOUND FOR THIS SEARCH CONDITION."
"""COCRDLIC.cbl L121-122 ``WS-NO-RECORDS-FOUND`` — empty list result."""

_MSG_LIST_NO_MORE_RECORDS: str = "NO MORE RECORDS TO SHOW"
"""COCRDLIC.cbl L1219 ``WS-NO-NEXT-PAGE`` — reached end of list during forward paging."""

# COCRDLIC.cbl L153-171 defines ``WS-FILE-ERROR-MESSAGE`` as an 80-character
# fixed-width concatenation used at every STARTBR/READNEXT/ENDBR failure
# site (L1226-1230, L1250-1254, L1312-1316, L1365-1369):
#
#   'File Error:' (12) + ERROR-OPNAME (8) + ' on ' (4) + ERROR-FILE (9) +
#   ' returned RESP ' (15) + ERROR-RESP (10) + ',RESP2 ' (7) + ERROR-RESP2 (10)
#   + filler (5) = 80 chars total
#
# Each call site MOVEs the specific operation name (e.g. 'READ'), file name
# (e.g. 'CARDDAT'), and RESP/RESP2 response-code strings before copying the
# composed message to WS-ERROR-MSG. There is no separate ``Unable to lookup
# cards...`` literal in the COBOL source — previous implementations
# fabricated that string. We now generate a COBOL-pattern-faithful message
# via :func:`_format_file_error_message` below, truncated to the 78-char
# ERRMSGI capacity on the List screen (COCRDLI.CPY ERRMSGI PIC X(78)).


def _format_file_error_message(op_name: str, file_name: str, resp: str, resp2: str) -> str:
    """Build a COCRDLIC-pattern ``File Error`` message.

    Faithfully reproduces the COCRDLIC.cbl L153-171 ``WS-FILE-ERROR-MESSAGE``
    fixed-width template:

    ::

        File Error:<op name padded to 8> on <file name padded to 9> returned
        RESP <resp padded to 10>,RESP2 <resp2 padded to 10>

    Parameters
    ----------
    op_name : str
        VSAM operation (READ, STARTBR, READNEXT, ENDBR, REWRITE). Padded
        to 8 characters with trailing spaces, matching COCRDLIC's
        ``ERROR-OPNAME PIC X(8)``.
    file_name : str
        DDNAME or logical file name (e.g. ``CARDDAT``). Padded to 9
        characters, matching ``ERROR-FILE PIC X(9)``.
    resp : str
        CICS RESP code or equivalent string. Padded to 10 characters,
        matching ``ERROR-RESP PIC X(10)``.
    resp2 : str
        CICS RESP2 code or equivalent. Padded to 10 characters,
        matching ``ERROR-RESP2 PIC X(10)``.

    Returns
    -------
    str
        Fully concatenated error message. May be truncated to 78 chars
        by the caller before emission to the COCRDLI ERRMSGI field.
    """
    return f"File Error:{op_name[:8]:<8} on {file_name[:9]:<9} returned RESP {resp[:10]:<10},RESP2 {resp2[:10]:<10}"


def _list_lookup_error_message(exc: Exception) -> str:
    """Convert a SQLAlchemy exception to a COCRDLIC File Error message.

    Replaces the fabricated ``"Unable to lookup cards..."`` literal with
    a COCRDLIC.cbl L1226-1230 pattern message — faithful to the COBOL
    ``WHEN OTHER`` branch at L1226 where STARTBR-on-CARDDAT failures
    populate ``WS-FILE-ERROR-MESSAGE`` with ``'READ'`` as ``ERROR-OPNAME``
    and ``LIT-CARD-FILE`` (``'CARDDAT  '``) as ``ERROR-FILE``. The Python
    RESP/RESP2 equivalents are the exception class name and first line
    of the exception message (truncated to the COBOL field widths).

    Parameters
    ----------
    exc : Exception
        The underlying SQLAlchemy / driver exception raised during
        STARTBR-equivalent (SELECT) or READNEXT-equivalent (LIMIT/OFFSET
        fetch) execution.

    Returns
    -------
    str
        A COCRDLIC-pattern ``File Error:`` message truncated to 78
        characters (the COCRDLI.CPY ``ERRMSGI PIC X(78)`` capacity).
    """
    resp_code: str = type(exc).__name__
    # Use only the first line of exception repr to stay within PIC X(10)
    resp2_code: str = str(exc).split("\n", 1)[0].strip()
    message: str = _format_file_error_message(
        op_name="READ",
        file_name="CARDDAT",
        resp=resp_code,
        resp2=resp2_code,
    )
    # COCRDLI.CPY ERRMSGI is PIC X(78); truncate to avoid schema
    # rejection. The COBOL source fixes the total at 80 chars but only
    # 78 are displayed on the List screen.
    return message[:78]


_MSG_DETAIL_NOT_FOUND: str = "Did not find cards for this search condition"
"""COCRDSLC.cbl L153-154 ``DID-NOT-FIND-ACCTCARD-COMBO`` — card_num not in CARDDAT."""

_MSG_DETAIL_LOOKUP_ERROR: str = "Error reading Card Data File"
"""COCRDSLC.cbl ``XREF-READ-ERROR`` — unexpected I/O failure on keyed read."""

_MSG_UPDATE_SUCCESS: str = "Changes committed to database"
"""COCRDUPC.cbl L168-169 ``CONFIRM-UPDATE-SUCCESS`` — successful REWRITE."""

_MSG_UPDATE_STALE: str = "Record changed by some one else. Please review"
"""COCRDUPC.cbl L207-208 ``DATA-WAS-CHANGED-BEFORE-UPDATE`` — version_id mismatch detected."""

_MSG_UPDATE_FAILED: str = "Update of record failed"
"""COCRDUPC.cbl L209-210 ``LOCKED-BUT-UPDATE-FAILED`` — REWRITE encountered DB error."""

_MSG_UPDATE_NOT_FOUND: str = "Did not find cards for this search condition"
"""COCRDUPC.cbl L203-204 — card_num not in CARDDAT at UPDATE time."""


# ---------------------------------------------------------------------------
# CardService
# ---------------------------------------------------------------------------
class CardService:
    """Service encapsulating card list, detail, and update operations.

    Translates the three CICS COBOL card-management programs
    (COCRDLIC, COCRDSLC, COCRDUPC) into async SQLAlchemy operations
    against the Aurora PostgreSQL ``cards`` table.

    Responsibilities
    ----------------
    * Paginated card listing (7 rows/page) with optional filtering by
      ``account_id`` and/or ``card_number`` — replaces
      ``COCRDLIC.cbl`` STARTBR/READNEXT browse mode.
    * Cursor-style forward / backward paging helpers —
      :meth:`list_cards_forward` / :meth:`list_cards_backward` —
      replicating the 3270 terminal's PF7/PF8 page-turn semantics.
    * Single-card detail lookup by 16-character ``card_num`` primary
      key — replaces ``COCRDSLC.cbl`` keyed ``READ FILE('CARDDAT')``.
    * Card update with optimistic concurrency control via the
      ``version_id`` column — replaces ``COCRDUPC.cbl`` ``READ UPDATE``
      / ``REWRITE`` pairing with RESP code checks.

    Transaction boundaries
    ----------------------
    The service does NOT manage transaction boundaries for read-only
    operations (list, detail, forward/backward paging) — the caller
    (typically the FastAPI dependency-injected session from
    ``src/api/database.py``) owns the transaction and commits or
    rolls back per request.

    For the write operation (:meth:`update_card`), the service manages
    its own transaction boundary: a successful flush+commit on happy
    path, or a rollback on any exception. This mirrors the COBOL
    ``SYNCPOINT`` / ``SYNCPOINT ROLLBACK`` boundaries in
    ``COCRDUPC.cbl`` (lines 1420-1496 — ``9200-WRITE-PROCESSING``).

    Concurrency model
    -----------------
    Class instances are cheap to construct and intentionally bound to
    a single :class:`AsyncSession` — meaning each HTTP request in the
    FastAPI layer gets its own service instance. Do NOT share a
    :class:`CardService` instance across concurrent requests or across
    asyncio tasks; each async context must construct its own
    instance.

    Parameters
    ----------
    db : AsyncSession
        SQLAlchemy 2.x async session, typically injected via the
        ``get_async_db`` FastAPI dependency. The session must be
        attached to the carddemo Aurora PostgreSQL database and must
        have the Card + CardCrossReference ORM models registered in
        its metadata.

    Examples
    --------
    Typical usage from a FastAPI route::

        @router.get("/cards", response_model=CardListResponse)
        async def list_cards_route(
            request: CardListRequest = Depends(),
            db: AsyncSession = Depends(get_async_db),
        ) -> CardListResponse:
            service = CardService(db)
            return await service.list_cards(request)
    """

    def __init__(self, db: AsyncSession) -> None:
        """Bind the service to an async database session.

        Parameters
        ----------
        db : AsyncSession
            The SQLAlchemy 2.x async session. Ownership of the
            session (including open/close lifecycle) remains with
            the caller; the service only issues queries and,
            for :meth:`update_card`, ``flush``/``commit``/``rollback``
            calls against the existing session.
        """
        self.db: AsyncSession = db

    # -----------------------------------------------------------------
    # Card list (F-006) — COCRDLIC.cbl 2100-PROCESS-ENTER-KEY
    # -----------------------------------------------------------------
    async def list_cards(self, request: CardListRequest) -> CardListResponse:
        """Return a paginated page of card records matching the request.

        Maps to ``COCRDLIC.cbl`` ``2100-PROCESS-ENTER-KEY`` paragraph
        (lines 950-1001) which orchestrates the browse-mode card
        lookup. The COBOL flow is:

        1. Edit account_id (2210-EDIT-ACCOUNT) — 11-digit numeric.
        2. Edit card_number (2220-EDIT-CARD) — 16-digit numeric.
        3. STARTBR FILE('CARDDAT') GTEQ on card_num (or account-based
           filter via xref).
        4. READNEXT up to WS-MAX-SCREEN-LINES (=7) times.
        5. Check for more records beyond the 7th (sets
           WS-NEXT-PAGE-EXISTS flag).
        6. ENDBR FILE('CARDDAT').
        7. Populate CRDSEL1..CRDSEL7 screen rows and info/error msgs.

        The Python translation issues two queries:

        * ``SELECT COUNT(*)`` for total record count (used to compute
          ``total_pages``).
        * ``SELECT * FROM cards WHERE <filters> ORDER BY card_num
          LIMIT 7 OFFSET (page-1)*7`` for the current page.

        Filter validation (11-digit account, 16-digit card) has
        already been performed at the Pydantic schema layer by
        :class:`CardListRequest` validators; this method trusts the
        validated input and applies the filters directly as SQL
        WHERE clauses.

        Account-based filtering additionally joins via
        :class:`CardCrossReference` (CARD-XREF-RECORD from
        ``CVACT03Y.cpy``) to resolve ``account_id -> card_num``
        relationships — matching the COBOL logic that uses the
        account AIX alternate index on CARDDAT.

        Parameters
        ----------
        request : CardListRequest
            Validated request model. Fields:
              * ``account_id``: optional 11-digit numeric account
                filter (stripped to None if whitespace-only).
              * ``card_number``: optional 16-character card filter.
              * ``page_number``: 1-indexed page number (defaults to 1).

        Returns
        -------
        CardListResponse
            Response with at most 7 :class:`CardListItem` entries,
            along with pagination metadata (``page_number``,
            ``total_pages``) and optional
            ``info_message``/``error_message`` text.
        """
        log_context: dict[str, object] = {
            "operation": "list_cards",
            "account_id": request.account_id,
            "card_number": request.card_number,
            "page_number": request.page_number,
            "page_size": _PAGE_SIZE,
        }

        # ---- Build the two queries in lock-step --------------------------
        # Each filter is applied to BOTH the COUNT statement and the
        # SELECT statement so the two query plans stay in sync. Each
        # predicate maps 1:1 to a COCRDLIC.cbl WHERE-clause check.
        # -----------------------------------------------------------------
        count_stmt = select(func.count()).select_from(Card)
        data_stmt = select(Card).order_by(Card.card_num)

        # Filter 1: card_number exact-match — maps to COCRDLIC
        # 2220-EDIT-CARD + STARTBR FILE('CARDDAT') GTEQ on CARD-NUM.
        if request.card_number:
            count_stmt = count_stmt.where(Card.card_num == request.card_number)
            data_stmt = data_stmt.where(Card.card_num == request.card_number)

        # Filter 2: account-based filter — maps to COCRDLIC using
        # the CARD-XREF AIX alternate index (CVACT03Y.cpy) to
        # resolve acct_id -> card_num. In Python we perform a
        # semi-join via a sub-SELECT against CardCrossReference to
        # avoid cartesian joins when combined with card_number.
        if request.account_id:
            xref_subquery = select(CardCrossReference.card_num).where(CardCrossReference.acct_id == request.account_id)
            count_stmt = count_stmt.where(Card.card_num.in_(xref_subquery))
            data_stmt = data_stmt.where(Card.card_num.in_(xref_subquery))

        # ---- Apply LIMIT/OFFSET to the SELECT statement ------------------
        # Maps to STARTBR GTEQ + READNEXT up to WS-MAX-SCREEN-LINES=7
        # times. We order by card_num ASC to preserve the VSAM KSDS
        # natural key sequence.
        # -----------------------------------------------------------------
        offset_rows = max(request.page_number - 1, 0) * _PAGE_SIZE
        data_stmt = data_stmt.offset(offset_rows).limit(_PAGE_SIZE)

        # ---- Execute both queries with a shared error handler ------------
        # COCRDLIC.cbl handles STARTBR/READNEXT failures via RESP code
        # checks in the WHEN OTHER branch of an EVALUATE. In Python
        # we catch the broader Exception (which covers driver-level,
        # connection, and statement errors) and surface a user-safe
        # message. The blanket except is gated by the ruff "BLE001"
        # suppression per project convention (matches
        # transaction_service.py and bill_service.py COBOL WHEN OTHER
        # semantics).
        # -----------------------------------------------------------------
        try:
            count_result = await self.db.execute(count_stmt)
            total_count: int = count_result.scalar_one() or 0

            data_result = await self.db.execute(data_stmt)
            cards: list[Card] = list(data_result.scalars().all())
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Card list lookup failed with unexpected error",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            # COCRDLIC.cbl L1226-1230: populates WS-FILE-ERROR-MESSAGE
            # with 'READ' / LIT-CARD-FILE / RESP / RESP2 and moves it
            # to WS-ERROR-MSG. Python equivalent: map SQLAlchemy
            # exception to the COBOL File Error template.
            return CardListResponse(
                cards=[],
                page_number=request.page_number,
                total_pages=0,
                info_message=None,
                error_message=_list_lookup_error_message(exc),
            )

        # ---- Build response items ---------------------------------------
        # Each ORM Card instance is projected into the CardListItem
        # wire-format. ``selected`` is always 'N' on initial fetch
        # (the user selects rows via the BMS screen; the API returns
        # pristine rows ready for selection by downstream UIs).
        # -----------------------------------------------------------------
        items: list[CardListItem] = [
            CardListItem(
                selected="N",
                account_id=card.acct_id,
                card_number=card.card_num,
                card_status=card.active_status,
            )
            for card in cards
        ]

        # ---- Compute pagination metadata --------------------------------
        # total_pages uses integer-ceiling division so a partial
        # final page still counts as a full page (standard pagination
        # convention). When total_count is zero, total_pages is zero.
        # -----------------------------------------------------------------
        total_pages: int = 0
        if total_count > 0:
            total_pages = (total_count + _PAGE_SIZE - 1) // _PAGE_SIZE

        # ---- Derive info / error messages -------------------------------
        # Matches COCRDLIC.cbl 2100-PROCESS-ENTER-KEY flow:
        #   * If no records at all -> WS-NO-RECORDS-FOUND
        #   * Else if user paged past the last page -> NO-MORE-RECORDS
        #   * Else -> quiet response (no message)
        # -----------------------------------------------------------------
        info_message: str | None = None
        error_message: str | None = None

        if total_count == 0:
            info_message = _MSG_LIST_NO_RECORDS
        elif not items and request.page_number > 1:
            # User requested a page beyond the valid range (e.g. page
            # 10 of a 2-page result). Preserves COBOL L1219 behavior.
            info_message = _MSG_LIST_NO_MORE_RECORDS

        response = CardListResponse(
            cards=items,
            page_number=request.page_number,
            total_pages=total_pages,
            info_message=info_message,
            error_message=error_message,
        )

        logger.info(
            "Card list retrieved",
            extra={
                **log_context,
                "returned_count": len(items),
                "total_count": total_count,
                "total_pages": total_pages,
            },
        )
        return response

    # -----------------------------------------------------------------
    # Forward paging (cursor-based) — COCRDLIC.cbl 9000-READ-FORWARD
    # -----------------------------------------------------------------
    async def list_cards_forward(
        self,
        last_card_num: str,
        account_id: str | None,
        page_size: int = _PAGE_SIZE,
    ) -> list[Card]:
        """Return the next page of cards AFTER ``last_card_num``.

        Maps to ``COCRDLIC.cbl`` paragraph ``9000-READ-FORWARD``
        (lines 1123-1263). The COBOL flow is:

        1. ``EXEC CICS STARTBR FILE('CARDDAT') RIDFLD(last_card_num)
           GTEQ`` — position the browse cursor at-or-after
           ``last_card_num``.
        2. ``EXEC CICS READNEXT FILE('CARDDAT') INTO(CARD-RECORD)``
           — read the first record AT-OR-AFTER the cursor; if equal,
           skip it (next-page semantics).
        3. Repeat READNEXT up to ``WS-MAX-SCREEN-LINES`` (=7) times.
        4. ``EXEC CICS ENDBR FILE('CARDDAT')``.

        The Python translation uses strict cursor-based pagination
        via ``WHERE card_num > :last_card_num ORDER BY card_num ASC
        LIMIT 7``. This is keyset (a.k.a. seek) pagination and is
        strictly more efficient than OFFSET/LIMIT for deep pages —
        a welcome side-effect of preserving the COBOL STARTBR
        semantics.

        Parameters
        ----------
        last_card_num : str
            The 16-character card_num that was last shown on the
            previous page. The method returns cards strictly greater
            than this key (i.e. NOT including last_card_num itself,
            matching the "next page" convention).
        account_id : str | None
            Optional 11-digit account-id filter. When provided, the
            result is restricted to cards belonging to that account
            via a semi-join against :class:`CardCrossReference`
            (CARD-XREF alternate index on CARDDAT).
        page_size : int, default 7
            Maximum number of cards to return. Defaults to
            :attr:`_PAGE_SIZE` to match the BMS OCCURS 7 TIMES layout.
            Callers MAY pass a smaller page size (for UIs with less
            vertical real estate) but SHOULD NOT pass a value larger
            than 7 without discussing the impact on downstream BMS-
            compatible clients.

        Returns
        -------
        list[Card]
            The next (at most ``page_size``) card ORM instances, in
            ascending ``card_num`` order. Empty list indicates no
            more records (equivalent to COBOL ``WS-NO-NEXT-PAGE`` at
            line 1219).

        Raises
        ------
        Exception
            Any SQLAlchemy / driver-level error is logged and
            re-raised to the caller. Unlike :meth:`list_cards` which
            swallows errors and returns a user-friendly error message,
            this lower-level helper surfaces errors so the caller can
            handle them contextually.
        """
        log_context: dict[str, object] = {
            "operation": "list_cards_forward",
            "last_card_num": last_card_num,
            "account_id": account_id,
            "page_size": page_size,
        }

        # ---- Build the forward-seek query -------------------------------
        # The strict inequality (``>``) matches COBOL ``STARTBR GTEQ`` +
        # ``READNEXT`` with the equal-key skip step: if the cursor lands
        # on a row exactly equal to last_card_num, the COBOL code reads
        # and discards it before returning data to the caller. By using
        # ``>`` in SQL we encode the same "skip equal" semantics in a
        # single predicate.
        # -----------------------------------------------------------------
        stmt = select(Card).where(Card.card_num > last_card_num).order_by(Card.card_num).limit(page_size)

        # ---- Apply optional account-based filter ------------------------
        if account_id:
            xref_subquery = select(CardCrossReference.card_num).where(CardCrossReference.acct_id == account_id)
            stmt = stmt.where(Card.card_num.in_(xref_subquery))

        try:
            result = await self.db.execute(stmt)
            cards: list[Card] = list(result.scalars().all())
        except Exception as exc:
            logger.error(
                "Forward card paging failed with unexpected error",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            raise

        logger.info(
            "Forward card paging retrieved rows",
            extra={**log_context, "returned_count": len(cards)},
        )
        return cards

    # -----------------------------------------------------------------
    # Backward paging (cursor-based) — COCRDLIC.cbl 9100-READ-BACKWARDS
    # -----------------------------------------------------------------
    async def list_cards_backward(
        self,
        first_card_num: str,
        account_id: str | None,
        page_size: int = _PAGE_SIZE,
    ) -> list[Card]:
        """Return the previous page of cards BEFORE ``first_card_num``.

        Maps to ``COCRDLIC.cbl`` paragraph ``9100-READ-BACKWARDS``
        (lines 1264-1380). The COBOL flow is:

        1. ``EXEC CICS STARTBR FILE('CARDDAT') RIDFLD(first_card_num)
           GTEQ`` — position the browse cursor at-or-after
           ``first_card_num``.
        2. ``EXEC CICS READPREV FILE('CARDDAT') INTO(CARD-RECORD)``
           — walk backward through the VSAM key space.
        3. Repeat READPREV up to ``WS-MAX-SCREEN-LINES`` (=7) times.
        4. ``EXEC CICS ENDBR FILE('CARDDAT')``.

        The Python translation uses ``WHERE card_num < :first_card_num
        ORDER BY card_num DESC LIMIT 7`` to fetch the rows in reverse
        order, then reverses the result in Python so the caller
        receives them in ascending display order (the same order they
        appear on the BMS screen). This matches COBOL behavior where
        READPREV returns rows in reverse order but the BMS screen
        array is populated in ascending visual order.

        Parameters
        ----------
        first_card_num : str
            The 16-character card_num that is the FIRST entry on the
            CURRENT page. The method returns cards strictly LESS than
            this key (i.e. the page immediately preceding the current
            page, matching the "previous page" convention).
        account_id : str | None
            Optional 11-digit account-id filter. When provided, the
            result is restricted to cards belonging to that account
            via a semi-join against :class:`CardCrossReference`.
        page_size : int, default 7
            Maximum number of cards to return. Defaults to
            :attr:`_PAGE_SIZE` to match the BMS OCCURS 7 TIMES layout.

        Returns
        -------
        list[Card]
            At most ``page_size`` card ORM instances in ASCENDING
            ``card_num`` order (same visual ordering as
            :meth:`list_cards_forward`), despite the underlying SQL
            using a DESC sort for keyset efficiency. An empty list
            indicates the caller is already on the first page
            (equivalent to COBOL ``WS-AT-TOP-OF-PAGE`` at line 1239).

        Raises
        ------
        Exception
            Any SQLAlchemy / driver-level error is logged and
            re-raised to the caller.
        """
        log_context: dict[str, object] = {
            "operation": "list_cards_backward",
            "first_card_num": first_card_num,
            "account_id": account_id,
            "page_size": page_size,
        }

        # ---- Build the backward-seek query ------------------------------
        # Strict inequality (``<``) + DESC sort + LIMIT gives us the
        # last ``page_size`` rows BEFORE first_card_num. We then
        # reverse the list in Python to present the caller with
        # ascending order (matching BMS screen-array sequencing).
        # -----------------------------------------------------------------
        stmt = select(Card).where(Card.card_num < first_card_num).order_by(Card.card_num.desc()).limit(page_size)

        # ---- Apply optional account-based filter ------------------------
        if account_id:
            xref_subquery = select(CardCrossReference.card_num).where(CardCrossReference.acct_id == account_id)
            stmt = stmt.where(Card.card_num.in_(xref_subquery))

        try:
            result = await self.db.execute(stmt)
            # Reverse in-memory so callers receive ascending-order rows
            # regardless of the underlying DESC sort used for efficient
            # keyset pagination. The reversal is O(page_size) = O(7)
            # — trivial.
            cards_desc: list[Card] = list(result.scalars().all())
            cards: list[Card] = list(reversed(cards_desc))
        except Exception as exc:
            logger.error(
                "Backward card paging failed with unexpected error",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            raise

        logger.info(
            "Backward card paging retrieved rows",
            extra={**log_context, "returned_count": len(cards)},
        )
        return cards

    # -----------------------------------------------------------------
    # Card detail (F-007) — COCRDSLC.cbl 9000-READ-DATA
    # -----------------------------------------------------------------
    async def get_card_detail(self, card_num: str) -> CardDetailResponse:
        """Return a single card's detail view by 16-character card_num.

        Maps to ``COCRDSLC.cbl`` ``9000-READ-DATA`` paragraph which
        orchestrates the keyed read:

        1. Validate CARD-NUM input (lengths, numeric checks performed
           by the caller — this method assumes a valid 16-char input,
           consistent with the Pydantic schema contracts).
        2. ``EXEC CICS READ FILE('CARDDAT') INTO(CARD-RECORD)
           RIDFLD(CARD-NUM) RESP(WS-RESP-CD)``.
        3. ``EVALUATE DFHRESP(NORMAL)``  → populate ACTVWI screen.
        4. ``EVALUATE DFHRESP(NOTFND)``  → error 'Did not find cards
           for this search condition'.
        5. ``EVALUATE OTHER``            → error 'Error reading Card
           Data File'.

        The Python translation uses
        :meth:`sqlalchemy.ext.asyncio.AsyncSession.get` for the PK
        lookup — identical semantics to a ``SELECT * FROM cards
        WHERE card_num = :card_num`` query but with identity-map
        caching automatic and no risk of MultipleResultsFound
        (``card_num`` is the PK).

        Parameters
        ----------
        card_num : str
            16-character card number (primary key of the Card
            entity). Whitespace is stripped; empty input returns a
            not-found response rather than raising.

        Returns
        -------
        CardDetailResponse
            Response with populated card fields on success, or with
            ``error_message`` set on not-found / unexpected errors.
            Fields returned on success:

            * ``account_id``       (11 chars)
            * ``card_number``      (16 chars — same as input)
            * ``embossed_name``    (up to 50 chars — from
              CARD-EMBOSSED-NAME)
            * ``status_code``      (1 char — 'Y' active or 'N'
              inactive, from CARD-ACTIVE-STATUS)
            * ``expiry_month``     (2 chars — parsed from
              expiration_date month component)
            * ``expiry_year``      (4 chars — parsed from
              expiration_date year component)

        Notes
        -----
        The ``expiration_date`` column in the ORM model is stored as a
        10-character string in ``YYYY-MM-DD`` format (mirroring the
        COBOL ``PIC X(10)`` byte-for-byte — see
        ``src/shared/models/card.py``). This method parses the MM and
        YYYY components for the response. If the stored value is not
        in the expected format, the method defensively returns
        whitespace-padded MM/YYYY rather than raising, matching the
        COBOL tolerance for malformed date fields in CARDDAT.
        """
        normalized_card_num: str = (card_num or "").strip()
        log_context: dict[str, object] = {
            "operation": "get_card_detail",
            "card_num": normalized_card_num,
        }

        # ---- Guard: empty or malformed input ---------------------------
        # COBOL equivalent: WS-PROMPT-FOR-CARD = 'Card number not
        # provided' (COCRDUPC.cbl L181). We return a not-found
        # response rather than raising so the caller can render a
        # user-friendly error message without special-casing
        # exceptions.
        # -----------------------------------------------------------------
        if not normalized_card_num:
            logger.warning(
                "Card detail lookup attempted with empty card_num",
                extra=log_context,
            )
            return _build_detail_not_found_response(card_num=normalized_card_num)

        # ---- Execute keyed PK lookup -----------------------------------
        # Maps to EXEC CICS READ FILE('CARDDAT') RIDFLD(CARD-NUM).
        # session.get() is the SQLAlchemy 2.x idiom for PK-by-value
        # lookup; it checks the identity map first (avoiding a round
        # trip on cache hit) and issues a SELECT on cache miss.
        # -----------------------------------------------------------------
        try:
            card: Card | None = await self.db.get(Card, normalized_card_num)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Card detail lookup failed with unexpected error",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            return CardDetailResponse(
                account_id="",
                card_number=normalized_card_num,
                embossed_name="",
                status_code="",
                expiry_month="",
                expiry_year="",
                info_message=None,
                error_message=_MSG_DETAIL_LOOKUP_ERROR,
            )

        # ---- Not-found branch (RESP(NOTFND) in COCRDSLC.cbl) -----------
        if card is None:
            logger.warning(
                "Card detail lookup returned no rows",
                extra=log_context,
            )
            return _build_detail_not_found_response(card_num=normalized_card_num)

        # ---- Happy path: project ORM into response schema --------------
        # Parse the 10-char expiration_date (YYYY-MM-DD) into the
        # MM / YYYY components expected by the detail response.
        # -----------------------------------------------------------------
        expiry_year, expiry_month = _parse_expiration_date(card.expiration_date)

        response = CardDetailResponse(
            account_id=card.acct_id,
            card_number=card.card_num,
            embossed_name=card.embossed_name,
            status_code=card.active_status,
            expiry_month=expiry_month,
            expiry_year=expiry_year,
            info_message=None,
            error_message=None,
        )

        # Diagnostic: log CVV presence (length only — NEVER the value
        # itself, to preserve PCI-DSS compliance). The cvv_cd column
        # is intentionally absent from the response schema; this log
        # confirms the column is populated at the persistence layer.
        cvv_length: int = len(card.cvv_cd) if card.cvv_cd else 0

        logger.info(
            "Card detail retrieved",
            extra={
                **log_context,
                "acct_id": card.acct_id,
                "cvv_length": cvv_length,
            },
        )
        return response

    # -----------------------------------------------------------------
    # Card update (F-008) — COCRDUPC.cbl 9200-WRITE-PROCESSING
    # -----------------------------------------------------------------
    async def update_card(
        self,
        card_num: str,
        request: CardUpdateRequest,
    ) -> CardUpdateResponse:
        """Update a card's mutable fields with optimistic concurrency control.

        Maps to ``COCRDUPC.cbl`` paragraphs ``9200-WRITE-PROCESSING``
        (lines 1420-1496) and ``9300-CHECK-CHANGE-IN-REC`` (line
        1498+). The COBOL flow is:

        1. ``EXEC CICS READ FILE('CARDDAT') UPDATE INTO(CARD-RECORD)
           RIDFLD(CARD-NUM)`` — read with enqueue (locks the record).
        2. Compare current record against CARD-RECORD-BEFORE-UPDATE
           captured during the initial display — detect concurrent
           modification (``DATA-WAS-CHANGED-BEFORE-UPDATE``).
        3. If unchanged since display, ``MOVE`` the new field values
           into CARD-UPDATE-RECORD.
        4. ``EXEC CICS REWRITE FILE('CARDDAT') FROM(CARD-UPDATE-
           RECORD) RESP(WS-RESP-CD)``.
        5. On NORMAL  → ``CONFIRM-UPDATE-SUCCESS`` ('Changes
           committed to database').
        6. On OTHER   → ``LOCKED-BUT-UPDATE-FAILED`` ('Update of
           record failed').

        The Python translation uses SQLAlchemy's native
        version_id_col feature which:

        1. Reads the current row including the current ``version_id``.
        2. Applies attribute-level mutations (marks the ORM instance
           dirty).
        3. Issues ``UPDATE cards SET <changed-cols>, version_id =
           version_id + 1 WHERE card_num = :pk AND version_id =
           :old_version`` on flush.
        4. If zero rows affected (version_id mismatch), SQLAlchemy
           raises :class:`sqlalchemy.orm.exc.StaleDataError` —
           replicating the COBOL
           ``DATA-WAS-CHANGED-BEFORE-UPDATE`` error path.
        5. On success, ``version_id`` is atomically incremented and
           the next update will see the new value.

        This preserves the F-008 optimistic-concurrency contract from
        AAP §0.7.1: "The optimistic concurrency check in Card Update
        (F-008) must be maintained".

        Parameters
        ----------
        card_num : str
            16-character card number (primary key). Must match the
            ``request.card_number`` field.
        request : CardUpdateRequest
            Validated request model. Fields:

            * ``account_id`` (11 chars)
            * ``card_number`` (16 chars — must match ``card_num`` arg)
            * ``embossed_name`` (up to 50 chars)
            * ``status_code`` (1 char — 'Y' or 'N')
            * ``expiry_month`` (2 chars — '01'..'12')
            * ``expiry_year`` (4 chars)
            * ``expiry_day`` (2 chars)

        Returns
        -------
        CardUpdateResponse
            Response with the POST-update state of the card (for
            display in the modern API client) plus an info or error
            message. On success, ``info_message='Changes committed to
            database'``. On stale-data detection,
            ``error_message='Record changed by some one else. Please
            review'``. On other failures, ``error_message='Update of
            record failed'``.

        Notes
        -----
        On every error path (not-found, stale-data, generic failure),
        the service calls ``self.db.rollback()`` to undo any dirty
        state in the session so subsequent operations on the same
        session start from a clean slate. This mirrors the COBOL
        ``SYNCPOINT ROLLBACK`` that ``COCRDUPC.cbl`` would issue via
        the CICS transaction-management implicit rollback on abnormal
        termination.
        """
        normalized_card_num: str = (card_num or "").strip()
        log_context: dict[str, object] = {
            "operation": "update_card",
            "card_num": normalized_card_num,
            "request_card_number": request.card_number,
            "request_account_id": request.account_id,
        }

        # ---- Guard: empty / missing card_num ---------------------------
        if not normalized_card_num:
            logger.warning(
                "Card update attempted with empty card_num",
                extra=log_context,
            )
            return _build_update_error_response(
                request=request,
                error_message=_MSG_UPDATE_NOT_FOUND,
            )

        # ---- Guard: path/body card_num mismatch ------------------------
        # COBOL-equivalent: the CICS screen binds the displayed
        # CARD-NUM to the input field, so a mismatch is essentially
        # impossible at the mainframe. On REST, however, clients can
        # craft a URL-path ``card_num`` that differs from the body's
        # ``card_number``. We treat this as a business-rule violation
        # (not-found semantics), matching the COBOL validator that
        # would reject inconsistent input.
        # -----------------------------------------------------------------
        if request.card_number != normalized_card_num:
            logger.warning(
                "Card update rejected: path card_num does not match body card_number",
                extra=log_context,
            )
            return _build_update_error_response(
                request=request,
                error_message=_MSG_UPDATE_NOT_FOUND,
            )

        # ---- Read-for-update: load the existing Card into the session --
        # Maps to EXEC CICS READ FILE('CARDDAT') UPDATE — this pulls
        # the current row (including the current version_id) into
        # the identity map so subsequent attribute mutations are
        # tracked. Unlike CICS READ UPDATE, SQLAlchemy does NOT hold
        # a row-level lock; the optimistic-concurrency pattern via
        # version_id is the substitute (see module docstring and
        # Card model docstring).
        # -----------------------------------------------------------------
        try:
            card: Card | None = await self.db.get(Card, normalized_card_num)
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            logger.error(
                "Card update failed during initial read",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            # Roll back to discard any partial session state and
            # restore the session to a clean state for any subsequent
            # operations on the same AsyncSession.
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(
                request=request,
                error_message=_MSG_UPDATE_FAILED,
            )

        # ---- Not-found branch (RESP(NOTFND) in COCRDUPC.cbl) -----------
        if card is None:
            logger.warning(
                "Card update target not found in CARDDAT",
                extra=log_context,
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(
                request=request,
                error_message=_MSG_UPDATE_NOT_FOUND,
            )

        # ---- Apply mutations to the ORM instance -----------------------
        # SQLAlchemy's unit-of-work tracks attribute assignments on
        # managed instances and emits an UPDATE statement at flush
        # time. The version_id_col feature ensures the UPDATE's WHERE
        # clause carries ``AND version_id = :old_version``, so a
        # concurrent modification results in 0 rows affected and a
        # subsequent StaleDataError on flush.
        #
        # COBOL-equivalent: MOVE CARD-UPDATE-* fields into
        # CARD-UPDATE-RECORD prior to the REWRITE. The ``acct_id``
        # field is NOT updated (it's the owning account and a
        # card-to-account reassignment would require a cross-reference
        # update — out of scope for F-008). The ``card_num`` field is
        # never mutated (it's the PK).
        # -----------------------------------------------------------------
        card.embossed_name = request.embossed_name
        card.active_status = request.status_code
        card.expiration_date = _assemble_expiration_date(
            year=request.expiry_year,
            month=request.expiry_month,
            day=request.expiry_day,
        )

        # Explicit CVV preservation: the CARD-CVV-CD (cvv_cd) column
        # is intentionally NOT mutated by this operation (the modern
        # API does not accept CVV updates; CVV rotation is handled by
        # a separate card-reissuance flow out of scope for F-008).
        # Reading the field here both:
        #   (a) documents the preservation contract explicitly (so a
        #       future refactor cannot accidentally null the CVV), and
        #   (b) satisfies PCI-DSS audit logging requirements — we
        #       emit the LENGTH of the CVV (never the value) alongside
        #       the update event for traceability.
        preserved_cvv_length: int = len(card.cvv_cd) if card.cvv_cd else 0
        log_context["cvv_preserved_length"] = preserved_cvv_length

        # Track the pre-update version_id for diagnostics / logging.
        old_version_id: int = card.version_id
        log_context["old_version_id"] = old_version_id

        # ---- Attempt to flush + commit ---------------------------------
        # flush() sends the UPDATE to the database so any
        # StaleDataError surfaces here (rather than at the implicit
        # commit, where error handling is harder to structure).
        # commit() then persists the transaction.
        # -----------------------------------------------------------------
        try:
            await self.db.flush()
            await self.db.commit()
        except StaleDataError as exc:
            # Optimistic-concurrency conflict detected — someone else
            # updated this card between our read and our write. Roll
            # back and return the COBOL-equivalent error message.
            #
            # Maps to COCRDUPC.cbl L207-208
            # ``DATA-WAS-CHANGED-BEFORE-UPDATE`` = 'Record changed by
            # some one else. Please review'.
            logger.warning(
                "Card update aborted due to optimistic-concurrency conflict",
                extra={
                    **log_context,
                    "error_type": type(exc).__name__,
                },
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(
                request=request,
                error_message=_MSG_UPDATE_STALE,
            )
        except Exception as exc:  # noqa: BLE001 — blanket catch per COBOL WHEN OTHER
            # Any other failure during REWRITE — constraint violation,
            # driver error, connection drop, serialization failure.
            # COBOL equivalent: RESP(OTHER) from the REWRITE -> maps
            # to COCRDUPC.cbl L209-210 ``LOCKED-BUT-UPDATE-FAILED``
            # = 'Update of record failed'.
            logger.error(
                "Card update failed with unexpected error during flush/commit",
                extra={**log_context, "error_type": type(exc).__name__},
                exc_info=True,
            )
            await _safe_rollback(self.db, log_context)
            return _build_update_error_response(
                request=request,
                error_message=_MSG_UPDATE_FAILED,
            )

        # ---- Happy path: refresh + return post-update response ---------
        # After commit, the ORM instance still reflects the in-memory
        # state. The new version_id has been applied by SQLAlchemy.
        # The post-commit state is what the client wants to see.
        # -----------------------------------------------------------------
        new_version_id: int = card.version_id
        logger.info(
            "Card updated successfully",
            extra={
                **log_context,
                "old_version_id": old_version_id,
                "new_version_id": new_version_id,
            },
        )

        expiry_year, expiry_month = _parse_expiration_date(card.expiration_date)
        return CardUpdateResponse(
            account_id=card.acct_id,
            card_number=card.card_num,
            embossed_name=card.embossed_name,
            status_code=card.active_status,
            expiry_month=expiry_month,
            expiry_year=expiry_year,
            info_message=_MSG_UPDATE_SUCCESS,
            error_message=None,
        )


# ---------------------------------------------------------------------------
# Module-private helper functions
# ---------------------------------------------------------------------------
# These helpers factor out response-construction and date-parsing logic
# that is repeated across multiple CardService methods. They are kept at
# module scope (rather than as CardService static methods) so they can
# be unit-tested without requiring a service instance.


def _parse_expiration_date(expiration_date: str) -> tuple[str, str]:
    """Split a ``YYYY-MM-DD`` string into (year, month) 4/2-char components.

    Defensive parser for the ``Card.expiration_date`` column, which
    is stored as a 10-character string mirroring the COBOL
    ``PIC X(10)`` field (see ``src/shared/models/card.py``). Returns
    4-char year and 2-char month suitable for direct inclusion in
    :class:`CardDetailResponse` and :class:`CardUpdateResponse`.

    Parameters
    ----------
    expiration_date : str
        The raw 10-character date string from the ORM. Expected
        format is ``YYYY-MM-DD`` (ISO 8601) but this function
        tolerates shorter or malformed input and returns empty
        strings for the non-parseable components, matching COBOL's
        tolerance for ragged data in VSAM records.

    Returns
    -------
    tuple[str, str]
        ``(year, month)`` as 4-char and 2-char strings respectively.
        Returns ``("", "")`` when the input is too short / malformed.

    Examples
    --------
    >>> _parse_expiration_date("2027-12-31")
    ('2027', '12')
    >>> _parse_expiration_date("")
    ('', '')
    >>> _parse_expiration_date("not-a-date")
    ('', '')
    """
    if not expiration_date:
        return ("", "")

    parts: list[str] = expiration_date.split("-")
    if len(parts) < 2:
        return ("", "")

    year_part: str = parts[0]
    month_part: str = parts[1]

    # Validate length of each component to guard against malformed
    # input. COBOL would simply accept the raw bytes; we check
    # length+digit-ness to avoid surfacing corrupted data to the API
    # client. If either component fails validation, return empty
    # strings for both (matching COBOL's all-or-nothing display
    # tolerance).
    if len(year_part) != 4 or not year_part.isdigit():
        return ("", "")
    if len(month_part) != 2 or not month_part.isdigit():
        return ("", "")

    return (year_part, month_part)


def _assemble_expiration_date(year: str, month: str, day: str) -> str:
    """Combine 4-char year, 2-char month, and 2-char day into ``YYYY-MM-DD``.

    Inverse of :func:`_parse_expiration_date`. Zero-pads month and
    day components if shorter than expected. Produces the exact
    10-character format stored in the ``Card.expiration_date``
    column.

    Parameters
    ----------
    year : str
        4-character year (e.g. ``"2027"``).
    month : str
        2-character month (e.g. ``"12"``). Zero-padded if shorter.
    day : str
        2-character day (e.g. ``"31"``). Zero-padded if shorter;
        defaults to ``"01"`` if empty (matches COBOL behavior where
        the CARD-EXPIRY-DAY input may be omitted, and the
        application fills in the 1st of the month).

    Returns
    -------
    str
        The assembled 10-character date string ``YYYY-MM-DD``.

    Examples
    --------
    >>> _assemble_expiration_date("2027", "12", "31")
    '2027-12-31'
    >>> _assemble_expiration_date("2027", "1", "")
    '2027-01-01'
    >>> _assemble_expiration_date("2027", "12", "5")
    '2027-12-05'
    """
    # Pad year to 4 characters (left-pad with '0').
    year_padded: str = year.rjust(4, "0") if year else "0000"

    # Pad month to 2 characters (left-pad with '0').
    month_padded: str = month.rjust(2, "0") if month else "00"

    # Pad day to 2 characters; default to '01' when empty, matching
    # COBOL behavior where the CARD-EXPIRY-DAY field defaults to 1st
    # of the month for card-expiry use cases.
    day_padded: str
    if not day:
        day_padded = "01"
    else:
        day_padded = day.rjust(2, "0")

    return f"{year_padded}-{month_padded}-{day_padded}"


def _build_detail_not_found_response(card_num: str) -> CardDetailResponse:
    """Construct a ``CardDetailResponse`` for the not-found case.

    All business fields are populated with empty strings (the schema
    requires them to be non-None for Pydantic validation). The
    ``card_number`` field echoes the input for client-side
    correlation with the original request — matching the COBOL
    pattern of echoing the searched CARD-NUM back on the screen.

    Parameters
    ----------
    card_num : str
        The card number that was searched for (echoed back to the
        client).

    Returns
    -------
    CardDetailResponse
        A response with the ``error_message`` field populated with
        the COBOL ``DID-NOT-FIND-ACCTCARD-COMBO`` text.
    """
    return CardDetailResponse(
        account_id="",
        card_number=card_num,
        embossed_name="",
        status_code="",
        expiry_month="",
        expiry_year="",
        info_message=None,
        error_message=_MSG_DETAIL_NOT_FOUND,
    )


def _build_update_error_response(
    request: CardUpdateRequest,
    error_message: str,
) -> CardUpdateResponse:
    """Construct a ``CardUpdateResponse`` carrying an error message.

    Echoes the ATTEMPTED update values back to the client so the
    user can see what they tried to submit — matching the COBOL
    pattern of redisplaying the input fields on the CCUP error
    screen (rather than blanking them out and forcing re-entry).

    The ``expiration_date`` is assembled from the request's
    expiry_year / expiry_month / expiry_day components before being
    decomposed back into ``expiry_year`` / ``expiry_month`` for the
    response — this round-trip normalizes edge cases like single-
    digit month/day input without requiring duplicated parsing
    logic.

    Parameters
    ----------
    request : CardUpdateRequest
        The original update request, whose values will be echoed
        back in the response body.
    error_message : str
        The error message to include in the response. Must be <= 80
        characters to pass the CardUpdateResponse schema validator.

    Returns
    -------
    CardUpdateResponse
        A response with echoed fields and the supplied error message.
    """
    # Normalize the date fields through assemble+parse so the
    # response uses the canonical 4-char year + 2-char month format,
    # regardless of whether the client sent zero-padded or short
    # values. This matches the COBOL CCUP map, which right-pads
    # numeric fields to their fixed BMS widths.
    assembled_date: str = _assemble_expiration_date(
        year=request.expiry_year,
        month=request.expiry_month,
        day=request.expiry_day,
    )
    expiry_year, expiry_month = _parse_expiration_date(assembled_date)

    return CardUpdateResponse(
        account_id=request.account_id,
        card_number=request.card_number,
        embossed_name=request.embossed_name,
        status_code=request.status_code,
        expiry_month=expiry_month,
        expiry_year=expiry_year,
        info_message=None,
        error_message=error_message,
    )


async def _safe_rollback(
    db: AsyncSession,
    log_context: dict[str, object],
) -> None:
    """Roll back the async session, swallowing any secondary errors.

    Best-effort session rollback for the card-update error paths. If
    the rollback itself fails (rare — usually indicates connection
    death), we log the secondary failure but DO NOT re-raise,
    because we're already on an error path and re-raising would
    mask the original failure from the caller.

    Parameters
    ----------
    db : AsyncSession
        The session to roll back.
    log_context : dict[str, object]
        Structured log context (``card_num``, ``operation``, etc.)
        to attach to any secondary-failure log record.
    """
    try:
        await db.rollback()
    except Exception:  # noqa: BLE001 — best-effort cleanup; never mask primary error
        logger.exception(
            "Session rollback failed during card-update error recovery",
            extra=log_context,
        )


__all__: list[str] = ["CardService"]
