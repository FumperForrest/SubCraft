# Pinned versions

Change these deliberately, with a DEVLOG note (MISSION.md rule 14).

| Component | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | |
| NeoForge | 21.1.252 | `guest-neoforge/gradle.properties` |
| ModDevGradle | 2.0.148 | `guest-neoforge/build.gradle` |
| Gradle | 8.11 | wrapper; runs on any JDK 17-23 (dev machine: Temurin 17) |
| Java (Minecraft) | 21 | provisioned by the Gradle toolchain (foojay) |
| Subnautica | Steam, macOS (x86_64 under Rosetta), changeSet 71288 (**check**: the code only compiles against Subnautica.GameLibs 82304, see below) | Unity 2019.4.36f1, OpenGL 4.1 (Metal) |
| BepInEx | 5.4.23.5 | from the installed `BepInEx/LogOutput.log` |
| Nautilus | — | not installed yet (compat phase) |
| BepInEx.AssemblyPublicizer.MSBuild | 0.4.2 | publicizes Assembly-CSharp at build time |
| Microsoft.NETFramework.ReferenceAssemblies | 1.0.3 | net472 on macOS |
| .NET SDK (build/tests) | 10.0.401 | host tests run on net10.0 |
| Flywheel / Create / Sable / Aeronautics / Connector | — | compat phase |
| Subnautica.GameLibs (compile check only) | 82304.0.0-r.0 | `host-subnautica/compile-check`, from nuget.bepinex.dev; 71288 lacks `GameInputLegacy/System/Steam` and `GameInput.Button.Look`, which the plugin uses |
| BepInEx.Core / UnityEngine.Modules (compile check only) | 5.4.21 / 2019.4.36 | reference assemblies for the compile check |
