# ============================================================================
# Source: COBOL BMS symbolic maps COUSR00.CPY (User List,   F-018),
#                                 COUSR01.CPY (User Add,    F-019),
#                                 COUSR02.CPY (User Update, F-020),
#                                 COUSR03.CPY (User Delete, F-021)
#         + COBOL record layout copybook CSUSR01Y.cpy (SEC-USER-DATA, 80 bytes).
# ============================================================================
# Mainframe-to-Cloud migration: CICS User Administration screens
#   → FastAPI REST + Strawberry GraphQL endpoints.
#
# Replaces:
#   * BMS input/output fields previously submitted/emitted via
#     CICS RECEIVE/SEND MAP ('COUSR0A', 'COUSR1A', 'COUSR2A', 'COUSR3A')
#     in COUSR00C.cbl, COUSR01C.cbl, COUSR02C.cbl, COUSR03C.cbl:
#       - USRIDINI/USERIDI PIC X(08) — user ID (filter/display/input)
#       - FNAMEI/FNAME0nI  PIC X(20) — first name
#       - LNAMEI/LNAME0nI  PIC X(20) — last name
#       - PASSWDI          PIC X(08) — password (Add + Update only;
#                                      omitted from Delete screen)
#       - USRTYPEI/UTYPE0nI PIC X(01) — user type ('A'=admin, 'U'=user)
#       - PAGENUMI         PIC X(08) — current page indicator
#       - ERRMSGI          PIC X(78) — info/error message
#   * The CSUSR01Y.cpy SEC-USER-DATA record (80-byte fixed-length VSAM row)
#     whose fields (SEC-USR-ID, SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD,
#     SEC-USR-TYPE) are surfaced by these schemas.
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
"""Pydantic v2 User Administration schemas for the CardDemo REST/GraphQL API.

Converts four BMS symbolic-map copybooks (``COUSR00.CPY`` through
``COUSR03.CPY``) plus the underlying user security record layout
(``CSUSR01Y.cpy``) into eight Pydantic v2 request/response models that drive
the User Administration endpoints:

* ``GET  /admin/users``            (list,   Feature F-018, COUSR00C.cbl)
* ``POST /admin/users``            (create, Feature F-019, COUSR01C.cbl)
* ``PUT  /admin/users/{user_id}``  (update, Feature F-020, COUSR02C.cbl)
* ``DELETE /admin/users/{user_id}`` (delete, Feature F-021, COUSR03C.cbl)

COBOL → Python Field Mapping
----------------------------
=============================  ==========================  ==========================
COBOL Field                    Py Class                    Python Field
=============================  ==========================  ==========================
COUSR00 USRIDINI   X(08)       UserListRequest             ``user_id`` (filter)
COUSR00 PAGENUMI   X(08)       UserListRequest             ``page`` (numeric)
(new — pagination)             UserListRequest             ``page_size`` (default 10)
COUSR00 USRID0nI   X(08)       UserListItem                ``user_id``
COUSR00 FNAME0nI   X(20)       UserListItem                ``first_name``
COUSR00 LNAME0nI   X(20)       UserListItem                ``last_name``
COUSR00 UTYPE0nI   X(01)       UserListItem                ``user_type``
(new — envelope)               UserListResponse            ``users`` (List[Item])
COUSR00 PAGENUMI   X(08)       UserListResponse            ``page``
(new — envelope)               UserListResponse            ``total_count``
COUSR00 ERRMSGI    X(78)       UserListResponse            ``message``

COUSR01 USERIDI    X(08)       UserCreateRequest           ``user_id``
COUSR01 FNAMEI     X(20)       UserCreateRequest           ``first_name``
COUSR01 LNAMEI     X(20)       UserCreateRequest           ``last_name``
COUSR01 PASSWDI    X(08)       UserCreateRequest           ``password``
COUSR01 USRTYPEI   X(01)       UserCreateRequest           ``user_type``
(echo of request)              UserCreateResponse          ``user_id``
(echo of request)              UserCreateResponse          ``first_name``
(echo of request)              UserCreateResponse          ``last_name``
(echo of request)              UserCreateResponse          ``user_type``
COUSR01 ERRMSGI    X(78)       UserCreateResponse          ``message``

(URL path param)               UserUpdateRequest           — (``user_id`` not in body)
COUSR02 FNAMEI     X(20)       UserUpdateRequest           ``first_name`` (optional)
COUSR02 LNAMEI     X(20)       UserUpdateRequest           ``last_name``  (optional)
COUSR02 PASSWDI    X(08)       UserUpdateRequest           ``password``   (optional)
COUSR02 USRTYPEI   X(01)       UserUpdateRequest           ``user_type``  (optional)
COUSR02 USRIDINI   X(08)       UserUpdateResponse          ``user_id`` (echo of URL)
(echo after update)            UserUpdateResponse          ``first_name``
(echo after update)            UserUpdateResponse          ``last_name``
(echo after update)            UserUpdateResponse          ``user_type``
COUSR02 ERRMSGI    X(78)       UserUpdateResponse          ``message``

COUSR03 USRIDINI   X(08)       UserDeleteResponse          ``user_id``
COUSR03 FNAMEI     X(20)       UserDeleteResponse          ``first_name``
COUSR03 LNAMEI     X(20)       UserDeleteResponse          ``last_name``
COUSR03 USRTYPEI   X(01)       UserDeleteResponse          ``user_type``
COUSR03 ERRMSGI    X(78)       UserDeleteResponse          ``message``
=============================  ==========================  ==========================

Design Notes
------------
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length/default constraints and
  :func:`~pydantic.field_validator` for business-rule validation. Response
  schemas opt into ``ConfigDict(from_attributes=True)`` (Pydantic v2 ORM
  mode) so the user-service layer can construct them directly from
  :class:`~src.shared.models.user_security.UserSecurity` SQLAlchemy rows.
* **User type domain** — the ``user_type`` field is constrained to the
  two COBOL 88-level values from ``COCOM01Y.cpy``: ``'A'``
  (``CDEMO-USRTYP-ADMIN``) and ``'U'`` (``CDEMO-USRTYP-USER``). Any
  other value is rejected at the schema layer before reaching the
  service layer.
* **Password handling** — ``UserCreateRequest.password`` and
  ``UserUpdateRequest.password`` accept cleartext up to 8 characters
  (the COBOL ``PASSWDI`` ``PIC X(08)`` limit). The service layer
  (:mod:`src.api.services.user_service`) applies BCrypt hashing before
  persisting to the ``user_security.password`` column (which stores a
  60-character BCrypt hash rather than the original 8-character
  plaintext). The schemas never return the password on any response.
* **Delete response has NO password** — ``UserDeleteResponse`` omits the
  ``password`` field entirely because ``COUSR03.CPY`` has no
  ``PASSWDI``/``PASSWDO`` field (the delete screen is display-only and
  never surfaces the stored password).
* **Pagination defaults** — the original CICS User List screen
  (``COUSR00C.cbl``) displayed exactly 10 repeating rows per page; the
  API default ``page_size`` of 10 preserves that paging cadence. Callers
  may override up to ``_MAX_PAGE_SIZE`` (100) for bulk admin views.
* **String preservation** — ``user_id``, ``first_name``, ``last_name``,
  and ``user_type`` are stored as :class:`str` (never stripped by the
  schema layer) so that fixed-width COBOL ``PIC X(N)`` values with
  trailing spaces flow through unchanged — preserving exact parity
  with the on-disk VSAM ``SEC-USER-DATA`` row layout.
* **``ConfigDict(from_attributes=True)``** is applied to the five
  *response* schemas (``UserListItem``, ``UserListResponse``,
  ``UserCreateResponse``, ``UserUpdateResponse``,
  ``UserDeleteResponse``) so the service layer can instantiate them
  directly from a :class:`UserSecurity` ORM row without an explicit
  ``dict`` conversion. It is NOT applied to request schemas because
  request payloads always arrive as JSON-decoded dicts from the
  REST/GraphQL transport layer.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI/Uvicorn container image).
"""

from typing import List, Optional  # noqa: UP035  # schema requires `typing.List` / `typing.Optional`

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Private module constants — COBOL PIC X(N) field-length ceilings
# ---------------------------------------------------------------------------
# Keeping these as private module constants rather than public enums keeps
# the transport schemas lightweight; they are pure implementation details
# of the mainframe-to-Python mapping. Every value below is derived
# verbatim from the corresponding BMS PIC X(N) clause.
_USER_ID_MAX_LEN: int = 8  # COUSR0x USERIDI / USRIDINI / USRID0nI PIC X(08)
_FIRST_NAME_MAX_LEN: int = 20  # COUSR0x FNAMEI  / FNAME0nI           PIC X(20)
_LAST_NAME_MAX_LEN: int = 20  # COUSR0x LNAMEI  / LNAME0nI           PIC X(20)
_PASSWORD_MAX_LEN: int = 8  # COUSR01/02 PASSWDI                    PIC X(08)
_USER_TYPE_MAX_LEN: int = 1  # COUSR0x USRTYPEI / UTYPE0nI           PIC X(01)
_ERRMSG_MAX_LEN: int = 78  # COUSR0x ERRMSGI                       PIC X(78)

# Pagination defaults — the original CICS User List screen (COUSR0A)
# displayed exactly 10 repeating row groups per page. Preserve that cadence
# as the default; cap the ceiling at 100 for bulk admin queries.
_DEFAULT_PAGE_SIZE: int = 10
_MAX_PAGE_SIZE: int = 100
_MIN_PAGE: int = 1  # PAGENUMI is 1-based in the CICS UI
_MIN_PAGE_SIZE: int = 1

# User-type domain — COCOM01Y.cpy 88-level conditions
#   CDEMO-USRTYP-ADMIN ('A') — administrator (access to admin menu + user CRUD)
#   CDEMO-USRTYP-USER  ('U') — regular end-user (access to main menu only)
_USER_TYPE_ADMIN: str = "A"
_USER_TYPE_USER: str = "U"
_VALID_USER_TYPES: frozenset[str] = frozenset({_USER_TYPE_ADMIN, _USER_TYPE_USER})


# ---------------------------------------------------------------------------
# UserListRequest — query-string payload for GET /admin/users
# ---------------------------------------------------------------------------
class UserListRequest(BaseModel):
    """Request filter/pagination parameters for the User List endpoint.

    Derived from the filter and pagination fields of the CICS User List
    BMS map ``COUSR0AI`` in ``app/cpy-bms/COUSR00.CPY``:

    * ``USRIDINI`` ``PIC X(08)`` — optional user-ID filter (``F5`` key in
      the original CICS UI populated this field before re-submitting).
    * ``PAGENUMI`` ``PIC X(08)`` — the current page indicator; re-submitted
      with ``F7``/``F8`` keys. In the new REST contract this is a
      1-based integer ``page`` query parameter.
    * ``page_size`` is net-new for the REST contract — the original CICS
      screen hard-coded 10 rows per page (the 10 repeating row groups
      ``01`` through ``10`` in ``COUSR00.CPY``). We preserve that as the
      default and allow override up to 100.

    Attributes
    ----------
    user_id : Optional[str]
        Optional user-ID prefix/exact filter. Max 8 characters (COBOL
        ``USRIDINI`` ``PIC X(08)`` constraint). ``None`` (or unset)
        returns the full unfiltered page.
    page : int
        1-based page number. Defaults to 1. Must be >= 1.
    page_size : int
        Number of items per page. Defaults to 10 (matching the CICS
        screen's 10 repeated row groups). Must be between 1 and 100.
    """

    user_id: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_USER_ID_MAX_LEN,
        description=(
            "Optional user-ID filter — maps to COUSR00 USRIDINI PIC X(08). "
            "When None or unset, returns the full unfiltered result page."
        ),
    )
    page: int = Field(
        default=_MIN_PAGE,
        ge=_MIN_PAGE,
        description=(
            "1-based page number — maps to COUSR00 PAGENUMI PIC X(08) (re-numbered from the F7/F8 paging keys)."
        ),
    )
    page_size: int = Field(
        default=_DEFAULT_PAGE_SIZE,
        ge=_MIN_PAGE_SIZE,
        le=_MAX_PAGE_SIZE,
        description=(
            "Items per page. Defaults to 10 (matching the COUSR00 screen's 10 repeating row groups). Range: 1..100."
        ),
    )

    @field_validator("user_id")
    @classmethod
    def _validate_user_id_filter(cls, value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """Accept ``None`` / unset; otherwise enforce COBOL ``PIC X(08)``.

        Unlike the Create/Update request validators, an *empty* filter
        is legitimate — it means "return all users on this page". We
        therefore preserve ``None`` as-is and only enforce the length
        ceiling when a value is supplied. The value is NOT stripped:
        the service layer compares against the fixed-width
        ``user_security.user_id`` column and incidental leading/trailing
        whitespace carries meaning when the user_id is right-padded
        with spaces from the COBOL era.
        """
        if value is None:
            return value
        if not isinstance(value, str):
            raise ValueError(f"user_id must be a string; got {type(value).__name__}")
        if len(value) > _USER_ID_MAX_LEN:
            raise ValueError(
                f"user_id exceeds max length {_USER_ID_MAX_LEN} "
                f"(COBOL PIC X({_USER_ID_MAX_LEN})); got length {len(value)}"
            )
        return value


# ---------------------------------------------------------------------------
# UserListItem — one of up to 10 rows in a User List page
# ---------------------------------------------------------------------------
class UserListItem(BaseModel):
    """Single row in a User List response page.

    Derived from one of the 10 repeated row groups in ``COUSR00.CPY``
    (row indices ``01`` through ``10``). Each group contains:

    * ``USRID0nI`` ``PIC X(08)``  — ``user_id``
    * ``FNAME0nI`` ``PIC X(20)``  — ``first_name``
    * ``LNAME0nI`` ``PIC X(20)``  — ``last_name``
    * ``UTYPE0nI`` ``PIC X(01)``  — ``user_type``
    * ``SEL0nnI``  ``PIC X(01)``  — row-selection character (not
      surfaced in the REST/GraphQL contract; the modern UI uses
      per-row HTTP verbs instead of a single-letter action code)

    Attributes
    ----------
    user_id : str
        User ID. Max 8 characters (COBOL ``USRID0nI`` ``PIC X(08)``).
    first_name : str
        First name. Max 20 characters (COBOL ``FNAME0nI`` ``PIC X(20)``).
    last_name : str
        Last name. Max 20 characters (COBOL ``LNAME0nI`` ``PIC X(20)``).
    user_type : str
        User role — ``'A'`` (admin) or ``'U'`` (user). 1 character
        (COBOL ``UTYPE0nI`` ``PIC X(01)``).
    """

    # Pydantic v2 ORM mode — allow construction directly from a
    # UserSecurity SQLAlchemy row without an explicit dict conversion.
    model_config = ConfigDict(from_attributes=True)

    user_id: str = Field(
        ...,
        max_length=_USER_ID_MAX_LEN,
        description=(
            "User ID — maps to COUSR00 USRID0nI PIC X(08) and to SEC-USR-ID in CSUSR01Y.cpy / user_security.user_id."
        ),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description=(
            "First name — maps to COUSR00 FNAME0nI PIC X(20) and to "
            "SEC-USR-FNAME in CSUSR01Y.cpy / user_security.first_name."
        ),
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description=(
            "Last name — maps to COUSR00 LNAME0nI PIC X(20) and to "
            "SEC-USR-LNAME in CSUSR01Y.cpy / user_security.last_name."
        ),
    )
    user_type: str = Field(
        ...,
        max_length=_USER_TYPE_MAX_LEN,
        description=(
            "User role — maps to COUSR00 UTYPE0nI PIC X(01) and to "
            "SEC-USR-TYPE in CSUSR01Y.cpy / user_security.usr_type. "
            "'A' = admin, 'U' = user (COCOM01Y.cpy 88-level values)."
        ),
    )

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); "
                f"got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# UserListResponse — paginated envelope for GET /admin/users
# ---------------------------------------------------------------------------
class UserListResponse(BaseModel):
    """Paginated envelope for the User List endpoint.

    Replaces the CICS ``SEND MAP ('COUSR0AO')`` screen refresh that
    previously emitted up to 10 row groups plus the page number, user-ID
    filter echo, and optional error message.

    Attributes
    ----------
    users : List[UserListItem]
        The list of user rows on this page. Length is at most
        ``page_size`` (by default 10 — matching the 10 repeated row
        groups in ``COUSR00.CPY``). May be empty when no users match
        the filter or when paging past the end of the result set.
    page : int
        1-based current page number (echo of the request ``page``).
        Maps to COUSR00 PAGENUMI PIC X(08).
    total_count : int
        Total number of users matching the filter across all pages
        (i.e., the unpaged cardinality). Enables the client to compute
        ``total_pages = ceil(total_count / page_size)`` without an
        extra round-trip. Always >= 0.
    message : Optional[str]
        Optional informational or error message, up to 78 characters —
        maps to COUSR00 ``ERRMSGI`` ``PIC X(78)``. ``None`` when no
        remark is needed.
    """

    # Pydantic v2 ORM mode — permits construction from attribute-based
    # objects (e.g., a service-layer result wrapper with .users /
    # .page / .total_count / .message attributes).
    model_config = ConfigDict(from_attributes=True)

    users: List[UserListItem] = Field(  # noqa: UP006  # schema requires `typing.List`
        default_factory=list,
        description=("Up to page_size UserListItem rows (default 10 — matching COUSR00's 10 repeated row groups)."),
    )
    page: int = Field(
        default=_MIN_PAGE,
        ge=_MIN_PAGE,
        description=("1-based current page number — echoes COUSR00 PAGENUMI PIC X(08) (now an integer)."),
    )
    total_count: int = Field(
        default=0,
        ge=0,
        description=(
            "Total number of users matching the filter across all "
            "pages (unpaged cardinality). Enables client-side "
            "total_pages computation."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=("Optional info/error message, max 78 chars — maps to COUSR00 ERRMSGI PIC X(78)."),
    )


# ---------------------------------------------------------------------------
# UserCreateRequest — POST /admin/users payload
# ---------------------------------------------------------------------------
class UserCreateRequest(BaseModel):
    """User Add request — converted from BMS map ``COUSR1AI`` (F-019).

    Maps the five business-input fields from ``app/cpy-bms/COUSR01.CPY``.
    All five are REQUIRED — the original CICS program ``COUSR01C.cbl``
    emitted a "Please enter..." message (from ``CSMSG01Y.cpy``) and
    blocked the WRITE to the USRSEC VSAM cluster when any of them was
    blank.

    Attributes
    ----------
    user_id : str
        New user ID. Max 8 characters (COBOL ``USERIDI`` ``PIC X(08)``).
        Must be non-empty. Becomes the primary key of the
        ``user_security`` row. The service layer rejects duplicates at
        the database layer (unique constraint on ``user_id``).
    first_name : str
        First name. Max 20 characters (``FNAMEI`` ``PIC X(20)``).
    last_name : str
        Last name. Max 20 characters (``LNAMEI`` ``PIC X(20)``).
    password : str
        Cleartext password submitted over HTTPS. Max 8 characters
        (COBOL ``PASSWDI`` ``PIC X(08)`` constraint). Must be non-empty.
        The service layer applies BCrypt hashing before persisting to
        the ``user_security.password`` column; the plaintext never
        touches disk.
    user_type : str
        User role — must be ``'A'`` (admin) or ``'U'`` (user). 1
        character (``USRTYPEI`` ``PIC X(01)``). Enforced by the
        ``COCOM01Y.cpy`` 88-level constraint.
    """

    user_id: str = Field(
        ...,
        max_length=_USER_ID_MAX_LEN,
        description=("New user ID credential — from COUSR01 USERIDI PIC X(08). Becomes the user_security primary key."),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description="First name — from COUSR01 FNAMEI PIC X(20).",
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description="Last name — from COUSR01 LNAMEI PIC X(20).",
    )
    password: str = Field(
        ...,
        max_length=_PASSWORD_MAX_LEN,
        description=(
            "Cleartext password — from COUSR01 PASSWDI PIC X(08). "
            "Submitted as cleartext over HTTPS; BCrypt-hashed by the "
            "service layer before persistence."
        ),
    )
    user_type: str = Field(
        ...,
        max_length=_USER_TYPE_MAX_LEN,
        description=(
            "User role — from COUSR01 USRTYPEI PIC X(01). 'A' = admin, 'U' = user (COCOM01Y.cpy 88-level values)."
        ),
    )

    # -----------------------------------------------------------------
    # Field-level validators
    # -----------------------------------------------------------------
    @field_validator("user_id")
    @classmethod
    def _validate_user_id(cls, value: str) -> str:
        """Ensure user_id is non-empty and within COBOL ``PIC X(08)``.

        Matches the CICS behavior in ``COUSR01C.cbl`` which rejected
        the Add attempt with a "Please enter User ID..." message (from
        ``CSMSG01Y.cpy``) when ``USERIDI`` was blank or zero-length.
        Preserves the original value (no strip) — the service layer
        compares against the fixed-width ``user_security.user_id`` key.
        """
        if value is None:
            raise ValueError("user_id must not be null")
        if not isinstance(value, str):
            raise ValueError(f"user_id must be a string; got {type(value).__name__}")
        if not value or not value.strip():
            raise ValueError("user_id must not be empty")
        if len(value) > _USER_ID_MAX_LEN:
            raise ValueError(
                f"user_id exceeds max length {_USER_ID_MAX_LEN} "
                f"(COBOL PIC X({_USER_ID_MAX_LEN})); got length {len(value)}"
            )
        return value

    @field_validator("password")
    @classmethod
    def _validate_password(cls, value: str) -> str:
        """Ensure password is non-empty and within COBOL ``PIC X(08)``.

        Matches the CICS behavior in ``COUSR01C.cbl`` which rejected
        the Add attempt with a "Please enter Password..." message when
        ``PASSWDI`` was blank.
        """
        if value is None:
            raise ValueError("password must not be null")
        if not isinstance(value, str):
            raise ValueError(f"password must be a string; got {type(value).__name__}")
        if not value or not value.strip():
            raise ValueError("password must not be empty")
        if len(value) > _PASSWORD_MAX_LEN:
            raise ValueError(
                f"password exceeds max length {_PASSWORD_MAX_LEN} "
                f"(COBOL PIC X({_PASSWORD_MAX_LEN})); got length "
                f"{len(value)}"
            )
        return value

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value is None:
            raise ValueError("user_type must not be null")
        if not isinstance(value, str):
            raise ValueError(f"user_type must be a string; got {type(value).__name__}")
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); "
                f"got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# UserCreateResponse — POST /admin/users success body
# ---------------------------------------------------------------------------
class UserCreateResponse(BaseModel):
    """Success response for the User Add endpoint.

    Echoes the newly-created identity (minus the password) so the client
    can display a confirmation banner without a follow-up GET. Mirrors
    the CICS ``SEND MAP ('COUSR1AO')`` refresh that displayed the
    freshly-written row with a confirmation message.

    Attributes
    ----------
    user_id : str
        The created user's ID. Max 8 characters.
    first_name : str
        First name. Max 20 characters.
    last_name : str
        Last name. Max 20 characters.
    user_type : str
        User role — ``'A'`` (admin) or ``'U'`` (user). 1 character.
    message : Optional[str]
        Optional informational or error message, up to 78 characters —
        maps to COUSR01 ``ERRMSGI`` ``PIC X(78)``. Populated on
        success with a positive confirmation string (e.g. "User added
        successfully.") or ``None``.
    """

    # Pydantic v2 ORM mode — allow construction directly from the
    # newly-INSERTed UserSecurity SQLAlchemy row.
    model_config = ConfigDict(from_attributes=True)

    user_id: str = Field(
        ...,
        max_length=_USER_ID_MAX_LEN,
        description=("Created user's ID — echoes COUSR01 USERIDI PIC X(08) (persisted as user_security.user_id)."),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description=("First name — echoes COUSR01 FNAMEI PIC X(20) (persisted as user_security.first_name)."),
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description=("Last name — echoes COUSR01 LNAMEI PIC X(20) (persisted as user_security.last_name)."),
    )
    user_type: str = Field(
        ...,
        max_length=_USER_TYPE_MAX_LEN,
        description=(
            "User role — echoes COUSR01 USRTYPEI PIC X(01) "
            "(persisted as user_security.usr_type). "
            "'A' = admin, 'U' = user."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=("Optional info/error message, max 78 chars — maps to COUSR01 ERRMSGI PIC X(78)."),
    )

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); "
                f"got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# UserUpdateRequest — PUT /admin/users/{user_id} payload
# ---------------------------------------------------------------------------
class UserUpdateRequest(BaseModel):
    """User Update request — converted from BMS map ``COUSR2AI`` (F-020).

    All four business fields (``FNAMEI``, ``LNAMEI``, ``PASSWDI``,
    ``USRTYPEI``) are *Optional* so callers can perform PATCH-style
    partial updates without having to re-send unchanged values. The
    ``user_id`` target is passed as the URL path parameter
    (``PUT /admin/users/{user_id}``), not in the request body — mirroring
    the CICS ``USRIDINI`` ``PIC X(08)`` field on ``COUSR02.CPY`` which
    was populated from the List screen's selection rather than typed
    fresh on the Update screen.

    Attributes
    ----------
    first_name : Optional[str]
        New first name. ``None`` leaves the stored value unchanged.
        Max 20 characters (COBOL ``FNAMEI`` ``PIC X(20)``).
    last_name : Optional[str]
        New last name. ``None`` leaves the stored value unchanged.
        Max 20 characters (COBOL ``LNAMEI`` ``PIC X(20)``).
    password : Optional[str]
        New cleartext password. ``None`` leaves the stored BCrypt hash
        unchanged. Max 8 characters (COBOL ``PASSWDI`` ``PIC X(08)``).
        When supplied, the service layer BCrypt-hashes the plaintext
        before UPDATEing ``user_security.password``.
    user_type : Optional[str]
        New user role. ``None`` leaves the stored value unchanged.
        When supplied, must be ``'A'`` or ``'U'``.
    """

    first_name: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_FIRST_NAME_MAX_LEN,
        description=(
            "Optional new first name — maps to COUSR02 FNAMEI PIC X(20). None leaves the stored value unchanged."
        ),
    )
    last_name: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_LAST_NAME_MAX_LEN,
        description=(
            "Optional new last name — maps to COUSR02 LNAMEI PIC X(20). None leaves the stored value unchanged."
        ),
    )
    password: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_PASSWORD_MAX_LEN,
        description=(
            "Optional new cleartext password — maps to COUSR02 PASSWDI "
            "PIC X(08). None leaves the stored BCrypt hash unchanged; "
            "when supplied, service layer BCrypt-hashes before update."
        ),
    )
    user_type: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_USER_TYPE_MAX_LEN,
        description=(
            "Optional new user role — maps to COUSR02 USRTYPEI PIC X(01). "
            "Must be 'A' or 'U' when provided. None leaves value unchanged."
        ),
    )

    # -----------------------------------------------------------------
    # Field-level validators — run ONLY when the optional value is
    # supplied (i.e., non-None). Pydantic passes None straight through
    # to a field_validator by default, so each validator explicitly
    # short-circuits on None to preserve PATCH semantics.
    # -----------------------------------------------------------------
    @field_validator("password")
    @classmethod
    def _validate_password(cls, value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """When supplied, enforce non-empty and COBOL ``PIC X(08)`` limit.

        Mirrors the CICS behavior in ``COUSR02C.cbl`` which — when the
        user typed a new value into ``PASSWDI`` — rejected it with a
        "Password cannot be blank" message if it contained only spaces.
        When ``None`` / unset, the service layer leaves the stored
        BCrypt hash unchanged.
        """
        if value is None:
            return value
        if not isinstance(value, str):
            raise ValueError(f"password must be a string; got {type(value).__name__}")
        if not value or not value.strip():
            raise ValueError("password must not be empty when provided")
        if len(value) > _PASSWORD_MAX_LEN:
            raise ValueError(
                f"password exceeds max length {_PASSWORD_MAX_LEN} "
                f"(COBOL PIC X({_PASSWORD_MAX_LEN})); got length "
                f"{len(value)}"
            )
        return value

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """When supplied, enforce the COCOM01Y.cpy 88-level domain {A, U}."""
        if value is None:
            return value
        if not isinstance(value, str):
            raise ValueError(f"user_type must be a string; got {type(value).__name__}")
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); "
                f"got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# UserUpdateResponse — PUT /admin/users/{user_id} success body
# ---------------------------------------------------------------------------
class UserUpdateResponse(BaseModel):
    """Success response for the User Update endpoint.

    Mirrors the CICS ``SEND MAP ('COUSR2AO')`` refresh that redisplayed
    the post-update row with a confirmation message. Like
    :class:`UserCreateResponse`, the password field is intentionally
    omitted from the response envelope.

    Attributes
    ----------
    user_id : str
        The updated user's ID — echoes the URL path parameter.
    first_name : str
        Current (post-update) first name.
    last_name : str
        Current (post-update) last name.
    user_type : str
        Current (post-update) user role — ``'A'`` or ``'U'``.
    message : Optional[str]
        Optional informational or error message, up to 78 characters —
        maps to COUSR02 ``ERRMSGI`` ``PIC X(78)``.
    """

    model_config = ConfigDict(from_attributes=True)

    user_id: str = Field(
        ...,
        max_length=_USER_ID_MAX_LEN,
        description=("Updated user's ID — maps to COUSR02 USRIDINI PIC X(08) (echoes the URL path parameter)."),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description=("Post-update first name — maps to COUSR02 FNAMEI PIC X(20)."),
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description=("Post-update last name — maps to COUSR02 LNAMEI PIC X(20)."),
    )
    user_type: str = Field(
        ...,
        max_length=_USER_TYPE_MAX_LEN,
        description=("Post-update user role — maps to COUSR02 USRTYPEI PIC X(01). 'A' = admin, 'U' = user."),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=("Optional info/error message, max 78 chars — maps to COUSR02 ERRMSGI PIC X(78)."),
    )

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); "
                f"got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# UserDeleteResponse — DELETE /admin/users/{user_id} success body
# ---------------------------------------------------------------------------
class UserDeleteResponse(BaseModel):
    """Success response for the User Delete endpoint.

    Mirrors the CICS ``SEND MAP ('COUSR3AO')`` confirmation refresh that
    displayed the about-to-be-deleted row for visual verification. Unlike
    the Add/Update responses, this envelope has **no password field** —
    ``COUSR03.CPY`` intentionally omits the ``PASSWDI``/``PASSWDO``
    symbolic-map pair because the Delete screen is display-only and
    never surfaces the stored password.

    Attributes
    ----------
    user_id : str
        The deleted user's ID — maps to COUSR03 ``USRIDINI`` PIC X(08).
    first_name : str
        The deleted user's first name — maps to COUSR03 ``FNAMEI``
        PIC X(20). Display-only.
    last_name : str
        The deleted user's last name — maps to COUSR03 ``LNAMEI``
        PIC X(20). Display-only.
    user_type : str
        The deleted user's role — maps to COUSR03 ``USRTYPEI`` PIC
        X(01). Display-only; ``'A'`` or ``'U'``.
    message : Optional[str]
        Optional informational or error message, up to 78 characters —
        maps to COUSR03 ``ERRMSGI`` ``PIC X(78)``. Typically populated
        with a confirmation string (e.g., "User deleted successfully.").
    """

    # Pydantic v2 ORM mode — allow construction from the pre-DELETE
    # UserSecurity snapshot captured by the service layer before
    # removing the row, so the response can display the deleted row's
    # identity fields even though the DB row no longer exists.
    model_config = ConfigDict(from_attributes=True)

    user_id: str = Field(
        ...,
        max_length=_USER_ID_MAX_LEN,
        description=("Deleted user's ID — maps to COUSR03 USRIDINI PIC X(08)."),
    )
    first_name: str = Field(
        ...,
        max_length=_FIRST_NAME_MAX_LEN,
        description=("Deleted user's first name — maps to COUSR03 FNAMEI PIC X(20). Display-only."),
    )
    last_name: str = Field(
        ...,
        max_length=_LAST_NAME_MAX_LEN,
        description=("Deleted user's last name — maps to COUSR03 LNAMEI PIC X(20). Display-only."),
    )
    user_type: str = Field(
        ...,
        max_length=_USER_TYPE_MAX_LEN,
        description=(
            "Deleted user's role — maps to COUSR03 USRTYPEI PIC X(01). Display-only; 'A' = admin, 'U' = user."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=(
            "Optional info/error message, max 78 chars — maps to "
            "COUSR03 ERRMSGI PIC X(78). Typically a confirmation "
            "string such as 'User deleted successfully.'"
        ),
    )

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); "
                f"got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# Public export surface
# ---------------------------------------------------------------------------
# The eight transport schemas are the module's public API. Private
# constants (``_USER_ID_MAX_LEN``, ``_VALID_USER_TYPES``, etc.) are
# implementation details and are NOT re-exported.
__all__: list[str] = [
    "UserListRequest",
    "UserListItem",
    "UserListResponse",
    "UserCreateRequest",
    "UserCreateResponse",
    "UserUpdateRequest",
    "UserUpdateResponse",
    "UserDeleteResponse",
]
