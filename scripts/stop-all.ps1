<#
.SYNOPSIS
  Stop the Converge services that run-all started - and nothing else.

.DESCRIPTION
  A JVM is stopped only when its command line carries -Dconverge.service=<name>, the tag run-all
  gives every JVM it starts. With -Orphans, a JVM is also stopped when its classpath points into
  <this checkout>\<module>\target\classes: a spring-boot:run fork left behind by Ctrl+C, which
  keeps its port and file-locks maven-wrapper.jar. A sibling worktree's build or services, and any
  other Java program on the machine, never match.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -Services payment-service
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -Orphans -DryRun
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -SelfTest
#>
param(
    [string[]] $Services = @(),
    [switch] $Orphans,
    [switch] $DryRun,
    [switch] $SelfTest
)
$ErrorActionPreference = 'Continue'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-ServiceTable([string] $Path) {
    Get-Content -LiteralPath $Path | ForEach-Object { ($_ -replace '#.*$', '').Trim() } |
        Where-Object { $_ } | ForEach-Object {
            $f = -split $_
            [pscustomobject]@{ Name = $f[0]; Port = [int]$f[1]; Probe = $f[2] }
        }
}
$Table = @(Read-ServiceTable (Join-Path $PSScriptRoot 'services.conf'))

# -File passes "a,b" as one string; accept both "a,b" and separate arguments.
$Services = @($Services | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Test-ConvergeJvm([string] $CommandLine, [string[]] $Wanted, [bool] $IncludeOrphans, [string] $CheckoutRoot) {
    if (-not $CommandLine) { return $false }
    $normalised = $CommandLine.Replace('/', '\').ToLowerInvariant()
    foreach ($name in $Wanted) {
        if ($CommandLine -match ('-Dconverge\.service=' + [regex]::Escape($name) + '(\s|"|$)')) { return $true }
        if ($IncludeOrphans) {
            $classes = ($CheckoutRoot.TrimEnd('\', '/') + '\' + $name + '\target\classes').Replace('/', '\').ToLowerInvariant()
            if ($normalised.Contains($classes)) { return $true }
        }
    }
    return $false
}

if ($SelfTest) {
    Write-Host 'Self-test: the matcher must refuse everything that is not ours'
    $all = @($Table | ForEach-Object { $_.Name })
    $r = 'C:\Users\x\Converge'
    $cases = @(
        @(1, 'a tagged jar matches',                     '"C:\jdk\bin\java.exe" -Dconverge.service=payment-service -jar payment-service/target/payment-service-0.1.0-SNAPSHOT.jar', $all, $false),
        @(1, 'a tagged jar matches when named',          'java -Dconverge.service=payment-service -jar x.jar', @('payment-service'), $false),
        @(0, 'a tagged jar does not match another name', 'java -Dconverge.service=payment-service -jar x.jar', @('registration-service'), $false),
        @(0, 'a longer service name is not a prefix match', 'java -Dconverge.service=payment-service-old -jar x.jar', @('payment-service'), $false),
        @(0, 'an untagged spring-boot:run fork is left alone without -Orphans', "java -cp $r\registration-service\target\classes;C:\m2\a.jar App", $all, $false),
        @(1, 'this checkout''s spring-boot:run fork matches with -Orphans', "java -cp $r\registration-service\target\classes;C:\m2\a.jar App", $all, $true),
        @(1, 'forward slashes and case do not hide it', "java -cp c:/users/X/converge/registration-service/target/classes;a.jar App", $all, $true),
        @(0, 'a sibling worktree''s fork never matches', "java -cp $r\.worktrees\m3\registration-service\target\classes;a.jar App", $all, $true),
        @(0, 'a sibling worktree''s Maven build never matches', "`"C:\jdk\bin\java.exe`" -Dmaven.multiModuleProjectDirectory=$r\.worktrees\m4 org.codehaus.plexus.classworlds.launcher.Launcher -pl concierge-service -am test", $all, $true),
        @(0, 'Maven itself is never stopped',            "java -Dmaven.multiModuleProjectDirectory=$r org.codehaus.plexus.classworlds.launcher.Launcher spring-boot:run -pl registration-service", $all, $true),
        @(0, 'another Java program never matches',       'java -jar C:\tools\some-other-app.jar', $all, $true)
    )
    $failures = 0
    foreach ($c in $cases) {
        $got = [int](Test-ConvergeJvm $c[2] $c[3] $c[4] $r)
        if ($got -eq $c[0]) { Write-Host "   ok      $($c[1])" }
        else { Write-Host "   FAILED: $($c[1]) (got $got, wanted $($c[0]))"; $failures++ }
    }
    if ($failures -eq 0) { Write-Host '   the matcher refuses everything that is not ours'; exit 0 }
    Write-Host "   FAILED: $failures matcher check(s)"; exit 1
}

$wanted = if ($Services.Count) { $Services } else { @($Table | ForEach-Object { $_.Name }) }
$unknown = @($wanted | Where-Object { $_ -notin @($Table | ForEach-Object { $_.Name }) })
if ($unknown.Count) { Write-Host "unknown service(s): $($unknown -join ', ')"; exit 2 }

$jvms = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
          Where-Object { Test-ConvergeJvm $_.CommandLine $wanted ([bool]$Orphans) $Root })

if (-not $jvms.Count) { Write-Host '   nothing to stop' }
foreach ($p in $jvms) {
    $label = if ($p.CommandLine -match '-Dconverge\.service=(\S+)') { $Matches[1] } else { 'spring-boot:run fork' }
    if ($DryRun) { Write-Host "   would stop PID $($p.ProcessId)  $label"; continue }
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "   stopped PID $($p.ProcessId)  $label"
}

if (-not $DryRun) {
    $ports = @($Table | Where-Object { $_.Name -in $wanted } | ForEach-Object { $_.Port })
    for ($i = 0; $i -lt 20; $i++) {
        $held = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
        if (-not $held.Count) { break }
        Start-Sleep -Seconds 1
    }
}

# Anything still listening on a Converge port was not ours to stop: say what it is.
$ports = @($Table | Where-Object { $_.Name -in $wanted } | ForEach-Object { $_.Port })
foreach ($c in @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($c.OwningProcess)" -ErrorAction SilentlyContinue
    Write-Host "   port $($c.LocalPort) is still held by PID $($c.OwningProcess) ($($owner.Name)) - not started by run-all."
    Write-Host "     If it is a spring-boot:run left from this checkout, re-run with -Orphans (stop-all.sh --orphans)."
}
exit 0
