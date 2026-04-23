# ============================================================================
# CardDemo — Utility Unit Test Package Init (Mainframe-to-Cloud migration)
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
"""Unit tests for shared utility modules (``src/shared/utils/``).

Tests validate behavioural parity of the shared Python utility modules
with their COBOL-era counterparts from the CardDemo mainframe
application:

* ``test_string_utils``  — CICS AID key mapping + pad helpers
                           (derived from ``app/cpy/CSSTRPFY.cpy``
                            and CICS ``DFHBMSCA`` AID constants).
* ``test_decimal_utils`` — COBOL-compatible financial arithmetic
                           (derived from the ``PIC S9(n)V99``
                            monetary fields in every record copybook
                            and the interest formula in
                            ``app/cbl/CBACT04C.cbl``).
* ``test_date_utils``    — CEEDAYS-equivalent date validation
                           (derived from ``app/cbl/CSUTLDTC.cbl``,
                            ``app/cpy/CSDAT01Y.cpy``,
                            ``app/cpy/CSUTLDWY.cpy``,
                            and ``app/cpy/CSUTLDPY.cpy``).

Purpose
-------
Every utility module under ``src/shared/utils/`` preserves a specific
COBOL-era semantic contract (AAP §0.7.1 "Preserve all existing
functionality exactly as-is"); these tests guarantee that contract is
never silently broken by refactors. They are deliberately
fine-grained — one test per documented branch — to maximise the
signal-to-noise ratio when a future change touches these shared
helpers.

AAP Coverage Gap Remediation
----------------------------
This package closes the coverage gap identified in QA Checkpoint 7:

* ``src/shared/utils/string_utils.py``  had 0% coverage.
* ``src/shared/utils/decimal_utils.py`` had 31% coverage.
* ``src/shared/utils/date_utils.py``    had 68% coverage.

Raising these three modules to full coverage contributes the largest
single jump toward the AAP §0.7.2 "81.5% parity" target.

See Also
--------
* :mod:`src.shared.utils.string_utils`
* :mod:`src.shared.utils.decimal_utils`
* :mod:`src.shared.utils.date_utils`
* AAP §0.5.1 — File-by-File Transformation Plan
* AAP §0.7.1 — Refactoring-Specific Rules
* AAP §0.7.2 — Financial Precision / Testing Requirements
"""
