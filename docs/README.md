# Documentation Hub — blitzy-card-demo

This directory (`docs/`) is the documentation hub for the **blitzy-card-demo** repository — the AWS CardDemo COBOL mainframe application migrated to Java 25 + Spring Boot 3.x. It hosts the MkDocs-based documentation site content that integrates with Backstage TechDocs for automated documentation publishing. The site is published under the name **blitzy-card-demo** as configured in [`mkdocs.yml`](../mkdocs.yml).

## Content Map

The documentation site navigation is explicitly defined in [`mkdocs.yml`](../mkdocs.yml) (lines 2–5). The following pages compose the published site:

| Nav Label | File | Description |
|-----------|------|-------------|
| Home | [index.md](index.md) | Project landing page with a brief description of the blitzy-card-demo repository |
| Project Guide | [project-guide.md](project-guide.md) | Comprehensive project guide covering migration status, test results, compliance review, development setup, runtime validation evidence, risk assessment, and appendices with glossary and technology versions |
| Technical Specifications | [technical-specifications.md](technical-specifications.md) | Authoritative technical migration blueprint defining the architecture, scope, file-by-file transformation plan, dependency inventory, observability requirements, validation gates, and implementation contract for the COBOL-to-Java modernization |

> **Note:** This `README.md` itself is a GitHub-rendered file for repository browsing and is **not** included in the MkDocs navigation. It is not a TechDocs page.

## Documentation Pipeline

The documentation site is built using **MkDocs** as the static site generator, configured in [`mkdocs.yml`](../mkdocs.yml) at the repository root. Two plugins are enabled to extend its capabilities:

1. **`techdocs-core`** — Backstage TechDocs wrapper plugin that bundles the Material theme, monorepo support, admonitions, and Python Markdown extensions for seamless Backstage integration.
2. **`mermaid2`** — Mermaid.js diagram rendering plugin that enables flowcharts, sequence diagrams, and other visualizations from fenced code blocks in Markdown.

### Backstage Integration

The repository is registered as a Backstage component via [`catalog-info.yaml`](../catalog-info.yaml) at the repository root. The TechDocs annotation `backstage.io/techdocs-ref: dir:.` points to the repository root where `mkdocs.yml` resides, enabling Backstage to locate and build the documentation automatically.

TechDocs builds and publishes the documentation site from the contents of this `docs/` directory whenever changes are pushed to the repository.

> Source: [`mkdocs.yml`](../mkdocs.yml), [`catalog-info.yaml`](../catalog-info.yaml)

## Building Documentation Locally

### Prerequisites

Install the required MkDocs plugins:

```bash
pip install mkdocs-techdocs-core mkdocs-mermaid2-plugin
```

### Build

Generate the static documentation site from the repository root (where `mkdocs.yml` resides):

```bash
mkdocs build
```

Output is generated in the `site/` directory.

### Preview

Start a local development server with live reload for iterative editing:

```bash
mkdocs serve
```

The server starts at `http://localhost:8000`. The `mermaid2` plugin renders Mermaid diagrams client-side, so diagrams appear correctly in the local preview without any additional tooling.

## Contributing Documentation

All documentation pages are standard Markdown files (`.md`) stored in this `docs/` directory. When authoring or editing pages, follow these guidelines:

- **Markdown format:** Use GitHub-flavored Markdown with ATX-style headings (`#`, `##`, `###`).
- **Diagrams:** Mermaid diagrams are supported via fenced code blocks with the `mermaid` language identifier. They are rendered by the `mermaid2` plugin.
- **Adding a new page:** Create a `.md` file in this directory and add a corresponding entry to the `nav` section in [`mkdocs.yml`](../mkdocs.yml). The navigation structure is explicitly defined — pages are not auto-discovered.
- **Existing navigation pattern:** Follow the label-to-file mapping style already present in `mkdocs.yml`:

  ```yaml
  nav:
    - Label: filename.md
  ```

- **General contribution guidelines:** See [`CONTRIBUTING.md`](../CONTRIBUTING.md) for the repository-wide contribution process, including issue reporting and pull request procedures.

Documentation changes merged into the default branch are automatically reflected in the Backstage TechDocs site.

## Troubleshooting

### Missing plugins

If `mkdocs build` fails with plugin import errors such as `Plugin 'techdocs-core' not found` or `Plugin 'mermaid2' not found`, ensure both required packages are installed:

```bash
pip install mkdocs-techdocs-core mkdocs-mermaid2-plugin
```

### Mermaid diagrams not rendering

Verify the `mermaid2` plugin is listed under `plugins` in [`mkdocs.yml`](../mkdocs.yml). Ensure that diagram code blocks use the correct language fence:

````markdown
```mermaid
graph LR
    A --> B
```
````

### Navigation not updating

After adding a new documentation page, ensure it is listed in the `nav` section of [`mkdocs.yml`](../mkdocs.yml). The navigation structure is explicitly defined rather than auto-discovered — a new `.md` file in this directory will not appear in the site navigation until it is added to `nav`.

### TechDocs build failures in Backstage

Verify the following in [`catalog-info.yaml`](../catalog-info.yaml):

- The annotation `backstage.io/techdocs-ref: dir:.` is present and correctly points to the repository root.
- The file [`mkdocs.yml`](../mkdocs.yml) exists at the repository root alongside `catalog-info.yaml`.
- All files referenced in the `nav` section of `mkdocs.yml` exist in this `docs/` directory.
