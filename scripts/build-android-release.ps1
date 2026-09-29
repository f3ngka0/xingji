param(
    [Parameter(Mandatory = $true)][string]$SigningProperties
)

$ErrorActionPreference = 'Stop'
$repoDir = Split-Path -Parent $PSScriptRoot
$signingPath = (Resolve-Path -LiteralPath $SigningProperties).Path
$allowedNames = @('RELEASE_STORE_FILE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD')
$savedEnvironment = @{}
foreach ($name in $allowedNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$savedPath = $env:PATH

try {
    # Quotes in a Windows PATH entry can break Gradle's test JVM argument parsing.
    $env:PATH = $env:PATH.Replace('"', '')
    foreach ($line in Get-Content -LiteralPath $signingPath -Encoding UTF8) {
        if ($line.Trim().StartsWith('#') -or [string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line -split '=', 2
        if ($parts.Count -ne 2 -or $allowedNames -notcontains $parts[0].Trim()) { throw 'Invalid release signing property.' }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
    foreach ($name in $allowedNames) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "Missing $name" }
    }
    if (-not (Test-Path -LiteralPath $env:RELEASE_STORE_FILE)) { throw 'Release keystore does not exist.' }
    Push-Location (Join-Path $repoDir 'android')
    try {
        & .\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Android release build failed.' }
    } finally { Pop-Location }
    Write-Output (Join-Path $repoDir 'android\app\build\outputs\apk\release\app-release.apk')
} finally {
    foreach ($name in $allowedNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    $env:PATH = $savedPath
}
