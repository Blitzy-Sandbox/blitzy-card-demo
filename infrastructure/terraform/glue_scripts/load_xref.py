"""
load_xref.py
============

AWS Glue PySpark ETL job — bulk-load the Card Cross-Reference table from
an S3-staged fixed-width ASCII fixture into the CardDemo RDS PostgreSQL
``card_xref`` table.

Replaces:
    app/jcl/XREFFILE.jcl STEP15 (IDCAMS REPRO INFILE(XREFDATA
    AWS.M2.CARDDEMO.CARDXREF.PS) OUTFILE(XREFVSAM
    AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)), plus AIX/PATH/BLDINDEX which
    are replaced by a PostgreSQL secondary index on
    ``card_xref.xref_acct_id`` in V004__create_cardxref.sql (replaces
    CXACAIX AIX KEYS(11,25) NONUNIQUEKEY UPGRADE).

Source record layout (app/cpy/CVACT03Y.cpy — CARD-XREF-RECORD, RECLN 50):

    XREF-CARD-NUM        PIC X(16)        offset 1..16   (primary key)
    XREF-CUST-ID         PIC 9(09)        offset 17..25
    XREF-ACCT-ID         PIC 9(11)        offset 26..36  (AIX key — KEYS(11,25))
    FILLER               PIC X(14)        offset 37..50 (ignored)

Per AAP §0.7.3 Minimal Change Clause the parse logic mirrors the COBOL
PIC layout exactly. The XREF-ACCT-ID secondary index is enforced at the
DDL level (V004 Flyway migration) rather than at load time — bulk
inserts ignore the index until the load completes, which is the
PostgreSQL equivalent of the IDCAMS BLDINDEX-after-REPRO pattern.

Job arguments (Terraform default_arguments / Step Functions Arguments):

    --connection_name   Name of the aws_glue_connection.rds resource.
    --source_bucket     S3 bucket holding the ASCII fixture.
    --source_key        Object key of the fixture (``fixtures/ascii/cardxref.txt``).
    --target_table      Target RDS table name (``card_xref``).
    --TempDir           Glue temp directory.
"""

from __future__ import annotations

import sys

from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import SparkSession
from pyspark.sql.types import (
    LongType,
    StringType,
    StructField,
    StructType,
)


XREF_SCHEMA = StructType(
    [
        StructField("xref_card_num", StringType(), nullable=False),
        StructField("xref_cust_id", LongType(), nullable=False),
        StructField("xref_acct_id", LongType(), nullable=False),
    ]
)


def parse_xref_record(line: str) -> dict:
    """Parse one 50-byte ASCII card cross-reference record into a dict
    matching the ``card_xref`` table column names.
    """
    if line is None:
        raise ValueError("Cross-reference record is None")
    if len(line) < 36:
        raise ValueError(
            "Cross-reference record must be at least 36 bytes (excluding "
            f"14-byte FILLER); got {len(line)} bytes"
        )

    return {
        "xref_card_num": line[0:16],
        "xref_cust_id": int(line[16:25]),
        "xref_acct_id": int(line[25:36]),
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

    parsed_rdd = raw_df.rdd.map(lambda row: parse_xref_record(row["value"]))
    parsed_df = spark.createDataFrame(parsed_rdd, schema=XREF_SCHEMA)

    parsed_df.write.format("jdbc").option(
        "connectionName", args["connection_name"]
    ).option("dbtable", args["target_table"]).mode("append").save()

    job.commit()


if __name__ == "__main__":
    main()
