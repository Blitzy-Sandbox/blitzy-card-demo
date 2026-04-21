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
import traceback
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError

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
    # Handler 1 — fastapi.HTTPException
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
    # ------------------------------------------------------------------
    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
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
        if isinstance(detail, str):
            reason = detail or _DEFAULT_HTTP_MESSAGES.get(status_code, CCDA_MSG_INVALID_KEY)
        else:
            # Non-string detail (dict/list) — fall back to the default
            # message for this status. The original structured detail
            # is still available server-side via `exc.detail` in logs.
            reason = _DEFAULT_HTTP_MESSAGES.get(status_code, CCDA_MSG_INVALID_KEY)

        # The full message field uses the default for this status when
        # available, otherwise the reason. This keeps a
        # COBOL-accurate user-facing tone (CCDA_MSG_*).
        message = _DEFAULT_HTTP_MESSAGES.get(status_code, reason)

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
        logger.warning(
            "Request validation failed",
            extra={
                "error_code": "VALD",
                "status_code": 422,
                "path": path,
                "method": method,
                "exception_type": type(exc).__name__,
                "validation_errors": errors,
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
        logger.error(
            "Database error: %s",
            type(exc).__name__,
            extra={
                "error_code": "DBIO",
                "status_code": 500,
                "path": path,
                "method": method,
                "exception_type": type(exc).__name__,
                "traceback": traceback.format_exc(),
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
        logger.critical(
            "Unhandled exception: %s",
            type(exc).__name__,
            extra={
                "error_code": "ABND",
                "status_code": 500,
                "path": path,
                "method": method,
                "exception_type": type(exc).__name__,
                "traceback": traceback.format_exc(),
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
    "register_exception_handlers",
]
