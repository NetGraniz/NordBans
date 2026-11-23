# Building NordBans

Requirements:

- JDK 25
- Maven 3.9+

Run:

```powershell
.\build.ps1 -MavenCommand "C:\path\to\mvn.cmd"
```

The script runs `mvn clean verify`, checks the exact release JAR, and validates `plugin.yml`. The production artifact is `target/NordBans-1.3.1.jar`.
