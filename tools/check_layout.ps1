# Windows twin of check_layout.sh: regenerates protocol/layout_dump.cpp (tools/gen_layout_dump.py)
# and protocol/layout.json from the C++ header and fails if either differs from the committed file.
# Pass -Write after a deliberate protocol change.
# Needs g++ (`winget install BrechtSanders.WinLibs.POSIX.UCRT`), clang++ with an STL, or MSVC cl from a VS prompt.
param([switch]$Write)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $env:TEMP "subcraft_layout_dump.exe"
$src = "$root\protocol\layout_dump.cpp"
if ($Write) { python "$root\tools\gen_layout_dump.py" } else { python "$root\tools\gen_layout_dump.py" --check }
if ($LASTEXITCODE -ne 0) { exit 1 }
# winget's WinLibs (g++) is not put on PATH until the next login; look for it.
if (-not (Get-Command g++ -ErrorAction SilentlyContinue)) {
	$g = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter g++.exe -ErrorAction SilentlyContinue | Select-Object -First 1
	if ($g) { $env:PATH = "$($g.DirectoryName);$env:PATH" }
}
if (Get-Command g++ -ErrorAction SilentlyContinue) { g++ -std=c++20 -Wall -Wextra -Werror -o $exe $src }
elseif (Get-Command clang++ -ErrorAction SilentlyContinue) { clang++ -std=c++20 -Wall -Wextra -Werror -o $exe $src }
elseif (Get-Command cl -ErrorAction SilentlyContinue) { cl /nologo /std:c++20 /W4 /WX /EHsc "/Fe:$exe" "/Fo:$env:TEMP\" $src }
else { Write-Host "no C++ compiler found (winget install BrechtSanders.WinLibs.POSIX.UCRT)"; exit 2 }
if ($LASTEXITCODE -ne 0) { exit 1 }
$out = (& $exe) -join "`n"
if ($Write) { [IO.File]::WriteAllText("$root\protocol\layout.json", $out + "`n"); Write-Host "wrote protocol/layout.json"; exit 0 }
$want = ([IO.File]::ReadAllText("$root\protocol\layout.json")) -replace "`r`n", "`n"
if ($want.TrimEnd() -eq $out.TrimEnd()) { Write-Host "layout.json matches subcraft_protocol.h" } else { Write-Host "layout.json differs from the header"; exit 1 }
