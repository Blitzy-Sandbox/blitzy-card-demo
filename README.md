## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-card-demo-application)
- [Description](#description)
- [Technologies used](#technologies-used)
- [Installation on the mainframe](#installation-on-the-mainframe)
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
1. COBOL
2. CICS
3. VSAM
4. JCL
5. RACF

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
     - Enter userid ADMIN001 and the initially configured password PASSWORD to manage users
     - Enter userid USER0001 and the initially configured password PASSWORD to access back office functions
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

We are planning a v2 of this application in Q1 2023.

Watch this space for updates

<br/>


## Java 25 migration target (in place, additive)

Everything above this line documents the mainframe application and stays the reference of
record. This section documents the Java target that was added **beside** it, in this same
repository: what it is, how to build, run and test it, how it is configured, and what has and
has not yet been proven.

The migration is **purely additive**. No file under `app/` is edited, moved, renamed,
reformatted or deleted, because that tree plays three roles at once: it is the parity oracle the
Java output is compared against, the field-contract source the entity and DTO shapes are derived
from, and the traceability anchor cited by commit SHA. It loses all three the moment it is
edited. The legacy inventories above are therefore still authoritative and are deliberately left
byte-for-byte intact.

- [Target architecture](#target-architecture)
- [Prerequisites and the recorded build environment](#prerequisites-and-the-recorded-build-environment)
- [Security and configuration](#security-and-configuration)
- [Build, run and verify](#build-run-and-verify)
- [Runtime topology and tests](#runtime-topology-and-tests)
- [Validation gates](#validation-gates)
- [Documentation and traceability](#documentation-and-traceability)
- [Troubleshooting and the contribution boundary](#troubleshooting-and-the-contribution-boundary)

<br/>

### Target architecture

The Java tree lives at `src/` in **this** repository, in Maven standard layout, directly
alongside the frozen `app/` tree. There is no separate repository and no wrapper sub-directory:
`pom.xml` sits at the repository root next to the existing `app/`, `docs/`, `diagrams/` and
`samples/` directories, and no COBOL file is ever copied into `src/`.

| Dimension | Value |
| :-------- | :---- |
| Package root | `com.cardemo` — one spelling, used uniformly in main sources, test sources, configuration and documentation |
| Maven coordinates | `com.cardemo:carddemo:1.0.0`, packaging `jar` |
| Language runtime | Java 25 LTS (`maven.compiler.release` is `25`; no preview features are enabled) |
| Framework | Spring Boot 3.5.11 (`spring-boot-starter-parent`) |
| Build tool | Apache Maven 3.9.11, pinned by the wrapper in `.mvn/wrapper/maven-wrapper.properties` |
| Database | PostgreSQL 16, schema owned by three Flyway migrations |
| Cloud services | S3, SQS FIFO and SNS through Spring Cloud AWS 3.3.0, against LocalStack only |
| Deployment shape | **One deployable JAR — a modular monolith, explicitly not microservices** |

The monolith is a deliberate constraint rather than a convenience. `EXEC CICS SYNCPOINT
ROLLBACK` in `COACTUPC` spans an account write and a customer write inside one unit of work, and
the daily posting job in `CBTRN02C` commits a transaction-category-balance upsert, an account
update and a transaction insert together. Splitting those writes across services would require
compensating transactions and would change failure semantics, which is a behaviour change and so
is out of scope.

**What is frozen, and what is outside the Java build**

- `app/**` is read-only reference material: 28 COBOL programs (19,254 lines), 28 copybooks, 17
  BMS mapsets with their 17 generated symbolic maps, 29 JCL members, 2 PROC members, 1 CTL
  member, the CICS CSD, the VSAM catalogue listing and the data fixtures. The traceability
  anchor is commit `7756d89`.
- `samples/**` — the three z/OS compile templates, the three build procedures and the two binary
  emulator runtime archives — has no Java analogue. `pom.xml` supersedes the set conceptually and
  nothing is ported from it.
- `app/data/EBCDIC/**` is retained as byte-level codepage reference only. It is never parsed by
  the build and no transcoding utility is provided. The ASCII fixtures under `app/data/ASCII/`
  are the authoritative seed and test input.

**Runtime substitutions**

| Legacy construct | Java target |
| :--------------- | :---------- |
| 3270 / BMS screens, pseudo-conversational COMMAREA, `EXEC CICS XCTL` | Stateless HTTP with JSON payloads; a correlation-id filter then a JWT filter ahead of role-based authorisation. No server-side session state |
| VSAM KSDS clusters, alternate indexes and paths | 11 JPA entities over 11 Spring Data repositories against PostgreSQL 16; three alternate indexes become three finder methods backed by B-tree indexes |
| IDCAMS `DEFINE CLUSTER` provisioning jobs | `V1__create_schema.sql` and `V2__create_indexes.sql` |
| The nine ASCII fixtures and the inline user records | `V3__seed_data.sql`, decoding zoned-decimal overpunch signs position-aware from the PIC clauses |
| JES2 and the JCL job stream, `COND` gating, DFSORT, IDCAMS `REPRO` | Spring Batch jobs, steps and flows; a `JobExecutionDecider` for return-code gating; `Comparator` instances for sorts; batched JDBC for bulk loads. No external sort process is spawned |
| GDG generations `(+1)` / `(0)` | S3 keys under a timestamp or job-instance prefix over a versioned bucket, with record lengths preserved byte-exactly |
| `DEFINE TDQUEUE(JOBS)` and the internal reader | An SQS FIFO queue; the 80-byte fixed parameter record becomes a typed JSON message |
| `FILE STATUS` values and `DFHRESP` codes | A typed exception hierarchy applied on every I/O path, never swallowed |
| `CALL 'CSUTLDTC'` / `CEEDAYS` | `java.time` plus a dedicated date validation service that reproduces the accept and reject outcomes, not merely the parsing |
| Plaintext `SEC-USR-PWD` | BCrypt at strength 10 |
| `DISPLAY` to SYSOUT — the only instrumentation in the corpus | Structured JSON logging with trace, span and correlation identifiers; Micrometer metrics; distributed tracing; composite health |

**The 17 operations**

The CICS CSD defines 18 transactions and 18 programs but only 17 mapsets. Seventeen
transaction-to-program pairs have both source and a mapset, and those seventeen — and only those
seventeen — became endpoints:

| Method | Path | Legacy transaction | Legacy program |
| :----- | :--- | :----------------- | :------------- |
| `POST` | `/api/auth/signon` | `CC00` | `COSGN00C` |
| `GET` | `/api/menu/main` | `CM00` | `COMEN01C` |
| `GET` | `/api/menu/admin` | `CA00` | `COADM01C` |
| `GET` | `/api/accounts/{accountId}` | `CAVW` | `COACTVWC` |
| `PUT` | `/api/accounts` | `CAUP` | `COACTUPC` |
| `GET` | `/api/cards` | `CCLI` | `COCRDLIC` |
| `GET` | `/api/cards/detail` | `CCDL` | `COCRDSLC` |
| `PUT` | `/api/cards` | `CCUP` | `COCRDUPC` |
| `GET` | `/api/transactions` | `CT00` | `COTRN00C` |
| `GET` | `/api/transactions/detail` | `CT01` | `COTRN01C` |
| `POST` | `/api/transactions` | `CT02` | `COTRN02C` |
| `POST` | `/api/billing/payments` | `CB00` | `COBIL00C` |
| `POST` | `/api/reports` | `CR00` | `CORPT00C` |
| `GET` | `/api/admin/users` | `CU00` | `COUSR00C` |
| `POST` | `/api/admin/users` | `CU01` | `COUSR01C` |
| `PUT` | `/api/admin/users/{userId}` | `CU02` | `COUSR02C` |
| `DELETE` | `/api/admin/users/{userId}` | `CU03` | `COUSR03C` |

The eighteenth CSD entry is transaction `CDV1`, whose program `COCRDSEC` **has no source file
anywhere in this repository** — the only occurrence of the name is the CSD definition itself. It
is a dangling legacy definition, so no endpoint was invented for it. Re-derive with
`grep -rl COCRDSEC app/`.

Also deliberately absent, and not oversights:

- No 3270 or BMS terminal emulation, no green-screen rendering and no pseudo-conversational
  session emulation. The 460 input fields across the 17 symbolic maps are consumed as **DTO field
  contracts**, not reimplemented as a user interface.
- No single-page application, no generated front end and no component library. The interface is
  REST plus JSON, and Actuator.
- No generated OpenAPI document. `docs/api-contracts.md` is the manual substitute and is the
  endpoint contract of record.
- No microservice decomposition, event sourcing or CQRS. No Kubernetes, Helm or service mesh —
  container orchestration stops at Docker Compose.
- No live AWS account and no real credentials on any code path.

<br/>

### Prerequisites and the recorded build environment

| Requirement | Version | Why exactly this |
| :---------- | :------ | :--------------- |
| JDK | 25 | `maven.compiler.release` is `25` and the Maven Enforcer plugin asserts a floor of `[25,)`, so the build refuses to run on an older toolchain rather than silently producing different bytecode |
| Apache Maven | 3.9.11 | Pinned by `.mvn/wrapper/maven-wrapper.properties` and asserted by Enforcer as `[3.9.11,)`. Use `./mvnw`, which downloads exactly that version — a pre-installed Maven is not required |
| Docker Engine with Compose v2 or later | any working daemon | Needed for `docker compose` and for the Testcontainers-backed test tiers. Confirm the daemon is reachable with `docker info` before building |

Nothing else is required on the host. The database, the cloud-service emulator and the whole
observability stack are provided by `docker-compose.yml`, and every image there is pinned to both
a tag and a digest so a build is reproducible rather than dependent on when it ran.

**Recorded environment observation — Thursday, July 30, 2026.** On that date, on the host where
this section was authored:

- **Docker Engine 29.6.2 and `docker compose` v5.3.1 were present and worked.**
- The host had **no `java`, no `javac` and no `mvn`** on `PATH`.
- Because of that, **host Maven validation of this build was unavailable on that host**, while a
  build inside a **pinned Java 25 / Maven 3.9.11 container was feasible**. The container path in
  [Build, run and verify](#build-run-and-verify) is the remediation, and it reuses the same image
  the `Dockerfile` build stage already pins.
- The LocalStack and AWS command-line tools were also absent from the host. Neither is required
  for any workflow documented here: the compose stack supplies LocalStack, and the application
  reaches it through the AWS SDK rather than through a CLI.

Read that as a dated observation about one host, not as a property of the project, and note which
component was missing: the container runtime was **not** the gap. Docker Engine and `docker compose`
were the parts that worked there; the JDK and Maven were the parts absent from the host. On any host
that has JDK 25, `./mvnw` supplies Maven itself and no container is needed at all.

<br/>

### Security and configuration

**Nothing in this repository contains a usable credential.** `.env.example` is a template of
variable **names with empty or non-sensitive placeholder values** — `JWT_SIGNING_KEY`,
`POSTGRES_PASSWORD`, `CARDDEMO_DB_APP_PASSWORD`, `CARDDEMO_DB_MIGRATION_PASSWORD`,
`GRAFANA_ADMIN_PASSWORD`, `METRICS_SCRAPE_PASSWORD` and `NVD_API_KEY` are all present as names
and all left empty. Copy it to `.env`, fill it in locally, and never commit the result; `.env` is
listed in `.gitignore` for exactly that reason.

**The JWT signing key is environment-indirected with no committed default.** The base profile
resolves it as `${JWT_SIGNING_KEY}` with no fallback value, so an absent key is a **startup
failure**, not a silent fall back to a well-known secret. That fail-fast behaviour is the point:
a default would be a shipped credential.

**Passwords exist only as BCrypt hashes.** The legacy security file stores an 8-character
plaintext password field; the Java target stores a 60-character BCrypt hash column instead.
Strength 10 is *enforced* rather than merely defaulted — the security configuration validates the
configured strength and rejects any other value. Password material is never returned by any
endpoint and never written to a log.

| Profile | File | Intended use | Cloud services | Demo user seeding |
| :------ | :--- | :----------- | :------------- | :---------------- |
| base | `src/main/resources/application.yml` | Shared defaults inherited by every profile. Carries the secret indirection, never a secret | Endpoint is indirected, with no default | Off |
| `local` | `application-local.yml` | Developer workstation against the compose stack | **LocalStack only** | On |
| `test` | `application-test.yml` | Automated tests against Testcontainers | **LocalStack only** | On |
| `prod` | `application-prod.yml` | Deployment. Every secret externalised, least privilege, no development conveniences | **No emulator endpoint and no live credentials are configured** | Off |

The `local` and `test` profiles are the only ones that seed the demo users, and they are also the
only ones that talk to an emulator. `application-prod.yml` contains **no** reference to LocalStack
at all, so there is no path from an open gate in a development profile to a deployed system —
a deployed system reads a different profile.

**Log masking.** `src/main/resources/logback-spring.xml` emits structured JSON and carries
`traceId`, `spanId`, `correlationId` and `jobInstanceId` through the mapped diagnostic context, so
a batch run and an HTTP request are both correlatable end to end. Before anything reaches an
appender it passes through a masking layer that redacts BCrypt hashes, social security numbers,
JWTs, cloud access keys, card numbers, card verification values, generic `password`-shaped values
and other personally identifiable fields, each replaced by an explicit marker such as
`[REDACTED_BCRYPT_HASH]` or `[REDACTED_SSN]`. The rules are identical across profiles, so a value
that is masked locally is masked in production.

<br/>

### Build, run and verify

**Build and test on a host that has JDK 25**

```shell
./mvnw clean verify
```

```shell
mvnw.cmd clean verify
```

Use the wrapper rather than a system `mvn`. The wrapper is what pins Maven 3.9.11, and
`.mvn/jvm.config` is what passes `--sun-misc-unsafe-memory-access=allow` to the build JVM so that
JDK 25 does not emit `sun.misc.Unsafe` deprecation warnings from inside the build tool itself
against a tree that is otherwise warning-free.

**Build in a pinned container instead** — the remediation when the host has no JDK or no Maven.
This is the same image the `Dockerfile` build stage pins, tag *and* digest, so it is a verifiable
reference rather than a floating one:

```shell
docker run --rm \
  -v "$PWD":/workspace -w /workspace \
  -v "$HOME/.m2":/root/.m2 \
  maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c \
  mvn -B -ntp clean verify
```

That container already carries Maven 3.9.11 on JDK 25, so `mvn` is invoked directly. The
Testcontainers-backed tiers additionally need a reachable Docker daemon from *inside* the
container; add `-v /var/run/docker.sock:/var/run/docker.sock` when you want those tiers to run,
and expect them to be skipped or to fail fast without it.

**What `verify` enforces.** Every item below fails the build rather than warning:

1. **Warnings are errors.** The compiler runs with `-Xlint:all -Werror` and `failOnWarning`, so a
   single warning stops the build. A Javadoc gate runs `doclint` at `all` with `failOnWarnings`,
   so undocumented public surface fails too.
2. **Toolchain floor.** Enforcer asserts Java `[25,)` and Maven `[3.9.11,)`, and rejects
   non-release dependency versions.
3. **Tests.** Surefire runs the unit tier; Failsafe runs the integration and end-to-end tiers.
4. **Coverage.** JaCoCo enforces a **minimum line coverage ratio of 0.80** and fails below it.
5. **Vulnerabilities.** OWASP dependency-check is bound to `verify` and fails the build on any
   finding at **CVSS 7.0 or above — that is, zero High and zero Critical**.

For fast local iteration the vulnerability scan can be skipped, which is exactly what the
repository's own `verify` CI job does, with a separate `security-scan` job running the scan on its
own schedule:

```shell
./mvnw -B -ntp -Ddependency-check.skip=true clean verify
```

Treat that as an iteration shortcut, not as a pass: a complete `verify` includes the scan.

**Bring up the full topology**

```shell
cp .env.example .env      # then fill in the empty values locally; never commit .env
docker compose up --build
```

```shell
docker compose up -d --wait   # detached, returns once every healthcheck reports healthy
```

**Check health and logs**

```shell
docker compose ps
curl -fsS http://localhost:8080/actuator/health/readiness
curl -fsS http://localhost:8080/actuator/health/liveness
docker compose logs --since 5m app
docker compose logs -f app
```

Only `health`, `info` and `prometheus` are exposed on Actuator, so any other endpoint answering
`404` is the intended configuration rather than a fault. The readiness group covers the
application state plus the database, object storage and queue; the liveness group covers the
application state alone.

**Tear down, including volumes**

```shell
docker compose down -v
```

Dropping the volumes is what makes the next start-up re-run all three Flyway migrations against
an empty database, which is the reliable way to recover from a failed or half-applied migration.

**Run the packaged JAR under the `local` profile**

Read the signing key without echoing it, and let only the JVM see it:

```shell
read -rsp 'JWT_SIGNING_KEY: ' JWT_SIGNING_KEY && export JWT_SIGNING_KEY && printf '\n'
```

```shell
SPRING_PROFILES_ACTIVE=local \
POSTGRES_HOST=localhost \
AWS_ENDPOINT_URL=http://localhost:4566 \
java --sun-misc-unsafe-memory-access=allow -jar target/carddemo-1.0.0.jar
```

Never pass a secret on a command line that a shell will record, never `echo` it back, and never
place it in a file that is tracked. The container image sets the same JVM flag through
`JAVA_TOOL_OPTIONS`, so the flag is only needed for a direct `java -jar` invocation.

**Testcontainers 2.0.3 — the one dependency detail that breaks a build if taken at face value**

The Testcontainers 2.x line renamed every module artifact, and Spring Boot 3.5.11 independently
manages a version from the 1.x line. Two things are therefore required together, and applying
only one of them still fails:

- **Pin the version with a property, not a second bill of materials.** `pom.xml` sets
  `<testcontainers.version>2.0.3</testcontainers.version>`, which overrides the version the
  parent manages. Importing a second Testcontainers bill of materials alongside the one already
  imported produces an ordering-dependent resolution that can silently select the managed 1.x
  version instead.
- **Use prefixed module coordinates only.** `testcontainers`, `testcontainers-postgresql`,
  `testcontainers-localstack` and `testcontainers-junit-jupiter` resolve at 2.0.3. The bare 1.x
  names — `postgresql`, `localstack`, `junit-jupiter` under the same group — **do not exist** at
  that version and fail to resolve outright.

Overriding the version without renaming the artifacts resolves non-existent modules; renaming the
artifacts without overriding the version resolves the wrong one.

<br/>

### Runtime topology and tests

`docker compose up` starts six services. Every image is pinned to a tag **and** a digest; a
floating or unpinned image reference must never be introduced, because it would make the stack
depend on when it was started.

| Service | Image | Host port | Role |
| :------ | :---- | --------: | :--- |
| `app` | built from `Dockerfile` | 8080 | The single CardDemo JAR — REST surface, batch jobs and Actuator |
| `postgres` | `postgres:16.14-alpine` | 5432 | The VSAM replacement. Flyway applies `V1`, `V2` and `V3` on start-up |
| `localstack` | `localstack/localstack:4.14.0` | 4566 | S3, SQS FIFO and SNS. `localstack-init/init-aws.sh` provisions the buckets, the FIFO queue and the topic |
| `jaeger` | `jaegertracing/jaeger:2.20.0` | 16686 UI, 4318 OTLP/HTTP | Trace collection and search |
| `prometheus` | `prom/prometheus:v3.13.2` | 9090 | Scrapes `/actuator/prometheus` |
| `grafana` | `grafana/grafana:12.4.6` | 3000 | Dashboards, provisioned from `observability/grafana/` |

Published ports bind to `127.0.0.1` by default rather than to every interface. Parallel checkouts
that need their own stack should export `CLONE_INDEX` together with the per-port overrides
documented in `docker-compose.yml`, so that two stacks do not contend for the same ports.

**Authentication and authorisation.** `POST /api/auth/signon` upper-cases both the identifier and
the password exactly as `COSGN00C` does, verifies the BCrypt hash, and returns a token instead of
populating a COMMAREA. The legacy `CDEMO-USER-TYPE` values `'A'` and `'U'` become the **ADMIN** and
**USER** roles. `/api/admin/**` requires ADMIN. Session creation policy is **stateless** on every
chain — no session is created and none is consulted, so pagination state travels in request
parameters and response metadata rather than in server memory.

Demo users are seeded **only** under the `local` and `test` profiles. Their source is the inline
`SYSUT1 DD *` data in `app/jcl/DUSRSECJ.jcl` — ten records in the 80-byte `CSUSR01Y` layout, five
administrators and five standard users. There is no standalone ASCII fixture for them. The seed
migration stores those passwords **only** as BCrypt hashes; the plaintext value in the JCL is
never persisted.

**Batch.** Five jobs correspond to the JCL members that carry logic, plus an orchestrator:

| Job | Source |
| :-- | :----- |
| `POSTTRAN` | `app/jcl/POSTTRAN.jcl` + `CBTRN02C`, with the read-only `CBTRN01C` folded in as a labelled pre-flight step |
| `INTCALC` | `app/jcl/INTCALC.jcl` + `CBACT04C` |
| `COMBTRAN` | `app/jcl/COMBTRAN.jcl` — sort and load control cards only, **no COBOL program exists for it** |
| `CREASTMT` | `app/jcl/CREASTMT.JCL` (note the uppercase extension) + `CBSTM03A` + `CBSTM03B` |
| `TRANREPT` | `app/jcl/TRANREPT.jcl` + `app/proc/TRANREPT.prc` + `CBTRN03C` |
| `CARDDEMO-PIPELINE` | The orchestrator: `POSTTRAN` then `INTCALC` then `COMBTRAN`, then `CREASTMT` and `TRANREPT` as parallel branches of a split |

Jobs **do not auto-launch on start-up** — `spring.batch.job.enabled` is `false` deliberately,
because the framework default would run every job on every boot. Launching is explicit, through
the orchestrator.

`POST /api/reports` publishes a report-job message to the FIFO queue, which is the direct
replacement for `EXEC CICS WRITEQ TD QUEUE('JOBS')` in `CORPT00C`. Be aware of the current limit:
the consumer that replaces the JES2 internal reader is **not yet wired** — there is no
`@SqsListener` anywhere in `src/main/java`, so a published message is not drained and does not by
itself start a job. See
[Troubleshooting and the contribution boundary](#troubleshooting-and-the-contribution-boundary).

**What the test tiers are expected to cover.** These are the intended shape of the suite, stated
so a gap is visible; they are **not** a report of a passing run:

- `src/test/java/com/cardemo/unit/service/**` — at least one class per service bean, so at least
  **21** classes, asserting paragraph-level behaviour against the cited COBOL locators.
- `src/test/java/com/cardemo/unit/batch/**` — at least one class per batch processor, so at least
  **5**, including the assertion that reject code 103 overwrites 102 when both the over-limit and
  the expiry check fail.
- `src/test/java/com/cardemo/integration/repository/**` — at least one class per repository, so at
  least **11**, against a **Testcontainers PostgreSQL 16** instance.
- `src/test/java/com/cardemo/integration/aws/**` — S3 and SQS behaviour against a
  **Testcontainers LocalStack** instance.
- `src/test/java/com/cardemo/e2e/**` — the batch pipeline driven with the **300-record**
  `app/data/ASCII/dailytran.txt` fixture (105,300 bytes, 350 bytes per record, copied byte-identically
  to `src/test/resources/dailytran.txt`), and the online surface exercised from sign-on through
  transaction add across **all 17 operations**.
- Coverage: JaCoCo's **0.80 line-coverage minimum** is enforced by the build. Treat it as the
  threshold the build imposes, not as a measured result — a coverage figure is only meaningful
  once `verify` has actually run in your environment and produced
  `target/site/jacoco/index.html`.

Note that the fixture is spelled `dailytran.txt`, with the word in full, even though the mainframe
DD name and dataset are `DALYTRAN`. A path built from the DD name will not resolve.

<br/>

### Validation gates

Eight gates define acceptance for the migration. Their definitions, evidence and results live in
[`docs/validation-gates.md`](docs/validation-gates.md).

**Every gate below currently reads `Not available`.** A gate keeps that status until its evidence
has actually been produced in the environment doing the checking; a result is never asserted from
intent. Where a blocker is known, it is named precisely along with its remediation, rather than
being generalised.

| Gate | What it asserts | Evidence artifact | Status |
| ---: | :-------------- | :---------------- | :----- |
| 1 | End-to-end boundary parity: the 300-record `app/data/ASCII/dailytran.txt` fixture driven through `POSTTRAN`, compared field by field against the legacy baseline | [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — pending container execution. Additionally requires an expected-output baseline for the comparison, and none is present under `src/test/resources` |
| 2 | Zero-warning build: a clean `verify` with warnings escalated to errors, exiting zero, plus a vulnerability scan reporting no Critical or High finding | [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — **host execution was blocked as of Thursday, July 30, 2026** by the absence of `java`, `javac` and `mvn` on that host. Remediation: run the build in the pinned Java 25 / Maven 3.9.11 container shown in [Build, run and verify](#build-run-and-verify), or install JDK 25 and use `./mvnw` |
| 3 | Performance **baseline**: throughput in records per second, per-endpoint p95 latency, and peak heap | [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — no measurement recorded. This gate captures a **measured baseline, not a target**: the COBOL publishes no service-level objective, so no threshold may be invented for it |
| 4 | Named fixture validation: all nine ASCII fixtures loaded through `V3__seed_data.sql` and driven through the pipeline, including zoned-decimal overpunch decode assertions and the ten seeded users | [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — pending container execution |
| 5 | API contract verification: every one of the **17** operations exercised by integration tests against a real application context | [`docs/api-contracts.md`](docs/api-contracts.md), [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — pending container execution |
| 6 | Security audit: no floating-point type in any financial field, every password stored only as a BCrypt hash, no literal secret anywhere | [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — evidence not yet generated |
| 7 | Scope coverage: all **28** COBOL programs mapped, with the traceability matrix demonstrating complete paragraph coverage | [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md), [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — evidence not yet generated |
| 8 | Integration sign-off: the full compose stack up with health reporting `UP`, and all three Flyway migrations applying cleanly | [`docs/validation-gates.md`](docs/validation-gates.md) | `Not available` — pending container execution |

Two distinctions in that table matter, because collapsing either one produces a misleading
picture:

- **Gates 2, 3, 6 and 7 need no container at all.** Gate 2's blocker on the recorded host was
  specifically the missing JDK and Maven, and it has a stated container remediation. Gates 6 and 7
  are static analyses over the source tree and the traceability matrix.
- **Gates 1, 4, 5 and 8 are pending container execution, which is not a Docker blocker.** On the
  host recorded above, Docker Engine and `docker compose` were present and working; the JDK and
  Maven were the components missing there. These four gates have simply not been run yet, and they
  need a reachable Docker daemon at the point when they are.

<br/>

### Documentation and traceability

| Document | What it gives you |
| :------- | :---------------- |
| [`docs/api-contracts.md`](docs/api-contracts.md) | The endpoint contract of record for all 17 operations: request and response shapes, field lengths taken from the BMS symbolic maps, status codes and error modes. This is the **manual substitute** for a generated API specification, which is out of scope — so do not look for, or link, a generated OpenAPI document |
| [`docs/architecture-before-after.md`](docs/architecture-before-after.md) | Side-by-side legacy and target architecture, drawn from `diagrams/**` and `app/catlg/LISTCAT.txt` |
| [`docs/onboarding-guide.md`](docs/onboarding-guide.md) | Developer setup and troubleshooting, consistent with `CONTRIBUTING.md` |
| [`docs/validation-gates.md`](docs/validation-gates.md) | The eight gate definitions, the evidence each produces, their results, the container prerequisite, and the residual-risk register for deferred hardening |
| [`docs/executive-presentation.html`](docs/executive-presentation.html) | A static stakeholder summary of scope, evidence and residual risk. It is a document, not an application interface |
| [`DECISION_LOG.md`](DECISION_LOG.md) | Every mechanism substitution and every deliberately preserved legacy quirk, each citing its COBOL locator — including why three known legacy defects were kept rather than repaired, and which deviations from parity were chosen on purpose |
| [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md) | Paragraph-level mapping from all **28** COBOL programs — **19,254 lines** — to their Java methods, keyed to commit `7756d89` |

Traceability is paragraph-by-paragraph, not module-by-module: each COBOL paragraph or section
becomes one private Java method, with no consolidation across paragraphs, and each method carries a
Javadoc citation naming its source paragraph. That is what makes Gate 7 provable by inspection
rather than by assertion, and it is why some code that looks redundant is retained and justified in
the decision log instead of deleted.

Two documents in that table have no COBOL antecedent and are new capability rather than
translation: the validation-gate record and the executive summary. The rest derive from the frozen
corpus, and the specific artifacts each one derives from are named inside it.

Each row above states what the document contains, which is its contract — not a claim that the file
is already in your checkout. The documentation artifacts land as the migration progresses, so in an
earlier checkout some of them are legitimately **`Not available`**, and the prerequisite for each is
simply that the documentation stage has been applied to the tree you are reading. Confirm which are
present before relying on a link:

```shell
ls -1 DECISION_LOG.md TRACEABILITY_MATRIX.md \
      docs/api-contracts.md docs/architecture-before-after.md \
      docs/onboarding-guide.md docs/validation-gates.md \
      docs/executive-presentation.html
```

`docs/project-guide.md` predates this section and records an earlier attempt at the migration. It
is retained as historical evidence and is **not authoritative** — where it disagrees with this
README, with `docs/technical-specifications.md` or with the COBOL corpus, it is stale. Several
defects it reports are closed by the current tree, including the absence of continuous integration,
a hardcoded JWT signing key and a missing production profile.

<br/>

### Troubleshooting and the contribution boundary

Findings are classified Blocker, High, Medium or Low by their effect on your ability to build,
run or verify, and each carries a remediation and a way to re-derive it yourself.

**Blocker — `./mvnw` fails, or `java` and `mvn` are not found.** The build requires JDK 25;
Enforcer asserts `[25,)` and stops rather than compiling against an older release. Check with
`java -version` and `./mvnw -version`. Remediation: install a JDK 25 distribution and use
`./mvnw`, which supplies Maven 3.9.11 itself — or, if no JDK can be installed, build in the pinned
Java 25 / Maven 3.9.11 container shown above. This is precisely the condition recorded on
Thursday, July 30, 2026, and the container is the documented way around it.

**Blocker — Testcontainers modules will not resolve, or resolve at the wrong version.** Symptom:
`Could not resolve dependencies` naming `org.testcontainers:postgresql`,
`org.testcontainers:localstack` or `org.testcontainers:junit-jupiter`, or a 1.x jar on the test
classpath when 2.0.3 was expected. Cause and remediation are both in
[Build, run and verify](#build-run-and-verify): keep the `testcontainers.version` property at
`2.0.3`, do **not** import a second Testcontainers bill of materials, and use only the prefixed
module coordinates. Re-derive the current state with
`./mvnw -B dependency:tree -Dincludes=org.testcontainers`.

**Blocker — the application exits at start-up complaining about the signing key.** That is the
intended fail-fast: the base profile resolves `${JWT_SIGNING_KEY}` with no default, so an absent
key is a startup failure rather than a fall back to a shipped secret. Remediation: set
`JWT_SIGNING_KEY` in your environment or in your local uncommitted `.env`, using the non-echoing
pattern above. Do not add a default value to any profile.

**Blocker — container-dependent work cannot run.** Gates 1, 4, 5 and 8 and the
Testcontainers-backed test tiers all need a reachable Docker daemon. Check with `docker info`
before building. Remediation: start the daemon, and for the containerised build path mount
`/var/run/docker.sock` as shown above. Note this is a prerequisite, not a defect in the tree.

**High — Gate 1 has no baseline to compare against.** The parity comparison needs an
expected-output baseline generated from the legacy system, and none is present under
`src/test/resources` — only the input fixtures are. Until one exists, a Gate 1 run can execute the
pipeline but cannot assert parity, and no parity claim should be made from it. Remediation:
capture the legacy output for the same 300-record input and commit it as the baseline alongside the
fixture.

**High — start-up fails on a Flyway migration, or reports a checksum mismatch.** Usually a
database volume left behind from an earlier schema. Remediation: `docker compose down -v` and then
`docker compose up -d --wait`, which recreates an empty volume and re-applies `V1`, `V2` and `V3`
in order. Never edit an applied migration in place to make a checksum match — add a new one.

**Medium — a report submitted through `POST /api/reports` never starts a job.** Expected at
present. The publish side is wired and does replace `EXEC CICS WRITEQ TD QUEUE('JOBS')`, but the
consumer that replaces the JES2 internal reader is not: there is no `@SqsListener` in the main
sources, so the message is published and then simply not drained. Re-derive with
`grep -rn "@SqsListener" src/main/java` — an empty result confirms it. Remediation until a listener
exists: launch the pipeline explicitly through the orchestrator rather than expecting the queue to
trigger it.

**Medium — the vulnerability scan fails the build on a dependency you did not change.** OWASP
dependency-check fails at CVSS 7.0 and above, so a newly published advisory against a managed
transitive dependency can fail a build that passed yesterday. That is a dependency-version policy
decision rather than a defect in your change. Remediation: for local iteration use
`-Ddependency-check.skip=true` as the `verify` CI job does, and resolve the finding on its own —
either by moving the managed version or by recording an explicit, justified suppression. Do not
lower `owasp.failBuildOnCVSS`.

**Medium — a new document does not appear in the published documentation site.** `mkdocs.yml`
publishes strictly from its `nav` block and `catalog-info.yaml` renders from that same
configuration, so a document that is not listed in `nav` never appears — and this failure mode
produces **no error and no output**, which is what makes it easy to miss. Remediation: add the
document to `mkdocs.yml`'s `nav`, then confirm with a local `mkdocs build`.

**Medium — LocalStack resources appear to be missing after a restart.** `localstack-init/init-aws.sh`
is written to be idempotent, so repeated `docker compose up` cycles converge rather than failing on
resources that already exist; an "already exists" line in its output is success, not an error.
Remediation: check `docker compose logs localstack` for the init output, and remember that
`docker compose down -v` discards emulator state along with the database volume.

**Low — a documentation link in this section does not resolve.** The seven artifacts listed under
[Documentation and traceability](#documentation-and-traceability) are added as the migration
progresses, so in an earlier checkout one of them is `Not available` rather than missing by mistake.
Remediation: run the `ls -1` check in that section to see which are present, and treat the row's
description as the document's contract until the file itself lands. Nothing in the build depends on
any of them.

**Low — an Actuator endpoint returns `404`.** Only `health`, `info` and `prometheus` are exposed.
That is the configured least-privilege surface, not a fault: `env`, `beans`, `configprops`,
`loggers`, `heapdump` and `threaddump` each disclose configuration or memory contents, and
`configprops` in particular would echo resolved property values including the signing key.
Remediation: none is needed. If you genuinely need another endpoint, add it explicitly to
`management.endpoints.web.exposure.include` and review the disclosure that creates — never widen the
list to a wildcard.

**The contribution boundary**

`CONTRIBUTING.md` asks contributors to focus on the specific change and warns that wholesale
reformatting makes a change hard to review. These rules make that concrete for this migration:

- **Never edit, reformat, move, rename or delete anything under `app/`.** It is the parity oracle,
  the field-contract source and the traceability anchor simultaneously. A CI job asserts that the
  diff under `app/` is empty and fails the build otherwise. `.gitattributes` additionally disables
  text conversion for `app/**`, `samples/**` and `diagrams/**` so a checkout cannot silently
  renormalise them — which is also why `git add --renormalize` must never be run in this
  repository.
- **Do not parse or transcode the EBCDIC datasets.** The twelve files under `app/data/EBCDIC/` are
  byte-level codepage reference only. Use the ASCII fixtures instead.
- **Do not widen scope.** Deferred hardening — table partitioning, read replicas, connection-pool
  tuning, TLS termination, rate limiting, URI-based API versioning, a generated API specification
  and encryption at rest for personal data — is disclosed as residual risk in
  [`docs/validation-gates.md`](docs/validation-gates.md) and
  [`DECISION_LOG.md`](DECISION_LOG.md), and is deliberately not implemented here.
- **Do not "correct" legacy behaviour.** Parity is the contract. Known quirks are preserved and
  documented rather than repaired, and each one is justified in
  [`DECISION_LOG.md`](DECISION_LOG.md) with its COBOL locator. Where a deviation from parity was
  chosen deliberately, it is labelled as a deviation rather than presented as equivalence.
- **Keep the fixture names exact.** The ASCII transaction fixture is `dailytran.txt`, spelled in
  full, even though the mainframe DD name and dataset are `DALYTRAN`. The ten seed users have no
  standalone ASCII fixture at all — they are inline `SYSUT1 DD *` data inside
  `app/jcl/DUSRSECJ.jcl`.
- **Match `app/jcl` patterns case-insensitively.** Twenty-eight members use a lowercase `.jcl`
  extension and one — `CREASTMT.JCL` — does not. A `*.jcl` glob silently drops it, and it is the
  sole source for statement generation.
- **This README is append-only.** Everything above this section is the legacy reference of record,
  including the transaction, program and JCL inventories. Add to it; do not rewrite it. The file is
  CRLF-terminated and is exempted from end-of-line conversion in `.gitattributes` and pinned to
  `crlf` in `.editorconfig`, so any addition must use CRLF to avoid a whole-file diff with no
  content change.
