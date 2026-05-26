# blitzy-card-demo

The **AWS CardDemo** mainframe COBOL application — 28 COBOL programs, 28 shared copybooks, 17 BMS mapsets, and 29 JCL job members — migrated to **Java 17+ / Spring Boot 3.x** on AWS-native managed services. The migration is layered and additive: the original COBOL source tree under `app/` is preserved frozen as the authoritative reference for behavioral parity validation, and a parallel Java/Spring Boot implementation lives under `src/`, `pom.xml`, and `infrastructure/`.

## Target Stack

The runtime is deployed to **Amazon ECS Fargate** behind an Application Load Balancer (ALB) protected by **AWS WAF + Shield**, persists data in **Amazon RDS Multi-AZ for PostgreSQL 16+** (encrypted with **AWS KMS** customer-managed keys), caches account balances in **Amazon ElastiCache Redis**, exchanges transaction events via **Amazon MSK (Kafka)** topics partitioned by account ID, orchestrates end-of-day batch with **AWS Step Functions + AWS Batch + AWS Glue**, stores batch outputs in **Amazon S3** (SSE-KMS, versioned, lifecycle policies), manages credentials via **AWS Secrets Manager**, indexes audit logs and transaction events in **Amazon OpenSearch**, captures an immutable audit trail of AWS API activity via **AWS CloudTrail**, and exposes metrics and structured logs via **Amazon CloudWatch** (Container Insights + Micrometer registry). PII and financial-data exposure in S3 is continuously scanned by **Amazon Macie**. The architecture is **PCI-DSS aligned**.

The application is **demo-ready by May 20, 2026**.

## Documentation

- **[Project Guide](project-guide.md)** — Current project status, target-stack summary, build and run instructions, environment variables, and developer guidance.
- **[Technical Specifications](technical-specifications.md)** — Full technical contract: architecture, COBOL-to-Java transformation rules, VSAM → RDS migration strategy, JCL → Step Functions orchestration, Secrets Manager rotation, MSK topic ordering, PCI-DSS posture, and dependency inventory.
