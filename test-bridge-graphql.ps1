param(
    [string]$Username = "instagram",
    [string]$SessionFile = "",
    [switch]$NoRerun
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$androidDir = Join-Path $root "android-client"

if ([string]::IsNullOrWhiteSpace($SessionFile)) {
    $SessionFile = Join-Path $root "testsession.json"
}

$resolvedSession = Resolve-Path -LiteralPath $SessionFile -ErrorAction Stop
$cleanUsername = $Username.Trim().TrimStart("@")
if ([string]::IsNullOrWhiteSpace($cleanUsername)) {
    throw "Username cannot be blank."
}

Write-Host "Testing bridge GraphQL feed fetch"
Write-Host "Username: @$cleanUsername"
Write-Host "Session:  $($resolvedSession.Path)"

$oldSession = $env:FEEDPILOT_SESSION_FILE
$oldUsername = $env:FEEDPILOT_BRIDGE_TEST_USERNAME
try {
    $env:FEEDPILOT_SESSION_FILE = $resolvedSession.Path
    $env:FEEDPILOT_BRIDGE_TEST_USERNAME = $cleanUsername

    $argsList = @(
        "testDebugUnitTest",
        "--tests",
        "com.feedpilot.client.data.repository.InstagramRepositoryTest.bridge path fetches target feed by username"
    )
    if (-not $NoRerun) {
        $argsList = @("testDebugUnitTest", "--rerun-tasks", "--tests", "com.feedpilot.client.data.repository.InstagramRepositoryTest.bridge path fetches target feed by username")
    }

    Push-Location $androidDir
    try {
        & .\gradlew.bat @argsList
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle test failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $xmlPath = Join-Path $androidDir "app\build\test-results\testDebugUnitTest\TEST-com.feedpilot.client.data.repository.InstagramRepositoryTest.xml"
    if (Test-Path -LiteralPath $xmlPath) {
        [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
        $lines = ($xml.testsuite.'system-out').'#cdata-section' -split "`r?`n" |
            Where-Object { $_ -like "BRIDGE_FEED*" }
        if ($lines.Count -gt 0) {
            Write-Host ""
            Write-Host "Converted response:"
            $lines | ForEach-Object { Write-Host $_ }
        }
    }

    Write-Host ""
    Write-Host "OK: live bridge GraphQL feed fetch passed for @$cleanUsername"
}
catch {
    Write-Host ""
    Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Report: $androidDir\app\build\reports\tests\testDebugUnitTest\index.html"
    exit 1
}
finally {
    $env:FEEDPILOT_SESSION_FILE = $oldSession
    $env:FEEDPILOT_BRIDGE_TEST_USERNAME = $oldUsername
}
