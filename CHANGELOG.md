# Changelog

## [Unreleased]

### Fixed

- End conversations that fail before any record is saved (budget, deadline, iteration cap, provider or unusable model output) as FAILED with a public reason and guidance instead of UNKNOWN, and record a public-safe `failure_code` for FAILED and UNKNOWN workflows (domain migration V63). Revocation and failures after a possible write stay UNKNOWN.

## [0.1.0] - 2026-09-30

### Added

- Initial Carl AI household agent with scoped financial records, planning, reports, drafts, native administration and explicit family workflows.
- Native docked chat, app/table-only navigation, System diagnostics and explicitly saved dashboard selections on qualified Next UI RC.8 development artifacts.
- Reproducible public fictional household imports and typed home/mortgage profiles linked to authoritative accounts with protected revision history.
- Synthetic PostgreSQL, native HTTP and packaged browser qualification; signed release evidence for development, release candidates and stable versions.

### Security

- Upgrade the pinned container’s three affected OpenSSL packages to Ubuntu’s fixed version for CVE-2026-84782; refreshed ARM64 and AMD64 scans pass with unchanged application bytes.
- Preserve read-only financial/vendor boundaries, verified family identity, per-record permissions and access-revocation checks. Live provider and production qualification remain separate.
