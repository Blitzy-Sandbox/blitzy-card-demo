# ============================================================================
# Copyright 2024 Amazon.com, Inc. or its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License"). You may not
# use this file except in compliance with the License. A copy of the License
# is located at:
#
#     http://www.apache.org/licenses/LICENSE-2.0
# ============================================================================
"""Unit tests for :mod:`src.batch.common.glue_context`.

This test module covers the AWS Glue job initialization factory and its
companion helpers that replace the mainframe JCL batch initialization
sequence. Specifically it provides coverage for:

* :class:`~src.batch.common.glue_context.JsonFormatter` — the
  CloudWatch-compatible JSON log formatter that replaces the COBOL
  ``DISPLAY`` output routed via ``SYSPRINT``/``SYSOUT`` DD cards (see
  POSTTRAN.jcl, INTCALC.jcl, COMBTRAN.jcl, CREASTMT.JCL, TRANREPT.jcl).
* :func:`~src.batch.common.glue_context._setup_logging` — the private
  idempotent logger configurator exercised by :func:`init_glue`.
* :func:`~src.batch.common.glue_context.init_glue` — the public factory
  that replaces the JCL ``JOB`` card plus ``EXEC PGM=`` step initialization.
  Both execution paths are covered: the production AWS Glue runtime path
  (``_GLUE_AVAILABLE is True``) exercised via mocks, and the local
  development / CI path (``_GLUE_AVAILABLE is False``) exercised via a
  mocked :class:`pyspark.sql.SparkSession` builder chain.
* :func:`~src.batch.common.glue_context.commit_job` — the public commit
  helper that replaces the JCL ``MAXCC = 0`` success signal.

COBOL / JCL → Python Verification Surface
-----------------------------------------

+---------------------------------------+-------------------------------------+
| Source construct                      | Python equivalent under test        |
+=======================================+=====================================+
| ``//POSTTRAN JOB``, ``//EXEC PGM=``,  | ``init_glue()`` factory call        |
| ``//STEPLIB DD DISP=SHR``             |                                     |
+---------------------------------------+-------------------------------------+
| ``//SYSPRINT DD SYSOUT=*``            | :class:`JsonFormatter` + stdout     |
| ``//SYSOUT   DD SYSOUT=*``            | :class:`logging.StreamHandler`      |
+---------------------------------------+-------------------------------------+
| JCL ``PARM='2022071800'`` &           | ``resolved_args`` dict from         |
| ``//SYSIN DD *``                      | :func:`getResolvedOptions`          |
+---------------------------------------+-------------------------------------+
| ``MAXCC = 0`` success signaling       | :func:`commit_job` (calls           |
|                                       | :meth:`awsglue.job.Job.commit`)     |
+---------------------------------------+-------------------------------------+

References
----------
* AAP §0.4.1 — Target structure places batch jobs under ``src/batch/``
  with a shared ``common/`` module for infrastructure primitives.
* AAP §0.4.4 — Batch layer architectural decisions (AWS Glue 5.1,
  Spark 3.5.6, Python 3.11, Aurora JDBC, S3 output, Step Functions).
* AAP §0.7.2 — Monitoring requirements (CloudWatch integration,
  structured JSON logging from batch components).
* QA Checkpoint 7 — Module coverage reported at 25% with 40 missed
  lines and no dedicated test module. This file remediates that gap
  by targeting 80%+ coverage across the 56 statements and the
  ``_GLUE_AVAILABLE`` branching logic.

Notes
-----
The module under test ships a conditional top-level import of the
``awsglue`` namespace. On developer workstations and CI environments
without the AWS Glue runtime wheels installed (the CardDemo default),
``_GLUE_AVAILABLE`` evaluates to ``False`` and the module-level names
``GlueContext``, ``Job``, and ``getResolvedOptions`` are never bound.
To exercise the production path deterministically we patch these
attributes onto the module using :func:`unittest.mock.patch.object`
with ``create=True`` — this mirrors how the Glue runtime injects the
real symbols on import, without requiring the heavy Glue wheel.
"""

from __future__ import annotations

import json
import logging
import sys
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from src.batch.common import glue_context as gc_mod
from src.batch.common.glue_context import (
    JsonFormatter,
    _setup_logging,
    commit_job,
    init_glue,
)

# ============================================================================
# Module-level markers
# ============================================================================

pytestmark = pytest.mark.unit


# ============================================================================
# Fixtures
# ============================================================================


@pytest.fixture
def log_record() -> logging.LogRecord:
    """Provide a deterministic :class:`logging.LogRecord` for formatter tests.

    Populates every attribute referenced by :meth:`JsonFormatter.format`
    so assertions can verify exact field values without depending on
    the caller's frame (which ``logging.LogRecord`` normally derives).
    """
    record = logging.LogRecord(
        name="src.batch.jobs.posttran_job",
        level=logging.INFO,
        pathname="/opt/glue/posttran_job.py",
        lineno=128,
        msg="Posting transaction %s to account %s",
        args=("TXN-0001", "00000000123"),
        exc_info=None,
    )
    # ``module`` and ``funcName`` are normally set by the logging
    # machinery based on the caller's stack frame. We override them
    # explicitly for deterministic assertions.
    record.module = "posttran_job"
    record.funcName = "post_transaction"
    return record


@pytest.fixture
def clean_root_logger() -> Any:
    """Save and restore the root logger state between tests.

    :func:`_setup_logging` mutates global logger state (handlers and
    level) to configure structured JSON output on stdout. Without
    this fixture, repeated :func:`_setup_logging` invocations across
    tests would leak handlers into subsequent pytest captured-log
    assertions. The fixture snapshots pre-test handlers and level,
    yields control to the test, then restores the original state.

    COBOL context: equivalent to the JCL ``DISPLAY`` redirection
    cleanup that each batch step performs on completion.
    """
    root = logging.getLogger()
    saved_handlers = root.handlers[:]
    saved_level = root.level
    yield root
    # Restore pre-test handlers and level.
    for handler in root.handlers[:]:
        root.removeHandler(handler)
    for handler in saved_handlers:
        root.addHandler(handler)
    root.setLevel(saved_level)


@pytest.fixture
def patched_glue_namespace() -> Any:
    """Patch the ``awsglue`` namespace onto :mod:`glue_context` for tests.

    The production path of :func:`init_glue` references four names that
    only exist when AWS Glue's Python libraries are installed:

    * ``_GLUE_AVAILABLE`` — module-level bool flag (bound in both modes)
    * ``getResolvedOptions`` — :mod:`awsglue.utils` helper (unbound in
      local mode)
    * ``GlueContext`` — :mod:`awsglue.context` class (unbound in local)
    * ``Job`` — :mod:`awsglue.job` class (unbound in local)

    In the CardDemo test environment these symbols are unbound because
    ``_GLUE_AVAILABLE`` evaluated to ``False`` at module import time.
    This fixture injects deterministic ``MagicMock`` instances for all
    four names (using ``create=True`` for the awsglue entries) and
    also patches :class:`pyspark.context.SparkContext` so no real
    JVM is spun up.

    The fixture yields a dict of the mock objects so tests can assert
    the expected call sequences on them.
    """
    mocks = {
        "getResolvedOptions": MagicMock(
            return_value={"JOB_NAME": "carddemo-posttran"},
            name="getResolvedOptions",
        ),
        "GlueContext": MagicMock(name="GlueContextClass"),
        "Job": MagicMock(name="JobClass"),
        "SparkContext": MagicMock(name="SparkContextClass"),
    }
    # Configure the nested-mock chain so tests can inspect each call.
    # GlueContext(sc).spark_session → a distinct mock we can compare to.
    mock_glue_ctx_instance = MagicMock(name="GlueContextInstance")
    mock_spark_session = MagicMock(name="SparkSession")
    mock_glue_ctx_instance.spark_session = mock_spark_session
    mocks["GlueContext"].return_value = mock_glue_ctx_instance
    mocks["glue_ctx_instance"] = mock_glue_ctx_instance
    mocks["spark_session"] = mock_spark_session

    # Job(glue_context) → a distinct mock with an observable .init().
    mock_job_instance = MagicMock(name="JobInstance")
    mocks["Job"].return_value = mock_job_instance
    mocks["job_instance"] = mock_job_instance

    with (
        patch.object(gc_mod, "_GLUE_AVAILABLE", True),
        patch.object(gc_mod, "getResolvedOptions", mocks["getResolvedOptions"], create=True),
        patch.object(gc_mod, "GlueContext", mocks["GlueContext"], create=True),
        patch.object(gc_mod, "Job", mocks["Job"], create=True),
        patch.object(gc_mod, "SparkContext", mocks["SparkContext"]),
    ):
        yield mocks


@pytest.fixture
def patched_local_spark() -> Any:
    """Patch :class:`pyspark.sql.SparkSession` onto :mod:`glue_context`.

    The local development path of :func:`init_glue` builds a
    :class:`~pyspark.sql.SparkSession` via
    ``SparkSession.builder.appName(...).config(...).getOrCreate()``.
    This fixture replaces the entire :class:`SparkSession` symbol on
    the module with a :class:`MagicMock` whose ``.builder`` attribute
    is a chain-compatible mock — every ``.appName(...)``, ``.config(...)``
    and ``.getOrCreate()`` call returns the same builder mock, enabling
    assertions on the exact sequence of configuration directives.

    Yields
    ------
    dict
        ``{"SparkSession": SparkSession_mock, "builder": builder_mock,
        "session": session_returned_by_getOrCreate}``.
    """
    # Build a chain-compatible mock: every method on ``builder`` returns
    # ``builder`` itself so call chaining works. ``getOrCreate`` returns
    # a separate mock that stands in for the returned SparkSession.
    mock_builder = MagicMock(name="SparkSessionBuilder")
    mock_builder.appName.return_value = mock_builder
    mock_builder.config.return_value = mock_builder
    mock_session = MagicMock(name="LocalSparkSession")
    mock_builder.getOrCreate.return_value = mock_session

    mock_spark_session_cls = MagicMock(name="SparkSessionClass")
    mock_spark_session_cls.builder = mock_builder

    with patch.object(gc_mod, "SparkSession", mock_spark_session_cls):
        yield {
            "SparkSession": mock_spark_session_cls,
            "builder": mock_builder,
            "session": mock_session,
        }


# ============================================================================
# Phase 1: JsonFormatter tests — CloudWatch-compatible JSON log encoding.
# COBOL heritage: DISPLAY statement + SYSPRINT DD routing. Each log record
# must be a single-line JSON document so CloudWatch Logs Insights can parse
# it via its native JSON extraction (AAP §0.7.2).
# ============================================================================


class TestJsonFormatter:
    """Behavior of :class:`JsonFormatter.format`."""

    def test_output_is_valid_json(self, log_record: logging.LogRecord) -> None:
        """``format`` must produce a string that parses as valid JSON."""
        formatter = JsonFormatter()
        out = formatter.format(log_record)
        # If json.loads raises, the output was not valid JSON.
        parsed = json.loads(out)
        assert isinstance(parsed, dict)

    def test_output_is_single_line(self, log_record: logging.LogRecord) -> None:
        """Output must be a single line (no embedded newlines) for CloudWatch."""
        formatter = JsonFormatter()
        out = formatter.format(log_record)
        assert "\n" not in out, (
            "CloudWatch Logs Insights parses records line-by-line; embedded "
            "newlines would split a single log event into multiple events."
        )

    def test_output_contains_all_required_keys(self, log_record: logging.LogRecord) -> None:
        """All 7 base keys must be present on every log entry."""
        formatter = JsonFormatter()
        parsed = json.loads(formatter.format(log_record))
        expected_keys = {
            "timestamp",
            "level",
            "logger",
            "message",
            "module",
            "function",
            "line",
        }
        assert expected_keys.issubset(parsed.keys())

    def test_output_values_match_record(self, log_record: logging.LogRecord) -> None:
        """Each field in the JSON payload must reflect the LogRecord source."""
        formatter = JsonFormatter()
        parsed = json.loads(formatter.format(log_record))
        assert parsed["level"] == "INFO"
        assert parsed["logger"] == "src.batch.jobs.posttran_job"
        assert parsed["message"] == "Posting transaction TXN-0001 to account 00000000123"
        assert parsed["module"] == "posttran_job"
        assert parsed["function"] == "post_transaction"
        assert parsed["line"] == 128
        # ``timestamp`` is a non-empty formatted string — exact format is
        # implementation-defined by :meth:`logging.Formatter.formatTime`.
        assert isinstance(parsed["timestamp"], str) and len(parsed["timestamp"]) > 0

    def test_message_args_are_substituted(self) -> None:
        """``%s``-style args must be interpolated via ``record.getMessage()``."""
        formatter = JsonFormatter()
        record = logging.LogRecord(
            name="app",
            level=logging.INFO,
            pathname="/app.py",
            lineno=1,
            msg="Account %s balance: %s",
            args=("00000000123", "1500.00"),
            exc_info=None,
        )
        parsed = json.loads(formatter.format(record))
        assert parsed["message"] == "Account 00000000123 balance: 1500.00"

    def test_no_exception_field_when_exc_info_is_none(self, log_record: logging.LogRecord) -> None:
        """Records without exc_info must not emit an ``exception`` field."""
        formatter = JsonFormatter()
        parsed = json.loads(formatter.format(log_record))
        assert "exception" not in parsed

    def test_exception_field_present_when_exc_info_set(self) -> None:
        """Records carrying exc_info must include a formatted traceback."""
        formatter = JsonFormatter()
        try:
            raise ValueError("simulated batch failure")
        except ValueError:
            exc_info = sys.exc_info()

        record = logging.LogRecord(
            name="src.batch.jobs.posttran_job",
            level=logging.ERROR,
            pathname="/opt/glue/posttran_job.py",
            lineno=200,
            msg="Unexpected error",
            args=None,
            exc_info=exc_info,
        )
        record.module = "posttran_job"
        record.funcName = "post_transaction"

        parsed = json.loads(formatter.format(record))
        assert "exception" in parsed
        # The traceback string must mention the exception class and message.
        assert "ValueError" in parsed["exception"]
        assert "simulated batch failure" in parsed["exception"]
        # The level field should be ERROR — exception logging typically
        # uses logger.exception which promotes to ERROR automatically.
        assert parsed["level"] == "ERROR"

    def test_no_exception_field_when_exc_info_tuple_is_all_none(self) -> None:
        """Records with ``exc_info=(None, None, None)`` skip the exception field.

        Some third-party logging adapters populate ``exc_info`` with a
        triple of ``None`` values when there is no active exception.
        The formatter guards against this by checking
        ``record.exc_info[0] is not None``.
        """
        formatter = JsonFormatter()
        record = logging.LogRecord(
            name="app",
            level=logging.WARNING,
            pathname="/app.py",
            lineno=1,
            msg="warn",
            args=None,
            exc_info=(None, None, None),
        )
        parsed = json.loads(formatter.format(record))
        assert "exception" not in parsed

    def test_non_json_native_values_coerced_via_default_str(self) -> None:
        """Non-JSON-native values in message args must not abort formatting.

        The formatter passes ``default=str`` to :func:`json.dumps` so
        that values such as :class:`~decimal.Decimal` (ubiquitous in
        CardDemo's monetary fields) or :class:`~datetime.datetime`
        coerce to their string form rather than raising ``TypeError``
        and losing the entire log event.
        """
        from decimal import Decimal

        formatter = JsonFormatter()
        # Construct a record where the interpolated message is a
        # Decimal — this exercises ``default=str`` via the ``message``
        # field (Decimal str is already JSON-serializable via str(), but
        # we also verify no exception is raised).
        record = logging.LogRecord(
            name="app",
            level=logging.INFO,
            pathname="/app.py",
            lineno=1,
            msg="amount=%s",
            args=(Decimal("1234.56"),),
            exc_info=None,
        )
        parsed = json.loads(formatter.format(record))
        assert parsed["message"] == "amount=1234.56"

    @pytest.mark.parametrize(
        "level_constant,level_name",
        [
            (logging.DEBUG, "DEBUG"),
            (logging.INFO, "INFO"),
            (logging.WARNING, "WARNING"),
            (logging.ERROR, "ERROR"),
            (logging.CRITICAL, "CRITICAL"),
        ],
    )
    def test_level_field_reflects_record_level(self, level_constant: int, level_name: str) -> None:
        """Every Python log level must map to the correct ``level`` field."""
        formatter = JsonFormatter()
        record = logging.LogRecord(
            name="app",
            level=level_constant,
            pathname="/app.py",
            lineno=1,
            msg="m",
            args=None,
            exc_info=None,
        )
        parsed = json.loads(formatter.format(record))
        assert parsed["level"] == level_name

    def test_formatter_is_stateless_across_records(self) -> None:
        """One formatter instance must format many records correctly.

        Regression guard: if a future refactor introduces per-instance
        mutable state (e.g., caching the last formatTime result), two
        records with different levels could share output incorrectly.
        """
        formatter = JsonFormatter()
        r1 = logging.LogRecord("app", logging.INFO, "/a.py", 1, "m1", None, None)
        r2 = logging.LogRecord("app", logging.ERROR, "/a.py", 2, "m2", None, None)
        p1 = json.loads(formatter.format(r1))
        p2 = json.loads(formatter.format(r2))
        assert p1["level"] == "INFO"
        assert p2["level"] == "ERROR"
        assert p1["message"] == "m1"
        assert p2["message"] == "m2"

    def test_formatter_is_a_logging_formatter_subclass(self) -> None:
        """:class:`JsonFormatter` must inherit from :class:`logging.Formatter`.

        This ensures drop-in compatibility with
        :meth:`logging.StreamHandler.setFormatter` and third-party
        logging frameworks that check ``isinstance(h.formatter,
        logging.Formatter)``.
        """
        formatter = JsonFormatter()
        assert isinstance(formatter, logging.Formatter)


# ============================================================================
# Phase 2: _setup_logging tests — root logger configuration with JSON handler.
# ============================================================================


class TestSetupLogging:
    """Behavior of :func:`_setup_logging` (root logger configuration)."""

    def test_installs_single_handler(self, clean_root_logger: Any) -> None:
        """After setup, the root logger must have exactly one handler."""
        # Pre-populate with noise to confirm removal.
        clean_root_logger.addHandler(logging.NullHandler())
        clean_root_logger.addHandler(logging.NullHandler())
        _setup_logging("INFO")
        assert len(clean_root_logger.handlers) == 1

    def test_installed_handler_is_stream_handler_on_stdout(self, clean_root_logger: Any) -> None:
        """The handler must be a :class:`StreamHandler` writing to stdout."""
        _setup_logging("INFO")
        handler = clean_root_logger.handlers[0]
        assert isinstance(handler, logging.StreamHandler)
        # CloudWatch's Glue integration captures stdout by convention.
        assert handler.stream is sys.stdout

    def test_installed_handler_uses_json_formatter(self, clean_root_logger: Any) -> None:
        """The handler's formatter must be a :class:`JsonFormatter`."""
        _setup_logging("INFO")
        handler = clean_root_logger.handlers[0]
        assert isinstance(handler.formatter, JsonFormatter)

    @pytest.mark.parametrize(
        "level_input,expected_level",
        [
            ("DEBUG", logging.DEBUG),
            ("INFO", logging.INFO),
            ("WARNING", logging.WARNING),
            ("ERROR", logging.ERROR),
            ("CRITICAL", logging.CRITICAL),
        ],
    )
    def test_log_level_set_correctly(
        self,
        clean_root_logger: Any,
        level_input: str,
        expected_level: int,
    ) -> None:
        """Each standard log level string must configure the matching level."""
        _setup_logging(level_input)
        assert clean_root_logger.level == expected_level

    def test_log_level_is_case_insensitive(self, clean_root_logger: Any) -> None:
        """Lowercase level strings must be accepted (case-insensitive)."""
        _setup_logging("debug")
        assert clean_root_logger.level == logging.DEBUG
        _setup_logging("warning")
        assert clean_root_logger.level == logging.WARNING

    def test_log_level_is_mixed_case_insensitive(self, clean_root_logger: Any) -> None:
        """MixedCase strings (e.g., from env var typos) must also work."""
        _setup_logging("Info")
        assert clean_root_logger.level == logging.INFO

    def test_unknown_log_level_falls_back_to_info(self, clean_root_logger: Any) -> None:
        """Unrecognized level strings must fall back to INFO silently."""
        _setup_logging("NONSENSE_LEVEL")
        # ``getattr(logging, "NONSENSE_LEVEL", logging.INFO)`` returns
        # the INFO fallback so logging is never disabled by a typo.
        assert clean_root_logger.level == logging.INFO

    def test_default_log_level_is_info(self, clean_root_logger: Any) -> None:
        """Calling :func:`_setup_logging` with no arg must set INFO."""
        _setup_logging()
        assert clean_root_logger.level == logging.INFO

    def test_idempotent_across_repeated_calls(self, clean_root_logger: Any) -> None:
        """Calling :func:`_setup_logging` N times must leave N=1 handlers."""
        _setup_logging("INFO")
        _setup_logging("INFO")
        _setup_logging("INFO")
        assert len(clean_root_logger.handlers) == 1, (
            "Repeated _setup_logging calls must not accumulate handlers — "
            "otherwise nested pytest fixtures would multiply log output "
            "by the fixture count."
        )

    def test_idempotent_updates_level_on_subsequent_calls(self, clean_root_logger: Any) -> None:
        """A second call with a new level must update the root logger level."""
        _setup_logging("INFO")
        _setup_logging("DEBUG")
        assert clean_root_logger.level == logging.DEBUG

    def test_existing_handlers_are_removed(self, clean_root_logger: Any) -> None:
        """Pre-existing handlers must be removed (test uses sentinel types)."""
        # Install handlers of a distinctive subclass to detect them.
        pre_handler_a = logging.NullHandler()
        pre_handler_b = logging.NullHandler()
        clean_root_logger.addHandler(pre_handler_a)
        clean_root_logger.addHandler(pre_handler_b)

        _setup_logging("INFO")

        # Neither of the pre-test handlers should remain.
        assert pre_handler_a not in clean_root_logger.handlers
        assert pre_handler_b not in clean_root_logger.handlers
        # Exactly one (new) handler installed.
        assert len(clean_root_logger.handlers) == 1


# ============================================================================
# Phase 3: init_glue — production (AWS Glue runtime) path tests.
# Exercise the ``_GLUE_AVAILABLE is True`` branch with mocked awsglue names.
# ============================================================================


class TestInitGlueProductionPath:
    """Behavior of :func:`init_glue` when the AWS Glue runtime is present."""

    def test_returns_4_tuple(self, patched_glue_namespace: dict[str, Any]) -> None:
        """:func:`init_glue` must return ``(spark, glue_ctx, job, args)``."""
        spark, ctx, job, args = init_glue(
            job_name="carddemo-posttran",
            args=["script.py", "--JOB_NAME", "carddemo-posttran"],
        )
        assert spark is patched_glue_namespace["spark_session"]
        assert ctx is patched_glue_namespace["glue_ctx_instance"]
        assert job is patched_glue_namespace["job_instance"]
        assert args == {"JOB_NAME": "carddemo-posttran"}

    def test_creates_sparkcontext_then_gluecontext(self, patched_glue_namespace: dict[str, Any]) -> None:
        """SparkContext must be instantiated then wrapped by GlueContext."""
        init_glue(args=["script.py"])
        patched_glue_namespace["SparkContext"].assert_called_once_with()
        # GlueContext is called with the SparkContext instance.
        sc_instance = patched_glue_namespace["SparkContext"].return_value
        patched_glue_namespace["GlueContext"].assert_called_once_with(sc_instance)

    def test_uses_explicit_job_name_override(self, patched_glue_namespace: dict[str, Any]) -> None:
        """``job_name`` argument must take precedence over resolved ``JOB_NAME``.

        This supports the case where a batch job wants to override the
        JCL-supplied job name for observability (e.g., parallel runs
        tagged with a partition key).
        """
        # The mock returns ``{"JOB_NAME": "carddemo-posttran"}`` but we
        # override with ``"my-custom-name"`` via the ``job_name`` param.
        _, _, _, _ = init_glue(
            job_name="my-custom-name",
            args=["script.py"],
        )
        # Job.init must have been called with the explicit override,
        # not the resolved value from getResolvedOptions.
        job_instance = patched_glue_namespace["job_instance"]
        job_instance.init.assert_called_once_with("my-custom-name", {"JOB_NAME": "carddemo-posttran"})

    def test_uses_resolved_job_name_when_override_is_none(self, patched_glue_namespace: dict[str, Any]) -> None:
        """With ``job_name=None``, ``resolved_args['JOB_NAME']`` is used."""
        init_glue(args=["script.py"])
        job_instance = patched_glue_namespace["job_instance"]
        # ``effective_job_name`` in init_glue falls back to
        # ``resolved_args.get("JOB_NAME", "carddemo-batch")``, which is
        # the mocked value ``"carddemo-posttran"``.
        job_instance.init.assert_called_once_with("carddemo-posttran", {"JOB_NAME": "carddemo-posttran"})

    def test_falls_back_to_carddemo_batch_when_resolved_args_missing_key(
        self, patched_glue_namespace: dict[str, Any]
    ) -> None:
        """If resolved_args has no ``JOB_NAME`` key, fallback to ``carddemo-batch``.

        Edge case: some Glue invocations may not carry JOB_NAME (e.g.,
        a broken Step Functions payload). The factory's ``.get(key, default)``
        guards against this so job.init still succeeds with a known name.
        """
        # Override the mock to return an empty dict.
        patched_glue_namespace["getResolvedOptions"].return_value = {}
        _, _, _, args = init_glue(args=["script.py"])
        assert args == {}
        patched_glue_namespace["job_instance"].init.assert_called_once_with("carddemo-batch", {})

    def test_spark_configuration_is_applied(self, patched_glue_namespace: dict[str, Any]) -> None:
        """SQL shuffle partitions and adaptive-execution must be configured."""
        init_glue(args=["script.py"])
        spark = patched_glue_namespace["spark_session"]
        # Both tuning parameters set via ``conf.set``.
        spark.conf.set.assert_any_call("spark.sql.shuffle.partitions", "10")
        spark.conf.set.assert_any_call("spark.sql.adaptive.enabled", "true")

    def test_get_resolved_options_receives_job_name_key(self, patched_glue_namespace: dict[str, Any]) -> None:
        """``getResolvedOptions`` must be called with ``["JOB_NAME"]``."""
        init_glue(args=["script.py", "--JOB_NAME", "carddemo-posttran"])
        patched_glue_namespace["getResolvedOptions"].assert_called_once_with(
            ["script.py", "--JOB_NAME", "carddemo-posttran"],
            ["JOB_NAME"],
        )

    def test_falls_back_to_sys_argv_when_args_is_none(self, patched_glue_namespace: dict[str, Any]) -> None:
        """When ``args=None``, :data:`sys.argv` is passed to getResolvedOptions."""
        # We patch sys.argv to a deterministic value for this test.
        with patch.object(sys, "argv", ["glue_runner.py", "--JOB_NAME", "x"]):
            init_glue(args=None)
        call_args = patched_glue_namespace["getResolvedOptions"].call_args
        assert call_args.args[0] == ["glue_runner.py", "--JOB_NAME", "x"]

    def test_job_init_called_with_resolved_args(self, patched_glue_namespace: dict[str, Any]) -> None:
        """:meth:`awsglue.job.Job.init` is invoked with name + resolved dict."""
        init_glue(args=["script.py"])
        job_instance = patched_glue_namespace["job_instance"]
        assert job_instance.init.call_count == 1

    def test_job_is_constructed_with_glue_context(self, patched_glue_namespace: dict[str, Any]) -> None:
        """The ``Job`` class is called with the GlueContext instance."""
        init_glue(args=["script.py"])
        patched_glue_namespace["Job"].assert_called_once_with(patched_glue_namespace["glue_ctx_instance"])


# ============================================================================
# Phase 4: init_glue — local development path tests.
# Exercise the ``_GLUE_AVAILABLE is False`` branch with a mocked
# SparkSession.builder chain.
# ============================================================================


class TestInitGlueLocalPath:
    """Behavior of :func:`init_glue` when the AWS Glue runtime is absent."""

    def test_returns_4_tuple_with_none_glue_context_and_job(self, patched_local_spark: dict[str, Any]) -> None:
        """Local mode must return ``(spark, None, None, args)``."""
        # Default: _GLUE_AVAILABLE is already False in this test env.
        assert gc_mod._GLUE_AVAILABLE is False
        spark, glue_ctx, job, args = init_glue(job_name="my-job")
        assert spark is patched_local_spark["session"]
        assert glue_ctx is None
        assert job is None
        assert args == {"JOB_NAME": "my-job"}

    def test_explicit_job_name_override(self, patched_local_spark: dict[str, Any]) -> None:
        """``job_name`` override must be used as effective name."""
        _, _, _, args = init_glue(job_name="custom-local-job")
        assert args["JOB_NAME"] == "custom-local-job"
        # appName called with the override
        patched_local_spark["builder"].appName.assert_called_with("custom-local-job")

    def test_default_job_name_is_carddemo_batch_local(self, patched_local_spark: dict[str, Any]) -> None:
        """With no ``job_name``, the default must be ``carddemo-batch-local``."""
        _, _, _, args = init_glue()
        assert args["JOB_NAME"] == "carddemo-batch-local"
        patched_local_spark["builder"].appName.assert_called_with("carddemo-batch-local")

    def test_spark_config_includes_shuffle_partitions(self, patched_local_spark: dict[str, Any]) -> None:
        """``spark.sql.shuffle.partitions=10`` is applied via builder.config."""
        init_glue()
        patched_local_spark["builder"].config.assert_any_call("spark.sql.shuffle.partitions", "10")

    def test_spark_config_includes_adaptive_execution(self, patched_local_spark: dict[str, Any]) -> None:
        """``spark.sql.adaptive.enabled=true`` is applied via builder.config."""
        init_glue()
        patched_local_spark["builder"].config.assert_any_call("spark.sql.adaptive.enabled", "true")

    def test_spark_config_includes_driver_class_path(self, patched_local_spark: dict[str, Any]) -> None:
        """``spark.driver.extraClassPath`` is set for JDBC driver discovery."""
        init_glue()
        patched_local_spark["builder"].config.assert_any_call("spark.driver.extraClassPath", "/opt/spark/jars/*")

    def test_get_or_create_is_called_once(self, patched_local_spark: dict[str, Any]) -> None:
        """``getOrCreate`` must be invoked exactly once to materialize the session."""
        init_glue()
        assert patched_local_spark["builder"].getOrCreate.call_count == 1

    def test_builder_chain_ends_at_get_or_create(self, patched_local_spark: dict[str, Any]) -> None:
        """The chain of appName → config* → getOrCreate returns the session."""
        spark, _, _, _ = init_glue(job_name="my-job")
        # The session returned must come from the builder's getOrCreate.
        assert spark is patched_local_spark["builder"].getOrCreate.return_value

    def test_resolved_args_contains_only_job_name(self, patched_local_spark: dict[str, Any]) -> None:
        """Local-mode resolved_args must be ``{"JOB_NAME": effective_name}``."""
        _, _, _, args = init_glue(job_name="posttran-local")
        assert args == {"JOB_NAME": "posttran-local"}

    def test_args_parameter_ignored_in_local_mode(self, patched_local_spark: dict[str, Any]) -> None:
        """``args`` argument is NOT parsed in local mode (no getResolvedOptions).

        In the Glue runtime ``getResolvedOptions`` parses argv; locally,
        resolved_args is constructed directly from ``effective_job_name``.
        Passing a long argv list should have no effect on resolved_args.
        """
        _, _, _, args = init_glue(
            job_name="local-test",
            args=["script.py", "--POSTING_DATE", "2022-07-18", "--DEBUG"],
        )
        assert args == {"JOB_NAME": "local-test"}


# ============================================================================
# Phase 5: commit_job — the JCL MAXCC=0 equivalent.
# ============================================================================


class TestCommitJob:
    """Behavior of :func:`commit_job`."""

    def test_commit_with_none_is_noop(self) -> None:
        """``commit_job(None)`` must not raise (local-dev fallback)."""
        # No AssertionError means the call completed normally.
        commit_job(None)

    def test_commit_with_none_logs_skip_message(self, caplog: pytest.LogCaptureFixture, clean_root_logger: Any) -> None:
        """Local-mode commit must emit an informational skip message."""
        # caplog needs the logger to propagate; reset root to a simple
        # handler so the capture fixture can observe records.
        caplog.set_level(logging.INFO, logger="src.batch.common.glue_context")
        commit_job(None)
        assert any("Job commit skipped (local mode)" in record.message for record in caplog.records)

    def test_commit_with_job_calls_job_commit(self) -> None:
        """A non-None job must have its ``.commit()`` method invoked."""
        mock_job = MagicMock()
        commit_job(mock_job)
        mock_job.commit.assert_called_once_with()

    def test_commit_with_job_logs_success_message(
        self, caplog: pytest.LogCaptureFixture, clean_root_logger: Any
    ) -> None:
        """Successful commit must emit an informational success message."""
        caplog.set_level(logging.INFO, logger="src.batch.common.glue_context")
        mock_job = MagicMock()
        commit_job(mock_job)
        assert any("Glue job committed successfully" in record.message for record in caplog.records)

    def test_commit_with_falsy_nonzero_value_still_treated_as_job(self) -> None:
        """The None check uses ``is not None`` — other falsy values count as jobs.

        This regression guard ensures that an AWS Glue Job instance
        that happens to evaluate as falsy under ``bool(job)`` (e.g., a
        future version defining ``__bool__`` based on job state) is
        still committed. The factory uses ``is not None``, not truthy
        evaluation.
        """

        class PseudoJob:
            """Test stand-in that evaluates to False via __bool__."""

            def __init__(self) -> None:
                self.commit_called = False

            def __bool__(self) -> bool:
                return False

            def commit(self) -> None:
                self.commit_called = True

        job = PseudoJob()
        assert bool(job) is False  # Precondition
        commit_job(job)
        assert job.commit_called is True, (
            "commit_job uses ``is not None``, not truthiness — a falsy job "
            "object with a valid commit() method must still be committed."
        )


# ============================================================================
# Phase 6: init_glue logging integration — verify _setup_logging is invoked.
# ============================================================================


class TestInitGlueLoggingIntegration:
    """Verify :func:`init_glue` configures structured JSON logging on startup.

    The factory contract (AAP §0.7.2) requires that every batch job
    emits CloudWatch-compatible JSON logs from the first line of
    output. This is achieved by calling :func:`_setup_logging` inside
    :func:`init_glue` before any other logger.info call.
    """

    def test_init_glue_local_invokes_setup_logging(
        self,
        patched_local_spark: dict[str, Any],
        clean_root_logger: Any,
    ) -> None:
        """After init_glue in local mode, root has a JSON-formatted handler."""
        # Install a non-JSON handler first to verify replacement.
        clean_root_logger.addHandler(logging.NullHandler())
        init_glue(job_name="log-test")
        assert len(clean_root_logger.handlers) == 1
        assert isinstance(clean_root_logger.handlers[0].formatter, JsonFormatter)

    def test_init_glue_production_invokes_setup_logging(
        self,
        patched_glue_namespace: dict[str, Any],
        clean_root_logger: Any,
    ) -> None:
        """After init_glue in prod mode, root has a JSON-formatted handler."""
        clean_root_logger.addHandler(logging.NullHandler())
        init_glue(args=["script.py"])
        assert len(clean_root_logger.handlers) == 1
        assert isinstance(clean_root_logger.handlers[0].formatter, JsonFormatter)


# ============================================================================
# Phase 7: Module public API surface guard.
# ============================================================================


class TestModuleSurface:
    """Regression guard for :attr:`__all__` — detect accidental API drift."""

    def test_all_contains_exactly_three_names(self) -> None:
        """:attr:`__all__` must list exactly ``init_glue``, ``commit_job``, ``JsonFormatter``."""
        assert gc_mod.__all__ == ["init_glue", "commit_job", "JsonFormatter"]

    def test_all_names_are_defined(self) -> None:
        """Every name in :attr:`__all__` must resolve to a live attribute."""
        for name in gc_mod.__all__:
            assert hasattr(gc_mod, name), f"{name!r} is listed in __all__ but not defined on the module"

    def test_all_is_list_of_strings(self) -> None:
        """:attr:`__all__` must be a list of strings (PEP 8 convention)."""
        assert isinstance(gc_mod.__all__, list)
        assert all(isinstance(n, str) for n in gc_mod.__all__)

    def test_public_names_are_callable_or_class(self) -> None:
        """Each exported name must be a function or class."""
        import inspect

        for name in gc_mod.__all__:
            obj = getattr(gc_mod, name)
            assert inspect.isfunction(obj) or inspect.isclass(obj), (
                f"{name!r} must be a function or class; got {type(obj).__name__}"
            )

    def test_private_names_not_in_all(self) -> None:
        """Private helpers (``_setup_logging``, ``_GLUE_AVAILABLE``) stay hidden."""
        assert "_setup_logging" not in gc_mod.__all__
        assert "_GLUE_AVAILABLE" not in gc_mod.__all__
