# Bill CSV import

Use [the synthetic template](templates/bills.csv). In Carl administration, open **Preview Bill Import**, paste the CSV text, and inspect the validated rows and logical row errors. Then use **Apply Reviewed Bill Import** with the same text, a stable source name, and the supplied logical request UUID. This workflow accepts at most 10,000 data rows and 1,000,000 characters. It is separate from the two-file Monarch financial import.

| Column | Meaning |
| --- | --- |
| `source_id` | Stable invoice identity, up to 200 characters. Prefer the real provider identifier. |
| `vendor` | Required label, at most 200 characters; this is evidence, not verified contact information. |
| `description` | Required description, at most 2,000 characters. |
| `amount` | Nonnegative exact decimal with the currency's supported precision; no commas, symbol or exponent. Blank means unknown, never zero. |
| `currency` | Explicit supported ISO currency code; currencies are never summed together. |
| `due_date` | ISO date `YYYY-MM-DD`, or blank when unknown; interpreted as a date in the configured household display zone. |
| `status` | `UNPAID`, `PAID_ASSERTED`, `DISPUTED`, or `UNKNOWN`. Asserted payment is not independent verification. |
| `visibility` | `PRIVATE` or `FAMILY`, still subject to current household/domain permissions. |

The header and order must match exactly. Standard double-quoted CSV fields support commas, embedded newlines and doubled quote escaping. Row numbers identify logical CSV records including the header. NUL, malformed quoting, excess columns, duplicate identities within a batch, unsupported precision and invalid values reject the import. Validation errors are reported before persistence; any changed/ambiguous source identity discovered during application rolls back the entire file. Correct the input or explicitly correct the existing local bill with attribution.

Deduplication is scoped to household, importing member and source name. Identical files reuse the saved import; repeated unchanged source IDs add no bills. A missing ID uses a hash of vendor, description, exact amount, currency and due date. This fallback can collide for legitimately repeated identical bills, so such rows need explicit unique source IDs and review. Changing fallback facts can create a new identity: do not use that to silently correct an existing bill. Keep the same source name across exports. Changed source IDs or source names cannot establish that two records are the same bill.

Retries retain the original UUID and exact payload; changing a payload under an existing UUID conflicts. If the client times out, query the recorded import/request outcome before generating another request. Committed records, original source/row references and attributed local corrections survive restart. Importing a paid assertion or passing a due date never proves payment. Development examples contain synthetic information only.
