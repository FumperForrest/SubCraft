# SubCraft — design notes

`MISSION.md` is the plan. This file records what turned out to be true while building it: verified
API facts, layout decisions and findings. Code wins over this file when they disagree.

## Link

- One file, `$TMPDIR/subcraft/link.bin` (macOS) / `%LOCALAPPDATA%\SubCraft\link.bin` (Windows),
  191 MiB, sparse. The host creates and sizes it, zeroes the control blocks and writes
  magic/version/pid; Minecraft maps it when it appears. A host restart is detected by its pid
  changing in the header (Minecraft then re-teleports and drops collision state).
- Layout: `protocol/subcraft_protocol.h` (v11). Rings carry total-bytes head/tail counters;
  messages are 8-byte aligned and never wrap (a pad message skips to the ring start).
- Clock: monotonic ns. macOS: HotSpot `System.nanoTime()` = `mach_absolute_time` in ns =
  `CLOCK_UPTIME_RAW` (measured against Python, 0.1 ms median difference).
- Java side (Java 21, no FFM): `MappedByteBuffer` + `byteBufferViewVarHandle` acquire/release.

## Guest frame order (Minecraft 1.21.1)

| When | Hook | Work |
|---|---|---|
| `Minecraft.runTick` head | mixin | poll link, read `HostState`, input ring -> GLFW handlers, teleport, host look |
| each client tick end | `ClientTickEvent.Post` | death -> event + respawn, hold until ground, publish physics tick |
| server tick end | `ServerTickEvent.Post` | collision ring -> ghost terrain blocks |
| after `GameRenderer.render` | `RenderFrameEvent.Post` | `McState`, overlay readback + publish |
| `Minecraft.runTick` tail | mixin | pace to one frame per host frame |

## Verified 1.21.1 facts

- `LevelRenderer.renderLevel` clears with the fog colour; skipping it and clearing to (0,0,0,0)
  leaves hand + GUI on transparent. `Gui.renderVignette` blends alpha with (ONE, ZERO) and must be
  skipped too. `GameRenderer.renderLevel` still runs, so picking (`pick`) and the hand work.
- `ReceivingLevelScreen` waits on `LevelRenderer.isSectionCompiled(playerPos)`.
- `WorldOpenFlows.openWorldCheckWorldStemCompatibility` -> `askForBackup` fires for datapack
  dimension types (lifecycle experimental).
- Retina: GLFW window size is in points; the framebuffer is 2x. Window size = viewport / content scale.
- `KeyboardHandler.keyPress(window, key, scancode, action, mods)` is public; `MouseHandler.onPress`,
  `onScroll`, `onMove` are private (invokers).
- The dimension type `min_y -2032, height 2288` is accepted.

## Open (Phase 0b)

- Subnautica render path, camera image effects, terrain/base/creature shaders -> material strategy
  for §3.2.
- Whether Subnautica's loading screens stall its main thread past the 2 s heartbeat timeout.
- Mono clock under Rosetta equals the JVM's.
