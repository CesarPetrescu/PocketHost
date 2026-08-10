param(
    [ValidateSet('arm64-v8a','x86_64')]
    [string]$Abi = 'arm64-v8a'
)

$ErrorActionPreference = 'Stop'
$deps = 'D:\PocketHostDeps'
$termux = Join-Path $deps 'termux-packages'
$phpOut = Join-Path $deps "php-android\$Abi"

New-Item -ItemType Directory -Path $deps,(Join-Path $deps 'php-android') -Force | Out-Null

if (!(Test-Path -LiteralPath $termux)) {
    git clone --depth 1 https://github.com/termux/termux-packages.git $termux
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    Write-Host "Docker detected. Build PHP inside the Termux package builder, with outputs under D:."
    Write-Host "Manual command to run from $termux after Docker Desktop is ready:"
    Write-Host "  .\scripts\run-docker.sh ./build-package.sh -a $Abi php"
    Write-Host "Then extract the generated PHP package tree into $phpOut and stage it with scripts\stage-php-runtime.ps1."
    exit 0
}

$wsl = Get-Command wsl -ErrorAction SilentlyContinue
if ($wsl) {
    $distros = (& wsl -l -q) -join ''
    if ($distros.Trim().Length -gt 0) {
        Write-Host "WSL detected. Use a Linux distro with Docker or Termux build dependencies. Source tree: $termux"
        Write-Host "Keep build/output bind mounts on D:, then stage with scripts\stage-php-runtime.ps1."
        exit 0
    }
}

throw "No usable Linux builder found. Install Docker Desktop or a WSL distro, then rerun. Termux source is staged at $termux."
