# ============================================================================
# Source: COBOL BMS symbolic map COBIL00.CPY (Bill Payment screen, F-012)
#         + COBOL online program COBIL00C.cbl (business logic reference)
# ============================================================================
# Mainframe-to-Cloud migration: CICS dual-write → SQLAlchemy transactional
# context manager.
#
# Replaces:
#   * The BMS symbolic-map input fields from ``COBIL0AI`` previously
#     submitted via CICS RECEIVE MAP ('COBIL0A') in ``COBIL00C.cbl``:
#       - ACTIDINI PIC X(11) — Account ID
#       - CURBALI  PIC X(14) — Current balance display (echoed back on
#                              the response; not accepted as input)
#       - CONFIRMI PIC X(1)  — Confirmation indicator ('Y'/'N')
#       - ERRMSGI  PIC X(78) — Info/error message
#   * The Bill Payment dual-write pattern from ``COBIL00C.cbl`` which
#     originally performed, within a single CICS task:
#       1. MOVE ACCT-CURR-BAL TO TRAN-AMT        (line 224 of COBIL00C.cbl)
#       2. EXEC CICS WRITE TRANFILE              (Transaction INSERT)
#       3. COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT    (line 234)
#       4. EXEC CICS REWRITE ACCTFILE            (Account balance UPDATE)
#       5. EXEC CICS SYNCPOINT / ROLLBACK        (transactional commit)
#     — now replaced by a SQLAlchemy session context manager in
#     ``src/api/services/bill_service.py`` that atomically INSERTs the
#     Transaction row AND UPDATEs the Account.curr_balance within a
#     single DB transaction (see AAP §0.4.3 "Transactional Outbox").
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
"""Pydantic v2 schemas for the CardDemo Bill Payment API (Feature F-012).

Converts the BMS symbolic-map copybook ``app/cpy-bms/COBIL00.CPY`` into a
pair of transport schemas that drive the ``POST /bills/pay`` REST endpoint
(and its GraphQL mutation counterpart). Bill Payment is the canonical
**dual-write** feature of CardDemo: it simultaneously INSERTs a new row
into the ``transaction`` table AND UPDATEs the payer's ``account.curr_balance``
— both operations occurring atomically within a single SQLAlchemy session.

BMS → Python Field Mapping
--------------------------
===============================  ==========  ==================================
BMS / COBOL Field                Py Class    Python Field
===============================  ==========  ==================================
ACTIDINI ``PIC X(11)``           Request     ``BillPaymentRequest.acct_id``
(derived from COBIL00C WS-TRAN-  Request     ``BillPaymentRequest.amount``
 AMT ``PIC +99999999.99``)
ACTIDINO ``PIC X(11)``           Response    ``BillPaymentResponse.acct_id``
(echoed from request)            Response    ``BillPaymentResponse.amount``
CURBALO  ``PIC X(14)``           Response    ``BillPaymentResponse.current_balance``
CONFIRMO ``PIC X(1)``            Response    ``BillPaymentResponse.confirm``
ERRMSGO  ``PIC X(78)``           Response    ``BillPaymentResponse.message``
===============================  ==========  ==================================

Design Notes
------------
* **Financial precision** — every monetary field on BOTH schemas uses
  :class:`decimal.Decimal`, NEVER :class:`float`. This preserves the
  exact COBOL ``PIC S9(n)V99`` semantics required by AAP §0.7.2
  ("Financial Precision") and prevents IEEE-754 representation errors
  in transaction amounts and balance computations. Any arithmetic
  performed on these values (e.g., ``new_balance = old_balance -
  payment_amount``) must also be performed in ``Decimal`` arithmetic
  with :data:`decimal.ROUND_HALF_EVEN` (banker's rounding, mirroring
  COBOL ``ROUNDED``).
* **Account ID as string** — ``acct_id`` is typed as :class:`str`
  rather than :class:`int` to preserve the 11-digit leading-zero
  representation from the COBOL ``PIC X(11)`` field (e.g. account
  ``"00000000042"``). Stripping leading zeros would break the key
  lookup against the Aurora PostgreSQL ``account`` table, which stores
  the account ID as a fixed-width character PK.
* **Positive-amount constraint** — ``BillPaymentRequest.amount`` MUST
  be strictly greater than zero. Bill payment is a debit-only
  transaction; negative or zero amounts are rejected at the schema
  layer so that the service layer's dual-write never corrupts the
  account balance.
* **Dual-write handled at the service layer** — this module defines
  ONLY the transport contracts. The actual dual-write
  (``transaction`` INSERT + ``account`` balance UPDATE) is orchestrated
  by ``src/api/services/bill_service.py`` within a single SQLAlchemy
  session context so that a failure in either operation rolls back
  both, mirroring the CICS SYNCPOINT / ROLLBACK pattern used by the
  original ``COBIL00C.cbl`` program (see AAP §0.7.1 — "Preserve all
  existing functionality exactly as-is" and §0.4.3 — "Transactional
  Outbox").
* **``ConfigDict(from_attributes=True)``** is applied to
  :class:`BillPaymentResponse` so the service layer may instantiate it
  directly from a SQLAlchemy ORM row (e.g., an updated ``Account``
  entity plus a freshly-created ``Transaction`` entity) without an
  intermediate ``dict`` conversion. No such config is applied to the
  request schema — request payloads always arrive as JSON dicts from
  the REST/GraphQL layer.
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field` for length/default constraints and
  :func:`~pydantic.field_validator` for business-rule enforcement.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI/Uvicorn container image).

See Also
--------
AAP §0.2.3 — Online CICS Program Classification (F-012, COBIL00C.cbl)
AAP §0.4.1 — Refactored Structure Planning (``bill_schema.py`` row)
AAP §0.4.3 — Design Pattern Applications (Transactional Outbox)
AAP §0.5.1 — File-by-File Transformation Plan (``bill_schema.py`` row)
AAP §0.7.1 — Refactoring-Specific Rules (dual-write preservation)
AAP §0.7.2 — Special Instructions (Financial Precision)
"""

from decimal import Decimal
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Private module constants — COBOL PIC-clause widths from COBIL00.CPY
# ---------------------------------------------------------------------------
# Keeping these as private module constants (leading underscore) keeps the
# public surface of the module minimal — only the two request/response
# schemas are exported via ``__all__`` below.

# ACTIDINI / ACTIDINO PIC X(11) — the 11-character account identifier.
# Preserved as a string in Python to retain leading zeros; 11 == COBOL
# fixed-width layout of both the input and the corresponding
# ``account.acct_id`` primary key column in Aurora PostgreSQL.
_ACCT_ID_MAX_LEN: int = 11

# CONFIRMI / CONFIRMO PIC X(1) — single-character Y/N confirmation flag.
_CONFIRM_MAX_LEN: int = 1

# ERRMSGI / ERRMSGO PIC X(78) — 78-character info/error message,
# matching the BMS screen row width convention used across all CardDemo
# screens (see e.g. CORPT00.CPY ERRMSGI, COSGN00.CPY ERRMSGI, etc.).
_ERRMSG_MAX_LEN: int = 78

# Valid values for the CONFIRMI / CONFIRMO PIC X(1) flag.
# Upper-case only — the COBOL UI always transmitted an upper-case
# character; the modern API follows the same convention to preserve
# behavioral parity with the COBOL VALID-CONFIRM 88-level check.
_VALID_CONFIRM_VALUES: frozenset[str] = frozenset({"Y", "N"})


# ---------------------------------------------------------------------------
# BillPaymentRequest — incoming BMS COBIL0AI fields + derived amount
# ---------------------------------------------------------------------------
class BillPaymentRequest(BaseModel):
    """Incoming bill-payment payload for ``POST /bills/pay``.

    Carries the two business-input fields required to perform a bill
    payment against a CardDemo account:

    * the *account identifier* (directly from BMS ``ACTIDINI``);
    * the *payment amount* (derived from the COBOL business logic in
      ``COBIL00C.cbl`` which sourced the amount from ``WS-TRAN-AMT``
      ``PIC +99999999.99``).

    The remaining COBIL00 symbolic-map fields are either display-only
    screen decoration (``TRNNAMEI``, ``TITLE01I``, ``CURDATEI``,
    ``PGMNAMEI``, ``CURTIMEI``, ``TITLE02I``) or response-only
    (``CURBALI``, ``CONFIRMI``, ``ERRMSGI``) — they are intentionally
    NOT part of this request contract.

    Attributes
    ----------
    acct_id : str
        Target account identifier — 11-character fixed-width string
        with leading zeros preserved. Max 11 characters (COBOL
        ``PIC X(11)`` constraint from the original ``ACTIDINI`` field).
        Must be non-empty. Looked up against the ``account`` table's
        primary key in :mod:`src.api.services.bill_service` to locate
        the payer's current balance.
    amount : Decimal
        Payment amount in account currency. MUST be strictly greater
        than zero. Typed as :class:`decimal.Decimal` (NEVER
        :class:`float`) to preserve COBOL ``PIC S9(n)V99`` precision
        and prevent IEEE-754 rounding errors. Derived from the COBOL
        business logic in ``COBIL00C.cbl`` which computed
        ``TRAN-AMT`` from the current account balance at line 224
        (``MOVE ACCT-CURR-BAL TO TRAN-AMT``). The modernized API
        allows arbitrary positive payment amounts rather than forcing
        a full-balance payment — this broadens the use case without
        breaking the dual-write contract (see AAP §0.7.1).

    Raises
    ------
    pydantic.ValidationError
        * When ``acct_id`` is empty, whitespace-only, or longer than
          11 characters.
        * When ``amount`` is ``None``, zero, or negative.
    """

    acct_id: str = Field(
        ...,
        max_length=_ACCT_ID_MAX_LEN,
        description=(
            "Account ID to debit — 11-char fixed-width string with "
            "leading zeros preserved. Maps to COBIL00 ACTIDINI PIC X(11)."
        ),
    )
    amount: Decimal = Field(
        ...,
        description=(
            "Payment amount in account currency. Must be > 0. Uses "
            "Decimal (never float) to preserve COBOL PIC S9(n)V99 "
            "precision. Derived from COBIL00C.cbl WS-TRAN-AMT "
            "PIC +99999999.99."
        ),
    )

    # ---------------------------------------------------------------
    # Field-level validators
    # ---------------------------------------------------------------
    @field_validator("acct_id")
    @classmethod
    def _validate_acct_id(cls, value: str) -> str:
        """Ensure acct_id is non-empty and within the COBOL PIC X(11) limit.

        Mirrors the CICS behavior in ``COBIL00C.cbl`` which rejected a
        bill-payment attempt with a "Please enter Account ID" message
        (from ``CSMSG01Y.cpy``) when ``ACTIDINI`` was blank, empty,
        or all-zero.

        Parameters
        ----------
        value
            Candidate ``acct_id`` string from the request payload.

        Returns
        -------
        str
            The original ``value`` unchanged. Whitespace is NOT
            stripped because the underlying ``account.acct_id`` key in
            Aurora PostgreSQL is stored as a fixed-width character
            field; altering the value here would break key lookup.

        Raises
        ------
        ValueError
            * When ``value`` is ``None``.
            * When ``value`` is empty or whitespace-only.
            * When ``value`` exceeds 11 characters.
        """
        if value is None:
            raise ValueError("acct_id must not be null")
        if not isinstance(value, str):
            raise ValueError(
                f"acct_id must be a string; got {type(value).__name__}"
            )
        if not value or not value.strip():
            raise ValueError("acct_id must not be empty")
        if len(value) > _ACCT_ID_MAX_LEN:
            raise ValueError(
                f"acct_id exceeds max length {_ACCT_ID_MAX_LEN} "
                f"(COBOL PIC X({_ACCT_ID_MAX_LEN})); got length "
                f"{len(value)}"
            )
        return value

    @field_validator("amount")
    @classmethod
    def _validate_amount_positive(cls, value: Decimal) -> Decimal:
        """Ensure the payment amount is a positive :class:`~decimal.Decimal`.

        Bill payment is a **debit-only** transaction — the account's
        current balance decreases by exactly ``amount`` (see
        ``COBIL00C.cbl`` line 234:
        ``COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT``).
        Rejecting non-positive amounts at the schema layer guarantees
        the service-layer dual-write can never produce an account
        balance that moves in the wrong direction or remains unchanged
        (which would leave an orphan transaction row).

        Parameters
        ----------
        value
            Candidate ``amount`` from the request payload. Pydantic v2
            coerces accepted JSON representations (``int``, ``str``,
            ``float``) into :class:`~decimal.Decimal` before this
            validator runs; floats are promoted losslessly only when
            they are exactly representable.

        Returns
        -------
        Decimal
            The original ``value`` unchanged.

        Raises
        ------
        ValueError
            * When ``value`` is ``None``.
            * When ``value`` is not a :class:`~decimal.Decimal`
              (defensive — Pydantic should already have coerced).
            * When ``value`` is less than or equal to zero.
        """
        if value is None:
            raise ValueError("amount must not be null")
        if not isinstance(value, Decimal):
            # Pydantic v2 normally coerces into Decimal before
            # field_validator runs; this guard is purely defensive
            # and ensures a clear error if a custom caller bypasses
            # Pydantic's type machinery.
            raise ValueError(
                f"amount must be a decimal.Decimal; got "
                f"{type(value).__name__}"
            )
        if value <= Decimal("0"):
            raise ValueError(
                f"amount must be strictly greater than zero "
                f"(bill payment is a debit-only operation); got {value}"
            )
        return value


# ---------------------------------------------------------------------------
# BillPaymentResponse — outgoing BMS COBIL0AO fields + echoed request data
# ---------------------------------------------------------------------------
class BillPaymentResponse(BaseModel):
    """Outgoing payload from ``POST /bills/pay``.

    Replaces the CICS ``SEND MAP ('COBIL0AO')`` screen refresh that
    previously closed the Bill Payment transaction in ``COBIL00C.cbl``.
    After the service layer performs the atomic dual-write
    (``transaction`` INSERT + ``account.curr_balance`` UPDATE) within a
    single SQLAlchemy session, this response is assembled to surface:

    * the echoed request identifiers (``acct_id``, ``amount``) so the
      client can correlate the response with the originating request
      without a round-trip to the service;
    * the **updated** account balance after the payment has been
      applied (``current_balance``);
    * a confirmation flag and an optional info/error message that
      mirror the original BMS output layout.

    The ``ConfigDict(from_attributes=True)`` setting enables the
    service layer to construct this response directly from a mix of
    SQLAlchemy ORM entities (e.g. an updated ``Account`` object) and
    the original request payload, without requiring an intermediate
    ``dict`` conversion.

    Attributes
    ----------
    acct_id : str
        Echoed account identifier — mirrors the request ``acct_id``.
        Included so clients can correlate the response with the
        originating request without additional state. Maps to COBIL00
        ``ACTIDINO`` PIC X(11).
    amount : Decimal
        Echoed payment amount — mirrors the request ``amount``. Typed
        as :class:`~decimal.Decimal` to preserve COBOL precision.
        Reflects the exact amount applied to the account, which is
        identical to the ``transaction.tran_amt`` row just INSERTed.
    current_balance : Decimal
        The account's balance **AFTER** the payment was applied.
        Equals ``old_balance - amount`` computed in ``Decimal``
        arithmetic. Typed as :class:`~decimal.Decimal` to preserve
        COBOL ``PIC S9(n)V99`` precision. Maps to COBIL00 ``CURBALO``
        PIC X(14) — note that the BMS layout reserved 14 display
        characters for the formatted balance string (e.g.
        ``"-1234567890.12"``); the API contract returns the raw
        numeric value and leaves any display formatting to the client.
    confirm : str
        Confirmation indicator — one of ``'Y'`` (success — payment
        applied) or ``'N'`` (failure — payment rejected). 1-character
        upper-case string. Maps directly to the original ``CONFIRMO``
        PIC X(1) field in ``COBIL00.CPY``.
    message : Optional[str]
        Informational or error message, up to 78 characters — directly
        maps to the original ``ERRMSGO`` PIC X(78) field in
        ``COBIL00.CPY``. ``None`` when the operation succeeded and no
        remarks are needed; populated on failure with a human-readable
        reason (e.g. ``"Insufficient funds"``), or on success with a
        positive confirmation string (e.g. ``"Payment applied
        successfully."``).

    Raises
    ------
    pydantic.ValidationError
        * When ``confirm`` is not one of ``'Y'`` or ``'N'``.
        * When ``acct_id`` is longer than 11 characters
          (enforced by the ``max_length`` :class:`~pydantic.Field`
          constraint derived from ``ACTIDINO`` PIC X(11)).
        * When ``message`` exceeds 78 characters
          (enforced by the ``max_length`` :class:`~pydantic.Field`
          constraint derived from ``ERRMSGO`` PIC X(78)).
    """

    # Pydantic v2 ORM mode — permits construction directly from
    # attribute-based objects (e.g. SQLAlchemy ``Account`` instances).
    # The request schema does NOT include this because request payloads
    # always arrive as JSON-decoded dicts from the REST/GraphQL layer.
    model_config = ConfigDict(from_attributes=True)

    acct_id: str = Field(
        ...,
        max_length=_ACCT_ID_MAX_LEN,
        description=(
            "Echoed account ID — 11-char fixed-width. Maps to COBIL00 "
            "ACTIDINO PIC X(11)."
        ),
    )
    amount: Decimal = Field(
        ...,
        description=(
            "Echoed payment amount applied. Decimal (never float) for "
            "COBOL PIC S9(n)V99 parity. Equals the transaction.tran_amt "
            "row just INSERTed during the dual-write."
        ),
    )
    current_balance: Decimal = Field(
        ...,
        description=(
            "Account balance AFTER the payment was applied (old_balance "
            "- amount, computed in Decimal arithmetic with "
            "ROUND_HALF_EVEN). Maps to COBIL00 CURBALO PIC X(14) "
            "display width."
        ),
    )
    confirm: str = Field(
        ...,
        max_length=_CONFIRM_MAX_LEN,
        description=(
            "Confirmation indicator — 'Y' (payment applied) or 'N' "
            "(payment rejected). Maps to COBIL00 CONFIRMO PIC X(1)."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=(
            "Optional info/error message, max 78 chars. Maps to "
            "COBIL00 ERRMSGO PIC X(78)."
        ),
    )

    # ---------------------------------------------------------------
    # Field-level validators
    # ---------------------------------------------------------------
    @field_validator("confirm")
    @classmethod
    def _validate_confirm(cls, value: str) -> str:
        """Enforce the COBIL00 ``CONFIRMO`` PIC X(1) domain: ``'Y'`` or ``'N'``.

        The COBOL field nominally accepts any single character, but
        the CardDemo application convention across every confirm/cancel
        screen is upper-case ``'Y'`` / ``'N'`` — matching the
        ``VALID-CONFIRM`` 88-level constraint implicitly enforced by
        the CICS logic in ``COBIL00C.cbl``.

        Parameters
        ----------
        value
            Candidate ``confirm`` string from the response construction.

        Returns
        -------
        str
            The original ``value`` unchanged.

        Raises
        ------
        ValueError
            * When ``value`` is ``None``.
            * When ``value`` is not a string.
            * When ``value`` is not one of ``'Y'`` or ``'N'``.
        """
        if value is None:
            raise ValueError("confirm must not be null")
        if not isinstance(value, str):
            raise ValueError(
                f"confirm must be a string; got {type(value).__name__}"
            )
        if value not in _VALID_CONFIRM_VALUES:
            raise ValueError(
                f"confirm must be one of {sorted(_VALID_CONFIRM_VALUES)} "
                f"(COBIL00 CONFIRMO PIC X(1)); got {value!r}"
            )
        return value


# ---------------------------------------------------------------------------
# Public export surface
# ---------------------------------------------------------------------------
# Only the two transport schemas are intended for import by callers;
# the module's private constants (``_ACCT_ID_MAX_LEN``,
# ``_VALID_CONFIRM_VALUES``, etc.) are implementation details.
__all__: list[str] = [
    "BillPaymentRequest",
    "BillPaymentResponse",
]
