# Skill Builder writer. Reads JSON arguments on stdin and writes a new instruction-only
# GoalMaker skill at skills/<skill_name>/SKILL.md. Emits a confirmation on stdout.
$ErrorActionPreference = 'Stop'

$raw = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($raw)) {
    Write-Output 'ERROR: no JSON arguments were provided on stdin.'
    exit 1
}

try {
    $request = $raw | ConvertFrom-Json
} catch {
    Write-Output "ERROR: arguments were not valid JSON: $($_.Exception.Message)"
    exit 1
}

function Field([object]$source, [string]$key) {
    $property = $source.PSObject.Properties[$key]
    if ($null -eq $property -or $null -eq $property.Value) { return '' }
    return [string]$property.Value
}

$rawName = (Field $request 'skill_name').Trim()
$description = (Field $request 'description').Trim()
$whenToUse = (Field $request 'when_to_use').Trim()
$instructions = (Field $request 'instructions').Trim()
$overwrite = $false
$overwriteProp = $request.PSObject.Properties['overwrite']
if ($null -ne $overwriteProp) { $overwrite = [bool]$overwriteProp.Value }

# Normalize the name to a safe kebab-case slug.
$slug = $rawName.ToLowerInvariant() -replace '[^a-z0-9]+', '-'
$slug = $slug.Trim('-')
if ([string]::IsNullOrWhiteSpace($slug)) {
    Write-Output 'ERROR: skill_name has no usable characters.'
    exit 1
}
foreach ($pair in @(@('description', $description), @('when_to_use', $whenToUse), @('instructions', $instructions))) {
    if ([string]::IsNullOrWhiteSpace($pair[1])) {
        Write-Output "ERROR: $($pair[0]) is required."
        exit 1
    }
}

# Default to a no-input schema unless a JSON Schema object is supplied.
$parametersCompact = '{"type":"object","properties":{}}'
$parametersJson = (Field $request 'parameters_json').Trim()
if (-not [string]::IsNullOrWhiteSpace($parametersJson)) {
    try {
        $parametersObject = $parametersJson | ConvertFrom-Json
    } catch {
        Write-Output "ERROR: parameters_json is not valid JSON: $($_.Exception.Message)"
        exit 1
    }
    $parametersCompact = $parametersObject | ConvertTo-Json -Compress -Depth 20
}

$skillsRoot = Split-Path -Parent $PSScriptRoot
$targetDir = Join-Path $skillsRoot $slug
$targetFile = Join-Path $targetDir 'SKILL.md'
if ((Test-Path $targetFile) -and -not $overwrite) {
    Write-Output "ERROR: skill '$slug' already exists. Pass overwrite=true to replace it."
    exit 1
}
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

# JSON-encode the scalar front-matter values; JSON is valid YAML, so this safely quotes/escapes them.
$nameYaml = $slug | ConvertTo-Json
$descriptionYaml = $description | ConvertTo-Json

$frontMatter = "---`n" +
    "name: $nameYaml`n" +
    "description: $descriptionYaml`n" +
    "parameters: $parametersCompact`n" +
    "---`n"
$body = "# $slug`n`n" +
    "## When to use`n`n$whenToUse`n`n" +
    "## Best practices`n`n$instructions`n"
$content = ($frontMatter + $body) -replace "`r`n", "`n"

# Write UTF-8 without a BOM so the loader's front-matter parser accepts the file.
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($targetFile, $content, $encoding)

Write-Output "Created skill '$slug' at skills/$slug/SKILL.md."
Write-Output "Reload the skill catalog or restart GoalMaker to activate it; it will expose tool skill_$slug."
