# ============================================================================
# Source: app/cbl/CO*.cbl (all online CICS COBOL programs)
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
"""GraphQL schema — Strawberry root schema for the CardDemo API.

This module stitches the package-level GraphQL types, queries, and
mutations into a single :class:`strawberry.Schema` that the
:mod:`src.api.main` module mounts at the ``/graphql`` endpoint via
:class:`strawberry.fastapi.GraphQLRouter`.

Structure
---------
The Strawberry schema is composed of three pieces, each defined in
its own module:

* :class:`~src.api.graphql.queries.Query` — eight read-side resolvers
  (``account``, ``accounts``, ``card``, ``cards``, ``transaction``,
  ``transactions``, ``user``, ``users``) converted from the
  read-oriented online CICS COBOL programs.
* :class:`~src.api.graphql.mutations.Mutation` — four write-side
  resolvers (``update_account``, ``update_card``, ``add_transaction``,
  ``pay_bill``) converted from the write-oriented online CICS COBOL
  programs.
* The four :mod:`src.api.graphql.types` modules
  (``account_type``, ``card_type``, ``transaction_type``,
  ``user_type``) which Strawberry discovers implicitly through the
  type annotations on the Query and Mutation classes — no explicit
  type registration is required at the schema level.

GraphQL Protocol Surface
------------------------
The single :class:`strawberry.Schema` exposed from this module
provides a complete CardDemo GraphQL API with the following
top-level operations:

.. code-block:: graphql

    type Query {
      account(acctId: String!): AccountType
      accounts(page: Int! = 1, pageSize: Int! = 10): [AccountType!]!
      card(cardNum: String!): CardType
      cards(accountId: String, cardNum: String, page: Int! = 1): [CardType!]!
      transaction(tranId: String!): TransactionType
      transactions(tranId: String, page: Int! = 1, pageSize: Int! = 10): [TransactionType!]!
      user(userId: String!): UserType
      users(userId: String, page: Int! = 1, pageSize: Int! = 10): [UserType!]!
    }

    type Mutation {
      updateAccount(accountInput: AccountUpdateInput!): AccountType!
      updateCard(cardInput: CardUpdateInput!): CardType!
      addTransaction(transactionInput: TransactionAddInput!): TransactionType!
      payBill(billInput: BillPaymentInput!): TransactionType!
    }

Note that Strawberry converts Python ``snake_case`` to GraphQL
``camelCase`` automatically for field names, while GraphQL type
names retain their original casing.

Error Handling and Information Disclosure Safeguards
----------------------------------------------------
The schema is registered with a :class:`strawberry.extensions.MaskErrors`
extension that inspects every :class:`graphql.GraphQLError` produced
during query/mutation execution and replaces the error message with a
generic placeholder when the underlying Python exception is a
SQLAlchemy error. This prevents SQL statements, schema identifiers,
bind-parameter values, and internal driver stack frames from being
serialised into the GraphQL ``errors`` array — a defence-in-depth
control that addresses the QA finding of SQL + parameter leakage in
GraphQL error responses (AAP §0.7.2 security requirement).

Legitimate exceptions that carry no sensitive internals — for example,
:class:`PermissionError` raised by admin-only resolvers, and the
plain :class:`Exception` messages raised by :mod:`~src.api.graphql.mutations`
from service-layer responses (e.g. ``"Account not found"``) — are
passed through unchanged so clients can still act on business
outcomes. The predicate below is deliberately conservative: it masks
only the known-leaky SQLAlchemy exception family, leaving every
other error type untouched.

Auto-Generated Schema
---------------------
The :class:`strawberry.Schema` exposed by this module is ready for
mounting via :class:`strawberry.fastapi.GraphQLRouter`. The schema
SDL can be inspected at runtime by calling ``str(schema)``, and
GraphQL introspection is enabled by default — clients may introspect
the schema by sending a standard introspection query to
``/graphql``.

Authentication
--------------
The ``/graphql`` path is NOT on the PUBLIC_PATHS allow-list of the
:class:`~src.api.middleware.auth.JWTAuthMiddleware`, so both queries
and mutations require a valid Bearer token. Unauthenticated requests
are rejected with HTTP 401 by the middleware BEFORE Strawberry is
invoked — the resolvers themselves can therefore assume an
authenticated caller.

No admin-only GraphQL operations are defined at present. If a
future operation requires admin privileges, field-level
authorization should be added via a Strawberry ``permission_classes``
parameter rather than by extending the middleware's
ADMIN_ONLY_PREFIXES (which is path-based only and would gate all
GraphQL operations if ``/graphql`` were added to it).

Source: Composition of :mod:`~src.api.graphql.queries` (read-side)
and :mod:`~src.api.graphql.mutations` (write-side) — Mainframe-to-
Cloud migration (AAP §0.5.1).
"""

from __future__ import annotations

import logging

import strawberry
from graphql import GraphQLError
from sqlalchemy.exc import SQLAlchemyError
from strawberry.extensions import MaskErrors
from strawberry.fastapi import GraphQLRouter

from src.api.graphql.mutations import Mutation
from src.api.graphql.queries import Query

# ----------------------------------------------------------------------------
# Module-level logger — emits structured diagnostics for masked errors so
# operators retain full SQL/stack visibility on the server side while
# clients receive a sanitised generic message.
# ----------------------------------------------------------------------------
logger: logging.Logger = logging.getLogger(__name__)


# ============================================================================
# Error masking predicate and generic message
# ============================================================================
# The GraphQL error surface is a known information-disclosure vector:
# Strawberry's default behavior is to serialise the full ``str(exc)``
# of any exception raised inside a resolver into the ``errors[*].message``
# field of the JSON response. For SQLAlchemy exceptions this includes
# the complete rendered SQL statement and the bind-parameter values —
# which may contain PII (account IDs, card numbers, monetary amounts)
# and leaks the internal schema (table and column names) to untrusted
# callers.
#
# The :class:`strawberry.extensions.MaskErrors` extension addresses
# this by giving us a hook point in the post-execution pipeline where
# we can decide, per-error, whether to replace the outgoing message
# with a generic placeholder. The predicate below returns ``True`` ONLY
# for SQLAlchemy's exception hierarchy (and its :class:`~sqlalchemy.exc.DBAPIError`
# subtree, which covers wrapped driver-level errors), so:
#
# * Database failures (connectivity issues, constraint violations,
#   schema mismatches, etc.) → masked to ``_MASKED_ERROR_MESSAGE``.
#   The original exception is still logged server-side with the full
#   stack trace via :meth:`logging.Logger.exception` inside the
#   resolver (queries.py / mutations.py), so operators retain full
#   diagnostic context.
#
# * Business-logic errors raised from mutations (plain ``Exception``
#   with messages like ``"Account not found"`` or ``"Invalid account
#   update payload: ..."``) → passed through unchanged. These are
#   safe by construction — the messages are authored by the
#   application and contain only user-facing text.
#
# * Authorization failures (:class:`PermissionError` raised by
#   ``_require_admin``) → passed through unchanged. The message is
#   always the literal string ``"Admin privileges required"`` and
#   must reach the client so UI layers can prompt re-authentication.
#
# * Unexpected / unknown errors → passed through unchanged. This is
#   deliberate: masking an unknown error class risks hiding a
#   genuine application bug from the client. A future tightening
#   pass can widen the mask predicate once the error inventory is
#   exhaustively documented, but the current default preserves
#   debuggability while addressing the specific SQL-leak vector.
# ============================================================================
_MASKED_ERROR_MESSAGE: str = (
    "A database error occurred while processing the request. "
    "The incident has been logged. Please retry the operation or "
    "contact an administrator if the issue persists."
)


def _should_mask_error(error: GraphQLError) -> bool:
    """Predicate selecting which :class:`GraphQLError` instances to mask.

    Returns ``True`` when the wrapped ``original_error`` is a
    SQLAlchemy exception (including any :class:`~sqlalchemy.exc.DBAPIError`
    subclass such as :class:`~sqlalchemy.exc.IntegrityError`,
    :class:`~sqlalchemy.exc.OperationalError`, or
    :class:`~sqlalchemy.exc.StaleDataError`). Returns ``False`` for
    every other exception type so that authorization failures
    (:class:`PermissionError`), plain :class:`Exception` business
    messages raised by mutations, and the null-``original_error``
    case (GraphQL protocol errors such as validation failures) pass
    through unchanged.

    Parameters
    ----------
    error : GraphQLError
        The executor-produced error object under consideration. Its
        ``original_error`` attribute is the wrapped Python exception;
        it may be ``None`` for protocol-level errors (unknown field,
        type mismatch, etc.), in which case the error is left as-is.

    Returns
    -------
    bool
        ``True`` to replace the error's message with
        :data:`_MASKED_ERROR_MESSAGE`; ``False`` to leave the error
        untouched.

    Notes
    -----
    When this predicate returns ``True``, a server-side warning is
    also logged with the unsanitised original exception so operators
    have full diagnostic visibility. The client sees only the
    sanitised generic message.
    """
    original: BaseException | None = error.original_error
    if isinstance(original, SQLAlchemyError):
        # Emit a single structured warning with the original exception
        # type so server-side operators can correlate client incidents
        # with the full stack trace in the logs. The resolver itself
        # has already called ``logger.exception(...)`` with the
        # request context, so this log line provides only the high-
        # level marker for the sanitisation event.
        logger.warning(
            "GraphQL error masked: SQLAlchemy exception sanitised",
            extra={
                "operation": "graphql_error_mask",
                "original_error_type": type(original).__name__,
                "path": list(error.path) if error.path is not None else None,
            },
        )
        return True
    return False


# ============================================================================
# Strawberry schema
# ============================================================================
# The :data:`schema` object is the single source of truth for the
# CardDemo GraphQL surface. It is consumed by :mod:`src.api.main`
# where it is wrapped in a :class:`strawberry.fastapi.GraphQLRouter`
# and mounted at ``/graphql``.
#
# Keep the Schema construction signature minimal — Query and Mutation
# are the only two root types, and the four GraphQL object types
# (:class:`~src.api.graphql.types.account_type.AccountType`,
# :class:`~src.api.graphql.types.card_type.CardType`,
# :class:`~src.api.graphql.types.transaction_type.TransactionType`,
# :class:`~src.api.graphql.types.user_type.UserType`) are discovered
# transitively via type annotations on the resolvers. Adding an
# explicit ``types=[...]`` argument is unnecessary and would make
# the schema brittle to new types being added in the future.
#
# The :class:`strawberry.extensions.MaskErrors` extension is
# registered with the :func:`_should_mask_error` predicate so that
# SQLAlchemy exceptions that propagate out of resolvers are
# sanitised into a generic client-facing message before the GraphQL
# JSON response is serialised (see module docstring for rationale).
# ============================================================================
schema: strawberry.Schema = strawberry.Schema(
    query=Query,
    mutation=Mutation,
    extensions=[
        MaskErrors(
            should_mask_error=_should_mask_error,
            error_message=_MASKED_ERROR_MESSAGE,
        ),
    ],
)


# ============================================================================
# GraphQL router factory
# ============================================================================
# Replaces the CICS transaction dispatch (``EXEC CICS XCTL``) pattern
# from ``COMEN01C.cbl`` (Main Menu, F-002) and ``COADM01C.cbl``
# (Admin Menu, F-003) with a single GraphQL endpoint. In the legacy
# mainframe architecture, the menu programs transferred control to
# up to 14 downstream programs via ``EXEC CICS XCTL PROGRAM(...)``
# through the CICS COMMAREA (``COCOM01Y.cpy``). In the cloud-native
# architecture, every read and write operation across those programs
# is surfaced as a resolver on the single Strawberry schema created
# above; a GraphQL client navigates by sending a query or mutation to
# ``POST /graphql`` rather than by a program-to-program XCTL chain.
#
# :func:`get_graphql_router` is the canonical convenience factory
# that packages the schema into a :class:`strawberry.fastapi.GraphQLRouter`
# for mounting on a FastAPI app (``app.include_router(router,
# prefix="/graphql")``). ``src.api.main`` creates its own
# GraphQLRouter with a custom ``context_getter`` that threads an
# :class:`~sqlalchemy.ext.asyncio.AsyncSession` and the current user
# into ``info.context`` for resolver consumption; callers that do not
# need custom context (for example, unit tests or simple demos) can
# use this factory directly.
# ============================================================================
def get_graphql_router() -> GraphQLRouter:
    """Create the :class:`strawberry.fastapi.GraphQLRouter` for /graphql.

    Wraps the module-level :data:`schema` in a
    :class:`~strawberry.fastapi.GraphQLRouter` ready for mounting on a
    :class:`~fastapi.FastAPI` application. This is the convenience
    factory for consumers that do not require a custom
    ``context_getter`` — the resulting router exposes the full
    CardDemo GraphQL surface (Query + Mutation) with Strawberry's
    default context (only the HTTP ``Request`` and ``Response`` are
    available to resolvers via ``info.context``).

    :mod:`src.api.main` constructs its own :class:`GraphQLRouter` with
    a custom ``context_getter`` to inject the per-request
    :class:`~sqlalchemy.ext.asyncio.AsyncSession` and the
    authenticated user's :class:`~src.api.dependencies.CurrentUser`
    into ``info.context``; resolvers (see
    :func:`src.api.graphql.queries._get_session` and
    :func:`src.api.graphql.mutations._get_session`) read those values
    to perform database access and enforce authorization.

    Returns
    -------
    GraphQLRouter
        A fresh :class:`strawberry.fastapi.GraphQLRouter` bound to
        the module-level :data:`schema`. Each call returns a new
        router instance; the underlying :data:`schema` is shared
        (immutable after construction).

    Notes
    -----
    * Replaces the CICS transaction dispatch (``EXEC CICS XCTL``)
      pattern from COMEN01C.cbl and COADM01C.cbl with a single
      GraphQL endpoint.
    * The returned router is stateless with respect to application
      configuration — consumers that require database sessions or
      JWT authorization in ``info.context`` MUST construct their own
      :class:`GraphQLRouter` with a ``context_getter`` (see
      :mod:`src.api.main`).
    * GraphQL introspection is enabled by default on the returned
      router. Production deployments that need to disable
      introspection should either construct the router directly
      with ``graphql_ide=None`` or configure a custom schema with
      :meth:`strawberry.Schema.execute_sync` / ``validation_rules``.
    """
    return GraphQLRouter(schema)


# ----------------------------------------------------------------------------
# Public exports
# ----------------------------------------------------------------------------
# Both the :data:`schema` object and the :func:`get_graphql_router`
# convenience factory are exported from this module. Consumers must go
# through these symbols to access the Strawberry schema or to mount
# the GraphQL endpoint, which keeps the ``/graphql`` mount point in
# :mod:`src.api.main` decoupled from the internal structure of Query /
# Mutation / types.
#
# * :data:`schema` — raw :class:`strawberry.Schema` for advanced
#   integrations (custom ``GraphQLRouter`` with context injection, SDL
#   export, introspection tooling, etc.).
# * :func:`get_graphql_router` — default :class:`GraphQLRouter` for
#   simple mounting without custom context.
# ----------------------------------------------------------------------------
__all__ = ["schema", "get_graphql_router"]
