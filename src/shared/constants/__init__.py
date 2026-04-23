# ============================================================================
# Source: COBOL copybooks (app/cpy/) — Mainframe-to-Cloud migration
# Derived from: app/cpy/CSMSG01Y.cpy  — Common user-facing messages
#               app/cpy/CSMSG02Y.cpy  — Abend (error) data work area
#               app/cpy/COTTL01Y.cpy  — Screen title text constants
#               app/cpy/CSLKPCDY.cpy  — NANPA / US-state lookup codes
#               app/cpy/COMEN02Y.cpy  — Main menu option table
#               app/cpy/COADM02Y.cpy  — Admin menu option table
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
"""Application constants package converted from COBOL copybook literal storage.

Provides message constants, lookup code sets, and menu configuration for the
CardDemo cloud-native application. Every symbol re-exported by this package is
derived from a specific COBOL copybook in ``app/cpy/`` — the mainframe
literal-storage copybooks that the legacy CICS/batch programs ``COPY``'d into
their WORKING-STORAGE sections. Preserving these constants verbatim is mandated
by AAP §0.7.1 ("Preserve all existing functionality exactly as-is").

Submodules
----------
messages
    User-facing error, info, and success message text, plus screen title
    banners and the :class:`AbendData` structured error dataclass. Derived
    from:

    * ``app/cpy/CSMSG01Y.cpy`` — ``CCDA-COMMON-MESSAGES`` record with the
      two common messages (``CCDA-MSG-THANK-YOU``,
      ``CCDA-MSG-INVALID-KEY``).
    * ``app/cpy/CSMSG02Y.cpy`` — ``ABEND-DATA`` work area used by the
      abend (error) routine (``ABEND-CODE``, ``ABEND-CULPRIT``,
      ``ABEND-REASON``, ``ABEND-MSG``).
    * ``app/cpy/COTTL01Y.cpy`` — ``CCDA-SCREEN-TITLE`` record containing
      the three screen-title banners (``CCDA-TITLE01``,
      ``CCDA-TITLE02``, ``CCDA-THANK-YOU``).

lookup_codes
    Validation reference data: NANPA phone area code frozenset, US
    state/territory frozenset, state + ZIP-prefix combo frozenset, and
    three validation helper functions that replace the COBOL ``IF
    88-level-condition`` idiom. Derived from ``app/cpy/CSLKPCDY.cpy``.

menu_options
    Main-menu (10 entries, general-user accessible) and admin-menu
    (4 entries, admin-only) configuration tables plus the legacy-program
    name → REST API route mapping used by the FastAPI routers to build
    navigation links. Derived from ``app/cpy/COMEN02Y.cpy`` (main menu)
    and ``app/cpy/COADM02Y.cpy`` (admin menu).

Convenience Re-exports
----------------------
For ergonomics, the most frequently used symbols from each submodule are
re-exported at the package root so callers can write::

    from src.shared.constants import (
        CCDA_MSG_THANK_YOU,
        MAIN_MENU_OPTIONS,
        VALID_PHONE_AREA_CODES,
        is_valid_us_state_code,
    )

instead of importing from each submodule individually. The re-export list
is deliberately curated (17 symbols) — it covers exactly the public API
surface declared in the AAP §0.5.1 file schema for this package. Any
additional private-detail symbols defined inside a submodule (for
example, the ``VALID_GENERAL_PURPOSE_CODES`` / ``VALID_EASILY_RECOGNIZABLE_CODES``
subsets inside ``lookup_codes``, or the ``ABEND_*_MAX_LEN`` width constants
and ``MainMenuOption`` / ``AdminMenuOption`` TypedDict types) MUST still be
imported from their specific submodule directly.

Design Notes
------------
* **Immutable values only** — Every re-exported symbol is either a
  ``str``, ``int``, frozen dataclass, frozenset, tuple, or TypedDict
  consumer (the top-level ``list`` and ``dict`` containers are treated
  as constants — they are populated once at import time and never
  mutated at runtime). This prevents accidental request-to-request
  state leakage in the long-running FastAPI ECS service and keeps the
  constants broadcast-safe in PySpark workers.

* **Exact COBOL text preservation** — Message wording, menu option
  labels, area codes, state codes, and state-ZIP combos match the
  source copybooks byte-for-byte after stripping the fixed-width
  ``PIC X(n)`` space padding. This preserves the operator-visible
  strings that end-users recognise from the legacy application
  (AAP §0.7.1 "Refactoring-Specific Rules").

* **Eager imports are cheap** — Each submodule is pure-Python,
  standard-library-only, and contains no I/O or heavy computation.
  Importing this package pulls all three submodules into memory but the
  cost is a few hundred microseconds for the frozenset literals — well
  below the AWS Glue cold-start threshold and negligible in the FastAPI
  Uvicorn startup path.

* **Python 3.11+** — Aligned with the AWS Glue 5.1 runtime (Python
  3.11, Spark 3.5.6) and the FastAPI container base image
  (``python:3.11-slim``) per AAP §0.6.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/shared/constants/``
layout).
AAP §0.5.1 — File-by-File Transformation Plan (constants re-export
contract).
AAP §0.7.1 — Refactoring-Specific Rules (preserve existing
functionality exactly).
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Re-exports from the .lookup_codes submodule — app/cpy/CSLKPCDY.cpy.
# ---------------------------------------------------------------------------
# Three NANPA/US-centric validation frozensets plus three helper functions
# that replace the COBOL ``IF VALID-xxxx`` 88-level condition idiom with
# O(1) set-membership checks. The helper functions accept a string input
# and return ``bool`` — identical semantics to the original condition
# evaluation, with the ``is_valid_us_state_code`` and
# ``is_valid_state_zip_combo`` helpers performing ``.upper()``
# normalisation to gracefully accept lower-case input from JSON API
# clients.
#
# Private detail kept inside the submodule (NOT re-exported here):
#   * VALID_GENERAL_PURPOSE_CODES  — the 410-entry subset of NANPA codes
#     without the 80 pattern/reserved codes.
#   * VALID_EASILY_RECOGNIZABLE_CODES — the 80-entry pattern/reserved
#     subset.
# Consumers who need these subsets must import them directly from
# ``src.shared.constants.lookup_codes``.
from src.shared.constants.lookup_codes import (
    VALID_PHONE_AREA_CODES,
    VALID_US_STATE_CODES,
    VALID_US_STATE_ZIP_COMBOS,
    is_valid_phone_area_code,
    is_valid_state_zip_combo,
    is_valid_us_state_code,
)

# ---------------------------------------------------------------------------
# Re-exports from the .menu_options submodule —
# app/cpy/COMEN02Y.cpy + app/cpy/COADM02Y.cpy.
# ---------------------------------------------------------------------------
# Row counts and populated option tables for the legacy main menu
# (10 entries, user-accessible) and admin menu (4 entries, admin-only),
# plus the legacy-program-name → REST API route prefix mapping used by
# the FastAPI admin router and GraphQL navigation resolver to translate
# the legacy CICS XCTL target names into cloud-native HTTP routes.
#
# Private detail kept inside the submodule (NOT re-exported here):
#   * MainMenuOption, AdminMenuOption — ``TypedDict`` row types. These
#     describe the shape of each element in MAIN_MENU_OPTIONS /
#     ADMIN_MENU_OPTIONS and are useful for static type checking, but
#     are structural types not typically needed by menu consumers.
# Consumers who need these row types must import them directly from
# ``src.shared.constants.menu_options``.
from src.shared.constants.menu_options import (
    ADMIN_MENU_OPT_COUNT,
    ADMIN_MENU_OPTIONS,
    MAIN_MENU_OPT_COUNT,
    MAIN_MENU_OPTIONS,
    PROGRAM_TO_API_ROUTE,
)

# ---------------------------------------------------------------------------
# Re-exports from the .messages submodule —
# app/cpy/CSMSG01Y.cpy + app/cpy/CSMSG02Y.cpy + app/cpy/COTTL01Y.cpy.
# ---------------------------------------------------------------------------
# The five common message / screen title constants consumed by the API
# error handler and the menu/title rendering code paths, plus the
# :class:`AbendData` dataclass used to capture structured error
# information during an abend (unrecoverable error) event.
#
# Private detail kept inside the submodule (NOT re-exported here):
#   * ABEND_CODE_MAX_LEN / ABEND_CULPRIT_MAX_LEN / ABEND_REASON_MAX_LEN
#     / ABEND_MSG_MAX_LEN — original ``PIC X(n)`` field widths used for
#     truncation/validation of inbound strings.
# Consumers who need these width constants must import them directly
# from ``src.shared.constants.messages``.
from src.shared.constants.messages import (
    CCDA_MSG_INVALID_KEY,
    CCDA_MSG_THANK_YOU,
    CCDA_THANK_YOU,
    CCDA_TITLE01,
    CCDA_TITLE02,
    AbendData,
)

# ---------------------------------------------------------------------------
# Public re-export list.
#
# The explicit ``__all__`` declaration serves two purposes:
#
# 1. It marks the imported symbols as intentional re-exports, satisfying
#    the ``ruff`` ``F401`` ("unused import") lint rule without the need
#    for per-line suppression directives.
# 2. It makes ``from src.shared.constants import *`` — when used in
#    interactive sessions or docs examples — expose exactly the 17
#    symbols below and nothing else (no accidental leakage of the
#    ``AbendData``-helper ``ABEND_*_MAX_LEN`` widths or the
#    ``MainMenuOption`` TypedDict).
#
# Entries are grouped by originating submodule / COBOL copybook for
# traceability; within each group the order matches the schema exports
# declaration in AAP §0.5.1.
# ---------------------------------------------------------------------------
__all__: list[str] = [
    # --- Messages — from app/cpy/CSMSG01Y.cpy, CSMSG02Y.cpy, COTTL01Y.cpy ---
    "CCDA_MSG_THANK_YOU",
    "CCDA_MSG_INVALID_KEY",
    "CCDA_TITLE01",
    "CCDA_TITLE02",
    "CCDA_THANK_YOU",
    "AbendData",
    # --- Lookup codes — from app/cpy/CSLKPCDY.cpy ---
    "VALID_PHONE_AREA_CODES",
    "VALID_US_STATE_CODES",
    "VALID_US_STATE_ZIP_COMBOS",
    "is_valid_phone_area_code",
    "is_valid_us_state_code",
    "is_valid_state_zip_combo",
    # --- Menu options — from app/cpy/COMEN02Y.cpy, COADM02Y.cpy ---
    "MAIN_MENU_OPTIONS",
    "ADMIN_MENU_OPTIONS",
    "MAIN_MENU_OPT_COUNT",
    "ADMIN_MENU_OPT_COUNT",
    "PROGRAM_TO_API_ROUTE",
]
