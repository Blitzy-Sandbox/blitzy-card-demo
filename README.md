## CardDemo -- Mainframe CardDemo Application

- [CardDemo -- Mainframe CardDemo Application](#carddemo----mainframe-card-demo-application)
- [Description](#description)
- [Technologies used](#technologies-used)
- [Installation on the mainframe](#installation-on-the-mainframe)
- [Building and running the Java module](#building-and-running-the-java-module)
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
6. Java 21 (LTS)
7. Apache Maven
8. Spring Boot 3.x

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

   The Java module added under app/java is built with Maven instead. See [Building and running the Java module](#building-and-running-the-java-module) below

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

> [!WARNING]
> **Those two credentials are published seed values, and they must be replaced before any deployment that is reachable by anyone but you.** They are printed above, they are in `app/jcl/DUSRSECJ.jcl` in the clear, and `USRSEC` stores `SEC-USR-PWD` as `PIC X(08)` plaintext -- so anyone who has read this file has administrator credentials, and `ADMIN001` has user-maintenance authority. `PASSWORD` is also eight characters, which is the field's whole width: `CSUSR01Y.cpy:21` declares `PIC X(08)`, so no longer password is representable and the password space is small by construction.
>
> Sign-on additionally distinguishes its failure reasons -- `Wrong Password. Try again ...` for a known user, `User not found. Try again ...` for an unknown one -- which lets a caller enumerate valid user ids. **That is preserved deliberately.** Those message texts are transcribed byte for byte from `app/cbl/COSGN00C.cbl:242-249` and the parity suite compares them character for character, so collapsing them into one message would be a behaviour change and would fail the gate. Nothing in the application throttles, delays, locks out or counts failed attempts either, because nothing in the COBOL does.
>
> The consequence is that **rate limiting, lockout and authentication monitoring are controls the deployment must supply**, outside this application: at the gateway or in the network in front of it. Replace both seed passwords, and see *Securing a deployment* below before exposing either the CICS region or the Java module beyond a private environment.

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

## Building and running the Java module

The repository also carries a Java translation of the same application in `app/java`, added alongside the mainframe source rather than in place of it. It is a like-for-like migration of the COBOL programs in `app/cbl`: the CICS online transactions become stateless REST endpoints, the batch programs become Spring Batch jobs, and the migration targets byte-for-byte equivalence of every observable output -- record bytes, field values, numeric scale, message text and return codes. That target is gated rather than asserted: every program is diffed field by field against expected values, and a module is not complete until its diff count is zero. What those expected values are is stated plainly under Verification gates below -- they are derived from the COBOL sources, the copybook byte layouts, the JCL contracts and the sample data in `app/data/ASCII`, and they were **not** captured from a run of the legacy programs, which needs a mainframe this build does not have. So the gate establishes conformance to derived expectations, not observed equivalence with a live COBOL execution; that limitation is open and unresolved, and is carried as risk R-A in [docs/project-guide.md](docs/project-guide.md). No mainframe artefact changes, and the installation path described above remains valid and complete on its own.

### Prerequisites

1. Java 21 (LTS). The module targets release level 21 (`<release>21</release>`), so any Java 21 JDK compiles it. The build here was verified with OpenJDK 21.0.11, and **21.0.11 is a version this build was verified on, not a version you should deploy on**: the JDK a deployment runs is a deployment input, and it should be a 21.x build carrying the **current quarterly Critical Patch Update**. Oracle and the OpenJDK distributors publish 21.x CPU releases every quarter, each closing vulnerabilities that affect every earlier 21.x build; 21.0.11 predates the current one. Pin the JDK explicitly in your deployment and advance it on the CPU cadence -- this repository cannot do that for you, and no `pom.xml` setting can, because `<release>` fixes the *language and API level* and says nothing about the runtime's patch level.
2. Apache Maven 3.9 or newer -- verified with Maven 3.9.16. Maven 3.8.x is below the required floor

Nothing else is needed to build and test the module: no database server, no container runtime, no cloud account and no mainframe connectivity.

### Building and testing

```shell
mvn -f app/java/pom.xml clean verify
```

That one command is the gate. It compiles the module, runs every unit and parity test, and enforces the coverage threshold in a single pass. Add `-B` for a non-interactive batch-mode run and `-Dsurefire.useFile=false` to keep test output on the console. There is no watch mode: every build command and every batch submission runs to completion and stops. The two commands that start the online service -- `mvn spring-boot:run` and `java -jar` with no job name -- are the exception, and deliberately so: a server runs until it is interrupted.

Narrower commands are useful when only one gate is of interest

| Command                                         | What it does                                            |
| :---------------------------------------------- | :------------------------------------------------------ |
| `mvn -f app/java/pom.xml -B clean compile`      | Compiles the module and nothing else                    |
| `mvn -f app/java/pom.xml -B dependency:resolve` | Confirms every dependency resolves from Maven Central   |
| `mvn -f app/java/pom.xml -B clean package`      | Builds the runnable jar at app/java/target/carddemo.jar |

### Running the online transactions

The entry point is `com.vsergeychik.carddemo.CardDemoApplication`. Run it from the build, or from the jar the build produces - either form needs the data source and the CICS region identity described under Configuration and data access below, and refuses to start without them

```shell
mvn -f app/java/pom.xml spring-boot:run
```

```shell
mvn -f app/java/pom.xml clean package
LOADER_PATH=/opt/carddemo/drivers java -jar app/java/target/carddemo.jar
```

`LOADER_PATH` is how the deployment's own JDBC driver reaches the application, and it is not optional in a real deployment: the build pins no driver coordinate, so the driver jar is never inside `carddemo.jar`. Put it - together with any file the driver itself needs - in a directory of your choosing and name that directory in `LOADER_PATH` (or `-Dloader.path=...`, which takes a comma-separated list of directories and jars). The jar is packaged with Spring Boot's `PropertiesLauncher` for exactly this reason.

**A `-cp` entry beside `-jar` will not work.** `java -cp /opt/carddemo/drivers/driver.jar -jar carddemo.jar` is silently ignored by the JVM: with `-jar`, the class path comes from the archive alone. That command fails at startup with *"The JDBC driver class ... is not on the classpath"* while the driver sits on the machine, which is why the diagnostic itself names `LOADER_PATH`.

**`LOADER_PATH` is a code-loading path, so treat it as one.** Anything in that directory is loaded into the application's class loader and runs with the application's full authority, and `PropertiesLauncher` *prepends* it -- so a jar placed there can also shadow a class the application would otherwise have loaded from inside `carddemo.jar`. Whoever can write to the directory, or set the variable, can therefore execute code in this process (CWE-427, CWE-494). The build pinning no driver coordinate is what makes the deployment portable; it also means **the integrity of that jar is entirely the deployment's responsibility**, because nothing in this repository can verify a driver it never names. At minimum:

* **Name one approved driver at one exact version** in your own deployment configuration, and treat a change to it as a change to the application.
* **Verify the artefact before it is installed** -- a published checksum, and a signature where the vendor provides one. Record the verified digest with the release.
* **Own the directory outside the application.** It should be owned by `root` (or a deployment account that is not the account the application runs as) and mode `0755` or tighter, containing nothing writable by the runtime user. The application must be able to read it and must not be able to write it.
* **Put nothing else in it.** It is not a general library directory; every jar there is trusted code. Prefer naming individual jars via `-Dloader.path=/opt/carddemo/drivers/driver.jar` over naming a directory, so adding a file to the directory is not by itself enough to load it.
* **Run the application as an unprivileged account** that owns none of its own code, so a compromise of the runtime user cannot alter what is loaded next time.
* **Put the variable under change control.** `LOADER_PATH` is read from the environment, so whoever controls the unit file, container spec or shell that starts the process controls what is loaded. Set it in the deployment manifest, not interactively.

The programs listed under Online below are served as REST resources under `/api`, one per CICS transaction -- `POST /api/signon`, `GET /api/menu`, `GET /api/accounts/{acctId}`, `GET /api/cards`, `GET /api/transactions`, `POST /api/billpay`, `GET /api/users` and their siblings. CICS is pseudo-conversational, so the migration keeps no server-side session: the communication area, the key that was pressed and the screen's own field values all travel in the request and response payloads, and every reply carries the state the next call needs.

To start the service locally with no external data source at all, run it on the fixture-backed `test` profile. That profile reaches its in-memory settings through a classpath import that lives in the test tree, so the JVM that runs has to carry `target/test-classes`: `spring-boot:run` forks a JVM that does not, and asking the plugin for the test classpath is not enough. From `app/java`

```shell
mvn -B test-compile
mvn -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
     com.vsergeychik.carddemo.CardDemoApplication --spring.profiles.active=test
```

Started that way the context comes up and `/api` answers with no mainframe and no database server in sight. What that command does not do is create a dataset. This module issues no DDL at all, by design -- that is the same guarantee the production deployment depends on -- so the profile only says *where* each dataset lives, and an in-memory database starts with no relation in it. Until the relations exist a data-backed call fails rather than reading nothing: sign-on answers `Unable to verify the User ...` because the backend reported `SQLSTATE 42S02`, which the repository maps to file status `9000`, and `accountBalanceJob` abends with `ERROR OPENING ACCTFILE`, `RETURN-CODE=12` and process exit code 12. The test suite never meets that, because each test creates and seeds the relations it needs itself, which is why `mvn -f app/java/pom.xml clean verify` needs none of the steps below.

The quickest way to put the profile's own declared data behind those bindings is the seeded entry point that lives in the test tree. `com.vsergeychik.carddemo.testsupport.FixtureSeededApplication` is the shipped application plus one test-scope configuration that materialises what `app/java/src/test/resources/carddemo-test-fixtures.yml` declares: the nine fixed-width fixtures across the DD names they serve, and the ten `USRSEC` rows from `app/jcl/DUSRSECJ.jcl`, each right-padded once to its copybook width. It is under `src/test` and is never packaged, because those ten rows carry plaintext passwords and a credential-shaped value inside a distributable artifact is indistinguishable from a real one. With the same two preparation commands as above, from `app/java`

```shell
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
     com.vsergeychik.carddemo.testsupport.FixtureSeededApplication --spring.profiles.active=test
```

Started that way the log states what it seeded -- ten relations, 636 records -- and the data-backed calls answer instead of failing: `POST /api/signon` as `ADMIN001` with password `PASSWORD` returns a blank `errmsg` and `COADM01C` as the next program, and `GET /api/cards?eibaid=125` returns real card numbers. Those relations belong to that one process's in-memory database and go away with it, which is the one thing the file-backed procedure below does differently.

To provision a database that **outlives** the run, so that a second process, a repeated start or an SQL client sees the same rows, do it by hand against a file-backed database instead. Each relation is one column wide: the record image in column 1, at the copybook width, one row per fixed-width record. The dataset names this profile uses are listed in `app/java/src/main/resources/application-test.yml` and the records come from `app/data/ASCII`. The account master is shown here; every other dataset follows the same two steps. From `app/java`

```shell
CP="target/test-classes:target/classes:$(cat target/cp.txt)"
DB="$PWD/target/local/carddemo"
DS="CARDDEMO.TEST.ACCTDATA.VSAM.KSDS"
mkdir -p target/local

{ printf 'CREATE TABLE "%s" (RECORD_IMAGE CHAR(300));\n' "$DS"
  sed -e "s/'/''/g" -e "s|^|INSERT INTO \"$DS\" VALUES ('|" -e "s|\$|');|" ../data/ASCII/acctdata.txt
} > target/local/provision.sql

java -cp "$CP" org.h2.tools.RunScript -url "jdbc:h2:file:$DB" -user sa -script target/local/provision.sql
java -cp "$CP" com.vsergeychik.carddemo.CardDemoApplication \
     --spring.profiles.active=test --spring.datasource.url="jdbc:h2:file:$DB"
```

The sign-on dataset is the one exception worth naming: no file under `app/data/ASCII` holds users, and the ten the application ships with come from the inline data in `app/jcl/DUSRSECJ.jcl`, padded to the 80-byte record `app/cpy/CSUSR01Y.cpy` declares. [docs/project-guide.md](docs/project-guide.md) carries this procedure in full, alongside what a real deployment has to expose in place of an in-memory database.

### Running a batch job

`spring.batch.job.enabled` is `false`, so starting the application runs no job. Each job is submitted explicitly by name, one per process, exactly as JCL submits one `EXEC PGM=` step at a time

```shell
LOADER_PATH=/opt/carddemo/drivers java -jar app/java/target/carddemo.jar \
     --carddemo.batch.job-name=accountBalanceJob
```

A submission reads the datasets through the same deployment-supplied driver, so it needs the same `LOADER_PATH`. The name may also be given as the `CARDDEMO_BATCH_JOB_NAME` environment variable, which is the relaxed spelling of the same property. Supplying the job name either way also selects a non-web process: no HTTP port is bound, because a job has nothing to serve, and the process ends when the job does.

The process exit code is the program's `RETURN-CODE`, so gating one job on the result of the one before it behaves as `COND` does on the mainframe.

The interest calculation translated from CBACT04C is the only job that takes a parameter. It declares a single `parmDate` string job parameter, mirroring `PARM='2022071800'` in INTCALC.jcl. That value is character data: the program concatenates it verbatim into the transaction identifiers it generates, and it is never parsed as a date or reformatted. Override it with the `CARDDEMO_JOB_PARM_DATE` environment variable.

### Configuration and data access

Dataset names and the JDBC `DataSource` are entirely configuration bound in `app/java/src/main/resources/application.yml`, so no dataset name and no connection detail is compiled into the code. The site-specific data access driver is a deployment-time input, supplied through `CARDDEMO_DATASOURCE_URL`, `CARDDEMO_DATASOURCE_DRIVER_CLASS_NAME` and the matching credential variables; the build deliberately pins no driver of its own, and startup is refused with a message naming the missing property when none is supplied.

The CICS region identity is a deployment input for the same reason. `COSGN00C` obtains two of its eleven screen fields from `EXEC CICS ASSIGN APPLID` and `EXEC CICS ASSIGN SYSID`, which have no Java equivalent, so they are supplied through `CARDDEMO_CICS_APPLID` and `CARDDEMO_CICS_SYSID`. Neither has a default: both fields are compared byte for byte by the parity suite, so a deployment that states no region is refused at startup rather than painting eight spaces into a field a real region would have filled. Both are checked against what `ASSIGN` could have reported, too - at most 8 characters of application identifier and at most 4 of system identifier, the latter despite its field being eight columns wide - and against the code page, so an identity no region could have answered is refused rather than truncated into a compared field. Two further inputs describe the site's gateway rather than the module and are defaultless for the same kind of reason: `CARDDEMO_RECORD_IMAGE_FORM` states whether a record image crosses JDBC as characters or as bytes, and `CARDDEMO_PHYSICAL_SEQUENCE_EXPRESSION` names the ordinal that stands for a stored record's physical position. The packaged `application.yml` supplies neither value, because a default would be indistinguishable from a decision the deployment never made: a gateway presenting binary columns would otherwise have had every record decoded as text, and a differently spelled ordinal would have ordered a report by something that is not the record's position.

The module reaches the existing datasets over plain JDBC and changes nothing about how they are stored: no DDL, no schema migration, no ORM and no new database. Record layouts stay exactly as the copybooks in `app/cpy` define them, which is why the dataset and copybook table above is the reference every Java record width is checked against, and why the code pages are named explicitly -- IBM037 for the EBCDIC datasets, US-ASCII for the sample text files -- rather than left to a platform default.

`application-test.yml` rebinds all 27 dataset bindings onto in-memory test data, so the test suite runs with no external database and no mainframe connectivity. Seventeen of them are backed by the nine fixed-width fixtures derived from app/data/ASCII -- one fixture reaches several DD names where the legacy estate addresses one dataset under more than one name, and `cardxref`'s 36-byte rows are padded up to the 50 bytes CVACT03Y declares. `USRSEC` is seeded inline from the ten sign-on rows of app/jcl/DUSRSECJ.jcl, padded from 57 bytes to 80. The remaining nine -- `DALYREJS`, `DATEPARM`, `HTMLFILE`, `STMTFILE`, `SYSTRAN`, `TRANFILE`, `TRANREPT`, `TRANSACT` and `TRNXFILE` -- hold nothing at the start of a run: they are what a run produces rather than what it reads. Inside the suite the seeding is each test's own work rather than the profile's: a case declares the datasets it needs, loads its rows and holds them privately for its own duration, which is what lets one case seed an expired card, another an empty dataset and another a record narrower than its copybook -- a shared baseline underneath them all would make those cases unreachable. What the profile declares is nevertheless bound and checked rather than merely written down: `app/java/src/test/resources/carddemo-test-fixtures.yml` states the ten entries, each with its resource, record count, record width, the copybook width to pad up to where it is short, and the DD names it serves; `FixtureInventory` binds that strictly, so a misspelled key fails the bind, and re-derives every number from the fixture bytes and the shipped dataset catalogue rather than trusting it; and `FixtureSeeder` materialises it for a hand-started JVM, as described above.

### Securing a deployment

This module is a like-for-like translation, and its security posture is therefore the COBOL's security posture plus whatever the boundary around it supplies. That split matters more here than in most applications, because on the mainframe a great deal was enforced by the CICS region and the terminal network rather than by the programs -- and **none of that surrounding enforcement is reproduced by translating the programs.** What follows separates the three kinds of control so that nothing is assumed to be in place that is not. [docs/project-guide.md](docs/project-guide.md) carries the same split in full, finding by finding.

**Enforced by this module.** These are properties of the code and configuration as shipped, and each is covered by a test:

* Request bodies are bounded. Document length is capped at 1 MiB and token count at 100,000 -- both of which Jackson leaves *unlimited* by default -- along with string length, nesting depth, property-name length and numeric-token length. An over-size body is answered `400` and nothing of it is echoed back.
* Every response carries `Cache-Control: no-store` (with the legacy `Pragma`/`Expires` pair) and `X-Content-Type-Options: nosniff`, on the success path and on the container's error path alike. No screen this API paints is reusable by another caller.
* The listener binds to loopback unless told otherwise, and `X-Forwarded-*` headers are ignored unless a deployment names a terminator it trusts.
* Error responses carry no message, no binding detail and no stack trace; the field names behind a rejection go to the server log and only to the server log.
* The submitted sign-on password is never returned in a response body.
* No dataset name and no connection detail is compiled in, and startup is refused rather than guessed at when a required deployment input is missing.
* Every SQL statement is parameterised, every code page is named explicitly, and no mutable state is static or session-scoped.

**Must be supplied by the deployment.** These are not optional, and this module cannot provide any of them without changing observable behaviour or adding a framework the migration excludes:

* **TLS.** The listener is plain HTTP until a keystore is supplied, and every request carries screen data -- account numbers, full card numbers, customer addresses, and on `POST /api/signon` a password compared in plaintext exactly as `COSGN00C.cbl:223` compares it. Enable it with `CARDDEMO_SERVER_SSL_ENABLED=true` plus `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD` and, where needed, `SERVER_SSL_KEY_STORE_TYPE` and `SERVER_SSL_KEY_ALIAS`. Spring Boot reads all of those from the environment, so no keystore or password ever enters this repository or an image layer. Terminating TLS at a gateway instead is equally acceptable; leaving it off is not.
* **Authentication and authorization at the boundary.** `POST /api/signon` verifies a password against `USRSEC`, and that is the whole of it: it issues no token, sets no cookie and establishes no server-side session, because CICS was pseudo-conversational and the migration keeps that shape (no session is a deliberate, tested property). Consequently **every other route is reachable without signing on**, including `GET /api/admin/menu` and the `USRSEC` maintenance routes under `/api/users`, and the `userType` a request carries is a value the *client* supplied rather than an identity the server established. Put a trusted gateway or a private enclave in front of this listener, have it establish identity, and have it authorize `/api/admin/**` and `/api/users/**` against that identity -- never against a field in the payload. Spring Security, JWT and credential hashing are excluded from this migration by plan, so this cannot be closed inside the application.
* **Authorize on the URI, which is the resource identity.** A path variable such as the `{acctId}` in `PUT /api/accounts/{acctId}` governs on every turn, first entry and re-entry alike: each of the seven keyed screens compares the body's key member against the path variable before it reads anything, and then overwrites that member with the path variable's value. A body key naming a different record is refused with `400 Bad Request`, a fixed detail and neither value echoed, so a request cannot be authorized as `A` and act on `B`. Comparison is at the declared `PIC X` width, and four forms mean "no key in the body" and are accepted: absent, all spaces or all `LOW-VALUES`, the path variable echoed back from a painted screen, and the `'*'` no-criterion image on the account and card screens that paint one. So authorize and audit on the path variable alone -- a gateway never has to read a body to find the key a request will act on. The other ten routes carry no key in their path -- `GET /api/menu`, `GET /api/admin/menu`, the three list screens `GET /api/cards`, `GET /api/transactions` and `GET /api/users`, and `POST /api/signon`, `POST /api/users`, `POST /api/transactions`, `POST /api/billpay` and `POST /api/reports` -- and state their criterion in the body only, so authorize those on the authenticated identity and the route.
* **Rate limiting, lockout and authentication monitoring**, for the reasons given with the seed credentials above.
* **Access control, retention and monitoring for the raw output channels.** Batch `SYSOUT` carries whole records by design -- `CBACT02C.cbl:78` is `DISPLAY CARD-RECORD`, so a full card number reaches standard output, and `CBCUS01C.cbl:78` displays the entire 500-byte customer record -- and the report and statement files carry cardholder data in the clear. Those bytes are the parity contract and cannot be masked here. Treat job output, `TRANREPT`, `STMTFILE` and `HTMLFILE` as cardholder-data stores: restrict who can read them, set a retention period, and log access.
* **Isolated delivery for the generated HTML statement.** `CBSTM03A` writes operator-supplied values into HTML without escaping, because the COBOL emits them raw and the record is a fixed 100 bytes; escaping would change the bytes and fail the gate. Serve `HTMLFILE` from an origin that shares nothing with an application session, or escape it in a renderer outside this boundary.
* **A supported Spring line, or a support contract.** Spring Boot 3.5 and Spring Framework 6.2 reached the end of OSS support on 30 June 2026. The migration pins Boot 3.x by plan, and 3.5.16 is the last published OSS 3.5.x release, so future fixes for the framework itself will not arrive through a parent upgrade inside that pin. Four transitive families are pinned forward to their fixed releases in `app/java/pom.xml` for exactly this reason, and that route does not extend to the framework. Obtain commercial support or authorize a move to a supported line.
* **A current JDK**, per the note under Prerequisites.
* **Driver provenance controls** for `LOADER_PATH`, per the note above.
* **Privacy governance.** This module implements no retention limit, no export, no erasure and no field-level encryption at rest, because the legacy application implements none and adding any of them would change observable behaviour. If the data is real, those obligations are the surrounding platform's.

**Inherited from the source and deliberately preserved.** These are not defects in the translation; they are the legacy behaviour, and the parity gate holds them in place. Each is recorded with its evidence in [docs/project-guide.md](docs/project-guide.md):

* Passwords are stored and compared as plaintext `PIC X(08)`, and the user-update screen paints the stored password into a field because `COUSR02C.cbl:169` moves it there.
* Sign-on distinguishes an unknown user from a wrong password.
* Response payloads project the screen exactly, so a field the 3270 showed an operator is a field the payload carries -- full card numbers included.
* Bill payment writes its transaction *before* debiting the balance and does not guard the debit on the write having succeeded, so a refused duplicate leaves a debited balance with no transaction record. This is `COBIL00C.cbl:211-235` faithfully, it is asserted positively by a parity case, and it means bill payment needs an external single-flight control before concurrent production use.

### Verification gates

* Branch coverage of at least 90% per module, enforced by JaCoCo at the `verify` phase. The build fails below the threshold rather than warning about it.
* A parity suite of 20 declarative cases per program, 560 in all, each diffed field by field against the expected records, return code and messages. A module is not complete until its diff count is zero across all 20 of its cases.
* The expected values in those cases are derived statically from the COBOL paragraphs, the copybook byte layouts, the JCL DD and PARM contracts, the BMS field definitions and the sample ASCII data. They are not captured from a run of the legacy programs, which needs a mainframe this build does not have.

### Relationship to the COBOL sources

app/cbl, app/cpy, app/bms, app/cpy-bms, app/jcl, app/proc, app/csd, app/ctl, app/catlg and app/data are the authoritative behavioural contract for the migration. They are read-only and unchanged by the Java module, because they are the only reference against which equivalence can be judged: where the two disagree, the COBOL is right and the Java is corrected.

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
