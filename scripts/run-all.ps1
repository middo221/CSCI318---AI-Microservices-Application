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
    [ValidateRange(1, 86400)] [int] $TimeoutSeconds = 240,
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
        if ($value.Length -ge 1 -and ($value[0] -eq '"' -or $value[0] -eq "'")) {
            # quoted: the value ends at the closing quote; anything after it is a comment
            $end = $value.IndexOf($value[0], 1)
            $value = if ($end -ge 0) { $value.Substring(1, $end - 1) } else { $value.Substring(1) }
        } else {
            $value = ($value -replace '\s+#.*$', '').Trim()
        }
        $found = $value
    }
    return $found
}

function Test-Wanted([string] $Name) { return (-not $Only.Count) -or ($Only -contains $Name) }

# Names in $Names that the service table does not list.
function Get-UnknownNames([string[]] $Names, $ServiceTable) {
    return @($Names | Where-Object { $_ -notin @($ServiceTable | ForEach-Object { $_.Name }) })
}

# Failure markers spring leaves in a log, the same as run-all.sh checks.
$FailurePatterns = @('APPLICATION FAILED TO START', 'Unable to access jarfile', 'Error starting ApplicationContext')
function Test-StartFailed([string[]] $LogPaths) {
    $existing = @($LogPaths | Where-Object { Test-Path -LiteralPath $_ })
    if (-not $existing.Count) { return $false }
    return [bool](Select-String -LiteralPath $existing -Pattern $FailurePatterns -Quiet -ErrorAction SilentlyContinue)
}

# status.txt as plain ASCII/UTF-8 (Tee-Object in Windows PowerShell 5.1 writes UTF-16).
function Save-Status([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding $false))
}

# The last lines of each log, with a heading, as an array of strings.
function Get-LogTail([string] $Path, [int] $Lines = 20) {
    $out = @("--- $Path (last $Lines lines)")
    if (Test-Path -LiteralPath $Path) { $out += @(Get-Content -LiteralPath $Path -Tail $Lines) } else { $out += '(no log file)' }
    return $out
}

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
        [IO.File]::WriteAllText("$tmp\inline.env", "GEMINI_API_KEY=abc # my key`n")
        [IO.File]::WriteAllText("$tmp\inlineq.env", "GEMINI_API_KEY=`"ab # c`" # note`n")
        Expect 'an inline comment is stripped from an unquoted value' (Get-EnvValue "$tmp\inline.env" 'GEMINI_API_KEY') 'abc'
        Expect 'a # inside quotes is kept, the comment after it is not' (Get-EnvValue "$tmp\inlineq.env" 'GEMINI_API_KEY') 'ab # c'
        $fixture = @('some line', 'Error starting ApplicationContext. To display the condition report...')
        [IO.File]::WriteAllLines("$tmp\bad.err.log", $fixture)
        [IO.File]::WriteAllLines("$tmp\good.log", @('Started App in 3 seconds'))
        Expect 'a failure marker in .err.log is found' (Test-StartFailed @("$tmp\good.log", "$tmp\bad.err.log")) $true
        Expect 'a healthy log is not a failure' (Test-StartFailed @("$tmp\good.log", "$tmp\absent.err.log")) $false
        [IO.File]::WriteAllLines("$tmp\jar.log", @('Error: Unable to access jarfile x.jar'))
        Expect 'a missing jar is a failure' (Test-StartFailed @("$tmp\jar.log")) $true
        [IO.File]::WriteAllLines("$tmp\long.log", @(1..30 | ForEach-Object { "line $_" }))
        $tail = Get-LogTail "$tmp\long.log" 20
        Expect 'the log tail has a heading and 20 lines, ending at the last' ("$($tail.Count)/$($tail[1])/$($tail[-1])") '21/line 11/line 30'
        Expect 'a missing log is reported, not hidden' ((Get-LogTail "$tmp\nope.log" 20)[-1]) '(no log file)'
        Save-Status "$tmp\status.txt" "service port`r`nalpha 1`r`n"
        $bytes = [IO.File]::ReadAllBytes("$tmp\status.txt")
        Expect 'status.txt is single-byte text, not UTF-16' ("$($bytes[0])/$($bytes.Length)") '115/23'
    } finally { Remove-Item -LiteralPath $tmp -Recurse -Force }
    Expect 'the shipped services.conf lists six services' $Table.Count 6
    $Only = @('payment-service')
    Expect '-Only includes a named service' (Test-Wanted 'payment-service') $true
    Expect '-Only excludes an unnamed one' (Test-Wanted 'analytics-service') $false
    Expect 'Get-UnknownNames finds a misspelt service' ((Get-UnknownNames @('payment-service', 'paymnt-service') $Table) -join ',') 'paymnt-service'
    Expect 'Get-UnknownNames accepts real services' (Get-UnknownNames @('payment-service', 'analytics-service') $Table).Count 0
    if ($failures -eq 0) { Write-Host '   every helper can say no'; exit 0 }
    Write-Host "   FAILED: $failures helper check(s)"; exit 1
}

$bad = Get-UnknownNames $Only $Table
if ($bad.Count) {
    Write-Host "   unknown service(s) in -Only: $($bad -join ', ')"
    Write-Host "   Known: $(($Table | ForEach-Object { $_.Name }) -join ' ')"
    exit 2
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'logs') | Out-Null

$script:Down = @()
function Show-Status {
    $rows = foreach ($s in $Table | Where-Object { Test-Wanted $_.Name }) {
        $code = Get-ProbeCode $s.Port $s.Probe
        [pscustomobject]@{ service = $s.Name; port = $s.Port; state = $(if (Test-Ready $code) { 'READY' } else { 'DOWN' }); HTTP = $code; log = "logs\$($s.Name).log, logs\$($s.Name).err.log" }
    }
    $text = $rows | Format-Table -AutoSize | Out-String
    Save-Status (Join-Path $Root 'logs\status.txt') $text
    Write-Host $text
    $script:Down = @($rows | Where-Object { $_.state -eq 'DOWN' } | ForEach-Object { $_.service })
    return $script:Down.Count -eq 0
}

function Show-Failures {
    foreach ($n in $script:Down) {
        foreach ($f in @("logs\$n.log", "logs\$n.err.log")) { Get-LogTail (Join-Path $Root $f) 20 | ForEach-Object { Write-Host $_ } }
    }
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
if ($LASTEXITCODE -ne 0) {
    Write-Host '   Docker is not running. Start Docker Desktop and run this again.'
    Write-Host '   Kafka runs in Docker, and analytics-service needs the broker to start, so nothing is started without it.'
    exit 1
}
function Get-KafkaHealth { (docker inspect -f '{{.State.Health.Status}}' converge-kafka 2>$null | Out-String).Trim() }
if ((Get-KafkaHealth) -ne 'healthy') {
    Write-Host '   Kafka is not up - docker compose up -d'
    docker compose up -d *> $null
    for ($i = 0; $i -lt 60 -and (Get-KafkaHealth) -ne 'healthy'; $i++) { Start-Sleep -Seconds 2 }
}
if ((Get-KafkaHealth) -ne 'healthy') {
    Write-Host '   Kafka (converge-kafka) did not become healthy in 120s; analytics-service needs the broker to start, so nothing was started.'
    exit 1
}
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
$procs = @{}
foreach ($s in $Table | Where-Object { Test-Wanted $_.Name }) {
    $jar = Get-ChildItem -Path (Join-Path $Root "$($s.Name)\target") -Filter "$($s.Name)-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { Write-Host "   no jar in $($s.Name)\target - run without -NoBuild"; exit 1 }
    if ($s.Name -eq 'concierge-service') { $env:GEMINI_API_KEY = $gemini } else { Remove-Item Env:\GEMINI_API_KEY -ErrorAction SilentlyContinue }
    $p = Start-Process -FilePath $java -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -ArgumentList @("-Dconverge.service=$($s.Name)", '-jar', "`"$($jar.FullName)`"") `
        -RedirectStandardOutput (Join-Path $Root "logs\$($s.Name).log") `
        -RedirectStandardError (Join-Path $Root "logs\$($s.Name).err.log")
    $procs[$s.Name] = $p
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
        elseif (Test-StartFailed @((Join-Path $Root "logs\$($_.Name).log"), (Join-Path $Root "logs\$($_.Name).err.log"))) {
            Write-Host "   $($_.Name) failed to start - see logs\$($_.Name).log and .err.log"; $false }
        elseif ($procs[$_.Name].HasExited) {
            Write-Host "   $($_.Name) exited before it answered - see logs\$($_.Name).log and .err.log"; $false }
        else { $true }
    })
    if ($pending.Count) { Start-Sleep -Seconds 3 }
}
if (Show-Status) { Write-Host '   all requested services are up. Stop them with scripts\stop-all.ps1'; exit 0 }
Show-Failures
Write-Host "   not every service answered within ${TimeoutSeconds}s - read its log under logs\"
exit 1
