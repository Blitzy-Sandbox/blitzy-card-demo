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

> **Authorization is role-based only, and that is a deployment constraint rather than a detail.**
> Ten of these seventeen operations — the account, card, transaction, billing and report
> operations — are granted to **either role** and **scope nothing to the caller's own records**.
> An authenticated standard user may read or modify any account, any card and any transaction,
> and may pay a bill or run a report against an unrelated account, by naming its identifier.
>
> This is faithful, not an oversight: `app/cbl/COACTVWC.cbl` never consults `CDEMO-USER-ID`, so a
> signed-on 3270 operator could view any account by typing its number. It is also **not
> implementable from the source** — `app/cpy/CSUSR01Y.cpy` holds six fields (identifier, names,
> password, type, filler) and **none of them references an account, card or customer**, so no
> ownership relation exists in the frozen corpus to enforce.
>
> **Consequence:** treat every authenticated principal as trusted with the whole data set. This
> is a back-office application, safe where the legacy system was safe — a closed internal network
> with operator accounts issued by an administrator — and it **must not be exposed to end
> customers**, or to any population where one user must not see another's data, until that
> control is designed. What a human must decide first is set out as `DL-RR-10` in
> `DECISION_LOG.md` and `H-6` in `docs/validation-gates.md`.

The eighteenth CSD entry is transaction `CDV1`, whose program `COCRDSEC` **has no source file
anywhere in this repository**. The name occurs **twice**, and both occurrences are in the CSD
itself: `DEFINE PROGRAM(COCRDSEC)` at `app/csd/CARDDEMO.CSD:L211` and `PROGRAM(COCRDSEC)
TWASIZE(0)` at `:L390`. Both occurrences are definitions and neither is an implementation, so a
count of one would undercount the occurrences without changing the conclusion. It is a dangling legacy
definition, so no endpoint was invented for it. Re-derive with `grep -rn COCRDSEC app/`, which
prints two lines, both from `app/csd/CARDDEMO.CSD`, and names no `.cbl` or `.CBL` file.

Also deliberately absent, and not oversights:

- No 3270 or BMS terminal emulation, no green-screen rendering and no pseudo-conversational
  session emulation. The **441** input fields across the 17 symbolic maps are consumed as **DTO
  field contracts**, not reimplemented as a user interface. The **460** that specification prose
  publishes is not derived from the maps and is internally inconsistent with its own per-map table,
  which sums to 440, so it is not the figure used here. 441
  is mechanically derived, and the derivation is published so it can be re-run: every screen field
  is generated as a level-**02** data item named `<FIELD>I` carrying a `PIC` clause, alongside a
  matching `<FIELD>L COMP PIC S9(4)` length item, so

  ```shell
  for f in app/cpy-bms/*.CPY; do
    printf '%s %s\n' "$(basename "$f")" "$(grep -cE '^ +02 +[A-Z0-9]+I +PIC ' "$f")"
  done
  ```

  reproduces every per-map figure and sums to 441, and the independent count of the `<FIELD>L`
  length items agrees. Note `COACTVW` carries **37** fields, not the 36 the superseded table gave.

  `GateVerificationTest` asserts the derived figure, so it cannot drift from the corpus.
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

**Current environment observation — Friday, August 7, 2026.** This is the operative reading. On
the host where the results in [Validation gates](#validation-gates) were produced:

- **JDK 25.0.3 (Temurin-25.0.3+9, Eclipse Adoptium) and Apache Maven 3.9.11 are present and
  working.** `./mvnw -B -ntp -Ddependency-check.skip=true clean verify` completes with exit code 0.
- **Docker Engine 29.7.0 and `docker compose` are present and working.**
  `docker compose up -d --build --wait` brings all seven services up healthy.
- The AWS and LocalStack command-line tools are installed, though neither is required for any
  workflow documented here: the compose stack supplies LocalStack, and the application reaches it
  through the AWS SDK rather than through a CLI.

So **no host tool is missing, and the container path is a convenience rather than a remediation.**

<details>
<summary><strong>Superseded reading — Thursday, July 30, 2026</strong> (kept because the earlier
one was quoted as current context and should be visibly dated, not deleted)</summary>

On that earlier date, on a different host: Docker Engine 29.6.2 and `docker compose` v5.3.1 were
present and worked, but the host had **no `java`, no `javac` and no `mvn`** on `PATH`, so host
Maven validation was unavailable there while a build inside a pinned Java 25 / Maven 3.9.11
container was feasible. The LocalStack and AWS command-line tools were also absent. Note which
component was missing even then: the container runtime was **not** the gap.

That was a dated observation about one host, never a property of the project — and on any host with
JDK 25, `./mvnw` supplies Maven itself and no container is needed at all.

</details>

**Current environment observation — Friday, 7 August 2026. Every component above is present, so the
missing-JDK condition is closed.** Measured by invoking each tool:

| Component | Reading |
|-----------|---------|
| `java` / `javac` | Eclipse Temurin OpenJDK **25.0.3+9** (2026-04-21 LTS) |
| `./mvnw` | Apache Maven **3.9.11** — the wrapper resolves it, so no host `mvn` is needed |
| `docker` | Engine **29.7.0**, `docker compose` **v5.3.1**, daemon reachable |
| `localstack` / `aws` | LocalStack CLI **4.14.0**, aws-cli **1.46.0** — neither required by any workflow here |
| `mkdocs` | **1.6.1** with `techdocs-core` and `mermaid2`; `mkdocs build --strict` exits 0 with zero warnings |

The consequence is worth stating plainly: **the containerised build path is a convenience rather than
a remediation**, and the full gate runs directly on the host with `./mvnw clean verify`. That command
was executed on the host at this commit and exited **0**, with **15,092 unit test cases** and **919
integration and end-to-end test cases** passing and **0 compiler warnings**, against the 0.80 coverage
floor. **That warning figure is stated as compiler warnings deliberately, because the two available
readings differ and the looser one was published here before 9 August 2026.** `[WARNING]` lines in the
log are **0** when the vulnerability scan is skipped and **1 or 2** when it is not — measured at both
values on 9 August 2026, because the blank continuation line the scan plugin emits is always there while
its no-NVD-API-key advisory appears only when the plugin attempts a feed refresh — and every one of them
belongs to the scan plugin rather than to the build. Compiler warnings are **0** either way and cannot be otherwise, because
`maven-compiler-plugin` runs `-Xlint:all -Werror` with `failOnWarning`, so a compiler warning fails the
build instead of appearing in it. The earlier wording attributed "0 `[WARNING]` lines" to the full
`clean verify`, which is the one command for which it is not true. The Failsafe total decomposes as **804** integration plus **115** end-to-end, of which the gate
harness is **64** — and it decomposes exactly, which the figures published here before 9 August 2026
did not: they were 14,914 and 906, both understated, against a ledger table that itself published
799 + 107 = 907. The unit figure then moved four times more inside this one checkpoint —
**14,917 → 14,925 → 14,929 → 14,931 → 14,932 → 15,092** — and every move has the same cause: a claim that had been
maintained by hand was converted into one a build measures. The eight at 14,925 hold the published
batch-launch command against the job names the code registers; the four at 14,929 hold this
documentation set's published structural counts against the files they describe; the two at 14,931
hold every published page against a Markdown defect that renders as literal asterisks; and the one at
14,932 holds every pipe table against a missing separator row, which renders a whole table as a wall of
pipe characters; and the 160 at 15,092 are the field-width unit contract, the two batch span-naming
suites and the assertions added to suites that already existed. None of these four defect classes is reported by a strict build, because in every case
the Markdown is valid. Expect this figure to keep moving for that reason, and read it as the count
belonging to the run named here rather than as a constant. All of them are restamped from one green
run, and
[`docs/validation-gates.md`](docs/validation-gates.md) §2.6 carries the reconciliation.

Those figures are reproducible rather than retrievable: the reports live under `target/`, which
is build output and is not committed, so re-run the command at this commit rather than looking for a
stored file. `docs/validation-gates.md` is the authoritative ledger for anything gate-shaped.

<br/>

### Security and configuration

**Nothing in this repository contains a usable credential.** `.env.example` is a template of
variable names. Every name that carries credential material ships **empty** — `JWT_SIGNING_KEY`,
`POSTGRES_PASSWORD`, `CARDDEMO_DB_APP_PASSWORD`, `CARDDEMO_DB_MIGRATION_PASSWORD`,
`GRAFANA_ADMIN_PASSWORD`, `METRICS_SCRAPE_PASSWORD` and `NVD_API_KEY` — with exactly **two
documented exemptions**: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` carry the literal
placeholder `test`, which the emulator does not validate and which `AwsConfig` refuses to point at a
real account. That split is machine-checked rather than described:
`EnvironmentTemplateContractTest` asserts it, so this paragraph cannot drift from the file.
Copy the template to `.env`, fill it in locally, and never commit the result; `.env` is listed in
`.gitignore` for exactly that reason.

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
| `prod` | `application-prod.yml` | Deployment. Every secret externalised, least privilege, no development conveniences | **Endpoint required, with no default and no committed credential** | Off |

The `local` and `test` profiles are the only ones that seed the demo users. What `prod` does with the
cloud services needs stating precisely, because reading it as configuring **no** endpoint at all and
making **no** reference to an emulator gets it backwards:
`application-prod.yml` pins all three service endpoints to `${AWS_ENDPOINT_URL}` with **no default
value**, so the deployment must supply one and an absent variable is a startup failure rather than a
silent fall back. `AwsConfig` then validates every endpoint — absolute `http`/`https`, explicit port,
no user information, host on its permitted list — and rejects a credential carrying a real AWS key
prefix. So the guarantee is not "prod cannot reach an emulator"; it is that **no endpoint and no
credential is committed anywhere**, that nothing resolves by default, and that the demo-user seeding
and the development conveniences are off in this profile. A deployed system reads a different profile
and must be handed its own endpoint deliberately.

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

That container already carries Maven 3.9.11 on JDK 25, so `mvn` is invoked directly. Note that the
first `-v` mounts your working tree **read-write**, so the build writes `target/` back onto the
host, and anything running in the container can modify your sources.

The Testcontainers-backed tiers additionally need a reachable Docker daemon from *inside* the
container, which is normally arranged by adding `-v
/var/run/docker.sock:/var/run/docker.sock`. **Read the warning below before you do.**

> **Mounting the Docker socket grants host-root-equivalent privilege. Treat it as such.**
> The Docker API is not a sandbox boundary — it is a control interface for the daemon, and the
> daemon runs as root. Anything that can reach that socket can start a container that mounts `/`
> from the host with `--privileged`, and from there read, modify or replace any file on the machine
> regardless of your own user's permissions. **`:ro` does not help.** Appending `:ro` makes the
> *socket inode* read-only; it does nothing to the API reachable through it, because the API's
> destructive operations are ordinary writes on an already-open socket, not writes to the file. A
> socket mounted `:ro` still accepts `container create`, `--privileged` and arbitrary bind mounts.
>
> Prefer, in order:
> 1. **Run the build on the host** with the JDK and Maven installed, so no socket is shared at all.
> 2. **Use a rootless Docker daemon** and mount *its* per-user socket, typically
>    `$XDG_RUNTIME_DIR/docker.sock`, so a compromise is bounded by your own user rather than root.
> 3. **Use a throwaway or isolated daemon** — a dedicated VM, or a daemon whose only client is this
>    build — so the blast radius excludes anything you care about.
>
> If you mount the root daemon's socket anyway, do it knowingly, only for code you trust, and not
> on a machine holding credentials for anything else. Without a reachable daemon, expect the
> container-dependent tiers to be skipped or to fail fast; that outcome is safe, and it is the
> right default.

**What `verify` enforces.** Every item below fails the build rather than warning:

1. **Warnings are errors.** The compiler runs with `-Xlint:all -Werror` and `failOnWarning`, so a
   single warning stops the build. A Javadoc gate runs `doclint` at `all` with `failOnWarnings`,
   so undocumented public surface fails too.
2. **Toolchain floor.** Enforcer asserts Java `[25,)` and Maven `[3.9.11,)`, and rejects
   non-release dependency versions.
3. **Tests.** Surefire runs the unit tier; Failsafe runs the integration and end-to-end tiers.
4. **Coverage.** JaCoCo enforces a **minimum line coverage ratio of 0.80** and fails below it.
5. **Vulnerabilities.** OWASP dependency-check is bound to `verify` and fails the build on any
   finding at **CVSS 7.0 or above**, which is the range the CVSS specification itself labels
   High and Critical. Those two words are **external CVSS ratings**, not this project's finding
   severities, which are Blocker, High, Medium and Low.

For fast local iteration the vulnerability scan can be skipped:

```shell
./mvnw -B -ntp -Ddependency-check.skip=true clean verify
```

That prints `Skipping dependency-check` and produces **no scan report at all**, so it can never
show the scan passing — a skipped check is not a passed check. The same hole opens by a different
route offline, because `dependency-check:check` declares `requiresOnline` and Maven skips it with a
warning under `-o`. To measure the scan on its own, online:

```shell
./mvnw -B -ntp org.owasp:dependency-check-maven:12.1.0:check
```

**As last measured that scan exits 0**, reporting **168** dependencies, **166** suppressed
matches and **one** active finding — `CVE-2026-40977` against `spring-boot-3.5.11.jar` at CVSS
**6.7**, below the `owasp.failBuildOnCVSS` threshold of 7. `CVE-2026-66299` against
`tomcat-embed-core-10.1.57.jar` at CVSS v3 **7.5** is at or above that threshold and still has no
obtainable upgrade; it is answered by the single Tier 2 entry in `owasp-suppressions.xml`, scoped
to that one coordinate and identifier and **dated `until="2026-10-01Z"` so the record returns
rather than vanishing**. *Historical, 6 August 2026: the same command exited 1 on that advisory,
before the dated entry existed.* The reports land in
`target/dependency-check/dependency-check-report.{html,json,sarif}`. The gate closes by advancing
the pinned Tomcat version once a fixed release exists, or by an evidence-tiered suppression that
carries an expiry — **never** by raising the threshold, adding a skip, or narrowing the scanned
scope. `docs/validation-gates.md` is the authoritative gate ledger and carries the current status.

**CI does not take that shortcut.** Reading the repository's own `verify` job as passing
`-Ddependency-check.skip=true`, with a separate `security-scan` job running the scan on its own
schedule, would make a green CI run look like it had proved less than it did.
`.github/workflows/build.yml` has one `verify` job, it runs
`./mvnw clean verify` with **no skip property of any kind**, and there is no `security-scan` job.
The one-line proof: `grep -n 'dependency-check.skip' .github/workflows/build.yml` returns nothing.
The workflow's `image-scan` job is a different scanner (Trivy) over a different artefact (the built
container image) and proves nothing about the Maven dependency graph.

**Bring up the full topology**

Create `.env` with restrictive permissions **before** any credential goes into it, and verify the
mode. A plain `cp` inherits your `umask`, which on most systems leaves the file world-readable at
`0644` — and once a secret has been written into a `0644` file, tightening the mode afterwards does
not undo the window in which every local account could read it:

```shell
umask 077 && cp .env.example .env      # or: install -m 600 .env.example .env
stat -c '%a %n' .env                   # MUST print: 600 .env
```

Only once `stat` prints `600` should you fill in the empty values. `.env` is git-ignored and must
never be committed; `.env.example` is the tracked template and **ships every credential blank**. Two
values in it are not blank and are not credentials: the LocalStack access key and secret, both the
literal `test`, which the emulator does not validate and which `AwsConfig` refuses to point at a real
account. Thirty-one further values are populated and none is a secret either — profile name, ports,
host names, bucket and queue names, region, time zone, token issuer and expiry, and the **two
least-privilege database role names** `carddemo_app` and `carddemo_migrator`, which the provisioning
step creates and which are identifiers rather than credentials; their two passwords ship blank, and
remain yours to generate. `EnvironmentTemplateContractTest` is the authority for that split - it asserts
that every name carrying credential material ships empty, with exactly those two documented exemptions -
so the rule is machine-checked rather than described.

```shell
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
**`401`** is the intended configuration rather than a fault. `401` rather than `404` because the
security chain ends in `anyRequest().denyAll()` and refuses the request before routing decides whether
an endpoint exists — which is the stronger posture, since a `404` would confirm which endpoints are
absent. Measured on the running stack: `env`, `beans`, `metrics`, `configprops`, `loggers`, `heapdump`,
`threaddump`, `mappings`, `shutdown` and a nonexistent path all answer `401`, and none has ever answered
`200`. `prometheus` also answers `401` until the scrape credential is supplied.

Two further details of that posture are worth knowing before you write a probe against it. The
readiness group covers the application state plus the database, object storage and queue, and the
liveness group covers the application state alone. And the health matchers are declared for `GET`
specifically, so **`HEAD /actuator/health` answers `401` while `GET` answers `200`** — the image's
`HEALTHCHECK` and Prometheus both use `GET`, so the topology is unaffected, but a `HEAD`-based external
probe would report the application down while it is serving.

**Tear down, including volumes**

```shell
docker compose down -v
```

Dropping the volumes is what makes the next start-up re-run all three Flyway migrations against
an empty database, which is the reliable way to recover from a failed or half-applied migration.

**Run the packaged JAR under the `local` profile**

Scope the signing key to the single command that needs it. **An `export` is not a scope**: an
exported value is inherited by *every* subsequent child process of that shell — every later
`docker`, `git`, `npm` or editor invocation — for as long as the shell lives, and it is visible in
`/proc/<pid>/environ` to anything running as your user. So `read -rsp ... && export JWT_SIGNING_KEY`
does **not** let *"only the JVM"* see the key — the export is precisely what makes it not so.

The shortest recipe that works is the **subshell** form, because this profile needs five secrets and
three coordinates and a `.env` you have already verified at mode `0600` above holds all of them. Load
it in a subshell so the parent shell never holds them — `set -a` exports whatever the file contains,
so an untrusted or world-readable file is exactly the wrong input for it:

```shell
( set -a; . ./.env; set +a; \
  java --sun-misc-unsafe-memory-access=allow -jar target/carddemo-1.0.0.jar )
```

The parentheses are load-bearing: the exports die with the subshell.

If you would rather not load a file at all, use per-command assignments, which the shell places in that
one process's environment and nowhere else. **All four `CARDDEMO_DB_*` values are required** — they are
bound bare, with no default and deliberately no fallback to `POSTGRES_USER`, because falling back would
reconnect the application as the cluster superuser. Omitting them does not degrade to a working default;
start-up aborts naming the variable:

```shell
read -rsp 'JWT_SIGNING_KEY: ' key && printf '\n'
read -rsp 'CARDDEMO_DB_APP_PASSWORD: ' app_pw && printf '\n'
read -rsp 'CARDDEMO_DB_MIGRATION_PASSWORD: ' mig_pw && printf '\n'
JWT_SIGNING_KEY="$key" \
CARDDEMO_DB_APP_USER=carddemo_app \
CARDDEMO_DB_APP_PASSWORD="$app_pw" \
CARDDEMO_DB_MIGRATION_USER=carddemo_migrator \
CARDDEMO_DB_MIGRATION_PASSWORD="$mig_pw" \
SPRING_PROFILES_ACTIVE=local \
POSTGRES_HOST=localhost \
AWS_ENDPOINT_URL=http://localhost:4566 \
java --sun-misc-unsafe-memory-access=allow -jar target/carddemo-1.0.0.jar
unset key app_pw mig_pw
```

The two role names are the ones `docker compose` provisions and `.env.example` documents; the two
passwords are whatever you put in `.env` when you created it. Under `docker compose` none of this
arises: the compose file guards all four with Compose's `${VAR:?}`, so a container is never created
with one missing.

Where a wrapper genuinely forces an export into the current shell, `unset` every name the moment the
command returns rather than at the end of the session.

Never pass a secret as a command-line **argument**, which the process table and the shell history
both record — a leading `NAME=value` assignment is not an argument and is not recorded. Never
`echo` a secret back, and never place one in a tracked file. The container image sets the same JVM flag through
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

`docker compose up` starts seven services. Every image is pinned to a tag **and** a digest; a
floating or unpinned image reference must never be introduced, because it would make the stack
depend on when it was started.

| Service | Image | Host port | Role |
| :------ | :---- | --------: | :--- |
| `app` | built from `Dockerfile` | 8080 | The single CardDemo JAR — REST surface, batch jobs and Actuator |
| `postgres` | `postgres:16.14-alpine` | 5432 | The VSAM replacement. Flyway applies `V1`, `V2` and `V3` on start-up |
| `localstack` | `localstack/localstack:4.14.0` | 4566 | S3, SQS FIFO and SNS. `localstack-init/init-aws.sh` provisions the buckets, the FIFO queue and the topic |
| `jaeger` | `jaegertracing/jaeger:2.20.0` | 16686 UI, 4318 OTLP/HTTP | Trace collection and search |
| `pushgateway` | `prom/pushgateway:v1.11.1` | 9091 | Holds the end-of-run counter totals the batch submission process publishes as it exits |
| `prometheus` | `prom/prometheus:v3.13.2` | 9090 | Scrapes `/actuator/prometheus` on `app` and `/metrics` on `pushgateway` |
| `grafana` | `grafana/grafana:12.4.6` | 3000 | Dashboards, provisioned from `observability/grafana/` |

**Why the Pushgateway is part of the topology.** Three of the four named counters —
`carddemo.batch.records.processed`, `carddemo.batch.records.rejected` and
`carddemo.transaction.amount.total` — are written by `POSTTRAN` and `COMBTRAN`, and those run in
the operator submission process documented on `BatchPipelineOrchestrator`: a `java -jar` launch with
`--spring.main.web-application-type=none`, which ends when its job ends. A scrape cannot reach a
process that has already exited, and the consequence was measured rather than theorised — a real
`POSTTRAN` run printed `TRANSACTIONS PROCESSED :000000300` and `TRANSACTIONS REJECTED :000000038`
while every corresponding series still read `0.0`. The batch process now pushes its final values as
it exits and Prometheus reads them back, which is the same shape the legacy job had: publish the
totals once at end of run, to somewhere that keeps them. The push is **off by default** in every
profile, because the web application is already scraped and a process that both pushed and was
scraped would be counted twice; it is enabled per launch with
`--management.prometheus.metrics.export.pushgateway.enabled=true`. A `MeterFilter` in
`ObservabilityConfig` restricts what a pushing process publishes to the `carddemo.*` namespace, so a
dead batch JVM's own heap gauge cannot be retained and inflate a live measurement.

Published ports bind to `127.0.0.1` by default rather than to every interface. Parallel checkouts
that need their own stack should export `CLONE_INDEX` together with the per-port overrides
documented in `docker-compose.yml`, so that two stacks do not contend for the same ports.

**Authentication and authorisation.** `POST /api/auth/signon` upper-cases both the identifier and
the password exactly as `COSGN00C` does, verifies the BCrypt hash, and returns a token instead of
populating a COMMAREA. The legacy `CDEMO-USER-TYPE` values `'A'` and `'U'` become the **ADMIN** and
**USER** roles. `/api/admin/**` requires ADMIN. Session creation policy is **stateless** on every
chain — no session is created and none is consulted, so pagination state travels in request
parameters and response metadata rather than in server memory.

Demo users are seeded **only** under the `local` and `test` profiles — the Flyway placeholder
`seeddemousers` is `true` in `application-local.yml` and `application-test.yml` and `false` in both
`application.yml` and `application-prod.yml`, so no deployment outside those two profiles carries
them. Their source is the inline `SYSUT1 DD *` data in `app/jcl/DUSRSECJ.jcl` — ten records in the
80-byte `CSUSR01Y` layout, five administrators and five standard users. There is no standalone ASCII
fixture for them. The seed migration stores those passwords **only** as BCrypt hashes at cost 10; the
plaintext value in the JCL is never persisted.

> **Disclosure — this README reproduces that demo password literally, and cannot stop doing so.**
> The convention everywhere the migration controls is to cite the value by locator and never to spell
> it, so that a search of the tree for the literal returns nothing. This file is the one exception, and
> it is a cited one rather than an oversight: the first **14,639 bytes** are the pre-migration README,
> which AAP §0.3.1.6 constrains to an append-only update with that prefix byte-identical, and lines
> **157** and **158** inside it print the fixed password in full as part of the original demo
> instructions. Removing it would edit the frozen prefix, which is forbidden.
>
> Three consequences follow, and none of them is theoretical:
>
> - **Treat the value as public.** It is published in this file, it is identical for all ten seeded
>   users, and it is fixed in the frozen corpus. Nothing about it is secret and nothing should be built
>   as though it were.
> - **Re-derive it from the corpus rather than from prose**, which is what the rest of the
>   documentation does:
>   `awk 'NR==35 {print substr($0,49,8)}' app/jcl/DUSRSECJ.jcl` — record 1 of the ten, password field
>   at offset 49 for 8 bytes.
> - **Rotate before any non-demo use.** The default posture already does most of the work, because
>   `seeddemousers` is `false` outside `local` and `test`. If a deployment genuinely needs those ten
>   identifiers, seed them from a fresh secret hashed at cost 10 or higher and leave the placeholder
>   `false`; do not enable the demo seed and then change the passwords afterwards, because the window
>   between the two is a window with a published credential in it.

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
because the framework default would run every job on every boot. Launching is explicit, and the
name in the left-hand column above is exactly what an operator submits:

```shell
java --sun-misc-unsafe-memory-access=allow -jar target/carddemo-1.0.0.jar \
  --spring.main.web-application-type=none \
  --spring.batch.job.enabled=true \
  --spring.batch.job.name=POSTTRAN
```

That is the framework's own `JobLauncherApplicationRunner`, switched on for one process and
matching `spring.batch.job.name` against `Job.getName()` — so the value is **a job name from the
table above, never a Spring bean name**. `INTCALC` additionally needs `parmDate=2022071800`,
`TRANREPT` needs `startDate=` and `endDate=`, and `CARDDEMO-PIPELINE` needs **all three** —
passed as **bare arguments rather than `--` options**, because job parameters are not
configuration properties.
No in-process runner exists and none may be added: a bean that launches from inside
`SpringApplication.run` is a boot-time launch however narrowly it is gated. Every failure mode of
that command — a bean name, a `--`-prefixed parameter, and a second submission that either
quietly does nothing or refuses outright depending on whether the job takes parameters — is
measured in `docs/onboarding-guide.md` under *Running a batch job*.

`POST /api/reports` publishes a report-job message to the FIFO queue, which is the direct
replacement for `EXEC CICS WRITEQ TD QUEUE('JOBS')` in `CORPT00C`. **The consumer side is wired
too**, so the loop the legacy system closed through the JES2 internal reader is closed here: the
message is drained and the report job is launched from it, with no manual step in between. There is
exactly one consumer — `BatchConfig.ReportJobQueueListener.drainReportJobQueue`, bound with
`@SqsListener` to the queue named by `carddemo.aws.sqs.report-queue` under the listener id
`carddemoReportJobsListener`. Re-derive it with `grep -rnE '^\s*@SqsListener' src/main/java`, which
returns exactly that one line. Anchoring the pattern matters: a plain
`grep -rn "@SqsListener" src/main/java` also returns the prose in `BatchConfig` and `AwsConfig` that
documents the ownership boundary, so it reports **ten** lines for one declaration. Ten rather than the
eight an earlier revision of this sentence claimed - the figure is whatever the current commentary
happens to contain, which is exactly why the anchored form is the one to use and why no count of the
unanchored form should be relied on.

Three properties of that consumer are worth knowing before you rely on it, and each is stated in
full under [Troubleshooting and the contribution boundary](#troubleshooting-and-the-contribution-boundary):
it is **idempotent**, so a redelivery does not run the report twice; it **never returns a message it
cannot use**, because a FIFO group is ordered and a message that can never run would make every later
submission wait behind it; and it **logs no part of the payload**.

The listener also **withdraws itself rather than competing**, under exactly three conditions:
`carddemo.aws.sqs.report-queue` is unset, so there is no queue to bind; `spring.batch.job.name`
names a job, because a submitted batch process must not become a second reader of the queue that fed
it; or `carddemo.batch.report-queue-listener.enabled` is `false`, which is what `application-test.yml`
sets so the integration harness can be the only reader.

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

**[`docs/validation-gates.md`](docs/validation-gates.md) is the authoritative ledger, and it is the
only place a gate result may be read from.** The Status column below is quoted from it, not restated
in this file's own words — a result paraphrased in two places is a result that will eventually
disagree with itself, and a README contradicting the ledger it summarises is worse than one that
stays silent. When the two differ, the ledger is right and this table is stale; fix it here rather
than there.

**All eight gates have been executed**, at the run the ledger records in its §2.6:
`./mvnw -B -ntp clean verify` with **no skips**, exit code 0. A gate never
carries a status asserted from intent; each result below traces to a command, a UTC timestamp and an
exit code recorded there.

**On which commit that run belongs to, stated precisely rather than as "exact-HEAD".** A published
gate figure necessarily comes from a run made *before* the commit that publishes it, because a
document cannot cite the name of the commit containing it. The ledger therefore names the **parent**
deliberately and designates `gate.harness.commit` in
`target/gate-verification/gate-verification-evidence.properties` as the **enforceable** identity: the
harness reads the working tree's own git metadata at the instant it writes that file, so a consumer
can require it to equal the commit it asked to be tested, and a stale artefact from an earlier commit
is detectable where a timestamp alone would not reveal it. **This paragraph said "the exact-HEAD run"
for a commit**, which described the intent rather than the mechanism and invited a reader to compare
a published parent SHA against `HEAD` and conclude the evidence was misattributed. Read the marker,
not the prose. **One result is deliberately not "Pass", and it is not rounded up:**
Gate 3 reports a *measured baseline* rather than a verdict, because the corpus states no objective to
compare against. Gate 8, which previously reported *Partly*, is now a pass, and its figures were **re-taken**
rather than carried forward: the **seven**-service compose topology was brought up as a unit in this working
tree over a freshly emptied volume set, and a second `up` converged in about a second having recreated
nothing. The re-take was necessary, not cosmetic - the previous reading named a `--build` command at a commit
whose image build could not exit 0, and that build stage now exits 0.

| Gate | What it asserts | Evidence artifact | Status |
| ---: | :-------------- | :---------------- | :----- |
| 1 | End-to-end boundary parity: the 300-record `app/data/ASCII/dailytran.txt` fixture driven through `POSTTRAN`, compared field by field against the legacy baseline | [`docs/validation-gates.md`](docs/validation-gates.md) | **Pass** — the run matches **two independent expectations** on every field and every byte: the frozen program's own captured output under `src/test/resources/parity/gate1` (38 rejects, 262 postings, 50 account images, 100 category balances, return code 4), derived by compiling `app/cbl/CBTRN02C.cbl` unmodified with GnuCOBOL and executing it against the frozen fixtures; and a source-derived expectation under `src/test/resources/expected/posttran` (300 processed, 262 posted, 38 rejected, return code 4). A captured **z/OS** run remains `Not available` and would corroborate rather than replace either |
| 2 | Zero-warning build: a clean `verify` with warnings escalated to errors, exiting zero, plus a vulnerability scan reporting nothing at or above CVSS 7 — the range CVSS labels High and Critical | [`docs/validation-gates.md`](docs/validation-gates.md) | **Pass** — exit code **0**, **0** compiler warnings under `-Xlint:all -Werror`, 0 doclint errors, line coverage **0.9181** against the enforced 0.80 floor, and a scan that **ran rather than being skipped**, reporting **0** findings at or above CVSS 7 |
| 3 | Performance **baseline**: throughput in records per second, per-endpoint p95 latency, and peak heap | [`docs/validation-gates.md`](docs/validation-gates.md) | **Baselines measured and published** — **1,538** records/second, per-endpoint p95 from **21.5 ms** to **105.1 ms**, peak heap **252 MB** as a JVM-wide envelope. **No threshold is applied to any of them**: the corpus publishes no service-level objective, so none may be invented |
| 4 | Named fixture validation: all nine ASCII fixtures loaded through `V3__seed_data.sql` and driven through the pipeline, including zoned-decimal overpunch decode assertions and the ten seeded users | [`docs/validation-gates.md`](docs/validation-gates.md) | **Pass** — 300 daily-transaction rows seeded, **50** of them carrying negative overpunch amounts, 50 account rows compared field by field, and all **10** inline user records present as BCrypt digests |
| 5 | API contract verification: every one of the **17** operations exercised by integration tests against a real application context | [`docs/api-contracts.md`](docs/api-contracts.md), [`docs/validation-gates.md`](docs/validation-gates.md) | **Pass** — **17** mapped operations across the **8** named controllers, exercised over real HTTP against a running container, with role enforcement, statelessness and failure mapping asserted |
| 6 | Security audit: no floating-point type in any financial field, every password stored only as a BCrypt hash, no literal secret anywhere | [`docs/validation-gates.md`](docs/validation-gates.md) | **Pass** — **0** floating-point types in any financial field, **10** seeded credentials stored only as BCrypt cost-10 digests with **0** of 83 candidates authenticating, **0** committed secrets, and a scan over 166 dependencies with one active finding at CVSS 6.7 and **zero at or above 7** |
| 7 | Scope coverage: all **28** COBOL programs mapped, with the traceability matrix demonstrating complete paragraph coverage | [`TRACEABILITY_MATRIX.md`](TRACEABILITY_MATRIX.md), [`docs/validation-gates.md`](docs/validation-gates.md) | **Assertions hold** — **59** gate assertions, exit code **0**; 528 procedure paragraphs mapped across all **28** programs, with **537** matrix rows each naming a Java target method and an executable test method |
| 8 | Integration sign-off: the full compose stack up with health reporting `UP`, and all three Flyway migrations applying cleanly | [`docs/validation-gates.md`](docs/validation-gates.md) | **Pass** — the application stood up against a real containerised PostgreSQL 16 and LocalStack with all **three** Flyway migrations applied, **11** domain tables, **3** alternate-key indexes and **8** health contributors reporting; **and** the **seven**-service compose topology was brought up as a unit in this working tree — `docker compose up -d --build --wait`, exit code 0, all **seven** containers `healthy` — with a second `up` converging in about a second |

> **Only one gate table is published here, deliberately.** An earlier revision of this file
> carried a second table immediately below the one above, headed *Status, 6 August 2026*, which
> reported Gate 1 as `Not available`, the vulnerability half of Gate 2 as failing at exit 1, and
> Gates 3 and 8 as partly measured. Every one of those readings has since been superseded by the run
> named above, and publishing both left this file asserting two different current states at once —
> with the older, worse one last, where a reader stops. The superseded readings are not lost: the
> ledger keeps them, marked as superseded, so the direction of travel stays auditable in the one
> place this file has already said results may be read from.

- **Gates 2, 6 and 7 need no container at all.** Gates 6 and 7 are static analyses over the
  source tree and the traceability matrix. Gate 2 needs a JDK and Maven on the host and nothing more;
  both are present and the build passes.
- **Gates 1, 3, 4, 5 and 8 needed a container runtime, and it was available, so they ran.** All five
  were executed against a real PostgreSQL 16 and LocalStack, and Gate 8 additionally had the
  **seven**-service compose topology brought up as a unit in this working tree - exit code 0, all
  **seven** containers healthy - with a second `up` converging in about a second and recreating
  nothing. Those figures were re-taken after the fix that lets the image build stage exit 0; the earlier
  pair is withdrawn.
- **What remains open cannot be closed from inside this repository, and is stated rather than
  qualified away.** Gate 1 already diffs against two independent expectations; what it still lacks is
  a capture from the *real runtime* — the frozen COBOL executed on z/OS or a licensed emulator — which
  would corroborate both rather than replace either. Gate 3 lacks a service-level objective, and none
  may be invented.
- **`Not available` is a statement about published evidence, not about the code.** It does not mean a
  capability is missing and it does not predict that a gate would fail; it means the artefact a
  reviewer would open does not exist, so no verdict may be entered.

**One prerequisite for these results is worth knowing about.** The image build stage runs the unit
tier, so every repository file a unit guard reads has to reach the build context — and a `Dockerfile`
copying a selected file list that omits one breaks `docker compose up --build` while the identical
suite passes on the host. That has now happened three times: for `DECISION_LOG.md` and
`TRACEABILITY_MATRIX.md`, for `docs/` and `observability/`, and most recently for the four root
convention files `.gitignore`, `.gitattributes`, `.editorconfig` and `.dockerignore`. All of them are
copied, and the property is no longer left to the next image build to discover:
`SourceCitationResolutionTest.everyCitedTargetIsCarriedIntoTheImageBuild` reads the citation table
and the `Dockerfile` together, and fails in milliseconds when a row names a file the build stage
would not have — including, on the build that introduced it, itself.

The in-container tier is the host tier with two differences, both stated rather than rounded off.
`.dockerignore` prunes the out-of-scope `app/data/EBCDIC` datasets, so the two gates that resolve
citations into that subtree pass them over when — and only when — the whole directory is absent; a
clone and CI resolve them strictly. And the Maven build image ships no `python3`, so the single
executable proof needing an interpreter is assumed rather than run, which is the one skip the image
reports. Otherwise the two runs are equal: **14969** tests, **0** failures, **0** errors in each.

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

Each row above states what the document contains, and **all seven are present in this tree** — the
migration is a single phase, as the specification requires, so there is no staged delivery in which a
mandated document is legitimately still missing. Absence is therefore **not** a normal intermediate
state here, and **`Not available`** is not an expected reading for any of them: that would contradict
the single-phase contract and would make a real omission read as expected.
`.github/workflows/build.yml` asserts every one of them by name, together with the
eight `mkdocs.yml` nav entries that publish them, so an omission fails the build instead of passing
quietly. The command below re-derives the state of your own checkout:

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
Java 25 / Maven 3.9.11 container shown above. **That condition does not hold on the current host:**
JDK 25.0.3+9 and Maven 3.9.11 are both present as of Friday, 7 August 2026, `./mvnw` runs directly,
and the container is a convenience for hosts that lack a JDK rather than a remediation for this one.
The entry is kept because the failure mode itself is unchanged for anyone without JDK 25.

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
before building. This is a prerequisite, not a defect in the tree. Remediation: start the daemon,
and if you are building *inside* a container, share a daemon socket with it — **but only after
reading the privilege warning in [Build, run and verify](#build-run-and-verify), because sharing
the root daemon's socket is equivalent to granting host root, and `:ro` does not reduce that.**
Running the build directly on the host, or against a rootless daemon's per-user socket, avoids the
exposure entirely and is the preferred route.

**Low — the Gate 1 parity oracle needs re-deriving, or a comparison fails.** The oracle under
`src/test/resources/parity/gate1/` is the output of the frozen `app/cbl/CBTRN02C.cbl` compiled
unmodified with GnuCOBOL and executed against the frozen fixtures, so a comparison failure is a
parity defect in the Java pipeline rather than a reason to edit the oracle. Its loader checks every
record width and every SHA-256 against `PROVENANCE.properties`, so a hand-edited artefact fails at
load with a message naming it. Remediation: fix the pipeline. Re-derive only when a fixture or the
frozen program changes, with `apt-get install -y gnucobol3` and then
`sh src/test/resources/parity/gate1/harness/derive-gate1-oracle.sh "$(pwd)" /tmp/gate1`. That script
stops at the raw datasets on purpose: rendering them into the committed `.expected` images and
re-taking the digests is a separate manual step, so a regeneration is **diffed against the committed
oracle before it replaces it**. This entry was a High
reading — "Gate 1 has no baseline to compare against" — until 7 August 2026, when the oracle was
produced by executing the legacy program.

**High — start-up fails on a Flyway migration, or reports a checksum mismatch.** Usually a
database volume left behind from an earlier schema. Remediation: `docker compose down -v` and then
`docker compose up -d --wait`, which recreates an empty volume and re-applies `V1`, `V2` and `V3`
in order. Never edit an applied migration in place to make a checksum match — add a new one.

**Medium — a report submitted through `POST /api/reports` never starts a job.** No longer expected:
both halves of the bridge are wired. The publish side replaces `EXEC CICS WRITEQ TD QUEUE('JOBS')`,
and `BatchConfig.ReportJobQueueListener.drainReportJobQueue` — the one `@SqsListener` in the main
sources, bound to `carddemo.aws.sqs.report-queue` under the id `carddemoReportJobsListener` — drains
the message and launches the report job from it, replacing the JES2 internal reader. Diagnose in this
order:

1. **Is the queue reachable and is the listener bound?** The listener resolves its queue name from a
   property with no default, so an unresolvable value fails start-up rather than degrading silently.
   A running application with no start-up failure means the binding is live. Confirm the queue exists
   with `docker compose exec localstack awslocal sqs list-queues`.
   If there is no listener at all, it withdrew on purpose: `carddemo.aws.sqs.report-queue` is unset,
   `spring.batch.job.name` names a job, or `carddemo.batch.report-queue-listener.enabled` is `false`
   (which is what `application-test.yml` sets, so the integration harness is the only reader).
2. **Was the message rejected as out of contract?** Look for a `discarded` record naming a
   `reason`. The submission record admits only the three report periods the legacy screen offers and
   only the ten-character dashed dates the source's parameter cards carry, so a body outside that
   grammar is refused at binding. **The record names a reason code and never the payload**, by
   design — the values are bound from a queue body, so a publisher could otherwise put a card number
   in the report name and have it written to the log.
3. **Was it a redelivery of something already run?** The consumer is **idempotent**: the queue's
   deduplication identifier is carried as an identifying job parameter, so a second delivery of the
   same submission is recognised and no competing execution is started. That is what turns an
   at-least-once queue into exactly-once processing. A record saying the submission was *already
   processed to completion* or *still running* is the mechanism working, not a fault.

**Safe failure modes, stated because they are deliberate.** A message that cannot be bound is
**consumed and discarded, never returned to the queue.** That looks wrong until you notice a FIFO
group is ordered: returning one unusable message makes every later submission in the group wait a
visibility window per redelivery, for no possibility of a different outcome. `localstack-init/init-aws.sh`
does provision a dead-letter target with a `RedrivePolicy` of `maxReceiveCount` 4, and it bounds the
failures this consumer *cannot* classify - a job that fails deterministically is returned to the queue
here, because a failure might be transient. A body that can never bind is not one of those, so it is
dropped on the first delivery rather than left to exhaust the count. The bytes stay on the queue's own
retention rather than in a log stream. Every delivery is named in the log by a short one-way digest
of its transport identifier rather than by the identifier itself, so records for one delivery are
still joinable while the publisher-controlled value is never republished. The identifier itself keeps
its load-bearing role as the idempotency key, because that path never reaches a log.

**Medium — the vulnerability scan fails the build on a dependency you did not change.** OWASP
dependency-check fails at CVSS 7.0 and above, so a newly published advisory against a managed
transitive dependency can fail a build that passed yesterday. That is a dependency-version policy
decision rather than a defect in your change. Remediation, in order: pin the affected dependency
**forward** to a fixed release; if no fixed release exists, disposition the record in
`owasp-suppressions.xml` with a narrow, dated, evidence-based entry naming the coordinate and the
single CVE, and delete that entry the moment the fix can be pinned. `-Ddependency-check.skip=true` is
a local iteration shortcut only — **CI never passes it** — and lowering `owasp.failBuildOnCVSS` is not
remediation.
Entries in that register are tiered, and each names one advisory and one coordinate family
rather than a class of advisories, carrying the evidence that the vulnerable component is
genuinely absent from what this application ships. A `packageUrl` pattern is used only to
cover the coordinates one scanner attributes a single CPE to - never to widen an entry past
the one named CVE - and no skip is ever added to the plugin itself: a gate that passes
because it stopped looking is worse than a red one.

**Medium — a new document does not appear in the published documentation site.** `mkdocs.yml`
publishes strictly from its `nav` block and `catalog-info.yaml` renders from that same
configuration, so a document that is not listed in `nav` never appears — and this failure mode
produces **no error and no output**, which is what makes it easy to miss. Remediation: add the
document to `mkdocs.yml`'s `nav`, then confirm with a local build — writing the site **outside**
the repository, which every documentation build in this project does for the reason below:

```bash
mkdocs build --strict --site-dir /tmp/carddemo-site
```

`mkdocs.yml` sets no `site_dir`, so a bare `mkdocs build` writes `./site/` into the working tree.
That output contains the *rendered* form of `docs/project-guide.md`, whose sign-on example carries
the seeded plaintext, and Gate 6's credential walk excluded that document only by its exact source
path — so the very next `./mvnw verify` failed on `site/project-guide/index.html`. Two documented
commands, each correct on its own, were mutually exclusive in sequence. The walk now also skips the
gitignored build-output roots, so the collision is closed from both ends; `--site-dir` is the half
that keeps the working tree clean.

**Medium — LocalStack resources appear to be missing after a restart.** `localstack-init/init-aws.sh`
is written to be idempotent, so repeated `docker compose up` cycles converge rather than failing on
resources that already exist; an "already exists" line in its output is success, not an error.
Remediation: check `docker compose logs localstack` for the init output, and remember that
`docker compose down -v` discards emulator state along with the database volume.

**Resolved — every documentation link in this section resolves.** All seven artifacts listed under
[Documentation and traceability](#documentation-and-traceability) are present in this tree, and a
repository-wide scan finds **zero** broken Markdown link occurrences. `.github/workflows/build.yml`
asserts every one of them by name, together with the eight `mkdocs.yml` nav entries that publish
them, so an omission fails the build instead of passing quietly. The entry is kept rather than
deleted because the underlying condition can recur in a checkout taken before the documentation
landed: there, run the `ls -1` check in that section to see which are present, and treat the row's
description as the document's contract until the file itself lands. Nothing in the build depends on
any of them.

**Low — an Actuator endpoint returns `401`.** Only `health`, `info` and `prometheus` are exposed.
That is the configured least-privilege surface, not a fault: `env`, `beans`, `configprops`,
`loggers`, `heapdump` and `threaddump` each disclose configuration or memory contents, and
`configprops` in particular would echo resolved property values including the signing key.
An earlier revision of this entry said `404`, which was wrong about the status while right about the
posture: the security chain ends in `anyRequest().denyAll()`, so an unexposed path is refused before
routing decides whether it exists, and a nonexistent path under `/actuator` answers `401` for the same
reason. Measured on the running stack rather than inferred. Remediation: none is needed. If you
genuinely need another endpoint, add it explicitly to
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
