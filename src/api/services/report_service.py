# ============================================================================
# Source: app/cbl/CORPT00C.cbl (CICS report submission program, CR00
#         transaction — 649 lines of COBOL, Feature F-022)
#       + app/cpy-bms/CORPT00.CPY (BMS symbolic map for CORPT0A screen)
# ============================================================================
# Mainframe-to-Cloud migration:
#
#   CICS ``EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)
#   LENGTH(LENGTH OF JCL-RECORD)`` (writing a multi-record JCL deck —
#   JOB card, EXEC card, DD statements, PARM lines, ``/*EOF``
#   sentinel — onto the CICS Transient Data Queue named ``'JOBS'``,
#   consumed by an external job-scheduler that launched the
#   ``TRANREPT`` batch report via the internal reader)
#
# becomes
#
#   boto3 ``sqs_client.send_message(QueueUrl=..., MessageBody=...,
#   MessageGroupId='report-submissions',
#   MessageDeduplicationId=report_id)`` — publishing a single JSON
#   message to an AWS SQS FIFO queue that is consumed downstream by a
#   worker which launches the equivalent AWS Glue job
#   (``src/batch/jobs/tranrept_job.py``, derived from
#   ``CBTRN03C.cbl`` + ``TRANREPT.jcl``).
#
# FIFO semantics — guaranteed ordering and exactly-once processing
# within a message group — are used to faithfully preserve the
# sequential behavior of the original CICS TDQ, which processed
# requests in arrival order on an internal reader. The
# ``MessageDeduplicationId`` is set to the server-generated
# ``report_id`` (a UUID v4) so that accidental retries on the client
# side cannot enqueue duplicate work.
#
# The target deployment is AWS ECS Fargate behind an Application Load
# Balancer. SQS access is granted via the ECS task role (IAM
# role-based authentication, per AAP 0.7.2 — no access keys). In
# local development (docker-compose + LocalStack) the
# ``SQS_QUEUE_URL`` environment variable is either empty (triggering
# the local-log fallback) or points at the LocalStack endpoint
# (configured via ``AWS_ENDPOINT_URL``).
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
"""Report submission service.

Converted from ``app/cbl/CORPT00C.cbl`` (CICS transaction CR00,
649 lines of COBOL — Feature F-022). Replaces the CICS ``WRITEQ TD
QUEUE('JOBS')`` call (writing a multi-record JCL deck onto the
Transient Data Queue consumed by an external job-scheduler) with a
single JSON message published to an AWS SQS FIFO queue.

Service contract
----------------
The public entry point is :meth:`ReportService.submit_report`, which
consumes a
:class:`~src.shared.schemas.report_schema.ReportSubmissionRequest`
and returns a
:class:`~src.shared.schemas.report_schema.ReportSubmissionResponse`.

This service owns NO database session — it is a pure messaging
adapter. Pydantic validation on
:class:`ReportSubmissionRequest` has already enforced:

* ``report_type`` is one of ``monthly`` / ``yearly`` / ``custom``
  (three mutually-exclusive BMS radio-button fields ``MONTHLYI``,
  ``YEARLYI``, ``CUSTOMI`` consolidated into a single enum);
* ``start_date`` and ``end_date`` (when supplied) are strict
  ``YYYY-MM-DD`` strings resolvable to valid calendar dates;
* for ``report_type == custom`` both dates are present;
* when both dates are present, ``end_date >= start_date``.

:meth:`submit_report` therefore does not re-validate these
invariants — it trusts the upstream Pydantic schema contract, which
mirrors the CORPT00C paragraphs ``1200-PROCESS-MONTHLY``,
``1210-PROCESS-YEARLY``, and ``1220-PROCESS-CUSTOM`` validation
cascade.

COBOL → Python flow mapping (``CORPT00C.cbl`` PROCEDURE DIVISION):

==================================================  ==========================================
COBOL paragraph / statement                         Python equivalent (this module)
==================================================  ==========================================
``PROCESS-ENTER-KEY`` (entry)                       :meth:`ReportService.submit_report`
``EVALUATE TRUE`` (MONTHLYI/YEARLYI/CUSTOMI)        ``request.report_type`` (enum — upstream)
``1200-PROCESS-MONTHLY`` (date assembly)            (absorbed into Pydantic request schema)
``1210-PROCESS-YEARLY`` (date assembly)             (absorbed into Pydantic request schema)
``1220-PROCESS-CUSTOM`` (EDIT-DATE-CCYYMMDD)        (absorbed into Pydantic request schema)
``SUBMIT-JOB-TO-INTRDR`` (JCL deck builder)         :meth:`_build_message_body`
``INITIALIZE JOB-DATA``                             fresh ``dict`` per call
``MOVE ... TO JOB-LINES(n)`` (80-char records)      ``dict`` keys set directly
``WIRTE-JOBSUB-TDQ`` (CICS WRITEQ TD)               ``sqs_client.send_message(...)``
``EXEC CICS WRITEQ TD QUEUE('JOBS')`` (L517-523)    ``send_message(QueueUrl=..., ...)``
``DFHRESP(NORMAL)`` path                            successful ``response["MessageId"]``
``DFHRESP(OTHER)`` path (L528)                      ``except Exception as exc``
``'Unable to Write TDQ (JOBS)...'`` error (L531)    error message returned in response
``PERFORM SEND-TRNRPT-SCREEN``                      ``ReportSubmissionResponse`` assembled
==================================================  ==========================================

Local-development fallback
--------------------------
When :attr:`~src.shared.config.settings.Settings.SQS_QUEUE_URL` is
empty (the default in local development — see AAP 0.6.2) the service
logs the message at ``INFO`` level via the standard
:mod:`logging` module and returns ``confirm='Y'`` with a
``"Report logged (SQS disabled in local dev)."`` acknowledgement.
This allows the FastAPI test client and the docker-compose stack to
exercise the full request/response path without an AWS / LocalStack
dependency.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (services layer)
AAP §0.5.1 — File-by-File Transformation Plan (``report_service.py`` row)
AAP §0.6.2 — AWS Service Dependencies (SQS FIFO replaces CICS TDQ)
AAP §0.7.2 — Monitoring Requirements (CloudWatch-friendly JSON logging)
"""

import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from src.shared.config.aws_config import get_sqs_client
from src.shared.config.settings import Settings
from src.shared.schemas.report_schema import (
    ReportSubmissionRequest,
    ReportSubmissionResponse,
    ReportType,
)

# ---------------------------------------------------------------------------
# Module logger
# ---------------------------------------------------------------------------
# Structured JSON logging for CloudWatch. The logger name
# (``src.api.services.report_service``) propagates through the FastAPI
# root logger configured in ``src/api/main.py`` and is picked up by the
# CloudWatch Logs agent on ECS Fargate.
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Private module constants
# ---------------------------------------------------------------------------
# SQS FIFO ``MessageGroupId`` — all report submissions share the same
# logical group so that the downstream Glue-launch worker processes them
# in strict arrival order (replicating the single-threaded CICS TDQ
# reader). Using one group means throughput is limited to the FIFO
# per-group cap (~3000 msg/s with high throughput enabled); this is
# vastly sufficient for a user-initiated batch-job submission flow.
_MESSAGE_GROUP_ID: str = "report-submissions"

# Success message returned in :class:`ReportSubmissionResponse.message`
# after the SQS ``send_message`` call succeeds (or the fallback log
# path executes). Mirrors the COBOL success string ``'<report-name>
# report submitted for printing ...'`` assembled in CORPT00C at the
# paragraph that flows into ``SEND-TRNRPT-SCREEN`` after a successful
# ``WIRTE-JOBSUB-TDQ``.  Kept under the 78-character ``ERRMSGI`` limit.
_SUCCESS_MSG: str = "Report submitted for processing."

# Message returned when the SQS queue URL is empty (local development
# fallback path — see the docstring of :meth:`submit_report`). Also
# kept within the 78-character ``ERRMSGI`` bound.
_FALLBACK_MSG: str = "Report logged (SQS disabled in local dev)."

# Error prefix used when ``send_message`` raises. Mirrors the COBOL
# error string ``'Unable to Write TDQ (JOBS)...'`` (CORPT00C line 531)
# that the CICS program wrote to ``WS-MESSAGE`` before re-displaying
# the screen via ``SEND-TRNRPT-SCREEN``.
_ERROR_MSG_PREFIX: str = "Unable to submit report"

# Maximum length of the error/info message in
# :class:`ReportSubmissionResponse.message` — matches the CORPT00
# ``ERRMSGI`` field declared ``PIC X(78)`` in the BMS symbolic map
# ``app/cpy-bms/CORPT00.CPY``. The Pydantic schema enforces this via
# ``max_length`` but we apply the truncation defensively here so the
# model-level ``ValidationError`` is never raised when stringifying an
# unexpectedly long boto3 exception message.
_ERRMSG_MAX_LEN: int = 78

# Confirm-flag domain from CORPT00 ``CONFIRMI PIC X(1)`` — upper-case
# single character. ``'Y'`` indicates the enqueue operation succeeded
# (or the fallback log path was taken); ``'N'`` indicates failure. The
# same literal constants are used by every CardDemo "confirm" screen.
_CONFIRM_YES: str = "Y"
_CONFIRM_NO: str = "N"


# ---------------------------------------------------------------------------
# ReportService — public service class
# ---------------------------------------------------------------------------
class ReportService:
    """Report submission service (converts ``CORPT00C.cbl``).

    The service publishes a report-generation request as a single
    JSON message to an AWS SQS FIFO queue. The queue URL is provided
    by :attr:`~src.shared.config.settings.Settings.SQS_QUEUE_URL`; the
    SQS client is built lazily via
    :func:`~src.shared.config.aws_config.get_sqs_client` (IAM
    role-based authentication — no hardcoded credentials per AAP
    §0.7.2).

    Attributes
    ----------
    _queue_url : str
        The SQS FIFO queue URL snapshotted at construction time.
        Empty string triggers the local-development log-only fallback
        path in :meth:`submit_report`.
    _sqs_client : Any
        Lazily-initialized boto3 ``sqs`` client — constructed on first
        use by :meth:`_get_client`. ``None`` until the first
        non-fallback :meth:`submit_report` call. Typed as ``Any``
        because boto3 client objects are dynamically generated and
        lack static type stubs.

    Notes
    -----
    * No database session — report submission is a pure messaging
      operation. The accompanying
      :mod:`src.api.services.auth_service`,
      :mod:`src.api.services.account_service`, etc. all take an
      :class:`~sqlalchemy.ext.asyncio.AsyncSession`; this service
      intentionally does not, matching the AAP contract
      ``__init__(self)`` with no parameters.
    * The public method is declared ``async`` so that router code can
      ``await`` it uniformly alongside the other async services; the
      boto3 call itself is synchronous (boto3 does not have a native
      async API) and executes inside the event loop — this is
      acceptable because ``send_message`` is a short-lived network
      call (<200ms typical) and the ECS Fargate deployment uses
      Uvicorn with multiple worker processes.
    """

    def __init__(self) -> None:
        """Construct the service.

        Reads ``SQS_QUEUE_URL`` from settings exactly once (at
        construction time) and prepares a placeholder for the lazily
        built SQS client. The ``Settings`` object is instantiated
        fresh here rather than cached at module level to keep the
        service testable — unit tests can override the environment
        variable and construct a new :class:`ReportService` to pick
        up the change, without any monkey-patching of module-level
        state.
        """
        # Snapshot the queue URL at construction. This is an
        # intentional tradeoff: callers must re-instantiate
        # :class:`ReportService` after changing ``SQS_QUEUE_URL``
        # (which is normal, since the value is an application-level
        # deployment configuration, not a per-request value).
        settings: Settings = Settings()
        self._queue_url: str = settings.SQS_QUEUE_URL

        # Lazy-initialized SQS client. Kept as ``None`` until the
        # first non-fallback submission so that the fallback (local
        # dev) path never pays the cost of constructing a boto3
        # client, AND so that test fixtures running without AWS
        # credentials aren't affected by client-construction time.
        self._sqs_client: Any = None

    # -----------------------------------------------------------------
    # Public API
    # -----------------------------------------------------------------
    async def submit_report(
        self,
        request: ReportSubmissionRequest,
    ) -> ReportSubmissionResponse:
        """Submit a report-generation request to the SQS FIFO queue.

        Replaces the CORPT00C ``SUBMIT-JOB-TO-INTRDR`` +
        ``WIRTE-JOBSUB-TDQ`` paragraph pair. The original COBOL
        program wrote up to 1000 80-character JCL records (the JOB
        card, EXEC card, DD statements, PARM lines, and a ``/*EOF``
        sentinel) onto the CICS TDQ ``'JOBS'``; this method replaces
        that with a single SQS FIFO message whose body is a compact
        JSON object carrying the report parameters.

        Parameters
        ----------
        request : ReportSubmissionRequest
            The validated request payload. Upstream Pydantic
            validation has already enforced:

            * ``report_type`` is one of ``monthly`` / ``yearly`` /
              ``custom``;
            * ``start_date`` / ``end_date`` (when supplied) are valid
              ``YYYY-MM-DD`` strings;
            * for ``report_type == custom`` both dates are provided;
            * ``end_date >= start_date`` when both are supplied.

        Returns
        -------
        ReportSubmissionResponse
            The outgoing response carrying:

            * ``report_id`` — the server-generated UUIDv4 used as
              both the return value and the SQS
              ``MessageDeduplicationId``;
            * ``report_type`` — echo of the request type;
            * ``confirm`` — ``'Y'`` on success / local-fallback,
              ``'N'`` on SQS error;
            * ``message`` — 78-character-or-shorter info / error
              message (maps to the CORPT00 ``ERRMSGI`` field).

        Notes
        -----
        This method never raises. SQS errors (any exception from
        ``boto3.client('sqs').send_message``) are caught and returned
        to the caller as ``confirm='N'`` with a human-readable
        error message. This matches CORPT00C, which on ``DFHRESP !=
        NORMAL`` set ``WS-MESSAGE = 'Unable to Write TDQ (JOBS)...'``
        and re-sent the screen rather than terminating the
        transaction.
        """
        # -------------------------------------------------------------
        # Step 1 — Generate the unique report submission ID.
        # -------------------------------------------------------------
        # The UUID v4 is used for TWO distinct purposes:
        #
        # 1. Returned to the client as
        #    :attr:`ReportSubmissionResponse.report_id` so the caller
        #    can correlate the submission with downstream batch-job
        #    artifacts (S3 objects written by the Glue job).
        # 2. Used as the SQS ``MessageDeduplicationId`` to guarantee
        #    exactly-once processing within the FIFO deduplication
        #    window (5 minutes by default).  Accidental client retries
        #    of the SAME submission therefore cannot enqueue a
        #    duplicate.
        #
        # There is no COBOL equivalent — CORPT00C did not generate a
        # per-submission identifier; it simply wrote the JCL deck
        # and relied on JES2 JOBNAME + incrementing JOB-ID for
        # uniqueness.
        report_id: str = str(uuid.uuid4())

        # -------------------------------------------------------------
        # Step 2 — Build the message body (replaces JCL deck).
        # -------------------------------------------------------------
        # The COBOL ``SUBMIT-JOB-TO-INTRDR`` paragraph constructed a
        # 1000-element ``JOB-LINES`` array of 80-character records
        # (JOB card + EXEC card + DD cards + PARM + ``/*EOF``); here
        # we condense that entire deck into a compact JSON object
        # containing only the business-significant parameters the
        # downstream Glue job needs. Infrastructure concerns (which
        # program to run, which datasets to mount, which class to
        # submit under) are encoded in the target Glue job definition
        # itself, not in this message.
        #
        # ``timezone.utc`` is used (rather than the Python 3.11+
        # ``datetime.UTC`` alias) because the file schema's
        # ``external_imports`` specification for the ``datetime``
        # module declares ``members_accessed=['timezone']`` — the
        # schema-mandated member must be retained verbatim.
        submitted_at: str = datetime.now(timezone.utc).isoformat()  # noqa: UP017
        message_body: dict[str, Any] = self._build_message_body(
            report_id=report_id,
            request=request,
            submitted_at=submitted_at,
        )

        # -------------------------------------------------------------
        # Step 3 — Local-development fallback.
        # -------------------------------------------------------------
        # When ``SQS_QUEUE_URL`` is empty (the production default for
        # local development — see AAP §0.6.2), we log the submission
        # at INFO level and return success. This allows the FastAPI
        # test client, pytest integration tests, and docker-compose
        # stacks to exercise the request/response path without
        # requiring AWS or LocalStack to be running.
        if not self._queue_url:
            logger.info(
                "Report submission captured (SQS disabled — local-dev "
                "fallback). report_id=%s report_type=%s "
                "start_date=%s end_date=%s body=%s",
                report_id,
                request.report_type.value,
                request.start_date,
                request.end_date,
                message_body,
            )
            return ReportSubmissionResponse(
                report_id=report_id,
                report_type=request.report_type,
                confirm=_CONFIRM_YES,
                message=_truncate_message(_FALLBACK_MSG),
            )

        # -------------------------------------------------------------
        # Step 4 — Publish to SQS FIFO queue.
        # -------------------------------------------------------------
        # This is the core CICS-TDQ-to-SQS conversion.
        #
        #   CICS:   EXEC CICS WRITEQ TD
        #             QUEUE('JOBS')
        #             FROM(JCL-RECORD)
        #             LENGTH(LENGTH OF JCL-RECORD)
        #             RESP(WS-RESP-CD)
        #             RESP2(WS-REAS-CD)
        #           END-EXEC.
        #
        #   Python: sqs_client.send_message(
        #               QueueUrl=self._queue_url,
        #               MessageBody=json.dumps(message_body),
        #               MessageGroupId=_MESSAGE_GROUP_ID,
        #               MessageDeduplicationId=report_id,
        #           )
        #
        # FIFO parameters:
        #   * ``MessageGroupId='report-submissions'`` — all report
        #     submissions share the same logical group so that
        #     downstream processing preserves arrival order
        #     (mirroring the sequential CICS TDQ reader).
        #   * ``MessageDeduplicationId=report_id`` — ensures
        #     exactly-once delivery within the 5-minute deduplication
        #     window; accidental client retries are dropped
        #     server-side by SQS.
        try:
            sqs_client: Any = self._get_client()
            sqs_response: dict[str, Any] = sqs_client.send_message(
                QueueUrl=self._queue_url,
                MessageBody=json.dumps(message_body),
                MessageGroupId=_MESSAGE_GROUP_ID,
                MessageDeduplicationId=report_id,
            )
        except Exception as exc:  # noqa: BLE001  # boto3 ClientError et al.
            # Mirrors CORPT00C lines 528-535:
            #
            #   WHEN OTHER
            #       DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
            #       MOVE 'Y'     TO WS-ERR-FLG
            #       MOVE 'Unable to Write TDQ (JOBS)...' TO
            #                       WS-MESSAGE
            #       MOVE -1       TO MONTHLYL OF CORPT0AI
            #       PERFORM SEND-TRNRPT-SCREEN
            #
            # In CloudWatch terms: log at ERROR level with full stack
            # trace (exc_info=True) so that the failing call can be
            # diagnosed, then return ``confirm='N'`` with a
            # truncated error message. We intentionally do not
            # re-raise: the caller (router layer) translates the
            # response into an HTTP 200 with an ``{"confirm":"N",
            # "message":"..."}`` body, matching the CICS behavior of
            # re-displaying the screen with an error rather than
            # terminating the transaction.
            logger.error(
                "Failed to publish report submission to SQS FIFO queue. "
                "report_id=%s report_type=%s queue_url=%s error=%s",
                report_id,
                request.report_type.value,
                self._queue_url,
                exc,
                exc_info=True,
            )
            error_detail: str = f"{_ERROR_MSG_PREFIX}: {exc}"
            return ReportSubmissionResponse(
                report_id=report_id,
                report_type=request.report_type,
                confirm=_CONFIRM_NO,
                message=_truncate_message(error_detail),
            )

        # -------------------------------------------------------------
        # Step 5 — Log successful publish for CloudWatch audit trail.
        # -------------------------------------------------------------
        # Extract the SQS-assigned MessageId so operators can
        # correlate the submission with queue-side metrics and the
        # downstream worker log entries. Defensive ``.get()`` in
        # case boto3 ever changes the response shape.
        sqs_message_id: Any = sqs_response.get("MessageId")
        if sqs_message_id is None:
            # Every real SQS response includes a ``MessageId``. A
            # missing value would indicate either an upstream boto3
            # behavior change or an unexpected stub being used in
            # tests — log at WARNING level so operators can spot the
            # anomaly in CloudWatch Logs Insights (``fields @message
            # | filter @message like /missing MessageId/``). The
            # request is still considered successfully enqueued; we
            # fall back to our UUID ``report_id`` for correlation.
            logger.warning(
                "SQS send_message returned a response without a "
                "MessageId. Falling back to client-generated "
                "report_id for correlation. report_id=%s "
                "queue_url=%s response=%s",
                report_id,
                self._queue_url,
                sqs_response,
            )
        logger.info(
            "Published report submission to SQS FIFO. report_id=%s report_type=%s sqs_message_id=%s queue_url=%s",
            report_id,
            request.report_type.value,
            sqs_message_id,
            self._queue_url,
        )

        # -------------------------------------------------------------
        # Step 6 — Return the successful response.
        # -------------------------------------------------------------
        # Maps to CORPT00C ``SEND-TRNRPT-SCREEN`` after the successful
        # ``WIRTE-JOBSUB-TDQ`` path. The COBOL program cleared WS-ERR-FLG
        # and set WS-MESSAGE to the success text before re-displaying
        # the screen; here we assemble the equivalent JSON response.
        return ReportSubmissionResponse(
            report_id=report_id,
            report_type=request.report_type,
            confirm=_CONFIRM_YES,
            message=_truncate_message(_SUCCESS_MSG),
        )

    # -----------------------------------------------------------------
    # Private helpers
    # -----------------------------------------------------------------
    def _get_client(self) -> Any:
        """Lazily build and cache the boto3 SQS client.

        The client is constructed via
        :func:`~src.shared.config.aws_config.get_sqs_client`, which
        uses IAM role-based authentication (no hardcoded credentials)
        and the shared retry policy (``max_attempts=3, mode='standard'``).
        Caching the client on the :class:`ReportService` instance
        avoids the per-call overhead of boto3 client construction
        (≈10-20ms) under steady request load.

        Returns
        -------
        Any
            A boto3 ``sqs`` low-level client. Typed as ``Any`` because
            boto3 client objects lack static type stubs.
        """
        if self._sqs_client is None:
            self._sqs_client = get_sqs_client()
        return self._sqs_client

    @staticmethod
    def _build_message_body(
        *,
        report_id: str,
        request: ReportSubmissionRequest,
        submitted_at: str,
    ) -> dict[str, Any]:
        """Build the JSON body for the SQS FIFO message.

        Replaces the COBOL JCL-deck construction in
        ``SUBMIT-JOB-TO-INTRDR`` (CORPT00C lines 369-510) that wrote
        up to 1000 80-character records (JOB card, EXEC card, DD
        statements, PARM lines, ``/*EOF`` sentinel).

        The message body is a compact JSON object rather than an
        escape-padded z/OS JCL stream — the downstream Glue-launch
        worker parses the JSON, selects the matching Glue job by
        ``report_type``, and invokes it with the supplied date range
        as Glue job arguments (for ``custom``) or the worker's own
        ``date_trunc`` derivation (for ``monthly`` / ``yearly``).

        Parameters
        ----------
        report_id : str
            UUID v4 generated by :meth:`submit_report`. Included in
            the message body for downstream audit / log correlation.
        request : ReportSubmissionRequest
            The validated incoming request — its ``report_type``,
            ``start_date``, and ``end_date`` fields flow into the
            message body.
        submitted_at : str
            ISO-8601 UTC timestamp string generated by
            :meth:`submit_report`. Recorded in the message body for
            audit trail purposes (so the Glue worker's CloudWatch
            logs can correlate ``submitted_at`` with ``started_at``
            / ``completed_at``).

        Returns
        -------
        dict[str, Any]
            JSON-serializable ``dict`` with keys: ``report_id``,
            ``report_type``, ``submitted_at``, and (conditionally)
            ``start_date``, ``end_date``. Keys absent when their
            corresponding request fields are ``None`` — keeping the
            message body compact and making the "no date supplied"
            case explicit for downstream parsers.
        """
        # Use the :class:`ReportType` enum :attr:`.value` so the
        # serialized JSON contains a plain string (``"monthly"``)
        # rather than the Python repr. This matches the Pydantic
        # serialization convention used by the
        # :class:`ReportSubmissionResponse` on the wire.
        body: dict[str, Any] = {
            "report_id": report_id,
            "report_type": _report_type_value(request.report_type),
            "submitted_at": submitted_at,
        }
        # Only include the date fields when supplied. This matches the
        # BMS-level semantics: ``SDT*`` / ``EDT*`` fields were
        # intentionally ignored when ``MONTHLYI`` or ``YEARLYI`` was
        # selected (per the CORPT00C attention-indicator logic). For
        # ``ReportType.monthly`` and ``ReportType.yearly`` the
        # downstream worker derives the date range itself (current
        # month start/end, current year start/end); for
        # ``ReportType.custom`` the worker uses the supplied dates
        # directly.
        if request.start_date is not None:
            body["start_date"] = request.start_date
        if request.end_date is not None:
            body["end_date"] = request.end_date
        return body


# ---------------------------------------------------------------------------
# Module-level helpers
# ---------------------------------------------------------------------------
def _truncate_message(message: str) -> str:
    """Truncate a status/error message to the CORPT00 ``ERRMSGI`` bound.

    The CORPT00 BMS field ``ERRMSGI`` was declared ``PIC X(78)`` in
    ``app/cpy-bms/CORPT00.CPY``; the corresponding Pydantic field
    :attr:`ReportSubmissionResponse.message` inherits that bound via
    ``max_length=78``. This helper truncates longer strings (e.g.,
    verbose boto3 exception messages) to fit the field without
    triggering a :class:`pydantic.ValidationError` at response
    construction time.

    Parameters
    ----------
    message : str
        The candidate message, of any length. ``None`` is NOT
        accepted — callers should pass the literal ``None`` directly
        to the response model rather than round-tripping through
        this helper.

    Returns
    -------
    str
        The message unchanged if ``len(message) <= 78``, otherwise
        the first 78 characters. Never raises.
    """
    if len(message) <= _ERRMSG_MAX_LEN:
        return message
    return message[:_ERRMSG_MAX_LEN]


def _report_type_value(report_type: ReportType) -> str:
    """Extract the underlying ``str`` value from a :class:`ReportType`.

    Because :class:`ReportType` is defined as
    ``class ReportType(str, Enum)``, the ``value`` attribute is the
    underlying string. We use ``.value`` explicitly rather than
    relying on ``str(report_type)`` (which, in some Python / Pydantic
    version combinations, returned ``"ReportType.monthly"`` — the
    enum repr — rather than ``"monthly"``).

    Parameters
    ----------
    report_type : ReportType
        The enum member — one of :attr:`ReportType.monthly`,
        :attr:`ReportType.yearly`, :attr:`ReportType.custom`.

    Returns
    -------
    str
        The underlying string value (``"monthly"``, ``"yearly"``, or
        ``"custom"``) — what the JSON SQS message body and the
        downstream Glue worker expect.
    """
    return report_type.value


__all__: list[str] = [
    "ReportService",
]
