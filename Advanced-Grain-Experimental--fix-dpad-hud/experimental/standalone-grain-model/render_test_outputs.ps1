param(
    [string]$InputDir = "C:\Users\zebga\Documents\Codex\test_photos",
    [string]$OutputDir = "C:\Users\zebga\Documents\Codex\film_grain_standalone_template_v3\film_grain_standalone_v2\outputs\v4_preview_g4",
    [int]$Grain = 4,
    [int]$GrainSize = 1,
    [int]$ScaleDenom = 1,
    [switch]$EnableChromaGrain
)

$sourcePath = Join-Path $PSScriptRoot "film_grain_preview.cs"
$source = Get-Content -Raw -LiteralPath $sourcePath
$env:FILM_GRAIN_CACHE_DIR = Join-Path $PSScriptRoot "atlas_cache"

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition $source

$settings = New-Object FilmGrainPreview.GrainSettings
$settings.Grain = $Grain
$settings.GrainSize = $GrainSize
$settings.OutputScaleDenom = $ScaleDenom
$settings.EnableChromaGrain = [bool]$EnableChromaGrain
$settings.Seed = 0x13572468
$settings.BlockSize = 32
$settings.TemplateSize = 128

[FilmGrainPreview.BatchRenderer]::RenderFolder($InputDir, $OutputDir, $settings)
Write-Output "Rendered outputs to $OutputDir"
