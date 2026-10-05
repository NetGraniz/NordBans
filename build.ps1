param(
    [string]$ServerPath = 'Z:\Minecraft server'
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $projectPath 'src\main\java'
$testSourcePath = Join-Path $projectPath 'src\test\java'
$resourcePath = Join-Path $projectPath 'src\main\resources'
$buildPath = Join-Path $projectPath 'build'
$classesPath = Join-Path $buildPath 'classes'
$testClassesPath = Join-Path $buildPath 'test-classes'
$outputPath = Join-Path $buildPath 'NordBans-1.1.0.jar'
$javaPath = 'C:\Program Files\Java\jdk-25\bin'

New-Item -ItemType Directory -Force -Path $classesPath, $testClassesPath | Out-Null
foreach ($generatedPath in @($classesPath,$testClassesPath)) {
    $resolved = (Resolve-Path -LiteralPath $generatedPath).Path
    $projectRoot = (Resolve-Path -LiteralPath $projectPath).Path
    if ($resolved -notin @((Join-Path $projectRoot 'build\classes'),(Join-Path $projectRoot 'build\test-classes'))) { throw 'Unsafe cleanup target' }
    foreach ($child in Get-ChildItem -LiteralPath $resolved -Force) {
        if (-not $child.FullName.StartsWith($resolved+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe generated child' }
        Remove-Item -LiteralPath $child.FullName -Recurse -Force
    }
}
$sources = Get-ChildItem -LiteralPath $sourcePath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$paperApi = Get-ChildItem -LiteralPath (Join-Path $ServerPath 'libraries\io\papermc\paper\paper-api') -Recurse -Filter 'paper-api-26.2.build.127-stable.jar' | Select-Object -First 1
if (-not $paperApi) { throw 'Paper API 26.2 build 127 was not found in the server libraries.' }
$dependencies = @($paperApi.FullName)
$dependencies += Get-ChildItem -LiteralPath (Join-Path $ServerPath 'libraries') -Recurse -File -Filter '*.jar' |
    Where-Object { $_.FullName -match 'adventure|examination|annotations|snakeyaml|bungeecord-chat' } |
    Select-Object -ExpandProperty FullName
$classpath = ($dependencies | Sort-Object -Unique) -join ';'
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath $classpath -d $classesPath $sources
if ($LASTEXITCODE -ne 0) { throw 'NordBans compilation failed.' }
$testSources = Get-ChildItem -LiteralPath $testSourcePath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath $classesPath -d $testClassesPath $testSources
if ($LASTEXITCODE -ne 0) { throw 'NordBans test compilation failed.' }
& (Join-Path $javaPath 'java.exe') -ea -classpath "$classesPath;$testClassesPath" com.nordfjell.nordbans.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'NordBans tests failed.' }
& (Join-Path $javaPath 'java.exe') -ea -classpath "$classesPath;$testClassesPath" com.nordfjell.nordbans.RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'NordBans regression tests failed.' }
Copy-Item -Path (Join-Path $resourcePath '*') -Destination $classesPath -Recurse -Force
if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
Push-Location $classesPath
try {
    & (Join-Path $javaPath 'jar.exe') --create --file $outputPath .
    if ($LASTEXITCODE -ne 0) { throw 'NordBans packaging failed.' }
} finally { Pop-Location }
Write-Output $outputPath
