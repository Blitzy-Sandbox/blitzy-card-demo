# blitzy-card-demo

AWS CardDemo COBOL mainframe application modernized to Python 3.11 / FastAPI / PySpark on AWS (Aurora PostgreSQL, ECS Fargate, Glue, Step Functions).

## Architecture at a glance

- **API layer** — 18 online CICS COBOL programs → FastAPI REST + GraphQL endpoints on AWS ECS Fargate.
- **Batch layer** — 10 batch COBOL programs → PySpark jobs on AWS Glue 5.1, orchestrated by AWS Step Functions.
- **Database** — 10 VSAM KSDS datasets (plus 3 alternate-index paths) → 11 Aurora PostgreSQL tables with B-tree indexes.

See [architecture.md](architecture.md) for the detailed target architecture, including COBOL-to-Python transformation mappings, the 5-stage batch pipeline, the database schema, and the AWS service topology. The [project guide](project-guide.md) captures migration status, and the [technical specifications](technical-specifications.md) carry forward the feature catalog (F-001 through F-022) and business rules from the mainframe baseline.
