# Carl Quick Search and ESB

Carl integrates the foundation's native Quick Search and ESB QBits. Both are disabled by default. Configure them only through the trusted application environment; conversation text, imported files, browser inputs, and broker payloads cannot configure providers.

| Environment variable | Purpose |
| --- | --- |
| `CARL_QBITS_SEARCH_ENABLED` | Explicit `true` or `false`; defaults to `false`. |
| `CARL_QBITS_OPENSEARCH_HOST` | Operator-selected OpenSearch host; required when search is enabled. |
| `CARL_QBITS_OPENSEARCH_PORT` | Port from 1 through 65535; required when search is enabled. |
| `CARL_QBITS_OPENSEARCH_INDEX` | Dedicated lowercase index identifier, at most 64 characters. |
| `CARL_QBITS_OPENSEARCH_SSL` | TLS is used unless explicitly set to `false`; disable only for a controlled local fixture. |
| `CARL_QBITS_OPENSEARCH_USERNAME` | Optional protected OpenSearch credential reference. |
| `CARL_QBITS_OPENSEARCH_PASSWORD` | Optional protected OpenSearch credential reference. |
| `CARL_QBITS_ESB_ENABLED` | Explicit `true` or `false`; defaults to `false`. Requires search. |
| `CARL_QBITS_BROKER_URL` | Operator-selected Artemis `tcp://` URL without embedded credentials. |
| `CARL_QBITS_BROKER_USERNAME` | Optional protected broker credential reference. |
| `CARL_QBITS_BROKER_PASSWORD` | Optional protected broker credential reference. |

Keep credential values in the authorized secret environment and out of committed files, command transcripts, and logs. The OpenSearch index is trusted infrastructure containing private titles; protect its access, backups, and lifecycle accordingly. It is reconstructible from PostgreSQL and is never Carl's authoritative business store.

## Search behavior

Carl indexes only titles from the fixed native bill, vendor, work-item, account, transaction, budget, rental-property, and calendar tables. Reports, drafts, attachments, and conversational text are not indexed. The application performs an initial reconciliation and repeats it every 30 seconds. Each table is bounded to 100,000 indexed records; exceeding the bound degrades refresh rather than silently claiming completion. Search authorization reads are bounded to 50,000 permitted record IDs per table and fail closed above that limit.

Every native search resolves the verified caller, filters candidate IDs through current domain permissions, and reloads result titles from authoritative records. Index highlights cannot supply private text. A full member/permission revision is captured across the request: if access changes while results are assembled, Carl replaces the entire response with a fixed denial. Hiding a search control does not provide authorization.

The indexer reports starting, ready, degraded, and stopped states. A search-provider outage can make search unavailable; existing PostgreSQL records remain available through their authorized domain views. Reconciliation removes obsolete indexed records only after completing that table's current scan.

## Fixed ESB invalidation

The native provider is `carlBroker`, its queue destination is `carlIndexChanges`, and its allowlisted process is `carlRefreshSearchIndex`. The trigger performs the same fixed title reconciliation under the narrowly permitted `carl-index-service` identity. It allows three attempts. Event content cannot choose a caller, SQL, table, provider, or financial action. Ordinary household roles cannot invoke this internal process. Native ESB administration and health remain subject to foundation permissions and payload-route restrictions.

## Evidence boundary

`CarlQBitsNativeTest` uses actual PostgreSQL, OpenSearch, verified HTTP authentication, and the native search endpoint. It proves real title indexing, unauthorized-member exclusion, and whole-response rejection when a permission is revoked after an earlier result was accumulated. It does not establish live household-provider acceptance. Broker execution and packaged browser checks have separate evidence; inspect [Session state](SESSION-STATE.md) before treating them as qualified. Run the normal consumer quality gates for the full integrated application; these optional configurations do not replace them.
