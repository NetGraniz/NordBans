# NordBans

Independent temporary account bans for Paper/Folia. NordBans 2.0.0 uses indexed SQLite storage as its only persistence layer.

## Storage

- Runtime database: `plugins/NordBans/bans.db`
- Expired records are removed periodically.
- The database schema is owned by NordBans and is validated before use.

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
