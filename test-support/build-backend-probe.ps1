param([string]$MavenCommand='mvn')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$classpath=Join-Path $project 'target/probe-classpath.txt'
& $MavenCommand -B -ntp -f (Join-Path $project 'pom.xml') dependency:build-classpath "-Dmdep.outputFile=$classpath"
if($LASTEXITCODE -ne 0){throw 'Dependency resolution failed'}
$classes=Join-Path $project 'target/probe-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javaBin=Join-Path $env:JAVA_HOME 'bin'
& (Join-Path $javaBin 'javac.exe') --release 25 -encoding UTF-8 -cp (Get-Content $classpath -Raw).Trim() -d $classes (Join-Path $PSScriptRoot 'paper-probe/BanTestProbe.java')
if($LASTEXITCODE -ne 0){throw 'Probe compilation failed'}
& (Join-Path $javaBin 'jar.exe') --create --file (Join-Path $project 'target/NordBansLocalTestProbe.jar') -C $classes . -C (Join-Path $PSScriptRoot 'paper-probe') plugin.yml
if($LASTEXITCODE -ne 0){throw 'Probe packaging failed'}
