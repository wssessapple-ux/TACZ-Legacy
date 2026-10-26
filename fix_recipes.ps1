# ============================================================
#  TACZ-Legacy 1.12.2 gun-pack recipe "dimension wall" fix (Plan A)
#  Replaces 5 unresolvable high-version materials with 1.12.2 equivalents.
#  `count` is never touched. The whole recipes dir is backed up first.
# ============================================================

$recipesRoot = "E:\vip\TACZ-Legacy\src\main\resources\assets\tacz\custom\tacz_default_gun\data\tacz\recipes"

if (-not (Test-Path -LiteralPath $recipesRoot)) {
    Write-Host "Path not found: $recipesRoot" -ForegroundColor Red
    exit 1
}

# ---- 1. backup the whole recipes dir ----
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$parent = Split-Path -Parent $recipesRoot
$backup = Join-Path $parent ("recipes.bak-" + $stamp)
Copy-Item -LiteralPath $recipesRoot -Destination $backup -Recurse
Write-Host "Backed up recipes dir to: $backup" -ForegroundColor Cyan

# ---- 2. five replacement rules (regex, tolerant of compact/pretty JSON) ----
$rules = @(
    @{ pat = '"tag"\s*:\s*"forge:ingots/netherite"';       rep = '"tag": "forge:gems/diamond"' },
    @{ pat = '"tag"\s*:\s*"forge:ingots/copper"';          rep = '"tag": "forge:ingots/iron"' },
    @{ pat = '"tag"\s*:\s*"forge:gems/amethyst"';          rep = '"tag": "forge:glass"' },
    @{ pat = '"tag"\s*:\s*"forge:ores/netherite_scrap"';   rep = '"tag": "forge:gems/diamond"' },
    @{ pat = '"item"\s*:\s*"minecraft:crying_obsidian"';   rep = '"item": "minecraft:obsidian"' }
)

# ---- 3. walk and replace ----
$totalFiles = 0
$totalChanges = 0
Get-ChildItem -LiteralPath $recipesRoot -Recurse -Filter *.json | ForEach-Object {
    $content = [IO.File]::ReadAllText($_.FullName)
    $orig = $content
    foreach ($r in $rules) {
        $content = [regex]::Replace($content, $r.pat, $r.rep)
    }
    if ($content -ne $orig) {
        $changes = 0
        foreach ($r in $rules) { $changes += [regex]::Matches($orig, $r.pat).Count }
        # write back as BOM-less UTF-8 (recipes are parsed by ConvertFrom-Json/Gson; a BOM would corrupt the first field)
        [IO.File]::WriteAllText($_.FullName, $content, (New-Object Text.UTF8Encoding $false))
        $totalFiles++
        $totalChanges += $changes
        Write-Host ("modified {0} ({1} place(s))" -f $_.Name, $changes)
    }
}

Write-Host ""
Write-Host ("Done: {0} files changed, {1} material refs replaced" -f $totalFiles, $totalChanges) -ForegroundColor Yellow
Write-Host "Backup at: $backup" -ForegroundColor Cyan
