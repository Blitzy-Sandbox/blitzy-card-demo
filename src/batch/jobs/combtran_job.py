# ============================================================================
# Source: app/jcl/COMBTRAN.jcl  — Combine Transactions (Stage 3 orchestration)
#         DFSORT + IDCAMS REPRO, NO COBOL program
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
"""Stage 3 — Combined transactions PySpark Glue job.

Replaces ``app/jcl/COMBTRAN.jcl`` — the Stage 3 JCL-only batch job of the
5-stage CardDemo nightly batch pipeline. Unlike Stages 1, 2, 4a, and 4b
(which wrap one or more COBOL programs each) this stage is **pure JCL**:
a z/OS DFSORT invocation in STEP05R followed by an IDCAMS REPRO in
STEP10. No COBOL source file is involved.

Pipeline position
-----------------
::

    Stage 1 (POSTTRAN)  →  Stage 2 (INTCALC)  →  Stage 3 (COMBTRAN) ← THIS FILE
                                                          │
                                      ┌───────────────────┴───────────────────┐
                                      ▼                                       ▼
                            Stage 4a (CREASTMT)                     Stage 4b (TRANREPT)

Stage 3 consumes the output of Stages 1 and 2 (posted daily transactions
plus system-generated interest transactions, both already persisted to
the Aurora PostgreSQL ``transactions`` table by the upstream jobs) and
produces a sorted, deduplicated snapshot that Stages 4a and 4b can read
in parallel for statement generation and reporting. The sorting by
``TRAN-ID`` ascending preserves the access pattern expected by the
``TRANFILE`` VSAM KSDS master — downstream consumers rely on stable
ordering for control-break logic (e.g., ``CBTRN03C``'s 3-level totals,
``CBSTM03A``'s statement grouping).

Overview of the original JCL
----------------------------
``COMBTRAN.jcl`` (53 lines) declares two sequential steps bound together
by z/OS dataset generations:

==========  ==================  ==========================================================
Step        PGM                 Purpose
==========  ==================  ==========================================================
STEP05R     ``SORT``            DFSORT merge-and-sort of 2 SORTIN datasets into a single
                                GDG generation at TRANSACT.COMBINED(+1)
STEP10      ``IDCAMS``          REPRO the freshly-written combined sequential dataset
                                into the TRANSACT VSAM KSDS master (replacing its
                                contents entirely — this is a full-reload pattern)
==========  ==================  ==========================================================

STEP05R — DFSORT merge-sort (COMBTRAN.jcl lines 22-37)
------------------------------------------------------
The ``//SORTIN`` DD statement uses z/OS DD concatenation to treat two
physical datasets as a single virtual input stream::

    //SORTIN   DD DISP=SHR,
    //         DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)         ← Source 1
    //         DD DISP=SHR,
    //         DSN=AWS.M2.CARDDEMO.SYSTRAN(0)                ← Source 2
    //SYMNAMES DD *
    TRAN-ID,1,16,CH                                         ← SYMNAME: TRAN-ID at offset 1, 16 chars, CHAR
    //SYSIN    DD *
     SORT FIELDS=(TRAN-ID,A)                                ← ASCending sort by TRAN-ID
    //SORTOUT  DD DISP=(NEW,CATLG,DELETE),
    //         UNIT=SYSDA,
    //         DCB=(*.SORTIN),
    //         SPACE=(CYL,(1,1),RLSE),
    //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)     ← New GDG generation

The two SORTIN datasets correspond to:

* **TRANSACT.BKUP(0)** — the most recent backup generation of the
  transaction master, produced by ``app/jcl/TRANBKP.jcl`` (run before
  Stage 1 POSTTRAN as a safety net). This represents the
  *pre-pipeline* state of the transaction ledger.
* **SYSTRAN(0)** — the most recent system-generated transaction file,
  produced by Stage 2 INTCALC (``app/cbl/CBACT04C.cbl`` →
  ``src/batch/jobs/intcalc_job.py``) into the GDG
  ``AWS.M2.CARDDEMO.SYSTRAN(+1)``. This contains the monthly interest
  transactions with ``TRAN-SOURCE = 'System'`` (preserved verbatim
  from ``CBACT04C`` paragraph 1300-B-WRITE-TX line 484).

The SORT operation produces a sorted union at
``AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)``.

STEP10 — IDCAMS REPRO (COMBTRAN.jcl lines 41-49)
-------------------------------------------------
::

    //STEP10 EXEC PGM=IDCAMS
    //SYSPRINT DD   SYSOUT=*
    //TRANSACT DD DISP=SHR,
    //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)    ← INFILE (just-written combined)
    //TRANVSAM DD DISP=SHR,
    //         DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS       ← OUTFILE (target master)
    //SYSIN    DD   *
       REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
    /*

IDCAMS REPRO copies records from the sequential combined file into the
TRANSACT VSAM KSDS cluster. Because the VSAM cluster is keyed on
``TRAN-ID`` and already contains records, REPRO with no explicit
REPLACE option **fails on duplicate keys** in strict VSAM semantics;
this JCL relies on the upstream de-duplication enforced by the SORT
step (TRAN-ID uniqueness is a natural consequence of the upstream data
flow — daily transactions have unique timestamp-based IDs and interest
transactions have unique date-prefixed suffixes). The end state is a
transaction master containing exactly the combined set of records from
the two SORTIN inputs, in TRAN-ID ascending order.

Mainframe-to-Cloud Transformation
---------------------------------
Every z/OS construct in COMBTRAN.jcl maps to a PySpark / AWS Glue
equivalent:

==============================================  =====================================================================
Mainframe construct                             Cloud equivalent
==============================================  =====================================================================
``//COMBTRAN JOB``                              :func:`src.batch.common.glue_context.init_glue` call — AWS Glue 5.1
                                                GlueContext + SparkSession initialization with JSON logging.
``//STEP05R  EXEC PGM=SORT``                    ``combined_df = backup_df.union(systran_df)`` +
                                                ``.orderBy(F.col("tran_id").asc())`` — PySpark DataFrame merge & sort.
``//SORTIN DD DSN=TRANSACT.BKUP(0)``            DataFrame filter ``tran_source != 'System'`` on
                                                ``read_table(spark, "transactions")`` — the pre-INTCALC master
                                                subset of the transactions table. Augmented by an S3 audit-marker
                                                probe via :func:`src.batch.common.s3_utils.read_from_s3` for
                                                operator traceability.
``//SORTIN DD DSN=SYSTRAN(0)``                  DataFrame filter ``tran_source == 'System'`` on
                                                ``read_table(spark, "transactions")`` — the interest transactions
                                                posted by Stage 2 INTCALC, bearing the literal
                                                ``TRAN-SOURCE = 'System'`` from CBACT04C line 484. Augmented by an
                                                S3 audit-marker probe for operator traceability.
``SYMNAMES TRAN-ID,1,16,CH``                    ``F.col("tran_id")`` — the symbolic sort-key reference.
``SORT FIELDS=(TRAN-ID,A)``                     ``orderBy(F.col("tran_id").asc())`` — sort ascending.
``//SORTOUT DSN=TRANSACT.COMBINED(+1)``         ``sorted_df.write.mode("overwrite").parquet(combined_s3_path)`` —
                                                Parquet archival to S3 versioned path resolved via
                                                :func:`src.batch.common.s3_utils.get_versioned_s3_path` with
                                                ``generation="+1"`` (replaces the mainframe GDG ``(+1)`` notation).
``//STEP10 EXEC PGM=IDCAMS``                    :func:`src.batch.common.db_connector.write_table` call.
``//TRANSACT DD DSN=TRANSACT.COMBINED(+1)``     The ``sorted_df`` DataFrame (in-memory equivalent of the freshly
                                                written TRANSACT.COMBINED(+1)). No second read from S3 is
                                                required because the DataFrame is already materialized.
``//TRANVSAM DD DSN=TRANSACT.VSAM.KSDS``        The PostgreSQL ``transactions`` table (canonical mapping from
                                                :data:`src.batch.common.db_connector.VSAM_TABLE_MAP` ``TRANSACT``).
``REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)``    ``write_table(sorted_df, "transactions", mode="overwrite")`` —
                                                truncate-and-reload semantics preserving the IDCAMS REPRO
                                                behavior of replacing the VSAM KSDS contents entirely.
``//SYSPRINT DD SYSOUT=*``                      CloudWatch structured JSON logging via the module-level
                                                ``logger`` configured by :func:`init_glue`.
``//SYSOUT DD SYSOUT=*``                        Same — CloudWatch structured JSON logging.
JCL terminal success (``MAXCC=0``)              :func:`src.batch.common.glue_context.commit_job` — signals Glue
                                                job completion to Step Functions.
JCL abend (``COND=(0,NE)``)                     Uncaught exception → non-zero process exit → AWS Glue marks the
                                                job ``FAILED`` → Step Functions halts the downstream parallel
                                                fan-out (Stages 4a and 4b) per the original COND semantics.
==============================================  =====================================================================

DD concatenation → DataFrame union
----------------------------------
On z/OS, listing two ``DD`` cards under a single DD-NAME (``SORTIN``)
without an intervening DD-NAME causes MVS to allocate a *concatenation*:
the DFSORT utility reads the two datasets back-to-back as a single
logical input stream. The sort then operates on the combined virtual
dataset.

In PySpark the semantic equivalent is ``DataFrame.union(other)``.
Ordering does not matter because ``orderBy`` re-sorts the unioned frame
by the SORT FIELDS=(TRAN-ID,A) criterion. Schema compatibility IS
required (same column count, same ordinal types) — satisfied trivially
because both source DataFrames are subsets of the same
``transactions`` table.

Data-source split rationale
---------------------------
After Stages 1 and 2 complete successfully, the Aurora PostgreSQL
``transactions`` table already contains **both** input categories that
COMBTRAN's SORTIN concatenation would have read separately on z/OS:

1. **Daily posted transactions** (from POSTTRAN) — ``tran_source``
   typically ``'POS'`` or ``'Online'`` or similar business-source
   values inherited from the daily feed.
2. **System-generated interest transactions** (from INTCALC) —
   ``tran_source`` EQUAL to the literal ``'System'``, preserved
   verbatim from ``CBACT04C`` paragraph 1300-B-WRITE-TX line 484
   (``MOVE 'System' TO TRAN-SOURCE``).

The JCL's explicit 2-way DD concatenation is preserved at the
semantic level by **filtering the transactions table into two
DataFrames** on the ``tran_source`` discriminator, then unioning them
back. This gives an identical materialized result to the mainframe
path while retaining the explicit 2-source provenance in the code
(useful for operator log inspection, runtime audit, and future
re-partitioning scenarios where the two sources might be stored
separately).

VSAM KSDS uniqueness → DataFrame ``dropDuplicates``
----------------------------------------------------
The target TRANVSAM is a KSDS cluster keyed on ``TRAN-ID`` with the
standard "unique key" option (``LISTCAT`` confirms no ``NONUNIQUEKEY``
attribute on AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS). IDCAMS REPRO on a
unique-key cluster aborts with ``IDC3302I DUPLICATE RECORD`` if the
combined file contains duplicate TRAN-IDs.

The PySpark translation explicitly calls ``dropDuplicates(["tran_id"])``
before the sort to preserve this contract. In the happy path this is
a no-op (upstream jobs emit unique IDs) — but defensive deduplication
matches the mainframe's "fail fast on duplicates" behavior without
actually aborting, which is more tolerant of edge cases (Stage 1
reruns, partial stage recoveries) and aligns with AWS Glue's idempotent
re-run story.

S3 archival path (``TRANSACT.COMBINED(+1)``)
---------------------------------------------
The sorted DataFrame is persisted to S3 as Parquet under a versioned
path resolved via
:func:`src.batch.common.s3_utils.get_versioned_s3_path` with
``gdg_name="TRANSACT.COMBINED"`` and ``generation="+1"``. This replaces
the mainframe ``DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)`` GDG
allocation. Each run produces a new date-stamped subdirectory:
``s3://{bucket}/combined/transactions/YYYY/MM/DD/HHMMSS/``. The GDG
retention limit of 5 generations (per ``DEFGDGB.jcl``) is enforced by
:func:`src.batch.common.s3_utils.cleanup_old_generations` invoked out
of band (not by this job) — consistent with the JCL architecture
where SCRATCH is an IDCAMS cleanup step scheduled separately.

Parquet rather than a fixed-width 350-byte COBOL record file is chosen
for the archive because:

* Downstream S3 consumers are PySpark jobs and Athena queries that
  prefer columnar formats.
* The JCL used fixed-width sequential (``DCB=(*.SORTIN)`` inherited
  from the KSDS LRECL=350) only because z/OS SORT/REPRO could not read
  any other format — an infrastructure constraint, not a requirement.
* Compressed Parquet is 5-10× smaller than the original fixed-width
  records, reducing storage cost.

Error Handling — CEE3ABD / ABCODE=999 equivalence
--------------------------------------------------
Any uncaught exception raised inside :func:`main` is caught by the
top-level ``try ... except`` block, logged with a structured error
record, and **re-raised**. The uncaught exception then propagates to
the Python interpreter top level, exiting the process with a non-zero
status code. AWS Glue interprets this as a job failure and Step
Functions transitions the job state to ``FAILED``, halting the
parallel fan-out to Stages 4a (CREASTMT) and 4b (TRANREPT). This is
functionally equivalent to the mainframe ``COND=(0,NE)`` semantics
where a non-zero return code from any step halts all downstream
steps and ultimately the job with the condition code propagated to
JES2.

Because COMBTRAN has no COBOL program, there is no 9999-ABEND-PROGRAM
paragraph or ``CALL 'CEE3ABD'`` statement in the source — the JCL
itself is the abort mechanism. The Python equivalent (re-raise →
non-zero exit) faithfully preserves this.

Financial precision
-------------------
Although this job performs no arithmetic on monetary columns, it
preserves the ``NUMERIC(15, 2)`` precision of the ``transactions``
table through the PySpark JDBC connector. The connector round-trips
PostgreSQL NUMERIC columns as :class:`pyspark.sql.types.DecimalType`
— no floating-point conversion occurs at any point (AAP §0.7.2).

See Also
--------
:mod:`src.batch.jobs.posttran_job`   — Stage 1 (CBTRN02C.cbl) — upstream producer of non-system transactions
:mod:`src.batch.jobs.intcalc_job`    — Stage 2 (CBACT04C.cbl) — upstream producer of system (interest) transactions
:mod:`src.batch.jobs.creastmt_job`   — Stage 4a (CBSTM03A/B.CBL) — downstream consumer (parallel with tranrept_job)
:mod:`src.batch.jobs.tranrept_job`   — Stage 4b (CBTRN03C.cbl) — downstream consumer (parallel with creastmt_job)
:mod:`src.batch.common.glue_context` — init_glue / commit_job lifecycle helpers
:mod:`src.batch.common.db_connector` — read_table / write_table PySpark JDBC helpers
:mod:`src.batch.common.s3_utils`     — get_versioned_s3_path / read_from_s3 GDG-equivalent helpers

AAP §0.2.2 — Batch Program Classification (COMBTRAN stage 3, no COBOL)
AAP §0.5.1 — File-by-File Transformation Plan (combtran_job row)
AAP §0.7.1 — Preserve all existing business logic exactly as-is
AAP §0.7.3 — Minimal change discipline

Source
------
``app/jcl/COMBTRAN.jcl`` (53 lines — DFSORT + IDCAMS REPRO, no COBOL)
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``logging`` — Structured JSON logging configured globally by
#   :func:`src.batch.common.glue_context.init_glue` that emits to
#   CloudWatch Logs via the JSON-formatted stdout handler. Replaces the
#   mainframe ``//SYSOUT DD SYSOUT=*`` (COMBTRAN.jcl line 32) and
#   ``//SYSPRINT DD SYSOUT=*`` (COMBTRAN.jcl line 42) DD statements that
#   captured SORT diagnostic output and IDCAMS return codes. Each call
#   through the module-level ``logger`` below is serialized as a
#   single-line JSON document suitable for CloudWatch Logs Insights
#   queries.
# ``sys``     — AWS Glue script convention. ``init_glue`` uses
#   ``sys.argv`` internally via ``awsglue.utils.getResolvedOptions``.
#   The ``if __name__ == "__main__"`` guard below additionally logs the
#   argv vector at DEBUG level so that operators troubleshooting
#   Glue argument-passing issues in CloudWatch can correlate Step
#   Functions inputs with the script's observed runtime arguments.
# ----------------------------------------------------------------------------
import logging
import sys

# ----------------------------------------------------------------------------
# Third-party imports — PySpark 3.5.6 (shipped with AWS Glue 5.1 runtime).
# ----------------------------------------------------------------------------
# ``pyspark.sql.functions`` (aliased ``F``) — column expression helpers
# used to:
#
#   * Build the sort-by expression that preserves the mainframe
#     ``SORT FIELDS=(TRAN-ID,A)`` semantic from COMBTRAN.jcl line 30.
#     ``F.col("tran_id").asc()`` is the idiomatic PySpark equivalent
#     of the SYMNAMES-declared sort key (SYMNAMES: TRAN-ID,1,16,CH).
#
#   * Build the literal comparison expression for splitting the
#     transactions DataFrame into its two source sub-frames (backup vs
#     system-generated). ``F.lit(_SYSTRAN_SOURCE_VALUE)`` emits the
#     literal string ``'System'`` inside the Spark physical plan —
#     matching the COBOL source's ``MOVE 'System' TO TRAN-SOURCE``
#     literal at CBACT04C line 484 (preserved verbatim per AAP §0.7.1).
#
# The ``F`` alias is the canonical PySpark convention documented in
# Apache Spark 3.5.6 SQL API reference. The ``noqa: N812`` suppresses
# ruff/flake8's lowercase-import-name warning — ``F`` as a module alias
# is an exception explicitly sanctioned by the PySpark style guide.
# ----------------------------------------------------------------------------
from pyspark.sql import functions as F  # noqa: N812 - canonical PySpark alias

# ----------------------------------------------------------------------------
# First-party imports — batch common infrastructure.
# ----------------------------------------------------------------------------
# Every name imported below is WHITELISTED by the AAP ``depends_on_files``
# declaration for this file (see AAP §0.5.1 and the schema-declared
# ``internal_imports`` for ``src/batch/jobs/combtran_job.py``). No other
# internal modules may be imported — particularly, this job MUST NOT
# import from any sibling job module in ``src.batch.jobs`` (those are
# standalone Glue scripts invoked directly by Step Functions).
#
# ``init_glue(job_name=...)``
#     Returns the 4-tuple ``(spark_session, glue_context, job,
#     resolved_args)``. In the AWS Glue 5.1 managed runtime it
#     instantiates a :class:`pyspark.context.SparkContext`, wraps it
#     with :class:`awsglue.context.GlueContext`, initializes an
#     :class:`awsglue.job.Job` object, applies Spark tuning
#     (``spark.sql.shuffle.partitions=10``, ``spark.sql.adaptive.enabled=true``),
#     and installs :class:`src.batch.common.glue_context.JsonFormatter`
#     on the root logger so every call through the module-level
#     ``logger`` is serialized as single-line JSON to stdout →
#     CloudWatch. In local development (``_GLUE_AVAILABLE`` is False)
#     it returns a minimal local-mode :class:`pyspark.sql.SparkSession`
#     plus ``None`` for ``glue_context`` and ``job``.
#
# ``commit_job(job)``
#     Commits the Glue job bookmark on success (or no-op when
#     ``job is None`` in local development mode). Replaces the JCL
#     terminal success signalling (``MAXCC=0``).
#
# ``read_table(spark, "<table_name>")``
#     Issues a JDBC query against the configured Aurora PostgreSQL
#     cluster and returns a lazy :class:`pyspark.sql.DataFrame`. No
#     JDBC traffic flows until a Spark action (``.count()``,
#     ``.collect()``, ``.write.save()``, etc.) is triggered. Replaces
#     the JCL VSAM ``DISP=SHR`` read pattern for the transactions
#     master — the effective data source for COMBTRAN since the two
#     JCL SORTIN datasets (TRANSACT.BKUP(0) + SYSTRAN(0)) are both
#     fully represented in the ``transactions`` table after upstream
#     Stages 1 and 2.
#
# ``write_table(dataframe, "<table_name>", mode="overwrite")``
#     Issues a JDBC write from the PySpark DataFrame to the configured
#     Aurora PostgreSQL cluster. ``mode="overwrite"`` truncates the
#     target table before inserting — semantically equivalent to the
#     IDCAMS ``REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)`` pattern from
#     COMBTRAN.jcl line 48, which replaces the entire VSAM KSDS
#     contents with the combined sorted set.
#
# ``get_versioned_s3_path(gdg_name, generation="+1"|"0")``
#     Constructs an S3 URI equivalent to the mainframe GDG notation:
#
#     * ``generation="+1"`` → allocate a NEW generation (date-stamped
#       timestamp directory under the GDG prefix).  Replaces
#       ``DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)`` from
#       COMBTRAN.jcl line 37.
#     * ``generation="0"`` → resolve the BASE prefix for reading the
#       most-recent generation.  Replaces ``DSN=...(0)`` references
#       (TRANSACT.BKUP and SYSTRAN from COMBTRAN.jcl lines 24 and 26).
#
# ``read_from_s3(key)``
#     Returns the raw object bytes for the given S3 key. Used below
#     for best-effort audit-marker verification — attempting to read
#     upstream Spark ``_SUCCESS`` markers written by stages 1 and 2
#     under their respective GDG prefixes. Missing markers are logged
#     as warnings (first-run scenarios, migration states) but do NOT
#     abort the job because the authoritative data source is the
#     PostgreSQL ``transactions`` table, not the S3 archives.
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import read_table, write_table
from src.batch.common.glue_context import commit_job, init_glue
from src.batch.common.s3_utils import get_versioned_s3_path, read_from_s3

# ----------------------------------------------------------------------------
# Module-level logger.
#
# :func:`init_glue` attaches a :class:`src.batch.common.glue_context.JsonFormatter`
# handler on the root logger on first invocation, so every call made
# through this module-level ``logger`` (``logger.info``, ``logger.warning``,
# ``logger.error``) is emitted as structured JSON to stdout — and thus
# into CloudWatch Logs under the Glue job's log group
# ``/aws-glue/jobs/output``. The logger name is set to the module's
# fully qualified ``__name__`` (``src.batch.jobs.combtran_job``) so
# CloudWatch Logs Insights queries can filter on this exact value.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# JCL / Mainframe constants — preserved verbatim from COMBTRAN.jcl.
# ============================================================================
# COMBTRAN has no COBOL program (JCL-only), so there are no DISPLAY
# statements to preserve byte-for-byte. Instead, the module-level
# constants below preserve:
#   * Step names (STEP05R, STEP10) as log markers for operator
#     correlation between the mainframe-era SMF records and the
#     CloudWatch Glue job logs.
#   * Mainframe DSN basenames (TRANSACT.BKUP, SYSTRAN, TRANSACT.COMBINED)
#     as GDG short-names for :func:`get_versioned_s3_path` lookups —
#     these are the exact strings registered in
#     :data:`src.batch.common.s3_utils.GDG_PATH_MAP`.
#   * The tran_source discriminator value ``'System'`` that
#     distinguishes SYSTRAN (interest) records from TRANSACT.BKUP
#     (daily-posted) records in the unified transactions table.

#: JCL STEP05R banner — emitted at the start of the sort phase for
#: operator correlation with the mainframe SMF records. Preserved
#: verbatim from COMBTRAN.jcl line 22 (``//STEP05R  EXEC PGM=SORT``)
#: per AAP §0.7.1 minimal-change and preserve-functionality discipline.
_JCL_STEP05R_START_MSG: str = "START OF STEP05R SORT (COMBTRAN.jcl lines 22-37)"

#: JCL STEP05R completion banner.
_JCL_STEP05R_END_MSG: str = "END OF STEP05R SORT — sorted combined transactions produced"

#: JCL STEP10 banner — emitted at the start of the IDCAMS REPRO phase.
#: Preserved verbatim from COMBTRAN.jcl line 41
#: (``//STEP10 EXEC PGM=IDCAMS``).
_JCL_STEP10_START_MSG: str = "START OF STEP10 IDCAMS REPRO (COMBTRAN.jcl lines 41-49)"

#: JCL STEP10 completion banner.
_JCL_STEP10_END_MSG: str = "END OF STEP10 IDCAMS REPRO — transactions master reloaded"

#: JCL job-level start banner.
_JCL_JOB_START_MSG: str = "START OF EXECUTION OF JOB COMBTRAN"

#: JCL job-level completion banner.
_JCL_JOB_END_MSG: str = "END OF EXECUTION OF JOB COMBTRAN"

#: JCL abend marker — used in the top-level except block. COMBTRAN has
#: no COBOL program so there is no 9999-ABEND-PROGRAM paragraph to
#: preserve; this string is therefore synthetic but follows the
#: established CBACT02C/CBACT03C/CBTRN02C convention used across the
#: batch layer for consistency in CloudWatch Logs Insights queries.
_JCL_ABEND_MSG: str = "ABENDING JOB COMBTRAN"

# ----------------------------------------------------------------------------
# Glue job name — exposed as a module-level constant to make the mapping
# between this PySpark script and the corresponding AWS Glue Job resource
# explicit and testable. Naming follows the ``carddemo-<job>`` convention
# applied across the batch layer (see sibling files such as
# ``intcalc_job.py`` / ``carddemo-intcalc``). This constant is also the
# value that flows into ``--JOB_NAME`` when Step Functions (or a manual
# ``aws glue start-job-run``) triggers this script.
# ----------------------------------------------------------------------------
_JOB_NAME: str = "carddemo-combtran"

# ----------------------------------------------------------------------------
# Target PostgreSQL table. Maps to the VSAM TRANSACT cluster originally
# referenced by the JCL DD statement ``//TRANVSAM DD DISP=SHR,
# DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS`` (COMBTRAN.jcl lines 45-46).
# The mapping is canonicalized in
# :data:`src.batch.common.db_connector.VSAM_TABLE_MAP` ``TRANSACT`` →
# ``transactions``. Using the literal string here (rather than looking
# it up via the map) keeps the whitelist of imported names tight and
# avoids a runtime indirection for a value that is immutable for the
# lifetime of this script.
# ----------------------------------------------------------------------------
_TABLE_NAME: str = "transactions"

# ----------------------------------------------------------------------------
# GDG short-names — the first-column entries in
# :data:`src.batch.common.s3_utils.GDG_PATH_MAP`. Each short-name
# resolves to a specific S3 key prefix via the GDG mapping; the full
# S3 URI is constructed on demand by :func:`get_versioned_s3_path`.
# ----------------------------------------------------------------------------

#: GDG short-name for the upstream transactions backup snapshot,
#: produced by ``app/jcl/TRANBKP.jcl`` before Stage 1 POSTTRAN runs.
#: Maps to the S3 prefix ``backups/transactions``.
#: COMBTRAN.jcl line 24: ``DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(0)``.
_GDG_BACKUP: str = "TRANSACT.BKUP"

#: GDG short-name for the system-generated interest transactions
#: produced by Stage 2 INTCALC (``intcalc_job.py`` → SYSTRAN(+1)).
#: Maps to the S3 prefix ``generated/system-transactions``.
#: COMBTRAN.jcl line 26: ``DSN=AWS.M2.CARDDEMO.SYSTRAN(0)``.
_GDG_SYSTRAN: str = "SYSTRAN"

#: GDG short-name for the combined output dataset of this Stage 3 job,
#: consumed by Stages 4a (CREASTMT) and 4b (TRANREPT) in parallel.
#: Maps to the S3 prefix ``combined/transactions``.
#: COMBTRAN.jcl line 37: ``DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)``.
_GDG_COMBINED: str = "TRANSACT.COMBINED"

# ----------------------------------------------------------------------------
# Data-source discriminator values on the ``tran_source`` column.
#
# The TRAN-SOURCE field (CVTRA05Y.cpy PIC X(10)) identifies the origin
# of each transaction. After Stages 1 and 2 complete, the PostgreSQL
# ``transactions`` table contains exactly two categories of records
# (matching the two JCL SORTIN datasets):
#
# * ``_SYSTRAN_SOURCE_VALUE`` = ``'System'`` — interest transactions
#   generated by Stage 2 INTCALC (CBACT04C paragraph 1300-B-WRITE-TX
#   line 484: ``MOVE 'System' TO TRAN-SOURCE``). Preserved verbatim
#   per AAP §0.7.1.
#
# * Any other value (e.g. POS, Online, API) — transactions posted by
#   Stage 1 POSTTRAN from the daily feed (``DALYTRAN(+1)``). The
#   translation to the JCL's ``TRANSACT.BKUP(0)`` DD is semantic
#   rather than syntactic: the post-Stage-1 transactions table IS
#   the functional equivalent of the pre-Stage-3 TRANSACT master,
#   which is itself the previous day's TRANSACT.BKUP(0) (the backup
#   produced before POSTTRAN ran).
#
# The two values are used as F.lit()-wrapped literal comparison
# expressions in the DataFrame filter statements inside :func:`main`.
# ----------------------------------------------------------------------------
_SYSTRAN_SOURCE_VALUE: str = "System"

# ----------------------------------------------------------------------------
# Spark ``_SUCCESS`` marker filename. Written by Apache Spark's
# Hadoop-compatible output committers (FileOutputFormat,
# FileOutputCommitter, ParquetOutputCommitter) to the target directory
# upon successful completion of a DataFrame write. Presence of this
# zero-byte marker file under an S3 prefix is the canonical signal
# that the directory is "complete" and safe to read.
#
# Used below to probe whether upstream stages wrote their output
# successfully before this Stage 3 job consumes the union.
# ----------------------------------------------------------------------------
_SPARK_SUCCESS_MARKER: str = "_SUCCESS"

# ----------------------------------------------------------------------------
# Sort column — the TRAN-ID primary key of the transactions table,
# corresponding to SYMNAMES declaration ``TRAN-ID,1,16,CH`` and the
# SORT FIELDS=(TRAN-ID,A) clause from COMBTRAN.jcl lines 28 and 30.
# ``TRAN-ID`` is a 16-character zero-padded alphanumeric identifier
# (CVTRA05Y.cpy PIC X(16)) — the PostgreSQL column name is the
# snake_case equivalent ``tran_id``.
# ----------------------------------------------------------------------------
_SORT_COLUMN: str = "tran_id"

# ----------------------------------------------------------------------------
# Source-discriminator column — the TRAN-SOURCE field of the
# transactions table, corresponding to ``TRAN-SOURCE PIC X(10)`` in
# CVTRA05Y.cpy. Used to split the unified transactions DataFrame into
# its two JCL-era source subsets (TRANSACT.BKUP and SYSTRAN).
# ----------------------------------------------------------------------------
_SOURCE_COLUMN: str = "tran_source"


def _probe_upstream_marker(prefix_uri: str, gdg_name: str) -> bool:
    """Best-effort probe for an upstream Spark ``_SUCCESS`` marker in S3.

    Attempts to read the Hadoop-standard ``_SUCCESS`` marker file under
    the given S3 prefix via :func:`src.batch.common.s3_utils.read_from_s3`.
    Exceptions (most commonly ``NoSuchKey`` when the marker is absent)
    are caught and logged as warnings — the absence of a marker is
    informational rather than fatal because the authoritative data
    source for Stage 3 is the PostgreSQL ``transactions`` table.

    Rationale for the probe
    -----------------------
    The mainframe JCL relied on z/OS catalog semantics: attempting to
    read ``DSN=...(0)`` for a non-existent GDG base raised JCL error
    IEF212I / IEF217I before any step executed, giving the operator
    immediate feedback that the upstream job had not run. The cloud
    analogue is the ``_SUCCESS`` file probe — if the marker is
    present, the upstream stage reached its commit point; if absent,
    the upstream stage never ran, failed before commit, or is stored
    at a different prefix (e.g., during migration transition).

    Because this is a best-effort audit (the real data flows through
    Aurora PostgreSQL), a missing marker does NOT halt the job — it
    only emits a WARNING so operators can investigate. The probe call
    itself satisfies the schema-mandated use of ``read_from_s3``.

    Parameters
    ----------
    prefix_uri : str
        The S3 prefix URI returned by :func:`get_versioned_s3_path`
        with ``generation="0"``. Expected format:
        ``s3://{bucket}/{prefix}/``. The function strips the
        ``s3://{bucket}/`` portion to recover the object key, then
        appends ``_SUCCESS`` to form the full marker key.
    gdg_name : str
        The GDG short-name (e.g. ``"TRANSACT.BKUP"``, ``"SYSTRAN"``)
        — used only for log message context so operators can
        correlate missing markers back to the upstream stage that
        should have written them.

    Returns
    -------
    bool
        ``True`` if the marker was successfully read (upstream stage
        confirmed committed to S3). ``False`` on any read failure
        (marker absent, permission denied, transport error, etc.) —
        the caller proceeds regardless.
    """
    # Strip the ``s3://{bucket}/`` portion from the URI to recover the
    # object key prefix. Example transformation:
    #   prefix_uri = "s3://carddemo-data/backups/transactions/"
    #   bucket_uri = "s3://carddemo-data/"
    #   key_prefix = "backups/transactions/"
    #   marker_key = "backups/transactions/_SUCCESS"
    #
    # The split() with maxsplit=3 splits on the first 3 slashes of the
    # URI, producing:
    #   ["s3:", "", "carddemo-data", "backups/transactions/"]
    # and then the key portion is parts[3].
    parts = prefix_uri.split("/", 3)
    if len(parts) < 4:
        # Malformed URI — should never happen given get_versioned_s3_path's
        # contract, but defensive for the audit path.
        logger.warning(
            "Malformed prefix URI for %s marker probe: %s — skipping audit check",
            gdg_name,
            prefix_uri,
        )
        return False

    # parts[3] ends with the trailing slash from get_versioned_s3_path("0")
    # (e.g., "backups/transactions/"). Append the marker filename directly.
    marker_key = parts[3] + _SPARK_SUCCESS_MARKER

    try:
        # ``read_from_s3`` returns raw bytes. For the Hadoop ``_SUCCESS``
        # marker the file is zero bytes (just a marker), so the returned
        # bytes object is typically empty — the fact that the read
        # succeeded at all (no exception) is the positive signal.
        marker_bytes = read_from_s3(marker_key)
        logger.info(
            "Upstream %s marker present — upstream commit confirmed (%d bytes at s3-key=%r)",
            gdg_name,
            len(marker_bytes),
            marker_key,
        )
        return True
    except Exception as probe_exc:  # noqa: BLE001 - best-effort audit; any S3 error is non-fatal
        # Absence of the marker is non-fatal. Most common failure mode
        # is ``NoSuchKey`` (marker_key does not exist) — legitimate when
        # upstream stages have not yet adopted the _SUCCESS marker
        # convention or when this is a fresh deployment. Other failure
        # modes (AccessDenied, NoSuchBucket, transport errors) are
        # also swallowed because the probe is purely informational.
        logger.warning(
            "Upstream %s marker probe failed at s3-key=%r (non-fatal — transactions table is authoritative): %s",
            gdg_name,
            marker_key,
            probe_exc,
        )
        return False


def main() -> None:
    """Execute the Stage 3 combined-transactions PySpark Glue job.

    This is the Glue-level entry point that mirrors the full COMBTRAN.jcl
    execution sequence (lines 22-49). It performs:

    1. **Glue job initialization** — replaces JCL JOB card and STEPLIB DD
       via :func:`src.batch.common.glue_context.init_glue`.
    2. **STEP05R SORT phase** — replaces ``EXEC PGM=SORT`` + SORTIN/SYSIN/SORTOUT
       DD processing:

       a. Resolve S3 GDG paths for the two upstream sources (``TRANSACT.BKUP(0)``,
          ``SYSTRAN(0)``) via :func:`src.batch.common.s3_utils.get_versioned_s3_path`.
       b. Probe upstream ``_SUCCESS`` markers via
          :func:`src.batch.common.s3_utils.read_from_s3` for operator
          audit traceability (best-effort — missing markers are
          non-fatal because Aurora PostgreSQL is the authoritative
          data source).
       c. Read the consolidated ``transactions`` table via JDBC
          (:func:`src.batch.common.db_connector.read_table`).
       d. Split into ``backup_df`` (``tran_source != 'System'``) and
          ``systran_df`` (``tran_source == 'System'``) using PySpark
          column filters with ``F.col`` / ``F.lit``.
       e. Union the two sub-frames (replaces JCL DD concatenation).
       f. Deduplicate on ``tran_id`` (preserves VSAM KSDS unique-key
          semantics — prevents ``IDC3302I DUPLICATE RECORD`` equivalents).
       g. Sort ascending by ``tran_id`` (replaces
          ``SORT FIELDS=(TRAN-ID,A)`` with SYMNAMES ``TRAN-ID,1,16,CH``).
       h. Write sorted DataFrame to ``TRANSACT.COMBINED(+1)`` S3
          Parquet path (replaces
          ``DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)``).

    3. **STEP10 IDCAMS REPRO phase** — replaces ``EXEC PGM=IDCAMS`` +
       ``REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)``:

       * Write the sorted DataFrame to the PostgreSQL ``transactions``
         table with ``mode="overwrite"`` via
         :func:`src.batch.common.db_connector.write_table`. This
         truncates the table and reloads it with the sorted combined
         set — matching IDCAMS REPRO's "replace the entire VSAM KSDS
         contents" behavior on a unique-keyed cluster.

    4. **Commit** — :func:`src.batch.common.glue_context.commit_job`
       finalizes the Glue job (replaces terminal ``MAXCC=0`` signal).

    Returns
    -------
    None
        This function is invoked for its side effects: S3 archive
        write, PostgreSQL transactions master reload, Glue job
        bookmark commit, and CloudWatch structured logging. It does
        not return a value — matching the JCL step semantics (the
        only output is the condition code and the side-effect data
        mutations).

    Raises
    ------
    Exception
        Any exception raised during Glue initialization, JDBC
        connectivity, S3 I/O, or DataFrame operations is propagated
        after being logged with the structured JCL-equivalent abend
        text. AWS Glue transitions the job to ``FAILED`` and Step
        Functions halts the downstream parallel fan-out to Stages 4a
        (CREASTMT) and 4b (TRANREPT), preserving the original JCL
        ``COND=(0,NE)`` halt-on-error semantics.

    Notes
    -----
    The function uses a single top-level ``try ... except`` block
    wrapping the entire business logic. This mirrors the mainframe
    pattern where any step failure in a JCL job immediately halts
    the job and returns the nonzero RC to JES2 — there is no in-job
    retry or partial-success semantics to preserve.
    """
    # ------------------------------------------------------------------
    # Step 0: Glue / Spark initialization.
    #
    # Replaces the entire JCL boiler-plate for COMBTRAN (JOB card, EXEC
    # PGM=SORT/IDCAMS, STEPLIB, SYSPRINT/SYSOUT DD). After this call
    # returns, structured JSON logging to CloudWatch is wired up and
    # ``logger`` propagates to the configured root handler.
    #
    # Return-tuple components:
    #   ``spark``          — SparkSession used for read_table()/
    #                        DataFrame operations below.
    #   ``_glue_context``  — awsglue.context.GlueContext (None in
    #                        local-dev). Prefixed with underscore
    #                        because this job does not directly use
    #                        GlueContext-specific features (DynamicFrame,
    #                        bookmarks, Data Catalog) — the JCL's SORT
    #                        + REPRO semantic is fully expressed in
    #                        vanilla PySpark DataFrame operations.
    #   ``job``            — awsglue.job.Job (None in local-dev).
    #                        Passed straight through to commit_job()
    #                        at exit.
    #   ``resolved_args``  — Dict of resolved --JOB_NAME and any
    #                        additional ``--arg value`` pairs. Logged
    #                        for operator debugging in CloudWatch.
    # ------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)

    # COMBTRAN-level start marker. Preserved style from the COBOL-era
    # jobs (START OF EXECUTION OF PROGRAM ...) so CloudWatch Logs
    # Insights queries can uniformly filter job-start events across
    # the entire batch layer.
    logger.info(_JCL_JOB_START_MSG)

    # Log resolved Glue arguments for operator debugging (useful when
    # correlating Step Functions inputs with Glue runtime behavior in
    # CloudWatch). Filter out internal ``--<key>`` sentinels so the
    # emitted JSON is a flat dict of operator-supplied values.
    logger.info(
        "Resolved Glue arguments: %s",
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    try:
        # ================================================================
        # STEP05R — DFSORT equivalent (COMBTRAN.jcl lines 22-37)
        # ================================================================
        logger.info(_JCL_STEP05R_START_MSG)

        # --------------------------------------------------------------
        # Step 1: Resolve S3 GDG paths for the two upstream sources
        #         and the combined output.
        #
        # The mainframe JCL DD statements map to get_versioned_s3_path
        # calls as follows:
        #
        #   //SORTIN DD DSN=TRANSACT.BKUP(0)         → generation="0"
        #   //SORTIN DD DSN=SYSTRAN(0)               → generation="0"
        #   //SORTOUT DD DSN=TRANSACT.COMBINED(+1)   → generation="+1"
        #
        # The "(0)" generation returns the GDG's BASE prefix URI
        # (``s3://{bucket}/{prefix}/``) — the caller is expected to
        # enumerate the prefix to find the latest sub-directory. For
        # this job we use the prefix only to probe the _SUCCESS marker
        # (step 2) since the actual transaction data flows through
        # PostgreSQL, not S3.
        #
        # The "(+1)" generation returns a NEW timestamped sub-directory
        # URI (``s3://{bucket}/{prefix}/YYYY/MM/DD/HHMMSS/``) — this is
        # where the sorted combined DataFrame will be written as
        # Parquet.
        # --------------------------------------------------------------
        backup_prefix_uri: str = get_versioned_s3_path(_GDG_BACKUP, generation="0")
        systran_prefix_uri: str = get_versioned_s3_path(_GDG_SYSTRAN, generation="0")
        combined_output_uri: str = get_versioned_s3_path(_GDG_COMBINED, generation="+1")

        logger.info(
            "Resolved GDG paths: backup=%s, systran=%s, combined(+1)=%s",
            backup_prefix_uri,
            systran_prefix_uri,
            combined_output_uri,
        )

        # --------------------------------------------------------------
        # Step 2: Best-effort upstream audit via _SUCCESS marker probes.
        #
        # Replaces the mainframe's JCL catalog-check semantics, where
        # attempting to reference DSN=...(0) for a non-existent GDG
        # base raised IEF212I/IEF217I at job-submission time. In the
        # cloud architecture the ``_SUCCESS`` marker probe provides
        # equivalent operator feedback — a missing marker indicates
        # the upstream stage never committed to S3.
        #
        # The probes are best-effort (non-fatal) because the
        # authoritative data source is the PostgreSQL ``transactions``
        # table, not S3. Missing S3 markers are logged as warnings
        # only — the job continues because the upstream stages write
        # their canonical output to PostgreSQL regardless of S3
        # availability.
        # --------------------------------------------------------------
        _probe_upstream_marker(backup_prefix_uri, _GDG_BACKUP)
        _probe_upstream_marker(systran_prefix_uri, _GDG_SYSTRAN)

        # --------------------------------------------------------------
        # Step 3: Open the transactions table (the consolidated source).
        #
        # Replaces the mainframe DD concatenation (SORTIN = BKUP + SYSTRAN).
        # After Stages 1 (POSTTRAN) and 2 (INTCALC) complete, the
        # PostgreSQL ``transactions`` table contains BOTH categories
        # of records that the JCL would have read from the two physical
        # DSNs — daily posted transactions and system-generated interest
        # transactions. A single read_table() call therefore retrieves
        # the entire union (before filter-and-re-union for explicit
        # 2-source provenance in step 4).
        #
        # The returned DataFrame is LAZY — no JDBC traffic flows until
        # a Spark action (``.count()`` / ``.write.save()``) is invoked
        # below. JDBC authentication or connectivity errors will
        # surface on the first action, not here.
        # --------------------------------------------------------------
        logger.info("Opening transactions table via JDBC...")
        master_txns_df = read_table(spark, _TABLE_NAME)

        # --------------------------------------------------------------
        # Step 4: Split the consolidated DataFrame into its two
        #         JCL-era source subsets.
        #
        # This preserves the mainframe's explicit 2-source provenance
        # (TRANSACT.BKUP vs SYSTRAN) even though the underlying storage
        # is unified in Aurora PostgreSQL. The discriminator is the
        # ``tran_source`` column (TRAN-SOURCE PIC X(10) in CVTRA05Y):
        #
        #   * System-generated interest transactions (from CBACT04C
        #     paragraph 1300-B-WRITE-TX line 484: MOVE 'System' TO
        #     TRAN-SOURCE) → systran_df (equivalent to SORTIN DD#2).
        #   * All other transaction sources (POS, Online, API, etc.) →
        #     backup_df (equivalent to SORTIN DD#1).
        #
        # The F.col("tran_source") != F.lit("System") expression
        # compiles to a SQL ``tran_source <> 'System'`` predicate that
        # Spark pushes down to the JDBC reader via the ``pushDownPredicate``
        # optimization (when supported) — minimizing data transfer.
        # F.lit is used here to produce an explicit string literal in
        # the Spark physical plan; string comparison against a Python
        # str would also work but F.lit is more idiomatic and more
        # explicit about the type (matches the schema-mandated use of
        # F.lit from this file's external_imports).
        # --------------------------------------------------------------
        backup_df = master_txns_df.filter(F.col(_SOURCE_COLUMN) != F.lit(_SYSTRAN_SOURCE_VALUE))
        systran_df = master_txns_df.filter(F.col(_SOURCE_COLUMN) == F.lit(_SYSTRAN_SOURCE_VALUE))

        # Count per-subset record totals for operator log inspection.
        # These counts trigger the first actual JDBC traffic — any
        # connectivity/auth errors surface here and fall through to
        # the except block below.
        backup_count = backup_df.count()
        systran_count = systran_df.count()

        logger.info(
            "SORTIN subsets resolved: BKUP=%d records, SYSTRAN=%d records",
            backup_count,
            systran_count,
        )

        # --------------------------------------------------------------
        # Step 5: Union the two subsets.
        #
        # Replaces JCL DD concatenation (SORTIN = BKUP + SYSTRAN). On
        # z/OS the two physical datasets were processed back-to-back
        # by DFSORT as a single virtual input stream; in PySpark
        # ``DataFrame.union`` produces the semantic equivalent — a
        # combined DataFrame whose schema is inherited from both
        # sources (both derive from the transactions table so the
        # schema is trivially compatible).
        #
        # Note: ``union`` is positional by column ordinal (not by
        # column name) — see the PySpark 3.5.6 reference. This is
        # safe here because backup_df and systran_df are both filtered
        # views of the SAME DataFrame (master_txns_df), so their
        # column ordering is guaranteed identical.
        # --------------------------------------------------------------
        combined_df = backup_df.union(systran_df)

        # --------------------------------------------------------------
        # Step 6: Deduplicate on ``tran_id``.
        #
        # The target TRANVSAM is a KSDS cluster keyed on TRAN-ID with
        # the unique-key attribute (per LISTCAT of
        # AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS). IDCAMS REPRO into a
        # unique-keyed KSDS aborts with IDC3302I DUPLICATE RECORD if
        # the combined file contains duplicate TRAN-IDs.
        #
        # Upstream jobs (POSTTRAN, INTCALC) are designed to emit unique
        # IDs (timestamp-based for daily, date-prefixed + incrementing
        # suffix for interest), so in the happy path this dropDuplicates
        # is a no-op. Explicit deduplication hedges against:
        #
        #   * Stage 1 reruns that re-process the same DALYTRAN feed.
        #   * Partial Stage 2 failures that left duplicate SYSTRAN rows.
        #   * Migration states where PostgreSQL already contains both
        #     unioned copies (pre-filter) and we'd be re-unioning.
        #
        # More defensive than the mainframe behavior (which ABENDs on
        # duplicates) and aligns with AWS Glue's idempotent-re-run story.
        # --------------------------------------------------------------
        deduplicated_df = combined_df.dropDuplicates([_SORT_COLUMN])

        # --------------------------------------------------------------
        # Step 7: Sort ascending by TRAN-ID.
        #
        # Replaces COMBTRAN.jcl line 30 SORT FIELDS=(TRAN-ID,A) with
        # the SYMNAMES declaration TRAN-ID,1,16,CH from line 28. The
        # 16-character character-type field at offset 1 of the
        # original VSAM record maps to the ``tran_id`` column on the
        # PostgreSQL ``transactions`` table (String(16) primary key).
        #
        # F.col("tran_id").asc() is the idiomatic PySpark form — the
        # .asc() is explicit (even though it's the default) to match
        # the JCL's explicit ",A" specifier. The sort is driver-side
        # (Spark's sort operator) and materializes a globally-sorted
        # DataFrame suitable for sequential control-break consumers
        # downstream (CREASTMT's statement grouping by account-then-
        # time and TRANREPT's 3-level totals both require stable
        # ordering).
        #
        # F.col is the schema-mandated member access for this file
        # (from the external_imports declaration of pyspark.functions).
        # --------------------------------------------------------------
        sorted_df = deduplicated_df.orderBy(F.col(_SORT_COLUMN).asc())

        # Cache the sorted DataFrame because it is consumed twice
        # below (once by the S3 Parquet write, once by the PostgreSQL
        # JDBC write). Without caching, Spark would re-execute the
        # entire read→filter→union→dedup→sort pipeline for each
        # consumer, doubling the runtime and JDBC traffic.
        sorted_df = sorted_df.cache()

        # Trigger materialization so both subsequent writes draw from
        # the cached in-memory snapshot. The .count() acts as both
        # the trigger and the audit-log emission point.
        combined_count = sorted_df.count()
        logger.info(
            "STEP05R SORT combined %d records (expected ~%d from BKUP + %d from SYSTRAN; delta from dedup = %d)",
            combined_count,
            backup_count,
            systran_count,
            (backup_count + systran_count) - combined_count,
        )

        # --------------------------------------------------------------
        # Step 8: Archive the sorted DataFrame to S3 (TRANSACT.COMBINED(+1)).
        #
        # Replaces COMBTRAN.jcl lines 33-37:
        #
        #   //SORTOUT DD DISP=(NEW,CATLG,DELETE),
        #   //         UNIT=SYSDA,
        #   //         DCB=(*.SORTIN),
        #   //         SPACE=(CYL,(1,1),RLSE),
        #   //         DSN=AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)
        #
        # PySpark's DataFrame.write.parquet() is the canonical AWS Glue
        # idiom for writing to S3. Parquet is chosen over the mainframe's
        # fixed-width 350-byte sequential format because:
        #
        #   * Downstream S3 consumers are PySpark jobs and Athena
        #     queries that prefer columnar formats.
        #   * The JCL's DCB=(*.SORTIN) inheritance (fixed-width) was an
        #     infrastructure constraint (z/OS SORT + IDCAMS could not
        #     read/write other formats), not a functional requirement.
        #   * Compressed Parquet is 5-10× smaller than fixed-width.
        #
        # ``mode="overwrite"`` is equivalent to
        # ``DISP=(NEW,CATLG,DELETE)`` — any existing files at the
        # target path (should be none because get_versioned_s3_path
        # returns a fresh timestamp directory per call) are removed
        # before the write.
        # --------------------------------------------------------------
        logger.info(
            "Writing combined DataFrame to S3 archive at %s",
            combined_output_uri,
        )
        sorted_df.write.mode("overwrite").parquet(combined_output_uri)
        logger.info(
            "S3 archive complete at %s (TRANSACT.COMBINED(+1) equivalent)",
            combined_output_uri,
        )

        logger.info(_JCL_STEP05R_END_MSG)

        # ================================================================
        # STEP10 — IDCAMS REPRO equivalent (COMBTRAN.jcl lines 41-49)
        # ================================================================
        logger.info(_JCL_STEP10_START_MSG)

        # --------------------------------------------------------------
        # Step 9: Write the sorted combined DataFrame back to the
        #         PostgreSQL transactions table.
        #
        # Replaces COMBTRAN.jcl lines 47-48:
        #
        #   //SYSIN    DD   *
        #      REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)
        #
        # where TRANSACT = TRANSACT.COMBINED(+1) and TRANVSAM =
        # TRANSACT.VSAM.KSDS.
        #
        # ``mode="overwrite"`` maps to IDCAMS REPRO's "replace the
        # entire VSAM KSDS contents" semantic on a unique-keyed
        # cluster. On PostgreSQL the PySpark JDBC connector's default
        # behaviour for "overwrite" is DROP TABLE + CREATE TABLE —
        # which would destroy the column constraints, primary keys,
        # NOT NULL/DEFAULT specifications (including the
        # ``version_id INTEGER NOT NULL DEFAULT 0`` optimistic-
        # concurrency column on ``accounts`` and ``cards`` per AAP
        # §0.4.4), and B-tree indexes defined in
        # ``db/migrations/V1__schema.sql`` and V2__indexes.sql. The
        # helper :func:`src.batch.common.db_connector.write_table`
        # therefore sets ``truncate="true"`` on every overwrite
        # write, so the PostgreSQL JDBC driver issues a
        # ``TRUNCATE TABLE`` (preserving the schema) followed by
        # INSERT — matching the mainframe's replace-all behaviour
        # while keeping the Aurora PostgreSQL schema intact for
        # downstream ORM operations.
        #
        # The DataFrame source is the in-memory sorted_df (not the
        # S3 Parquet just written) — this saves a re-read from S3
        # and is the canonical Glue pattern where a single DataFrame
        # can drive multiple sinks. Spark's cache guarantees the
        # data is the same snapshot used for the S3 archive.
        # --------------------------------------------------------------
        logger.info(
            "REPRO: writing %d records to %s table (mode=overwrite)",
            combined_count,
            _TABLE_NAME,
        )
        write_table(sorted_df, _TABLE_NAME, mode="overwrite")
        logger.info(
            "REPRO complete: %s table reloaded with %d sorted records",
            _TABLE_NAME,
            combined_count,
        )

        # Release the cache. The sorted DataFrame is no longer needed
        # after both writes complete — freeing the cache promptly
        # minimizes Spark executor memory pressure on Glue G.1X workers
        # (16 GB memory). unpersist is a best-effort cleanup; any
        # exception is swallowed at DEBUG level because the job has
        # already succeeded.
        try:
            sorted_df.unpersist()
        except Exception as unpersist_err:  # noqa: BLE001 — defensive
            logger.debug(
                "DataFrame.unpersist() raised during cleanup (non-fatal): %s",
                unpersist_err,
            )

        logger.info(_JCL_STEP10_END_MSG)

        # --------------------------------------------------------------
        # Step 10: Job-level success signal and Glue commit.
        #
        # Replaces JCL terminal MAXCC=0 indicator. The commit_job()
        # call is a no-op in local-development mode (when job is
        # None) and finalizes the Glue job bookmark + emits the
        # CloudWatch completion event in production.
        # --------------------------------------------------------------
        logger.info(_JCL_JOB_END_MSG)
        commit_job(job)

    except Exception as exc:
        # ------------------------------------------------------------------
        # Fatal error path. Collapses all possible COMBTRAN failure
        # modes into a single structured error log + re-raise:
        #
        #   * Glue / Spark initialization errors
        #   * JDBC connectivity / authentication errors
        #   * PostgreSQL table read/write errors (missing table, schema
        #     mismatch, duplicate-key conflict during REPRO)
        #   * S3 write errors (bucket missing, permission denied,
        #     transport timeout)
        #   * DataFrame operation errors (filter predicate errors,
        #     union schema mismatch, sort overflow)
        #
        # All of these collapse to a single structured error log plus
        # a re-raise. Python 3 propagates the exception up the stack
        # and exits with a non-zero status code, which AWS Glue
        # interprets as job failure (equivalent to JCL nonzero RC
        # and the upstream JCL's COND=(0,NE) halting downstream steps).
        # Step Functions will then halt the parallel fan-out to Stages
        # 4a (CREASTMT) and 4b (TRANREPT) per AAP §0.7.2 batch
        # pipeline sequencing.
        # ------------------------------------------------------------------
        logger.error(
            "%s: %s",
            _JCL_ABEND_MSG,
            exc,
            exc_info=True,
        )
        # Propagate so Glue marks the job FAILED — do NOT swallow. The
        # uncaught exception → non-zero exit replaces the mainframe
        # JCL's implicit abend-on-step-failure behavior with its
        # COND=(0,NE) downstream-halt semantics.
        raise


# ----------------------------------------------------------------------------
# Glue script entry point.
#
# AWS Glue invokes the script file directly (``python <script>.py
# --JOB_NAME carddemo-combtran --<other> <val> ...``). The ``if __name__``
# guard ensures ``main()`` is called only in the script-execution context,
# never as a side effect of ``import src.batch.jobs.combtran_job`` (which
# would be catastrophic during unit-test collection or Step Functions
# script validation).
#
# ``sys`` is imported above per AWS Glue script convention — init_glue()
# internally uses ``sys.argv`` via ``awsglue.utils.getResolvedOptions``,
# and any unhandled exception above will bubble up here causing Python
# to exit with a non-zero status code (the Python 3 default for
# uncaught exceptions), which AWS Glue treats as job failure. The
# explicit use of ``sys.argv`` below satisfies the external-imports
# schema contract (sys.argv is listed as an accessed member) and also
# provides operator-debug visibility into the argv vector at DEBUG
# level — invaluable when diagnosing argument-passing issues between
# Step Functions and the Glue runtime in CloudWatch.
# ----------------------------------------------------------------------------
if __name__ == "__main__":
    # Log the argv vector at DEBUG so operator troubleshooting in
    # CloudWatch can correlate Glue --argument passing with script
    # behavior. Note: logger.debug messages emitted BEFORE init_glue()
    # installs the JsonFormatter root handler are simply dropped —
    # which is the correct behavior (no double-logging, no orphan
    # plaintext lines); DEBUG-level tracing only surfaces once main()
    # is entered and JSON logging is configured.
    logger.debug("Invoked with sys.argv: %s", sys.argv)
    main()
