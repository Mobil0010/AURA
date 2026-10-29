# Build from an ASCII working directory: Android CMake can crash in a Unicode cwd.
# Copies build inputs only; never copies signing keys or publishes an APK.
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release', 'Both')]
    [string]$Variant = 'Both',
    [switch]$AndroidTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stagingRoot = Join-Path ([IO.Path]::GetTempPath()) ('AURA-build-' + [guid]::NewGuid().ToString('N'))
if ($stagingRoot -match '[^\x00-\x7F]') {
    throw 'TEMP must point to an ASCII path for the Windows native build.'
}
if (-not $env:JAVA_HOME) {
    $bundledJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (-not (Test-Path (Join-Path $bundledJava 'bin\javac.exe'))) {
        throw 'Set JAVA_HOME to a JDK 17 or newer installation.'
    }
    $env:JAVA_HOME = $bundledJava
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
        Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
}
if (-not (Test-Path $env:ANDROID_HOME)) {
    throw 'Android SDK not found. Set ANDROID_HOME.'
}

New-Item -ItemType Directory -Path $stagingRoot | Out-Null
foreach ($name in @('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew.bat')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination $stagingRoot
}
foreach ($name in @('app', 'gradle')) {
    & robocopy.exe (Join-Path $projectRoot $name) (Join-Path $stagingRoot $name) /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1 /XD build .cxx .gradle .local .git /XF local.properties *.jks *.keystore *.pass *.p12 *.pfx *.pk8
    if ($LASTEXITCODE -ge 8) {
        throw "Copying $name failed with robocopy exit $LASTEXITCODE"
    }
}

$tasks = @(':app:lintDebug')
if ($Variant -in @('Debug', 'Both')) {
    $tasks += ':app:assembleDebug'
}
if ($Variant -in @('Release', 'Both')) {
    $tasks += ':app:assembleRelease'
}
if ($AndroidTests) {
    $tasks += ':app:assembleDebugAndroidTest'
}
$logDirectory = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$logPath = Join-Path $logDirectory 'build-windows.log'
Write-Host "Build working directory: $stagingRoot"
Write-Host "Build log: $logPath"
Push-Location $stagingRoot
try {
    & .\gradlew.bat @tasks --no-daemon 2>&1 | Tee-Object -FilePath $logPath
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($buildExit -ne 0) {
    throw "Gradle failed ($buildExit). Staging retained: $stagingRoot"
}

$outputs = @()
if ($Variant -in @('Debug', 'Both')) {
    $outputs += 'debug/app-debug.apk'
}
if ($Variant -in @('Release', 'Both')) {
    $outputs += 'release/app-release-unsigned.apk'
}
foreach ($relative in $outputs) {
    $source = Join-Path $stagingRoot "app/build/outputs/apk/$relative"
    $destination = Join-Path $projectRoot "app/build/outputs/apk/$relative"
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
}
if ($AndroidTests) {
    $testDirectory = Join-Path $projectRoot 'app/build/outputs/apk/androidTest/debug'
    New-Item -ItemType Directory -Force -Path $testDirectory | Out-Null
    Copy-Item -LiteralPath (Join-Path $stagingRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') -Destination $testDirectory
}
$reports = Join-Path $projectRoot 'app/build/reports'
New-Item -ItemType Directory -Force -Path $reports | Out-Null
Copy-Item -Path (Join-Path $stagingRoot 'app/build/reports/lint-results-debug.*') -Destination $reports
Write-Host 'Debug APK uses the local debug certificate; release APK is unsigned. Neither is a public release.'
Write-Host "Staging retained for diagnostics: $stagingRoot"
