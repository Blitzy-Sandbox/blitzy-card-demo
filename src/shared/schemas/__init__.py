# ============================================================================
# src/shared/schemas — Pydantic v2 API request/response schemas
# ============================================================================
# Converted from COBOL BMS symbolic map copybooks (app/cpy-bms/*.CPY)
# All monetary fields use Decimal type (never float) matching COBOL
# PIC S9(n)V99 semantics — see AAP §0.7.2.
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
defined a screen's input (``AI``) and output (``AO``) fields, lengths,
and attributes. In the cloud target, those same contracts are expressed
as Pydantic v2 models that serve double duty as:

* FastAPI request / response models for the REST layer
  (``src/api/routers/``), carrying HTTP bodies and powering the automatic
  OpenAPI documentation.

* GraphQL input / output types for the Strawberry resolver layer
  (``src/api/graphql/``), exposing the same fields through a typed
  GraphQL schema.

* JSON-serializable message envelopes between batch jobs and the API
  where applicable (e.g., the SQS FIFO report-submission payload).

The one-to-one mapping between a BMS mapset and a Pydantic schema
ensures each screen in the legacy 3270 UI has a direct, testable
equivalent in the modernized API — preserving the behavioral parity
required by AAP §0.7.1 ("Preserve all existing functionality exactly
as-is").

Submodules
----------
admin_schema
    Admin menu (``GET /admin/menu``) and admin status
    (``GET /admin/status``) response models (F-003). Derived from
    ``app/cpy-bms/COADM01.CPY`` (admin symbolic map) and
    ``app/cpy/COADM02Y.cpy`` (the 4-entry ``CDEMO-ADMIN-OPTIONS-DATA``
    navigation table). Added to satisfy the code-review MINOR finding
    on typed response models (previously ``dict[str, Any]``). Exposes:
    :class:`AdminMenuOption`, :class:`AdminMenuResponse`,
    :class:`AdminStatusResponse`.

auth_schema
    Sign-on request, JWT-token response, token payload, and sign-out
    response models. Derived from ``app/cpy-bms/COSGN00.CPY`` (sign-on
    symbolic map) and ``app/cpy/COCOM01Y.cpy`` (CICS COMMAREA session
    payload, now encoded as JWT claims). Exposes: :class:`SignOnRequest`,
    :class:`SignOnResponse`, :class:`TokenPayload`,
    :class:`SignOutResponse`.

account_schema
    Account view and account update request/response models. Derived
    from ``app/cpy-bms/COACTVW.CPY`` (F-004 Account View — 3-entity
    join) and ``app/cpy-bms/COACTUP.CPY`` (F-005 Account Update —
    ``SYNCPOINT ROLLBACK``-protected dual write). Exposes:
    :class:`AccountViewResponse`, :class:`AccountUpdateRequest`,
    :class:`AccountUpdateResponse`.

card_schema
    Card list (7 rows/page), card detail, and card update models.
    Derived from ``app/cpy-bms/COCRDLI.CPY`` (F-006),
    ``app/cpy-bms/COCRDSL.CPY`` (F-007), and
    ``app/cpy-bms/COCRDUP.CPY`` (F-008 — optimistic concurrency).
    Exposes: :class:`CardListRequest`, :class:`CardListItem`,
    :class:`CardListResponse`, :class:`CardDetailResponse`,
    :class:`CardUpdateRequest`, :class:`CardUpdateResponse`.

customer_schema
    Customer record models for use by the Account View join and any
    standalone customer-data transfer paths. Mirrors the
    ``app/cpy/CVCUS01Y.cpy`` record layout (9-digit PK customer ID,
    500-byte record on VSAM). Exposes: :class:`CustomerResponse`,
    :class:`CustomerCreateRequest`.

transaction_schema
    Transaction list (10 rows/page), transaction detail, and
    transaction add models. Derived from ``app/cpy-bms/COTRN00.CPY``
    (F-009), ``app/cpy-bms/COTRN01.CPY`` (F-010), and
    ``app/cpy-bms/COTRN02.CPY`` (F-011 — auto-ID with xref resolution).
    Exposes: :class:`TransactionListRequest`,
    :class:`TransactionListItem`, :class:`TransactionListResponse`,
    :class:`TransactionDetailResponse`, :class:`TransactionAddRequest`,
    :class:`TransactionAddResponse`.

bill_schema
    Bill payment request and response. Derived from
    ``app/cpy-bms/COBIL00.CPY`` (F-012 — atomic dual-write:
    ``Transaction`` INSERT + ``Account`` balance UPDATE). Exposes:
    :class:`BillPaymentRequest`, :class:`BillPaymentResponse`.

report_schema
    Report submission request, response, and type enum. Derived from
    ``app/cpy-bms/CORPT00.CPY`` (F-022 — submits to an SQS FIFO queue
    in place of the CICS ``WRITEQ TD JOBS`` bridge). Exposes:
    :class:`ReportType`, :class:`ReportSubmissionRequest`,
    :class:`ReportSubmissionResponse`.

user_schema
    User list, user add, user update, and user delete models. Derived
    from ``app/cpy-bms/COUSR00.CPY`` / ``COUSR01.CPY`` /
    ``COUSR02.CPY`` / ``COUSR03.CPY`` (F-018 through F-021 — BCrypt
    password hashing preserved). Exposes: :class:`UserListRequest`,
    :class:`UserListItem`, :class:`UserListResponse`,
    :class:`UserCreateRequest`, :class:`UserCreateResponse`,
    :class:`UserUpdateRequest`, :class:`UserUpdateResponse`,
    :class:`UserDeleteResponse`.

Convenience Re-exports
----------------------
For ergonomics and symmetry with the sibling packages
``src.shared.models`` and ``src.shared.constants``, every public
schema class from every submodule is re-exported at the package root
so callers can write::

    from src.shared.schemas import (
        AccountViewResponse,
        CardListResponse,
        BillPaymentRequest,
        ReportType,
        SignOnRequest,
    )

instead of importing from each submodule individually. The re-export
list matches the ``exports`` block declared for this file in
AAP §0.5.1 — originally 34 public symbols (33 Pydantic ``BaseModel``
subclasses plus the single ``ReportType`` string enum). Three
additional symbols (:class:`AdminMenuOption`, :class:`AdminMenuResponse`,
and :class:`AdminStatusResponse`) were added from the new
``admin_schema`` submodule to satisfy the code-review MINOR finding
that ``admin_router.py`` returned untyped ``dict[str, Any]`` values.
Total public surface is now 37 symbols.

Design Notes
------------
* **Pydantic v2 (``>=2.10,<3.0``)**: All schemas use the Rust-backed
  ``pydantic-core`` engine for high-throughput validation. Per AAP
  §0.6.1, Pydantic v2 is the mandated version.

* **Decimal precision**: Monetary fields map to Python
  :class:`decimal.Decimal` with explicit ``max_digits`` and
  ``decimal_places`` matching COBOL ``PIC S9(n)V99`` semantics. Float
  is forbidden for money per AAP §0.7.2.

* **Field-length parity**: String fields use ``Field(max_length=n)``
  with ``n`` matching the originating COBOL ``PIC X(n)`` size. This
  preserves the wire-format envelope sizes enforced by the legacy
  3270 screens.

* **Password exclusion**: User response schemas NEVER include the
  password field, even when the underlying ORM entity does. BCrypt
  hashing (``passlib[bcrypt]``) is performed at the service layer.

* **User-type discipline**: ``user_type`` is validated as ``'A'``
  (admin) or ``'U'`` (user) per the ``CDEMO-USR-ADMIN`` /
  ``CDEMO-USR-NORMAL`` 88-level conditions from
  ``app/cpy/COCOM01Y.cpy``.

* **Python 3.11+ typing**: Type hints use PEP 604 union syntax
  (``str | None``) and PEP 585 built-in generics (``list[str]``).
  Aligned with the AWS Glue 5.1 runtime (Python 3.11, Spark 3.5.6)
  and the FastAPI container base image (``python:3.11-slim``) per
  AAP §0.6.

* **Eager imports are cheap**: Each submodule imports only
  ``pydantic`` (already loaded by the FastAPI app on startup),
  ``decimal``, ``typing``, and ``enum``. Importing this package pulls
  all eight submodules into memory but the cost is a few hundred
  microseconds for class construction — negligible relative to the
  Uvicorn ECS cold-start path.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/shared/schemas/``
layout).
AAP §0.5.1 — File-by-File Transformation Plan (schema file mappings
and exports contract).
AAP §0.6.1 — Dependencies (Pydantic v2 specification).
AAP §0.7.2 — Implementation rules (``Decimal`` precision, security
requirements).
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Re-exports from .account_schema — source: app/cpy-bms/COACTVW.CPY
# (F-004 Account View) + app/cpy-bms/COACTUP.CPY (F-005 Account Update).
# ---------------------------------------------------------------------------
# AccountViewResponse  — 31-field account + joined customer projection
#                         with 5 Decimal monetary fields (credit_limit,
#                         cash_credit_limit, current_balance,
#                         current_cycle_credit, current_cycle_debit).
# AccountUpdateRequest  — Segmented date/phone/SSN update payload.
# AccountUpdateResponse — Inherits from AccountViewResponse — same
#                         post-update projection.
from .account_schema import (
    AccountUpdateRequest,
    AccountUpdateResponse,
    AccountViewResponse,
)

# ---------------------------------------------------------------------------
# Re-exports from .admin_schema — source: app/cpy-bms/COADM01.CPY
# (F-003 Admin Menu) + app/cpy/COADM02Y.cpy (admin options table).
# ---------------------------------------------------------------------------
# AdminMenuOption      — One row of the 4-entry admin navigation table
#                         (option, label, endpoint, method).
# AdminMenuResponse    — GET /admin/menu payload (menu_title + options).
# AdminStatusResponse  — GET /admin/status payload (status, user).
from .admin_schema import (
    AdminMenuOption,
    AdminMenuResponse,
    AdminStatusResponse,
)

# ---------------------------------------------------------------------------
# Re-exports from .auth_schema — source: app/cpy-bms/COSGN00.CPY
# (F-001 Sign-on) + app/cpy/COCOM01Y.cpy (CICS COMMAREA → JWT claims).
# ---------------------------------------------------------------------------
# SignOnRequest    — user_id (8) + password (8) credentials.
# SignOnResponse   — access_token + token_type + user_id + user_type.
# TokenPayload     — JWT claim bundle (sub, user_type, exp).
# SignOutResponse  — Logout confirmation message.
from .auth_schema import (
    SignOnRequest,
    SignOnResponse,
    SignOutResponse,
    TokenPayload,
)

# ---------------------------------------------------------------------------
# Re-exports from .bill_schema — source: app/cpy-bms/COBIL00.CPY
# (F-012 Bill Payment — atomic dual-write).
# ---------------------------------------------------------------------------
# BillPaymentRequest   — acct_id + amount (Decimal, > 0).
# BillPaymentResponse  — acct_id + amount + current_balance (all
#                         Decimal) + confirm + message.
from .bill_schema import (
    BillPaymentRequest,
    BillPaymentResponse,
)

# ---------------------------------------------------------------------------
# Re-exports from .card_schema — source: app/cpy-bms/COCRDLI.CPY
# (F-006 Card List, 7 rows/page) + app/cpy-bms/COCRDSL.CPY (F-007 Card
# Detail) + app/cpy-bms/COCRDUP.CPY (F-008 Card Update, optimistic
# concurrency).
# ---------------------------------------------------------------------------
# CardListRequest     — Filter + pagination.
# CardListItem        — One row in the 7-row list (selected, account,
#                        card_number, card_status).
# CardListResponse    — Paginated list envelope.
# CardDetailResponse  — Single-card detail view.
# CardUpdateRequest   — Updatable card fields.
# CardUpdateResponse  — Post-update confirmation.
from .card_schema import (
    CardDetailResponse,
    CardListItem,
    CardListRequest,
    CardListResponse,
    CardUpdateRequest,
    CardUpdateResponse,
)

# ---------------------------------------------------------------------------
# Re-exports from .customer_schema — source: app/cpy/CVCUS01Y.cpy
# (CUSTOMER-RECORD, 500 bytes).
# ---------------------------------------------------------------------------
# CustomerResponse       — Full customer projection (18 fields
#                           including address, phone, SSN, DOB, FICO).
# CustomerCreateRequest  — Create/update customer payload.
from .customer_schema import (
    CustomerCreateRequest,
    CustomerResponse,
)

# ---------------------------------------------------------------------------
# Re-exports from .report_schema — source: app/cpy-bms/CORPT00.CPY
# (F-022 Report Submission — SQS FIFO queue bridge).
# ---------------------------------------------------------------------------
# ReportType                 — String enum: monthly / yearly / custom.
# ReportSubmissionRequest    — report_type + start_date + end_date.
# ReportSubmissionResponse   — report_id + confirmation envelope.
from .report_schema import (
    ReportSubmissionRequest,
    ReportSubmissionResponse,
    ReportType,
)

# ---------------------------------------------------------------------------
# Re-exports from .transaction_schema — source: app/cpy-bms/COTRN00.CPY
# (F-009 Transaction List, 10 rows/page) + app/cpy-bms/COTRN01.CPY
# (F-010 Transaction Detail) + app/cpy-bms/COTRN02.CPY (F-011
# Transaction Add — auto-ID + xref resolution).
# ---------------------------------------------------------------------------
# TransactionListRequest     — Filter + pagination (page_size=10).
# TransactionListItem        — One row (id, date, description, amount).
# TransactionListResponse    — Paginated list envelope.
# TransactionDetailResponse  — Full-field transaction detail (350B).
# TransactionAddRequest      — New transaction payload (no tran_id
#                               — auto-generated).
# TransactionAddResponse     — Post-insert confirmation with generated
#                               tran_id.
from .transaction_schema import (
    TransactionAddRequest,
    TransactionAddResponse,
    TransactionDetailResponse,
    TransactionListItem,
    TransactionListRequest,
    TransactionListResponse,
)

# ---------------------------------------------------------------------------
# Re-exports from .user_schema — source: app/cpy-bms/COUSR00.CPY
# (F-018 User List) + app/cpy-bms/COUSR01.CPY (F-019 User Add) +
# app/cpy-bms/COUSR02.CPY (F-020 User Update) + app/cpy-bms/COUSR03.CPY
# (F-021 User Delete) + app/cpy/CSUSR01Y.cpy (80-byte user security
# record).
# ---------------------------------------------------------------------------
# UserListRequest      — Filter + pagination.
# UserListItem         — One row (user_id, first_name, last_name,
#                         user_type).
# UserListResponse     — Paginated list envelope.
# UserCreateRequest    — user_id + first_name + last_name + password
#                         + user_type ('A'/'U').
# UserCreateResponse   — Post-create confirmation (no password).
# UserUpdateRequest    — Partial-update payload (no user_id — from URL
#                         path).
# UserUpdateResponse   — Post-update confirmation (no password).
# UserDeleteResponse   — Pre/post-delete display (no password).
from .user_schema import (
    UserCreateRequest,
    UserCreateResponse,
    UserDeleteResponse,
    UserListItem,
    UserListRequest,
    UserListResponse,
    UserUpdateRequest,
    UserUpdateResponse,
)

# ---------------------------------------------------------------------------
# Public re-export list.
#
# The explicit ``__all__`` declaration serves three purposes:
#
# 1. It marks the imported symbols as intentional re-exports, satisfying
#    the ``ruff`` ``F401`` ("unused import") lint rule without the need
#    for per-line suppression directives.
# 2. It makes ``from src.shared.schemas import *`` — when used in
#    interactive sessions, tests, or docs examples — expose exactly
#    the 37 public contract classes below and nothing else (no
#    accidental leakage of ``BaseModel``, ``ConfigDict``, ``Decimal``,
#    or ``Enum`` which are re-imported as module attributes by the
#    submodules).
# 3. It documents the complete public API surface of the package in
#    one place. Originally the ``exports`` block in AAP §0.5.1
#    declared 34 entries (33 Pydantic ``BaseModel`` subclasses plus
#    1 ``ReportType`` string enum). Three additional symbols —
#    :class:`AdminMenuOption`, :class:`AdminMenuResponse`, and
#    :class:`AdminStatusResponse` — were added from the new
#    ``admin_schema`` submodule to satisfy the code-review MINOR
#    finding that ``admin_router.py`` returned untyped
#    ``dict[str, Any]`` values. Total public surface is now 37.
#
# Entries are listed in alphabetical order for readability per the
# agent prompt directive — no COBOL / BMS source-file grouping is
# applied here because consumers use class names only.
# ---------------------------------------------------------------------------
__all__: list[str] = [
    "AccountUpdateRequest",
    "AccountUpdateResponse",
    "AccountViewResponse",
    "AdminMenuOption",
    "AdminMenuResponse",
    "AdminStatusResponse",
    "BillPaymentRequest",
    "BillPaymentResponse",
    "CardDetailResponse",
    "CardListItem",
    "CardListRequest",
    "CardListResponse",
    "CardUpdateRequest",
    "CardUpdateResponse",
    "CustomerCreateRequest",
    "CustomerResponse",
    "ReportSubmissionRequest",
    "ReportSubmissionResponse",
    "ReportType",
    "SignOnRequest",
    "SignOnResponse",
    "SignOutResponse",
    "TokenPayload",
    "TransactionAddRequest",
    "TransactionAddResponse",
    "TransactionDetailResponse",
    "TransactionListItem",
    "TransactionListRequest",
    "TransactionListResponse",
    "UserCreateRequest",
    "UserCreateResponse",
    "UserDeleteResponse",
    "UserListItem",
    "UserListRequest",
    "UserListResponse",
    "UserUpdateRequest",
    "UserUpdateResponse",
]
