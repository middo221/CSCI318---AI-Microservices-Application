<#
.SYNOPSIS
  Start Kafka and all six Converge services from their jars, and wait until each one answers.

.DESCRIPTION
  The PowerShell twin of scripts/run-all.sh, for Windows users without Git Bash. Same service
  table (scripts/services.conf), same jar launch, same -Dconverge.service=<name> tag, so
  scripts/stop-all.ps1 stops exactly these JVMs and nothing else.

  Kafka comes first: analytics-service provisions its topics at start-up and fails after about
  thirty seconds per topic without a broker. After `docker compose down -v`, restart analytics.

  Every CONFERENCE_* variable in this session reaches every service (Spring's relaxed binding):
    $env:CONFERENCE_REGISTRATION_HOLD_WINDOW = 'PT20S'
  GEMINI_API_KEY is read from .env and given to concierge-service only.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -NoBuild -Only payment-service
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -Check
  powershell -ExecutionPolicy Bypass -File scripts\run-all.ps1 -SelfTest
#>
param(
    [switch] $NoBuild,
    [string[]] $Only = @(),
    [int] $TimeoutSeconds = 240,
    [switch] $Check,
    [switch] $SelfTest
)
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root
$Only = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Read-ServiceTable([string] $Path) {
    Get-Content -LiteralPath $Path | ForEach-Object { ($_ -replace '#.*$', '').Trim() } |
        Where-Object { $_ } | ForEach-Object {
            $f = -split $_
            [pscustomobject]@{ Name = $f[0]; Port = [int]$f[1]; Probe = $f[2] }
        }
}

# Any HTTP answer except 5xx; 000 means nothing is listening. curl.exe ships with Windows 10+.
function Get-ProbeCode([int] $Port, [string] $Path) {
    $code = & curl.exe -s -o NUL -m 3 -w '%{http_code}' "http://localhost:$Port$Path" 2>$null
    if (-not $code) { return '000' }
    return [string]$code
}
function Test-Ready([string] $Code) { return ($Code -and $Code -notmatch '^(000|5\d\d)$') }

# NAME's value in a .env file: BOM, CRLF, `export`, quotes and comments tolerated; last one wins.
function Get-EnvValue([string] $Path, [string] $Name) {
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $found = ''
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $line = $line.Trim().TrimStart([char]0xFEFF)
        if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7) }
        $key, $value = $line.Split('=', 2)
        if ($key.Trim() -ne $Name) { continue }
        $value = $value.Trim()
        if ($value.Length -ge 2 -and ($value[0] -eq '"' -or $value[0] -eq "'") -and $value[-1] -eq $value[0]) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $found = $value
    }
    return $found
}

function Test-Wanted([string] $Name) { return (-not $Only.Count) -or ($Only -contains $Name) }

$Table = @(Read-ServiceTable (Join-Path $PSScriptRoot 'services.conf'))

if ($SelfTest) {
    Write-Host 'Self-test: every helper must be able to say no'
    $failures = 0
    function Expect($Description, $Got, $Want) {
        if ("$Got" -eq "$Want") { Write-Host "   ok      $Description" }
        else { Write-Host "   FAILED: $Description (got '$Got', wanted '$Want')"; $script:failures++ }
    }
    Expect '200 is ready' (Test-Ready '200') $true
    Expect '404 is ready (registration answers 404)' (Test-Ready '404') $true
    Expect '000 is not ready' (Test-Ready '000') $false
    Expect '503 is not ready (analytics rebuilding)' (Test-Ready '503') $false
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("converge-selftest-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        [IO.File]::WriteAllText("$tmp\notepad.env", "# saved by Notepad`r`nGEMINI_API_KEY = `"quoted-key`"`r`n", (New-Object Text.UTF8Encoding $true))
        [IO.File]::WriteAllText("$tmp\twice.env", "export GEMINI_API_KEY=first`nGEMINI_API_KEY=second`n")
        [IO.File]::WriteAllText("$tmp\none.env", "# GEMINI_API_KEY=commented`nOTHER=x`n")
        Expect 'a BOM, CRLF, spaces and quotes are stripped' (Get-EnvValue "$tmp\notepad.env" 'GEMINI_API_KEY') 'quoted-key'
        Expect 'export is accepted and the last assignment wins' (Get-EnvValue "$tmp\twice.env" 'GEMINI_API_KEY') 'second'
        Expect 'a commented-out key is not a key' (Get-EnvValue "$tmp\none.env" 'GEMINI_API_KEY') ''
        Expect 'a missing .env yields nothing' (Get-EnvValue "$tmp\absent.env" 'GEMINI_API_KEY') ''
    } finally { Remove-Item -LiteralPath $tmp -Recurse -Force }
    Expect 'the shipped services.conf lists six services' $Table.Count 6
    $Only = @('payment-service')
    Expect '-Only includes a named service' (Test-Wanted 'payment-service') $true
    Expect '-Only excludes an unnamed one' (Test-Wanted 'analytics-service') $false
    if ($failures -eq 0) { Write-Host '   every helper can say no'; exit 0 }
    Write-Host "   FAILED: $failures helper check(s)"; exit 1
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'logs') | Out-Null

function Show-Status {
    $rows = foreach ($s in $Table | Where-Object { Test-Wanted $_.Name }) {
        $code = Get-ProbeCode $s.Port $s.Probe
        [pscustomobject]@{ service = $s.Name; port = $s.Port; state = $(if (Test-Ready $code) { 'READY' } else { 'DOWN' }); HTTP = $code; log = "logs\$($s.Name).log" }
    }
    $rows | Format-Table -AutoSize | Out-String | Tee-Object -FilePath (Join-Path $Root 'logs\status.txt') | Write-Host
    return @($rows | Where-Object { $_.state -eq 'DOWN' }).Count -eq 0
}

if ($Check) { if (Show-Status) { exit 0 } else { exit 1 } }

Write-Host 'Converge - run-all'

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$version = cmd /c "`"$java`" -version 2>&1"
if (-not ($version -match 'version "21([."]|$)')) {
    Write-Host "   Java 21 is required. '$java -version' says: $($version | Select-Object -First 1)"; exit 1
}
Write-Host "   Java 21: $java"

docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-Host '   Docker is not running. Start Docker Desktop and run this again.'; exit 1 }
function Get-KafkaHealth { (docker inspect -f '{{.State.Health.Status}}' converge-kafka 2>$null | Out-String).Trim() }
if ((Get-KafkaHealth) -ne 'healthy') {
    Write-Host '   Kafka is not up - docker compose up -d'
    docker compose up -d *> $null
    for ($i = 0; $i -lt 60 -and (Get-KafkaHealth) -ne 'healthy'; $i++) { Start-Sleep -Seconds 2 }
}
if ((Get-KafkaHealth) -ne 'healthy') { Write-Host '   Kafka (converge-kafka) did not become healthy in 120s'; exit 1 }
Write-Host '   Kafka is healthy on localhost:9092; Kafka UI on http://localhost:8080'

$busy = @($Table | Where-Object { (-not $NoBuild) -or (Test-Wanted $_.Name) } |
          Where-Object { (Get-ProbeCode $_.Port '/') -ne '000' } | ForEach-Object { "$($_.Name):$($_.Port)" })
if ($busy.Count) {
    Write-Host "   already in use: $($busy -join ' ')"
    Write-Host '   Stop them first: powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 (add -Orphans for a spring-boot:run left behind).'
    exit 1
}

$gemini = Get-EnvValue (Join-Path $Root '.env') 'GEMINI_API_KEY'
if (-not $gemini) { $gemini = $env:GEMINI_API_KEY }
if ((Test-Wanted 'concierge-service') -and (-not $gemini -or $gemini -eq 'your-key-here')) {
    Write-Host '   no GEMINI_API_KEY in .env: the concierge will answer 502 LLM_UNAVAILABLE'
}

if (-not $NoBuild) {
    Write-Host '   building: mvnw.cmd -q clean package -DskipTests  (about a minute)'
    cmd /c "mvnw.cmd -q clean package -DskipTests > logs\build.log 2>&1"
    if ($LASTEXITCODE -ne 0) { Get-Content logs\build.log -Tail 30; Write-Host '   the build failed - see logs\build.log'; exit 1 }
    Write-Host '   built every module'
}

$inherited = $env:GEMINI_API_KEY
foreach ($s in $Table | Where-Object { Test-Wanted $_.Name }) {
    $jar = Get-ChildItem -Path (Join-Path $Root "$($s.Name)\target") -Filter "$($s.Name)-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { Write-Host "   no jar in $($s.Name)\target - run without -NoBuild"; exit 1 }
    if ($s.Name -eq 'concierge-service') { $env:GEMINI_API_KEY = $gemini } else { Remove-Item Env:\GEMINI_API_KEY -ErrorAction SilentlyContinue }
    $p = Start-Process -FilePath $java -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -ArgumentList @("-Dconverge.service=$($s.Name)", '-jar', "`"$($jar.FullName)`"") `
        -RedirectStandardOutput (Join-Path $Root "logs\$($s.Name).log") `
        -RedirectStandardError (Join-Path $Root "logs\$($s.Name).err.log")
    Set-Content -Path (Join-Path $Root "logs\$($s.Name).pid") -Value $p.Id
    Write-Host "   started $($s.Name) on $($s.Port) (PID $($p.Id), log: logs\$($s.Name).log)"
}
if ($inherited) { $env:GEMINI_API_KEY = $inherited } else { Remove-Item Env:\GEMINI_API_KEY -ErrorAction SilentlyContinue }

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$pending = @($Table | Where-Object { Test-Wanted $_.Name })
while ($pending.Count -and (Get-Date) -lt $deadline) {
    $pending = @($pending | Where-Object {
        $code = Get-ProbeCode $_.Port $_.Probe
        if (Test-Ready $code) { Write-Host "   $($_.Name) answers on $($_.Port) (HTTP $code)"; $false }
        elseif (Select-String -Path (Join-Path $Root "logs\$($_.Name).log") -Pattern 'APPLICATION FAILED TO START' -Quiet -ErrorAction SilentlyContinue) {
            Write-Host "   $($_.Name) failed to start - see logs\$($_.Name).log"; $false }
        else { $true }
    })
    if ($pending.Count) { Start-Sleep -Seconds 3 }
}
if (Show-Status) { Write-Host '   all requested services are up. Stop them with scripts\stop-all.ps1'; exit 0 }
Write-Host "   not every service answered within ${TimeoutSeconds}s - read its log under logs\"
exit 1
