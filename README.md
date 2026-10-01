# SubCraft

Play Subnautica as a Minecraft player: one world, one light, one sound, one physics.

A hidden Minecraft (1.21.1, NeoForge) runs beside Subnautica. The two talk through one shared
memory file with a fixed binary protocol. **Minecraft owns the player**: physics, inventory,
combat math, blocks and Minecraft mobs. **Subnautica owns the world**: terrain, creatures, light,
sound and saves, and it draws every pixel of the 3D scene, including Minecraft's blocks and mobs,
which it captures as geometry and lights like its own.

Status: early development. See `MISSION.md` for the plan, `docs/DEVLOG.md` for progress and
`docs/TESTING.md` for what to try.

## Repository

| Path | What |
|---|---|
| `protocol/` | The shared-memory protocol (C++ header = source of truth, `layout.json` for tests) |
| `guest-neoforge/` | The Minecraft mod |
| `host-subnautica/` | The Subnautica BepInEx plugin |
| `tools/` | Stand-ins for either game, dump and debug tools |
| `docs/` | Design notes, dev log, test plan |

## Credits

SubCraft's architecture comes from [SkyCraft](https://github.com/chasmlol/SkyCraft) by chasmlol
(MIT), which does the same for Skyrim. Parts of its protocol and link code are derived from
SkyCraft; see `THIRD-PARTY-NOTICES.md`.

Not affiliated with or endorsed by Mojang, Microsoft or Unknown Worlds.
