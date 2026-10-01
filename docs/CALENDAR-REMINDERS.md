# Synology calendar and reminders contract

Owner decision, September 29, 2026: Carl will have read/write access to a shared CalDAV calendar and reminders hosted on Synology, and may maintain them as needed. This replaces the earlier Apple iCloud provider choice and supersedes the original calendar-read-only restriction for the designated shared planning workspace only. Financial execution remains human-led; no payments, credit applications, purchases, vendor messages or external bookings are authorized by calendar access.

Carl has standing authority to manage plan-related events and reminders in the configured shared collections. Do not request redundant per-entry approval. Real endpoint, Synology/Calendar versions, specific calendar/task collection identifiers, credentials and audience mapping are still required before live qualification. Store secret names only in repository configuration examples; do not request passwords in chat or use arbitrary model-provided destinations.

| ID | Requirement |
| --- | --- |
| SYN-01 | Discover and validate the configured Synology CalDAV service and allowlisted shared collections, advertised capabilities, verified TLS, authentication and minimum necessary permissions. Confirm VEVENT and task/reminder support for the actual installed versions. Do not assume Apple Reminders interoperability or VTODO support without evidence. |
| SYN-02 | Read events/tasks and preserve recurrence, exceptions, cancellations, all-day/date-only values, time zones and daylight-saving behavior. Distinguish event alarms, standalone tasks and assigned Carl plan steps. Expose last successful sync, errors and incomplete coverage. |
| SYN-03 | Create/update plan events and reminders using typed application capabilities with caller/standing-authority checks. Keep stable plan-item-to-provider UID/resource mappings, source plan version, collection, ETag and synchronization state. Calendar text contains permitted shared-scope information only; no private financial detail is copied merely because Carl can read it. |
| SYN-04 | Use conditional creation/update and durable operation identities. Reconcile ambiguous timeouts against the same resource identity before retrying; never create a second item with a fresh UID just because the response was lost. Mark partial/failed/unknown outcomes truthfully. Preserve authenticated audit attribution without raw sensitive payloads. |
| SYN-05 | Reconcile household edits and task completion from the shared service with Carl's plan state. Detect concurrent edits using provider versions/ETags; preserve human changes and surface unresolved conflicts instead of overwriting stale state. Completion of a reminder means the task was reported complete, not that a loan/payment/appointment was independently verified. |
| SYN-06 | When a plan changes, update or retire linked Carl-managed items with provenance and valid state transitions. Do not delete unrelated calendar contents, alter other collections or change sharing/permissions. Keep plan history and do not resurrect deliberately removed items through blind synchronization. |
| SYN-07 | Bound request time, retries, payloads, pagination, recurrence windows and concurrency; protect XML/iCalendar parsing. Allow network operations only to configured/validated endpoints and same-authorized collection resources; reject untrusted cross-origin hrefs/redirects. Separate read capabilities from governed calendar/task mutation capabilities. Ordinary read tools cannot hide calendar writes. |
| SYN-08 | Do not send invitations or vendor communications through calendar fields. Plan entries are internal organizational items, not external bookings; reject arbitrary attendees/organizers, scheduling outbox destinations and email/SMS alarm actions. Client-side display alarms may be supported explicitly. |
| SYN-09 | Show plan/calendar synchronization in QQQ and the family API, with permission-scoped event/task links and retry/conflict controls. Calendar/reminder changes follow the agreed plan/workflow and standing authority; read-only conversation paths remain truthful about which operations were actually performed. |

## Acceptance

| Test | Required result |
| --- | --- |
| SYN-AT-01 | Controlled CalDAV server discovers only allowed collections and exercises create/read/update/task completion; unsupported component capabilities are surfaced, not silently emulated as success. |
| SYN-AT-02 | Retry after a server-side successful write with a lost response finds the same resource and creates no duplicate; restart recovers durable pending/unknown state. |
| SYN-AT-03 | A family edit between read and write causes a conditional conflict; no stale overwrite occurs. Check-in completion and verified financial outcomes remain distinct. |
| SYN-AT-04 | Recurrence, exceptions, canceled/all-day events and DST match fixed expected instants/dates; plan revisions update linked entries without touching unrelated items. |
| SYN-AT-05 | Private-record content and external attendee/scheduling destinations are rejected; guessed resources, wrong collection, revoked permissions and hostile XML/redirects cannot escape scope. |
| SYN-AT-06 | Authentication expiration/rate limits/timeouts preserve existing local data and show last successful sync plus truthful partial/unknown outcomes. |
| SYN-AT-07 | Actual packaged QQQ/family conversation creates a synthetic plan, synchronizes its entries through the qualified controlled provider, receives an external completion/edit and exposes matching authorized plan state. |
| SYN-AT-08 | Separately authorized live qualification verifies the actual Synology version, collection ACLs, event/task behavior and chosen family clients with non-sensitive test entries. Until then live compatibility is BLOCKED. |

## Official reference check

Checked September 29, 2026: Synology documents Calendar task management and CalDAV event compatibility. Its client guidance specifically notes compatibility limitations with newer macOS Reminders; that is a reason to test the chosen family client/task flow, not to assume all Apple reminder clients work.

- [Synology Calendar specifications](https://www.synology.com/en-br/dsm/7.3/software_spec/calendar)
- [Synology CalDAV client setup and limitations](https://kb.synology.com/en-global/DSM/tutorial/How_to_Sync_Synology_Calendar_with_CalDAV_Clients)
- [Synology Calendar API guide](https://kb.synology.com/en-sg/DG/Calendar_API_Guide/4)

Prefer the standard CalDAV contract where the service supports it. A Synology-specific task API is a possible compatibility decision, not an assumed dependency or permission to use unrestricted NAS administration.
