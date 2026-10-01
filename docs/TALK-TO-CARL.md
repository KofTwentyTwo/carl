# Talk to Carl

Carl's **Overview** app includes **Talk to Carl**, **Read Carl's Response**, and **Continue Talking to Carl**. These are native QQQ processes over the same authenticated application and persistent workflow service used by supported family clients.

Enter a **Message to Carl** and leave **Conversation visibility** as **Private** for a conversation accessible only to you. To share explicitly, choose **Shared** and select a currently available family member in **Share with (shared conversations only)**. When multiple other members are available, an explicitly labeled choice shares with everyone listed. Carl includes you automatically. The selected audience is fixed for that conversation; reports and drafts use the participants' common authorized scope. Sharing does not grant access to private source records.

The receipt distinguishes completed, partial, failed, pending, and unknown outcomes. Use **Read this response** or **Read Carl's Response** to check the same saved message. Checking a pending or unknown message does not restart the model or repeat a financial operation. An unknown outcome requires review; it is not evidence that nothing was saved. After a complete or partial response, **Continue this conversation** submits a new explicit message with the permitted history. **Ask a new question** starts a separate conversation.

Saved responses show readable facts, narrative, limitations, and source references. Available report and draft copy/download actions pass through Carl's existing authenticated processes and recheck current permissions. Access revocation also removes saved-message choices and denies old result links. Conversation choices show the message preview, time, status, and visibility; users do not need to copy internal identifiers.

Carl can explain financial choices and produce local reports, drafts, and plans. Available credit is never spending budget. Carl cannot borrow, refinance, transfer money, pay bills, purchase goods, or send vendor messages. The designated shared Synology plan-calendar/reminder capability has separate authority documented in [Calendar and reminders](CALENDAR-REMINDERS.md).

## Configuration and qualification

The foundation's family-client service must be enabled and configured for native conversations. The application attaches its Talk processes to that single host-owned service; it does not create another conversation store or inference service. Verified native identities must map to active Carl members with appropriate domain permissions. Use the existing [operator runbook](OPERATOR-RUNBOOK.md) and [foundation configuration reference](foundation/CONFIGURATION.md) for secret names and transport setup; never place a provider key in a browser field or source file.

Controlled acceptance covers PostgreSQL-backed private/shared conversations, current-permission checks, saved artifacts, safe history continuation, readable escaped output, bounded rendering, and revocation during choice construction. A controlled HTTP model fixture proves that revocation after context assembly prevents inference transmission. These tests do not qualify real family identities, live model data handling, Synology accounts, or production deployment. Current packaged/browser evidence is recorded separately in [Session state](SESSION-STATE.md).
