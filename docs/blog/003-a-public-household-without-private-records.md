# Test Carl with a household we can publish

Status: draft. All examples are fictional. Packaged integration and live household qualification are separate from these controlled tests; recheck the current acceptance evidence before publication.

A one-bill demonstration cannot exercise the questions Carl will face. A useful test household needs transaction history, balances, high-interest debt, transfers, recurring expenses, a primary home and rental properties. It also needs missing information, conflicting imports and private records that another family member cannot see.

Publishing copies of real exports with renamed merchants would retain too much information. Carl's public fixture is authored from scratch. Only supported CSV columns and broad workload scale informed its shape. Fictional people, accounts, dates, amounts, contracts and source identities come from a deterministic generator that has no private-file input. There is no reversible mapping back to household records, and this method makes no mathematical anonymity claim. The fixture loader checks the complete canonical public bundle before creating records; a modified transaction, manifest, event or reminder fails that check.

The resulting household has 36 accounts, 10,920 transactions and 22,365 daily balance observations. Its primary home, duplex and rental house link to the same account and property records Carl uses for financial reporting. Typed home profiles distinguish use, supplied mortgage terms and unknown facts. Rental units, vacancy and rent arrears remain rental records; owning a home does not imply rental income. These links prevent a second property or mortgage representation from silently doubling the balance sheet.

Tests reconcile every available account observation using exact decimal arithmetic. They also retain omitted source rows, require explicit acceptance of corrections, reject conflicting balances and recheck access to saved artifacts. A valid repeat seed creates no duplicates. Controlled PostgreSQL and native administration tests establish these rules; they do not establish real account coverage, live model interpretation, credit approval or tax treatment.

The [public fixture guide](../PUBLIC-SYNTHETIC-DATA.md) documents regeneration and testing. [Acceptance evidence](../ACCEPTANCE.md) distinguishes implemented behavior, integration holds and production qualification. A public test household helps us inspect Carl without publishing our household's financial history.
