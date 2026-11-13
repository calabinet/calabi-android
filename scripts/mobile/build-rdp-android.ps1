param([string]$AndroidHome = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:/Android/Sdk' }), [ValidateSet('arm64-v8a')][string]$Abi = 'arm64-v8a', [switch]$TestClipboard)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../dev/build-cache.ps1')
if (-not (Test-CalabiBuildLease)) {
    & (Join-Path $PSScriptRoot '../dev/build.ps1') __script $PSCommandPath $PSBoundParameters
    exit $LASTEXITCODE
}
$rdpBuildArgs = @('--android-home', $AndroidHome, '--abi', $Abi)
if ($TestClipboard) { $rdpBuildArgs += '--test-clipboard' }
& python (Join-Path $PSScriptRoot 'build-rdp-android.py') @rdpBuildArgs
if ($LASTEXITCODE) { throw 'Android RDP native build failed' }
