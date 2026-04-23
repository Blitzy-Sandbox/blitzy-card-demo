# ============================================================================
# Source: app/cpy/CSMSG01Y.cpy, app/cpy/CSMSG02Y.cpy, app/cpy/COTTL01Y.cpy —
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
"""Application message constants converted from COBOL copybooks.

This module is the Python conversion of three COBOL copybooks that together
provide the user-visible text fragments and structured error reporting
surfaces shared across the CardDemo online and batch programs:

* ``app/cpy/CSMSG01Y.cpy`` — ``CCDA-COMMON-MESSAGES`` record (two common
  user-facing messages — thank-you and invalid-key — each stored as a
  fixed-width ``PIC X(50)`` literal).
* ``app/cpy/COTTL01Y.cpy`` — ``CCDA-SCREEN-TITLE`` record (three screen
  title banners — two title lines and a trailing thank-you — each stored
  as a fixed-width ``PIC X(40)`` literal).
* ``app/cpy/CSMSG02Y.cpy`` — ``ABEND-DATA`` work area (four ``PIC X(n)``
  fields with ``VALUE SPACES`` that hold structured error information
  during a COBOL abend routine).

Conversion semantics
--------------------
COBOL stores each literal as a space-padded fixed-width field — the
compiler allocates exactly ``n`` bytes for a ``PIC X(n)`` item and pads
the declared VALUE with trailing (or occasionally leading+trailing)
spaces. Consumers in COBOL typically reference the whole field, so the
padding is invisibly carried along.

In the cloud-native Python/FastAPI target architecture the padding is
purely presentational: JSON response payloads, HTML renderings, and
structured logs all present the logical content without the COBOL fill
characters. The conversion therefore strips the surrounding whitespace
per the following rules, which are faithful to the original display
intent:

* ``CCDA-MSG-THANK-YOU``, ``CCDA-MSG-INVALID-KEY``,
  ``CCDA-THANK-YOU`` — trailing spaces removed (the leading text is
  already flush-left in the COBOL literal).
* ``CCDA-TITLE01``, ``CCDA-TITLE02`` — both leading and trailing spaces
  removed. The COBOL literals use *both* leading and trailing padding
  to visually centre the title within the 40-column banner; in an API
  context the content is what matters and the centring is performed by
  the client (or not at all, for structured payloads).

The ``ABEND-DATA`` COBOL ``VALUE SPACES`` initializer translates to
Python default empty strings (``""``). All four field max-length
constants (``ABEND_CODE_MAX_LEN`` etc.) are exported so callers can
truncate or validate inbound values against the original COBOL field
widths when constructing an :class:`AbendData` record.

Public surface
--------------
Common user messages (from ``CSMSG01Y.cpy``):

:data:`CCDA_MSG_THANK_YOU`
    Default session-ending thank-you message.
:data:`CCDA_MSG_INVALID_KEY`
    Default response when an unrecognised key/function is pressed.

Screen titles (from ``COTTL01Y.cpy``):

:data:`CCDA_TITLE01`
    First screen title line ("AWS Mainframe Modernization").
:data:`CCDA_TITLE02`
    Second screen title line ("CardDemo").
:data:`CCDA_THANK_YOU`
    Screen-level thank-you text variant (40-column width).

Structured error reporting (from ``CSMSG02Y.cpy``):

:class:`AbendData`
    Dataclass mirroring the COBOL ``ABEND-DATA`` work area.
:data:`ABEND_CODE_MAX_LEN`, :data:`ABEND_CULPRIT_MAX_LEN`,
:data:`ABEND_REASON_MAX_LEN`, :data:`ABEND_MSG_MAX_LEN`
    Maximum byte-widths of the four ``ABEND-DATA`` fields, preserved for
    validation and compatibility with the original fixed-width layout.
"""

from __future__ import annotations

from dataclasses import dataclass

# ---------------------------------------------------------------------------
# Common User Messages (from app/cpy/CSMSG01Y.cpy)
# ---------------------------------------------------------------------------
# Source: COBOL 01 CCDA-COMMON-MESSAGES (CSMSG01Y.cpy lines 17-21).
#
# Each message is declared in COBOL as:
#     05 CCDA-MSG-xxxxx   PIC X(50) VALUE '...'.
#
# The Python values below preserve the original literal text verbatim with
# trailing COBOL space padding stripped (``str.rstrip()`` semantics). The
# COBOL hyphenated field names map to Python UPPER_SNAKE_CASE constants.
# ---------------------------------------------------------------------------

# COBOL: 05 CCDA-MSG-THANK-YOU PIC X(50)
#        VALUE 'Thank you for using CardDemo application...      '.
# Logical text: 43 characters, followed by 7 trailing space fillers.
CCDA_MSG_THANK_YOU: str = "Thank you for using CardDemo application..."

# COBOL: 05 CCDA-MSG-INVALID-KEY PIC X(50)
#        VALUE 'Invalid key pressed. Please see below...         '.
# Logical text: 40 characters, followed by 10 trailing space fillers.
CCDA_MSG_INVALID_KEY: str = "Invalid key pressed. Please see below..."

# ---------------------------------------------------------------------------
# Screen Titles (from app/cpy/COTTL01Y.cpy)
# ---------------------------------------------------------------------------
# Source: COBOL 01 CCDA-SCREEN-TITLE (COTTL01Y.cpy lines 17-24).
#
# Each title is declared in COBOL as:
#     05 CCDA-TITLExx   PIC X(40) VALUE '...'.
#
# CCDA-TITLE01 and CCDA-TITLE02 are padded with both leading and trailing
# spaces in the COBOL source — the padding centres the banner within the
# 40-column BMS screen field. For API/JSON consumers the logical content
# is what matters, so ``str.strip()`` semantics are applied.
# CCDA-THANK-YOU is flush-left with only trailing padding, so
# ``str.rstrip()`` semantics are sufficient.
# ---------------------------------------------------------------------------

# COBOL: 05 CCDA-TITLE01 PIC X(40)
#        VALUE '      AWS Mainframe Modernization       '.
# 6 leading spaces + 27-character title + 7 trailing spaces = 40 bytes.
CCDA_TITLE01: str = "AWS Mainframe Modernization"

# COBOL: 05 CCDA-TITLE02 PIC X(40)
#        VALUE '              CardDemo                  '.
# 14 leading spaces + 8-character title + 18 trailing spaces = 40 bytes.
# (The COBOL source carries an adjacent commented-out alternative
# '  Credit Card Demo Application (CCDA)   ' which is intentionally not
# migrated — only the active VALUE literal is authoritative.)
CCDA_TITLE02: str = "CardDemo"

# COBOL: 05 CCDA-THANK-YOU PIC X(40)
#        VALUE 'Thank you for using CCDA application... '.
# 39-character message + 1 trailing space = 40 bytes.
CCDA_THANK_YOU: str = "Thank you for using CCDA application..."

# ---------------------------------------------------------------------------
# Abend Data Structure — Field Max Lengths (from app/cpy/CSMSG02Y.cpy)
# ---------------------------------------------------------------------------
# Source: COBOL 01 ABEND-DATA (CSMSG02Y.cpy lines 21-29).
#
# The four ABEND-DATA fields carry explicit PIC X(n) widths. These widths
# are preserved as module-level constants so callers can validate or
# truncate inbound values against the original fixed-width layout when
# they construct an AbendData record for logging, persistence, or API
# error responses.
# ---------------------------------------------------------------------------

# COBOL: 05 ABEND-CODE    PIC X(4)  VALUE SPACES.
ABEND_CODE_MAX_LEN: int = 4

# COBOL: 05 ABEND-CULPRIT PIC X(8)  VALUE SPACES.
ABEND_CULPRIT_MAX_LEN: int = 8

# COBOL: 05 ABEND-REASON  PIC X(50) VALUE SPACES.
ABEND_REASON_MAX_LEN: int = 50

# COBOL: 05 ABEND-MSG     PIC X(72) VALUE SPACES.
ABEND_MSG_MAX_LEN: int = 72

# ---------------------------------------------------------------------------
# Abend Data Structure (from app/cpy/CSMSG02Y.cpy)
# ---------------------------------------------------------------------------
# Source: COBOL 01 ABEND-DATA (CSMSG02Y.cpy lines 21-29).
#
# The COBOL ABEND-DATA work area captures structured error information
# during the application's abend routine — it is populated by the abend
# handler and then written to the error log / displayed to the operator.
#
#     01 ABEND-DATA.
#        05 ABEND-CODE    PIC X(4)  VALUE SPACES.
#        05 ABEND-CULPRIT PIC X(8)  VALUE SPACES.
#        05 ABEND-REASON  PIC X(50) VALUE SPACES.
#        05 ABEND-MSG     PIC X(72) VALUE SPACES.
#
# In the Python target the four COBOL fields become dataclass fields
# carrying the same semantics:
#
# * ``VALUE SPACES`` → ``""`` (empty string) as the factory default.
# * Field names converted from COBOL hyphens to Python underscores. The
#   COBOL reserved keyword ``MSG`` is renamed ``message`` — the Python
#   attribute name ``msg`` is valid but ``message`` is the idiomatic
#   choice for a user-facing string, aligning with
#   ``logging.LogRecord.message`` and common Python conventions.
# * Field max widths are exposed via the ``ABEND_*_MAX_LEN`` constants
#   above for callers that need to validate or truncate inputs.
#
# This dataclass is the canonical error structure consumed by the API
# layer's exception handlers (see ``src/api/middleware/error_handler.py``)
# and by batch-layer logging when a PySpark job encounters an
# unrecoverable error.
# ---------------------------------------------------------------------------


@dataclass
class AbendData:
    """Structured abend (error) data, converted from COBOL ``ABEND-DATA``.

    Mirrors the ``01 ABEND-DATA`` record layout declared in
    ``app/cpy/CSMSG02Y.cpy``. Every field defaults to an empty string,
    matching the COBOL ``VALUE SPACES`` initializer — a freshly
    constructed ``AbendData()`` is equivalent to the zero-initialized
    COBOL work area at program start.

    Attributes
    ----------
    code:
        COBOL ``ABEND-CODE``, ``PIC X(4)``. A short (<=4-character)
        machine-readable abend code (e.g., ``"S0C7"``) that categorises
        the error class. Max length :data:`ABEND_CODE_MAX_LEN`.
    culprit:
        COBOL ``ABEND-CULPRIT``, ``PIC X(8)``. The 8-character name of
        the program/module that raised the abend — typically the COBOL
        PROGRAM-ID or, in the Python target, the module/function name.
        Max length :data:`ABEND_CULPRIT_MAX_LEN`.
    reason:
        COBOL ``ABEND-REASON``, ``PIC X(50)``. Short descriptive reason
        for the abend (e.g., ``"FILE NOT FOUND"``). Max length
        :data:`ABEND_REASON_MAX_LEN`.
    message:
        COBOL ``ABEND-MSG``, ``PIC X(72)``. Full 72-character abend
        message for display/logging. The attribute is renamed from the
        COBOL ``MSG`` to the Pythonic ``message``. Max length
        :data:`ABEND_MSG_MAX_LEN`.
    """

    # COBOL: 05 ABEND-CODE    PIC X(4)  VALUE SPACES.
    code: str = ""
    # COBOL: 05 ABEND-CULPRIT PIC X(8)  VALUE SPACES.
    culprit: str = ""
    # COBOL: 05 ABEND-REASON  PIC X(50) VALUE SPACES.
    reason: str = ""
    # COBOL: 05 ABEND-MSG     PIC X(72) VALUE SPACES.
    message: str = ""


# ---------------------------------------------------------------------------
# Module exports
# ---------------------------------------------------------------------------
__all__ = [
    # Common User Messages (from CSMSG01Y.cpy)
    "CCDA_MSG_THANK_YOU",
    "CCDA_MSG_INVALID_KEY",
    # Screen Titles (from COTTL01Y.cpy)
    "CCDA_TITLE01",
    "CCDA_TITLE02",
    "CCDA_THANK_YOU",
    # Abend Data Structure (from CSMSG02Y.cpy)
    "AbendData",
    "ABEND_CODE_MAX_LEN",
    "ABEND_CULPRIT_MAX_LEN",
    "ABEND_REASON_MAX_LEN",
    "ABEND_MSG_MAX_LEN",
]
