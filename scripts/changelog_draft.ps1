<#
.SYNOPSIS
    从上一个 Release 标签到 HEAD 的提交生成 changelog 草稿（Markdown）。

.DESCRIPTION
    读取 `git log <最近tag>..HEAD` 的提交主题，按前缀（feat / fix / hotfix / docs /
    i18n / ci 等）分组，输出一份 Markdown 草稿到 build/changelog-draft.md。

    注意：这只是**提交素材汇总**。Release 正文面向玩家，需要人工改写成口语化说明，
    不要直接把草稿贴进发布页。

.PARAMETER SinceTag
    起始标签；不传则自动取最近一个 git tag。

.PARAMETER OutFile
    草稿输出路径，默认 <仓库根>/build/changelog-draft.md。

.PARAMETER IncludeMerges
    默认过滤 Merge 提交；加上此开关则保留。

.EXAMPLE
    pwsh scripts/changelog_draft.ps1
    pwsh scripts/changelog_draft.ps1 -SinceTag 0.7.0
#>

[CmdletBinding()]
param(
    [string]$SinceTag,
    [string]$OutFile,
    [switch]$IncludeMerges
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not $SinceTag) {
        $describe = & git describe --tags --abbrev=0 2>$null
        if ($LASTEXITCODE -eq 0 -and $describe) { $SinceTag = $describe.Trim() }
    }

    if ($SinceTag) {
        $range = "$SinceTag..HEAD"
        Write-Host "汇总范围：$range"
    } else {
        $range = 'HEAD'
        Write-Host "未找到 tag，汇总全部历史提交"
    }

    $subjects = & git log $range --pretty=format:%s | Where-Object { $_ }
}
finally {
    Pop-Location
}

if (-not $OutFile) { $OutFile = Join-Path $root 'build/changelog-draft.md' }

# 前缀分组（顺序即输出顺序）。
$groups = [ordered]@{
    'feat'     = @()
    'fix'      = @()
    'hotfix'   = @()
    'perf'     = @()
    'refactor' = @()
    'i18n'     = @()
    'docs'     = @()
    'ci'       = @()
    'other'    = @()
}

foreach ($subject in $subjects) {
    if (-not $IncludeMerges -and $subject -match '^Merge\b') { continue }

    $prefix = 'other'
    if ($subject -match '^(\w+)(\([^)]*\))?\s*[:：]') {
        $candidate = $Matches[1].ToLower()
        if ($groups.Contains($candidate)) { $prefix = $candidate }
    }
    $groups[$prefix] += $subject.Trim()
}

$lines = @()
$lines += "# Changelog 草稿（$SinceTag -> HEAD）"
$lines += ""
$lines += "> ⚠️ 这是提交记录自动汇总的**素材草稿**，发布前请人工改写成面向玩家的口语化说明，不要直接贴进 Release 页。"
$lines += ""

$sectionTitles = @{
    'feat'     = '✨ 新功能 / 原创内容'
    'fix'      = '🔧 修复'
    'hotfix'   = '🚑 热修'
    'perf'     = '⚡ 性能'
    'refactor' = '♻️ 重构'
    'i18n'     = '🌐 本地化'
    'docs'     = '📝 文档'
    'ci'       = '🤖 工程 / CI'
    'other'    = '📌 其他'
}

foreach ($key in $groups.Keys) {
    $items = $groups[$key] | Select-Object -Unique
    if (-not $items) { continue }
    $lines += "### $($sectionTitles[$key])"
    foreach ($item in $items) { $lines += "- $item" }
    $lines += ""
}

$outDir = Split-Path -Parent $OutFile
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
[System.IO.File]::WriteAllText($OutFile, ($lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Host "草稿已写入：$OutFile"
