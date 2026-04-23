"""Unit tests for ``src.batch.common.db_connector``.

# =======================================================================
# Licensed Materials - Property of AWS
# (C) Copyright IBM Corp. 2022 All Rights Reserved.
# Apache License Version 2.0
# =======================================================================

Source Heritage
---------------
This test module verifies the PostgreSQL JDBC connection factory that
replaces VSAM dataset access (``DISP=SHR`` reads,
``DISP=(NEW,CATLG,DELETE)`` writes, and REWRITE-in-place updates) across
all batch pipeline stages. The tests exercise the public API surface
listed in :data:`src.batch.common.db_connector.__all__` against mocked
PySpark DataFrame and AWS Secrets Manager interfaces so that the unit
tests can run without an actual Aurora PostgreSQL instance, without a
live Spark cluster, and without AWS network access — matching the
``moto``-based isolation pattern established by the rest of the batch
test suite (see ``tests/unit/test_batch/common/test_s3_utils.py``).

The JCL/VSAM operations these tests protect (one-to-one mapping):

* ``POSTTRAN.jcl`` — VSAM DD statements for TRANFILE (VSAM KSDS),
  DALYTRAN (PS), XREFFILE (VSAM KSDS with AIX), ACCTFILE
  (VSAM KSDS), TCATBALF (VSAM KSDS with composite key). Replaced by
  five PySpark JDBC reads/writes via :func:`read_table`,
  :func:`write_table`, :func:`write_table_idempotent`.
* ``INTCALC.jcl`` — VSAM DD statements for TCATBALF, XREFFILE,
  ACCTFILE, DISCGRP, TRANSACT. Replaced by five PySpark JDBC
  operations against the corresponding PostgreSQL tables.
* ``CREASTMT.JCL`` — VSAM DD statements for TRNXFILE, XREFFILE,
  ACCTFILE, CUSTFILE. Replaced by four PySpark JDBC reads.
* ``TRANREPT.jcl`` — VSAM DD statements for TRANFILE, CARDXREF,
  TRANTYPE, TRANCATG. Replaced by four PySpark JDBC reads.
* ``ACCTFILE.jcl`` / ``TRANBKP.jcl`` — VSAM DEFINE CLUSTER / REPRO
  operations for ACCTDATA and TRANSACT. Replaced by Flyway schema
  migrations and PySpark DataFrame write operations.

COBOL -> Python Verification Surface
------------------------------------
* ``VSAM_TABLE_MAP`` - the authoritative 11-entry dictionary that
  translates every VSAM short-name used across the batch JCLs into its
  corresponding PostgreSQL table name. See ``db/migrations/V1__schema.sql``
  for the target tables and ``db_connector.py`` lines 173-208 for the
  canonical JCL DSN-to-table mapping.
* ``get_jdbc_url`` — three-pathway URL resolver (explicit args -> Secrets
  Manager -> Settings fallback). Replaces
  ``DSN=AWS.M2.CARDDEMO.<name>.VSAM.KSDS`` dataset references.
* ``get_connection_options`` — returns the full ``.options(**kwargs)``
  dict that PySpark's JDBC connector requires (url, driver, user,
  password, dbtable).
* ``get_table_name`` — case-insensitive VSAM short-name to table-name
  translator; raises :exc:`ValueError` for unknown VSAM names.
* ``read_table`` — lazy JDBC DataFrame read (replaces COBOL
  ``READ DD-NAME INTO ...`` looping).
* ``write_table`` — eager JDBC DataFrame write with ``truncate="true"``
  (preserves schema on overwrite; replaces ``WRITE``/``REWRITE``).
* ``write_table_idempotent`` — left-anti-join idempotent append with
  primary-key deduplication (resolves QA Checkpoint 5 Issue 22
  POSTTRAN non-idempotent-retry failure).

References
----------
AAP §0.4.1 — Refactored Structure Planning
AAP §0.4.4 — Key Architectural Decisions (Batch Layer — AWS Glue)
AAP §0.5.1 — File-by-File Transformation Plan
AAP §0.6.2 — AWS Service Dependencies (Aurora PostgreSQL,
             Secrets Manager)
AAP §0.7.1 — Preserve all existing functionality exactly as-is
AAP §0.7.2 — Security Requirements (IAM roles, Secrets Manager,
             no hardcoded credentials); Financial Precision
             (Python Decimal, no floating-point)
QA Checkpoint 7 — Remediation (this file raises coverage of
                  ``src/batch/common/db_connector.py`` from 54% to
                  target >=90%)
"""

from __future__ import annotations

# =======================================================================
# Standard-library imports.
#
# ``logging`` is imported only for the caplog verification of the
# Secrets-Manager-fallback WARNING log emitted by get_jdbc_url() and
# get_connection_options() when Secrets Manager raises any exception
# (pathway 3 of the three-pathway resolver described in the module
# docstring).
# =======================================================================
import logging
from unittest.mock import MagicMock, patch

# =======================================================================
# Third-party imports.
#
# ``pytest`` for test discovery, parametrization, fixtures, caplog
# integration, and the ``@pytest.mark.unit`` marker that categorizes
# these tests for the coverage-only unit test run.
# =======================================================================
import pytest

# =======================================================================
# System-under-test import.
#
# Imports the module itself (not individual names) so that tests can:
# 1. Reference the private import target ``get_database_credentials``
#    via ``patch("src.shared.config.aws_config.get_database_credentials",
#    ...)``. The lazy ``from src.shared.config.aws_config import ...``
#    inside ``get_jdbc_url`` and ``get_connection_options`` re-binds
#    the name on each call, so the patch must target the source module
#    not the consumer.
# 2. Use ``patch.object(dc, "read_table", ...)`` for the sibling-function
#    substitution in ``write_table_idempotent`` tests.
# =======================================================================
import src.batch.common.db_connector as dc

# =======================================================================
# Global pytest marker — every test in this file is a unit test per the
# project's pytest.ini markers (``unit``, ``integration``, ``e2e``).
# =======================================================================
pytestmark = pytest.mark.unit


# =======================================================================
# Test fixtures.
#
# These fixtures construct the minimum amount of mock scaffolding needed
# to exercise the JDBC path without requiring an actual Spark session,
# Aurora instance, or AWS Secrets Manager endpoint.
# =======================================================================


@pytest.fixture
def mock_credentials() -> dict[str, str]:
    """Canonical Secrets Manager credential dict.

    Matches the exact shape returned by
    :func:`src.shared.config.aws_config.get_database_credentials` —
    a ``dict[str, str]`` with keys ``username``, ``password``, ``host``,
    ``port``, ``dbname``. The values are deliberately distinct strings
    so assertion failures are self-identifying in the test output.
    """
    return {
        "username": "secrets_user",
        "password": "secrets_pass",
        "host": "aurora.cluster-xyz.rds.amazonaws.com",
        "port": "5432",
        "dbname": "carddemo",
    }


@pytest.fixture
def mock_spark_session_for_read() -> MagicMock:
    """Spark session mock configured to return a DataFrame from a JDBC
    read.

    Mirrors the real PySpark call chain exercised by
    :func:`src.batch.common.db_connector.read_table`::

        spark_session.read.format("jdbc").options(**options).load()

    Each intermediate call returns a distinct :class:`MagicMock` so
    tests can assert on individual builder steps (``format("jdbc")``
    vs. ``options(**opts)`` vs. ``load()``) without interference.
    """
    session = MagicMock()
    reader = MagicMock()
    options_result = MagicMock()
    loaded_df = MagicMock(name="loaded_dataframe")

    session.read.format.return_value = reader
    reader.options.return_value = options_result
    options_result.load.return_value = loaded_df

    return session


@pytest.fixture
def mock_dataframe_for_write() -> MagicMock:
    """DataFrame mock configured for the ``write_table`` call chain.

    Mirrors the real PySpark write chain::

        dataframe.write.format("jdbc")
            .options(**options)
            .option("dbtable", table_name)
            .option("truncate", "true")
            .mode(mode)
            .save()

    Each intermediate returns a distinct mock so tests can assert on
    individual builder calls. Call order is verifiable via the
    ``call_args_list`` of each mock.
    """
    dataframe = MagicMock()
    writer = MagicMock()
    w_options = MagicMock()
    w_dbtable = MagicMock()
    w_truncate = MagicMock()
    w_mode = MagicMock()

    dataframe.write.format.return_value = writer
    writer.options.return_value = w_options
    w_options.option.return_value = w_dbtable
    w_dbtable.option.return_value = w_truncate
    w_truncate.mode.return_value = w_mode

    # Expose the chain links on the mock so tests can assert without
    # needing to re-traverse the chain. This keeps assertions concise.
    dataframe._chain_writer = writer
    dataframe._chain_w_options = w_options
    dataframe._chain_w_dbtable = w_dbtable
    dataframe._chain_w_truncate = w_truncate
    dataframe._chain_w_mode = w_mode

    return dataframe


# =======================================================================
# Phase 1 — VSAM_TABLE_MAP constant surface tests.
# =======================================================================


class TestVsamTableMap:
    """Validate the 11-entry VSAM short-name -> PostgreSQL table-name map.

    Preserving this mapping exactly per AAP §0.7.1 is critical: every
    batch Glue job references it via :func:`get_table_name` to translate
    the VSAM DD-NAME referenced in the originating JCL into the Aurora
    PostgreSQL table that replaces the VSAM cluster.
    """

    def test_vsam_table_map_is_dict_of_str_to_str(self) -> None:
        """The map must be a concrete ``dict[str, str]``.

        Guards against a future refactor converting the map to an enum
        or a frozen dataclass, which would break every existing caller
        site that uses ``in`` / ``[]`` / ``.keys()`` lookups.
        """
        assert isinstance(dc.VSAM_TABLE_MAP, dict)
        for key, value in dc.VSAM_TABLE_MAP.items():
            assert isinstance(key, str), f"key {key!r} is not str"
            assert isinstance(value, str), f"value {value!r} is not str"

    def test_vsam_table_map_has_exactly_eleven_entries(self) -> None:
        """Exactly 11 VSAM datasets are migrated to PostgreSQL tables.

        Per AAP §0.2.2/§0.5.1 the migration inventory is:
        ACCTDATA, CARDDATA, CUSTDATA, CARDXREF, TRANSACT, TCATBALF,
        DISCGRP, TRANTYPE, TRANCATG, DALYTRAN, USRSEC -> 11 total.
        """
        assert len(dc.VSAM_TABLE_MAP) == 11

    @pytest.mark.parametrize(
        ("vsam_name", "expected_table"),
        [
            # JCL: //ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
            ("ACCTDATA", "accounts"),
            # JCL: //CARDFILE DD DSN=AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS
            ("CARDDATA", "cards"),
            # JCL: //CUSTFILE DD DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
            ("CUSTDATA", "customers"),
            # JCL: //XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
            ("CARDXREF", "card_cross_references"),
            # JCL: //TRANFILE DD DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
            ("TRANSACT", "transactions"),
            # JCL: //TCATBALF DD DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
            ("TCATBALF", "transaction_category_balances"),
            # JCL: //DISCGRP  DD DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
            ("DISCGRP", "disclosure_groups"),
            # JCL: //TRANTYPE DD DSN=AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
            ("TRANTYPE", "transaction_types"),
            # JCL: //TRANCATG DD DSN=AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
            ("TRANCATG", "transaction_categories"),
            # JCL: //DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS
            ("DALYTRAN", "daily_transactions"),
            # CICS sign-on USRSEC VSAM (DUSRSECJ.jcl provisions)
            ("USRSEC", "user_security"),
        ],
    )
    def test_vsam_table_map_canonical_entries(self, vsam_name: str, expected_table: str) -> None:
        """Every canonical JCL DD-NAME maps to its schema-defined table.

        Parametrized over all 11 entries so a single regression yields a
        single identifiable failure rather than a bulk-dict-compare
        error that obscures which mapping is wrong.
        """
        assert dc.VSAM_TABLE_MAP[vsam_name] == expected_table

    def test_vsam_table_map_exact_equality(self) -> None:
        """Full dict equality guards against silent additions.

        If a future change adds (or deletes) a VSAM mapping, the
        parametrized ``test_vsam_table_map_canonical_entries`` would
        still pass while this test would fail and surface the drift.
        """
        assert dc.VSAM_TABLE_MAP == {
            "ACCTDATA": "accounts",
            "CARDDATA": "cards",
            "CUSTDATA": "customers",
            "CARDXREF": "card_cross_references",
            "TRANSACT": "transactions",
            "TCATBALF": "transaction_category_balances",
            "DISCGRP": "disclosure_groups",
            "TRANTYPE": "transaction_types",
            "TRANCATG": "transaction_categories",
            "DALYTRAN": "daily_transactions",
            "USRSEC": "user_security",
        }

    def test_vsam_table_map_values_are_lowercase_snake_case(self) -> None:
        """PostgreSQL target table names follow snake_case convention.

        The target schema in ``db/migrations/V1__schema.sql`` uses
        snake_case plural table names. A regression that introduced a
        mixed-case or CamelCase value would silently break every
        consumer that relies on the PostgreSQL unquoted identifier
        folding rules (all lowercase).
        """
        for value in dc.VSAM_TABLE_MAP.values():
            assert value == value.lower(), f"{value!r} is not all-lowercase"

    def test_vsam_table_map_keys_are_uppercase(self) -> None:
        """VSAM short-names are uppercase (8-character DSN qualifiers)."""
        for key in dc.VSAM_TABLE_MAP.keys():
            assert key == key.upper(), f"{key!r} is not all-uppercase"


# =======================================================================
# Phase 2 — get_jdbc_url tests (three resolution pathways).
# =======================================================================


class TestGetJdbcUrlExplicitArgs:
    """Pathway 1 — all three arguments explicit.

    No Secrets Manager lookup, no Settings import. This is the
    zero-dependency invocation useful for unit tests and ad-hoc
    diagnostic scripts that target a Testcontainers PostgreSQL
    instance.
    """

    def test_returns_jdbc_url_from_explicit_args(self) -> None:
        """Explicit host/port/dbname form the JDBC URL directly."""
        url = dc.get_jdbc_url(host="localhost", port="5432", dbname="carddemo")
        assert url == "jdbc:postgresql://localhost:5432/carddemo"

    def test_explicit_args_with_aurora_rds_endpoint(self) -> None:
        """Real-world Aurora endpoint is rendered verbatim.

        The function does no sanitisation or validation — the caller
        is responsible for supplying a well-formed Aurora cluster DNS.
        """
        url = dc.get_jdbc_url(
            host="carddemo-aurora.cluster-xyz.us-east-1.rds.amazonaws.com",
            port="5432",
            dbname="carddemo",
        )
        assert url == ("jdbc:postgresql://carddemo-aurora.cluster-xyz.us-east-1.rds.amazonaws.com:5432/carddemo")

    def test_explicit_args_all_three_required_for_pathway_one(self, mock_credentials: dict[str, str]) -> None:
        """Missing any one argument falls through to Secrets Manager.

        The ``if host and port and dbname:`` guard requires all three
        to be truthy; any ``None`` triggers the Secrets Manager
        pathway. This test verifies the boundary by providing only
        ``host`` and confirming pathway 2 credentials are used.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            url = dc.get_jdbc_url(host="only-host")

        # Pathway 2 used the Secrets Manager host, not "only-host".
        assert "only-host" not in url
        assert mock_credentials["host"] in url

    def test_empty_string_args_trigger_secrets_manager(self, mock_credentials: dict[str, str]) -> None:
        """Empty-string args are falsy and trigger pathway 2.

        This matches Python's truthiness rules — ``if "" and ...``
        is False. The function treats empty strings identically to
        ``None`` to avoid constructing a malformed
        ``jdbc:postgresql://:/`` URL.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            url = dc.get_jdbc_url(host="", port="", dbname="")
        assert url == (
            f"jdbc:postgresql://{mock_credentials['host']}:{mock_credentials['port']}/{mock_credentials['dbname']}"
        )

    def test_explicit_args_logs_info(self, caplog: pytest.LogCaptureFixture) -> None:
        """Successful pathway-1 invocation emits an INFO log."""
        with caplog.at_level(logging.INFO, logger=dc.logger.name):
            dc.get_jdbc_url(host="localhost", port="5432", dbname="carddemo")
        # Message includes the constructed URL parts for traceability.
        assert any("Constructed JDBC URL" in record.message for record in caplog.records)


class TestGetJdbcUrlSecretsManager:
    """Pathway 2 — Secrets Manager credential retrieval.

    Represents the production Glue / ECS deployment path where the IAM
    role grants access to the secret defined by
    :attr:`Settings.DB_SECRET_NAME`.
    """

    def test_returns_url_composed_from_secrets_credentials(self, mock_credentials: dict[str, str]) -> None:
        """The URL is composed from the Secrets Manager dict."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            url = dc.get_jdbc_url()

        expected = (
            f"jdbc:postgresql://{mock_credentials['host']}:{mock_credentials['port']}/{mock_credentials['dbname']}"
        )
        assert url == expected

    def test_secrets_manager_is_called_exactly_once(self, mock_credentials: dict[str, str]) -> None:
        """Each invocation results in one Secrets Manager read.

        Critically — no caching layer should be added without explicit
        design. Callers that want to cache the URL must do so at the
        call site; the factory itself remains stateless.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ) as mock_get:
            dc.get_jdbc_url()
            assert mock_get.call_count == 1

    def test_secrets_manager_logs_info_on_success(
        self,
        mock_credentials: dict[str, str],
        caplog: pytest.LogCaptureFixture,
    ) -> None:
        """Pathway 2 emits an INFO log at the
        ``Constructed JDBC URL from Secrets Manager`` message.
        """
        with (
            patch(
                "src.shared.config.aws_config.get_database_credentials",
                return_value=mock_credentials,
            ),
            caplog.at_level(logging.INFO, logger=dc.logger.name),
        ):
            dc.get_jdbc_url()

        assert any("Constructed JDBC URL from Secrets Manager" in rec.message for rec in caplog.records)


class TestGetJdbcUrlSettingsFallback:
    """Pathway 3 — Secrets Manager failure falls back to local Settings.

    Triggered by ANY exception raised by
    ``get_database_credentials`` — ResourceNotFoundException, IAM
    denial, botocore transport failure, LocalStack unavailable, etc.
    Emits a WARNING log and parses
    :attr:`Settings.DATABASE_URL_SYNC` for the host:port/dbname tuple.
    """

    def test_fallback_on_generic_exception(self) -> None:
        """ANY exception triggers the fallback (wide except clause).

        The ``try: ... except Exception:`` guard in pathway 2 is
        intentionally broad per the module docstring to handle the
        full range of AWS / local-dev failure modes without requiring
        each to be enumerated.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            side_effect=RuntimeError("transport failure"),
        ):
            url = dc.get_jdbc_url()

        # DATABASE_URL_SYNC from conftest.py is
        # postgresql+psycopg2://carddemo:carddemo@localhost:5432/carddemo
        # so the fallback URL must contain host:port/dbname.
        assert url.startswith("jdbc:postgresql://")
        # The conftest test environment points to localhost:5432/carddemo.
        assert "localhost:5432/carddemo" in url or url.endswith("/carddemo")

    def test_fallback_logs_warning(self, caplog: pytest.LogCaptureFixture) -> None:
        """Pathway 3 emits a WARNING log (not ERROR) per module docstring.

        The WARNING level is intentional: pathway 3 is fully valid for
        local docker-compose development. ERROR logs for the actual
        Secrets Manager failure are emitted by ``aws_config`` itself.
        """
        with (
            patch(
                "src.shared.config.aws_config.get_database_credentials",
                side_effect=Exception("boom"),
            ),
            caplog.at_level(logging.WARNING, logger=dc.logger.name),
        ):
            dc.get_jdbc_url()

        # Look for the exact WARNING record.
        warning_records = [rec for rec in caplog.records if rec.levelname == "WARNING"]
        assert any("Failed to retrieve credentials from Secrets Manager" in rec.message for rec in warning_records)
        assert any("falling back to local settings" in rec.message for rec in warning_records)

    def test_fallback_parses_database_url_sync(self) -> None:
        """Parses ``postgresql+psycopg2://user:pass@host:port/db``.

        The parsing is deliberately minimal (``split("@")[-1]``
        semantics). Verifies the parser recovers the correct host:port
        and dbname from the conftest-provided URL.
        """
        from src.shared.config.settings import Settings

        sync_url = Settings().DATABASE_URL_SYNC
        # Extract the expected host:port and dbname from the settings
        # URL using the same split pattern as the fallback.
        host_port_db = sync_url.split("@")[-1]
        expected_host_port, expected_dbname = host_port_db.split("/", 1)

        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            side_effect=Exception("boom"),
        ):
            url = dc.get_jdbc_url()

        assert url == (f"jdbc:postgresql://{expected_host_port}/{expected_dbname}")


# =======================================================================
# Phase 3 — get_connection_options tests.
# =======================================================================


class TestGetConnectionOptionsSecretsManager:
    """Primary pathway — credentials from AWS Secrets Manager."""

    def test_returns_dict_with_required_keys(self, mock_credentials: dict[str, str]) -> None:
        """The returned dict has at minimum url/driver/user/password.

        These are the four JDBC options that the PostgreSQL driver
        (``org.postgresql.Driver``) requires for every connection. The
        ``dbtable`` key is only present when the caller supplies
        ``table_name``.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options("accounts")

        required_keys = {"url", "driver", "user", "password", "dbtable"}
        assert required_keys.issubset(opts.keys())

    def test_driver_is_postgres_jdbc(self, mock_credentials: dict[str, str]) -> None:
        """Driver is always ``org.postgresql.Driver`` (AAP §0.4.4).

        Aurora PostgreSQL and PostgreSQL both use this canonical JDBC
        driver class — bundled with AWS Glue 5.1 / Apache Spark 3.5.6
        per the AAP. Any change here would break every batch job.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options()

        assert opts["driver"] == "org.postgresql.Driver"

    def test_user_and_password_from_secrets_manager(self, mock_credentials: dict[str, str]) -> None:
        """Credentials flow straight through from the secret dict.

        ``creds["username"]`` -> ``opts["user"]`` (note the key rename
        to match PostgreSQL JDBC driver convention).
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options()

        assert opts["user"] == mock_credentials["username"]
        assert opts["password"] == mock_credentials["password"]

    def test_url_composed_from_secrets_manager_creds(self, mock_credentials: dict[str, str]) -> None:
        """The url is ``jdbc:postgresql://{host}:{port}/{dbname}``."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options()

        assert opts["url"] == (
            f"jdbc:postgresql://{mock_credentials['host']}:{mock_credentials['port']}/{mock_credentials['dbname']}"
        )

    def test_with_table_name_adds_dbtable(self, mock_credentials: dict[str, str]) -> None:
        """Passing ``table_name`` adds the ``dbtable`` option."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options("transactions")

        assert opts["dbtable"] == "transactions"

    def test_without_table_name_omits_dbtable(self, mock_credentials: dict[str, str]) -> None:
        """No ``table_name`` -> no ``dbtable`` key in the dict.

        This minimal-default pattern lets the caller set ``dbtable``
        separately via ``.option("dbtable", ...)`` — matching PySpark
        JDBC writer's preferred idiom.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options()

        assert "dbtable" not in opts

    def test_empty_table_name_omits_dbtable(self, mock_credentials: dict[str, str]) -> None:
        """Empty-string ``table_name`` is falsy -> no ``dbtable`` key."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options(table_name="")

        assert "dbtable" not in opts

    def test_all_values_are_str(self, mock_credentials: dict[str, str]) -> None:
        """PySpark JDBC requires all options to be str-typed.

        This is a type-safety guard: if any value is coerced to int
        (e.g., port as int) PySpark raises a TypeError at job startup.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            opts = dc.get_connection_options("accounts")

        for key, value in opts.items():
            assert isinstance(value, str), f"{key}={value!r} is not str (type={type(value).__name__})"


class TestGetConnectionOptionsSettingsFallback:
    """Fallback pathway — Secrets Manager fails, parse DATABASE_URL_SYNC."""

    def test_fallback_parses_settings_url_for_user_password(self) -> None:
        """Fallback parser extracts user and password from URL.

        DATABASE_URL_SYNC format:
            postgresql+psycopg2://user:pass@host:port/dbname
        The parser peels scheme, then user:pass, then host:port/dbname.
        """
        from src.shared.config.settings import Settings

        sync_url = Settings().DATABASE_URL_SYNC
        after_scheme = sync_url.split("://", 1)[1]
        user_pass, _host_port_db = after_scheme.split("@", 1)
        expected_user, expected_password = user_pass.split(":", 1)

        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            side_effect=Exception("boom"),
        ):
            opts = dc.get_connection_options("accounts")

        assert opts["user"] == expected_user
        assert opts["password"] == expected_password

    def test_fallback_includes_dbtable_when_table_given(self) -> None:
        """Fallback path also honours the ``table_name`` argument."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            side_effect=Exception("boom"),
        ):
            opts = dc.get_connection_options("accounts")
        assert opts.get("dbtable") == "accounts"

    def test_fallback_omits_dbtable_when_no_table(self) -> None:
        """Fallback path with no ``table_name`` -> no ``dbtable`` key."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            side_effect=Exception("boom"),
        ):
            opts = dc.get_connection_options()

        assert "dbtable" not in opts

    def test_fallback_driver_still_postgres(self) -> None:
        """Driver constant is path-agnostic."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            side_effect=Exception("boom"),
        ):
            opts = dc.get_connection_options()

        assert opts["driver"] == "org.postgresql.Driver"

    def test_fallback_logs_warning(self, caplog: pytest.LogCaptureFixture) -> None:
        """Fallback emits the same WARNING as :func:`get_jdbc_url`."""
        with (
            patch(
                "src.shared.config.aws_config.get_database_credentials",
                side_effect=Exception("boom"),
            ),
            caplog.at_level(logging.WARNING, logger=dc.logger.name),
        ):
            dc.get_connection_options("transactions")

        assert any("Failed to retrieve credentials from Secrets Manager" in rec.message for rec in caplog.records)


# =======================================================================
# Phase 4 — get_table_name tests.
# =======================================================================


class TestGetTableName:
    """VSAM short-name -> PostgreSQL table name translator.

    Case-insensitive via :meth:`str.upper`. Raises :exc:`ValueError`
    for any unknown short-name — fail-loud semantics matching JCL's
    unknown-DD-NAME behaviour at job submission time.
    """

    @pytest.mark.parametrize(
        ("vsam_name", "expected_table"),
        [
            ("ACCTDATA", "accounts"),
            ("CARDDATA", "cards"),
            ("CUSTDATA", "customers"),
            ("CARDXREF", "card_cross_references"),
            ("TRANSACT", "transactions"),
            ("TCATBALF", "transaction_category_balances"),
            ("DISCGRP", "disclosure_groups"),
            ("TRANTYPE", "transaction_types"),
            ("TRANCATG", "transaction_categories"),
            ("DALYTRAN", "daily_transactions"),
            ("USRSEC", "user_security"),
        ],
    )
    def test_uppercase_lookup_succeeds(self, vsam_name: str, expected_table: str) -> None:
        """Every canonical uppercase name resolves correctly."""
        assert dc.get_table_name(vsam_name) == expected_table

    @pytest.mark.parametrize(
        ("vsam_name", "expected_table"),
        [
            ("acctdata", "accounts"),
            ("carddata", "cards"),
            ("tcatbalf", "transaction_category_balances"),
        ],
    )
    def test_lowercase_lookup_succeeds(self, vsam_name: str, expected_table: str) -> None:
        """Lowercase input is uppercased before lookup."""
        assert dc.get_table_name(vsam_name) == expected_table

    @pytest.mark.parametrize(
        ("vsam_name", "expected_table"),
        [
            ("AcctData", "accounts"),
            ("CardData", "cards"),
            ("TranSact", "transactions"),
        ],
    )
    def test_mixed_case_lookup_succeeds(self, vsam_name: str, expected_table: str) -> None:
        """Mixed-case input is uppercased before lookup."""
        assert dc.get_table_name(vsam_name) == expected_table

    def test_unknown_vsam_name_raises_value_error(self) -> None:
        """Unknown short-name triggers :exc:`ValueError`."""
        with pytest.raises(ValueError):
            dc.get_table_name("BOGUS")

    def test_error_message_contains_original_vsam_name(self) -> None:
        """Error message contains the caller-supplied name verbatim.

        The message uses the ORIGINAL (non-uppercased) ``vsam_name``
        so operators can trace configuration drift without guessing
        what casing the caller used.
        """
        with pytest.raises(ValueError) as exc_info:
            dc.get_table_name("bogus_name")

        # Note: message uses the original-case vsam_name in the head
        # part; the message format is:
        #   "Unknown VSAM dataset: {vsam_name}. Valid names: [...]"
        assert "bogus_name" in str(exc_info.value)
        assert "Unknown VSAM dataset" in str(exc_info.value)

    def test_error_message_lists_valid_names(self) -> None:
        """Error message enumerates every valid VSAM short-name.

        Operators debugging an unknown-name failure see the full menu
        of legal values in one place — matching JCL's behaviour where
        an unknown DD-NAME is flagged at job-submission with the full
        JES2 catalog.
        """
        with pytest.raises(ValueError) as exc_info:
            dc.get_table_name("ZZZ")

        msg = str(exc_info.value)
        # Every canonical VSAM short-name must appear in the message.
        for valid_name in dc.VSAM_TABLE_MAP:
            assert valid_name in msg, f"valid name {valid_name!r} missing from error message"

    def test_error_message_includes_valid_names_phrase(self) -> None:
        """The ``Valid names:`` prefix appears before the list."""
        with pytest.raises(ValueError) as exc_info:
            dc.get_table_name("UNKNOWN")
        assert "Valid names" in str(exc_info.value)

    def test_empty_string_raises_value_error(self) -> None:
        """Empty string does not match any key -> ValueError."""
        with pytest.raises(ValueError):
            dc.get_table_name("")

    def test_whitespace_is_not_stripped(self) -> None:
        """Leading/trailing whitespace is NOT stripped (per docstring).

        Callers are expected to supply a well-formed identifier. A
        whitespace-padded name is NOT a valid VSAM short-name.
        """
        with pytest.raises(ValueError):
            dc.get_table_name(" ACCTDATA ")


# =======================================================================
# Phase 5 — read_table tests.
# =======================================================================


class TestReadTable:
    """Verify :func:`read_table` uses the PySpark JDBC reader pattern.

    The function is a thin wrapper around::

        spark_session.read.format("jdbc").options(**options).load()

    Tests verify the exact call sequence without spinning up a real
    Spark session or connecting to PostgreSQL.
    """

    def test_calls_spark_read_format_with_jdbc(
        self,
        mock_spark_session_for_read: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``spark_session.read.format`` is called with ``"jdbc"``."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.read_table(mock_spark_session_for_read, "accounts")

        mock_spark_session_for_read.read.format.assert_called_once_with("jdbc")

    def test_calls_options_with_full_jdbc_opts(
        self,
        mock_spark_session_for_read: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``.options(**opts)`` is passed url/driver/user/password/dbtable."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.read_table(mock_spark_session_for_read, "accounts")

        # Access the reader mock (= return of .format("jdbc"))
        reader = mock_spark_session_for_read.read.format.return_value
        assert reader.options.call_count == 1
        call_kwargs = reader.options.call_args.kwargs
        # Required JDBC opts
        assert call_kwargs["url"].startswith("jdbc:postgresql://")
        assert call_kwargs["driver"] == "org.postgresql.Driver"
        assert call_kwargs["user"] == mock_credentials["username"]
        assert call_kwargs["password"] == mock_credentials["password"]
        # dbtable key supplied via the table_name argument.
        assert call_kwargs["dbtable"] == "accounts"

    def test_load_is_called_exactly_once(
        self,
        mock_spark_session_for_read: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``.load()`` triggers lazy JDBC DataFrame construction."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.read_table(mock_spark_session_for_read, "accounts")

        reader = mock_spark_session_for_read.read.format.return_value
        options_result = reader.options.return_value
        assert options_result.load.call_count == 1

    def test_returns_loaded_dataframe(
        self,
        mock_spark_session_for_read: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """Return value is whatever ``.load()`` returns."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            result = dc.read_table(mock_spark_session_for_read, "accounts")

        expected = mock_spark_session_for_read.read.format.return_value.options.return_value.load.return_value
        assert result is expected

    def test_read_table_logs_info(
        self,
        mock_spark_session_for_read: MagicMock,
        mock_credentials: dict[str, str],
        caplog: pytest.LogCaptureFixture,
    ) -> None:
        """INFO log contains the table name for traceability."""
        with (
            patch(
                "src.shared.config.aws_config.get_database_credentials",
                return_value=mock_credentials,
            ),
            caplog.at_level(logging.INFO, logger=dc.logger.name),
        ):
            dc.read_table(mock_spark_session_for_read, "transactions")

        assert any("Reading table" in rec.message and "transactions" in rec.message for rec in caplog.records)

    @pytest.mark.parametrize(
        "table_name",
        [
            "accounts",
            "cards",
            "customers",
            "transactions",
            "transaction_category_balances",
        ],
    )
    def test_read_table_works_for_every_table(
        self,
        table_name: str,
        mock_credentials: dict[str, str],
    ) -> None:
        """read_table works for each canonical PostgreSQL table.

        Uses a fresh MagicMock per invocation to ensure no state
        bleeds between parametrized cases.
        """
        session = MagicMock()
        session.read.format.return_value.options.return_value.load.return_value = MagicMock(name=f"{table_name}_df")

        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            result = dc.read_table(session, table_name)

        # Verify the dbtable option matches the requested table.
        reader = session.read.format.return_value
        assert reader.options.call_args.kwargs["dbtable"] == table_name
        assert result is not None


# =======================================================================
# Phase 6 — write_table tests.
# =======================================================================


class TestWriteTable:
    """Verify :func:`write_table` builds the PySpark JDBC writer chain.

    The chain is::

        dataframe.write.format("jdbc")
            .options(**options)
            .option("dbtable", table_name)
            .option("truncate", "true")
            .mode(mode)
            .save()
    """

    def test_default_mode_is_append(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """Default ``mode`` argument is ``"append"``."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "transactions")

        mode_mock = mock_dataframe_for_write._chain_w_truncate
        assert mode_mock.mode.call_args == (("append",), {})

    def test_format_jdbc_is_called(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``dataframe.write.format("jdbc")`` starts the chain."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "transactions")
        mock_dataframe_for_write.write.format.assert_called_once_with("jdbc")

    def test_options_include_full_jdbc_opts(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``.options(**opts)`` is passed the full connection dict."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "transactions")

        writer = mock_dataframe_for_write._chain_writer
        opts = writer.options.call_args.kwargs
        assert opts["url"].startswith("jdbc:postgresql://")
        assert opts["driver"] == "org.postgresql.Driver"
        assert opts["user"] == mock_credentials["username"]
        assert opts["password"] == mock_credentials["password"]

    def test_option_dbtable_is_called(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``.option("dbtable", table_name)`` is called explicitly."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "transactions")

        w_options = mock_dataframe_for_write._chain_w_options
        # First option() call in chain
        assert w_options.option.call_args == (
            ("dbtable", "transactions"),
            {},
        )

    def test_option_truncate_is_always_true(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``truncate="true"`` preserves Aurora schema (AAP §0.4.4).

        Without this option, overwrite mode triggers DROP + CREATE
        which strips PRIMARY KEY, NOT NULL, DEFAULT (including the
        ``version_id`` optimistic-concurrency column on ``accounts``
        and ``cards``), and B-tree indexes — breaking the online
        REST API's concurrency checks immediately after any overwrite.
        The option is set unconditionally because the PostgreSQL JDBC
        driver ignores it for non-overwrite modes.
        """
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "transactions")

        w_dbtable = mock_dataframe_for_write._chain_w_dbtable
        # Second option() call in chain
        assert w_dbtable.option.call_args == (
            ("truncate", "true"),
            {},
        )

    def test_explicit_overwrite_mode(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """Explicit ``mode="overwrite"`` is honored."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "accounts", mode="overwrite")

        w_truncate = mock_dataframe_for_write._chain_w_truncate
        assert w_truncate.mode.call_args == (("overwrite",), {})

    def test_save_is_called(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """``.save()`` triggers the eager JDBC write."""
        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(mock_dataframe_for_write, "transactions")

        w_mode = mock_dataframe_for_write._chain_w_mode
        assert w_mode.save.call_count == 1

    def test_write_table_returns_none(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
    ) -> None:
        """Writes are side-effecting; no value returned.

        ``write_table`` is statically typed as ``-> None`` to document the
        side-effecting (write-then-return-nothing) contract. This test
        uses :func:`typing.cast` to temporarily downgrade the return-type
        annotation to ``object`` so the test can bind the result to a
        local and still compare against ``None`` — mypy otherwise
        rejects assigning a ``None``-returning function's value (the
        ``func-returns-value`` check). The runtime behavior is unchanged
        and matches the COBOL PERFORM-of-a-PROCEDURE contract: call for
        effect, no computed output.
        """
        from typing import cast

        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            # cast() tells mypy to treat the call as returning ``object``
            # so the value can be bound; the runtime ``None`` is preserved.
            result = cast(object, dc.write_table(mock_dataframe_for_write, "transactions"))

        assert result is None

    def test_write_table_logs_info_with_mode(
        self,
        mock_dataframe_for_write: MagicMock,
        mock_credentials: dict[str, str],
        caplog: pytest.LogCaptureFixture,
    ) -> None:
        """INFO log captures the table and mode for traceability."""
        with (
            patch(
                "src.shared.config.aws_config.get_database_credentials",
                return_value=mock_credentials,
            ),
            caplog.at_level(logging.INFO, logger=dc.logger.name),
        ):
            dc.write_table(mock_dataframe_for_write, "accounts", mode="overwrite")

        msgs = [rec.message for rec in caplog.records]
        assert any("Writing to table" in m and "accounts" in m and "overwrite" in m for m in msgs)

    @pytest.mark.parametrize("mode", ["append", "overwrite", "error", "ignore"])
    def test_all_standard_modes_accepted(
        self,
        mode: str,
        mock_credentials: dict[str, str],
    ) -> None:
        """Every standard Spark mode is forwarded verbatim.

        Uses a fresh mock per iteration to avoid cross-case state.
        """
        df = MagicMock()
        writer = MagicMock()
        w_options = MagicMock()
        w_dbtable = MagicMock()
        w_truncate = MagicMock()
        w_mode = MagicMock()
        df.write.format.return_value = writer
        writer.options.return_value = w_options
        w_options.option.return_value = w_dbtable
        w_dbtable.option.return_value = w_truncate
        w_truncate.mode.return_value = w_mode

        with patch(
            "src.shared.config.aws_config.get_database_credentials",
            return_value=mock_credentials,
        ):
            dc.write_table(df, "transactions", mode=mode)

        assert w_truncate.mode.call_args == ((mode,), {})


# =======================================================================
# Phase 7 — write_table_idempotent tests.
# =======================================================================


class TestWriteTableIdempotent:
    """Verify the left-anti-join idempotency pattern.

    Resolves QA Checkpoint 5 Issue 22 (POSTTRAN non-idempotent retry).
    The function reads already-posted keys from the target, excludes
    matching rows from the input DataFrame via ``left_anti`` join, and
    appends the remainder. Must raise :exc:`ValueError` for an empty
    key-columns sequence so silently-broken callers fail loud.
    """

    def test_empty_list_raises_value_error(self) -> None:
        """Empty list triggers ValueError (fail-loud guard)."""
        with pytest.raises(ValueError):
            dc.write_table_idempotent(MagicMock(), MagicMock(), "transactions", [])

    def test_empty_tuple_raises_value_error(self) -> None:
        """Empty tuple also triggers ValueError (Sequence check)."""
        with pytest.raises(ValueError):
            dc.write_table_idempotent(MagicMock(), MagicMock(), "transactions", ())

    def test_empty_keys_error_message_verbatim(self) -> None:
        """Error message is the exact string from the source.

        Asserting the verbatim message guards against message drift —
        operator runbooks and log-aggregation alerts key on this
        exact text.
        """
        with pytest.raises(ValueError) as exc_info:
            dc.write_table_idempotent(MagicMock(), MagicMock(), "transactions", [])

        expected = "write_table_idempotent requires at least one key column; received an empty sequence."
        assert str(exc_info.value) == expected

    def test_zero_rows_returns_zero_without_calling_write(self, mock_credentials: dict[str, str]) -> None:
        """Every input key already present -> no write, return 0.

        This is the clean-retry happy path that resolves the QA issue:
        POSTTRAN re-run after a partial-failure finds every key
        already committed and becomes a no-op.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 0
        mock_filtered_df.unpersist = MagicMock()

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table") as mock_write,
        ):
            result = dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert result == 0
        assert mock_write.call_count == 0

    def test_zero_rows_logs_noop(self, caplog: pytest.LogCaptureFixture) -> None:
        """Zero-rows path logs the ``every key already present`` msg.

        Operators rely on this log message to confirm retry safety
        during pipeline diagnostics.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 0

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
            caplog.at_level(logging.INFO, logger=dc.logger.name),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert any(
            "No new rows" in rec.message and "every key already present" in rec.message for rec in caplog.records
        )

    def test_nonzero_rows_calls_write_table(self) -> None:
        """Rows remaining after left-anti join are appended."""
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 7

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table") as mock_write,
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert mock_write.call_count == 1
        # First positional arg must be the filtered df.
        write_args = mock_write.call_args
        assert write_args.args[0] is mock_filtered_df
        assert write_args.args[1] == "transactions"
        assert write_args.kwargs.get("mode") == "append"

    def test_nonzero_rows_returns_written_count(self) -> None:
        """Return value is the integer count of written rows."""
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 42

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            result = dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert result == 42
        assert isinstance(result, int)

    def test_left_anti_join_uses_key_columns(self) -> None:
        """``.join(on=key_columns, how="left_anti")`` semantic is used.

        This verifies the exact idempotency strategy: left-anti join
        against the existing primary-key projection returns only rows
        whose key is NOT present in the target table.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 3

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert mock_input_df.join.call_args.kwargs["on"] == ["tran_id"]
        assert mock_input_df.join.call_args.kwargs["how"] == "left_anti"

    def test_existing_keys_select_projects_key_columns_only(self) -> None:
        """``.select(*key_columns)`` on the existing-keys DataFrame.

        Minimises network traffic — only the key columns are fetched
        from PostgreSQL, not the full row width.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 0

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        # The select call uses positional unpacking so the call args
        # tuple contains each key column as a separate positional.
        assert mock_existing_df.select.call_args.args == ("tran_id",)

    def test_composite_key_columns_passed_through(self) -> None:
        """Multiple key columns are preserved verbatim in select/join.

        Example from ``transaction_category_balances`` composite PK.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 1

        composite_keys = ["acct_id", "tran_type_cd", "tran_cat_cd"]

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transaction_category_balances",
                composite_keys,
            )

        # Both select and join see the full 3-column list.
        assert mock_existing_df.select.call_args.args == tuple(composite_keys)
        assert mock_input_df.join.call_args.kwargs["on"] == composite_keys

    def test_filtered_df_is_cached(self) -> None:
        """The filtered DataFrame is cached before ``count()``.

        Caching avoids re-executing the upstream JDBC read + join
        between ``.count()`` (action 1) and ``.write.save()`` (action
        2), doubling network traffic otherwise.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 3

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert mock_filtered_df.cache.call_count == 1

    def test_unpersist_called_in_finally_on_success(self) -> None:
        """Finally block releases the cached DataFrame after success."""
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 5
        mock_filtered_df.unpersist = MagicMock()

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert mock_filtered_df.unpersist.call_count == 1

    def test_unpersist_called_even_on_zero_rows(self) -> None:
        """Zero-rows path still releases the cached DataFrame."""
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 0
        mock_filtered_df.unpersist = MagicMock()

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert mock_filtered_df.unpersist.call_count == 1

    def test_unpersist_exception_does_not_mask_success(self) -> None:
        """An exception during ``unpersist()`` is swallowed (debug log).

        Per module docstring: ``unpersist()`` raising is non-fatal and
        should never mask the write's success or failure. The function
        still returns the written row count.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 3
        mock_filtered_df.unpersist.side_effect = RuntimeError("unpersist blew up")

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
        ):
            result = dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        # The write's return value is preserved despite unpersist
        # raising — this is the exact guarantee documented in the
        # module docstring.
        assert result == 3

    def test_unpersist_exception_logged_at_debug(self, caplog: pytest.LogCaptureFixture) -> None:
        """Unpersist exception is logged at DEBUG (not ERROR/WARN).

        DEBUG level keeps the CloudWatch log noise minimal for healthy
        runs while preserving diagnostic info when a non-fatal
        cleanup failure actually occurs.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 3
        mock_filtered_df.unpersist.side_effect = RuntimeError("unpersist blew up")

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
            caplog.at_level(logging.DEBUG, logger=dc.logger.name),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        debug_records = [rec for rec in caplog.records if rec.levelname == "DEBUG"]
        assert any("filtered_df.unpersist() raised" in rec.message for rec in debug_records)

    def test_write_table_called_with_append_mode(self) -> None:
        """The delegated :func:`write_table` is always ``mode="append"``.

        ``mode="overwrite"`` would violate the idempotency semantic
        because it would destroy committed rows on retry. Append is
        the only correct mode for the idempotent-insert pattern.
        """
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 2

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table") as mock_write,
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert mock_write.call_args.kwargs["mode"] == "append"

    def test_success_logs_written_count(self, caplog: pytest.LogCaptureFixture) -> None:
        """Happy-path log reports the written row count."""
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 10

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
            caplog.at_level(logging.INFO, logger=dc.logger.name),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transactions",
                ["tran_id"],
            )

        assert any("wrote 10" in rec.message and "Idempotent append" in rec.message for rec in caplog.records)

    def test_starting_log_includes_key_columns(self, caplog: pytest.LogCaptureFixture) -> None:
        """Start log lists the key columns for traceability."""
        mock_session = MagicMock()
        mock_existing_df = MagicMock()
        mock_existing_df.select.return_value = mock_existing_df
        mock_input_df = MagicMock()
        mock_filtered_df = MagicMock()
        mock_input_df.join.return_value = mock_filtered_df
        mock_filtered_df.cache.return_value = mock_filtered_df
        mock_filtered_df.count.return_value = 1

        with (
            patch.object(dc, "read_table", return_value=mock_existing_df),
            patch.object(dc, "write_table"),
            caplog.at_level(logging.INFO, logger=dc.logger.name),
        ):
            dc.write_table_idempotent(
                mock_session,
                mock_input_df,
                "transaction_category_balances",
                ["acct_id", "tran_type_cd", "tran_cat_cd"],
            )

        assert any(
            "Starting idempotent append" in rec.message and "transaction_category_balances" in rec.message
            for rec in caplog.records
        )


# =======================================================================
# Phase 8 — module surface guard (``__all__``).
# =======================================================================


class TestModuleSurface:
    """Guard the public API of the module.

    A future refactor that adds or removes a public name in ``__all__``
    must update this surface test — surfacing API-level drift via a
    single focused test rather than a bulk downstream-test failure.
    """

    def test_all_contains_exactly_seven_names(self) -> None:
        """``__all__`` must expose exactly seven public names."""
        assert len(dc.__all__) == 7

    def test_all_contents_are_exact(self) -> None:
        """``__all__`` contents are the documented public API."""
        assert list(dc.__all__) == [
            "VSAM_TABLE_MAP",
            "get_jdbc_url",
            "get_connection_options",
            "get_table_name",
            "read_table",
            "write_table",
            "write_table_idempotent",
        ]

    def test_all_names_are_defined(self) -> None:
        """Every name in ``__all__`` must be importable from the module.

        Protects against a stale ``__all__`` entry whose corresponding
        definition was removed — which would raise ImportError on
        ``from src.batch.common.db_connector import *``.
        """
        for name in dc.__all__:
            assert hasattr(dc, name), f"missing name {name!r}"

    def test_all_is_a_list_of_str(self) -> None:
        """``__all__`` is a list (not tuple/set) per Python idiom."""
        assert isinstance(dc.__all__, list)
        for name in dc.__all__:
            assert isinstance(name, str)

    def test_private_logger_not_in_all(self) -> None:
        """The module-level ``logger`` is NOT in the public API."""
        assert "logger" not in dc.__all__

    def test_vsam_table_map_is_dict(self) -> None:
        """Sanity: VSAM_TABLE_MAP is a dict object."""
        assert isinstance(dc.VSAM_TABLE_MAP, dict)

    def test_functions_are_callable(self) -> None:
        """Every public function member is callable."""
        callable_names = [
            "get_jdbc_url",
            "get_connection_options",
            "get_table_name",
            "read_table",
            "write_table",
            "write_table_idempotent",
        ]
        for name in callable_names:
            obj = getattr(dc, name)
            assert callable(obj), f"{name!r} is not callable"
