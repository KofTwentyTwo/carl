# Carl is the application

Status: draft. Technical claims describe the implementation direction and synthetic verification; this is not a production-readiness announcement.

Carl AI has a clear order of priorities: help the family reduce debt, understand the rentals and tax structure, then plan for investing. That order matters whenever Carl evaluates a purchase or a new borrowing option. An unused credit limit is borrowing capacity. It is not a spending budget.

The first architectural decision is equally important: Carl is the application. His structured records, deterministic calculations, reasoning, workflows and interfaces belong to one standalone consumer repository. The KofTwentyTwo Agent Foundation supplies shared runtime, authentication, governed tool execution, audit and administration infrastructure. Carl supplies the household capabilities.

That distinction prevents conversation history from becoming an improvised financial database. A statement balance has an account, currency, date and evidence. A hypothetical refinancing offer has terms and assumptions. A generated explanation does not turn an offer into approval or a payment-like transaction into proof that a debt was discharged.

Carl uses Java services for calculation and authorization, PostgreSQL for persistent records, and native QQQ for administration. His language-model component invokes typed, caller-aware capabilities and explains their results. Administration and conversation use the same authoritative records and permissions.

Consider a synthetic debt-transfer example. Moving USD 200 from a USD 500 card leaves USD 300 on that card. A financed USD 6 fee adds to debt, while an upfront USD 10 fee reduces cash. Other debts remain in the portfolio. If a comparison drops the retained balance or treats borrowing proceeds as income, its apparent savings are misleading.

The calculator therefore separates original principal, financed fees, upfront cash requirements, interest, payment dates and remaining balances. A short projection with unpaid balances does not establish lifetime savings. An advertised zero-percent rate is also different from a deferred-interest arrangement: supported deferred-interest calculations retain conditional accrual and apply the recorded expiry conditions. Unsupported contract assumptions produce an incomplete result.

Plans are human workflows. Carl records selected assumptions, people, tasks, deadlines and reported progress. Changes to source records make dependent outputs stale; recalculation and renewed agreement are explicit. Humans submit applications, sign agreements, purchase and move money outside Carl. The separately designated shared Synology calendar and reminder workspace has its own standing authority for planning outputs.

Privacy belongs in the services, before model context is constructed. Private conversations are the default. A shared output uses information accessible to every intended recipient, and stored reports and exports recheck current access. A family label does not override a private source account.

Development evidence uses synthetic records and controlled providers. Deterministic tests, actual PostgreSQL tests, packaged browser checks and artifact scans establish specific behaviors. They do not qualify real provider accounts, tax rules, model handling of family data or a deployment. Those are separate milestones with explicit evidence.

The following posts will trace the vertical slices: importing records, comparing debts and cash requirements, sharing permitted results, documenting human execution, and qualifying the packaged application. Each post should explain what worked, what failed and what remains unqualified.
