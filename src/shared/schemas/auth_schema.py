# ============================================================================
# Source: COBOL BMS symbolic map COSGN00.CPY (sign-on screen, Feature F-001)
#         + COMMAREA copybook COCOM01Y.cpy (CARDDEMO-COMMAREA, 96 bytes)
# ============================================================================
# Mainframe-to-Cloud migration: CICS COMMAREA session state → stateless JWT.
#
# Replaces:
#   * The BMS sign-on input fields USERIDI PIC X(08) / PASSWDI PIC X(08)
#     previously submitted via CICS RECEIVE MAP ('COSGN0A') in COSGN00C.cbl.
#   * The CICS COMMAREA ``CARDDEMO-COMMAREA`` used to propagate user identity
#     (``CDEMO-USER-ID`` PIC X(08)) and role (``CDEMO-USER-TYPE`` PIC X(01))
#     across CICS program transfers — now encoded as JWT claims.
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
"""Pydantic v2 authentication schemas for the CardDemo REST/GraphQL API.

Converts two COBOL artifacts into four Pydantic v2 request/response models:

* ``app/cpy-bms/COSGN00.CPY`` — the BMS symbolic-map copybook that defines
  the sign-on screen input/output layout (Feature F-001, Sign-On /
  Authentication). Its two business input fields — ``USERIDI`` PIC X(08)
  and ``PASSWDI`` PIC X(08) — become :class:`SignOnRequest`.
* ``app/cpy/COCOM01Y.cpy`` — the CICS COMMAREA communication block that
  carries user identity (``CDEMO-USER-ID``) and role (``CDEMO-USER-TYPE``)
  across CICS program transfers. Its authentication-relevant fields become
  the JWT token payload (:class:`TokenPayload`) and the sign-on response
  (:class:`SignOnResponse`).

COBOL → Python Field Mapping
----------------------------
===============================  ==========  ==================================
COBOL Field                      Py Class    Python Field
===============================  ==========  ==================================
USERIDI ``PIC X(08)``            Request     ``SignOnRequest.user_id``
PASSWDI ``PIC X(08)``            Request     ``SignOnRequest.password``
CDEMO-USER-ID ``PIC X(08)``      Response    ``SignOnResponse.user_id``
CDEMO-USER-TYPE ``PIC X(01)``    Response    ``SignOnResponse.user_type``
ERRMSGI ``PIC X(78)``            Response    ``SignOnResponse.message``
(new)                            Response    ``SignOnResponse.access_token``
(new)                            Response    ``SignOnResponse.token_type``
CDEMO-USER-ID ``PIC X(08)``      JWT claim   ``TokenPayload.sub``
CDEMO-USER-TYPE ``PIC X(01)``    JWT claim   ``TokenPayload.user_type``
(new — JWT standard)             JWT claim   ``TokenPayload.exp``
(new)                            Response    ``SignOutResponse.message``
===============================  ==========  ==================================

User Type Semantics (COCOM01Y.cpy 88-level conditions)
------------------------------------------------------
* ``'A'`` — Administrator (``CDEMO-USRTYP-ADMIN``). Grants access to admin
  menu (``COADM01C``) including user CRUD (``COUSR0{0,1,2,3}C``).
* ``'U'`` — Regular user (``CDEMO-USRTYP-USER``). Grants access to main
  menu (``COMEN01C``) for account/card/transaction/bill-payment/report
  operations.

Design Notes
------------
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length/default constraints and
  :func:`~pydantic.field_validator` for business-rule validation.
* **Password handling** — ``SignOnRequest.password`` is accepted as
  cleartext (max 8 chars from COBOL ``PIC X(08)``) over HTTPS. The
  service layer performs BCrypt verification against the stored hash in
  the ``user_security`` table (see ``src/shared/models/user_security.py``
  and AAP §0.7.2 "Security Requirements"). The schema never stores or
  returns the password.
* **No ``ConfigDict(from_attributes=True)``** — these are transport-layer
  schemas, not ORM-derived models. JWT payloads are dict-encoded; sign-on
  responses are assembled explicitly by the auth service.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI/Uvicorn container image).
"""

from typing import Optional

from pydantic import BaseModel, Field, field_validator

# ---------------------------------------------------------------------------
# Constants — COBOL 88-level values from COCOM01Y.cpy
# ---------------------------------------------------------------------------
# Keeping these as private module constants rather than a public Enum keeps
# the transport schemas lightweight; the auth service interprets them.
_USER_TYPE_ADMIN: str = "A"  # CDEMO-USRTYP-ADMIN (88-level)
_USER_TYPE_USER: str = "U"  # CDEMO-USRTYP-USER  (88-level)
_VALID_USER_TYPES: frozenset[str] = frozenset({_USER_TYPE_ADMIN, _USER_TYPE_USER})


class SignOnRequest(BaseModel):
    """Sign-on (login) request — converted from BMS map ``COSGN0AI``.

    The two business-input fields from ``app/cpy-bms/COSGN00.CPY`` —
    ``USERIDI`` PIC X(08) and ``PASSWDI`` PIC X(08) — map to exactly two
    request fields. All other COSGN00 symbolic-map fields (``APPLIDI``,
    ``SYSIDI``, ``TRNNAMEI``, ``TITLE01I``, ``CURDATEI``, ``PGMNAMEI``,
    ``CURTIMEI``, ``TITLE02I``) are display-only screen decoration and
    are intentionally NOT part of the API request contract.

    Attributes
    ----------
    user_id : str
        Login identifier. Max 8 characters (COBOL ``PIC X(08)`` constraint
        from the original ``USERIDI`` field). Must be non-empty.
    password : str
        Cleartext password submitted over HTTPS. Max 8 characters (COBOL
        ``PIC X(08)`` constraint from the original ``PASSWDI`` field).
        Must be non-empty. Verified against a BCrypt hash by the auth
        service; never stored as-is.
    """

    user_id: str = Field(
        ...,
        max_length=8,
        description="User ID credential — from COSGN00 USERIDI PIC X(08).",
    )
    password: str = Field(
        ...,
        max_length=8,
        description=(
            "Password credential — from COSGN00 PASSWDI PIC X(08). "
            "Submitted as cleartext over HTTPS; BCrypt-verified by the "
            "auth service."
        ),
    )

    @field_validator("user_id")
    @classmethod
    def _validate_user_id(cls, value: str) -> str:
        """Ensure user_id is non-empty and within the COBOL PIC X(08) limit.

        Matches the CICS behavior in ``COSGN00C.cbl`` which rejects a
        sign-on attempt with the message ``"Please enter User ID ..."``
        (from ``CSMSG01Y.cpy``) when ``USERIDI`` is blank or zero-length.
        """
        if value is None:
            raise ValueError("user_id must not be null")
        # Preserve the original value (do not strip) — the service layer
        # compares against the stored SEC-USR-ID which is PIC X(08) and
        # may be right-padded with spaces; stripping here would alter
        # what the service sees.
        if not value or not value.strip():
            raise ValueError("user_id must not be empty")
        if len(value) > 8:
            raise ValueError(
                "user_id exceeds max length 8 (COBOL PIC X(08))"
            )
        return value

    @field_validator("password")
    @classmethod
    def _validate_password(cls, value: str) -> str:
        """Ensure password is non-empty and within the COBOL PIC X(08) limit.

        Matches the CICS behavior in ``COSGN00C.cbl`` which rejects a
        sign-on attempt when ``PASSWDI`` is blank.
        """
        if value is None:
            raise ValueError("password must not be null")
        if not value or not value.strip():
            raise ValueError("password must not be empty")
        if len(value) > 8:
            raise ValueError(
                "password exceeds max length 8 (COBOL PIC X(08))"
            )
        return value


class SignOnResponse(BaseModel):
    """Sign-on (login) success response containing a JWT access token.

    Replaces the CICS ``SEND MAP ('COSGN0AO')`` + COMMAREA population
    that previously closed the sign-on transaction. The JWT encodes the
    ``CDEMO-USER-ID`` and ``CDEMO-USER-TYPE`` fields from COCOM01Y.cpy
    (see :class:`TokenPayload`) so that subsequent REST/GraphQL calls
    can be authenticated statelessly — no server-side session is needed.

    Attributes
    ----------
    access_token : str
        JWT token string (HS256-signed). The signing secret is loaded
        from AWS Secrets Manager in production and from the
        ``JWT_SECRET_KEY`` environment variable in local development.
    token_type : str
        OAuth2-compliant token type; always ``"bearer"``.
    user_id : str
        Authenticated user's ID — mirrors ``CDEMO-USER-ID`` PIC X(08).
        Returned for client-side display convenience (e.g., "Welcome,
        USER0001"); the authoritative identity lives in the JWT.
    user_type : str
        Authenticated user's role — mirrors ``CDEMO-USER-TYPE`` PIC X(01).
        Either ``'A'`` (admin) or ``'U'`` (user).
    message : Optional[str]
        Optional status message (max 78 chars) — mirrors COSGN00
        ``ERRMSGI`` PIC X(78). Populated for informational messages on
        successful sign-on; error responses use HTTP status codes +
        structured error envelopes rather than this field.
    """

    access_token: str = Field(
        ...,
        description="JWT bearer token (HS256-signed).",
    )
    token_type: str = Field(
        default="bearer",
        description="OAuth2 token type; always 'bearer'.",
    )
    user_id: str = Field(
        ...,
        max_length=8,
        description="Authenticated user ID — from CDEMO-USER-ID PIC X(08).",
    )
    user_type: str = Field(
        ...,
        max_length=1,
        description=(
            "User role — from CDEMO-USER-TYPE PIC X(01). "
            "'A' = admin, 'U' = user."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        max_length=78,
        description=(
            "Optional status message — maps to COSGN00 ERRMSGI PIC X(78)."
        ),
    )

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); got {value!r}"
            )
        return value


class TokenPayload(BaseModel):
    """JWT token payload — internal schema for decoded JWT claims.

    Used by the auth middleware (``src/api/middleware/auth.py``) to
    validate incoming bearer tokens and extract user context. Not
    directly exposed to API consumers.

    Maps the three authentication-relevant COMMAREA fields from
    ``app/cpy/COCOM01Y.cpy`` plus the standard JWT ``exp`` claim:

    * ``sub``       — JWT standard ``subject`` claim = ``CDEMO-USER-ID``
    * ``user_type`` — custom claim         = ``CDEMO-USER-TYPE``
    * ``exp``       — JWT standard expiration (epoch seconds)

    Other COMMAREA fields (``CDEMO-CUST-ID``, ``CDEMO-ACCT-ID``,
    ``CDEMO-CARD-NUM``, navigation breadcrumbs, customer name) are NOT
    carried in the JWT — they are request-scoped data and are queried
    per-request from Aurora PostgreSQL.

    Attributes
    ----------
    sub : str
        JWT subject — the authenticated user's ID. Max 8 characters
        (from ``CDEMO-USER-ID`` PIC X(08)).
    user_type : str
        User role — either ``'A'`` (admin) or ``'U'`` (user). Matches
        the ``CDEMO-USER-TYPE`` PIC X(01) 88-level conditions.
    exp : Optional[int]
        Expiration timestamp in epoch seconds (JWT standard ``exp``
        claim, RFC 7519 §4.1.4). Optional for schema-only use (e.g.,
        test fixtures); always populated by ``jose.jwt.encode``.
    """

    sub: str = Field(
        ...,
        max_length=8,
        description="JWT subject = user ID (from CDEMO-USER-ID PIC X(08)).",
    )
    user_type: str = Field(
        ...,
        max_length=1,
        description=(
            "User role — 'A' (admin) or 'U' (user); from "
            "CDEMO-USER-TYPE PIC X(01)."
        ),
    )
    exp: Optional[int] = Field(  # noqa: UP045  # schema requires `typing.Optional`
        default=None,
        description=(
            "Token expiration (epoch seconds) — JWT standard 'exp' claim "
            "per RFC 7519 §4.1.4."
        ),
    )

    @field_validator("sub")
    @classmethod
    def _validate_sub(cls, value: str) -> str:
        """Ensure the JWT subject (user ID) is present and bounded."""
        if value is None or not value.strip():
            raise ValueError("sub (user_id) must not be empty")
        if len(value) > 8:
            raise ValueError(
                "sub exceeds max length 8 (COBOL CDEMO-USER-ID PIC X(08))"
            )
        return value

    @field_validator("user_type")
    @classmethod
    def _validate_user_type(cls, value: str) -> str:
        """Enforce the COCOM01Y.cpy 88-level constraint: user_type ∈ {A, U}."""
        if value not in _VALID_USER_TYPES:
            raise ValueError(
                f"user_type must be one of {sorted(_VALID_USER_TYPES)} "
                f"(COCOM01Y.cpy 88-level: 'A'=admin, 'U'=user); got {value!r}"
            )
        return value


class SignOutResponse(BaseModel):
    """Sign-out (logout) response — confirmation payload.

    Stateless JWT authentication has no server-side session to
    invalidate by default; logout is a client-side operation (discard
    the token). This response exists purely to provide a consistent
    confirmation envelope for the ``POST /auth/logout`` endpoint.

    Attributes
    ----------
    message : str
        Logout confirmation message (e.g., ``"Signed out successfully"``).
    """

    message: str = Field(
        ...,
        description="Logout confirmation message.",
    )
