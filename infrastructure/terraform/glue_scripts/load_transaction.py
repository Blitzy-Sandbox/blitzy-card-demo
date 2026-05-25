"""
load_transaction.py
===================

AWS Glue PySpark ETL job — bulk-load the Transaction fact table (or the
DailyTransaction staging table) from an S3-staged fixed-width ASCII
fixture into the CardDemo RDS PostgreSQL ``transactions`` /
``daily_transactions`` table.

Replaces:
    app/jcl/TRANFILE.jcl STEP15 (IDCAMS REPRO INFILE(TRANSACT
    AWS.M2.CARDDEMO.DALYTRAN.PS.INIT) OUTFILE(TRANVSAM
    AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS), plus AIX/PATH/BLDINDEX which
    are replaced by a PostgreSQL composite index on
    (tran_card_num, tran_proc_ts) in V005__create_transaction.sql
    (replaces TRANSACT.VSAM.AIX KEYS(26,304) NONUNIQUEKEY UPGRADE).

Source record layout (app/cpy/CVTRA05Y.cpy — TRAN-RECORD, RECLN 350):

    TRAN-ID                PIC X(16)        offset 1..16    (16 bytes, primary key)
    TRAN-TYPE-CD           PIC X(02)        offset 17..18   (2 bytes)
    TRAN-CAT-CD            PIC 9(04)        offset 19..22   (4 bytes)
    TRAN-SOURCE            PIC X(10)        offset 23..32   (10 bytes)
    TRAN-DESC              PIC X(100)       offset 33..132  (100 bytes)
    TRAN-AMT               PIC S9(09)V99    offset 133..143 (11 bytes — zoned-decimal w/ trailing sign overpunch)
    TRAN-MERCHANT-ID       PIC 9(09)        offset 144..152 (9 bytes)
    TRAN-MERCHANT-NAME     PIC X(50)        offset 153..202 (50 bytes)
    TRAN-MERCHANT-CITY     PIC X(50)        offset 203..252 (50 bytes)
    TRAN-MERCHANT-ZIP      PIC X(10)        offset 253..262 (10 bytes)
    TRAN-CARD-NUM          PIC X(16)        offset 263..278 (16 bytes, AIX prefix)
    TRAN-ORIG-TS           PIC X(26)        offset 279..304 (26 bytes)
    TRAN-PROC-TS           PIC X(26)        offset 305..330 (26 bytes, AIX suffix)
    FILLER                 PIC X(20)        offset 331..350 (20 bytes, ignored)

The TRAN-AMT field is PIC S9(09)V99 — 9 integer digits + 2 fractional
digits = 11 digit positions. In IBM zoned-decimal USAGE DISPLAY the
field occupies exactly 11 bytes: 10 plain digit bytes followed by 1
trailing sign-overpunch byte that decodes to (sign, units digit). The
implementation mirrors the load_account.py zoned-decimal parser.

Per AAP 0.7.3 Minimal Change Clause the parse logic mirrors the COBOL
PIC layout exactly. Monetary values are parsed to Python ``Decimal``
and written as PostgreSQL NUMERIC(11,2) per V005 Flyway migration and
AAP 0.6.1 (BigDecimal precision contract for COBOL PIC S9(9)V99).

Job arguments (Terraform default_arguments / Step Functions Arguments):

    --connection_name   Name of the aws_glue_connection.rds resource.
    --source_bucket     S3 bucket holding the ASCII fixture.
    --source_key        Object key of the fixture
                        (``fixtures/ascii/dailytran.txt`` for the
                        DALYTRAN staging load).
    --target_table      Target RDS table name (``daily_transactions``
                        for the initial DALYTRAN load; ``transactions``
                        if used to seed the master).
    --TempDir           Glue temp directory.
"""

from __future__ import annotations

import sys
from decimal import Decimal, ROUND_HALF_EVEN
from typing import Optional

from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import SparkSession
from pyspark.sql.types import (
    DecimalType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)


# Zoned-decimal overpunch translation tables (identical convention to
# load_account.py). See load_account.py for the table commentary.
POSITIVE_OVERPUNCH = {
    "{": "0",
    "A": "1",
    "B": "2",
    "C": "3",
    "D": "4",
    "E": "5",
    "F": "6",
    "G": "7",
    "H": "8",
    "I": "9",
}

NEGATIVE_OVERPUNCH = {
    "}": "0",
    "J": "1",
    "K": "2",
    "L": "3",
    "M": "4",
    "N": "5",
    "O": "6",
    "P": "7",
    "Q": "8",
    "R": "9",
}


def parse_zoned_decimal_s9_9_v2(field: str) -> Optional[Decimal]:
    """Translate an 11-character PIC S9(09)V99 zoned-decimal field to
    ``Decimal`` with 2-decimal-place rounding (HALF_EVEN).

    Layout: 10 plain digit bytes + 1 trailing sign-overpunch byte. In
    IBM zoned-decimal USAGE DISPLAY the rightmost byte carries BOTH the
    sign and the units digit (the rightmost fractional digit for V99).
    The decimal point is implied between the 9th and 10th digit
    positions of the expanded 11-digit value (``V99``).
    """
    if field is None:
        return None

    s = field.strip()
    if s == "":
        return None

    if len(field) != 11:
        raise ValueError(
            "PIC S9(09)V99 field must be exactly 11 bytes; got "
            f"{len(field)} bytes: {field!r}"
        )

    leading_digits = field[:10]
    trailing_byte = field[10]

    if not leading_digits.isdigit():
        raise ValueError(
            "PIC S9(09)V99 leading 10 bytes must be digits; got "
            f"{leading_digits!r}"
        )

    if trailing_byte in POSITIVE_OVERPUNCH:
        sign = ""
        last_digit = POSITIVE_OVERPUNCH[trailing_byte]
    elif trailing_byte in NEGATIVE_OVERPUNCH:
        sign = "-"
        last_digit = NEGATIVE_OVERPUNCH[trailing_byte]
    elif trailing_byte.isdigit():
        sign = ""
        last_digit = trailing_byte
    else:
        raise ValueError(
            "PIC S9(09)V99 trailing byte must be a digit or zoned-decimal "
            f"sign-overpunch; got {trailing_byte!r}"
        )

    # Reassemble the 11 logical digit positions: 10 plain leading digits +
    # 1 units digit decoded from the trailing overpunch. The COBOL V99
    # specifier puts the implied decimal point between the 9th and 10th
    # logical digits, so:
    #   raw    = D1 D2 D3 D4 D5 D6 D7 D8 D9 F1 F2   (11 digits)
    #   value  = D1..D9 . F1 F2                    (9 integer, 2 fractional)
    raw = sign + leading_digits + last_digit  # 11 digits with optional '-' prefix
    integer_part = raw[:-2]
    fractional_part = raw[-2:]
    value = Decimal(f"{integer_part}.{fractional_part}")
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)


TRANSACTION_SCHEMA = StructType(
    [
        StructField("tran_id", StringType(), nullable=False),
        StructField("tran_type_cd", StringType(), nullable=False),
        StructField("tran_cat_cd", IntegerType(), nullable=False),
        StructField("tran_source", StringType(), nullable=True),
        StructField("tran_desc", StringType(), nullable=True),
        StructField("tran_amt", DecimalType(11, 2), nullable=False),
        StructField("tran_merchant_id", LongType(), nullable=True),
        StructField("tran_merchant_name", StringType(), nullable=True),
        StructField("tran_merchant_city", StringType(), nullable=True),
        StructField("tran_merchant_zip", StringType(), nullable=True),
        StructField("tran_card_num", StringType(), nullable=False),
        StructField("tran_orig_ts", StringType(), nullable=True),
        StructField("tran_proc_ts", StringType(), nullable=True),
    ]
)


def _opt_strip(value: str) -> Optional[str]:
    """Return ``value.rstrip()`` or ``None`` if the result is empty."""
    if value is None:
        return None
    stripped = value.rstrip()
    return stripped if stripped else None


def parse_transaction_record(line: str) -> dict:
    """Parse one 350-byte ASCII transaction record into a dict matching
    the ``transactions`` (or ``daily_transactions``) table column names.

    Byte offsets (0-indexed, end-exclusive — Python slice form):
        line[  0: 16]  TRAN-ID                PIC X(16)      16 bytes
        line[ 16: 18]  TRAN-TYPE-CD           PIC X(02)       2 bytes
        line[ 18: 22]  TRAN-CAT-CD            PIC 9(04)       4 bytes
        line[ 22: 32]  TRAN-SOURCE            PIC X(10)      10 bytes
        line[ 32:132]  TRAN-DESC              PIC X(100)    100 bytes
        line[132:143]  TRAN-AMT               PIC S9(09)V99  11 bytes
        line[143:152]  TRAN-MERCHANT-ID       PIC 9(09)       9 bytes
        line[152:202]  TRAN-MERCHANT-NAME     PIC X(50)      50 bytes
        line[202:252]  TRAN-MERCHANT-CITY     PIC X(50)      50 bytes
        line[252:262]  TRAN-MERCHANT-ZIP      PIC X(10)      10 bytes
        line[262:278]  TRAN-CARD-NUM          PIC X(16)      16 bytes (AIX prefix)
        line[278:304]  TRAN-ORIG-TS           PIC X(26)      26 bytes
        line[304:330]  TRAN-PROC-TS           PIC X(26)      26 bytes (AIX suffix)
        line[330:350]  FILLER                 PIC X(20)      20 bytes (ignored)
    """
    if line is None:
        raise ValueError("Transaction record is None")
    if len(line) < 330:
        raise ValueError(
            "Transaction record must be at least 330 bytes (excluding "
            f"20-byte FILLER); got {len(line)} bytes"
        )

    merchant_id_raw = line[143:152].strip()
    merchant_id = int(merchant_id_raw) if merchant_id_raw and merchant_id_raw.isdigit() else None

    return {
        "tran_id": line[0:16],
        "tran_type_cd": line[16:18],
        "tran_cat_cd": int(line[18:22]),
        "tran_source": _opt_strip(line[22:32]),
        "tran_desc": _opt_strip(line[32:132]),
        "tran_amt": parse_zoned_decimal_s9_9_v2(line[132:143]),
        "tran_merchant_id": merchant_id,
        "tran_merchant_name": _opt_strip(line[152:202]),
        "tran_merchant_city": _opt_strip(line[202:252]),
        "tran_merchant_zip": _opt_strip(line[252:262]),
        "tran_card_num": line[262:278],
        "tran_orig_ts": _opt_strip(line[278:304]),
        "tran_proc_ts": _opt_strip(line[304:330]),
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

    parsed_rdd = raw_df.rdd.map(
        lambda row: parse_transaction_record(row["value"])
    )
    parsed_df = spark.createDataFrame(parsed_rdd, schema=TRANSACTION_SCHEMA)

    parsed_df.write.format("jdbc").option(
        "connectionName", args["connection_name"]
    ).option("dbtable", args["target_table"]).mode("append").save()

    job.commit()


if __name__ == "__main__":
    main()
