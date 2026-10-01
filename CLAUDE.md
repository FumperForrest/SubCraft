# SubCraft — working notes for Claude

Read `MISSION.md` first: it is the directive (architecture, fixed decisions, phases, ground rules).
This file is the practical how-to for this repo and this machine.

## Machine (Sean's Mac)

- Apple Silicon, **8 GB RAM**, disk often nearly full: check `df -h ~` before big downloads.
- JDKs: Temurin 17, 25, 27 (no 21 installed). **Run Gradle with JDK 17**:
  `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`.
  Gradle's toolchain (foojay) provisions Java 21 for compiling and running Minecraft.
- Subnautica: `~/Library/Application Support/Steam/steamapps/common/Subnautica` (BepInEx 5.4.23.5
  already installed, Steam launch options route through it). Launch with `open steam://run/264710`.
- .NET SDK 10 (`dotnet`), ilspycmd (`~/.dotnet/tools`, needs `DOTNET_ROLL_FORWARD=Major`), clang++,
  Python 3.9 (stdlib only: no numpy/PIL).
- Subnautica: Unity 2019.4.36f1 Mono, OpenGL 4.1, saves in `Subnautica.app/SNAppData/SavedGames`.
- SkyCraft reference clone: `../SkyCraft`. It targets Minecraft 26.3/Fabric (SDL, renderpearl),
  so its client code ports as ideas, not line for line.

## Layout

```
protocol/         subcraft_protocol.h (source of truth), layout_dump.cpp, layout.json (generated)
guest-neoforge/   NeoForge 1.21.1 mod (dev.subcraft.*), ModDevGradle, Gradle 8.11 wrapper
host-subnautica/  BepInEx 5 plugin (C#, net472)
tools/            fake_host.py, check_layout.sh, ... (Python 3.9 stdlib only)
docs/             DESIGN.md, DEVLOG.md, TESTING.md, look/
compat/           mod lists and per-phase results
```

## Commands

```sh
tools/check_layout.sh            # header -> layout.json still matches (use --write after a deliberate change)
cd guest-neoforge && ./gradlew build test      # build the mod, run the no-game tests
cd guest-neoforge && ./gradlew runClient       # dev client (2 GB heap), game dir guest-neoforge/run
python3 tools/fake_host.py 60 tools/out/overlay.png   # stand-in Subnautica for one-game tests
tools/mc_dev.sh start|stop|status          # exactly one dev Minecraft (never pkill by hand)
cd host-subnautica && dotnet build SubCraft.Host.csproj   # build + deploy plugin (game must be closed)
cd host-subnautica/tests && dotnet test    # host no-game tests (net10.0)
tools/sn_dev.sh start|stop|status|log      # Subnautica via Steam, 960x540; stop restores Sean's prefs
python3 tools/sn_cmd.py '{"cmd":"dump"}'   # dev harness; --scenario tools/scenarios/*.jsonl
python3 tools/fake_minecraft.py 60         # stand-in Minecraft for one-game Subnautica tests
tools/sn_restart.sh [scenario.jsonl]       # rebuild+deploy plugin, restart Subnautica, load dev slot
python3 tools/mc_cmd.py "subcraft tris"    # command into the running Minecraft (also "/..." commands)
python3 tools/capture_dump.py f.scdump --preview out.png   # inspect a capture dump without games
python3 tools/look_shots.py docs/look      # Phase 0c screenshot set (scene placed by look_place.jsonl)
```

Harness commands (`host-subnautica/src/Dev/DevHarness.cs`): wait, screenshot, dump, recon,
console, teleport, look, time, key, newgame, load, save, skipintro, waitingame, quit, loaddump,
unloaddump, matinfo, matset, lightset, rendinfo, equip, holster, colprobe, harvestprobe, tricheck.
Minecraft's own: `subcraft dump|tris|pos` and any `/command` (DebugCommands.java).

Harness rules: the dev save slot is `slot0002` (BepInEx config `DevSlot`); never load, save or
touch `slot0000`/`slot0001` (Sean's). Back up `SNAppData/SavedGames` before save-related work.
Run `dotnet build-server shutdown` after builds to free memory before launching games.

## Rules of thumb

- Protocol change: edit the header, mirror in `Proto.java` / `Proto.cs`, bump `kVersion`,
  `tools/check_layout.sh --write`, update `tools/*.py`.
- Seqlock and ring fields: acquire/release only (`VarHandle` on the Java side, `Volatile` /
  `Interlocked` on the C# side).
- Verify every game API against decompiled code or NeoForge sources; names in MISSION.md are leads.
- Change behaviour only in the SubCraft world/dimension, and only while linked.
- NeoForge events over Mixins; Mixins only `@Inject` / MixinExtras / accessors and invokers.
- Every session: a `docs/DEVLOG.md` entry, `docs/TESTING.md` up to date, small commits, push.
- Subnautica facts that cost time (details in `docs/DESIGN.md`): props/creatures use MarmosetUBER,
  ambient comes from `SkyApplier`, the Low preset drops top mips (no mips on our textures), terrain
  collision meshes are cleared after cooking (we capture them), terrain collision exists only in a
  5x5x5 window of 16-block cells aligned with Minecraft sections.
- Sean's goal: the Minecraft character fully replaces Subnautica's diver (hands, tools, body).
