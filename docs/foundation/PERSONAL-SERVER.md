# Local development to a personal server

The supported starting point is one agent JVM/container and its PostgreSQL database,
with QQQ administration inherited from `kof22-agent-qqq`. Build the consumer using
[Build a Standalone Agent](BUILD-STANDALONE-AGENT.md) and use synthetic data until its
domain calculations, permissions, integrations and policy controls are tested.

## Local preparation

1. Follow [GETTING-STARTED.md](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/docs/GETTING-STARTED.md) to verify/install the foundation and
   generate a separate application. For published versions, use the Maven settings and
   explicit version pin described in [ARTIFACTS.md](ARTIFACTS.md).
2. Configure PERSONAL governance and its explicit owner, then use the packaged
   `DatabaseBootstrap provision` CLI with a private 0600 properties file for a fresh
   dedicated database and separate migration/runtime/reader roles.
3. Start loopback administration with separate writer/reader credentials. Retain
   `-Dqqq.logger.logSessionId.disabled=true` in every Java launch.
4. Add business metadata, migrations, services and actual-application/evaluation cases.

Bootstrap creates the three restricted roles, runs core/domain migrations as the migration
owner and applies the runtime/reader grants. See [DATABASE-ROLES.md](DATABASE-ROLES.md) for
upgrades, restore preparation and explicit credential rotation. Flyway migration credentials
belong to the maintenance process.
Runtime startup validates the existing schema and never migrates it. Domain
migrations and backend grants remain explicit. The CLI does not silently grant broad access
to new columns or take over existing roles. It does not provision the operating system,
PostgreSQL service, encryption keys or identity provider.

## Enable the production configuration

`KOF22_AGENT_DEPLOYMENT_MODE=PRODUCTION` selects the enforced server contract. Copy the
complete generated `config/agent.properties` to `/etc/agent/agent.properties`, retain its
declared maps/lists, and set the server-specific nonsecret values:

```properties
kof22.agent.deployment.mode=PRODUCTION
kof22.agent.persona-path=/opt/agent/current/prompts/PERSONA.md
kof22.agent.policy.profile=PERSONAL
kof22.agent.policy.owner=Family administrators
kof22.agent.qqq.auth-mode=oidc-bearer
kof22.agent.qqq.host=127.0.0.1
kof22.agent.qqq.public-origin=https://agent.example.com
kof22.agent.qqq.oidc.issuer=https://your-issuer.example.com/
kof22.agent.qqq.oidc.audience=your-api-audience
kof22.agent.qqq.oidc.client-id=your-browser-client
kof22.agent.rbac.users.verified-operator-subject=ADMIN
kof22.agent.slack.enabled=true
kof22.agent.mcp.enabled=true
kof22.agent.mcp.approval-channel=your-approval-channel
```

Replace example identity settings with the real issuer/client and exact subjects. Keep
runtime/reader passwords, provider/Slack credentials and declared MCP caller tokens in a
private environment file using `KOF22_AGENT_*` names from [Configuration](CONFIGURATION.md).
Never mount the bootstrap file or migration credentials in the runtime. Configure job paths
absolutely too when this properties file lives outside the distribution.

Production checks restricted database grants, enabled authenticated interfaces, safe outbound
URLs and verified operator login. QQQ, MCP and health share the same loopback listener;
HTTPS terminates at a proxy that can reach that network namespace. Provider/domain HTTP URLs
must use HTTPS or literal-loopback HTTP. Local Basic mode does not qualify server access.

## Reviewable deployment files

[The Dockerfile](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/deploy/Dockerfile) copies the complete verified `target/agent/` distribution, runs as UID 10001,
and sets finite JVM memory behavior. Supply a reviewed image digest through `RUNTIME_IMAGE`
when qualifying a candidate. The integration check runs the image read-only, drops Linux
capabilities and supplies a bounded temporary filesystem. Run with equivalent restrictions
on the server. Do not bind local Basic authentication to the network.

[The systemd example](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/deploy/agent.service.example) shows a single non-root systemd process, root-owned private
environment file, restricted filesystem access and graceful shutdown. Adapt its paths and
resource limits to the actual server. Never store secrets in a release directory or image.
No host changes or deployment have been performed by these files.

For systemd, copy all of `target/agent/` into `/opt/agent/current/`, preserving `app.jar`,
`lib/` and `prompts/`. Use `/etc/agent/agent.properties` and private `/etc/agent/agent.env`. Install the adapted
unit as `agent.service`. The usual service commands are:

```sh
sudo systemctl start agent
sudo systemctl status agent
sudo journalctl -u agent -f
sudo systemctl stop agent
```

Quiesce active requests and writes before planned stops. A container deployment must make
its HTTPS proxy able to reach the shared loopback listener; bridge-network port
publication alone does not make a container's loopback reachable from a separate proxy.
Keep the proxy in the same network namespace or choose a reviewed equivalent topology.

For per-person server access use QQQ verified operator mode, an Auth0-compatible browser
provider, exact issuer/audience/client settings and core RBAC mappings. Terminate HTTPS at a
qualified proxy, retain a private backend network, limit request sizes/rates, and verify real
login, unauthorized access, subject isolation, expiry and local logout/revocation. See
[QQQ administration](FOUNDATION-ADMIN.md). A controlled token test is not live SSO proof.

## Gates before real family data

- Scan the actual consumer JAR/image before deployment. Foundation [verification evidence](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/docs/SESSION-STATE.md)
  is specific to its tested dependency graph and scan date. It does not cover your new
  business dependencies or vulnerabilities disclosed after that scan.
- Establish encrypted disks/backups, off-host age recipients, private secret storage/rotation,
  the actual operator identity, and least-privilege service/database roles.
- Choose retention, recovery objectives and alert ownership. The shared retention service
  does not purge transcripts or memory automatically. Exact approval payloads are sensitive.
- Run the encrypted restore drill on the intended host and test the chosen upgrade/rollback
  pair. The shared synthetic drill does not qualify every historical schema.
- Qualify the real model/chat/vendor integrations using authorized synthetic/sandbox cases,
  then record a supervised observation period. No live calls or financial actions are
  authorized by creating the application.
- Pin the shared artifacts to the intended published semantic version and verify the
  consumer can resolve them in its build environment. [ARTIFACTS.md](ARTIFACTS.md) and
  [CI-CD.md](https://github.com/KofTwentyTwo/kof22-agent-foundation/blob/b23f89a44647ddc8fd0f3f0894b159ae823638b7/docs/CI-CD.md) describe foundation releases; an agent deployment is a separate
  application release.

These are concrete remaining deployment/business qualifications. The foundation must own
shared implementations so the finance agent only supplies its unique business behavior.

Use [OPERATIONS.md](OPERATIONS.md) for encrypted backup/restore, operational inspection,
upgrades and rollback. These instructions describe the shipped mechanisms; the actual
personal server, live identity provider and business integrations still need to be configured
and exercised in their target environment.
