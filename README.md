# NordBans

Independent temporary account bans for Paper/Folia. NordBans 2.0.1 uses indexed SQLite storage as its only persistence layer.

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

`config.yml` contains user-facing messages. `/nordbans reload` validates and reloads messages and the maximum ban duration without a server restart. The bounded storage worker settings are validated during reload, but changing them requires a normal server restart; the command reports this explicitly and keeps the active worker intact.

## Platform

- Java 25
- Paper/Folia 26.2
- SQLite JDBC is declared as a Paper runtime library
- Folia scheduling uses global, entity, or asynchronous ownership as appropriate; database I/O never runs on an entity region

See [BUILDING.md](BUILDING.md) for the verified build command.

## License

Current development on `main` is licensed under [GNU GPL v3.0 only](LICENSE). The latest published release remains available under the MIT License stated on its release page.
