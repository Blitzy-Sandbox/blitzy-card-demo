# ============================================================================
# Source: COBOL copybook CSUSR01Y.cpy — SEC-USER-DATA (80 bytes, VSAM KSDS)
# ============================================================================
# Password field upgraded from COBOL PIC X(08) plaintext to BCrypt hash
# (60 chars). This is the ONLY field size deviation between the COBOL
# copybook and the Python SQLAlchemy model (AAP §0.7.2 Security
# Requirements — "BCrypt password hashing must be maintained for user
# authentication").
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
"""Unit tests for the :class:`UserSecurity` SQLAlchemy ORM model.

Validates the translation of the COBOL copybook
``app/cpy/CSUSR01Y.cpy`` (record layout ``SEC-USER-DATA``, an 80-byte
VSAM KSDS record with an 8-byte key) into the SQLAlchemy 2.x
declarative ORM model at ``src/shared/models/user_security.py``.

COBOL Source Layout (``CSUSR01Y.cpy``)
--------------------------------------
::

    01 SEC-USER-DATA.
      05 SEC-USR-ID                 PIC X(08).
      05 SEC-USR-FNAME              PIC X(20).
      05 SEC-USR-LNAME              PIC X(20).
      05 SEC-USR-PWD                PIC X(08).       <-- UPGRADED to String(60)
      05 SEC-USR-TYPE               PIC X(01).
      05 SEC-USR-FILLER             PIC X(23).       <-- NOT mapped (padding)

COBOL -> Python Field Mapping
-----------------------------
============  ==============  ============  ====================
COBOL Field   COBOL Type      Python Attr   SQLAlchemy Type
============  ==============  ============  ====================
SEC-USR-ID    ``PIC X(08)``   user_id       ``String(8)`` (PK)
SEC-USR-FNAME ``PIC X(20)``   first_name    ``String(20)``
SEC-USR-LNAME ``PIC X(20)``   last_name     ``String(20)``
SEC-USR-PWD   ``PIC X(08)``†  password      ``String(60)`` (BCrypt)
SEC-USR-TYPE  ``PIC X(01)``   usr_type      ``String(1)``
SEC-USR-FILLER``PIC X(23)``   (not mapped)  (COBOL padding only)
============  ==============  ============  ====================

† The ``password`` column is the **only** field whose width differs from
the COBOL copybook. The original 8-byte cleartext ``SEC-USR-PWD`` is
upgraded to a 60-character BCrypt hash column (format
``$2b$<cost>$<22-char-salt><31-char-hash>``) to satisfy AAP §0.7.2
"Security Requirements" — "BCrypt password hashing must be maintained
for user authentication". All other fields preserve their original
COBOL widths exactly.

Note on COBOL-vs-Python Naming
------------------------------
The test *function* names below use the COBOL-style short form
``usr_id`` (matching the COBOL ``SEC-USR-ID`` field and the file
schema's export-name contract) while the test *bodies* reference the
actual Python attribute ``user_id`` declared on the ORM model. The
test-function-name-to-column mapping is therefore:

* ``test_usr_id_type``      -> column ``user_id`` (from ``SEC-USR-ID``)
* ``test_first_name_type``  -> column ``first_name`` (from ``SEC-USR-FNAME``)
* ``test_last_name_type``   -> column ``last_name`` (from ``SEC-USR-LNAME``)
* ``test_password_type``    -> column ``password`` (from ``SEC-USR-PWD``)
* ``test_usr_type_type``    -> column ``usr_type`` (from ``SEC-USR-TYPE``)

User Type Semantics
-------------------
The ``usr_type`` 1-character code replicates the two COBOL 88-level
condition names used in the sign-on flow (``COSGN00C.cbl``) and user
administration programs (``COUSR01C.cbl``, ``COUSR02C.cbl``):

* ``'A'`` — Administrator (admin menu ``COADM01C``, user CRUD ops)
* ``'U'`` — Regular user  (main menu  ``COMEN01C``)

Test Coverage (16 functions)
----------------------------
1.  :func:`test_tablename`                       — ``__tablename__`` contract.
2.  :func:`test_column_count`                    — Exactly 5 mapped columns.
3.  :func:`test_primary_key_usr_id`              — ``user_id`` is the sole PK.
4.  :func:`test_usr_id_type`                     — ``user_id`` is ``String(8)``.
5.  :func:`test_first_name_type`                 — ``first_name`` is ``String(20)``.
6.  :func:`test_last_name_type`                  — ``last_name`` is ``String(20)``.
7.  :func:`test_password_type`                   — ``password`` is ``String(60)``.
8.  :func:`test_usr_type_type`                   — ``usr_type`` is ``String(1)``.
9.  :func:`test_admin_user_type`                 — ``usr_type='A'`` (admin).
10. :func:`test_regular_user_type`               — ``usr_type='U'`` (user).
11. :func:`test_password_length_for_bcrypt`      — BCrypt hash width.
12. :func:`test_password_not_in_repr`            — Credential leakage guard.
13. :func:`test_non_nullable_fields`             — NOT NULL on every column.
14. :func:`test_create_user_security_instance`   — Full-instance construction.
15. :func:`test_user_security_repr`              — ``__repr__`` format.
16. :func:`test_no_filler_columns`               — FILLER is NOT mapped.

See Also
--------
``src/shared/models/user_security.py``  — The ORM model under test.
``app/cpy/CSUSR01Y.cpy``                — Original COBOL record layout.
AAP §0.5.1                              — File-by-File Transformation Plan.
AAP §0.7.2                              — Security Requirements (BCrypt).
AAP §0.7.1                              — Minimal-change clause (preserve
                                          COBOL field widths exactly except
                                          for the documented BCrypt upgrade).
``tests.unit.test_models.__init__``     — Package docstring listing the
                                          full model-to-copybook mapping.
"""

from __future__ import annotations

import pytest
from sqlalchemy import String, inspect

from src.shared.models import Base
from src.shared.models.user_security import UserSecurity

# ============================================================================
# Module-level constants shared by multiple tests.
# ============================================================================
#
# BCrypt hash structure: "$2b$" + cost(2) + "$" + salt(22) + hash(31) = 60.
# This is a real BCrypt digest (cost factor 12) of the throwaway string
# "testpassword" — safe to hard-code in tests because it is never stored
# outside this module and corresponds to no real credential. Using a
# genuine BCrypt hash (rather than a hand-crafted placeholder) keeps the
# test data realistic for repr-leak detection and matches the exact byte
# layout that production code stores via ``passlib.hash.bcrypt.hash()``.
_BCRYPT_SENTINEL_HASH: str = "$2b$12$7tBLiq0eAAy9gy5cDa5oUeB1V7gF7fLjfF19sON0PEHarNIpr3Mbq"

# Set of the 5 expected mapped column names (Python attribute names,
# also the SQL column names under SQLAlchemy's default resolution).
# ``SEC-USR-FILLER`` from the copybook is DELIBERATELY absent — COBOL
# padding has no place in the relational model.
_EXPECTED_COLUMNS: frozenset[str] = frozenset(
    {
        "user_id",  # from SEC-USR-ID    PIC X(08)
        "first_name",  # from SEC-USR-FNAME PIC X(20)
        "last_name",  # from SEC-USR-LNAME PIC X(20)
        "password",  # from SEC-USR-PWD   PIC X(08) — UPGRADED to String(60)
        "usr_type",  # from SEC-USR-TYPE  PIC X(01)
    }
)


# ============================================================================
# Phase 2: Table & Column Metadata Tests
# ============================================================================


@pytest.mark.unit
def test_tablename() -> None:
    """UserSecurity must be mapped to the ``user_security`` table.

    The table name is the relational anchor that ties the ORM model to:

    * ``db/migrations/V1__schema.sql`` — ``CREATE TABLE user_security``
    * ``db/migrations/V3__seed_data.sql`` — ``INSERT INTO user_security``
    * The batch (F-013..F-017) and online (F-001, F-018..F-021) features
      that perform SELECT / INSERT / UPDATE / DELETE against this table.

    Any drift between ``UserSecurity.__tablename__`` and the DDL /
    seed-data contract would cause runtime ``UndefinedTable`` errors, so
    this invariant is pinned.
    """
    assert UserSecurity.__tablename__ == "user_security", (
        "UserSecurity.__tablename__ must be 'user_security' to match db/migrations/V1__schema.sql and V3__seed_data.sql"
    )


@pytest.mark.unit
def test_column_count() -> None:
    """UserSecurity must expose exactly 5 mapped columns.

    The COBOL ``SEC-USER-DATA`` layout has 6 fields, but only 5 are
    mapped to the relational model. ``SEC-USR-FILLER PIC X(23)`` is
    deliberately dropped because trailing padding has no storage or
    semantic meaning in a column-typed schema.

    Ensuring the count is exactly 5 guards against two regressions:

    * An accidental ``filler`` column being added back (increases the
      count to 6).
    * A field being accidentally removed from the model (decreases the
      count below 5).
    """
    columns = UserSecurity.__table__.columns
    assert len(columns) == 5, (
        f"UserSecurity must have exactly 5 columns (SEC-USR-ID, "
        f"SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD, SEC-USR-TYPE); "
        f"found {len(columns)}: {[c.name for c in columns]}"
    )


@pytest.mark.unit
def test_primary_key_usr_id() -> None:
    """The sole primary key is ``user_id`` (from COBOL SEC-USR-ID).

    Maps to VSAM KSDS primary key slot (offset 0, length 8) of the
    ``USRSEC`` dataset. Replaces the mainframe VSAM ``DEFINE CLUSTER
    KEYS(8 0)`` clause from ``app/jcl/DUSRSECJ.jcl``.

    Verifies both that:

    * :class:`sqlalchemy.inspect` reports ``user_id`` as the (single)
      primary key column.
    * The PK column's type is ``String(8)`` — matching the COBOL
      ``PIC X(08)`` original width.
    """
    primary_keys = list(inspect(UserSecurity).primary_key)

    # Exactly one PK column (no composite key for UserSecurity).
    assert len(primary_keys) == 1, (
        f"UserSecurity must have exactly one primary key column "
        f"(SEC-USR-ID); found {len(primary_keys)}: "
        f"{[pk.name for pk in primary_keys]}"
    )

    pk_column = primary_keys[0]
    assert pk_column.name == "user_id", (
        f"Primary key column must be 'user_id' (from COBOL SEC-USR-ID PIC X(08)); found '{pk_column.name}'"
    )

    # PK type validation — must be String(8) to match COBOL PIC X(08).
    assert isinstance(pk_column.type, String), (
        f"Primary key 'user_id' must be SQLAlchemy String; found {type(pk_column.type).__name__}"
    )
    assert pk_column.type.length == 8, (
        f"Primary key 'user_id' must be String(8) (from COBOL "
        f"SEC-USR-ID PIC X(08)); found String({pk_column.type.length})"
    )


# ============================================================================
# Phase 3: Column Type Tests
# ============================================================================


@pytest.mark.unit
def test_usr_id_type() -> None:
    """``user_id`` column is ``String(8)`` (from COBOL SEC-USR-ID PIC X(08)).

    Preserves the original mainframe 8-character user-ID width so that
    existing user records migrated from VSAM remain addressable with
    their historical identifiers.
    """
    column = UserSecurity.__table__.columns["user_id"]
    assert isinstance(column.type, String), f"user_id must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 8, (
        f"user_id must be String(8) (from COBOL SEC-USR-ID PIC X(08)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_first_name_type() -> None:
    """``first_name`` column is ``String(20)`` (from COBOL SEC-USR-FNAME PIC X(20))."""
    column = UserSecurity.__table__.columns["first_name"]
    assert isinstance(column.type, String), f"first_name must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 20, (
        f"first_name must be String(20) (from COBOL SEC-USR-FNAME PIC X(20)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_last_name_type() -> None:
    """``last_name`` column is ``String(20)`` (from COBOL SEC-USR-LNAME PIC X(20))."""
    column = UserSecurity.__table__.columns["last_name"]
    assert isinstance(column.type, String), f"last_name must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 20, (
        f"last_name must be String(20) (from COBOL SEC-USR-LNAME PIC X(20)); found String({column.type.length})"
    )


@pytest.mark.unit
def test_password_type() -> None:
    """``password`` column is ``String(60)`` — the BCrypt hash upgrade.

    **CRITICAL — the only field-width deviation from the COBOL copybook.**

    * COBOL original: ``SEC-USR-PWD PIC X(08)`` — 8-character cleartext.
    * Python target:  ``password    String(60)`` — BCrypt digest.

    BCrypt hashes are always exactly 60 printable ASCII characters:
    ``$2b$`` (4) + cost factor (2) + ``$`` (1) + salt (22) +
    hash (31) = 60.

    This upgrade is explicitly required by AAP §0.7.2 "Security
    Requirements" — "BCrypt password hashing must be maintained for
    user authentication, matching existing COBOL behavior". Any other
    width would either truncate valid hashes (< 60) or admit invalid
    non-BCrypt values (> 60).
    """
    column = UserSecurity.__table__.columns["password"]
    assert isinstance(column.type, String), f"password must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 60, (
        f"password must be String(60) for BCrypt hash storage (UPGRADED "
        f"from COBOL SEC-USR-PWD PIC X(08) per AAP §0.7.2); "
        f"found String({column.type.length})"
    )


@pytest.mark.unit
def test_usr_type_type() -> None:
    """``usr_type`` column is ``String(1)`` (from COBOL SEC-USR-TYPE PIC X(01))."""
    column = UserSecurity.__table__.columns["usr_type"]
    assert isinstance(column.type, String), f"usr_type must be SQLAlchemy String; found {type(column.type).__name__}"
    assert column.type.length == 1, (
        f"usr_type must be String(1) (from COBOL SEC-USR-TYPE PIC X(01)); found String({column.type.length})"
    )


# ============================================================================
# Phase 4: User Type Validation Tests
# ============================================================================


@pytest.mark.unit
def test_admin_user_type() -> None:
    """``usr_type='A'`` represents an administrator.

    Maps to the COBOL 88-level condition::

        88 CDEMO-USRTYP-ADMIN VALUE 'A'.

    Grants access to the admin menu (``COADM01C``) and the user CRUD
    programs (``COUSR00C``, ``COUSR01C``, ``COUSR02C``, ``COUSR03C``).

    The single-character code is preserved as-is from the copybook so
    that migrated VSAM records retain their exact access-control
    semantics without any value-translation step.
    """
    admin_user = UserSecurity(
        user_id="ADMIN001",
        first_name="Admin",
        last_name="User",
        password=_BCRYPT_SENTINEL_HASH,
        usr_type="A",
    )

    assert admin_user.usr_type == "A", (
        f"Admin users must have usr_type='A' (COBOL 88 CDEMO-USRTYP-ADMIN VALUE 'A'); found '{admin_user.usr_type}'"
    )
    # The usr_type must be exactly 1 character to fit the String(1) column.
    assert len(admin_user.usr_type) == 1, (
        f"usr_type must be exactly 1 character (String(1)); found {len(admin_user.usr_type)} characters"
    )


@pytest.mark.unit
def test_regular_user_type() -> None:
    """``usr_type='U'`` represents a regular (non-admin) user.

    Maps to the COBOL 88-level condition::

        88 CDEMO-USRTYP-USER VALUE 'U'.

    Grants access to the main menu (``COMEN01C``) for account / card /
    transaction / bill-payment / report operations. Cannot access the
    admin menu or user-CRUD programs.
    """
    regular_user = UserSecurity(
        user_id="USER0001",
        first_name="Regular",
        last_name="User",
        password=_BCRYPT_SENTINEL_HASH,
        usr_type="U",
    )

    assert regular_user.usr_type == "U", (
        f"Regular users must have usr_type='U' (COBOL 88 CDEMO-USRTYP-USER VALUE 'U'); found '{regular_user.usr_type}'"
    )
    assert len(regular_user.usr_type) == 1, (
        f"usr_type must be exactly 1 character (String(1)); found {len(regular_user.usr_type)} characters"
    )


# ============================================================================
# Phase 5: Password Field Tests (BCrypt Upgrade)
# ============================================================================


@pytest.mark.unit
def test_password_length_for_bcrypt() -> None:
    """The ``password`` column length is exactly 60 to fit a BCrypt hash.

    BCrypt digest format breakdown::

        $2b$ <cost> $ <salt:22> <hash:31>
         4  +  2   + 1 +   22   +   31    = 60

    Storing fewer than 60 characters truncates valid hashes (rendering
    them unverifiable); storing more than 60 characters admits
    malformed values. A strict equality check on 60 is therefore the
    correct invariant — this test codifies the BCrypt upgrade required
    by AAP §0.7.2.

    Also sanity-checks the sentinel hash used elsewhere in this module
    so that test authors are warned if they accidentally shorten or
    lengthen it.
    """
    password_column = UserSecurity.__table__.columns["password"]
    # Narrow ``column.type`` from ``TypeEngine`` to ``String`` so that the
    # ``length`` attribute is visible to static type checkers — this
    # mirrors the pattern used by the other column-type tests in this
    # module.
    assert isinstance(password_column.type, String), (
        f"password must be SQLAlchemy String (needed for BCrypt hash "
        f"storage); found {type(password_column.type).__name__}"
    )
    assert password_column.type.length == 60, (
        f"password column length must be exactly 60 (BCrypt hash width); found {password_column.type.length}"
    )
    # Self-check: the module-level sentinel hash must also be 60 chars so
    # every downstream test uses a realistically sized value.
    assert len(_BCRYPT_SENTINEL_HASH) == 60, (
        f"Internal test-data sentinel _BCRYPT_SENTINEL_HASH must be "
        f"60 characters; found {len(_BCRYPT_SENTINEL_HASH)}. Fix the "
        f"constant at the top of this module."
    )


@pytest.mark.unit
def test_password_not_in_repr() -> None:
    """``__repr__`` must not leak the stored BCrypt password hash.

    Even though the stored value is a cryptographic hash (not the
    original cleartext), no credential-derived material should ever
    surface in log streams, tracebacks, debugger output, or error
    reports. This is a defence-in-depth measure required by AAP §0.7.2
    "Security Requirements".

    This test constructs a sentinel user whose password field contains
    a unique, recognizable BCrypt-shaped string and asserts that the
    value does NOT appear anywhere in the ``__repr__`` output. The
    sentinel also guards against an attribute name like ``'password'``
    sneaking into the repr as a keyword — that would make the field's
    presence in log output obvious to an attacker even without the
    hash value itself.
    """
    user = UserSecurity(
        user_id="LEAKTEST",
        first_name="Leak",
        last_name="Tester",
        password=_BCRYPT_SENTINEL_HASH,
        usr_type="U",
    )

    repr_output = repr(user)

    # The literal hash must not appear anywhere in the repr output.
    assert _BCRYPT_SENTINEL_HASH not in repr_output, (
        "UserSecurity.__repr__() MUST NOT include the password value "
        "(AAP §0.7.2 Security Requirements). Repr output was: "
        f"{repr_output!r}"
    )

    # The keyword 'password' must also be absent — its mere presence in
    # the repr would signal to a reader that the field exists on this
    # object and invite further probing.
    assert "password" not in repr_output, (
        "UserSecurity.__repr__() MUST NOT include the substring "
        "'password' — even the attribute name is withheld for "
        "defence-in-depth. Repr output was: "
        f"{repr_output!r}"
    )


# ============================================================================
# Phase 6: NOT NULL Constraint Tests
# ============================================================================


@pytest.mark.unit
def test_non_nullable_fields() -> None:
    """All 5 mapped columns must be declared NOT NULL.

    The COBOL ``SEC-USER-DATA`` record has no ``OCCURS ... DEPENDING ON``
    clauses and no ``REDEFINES`` — every field is present on every
    record. Translating that semantics to the relational model means
    every column is mandatory (``nullable=False``):

    * ``user_id``    — required (primary key; NULL PKs are rejected at
      the SQL level anyway).
    * ``first_name`` — required (COBOL PIC X(20), no blank-value
      convention in CSUSR01Y).
    * ``last_name``  — required (COBOL PIC X(20)).
    * ``password``   — required (no credential-less account is valid).
    * ``usr_type``   — required (the access-control discriminator 'A'/'U').

    SQLAlchemy automatically sets ``nullable=False`` on any column
    marked ``primary_key=True``, but this test asserts the invariant
    explicitly on every column so that accidentally dropping
    ``nullable=False`` (or unmarking the PK) triggers an obvious
    failure.
    """
    for column_name in _EXPECTED_COLUMNS:
        column = UserSecurity.__table__.columns[column_name]
        assert column.nullable is False, (
            f"Column '{column_name}' must be NOT NULL "
            f"(every COBOL SEC-USER-DATA field is mandatory); "
            f"nullable={column.nullable}"
        )


# ============================================================================
# Phase 7: Instance Creation Tests
# ============================================================================


@pytest.mark.unit
def test_create_user_security_instance() -> None:
    """A UserSecurity instance can be constructed with all 5 fields.

    Exercises the SQLAlchemy 2.x ``__init__`` synthesized from the
    :class:`~sqlalchemy.orm.Mapped` declarations in the ORM class.
    All field values correspond 1-to-1 to the COBOL
    ``SEC-USER-DATA`` record layout:

    * ``user_id="ADMIN001"``       — 8 chars (SEC-USR-ID PIC X(08))
    * ``first_name="Admin"``       — within 20 chars (SEC-USR-FNAME)
    * ``last_name="User"``         — within 20 chars (SEC-USR-LNAME)
    * ``password=_BCRYPT_SENTINEL`` — 60 chars BCrypt (UPGRADED)
    * ``usr_type="A"``             — 1 char admin flag (SEC-USR-TYPE)

    After construction, every field must read back verbatim. No ORM
    session or database round-trip is required for this test — it
    exercises pure in-memory object construction.
    """
    user = UserSecurity(
        user_id="ADMIN001",
        first_name="Admin",
        last_name="User",
        password=_BCRYPT_SENTINEL_HASH,
        usr_type="A",
    )

    # Verify the entity is a proper descendant of the shared
    # declarative base — this guards against accidentally re-rooting
    # the model on a different MetaData during a refactor.
    assert isinstance(user, Base), (
        "UserSecurity must be a subclass of src.shared.models.Base so that its table registers on the shared MetaData."
    )

    # Field-by-field readback.
    assert user.user_id == "ADMIN001", f"user_id readback mismatch: got '{user.user_id}'"
    assert user.first_name == "Admin", f"first_name readback mismatch: got '{user.first_name}'"
    assert user.last_name == "User", f"last_name readback mismatch: got '{user.last_name}'"
    assert user.password == _BCRYPT_SENTINEL_HASH, "password readback mismatch"
    assert user.usr_type == "A", f"usr_type readback mismatch: got '{user.usr_type}'"


@pytest.mark.unit
def test_user_security_repr() -> None:
    """``__repr__`` returns a developer-friendly string sans credentials.

    Contract:

    * MUST include the class name ``UserSecurity``.
    * MUST include ``user_id``, ``first_name``, ``last_name``, and
      ``usr_type`` (all non-credential identity / display fields).
    * MUST NOT include ``password`` (the attribute name OR its value).

    The repr is used by print statements, ``logging`` formatters,
    debugger inspections, and traceback frame-locals dumps — every one
    of which could otherwise exfiltrate credential-derived material
    (AAP §0.7.2 Security Requirements).
    """
    user = UserSecurity(
        user_id="REPRUSER",
        first_name="Repr",
        last_name="Tester",
        password=_BCRYPT_SENTINEL_HASH,
        usr_type="A",
    )

    repr_output = repr(user)

    # Required inclusions.
    assert "UserSecurity" in repr_output, f"__repr__ must include the class name 'UserSecurity'; got {repr_output!r}"
    assert "REPRUSER" in repr_output, f"__repr__ must include user_id value; got {repr_output!r}"
    assert "Repr" in repr_output, f"__repr__ must include first_name value; got {repr_output!r}"
    assert "Tester" in repr_output, f"__repr__ must include last_name value; got {repr_output!r}"
    # usr_type 'A' must be present — but we check for "'A'" (quoted) so
    # we don't false-positive on the 'A' inside "Admin" or "Tester".
    assert "'A'" in repr_output or '"A"' in repr_output, (
        f"__repr__ must include usr_type value 'A' as a quoted literal; got {repr_output!r}"
    )

    # Required exclusions (security).
    assert _BCRYPT_SENTINEL_HASH not in repr_output, (
        "__repr__ MUST NOT include the password hash value (AAP §0.7.2); got " + repr(repr_output)
    )
    assert "password" not in repr_output, (
        "__repr__ MUST NOT mention the 'password' attribute at all — defence-in-depth; got " + repr(repr_output)
    )


# ============================================================================
# Phase 8: FILLER Exclusion Test
# ============================================================================


@pytest.mark.unit
def test_no_filler_columns() -> None:
    """No column maps the COBOL ``SEC-USR-FILLER`` padding.

    COBOL fixed-width records routinely use ``FILLER`` regions to pad
    to a target record length (here, 23 bytes so the overall record
    reaches 80). These padding regions exist purely as storage
    artifacts — they carry no semantic data — and therefore have no
    equivalent in a typed relational schema.

    This test scans every column on the model's ``__table__.columns``
    collection and asserts that none contains the substring
    ``filler`` (case-insensitive). The substring check catches common
    naming variants including ``filler``, ``sec_usr_filler``,
    ``sec_filler``, ``usr_filler``, etc.
    """
    # Use Column.key (the Python attribute name) rather than Column.name
    # (the physical DB column name). The two diverge when
    # :func:`~sqlalchemy.orm.mapped_column` is given an explicit
    # ``name=`` or positional DB column name to map the Python
    # attribute onto a differently-named physical column in
    # ``db/migrations/V1__schema.sql`` (e.g., ``password`` attribute
    # → ``sec_usr_pwd`` column). This test asserts the Python-side
    # attribute set matches the declared contract; a parallel test
    # (:func:`test_no_filler_columns_db_names`) may additionally
    # verify the DB-level column names if required.
    column_keys: list[str] = [c.key for c in UserSecurity.__table__.columns]
    column_db_names: list[str] = [c.name for c in UserSecurity.__table__.columns]

    # Positive: the exact set of mapped columns (by Python attr name)
    # must match the contract.
    assert set(column_keys) == set(_EXPECTED_COLUMNS), (
        f"Column set drift detected. Expected: {sorted(_EXPECTED_COLUMNS)}; found: {sorted(column_keys)}"
    )

    # Negative: no Python attribute name OR DB column name may
    # contain the substring 'filler' in any casing. This guards
    # against future regressions where a copybook-to-model
    # translator accidentally emits a filler column.
    for column_name in column_keys + column_db_names:
        assert "filler" not in column_name.lower(), (
            f"Column '{column_name}' appears to map a COBOL FILLER "
            f"region. FILLER fields (like SEC-USR-FILLER PIC X(23)) "
            f"are padding only and MUST NOT be mapped to the "
            f"relational model."
        )
