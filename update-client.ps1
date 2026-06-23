# Update GolemMod client jar from latest GitHub release
# Run this script whenever you want to update your client mod

$repo = "Creeperhouse999/MinecraftGPT"
$modName = "MinecraftGPT"

# Find CurseForge mods folder automatically
$curseforgePaths = @(
    "$env:USERPROFILE\curseforge\minecraft\Instances",
    "$env:LOCALAPPDATA\CurseForge\minecraft\Instances",
    "C:\Users\$env:USERNAME\curseforge\minecraft\Instances"
)

$instancesDir = $null
foreach ($path in $curseforgePaths) {
    if (Test-Path $path) {
        $instancesDir = $path
        break
    }
}

if (-not $instancesDir) {
    Write-Host "CurseForge instances folder not found. Enter your mods folder path manually:"
    $modsDir = Read-Host "Mods folder path"
} else {
    # List instances and pick the right one
    $instances = Get-ChildItem $instancesDir -Directory
    Write-Host "Found instances:"
    for ($i = 0; $i -lt $instances.Count; $i++) {
        Write-Host "  [$i] $($instances[$i].Name)"
    }
    $idx = Read-Host "Pick instance number (or press Enter for 0)"
    if ($idx -eq "") { $idx = 0 }
    $modsDir = Join-Path $instances[$idx].FullName "mods"
}

if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir | Out-Null
}

Write-Host "Downloading latest release from GitHub..."
$apiUrl = "https://api.github.com/repos/$repo/releases/latest"
try {
    $release = Invoke-RestMethod -Uri $apiUrl -Headers @{Accept="application/vnd.github.v3+json"}
    $asset = $release.assets | Where-Object { $_.name -like "$modName-*.jar" } | Select-Object -First 1
    if (-not $asset) {
        Write-Host "ERROR: No jar found in latest release." -ForegroundColor Red
        exit 1
    }
    $jarPath = Join-Path $modsDir $asset.name
    # Remove old version
    Get-ChildItem $modsDir -Filter "$modName-*.jar" | Remove-Item -Force
    # Download new
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $jarPath
    Write-Host "Updated: $($asset.name)" -ForegroundColor Green
    Write-Host "Restart Minecraft to apply." -ForegroundColor Yellow
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
}
