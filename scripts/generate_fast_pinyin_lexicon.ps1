param(
    [int]$MinimumWeight = 100000
)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $project 'app\src\main\assets\rime-data\openime_dicts'
$output = Join-Path $project 'app\src\main\assets\pinyin_phrases.tsv'
$entries = [System.Collections.Generic.List[object]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

foreach ($name in @('base.dict.yaml', 'ext.dict.yaml')) {
    $path = Join-Path $sourceRoot $name
    $inData = $false
    foreach ($line in [System.IO.File]::ReadLines($path)) {
        if ($line -eq '...') {
            $inData = $true
            continue
        }
        if (-not $inData -or [string]::IsNullOrWhiteSpace($line) -or $line[0] -eq '#') {
            continue
        }
        $columns = $line.Split("`t")
        if ($columns.Length -lt 3) { continue }
        $weight = 0
        if (-not [int]::TryParse($columns[2], [ref]$weight) -or $weight -lt $MinimumWeight) {
            continue
        }
        $text = $columns[0]
        $pinyin = $columns[1].Replace(' ', '')
        if ($text.Length -lt 2 -or $text.Length -gt 8 -or $pinyin -notmatch '^[a-z]+$') {
            continue
        }
        $identity = "$pinyin`t$text"
        if ($seen.Add($identity)) {
            $entries.Add([pscustomobject]@{ Pinyin = $pinyin; Text = $text; Weight = $weight })
        }
    }
}

$ordered = $entries | Sort-Object Pinyin, @{ Expression = 'Weight'; Descending = $true }, Text
$utf8 = [System.Text.UTF8Encoding]::new($false)
$writer = [System.IO.StreamWriter]::new($output, $false, $utf8)
try {
    $writer.WriteLine("# generated from bundled Rime Ice base/ext; minimum weight=$MinimumWeight")
    foreach ($entry in $ordered) {
        $writer.WriteLine("$($entry.Pinyin)`t$($entry.Text)`t$($entry.Weight)")
    }
} finally {
    $writer.Dispose()
}

Write-Host "FAST LEXICON OK -> $($entries.Count) entries"
