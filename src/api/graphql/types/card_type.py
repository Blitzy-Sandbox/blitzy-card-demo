# ============================================================================
# Source: COBOL copybook CVACT02Y.cpy — CARD-RECORD (150 bytes fixed-length
#         record layout, the credit card entity of the CardDemo domain model)
#         BMS symbolic map COCRDSL.CPY — Card detail screen
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
"""Strawberry GraphQL type for the Card entity.

Source: COBOL copybook ``app/cpy/CVACT02Y.cpy`` — ``CARD-RECORD``
(150-byte fixed-length record layout, the credit-card entity of the
CardDemo domain model).
BMS symbolic map: ``app/cpy-bms/COCRDSL.CPY`` — Card detail screen,
which defines the AI/AO field mapping for the COBOL online Card
Detail transaction (``COCRDSLC``, F-007). The screen splits the
stored 10-character expiration date into separate month
(``EXPMONI PIC X(2)``) and year (``EXPYEARI PIC X(4)``) components for
the 3270 terminal, but the canonical on-the-wire form of
``expiration_date`` on this GraphQL type remains the full 10-character
``YYYY-MM-DD`` string — downstream consumers that need month/year
split may compute the slice client-side.

Mainframe-to-Cloud migration: VSAM KSDS ``CARDFILE`` → Aurora PostgreSQL
``cards`` table → GraphQL API via Strawberry.

COBOL to GraphQL Field Mapping
------------------------------
========================  =================  ====================  =====================
COBOL Field               COBOL Type         GraphQL Field         Python Type
========================  =================  ====================  =====================
CARD-NUM                  ``PIC X(16)``      ``card_num``          ``str``
CARD-ACCT-ID              ``PIC 9(11)``      ``acct_id``           ``str`` †
CARD-CVV-CD               ``PIC 9(03)``      ``cvv_cd``            ``str`` †
CARD-EMBOSSED-NAME        ``PIC X(50)``      ``embossed_name``     ``str``
CARD-EXPIRAION-DATE ‡     ``PIC X(10)``      ``expiration_date``   ``str``
CARD-ACTIVE-STATUS        ``PIC X(01)``      ``active_status``     ``str``
FILLER                    ``PIC X(59)``      — (not mapped)        — (COBOL padding)
========================  =================  ====================  =====================

† **Numeric identifiers stored as strings.** The COBOL fields
  ``CARD-ACCT-ID`` (``PIC 9(11)``) and ``CARD-CVV-CD`` (``PIC 9(03)``)
  are numeric in the source record layout, but both are exposed as
  ``str`` on this GraphQL type. This is deliberate: migrated VSAM
  records can legitimately contain leading zeros (e.g., account ID
  ``00000000001`` or CVV ``007``), and representing these as numeric
  types would silently strip the leading zeros at serialization time,
  breaking both the 3-entity join used by ``COACTVWC`` /
  ``account_service.view()`` and the CVV comparison semantics used
  by card-verification flows. Byte-for-byte preservation of the
  original VSAM representation is required by AAP §0.7.1 ("preserve
  all existing functionality exactly as-is") and matches the
  ``String(11)`` / ``String(3)`` column types declared on
  :class:`src.shared.models.card.Card`.

‡ The original COBOL field name ``CARD-EXPIRAION-DATE`` contains a
  historical typo (missing a ``T`` — the name should be
  ``CARD-EXPIRATION-DATE``). The Python field is renamed to the
  corrected spelling ``expiration_date`` because the copybook-to-
  GraphQL mapping is purely semantic (the relational schema has no
  byte-level coupling to COBOL field names). The COBOL typo is
  preserved unchanged in the retained ``app/cpy/CVACT02Y.cpy``
  source artifact (AAP §0.7.1 "do not modify the original COBOL
  source files"). This rename matches the corresponding rename in
  :class:`src.shared.models.card.Card`, which is the source of data
  for :meth:`CardType.from_model`.

Total RECLN: 16 + 11 + 3 + 50 + 10 + 1 + 59 = 150 bytes — matches
the VSAM cluster definition in ``app/jcl/CARDFILE.jcl``
(``RECSZ(150 150)``). The trailing 59-byte FILLER is explicitly
**not** mapped to a GraphQL field; in the relational model, column
widths are explicit and trailing padding has no storage or semantic
meaning.

CVV Code Leading-Zero Preservation — CRITICAL
---------------------------------------------
The ``cvv_cd`` field is a 3-character card-verification value sourced
from the COBOL ``CARD-CVV-CD`` (``PIC 9(03)``) record field. Although
the COBOL source layout declares this as numeric, the column is
exposed here as ``str`` to preserve leading zeros. Concrete examples:

* CVV ``007`` — a valid 3-digit CVV. If exposed as ``int`` it would
  collapse to ``7`` on the wire, breaking every downstream system
  that compares CVV strings literally.
* CVV ``042`` — same reasoning; the ``0`` prefix is semantically
  significant.
* CVV ``123`` — round-trips identically regardless of int/str
  representation, but consistency requires that every CVV uses the
  same representation, so the rule is "always str".

The ``from_model()`` factory below forwards the ORM attribute
unchanged; the ORM column is already declared as ``String(3)``, so
no type coercion is needed and none is performed.

Consumer Resolvers
------------------
Instances of :class:`CardType` are returned by the following
Strawberry resolvers:

* ``src.api.graphql.queries.Query.card(card_num)`` — single card
  detail view, corresponding to COBOL ``COCRDSLC.cbl`` (F-007, Card
  View). The resolver reads a row from the Aurora PostgreSQL
  ``cards`` table and passes the ORM instance to
  :meth:`CardType.from_model` to produce the GraphQL response
  object.
* ``src.api.graphql.queries.Query.cards(...)`` — paginated list of
  cards, corresponding to COBOL ``COCRDLIC.cbl`` (F-006, Card List).
  Default page size is 7 rows (matching the ``OCCURS 7 TIMES``
  declaration in ``COCRDLI.CPY`` — the card-list BMS symbolic map).
  Each row in the result list is produced via
  :meth:`CardType.from_model`.
* ``src.api.graphql.mutations.Mutation.update_card(input)`` —
  corresponding to COBOL ``COCRDUPC.cbl`` (F-008, Card Update,
  optimistic-concurrency via the CICS ``READ UPDATE`` / ``REWRITE``
  pattern). The mutation returns the updated card as a
  :class:`CardType`, again via :meth:`CardType.from_model`.

Design Notes
------------
* **snake_case field names** match the SQLAlchemy model column names
  (``card_num``, ``acct_id``, ``cvv_cd``, ``embossed_name``,
  ``expiration_date``, ``active_status``) and the Aurora PostgreSQL
  DDL column names from ``db/migrations/V1__schema.sql``.
  Strawberry's default ``snake_case → camelCase`` transformation
  will surface these to GraphQL clients as ``cardNum``, ``acctId``,
  ``cvvCd``, ``embossedName``, ``expirationDate``, ``activeStatus``
  — a GraphQL convention standard across the ecosystem.
* **16-character card number as ``str``.** The COBOL ``CARD-NUM``
  field (``PIC X(16)``) is an alphanumeric primary key, not numeric.
  Primary-account numbers (PANs) migrated from production VSAM can
  contain embedded non-digits in the CardDemo sample data, which is
  why the ORM column is ``String(16)`` and the GraphQL field is
  ``str``. Matches the VSAM cluster key length documented in
  ``app/jcl/CARDFILE.jcl`` (``KEYS(16 0)``).
* **Date field as ``str``, not ``date``.** The 10-character
  expiration date is represented as ``str`` (format ``YYYY-MM-DD``)
  to match the COBOL ``PIC X(10)`` source layout and the ORM
  ``String(10)`` column type. Date validation is delegated to the
  ``src.shared.utils.date_utils`` helpers, which preserve the
  ``CSUTLDTC`` validation rules. No ``date`` type coercion occurs at
  either the ORM layer or the GraphQL layer.
* **No FILLER mapping.** The trailing 59 bytes of COBOL padding
  (``FILLER PIC X(59)``) have no relational or GraphQL counterpart
  and are therefore not declared here.
* **No ``version_id`` field.** The SQLAlchemy ORM carries an
  additional ``version_id`` column for optimistic concurrency
  (replacing the CICS ``READ UPDATE`` / ``REWRITE`` protocol from
  ``COCRDUPC.cbl``). This is a server-side implementation detail
  and is deliberately NOT exposed via the GraphQL API — clients
  should not be able to read or manipulate the concurrency token
  directly. The mutation layer handles version mismatches by
  raising a GraphQL error, preserving the AAP §0.7.1 requirement
  that "the optimistic concurrency check in Card Update (F-008)
  must be maintained".
* **No monetary fields.** The card-record layout has no
  ``PIC S9(n)V99`` monetary fields, so this type has no
  :class:`decimal.Decimal` columns. The strict "no floating-point
  arithmetic for financial values" rule from the AAP does not
  require a ``Decimal`` import here; the type follows the same
  discipline by using only ``str`` for every field (no floats, no
  integers, no implicit coercions).
* **Python 3.11+** only. Aligned with the AWS Glue 5.1 runtime
  baseline and the FastAPI / ECS Fargate deployment target.

See Also
--------
* AAP §0.2.3 — Feature mapping for F-006 (Card List), F-007 (Card
  View), and F-008 (Card Update).
* AAP §0.5.1 — File-by-File Transformation Plan (``card_type.py``
  entry).
* AAP §0.7.1 — Refactoring-Specific Rules (preserve existing
  business logic, optimistic concurrency for Card Update).
* AAP §0.7.2 — Security Requirements (CVV treated as a sensitive
  credential).
* :class:`src.shared.models.card.Card` — SQLAlchemy ORM model (the
  source of data for :meth:`CardType.from_model`).
* ``app/cpy/CVACT02Y.cpy`` — Original COBOL record layout (source
  artifact, retained for traceability per AAP §0.7.1).
* ``app/cpy-bms/COCRDSL.CPY`` — BMS symbolic map confirming the
  card-detail screen field layout (note: the screen splits
  ``expiration_date`` into ``EXPMONI``/``EXPYEARI`` for 3270
  rendering, but the stored form — and the GraphQL form — is the
  full 10-character ``YYYY-MM-DD`` string).
* ``src.api.graphql.queries.Query.card`` / ``Query.cards`` — the
  query resolvers that return instances of this type.
* ``src.api.graphql.mutations.Mutation.update_card`` — the
  mutation resolver that returns an updated instance of this type.
"""

# ----------------------------------------------------------------------------
# Imports
# ----------------------------------------------------------------------------
# Strawberry GraphQL — provides the @strawberry.type decorator that
# converts a Python class into a GraphQL schema type. The class body's
# type annotations (``card_num: str``, ``acct_id: str``, ...) become
# GraphQL schema fields; Strawberry reads the annotations at decoration
# time and generates the GraphQL introspection schema accordingly. All
# six fields on CardType are ``str``, so no scalar customisation is
# needed (Strawberry maps Python ``str`` to the GraphQL built-in
# ``String`` scalar by default).
import strawberry

# Card — the SQLAlchemy 2.x ORM model representing a single row of the
# Aurora PostgreSQL ``cards`` table (the relational successor to the
# VSAM ``CARDFILE`` KSDS dataset). Used as the parameter type
# annotation for the ``from_model`` static factory below. The factory
# reads exactly six attributes from this model — one per GraphQL field
# — and deliberately does NOT read the ``version_id`` attribute (the
# optimistic-concurrency counter is a server-side implementation
# detail and must not leak across the ORM/GraphQL boundary).
from src.shared.models.card import Card


# ----------------------------------------------------------------------------
# CardType — Strawberry GraphQL type for CARD-RECORD
# ----------------------------------------------------------------------------
@strawberry.type
class CardType:
    """GraphQL type representing a credit card.

    Maps COBOL ``CARD-RECORD`` (``app/cpy/CVACT02Y.cpy``, 150 bytes) to
    a Strawberry GraphQL schema type consumed by the ``card`` /
    ``cards`` query resolvers in ``src.api.graphql.queries`` and the
    ``update_card`` mutation resolver in ``src.api.graphql.mutations``.

    CVV code (COBOL ``CARD-CVV-CD`` ``PIC 9(03)``) is exposed as
    ``str`` — **never** as ``int`` — to preserve leading zeros (e.g.,
    ``'007'`` would otherwise collapse to the integer ``7``, breaking
    every downstream system that compares CVV strings literally).
    The same rule applies to the 11-character zero-padded account ID
    (``CARD-ACCT-ID`` ``PIC 9(11)``).

    Attributes
    ----------
    card_num : str
        **Primary key.** 16-character card number. Maps to COBOL
        ``CARD-NUM`` (``PIC X(16)``) and to the SQLAlchemy
        ``Card.card_num`` primary-key column (``String(16)``). Stored
        as a string rather than an integer because primary-account
        numbers (PANs) migrated from production VSAM can contain
        embedded non-digits in the CardDemo sample data. Matches the
        VSAM cluster key length documented in ``app/jcl/CARDFILE.jcl``
        (``KEYS(16 0)``) and the BMS symbolic-map field
        ``CARDSIDI PIC X(16)`` from ``COCRDSL.CPY``.
    acct_id : str
        11-character zero-padded owning account ID. Maps to COBOL
        ``CARD-ACCT-ID`` (``PIC 9(11)``) and to the SQLAlchemy
        ``Card.acct_id`` column (``String(11)``). Stored as a string
        rather than an integer to preserve leading zeros from migrated
        VSAM records byte-for-byte (e.g., ``'00000000001'`` must not
        collapse to ``1``). Logical foreign key into the ``accounts``
        table. Indexed at the ORM layer by ``ix_card_acct_id``
        (non-unique B-tree), replicating the VSAM alternate index
        ``CARDFILE.CARDAIX.PATH`` from the mainframe. Matches the BMS
        symbolic-map field ``ACCTSIDI PIC X(11)`` from ``COCRDSL.CPY``.
    cvv_cd : str
        3-character card-verification value (CVV/CVC/CV2). Maps to
        COBOL ``CARD-CVV-CD`` (``PIC 9(03)``) and to the SQLAlchemy
        ``Card.cvv_cd`` column (``String(3)``). **CRITICAL: stored as
        ``str`` — not ``int`` — to preserve leading zeros.** A CVV of
        ``'007'`` is a valid 3-digit code; exposing it as integer ``7``
        would break the AAP §0.7.1 "preserve all existing
        functionality exactly as-is" contract and every downstream
        system that performs literal string comparison during
        card-not-present transaction verification. Not displayed on
        the BMS card-detail screen (``COCRDSL.CPY`` omits the CVV
        field deliberately for 3270 terminal security), and
        GraphQL-level access should be gated by authorization at the
        resolver / middleware layer in production deployments.
    embossed_name : str
        50-character name embossed on the physical card face. Maps to
        COBOL ``CARD-EMBOSSED-NAME`` (``PIC X(50)``) and to the
        SQLAlchemy ``Card.embossed_name`` column (``String(50)``).
        This is the cardholder's name as printed on the card, and may
        differ from the owning :class:`~src.shared.models.customer.Customer`
        full name for joint accounts, authorised-user cards, and
        similar scenarios. Matches the BMS symbolic-map field
        ``CRDNAMEI PIC X(50)`` from ``COCRDSL.CPY``.
    expiration_date : str
        10-character card expiration date. Maps to COBOL
        ``CARD-EXPIRAION-DATE`` (``PIC X(10)``, note the original
        COBOL typo — see module docstring) and to the SQLAlchemy
        ``Card.expiration_date`` column (``String(10)``). ISO-like
        format ``YYYY-MM-DD``. Validation is delegated to
        ``src.shared.utils.date_utils`` helpers, which preserve the
        ``CSUTLDTC`` validation rules. Consulted by the POSTTRAN
        batch stage (``CBTRN02C`` → ``posttran_job.py``) during
        transaction validation. The BMS card-detail screen
        (``COCRDSL.CPY``) splits this field into separate month
        (``EXPMONI PIC X(2)``) and year (``EXPYEARI PIC X(4)``)
        components for 3270 rendering, but the stored and
        GraphQL-exposed form is the full 10-character string.
    active_status : str
        1-character active-status flag. Maps to COBOL
        ``CARD-ACTIVE-STATUS`` (``PIC X(01)``) and to the SQLAlchemy
        ``Card.active_status`` column (``String(1)``). Typical values:
        ``'Y'`` = active (card may be used), ``'N'`` = inactive
        (transactions must be rejected). Consulted by the online
        card-management flows and by the POSTTRAN batch reject-code
        cascade when deciding whether a transaction may be posted.
        Matches the BMS symbolic-map field ``CRDSTCDI PIC X(1)`` from
        ``COCRDSL.CPY``.
    """

    # ------------------------------------------------------------------
    # card_num — 16-char card number
    # (COBOL ``CARD-NUM`` PIC X(16); ORM column ``card_num`` String(16)).
    # GraphQL primary key; unique per card. Stored as str because
    # PANs migrated from VSAM may contain non-digits in sample data.
    # Matches BMS symbolic map field CARDSIDI PIC X(16) from
    # COCRDSL.CPY.
    # ------------------------------------------------------------------
    card_num: str

    # ------------------------------------------------------------------
    # acct_id — 11-char zero-padded owning account ID
    # (COBOL ``CARD-ACCT-ID`` PIC 9(11); ORM column ``acct_id``
    # String(11)). CRITICAL: Stored as str (not int) to preserve
    # leading zeros from migrated VSAM records byte-for-byte.
    # Logical FK into the accounts table. Indexed at the ORM layer
    # by ix_card_acct_id (non-unique B-tree) — replicates the VSAM
    # alternate index CARDFILE.CARDAIX.PATH from the mainframe.
    # Matches BMS symbolic map field ACCTSIDI PIC X(11) from
    # COCRDSL.CPY.
    # ------------------------------------------------------------------
    acct_id: str

    # ------------------------------------------------------------------
    # cvv_cd — 3-char card verification value
    # (COBOL ``CARD-CVV-CD`` PIC 9(03); ORM column ``cvv_cd``
    # String(3)).
    # CRITICAL: Stored as str (NEVER int) to preserve leading zeros.
    # CVV '007' is a valid 3-digit code — exposing it as int would
    # collapse to 7, breaking every downstream system that performs
    # literal string comparison during card-not-present transaction
    # verification. AAP §0.7.1 "preserve all existing functionality
    # exactly as-is".
    # NOTE: Not displayed on the BMS card-detail screen
    # (COCRDSL.CPY deliberately omits the CVV for 3270 terminal
    # security); GraphQL-level access should be gated by
    # authorization at the resolver / middleware layer.
    # ------------------------------------------------------------------
    cvv_cd: str

    # ------------------------------------------------------------------
    # embossed_name — 50-char name embossed on card face
    # (COBOL ``CARD-EMBOSSED-NAME`` PIC X(50); ORM column
    # ``embossed_name`` String(50)).
    # May differ from the owning Customer's full name for joint
    # accounts, authorised-user cards, etc.
    # Matches BMS symbolic map field CRDNAMEI PIC X(50) from
    # COCRDSL.CPY.
    # ------------------------------------------------------------------
    embossed_name: str

    # ------------------------------------------------------------------
    # expiration_date — 10-char expiration date
    # (COBOL ``CARD-EXPIRAION-DATE`` PIC X(10), note historical COBOL
    # typo; ORM column ``expiration_date`` String(10)).
    # YYYY-MM-DD format. Validation delegated to
    # src.shared.utils.date_utils (preserves CSUTLDTC rules).
    # Consulted by POSTTRAN batch transaction validation.
    # The COBOL typo is preserved in the retained app/cpy/CVACT02Y.cpy
    # source artifact; the Python/GraphQL field uses the corrected
    # spelling.
    # BMS card-detail screen (COCRDSL.CPY) splits into EXPMONI X(2)
    # and EXPYEARI X(4) for 3270 rendering, but the canonical stored
    # and GraphQL-exposed form is the full 10-character string.
    # ------------------------------------------------------------------
    expiration_date: str

    # ------------------------------------------------------------------
    # active_status — 1-char active flag
    # (COBOL ``CARD-ACTIVE-STATUS`` PIC X(01); ORM column
    # ``active_status`` String(1)).
    # 'Y' = card is active and may be used.
    # 'N' = card is inactive; transactions must be rejected.
    # Consulted by online card-management flows and POSTTRAN batch
    # reject-code cascade.
    # Matches BMS symbolic map field CRDSTCDI PIC X(1) from
    # COCRDSL.CPY.
    # ------------------------------------------------------------------
    active_status: str

    # ------------------------------------------------------------------
    # NOTE (intentional omissions, do not remove this comment):
    #
    # 1. The COBOL ``FILLER`` field (``PIC X(59)``) — the trailing
    #    59 bytes of padding in the original 150-byte VSAM record —
    #    is NOT declared here. In the relational model, column
    #    widths are explicit and trailing padding has no storage or
    #    semantic meaning.
    #
    # 2. The SQLAlchemy ``Card.version_id`` column
    #    (optimistic-concurrency counter, NOT from COBOL — introduced
    #    as part of the CICS READ UPDATE / REWRITE → SQLAlchemy
    #    migration) is NOT exposed as a GraphQL field. The
    #    concurrency token is a server-side implementation detail
    #    and must not leak across the ORM/GraphQL boundary. Clients
    #    do not need to see, compare, or submit this value; the
    #    mutation layer handles version mismatches by raising a
    #    GraphQL error (StaleDataError → "Record modified by another
    #    user"). This matches the CICS behavior in COCRDUPC.cbl,
    #    where the VSAM RBA/locking state is never exposed to the
    #    terminal user. AAP §0.7.1 explicitly requires that
    #    "the optimistic concurrency check in Card Update (F-008)
    #    must be maintained".
    # ------------------------------------------------------------------

    # ------------------------------------------------------------------
    # from_model — factory method mapping Card → CardType.
    # ------------------------------------------------------------------
    @staticmethod
    def from_model(card: Card) -> "CardType":
        """Convert a SQLAlchemy :class:`Card` row to a GraphQL :class:`CardType`.

        This factory is the single allowed conversion point from the
        ORM layer to the GraphQL layer for card records. It is
        deliberately written to read **exactly six** attributes from
        the input :class:`Card` instance — one per declared GraphQL
        field — and to deliberately NOT read the ``version_id``
        attribute. The optimistic-concurrency token is a server-side
        implementation detail and must not enter the GraphQL response
        path under any circumstances.

        All six string fields (``card_num``, ``acct_id``, ``cvv_cd``,
        ``embossed_name``, ``expiration_date``, ``active_status``) are
        forwarded unchanged from the ORM layer. No string coercion
        (e.g., via ``str(...)``) is performed because the ORM columns
        are already declared as ``String(n)`` and the ``asyncpg`` /
        ``psycopg2`` drivers materialize PostgreSQL ``VARCHAR`` as
        Python ``str`` natively. This preserves the leading-zero
        semantics for the ``acct_id`` (11-digit zero-padded) and
        ``cvv_cd`` (3-digit zero-padded) fields — see the module
        docstring for the rationale.

        Parameters
        ----------
        card : Card
            A SQLAlchemy ORM row fetched from the ``cards`` table.
            The caller is responsible for ensuring the row is not
            ``None``; this factory does not perform null checks
            (query resolvers should return ``None`` directly when the
            card is not found, without invoking this factory).

        Returns
        -------
        CardType
            A newly constructed :class:`CardType` instance containing
            exactly the six fields that constitute the GraphQL card
            contract. The returned instance is a plain Strawberry
            type — it has no database session reference and may be
            safely returned from an async resolver without the
            "detached ORM row" pitfalls that SQLAlchemy would
            otherwise enforce on a session-bound instance.

        Notes
        -----
        ``card.version_id`` is INTENTIONALLY not read. The optimistic-
        concurrency counter (see :class:`~src.shared.models.card.Card`)
        never crosses the ORM/GraphQL boundary; it is consumed
        internally by SQLAlchemy's ``version_id_col`` mapper option
        and surfaced to callers only as a ``StaleDataError`` on
        conflicting writes. See AAP §0.7.1 — "The optimistic
        concurrency check in Card Update (F-008) must be maintained."
        """
        # ------------------------------------------------------------------
        # Explicit field-by-field copy. Do NOT rewrite this as an
        # ``__dict__`` splat, ``**vars(card)`` expansion, or any
        # generic attribute-forwarding idiom — those would inadvertently
        # copy ``version_id`` (and any future internal attributes) into
        # the returned object, violating the server-side-detail
        # isolation described in the module-level and class-level
        # docstrings. Explicit is safer and more auditable.
        # ------------------------------------------------------------------
        return CardType(
            # COBOL CARD-NUM (PIC X(16)) — 16-char card number PK.
            card_num=card.card_num,
            # COBOL CARD-ACCT-ID (PIC 9(11)) — 11-char zero-padded
            # string, leading zeros preserved (not int).
            acct_id=card.acct_id,
            # COBOL CARD-CVV-CD (PIC 9(03)) — 3-char CVV. CRITICAL:
            # str, not int — '007' must not collapse to 7.
            cvv_cd=card.cvv_cd,
            # COBOL CARD-EMBOSSED-NAME (PIC X(50)) — name on card face.
            embossed_name=card.embossed_name,
            # COBOL CARD-EXPIRAION-DATE (PIC X(10), note COBOL typo) —
            # Python/GraphQL field uses corrected spelling
            # 'expiration_date'.
            expiration_date=card.expiration_date,
            # COBOL CARD-ACTIVE-STATUS (PIC X(01)) — 'Y' or 'N'.
            active_status=card.active_status,
            # NOTE: card.version_id is INTENTIONALLY not accessed
            # here. The optimistic-concurrency counter never crosses
            # the ORM/GraphQL boundary — it is consumed only
            # internally by SQLAlchemy's version_id_col feature and
            # surfaced externally only as a StaleDataError on
            # conflicting writes.
        )
