# ============================================================================
# tests/conftest.py — Project-wide pytest fixture registry.
#
# Purpose
# -------
# Pytest automatically discovers ``conftest.py`` files and makes their
# fixtures available to every test module in the same directory or any
# descendant directory.  This module-level conftest lives at the ``tests/``
# root so its fixtures are visible to:
#
#   * ``tests/unit/test_batch/*``         — PySpark Glue job tests
#   * ``tests/unit/test_services/*``      — FastAPI service-layer tests
#   * ``tests/unit/test_routers/*``       — FastAPI router tests
#   * ``tests/unit/test_models/*``        — SQLAlchemy ORM model tests
#   * ``tests/integration/*``             — integration tests
#   * ``tests/e2e/*``                     — end-to-end pipeline tests
#
# Currently exposed fixtures
# --------------------------
# ``spark_session`` — a session-scoped real :class:`pyspark.sql.SparkSession`
#     running in ``local[1]`` mode.  Required by the Phase 7 PySpark
#     integration test in ``tests/unit/test_batch/test_posttran_job.py``
#     (``test_posttran_main_with_spark(spark_session)``).  Individual test
#     modules MAY override this with a module-local fixture (pytest honours
#     the nearest definition) — that is how
#     ``tests/unit/test_batch/test_daily_tran_driver_job.py`` provides a
#     MagicMock-based fallback when real Spark is not needed.
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
"""Project-wide pytest fixtures.

The ``spark_session`` fixture built here provides a real, in-process
:class:`pyspark.sql.SparkSession` that can be used by any test requiring
actual PySpark DataFrame execution — in particular the Phase 7 PySpark
integration test of the POSTTRAN job (AAP §0.5.1, CBTRN02C.cbl), where
validate_transaction() and the main() orchestrator flow must be
exercised end-to-end against in-memory DataFrames built from
:class:`pyspark.sql.Row` literals matching the COBOL copybook layouts
(CVTRA06Y, CVACT01Y, CVACT03Y, CVTRA01Y, CVTRA05Y).

The session is configured for the fastest possible unit-test turnaround:

* ``master("local[1]")`` — single-thread local execution avoids the
  scheduler overhead of ``local[*]`` while still exercising the full
  DataFrame execution path (catalyst optimiser, codegen, etc.).
* ``spark.ui.enabled=false`` — skips the Spark Web UI port-binding
  step (tests are headless CI runs; the UI is unnecessary and can
  cause ``BindException`` flakes when many pytest workers start in
  parallel).
* ``spark.sql.shuffle.partitions=1`` — shuffle partitions default to
  200, which is wasteful for the ~10-row DataFrames used in unit
  tests and slows down tests materially.
* ``spark.sql.adaptive.enabled=false`` — adaptive query execution is
  overkill for small unit-test datasets and its logging is noisy.

The fixture is ``scope="session"`` so the SparkSession is created once
at the start of the pytest run and shared across every test in the
session.  Teardown runs at the end of the session via ``yield`` →
``spark.stop()``.  SparkSession construction is expensive (1-3
seconds per session); sharing it across tests is a ~10× speedup when
more than one test uses the fixture.

See Also
--------
:mod:`tests.unit.test_batch.test_posttran_job` — primary consumer of
    ``spark_session`` for the Phase 7 end-to-end PySpark integration
    test.
:mod:`tests.unit.test_batch.test_daily_tran_driver_job` — provides a
    module-local MagicMock override of ``spark_session`` because its
    tests do not need real Spark execution.
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from pyspark.sql import SparkSession


@pytest.fixture(scope="session")
def spark_session() -> Iterator[SparkSession]:
    """Provide a session-scoped :class:`pyspark.sql.SparkSession`.

    Construction cost: ~1-3 seconds for the local[1] session.  The
    session is reused across every test that requests the fixture
    and is explicitly stopped when pytest tears down the session,
    which releases the JVM and all associated cluster resources.

    The fixture is available project-wide because this conftest.py
    lives at the tests/ root and pytest discovers it automatically.

    Yields
    ------
    SparkSession
        A live SparkSession configured for single-thread local
        execution with the optimisations documented in the module
        docstring (UI disabled, 1 shuffle partition, adaptive-QE
        disabled).

    Notes
    -----
    Module-local fixtures with the same name (``spark_session``)
    override this project-wide fixture per pytest's fixture-
    resolution hierarchy — this is how
    ``tests/unit/test_batch/test_daily_tran_driver_job.py``
    substitutes a cheap :class:`unittest.mock.MagicMock` when it
    does not need a real Spark runtime.
    """
    # ------------------------------------------------------------------
    # Build the SparkSession.
    #
    # ``appName`` is set to the project name so the session appears
    # with a recognisable label in local log output (and in the
    # Spark UI, were it enabled).  The config keys below are set in
    # the builder rather than at runtime because some (``ui.enabled``
    # in particular) must be set BEFORE the SparkContext starts.
    # ------------------------------------------------------------------
    spark = (
        SparkSession.builder.master("local[1]")
        .appName("carddemo-unit-tests")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .config("spark.sql.adaptive.enabled", "false")
        .config("spark.driver.bindAddress", "127.0.0.1")
        .config("spark.driver.host", "127.0.0.1")
        .getOrCreate()
    )

    # Reduce log noise during unit-test runs — ERROR-only logging
    # keeps pytest output legible (Spark defaults to WARN which is
    # still verbose for a test suite).
    spark.sparkContext.setLogLevel("ERROR")

    # Hand the session over to the consuming test(s).  The yield
    # suspends this fixture function until pytest's session-scoped
    # teardown fires at the end of the pytest run.
    yield spark

    # ------------------------------------------------------------------
    # Teardown: stop the SparkSession cleanly.
    #
    # ``SparkSession.stop()`` releases the JVM, closes the Spark
    # context, and unbinds any network ports acquired during the
    # session.  Without this teardown the JVM would linger in the
    # test process and subsequent pytest invocations (e.g., in a
    # ``--lf`` rerun) could hit port-conflict errors.
    # ------------------------------------------------------------------
    spark.stop()
