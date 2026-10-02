# Windows twin of sn_restart.sh: rebuild + redeploy the plugin, restart Subnautica, load the dev
# slot, run an optional scenario.   tools\sn_restart.ps1 [scenario.jsonl]
param([string]$Scenario = "")
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
& "$PSScriptRoot\sn_dev.ps1" stop | Out-Null
Push-Location "$root\host-subnautica"
dotnet build SubCraft.Host.csproj 2>&1 | Select-String -Pattern " error |deployed"
Pop-Location
dotnet build-server shutdown 2>&1 | Out-Null
& "$PSScriptRoot\sn_dev.ps1" start
if ($LASTEXITCODE -ne 0) { exit 1 }
$cmd = "$root\tools\sn_cmd.py"
$i = 0
while ($true) {
	# Already in game (someone loaded by hand): stop asking the menu to load.
	python $cmd '{\"cmd\":\"waitingame\",\"timeout\":1}' *> $null
	if ($LASTEXITCODE -eq 0) { break }
	python $cmd '{\"cmd\":\"load\"}' *> $null
	if ($LASTEXITCODE -eq 0) { break }
	if (++$i -gt 60) { Write-Host "main menu never accepted load"; exit 1 }
	python $cmd '{\"cmd\":\"wait\",\"seconds\":3}' *> $null
}
python $cmd '{\"cmd\":\"waitingame\",\"timeout\":300}'
if ($Scenario) { python $cmd --scenario $Scenario }
exit 0
