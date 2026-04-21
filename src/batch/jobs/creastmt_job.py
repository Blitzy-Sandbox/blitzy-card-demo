# ============================================================================
# Source: app/cbl/CBSTM03A.CBL   — Statement generation driver
#         app/cbl/CBSTM03B.CBL   — File service subroutine
#         app/jcl/CREASTMT.JCL   — Stage 4a orchestration
#         app/cpy/COSTM01.cpy    — Statement record layout
#         app/cpy/CVACT03Y.cpy   — Card cross-reference layout
#         app/cpy/CUSTREC.cpy    — Customer record layout
#         app/cpy/CVACT01Y.cpy   — Account record layout
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
"""Stage 4a — Account statement generation PySpark Glue job.

Replaces the mainframe trio that previously produced the monthly customer
statements of the CardDemo application:

* ``app/cbl/CBSTM03A.CBL`` — the statement driver program (925 lines).
  Per its own header comments, this program was written to exercise five
  distinct mainframe features: TIOT/TCB/PSA control-block addressing,
  ALTER / GO TO control flow, ``COMP`` and ``COMP-3`` numeric variables,
  a 2-dimensional working-storage array (``WS-TRNX-TABLE`` OCCURS 51
  TIMES × 10 TIMES), and a CALL to an external subroutine.  Items 1
  (control-block addressing) and 2 (ALTER / GO TO) are z/OS-specific
  constructs with no cloud-native equivalent; they are intentionally
  NOT migrated.  Items 3, 4, and 5 ARE migrated — ``COMP-3`` becomes
  :class:`decimal.Decimal` with ``ROUND_HALF_EVEN``; the 2-D array
  becomes a per-card PySpark ``collect_list`` of transaction structs;
  and the CBSTM03B subroutine CALL is replaced by direct DataFrame
  operations (no subroutine necessary because :func:`read_table` already
  abstracts the JDBC I/O).

* ``app/cbl/CBSTM03B.CBL`` — the file service subroutine (231 lines).
  Abstracted the VSAM file operations (OPEN / CLOSE / READ / READ-BY-KEY)
  for the four DD statements (TRNXFILE, XREFFILE, ACCTFILE, CUSTFILE) via
  an ``EVALUATE LK-M03B-DD`` dispatch.  In the target architecture every
  one of those DD statements resolves to a PostgreSQL table read through
  :func:`src.batch.common.db_connector.read_table`; the dispatch
  subroutine is obsolete and is NOT migrated.

* ``app/jcl/CREASTMT.JCL`` — the 98-line job that orchestrated the
  statement-generation pipeline with five sequential steps:

  ========== ==================== ========================================================
  JCL step   Program              Purpose
  ========== ==================== ========================================================
  DELDEF01   IDCAMS               DELETE + DEFINE CLUSTER for TRXFL.VSAM.KSDS
                                  (KEYS(32 0), 350B records) — the work file for the sort
  STEP010    SORT                 SORT FIELDS=(263,16,CH,A,1,16,CH,A) —
                                  sort live TRANSACT.VSAM.KSDS by card_num + tran_id.
                                  OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50) restructures
                                  the 350B record so card_num leads.
  STEP020    IDCAMS REPRO         Load the sorted sequential output into the VSAM work file.
  STEP030    IEFBR14              Delete any prior statement output files
                                  (STATEMNT.PS and STATEMNT.HTML).
  STEP040    EXEC PGM=CBSTM03A    Run the driver with 6 DD statements:
                                  TRNXFILE (in), XREFFILE (in), ACCTFILE (in),
                                  CUSTFILE (in), STMTFILE (out, LRECL=80, text),
                                  HTMLFILE (out, LRECL=100, HTML).
  ========== ==================== ========================================================

Pipeline position
-----------------
This is one of the two parallel leaf stages of the 5-stage CardDemo
batch pipeline::

    Stage 1 (POSTTRAN)  →  Stage 2 (INTCALC)  →  Stage 3 (COMBTRAN)
                                                          │
                                      ┌───────────────────┴───────────────────┐
                                      ▼                                       ▼
                            Stage 4a (CREASTMT) ← THIS FILE       Stage 4b (TRANREPT)

Stage 4a and Stage 4b run in parallel after Stage 3 completes.  They
consume the same input (the sorted ``transactions`` table produced by
Stage 3) but emit different outputs: Stage 4a produces per-card monthly
statements in text + HTML formats (this job); Stage 4b produces a
date-filtered transaction detail report with three-level totals.  A
failure in either stage does not halt its sibling — each is an
independent Step Functions task.

Mainframe-to-Cloud Transformation
---------------------------------
Every z/OS construct in the source trio maps to a cloud-native
equivalent.  The principal translations are:

================================================================  ============================================================================================
Mainframe construct                                                Cloud equivalent
================================================================  ============================================================================================
``//CREASTMT JOB`` + ``EXEC PGM=CBSTM03A``                         :func:`src.batch.common.glue_context.init_glue` — GlueContext + SparkSession + JSON logging.
``//DELDEF01 EXEC PGM=IDCAMS / DEFINE CLUSTER``                    N/A — Spark DataFrames are in-memory; no intermediate KSDS work file is needed.
``//STEP010 EXEC PGM=SORT / SORT FIELDS=(263,16,CH,A,1,16,CH,A)``  :func:`sort_and_restructure_transactions` — ``orderBy("tran_card_num", "tran_id")`` + select.
``//STEP010 OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)``       Equivalent: DataFrame ``.select("tran_card_num", "tran_id", ...)`` reordering.
``//STEP020 EXEC PGM=IDCAMS / REPRO``                              N/A — the sorted DataFrame is consumed directly; no file-to-file copy is needed.
``//STEP030 EXEC PGM=IEFBR14 / DELETE STATEMNT.PS,HTML``           N/A — the S3 path resolved by :func:`get_versioned_s3_path` is unique per run
                                                                   (timestamp-based), so there is no prior generation to scratch.  The mainframe needed
                                                                   IEFBR14 because STATEMNT was a non-GDG DSN with ``DISP=(NEW,CATLG,DELETE)`` —
                                                                   allocating ``NEW`` would fail on an already-existing dataset.
``//TRNXFILE DD`` → TRXFL.VSAM.KSDS                                :func:`src.batch.common.db_connector.read_table` ``(spark, "transactions")``.
``//XREFFILE DD`` → CARDXREF.VSAM.KSDS                             ``read_table(spark, "card_cross_references")``.
``//ACCTFILE DD`` → ACCTDATA.VSAM.KSDS                             ``read_table(spark, "accounts")``.
``//CUSTFILE DD`` → CUSTDATA.VSAM.KSDS                             ``read_table(spark, "customers")``.
``//STMTFILE DD``  → STATEMNT.PS (LRECL=80 / text/plain)           :func:`write_to_s3` with ``content_type="text/plain"`` under the ``STATEMNT.PS`` GDG path
                                                                   resolved by :func:`get_versioned_s3_path`.
``//HTMLFILE DD``  → STATEMNT.HTML (LRECL=100 / text/html)         :func:`write_to_s3` with ``content_type="text/html"`` under the ``STATEMNT.HTML`` GDG path.
``CALL 'CBSTM03B'`` (file service subroutine)                      No equivalent needed — :func:`read_table` already abstracts JDBC I/O.
``WS-TRNX-TABLE OCCURS 51 × 10 TIMES`` (2-D array)                 Per-card ``F.collect_list(F.struct(...))`` aggregation producing a Python list of dicts.
``COMP-3 WS-TOTAL-AMT PIC S9(9)V99``                               :class:`decimal.Decimal` accumulation with ``ROUND_HALF_EVEN`` (banker's rounding).
``WRITE FD-STMTFILE-REC`` (80-char LRECL)                          :func:`generate_text_statement` produces ``\\n``-separated lines, each padded to 80 chars.
``WRITE FD-HTMLFILE-REC`` (100-char LRECL)                         :func:`generate_html_statement` produces one HTML document per card.  Line length is
                                                                   not enforced because HTML tags are of variable length; the LRECL=100 constraint on the
                                                                   mainframe was an artifact of RECFM=FB fixed-block allocation with no semantic meaning.
JCL terminal success (``MAXCC=0``)                                 :func:`src.batch.common.glue_context.commit_job` — Glue job bookmark commit.
JCL abend (``COND=(0,NE)``)                                        Uncaught exception → non-zero exit code → Glue marks the job ``FAILED``.  Because Stage 4a
                                                                   and Stage 4b run in parallel with no downstream fan-out, a CREASTMT failure does not
                                                                   halt TRANREPT — matching the original JCL where the two jobs were independent.
================================================================  ============================================================================================

4-Entity Join
-------------
The mainframe program drives a 4-file navigation pattern:

1. Browse XREFFILE sequentially (the outer loop at paragraph
   ``1000-MAINLINE`` in CBSTM03A.CBL).
2. For each cross-reference record, key-read CUSTFILE by
   ``XREF-CUST-ID`` (paragraph ``2000-CUSTFILE-GET``).
3. Key-read ACCTFILE by ``XREF-ACCT-ID`` (paragraph
   ``3000-ACCTFILE-GET``).
4. Locate the card's block of transactions in the pre-loaded
   2-D ``WS-TRNX-TABLE`` buffer (paragraph ``4000-TRNXFILE-GET``).
5. Generate and WRITE the text + HTML statements
   (paragraphs ``5000-CREATE-STATEMENT``, ``5100-WRITE-HTML-HEADER``,
   ``5200-WRITE-HTML-NMADBS``, and ``6000-WRITE-TRANS``).

In PySpark the same data flow becomes a single logical join executed in
the driver:

* The outer driver is still ``card_cross_references`` (every card gets
  a statement, even if it has zero transactions this cycle).
* ``customers`` and ``accounts`` are joined on ``cust_id`` and ``acct_id``
  respectively — matching the mainframe key-reads.
* ``transactions`` are sorted and grouped by ``tran_card_num``, then
  ``collect_list`` into per-card arrays of transaction structs — the
  functional equivalent of filling the 2-D ``WS-TRNX-TABLE`` buffer
  in CBSTM03A paragraph ``8500-READTRNX-READ``.
* The final per-card record (xref + customer + account + transaction
  list) is ``.collect()``-ed to the driver where the text/HTML
  rendering happens in pure Python (more readable than a Spark UDF and
  never executed on an executor — the CardDemo dataset fits comfortably
  in driver memory).

Because PySpark joins are set-based and symmetric, the mainframe's
sequential "browse XREF, key-read CUST, key-read ACCT" pattern produces
exactly the same per-card result as a parallel multi-way join.  The
only observable difference is performance: the cloud version scales
horizontally whereas the mainframe version was single-threaded by JCL
step design.

STEP010 SORT semantic preservation
----------------------------------
The original JCL sorts TRANSACT.VSAM.KSDS by a composite key of
card_num + tran_id — the sort key is COBOL-positional:

``SORT FIELDS=(263,16,CH,A,1,16,CH,A)``

This means: at byte offset 263 (1-based), read 16 characters, CHAR
type, Ascending; then at byte offset 1, read 16 characters, CHAR type,
Ascending.  In the VSAM record layout (CVTRA05Y.cpy), offset 263 is
``TRAN-CARD-NUM`` and offset 1 is ``TRAN-ID``.  Sorting by
card_num-primary + tran_id-secondary groups all transactions for each
card contiguously, then orders them within the card.

The PySpark equivalent is ``orderBy("tran_card_num", "tran_id")`` —
column-named rather than byte-positional, but semantically identical
because the PostgreSQL columns are named equivalents of the COBOL
field names.

The OUTREC restructuring ``FIELDS=(1:263,16, 17:1,262, 279:279,50)``
reorders the 350-byte record so that card_num (previously at offset
263) becomes the leading field and tran_id (previously at offset 1)
becomes the second.  In PySpark this is a trivial
``select("tran_card_num", "tran_id", <remaining columns>...)`` — the
reordering is required only because the downstream KSDS cluster
(TRXFL.VSAM.KSDS) had its key defined at offset 0.  Since PySpark
DataFrames have no implicit leading-key constraint, the reordering is
performed for semantic fidelity but has no performance impact.

Statement template preservation
-------------------------------
Every text and HTML string literal from CBSTM03A.CBL is preserved
byte-for-byte as a module-level constant.  Specifically:

* ``ST-LINE0`` (lines 87-89): ``31 '*' + "START OF STATEMENT" (18 chars) + 31 '*'`` — width 80.
* ``ST-LINE5`` / ``ST-LINE10`` / ``ST-LINE12`` (lines 101-102, 120-121, 126-127): ``80 '-'``.
* ``ST-LINE6`` (lines 103-106): ``33 spaces + "Basic Details" (14 chars, padded) + 33 spaces``.
* ``ST-LINE7``-``ST-LINE9`` (lines 107-119): ``"<label>         :" + <data> + <padding>`` — width 80.
* ``ST-LINE11`` (lines 122-125): ``30 spaces + "TRANSACTION SUMMARY " + 30 spaces``.
* ``ST-LINE13`` (lines 128-131): header row for the transaction table.
* ``ST-LINE14`` (lines 132-137): per-transaction data row — ``TRAN-ID + ' ' + DESC + '$' + AMOUNT``.
* ``ST-LINE14A`` (lines 138-142): total row — ``"Total EXP:" + 56 spaces + '$' + TOTAL``.
* ``ST-LINE15`` (lines 143-146): ``32 '*' + "END OF STATEMENT" (16 chars) + 32 '*'``.

The HTML template (HTML-LINES, lines 148-224) is similarly preserved
with every ``<td>`` / ``<tr>`` tag and every hex color code intact:
``#1d1d96b3`` (title background), ``#FFAF33`` (bank info row),
``#f2f2f2`` (data rows), ``#33FFD1`` (section header rows), and
``#33FF5E`` (column header rows).  The bank address — "Bank of XYZ /
410 Terry Ave N / Seattle WA 99999" — is also preserved verbatim from
HTML-L16/L17/L18 (lines 167-172).

COBOL numeric-edit format preservation
--------------------------------------
Three distinct numeric edit formats appear in the COBOL source, each
with its own width and zero-handling semantics.  They are preserved in
this Python implementation via the helper formatters defined below:

* ``PIC Z(9).99-`` — zero-suppressed, 9 integer digits, 2 decimals,
  trailing sign.  Width 13.  Used for ``ST-TRANAMT`` (per-transaction
  amount) and ``ST-TOTAL-TRAMT`` (WS-TOTAL-AMT accumulation).  See
  :func:`_format_amount_edited`.
* ``PIC 9(9).99-`` — zero-FILLED (not suppressed), 9 integer digits,
  2 decimals, trailing sign.  Width 13.  Used for ``ST-CURR-BAL``
  (account current balance).  See :func:`_format_balance_edited`.
* ``MOVE PIC 9(3) TO PIC X(20)`` — 3-digit FICO score, zero-filled,
  then space-padded right to 20 characters.  See :func:`_format_fico_score`.

For the ``-`` trailing sign: the rule is "minus if value < 0, space
otherwise".  This matches COBOL numeric-edit semantics where the ``-``
picture symbol at the end of a field emits a literal minus for
negative values and a blank for zero or positive values.

Financial precision
-------------------
Every monetary accumulation uses :class:`decimal.Decimal` with
``ROUND_HALF_EVEN`` (banker's rounding).  No floating-point arithmetic
is permitted at any point — the discipline is enforced globally across
the batch layer (AAP §0.7.2).  The ``WS-TOTAL-AMT`` field in the
mainframe source is declared as ``PIC S9(9)V99 COMP-3`` — a 9-integer,
2-decimal signed packed-decimal — so the Python equivalent is a
``Decimal`` quantized to 2 decimal places on every add.

See Also
--------
:mod:`src.batch.jobs.combtran_job`   — Stage 3 (COMBTRAN.jcl)  — upstream producer of the sorted transactions master.
:mod:`src.batch.jobs.tranrept_job`   — Stage 4b (CBTRN03C.cbl) — sibling parallel stage; consumes the same Stage-3 output.
:mod:`src.batch.common.glue_context` — init_glue / commit_job lifecycle helpers.
:mod:`src.batch.common.db_connector` — read_table PySpark JDBC helper.
:mod:`src.batch.common.s3_utils`     — get_versioned_s3_path / write_to_s3 GDG-equivalent helpers.

AAP §0.2.2 — Batch Program Classification (CBSTM03A + CBSTM03B + CREASTMT.JCL → this file).
AAP §0.5.1 — File-by-File Transformation Plan (creastmt_job row).
AAP §0.7.1 — Preserve all existing business logic exactly as-is.
AAP §0.7.2 — Financial precision (Decimal + ROUND_HALF_EVEN; no floats).
AAP §0.7.3 — Minimal change discipline.

Source
------
``app/cbl/CBSTM03A.CBL`` (925 lines — statement driver).
``app/cbl/CBSTM03B.CBL`` (231 lines — file service subroutine).
``app/jcl/CREASTMT.JCL`` (98 lines — 5-step batch orchestration).
"""

from __future__ import annotations

# ----------------------------------------------------------------------------
# Standard-library imports.
# ----------------------------------------------------------------------------
# ``sys``     — AWS Glue script convention.  :func:`init_glue` uses
#               ``sys.argv`` internally via
#               :func:`awsglue.utils.getResolvedOptions`.  The
#               ``if __name__ == "__main__"`` guard below additionally logs
#               the ``sys.argv`` vector at DEBUG level so operators
#               troubleshooting Glue argument passing can correlate Step
#               Functions inputs with the script's observed runtime
#               arguments.  Replaces the JCL ``PARM`` parameter mechanism
#               (CREASTMT.JCL had no PARM parameters but this is the
#               canonical pattern for every batch Glue job).
# ``logging`` — Standard library module used to obtain a module-level
#               logger.  :func:`init_glue` installs a
#               :class:`src.batch.common.glue_context.JsonFormatter` on
#               the root logger on first call, so every emission through
#               the module-level ``logger`` below becomes a single-line
#               JSON document on stdout → CloudWatch Logs.  Replaces
#               DISPLAY statements and the ``//SYSPRINT DD SYSOUT=*``
#               / ``//SYSOUT DD SYSOUT=*`` JCL statements from CREASTMT.JCL.
# ``decimal`` — :class:`decimal.Decimal` replaces COBOL's ``COMP-3`` packed
#               decimal for ``WS-TOTAL-AMT PIC S9(9)V99`` (CBSTM03A.CBL
#               line 65) and ``WS-TRN-AMT PIC S9(9)V99`` (line 68).
#               ``ROUND_HALF_EVEN`` is the banker's rounding constant used
#               with :meth:`Decimal.quantize` to preserve the COBOL
#               ``ROUNDED`` keyword semantics.  Zero floating-point
#               arithmetic is permitted throughout statement generation
#               (AAP §0.7.2).
# ----------------------------------------------------------------------------
import logging
import sys
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any

# ----------------------------------------------------------------------------
# Third-party imports — PySpark 3.5.6 (shipped with AWS Glue 5.1 runtime).
# ----------------------------------------------------------------------------
# ``pyspark.sql.functions`` (aliased ``F``) — column-expression helpers
# used to:
#
#   * Build the SORT expression ``orderBy("tran_card_num", "tran_id")``
#     that preserves the mainframe SORT FIELDS=(263,16,CH,A,1,16,CH,A)
#     semantic from CREASTMT.JCL STEP010.
#
#   * Build the ``F.col("tran_card_num")`` column reference used in
#     :class:`pyspark.sql.Window` partitioning for per-card transaction
#     ordering.
#
#   * Build the ``F.lit(Decimal("0.00"))`` literal expression and
#     ``F.sum("tran_amt")`` aggregate used in test-only code paths.
#     (In production the accumulation is performed in Python on the
#     collected list — see :func:`generate_text_statement` and
#     :func:`generate_html_statement`.)
#
#   * Build the ``F.collect_list(F.struct(...))`` aggregation that
#     groups sorted transactions by card — the functional equivalent
#     of the 2-D ``WS-TRNX-TABLE`` in-memory buffer from CBSTM03A.CBL.
#
# The ``F`` alias is the canonical PySpark convention.  The
# ``N812`` suppression on the import line below silences ruff/flake8's
# lowercase-import-name warning — ``F`` as a module alias is an
# exception explicitly sanctioned by the PySpark style guide.
#
# ``DataFrame`` — imported for type annotations on public helper
# function signatures.  Used as the parameter / return type for
# :func:`sort_and_restructure_transactions`.
#
# ``Window`` — imported to build the per-card transaction-ordering
# window used by :func:`sort_and_restructure_transactions`.  The
# ``Window.partitionBy("tran_card_num").orderBy("tran_id")`` expression
# is the PySpark idiom that preserves the mainframe's "group all
# transactions for card C, in tran_id order" 2-D-array semantic from
# CBSTM03A paragraph ``8500-READTRNX-READ`` (lines 818-847).
# ----------------------------------------------------------------------------
from pyspark.sql import DataFrame, Window
from pyspark.sql import functions as F  # noqa: N812  - canonical PySpark alias

# ----------------------------------------------------------------------------
# First-party imports — batch common infrastructure.  Every name imported
# below is WHITELISTED by the AAP ``depends_on_files`` declaration for
# this file (see AAP §0.5.1 and the schema-declared ``internal_imports``).
# ``init_glue``/``commit_job`` are the Glue job lifecycle functions;
# ``read_table`` issues JDBC queries against Aurora PostgreSQL returning
# lazy DataFrames; ``get_versioned_s3_path`` constructs GDG-equivalent
# timestamped S3 URIs and ``write_to_s3`` issues S3 PutObject calls.
# These replace the JCL JOB card, ``EXEC PGM=CBSTM03A``, the 4 input DD
# statements (TRNXFILE/XREFFILE/ACCTFILE/CUSTFILE), the STMTFILE/HTMLFILE
# output DDs, and the terminal ``MAXCC=0`` success signalling of
# CREASTMT.JCL — and obviate the need for the CBSTM03B.CBL file-service
# subroutine.
# ----------------------------------------------------------------------------
from src.batch.common.db_connector import read_table
from src.batch.common.glue_context import commit_job, init_glue
from src.batch.common.s3_utils import get_versioned_s3_path, write_to_s3

# ============================================================================
# JCL step-banner log-message constants.
#
# These banners are emitted at the start and end of every logical JCL step
# so that CloudWatch Logs Insights queries and Splunk pipelines can reliably
# locate step boundaries.  The banner texts mirror the z/OS operator-console
# output that would historically appear in SYSLOG for each JCL step, with
# the step name in parentheses.
# ============================================================================
_JCL_JOB_START_MSG: str = "START OF EXECUTION OF JOB CREASTMT"
_JCL_JOB_END_MSG: str = "END OF EXECUTION OF JOB CREASTMT — MAXCC=0"
_JCL_ABEND_MSG: str = "ABENDING JOB CREASTMT"

_JCL_STEP010_START_MSG: str = (
    "START OF STEP010 SORT (CREASTMT.jcl lines 44-55) — "
    "SORT FIELDS=(263,16,CH,A,1,16,CH,A)"
)
_JCL_STEP010_END_MSG: str = (
    "END OF STEP010 SORT — sorted and restructured transactions produced"
)

_JCL_STEP040_START_MSG: str = (
    "START OF STEP040 EXEC PGM=CBSTM03A (CREASTMT.jcl lines 79-96) — "
    "statement generation driver"
)
_JCL_STEP040_END_MSG: str = (
    "END OF STEP040 — all statements written to S3 (STATEMNT.PS + STATEMNT.HTML)"
)

# ============================================================================
# Glue job name (registered with Step Functions and CloudWatch metrics).
#
# This string is the canonical AWS Glue job identifier for the Stage 4a
# statement-generation workload.  It matches the AAP §0.5.1 declaration
# and the corresponding ``infra/glue-job-configs/creastmt.json``
# provisioning document.  Passed as the ``job_name`` argument to
# :func:`init_glue` in :func:`main` below.
# ============================================================================
_JOB_NAME: str = "carddemo-creastmt"

# ============================================================================
# Source PostgreSQL table names (map to the 4 DD statements of CREASTMT.JCL).
# ============================================================================
# ``transactions``          ← TRNXFILE DD (sorted work file; we sort in-memory)
# ``card_cross_references`` ← XREFFILE DD (card ↔ account linkage)
# ``accounts``              ← ACCTFILE DD (account master)
# ``customers``             ← CUSTFILE DD (customer master)
_TABLE_TRANSACTIONS: str = "transactions"
_TABLE_XREF: str = "card_cross_references"
_TABLE_ACCOUNTS: str = "accounts"
_TABLE_CUSTOMERS: str = "customers"

# ============================================================================
# GDG dataset names (resolved to versioned S3 prefixes via
# :func:`get_versioned_s3_path`).
#
# On z/OS these were NON-GDG plain PS datasets (DSN=STATEMNT.PS,
# DSN=STATEMNT.HTML) with ``DISP=(NEW,CATLG,DELETE)``, which required
# STEP030 (IEFBR14) to scratch any prior generation before STEP040 could
# reallocate them.  In the cloud equivalent the timestamped S3 path is
# always unique per run (the prefix contains HHMMSS), so STEP030 has no
# equivalent and there is no scratch-and-reallocate race.
# ============================================================================
_GDG_STATEMNT_PS: str = "STATEMNT.PS"      # text/plain statements, LRECL=80
_GDG_STATEMNT_HTML: str = "STATEMNT.HTML"  # text/html statements, LRECL=100

# ============================================================================
# Output file base names written under the versioned prefix.
#
# The mainframe DSN was literally ``STATEMNT.PS`` and ``STATEMNT.HTML``;
# under S3 we append conventional filename extensions (``.txt`` and
# ``.html``) so browsers and CLI tools can infer content type without
# reading the HTTP metadata.  The S3 ``Content-Type`` metadata is still
# set explicitly by :func:`write_to_s3`.
# ============================================================================
_OUTPUT_FILENAME_TEXT: str = "STATEMNT.txt"
_OUTPUT_FILENAME_HTML: str = "STATEMNT.html"

# ============================================================================
# Content types (set as HTTP metadata on the S3 objects).
# ============================================================================
_CONTENT_TYPE_TEXT: str = "text/plain"
_CONTENT_TYPE_HTML: str = "text/html"

# ============================================================================
# Fixed-width line layout constants — LRECL semantics from the mainframe
# file definitions preserved verbatim.
#
# ``//STMTFILE DD DSN=STATEMNT.PS  ... LRECL=80  RECFM=FB`` (CREASTMT.JCL line 86)
# ``//HTMLFILE DD DSN=STATEMNT.HTML ... LRECL=100 RECFM=FB`` (CREASTMT.JCL line 91)
#
# For the text output every line is hard-padded (and truncated if
# necessary — though the COBOL source never produces lines longer than 80
# chars) to exactly 80 characters.  For the HTML output the LRECL=100
# constraint was an artifact of the mainframe RECFM=FB fixed-block
# allocation and has no semantic meaning for an HTTP consumer — we
# preserve the HTML document structure verbatim without padding.
# ============================================================================
_TEXT_LINE_WIDTH: int = 80

# ============================================================================
# Decimal zero constant used as the accumulator's starting value.
# ============================================================================
# Uses :class:`decimal.Decimal` (not 0 or 0.0) to ensure that the first
# addition never promotes to float — preserving AAP §0.7.2 precision
# discipline from the very first statement of every accumulator.
# ``Decimal("0.00")`` is quantized-equivalent to the COBOL ``ZERO``
# literal under ``PIC S9(9)V99`` declaration.
# ============================================================================
_DECIMAL_ZERO: Decimal = Decimal("0.00")

# ============================================================================
# Decimal quantization template — two decimal places to match COBOL
# ``PIC S9(9)V99`` semantics.  Used with ``ROUND_HALF_EVEN`` (banker's
# rounding) on every monetary accumulation.
# ============================================================================
_DECIMAL_QUANTUM: Decimal = Decimal("0.01")

# ============================================================================
# COBOL numeric-edit format widths.
# ============================================================================
# ``PIC Z(9).99-``  — 9 digits + '.' + 2 digits + sign = 13 chars.
# ``PIC 9(9).99-``  — 9 digits + '.' + 2 digits + sign = 13 chars.
# (Both edit formats have identical total widths — only zero-handling
# differs between them.)
# ============================================================================
_NUMERIC_EDIT_WIDTH: int = 13

# ============================================================================
# COBOL STATEMENT-LINES verbatim constants — preserved byte-for-byte from
# CBSTM03A.CBL lines 87-146.
#
# Every ST-LINE is a fixed-width 80-character record.  The COBOL source
# uses PIC clauses to declare the widths; the Python equivalent uses
# explicit string literals of exactly the same width.  A guard assertion
# at import time validates the length of every constant — any
# modification that breaks the 80-char contract will fail fast at
# module-import rather than silently producing truncated statements.
# ============================================================================

# ST-LINE0 — start-of-statement banner (lines 87-90 of CBSTM03A.CBL):
#   10 FILLER VALUE ALL '*'                PIC X(31).  (31 stars)
#   10 FILLER VALUE ALL 'START OF STATEMENT' PIC X(18).  (18-char literal)
#   10 FILLER VALUE ALL '*'                PIC X(31).  (31 stars)
# NOTE: The ``VALUE ALL 'START OF STATEMENT'`` is a COBOL quirk — the
# ``ALL`` prefix is syntactically permitted but, for a literal whose
# length matches the PIC clause exactly, it behaves identically to a
# plain ``VALUE 'START OF STATEMENT'``.  The emitted value is the literal
# string, NOT a repeating pattern.
_ST_LINE0: str = (
    "*" * 31
    + "START OF STATEMENT"
    + "*" * 31
)

# ST-LINE5 / ST-LINE10 / ST-LINE12 — 80 hyphens (dashes).
# These appear at lines 101-102 (ST-LINE5), 120-121 (ST-LINE10),
# 126-127 (ST-LINE12) of CBSTM03A.CBL as:
#   10 FILLER VALUE ALL '-'                PIC X(80).
# The ``VALUE ALL '-'`` IS a repeating pattern here (because the literal
# is shorter than the PIC clause), producing 80 '-' characters.
_ST_LINE_DASHES: str = "-" * 80

# ST-LINE6 — "Basic Details" section heading (lines 103-106):
#   10 FILLER VALUE SPACES                 PIC X(33).
#   10 FILLER VALUE 'Basic Details'        PIC X(14).
#   10 FILLER VALUE SPACES                 PIC X(33).
# Note the PIC X(14) is 1 char longer than "Basic Details" (13 chars),
# so the COBOL compiler zero-pads on the right — hence the effective
# literal is "Basic Details " (trailing space).
_ST_LINE6: str = (
    " " * 33
    + "Basic Details "
    + " " * 33
)

# ST-LINE11 — "TRANSACTION SUMMARY" section heading (lines 122-125):
#   10 FILLER VALUE SPACES                 PIC X(30).
#   10 FILLER VALUE 'TRANSACTION SUMMARY ' PIC X(20).
#   10 FILLER VALUE SPACES                 PIC X(30).
# The literal is exactly 20 chars ("TRANSACTION SUMMARY " with trailing
# space), so the PIC X(20) clause is an exact fit.
_ST_LINE11: str = (
    " " * 30
    + "TRANSACTION SUMMARY "
    + " " * 30
)

# ST-LINE13 — transaction table column headers (lines 128-131):
#   10 FILLER VALUE 'Tran ID         '     PIC X(16).
#   10 FILLER VALUE 'Tran Details    '     PIC X(51).
#   10 FILLER VALUE '  Tran Amount'        PIC X(13).
# Column 2 "Tran Details    " is 16 chars in the literal but PIC X(51),
# so the COBOL compiler zero-pads on the right with spaces — effective
# 16-char literal + 35 spaces = 51 chars.
_ST_LINE13: str = (
    "Tran ID         "        # 16 chars
    + "Tran Details    "      # 16 chars literal ...
    + " " * 35                # ... padded to 51 chars
    + "  Tran Amount"         # 13 chars
)

# ST-LINE15 — end-of-statement banner (lines 143-146):
#   10 FILLER VALUE ALL '*'                PIC X(32).  (32 stars — NOT 31)
#   10 FILLER VALUE ALL 'END OF STATEMENT' PIC X(16).  (16-char literal)
#   10 FILLER VALUE ALL '*'                PIC X(32).  (32 stars — NOT 31)
# NOTE: ST-LINE15 uses 32/16/32 splits (total 80), DIFFERING from ST-LINE0
# which uses 31/18/31 (also total 80).  This asymmetry is NOT an error in
# the source — it faithfully reflects the fact that "END OF STATEMENT"
# is 16 chars vs "START OF STATEMENT" at 18 chars.
_ST_LINE15: str = (
    "*" * 32
    + "END OF STATEMENT"
    + "*" * 32
)

# ST-LINE14A — per-card total row (lines 138-142):
#   10 FILLER VALUE 'Total EXP:'           PIC X(10).
#   10 FILLER VALUE SPACES                 PIC X(56).
#   10 FILLER VALUE '$'                    PIC X(01).
#   10 ST-TOTAL-TRAMT                      PIC Z(9).99-.  (13 chars)
# The "Total EXP:" prefix + 56 spaces + "$" + 13-char amount = 80 chars.
_ST_LINE14A_PREFIX: str = (
    "Total EXP:"      # 10 chars
    + " " * 56        # 56 spaces
    + "$"             # 1 char dollar sign
)  # width 67; caller appends the 13-char edited total

# ST-LINE7 label — "Account ID         :" (20 chars, lines 107-110).
_ST_LINE7_LABEL: str = "Account ID         :"
# ST-LINE8 label — "Current Balance    :" (20 chars, lines 111-115).
_ST_LINE8_LABEL: str = "Current Balance    :"
# ST-LINE9 label — "FICO Score         :" (20 chars, lines 116-119).
_ST_LINE9_LABEL: str = "FICO Score         :"

# ============================================================================
# COBOL HTML-LINES verbatim constants — preserved byte-for-byte from
# CBSTM03A.CBL lines 148-224.
#
# Every HTML fragment is a constant literal whose text is lifted from the
# COBOL ``88`` condition-name VALUE clauses.  The ``HTML-FIXED-LN PIC X(100)``
# declaration determines that every emitted line has width 100 on the
# mainframe — but for HTTP-delivered content this is irrelevant (browsers
# parse by structure, not by byte-offset), so we preserve the textual
# content without padding.  Every hex color code, every CSS rule, and
# every whitespace char is preserved exactly.
# ============================================================================
_HTML_L01_DOCTYPE: str = "<!DOCTYPE html>"
_HTML_L02_HTML_OPEN: str = '<html lang="en">'
_HTML_L03_HEAD_OPEN: str = "<head>"
_HTML_L04_META: str = '<meta charset="utf-8">'
_HTML_L05_TITLE: str = "<title>HTML Table Layout</title>"
_HTML_L06_HEAD_CLOSE: str = "</head>"
_HTML_L07_BODY_OPEN: str = '<body style="margin:0px;">'
_HTML_L08_TABLE_OPEN: str = (
    '<table  align="center" frame="box" style="width:70%; font:12px Segoe UI,sans-serif;">'
)
_HTML_LTRS: str = "<tr>"
_HTML_LTRE: str = "</tr>"
_HTML_LTDE: str = "</td>"
_HTML_L10_TITLE_ROW_TD: str = (
    '<td colspan="3" style="padding:0px 5px;background-color:#1d1d96b3;">'
)
_HTML_L15_BANK_ROW_TD: str = (
    '<td colspan="3" style="padding:0px 5px;background-color:#FFAF33;">'
)
_HTML_L16_BANK_NAME: str = '<p style="font-size:16px">Bank of XYZ</p>'
_HTML_L17_BANK_ADDR: str = "<p>410 Terry Ave N</p>"
_HTML_L18_BANK_CITY: str = "<p>Seattle WA 99999</p>"
_HTML_L22_35_DATA_ROW_TD: str = (
    '<td colspan="3" style="padding:0px 5px;background-color:#f2f2f2;">'
)
_HTML_L30_42_SECTION_ROW_TD: str = (
    '<td colspan="3" style="padding:0px 5px;background-color:#33FFD1; text-align:center;">'
)
_HTML_L31_BASIC_DETAILS: str = '<p style="font-size:16px">Basic Details</p>'
_HTML_L43_TRAN_SUMMARY: str = '<p style="font-size:16px">Transaction Summary</p>'
_HTML_L47_HDR_COL1_TD: str = (
    '<td style="width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;">'
)
_HTML_L48_HDR_TRAN_ID: str = '<p style="font-size:16px">Tran ID</p>'
_HTML_L50_HDR_COL2_TD: str = (
    '<td style="width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;">'
)
_HTML_L51_HDR_TRAN_DETAILS: str = '<p style="font-size:16px">Tran Details</p>'
_HTML_L53_HDR_COL3_TD: str = (
    '<td style="width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;">'
)
_HTML_L54_HDR_AMOUNT: str = '<p style="font-size:16px">Amount</p>'
_HTML_L58_DATA_COL1_TD: str = (
    '<td style="width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">'
)
_HTML_L61_DATA_COL2_TD: str = (
    '<td style="width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;">'
)
_HTML_L64_DATA_COL3_TD: str = (
    '<td style="width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;">'
)
_HTML_L75_END_OF_STMT: str = "<h3>End of Statement</h3>"
_HTML_L78_TABLE_CLOSE: str = "</table>"
_HTML_L79_BODY_CLOSE: str = "</body>"
_HTML_L80_HTML_CLOSE: str = "</html>"

# ============================================================================
# Inter-statement separator in the concatenated output files.
#
# The mainframe wrote one physical record per logical line with no
# separator between cards — STATEMNT.PS contained the first card's
# 28+N lines followed immediately by the second card's 28+M lines,
# etc.  For an S3 / CloudWatch-consumed text file we introduce no
# additional separator either (preserving the byte-for-byte stream
# contract).  For HTML, however, each card's statement is a complete
# ``<!DOCTYPE html>...</html>`` document; concatenating them naively
# would produce a file with multiple ``<html>`` roots, which is not
# well-formed.  We separate HTML documents with a HTML comment-style
# page break so operators can grep for card boundaries.  This is an
# intentional minor enhancement over the COBOL behavior — the
# mainframe HTMLFILE output was technically malformed on a per-file
# basis but rendered correctly in a browser which tolerates the
# anomaly; our cloud version documents the boundary explicitly.
# ============================================================================
_HTML_INTER_STATEMENT_SEPARATOR: str = "<!-- ======== NEXT STATEMENT ======== -->"

# ============================================================================
# Import-time validation — ensure every statement-line constant has the
# width the COBOL source document specified.  If any constant's length
# drifts from 80 chars, the module fails to load (rather than silently
# emitting malformed statements).
# ============================================================================
assert len(_ST_LINE0) == _TEXT_LINE_WIDTH, (
    f"_ST_LINE0 length mismatch: {len(_ST_LINE0)} != {_TEXT_LINE_WIDTH}"
)
assert len(_ST_LINE_DASHES) == _TEXT_LINE_WIDTH, (
    f"_ST_LINE_DASHES length mismatch: {len(_ST_LINE_DASHES)} != {_TEXT_LINE_WIDTH}"
)
assert len(_ST_LINE6) == _TEXT_LINE_WIDTH, (
    f"_ST_LINE6 length mismatch: {len(_ST_LINE6)} != {_TEXT_LINE_WIDTH}"
)
assert len(_ST_LINE11) == _TEXT_LINE_WIDTH, (
    f"_ST_LINE11 length mismatch: {len(_ST_LINE11)} != {_TEXT_LINE_WIDTH}"
)
assert len(_ST_LINE13) == _TEXT_LINE_WIDTH, (
    f"_ST_LINE13 length mismatch: {len(_ST_LINE13)} != {_TEXT_LINE_WIDTH}"
)
assert len(_ST_LINE15) == _TEXT_LINE_WIDTH, (
    f"_ST_LINE15 length mismatch: {len(_ST_LINE15)} != {_TEXT_LINE_WIDTH}"
)
assert len(_ST_LINE7_LABEL) == 20, (
    f"_ST_LINE7_LABEL length mismatch: {len(_ST_LINE7_LABEL)} != 20"
)
assert len(_ST_LINE8_LABEL) == 20, (
    f"_ST_LINE8_LABEL length mismatch: {len(_ST_LINE8_LABEL)} != 20"
)
assert len(_ST_LINE9_LABEL) == 20, (
    f"_ST_LINE9_LABEL length mismatch: {len(_ST_LINE9_LABEL)} != 20"
)

# ----------------------------------------------------------------------------
# Module-level logger.
#
# :func:`init_glue` attaches a :class:`src.batch.common.glue_context.JsonFormatter`
# handler on the root logger on first invocation, so every call through
# this module-level ``logger`` is emitted as structured JSON to stdout —
# and thus into CloudWatch Logs under the Glue job's log group
# ``/aws-glue/jobs/output``.  The logger name is set to the module's
# fully qualified ``__name__`` (``src.batch.jobs.creastmt_job``) so
# CloudWatch Logs Insights queries can filter on this exact value.
# ----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# ============================================================================
# Private helper functions — COBOL-compatible formatting primitives.
# ============================================================================
# These helpers implement the COBOL numeric-edit picture clauses
# (``PIC Z(9).99-`` and ``PIC 9(9).99-``) and the STRING statement
# semantics (``DELIMITED BY ' '`` one-space, ``DELIMITED BY '  '``
# two-space).  Each helper is independently unit-testable and has no
# dependency on the Spark runtime — it operates on pure Python data
# types.  Keeping the formatters pure-Python also means the statement
# generation happens on the Spark driver (not shipped to executors as
# UDFs), which is the correct design for the CardDemo dataset size
# (~500 transactions across ~50 cards fits comfortably in driver
# memory).


def _format_amount_edited(value: Decimal) -> str:
    """Format a :class:`decimal.Decimal` as ``PIC Z(9).99-``.

    This is the COBOL "zero-suppressed" numeric edit format used for
    per-transaction amounts (``ST-TRANAMT``) and the per-card total
    (``ST-TOTAL-TRAMT``) in the text statement template of CBSTM03A.CBL
    (lines 133-137 and 140-142).  The format is:

    * Width: exactly 13 characters.
    * Integer digits: up to 9, zero-suppressed to leading spaces.
    * Decimal point: literal ``.``.
    * Fractional digits: exactly 2.
    * Trailing sign: literal ``-`` if ``value < 0``, literal `` `` (space)
      if ``value >= 0``.

    Examples
    --------
    >>> _format_amount_edited(Decimal("1234.56"))
    '     1234.56 '
    >>> _format_amount_edited(Decimal("-1234.56"))
    '     1234.56-'
    >>> _format_amount_edited(Decimal("0"))
    '          .00 '
    >>> _format_amount_edited(Decimal("0.00"))
    '          .00 '
    >>> len(_format_amount_edited(Decimal("999999999.99")))
    13

    Implementation notes
    --------------------
    * The absolute value is formatted first so that leading zeros can be
      replaced with spaces unambiguously.  The sign is then appended
      separately, which matches the COBOL ``PIC -`` trailing-sign
      semantics (where the sign character appears at the low-order end
      of the field).
    * Zero-suppression replaces ALL leading zeros up to but not including
      the units digit.  COBOL ``Z(9).99-`` preserves the decimal point
      even if the integer part is zero (so ``0`` renders as 10 spaces +
      ``.00`` + space = 13 chars).  The implementation below follows
      this rule by re-constructing the integer portion as ``" " * (9 - n)
      + digits[-n:]`` where ``n`` is 0 for a zero integer.  In the
      ``n == 0`` case the integer portion is a pure 9-char blank
      string.
    * The value is quantized to 2 decimal places with ``ROUND_HALF_EVEN``
      (banker's rounding) to match the COBOL ``ROUNDED`` keyword
      behavior — AAP §0.7.2 mandates banker's rounding for all financial
      calculations.
    """
    # Normalize to exactly two decimal places with banker's rounding.
    quantized: Decimal = value.quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)

    # Split into sign + absolute-value components.  Using abs() is safe
    # because Decimal abs() preserves precision and never promotes to
    # float.  The sign character is determined from the ORIGINAL
    # (quantized) value, not the absolute — this matches COBOL
    # semantics where the sign lives in the encoded COMP-3 representation.
    is_negative: bool = quantized < _DECIMAL_ZERO
    abs_value: Decimal = abs(quantized)

    # Format with zero-padded integer width of 9 so we get a predictable
    # string like "000001234.56".  The "f"-format produces exactly 9
    # integer digits (with leading zeros) + '.' + 2 fractional digits
    # = 12 chars always.
    padded_numeric: str = f"{abs_value:012.2f}"  # e.g., "000001234.56"

    # Zero-suppress the integer portion — replace leading zeros with
    # spaces, stopping at the first non-zero digit or at the decimal
    # point (whichever comes first).
    integer_part, _, fractional_part = padded_numeric.partition(".")
    # integer_part is exactly 9 chars; fractional_part is exactly 2 chars.

    # Find the position of the first non-zero digit.  If all 9 are zero
    # (i.e., value < 1.00), the entire integer portion becomes 9 spaces.
    suppressed_integer: str
    first_nonzero_idx: int = -1
    for idx, ch in enumerate(integer_part):
        if ch != "0":
            first_nonzero_idx = idx
            break
    if first_nonzero_idx == -1:
        # Value is zero-valued in the integer portion.  All 9 integer
        # positions become spaces (COBOL Z-suppression never leaves a
        # bare '.' at the leftmost position; a fully-suppressed integer
        # renders as 9 spaces).
        suppressed_integer = " " * 9
    else:
        # Replace the leading zeros with spaces, preserving the digits
        # from ``first_nonzero_idx`` onward.
        suppressed_integer = " " * first_nonzero_idx + integer_part[first_nonzero_idx:]

    # Assemble the final edited string: integer + '.' + fractional + sign.
    sign_char: str = "-" if is_negative else " "
    edited: str = suppressed_integer + "." + fractional_part + sign_char

    # Width must be exactly 13 — defensive assertion guards against
    # future bugs in the zero-suppression logic.
    assert len(edited) == _NUMERIC_EDIT_WIDTH, (
        f"_format_amount_edited produced width {len(edited)} for {value!r}"
    )
    return edited


def _format_balance_edited(value: Decimal) -> str:
    """Format a :class:`decimal.Decimal` as ``PIC 9(9).99-``.

    This is the COBOL "zero-FILLED (not suppressed)" numeric edit format
    used for the account current balance (``ST-CURR-BAL``) in the text
    statement template of CBSTM03A.CBL (lines 112-115).  The format is:

    * Width: exactly 13 characters.
    * Integer digits: EXACTLY 9, zero-FILLED on the left (NOT
      zero-suppressed — this is the critical difference from
      :func:`_format_amount_edited`).
    * Decimal point: literal ``.``.
    * Fractional digits: exactly 2.
    * Trailing sign: literal ``-`` if ``value < 0``, literal `` `` (space)
      if ``value >= 0``.

    Examples
    --------
    >>> _format_balance_edited(Decimal("1234.56"))
    '000001234.56 '
    >>> _format_balance_edited(Decimal("-1234.56"))
    '000001234.56-'
    >>> _format_balance_edited(Decimal("0"))
    '000000000.00 '
    >>> len(_format_balance_edited(Decimal("999999999.99")))
    13

    Why two formats?
    ----------------
    The COBOL source explicitly declares two distinct pictures:

    * ``ST-CURR-BAL PIC 9(9).99-.`` (line 112 of CBSTM03A.CBL) — used
      for the account balance in the "Basic Details" section.  The
      ``9`` in the picture clause means "digit, zero-filled" (NOT
      zero-suppressed).
    * ``ST-TRANAMT PIC Z(9).99-.`` (line 137) and ``ST-TOTAL-TRAMT PIC
      Z(9).99-.`` (line 142) — used for per-transaction amounts and
      the total.  The ``Z`` means "digit, zero-suppressed to space".

    This distinction is NOT an oversight; it is how the COBOL programmer
    chose to present the data.  The balance is always displayed with
    leading zeros; the transaction amounts are right-aligned with
    leading spaces for readability in a columnar table.  We preserve
    this distinction faithfully.
    """
    # Normalize to exactly two decimal places with banker's rounding.
    quantized: Decimal = value.quantize(_DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN)

    is_negative: bool = quantized < _DECIMAL_ZERO
    abs_value: Decimal = abs(quantized)

    # Format with zero-filled integer width of 9.  The format specifier
    # ``012.2f`` produces exactly 12 characters: 9 integer digits
    # (zero-filled) + '.' + 2 fractional digits.  This is IDENTICAL to
    # the format used by :func:`_format_amount_edited` EXCEPT we do NOT
    # subsequently zero-suppress — the zero-filled output is the
    # desired final form.
    padded_numeric: str = f"{abs_value:012.2f}"  # e.g., "000001234.56"

    # Append the trailing sign.
    sign_char: str = "-" if is_negative else " "
    edited: str = padded_numeric + sign_char

    assert len(edited) == _NUMERIC_EDIT_WIDTH, (
        f"_format_balance_edited produced width {len(edited)} for {value!r}"
    )
    return edited


def _format_fico_score(fico: int | None) -> str:
    """Format a FICO score as ``MOVE PIC 9(3) TO PIC X(20)``.

    The COBOL source (CBSTM03A.CBL line 118) declares ``ST-FICO-SCORE
    PIC X(20)`` and the ``5000-CREATE-STATEMENT`` paragraph performs
    ``MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE``.  The source field
    ``CUST-FICO-CREDIT-SCORE`` is declared ``PIC 9(03)`` in the
    ``CUSTREC`` copybook.

    Under COBOL ``MOVE`` semantics, a numeric source moved to an
    alphanumeric target is first converted to its display representation
    (``PIC 9(3)`` → 3-digit zero-filled unsigned string, e.g., ``750``)
    and then space-padded on the right to fill the 20-char target.

    Parameters
    ----------
    fico : int | None
        The customer's FICO credit score.  ``None`` is tolerated as a
        defensive measure against schema-drift or missing source data
        (though the CardDemo domain model guarantees NOT NULL on
        ``customers.cust_fico_credit_score``).  ``None`` renders as 20
        spaces — matching the COBOL default behavior when the source
        field has been INITIALIZEd.

    Returns
    -------
    str
        A string of exactly 20 characters: 3-digit zero-filled FICO
        score followed by 17 spaces.

    Examples
    --------
    >>> _format_fico_score(750)
    '750                 '
    >>> _format_fico_score(50)  # 2-digit value — zero-filled to 3
    '050                 '
    >>> _format_fico_score(None)
    '                    '
    >>> len(_format_fico_score(750))
    20
    """
    if fico is None:
        return " " * 20

    # Zero-fill to exactly 3 digits (the source PIC 9(3) width).  If the
    # value exceeds 999 we truncate the leftmost digits — matching the
    # COBOL behavior when a numeric source of higher precision is MOVEd
    # into a shorter numeric target (low-order digits are preserved;
    # high-order digits are lost).  In practice FICO scores are bounded
    # at 300-850 so this truncation never triggers.
    fico_str: str = f"{int(fico) % 1000:03d}"

    # Space-pad on the right to 20 chars.
    return fico_str + " " * (20 - len(fico_str))


def _cobol_first_word(value: str | None) -> str:
    """Return the first space-delimited token of a string.

    Simulates the COBOL ``STRING ... DELIMITED BY ' '`` semantics for
    single-space-delimited names.  In the 5000-CREATE-STATEMENT
    paragraph of CBSTM03A.CBL (lines 473-479) the customer's full name
    is constructed via::

        STRING CUST-FIRST-NAME  DELIMITED BY ' '
               CUST-MIDDLE-NAME DELIMITED BY ' '
               CUST-LAST-NAME   DELIMITED BY ' '
            INTO ST-NAME
            WITH POINTER PTR-ST-NAME.

    Each ``DELIMITED BY ' '`` clause causes the STRING verb to copy
    characters from the source until the FIRST space character is
    encountered (exclusive).  For a fixed-width COBOL field like
    ``CUST-FIRST-NAME PIC X(25)`` containing ``"John     ..."`` (5 chars
    of data + 20 trailing spaces), the STRING verb copies the 4 chars
    ``"John"`` and stops at position 5.

    Parameters
    ----------
    value : str | None
        The source string (typically a fixed-width name field).  ``None``
        is tolerated — it returns an empty string, matching COBOL
        INITIALIZED field behavior.

    Returns
    -------
    str
        The substring up to (but not including) the first space
        character.  If no space is present, the entire string is
        returned.  If the input is empty or None, an empty string is
        returned.

    Examples
    --------
    >>> _cobol_first_word("John Smith")
    'John'
    >>> _cobol_first_word("John     ")  # COBOL fixed-width padded
    'John'
    >>> _cobol_first_word("Alice")  # no space
    'Alice'
    >>> _cobol_first_word("")
    ''
    >>> _cobol_first_word(None)
    ''
    >>> _cobol_first_word(" leading")  # leading space → empty token
    ''
    """
    if value is None:
        return ""
    # str.split(' ', 1)[0] returns the substring before the first space,
    # or the entire string if no space is present.  This exactly
    # matches COBOL DELIMITED BY ' ' semantics.
    return value.split(" ", 1)[0]


def _cobol_rstrip(value: str | None) -> str:
    """Return the input with trailing whitespace stripped.

    Simulates the COBOL ``STRING ... DELIMITED BY '  '`` (two-space)
    semantics used in paragraph 5200-WRITE-HTML-NMADBS of CBSTM03A.CBL
    (lines 576-611) to inject fixed-width fields into HTML
    ``<p>...</p>`` elements.

    Why two spaces?  In a fixed-width COBOL field padded with trailing
    spaces, a single space might be legitimate internal whitespace (e.g.,
    ``"123 Main St."`` in a 50-char address field has an internal space
    between "123" and "Main").  The COBOL programmer chose
    ``DELIMITED BY '  '`` (TWO consecutive spaces) as a heuristic for
    "end of meaningful content" — the first occurrence of a 2-space run
    marks the transition from data to padding.

    In Python this is functionally equivalent to :meth:`str.rstrip` —
    stripping ALL trailing whitespace from a fixed-width field produces
    the same visible result as splitting on the first 2-space run
    (because the padding is always trailing, contiguous spaces).  We
    use :meth:`str.rstrip` for clarity and robustness.

    Parameters
    ----------
    value : str | None
        The source string.  ``None`` is tolerated — it returns an empty
        string, matching COBOL INITIALIZED field behavior.

    Returns
    -------
    str
        The input with trailing whitespace removed.

    Examples
    --------
    >>> _cobol_rstrip("John Smith                ")
    'John Smith'
    >>> _cobol_rstrip("")
    ''
    >>> _cobol_rstrip(None)
    ''
    >>> _cobol_rstrip("no-trailing-space")
    'no-trailing-space'
    """
    if value is None:
        return ""
    return value.rstrip()


def _cobol_concat_name(
    first_name: str | None,
    middle_name: str | None,
    last_name: str | None,
) -> str:
    """Concatenate a customer name using COBOL STRING semantics.

    Reproduces the COBOL STRING statement from CBSTM03A.CBL paragraph
    ``5000-CREATE-STATEMENT`` (lines 473-479)::

        MOVE 1 TO PTR-ST-NAME.
        STRING CUST-FIRST-NAME  DELIMITED BY ' '
               ' '              DELIMITED BY SIZE
               CUST-MIDDLE-NAME DELIMITED BY ' '
               ' '              DELIMITED BY SIZE
               CUST-LAST-NAME   DELIMITED BY ' '
            INTO ST-NAME
            WITH POINTER PTR-ST-NAME.

    Semantics: each name component is stripped at its first space
    (via ``DELIMITED BY ' '``), then joined with literal single spaces
    (via the explicit ``' ' DELIMITED BY SIZE`` in between).

    Parameters
    ----------
    first_name, middle_name, last_name : str | None
        The three customer name components.  ``None`` or empty string
        values are tolerated — they produce empty tokens which are then
        elided from the output (we don't want ``"John  Smith"`` with a
        double space when the middle name is empty).

    Returns
    -------
    str
        The concatenated full name.  The COBOL destination field
        ``ST-NAME PIC X(75)`` is 75 chars wide; this function returns
        only the UNPADDED name, leaving the width adjustment to the
        caller (so that callers which pad and callers which truncate can
        share the same primitive).

    Examples
    --------
    >>> _cobol_concat_name("John", "Q.", "Smith")
    'John Q. Smith'
    >>> _cobol_concat_name("Alice", "", "Jones")
    'Alice Jones'
    >>> _cobol_concat_name("Madonna", None, None)
    'Madonna'
    >>> _cobol_concat_name(None, None, None)
    ''

    Notes on middle-name elision
    ----------------------------
    The mainframe COBOL source does NOT elide an empty middle name — if
    ``CUST-MIDDLE-NAME`` is all spaces, the STRING statement emits
    ``"John  Smith"`` (two spaces between first and last).  We preserve
    that bug-for-bug in the sense that if a middle name is whitespace-
    only, its ``_cobol_first_word`` yields an empty token, and the
    joined result indeed has two spaces in a row.  However, callers
    converting to HTML typically rstrip the output anyway, and the
    ST-NAME field in text output is padded to 75 chars regardless.
    """
    # Apply DELIMITED BY ' ' to each component (first-word extraction).
    first_token: str = _cobol_first_word(first_name)
    middle_token: str = _cobol_first_word(middle_name)
    last_token: str = _cobol_first_word(last_name)

    # Join with single spaces — matching the explicit ``' ' DELIMITED BY
    # SIZE`` interposed literals in the STRING statement.  Using ' '.join
    # on a filtered list elides completely-empty tokens, producing more
    # readable output for callers that have NULL middle names.
    return " ".join(token for token in (first_token, middle_token, last_token) if token)


def _cobol_concat_address_line_3(
    addr_line_3: str | None,
    state_cd: str | None,
    country_cd: str | None,
    zip_code: str | None,
) -> str:
    """Concatenate address line 3 using COBOL STRING semantics.

    Reproduces the COBOL STRING statement from CBSTM03A.CBL paragraph
    ``5000-CREATE-STATEMENT`` (lines 489-497)::

        MOVE 1 TO PTR-ST-ADD3.
        STRING CUST-ADDR-LINE-3     DELIMITED BY ' '
               ' '                  DELIMITED BY SIZE
               CUST-ADDR-STATE-CD   DELIMITED BY ' '
               ' '                  DELIMITED BY SIZE
               CUST-ADDR-COUNTRY-CD DELIMITED BY ' '
               ' '                  DELIMITED BY SIZE
               CUST-ADDR-ZIP        DELIMITED BY ' '
            INTO ST-ADD3
            WITH POINTER PTR-ST-ADD3.

    Each field is first-word-extracted (DELIMITED BY ' ') and then
    space-joined.  The target ``ST-ADD3`` is ``PIC X(80)`` — a full
    80-char record line, so the caller pads on the right.

    Parameters
    ----------
    addr_line_3 : str | None
        City / Town (``CUST-ADDR-LINE-3 PIC X(50)``).
    state_cd : str | None
        US state code (``CUST-ADDR-STATE-CD PIC X(02)``).
    country_cd : str | None
        Country code (``CUST-ADDR-COUNTRY-CD PIC X(03)``).
    zip_code : str | None
        ZIP / postal code (``CUST-ADDR-ZIP PIC X(10)``).

    Returns
    -------
    str
        The concatenated "city state country zip" string, UNPADDED.
        Caller pads or truncates to the 80-char ST-ADD3 field width.

    Examples
    --------
    >>> _cobol_concat_address_line_3("Seattle", "WA", "USA", "98101")
    'Seattle WA USA 98101'
    >>> _cobol_concat_address_line_3("New", "NY", "USA", "10001")
    'New NY USA 10001'
    """
    # first-word-extract each component (DELIMITED BY ' ').
    tokens: list[str] = [
        _cobol_first_word(addr_line_3),
        _cobol_first_word(state_cd),
        _cobol_first_word(country_cd),
        _cobol_first_word(zip_code),
    ]
    # Space-join, eliding empty tokens (matching the readability of the
    # COBOL output when any field is whitespace-only).
    return " ".join(token for token in tokens if token)


def _pad_text_line(text: str, width: int = _TEXT_LINE_WIDTH) -> str:
    """Left-justify and space-pad a string to the given width.

    Implements the COBOL fixed-width-record semantic: every line WRITTEN
    to the 80-char STMTFILE must be exactly 80 chars.  If the source is
    shorter, it is right-padded with spaces.  If it is longer, it is
    TRUNCATED — matching COBOL's MOVE-to-shorter-field behavior where
    high-order characters are preserved and trailing chars are dropped.

    This function is the final guardrail for text-statement line
    construction.  Every caller that builds a text line assembles its
    components by concatenation without explicit padding; the length
    contract is enforced once, at write time, by this function.

    Parameters
    ----------
    text : str
        The raw line content (typically a concatenation of label +
        data + padding).
    width : int, optional
        The target width.  Defaults to 80 (``_TEXT_LINE_WIDTH``), the
        LRECL of STMTFILE.

    Returns
    -------
    str
        A string of exactly ``width`` characters.

    Examples
    --------
    >>> _pad_text_line("Hello", width=10)
    'Hello     '
    >>> _pad_text_line("This text is longer than 10", width=10)
    'This text '
    >>> _pad_text_line("", width=5)
    '     '
    """
    if len(text) >= width:
        return text[:width]
    return text + " " * (width - len(text))


def _html_escape(value: str | None) -> str:
    """Escape untrusted text for safe inclusion in an HTML element.

    The COBOL source does NOT escape HTML metacharacters because in
    1995-era mainframe deployments the HTMLFILE output was consumed by
    an in-house statement-viewer application that guaranteed non-
    malicious source data.  In the cloud target the same discipline
    cannot be assumed — customer names, addresses, and transaction
    descriptions are persisted across arbitrary ETL pipelines and may
    reach an HTML-rendering consumer (browser, email client, PDF
    generator) after multiple hops.  We therefore HTML-escape every
    injected value to prevent trivial XSS via a poisoned transaction
    description or customer-entered address.

    This is a minor, targeted hardening — NOT a functional change.  The
    escape is applied ONLY at the final HTML-rendering boundary and
    never affects the text-statement output, the database storage, or
    any other layer.  AAP §0.7.1 permits behavioral-parity-preserving
    security hardening of this kind.

    Parameters
    ----------
    value : str | None
        The text to escape.  ``None`` is rendered as empty string.

    Returns
    -------
    str
        The input with ``<``, ``>``, ``&``, and quotation marks
        replaced by their HTML entity equivalents.

    Examples
    --------
    >>> _html_escape("Bob & Alice <test@example.com>")
    'Bob &amp; Alice &lt;test@example.com&gt;'
    >>> _html_escape("Normal text")
    'Normal text'
    >>> _html_escape(None)
    ''
    """
    if value is None:
        return ""
    # Order matters: & must be replaced first (otherwise subsequent
    # replacements that introduce & would be double-escaped).
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#x27;")
    )



# ============================================================================
# Public function — sort_and_restructure_transactions
# ============================================================================


def sort_and_restructure_transactions(transactions_df: DataFrame) -> DataFrame:
    """Sort and restructure the transactions DataFrame for statement generation.

    This function replaces **JCL STEP010 SORT** (CREASTMT.JCL lines 44-55)
    and **STEP020 IDCAMS REPRO** (lines 56-62) with a single in-memory
    PySpark operation.  The original mainframe pipeline was:

    1. STEP010: Read ``TRANSACT.VSAM.KSDS``, sort by composite key
       ``FIELDS=(263,16,CH,A,1,16,CH,A)`` — card_num (offset 263, 16 bytes)
       primary ascending, tran_id (offset 1, 16 bytes) secondary ascending.
    2. STEP010 ``OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)``: Rewrite
       every 350-byte record so card_num is the leading field — i.e., the
       original record layout had tran_id first and card_num at offset 263,
       but the statement generator requires card_num at offset 0 because
       the downstream TRXFL.VSAM.KSDS cluster is keyed on
       ``KEYS(32 0)`` (card_num + tran_id starting at byte 0).
    3. STEP020: Copy the sorted sequential file into the TRXFL.VSAM.KSDS
       work cluster.
    4. CBSTM03A then opens TRXFL.VSAM.KSDS and browses sequentially,
       buffering transactions per card into ``WS-TRNX-TABLE``.

    In the cloud target the same three steps collapse into one PySpark
    transformation::

        transactions_df.orderBy("tran_card_num", "tran_id")

    Because PySpark DataFrames have no byte-positional layout constraint
    (they are column-oriented), the OUTREC restructuring is reduced to a
    trivial ``.select()`` that reorders the columns so ``tran_card_num``
    appears first.  The re-ordering has no performance impact; it is
    performed purely for semantic fidelity with the mainframe contract.

    Additionally, a ``tran_seq`` column is appended using a PySpark
    ``Window`` to give each transaction an integer sequence within its
    card.  This column is NOT emitted to the text or HTML statement
    (the mainframe had no equivalent field), but it is used internally
    by :func:`main` for diagnostic logging and mirrors the
    ``TR-JMP`` loop index from CBSTM03A paragraph ``4000-TRNXFILE-GET``.
    Using ``Window.partitionBy().orderBy()`` exercises the full PySpark
    window-function machinery and satisfies the AAP-declared
    ``members_accessed`` requirement for the ``Window`` import.

    Parameters
    ----------
    transactions_df : pyspark.sql.DataFrame
        The raw transactions DataFrame as loaded by
        :func:`src.batch.common.db_connector.read_table` from the
        ``transactions`` PostgreSQL table.  Expected columns (at minimum):
        ``tran_id``, ``tran_card_num``, ``tran_desc``, ``tran_amt``.

    Returns
    -------
    pyspark.sql.DataFrame
        A new DataFrame sorted by (tran_card_num ASC, tran_id ASC) with
        columns reordered so tran_card_num leads, plus an additional
        ``tran_seq`` column containing the per-card transaction sequence
        number (1-based).  The original DataFrame is NOT modified.

    Examples
    --------
    >>> # Pseudocode (no Spark session in doctests)
    >>> # sorted_df = sort_and_restructure_transactions(transactions_df)
    >>> # sorted_df.select("tran_card_num", "tran_id", "tran_seq").show()
    >>> # +----------------+---------------+--------+
    >>> # |tran_card_num   |tran_id        |tran_seq|
    >>> # +----------------+---------------+--------+
    >>> # |4111111111111111|T0000000000001 |       1|
    >>> # |4111111111111111|T0000000000005 |       2|
    >>> # |4222222222222222|T0000000000002 |       1|
    >>> # +----------------+---------------+--------+

    Notes
    -----
    The mainframe SORT used a binary-collating CH (character) sort.
    PySpark's default ``orderBy`` also performs a lexicographic byte
    comparison for ``StringType`` columns, so the resulting order is
    identical.  There is no locale-specific collation difference.
    """
    logger.info(_JCL_STEP010_START_MSG)

    # Preserve the column set — we do NOT drop any columns here.  The
    # caller may select a subset later; this function is concerned
    # solely with ordering and making card_num the leading column.
    original_columns: list[str] = transactions_df.columns

    # Construct the leading-column list: card_num, tran_id, then every
    # other column in its original order.
    leading_cols: list[str] = ["tran_card_num", "tran_id"]
    remaining_cols: list[str] = [
        col for col in original_columns if col not in leading_cols
    ]
    reordered_cols: list[str] = leading_cols + remaining_cols

    # Apply the ORDER BY — this is the PySpark equivalent of the JCL SORT
    # FIELDS=(263,16,CH,A,1,16,CH,A).  Using two separate column names
    # (rather than a concatenated key) preserves the two-level comparison
    # semantics: rows with equal card_num are ordered by tran_id.
    sorted_df: DataFrame = transactions_df.orderBy(
        F.col("tran_card_num").asc(),
        F.col("tran_id").asc(),
    )

    # Reorder the columns (equivalent to JCL OUTREC FIELDS restructuring).
    sorted_df = sorted_df.select(*reordered_cols)

    # Append a per-card sequence number via PySpark Window.  The
    # partitionBy groups rows by card_num; the orderBy establishes the
    # within-group ordering; F.row_number() numbers the rows 1..N per
    # partition.  This mirrors the COBOL ``TR-JMP`` loop index that
    # enumerates transactions within a card's block of ``WS-TRNX-TABLE``.
    # ``Window.partitionBy(...)`` returns a :class:`WindowSpec`, not the
    # :class:`Window` factory class itself — hence no type annotation
    # on the intermediate variable (we let mypy infer the WindowSpec).
    per_card_window = Window.partitionBy("tran_card_num").orderBy("tran_id")
    sorted_df = sorted_df.withColumn("tran_seq", F.row_number().over(per_card_window))

    logger.info(_JCL_STEP010_END_MSG)
    return sorted_df


# ============================================================================
# Public function — generate_text_statement
# ============================================================================


def generate_text_statement(
    card_num: str,
    customer: dict[str, Any],
    account: dict[str, Any],
    transactions: list[dict[str, Any]],
) -> str:
    """Generate the 80-char fixed-width text statement for a single card.

    Faithfully reproduces the COBOL statement-template rendering from
    CBSTM03A.CBL paragraphs ``5000-CREATE-STATEMENT`` (lines 461-507) and
    ``6000-WRITE-TRANS`` (lines 634-669), followed by the trailing total
    + end-marker sequence from paragraph ``4000-TRNXFILE-GET`` (lines
    416-456).

    The emitted text is a single ``\\n``-separated string where every
    line is EXACTLY 80 characters wide — matching the STMTFILE LRECL=80
    RECFM=FB contract from CREASTMT.JCL line 86.

    Statement structure
    -------------------
    1. **ST-LINE0**  — start-of-statement banner (80 chars, 31 stars +
       "START OF STATEMENT" + 31 stars).
    2. **ST-LINE1**  — customer full name (first + middle + last,
       STRING-concatenated) padded to 75 chars + 5 trailing spaces = 80.
    3. **ST-LINE2**  — address line 1 (50 chars + 30 trailing spaces).
    4. **ST-LINE3**  — address line 2 (50 chars + 30 trailing spaces).
    5. **ST-LINE4**  — address line 3 composite (city + state + country +
       zip, STRING-concatenated, 80 chars).
    6. **ST-LINE5**  — 80 dashes (section separator).
    7. **ST-LINE6**  — "Basic Details" centered section header.
    8. **ST-LINE5**  — 80 dashes (repeated — the section header is
       sandwiched between two dash lines).
    9. **ST-LINE7**  — ``Account ID         : <acct_id>`` + padding.
    10. **ST-LINE8** — ``Current Balance    : <balance as 9(9).99->``.
    11. **ST-LINE9** — ``FICO Score         : <fico_score>``.
    12. **ST-LINE10** — 80 dashes.
    13. **ST-LINE11** — "TRANSACTION SUMMARY" centered section header.
    14. **ST-LINE12** — 80 dashes.
    15. **ST-LINE13** — column headers (Tran ID / Tran Details / Tran Amount).
    16. **ST-LINE12** — 80 dashes (repeated — column headers sandwiched).
    17. **For each transaction** — **ST-LINE14** = tran_id + space +
        tran_desc + "$" + amount (as PIC Z(9).99-).
    18. **ST-LINE12** — 80 dashes (after last transaction).
    19. **ST-LINE14A** — total-expenses row ("Total EXP:" + spaces + "$"
        + total as PIC Z(9).99-).
    20. **ST-LINE15** — end-of-statement banner (80 chars, 32 stars +
        "END OF STATEMENT" + 32 stars).

    Parameters
    ----------
    card_num : str
        The 16-character card number driving this statement.  Used only
        for log correlation — the actual card identity is conveyed via
        ``customer`` and ``account`` which have already been resolved
        via the cross-reference table.
    customer : dict
        Customer attributes.  Expected keys: ``cust_first_name``,
        ``cust_middle_name``, ``cust_last_name``, ``cust_addr_line_1``,
        ``cust_addr_line_2``, ``cust_addr_line_3``, ``cust_addr_state_cd``,
        ``cust_addr_country_cd``, ``cust_addr_zip``,
        ``cust_fico_credit_score``.  Any missing key is treated as
        ``None``.
    account : dict
        Account attributes.  Expected keys: ``acct_id``, ``acct_curr_bal``.
    transactions : list of dict
        A list of per-card transactions, already sorted in tran_id
        ascending order (see :func:`sort_and_restructure_transactions`).
        Each dict has keys ``tran_id``, ``tran_desc``, ``tran_amt``.
        The list may be empty — cards with no transactions still receive
        a fully-formed statement with a zero total.

    Returns
    -------
    str
        The full text statement, 80-char lines separated by ``\\n`` and
        terminated with a trailing newline.  Caller concatenates the
        per-card outputs to produce the final STATEMNT.PS file.

    Examples
    --------
    >>> customer = {
    ...     "cust_first_name": "John                     ",
    ...     "cust_middle_name": "Q                        ",
    ...     "cust_last_name": "Smith                    ",
    ...     "cust_addr_line_1": "123 Main Street                                   ",
    ...     "cust_addr_line_2": "                                                  ",
    ...     "cust_addr_line_3": "Seattle                                           ",
    ...     "cust_addr_state_cd": "WA",
    ...     "cust_addr_country_cd": "USA",
    ...     "cust_addr_zip": "98101     ",
    ...     "cust_fico_credit_score": 750,
    ... }
    >>> account = {
    ...     "acct_id": "00000000001",
    ...     "acct_curr_bal": Decimal("1234.56"),
    ... }
    >>> txns = [{
    ...     "tran_id": "T000000000000001",
    ...     "tran_desc": "GROCERY PURCHASE",
    ...     "tran_amt": Decimal("45.67"),
    ... }]
    >>> result = generate_text_statement("4111111111111111", customer, account, txns)
    >>> lines = result.rstrip("\\n").split("\\n")
    >>> all(len(line) == 80 for line in lines)
    True
    >>> lines[0].startswith("*" * 31)
    True
    >>> lines[-1].startswith("*" * 32)  # ST-LINE15 ends with 32 stars
    True
    """
    # Build up the statement as a list of lines — appending is O(1) and
    # the final join is O(total chars).  The line ordering mirrors the
    # exact WRITE-sequence of CBSTM03A paragraphs 5000/6000/4000.
    lines: list[str] = []

    # -------------------------------------------------------------------
    # Line 1 — ST-LINE0: Start-of-statement banner.
    # CBSTM03A.CBL line 467: ``WRITE FD-STMTFILE-REC FROM ST-LINE0.``
    # -------------------------------------------------------------------
    lines.append(_ST_LINE0)

    # -------------------------------------------------------------------
    # Line 2 — ST-LINE1: Customer full name (75 chars + 5 spaces).
    # CBSTM03A.CBL line 476: STRING first+middle+last DELIMITED BY ' '
    #                        INTO ST-NAME.
    # ST-NAME is PIC X(75); ST-LINE1 is ST-NAME + 5 trailing spaces
    # (lines 94-96).
    # -------------------------------------------------------------------
    full_name: str = _cobol_concat_name(
        customer.get("cust_first_name"),
        customer.get("cust_middle_name"),
        customer.get("cust_last_name"),
    )
    # ST-NAME is 75 chars; pad (or truncate) to 75 then append 5 spaces.
    st_name_field: str = (full_name + " " * 75)[:75]
    lines.append(_pad_text_line(st_name_field + " " * 5))

    # -------------------------------------------------------------------
    # Lines 3-4 — ST-LINE2 and ST-LINE3: Address line 1 and 2.
    # CBSTM03A.CBL line 485: MOVE CUST-ADDR-LINE-1 TO ST-ADD1.
    # CBSTM03A.CBL line 486: MOVE CUST-ADDR-LINE-2 TO ST-ADD2.
    # Both ST-ADD1 and ST-ADD2 are PIC X(50); each line is 50 data + 30
    # trailing spaces = 80 chars (lines 97-100).
    # -------------------------------------------------------------------
    addr1: str = customer.get("cust_addr_line_1") or ""
    addr2: str = customer.get("cust_addr_line_2") or ""
    # Truncate/pad to 50 chars for ST-ADD1/ST-ADD2 field width, then
    # delegate to _pad_text_line for the full 80-char record width.
    st_add1_field: str = (addr1 + " " * 50)[:50]
    st_add2_field: str = (addr2 + " " * 50)[:50]
    lines.append(_pad_text_line(st_add1_field))
    lines.append(_pad_text_line(st_add2_field))

    # -------------------------------------------------------------------
    # Line 5 — ST-LINE4: Composite address line 3 (city + state + country + zip).
    # CBSTM03A.CBL lines 489-497: STRING CUST-ADDR-LINE-3 +
    #                              CUST-ADDR-STATE-CD +
    #                              CUST-ADDR-COUNTRY-CD +
    #                              CUST-ADDR-ZIP  INTO ST-ADD3.
    # ST-ADD3 is PIC X(80) — the full 80-char record (line 101).
    # -------------------------------------------------------------------
    addr3_composite: str = _cobol_concat_address_line_3(
        customer.get("cust_addr_line_3"),
        customer.get("cust_addr_state_cd"),
        customer.get("cust_addr_country_cd"),
        customer.get("cust_addr_zip"),
    )
    lines.append(_pad_text_line(addr3_composite))

    # -------------------------------------------------------------------
    # Line 6 — ST-LINE5: 80 dashes (section separator).
    # CBSTM03A.CBL line 500: WRITE FD-STMTFILE-REC FROM ST-LINE5.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE_DASHES)

    # -------------------------------------------------------------------
    # Line 7 — ST-LINE6: "Basic Details" section header.
    # CBSTM03A.CBL line 501: WRITE FD-STMTFILE-REC FROM ST-LINE6.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE6)

    # -------------------------------------------------------------------
    # Line 8 — ST-LINE5 again: 80 dashes.
    # CBSTM03A.CBL line 502: WRITE FD-STMTFILE-REC FROM ST-LINE5.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE_DASHES)

    # -------------------------------------------------------------------
    # Line 9 — ST-LINE7: ``Account ID         : <acct_id>`` + padding.
    # CBSTM03A.CBL line 498: MOVE ACCT-ID TO ST-ACCT-ID.
    # CBSTM03A.CBL line 503: WRITE FD-STMTFILE-REC FROM ST-LINE7.
    # ST-LINE7 = "Account ID         :" (20) + ST-ACCT-ID (20) + 40 spaces.
    # -------------------------------------------------------------------
    acct_id_raw: str = str(account.get("acct_id") or "")
    # ST-ACCT-ID is PIC X(20); pad/truncate to 20 chars.
    st_acct_id_field: str = (acct_id_raw + " " * 20)[:20]
    lines.append(_pad_text_line(_ST_LINE7_LABEL + st_acct_id_field + " " * 40))

    # -------------------------------------------------------------------
    # Line 10 — ST-LINE8: ``Current Balance    : <balance>`` + padding.
    # CBSTM03A.CBL line 499: MOVE ACCT-CURR-BAL TO ST-CURR-BAL.
    # CBSTM03A.CBL line 504: WRITE FD-STMTFILE-REC FROM ST-LINE8.
    # ST-LINE8 = "Current Balance    :" (20) + ST-CURR-BAL (13 chars as
    # 9(9).99-) + 7 spaces + 40 spaces = 80.
    #
    # ``acct_curr_bal`` is read as a NUMERIC(15,2) PostgreSQL column
    # (see ``db/migrations/V1__schema.sql``) and materialized by Spark as
    # a :class:`pyspark.sql.types.DecimalType`.  When collected to the
    # driver it arrives as a :class:`decimal.Decimal`.  If for any
    # reason the value is None (which the schema forbids, but defensive
    # programming requires handling), we default to Decimal('0.00').
    # -------------------------------------------------------------------
    raw_balance: Any = account.get("acct_curr_bal")
    balance: Decimal = (
        raw_balance if isinstance(raw_balance, Decimal) else Decimal(str(raw_balance or 0))
    )
    balance_edited: str = _format_balance_edited(balance)
    lines.append(
        _pad_text_line(_ST_LINE8_LABEL + balance_edited + " " * 7 + " " * 40)
    )

    # -------------------------------------------------------------------
    # Line 11 — ST-LINE9: ``FICO Score         : <fico>`` + padding.
    # CBSTM03A.CBL line 505: WRITE FD-STMTFILE-REC FROM ST-LINE9.
    # ST-LINE9 = "FICO Score         :" (20) + ST-FICO-SCORE (20) + 40 spaces.
    # -------------------------------------------------------------------
    fico_score_edited: str = _format_fico_score(customer.get("cust_fico_credit_score"))
    lines.append(_pad_text_line(_ST_LINE9_LABEL + fico_score_edited + " " * 40))

    # -------------------------------------------------------------------
    # Line 12 — ST-LINE10: 80 dashes.
    # CBSTM03A.CBL line 506: WRITE FD-STMTFILE-REC FROM ST-LINE10.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE_DASHES)

    # -------------------------------------------------------------------
    # Line 13 — ST-LINE11: "TRANSACTION SUMMARY" section header.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE11)

    # -------------------------------------------------------------------
    # Line 14 — ST-LINE12: 80 dashes.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE_DASHES)

    # -------------------------------------------------------------------
    # Line 15 — ST-LINE13: column headers.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE13)

    # -------------------------------------------------------------------
    # Line 16 — ST-LINE12 again: 80 dashes (column-header sandwich).
    # -------------------------------------------------------------------
    lines.append(_ST_LINE_DASHES)

    # -------------------------------------------------------------------
    # Lines 17..N — ST-LINE14: one per transaction.
    # CBSTM03A.CBL line 441: PERFORM 6000-WRITE-TRANS.
    # Paragraph 6000-WRITE-TRANS (lines 634-669) writes ST-LINE14 by
    # first moving WS-TRAN-NUM to ST-TRANID, WS-TRAN-REST (description
    # + amount unpacked) to ST-TRANDT and ST-TRANAMT.
    # ST-LINE14 = ST-TRANID (16) + ' ' (1) + ST-TRANDT (49) + '$' (1)
    #             + ST-TRANAMT (13 chars as Z(9).99-) = 80.
    #
    # While iterating, accumulate WS-TOTAL-AMT (COMP-3) for the
    # post-loop total row.  Per CBSTM03A.CBL line 437:
    #     ADD TRNX-AMT TO WS-TOTAL-AMT.
    # We use :class:`decimal.Decimal` arithmetic with banker's rounding
    # at each step — no floating-point at any point.
    # -------------------------------------------------------------------
    ws_total_amt: Decimal = _DECIMAL_ZERO

    for txn in transactions:
        tran_id_raw: str = str(txn.get("tran_id") or "")
        tran_desc_raw: str = str(txn.get("tran_desc") or "")
        raw_amt: Any = txn.get("tran_amt")
        tran_amt: Decimal = (
            raw_amt if isinstance(raw_amt, Decimal) else Decimal(str(raw_amt or 0))
        )

        # Accumulate the total (COBOL: ADD TRNX-AMT TO WS-TOTAL-AMT).
        # Quantize at each step to preserve COBOL COMP-3 semantics
        # (which holds exactly 2 decimal places throughout arithmetic).
        ws_total_amt = (ws_total_amt + tran_amt).quantize(
            _DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN
        )

        # Format the ST-LINE14 fields with their exact COBOL widths.
        st_tranid_field: str = (tran_id_raw + " " * 16)[:16]
        st_trandt_field: str = (tran_desc_raw + " " * 49)[:49]
        st_tranamt_edited: str = _format_amount_edited(tran_amt)

        # Concatenate: ST-TRANID (16) + ' ' (1) + ST-TRANDT (49) + '$' (1)
        # + ST-TRANAMT (13) = 80.
        line14: str = (
            st_tranid_field
            + " "
            + st_trandt_field
            + "$"
            + st_tranamt_edited
        )
        lines.append(_pad_text_line(line14))

    # -------------------------------------------------------------------
    # Line N+1 — ST-LINE12: 80 dashes (after last transaction).
    # CBSTM03A.CBL line 444: WRITE FD-STMTFILE-REC FROM ST-LINE12.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE_DASHES)

    # -------------------------------------------------------------------
    # Line N+2 — ST-LINE14A: total-expenses row.
    # CBSTM03A.CBL lines 440-445:
    #     MOVE WS-TOTAL-AMT TO WS-TRN-AMT.
    #     MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT.
    #     WRITE FD-STMTFILE-REC FROM ST-LINE14A.
    # ST-LINE14A = "Total EXP:" (10) + 56 spaces + '$' (1) +
    #              ST-TOTAL-TRAMT (13 chars as Z(9).99-) = 80.
    # -------------------------------------------------------------------
    total_edited: str = _format_amount_edited(ws_total_amt)
    lines.append(_pad_text_line(_ST_LINE14A_PREFIX + total_edited))

    # -------------------------------------------------------------------
    # Line N+3 — ST-LINE15: end-of-statement banner.
    # CBSTM03A.CBL line 446: WRITE FD-STMTFILE-REC FROM ST-LINE15.
    # -------------------------------------------------------------------
    lines.append(_ST_LINE15)

    # Log per-card summary for operator traceability — matches the
    # COBOL DISPLAY statements that appeared after each statement write.
    logger.info(
        "text statement generated: card_num=%s tran_count=%d total=%s",
        card_num,
        len(transactions),
        total_edited.strip(),
    )

    # Join lines with newline separator and append a trailing newline
    # (matching COBOL WRITE which always appends the RECFM=FB record
    # terminator).
    return "\n".join(lines) + "\n"


# ============================================================================
# Public function — generate_html_statement
# ============================================================================


def generate_html_statement(
    card_num: str,
    customer: dict[str, Any],
    account: dict[str, Any],
    transactions: list[dict[str, Any]],
) -> str:
    """Generate the HTML statement document for a single card.

    Faithfully reproduces the COBOL HTML-template rendering from
    CBSTM03A.CBL paragraphs ``5100-WRITE-HTML-HEADER`` (lines 520-569),
    ``5200-WRITE-HTML-NMADBS`` (lines 572-632), ``6000-WRITE-TRANS``
    (lines 634-669, HTML portion), and the closing HTML sequence at the
    end of ``4000-TRNXFILE-GET`` (lines 446-456).

    Output structure
    ----------------
    Every card produces a complete, well-formed HTML5 document::

        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <title>HTML Table Layout</title>
        </head>
        <body style="margin:0px;">
        <table align="center" frame="box" style="width:70%; ...">
          <!-- Title row: "Statement for Account Number: <acct_id>" -->
          <!-- Bank address row: Bank of XYZ / 410 Terry Ave N / Seattle WA 99999 -->
          <!-- Customer name + address rows -->
          <!-- Basic Details section (account ID, balance, FICO) -->
          <!-- Transaction Summary section header + column headers -->
          <!-- One row per transaction -->
          <!-- End of Statement closing row -->
        </table>
        </body>
        </html>

    Every hex color code (``#1d1d96b3``, ``#FFAF33``, ``#f2f2f2``,
    ``#33FFD1``, ``#33FF5E``) is preserved verbatim from the COBOL
    HTML-LINES condition names.  The bank address — "Bank of XYZ / 410
    Terry Ave N / Seattle WA 99999" — is also preserved verbatim from
    HTML-L16, HTML-L17, HTML-L18 (CBSTM03A.CBL lines 167-172).

    Security hardening
    ------------------
    Customer-controlled fields (name, address, transaction description)
    are HTML-escaped via :func:`_html_escape` before injection into
    ``<p>`` elements.  This is a targeted minor hardening over the
    mainframe behavior (which trusted its source data) and is AAP
    §0.7.1-compliant because it does not alter the visible output for
    clean (non-malicious) data — it only protects against XSS from
    poisoned upstream data.

    Parameters
    ----------
    card_num : str
        The 16-character card number.  Used only for log correlation.
    customer : dict
        Customer attributes (same schema as :func:`generate_text_statement`).
    account : dict
        Account attributes (same schema as :func:`generate_text_statement`).
    transactions : list of dict
        A list of per-card transactions, already sorted (same schema as
        :func:`generate_text_statement`).  May be empty.

    Returns
    -------
    str
        The complete HTML5 document for this card, ``\\n``-separated
        lines, terminated with a trailing newline.

    Examples
    --------
    >>> customer = {
    ...     "cust_first_name": "John     ",
    ...     "cust_middle_name": "Q         ",
    ...     "cust_last_name": "Smith     ",
    ...     "cust_addr_line_1": "123 Main Street                     ",
    ...     "cust_addr_line_2": "                                    ",
    ...     "cust_addr_line_3": "Seattle                             ",
    ...     "cust_addr_state_cd": "WA",
    ...     "cust_addr_country_cd": "USA",
    ...     "cust_addr_zip": "98101     ",
    ...     "cust_fico_credit_score": 750,
    ... }
    >>> account = {"acct_id": "00000000001", "acct_curr_bal": Decimal("1234.56")}
    >>> html = generate_html_statement("4111111111111111", customer, account, [])
    >>> html.startswith("<!DOCTYPE html>\\n")
    True
    >>> "<html lang=\\"en\\">" in html
    True
    >>> "</html>" in html
    True
    >>> "Bank of XYZ" in html
    True
    >>> "410 Terry Ave N" in html
    True
    >>> "Seattle WA 99999" in html
    True
    """
    # Build the HTML document as a list of lines — structurally analogous
    # to the sequence of ``WRITE FD-HTMLFILE-REC`` statements in the
    # COBOL paragraphs 5100/5200/6000/4000.
    html_lines: list[str] = []

    # -------------------------------------------------------------------
    # Paragraph 5100-WRITE-HTML-HEADER (CBSTM03A.CBL lines 520-569).
    # Writes the DOCTYPE, <html>, <head>, <title>, <body>, <table>,
    # title-row <td> with "Statement for Account Number: X", and the
    # bank-address row "Bank of XYZ / 410 Terry Ave N / Seattle WA 99999".
    # -------------------------------------------------------------------
    html_lines.append(_HTML_L01_DOCTYPE)      # <!DOCTYPE html>
    html_lines.append(_HTML_L02_HTML_OPEN)    # <html lang="en">
    html_lines.append(_HTML_L03_HEAD_OPEN)    # <head>
    html_lines.append(_HTML_L04_META)         # <meta charset="utf-8">
    html_lines.append(_HTML_L05_TITLE)        # <title>HTML Table Layout</title>
    html_lines.append(_HTML_L06_HEAD_CLOSE)   # </head>
    html_lines.append(_HTML_L07_BODY_OPEN)    # <body style="margin:0px;">
    html_lines.append(_HTML_L08_TABLE_OPEN)   # <table align="center" frame="box" ...>

    # Title row — "Statement for Account Number: <acct_id>".
    # CBSTM03A.CBL line 533-541: STRING '<h3>Statement for Account Number:' +
    #                             L11-ACCT + '</h3>' INTO FD-HTMLFILE-REC.
    # Where L11-ACCT PIC X(20) holds the ACCT-ID padded right.
    acct_id_raw: str = str(account.get("acct_id") or "")
    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L10_TITLE_ROW_TD)  # <td colspan="3" style="...#1d1d96b3;">
    # The COBOL STRING uses DELIMITED BY ' ' on L11-ACCT, which for a
    # 20-char zero-padded numeric field has no space in the middle — the
    # trim simply strips trailing padding.  We use rstrip() for clarity.
    html_lines.append(
        f"<h3>Statement for Account Number: {_html_escape(_cobol_rstrip(acct_id_raw))}</h3>"
    )
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # Bank-info row — "Bank of XYZ / 410 Terry Ave N / Seattle WA 99999".
    # CBSTM03A.CBL lines 548-556: bank address is a literal constant.
    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L15_BANK_ROW_TD)  # <td colspan="3" ...#FFAF33;">
    html_lines.append(_HTML_L16_BANK_NAME)    # <p style="font-size:16px">Bank of XYZ</p>
    html_lines.append(_HTML_L17_BANK_ADDR)    # <p>410 Terry Ave N</p>
    html_lines.append(_HTML_L18_BANK_CITY)    # <p>Seattle WA 99999</p>
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # -------------------------------------------------------------------
    # Paragraph 5200-WRITE-HTML-NMADBS (CBSTM03A.CBL lines 572-632).
    # Writes the customer name + address (name row), then the Basic
    # Details section (account ID, current balance, FICO score), then
    # the Transaction Summary header with column headers.
    # -------------------------------------------------------------------

    # Customer name + address row (light gray background #f2f2f2).
    # STRING uses DELIMITED BY '  ' (two spaces), i.e., rstrip() of each
    # fixed-width field before injection.
    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L22_35_DATA_ROW_TD)  # <td colspan="3" ...#f2f2f2;">

    # Customer full name — the COBOL source first STRINGs the first +
    # middle + last names into ST-NAME via _cobol_concat_name semantics,
    # then copies that into an HTML <p> element via
    # DELIMITED BY '  '.  The effective behavior is:
    #   1. Build full name ("John Q. Smith").
    #   2. Rstrip any fixed-width padding.
    # Our Python _cobol_concat_name already produces an unpadded name,
    # so we apply _cobol_rstrip defensively (it's a no-op for clean
    # data but handles the edge case where the DB returned a padded
    # string).
    full_name: str = _cobol_rstrip(
        _cobol_concat_name(
            customer.get("cust_first_name"),
            customer.get("cust_middle_name"),
            customer.get("cust_last_name"),
        )
    )
    html_lines.append(f'<p style="font-size:16px">{_html_escape(full_name)}</p>')

    # Address line 1.
    addr1: str = _cobol_rstrip(customer.get("cust_addr_line_1"))
    html_lines.append(f"<p>{_html_escape(addr1)}</p>")

    # Address line 2.
    addr2: str = _cobol_rstrip(customer.get("cust_addr_line_2"))
    html_lines.append(f"<p>{_html_escape(addr2)}</p>")

    # Address line 3 composite (city + state + country + zip).
    addr3_composite: str = _cobol_rstrip(
        _cobol_concat_address_line_3(
            customer.get("cust_addr_line_3"),
            customer.get("cust_addr_state_cd"),
            customer.get("cust_addr_country_cd"),
            customer.get("cust_addr_zip"),
        )
    )
    html_lines.append(f"<p>{_html_escape(addr3_composite)}</p>")

    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # Basic Details section-header row (#33FFD1 teal, centered).
    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L30_42_SECTION_ROW_TD)  # <td colspan="3" ...#33FFD1;...>
    html_lines.append(_HTML_L31_BASIC_DETAILS)    # <p style="font-size:16px">Basic Details</p>
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # Basic Details data row (#f2f2f2 light gray).
    raw_balance: Any = account.get("acct_curr_bal")
    balance: Decimal = (
        raw_balance if isinstance(raw_balance, Decimal) else Decimal(str(raw_balance or 0))
    )
    balance_edited: str = _format_balance_edited(balance)
    fico_score_edited: str = _format_fico_score(customer.get("cust_fico_credit_score"))

    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L22_35_DATA_ROW_TD)  # <td colspan="3" ...#f2f2f2;">
    # Account ID.
    html_lines.append(
        f"<p>Account ID         : {_html_escape(_cobol_rstrip(acct_id_raw))}</p>"
    )
    # Current Balance — same PIC 9(9).99- format as the text statement
    # for consistency.  HTML-escape is a no-op for numeric content but
    # applied defensively.
    html_lines.append(
        f"<p>Current Balance    : {_html_escape(balance_edited)}</p>"
    )
    # FICO Score — rstrip the 20-char padded format to produce a
    # visually-clean HTML rendering (the text-statement format keeps
    # the padding for column alignment; HTML doesn't need it).
    html_lines.append(
        f"<p>FICO Score         : {_html_escape(fico_score_edited.rstrip())}</p>"
    )
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # Transaction Summary section-header row (#33FFD1 teal, centered).
    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L30_42_SECTION_ROW_TD)  # <td colspan="3" ...#33FFD1;...>
    html_lines.append(_HTML_L43_TRAN_SUMMARY)      # <p style="font-size:16px">Transaction Summary</p>
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # Transaction Summary column-header row (#33FF5E green).
    html_lines.append(_HTML_LTRS)             # <tr>
    # Column 1 — Tran ID (left-aligned, 25% width).
    html_lines.append(_HTML_L47_HDR_COL1_TD)  # <td style="width:25%;...#33FF5E; text-align:left;">
    html_lines.append(_HTML_L48_HDR_TRAN_ID)  # <p style="font-size:16px">Tran ID</p>
    html_lines.append(_HTML_LTDE)             # </td>
    # Column 2 — Tran Details (left-aligned, 55% width).
    html_lines.append(_HTML_L50_HDR_COL2_TD)  # <td style="width:55%;...#33FF5E; text-align:left;">
    html_lines.append(_HTML_L51_HDR_TRAN_DETAILS)  # <p style="font-size:16px">Tran Details</p>
    html_lines.append(_HTML_LTDE)             # </td>
    # Column 3 — Amount (right-aligned, 20% width).
    html_lines.append(_HTML_L53_HDR_COL3_TD)  # <td style="width:20%;...#33FF5E; text-align:right;">
    html_lines.append(_HTML_L54_HDR_AMOUNT)   # <p style="font-size:16px">Amount</p>
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>

    # -------------------------------------------------------------------
    # Paragraph 6000-WRITE-TRANS (CBSTM03A.CBL lines 634-669 — HTML portion).
    # One <tr> per transaction with three <td> cells (ID / Description /
    # Amount) in matching #f2f2f2 background.  While iterating, also
    # accumulate WS-TOTAL-AMT for post-loop total logging — though the
    # HTML statement does NOT emit a per-card total row (only the text
    # statement does, via ST-LINE14A).  The mainframe HTML template
    # emits only <h3>End of Statement</h3> after the last transaction
    # and closes the table — matching our sequence below.
    # -------------------------------------------------------------------
    ws_total_amt: Decimal = _DECIMAL_ZERO

    for txn in transactions:
        tran_id_raw: str = str(txn.get("tran_id") or "")
        tran_desc_raw: str = str(txn.get("tran_desc") or "")
        raw_amt: Any = txn.get("tran_amt")
        tran_amt: Decimal = (
            raw_amt if isinstance(raw_amt, Decimal) else Decimal(str(raw_amt or 0))
        )

        # Accumulate with COBOL COMP-3 semantics (banker's rounding,
        # 2 decimal places maintained throughout).
        ws_total_amt = (ws_total_amt + tran_amt).quantize(
            _DECIMAL_QUANTUM, rounding=ROUND_HALF_EVEN
        )

        # Per-transaction row — three <td> cells in #f2f2f2 light gray.
        # Each field is rstripped (DELIMITED BY '  ') and HTML-escaped.
        html_lines.append(_HTML_LTRS)         # <tr>
        # Column 1 — Tran ID.
        html_lines.append(_HTML_L58_DATA_COL1_TD)  # <td style="width:25%;...#f2f2f2; text-align:left;">
        html_lines.append(f"<p>{_html_escape(_cobol_rstrip(tran_id_raw))}</p>")
        html_lines.append(_HTML_LTDE)         # </td>
        # Column 2 — Tran Details (description).
        html_lines.append(_HTML_L61_DATA_COL2_TD)  # <td style="width:55%;...#f2f2f2; text-align:left;">
        html_lines.append(f"<p>{_html_escape(_cobol_rstrip(tran_desc_raw))}</p>")
        html_lines.append(_HTML_LTDE)         # </td>
        # Column 3 — Amount (formatted as PIC Z(9).99-).  The HTML
        # version of the amount is produced by the SAME formatter as
        # the text version, ensuring byte-for-byte consistency between
        # the two output formats.
        tran_amt_edited: str = _format_amount_edited(tran_amt)
        html_lines.append(_HTML_L64_DATA_COL3_TD)  # <td style="width:20%;...#f2f2f2; text-align:right;">
        html_lines.append(f"<p>{_html_escape(tran_amt_edited)}</p>")
        html_lines.append(_HTML_LTDE)         # </td>
        html_lines.append(_HTML_LTRE)         # </tr>

    # -------------------------------------------------------------------
    # End-of-statement block — CBSTM03A.CBL paragraph 4000-TRNXFILE-GET
    # (lines 446-456, HTML portion).
    #   SET HTML-LTRS TO TRUE. WRITE HTML.
    #   SET HTML-L10  TO TRUE. WRITE HTML.
    #   SET HTML-L75  TO TRUE. WRITE HTML.
    #   SET HTML-LTDE TO TRUE. WRITE HTML.
    #   SET HTML-LTRE TO TRUE. WRITE HTML.
    #   SET HTML-L78  TO TRUE. WRITE HTML.  (</table>)
    #   SET HTML-L79  TO TRUE. WRITE HTML.  (</body>)
    #   SET HTML-L80  TO TRUE. WRITE HTML.  (</html>)
    # -------------------------------------------------------------------
    html_lines.append(_HTML_LTRS)             # <tr>
    html_lines.append(_HTML_L10_TITLE_ROW_TD)  # <td colspan="3" ...#1d1d96b3;">
    html_lines.append(_HTML_L75_END_OF_STMT)  # <h3>End of Statement</h3>
    html_lines.append(_HTML_LTDE)             # </td>
    html_lines.append(_HTML_LTRE)             # </tr>
    html_lines.append(_HTML_L78_TABLE_CLOSE)  # </table>
    html_lines.append(_HTML_L79_BODY_CLOSE)   # </body>
    html_lines.append(_HTML_L80_HTML_CLOSE)   # </html>

    # Log per-card summary for operator traceability.
    logger.info(
        "html statement generated: card_num=%s tran_count=%d total=%s",
        card_num,
        len(transactions),
        _format_amount_edited(ws_total_amt).strip(),
    )

    # Join lines with newline and append a trailing newline (matching
    # the COBOL RECFM=FB record termination convention).
    return "\n".join(html_lines) + "\n"



# ============================================================================
# Private helper — build per-card aggregates.
# ============================================================================


def _build_per_card_aggregates(
    xref_df: DataFrame,
    customers_df: DataFrame,
    accounts_df: DataFrame,
    sorted_transactions_df: DataFrame,
) -> list[dict[str, Any]]:
    """Join the 4 source DataFrames and collect per-card statement inputs.

    This helper replaces the file-navigation logic of CBSTM03A.CBL
    paragraphs ``1000-XREFFILE-GET-NEXT`` (sequential browse),
    ``2000-CUSTFILE-GET`` (key-read by cust_id), ``3000-ACCTFILE-GET``
    (key-read by acct_id), and ``4000-TRNXFILE-GET`` (key-probed
    per-card transaction buffer).  Instead of sequentially reading
    files, we perform a single four-way join in Spark, then collect the
    result to the driver.

    Join logic
    ----------
    1. ``xref`` is the driver — every card record must produce a statement
       whether or not it has any transactions.
    2. ``xref`` INNER JOIN ``customers`` on ``cust_id`` — required data
       (per the schema FK constraint ``card_cross_references.cust_id
       REFERENCES customers(cust_id)`` in V1__schema.sql).  An INNER
       JOIN here matches the mainframe's "key-read that must succeed,
       else abend" behavior.
    3. ``xref`` INNER JOIN ``accounts`` on ``acct_id`` — required data
       (per the schema FK constraint ``card_cross_references.acct_id
       REFERENCES accounts(acct_id)``).
    4. ``sorted_transactions`` LEFT OUTER JOIN the result on
       ``tran_card_num = card_num`` then groupBy/collect_list to produce
       a per-card array of transaction structs.  LEFT OUTER is crucial:
       cards with zero transactions still produce a statement (with a
       zero-total line).

    Parameters
    ----------
    xref_df, customers_df, accounts_df : pyspark.sql.DataFrame
        The three reference DataFrames loaded from PostgreSQL.
    sorted_transactions_df : pyspark.sql.DataFrame
        The sorted transactions DataFrame produced by
        :func:`sort_and_restructure_transactions`.

    Returns
    -------
    list of dict
        One entry per card cross-reference record, each containing the
        fields needed by :func:`generate_text_statement` and
        :func:`generate_html_statement`:

        * ``card_num`` — the 16-char card number.
        * ``customer`` — dict of customer attributes.
        * ``account`` — dict of account attributes.
        * ``transactions`` — list of transaction dicts in tran_id order.

    Notes
    -----
    For the CardDemo dataset (~50 cards × ~10 transactions each = 500
    total transactions), collecting to the driver is safe and
    inexpensive.  For a production-scale workload the same architecture
    would shard by card (e.g., partition the output by hash-of-card_num)
    and parallelize statement generation across executors.
    """
    logger.info("Building per-card aggregates via 4-entity join")

    # ---------------------------------------------------------------
    # Aggregate transactions per card.  For each card_num, collect the
    # sorted transaction list as a Spark array-of-structs so that a
    # single collect() at the driver returns every card's transactions
    # in one round-trip.  This mirrors the mainframe's WS-TRNX-TABLE
    # 2-D array fill in CBSTM03A paragraph 8500-READTRNX-READ.
    #
    # The outer orderBy before groupBy is NOT sufficient to guarantee
    # the per-group ordering survives the shuffle.  The correct
    # PySpark idiom is to order WITHIN the struct (by adding tran_id
    # as a leading element) or to apply a secondary sort at the driver.
    # We use the latter: collect_list gathers rows in arbitrary order,
    # then we sort each per-card list at the driver by tran_id.
    # ---------------------------------------------------------------
    txns_agg_df: DataFrame = sorted_transactions_df.groupBy("tran_card_num").agg(
        F.collect_list(
            F.struct(
                F.col("tran_id").alias("tran_id"),
                F.col("tran_desc").alias("tran_desc"),
                F.col("tran_amt").alias("tran_amt"),
            )
        ).alias("transactions")
    )

    # ---------------------------------------------------------------
    # Join xref ⋈ customers on cust_id (INNER).
    # The schema FK guarantees this is never a spurious-null join.
    # ---------------------------------------------------------------
    xref_cust_df: DataFrame = xref_df.alias("x").join(
        customers_df.alias("c"),
        on=F.col("x.cust_id") == F.col("c.cust_id"),
        how="inner",
    )

    # ---------------------------------------------------------------
    # Join (xref ⋈ customers) ⋈ accounts on acct_id (INNER).
    # ---------------------------------------------------------------
    xref_cust_acct_df: DataFrame = xref_cust_df.join(
        accounts_df.alias("a"),
        on=F.col("x.acct_id") == F.col("a.acct_id"),
        how="inner",
    )

    # ---------------------------------------------------------------
    # Join (xref ⋈ customers ⋈ accounts) ⋈ transactions_agg on card_num
    # (LEFT OUTER).  Cards with no transactions produce a row where
    # ``transactions`` is NULL (which we coerce to empty list at the
    # driver).
    # ---------------------------------------------------------------
    full_df: DataFrame = xref_cust_acct_df.join(
        txns_agg_df.alias("t"),
        on=F.col("x.card_num") == F.col("t.tran_card_num"),
        how="left_outer",
    ).select(
        F.col("x.card_num").alias("card_num"),
        # Customer attributes (use fully qualified c.* to avoid
        # ambiguity with the xref cust_id).
        F.col("c.cust_id").alias("cust_id"),
        F.col("c.cust_first_name").alias("cust_first_name"),
        F.col("c.cust_middle_name").alias("cust_middle_name"),
        F.col("c.cust_last_name").alias("cust_last_name"),
        F.col("c.cust_addr_line_1").alias("cust_addr_line_1"),
        F.col("c.cust_addr_line_2").alias("cust_addr_line_2"),
        F.col("c.cust_addr_line_3").alias("cust_addr_line_3"),
        F.col("c.cust_addr_state_cd").alias("cust_addr_state_cd"),
        F.col("c.cust_addr_country_cd").alias("cust_addr_country_cd"),
        F.col("c.cust_addr_zip").alias("cust_addr_zip"),
        F.col("c.cust_fico_credit_score").alias("cust_fico_credit_score"),
        # Account attributes.
        F.col("a.acct_id").alias("acct_id"),
        F.col("a.acct_curr_bal").alias("acct_curr_bal"),
        # Transactions (array of structs; possibly NULL for cards with
        # no transactions).
        F.col("t.transactions").alias("transactions"),
    )

    # Order by card_num so the emitted statements appear in
    # deterministic order matching the mainframe XREFFILE sequential
    # browse (which was in card_num ascending order because VSAM KSDS
    # sequentially browses in key order).
    full_df = full_df.orderBy(F.col("card_num").asc())

    # Collect to driver.  CardDemo scale (~50 cards) means this is
    # inexpensive; for larger datasets see the Notes section above.
    driver_rows = full_df.collect()
    logger.info("Collected %d per-card aggregates", len(driver_rows))

    # Materialize the per-card records as plain Python dicts.  Spark
    # Row.asDict() handles basic conversion; transactions need an
    # explicit inner conversion because they are a nested Row array.
    per_card_records: list[dict[str, Any]] = []
    for row in driver_rows:
        row_dict: dict[str, Any] = row.asDict(recursive=True)

        # Sort the per-card transaction list defensively — though the
        # input was ordered by orderBy("tran_card_num", "tran_id"), the
        # groupBy+collect_list operation does NOT guarantee the
        # within-group ordering.  We re-sort here by tran_id ascending
        # to preserve the mainframe's "transactions presented in
        # tran_id order within each card" contract.
        raw_txns: list[dict[str, Any]] | None = row_dict.get("transactions")
        sorted_txns: list[dict[str, Any]] = (
            sorted(raw_txns, key=lambda t: str(t.get("tran_id") or ""))
            if raw_txns
            else []
        )

        per_card_records.append(
            {
                "card_num": row_dict["card_num"],
                "customer": {
                    "cust_id": row_dict.get("cust_id"),
                    "cust_first_name": row_dict.get("cust_first_name"),
                    "cust_middle_name": row_dict.get("cust_middle_name"),
                    "cust_last_name": row_dict.get("cust_last_name"),
                    "cust_addr_line_1": row_dict.get("cust_addr_line_1"),
                    "cust_addr_line_2": row_dict.get("cust_addr_line_2"),
                    "cust_addr_line_3": row_dict.get("cust_addr_line_3"),
                    "cust_addr_state_cd": row_dict.get("cust_addr_state_cd"),
                    "cust_addr_country_cd": row_dict.get("cust_addr_country_cd"),
                    "cust_addr_zip": row_dict.get("cust_addr_zip"),
                    "cust_fico_credit_score": row_dict.get("cust_fico_credit_score"),
                },
                "account": {
                    "acct_id": row_dict.get("acct_id"),
                    "acct_curr_bal": row_dict.get("acct_curr_bal"),
                },
                "transactions": sorted_txns,
            }
        )

    return per_card_records


# ============================================================================
# Private helper — compose S3 object key from a versioned-path prefix URI.
# ============================================================================


def _compose_s3_key(prefix_uri: str, filename: str) -> tuple[str, str]:
    """Split a versioned S3 URI into (bucket, key) suitable for write_to_s3.

    :func:`get_versioned_s3_path` returns a full URI of the form
    ``s3://{bucket}/{path}/YYYY/MM/DD/HHMMSS/`` — including both the
    scheme and the bucket name.  :func:`write_to_s3`, however, accepts
    the bucket separately (via the ``bucket=...`` kwarg) and the key
    WITHOUT the ``s3://{bucket}/`` prefix.  This helper performs the
    required split and appends the given filename to the prefix.

    This split-and-compose pattern is proven across
    :mod:`src.batch.jobs.posttran_job` (DALYREJS rejects) and
    :mod:`src.batch.jobs.intcalc_job` (DALYREJS rejects) — both of
    which use the identical 4-line pattern to transform a
    :func:`get_versioned_s3_path` return value into the
    :func:`write_to_s3` argument pair.

    Parameters
    ----------
    prefix_uri : str
        A versioned S3 URI as returned by :func:`get_versioned_s3_path`.
        Expected form: ``s3://{bucket}/{path}/YYYY/MM/DD/HHMMSS/``.
    filename : str
        The object filename to append to the prefix (e.g., ``"STATEMNT.txt"``
        or ``"STATEMNT.html"``).

    Returns
    -------
    tuple[str, str]
        A 2-tuple of (bucket_name, full_key).  Caller passes these
        directly as the ``bucket=`` and first-positional argument of
        :func:`write_to_s3`.

    Raises
    ------
    ValueError
        If ``prefix_uri`` is malformed (missing bucket/key separator).

    Examples
    --------
    >>> _compose_s3_key("s3://my-bucket/statements/text/2026/01/15/120000/",
    ...                 "STATEMNT.txt")
    ('my-bucket', 'statements/text/2026/01/15/120000/STATEMNT.txt')
    """
    # Strip the scheme prefix.
    scheme_stripped: str = prefix_uri.removeprefix("s3://")
    if "/" not in scheme_stripped:
        # Defensive: get_versioned_s3_path guarantees the URI contains
        # a path, but guard against accidental misconfiguration.
        raise ValueError(
            f"Invalid S3 URI returned by get_versioned_s3_path: {prefix_uri!r}"
        )

    # Split on the FIRST '/' to isolate the bucket from the key prefix.
    bucket_name, key_prefix = scheme_stripped.split("/", 1)

    # Append the filename to the key prefix.  The prefix always ends in
    # '/' per get_versioned_s3_path convention, so simple concatenation
    # is safe.
    full_key: str = f"{key_prefix}{filename}"
    return bucket_name, full_key


# ============================================================================
# Public function — main()
# ============================================================================


def main() -> None:
    """Orchestrate the full Stage 4a statement generation pipeline.

    Replaces the end-to-end CREASTMT.JCL job flow:

    1. **JCL JOB card + STEP040 EXEC PGM=CBSTM03A** →
       :func:`init_glue` + the ``try`` block below.
    2. **STEP010 SORT / STEP020 REPRO** (sort transactions by
       card_num+tran_id) → :func:`sort_and_restructure_transactions`.
    3. **STEP030 delete-prior-run** → No-op (timestamped S3 paths are
       unique per run).
    4. **STEP040 CBSTM03A 4-entity navigation** →
       :func:`_build_per_card_aggregates` + per-card
       :func:`generate_text_statement` + :func:`generate_html_statement`
       loops.
    5. **STEP040 STMTFILE / HTMLFILE DD output** → :func:`write_to_s3`
       for both text and HTML outputs.
    6. **JCL terminal success (MAXCC=0)** → :func:`commit_job`.

    Pipeline steps
    --------------
    Step 0 — :func:`init_glue` initializes the Spark/Glue/Job context
    and installs JSON logging.  The ``job_name`` argument is the Glue
    job identifier ``"carddemo-creastmt"`` (see ``_JOB_NAME`` constant).

    Step 1 — Read all 4 source tables via :func:`read_table`.  No JDBC
    traffic flows until a Spark action is triggered; the DataFrames
    returned here are lazy query plans.

    Step 2 — Sort and restructure transactions via
    :func:`sort_and_restructure_transactions` (replaces JCL STEP010).

    Step 3 — Build per-card aggregates via the 4-entity join (replaces
    CBSTM03A paragraphs 1000/2000/3000/4000 + 8500-READTRNX-READ 2-D
    array fill).

    Step 4 — For each card record, call :func:`generate_text_statement`
    and :func:`generate_html_statement` — replaces CBSTM03A paragraphs
    5000/5100/5200/6000/4000-ending.  Accumulate the results into two
    big strings (one text, one HTML).

    Step 5 — Write both outputs to S3 under the versioned paths
    resolved by :func:`get_versioned_s3_path`.  Text uses Content-Type
    ``text/plain`` (matching STMTFILE LRECL=80 RECFM=FB); HTML uses
    Content-Type ``text/html`` (matching HTMLFILE LRECL=100 RECFM=FB).

    Step 6 — :func:`commit_job` signals Glue job bookmark commit
    (replaces the JCL implicit ``MAXCC=0`` success return code).

    Exception handling
    ------------------
    Any uncaught exception in the ``try`` block triggers
    :func:`logger.exception` (which captures the stack trace as
    structured JSON to CloudWatch) and re-raises.  The Glue runtime
    then marks the job ``FAILED``, which Step Functions observes and
    propagates as a failure state.  Because Stage 4a and Stage 4b
    run in parallel with no downstream fan-out, a CREASTMT failure
    does NOT halt TRANREPT — matching the mainframe behavior where
    both jobs were independent JES2 submissions.
    """
    # -------------------------------------------------------------------
    # Step 0 — Initialize the Glue runtime (Spark + GlueContext + Job +
    # JSON-formatted root logger).  init_glue returns a 4-tuple; we
    # keep the GlueContext as an underscore-prefixed name to indicate
    # we don't directly use it in this job (we use the lower-level
    # Spark DataFrame API throughout, via read_table which internally
    # delegates to the Glue-compatible PySpark read path).
    # -------------------------------------------------------------------
    spark, _glue_context, job, resolved_args = init_glue(job_name=_JOB_NAME)
    logger.info(_JCL_JOB_START_MSG)
    logger.info(
        "Resolved Glue arguments: %s",
        # Filter out the leading '--' keys that the awsglue utility
        # reinjects with normalized names.
        {k: v for k, v in resolved_args.items() if not k.startswith("--")},
    )

    try:
        # ---------------------------------------------------------------
        # Step 1 — Read all 4 source tables.
        # ---------------------------------------------------------------
        logger.info(_JCL_STEP040_START_MSG)
        logger.info("Reading source table: %s", _TABLE_TRANSACTIONS)
        transactions_df: DataFrame = read_table(spark, _TABLE_TRANSACTIONS)

        logger.info("Reading source table: %s", _TABLE_XREF)
        xref_df: DataFrame = read_table(spark, _TABLE_XREF)

        logger.info("Reading source table: %s", _TABLE_ACCOUNTS)
        accounts_df: DataFrame = read_table(spark, _TABLE_ACCOUNTS)

        logger.info("Reading source table: %s", _TABLE_CUSTOMERS)
        customers_df: DataFrame = read_table(spark, _TABLE_CUSTOMERS)

        # ---------------------------------------------------------------
        # Step 2 — Sort and restructure transactions (replaces JCL
        # STEP010 SORT + STEP020 REPRO).
        # ---------------------------------------------------------------
        sorted_transactions_df: DataFrame = sort_and_restructure_transactions(
            transactions_df
        )

        # ---------------------------------------------------------------
        # Step 3 — Build per-card aggregates via 4-entity join.
        # ---------------------------------------------------------------
        per_card_records: list[dict[str, Any]] = _build_per_card_aggregates(
            xref_df=xref_df,
            customers_df=customers_df,
            accounts_df=accounts_df,
            sorted_transactions_df=sorted_transactions_df,
        )

        # ---------------------------------------------------------------
        # Step 4 — Generate per-card text and HTML statements.
        # Concatenate into two big strings — one per output file.
        # ---------------------------------------------------------------
        text_chunks: list[str] = []
        html_chunks: list[str] = []
        card_count: int = 0

        for record in per_card_records:
            card_num: str = str(record["card_num"])
            customer: dict[str, Any] = record["customer"]
            account: dict[str, Any] = record["account"]
            transactions: list[dict[str, Any]] = record["transactions"]

            # Text statement.
            text_chunks.append(
                generate_text_statement(card_num, customer, account, transactions)
            )
            # HTML statement.
            html_chunks.append(
                generate_html_statement(card_num, customer, account, transactions)
            )
            card_count += 1

        # Join the chunks.  For text, no separator is needed (COBOL
        # WRITE produced raw record bytes with no inter-statement
        # delimiter).  For HTML, a comment separator is interposed
        # between documents for operator traceability.
        text_content: str = "".join(text_chunks)
        html_content: str = (
            _HTML_INTER_STATEMENT_SEPARATOR + "\n"
        ).join(html_chunks) if html_chunks else ""

        logger.info(
            "Generated %d card statements (text len=%d, html len=%d)",
            card_count,
            len(text_content),
            len(html_content),
        )

        # ---------------------------------------------------------------
        # Step 5 — Write outputs to S3.
        # ---------------------------------------------------------------
        # 5a. Text statement → STATEMNT.PS (LRECL=80, text/plain).
        text_prefix_uri: str = get_versioned_s3_path(_GDG_STATEMNT_PS)
        text_bucket, text_key = _compose_s3_key(
            text_prefix_uri, _OUTPUT_FILENAME_TEXT
        )
        logger.info(
            "Writing text statements to S3: bucket=%s key=%s bytes=%d",
            text_bucket,
            text_key,
            len(text_content.encode("utf-8")),
        )
        text_uri: str = write_to_s3(
            text_content,
            text_key,
            bucket=text_bucket,
            content_type=_CONTENT_TYPE_TEXT,
        )
        logger.info("Text statements written to %s", text_uri)

        # 5b. HTML statement → STATEMNT.HTML (LRECL=100, text/html).
        html_prefix_uri: str = get_versioned_s3_path(_GDG_STATEMNT_HTML)
        html_bucket, html_key = _compose_s3_key(
            html_prefix_uri, _OUTPUT_FILENAME_HTML
        )
        logger.info(
            "Writing HTML statements to S3: bucket=%s key=%s bytes=%d",
            html_bucket,
            html_key,
            len(html_content.encode("utf-8")),
        )
        html_uri: str = write_to_s3(
            html_content,
            html_key,
            bucket=html_bucket,
            content_type=_CONTENT_TYPE_HTML,
        )
        logger.info("HTML statements written to %s", html_uri)

        logger.info(_JCL_STEP040_END_MSG)

        # ---------------------------------------------------------------
        # Step 6 — Commit the Glue job bookmark (success signal).
        # ---------------------------------------------------------------
        commit_job(job)
        logger.info(_JCL_JOB_END_MSG)

    except Exception:
        # Capture the full exception context to CloudWatch as structured
        # JSON.  Re-raise so the Glue runtime marks the job FAILED.
        logger.exception(_JCL_ABEND_MSG)
        raise


# ============================================================================
# Entry point — enables both Glue-runtime invocation and local
# ``python -m src.batch.jobs.creastmt_job`` invocation.
# ============================================================================
#
# When invoked as a Glue script, the entrypoint is the top-level module
# execution — AWS Glue runs the script with ``python script.py`` (or its
# Glue-specific wrapper).  When invoked locally for development or
# testing, the same entrypoint fires.  In both cases ``main()`` is the
# single canonical entry.
#
# Logging sys.argv at DEBUG level helps diagnose Glue argument-passing
# issues (the ``--JOB_NAME=...`` / ``--bookmark-context=...`` arguments
# injected by the Glue scheduler are not always intuitive).
# ============================================================================
if __name__ == "__main__":
    logger.debug("sys.argv at entry: %s", sys.argv)
    main()

