<#
.SYNOPSIS
  Runs the game tests and saves the full output under docs/qa/testlogs.

.DESCRIPTION
  Each run writes docs/qa/testlogs/<yyyy-MM-dd_HH-mm-ss>-<task>.log (the whole Gradle and game
  output) and a short <same name>.summary.txt (the test count, the failed tests, and the build
  result). Run it from the repository root.

.EXAMPLE
  .\tools\run-gametests.ps1                       # both targets
  .\tools\run-gametests.ps1 -Task :1.21.1:runGameTestServer
  .\tools\run-gametests.ps1 -Task compileJava,verifyNavigationArchitecture,:core:test
#>
param(
    [string[]]$Task = @('runGameTestServer'),
    [string]$LogDirectory = 'docs/qa/testlogs'
)

$ErrorActionPreference = 'Continue'
$env:GRADLE_OPTS = '-Djava.net.preferIPv4Stack=true'

New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$stamp = Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
$label = ($Task -join '+') -replace '[^A-Za-z0-9._+-]', '_'
$logPath = Join-Path $LogDirectory "$stamp-$label.log"
$summaryPath = Join-Path $LogDirectory "$stamp-$label.summary.txt"

Write-Host "Logging to $logPath"
# Windows PowerShell 5.1's Tee-Object writes UTF-16; write UTF-8 so the log is greppable.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter((Join-Path (Get-Location) $logPath), $false, $utf8NoBom)
try {
    & .\gradlew.bat @Task --no-daemon --no-configuration-cache --console=plain 2>&1 |
        ForEach-Object {
            $line = "$_"
            Write-Host $line
            $writer.WriteLine($line)
        }
} finally {
    $writer.Dispose()
}

$log = Get-Content -LiteralPath $logPath -Encoding utf8
$summary = @()
$summary += "Command: .\gradlew.bat $($Task -join ' ') --no-daemon --no-configuration-cache --console=plain"
$summary += "Finished: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$summary += "Commit: $(git rev-parse --short HEAD)"
$summary += ''
$summary += ($log | Select-String -Pattern 'GAME TESTS COMPLETE|required tests failed|optional tests failed|BUILD (SUCCESSFUL|FAILED)|Verified ' |
    ForEach-Object { $_.Line.Trim() })
$failed = $log | Select-String -Pattern 'GameTestServer\]:\s+- ' | ForEach-Object { $_.Line.Trim() }
if ($failed) {
    $summary += ''
    $summary += 'Failed tests:'
    $summary += ($failed | Select-Object -Unique)
}
$summary | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Host "Summary: $summaryPath"
