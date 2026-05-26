## CardDemo -- Mainframe CardDemo Application

> **Repository contents — dual implementation tree.**
> This repository contains BOTH the original IBM Enterprise COBOL / CICS / VSAM / JCL source
> (preserved frozen under `app/` and `samples/jcl/` as the authoritative reference for
> behavioral parity validation) AND a new Java 17+ / Spring Boot 3.x / AWS-native target
> implementation (under `src/`, `pom.xml`, `Dockerfile`, `docker-compose.yml`, `infrastructure/`,
> `.github/workflows/`, and `localstack/init/`). The COBOL artifacts under `app/` are never
> edited; the Java target is added in parallel so that every behavior in the live system can
> be traced back to its original COBOL paragraph during the parallel-run cutover window.

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-carddemo-application)
- [Description](#description)
- [Technologies used](#technologies-used)
  - [Legacy mainframe technologies](#legacy-mainframe-technologies)
  - [Target technologies (Java / Spring Boot / AWS)](#target-technologies-java--spring-boot--aws)
- [Installation on the mainframe](#installation-on-the-mainframe)
- [Running full batch](#running-full-batch)
- [Java / Spring Boot / AWS Migration Target](#java--spring-boot--aws-migration-target)
  - [Migration objective](#migration-objective)
  - [Target stack summary](#target-stack-summary)
  - [Repository layout](#repository-layout)
  - [Transformation rules](#transformation-rules)
  - [REST endpoint inventory](#rest-endpoint-inventory)
- [Build and Run (Java / Spring Boot)](#build-and-run-java--spring-boot)
  - [Prerequisites](#prerequisites)
  - [Build and run](#build-and-run)
  - [LocalStack setup](#localstack-setup)
  - [Environment variables](#environment-variables)
- [AWS Deployment](#aws-deployment)
- [Testing and Validation](#testing-and-validation)
- [Documentation](#documentation)
- [Application Details](#application-details)
  - [User Functions](#user-functions)
  - [Admin Functions](#admin-functions)
  - [Application Inventory](#application-inventory)
    - [**Online**](#online)
    - [**Batch**](#batch)
  - [Application Screens](#application-screens)
    - [**Signon Screen**](#signon-screen)
    - [**Main Menu**](#main-menu)
    - [**Admin Menu**](#admin-menu)
- [Support](#support)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)
- [Project status](#project-status)

<br/>

## Description
CardDemo is a Mainframe application designed and developed to test and showcase AWS and partner technology for mainframe migration and modernization use-cases such as discovery, migration, modernization, performance test, augmentation, service enablement, service extraction, test creation, test harness, etc.

Note that the intent of this application is to provide mainframe coding scenarios to excercise analysis, transformation and migration tooling. So, the coding style is not uniform across the application

<br/>

## Technologies used

### Legacy mainframe technologies
1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

### Target technologies (Java / Spring Boot / AWS)

The modernized target stack runs the same business logic as the legacy mainframe application,
re-implemented in Java and deployed on AWS-native managed services. Categories below match
the layering used throughout the new implementation tree under `src/`, `pom.xml`, and
`infrastructure/terraform/`.

- **Language and build:** Java 17+ (Eclipse Temurin LTS), Apache Maven 3.8+ (via the
  bundled Maven Wrapper `./mvnw` / `mvnw.cmd`)
- **Application framework:** Spring Boot 3.x with starters `web`, `data-jpa`, `batch`,
  `security`, `actuator`, `validation`, `data-redis`
- **Data access and migrations:** Spring Data JPA + Hibernate against RDS PostgreSQL,
  Flyway for schema and seed-data migrations
- **Messaging and batch:** Spring Kafka (against Amazon MSK), Spring Batch (executed inside
  AWS Batch on Fargate)
- **Security:** Spring Security 6, JWT (jjwt), BCrypt password hashing
- **AWS integration:** AWS SDK for Java v2, Spring Cloud AWS 3.x
  (`spring-cloud-aws-starter`, `-secrets-manager`, `-parameter-store`)
- **Managed AWS services:** RDS PostgreSQL Multi-AZ, ElastiCache Redis, MSK (Kafka),
  AWS Step Functions, AWS Batch, AWS Glue, ECS Fargate, Application Load Balancer (ALB),
  Amazon S3, AWS KMS, AWS Secrets Manager, AWS Systems Manager Parameter Store,
  AWS WAF, AWS Shield, Amazon Macie, AWS CloudTrail, Amazon CloudWatch (Container
  Insights, Logs, Metrics, Alarms), Amazon OpenSearch
- **Build and runtime images:** Docker (multi-stage), `eclipse-temurin:17-jre-alpine`
  runtime, Amazon ECR for image registry
- **Local development:** LocalStack Pro 4.14.0 (S3, SQS, Secrets Manager, KMS, etc.),
  Docker Compose, Testcontainers (PostgreSQL, Kafka, LocalStack)
- **Infrastructure as Code:** Terraform (modules under `infrastructure/terraform/`)
- **CI/CD:** GitHub Actions (`.github/workflows/build.yml`, `docker-build.yml`, `deploy.yml`)

<br/>

## Installation on the mainframe

To install this repository on the mainframe please follow the following steps

1. Clone this repository to your local development environment

2. Create datasets on the mainframe  hold the code
   * It is recommended to group them under a High Level Qualifier (HLQ)for all your datasets.
   * Upload the following application source folders from the main branch of git repository on to your mainframe
      using $INDFILE or your preferred upload tool.
   * If you have used AWS.M2 as your HLQ, you should end up with the below code structure on the mainframe

      | HLQ    | Name          | Format | Length |
      | :----- | :------------ | :----- | -----: |
      | AWS.M2 | CARDDEMO.JCL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.PROC | FB     |     80 |
      | AWS.M2 | CARDDEMO.CBL  | FB     |     80 |
      | AWS.M2 | CARDDEMO.CPY  | FB     |     80 |
      | AWS.M2 | CARDDEMO.BMS  | FB     |     80 |

3. Use data for testing using either of the below approaches

   ** Use the supplied sample data**

      * Upload the sample data provided in the main/-/data/EBCDIC/ folder to the mainframe. Ensure that you use transfer mode binary

         | Dataset name                      | Name                                             | Copybook (Layout) | Format | Length | Name of equivalent ascii file |
         | :---------------------------------| :----------------------------------------------- | :-----            | :----- | -----: | :---------------------------- |
         | AWS.M2.CARDDEMO.USRSEC.PS         | User Security file                               | CSUSR01Y          | FB     |     80 | See DEFUSR01.jcl (inline)     |
         | AWS.M2.CARDDEMO.ACCTDATA.PS       | Account Data                                     | CVACT01Y          | FB     |    300 | acctdata.txt                  |
         | AWS.M2.CARDDEMO.CARDDATA.PS       | Card Data                                        | CVACT02Y          | FB     |    150 | carddata.txt                  |
         | AWS.M2.CARDDEMO.CUSTDATA.PS       | Customer Data                                    | CVCUS01Y          | FB     |    500 | custdata.txt                  |
         | AWS.M2.CARDDEMO.CARDXREF.PS       | Customer Account Card Cross reference            | CVACT03Y          | FB     |     50 | cardxref.txt                  |
         | AWS.M2.CARDDEMO.DALYTRAN.PS.INIT  | Transaction database initialization record       | CVTRA06Y          | FB     |    350 | 1 record (low-values ending with 00000100)|
         | AWS.M2.CARDDEMO.DALYTRAN.PS       | Transaction data which has to go through posting | CVTRA06Y          | FB     |    350 | dailytran.txt                 |
         | AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS| Transaction data entered online                  | CVTRA05Y          | FB     |    350 | not applicable                |
         | AWS.M2.CARDDEMO.DISCGRP.PS        | Disclosure Groups                                | CVTRA02Y          | FB     |     50 | discgrp.txt                   |
         | AWS.M2.CARDDEMO.TRANCATG.PS       | Transaction Category Types                       | CVTRA04Y          | FB     |     60 | trancatg.txt                  |
         | AWS.M2.CARDDEMO.TRANTYPE.PS       | Transaction Types                                | CVTRA03Y          | FB     |     60 | trantype.txt                  |
         | AWS.M2.CARDDEMO.TCATBALF.PS       | Transaction Category Balance                     | CVTRA01Y          | FB     |     50 | tcatbal.txt                   |

      * Execute the following JCLs in order

         | Jobname  | What it does                                        |
         | :------- | :-------------------------------------------------- |
         | DUSRSECJ | Sets up user security vsam file                     |
         | CLOSEFIL | Closes files opened by CICS                         |
         | ACCTFILE | Loads Account database using sample data            |
         | CARDFILE | Loads Card database with credit card sample data    |
         | CUSTFILE | Creates customer database                           |
         | XREFFILE | Loads Customer Card account cross reference to VSAM |
         | TRANFILE | Copies initial Trasaction file  to VSAM             |
         | DISCGRP  | Copies initial Disclosure Group file  to VSAM       |
         | TCATBALF | Copies initial TCATBALF file  to VSAM               |
         | TRANCATG | Copies initial transaction category file  to VSAM   |
         | TRANTYPE | Copies initial transaction type file                |
         | OPENFIL  | Makes files available to CICS                       |
         | DEFGDGB  | Defines GDG Base                                    |


4. Compile the Programs.

   You should use the compile process followed by your mainframe shopfloor

   We have however provided some sample JCLs in the samples folder in git to help you craft the JCL

5. Create resources in the CARDDEMO group in CICS

   You have 2 options

   Be sure to edit the HLQs in the below documents as required before you do the definition

   * (Preferred) . Use the DFHCSDUP JCL that the resources required by the application

      The resources required are in the CSD file provided in the CSD folder

      * Group CARDDEMO
      * Mapsets
      * Transactions
      * Maps
      * Files

   * Use the CEDA transaction to execute the commands in the above listing

      * Define group
         ```shell
         DEFINE LIBRARY(COM2DOLL) GROUP(CARDDEMO) DSNAME01(&HLQ..LOADLIB)
         ```
      * Define Mapsets, Maps , Programs and Files

         Sample CEDA commands

         ```shell
         DEF PROGRAM(COCRDLIC) GROUP(CARDDEMO)
         DEF MAPSET(COCRDLI) GROUP(CARDDEMO)
         DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO) DA(ANY) TRANSID(CC00) DESCRIPTION(LOGIN)
         DEFINE TRANSACTION(CC00) GROUP(CARDDEMO) PROGRAM(COSGN00C) TASKDATAL(ANY)
         ```

   * Install /Load the online resources to your CICS region

      ```shell
      CEDA INSTALL TRANS(CCLI) GROUP(CARDDEMO)
      CEDA INSTALL FILE(CARDDAT) GROUP(CARDDEMO)
      CECI LOAD PROG(COCRDUP)
      CECI LOAD PROG(COCRDUPC)
      ```

   * Execute a NEWCOPY of mapsets and maps
      ```shell
      CEMT SET PROG(COCRDUP) NEWCOPY
      CEMT SET PROG(COCRDUPC) NEWCOPY
      ```
6. Enjoy the demo

   * For online functions : Start the CardDemo application using the CC00 transaction
     - Enter userid ADMIN001 and the initially configured password PASSWDA1 to manage users
     - Enter userid USER0001 and the initially configured password PASSWDU1 to access back office functions

   > **Note (Java target):** The Java/Spring Boot port enforces the COBOL
   > `SEC-USR-PWD PIC X(08)` 8-byte password contract via Jakarta Bean
   > Validation on `SignonRequestDto`. Default passwords are 8 characters
   > (`PASSWDA1` for `ADMIN001`, `PASSWDU1` for `USER0001`); see
   > `src/main/resources/db/migration/V015__seed_default_users.sql` for the
   > BCrypt-hashed seed values.
   * For batch            : See the instructions for running full batch below.

## Running full batch

  * Execute the following JCLs in order

    | Jobname  | What it does                                        |
    | :------- | :-------------------------------------------------- |
    | CLOSEFIL | Closes files opened by CICS                         |
    | ACCTFILE | Loads Account database using sample data            |
    | CARDFILE | Loads Card database with credit card sample data    |
    | XREFFILE | Loads Customer Card account cross reference to VSAM |
    | CUSTFILE | Creates customer database                           |
    | TRANBKP  | Creates Transaction database                        |
    | DISCGRP  | Copies initial disclosure Group file  to VSAM       |
    | TCATBALF | Copies initial TCATBALF file  to VSAM               |
    | TRANTYPE | Copies initial transaction type file                |
    | DUSRSECJ | Sets up user security vsam file                     |
    | POSTTRAN | Core processing job                                 |
    | INTCALC  | Run interest calculations                           |
    | TRANBKP  | Backup Transaction database                         |
    | COMBTRAN | Combine system transactions with daily ones         |
    | CREASTMT | Produce transaction statement                       |
    | TRANIDX  | Define alternate index on transaction file          |
    | OPENFIL  | Makes files available to CICS                       |
<br/>

## Java / Spring Boot / AWS Migration Target

This section documents the modernized Java 17+ / Spring Boot 3.x / AWS-native implementation
that runs alongside the preserved COBOL source. The migration is intentionally **layered and
additive**: COBOL artifacts remain physically present under `app/` and are never edited;
the Java target is introduced as a parallel implementation tree under new top-level paths.

### Migration objective

Translate the AWS CardDemo mainframe COBOL application — comprising 28 COBOL programs,
28 shared copybooks, 17 BMS mapsets, 17 generated symbolic-map copybooks, 29 JCL job members,
9 ASCII seed-data fixtures, and 1 IDCAMS LISTCAT inventory — to a Java 17+ / Spring Boot 3.x
application deployed on AWS-native managed services with 100% financial business-logic
fidelity and PCI-DSS compliance. Demo-ready by **May 20, 2026**.

The transformation spans every architectural layer simultaneously: language (COBOL → Java
17+), persistence (VSAM KSDS/AIX/PATH and sequential PS files → RDS PostgreSQL Multi-AZ
with Spring Data JPA), orchestration (JCL/JES → AWS Step Functions + AWS Batch), messaging
(CICS TDQ → Amazon MSK/Kafka), caching (none → Amazon ElastiCache Redis), security
(file-based plaintext credentials → AWS KMS + Secrets Manager + WAF + Shield + Macie),
observability (none → CloudWatch + Container Insights + OpenSearch + CloudTrail + Spring
Actuator), and runtime (z/OS mainframe LPAR → ECS Fargate behind ALB).

### Target stack summary

| Concern             | Technology / Service                                                                                               |
| :------------------ | :----------------------------------------------------------------------------------------------------------------- |
| Runtime             | OpenJDK (Eclipse Temurin) 17+ LTS                                                                                  |
| Build               | Apache Maven 3.8+ (via Maven Wrapper `mvnw` / `mvnw.cmd`)                                                          |
| Application         | Spring Boot 3.x (web, data-jpa, batch, security, actuator, validation, data-redis)                                 |
| Persistence         | RDS PostgreSQL 16+ Multi-AZ via Spring Data JPA + Hibernate                                                        |
| Caching             | Amazon ElastiCache Redis (cache-aside, allkeys-lru, TTL aligned to transaction frequency)                          |
| Messaging           | Amazon MSK (Kafka) topics `transaction.posted`, `account.updated`, `ledger.balanced`, `report.requested` partitioned by account ID |
| Orchestration       | AWS Step Functions + AWS Batch (Spring Batch jobs running in Fargate)                                              |
| Object storage      | Amazon S3 (versioned, SSE-KMS, lifecycle policies) replacing GDG generations                                       |
| Security            | AWS WAF + Shield on ALB, AWS Secrets Manager, AWS KMS CMKs, Spring Security 6 + JWT + BCrypt                       |
| Compliance          | AWS Macie, AWS CloudTrail organization-level trail, PCI-DSS posture                                                |
| Observability       | Amazon CloudWatch Container Insights, Amazon OpenSearch, Micrometer → CloudWatch, Spring Actuator, Logback + logstash-logback-encoder JSON logs |
| Runtime container   | ECS Fargate behind Application Load Balancer (ALB)                                                                 |
| IaC                 | Terraform under `infrastructure/terraform/`                                                                        |
| Local emulation     | LocalStack Pro 4.14.0 (S3, SQS, Secrets Manager, KMS, etc.)                                                        |

### Repository layout

The repository now contains both the preserved COBOL tree and the new Java target tree
side by side:

```plaintext
/                                                   # repository root
├── README.md                                       # this file
├── CONTRIBUTING.md, CODE_OF_CONDUCT.md             # preserved
├── LICENSE, NOTICE                                 # preserved (Apache 2.0)
├── catalog-info.yaml                               # Backstage entity metadata
├── mkdocs.yml                                      # MkDocs configuration
├── pom.xml                                         # Maven build descriptor (Spring Boot 3.x)
├── mvnw, mvnw.cmd                                  # Maven Wrapper
├── .mvn/                                           # Maven Wrapper resources
├── Dockerfile                                      # multi-stage Docker build
├── docker-compose.yml                              # local dev stack
├── .dockerignore, .gitignore                       # ignore rules
├── app/                                            # preserved (COBOL source, frozen)
│   ├── cbl/                                        # preserved (28 COBOL programs)
│   ├── cpy/                                        # preserved (28 copybooks)
│   ├── bms/                                        # preserved (17 BMS mapsets)
│   ├── cpy-bms/                                    # preserved (17 symbolic copybooks)
│   ├── jcl/                                        # preserved (29 JCL jobs)
│   ├── catlg/LISTCAT.txt                           # preserved (catalog inventory)
│   └── data/ASCII/                                 # preserved (9 ASCII fixtures)
├── samples/jcl/                                    # preserved (3 sample build wrappers)
├── diagrams/                                       # diagrams and screenshots
│   ├── CARDDEMO-DataModel.drawio                   # data-model (extended with RDS schema)
│   ├── target-architecture.drawio                  # AWS-native target architecture (new)
│   ├── Admin-Menu.png, Main-Menu.png               # preserved
│   ├── Signon-Screen.png                           # preserved
│   └── Application-Flow-User.png,
│       Application-Flow-Admin.png                  # preserved
├── docs/                                           # documentation
│   ├── index.md                                    # landing page
│   ├── project-guide.md                            # project status and operational guidance
│   └── technical-specifications.md                 # full target stack technical contract
├── src/                                            # Java / Spring Boot tree
│   ├── main/
│   │   ├── java/com/awsm2/carddemo/
│   │   │   ├── CardDemoApplication.java            # @SpringBootApplication entry
│   │   │   ├── controller/                         # REST controllers (← CICS online programs)
│   │   │   ├── service/                            # @Service classes (one per COBOL program)
│   │   │   ├── repository/                         # Spring Data JPA repositories
│   │   │   ├── domain/                             # JPA @Entity classes
│   │   │   ├── dto/                                # Request/response DTOs (← BMS maps)
│   │   │   ├── adapter/                            # AWS SDK isolation (S3, Kafka, Step Functions, ...)
│   │   │   ├── batch/                              # Spring Batch jobs (← JCL batch programs)
│   │   │   ├── glue/                               # AWS Glue ETL configuration
│   │   │   ├── exception/                          # Domain exception hierarchy + @ControllerAdvice
│   │   │   ├── config/                             # Spring @Configuration classes
│   │   │   ├── validation/                         # Validation services (← CSLKPCDY, CSUTLDPY)
│   │   │   └── security/                           # JWT + BCrypt building blocks
│   │   └── resources/
│   │       ├── application.yml                     # base profile (non-sensitive)
│   │       ├── application-local.yml               # LocalStack-backed local config
│   │       ├── application-dev.yml                 # dev environment overlay
│   │       ├── application-prod.yml                # AWS-deployed config
│   │       ├── logback-spring.xml                  # structured JSON logging
│   │       ├── stepfunctions/                      # ASL state machine definitions
│   │       │   ├── eod-batch-pipeline.asl.json     # EOD batch chain (POSTTRAN → ... → CREASTMT/TRANREPT)
│   │       │   ├── file-provisioning.asl.json      # reference + seed data loader
│   │       │   └── report-pipeline.asl.json        # CORPT00C online-to-batch report bridge
│   │       └── db/migration/                       # Flyway migrations (← IDCAMS DEFINE CLUSTER)
│   │           └── V001__create_account.sql, ...
│   └── test/
│       ├── java/com/awsm2/carddemo/                # JUnit 5 + Mockito + Testcontainers tests
│       └── resources/
│           ├── application-test.yml
│           └── golden/                             # copies of app/data/ASCII/*.txt for golden diff
├── infrastructure/                                 # Infrastructure as Code
│   └── terraform/                                  # ECS, ALB, RDS, ElastiCache, MSK, S3, Step
│                                                   # Functions, Glue, KMS, Secrets Manager, WAF,
│                                                   # Shield, Macie, CloudTrail, OpenSearch,
│                                                   # CloudWatch, ECR, IAM
├── .github/workflows/                              # CI/CD pipelines
│   ├── build.yml                                   # mvn clean install + tests
│   ├── docker-build.yml                            # docker build + push to ECR
│   └── deploy.yml                                  # ECS task definition update
└── localstack/init/                                # LocalStack initialization scripts
    └── init-aws.sh                                 # create local S3 buckets, MSK topics, secrets
```

### Transformation rules

The following table condenses the source-to-target transformation rules applied
throughout the Java implementation. Each rule preserves the financial business-logic
semantics of the corresponding COBOL/CICS/JCL construct.

| Source construct                                       | Target pattern                                                                                |
| :----------------------------------------------------- | :-------------------------------------------------------------------------------------------- |
| COBOL `PIC S9(n)V99` / `COMP-3` (monetary fields)      | `java.math.BigDecimal` with `RoundingMode.HALF_EVEN` (banker's rounding); never `float`/`double` |
| COBOL `PARAGRAPH` / `SECTION`                          | Private method on the corresponding Java `@Service` class                                     |
| `COPY` / `REPLACE` directives                          | Shared DTOs, JPA entities, and utility classes under `domain/`, `dto/`, `validation/`, `util/` |
| `WORKING-STORAGE SECTION`                              | Java instance fields or `@Component`-scoped beans                                             |
| VSAM KSDS with keyed access                            | JPA `@Entity` + `JpaRepository<Entity, Key>`                                                  |
| VSAM AIX / PATH (alternate indexes)                    | Secondary database indexes + derived `findBy<Alt>` queries on the JPA repository              |
| CICS `READ` / `WRITE` / `REWRITE` / `DELETE`           | `findById` / `save` / `delete` on Spring Data JPA repositories                                |
| CICS `STARTBR` / `READNEXT` browse                     | `Pageable` + `Page<T>` paginated query                                                        |
| CICS `XCTL` / `LINK`                                   | HTTP redirect, REST call, or constructor-injected `@Service` invocation                       |
| CICS `SYNCPOINT` / `SYNCPOINT ROLLBACK`                | `@Transactional(rollbackFor = Exception.class)` with appropriate isolation level              |
| CICS pseudo-conversational COMMAREA (`COCOM01Y.cpy`)   | Stateless REST + JWT claims + ALB sticky sessions for stateful flows                          |
| CICS TDQ (online-to-batch bridge)                      | MSK Kafka topic `report.requested` consumed by Step Functions trigger                         |
| Sequential file `READ` / `WRITE`                       | Spring Batch `ItemReader` / `ItemWriter` against S3 via `S3OutputService` adapter             |
| GDG `(+1)` / `(0)` generations                         | Amazon S3 versioned objects with lifecycle policies                                           |
| JCL job stream dependencies                            | AWS Step Functions state machine sequencing                                                   |
| JCL `STEP EXEC` of a COBOL batch program               | AWS Batch job definition wrapping the Spring Batch job, submitted by Step Functions           |
| JCL `COND=` condition codes                            | Step Functions `Choice` state with branching                                                  |
| JCL parallel steps                                     | Step Functions `Parallel` state                                                               |
| `IDCAMS DEFINE CLUSTER`                                | Flyway migration under `src/main/resources/db/migration/V*.sql`                               |
| `IDCAMS REPRO`                                         | Spring Batch `ItemReader<File>` → `ItemWriter<JpaRepository>`                                 |
| `IEBGENER`                                             | AWS SDK S3 copy operation                                                                     |
| `IEFBR14`                                              | Step Functions `Pass` state                                                                   |
| DFSORT utility                                         | `java.util.Comparator` within a Spring Batch step                                             |
| BMS `MAPSET` / `MAP` / `MDF` field                     | Java DTO field with Jakarta Bean Validation annotations + OpenAPI schema                      |
| 3270 terminal screen                                   | REST endpoint behind ALB returning JSON                                                       |
| LE `CEEDAYS` (date validation)                         | `java.time.LocalDate.parse()` with `DateTimeFormatter`                                        |
| LE `CEE3ABD` (controlled abend)                        | `throw new CardDemoSystemException(reasonCode)`                                               |
| RACF user identity                                     | AWS IAM roles (service-to-service) + JWT (end-user)                                           |
| FILE STATUS `23` (record not found)                    | `RecordNotFoundException` → HTTP 404                                                          |
| FILE STATUS `22` (duplicate key)                       | `DuplicateRecordException` → HTTP 409                                                         |
| Snapshot mismatch on READ UPDATE / REWRITE             | JPA `@Version` optimistic lock → `ConcurrentModificationException` → HTTP 409                 |
| `ON SIZE ERROR` on arithmetic                          | Explicit overflow check → `OnSizeErrorException`                                              |

### REST endpoint inventory

The original 17 CICS transactions (3270 terminal screens) are replaced by REST endpoints
behind the ALB. Every BMS field contract is preserved as a Jakarta Bean Validation
constraint on the request DTO. Each endpoint maps to a single Java `@Service` class
that mirrors the corresponding COBOL program (one Java service per COBOL program — the
core refactor discipline).

| HTTP method and path                       | COBOL program(s) replaced            | Purpose                                                  |
| :----------------------------------------- | :----------------------------------- | :------------------------------------------------------- |
| `POST /api/auth/signin`                    | `COSGN00C`                           | JWT-based sign-on; routes by user type (ADMIN/USER)      |
| `GET /api/menu/main`                       | `COMEN01C`                           | Main menu options                                        |
| `GET /api/menu/admin`                      | `COADM01C`                           | Admin menu options                                       |
| `GET /api/accounts/{id}`                   | `COACTVWC`                           | Account inquiry (Account + Customer + CardCrossRef join) |
| `PUT /api/accounts/{id}`                   | `COACTUPC`                           | Account update with `@Version` optimistic lock           |
| `GET /api/cards`                           | `COCRDLIC`                           | Paginated card list (page size 7)                        |
| `GET /api/cards/{n}`                       | `COCRDSLC`                           | Card detail                                              |
| `PUT /api/cards/{n}`                       | `COCRDUPC`                           | Card update with `@Version` optimistic lock              |
| `GET /api/transactions`                    | `COTRN00C`                           | Paginated transaction list (page size 10)                |
| `GET /api/transactions/{id}`               | `COTRN01C`                           | Transaction detail                                       |
| `POST /api/transactions`                   | `COTRN02C`                           | Transaction creation; publishes `transaction.posted` to MSK |
| `POST /api/billing/pay`                    | `COBIL00C`                           | Bill payment; publishes `account.updated` to MSK         |
| `POST /api/reports/submit`                 | `CORPT00C`                           | Report submission; publishes `report.requested` to MSK   |
| `GET /api/admin/users`                     | `COUSR00C`                           | Paginated user list                                      |
| `POST /api/admin/users`                    | `COUSR01C`                           | User add with BCrypt password hashing                    |
| `PUT /api/admin/users/{id}`                | `COUSR02C`                           | User update; re-hash on password change                  |
| `DELETE /api/admin/users/{id}`             | `COUSR03C`                           | User delete (confirmation-then-delete)                   |

OpenAPI 3 documentation is generated by `springdoc-openapi` and served at
`/v3/api-docs` (JSON), `/v3/api-docs.yaml` (YAML), and `/swagger-ui.html`
(gated by Spring Profile in production; see
`SecurityConfig.requestMatchers` for the public-path whitelist). Domain
exceptions are translated to standardized JSON error envelopes by
`GlobalExceptionHandler` (`@RestControllerAdvice`).

<br/>

## Build and Run (Java / Spring Boot)

### Prerequisites

- **Java 17+** (Eclipse Temurin 17 LTS or higher; baseline is the Java 17+ floor)
- **Apache Maven 3.8+** (or use the bundled Maven Wrapper `./mvnw` / `mvnw.cmd`)
- **AWS CLI v2**
- **Docker** (latest stable)
- **LocalStack Pro 4.14.0** + **LocalStack CLI** (for local AWS emulation)

### Build and run

```shell
# 1. Clone the repository
git clone <repository-url>
cd carddemo

# 2. Authenticate with AWS
aws configure
# or assume an IAM role appropriate to your environment

# 3. Build the application and run unit + integration tests
./mvnw clean install

# 4. Start the local development stack
#    (Spring Boot app, PostgreSQL, LocalStack Pro, Redis, Kafka)
docker-compose up

# 5. Run the application
./mvnw spring-boot:run
# or
java -jar target/*.jar
```

### LocalStack setup

The local development stack uses LocalStack Pro to emulate the AWS services the application
depends on (S3, SQS, Secrets Manager, KMS, etc.). The steps below come directly from the
environment setup expected by `docker-compose.yml` and `localstack/init/init-aws.sh`.

```shell
# Install AWS CLI (Python wrapper)
pip install awscli

# Pull LocalStack Pro Docker image
docker pull localstack/localstack-pro:latest

# Install LocalStack CLI 4.14.0 from the official GitHub release
curl --output localstack-cli-4.14.0-linux-amd64-onefile.tar.gz \
    --location https://github.com/localstack/localstack-cli/releases/download/v4.14.0/localstack-cli-4.14.0-linux-amd64-onefile.tar.gz
sudo tar xvzf localstack-cli-4.14.0-linux-amd64-onefile.tar.gz -C /usr/local/bin

# Authenticate with your LocalStack Pro token
localstack auth set-token ${LOCALSTACK_AUTH_TOKEN}

# Start LocalStack
localstack start

# Verify with an S3 bucket creation against the LocalStack endpoint
aws s3 mb s3://bucket1 --endpoint-url=http://localhost.localstack.cloud:4566
```

### Environment variables

The application reads non-sensitive configuration from environment variables and
**every credential or secret** from AWS Secrets Manager / AWS Systems Manager Parameter
Store (never from `application.yml`, never from plain environment variables). Only the
ARNs and endpoints below are exposed as environment variables.

| Variable                | Purpose                                                                |
| :---------------------- | :--------------------------------------------------------------------- |
| `AWS_REGION`            | AWS region for all SDK clients                                         |
| `ECS_CLUSTER_NAME`      | Target ECS cluster for service-to-service discovery and ops tooling    |
| `RDS_SECRET_ARN`        | Secrets Manager ARN holding the RDS PostgreSQL credentials             |
| `KMS_KEY_ARN`           | KMS CMK ARN used by RDS, S3, ElastiCache, and CloudWatch Logs          |
| `MSK_BOOTSTRAP_SERVERS` | MSK bootstrap server list (SASL_SSL with IAM auth)                     |
| `S3_OUTPUT_BUCKET`      | S3 bucket name receiving batch outputs (DALYREJS, SYSTRAN, TRANREPT, statements) |
| `OPENSEARCH_ENDPOINT`   | OpenSearch domain endpoint for transaction-log and CloudTrail indexing |

<br/>

## AWS Deployment

The deployment workflow produces a container image, pushes it to Amazon ECR, and performs
a zero-downtime rolling deployment behind the ALB by updating the ECS task definition
revision and the ECS service.

```shell
# 1. Build the container image
docker build -t carddemo:$(git rev-parse --short HEAD) .

# 2. Tag and push to Amazon ECR
aws ecr get-login-password --region ${AWS_REGION} \
    | docker login --username AWS --password-stdin \
        ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
docker tag carddemo:$(git rev-parse --short HEAD) \
    ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/carddemo:$(git rev-parse --short HEAD)
docker push \
    ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/carddemo:$(git rev-parse --short HEAD)

# 3. Register a new ECS task definition revision and update the service
#    (handled by .github/workflows/deploy.yml in CI/CD; manual command below)
aws ecs register-task-definition --cli-input-json file://task-definition.json
aws ecs update-service --cluster ${ECS_CLUSTER_NAME} --service carddemo \
    --task-definition carddemo
```

The canonical infrastructure definitions live under `infrastructure/terraform/` and
provision the full target footprint: ECS Fargate cluster + service, ALB + listeners +
target groups, RDS Multi-AZ + parameter groups + subnet groups, ElastiCache Redis cluster,
MSK cluster + topics, S3 buckets with SSE-KMS + lifecycle policies, AWS Batch compute
environment + job queue + job definitions, Step Functions state machines, Glue jobs,
KMS CMKs, Secrets Manager secrets + rotation Lambdas, WAF Web ACL + rules, Shield
Advanced subscription (optional), Macie session, CloudTrail organization-level trail,
OpenSearch domain, CloudWatch log groups + alarms, ECR repository, and the IAM roles +
policies for ECS tasks, AWS Batch jobs, Step Functions, and Lambda.

The CI/CD pipeline definitions live under `.github/workflows/`:

- `.github/workflows/build.yml` — `mvn clean install` + tests on every push/PR
- `.github/workflows/docker-build.yml` — `docker build` + push to ECR on merges to `main`
- `.github/workflows/deploy.yml` — ECS task definition update on tagged releases

<br/>

### CloudTrail Audit Bucket — Object Lock and MFA Delete Runbooks

The CloudTrail logs S3 bucket (`carddemo-${env}-cloudtrail-logs-${account_id}`) is
configured as the audit-of-record per AAP §0.6.6 and PCI-DSS Requirement 10. Two
defence-in-depth immutability controls layer on top of the standard versioning + KMS +
log file integrity validation + bucket policy posture:

1. **S3 Object Lock with COMPLIANCE retention** — provisioned automatically by Terraform
   via `aws_s3_bucket.cloudtrail_logs.object_lock_enabled = true` and
   `aws_s3_bucket_object_lock_configuration.cloudtrail_logs`. Retention defaults to 2557
   days (~7 years) matching `s3_lifecycle_expiration_days`. COMPLIANCE mode prevents
   delete/modify by any principal — including the root account — until retention
   elapses. Added in QA Checkpoint 9 Issue 2 remediation.
2. **S3 MFA Delete** — manual root-only enablement (not supported by Terraform / AWS
   SDK). Adds a final layer of defence specifically against root-account
   version-deletion attacks. Optional but recommended before production cutover per the
   Code Review CP7 audit finding.

#### CloudTrail Object Lock — Brownfield Migration

Object Lock can ONLY be enabled at bucket CREATION time per an AWS S3 API constraint.
Toggling `var.cloudtrail_s3_object_lock_enabled` to `true` against an existing
pre-Object-Lock bucket therefore forces Terraform to REPLACE the bucket. The migration
procedure for an existing production deployment is:

```shell
# 1. Create a sibling Object-Lock-enabled bucket via a temporary Terraform module
#    (or via aws s3api create-bucket --object-lock-enabled-for-bucket).
NEW_BUCKET="carddemo-prod-cloudtrail-logs-${AWS_ACCOUNT_ID}-v2"
aws s3api create-bucket \
    --bucket "${NEW_BUCKET}" \
    --region "${AWS_REGION}" \
    --create-bucket-configuration "LocationConstraint=${AWS_REGION}" \
    --object-lock-enabled-for-bucket

# 2. Apply the same versioning + SSE-KMS + BPA + bucket policy as the existing bucket.
aws s3api put-bucket-versioning --bucket "${NEW_BUCKET}" \
    --versioning-configuration Status=Enabled

# 3. Apply the Object Lock configuration (COMPLIANCE retention, 2557 days).
aws s3api put-object-lock-configuration --bucket "${NEW_BUCKET}" \
    --object-lock-configuration \
      'ObjectLockEnabled=Enabled,Rule={DefaultRetention={Mode=COMPLIANCE,Days=2557}}'

# 4. Replicate historical log objects to the new bucket via S3 Batch Replication
#    (or aws s3 sync for smaller volumes). Note that historical objects copied this
#    way will inherit the new retention from the moment of copy.
aws s3 sync s3://${OLD_BUCKET} s3://${NEW_BUCKET} --storage-class STANDARD_IA

# 5. Switch CloudTrail to deliver to the new bucket. Schedule this during an
#    audit-tolerant maintenance window — there is a brief gap in log delivery
#    between trail update and confirmation of resumed delivery.
aws cloudtrail update-trail --name carddemo-prod --s3-bucket-name "${NEW_BUCKET}"
aws cloudtrail get-trail-status --name carddemo-prod    # confirm LatestDeliveryTime advances

# 6. Update the Terraform configuration to reference ${NEW_BUCKET}, then
#    `terraform import` the new bucket into the aws_s3_bucket.cloudtrail_logs
#    resource address (replacing the old import). Run `terraform plan` to confirm
#    drift is resolved before `terraform apply`.

# 7. Retain the old bucket as a read-only archive until its lifecycle expires the
#    last historical object; then `aws s3 rb s3://${OLD_BUCKET} --force` to remove.
```

For greenfield deployments (`terraform apply` on a fresh account / environment), the
configuration is fully automated and no manual steps are required.

#### MFA Delete on CloudTrail Bucket

S3 MFA Delete cannot be enabled via Terraform or the AWS SDK; it requires an
`aws s3api put-bucket-versioning` call performed by the AWS account ROOT user with an
active hardware or virtual MFA token. The procedure is:

```shell
# Performed by the root user (NOT an IAM user / role) with an MFA token.
# The serial is the MFA device's ARN; the code is the current 6-digit OTP.
aws s3api put-bucket-versioning \
    --bucket "carddemo-prod-cloudtrail-logs-${AWS_ACCOUNT_ID}" \
    --versioning-configuration 'Status=Enabled,MFADelete=Enabled' \
    --mfa "arn:aws:iam::${AWS_ACCOUNT_ID}:mfa/root-account-mfa-device 123456"

# Verify:
aws s3api get-bucket-versioning \
    --bucket "carddemo-prod-cloudtrail-logs-${AWS_ACCOUNT_ID}"
# Expected: { "Status": "Enabled", "MFADelete": "Enabled" }
```

Once enabled, MFA Delete is required for any subsequent versioning state change AND for
any permanent (versioned) delete operation against the bucket — providing an additional
control against root-account compromise. Note that the Object Lock COMPLIANCE retention
already prevents version deletion within the retention window; MFA Delete defends
against attempts to delete versions AFTER their retention has expired (e.g., during the
lifecycle window between retention expiry and lifecycle expiration).

<br/>

## Testing and Validation

The Java target is validated end-to-end with the following layered testing strategy.
All tests are required to pass before the migration is considered complete.

- **Unit tests** — JUnit 5 + Mockito for every service method, with particular focus on
  `BigDecimal` arithmetic logic (interest calculation, balance updates, transaction
  amount handling). Every monetary computation is verified against its COBOL source for
  bit-identical results within `RoundingMode.HALF_EVEN`.
- **Integration tests** — `@WebMvcTest` slice tests for controllers, `@DataJpaTest` +
  Testcontainers PostgreSQL for repositories, and end-to-end tests against
  LocalStack-backed AWS services.
- **Spring Batch + AWS Batch test harness** — `JobLauncherTestUtils` validates each
  batch job (`DailyTransactionPostingJob`, `InterestCalculationJob`,
  `CombineTransactionsJob`, `StatementGenerationJob`, `TransactionReportJob`) against
  the same inputs as the corresponding JCL job.
- **Golden output diff tests** — `GoldenOutputDiffTest` compares Java output against the
  canonical COBOL golden output files (`app/data/ASCII/*.txt`, copied verbatim under
  `src/test/resources/golden/`) for identical inputs. Any byte-level divergence fails the
  test, enforcing the regulatory output format constraint.
- **LocalStack for local AWS service mocking** — S3, SQS, Secrets Manager, KMS, and
  related services are emulated locally so the test suite runs without an AWS account.
- **Parallel-run period** — before cutover, the COBOL and Java systems run simultaneously
  against the same inputs and their outputs are diffed. The 9 ASCII fixture files under
  `app/data/ASCII/` serve as the canonical golden test data for this period.

<br/>

## Documentation

Additional documentation lives under `docs/` and `diagrams/`:

- [`docs/index.md`](docs/index.md) — documentation landing page
- [`docs/project-guide.md`](docs/project-guide.md) — project status and operational guidance
- [`docs/technical-specifications.md`](docs/technical-specifications.md) — full target stack
  technical contract (Spring Boot, AWS services, transformation rules, exception hierarchy,
  testing approach)
- [`diagrams/CARDDEMO-DataModel.drawio`](diagrams/CARDDEMO-DataModel.drawio) — data-model
  diagram (extended with the RDS PostgreSQL schema)
- [`diagrams/target-architecture.drawio`](diagrams/target-architecture.drawio) — AWS-native
  target architecture diagram

The site is buildable with MkDocs (`mkdocs serve` for local preview, `mkdocs build` for
the static site) using the configuration in `mkdocs.yml`.

<br/>

## Application Details
The CardDemo is a Credit Card management application, built primarily using COBOL programming language. The application has various functions that allows users to manage Account, Credit card, Transaction and Bill payment.

There are 2 types of users:
* Regular User
* Admin User

The Regular user can perform the user functions and the Admin users can only perform Admin functions.

<br/>

### User Functions

![Alt text](./diagrams/Application-Flow-User.png?raw=true "User Flow")

<br/>

### Admin Functions

![Alt text](./diagrams/Application-Flow-Admin.png?raw=true "Admin Flow")

<br/>

### Application Inventory

#### **Online**

| Transaction |      | BMS Map | Program  | Function            |
| :---------- | :--- | :------ | :------- | :------------------ |
| CC00        |      | COSGN00 | COSGN00C | Signon Screen       |
| CM00        |      | COMEN01 | COMEN01C | Main Menu           |
|             | CAVW | COACTVW | COACTVWC | Account View        |
|             | CAUP | COACTUP | COACTUPC | Account Update      |
|             | CCLI | COCRDLI | COCRDLIC | Credit Card List    |
|             | CCDL | COCRDSL | COCRDSLC | Credit Card View    |
|             | CCUP | COCRDUP | COCRDUPC | Credit Card Update  |
|             | CT00 | COTRN00 | COTRN00C | Transaction List    |
|             | CT01 | COTRN01 | COTRN01C | Transaction View    |
|             | CT02 | COTRN02 | COTRN02C | Transaction Add     |
|             | CR00 | CORPT00 | CORPT00C | Transaction Reports |
|             | CB00 | COBIL00 | COBIL00C | Bill Payment        |
| CA00        |      | COADM01 | COADM01C | Admin Menu          |
|             | CU00 | COUSR00 | COUSR00C | List Users          |
|             | CU01 | COUSR01 | COUSR01C | Add User            |
|             | CU02 | COUSR02 | COUSR02C | Update User         |
|             | CU03 | COUSR03 | COUSR03C | Delete User         |

#### **Batch**

| Job      | Program  | Function                                   |
| :------- | :------- | :----------------------------------------- |
| DUSRSECJ | IEBGENER | Initial Load of User security file         |
| DEFGDGB  | IDCAMS   | Setup GDG Bases                            |
| ACCTFILE | IDCAMS   | Refresh Account Master                     |
| CARDFILE | IDCAMS   | Refresh Card Master                        |
| CUSTFILE | IDCAMS   | Refresh Customer Master                    |
| DISCGRP  | IDCAMS   | Load Disclosure Group File                 |
| TRANFILE | IDCAMS   | Load Transaction Master file               |
| TRANCATG | IDCAMS   | Load Transaction category types            |
| TRANTYPE | IDCAMS   | Load Transaction type file                 |
| XREFFILE | IDCAMS   | Account, Card and Customer cross reference |
| CLOSEFIL | IEFBR14  | Close VSAM files in CICS                   |
| TCATBALF | IDCAMS   | Refresh Transaction Category Balance       |
| TRANBKP  | IDCAMS   | Refresh Transaction Master                 |
| POSTTRAN | CBTRN02C | Transaction processing job                 |
| TRANIDX  | IDCAMS   | Define AIX for transaction file            |
| OPENFIL  | IEFBR14  | Open files in CICS                         |
| INTCALC  | CBACT04C | Run interest calculations                  |
| COMBTRAN | SORT     | Combine transaction files                  |
| CREASTMT | CBSTM03A | Produce transaction statement              |

<br/>

### Application Screens

#### **Signon Screen**

![Alt text](./diagrams/Signon-Screen.png?raw=true "Signon Screen")


#### **Main Menu**

![Alt text](./diagrams/Main-Menu.png?raw=true "Main Menu")

#### **Admin Menu**

![Alt text](./diagrams/Admin-Menu.png?raw=true "Admin Menu")

<br/>

## Support

If you have questions or requests for improvement please raise an issue in the repository.

<br/>

## Roadmap

The following features are planned for upcoming releases

1. More database types

   1. Relational Database usage : Db2

   2. Hierachical database calls : IMS

2. Integration

   * ftp, sftp

   * Message queue integration

   * Exposure of transactions for distributed application integration

<br/>

## Contributing

We are looking forward to receiving contributions and enhancements to this initial codebase from the mainframe code base

Feel free to raise issues, create code and raise merge requests for enhancements so that we can build out this application as a resource for programmers wanting to understand and modernize their mainframes.

<br/>

## License

This is intended to be a community resource and it is released under the Apache 2.0 license.

<br/>

## Project status

This repository is in the middle of an active **COBOL → Java 17+ / Spring Boot 3.x / AWS-native
migration**. The preserved COBOL source under `app/` continues to serve as the authoritative
behavioral-parity reference; the modernized Java target under `src/`, `pom.xml`, and
`infrastructure/` is being delivered in parallel.

**Demo target: May 20, 2026.** All artifacts required for an end-to-end demo — deployed to
an AWS account, exercised via REST and a Step Functions batch run, monitored via CloudWatch
and OpenSearch — are scheduled to be in place by that date.

See [`docs/project-guide.md`](docs/project-guide.md) for the latest delivery state, sprint
status, and operational runbook.

<br/>


