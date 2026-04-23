# ============================================================================
# Source: app/cpy/CSSTRPFY.cpy (YYYY-STORE-PFKEY paragraph) —
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
"""CICS AID (Attention Identifier) key mapping and COBOL-style string utilities.

This module is the Python conversion of the COBOL copybook
``app/cpy/CSSTRPFY.cpy`` (the ``YYYY-STORE-PFKEY`` paragraph). It replaces the
CICS ``EIBAID``-to-``CCARD-AID-*`` 88-level-condition mapping pattern used by
every online COBOL program (``CO*.cbl``) to decide which action the terminal
operator requested (Enter / Clear / PA1 / PA2 / PF1 – PF24).

The CICS legacy mapping lives in a single ``EVALUATE TRUE`` block that
translates 28 distinct ``DFH*`` host-variable values into one of 16 logical
action codes — the PF13–PF24 keys are **folded** onto PF1–PF12 respectively,
matching the IBM 3270 convention where an upper-row PF key produces the same
semantic action as its lower-row sibling. This folding behavior is a
**business-level contract** and must be preserved exactly (AAP §0.7.1
"Preserve all existing functionality exactly as-is").

In the cloud-native target architecture this module is called by:

* FastAPI routers in ``src/api/routers/`` that receive a client-side
  ``action`` or ``aid`` field in the JSON request body and need to translate
  it to the same logical :class:`ActionCode` the COBOL service expected.
* GraphQL mutations in ``src/api/graphql/mutations.py`` that accept a
  ``pfKey`` input.
* Shared middleware in ``src/api/middleware/`` that may want to short-circuit
  on ``ActionCode.CLEAR`` (reset screen) or ``ActionCode.PA1`` (cancel).

Public surface
--------------
:class:`ActionCode`
    String enum with exactly 16 members corresponding 1:1 to the
    ``CCARD-AID-*`` 88-level condition names from
    ``CSSTRPFY.cpy``: ``ENTER``, ``CLEAR``, ``PA1``, ``PA2``, and
    ``PFK01`` through ``PFK12``.

:data:`AID_KEY_MAP`
    Module-level ``dict[str, ActionCode]`` with all legal input strings
    mapped to their canonical :class:`ActionCode`. Contains both the
    original CICS-style names (``DFHENTER``, ``DFHPF1`` … ``DFHPF24``) and
    API-friendly aliases (``ENTER``, ``PF1`` … ``PF24``). All keys are
    upper-case; see :func:`map_aid_key` for case-insensitive lookup.

:func:`map_aid_key`
    Case-insensitive translation from any recognized input string to an
    :class:`ActionCode`, returning ``None`` on unknown input. This replaces
    the COBOL ``EVALUATE TRUE`` block.

:func:`is_valid_aid_key`
    Boolean check wrapping :func:`map_aid_key` — convenient for FastAPI
    validators and Pydantic ``field_validator`` hooks.

:func:`get_pf_key_number`
    Reverse lookup that extracts the numeric PF key (1 – 12) from a
    ``PFK**`` :class:`ActionCode`. Returns ``None`` for ``ENTER``,
    ``CLEAR``, ``PA1``, ``PA2``.

:func:`left_pad`, :func:`right_pad`
    COBOL fixed-width field helpers. ``PIC X(n)`` fields in the legacy
    copybooks are right-padded with spaces; some numeric display fields
    (e.g. ``PIC 9(11)`` account IDs displayed on screen maps) are left
    padded with zeros or spaces. These helpers reproduce the legacy
    layout semantics for API responses that must match the original BMS
    field widths.

:func:`safe_strip`
    Whitespace-strip that tolerates ``None`` input — replaces the COBOL
    ``IF FIELD-NAME = SPACES OR LOW-VALUES`` idiom used throughout the
    online programs to detect empty input fields.

Design rules (from AAP §0.7.1 and the file-level instructions)
--------------------------------------------------------------
* **PF13–PF24 fold onto PF1–PF12** — identical to the COBOL lines 54-77.
* **Case-insensitive** AID-key lookup (both CICS and API-friendly names).
* **Enum values match COBOL** ``CCARD-AID-*`` condition names exactly
  (``CCARD-AID-PFK01`` → ``ActionCode.PFK01``).
* **Standard library only** — ``enum`` and ``typing`` only; no third-party
  dependencies. This keeps the module safe to import from AWS Glue
  Python-shell jobs with minimal cold-start penalty.
* **Python 3.11 compatible** — aligned with the AWS Glue 5.1 runtime and
  the FastAPI ``python:3.11-slim`` ECS container.

See Also
--------
AAP §0.5.1 — File-by-File Transformation Plan (``CSSTRPFY.cpy`` row).
AAP §0.7.1 — Refactoring-Specific Rules (preserve behavior exactly).
AAP §0.3.1 — Exhaustively In Scope (utility conversion scope).
"""

from __future__ import annotations

from enum import Enum
from typing import Optional

# ----------------------------------------------------------------------------
# Public re-export list — names consumers may legally import from this module.
# Any symbol not in this list is considered a private implementation detail
# and may change without notice.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "ActionCode",
    "AID_KEY_MAP",
    "map_aid_key",
    "is_valid_aid_key",
    "get_pf_key_number",
    "left_pad",
    "right_pad",
    "safe_strip",
]


# ============================================================================
# ActionCode — logical action enum matching COBOL CCARD-AID-* conditions
# ============================================================================
class ActionCode(str, Enum):  # noqa: UP042  # schema mandates `(str, Enum)` rather than `StrEnum`
    """Logical action code for all AID (Attention Identifier) keys.

    Every member of this enum corresponds 1:1 to a COBOL ``CCARD-AID-*``
    88-level condition flag as declared in the shared COMMAREA
    (see ``app/cpy/COCOM01Y.cpy``) and toggled by the
    ``YYYY-STORE-PFKEY`` paragraph of ``CSSTRPFY.cpy``.

    Source COBOL mapping (from ``CSSTRPFY.cpy`` lines 22-77)::

        CCARD-AID-ENTER  <->  DFHENTER
        CCARD-AID-CLEAR  <->  DFHCLEAR
        CCARD-AID-PA1    <->  DFHPA1
        CCARD-AID-PA2    <->  DFHPA2
        CCARD-AID-PFK01  <->  DFHPF1  or DFHPF13
        CCARD-AID-PFK02  <->  DFHPF2  or DFHPF14
        CCARD-AID-PFK03  <->  DFHPF3  or DFHPF15
        CCARD-AID-PFK04  <->  DFHPF4  or DFHPF16
        CCARD-AID-PFK05  <->  DFHPF5  or DFHPF17
        CCARD-AID-PFK06  <->  DFHPF6  or DFHPF18
        CCARD-AID-PFK07  <->  DFHPF7  or DFHPF19
        CCARD-AID-PFK08  <->  DFHPF8  or DFHPF20
        CCARD-AID-PFK09  <->  DFHPF9  or DFHPF21
        CCARD-AID-PFK10  <->  DFHPF10 or DFHPF22
        CCARD-AID-PFK11  <->  DFHPF11 or DFHPF23
        CCARD-AID-PFK12  <->  DFHPF12 or DFHPF24

    This enum subclasses ``str`` so its members compare equal to their
    string values (``ActionCode.ENTER == "ENTER"`` is ``True``). That
    behavior is needed by FastAPI when the enum is used as a request body
    field type: Pydantic can serialize the enum member directly to its
    string value in JSON responses without a custom encoder.

    Example
    -------
    >>> ActionCode.ENTER.value
    'ENTER'
    >>> ActionCode.PFK01 == "PFK01"
    True
    >>> list(ActionCode)[:4]
    [<ActionCode.ENTER: 'ENTER'>, <ActionCode.CLEAR: 'CLEAR'>, <ActionCode.PA1: 'PA1'>, <ActionCode.PA2: 'PA2'>]
    """

    # --- Non-PF keys ---------------------------------------------------------
    ENTER = "ENTER"
    CLEAR = "CLEAR"
    PA1 = "PA1"
    PA2 = "PA2"

    # --- Programmable Function keys 1 through 12 -----------------------------
    # PF13–PF24 are NOT enum members: they fold onto PFK01–PFK12 at mapping
    # time (see AID_KEY_MAP below). This matches the COBOL 88-level layout
    # which only defines CCARD-AID-PFK01 through CCARD-AID-PFK12 and has no
    # concept of a distinct PFK13..24 condition.
    PFK01 = "PFK01"
    PFK02 = "PFK02"
    PFK03 = "PFK03"
    PFK04 = "PFK04"
    PFK05 = "PFK05"
    PFK06 = "PFK06"
    PFK07 = "PFK07"
    PFK08 = "PFK08"
    PFK09 = "PFK09"
    PFK10 = "PFK10"
    PFK11 = "PFK11"
    PFK12 = "PFK12"


# ============================================================================
# AID_KEY_MAP — canonical input-string → ActionCode lookup table
# ============================================================================
# This dictionary is the Python equivalent of the COBOL ``EVALUATE TRUE``
# block in YYYY-STORE-PFKEY. Two independent name spaces are supported:
#
# 1. CICS legacy names: ``DFHENTER``, ``DFHCLEAR``, ``DFHPA1``, ``DFHPA2``,
#    ``DFHPF1`` through ``DFHPF24`` — the exact host variable identifiers
#    used by the COBOL ``WHEN EIBAID IS EQUAL TO DFH*`` clauses. These are
#    included for two reasons:
#       a. Backwards compatibility with any client or test harness that
#          speaks the legacy vocabulary (e.g., screen-scraping test
#          fixtures migrated from the mainframe).
#       b. Straightforward diff review against the COBOL source — a
#          reviewer can visually line up each ``DFH*`` key here with the
#          corresponding ``WHEN EIBAID IS EQUAL TO DFH*`` clause in
#          CSSTRPFY.cpy.
#
# 2. API-friendly aliases: ``ENTER``, ``CLEAR``, ``PA1``, ``PA2``, ``PF1``
#    through ``PF24`` — the bare names a cloud-native REST client is
#    expected to send. Every alias returns the same ActionCode as its
#    ``DFH*`` counterpart.
#
# **CRITICAL — PF13–PF24 folding**: Both ``DFHPF13`` / ``PF13`` map to
# ``ActionCode.PFK01``, ``DFHPF14`` / ``PF14`` map to ``ActionCode.PFK02``,
# and so on through ``DFHPF24`` / ``PF24`` → ``ActionCode.PFK12``. This
# folding is mandated by COBOL lines 54-77 and must be preserved exactly
# (AAP §0.7.1 "preserve existing functionality exactly as-is").
#
# All keys are stored in UPPER CASE. Callers must use :func:`map_aid_key`
# for case-insensitive lookup; direct dictionary access is case-sensitive.
# ----------------------------------------------------------------------------
AID_KEY_MAP: dict[str, ActionCode] = {
    # -- CICS-style identifiers (legacy / mainframe-equivalent) ---------------
    "DFHENTER": ActionCode.ENTER,
    "DFHCLEAR": ActionCode.CLEAR,
    "DFHPA1": ActionCode.PA1,
    "DFHPA2": ActionCode.PA2,
    # PF1–PF12 direct mapping (CSSTRPFY.cpy lines 30-53)
    "DFHPF1": ActionCode.PFK01,
    "DFHPF2": ActionCode.PFK02,
    "DFHPF3": ActionCode.PFK03,
    "DFHPF4": ActionCode.PFK04,
    "DFHPF5": ActionCode.PFK05,
    "DFHPF6": ActionCode.PFK06,
    "DFHPF7": ActionCode.PFK07,
    "DFHPF8": ActionCode.PFK08,
    "DFHPF9": ActionCode.PFK09,
    "DFHPF10": ActionCode.PFK10,
    "DFHPF11": ActionCode.PFK11,
    "DFHPF12": ActionCode.PFK12,
    # PF13–PF24 FOLDED onto PF1–PF12 (CSSTRPFY.cpy lines 54-77)
    # This folding is required by the COBOL source and is a behavioral
    # contract — it must NOT be "fixed" or "optimized" away.
    "DFHPF13": ActionCode.PFK01,
    "DFHPF14": ActionCode.PFK02,
    "DFHPF15": ActionCode.PFK03,
    "DFHPF16": ActionCode.PFK04,
    "DFHPF17": ActionCode.PFK05,
    "DFHPF18": ActionCode.PFK06,
    "DFHPF19": ActionCode.PFK07,
    "DFHPF20": ActionCode.PFK08,
    "DFHPF21": ActionCode.PFK09,
    "DFHPF22": ActionCode.PFK10,
    "DFHPF23": ActionCode.PFK11,
    "DFHPF24": ActionCode.PFK12,
    # -- API-friendly aliases (cloud-native equivalents) ----------------------
    "ENTER": ActionCode.ENTER,
    "CLEAR": ActionCode.CLEAR,
    "PA1": ActionCode.PA1,
    "PA2": ActionCode.PA2,
    # PF1–PF12 direct mapping
    "PF1": ActionCode.PFK01,
    "PF2": ActionCode.PFK02,
    "PF3": ActionCode.PFK03,
    "PF4": ActionCode.PFK04,
    "PF5": ActionCode.PFK05,
    "PF6": ActionCode.PFK06,
    "PF7": ActionCode.PFK07,
    "PF8": ActionCode.PFK08,
    "PF9": ActionCode.PFK09,
    "PF10": ActionCode.PFK10,
    "PF11": ActionCode.PFK11,
    "PF12": ActionCode.PFK12,
    # PF13–PF24 FOLDED onto PF1–PF12 (matches DFHPF* folding above)
    "PF13": ActionCode.PFK01,
    "PF14": ActionCode.PFK02,
    "PF15": ActionCode.PFK03,
    "PF16": ActionCode.PFK04,
    "PF17": ActionCode.PFK05,
    "PF18": ActionCode.PFK06,
    "PF19": ActionCode.PFK07,
    "PF20": ActionCode.PFK08,
    "PF21": ActionCode.PFK09,
    "PF22": ActionCode.PFK10,
    "PF23": ActionCode.PFK11,
    "PF24": ActionCode.PFK12,
    # -- PFK-prefixed aliases (direct enum-value names) -----------------------
    # These are included so that round-tripping is possible: a client that
    # receives an ActionCode value and echoes it back as a string can still
    # be parsed. For example, if an API response contains ``"action":
    # "PFK01"`` and a later request echoes that value back, the same
    # mapping should resolve. No folding is needed here because PFK01–PFK12
    # are already the canonical post-folded identifiers.
    "PFK01": ActionCode.PFK01,
    "PFK02": ActionCode.PFK02,
    "PFK03": ActionCode.PFK03,
    "PFK04": ActionCode.PFK04,
    "PFK05": ActionCode.PFK05,
    "PFK06": ActionCode.PFK06,
    "PFK07": ActionCode.PFK07,
    "PFK08": ActionCode.PFK08,
    "PFK09": ActionCode.PFK09,
    "PFK10": ActionCode.PFK10,
    "PFK11": ActionCode.PFK11,
    "PFK12": ActionCode.PFK12,
}


# ============================================================================
# AID-key mapping functions
# ============================================================================
def map_aid_key(aid_identifier: str) -> Optional[ActionCode]:  # noqa: UP045  # schema requires `typing.Optional`
    """Translate a client-supplied AID identifier to a canonical :class:`ActionCode`.

    This is the Python equivalent of the COBOL ``EVALUATE TRUE`` block in
    the ``YYYY-STORE-PFKEY`` paragraph of ``CSSTRPFY.cpy``. It accepts both
    CICS-style names (``DFHPF1``) and API-friendly aliases (``PF1``),
    performs a case-insensitive and whitespace-tolerant lookup, and
    returns the matching enum member — or ``None`` when the input is
    unrecognized, empty, or ``None``.

    Parameters
    ----------
    aid_identifier:
        The AID identifier string supplied by the client. Accepts
        ``DFHENTER``, ``DFHCLEAR``, ``DFHPA1``, ``DFHPA2``,
        ``DFHPF1`` – ``DFHPF24``, ``ENTER``, ``CLEAR``, ``PA1``,
        ``PA2``, ``PF1`` – ``PF24``, and ``PFK01`` – ``PFK12``.
        Lookup is case-insensitive. Leading / trailing whitespace is
        stripped. PF13–PF24 fold onto PF1–PF12.

    Returns
    -------
    :class:`ActionCode` | None
        The canonical :class:`ActionCode` member for the input, or
        ``None`` if the identifier is unrecognized, the input is
        an empty / whitespace-only string, or the input is not a
        string at all. **Intentionally never raises** — callers get a
        tri-state answer (valid / invalid / None) without needing
        to wrap calls in ``try``/``except``. This matches the COBOL
        semantics where an unrecognized EIBAID value would simply leave
        all ``CCARD-AID-*`` conditions false.

    Notes
    -----
    The function tolerates ``None`` / non-string input defensively
    (returning ``None``) to keep FastAPI request-parsing flow simple:
    Pydantic may deliver a stripped / empty string to a router, and the
    caller can treat ``map_aid_key(body.action)`` as a safe expression.

    Examples
    --------
    >>> map_aid_key("DFHPF1")
    <ActionCode.PFK01: 'PFK01'>
    >>> map_aid_key("pf13")  # PF13 folds onto PFK01
    <ActionCode.PFK01: 'PFK01'>
    >>> map_aid_key("  enter  ")
    <ActionCode.ENTER: 'ENTER'>
    >>> map_aid_key("") is None
    True
    >>> map_aid_key("UNKNOWN") is None
    True
    """
    # Defensive handling: non-string or falsy input returns None to keep
    # router code simple. This matches COBOL semantics where an
    # unrecognized EIBAID value leaves all CCARD-AID-* conditions false.
    if aid_identifier is None:
        return None
    if not isinstance(aid_identifier, str):
        return None

    # Normalize whitespace and case. AID_KEY_MAP keys are all uppercase.
    normalized = aid_identifier.strip().upper()
    if not normalized:
        return None

    # dict.get returns None for unknown keys, which is the documented
    # contract. Do NOT raise KeyError here — callers rely on the
    # three-state (valid / invalid / None) response.
    return AID_KEY_MAP.get(normalized)


def is_valid_aid_key(aid_identifier: str) -> bool:
    """Return ``True`` iff ``aid_identifier`` maps to a known :class:`ActionCode`.

    Thin boolean wrapper around :func:`map_aid_key` intended for use as:

    * A FastAPI / Pydantic ``field_validator`` predicate.
    * A Strawberry GraphQL input-type custom validator.
    * A quick pre-check before attempting more expensive action dispatch.

    Parameters
    ----------
    aid_identifier:
        Any input the caller wishes to test. Accepts exactly the same
        inputs as :func:`map_aid_key` (including ``None`` and
        non-string types — both return ``False`` rather than raising).

    Returns
    -------
    bool
        ``True`` if :func:`map_aid_key` would return a non-``None``
        :class:`ActionCode`; ``False`` otherwise.

    Examples
    --------
    >>> is_valid_aid_key("DFHENTER")
    True
    >>> is_valid_aid_key("PF24")      # folds onto PFK12 — still valid
    True
    >>> is_valid_aid_key("XYZ")
    False
    >>> is_valid_aid_key("")
    False
    >>> is_valid_aid_key(None)
    False
    """
    return map_aid_key(aid_identifier) is not None


def get_pf_key_number(action_code: ActionCode) -> Optional[int]:  # noqa: UP045  # schema requires `typing.Optional`
    """Return the numeric PF-key index (1-12) for a ``PFK**`` :class:`ActionCode`.

    This is the inverse of the :data:`AID_KEY_MAP` encoding for the twelve
    canonical PF keys. For the four non-PF actions
    (``ENTER``, ``CLEAR``, ``PA1``, ``PA2``) the function returns
    ``None`` — there is no associated PF number.

    This utility is primarily intended for building human-readable
    response metadata and log messages (e.g.,
    ``f"Operator pressed PF{get_pf_key_number(action)}"``) and for
    GraphQL types that expose a separate ``pfNumber`` field.

    Parameters
    ----------
    action_code:
        An :class:`ActionCode` member.

    Returns
    -------
    int | None
        An integer in ``[1, 12]`` for ``ActionCode.PFK01`` through
        ``ActionCode.PFK12``; ``None`` for ``ENTER``, ``CLEAR``,
        ``PA1``, ``PA2``, or any other value.

    Raises
    ------
    (Nothing.) Non-:class:`ActionCode` input returns ``None`` rather
    than raising, for parity with :func:`map_aid_key`.

    Examples
    --------
    >>> get_pf_key_number(ActionCode.PFK01)
    1
    >>> get_pf_key_number(ActionCode.PFK12)
    12
    >>> get_pf_key_number(ActionCode.ENTER) is None
    True
    >>> get_pf_key_number(ActionCode.PA2) is None
    True
    """
    # Validate type defensively — permit str-equal comparison with enum
    # values (ActionCode subclasses str) but still require the string
    # value to match the canonical PFK** prefix.
    if not isinstance(action_code, ActionCode):
        return None

    # ActionCode values are always of the form "PFKnn" (n in 01..12),
    # "ENTER", "CLEAR", "PA1", or "PA2". Only the PFK-prefixed codes
    # have an associated numeric index.
    value = action_code.value
    if not value.startswith("PFK"):
        return None

    # The substring after "PFK" is guaranteed to be two ASCII digits
    # because the enum is closed (only 12 PFK** members exist).
    suffix = value[3:]
    try:
        number = int(suffix)
    except ValueError:
        # Unreachable given the closed enum, but defensively return None.
        return None

    # Sanity clamp — the enum is closed so this is always true, but we
    # assert the invariant explicitly for defense in depth.
    if 1 <= number <= 12:
        return number
    return None


# ============================================================================
# COBOL-style fixed-width string padding
# ============================================================================
def left_pad(value: str, length: int, pad_char: str = " ") -> str:
    """Left-pad ``value`` with ``pad_char`` to at least ``length`` characters.

    Mirrors the COBOL fixed-width field-display convention where a numeric
    or short string value is right-justified inside a larger display
    field by prepending filler characters. Typical uses include:

    * Displaying an 11-digit account ID stored as ``PIC 9(11)`` with
      leading zero padding in a BMS output map.
    * Right-aligning currency display strings within a fixed column
      width on a report layout (legacy fixed-width report output
      reused via :class:`~src.batch.jobs.creastmt_job`).

    Parameters
    ----------
    value:
        The input string to pad. Must be a ``str``; ``None`` raises
        :class:`TypeError` (a caller that may receive ``None`` should
        first call :func:`safe_strip`).
    length:
        The desired total width. Must be a non-negative integer. If
        ``value`` is already at least this long, it is returned
        unchanged (no truncation). Passing a negative ``length``
        raises :class:`ValueError`.
    pad_char:
        The fill character. Defaults to ASCII space (``" "``), matching
        the COBOL ``PIC X(n)`` SPACES convention for alphanumeric
        fields. Must be a single character; otherwise
        :class:`ValueError` is raised. The common alternatives are
        ``"0"`` (for numeric-display padding of PIC 9(n) fields) and
        ``" "`` (default).

    Returns
    -------
    str
        The right-justified, left-padded string. Length is
        ``max(len(value), length)``.

    Raises
    ------
    TypeError
        If ``value`` is not a string, or if ``length`` is not an
        integer, or if ``pad_char`` is not a string.
    ValueError
        If ``length`` is negative or ``pad_char`` is not exactly one
        character.

    Examples
    --------
    >>> left_pad("42", 5)
    '   42'
    >>> left_pad("42", 5, pad_char="0")
    '00042'
    >>> left_pad("already_long_enough", 3)
    'already_long_enough'
    >>> left_pad("", 3, pad_char="x")
    'xxx'
    """
    _validate_pad_args(value, length, pad_char)

    # If already at or above the target width, return unchanged — COBOL
    # would truncate here, but Python string padding semantics (and the
    # principle of least data loss) dictate preserving the original.
    if len(value) >= length:
        return value

    # str.rjust is the standard-library primitive for left-padding to a
    # right-justified fixed width. Using the built-in keeps the
    # implementation short and delegates Unicode-safe character counting
    # to CPython.
    return value.rjust(length, pad_char)


def right_pad(value: str, length: int, pad_char: str = " ") -> str:
    """Right-pad ``value`` with ``pad_char`` to at least ``length`` characters.

    Mirrors the COBOL ``PIC X(n)`` / ``MOVE ... TO`` convention where an
    alphanumeric field shorter than its declared length is filled with
    spaces on the right. Typical uses include:

    * Padding a customer name stored as ``PIC X(25)`` (see
      ``CUST-FIRST-NAME`` in ``CVCUS01Y.cpy``) so that downstream
      fixed-width output (statements, reports) aligns the next field.
    * Preparing a reason-code string for reject-log writes where
      Glue expects a fixed-width column.

    Parameters
    ----------
    value:
        The input string to pad. Must be a ``str``; ``None`` raises
        :class:`TypeError`.
    length:
        Desired total width. Non-negative integer. If ``value`` is
        already at least this long it is returned unchanged (no
        truncation). Negative ``length`` raises :class:`ValueError`.
    pad_char:
        Fill character. Defaults to space. Must be exactly one
        character.

    Returns
    -------
    str
        The left-justified, right-padded string. Length is
        ``max(len(value), length)``.

    Raises
    ------
    TypeError
        If ``value`` is not a string, or if ``length`` is not an
        integer, or if ``pad_char`` is not a string.
    ValueError
        If ``length`` is negative or ``pad_char`` is not exactly one
        character.

    Examples
    --------
    >>> right_pad("JOHN", 10)
    'JOHN      '
    >>> right_pad("JOHN", 10, pad_char="-")
    'JOHN------'
    >>> right_pad("already_long_enough", 3)
    'already_long_enough'
    >>> right_pad("", 4)
    '    '
    """
    _validate_pad_args(value, length, pad_char)

    # If already at or above target width, return unchanged (no truncation).
    if len(value) >= length:
        return value

    # str.ljust is the standard-library primitive for right-padding to a
    # left-justified fixed width. Unicode-safe.
    return value.ljust(length, pad_char)


def safe_strip(value: Optional[str]) -> str:  # noqa: UP045  # schema requires `typing.Optional`
    """Strip whitespace from ``value``, tolerating ``None`` and non-string input.

    Replaces the COBOL idiom::

        IF FIELD-NAME = SPACES OR LOW-VALUES
            MOVE SPACES TO FIELD-NAME
        END-IF

    which appears throughout the online COBOL programs
    (``COACTUPC``, ``COUSR01C``, etc.) as a defensive check before
    using an input field. In Python the cloud-native equivalent is to
    strip whitespace and substitute an empty string for ``None`` /
    ``LOW-VALUES`` / anything that would be treated as "empty" by a
    Pydantic validator.

    Parameters
    ----------
    value:
        The value to strip. Accepts ``str`` and ``None``. Any other
        type is converted to ``str`` via :func:`str` first — for
        example, numeric or enum values are coerced — matching the
        forgiving behavior of COBOL ``MOVE`` between compatible PIC
        clauses. This is intentional: the function is designed to
        never raise on unexpected types during request parsing.

    Returns
    -------
    str
        The whitespace-stripped string, or an empty string if
        ``value`` is ``None`` or produces an empty string after
        stripping.

    Examples
    --------
    >>> safe_strip("  HELLO  ")
    'HELLO'
    >>> safe_strip(None)
    ''
    >>> safe_strip("")
    ''
    >>> safe_strip("   ")
    ''
    >>> safe_strip(42)     # non-string input is coerced to str then stripped
    '42'
    """
    if value is None:
        return ""
    # Fast path for the common str case. The non-str branch uses str()
    # coercion for defensive forgiveness — a consumer that accidentally
    # passes an int / Decimal / enum value still gets a reasonable
    # result without an exception, matching COBOL MOVE semantics across
    # compatible PIC types.
    if isinstance(value, str):
        return value.strip()
    try:
        return str(value).strip()
    except Exception:  # pragma: no cover — defensive, almost never taken
        # If str() raises (extremely unusual), fall back to empty string
        # to preserve the "never raises" contract of this helper.
        return ""


# ============================================================================
# Internal helpers — private implementation details (not in __all__)
# ============================================================================
def _validate_pad_args(value: str, length: int, pad_char: str) -> None:
    """Validate arguments common to :func:`left_pad` and :func:`right_pad`.

    Factored out to keep both public pad functions tight and to ensure
    identical error messages and error types. Raises on invalid input
    rather than returning silently, because pad operations with bad
    arguments almost always indicate a programming error and failing
    fast is the safer default.

    Parameters
    ----------
    value, length, pad_char
        Same as :func:`left_pad` / :func:`right_pad`.

    Raises
    ------
    TypeError
        * ``value`` is not a ``str``
        * ``length`` is not an ``int`` (``bool`` specifically rejected
          because ``True`` / ``False`` are technically ``int`` subclasses
          but almost never the caller's intent)
        * ``pad_char`` is not a ``str``
    ValueError
        * ``length`` is negative
        * ``pad_char`` is not exactly one character
    """
    if not isinstance(value, str):
        raise TypeError(f"value must be a str; got {type(value).__name__}")
    # Reject bool explicitly — isinstance(True, int) is True in Python,
    # but passing True/False as a length is almost certainly a mistake.
    if isinstance(length, bool) or not isinstance(length, int):
        raise TypeError(f"length must be an int; got {type(length).__name__}")
    if length < 0:
        raise ValueError(f"length must be non-negative; got {length}")
    if not isinstance(pad_char, str):
        raise TypeError(f"pad_char must be a str; got {type(pad_char).__name__}")
    if len(pad_char) != 1:
        raise ValueError(f"pad_char must be exactly one character; got {len(pad_char)} characters ({pad_char!r})")
