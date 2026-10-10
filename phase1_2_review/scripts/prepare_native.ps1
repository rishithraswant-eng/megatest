param([string]$Workspace = (Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference = 'Stop'
$sources = @(
    @{ Name = 'whisper.cpp'; Url = 'https://github.com/ggml-org/whisper.cpp.git'; Commit = 'd1be6fde11ac6e0407606b4e42fe72d34add8037' },
    @{ Name = 'llama.cpp'; Url = 'https://github.com/ggml-org/llama.cpp.git'; Commit = 'aa94f2086147cd4bfbcff818ce998dffce5fcb39' }
)
$vendorRoot = Join-Path $Workspace 'third_party'
New-Item -ItemType Directory -Path $vendorRoot -Force | Out-Null
foreach ($source in $sources) {
    $target = Join-Path $vendorRoot $source.Name
    if (!(Test-Path -LiteralPath (Join-Path $target '.git'))) {
        New-Item -ItemType Directory -Path $target -Force | Out-Null
        & git -C $target init --quiet
        if ($LASTEXITCODE -ne 0) { throw "Cannot initialize $target" }
        & git -C $target remote add origin $source.Url
    }
    $changes = & git -C $target status --porcelain
    if ($changes) { throw "Local native-source changes found in $target. Preserve them before preparing sources." }
    $head = & git -C $target rev-parse --verify --quiet HEAD
    if ($head -ne $source.Commit) {
        & git -C $target fetch --depth 1 origin $source.Commit
        if ($LASTEXITCODE -ne 0) { throw "Cannot fetch pinned $($source.Name) revision" }
        & git -C $target checkout --detach $source.Commit
        if ($LASTEXITCODE -ne 0) { throw "Cannot check out pinned $($source.Name) revision" }
    }
    Write-Output "$($source.Name): $($source.Commit)"
}
