# CardDemo — Cloud-Native Credit Card Management Application

CardDemo is being modernized from a z/OS mainframe stack (COBOL / CICS / VSAM /
JCL) to a Python / AWS cloud-native stack. The target architecture splits the
original mainframe workload into two distinct cloud-native workload types that
share a single Aurora PostgreSQL database and a common Python domain model:

- **Online workload** — FastAPI service exposing REST and GraphQL endpoints
  (via `strawberry-graphql`), deployed as a Docker container on **AWS ECS
  Fargate**. Replaces the 18 online CICS COBOL programs and the CICS COMMAREA
  session with stateless JWT authentication.
- **Batch workload** — PySpark jobs executed as **AWS Glue 5.1** serverless
  Spark jobs (Spark 3.5.6, Python 3.11). Replaces the 10 batch COBOL programs
  and the JCL `COND` chaining with an **AWS Step Functions** state machine.
- **Database layer** — **AWS Aurora PostgreSQL-Compatible Edition**. The 10
  VSAM KSDS datasets and 3 alternate-index paths are modelled as 11 relational
  tables with B-tree indexes. Financial fields use `NUMERIC(15,2)` to preserve
  COBOL `PIC S9(n)V99` precision.
- **Supporting services** — AWS S3 (statement / report / reject output;
  replaces GDG generations), AWS SQS FIFO (report submission queue; replaces
  CICS TDQ `WRITEQ JOBS`), AWS Secrets Manager (database credentials and JWT
  signing key), AWS CloudWatch (logs, metrics, dashboards), AWS ECR (container
  image registry), AWS IAM (service-to-service authentication; replaces RACF).
- **CI / CD** — GitHub Actions workflows for linting (`ruff`), type-checking
  (`mypy`), testing (`pytest`), container build & ECR push, and Glue
  script upload & Step Functions update.

!!! note "Migration is in progress"
    At this checkpoint the repository contains the shared domain model
    (SQLAlchemy ORM, Pydantic v2 schemas, constants, utilities, configuration),
    the Aurora PostgreSQL migration scripts, the AWS infrastructure
    configuration (ECS task definition, Glue job configs, CloudWatch
    dashboard), the GitHub Actions workflows, and the Docker + Docker Compose
    local-dev scaffolding. The **FastAPI routers / services / middleware**
    (`src/api/`) and the **PySpark Glue job scripts** (`src/batch/jobs/`) are
    designed in detail in [the architecture document](architecture.md) and
    [the project guide](project-guide.md) but are not yet implemented in this
    checkpoint. The original COBOL source tree under `app/` is retained
    unchanged for traceability.

## Documentation map

- [Project guide](project-guide.md) — progress status, completed and
  pending work, development environment setup, command reference,
  glossary, and technology-version inventory.
- [Architecture](architecture.md) — target architecture deep-dive:
  component diagrams, COBOL-to-Python transformation rules, database
  schema, AWS service topology, design patterns, and project-structure
  reference.
- [Technical specifications](technical-specifications.md) — the original
  feature catalog (F-001 through F-022), business rules, data-model
  details, and batch-pipeline stage behaviour carried forward from the
  mainframe baseline.

## Repository reference

- Source repository: `blitzy-card-demo`
- License: [Apache 2.0](https://github.com/aws-samples/aws-mainframe-modernization-carddemo/blob/main/LICENSE)
- Original mainframe sources retained unchanged under `app/` for
  traceability.
