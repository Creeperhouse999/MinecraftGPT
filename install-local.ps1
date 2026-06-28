# Install MinecraftGPT mod for local singleplayer
# Run once to set up, then run update-client.ps1 for future updates

$repo = "Creeperhouse999/MinecraftGPT"
$modName = "MinecraftGPT"
$fabricApiVersion = "0.153.0+1.26.2"  # adjust if needed
$mcVersion = "26.2"

Write-Host "=== MinecraftGPT Local Installer ===" -ForegroundColor Cyan

# ---- Find mods folder ----
$curseforgePaths = @(
    "$env:USERPROFILE\curseforge\minecraft\Instances",
    "$env:LOCALAPPDATA\CurseForge\minecraft\Instances",
    "C:\Users\$env:USERNAME\curseforge\minecraft\Instances"
)
$vanillaPath = "$env:APPDATA\.minecraft"

Write-Host ""
Write-Host "Where is your Minecraft?" -ForegroundColor Yellow
Write-Host "  [0] CurseForge (pick instance)"
Write-Host "  [1] Vanilla launcher (.minecraft)"
Write-Host "  [2] Enter path manually"
$launcherChoice = Read-Host "Choice"

$instanceDir = $null

if ($launcherChoice -eq "1") {
    $instanceDir = $vanillaPath
    $modsDir = Join-Path $vanillaPath "mods"
} elseif ($launcherChoice -eq "2") {
    $modsDir = Read-Host "Enter full path to mods folder"
    $instanceDir = Split-Path $modsDir
} else {
    $instancesDir = $null
    foreach ($path in $curseforgePaths) {
        if (Test-Path $path) { $instancesDir = $path; break }
    }
    if (-not $instancesDir) {
        Write-Host "CurseForge not found. Enter mods folder manually:" -ForegroundColor Red
        $modsDir = Read-Host "Mods folder path"
        $instanceDir = Split-Path $modsDir
    } else {
        $instances = Get-ChildItem $instancesDir -Directory
        Write-Host "Found instances:"
        for ($i = 0; $i -lt $instances.Count; $i++) {
            Write-Host "  [$i] $($instances[$i].Name)"
        }
        $idx = Read-Host "Pick instance number (or press Enter for 0)"
        if ($idx -eq "") { $idx = 0 }
        $instanceDir = $instances[$idx].FullName
        $modsDir = Join-Path $instanceDir "mods"
    }
}

if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir | Out-Null
}

Write-Host ""
Write-Host "Mods folder: $modsDir" -ForegroundColor Cyan

# ---- Download MinecraftGPT mod ----
Write-Host ""
Write-Host "Downloading MinecraftGPT mod..." -ForegroundColor Yellow
$apiUrl = "https://api.github.com/repos/$repo/releases/latest"
try {
    $release = Invoke-RestMethod -Uri $apiUrl -Headers @{Accept="application/vnd.github.v3+json"}
    $asset = $release.assets | Where-Object { $_.name -like "$modName-*.jar" } | Select-Object -First 1
    if (-not $asset) {
        Write-Host "ERROR: No jar found in latest release. Check GitHub Actions ran successfully." -ForegroundColor Red
        exit 1
    }
    Get-ChildItem $modsDir -Filter "$modName-*.jar" | Remove-Item -Force
    $jarPath = Join-Path $modsDir $asset.name
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $jarPath
    Write-Host "Downloaded: $($asset.name)" -ForegroundColor Green
} catch {
    Write-Host "ERROR downloading mod: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---- Check for Fabric API ----
Write-Host ""
$hasFabricApi = Get-ChildItem $modsDir -Filter "fabric-api-*.jar" -ErrorAction SilentlyContinue
if ($hasFabricApi) {
    Write-Host "Fabric API already present: $($hasFabricApi.Name)" -ForegroundColor Green
} else {
    Write-Host "Fabric API NOT found in mods folder." -ForegroundColor Red
    Write-Host "Download it from: https://modrinth.com/mod/fabric-api/versions?g=$mcVersion" -ForegroundColor Yellow
    Write-Host "Put the jar in: $modsDir"
    $open = Read-Host "Open mods folder now? (Y/n)"
    if ($open -ne "n") { Start-Process explorer $modsDir }
}

# ---- Set up config/golem.json for singleplayer ----
Write-Host ""
$configDir = Join-Path $instanceDir "config"
if (-not (Test-Path $configDir)) { New-Item -ItemType Directory -Path $configDir | Out-Null }
$configFile = Join-Path $configDir "golem.json"

if (Test-Path $configFile) {
    Write-Host "Config already exists: $configFile" -ForegroundColor Green
    $showConfig = Read-Host "Edit API keys now? (Y/n)"
    if ($showConfig -ne "n") { Start-Process notepad $configFile }
} else {
    Write-Host "Creating config/golem.json..." -ForegroundColor Yellow
    $apiKey = Read-Host "Enter your Groq API key (get free at console.groq.com, or leave blank)"
    $config = @{
        geminiKeys = @()
        model = "llama-3.3-70b-versatile"
    }
    if ($apiKey -ne "") { $config.geminiKeys = @($apiKey) }
    $config | ConvertTo-Json | Out-File -FilePath $configFile -Encoding utf8
    Write-Host "Config created: $configFile" -ForegroundColor Green
    if ($apiKey -eq "") {
        Write-Host "No API key entered — golem will spawn but won't understand commands." -ForegroundColor Yellow
        Write-Host "Edit $configFile later to add your Groq key." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== Done! ===" -ForegroundColor Cyan
Write-Host "1. Make sure Fabric loader is installed for MC $mcVersion"
Write-Host "2. Make sure Fabric API jar is in mods folder"
Write-Host "3. Launch Minecraft with the Fabric profile"
Write-Host "4. In a world, press G to open golem control, /golem spawn to spawn"
Write-Host ""
Write-Host "Get a free Groq API key at: https://console.groq.com" -ForegroundColor Yellow
