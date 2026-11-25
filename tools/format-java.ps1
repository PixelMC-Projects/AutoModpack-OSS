param(
    [Parameter(Mandatory = $true)][string]$FormatterJar,
    [string]$Java = 'java',
    [switch]$Write
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$configuration = Get-Content -LiteralPath (Join-Path $projectRoot '.java-format.json') -Raw | ConvertFrom-Json
$formatterPath = (Resolve-Path -LiteralPath $FormatterJar).Path
$expectedHash = '812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1'
if ((Get-FileHash -LiteralPath $formatterPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Use the official google-java-format 1.24.0 all-deps JAR; SHA-256 does not match.'
}

$sourceFiles = foreach ($source in $configuration.sources) {
    $sourcePath = [IO.Path]::GetFullPath((Join-Path $projectRoot $source))
    if (-not $sourcePath.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Source path is outside the project: $source"
    }
    if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Missing source path: $source" }
    Get-ChildItem -LiteralPath $sourcePath -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName }
}
$sourceFiles = @($sourceFiles | Sort-Object -Unique | Where-Object {
    $relative = $_.Substring($projectRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, [char]'/')
    $excluded = $false
    foreach ($pattern in $configuration.exclude) {
        if ($relative -like $pattern) { $excluded = $true; break }
    }
    -not $excluded
})
if ($sourceFiles.Count -eq 0) { throw 'No Java source files selected.' }

$arguments = @('--aosp', '--skip-sorting-imports', '--skip-removing-unused-imports', '--skip-reflowing-long-strings', '--skip-javadoc-formatting')
if ($Write) { $arguments += '--replace' } else { $arguments += @('--dry-run', '--set-exit-if-changed') }
$formatterExit = 0
for ($offset = 0; $offset -lt $sourceFiles.Count; $offset += 32) {
    $last = [Math]::Min($offset + 31, $sourceFiles.Count - 1)
    $batch = @($sourceFiles[$offset..$last])
    & $Java '-jar' $formatterPath @arguments @batch
    $batchExit = $LASTEXITCODE
    if ($batchExit -ne 0 -and ($formatterExit -eq 0 -or $batchExit -ne 1)) {
        $formatterExit = $batchExit
    }
}
exit $formatterExit
