# NordBans 1.1.0

Minimal Paper temporary bans for Nord Fjell.

- `/tempban <player> <time> <reason>` with `m`, `h`, or `d`
- `/unban <player>`
- Permission nodes: `nordbans.tempban` and `nordbans.unban`
- Atomic local persistence in `plugins/NordBans/bans.properties`
- Synchronizes suspended players to NordQueue over `nordfjell:bans`
- A suspended player remains in limbo, outside both queues, until expiration or `/unban`
- On release, NordQueue places the player at the end of the regular queue

Prepared and tested on Paper 26.2-127 / Java 25 with NordQueue 1.1.1 on Velocity
4.2.1. This release is **not installed in production**. Sources, tests and release
artifacts remain on the original network share.

Changes:

- Ban/unban/expiry file writes run on one bounded worker, not the gameplay thread.
  A queued message is not confirmation: wait for the final success/error reply.
- A successful file commit precedes publication to immutable in-memory readers;
  failed unban does not silently remove an enforced ban.
- Corrupt records, duplicates and conflicting metadata reject the complete load.
  Initialization failure or disabling this security plugin requests a safe Paper
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

Build on the Codex workstation, using only local server API libraries:

```powershell
& 'Z:\Minecraft Plagins\NordBans\build.ps1' -ServerPath 'C:\Users\artyo\Documents\Codex\nordbans-test-20261004\paper'
```

Build runs the existing CoreTest and 31 new regression scenarios. See
[SECURITY-1.1.0.md](SECURITY-1.1.0.md) and `releases\1.1.0` for functional evidence,
limits and rollback notes. The V1 wire protocol remains BAN/UNBAN-compatible but
has no acknowledgement or cryptographic signature; receiver-side persistence and
delivery confirmation remain separate work. Never deploy `test-support` probes.
