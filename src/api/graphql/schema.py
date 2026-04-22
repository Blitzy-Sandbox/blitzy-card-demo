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

* :class:`~src.api.graphql.queries.Query` — seven read-side resolvers
  (``account``, ``card``, ``cards``, ``transaction``, ``transactions``,
  ``user``, ``users``) converted from the read-oriented online CICS
  COBOL programs.
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

import strawberry

from src.api.graphql.mutations import Mutation
from src.api.graphql.queries import Query

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
# ============================================================================
schema: strawberry.Schema = strawberry.Schema(query=Query, mutation=Mutation)


# ----------------------------------------------------------------------------
# Public exports
# ----------------------------------------------------------------------------
# Only the :data:`schema` object is exported from this module. Consumers
# must go through this single symbol to access the Strawberry schema,
# which keeps the ``/graphql`` mount point in :mod:`src.api.main`
# decoupled from the internal structure of Query / Mutation / types.
# ----------------------------------------------------------------------------
__all__ = ["schema"]
