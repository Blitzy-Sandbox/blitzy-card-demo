# ============================================================================
# Source: COBOL working-storage literals, JCL PARM/DD, CICS resource defs
#         → Pydantic BaseSettings
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
"""Pydantic BaseSettings for CardDemo cloud-native application.

Centralizes all environment-based configuration previously embedded in COBOL
programs (working storage literals), JCL job cards (PARM values, DD
statements), and CICS resource definitions.

Mainframe → Cloud Mapping
-------------------------
* **CICS COMMAREA** (``app/cpy/COCOM01Y.cpy``) — the 96-byte
  ``CARDDEMO-COMMAREA`` structure carrying ``CDEMO-USER-ID`` /
  ``CDEMO-USER-TYPE`` across CICS program transfers — is replaced by
  stateless JWT tokens. The JWT signing parameters (``JWT_SECRET_KEY``,
  ``JWT_ALGORITHM``, ``JWT_ACCESS_TOKEN_EXPIRE_MINUTES``) are configured
  here.
* **CICS sign-on security** (``app/cbl/COSGN00C.cbl`` reading
  ``app/cpy/CSUSR01Y.cpy`` via ``WS-USRSEC-FILE`` DD ``USRSEC``) —
  becomes BCrypt password verification + JWT issuance. The BCrypt
  hashing is performed in the auth service; JWT parameters live here.
* **JCL DD statements** (e.g., ``//ACCTFILE DD DSN=AWS.M2.CARDDEMO.\
ACCTDATA.VSAM.KSDS`` from ``app/jcl/ACCTFILE.jcl``) — become
  ``DATABASE_URL`` and ``DATABASE_URL_SYNC`` connection strings to
  Aurora PostgreSQL.
* **JCL JOB card parameters** (``CLASS=A``, ``MSGCLASS=0`` from
  ``app/jcl/POSTTRAN.jcl``) — become AWS service configuration:
  ``AWS_REGION``, ``S3_BUCKET_NAME``, ``SQS_QUEUE_URL``,
  ``GLUE_JOB_ROLE_ARN``.
* **POSTTRAN.jcl pipeline parameters** (STEPLIB, SYSPRINT, DD
  statements for TRANFILE/DALYTRAN/XREFFILE/ACCTFILE/TCATBALF) —
  become Glue job configuration consumed by ``src/batch/jobs/*``.

Design Principles
-----------------
* **Environment variable validation at startup** — Pydantic raises
  ``ValidationError`` if a required field is missing and has no
  default, failing fast rather than at first use.
* **`.env` file support via python-dotenv** — For local development
  with ``docker-compose``, a ``.env`` file at the project root is
  automatically loaded.
* **Credentials via AWS Secrets Manager (fail-fast, no defaults)** —
  Sensitive credentials (``JWT_SECRET_KEY``, ``DATABASE_URL``,
  ``DATABASE_URL_SYNC``) have **no default values**; Pydantic raises
  ``ValidationError`` at process startup if any is missing (CWE-798
  protection, AAP §0.7.2). For local ``docker-compose`` development
  these values are provided by the compose file's ``environment:``
  block; production deployments on AWS ECS inject them via the task
  definition's ``secrets`` block referencing AWS Secrets Manager.
  ``DB_SECRET_NAME`` names the Secrets Manager secret holding Aurora
  PostgreSQL credentials (``username``, ``password``, ``host``,
  ``port``, ``dbname``).
* **IAM-based AWS auth** — No AWS access keys are stored in settings;
  all AWS clients rely on IAM roles attached to the ECS task role or
  Glue job execution role (see AAP §0.7.2 Security Requirements).
* **Minimal change clause** — Only settings strictly required by the
  API layer (``src/api/``) and batch layer (``src/batch/``) for the
  COBOL→Python/AWS migration. No speculative fields.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.2 — Security Requirements
AAP §0.7.3 — User-Specified Implementation Rules
"""

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


# ----------------------------------------------------------------------------
# Settings class
# ----------------------------------------------------------------------------
class Settings(BaseSettings):
    """Centralized application settings loaded from environment variables.

    This class is instantiated once per process (typically cached behind
    ``functools.lru_cache`` in ``src.shared.config.get_settings``) and read
    by both workload layers:

    * **API layer** (``src/api/``) — reads ``DATABASE_URL``, ``JWT_*``,
      ``AWS_REGION``, ``SQS_QUEUE_URL``, ``LOG_LEVEL`` to configure the
      FastAPI application, its async SQLAlchemy engine, JWT middleware,
      and report-submission SQS producer.
    * **Batch layer** (``src/batch/``) — reads ``DATABASE_URL_SYNC`` (or
      retrieves credentials via ``DB_SECRET_NAME``), ``AWS_REGION``,
      ``S3_BUCKET_NAME``, ``GLUE_JOB_ROLE_ARN``, ``LOG_LEVEL`` to
      configure PySpark Glue jobs and their JDBC connections to Aurora
      PostgreSQL.

    Sources of values at runtime (in precedence order):
        1. Environment variables (case-sensitive)
        2. ``.env`` file in the project root (local development only)
        3. Field defaults declared below (only non-secret fields have
           defaults; see below)

    Credential-bearing fields (``DATABASE_URL``, ``DATABASE_URL_SYNC``,
    ``JWT_SECRET_KEY``) have **no defaults** — they must be supplied via
    environment variables (or the ``.env`` file). Pydantic raises
    ``ValidationError`` at process startup if any is missing, making
    accidental runtime use of placeholder credentials impossible
    (CWE-798 protection, AAP §0.7.2). For local ``docker-compose``
    development these are provided by the compose file's
    ``environment:`` block (``postgres`` hostname, dev-only credentials);
    production deployments on AWS ECS inject them via the task
    definition's ``secrets`` block referencing AWS Secrets Manager.
    """

    # ------------------------------------------------------------------
    # Database configuration
    # ------------------------------------------------------------------
    # Replaces VSAM DD statements such as:
    #   //ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS  (ACCTFILE.jcl)
    #   //TRANFILE DD DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS  (POSTTRAN.jcl)
    #   //XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS  (POSTTRAN.jcl)
    #   //TCATBALF DD DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS  (POSTTRAN.jcl)
    # All ten VSAM KSDS clusters are consolidated into one Aurora
    # PostgreSQL database reachable via the connection string below.
    # ------------------------------------------------------------------
    DATABASE_URL: str = Field(
        ...,
        description=(
            "Aurora PostgreSQL connection string for async SQLAlchemy. "
            "Replaces VSAM DD statements (e.g., DSN=AWS.M2.CARDDEMO."
            "ACCTDATA.VSAM.KSDS). Uses the `postgresql+asyncpg://` scheme "
            "for the FastAPI async engine. "
            "REQUIRED — no default (AAP §0.7.2, CWE-798); provided via "
            "docker-compose ``environment:`` for local dev or AWS Secrets "
            "Manager → ECS task definition ``secrets`` in staging/"
            "production. Pydantic raises ValidationError at startup if "
            "unset, preventing accidental use of placeholder credentials."
        ),
    )
    DATABASE_URL_SYNC: str = Field(
        ...,
        description=(
            "Synchronous PostgreSQL connection string for Alembic "
            "migrations, PySpark Glue JDBC fallback, and any blocking "
            "batch operations. Uses the `postgresql+psycopg2://` scheme. "
            "REQUIRED — no default (AAP §0.7.2, CWE-798); provided via "
            "docker-compose ``environment:`` for local dev or AWS Secrets "
            "Manager → ECS task definition ``secrets`` in staging/"
            "production. Pydantic raises ValidationError at startup if "
            "unset, preventing accidental use of placeholder credentials."
        ),
    )
    DB_SECRET_NAME: str = Field(
        default="carddemo/aurora-credentials",
        description=(
            "AWS Secrets Manager secret name holding Aurora PostgreSQL "
            "credentials (username, password, host, port, dbname). "
            "Replaces z/OS RACF credential management for VSAM dataset "
            "access."
        ),
    )
    DB_POOL_SIZE: int = Field(
        default=10,
        description=(
            "SQLAlchemy connection pool size (persistent connections "
            "per process). Tuned for ECS Fargate task memory budget."
        ),
    )
    DB_MAX_OVERFLOW: int = Field(
        default=20,
        description=(
            "SQLAlchemy maximum overflow connections above DB_POOL_SIZE. "
            "Bursts beyond the pool are permitted up to this limit "
            "before requests block waiting for a free connection."
        ),
    )

    # ------------------------------------------------------------------
    # JWT authentication configuration
    # ------------------------------------------------------------------
    # Replaces CICS COMMAREA session state carried via EXEC CICS RETURN
    # TRANSID(...) COMMAREA(CARDDEMO-COMMAREA) — see app/cpy/COCOM01Y.cpy.
    # The 8-byte CDEMO-USER-ID and 1-byte CDEMO-USER-TYPE (A=Admin /
    # U=User) previously passed between CICS transactions are now
    # encoded as claims inside a signed JWT. The original sign-on
    # program COSGN00C.cbl validates WS-USER-ID/WS-USER-PWD against
    # the USRSEC VSAM file (CSUSR01Y.cpy record layout); the Python
    # equivalent (src/api/services/auth_service.py) validates BCrypt
    # hashes and issues a JWT with CDEMO-USER-ID / CDEMO-USER-TYPE
    # claims mirroring the COMMAREA fields.
    # ------------------------------------------------------------------
    JWT_SECRET_KEY: str = Field(
        ...,
        description=(
            "JWT signing secret. Replaces CICS COMMAREA session state "
            "(CDEMO-USER-ID, CDEMO-USER-TYPE from COCOM01Y.cpy). "
            "REQUIRED — no default (AAP §0.7.2, CWE-798); provided via "
            "docker-compose ``environment:`` for local dev or AWS "
            "Secrets Manager → ECS task definition ``secrets`` in "
            "staging/production. Pydantic raises ValidationError at "
            "startup if unset, preventing accidental use of a "
            "placeholder signing key that would render JWTs trivially "
            "forgeable."
        ),
    )
    JWT_ALGORITHM: str = Field(
        default="HS256",
        description=(
            "JWT signing algorithm. HS256 (HMAC-SHA256) is compatible "
            "with python-jose and sufficient for a single-service "
            "deployment; switch to RS256 for multi-service federation."
        ),
    )
    JWT_ACCESS_TOKEN_EXPIRE_MINUTES: int = Field(
        default=30,
        description=(
            "JWT access token expiration in minutes. Replaces the CICS "
            "transaction RTIMOUT timeout. Tune to match session length "
            "requirements of the card-demo UI. The canonical env-var "
            "name ``JWT_ACCESS_TOKEN_EXPIRE_MINUTES`` matches the "
            "``docker-compose.yml`` ``environment:`` key and the "
            "README setup instructions — renamed from the earlier "
            "``JWT_EXPIRE_MINUTES`` to eliminate silent config drift "
            "(see code-review Finding #4)."
        ),
    )

    # ------------------------------------------------------------------
    # AWS service configuration
    # ------------------------------------------------------------------
    # Replaces JCL JOB card parameters (CLASS=A, MSGCLASS=0, NOTIFY)
    # and CICS System Initialization Table (SIT) settings. In the
    # target architecture, AWS IAM roles replace RACF access control,
    # and AWS service endpoints replace the z/OS subsystem routing
    # (JES2 for batch, CICS region for online).
    # ------------------------------------------------------------------
    AWS_REGION: str = Field(
        default="us-east-1",
        validation_alias=AliasChoices("AWS_REGION", "AWS_DEFAULT_REGION"),
        description=(
            "AWS region for all service clients (S3, SQS, Secrets "
            "Manager, Glue, CloudWatch). Applied to boto3.Config at "
            "client construction time in aws_config.py. Accepts both "
            "``AWS_REGION`` (boto3 convention, GitHub Actions deploy "
            "workflows) and ``AWS_DEFAULT_REGION`` (AWS CLI/SDK "
            "convention, ``docker-compose.yml`` and ``ci.yml``) via "
            "Pydantic validation alias — prevents silent config drift "
            "across deployment contexts (see code-review Finding #4)."
        ),
    )
    S3_BUCKET_NAME: str = Field(
        default="carddemo-data",
        description=(
            "S3 bucket for statement/report output and reject logs. "
            "Replaces GDG (Generation Data Group) generations defined "
            "in DEFGDGB.jcl, REPTFILE.jcl, DALYREJS.jcl. S3 object "
            "versioning substitutes for GDG (+1) semantics."
        ),
    )
    SQS_QUEUE_URL: str = Field(
        default="",
        description=(
            "SQS FIFO queue URL for report submission. Replaces the "
            "CICS TDQ (Transient Data Queue) WRITEQ JOBS pattern used "
            "by CORPT00C.cbl to trigger batch report generation. "
            "Empty default disables SQS in local dev; production must "
            "supply the full queue URL."
        ),
    )
    GLUE_JOB_ROLE_ARN: str = Field(
        default="",
        description=(
            "IAM role ARN assumed by AWS Glue jobs. Replaces JCL JOB "
            "card CLASS/MSGCLASS parameters (from POSTTRAN.jcl et al.) "
            "that identified the batch execution class under JES2. "
            "Empty default is valid only for local/development where "
            "Glue is mocked or not invoked."
        ),
    )
    AWS_ENDPOINT_URL: str = Field(
        default="",
        description=(
            "Optional AWS service endpoint URL override, used exclusively "
            "for local development against LocalStack. When set to a "
            "non-empty value, all boto3 clients constructed via "
            "``src.shared.config.aws_config`` (S3, SQS, Secrets Manager) "
            "will target this endpoint instead of the AWS-managed "
            "regional endpoints — this enables the Docker Compose local "
            "development stack to fully exercise AWS integration code "
            "paths against the ``localstack/localstack:3`` container "
            "(see docker-compose.yml ``AWS_ENDPOINT_URL: "
            "http://localstack:4566``). In production deployments to "
            "AWS ECS Fargate this variable MUST be unset (or empty) so "
            "that boto3 resolves to the real AWS endpoints via the "
            "standard regional / VPC endpoint discovery mechanism. "
            "There is no mainframe equivalent — the original z/OS "
            "application had no concept of a pluggable service endpoint."
        ),
    )

    # ------------------------------------------------------------------
    # Application configuration
    # ------------------------------------------------------------------
    LOG_LEVEL: str = Field(
        default="INFO",
        description=(
            "Logging level for structured JSON logging. Supported "
            "values: DEBUG, INFO, WARNING, ERROR, CRITICAL. Emitted "
            "logs are aggregated by AWS CloudWatch (ECS awslogs "
            "driver for the API; Glue CloudWatch log groups for "
            "batch jobs)."
        ),
    )
    APP_NAME: str = Field(
        default="carddemo",
        description=(
            "Application name used in log records, CloudWatch dashboards, and OpenTelemetry service identification."
        ),
    )
    APP_VERSION: str = Field(
        default="1.0.0",
        description=(
            "Application version string (semver). Stamped into API "
            "responses (/health, /version) and log records for "
            "deployment traceability."
        ),
    )
    DEBUG: bool = Field(
        default=False,
        description=(
            "Debug mode flag. When True, FastAPI enables interactive "
            "docs at /docs and /redoc, SQLAlchemy echoes SQL to stdout, "
            "and stack traces surface in API responses. MUST be False "
            "in production."
        ),
    )

    # ------------------------------------------------------------------
    # Model configuration — controls .env loading and validation
    # behavior for the Pydantic BaseSettings subclass.
    # ------------------------------------------------------------------
    # `env_file`           — local development `.env` file is auto-
    #                        loaded via python-dotenv (installed in
    #                        requirements.txt); production deployments
    #                        never ship a `.env` file and instead rely
    #                        on ECS task-definition env vars / Secrets
    #                        Manager references.
    # `env_file_encoding`  — UTF-8 is the universal default and
    #                        matches the ASCII seed data in
    #                        `app/data/ASCII/`.
    # `case_sensitive`     — True: DATABASE_URL (not database_url)
    #                        must match. Aligns with the convention
    #                        that POSIX environment variables are
    #                        case-sensitive and avoids accidental
    #                        collisions.
    # `extra`              — "ignore": unknown environment variables
    #                        (e.g., AWS_ACCESS_KEY_ID, PATH, HOME)
    #                        are silently skipped rather than causing
    #                        validation errors. The alternative
    #                        "forbid" would break any container
    #                        environment that injects unrelated env
    #                        vars (which AWS ECS invariably does).
    # ------------------------------------------------------------------
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",
    )


# ----------------------------------------------------------------------------
# Public re-export list.
#
# Only the ``Settings`` class itself is part of the public API of this
# module. Consumers should either instantiate ``Settings()`` directly
# (for explicit overrides in tests) or, more commonly, import the
# cached singleton accessor ``get_settings`` from
# ``src.shared.config.__init__``.
# ----------------------------------------------------------------------------
__all__ = ["Settings"]
