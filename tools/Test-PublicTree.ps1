[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$failures = [System.Collections.Generic.List[string]]::new()

$prohibitedExtensions = @(
    '.apk', '.apex', '.dex', '.img', '.iso', '.bin', '.blob', '.ko', '.so',
    '.tar', '.gz', '.tgz', '.zip', '.zst', '.pk8', '.pem', '.jks',
    '.keystore', '.p12'
)

$files = Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File -Force |
    Where-Object { $_.FullName -notmatch '[\\/]\.git[\\/]' }

foreach ($file in $files) {
    if ($prohibitedExtensions -contains $file.Extension.ToLowerInvariant()) {
        $failures.Add("Prohibited binary or credential file: $($file.FullName)")
    }

    if ($file.Length -gt 20MB) {
        $failures.Add("File exceeds the 20 MiB public-tree limit: $($file.FullName)")
    }
}

$textExtensions = @(
    '.md', '.txt', '.c', '.h', '.java', '.xml', '.sh', '.ps1', '.yml',
    '.yaml', '.json', '.bp', '.idc', '.gitignore', '.gitattributes'
)

$secretPatterns = @(
    ('gh' + 'p_[A-Za-z0-9]{20,}'),
    ('github_pat_' + '[A-Za-z0-9_]{20,}'),
    ('AKIA' + '[A-Z0-9]{16}'),
    ('sk-' + '[A-Za-z0-9]{20,}'),
    ('xox[baprs]-' + '[A-Za-z0-9-]{10,}'),
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    '(?i)(api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token)\s*[:=]\s*["''][^"'']{8,}["'']',
    '(?i)\bpsk\s*=\s*["''][^"'']+["'']'
)

$privatePathPatterns = @(
    ('(?i)C:\\Users\\' + 'skdlz'),
    ('(?i)redroid-research-' + '20260727'),
    ('\b172\.27\.' + '65\.51\b')
)

foreach ($file in $files) {
    $name = $file.Name.ToLowerInvariant()
    $extension = $file.Extension.ToLowerInvariant()
    if (($textExtensions -notcontains $extension) -and
        ($textExtensions -notcontains $name)) {
        continue
    }

    $content = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($pattern in $secretPatterns + $privatePathPatterns) {
        if ($content -match $pattern) {
            $failures.Add("Sensitive-looking text in $($file.FullName): $pattern")
        }
    }
}

foreach ($script in $files | Where-Object Extension -eq '.ps1') {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $script.FullName,
        [ref]$tokens,
        [ref]$errors
    )
    foreach ($parseError in $errors) {
        $failures.Add("PowerShell parse error in $($script.FullName): $($parseError.Message)")
    }
}

foreach ($xmlFile in $files | Where-Object Extension -eq '.xml') {
    try {
        [void][xml](Get-Content -LiteralPath $xmlFile.FullName -Raw)
    } catch {
        $failures.Add("XML parse error in $($xmlFile.FullName): $($_.Exception.Message)")
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Public tree audit passed: $($files.Count) files checked."
Write-Host 'No prohibited binaries, oversized files, known private paths, or secret-shaped values were found.'
exit 0
