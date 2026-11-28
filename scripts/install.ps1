# Installs Financial Dashboard on Windows: downloads the executable from the latest GitHub release, checks its checksum and
# sets up a `financial-dashboard` command. Run it in PowerShell:
#
#   irm https://raw.githubusercontent.com/vinaykumar-pulipyata/FinancialDashboard/main/scripts/install.ps1 | iex
#
# Options through environment variables (set them first in the same window):
#   $env:FINANCIAL_DASHBOARD_VERSION = "v0.1.0-rc1"      install that release (a pre-release is only installed when named)
#   $env:FINANCIAL_DASHBOARD_HOME = "D:\finance"          where the program and your data go (default %LOCALAPPDATA%\FinancialDashboard)
#   $env:FINANCIAL_DASHBOARD_DRY_RUN = "1"                only say what would happen
#   $env:FINANCIAL_DASHBOARD_UNINSTALL = "1"              remove the program and the command and take the folder off your PATH (your data is kept)
#   $env:FINANCIAL_DASHBOARD_PURGE = "1"                  with UNINSTALL: also delete your data (data, backups, logs); asks you to type "delete" first
#   $env:FINANCIAL_DASHBOARD_YES = "1"                    with PURGE: do not ask (for scripts: there is no way back)
#
# Where things go: the executable (financial-dashboard-app.exe), the launcher (financial-dashboard.cmd) and, once the app has run,
# data\, backups\ and logs\ all sit in that one folder; the folder is added to your user PATH.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = 'vinaykumar-pulipyata/FinancialDashboard'
$Version = if ($env:FINANCIAL_DASHBOARD_VERSION) { $env:FINANCIAL_DASHBOARD_VERSION } else { 'latest' }
$HomeDir = if ($env:FINANCIAL_DASHBOARD_HOME) { $env:FINANCIAL_DASHBOARD_HOME } else { Join-Path $env:LOCALAPPDATA 'FinancialDashboard' }
$DryRun = $env:FINANCIAL_DASHBOARD_DRY_RUN -eq '1'
$Uninstall = $env:FINANCIAL_DASHBOARD_UNINSTALL -eq '1'
$Purge = $env:FINANCIAL_DASHBOARD_PURGE -eq '1'
$Yes = $env:FINANCIAL_DASHBOARD_YES -eq '1'
$BaseUrl = $env:FINANCIAL_DASHBOARD_BASE_URL          # for tests: a folder or address that holds the files

function Fail($message) { Write-Host "Error: $message" -ForegroundColor Red; throw $message }

if ($Purge -and -not $Uninstall) { Fail "FINANCIAL_DASHBOARD_PURGE only goes with FINANCIAL_DASHBOARD_UNINSTALL=1." }

if ($Uninstall) {
    if ($Purge) { Write-Host "Removing the program and the command, and deleting your data on request (see below)." }
    else { Write-Host "Removing the program and the command. Your data (data, backups, logs in $HomeDir) is left alone." }
    foreach ($f in 'financial-dashboard-app.exe', 'financial-dashboard.cmd') {
        $p = Join-Path $HomeDir $f
        if (Test-Path $p) { if ($DryRun) { Write-Host "  would remove $p" } else { Remove-Item $p -Force; Write-Host "  removed $p" } }
    }
    # the installer put the folder on the user PATH: take it off again
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath -and (($userPath -split ';') -contains $HomeDir)) {
        if ($DryRun) { Write-Host "  would take $HomeDir off your PATH" }
        else {
            [Environment]::SetEnvironmentVariable('Path', ((($userPath -split ';') | Where-Object { $_ -and $_ -ne $HomeDir }) -join ';'), 'User')
            Write-Host "  took $HomeDir off your PATH"
        }
    }

    if ($Purge) {
        # Your data is the one thing this script can destroy, so it asks first.
        $targets = @('data', 'backups', 'logs') | ForEach-Object { Join-Path $HomeDir $_ } | Where-Object { Test-Path $_ }
        Write-Host ""
        if (-not $targets) { Write-Host "No data to delete in $HomeDir." }
        else {
            Write-Host "Purge deletes your data. This cannot be undone:"
            foreach ($t in $targets) {
                $size = [math]::Round(((Get-ChildItem $t -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1MB), 1)
                Write-Host "  $t  ($size MB)"
            }
            if ($DryRun) { Write-Host "(dry run: nothing was deleted)"; return }
            if (-not $Yes) {
                if (-not [Environment]::UserInteractive) { Fail "Cannot ask for confirmation here (no interactive window). Nothing was deleted. Run it in PowerShell, or set FINANCIAL_DASHBOARD_YES=1 if you are sure." }
                $answer = Read-Host 'Type "delete" to delete it, anything else keeps it'
                if ($answer -ne 'delete') { Write-Host "Kept. Nothing was deleted."; return }
            }
            foreach ($t in $targets) { Remove-Item $t -Recurse -Force; Write-Host "  deleted $t" }
        }
        if ((Test-Path $HomeDir) -and -not (Get-ChildItem $HomeDir -Force)) { Remove-Item $HomeDir -Force; Write-Host "  removed the empty folder $HomeDir" }
        elseif (Test-Path $HomeDir) { Write-Host "Left in $HomeDir : $((Get-ChildItem $HomeDir -Force | ForEach-Object Name) -join ' ')" }
    }
    return
}

$arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
if ($arch -ne 'X64') { Fail "Unsupported processor: $arch. The Windows build is for 64-bit Intel/AMD (x64)." }
$asset = 'financial-dashboard-windows-x64.exe'

if ($BaseUrl) { $base = $BaseUrl.TrimEnd('/', '\') }
elseif ($Version -eq 'latest') { $base = "https://github.com/$Repo/releases/latest/download" }
else { $base = "https://github.com/$Repo/releases/download/$Version" }

Write-Host "System:      windows x64"
Write-Host "Release:     $Version"
Write-Host "Download:    $base/$asset"
Write-Host "Install in:  $HomeDir"
if ($DryRun) { Write-Host "(dry run: nothing was downloaded or changed)"; return }

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("fdinstall-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "Downloading ..."
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile (Join-Path $tmp $asset)
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset.sha256" -OutFile (Join-Path $tmp "$asset.sha256")
    } catch {
        Fail "Could not download $base/$asset (Is there a release yet? See https://github.com/$Repo/releases . A pre-release is only installed with FINANCIAL_DASHBOARD_VERSION.)"
    }
    $expected = ((Get-Content (Join-Path $tmp "$asset.sha256") -TotalCount 1) -split '\s+')[0]
    $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $asset)).Hash
    if (-not $expected -or $expected.ToLower() -ne $actual.ToLower()) { Fail "The checksum does not match (expected $expected, got $actual). Nothing was installed." }
    Write-Host "Checksum OK."

    New-Item -ItemType Directory -Force -Path $HomeDir | Out-Null
    $exe = Join-Path $HomeDir 'financial-dashboard-app.exe'
    Copy-Item (Join-Path $tmp $asset) "$exe.new" -Force
    Unblock-File "$exe.new" -ErrorAction SilentlyContinue      # takes off the "downloaded from the internet" mark
    Move-Item "$exe.new" $exe -Force

    # The launcher runs the app from this folder, because the app keeps data, backups and logs in the folder it is started from.
    $cmd = @"
@echo off
cd /d "%~dp0"
start "" /b cmd /c "timeout /t 8 /nobreak >nul & start http://localhost:8080/"
"%~dp0financial-dashboard-app.exe" %*
"@
    Set-Content -Path (Join-Path $HomeDir 'financial-dashboard.cmd') -Value $cmd -Encoding ASCII

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not (($userPath -split ';') -contains $HomeDir)) {
        [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $HomeDir), 'User')
        $onPath = $false
    } else { $onPath = $true }

    Write-Host ""
    Write-Host "Installed. Start it with:  financial-dashboard" -NoNewline
    if (-not $onPath) { Write-Host "  (open a new PowerShell or Command Prompt window first, so the new PATH is used)" } else { Write-Host "" }
    Write-Host "It opens http://localhost:8080/ ; on the first run it asks you to set a password."
    Write-Host "Windows may show 'Windows protected your PC' the first time: choose More info, then Run anyway."
    Write-Host "Your data lives in $HomeDir\data (and backups, logs). To remove the program later, set FINANCIAL_DASHBOARD_UNINSTALL=1 and run this script again."
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}