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
    CARD-CVV-CD          PIC 9(03)        offset 28..30
    CARD-EMBOSSED-NAME   PIC X(50)        offset 31..80
    CARD-EXPIRAION-DATE  PIC X(10)        offset 81..90
    CARD-ACTIVE-STATUS   PIC X(01)        offset 91..91
    FILLER               PIC X(59)        offset 92..150 (ignored)

Per AAP §0.7.3 Minimal Change Clause, the parsing logic mirrors the
COBOL PIC layout exactly. Card numbers retain their string form (PIC
X(16)) since they are non-numeric in the JPA entity (``cards.card_num
CHAR(16)``) and may carry leading zeros that would be lost in an
integer conversion.

Job arguments (passed by Terraform default_arguments or by the Step
Functions ``startJobRun.sync`` Arguments map):

    --connection_name   Name of the aws_glue_connection.rds resource.
    --source_bucket     S3 bucket holding the ASCII fixture.
    --source_key        Object key of the fixture (``fixtures/ascii/carddata.txt``).
    --target_table      Target RDS table name (``cards``).
    --TempDir           Glue temp directory.
"""

from __future__ import annotations

import sys
from typing import Optional

from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import SparkSession
from pyspark.sql.types import (
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)


CARD_SCHEMA = StructType(
    [
        StructField("card_num", StringType(), nullable=False),
        StructField("card_acct_id", LongType(), nullable=False),
        StructField("card_cvv_cd", IntegerType(), nullable=False),
        StructField("card_embossed_name", StringType(), nullable=False),
        StructField("card_expiration_date", StringType(), nullable=False),
        StructField("card_active_status", StringType(), nullable=False),
    ]
)


def parse_card_record(line: str) -> dict:
    """Parse one 150-byte ASCII card record into a dict matching the
    ``cards`` table column names.
    """
    if line is None:
        raise ValueError("Card record is None")
    if len(line) < 92:
        raise ValueError(
            "Card record must be at least 92 bytes (excluding 59-byte "
            f"FILLER); got {len(line)} bytes"
        )

    return {
        "card_num": line[0:16],
        "card_acct_id": int(line[16:27]),
        "card_cvv_cd": int(line[27:30]),
        "card_embossed_name": line[30:80].rstrip(),
        "card_expiration_date": line[80:90].strip(),
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
