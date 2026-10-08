# NordBans

Temporary account bans for Paper 26.2 and Folia 26.2, with NordQueue synchronization. One Java 25 JAR supports both platforms.

## Commands

- `/tempban <player> <time> <reason>` bans an account. Time suffixes are `m`, `h` and `d`.
- `/unban <player>` removes an account ban.

## Permissions

| Permission | Allows | Default |
| --- | --- | --- |
| `nordbans.tempban` | `/tempban` | Nobody, including operators |
| `nordbans.unban` | `/unban` | Nobody, including operators |

Grant each permission explicitly. A command-filter allowlist entry does not grant either permission.

## Storage and queue integration

Local bans use atomic persistence in `plugins/NordBans/bans.properties`. Plugin messages on `nordfjell:bans` synchronize NordQueue.

Banned players remain in limbo outside both queues. After expiry or unban, they join the end of the regular queue.

The file format and configuration defaults are unchanged. Stop the server, back up its installed configuration and ban files, and retain them when replacing the JAR.

## Folia scheduling

The global scheduler drains completed operations and handles expiry. Player kicks, replies and messages use the player's entity scheduler.

Session tracking is concurrent and message templates are immutable after startup. Synchronization is serialized without file I/O under its state lock. Each carrier has at most one outstanding send; a carrier awaiting a ban kick does not send messages.

## Persistence and failure handling

One bounded storage worker processes operations. A queued reply does not mean a change has committed; the final reply reports the outcome.

Successful persistence precedes publication of immutable state. A failed unban stays enforced. Corrupt records, duplicates or invalid metadata reject the entire initial load. Initialization failure or a hot disable requests server shutdown.

Records named `!unban.*` hold pending releases until the old ban expires. Do not delete them while a proxy may still hold the ban.

Recovery sends a baseline through one carrier rather than resending the full store on every join. Carriers warm up for 1 second. The default send budget is 10 messages per tick; a newer state replaces an older pending state for the same account.

## Configuration

```yaml
storage:
  maximum-pending-operations: 128
  maximum-queue-wait-millis: 10000
sync:
  messages-per-tick: 10
```

`maximum-pending-operations` accepts 1..4096 and includes pending results. `maximum-queue-wait-millis` accepts 1..300000; it limits waiting work, not the duration of running I/O. `messages-per-tick` accepts 1..100.

V1 BAN/UNBAN messages have no acknowledgments or cryptographic signature. Receiver persistence and confirmed end-to-end delivery are separate concerns.

## Build and tests

Run `./build.ps1` with Maven 3.9+ and JDK 25. The output is `target/NordBans-1.2.0.jar`. See [BUILDING.md](BUILDING.md).

Checks include `CoreTest`, 31 regression scenarios and 2000 synthetic updates. These do not establish capacity for 1000 connected players.

`test-support/backend-integration.cjs` covers both platforms: legacy bans, V1 frames, permissions, console and player commands, kicks, pre-login enforcement, failed writes, overload, restart, expiry and shutdown.

Build the fixture-only helper with `test-support/build-backend-probe.ps1`, `JAVA_HOME` set to JDK 25 and a suitable `MavenCommand`. Set `NODE_PATH` to the prepared NordLoadTest 26.2 client dependencies.

```powershell
node ./test-support/backend-integration.cjs $FreshFixture $JavaExecutable $BinarySeed Folia
```

Use a fresh local fixture with binary/cache/EULA files only; the existing NordAuth fixture can supply those files, not its account data. The backend listens on `127.0.0.1:25636`, uses synthetic offline clients and stores results in the fixture.

This is not a full production Velocity deployment test. Older `integration.cjs` and proxy-probe fixtures are historical; do not reuse their old source paths or install any probe on production.
