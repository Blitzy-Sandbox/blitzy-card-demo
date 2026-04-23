# ============================================================================
# Source: app/cpy/CSMSG01Y.cpy (CCDA-COMMON-MESSAGES — user-facing messages)
#         + app/cpy/CSMSG02Y.cpy (ABEND-DATA — abend routine work areas)
#         + app/cpy/COTTL01Y.cpy (CCDA-SCREEN-TITLE — screen titles) —
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
"""Global exception handler middleware for CardDemo API.

Converts COBOL-style error codes and ``ABEND-DATA`` work areas (from
``app/cpy/CSMSG02Y.cpy``) into standardized JSON error responses for the
FastAPI application. Maps CICS RESP codes to HTTP status codes and
preserves the COBOL common-message semantics from ``app/cpy/CSMSG01Y.cpy``
(e.g., ``CCDA-MSG-INVALID-KEY`` for validation failures,
``CCDA-MSG-THANK-YOU`` for graceful successful terminations).

Mainframe -> Cloud Mapping
--------------------------
In the original COBOL/CICS implementation, errors were handled by three
collaborating mechanisms:

1. **CICS RESP codes** (returned by every ``EXEC CICS`` call into
   ``WS-RESP-CD`` PIC S9(09) COMP) — ``0`` is NORMAL, ``13`` is NOTFND,
   ``14`` is DUPREC, ``16`` is INVREQ, ``22`` is LENGERR, ``27`` is
   NOTOPEN, ``70`` is DISABLED, ``81`` is ILLOGIC, ``84`` is IOERR, etc.
   Programs contain ``EVALUATE WS-RESP-CD`` blocks that branch to
   specific user-facing error messages per code (see, e.g.,
   ``COSGN00C.cbl`` lines 221-257 which branches on RESP=0 success vs
   RESP=13 NOTFND).
2. **``ABEND-DATA`` work area** (from ``CSMSG02Y.cpy``) populated by the
   program immediately prior to calling the abend routine — four
   fixed-width fields: ``ABEND-CODE`` PIC X(4), ``ABEND-CULPRIT``
   PIC X(8), ``ABEND-REASON`` PIC X(50), ``ABEND-MSG`` PIC X(72).
3. **BMS SEND MAP** rendering that displays ``ABEND-CODE``,
   ``ABEND-REASON``, etc. in the error region of the current screen, or
   ``EXEC CICS SEND TEXT`` rendering a dedicated abend screen.

In the cloud-native target architecture, these become:

1. **HTTP status codes** — the :data:`CICS_RESP_TO_HTTP` table provides
   the canonical forward mapping used by service-layer code when
   translating a raw CICS RESP code (carried through as context in some
   error paths) into an HTTP status. The handlers here use the
   *reverse* direction — given an HTTP status, derive a short mnemonic
   4-character error code (``NFND`` for 404, ``DUPR`` for 409, etc.)
   that preserves the spirit of ``ABEND-CODE`` PIC X(4).
2. **JSON response bodies** whose ``error`` object mirrors the
   ``ABEND-DATA`` layout exactly (``error_code`` <-> ``ABEND-CODE``,
   ``culprit`` <-> ``ABEND-CULPRIT``, ``reason`` <-> ``ABEND-REASON``,
   ``message`` <-> ``ABEND-MSG``) plus two cloud-native extensions
   (``timestamp`` for CloudWatch correlation and ``path`` for API
   routing context).
3. **FastAPI exception handlers** registered with
   :func:`register_exception_handlers` that catch every unhandled
   exception, convert it to the JSON ``ABEND-DATA`` shape, and return a
   :class:`fastapi.responses.JSONResponse`.

Public Surface
--------------
:data:`CICS_RESP_TO_HTTP`
    Immutable mapping from CICS RESP code (integer) to HTTP status code
    (integer) covering the codes observed across the 18 online COBOL
    programs. Exported so service-layer code that raises HTTP errors in
    response to a captured RESP code can stay aligned with the handler
    here.
:func:`register_exception_handlers`
    Registers four exception handlers — HTTPException, Pydantic
    validation errors, SQLAlchemy database errors, and a catch-all —
    onto a FastAPI application instance. Call this once from
    ``src/api/main.py`` during application bootstrap.

Security & Observability Properties
-----------------------------------
* **Never expose internals to clients** — stack traces, database query
  SQL, and internal exception messages are logged server-side only (via
  the module logger) and never appear in the JSON response body sent to
  API clients. This mirrors the z/OS convention where ABEND details
  stayed in SYSLOG / SMF and only the curated ``ABEND-MSG`` reached the
  terminal.
* **Structured JSON logging** — every handler emits a log record with
  an ``extra`` dict carrying ``error_code``, ``path``, ``method``, and
  ``exception_type`` so the AWS CloudWatch ``awslogs`` driver attached
  to the ECS Fargate task indexes these fields for search and alarms.
* **Severity-aligned log levels** — ``logger.warning`` for 4xx client
  errors (expected outcomes), ``logger.error`` for 5xx server errors
  and database errors (operator action likely required), and
  ``logger.critical`` for unhandled exceptions (the cloud-native
  equivalent of the ``CEE3ABD`` abend routine that the COBOL programs
  terminated into).
* **PIC X(n) width preservation** — all four ``ABEND-DATA``-equivalent
  fields are truncated to their original COBOL byte-widths (via the
  ``ABEND_*_MAX_LEN`` constants re-exported from
  :mod:`src.shared.constants.messages`) so downstream integrations that
  rely on the fixed-width contract (log parsers, COBOL-era dashboards)
  continue to function.

See Also
--------
AAP Section 0.5.1 — File-by-File Transformation Plan (this file's row)
AAP Section 0.6.1 — Key Public Packages (fastapi, sqlalchemy)
AAP Section 0.7 — Refactoring Rules (minimal-change, preserve semantics)
``src/shared/constants/messages.py`` — AbendData dataclass & message constants
``src/api/middleware/auth.py`` — sibling middleware (JWT validation)
``src/api/main.py`` — invokes :func:`register_exception_handlers`
"""

from __future__ import annotations

import datetime
import logging
import os
import re
import traceback
from collections.abc import Mapping, Sequence
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError

# Note: we import the Starlette base class rather than
# ``fastapi.HTTPException`` because (a) the latter subclasses the
# former, so registering on the Starlette base catches both, and
# (b) the default 404 raised by Starlette for unmatched routes is
# a bare ``starlette.exceptions.HTTPException`` not a
# ``fastapi.HTTPException``, so registering only on the FastAPI
# subclass missed the unmatched-route 404 case (QA Checkpoint 2,
# Issue 5 — ABEND-DATA format inconsistency).
from starlette.exceptions import HTTPException as StarletteHTTPException

from src.shared.constants.messages import (
    ABEND_CODE_MAX_LEN,
    ABEND_CULPRIT_MAX_LEN,
    ABEND_MSG_MAX_LEN,
    ABEND_REASON_MAX_LEN,
    CCDA_MSG_INVALID_KEY,
    CCDA_MSG_THANK_YOU,
    AbendData,
)

# ----------------------------------------------------------------------------
# Module-level structured logger.
#
# All error events emit a structured `extra` dict so that the CloudWatch
# awslogs driver (attached to the ECS Fargate task definition) can index
# fields like `error_code`, `path`, and `method` as searchable
# attributes. The module name `src.api.middleware.error_handler` is the
# natural log-source identifier.
#
# CRITICAL: The logger may receive full stack traces and internal
# exception messages; it MUST NEVER receive those fields back out to a
# JSON response body. Helper `_build_error_response()` is the sole
# sanitized surface reaching API clients.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Phase 2 — CICS RESP Code to HTTP Status Mapping
# ============================================================================

#: Canonical mapping from CICS RESP code to HTTP status code.
#:
#: CICS RESP codes are the return values of every ``EXEC CICS`` call,
#: stored in the program's ``WS-RESP-CD`` PIC S9(09) COMP field. The
#: 18 online COBOL programs branch on this code in ``EVALUATE
#: WS-RESP-CD`` blocks to generate user-facing error messages. The
#: canonical CICS symbolic constants (e.g., ``DFHRESP(NORMAL)``,
#: ``DFHRESP(NOTFND)``) resolve to the integer values shown below
#: (from the ``DFHRESP`` copy-in expansion in the IBM CICS manual).
#:
#: The integer keys are deliberately used rather than symbolic constants
#: because Python has no direct equivalent of ``DFHRESP``; the comment
#: on each line records the original symbolic name for traceability.
#:
#: ===========  =======  ====================================================
#: CICS RESP    HTTP     Meaning / COBOL origin
#: ===========  =======  ====================================================
#:   0 NORMAL   200 OK   Successful CICS call. Example:
#:                       ``COSGN00C.cbl`` lines 221-229 on successful
#:                       ``READ DATASET('USRSEC')``.
#:  13 NOTFND   404      Requested record not found in dataset. Example:
#:                       ``COSGN00C.cbl`` lines 240-249 where
#:                       ``RESP=13`` on ``READ DATASET('USRSEC')``
#:                       produces the 'User not found. Try again ...'
#:                       message.
#:  14 DUPREC   409      Duplicate record on WRITE. Example:
#:                       ``COUSR01C.cbl`` (user-add) where adding a user
#:                       whose ID already exists returns RESP=14.
#:  16 INVREQ   400      Invalid request (command not allowed in current
#:                       context, e.g., WRITE to read-only dataset, or
#:                       invalid field on a map).
#:  22 LENGERR  400      Length error on COMMAREA / map / file I/O
#:                       (data exceeded declared size).
#:  27 NOTOPEN  503      File not open — the CICS file-control entry is
#:                       closed or quiesced. Equivalent to the target
#:                       database being temporarily unavailable.
#:  70 DISABLED 500      Transaction or program disabled administratively;
#:                       a configuration/operator error in the mainframe
#:                       equivalent of an unhealthy service.
#:  81 ILLOGIC  500      VSAM detected an internal inconsistency (a
#:                       logic error in the application or file). No
#:                       clean client recovery path.
#:  84 IOERR    500      Generic I/O error — disk, channel, or VSAM
#:                       hardware-level failure. No clean client
#:                       recovery path.
#: ===========  =======  ====================================================
#:
#: This constant is part of the module's public API (see ``__all__``)
#: so service-layer code can reuse the mapping when translating a
#: captured CICS RESP context value (e.g., bubbled up from a migrated
#: diagnostic path) into the appropriate HTTP response.
CICS_RESP_TO_HTTP: dict[int, int] = {
    0: 200,  # DFHRESP(NORMAL)    -> 200 OK
    13: 404,  # DFHRESP(NOTFND)    -> 404 Not Found
    14: 409,  # DFHRESP(DUPREC)    -> 409 Conflict
    16: 400,  # DFHRESP(INVREQ)    -> 400 Bad Request
    22: 400,  # DFHRESP(LENGERR)   -> 400 Bad Request (length error)
    27: 503,  # DFHRESP(NOTOPEN)   -> 503 Service Unavailable
    70: 500,  # DFHRESP(DISABLED)  -> 500 Internal Server Error
    81: 500,  # DFHRESP(ILLOGIC)   -> 500 Internal Server Error
    84: 500,  # DFHRESP(IOERR)     -> 500 Internal Server Error
}


# ----------------------------------------------------------------------------
# Reverse-direction convenience table: HTTP status -> 4-character ABEND-CODE.
#
# Used by the HTTPException handler to populate ``error_code`` in the JSON
# response body. Multiple CICS RESP codes can map to the same HTTP status
# (70, 81, and 84 all -> 500), so a deterministic representative code is
# chosen per HTTP status. Codes are short 4-character mnemonics matching
# the PIC X(4) width of COBOL ``ABEND-CODE``.
#
# Statuses not listed here fall back to a generic ``"H{status}"`` pattern
# (e.g., 418 -> "H418") which is also <= 4 characters.
# ----------------------------------------------------------------------------
_HTTP_STATUS_TO_ERROR_CODE: dict[int, str] = {
    200: "NRML",  # DFHRESP(NORMAL)  — successful exit
    400: "INVR",  # DFHRESP(INVREQ)  / LENGERR — bad request
    401: "AUTH",  # Authentication required (missing/invalid JWT)
    403: "FRBD",  # Forbidden (admin-only endpoint, non-admin caller)
    404: "NFND",  # DFHRESP(NOTFND)  — record not found
    409: "DUPR",  # DFHRESP(DUPREC)  — duplicate record
    422: "VALD",  # Pydantic validation failure ('Invalid key pressed...')
    500: "IOER",  # DFHRESP(IOERR)   / ILLOGIC / DISABLED / generic 5xx
    503: "NOPN",  # DFHRESP(NOTOPEN) — service unavailable
}

# ----------------------------------------------------------------------------
# Default user-facing messages for HTTP status classes.
#
# These align with the COBOL common messages from ``CSMSG01Y.cpy``
# that are consumed by every online program's error rendering. Both
# ``CCDA_MSG_THANK_YOU`` and ``CCDA_MSG_INVALID_KEY`` are used here.
#
# * ``CCDA_MSG_THANK_YOU`` — used as the default ``message`` for
#   graceful-exit HTTPExceptions (2xx intentionally raised) because in
#   the COBOL world this message is sent on PF3 exit (see
#   ``COSGN00C.cbl`` lines 98-100 on the DFHPF3 branch).
# * ``CCDA_MSG_INVALID_KEY`` — used as the default ``reason`` for
#   Pydantic validation failures and as a fallback ``message`` for 4xx
#   client errors without an explicit detail, mirroring the COBOL
#   catch-all for unrecognized input keys (see the 'WHEN OTHER' branch
#   in most online programs' EVALUATE EIBAID block, e.g.,
#   ``COSGN00C.cbl`` lines 93-96).
# ----------------------------------------------------------------------------
_DEFAULT_HTTP_MESSAGES: dict[int, str] = {
    200: CCDA_MSG_THANK_YOU,  # CCDA-MSG-THANK-YOU — graceful exit
    400: CCDA_MSG_INVALID_KEY,  # CCDA-MSG-INVALID-KEY — bad input
    422: CCDA_MSG_INVALID_KEY,  # CCDA-MSG-INVALID-KEY — validation failed
}


# ============================================================================
# Phase 3 — Private Helpers
# ============================================================================


# ----------------------------------------------------------------------------
# Sensitive-field allowlist for log redaction (QA Checkpoint 6 Issue #2).
#
# When a ``fastapi.exceptions.RequestValidationError`` is raised, its
# ``errors()`` method returns a list of dicts each of the form::
#
#     {
#         "type": "string_too_long",
#         "loc": ("body", "password"),
#         "msg": "String should have at most 8 characters",
#         "input": "<original submitted value>",  <-- CWE-532 risk
#         "ctx": {"max_length": 8},
#     }
#
# The ``input`` field contains the *original, unmodified* user input
# that triggered the validation failure. For login endpoints this
# includes the plaintext password; for user-create endpoints this
# includes SSNs, card numbers, secrets, tokens, etc. Logging this
# field verbatim — as the default ``exc.errors()`` usage does —
# violates CWE-532 ("Insertion of Sensitive Information into Log File")
# and was the root cause of QA Checkpoint 6 CRITICAL Issue #2.
#
# The set below enumerates the exact field names (case-insensitive)
# whose ``input`` must be masked before the errors list is logged.
# The set uses singular lower-case names; matching is done via
# ``field_name.lower() in _SENSITIVE_FIELD_NAMES`` so variants like
# ``Password`` or ``PASSWORD`` all match.
#
# This list is intentionally broad — it is safer to over-redact than
# to under-redact. The only downside of over-redaction is that a
# field named ``password_hint`` would also be masked in logs, which
# is an acceptable false-positive given the field is not critical
# for debugging (type + loc + msg are still present).
# ----------------------------------------------------------------------------
_SENSITIVE_FIELD_NAMES: frozenset[str] = frozenset(
    {
        # Direct password-like fields.
        "password",
        "passwd",
        "pwd",
        "current_password",
        "new_password",
        "old_password",
        "confirm_password",
        "password_confirm",
        "user_password",
        # Secret / API key / token-like fields.
        "secret",
        "secret_key",
        "client_secret",
        "token",
        "access_token",
        "refresh_token",
        "auth_token",
        "jwt",
        "bearer",
        "api_key",
        "apikey",
        "authorization",
        # Personally-identifying secrets.
        "ssn",
        "social_security_number",
        "pin",
        # Payment-card sensitive fields.
        "cvv",
        "cvv2",
        "cvc",
        "card_cvv",
        "card_num",
        "card_number",
        "pan",
        # Private key fields.
        "private_key",
        "signing_key",
    }
)

# ----------------------------------------------------------------------------
# Sentinel string that replaces the ``input`` field of sensitive
# validation errors. Deliberately distinctive so log consumers can
# visually spot redaction occurrences, and deterministic so downstream
# log-anomaly detectors do not mistake the replacement for an
# attack signature.
# ----------------------------------------------------------------------------
_REDACTED_SENTINEL: str = "***REDACTED***"


def _redact_validation_errors(
    errors: Sequence[Mapping[str, Any]],
) -> list[dict[str, Any]]:
    """Return a deep-copied error list with sensitive ``input`` fields masked.

    Address QA Checkpoint 6 CRITICAL Issue #2 (CWE-532 Insertion of
    Sensitive Information into Log File). Pydantic's
    :meth:`fastapi.exceptions.RequestValidationError.errors` returns a
    list of dicts whose ``input`` entry echoes the user-submitted value
    verbatim. For authentication endpoints that value is the plaintext
    password. This helper masks every ``input`` whose corresponding
    ``loc`` tuple ends in a sensitive field name (see
    :data:`_SENSITIVE_FIELD_NAMES`).

    Examples
    --------
    Before::

        [{"type": "string_too_long", "loc": ("body", "password"),
          "msg": "String should have at most 8 characters",
          "input": "MySecret123", "ctx": {"max_length": 8}}]

    After::

        [{"type": "string_too_long", "loc": ("body", "password"),
          "msg": "String should have at most 8 characters",
          "input": "***REDACTED***", "ctx": {"max_length": 8}}]

    Notes
    -----
    * The function returns a **new** list of **new** dicts. The input
      list is not mutated — this preserves Pydantic's internal state
      in case the caller also wants to serialize the raw errors
      elsewhere (e.g., in a test double).
    * ``ctx`` is passed through unchanged because Pydantic does not
      place user input into ``ctx`` — it contains only validator
      metadata (``max_length``, ``min_length``, ``pattern``, etc.).
    * The ``loc`` tuple may contain non-string elements (e.g.,
      integers for list indices) when the validation failure is inside
      a nested body field. We only inspect string elements for field-
      name matching; numeric indices are skipped.

    Parameters
    ----------
    errors : list[dict[str, Any]]
        The raw return value of
        :meth:`RequestValidationError.errors`.

    Returns
    -------
    list[dict[str, Any]]
        A redacted copy safe to log.
    """
    # We construct a new list entirely — never mutating the caller's
    # copy. Pydantic errors dicts are shallow enough that a dict copy
    # plus shallow copies of nested dicts suffices; no deeply nested
    # mutable state is present.
    redacted: list[dict[str, Any]] = []
    for entry in errors:
        # Shallow-copy the outer dict to avoid mutating the caller's
        # structure. This is O(k) where k is the small, bounded set of
        # keys Pydantic populates.
        new_entry: dict[str, Any] = dict(entry)

        # Determine whether the last string element of ``loc`` is a
        # sensitive field name. We use the last string rather than any
        # element so that nested paths like ``("body", "user",
        # "password")`` still match correctly.
        loc = entry.get("loc", ())
        is_sensitive = False
        for part in reversed(loc):
            if isinstance(part, str):
                if part.lower() in _SENSITIVE_FIELD_NAMES:
                    is_sensitive = True
                # Break after the first string element regardless of
                # match — if the last string-typed field name isn't
                # sensitive, parents aren't either (we don't
                # aggressively redact all of ``body.user`` just because
                # ``body.user.password`` is sensitive).
                break

        if is_sensitive and "input" in new_entry:
            new_entry["input"] = _REDACTED_SENTINEL

        # Also redact any ``ctx`` sub-value that contains the sensitive
        # input (Pydantic V2 occasionally includes snippets in ``ctx``
        # for specialized validators, e.g., uuid4_version). Defensive
        # check — no known current validator does this, but the
        # contract with future Pydantic upgrades is worth preserving.
        if is_sensitive and isinstance(new_entry.get("ctx"), dict):
            ctx_copy: dict[str, Any] = dict(new_entry["ctx"])
            # Keys known to sometimes echo user input.
            for echo_key in ("input", "value", "user_input"):
                if echo_key in ctx_copy:
                    ctx_copy[echo_key] = _REDACTED_SENTINEL
            new_entry["ctx"] = ctx_copy

        redacted.append(new_entry)

    return redacted


# ----------------------------------------------------------------------------
# Path-disclosure regex for log sanitization (QA Checkpoint 6 Issue #5).
#
# Python tracebacks produced by ``traceback.format_exc()`` include the
# absolute file system path of every frame — e.g.::
#
#     File "/tmp/blitzy/blitzy-card-demo/blitzy-XX..XX_b3fe62/src/api/routers/card_router.py"
#
# These paths disclose deployment details (container image structure,
# working-directory naming) that serve no debugging purpose and
# facilitate attacker reconnaissance when logs are leaked. Starlette
# and FastAPI do not sanitize tracebacks — the application is
# responsible for scrubbing the output.
#
# The strategy implemented in :func:`_sanitize_traceback` is:
#
# 1. Compute the project root once at import time (the directory that
#    contains ``src/``).
# 2. At log time, rewrite every absolute path that starts with the
#    project root to a relative path.
# 3. As a belt-and-braces second pass, rewrite any remaining absolute
#    path under ``/tmp/``, ``/home/``, ``/app/``, or the active Python
#    venv's ``site-packages`` to a relative path based on the path's
#    trailing ``src/`` directory (or an opaque ``<external>``
#    placeholder when the path lies outside our source tree).
# ----------------------------------------------------------------------------


def _compute_project_root() -> str:
    """Return the absolute path to the repository root.

    The repository root is the directory that contains ``src/``. We
    walk up from this file's location four levels (from
    ``.../src/api/middleware/error_handler.py`` to ``...``) and that
    directory is the project root by construction.

    This is computed once at import time; the result is cached in
    :data:`_PROJECT_ROOT` below.

    Returns
    -------
    str
        Absolute filesystem path ending in no trailing slash.
    """
    # __file__ resolves to the absolute path when the module is
    # imported via a fully-qualified name (``from src.api.middleware
    # import error_handler``). The four parent directories are:
    #   error_handler.py -> middleware/ -> api/ -> src/ -> project root.
    here = os.path.abspath(__file__)
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(here))))


# Absolute path to the repository root (e.g. ``/tmp/blitzy/.../b3fe62``).
# Computed once at import time to keep :func:`_sanitize_traceback` on
# the hot path branch-free.
_PROJECT_ROOT: str = _compute_project_root()


# Regex matching absolute paths inside a traceback's ``File "..."``
# prefix. We match the full absolute path AND its ``File "..."``
# context so we can substitute in the same pass. The `[^"]+` class is
# safe because traceback paths never contain literal double-quotes on
# any supported platform.
_TRACEBACK_FILE_LINE_RE: re.Pattern[str] = re.compile(r'File "([^"]+)"')


def _sanitize_traceback(tb: str) -> str:
    """Strip absolute file paths from a Python traceback string.

    Address QA Checkpoint 6 MINOR Issue #5 (CWE-209 Generation of Error
    Message Containing Sensitive Information). The default
    :func:`traceback.format_exc` output embeds every frame's absolute
    path. When a log file is leaked, these paths expose internal
    deployment layout (container working directory, venv location,
    blitzy scratch directory). The trade-off: losing the venv prefix
    marginally reduces debug utility, so we preserve the relative path
    within our source tree (``src/api/routers/card_router.py``) which
    retains all information needed to locate the frame.

    The sanitization rewrites every ``File "<abspath>"`` to
    ``File "<relpath>"`` where:

    * If the abspath starts with the project root, the rel path is the
      path relative to the project root (preserving ``src/``
      structure).
    * If the abspath contains a ``/site-packages/`` segment, the rel
      path becomes ``<site-packages>/<tail>``.
    * If the abspath points to a standard-library file (detectable by
      the Python installation prefix prefix), the rel path becomes
      ``<stdlib>/<basename>``.
    * Otherwise, the rel path becomes ``<external>/<basename>``.

    Examples
    --------
    Before::

        Traceback (most recent call last):
          File "/tmp/blitzy/blitzy-card-demo/blitzy-XX..XX_b3fe62/src/api/routers/card_router.py", line 247, in list_cards
            request = CardListRequest(...)
          File "/usr/lib/python3.11/site-packages/pydantic/main.py", line 161, in __init__
            ...

    After::

        Traceback (most recent call last):
          File "src/api/routers/card_router.py", line 247, in list_cards
            request = CardListRequest(...)
          File "<site-packages>/pydantic/main.py", line 161, in __init__
            ...

    Parameters
    ----------
    tb : str
        Raw traceback string — typically the return value of
        :func:`traceback.format_exc`.

    Returns
    -------
    str
        The traceback with every ``File "<abspath>"`` rewritten.
    """

    def _rewrite(match: re.Match[str]) -> str:
        """Regex substitution callback — rewrites one path."""
        abs_path = match.group(1)

        # Case 1: path is inside our project tree -> relative to
        # project root.
        if abs_path.startswith(_PROJECT_ROOT):
            rel = os.path.relpath(abs_path, _PROJECT_ROOT)
            return f'File "{rel}"'

        # Case 2: path is in a venv's site-packages -> opaque
        # <site-packages>/ prefix. We split on the LAST occurrence of
        # '/site-packages/' so that nested venvs still produce a
        # sensible tail.
        sp_marker = "/site-packages/"
        if sp_marker in abs_path:
            tail = abs_path.rsplit(sp_marker, 1)[-1]
            return f'File "<site-packages>/{tail}"'

        # Case 3: path looks like a stdlib frame. We detect "python3"
        # in the path as a heuristic — the absolute prefix varies
        # between distributions but the python3 segment is universal.
        if "/python3" in abs_path or "/python/" in abs_path:
            return f'File "<stdlib>/{os.path.basename(abs_path)}"'

        # Case 4: an external path outside all known locations. Emit
        # just the basename so we still get a filename for debugging,
        # but the full directory structure is not disclosed.
        return f'File "<external>/{os.path.basename(abs_path)}"'

    return _TRACEBACK_FILE_LINE_RE.sub(_rewrite, tb)


def _utc_now_iso() -> str:
    """Return the current UTC time as an ISO 8601 string.

    Wraps ``datetime.datetime.now(datetime.timezone.utc).isoformat()`` —
    used to stamp every error response with a cloud-native observability
    marker that pairs naturally with CloudWatch log ingestion timestamps.
    This is an enhancement over the original COBOL ``ABEND-DATA`` layout
    (which had no timestamp field — the z/OS SMF record provided that
    correlation context).

    Returns
    -------
    str
        ISO 8601 formatted UTC timestamp (e.g., ``"2024-03-15T12:34:56.789+00:00"``).
    """
    return datetime.datetime.now(datetime.UTC).isoformat()


def _truncate_to_pic_width(value: str, max_len: int) -> str:
    """Truncate a Python string to an original COBOL ``PIC X(n)`` width.

    COBOL ``PIC X(n)`` fields are fixed-width byte buffers; any string
    longer than ``n`` bytes is implicitly truncated on MOVE. Python
    strings have no such width, so we must enforce the contract
    ourselves when constructing :class:`AbendData` instances that will
    be serialized into a JSON response body where downstream log
    parsers may still expect the original widths.

    Parameters
    ----------
    value:
        Source Python string.
    max_len:
        Maximum allowed length (the ``n`` from the original ``PIC X(n)``).

    Returns
    -------
    str
        The input unchanged when ``len(value) <= max_len``, otherwise
        the first ``max_len`` characters of ``value``. Empty string when
        ``value`` is empty (mirroring COBOL ``VALUE SPACES`` initializer
        after ``str.rstrip()``).
    """
    if not value:
        return ""
    if len(value) <= max_len:
        return value
    return value[:max_len]


def _error_code_for_http_status(status_code: int) -> str:
    """Derive a 4-character ``ABEND-CODE`` mnemonic for an HTTP status.

    Returns a value whose length never exceeds
    :data:`src.shared.constants.messages.ABEND_CODE_MAX_LEN` (4),
    preserving the COBOL ``ABEND-CODE PIC X(4)`` width contract.

    Parameters
    ----------
    status_code:
        HTTP status code (100-599 nominally).

    Returns
    -------
    str
        A mnemonic 4-character code when the status matches a known
        CICS RESP equivalent (``NFND`` for 404, ``DUPR`` for 409, etc.);
        otherwise a generic ``H{status}`` fallback (e.g., ``H418``),
        always truncated to 4 characters.
    """
    if status_code in _HTTP_STATUS_TO_ERROR_CODE:
        return _HTTP_STATUS_TO_ERROR_CODE[status_code]
    # Generic fallback — always <= 4 chars for any 3-digit HTTP status.
    return _truncate_to_pic_width(f"H{status_code}", ABEND_CODE_MAX_LEN)


def _derive_culprit_from_path(path: str) -> str:
    """Derive an 8-character ``ABEND-CULPRIT`` value from a request path.

    In COBOL, ``ABEND-CULPRIT`` PIC X(8) held the PROGRAM-ID of the
    module that populated ``ABEND-DATA`` immediately before terminating
    (e.g., ``"COSGN00C"``, ``"COACTVWC"``). In the API target, the
    closest equivalent is the first path segment which typically names
    the router module (``/accounts`` -> ``accounts``, ``/cards/{id}``
    -> ``cards``, ``/`` or empty -> ``"API"``).

    The output is always <=
    :data:`src.shared.constants.messages.ABEND_CULPRIT_MAX_LEN` (8) and
    upper-cased to match the COBOL convention.

    Parameters
    ----------
    path:
        The request URL path (e.g., ``"/accounts/12345"``).

    Returns
    -------
    str
        Uppercase, space-padded-then-stripped 8-char (or shorter)
        identifier. Always returns at least ``"API"`` when the path
        has no distinguishable first segment.
    """
    if not path:
        return "API"
    # Strip the leading slash and take the first segment.
    segment = path.lstrip("/").split("/", 1)[0]
    if not segment:
        return "API"
    return _truncate_to_pic_width(segment.upper(), ABEND_CULPRIT_MAX_LEN)


def _build_error_response(
    status_code: int,
    error_code: str,
    culprit: str,
    reason: str,
    message: str,
    request_path: str = "",
) -> dict[str, Any]:
    """Build the JSON body of an error response mirroring ``ABEND-DATA``.

    Constructs an :class:`AbendData` instance (the canonical Python
    equivalent of COBOL ``01 ABEND-DATA`` from ``CSMSG02Y.cpy``) with
    every string field truncated to its original ``PIC X(n)`` width,
    then wraps it in an ``{"error": {...}}`` envelope enriched with two
    cloud-native observability fields (``timestamp``, ``path``) that
    have no direct COBOL equivalent but are essential for CloudWatch
    log correlation.

    Parameters
    ----------
    status_code:
        HTTP status code for the response (e.g., 404, 500). Echoed
        inside the body for clients that only inspect the payload.
    error_code:
        Short mnemonic code. Maps to COBOL ``ABEND-CODE PIC X(4)``;
        truncated to :data:`ABEND_CODE_MAX_LEN` characters.
    culprit:
        Identifier of the module/router that raised the error. Maps to
        COBOL ``ABEND-CULPRIT PIC X(8)``; truncated to
        :data:`ABEND_CULPRIT_MAX_LEN` characters.
    reason:
        Human-readable short reason phrase (e.g., ``"Record not found"``).
        Maps to COBOL ``ABEND-REASON PIC X(50)``; truncated to
        :data:`ABEND_REASON_MAX_LEN` characters.
    message:
        Full user-facing message string. Maps to COBOL ``ABEND-MSG
        PIC X(72)``; truncated to :data:`ABEND_MSG_MAX_LEN` characters.
    request_path:
        URL path the client originally requested, used for API-layer
        routing context. Not bounded by a COBOL PIC width; sent as-is.

    Returns
    -------
    dict[str, Any]
        A two-level dict ``{"error": {...}}`` ready for serialization
        by :class:`fastapi.responses.JSONResponse`. The inner mapping's
        keys are stable API contract — downstream clients and log
        parsers depend on them.
    """
    abend = AbendData(
        code=_truncate_to_pic_width(error_code, ABEND_CODE_MAX_LEN),
        culprit=_truncate_to_pic_width(culprit, ABEND_CULPRIT_MAX_LEN),
        reason=_truncate_to_pic_width(reason, ABEND_REASON_MAX_LEN),
        message=_truncate_to_pic_width(message, ABEND_MSG_MAX_LEN),
    )
    return {
        "error": {
            "status_code": status_code,
            # error_code <-> ABEND-CODE    PIC X(4)
            "error_code": abend.code,
            # culprit    <-> ABEND-CULPRIT PIC X(8)
            "culprit": abend.culprit,
            # reason     <-> ABEND-REASON  PIC X(50)
            "reason": abend.reason,
            # message    <-> ABEND-MSG     PIC X(72)
            "message": abend.message,
            # Cloud-native extensions (no COBOL equivalent):
            "timestamp": _utc_now_iso(),
            "path": request_path,
        }
    }


def build_abend_response(
    status_code: int,
    error_code: str,
    culprit: str,
    reason: str,
    message: str,
    request_path: str = "",
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    """Construct a :class:`JSONResponse` carrying the ABEND-DATA envelope.

    Thin public wrapper over :func:`_build_error_response` that returns
    a ready-to-emit :class:`fastapi.responses.JSONResponse` with the
    canonical ``{"error": {...}}`` envelope. Intended for use by
    *middleware* classes (e.g., :class:`src.api.middleware.auth.JWTAuthMiddleware`)
    that must return an error response themselves rather than raise
    an exception -- because middleware dispatches run *before* the
    FastAPI exception-handler layer, so raising an
    :class:`HTTPException` inside middleware would bypass the
    ABEND-DATA envelope that the registered handlers attach.

    Parameters
    ----------
    status_code:
        HTTP status code to return.
    error_code:
        4-character mnemonic, e.g., ``"AUTH"``, ``"FRBD"``, ``"NFND"``.
        Truncated to :data:`ABEND_CODE_MAX_LEN` characters.
    culprit:
        Identifier of the middleware or module that raised the error.
        Truncated to :data:`ABEND_CULPRIT_MAX_LEN` characters.
    reason:
        Short human-readable reason phrase. Truncated to
        :data:`ABEND_REASON_MAX_LEN` characters.
    message:
        Full user-facing message. Truncated to
        :data:`ABEND_MSG_MAX_LEN` characters.
    request_path:
        URL path of the offending request.
    headers:
        Optional response headers to attach (e.g.,
        ``{"WWW-Authenticate": "Bearer"}`` on a 401 response).

    Returns
    -------
    JSONResponse
        A fully-formed 4xx/5xx response ready to return from a
        middleware ``dispatch()`` implementation.

    Examples
    --------
    Inside a :class:`BaseHTTPMiddleware.dispatch` implementation::

        if token is None:
            return build_abend_response(
                status_code=401,
                error_code="AUTH",
                culprit="JWTAUTH",
                reason="Authentication required",
                message="Please enter User ID ...",
                request_path=request.url.path,
                headers={"WWW-Authenticate": "Bearer"},
            )
    """
    body = _build_error_response(
        status_code=status_code,
        error_code=error_code,
        culprit=culprit,
        reason=reason,
        message=message,
        request_path=request_path,
    )
    return JSONResponse(
        status_code=status_code,
        content=body,
        headers=headers,
    )


# ============================================================================
# Phase 4 — Exception Handler Registration
# ============================================================================


def register_exception_handlers(app: FastAPI) -> None:
    """Register all global exception handlers on a FastAPI application.

    Invoked exactly once from ``src/api/main.py`` during application
    bootstrap. Registers four handlers that collectively form the
    cloud-native equivalent of the COBOL ``EVALUATE WS-RESP-CD`` error
    branches plus the final ``CEE3ABD`` abend safety net:

    #. :class:`fastapi.HTTPException` — explicit errors raised by
       route handlers or middleware (maps directly to CICS RESP-code
       branches that ``SEND MAP`` an error screen).
    #. :class:`fastapi.exceptions.RequestValidationError` — Pydantic
       schema validation failures (maps to COBOL field-level SPACES /
       LOW-VALUES checks, e.g., ``COSGN00C.cbl`` lines 117-130).
    #. :class:`sqlalchemy.exc.SQLAlchemyError` — every ORM / driver
       exception (IntegrityError, OperationalError, etc.) which maps
       to CICS file I/O errors RESP=13 NOTFND, 14 DUPREC, 27 NOTOPEN,
       81 ILLOGIC, 84 IOERR.
    #. :class:`Exception` — the catch-all safety net, equivalent to
       the z/OS abend routine (``CEE3ABD``) that the COBOL programs
       fell into when they could not recover.

    All four handlers share the same response shape (see
    :func:`_build_error_response`) and structured logging contract
    (``extra`` dict carrying ``error_code``, ``path``, ``method``,
    ``exception_type``). None of them leak internal details (stack
    traces, database SQL, raw exception messages) into the JSON
    response body sent to clients.

    Parameters
    ----------
    app:
        The :class:`fastapi.FastAPI` application instance. Mutated in
        place via ``@app.exception_handler(...)`` decorators.

    Returns
    -------
    None
        The app is modified in-place; there is nothing meaningful to
        return.

    Notes
    -----
    The handlers are declared as nested async functions (closures over
    ``register_exception_handlers``) because FastAPI's
    ``@app.exception_handler`` decorator registers the function onto
    the app *as a side effect* at definition time — nesting keeps the
    registration scope-local and allows the caller to control when
    registration happens (e.g., deferred until after routers are
    mounted, if desired).
    """

    # ------------------------------------------------------------------
    # Handler 1 — starlette.HTTPException (superclass of fastapi.HTTPException)
    #
    # HTTPException is the primary way route handlers signal an error
    # to the framework. It is analogous to the COBOL pattern:
    #
    #     IF WS-RESP-CD NOT = DFHRESP(NORMAL)
    #         MOVE 'nnnn' TO ABEND-CODE
    #         MOVE 'Record not found' TO ABEND-REASON
    #         PERFORM SEND-ERROR-MAP
    #         EXEC CICS RETURN END-EXEC.
    #
    # The `exc.status_code` replaces the CICS RESP code, `exc.detail`
    # replaces the ABEND-MSG text, and the JSONResponse replaces the
    # BMS SEND MAP of the error screen.
    #
    # IMPORTANT: we register on ``starlette.exceptions.HTTPException``
    # rather than ``fastapi.HTTPException`` because:
    #
    #   * ``fastapi.HTTPException`` is a SUBCLASS of
    #     ``starlette.exceptions.HTTPException``, so registering on
    #     the Starlette superclass catches BOTH.
    #
    #   * The default 404 that Starlette raises for unmatched routes
    #     is a bare ``starlette.exceptions.HTTPException`` (not
    #     ``fastapi.HTTPException``). Registering only on the FastAPI
    #     subclass left unmatched-route 404s flowing through the
    #     default FastAPI handler, which produced the plain
    #     ``{"detail": "Not Found"}`` shape instead of the
    #     ABEND-DATA envelope that QA Checkpoint 2 (Issue 5) flagged
    #     as a format inconsistency.
    #
    # The type hint on ``exc`` is the Starlette superclass so that
    # mypy accepts both subclass and superclass instances; the
    # runtime behavior is identical because ``fastapi.HTTPException``
    # adds only a couple of validation-friendly attributes that are
    # not referenced by this handler.
    # ------------------------------------------------------------------
    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        status_code = exc.status_code
        path = request.url.path
        method = request.method

        # Derive the 4-char ABEND-CODE equivalent from the HTTP status.
        error_code = _error_code_for_http_status(status_code)

        # Reason string: HTTPException.detail is usually a short string
        # (e.g., "Item not found"). When it's a dict (rare — FastAPI
        # allows structured details), coerce to a stable string form
        # so the PIC X(50) ABEND-REASON field has a usable value.
        detail = exc.detail
        if isinstance(detail, str) and detail:
            reason = detail
            # When the route handler supplied an explicit detail string,
            # propagate that specific text into BOTH ``reason`` and
            # ``message`` so callers see the cause of the failure (e.g.,
            # "Card not active" or "Account not found") rather than the
            # generic CCDA_MSG_INVALID_KEY default. This resolves QA
            # Checkpoint 5 Issue 20 (card router error envelope
            # consistency) and applies uniformly to every router that
            # uses the ``raise HTTPException(..., detail="...")``
            # idiom — account, card, transaction, bill, report, user,
            # and auth — yielding a ``reason`` / ``message`` pair that
            # carries the specific cause string rather than a generic
            # default. The structured ``error_code`` (4-char ABEND-CODE
            # mnemonic) and ``culprit`` (path-derived component name)
            # still convey the categorical dimensions.
            message = detail
        elif isinstance(detail, str):
            # Explicit empty-string detail (unlikely but possible) —
            # fall back to the categorical default for this status so
            # the envelope is never blank.
            reason = _DEFAULT_HTTP_MESSAGES.get(status_code, CCDA_MSG_INVALID_KEY)
            message = reason
        else:
            # Non-string detail (dict/list) — fall back to the default
            # message for this status. The original structured detail
            # is still available server-side via ``exc.detail`` in logs
            # so operators can correlate the categorical response with
            # the richer server-side payload.
            reason = _DEFAULT_HTTP_MESSAGES.get(status_code, CCDA_MSG_INVALID_KEY)
            message = reason

        culprit = _derive_culprit_from_path(path)

        error_body = _build_error_response(
            status_code=status_code,
            error_code=error_code,
            culprit=culprit,
            reason=reason,
            message=message,
            request_path=path,
        )

        # Severity-aligned logging: 4xx is expected (client's fault, a
        # warning), 5xx is unexpected (server's fault, an error). 3xx /
        # 2xx HTTPExceptions are unusual but possible — log as info via
        # warning to keep them searchable.
        log_extra = {
            "error_code": error_code,
            "status_code": status_code,
            "path": path,
            "method": method,
            "exception_type": type(exc).__name__,
        }
        if 400 <= status_code < 500:
            logger.warning("HTTP client error: %s", reason, extra=log_extra)
        elif status_code >= 500:
            logger.error("HTTP server error: %s", reason, extra=log_extra)
        else:
            # 1xx/2xx/3xx raised as HTTPException — unusual but legal.
            logger.info("HTTP exception (non-error status): %s", reason, extra=log_extra)

        # Preserve any custom headers set on the HTTPException (e.g.,
        # WWW-Authenticate on 401, Retry-After on 503). FastAPI's own
        # default HTTPException handler honors this; we replicate the
        # behavior here so upstream proxies and clients continue to
        # receive the expected protocol-level headers.
        return JSONResponse(
            status_code=status_code,
            content=error_body,
            headers=getattr(exc, "headers", None),
        )

    # ------------------------------------------------------------------
    # Handler 2 — fastapi.exceptions.RequestValidationError
    #
    # Pydantic v2 raises RequestValidationError when a request body /
    # query parameter / path parameter fails schema validation. This is
    # the cloud-native equivalent of the COBOL per-field presence
    # checks, e.g., `COSGN00C.cbl` lines 117-130:
    #
    #     IF USERIDI OF COSGN0AI = SPACES
    #        OR USERIDI OF COSGN0AI = LOW-VALUES
    #         MOVE 'Please enter User ID ...' TO WS-MESSAGE
    #         MOVE -1 TO USERIDL OF COSGN0AO
    #         PERFORM SEND-SIGNON-SCREEN
    #     END-IF.
    #
    # FastAPI returns 422 Unprocessable Entity by default; we preserve
    # that behavior and enrich the response with the ABEND-DATA shape.
    # The list of validation errors (each with `loc`, `msg`, `type`) is
    # summarized into the PIC X(72) ABEND-MSG field.
    # ------------------------------------------------------------------
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        path = request.url.path
        method = request.method

        # Summarize the validation errors into a PIC X(72)-sized string.
        # We take the first field path + message as the primary human
        # summary (matching COBOL behavior where only the first
        # offending field is highlighted before the user re-submits).
        errors = exc.errors()
        if errors:
            first = errors[0]
            loc = ".".join(str(part) for part in first.get("loc", ()) if part != "body")
            msg = str(first.get("msg", "validation failed"))
            summary = f"{loc}: {msg}" if loc else msg
        else:
            summary = "Request validation failed"

        error_body = _build_error_response(
            status_code=422,
            # "VALD" <- validation-failure mnemonic ABEND-CODE.
            error_code="VALD",
            culprit=_derive_culprit_from_path(path),
            # COBOL user-facing equivalent from CSMSG01Y.cpy:
            # 'Invalid key pressed. Please see below...'.
            reason=CCDA_MSG_INVALID_KEY,
            message=summary,
            request_path=path,
        )

        # The full errors list is a server-side-only detail — include
        # it in the structured log for debugging but NOT in the
        # response body (which we keep tight to the ABEND-DATA shape).
        #
        # CRITICAL SECURITY: Pydantic's ``exc.errors()`` list embeds
        # the ORIGINAL user-submitted value in each entry's ``input``
        # field. For a failed ``POST /auth/login`` validation, that
        # ``input`` is the plaintext password. Logging the raw list
        # verbatim was the root cause of QA Checkpoint 6 CRITICAL
        # Issue #2 (CWE-532 Insertion of Sensitive Information into
        # Log File). We call :func:`_redact_validation_errors` to mask
        # the ``input`` field for any entry whose ``loc`` tuple ends
        # in a sensitive field name (password, secret, token, ssn,
        # card_num, cvv, etc. — see :data:`_SENSITIVE_FIELD_NAMES`).
        # Non-sensitive fields (e.g., a malformed transaction date)
        # are passed through unchanged so debugging remains effective.
        safe_errors = _redact_validation_errors(errors)
        logger.warning(
            "Request validation failed",
            extra={
                "error_code": "VALD",
                "status_code": 422,
                "path": path,
                "method": method,
                "exception_type": type(exc).__name__,
                "validation_errors": safe_errors,
            },
        )

        return JSONResponse(status_code=422, content=error_body)

    # ------------------------------------------------------------------
    # Handler 3 — sqlalchemy.exc.SQLAlchemyError
    #
    # Every database-layer exception (IntegrityError, OperationalError,
    # DataError, ProgrammingError, ...) inherits from SQLAlchemyError,
    # so a single handler catches the full class. This replaces the
    # CICS file-I/O error branches:
    #
    #   RESP=13 NOTFND   (record not found on READ)
    #   RESP=14 DUPREC   (duplicate key on WRITE)
    #   RESP=27 NOTOPEN  (file-control entry closed)
    #   RESP=81 ILLOGIC  (VSAM internal inconsistency)
    #   RESP=84 IOERR    (disk/channel hardware error)
    #
    # CRITICAL: SQLAlchemy exception messages often include the full
    # SQL statement and the offending bind-parameter values — these
    # MUST be logged server-side only and must never appear in the
    # response body. The client receives a generic "Database error"
    # message with ABEND-CODE 'DBIO'.
    # ------------------------------------------------------------------
    @app.exception_handler(SQLAlchemyError)
    async def database_exception_handler(request: Request, exc: SQLAlchemyError) -> JSONResponse:
        path = request.url.path
        method = request.method

        # Sanitized, COBOL-style response body. We deliberately do not
        # peek at `str(exc)` — which may include SQL text and bind
        # values — for the `message` field. The server-side log below
        # retains the full exception context including the traceback.
        error_body = _build_error_response(
            status_code=500,
            # "DBIO" <- database I/O ABEND-CODE, aligned with
            #           CICS IOERR (RESP=84) which is the nearest
            #           semantic match.
            error_code="DBIO",
            culprit=_derive_culprit_from_path(path),
            reason="Database error",
            message="A database error occurred. Please try again later.",
            request_path=path,
        )

        # logger.error with full traceback — the full SQL and bind
        # values in `str(exc)` are a server-side-only forensic trail
        # (equivalent to z/OS SMF / SYSLOG retention of the ABEND
        # dump). CloudWatch Logs Insights can query on `exception_type`
        # to distinguish IntegrityError vs OperationalError vs
        # ProgrammingError for alerting and dashboards.
        #
        # The traceback is sanitized by :func:`_sanitize_traceback`
        # before logging so that absolute filesystem paths (e.g.,
        # ``/tmp/blitzy/blitzy-card-demo/blitzy-XX..XX_b3fe62/src/``)
        # are replaced with relative paths (``src/``). Addresses QA
        # Checkpoint 6 MINOR Issue #5 (CWE-209 Generation of Error
        # Message Containing Sensitive Information). The relative
        # paths preserve all debugging information — the specific
        # module and line number — while denying attackers visibility
        # into deployment layout.
        logger.error(
            "Database error: %s",
            type(exc).__name__,
            extra={
                "error_code": "DBIO",
                "status_code": 500,
                "path": path,
                "method": method,
                "exception_type": type(exc).__name__,
                "traceback": _sanitize_traceback(traceback.format_exc()),
            },
        )

        return JSONResponse(status_code=500, content=error_body)

    # ------------------------------------------------------------------
    # Handler 4 — catch-all Exception
    #
    # The safety net that catches everything the three specific
    # handlers above did not match. This is the cloud-native
    # equivalent of the z/OS language-environment abend routine
    # (``CEE3ABD``) that a COBOL program fell into when it raised a
    # condition it had no ON EXCEPTION handler for:
    #
    #     MOVE 'ABND' TO ABEND-CODE
    #     MOVE 'COxxxxxC' TO ABEND-CULPRIT
    #     CALL 'CEE3ABD' USING ABEND-CODE ABEND-FLAG.
    #
    # CRITICAL: Stack traces MUST stay server-side only. The JSON
    # response body contains only the sanitized ABEND-DATA shape with
    # a generic message; the full traceback is written to the module
    # logger at CRITICAL level for operator alerting.
    # ------------------------------------------------------------------
    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        path = request.url.path
        method = request.method

        error_body = _build_error_response(
            status_code=500,
            # "ABND" <- COBOL ABEND-equivalent unrecoverable-error code.
            error_code="ABND",
            # The exception class name is the closest API equivalent
            # of COBOL PROGRAM-ID for the culprit field. Truncated to
            # 8 chars by _build_error_response.
            culprit=type(exc).__name__,
            reason="Internal server error",
            message="An unexpected error occurred. Please try again later.",
            request_path=path,
        )

        # logger.critical — this is the severity level CloudWatch
        # alarms should monitor. An unhandled exception is by
        # definition an operator-action event.
        #
        # The traceback is sanitized by :func:`_sanitize_traceback`
        # before logging so that absolute filesystem paths are
        # replaced with relative paths. Addresses QA Checkpoint 6
        # MINOR Issue #5 (CWE-209). The relative paths preserve all
        # debugging information while denying attackers visibility
        # into deployment layout.
        logger.critical(
            "Unhandled exception: %s",
            type(exc).__name__,
            extra={
                "error_code": "ABND",
                "status_code": 500,
                "path": path,
                "method": method,
                "exception_type": type(exc).__name__,
                "traceback": _sanitize_traceback(traceback.format_exc()),
            },
        )

        return JSONResponse(status_code=500, content=error_body)


# ----------------------------------------------------------------------------
# Public re-export list.
#
# Only the two schema-declared exports are part of the public API of
# this module. Private helpers (_build_error_response,
# _truncate_to_pic_width, _error_code_for_http_status,
# _derive_culprit_from_path, _utc_now_iso) and the internal lookup
# tables (_HTTP_STATUS_TO_ERROR_CODE, _DEFAULT_HTTP_MESSAGES) are
# intentionally omitted.
# ----------------------------------------------------------------------------
__all__ = [
    "CICS_RESP_TO_HTTP",
    "build_abend_response",
    "register_exception_handlers",
]
