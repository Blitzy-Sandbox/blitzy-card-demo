# ============================================================================
# Source: COBOL copybook CVTRA03Y.cpy — TRAN-TYPE-RECORD (RECLN 60)
# ============================================================================
# Mainframe-to-Cloud migration: VSAM KSDS → Aurora PostgreSQL
#
# Reference data table — transaction type code lookup (typically 7 rows:
# purchase, payment, credit, debit, refund, adjustment, fee — loaded from
# the ``app/data/ASCII/trantype.txt`` seed file).
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
"""SQLAlchemy 2.x ORM model for the ``transaction_type`` table.

Converts the COBOL copybook ``app/cpy/CVTRA03Y.cpy`` (record layout
``TRAN-TYPE-RECORD``, 60-byte fixed-width record) to a SQLAlchemy 2.x
declarative ORM model representing a transaction-type reference-data
row in the CardDemo Aurora PostgreSQL database.

COBOL to Python Field Mapping
-----------------------------
=================  ==============  =================  =========================
COBOL Field        COBOL Type      Python Column      SQLAlchemy Type
=================  ==============  =================  =========================
TRAN-TYPE          ``PIC X(02)``   ``tran_type``      ``String(2)`` — PK
TRAN-TYPE-DESC     ``PIC X(50)``   ``description``    ``String(50)``
FILLER             ``PIC X(08)``   — (not mapped)     — (COBOL padding only)
=================  ==============  =================  =========================

Total RECLN: 2 + 50 + 8 = 60 bytes — matches the VSAM cluster definition
in ``app/jcl/TRANTYPE.jcl`` (``RECSZ(60 60)``).

Transaction Type Semantics
--------------------------
The ``tran_type`` column is a 2-character fixed-width reference code
used throughout the batch and online pipelines. Typical values (see
``app/data/ASCII/trantype.txt`` seed fixture, 7 rows):

* ``'01'`` — Purchase
* ``'02'`` — Payment
* ``'03'`` — Credit
* ``'04'`` — Debit
* ``'05'`` — Refund
* ``'06'`` — Adjustment
* ``'07'`` — Fee

Consumer References
-------------------
The ``tran_type`` code is a foreign-key-equivalent in the following
entities (composite-key participation documented in their respective
modules — relationships are not declared here to keep this reference
table free of back-references):

* ``Transaction`` (``CVTRA05Y.cpy``) — ``TRAN-TYPE-CD`` (PIC X(02))
* ``DailyTransaction`` (``CVTRA06Y.cpy``) — ``DALYTRAN-TYPE-CD``
* ``TransactionCategory`` (``CVTRA04Y.cpy``) — composite PK
  (``tran_type`` + ``tran_cat_cd``)
* ``TransactionCategoryBalance`` (``CVTRA01Y.cpy``) — composite PK
  (``acct_id`` + ``tran_type`` + ``tran_cat_cd``)
* ``DisclosureGroup`` (``CVTRA02Y.cpy``) — composite PK
  (``disc_grp_cd`` + ``tran_type`` + ``tran_cat_cd``)

Design Notes
------------
* Uses SQLAlchemy 2.x :func:`~sqlalchemy.orm.mapped_column` style with
  typed :class:`~sqlalchemy.orm.Mapped` annotations (NOT the legacy
  ``Column()`` constructor).
* ``Base`` is imported from the package ``__init__.py`` (``src.shared.models``)
  so that this entity registers with the shared
  :class:`~sqlalchemy.MetaData` alongside the other CardDemo models.
* No ``FILLER`` column is mapped — the trailing 8 bytes of COBOL padding
  have no relational counterpart. In PostgreSQL, column widths are
  explicit and trailing padding carries no storage or semantic meaning.
* No monetary fields — the transaction-type record has no
  ``decimal.Decimal`` columns.
* Python 3.11+ only (aligned with AWS Glue 5.1 runtime and FastAPI
  deployment baseline).

See Also
--------
AAP §0.2.2 — Batch Program Classification (transaction type usage).
AAP §0.5.1 — File-by-File Transformation Plan (``transaction_type.py`` entry).
AAP §0.5.1 — DB migrations: ``db/migrations/V1__schema.sql``,
``db/migrations/V3__seed_data.sql`` (seed rows from ``trantype.txt``).
``app/cpy/CVTRA03Y.cpy`` — Original COBOL record layout (source artifact).
``app/jcl/TRANTYPE.jcl`` — Original VSAM cluster definition.
``app/data/ASCII/trantype.txt`` — Original seed-data fixture (7 rows).
"""

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column

from src.shared.models import Base


class TransactionType(Base):
    """ORM entity for the ``transaction_type`` table (from COBOL ``TRAN-TYPE-RECORD``).

    Represents a single row in the CardDemo Aurora PostgreSQL
    ``transaction_type`` reference table, which replaces the mainframe
    VSAM KSDS ``TRANTYPE`` dataset. Each row defines a 2-character
    transaction-type code and its human-readable description, used by
    batch posting (``CBTRN02C`` → ``posttran_job.py``), interest
    calculation (``CBACT04C`` → ``intcalc_job.py``), and the online
    transaction-add flow (``COTRN02C`` → ``transaction_service.py``).

    Attributes
    ----------
    tran_type : str
        **Primary key.** 2-character transaction-type code (from COBOL
        ``TRAN-TYPE``, ``PIC X(02)``). Preserved at the original COBOL
        width so that existing transaction records migrated from VSAM
        remain addressable with their historical codes. Referenced by
        ``Transaction.tran_type``, ``DailyTransaction.tran_type``,
        ``TransactionCategory.tran_type``,
        ``TransactionCategoryBalance.tran_type``, and
        ``DisclosureGroup.tran_type``.
    description : str
        Up to 50-character type description (from COBOL
        ``TRAN-TYPE-DESC``, ``PIC X(50)``). Human-readable label
        displayed in transaction list/detail screens
        (``COTRN00``/``COTRN01``) and in generated statements
        (``CREASTMT``) and transaction reports (``TRANREPT``).
    """

    __tablename__ = "transaction_types"

    # ------------------------------------------------------------------
    # Primary key: 2-character transaction-type code
    # (COBOL ``TRAN-TYPE`` PIC X(02))
    # ------------------------------------------------------------------
    tran_type: Mapped[str] = mapped_column(
        String(2),
        primary_key=True,
    )

    # ------------------------------------------------------------------
    # Type description (COBOL ``TRAN-TYPE-DESC`` PIC X(50))
    # ------------------------------------------------------------------
    description: Mapped[str] = mapped_column(
        String(50),
        nullable=False,
    )

    # Note: COBOL ``FILLER PIC X(08)`` — the trailing 8 bytes of padding
    # in the original 60-byte VSAM record — is deliberately NOT mapped.
    # In the relational model, column widths are explicit and trailing
    # padding has no storage or semantic meaning.

    def __repr__(self) -> str:  # pragma: no cover - trivial formatting
        """Return a developer-friendly string representation.

        Returns
        -------
        str
            Representation of the form
            ``TransactionType(tran_type='01', description='Purchase')``.
        """
        return f"TransactionType(tran_type={self.tran_type!r}, description={self.description!r})"
