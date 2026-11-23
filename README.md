# NordBans

Temporary account bans for Paper/Folia 26.2. NordBans 1.3.1 stores bans in indexed SQLite instead of rewriting a properties file.

## Storage

- Runtime database: `plugins/NordBans/bans.db`
- Legacy import source: `plugins/NordBans/bans.properties`
- The legacy file is imported once and left untouched.
- Expired records are removed periodically.
- NordBans no longer sends plugin messages to the proxy. NordQueue reads `bans.db` directly through its configured read-only connection.

## Commands

- `/tempban <player> <duration> <reason>`
- `/untempban <player>`
- `/nordbans reload`

Supported durations are minutes (`m`), hours (`h`), and days (`d`).

## Configuration

`config.yml` contains user-facing messages. `/nordbans reload` validates and reloads the configuration without a server restart.

## Platform

- Java 25
- Paper/Folia 26.2
- SQLite JDBC is declared as a Paper runtime library
- Folia scheduling uses global, entity, or asynchronous ownership as appropriate; database I/O never runs on an entity region

See [BUILDING.md](BUILDING.md) for the verified build command.
