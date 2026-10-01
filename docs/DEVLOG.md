# SubCraft dev log

Newest first. One entry per session (MISSION.md rule 11).

## 2026-10-01 — Session 1: skeleton and Phase 0a

**Done**

- Repo skeleton (MISSION.md §5): `protocol/`, `guest-neoforge/`, `tools/`, `docs/`, `compat/`,
  `CLAUDE.md`, `README.md`, `THIRD-PARTY-NOTICES.md`, `versions.md`, `.gitignore`.
- Read SkyCraft (`../SkyCraft`, commit `bfcaf17`). It targets Minecraft 26.3/Fabric (SDL input,
  renderpearl GPU API), so its client code ports as ideas; protocol, link and fake host port closely.
- **Protocol v11** (`protocol/subcraft_protocol.h`): SkyCraft v11's layout with a file mapping,
  monotonic-ns timestamps, GLFW key/button codes, a host-neutral `HostState`, survival stats in
  `McState` (0xE0 bytes), a creature table and a generic event ring. Skyrim-only blocks (water grid,
  world entities, dig, ragdoll, skills) dropped. `layout.json` is generated from the header by
  `protocol/layout_dump.cpp` (`tools/check_layout.sh`), and the Java test checks every constant and
  field offset against it.
- **Guest** (NeoForge 21.1.252, ModDevGradle 2.0.148, Gradle 8.11, Java 21 toolchain):
  - link: `Proto`, `Platform` (paths, clock, running lock), `LinkView` (all rings and seqlocks),
    `SubLink` (open/heartbeat/host-restart detection);
  - client: `SubClient` (frame glue, teleport/hold, pacing, fail-safe pause), `InputBridge` (GLFW
    events into KeyboardHandler/MouseHandler), `OverlayExporter` (colour texture -> overlay triple
    buffer), `DevWorld` (opens/creates the dedicated "SubCraft Dev" world);
  - world: `subcraft:terrain` ghost block (invisible, unbreakable, waterloggable, dark to sky light),
    SubCraft dimension type (min_y -2032, height 2288 — Minecraft accepts it) with water below y 0,
    collision ring -> terrain blocks (`CollisionConsumer`, Phase 0a "temporary injection": any
    occupied cell is a full block).
  - mixins (all `@Inject`, MixinExtras or accessors/invokers): runTick head/tail, isWindowActive,
    InputConstants.isKeyDown/grabOrReleaseMouse, LevelRenderer.renderLevel (skip + transparent
    clear) and isSectionCompiled, Gui.renderVignette, WorldOpenFlows backup prompt, invokers for
    MouseHandler and GameRenderer.getFov.
- **Tools:** `tools/fake_host.py` (stand-in host with self-checks), `tools/mc_dev.sh` (start/stop
  exactly one dev client).
- **Tests (no game):** layout (Java vs `layout.json`), input-ring wrap and lapping, collision-ring
  padding, render-ring full/wrap, seqlock under a concurrent writer, overlay triple buffer. 11/11 pass.

**Phase 0a result: done.** `fake_host.py` against the hidden dev client:
linked and heartbeating; clocks agree (median host-now minus MC heartbeat 0.1 ms); player teleported
onto the fake floor and released once ground arrived; W walks it +12.5 blocks on Minecraft physics;
Space jumps it onto the 1-block step (peak y 66.25); the overlay PNG shows hotbar, hearts, hunger,
crosshair and hand on a transparent background. Two host sessions in a row against one Minecraft
pass (re-teleport on new host instance). A 240 s run held ~70 fps hidden the whole time.

**Decisions (flagged where Sean may want a say)**

- *Java 21 has FFM only as a preview*, so the link does not use `FileChannel.map(..., Arena)`:
  it maps with a plain `MappedByteBuffer` and does every seq/head/tail access through
  `MethodHandles.byteBufferViewVarHandle` acquire/release. Monotonic clock = `System.nanoTime()`,
  which on macOS HotSpot is `mach_absolute_time` in ns (CLOCK_UPTIME_RAW) — measured equal to
  Python's `clock_gettime_ns(CLOCK_UPTIME_RAW)` within 0.1 ms. The Mono side must use the same
  clock (Phase 0b: P/Invoke `clock_gettime_nsec_np(CLOCK_UPTIME_RAW)`; verify under Rosetta).
- *World is the overworld slot of a custom preset* (`subcraft:subnautica`), not an extra dimension:
  one dimension type, flat generator with 2032 layers of water. First creation took ~50 s; opening
  it afterwards ~2 s.
- *Host owns the look* (as in SkyCraft): MC's yaw/pitch are set from `HostState` every frame.
- *Death, interim:* while linked, MC reports `kEvPlayerDied` and respawns at once (its death screen
  can't be clicked in a hidden window); the host's death flow replaces this in Phase 5.
- *Fail-safe:* host heartbeat older than 2 s -> Minecraft opens its pause screen (and closes it when
  the host returns). SkyCraft used 8 s because of loading screens; revisit if Subnautica's loading
  stalls its heartbeat (Phase 0b).
- *Vignette off while the host draws the world* (it wrote alpha 1 over the whole overlay).
- `mod_license` in `gradle.properties` is MIT (to match SkyCraft); there is no LICENSE file yet.
  **Sean decides the project licence.**

**Bugs found and fixed on the way:** `pkill -f forgeclientdev` never matched (the dev client is
started through an argfile), so three clients ran at once and fought over the link — now
`tools/mc_dev.sh` matches `fml.modFolders=subcraft`. Accessibility onboarding and the experimental
backup prompt blocked the hidden client. "Loading terrain" never closed because no section is ever
compiled. The vignette made the overlay opaque.

**Unverified**

- App Nap with Minecraft launched by **Prism** (an app bundle) rather than Gradle — the Gradle-launched
  client was never throttled in 4 minutes. Recheck in Phase 5's launch work.
- Overlay readback cost (synchronous `glGetTexImage`): fine at 960x540; measure at full resolution.
- First-person hand looks dull because the skipped world pass leaves its lightmap unset (Phase 3
  captures the hand anyway).

**Memory:** dev client heap peaked ~1.1 GB of 2 GB; RSS read by `ps` 140–930 MB (macOS compresses,
so RSS undercounts). Free disk ~14 GB after Gradle, Java 21, Minecraft and NeoForge caches.

**Next:** Phase 0b — `host-subnautica` scaffold (net472, BepInEx 5, Harmony, publicized
Assembly-CSharp), `Proto.cs` + layout test, `Platform` clock under Rosetta, player puppet,
render-path reconnaissance, `tools/fake_minecraft.py`, dev harness (command file, screenshots,
state dumps). Back up Subnautica saves before running any save-related code.
