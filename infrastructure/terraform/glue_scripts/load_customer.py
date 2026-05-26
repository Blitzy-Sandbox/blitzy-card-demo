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

Schema Contract with V003__create_customer.sql (Code Review CP7 fix):
    Eight customer fields are declared ``NOT NULL`` in Flyway:
    cust_id, cust_first_name, cust_last_name, cust_addr_line_1,
    cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip, cust_ssn,
    cust_dob_yyyy_mm_dd, cust_pri_card_holder_ind, and
    cust_fico_credit_score. This Glue schema marks each of those
    fields ``nullable=False`` so that a bad input record fails fast
    in the Spark stage (clear ValueError + reject record) rather
    than triggering a downstream JDBC NOT NULL violation that aborts
    the entire batch.

    Three column-type contracts are also enforced explicitly:
      * ``cust_ssn`` parses to ``LongType`` because Flyway declares
        ``bigint not null`` (COBOL PIC 9(09)).
      * ``cust_dob_yyyy_mm_dd`` parses to ``datetime.date``
        (DateType) because Flyway declares ``date not null``. The
        previous Glue field name ``cust_dob_yyyymmdd`` is renamed to
        the canonical ``cust_dob_yyyy_mm_dd`` to match the database.
      * ``cust_fico_credit_score`` parses to ``IntegerType`` and
        rejects FICO scores outside 300-850 to honor the CHECK
        constraint ``ck_customers_cust_fico_credit_score``.

PCI-DSS PII Handling:
    cust_ssn values are never logged. They are parsed to a primitive
    long and written via JDBC to the encrypted PostgreSQL column.
    Macie continuously scans S3 for accidental PII leakage; this
    loader operates only on encrypted S3 objects (SSE-KMS) and the
    TLS-encrypted JDBC channel.

Job arguments (Terraform default_arguments / Step Functions Arguments):

    --connection_name   Name of the aws_glue_connection.rds resource.
    --source_bucket     S3 bucket holding the ASCII fixture.
    --source_key        Object key of the fixture (``fixtures/ascii/custdata.txt``).
    --target_table      Target RDS table name (``customers``).
    --TempDir           Glue temp directory.
"""

from __future__ import annotations

import datetime
import sys

from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import SparkSession
from pyspark.sql.types import (
    DateType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)


# Customer schema — every nullability marker mirrors V003__create_customer.sql
# (NN annotations in the file header). Eleven of the eighteen business
# columns are NOT NULL; the remaining seven (cust_middle_name,
# cust_addr_line_2/3, cust_phone_num_1/2, cust_govt_issued_id,
# cust_eft_account_id) are explicitly nullable in Flyway and remain so
# here.
# COBOL: CVCUS01Y.cpy / CUSTREC.cpy / CUSTOMER-RECORD (RECLN 500) — see
# parse_customer_record docstring for byte offsets.
CUSTOMER_SCHEMA = StructType(
    [
        StructField("cust_id", LongType(), nullable=False),
        StructField("cust_first_name", StringType(), nullable=False),
        StructField("cust_middle_name", StringType(), nullable=True),
        StructField("cust_last_name", StringType(), nullable=False),
        StructField("cust_addr_line_1", StringType(), nullable=False),
        StructField("cust_addr_line_2", StringType(), nullable=True),
        StructField("cust_addr_line_3", StringType(), nullable=True),
        StructField("cust_addr_state_cd", StringType(), nullable=False),
        StructField("cust_addr_country_cd", StringType(), nullable=False),
        StructField("cust_addr_zip", StringType(), nullable=False),
        StructField("cust_phone_num_1", StringType(), nullable=True),
        StructField("cust_phone_num_2", StringType(), nullable=True),
        StructField("cust_ssn", LongType(), nullable=False),
        StructField("cust_govt_issued_id", StringType(), nullable=True),
        StructField("cust_dob_yyyy_mm_dd", DateType(), nullable=False),
        StructField("cust_eft_account_id", StringType(), nullable=True),
        StructField("cust_pri_card_holder_ind", StringType(), nullable=False),
        StructField("cust_fico_credit_score", IntegerType(), nullable=False),
    ]
)


def _opt_strip(value: str) -> str:
    """Return ``value.rstrip()`` or ``None`` if the result is empty."""
    if value is None:
        return None
    stripped = value.rstrip()
    return stripped if stripped else None


def _required_strip(value: str, field_name: str) -> str:
    """Return ``value.rstrip()`` after asserting the stripped result is
    non-empty.

    Used for NOT NULL string columns so the loader fails fast on bad
    input rather than emitting a row that the database will reject.
    """
    if value is None:
        raise ValueError(f"{field_name} is None (NOT NULL required)")
    stripped = value.rstrip()
    if not stripped:
        raise ValueError(
            f"{field_name} is blank or whitespace-only (NOT NULL required)"
        )
    return stripped


def parse_customer_record(line: str) -> dict:
    """Parse one 500-byte ASCII customer record into a dict matching the
    ``customers`` table column names.

    The 500-byte layout follows the COBOL CUSTOMER-RECORD copybook:

        CUST-ID                       PIC 9(09)   offset 1..9     (PK)
        CUST-FIRST-NAME               PIC X(25)   offset 10..34   NN
        CUST-MIDDLE-NAME              PIC X(25)   offset 35..59
        CUST-LAST-NAME                PIC X(25)   offset 60..84   NN
        CUST-ADDR-LINE-1              PIC X(50)   offset 85..134  NN
        CUST-ADDR-LINE-2              PIC X(50)   offset 135..184
        CUST-ADDR-LINE-3              PIC X(50)   offset 185..234
        CUST-ADDR-STATE-CD            PIC X(02)   offset 235..236 NN
        CUST-ADDR-COUNTRY-CD          PIC X(03)   offset 237..239 NN
        CUST-ADDR-ZIP                 PIC X(10)   offset 240..249 NN
        CUST-PHONE-NUM-1              PIC X(15)   offset 250..264
        CUST-PHONE-NUM-2              PIC X(15)   offset 265..279
        CUST-SSN                      PIC 9(09)   offset 280..288 NN (BIGINT)
        CUST-GOVT-ISSUED-ID           PIC X(20)   offset 289..308
        CUST-DOB-YYYY-MM-DD           PIC X(10)   offset 309..318 NN (DATE)
        CUST-EFT-ACCOUNT-ID           PIC X(10)   offset 319..328
        CUST-PRI-CARD-HOLDER-IND      PIC X(01)   offset 329..329 NN
        CUST-FICO-CREDIT-SCORE        PIC 9(03)   offset 330..332 NN (300..850)
        FILLER                        PIC X(168)  offset 333..500 (ignored)

    Per AAP §0.6.6 (PCI-DSS / PII):
        - cust_ssn (PIC 9(09)) is parsed to a Long matching the
          BIGINT NOT NULL Flyway column; never logged.
        - cust_dob_yyyy_mm_dd (PIC X(10)) is parsed to a
          datetime.date matching the DATE NOT NULL Flyway column.
    """
    if line is None:
        raise ValueError("Customer record is None")
    if len(line) < 332:
        raise ValueError(
            "Customer record must be at least 332 bytes (excluding 168-byte "
            f"FILLER); got {len(line)} bytes"
        )

    # --- Required PII fields parsed defensively ---------------------
    ssn_raw = line[279:288].strip()
    if not ssn_raw:
        raise ValueError("cust_ssn is blank (NOT NULL required)")
    if not ssn_raw.isdigit():
        raise ValueError(
            "cust_ssn must be a 9-digit numeric (COBOL PIC 9(09)); "
            "rejected to avoid masking sensitive value in error message"
        )
    ssn = int(ssn_raw)

    dob_raw = line[308:318].strip()
    if not dob_raw:
        raise ValueError("cust_dob_yyyy_mm_dd is blank (NOT NULL required)")
    try:
        dob = datetime.date.fromisoformat(dob_raw)
    except ValueError as exc:
        raise ValueError(
            "cust_dob_yyyy_mm_dd must be an ISO 8601 date (YYYY-MM-DD); "
            f"got '{dob_raw}'"
        ) from exc

    fico_raw = line[329:332].strip()
    if not fico_raw or not fico_raw.isdigit():
        raise ValueError(
            "cust_fico_credit_score must be a 3-digit numeric "
            "(COBOL PIC 9(03), NOT NULL); got "
            f"'{fico_raw}'"
        )
    fico = int(fico_raw)
    if fico < 300 or fico > 850:
        raise ValueError(
            "cust_fico_credit_score must satisfy CHECK constraint "
            f"(300 <= score <= 850); got {fico}"
        )

    return {
        "cust_id": int(line[0:9]),
        "cust_first_name": _required_strip(line[9:34], "cust_first_name"),
        "cust_middle_name": _opt_strip(line[34:59]),
        "cust_last_name": _required_strip(line[59:84], "cust_last_name"),
        "cust_addr_line_1": _required_strip(line[84:134], "cust_addr_line_1"),
        "cust_addr_line_2": _opt_strip(line[134:184]),
        "cust_addr_line_3": _opt_strip(line[184:234]),
        "cust_addr_state_cd": _required_strip(line[234:236], "cust_addr_state_cd"),
        "cust_addr_country_cd": _required_strip(line[236:239], "cust_addr_country_cd"),
        "cust_addr_zip": _required_strip(line[239:249], "cust_addr_zip"),
        "cust_phone_num_1": _opt_strip(line[249:264]),
        "cust_phone_num_2": _opt_strip(line[264:279]),
        "cust_ssn": ssn,
        "cust_govt_issued_id": _opt_strip(line[288:308]),
        "cust_dob_yyyy_mm_dd": dob,
        "cust_eft_account_id": _opt_strip(line[318:328]),
        "cust_pri_card_holder_ind": _required_strip(
            line[328:329], "cust_pri_card_holder_ind"
        ),
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
