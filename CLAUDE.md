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
- .NET SDK 10 (`dotnet`), clang++, Python 3.9 (stdlib only: no numpy/PIL).
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
```

## Rules of thumb

- Protocol change: edit the header, mirror in `Proto.java` / `Proto.cs`, bump `kVersion`,
  `tools/check_layout.sh --write`, update `tools/*.py`.
- Seqlock and ring fields: acquire/release only (`VarHandle` on the Java side, `Volatile` /
  `Interlocked` on the C# side).
- Verify every game API against decompiled code or NeoForge sources; names in MISSION.md are leads.
- Change behaviour only in the SubCraft world/dimension, and only while linked.
- NeoForge events over Mixins; Mixins only `@Inject` / MixinExtras / accessors and invokers.
- Every session: a `docs/DEVLOG.md` entry, `docs/TESTING.md` up to date, small commits, push.
