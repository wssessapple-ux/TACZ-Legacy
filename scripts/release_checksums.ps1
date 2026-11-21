<#
.SYNOPSIS
    为发布产物生成 SHA-256 校验文件（checksums.txt）。

.DESCRIPTION
    扫描 build/libs/ 下的构建产物，只取最终要发布的 reobf 主 jar，
    排除 -dev / -shadow-dev / -sources 等中间包，计算 SHA-256 并写入 checksums.txt。

    输出格式为标准 sha256sum 风格：`<HASH>  <FILENAME>`（小写哈希、两个空格），
    可在其他机器上用 `sha256sum -c checksums.txt` 校验。
    同时在控制台打印主 jar 的裸哈希，方便粘贴到 Release 正文 / README。

.PARAMETER LibsDir
    构建产物目录，默认 <仓库根>/build/libs。

.PARAMETER OutFile
    checksums.txt 输出路径，默认 <LibsDir>/checksums.txt。

.EXAMPLE
    pwsh scripts/release_checksums.ps1
    pwsh scripts/release_checksums.ps1 -OutFile build/libs/checksums.txt
#>

[CmdletBinding()]
param(
    [string]$LibsDir,
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not $LibsDir) { $LibsDir = Join-Path $root 'build/libs' }
if (-not $OutFile) { $OutFile = Join-Path $LibsDir 'checksums.txt' }

if (-not (Test-Path $LibsDir)) {
    Write-Error "产物目录不存在：$LibsDir（先跑 ./gradlew build 生成 jar）"
    exit 1
}

# 只保留最终发布主 jar，排除开发中间包与源码包。
$artifacts = Get-ChildItem -File -Path $LibsDir -Filter '*.jar' | Where-Object {
    $_.Name -notmatch '-(dev|shadow-dev|sources)\.jar$'
}

if (-not $artifacts) {
    Write-Error "在 $LibsDir 没找到可发布的主 jar（已排除 -dev / -shadow-dev / -sources）"
    exit 1
}

$lines = @()
foreach ($jar in $artifacts) {
    $hash = (Get-FileHash -Algorithm SHA256 -Path $jar.FullName).Hash.ToLower()
    $lines += "$hash  $($jar.Name)"
    Write-Host ("{0}  {1}" -f $hash, $jar.Name)
}

# 写 UTF-8（无 BOM），换行用 LF，避免 sha256sum -c 在 Linux 上报格式问题。
$outDir = Split-Path -Parent $OutFile
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
[System.IO.File]::WriteAllText($OutFile, ($lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "checksums.txt 已写入：$OutFile"

# 控制台额外打印第一个（主）jar 的裸哈希，便于填进 Release 正文 / README。
$main = $artifacts | Select-Object -First 1
$mainHash = (Get-FileHash -Algorithm SHA256 -Path $main.FullName).Hash.ToLower()
Write-Host ""
Write-Host "主产物：$($main.Name)"
Write-Host "SHA-256（裸串）：$mainHash"
