param([string]$Fixture='C:\Users\artyo\Documents\Codex\nordbans-test-20261004')
$ErrorActionPreference='Stop'
if(-not $Fixture.StartsWith('C:\Users\artyo\Documents\Codex\nordbans-test-',[StringComparison]::OrdinalIgnoreCase)){throw 'Local fixture required'}
$java='C:\Program Files\Java\jdk-25\bin'
foreach($kind in @('paper','proxy')){
    $probe=Join-Path $PSScriptRoot ($kind+'-probe')
    $classes=Join-Path $probe 'build\classes'
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    if($kind -eq 'paper'){
        $api=Get-ChildItem -LiteralPath (Join-Path $Fixture 'paper\libraries\io\papermc\paper\paper-api') -Recurse -Filter 'paper-api-26.2.build.127-stable.jar' | Select-Object -First 1
        $dependencies=@($api.FullName)
        $dependencies+=Get-ChildItem -LiteralPath (Join-Path $Fixture 'paper\libraries') -Recurse -File -Filter '*.jar' |
            Where-Object {$_.FullName -match 'adventure|examination|annotations|snakeyaml|bungeecord-chat'} | Select-Object -ExpandProperty FullName
        $cp=($dependencies | Sort-Object -Unique) -join ';'
        $class='BanTestProbe.java';$resource='plugin.yml'
    }else{
        $cp=(Join-Path $Fixture 'proxy\velocity.jar')+';Z:\Minecraft Plagins\NordQueue\releases\1.1.1\NordQueue-1.1.1.jar'
        $class='BanBridgeProbe.java';$resource='velocity-plugin.json'
    }
    & (Join-Path $java 'javac.exe') --release 25 -encoding UTF-8 -cp $cp -d $classes (Join-Path $probe $class)
    if($LASTEXITCODE -ne 0){throw 'Probe compilation failed'}
    $jar=Join-Path $probe ('build\'+$kind+'-local-test-probe.jar')
    & (Join-Path $java 'jar.exe') --create --file $jar -C $classes . -C $probe $resource
    if($LASTEXITCODE -ne 0){throw 'Probe packaging failed'}
    Copy-Item -LiteralPath $jar -Destination (Join-Path $Fixture ($kind+'\plugins'))
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\build\NordBans-1.1.0.jar') -Destination (Join-Path $Fixture 'paper\plugins')
