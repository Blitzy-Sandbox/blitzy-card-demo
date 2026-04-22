# ============================================================================
# Source: app/cbl/COACTVWC.cbl (Account View     — Feature F-004, ~600 lines)
#         app/cbl/COCRDLIC.cbl (Card List        — Feature F-006, ~500 lines)
#         app/cbl/COCRDSLC.cbl (Card Detail View — Feature F-007, ~400 lines)
#         app/cbl/COTRN00C.cbl (Transaction List — Feature F-009, ~500 lines)
#         app/cbl/COTRN01C.cbl (Transaction Detail — Feature F-010, ~400 lines)
#         app/cbl/COUSR00C.cbl (User List        — Feature F-018, ~400 lines)
#         — Mainframe-to-Cloud migration
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
"""GraphQL query (read-side) resolvers.

Converted from six read-oriented online CICS COBOL programs:

* ``COACTVWC.cbl`` (Account View,       F-004) — :py:meth:`Query.account`
* ``COCRDLIC.cbl`` (Card List,          F-006) — :py:meth:`Query.cards`
* ``COCRDSLC.cbl`` (Card Detail View,   F-007) — :py:meth:`Query.card`
* ``COTRN00C.cbl`` (Transaction List,   F-009) — :py:meth:`Query.transactions`
* ``COTRN01C.cbl`` (Transaction Detail, F-010) — :py:meth:`Query.transaction`
* ``COUSR00C.cbl`` (User List,          F-018) — :py:meth:`Query.users` /
                                                 :py:meth:`Query.user`

The resolvers map the following CICS patterns to SQLAlchemy operations:

* ``EXEC CICS READ FILE(...)``          →  ``SELECT ... WHERE <pk> = :id``
                                           returning a single ORM row.
* ``EXEC CICS STARTBR FILE(...) GTEQ``  →  ``SELECT ... ORDER BY <key>
                                           LIMIT N OFFSET (page-1)*N``.
* ``EXEC CICS READNEXT``                →  implicit iteration over the
                                           bounded SELECT result set.
* ``EXEC CICS ENDBR``                   →  no-op at the SQL layer.

Design Notes
------------
* **Direct ORM access** — Unlike the mutation resolvers in
  :mod:`src.api.graphql.mutations`, which go through the service layer
  to preserve dual-write / rollback / optimistic-concurrency semantics,
  the read-side resolvers here execute SELECT statements directly via
  SQLAlchemy ``AsyncSession`` and hand the resulting ORM rows to the
  corresponding :py:meth:`~AccountType.from_model` factories. This is
  deliberate — GraphQL types expect an ORM row as input (see the
  ``from_model`` factories on ``AccountType``, ``CardType``,
  ``TransactionType`` and ``UserType``), whereas the service layer
  emits Pydantic response DTOs tuned for the REST surface. Re-using
  the REST DTOs here would require field-by-field re-mapping with no
  functional benefit.

* **Return semantics** — Single-item resolvers return
  :class:`typing.Optional[<Type>]` — ``None`` for "not found" rather
  than raising. This matches the GraphQL idiom where a null result
  with no ``errors`` field is the normal signal for "record does not
  exist" (in contrast, raising would populate ``errors`` and suggest
  a protocol-level failure). List resolvers always return a list,
  possibly empty, matching the COBOL screen behavior where empty
  browse-mode screens show empty rows but no error.

* **Pagination** — Fixed page sizes match the BMS mapset row counts:
  7 cards per page (``WS-MAX-SCREEN-LINES`` in COCRDLIC.cbl, echoed
  by COCRDLI.bms), 10 transactions per page (OCCURS 10 in
  COTRN00.bms), 10 users per page (OCCURS 10 in COUSR00.bms).
  Clients pass ``page`` (1-indexed) and optionally ``page_size``
  where the schema permits it. SQL pagination uses ``LIMIT`` +
  ``OFFSET``.

* **Financial precision** — GraphQL types already require
  :class:`decimal.Decimal` for every monetary field. SQLAlchemy's
  PostgreSQL ``NUMERIC(15, 2)`` columns are materialized directly
  as ``Decimal`` by ``asyncpg`` / ``psycopg2``, so the query
  resolvers never perform any monetary conversion — the AAP §0.7.2
  "no floating-point arithmetic" contract is preserved end-to-end.

* **Password exclusion** — The :py:meth:`~UserType.from_model`
  factory *never* reads ``user.password``, so even though the
  resolver SELECTs the full :class:`~src.shared.models.user_security.UserSecurity`
  row, the BCrypt hash is guaranteed not to cross the GraphQL
  boundary. This is a defence-in-depth guarantee documented in
  :mod:`src.api.graphql.types.user_type`.

* **Authorization** — JWT authentication is enforced by the
  :class:`~src.api.middleware.auth.JWTAuthMiddleware` *before* any
  resolver on this module runs. The ``/graphql`` path is not in the
  PUBLIC_PATHS allow-list, so the middleware rejects missing or
  invalid tokens with HTTP 401 before Strawberry is invoked. As a
  result, the resolvers here may assume the caller is authenticated
  and focus on business logic only. Field-level authorization (e.g.,
  hiding sensitive fields for non-admins) is not required by the
  COBOL programs being converted and is therefore not implemented
  here either.

Source: ``app/cbl/COACTVWC.cbl``, ``app/cbl/COCRDLIC.cbl``,
``app/cbl/COCRDSLC.cbl``, ``app/cbl/COTRN00C.cbl``,
``app/cbl/COTRN01C.cbl``, ``app/cbl/COUSR00C.cbl``, and their
associated data copybooks (``app/cpy/CVACT01Y.cpy``,
``app/cpy/CVACT02Y.cpy``, ``app/cpy/CVTRA05Y.cpy``,
``app/cpy/CSUSR01Y.cpy``) and BMS symbolic maps
(``app/cpy-bms/COACTVW.CPY``, ``app/cpy-bms/COCRDLI.CPY``,
``app/cpy-bms/COCRDSL.CPY``, ``app/cpy-bms/COTRN00.CPY``,
``app/cpy-bms/COTRN01.CPY``, ``app/cpy-bms/COUSR00.CPY``)
— Mainframe-to-Cloud migration (AAP §0.5.1).
"""

from __future__ import annotations

import logging
from typing import Optional

import strawberry
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from strawberry.types import Info

from src.api.graphql.types.account_type import AccountType
from src.api.graphql.types.card_type import CardType
from src.api.graphql.types.transaction_type import TransactionType
from src.api.graphql.types.user_type import UserType
from src.shared.models.account import Account
from src.shared.models.card import Card
from src.shared.models.transaction import Transaction
from src.shared.models.user_security import UserSecurity

# ----------------------------------------------------------------------------
# Module-level logger
#
# The CardDemo application uses structured JSON logging per AAP §0.7.2
# Monitoring Requirements; the top-level FastAPI application configures
# the root handler and its JSON formatter. Named loggers propagate to
# the root handler automatically — GraphQL queries therefore emit
# structured events visible in CloudWatch alongside the REST router and
# service-layer logs.
# ----------------------------------------------------------------------------
logger: logging.Logger = logging.getLogger(__name__)


# ============================================================================
# Module-private constants
# ============================================================================
# Page-size constants track the BMS screen geometry that the CICS
# programs present to the 3270 user. Preserving these sizes in the
# GraphQL contract makes the migration byte-for-byte behaviorally
# equivalent for any client that mirrors the COBOL screen layout.
# ----------------------------------------------------------------------------

# COBOL WS-MAX-SCREEN-LINES in COCRDLIC.cbl (line ~70) — 7 card rows
# per page on COCRDLI.bms.
_CARD_PAGE_SIZE: int = 7

# COBOL OCCURS 10 TIMES on COTRN00.bms — 10 transaction rows per page.
_TRANSACTION_PAGE_SIZE_DEFAULT: int = 10

# COBOL OCCURS 10 TIMES on COUSR00.bms — 10 user rows per page.
_USER_PAGE_SIZE_DEFAULT: int = 10

# Hard upper bound for page_size to defend against pathologically-large
# pagination requests that could degrade database performance. Matches
# the SQL defensive pattern applied by the REST services.
_MAX_PAGE_SIZE: int = 100


# ============================================================================
# Module-private helper functions
# ============================================================================
# _get_session is duplicated from src.api.graphql.mutations rather than
# imported so this module has no dependency on the mutations module and
# can be imported independently (e.g., by tests that do not want to
# pull in the full write-side resolvers). The function body is the
# SAME as in mutations.py by design — both resolver modules need the
# same session-extraction guarantees from the Strawberry context.
# ============================================================================


def _get_session(info: Info) -> AsyncSession:
    """Extract the async SQLAlchemy session from Strawberry's ``Info`` context.

    Every resolver receives a :class:`~strawberry.types.Info` object
    whose ``context`` attribute is the dict supplied by the FastAPI
    adapter's ``context_getter`` callback (see :mod:`src.api.main` for
    where the adapter is wired). By convention established in the
    GraphQL package (``src/api/graphql/__init__.py``) the FastAPI
    adapter places the active :class:`AsyncSession` under the ``"db"``
    key of the context dict — this session was obtained via
    :func:`src.api.database.get_async_session` and will be rolled
    back on exception, matching the CICS SYNCPOINT semantics from
    the original COBOL programs.

    Parameters
    ----------
    info : Info
        The Strawberry resolver context object passed as the first
        argument to every resolver.

    Returns
    -------
    AsyncSession
        The request-scoped async SQLAlchemy session.

    Raises
    ------
    RuntimeError
        If ``info.context`` does not contain a ``"db"`` key or the
        value is not an :class:`AsyncSession`. This indicates a
        mis-configured FastAPI + Strawberry integration and should
        never occur in production; surfacing a clear error here is
        preferable to silently failing inside a resolver.
    """
    context: object = info.context
    if not isinstance(context, dict):
        raise RuntimeError(
            "GraphQL Info.context is expected to be a dict supplied by "
            "the FastAPI + Strawberry integration (see src/api/main.py). "
            f"Got: {type(context).__name__}."
        )
    session = context.get("db")
    if not isinstance(session, AsyncSession):
        raise RuntimeError(
            "GraphQL Info.context['db'] is expected to be an "
            "sqlalchemy.ext.asyncio.AsyncSession (supplied by the "
            "FastAPI dependency chain via get_async_session). "
            f"Got: {type(session).__name__}."
        )
    return session


def _clamp_page(page: int) -> int:
    """Clamp a 1-indexed page number to the valid range (>= 1).

    GraphQL integer inputs are signed 32-bit by default, so a negative
    page value can reach the resolver. SQL ``OFFSET`` with a negative
    integer raises an error in PostgreSQL; rather than surface the
    DB-level error, we silently treat any page <= 0 as page 1 (the
    first page). This matches the BMS screen behavior where the
    "page number" spinbox has no notion of a negative page.
    """
    return page if page >= 1 else 1


def _clamp_page_size(page_size: int, default: int) -> int:
    """Clamp a caller-supplied page_size to ``[1, _MAX_PAGE_SIZE]``.

    Applies the same guardrails as the REST services: reject zero and
    negative values (fall back to the default), and cap at the module
    constant :data:`_MAX_PAGE_SIZE` to prevent runaway queries.
    """
    if page_size <= 0:
        return default
    if page_size > _MAX_PAGE_SIZE:
        return _MAX_PAGE_SIZE
    return page_size


# ============================================================================
# Root Query type
# ============================================================================
# The :class:`Query` class below is the root read-side GraphQL type.
# It is consumed by :mod:`src.api.graphql.schema` where it is combined
# with the :class:`~src.api.graphql.mutations.Mutation` class into a
# single :class:`strawberry.Schema`, which is in turn mounted at
# ``/graphql`` by :mod:`src.api.main`.
#
# Every resolver method:
#
# * Is declared ``async`` so it can ``await`` the async SQLAlchemy
#   session operations without blocking the FastAPI event loop.
# * Accepts ``info: Info`` as its first non-``self`` argument — the
#   Strawberry convention for resolvers that need access to the
#   FastAPI request context.
# * Pulls the :class:`AsyncSession` from ``info`` via
#   :func:`_get_session`.
# * Returns either an ``Optional[<Type>]`` (single-item) or
#   ``list[<Type>]`` (paginated list) of the corresponding GraphQL
#   type.
# * Logs its invocation at ``DEBUG`` and any anomalies at ``WARNING``
#   via the module logger, so CloudWatch Logs Insights can correlate
#   GraphQL traffic with REST traffic on the same request path.
# ============================================================================


@strawberry.type
class Query:
    """Root read-side GraphQL type for the CardDemo API.

    Provides one resolver field per read-oriented online CICS COBOL
    program converted for F-004, F-006, F-007, F-009, F-010, F-018:

    * :py:meth:`account`       — COACTVWC.cbl    — single account by ID.
    * :py:meth:`card`          — COCRDSLC.cbl    — single card by number.
    * :py:meth:`cards`         — COCRDLIC.cbl    — paginated card list.
    * :py:meth:`transaction`   — COTRN01C.cbl    — single transaction.
    * :py:meth:`transactions`  — COTRN00C.cbl    — paginated tx list.
    * :py:meth:`user`          — COUSR00C.cbl    — single user by ID.
    * :py:meth:`users`         — COUSR00C.cbl    — paginated user list.

    The class is deliberately ``@strawberry.type``-decorated (not
    ``@strawberry.input`` or ``@strawberry.interface``) so Strawberry
    registers it as a GraphQL object type with no automatic
    reflection on private attributes. Each ``@strawberry.field``
    decoration below exposes exactly one resolver to the GraphQL
    schema — methods without that decorator are not visible to
    clients.
    """

    # ------------------------------------------------------------------
    # account — COACTVWC.cbl → single account lookup (Feature F-004)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "Fetch a single account by its 11-digit account ID. "
            "Maps to COACTVWC.cbl (Feature F-004). Returns null when "
            "no account matches the supplied ID — GraphQL callers "
            "should treat a null data.account as 'not found'."
        ),
    )
    async def account(
        self,
        info: Info,
        acct_id: str,
    ) -> Optional[AccountType]:  # noqa: UP045  — Strawberry requires typing.Optional
        """Resolve the ``account(acct_id: String!)`` query field.

        Issues a single primary-key ``SELECT`` against the
        ``accounts`` table. If a row is found, it is converted to
        an :class:`AccountType` via
        :py:meth:`AccountType.from_model` (which exposes exactly
        12 fields and omits the server-side ``version_id``). If no
        row matches, ``None`` is returned.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async
            SQLAlchemy session under ``context["db"]``.
        acct_id : str
            The 11-digit, zero-padded account identifier (COBOL
            ``PIC 9(11)`` → Python ``str`` to preserve leading
            zeros). Leading/trailing whitespace is stripped before
            the lookup to match the COBOL ``INSPECT`` normalization
            in COACTVWC.cbl.

        Returns
        -------
        Optional[AccountType]
            The resolved GraphQL account type, or ``None`` if no
            account matches.
        """
        session: AsyncSession = _get_session(info)
        normalized_id: str = acct_id.strip() if acct_id else ""

        logger.debug(
            "account query",
            extra={"operation": "query_account", "acct_id": normalized_id},
        )

        if not normalized_id:
            # Empty / whitespace-only acct_id — COBOL treats this as
            # "blank input" which is a validation failure (not a
            # not-found). At the GraphQL layer we surface it as an
            # error so the caller sees a clear diagnostic rather
            # than a null masquerading as "not found".
            raise Exception("acct_id is required")

        stmt = select(Account).where(Account.acct_id == normalized_id)
        result = await session.execute(stmt)
        account_orm: Optional[Account] = result.scalar_one_or_none()  # noqa: UP045

        if account_orm is None:
            logger.info(
                "account query: not found",
                extra={"operation": "query_account", "acct_id": normalized_id},
            )
            return None

        return AccountType.from_model(account_orm)

    # ------------------------------------------------------------------
    # card — COCRDSLC.cbl → single card lookup (Feature F-007)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]
        description=(
            "Fetch a single card by its 16-digit card number. "
            "Maps to COCRDSLC.cbl (Feature F-007). Returns null "
            "when no card matches the supplied number."
        ),
    )
    async def card(
        self,
        info: Info,
        card_num: str,
    ) -> Optional[CardType]:  # noqa: UP045
        """Resolve the ``card(card_num: String!)`` query field.

        Issues a single primary-key ``SELECT`` against the
        ``cards`` table. If a row is found, it is converted to a
        :class:`CardType` via :py:meth:`CardType.from_model`
        (which exposes exactly 6 fields and omits the server-side
        ``version_id``). If no row matches, ``None`` is returned.

        Parameters
        ----------
        info : Info
            Strawberry resolver context.
        card_num : str
            The 16-digit card number (COBOL ``PIC X(16)`` →
            Python ``str``). Whitespace is stripped before the
            lookup to match COBOL input normalization.

        Returns
        -------
        Optional[CardType]
            The resolved GraphQL card type, or ``None`` if no
            card matches.
        """
        session: AsyncSession = _get_session(info)
        normalized_num: str = card_num.strip() if card_num else ""

        logger.debug(
            "card query",
            extra={"operation": "query_card", "card_num": normalized_num},
        )

        if not normalized_num:
            raise Exception("card_num is required")

        stmt = select(Card).where(Card.card_num == normalized_num)
        result = await session.execute(stmt)
        card_orm: Optional[Card] = result.scalar_one_or_none()  # noqa: UP045

        if card_orm is None:
            logger.info(
                "card query: not found",
                extra={"operation": "query_card", "card_num": normalized_num},
            )
            return None

        return CardType.from_model(card_orm)

    # ------------------------------------------------------------------
    # cards — COCRDLIC.cbl → paginated card list (Feature F-006)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]
        description=(
            "List cards, optionally filtered by account_id and/or "
            "card_num, with pagination of 7 rows per page. Maps to "
            "COCRDLIC.cbl (Feature F-006). The page size is fixed "
            "at 7 rows to match the BMS screen geometry "
            "(WS-MAX-SCREEN-LINES in COCRDLIC.cbl)."
        ),
    )
    async def cards(
        self,
        info: Info,
        account_id: Optional[str] = None,  # noqa: UP045
        card_num: Optional[str] = None,  # noqa: UP045
        page: int = 1,
    ) -> list[CardType]:
        """Resolve the ``cards(account_id, card_num, page)`` query field.

        Matches the COBOL ``2100-PROCESS-ENTER-KEY`` paragraph in
        COCRDLIC.cbl which orchestrates the browse-mode card lookup.
        Two optional filters are available:

        * ``account_id`` — restrict the result set to cards that
          belong to the given 11-digit account.
        * ``card_num`` — restrict the result set to a specific
          16-digit card. Combined with ``account_id``, both
          predicates are AND-ed.

        Results are ordered by ``card_num`` ASC and paginated at
        7 rows per page (matching ``WS-MAX-SCREEN-LINES``).
        """
        session: AsyncSession = _get_session(info)
        page_clamped: int = _clamp_page(page)
        offset: int = (page_clamped - 1) * _CARD_PAGE_SIZE

        logger.debug(
            "cards query",
            extra={
                "operation": "query_cards",
                "account_id": account_id,
                "card_num": card_num,
                "page": page_clamped,
                "page_size": _CARD_PAGE_SIZE,
            },
        )

        stmt = select(Card).order_by(Card.card_num).limit(_CARD_PAGE_SIZE).offset(offset)

        if account_id is not None and account_id.strip():
            stmt = stmt.where(Card.acct_id == account_id.strip())
        if card_num is not None and card_num.strip():
            stmt = stmt.where(Card.card_num == card_num.strip())

        result = await session.execute(stmt)
        rows: list[Card] = list(result.scalars().all())

        return [CardType.from_model(row) for row in rows]

    # ------------------------------------------------------------------
    # transaction — COTRN01C.cbl → single transaction (Feature F-010)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]
        description=(
            "Fetch a single transaction by its 16-character "
            "transaction ID. Maps to COTRN01C.cbl (Feature F-010). "
            "Returns null when no transaction matches."
        ),
    )
    async def transaction(
        self,
        info: Info,
        tran_id: str,
    ) -> Optional[TransactionType]:  # noqa: UP045
        """Resolve the ``transaction(tran_id: String!)`` query field.

        Issues a single primary-key ``SELECT`` against the
        ``transactions`` table. If a row is found, it is converted
        to a :class:`TransactionType` via
        :py:meth:`TransactionType.from_model` (which exposes exactly
        13 fields and omits the COBOL FILLER padding). If no row
        matches, ``None`` is returned.

        Parameters
        ----------
        info : Info
            Strawberry resolver context.
        tran_id : str
            The 16-character transaction identifier (COBOL
            ``PIC X(16)`` → Python ``str``).

        Returns
        -------
        Optional[TransactionType]
            The resolved GraphQL transaction type, or ``None`` if
            no transaction matches.
        """
        session: AsyncSession = _get_session(info)
        normalized_id: str = tran_id.strip() if tran_id else ""

        logger.debug(
            "transaction query",
            extra={"operation": "query_transaction", "tran_id": normalized_id},
        )

        if not normalized_id:
            raise Exception("tran_id is required")

        stmt = select(Transaction).where(Transaction.tran_id == normalized_id)
        result = await session.execute(stmt)
        tran_orm: Optional[Transaction] = result.scalar_one_or_none()  # noqa: UP045

        if tran_orm is None:
            logger.info(
                "transaction query: not found",
                extra={"operation": "query_transaction", "tran_id": normalized_id},
            )
            return None

        return TransactionType.from_model(tran_orm)

    # ------------------------------------------------------------------
    # transactions — COTRN00C.cbl → paginated tx list (Feature F-009)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]
        description=(
            "List transactions, optionally filtered by tran_id "
            "prefix, with pagination. Maps to COTRN00C.cbl "
            "(Feature F-009). Default page size 10 to match the "
            "OCCURS 10 TIMES rows on COTRN00.bms."
        ),
    )
    async def transactions(
        self,
        info: Info,
        tran_id: Optional[str] = None,  # noqa: UP045
        page: int = 1,
        page_size: int = _TRANSACTION_PAGE_SIZE_DEFAULT,
    ) -> list[TransactionType]:
        """Resolve the ``transactions(tran_id, page, page_size)`` field.

        Matches the COBOL ``PROCESS-ENTER-KEY`` / ``PROCESS-PF8``
        pages (COTRN00C.cbl lines ~146 and ~279). When ``tran_id``
        is supplied, it is used as a prefix filter (``LIKE
        'tran_id%'``) — functionally richer than COBOL's "jump to
        anchor" but behaviorally equivalent when the anchor is a
        unique value. Results are ordered by ``tran_id`` ASC for
        a deterministic, stable cursor.
        """
        session: AsyncSession = _get_session(info)
        page_clamped: int = _clamp_page(page)
        page_size_clamped: int = _clamp_page_size(
            page_size,
            _TRANSACTION_PAGE_SIZE_DEFAULT,
        )
        offset: int = (page_clamped - 1) * page_size_clamped

        logger.debug(
            "transactions query",
            extra={
                "operation": "query_transactions",
                "tran_id_prefix": tran_id,
                "page": page_clamped,
                "page_size": page_size_clamped,
            },
        )

        stmt = select(Transaction).order_by(Transaction.tran_id).limit(page_size_clamped).offset(offset)

        if tran_id is not None and tran_id.strip():
            stripped: str = tran_id.strip()
            # Escape SQL LIKE wildcards so a user-supplied prefix
            # can't silently widen the filter. Using backslash as
            # the escape char to match PostgreSQL default.
            escaped: str = stripped.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            stmt = stmt.where(Transaction.tran_id.like(f"{escaped}%", escape="\\"))

        result = await session.execute(stmt)
        rows: list[Transaction] = list(result.scalars().all())

        return [TransactionType.from_model(row) for row in rows]

    # ------------------------------------------------------------------
    # user — derived from COUSR00C.cbl → single user by ID
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]
        description=(
            "Fetch a single user by user_id. Derived from "
            "COUSR00C.cbl (Feature F-018). The password field is "
            "never exposed via GraphQL. Returns null when no user "
            "matches."
        ),
    )
    async def user(
        self,
        info: Info,
        user_id: str,
    ) -> Optional[UserType]:  # noqa: UP045
        """Resolve the ``user(user_id: String!)`` query field.

        Issues a single primary-key ``SELECT`` against the
        ``user_security`` table. If a row is found, it is converted
        to a :class:`UserType` via :py:meth:`UserType.from_model`
        (which exposes exactly 4 fields and DELIBERATELY OMITS the
        BCrypt password hash). If no row matches, ``None`` is
        returned.

        Parameters
        ----------
        info : Info
            Strawberry resolver context.
        user_id : str
            The 8-character user identifier (COBOL ``PIC X(08)``
            → Python ``str``).

        Returns
        -------
        Optional[UserType]
            The resolved GraphQL user type (password excluded), or
            ``None`` if no user matches.
        """
        session: AsyncSession = _get_session(info)
        normalized_id: str = user_id.strip() if user_id else ""

        logger.debug(
            "user query",
            extra={"operation": "query_user", "user_id": normalized_id},
        )

        if not normalized_id:
            raise Exception("user_id is required")

        stmt = select(UserSecurity).where(UserSecurity.user_id == normalized_id)
        result = await session.execute(stmt)
        user_orm: Optional[UserSecurity] = result.scalar_one_or_none()  # noqa: UP045

        if user_orm is None:
            logger.info(
                "user query: not found",
                extra={"operation": "query_user", "user_id": normalized_id},
            )
            return None

        return UserType.from_model(user_orm)

    # ------------------------------------------------------------------
    # users — COUSR00C.cbl → paginated user list (Feature F-018)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]
        description=(
            "List users, optionally filtered by user_id prefix, "
            "with pagination. Maps to COUSR00C.cbl (Feature F-018). "
            "Default page size 10 matches the OCCURS 10 rows on "
            "COUSR00.bms. Password fields are NEVER exposed."
        ),
    )
    async def users(
        self,
        info: Info,
        user_id: Optional[str] = None,  # noqa: UP045
        page: int = 1,
        page_size: int = _USER_PAGE_SIZE_DEFAULT,
    ) -> list[UserType]:
        """Resolve the ``users(user_id, page, page_size)`` query field.

        Matches the COBOL user browse in COUSR00C.cbl. Optional
        ``user_id`` is used as a prefix filter (``LIKE
        'user_id%'``). Results are ordered by ``user_id`` ASC and
        paginated at the caller-supplied ``page_size`` (default 10,
        capped at 100 to prevent runaway queries).
        """
        session: AsyncSession = _get_session(info)
        page_clamped: int = _clamp_page(page)
        page_size_clamped: int = _clamp_page_size(
            page_size,
            _USER_PAGE_SIZE_DEFAULT,
        )
        offset: int = (page_clamped - 1) * page_size_clamped

        logger.debug(
            "users query",
            extra={
                "operation": "query_users",
                "user_id_prefix": user_id,
                "page": page_clamped,
                "page_size": page_size_clamped,
            },
        )

        stmt = select(UserSecurity).order_by(UserSecurity.user_id).limit(page_size_clamped).offset(offset)

        if user_id is not None and user_id.strip():
            stripped: str = user_id.strip()
            # Escape SQL LIKE wildcards (same pattern as
            # transactions query above). Backslash matches the
            # PostgreSQL LIKE escape default.
            escaped: str = stripped.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            stmt = stmt.where(
                UserSecurity.user_id.like(f"{escaped}%", escape="\\"),
            )

        result = await session.execute(stmt)
        rows: list[UserSecurity] = list(result.scalars().all())

        return [UserType.from_model(row) for row in rows]


# ----------------------------------------------------------------------------
# Public exports
# ----------------------------------------------------------------------------
# Only the :class:`Query` class is exported from this module. The
# private helpers (``_get_session``, ``_clamp_page``, ``_clamp_page_size``)
# and module constants are intentionally not re-exported to keep the
# public surface minimal and discourage cross-module coupling.
# ----------------------------------------------------------------------------
__all__ = ["Query"]
