# CardDemo — Java 25 LTS Implementation

This tree contains the **Java 25 LTS** source-to-source translation of the AWS
CardDemo mainframe application. The original COBOL source tree at
[`../app/`](../app/) is preserved unchanged as the **reference implementation**
and as the source of golden-record test fixtures.

The architecture, file inventory, and translation rules are documented in
the Agent Action Plan delivered with this repository.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | **25 LTS** | Eclipse Temurin 25.0.3+ recommended. No `--enable-preview` is required or permitted (AAP §0.7.4). |
| Apache Maven | **3.9.9+** | `mvn -version` should report `Java version: 25.x`. |

On Ubuntu 25.10 the setup used by this baseline is:

```bash
# Install JDK 25 from Adoptium Temurin
curl -fsSL -o /tmp/jdk-25.tar.gz \
  "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz"
sudo tar -xzf /tmp/jdk-25.tar.gz -C /opt && sudo ln -sfn /opt/jdk-25.0.3+9 /opt/jdk-25
export JAVA_HOME=/opt/jdk-25
export PATH=$JAVA_HOME/bin:$PATH

# Install Maven 3.9.9
sudo apt-get install -y --no-install-recommends maven
```

## Build and Test

From the `java/` directory:

```bash
mvn -B clean verify
```

This will:
1. Compile all 7 Maven modules against Java 25 (`<release>25</release>`).
2. Run the unit tests, including the jqwik property-based tests and the golden-record harness.
3. Produce shaded executable jars in `carddemo-app/target/`, one per JCL `EXEC PGM=` step.

## Run an Application

Each program named in the original JCL is packaged as a single shaded jar:

```bash
java \
  -XX:+UseCompactObjectHeaders \
  -XX:+UseShenandoahGC \
  -XX:ShenandoahGCMode=generational \
  -jar carddemo-app/target/carddemo-app-<version>-shaded.jar <args>
```

JVM flags are mandated by AAP §0.3.4:

- `-XX:+UseCompactObjectHeaders` — JEP 519 finalized in Java 25 (~20–30 % heap reduction on small-record workloads).
- `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` — JEP 521 finalized in Java 25 (low-pause batch).

## Module Layout

| Module | Purpose |
|--------|---------|
| `carddemo-domain` | Pure domain — records from copybooks, sealed types from REDEFINES / 88-levels, repository ports, `Decimals` utility. No runtime dependencies. |
| `carddemo-application` | One class per COBOL `PROGRAM-ID`. |
| `carddemo-adapter-file` | `java.nio.file` fixed-width readers/writers, EBCDIC IBM-1047 default. |
| `carddemo-adapter-db` | Optional JDBC adapter (empty by default). |
| `carddemo-batch` | Batch drivers, virtual-thread fan-out, `ScopedValue` batch context. |
| `carddemo-app` | Main entry points (one per JCL step); shaded jars. |
| `carddemo-tests` | Golden-record harness, jqwik property tests, JFR baselines. |

## Configuration

Runtime configuration uses a 12-factor approach (AAP §0.7.2). See
[`application.properties.example`](application.properties.example) for the full
list of supported settings (file paths, codepages, optional DB credentials).

## Migration Notes

Deviations from idiom-for-idiom translation, suspected COBOL bugs translated
faithfully, dead-code translations, and TODO markers from the user prompt are
logged in [`MIGRATION_NOTES.md`](MIGRATION_NOTES.md).

## Reference Implementation

The original COBOL source tree at [`../app/`](../app/) is **immutable** for this
refactor. Per AAP §0.2.2 it stays in the repository as the reference
implementation and as the source for golden-record test fixtures.
