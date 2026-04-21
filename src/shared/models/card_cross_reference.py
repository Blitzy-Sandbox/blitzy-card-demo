# ============================================================================
# Source: COBOL copybook CVACT03Y.cpy — CARD-XREF-RECORD (RECLN 50)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Replaces the mainframe XREFFILE VSAM KSDS cluster (see app/jcl/XREFFILE.jcl)
# with a relational PostgreSQL table persisting the card → customer → account
# cross-reference. The alternate index XREFFILE.XREFAI02.PATH — which keyed
# cross-reference rows by their owning account ID in the VSAM world — is
# re-implemented here as a non-unique B-tree index
# (``ix_card_cross_reference_acct_id``) on the ``acct_id`` column.
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
"""SQLAlchemy 2.x ORM model for the ``card_cross_reference`` table.

Converts the COBOL copybook ``app/cpy/CVACT03Y.cpy`` (record layout
``CARD-XREF-RECORD``, 50-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a single card ↔ customer ↔ account
cross-reference row in the CardDemo Aurora PostgreSQL database.

The cross-reference table is the linking entity that resolves a physical
card number to its owning customer and account — a many-to-many bridge
that was implemented in the mainframe world as a dedicated VSAM KSDS
keyed by card number with an alternate index by account ID.

COBOL to Python Field Mapping
-----------------------------
==============  ==============  ==============  =========================
COBOL Field     COBOL Type      Python Column   SQLAlchemy Type
==============  ==============  ==============  =========================
XREF-CARD-NUM   ``PIC X(16)``   ``card_num``    ``String(16)`` — PK
XREF-CUST-ID    ``PIC 9(09)``   ``cust_id``     ``String(9)`` †
XREF-ACCT-ID    ``PIC 9(11)``   ``acct_id``     ``String(11)`` †
FILLER          ``PIC X(14)``   — (not mapped)  — (COBOL padding only)
==============  ==============  ==============  =========================

† **Numeric identifiers stored as strings.** The COBOL fields
  ``XREF-CUST-ID`` (``PIC 9(09)``) and ``XREF-ACCT-ID`` (``PIC 9(11)``)
  are numeric in the source record layout, but both are mapped to
  ``String(n)`` columns on the Python / PostgreSQL side. This is
  deliberate: migrated VSAM records can legitimately contain leading
  zeros (e.g., customer ID ``000000001`` or account ID
  ``00000000001``), and storing these as numeric types would silently
  strip the leading zeros at INSERT time, breaking the logical joins
  to :class:`~src.shared.models.customer.Customer` (``cust_id``) and
  :class:`~src.shared.models.account.Account` (``acct_id``).
  Byte-for-byte preservation of the original VSAM representation is
  required by AAP §0.7.1 ("preserve all existing functionality
  exactly as-is"). The identical convention is used for the companion
  FK-like identifiers on :class:`~src.shared.models.card.Card` and
  :class:`~src.shared.models.account.Account`.

Total RECLN: 16 + 9 + 11 + 14 = 50 bytes — matches the VSAM cluster
definition in ``app/jcl/XREFFILE.jcl`` (``RECSZ(50 50)``) and the
IDCAMS catalog entry in ``app/catlg/LISTCAT.txt``.

Alternate Index Preservation
----------------------------
The VSAM cluster ``XREFFILE`` defined an alternate index
``XREFFILE.XREFAI02.PATH`` keyed on ``XREF-ACCT-ID`` (one of 3 AIX
paths in the CardDemo mainframe layout — see the IDCAMS catalog
report ``app/catlg/LISTCAT.txt``). This alternate index enabled
efficient "find all cards mapped to account N" lookups from COBOL
programs:

* ``COACTVWC.cbl`` (F-004 Account View) — traverses the cross-reference
  by account ID to locate all cards on the account as part of the
  3-entity join (Account + Customer + Card) rendered on the account
  view screen.
* ``COCRDLIC.cbl`` (F-006 Card List) — similarly walks the
  cross-reference by account ID to produce the paginated 7-rows-per-page
  card listing scoped to the currently-selected account.
* ``COTRN02C.cbl`` (F-011 Transaction Add) — resolves the card-number
  input value to its owning account ID through the cross-reference at
  transaction creation time, so the posted transaction carries the
  correct ``acct_id`` for later balance updates and statement
  attribution.
* ``CBTRN02C.cbl`` / ``posttran_job.py`` (POSTTRAN batch stage) — the
  same card-number-to-account resolution is repeated in bulk during
  daily transaction posting; every inbound daily transaction must
  resolve to a valid cross-reference row or be rejected with reject
  code 102 (see the POSTTRAN 4-stage validation cascade).

To preserve this access pattern with Aurora PostgreSQL, this module
declares a non-unique B-tree index (``ix_card_cross_reference_acct_id``)
on the ``acct_id`` column via ``__table_args__``. The index is
intentionally non-unique: a single account can legitimately own
multiple cards (primary, authorised user, replacement, etc.), which
is exactly why the VSAM AIX was also non-unique (``NONUNIQUEKEY`` flag
in LISTCAT). The same index is separately created in the Flyway-style
migration ``db/migrations/V2__indexes.sql`` so that schema bootstrap
via ``Base.metadata.create_all()`` and migration-driven deployment
converge on identical relational shape.

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor) — consistent with all sibling entity
  modules in ``src.shared.models``.
* ``Base`` is imported from the package ``__init__.py``
  (``src.shared.models``) so that this entity registers with the
  shared :class:`~sqlalchemy.MetaData` alongside the other CardDemo
  models, enabling unified ``Base.metadata.create_all()`` bootstrap
  and Alembic migration discovery.
* No ``FILLER`` column is mapped — the trailing 14 bytes of COBOL
  padding in the 50-byte VSAM record have no relational counterpart.
  In PostgreSQL, column widths are explicit and trailing padding
  carries no storage or semantic meaning.
* ``cust_id`` and ``acct_id`` are declared as plain ``String(n)``
  columns rather than as SQL-level ``ForeignKey`` references. The FK
  relationships to the :class:`~src.shared.models.customer.Customer`
  and :class:`~src.shared.models.account.Account` tables are real and
  enforced at the service layer, but the declarative FK is deliberately
  NOT wired at the ORM level here to keep this module and its
  referenced peers (``customer.py``, ``account.py``) independently
  loadable. This mirrors the pattern already established in
  ``card.py`` and ``account.py`` — referential integrity for the full
  set of relationships is managed by the Flyway-style migration
  scripts in ``db/migrations/``.
* This entity has no monetary fields — every column is a character
  identifier — so no ``decimal.Decimal`` / ``Numeric`` columns or
  financial-precision considerations apply.
* This entity has no optimistic-concurrency version column because
  cross-reference rows are write-once: created when a card is issued
  (during customer onboarding) and never updated in place. Deletion
  is the only terminal operation (on card closure), which is handled
  atomically by the service layer.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.3 — Feature mapping for F-004 (Account View), F-006 (Card
List), F-011 (Transaction Add) — all consume this cross-reference.
AAP §0.5.1 — File-by-File Transformation Plan
(``card_cross_reference.py`` entry).
AAP §0.5.1 — DB migrations: ``db/migrations/V1__schema.sql`` (CREATE
TABLE), ``db/migrations/V2__indexes.sql`` (account-ID B-tree index),
``db/migrations/V3__seed_data.sql`` (seed from ``cardxref.txt``).
AAP §0.7.1 — Refactoring-Specific Rules (byte-for-byte fidelity to the
COBOL field widths; no enhancement beyond migration requirements).
``app/cpy/CVACT03Y.cpy`` — Original COBOL record layout (source
artifact, retained for traceability).
``app/jcl/XREFFILE.jcl`` — Original VSAM cluster definition
(``RECSZ(50 50)``, ``KEYS(16 0)``).
``app/catlg/LISTCAT.txt`` — IDCAMS catalog entry for
``XREFFILE.XREFAI02.PATH`` alternate index.
``app/data/ASCII/cardxref.txt`` — 50-row seed fixture loaded via
``db/migrations/V3__seed_data.sql``.
"""

from sqlalchemy import Index, String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class CardCrossReference(Base):
    """ORM entity for the ``card_cross_reference`` table (from COBOL ``CARD-XREF-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``card_cross_reference`` table, which replaces the mainframe VSAM KSDS
    ``XREFFILE`` dataset. Each row links exactly one credit card
    (``card_num``) to its owning customer (``cust_id``) and account
    (``acct_id``) — the many-to-many bridge that resolves card-level
    events to the customer and account contexts required by downstream
    business logic.

    Participates in the following online and batch flows:

    * **Online — F-004 Account View** (``COACTVWC`` →
      ``account_service.view()``): the cross-reference is traversed by
      ``acct_id`` (via the ``ix_card_cross_reference_acct_id`` index) to
      discover all cards on the account and assemble the consolidated
      3-entity view (Account + Customer + Card).
    * **Online — F-006 Card List** (``COCRDLIC`` →
      ``card_service.list_by_account()``): the cross-reference is
      traversed by ``acct_id`` to produce the paginated 7-rows-per-page
      card listing for the currently-selected account.
    * **Online — F-011 Transaction Add** (``COTRN02C`` →
      ``transaction_service.add()``): the cross-reference is queried by
      ``card_num`` (primary key) to resolve the user-supplied card
      number to its owning account ID before the new transaction is
      INSERTed — this is the mechanism that guarantees every
      transaction row carries a correct ``acct_id`` value.
    * **Batch — POSTTRAN** (``CBTRN02C`` → ``posttran_job.py``): the
      same card → account resolution is applied in bulk during daily
      transaction posting. Daily-transaction records whose card number
      does not resolve through the cross-reference are rejected with
      reject code 102 as part of the 4-stage validation cascade.

    Attributes
    ----------
    card_num : str
        **Primary key.** 16-character card number (from COBOL
        ``XREF-CARD-NUM``, ``PIC X(16)``). Stored as ``String(16)`` to
        preserve the original character representation byte-for-byte.
        Matches the VSAM cluster key length documented in
        ``app/jcl/XREFFILE.jcl`` (``KEYS(16 0)``) and the PK width of
        the companion :class:`~src.shared.models.card.Card` entity
        (``CVACT02Y.cpy``). There is exactly one cross-reference row
        per issued card — cross-reference rows are created at card
        issuance and deleted only on card closure.
    cust_id : str
        9-character zero-padded customer ID (from COBOL
        ``XREF-CUST-ID``, ``PIC 9(09)``). Stored as ``String(9)``
        rather than numeric to preserve leading zeros from migrated
        VSAM records. References
        :class:`~src.shared.models.customer.Customer.cust_id` at the
        service layer, though no database-level ``ForeignKey`` is
        declared here (see module docstring "Design Notes" — FK
        enforcement is managed by the Flyway migrations).
    acct_id : str
        11-character zero-padded owning account ID (from COBOL
        ``XREF-ACCT-ID``, ``PIC 9(11)``). Stored as ``String(11)``
        rather than numeric to preserve leading zeros from migrated
        VSAM records. References
        :class:`~src.shared.models.account.Account.acct_id` at the
        service layer. **Indexed** by
        ``ix_card_cross_reference_acct_id`` (non-unique B-tree) so that
        "find all cards mapped to account N" queries — issued by the
        F-004 Account View, F-006 Card List, F-011 Transaction Add,
        and POSTTRAN batch stage — remain efficient. This index
        directly replicates the VSAM alternate index
        ``XREFFILE.XREFAI02.PATH`` from the mainframe world (see the
        "Alternate Index Preservation" section of the module
        docstring).
    """

    __tablename__ = "card_cross_references"

    # ------------------------------------------------------------------
    # Non-unique B-tree index on the owning account ID.
    #
    # Replicates the VSAM alternate index XREFFILE.XREFAI02.PATH
    # (NONUNIQUEKEY) from the mainframe, enabling efficient
    # "find all cards mapped to account N" scans issued by:
    #
    #   * F-004 Account View   (COACTVWC → account_service.view())
    #   * F-006 Card List      (COCRDLIC → card_service.list_by_account())
    #   * F-011 Transaction Add (COTRN02C → transaction_service.add())
    #   * POSTTRAN batch stage (CBTRN02C → posttran_job.py)
    #
    # Declared non-unique because a single account can legitimately
    # own multiple cards (primary, authorised user, replacement card,
    # lost/stolen reissue, etc.) — mirroring the NONUNIQUEKEY property
    # of the original VSAM AIX (see app/catlg/LISTCAT.txt).
    # ------------------------------------------------------------------
    __table_args__ = (Index("ix_card_cross_reference_acct_id", "acct_id"),)

    # ------------------------------------------------------------------
    # Primary key: 16-character card number
    # (COBOL ``XREF-CARD-NUM`` PIC X(16))
    #
    # Stored as String(16) to preserve the original character
    # representation byte-for-byte. Matches the VSAM cluster key
    # length (KEYS(16 0)) in app/jcl/XREFFILE.jcl and the PK width
    # of the companion Card entity (CVACT02Y.cpy).
    # ------------------------------------------------------------------
    card_num: Mapped[str] = mapped_column(
        String(16),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Customer ID (COBOL ``XREF-CUST-ID`` PIC 9(09))
    #
    # Stored as String(9) rather than numeric to preserve leading
    # zeros from migrated VSAM records (e.g., customer ID
    # '000000001'). References Customer.cust_id at the service layer;
    # no database-level FK is declared (see module docstring).
    # ------------------------------------------------------------------
    cust_id: Mapped[str] = mapped_column(
        String(9),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Owning account ID (COBOL ``XREF-ACCT-ID`` PIC 9(11))
    #
    # Stored as String(11) rather than numeric to preserve leading
    # zeros from migrated VSAM records (e.g., account ID
    # '00000000001'). References Account.acct_id at the service
    # layer. Indexed by ix_card_cross_reference_acct_id
    # (non-unique B-tree) above — directly replicates the VSAM
    # alternate index XREFFILE.XREFAI02.PATH.
    # ------------------------------------------------------------------
    acct_id: Mapped[str] = mapped_column(
        String(11),
        nullable=False,
    )

    # Note: COBOL ``FILLER PIC X(14)`` — the trailing 14 bytes of
    # padding in the original 50-byte VSAM record — is deliberately
    # NOT mapped. In the relational model, column widths are
    # explicit and trailing padding has no storage or semantic
    # meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        Returns
        -------
        str
            Representation of the form
            ``CardCrossReference(card_num='4111111111111111',
            cust_id='000000001', acct_id='00000000001')``.
        """
        return f"CardCrossReference(card_num={self.card_num!r}, cust_id={self.cust_id!r}, acct_id={self.acct_id!r})"
