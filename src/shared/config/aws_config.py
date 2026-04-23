# ============================================================================
# Source: JCL DD statements (app/jcl/ACCTFILE.jcl, POSTTRAN.jcl, CREASTMT.JCL),
#         CICS TDQ (app/cbl/CORPT00C.cbl), GDG (DEFGDGB.jcl, REPTFILE.jcl,
#         DALYREJS.jcl), CICS sign-on creds (app/cbl/COSGN00C.cbl),
#         COMMAREA (app/cpy/COCOM01Y.cpy)
#         --> AWS boto3 service clients (S3, SQS, Secrets Manager, Glue)
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
"""AWS service client factories for CardDemo cloud-native application.

Provides centralized boto3 client creation using IAM roles (NOT access
keys per security requirements). Replaces mainframe infrastructure
connectivity: JCL DD statements, CICS TDQ, GDG definitions, and z/OS
RACF-protected credential storage.

Mainframe-to-Cloud Mapping
--------------------------
============================  ==============================================
AWS service / facility        Original mainframe construct replaced
============================  ==============================================
Amazon S3                      GDG generation data groups (DEFGDGB.jcl,
                               REPTFILE.jcl, DALYREJS.jcl, TRANBKP.jcl) for
                               statement output, report output, and reject
                               logs. S3 object versioning substitutes for
                               GDG (+1) semantics (see AAP 0.4.1 / 0.5.1).

Amazon SQS (FIFO)              CICS Transient Data Queue ``WRITEQ JOBS``
                               used by ``CORPT00C.cbl`` (F-022 Report
                               Submission) to hand batch job requests to
                               internal readers. Publishing to an SQS FIFO
                               queue preserves ordering semantics exactly
                               (see AAP 0.4.1).

AWS Secrets Manager            z/OS RACF-protected credential storage. The
                               CICS sign-on program ``COSGN00C.cbl`` reads
                               the USRSEC VSAM file via ``WS-USRSEC-FILE``
                               DD. In the target architecture Aurora
                               PostgreSQL credentials, JWT signing keys,
                               and other secrets live in AWS Secrets
                               Manager rather than SYS1.PARMLIB or JCL DD
                               statements (see AAP 0.7.2).

AWS Glue                       JES2/JCL batch execution (POSTTRAN.jcl,
                               INTCALC.jcl, COMBTRAN.jcl, CREASTMT.JCL,
                               TRANREPT.jcl). Programmatic Glue job start,
                               monitoring, and cancellation replaces
                               z/OS internal-reader submission (see AAP
                               0.4.1, 0.6.2).
============================  ==============================================

Credentials (IAM Roles Only)
----------------------------
The factories do NOT pass explicit ``aws_access_key_id`` /
``aws_secret_access_key`` arguments. Credentials are resolved by the
standard boto3 credential chain:

1. Environment variables (``AWS_ACCESS_KEY_ID`` / ``AWS_SECRET_ACCESS_KEY``)
   -- used for local development and LocalStack (``test`` / ``test``).
2. Shared credentials file (``~/.aws/credentials``) -- developer laptops.
3. ECS container credentials endpoint -- production ECS Fargate tasks
   assume the ECS task role.
4. EC2 Instance Metadata Service -- used by AWS Glue worker nodes to
   assume the Glue job execution role.

This design satisfies the AAP 0.7.2 security requirement "AWS services
must have required access via IAM roles and policies (not access keys)"
for the production deployment while still allowing local developers to
set fake credentials for LocalStack testing.

Design Principles
-----------------
* **Stateless factories** -- Each call creates a fresh boto3 client.
  boto3 itself caches connection pools internally, so the overhead is
  negligible. No module-level client instances avoids test pollution.
* **Optional region parameter** -- Callers can override the default
  region (e.g., to target a specific cross-region S3 bucket). When
  omitted, the region is lazily read from ``Settings.AWS_REGION``.
* **Lazy Settings import** -- ``Settings`` is imported inside function
  bodies (not at module top-level) to avoid circular-import pitfalls
  between ``src.shared.config.settings`` and the many modules that
  transitively import this file.
* **Consistent retry policy** -- All clients share the same botocore
  Config (``max_attempts=3, mode=standard``) so transient AWS errors
  behave identically across S3, SQS, Secrets Manager, and Glue.
* **Structured logging** -- All Secrets Manager errors are logged via
  ``logging.getLogger(__name__)`` for CloudWatch aggregation.

AAP Cross-References
--------------------
0.4.1 -- Refactored Structure Planning (``src/shared/config/aws_config.py``)
0.5.1 -- File-by-File Transformation Plan
0.6.1 -- Core / Shared Dependencies (boto3 1.35.x, botocore 1.35.x)
0.6.2 -- AWS Service Dependencies (S3, SQS FIFO, Secrets Manager, Glue)
0.7.2 -- Security Requirements (IAM roles, Secrets Manager, no hardcoded
         credentials)

Usage
-----
.. code-block:: python

    from src.shared.config.aws_config import (
        get_s3_client,
        get_sqs_client,
        get_secrets_manager_client,
        get_glue_client,
        get_database_credentials,
    )

    # Upload a generated statement to S3 (replaces GDG output)
    s3 = get_s3_client()
    s3.put_object(
        Bucket="carddemo-statements",
        Key="2025/01/STMT-000000001.pdf",
        Body=pdf_bytes,
    )

    # Publish a report request to SQS (replaces CICS TDQ WRITEQ JOBS)
    sqs = get_sqs_client()
    sqs.send_message(
        QueueUrl=queue_url,
        MessageBody=payload_json,
        MessageGroupId="reports",
    )

    # Retrieve DB credentials from Secrets Manager (replaces RACF)
    creds = get_database_credentials()
    conn_str = (
        f"postgresql://{creds['username']}:{creds['password']}"
        f"@{creds['host']}:{creds['port']}/{creds['dbname']}"
    )

    # Start a Glue job (replaces JCL / internal-reader submission)
    glue = get_glue_client()
    run = glue.start_job_run(JobName="posttran-job")
"""

from __future__ import annotations

import json
import logging
from functools import lru_cache
from typing import Any

import boto3
from botocore.config import Config as BotoConfig

# ----------------------------------------------------------------------------
# Module logger. Uses the module's __name__ so CloudWatch log streams
# clearly identify "src.shared.config.aws_config" as the source of
# Secrets Manager failures and other AWS client-side diagnostics.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# Public re-export list.
#
# Only the five factory / retrieval functions below are part of the
# public API. The two leading-underscore helpers
# (`_get_boto_config`, `_get_default_region`) are implementation details.
# ----------------------------------------------------------------------------
__all__ = [
    "get_s3_client",
    "get_sqs_client",
    "get_secrets_manager_client",
    "get_glue_client",
    "get_database_credentials",
]


# ============================================================================
# Private helpers
# ============================================================================


@lru_cache(maxsize=1)
def _get_default_region() -> str:
    """Resolve the default AWS region from ``Settings``.

    This helper is cached via :func:`functools.lru_cache` so the
    ``Settings`` instance (and its environment-variable parsing) is
    only constructed once per process. Subsequent calls across all
    four client factories return the cached region string without
    re-reading environment variables.

    The import of :class:`~src.shared.config.settings.Settings` is
    deliberately performed inside the function body (lazy import) to
    avoid circular-import pitfalls. The ``settings`` module and this
    ``aws_config`` module can each be safely imported first; neither
    triggers the other at import time.

    Returns
    -------
    str
        The AWS region string from :attr:`Settings.AWS_REGION`
        (default: ``"us-east-1"`` per AAP 0.6.2).
    """
    # Lazy import prevents ``src.shared.config.settings`` from being
    # executed during module import of ``aws_config``. This matters
    # because ``Settings`` requires runtime environment variables
    # (``DATABASE_URL`` etc.) to instantiate successfully -- we want
    # ``import src.shared.config.aws_config`` to succeed even in
    # minimal test environments that have not yet configured those
    # variables.
    from src.shared.config.settings import Settings

    return Settings().AWS_REGION


def _get_endpoint_url() -> str | None:
    """Resolve the optional AWS endpoint URL override for LocalStack.

    Returns the value of :attr:`Settings.AWS_ENDPOINT_URL` when it has
    been configured to a non-empty string; otherwise returns ``None``
    so that boto3 falls back to the standard AWS-managed regional
    endpoint discovery. This enables the ``docker-compose.yml`` local
    development stack (which sets
    ``AWS_ENDPOINT_URL=http://localstack:4566``) to fully exercise
    every AWS integration code path against the
    ``localstack/localstack:3`` container while keeping production
    deployments untouched (AAP 0.4.2 LocalStack local-dev
    requirement).

    The value is re-read on every call -- we intentionally do NOT
    cache the endpoint URL the way :func:`_get_default_region` caches
    the region. ``AWS_ENDPOINT_URL`` is an *override* that developers
    may toggle at runtime (e.g., by editing ``.env`` and restarting a
    single process) whereas ``AWS_REGION`` is a near-immutable
    process-scoped attribute. Re-reading per call costs a single
    ``Settings()`` construction (Pydantic's ``BaseSettings`` caches
    its environment parsing internally) and correctly reflects any
    runtime overrides for test harnesses.

    Returns
    -------
    str | None
        The override URL (e.g., ``"http://localstack:4566"``) when
        :attr:`Settings.AWS_ENDPOINT_URL` is non-empty, otherwise
        ``None`` (signals boto3 to use the default regional
        endpoint).
    """
    # Lazy import for the same circular-import-avoidance rationale as
    # ``_get_default_region``: importing ``src.shared.config.settings``
    # at module load time can trigger environment-variable parsing in
    # contexts where the environment has not been configured yet
    # (for example, during ``mypy`` analysis of ``aws_config.py`` in
    # isolation).
    from src.shared.config.settings import Settings

    endpoint_url = Settings().AWS_ENDPOINT_URL
    # An empty-string sentinel is treated identically to "unset" --
    # matches the default defined on the Settings model and the
    # production deployment expectation that this env var is absent.
    return endpoint_url or None


def _get_boto_config() -> BotoConfig:
    """Build the shared botocore ``Config`` object used by every client.

    A single :class:`botocore.config.Config` instance encodes the retry
    policy and region resolution so that every AWS client -- S3, SQS,
    Secrets Manager, Glue -- applies identical networking behavior.

    The retry configuration (``max_attempts=3, mode="standard"``) is
    the recommended balance for production-grade resilience per AAP
    0.7.2 monitoring requirements:

    * ``max_attempts=3`` -- Retry up to 3 times on transient errors.
      Matches the default boto3 behavior and mirrors the z/OS
      automatic-retry semantics provided by JES2 for jobs with
      ``RETRY=(,R)`` on the JOB card.
    * ``mode="standard"`` -- Uses the "standard" retry mode which
      retries on throttling, 5xx server errors, and retriable client
      errors. The alternative "adaptive" mode adds client-side rate
      limiting which can mask legitimate throughput issues in
      monitoring dashboards.

    Returns
    -------
    botocore.config.Config
        A pre-configured Config object ready to be passed as
        ``config=`` to ``boto3.client(...)``. The ``region_name`` is
        read from :attr:`Settings.AWS_REGION` via
        :func:`_get_default_region`.
    """
    return BotoConfig(
        region_name=_get_default_region(),
        retries={"max_attempts": 3, "mode": "standard"},
    )


# ============================================================================
# Public AWS client factories
# ============================================================================


def get_s3_client(region: str | None = None) -> Any:
    """Create S3 client.

    Replaces GDG generation output (DEFGDGB.jcl, REPTFILE.jcl,
    DALYREJS.jcl).

    S3 is used by the target architecture for three workloads:

    1. **Statement output storage** -- The ``CREASTMT`` batch stage
       (``src/batch/jobs/creastmt_job.py``, derived from
       ``CBSTM03A.CBL`` + ``CREASTMT.JCL``) writes generated text and
       HTML statements as versioned S3 objects. S3 versioning replaces
       GDG generations G0001V00, G0002V00, etc.
    2. **Transaction report output** -- The ``TRANREPT`` batch stage
       (``src/batch/jobs/tranrept_job.py``, derived from
       ``CBTRN03C.cbl``) writes date-filtered reports with 3-level
       totals.
    3. **Reject log output** -- The ``POSTTRAN`` batch stage
       (``src/batch/jobs/posttran_job.py``, derived from
       ``CBTRN02C.cbl`` + ``POSTTRAN.jcl``) writes rejected daily
       transactions (reject codes 100-109) to the ``DALYREJS`` bucket
       path, replacing the GDG in the original JCL.

    The client uses IAM role-based authentication (no access key
    parameters) per AAP 0.7.2 security requirements.

    Parameters
    ----------
    region : str | None, optional
        AWS region override (e.g., ``"us-west-2"``). When ``None``
        (the default), the region is lazily resolved from
        :attr:`Settings.AWS_REGION`. Provided explicit regions
        override the default, enabling cross-region bucket access
        from a single process.

    Returns
    -------
    Any
        A boto3 ``s3`` low-level client bound to the resolved region
        and the shared retry policy. Typed as ``Any`` because boto3
        client objects are dynamically generated and lack static
        type stubs.
    """
    # ``endpoint_url`` is threaded through from
    # :attr:`Settings.AWS_ENDPOINT_URL` so local-development
    # invocations honor the ``AWS_ENDPOINT_URL=http://localstack:4566``
    # override defined in ``docker-compose.yml``. Production ECS
    # Fargate tasks leave the env var unset, so ``_get_endpoint_url``
    # returns ``None`` and boto3 uses its default regional endpoint
    # (AAP 0.4.2 LocalStack local-dev requirement).
    return boto3.client(
        "s3",
        region_name=region or _get_default_region(),
        endpoint_url=_get_endpoint_url(),
        config=_get_boto_config(),
    )


def get_sqs_client(region: str | None = None) -> Any:
    """Create SQS FIFO client.

    Replaces CICS TDQ WRITEQ JOBS (CORPT00C.cbl report submission).

    The target architecture uses an SQS FIFO queue to deliver
    report-generation requests from the FastAPI API layer to a
    downstream worker that launches AWS Glue jobs:

    * The FastAPI endpoint ``POST /reports/submit`` (see
      ``src/api/routers/report_router.py`` and
      ``src/api/services/report_service.py``, derived from
      ``CORPT00C.cbl``) publishes a message to the queue via
      ``send_message`` with ``MessageGroupId`` set for FIFO ordering.
    * A downstream consumer (out of scope of this repository) polls
      the queue, launches the appropriate AWS Glue job, and writes
      the report artifact to S3.

    FIFO semantics -- guaranteed ordering and exactly-once processing
    within a message group -- are required to faithfully preserve the
    behavior of the original CICS TDQ, which processed requests
    sequentially on an internal reader.

    The target queue URL is provided separately via
    :attr:`Settings.SQS_QUEUE_URL`. This factory only creates the
    client.

    Parameters
    ----------
    region : str | None, optional
        AWS region override. When ``None`` (the default), the region
        is lazily resolved from :attr:`Settings.AWS_REGION`.

    Returns
    -------
    Any
        A boto3 ``sqs`` low-level client bound to the resolved region
        and the shared retry policy.
    """
    # See ``get_s3_client`` for the rationale behind threading
    # ``endpoint_url`` through to boto3 (LocalStack local-dev
    # override, no-op in production when Settings.AWS_ENDPOINT_URL
    # is empty).
    return boto3.client(
        "sqs",
        region_name=region or _get_default_region(),
        endpoint_url=_get_endpoint_url(),
        config=_get_boto_config(),
    )


def get_secrets_manager_client(region: str | None = None) -> Any:
    """Create Secrets Manager client for database credential retrieval.

    Replaces z/OS RACF credential management.

    The original sign-on program ``COSGN00C.cbl`` authenticated users
    against the USRSEC VSAM file (DD ``USRSEC``) under RACF-protected
    access control. Aurora PostgreSQL credentials were bundled in JCL
    DD statements such as ``//ACCTFILE DD
    DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`` (see
    ``app/jcl/ACCTFILE.jcl``) and protected by dataset-level RACF
    profiles.

    In the target architecture, every secret -- Aurora PostgreSQL
    credentials (username, password, host, port, dbname), JWT signing
    keys, third-party API keys -- is stored in AWS Secrets Manager
    and retrieved at runtime. The Secrets Manager client returned by
    this factory is the authorized entry point; access is granted via
    IAM policies attached to the ECS task role (for the API) and the
    Glue job execution role (for batch jobs).

    Parameters
    ----------
    region : str | None, optional
        AWS region override. When ``None`` (the default), the region
        is lazily resolved from :attr:`Settings.AWS_REGION`.

    Returns
    -------
    Any
        A boto3 ``secretsmanager`` low-level client bound to the
        resolved region and the shared retry policy.
    """
    # See ``get_s3_client`` for the rationale behind threading
    # ``endpoint_url`` through to boto3 (LocalStack local-dev
    # override, no-op in production when Settings.AWS_ENDPOINT_URL
    # is empty).
    return boto3.client(
        "secretsmanager",
        region_name=region or _get_default_region(),
        endpoint_url=_get_endpoint_url(),
        config=_get_boto_config(),
    )


def get_glue_client(region: str | None = None) -> Any:
    """Create Glue client for batch job management.

    Replaces JES2/JCL batch job submission.

    The original application submitted batch jobs (POSTTRAN,
    INTCALC, COMBTRAN, CREASTMT, TRANREPT) to the JES2 internal
    reader via JCL JOB cards. AWS Glue replaces this entirely: each
    batch COBOL program is a PySpark script registered as an AWS
    Glue Job, orchestrated by AWS Step Functions (see
    ``src/batch/pipeline/step_functions_definition.json``).

    This client is used by:

    * **Step Functions state transitions** -- ``StartJobRun`` /
      ``GetJobRun`` API calls drive the S1 -> S2 -> S3 -> parallel
      (S4a, S4b) pipeline flow (see AAP 0.7.2 batch pipeline
      sequencing requirement).
    * **Deployment automation** -- GitHub Actions workflows
      (``.github/workflows/deploy-glue.yml``) use
      ``UpdateJob`` / ``GetJob`` API calls to register new PySpark
      script versions in the Glue catalog after CI builds.
    * **Operational tooling** -- Administrative scripts may call
      ``ListJobRuns`` / ``StopJobRun`` for manual intervention
      equivalent to z/OS ``$P`` / ``$C`` operator commands.

    Parameters
    ----------
    region : str | None, optional
        AWS region override. When ``None`` (the default), the region
        is lazily resolved from :attr:`Settings.AWS_REGION`.

    Returns
    -------
    Any
        A boto3 ``glue`` low-level client bound to the resolved
        region and the shared retry policy.
    """
    # See ``get_s3_client`` for the rationale behind threading
    # ``endpoint_url`` through to boto3 (LocalStack local-dev
    # override, no-op in production when Settings.AWS_ENDPOINT_URL
    # is empty).
    return boto3.client(
        "glue",
        region_name=region or _get_default_region(),
        endpoint_url=_get_endpoint_url(),
        config=_get_boto_config(),
    )


# ============================================================================
# Secrets Manager credential retrieval
# ============================================================================


def get_database_credentials(secret_name: str | None = None) -> dict[str, str]:
    """Retrieve database credentials from AWS Secrets Manager.

    Replaces z/OS RACF/JCL credential management for VSAM dataset
    access.

    The AWS Secrets Manager secret is expected to contain a JSON
    document of the form::

        {
            "username": "carddemo_user",
            "password": "...",
            "host": "carddemo-aurora.cluster-xyz.us-east-1.rds.amazonaws.com",
            "port": 5432,
            "dbname": "carddemo"
        }

    This matches the canonical "credentials-for-rds-database" secret
    template produced by AWS RDS when Secrets Manager is selected as
    the credential store at database creation time.

    Error Handling
    --------------
    The function distinguishes three specific Secrets Manager error
    conditions for structured logging:

    * ``ResourceNotFoundException`` -- secret does not exist (wrong
      name or wrong region). Indicates a configuration error.
    * ``InvalidRequestException`` -- request was malformed, typically
      because the secret is scheduled for deletion or is in a state
      where it cannot be read.
    * ``InvalidParameterException`` -- one or more parameters are
      invalid, typically a malformed ARN.

    All three are logged at ``ERROR`` level with the secret name (for
    operations support) and re-raised so the caller can react (e.g.,
    by aborting startup rather than continuing with placeholder
    credentials). Any unexpected exception is also logged and
    re-raised unchanged.

    Parameters
    ----------
    secret_name : str | None, optional
        The AWS Secrets Manager secret name or ARN. When ``None``
        (the default), the name is lazily read from
        :attr:`Settings.DB_SECRET_NAME` (default:
        ``"carddemo/aurora-credentials"``).

    Returns
    -------
    dict[str, str]
        Dictionary with the following keys:

        * ``"username"`` -- database username (str)
        * ``"password"`` -- database password (str)
        * ``"host"`` -- database host (str)
        * ``"port"`` -- database port (str, formatted from the
          numeric field; defaults to ``"5432"`` if omitted from the
          secret)
        * ``"dbname"`` -- database name (str)

    Raises
    ------
    botocore.exceptions.ClientError
        Re-raised after logging if Secrets Manager returns any of
        the three documented error conditions above.
    Exception
        Any other unexpected exception is logged at ERROR and
        re-raised unchanged (preserves the original traceback for
        diagnostics).
    """
    # ------------------------------------------------------------------
    # Resolve the secret name. If not provided, read from Settings
    # (lazy import to avoid circular-import with settings.py).
    # ------------------------------------------------------------------
    if secret_name is None:
        from src.shared.config.settings import Settings

        secret_name = Settings().DB_SECRET_NAME

    client = get_secrets_manager_client()

    # ------------------------------------------------------------------
    # Call Secrets Manager and parse the SecretString as JSON.
    #
    # NOTE: Secrets Manager supports binary secrets via SecretBinary,
    # but RDS-managed credentials always use SecretString. This
    # implementation therefore requires SecretString; calling sites
    # needing binary secrets must build a different helper.
    # ------------------------------------------------------------------
    try:
        response = client.get_secret_value(SecretId=secret_name)
        secret = json.loads(response["SecretString"])
        return {
            "username": secret["username"],
            "password": secret["password"],
            "host": secret["host"],
            # ``port`` is sometimes stored as an integer in the secret
            # (RDS-managed default) and sometimes as a string. We
            # coerce to str() so downstream consumers can unconditionally
            # format the connection string. Default to "5432" for
            # standard PostgreSQL if the key is missing -- mirrors the
            # Aurora PostgreSQL default port.
            "port": str(secret.get("port", "5432")),
            "dbname": secret["dbname"],
        }
    except client.exceptions.ResourceNotFoundException:
        # Operator error: secret does not exist in the current region.
        # Log at ERROR so CloudWatch alarms fire, then re-raise so the
        # caller can abort startup.
        logger.error("Secret '%s' not found in Secrets Manager", secret_name)
        raise
    except client.exceptions.InvalidRequestException:
        # Typically occurs when a secret is pending deletion or has
        # been deleted. Operator needs to restore or rotate.
        logger.error("Invalid request for secret '%s'", secret_name)
        raise
    except client.exceptions.InvalidParameterException:
        # Malformed SecretId (e.g., invalid ARN syntax). Indicates a
        # configuration mistake in Settings.DB_SECRET_NAME.
        logger.error("Invalid parameter for secret '%s'", secret_name)
        raise
    except Exception:
        # Catch-all for transport errors, JSON decode failures,
        # malformed secret payloads (missing keys), etc. Log the
        # secret name for diagnostics and re-raise unchanged so the
        # original exception type and traceback reach the caller.
        logger.error("Failed to retrieve secret '%s'", secret_name)
        raise
