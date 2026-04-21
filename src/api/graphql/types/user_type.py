# ============================================================================
# Source: COBOL copybook CSUSR01Y.cpy — SEC-USER-DATA (80 bytes)
#         BMS symbolic map COUSR00.CPY — User list screen
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
"""Strawberry GraphQL type for the User Security entity.

Source: COBOL copybook ``app/cpy/CSUSR01Y.cpy`` — ``SEC-USER-DATA``
(80 bytes fixed-length record layout).
BMS symbolic map: ``app/cpy-bms/COUSR00.CPY`` — User list screen
(10 repeated row groups, each containing user ID, first name, last
name, and user type — **no password field**).

Mainframe-to-Cloud migration: VSAM KSDS ``USRSEC`` → Aurora PostgreSQL
``user_security`` table → GraphQL API via Strawberry.

COBOL to GraphQL Field Mapping
------------------------------
================  ==============  ================  =======================
COBOL Field       COBOL Type      GraphQL Field     Python Type
================  ==============  ================  =======================
SEC-USR-ID        ``PIC X(08)``   ``usr_id``        ``str``
SEC-USR-FNAME     ``PIC X(20)``   ``first_name``    ``str``
SEC-USR-LNAME     ``PIC X(20)``   ``last_name``     ``str``
SEC-USR-PWD       ``PIC X(08)``   — (OMITTED)       — (security)
SEC-USR-TYPE      ``PIC X(01)``   ``usr_type``      ``str``
SEC-USR-FILLER    ``PIC X(23)``   — (not mapped)    — (COBOL padding)
================  ==============  ================  =======================

CRITICAL SECURITY: Password Field Deliberately Omitted
------------------------------------------------------
The COBOL ``SEC-USR-PWD`` field (``PIC X(08)``) is **deliberately and
permanently** excluded from this GraphQL type. Three reinforcing reasons:

1. **Aurora PostgreSQL storage is a BCrypt digest, not cleartext.** The
   SQLAlchemy ``UserSecurity.password`` column (see
   ``src.shared.models.user_security``) stores a 60-character BCrypt
   hash, not the original 8-character COBOL cleartext. Even though the
   stored value is a hash, exposing it via the API would permit offline
   dictionary attacks. The hash MUST remain server-side.

2. **The COBOL user list screen (COUSR00.CPY) already hides the
   password.** The BMS symbolic map for the user list defines 10
   repeated row groups as ``SELnn PIC X(1)``, ``USRIDnn PIC X(8)``,
   ``FNAMEnn PIC X(20)``, ``LNAMEnn PIC X(20)``, ``UTYPEnn PIC X(1)``
   — i.e., the user's ID, first name, last name, and type, but NOT
   the password. This GraphQL type preserves that existing security
   posture exactly (per the AAP §0.7.1 "preserve existing functionality
   exactly as-is" rule).

3. **Defence-in-depth per AAP §0.7.2.** The Agent Action Plan
   "Security Requirements" mandate that password material never surface
   on any external interface. Since Strawberry auto-generates the
   GraphQL schema by introspecting the decorated class, simply not
   declaring the field is sufficient to exclude it from both the
   public schema, the introspection endpoint, and any generated client
   code. The ``from_model()`` factory reinforces this by explicitly
   NOT reading the ``password`` attribute from the SQLAlchemy model —
   the hash never crosses the ORM/API boundary.

User Type Semantics
-------------------
The ``usr_type`` field replicates the two COBOL 88-level condition
names from ``COCOM01Y.cpy`` (CICS COMMAREA) and the sign-on /
user-administration programs:

* ``'A'`` — **Administrator**. In CICS, this user is routed by
  ``COSGN00C`` to the admin menu program ``COADM01C``, gaining access
  to user CRUD transactions (``COUSR00C``, ``COUSR01C``, ``COUSR02C``,
  ``COUSR03C``). In the cloud architecture, the JWT issued at sign-on
  carries this value as a claim, and the admin endpoints check for it.
* ``'U'`` — **Regular user**. Routed by ``COSGN00C`` to the main menu
  program ``COMEN01C``, with access to account view/update, card
  view/update, transaction operations, bill payment, and reports.

These two values MUST be preserved exactly — any deviation from
single-character ``'A'`` or ``'U'`` would break compatibility with the
seed data migrated from VSAM via the Flyway V3 migration and with the
COBOL ``IF SEC-USER-TYPE = 'A' ...`` conditionals preserved in the
Python authorization middleware.

Design Notes
------------
* **No monetary fields.** The user-security record has no ``PIC S9(n)V99``
  fields, so this type has no ``Decimal`` columns. The strict
  "no floating-point arithmetic for financial values" rule from the AAP
  does not apply here, but the type follows the same discipline by
  using only ``str`` for every field (no floats, no integers, no
  implicit coercions).
* **No FILLER mapping.** The trailing 23 bytes of COBOL padding
  (``SEC-USR-FILLER PIC X(23)``) have no storage or semantic meaning
  in the relational model and are therefore not mapped.
* **snake_case field names** match the SQLAlchemy model column names
  (``first_name``, ``last_name``, ``usr_type``) and the Aurora
  PostgreSQL DDL column names. The GraphQL schema will surface these
  as ``firstName`` / ``lastName`` / ``usrType`` after Strawberry's
  default ``snake_case → camelCase`` name transformation — a
  convention standard in GraphQL clients.
* **Python 3.11+** only. Uses PEP 585 generic collection types and is
  aligned with the FastAPI / ECS Fargate runtime (``python:3.11-slim``
  base image) and the shared CardDemo source tree.

See Also
--------
* AAP §0.7.1 — Refactoring-Specific Rules (preserve business logic).
* AAP §0.7.2 — Security Requirements (password never exposed).
* ``src.shared.models.user_security.UserSecurity`` — SQLAlchemy model
  (the source of data for ``UserType.from_model()``).
* ``app/cpy/CSUSR01Y.cpy`` — Original COBOL record layout.
* ``app/cpy-bms/COUSR00.CPY`` — BMS symbolic map confirming the
  no-password display contract.
* ``src.api.graphql.queries.Query.user`` / ``Query.users`` — The query
  resolvers that return instances of this type.
"""

# ----------------------------------------------------------------------------
# Imports
# ----------------------------------------------------------------------------
# Strawberry GraphQL — provides the @strawberry.type decorator that
# converts a Python class into a GraphQL schema type. The class body's
# type annotations (``usr_id: str``, ``first_name: str``, ...) become
# GraphQL schema fields; no runtime validation of field TYPE occurs, but
# the GraphQL introspection schema is generated from them exactly.
import strawberry

# UserSecurity — the SQLAlchemy 2.x ORM model representing a single row
# of the Aurora PostgreSQL ``user_security`` table (the relational
# successor to the VSAM ``USRSEC`` KSDS dataset). Used as the parameter
# type annotation for the ``from_model`` static factory below. The
# factory reads exactly four attributes from this model (``user_id``,
# ``first_name``, ``last_name``, ``usr_type``) and deliberately does
# NOT read the ``password`` attribute — the BCrypt hash never crosses
# the ORM/GraphQL boundary.
from src.shared.models.user_security import UserSecurity


# ----------------------------------------------------------------------------
# UserType — Strawberry GraphQL type for SEC-USER-DATA
# ----------------------------------------------------------------------------
@strawberry.type
class UserType:
    """GraphQL type representing a user-security record.

    Maps COBOL ``SEC-USER-DATA`` (``app/cpy/CSUSR01Y.cpy``, 80 bytes)
    to a Strawberry GraphQL schema type consumed by the ``user`` /
    ``users`` query resolvers in ``src.api.graphql.queries`` and the
    user administration mutations in ``src.api.graphql.mutations``.

    **SECURITY — Password field deliberately omitted.** The COBOL
    ``SEC-USR-PWD`` (``PIC X(08)``) field is intentionally NOT declared
    on this class. The Strawberry schema generator derives GraphQL
    fields from annotated class attributes, so omitting the attribute
    is equivalent to making the field impossible to query — it will not
    appear in the introspection schema, cannot be selected in a GraphQL
    query, and cannot be returned in any response. This matches the
    behavior of the original COBOL user-list screen (``COUSR00.CPY``),
    which never displayed the password.

    Attributes
    ----------
    usr_id : str
        8-character user ID. Maps to COBOL ``SEC-USR-ID`` (``PIC X(08)``)
        and to the SQLAlchemy ``UserSecurity.user_id`` primary-key
        column. This is the login identifier entered on the sign-on
        screen (``COSGN00.bms``) and the logical audit key across every
        CardDemo transaction. The GraphQL field name
        (``usr_id``/``usrId``) preserves the COBOL naming convention
        (``SEC-USR-ID`` → ``usr_id``), while the underlying model
        column name is the longer ``user_id``; the factory method
        below performs the one-line attribute rename so the GraphQL
        surface matches the CardDemo user-interface heritage.
    first_name : str
        Up to 20 characters of given name. Maps to COBOL
        ``SEC-USR-FNAME`` (``PIC X(20)``) and the
        ``UserSecurity.first_name`` column. Displayed in the COBOL
        admin user-list screen (``COUSR00.CPY``, ``FNAMEnn PIC X(20)``)
        and in audit trails.
    last_name : str
        Up to 20 characters of surname. Maps to COBOL ``SEC-USR-LNAME``
        (``PIC X(20)``) and the ``UserSecurity.last_name`` column.
        Displayed in the COBOL admin user-list screen
        (``COUSR00.CPY``, ``LNAMEnn PIC X(20)``).
    usr_type : str
        1-character user-type code. Maps to COBOL ``SEC-USR-TYPE``
        (``PIC X(01)``) and the ``UserSecurity.usr_type`` column.
        Values (from COBOL 88-level conditions in ``COCOM01Y.cpy``):

        * ``'A'`` — administrator (admin menu ``COADM01C``).
        * ``'U'`` — regular user (main menu ``COMEN01C``).

        Controls routing and authorization decisions in the FastAPI
        auth middleware and the CardDemo JWT claim set (replacing the
        CICS COMMAREA user-type propagation).
    """

    # ------------------------------------------------------------------
    # usr_id — 8-char user ID, COBOL SEC-USR-ID PIC X(08).
    # GraphQL PK; unique per user. Corresponds to
    # ``UserSecurity.user_id`` (note the model uses the fully spelled
    # form ``user_id``; this GraphQL field preserves the 3-letter
    # COBOL abbreviation ``usr_id`` that also appears in the BMS
    # symbolic map ``COUSR00.CPY`` as ``USRIDnn``).
    # ------------------------------------------------------------------
    usr_id: str

    # ------------------------------------------------------------------
    # first_name — up to 20 chars, COBOL SEC-USR-FNAME PIC X(20).
    # ------------------------------------------------------------------
    first_name: str

    # ------------------------------------------------------------------
    # last_name — up to 20 chars, COBOL SEC-USR-LNAME PIC X(20).
    # ------------------------------------------------------------------
    last_name: str

    # ------------------------------------------------------------------
    # usr_type — 1 char, COBOL SEC-USR-TYPE PIC X(01).
    # 'A' = admin, 'U' = regular user (COBOL 88-level conditions in
    # COCOM01Y.cpy). These two exact characters must be preserved
    # across the ORM, the GraphQL API, the REST API, and the seed
    # data migrated from VSAM — authorization logic relies on the
    # literal equality ``usr_type == 'A'``.
    # ------------------------------------------------------------------
    usr_type: str

    # ------------------------------------------------------------------
    # NOTE (intentional omission, do not remove this comment):
    #
    # The COBOL ``SEC-USR-PWD`` field (``PIC X(08)``) is NOT declared
    # as a GraphQL field on this type. The SQLAlchemy
    # ``UserSecurity.password`` column stores a 60-character BCrypt
    # digest, but even the hash MUST NOT be returned to GraphQL
    # clients — exposing the hash would permit offline attacks. This
    # omission is a security requirement explicitly mandated by
    # AAP §0.7.2 and corroborated by the BMS symbolic map
    # ``COUSR00.CPY``, which did NOT display the password on the
    # user-list screen. See the module docstring for details.
    #
    # Similarly, the COBOL ``SEC-USR-FILLER`` field (``PIC X(23)``) —
    # the trailing 23 bytes of padding in the original 80-byte VSAM
    # record — has no storage or semantic meaning in the relational
    # model and is therefore not declared here.
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # from_model — factory method mapping UserSecurity → UserType.
    # ------------------------------------------------------------------
    @staticmethod
    def from_model(user: UserSecurity) -> "UserType":
        """Convert a SQLAlchemy :class:`UserSecurity` row to a GraphQL :class:`UserType`.

        This factory is the single allowed conversion point from the
        ORM layer to the GraphQL layer for user records. It is
        deliberately written to read **exactly four** attributes from
        the input ``UserSecurity`` instance — ``user_id``,
        ``first_name``, ``last_name``, and ``usr_type`` — and to
        deliberately NOT read the ``password`` attribute. The BCrypt
        hash therefore never enters the GraphQL response path, even
        transiently, matching the defence-in-depth posture mandated
        by AAP §0.7.2 and the COBOL user-list screen behavior
        documented in ``COUSR00.CPY``.

        The factory also performs the one necessary column-name
        translation: the ORM model uses the fully spelled form
        ``user_id`` (to match the V1 DDL migration), while the GraphQL
        field uses the COBOL 3-letter abbreviation ``usr_id`` (to match
        ``SEC-USR-ID`` in ``CSUSR01Y.cpy`` and ``USRIDnn`` in
        ``COUSR00.CPY``). This is the ONLY field where the model
        attribute name and the GraphQL field name differ; all other
        fields (``first_name``, ``last_name``, ``usr_type``) have
        identical names on both sides of the boundary.

        Parameters
        ----------
        user : UserSecurity
            A SQLAlchemy ORM row fetched from the ``user_security``
            table. The caller is responsible for ensuring the row is
            not ``None``; this factory does not perform null checks
            (query resolvers should return ``None`` directly when the
            user is not found, without invoking this factory).

        Returns
        -------
        UserType
            A newly constructed :class:`UserType` instance containing
            exactly the four fields that are safe to expose via
            GraphQL. The returned instance is a plain Strawberry
            type — it has no database session reference and may be
            safely returned from an async resolver without the
            "detached ORM row" pitfalls that SQLAlchemy would
            otherwise enforce.

        Notes
        -----
        Password exclusion verified by inspection — the implementation
        does NOT reference ``user.password``. If a future change
        requires any password-related behavior (e.g., last-rotation
        timestamp exposure), a new dedicated type should be created
        rather than relaxing this factory; the BCrypt hash itself
        must never cross this boundary.
        """
        # ---------------------------------------------------------
        # Explicit field-by-field copy. Do NOT rewrite this as an
        # ``__dict__`` splat or a ``**vars(user)`` expansion — those
        # idioms would inadvertently copy the password attribute into
        # the returned dict-like, violating the security contract
        # described in the module docstring. Explicit is safer.
        # ---------------------------------------------------------
        return UserType(
            # COBOL SEC-USR-ID (PIC X(08)) — model column is user_id,
            # GraphQL field is usr_id (COBOL abbreviation).
            usr_id=user.user_id,
            # COBOL SEC-USR-FNAME (PIC X(20)) — same name on both sides.
            first_name=user.first_name,
            # COBOL SEC-USR-LNAME (PIC X(20)) — same name on both sides.
            last_name=user.last_name,
            # COBOL SEC-USR-TYPE (PIC X(01)) — same name on both sides.
            # Values: 'A' (admin) or 'U' (regular user).
            usr_type=user.usr_type,
            # NOTE: user.password is INTENTIONALLY not accessed here.
            # The BCrypt hash never crosses the ORM/GraphQL boundary.
        )
