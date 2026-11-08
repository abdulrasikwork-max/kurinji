<#
  L1: prepares the WINDOWS side of the laptop server (WSL2 host). Run it in Windows PowerShell as yourself.

    powershell -ExecutionPolicy Bypass -File deploy\wsl\windows-host-setup.ps1            # REPORT + DRY RUN: changes nothing
    powershell -ExecutionPolicy Bypass -File deploy\wsl\windows-host-setup.ps1 -Apply     # does the changes listed as APPLY

  What -Apply does (each line is printed first, nothing else is touched):
    1. power, plugged in: never sleep, never hibernate, lid close does nothing (battery settings are NOT changed);
    2. creates %UserProfile%\.wslconfig from deploy\wsl\wslconfig.example ONLY if you have none (an existing one is never overwritten);
    3. registers a scheduled task that, at your sign-in, starts one harmless process inside WSL so the Linux machine stays up.
  What it NEVER does: install WSL (needs admin + a reboot: `wsl --install -d Ubuntu`, your decision), change Windows Update, the
  firewall, Defender, or the Windows MySQL80 service. Those are listed in deploy\LAPTOP.md as options for you to decide.
  NOT VERIFIED on a laptop with WSL installed (it is not installed on the machine this was written on).
  The file is ASCII only on purpose (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$Distro = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'

function Section($text) { Write-Host ''; Write-Host "== $text" }
function Act($description, [scriptblock]$action) {
    if ($Apply) { Write-Host "APPLY  $description"; & $action } else { Write-Host "WOULD  $description" }
}

Write-Host ('IRL laptop-server host setup - mode: ' + $(if ($Apply) { 'APPLY' } else { 'REPORT / DRY RUN (nothing is changed)' }))

# ---------------------------------------------------------------------------------------------------------------- report
Section 'This machine (read only)'
$cs = Get-CimInstance Win32_ComputerSystem
$os = Get-CimInstance Win32_OperatingSystem
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
'{0}: RAM total {1:N1} GB, free {2:N1} GB; {3} logical processors; C: free {4:N0} GB of {5:N0} GB' -f `
    $cs.Model, ($cs.TotalPhysicalMemory / 1GB), ($os.FreePhysicalMemory / 1MB), $cs.NumberOfLogicalProcessors, `
    ($disk.FreeSpace / 1GB), ($disk.Size / 1GB)
$battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue
if ($battery) { 'Battery present: it acts as a small UPS during a power cut (keep the charger plugged in while serving).' }

Section 'WSL (read only)'
$wslExe = Get-Command wsl.exe -ErrorAction SilentlyContinue
if (-not $wslExe) { 'wsl.exe not found.' } else {
    # wsl.exe writes to stderr when WSL is missing (and in UTF-16): do not let "Stop" turn that into an error here.
    $ErrorActionPreference = 'Continue'
    $list = (& wsl.exe -l -v 2>&1 | ForEach-Object { "$_" } | Out-String) -replace "`0", ''
    $ErrorActionPreference = 'Stop'
    if ($list -match 'not\s+installed|--install') {
        'WSL is NOT installed. Install it yourself (admin + reboot):  wsl --install -d Ubuntu'
    } else { $list.Trim() }
}

Section 'Listening sockets reachable from other machines (read only; the server must add NONE of these)'
$watch = 22, 3306, 3307, 3389, 8080, 8081, 8091, 8180, 33060
$exposed = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalAddress -notin '127.0.0.1', '::1' }
if (-not $exposed) { 'none' } else {
    $exposed | Sort-Object LocalPort -Unique | ForEach-Object {
        $name = (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        $flag = if ($watch -contains $_.LocalPort) { '  <-- one of the ports the project uses: should be loopback only' } else { '' }
        '{0,-24} {1,6}  {2}{3}' -f $_.LocalAddress, $_.LocalPort, $name, $flag
    }
}

# ---------------------------------------------------------------------------------------------------------------- changes
Section '1. Power (plugged in)'
Act 'sleep after: never (AC)'        { powercfg /change standby-timeout-ac 0 }
Act 'hibernate after: never (AC)'    { powercfg /change hibernate-timeout-ac 0 }
Act 'turn the screen off after 10 minutes (AC; saves the panel, the server keeps running)' { powercfg /change monitor-timeout-ac 10 }
Act 'closing the lid does nothing (AC)' {
    powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0
    powercfg /setactive SCHEME_CURRENT
}

Section '2. WSL settings file'
$wslconfig = Join-Path $env:USERPROFILE '.wslconfig'
$example = Join-Path $PSScriptRoot 'wslconfig.example'
if (Test-Path $wslconfig) {
    "$wslconfig already exists: left alone. Compare it with $example by hand."
} else {
    Act "create $wslconfig from wslconfig.example (memory cap, no localhost forwarding)" { Copy-Item -LiteralPath $example -Destination $wslconfig }
}

Section '3. Keep the Linux machine alive (scheduled task at sign-in)'
$taskName = 'IRL-WSL-KeepAlive'
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    "$taskName already exists: left alone."
} else {
    Act "register the scheduled task $taskName (runs one idle process in $Distro at your sign-in)" {
        $arg = "-NoProfile -WindowStyle Hidden -Command `"wsl.exe -d $Distro --exec /bin/sleep infinity`""
        $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
        Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
            -Description 'Keeps the IRL WSL2 machine running (see deploy/LAPTOP.md)' | Out-Null
    }
}

Write-Host ''
Write-Host 'Still by hand (deploy\LAPTOP.md): install WSL, Windows Update active hours, automatic sign-in decision, device encryption.'
if (-not $Apply) { Write-Host 'This was a dry run. Add -Apply to make the changes marked WOULD.' }
