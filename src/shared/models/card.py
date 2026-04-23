# ============================================================================
# Source: COBOL copybook CVACT02Y.cpy — CARD-RECORD (RECLN 150)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Replaces the mainframe CARDFILE VSAM KSDS cluster (see app/jcl/CARDFILE.jcl)
# with a relational PostgreSQL table persisting credit card records. The
# alternate index CARDFILE.CARDAIX.PATH — which keyed cards by their
# owning account ID in the VSAM world — is re-implemented here as a
# non-unique B-tree index (``ix_card_acct_id``) on the ``acct_id`` column.
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
"""SQLAlchemy 2.x ORM model for the ``card`` table.

Converts the COBOL copybook ``app/cpy/CVACT02Y.cpy`` (record layout
``CARD-RECORD``, 150-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a single credit card in the
CardDemo Aurora PostgreSQL database.

COBOL to Python Field Mapping
-----------------------------
========================  =================  ====================  =====================
COBOL Field               COBOL Type         Python Column         SQLAlchemy Type
========================  =================  ====================  =====================
CARD-NUM                  ``PIC X(16)``      ``card_num``          ``String(16)`` — PK
CARD-ACCT-ID              ``PIC 9(11)``      ``acct_id``           ``String(11)`` †
CARD-CVV-CD               ``PIC 9(03)``      ``cvv_cd``            ``String(3)`` †
CARD-EMBOSSED-NAME        ``PIC X(50)``      ``embossed_name``     ``String(50)``
CARD-EXPIRAION-DATE ‡     ``PIC X(10)``      ``expiration_date``   ``String(10)``
CARD-ACTIVE-STATUS        ``PIC X(01)``      ``active_status``     ``String(1)``
FILLER                    ``PIC X(59)``      — (not mapped)        — (COBOL padding only)
(new)                     —                  ``version_id``        ``Integer`` — OCC
========================  =================  ====================  =====================

† **Numeric identifiers stored as strings.** The COBOL fields
  ``CARD-ACCT-ID`` (``PIC 9(11)``) and ``CARD-CVV-CD`` (``PIC 9(03)``)
  are numeric in the source record layout, but both are mapped to
  ``String(n)`` columns on the Python / PostgreSQL side. This is
  deliberate: migrated VSAM records can legitimately contain leading
  zeros (e.g., account ID ``00000000001`` or CVV ``007``), and
  storing these as numeric types would silently strip the leading
  zeros at INSERT time, breaking both the 3-entity join used by
  ``COACTVWC`` / ``account_service.view()`` and the CVV comparison
  semantics used by card-verification flows. Byte-for-byte preservation
  of the original VSAM representation is required by AAP §0.7.1
  ("preserve all existing functionality exactly as-is").

‡ The original COBOL field name ``CARD-EXPIRAION-DATE`` contains a
  historical typo (missing a ``T`` — the name should be
  ``CARD-EXPIRATION-DATE``). The Python column is renamed to the
  corrected spelling ``expiration_date`` because the copybook-to-Python
  mapping is purely semantic (relational PostgreSQL has no schema
  coupling to COBOL field names). The COBOL typo is preserved
  unchanged in the retained ``app/cpy/CVACT02Y.cpy`` source artifact
  for traceability, per AAP §0.7.1 ("do not modify the original
  COBOL source files").

Total RECLN: 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes — matches the
VSAM cluster definition in ``app/jcl/CARDFILE.jcl`` (``RECSZ(150 150)``).

Alternate Index Preservation
----------------------------
The VSAM cluster ``CARDFILE`` defined an alternate index
``CARDFILE.CARDAIX.PATH`` keyed on ``CARD-ACCT-ID`` (see the IDCAMS
catalog report ``app/catlg/LISTCAT.txt`` — 3 AIX paths are migrated).
This alternate index enabled efficient "find all cards belonging to
account N" queries from COBOL programs:

* ``COACTVWC.cbl`` (F-004 Account View) — joins Card by account ID
  as part of the 3-entity view (Account + Customer + CardCrossReference
  + Card).
* ``COCRDLIC.cbl`` (F-006 Card List) — lists cards belonging to the
  currently-selected account, 7 rows per page.
* ``CBSTM03A.CBL`` (CREASTMT batch stage) — fans out statements
  across every card of an account.

To preserve this access pattern with Aurora PostgreSQL, this module
declares a non-unique B-tree index (``ix_card_acct_id``) on the
``acct_id`` column via ``__table_args__``. The index is intentionally
non-unique: a single account can legitimately own multiple cards
(primary, authorised user, replacement, etc.) which is exactly why
the VSAM AIX was also non-unique (``NONUNIQUEKEY`` flag in LISTCAT).

Optimistic Concurrency
----------------------
The ``version_id`` column (``Integer``, default ``0``) is wired to
SQLAlchemy's built-in optimistic-locking feature via
``__mapper_args__ = {"version_id_col": version_id}``. On every UPDATE,
SQLAlchemy appends ``AND version_id = :old_version`` to the WHERE
clause and increments the column. A stale read results in zero rows
affected, which SQLAlchemy raises as
:class:`sqlalchemy.orm.exc.StaleDataError`. This replaces the
CICS ``READ UPDATE`` / ``REWRITE`` locking protocol used in
``app/cbl/COCRDUPC.cbl`` (the Card Update online program, F-008).
See AAP §0.7.1 — "The optimistic concurrency check in Card Update
(F-008) must be maintained".

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models.
* No FILLER column is mapped — the trailing 59 bytes of COBOL padding
  in the 150-byte VSAM record have no relational counterpart. In
  PostgreSQL, column widths are explicit and trailing padding carries
  no storage or semantic meaning.
* ``acct_id`` is declared as a plain ``String(11)`` rather than as a
  SQL-level ``ForeignKey("account.acct_id")``. The FK relationship to
  the ``Account`` table is real and enforced at the service layer, but
  the declarative FK is deliberately NOT wired at the ORM level here to
  keep ``card.py`` and ``account.py`` independently loadable and to
  mirror the pattern already established in ``account.py`` (which
  similarly does not declare a FK to ``disclosure_group``). Referential
  integrity for the full set of relationships is managed by the
  Flyway-style migration scripts in ``db/migrations/``.
* The ``expiration_date`` column uses ``String(10)`` rather than a
  native ``DATE`` type to mirror the COBOL ``PIC X(10)`` representation
  byte-for-byte. This allows the faithful migration of VSAM records
  whose dates may not parse as strict ISO-8601 (e.g., placeholder
  values, partial dates). Date validation is delegated to
  ``src.shared.utils.date_utils`` so the ``CSUTLDTC`` rules are
  preserved.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.3 — Feature mapping for F-006 (Card List), F-007 (Card View),
and F-008 (Card Update).
AAP §0.5.1 — File-by-File Transformation Plan (``card.py`` entry).
AAP §0.7.1 — Refactoring-Specific Rules (optimistic concurrency must
be maintained for Card Update).
``app/cpy/CVACT02Y.cpy`` — Original COBOL record layout (source
artifact, retained for traceability).
``app/jcl/CARDFILE.jcl`` — Original VSAM cluster definition
(RECSZ(150 150), KEYS(16 0)).
``app/catlg/LISTCAT.txt`` — IDCAMS catalog entry for
``CARDFILE.CARDAIX.PATH`` alternate index.
``app/data/ASCII/carddata.txt`` — 50-row seed fixture loaded via
``db/migrations/V3__seed_data.sql``.
"""

from sqlalchemy import Index, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class Card(Base):
    """ORM entity for the ``card`` table (from COBOL ``CARD-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``card`` table, which replaces the mainframe VSAM KSDS
    ``CARDFILE`` dataset. Each row corresponds to one physical credit
    card — a plastic instrument issued against an underlying
    :class:`~src.shared.models.account.Account` — and carries the
    security attributes (CVV, embossed name, expiration date, active
    status) that are presented at the point of sale.

    Participates in the following online and batch flows:

    * **Online — F-006 Card List** (``COCRDLIC`` →
      ``card_service.list_by_account()``): paginated listing of cards
      belonging to the currently-selected account (7 rows per page).
      Leverages the ``ix_card_acct_id`` index for efficient account
      scoping.
    * **Online — F-007 Card View** (``COCRDSLC`` →
      ``card_service.get()``): full detail view of a single card by
      ``card_num`` primary key.
    * **Online — F-008 Card Update** (``COCRDUPC`` →
      ``card_service.update()``): atomic update of card attributes
      protected by ``version_id`` optimistic concurrency. Preserves
      the CICS ``READ UPDATE`` / ``REWRITE`` semantics of the
      mainframe program (AAP §0.7.1 — "The optimistic concurrency
      check in Card Update (F-008) must be maintained").
    * **Online — F-004 Account View** (``COACTVWC`` →
      ``account_service.view()``): one of the 3 entities joined
      through the ``CardCrossReference`` link to produce the
      consolidated account view.
    * **Batch — CREASTMT** (``CBSTM03A.CBL`` →
      ``creastmt_job.py``): one of 4 joined entities in the statement
      generation pipeline; the statement enumerates all cards on the
      account and the transactions posted to each.

    Attributes
    ----------
    card_num : str
        **Primary key.** 16-character card number (from COBOL
        ``CARD-NUM``, ``PIC X(16)``). Stored as ``String(16)`` to
        preserve the original character representation byte-for-byte.
        Matches the VSAM cluster key length documented in
        ``app/jcl/CARDFILE.jcl`` (``KEYS(16 0)``). Primary-account
        numbers (PANs) migrated from production VSAM can contain
        embedded non-digits in the CardDemo sample data, which is why
        the column is ``String(16)`` rather than a numeric type.
    acct_id : str
        11-character zero-padded owning account ID (from COBOL
        ``CARD-ACCT-ID``, ``PIC 9(11)``). Stored as ``String(11)``
        rather than numeric to preserve leading zeros from migrated
        VSAM records. **Indexed** by ``ix_card_acct_id`` (non-unique
        B-tree) so that "find all cards belonging to account N"
        queries — issued by the F-004 Account View, F-006 Card List,
        and CREASTMT batch stage — remain efficient. This index
        directly replicates the VSAM alternate index
        ``CARDFILE.CARDAIX.PATH`` from the mainframe world (see the
        "Alternate Index Preservation" section of the module
        docstring).
    cvv_cd : str
        3-character card verification value (from COBOL
        ``CARD-CVV-CD``, ``PIC 9(03)``). Stored as ``String(3)``
        rather than numeric to preserve leading zeros (e.g., CVV
        ``007`` is valid and must not be collapsed to the integer
        ``7``). Used for card-not-present transaction verification.
    embossed_name : str
        50-character name embossed on the physical card face (from
        COBOL ``CARD-EMBOSSED-NAME``, ``PIC X(50)``). This is the
        cardholder's name as printed on the card, and may differ from
        the owning :class:`~src.shared.models.customer.Customer`
        full name for joint accounts, authorised-user cards, and
        similar scenarios.
    expiration_date : str
        10-character card expiration date (from COBOL
        ``CARD-EXPIRAION-DATE``, ``PIC X(10)`` — note the original
        COBOL typo, corrected on the Python side as described in the
        module docstring). ISO-like format ``YYYY-MM-DD``. Validated
        by ``src.shared.utils.date_utils`` helpers, which preserve
        the ``CSUTLDTC`` validation rules. The batch POSTTRAN stage
        (``CBTRN02C`` → ``posttran_job.py``) consults this field
        during transaction validation.
    active_status : str
        1-character active-status flag (from COBOL
        ``CARD-ACTIVE-STATUS``, ``PIC X(01)``). Typical values:
        ``'Y'`` = active (card may be used), ``'N'`` = inactive
        (transactions must be rejected). Consulted by the online
        card-management flows and by the POSTTRAN batch reject-code
        cascade when deciding whether a transaction may be posted.
    version_id : int
        Optimistic-concurrency counter (not from COBOL — introduced
        as part of the CICS → SQLAlchemy migration). Incremented by
        SQLAlchemy on every UPDATE; participates in the WHERE clause
        so that a stale read-then-write raises
        :class:`sqlalchemy.orm.exc.StaleDataError`. This replaces the
        CICS ``READ UPDATE`` / ``REWRITE`` enqueue protocol used in
        ``COCRDUPC.cbl`` (Card Update program). See AAP §0.7.1 —
        "The optimistic concurrency check in Card Update (F-008)
        must be maintained".
    """

    __tablename__ = "cards"

    # ------------------------------------------------------------------
    # Non-unique B-tree index on the owning account ID.
    #
    # Replicates the VSAM alternate index CARDFILE.CARDAIX.PATH
    # (NONUNIQUEKEY) from the mainframe, enabling efficient
    # "find all cards belonging to account N" scans issued by:
    #
    #   * F-006 Card List (COCRDLIC → card_service.list_by_account())
    #   * F-004 Account View (COACTVWC → account_service.view())
    #   * CREASTMT batch stage (CBSTM03A → creastmt_job.py)
    #
    # Declared non-unique because a single account can legitimately
    # own multiple cards (primary, authorised user, replacement card,
    # lost/stolen reissue, etc.) — mirroring the NONUNIQUEKEY property
    # of the original VSAM AIX (see app/catlg/LISTCAT.txt).
    # ------------------------------------------------------------------
    __table_args__ = (Index("ix_card_acct_id", "acct_id"),)

    # ------------------------------------------------------------------
    # Primary key: 16-character card number
    # (COBOL ``CARD-NUM`` PIC X(16))
    #
    # Stored as String(16) to preserve the original character
    # representation byte-for-byte. Matches the VSAM cluster key
    # length (KEYS(16 0)) in app/jcl/CARDFILE.jcl.
    # ------------------------------------------------------------------
    card_num: Mapped[str] = mapped_column(
        String(16),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Owning account ID (COBOL ``CARD-ACCT-ID`` PIC 9(11))
    #
    # Stored as String(11) — not numeric — to preserve leading zeros
    # from migrated VSAM records byte-for-byte. Logical foreign key
    # into the ``account`` table (FK enforced at the migration-script
    # / service layer, not declared at the ORM level to keep card.py
    # and account.py independently loadable).
    #
    # Indexed by ``ix_card_acct_id`` (see __table_args__ above) to
    # replicate the VSAM AIX CARDFILE.CARDAIX.PATH access path.
    #
    # DB column name: ``card_acct_id`` (per V1__schema.sql — all
    # columns in the ``cards`` table carry the ``card_`` prefix).
    # ------------------------------------------------------------------
    acct_id: Mapped[str] = mapped_column(
        "card_acct_id",
        String(11),
        nullable=False,
        key="acct_id",
    )

    # ------------------------------------------------------------------
    # Card verification value (COBOL ``CARD-CVV-CD`` PIC 9(03))
    #
    # Stored as String(3) to preserve leading zeros — e.g., CVV '007'
    # is a valid 3-digit CVV that would be silently collapsed to the
    # integer 7 if stored as a numeric column. Used for card-not-
    # present transaction verification.
    #
    # DB column name: ``card_cvv_cd``.
    # ------------------------------------------------------------------
    cvv_cd: Mapped[str] = mapped_column(
        "card_cvv_cd",
        String(3),
        nullable=False,
        key="cvv_cd",
    )

    # ------------------------------------------------------------------
    # Embossed name (COBOL ``CARD-EMBOSSED-NAME`` PIC X(50))
    #
    # 50-character name as printed on the physical card. May differ
    # from the owning Customer's full name for joint accounts,
    # authorised-user cards, and similar scenarios.
    #
    # DB column name: ``card_embossed_name``.
    # ------------------------------------------------------------------
    embossed_name: Mapped[str] = mapped_column(
        "card_embossed_name",
        String(50),
        nullable=False,
        key="embossed_name",
    )

    # ------------------------------------------------------------------
    # Expiration date (COBOL ``CARD-EXPIRAION-DATE`` PIC X(10))
    #
    # The original COBOL field name contains a historical typo
    # ("EXPIRAION" should be "EXPIRATION"). The Python column is
    # corrected to ``expiration_date`` because the mapping is
    # semantic rather than byte-copy. The COBOL typo is preserved in
    # the retained app/cpy/CVACT02Y.cpy source artifact for
    # traceability (AAP §0.7.1 — do not modify original COBOL source).
    #
    # 10-character YYYY-MM-DD string; validation delegated to
    # src.shared.utils.date_utils (which preserves the CSUTLDTC rules).
    #
    # DB column name: ``card_expiration_date`` (typo normalized in the
    # PostgreSQL DDL per V1__schema.sql).
    # ------------------------------------------------------------------
    expiration_date: Mapped[str] = mapped_column(
        "card_expiration_date",
        String(10),
        nullable=False,
        key="expiration_date",
    )

    # ------------------------------------------------------------------
    # Active-status flag (COBOL ``CARD-ACTIVE-STATUS`` PIC X(01))
    #
    # 'Y' = card is active and may be used;
    # 'N' = card is inactive, all transactions must be rejected.
    # Consulted by the online card-management flows and by the
    # POSTTRAN batch reject-code cascade.
    #
    # DB column name: ``card_active_status``.
    # ------------------------------------------------------------------
    active_status: Mapped[str] = mapped_column(
        "card_active_status",
        String(1),
        nullable=False,
        key="active_status",
    )

    # ------------------------------------------------------------------
    # Optimistic concurrency counter (NOT from COBOL — new column).
    #
    # SQLAlchemy increments this on every UPDATE and appends it to
    # the WHERE clause so that a stale read-then-write raises
    # sqlalchemy.orm.exc.StaleDataError. This replaces the CICS
    # READ UPDATE / REWRITE enqueue protocol used in COCRDUPC.cbl
    # (the Card Update online program, F-008). See __mapper_args__
    # below — the mapping wires this column to SQLAlchemy's
    # version_id_col feature. AAP §0.7.1 explicitly requires that
    # "the optimistic concurrency check in Card Update (F-008) must
    # be maintained".
    # ------------------------------------------------------------------
    version_id: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
    )

    # Note: COBOL ``FILLER PIC X(59)`` — the trailing 59 bytes of
    # padding in the original 150-byte VSAM record — is deliberately
    # NOT mapped. In the relational model, column widths are explicit
    # and trailing padding has no storage or semantic meaning.

    # ------------------------------------------------------------------
    # SQLAlchemy mapper options.
    #
    # ``version_id_col`` enables SQLAlchemy's built-in optimistic-
    # locking feature: on UPDATE, SQLAlchemy appends
    # ``AND version_id = :old_version`` to the WHERE clause and
    # increments the column. A stale write results in zero rows
    # affected, which SQLAlchemy raises as StaleDataError. This
    # replaces the CICS READ UPDATE / REWRITE locking protocol used
    # in COCRDUPC.cbl (AAP §0.7.1 — Card Update must preserve
    # optimistic concurrency).
    # ------------------------------------------------------------------
    __mapper_args__ = {
        "version_id_col": version_id,
    }

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        The three most diagnostically useful columns are included:
        the primary key (``card_num``), the owning account ID
        (``acct_id``), and the active-status flag (``active_status``).
        Security-sensitive fields such as ``cvv_cd`` are deliberately
        omitted from the representation to avoid leaking CVV values
        into log files.

        Returns
        -------
        str
            Representation of the form
            ``Card(card_num='4111111111111111', acct_id='00000000001',
            active_status='Y')``.
        """
        return f"Card(card_num={self.card_num!r}, acct_id={self.acct_id!r}, active_status={self.active_status!r})"
