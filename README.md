# NordBans 1.2.0

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).

Temporary account bans for Paper 26.2 and Folia 26.2 / Java 25.
One release JAR supports both platforms; development stays on `main`.

- `/tempban <player> <time> <reason>` with `m`, `h`, or `d`
- `/unban <player>`
- Permission nodes: `nordbans.tempban` and `nordbans.unban`
- Atomic local persistence in `plugins/NordBans/bans.properties`
- Synchronizes suspended players to NordQueue over `nordfjell:bans`
- A suspended player remains in limbo, outside both queues, until expiration or `/unban`
- On release, NordQueue places the player at the end of the regular queue

Sources and release artifacts are maintained in GitHub. Keep existing configuration
and ban files: their format and defaults are unchanged, no migration is required
by this update. Stop and back up the server before replacing its JAR.

## Paper and Folia

Storage completion draining and expiry maintenance use the global scheduler.
Kicks, player command replies and plugin-message transmission use the owning
entity scheduler. Carrier sessions are tracked concurrently; messages are immutable
startup snapshots. Sync state is serialized without file I/O under its lock.
Only one carrier task may be outstanding, preventing backlog on a slow region.
Banned players cannot carry messages while their kick is pending.

Changes:

- Ban/unban/expiry file writes run on one bounded worker, not the gameplay thread.
  A queued message is not confirmation: wait for the final success/error reply.
- A successful file commit precedes publication to immutable in-memory readers;
  failed unban does not silently remove an enforced ban.
- Corrupt records, duplicates and conflicting metadata reject the complete load.
  Initialization failure or disabling this security plugin requests a safe server
  shutdown. Hot reload and hot disable are unsupported.
- Persistent `!unban.*` metadata records pending releases until the original ban
  would expire. Do not delete it while the proxy may still hold that ban.
- One carrier-recovery baseline, not a full resend on each join; default at most
  ten messages per tick. A new carrier has a one-second warm-up for Velocity's
  backend switch. Latest account state supersedes stale pending messages.
- Command and tab-completion permission checks are explicit. Existing permission
  nodes remain default-false; no ordinary player or operator gains new access.
  The local test helper grants only its test console the two nodes; no production
  permission is changed.

Optional configuration (existing files receive embedded defaults without manual edits):

```yaml
storage:
  maximum-pending-operations: 128       # 1..4096; includes results awaiting delivery
  maximum-queue-wait-millis: 10000      # 1..300000; not a running-I/O deadline
sync:
  messages-per-tick: 10                # 1..100
```

Build from the working Git clone with Maven and JDK 25:

```powershell
./build.ps1
```

Build runs CoreTest, 31 regression scenarios and concurrent delivery of 2000
synthetic updates. This is not a 1000-player server load test.
The V1 wire protocol remains BAN/UNBAN-compatible but
has no acknowledgement or cryptographic signature; receiver-side persistence and
delivery confirmation remain separate work. Never deploy `test-support` probes.

## Isolated backend tests

`test-support/backend-integration.cjs` exercises the same JAR on separate local
Paper/Folia fixtures: legacy bans, V1 wire frames, permission rejection, console
and player commands, kicks, pre-login denial, failed writes, queue overload,
restart recovery, expiry and fail-closed shutdown.

Build the test-only probe with `test-support/build-backend-probe.ps1`; set
`JAVA_HOME` to JDK 25 and pass `-MavenCommand` if Maven is not on PATH.
Set `NODE_PATH` to installed NordLoadTest dependencies with 26.2 metadata prepared.
Pass a fresh local fixture path, Java executable, a same-day NordAuth fixture
supplying only server binaries/cache and accepted EULA, and `Paper` or `Folia`.
The harness never copies accounts, configurations or worlds.

```powershell
node ./test-support/backend-integration.cjs $FreshFixture $JavaExecutable $BinarySeed Folia
```

It binds only `127.0.0.1:25636`, uses offline-mode synthetic clients and writes
results into that fixture. The probe grants only isolated test permissions.
Never install test probes on production. These tests verify V1 packet transport,
not the complete production Velocity/NordQueue deployment. The older
`integration.cjs`/proxy probe describe a historical fixture; do not run their old
paths or use historical sources for new builds.
