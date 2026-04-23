# ============================================================================
# Source: app/cbl/COUSR00C.cbl  (User List   — CICS transaction CU00, 695 lines)
#       + app/cbl/COUSR01C.cbl  (User Add    — CICS transaction CU01, 299 lines)
#       + app/cbl/COUSR02C.cbl  (User Update — CICS transaction CU02, 414 lines)
#       + app/cbl/COUSR03C.cbl  (User Delete — CICS transaction CU03, 359 lines)
#       + app/cpy/CSUSR01Y.cpy  (SEC-USER-DATA 80-byte VSAM record layout)
#       + app/cpy-bms/COUSR00.CPY / COUSR01.CPY / COUSR02.CPY / COUSR03.CPY
#         (BMS symbolic-map layouts defining the request / response contracts)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS ``EXEC CICS STARTBR / READNEXT / READPREV / ENDBR FILE('USRSEC')``
#   (browse-mode cursor pagination in COUSR00C.cbl) +
#   ``EXEC CICS WRITE  FILE('USRSEC')`` (COUSR01C.cbl user add) +
#   ``EXEC CICS READ   FILE('USRSEC') UPDATE`` + ``REWRITE`` (COUSR02C.cbl) +
#   ``EXEC CICS READ   FILE('USRSEC')`` + ``DELETE`` (COUSR03C.cbl) +
#   CICS XCTL PROGRAM('COUSR0xC') transfers of control between list / add /
#   update / delete screens
#
# becomes
#
#   SQLAlchemy 2.x async ``SELECT ... LIMIT ... OFFSET`` paginated queries
#   for the list endpoint, ``session.add()`` + ``flush()`` INSERTs for the
#   add endpoint, attribute-level mutation + ``flush()`` for the update
#   endpoint (via SQLAlchemy's unit-of-work tracking — the ORM-managed
#   equivalent of CICS READ UPDATE / REWRITE), and
#   ``session.delete()`` + ``flush()`` for the delete endpoint. Passwords
#   are BCrypt-hashed (``passlib.hash.bcrypt``) before any INSERT / UPDATE
#   on the ``user_security.password`` column; plaintext never touches
#   disk, never appears in logs, and never appears in responses.
#
# The target deployment is AWS ECS Fargate behind an Application Load
# Balancer; the database credentials come from AWS Secrets Manager in
# staging/production (injected via ECS task-definition secrets) and from
# the ``.env`` file in local development (docker-compose).
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
"""User CRUD service.

Converted from ``app/cbl/COUSR00C.cbl`` (695 lines — browse-mode user
list), ``app/cbl/COUSR01C.cbl`` (299 lines — user add with WRITE to
USRSEC),  ``app/cbl/COUSR02C.cbl`` (414 lines — user update with READ
UPDATE / REWRITE), and ``app/cbl/COUSR03C.cbl`` (359 lines — user
delete with READ + DELETE). Full CRUD with BCrypt password hashing
for every new or updated password.

The service exposes :class:`UserService`, used by the user-admin
router (``src/api/routers/user_router.py``) and indirectly by the
admin GraphQL resolvers (``src/api/graphql/mutations.py``). Like
:class:`src.api.services.auth_service.AuthService`, the class is
intentionally stateful in the database session only: no caches, no
in-memory session data, no mutable class attributes. The async
session is scoped to a single HTTP request and managed by the FastAPI
dependency system; transaction boundaries are owned by the caller.

COBOL → Python flow mapping (four PROCEDURE DIVISIONs merged):

==========================================  ==========================================
COBOL paragraph / statement                 Python equivalent (this module)
==========================================  ==========================================
COUSR00C ``PROCESS-PF7-KEY``                 :meth:`UserService.list_users`
COUSR00C ``STARTBR-USER-SEC-FILE`` L583-615  ``select(UserSecurity).order_by(user_id)``
COUSR00C ``READNEXT-USER-SEC-FILE`` L618-650 ``.offset(...).limit(...)``
COUSR00C ``ENDBR`` (implicit)                ``await self.db.execute(stmt)``
COUSR00C ``WHEN OTHER`` (lookup fail)        ``logger.exception`` +
                                             ``UserServiceError(MSG_UNABLE_TO_LOOKUP)``
COUSR01C ``PROCESS-ENTER-KEY``               :meth:`UserService.create_user`
COUSR01C ``ADD-USER-SEC-FILE`` L236-275      ``self.db.add(user)`` +
                                             ``await self.db.flush()``
COUSR01C ``WHEN DUPKEY / DUPREC`` L261-268   duplicate pre-check raises
                                             :class:`UserIdAlreadyExistsError`
COUSR01C ``WHEN OTHER`` (ADD fail)           raises :class:`UserServiceError`
                                             ``(MSG_UNABLE_TO_ADD_USER)``
COUSR02C ``PROCESS-ENTER-KEY``               :meth:`UserService.update_user`
COUSR02C ``READ-USER-SEC-FILE`` L300-355     ``select(UserSecurity).where(...)``
COUSR02C ``UPDATE-USER-SEC-FILE`` L358-392   attribute mutation +
                                             ``await self.db.flush()``
COUSR02C ``WHEN NOTFND``                     raises :class:`UserNotFoundError`
COUSR02C ``WHEN OTHER`` (REWRITE fail)       raises :class:`UserServiceError`
                                             ``(MSG_UNABLE_TO_UPDATE_USER)``
COUSR03C ``PROCESS-ENTER-KEY``               :meth:`UserService.delete_user`
COUSR03C ``READ-USER-SEC-FILE`` L243-301     ``select(UserSecurity).where(...)``
COUSR03C ``DELETE-USER-SEC-FILE`` L304-338   ``await self.db.delete(user)`` +
                                             ``await self.db.flush()``
COUSR03C ``WHEN NOTFND``                     raises :class:`UserNotFoundError`
COUSR03C ``WHEN OTHER`` (DELETE fail)        raises :class:`UserServiceError`
                                             ``(MSG_UNABLE_TO_UPDATE_USER)`` —
                                             COBOL preserved its "Update"
                                             wording even in the Delete flow;
                                             we reproduce that byte-for-byte
                                             per AAP §0.7.1 "Preserve exact
                                             error messages from COBOL"
``SEC-USR-PWD = WS-USER-PWD`` comparison     replaced by BCrypt hashing at
                                             WRITE / REWRITE time; no
                                             cleartext password ever reaches
                                             disk or logs.
==========================================  ==========================================

Error message fidelity
----------------------
The COBOL error messages from the four source programs are reproduced
**byte-for-byte**, including the trailing ellipses and the literal
space before ``...``:

* ``'Unable to lookup User...'``       (COUSR00C line 610; COUSR02C line 349;
                                        COUSR03C line 296)
* ``'User ID already exist...'``       (COUSR01C line 263 — DUPKEY / DUPREC)
* ``'Unable to Add User...'``          (COUSR01C line 270 — WHEN OTHER)
* ``'User ID NOT found...'``           (COUSR02C line 342; COUSR03C line 289)
* ``'Please modify to update ...'``    (COUSR02C line 239 — no changes detected)
* ``'Unable to Update User...'``       (COUSR02C line 386 and COUSR03C line 332;
                                        the Delete flow preserves "Update"
                                        verbatim as it appeared in the original
                                        COBOL — a benign code-sharing artifact
                                        in the generated BMS handler)
* ``'User <id> has been added ...'``   (COUSR01C line 257 — success string
                                        template)
* ``'User <id> has been updated ...'`` (COUSR02C line 374)
* ``'User <id> has been deleted ...'`` (COUSR03C line 320)

These constants are the sole user-facing strings owned by this module;
they are surfaced via the ``message`` field of the Pydantic response
schemas (``UserListResponse.message``, ``UserCreateResponse.message``,
etc. — each mapped to the COBOL ``ERRMSGI PIC X(78)`` field).

Observability
-------------
All user-administration events emit structured log records via the
module logger. Log records include the ``user_id`` field (never the
password, never the BCrypt hash) so that CloudWatch Logs Insights
queries can correlate admin activity by operator / target user. Log
levels follow the same pattern as ``auth_service``:

* ``INFO``  — successful user creation / update / deletion; paginated
  list invocation (with the hit count for capacity planning).
* ``WARNING`` — business-rule failures: duplicate user_id on create,
  user_id not found on update / delete, empty-patch update (no
  fields supplied).
* ``ERROR`` — unexpected SQLAlchemy / BCrypt exceptions (emitted via
  ``logger.exception`` to preserve the full traceback alongside the
  structured ``user_id`` context).

Security notes
--------------
* **Password handling** — the plaintext password provided by the
  client on :class:`UserCreateRequest` / :class:`UserUpdateRequest`
  is held in memory for exactly the duration of the respective
  service method and is never logged, serialized, or persisted.
  Only BCrypt hashes reach the database (the 60-char output of
  :func:`passlib.context.CryptContext.hash`).
* **Response redaction** — :class:`UserListResponse` omits the
  password hash (it surfaces only ``user_id`` / ``first_name`` /
  ``last_name`` / ``user_type``). :class:`UserDeleteResponse`
  omits the password field entirely (mirroring the ``COUSR03.CPY``
  symbolic map which intentionally omits ``PASSWDI`` / ``PASSWDO``
  because the Delete screen is display-only).
* **User type** — all values persisted to ``user_security.usr_type``
  are validated against the COBOL ``COCOM01Y.cpy`` 88-level
  constraint (``'A'`` = admin, ``'U'`` = user). The Pydantic
  schemas enforce this, but the service layer re-validates as
  defence-in-depth before calling ``session.add()`` or ``flush()``.

See Also
--------
* AAP §0.2.3 — Online CICS Program Classification (F-018 through F-021)
* AAP §0.5.1 — File-by-File Transformation Plan (``user_service.py``)
* AAP §0.7.1 — Refactoring-Specific Rules (preserve exact COBOL messages)
* AAP §0.7.2 — Security Requirements (BCrypt hashing mandated)
* ``src/shared/models/user_security.py`` — ORM model queried here
* ``src/shared/schemas/user_schema.py`` — Pydantic request / response schemas
* ``src/api/services/auth_service.py`` — companion service for sign-on /
  token issuance; shares the :data:`pwd_context` BCrypt configuration
"""

from __future__ import annotations

import logging

from passlib.context import CryptContext
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from src.shared.models.user_security import UserSecurity
from src.shared.schemas.user_schema import (
    UserCreateRequest,
    UserCreateResponse,
    UserDeleteResponse,
    UserListItem,
    UserListRequest,
    UserListResponse,
    UserUpdateRequest,
    UserUpdateResponse,
)

# ============================================================================
# Module-level configuration
# ============================================================================

#: Module logger. Structured records flow to CloudWatch Logs (via the
#: ECS awslogs driver) where Logs Insights queries can filter by
#: ``logger_name`` = ``src.api.services.user_service`` to isolate user-
#: administration activity from authentication (``auth_service``) and
#: from other service-layer modules.
logger = logging.getLogger(__name__)

#: BCrypt password-hashing context. Re-uses the identical configuration
#: as :data:`src.api.services.auth_service.pwd_context` so that hashes
#: written here (via :meth:`UserService.create_user` and
#: :meth:`UserService.update_user`) are transparently verifiable by
#: :meth:`AuthService.authenticate` on subsequent sign-ons. The shared
#: ``deprecated="auto"`` setting ensures future scheme migrations
#: (e.g. BCrypt → Argon2) rehash automatically on next successful
#: verify.
pwd_context: CryptContext = CryptContext(schemes=["bcrypt"], deprecated="auto")

# ----------------------------------------------------------------------------
# COBOL-exact error messages from ``COUSR00C.cbl`` / ``COUSR01C.cbl`` /
# ``COUSR02C.cbl`` / ``COUSR03C.cbl``.
#
# These strings are reproduced byte-for-byte from the COBOL source —
# including the trailing ellipses and the deliberate space before
# ``...`` in the "Please modify to update ..." string — per AAP §0.7.1
# "Preserve exact error messages from COBOL". They are surfaced to API
# clients via the ``message`` field of the Pydantic response envelope
# (ultimately a Pydantic ``str`` payload on the HTTP response body, or
# a GraphQL error extension when the error-handler middleware maps a
# :class:`UserServiceError` to an HTTP 4xx / 5xx).
# ----------------------------------------------------------------------------

#: Raised by :meth:`UserService.list_users` when the SELECT against
#: the ``user_security`` table fails for an unexpected reason (DB
#: connectivity, driver error, etc.). Mirrors COBOL
#: ``EVALUATE WS-RESP-CD WHEN OTHER`` branch at ``COUSR00C.cbl`` line 610
#: (the STARTBR-USER-SEC-FILE paragraph); the identical string is also
#: emitted by the READNEXT and READPREV paragraphs (lines 644 and 678)
#: and by the READ flow in ``COUSR02C.cbl`` line 349 and
#: ``COUSR03C.cbl`` line 296.
MSG_UNABLE_TO_LOOKUP: str = "Unable to lookup User..."

#: Raised by :meth:`UserService.create_user` when the target
#: ``user_id`` already exists (the ``user_security.user_id`` primary-
#: key unique constraint would be violated). Mirrors COBOL
#: ``EVALUATE WS-RESP-CD WHEN DFHRESP(DUPKEY) / DFHRESP(DUPREC)`` at
#: ``COUSR01C.cbl`` line 263.
MSG_USER_ID_ALREADY_EXISTS: str = "User ID already exist..."

#: Raised by :meth:`UserService.create_user` when the INSERT against
#: ``user_security`` fails for any reason *other than* duplicate key
#: (DB connectivity, constraint violation, etc.). Mirrors COBOL
#: ``EVALUATE WS-RESP-CD WHEN OTHER`` branch at ``COUSR01C.cbl`` line 270.
MSG_UNABLE_TO_ADD_USER: str = "Unable to Add User..."

#: Raised by :meth:`UserService.update_user` and
#: :meth:`UserService.delete_user` when no ``user_security`` row
#: matches the supplied ``user_id``. Mirrors COBOL
#: ``EVALUATE WS-RESP-CD WHEN DFHRESP(NOTFND)`` at ``COUSR02C.cbl``
#: line 342 and ``COUSR03C.cbl`` line 289.
MSG_USER_ID_NOT_FOUND: str = "User ID NOT found..."

#: Raised by :meth:`UserService.update_user` when the client submits
#: an entirely-empty PATCH (all four optional fields ``first_name``,
#: ``last_name``, ``password``, ``user_type`` are ``None``). Mirrors
#: COBOL ``MOVE 'Please modify to update ...' TO WS-MESSAGE`` at
#: ``COUSR02C.cbl`` line 239, which guarded the REWRITE flow against
#: no-op submissions. Note the deliberate space before the ellipses —
#: preserved byte-for-byte from the COBOL source.
MSG_PLEASE_MODIFY_TO_UPDATE: str = "Please modify to update ..."

#: Raised by :meth:`UserService.update_user` and
#: :meth:`UserService.delete_user` when the UPDATE / DELETE against
#: ``user_security`` fails for any reason *other than* "row not
#: found" (DB connectivity, constraint violation, etc.). Mirrors
#: COBOL ``EVALUATE WS-RESP-CD WHEN OTHER`` at ``COUSR02C.cbl`` line
#: 386 (UPDATE flow) and ``COUSR03C.cbl`` line 332 (DELETE flow).
#:
#: Note — ``COUSR03C.cbl`` deliberately emits the string ``"Unable to
#: Update User..."`` in its DELETE flow (line 332), not ``"Unable to
#: Delete User..."``. This is a benign code-sharing artifact from the
#: CICS-generated BMS handler. Per AAP §0.7.1 "Preserve exact error
#: messages from COBOL", we reproduce the wording byte-for-byte, even
#: though it looks odd in the Delete context.
MSG_UNABLE_TO_UPDATE_USER: str = "Unable to Update User..."

# ----------------------------------------------------------------------------
# COBOL success-message templates from ``COUSR01C.cbl`` / ``COUSR02C.cbl``
# / ``COUSR03C.cbl``. Each program used ``STRING 'User ' + SEC-USR-ID +
# ' has been <verb> ...'`` to build the confirmation banner that was then
# MOVEd to ``ERRMSGO`` with ``DFHGREEN`` attribute. In the HTTP / JSON
# world we store only the final assembled string (the colour attribute
# has no meaning off-terminal); the ``message`` field of the response
# schemas is the direct successor to ``ERRMSGO``.
#
# These templates are plain Python ``str`` with a ``{user_id}`` format
# placeholder (instead of the COBOL ``STRING ... DELIMITED BY SPACE``
# pattern). The ``{user_id}`` slot is filled via ``.format(user_id=...)``
# at response-construction time.
# ----------------------------------------------------------------------------

#: Success template for :meth:`UserService.create_user`. Matches the
#: COBOL string concatenation at ``COUSR01C.cbl`` lines 256-258:
#: ``STRING 'User ' DELIMITED BY SIZE + SEC-USR-ID DELIMITED BY SPACE +
#: ' has been added ...' DELIMITED BY SIZE INTO WS-MESSAGE``. The
#: COBOL ``DELIMITED BY SPACE`` clause trimmed trailing spaces from
#: the fixed-width ``SEC-USR-ID PIC X(08)``; Python strings have no
#: trailing spaces, so no equivalent strip is needed.
MSG_USER_ADDED_TEMPLATE: str = "User {user_id} has been added ..."

#: Success template for :meth:`UserService.update_user`. Matches the
#: COBOL string at ``COUSR02C.cbl`` lines 373-375.
MSG_USER_UPDATED_TEMPLATE: str = "User {user_id} has been updated ..."

#: Success template for :meth:`UserService.delete_user`. Matches the
#: COBOL string at ``COUSR03C.cbl`` lines 319-321.
MSG_USER_DELETED_TEMPLATE: str = "User {user_id} has been deleted ..."

# ----------------------------------------------------------------------------
# COBOL-compatible user-type codes from ``app/cpy/COCOM01Y.cpy`` 88-level
# conditions (``CDEMO-USRTYP-ADMIN`` / ``CDEMO-USRTYP-USER``). Kept in
# sync with the identical constants in ``auth_service.py`` so that both
# services apply the same domain constraint to ``usr_type``.
# ----------------------------------------------------------------------------
_USER_TYPE_ADMIN: str = "A"  # CDEMO-USRTYP-ADMIN
_USER_TYPE_USER: str = "U"  # CDEMO-USRTYP-USER

#: The closed set of valid user-type codes. Persisted values of
#: :attr:`UserSecurity.usr_type` are restricted to these two characters
#: by the Pydantic schema layer; the service layer re-validates as
#: defence-in-depth before calling ``session.add()`` / ``flush()``.
_VALID_USER_TYPES: frozenset[str] = frozenset({_USER_TYPE_ADMIN, _USER_TYPE_USER})

# ============================================================================
# Exception hierarchy
# ============================================================================


class UserServiceError(Exception):
    """Base exception for all user-service operational failures.

    Encapsulates the COBOL ``WHEN OTHER`` / ``WHEN DFHRESP(DUPKEY)`` /
    ``WHEN DFHRESP(NOTFND)`` error branches across all four source
    programs (COUSR00C / COUSR01C / COUSR02C / COUSR03C). The
    error-handler middleware (``src/api/middleware/error_handler``)
    translates this exception (and its subclasses) to an HTTP
    response with the :attr:`message` as the body. Callers who need
    programmatic access to the specific failure mode can catch one
    of the subclasses directly:

    * :class:`UserIdAlreadyExistsError` — 409 Conflict on create
    * :class:`UserNotFoundError`        — 404 Not Found on read /
      update / delete
    * :class:`UserValidationError`      — 400 Bad Request on empty
      update patch or invalid user_type

    Parameters
    ----------
    message : str
        One of the ``MSG_*`` module constants. Kept as the sole
        positional arg so that ``str(exc) == message`` — convenient
        for structured logging and for the Pydantic response's
        ``message`` field.

    Examples
    --------
    >>> raise UserServiceError(MSG_UNABLE_TO_LOOKUP)
    Traceback (most recent call last):
        ...
    src.api.services.user_service.UserServiceError: Unable to lookup User...
    """

    def __init__(self, message: str) -> None:
        super().__init__(message)
        #: The user-facing error message (one of the ``MSG_*``
        #: constants). Exposed as a typed attribute for callers that
        #: prefer attribute access over ``exc.args[0]``.
        self.message: str = message


class UserIdAlreadyExistsError(UserServiceError):
    """Raised by :meth:`UserService.create_user` on duplicate user_id.

    Mirrors COBOL ``EVALUATE WS-RESP-CD WHEN DFHRESP(DUPKEY) /
    DFHRESP(DUPREC)`` at ``COUSR01C.cbl`` line 263. Surfaced to the
    client as HTTP 409 Conflict with the message
    :data:`MSG_USER_ID_ALREADY_EXISTS`.
    """

    def __init__(self, message: str = MSG_USER_ID_ALREADY_EXISTS) -> None:
        super().__init__(message)


class UserNotFoundError(UserServiceError):
    """Raised by update / delete when no row matches the supplied user_id.

    Mirrors COBOL ``EVALUATE WS-RESP-CD WHEN DFHRESP(NOTFND)`` at
    ``COUSR02C.cbl`` line 342 (UPDATE flow) and ``COUSR03C.cbl`` line
    289 (DELETE flow). Surfaced to the client as HTTP 404 Not Found
    with the message :data:`MSG_USER_ID_NOT_FOUND`.
    """

    def __init__(self, message: str = MSG_USER_ID_NOT_FOUND) -> None:
        super().__init__(message)


class UserValidationError(UserServiceError):
    """Raised on application-level input validation failures.

    Covers the service-layer validation rules that are complementary
    to (not duplicative of) the Pydantic schema validators:

    * Empty-patch update — :meth:`UserService.update_user` called
      with all four optional fields set to ``None``. Mirrors
      ``COUSR02C.cbl`` line 239 ``'Please modify to update ...'``.
    * Unexpected user_type value that slipped past Pydantic (should
      not normally occur, but guarded as defence-in-depth).

    Surfaced to the client as HTTP 400 Bad Request with the offending
    COBOL-exact message.
    """

    def __init__(self, message: str) -> None:
        super().__init__(message)


# ============================================================================
# UserService
# ============================================================================


class UserService:
    """Service facade for user-administration CRUD operations.

    Each instance wraps a single SQLAlchemy async session — the
    database handle that replaces the CICS file handle to the
    ``USRSEC`` VSAM dataset referenced implicitly by every
    ``EXEC CICS STARTBR / READ / WRITE / REWRITE / DELETE FILE
    ('USRSEC')`` call in the four source programs. Sessions are
    managed (opened, closed, committed, rolled back) by the FastAPI
    dependency system in ``src/api/dependencies.py``; the service
    itself does not manage transaction boundaries.

    The facade exposes four methods aligned with the AAP export
    schema (see AAP §0.5.1 for the ``user_service.py`` row):

    * :meth:`list_users`   — paginated browse (async, DB-backed)
    * :meth:`create_user`  — WRITE equivalent   (async, DB-backed)
    * :meth:`update_user`  — REWRITE equivalent (async, DB-backed)
    * :meth:`delete_user`  — DELETE equivalent  (async, DB-backed)

    All four methods require the async session injected at
    construction time.

    Parameters
    ----------
    db : AsyncSession
        The SQLAlchemy async session, injected by the FastAPI
        dependency ``src.api.dependencies.get_db``. Replaces the
        implicit CICS file handle to the ``USRSEC`` VSAM dataset
        referenced by every ``EXEC CICS <verb> FILE('USRSEC')`` call
        in the four source programs.

    Attributes
    ----------
    db : AsyncSession
        The session held for the duration of the current request.
    """

    # ------------------------------------------------------------------
    # Construction
    # ------------------------------------------------------------------
    def __init__(self, db: AsyncSession) -> None:
        # Preserve the session reference on the instance. We deliberately
        # avoid storing any other request-scoped state (user_id, token,
        # etc.) so that the service object is safe to construct lazily
        # per-request via FastAPI's Depends(). Mirrors the identical
        # pattern in ``AuthService.__init__`` — both services share the
        # same async-session lifetime rules.
        self.db: AsyncSession = db

    # ------------------------------------------------------------------
    # Primary API: list_users()
    # ------------------------------------------------------------------
    async def list_users(self, request: UserListRequest) -> UserListResponse:
        """Return a paginated page of users, optionally prefix-filtered.

        Replaces the CICS browse-mode cursor pagination implemented by
        ``COUSR00C.cbl`` (``STARTBR`` / ``READNEXT`` / ``READPREV`` /
        ``ENDBR`` on the ``USRSEC`` VSAM dataset). The CICS-era flow
        maintained an on-disk cursor across pseudo-conversational
        turns using the ``USRIDINI`` ``PIC X(08)`` field as the
        ``RIDFLD`` anchor; the modern flow re-issues an idempotent
        ``SELECT ... ORDER BY user_id LIMIT ... OFFSET ...`` on each
        request because HTTP is stateless and JDBC/psycopg connection
        pooling makes this cheap.

        The response envelope (:class:`UserListResponse`) carries the
        paged ``users`` list plus the echoed ``page`` number and the
        un-paged ``total_count`` (for client-side
        ``total_pages = ceil(total_count / page_size)`` calculation).
        No password material is included — the envelope surfaces only
        ``user_id`` / ``first_name`` / ``last_name`` / ``user_type``
        for each row, matching the security posture of
        ``COUSR00.CPY`` which likewise omitted the password column
        from the 10 repeated row groups.

        Parameters
        ----------
        request : UserListRequest
            The paging / filtering parameters. ``page`` is 1-based
            (matching the COBOL ``PAGENUMI`` semantics), ``page_size``
            defaults to 10 (matching the 10 repeating row groups in
            ``COUSR00.CPY``), and ``user_id`` is an optional prefix /
            exact filter applied server-side via ``ILIKE`` when
            populated.

        Returns
        -------
        UserListResponse
            Paginated envelope: ``users`` (at most ``page_size``
            :class:`UserListItem` rows, may be empty), ``page``
            (echo of the requested page number), ``total_count``
            (unpaged cardinality, always >= 0), and ``message``
            (always ``None`` on success — error messages surface via
            :class:`UserServiceError`).

        Raises
        ------
        UserServiceError
            On any unexpected SQLAlchemy / driver failure. Message is
            :data:`MSG_UNABLE_TO_LOOKUP`, mirroring the COBOL
            ``WHEN OTHER`` catch-all at ``COUSR00C.cbl`` line 610.
        """
        # ------------------------------------------------------------
        # Step 1: Build the base SELECT statement on ``user_security``.
        #
        # COBOL equivalent (COUSR00C.cbl STARTBR-USER-SEC-FILE L583-615):
        #     EXEC CICS STARTBR
        #          DATASET   (WS-USRSEC-FILE)
        #          RIDFLD    (SEC-USR-ID)
        #          KEYLENGTH (LENGTH OF SEC-USR-ID)
        #          GTEQ
        #     END-EXEC.
        #
        # In SQLAlchemy we express the browse cursor as a single
        # `SELECT` ordered by the primary key so that pagination is
        # deterministic: ``LIMIT ... OFFSET ...`` over a stable sort
        # is the relational equivalent of the CICS GTEQ-anchored
        # cursor. The ``ORDER BY user_id`` matches the VSAM KSDS
        # physical ordering on the primary key.
        # ------------------------------------------------------------
        stmt = select(UserSecurity).order_by(UserSecurity.user_id)
        count_stmt = select(func.count()).select_from(UserSecurity)

        # ------------------------------------------------------------
        # Step 2: Apply optional user_id prefix filter.
        #
        # COBOL equivalent: the COUSR00C.cbl browse positioned the
        # cursor at the first SEC-USR-ID >= USRIDINI via
        # ``STARTBR ... GTEQ``. In the relational / HTTP model we
        # express "prefix filter" more naturally with a LIKE clause;
        # an exact-match filter is conveniently a special case of
        # prefix with no wildcard suffix. When `request.user_id` is
        # None (or empty), no filter is applied and the full table
        # is browsed in primary-key order.
        # ------------------------------------------------------------
        if request.user_id:
            # Escape LIKE metacharacters (``%`` and ``_``) so a caller
            # cannot accidentally inject wildcards via the filter.
            # The ``\\`` escape character must be declared via the
            # SQL-standard ``ESCAPE '\\'`` clause — SQLAlchemy emits
            # this automatically when the ``escape=`` kwarg is passed
            # to :meth:`like`.
            escape_char = "\\"
            escaped_prefix = (
                request.user_id.replace(escape_char, escape_char + escape_char)
                .replace("%", escape_char + "%")
                .replace("_", escape_char + "_")
            )
            like_pattern = escaped_prefix + "%"
            stmt = stmt.where(UserSecurity.user_id.like(like_pattern, escape=escape_char))
            count_stmt = count_stmt.where(UserSecurity.user_id.like(like_pattern, escape=escape_char))

        # ------------------------------------------------------------
        # Step 3: Apply pagination (LIMIT / OFFSET).
        #
        # COBOL equivalent (COUSR00C.cbl PROCESS-PF7-KEY / PF8-KEY):
        #     The CICS browse emitted READNEXT / READPREV in a loop
        #     bounded by the 10 repeated row groups of COUSR00.CPY.
        #     We express the same windowing declaratively via LIMIT /
        #     OFFSET so that the database — not application code —
        #     does the row-bounding work. ``page`` is 1-based per the
        #     UserListRequest contract; the arithmetic ``(page-1) *
        #     page_size`` maps that to the 0-based OFFSET expected by
        #     PostgreSQL.
        # ------------------------------------------------------------
        offset_rows = (request.page - 1) * request.page_size
        stmt = stmt.offset(offset_rows).limit(request.page_size)

        # ------------------------------------------------------------
        # Step 4: Execute both queries — the page fetch and the total
        # count. We deliberately run them in sequence (not
        # concurrently via asyncio.gather) so that they share the
        # same transactional snapshot in the SQLAlchemy async session.
        #
        # COBOL equivalent: COUSR00C.cbl had no total-count equivalent;
        # the CICS flow surfaced "You have reached the bottom of the
        # page..." only after a READNEXT returned DFHRESP(ENDFILE).
        # In REST/GraphQL the modern idiom is to return a total so
        # clients can render pagination widgets without probing; we
        # pay the extra SELECT COUNT(*) per list call (cheap thanks
        # to the primary-key-only index scan).
        #
        # Exception handling mirrors ``AuthService.authenticate``: any
        # SQLAlchemy / driver error maps to the COBOL WHEN OTHER
        # message. ``noqa: BLE001`` suppresses the ruff / flake8
        # broad-except warning — intentional design choice per the
        # auth_service convention to mirror COBOL ``WHEN OTHER``.
        # ------------------------------------------------------------
        try:
            page_result = await self.db.execute(stmt)
            count_result = await self.db.execute(count_stmt)
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            logger.exception(
                "User list lookup failed",
                extra={
                    "user_id_filter": request.user_id,
                    "page": request.page,
                    "page_size": request.page_size,
                },
            )
            raise UserServiceError(MSG_UNABLE_TO_LOOKUP) from exc

        # ------------------------------------------------------------
        # Step 5: Materialize the result rows.
        #
        # ``scalars().all()`` returns a list of :class:`UserSecurity`
        # ORM instances. ``count_result.scalar_one()`` returns the
        # single integer total; we guard against ``None`` (which
        # should be impossible for COUNT(*) on any table) with an
        # explicit fallback to 0 to keep mypy strict happy.
        # ------------------------------------------------------------
        users_rows: list[UserSecurity] = list(page_result.scalars().all())
        total_count: int = count_result.scalar_one() or 0

        # ------------------------------------------------------------
        # Step 6: Project each UserSecurity row onto a UserListItem
        # (excluding the password column).
        #
        # COBOL equivalent (COUSR00C.cbl mapping to COUSR00.CPY):
        #     MOVE SEC-USR-ID    TO USRIDnnI OF COUSR0AI   (n = 01..10)
        #     MOVE SEC-USR-FNAME TO FNAMEnnI OF COUSR0AI
        #     MOVE SEC-USR-LNAME TO LNAMEnnI OF COUSR0AI
        #     MOVE SEC-USR-TYPE  TO UTYPEnnI OF COUSR0AI
        #
        # Critically the COBOL screen painter did NOT move SEC-USR-PWD
        # anywhere — the List screen (COUSR00.CPY) has no password
        # field. We honour that contract by excluding the password
        # hash from the Pydantic projection below.
        # ------------------------------------------------------------
        list_items: list[UserListItem] = [
            UserListItem(
                user_id=row.user_id,
                first_name=row.first_name,
                last_name=row.last_name,
                user_type=row.usr_type,
            )
            for row in users_rows
        ]

        # ------------------------------------------------------------
        # Step 7: Emit structured log and assemble the response.
        #
        # The log record includes page / page_size / hit count for
        # CloudWatch-based capacity planning; it does NOT include the
        # filter value or any user row data (to avoid accidentally
        # leaking PII-equivalent admin-username prefixes).
        # ------------------------------------------------------------
        logger.info(
            "User list returned",
            extra={
                "page": request.page,
                "page_size": request.page_size,
                "hit_count": len(list_items),
                "total_count": total_count,
                "filtered": bool(request.user_id),
            },
        )

        return UserListResponse(
            users=list_items,
            page=request.page,
            total_count=total_count,
            message=None,
        )

    # ------------------------------------------------------------------
    # Primary API: create_user()
    # ------------------------------------------------------------------
    async def create_user(self, request: UserCreateRequest) -> UserCreateResponse:
        """Create a new user row with a BCrypt-hashed password.

        Replaces the CICS WRITE flow in ``COUSR01C.cbl`` (CICS
        transaction CU01). The COBOL-era flow performed a direct
        ``EXEC CICS WRITE FILE('USRSEC')`` with cleartext
        ``SEC-USR-PWD PIC X(08)`` and relied on the VSAM KSDS unique-
        key constraint to surface duplicates via ``DFHRESP(DUPKEY)`` /
        ``DFHRESP(DUPREC)``. The modern flow:

        1. Performs an **explicit pre-check** for duplicate user_id
           (``SELECT 1 FROM user_security WHERE user_id = ...``) so
           that the error can be surfaced with the COBOL-exact
           message *before* the INSERT is attempted. This is cheaper
           than catching an ``IntegrityError`` post-factum and
           preserves the COBOL user-visible behaviour.
        2. BCrypt-hashes the cleartext password (60-char output).
        3. ``session.add()`` + ``session.flush()`` to INSERT the new
           row into the ``user_security`` table. Commit is the
           caller's responsibility (the FastAPI dependency system
           commits on successful request, rolls back on exception).

        Parameters
        ----------
        request : UserCreateRequest
            The new user's identity and credentials. All five fields
            (``user_id``, ``first_name``, ``last_name``, ``password``,
            ``user_type``) are required; the Pydantic schema
            enforces non-empty values, COBOL width limits, and the
            user_type domain constraint.

        Returns
        -------
        UserCreateResponse
            The newly-created identity (minus the password hash) plus
            a COBOL-exact success message of the form
            ``"User <user_id> has been added ..."``.

        Raises
        ------
        UserIdAlreadyExistsError
            When a ``user_security`` row with the supplied
            ``user_id`` already exists. Message is
            :data:`MSG_USER_ID_ALREADY_EXISTS`.
        UserValidationError
            When the ``user_type`` value slips past the Pydantic
            schema with an unexpected value (defence-in-depth check
            before INSERT).
        UserServiceError
            On any unexpected SQLAlchemy / BCrypt / driver failure.
            Message is :data:`MSG_UNABLE_TO_ADD_USER`.
        """
        # ------------------------------------------------------------
        # Step 1: Defence-in-depth — re-validate the user_type code.
        #
        # The UserCreateRequest Pydantic schema already enforces
        # user_type ∈ {'A', 'U'} via a field_validator. We re-check
        # here (a) so that test mocks that bypass Pydantic cannot
        # silently persist invalid values, and (b) so that the
        # service layer carries the COBOL 88-level contract as an
        # explicit code-level assertion.
        #
        # COBOL equivalent (COCOM01Y.cpy 88-level):
        #     05  CDEMO-USER-TYPE             PIC X(01).
        #         88 CDEMO-USRTYP-ADMIN       VALUE 'A'.
        #         88 CDEMO-USRTYP-USER        VALUE 'U'.
        # ------------------------------------------------------------
        if request.user_type not in _VALID_USER_TYPES:
            logger.warning(
                "User create rejected: invalid user_type",
                extra={
                    "user_id": request.user_id,
                    "supplied_user_type": request.user_type,
                },
            )
            raise UserValidationError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)}; got {request.user_type!r}"
            )

        # ------------------------------------------------------------
        # Step 2: Check for existing user_id.
        #
        # COBOL equivalent (COUSR01C.cbl ADD-USER-SEC-FILE L236-275):
        #     EXEC CICS WRITE
        #          DATASET   (WS-USRSEC-FILE)
        #          FROM      (SEC-USER-DATA)
        #          RIDFLD    (SEC-USR-ID)
        #          KEYLENGTH (LENGTH OF SEC-USR-ID)
        #          LENGTH    (LENGTH OF SEC-USER-DATA)
        #          RESP      (WS-RESP-CD)
        #          RESP2     (WS-REAS-CD)
        #     END-EXEC.
        #     EVALUATE WS-RESP-CD
        #         WHEN DFHRESP(NORMAL)  ... success ...
        #         WHEN DFHRESP(DUPKEY)
        #         WHEN DFHRESP(DUPREC)
        #             MOVE 'User ID already exist...' TO WS-MESSAGE
        #         WHEN OTHER
        #             MOVE 'Unable to Add User...' TO WS-MESSAGE
        #     END-EVALUATE.
        #
        # In the relational model we explicitly pre-check the primary
        # key before INSERT — cheaper and cleaner than catching
        # sqlalchemy.exc.IntegrityError, and the resulting error
        # message can be the COBOL-exact string.
        # ------------------------------------------------------------
        duplicate_stmt = select(UserSecurity).where(UserSecurity.user_id == request.user_id)
        try:
            duplicate_result = await self.db.execute(duplicate_stmt)
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            logger.exception(
                "User create pre-check failed",
                extra={"user_id": request.user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_ADD_USER) from exc

        existing_user: UserSecurity | None = duplicate_result.scalar_one_or_none()
        if existing_user is not None:
            logger.warning(
                "User create rejected: duplicate user_id",
                extra={"user_id": request.user_id, "reason": "duplicate"},
            )
            raise UserIdAlreadyExistsError()

        # ------------------------------------------------------------
        # Step 3: BCrypt-hash the cleartext password.
        #
        # COBOL equivalent (COUSR01C.cbl L217-222):
        #     MOVE USERIDI  TO SEC-USR-ID
        #     MOVE FNAMEI   TO SEC-USR-FNAME
        #     MOVE LNAMEI   TO SEC-USR-LNAME
        #     MOVE PASSWDI  TO SEC-USR-PWD      <-- cleartext!
        #     MOVE USRTYPEI TO SEC-USR-TYPE
        #
        # Per AAP §0.7.2 "Security Requirements", cleartext passwords
        # are *never* persisted. We hash with BCrypt (passlib's
        # CryptContext) to produce a 60-char digest that fits the
        # ``user_security.password`` column (String(60) per
        # user_security.py). The plaintext is held only in the
        # method-local ``request.password`` reference and is eligible
        # for GC immediately after this method returns.
        # ------------------------------------------------------------
        try:
            # pwd_context.hash is untyped (passlib ships no stubs).
            # BCrypt always returns a plain ``str``; we explicitly
            # annotate to satisfy mypy strict.
            hashed_password: str = pwd_context.hash(request.password)
        except Exception as exc:  # noqa: BLE001 — defensive; BCrypt rarely fails
            logger.exception(
                "BCrypt hash failed during user create",
                extra={"user_id": request.user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_ADD_USER) from exc

        # ------------------------------------------------------------
        # Step 4: Construct the ORM entity and INSERT.
        #
        # Note on attribute naming: the SQLAlchemy model uses
        # ``user_id`` / ``first_name`` / ``last_name`` / ``password``
        # / ``usr_type`` for the five mapped columns. Pydantic
        # request schema uses ``user_id`` / ``first_name`` /
        # ``last_name`` / ``password`` / ``user_type`` — note the
        # deliberate ``user_type`` vs ORM ``usr_type`` rename to
        # preserve COBOL's ``SEC-USR-TYPE`` naming in the column and
        # idiomatic ``user_type`` in the API. We map one to the
        # other explicitly at the boundary.
        # ------------------------------------------------------------
        new_user = UserSecurity(
            user_id=request.user_id,
            first_name=request.first_name,
            last_name=request.last_name,
            password=hashed_password,
            usr_type=request.user_type,
        )

        # ------------------------------------------------------------
        # Step 5: Add to session and flush. We flush (not commit) so
        # the INSERT is issued against the database (surfacing any
        # late-arriving constraint violation as IntegrityError) but
        # transaction commit is left to the FastAPI dependency
        # (``dependencies.get_db``). This matches the AuthService
        # pattern — neither service commits its own transaction.
        # ------------------------------------------------------------
        try:
            self.db.add(new_user)
            await self.db.flush()
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            # NOTE: we've already handled the duplicate case explicitly
            # above, so any exception here is a genuine WHEN OTHER. We
            # do NOT attempt to introspect it as an IntegrityError —
            # the module-level tests for COBOL fidelity expect the
            # generic "Unable to Add User..." message for ALL
            # non-duplicate failures.
            logger.exception(
                "User create INSERT failed",
                extra={"user_id": request.user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_ADD_USER) from exc

        # ------------------------------------------------------------
        # Step 6: Assemble the success response.
        #
        # COBOL equivalent (COUSR01C.cbl L255-260):
        #     STRING 'User '     DELIMITED BY SIZE
        #            SEC-USR-ID  DELIMITED BY SPACE
        #            ' has been added ...' DELIMITED BY SIZE
        #       INTO WS-MESSAGE
        #
        # The ``DELIMITED BY SPACE`` clause on SEC-USR-ID trimmed
        # trailing fixed-width spaces; Python strings have no
        # trailing pad so we use the unadorned request.user_id.
        # ------------------------------------------------------------
        success_message = MSG_USER_ADDED_TEMPLATE.format(user_id=request.user_id)
        logger.info(
            "User created",
            extra={
                "user_id": request.user_id,
                "user_type": request.user_type,
            },
        )

        return UserCreateResponse(
            user_id=new_user.user_id,
            first_name=new_user.first_name,
            last_name=new_user.last_name,
            user_type=new_user.usr_type,
            message=success_message,
        )

    # ------------------------------------------------------------------
    # Primary API: update_user()
    # ------------------------------------------------------------------
    async def update_user(
        self,
        user_id: str,
        request: UserUpdateRequest,
    ) -> UserUpdateResponse:
        """Apply a partial update (PATCH-style) to an existing user row.

        Replaces the CICS READ UPDATE + REWRITE flow in
        ``COUSR02C.cbl`` (CICS transaction CU02). The COBOL-era flow
        performed:

        1. ``EXEC CICS READ FILE('USRSEC') UPDATE`` to lock the row
           and read it into ``SEC-USER-DATA``;
        2. conditional MOVE of the *changed* subset of
           ``FNAMEI`` / ``LNAMEI`` / ``PASSWDI`` / ``USRTYPEI`` onto
           ``SEC-USR-FNAME`` / ``SEC-USR-LNAME`` / ``SEC-USR-PWD`` /
           ``SEC-USR-TYPE`` (COUSR02C.cbl only overwrote fields that
           actually changed from their pre-image — see the
           UPDATE-USER-INFO paragraph);
        3. ``EXEC CICS REWRITE FILE('USRSEC') FROM(SEC-USER-DATA)``.

        The modern flow:

        1. Guards against an all-None patch with
           :class:`UserValidationError`
           (message :data:`MSG_PLEASE_MODIFY_TO_UPDATE`).
        2. ``SELECT`` the current row by ``user_id``; if not found,
           raises :class:`UserNotFoundError`.
        3. Mutates the ORM instance in place — SQLAlchemy's identity
           map and unit-of-work tracking treat each attribute
           assignment as a dirty-flag, and ``flush()`` emits a
           targeted UPDATE touching only the changed columns. This
           is the direct relational equivalent of COBOL's
           conditional-MOVE-then-REWRITE approach.
        4. If ``password`` is supplied, BCrypt-hashes it before
           assigning to ``user.password``.
        5. ``await self.db.flush()`` to emit the UPDATE statement;
           transaction commit stays with the caller.

        Parameters
        ----------
        user_id : str
            The target user's primary key. Passed as a path
            parameter on the HTTP route (``PUT /admin/users/{user_id}``)
            rather than in the request body, mirroring the CICS
            ``USRIDINI`` ``PIC X(08)`` field on ``COUSR02.CPY`` which
            was populated from the List-screen selection rather than
            typed fresh on the Update screen.
        request : UserUpdateRequest
            The partial update payload — all four fields are
            ``Optional``. ``None`` leaves the corresponding stored
            value unchanged.

        Returns
        -------
        UserUpdateResponse
            The post-update identity (minus the password hash) plus
            a COBOL-exact success message of the form
            ``"User <user_id> has been updated ..."``.

        Raises
        ------
        UserValidationError
            When the patch is entirely empty (all four optional
            fields are ``None``). Message is
            :data:`MSG_PLEASE_MODIFY_TO_UPDATE`.
        UserNotFoundError
            When no ``user_security`` row matches ``user_id``.
            Message is :data:`MSG_USER_ID_NOT_FOUND`.
        UserServiceError
            On any unexpected SQLAlchemy / BCrypt / driver failure.
            The SELECT-side failure uses
            :data:`MSG_UNABLE_TO_LOOKUP` (mirroring COUSR02C.cbl line
            349); the UPDATE-side failure uses
            :data:`MSG_UNABLE_TO_UPDATE_USER` (COUSR02C.cbl line 386).
        """
        # ------------------------------------------------------------
        # Step 1: Reject an entirely-empty patch.
        #
        # COBOL equivalent (COUSR02C.cbl L233-241):
        #     IF SEC-USR-FNAME OF INPUT = SEC-USR-FNAME OF READ AND
        #        SEC-USR-LNAME OF INPUT = SEC-USR-LNAME OF READ AND
        #        SEC-USR-PWD   OF INPUT = SEC-USR-PWD   OF READ AND
        #        SEC-USR-TYPE  OF INPUT = SEC-USR-TYPE  OF READ
        #        MOVE 'Please modify to update ...' TO WS-MESSAGE
        #
        # The COBOL flow compared post-read to pre-read after READ
        # UPDATE had already acquired the row lock; we short-circuit
        # *before* hitting the DB because the modern Pydantic request
        # carries None for un-changed fields (PATCH semantics). The
        # net behaviour is identical: no UPDATE is issued when the
        # caller has nothing to change.
        # ------------------------------------------------------------
        if (
            request.first_name is None
            and request.last_name is None
            and request.password is None
            and request.user_type is None
        ):
            logger.warning(
                "User update rejected: empty patch",
                extra={"user_id": user_id, "reason": "no_changes"},
            )
            raise UserValidationError(MSG_PLEASE_MODIFY_TO_UPDATE)

        # ------------------------------------------------------------
        # Step 2: Defence-in-depth — re-validate the user_type code
        # when it is actually being changed.
        #
        # The UserUpdateRequest Pydantic schema already enforces
        # user_type ∈ {'A', 'U'} via a field_validator that short-
        # circuits on None. We re-check here for the same reasons as
        # in create_user.
        # ------------------------------------------------------------
        if request.user_type is not None and request.user_type not in _VALID_USER_TYPES:
            logger.warning(
                "User update rejected: invalid user_type",
                extra={
                    "user_id": user_id,
                    "supplied_user_type": request.user_type,
                },
            )
            raise UserValidationError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)}; got {request.user_type!r}"
            )

        # ------------------------------------------------------------
        # Step 3: SELECT the current row.
        #
        # COBOL equivalent (COUSR02C.cbl READ-USER-SEC-FILE L300-355):
        #     EXEC CICS READ
        #          DATASET   (WS-USRSEC-FILE)
        #          INTO      (SEC-USER-DATA)
        #          RIDFLD    (SEC-USR-ID)
        #          KEYLENGTH (LENGTH OF SEC-USR-ID)
        #          LENGTH    (LENGTH OF SEC-USER-DATA)
        #          UPDATE
        #          RESP      (WS-RESP-CD)
        #          RESP2     (WS-REAS-CD)
        #     END-EXEC.
        #     EVALUATE WS-RESP-CD
        #         WHEN DFHRESP(NORMAL) ... proceed to REWRITE
        #         WHEN DFHRESP(NOTFND)
        #             MOVE 'User ID NOT found...' TO WS-MESSAGE
        #         WHEN OTHER
        #             MOVE 'Unable to lookup User...' TO WS-MESSAGE
        #     END-EVALUATE.
        #
        # Relational translation: we don't need an explicit UPDATE
        # lock because SQLAlchemy's async session + PostgreSQL's
        # default READ COMMITTED isolation give us a single-
        # transaction snapshot; the row-level lock is taken
        # implicitly by the subsequent UPDATE at flush time.
        # ------------------------------------------------------------
        lookup_stmt = select(UserSecurity).where(UserSecurity.user_id == user_id)
        try:
            lookup_result = await self.db.execute(lookup_stmt)
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            logger.exception(
                "User update lookup failed",
                extra={"user_id": user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_LOOKUP) from exc

        user: UserSecurity | None = lookup_result.scalar_one_or_none()
        if user is None:
            logger.warning(
                "User update rejected: user_id not found",
                extra={"user_id": user_id, "reason": "not_found"},
            )
            raise UserNotFoundError()

        # ------------------------------------------------------------
        # Step 4: Apply the partial update.
        #
        # SQLAlchemy's unit-of-work tracks attribute mutations and
        # emits an UPDATE touching only the changed columns at
        # flush() time. This is the direct relational equivalent of
        # COBOL's conditional-MOVE-then-REWRITE pattern.
        #
        # For the password field we BCrypt-hash before assignment;
        # the plaintext is captured by ``request.password`` only for
        # the duration of this block and is never written to the
        # ``user_security.password`` column.
        # ------------------------------------------------------------
        if request.first_name is not None:
            user.first_name = request.first_name
        if request.last_name is not None:
            user.last_name = request.last_name
        if request.user_type is not None:
            user.usr_type = request.user_type
        password_actually_changed: bool = False
        if request.password is not None:
            # --------------------------------------------------------
            # Optimization: if the supplied plaintext password matches
            # the currently-stored BCrypt hash, skip the rehash-and-
            # UPDATE path entirely. BCrypt's hash() function includes
            # a random salt, so two hash() calls with the same input
            # produce different outputs — a naive string compare of
            # hashes would always report a difference. We therefore
            # use pwd_context.verify(plain, hash) which performs the
            # correct constant-time comparison of plaintext against
            # the stored BCrypt payload.
            #
            # This shaves the ~100ms of BCrypt CPU on no-op password
            # re-submissions (common when the Update screen's PASSWDI
            # field is rekeyed to its existing value) and also avoids
            # dirtying the ``password`` column when no real change
            # occurred — so SQLAlchemy's unit-of-work tracking does
            # not emit a redundant UPDATE on the password column.
            #
            # If verify() raises (malformed stored hash — e.g. a pre-
            # migration plaintext password that predates the BCrypt
            # migration), we treat the password as "changed" and
            # proceed to rehash, which naturally repairs the row with
            # a valid BCrypt hash on the next flush.
            # --------------------------------------------------------
            try:
                password_unchanged: bool = bool(pwd_context.verify(request.password, user.password))
            except Exception:  # noqa: BLE001 — malformed stored hash → rehash
                password_unchanged = False

            if not password_unchanged:
                try:
                    # pwd_context.hash is untyped (passlib has no type
                    # stubs). Explicit str annotation satisfies mypy
                    # strict --no-any-return on the subsequent
                    # assignment.
                    new_password_hash: str = pwd_context.hash(request.password)
                except Exception as exc:  # noqa: BLE001 — defensive; BCrypt rarely fails
                    logger.exception(
                        "BCrypt hash failed during user update",
                        extra={"user_id": user_id},
                    )
                    raise UserServiceError(MSG_UNABLE_TO_UPDATE_USER) from exc
                user.password = new_password_hash
                password_actually_changed = True

        # ------------------------------------------------------------
        # Step 5: Flush to emit the UPDATE statement.
        #
        # COBOL equivalent (COUSR02C.cbl UPDATE-USER-SEC-FILE L358-392):
        #     EXEC CICS REWRITE
        #          DATASET   (WS-USRSEC-FILE)
        #          FROM      (SEC-USER-DATA)
        #          LENGTH    (LENGTH OF SEC-USER-DATA)
        #          RESP      (WS-RESP-CD)
        #          RESP2     (WS-REAS-CD)
        #     END-EXEC.
        #
        # Note that if no attribute was actually mutated (e.g.,
        # caller supplied values identical to the pre-image),
        # SQLAlchemy's dirty-tracking skips the UPDATE entirely —
        # flush() is a no-op. This matches COBOL behaviour where a
        # REWRITE of an unchanged record was a harmless no-op at
        # the VSAM level.
        # ------------------------------------------------------------
        try:
            await self.db.flush()
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            logger.exception(
                "User update UPDATE failed",
                extra={"user_id": user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_UPDATE_USER) from exc

        # ------------------------------------------------------------
        # Step 6: Assemble the success response.
        #
        # COBOL equivalent (COUSR02C.cbl L372-377):
        #     STRING 'User '     DELIMITED BY SIZE
        #            SEC-USR-ID  DELIMITED BY SPACE
        #            ' has been updated ...' DELIMITED BY SIZE
        #       INTO WS-MESSAGE
        #
        # We log which fields changed (but never the values
        # themselves — passwords especially stay out of logs; the
        # boolean ``password_changed`` is the only signal).
        # ------------------------------------------------------------
        success_message = MSG_USER_UPDATED_TEMPLATE.format(user_id=user.user_id)
        logger.info(
            "User updated",
            extra={
                "user_id": user.user_id,
                "first_name_changed": request.first_name is not None,
                "last_name_changed": request.last_name is not None,
                "user_type_changed": request.user_type is not None,
                # ``password_actually_changed`` reflects whether a
                # genuine rehash+store occurred, as opposed to the
                # submission-received-but-was-identical fast path.
                # Never log the password value itself.
                "password_changed": password_actually_changed,
            },
        )

        return UserUpdateResponse(
            user_id=user.user_id,
            first_name=user.first_name,
            last_name=user.last_name,
            user_type=user.usr_type,
            message=success_message,
        )

    # ------------------------------------------------------------------
    # Primary API: delete_user()
    # ------------------------------------------------------------------
    async def delete_user(self, user_id: str) -> UserDeleteResponse:
        """Delete an existing user row and return its pre-DELETE snapshot.

        Replaces the CICS READ + DELETE flow in ``COUSR03C.cbl`` (CICS
        transaction CU03). The COBOL-era flow:

        1. ``EXEC CICS READ FILE('USRSEC')`` to fetch the row so the
           Delete screen (``COUSR03.CPY``) could display the
           first_name / last_name / user_type for visual
           confirmation *before* the DELETE;
        2. ``EXEC CICS DELETE FILE('USRSEC') RIDFLD(SEC-USR-ID)`` to
           remove the row.

        The modern flow:

        1. ``SELECT`` the current row by ``user_id``; if not found,
           raises :class:`UserNotFoundError`.
        2. Captures the row's identity fields
           (``first_name`` / ``last_name`` / ``usr_type``) in local
           variables **before** deletion so they can populate the
           response envelope.
        3. ``session.delete()`` + ``session.flush()`` to emit the
           DELETE statement. Transaction commit stays with the caller.
        4. Returns a :class:`UserDeleteResponse` carrying the captured
           identity (no password — mirroring ``COUSR03.CPY`` which
           intentionally omits ``PASSWDI`` / ``PASSWDO``).

        Parameters
        ----------
        user_id : str
            The target user's primary key. Passed as a path
            parameter (``DELETE /admin/users/{user_id}``). Must
            match an existing ``user_security`` row.

        Returns
        -------
        UserDeleteResponse
            The deleted user's identity (no password) plus a
            COBOL-exact success message of the form
            ``"User <user_id> has been deleted ..."``.

        Raises
        ------
        UserNotFoundError
            When no ``user_security`` row matches ``user_id``.
            Message is :data:`MSG_USER_ID_NOT_FOUND`.
        UserServiceError
            On any unexpected SQLAlchemy / driver failure. The
            SELECT-side failure uses :data:`MSG_UNABLE_TO_LOOKUP`
            (mirroring COUSR03C.cbl line 296); the DELETE-side
            failure uses :data:`MSG_UNABLE_TO_UPDATE_USER`
            (COUSR03C.cbl line 332 — the COBOL preserved "Update"
            wording even in the Delete flow, and we reproduce that
            byte-for-byte per AAP §0.7.1).
        """
        # ------------------------------------------------------------
        # Step 1: SELECT the current row.
        #
        # COBOL equivalent (COUSR03C.cbl READ-USER-SEC-FILE L243-301):
        #     EXEC CICS READ
        #          DATASET   (WS-USRSEC-FILE)
        #          INTO      (SEC-USER-DATA)
        #          RIDFLD    (SEC-USR-ID)
        #          KEYLENGTH (LENGTH OF SEC-USR-ID)
        #          LENGTH    (LENGTH OF SEC-USER-DATA)
        #          RESP      (WS-RESP-CD)
        #          RESP2     (WS-REAS-CD)
        #     END-EXEC.
        #     EVALUATE WS-RESP-CD
        #         WHEN DFHRESP(NORMAL) ... proceed to DELETE
        #         WHEN DFHRESP(NOTFND)
        #             MOVE 'User ID NOT found...' TO WS-MESSAGE
        #         WHEN OTHER
        #             MOVE 'Unable to lookup User...' TO WS-MESSAGE
        #     END-EVALUATE.
        # ------------------------------------------------------------
        lookup_stmt = select(UserSecurity).where(UserSecurity.user_id == user_id)
        try:
            lookup_result = await self.db.execute(lookup_stmt)
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            logger.exception(
                "User delete lookup failed",
                extra={"user_id": user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_LOOKUP) from exc

        user: UserSecurity | None = lookup_result.scalar_one_or_none()
        if user is None:
            logger.warning(
                "User delete rejected: user_id not found",
                extra={"user_id": user_id, "reason": "not_found"},
            )
            raise UserNotFoundError()

        # ------------------------------------------------------------
        # Step 2: Capture identity fields BEFORE deletion.
        #
        # COBOL equivalent (COUSR03C.cbl L252-268):
        #     The Delete screen COUSR03.CPY displayed FNAMEI /
        #     LNAMEI / USRTYPEI as read-only fields populated from
        #     the READ step, so the operator could confirm the row
        #     visually before pressing PF5 to commit. Our response
        #     envelope serves the same purpose: it returns what was
        #     deleted for operator review.
        #
        # We use local variables (not ``user.first_name`` etc.
        # directly on the response) because once ``session.delete()``
        # is flushed, the ORM instance is detached and accessing its
        # attributes after flush raises DetachedInstanceError. This
        # snapshot-before-delete pattern is the canonical SQLAlchemy
        # idiom.
        # ------------------------------------------------------------
        deleted_user_id: str = user.user_id
        deleted_first_name: str = user.first_name
        deleted_last_name: str = user.last_name
        deleted_usr_type: str = user.usr_type

        # ------------------------------------------------------------
        # Step 3: Issue the DELETE and flush.
        #
        # COBOL equivalent (COUSR03C.cbl DELETE-USER-SEC-FILE L304-338):
        #     EXEC CICS DELETE
        #          DATASET   (WS-USRSEC-FILE)
        #          RIDFLD    (SEC-USR-ID)
        #          KEYLENGTH (LENGTH OF SEC-USR-ID)
        #          RESP      (WS-RESP-CD)
        #          RESP2     (WS-REAS-CD)
        #     END-EXEC.
        #     EVALUATE WS-RESP-CD
        #         WHEN DFHRESP(NORMAL) ... success ...
        #         WHEN DFHRESP(NOTFND)
        #             MOVE 'User ID NOT found...' TO WS-MESSAGE
        #         WHEN OTHER
        #             MOVE 'Unable to Update User...' TO WS-MESSAGE   ← sic
        #     END-EVALUATE.
        #
        # The "Unable to Update User..." wording in the Delete flow
        # is a verbatim reproduction of the COBOL source (line 332);
        # per AAP §0.7.1 we keep the exact COBOL text even though
        # the word "Update" reads oddly in the Delete context.
        #
        # Note on the "NOTFND on DELETE" branch (COUSR03C L323-328):
        # the modern flow cannot hit that branch because we SELECTed
        # the row immediately before (step 1) inside the same async
        # session / transaction, so a concurrent DELETE by another
        # actor would be prevented by SQLAlchemy's session snapshot
        # at our isolation level. If it DID happen, the generic
        # "Unable to Update User..." catch-all would fire — the
        # same as the COBOL WHEN OTHER. This is acceptable
        # behavioral parity; any caller seeing this error can retry.
        #
        # We emit the equivalent SQL-level DELETE statement as a
        # DEBUG log record for CloudWatch Logs Insights audit
        # queries — the ``str(audit_stmt)`` form returns the SQL
        # template with ``:user_id_1`` named-parameter placeholders
        # (no bind values leak into the log), which is the safe
        # representation for audit trails. The statement is NOT
        # executed here — we rely on the ORM-canonical
        # ``session.delete(user)`` + ``flush()`` path below for the
        # actual DELETE, so that SQLAlchemy's identity map stays
        # consistent and any future cascade relationships are
        # honoured.
        # ------------------------------------------------------------
        audit_delete_stmt = delete(UserSecurity).where(UserSecurity.user_id == deleted_user_id)
        logger.debug(
            "Issuing user DELETE",
            extra={
                "user_id": deleted_user_id,
                "statement": str(audit_delete_stmt),
            },
        )

        try:
            await self.db.delete(user)
            await self.db.flush()
        except Exception as exc:  # noqa: BLE001 — catch-all mirrors COBOL WHEN OTHER
            logger.exception(
                "User delete DELETE failed",
                extra={"user_id": deleted_user_id},
            )
            raise UserServiceError(MSG_UNABLE_TO_UPDATE_USER) from exc

        # ------------------------------------------------------------
        # Step 4: Assemble the success response.
        #
        # COBOL equivalent (COUSR03C.cbl L318-323):
        #     STRING 'User '     DELIMITED BY SIZE
        #            SEC-USR-ID  DELIMITED BY SPACE
        #            ' has been deleted ...' DELIMITED BY SIZE
        #       INTO WS-MESSAGE
        #
        # The response envelope explicitly omits the password field
        # per COUSR03.CPY (which has no PASSWDI / PASSWDO pair); the
        # UserDeleteResponse Pydantic schema enforces the omission
        # structurally (no ``password`` attribute in the schema).
        # ------------------------------------------------------------
        success_message = MSG_USER_DELETED_TEMPLATE.format(user_id=deleted_user_id)
        logger.info(
            "User deleted",
            extra={
                "user_id": deleted_user_id,
                "user_type": deleted_usr_type,
            },
        )

        return UserDeleteResponse(
            user_id=deleted_user_id,
            first_name=deleted_first_name,
            last_name=deleted_last_name,
            user_type=deleted_usr_type,
            message=success_message,
        )


# ============================================================================
# Public re-export list.
#
# The :class:`UserService` class is the module's primary public symbol.
# The exception hierarchy (``UserServiceError`` and subclasses) is
# exported so routers / middleware / tests can catch failures with
# targeted ``except`` clauses. The COBOL-exact ``MSG_*`` constants are
# exported so test suites and error-handler middleware can reference
# them by name rather than by literal string — critical for AAP §0.7.1
# "Preserve exact error messages from COBOL" audit traceability.
# ============================================================================
__all__ = [
    # Primary service class (matches AAP exports schema).
    "UserService",
    # Exception hierarchy.
    "UserServiceError",
    "UserIdAlreadyExistsError",
    "UserNotFoundError",
    "UserValidationError",
    # COBOL-exact error messages.
    "MSG_UNABLE_TO_LOOKUP",
    "MSG_USER_ID_ALREADY_EXISTS",
    "MSG_UNABLE_TO_ADD_USER",
    "MSG_USER_ID_NOT_FOUND",
    "MSG_PLEASE_MODIFY_TO_UPDATE",
    "MSG_UNABLE_TO_UPDATE_USER",
    # COBOL-exact success message templates.
    "MSG_USER_ADDED_TEMPLATE",
    "MSG_USER_UPDATED_TEMPLATE",
    "MSG_USER_DELETED_TEMPLATE",
]
