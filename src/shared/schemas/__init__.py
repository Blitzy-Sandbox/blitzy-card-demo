# ============================================================================
# Source: BMS mapsets (app/bms/*.bms) and symbolic map copybooks
#         (app/cpy-bms/*.CPY) — Mainframe-to-Cloud migration
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
"""Shared Pydantic v2 schemas — the REST/GraphQL contract layer.

This package replaces the CICS BMS (Basic Mapping Support) screen layer
that the mainframe application used to communicate with 3270 terminals.
Each BMS mapset — together with its generated symbolic-map copybook —
defined a screen's input (AI) and output (AO) fields, lengths, and
attributes. In the cloud target, those same contracts are expressed as
Pydantic v2 models that serve double duty as:

* FastAPI request / response models for the REST layer
  (``src/api/routers/``), carrying HTTP bodies and providing automatic
  OpenAPI documentation.

* GraphQL input / output types for the Strawberry resolver layer
  (``src/api/graphql/``), exposing the same fields through a typed
  GraphQL schema.

* JSON-serializable message envelopes between batch jobs and the API
  where applicable.

The one-to-one mapping between a BMS mapset and a Pydantic schema
ensures each screen in the legacy 3270 UI has a direct, testable
equivalent in the modernized API — preserving the behavioral parity
required by AAP §0.7.1 ("Preserve all existing functionality exactly
as-is").

Submodules
----------
auth_schema
    Sign-on request and JWT-token response models. Derived from
    ``app/cpy-bms/COSGN00.CPY`` (sign-on symbolic map) and
    ``app/cpy/COCOM01Y.cpy`` (CICS COMMAREA session payload, now
    encoded as JWT claims).

account_schema
    Account view and account update request/response models. Derived
    from ``app/cpy-bms/COACTVW.CPY`` (F-004 Account View, 3-entity
    join) and ``app/cpy-bms/COACTUP.CPY`` (F-005 Account Update,
    SYNCPOINT ROLLBACK-protected dual write).

card_schema
    Card list (7 rows/page), card detail, and card update models.
    Derived from ``app/cpy-bms/COCRDLI.CPY`` (F-006),
    ``app/cpy-bms/COCRDSL.CPY`` (F-007), and ``app/cpy-bms/COCRDUP.CPY``
    (F-008 — optimistic concurrency).

customer_schema
    Customer record models for use by the Account View join and the
    Customer-view read path. Mirrors the ``CVCUS01Y.cpy`` record layout
    (9-digit PK customer ID; 500-byte record on VSAM).

transaction_schema
    Transaction list (10 rows/page), transaction detail, and
    transaction add models. Derived from ``app/cpy-bms/COTRN00.CPY``
    (F-009), ``app/cpy-bms/COTRN01.CPY`` (F-010), and
    ``app/cpy-bms/COTRN02.CPY`` (F-011 — auto-ID, xref resolution).

bill_schema
    Bill payment request and response. Derived from
    ``app/cpy-bms/COBIL00.CPY`` (F-012 — atomic dual-write:
    Transaction INSERT + Account balance UPDATE).

report_schema
    Report submission request and response. Derived from
    ``app/cpy-bms/CORPT00.CPY`` (F-022 — submits to SQS FIFO in place
    of the CICS ``WRITEQ TD JOBS`` bridge).

user_schema
    User list, user add, user update, and user delete models.
    Derived from ``app/cpy-bms/COUSR00.CPY`` / ``COUSR01.CPY`` /
    ``COUSR02.CPY`` / ``COUSR03.CPY`` (F-018 through F-021 — BCrypt
    password hashing preserved).

Design Notes
------------
* **Pydantic v2 (``>=2.10,<3.0``)**: All schemas use the Rust-backed
  ``pydantic-core`` engine for high-throughput validation. Per AAP
  §0.6.1, Pydantic v2 is the mandated version.

* **Decimal precision**: Monetary fields map to Python
  ``decimal.Decimal`` with explicit ``max_digits`` and ``decimal_places``
  — mirroring COBOL ``PIC S9(n)V99`` semantics. Float is forbidden for
  money (AAP §0.7.2).

* **Immutable responses**: Response models that leave the API are frozen
  via ``model_config = ConfigDict(frozen=True)`` where stability is
  important. Request models remain mutable so FastAPI can apply default
  values and coercions during parsing.

* **Password exclusion**: User response schemas NEVER include the
  password field, even when the underlying ORM entity does. Verified
  by QA Checkpoint 1, Feature 7.

* **Case convention**: Field names use ``snake_case`` in Python; the
  original COBOL field names (ACCT-ID, CARD-NUM, etc.) are preserved
  as JSON aliases via ``Field(alias=...)`` where backward compatibility
  with a legacy client is needed. Fresh clients see the pythonic name.

* **Lazy loading**: This package init performs NO imports of its
  submodules. Consumers must import the specific schema they need::

      from src.shared.schemas.account_schema import AccountViewResponse
      from src.shared.schemas.auth_schema import SignOnRequest

  This keeps the API service startup fast and mirrors the pattern used
  by ``src.shared.models`` and ``src.shared.constants``.

* **Python 3.11+ typing**: Type hints use PEP 604 union syntax
  (``str | None``), PEP 585 built-in generics (``list[str]``), and
  ``typing.Annotated`` where constraints are attached.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/shared/schemas/`` layout)
AAP §0.5.1 — File-by-File Transformation Plan (schema file mappings)
AAP §0.6.1 — Dependencies (Pydantic v2 specification)
AAP §0.7.2 — Implementation rules (Decimal precision, security)
"""

# ----------------------------------------------------------------------------
# Public submodule re-export list.
#
# Each schema must be imported from its specific submodule rather than
# the package root — this matches the lazy-loading contract used by the
# rest of ``src.shared.*``.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "auth_schema",
    "account_schema",
    "card_schema",
    "customer_schema",
    "transaction_schema",
    "bill_schema",
    "report_schema",
    "user_schema",
]
