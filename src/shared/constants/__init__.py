# ============================================================================
# Source: COBOL copybooks (app/cpy/CSMSG0*Y.cpy, app/cpy/COTTL01Y.cpy,
#         app/cpy/CSLKPCDY.cpy, app/cpy/COMEN02Y.cpy, app/cpy/COADM02Y.cpy)
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
"""Shared constants derived from COBOL copybooks and mainframe reference data.

This package bundles the CardDemo application's static reference data —
message text, lookup codes, and menu/navigation configuration —
translated verbatim from the original COBOL copybook library to preserve
the exact wording, ordering, and code values the end user and operator
interact with. Faithful preservation is mandated by AAP §0.7.1
("Refactoring-Specific Rules — preserve existing functionality exactly
as-is").

Submodules
----------
messages
    User-facing error, info, and success message text, along with screen
    title constants. Derived from:

    * ``app/cpy/CSMSG01Y.cpy`` — System messages, set 1 (validation
      errors, sign-on failures, not-found conditions).
    * ``app/cpy/CSMSG02Y.cpy`` — System messages, set 2 (confirmation
      messages, success banners, abend messages).
    * ``app/cpy/COTTL01Y.cpy`` — Screen title constants for every BMS
      mapset (e.g., ``CCDA_TITLE01``, ``CCDA_TITLE02``, menu and admin
      headings, the ``CCDA_MSG_THANK_YOU`` sign-off banner).

lookup_codes
    Lookup / reference codes used for CICS-era tokens that the rewritten
    REST and GraphQL APIs still surface for backward-compatible error
    response bodies and audit logs. Derived from
    ``app/cpy/CSLKPCDY.cpy``.

menu_options
    Main menu and admin menu option tables — the 10 main-menu entries
    (``COMEN02Y``) and the 4 admin-menu entries (``COADM02Y``) that
    drive navigation. Translated into structured Python data so the API
    layer can expose a ``GET /menu`` endpoint that returns the same
    option list the CICS ``SEND MAP MENU`` statement displayed.
    Derived from ``app/cpy/COMEN02Y.cpy`` and ``app/cpy/COADM02Y.cpy``.

Design Notes
------------
* **Immutable constants**: Every symbol exported by the submodules is
  either a ``str``, ``int``, frozen dataclass, or ``tuple`` — never a
  mutable container. This prevents accidental runtime mutation from one
  request leaking into another in the long-running ECS service.

* **Exact COBOL text preservation**: Message wording, spacing, and
  capitalization match the source copybooks byte-for-byte where
  possible. QA Checkpoint 1 verified the ``CCDA_MSG_THANK_YOU``
  constant round-trips the original COBOL text.

* **Standard library only**: Each submodule depends solely on Python's
  standard library (``enum``, ``dataclasses``, ``typing``). No
  third-party dependencies.

* **Lazy loading**: This package init performs NO imports of its
  submodules. Consumers must import what they need explicitly::

      from src.shared.constants import messages
      from src.shared.constants.messages import CCDA_MSG_THANK_YOU
      from src.shared.constants.lookup_codes import LookupCode
      from src.shared.constants.menu_options import MAIN_MENU_OPTIONS

  This matches the pattern used throughout the shared library and keeps
  import cost minimal for AWS Glue worker startup.

* **Python 3.11+**: Aligned with the AWS Glue 5.1 runtime (Python 3.11)
  and the FastAPI container image (``python:3.11-slim``).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/shared/constants/`` layout)
AAP §0.5.1 — File-by-File Transformation Plan (constant file mappings)
AAP §0.7.1 — Refactoring-Specific Rules (preserve existing functionality)
"""

# ----------------------------------------------------------------------------
# Public submodule re-export list.
#
# Only the three submodule names are advertised as the public API of this
# package. Individual constants must be imported from their specific
# submodule (e.g., ``from src.shared.constants.messages import
# CCDA_MSG_THANK_YOU``) rather than from the package root.
#
# NOTE: ``__all__`` containing submodule names does NOT cause those
# submodules to be imported automatically. It only controls what
# ``from src.shared.constants import *`` would pull in. The lazy-loading
# contract is preserved.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "messages",
    "lookup_codes",
    "menu_options",
]
