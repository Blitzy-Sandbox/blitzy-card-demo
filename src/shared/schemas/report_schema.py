# ============================================================================
# Source: COBOL BMS symbolic map CORPT00.CPY (Report Submission screen,
#         Feature F-022 — Report Submission)
# ============================================================================
# Mainframe-to-Cloud migration: CICS TDQ (WRITEQ JOBS) → AWS SQS FIFO queue.
#
# Replaces:
#   * The BMS input fields from ``CORPT0AI`` previously submitted via
#     CICS RECEIVE MAP ('CORPT0A') in ``CORPT00C.cbl``:
#       - MONTHLYI  PIC X(1)   — monthly-report radio-button flag
#       - YEARLYI   PIC X(1)   — yearly-report radio-button flag
#       - CUSTOMI   PIC X(1)   — custom-range radio-button flag
#       - SDTMMI    PIC X(2)   — start-date month segment
#       - SDTDDI    PIC X(2)   — start-date day segment
#       - SDTYYYYI  PIC X(4)   — start-date year segment
#       - EDTMMI    PIC X(2)   — end-date month segment
#       - EDTDDI    PIC X(2)   — end-date day segment
#       - EDTYYYYI  PIC X(4)   — end-date year segment
#       - CONFIRMI  PIC X(1)   — user confirmation ('Y'/'N')
#       - ERRMSGI   PIC X(78)  — info/error message
#   * The CICS ``WRITEQ TD QUEUE('JOBS')`` command that previously pushed
#     a JCL job-submission record onto the transient-data queue consumed
#     by an external job-scheduler — now replaced by a JSON message
#     published to an AWS SQS FIFO queue (see AAP §0.5.1 and
#     ``src/api/services/report_service.py``).
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
"""Pydantic v2 schemas for the CardDemo Report Submission API (Feature F-022).

Converts the BMS symbolic-map copybook ``app/cpy-bms/CORPT00.CPY`` into a
pair of transport schemas that drive the ``POST /reports/submit`` REST
endpoint (and its GraphQL mutation counterpart). The Report Submission
feature publishes a JSON message to an **AWS SQS FIFO queue**, replacing
the original CICS ``WRITEQ TD QUEUE('JOBS')`` call used by
``CORPT00C.cbl``.

BMS → Python Field Mapping
--------------------------
===============================  ==========  ==================================
BMS / COBOL Field                Py Class    Python Field
===============================  ==========  ==================================
MONTHLYI ``PIC X(1)``             Request    ``ReportSubmissionRequest.report_type``
YEARLYI  ``PIC X(1)``             (union)    (=:class:`ReportType.yearly`)
CUSTOMI  ``PIC X(1)``             (union)    (=:class:`ReportType.custom`)
SDTYYYYI+SDTMMI+SDTDDI             Request    ``ReportSubmissionRequest.start_date``
EDTYYYYI+EDTMMI+EDTDDI             Request    ``ReportSubmissionRequest.end_date``
(generated)                       Response   ``ReportSubmissionResponse.report_id``
(from request)                    Response   ``ReportSubmissionResponse.report_type``
CONFIRMI ``PIC X(1)``             Response   ``ReportSubmissionResponse.confirm``
ERRMSGI  ``PIC X(78)``            Response   ``ReportSubmissionResponse.message``
===============================  ==========  ==================================

Design Notes
------------
* **Radio-button consolidation** — the three mutually exclusive BMS
  fields (``MONTHLYI``, ``YEARLYI``, ``CUSTOMI``) collapse into a single
  typed field ``report_type: ReportType``. This is safer than the
  original COBOL layout because the enum forbids the invalid state
  where two flags are simultaneously ``'Y'``.
* **Date assembly** — the BMS screen split each date across three
  segmented fields (year, month, day); those segments are assembled by
  the API layer into standard ISO-8601 ``YYYY-MM-DD`` strings before
  validation. Validation uses :meth:`datetime.date.fromisoformat` to
  reject both malformed strings and invalid calendar dates
  (e.g. ``2024-02-30``). Because Python 3.11's ``fromisoformat``
  tolerates the compact ``YYYYMMDD`` form, an explicit
  format regex is applied first to enforce the dash-separated layout.
* **Custom-range requirement** — ``ReportType.custom`` requires BOTH
  ``start_date`` AND ``end_date``. For ``ReportType.monthly`` and
  ``ReportType.yearly``, any supplied dates are accepted but are
  semantically ignored by the consumer (the SQS message records them
  only when ``report_type == custom``). This mirrors the BMS screen
  behavior where ``SDT*``/``EDT*`` fields are protected unless the
  ``CUSTOMA`` attention indicator is set.
* **Date ordering** — when both dates are provided, ``end_date`` must
  be greater than or equal to ``start_date`` (inclusive range),
  matching the COBOL date-range validation cascade used in the
  reporting programs.
* **No ``ConfigDict(from_attributes=True)``** — these are transport
  schemas, not ORM-derived models. The response object is assembled
  explicitly by ``src/api/services/report_service.py`` after enqueuing
  the SQS message.
* **Pydantic v2** (``pydantic>=2.10``) — uses :class:`pydantic.BaseModel`
  with :func:`~pydantic.Field`, :func:`~pydantic.field_validator`, and
  :func:`~pydantic.model_validator` exclusively.
* **Python 3.11+ only** (aligned with the AWS Glue 5.1 runtime and the
  FastAPI/Uvicorn container image).

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan (``report_schema.py`` row)
AAP §0.7.1 — Refactoring-Specific Rules (business-logic preservation)
"""

import re
from datetime import date as _date_cls
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field, field_validator, model_validator

# ---------------------------------------------------------------------------
# Private module constants
# ---------------------------------------------------------------------------
# Strict YYYY-MM-DD regex.  Enforced BEFORE :meth:`datetime.date.fromisoformat`
# because Python 3.11's ``fromisoformat`` accepts additional ISO-8601 forms
# (notably the compact ``YYYYMMDD`` layout). The CardDemo API contract
# requires the dash-separated extended form only — this matches the way
# ``src/shared/utils/date_utils.py`` normalizes dates for downstream
# batch (PySpark) consumers.
_YMD_FORMAT_RE: re.Pattern[str] = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# Valid values for the CONFIRMI PIC X(1) flag in CORPT00.CPY.
# Uppercase only — the COBOL UI always transmitted an uppercase character.
_VALID_CONFIRM_VALUES: frozenset[str] = frozenset({"Y", "N"})

# Maximum length of the CORPT00 ERRMSGI field (PIC X(78)).
_ERRMSG_MAX_LEN: int = 78


# ---------------------------------------------------------------------------
# ReportType — consolidates MONTHLYI / YEARLYI / CUSTOMI radio buttons
# ---------------------------------------------------------------------------
class ReportType(str, Enum):  # noqa: UP042  # schema mandates `(str, Enum)` rather than `StrEnum`
    """The three mutually-exclusive report selections offered by the BMS screen.

    Replaces the three separate 1-byte BMS fields — ``MONTHLYI``,
    ``YEARLYI``, and ``CUSTOMI`` — from ``app/cpy-bms/CORPT00.CPY`` with
    a single typed enumeration that guarantees exactly one report type
    is selected. In the original COBOL layout each field could
    independently carry ``'Y'`` / ``'N'`` / ``'X'`` and the CICS
    program had to verify at runtime that one and only one was set;
    the enum eliminates that check entirely.

    Values
    ------
    monthly
        Generate the report for the current (or most recent) calendar
        month. No date range is required. Corresponds to the original
        ``MONTHLYI = 'Y'`` selection.
    yearly
        Generate the report for the current (or most recent) calendar
        year. No date range is required. Corresponds to the original
        ``YEARLYI = 'Y'`` selection.
    custom
        Generate the report for a user-supplied date range. **Both**
        ``start_date`` and ``end_date`` MUST be provided on the
        request; this is enforced by the
        :class:`ReportSubmissionRequest` model-level validator.
        Corresponds to the original ``CUSTOMI = 'Y'`` selection.

    Notes
    -----
    ``str`` subclass so that the value is serialized as a plain JSON
    string (e.g. ``"monthly"``) rather than an object, which keeps the
    REST API contract friendly for non-Python clients and also simplifies
    the SQS FIFO message body produced by
    ``src/api/services/report_service.py``.
    """

    monthly = "monthly"
    yearly = "yearly"
    custom = "custom"


# ---------------------------------------------------------------------------
# ReportSubmissionRequest — incoming BMS CORPT0AI fields
# ---------------------------------------------------------------------------
class ReportSubmissionRequest(BaseModel):
    """Incoming report-submission payload for ``POST /reports/submit``.

    Carries the three business-input fields from the BMS symbolic map
    ``CORPT0AI`` — consolidated and normalized for the REST/GraphQL
    transport layer:

    * the *type of report* (formerly three radio-button flags);
    * an optional *start date* (assembled from
      ``SDTYYYYI + SDTMMI + SDTDDI``);
    * an optional *end date* (assembled from
      ``EDTYYYYI + EDTMMI + EDTDDI``).

    The remaining CORPT00 symbolic-map fields are display-only screen
    decoration (``TRNNAMEI``, ``TITLE01I``, ``CURDATEI``, ``PGMNAMEI``,
    ``CURTIMEI``, ``TITLE02I``) or are response-only (``CONFIRMI``,
    ``ERRMSGI``) — they are intentionally NOT part of this request
    contract.

    Attributes
    ----------
    report_type : ReportType
        Which report to generate. One of ``monthly``, ``yearly``, or
        ``custom``. Required. Replaces the original
        ``MONTHLYI`` / ``YEARLYI`` / ``CUSTOMI`` trio of BMS flags.
    start_date : Optional[str]
        Range start date in ISO-8601 ``YYYY-MM-DD`` format. Required
        only when ``report_type == ReportType.custom``; ignored for
        ``monthly`` / ``yearly``. Assembled by the API layer from the
        original segmented BMS fields
        ``SDTYYYYI`` (year), ``SDTMMI`` (month), ``SDTDDI`` (day).
    end_date : Optional[str]
        Range end date in ISO-8601 ``YYYY-MM-DD`` format. Required
        only when ``report_type == ReportType.custom``; ignored for
        ``monthly`` / ``yearly``. Assembled by the API layer from the
        original segmented BMS fields
        ``EDTYYYYI`` (year), ``EDTMMI`` (month), ``EDTDDI`` (day).
        When both ``start_date`` and ``end_date`` are provided,
        ``end_date >= start_date`` is enforced.

    Raises
    ------
    pydantic.ValidationError
        * When ``start_date`` or ``end_date`` is not in ``YYYY-MM-DD``
          format or is not a valid calendar date.
        * When ``report_type == ReportType.custom`` and either
          ``start_date`` or ``end_date`` is missing.
        * When both dates are supplied and ``end_date < start_date``.
    """

    report_type: ReportType = Field(
        ...,
        description=(
            "Report type selector. Replaces the three mutually-exclusive "
            "BMS radio-button fields MONTHLYI/YEARLYI/CUSTOMI from "
            "CORPT00.CPY with a single typed enum value."
        ),
    )
    start_date: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Report range start (ISO-8601 YYYY-MM-DD). Required only when "
            "report_type=custom. Assembled from CORPT00 SDTYYYYI+SDTMMI+"
            "SDTDDI (segmented BMS date layout)."
        ),
    )
    end_date: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        description=(
            "Report range end (ISO-8601 YYYY-MM-DD). Required only when "
            "report_type=custom. Assembled from CORPT00 EDTYYYYI+EDTMMI+"
            "EDTDDI (segmented BMS date layout). Must be >= start_date "
            "when both are supplied."
        ),
    )

    # -----------------------------------------------------------------
    # Field-level validators
    # -----------------------------------------------------------------
    @field_validator("start_date")
    @classmethod
    def _validate_start_date_format(cls, value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """Enforce strict ``YYYY-MM-DD`` format and valid calendar date.

        ``None`` is permitted at this layer — the cross-field
        :meth:`_validate_custom_requires_dates` validator below rejects
        ``None`` only when ``report_type == ReportType.custom``.
        """
        if value is None:
            return value
        return cls._enforce_ymd(value, field_name="start_date")

    @field_validator("end_date")
    @classmethod
    def _validate_end_date_format(cls, value: Optional[str]) -> Optional[str]:  # noqa: UP045  # schema requires `typing.Optional`
        """Enforce strict ``YYYY-MM-DD`` format and valid calendar date.

        ``None`` is permitted at this layer — the cross-field
        :meth:`_validate_custom_requires_dates` validator below rejects
        ``None`` only when ``report_type == ReportType.custom``.
        """
        if value is None:
            return value
        return cls._enforce_ymd(value, field_name="end_date")

    @staticmethod
    def _enforce_ymd(value: str, *, field_name: str) -> str:
        """Validate that ``value`` is a strict ``YYYY-MM-DD`` calendar date.

        Parameters
        ----------
        value
            The candidate date string.
        field_name
            Name used in error messages for caller context
            (``"start_date"`` or ``"end_date"``).

        Returns
        -------
        str
            The original ``value`` unchanged (Pydantic v2 convention:
            validators return the normalized value).

        Raises
        ------
        ValueError
            * When ``value`` does not match the ``^\\d{4}-\\d{2}-\\d{2}$``
              regex (rejects compact ``YYYYMMDD`` and any non-dashed
              variant that Python 3.11's ``fromisoformat`` would accept).
            * When ``value`` matches the regex but is not a valid
              calendar date (e.g. ``"2024-02-30"``).
        """
        if not isinstance(value, str):
            raise ValueError(
                f"{field_name} must be a YYYY-MM-DD string; got "
                f"{type(value).__name__}"
            )
        if not _YMD_FORMAT_RE.match(value):
            raise ValueError(
                f"{field_name} must be in strict YYYY-MM-DD format "
                f"(e.g. '2024-01-31'); got {value!r}"
            )
        try:
            _date_cls.fromisoformat(value)
        except ValueError as exc:
            # Re-raise with a caller-friendly message that names the
            # offending field; the underlying exception text identifies
            # the specific invalidity (month out of range, day out of
            # range, etc.).
            raise ValueError(
                f"{field_name} is not a valid calendar date: {exc}"
            ) from exc
        return value

    # -----------------------------------------------------------------
    # Cross-field (model-level) validator
    # -----------------------------------------------------------------
    @model_validator(mode="after")
    def _validate_custom_requires_dates(self) -> "ReportSubmissionRequest":
        """Enforce the two cross-field invariants of the CORPT00 form.

        1. **Custom-range completeness** — when
           ``report_type == ReportType.custom`` BOTH ``start_date`` and
           ``end_date`` MUST be provided. Replaces the CICS-level
           validation cascade in ``CORPT00C.cbl`` that flagged the
           custom radio-button with a required date range. Note that
           for ``monthly`` / ``yearly`` selections the two date fields
           are intentionally permissive (accepted but semantically
           ignored by the consumer) — this matches the original BMS
           behavior where those inputs were protected unless the custom
           radio was active.
        2. **Date ordering** — whenever both ``start_date`` AND
           ``end_date`` are supplied (for ANY ``report_type``),
           ``end_date >= start_date`` must hold. Comparison is done on
           ISO-8601 strings, which sort identically to their date
           equivalents because each component (year/month/day) is
           zero-padded to a fixed width.

        Returns
        -------
        ReportSubmissionRequest
            ``self`` unchanged when every invariant holds — the
            Pydantic-v2 "after"-mode convention.

        Raises
        ------
        ValueError
            When either invariant is violated. ``pydantic`` converts
            this into a ``ValidationError`` with both the field name
            and the human-readable reason.
        """
        # Invariant #1: custom report type requires both dates.
        if self.report_type == ReportType.custom:
            missing: list[str] = []
            if self.start_date is None:
                missing.append("start_date")
            if self.end_date is None:
                missing.append("end_date")
            if missing:
                raise ValueError(
                    "report_type=custom requires "
                    f"{' and '.join(missing)} to be provided"
                )
        # Invariant #2: when both dates are supplied, end must not
        # precede start.  We deliberately compare the ISO-8601 strings
        # rather than parsing them to ``date`` objects: the format
        # has already been validated field-by-field, and a lexicographic
        # compare over zero-padded YYYY-MM-DD strings is order-preserving.
        if self.start_date is not None and self.end_date is not None:
            if self.end_date < self.start_date:
                raise ValueError(
                    "end_date must be greater than or equal to start_date "
                    f"(got start_date={self.start_date!r}, "
                    f"end_date={self.end_date!r})"
                )
        return self


# ---------------------------------------------------------------------------
# ReportSubmissionResponse — outgoing BMS CORPT0AO fields + generated report_id
# ---------------------------------------------------------------------------
class ReportSubmissionResponse(BaseModel):
    """Outgoing payload from ``POST /reports/submit``.

    Replaces the CICS ``SEND MAP ('CORPT0AO')`` screen refresh that
    previously closed the Report Submission transaction in
    ``CORPT00C.cbl``. The response confirms the enqueue operation and
    surfaces the server-generated identifier that consumers can use to
    track the SQS message downstream (e.g., in CloudWatch logs or the
    AWS Glue/Step-Functions pipeline).

    Attributes
    ----------
    report_id : str
        Server-generated identifier for the submitted report. Opaque
        string — typically a UUID or the SQS FIFO ``MessageId``
        produced by ``src/api/services/report_service.py`` when the
        queue message is published.
    report_type : ReportType
        Echo of the report type supplied in the request. Helps clients
        correlate the response with the originating request without a
        round-trip to the service.
    confirm : str
        Confirmation indicator from the request acknowledgement.
        One of ``'Y'`` (success — message enqueued) or ``'N'``
        (failure — enqueue aborted). 1-character upper-case string —
        directly maps to the original ``CONFIRMI`` PIC X(1) field in
        ``CORPT00.CPY``.
    message : Optional[str]
        Informational or error message, up to 78 characters — directly
        maps to the original ``ERRMSGI`` PIC X(78) field in
        ``CORPT00.CPY``. ``None`` when the operation succeeded and no
        remarks are needed; populated on failure with a human-readable
        reason, or on success with a positive confirmation string
        (e.g. ``"Report submitted for processing."``).

    Raises
    ------
    pydantic.ValidationError
        * When ``confirm`` is not one of ``'Y'`` or ``'N'``.
        * When ``message`` exceeds 78 characters
          (enforced by the ``max_length`` :class:`~pydantic.Field`
          constraint derived from ``ERRMSGI`` PIC X(78)).
    """

    report_id: str = Field(
        ...,
        description=(
            "Server-generated report submission identifier (e.g., "
            "UUID or SQS FIFO MessageId). Opaque to the client."
        ),
    )
    report_type: ReportType = Field(
        ...,
        description=(
            "Echo of the report type submitted in the request — one of "
            "monthly / yearly / custom."
        ),
    )
    confirm: str = Field(
        ...,
        max_length=1,
        description=(
            "Confirmation indicator — 'Y' (submitted) or 'N' (rejected). "
            "Maps to CORPT00 CONFIRMI PIC X(1)."
        ),
    )
    message: Optional[str] = Field(  # noqa: UP045  # schema requires typing.Optional
        default=None,
        max_length=_ERRMSG_MAX_LEN,
        description=(
            "Optional info/error message, max 78 chars. Maps to CORPT00 "
            "ERRMSGI PIC X(78)."
        ),
    )

    @field_validator("confirm")
    @classmethod
    def _validate_confirm(cls, value: str) -> str:
        """Enforce the CORPT00 ``CONFIRMI`` PIC X(1) domain: ``'Y'`` or ``'N'``.

        The COBOL field accepts any single character, but the
        application convention across every CardDemo confirm/cancel
        screen is upper-case ``'Y'`` / ``'N'`` — matching the
        ``VALID-CONFIRM`` 88-level constraint implicitly enforced by
        the CICS logic in ``CORPT00C.cbl``.
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
                f"(CORPT00 CONFIRMI PIC X(1)); got {value!r}"
            )
        return value


__all__: list[str] = [
    "ReportType",
    "ReportSubmissionRequest",
    "ReportSubmissionResponse",
]
