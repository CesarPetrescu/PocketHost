param(
    [ValidateSet('arm64-v8a','x86_64')]
    [string]$Abi,
    [Parameter(Mandatory=$true)]
    [string]$PhpExecutable,
    [Parameter(Mandatory=$true)]
    [string]$RuntimeRoot
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$jni = Join-Path $repo "android\app\src\main\jniLibs\$Abi"
$assets = 'D:\PocketHostDeps\php-android\assets'
$zip = Join-Path $assets "php-runtime-$Abi.zip"

if (!(Test-Path -LiteralPath $PhpExecutable -PathType Leaf)) { throw "PHP executable not found: $PhpExecutable" }
if (!(Test-Path -LiteralPath $RuntimeRoot -PathType Container)) { throw "PHP runtime root not found: $RuntimeRoot" }

New-Item -ItemType Directory -Path $jni,$assets -Force | Out-Null
Copy-Item -LiteralPath $PhpExecutable -Destination (Join-Path $jni 'libphp.so') -Force
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $RuntimeRoot '*') -DestinationPath $zip -Force

Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $jni 'libphp.so'),$zip | Format-Table -AutoSize
