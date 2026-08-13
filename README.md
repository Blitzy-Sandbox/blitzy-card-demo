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

1. Java 21 (LTS) -- verified with OpenJDK 21.0.11
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

The programs listed under Online below are served as REST resources under `/api`, one per CICS transaction -- `POST /api/signon`, `GET /api/menu`, `GET /api/accounts/{acctId}`, `GET /api/cards`, `GET /api/transactions`, `POST /api/billpay`, `GET /api/users` and their siblings. CICS is pseudo-conversational, so the migration keeps no server-side session: the communication area, the key that was pressed and the screen's own field values all travel in the request and response payloads, and every reply carries the state the next call needs.

To start the service locally with no external data source at all, run it on the fixture-backed `test` profile. That profile reaches its in-memory settings through a classpath import that lives in the test tree, so the JVM that runs has to carry `target/test-classes`: `spring-boot:run` forks a JVM that does not, and asking the plugin for the test classpath is not enough. From `app/java`

```shell
mvn -B test-compile
mvn -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
     com.vsergeychik.carddemo.CardDemoApplication --spring.profiles.active=test
```

Started that way the context comes up and `/api` answers with no mainframe and no database server in sight. What that command does not do is create a dataset. This module issues no DDL at all, by design -- that is the same guarantee the production deployment depends on -- so the profile only says *where* each dataset lives, and an in-memory database starts with no relation in it. Until the relations exist a data-backed call fails rather than reading nothing: sign-on answers `Unable to verify the User ...` because the backend reported `SQLSTATE 42S02`, which the repository maps to file status `9000`, and `accountBalanceJob` abends with `ERROR OPENING ACCTFILE`, `RETURN-CODE=12` and process exit code 12. The test suite never meets that, because each test creates and seeds the relations it needs itself, which is why `mvn -f app/java/pom.xml clean verify` needs none of the steps below.

To give a local run some data, create one relation per dataset -- the record image in column 1, the copybook width, one row per fixed-width record -- and point the run at a database that outlives the provisioning step, because the profile's own in-memory database is created fresh per run. The dataset names this profile uses are listed in `app/java/src/main/resources/application-test.yml` and the records come from `app/data/ASCII`. The account master is shown here; every other dataset follows the same two steps. From `app/java`

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

The CICS region identity is a deployment input for the same reason. `COSGN00C` obtains two of its eleven screen fields from `EXEC CICS ASSIGN APPLID` and `EXEC CICS ASSIGN SYSID`, which have no Java equivalent, so they are supplied through `CARDDEMO_CICS_APPLID` and `CARDDEMO_CICS_SYSID`. Neither has a default: both fields are compared byte for byte by the parity suite, so a deployment that states no region is refused at startup rather than painting eight spaces into a field a real region would have filled.

The module reaches the existing datasets over plain JDBC and changes nothing about how they are stored: no DDL, no schema migration, no ORM and no new database. Record layouts stay exactly as the copybooks in `app/cpy` define them, which is why the dataset and copybook table above is the reference every Java record width is checked against, and why the code pages are named explicitly -- IBM037 for the EBCDIC datasets, US-ASCII for the sample text files -- rather than left to a platform default.

`application-test.yml` rebinds all 27 dataset bindings onto in-memory test data, so the test suite runs with no external database and no mainframe connectivity. Seventeen of them are backed by the nine fixed-width fixtures derived from app/data/ASCII -- one fixture reaches several DD names where the legacy estate addresses one dataset under more than one name, and `cardxref`'s 36-byte rows are padded up to the 50 bytes CVACT03Y declares. `USRSEC` is seeded inline from the ten sign-on rows of app/jcl/DUSRSECJ.jcl, padded from 57 bytes to 80. The remaining nine -- `DALYREJS`, `DATEPARM`, `HTMLFILE`, `STMTFILE`, `SYSTRAN`, `TRANFILE`, `TRANREPT`, `TRANSACT` and `TRNXFILE` -- hold nothing at the start of a run: they are what a run produces rather than what it reads, and each test seeds only what its own case declares. The seeding is the tests' own work rather than the profile's: a test declares the datasets its case needs, loads its rows, and holds them privately for the duration of that one case.

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


