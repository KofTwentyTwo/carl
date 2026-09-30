# Carl AI

Carl helps the family get out of debt, improve rental/tax planning, and prepare to invest. It is a standalone family agent application: its domain model, PostgreSQL records, deterministic capabilities, reasoning, QQQ administration, and workflows form one product. Carl must understand permitted debts, assets, cash flow, budgets and rental properties, compare financial plans, and assist with tax preparation and ownership-structure analysis. Version 1 also reads bills, Synology CalDAV calendars/reminders and vendor records, produces reports, and prepares local drafts. It may maintain the authorized shared Synology plan calendar/reminders. It cannot send vendor messages, book external services, pay, purchase, trade, or make commitments.

Implementation is in progress. PostgreSQL, native QQQ HTTP/browser, permission and deterministic calculation checks pass for implemented slices; the full requirements are not complete. Live integration and production qualification are not claimed. Start with [requirements](docs/REQUIREMENTS.md), [financial planning requirements](docs/FINANCIAL-PLANNING-REQUIREMENTS.md), [confirmed decisions](docs/DECISIONS.md), [implementation work](docs/TODO.md), and [acceptance evidence](docs/ACCEPTANCE.md). Development uses synthetic data only. Foundation 0.4.0 is remotely qualified; this consumer still needs authorized Maven package access and immutable-pin verification.


## Build and local verification

Use Java 21, Maven 3.9+ and Docker for disposable PostgreSQL tests. Run `mvn spotless:apply verify`; the distribution is `target/agent`. Open this directory as a Maven project in IntelliJ, select Java 21 for the project and Maven runner, and run the same `verify` lifecycle. `AgentApplication.main` and tests use the same component factory.

The parent is pinned to qualified `com.kof22:kof22-agent-parent:0.4.0`. The local cache contains the exact nine release artifacts, verified against signed publication hashes; a clean public-consumer checkout still needs separately authorized private Maven package credentials. The final consumer build is verified separately from upstream publication. The consumer repository token alone is not assumed to grant cross-repository package access. Supply secrets privately through the environment and use `--settings config/maven/settings.xml.example`; do not store a token in this repository.

Run packaged browser checks with `npm ci --prefix scripts/e2e`, `npx --prefix scripts/e2e playwright install chromium`, then `bash scripts/verify-browser.sh` after the Maven build. The fixture uses synthetic PostgreSQL records, local TLS/OIDC and no model/provider calls. Optional `MAVEN_SETTINGS` and `MAVEN_REPO` select an isolated qualified development cache.

See [operator instructions](docs/OPERATOR-RUNBOOK.md), [family API integration](docs/CLIENT-INTEGRATION.md), and [acceptance evidence](docs/ACCEPTANCE.md). Do not connect personal sources or expose this development application publicly until the outstanding acceptance and deployment work is qualified.

The [build journal drafts](docs/blog/README.md) document Carl’s creation using synthetic examples and explicit qualification boundaries.
