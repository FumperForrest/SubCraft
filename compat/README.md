# Compatibility packs

Re-run from Phase 2 on (MISSION.md §3.5). Not run on the 8 GB dev Mac (rule 15): these are for a
bigger machine or a later session.

## Minecraft (NeoForge 1.21.1)

| Mod | Why | Pinned version |
|---|---|---|
| Create | contraptions, Flywheel instancing | — |
| Create: Aeronautics + Sable | moving structures, physics vs ghost terrain | — |
| JEI or EMI | item bridge, recipe GUIs in the overlay | — |
| Jade | HUD overlay from another mod | — |
| One Fabric mod via Sinytra Connector | Connector path | — |
| Aquatic mob mods (Sean picks) | modded ocean spawns | — |

## Subnautica (BepInEx 5)

| Mod | Why | Pinned version |
|---|---|---|
| Nautilus | modded TechTypes, soft dependency | — |
| Most-downloaded creature mod | modded creature proxies | — |
| Most-downloaded vehicle mod | modded vehicles | — |
| Most-downloaded base-piece mod | modded habitats | — |

## Waiting for this machine (from Phase 3)

These Phase 3 items need the mods above and are not built or verified on the dev Mac:

- **Flywheel** (Create's instanced parts): a SubCraft backend, or a Mixin on its instancer, sending
  models once and instance arrays per frame. Today Flywheel-drawn parts are simply missing.
- **Fallback layer** for draws the capture can't classify (custom shaders, raw GL, level-stage
  events): every such RenderType is logged once in Minecraft's log as
  `capturing fallback: <type> failed: ... render type not captured` -> collect those lines.
- **Moving structures' colliders** (contraptions, Aeronautics ships) for Unity.
- Done-when to check: a Create water wheel and gearbox turn, lit by Subnautica; the compat pack
  runs with fallback draws logged.

## Results

None yet.
