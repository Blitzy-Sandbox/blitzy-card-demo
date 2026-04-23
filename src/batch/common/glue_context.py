# ============================================================================
# Source: app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl, app/jcl/CREASTMT.JCL,
#         app/jcl/TRANREPT.jcl, app/jcl/COMBTRAN.jcl
#         — JCL JOB card + STEPLIB + SYSPRINT/SYSOUT + EXEC PGM= initialization
#         → AWS Glue 5.1 GlueContext + SparkSession factory
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
"""GlueContext and SparkSession factory for AWS Glue PySpark batch jobs.

Replaces mainframe JES batch initialization patterns:

* **JCL JOB card** (``CLASS=A``, ``MSGCLASS=0``, ``NOTIFY=&SYSUID``) — the
  header directive that identifies the batch execution class, message
  class, and notify user on every one of the 5 pipeline JCLs
  (``POSTTRAN.jcl``, ``INTCALC.jcl``, ``CREASTMT.JCL``, ``TRANREPT.jcl``,
  ``COMBTRAN.jcl``) — becomes a GlueContext initialization with
  structured JSON logging to CloudWatch.
* **EXEC PGM=program** (``EXEC PGM=CBTRN02C`` in POSTTRAN;
  ``EXEC PGM=CBACT04C,PARM='2022071800'`` in INTCALC;
  ``EXEC PGM=CBSTM03A,COND=(0,NE)`` in CREASTMT;
  ``EXEC PGM=CBTRN03C`` in TRANREPT; ``EXEC PGM=SORT``/``PGM=IDCAMS`` in
  COMBTRAN) — becomes a PySpark job entry point that calls
  :func:`init_glue` once at module start.
* **STEPLIB DD DISP=SHR, DSN=AWS.M2.CARDDEMO.LOADLIB** — the z/OS batch
  load-library resolution is replaced by the AWS Glue 5.1 managed
  runtime's automatic library path (Apache Spark 3.5.6, Python 3.11,
  Scala 2.12.18). No STEPLIB equivalent is required.
* **SYSPRINT DD SYSOUT=\\*** and **SYSOUT DD SYSOUT=\\*** — the
  system-print and system-output DD statements that captured COBOL
  ``DISPLAY`` output on every batch job are replaced by CloudWatch
  structured JSON logging emitted through a ``StreamHandler`` wired to
  ``sys.stdout`` (which AWS Glue forwards to the CloudWatch log group
  ``/aws-glue/jobs/output``). The :class:`JsonFormatter` serializes each
  ``LogRecord`` as a single-line JSON document for CloudWatch Logs
  Insights queries.
* **PARM='value'** (e.g., ``PARM='2022071800'`` on INTCALC's
  ``EXEC PGM=CBACT04C``) — the traditional mechanism for passing a
  parameter string to a COBOL batch program becomes Glue's
  ``getResolvedOptions`` API, which parses command-line ``--KEY value``
  arguments injected by the Glue job runtime and returns a ``dict`` for
  programmatic access.

Graceful local-development fallback
-----------------------------------
When the ``awsglue`` Python package is NOT available (e.g., during
``pytest`` runs, local CI, or developer workstations without the AWS
Glue managed runtime installed), the factory falls back to a pure
:class:`pyspark.sql.SparkSession` in local mode. The returned
``glue_context`` and ``job`` values are ``None`` in this mode, and
:func:`commit_job` safely no-ops on a ``None`` input. This mirrors the
pattern used by AWS Glue's own sample scripts and enables the full
test suite to run under ``pytest`` without pulling the AWS Glue runtime.

Public API
----------
* :func:`init_glue` — primary factory; call once at the start of every
  batch job script. Returns ``(spark_session, glue_context, job,
  resolved_args)``.
* :func:`commit_job` — call at the end of a successful batch job to
  signal completion to Glue (replaces JCL ``MAXCC=0``).
* :class:`JsonFormatter` — the ``logging.Formatter`` subclass that
  produces CloudWatch-compatible JSON log entries. Used internally by
  :func:`init_glue` and available for re-use by any batch module that
  needs to attach additional handlers.

Source
------
``app/jcl/POSTTRAN.jcl``, ``app/jcl/INTCALC.jcl``,
``app/jcl/CREASTMT.JCL``, ``app/jcl/TRANREPT.jcl``,
``app/jcl/COMBTRAN.jcl``.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue)
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.7.2 — Monitoring Requirements (CloudWatch integration)
"""

from __future__ import annotations

import json
import logging
import sys
from typing import Any

from pyspark.context import SparkContext
from pyspark.sql import SparkSession

# ----------------------------------------------------------------------------
# AWS Glue runtime imports — conditional for graceful local development.
#
# The ``awsglue`` Python package is provided by the managed AWS Glue 5.1
# runtime on its worker nodes and is NOT available on developer
# workstations or in the local ``pytest`` environment. We guard the
# import with a try/except so that:
#   * In production (AWS Glue): ``_GLUE_AVAILABLE = True``; ``GlueContext``,
#     ``Job``, and ``getResolvedOptions`` are imported and used by
#     :func:`init_glue` to initialize the real Glue job context.
#   * In local dev / CI: ``_GLUE_AVAILABLE = False``; :func:`init_glue`
#     falls back to a pure ``pyspark.sql.SparkSession`` in local mode,
#     returning ``None`` for the GlueContext and Job arguments.
#
# This pattern enables the full test suite (``tests/unit/test_batch/``)
# to exercise each Glue job's business logic without requiring the AWS
# Glue runtime — a critical capability for TDD workflows and CI.
# ----------------------------------------------------------------------------
try:
    from awsglue.context import GlueContext
    from awsglue.job import Job
    from awsglue.utils import getResolvedOptions

    _GLUE_AVAILABLE = True
except ImportError:  # pragma: no cover - exercised only in the Glue runtime
    _GLUE_AVAILABLE = False

# Module-level logger. Intentionally acquired at import time so that the
# module's own log statements (e.g., inside :func:`init_glue`) route
# through whatever handler configuration is active at call time — which
# is the JSON-formatted stdout handler installed by :func:`_setup_logging`.
logger = logging.getLogger(__name__)


# ----------------------------------------------------------------------------
# JSON log formatter — replaces SYSPRINT DD SYSOUT=* / SYSOUT DD SYSOUT=*
# ----------------------------------------------------------------------------
class JsonFormatter(logging.Formatter):
    """Structured JSON log formatter for CloudWatch integration.

    Replaces the mainframe ``SYSPRINT DD SYSOUT=*`` and
    ``SYSOUT DD SYSOUT=*`` DD statements that captured COBOL ``DISPLAY``
    output on every batch job (POSTTRAN.jcl lines 26-27;
    INTCALC.jcl lines 25-26; CREASTMT.JCL lines 81-82;
    TRANREPT.jcl lines 62-63; COMBTRAN.jcl lines 32, 42).

    Each ``logging.LogRecord`` is serialized as a single-line JSON
    document containing a consistent schema of fields (timestamp, level,
    logger, message, module, function, line). When the log record
    carries exception information (``record.exc_info``), the formatted
    traceback is embedded under the ``exception`` key.

    CloudWatch Logs Insights queries against Glue job log groups
    (``/aws-glue/jobs/output`` and ``/aws-glue/jobs/error``) can filter
    and aggregate on any of these fields. A single-line JSON encoding is
    required so that each log event appears as one row in CloudWatch.

    The formatter is stateless and thread-safe — the same instance can
    be shared across all handlers in a Glue worker process.

    Attributes
    ----------
    (inherits all configuration from :class:`logging.Formatter`)

    Examples
    --------
    Attach to a StreamHandler writing to stdout::

        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(JsonFormatter())
        logging.getLogger().addHandler(handler)

    A resulting log line (pretty-printed for readability; actual output
    is one line)::

        {
          "timestamp": "2025-04-21 10:15:30,123",
          "level": "INFO",
          "logger": "src.batch.jobs.posttran_job",
          "message": "Processed 1,234 transactions",
          "module": "posttran_job",
          "function": "main",
          "line": 87
        }
    """

    def format(self, record: logging.LogRecord) -> str:
        """Serialize a :class:`logging.LogRecord` as a JSON string.

        The serialized payload is a single-line JSON document (no
        embedded newlines) so that CloudWatch treats each emission as
        one discrete log event. Exception tracebacks, when present, are
        formatted via :meth:`logging.Formatter.formatException` and
        embedded under the ``exception`` key.

        Parameters
        ----------
        record : logging.LogRecord
            The log record produced by the Python logging machinery.
            All attributes inherited from the logging ``LogRecord``
            protocol are available (``levelname``, ``name``,
            ``getMessage()``, ``module``, ``funcName``, ``lineno``,
            ``exc_info``).

        Returns
        -------
        str
            A single-line JSON string suitable for CloudWatch ingestion.
            Guaranteed to be non-empty and valid JSON — the ``json``
            module raises ``TypeError`` for non-serializable values,
            which would surface as a logging failure rather than silent
            data loss.
        """
        # Build the log payload as a plain dict so that json.dumps can
        # serialize it deterministically. The field order matches the
        # CloudWatch Logs Insights convention of putting the most
        # frequently queried fields (timestamp, level, message) first.
        log_entry: dict[str, Any] = {
            "timestamp": self.formatTime(record, self.datefmt),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "module": record.module,
            "function": record.funcName,
            "line": record.lineno,
        }

        # Attach the formatted traceback when the caller passed
        # ``exc_info`` (e.g., ``logger.exception("...")`` or
        # ``logger.error("...", exc_info=True)``). The second guard on
        # ``record.exc_info[0]`` avoids the edge case where exc_info is
        # set to ``(None, None, None)`` — a legal but meaningless value
        # produced by some third-party logging adapters.
        if record.exc_info and record.exc_info[0] is not None:
            log_entry["exception"] = self.formatException(record.exc_info)

        # ``default=str`` protects against non-JSON-native values (e.g.,
        # ``Decimal``, ``datetime``) appearing in structured log extras
        # — they are coerced to their string representation rather than
        # raising ``TypeError`` and losing the log event entirely.
        return json.dumps(log_entry, default=str)


# ----------------------------------------------------------------------------
# Logging configuration — installs the JSON formatter on the root logger.
# ----------------------------------------------------------------------------
def _setup_logging(log_level: str = "INFO") -> None:
    """Configure the root logger with a JSON-formatted stdout handler.

    Idempotent: existing handlers on the root logger are removed before
    the new JSON StreamHandler is installed so that repeated calls (from
    nested test fixtures, for example) do not cause duplicate log
    emissions. Child loggers (e.g., ``logging.getLogger(__name__)``)
    inherit from the root configuration automatically — no per-module
    configuration is required.

    The handler targets :data:`sys.stdout` rather than :data:`sys.stderr`
    because AWS Glue's CloudWatch integration captures both streams but
    the standard convention for structured logs in the AWS Glue 5.1
    runtime is stdout.

    Parameters
    ----------
    log_level : str, optional
        Minimum log level to emit. One of ``"DEBUG"``, ``"INFO"``,
        ``"WARNING"``, ``"ERROR"``, ``"CRITICAL"``. Defaults to
        ``"INFO"``. Case-insensitive. Unrecognized levels fall back to
        ``logging.INFO`` (via :func:`getattr` default) so that a typo
        in an environment variable does not disable logging entirely.

    Notes
    -----
    This function is intentionally private (``_setup_logging``) —
    external callers should invoke :func:`init_glue` instead, which
    handles logging configuration together with Spark/Glue setup. The
    function is exposed for test isolation and advanced customization
    scenarios only.
    """
    root_logger = logging.getLogger()
    # Resolve the log level case-insensitively. ``getattr`` with a
    # default guards against typos in the LOG_LEVEL environment variable
    # — an unknown level silently falls back to INFO rather than
    # disabling logging outright.
    root_logger.setLevel(getattr(logging, log_level.upper(), logging.INFO))

    # Remove any existing handlers to avoid duplicate log entries. This
    # is important in pytest scenarios where :func:`init_glue` is called
    # across multiple tests in the same Python process — without this
    # cleanup, each test would accumulate another StreamHandler and log
    # lines would be emitted N times per event after N tests.
    for handler in root_logger.handlers[:]:
        root_logger.removeHandler(handler)

    # Install the single JSON-formatted stdout handler. CloudWatch's
    # awslogs agent (for ECS) and the Glue CloudWatch log group
    # integration both capture stdout automatically — there is no need
    # to write to a file or to configure a custom log driver.
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root_logger.addHandler(handler)


# ----------------------------------------------------------------------------
# Main factory function — replaces JCL JOB + EXEC PGM= + STEPLIB init.
# ----------------------------------------------------------------------------
def init_glue(
    job_name: str | None = None,
    args: list[str] | None = None,
) -> tuple[Any, Any, Any, dict[str, str]]:
    """Initialize an AWS Glue job context with Spark, GlueContext, and Job.

    This is the single entry point for every PySpark Glue job script in
    ``src/batch/jobs/``. It replaces the mainframe JES batch
    initialization sequence::

        //POSTTRAN JOB 'POSTTRAN',CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID
        //STEP15 EXEC PGM=CBTRN02C
        //STEPLIB  DD DISP=SHR,DSN=AWS.M2.CARDDEMO.LOADLIB
        //SYSPRINT DD SYSOUT=*
        //SYSOUT   DD SYSOUT=*

    with a single factory call::

        spark, glue_ctx, job, args = init_glue()

    The function operates in two distinct modes selected at import time:

    1. **Production (AWS Glue runtime)**: ``_GLUE_AVAILABLE is True``.
       Creates a real :class:`pyspark.context.SparkContext`, wraps it in
       :class:`awsglue.context.GlueContext`, extracts the ``SparkSession``
       from the GlueContext, initializes an :class:`awsglue.job.Job`
       object with the job name and resolved arguments, and applies
       Spark SQL tuning parameters (shuffle partitions, adaptive query
       execution). Equivalent to the full JCL initialization sequence.
    2. **Local development / CI**: ``_GLUE_AVAILABLE is False``.
       Creates a standalone local :class:`pyspark.sql.SparkSession` via
       ``SparkSession.builder`` with the same Spark SQL tuning
       parameters. Returns ``None`` for ``glue_context`` and ``job`` —
       callers must handle these ``None`` values gracefully (the
       companion :func:`commit_job` helper does so automatically).

    Spark configuration applied in both modes
    -----------------------------------------
    * ``spark.sql.shuffle.partitions = 10`` — Sized for the CardDemo
      workload (50-account fixture dataset, low-to-medium transaction
      volume). Prevents oversharding on small tables which would pay
      the shuffle overhead without parallelism benefit.
    * ``spark.sql.adaptive.enabled = true`` — Enables Adaptive Query
      Execution (AQE) for runtime optimization of shuffle partitions,
      join strategies, and skew handling. Standard best practice on
      Spark 3.5.x.

    Parameters
    ----------
    job_name : str or None, optional
        Explicit job name override. When ``None`` (the default), the
        name is extracted from the resolved Glue arguments under the
        ``"JOB_NAME"`` key (populated by the Glue runtime from
        ``--JOB_NAME carddemo-posttran`` style command-line arguments).
        For local development, if both ``job_name`` and ``args`` are
        unset, the effective name falls back to ``"carddemo-batch-local"``.
    args : list[str] or None, optional
        Explicit argument list override (used primarily in tests to
        inject deterministic arguments without mutating
        :data:`sys.argv`). When ``None``, :data:`sys.argv` is used. In
        production, AWS Glue populates ``sys.argv`` with the resolved
        job arguments prefixed by the script path.

    Returns
    -------
    tuple
        A 4-element tuple ``(spark_session, glue_context, job,
        resolved_args)``:

        * ``spark_session`` — a configured :class:`pyspark.sql.SparkSession`.
          The primary Spark entry point for DataFrame operations inside
          each batch job. Typed as :class:`typing.Any` because
          ``awsglue`` types are not available in local development mode.
        * ``glue_context`` — :class:`awsglue.context.GlueContext`
          wrapping the SparkContext when running in the Glue runtime;
          ``None`` in local development mode. Provides Glue-specific
          features like ``DynamicFrame``, ``resolveChoice``, and the
          Glue Data Catalog integration.
        * ``job`` — :class:`awsglue.job.Job` instance used to signal
          job start (already called via ``job.init``) and job
          completion (callers should invoke :func:`commit_job` on this
          object at the end of their script); ``None`` in local mode.
        * ``resolved_args`` — ``dict[str, str]`` of parsed Glue job
          arguments. Contains at minimum ``{"JOB_NAME": ...}``. Batch
          jobs requiring additional arguments (e.g., ``PARM='2022071800'``
          from INTCALC.jcl) should pass the argument names to
          :func:`awsglue.utils.getResolvedOptions` explicitly in their
          own script — this factory resolves only ``JOB_NAME``.

    Raises
    ------
    pydantic.ValidationError
        If the application's :class:`~src.shared.config.settings.Settings`
        cannot load required environment variables (DATABASE_URL,
        DATABASE_URL_SYNC, JWT_SECRET_KEY). Propagated from the lazy
        Settings import — batch jobs fail fast on missing configuration.

    Examples
    --------
    Typical usage at the top of a batch job script::

        from src.batch.common.glue_context import init_glue, commit_job

        def main() -> None:
            spark, glue_ctx, job, args = init_glue()
            # ... PySpark business logic ...
            commit_job(job)

        if __name__ == "__main__":
            main()

    Notes
    -----
    The lazy import of :class:`~src.shared.config.settings.Settings`
    inside the function body (rather than at module top-level) is
    intentional — it avoids circular-import risk between
    ``src.batch.common`` and ``src.shared.config`` and defers the
    Pydantic validation cost until the batch job actually starts (which
    matters for static analysis and module discovery by tooling like
    ``ruff`` and ``mypy``).
    """
    # Lazy import to avoid circular dependencies and to defer Pydantic
    # validation until the job actually runs. Settings() will raise
    # pydantic.ValidationError if required env vars (DATABASE_URL,
    # DATABASE_URL_SYNC, JWT_SECRET_KEY) are missing — batch jobs fail
    # fast on missing configuration, matching the AAP §0.7.2 discipline.
    from src.shared.config.settings import Settings

    settings = Settings()
    _setup_logging(settings.LOG_LEVEL)

    logger.info("Initializing AWS Glue job context")

    if _GLUE_AVAILABLE:
        # ------------------------------------------------------------------
        # Production path: running on the AWS Glue 5.1 managed runtime.
        # ------------------------------------------------------------------
        # ``getResolvedOptions`` is Glue's argparse-equivalent — it
        # extracts ``--KEY value`` pairs from the provided argv list
        # into a dict. We always request ``JOB_NAME`` (populated by the
        # Glue runtime); individual job scripts can call
        # ``getResolvedOptions`` again with additional keys for their
        # specific parameters (e.g., ``--POSTING_DATE 2022-07-18`` for
        # INTCALC's PARM='2022071800' equivalent).
        resolved_args: dict[str, str] = getResolvedOptions(
            args or sys.argv,
            ["JOB_NAME"],
        )
        effective_job_name = job_name or resolved_args.get("JOB_NAME", "carddemo-batch")

        # Create the SparkContext first, then wrap with GlueContext.
        # GlueContext extends SparkContext with Glue-specific features
        # like DynamicFrame and the Data Catalog integration.
        sc = SparkContext()
        glue_context = GlueContext(sc)
        spark_session = glue_context.spark_session

        # Apply Spark SQL tuning. These values are chosen for the
        # CardDemo workload (50-account fixture, low transaction
        # volume); larger deployments should override via Glue job
        # configuration rather than editing this factory.
        spark_session.conf.set("spark.sql.shuffle.partitions", "10")
        spark_session.conf.set("spark.sql.adaptive.enabled", "true")

        # Initialize the Glue Job object. ``job.init`` records job
        # metadata for the Glue console and CloudWatch — job name,
        # run arguments, and the start marker for job-bookmark tracking.
        job = Job(glue_context)
        job.init(effective_job_name, resolved_args)

        logger.info(
            "Glue job '%s' initialized successfully (Glue environment)",
            effective_job_name,
        )
    else:
        # ------------------------------------------------------------------
        # Local development path: pure PySpark, no AWS Glue runtime.
        # ------------------------------------------------------------------
        # Used by ``pytest`` unit tests and developer workstations. The
        # resulting SparkSession is fully functional for DataFrame
        # operations — only Glue-specific features (DynamicFrame, Data
        # Catalog, job bookmarks) are unavailable. Callers must handle
        # the ``None`` glue_context and job values; :func:`commit_job`
        # does so automatically.
        effective_job_name = job_name or "carddemo-batch-local"
        resolved_args = {"JOB_NAME": effective_job_name}

        # Build a local SparkSession with the same SQL tuning applied
        # in production. ``spark.driver.extraClassPath`` points at the
        # conventional location for the PostgreSQL JDBC driver jar
        # (shipped by ``requirements-glue.txt`` via pg8000 in local
        # mode, or pre-installed in the Glue runtime in production).
        spark_session = (
            SparkSession.builder.appName(effective_job_name)
            .config("spark.sql.shuffle.partitions", "10")
            .config("spark.sql.adaptive.enabled", "true")
            .config("spark.driver.extraClassPath", "/opt/spark/jars/*")
            .getOrCreate()
        )
        glue_context = None
        job = None

        logger.info(
            "Spark session '%s' initialized (local mode — Glue libs not available)",
            effective_job_name,
        )

    return spark_session, glue_context, job, resolved_args


# ----------------------------------------------------------------------------
# Job commit helper — replaces JCL ``MAXCC = 0`` success signaling.
# ----------------------------------------------------------------------------
def commit_job(job: Any) -> None:
    """Commit an AWS Glue job, signaling successful completion.

    Replaces the JCL ``MAXCC = 0`` success pattern that indicated a
    batch job step completed without error. On the AWS Glue runtime,
    calling ``job.commit()`` finalizes the job's bookmark state
    (enabling incremental processing on the next run) and emits the
    completion event that Step Functions and CloudWatch consume for
    pipeline orchestration and alerting.

    Safely handles the local-development mode where ``init_glue``
    returned ``None`` for the ``job`` argument — the function becomes a
    no-op with an informational log message, allowing the same batch
    job script to run unchanged under both ``pytest`` (local) and AWS
    Glue (production) without conditional logic at the call site.

    Parameters
    ----------
    job : Any
        The :class:`awsglue.job.Job` instance returned from
        :func:`init_glue` (element 2 of the returned 4-tuple), or
        ``None`` when running in local development mode. Typed as
        :class:`typing.Any` because :class:`awsglue.job.Job` is not
        importable in local development mode where ``_GLUE_AVAILABLE``
        is ``False``.

    Examples
    --------
    Typical usage at the end of a batch job script::

        def main() -> None:
            spark, glue_ctx, job, args = init_glue()
            try:
                # ... PySpark business logic ...
                pass
            finally:
                commit_job(job)

    Notes
    -----
    This function intentionally does NOT raise when ``job`` is ``None``
    — the local-development fallback is a first-class supported mode,
    not an error condition. Unit tests can call ``commit_job(None)``
    freely.
    """
    if job is not None:
        job.commit()
        logger.info("Glue job committed successfully")
    else:
        logger.info("Job commit skipped (local mode)")


# ----------------------------------------------------------------------------
# Public re-export list.
#
# Only the three user-facing symbols are part of the public API of this
# module: ``init_glue`` (the factory), ``commit_job`` (the commit
# helper), and ``JsonFormatter`` (re-exposed for advanced logging
# customization, e.g., attaching the formatter to an additional handler
# for third-party log shipping). The private ``_setup_logging`` function
# and the ``_GLUE_AVAILABLE`` module flag are intentionally excluded —
# they are implementation details that may change without a MAJOR
# version bump of ``src.batch`` (see __version__ in src/batch/__init__.py).
# ----------------------------------------------------------------------------
__all__ = ["init_glue", "commit_job", "JsonFormatter"]
