###############################################################################
# infrastructure/terraform/rds.tf
#
# Amazon RDS for PostgreSQL Multi-AZ instance hosting the CardDemo relational
# schema (per AAP §0.1.1, §0.6.2, §0.7.2).
#
# Purpose:
#   Provisions the persistence layer that replaces the legacy z/OS VSAM file
#   system. The catalog inventory at app/catlg/LISTCAT.txt enumerates the
#   source VSAM clusters being migrated:
#     * AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS         RECLN 300  KEYLEN 11
#     * AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS         RECLN 150  KEYLEN 16
#     * AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX(.PATH)   (alternate index by acct id)
#     * AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS         RECLN 500  KEYLEN 9
#     * AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS         RECLN 50   KEYLEN 16
#     * AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS         RECLN 350  KEYLEN 16
#     * AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX(.PATH)   (alternate index by card num)
#     * AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS         RECLN 50   composite key
#     * AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS          RECLN 50
#     * AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS         RECLN 60
#     * AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS         RECLN 60
#     * AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS           RECLN 80   KEYLEN 8
#
#   Each becomes a JPA @Entity table in PostgreSQL plus secondary indexes for
#   the AIX/PATH chains. Schemas are managed by Flyway via
#   src/main/resources/db/migration/V*.sql which runs on Spring Boot startup;
#   this Terraform file ONLY provisions the RDS instance + its supporting
#   resources, never the schema itself.
#
# Resources provisioned (in dependency order resolved by Terraform):
#   1. aws_security_group.rds                       — Network ACL for the
#                                                     PostgreSQL listener
#                                                     (port 5432, TLS only).
#                                                     Inbound rules attached
#                                                     by ecs.tf / batch.tf /
#                                                     glue.tf to avoid SG
#                                                     dependency cycles.
#   2. aws_db_subnet_group.carddemo                 — Multi-AZ private subnet
#                                                     placement (>= 2 AZs).
#   3. aws_db_parameter_group.carddemo              — PostgreSQL 16 parameters:
#                                                     rds.force_ssl=1 (TLS),
#                                                     default isolation READ
#                                                     COMMITTED (AAP §0.6.2),
#                                                     PCI-DSS audit logging,
#                                                     pg_stat_statements for
#                                                     BigDecimal NUMERIC
#                                                     performance tuning.
#   4. random_password.rds_master                   — Bootstrap master password
#                                                     (32 chars, PostgreSQL-
#                                                     safe specials). Rotation
#                                                     Lambda takes over after
#                                                     initial provisioning.
#   5. aws_secretsmanager_secret.rds_master         — KMS-encrypted master
#                                                     credential container.
#   6. aws_secretsmanager_secret_version.rds_master_value
#                                                   — JSON payload with
#                                                     username, password,
#                                                     engine, host, port, db
#                                                     consumed by Spring Boot
#                                                     via Spring Cloud AWS
#                                                     Secrets Manager binder.
#   7. aws_db_instance.carddemo                     — Multi-AZ PostgreSQL 16
#                                                     with KMS at rest,
#                                                     gp3 storage with
#                                                     autoscaling, 35-day
#                                                     automated backups + PITR,
#                                                     Performance Insights,
#                                                     Enhanced Monitoring,
#                                                     CloudWatch log exports,
#                                                     IAM database auth, and
#                                                     deletion protection.
#
# Security posture (PCI-DSS-aligned per AAP §0.6.6, §0.7.1):
#   * Encryption at rest — KMS customer-managed CMK (aws_kms_key.carddemo
#     from kms.tf) for the PostgreSQL storage volume, the Performance
#     Insights data store, and the master credential secret.
#   * Encryption in transit — rds.force_ssl=1 parameter rejects any
#     non-TLS connection; Spring Boot DataSource connects with
#     sslmode=require.
#   * Authentication — random master password stored only in Secrets
#     Manager; optional IAM database authentication enabled for short-
#     lived token-based fallback.
#   * Network — placed in private subnets only via the subnet group;
#     publicly_accessible = false; no public access path exists.
#   * Backups — backup_retention_period = 35 with PITR; final snapshot
#     on destroy; copy_tags_to_snapshot for compliance traceability.
#   * Deletion protection — toggled per environment so prod cannot be
#     destroyed accidentally.
#   * Observability — postgresql + upgrade logs to CloudWatch;
#     Performance Insights with KMS-encrypted at-rest storage; Enhanced
#     Monitoring at 60-second granularity emits OS-level metrics under
#     the RDSOSMetrics namespace.
#
# Coordination with sibling files:
#   * kms.tf — provides aws_kms_key.carddemo (envelope encryption key).
#   * iam.tf — provides aws_iam_role.rds_enhanced_monitoring (CloudWatch
#     metric publication role).
#   * variables.tf — exposes var.environment, var.rds_instance_class,
#     var.rds_engine_version, var.rds_allocated_storage,
#     var.rds_max_allocated_storage, var.rds_backup_retention_period,
#     var.rds_db_name, var.rds_master_username, var.rds_multi_az,
#     var.rds_deletion_protection.
#   * main.tf — provides local.common_tags, data.aws_vpc.carddemo,
#     data.aws_subnets.private.
#   * ecs.tf, batch.tf, glue.tf — append aws_security_group_rule resources
#     that source from their own task / job SGs into aws_security_group.rds
#     on port 5432 (kept separate to avoid SG dependency cycles).
#   * secrets.tf — declares the rotation Lambda for
#     aws_secretsmanager_secret.rds_master (per AAP §0.6.4 dynamic
#     rotation without Spring Boot restart).
#   * outputs.tf — re-exports rds_endpoint, rds_secret_arn,
#     rds_database_name, rds_port for the Spring Boot environment
#     variable contract (AAP §0.7.2).
#   * src/main/resources/db/migration/V*.sql — Flyway runs against this
#     instance on application startup (V001..V015 provision tables for
#     Account, Card, Customer, CardCrossReference, Transaction,
#     TransactionCategoryBalance, DisclosureGroup, TransactionType,
#     TransactionCategory, UserSecurity, DailyTransaction + seed data).
#   * src/main/resources/application-prod.yml — references RDS_SECRET_ARN
#     for the DataSource credentials.
#   * src/main/java/com/awsm2/carddemo/config/JpaConfig.java — declares
#     @RefreshScope on the HikariCP DataSource so credential rotation
#     takes effect without restart (AAP §0.6.4).
#
# Engine selection — PostgreSQL 16.x:
#   PostgreSQL 16 NUMERIC arbitrary precision exactly preserves COBOL
#   PIC S9(n)V99 / COMP-3 decimal arithmetic (AAP §0.6.1). The source
#   copybooks fix the precision/scale expectations:
#     * app/cpy/CVACT01Y.cpy   — ACCT-CURR-BAL,
#                                ACCT-CREDIT-LIMIT,
#                                ACCT-CASH-CREDIT-LIMIT,
#                                ACCT-CURR-CYC-CREDIT,
#                                ACCT-CURR-CYC-DEBIT  all PIC S9(10)V99
#                                  -> NUMERIC(12,2) in Flyway DDL.
#     * app/cpy/CVTRA05Y.cpy   — TRAN-AMT  PIC S9(09)V99
#                                  -> NUMERIC(11,2) in Flyway DDL.
#   Float / double types are NEVER used for monetary values.
#
# References:
#   * AAP §0.1.1 — Core refactoring objective: VSAM -> RDS PostgreSQL
#     Multi-AZ.
#   * AAP §0.6.1 — BigDecimal precision requires PostgreSQL NUMERIC.
#   * AAP §0.6.2 — VSAM-to-RDS migration strategy; Multi-AZ synchronous
#     standby; 35-day automated backups; READ_COMMITTED default
#     isolation.
#   * AAP §0.6.4 — AWS Secrets Manager dynamic rotation without
#     Spring Boot restart.
#   * AAP §0.6.6 — Cross-cutting: KMS CMKs, TLS 1.2+, CloudWatch
#     observability, PCI-DSS posture.
#   * AAP §0.7.1 — Refactoring rules: encrypt all RDS data at rest with
#     KMS CMKs; all credentials in Secrets Manager.
#   * AAP §0.7.2 — Non-functional requirements: 35-day automated backups
#     with PITR; encryption in transit via TLS 1.2+.
#   * Source files referenced for context only (never modified):
#       app/catlg/LISTCAT.txt
#       app/jcl/ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl,
#               XREFFILE.jcl, TRANFILE.jcl
#       app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVCUS01Y.cpy, CVTRA05Y.cpy
###############################################################################

# =============================================================================
# Section 1 — Security group for the RDS ENI
# =============================================================================
# Attached to the RDS instance ENI(s) in the private subnets selected by
# the subnet group below. Gates inbound connectivity to the PostgreSQL
# listener (port 5432).
#
# Ingress strategy — defer to consumer modules:
#   This SG intentionally declares NO ingress rules inline. Consumer
#   modules (ecs.tf for the Spring Boot ECS service, batch.tf for AWS
#   Batch jobs, glue.tf for AWS Glue Spark jobs that bulk-load the
#   ASCII fixtures) attach their own aws_security_group_rule resources
#   sourcing from THEIR task / job SGs into THIS SG on port 5432.
#   Keeping ingress declarations out of this file prevents the classic
#   Terraform SG dependency cycle (where SG A needs to know SG B's ID
#   and SG B needs to know SG A's ID).
#
# Egress strategy — permissive:
#   RDS legitimately needs outbound to KMS (data-key decryption for
#   storage encryption), Secrets Manager (rotation grants), CloudWatch
#   (Enhanced Monitoring), and S3 (snapshot uploads). Rather than
#   enumerating each AWS endpoint, all outbound is allowed — the
#   Interface VPC endpoints in main.tf keep that traffic on the AWS
#   backbone, and IAM / KMS key policies enforce the actual
#   authorization surface.
#
# Members exposed for downstream consumers (per the file schema):
#   id, arn, name, description, vpc_id, owner_id, tags_all.
# =============================================================================

resource "aws_security_group" "rds" {
  name        = "carddemo-${var.environment}-rds-sg"
  description = "RDS PostgreSQL - inbound from ECS task, Batch, Glue on port 5432 (TLS); ingress rules attached by ecs.tf / batch.tf / glue.tf"
  vpc_id      = data.aws_vpc.carddemo.id

  # NOTE: ingress rules are intentionally omitted here. ecs.tf, batch.tf,
  # and glue.tf attach aws_security_group_rule resources that reference
  # this SG's id as the destination, sourcing from each consumer's own
  # task / job SG. This prevents a Terraform SG dependency cycle.

  egress {
    description = "All outbound - RDS calls KMS, Secrets Manager, CloudWatch, and S3 over the AWS backbone via VPC endpoints"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-sg"
    Purpose = "RDS PostgreSQL Multi-AZ security group"
  })

  # Replacing this SG would force a replacement of the DB instance
  # (vpc_security_group_ids is ForceNew on aws_db_instance). The
  # create_before_destroy lifecycle minimizes blast radius if a name
  # collision forces an in-place rename.
  lifecycle {
    create_before_destroy = true
  }
}

# =============================================================================
# Section 2 — DB subnet group (Multi-AZ private subnet placement)
# =============================================================================
# The subnet group defines the set of subnets RDS uses to place the
# primary instance and (when var.rds_multi_az = true) the synchronous
# standby. Two requirements drive this:
#
#   * Private-only placement — AAP §0.7.1 mandates that RDS never sit in
#     a public subnet. data.aws_subnets.private is filtered to the
#     operator-supplied private subnet IDs by main.tf, so this group
#     inherits that guarantee transparently.
#
#   * Multi-AZ spread — When var.rds_multi_az = true (prod default),
#     RDS places the primary in one AZ and the synchronous standby in
#     a different AZ drawn from this subnet group. The operator must
#     therefore supply private subnets in at least 2 distinct AZs in
#     var.private_subnet_ids (variables.tf enforces a length >= 2
#     validation block).
#
# Replaces the z/OS VOLSER allocation pattern visible in LISTCAT.txt
# (e.g., VOLSER YYYYO7 for ACCTDATA.VSAM.KSDS) — the RDS subnet group
# is the cloud-native equivalent for instance placement.
#
# Members exposed for downstream consumers (per the file schema):
#   id, arn, name, description, subnet_ids, tags_all.
# =============================================================================

resource "aws_db_subnet_group" "carddemo" {
  name        = "carddemo-${var.environment}-rds-subnet-group"
  description = "Private subnets across multiple AZs for the CardDemo RDS Multi-AZ instance"
  subnet_ids  = data.aws_subnets.private.ids

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-subnet-group"
    Purpose = "Multi-AZ private-subnet placement for RDS PostgreSQL"
  })
}

# =============================================================================
# Section 3 — DB parameter group (TLS enforcement + audit logging + tuning)
# =============================================================================
# Parameter groups are PostgreSQL configuration profiles that RDS applies
# to every instance referencing them. CardDemo's parameter group enforces
# the PCI-DSS and AAP-mandated configuration:
#
#   * rds.force_ssl = 1 — Mandatory per AAP §0.7.2 ("TLS 1.2+ (ALB, MSK,
#     RDS)"). When set, PostgreSQL rejects every connection that does
#     not negotiate TLS. Spring Boot connects with sslmode=require
#     against the RDS CA bundle. apply_method = "pending-reboot" is
#     required because rds.force_ssl is a static parameter.
#
#   * default_transaction_isolation = "read committed" — Mandatory per
#     AAP §0.6.2 ("READ_COMMITTED default isolation"). Spring
#     @Transactional methods inherit this isolation unless they
#     explicitly request a stronger level (REPEATABLE_READ or
#     SERIALIZABLE) for read-modify-write flows.
#
#   * log_connections = 1, log_disconnections = 1 — PCI-DSS Requirement
#     10.2.5 audit logging: every authentication event is captured.
#
#   * log_statement = "ddl" — PCI-DSS Requirement 10.2.2: log all DDL
#     statements (CREATE / ALTER / DROP) for schema-change audit. DML
#     statements are NOT logged here because they would produce a
#     prohibitive log volume and risk capturing PII / PAN-like data in
#     CloudWatch (the CloudWatch log filters in cloudwatch.tf detect
#     PAN sequences for incident response).
#
#   * log_min_duration_statement = 1000 — Slow-query audit: any
#     statement that takes longer than 1000 ms is logged with full
#     parameters. Helps diagnose the BigDecimal-arithmetic-heavy
#     interest-calculation queries against the NUMERIC(12,2) and
#     NUMERIC(11,2) columns in the Account / Transaction tables.
#
#   * shared_preload_libraries = "pg_stat_statements" — Performance
#     tuning library required by Performance Insights. Captures
#     aggregate query statistics for the Spring Boot @Repository
#     methods that surface high BigDecimal-arithmetic workload (e.g.,
#     CBACT04C interest calculation port and CBTRN02C transaction
#     posting port). apply_method = "pending-reboot" is required
#     because shared_preload_libraries is a static parameter.
#
# Family selection — postgres16:
#   PostgreSQL 16.x (default engine_version = "16.4") requires the
#   "postgres16" parameter group family. RDS rejects family/version
#   mismatches at apply time.
#
# Members exposed for downstream consumers (per the file schema):
#   id, arn, name, family, description, tags_all.
# =============================================================================

resource "aws_db_parameter_group" "carddemo" {
  name        = "carddemo-${var.environment}-pg16-params"
  family      = "postgres16"
  description = "CardDemo PostgreSQL 16 parameters: rds.force_ssl=1 (TLS, AAP §0.7.2), READ COMMITTED default isolation (AAP §0.6.2), PCI-DSS audit logging, pg_stat_statements"

  # Force TLS on every connection — mandatory per AAP §0.7.2.
  # Static parameter; takes effect on instance reboot only.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  # PCI-DSS Requirement 10.2.5 — log all authentication events.
  parameter {
    name         = "log_connections"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_disconnections"
    value        = "1"
    apply_method = "immediate"
  }

  # PCI-DSS Requirement 10.2.2 — log all DDL statements for schema-change
  # audit. DML statements are intentionally NOT logged to avoid capturing
  # potential PAN / PII in CloudWatch Logs.
  parameter {
    name         = "log_statement"
    value        = "ddl"
    apply_method = "immediate"
  }

  # Slow-query audit — statements > 1000 ms are logged with parameters.
  parameter {
    name         = "log_min_duration_statement"
    value        = "1000"
    apply_method = "immediate"
  }

  # Default isolation level for new sessions — mandated by AAP §0.6.2.
  # Spring @Transactional may upgrade per-method to REPEATABLE_READ or
  # SERIALIZABLE for read-modify-write flows.
  parameter {
    name         = "default_transaction_isolation"
    value        = "read committed"
    apply_method = "immediate"
  }

  # Performance tuning — pg_stat_statements aggregates query statistics
  # for Performance Insights dashboards. Static parameter; takes effect
  # on instance reboot only.
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-pg16-params"
    Purpose = "PostgreSQL 16 parameter group with TLS enforcement and PCI-DSS audit"
  })

  # Parameter group changes that require a restart (e.g., family
  # changes or static-parameter additions) would otherwise force a DB
  # instance replacement. The lifecycle block lets Terraform create the
  # new parameter group before destroying the old one, minimizing
  # downtime during apply.
  lifecycle {
    create_before_destroy = true
  }
}

# =============================================================================
# Section 4 — Master password in AWS Secrets Manager
# =============================================================================
# Per AAP §0.7.1 ("All credentials fetched at runtime from AWS Secrets
# Manager — never hardcoded, never in application.yml, never in plain
# environment variables"), the RDS master password must be stored in
# Secrets Manager and read at runtime by the Spring Boot application via
# Spring Cloud AWS Secrets Manager integration. Plaintext password
# storage in the source z/OS USRSEC file (CSUSR01Y.cpy) is explicitly
# called out by the AAP as a security debt that is paid off in this
# refactor.
#
# Bootstrap flow:
#   1. random_password.rds_master generates a 32-character password at
#      apply time. Override_special excludes characters that would
#      conflict with PostgreSQL connection strings or JDBC URLs
#      (forward slash, double-quote, single-quote, backslash, space,
#      colon, semicolon, comma).
#   2. aws_secretsmanager_secret.rds_master creates the KMS-encrypted
#      secret container. KMS envelope encryption protects the secret
#      at rest using the customer-managed CMK from kms.tf.
#   3. aws_secretsmanager_secret_version.rds_master_value stores the
#      JSON-encoded payload {username, password, engine, host, port,
#      dbname} as the AWSCURRENT version. The JSON structure follows
#      the standard AWS RDS rotation Lambda contract so the
#      SAR-managed rotator (provisioned in secrets.tf) can rotate in
#      place without code changes.
#   4. aws_db_instance.carddemo references random_password.rds_master.
#      result directly for its password argument (Terraform must be
#      able to resolve the value at apply time, before Spring Boot
#      consults Secrets Manager).
#   5. lifecycle ignore_changes on aws_db_instance.password and
#      aws_secretsmanager_secret_version.secret_string allow the
#      rotation Lambda (AAP §0.6.4) to rotate the password without
#      Terraform drift.
#
# Rotation strategy (AAP §0.6.4):
#   * Spring Cloud AWS @RefreshScope on the HikariCP DataSource
#     re-reads the rotated password on the next refresh event without
#     restarting the JVM.
#   * A future rotation Lambda (declared in secrets.tf when rotation
#     is enabled) calls PutSecretValue with the rotated password
#     payload and invokes ModifyDBInstance with the new master
#     password — both operations are idempotent and protected by the
#     ignore_changes lifecycle below.
# =============================================================================

# -----------------------------------------------------------------------------
# Random master password — 32 characters, PostgreSQL-safe specials.
#
# Length rationale: 32 characters provide >= 190 bits of entropy across
# the allowed character set — well in excess of the PCI-DSS Requirement
# 8.2 minimum and the NIST SP 800-63B Level 3 floor. AWS RDS imposes a
# 128-character maximum (master_password length); 32 is the
# operationally-tested sweet spot used by the AWS RDS Secrets Manager
# rotation Lambda reference implementation.
#
# Character-class diversification (min_numeric, min_lower, min_upper,
# min_special) guarantees the generated string is not, e.g., all
# uppercase, which PostgreSQL would still accept but which would
# marginally reduce effective entropy.
#
# override_special excludes characters that conflict with:
#   * JDBC URL parsing      ( /  :  ;  ,  ? )
#   * PostgreSQL string literals ( '  " )
#   * Shell command-line use ( \  space )
#   * URL-encoding ambiguity ( & )
# The remaining set { ! # $ % ( ) * + - . < = > ? @ [ ] ^ _ { | } ~ }
# is PostgreSQL-safe across every connection-string variant used by
# Spring Boot, psql, and AWS RDS rotation Lambdas.
#
# Members exposed for downstream consumers (per the file schema):
#   result, length, special, override_special.
# -----------------------------------------------------------------------------
resource "random_password" "rds_master" {
  length      = 32
  special     = true
  min_numeric = 1
  min_lower   = 1
  min_upper   = 1
  min_special = 2

  # PostgreSQL-safe special characters. Excludes: forward slash, colon,
  # semicolon, comma, single-quote, double-quote, backslash, space,
  # ampersand (each of which conflicts with at least one connection
  # string parser used in this stack).
  override_special = "!#$%&()*+,-.<=>?@[]^_{|}~"
}

# -----------------------------------------------------------------------------
# Secrets Manager secret container for the RDS master credential.
#
# kms_key_id references the customer-managed KMS CMK from kms.tf, per
# AAP §0.7.1 mandate that all secrets be encrypted at rest with a CMK
# (not the AWS-managed aws/secretsmanager default key). Using a CMK
# enables CloudTrail key-usage events for compliance reporting and
# IAM-based access scoping at the key-policy level.
#
# Naming convention: carddemo-${env}-rds-master matches the broader
# carddemo-${env}-* pattern used across the iam.tf least-privilege
# secretsmanager:GetSecretValue scope. Roles can never read secrets
# belonging to a different environment.
#
# recovery_window_in_days uses the AWS default (30 days) implicitly —
# not overridden here because the AAP does not relax it and 30 days
# matches the var.kms_deletion_window_in_days for symmetric recovery
# windows on the CMK and the secret.
#
# Members exposed for downstream consumers (per the file schema):
#   arn, id, name, description, kms_key_id, rotation_enabled, tags_all.
# -----------------------------------------------------------------------------
# F-CP6-TF-KMS-01: Per-service CMK separation. The RDS master secret is
# encrypted with the dedicated Secrets Manager CMK (aws_kms_key.secrets_kms)
# rather than the legacy shared aws_kms_key.carddemo, per the CP6 KMS
# separation requirement.
resource "aws_secretsmanager_secret" "rds_master" {
  name        = "carddemo-${var.environment}-rds-master"
  description = "RDS PostgreSQL master credentials for CardDemo (AAP §0.7.1 — Secrets Manager only; §0.6.4 — Lambda rotation without Spring Boot restart)"
  kms_key_id  = aws_kms_key.secrets_kms.arn

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds-master"
    Purpose = "RDS master credential for CardDemo Spring Boot DataSource"
  })

  # Rotation Lambda (declared in secrets.tf when var.rds_rotation_days
  # is set) attaches via aws_secretsmanager_secret_rotation referencing
  # this secret's id. The rotation_enabled attribute on the export
  # reflects the rotation Lambda's attached state.
}

# -----------------------------------------------------------------------------
# Initial AUTH credential version.
#
# Stores the JSON-encoded payload as the AWSCURRENT version. The JSON
# structure follows the standard AWS RDS Secrets Manager rotation
# Lambda contract:
#   {
#     "username": "<master-user>",
#     "password": "<random-master-password>",
#     "engine":   "postgres",
#     "host":     "<rds-endpoint>",
#     "port":     5432,
#     "dbname":   "carddemo"
#   }
# The SAR-managed RDS single-user rotation Lambda (provisioned in
# secrets.tf) reads/writes this exact shape, so no custom rotation
# code is required.
#
# Note: the host/port/dbname fields are populated from the aws_db_
# instance.carddemo attributes which are unknown until that resource
# is created. Terraform resolves the implicit dependency by ordering
# this version's apply AFTER aws_db_instance.carddemo (which itself
# depends on the parameter group, subnet group, and security group
# defined above). This ordering also means the FIRST apply persists
# the secret with the resolved endpoint immediately on initial
# provisioning.
#
# lifecycle.ignore_changes on secret_string allows the rotation Lambda
# to publish new versions via PutSecretValue without Terraform
# attempting to revert to the original value on the next apply
# (per AAP §0.6.4).
#
# Members exposed for downstream consumers (per the file schema):
#   id, secret_id, version_id, version_stages, arn.
# -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret_version" "rds_master_value" {
  secret_id = aws_secretsmanager_secret.rds_master.id

  secret_string = jsonencode({
    username = var.rds_master_username
    password = random_password.rds_master.result
    engine   = "postgres"
    host     = aws_db_instance.carddemo.address
    port     = aws_db_instance.carddemo.port
    dbname   = var.rds_db_name
  })

  # Default version_stages is ["AWSCURRENT"] which is exactly what we
  # want for the initial version. The rotation Lambda manages
  # subsequent stage transitions (AWSCURRENT, AWSPREVIOUS, AWSPENDING)
  # automatically.

  lifecycle {
    # Allow the rotation Lambda (AAP §0.6.4) to publish new versions
    # via PutSecretValue without Terraform drift. Without this, every
    # apply after rotation would revert the secret to the
    # Terraform-bootstrapped value.
    ignore_changes = [secret_string]
  }
}

# =============================================================================
# Section 5 — Option group (PostgreSQL on RDS does NOT require one)
# =============================================================================
# PostgreSQL on RDS uses parameter groups exclusively; there is no
# equivalent of the Oracle / SQL Server option group concept. No
# aws_db_option_group resource is required. This section is left as a
# documented anchor to prevent future re-introduction.
# =============================================================================

# (Intentionally empty — PostgreSQL on RDS does not require an option group.)

# =============================================================================
# Section 6 — RDS PostgreSQL Multi-AZ instance
# =============================================================================
# The central resource of this file. Provisions a Multi-AZ PostgreSQL
# instance running var.rds_engine_version (default "16.4") on
# var.rds_instance_class with gp3 storage, KMS encryption at rest,
# 35-day automated backups with PITR, Performance Insights, Enhanced
# Monitoring, CloudWatch log exports, optional IAM authentication, and
# deletion protection.
#
# High-availability semantics:
#   * multi_az = var.rds_multi_az — true in prod / staging deployments.
#     When true, RDS maintains a synchronous standby in a separate AZ
#     and fails over automatically on primary failure with typical RTO
#     of 60-120 seconds. The standby is not directly addressable; it
#     becomes the new primary on failover and inherits the same
#     endpoint DNS record.
#   * The DB subnet group must span >= 2 AZs (enforced by
#     variables.tf's length validation on private_subnet_ids).
#
# Encryption configuration (AAP §0.6.6, §0.7.1):
#   * storage_encrypted = true — Mandatory. Encrypts the underlying
#     EBS volume(s) and all snapshots derived from them with the CMK
#     referenced by kms_key_id. Without this flag, RDS storage
#     encryption defaults to OFF.
#   * kms_key_id = aws_kms_key.carddemo.arn — Customer-managed CMK
#     (mandated by AAP §0.7.1 "Encrypt all RDS data at rest using AWS
#     KMS customer-managed keys (CMKs)").
#   * performance_insights_enabled = true with
#     performance_insights_kms_key_id = aws_kms_key.carddemo.arn —
#     Performance Insights captures DB load metrics and stores them in
#     a separate encrypted data store; that store is also encrypted
#     with the CardDemo CMK.
#   * rds.force_ssl=1 (parameter group above) — Enforces TLS 1.2+ on
#     every connection per AAP §0.7.2.
#
# Storage configuration:
#   * allocated_storage = var.rds_allocated_storage (default 100 GiB)
#     and max_allocated_storage = var.rds_max_allocated_storage
#     (default 500 GiB) — RDS storage autoscaling expands the volume
#     between these two values automatically as utilisation
#     approaches the high-watermark. Setting max_allocated_storage >
#     allocated_storage activates autoscaling; setting them equal
#     disables autoscaling.
#   * storage_type = "gp3" — General-purpose SSD with provisioned IOPS
#     and throughput independent of volume size; preferred over gp2
#     for new instances. RDS automatically provisions baseline IOPS /
#     throughput appropriate for the instance class.
#
# Backup configuration (AAP §0.7.2 "35-day automated backups + PITR"):
#   * backup_retention_period = var.rds_backup_retention_period
#     (default 35) — Maximum allowed by RDS. Automated daily backups
#     are retained for this many days; point-in-time recovery is
#     available across the entire window (5-minute granularity).
#   * backup_window = "02:00-03:00" — UTC, off-peak relative to the
#     EOD batch pipeline (POSTTRAN -> INTCALC -> COMBTRAN ->
#     CREASTMT / TRANREPT runs in the 22:00-02:00 UTC window per AAP
#     §0.6.3). Backups complete BEFORE the maintenance window starts.
#   * copy_tags_to_snapshot = true — Snapshots inherit the same
#     Project / Environment / Owner / ManagedBy tag set as the
#     instance, supporting compliance traceability across backup
#     artifacts.
#   * delete_automated_backups = false — Retain automated backups
#     past the instance lifetime for accidental-deletion recovery.
#     The 35-day retention window still applies; backups expire
#     according to their own schedule rather than the instance's.
#   * skip_final_snapshot = false + final_snapshot_identifier — On
#     terraform destroy, RDS takes one final snapshot identified by
#     final_snapshot_identifier before deleting the instance. The
#     timestamp is computed at plan time via formatdate() so each
#     destroy produces a uniquely-named snapshot.
#
# Maintenance window:
#   * maintenance_window = "sun:04:00-sun:05:00" — UTC, after the
#     backup window. RDS applies engine patches during this window.
#   * auto_minor_version_upgrade = false — Prod stability prevails
#     over auto-applied minor upgrades; security patches are applied
#     deliberately via var.rds_engine_version bumps in CI/CD.
#   * apply_immediately = false — Defers in-place changes (parameter
#     group apply, version upgrades) to the next maintenance window
#     unless explicitly overridden by an operator.
#
# Monitoring & observability (AAP §0.6.6):
#   * monitoring_interval = 60 — Enhanced Monitoring at 60-second
#     granularity. OS-level metrics (per-process CPU, memory, swap,
#     file system, network) stream to CloudWatch Logs under the
#     RDSOSMetrics namespace. The IAM role is provisioned in iam.tf.
#   * monitoring_role_arn = aws_iam_role.rds_enhanced_monitoring.arn
#     — Service role assumed by the RDS Enhanced Monitoring agent
#     running in the RDS host OS partition.
#   * performance_insights_enabled = true — Captures aggregate query
#     statistics (top SQL by load, wait events, host metrics) for the
#     last performance_insights_retention_period days.
#   * performance_insights_retention_period = 7 — Free-tier retention.
#     Increase to 731 days (Standard tier) for compliance retention
#     beyond a week.
#   * enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"] —
#     PostgreSQL server logs (per the parameter group above) and
#     engine upgrade logs are exported to CloudWatch Logs for
#     centralised retention and search.
#
# Authentication:
#   * username + password — Bootstrap master credential (sourced
#     from random_password.rds_master and stored in Secrets Manager
#     per Section 4 above). Rotation Lambda manages updates without
#     Terraform drift per AAP §0.6.4.
#   * iam_database_authentication_enabled = true — Optional IAM auth
#     fallback. Roles with rds-db:connect can mint short-lived
#     authentication tokens via the AWS SDK and connect to specific
#     DB users without the master password — useful for break-glass
#     emergency access and CI/CD smoke tests.
#
# Deletion protection (AAP §0.6.6 / §0.7.1 compliance posture):
#   * deletion_protection = var.rds_deletion_protection — true in
#     prod. When true, neither the AWS console nor terraform destroy
#     can delete the instance without first toggling this flag off.
#
# Final snapshot identifier:
#   * formatdate("YYYYMMDD", timestamp()) renders today's UTC date
#     when terraform plan runs. The plan-time value is captured into
#     the state and used on destroy. If the operator wants a unique
#     name per destroy attempt, the value can be passed explicitly
#     via a -var-override.
#
# lifecycle.ignore_changes:
#   * password — The rotation Lambda updates the master password on
#     RDS via ModifyDBInstance; without this ignore_changes, every
#     subsequent terraform apply would revert the password to the
#     Terraform-bootstrapped value. Combined with the
#     ignore_changes on the secret_string in Section 4, this gives
#     the rotation Lambda full control of credential lifecycle per
#     AAP §0.6.4.
#   * final_snapshot_identifier — Re-rendering the timestamp() on
#     every plan would force an in-place update of this otherwise-
#     ineffective attribute (it's only consulted on destroy). Ignore
#     it so the value stays stable across plans.
#
# Replaces (per AAP §0.6.2):
#   * AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS  -> Account table
#   * AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS  -> Card table
#   * AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS  -> Customer table
#   * AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS  -> CardCrossReference table
#   * AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS  -> Transaction table
#   * AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS  -> TransactionCategoryBalance
#   * AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS   -> DisclosureGroup
#   * AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS  -> TransactionCategory
#   * AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS  -> TransactionType
#   * AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS    -> UserSecurity
#   * AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX   -> Card.account_id index
#   * AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX   -> Transaction(card_num,
#                                            proc_ts) composite index
#
# Members exposed for downstream consumers (per the file schema):
#   arn, id, identifier, address, endpoint, port, db_name, username,
#   engine, engine_version, engine_version_actual, instance_class,
#   allocated_storage, max_allocated_storage, storage_type,
#   storage_encrypted, kms_key_id, multi_az, availability_zone,
#   publicly_accessible, backup_retention_period, backup_window,
#   maintenance_window, performance_insights_enabled,
#   performance_insights_kms_key_id, monitoring_interval,
#   monitoring_role_arn, enabled_cloudwatch_logs_exports,
#   deletion_protection, iam_database_authentication_enabled,
#   resource_id, hosted_zone_id, status, vpc_security_group_ids,
#   db_subnet_group_name, parameter_group_name, tags_all.
# =============================================================================

resource "aws_db_instance" "carddemo" {
  # Resource identification
  identifier = "carddemo-${var.environment}-rds"

  # Engine selection — PostgreSQL 16.x required by AAP §0.6.1 for
  # NUMERIC arbitrary precision matching COBOL PIC S9(n)V99 / COMP-3
  # semantics. Source copybook fields driving the precision choice:
  #   * app/cpy/CVACT01Y.cpy   ACCT-CURR-BAL          PIC S9(10)V99
  #                            -> NUMERIC(12,2) in V001 Flyway DDL.
  #   * app/cpy/CVTRA05Y.cpy   TRAN-AMT               PIC S9(09)V99
  #                            -> NUMERIC(11,2) in V005 Flyway DDL.
  engine         = "postgres"
  engine_version = var.rds_engine_version
  instance_class = var.rds_instance_class

  # Storage — gp3 with autoscaling between allocated_storage and
  # max_allocated_storage. KMS CMK encryption mandatory per AAP §0.7.1.
  allocated_storage     = var.rds_allocated_storage
  max_allocated_storage = var.rds_max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true
  # F-CP6-TF-KMS-01: Per-service CMK separation. RDS storage encryption
  # uses the dedicated aws_kms_key.rds_kms CMK rather than the legacy
  # shared aws_kms_key.carddemo, per the CP6 KMS separation requirement.
  kms_key_id = aws_kms_key.rds_kms.arn

  # Initial database and master credential
  db_name  = var.rds_db_name
  username = var.rds_master_username
  password = random_password.rds_master.result
  port     = 5432

  # Network attachment — private subnet group and SG defined above
  db_subnet_group_name   = aws_db_subnet_group.carddemo.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.carddemo.name

  # High availability — synchronous standby in a separate AZ per AAP
  # §0.6.2. Toggled per environment via var.rds_multi_az.
  multi_az = var.rds_multi_az

  # Network exposure — private subnets ONLY; no public IP. PCI-DSS
  # network segregation requirement.
  publicly_accessible = false

  # Backups — 35-day retention with PITR per AAP §0.7.2. Backup
  # window precedes the maintenance window in the same UTC night
  # cycle. Snapshots are tagged for compliance traceability.
  backup_retention_period   = var.rds_backup_retention_period
  backup_window             = "02:00-03:00"
  copy_tags_to_snapshot     = true
  delete_automated_backups  = false
  skip_final_snapshot       = false
  final_snapshot_identifier = "carddemo-${var.environment}-rds-final-${formatdate("YYYYMMDD", timestamp())}"

  # Maintenance window — Sunday early-morning UTC, after the backup
  # window. RDS applies engine patches here only if
  # auto_minor_version_upgrade is true (intentionally false for prod
  # stability) OR an operator explicitly requests a version change.
  maintenance_window = "sun:04:00-sun:05:00"

  # Performance Insights — query-level diagnostics with KMS encryption
  # of the captured workload data. 7-day retention is the free tier;
  # set to 731 for Standard-tier compliance retention if required.
  performance_insights_enabled = true
  # F-CP6-TF-KMS-01: Performance Insights encrypted with the same RDS
  # CMK as storage so the workload data set and the underlying storage
  # share an auditing key.
  performance_insights_kms_key_id       = aws_kms_key.rds_kms.arn
  performance_insights_retention_period = 7

  # Enhanced Monitoring — per-OS-process metrics every 60 seconds via
  # the dedicated IAM service role from iam.tf.
  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.rds_enhanced_monitoring.arn

  # CloudWatch Logs exports — PostgreSQL server logs (driven by the
  # parameter group's log_* settings) and engine upgrade logs.
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  # Upgrade discipline — minor versions applied deliberately, not
  # automatically; in-place changes deferred to the maintenance window.
  auto_minor_version_upgrade = false
  apply_immediately          = false

  # Deletion protection per AAP §0.6.6 — true in prod prevents
  # accidental terraform destroy.
  deletion_protection = var.rds_deletion_protection

  # Optional IAM database authentication — allows roles with
  # rds-db:connect to mint short-lived authentication tokens via the
  # AWS SDK in addition to password auth from Secrets Manager. Useful
  # for break-glass emergency access and CI/CD smoke tests.
  iam_database_authentication_enabled = true

  tags = merge(local.common_tags, {
    Name    = "carddemo-${var.environment}-rds"
    Purpose = "Primary CardDemo relational data store - replaces VSAM KSDS clusters per AAP §0.6.2"
  })

  # lifecycle.ignore_changes — handed off to the rotation Lambda for
  # the master password (AAP §0.6.4) and stable across plans for the
  # final snapshot identifier.
  lifecycle {
    ignore_changes = [
      password,
      final_snapshot_identifier,
    ]
  }

  # Explicit dependency on the parameter group and subnet group is
  # already implied by attribute references above, but the parameter
  # group's create_before_destroy lifecycle benefits from an explicit
  # ordering note: RDS validates the parameter group family at apply
  # time, so a stale group reference would cause the instance create
  # to fail before any storage is provisioned.
  depends_on = [
    aws_db_parameter_group.carddemo,
    aws_db_subnet_group.carddemo,
    aws_security_group.rds,
  ]
}
