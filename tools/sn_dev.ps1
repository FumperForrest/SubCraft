# Windows twin of sn_dev.sh: start / stop Subnautica for dev runs (through Steam, so BepInEx
# loads), windowed at a low resolution. Unity keeps the screen prefs in the registry
# (HKCU\Software\Unknown Worlds\Subnautica); the originals are saved to
# %USERPROFILE%\Documents\Development\Modding\SubCraft-save-backups\subnautica-prefs-original.reg
# on the first `start` and put back by `stop`.
#
#   tools\sn_dev.ps1 start [width height]   # waits until the SubCraft plugin has loaded
#   tools\sn_dev.ps1 stop
#   tools\sn_dev.ps1 status | log [n]
param([string]$Action = "status", [string]$A1 = "", [string]$A2 = "")
$ErrorActionPreference = "Stop"
$SN = if ($env:SUBNAUTICA_DIR) { $env:SUBNAUTICA_DIR } else { "C:\Program Files (x86)\Steam\steamapps\common\Subnautica" }
$LOG = Join-Path $SN "BepInEx\LogOutput.log"
$KEY = "HKCU:\Software\Unknown Worlds\Subnautica"
$BACKUPDIR = Join-Path $env:USERPROFILE "Documents\Development\Modding\SubCraft-save-backups"
$BACKUP = Join-Path $BACKUPDIR "subnautica-prefs-original.json"
$PREFS = "Screenmanager Resolution Width", "Screenmanager Resolution Height", "Screenmanager Fullscreen mode", "Screenmanager Resolution Use Native"

function Get-Running { Get-Process Subnautica -ErrorAction SilentlyContinue }

# Unity appends "_h<hash>" to each PlayerPrefs value name; match by prefix.
function Get-PrefNames { if (Test-Path $KEY) { (Get-Item $KEY).GetValueNames() } else { @() } }
function Find-Pref($base) { Get-PrefNames | Where-Object { $_ -like "$base`_h*" } | Select-Object -First 1 }

function Save-Prefs {
	if (Test-Path $BACKUP) { return }
	New-Item -ItemType Directory -Force $BACKUPDIR | Out-Null
	$saved = @{}
	foreach ($b in $PREFS) { $n = Find-Pref $b; if ($n) { $saved[$b] = @{ name = $n; value = (Get-ItemProperty $KEY).$n } } }
	$saved | ConvertTo-Json -Depth 4 | Set-Content $BACKUP
	Write-Host "saved original screen prefs to $BACKUP"
}

function Restore-Prefs {
	if (-not (Test-Path $BACKUP)) { return }
	$saved = Get-Content $BACKUP -Raw | ConvertFrom-Json
	foreach ($b in $PREFS) {
		$e = $saved.$b
		if ($e) { Set-ItemProperty $KEY -Name $e.name -Value ([int]$e.value) -Type DWord }
	}
	Write-Host "restored Subnautica screen prefs"
}

function Set-Pref($base, $value) {
	New-Item -Path $KEY -Force | Out-Null
	$n = Find-Pref $base
	if (-not $n) { Write-Host "no '$base' pref yet (run Subnautica once so Unity writes it); skipping"; return }
	Set-ItemProperty $KEY -Name $n -Value $value -Type DWord
}

switch ($Action) {
	"start" {
		if (Get-Running) { Write-Host "Subnautica already running (pid $((Get-Running).Id -join ' '))"; exit 1 }
		$w = if ($A1) { [int]$A1 } else { 960 }; $h = if ($A2) { [int]$A2 } else { 540 }
		Save-Prefs
		$before = if (Test-Path $LOG) { (Get-Item $LOG).LastWriteTimeUtc } else { [datetime]::MinValue }
		Set-Pref "Screenmanager Resolution Width" $w
		Set-Pref "Screenmanager Resolution Height" $h
		Set-Pref "Screenmanager Fullscreen mode" 3
		Set-Pref "Screenmanager Resolution Use Native" 0
		Start-Process "steam://run/264710"
		Write-Host "launching Subnautica ${w}x${h} windowed through Steam"
		for ($i = 0; $i -lt 240; $i++) {
			if ((Test-Path $LOG) -and ((Get-Item $LOG).LastWriteTimeUtc -ne $before)) {
				$t = Get-Content $LOG -Raw -ErrorAction SilentlyContinue
				if ($t -match "SubCraft .* loaded") { Write-Host "plugin loaded (pid $((Get-Running).Id -join ' '))"; exit 0 }
			}
			Start-Sleep 1
		}
		Write-Host "timed out waiting for the SubCraft plugin; see $LOG"; exit 1
	}
	"stop" {
		$p = Get-Running
		if ($p) {
			$p | ForEach-Object { $null = $_.CloseMainWindow() }
			for ($i = 0; $i -lt 20 -and (Get-Running); $i++) { Start-Sleep 1 }
			Get-Running | Stop-Process -Force -ErrorAction SilentlyContinue
			Start-Sleep 1
		}
		Restore-Prefs
		if (-not (Get-Running)) { Write-Host "stopped" }
	}
	"log" {
		$n = if ($A1) { [int]$A1 } else { 40 }
		Select-String -Path $LOG -Pattern "SubCraft" | Select-Object -Last $n | ForEach-Object Line
	}
	default {
		$r = Get-Running
		if ($r) { $r | Select-Object Id, @{n = "WorkingSetMB"; e = { [int]($_.WorkingSet64 / 1MB) } } } else { Write-Host "not running" }
	}
}
