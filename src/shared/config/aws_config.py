"""
AWS service client factories for the CardDemo modernization project.

This module provides thin, dependency-injection-friendly factory functions
that construct :mod:`boto3` low-level clients for the three AWS services
that replace mainframe constructs in the target architecture:

============================  ==============================================
AWS service                    Original mainframe construct replaced
============================  ==============================================
Amazon S3                      GDG generation data groups (REPTFILE, DALYREJS,
                               TRANBKP) — statement and report output storage,
                               PySpark Glue script artifacts, versioned
                               transaction backups (see AAP §0.4.1, §0.5.1).
Amazon SQS (FIFO)              CICS Transient Data Queue ``WRITEQ JOBS``
                               bridge used by ``CORPT00C.cbl`` (F-022 Report
                               Submission) to hand batch job requests to
                               internal readers.  In the cloud architecture
                               report submissions are published to an SQS
                               FIFO queue consumed by a downstream worker
                               that launches AWS Glue jobs (AAP §0.4.1).
AWS Secrets Manager            z/OS RACF-protected credential storage.  In
                               the cloud architecture Aurora PostgreSQL
                               credentials, JWT signing keys, and other
                               secrets are retrieved from AWS Secrets
                               Manager rather than from SYS1.PARMLIB or
                               JCL DD statements (AAP §0.7.2).
============================  ==============================================

Why factory functions rather than module-level client instances?
    Each factory accepts an optional :class:`Settings` instance so that unit
    tests can inject stub configuration without patching module globals.  When
    no settings instance is supplied the factory instantiates one via
    :class:`~src.shared.config.settings.Settings` — for production use this
    reads credentials and the region from the environment variables defined
    in the AWS ECS task definition (AAP §0.6.2) or the operator's local
    ``.env`` file.

LocalStack local-development support
    When :attr:`Settings.AWS_ENDPOINT_URL` is non-empty the factories pass
    ``endpoint_url=<that URL>`` into ``boto3.client(...)``, which causes all
    requests to be directed at the configured LocalStack container running
    in Docker Compose (``docker-compose.yml`` exposes LocalStack at
    ``http://localhost:4566`` on the host and ``http://localstack:4566``
    inside the compose network).  This mirrors the production call paths
    exactly — the same factory code is exercised in both environments, only
    the endpoint changes — so integration tests that run against LocalStack
    provide high-confidence regression coverage for production AWS calls.

Credentials
    The factories do NOT pass explicit ``aws_access_key_id`` /
    ``aws_secret_access_key`` arguments.  Credentials are resolved by the
    standard boto3 credential chain:

    1. Environment variables (``AWS_ACCESS_KEY_ID`` / ``AWS_SECRET_ACCESS_KEY``)
       — used for local development and LocalStack (``test`` / ``test``).
    2. Shared credentials file (``~/.aws/credentials``) — developer laptops.
    3. ECS container credentials endpoint — production ECS Fargate tasks
       assume the ``AWS_DEPLOY_ROLE_ARN`` task role.
    4. EC2 Instance Metadata Service — unused in this project but kept in
       the chain by default.

    This design satisfies the AAP §0.7.2 rule "AWS services must have
    required access via IAM roles and policies (not access keys)" for the
    production deployment while still allowing local developers to set
    fake credentials for LocalStack.

Region resolution
    :attr:`Settings.AWS_REGION` (default ``us-east-1``) is always passed as
    ``region_name`` so boto3 has a deterministic region even when no region
    is configured at the account level.

AAP Cross-References
    §0.5.1 — File Transformation Plan declares this module as ``CREATE``
             with the description "AWS service client factories, Secrets
             Manager".
    §0.6.1 — Core / Shared Dependencies lists ``boto3 1.35.x`` and
             ``botocore 1.35.x`` as the required packages.  The actual
             installed versions are 1.42.90 per the environment setup log;
             the pinning in :file:`requirements.txt` governs the lower bound.
    §0.6.2 — AWS Service Dependencies enumerates S3, SQS FIFO, and Secrets
             Manager as mandatory AWS services for the target system.
    §0.7.2 — Security Requirements mandates Secrets Manager for database
             credentials, IAM roles (not access keys) for service-to-service
             authentication, and BCrypt password hashing.

Usage example
---------------

.. code-block:: python

    from src.shared.config.aws_config import (
        get_s3_client,
        get_sqs_client,
        get_secrets_manager_client,
    )

    # Production — uses real AWS endpoints, credentials from ECS task role
    s3 = get_s3_client()
    s3.put_object(
        Bucket="carddemo-statements",
        Key="2025/01/STMT-000000001.pdf",
        Body=pdf_bytes,
    )

    # Testing — inject a stub Settings so tests never touch real AWS
    from src.shared.config.settings import Settings
    stub = Settings(AWS_ENDPOINT_URL="http://localhost:4566", ...)
    sqs = get_sqs_client(stub)
    sqs.send_message(QueueUrl=..., MessageBody=..., MessageGroupId=...)
"""

from __future__ import annotations

from typing import Any

import boto3
from botocore.client import BaseClient

from src.shared.config.settings import Settings

__all__ = [
    "get_s3_client",
    "get_sqs_client",
    "get_secrets_manager_client",
]


def _build_client_kwargs(settings: Settings) -> dict[str, Any]:
    """
    Construct the ``**kwargs`` dictionary passed into ``boto3.client(...)``.

    The keyword arguments are assembled from the :class:`Settings` instance
    so every factory uses identical region and endpoint resolution logic:

    * ``region_name`` — always present, sourced from
      :attr:`Settings.AWS_REGION`.  Defaults to ``us-east-1`` when not
      overridden by the environment.
    * ``endpoint_url`` — included only when
      :attr:`Settings.AWS_ENDPOINT_URL` is a non-empty string.  This is
      the LocalStack escape hatch for local development; in production it
      must be left unset so boto3 resolves the real AWS regional endpoint.

    The function is private to the module (leading underscore) because
    callers should never need to bypass the factories — they exist to
    centralise this logic in one place.

    Parameters
    ----------
    settings :
        A fully-constructed :class:`Settings` instance.  Required.  The
        caller is responsible for loading environment variables and
        validating them; this helper does not re-read configuration.

    Returns
    -------
    dict
        A dictionary suitable for ``**``-unpacking into ``boto3.client``.
        Always contains ``region_name``; conditionally contains
        ``endpoint_url``.
    """
    kwargs: dict[str, Any] = {"region_name": settings.AWS_REGION}

    # AWS_ENDPOINT_URL is an optional str (default "").  Only include it
    # when the operator has explicitly configured a non-empty override —
    # passing endpoint_url="" to boto3.client is an error, and passing
    # None would silently mask misconfiguration in production.
    if settings.AWS_ENDPOINT_URL:
        kwargs["endpoint_url"] = settings.AWS_ENDPOINT_URL

    return kwargs


def get_s3_client(settings: Settings | None = None) -> BaseClient:
    """
    Construct an Amazon S3 ``boto3`` client.

    S3 replaces the mainframe's Generation Data Group (GDG) mechanism
    (originally declared in ``DEFGDGB.jcl``, ``REPTFILE.jcl``,
    ``DALYREJS.jcl``, ``TRANBKP.jcl``).  In the target architecture S3 is
    used for three purposes:

    1. **Statement output storage** — ``CREASTMT`` batch stage (Glue job
       :mod:`src.batch.jobs.creastmt_job`) writes generated statements
       (text + HTML) as versioned S3 objects.  S3 versioning replaces
       GDG generations G0001V00, G0002V00, … (see AAP §0.4.1).
    2. **Transaction-report output** — ``TRANREPT`` batch stage
       (:mod:`src.batch.jobs.tranrept_job`) writes date-filtered reports
       with three-level totals.
    3. **Glue script artifact storage** — CI/CD uploads PySpark job scripts
       to the S3 bucket declared by the ``GLUE_SCRIPT_BUCKET`` GitHub
       Actions variable so AWS Glue can reference them in job definitions.

    Parameters
    ----------
    settings :
        Optional explicit :class:`Settings` instance.  When ``None`` a new
        :class:`Settings` is constructed on the caller's behalf, reading
        environment variables according to the standard Pydantic
        BaseSettings resolution order.  Supplying an explicit instance is
        preferred in unit tests to avoid ambient environment coupling.

    Returns
    -------
    botocore.client.BaseClient
        A ``boto3`` client of service type ``s3``, bound to the configured
        region and (optionally) endpoint.
    """
    effective = settings or Settings()
    return boto3.client("s3", **_build_client_kwargs(effective))


def get_sqs_client(settings: Settings | None = None) -> BaseClient:
    """
    Construct an Amazon SQS ``boto3`` client.

    SQS replaces the CICS Transient Data Queue ``WRITEQ JOBS`` bridge
    originally implemented by ``CORPT00C.cbl`` (F-022 Report Submission).
    In the target architecture:

    * The FastAPI endpoint ``POST /reports/submit`` (see
      :mod:`src.api.routers.report_router` and
      :mod:`src.api.services.report_service`) publishes a message to an
      SQS FIFO queue via ``send_message``.
    * A downstream consumer (outside the scope of this repository) polls
      the queue, launches the appropriate AWS Glue job, and writes the
      resulting report artefact back to S3.

    FIFO semantics — guaranteed ordering and exactly-once processing —
    are required to faithfully preserve the behaviour of the original
    CICS TDQ, which processed requests sequentially on an internal
    reader.

    The ``QueueUrl`` of the target queue is configured separately via
    :attr:`Settings.SQS_QUEUE_URL`; this factory only creates the client.

    Parameters
    ----------
    settings :
        Optional explicit :class:`Settings` instance.  See
        :func:`get_s3_client` for semantics.

    Returns
    -------
    botocore.client.BaseClient
        A ``boto3`` client of service type ``sqs``, bound to the configured
        region and (optionally) endpoint.
    """
    effective = settings or Settings()
    return boto3.client("sqs", **_build_client_kwargs(effective))


def get_secrets_manager_client(
    settings: Settings | None = None,
) -> BaseClient:
    """
    Construct an AWS Secrets Manager ``boto3`` client.

    Secrets Manager replaces z/OS RACF-protected credential storage and
    JCL DD-statement-embedded credentials.  In the target architecture
    Secrets Manager is the authoritative store for:

    * **Aurora PostgreSQL credentials** — the JDBC username and password
      used by both the FastAPI service
      (:mod:`src.api.database`) and the PySpark Glue jobs
      (:mod:`src.batch.common.db_connector`).  The secret identifier is
      configured by :attr:`Settings.DB_SECRET_NAME` (default
      ``carddemo/aurora-credentials``).
    * **JWT signing keys** — the HS256 secret used by
      :mod:`src.api.middleware.auth` to sign and validate tokens that
      replace CICS COMMAREA session state.
    * **Future secrets** — any additional credentials introduced by
      downstream services.

    Retrieval pattern
    ^^^^^^^^^^^^^^^^^
    Callers typically invoke ``get_secret_value(SecretId=...)`` and parse
    the resulting ``SecretString`` as JSON:

    .. code-block:: python

        client = get_secrets_manager_client()
        raw = client.get_secret_value(SecretId=settings.DB_SECRET_NAME)
        creds = json.loads(raw["SecretString"])
        username, password = creds["username"], creds["password"]

    Parameters
    ----------
    settings :
        Optional explicit :class:`Settings` instance.  See
        :func:`get_s3_client` for semantics.

    Returns
    -------
    botocore.client.BaseClient
        A ``boto3`` client of service type ``secretsmanager``, bound to the
        configured region and (optionally) endpoint.
    """
    effective = settings or Settings()
    return boto3.client("secretsmanager", **_build_client_kwargs(effective))
