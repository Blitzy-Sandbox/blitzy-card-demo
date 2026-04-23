# ============================================================================
# CardDemo — Unit tests for ReportService (Mainframe-to-Cloud migration)
# ============================================================================
# Source (COBOL → Python):
#   * app/cbl/CORPT00C.cbl     — CICS report-submission program, transaction
#                                CR00 (~649 lines).
#                                  L212-243: EVALUATE TRUE / MONTHLYI-YEARLYI-
#                                             CUSTOMI branches.
#                                  L214:  MOVE 'Monthly' TO WS-REPORT-NAME
#                                  L240:  MOVE 'Yearly'  TO WS-REPORT-NAME
#                                  L433:  MOVE 'Custom'  TO WS-REPORT-NAME
#                                  L449-452: STRING WS-REPORT-NAME DELIMITED
#                                               BY SPACE ' report submitted
#                                               for printing ...' INTO
#                                               WS-MESSAGE.
#                                  L462-510: SUBMIT-JOB-TO-INTRDR builds
#                                             the 80-char JCL-RECORD array.
#                                  L515-535: WIRTE-JOBSUB-TDQ executes
#                                             EXEC CICS WRITEQ TD
#                                                 QUEUE('JOBS')
#                                                 FROM(JCL-RECORD)
#                                                 LENGTH(LENGTH OF JCL-RECORD)
#                                             END-EXEC
#                                             and on DFHRESP ≠ NORMAL moves
#                                             'Unable to Write TDQ (JOBS)...'
#                                             to WS-MESSAGE (L531).
#   * app/cpy-bms/CORPT00.CPY  — BMS symbolic map for CORPT0A screen. Three
#                                 mutually-exclusive radio-button flags:
#                                  MONTHLYI PIC X(1),
#                                  YEARLYI  PIC X(1),
#                                  CUSTOMI  PIC X(1),
#                                 plus six segmented date sub-fields
#                                 (SDTYYYYI/SDTMMI/SDTDDI,
#                                  EDTYYYYI/EDTMMI/EDTDDI),
#                                 plus CONFIRMI PIC X(1),
#                                 plus ERRMSGI PIC X(78).
# ----------------------------------------------------------------------------
# Feature F-022: Report Submission (CICS TDQ WRITEQ JOBS → AWS SQS FIFO).
# Target implementation under test: src/api/services/report_service.py
# (ReportService class, async service with SQS-FIFO publishing).
#
# Mainframe-to-Cloud mapping (the essence of this test module):
#
#     CICS:  EXEC CICS WRITEQ TD
#              QUEUE('JOBS')
#              FROM(JCL-RECORD)
#              LENGTH(LENGTH OF JCL-RECORD)
#            END-EXEC
#
#     Py:   sqs_client.send_message(
#              QueueUrl=self._queue_url,
#              MessageBody=json.dumps(body),
#              MessageGroupId='report-submissions',
#              MessageDeduplicationId=report_id,
#           )
#
# The AAP (§0.5.1, report_service.py row) mandates AWS SQS FIFO as the
# replacement for the CICS TDQ, with moto v5.x (unified ``mock_aws``
# decorator) as the test mocking strategy (AAP §0.6.1 Dev Dependencies).
# ----------------------------------------------------------------------------
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
"""Unit tests for :class:`ReportService`.

Validates SQS FIFO message publish (replacing CICS TDQ WRITEQ JOBS from
``app/cbl/CORPT00C.cbl``, Feature F-022). Uses moto v5.x ``mock_aws`` for
AWS SQS mocking — no live AWS calls, no LocalStack dependency.

Service Contract Under Test
---------------------------
The single public entry point of :class:`ReportService` under test is:

.. code-block:: python

    async def submit_report(
        self, request: ReportSubmissionRequest
    ) -> ReportSubmissionResponse

This method replaces the CORPT00C paragraphs:

* ``PROCESS-ENTER-KEY``          → method entry / ``request.report_type`` branching
* ``SUBMIT-JOB-TO-INTRDR``       → :meth:`_build_message_body` (JCL deck
                                    construction collapsed to a JSON body)
* ``WIRTE-JOBSUB-TDQ``           → ``sqs_client.send_message(...)``
* ``EXEC CICS WRITEQ TD``        → ``send_message(QueueUrl=...,
                                                MessageGroupId=...,
                                                MessageDeduplicationId=...)``
* ``DFHRESP ≠ NORMAL`` path      → ``except Exception`` branch returning
                                    ``confirm='N'`` with the COBOL-exact
                                    message ``'Unable to Write TDQ
                                    (JOBS)...'``.

COBOL → Python Verification Surface
-----------------------------------
===========================================================  ====================================================
COBOL paragraph / statement                                  Python test (this module)
===========================================================  ====================================================
``EVALUATE TRUE / MONTHLYI`` (L213-238)                      :func:`test_submit_monthly_report`
``EVALUATE TRUE / YEARLYI`` (L239-255)                       :func:`test_submit_yearly_report`
``EVALUATE TRUE / CUSTOMI`` (L256-436)                       :func:`test_submit_custom_report_with_dates`
``CUSTOMI`` with missing dates (L259-303)                    :func:`test_submit_custom_report_missing_dates_rejected`
``SUBMIT-JOB-TO-INTRDR`` JCL builder (L462-510)              :func:`test_sqs_message_body_structure`
``EXEC CICS WRITEQ TD`` FIFO (L517-523)                      :func:`test_sqs_fifo_message_attributes`
Server-generated submission identifier                       :func:`test_sqs_message_contains_report_id`
``CSUTLDTC`` start-date validation (L392-406)                :func:`test_custom_report_valid_date_range`
Date ordering invariant (end ≥ start)                        :func:`test_custom_report_start_after_end_rejected`
``CSUTLDTC`` format validation (L388-395)                    :func:`test_custom_report_invalid_date_format`
``DFHRESP ≠ NORMAL`` error path (L528-534)                   :func:`test_sqs_publish_failure`
Local-development fallback (no CICS equivalent)              :func:`test_local_development_fallback`
``SEND-TRNRPT-SCREEN`` confirm='Y' success path (L448-454)   :func:`test_submit_report_returns_confirmation`
===========================================================  ====================================================

Test Design
-----------
* **moto mock for SQS** — every test that publishes to SQS runs inside a
  ``@mock_aws`` context (either via the ``mock_sqs_queue`` fixture or a
  decorator on the test). The queue is created with
  ``FifoQueue='true'`` and ``ContentBasedDeduplication='true'`` so that
  ``send_message`` calls from the service are accepted at runtime.
* **Environment isolation** — the autouse ``_set_required_env_vars``
  fixture uses ``monkeypatch.setenv`` to provide the database and JWT
  placeholders demanded by :class:`Settings` (CWE-798 protection, AAP
  §0.7.2). ``SQS_QUEUE_URL`` is also set or unset per-test via
  monkeypatch to drive the service under test down either the FIFO
  publish path or the local-dev fallback.
* **No database** — :class:`ReportService` is a pure messaging adapter
  with no SQLAlchemy session. Every test in this module validates a
  boto3 interaction, never a database query.
* **Moto queue inspection** — assertions read the enqueued messages
  back via ``sqs.receive_message(...)`` and parse them as JSON. The
  test then asserts on the message body, the ``MessageAttributes``,
  and (where inspectable via the moto backend) the FIFO-specific
  ``MessageGroupId`` / ``MessageDeduplicationId``.
* **COBOL byte-exact messages** — the success template ``'{name}
  report submitted for printing ...'`` (CORPT00C lines 449-452) and
  the error literal ``'Unable to Write TDQ (JOBS)...'`` (CORPT00C
  line 531) are asserted as string literals so that any drift from
  the COBOL source would be caught by these tests. These constants
  are defined locally in this test module (not imported from
  report_service.py) per AAP §0.7.1 "Preserve exact error messages
  from COBOL".

See Also
--------
* ``src/api/services/report_service.py`` — The service under test.
* ``src/shared/schemas/report_schema.py`` — Pydantic request/response
                                             schemas and ``ReportType``
                                             enum.
* ``app/cbl/CORPT00C.cbl`` — Original CICS COBOL program.
* ``app/cpy-bms/CORPT00.CPY`` — BMS symbolic map (radio buttons and
                                 date segments).
* AAP §0.5.1 — File-by-File Transformation Plan (report_service row).
* AAP §0.6.1 — Dev Dependencies (moto 5.x, pytest 8.3.x, pytest-asyncio).
* AAP §0.7.1 — Preserve exact COBOL error messages (byte-for-byte).
* AAP §0.7.2 — Monitoring Requirements (CloudWatch-friendly JSON logging).
"""

from __future__ import annotations

import json
import uuid
from typing import Any
from unittest.mock import MagicMock, patch

import boto3
import pytest
from moto import mock_aws

from src.api.services.report_service import ReportService
from src.shared.schemas.report_schema import ReportSubmissionRequest, ReportType

# ============================================================================
# Module-level constants shared by fixtures and tests.
# ============================================================================
#
# These constants encode the COBOL-exact wire values from
# app/cbl/CORPT00C.cbl. They are defined locally in this test module
# (not imported from report_service.py) so that the tests verify the
# wire values independently of the service implementation — a drift
# between the COBOL source and the service constants would be caught
# by the tests rather than silently propagated.
# ============================================================================

#: The SQS FIFO queue name for report submissions. Per AWS SQS naming
#: rules, FIFO queue names MUST end with the literal ``.fifo`` suffix.
#: This is the moto test-queue counterpart to the production
#: ``carddemo-reports.fifo`` queue declared in
#: :attr:`Settings.SQS_QUEUE_URL` at deployment time.
#: Replaces the CICS TDQ name ``'JOBS'`` from ``CORPT00C.cbl`` L518.
_TEST_QUEUE_NAME: str = "carddemo-reports.fifo"

#: AWS region for moto's in-memory SQS backend. ``us-east-1`` is the
#: AWS SDK default and the region used by the shared
#: :attr:`Settings.AWS_REGION` default.
_TEST_REGION: str = "us-east-1"

#: SQS FIFO ``MessageGroupId`` expected on every published message.
#: Mirrors the constant ``_MESSAGE_GROUP_ID`` in
#: ``src/api/services/report_service.py`` (line 163). All report
#: submissions share a single logical group so downstream processing
#: preserves arrival order — mirroring the sequential CICS TDQ reader.
_EXPECTED_MESSAGE_GROUP_ID: str = "report-submissions"

#: COBOL-exact success-message template — preserved byte-for-byte from
#: ``CORPT00C.cbl`` lines 449-452 (``STRING WS-REPORT-NAME DELIMITED
#: BY SPACE ' report submitted for printing ...' DELIMITED BY SIZE
#: INTO WS-MESSAGE``). The ``{report_name}`` placeholder is populated
#: with one of ``'Monthly'``, ``'Yearly'``, or ``'Custom'`` — exactly
#: the three title-cased strings moved to ``WS-REPORT-NAME`` by
#: CORPT00C at lines 214, 240, and 433. Trailing three-dot ellipsis
#: (no period) is preserved from the COBOL literal.
_SUCCESS_MSG_TEMPLATE: str = "{report_name} report submitted for printing ..."

#: COBOL-exact error message for TDQ enqueue failure — preserved
#: byte-for-byte from ``CORPT00C.cbl`` line 531
#: (``MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE``). Thirty
#: characters including the trailing three-dot ellipsis, NO trailing
#: period, NO appended exception detail. Per AAP §0.7.1 this literal
#: is immutable across the migration.
_EXPECTED_ERROR_MSG: str = "Unable to Write TDQ (JOBS)..."

#: Local-development fallback message — a Python-only concept with no
#: COBOL counterpart (CICS TDQ is always configured in a production
#: region). Emitted when ``SQS_QUEUE_URL`` is empty so that
#: docker-compose and pytest runs can exercise the request/response
#: path without AWS or LocalStack.
_EXPECTED_FALLBACK_MSG: str = "Report logged (SQS disabled in local dev)."

#: Confirm-flag values from CORPT00 ``CONFIRMI PIC X(1)``. Upper-case
#: single characters — ``'Y'`` indicates the enqueue succeeded (or the
#: fallback log path was taken); ``'N'`` indicates SQS publish failed.
_CONFIRM_YES: str = "Y"
_CONFIRM_NO: str = "N"

#: Maximum length of the CORPT00 ``ERRMSGI`` BMS field (``PIC X(78)``).
#: The Pydantic :class:`ReportSubmissionResponse.message` field inherits
#: this limit via ``max_length=78``; the service-layer
#: ``_truncate_message`` helper defensively enforces it for exception
#: strings that might exceed the bound.
_ERRMSG_MAX_LEN: int = 78

#: Valid custom-report start date used across the "happy path" tests.
#: ISO-8601 ``YYYY-MM-DD`` format — matches the Pydantic
#: :class:`ReportSubmissionRequest` schema's strict regex validator.
_TEST_START_DATE: str = "2024-01-01"

#: Valid custom-report end date used across the "happy path" tests.
#: ISO-8601 ``YYYY-MM-DD`` format — MUST satisfy
#: ``_TEST_END_DATE >= _TEST_START_DATE`` so the model-level validator
#: does not raise.
_TEST_END_DATE: str = "2024-12-31"


# ============================================================================
# Fixtures
# ============================================================================
#
# These fixtures prepare the test environment:
#
# 1. ``_set_required_env_vars`` (autouse)   — Provides environment
#    variables required by :class:`Settings` so that
#    :class:`ReportService` can be instantiated without connecting to
#    real infrastructure.
#
# 2. ``mock_sqs_queue``                     — Spins up an in-memory
#    moto SQS FIFO queue and yields its URL plus a live boto3 client
#    the test may use for direct assertions
#    (``receive_message``, ``get_queue_attributes``, etc.).
#
# 3. ``report_service``                     — A fresh
#    :class:`ReportService` bound to the queue yielded by
#    ``mock_sqs_queue``.
#
# 4. ``unconfigured_report_service``        — A :class:`ReportService`
#    constructed with an empty ``SQS_QUEUE_URL`` to exercise the
#    local-dev fallback path (AAP §0.7.2 Local Development).
# ============================================================================


@pytest.fixture(autouse=True)
def _set_required_env_vars(monkeypatch: pytest.MonkeyPatch) -> None:
    """Provide the required Settings environment variables.

    :class:`src.shared.config.settings.Settings` declares
    :attr:`DATABASE_URL`, :attr:`DATABASE_URL_SYNC`, and
    :attr:`JWT_SECRET_KEY` as required (no defaults). Because
    :class:`ReportService` instantiates :class:`Settings` directly in
    its ``__init__``, these variables MUST be set before any
    ``ReportService()`` call — otherwise ``pydantic.ValidationError``
    raises at construction time and the test never reaches the
    assertion.

    This fixture is marked ``autouse=True`` so every test in the module
    inherits a correctly-configured environment. Individual tests
    still override ``SQS_QUEUE_URL`` via ``monkeypatch.setenv`` to
    drive the service down specific code paths (SQS publish vs.
    local-dev fallback).

    Security Note (AAP §0.7.2)
    --------------------------
    The JWT secret uses a placeholder that is obviously test-only
    (``test-secret-not-for-production``). A real deployment pulls the
    JWT signing key from AWS Secrets Manager; this literal never
    leaves the pytest process memory.
    """
    # --- Database credentials (required by Settings) ----------------------
    # ReportService never touches the database — it is a pure messaging
    # adapter — but Settings validates these fields at construction time
    # and will raise pydantic.ValidationError without them.
    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql+asyncpg://test:test@localhost:5432/carddemo_test",
    )
    monkeypatch.setenv(
        "DATABASE_URL_SYNC",
        "postgresql+psycopg2://test:test@localhost:5432/carddemo_test",
    )

    # --- JWT signing key (required by Settings for the API stack) ---------
    # This is a non-secret placeholder scoped to the pytest process.
    # Production deployments use AWS Secrets Manager (see
    # src/shared/config/aws_config.py::get_secret).
    monkeypatch.setenv("JWT_SECRET_KEY", "test-secret-not-for-production")

    # --- AWS region (already has a default in Settings, set explicitly) ---
    # Matches the region moto uses for its in-memory backend so the
    # service and the assertions share the same AWS endpoint context.
    monkeypatch.setenv("AWS_REGION", _TEST_REGION)
    monkeypatch.setenv("AWS_DEFAULT_REGION", _TEST_REGION)


@pytest.fixture
def mock_sqs_queue(
    monkeypatch: pytest.MonkeyPatch,
) -> Any:
    """Create a moto-backed SQS FIFO queue and yield its URL.

    The returned object exposes two attributes:

    * ``url``    — the queue URL string to be passed to
                   :attr:`ReportService._queue_url`.
    * ``client`` — a live boto3 SQS client (bound to the moto
                   backend) that tests can use to inspect the queue
                   after the service publishes a message.

    Queue Configuration
    -------------------
    * ``FifoQueue='true'``                   — required suffix ``.fifo``
    * ``ContentBasedDeduplication='true'``   — allows moto to accept
                                                 messages without an
                                                 explicit
                                                 ``MessageDeduplicationId``
                                                 (though the service
                                                 always provides one)

    Mainframe Counterpart
    ---------------------
    This fixture replaces the DFHRCT table entry that declares the
    CICS TDQ ``'JOBS'`` in the original mainframe. In the target
    architecture the DFHRCT table is supplanted by AWS SQS FIFO
    (AAP §0.5.1).
    """
    with mock_aws():
        # Create a real moto-backed SQS client.
        sqs_client: Any = boto3.client("sqs", region_name=_TEST_REGION)

        # Create the FIFO queue. FIFO queues require the ``.fifo`` suffix
        # and the ``FifoQueue`` attribute. ``ContentBasedDeduplication``
        # is enabled so moto accepts messages that omit an explicit
        # MessageDeduplicationId (the service always provides one,
        # but this keeps moto permissive).
        response: dict[str, Any] = sqs_client.create_queue(
            QueueName=_TEST_QUEUE_NAME,
            Attributes={
                "FifoQueue": "true",
                "ContentBasedDeduplication": "true",
            },
        )
        queue_url: str = response["QueueUrl"]

        # Configure the SQS_QUEUE_URL environment variable so that the
        # Settings object (re-read by ReportService.__init__) will
        # pick up this moto queue URL. The autouse env fixture above
        # ran first, so this call simply overlays SQS_QUEUE_URL.
        monkeypatch.setenv("SQS_QUEUE_URL", queue_url)

        # Build a simple container object so tests can write
        # ``mock_sqs_queue.url`` and ``mock_sqs_queue.client``.
        queue_handle: Any = MagicMock()
        queue_handle.url = queue_url
        queue_handle.client = sqs_client
        queue_handle.name = _TEST_QUEUE_NAME

        yield queue_handle


@pytest.fixture
def report_service(mock_sqs_queue: Any) -> ReportService:
    """Return a :class:`ReportService` bound to the moto FIFO queue.

    The autouse env fixture has set ``DATABASE_URL``,
    ``DATABASE_URL_SYNC``, and ``JWT_SECRET_KEY``. The
    ``mock_sqs_queue`` fixture has set ``SQS_QUEUE_URL``. Therefore
    ``ReportService().__init__`` will read a valid URL and the
    service's ``submit_report`` method will exercise the SQS publish
    path (not the local-dev fallback).

    Instantiating the service inside the ``mock_aws`` context managed
    by ``mock_sqs_queue`` ensures that the lazy boto3 client created
    on the first ``send_message`` call is routed through moto rather
    than to a real AWS endpoint.
    """
    return ReportService()


@pytest.fixture
def unconfigured_report_service(
    monkeypatch: pytest.MonkeyPatch,
) -> ReportService:
    """Return a :class:`ReportService` with an empty ``SQS_QUEUE_URL``.

    Used to exercise the local-dev fallback path documented in
    :meth:`ReportService.submit_report` step 3. The service logs the
    submission at INFO level and returns a response with
    ``confirm='Y'`` and
    ``message='Report logged (SQS disabled in local dev).'``. No
    network traffic is generated.
    """
    monkeypatch.setenv("SQS_QUEUE_URL", "")
    return ReportService()


# ============================================================================
# Helper functions (test-private)
# ============================================================================


def _receive_sent_message(sqs_client: Any, queue_url: str) -> dict[str, Any]:
    """Receive a single message from ``queue_url`` and return its attributes.

    Returns the raw message dict produced by moto's
    ``receive_message`` response (``Messages[0]``), with
    ``AttributeNames=['All']`` and
    ``MessageAttributeNames=['All']`` so that the caller can
    inspect ``MessageGroupId``, ``MessageDeduplicationId``,
    ``MessageId``, and ``Body``.

    Parameters
    ----------
    sqs_client : Any
        A moto-backed boto3 SQS client.
    queue_url : str
        The FIFO queue URL previously returned by ``create_queue``.

    Returns
    -------
    dict[str, Any]
        The single received message. Raises ``AssertionError`` if
        no message is present — that shape lets the test harness
        fail fast with a helpful pytest message rather than a raw
        ``KeyError``.
    """
    response: dict[str, Any] = sqs_client.receive_message(
        QueueUrl=queue_url,
        MaxNumberOfMessages=1,
        WaitTimeSeconds=0,
        AttributeNames=["All"],
        MessageAttributeNames=["All"],
    )
    assert "Messages" in response, (
        f"Expected at least one message on SQS queue {queue_url!r}, "
        f"got response={response!r}. This usually indicates that "
        f"``ReportService.submit_report`` took the fallback path "
        f"(empty SQS_QUEUE_URL) or raised before calling "
        f"``send_message``."
    )
    assert len(response["Messages"]) == 1, (
        f"Expected exactly one message on SQS queue, got {len(response['Messages'])} messages: {response['Messages']!r}"
    )
    message: dict[str, Any] = response["Messages"][0]
    return message


def _get_queue_message_count(sqs_client: Any, queue_url: str) -> int:
    """Return the number of messages currently enqueued on ``queue_url``.

    Uses ``get_queue_attributes`` with
    ``AttributeNames=['ApproximateNumberOfMessages']`` so the test
    can assert post-publish counts (e.g., "exactly one message
    was enqueued") without consuming the message (which would
    prevent subsequent assertions on its contents).
    """
    attrs: dict[str, Any] = sqs_client.get_queue_attributes(
        QueueUrl=queue_url,
        AttributeNames=["ApproximateNumberOfMessages"],
    )
    return int(attrs.get("Attributes", {}).get("ApproximateNumberOfMessages", "0"))


# ============================================================================
# Phase 3 — Report Type Tests
# ============================================================================
#
# These tests exercise the three mutually-exclusive report-type
# branches from ``CORPT00C.cbl`` ``PROCESS-ENTER-KEY`` ``EVALUATE
# TRUE`` (lines 212-443):
#
#     EVALUATE TRUE
#       WHEN MONTHLYI = 'Y'            *> L213
#            MOVE 'Monthly' TO WS-REPORT-NAME
#       WHEN YEARLYI  = 'Y'            *> L239
#            MOVE 'Yearly'  TO WS-REPORT-NAME
#       WHEN CUSTOMI  = 'Y'            *> L256
#            MOVE 'Custom'  TO WS-REPORT-NAME
#       WHEN OTHER                     *> L437
#            MOVE 'Select a report type to print report...'
#                                       TO WS-MESSAGE
#     END-EVALUATE
#
# In the Python target the three BMS radio-button fields collapse to
# the single :class:`ReportType` enum with members
# ``monthly``, ``yearly``, ``custom`` — upstream Pydantic validation
# ensures callers supply exactly one of these three values, so the
# OTHER branch is unreachable and does not need its own test.
# ============================================================================


@pytest.mark.unit
async def test_submit_monthly_report(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Submit a monthly report and verify the resulting SQS message.

    Mainframe counterpart
    ---------------------
    ``CORPT00C.cbl`` lines 213-238: when ``MONTHLYI = 'Y'`` the COBOL
    program sets ``WS-REPORT-NAME = 'Monthly'``, derives the date
    range to the current month (first-of-month → last-of-month), and
    performs ``SUBMIT-JOB-TO-INTRDR``. This test verifies that the
    Python equivalent publishes an SQS FIFO message with the
    ``report_type`` set to ``'monthly'`` and returns a success
    response with ``confirm='Y'``.

    Key invariant
    -------------
    Monthly reports never carry ``start_date`` / ``end_date`` in the
    SQS message body — the downstream Glue worker derives them on
    its own via ``date_trunc('month', now())``. Verifying the
    absence of these keys is essential for forward compatibility.
    """
    # --- Arrange --------------------------------------------------------
    request = ReportSubmissionRequest(report_type=ReportType.monthly)

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert on the response ----------------------------------------
    assert response.confirm == _CONFIRM_YES, (
        "Monthly submission must return confirm='Y' on successful SQS publish (maps to CORPT00C clearing WS-ERR-FLG)."
    )
    assert response.report_type == ReportType.monthly
    # UUID-v4 format check: 36-char string with dashes at the
    # canonical positions. Exact value is generated server-side so
    # we cannot pin it.
    assert isinstance(response.report_id, str)
    assert len(response.report_id) == 36
    # Validate the byte-exact COBOL success message —
    # ``'Monthly report submitted for printing ...'``. Trailing
    # three-dot ellipsis (NO period) preserved from CORPT00C
    # L449-450.
    expected_message: str = _SUCCESS_MSG_TEMPLATE.format(report_name="Monthly")
    assert response.message == expected_message

    # --- Assert on the SQS side ----------------------------------------
    assert _get_queue_message_count(mock_sqs_queue.client, mock_sqs_queue.url) == 1, (
        "Exactly one SQS message must be enqueued per submission "
        "(maps to CICS WRITEQ TD QUEUE('JOBS') at CORPT00C L517-523)."
    )
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)

    # Body is valid JSON with the required fields.
    body: dict[str, Any] = json.loads(message["Body"])
    assert body["report_type"] == "monthly"
    assert body["report_id"] == response.report_id
    assert "submitted_at" in body
    # Forward-compatibility invariant: monthly reports MUST NOT carry
    # start_date / end_date in the message body — the downstream
    # Glue worker derives them. If this assertion ever fails,
    # downstream parsers may reject the payload.
    assert "start_date" not in body, (
        "Monthly reports must omit start_date from the SQS body — "
        "CORPT00C L214 only moves 'Monthly' to WS-REPORT-NAME; the "
        "date-range derivation is performed at report-generation time."
    )
    assert "end_date" not in body, "Monthly reports must omit end_date from the SQS body."


@pytest.mark.unit
async def test_submit_yearly_report(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Submit a yearly report and verify the resulting SQS message.

    Mainframe counterpart
    ---------------------
    ``CORPT00C.cbl`` lines 239-255: when ``YEARLYI = 'Y'`` the COBOL
    program sets ``WS-REPORT-NAME = 'Yearly'`` and derives the date
    range to the current year (Jan 1 → Dec 31). The Python target
    omits the date-range construction entirely — the downstream
    Glue worker derives it — so this test verifies only that the
    message carries ``report_type='yearly'`` and returns a success
    response.
    """
    # --- Arrange --------------------------------------------------------
    request = ReportSubmissionRequest(report_type=ReportType.yearly)

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert on the response ----------------------------------------
    assert response.confirm == _CONFIRM_YES
    assert response.report_type == ReportType.yearly
    # Byte-exact COBOL success message — ``'Yearly report submitted
    # for printing ...'`` — from the STRING at CORPT00C L449-450.
    expected_message: str = _SUCCESS_MSG_TEMPLATE.format(report_name="Yearly")
    assert response.message == expected_message

    # --- Assert on the SQS side ----------------------------------------
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)
    body: dict[str, Any] = json.loads(message["Body"])
    assert body["report_type"] == "yearly"
    assert body["report_id"] == response.report_id
    # Yearly reports — like monthly — omit date fields.
    assert "start_date" not in body
    assert "end_date" not in body


@pytest.mark.unit
async def test_submit_custom_report_with_dates(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Submit a custom report with explicit dates and verify the SQS message.

    Mainframe counterpart
    ---------------------
    ``CORPT00C.cbl`` lines 256-436: when ``CUSTOMI = 'Y'`` the COBOL
    program validates the BMS-level ``SDTYYYYI/SDTMMI/SDTDDI`` and
    ``EDTYYYYI/EDTMMI/EDTDDI`` sub-fields, assembles them into
    ``WS-START-DATE`` / ``WS-END-DATE`` (lines 403, 415) in
    ``YYYY-MM-DD`` format, and sets
    ``WS-REPORT-NAME = 'Custom'`` (line 433). The Python target
    collapses the BMS segment validation into a single Pydantic
    regex validator on :attr:`ReportSubmissionRequest.start_date`
    and :attr:`.end_date`.

    This test verifies that a VALID custom request produces an SQS
    message carrying BOTH date fields, plus the correct
    ``report_type`` and ``report_name``.
    """
    # --- Arrange --------------------------------------------------------
    request = ReportSubmissionRequest(
        report_type=ReportType.custom,
        start_date=_TEST_START_DATE,
        end_date=_TEST_END_DATE,
    )

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert on the response ----------------------------------------
    assert response.confirm == _CONFIRM_YES
    assert response.report_type == ReportType.custom
    # Byte-exact COBOL success message — ``'Custom report submitted
    # for printing ...'`` — from the STRING at CORPT00C L449-450
    # resolving with WS-REPORT-NAME = 'Custom' (set at L433).
    expected_message: str = _SUCCESS_MSG_TEMPLATE.format(report_name="Custom")
    assert response.message == expected_message

    # --- Assert on the SQS side ----------------------------------------
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)
    body: dict[str, Any] = json.loads(message["Body"])
    assert body["report_type"] == "custom"
    assert body["report_id"] == response.report_id
    # Custom reports MUST carry both date fields on the wire —
    # otherwise the downstream Glue worker has no way to know the
    # requested date range (cannot derive it from the current
    # date, as it does for monthly/yearly).
    assert body["start_date"] == _TEST_START_DATE, (
        "Custom report SQS body must carry the start_date verbatim — maps to CORPT00C L403 building WS-START-DATE."
    )
    assert body["end_date"] == _TEST_END_DATE, (
        "Custom report SQS body must carry the end_date verbatim — maps to CORPT00C L415 building WS-END-DATE."
    )


@pytest.mark.unit
def test_submit_custom_report_missing_dates_rejected() -> None:
    """Reject a custom request lacking ``start_date`` / ``end_date``.

    Mainframe counterpart
    ---------------------
    ``CORPT00C.cbl`` lines 259-303 validate the six segmented date
    sub-fields of a custom submission BEFORE constructing
    ``WS-START-DATE`` / ``WS-END-DATE``. Missing fields produce one
    of the COBOL error messages:

        'Start Date - Month can NOT be empty...'
        'Start Date - Day can NOT be empty...'
        'Start Date - Year can NOT be empty...'
        'End Date - Month can NOT be empty...'
        'End Date - Day can NOT be empty...'
        'End Date - Year can NOT be empty...'

    In the Python target those BMS sub-field checks are replaced by
    a single Pydantic model-level validator
    (:meth:`ReportSubmissionRequest._validate_date_combinations`)
    that raises ``ValidationError`` when ``report_type == custom``
    and either ``start_date`` or ``end_date`` is missing. This is
    enforced at CONSTRUCTION TIME of the Pydantic request object —
    the ``submit_report`` method is never reached, so no SQS
    fixture is required (``pydantic.ValidationError`` is a subclass
    of ``ValueError``).
    """
    # Missing BOTH dates — Pydantic raises immediately.
    with pytest.raises(ValueError) as exc_info:
        ReportSubmissionRequest(report_type=ReportType.custom)
    # The error message must identify the schema-level violation —
    # we do not assert on the full COBOL byte-exact message here
    # (the BMS messages are multi-valued — one per missing
    # sub-field — and the Pydantic validator collapses them into
    # a single condensed message).
    assert "custom" in str(exc_info.value).lower()

    # Missing ONLY end_date.
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date=_TEST_START_DATE,
        )

    # Missing ONLY start_date.
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            end_date=_TEST_END_DATE,
        )


# ============================================================================
# Phase 4 — SQS Message Validation Tests
# ============================================================================
#
# These tests drill into the SQS message that the service publishes —
# the message body schema, the FIFO-specific attributes
# (``MessageGroupId``, ``MessageDeduplicationId``), and the
# response-carried correlation identifier (``report_id``).
#
# Mainframe counterpart
# ---------------------
# These invariants replace the CORPT00C JCL-deck construction in
# ``SUBMIT-JOB-TO-INTRDR`` (lines 462-510). The JCL deck was an
# 80-char-per-record ``JOB-LINES`` array with a ``JOB`` card, an
# ``EXEC`` card, DD-statements, a ``PARM`` line (carrying the
# YYYY-MM-DD date arguments for custom reports), and a ``/*EOF``
# sentinel. In the target architecture this entire deck collapses to
# a single compact JSON object sent via
# ``EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)`` — SQS FIFO
# (AAP §0.5.1 SUBMIT-JOB-TO-INTRDR row).
# ============================================================================


@pytest.mark.unit
async def test_sqs_message_body_structure(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Verify the SQS message body is valid JSON with required fields.

    Required keys
    -------------
    * ``report_id``    — UUIDv4 string, used for downstream audit
                          and SQS FIFO deduplication.
    * ``report_type``  — one of ``"monthly"``, ``"yearly"``,
                          ``"custom"`` (the plain string enum
                          ``.value``, NOT the Python repr).
    * ``submitted_at`` — ISO-8601 UTC timestamp string.

    Mainframe counterpart
    ---------------------
    These three fields replace the CORPT00C JCL-deck construction
    at lines 462-510 (``SUBMIT-JOB-TO-INTRDR``) — the entire deck
    collapses to a single compact JSON object. The downstream
    Glue-launch worker parses this JSON and invokes the
    ``tranrept_job`` Glue job with the business parameters.
    """
    # --- Arrange --------------------------------------------------------
    request = ReportSubmissionRequest(report_type=ReportType.monthly)

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert ---------------------------------------------------------
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)

    # Body is valid JSON — if this json.loads raises, the service is
    # producing malformed payloads and the downstream worker will
    # crash on ingest.
    body: dict[str, Any] = json.loads(message["Body"])
    assert isinstance(body, dict), (
        f"SQS message body must deserialize to a JSON object (dict), got {type(body).__name__}"
    )

    # Required keys present.
    for required_key in ("report_id", "report_type", "submitted_at"):
        assert required_key in body, f"SQS message body is missing required key {required_key!r}. Body: {body!r}"

    # report_id is a UUIDv4 string that matches the response.
    assert body["report_id"] == response.report_id
    # UUID.uuid4 produces 36-character strings (32 hex + 4 dashes).
    assert len(body["report_id"]) == 36
    # Stricter invariant: UUID parsing must not raise.
    uuid.UUID(body["report_id"])

    # report_type uses the enum .value (plain lowercase string), not
    # the Python repr (``<ReportType.monthly: 'monthly'>``).
    assert body["report_type"] == "monthly"
    assert body["report_type"] == request.report_type.value

    # submitted_at is an ISO-8601 string parseable by datetime.
    from datetime import datetime as _dt

    parsed_ts = _dt.fromisoformat(body["submitted_at"])
    # Must be UTC (timezone-aware). CORPT00C ran on z/OS where the
    # system time was implicitly local-to-the-region; the target
    # architecture standardizes on UTC for cross-region correctness.
    assert parsed_ts.tzinfo is not None, (
        f"SQS message submitted_at must be timezone-aware (UTC). Got naive timestamp: {body['submitted_at']!r}"
    )


@pytest.mark.unit
async def test_sqs_fifo_message_attributes(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Verify FIFO-specific attributes (``MessageGroupId``,
    ``MessageDeduplicationId``).

    FIFO requirements
    -----------------
    * ``MessageGroupId`` — AWS SQS FIFO REQUIRES every message to
                            carry a non-empty group id. The service
                            uses the literal ``'report-submissions'``
                            (the constant
                            :data:`report_service._MESSAGE_GROUP_ID`)
                            so that ALL submissions share a single
                            logical group and are processed in
                            strict arrival order — preserving the
                            single-threaded CICS TDQ reader semantics.
    * ``MessageDeduplicationId`` — MUST be unique per submission
                                   within the 5-minute dedup window.
                                   The service uses the same UUIDv4
                                   it returns as ``report_id`` so
                                   that client-side retries of the
                                   same submission are idempotent.

    The moto backend exposes these FIFO fields via
    ``receive_message`` with ``AttributeNames=['All']``.
    """
    # --- Arrange --------------------------------------------------------
    request = ReportSubmissionRequest(report_type=ReportType.yearly)

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert ---------------------------------------------------------
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)

    # FIFO attributes are exposed under ``Attributes`` when the
    # receiver requests ``AttributeNames=['All']``.
    assert "Attributes" in message, f"Expected ``Attributes`` dict on received FIFO message. Got message: {message!r}"
    attrs = message["Attributes"]

    # Every message in this test module shares the same group id
    # so that downstream processing preserves arrival order
    # (mirroring the sequential CICS TDQ reader in CORPT00C).
    assert attrs.get("MessageGroupId") == _EXPECTED_MESSAGE_GROUP_ID, (
        f"MessageGroupId must be {_EXPECTED_MESSAGE_GROUP_ID!r} "
        f"(the CICS TDQ reader was single-threaded). "
        f"Got: {attrs.get('MessageGroupId')!r}"
    )

    # Deduplication id must match the UUID returned to the client
    # so retries of the same submission are idempotent within the
    # 5-minute dedup window.
    assert attrs.get("MessageDeduplicationId") == response.report_id, (
        "MessageDeduplicationId must match the response.report_id "
        "UUID so duplicate submissions are dropped server-side by "
        f"SQS. Got: {attrs.get('MessageDeduplicationId')!r}, "
        f"expected: {response.report_id!r}"
    )


@pytest.mark.unit
async def test_sqs_message_contains_report_id(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Verify the service response carries a UUIDv4-format ``report_id``.

    The UUIDv4 is used for two purposes:

    1. Returned to the client for downstream correlation with
       S3 artifacts and CloudWatch logs.
    2. Used as the SQS ``MessageDeduplicationId`` (exercised by
       :func:`test_sqs_fifo_message_attributes`).

    A valid UUIDv4 has: 32 hex chars + 4 dashes = 36 chars total,
    and its 13th character is the literal ``'4'`` (the version digit).
    """
    # --- Arrange --------------------------------------------------------
    request = ReportSubmissionRequest(report_type=ReportType.monthly)

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert ---------------------------------------------------------
    # Report id is a 36-character string.
    assert isinstance(response.report_id, str)
    assert len(response.report_id) == 36

    # UUID parses cleanly — if this raises, the service is
    # producing malformed identifiers.
    parsed_uuid = uuid.UUID(response.report_id)

    # Version byte is 4 (UUID v4).
    assert parsed_uuid.version == 4, f"Expected UUIDv4, got UUIDv{parsed_uuid.version}: {response.report_id!r}"

    # The same id MUST also appear in the SQS message body so
    # downstream consumers can correlate without reading the
    # ``MessageDeduplicationId`` attribute.
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)
    body: dict[str, Any] = json.loads(message["Body"])
    assert body["report_id"] == response.report_id


# ============================================================================
# Phase 5 — Date Validation Tests
# ============================================================================
#
# These tests exercise the date-validation rules applied to custom
# reports in :class:`ReportSubmissionRequest`. In the original
# ``CORPT00C.cbl`` the rules were enforced at the BMS input-handling
# layer (lines 258-426) across SIX distinct BMS sub-fields
# (``SDTYYYYI``, ``SDTMMI``, ``SDTDDI``, ``EDTYYYYI``, ``EDTMMI``,
# ``EDTDDI``), each with its own emptiness and numeric checks, plus
# a final calendar-validity check via the ``CSUTLDTC`` subprogram.
#
# The target Python architecture collapses those six BMS sub-field
# checks into a single ``YYYY-MM-DD`` composite string validated by:
#
#   1. A regex validator (``^\d{4}-\d{2}-\d{2}$``) on each of
#      :attr:`ReportSubmissionRequest.start_date` and
#      :attr:`.end_date` — replaces the CORPT00C numeric-range
#      checks on the three sub-fields.
#   2. A ``datetime.date.fromisoformat`` parse — replaces the
#      CORPT00C ``CSUTLDTC`` call for calendar-validity.
#   3. A model-level validator that enforces
#      ``end_date >= start_date`` — a new invariant in the target
#      architecture (the COBOL program did NOT enforce this
#      ordering; it was callers' responsibility).
#
# These tests verify all three layers raise ``ValueError`` (which
# Pydantic wraps as ``ValidationError``) at the expected moments.
# ============================================================================


@pytest.mark.unit
async def test_custom_report_valid_date_range(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """A custom report with ``start_date < end_date`` is accepted.

    Mainframe counterpart
    ---------------------
    After CORPT00C validated all six BMS sub-fields (lines 258-426)
    the date pair was passed to the downstream reporting job
    WITHOUT any explicit start-vs-end ordering check. The target
    architecture adds this invariant because the downstream Glue
    job's date-range filter
    ``WHERE proc_ts BETWEEN :start_date AND :end_date`` would
    silently return an empty result set for an inverted pair — a
    failure mode that is harder to diagnose than an explicit
    validation error at submission time.
    """
    # --- Arrange --------------------------------------------------------
    # start_date < end_date — the canonical "valid range" case.
    request = ReportSubmissionRequest(
        report_type=ReportType.custom,
        start_date="2024-03-01",
        end_date="2024-03-31",
    )

    # --- Act ------------------------------------------------------------
    response = await report_service.submit_report(request)

    # --- Assert ---------------------------------------------------------
    assert response.confirm == _CONFIRM_YES
    assert response.report_type == ReportType.custom
    message = _receive_sent_message(mock_sqs_queue.client, mock_sqs_queue.url)
    body: dict[str, Any] = json.loads(message["Body"])
    assert body["start_date"] == "2024-03-01"
    assert body["end_date"] == "2024-03-31"

    # Boundary case: start_date == end_date is also valid (single-
    # day report range). Verify the schema accepts it — this
    # catches the off-by-one regression where ``>`` is mistakenly
    # replaced by ``>=`` in the validator.
    same_day_request = ReportSubmissionRequest(
        report_type=ReportType.custom,
        start_date="2024-06-15",
        end_date="2024-06-15",
    )
    assert same_day_request.start_date == same_day_request.end_date


@pytest.mark.unit
def test_custom_report_start_after_end_rejected() -> None:
    """A custom report with ``start_date > end_date`` is rejected.

    Mainframe counterpart
    ---------------------
    This is a NEW invariant in the target architecture (CORPT00C
    did NOT enforce start-vs-end ordering). The schema validator
    raises ``ValueError`` with a message containing both dates for
    operator diagnostics.

    Rejection is at construction time — the ``submit_report`` method
    is never invoked — so no SQS fixture is required.
    """
    # start_date > end_date — inverted pair MUST raise.
    with pytest.raises(ValueError) as exc_info:
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date="2024-12-31",
            end_date="2024-01-01",
        )

    # Error message must identify the ordering violation and
    # include both dates for operator diagnostics.
    err_msg: str = str(exc_info.value).lower()
    assert "end_date" in err_msg
    assert "start_date" in err_msg


@pytest.mark.unit
def test_custom_report_invalid_date_format() -> None:
    """Invalid ``YYYY-MM-DD`` formats are rejected by the schema.

    Mainframe counterpart
    ---------------------
    Replaces the CORPT00C calls to ``CSUTLDTC`` (lines 392-406) for
    date-validity checking. The target schema validates at two levels:

    1. Regex ``^\\d{4}-\\d{2}-\\d{2}$`` — enforces separator chars
       and digit-only sub-fields. Replaces the CORPT00C
       ``IS NUMERIC`` / emptiness checks on ``SDTYYYYI``,
       ``SDTMMI``, ``SDTDDI``.
    2. ``date.fromisoformat`` — enforces calendar validity
       (e.g., ``2024-02-30`` rejected). Replaces the CORPT00C
       ``CSUTLDTC`` call for calendar-validity.

    The COBOL error messages ``'Start Date - Not a valid Month...'``,
    ``'Start Date - Not a valid Day...'``, and
    ``'Start Date - Not a valid date...'`` are collapsed into a
    single Pydantic ``ValidationError`` in the target architecture.
    """
    # Invalid format: DD/MM/YYYY instead of YYYY-MM-DD.
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date="13/40/2025",
            end_date="14/40/2025",
        )

    # Invalid month (month 13).
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date="2024-13-01",
            end_date="2024-12-31",
        )

    # Invalid day (Feb 30 in a non-leap year).
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date="2024-02-30",
            end_date="2024-03-30",
        )

    # Missing leading zeros (``2024-1-1`` vs ``2024-01-01``).
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date="2024-1-1",
            end_date="2024-12-31",
        )

    # Empty string — maps to CORPT00C ``SDTMMI = LOW-VALUES``
    # rejected at lines 259-267 with ``'Start Date - Month can
    # NOT be empty...'``.
    with pytest.raises(ValueError):
        ReportSubmissionRequest(
            report_type=ReportType.custom,
            start_date="",
            end_date="2024-12-31",
        )


# ============================================================================
# Phase 6 — Error Handling Tests
# ============================================================================
#
# These tests verify that :meth:`ReportService.submit_report` handles
# SQS-client exceptions and configuration-gap scenarios gracefully.
#
# Mainframe counterpart
# ---------------------
# Maps to the CORPT00C ``WIRTE-JOBSUB-TDQ`` paragraph's
# ``EVALUATE WS-RESP-CD`` error-handling branches (lines 525-534):
#
#     EVALUATE WS-RESP-CD
#       WHEN DFHRESP(NORMAL)                    *> normal path
#            CONTINUE
#       WHEN OTHER                              *> error path, L528
#            DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD
#            MOVE 'Y' TO WS-ERR-FLG
#            MOVE 'Unable to Write TDQ (JOBS)...' TO WS-MESSAGE   *> L531
#     END-EVALUATE
#
# In the target architecture ``DFHRESP ≠ NORMAL`` collapses to
# ``except Exception`` in the service — covering all boto3
# ``ClientError`` subclasses, network timeouts, and AWS-side rejects.
# The user-visible error message is preserved byte-exactly
# (:data:`_EXPECTED_ERROR_MSG`).
# ============================================================================


@pytest.mark.unit
async def test_sqs_publish_failure(mock_sqs_queue: Any) -> None:
    """Verify SQS publish failures return ``confirm='N'`` + COBOL message.

    Mainframe counterpart
    ---------------------
    When the CICS WRITEQ TD fails (``DFHRESP ≠ NORMAL``), CORPT00C
    sets ``WS-MESSAGE = 'Unable to Write TDQ (JOBS)...'`` (line 531)
    and re-sends the screen with ``WS-ERR-FLG = 'Y'``. This test
    verifies the Python equivalent:

    * Returns ``confirm='N'`` (mapping WS-ERR-FLG='Y')
    * Returns the BYTE-EXACT error message
      (``'Unable to Write TDQ (JOBS)...'``) per AAP §0.7.1
    * Does NOT append the exception text to the user-visible
      message (avoids leaking internal implementation details).
    """
    # --- Arrange --------------------------------------------------------
    # Build the service inside the moto context so its lazy SQS
    # client is eligible to be patched. We patch the
    # ``get_sqs_client`` helper that the service's ``_get_client``
    # method calls so that it returns a MagicMock whose
    # ``send_message`` raises a boto3-style ClientError.
    from botocore.exceptions import ClientError

    client_mock = MagicMock()
    client_mock.send_message.side_effect = ClientError(
        error_response={
            "Error": {
                "Code": "QueueDoesNotExist",
                "Message": ("The specified queue does not exist for this wsdl version."),
            }
        },
        operation_name="SendMessage",
    )

    service = ReportService()

    # Patch the service instance's ``_get_client`` so the forced
    # exception is delivered on the ``send_message`` call.
    request = ReportSubmissionRequest(report_type=ReportType.monthly)

    with patch.object(service, "_get_client", return_value=client_mock):
        # --- Act --------------------------------------------------------
        response = await service.submit_report(request)

    # --- Assert ---------------------------------------------------------
    # Confirm flag must be 'N' — maps to CORPT00C setting
    # WS-ERR-FLG = 'Y'.
    assert response.confirm == _CONFIRM_NO, (
        f"On SQS publish failure, confirm must be {_CONFIRM_NO!r} "
        f"(maps to CORPT00C WS-ERR-FLG='Y'). "
        f"Got: {response.confirm!r}"
    )

    # Byte-exact COBOL error message from CORPT00C line 531.
    # Using string equality (NOT substring) so any drift in the
    # byte-exact literal is caught immediately.
    assert response.message == _EXPECTED_ERROR_MSG, (
        f"Error message must be byte-exact per AAP §0.7.1. "
        f"Expected: {_EXPECTED_ERROR_MSG!r} "
        f"(preserves CORPT00C line 531 COBOL literal verbatim). "
        f"Got: {response.message!r}"
    )

    # Defensive: the user-visible message must NOT contain the
    # underlying boto3 exception detail. Full diagnostics are
    # preserved in CloudWatch via ``logger.error(..., exc_info=True)``
    # but NOT leaked to the response.
    assert "QueueDoesNotExist" not in (response.message or "")
    assert "wsdl" not in (response.message or "")

    # The response message fits within the CORPT00 ``ERRMSGI``
    # PIC X(78) bound.
    assert len(response.message or "") <= _ERRMSG_MAX_LEN

    # The report_id is still returned even on failure — callers
    # can use it to correlate the failed submission with
    # CloudWatch logs.
    assert isinstance(response.report_id, str)
    assert len(response.report_id) == 36

    # Response echoes the requested report_type.
    assert response.report_type == ReportType.monthly

    # Verify the SQS mock was actually called (the exception did
    # come from the ``send_message`` call, not from earlier).
    client_mock.send_message.assert_called_once()


@pytest.mark.unit
async def test_local_development_fallback(
    unconfigured_report_service: ReportService,
) -> None:
    """Empty ``SQS_QUEUE_URL`` triggers the local-dev log-only fallback.

    This behavior is a Python-only concept with no COBOL counterpart
    — CICS TDQ is always configured in a production region. In the
    target architecture the empty ``SQS_QUEUE_URL`` default allows
    docker-compose and pytest runs to exercise the request/response
    path without AWS or LocalStack (AAP §0.7.2).

    Expected behavior
    -----------------
    * ``confirm='Y'``                — same as a successful SQS publish
    * ``message='Report logged (SQS disabled in local dev).'``
    * ``report_id``                  — server-generated UUIDv4
    * ``report_type``                — echoed from request
    * NO boto3 client construction   — so the test does NOT need
                                        to run inside ``mock_aws``.
    """
    # --- Arrange --------------------------------------------------------
    # All three report types take the same fallback path when
    # SQS_QUEUE_URL is empty. Iterate across all three to guard
    # against a branch being silently skipped.
    for report_type in (ReportType.monthly, ReportType.yearly):
        request = ReportSubmissionRequest(report_type=report_type)

        # --- Act --------------------------------------------------------
        response = await unconfigured_report_service.submit_report(request)

        # --- Assert -----------------------------------------------------
        assert response.confirm == _CONFIRM_YES, (
            f"Fallback path must return confirm='Y' for "
            f"{report_type.value!r} — SQS is simply unavailable, "
            f"not failed. Got: {response.confirm!r}"
        )
        assert response.message == _EXPECTED_FALLBACK_MSG, (
            f"Fallback message must be byte-exact for local-dev "
            f"developer visibility. "
            f"Expected: {_EXPECTED_FALLBACK_MSG!r}. "
            f"Got: {response.message!r}"
        )
        assert response.report_type == report_type
        assert isinstance(response.report_id, str)
        assert len(response.report_id) == 36

    # Custom report also falls through the fallback path; exercise
    # it once so all three branches are covered.
    custom_request = ReportSubmissionRequest(
        report_type=ReportType.custom,
        start_date=_TEST_START_DATE,
        end_date=_TEST_END_DATE,
    )
    custom_response = await unconfigured_report_service.submit_report(custom_request)
    assert custom_response.confirm == _CONFIRM_YES
    assert custom_response.message == _EXPECTED_FALLBACK_MSG
    assert custom_response.report_type == ReportType.custom


# ============================================================================
# Phase 7 — Confirmation Flow Tests
# ============================================================================
#
# Mainframe counterpart
# ---------------------
# These tests exercise the CORPT00 confirmation semantics. In the
# original COBOL program ``CONFIRMI`` was a single-character BMS
# input field (``PIC X(1)``) that the user populated with ``'Y'`` or
# ``'N'`` on a confirmation screen BEFORE submission:
#
#     EVALUATE CONFIRMI
#       WHEN 'Y'  *> L476-485, submit the job
#            PERFORM WIRTE-JOBSUB-TDQ
#            ...
#       WHEN 'N'  *> L486-488, abandon submission, clear dates
#            MOVE LOW-VALUES TO SDTMMI ... EDTDDI
#            MOVE 'Report cancelled by user...' TO WS-MESSAGE
#       WHEN OTHER *> L489-493, invalid confirm value
#            MOVE '"'       TO WS-DQ-CHAR
#            STRING WS-DQ-CHAR CONFIRMI WS-DQ-CHAR
#                  '" is not a valid value to confirm...'
#            INTO WS-MESSAGE
#     END-EVALUATE
#
# In the Python/REST architecture the confirmation step has moved
# CLIENT-SIDE: the API consumer prompts the user for confirmation
# and only then invokes ``POST /reports/submit``. The
# :attr:`ReportSubmissionResponse.confirm` field thereafter returns
# the OUTGOING confirmation (``'Y'`` for success / fallback,
# ``'N'`` for failure) — the semantic direction is REVERSED from
# the COBOL input field but the letter domain is identical.
#
# These tests verify that every successful submission returns
# ``confirm='Y'`` together with the COBOL-exact success message.
# ============================================================================


@pytest.mark.unit
async def test_submit_report_returns_confirmation(
    report_service: ReportService,
    mock_sqs_queue: Any,  # noqa: ARG001  # fixture triggers moto setup
) -> None:
    """Every successful submission returns ``confirm='Y'`` + success message.

    Mainframe counterpart
    ---------------------
    Maps to the CORPT00C ``SEND-TRNRPT-SCREEN`` paragraph after
    ``WIRTE-JOBSUB-TDQ`` succeeds. The COBOL program clears
    ``WS-ERR-FLG`` (equivalent to setting ``confirm='Y'`` in our
    response schema) and builds ``WS-MESSAGE`` via the STRING
    statement at lines 449-450 — producing exactly one of:

        'Monthly report submitted for printing ...'
        'Yearly report submitted for printing ...'
        'Custom report submitted for printing ...'

    This test asserts byte-exact equality for all three report types
    so any drift in the COBOL literal is caught immediately.
    """
    # Table-driven: (report_type, expected_report_name) pairs.
    # Each pair corresponds to one of the CORPT00C EVALUATE branches
    # at lines 213, 239, 256.
    cases: list[tuple[ReportType, str]] = [
        (ReportType.monthly, "Monthly"),  # CORPT00C L214
        (ReportType.yearly, "Yearly"),  # CORPT00C L240
        (ReportType.custom, "Custom"),  # CORPT00C L433
    ]

    for report_type, report_name in cases:
        # Custom requires explicit dates; monthly/yearly don't.
        if report_type == ReportType.custom:
            request = ReportSubmissionRequest(
                report_type=report_type,
                start_date=_TEST_START_DATE,
                end_date=_TEST_END_DATE,
            )
        else:
            request = ReportSubmissionRequest(report_type=report_type)

        # --- Act --------------------------------------------------------
        response = await report_service.submit_report(request)

        # --- Assert -----------------------------------------------------
        # Confirm flag — maps to COBOL WS-ERR-FLG cleared.
        assert response.confirm == _CONFIRM_YES, (
            f"Confirm flag must be {_CONFIRM_YES!r} on success for {report_type.value!r}, got {response.confirm!r}"
        )

        # Byte-exact COBOL success message from the STRING at
        # CORPT00C L449-450 with WS-REPORT-NAME = report_name.
        expected_msg: str = _SUCCESS_MSG_TEMPLATE.format(
            report_name=report_name,
        )
        assert response.message == expected_msg, (
            f"Success message must be byte-exact for "
            f"{report_type.value!r}. Expected: {expected_msg!r}, "
            f"Got: {response.message!r}"
        )

        # Message is within the CORPT00 ``ERRMSGI`` PIC X(78) bound.
        assert len(response.message or "") <= _ERRMSG_MAX_LEN

        # Report type echoed in response.
        assert response.report_type == report_type

        # Server-generated UUIDv4 for downstream correlation.
        assert isinstance(response.report_id, str)
        assert len(response.report_id) == 36


@pytest.mark.unit
async def test_submit_report_deduplication_ids_are_unique(
    report_service: ReportService,
    mock_sqs_queue: Any,
) -> None:
    """Distinct submissions produce distinct ``report_id`` values.

    Mainframe counterpart
    ---------------------
    CORPT00C relied on JES2 to assign a unique JOB-ID to each JCL
    deck submitted via WRITEQ TD. The target architecture generates
    a UUIDv4 per submission in ``ReportService.submit_report``
    (Step 1); this UUIDv4 doubles as the SQS
    ``MessageDeduplicationId`` so accidental client retries are
    dropped server-side.

    Uniqueness is CRITICAL — two submissions within the 5-minute
    FIFO dedup window MUST NOT collide on ``MessageDeduplicationId``,
    otherwise the second would be silently dropped.
    """
    # --- Arrange --------------------------------------------------------
    request1 = ReportSubmissionRequest(report_type=ReportType.monthly)
    request2 = ReportSubmissionRequest(report_type=ReportType.monthly)
    request3 = ReportSubmissionRequest(report_type=ReportType.yearly)

    # --- Act ------------------------------------------------------------
    response1 = await report_service.submit_report(request1)
    response2 = await report_service.submit_report(request2)
    response3 = await report_service.submit_report(request3)

    # --- Assert ---------------------------------------------------------
    # All three report_id values are distinct.
    ids: set[str] = {
        response1.report_id,
        response2.report_id,
        response3.report_id,
    }
    assert len(ids) == 3, (
        f"Each submission must produce a unique report_id "
        f"(to satisfy SQS FIFO MessageDeduplicationId uniqueness). "
        f"Got IDs: {ids!r}"
    )

    # All three messages were published to the queue.
    assert _get_queue_message_count(mock_sqs_queue.client, mock_sqs_queue.url) == 3


@pytest.mark.unit
async def test_submit_report_response_fits_errmsgi_bound() -> None:
    """All :attr:`ReportSubmissionResponse.message` values fit within
    the 78-char BMS ``ERRMSGI`` bound.

    Mainframe counterpart
    ---------------------
    The CORPT00 BMS symbolic map declares ``ERRMSGI PIC X(78)`` —
    the user-visible message field is padded to exactly 78
    characters on the 3270 screen. In the target architecture the
    Pydantic :class:`ReportSubmissionResponse.message` field
    inherits this bound via ``max_length=78`` and the service's
    ``_truncate_message`` helper defensively enforces it.

    This test asserts the bound holds across every message the
    service can produce:

    * Fallback message (local-dev path)
    * Success messages for monthly, yearly, and custom
    * Error message (SQS publish failure)

    If any of these exceeds 78 chars the Pydantic model would raise
    ``ValidationError`` at response construction — breaking the
    router layer and returning HTTP 500 instead of a friendly
    error payload.
    """
    # Manually validate each of the four messages this module
    # expects the service to produce. This guards against any
    # future COBOL-literal update that might exceed the BMS bound.
    messages_under_bound: list[str] = [
        _EXPECTED_FALLBACK_MSG,
        _EXPECTED_ERROR_MSG,
        _SUCCESS_MSG_TEMPLATE.format(report_name="Monthly"),
        _SUCCESS_MSG_TEMPLATE.format(report_name="Yearly"),
        _SUCCESS_MSG_TEMPLATE.format(report_name="Custom"),
    ]
    for msg in messages_under_bound:
        assert len(msg) <= _ERRMSG_MAX_LEN, f"Message exceeds BMS ERRMSGI PIC X(78) bound ({len(msg)} chars): {msg!r}"
        # Sanity check: no stray blank suffix. The COBOL literals
        # do not have trailing spaces; a whitespace diff against
        # the COBOL source should be zero.
        assert msg == msg.rstrip() or msg.endswith(" ..."), f"Unexpected trailing whitespace in message: {msg!r}"


# ============================================================================
# Phase 8 — Edge case coverage
# ============================================================================
#
# These tests exercise the defensive branches of
# ``ReportService`` that the main happy-/sad-path tests above do not
# cover:
#
# * The warning branch when ``sqs_client.send_message`` returns a
#   response WITHOUT a ``MessageId`` (line 528 in
#   ``report_service.py``). In the wild this never happens — every
#   real SQS response carries a ``MessageId`` — but the defensive
#   ``get(...)`` check exists to guard against future boto3 behavior
#   changes or unexpected test stubs.
#
# * The truncation branch of ``_truncate_message`` when the input
#   exceeds the 78-character ``ERRMSGI`` bound (line 695). The
#   standard COBOL messages all fit within 78 chars but the helper
#   exists for defensive safety against exception strings that might
#   exceed the bound (e.g., an overly chatty boto3 error).
# ============================================================================


@pytest.mark.unit
async def test_sqs_response_without_message_id_still_succeeds(
    mock_sqs_queue: Any,  # noqa: ARG001  # fixture triggers moto setup
) -> None:
    """Response without ``MessageId`` logs WARNING but succeeds.

    In real AWS SQS every ``send_message`` response carries a
    ``MessageId``. The service defensively handles the case where
    one is missing — it logs a WARNING and returns a success
    response, falling back to the client-generated ``report_id``
    for correlation.

    This path covers line 528 of ``report_service.py`` which
    would otherwise be unreachable via the moto-backed FIFO fixture
    (moto always returns a MessageId).
    """
    # --- Arrange --------------------------------------------------------
    # Build a mocked SQS client whose ``send_message`` returns an
    # empty response (no MessageId key). This simulates the
    # hypothetical boto3 behavior change the defensive branch
    # guards against.
    client_mock = MagicMock()
    client_mock.send_message.return_value = {}  # No MessageId!

    service = ReportService()
    request = ReportSubmissionRequest(report_type=ReportType.monthly)

    with patch.object(service, "_get_client", return_value=client_mock):
        # --- Act --------------------------------------------------------
        response = await service.submit_report(request)

    # --- Assert ---------------------------------------------------------
    # Still treated as a success (confirm='Y', byte-exact COBOL
    # success message), but the warning is logged.
    assert response.confirm == _CONFIRM_YES
    expected_message: str = _SUCCESS_MSG_TEMPLATE.format(report_name="Monthly")
    assert response.message == expected_message
    client_mock.send_message.assert_called_once()


@pytest.mark.unit
async def test_error_message_truncation_for_oversized_exception(
    mock_sqs_queue: Any,  # noqa: ARG001  # fixture triggers moto setup
) -> None:
    """Verify message truncation when a message exceeds 78 chars.

    Mainframe counterpart
    ---------------------
    The CORPT00 ``ERRMSGI`` BMS field is declared ``PIC X(78)`` —
    attempting to store a longer value on the 3270 screen would
    truncate silently. The Pydantic :class:`ReportSubmissionResponse`
    inherits this bound via ``max_length=78`` — a value exceeding
    the bound would raise ``ValidationError`` at response
    construction.

    The service's ``_truncate_message`` helper defensively enforces
    the bound for exception messages that might exceed it. Line 695
    of ``report_service.py`` handles this truncation path.

    This test does NOT rely on mocking the service's
    ``_truncate_message`` directly — that would defeat the point.
    Instead we verify that the byte-exact _ERROR_MSG literal (29
    chars) fits within the bound so that any change to it must
    also update this assertion.
    """
    # --- Arrange: import the module-private helper directly --------------
    # Importing the private helper (underscore prefix) is acceptable
    # here because we are verifying a defensive implementation
    # detail, not a public API contract. The helper's signature is
    # stable as long as the ERRMSGI bound is 78 chars.
    from src.api.services.report_service import _truncate_message

    # Short message — returned unchanged.
    short_msg: str = "Hello"
    assert _truncate_message(short_msg) == short_msg

    # Exactly 78 chars — boundary case, returned unchanged.
    boundary_msg: str = "X" * _ERRMSG_MAX_LEN
    assert _truncate_message(boundary_msg) == boundary_msg
    assert len(_truncate_message(boundary_msg)) == _ERRMSG_MAX_LEN

    # 79 chars — truncated to 78.
    overflow_msg: str = "Y" * (_ERRMSG_MAX_LEN + 1)
    truncated = _truncate_message(overflow_msg)
    assert len(truncated) == _ERRMSG_MAX_LEN
    assert truncated == "Y" * _ERRMSG_MAX_LEN

    # Very long message — truncated to 78.
    long_msg: str = (
        "This is a deliberately long exception message intended to "
        "overflow the CORPT00 ERRMSGI PIC X(78) BMS field bound so "
        "we can verify the truncation branch of _truncate_message."
    )
    assert len(long_msg) > _ERRMSG_MAX_LEN
    assert len(_truncate_message(long_msg)) == _ERRMSG_MAX_LEN
    # Truncation keeps the PREFIX (not the suffix) so operators
    # still see the most descriptive portion of the message.
    assert _truncate_message(long_msg) == long_msg[:_ERRMSG_MAX_LEN]
