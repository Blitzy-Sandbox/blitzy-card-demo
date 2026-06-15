-- =====================================================================================
-- Flyway Migration V5 -- CardDemo Spring Batch metadata schema (PostgreSQL 16)
-- =====================================================================================
-- Purpose : Fifth versioned migration. Provisions the Spring Batch JDBC JobRepository
--           metadata schema -- the 6 BATCH_* tables and 3 BATCH_*_SEQ sequences -- that
--           Spring Batch requires to launch, track, and restart jobs. WITHOUT these
--           objects every batch job launch fails at runtime the moment the framework
--           checks for a prior JobInstance, e.g.:
--               org.postgresql.util.PSQLException:
--                 ERROR: relation "batch_job_instance" does not exist
--           thrown from SimpleJobRepository.isJobInstanceExists(...) via
--           JobLauncherApplicationRunner. This migration is the missing piece that makes
--           the JCL -> Spring Batch pipeline (AAP 0.7.6: POSTTRAN -> INTCALC -> COMBTRAN
--           -> CREASTMT/TRANREPT) actually runnable in a real (non-test) deployment.
--
-- Why Flyway owns this schema (NOT Spring Batch's own initializer) :
--           The application runs with BOTH of the following set in application.yml:
--               spring.jpa.hibernate.ddl-auto: validate   (Hibernate creates NOTHING)
--               spring.batch.jdbc.initialize-schema: never (Spring Batch creates NOTHING)
--           These two settings deliberately make Flyway the SINGLE schema authority for
--           the entire database so there is exactly one, ordered, checksummed source of
--           truth and no start-up race between Flyway and Spring Batch's
--           DataSourceScriptDatabaseInitializer. Consequently the BATCH_* objects MUST be
--           created by a Flyway migration (this file). Leaving initialize-schema=never and
--           adding this script keeps Flyway the sole owner; Spring Batch then simply USES
--           the tables it finds. (See the matching NOTE in application.yml under
--           spring.batch.jdbc.) 
--
-- ddl-auto=validate context : Hibernate `validate` only inspects the tables mapped by the
--           JPA @Entity classes in com.cardemo.model.entity.* . The BATCH_* tables have NO
--           JPA entity (Spring Batch accesses them through plain JDBC, not Hibernate), so
--           Hibernate neither validates nor touches them -- adding them here cannot affect
--           startup validation of the 11 business tables.
--
-- Flyway  : The file name encodes version "5" and description "batch_metadata"
--           (V<version>__<description>.sql -- single underscore after the version, double
--           underscore before the description). It is auto-discovered through
--           spring.flyway.locations=classpath:db/migration and executed exactly once, in
--           strict version order, AFTER V4__user_type_not_null.sql and BEFORE any @Service
--           or Spring Batch job runs -- consistent with the V1-V4 startup-ordering contract
--           (AAP 0.4.4). Once shipped this file is IMMUTABLE: Flyway records its checksum and
--           a later edit would break validation on already-migrated databases.
--
-- Authoritative source / fidelity : the DDL below is the canonical Spring Batch PostgreSQL
--           schema taken VERBATIM from the framework's own resource
--               org/springframework/batch/core/schema-postgresql.sql
--           shipped in spring-batch-core 5.2.6 (the version resolved by the Spring Boot
--           3.5.x BOM in this project's pom.xml). Reproducing it verbatim -- rather than
--           hand-authoring equivalent DDL -- guarantees the table names, column names,
--           types, lengths, primary keys, foreign keys, and sequence definitions match
--           EXACTLY what the framework's JdbcJobRepository / sequence DAOs expect, so the
--           production behaviour is identical to letting Spring Batch initialize its own
--           schema (the masked test-only path), only now owned by Flyway. Unquoted
--           identifiers fold to lower-case in PostgreSQL (e.g. BATCH_JOB_INSTANCE ->
--           batch_job_instance), which matches Spring Batch's own unquoted SQL and the
--           default table prefix BATCH_ (spring.batch.jdbc.table-prefix is NOT customized).
--
-- Technology substitution (AAP 0.7.1 -- documented at the point of change) : the legacy
--           JCL/JES estate had NO metadata store -- each `EXEC PGM=...` step (e.g.
--           app/jcl/POSTTRAN.jcl -> CBTRN02C, app/jcl/INTCALC.jcl -> CBACT04C) was a
--           discrete JES submission whose state lived in JES spool / SDSF, not in a
--           relational table. The Spring Batch JobRepository is the modern replacement for
--           that JES-managed execution state (job/step instances, executions, parameters,
--           and restart/skip/commit counters); these tables are its backing store.
--
-- Traceability : Source = AWS CardDemo COBOL/JCL baseline, commit 27d6c6f. No COBOL / JCL
--           source text is copied here; the schema is framework-supplied infrastructure
--           with no COBOL analogue. Source artifacts consulted for the pipeline this
--           repository backs: app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl, app/jcl/COMBTRAN.jcl,
--           app/jcl/CREASTMT.JCL, app/jcl/TRANREPT.jcl.
--
-- Minimal Change Clause (AAP 0.7.1) : this migration ADDS only the framework-required batch
--           metadata objects and changes nothing else -- no business table, no business
--           data, no behavioral rule. It is the minimal change that makes the documented
--           JCL -> Spring Batch migration operational at runtime.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- Spring Batch core schema (canonical, spring-batch-core 5.2.6 / schema-postgresql.sql).
-- Tables are created in foreign-key dependency order; sequences follow. Identifiers are
-- intentionally unquoted (PostgreSQL folds them to lower-case) to match the framework's
-- own SQL and the default BATCH_ table prefix.
-- -------------------------------------------------------------------------------------

CREATE TABLE BATCH_JOB_INSTANCE  (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT ,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION  (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT  ,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS  (
	JOB_EXECUTION_ID BIGINT NOT NULL ,
	PARAMETER_NAME VARCHAR(100) NOT NULL ,
	PARAMETER_TYPE VARCHAR(100) NOT NULL ,
	PARAMETER_VALUE VARCHAR(2500) ,
	IDENTIFYING CHAR(1) NOT NULL ,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION  (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY ,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL ,
	END_TIME TIMESTAMP DEFAULT NULL ,
	STATUS VARCHAR(10) ,
	COMMIT_COUNT BIGINT ,
	READ_COUNT BIGINT ,
	FILTER_COUNT BIGINT ,
	WRITE_COUNT BIGINT ,
	READ_SKIP_COUNT BIGINT ,
	WRITE_SKIP_COUNT BIGINT ,
	PROCESS_SKIP_COUNT BIGINT ,
	ROLLBACK_COUNT BIGINT ,
	EXIT_CODE VARCHAR(2500) ,
	EXIT_MESSAGE VARCHAR(2500) ,
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT  (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT  (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT ,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
