# Windows twin of mc_dev.sh: start / stop the dev Minecraft client (guest-neoforge runClient),
# never more than one.
#
#   tools\mc_dev.ps1 start [logfile]   # background; waits until the mod is loaded
#   tools\mc_dev.ps1 stop
#   tools\mc_dev.ps1 status
param([string]$Action = "status", [string]$Log = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jdk = if ($env:SUBCRAFT_GRADLE_JDK) { $env:SUBCRAFT_GRADLE_JDK } else { "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot" }
$match = "fml.modFolders=subcraft"

function Get-Running { Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$match*" } }

switch ($Action) {
	"start" {
		if (Get-Running) { Write-Host "dev client already running (pid $((Get-Running).ProcessId -join ' '))"; exit 1 }
		if (-not $Log) { $Log = Join-Path $env:TEMP ("subcraft-client-{0}.log" -f (Get-Date -Format yyyyMMdd-HHmmss)) }
		$err = "$Log.err"
		$env:JAVA_HOME = $jdk; $env:PATH = "$jdk\bin;$env:PATH"
		Start-Process -FilePath "$root\guest-neoforge\gradlew.bat" -ArgumentList "runClient", "--console=plain" `
			-WorkingDirectory "$root\guest-neoforge" -WindowStyle Hidden -RedirectStandardOutput $Log -RedirectStandardError $err | Out-Null
		Write-Host "starting dev client, log $Log"
		for ($i = 0; $i -lt 180; $i++) {
			$text = if (Test-Path $Log) { Get-Content $Log -Raw -ErrorAction SilentlyContinue } else { "" }
			if ($text -match "SubCraft client ready") { Write-Host "client up (pid $((Get-Running).ProcessId -join ' '))"; exit 0 }
			if ($text -match "BUILD FAILED|: error:") { Select-String -Path $Log -Pattern ": error:|FAILED" | ForEach-Object Line; exit 1 }
			Start-Sleep 2
		}
		Write-Host "timed out waiting for the client"; exit 1
	}
	"stop" {
		# Stop-Process can't ask a JVM to shut down cleanly on Windows: save the world first through
		# the command box (a hard kill loses everything since the last autosave: placed vehicles...).
		if (Get-Running) {
			$env:MSYS_NO_PATHCONV = "1"
			python "$PSScriptRoot\mc_cmd.py" "subcraft save" | Select-Object -Last 1
		}
		# Gradle's wrapper JVM and the Minecraft JVM both have to go.
		foreach ($p in Get-Running) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
		Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*GradleWrapperMain*runClient*" } |
			ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
		Start-Sleep 1
		if (Get-Running) { Write-Host "still running"; exit 1 } else { Write-Host "stopped" }
	}
	default {
		$r = Get-Running
		if ($r) { $r | Select-Object ProcessId, @{n = "WorkingSetMB"; e = { [int]($_.WorkingSetSize / 1MB) } } } else { Write-Host "not running" }
	}
}
