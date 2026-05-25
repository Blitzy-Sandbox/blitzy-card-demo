"""
load_customer.py
================

AWS Glue PySpark ETL job — bulk-load the Customer fact table from an
S3-staged fixed-width ASCII fixture into the CardDemo RDS PostgreSQL
``customers`` table.

Replaces:
    app/jcl/CUSTFILE.jcl STEP15 (IDCAMS REPRO INFILE(CUSTDATA
    AWS.M2.CARDDEMO.CUSTDATA.PS) OUTFILE(CUSTVSAM
    AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS))

Source record layout (app/cpy/CVCUS01Y.cpy / CUSTREC.cpy — CUSTOMER-RECORD,
RECLN 500). The Customer record is the widest fixed-width record in the
CardDemo dataset; the layout includes name, address, phone, SSN, FICO
score, and other PII fields that drive the PCI-DSS classification of the
``customers`` table.

Per AAP §0.6.6 every CardDemo data store containing PII is encrypted at
rest with the KMS CMK; the JDBC connection used by this Glue job
negotiates TLS with the RDS instance (``rds.force_ssl=1`` parameter
group in rds.tf) so the in-transit data is encrypted as well.

Per AAP §0.7.3 Minimal Change Clause the parse logic mirrors the COBOL
PIC layout exactly. Customer IDs are PIC 9(09) and are parsed to Long
to match the JPA entity.

Job arguments (Terraform default_arguments / Step Functions Arguments):

    --connection_name   Name of the aws_glue_connection.rds resource.
    --source_bucket     S3 bucket holding the ASCII fixture.
    --source_key        Object key of the fixture (``fixtures/ascii/custdata.txt``).
    --target_table      Target RDS table name (``customers``).
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
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)


CUSTOMER_SCHEMA = StructType(
    [
        StructField("cust_id", LongType(), nullable=False),
        StructField("cust_first_name", StringType(), nullable=False),
        StructField("cust_middle_name", StringType(), nullable=True),
        StructField("cust_last_name", StringType(), nullable=False),
        StructField("cust_addr_line_1", StringType(), nullable=True),
        StructField("cust_addr_line_2", StringType(), nullable=True),
        StructField("cust_addr_line_3", StringType(), nullable=True),
        StructField("cust_addr_state_cd", StringType(), nullable=True),
        StructField("cust_addr_country_cd", StringType(), nullable=True),
        StructField("cust_addr_zip", StringType(), nullable=True),
        StructField("cust_phone_num_1", StringType(), nullable=True),
        StructField("cust_phone_num_2", StringType(), nullable=True),
        StructField("cust_ssn", StringType(), nullable=True),
        StructField("cust_govt_issued_id", StringType(), nullable=True),
        StructField("cust_dob_yyyymmdd", StringType(), nullable=True),
        StructField("cust_eft_account_id", StringType(), nullable=True),
        StructField("cust_pri_card_holder_ind", StringType(), nullable=True),
        StructField("cust_fico_credit_score", IntegerType(), nullable=True),
    ]
)


def _opt_strip(value: str) -> str:
    """Return ``value.rstrip()`` or ``None`` if the result is empty."""
    if value is None:
        return None
    stripped = value.rstrip()
    return stripped if stripped else None


def parse_customer_record(line: str) -> dict:
    """Parse one 500-byte ASCII customer record into a dict matching the
    ``customers`` table column names.

    The 500-byte layout follows the COBOL CUSTOMER-RECORD copybook:

        CUST-ID                       PIC 9(09)   offset 1..9
        CUST-FIRST-NAME               PIC X(25)   offset 10..34
        CUST-MIDDLE-NAME              PIC X(25)   offset 35..59
        CUST-LAST-NAME                PIC X(25)   offset 60..84
        CUST-ADDR-LINE-1              PIC X(50)   offset 85..134
        CUST-ADDR-LINE-2              PIC X(50)   offset 135..184
        CUST-ADDR-LINE-3              PIC X(50)   offset 185..234
        CUST-ADDR-STATE-CD            PIC X(02)   offset 235..236
        CUST-ADDR-COUNTRY-CD          PIC X(03)   offset 237..239
        CUST-ADDR-ZIP                 PIC X(10)   offset 240..249
        CUST-PHONE-NUM-1              PIC X(15)   offset 250..264
        CUST-PHONE-NUM-2              PIC X(15)   offset 265..279
        CUST-SSN                      PIC X(09)   offset 280..288
        CUST-GOVT-ISSUED-ID           PIC X(20)   offset 289..308
        CUST-DOB-YYYYMMDD             PIC X(10)   offset 309..318
        CUST-EFT-ACCOUNT-ID           PIC X(10)   offset 319..328
        CUST-PRI-CARD-HOLDER-IND      PIC X(01)   offset 329..329
        CUST-FICO-CREDIT-SCORE        PIC 9(03)   offset 330..332
        FILLER                        PIC X(168)  offset 333..500 (ignored)
    """
    if line is None:
        raise ValueError("Customer record is None")
    if len(line) < 332:
        raise ValueError(
            "Customer record must be at least 332 bytes (excluding 168-byte "
            f"FILLER); got {len(line)} bytes"
        )

    fico_raw = line[329:332].strip()
    fico = int(fico_raw) if fico_raw and fico_raw.isdigit() else None

    return {
        "cust_id": int(line[0:9]),
        "cust_first_name": line[9:34].rstrip(),
        "cust_middle_name": _opt_strip(line[34:59]),
        "cust_last_name": line[59:84].rstrip(),
        "cust_addr_line_1": _opt_strip(line[84:134]),
        "cust_addr_line_2": _opt_strip(line[134:184]),
        "cust_addr_line_3": _opt_strip(line[184:234]),
        "cust_addr_state_cd": _opt_strip(line[234:236]),
        "cust_addr_country_cd": _opt_strip(line[236:239]),
        "cust_addr_zip": _opt_strip(line[239:249]),
        "cust_phone_num_1": _opt_strip(line[249:264]),
        "cust_phone_num_2": _opt_strip(line[264:279]),
        "cust_ssn": _opt_strip(line[279:288]),
        "cust_govt_issued_id": _opt_strip(line[288:308]),
        "cust_dob_yyyymmdd": _opt_strip(line[308:318]),
        "cust_eft_account_id": _opt_strip(line[318:328]),
        "cust_pri_card_holder_ind": _opt_strip(line[328:329]),
        "cust_fico_credit_score": fico,
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

    parsed_rdd = raw_df.rdd.map(lambda row: parse_customer_record(row["value"]))
    parsed_df = spark.createDataFrame(parsed_rdd, schema=CUSTOMER_SCHEMA)

    parsed_df.write.format("jdbc").option(
        "connectionName", args["connection_name"]
    ).option("dbtable", args["target_table"]).mode("append").save()

    job.commit()


if __name__ == "__main__":
    main()
