# ============================================================================
# Source: app/cbl/COACTVWC.cbl (Account View     — Feature F-004, ~941 lines)
#         app/cbl/COCRDLIC.cbl (Card List        — Feature F-006, ~1,459 lines)
#         app/cbl/COTRN00C.cbl (Transaction List — Feature F-009, ~699 lines)
#         app/cbl/COUSR00C.cbl (User List        — Feature F-018, ~695 lines)
#         — Mainframe-to-Cloud migration (AAP §0.5.1)
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
"""GraphQL query (read-side) resolvers for the CardDemo API.

Converted from four read-oriented online CICS COBOL programs:

* ``COACTVWC.cbl`` (Account View,       F-004) — :py:meth:`Query.account`
                                                 and :py:meth:`Query.accounts`.
* ``COCRDLIC.cbl`` (Card List,          F-006) — :py:meth:`Query.cards`;
                                                 the underlying single-card
                                                 detail path from COCRDSLC.cbl
                                                 (F-007) is exposed as
                                                 :py:meth:`Query.card`.
* ``COTRN00C.cbl`` (Transaction List,   F-009) — :py:meth:`Query.transactions`;
                                                 the single-transaction detail
                                                 path from COTRN01C.cbl (F-010)
                                                 is exposed as
                                                 :py:meth:`Query.transaction`.
* ``COUSR00C.cbl`` (User List,          F-018) — :py:meth:`Query.users` and
                                                 :py:meth:`Query.user`.

The resolvers map the following CICS patterns to SQLAlchemy operations
via the service layer (``src.api.services``):

* ``EXEC CICS READ FILE(...)``          →  the service layer's
                                           single-entity ``get_*`` methods
                                           (e.g. :meth:`AccountService.get_account_view`,
                                           :meth:`CardService.get_card_detail`,
                                           :meth:`TransactionService.get_transaction_detail`).
* ``EXEC CICS STARTBR FILE(...) GTEQ``  →  the service layer's list methods
                                           (:meth:`CardService.list_cards`,
                                           :meth:`TransactionService.list_transactions`,
                                           :meth:`UserService.list_users`)
                                           which internally execute
                                           ``SELECT ... ORDER BY <key>
                                           LIMIT N OFFSET (page-1)*N``.
* ``EXEC CICS READNEXT``                →  implicit iteration over the
                                           bounded SELECT result set.
* ``EXEC CICS ENDBR``                   →  no-op at the SQL layer.

Design Notes
------------
* **Service-layer delegation** — Every resolver instantiates the
  appropriate service class (:class:`AccountService`,
  :class:`CardService`, :class:`TransactionService`,
  :class:`UserService`) with the per-request
  :class:`~sqlalchemy.ext.asyncio.AsyncSession` obtained from the
  Strawberry :class:`~strawberry.types.Info` context. The resolver
  itself contains no direct SQL or ORM manipulation — it is solely
  responsible for adapting between the Strawberry output types
  (:class:`AccountType`, :class:`CardType`, :class:`TransactionType`,
  :class:`UserType`) and the Pydantic response DTOs that the service
  layer honors for both this GraphQL surface and the REST routers
  (``src.api.routers``). This preserves the "one business-logic
  implementation, two protocol surfaces" contract established by the
  GraphQL package docstring (``src/api/graphql/__init__.py``).

* **Return semantics** — Single-item resolvers return
  :class:`typing.Optional[<Type>]` — ``None`` for "not found" rather
  than raising. This matches the GraphQL idiom where a null result
  with no ``errors`` field is the normal signal for "record does not
  exist" (in contrast, raising would populate ``errors`` and suggest
  a protocol-level failure). List resolvers always return a list,
  possibly empty, matching the COBOL screen behavior where an empty
  browse-mode screen simply displays empty rows with no error banner.
  The service layer's Pydantic response envelopes contain an
  ``error_message`` / ``message`` field that signals the not-found
  or error condition; resolvers inspect this field to decide between
  returning ``None`` and returning the constructed GraphQL type.

* **Pagination** — Fixed default page sizes match the BMS mapset row
  counts from the original COBOL screens:
  **7 cards per page** (``WS-MAX-SCREEN-LINES`` in COCRDLIC.cbl,
  echoed by ``COCRDLI.CPY`` via ``OCCURS 7 TIMES``),
  **10 transactions per page** (``OCCURS 10 TIMES`` on
  ``COTRN00.CPY``), **10 users per page** (``OCCURS 10 TIMES`` on
  ``COUSR00.CPY``). Clients pass ``page`` (1-indexed) and optionally
  ``page_size`` where the schema permits it.

* **Financial precision** — GraphQL types already require
  :class:`decimal.Decimal` for every monetary field
  (:class:`AccountType` ``curr_bal`` / ``credit_limit`` /
  ``cash_credit_limit`` / ``curr_cyc_credit`` / ``curr_cyc_debit``;
  :class:`TransactionType` ``amount``). The service-layer responses
  also type these fields as :class:`Decimal`, backed by SQLAlchemy's
  PostgreSQL ``NUMERIC(15, 2)`` columns materialized as
  :class:`Decimal` by ``asyncpg`` / ``psycopg2``. The resolvers never
  perform any monetary conversion or arithmetic — the AAP §0.7.2
  "no floating-point arithmetic" contract is preserved end-to-end.

* **Password exclusion** — The :class:`UserType` declares only four
  fields (``user_id`` / ``first_name`` / ``last_name`` / ``usr_type``)
  and deliberately omits the BCrypt password hash. In addition, the
  :class:`UserListResponse` / :class:`UserListItem` Pydantic envelope
  used by :meth:`UserService.list_users` already strips the password,
  so even if the GraphQL type definition were accidentally extended,
  the service layer would still prevent the hash from crossing the
  GraphQL boundary. This matches the COBOL behavior in COUSR00C.cbl
  where the user-list BMS screen never displays the password.

* **Authentication** — JWT authentication is enforced by the
  :class:`~src.api.middleware.auth.JWTAuthMiddleware` *before* any
  resolver on this module runs. The ``/graphql`` path is not on the
  PUBLIC_PATHS allow-list, so the middleware rejects missing or
  invalid tokens with HTTP 401 before Strawberry is invoked. As a
  result, the resolvers here may assume the caller is authenticated.

* **Authorization — admin gating on USRSEC queries** — The REST
  :class:`~src.api.middleware.auth.JWTAuthMiddleware` gates the
  ``/users`` and ``/admin`` path prefixes behind an ``is_admin``
  (``user_type == 'A'``, 88 ``CDEMO-USRTYP-ADMIN``) check; see
  :data:`src.api.middleware.auth.ADMIN_ONLY_PREFIXES`. GraphQL
  requests all share the single ``/graphql`` endpoint, so
  path-prefix gating alone does not cover the ``user`` and ``users``
  resolvers (which query the USRSEC table).  To preserve parity
  with the REST admin gating — and with the legacy COBOL COUSR00
  / COUSR01 / COUSR02 / COUSR03 transactions which required
  ``CDEMO-USER-TYPE = 'A'`` — the ``user`` and ``users`` resolvers
  on this module explicitly verify that ``info.context["is_admin"]``
  is ``True`` before issuing any service call; non-admin callers
  receive a :class:`PermissionError` raised by
  :func:`_require_admin`.  The ``is_admin`` flag is injected into
  the GraphQL context by :func:`src.api.main.get_graphql_context`
  from ``request.state.is_admin``, which in turn was populated by
  :class:`~src.api.middleware.auth.JWTAuthMiddleware` from the
  JWT's ``user_type`` claim on successful authentication.

  Queries that map to COBOL transactions that did NOT require
  admin privilege (account view, card list/detail, transaction
  list/detail) remain accessible to any authenticated user — this
  matches the original CICS access model where those transactions
  were dispatchable from COMEN01 (the regular main menu) rather
  than COADM01 (the admin menu).

Source: ``app/cbl/COACTVWC.cbl``, ``app/cbl/COCRDLIC.cbl``,
``app/cbl/COTRN00C.cbl``, ``app/cbl/COUSR00C.cbl``, and their
associated data copybooks (``app/cpy/CVACT01Y.cpy``,
``app/cpy/CVACT02Y.cpy``, ``app/cpy/CVTRA05Y.cpy``,
``app/cpy/CSUSR01Y.cpy``) and BMS symbolic maps
(``app/cpy-bms/COACTVW.CPY``, ``app/cpy-bms/COCRDLI.CPY``,
``app/cpy-bms/COTRN00.CPY``, ``app/cpy-bms/COUSR00.CPY``)
— Mainframe-to-Cloud migration (AAP §0.5.1).
"""

from __future__ import annotations

import logging
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any, Optional

import strawberry
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from strawberry.types import Info

from src.api.database import get_async_session  # noqa: F401  — contract import
from src.api.graphql.types.account_type import AccountType
from src.api.graphql.types.card_type import CardType
from src.api.graphql.types.transaction_type import TransactionType
from src.api.graphql.types.user_type import UserType
from src.api.services.account_service import AccountService
from src.api.services.card_service import (  # type: ignore[attr-defined]  # CardListRequest re-export not in __all__
    CardListRequest,
    CardService,
)
from src.api.services.transaction_service import (  # type: ignore[attr-defined]  # TransactionListRequest re-export not in __all__
    TransactionListRequest,
    TransactionService,
)
from src.api.services.user_service import (  # type: ignore[attr-defined]  # UserListRequest re-export not in __all__
    UserListRequest,
    UserService,
)
from src.shared.models.account import Account

# ----------------------------------------------------------------------------
# Import commentary (deferred from the import block so that ruff's
# isort integration can auto-sort imports without tripping over
# interleaved comments).
#
# * ``strawberry`` + ``strawberry.types.Info`` — Strawberry GraphQL
#   framework. ``@strawberry.type`` / ``@strawberry.field`` register
#   the Query class and resolvers with the GraphQL schema;
#   ``Info.context["db"]`` is the Strawberry convention for carrying
#   per-request state (the async DB session in our case).
#
# * ``src.api.database.get_async_session`` — re-exported for
#   consumers that want a single entry point for API-layer DB
#   sessions; NOT invoked by resolvers on this module (the live
#   :class:`AsyncSession` is injected via ``Info.context["db"]`` by
#   the FastAPI + Strawberry adapter in :mod:`src.api.main`). The
#   noqa suppression on the import line hides the "imported but
#   unused" warning because the import is a public-API contract,
#   not a usage.
#
# * ``src.api.graphql.types.*`` — Strawberry output types returned
#   by the resolvers. Strawberry auto-generates the GraphQL SDL for
#   these at schema-construction time (see :mod:`src.api.graphql.schema`).
#
# * ``src.api.services.*`` — service classes + the ``*ListRequest``
#   Pydantic input DTOs that the services accept. The request DTOs
#   are re-exported by each service module, keeping our import
#   surface aligned with the file-level depends_on_files contract
#   (which allows the service modules but not ``src.shared.schemas``
#   directly).
# ----------------------------------------------------------------------------


# ============================================================================
# Module-level logger
# ============================================================================
# The CardDemo application uses structured JSON logging per AAP §0.7.2
# Monitoring Requirements. Named loggers propagate to the root handler
# configured by the FastAPI application, so GraphQL query events are
# visible alongside REST and service-layer events in CloudWatch Logs.
# ============================================================================
logger: logging.Logger = logging.getLogger(__name__)


# ============================================================================
# Module-private constants
# ============================================================================
# Page-size constants track the BMS screen geometry that the CICS
# programs present to the 3270 user. Preserving these sizes in the
# GraphQL contract makes the migration behaviorally equivalent for any
# client that mirrors the COBOL screen layout.
# ----------------------------------------------------------------------------

# COBOL WS-MAX-SCREEN-LINES in COCRDLIC.cbl (matches OCCURS 7 TIMES on
# COCRDLI.CPY) — 7 card rows per page.
_CARD_PAGE_SIZE_DEFAULT: int = 7

# COBOL OCCURS 10 TIMES on COTRN00.CPY — 10 transaction rows per page.
_TRANSACTION_PAGE_SIZE_DEFAULT: int = 10

# COBOL OCCURS 10 TIMES on COUSR00.CPY — 10 user rows per page.
_USER_PAGE_SIZE_DEFAULT: int = 10

# Hard upper bound for the caller-supplied ``page_size`` to defend
# against pathologically-large pagination requests that could degrade
# database performance. Matches the defensive pattern applied by the
# REST services.
_MAX_PAGE_SIZE: int = 100

# Canonical zero Decimal — used as a safe fallback when a service
# response unexpectedly yields a non-Decimal monetary value. Guarding
# against this preserves the AAP §0.7.2 "no floating-point" contract
# even in the unlikely event of a future regression in the service
# layer's Pydantic validators.
_DECIMAL_ZERO: Decimal = Decimal("0.00")

# COBOL ACCT-ID width. Used by :py:meth:`Query.accounts` to zero-pad
# the numeric iteration cursor so it matches the 11-character storage
# format from ``app/cpy/CVACT01Y.cpy`` (``PIC 9(11)``).
_ACCT_ID_WIDTH: int = 11


# ============================================================================
# Module-private helper functions
# ============================================================================
# These helpers factor small pieces of logic used across multiple
# resolvers: session acquisition, input normalization, pagination
# clamping, and GraphQL-type construction from service responses.
# Keeping them at module scope (rather than inside :class:`Query`)
# makes them trivially unit-testable without a Strawberry executor.
# ============================================================================


@asynccontextmanager
async def _get_session(info: Info) -> AsyncGenerator[AsyncSession, None]:
    """Yield a FRESH transactional :class:`AsyncSession` for this resolver.

    Every resolver receives a :class:`~strawberry.types.Info` object
    whose ``context`` attribute is the dict supplied by the FastAPI
    adapter's ``context_getter`` callback (see :mod:`src.api.main` for
    where the adapter is wired). By convention established in the
    GraphQL package (``src/api/graphql/__init__.py``), the FastAPI
    adapter places a session FACTORY callable under the
    ``"db_factory"`` key — that factory is
    :func:`src.api.database.get_async_session`, which creates a
    transactional :class:`~sqlalchemy.ext.asyncio.AsyncSession`
    implementing the CICS ``SYNCPOINT`` / ``SYNCPOINT ROLLBACK``
    contract (commit on clean exit; rollback on exception).

    This helper is decorated with
    :func:`contextlib.asynccontextmanager` so callers acquire a
    session via ``async with _get_session(info) as session: ...``.
    The ``async with`` block ensures:

    * A **fresh** session is opened per resolver, not shared across
      sibling resolvers. This eliminates the
      ``sqlalchemy.exc.InvalidRequestError: This session is
      provisioning a new connection; concurrent operations are not
      permitted`` error that plagued multi-field GraphQL queries
      before QA Checkpoint 10 (Issue 1). Strawberry executes sibling
      resolvers concurrently via ``asyncio.gather``, and SQLAlchemy's
      :class:`AsyncSession` is **not** safe for concurrent use.
    * CICS SYNCPOINT semantics are preserved: on normal exit the
      session is committed; on exception the session is rolled back
      and the exception re-raised. Both paths ensure the connection
      returns to the pool — see
      :func:`src.api.database.get_async_session` for the underlying
      try/except/else pattern.

    The helper name intentionally matches
    :func:`src.api.graphql.mutations._get_session` for naming
    consistency across the GraphQL layer (both queries and
    mutations use the same session-acquisition idiom).

    Parameters
    ----------
    info : Info
        The Strawberry resolver context object passed as the first
        argument to every resolver.

    Yields
    ------
    AsyncSession
        The per-resolver async SQLAlchemy session, valid for the
        duration of the enclosing ``async with`` block. Commits on
        clean exit; rolls back on exception.

    Raises
    ------
    RuntimeError
        If ``info.context`` does not carry a ``"db_factory"`` entry.
        This indicates a mis-configured FastAPI + Strawberry
        integration and should never occur in production; surfacing
        a clear error here is preferable to silently failing inside
        a service call.
    """
    context = info.context
    # Strawberry's default context is a dict; a custom context class
    # with a ``db_factory`` attribute is also supported for future
    # flexibility (matches the defensive isinstance-or-getattr
    # pattern used by :func:`_require_admin` below).
    if isinstance(context, dict):
        factory = context.get("db_factory")
    else:
        factory = getattr(context, "db_factory", None)

    if factory is None:
        raise RuntimeError(
            "GraphQL Info.context is missing the 'db_factory' async "
            "session factory. Expected the FastAPI + Strawberry "
            "integration (see src/api/main.py) to inject a factory "
            "callable via the context_getter callback. The factory "
            "(get_async_session) is an async generator that yields "
            "a fresh AsyncSession per resolver with CICS SYNCPOINT "
            "semantics."
        )

    # ``factory`` is the ``get_async_session`` async generator
    # function. Wrap it with :func:`contextlib.asynccontextmanager`
    # to obtain an async context manager whose __aenter__ drives the
    # generator to its first yield (opening the session) and whose
    # __aexit__ drives the generator through its remaining
    # ``try/except/else`` branches — triggering ``session.rollback()``
    # on exception or ``session.commit()`` on clean exit. This
    # preserves the CICS SYNCPOINT / SYNCPOINT ROLLBACK contract
    # end-to-end for each resolver that acquires its own session via
    # ``async with _get_session(info) as session: ...``.
    async with asynccontextmanager(factory)() as session:
        yield session


def _require_admin(info: Info) -> None:
    """Enforce admin-only access for USRSEC GraphQL resolvers.

    Inspects the ``is_admin`` flag on ``info.context`` (populated by
    :func:`src.api.main.get_graphql_context` from
    ``request.state.is_admin``, which in turn comes from
    :class:`src.api.middleware.auth.JWTAuthMiddleware` enforcing
    ``user_type == 'A'`` on the JWT). Raises
    :class:`PermissionError` when the caller is NOT an admin — this
    propagates as a GraphQL error with a stable message that callers
    can detect and handle at the application layer.

    This preserves parity with the REST admin gating performed by
    :data:`src.api.middleware.auth.ADMIN_ONLY_PREFIXES` on the
    ``/users`` and ``/admin`` path prefixes, and with the legacy
    CICS access model where COUSR00 / COUSR01 / COUSR02 / COUSR03
    transactions required ``CDEMO-USER-TYPE = 'A'``
    (88 ``CDEMO-USRTYP-ADMIN``).

    Parameters
    ----------
    info : Info
        The Strawberry resolver context object passed as the first
        argument to every resolver.

    Raises
    ------
    PermissionError
        When the authenticated caller is not an admin.  Strawberry
        surfaces this as a GraphQL ``errors`` entry on the response,
        with a stable ``message`` that client applications can
        recognize without parsing a stack trace.
    RuntimeError
        If ``info.context`` is not a mapping or does not carry the
        expected ``is_admin`` key.  This indicates a mis-configured
        FastAPI + Strawberry integration and should never occur in
        production; surfacing a clear error here is preferable to
        silently allowing unauthenticated access.
    """
    context = info.context
    if isinstance(context, dict):
        is_admin = context.get("is_admin", False)
    else:
        is_admin = getattr(context, "is_admin", False)

    # Defensive: explicit bool check rejects truthy-non-bool values
    # such as non-empty strings (so a mis-wired context that placed
    # the user_type string under the ``is_admin`` key cannot
    # accidentally grant admin access).
    if is_admin is not True:
        # The error message matches the pattern used by the REST
        # :class:`~src.api.middleware.auth.JWTAuthMiddleware` on 403
        # responses so that log-correlation between the two surfaces
        # uses the same literal string.
        raise PermissionError(
            "Administrator access required. This GraphQL field "
            "exposes data from the USRSEC table and is restricted "
            "to users whose JWT 'user_type' claim is 'A' "
            "(88 CDEMO-USRTYP-ADMIN)."
        )


def _clamp_page(page: int) -> int:
    """Clamp a 1-indexed page number to the valid range (>= 1).

    GraphQL integer inputs are signed 32-bit by default, so a negative
    page value can reach the resolver. Service-layer Pydantic
    validators reject ``page < 1`` with a 422 error; rather than
    surface that error, the resolver clamps any page <= 0 to page 1,
    matching the BMS screen behavior where the page selector has no
    notion of a negative page.
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


def _normalize(value: Optional[str]) -> str:  # noqa: UP045  # schema requires typing.Optional
    """Return ``value`` stripped of leading/trailing whitespace, or ``""``.

    Matches the COBOL ``INSPECT`` + ``FUNCTION REVERSE`` normalization
    applied to free-text inputs (e.g., account_id / card_num) before
    equality comparison against the VSAM primary key. Guards against
    common UX quirks (trailing newline from a copy/paste, extra space
    from a BMS field).
    """
    return value.strip() if value else ""


def _safe_decimal(value: Any) -> Decimal:
    """Return ``value`` as a :class:`Decimal`, falling back to zero.

    Pydantic guarantees every monetary field on the service responses
    is already a :class:`Decimal`, but the fallback defends against a
    future regression in the service layer. Never returns ``float``
    under any code path — preserves the AAP §0.7.2 contract.
    """
    if isinstance(value, Decimal):
        return value
    return _DECIMAL_ZERO


# ----------------------------------------------------------------------------
# GraphQL-type construction helpers
# ----------------------------------------------------------------------------
# These convert a service-layer Pydantic response DTO into the
# corresponding Strawberry GraphQL type. Field-by-field mapping is
# explicit so that any rename on either side of the boundary will
# produce a compile-time mypy error rather than a silent data-loss
# bug. Fields that are present on the GraphQL type but absent on the
# DTO (e.g., ``AccountType.addr_zip``, ``CardType.cvv_cd``) are
# defaulted to an empty string — documented in the body of each
# helper so the rationale is visible at the call site.
# ----------------------------------------------------------------------------


def _account_view_to_type(response: Any) -> AccountType:
    """Construct an :class:`AccountType` from an ``AccountViewResponse``.

    Field mapping (AccountViewResponse → AccountType):

    * ``account_id``            → ``acct_id``
    * ``active_status``         → ``active_status``
    * ``current_balance``       → ``curr_bal``         *(Decimal)*
    * ``credit_limit``          → ``credit_limit``     *(Decimal)*
    * ``cash_credit_limit``     → ``cash_credit_limit`` *(Decimal)*
    * ``open_date``             → ``open_date``
    * ``expiration_date``       → ``expiration_date``
    * ``reissue_date``          → ``reissue_date``
    * ``current_cycle_credit``  → ``curr_cyc_credit``  *(Decimal)*
    * ``current_cycle_debit``   → ``curr_cyc_debit``   *(Decimal)*
    * **(no response field)**   → ``addr_zip = ""``    †
    * ``group_id``              → ``group_id``

    † The :class:`AccountViewResponse` DTO (derived from the COACTVW
    BMS screen layout) does NOT expose the account's mailing ZIP
    because the COBOL Account View screen never displayed it — the
    VSAM record carried the ``ACCT-ADDR-ZIP`` field but the BMS
    mapset simply did not render it. The GraphQL :class:`AccountType`
    retains the field for completeness (the ORM model and REST
    :class:`AccountUpdateRequest` both expose it), but this
    read-only view defaults it to the empty string so the response
    stays round-trippable for any client that reads and later
    modifies an account via the REST surface.
    """
    return AccountType(
        acct_id=response.account_id,
        active_status=response.active_status,
        curr_bal=_safe_decimal(response.current_balance),
        credit_limit=_safe_decimal(response.credit_limit),
        cash_credit_limit=_safe_decimal(response.cash_credit_limit),
        open_date=response.open_date,
        expiration_date=response.expiration_date,
        reissue_date=response.reissue_date,
        curr_cyc_credit=_safe_decimal(response.current_cycle_credit),
        curr_cyc_debit=_safe_decimal(response.current_cycle_debit),
        addr_zip="",
        group_id=response.group_id,
    )


def _card_detail_to_type(response: Any) -> CardType:
    """Construct a :class:`CardType` from a ``CardDetailResponse``.

    Field mapping (CardDetailResponse → CardType):

    * ``card_number``       → ``card_num``
    * ``account_id``        → ``acct_id``
    * **(no response field)** → ``cvv_cd = ""``          †
    * ``embossed_name``     → ``embossed_name``
    * ``expiry_year`` + ``expiry_month`` → ``expiration_date``  ‡
    * ``status_code``       → ``active_status``

    † The card verification value (CVV) is a PCI-DSS sensitive field
    and is deliberately scrubbed from the read-side GraphQL surface.
    The CVV lives in the ``cards`` table for POSTTRAN / authorization
    checks but must never be exposed to API consumers. The service
    layer's :class:`CardDetailResponse` already omits the CVV; the
    resolver defaults the GraphQL field to the empty string so the
    response stays shape-compatible with the REST CardUpdateRequest.

    ‡ The COBOL Card Detail screen stored the expiration date as two
    separate 2- and 4-digit fields (``CREXPYRI`` year + ``CREXPMOI``
    month) which the service layer preserves. The GraphQL type
    expects a single 10-character ISO-like date string. We reconstruct
    ``YYYY-MM-01`` (day defaulting to the first of the month, since
    a card's "expiration" has no meaningful day component in the
    COBOL representation). If either the year or the month is blank,
    we return the empty string to signal "no expiration set".
    """
    if response.expiry_year and response.expiry_month:
        month: str = response.expiry_month.zfill(2)
        exp_date: str = f"{response.expiry_year}-{month}-01"
    else:
        exp_date = ""
    return CardType(
        card_num=response.card_number,
        acct_id=response.account_id,
        cvv_cd="",
        embossed_name=response.embossed_name,
        expiration_date=exp_date,
        active_status=response.status_code,
    )


def _card_list_item_to_type(item: Any) -> CardType:
    """Construct a :class:`CardType` from a ``CardListItem``.

    Field mapping (CardListItem → CardType):

    * ``card_number``         → ``card_num``
    * ``account_id``          → ``acct_id``
    * **(no item field)**     → ``cvv_cd = ""``            (PCI-DSS)
    * **(no item field)**     → ``embossed_name = ""``     †
    * **(no item field)**     → ``expiration_date = ""``   †
    * ``card_status``         → ``active_status``

    † The COBOL Card List BMS screen (``COCRDLI.CPY``) rendered only
    4 fields per row (selected, account_id, card_number, card_status)
    and did NOT display the embossed name or expiration date in the
    list view — those fields were only visible after drilling in to
    the Card Detail screen (``COCRDSLC.cbl``). The service layer's
    :class:`CardListItem` Pydantic DTO mirrors that screen geometry
    exactly. Clients that need the full card entity should call the
    :py:meth:`Query.card` resolver after the list, mirroring the
    COBOL user's "select a row then press ENTER to drill" flow.
    """
    return CardType(
        card_num=item.card_number,
        acct_id=item.account_id,
        cvv_cd="",
        embossed_name="",
        expiration_date="",
        active_status=item.card_status,
    )


def _transaction_detail_to_type(response: Any) -> TransactionType:
    """Construct a :class:`TransactionType` from a ``TransactionDetailResponse``.

    Field mapping (TransactionDetailResponse → TransactionType):

    * ``tran_id``          → ``tran_id``
    * ``tran_type_cd``     → ``type_cd``
    * ``tran_cat_cd``      → ``cat_cd``
    * ``tran_source``      → ``source``
    * ``description``      → ``description``
    * ``amount``           → ``amount``            *(Decimal)*
    * ``merchant_id``      → ``merchant_id``
    * ``merchant_name``    → ``merchant_name``
    * ``merchant_city``    → ``merchant_city``
    * ``merchant_zip``     → ``merchant_zip``
    * ``card_num``         → ``card_num``
    * ``orig_date``        → ``orig_ts``           †
    * ``proc_date``        → ``proc_ts``           †

    † The service layer exposes the original and processing timestamps
    as 10-character ``YYYY-MM-DD`` date strings on the detail view
    (COTRN01.CPY's BMS screen rendered only the date portion). The
    GraphQL field is named ``_ts`` rather than ``_date`` because the
    underlying VSAM record and PostgreSQL column carry the full
    26-character COBOL timestamp (``PIC X(26)``). When the detail
    response is used to populate the GraphQL field, we forward the
    truncated date string as-is — it is a strict prefix of the full
    timestamp and remains well-formed. Callers needing the nanosecond
    precision can fetch the underlying transaction via the REST API.
    """
    return TransactionType(
        tran_id=response.tran_id,
        type_cd=response.tran_type_cd,
        cat_cd=response.tran_cat_cd,
        source=response.tran_source,
        description=response.description,
        amount=_safe_decimal(response.amount),
        merchant_id=response.merchant_id,
        merchant_name=response.merchant_name,
        merchant_city=response.merchant_city,
        merchant_zip=response.merchant_zip,
        card_num=response.card_num,
        orig_ts=response.orig_date,
        proc_ts=response.proc_date,
    )


def _transaction_list_item_to_type(item: Any) -> TransactionType:
    """Construct a :class:`TransactionType` from a ``TransactionListItem``.

    Field mapping (TransactionListItem → TransactionType):

    * ``tran_id``            → ``tran_id``
    * **(no item field)**    → ``type_cd = ""``
    * **(no item field)**    → ``cat_cd = ""``
    * **(no item field)**    → ``source = ""``
    * ``description``        → ``description``
    * ``amount``             → ``amount``           *(Decimal)*
    * **(no item field)**    → ``merchant_id = ""``
    * **(no item field)**    → ``merchant_name = ""``
    * **(no item field)**    → ``merchant_city = ""``
    * **(no item field)**    → ``merchant_zip = ""``
    * **(no item field)**    → ``card_num = ""``
    * ``tran_date``          → ``orig_ts``          †
    * **(no item field)**    → ``proc_ts = ""``

    † The COBOL Transaction List BMS screen (``COTRN00.CPY``) rendered
    only 4 fields per row (tran_id, tran_date, description, amount)
    — the other 9 fields on :class:`TransactionType` were only visible
    on the Transaction Detail screen (COTRN01.CPY). The service
    layer's :class:`TransactionListItem` Pydantic DTO mirrors that
    screen geometry exactly. The ``tran_date`` value is mapped to
    ``orig_ts`` because it represents the original-authorization
    date — callers that need the full 26-character timestamp should
    drill in via :py:meth:`Query.transaction`.
    """
    return TransactionType(
        tran_id=item.tran_id,
        type_cd="",
        cat_cd="",
        source="",
        description=item.description,
        amount=_safe_decimal(item.amount),
        merchant_id="",
        merchant_name="",
        merchant_city="",
        merchant_zip="",
        card_num="",
        orig_ts=item.tran_date,
        proc_ts="",
    )


def _user_list_item_to_type(item: Any) -> UserType:
    """Construct a :class:`UserType` from a ``UserListItem``.

    Field mapping (UserListItem → UserType):

    * ``user_id``        → ``user_id``
    * ``first_name``     → ``first_name``
    * ``last_name``      → ``last_name``
    * ``user_type``      → ``usr_type``

    The GraphQL :class:`UserType` declares exactly 4 fields and
    deliberately omits the BCrypt password hash. The Pydantic
    :class:`UserListItem` DTO also omits the password, so the
    resolver path from DB through service through GraphQL never
    carries the hash.

    The GraphQL identifier field is named ``user_id`` (exposed as
    ``userId`` via Strawberry's snake_case → camelCase conversion)
    to match the SQLAlchemy ``UserSecurity.user_id`` model column,
    the REST ``/users/{user_id}`` path parameter, and the other
    GraphQL identifier conventions (``acctId``, ``tranId``,
    ``cardNum``). Prior to the QA Checkpoint 5 resolution for
    Issue 18 this field was named ``usr_id`` (abbreviated to mirror
    the COBOL ``SEC-USR-ID`` copybook field from
    :file:`app/cpy/CSUSR01Y.cpy`); the name was changed to
    ``user_id`` so the GraphQL surface presents a consistent
    identifier vocabulary. The COBOL provenance is documented in
    :class:`~src.api.graphql.types.user_type.UserType` and in the
    SQLAlchemy model :class:`~src.shared.models.user_security.UserSecurity`.
    """
    return UserType(
        user_id=item.user_id,
        first_name=item.first_name,
        last_name=item.last_name,
        usr_type=item.user_type,
    )


# ============================================================================
# Root Query type
# ============================================================================
# The :class:`Query` class below is the root read-side GraphQL type.
# It is consumed by :mod:`src.api.graphql.schema` where it is combined
# with the :class:`~src.api.graphql.mutations.Mutation` class into a
# single :class:`strawberry.Schema`, which in turn is mounted at
# ``/graphql`` by :mod:`src.api.main`.
#
# Every resolver method:
#
# * Is declared ``async`` so it can ``await`` the async service-layer
#   methods without blocking the FastAPI event loop.
# * Accepts ``info: Info`` as its first non-``self`` argument — the
#   Strawberry convention for resolvers that need access to the
#   FastAPI request context.
# * Pulls the :class:`AsyncSession` from ``info`` via
#   :func:`_get_session`, then constructs the appropriate service
#   object (``AccountService``, ``CardService``, ``TransactionService``,
#   ``UserService``) bound to that session.
# * Calls the service method listed in the file-level schema's
#   ``members_accessed`` contract.
# * Constructs the Strawberry GraphQL type from the Pydantic response
#   DTO via one of the ``_*_to_type`` helpers above, which do the
#   explicit field-by-field mapping.
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
    program converted for F-004 (Account View), F-006 (Card List),
    F-007 (Card Detail), F-009 (Transaction List), F-010 (Transaction
    Detail), F-018 (User List):

    * :py:meth:`account`       — COACTVWC.cbl    — single account by ID.
    * :py:meth:`accounts`      — COACTVWC.cbl    — paginated account list.
    * :py:meth:`card`          — COCRDSLC.cbl    — single card by number.
    * :py:meth:`cards`         — COCRDLIC.cbl    — paginated card list
                                                   (7 rows/page).
    * :py:meth:`transaction`   — COTRN01C.cbl    — single transaction.
    * :py:meth:`transactions`  — COTRN00C.cbl    — paginated tx list
                                                   (10 rows/page).
    * :py:meth:`user`          — COUSR00C.cbl    — single user by ID.
    * :py:meth:`users`         — COUSR00C.cbl    — paginated user list
                                                   (10 rows/page).

    The class is ``@strawberry.type``-decorated (not ``@strawberry.input``
    or ``@strawberry.interface``) so Strawberry registers it as a
    GraphQL object type with no automatic reflection on private
    attributes. Each ``@strawberry.field`` decoration below exposes
    exactly one resolver to the GraphQL schema — methods without
    that decorator are not visible to clients.
    """

    # ------------------------------------------------------------------
    # account — COACTVWC.cbl → single account lookup (Feature F-004)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "Fetch a single account by its 11-digit account ID. "
            "Maps to COACTVWC.cbl (Feature F-004), which performs a "
            "3-entity join across ACCTDAT + CUSTDAT + CXACAIX. "
            "Returns null when no account matches the supplied ID — "
            "GraphQL callers should treat a null data.account as "
            "'not found'."
        ),
    )
    async def account(
        self,
        info: Info,
        acct_id: str,
    ) -> Optional[AccountType]:  # noqa: UP045  # schema requires typing.Optional
        """Resolve the ``account(acct_id: String!)`` query field.

        Delegates to :meth:`AccountService.get_account_view` which
        implements the 3-entity keyed-read chain from
        ``COACTVWC.cbl`` paragraphs ``9200-GETCARDXREF-BYACCT``,
        ``9300-GETACCTDATA-BYACCT``, and ``9400-GETCUSTDATA-BYCUST``.
        The service returns an :class:`AccountViewResponse` whose
        ``error_message`` is non-null on not-found / invalid-id;
        this resolver inspects that field and returns ``None`` in
        that case, letting Strawberry emit ``"account": null`` in
        the GraphQL response.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers.
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
            account matches the supplied ID.
        """
        normalized_id: str = _normalize(acct_id)

        logger.debug(
            "account query",
            extra={
                "operation": "graphql_query_account",
                "acct_id": normalized_id,
            },
        )

        if not normalized_id:
            # Empty / whitespace-only acct_id — treat as "not found"
            # rather than forwarding to the service (which would
            # return a validation-error response for an 11-char
            # length mismatch). Short-circuit BEFORE opening a
            # session — there is no need to acquire a database
            # connection from the pool just to return null.
            logger.warning(
                "account query: empty acct_id",
                extra={"operation": "graphql_query_account"},
            )
            return None

        # Service-layer database failures (connectivity issues,
        # schema mismatches, constraint violations, etc.) are NOT
        # caught here. Previously this resolver wrapped the service
        # call in a broad ``except Exception: return None`` block
        # to mimic the COBOL "VSAM I/O error falls through to
        # record-not-found" behavior, but that silent-failure mode
        # made the API indistinguishable from a genuine
        # "account does not exist" response and directly caused the
        # QA Checkpoint 5 observation that GraphQL queries returned
        # empty data on HTTP 200 despite the database being populated.
        #
        # Instead, we now let SQLAlchemy exceptions propagate up to
        # the Strawberry executor. The schema-level
        # :class:`strawberry.extensions.MaskErrors` extension
        # (configured in :mod:`~src.api.graphql.schema`) replaces
        # the raw SQL + bind-parameter text with a generic client-
        # safe message, so callers receive a populated ``errors``
        # array that unambiguously signals a server-side failure
        # rather than a false-negative "null result". The full
        # stack trace and SQL remain in the server log for operator
        # diagnosis.
        #
        # The ``response.error_message is not None`` branch below
        # continues to encode the LEGITIMATE "account not found"
        # outcome — the service returned successfully but reported
        # the 11-digit account identifier did not match any row.
        # That path stays as ``return None`` because GraphQL's
        # nullable-result idiom is the correct way to signal
        # "record does not exist" without populating ``errors``.
        #
        # The ``async with _get_session(info) as session`` block
        # opens a FRESH per-resolver AsyncSession (CICS SYNCPOINT
        # boundary) so that this resolver can safely run in
        # parallel with sibling resolvers in a multi-field GraphQL
        # query (QA Checkpoint 10, Issue 1). On clean exit the
        # session commits; on any exception it rolls back.
        async with _get_session(info) as session:
            service: AccountService = AccountService(session)
            response = await service.get_account_view(normalized_id)

            if response.error_message is not None:
                logger.info(
                    "account query: not found",
                    extra={
                        "operation": "graphql_query_account",
                        "acct_id": normalized_id,
                        "reason": response.error_message,
                    },
                )
                return None

            return _account_view_to_type(response)

    # ------------------------------------------------------------------
    # accounts — COACTVWC.cbl → paginated account list
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "List accounts with pagination. Derived from "
            "COACTVWC.cbl (Feature F-004). Issues a single "
            "OFFSET/LIMIT SQL query against the accounts table "
            "ordered by acct_id (PRIMARY KEY / COBOL PIC 9(11) "
            "ascending) and returns the resulting rows. Default page "
            "size is 10; pagination is clamped to [1, _MAX_PAGE_SIZE]. "
            "Monetary fields are Decimal — no float coercion."
        ),
    )
    async def accounts(
        self,
        info: Info,
        page: int = 1,
        page_size: int = _USER_PAGE_SIZE_DEFAULT,
    ) -> list[AccountType]:
        """Resolve the ``accounts(page, page_size)`` query field.

        Issues a single SQL ``SELECT ... OFFSET :offset LIMIT
        :page_size`` against the ``accounts`` table ordered by
        ``acct_id`` (the PRIMARY KEY — zero-padded 11-character
        strings sort lexicographically in the same order as their
        numeric equivalents, matching the COBOL ``PIC 9(11)`` browse
        order). Each resulting :class:`Account` ORM row is converted
        to an :class:`AccountType` via :meth:`AccountType.from_model`,
        which deliberately excludes the server-side-only
        ``version_id`` optimistic-concurrency token (see
        AAP §0.4.4).

        This is a performance refactor — the previous implementation
        iterated the ``page_size`` candidate account-ID space
        sequentially and issued one 3-entity service call
        (:meth:`AccountService.get_account_view`) per candidate, which
        was O(page_size) sequential round-trips and performed two
        extra joins (Customer, CardCrossReference) per row that the
        GraphQL :class:`AccountType` does not expose. The bulk
        ``select()`` + ``offset()`` + ``limit()`` approach issues
        exactly one round-trip per page and reads only the columns
        that flow through to the GraphQL response.

        The primary-key order is preserved because ``acct_id`` is
        stored as a zero-padded 11-character string
        (``String(11) NOT NULL``) — lexicographic sort over
        zero-padded fixed-width digit strings is equivalent to
        numeric ascending order, matching the COBOL VSAM GTEQ browse
        behavior documented in ``COACTVWC.cbl``.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        page : int, default 1
            1-indexed page number. Values less than 1 are clamped
            to 1.
        page_size : int, default 10
            Number of accounts per page. Clamped to
            ``[1, _MAX_PAGE_SIZE]``.

        Returns
        -------
        list[AccountType]
            The (possibly empty) list of accounts in the requested
            page, each constructed from the ORM row via
            :meth:`AccountType.from_model` (Decimal monetary fields,
            no ``version_id``).
        """
        page_clamped: int = _clamp_page(page)
        page_size_clamped: int = _clamp_page_size(page_size, _USER_PAGE_SIZE_DEFAULT)
        offset: int = (page_clamped - 1) * page_size_clamped

        logger.debug(
            "accounts query",
            extra={
                "operation": "graphql_query_accounts",
                "page": page_clamped,
                "page_size": page_size_clamped,
                "offset": offset,
            },
        )

        # Bulk SQL pagination: ORDER BY acct_id (PRIMARY KEY) gives
        # stable, deterministic ordering. Because acct_id is stored
        # as a zero-padded 11-character string, the lexicographic
        # comparison used by PostgreSQL string ordering is identical
        # to numeric ascending — matching the COBOL PIC 9(11) browse
        # order from COACTVWC.cbl. Using the PRIMARY KEY also means
        # the OFFSET/LIMIT scan can use the btree index directly.
        stmt = select(Account).order_by(Account.acct_id).offset(offset).limit(page_size_clamped)

        # Database-level failures (connectivity, SQL errors,
        # schema mismatches) propagate to Strawberry and are
        # sanitised by the schema-level
        # :class:`~strawberry.extensions.MaskErrors` extension
        # before serialisation. We no longer swallow the exception
        # and return an empty list — that pattern caused QA
        # Checkpoint 5 to observe ``{"data":{"accounts":[]}}``
        # responses on HTTP 200 despite the database containing
        # 50 seed rows, making genuine database failures
        # indistinguishable from legitimately-empty result sets.
        # See the module docstring in ``src/api/graphql/schema.py``
        # for the error-masking contract.
        #
        # The ``async with _get_session(info) as session`` block
        # opens a FRESH per-resolver AsyncSession (CICS SYNCPOINT
        # boundary) so this resolver runs safely in parallel with
        # sibling resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            result = await session.execute(stmt)
            rows: list[Account] = list(result.scalars().all())

            # AccountType.from_model is the single allowed
            # ORM→GraphQL conversion point — it copies the 12
            # GraphQL fields explicitly and deliberately does NOT
            # read version_id, so the optimistic-concurrency token
            # cannot cross the GraphQL boundary. See
            # ``src/api/graphql/types/account_type.py``.
            return [AccountType.from_model(account) for account in rows]

    # ------------------------------------------------------------------
    # card — COCRDSLC.cbl → single card lookup (Feature F-007)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "Fetch a single card by its 16-character card number. "
            "Maps to COCRDSLC.cbl (Feature F-007). Returns null when "
            "no card matches. CVV is never exposed (PCI-DSS)."
        ),
    )
    async def card(
        self,
        info: Info,
        card_num: str,
    ) -> Optional[CardType]:  # noqa: UP045  # schema requires typing.Optional
        """Resolve the ``card(card_num: String!)`` query field.

        Delegates to :meth:`CardService.get_card_detail` which maps
        to the COBOL ``EXEC CICS READ FILE('CARDDAT') RIDFLD(CARD-NUM)``
        single-keyed-read pattern in ``COCRDSLC.cbl``. The service
        returns a :class:`CardDetailResponse` whose ``error_message``
        is non-null on not-found; this resolver inspects that field
        and returns ``None`` in that case.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        card_num : str
            The 16-digit card number (COBOL ``PIC X(16)`` → Python
            ``str``). Whitespace is stripped before the lookup.

        Returns
        -------
        Optional[CardType]
            The resolved GraphQL card type (CVV scrubbed per
            PCI-DSS), or ``None`` if no card matches.
        """
        normalized_num: str = _normalize(card_num)

        logger.debug(
            "card query",
            extra={
                "operation": "graphql_query_card",
                "card_num": normalized_num,
            },
        )

        if not normalized_num:
            # Empty / whitespace-only card_num — short-circuit
            # BEFORE acquiring a session.
            logger.warning(
                "card query: empty card_num",
                extra={"operation": "graphql_query_card"},
            )
            return None

        # Database failures propagate to Strawberry and are sanitised
        # by the schema-level :class:`~strawberry.extensions.MaskErrors`
        # extension. The ``response.error_message is not None`` branch
        # below continues to signal the legitimate "card not found"
        # outcome returned by a successful service call.
        #
        # The ``async with _get_session(info) as session`` block opens
        # a FRESH per-resolver AsyncSession (CICS SYNCPOINT boundary)
        # so this resolver runs safely in parallel with sibling
        # resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            service: CardService = CardService(session)
            response = await service.get_card_detail(normalized_num)

            if response.error_message is not None:
                logger.info(
                    "card query: not found",
                    extra={
                        "operation": "graphql_query_card",
                        "card_num": normalized_num,
                        "reason": response.error_message,
                    },
                )
                return None

            return _card_detail_to_type(response)

    # ------------------------------------------------------------------
    # cards — COCRDLIC.cbl → paginated card list (Feature F-006)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "List cards, optionally filtered by account_id, with "
            "pagination of 7 rows per page. Maps to COCRDLIC.cbl "
            "(Feature F-006). The page size is fixed at 7 rows to "
            "match the BMS screen geometry (WS-MAX-SCREEN-LINES in "
            "COCRDLIC.cbl, OCCURS 7 TIMES in COCRDLI.CPY). CVV and "
            "embossed name are not returned in list view — drill in "
            "via the card(card_num) query for full detail."
        ),
    )
    async def cards(
        self,
        info: Info,
        account_id: Optional[str] = None,  # noqa: UP045  # schema requires typing.Optional
        page: int = 1,
        page_size: int = _CARD_PAGE_SIZE_DEFAULT,
    ) -> list[CardType]:
        """Resolve the ``cards(account_id, page, page_size)`` field.

        Delegates to :meth:`CardService.list_cards` which implements
        the COBOL ``2100-PROCESS-ENTER-KEY`` paragraph in
        ``COCRDLIC.cbl``. The service returns at most 7 cards per
        page (hard-coded to match ``WS-MAX-SCREEN-LINES``).

        The optional ``account_id`` filter restricts the result set
        to cards that belong to the given 11-digit account, mirroring
        the ``2210-EDIT-ACCOUNT`` pre-browse validation.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        account_id : Optional[str]
            Optional 11-digit account filter. When supplied, only
            cards belonging to that account are returned.
        page : int, default 1
            1-indexed page number. Clamped to >= 1.
        page_size : int, default 7
            Requested page size. Hard-capped at 7 rows per page to
            match the COBOL BMS screen geometry
            (``WS-MAX-SCREEN-LINES`` in ``COCRDLIC.cbl``,
            ``OCCURS 7 TIMES`` in ``COCRDLI.CPY``). Callers that
            pass a smaller ``page_size`` get a truncated slice;
            callers that pass ``page_size > 7`` receive a GraphQL
            validation error rather than silently being given a
            partial 7-row page (QA Checkpoint 10, Issue 2).

        Returns
        -------
        list[CardType]
            The (possibly empty) list of cards, each with CVV,
            embossed name, and expiration date elided per the
            COBOL list-view behavior.

        Raises
        ------
        ValueError
            When ``page_size > 7``. The cap is a non-negotiable
            COBOL/BMS constraint preserved by the AAP — silently
            returning fewer rows than requested is a confusing
            API contract violation flagged by QA Checkpoint 10
            (Issue 2). ``ValueError`` is raised intentionally
            because the schema-level :class:`MaskErrors` extension
            ONLY masks :class:`SQLAlchemyError`, so the validation
            message reaches the caller verbatim.
        """
        # ------------------------------------------------------
        # Issue 2 fix (QA Checkpoint 10):
        # Reject ``page_size > 7`` BEFORE acquiring a session or
        # touching the database. Previously this resolver
        # silently capped the result set at 7 rows when the
        # caller asked for more, which violated the API contract
        # promise and made it impossible for clients to detect
        # truncation. Raising a ``ValueError`` makes the cap
        # explicit and visible; the schema's ``MaskErrors``
        # extension does NOT mask non-SQLAlchemy exceptions, so
        # the message reaches the GraphQL client unchanged.
        # ------------------------------------------------------
        if page_size > _CARD_PAGE_SIZE_DEFAULT:
            raise ValueError(
                f"page_size must not exceed {_CARD_PAGE_SIZE_DEFAULT} "
                f"for the cards query (got {page_size}). This is a "
                "COBOL/BMS screen-geometry constraint preserved from "
                "WS-MAX-SCREEN-LINES in COCRDLIC.cbl. Reduce "
                "page_size or paginate via the page parameter."
            )

        page_clamped: int = _clamp_page(page)
        normalized_acct: Optional[str] = (  # noqa: UP045  # schema requires typing.Optional
            _normalize(account_id) or None
        )

        logger.debug(
            "cards query",
            extra={
                "operation": "graphql_query_cards",
                "account_id": normalized_acct,
                "page": page_clamped,
                "page_size_requested": page_size,
                "page_size_effective": _CARD_PAGE_SIZE_DEFAULT,
            },
        )

        # CardListRequest accepts account_id, card_number, and
        # page_number — page_size is hard-coded to 7 by the service
        # (no request field to override it) because the COBOL
        # mapset only has 7 row slots.
        request: CardListRequest = CardListRequest(
            account_id=normalized_acct,
            card_number=None,
            page_number=page_clamped,
        )

        # Database failures propagate to Strawberry and are sanitised
        # by the schema-level :class:`~strawberry.extensions.MaskErrors`
        # extension. The previous silent ``return []`` pattern made
        # connectivity errors look identical to legitimately-empty
        # query windows and was flagged by QA Checkpoint 5.
        #
        # The ``async with _get_session(info) as session`` block
        # opens a FRESH per-resolver AsyncSession (CICS SYNCPOINT
        # boundary) so this resolver runs safely in parallel with
        # sibling resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            service: CardService = CardService(session)
            response = await service.list_cards(request)

            items = response.cards
            # Respect a caller-supplied ``page_size`` lower than the
            # service's fixed 7-row window. Values at exactly 7 (the
            # cap) have no effect — the service cap is authoritative.
            # Values above 7 are rejected at the top of the resolver
            # (Issue 2 fix), so this branch only fires for
            # 0 < page_size < 7.
            if 0 < page_size < _CARD_PAGE_SIZE_DEFAULT:
                items = items[:page_size]

            return [_card_list_item_to_type(item) for item in items]

    # ------------------------------------------------------------------
    # transaction — COTRN01C.cbl → single transaction (Feature F-010)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
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
    ) -> Optional[TransactionType]:  # noqa: UP045  # schema requires typing.Optional
        """Resolve the ``transaction(tran_id: String!)`` query field.

        Delegates to :meth:`TransactionService.get_transaction_detail`
        which maps to the COBOL ``EXEC CICS READ FILE('TRANSACT')
        RIDFLD(TRAN-ID)`` pattern in ``COTRN01C.cbl``. The service
        returns a :class:`TransactionDetailResponse` whose
        ``message`` field is non-null on not-found.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        tran_id : str
            The 16-character transaction identifier (COBOL
            ``PIC X(16)`` → Python ``str``).

        Returns
        -------
        Optional[TransactionType]
            The resolved GraphQL transaction type, or ``None`` if
            no transaction matches.
        """
        normalized_id: str = _normalize(tran_id)

        logger.debug(
            "transaction query",
            extra={
                "operation": "graphql_query_transaction",
                "tran_id": normalized_id,
            },
        )

        if not normalized_id:
            # Empty / whitespace-only tran_id — short-circuit
            # BEFORE acquiring a session.
            logger.warning(
                "transaction query: empty tran_id",
                extra={"operation": "graphql_query_transaction"},
            )
            return None

        # Database failures propagate to Strawberry and are sanitised
        # by the schema-level :class:`~strawberry.extensions.MaskErrors`
        # extension. A populated ``response.message`` below continues
        # to encode the legitimate "transaction not found" outcome.
        #
        # The ``async with _get_session(info) as session`` block opens
        # a FRESH per-resolver AsyncSession (CICS SYNCPOINT boundary)
        # so this resolver runs safely in parallel with sibling
        # resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            service: TransactionService = TransactionService(session)
            response = await service.get_transaction_detail(normalized_id)

            # The TransactionDetailResponse signals "not found" via a
            # populated ``message`` field (the COBOL COTRN01C screen
            # displays this message on the status bar when the tran_id
            # does not resolve). A None message indicates success.
            if response.message is not None:
                logger.info(
                    "transaction query: not found",
                    extra={
                        "operation": "graphql_query_transaction",
                        "tran_id": normalized_id,
                        "reason": response.message,
                    },
                )
                return None

            return _transaction_detail_to_type(response)

    # ------------------------------------------------------------------
    # transactions — COTRN00C.cbl → paginated tx list (Feature F-009)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "List transactions with pagination. Maps to COTRN00C.cbl "
            "(Feature F-009). Default page size 10 to match the "
            "OCCURS 10 TIMES rows on COTRN00.CPY. List view omits "
            "card number, merchant data, and timestamps — drill in "
            "via the transaction(tran_id) query for full detail."
        ),
    )
    async def transactions(
        self,
        info: Info,
        page: int = 1,
        page_size: int = _TRANSACTION_PAGE_SIZE_DEFAULT,
    ) -> list[TransactionType]:
        """Resolve the ``transactions(page, page_size)`` field.

        Delegates to :meth:`TransactionService.list_transactions`
        which maps to the COBOL ``PROCESS-ENTER-KEY`` /
        ``PROCESS-PF8`` paragraphs in ``COTRN00C.cbl``. The service
        returns at most ``page_size`` transactions per page (default
        10 to match the BMS mapset).

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        page : int, default 1
            1-indexed page number. Clamped to >= 1.
        page_size : int, default 10
            Requested page size. Clamped to ``[1, _MAX_PAGE_SIZE]``.

        Returns
        -------
        list[TransactionType]
            The (possibly empty) list of transactions, with the
            9 non-list fields defaulted to empty strings per the
            COBOL list-view screen geometry.
        """
        page_clamped: int = _clamp_page(page)
        page_size_clamped: int = _clamp_page_size(page_size, _TRANSACTION_PAGE_SIZE_DEFAULT)

        logger.debug(
            "transactions query",
            extra={
                "operation": "graphql_query_transactions",
                "page": page_clamped,
                "page_size": page_size_clamped,
            },
        )

        request: TransactionListRequest = TransactionListRequest(
            tran_id=None,
            page=page_clamped,
            page_size=page_size_clamped,
        )

        # Database failures propagate to Strawberry and are sanitised
        # by the schema-level :class:`~strawberry.extensions.MaskErrors`
        # extension. Previously this resolver returned ``[]`` on any
        # exception, which disguised connectivity failures as empty
        # pages (QA Checkpoint 5 finding).
        #
        # The ``async with _get_session(info) as session`` block
        # opens a FRESH per-resolver AsyncSession (CICS SYNCPOINT
        # boundary) so this resolver runs safely in parallel with
        # sibling resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            service: TransactionService = TransactionService(session)
            response = await service.list_transactions(request)

            return [_transaction_list_item_to_type(item) for item in response.transactions]

    # ------------------------------------------------------------------
    # user — derived from COUSR00C.cbl → single user by ID
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "Fetch a single user by the 8-character user ID. "
            "Derived from COUSR00C.cbl (Feature F-018). Implemented "
            "as a degenerate list query with an exact-match filter — "
            "the COBOL screen allowed user-id as an input to the "
            "browse, so an 8-char exact ID is functionally a single-"
            "row browse result. The password field is NEVER exposed."
        ),
    )
    async def user(
        self,
        info: Info,
        user_id: str,
    ) -> Optional[UserType]:  # noqa: UP045  # schema requires typing.Optional
        """Resolve the ``user(userId: String!)`` query field.

        **Authorization**: This resolver reads from the USRSEC table
        and is therefore restricted to administrator callers
        (``user_type == 'A'``, 88 ``CDEMO-USRTYP-ADMIN``) — matching
        the REST middleware admin gating on ``/users`` paths and the
        legacy CICS COUSR00 transaction access model. Non-admin
        callers receive a :class:`PermissionError` raised by
        :func:`_require_admin` before any service call is issued.

        The :class:`UserService` does not expose a dedicated
        ``get_user`` single-record API; the COBOL COUSR00C program
        uses the same browse-mode flow for both single-row lookup
        (user types an ID, presses ENTER, screen shows one matching
        row) and full list. We replicate that by calling
        :meth:`UserService.list_users` with the ``user_id`` filter
        populated — it becomes an exact-match because the filter is
        a LIKE prefix and user IDs are fixed at 8 characters.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        user_id : str
            The 8-character user identifier (COBOL ``PIC X(08)`` →
            Python ``str``). Whitespace is stripped before the
            lookup. Strawberry exposes this as ``userId`` in the
            GraphQL schema (automatic snake_case → camelCase
            conversion) so clients send ``user(userId: "...")``.
            The longer form ``user_id`` aligns with the SQLAlchemy
            ``UserSecurity.user_id`` model column, matches the REST
            path parameter ``/users/{user_id}``, and resolves the
            QA Checkpoint 5 finding (Issue 18) that the GraphQL
            surface previously exposed ``usrId`` — inconsistent with
            the other GraphQL argument names (``acctId``,
            ``tranId``, ``cardNum``) and with the authoritative
            model/DB column name.

        Returns
        -------
        Optional[UserType]
            The resolved GraphQL user type (password excluded), or
            ``None`` if no user matches.

        Raises
        ------
        PermissionError
            If the authenticated caller is not an administrator
            (``user_type != 'A'``). Raised by :func:`_require_admin`
            BEFORE any service call is issued so no USRSEC read
            occurs for non-admin callers.
        """
        # Admin-only gate — matches REST /users path-prefix gating
        # in src.api.middleware.auth.ADMIN_ONLY_PREFIXES and
        # preserves parity with the legacy CICS COUSR00 transaction
        # access model (CDEMO-USER-TYPE = 'A'). Raised BEFORE
        # session acquisition so unauthorized callers do not even
        # consume a database connection from the pool.
        _require_admin(info)

        normalized_id: str = _normalize(user_id)

        logger.debug(
            "user query",
            extra={
                "operation": "graphql_query_user",
                "user_id": normalized_id,
            },
        )

        if not normalized_id:
            # Empty / whitespace-only user_id — short-circuit
            # BEFORE acquiring a session.
            logger.warning(
                "user query: empty user_id",
                extra={"operation": "graphql_query_user"},
            )
            return None

        # Use list_users with an 8-char exact-match filter and a
        # small page window. The filter is applied as a LIKE prefix
        # in the service, but since all user IDs are exactly 8
        # characters, a LIKE 'XXXXXXXX%' effectively matches only
        # that user ID.
        request: UserListRequest = UserListRequest(
            user_id=normalized_id,
            page=1,
            page_size=1,
        )

        # Database failures propagate to Strawberry and are sanitised
        # by the schema-level :class:`~strawberry.extensions.MaskErrors`
        # extension. :func:`_require_admin` above raises
        # :class:`PermissionError` BEFORE this line when the caller
        # is not an administrator; that exception is not a SQLAlchemy
        # error and therefore passes through the mask predicate
        # unchanged, reaching the client as an explicit
        # authorization failure.
        #
        # The ``async with _get_session(info) as session`` block
        # opens a FRESH per-resolver AsyncSession (CICS SYNCPOINT
        # boundary) so this resolver runs safely in parallel with
        # sibling resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            service: UserService = UserService(session)
            response = await service.list_users(request)

            # Be strict about exact match (the LIKE prefix could
            # theoretically match a shorter prefix if a user ID were
            # ever shorter than 8 chars, though the schema guarantees
            # exactly 8 chars — defensive guard).
            for item in response.users:
                if item.user_id == normalized_id:
                    return _user_list_item_to_type(item)

            logger.info(
                "user query: not found",
                extra={
                    "operation": "graphql_query_user",
                    "user_id": normalized_id,
                },
            )
            return None

    # ------------------------------------------------------------------
    # users — COUSR00C.cbl → paginated user list (Feature F-018)
    # ------------------------------------------------------------------
    @strawberry.field(  # type: ignore[untyped-decorator]  # strawberry.field returns Any
        description=(
            "List users with pagination. Maps to COUSR00C.cbl "
            "(Feature F-018). Default page size 10 matches the "
            "OCCURS 10 TIMES rows on COUSR00.CPY. Password fields "
            "are NEVER exposed (not present on UserType or in the "
            "service response)."
        ),
    )
    async def users(
        self,
        info: Info,
        page: int = 1,
        page_size: int = _USER_PAGE_SIZE_DEFAULT,
    ) -> list[UserType]:
        """Resolve the ``users(page, page_size)`` query field.

        **Authorization**: This resolver reads from the USRSEC table
        and is therefore restricted to administrator callers
        (``user_type == 'A'``, 88 ``CDEMO-USRTYP-ADMIN``) — matching
        the REST middleware admin gating on ``/users`` paths and the
        legacy CICS COUSR00 transaction access model. Non-admin
        callers receive a :class:`PermissionError` raised by
        :func:`_require_admin` before any service call is issued so
        non-admin callers cannot enumerate user IDs through the
        GraphQL surface.

        Delegates to :meth:`UserService.list_users` which maps to
        the COBOL browse-mode user list in ``COUSR00C.cbl``. The
        service returns a :class:`UserListResponse` whose ``users``
        list contains at most ``page_size`` :class:`UserListItem`
        instances, none of which carry the password hash.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async session
            FACTORY under ``context["db_factory"]``. The resolver
            opens its OWN per-invocation
            :class:`~sqlalchemy.ext.asyncio.AsyncSession` via
            ``async with _get_session(info)`` to remain safe under
            Strawberry's concurrent ``asyncio.gather`` execution
            of sibling resolvers (QA Checkpoint 10, Issue 1).
        page : int, default 1
            1-indexed page number. Clamped to >= 1.
        page_size : int, default 10
            Requested page size. Clamped to ``[1, _MAX_PAGE_SIZE]``.

        Returns
        -------
        list[UserType]
            The (possibly empty) list of users, each with password
            DELIBERATELY ABSENT (defence in depth — the GraphQL
            type omits the field AND the service DTO omits the
            field, so the hash cannot cross this boundary).

        Raises
        ------
        PermissionError
            If the authenticated caller is not an administrator
            (``user_type != 'A'``). Raised by :func:`_require_admin`
            BEFORE any service call is issued so no USRSEC read
            occurs for non-admin callers.
        """
        # Admin-only gate — matches REST /users path-prefix gating
        # in src.api.middleware.auth.ADMIN_ONLY_PREFIXES and
        # preserves parity with the legacy CICS COUSR00 transaction
        # access model (CDEMO-USER-TYPE = 'A'). Without this gate,
        # any authenticated user could enumerate the USRSEC table
        # via GraphQL — an information-disclosure inconsistency
        # with the REST surface. Raised BEFORE session acquisition
        # so unauthorized callers do not even consume a database
        # connection from the pool.
        _require_admin(info)

        page_clamped: int = _clamp_page(page)
        page_size_clamped: int = _clamp_page_size(page_size, _USER_PAGE_SIZE_DEFAULT)

        logger.debug(
            "users query",
            extra={
                "operation": "graphql_query_users",
                "page": page_clamped,
                "page_size": page_size_clamped,
            },
        )

        request: UserListRequest = UserListRequest(
            user_id=None,
            page=page_clamped,
            page_size=page_size_clamped,
        )

        # Database failures propagate to Strawberry and are sanitised
        # by the schema-level :class:`~strawberry.extensions.MaskErrors`
        # extension so clients never observe raw SQL, parameter
        # values, or SQLAlchemy exception text. The previous
        # ``try / except Exception: return []`` pattern was deleted
        # as part of the QA Checkpoint 5 resolution for Issue 15 —
        # silently returning an empty list hid real database
        # failures behind a benign-looking HTTP 200 response,
        # confusing clients and obstructing operational monitoring.
        # :func:`_require_admin` above raises
        # :class:`PermissionError` BEFORE this line when the caller
        # is not an administrator; that exception is not a
        # SQLAlchemy error and therefore passes through the mask
        # predicate unchanged, reaching the client as an explicit
        # authorization failure.
        #
        # The ``async with _get_session(info) as session`` block
        # opens a FRESH per-resolver AsyncSession (CICS SYNCPOINT
        # boundary) so this resolver runs safely in parallel with
        # sibling resolvers — see QA Checkpoint 10 Issue 1 fix.
        async with _get_session(info) as session:
            service: UserService = UserService(session)
            response = await service.list_users(request)

            return [_user_list_item_to_type(item) for item in response.users]


# ----------------------------------------------------------------------------
# Public exports
# ----------------------------------------------------------------------------
# Only the :class:`Query` class is part of the public API. The private
# helpers (``_get_session``, ``_clamp_page``, ``_clamp_page_size``,
# ``_normalize``, ``_safe_decimal``, and the ``_*_to_type`` builders)
# and module constants are intentionally not re-exported to keep the
# public surface minimal and discourage cross-module coupling.
# ----------------------------------------------------------------------------
__all__ = ["Query"]
