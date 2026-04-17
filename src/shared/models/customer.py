# ============================================================================
# Source: COBOL copybook CVCUS01Y.cpy — CUSTOMER-RECORD (RECLN 500)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Replaces the mainframe CUSTFILE VSAM KSDS cluster (see app/jcl/CUSTFILE.jcl)
# with a relational PostgreSQL table persisting customer demographic records.
# The SSN column contains a 9-digit national identifier that the Agent Action
# Plan flags as sensitive — callers must ensure column-level encryption /
# tokenization at the AWS Aurora persistence layer (AAP §0.7.2 "Security
# Requirements").
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
"""SQLAlchemy 2.x ORM model for the ``customer`` table.

Converts the COBOL copybook ``app/cpy/CVCUS01Y.cpy`` (record layout
``CUSTOMER-RECORD``, 500-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a single customer in the CardDemo
Aurora PostgreSQL database.

COBOL to Python Field Mapping
-----------------------------
==========================  ==============  =======================  ==========================
COBOL Field                 COBOL Type      Python Column            SQLAlchemy Type
==========================  ==============  =======================  ==========================
CUST-ID                     ``PIC 9(09)``   ``cust_id``              ``String(9)`` — PK
CUST-FIRST-NAME             ``PIC X(25)``   ``first_name``           ``String(25)``
CUST-MIDDLE-NAME            ``PIC X(25)``   ``middle_name``          ``String(25)``
CUST-LAST-NAME              ``PIC X(25)``   ``last_name``            ``String(25)``
CUST-ADDR-LINE-1            ``PIC X(50)``   ``addr_line_1``          ``String(50)``
CUST-ADDR-LINE-2            ``PIC X(50)``   ``addr_line_2``          ``String(50)``
CUST-ADDR-LINE-3            ``PIC X(50)``   ``addr_line_3``          ``String(50)``
CUST-ADDR-STATE-CD          ``PIC X(02)``   ``state_cd``             ``String(2)``
CUST-ADDR-COUNTRY-CD        ``PIC X(03)``   ``country_cd``           ``String(3)``
CUST-ADDR-ZIP               ``PIC X(10)``   ``addr_zip``             ``String(10)``
CUST-PHONE-NUM-1            ``PIC X(15)``   ``phone_num_1``          ``String(15)``
CUST-PHONE-NUM-2            ``PIC X(15)``   ``phone_num_2``          ``String(15)``
CUST-SSN                    ``PIC 9(09)``†  ``ssn``                  ``String(9)`` — sensitive
CUST-GOVT-ISSUED-ID         ``PIC X(20)``   ``govt_issued_id``       ``String(20)``
CUST-DOB-YYYY-MM-DD         ``PIC X(10)``   ``dob``                  ``String(10)``
CUST-EFT-ACCOUNT-ID         ``PIC X(10)``   ``eft_account_id``       ``String(10)``
CUST-PRI-CARD-HOLDER-IND    ``PIC X(01)``   ``pri_card_holder_ind``  ``String(1)``
CUST-FICO-CREDIT-SCORE      ``PIC 9(03)``   ``fico_credit_score``    ``Integer`` ‡
FILLER                      ``PIC X(168)``  — (not mapped)           — (COBOL padding only)
==========================  ==============  =======================  ==========================

† **Sensitive field — SSN.** The 9-digit Social Security Number is stored as
  ``String(9)`` rather than ``Integer`` to preserve leading zeros from the
  COBOL ``PIC 9(09)`` source (e.g., ``'012345678'`` must round-trip without
  loss to ``12345678``). The Agent Action Plan (AAP §0.5.1 — ``customer``
  entry: "encrypted SSN field", AAP §0.7.2 "Security Requirements") mandates
  that this column be protected at rest. The ORM intentionally stores the
  column as plain ``String(9)`` here; column-level encryption / tokenization
  is the responsibility of the Aurora PostgreSQL persistence configuration
  (e.g., pgcrypto, AWS KMS-backed column encryption, or application-layer
  cryptography in the service layer). This keeps the COBOL copybook mapping
  byte-accurate while delegating cryptographic controls to the infrastructure
  boundary.

‡ **FICO credit score is Integer.** The 3-digit COBOL ``PIC 9(03)`` numeric
  field maps cleanly to a PostgreSQL ``INTEGER`` because FICO scores are
  always in the range 300 – 850 (never with meaningful leading zeros). This
  is the sole non-string column in the Customer entity; storing it as an
  integer enables efficient server-side ordering, range predicates, and
  aggregation queries (e.g., credit-tier reporting) without application-side
  string-to-integer coercion.

Total RECLN: 9 + (25 × 3) + (50 × 3) + 2 + 3 + 10 + (15 × 2) + 9 + 20 + 10
+ 10 + 1 + 3 + 168 = 500 bytes — matches the VSAM cluster definition in
``app/jcl/CUSTFILE.jcl``.

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the shared
  :class:`~sqlalchemy.MetaData` alongside the other CardDemo models.
* Identifier columns (``cust_id``, ``ssn``) derived from numeric COBOL
  pictures (``PIC 9(09)``) are deliberately stored as ``String`` rather
  than ``Integer`` so that leading zeros survive the migration. This
  preserves byte-for-byte parity with the VSAM KSDS records and with
  the fixture data in ``app/data/ASCII/custdata.txt``.
* ``dob`` is stored as a 10-character string in ``YYYY-MM-DD`` format,
  matching the COBOL ``CUST-DOB-YYYY-MM-DD`` field and avoiding any
  implicit date-type coercion at the ORM layer. Date validation is
  delegated to :mod:`src.shared.utils.date_utils` (preserving the
  ``CSUTLDTC`` validation rules — see AAP §0.7.1 "minimal change
  clause").
* The 168-byte FILLER tail of the COBOL record is **not** mapped — in
  the relational model, column widths are explicit and trailing padding
  carries no storage or semantic meaning.
* No monetary (``Decimal`` / ``Numeric``) columns exist on this entity;
  the customer record holds no financial amounts.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

Participates in the following online flows (AAP §0.2.3):

* **F-004 Account View** (``COACTVWC`` → ``account_service.view()``):
  3-entity join with ``Account`` and ``CardCrossReference`` to display
  cardholder demographic information.
* **F-005 Account Update** (``COACTUPC`` → ``account_service.update()``):
  dual-write atomic update of Customer + Account rows under a single
  transactional context (replacing CICS SYNCPOINT ROLLBACK semantics).
* **Batch — CREASTMT** (``CBSTM03A`` → ``creastmt_job.py``):
  customer row is one of 4 joined entities for statement header
  rendering.

See Also
--------
AAP §0.2.3 — Feature mapping for F-004/F-005 (Account View / Update).
AAP §0.5.1 — File-by-File Transformation Plan (``customer.py`` entry).
AAP §0.7.1 — Refactoring-Specific Rules (preserve behavior, minimal
change clause).
AAP §0.7.2 — Security Requirements (SSN protection, encryption at rest).
``app/cpy/CVCUS01Y.cpy`` — Original COBOL record layout (source
artifact, retained for traceability under AAP §0.7.1).
``app/jcl/CUSTFILE.jcl`` — Original VSAM cluster definition
(RECSZ(500 500), KEYS(9 0)).
``app/data/ASCII/custdata.txt`` — 50-row seed fixture loaded via
``db/migrations/V3__seed_data.sql``.
"""

from sqlalchemy import Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class Customer(Base):
    """ORM entity for the ``customer`` table (from COBOL ``CUSTOMER-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL ``customer``
    table, which replaces the mainframe VSAM KSDS ``CUSTFILE`` dataset.
    Each row corresponds to one customer (cardholder) with full
    demographic, contact, government-identifier, date-of-birth, EFT
    banking reference, primary-cardholder flag, and FICO credit-score
    attributes.

    The customer entity is referenced from:

    * **Account View / Update** — joined via ``Account.cust_id`` (logical
      FK) to retrieve the cardholder's full name, address, and phone for
      the F-004 / F-005 online flows.
    * **Statement Generation (CREASTMT)** — joined on the customer's
      account linkage to render statement headers (name, mailing
      address) for the monthly statement PDFs / HTML files produced
      by ``creastmt_job.py``.

    Attributes
    ----------
    cust_id : str
        **Primary key.** 9-character zero-padded customer ID (from COBOL
        ``CUST-ID``, ``PIC 9(09)``). Stored as ``String(9)`` rather than
        numeric to preserve leading zeros from migrated VSAM records
        byte-for-byte. Matches the VSAM cluster key length documented in
        ``app/jcl/CUSTFILE.jcl`` (``KEYS(9 0)``).
    first_name : str
        Up to 25-character given name (from COBOL ``CUST-FIRST-NAME``,
        ``PIC X(25)``). Required (``nullable=False``).
    middle_name : str
        Up to 25-character middle name (from COBOL ``CUST-MIDDLE-NAME``,
        ``PIC X(25)``). Defaults to empty string when omitted.
    last_name : str
        Up to 25-character surname (from COBOL ``CUST-LAST-NAME``,
        ``PIC X(25)``). Required (``nullable=False``).
    addr_line_1 : str
        First line of mailing address (from COBOL ``CUST-ADDR-LINE-1``,
        ``PIC X(50)``). Defaults to empty string when omitted.
    addr_line_2 : str
        Second line of mailing address (from COBOL ``CUST-ADDR-LINE-2``,
        ``PIC X(50)``). Defaults to empty string when omitted.
    addr_line_3 : str
        Third line of mailing address (from COBOL ``CUST-ADDR-LINE-3``,
        ``PIC X(50)``). Defaults to empty string when omitted.
    state_cd : str
        2-character state code (from COBOL ``CUST-ADDR-STATE-CD``,
        ``PIC X(02)``). ISO-like US state abbreviation. Defaults to
        empty string when omitted.
    country_cd : str
        3-character country code (from COBOL ``CUST-ADDR-COUNTRY-CD``,
        ``PIC X(03)``). ISO 3166-1 alpha-3 country code. Defaults to
        empty string when omitted.
    addr_zip : str
        Up to 10-character ZIP / postal code (from COBOL
        ``CUST-ADDR-ZIP``, ``PIC X(10)``). Accommodates 5-digit,
        9-digit (ZIP+4), and international postal codes. Defaults to
        empty string when omitted.
    phone_num_1 : str
        Primary phone number (from COBOL ``CUST-PHONE-NUM-1``,
        ``PIC X(15)``). Free-format string to preserve formatting
        (e.g., ``'(555) 123-4567'``). Defaults to empty string when
        omitted.
    phone_num_2 : str
        Secondary / alternate phone number (from COBOL
        ``CUST-PHONE-NUM-2``, ``PIC X(15)``). Defaults to empty string
        when omitted.
    ssn : str
        **Sensitive — SSN.** 9-digit US Social Security Number (from
        COBOL ``CUST-SSN``, ``PIC 9(09)``). Stored as ``String(9)`` to
        preserve leading zeros. Marked by AAP §0.5.1 / §0.7.2 as a field
        requiring column-level encryption at the Aurora PostgreSQL
        persistence layer (e.g., pgcrypto, AWS KMS-backed encryption,
        or application-layer cryptography). No cryptographic
        transformation is applied at this ORM layer — the encryption
        boundary lives in infrastructure. The ``__repr__`` method
        excludes this field to prevent accidental leakage into logs or
        tracebacks.
    govt_issued_id : str
        Up to 20-character government-issued identifier (from COBOL
        ``CUST-GOVT-ISSUED-ID``, ``PIC X(20)``). Examples: driver's
        license number, passport number, state ID. Defaults to empty
        string when omitted.
    dob : str
        10-character date of birth in ``YYYY-MM-DD`` format (from COBOL
        ``CUST-DOB-YYYY-MM-DD``, ``PIC X(10)``). Stored as a string to
        preserve the exact COBOL field representation; date validation
        is delegated to :mod:`src.shared.utils.date_utils`. Defaults to
        empty string when omitted.
    eft_account_id : str
        Up to 10-character EFT / direct-debit bank account identifier
        (from COBOL ``CUST-EFT-ACCOUNT-ID``, ``PIC X(10)``). Used by the
        F-012 Bill Payment flow to source funds for bill-pay debits.
        Defaults to empty string when omitted.
    pri_card_holder_ind : str
        1-character primary-cardholder indicator (from COBOL
        ``CUST-PRI-CARD-HOLDER-IND``, ``PIC X(01)``). Typical values:
        ``'Y'`` = primary cardholder, ``'N'`` = authorized user /
        secondary cardholder. Defaults to empty string when omitted.
    fico_credit_score : int
        3-digit FICO credit score (from COBOL ``CUST-FICO-CREDIT-SCORE``,
        ``PIC 9(03)``). Stored as PostgreSQL ``INTEGER``. Valid FICO
        range is 300 – 850; values outside this range (including the
        default ``0`` on INSERT) indicate a missing or unscored
        customer. Used by F-004 Account View, F-005 Account Update, and
        credit-tier reporting.
    """

    __tablename__ = "customer"

    # ------------------------------------------------------------------
    # Primary key: 9-digit customer ID (COBOL ``CUST-ID`` PIC 9(09))
    #
    # Stored as String(9) — NOT Integer — to preserve leading zeros
    # carried over from the VSAM KSDS records and the fixture data in
    # ``app/data/ASCII/custdata.txt``. Matches ``KEYS(9 0)`` in
    # ``app/jcl/CUSTFILE.jcl``.
    # ------------------------------------------------------------------
    cust_id: Mapped[str] = mapped_column(
        String(9),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Name fields (COBOL CUST-FIRST/MIDDLE/LAST-NAME PIC X(25))
    # ------------------------------------------------------------------
    first_name: Mapped[str] = mapped_column(
        String(25),
        nullable=False,
    )

    middle_name: Mapped[str] = mapped_column(
        String(25),
        nullable=False,
        default="",
    )

    last_name: Mapped[str] = mapped_column(
        String(25),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Address lines 1-3 (COBOL CUST-ADDR-LINE-1/2/3 PIC X(50))
    # ------------------------------------------------------------------
    addr_line_1: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
        default="",
    )

    addr_line_2: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
        default="",
    )

    addr_line_3: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # State code (COBOL CUST-ADDR-STATE-CD PIC X(02)) — e.g., 'NY', 'CA'
    # ------------------------------------------------------------------
    state_cd: Mapped[str] = mapped_column(
        String(2),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Country code (COBOL CUST-ADDR-COUNTRY-CD PIC X(03)) — ISO 3166-1
    # alpha-3 (e.g., 'USA', 'CAN', 'GBR')
    # ------------------------------------------------------------------
    country_cd: Mapped[str] = mapped_column(
        String(3),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # ZIP / postal code (COBOL CUST-ADDR-ZIP PIC X(10))
    # Width of 10 accommodates 5-digit, 9-digit (ZIP+4), and
    # international postal codes.
    # ------------------------------------------------------------------
    addr_zip: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Phone numbers (COBOL CUST-PHONE-NUM-1 / CUST-PHONE-NUM-2 PIC X(15))
    # Free-format strings to preserve human formatting.
    # ------------------------------------------------------------------
    phone_num_1: Mapped[str] = mapped_column(
        String(15),
        nullable=False,
        default="",
    )

    phone_num_2: Mapped[str] = mapped_column(
        String(15),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Social Security Number (COBOL CUST-SSN PIC 9(09))
    #
    # SENSITIVE FIELD. Stored as String(9) to preserve leading zeros.
    # Per AAP §0.5.1 / §0.7.2, column-level encryption at the Aurora
    # PostgreSQL layer (pgcrypto, AWS KMS-backed encryption, or
    # application-layer cryptography) is required — NOT applied at this
    # ORM layer. The ``__repr__`` method deliberately excludes this
    # value to prevent accidental leakage into log streams or debugger
    # output.
    # ------------------------------------------------------------------
    ssn: Mapped[str] = mapped_column(
        String(9),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Government-issued identifier (COBOL CUST-GOVT-ISSUED-ID PIC X(20))
    # — e.g., driver's license number, passport number.
    # ------------------------------------------------------------------
    govt_issued_id: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Date of birth (COBOL CUST-DOB-YYYY-MM-DD PIC X(10))
    #
    # Stored as a 10-character string in 'YYYY-MM-DD' format, matching
    # the COBOL source layout. Date validation is delegated to
    # ``src.shared.utils.date_utils`` (preserving the CSUTLDTC rules).
    # ------------------------------------------------------------------
    dob: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # EFT / direct-debit account identifier
    # (COBOL CUST-EFT-ACCOUNT-ID PIC X(10)) — used by F-012 Bill Payment.
    # ------------------------------------------------------------------
    eft_account_id: Mapped[str] = mapped_column(
        String(10),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # Primary-cardholder indicator
    # (COBOL CUST-PRI-CARD-HOLDER-IND PIC X(01))
    # 'Y' = primary cardholder, 'N' = authorized user / secondary.
    # ------------------------------------------------------------------
    pri_card_holder_ind: Mapped[str] = mapped_column(
        String(1),
        nullable=False,
        default="",
    )

    # ------------------------------------------------------------------
    # FICO credit score (COBOL CUST-FICO-CREDIT-SCORE PIC 9(03))
    #
    # Stored as INTEGER — unlike cust_id and ssn, FICO scores have no
    # meaningful leading-zero semantics (valid range: 300 – 850). A
    # value of 0 (the default) indicates a missing / unscored customer.
    # ------------------------------------------------------------------
    fico_credit_score: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
    )

    # Note: COBOL ``FILLER PIC X(168)`` — the trailing 168 bytes of
    # padding in the original 500-byte VSAM record — is deliberately
    # NOT mapped. In the relational model, column widths are explicit
    # and trailing padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        **Security:** the ``ssn`` column is intentionally omitted from
        this representation. The 9-digit Social Security Number is
        classified as sensitive under AAP §0.7.2 and must not surface
        in log streams, tracebacks, or interactive debugger output.
        Other identifier fields (``cust_id``, ``first_name``,
        ``last_name``) are safe to display and support common debugging
        and audit workflows.

        Returns
        -------
        str
            Representation of the form
            ``Customer(cust_id='000000001', first_name='JOHN',
            last_name='DOE')``.
        """
        return f"Customer(cust_id={self.cust_id!r}, first_name={self.first_name!r}, last_name={self.last_name!r})"
