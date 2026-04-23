# ============================================================================
# Source: app/cpy/COMEN02Y.cpy, app/cpy/COADM02Y.cpy —
#         Mainframe-to-Cloud migration
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
"""Menu and navigation configuration constants for the CardDemo application.

This module is the Python conversion of two COBOL copybooks:

* ``app/cpy/COMEN02Y.cpy`` — ``CARDDEMO-MAIN-MENU-OPTIONS`` (main menu,
  10 entries, general-user accessible).
* ``app/cpy/COADM02Y.cpy`` — ``CARDDEMO-ADMIN-MENU-OPTIONS`` (admin menu,
  4 entries, admin-only).

Each COBOL copybook declares a fixed-width contiguous data area populated by
``10 FILLER ... VALUE '...'`` literal statements and then *redefines* that
area as an ``OCCURS`` table — the classic COBOL idiom for a compile-time
constant lookup table. In Python the same semantic is expressed as a
``list[TypedDict]`` so that:

1. Every row carries the same set of keys (enforced by ``TypedDict``).
2. Accessing a field is an O(1) attribute lookup (``row["option_name"]``).
3. Consumers still iterate by index (``for opt in MAIN_MENU_OPTIONS:``)
   exactly as the COBOL code iterates with a ``PERFORM VARYING`` loop.

Original CICS online programs drive navigation by:

* Displaying the menu title + one row per option from the table.
* Reading the user-selected option number from the BMS map.
* Using ``CDEMO-MENU-OPT-PGMNAME`` / ``CDEMO-ADMIN-OPT-PGMNAME`` to build a
  ``EXEC CICS XCTL PROGRAM(...)`` (transfer-of-control) call.

In the cloud-native target architecture there is no XCTL: instead, the
FastAPI routers expose the equivalent REST endpoints and the menu is
returned to the client as a JSON payload. The original 8-character COBOL
program names are preserved as an opaque identifier (they are referenced
verbatim by several copybooks and UI screen literals) and the
:data:`PROGRAM_TO_API_ROUTE` mapping provides the bridge from the legacy
name to the target REST path prefix.

Public surface
--------------
:class:`MainMenuOption`
    ``TypedDict`` describing a single row of the main menu table — mirrors
    the COBOL record layout ``CDEMO-MENU-OPT`` from
    ``COMEN02Y.cpy`` lines 88-92.

:class:`AdminMenuOption`
    ``TypedDict`` describing a single row of the admin menu table — mirrors
    ``CDEMO-ADMIN-OPT`` from ``COADM02Y.cpy`` lines 45-48. Note: no
    ``user_type`` field because admin options are inherently admin-only
    (access control is performed by requiring an authenticated admin
    caller, not by a per-row flag).

:data:`MAIN_MENU_OPT_COUNT`
    ``Final[int]`` — the count literal ``CDEMO-MENU-OPT-COUNT = 10`` from
    ``COMEN02Y.cpy`` line 21. The COBOL table is over-allocated
    (``OCCURS 12 TIMES``) but only the first ``MAIN_MENU_OPT_COUNT`` rows
    are populated. Preserved for behavioral parity.

:data:`ADMIN_MENU_OPT_COUNT`
    ``Final[int]`` — ``CDEMO-ADMIN-OPT-COUNT = 4`` from
    ``COADM02Y.cpy`` line 20. Similar over-allocation
    (``OCCURS 9 TIMES``) in the COBOL source.

:data:`MAIN_MENU_OPTIONS`
    10-entry ``list[MainMenuOption]`` — the populated rows of the main
    menu in option-number order. Every row has ``user_type == "U"``
    (regular user) per the COBOL source.

:data:`ADMIN_MENU_OPTIONS`
    4-entry ``list[AdminMenuOption]`` — the populated rows of the admin
    menu in option-number order.

:data:`PROGRAM_TO_API_ROUTE`
    ``dict[str, str]`` mapping legacy 8-character COBOL program names to
    their cloud-native REST API route prefixes. Used by the FastAPI
    routers and GraphQL resolvers to emit correct ``Link`` / ``href``
    fields on menu responses, and by the admin menu handler to build the
    navigation links returned to the client.

Design rules (from AAP §0.7.1 and the file-level instructions)
--------------------------------------------------------------
* **Preserve all values exactly** — option numbers, labels, program
  names, and user-type flags are copied verbatim from the COBOL
  ``VALUE`` clauses after stripping the trailing space padding of the
  ``PIC X(35)`` / ``PIC X(08)`` / ``PIC X(01)`` fixed-width fields.
* **Standard library only** — ``typing`` (``TypedDict``, ``Final``) is
  the sole import, keeping the module cheap to load in AWS Glue Python
  shell cold starts and in the FastAPI startup path.
* **Python 3.11 compatible** — aligned with the AWS Glue 5.1 runtime
  (Spark 3.5.6, Python 3.11) and the FastAPI ``python:3.11-slim``
  ECS container.
* **Count constants are ``Final[int]``** — matches the immutability
  semantics of a COBOL ``VALUE`` clause on a ``PIC 9(02)`` field.

See Also
--------
AAP §0.5.1 — File-by-File Transformation Plan (menu_options row).
AAP §0.7.1 — Refactoring-Specific Rules (preserve behavior exactly).
AAP §0.4.3 — Design Pattern Applications (CICS XCTL -> REST routing).
"""

from __future__ import annotations

from typing import Final, TypedDict

# ----------------------------------------------------------------------------
# Public re-export list — names consumers may legally import from this module.
# Any symbol not in this list is considered a private implementation detail
# and may change without notice.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "MainMenuOption",
    "AdminMenuOption",
    "MAIN_MENU_OPT_COUNT",
    "MAIN_MENU_OPTIONS",
    "ADMIN_MENU_OPT_COUNT",
    "ADMIN_MENU_OPTIONS",
    "PROGRAM_TO_API_ROUTE",
]


# ============================================================================
# Type Definitions — COBOL table row layouts as TypedDict
# ============================================================================
class MainMenuOption(TypedDict):
    """A single row of the main (user) menu table.

    Mirrors the COBOL record layout ``CDEMO-MENU-OPT`` from
    ``app/cpy/COMEN02Y.cpy`` lines 88-92::

        10 CDEMO-MENU-OPT OCCURS 12 TIMES.
          15 CDEMO-MENU-OPT-NUM           PIC 9(02).
          15 CDEMO-MENU-OPT-NAME          PIC X(35).
          15 CDEMO-MENU-OPT-PGMNAME       PIC X(08).
          15 CDEMO-MENU-OPT-USRTYPE       PIC X(01).

    Fields
    ------
    option_num
        Option number the terminal operator types on the menu screen
        (COBOL ``CDEMO-MENU-OPT-NUM PIC 9(02)``). Range 1..99 but in
        practice only 1..10 are populated — see
        :data:`MAIN_MENU_OPT_COUNT`.

    option_name
        Human-readable display label, up to 35 characters
        (COBOL ``CDEMO-MENU-OPT-NAME PIC X(35)``). Trailing space
        padding from the fixed-width COBOL literal is stripped.

    program_name
        Legacy 8-character COBOL program identifier that the CICS
        ``XCTL`` transfer-of-control targets
        (COBOL ``CDEMO-MENU-OPT-PGMNAME PIC X(08)``). Preserved verbatim
        so it can be used as a stable opaque key by the
        :data:`PROGRAM_TO_API_ROUTE` mapping and by any logging /
        telemetry that needs to correlate API calls to their originating
        mainframe program.

    user_type
        One-character user-type flag (COBOL
        ``CDEMO-MENU-OPT-USRTYPE PIC X(01)``). ``'U'`` means "regular
        user — may access this option"; ``'A'`` would mean "admin only".
        All 10 rows of ``MAIN_MENU_OPTIONS`` have ``'U'`` per the COBOL
        source.
    """

    option_num: int
    option_name: str
    program_name: str
    user_type: str


class AdminMenuOption(TypedDict):
    """A single row of the admin menu table.

    Mirrors the COBOL record layout ``CDEMO-ADMIN-OPT`` from
    ``app/cpy/COADM02Y.cpy`` lines 45-48::

        10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
          15 CDEMO-ADMIN-OPT-NUM           PIC 9(02).
          15 CDEMO-ADMIN-OPT-NAME          PIC X(35).
          15 CDEMO-ADMIN-OPT-PGMNAME       PIC X(08).

    Note that there is **no** ``user_type`` field here — the admin menu is
    inherently admin-only. In the cloud-native architecture admin access
    is enforced by the FastAPI JWT-auth dependency on the admin router,
    not by a per-row flag.

    Fields
    ------
    option_num
        Option number the admin operator types on the menu screen
        (COBOL ``CDEMO-ADMIN-OPT-NUM PIC 9(02)``). Range 1..99 but in
        practice only 1..4 are populated — see
        :data:`ADMIN_MENU_OPT_COUNT`.

    option_name
        Human-readable display label, up to 35 characters
        (COBOL ``CDEMO-ADMIN-OPT-NAME PIC X(35)``). Trailing space
        padding from the fixed-width COBOL literal is stripped.

    program_name
        Legacy 8-character COBOL program identifier that the CICS
        ``XCTL`` transfer-of-control targets
        (COBOL ``CDEMO-ADMIN-OPT-PGMNAME PIC X(08)``). Preserved verbatim.
    """

    option_num: int
    option_name: str
    program_name: str


# ============================================================================
# Main Menu Options — converted from app/cpy/COMEN02Y.cpy
# ============================================================================
# CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10 (line 21 of COMEN02Y.cpy).
# The COBOL table is over-allocated: `OCCURS 12 TIMES` (line 88) reserves
# storage for twelve rows, but only `MAIN_MENU_OPT_COUNT` rows are populated.
# Callers MUST iterate using this count rather than assuming the table is
# fully populated (identical semantics to the legacy `PERFORM VARYING
# I FROM 1 BY 1 UNTIL I > CDEMO-MENU-OPT-COUNT` idiom).
MAIN_MENU_OPT_COUNT: Final[int] = 10

# MAIN_MENU_OPTIONS is the Python translation of the COMEN02Y.cpy
# `CDEMO-MENU-OPTIONS-DATA` / `CDEMO-MENU-OPTIONS REDEFINES` pair.
# Source lines are cited on each entry for traceability. Labels are taken
# from the COBOL `VALUE '...'` literal verbatim, then stripped of trailing
# space padding (the COBOL `PIC X(35)` field is right-padded with spaces).
MAIN_MENU_OPTIONS: list[MainMenuOption] = [
    # Entry 1 — COMEN02Y.cpy lines 25-29
    #   VALUE 1 / 'Account View                       ' / 'COACTVWC' / 'U'
    {
        "option_num": 1,
        "option_name": "Account View",
        "program_name": "COACTVWC",
        "user_type": "U",
    },
    # Entry 2 — COMEN02Y.cpy lines 31-35
    #   VALUE 2 / 'Account Update                     ' / 'COACTUPC' / 'U'
    {
        "option_num": 2,
        "option_name": "Account Update",
        "program_name": "COACTUPC",
        "user_type": "U",
    },
    # Entry 3 — COMEN02Y.cpy lines 37-41
    #   VALUE 3 / 'Credit Card List                   ' / 'COCRDLIC' / 'U'
    {
        "option_num": 3,
        "option_name": "Credit Card List",
        "program_name": "COCRDLIC",
        "user_type": "U",
    },
    # Entry 4 — COMEN02Y.cpy lines 43-47
    #   VALUE 4 / 'Credit Card View                   ' / 'COCRDSLC' / 'U'
    {
        "option_num": 4,
        "option_name": "Credit Card View",
        "program_name": "COCRDSLC",
        "user_type": "U",
    },
    # Entry 5 — COMEN02Y.cpy lines 49-53
    #   VALUE 5 / 'Credit Card Update                 ' / 'COCRDUPC' / 'U'
    {
        "option_num": 5,
        "option_name": "Credit Card Update",
        "program_name": "COCRDUPC",
        "user_type": "U",
    },
    # Entry 6 — COMEN02Y.cpy lines 55-59
    #   VALUE 6 / 'Transaction List                   ' / 'COTRN00C' / 'U'
    {
        "option_num": 6,
        "option_name": "Transaction List",
        "program_name": "COTRN00C",
        "user_type": "U",
    },
    # Entry 7 — COMEN02Y.cpy lines 61-65
    #   VALUE 7 / 'Transaction View                   ' / 'COTRN01C' / 'U'
    {
        "option_num": 7,
        "option_name": "Transaction View",
        "program_name": "COTRN01C",
        "user_type": "U",
    },
    # Entry 8 — COMEN02Y.cpy lines 67-72
    #   VALUE 8 / 'Transaction Add                    ' / 'COTRN02C' / 'U'
    # NOTE: Line 69 of the COBOL source contains a commented-out previous
    # label `'Transaction Add (Admin Only)       '` and line 70 has the
    # currently active label `'Transaction Add                    '`. The
    # ACTIVE value is preserved here per the COBOL source at compile time;
    # the commented-out historical label is ignored as it is inactive in
    # production.
    {
        "option_num": 8,
        "option_name": "Transaction Add",
        "program_name": "COTRN02C",
        "user_type": "U",
    },
    # Entry 9 — COMEN02Y.cpy lines 74-78
    #   VALUE 9 / 'Transaction Reports                ' / 'CORPT00C' / 'U'
    {
        "option_num": 9,
        "option_name": "Transaction Reports",
        "program_name": "CORPT00C",
        "user_type": "U",
    },
    # Entry 10 — COMEN02Y.cpy lines 80-84
    #   VALUE 10 / 'Bill Payment                       ' / 'COBIL00C' / 'U'
    {
        "option_num": 10,
        "option_name": "Bill Payment",
        "program_name": "COBIL00C",
        "user_type": "U",
    },
]


# ============================================================================
# Admin Menu Options — converted from app/cpy/COADM02Y.cpy
# ============================================================================
# CDEMO-ADMIN-OPT-COUNT PIC 9(02) VALUE 4 (line 20 of COADM02Y.cpy).
# As with the main menu, the COBOL table is over-allocated:
# `OCCURS 9 TIMES` (line 45) reserves storage for nine rows, but only
# `ADMIN_MENU_OPT_COUNT` rows are populated.
ADMIN_MENU_OPT_COUNT: Final[int] = 4

# ADMIN_MENU_OPTIONS is the Python translation of the COADM02Y.cpy
# `CDEMO-ADMIN-OPTIONS-DATA` / `CDEMO-ADMIN-OPTIONS REDEFINES` pair.
# Unlike the main menu rows, admin rows DO NOT carry a `user_type` field —
# admin access control is handled at the routing layer in the cloud-native
# target (FastAPI dependency that asserts the caller's JWT has admin
# claims), exactly mirroring the COBOL behavior where the COADM01C.cbl
# program is itself protected by a separate signon transaction.
ADMIN_MENU_OPTIONS: list[AdminMenuOption] = [
    # Entry 1 — COADM02Y.cpy lines 24-27
    #   VALUE 1 / 'User List (Security)               ' / 'COUSR00C'
    {
        "option_num": 1,
        "option_name": "User List (Security)",
        "program_name": "COUSR00C",
    },
    # Entry 2 — COADM02Y.cpy lines 29-32
    #   VALUE 2 / 'User Add (Security)                ' / 'COUSR01C'
    {
        "option_num": 2,
        "option_name": "User Add (Security)",
        "program_name": "COUSR01C",
    },
    # Entry 3 — COADM02Y.cpy lines 34-37
    #   VALUE 3 / 'User Update (Security)             ' / 'COUSR02C'
    {
        "option_num": 3,
        "option_name": "User Update (Security)",
        "program_name": "COUSR02C",
    },
    # Entry 4 — COADM02Y.cpy lines 39-42
    #   VALUE 4 / 'User Delete (Security)             ' / 'COUSR03C'
    {
        "option_num": 4,
        "option_name": "User Delete (Security)",
        "program_name": "COUSR03C",
    },
]


# ============================================================================
# Legacy-program-name -> REST API route mapping
# ============================================================================
# PROGRAM_TO_API_ROUTE bridges the mainframe CICS XCTL target name
# (an 8-character COBOL program identifier) to the cloud-native FastAPI
# route prefix. The mapping covers every program referenced by either the
# main menu or the admin menu (14 distinct programs in total):
#
#   Main menu (10 entries, from COMEN02Y.cpy):
#     COACTVWC (Account View),  COACTUPC (Account Update),
#     COCRDLIC (Card List),     COCRDSLC (Card Detail),
#     COCRDUPC (Card Update),   COTRN00C (Transaction List),
#     COTRN01C (Transaction Detail), COTRN02C (Transaction Add),
#     CORPT00C (Reports),       COBIL00C (Bill Payment)
#
#   Admin menu (4 entries, from COADM02Y.cpy):
#     COUSR00C (User List),     COUSR01C (User Add),
#     COUSR02C (User Update),   COUSR03C (User Delete)
#
# Multiple related legacy programs intentionally map to the SAME REST
# route prefix — the distinction between them becomes an HTTP verb in the
# target architecture (e.g. COACTVWC -> GET /accounts/{id},
# COACTUPC -> PUT /accounts/{id}). This mirrors the AAP §0.5.1 plan:
# "same route, different HTTP method".
#
# Consumers: src.api.routers.admin_router (admin menu JSON response
# construction), src.api.graphql.queries (GraphQL navigation resolver),
# and any future BFF layer that must translate menu selection events
# into outbound REST calls.
PROGRAM_TO_API_ROUTE: dict[str, str] = {
    # --- Account operations (main menu entries 1-2) ---
    "COACTVWC": "/accounts",  # Account View     -> GET  /accounts/{id}
    "COACTUPC": "/accounts",  # Account Update   -> PUT  /accounts/{id}
    # --- Card operations (main menu entries 3-5) ---
    "COCRDLIC": "/cards",  # Card List        -> GET  /cards
    "COCRDSLC": "/cards",  # Card Detail      -> GET  /cards/{id}
    "COCRDUPC": "/cards",  # Card Update      -> PUT  /cards/{id}
    # --- Transaction operations (main menu entries 6-8) ---
    "COTRN00C": "/transactions",  # Transaction List    -> GET  /transactions
    "COTRN01C": "/transactions",  # Transaction Detail  -> GET  /transactions/{id}
    "COTRN02C": "/transactions",  # Transaction Add     -> POST /transactions
    # --- Reports (main menu entry 9) ---
    "CORPT00C": "/reports",  # Transaction Reports -> POST /reports/submit
    # --- Bill payment (main menu entry 10) ---
    "COBIL00C": "/bills",  # Bill Payment        -> POST /bills/pay
    # --- User administration (admin menu entries 1-4) ---
    "COUSR00C": "/users",  # User List    -> GET    /users
    "COUSR01C": "/users",  # User Add     -> POST   /users
    "COUSR02C": "/users",  # User Update  -> PUT    /users/{id}
    "COUSR03C": "/users",  # User Delete  -> DELETE /users/{id}
}
