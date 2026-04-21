# ============================================================================
# Source: app/cbl/COSGN00C.cbl (Sign-on/authentication program, Feature F-001)
#         + app/cpy/CSMSG01Y.cpy (CCDA-COMMON-MESSAGES — common user messages)
#         + app/cpy/CSMSG02Y.cpy (ABEND-DATA — abend / error work areas)
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
"""Middleware package for CardDemo API.

Provides JWT authentication (converted from ``app/cbl/COSGN00C.cbl``
CICS pseudo-conversational sign-on) and global error handling
(converted from ``app/cpy/CSMSG01Y.cpy`` / ``app/cpy/CSMSG02Y.cpy``
error patterns). Source: ``app/cbl/COSGN00C.cbl``,
``app/cpy/CSMSG01Y.cpy``, ``app/cpy/CSMSG02Y.cpy`` —
Mainframe-to-Cloud migration.

Mainframe → Cloud Cross-Cutting Concerns Mapping
------------------------------------------------
The middleware layer replaces CICS COBOL cross-cutting patterns:
session management (via the ``COCOM01Y.cpy`` COMMAREA block threaded
through every ``EXEC CICS RETURN TRANSID(...) COMMAREA(...)``) and
error handling (via the ``CSMSG02Y.cpy`` ``ABEND-DATA`` work area
populated by ``EVALUATE WS-RESP-CD`` blocks in every CICS program).

=======================================  ===========================================
CICS / COBOL pattern                     FastAPI middleware equivalent
=======================================  ===========================================
``EXEC CICS RETURN TRANSID(...)          :class:`JWTAuthMiddleware`
COMMAREA(CARDDEMO-COMMAREA)``            (Bearer-token validation on every request,
(pseudo-conversational session)          stateless)
``EIBCALEN = 0`` first-entry check       ``PUBLIC_PATHS`` allow-list in
(COSGN00C.cbl PROCESS-ENTER-KEY)         ``auth.py`` (``/auth/login``, ``/docs``,
                                         ``/health``, …)
``88 CDEMO-USRTYP-ADMIN VALUE 'A'``      ``ADMIN_ONLY_PREFIXES`` guard in
(COCOM01Y.cpy)                           ``auth.py`` (enforces ``user_type == 'A'``
                                         on ``/admin/*`` paths)
``EVALUATE WS-RESP-CD`` + ``MOVE`` to    :func:`register_exception_handlers`
``ABEND-DATA`` (CSMSG02Y.cpy)            (maps CICS RESP codes to HTTP status
                                         codes, returns ``ABEND-DATA``-shaped JSON)
``EXEC CICS SEND MAP`` with              ``JSONResponse`` returned by the exception
``ABEND-MSG``                            handlers registered on the FastAPI app
=======================================  ===========================================

Public API
----------
Only two public symbols are re-exported from this package for use by
the FastAPI application entry point (``src/api/main.py``). All other
names (``PUBLIC_PATHS``, ``ADMIN_ONLY_PREFIXES``, ``decode_jwt_token``,
``CICS_RESP_TO_HTTP``, private helpers) live on the respective
submodules and must be imported from there directly when needed:

* :class:`JWTAuthMiddleware` — Starlette ``BaseHTTPMiddleware``
  subclass attached to the FastAPI app via ``app.add_middleware(...)``.
  Validates ``Authorization: Bearer <jwt>`` headers on every
  non-public request, decodes the JWT, enforces admin-only route
  restrictions, and attaches authenticated user context (``user_id``,
  ``user_type``, ``is_admin``) to ``request.state`` for downstream
  FastAPI route handlers. Replaces the CICS pseudo-conversational
  COMMAREA session-state mechanism from ``COSGN00C.cbl``.

* :func:`register_exception_handlers` — Registration function called
  from ``src/api/main.py`` during app startup. Attaches
  ``@app.exception_handler(...)`` callbacks for
  :class:`fastapi.HTTPException`,
  :class:`fastapi.exceptions.RequestValidationError`,
  :class:`sqlalchemy.exc.SQLAlchemyError`, and the catch-all
  :class:`Exception` class. Each handler converts the caught
  exception into a structured JSON response shaped like the COBOL
  ``ABEND-DATA`` record (``error_code`` ↔ ``ABEND-CODE`` ``PIC X(4)``,
  ``culprit`` ↔ ``ABEND-CULPRIT`` ``PIC X(8)``, ``reason`` ↔
  ``ABEND-REASON`` ``PIC X(50)``, ``message`` ↔ ``ABEND-MSG``
  ``PIC X(72)``). Replaces the CICS ``EVALUATE WS-RESP-CD`` / ``SEND
  MAP`` error presentation pattern from every online COBOL program.

Design Notes
------------
* **Minimal re-exports**: This ``__init__.py`` re-exports ONLY the
  two symbols that ``src/api/main.py`` needs to wire the middleware
  chain. Submodule internals (private helpers, lookup tables,
  constants, handler coroutines) stay encapsulated on ``auth.py`` /
  ``error_handler.py`` so that consumers import the narrowest
  dependency surface possible.

* **Explicit imports, no wildcards**: Each re-export is an explicit
  ``from src.api.middleware.<submodule> import <name>`` statement.
  Wildcard imports are prohibited per the project linting rules
  (ruff ``F403`` / ``F405``).

* **No circular imports**: This module imports ONLY from its own
  submodules. Neither ``auth.py`` nor ``error_handler.py`` imports
  anything back from ``src.api.middleware`` (they import from
  ``src.shared.config.settings`` and ``src.shared.constants.messages``
  respectively), so no circular-import risk exists.

* **No eager heavy-dependency loading beyond the middleware classes
  themselves**: Importing :class:`JWTAuthMiddleware` does pull in
  ``python-jose`` and Starlette; importing
  :func:`register_exception_handlers` does pull in FastAPI and
  SQLAlchemy's exception module. This is deliberate — these are the
  exact dependencies FastAPI's middleware chain requires anyway, so
  no additional cost is incurred. The AAP places middleware at Level
  4 (module-level validation), and both dependencies are already
  declared in ``requirements-api.txt``.

* **Python 3.11+**: Aligned with the Python baseline of the FastAPI /
  ECS Fargate deployment image (``python:3.11-slim``) and the AWS
  Glue 5.1 runtime for the batch layer. See AAP §0.6.1.

* **Apache License 2.0**: Inherited from the original AWS CardDemo
  mainframe reference application and the COBOL source artifacts this
  file replaces (``COSGN00C.cbl``, ``CSMSG01Y.cpy``, ``CSMSG02Y.cpy``).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (middleware package).
AAP §0.5.1 — File-by-File Transformation Plan
    (``COSGN00C.cbl`` → ``auth.py``;
    ``CSMSG01Y.cpy`` + ``CSMSG02Y.cpy`` → ``error_handler.py``).
AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic,
    minimal change clause, document technology-specific changes).

Usage
-----
Wiring into the FastAPI application entry point (``src/api/main.py``)::

    from fastapi import FastAPI

    from src.api.middleware import (
        JWTAuthMiddleware,
        register_exception_handlers,
    )

    app = FastAPI(title="CardDemo API")

    # Install the JWT validator on every non-public route.
    app.add_middleware(JWTAuthMiddleware)

    # Register CICS-RESP-style JSON error handlers.
    register_exception_handlers(app)
"""

# ----------------------------------------------------------------------------
# Middleware layer replaces CICS COBOL cross-cutting patterns: session
# management (COMMAREA threading via pseudo-conversational CICS RETURN)
# and error handling (ABEND-DATA-shaped JSON error bodies replacing BMS
# SEND MAP error screens).
# ----------------------------------------------------------------------------

# JWT authentication middleware (converted from app/cbl/COSGN00C.cbl).
# Validates Bearer tokens, extracts user_id / user_type JWT claims
# mapping COMMAREA fields CDEMO-USER-ID (PIC X(08)) and CDEMO-USER-TYPE
# (PIC X(01), values 'A'=admin / 'U'=user), and enforces admin-only
# path restrictions on the /admin/* prefix.
from src.api.middleware.auth import JWTAuthMiddleware

# Global exception handler registration (converted from
# app/cpy/CSMSG01Y.cpy + app/cpy/CSMSG02Y.cpy). Maps CICS RESP codes
# to HTTP status codes and formats errors using an ABEND-DATA-shaped
# JSON body. Registers handlers for HTTPException,
# RequestValidationError, SQLAlchemyError, and catch-all Exception on
# the FastAPI app instance.
from src.api.middleware.error_handler import register_exception_handlers

# ----------------------------------------------------------------------------
# Public re-export list — only these two symbols are considered part of
# the public API of the middleware package. Consumers that need submodule
# internals (PUBLIC_PATHS, ADMIN_ONLY_PREFIXES, decode_jwt_token,
# CICS_RESP_TO_HTTP, private helpers) must import them from their
# specific submodules directly:
#
#     from src.api.middleware.auth import PUBLIC_PATHS, decode_jwt_token
#     from src.api.middleware.error_handler import CICS_RESP_TO_HTTP
#
# ``from src.api.middleware import *`` resolves to exactly the two
# symbols declared below.
# ----------------------------------------------------------------------------
__all__: list[str] = [
    "JWTAuthMiddleware",
    "register_exception_handlers",
]
