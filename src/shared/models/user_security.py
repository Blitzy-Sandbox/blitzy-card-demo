# ============================================================================
# Source: COBOL copybook CSUSR01Y.cpy — SEC-USER-DATA (80 bytes)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Password field upgraded from COBOL PIC X(08) to BCrypt hash (60 chars).
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
"""SQLAlchemy 2.x ORM model for the ``user_security`` table.

Converts the COBOL copybook ``app/cpy/CSUSR01Y.cpy`` (record layout
``SEC-USER-DATA``, 80-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a user-security / authentication row
in the CardDemo Aurora PostgreSQL database.

COBOL to Python Field Mapping
-----------------------------
================  ==============  ================  =======================
COBOL Field       COBOL Type      Python Column     SQLAlchemy Type
================  ==============  ================  =======================
SEC-USR-ID        ``PIC X(08)``   ``usr_id``        ``String(8)`` — PK
SEC-USR-FNAME     ``PIC X(20)``   ``first_name``    ``String(20)``
SEC-USR-LNAME     ``PIC X(20)``   ``last_name``     ``String(20)``
SEC-USR-PWD       ``PIC X(08)``†  ``password``      ``String(60)`` — BCrypt
SEC-USR-TYPE      ``PIC X(01)``   ``usr_type``      ``String(1)`` — A/U
SEC-USR-FILLER    ``PIC X(23)``   — (not mapped)    — (COBOL padding only)
================  ==============  ================  =======================

† The password column is the **only** field size deviation from the COBOL
  copybook. The original ``PIC X(08)`` 8-byte cleartext field is upgraded
  to a 60-character BCrypt hash column to meet modern security
  requirements. BCrypt hashes are always exactly 60 printable characters
  (the format identifier ``$2b$`` + cost factor + 22-char salt + 31-char
  hash). This upgrade is explicitly required by the Agent Action Plan
  (see AAP §0.7.2 "Security Requirements" — "BCrypt password hashing
  must be maintained for user authentication, matching existing COBOL
  behavior"). All other fields preserve their COBOL widths exactly.

User Type Semantics
-------------------
The ``usr_type`` column replicates the two COBOL 88-level condition names
documented in the original sign-on flow (``COSGN00C.cbl``) and user
administration programs (``COUSR01C.cbl``, ``COUSR02C.cbl``):

* ``'A'`` — Administrator. Grants access to the admin menu (``COADM01C``)
  including user CRUD operations (``COUSR00C``, ``COUSR01C``,
  ``COUSR02C``, ``COUSR03C``).
* ``'U'`` — Regular user. Grants access to the main menu (``COMEN01C``)
  for account / card / transaction / bill-payment / report operations.

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py`` (``src.shared.models``)
  so that this entity registers with the shared
  :class:`~sqlalchemy.MetaData` alongside the other CardDemo models.
* ``__repr__`` deliberately **excludes** the password column. Even though
  the stored value is a BCrypt hash (not the original cleartext), no
  credential-derived material should ever appear in log streams,
  tracebacks, or debugger inspections. This is a defence-in-depth measure
  aligned with AAP §0.7.2 "Security Requirements".
* No floating-point or decimal columns in this entity — the user-security
  record has no monetary fields.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.3 — Feature mapping for F-018/F-019/F-020/F-021 (user admin).
AAP §0.5.1 — File-by-File Transformation Plan (``user_security.py`` entry).
AAP §0.7.2 — Security Requirements (BCrypt hashing, IAM, Secrets Manager).
``app/cpy/CSUSR01Y.cpy`` — Original COBOL record layout (source artifact).
"""

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class UserSecurity(Base):
    """ORM entity for the ``user_security`` table (from COBOL ``SEC-USER-DATA``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``user_security`` table, which replaces the mainframe VSAM KSDS
    ``USRSEC`` dataset. Each row corresponds to one system user (either
    administrator or regular user) authorized to sign on to the CardDemo
    application via the sign-on flow (``COSGN00C``).

    Attributes
    ----------
    usr_id : str
        **Primary key.** 8-character user ID (from COBOL ``SEC-USR-ID``,
        ``PIC X(08)``). Used as the login identifier in the sign-on
        screen (``COSGN00.bms``) and as the logical audit key across the
        application. Preserved at the original COBOL width so that
        existing user records migrated from VSAM remain addressable with
        their historical identifiers.
    first_name : str
        Up to 20-character given name (from COBOL ``SEC-USR-FNAME``,
        ``PIC X(20)``). Displayed in the admin user-list screen
        (``COUSR00``) and in audit trails.
    last_name : str
        Up to 20-character surname (from COBOL ``SEC-USR-LNAME``,
        ``PIC X(20)``). Displayed in the admin user-list screen and
        audit trails.
    password : str
        60-character BCrypt password hash. **Upgraded** from the COBOL
        original ``SEC-USR-PWD`` (``PIC X(08)`` cleartext) to a BCrypt
        digest (``$2b$`` format, 60 chars) to meet modern security
        requirements. The cleartext password is never stored or logged.
        Verified at sign-on via ``passlib.hash.bcrypt.verify()``.
    usr_type : str
        1-character user type code (from COBOL ``SEC-USR-TYPE``,
        ``PIC X(01)``). Values: ``'A'`` = administrator (admin menu
        ``COADM01C``), ``'U'`` = regular user (main menu ``COMEN01C``).
        Controls routing and authorization decisions in the FastAPI auth
        middleware (replacing CICS COMMAREA user-type checks).
    """

    __tablename__ = "user_security"

    # ------------------------------------------------------------------
    # Primary key: 8-character user ID (COBOL ``SEC-USR-ID`` PIC X(08))
    # ------------------------------------------------------------------
    usr_id: Mapped[str] = mapped_column(
        String(8),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # First name (COBOL ``SEC-USR-FNAME`` PIC X(20))
    # ------------------------------------------------------------------
    first_name: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Last name (COBOL ``SEC-USR-LNAME`` PIC X(20))
    # ------------------------------------------------------------------
    last_name: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Password — BCrypt hash (UPGRADED field size: COBOL PIC X(08) → 60).
    #
    # The 60-char width accommodates the full BCrypt digest format:
    #   ``$2b$`` + cost_factor(2) + ``$`` + salt(22) + hash(31) = 60 chars
    #
    # This is the ONLY field whose size differs from the COBOL copybook,
    # explicitly required by AAP §0.7.2 to upgrade from cleartext
    # 8-char passwords to BCrypt hashed storage. Plaintext is never
    # persisted; authentication verifies via ``passlib.hash.bcrypt``.
    # ------------------------------------------------------------------
    password: Mapped[str] = mapped_column(
        String(60),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # User type (COBOL ``SEC-USR-TYPE`` PIC X(01))
    #
    # Values (matches COBOL 88-level conditions in USRSEC copybooks):
    #   'A' — Administrator  (admin menu COADM01C)
    #   'U' — Regular user   (main menu  COMEN01C)
    # ------------------------------------------------------------------
    usr_type: Mapped[str] = mapped_column(
        String(1),
        nullable=False,
    )

    # Note: COBOL ``SEC-USR-FILLER PIC X(23)`` — the trailing 23 bytes of
    # padding in the original 80-byte VSAM record — is deliberately NOT
    # mapped. In the relational model, column widths are explicit and
    # trailing padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        **Security:** the ``password`` column is intentionally omitted
        from this representation. Even though the stored value is a
        BCrypt hash (not cleartext), no credential-derived material
        should ever surface in log streams, tracebacks, or interactive
        debugger output. This mirrors the defence-in-depth posture
        required by AAP §0.7.2.

        Returns
        -------
        str
            Representation of the form
            ``UserSecurity(usr_id='ADMIN001', first_name='ADMIN',
            last_name='USER', usr_type='A')``.
        """
        return (
            f"UserSecurity("
            f"usr_id={self.usr_id!r}, "
            f"first_name={self.first_name!r}, "
            f"last_name={self.last_name!r}, "
            f"usr_type={self.usr_type!r}"
            f")"
        )
