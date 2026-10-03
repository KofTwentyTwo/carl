# Foundation reference

Carl does not keep copies of the foundation guides. Read them in the
[KofTwentyTwo Agent Foundation guides](https://github.com/KofTwentyTwo/kof22-agent-foundation/tree/main/docs);
the shared QQQ administration guide is the foundation's
[`qqq-admin/README.md`](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/main/qqq-admin/README.md).
Carl inherits `com.kof22:kof22-agent-parent` `0.5.0-SNAPSHOT` (see `pom.xml`); use foundation
source matching that parent version when changing integrations, and recheck the guides on
each parent upgrade. Keep application requirements and evidence in AGENT-BRIEF.md and ACCEPTANCE.md.

## Carl development and immutable release baseline

Current development uses parent0.5.0-SNAPSHOT and the reviewed RC8 foundation source. Its source tree is identical to protected main1464b10, independently published and qualified in run36878751071; see [RC8 publication evidence](../evidence/2026-10-01-foundation-pr17-rc8-publication.json). [Current Carl qualification](../evidence/2026-10-01-rc8-docked-public-qualification.json) identifies the locally adopted artifacts. The foundation guides at that source describe that development contract.

The unchanged immutable release baseline is foundation0.4.1, sourceb23f89a44647ddc8fd0f3f0894b159ae823638b7, independently qualified stable run36703216841. [Nine historical artifact identities](../evidence/foundation-0.4.1-artifacts.json) and [historical guide identities](../evidence/foundation-reference-0.4.1.json) retain that release provenance; they do not identify the current foundation guides. Local recovery is not a fresh authenticated Maven fetch. Hosted private-package access and a matching qualified immutable replacement remain required before Carl release.
