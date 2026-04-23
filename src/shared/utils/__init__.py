# ============================================================================
# Source: COBOL copybooks and utility programs (app/cpy/, app/cbl/CSUTLDTC.cbl)
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
"""Shared utility modules converted from COBOL utility copybooks and programs.

Provides date validation (``CSUTLDTC.cbl`` / ``CSDAT01Y.cpy``), string
processing (``CSSTRPFY.cpy``), and decimal arithmetic (``CVTRA01Y.cpy``
patterns).

Package Layout
--------------
This package is a **thin, stateless utility library** shared by both CardDemo
workload layers:

* ``src.api``   — FastAPI REST/GraphQL endpoints deployed on AWS ECS Fargate
                  (replaces the 18 CICS online COBOL programs).
* ``src.batch`` — PySpark ETL jobs deployed on AWS Glue 5.1
                  (replaces the 10 batch COBOL programs).

Submodules
----------
date_utils
    CCYYMMDD date validation cascade, leap-year calculation, month/day
    cross-validation, future-date rejection for date-of-birth, and
    COBOL-layout date/time formatting helpers. Preserves the exact
    validation sequence and error-message text from the COBOL
    ``CSUTLDTC`` subroutine and the ``EDIT-DATE-CCYYMMDD`` /
    ``EDIT-DATE-OF-BIRTH`` paragraphs in ``CSUTLDPY.cpy``. Derived from
    ``app/cbl/CSUTLDTC.cbl``, ``app/cpy/CSDAT01Y.cpy``,
    ``app/cpy/CSUTLDWY.cpy``, and ``app/cpy/CSUTLDPY.cpy``.

string_utils
    CICS AID (Attention Identifier) key mapping — converts EIBAID-style
    identifiers (e.g., ``DFHPF1``, ``DFHENTER``) to cloud-native
    ``ActionCode`` enum values, preserving the PF13–PF24 → PF1–PF12
    folding behavior from the original copybook. Also provides
    COBOL-style fixed-width padding and safe whitespace-strip helpers.
    Derived from ``app/cpy/CSSTRPFY.cpy``.

decimal_utils
    COBOL-compatible ``decimal.Decimal`` arithmetic with Banker's
    rounding (``ROUND_HALF_EVEN``) matching the COBOL ``ROUNDED``
    keyword, safe construction that avoids floating-point imprecision,
    and the exact ``(balance * rate) / 1200`` interest formula (not
    algebraically simplified — per AAP §0.7.1). Derived from the
    ``PIC S9(n)V99`` field patterns in ``app/cpy/CVTRA01Y.cpy`` and
    replicated across all monetary fields in the CardDemo copybook
    library.

Design Notes
------------
* **Lazy loading**: This package init is intentionally minimal and does
  NOT eagerly import its submodules. Consumers must import what they
  need explicitly, e.g.::

      from src.shared.utils import date_utils
      from src.shared.utils.decimal_utils import safe_decimal, calculate_interest
      from src.shared.utils.string_utils import ActionCode, map_aid_key

  This pattern avoids circular-import issues with the API / batch
  layers, minimizes import cost for AWS Glue worker startup, and keeps
  this package free of heavy transitive dependencies.

* **No floating-point arithmetic**: All monetary operations live in
  ``decimal_utils`` and use ``decimal.Decimal`` with explicit 2-decimal
  scale to preserve the COBOL ``PIC S9(n)V99`` contract
  (see AAP §0.7.2 "Financial Precision"). This package intentionally
  does NOT re-export any ``float``-based helpers.

* **Standard library only**: Each submodule depends solely on Python's
  standard library (``datetime``, ``decimal``, ``enum``, ``typing``,
  ``dataclasses``, ``re``). No third-party dependencies.

* **Python 3.11+**: Aligned with the AWS Glue 5.1 runtime (Python 3.11)
  and the FastAPI deployment baseline (Python 3.11-slim container).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``src/shared/utils/`` layout)
AAP §0.5.1 — File-by-File Transformation Plan (utility file mappings)
AAP §0.7.1 — Refactoring-Specific Rules (business-logic preservation)
AAP §0.7.2 — Financial Precision (``Decimal`` + Banker's rounding)
"""

# ----------------------------------------------------------------------------
# Public submodule re-export list.
#
# Only the three submodule names are advertised as the public API of this
# package. Individual symbols (functions, classes, constants) must be
# imported from their specific submodule (e.g.,
# ``from src.shared.utils.decimal_utils import safe_decimal``) rather
# than from the package root. This keeps the package init trivial and
# side-effect-free, which is important for AWS Glue workers that may
# import this package many times across task startup.
#
# NOTE: ``__all__`` containing submodule names does NOT cause those
# submodules to be imported automatically. It only controls what
# ``from src.shared.utils import *`` would pull in. The lazy-loading
# contract is preserved.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "date_utils",
    "string_utils",
    "decimal_utils",
]
