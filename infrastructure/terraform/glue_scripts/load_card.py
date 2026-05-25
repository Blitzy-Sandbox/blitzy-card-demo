"""
load_card.py
============

AWS Glue PySpark ETL job — bulk-load the Card fact table from an
S3-staged fixed-width ASCII fixture into the CardDemo RDS PostgreSQL
``cards`` table.

Replaces:
    app/jcl/CARDFILE.jcl STEP15 (IDCAMS REPRO INFILE(CARDDATA
    AWS.M2.CARDDEMO.CARDDATA.PS) OUTFILE(CARDVSAM
    AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS), plus AIX/PATH/BLDINDEX which
    are replaced by a PostgreSQL secondary index on card.acct_id in
    V002__create_card.sql)

Source record layout (app/cpy/CVACT02Y.cpy — CARD-RECORD, RECLN 150):

    CARD-NUM             PIC X(16)        offset 1..16   (primary key)
    CARD-ACCT-ID         PIC 9(11)        offset 17..27  (FK to accounts.acct_id)
    CARD-CVV-CD          PIC 9(03)        offset 28..30  (READ BUT NOT STORED)
    CARD-EMBOSSED-NAME   PIC X(50)        offset 31..80
    CARD-EXPIRAION-DATE  PIC X(10)        offset 81..90  (ISO 8601 YYYY-MM-DD)
    CARD-ACTIVE-STATUS   PIC X(01)        offset 91..91
    FILLER               PIC X(59)        offset 92..150 (ignored)

Per AAP §0.7.3 Minimal Change Clause, the parsing logic mirrors the
COBOL PIC layout exactly. Card numbers retain their string form (PIC
X(16)) since they are non-numeric in the JPA entity (``cards.card_num
CHAR(16)``) and may carry leading zeros that would be lost in an
integer conversion.

PCI-DSS Remediation (V017__drop_card_cvv_column.sql):
    Per Code Review CP7 Critical finding (Glue schema vs Flyway DDL
    contract drift) and AAP §0.6.6, the ``card_cvv_cd`` column was
    dropped from the ``cards`` table by V017 because PCI-DSS prohibits
    the post-authorization storage of CVV/CVV2 values ("sensitive
    authentication data" per PCI-DSS Requirement 3.2). This Glue
    loader continues to consume the 3-byte CVV field from the fixed-
    width source fixture (so offsets for downstream fields remain
    correct) but **does not** include ``card_cvv_cd`` in the output
    DataFrame schema or the JDBC write. The CVV value is read,
    immediately discarded, and never logged.

Date Handling (V002__create_card.sql declares ``card_expiration_date
DATE NOT NULL``):
    The COBOL CARD-EXPIRAION-DATE field (PIC X(10)) is captured in
    ISO 8601 format (``YYYY-MM-DD``). This loader parses each value
    into a ``datetime.date`` instance before assembling the row dict
    so that Spark's ``DateType`` cast — and the PostgreSQL JDBC
    writer — receive a properly typed date rather than a free-form
    string. This eliminates the previous schema drift where a
    nullable ``StringType`` flowed into a ``NOT NULL DATE`` column.

Job arguments (passed by Terraform default_arguments or by the Step
Functions ``startJobRun.sync`` Arguments map):

    --connection_name   Name of the aws_glue_connection.rds resource.
    --source_bucket     S3 bucket holding the ASCII fixture.
    --source_key        Object key of the fixture (``fixtures/ascii/carddata.txt``).
    --target_table      Target RDS table name (``cards``).
    --TempDir           Glue temp directory.
"""

from __future__ import annotations

import datetime
import sys
from typing import Optional

from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import SparkSession
from pyspark.sql.types import (
    DateType,
    LongType,
    StringType,
    StructField,
    StructType,
)


# Card schema — mirrors V002__create_card.sql column definitions and
# V017__drop_card_cvv_column.sql (CVV removed). Order intentionally
# matches the cards table column order for clarity, with every field
# declared non-nullable to match the Flyway NOT NULL constraints.
# COBOL: CVACT02Y.cpy / CARD-RECORD (RECLN 150) — see file header for
# byte offsets. card_cvv_cd intentionally omitted post-V017.
CARD_SCHEMA = StructType(
    [
        StructField("card_num", StringType(), nullable=False),
        StructField("card_acct_id", LongType(), nullable=False),
        StructField("card_embossed_name", StringType(), nullable=False),
        StructField("card_expiration_date", DateType(), nullable=False),
        StructField("card_active_status", StringType(), nullable=False),
    ]
)


def parse_card_record(line: str) -> dict:
    """Parse one 150-byte ASCII card record into a dict matching the
    ``cards`` table column names.

    Behavior summary:
        * Reads and discards the 3-byte CARD-CVV-CD field (PCI-DSS
          compliance — V017 dropped the storage column).
        * Converts the 10-byte CARD-EXPIRAION-DATE (ISO 8601
          ``YYYY-MM-DD``) to a ``datetime.date`` object so Spark/JDBC
          writes a typed DATE rather than a string.

    Raises:
        ValueError: when ``line`` is ``None``, the record is shorter
            than 92 bytes (the smallest extent containing every
            stored field), or the expiration date is unparseable.
    """
    if line is None:
        raise ValueError("Card record is None")
    if len(line) < 92:
        raise ValueError(
            "Card record must be at least 92 bytes (excluding 59-byte "
            f"FILLER); got {len(line)} bytes"
        )

    # CARD-CVV-CD bytes are intentionally read past — we advance the
    # offset cursor but do not retain the value. Capturing it in a
    # named variable would risk it leaking into logs or stack traces.
    expiration_raw = line[80:90].strip()
    try:
        expiration_date = datetime.date.fromisoformat(expiration_raw)
    except ValueError as exc:  # pragma: no cover — defensive
        raise ValueError(
            "card_expiration_date must be an ISO 8601 date (YYYY-MM-DD); "
            f"got '{expiration_raw}'"
        ) from exc

    return {
        "card_num": line[0:16],
        "card_acct_id": int(line[16:27]),
        # card_cvv_cd intentionally omitted — V017 dropped the column
        # for PCI-DSS compliance; the source fixture still carries the
        # 3-byte field at offset 28..30 so downstream byte offsets
        # remain aligned, but it is never persisted.
        "card_embossed_name": line[30:80].rstrip(),
        "card_expiration_date": expiration_date,
        "card_active_status": line[90:91],
    }


def main() -> None:
    """Entry point — wired by the AWS Glue runtime when the job starts."""
    args = getResolvedOptions(
        sys.argv,
        [
            "JOB_NAME",
            "connection_name",
            "source_bucket",
            "source_key",
            "target_table",
        ],
    )

    sc = SparkContext()
    glue_context = GlueContext(sc)
    spark: SparkSession = glue_context.spark_session
    job = Job(glue_context)
    job.init(args["JOB_NAME"], args)

    source_uri = f"s3://{args['source_bucket']}/{args['source_key']}"
    raw_df = spark.read.text(source_uri)

    parsed_rdd = raw_df.rdd.map(lambda row: parse_card_record(row["value"]))
    parsed_df = spark.createDataFrame(parsed_rdd, schema=CARD_SCHEMA)

    parsed_df.write.format("jdbc").option(
        "connectionName", args["connection_name"]
    ).option("dbtable", args["target_table"]).mode("append").save()

    job.commit()


if __name__ == "__main__":
    main()
