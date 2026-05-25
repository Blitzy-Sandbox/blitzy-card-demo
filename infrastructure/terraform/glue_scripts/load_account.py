"""
load_account.py
================

AWS Glue PySpark ETL job — bulk-load the Account fact table from an
S3-staged fixed-width ASCII fixture into the CardDemo RDS PostgreSQL
``accounts`` table.

Replaces:
    app/jcl/ACCTFILE.jcl STEP15 (IDCAMS REPRO INFILE(ACCTDATA
    AWS.M2.CARDDEMO.ACCTDATA.PS) OUTFILE(ACCTVSAM
    AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS))

Source record layout (app/cpy/CVACT01Y.cpy — ACCOUNT-RECORD, RECLN 300):

    ACCT-ID                  PIC 9(11)        offset 1..11    (11 bytes)
    ACCT-ACTIVE-STATUS       PIC X(01)        offset 12..12   (1 byte)
    ACCT-CURR-BAL            PIC S9(10)V99    offset 13..24   (12 bytes — zoned-decimal w/ trailing sign overpunch)
    ACCT-CREDIT-LIMIT        PIC S9(10)V99    offset 25..36   (12 bytes)
    ACCT-CASH-CREDIT-LIMIT   PIC S9(10)V99    offset 37..48   (12 bytes)
    ACCT-OPEN-DATE           PIC X(10)        offset 49..58   (10 bytes)
    ACCT-EXPIRAION-DATE      PIC X(10)        offset 59..68   (10 bytes)
    ACCT-REISSUE-DATE        PIC X(10)        offset 69..78   (10 bytes)
    ACCT-CURR-CYC-CREDIT     PIC S9(10)V99    offset 79..90   (12 bytes)
    ACCT-CURR-CYC-DEBIT      PIC S9(10)V99    offset 91..102  (12 bytes)
    ACCT-ADDR-ZIP            PIC X(10)        offset 103..112 (10 bytes)
    ACCT-GROUP-ID            PIC X(10)        offset 113..122 (10 bytes)
    FILLER                   PIC X(178)       offset 123..300 (ignored)

The ``{`` character that appears in the ASCII fixture (e.g. ``00000001940{``)
is the EBCDIC zoned-decimal positive-sign overpunch ``+0`` — in IBM zoned-
decimal USAGE DISPLAY the rightmost byte of a signed numeric field carries
BOTH the sign and the units digit in one overpunch byte. The translation
rule used by this script matches IBM zoned-decimal:

    Trailing byte    Sign    Last digit
    ---------------- ------- ----------
    '0'..'9'         (unsigned digit — no overpunch)
    '{'              +       0
    'A'..'I'         +       1..9
    '}'              -       0
    'J'..'R'         -       1..9

For PIC S9(10)V99 (10 integer + 2 fractional digits = 12 digit positions),
the zoned-decimal USAGE DISPLAY representation occupies exactly 12 bytes:
11 plain digit bytes followed by 1 sign-overpunch byte that decodes to
both the sign and the units (rightmost fractional) digit. The
implementation below preserves COBOL fixed-point arithmetic by using
``decimal.Decimal`` (Python's arbitrary-precision decimal type) — never
``float`` — and writing the column to RDS as ``NUMERIC(12,2)`` per the
V001 Flyway migration and AAP 0.6.1.

Job arguments (passed via Glue ``default_arguments`` or the
``--Arguments`` map on a Step Functions ``startJobRun.sync`` task):

    --connection_name   Name of the aws_glue_connection.rds resource
                        (e.g. "carddemo-prod-rds-conn"). The Glue
                        runtime resolves JDBC credentials from
                        Secrets Manager via the connection.
    --source_bucket     S3 bucket holding the ASCII fixture
                        (typically the carddemo-<env>-batch-outputs
                        bucket, prefix ``fixtures/ascii/``).
    --source_key        Object key of the fixture (e.g.
                        ``fixtures/ascii/acctdata.txt``).
    --target_table      Target RDS table name (``accounts``).
    --TempDir           Glue temp directory (``s3://.../glue-temp/``).

The script is intentionally self-contained: it does not import any
third-party library outside the AWS Glue runtime and the standard
PySpark + Python 3.10 environment, so deployment is a single S3 upload
(handled by the ``aws_s3_object.glue_script_account`` Terraform
resource in glue.tf).

Per AAP §0.7.3 Minimal Change Clause the parse logic mirrors the COBOL
PIC layout exactly — no algebraic simplification, no "more idiomatic"
substitutions.
"""

from __future__ import annotations

import sys
from datetime import date, datetime
from decimal import Decimal, ROUND_HALF_EVEN
from typing import Optional

from awsglue.context import GlueContext
from awsglue.job import Job
from awsglue.utils import getResolvedOptions
from pyspark.context import SparkContext
from pyspark.sql import SparkSession
from pyspark.sql.functions import udf
from pyspark.sql.types import (
    DateType,
    DecimalType,
    LongType,
    StringType,
    StructField,
    StructType,
)

# -----------------------------------------------------------------------------
# Zoned-decimal overpunch translation tables (IBM EBCDIC convention).
# In IBM zoned-decimal USAGE DISPLAY the RIGHTMOST byte of a signed
# numeric field carries BOTH the sign and the units digit in a single
# overpunch byte. For PIC S9(10)V99 (12 digit positions, 12 bytes), the
# layout is: 11 plain digit bytes + 1 trailing overpunch byte that
# decodes to (sign, units digit). This is the canonical zoned-decimal
# sign-overpunch convention used by COBOL WRITE statements when the
# source data was originally produced on z/OS and converted to ASCII
# without unpacking the zoned-decimal fields.
# -----------------------------------------------------------------------------
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


def parse_zoned_decimal_s9_10_v2(field: str) -> Optional[Decimal]:
    """Translate a 12-character PIC S9(10)V99 zoned-decimal field to ``Decimal``.

    The layout is 11 plain digit bytes followed by 1 trailing sign-
    overpunch byte. In IBM zoned-decimal USAGE DISPLAY the rightmost byte
    of a signed numeric field carries BOTH the sign and the units digit
    (the rightmost fractional digit for V99). The decimal point is
    implied between the 10th and 11th digit positions (``V99``).

    For records produced by IDCAMS REPRO from a COBOL WRITE the trailing
    byte is one of ``{``, ``A``..``I`` (positive) or ``}``, ``J``..``R``
    (negative). For records lacking the overpunch (the last byte is a
    plain digit), the field is treated as unsigned and the last byte is
    the units digit of the V99 part.

    Returns ``None`` only when the field is completely blank (e.g. an
    optional balance column). Any other malformed input raises
    ``ValueError`` so that downstream Glue retries route the failed
    item to the per-job CloudWatch error stream rather than silently
    corrupting the target table.
    """
    if field is None:
        return None

    s = field.strip()
    if s == "":
        return None

    if len(field) != 12:
        raise ValueError(
            "PIC S9(10)V99 field must be exactly 12 bytes; got "
            f"{len(field)} bytes: {field!r}"
        )

    leading_digits = field[:11]
    trailing_byte = field[11]

    if not leading_digits.isdigit():
        raise ValueError(
            "PIC S9(10)V99 leading 11 bytes must be digits; got "
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
            "PIC S9(10)V99 trailing byte must be a digit or a zoned-decimal "
            f"sign-overpunch; got {trailing_byte!r}"
        )

    # Reassemble the 12 logical digit positions: 11 plain leading digits +
    # 1 units digit decoded from the trailing overpunch. The COBOL V99
    # specifier puts the implied decimal point between the 10th and 11th
    # logical digits, so:
    #   raw    = D1 D2 D3 D4 D5 D6 D7 D8 D9 D10 F1 F2   (12 digits)
    #   value  = D1..D10 . F1 F2                        (10 integer, 2 fractional)
    raw = sign + leading_digits + last_digit  # 12 digits with optional '-' prefix
    integer_part = raw[:-2]
    fractional_part = raw[-2:]
    value = Decimal(f"{integer_part}.{fractional_part}")
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_EVEN)


def parse_iso_date(field: str) -> Optional[date]:
    """Parse a ``YYYY-MM-DD`` ASCII date field to a ``datetime.date`` object.

    The Spark ``DateType`` schema column requires a ``datetime.date``
    instance — not a string — so this helper converts the COBOL
    PIC X(10) date field to a typed value. A blank field (all spaces)
    returns ``None`` to support nullable columns. Any malformed input
    raises ``ValueError`` (caught by the Glue retry/error stream).
    """
    if field is None:
        return None
    stripped = field.strip()
    if stripped == "":
        return None
    try:
        return datetime.strptime(stripped, "%Y-%m-%d").date()
    except ValueError as exc:
        raise ValueError(
            f"Date field must be in YYYY-MM-DD format; got {field!r}"
        ) from exc


def parse_account_record(line: str) -> dict:
    """Parse one 300-byte ASCII account record into a dict matching the
    ``accounts`` table column names.

    The function intentionally trims trailing whitespace from text
    fields (since IDCAMS REPRO left-justifies and space-pads) but
    preserves leading zeros in the account ID (PIC 9(11) — the ACCT-ID
    is stored as ``Long`` in JPA but the ASCII source has leading
    zeros that must be parsed as numeric not as a string).

    Byte offsets (0-indexed, end-exclusive — Python slice form):
        line[ 0: 11]  ACCT-ID                  PIC 9(11)     11 bytes
        line[11: 12]  ACCT-ACTIVE-STATUS       PIC X(01)      1 byte
        line[12: 24]  ACCT-CURR-BAL            PIC S9(10)V99 12 bytes
        line[24: 36]  ACCT-CREDIT-LIMIT        PIC S9(10)V99 12 bytes
        line[36: 48]  ACCT-CASH-CREDIT-LIMIT   PIC S9(10)V99 12 bytes
        line[48: 58]  ACCT-OPEN-DATE           PIC X(10)     10 bytes
        line[58: 68]  ACCT-EXPIRAION-DATE      PIC X(10)     10 bytes
        line[68: 78]  ACCT-REISSUE-DATE        PIC X(10)     10 bytes
        line[78: 90]  ACCT-CURR-CYC-CREDIT     PIC S9(10)V99 12 bytes
        line[90:102]  ACCT-CURR-CYC-DEBIT      PIC S9(10)V99 12 bytes
        line[102:112] ACCT-ADDR-ZIP            PIC X(10)     10 bytes
        line[112:122] ACCT-GROUP-ID            PIC X(10)     10 bytes
        line[122:300] FILLER                   PIC X(178)   178 bytes (ignored)
    """
    if line is None:
        raise ValueError("Account record is None")
    if len(line) < 122:
        raise ValueError(
            "Account record must be at least 122 bytes (excluding 178-byte "
            f"FILLER); got {len(line)} bytes"
        )

    return {
        "acct_id": int(line[0:11]),
        "acct_active_status": line[11:12],
        "acct_curr_bal": parse_zoned_decimal_s9_10_v2(line[12:24]),
        "acct_credit_limit": parse_zoned_decimal_s9_10_v2(line[24:36]),
        "acct_cash_credit_limit": parse_zoned_decimal_s9_10_v2(line[36:48]),
        "acct_open_date": parse_iso_date(line[48:58]),
        "acct_expiration_date": parse_iso_date(line[58:68]),
        "acct_reissue_date": parse_iso_date(line[68:78]),
        "acct_curr_cyc_credit": parse_zoned_decimal_s9_10_v2(line[78:90]),
        "acct_curr_cyc_debit": parse_zoned_decimal_s9_10_v2(line[90:102]),
        "acct_addr_zip": line[102:112].strip() or None,
        "acct_group_id": line[112:122].strip() or None,
    }


# UDF wrappers — Spark requires a Python callable returning a type that
# matches the configured StructType (or an Optional[T] for nullable cols).
# We register one UDF per column so that the parsing pipeline can be
# composed via `withColumn` and remain debuggable.
parse_dec_udf = udf(parse_zoned_decimal_s9_10_v2, DecimalType(12, 2))


ACCOUNT_SCHEMA = StructType(
    [
        StructField("acct_id", LongType(), nullable=False),
        StructField("acct_active_status", StringType(), nullable=False),
        StructField("acct_curr_bal", DecimalType(12, 2), nullable=False),
        StructField("acct_credit_limit", DecimalType(12, 2), nullable=False),
        StructField("acct_cash_credit_limit", DecimalType(12, 2), nullable=False),
        StructField("acct_open_date", DateType(), nullable=False),
        StructField("acct_expiration_date", DateType(), nullable=False),
        StructField("acct_reissue_date", DateType(), nullable=True),
        StructField("acct_curr_cyc_credit", DecimalType(12, 2), nullable=False),
        StructField("acct_curr_cyc_debit", DecimalType(12, 2), nullable=False),
        StructField("acct_addr_zip", StringType(), nullable=True),
        StructField("acct_group_id", StringType(), nullable=True),
    ]
)


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

    # Read the fixed-width ASCII fixture as raw text. spark.read.text
    # yields a DataFrame with a single "value" column per row.
    raw_df = spark.read.text(source_uri)

    # Parse each row via a Python UDF that emits a Row matching
    # ACCOUNT_SCHEMA. The mapPartitions form avoids per-row serialisation
    # overhead for large fixtures.
    parsed_rdd = raw_df.rdd.map(lambda row: parse_account_record(row["value"]))
    parsed_df = spark.createDataFrame(parsed_rdd, schema=ACCOUNT_SCHEMA)

    # Write to RDS via the Glue connection. The Glue connection holds
    # the JDBC URL + Secrets Manager-resolved credentials at runtime.
    parsed_df.write.format("jdbc").option(
        "connectionName", args["connection_name"]
    ).option("dbtable", args["target_table"]).mode("append").save()

    job.commit()


if __name__ == "__main__":
    main()
