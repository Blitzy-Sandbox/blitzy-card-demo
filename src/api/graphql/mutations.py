# ============================================================================
# Source: app/cbl/COACTUPC.cbl (Account Update — Feature F-005, ~4,236 lines)
#         app/cbl/COCRDUPC.cbl (Card Update    — Feature F-008, ~1,560 lines)
#         app/cbl/COTRN02C.cbl (Transaction Add — Feature F-011,   ~783 lines)
#         app/cbl/COBIL00C.cbl (Bill Payment    — Feature F-012,   ~572 lines)
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
"""GraphQL mutation resolvers.

Converted from four write-oriented online CICS COBOL programs:

* ``COACTUPC.cbl`` (Account Update, F-005) — ``update_account`` mutation.
* ``COCRDUPC.cbl`` (Card Update, F-008)    — ``update_card`` mutation.
* ``COTRN02C.cbl`` (Transaction Add, F-011) — ``add_transaction`` mutation.
* ``COBIL00C.cbl`` (Bill Payment, F-012)    — ``pay_bill`` mutation.

The resolvers map the following CICS patterns to SQLAlchemy operations
via the service layer (``src.api.services``):

* ``EXEC CICS READ FILE(...) UPDATE`` → ``SELECT`` + in-memory mutation.
* ``EXEC CICS REWRITE FILE(...)``     → dirty-tracked SQLAlchemy UPDATE
  (emitted automatically on ``session.flush()`` / ``session.commit()``).
* ``EXEC CICS WRITE FILE(...)``       → ``session.add(<orm_instance>)``
  followed by ``session.flush()`` / ``session.commit()``.
* ``EXEC CICS SYNCPOINT ROLLBACK``    → ``session.rollback()``.
* ``STARTBR`` + ``READPREV`` + ``ENDBR`` (auto-ID generation by
  browsing to end of VSAM cluster) → ``SELECT max(tran_id)`` +
  ``ORDER BY tran_id DESC LIMIT 1`` (both executed by
  ``TransactionService``/``BillService`` for tran_id allocation).

Design Notes
------------
* **Financial precision** — All monetary fields (``credit_limit``,
  ``cash_credit_limit``, ``amount``) are typed as Python
  :class:`decimal.Decimal` throughout both the input types and the
  resolver bodies. No ``float`` conversion is permitted anywhere on
  this module path — this preserves the COBOL ``PIC S9(n)V99``
  fixed-point semantics byte-for-byte (AAP §0.7.2 Financial
  Precision).

* **Dual-write atomicity** — The two "dual-write" mutations
  (``update_account`` and ``pay_bill``) delegate their atomicity
  guarantees to the service layer, which executes the INSERT plus
  UPDATE (Bill Payment) or Account UPDATE plus Customer UPDATE
  (Account Update) inside a single SQLAlchemy session. A single
  ``session.commit()`` makes both writes durable atomically; a single
  ``session.rollback()`` discards both on exception. This mirrors the
  implicit CICS SYNCPOINT at end-of-transaction and the explicit
  ``EXEC CICS SYNCPOINT ROLLBACK`` on the COBOL error path.

* **Optimistic concurrency** — The ``update_card`` mutation inherits
  optimistic-concurrency enforcement from
  :meth:`CardService.update_card`, which leverages SQLAlchemy's
  ``version_id_col`` feature on :class:`~src.shared.models.card.Card`.
  A concurrent modification between this resolver's SELECT and its
  UPDATE yields an error response carrying the COBOL-authentic
  ``'Record changed by some one else. Please review'`` error string;
  the resolver surfaces this as a GraphQL error so the client can
  retry after refreshing.

* **Service layer delegation** — Every resolver instantiates the
  appropriate service class (``AccountService``, ``CardService``,
  ``TransactionService``, ``BillService``) with the per-request
  :class:`~sqlalchemy.ext.asyncio.AsyncSession` pulled from
  Strawberry's :class:`~strawberry.types.Info` context. The resolver
  itself contains no direct SQL or ORM manipulation — it is solely
  responsible for adapting between Strawberry input/output types and
  the Pydantic request/response contracts that the service layer
  already honors for the REST surface (``src.api.routers``). This
  preserves the "one business-logic implementation, two protocol
  surfaces" contract established by the GraphQL package docstring
  (``src/api/graphql/__init__.py``).

* **Full-entity return** — All four mutations return a full
  Strawberry entity type (``AccountType``, ``CardType``, or
  ``TransactionType``), not a minimal confirmation payload. When the
  service layer returns a minimal Pydantic response (e.g.,
  :class:`TransactionAddResponse`, :class:`BillPaymentResponse`),
  the resolver re-fetches the freshly-persisted ORM row and maps it
  via the corresponding ``from_model`` static method on the
  Strawberry type. This gives the GraphQL client the complete
  post-mutation state of the affected entity without requiring a
  follow-up query round-trip.

* **Error surfacing** — Business-logic failures (not-found,
  stale-data, validation, zero-balance, etc.) are surfaced as
  GraphQL errors via ``raise Exception(...)`` from the resolver.
  The Strawberry executor catches these and adds them to the
  ``errors`` field of the GraphQL response, alongside the ``data``
  field which will be ``null`` for the failed mutation. This mirrors
  the COBOL UX of displaying an error message on the BMS screen
  while leaving the business entity unchanged.

Source: ``app/cbl/COACTUPC.cbl``, ``app/cbl/COCRDUPC.cbl``,
``app/cbl/COTRN02C.cbl``, ``app/cbl/COBIL00C.cbl``, and their
associated data copybooks (``app/cpy/CVACT01Y.cpy``,
``app/cpy/CVACT02Y.cpy``, ``app/cpy/CVTRA05Y.cpy``,
``app/cpy/CVACT03Y.cpy``) and BMS symbolic maps
(``app/cpy-bms/COACTUP.CPY``, ``app/cpy-bms/COCRDUP.CPY``,
``app/cpy-bms/COTRN02.CPY``, ``app/cpy-bms/COBIL00.CPY``)
— Mainframe-to-Cloud migration (AAP §0.5.1).
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from decimal import Decimal
from typing import Optional

import strawberry
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession
from strawberry.types import Info

from src.api.database import get_async_session  # noqa: F401  — public re-export target
from src.api.graphql.types.account_type import AccountType
from src.api.graphql.types.card_type import CardType
from src.api.graphql.types.transaction_type import TransactionType
from src.api.services.account_service import AccountService
from src.api.services.bill_service import BillService
from src.api.services.card_service import CardService
from src.api.services.transaction_service import TransactionService
from src.shared.models.account import Account
from src.shared.models.card import Card
from src.shared.models.card_cross_reference import CardCrossReference
from src.shared.models.transaction import Transaction
from src.shared.schemas.account_schema import AccountUpdateRequest
from src.shared.schemas.bill_schema import BillPaymentRequest
from src.shared.schemas.card_schema import CardUpdateRequest
from src.shared.schemas.transaction_schema import TransactionAddRequest

# ----------------------------------------------------------------------------
# Module-level logger
#
# The CardDemo application uses structured JSON logging per AAP §0.7.2
# Monitoring Requirements; the top-level FastAPI application configures
# the root handler and its JSON formatter. Named loggers propagate to
# the root handler automatically — GraphQL mutations therefore emit
# structured events visible in CloudWatch alongside the REST router and
# service-layer logs.
# ----------------------------------------------------------------------------
logger: logging.Logger = logging.getLogger(__name__)


# ============================================================================
# Module-private helper functions
# ============================================================================
# These helpers factor out small pieces of logic used across multiple
# resolvers: session acquisition from Strawberry context, ISO-date
# segmentation, SSN / phone parsing, and transaction-id extraction from
# the bill-payment success message. Keeping them at module scope (rather
# than inside the :class:`Mutation` body) makes them trivially unit-
# testable without needing a Strawberry executor.
# ============================================================================


def _get_session(info: Info) -> AsyncSession:
    """Extract the async SQLAlchemy session from Strawberry's Info context.

    Every resolver receives a :class:`~strawberry.types.Info` object
    whose ``context`` attribute is the dict supplied by the FastAPI
    adapter's ``context_getter`` callback (see ``src.api.main`` for
    where the adapter is wired). By convention established in the
    GraphQL package (``src/api/graphql/__init__.py``) the FastAPI
    adapter places the active :class:`AsyncSession` under the
    ``"db"`` key of the context dict — this session was obtained via
    :func:`src.api.database.get_async_session` and will be committed
    on successful request completion or rolled back on exception,
    matching the CICS SYNCPOINT semantics from the original COBOL
    programs.

    Parameters
    ----------
    info : Info
        The Strawberry resolver context object passed as the first
        argument to every resolver.

    Returns
    -------
    AsyncSession
        The request-scoped async SQLAlchemy session.

    Raises
    ------
    RuntimeError
        If ``info.context`` does not contain a ``"db"`` key or the
        value is not an :class:`AsyncSession`. This indicates a
        mis-configured FastAPI + Strawberry integration and should
        never occur in production; surfacing a clear error here is
        preferable to silently failing inside a service call.
    """
    context: object = info.context
    if not isinstance(context, dict):
        raise RuntimeError(
            "GraphQL Info.context is expected to be a dict supplied by "
            "the FastAPI + Strawberry integration (see src/api/main.py). "
            f"Got: {type(context).__name__}."
        )
    session = context.get("db")
    if not isinstance(session, AsyncSession):
        raise RuntimeError(
            "GraphQL Info.context['db'] is expected to be an "
            "sqlalchemy.ext.asyncio.AsyncSession (supplied by the "
            "FastAPI dependency chain via get_async_session). "
            f"Got: {type(session).__name__}."
        )
    return session


def _split_iso_date(iso_date: str) -> tuple[str, str, str]:
    """Split a ``YYYY-MM-DD`` ISO date into (year, month, day) segments.

    The legacy COBOL Account Update flow captures dates as three
    independent BMS fields (``*YEARI``, ``*MONI``, ``*DAYI``) because
    the 3270 terminal could not natively support a composite date
    widget. The modernized GraphQL ``AccountUpdateInput`` accepts a
    single ISO-8601-formatted string for user ergonomics, but the
    downstream :class:`AccountUpdateRequest` Pydantic schema still
    requires the segmented ``*_year`` / ``*_month`` / ``*_day``
    fields (one per date). This helper performs the deterministic
    split in the resolver boundary.

    Parameters
    ----------
    iso_date : str
        A date string in ``YYYY-MM-DD`` format (e.g., ``"2027-12-31"``).
        Whitespace-only or empty strings yield ``("", "", "")``.

    Returns
    -------
    tuple[str, str, str]
        ``(year, month, day)`` — a 4-char year segment and two 2-char
        month/day segments. The caller is responsible for downstream
        validation: Pydantic will reject short or non-numeric segments
        at the ``AccountUpdateRequest`` boundary, carrying the COBOL
        error message ``'Invalid date'`` on the resulting response.

    Examples
    --------
    >>> _split_iso_date("2027-12-31")
    ('2027', '12', '31')
    >>> _split_iso_date("")
    ('', '', '')
    >>> _split_iso_date("garbage")
    ('', '', '')
    """
    if not iso_date or not iso_date.strip():
        return ("", "", "")

    parts: list[str] = iso_date.split("-")
    if len(parts) != 3:
        return ("", "", "")

    year, month, day = parts[0], parts[1], parts[2]
    # Preserve input byte-for-byte; Pydantic ``AccountUpdateRequest``
    # handles the length and digit-only checks.
    return (year, month, day)


def _split_composite_ssn(ssn: str) -> tuple[str, str, str]:
    """Split a US SSN formatted as ``NNN-NN-NNNN`` into its three parts.

    :class:`~src.shared.schemas.account_schema.AccountViewResponse`
    exposes ``customer_ssn`` as the composite 11-character string
    ``"NNN-NN-NNNN"`` (3 digits, hyphen, 2 digits, hyphen, 4 digits).
    :class:`~src.shared.schemas.account_schema.AccountUpdateRequest`,
    however, requires the three parts as three independent fields
    (``customer_ssn_part1``, ``customer_ssn_part2``,
    ``customer_ssn_part3``) because that is how the COBOL
    ``COACTUP.CPY`` BMS symbolic map captured the value. This helper
    performs the reverse composition so the resolver can round-trip
    unchanged-by-the-user SSN values from the view response back into
    the update request.

    Parameters
    ----------
    ssn : str
        The composite SSN string in ``NNN-NN-NNNN`` form, or an
        empty string.

    Returns
    -------
    tuple[str, str, str]
        The three segments ``(part1, part2, part3)``. If the input
        does not match the expected pattern the function returns
        ``("", "", "")`` — Pydantic will reject those at the
        :class:`AccountUpdateRequest` boundary.

    Examples
    --------
    >>> _split_composite_ssn("123-45-6789")
    ('123', '45', '6789')
    >>> _split_composite_ssn("")
    ('', '', '')
    """
    if not ssn:
        return ("", "", "")
    parts: list[str] = ssn.split("-")
    if len(parts) != 3:
        return ("", "", "")
    return (parts[0], parts[1], parts[2])


def _split_composite_phone(phone: str) -> tuple[str, str, str]:
    """Split a US phone ``(AAA)BBB-CCCC`` into (area, prefix, line).

    Matches the inverse of the composition performed inside
    :class:`AccountViewResponse`, which reassembles the three BMS
    input fields into the single 13-character formatted phone
    string. The COBOL source field order is area-code (3 digits),
    prefix (3 digits), line (4 digits), matching the North American
    Numbering Plan segments.

    Parameters
    ----------
    phone : str
        The composite phone string in ``(AAA)BBB-CCCC`` form or an
        empty string.

    Returns
    -------
    tuple[str, str, str]
        The three segments ``(area, prefix, line)``. If the input
        does not match the expected pattern the function returns
        ``("", "", "")``.

    Examples
    --------
    >>> _split_composite_phone("(415)555-0123")
    ('415', '555', '0123')
    >>> _split_composite_phone("")
    ('', '', '')
    """
    if not phone:
        return ("", "", "")
    # The exact format is "(AAA)BBB-CCCC" — 13 characters with fixed
    # positions. Perform defensive slicing rather than a regex to keep
    # the helper branch-free on the happy path.
    if len(phone) != 13 or phone[0] != "(" or phone[4] != ")" or phone[8] != "-":
        return ("", "", "")
    area: str = phone[1:4]
    prefix: str = phone[5:8]
    line: str = phone[9:13]
    return (area, prefix, line)


def _extract_tran_id_from_message(message: Optional[str]) -> str:  # noqa: UP045  # schema requires typing.Optional
    """Extract the new transaction ID from the bill-payment success message.

    :meth:`BillService.pay_bill` returns a
    :class:`~src.shared.schemas.bill_schema.BillPaymentResponse`
    whose ``message`` field uses the format ``"Payment successful.
    Your Transaction ID is {tran_id}."``. The GraphQL surface prefers
    returning the full :class:`TransactionType` (rather than the
    minimal BillPaymentResponse), so the resolver needs the
    transaction ID to perform the follow-up ORM re-query. Rather than
    alter the service-layer return type, we parse the ID from the
    message. The message format is a constant in
    :mod:`src.api.services.bill_service` (``_MSG_PAYMENT_SUCCESS_FMT``)
    and is covered by the service-layer test suite, so this parse is
    stable.

    Parameters
    ----------
    message : Optional[str]  # noqa: UP045  # schema requires typing.Optional
        The ``message`` field of a successful
        :class:`BillPaymentResponse`.

    Returns
    -------
    str
        The zero-padded 16-character ``tran_id``. Empty string if
        the message does not match the expected format.
    """
    if not message:
        return ""
    # Marker is exact; the tran_id is the substring between the
    # marker and the trailing period.
    marker: str = "Transaction ID is "
    marker_idx: int = message.find(marker)
    if marker_idx < 0:
        return ""
    after_marker: str = message[marker_idx + len(marker) :]
    # Strip trailing period and any whitespace.
    return after_marker.rstrip(". \t\r\n")


# ============================================================================
# Strawberry @strawberry.input types (GraphQL input schema)
# ============================================================================
# The four input classes below define the GraphQL-side request payload
# for each mutation. They map directly to BMS symbolic maps from the
# original CICS programs — see the class docstrings for the exact
# one-to-one field correspondence. All monetary fields are typed as
# :class:`decimal.Decimal` (AAP §0.7.2), and all optional string fields
# follow the COBOL convention of empty-string-means-unchanged.
# ============================================================================


@strawberry.input(description="Input payload for the update_account mutation.")
class AccountUpdateInput:
    """Input type for the :func:`Mutation.update_account` resolver.

    Mirrors the user-editable fields of the Account Update screen
    (``COACTUP`` BMS mapset, see ``app/cpy-bms/COACTUP.CPY``). The
    original COBOL program exposed ~60 editable fields (account
    identity + full customer profile). The GraphQL contract
    intentionally surfaces only the **six** fields that the Account
    Update use-case routinely changes; all other downstream
    :class:`AccountUpdateRequest` fields are preserved from the
    current persisted state of the account (fetched via
    :meth:`AccountService.get_account_view` at the start of the
    resolver — a "read-then-update" pattern that preserves the full
    40-field Pydantic contract while keeping the GraphQL surface
    narrow).

    The six user-editable fields are:

    * ``acct_id`` — required 11-digit numeric account identifier;
      cannot be changed (used only for lookup).
    * ``active_status`` — ``Y`` or ``N`` (PIC X(01)).
    * ``credit_limit`` — ``PIC S9(10)V99`` → :class:`Decimal`.
    * ``cash_credit_limit`` — ``PIC S9(10)V99`` → :class:`Decimal`.
    * ``open_date`` — ``YYYY-MM-DD`` ISO date string.
    * ``expiration_date`` — ``YYYY-MM-DD`` ISO date string.
    """

    acct_id: str = strawberry.field(
        description=(
            "11-digit numeric account identifier (COBOL PIC 9(11), "
            "CVACT01Y.cpy ACCT-ID). This field serves as the primary "
            "key for the UPDATE and is never modified itself."
        ),
    )
    active_status: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Account active status flag (COBOL PIC X(01) "
            "ACCT-ACTIVE-STATUS). Must be exactly 'Y' or 'N' when "
            "provided. ``None`` means the field should be preserved "
            "from its current persisted value."
        ),
    )
    credit_limit: Optional[Decimal] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Overall credit limit — COBOL PIC S9(10)V99 → Decimal. "
            "Must be non-negative when provided. CRITICAL: Decimal "
            "type, never float, per AAP §0.7.2 Financial Precision."
        ),
    )
    cash_credit_limit: Optional[Decimal] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Cash advance credit sub-limit — COBOL PIC S9(10)V99 → "
            "Decimal. Must be non-negative when provided. CRITICAL: "
            "Decimal type, never float."
        ),
    )
    open_date: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Account open date in YYYY-MM-DD ISO-8601 format. The "
            "resolver splits this into the three segmented Pydantic "
            "fields (open_date_year / _month / _day) for the "
            "downstream AccountUpdateRequest."
        ),
    )
    expiration_date: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Account expiration date in YYYY-MM-DD ISO-8601 format. "
            "Split into expiration_date_year / _month / _day by the "
            "resolver before calling the service layer."
        ),
    )


@strawberry.input(description="Input payload for the update_card mutation.")
class CardUpdateInput:
    """Input type for the :func:`Mutation.update_card` resolver.

    Mirrors the user-editable fields of the Card Update BMS mapset
    (``COCRDUP``, see ``app/cpy-bms/COCRDUP.CPY``). The downstream
    :class:`CardUpdateRequest` also requires ``account_id`` and
    ``expiry_day`` — these are not exposed on the GraphQL surface
    because:

    * ``account_id`` is deterministically derived from the existing
      card record (card→account relationship) — the resolver reads
      the card first, obtains the account_id, then submits the
      update.
    * ``expiry_day`` is always ``"01"`` in the COBOL convention:
      ``CARD-EXPIRAION-DATE`` (sic) in ``CVACT02Y.cpy`` is stored
      as a 10-character YYYY-MM-DD but the day component is a
      constant because card expiration is specified at month
      granularity on the embossed plastic. The resolver defaults
      ``expiry_day`` to ``"01"`` when forwarding to the service
      layer.
    """

    card_num: str = strawberry.field(
        description=(
            "16-character card number (COBOL PIC X(16), "
            "CVACT02Y.cpy CARD-NUM). Primary key for the UPDATE "
            "and never changes."
        ),
    )
    embossed_name: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Name embossed on the plastic — COBOL PIC X(50) "
            "CARD-EMBOSSED-NAME. CardService normalizes case and "
            "trimming per COCRDUPC.cbl behavior."
        ),
    )
    active_status: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("Card active-status flag — COBOL PIC X(01) CARD-ACTIVE-STATUS. Must be 'Y' or 'N' when provided."),
    )
    expiration_month: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("2-digit expiration month (01–12) — COBOL PIC X(02) substring of CARD-EXPIRAION-DATE."),
    )
    expiration_year: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("4-digit expiration year — COBOL PIC X(04) substring of CARD-EXPIRAION-DATE."),
    )


@strawberry.input(description="Input payload for the add_transaction mutation.")
class TransactionAddInput:
    """Input type for the :func:`Mutation.add_transaction` resolver.

    Mirrors the Transaction Add BMS mapset (``COTRN02``, see
    ``app/cpy-bms/COTRN02.CPY``). Either ``card_num`` **or**
    ``acct_id`` must be supplied (but not necessarily both); the
    resolver resolves the missing half via the
    :class:`CardCrossReference` table — the Python equivalent of the
    COBOL ``CCXREF`` VSAM cross-reference file read by
    ``COTRN02C.cbl`` at lines 576 and 609.

    The transaction ID is **not** supplied by the client; the
    service layer generates it by selecting ``max(tran_id) + 1`` and
    zero-padding to 16 characters. This mirrors the COBOL pattern of
    ``EXEC CICS STARTBR`` + ``READPREV`` + ``ADD 1 TO`` at
    ``COTRN02C.cbl`` line 442+.
    """

    type_cd: str = strawberry.field(
        description=(
            "2-character transaction type code — COBOL PIC X(02) TRAN-TYPE-CD. Must match a row in transaction_types."
        ),
    )
    cat_cd: str = strawberry.field(
        description=(
            "4-digit transaction category code — COBOL PIC 9(04) "
            "TRAN-CAT-CD. Must match a row in transaction_categories "
            "for the given type_cd."
        ),
    )
    source: str = strawberry.field(
        description=("Transaction source identifier — COBOL PIC X(10) TRAN-SOURCE (e.g., 'POS TERM' or 'ONLINE')."),
    )
    description: str = strawberry.field(
        description=(
            "Free-form transaction description — COBOL PIC X(100) "
            "TRAN-DESC (truncated to 60 chars by the service-layer "
            "Pydantic contract)."
        ),
    )
    amount: Decimal = strawberry.field(
        description=(
            "Transaction amount — COBOL PIC S9(09)V99 → Decimal. "
            "Must be strictly greater than zero. CRITICAL: Decimal "
            "type, never float."
        ),
    )
    card_num: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "16-character card number — COBOL PIC X(16) "
            "TRAN-CARD-NUM. Either card_num or acct_id must be "
            "supplied; the other is resolved via CardCrossReference."
        ),
    )
    acct_id: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "11-digit account identifier. Alternative to card_num; "
            "the resolver looks up the primary card for this account "
            "via CardCrossReference."
        ),
    )
    merchant_id: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("9-digit merchant identifier — COBOL PIC 9(09) TRAN-MERCHANT-ID."),
    )
    merchant_name: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("Merchant business name — COBOL PIC X(50) TRAN-MERCHANT-NAME."),
    )
    merchant_city: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("Merchant city name — COBOL PIC X(50) TRAN-MERCHANT-CITY."),
    )
    merchant_zip: Optional[str] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=("Merchant postal code — COBOL PIC X(10) TRAN-MERCHANT-ZIP."),
    )


@strawberry.input(description="Input payload for the pay_bill mutation.")
class BillPaymentInput:
    """Input type for the :func:`Mutation.pay_bill` resolver.

    Mirrors the Bill Payment BMS mapset (``COBIL00``, see
    ``app/cpy-bms/COBIL00.CPY``). The COBOL program
    ``COBIL00C.cbl`` only supported a "pay full balance" operation
    (the only editable field on the BMS map was the single-char
    ``CONFIRMI`` toggle). The modernized API generalizes this to
    accept an arbitrary positive payment amount: if the client omits
    ``amount``, the resolver preserves COBOL behavior by reading the
    current balance first and passing it as the payment amount.
    """

    acct_id: str = strawberry.field(
        description=("11-digit account identifier — COBOL PIC 9(11), maps to COBIL00.CPY ACTIDINI field (PIC X(11))."),
    )
    amount: Optional[Decimal] = strawberry.field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Payment amount — Decimal, must be strictly positive. "
            "When omitted, the resolver reads the current account "
            "balance and substitutes it (preserving the COBOL 'pay "
            "in full' semantics of COBIL00C.cbl). CRITICAL: Decimal "
            "type, never float."
        ),
    )


# ============================================================================
# Root GraphQL Mutation type
# ============================================================================
# The :class:`Mutation` class is the entry point that gets stitched
# into the top-level Strawberry schema (``src.api.graphql.schema``).
# It aggregates the four write-oriented resolvers, each of which
# delegates business logic to the corresponding service class
# (``AccountService``, ``CardService``, ``TransactionService``,
# ``BillService``) from :mod:`src.api.services`.
# ============================================================================


@strawberry.type(description="Root GraphQL Mutation type for the CardDemo API.")
class Mutation:
    """Root GraphQL Mutation type.

    Aggregates all write/update resolvers from the CardDemo API,
    corresponding to the CICS WRITE / REWRITE data-modification
    patterns across the four online COBOL programs covered by
    AAP §0.5.1:

    * :meth:`update_account` — replaces ``COACTUPC.cbl`` dual-write
      (Account + Customer) with SYNCPOINT ROLLBACK semantics.
    * :meth:`update_card`    — replaces ``COCRDUPC.cbl`` READ UPDATE
      / REWRITE with optimistic concurrency via ``version_id``.
    * :meth:`add_transaction` — replaces ``COTRN02C.cbl`` auto-ID
      generation (STARTBR + READPREV + ADD 1) and CCXREF lookup.
    * :meth:`pay_bill`       — replaces ``COBIL00C.cbl`` atomic
      dual-write (Transaction INSERT + Account.curr_bal UPDATE).

    All four resolvers return the full GraphQL entity type
    (``AccountType``, ``CardType``, or ``TransactionType``) — this
    gives GraphQL clients the complete post-mutation state of the
    affected entity in a single round-trip, eliminating the need
    for a follow-up query.
    """

    # ------------------------------------------------------------------
    # update_account — COACTUPC.cbl → Account + Customer dual-write
    # ------------------------------------------------------------------
    @strawberry.mutation(  # type: ignore[untyped-decorator]  # strawberry.mutation returns Any
        description=(
            "Update mutable fields of an existing account. Replaces "
            "COACTUPC.cbl dual-write (Account + Customer) with "
            "SYNCPOINT ROLLBACK semantics — both writes succeed "
            "atomically or neither does."
        ),
    )
    async def update_account(
        self,
        info: Info,
        account_input: AccountUpdateInput,
    ) -> AccountType:
        """Update an existing account record (Feature F-005).

        This resolver performs a **read-then-update** pattern to
        preserve the full 40-field
        :class:`~src.shared.schemas.account_schema.AccountUpdateRequest`
        contract while keeping the GraphQL input surface narrow:

        1. Read the existing account via
           :meth:`AccountService.get_account_view` to obtain the
           current values for all 31 fields (account + customer).
        2. Split the composite customer SSN (``NNN-NN-NNNN``) into
           three parts, the two phone numbers (``(AAA)BBB-CCCC``)
           into three parts each, and all date fields
           (``YYYY-MM-DD``) into year/month/day segments.
        3. Overlay the six GraphQL-editable fields
           (``active_status``, ``credit_limit``,
           ``cash_credit_limit``, ``open_date``, ``expiration_date``)
           on top of the existing values.
        4. Construct the 40-field :class:`AccountUpdateRequest`.
        5. Delegate to :meth:`AccountService.update_account`, which
           performs the atomic Account + Customer dual-write.
        6. If the service returns an ``error_message``, surface it
           as a GraphQL error (the GraphQL ``data`` field becomes
           ``null`` and the ``errors`` field carries the message).
        7. Re-fetch the freshly-persisted ORM
           :class:`~src.shared.models.account.Account` row and
           return it as an :class:`AccountType` so the client gets
           a fully-populated response including the post-update
           version_id.

        CRITICAL: All monetary fields (``credit_limit``,
        ``cash_credit_limit``, and the customer balance fields)
        use :class:`decimal.Decimal` throughout — never float.

        CRITICAL: The dual-write atomicity is provided by the
        service layer, which executes both the Account UPDATE and
        the Customer UPDATE under a single
        :class:`AsyncSession.commit` call. On exception the session
        is rolled back before the mutation returns.

        Parameters
        ----------
        info : Info
            Strawberry resolver context; carries the async SQLAlchemy
            session under ``context["db"]``.
        account_input : AccountUpdateInput
            GraphQL input payload — six user-editable fields plus
            the required ``acct_id`` lookup key.

        Returns
        -------
        AccountType
            The post-update state of the account — all 12
            ``AccountType`` fields populated from the persisted ORM
            row.

        Raises
        ------
        Exception
            Business-rule failures (account not found, invalid
            acct_id, validation errors) surface as GraphQL errors.
            The service layer's error_message field is used as the
            exception message for full fidelity with the COBOL UX.
        """
        session: AsyncSession = _get_session(info)
        account_service: AccountService = AccountService(session)

        # ----- Step 1: Fetch current state -----
        # Replaces COACTUPC.cbl lines ~1300-1500: READ XREF + READ
        # ACCTDAT + READ CUSTDAT paragraphs. AccountService performs
        # all three reads and composes the result.
        current: object = await account_service.get_account_view(account_input.acct_id)
        if getattr(current, "error_message", None):
            error_msg: Optional[str] = getattr(current, "error_message", None)  # noqa: UP045  # schema requires typing.Optional
            logger.warning(
                "update_account: get_account_view returned error: %s",
                error_msg,
            )
            raise Exception(error_msg or "Account lookup failed")

        # ----- Step 2: Split composite fields and merge with GraphQL inputs -----
        # AccountUpdateRequest requires 39 segmented fields (dates, SSN,
        # phones). AccountViewResponse exposes these as composite strings.
        # We reverse the composition here, substituting any GraphQL-
        # supplied values on top.

        # Select open_date source: GraphQL input wins, else existing value.
        open_date_source: str = (
            account_input.open_date if account_input.open_date else getattr(current, "open_date", "")
        )
        open_year, open_month, open_day = _split_iso_date(open_date_source)

        # Select expiration_date source: GraphQL input wins, else existing value.
        exp_date_source: str = (
            account_input.expiration_date if account_input.expiration_date else getattr(current, "expiration_date", "")
        )
        exp_year, exp_month, exp_day = _split_iso_date(exp_date_source)

        # reissue_date, customer_dob are not editable via GraphQL — always
        # pass through the existing persisted value (read-then-update).
        reissue_year, reissue_month, reissue_day = _split_iso_date(getattr(current, "reissue_date", ""))
        dob_year, dob_month, dob_day = _split_iso_date(getattr(current, "customer_dob", ""))

        # Split composite customer SSN and phone numbers.
        ssn_part1, ssn_part2, ssn_part3 = _split_composite_ssn(getattr(current, "customer_ssn", ""))
        ph1_area, ph1_prefix, ph1_line = _split_composite_phone(getattr(current, "customer_phone_1", ""))
        ph2_area, ph2_prefix, ph2_line = _split_composite_phone(getattr(current, "customer_phone_2", ""))

        # Resolve monetary fields — GraphQL input wins, else existing
        # persisted Decimal value (already a Decimal in
        # AccountViewResponse, not a float).
        credit_limit_val: Decimal = (
            account_input.credit_limit
            if account_input.credit_limit is not None
            else getattr(current, "credit_limit", Decimal("0.00"))
        )
        cash_credit_limit_val: Decimal = (
            account_input.cash_credit_limit
            if account_input.cash_credit_limit is not None
            else getattr(current, "cash_credit_limit", Decimal("0.00"))
        )

        # active_status — GraphQL input wins, else existing value.
        active_status_val: str = (
            account_input.active_status
            if account_input.active_status is not None
            else getattr(current, "active_status", "")
        )

        # ----- Step 3: Construct the 39-field AccountUpdateRequest -----
        try:
            update_request: AccountUpdateRequest = AccountUpdateRequest(
                account_id=account_input.acct_id,
                active_status=active_status_val,
                open_date_year=open_year,
                open_date_month=open_month,
                open_date_day=open_day,
                credit_limit=credit_limit_val,
                expiration_date_year=exp_year,
                expiration_date_month=exp_month,
                expiration_date_day=exp_day,
                cash_credit_limit=cash_credit_limit_val,
                reissue_date_year=reissue_year,
                reissue_date_month=reissue_month,
                reissue_date_day=reissue_day,
                group_id=getattr(current, "group_id", ""),
                customer_ssn_part1=ssn_part1,
                customer_ssn_part2=ssn_part2,
                customer_ssn_part3=ssn_part3,
                customer_dob_year=dob_year,
                customer_dob_month=dob_month,
                customer_dob_day=dob_day,
                customer_fico_score=getattr(current, "customer_fico_score", ""),
                customer_first_name=getattr(current, "customer_first_name", ""),
                customer_middle_name=getattr(current, "customer_middle_name", ""),
                customer_last_name=getattr(current, "customer_last_name", ""),
                customer_addr_line_1=getattr(current, "customer_addr_line_1", ""),
                customer_state_cd=getattr(current, "customer_state_cd", ""),
                customer_addr_line_2=getattr(current, "customer_addr_line_2", ""),
                customer_zip=getattr(current, "customer_zip", ""),
                customer_city=getattr(current, "customer_city", ""),
                customer_country_cd=getattr(current, "customer_country_cd", ""),
                customer_phone_1_area=ph1_area,
                customer_phone_1_prefix=ph1_prefix,
                customer_phone_1_line=ph1_line,
                customer_govt_id=getattr(current, "customer_govt_id", ""),
                customer_phone_2_area=ph2_area,
                customer_phone_2_prefix=ph2_prefix,
                customer_phone_2_line=ph2_line,
                customer_eft_account_id=getattr(current, "customer_eft_account_id", ""),
                customer_pri_cardholder=getattr(current, "customer_pri_cardholder", ""),
            )
        except Exception as exc:  # Pydantic ValidationError inherits Exception
            logger.warning(
                "update_account: AccountUpdateRequest construction failed: %s",
                exc,
            )
            raise Exception(f"Invalid account update payload: {exc}") from exc

        # ----- Step 4: Delegate to service layer -----
        # Replaces COACTUPC.cbl REWRITE ACCTFILE + REWRITE CUSTFILE (lines
        # 4066 and 4086). The service performs both UPDATEs inside a
        # single session and commits atomically — mirrors the implicit
        # CICS SYNCPOINT at end-of-transaction.
        update_response: object = await account_service.update_account(account_input.acct_id, update_request)
        if getattr(update_response, "error_message", None):
            err: Optional[str] = getattr(update_response, "error_message", None)  # noqa: UP045  # schema requires typing.Optional
            logger.warning("update_account: service returned error: %s", err)
            raise Exception(err or "Account update failed")

        # ----- Step 5: Re-query ORM for a complete AccountType response -----
        # AccountUpdateResponse extends AccountViewResponse (31 business
        # fields) but we need the full Account ORM row to invoke
        # AccountType.from_model(), which normalizes ORM attributes to
        # GraphQL field names.
        stmt = select(Account).where(Account.acct_id == account_input.acct_id)
        result = await session.execute(stmt)
        account_orm: Optional[Account] = result.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
        if account_orm is None:
            # Extremely unlikely — the service just persisted the row —
            # but we guard defensively.
            raise Exception(f"Account {account_input.acct_id} not found after update")

        logger.info(
            "update_account: succeeded for acct_id=%s (version_id=%s)",
            account_input.acct_id,
            getattr(account_orm, "version_id", None),
        )
        return AccountType.from_model(account_orm)

    # ------------------------------------------------------------------
    # update_card — COCRDUPC.cbl → optimistic-concurrency REWRITE
    # ------------------------------------------------------------------
    @strawberry.mutation(  # type: ignore[untyped-decorator]  # strawberry.mutation returns Any
        description=(
            "Update mutable fields of an existing card. Replaces "
            "COCRDUPC.cbl EXEC CICS READ UPDATE / REWRITE with "
            "optimistic concurrency via the version_id column on "
            "the cards table."
        ),
    )
    async def update_card(
        self,
        info: Info,
        card_input: CardUpdateInput,
    ) -> CardType:
        """Update an existing card record (Feature F-008).

        Replaces the ``COCRDUPC.cbl`` CICS READ UPDATE + REWRITE
        flow (see ``9200-WRITE-PROCESSING`` at line 1420 of the
        COBOL source) with a SQLAlchemy dirty-tracked UPDATE guarded
        by optimistic concurrency via the
        :attr:`~src.shared.models.card.Card.version_id` column.

        The resolver performs a read-then-update pattern similar to
        :meth:`update_account`, but narrower:

        1. Read the existing card via
           :meth:`CardService.get_card_detail` to obtain the
           current ``account_id`` (which is not editable) and the
           existing ``expiration_date``.
        2. Split the existing ``expiration_date`` into year/month/day
           for the :class:`CardUpdateRequest` contract, then overlay
           any GraphQL-supplied ``expiration_month`` /
           ``expiration_year`` (``expiry_day`` is always ``"01"``
           per COBOL convention).
        3. Overlay any GraphQL-supplied ``embossed_name`` and
           ``active_status``.
        4. Construct the :class:`CardUpdateRequest` and delegate to
           :meth:`CardService.update_card`.
        5. On :class:`~sqlalchemy.orm.exc.StaleDataError` (handled
           internally by the service and surfaced as an
           ``error_message``), raise a GraphQL error with the
           COBOL-authentic ``'Record changed by some one else.
           Please review'`` text.
        6. Re-fetch the freshly-persisted ORM
           :class:`~src.shared.models.card.Card` row and return it
           as a :class:`CardType`.

        Parameters
        ----------
        info : Info
            Strawberry resolver context.
        card_input : CardUpdateInput
            GraphQL input payload.

        Returns
        -------
        CardType
            The post-update state of the card.

        Raises
        ------
        Exception
            On not-found, stale-data, or validation failure.
        """
        session = _get_session(info)
        card_service: CardService = CardService(session)

        # ----- Step 1: Fetch current card state -----
        # Replaces COCRDUPC.cbl READ FILE('CARDDAT') at the top of the
        # 9200-WRITE-PROCESSING paragraph.
        card_detail: object = await card_service.get_card_detail(card_input.card_num)
        if getattr(card_detail, "error_message", None):
            err = getattr(card_detail, "error_message", None)
            logger.warning("update_card: get_card_detail returned error: %s", err)
            raise Exception(err or "Card lookup failed")

        # ----- Step 2: Merge GraphQL inputs with current state -----
        # Pull the non-editable account_id from the current card record,
        # then overlay any GraphQL-supplied editable fields on top of
        # the existing values.
        account_id_val: str = getattr(card_detail, "account_id", "")
        embossed_name_val: str = (
            card_input.embossed_name
            if card_input.embossed_name is not None
            else getattr(card_detail, "embossed_name", "")
        )
        status_code_val: str = (
            card_input.active_status
            if card_input.active_status is not None
            else getattr(card_detail, "status_code", "")
        )
        expiry_month_val: str = (
            card_input.expiration_month
            if card_input.expiration_month is not None
            else getattr(card_detail, "expiry_month", "")
        )
        expiry_year_val: str = (
            card_input.expiration_year
            if card_input.expiration_year is not None
            else getattr(card_detail, "expiry_year", "")
        )
        # COBOL CVACT02Y.cpy CARD-EXPIRAION-DATE is PIC X(10) stored as
        # YYYY-MM-DD; the day component is always "01" because card
        # expiry on the embossed plastic is month-granular. The
        # CardUpdateRequest Pydantic contract still requires an
        # ``expiry_day`` field for future extensibility; we default it
        # here per the COBOL convention.
        expiry_day_val: str = "01"

        # ----- Step 3: Construct the CardUpdateRequest -----
        try:
            card_update_request: CardUpdateRequest = CardUpdateRequest(
                account_id=account_id_val,
                card_number=card_input.card_num,
                embossed_name=embossed_name_val,
                status_code=status_code_val,
                expiry_month=expiry_month_val,
                expiry_year=expiry_year_val,
                expiry_day=expiry_day_val,
            )
        except Exception as exc:
            logger.warning(
                "update_card: CardUpdateRequest construction failed: %s",
                exc,
            )
            raise Exception(f"Invalid card update payload: {exc}") from exc

        # ----- Step 4: Delegate to service layer -----
        # Replaces COCRDUPC.cbl REWRITE FILE('CARDDAT') (line 1478).
        # The service performs the optimistic-concurrency check against
        # the version_id column (set via __mapper_args__["version_id_col"]
        # on the Card ORM) and surfaces any StaleDataError as an
        # error_message on the response.
        card_update_response: object = await card_service.update_card(card_input.card_num, card_update_request)
        if getattr(card_update_response, "error_message", None):
            err2 = getattr(card_update_response, "error_message", None)
            logger.warning("update_card: service returned error: %s", err2)
            raise Exception(err2 or "Card update failed")

        # ----- Step 5: Re-query ORM for a complete CardType response -----
        stmt2 = select(Card).where(Card.card_num == card_input.card_num)
        result2 = await session.execute(stmt2)
        card_orm: Optional[Card] = result2.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
        if card_orm is None:
            raise Exception(f"Card {card_input.card_num} not found after update")

        logger.info(
            "update_card: succeeded for card_num=%s (version_id=%s)",
            card_input.card_num,
            getattr(card_orm, "version_id", None),
        )
        return CardType.from_model(card_orm)

    # ------------------------------------------------------------------
    # add_transaction — COTRN02C.cbl → auto-ID + CCXREF resolution
    # ------------------------------------------------------------------
    @strawberry.mutation(  # type: ignore[untyped-decorator]  # strawberry.mutation returns Any
        description=(
            "Add a new transaction to the database. Replaces "
            "COTRN02C.cbl EXEC CICS STARTBR + READPREV (auto-ID "
            "generation) and READ CCXREF (cross-reference "
            "resolution) with a SQLAlchemy INSERT."
        ),
    )
    async def add_transaction(
        self,
        info: Info,
        transaction_input: TransactionAddInput,
    ) -> TransactionType:
        """Add a new transaction (Feature F-011).

        Replaces the ``COTRN02C.cbl`` transaction-add flow:

        * ``READ-CXACAIX-FILE`` (line 576) — look up the primary card
          for an account → Python: SELECT from
          :class:`CardCrossReference` by ``acct_id``.
        * ``READ-CCXREF-FILE`` (line 609) — look up the account for
          a card → Python: SELECT from
          :class:`CardCrossReference` by ``card_num``.
        * ``ADD-TRANSACTION`` (line 442) — auto-generate the next
          ``tran_id`` via STARTBR + READPREV + ADD 1 → Python:
          SELECT ``max(tran_id)`` + 1, zero-padded to 16 chars (this
          is performed inside :meth:`TransactionService.add_transaction`).
        * ``WRITE-TRANSACT-FILE`` (line 711) → Python:
          ``session.add(Transaction(...))`` +
          ``session.flush()`` / ``session.commit()``.

        The GraphQL surface accepts either ``card_num`` or
        ``acct_id`` (or both); the resolver resolves the missing
        half via :class:`CardCrossReference` before invoking the
        service. This preserves the COBOL UX whereby the transaction-
        add screen accepted either key and populated the other.

        CRITICAL: ``amount`` is a :class:`Decimal` throughout (COBOL
        PIC S9(09)V99 semantics).

        Parameters
        ----------
        info : Info
            Strawberry resolver context.
        transaction_input : TransactionAddInput
            GraphQL input payload.

        Returns
        -------
        TransactionType
            The full post-insert state of the newly-created
            transaction.

        Raises
        ------
        Exception
            On cross-reference not-found, validation failure, or
            underlying database error.
        """
        session = _get_session(info)
        transaction_service: TransactionService = TransactionService(session)

        # ----- Step 1: Resolve cross-reference (card_num ↔ acct_id) -----
        # Both card_num and acct_id are required by TransactionAddRequest.
        # If the GraphQL client supplied only one, fill in the other.
        resolved_card_num: Optional[str] = transaction_input.card_num  # noqa: UP045  # schema requires typing.Optional
        resolved_acct_id: Optional[str] = transaction_input.acct_id  # noqa: UP045  # schema requires typing.Optional

        if not resolved_card_num and not resolved_acct_id:
            raise Exception("add_transaction: either card_num or acct_id must be supplied")

        if resolved_card_num and not resolved_acct_id:
            # COTRN02C.cbl READ-CCXREF-FILE at line 609 — look up
            # account for the given card.
            xref_stmt = select(CardCrossReference).where(CardCrossReference.card_num == resolved_card_num)
            xref_result = await session.execute(xref_stmt)
            xref_row: Optional[CardCrossReference] = xref_result.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
            if xref_row is None:
                logger.warning(
                    "add_transaction: no cross-reference for card_num=%s",
                    resolved_card_num,
                )
                raise Exception(f"Card {resolved_card_num} not found in cross-reference")
            resolved_acct_id = xref_row.acct_id
        elif resolved_acct_id and not resolved_card_num:
            # COTRN02C.cbl READ-CXACAIX-FILE at line 576 — look up the
            # primary card for the given account. The CardCrossReference
            # table has an alternate index on ``acct_id`` (V2__indexes.sql).
            xref_stmt2 = select(CardCrossReference).where(CardCrossReference.acct_id == resolved_acct_id)
            xref_result2 = await session.execute(xref_stmt2)
            xref_row2: Optional[CardCrossReference] = xref_result2.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
            if xref_row2 is None:
                logger.warning(
                    "add_transaction: no cross-reference for acct_id=%s",
                    resolved_acct_id,
                )
                raise Exception(f"Account {resolved_acct_id} has no card on file")
            resolved_card_num = xref_row2.card_num

        assert resolved_card_num is not None  # narrow for mypy
        assert resolved_acct_id is not None

        # ----- Step 2: Generate orig_date -----
        # COTRN02C.cbl populates TRAN-ORIG-TS with the current
        # CICS EIBTIMER value; the modernized request takes an
        # ``orig_date`` field (YYYY-MM-DD) and the service layer
        # attaches the full timestamp. Use UTC for consistency
        # across AWS regions.
        today_iso: str = datetime.now(tz=UTC).strftime("%Y-%m-%d")

        # ----- Step 3: Construct the TransactionAddRequest -----
        try:
            tran_request: TransactionAddRequest = TransactionAddRequest(
                acct_id=resolved_acct_id,
                card_num=resolved_card_num,
                tran_type_cd=transaction_input.type_cd,
                tran_cat_cd=transaction_input.cat_cd,
                tran_source=transaction_input.source,
                description=transaction_input.description,
                amount=transaction_input.amount,
                orig_date=today_iso,
                proc_date=today_iso,
                merchant_id=transaction_input.merchant_id,
                merchant_name=transaction_input.merchant_name,
                merchant_city=transaction_input.merchant_city,
                merchant_zip=transaction_input.merchant_zip,
            )
        except Exception as exc:
            logger.warning(
                "add_transaction: TransactionAddRequest construction failed: %s",
                exc,
            )
            raise Exception(f"Invalid transaction payload: {exc}") from exc

        # ----- Step 4: Delegate to service layer -----
        # Replaces COTRN02C.cbl WRITE-TRANSACT-FILE (line 711). The
        # service is responsible for the auto-ID generation and
        # cross-reference re-verification.
        tran_response: object = await transaction_service.add_transaction(tran_request)
        if getattr(tran_response, "error_message", None):
            err3 = getattr(tran_response, "error_message", None)
            logger.warning("add_transaction: service returned error: %s", err3)
            raise Exception(err3 or "Transaction add failed")

        new_tran_id: Optional[str] = getattr(tran_response, "tran_id", None)  # noqa: UP045  # schema requires typing.Optional
        if not new_tran_id:
            # Fallback: if the minimal response does not surface
            # tran_id, re-query for the largest transaction id on
            # this card as a best-effort recovery.
            fallback_stmt = (
                select(Transaction.tran_id)
                .where(Transaction.card_num == resolved_card_num)
                .order_by(desc(Transaction.tran_id))
                .limit(1)
            )
            fallback_result = await session.execute(fallback_stmt)
            new_tran_id = fallback_result.scalar_one_or_none()
            if not new_tran_id:
                raise Exception("add_transaction: could not determine new tran_id")

        # ----- Step 5: Re-query ORM for the full TransactionType -----
        stmt3 = select(Transaction).where(Transaction.tran_id == new_tran_id)
        result3 = await session.execute(stmt3)
        tran_orm: Optional[Transaction] = result3.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
        if tran_orm is None:
            raise Exception(f"Transaction {new_tran_id} not found after insert")

        logger.info(
            "add_transaction: succeeded tran_id=%s card_num=%s amount=%s",
            new_tran_id,
            resolved_card_num,
            transaction_input.amount,
        )
        return TransactionType.from_model(tran_orm)

    # ------------------------------------------------------------------
    # pay_bill — COBIL00C.cbl → atomic dual-write (Transaction + Account)
    # ------------------------------------------------------------------
    @strawberry.mutation(  # type: ignore[untyped-decorator]  # strawberry.mutation returns Any
        description=(
            "Submit a bill payment against an account. Replaces "
            "COBIL00C.cbl atomic dual-write: WRITE TRANSACT + "
            "REWRITE ACCTDAT under a single SYNCPOINT — both writes "
            "succeed atomically or neither does."
        ),
    )
    async def pay_bill(
        self,
        info: Info,
        payment_input: BillPaymentInput,
    ) -> TransactionType:
        """Submit a bill payment (Feature F-012).

        Replaces the ``COBIL00C.cbl`` atomic dual-write flow:

        * ``READ FILE('ACCTDAT') UPDATE`` — locks the Account row
          and retrieves ``ACCT-CURR-BAL``. Python: SELECT inside
          the service transaction.
        * ``READ-CXACAIX-FILE`` — looks up the primary card for the
          account (needed for ``TRAN-CARD-NUM``). Python: SELECT
          from :class:`CardCrossReference`.
        * ``STARTBR`` + ``READPREV`` + ``ADD 1`` — auto-generates
          the next ``TRAN-ID``. Python: SELECT ``max(tran_id)`` + 1.
        * ``WRITE FILE('TRANSACT')`` — inserts the payment
          transaction. Python: ``session.add(Transaction(...))``.
        * ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT`` —
          decrements the balance.
        * ``REWRITE FILE('ACCTDAT')`` — persists the new balance.
        * End-of-transaction SYNCPOINT commits both writes.

        CRITICAL ATOMICITY: The Transaction INSERT and the Account
        balance UPDATE happen in the same :class:`AsyncSession`
        under a single :meth:`session.commit` call inside
        :meth:`BillService.pay_bill`. On any exception the session
        is rolled back before the method returns.

        CRITICAL FINANCIAL PRECISION: All arithmetic uses
        :class:`decimal.Decimal` — never float. The COBOL subtract
        ``ACCT-CURR-BAL - TRAN-AMT`` is translated to the helper
        :func:`~src.shared.utils.decimal_utils.subtract` which
        preserves PIC S9(n)V99 semantics.

        The ``amount`` field on :class:`BillPaymentInput` is
        optional; when omitted the resolver reads the current
        account balance (preserving the COBOL "pay-in-full"
        semantics — ``COBIL00C.cbl`` line ~400 which sets
        ``TRAN-AMT = ACCT-CURR-BAL``).

        Parameters
        ----------
        info : Info
            Strawberry resolver context.
        payment_input : BillPaymentInput
            GraphQL input payload — account_id and optional amount.

        Returns
        -------
        TransactionType
            The full post-insert state of the payment transaction.

        Raises
        ------
        Exception
            On account-not-found, zero-balance, negative amount, or
            underlying database error.
        """
        session = _get_session(info)
        bill_service: BillService = BillService(session)

        # ----- Step 1: Resolve the payment amount -----
        # The BillPaymentRequest Pydantic schema REQUIRES a strictly
        # positive amount (``_validate_amount_positive``). If the
        # GraphQL client omits ``amount`` we preserve COBOL "pay in
        # full" semantics by reading the current balance and
        # substituting it. This is a separate SELECT — it does not
        # lock the row — because BillService.pay_bill will re-read
        # the row inside its own transaction anyway.
        resolved_amount: Optional[Decimal] = payment_input.amount  # noqa: UP045  # schema requires typing.Optional
        if resolved_amount is None:
            acct_stmt = select(Account).where(Account.acct_id == payment_input.acct_id)
            acct_result = await session.execute(acct_stmt)
            acct_orm: Optional[Account] = acct_result.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
            if acct_orm is None:
                logger.warning(
                    "pay_bill: account not found acct_id=%s",
                    payment_input.acct_id,
                )
                raise Exception(f"Account {payment_input.acct_id} not found")
            resolved_amount = acct_orm.curr_bal
            if resolved_amount is None or resolved_amount <= Decimal("0.00"):
                # COBIL00C.cbl line ~380: if ACCT-CURR-BAL is zero
                # or negative, display the "You have nothing to pay"
                # message and return to the menu. The modernized API
                # surfaces this as a validation error rather than a
                # UI message.
                logger.warning(
                    "pay_bill: zero or negative balance acct_id=%s balance=%s",
                    payment_input.acct_id,
                    resolved_amount,
                )
                raise Exception("You have nothing to pay — account balance is zero")

        # ----- Step 2: Construct the BillPaymentRequest -----
        try:
            bill_request: BillPaymentRequest = BillPaymentRequest(
                acct_id=payment_input.acct_id,
                amount=resolved_amount,
            )
        except Exception as exc:
            logger.warning(
                "pay_bill: BillPaymentRequest construction failed: %s",
                exc,
            )
            raise Exception(f"Invalid bill payment payload: {exc}") from exc

        # ----- Step 3: Delegate to service layer -----
        # Replaces COBIL00C.cbl atomic dual-write. The service handles:
        #   * CCXREF lookup to find the primary card
        #   * max(tran_id) + 1 auto-ID generation
        #   * Transaction INSERT
        #   * Account balance decrement via Decimal subtraction
        #   * session.commit() — atomic for both writes
        #   * session.rollback() on any exception
        bill_response: object = await bill_service.pay_bill(bill_request)
        if getattr(bill_response, "confirm", "N") != "Y" or getattr(bill_response, "message", None) is None:
            err4 = getattr(bill_response, "message", None)
            logger.warning("pay_bill: service returned non-success: %s", err4)
            raise Exception(err4 or "Bill payment failed")

        # ----- Step 4: Extract tran_id and re-query for full entity -----
        # BillPaymentResponse is a minimal 5-field payload: it encodes
        # the new tran_id inside the success ``message`` field
        # (format: "Payment successful. Your Transaction ID is
        # {tran_id}."). We parse it out to re-query the Transaction.
        new_tran_id2: str = _extract_tran_id_from_message(getattr(bill_response, "message", None))
        if not new_tran_id2:
            # Fallback: if the message format changes in the service
            # layer, look up the most recent payment transaction on
            # any card tied to this account.
            fallback_stmt2 = (
                select(Transaction.tran_id)
                .join(
                    CardCrossReference,
                    CardCrossReference.card_num == Transaction.card_num,
                )
                .where(CardCrossReference.acct_id == payment_input.acct_id)
                .order_by(desc(Transaction.tran_id))
                .limit(1)
            )
            fallback_result2 = await session.execute(fallback_stmt2)
            fallback_tran_id: Optional[str] = fallback_result2.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
            if not fallback_tran_id:
                raise Exception("pay_bill: could not determine new tran_id")
            new_tran_id2 = fallback_tran_id

        stmt4 = select(Transaction).where(Transaction.tran_id == new_tran_id2)
        result4 = await session.execute(stmt4)
        payment_tran_orm: Optional[Transaction] = result4.scalar_one_or_none()  # noqa: UP045  # schema requires typing.Optional
        if payment_tran_orm is None:
            raise Exception(f"Payment transaction {new_tran_id2} not found after insert")

        logger.info(
            "pay_bill: succeeded tran_id=%s acct_id=%s amount=%s",
            new_tran_id2,
            payment_input.acct_id,
            resolved_amount,
        )
        return TransactionType.from_model(payment_tran_orm)


# ============================================================================
# __all__ — module public API
# ============================================================================
# Named exports for the GraphQL schema module (``src.api.graphql.schema``)
# to pull in. Keeping this tight prevents accidental leakage of the
# private helper functions (``_get_session``, ``_split_*``,
# ``_extract_tran_id_from_message``).
# ============================================================================
__all__: list[str] = [
    "AccountUpdateInput",
    "BillPaymentInput",
    "CardUpdateInput",
    "Mutation",
    "TransactionAddInput",
]
