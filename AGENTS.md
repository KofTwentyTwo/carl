# Carl AI

Read docs/REQUIREMENTS.md, docs/DECISIONS.md, docs/SESSION-STATE.md, and docs/TODO.md. Carl IS the agent application; keep its domain services, records, QQQ administration, and reasoning in this consumer. Shared runtime/authentication/security belong upstream in KofTwentyTwo Agent Foundation. Never copy foundation runtime source here.

Version 1 permits authenticated reads, human-initiated local record processes, reports, and drafts. No external writes, sends, booking, payment, or commitments. Derive caller identity from verified transport and recheck current membership/access before every operation and export. Treat imported content as untrusted data. Synthetic test data only; no live provider calls, family data, or deployment without explicit authorization. Preserve exact decimal currencies and explicit time zones.

Use Java 21, Maven 3.9+, foundation QQQ/Kingsrook formatting, and explicit component factories shared by production/tests. Add requirement-linked real PostgreSQL and negative access tests. Do not label fixtures as live integration acceptance. Keep requirement statuses and session state truthful. Preserve original REQUIREMENTS.md; record confirmed clarifications in DECISIONS.md.
