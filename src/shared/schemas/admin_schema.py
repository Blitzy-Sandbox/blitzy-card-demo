# ============================================================================
# Source: COBOL BMS symbolic map COADM01.CPY  (Admin menu screen, F-003)
#         + app/cpy/COADM02Y.cpy               (Admin menu options table —
#                                                CDEMO-ADMIN-OPTIONS-DATA, 4 rows)
#         + app/cbl/COADM01C.cbl               (Admin menu dispatcher program —
#                                                CICS transaction CA00)
#         + app/cpy/COCOM01Y.cpy               (CARDDEMO-COMMAREA —
#                                                CDEMO-USER-TYPE 88-level gate)
# ============================================================================
# Mainframe-to-Cloud migration: CICS SEND MAP + XCTL-dispatch table →
# REST JSON response body validated by Pydantic v2.
#
# Replaces:
#   * The BMS symbolic-map output fields from ``COADM1AO`` previously
#     rendered by CICS ``SEND MAP('COADM1A') MAPSET('COADM01')`` in
#     ``COADM01C.cbl``:
#       - TITLE01O PIC X(40) — "Administrative Menu" title
#       - OPTN001O..OPTN012O PIC X(35) — 4 populated option labels
#         (only the first 4 of the 12 BMS ``OPTNnnnO`` fields are
#         backed by ``CDEMO-ADMIN-OPTIONS-DATA``; options 5-12 are
#         padded with SPACE in the COBOL program)
#       - ERRMSGO PIC X(78) — not used by /admin/menu (handled by the
#         FastAPI global exception handler instead)
#   * The XCTL-dispatch table from ``app/cpy/COADM02Y.cpy`` lines 24-42
#     where each row carried (option_number, 35-char label, 8-char
#     COBOL program name). The COBOL program name is DROPPED in the
#     cloud form and replaced with the equivalent REST (endpoint,
#     method) pair so self-describing clients can invoke the target
#     endpoint directly — see ``AdminMenuOption`` below.
#
# Source-line references (for traceability back to the COBOL baseline):
#   * COADM02Y.cpy lines 24-27  → Option 1 (User List)
#   * COADM02Y.cpy lines 29-32  → Option 2 (User Add)
#   * COADM02Y.cpy lines 34-37  → Option 3 (User Update)
#   * COADM02Y.cpy lines 39-42  → Option 4 (User Delete)
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
"""Pydantic v2 schemas for the CardDemo Admin Menu API (Feature F-003).

Converts the BMS symbolic-map copybook ``app/cpy-bms/COADM01.CPY`` and the
admin-options table ``app/cpy/COADM02Y.cpy`` into a trio of transport schemas
that drive the two admin-only REST endpoints mounted under ``/admin``:

* ``GET /admin/menu``   — returns the 4-option admin navigation (replaces
  ``EXEC CICS SEND MAP('COADM1A') MAPSET('COADM01')`` in ``COADM01C.cbl``).
* ``GET /admin/status`` — returns a lightweight admin-only liveness probe
  (cloud-native addition — no direct COBOL equivalent).

This module was added to address the code-review MINOR finding
"admin_router.py returns ``dict[str, Any]`` rather than typed Pydantic
response models. Reduces OpenAPI schema quality and client-side type
safety." By wiring these classes as ``response_model=`` on the router,
the generated ``/openapi.json`` contains full field-level schema
metadata for both endpoints, allowing typed-client generation via
``openapi-typescript`` / ``openapi-generator`` without hand-written
models.

BMS → Python Field Mapping
--------------------------
======================================  ===================  =========================================
BMS / COBOL Field                       Py Class             Python Field
======================================  ===================  =========================================
TITLE01O ``PIC X(40)``                  AdminMenuResponse    ``AdminMenuResponse.menu_title``
``CDEMO-ADMIN-OPT-NUM PIC 9(02)``       AdminMenuOption      ``AdminMenuOption.option``
``CDEMO-ADMIN-OPT-NAME PIC X(35)``      AdminMenuOption      ``AdminMenuOption.label``
``CDEMO-ADMIN-OPT-PGMNAME PIC X(08)``   AdminMenuOption      ``AdminMenuOption.endpoint`` +
                                                             ``AdminMenuOption.method``
                                                             (mapped from COBOL program name to
                                                             REST route/method pair — see Design
                                                             Notes below)
``CDEMO-ADMIN-OPTIONS`` (table of 4)    AdminMenuResponse    ``AdminMenuResponse.options``
(none — cloud-native addition)          AdminStatusResponse  ``AdminStatusResponse.status``
                                                             (literal ``"operational"``)
``CDEMO-USER-ID PIC X(8)`` (from        AdminStatusResponse  ``AdminStatusResponse.user``
 COCOM01Y.cpy COMMAREA)
======================================  ===================  =========================================

Design Notes
------------
* **Response-only schemas** — both endpoints accept no request body
  (they are plain ``GET`` requests authenticated via the JWT
  ``Authorization: Bearer`` header). No ``*Request`` class is exposed
  from this module.
* **Program-name → REST route mapping** — the legacy ``COADM02Y.cpy``
  rows carried an 8-character COBOL program name (``COUSR00C``,
  ``COUSR01C``, ``COUSR02C``, ``COUSR03C``) that CICS would ``XCTL``
  to when the user selected an option. In the stateless REST
  architecture, clients invoke the next endpoint directly, so the
  program name is replaced with the equivalent ``(endpoint, method)``
  pair:

  ============  ============  ==========================  ========
  COBOL pgm     Option #      REST endpoint               Method
  ============  ============  ==========================  ========
  COUSR00C      1             ``/users``                  GET
  COUSR01C      2             ``/users``                  POST
  COUSR02C      3             ``/users/{user_id}``        PUT
  COUSR03C      4             ``/users/{user_id}``        DELETE
  ============  ============  ==========================  ========

* **Stateless design** — ``AdminMenuResponse`` intentionally does
  NOT echo the ``CURDATEO`` / ``CURTIMEO`` / ``PGMNAMEO`` / ``TRNNAMEO``
  fields that COADM01C populated via ``POPULATE-HEADER-INFO``
  (lines 202-221). Those were terminal chrome — the modern client
  renders its own date/time — so forwarding them would be a
  compatibility-break rather than a parity-preservation. The only
  header field retained is ``menu_title`` (equivalent to BMS
  ``TITLE01O``) because clients use it to drive screen-level
  heading rendering.
* **Fixed-option count** — the admin menu is a static 4-option list
  (``CDEMO-ADMIN-OPT-COUNT = 4`` per the COBOL declaration). The
  Pydantic schema does NOT constrain ``options`` length to exactly 4
  because a future cloud-native revision may add new admin entries
  (e.g., "System Status", "Audit Log"), and over-constraining would
  force schema churn on every addition.
* **Forward-compatible ``status`` literal** — ``AdminStatusResponse.status``
  is a free-form ``str`` rather than a constrained Literal so that
  a future revision may evolve to ``"degraded"`` / ``"maintenance"``
  without breaking the response contract. The router currently
  always returns ``"operational"``.
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length/description constraints.
  No validators are needed — these are pure server-constructed
  response payloads; the server populates every field with known
  valid values.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and
  the FastAPI / Uvicorn container image).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (F-003, COADM01C.cbl)
AAP §0.4.1 — Refactored Structure Planning (``admin_schema.py`` — added
to satisfy code-review MINOR finding on typed response models).
AAP §0.5.1 — File-by-File Transformation Plan (admin_router.py row).
AAP §0.7.1 — Refactoring-Specific Rules ("Document all technology-
specific changes with clear comments" — see the program-name →
REST route rationale above).
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

# ---------------------------------------------------------------------------
# Private module constants — COBOL field widths from COADM02Y.cpy and
# COADM01.CPY. Kept private (leading underscore) so they are not re-
# exported as part of the public schema API — only the three
# ``BaseModel`` subclasses at the bottom of this file are exported.
# ---------------------------------------------------------------------------

# ``CDEMO-ADMIN-OPT-NAME PIC X(35)`` — option label width on the legacy
# BMS ``OPTNnnnO`` output field. The cloud label is the same text with
# trailing spaces stripped (e.g., "User List" rather than "User List"
# padded to 35 characters).
_OPT_LABEL_MAX_LEN: int = 35

# ``CDEMO-ADMIN-OPT-PGMNAME PIC X(08)`` in the COBOL baseline mapped a
# single 8-char program name. In the REST form we instead declare the
# target endpoint (e.g., ``/users/{user_id}``) and HTTP method — see
# the _ENDPOINT_MAX_LEN and _METHOD_MAX_LEN constants below.
_ENDPOINT_MAX_LEN: int = 64
_METHOD_MAX_LEN: int = 6  # GET / POST / PUT / DELETE (longest = 6)

# ``TITLE01O PIC X(40)`` — the BMS screen title field. The cloud
# ``menu_title`` string fits within the 40-character budget with room
# to spare ("Administrative Menu" = 19 chars), but the constraint is
# retained so future title changes never exceed the legacy envelope.
_TITLE_MAX_LEN: int = 40

# ``CDEMO-USER-ID PIC X(8)`` — the 8-character user identifier carried
# in the CICS COMMAREA and replicated into the JWT ``user_id`` claim.
# Matches the ``user_security.user_id`` primary-key width.
_USER_ID_MAX_LEN: int = 8


# ---------------------------------------------------------------------------
# AdminMenuOption — one row of the admin navigation table
# ---------------------------------------------------------------------------
class AdminMenuOption(BaseModel):
    """One row of the admin-menu navigation table (``CDEMO-ADMIN-OPT``).

    Represents a single admin-menu entry as a self-describing
    ``(option, label, endpoint, method)`` tuple. Replaces the COBOL
    ``CDEMO-ADMIN-OPT`` OCCURS structure declared in
    ``app/cpy/COADM02Y.cpy`` lines 22-48:

    .. code-block:: cobol

        01 CDEMO-ADMIN-OPT-DATA.
            05 CDEMO-ADMIN-OPT OCCURS 12 TIMES
                               INDEXED BY CDEMO-ADMIN-OPT-IDX.
                10 CDEMO-ADMIN-OPT-NUM      PIC 9(02).
                10 CDEMO-ADMIN-OPT-NAME     PIC X(35).
                10 CDEMO-ADMIN-OPT-PGMNAME  PIC X(08).

    Of the 12 OCCURS entries, only the first 4 are populated; the
    remaining 8 are padded with SPACEs — see ``COADM02Y.cpy`` lines
    24-48. The cloud-native form carries ONLY the 4 populated entries
    (no padding slots) because clients iterate the ``options`` array
    directly and do not need to handle a fixed-count sparse table.

    Attributes
    ----------
    option : int
        The 1-based option number as displayed in the admin menu.
        Maps to the COBOL ``CDEMO-ADMIN-OPT-NUM PIC 9(02)`` literal
        (values 1-4 for the populated rows). The field is typed as
        :class:`int` rather than :class:`str` because the client
        renders it in digit form.
    label : str
        Human-readable menu entry text. Maps to the COBOL
        ``CDEMO-ADMIN-OPT-NAME PIC X(35)`` field with trailing spaces
        stripped. Values preserved byte-for-byte from the COBOL
        baseline: ``"User List"``, ``"User Add"``, ``"User Update"``,
        ``"User Delete"``.
    endpoint : str
        REST endpoint path the client should invoke when the user
        selects this option. Replaces the legacy
        ``CDEMO-ADMIN-OPT-PGMNAME PIC X(08)`` COBOL-program dispatch
        table. Path parameters (``{user_id}``) are included literally
        so the client can substitute before issuing the request.
    method : str
        HTTP method the client should use against ``endpoint``.
        Always one of ``GET``, ``POST``, ``PUT``, ``DELETE`` — no
        enum type is declared to stay consistent with FastAPI
        convention (``str`` over ``Enum`` for method literals).
    """

    option: int = Field(
        ...,
        ge=1,
        description=(
            "1-based option number as displayed in the admin menu. "
            "Maps to the COBOL ``CDEMO-ADMIN-OPT-NUM PIC 9(02)`` "
            "literal. Values 1-4 for the 4 populated admin-menu "
            "rows declared in ``app/cpy/COADM02Y.cpy`` (lines 24-42). "
            "Must be >= 1."
        ),
    )
    label: str = Field(
        ...,
        min_length=1,
        max_length=_OPT_LABEL_MAX_LEN,
        description=(
            "Human-readable menu entry text. Maps to the COBOL "
            "``CDEMO-ADMIN-OPT-NAME PIC X(35)`` field with trailing "
            "spaces stripped. Byte-for-byte equal to the COBOL "
            "baseline values: 'User List', 'User Add', 'User "
            "Update', 'User Delete' (see ``app/cpy/COADM02Y.cpy`` "
            "lines 24-42)."
        ),
    )
    endpoint: str = Field(
        ...,
        min_length=1,
        max_length=_ENDPOINT_MAX_LEN,
        description=(
            "REST endpoint path the client should invoke when this "
            "option is selected. Replaces the legacy "
            "``CDEMO-ADMIN-OPT-PGMNAME PIC X(08)`` COBOL-program "
            "dispatch target. Path parameters "
            "(e.g., ``{user_id}``) are included literally so the "
            "client can substitute before issuing the request."
        ),
    )
    method: str = Field(
        ...,
        min_length=3,
        max_length=_METHOD_MAX_LEN,
        description=(
            "HTTP method for the option's endpoint. Always one of "
            "``GET``, ``POST``, ``PUT``, or ``DELETE``. Typed as "
            ":class:`str` (rather than an ``Enum``) to match "
            "FastAPI convention for method literals."
        ),
    )

    # from_attributes=False (default). Options are constructed inline
    # from Python dict literals in the router, NOT from ORM rows, so
    # attribute-mode parsing is unnecessary.
    model_config = ConfigDict(extra="forbid")


# ---------------------------------------------------------------------------
# AdminMenuResponse — GET /admin/menu response body
# ---------------------------------------------------------------------------
class AdminMenuResponse(BaseModel):
    """Response body for ``GET /admin/menu``.

    Replaces the BMS ``SEND MAP('COADM1A') MAPSET('COADM01')`` output
    previously rendered by ``COADM01C.cbl``. Carries:

    * the admin-menu title (``menu_title`` — maps to BMS
      ``TITLE01O PIC X(40)``);
    * the 4-option navigation table (``options`` — maps to the
      4 populated rows of ``CDEMO-ADMIN-OPTIONS-DATA`` in
      ``app/cpy/COADM02Y.cpy``).

    This response is served ONLY to admin users (``user_type == 'A'``),
    enforced by the :func:`src.api.dependencies.get_current_admin_user`
    dependency on the router. Non-admin callers receive HTTP 403
    Forbidden upstream and therefore never see this payload.

    Attributes
    ----------
    menu_title : str
        Human-readable screen title. Maps to the BMS ``TITLE01O``
        ``PIC X(40)`` field populated by ``COADM01C POPULATE-HEADER-
        INFO`` (lines 202-221). Currently always
        ``"Administrative Menu"``; the length constraint of 40
        characters preserves the legacy envelope for any future
        title revision.
    options : list[AdminMenuOption]
        The admin-menu navigation entries, one per selectable
        operation. For the current (4-option) admin menu this list
        contains 4 entries mapping to the four user-management
        operations (User List, User Add, User Update, User Delete).
        The length is deliberately NOT pinned to exactly 4 so a
        future cloud-native revision may extend the menu without
        a schema migration.
    """

    menu_title: str = Field(
        ...,
        min_length=1,
        max_length=_TITLE_MAX_LEN,
        description=(
            "Human-readable admin-menu screen title. Maps to the "
            "BMS ``TITLE01O PIC X(40)`` field populated by "
            "``COADM01C POPULATE-HEADER-INFO`` (lines 202-221). "
            "Currently 'Administrative Menu'."
        ),
    )
    options: list[AdminMenuOption] = Field(
        ...,
        description=(
            "Admin-menu navigation entries. Each entry carries the "
            "option number, label, target REST endpoint, and HTTP "
            "method. Maps to the 4 populated rows of "
            "``CDEMO-ADMIN-OPTIONS-DATA`` in "
            "``app/cpy/COADM02Y.cpy`` lines 24-42."
        ),
    )

    model_config = ConfigDict(extra="forbid")


# ---------------------------------------------------------------------------
# AdminStatusResponse — GET /admin/status response body
# ---------------------------------------------------------------------------
class AdminStatusResponse(BaseModel):
    """Response body for ``GET /admin/status``.

    Cloud-native addition — there is no direct COBOL equivalent. In the
    legacy mainframe architecture, admin "system oversight" data (file
    open/close state, CICS region health) was surfaced via separate
    CICS system transactions (``CEMT``, ``CICS``) that are not part of
    the CardDemo application. Here we expose a minimal admin-only
    liveness probe that:

    * confirms the caller's JWT is valid (validation happens upstream
      via :func:`get_current_admin_user`);
    * enforces the ``user_type == 'A'`` admin gate (HTTP 403 if the
      caller is a regular user);
    * echoes the authenticated admin's ``user_id`` so dashboards
      and on-call runbooks can verify the signed-in identity at a
      glance;
    * distinguishes from ``GET /health`` (public, DB-independent) by
      requiring admin privileges.

    Attributes
    ----------
    status : str
        Fixed literal ``"operational"`` in the current implementation.
        Declared as a free-form :class:`str` rather than a
        :class:`typing.Literal` so that a future revision may evolve
        the endpoint to return ``"degraded"`` / ``"maintenance"``
        without breaking the response contract.
    user : str
        The authenticated admin's ``user_id``, echoed from the JWT
        ``user_id`` claim (populated upstream by
        :func:`get_current_admin_user`). Maps to the COBOL
        ``CDEMO-USER-ID PIC X(8)`` field from
        ``app/cpy/COCOM01Y.cpy`` (CICS COMMAREA). Exactly 8 characters
        wide when the admin record is well-formed.
    """

    status: str = Field(
        ...,
        min_length=1,
        description=(
            "Admin system status marker. Currently always "
            "'operational'. Declared as a free-form string (not a "
            "``Literal``) so a future revision may evolve to "
            "'degraded' or 'maintenance' without breaking existing "
            "dashboards."
        ),
    )
    user: str = Field(
        ...,
        min_length=1,
        max_length=_USER_ID_MAX_LEN,
        description=(
            "The authenticated admin's ``user_id``, echoed from the "
            "JWT ``user_id`` claim. Maps to the COBOL "
            "``CDEMO-USER-ID PIC X(8)`` field from "
            "``app/cpy/COCOM01Y.cpy`` (CICS COMMAREA)."
        ),
    )

    model_config = ConfigDict(extra="forbid")


# ---------------------------------------------------------------------------
# Public API — the three schema classes consumed by admin_router.py as
# ``response_model=`` on the two admin endpoints.
# ---------------------------------------------------------------------------
__all__: list[str] = [
    "AdminMenuOption",
    "AdminMenuResponse",
    "AdminStatusResponse",
]
