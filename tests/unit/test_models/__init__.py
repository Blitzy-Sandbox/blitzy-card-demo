# ============================================================================
# CardDemo — Model Unit Test Package Init (Mainframe-to-Cloud migration)
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
"""Unit tests for CardDemo SQLAlchemy ORM models.

Tests cover all 11 entity models derived from COBOL copybooks
(``app/cpy/*.cpy``): Account, Card, Customer, CardCrossReference,
Transaction, TransactionCategoryBalance, DailyTransaction,
DisclosureGroup, TransactionType, TransactionCategory, and
UserSecurity.

Key validation areas:

* Field types and constraints (COBOL PIC -> SQLAlchemy types)
* Primary key configurations (single and composite keys)
* Monetary fields use ``Numeric(15, 2)`` — never ``Float`` (COBOL
  ``PIC S9(n)V99``)
* Optimistic concurrency (``version_id`` columns on Account and Card)
* FILLER field exclusion from all models
* ``NOT NULL`` constraints
* Index configurations matching VSAM AIX patterns

Source: Mainframe-to-Cloud migration — COBOL/VSAM -> Python/SQLAlchemy/
Aurora PostgreSQL.

This package is the marker for the **model** subtree of the unit-test
pyramid. Like its parent packages (``tests`` and ``tests.unit``), it is
intentionally minimal:

* **No imports of test modules** — pytest auto-discovers ``test_*.py``
  files via ``[tool.pytest.ini_options]`` in ``pyproject.toml``
  (``testpaths = ["tests"]``, ``python_files = ["test_*.py"]``).
* **No shared fixtures or helpers** — fixtures live in
  ``tests/conftest.py`` (session-wide) and subpackage-local
  ``conftest.py`` files so that pytest's hierarchical fixture resolution
  applies and fixtures stay close to the tests that use them.
* **No executable side effects** — importing
  ``tests.unit.test_models`` must be a no-op so that tooling
  (coverage reporters, IDE test runners, CI linters, mypy) can safely
  introspect the package without triggering database connections,
  AWS client construction, or Spark context creation.

Model-to-Copybook Mapping
-------------------------
Per AAP §0.5.1 (File-by-File Transformation Plan), the 11 SQLAlchemy ORM
models under ``src/shared/models/`` map one-to-one to COBOL record
layouts defined in ``app/cpy/*.cpy``:

========================================  ==============================  ====================================
Model (``src/shared/models/``)            Copybook (``app/cpy/``)         VSAM Cluster / JCL
========================================  ==============================  ====================================
``account.Account``                       ``CVACT01Y.cpy``                ACCTFILE  (``app/jcl/ACCTFILE.jcl``)
``card.Card``                             ``CVACT02Y.cpy``                CARDFILE  (``app/jcl/CARDFILE.jcl``)
``customer.Customer``                     ``CVCUS01Y.cpy``                CUSTFILE  (``app/jcl/CUSTFILE.jcl``)
``card_cross_reference.CardCross`` …      ``CVACT03Y.cpy``                XREFFILE  (``app/jcl/XREFFILE.jcl``)
``transaction.Transaction``               ``CVTRA05Y.cpy``                TRANFILE  (``app/jcl/TRANFILE.jcl``)
``transaction_category_balance.`` …       ``CVTRA01Y.cpy``                TCATBALF  (``app/jcl/TCATBALF.jcl``)
``daily_transaction.DailyTransaction``    ``CVTRA06Y.cpy``                DAILYTRAN (``app/data/ASCII/``)
``disclosure_group.DisclosureGroup``      ``CVTRA02Y.cpy``                DISCGRP   (``app/jcl/DISCGRP.jcl``)
``transaction_type.TransactionType``      ``CVTRA03Y.cpy``                TRANTYPE  (``app/jcl/TRANTYPE.jcl``)
``transaction_category.`` …               ``CVTRA04Y.cpy``                TRANCATG  (``app/jcl/TRANCATG.jcl``)
``user_security.UserSecurity``            ``CSUSR01Y.cpy``                USRSEC    (``app/jcl/DUSRSECJ.jcl``)
========================================  ==============================  ====================================

Per AAP §0.4.4 these 11 tables map the 10 VSAM KSDS clusters plus 3 AIX
paths (``CARDAIX``, ``CXRFAIX``, ``TRNXAIX``) that become B-tree indexes
on the relational schema (see ``db/migrations/V2__indexes.sql``).

Test Module Scope
-----------------
Each model is expected to have a corresponding ``test_<model>.py``
module in this package exercising:

* **Instantiation** — the model can be instantiated with valid
  positional and keyword arguments that mirror the COBOL field order.
* **Primary keys** — single-column PKs
  (Account, Card, Customer, CardCrossReference, UserSecurity) and
  composite PKs (TransactionCategoryBalance 3-part,
  DisclosureGroup 3-part, TransactionCategory 2-part) round-trip
  through a SQLAlchemy session without ``IntegrityError``.
* **Financial precision** — all monetary columns declared as
  ``Numeric(15, 2)`` accept ``decimal.Decimal`` values, reject ``float``
  for arithmetic, and persist/retrieve with exactly 2 decimal places
  (banker's rounding ``ROUND_HALF_EVEN`` preserves COBOL ROUNDED
  semantics). This applies to Account (``curr_bal``,
  ``credit_limit``, ``cash_credit_limit``, ``curr_cyc_credit``,
  ``curr_cyc_debit``), Transaction (``tran_amt``),
  TransactionCategoryBalance (``tran_cat_bal``), DailyTransaction
  (``dalytran_amt``), and DisclosureGroup (``dis_int_rate``).
* **Optimistic concurrency** — Account and Card declare SQLAlchemy
  ``__mapper_args__ = {"version_id_col": version_id}`` so concurrent
  updates raise :class:`sqlalchemy.orm.exc.StaleDataError`, preserving
  the CICS ``READ UPDATE / REWRITE`` semantics from F-005 (Account
  Update) and F-008 (Card Update).
* **FILLER exclusion** — no Python attribute corresponds to any COBOL
  ``FILLER`` region (e.g., ``PIC X(178)`` trailing pad on
  ACCOUNT-RECORD, ``PIC X(59)`` trailing pad on CARD-RECORD). Tests
  assert that ``__table__.columns`` does not include any attribute
  named with the ``filler`` substring.
* **``NOT NULL`` constraints** — every non-optional COBOL field
  translates to a non-nullable SQLAlchemy ``Column`` (``nullable=False``);
  tests assert that instantiating a model missing a required field
  and flushing to the session raises :class:`sqlalchemy.exc.IntegrityError`.
* **Index configurations** — explicit secondary indexes exist for the
  3 VSAM AIX paths:

  * ``card.acct_id``                     (replaces ``CARDAIX``)
  * ``card_cross_reference.acct_id``     (replaces ``CXRFAIX``)
  * ``transaction.tran_proc_ts``         (replaces ``TRNXAIX``)

  Tests assert ``__table_args__`` contains ``Index(...)`` entries
  matching these paths.

Markers
-------
All tests in this package should be decorated with ``@pytest.mark.unit``
(registered in ``pyproject.toml`` under
``[tool.pytest.ini_options] markers``) so that selective execution is
possible:

* Run only model unit tests:     ``pytest tests/unit/test_models -m unit``
* Run all unit tests:            ``pytest -m unit``

Feature Coverage
----------------
Model tests in this package provide foundational coverage for every
migrated feature (F-001 through F-022) because every online CICS
program and every batch COBOL program interacts with at least one of
the 11 persistent entities. In particular:

* F-001 Sign-on / authentication       -> ``UserSecurity``
* F-004 Account view                   -> ``Account``, ``Customer``, ``CardCrossReference``
* F-005 Account update                 -> ``Account``, ``Customer`` (dual-write, ``version_id``)
* F-006 Card list / F-007 detail       -> ``Card``, ``CardCrossReference``
* F-008 Card update                    -> ``Card`` (``version_id``)
* F-009 Txn list / F-010 detail        -> ``Transaction``
* F-011 Transaction add                -> ``Transaction``, ``CardCrossReference``
* F-012 Bill payment                   -> ``Transaction``, ``Account`` (dual-write)
* F-013 POSTTRAN                       -> ``DailyTransaction``, ``Transaction``, ``Account``,
                                          ``CardCrossReference``, ``TransactionCategoryBalance``
* F-014 INTCALC                        -> ``Account``, ``TransactionCategoryBalance``,
                                          ``DisclosureGroup``, ``TransactionType``,
                                          ``TransactionCategory``
* F-015 COMBTRAN                       -> ``Transaction`` (merge/sort)
* F-016 CREASTMT                       -> ``Account``, ``Customer``, ``Transaction``,
                                          ``CardCrossReference``
* F-017 TRANREPT                       -> ``Transaction``, ``Account``, ``CardCrossReference``
* F-018 .. F-021 User CRUD             -> ``UserSecurity``

See Also
--------
``tests.__init__``          — Parent test-suite package marker and full
                              feature catalog (F-001 through F-022).
``tests.unit.__init__``     — Parent unit-test package marker describing
                              the full unit-test subpackage layout.
``tests.conftest``          — Session-wide pytest fixtures (DB session,
                              FastAPI TestClient, Spark session, mocked
                              AWS services).
``src.shared.models``       — The 11 SQLAlchemy ORM models under test.
``db.migrations.V1__schema``— Relational DDL for the 11 tables.
``db.migrations.V2__indexes``— B-tree indexes replacing VSAM AIX paths.
AAP §0.4.1                  — Refactored Structure Planning
                              (``tests/unit/test_models/`` subtree).
AAP §0.4.4                  — "11 tables mapping the 10 VSAM clusters
                              + 3 AIX".
AAP §0.5.1                  — File-by-File Transformation Plan.
AAP §0.7.1                  — Financial precision requirements (COBOL
                              ``PIC S9(n)V99`` -> ``decimal.Decimal``).
AAP §0.7.2                  — Testing Requirements (pytest as primary
                              test framework, parity with 81.5%
                              originating coverage).
"""
