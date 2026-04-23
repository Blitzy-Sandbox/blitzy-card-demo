# ============================================================================
# Source: AAP §0.4.1 Refactored Structure Planning — "tests/unit/test_batch/common/"
#         — Coverage remediation for src/batch/common/ utilities
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
"""Unit test package for ``src.batch.common`` utility modules.

This package houses unit tests for the three shared infrastructure
modules used by every PySpark Glue job in :mod:`src.batch.jobs`:

* ``test_s3_utils`` — verifies the GDG (Generation Data Group) → S3
  versioned object storage helpers (``get_versioned_s3_path``,
  ``write_to_s3``, ``read_from_s3``, ``list_generations``,
  ``cleanup_old_generations``). Replaces the IDCAMS
  ``DEFINE GENERATIONDATAGROUP`` / ``SCRATCH`` / ``LISTCAT`` semantics
  from ``DEFGDGB.jcl``, ``REPTFILE.jcl``, ``DALYREJS.jcl``,
  ``TRANBKP.jcl``, ``CREASTMT.JCL``, ``TRANREPT.jcl``.
* ``test_glue_context`` — verifies the ``GlueContext`` /
  ``SparkSession`` factory (``init_glue``, ``commit_job``) and the
  CloudWatch-compatible ``JsonFormatter`` used for structured logging.
* ``test_db_connector`` — verifies the Aurora PostgreSQL JDBC
  connectivity helpers (``get_jdbc_url``, ``get_connection_options``,
  ``read_table``, ``write_table``, ``write_table_idempotent``) and the
  ``VSAM_TABLE_MAP`` VSAM-name → PostgreSQL-table lookup.

QA Checkpoint 7 Remediation
---------------------------
Addresses the four "MAJOR — no tests" coverage gaps documented in the
QA Checkpoint 7 report under *Coverage Gap Analysis*:

* ``src/batch/common/s3_utils.py``       — 10% → target ≥ 80%
* ``src/batch/common/glue_context.py``   — 25% → target ≥ 80%
* ``src/batch/common/db_connector.py``   — 54% → target ≥ 80%

Combined with the coverage additions under ``tests/unit/test_utils/``
(``test_string_utils.py``, ``test_decimal_utils.py``,
``test_date_utils.py``) this package lifts the project-wide coverage
past the AAP §0.7.2 Testing Requirements target of 81.5%.

See Also
--------
AAP §0.4.1 — Refactored Structure Planning (``tests/unit/test_batch/``)
AAP §0.7.2 — Testing Requirements (target coverage parity with 81.5%)
QA Checkpoint 7 Test Report — Coverage Gap Analysis table
"""
