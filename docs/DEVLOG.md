# SubCraft dev log

Newest first. One entry per session (MISSION.md rule 11).

## 2026-10-01 — Session 2: Phase 0b (Subnautica link)

**Done**

- Licence: MIT (Sean's decision), `LICENSE` added.
- Saves backed up before anything ran: `~/Development/Modding/SubCraft-save-backups/` (SavedGames
  copy + exported Unity prefs). Sean's `slot0000`/`slot0001` diffed byte-identical afterwards.
- Decompiled Assembly-CSharp(+firstpass) with ilspycmd 11.1 into `.decompiled/` (git-ignored).
- **`host-subnautica`** (net472, BepInEx 5.4.23.5, HarmonyX, publicized Assembly-CSharp via
  BepInEx.AssemblyPublicizer.MSBuild, built with .NET SDK 10 on macOS, post-build deploy):
  - `src/Link`: Proto.cs (mirror of the header), Platform (CLOCK_UPTIME_RAW via libSystem, paths),
    LinkView (unsafe, Volatile/Interlocked), HostLink (creates the sparse 191 MiB file, magic
    written last), Coords (Unity <-> MC, look), KeyMap (Unity KeyCode -> GLFW);
  - `LinkDriver`/`HostState`: heartbeat, McState, events, HostState with camera look, viewport, day;
  - `Player/PlayerPuppet`: Harmony prefix skips `PlayerController.UpdateController` while
    Minecraft drives; camera placed at Minecraft's eye; teleport handshake;
  - `Player/InputCapture`: real keyboard/mouse -> GLFW events (Tab, Esc stay Subnautica's);
  - `Hud/OverlayView`: Minecraft's overlay drawn on top (IMGUI for now);
  - `Dev/DevHarness`, `StateDump`, `RenderRecon`: command-file harness (rule 12).
- **Tests (no game, net10.0 xunit):** layout vs `layout.json`, coordinate and look mapping (round
  trips, Unity euler yaw), key table, input/collision rings, McState seqlock under a writer,
  overlay exchange, HostLink file creation, clock equality with Python's CLOCK_UPTIME_RAW. 34/34.
- **Tools:** `tools/sn_dev.sh` (launch through Steam windowed 960x540, stop + restore prefs),
  `tools/sn_prefs.py`, `tools/sn_cmd.py` (harness client), `tools/fake_minecraft.py` (stand-in
  Minecraft), `tools/scenarios/phase0b.jsonl`, `load_dev_slot.jsonl`.
- **Guest fixes found by the two-game run:** hold the player until the destination chunk is on the
  client and there is ground or water (hold timer now resets per teleport); Minecraft never drowns
  the player in the SubCraft world while linked (host owns oxygen; Phase 1 mirrors it).

**Phase 0b result: done.**
- One game (Subnautica + `fake_minecraft.py`): link up, teleport acked, puppet takes over, injected
  W walks the Subnautica player 14 m; overlay test pattern drawn.
- Both games (Minecraft dev client + Subnautica, dev slot `slot0002`): W injected through the
  harness -> Minecraft's physics moves its player 8 blocks underwater (sinking, in-water flags) ->
  Subnautica's player and camera follow exactly (`docs/screens/0b-before-w.png`, `0b-after-w.png`:
  Minecraft hearts, air, hunger and hotbar over Subnautica's shallows).
- Hard-killing Minecraft returns control to Subnautica after 2.09 s (2.0 s timeout + polling).
- Render-path recon written up in `docs/DESIGN.md` (deferred, HDR, linear, MarmosetUBER
  everywhere, Subnautica's own waterscape fog, WBOIT transparency) with the 0c material strategy.

**Bugs found and fixed on the way:** Steam asks for confirmation when a `steam://run` URL carries
arguments (dev window size moved to Unity prefs); PlistBuddy splits keys on spaces, so the first
prefs restore silently did nothing (rewritten with plistlib, verified); new-game setup briefly uses
a scratch slot named `test`, which the dev-slot detection grabbed; a harness teleport during a
pending teleport got yanked back (re-teleport when Minecraft and host disagree by > 2 m); a
released player in an unloaded chunk fell to y -800 and dragged Subnautica's player with it.

**Decisions**
- Puppet anchors on the eye (camera at Minecraft's eye; host feet = camera - 1.62), not the
  collider, because Subnautica's collider shrinks while swimming.
- Subnautica keeps the mouse look (host owns the look, as SkyCraft); Tab and Esc stay Subnautica's.
- Fail-safe stays at 2 s even though Subnautica's loading stalls its heartbeat 2-6 s: Minecraft
  pausing during loading is harmless and resumes on its own.

**Unverified / not done yet**
- Real keyboard input from a person (all W tests injected through the harness on the same ring
  `InputCapture` writes to). Sean's TESTING step covers it.
- Subnautica still acts on routed keys too (number keys select both hotbars; left click uses
  Subnautica's tool) — Phase 1 input routing.
- Minecraft screens (inventory) can't get the cursor yet — Phase 1.
- Overlay is drawn with IMGUI over everything including Subnautica's PDA — Phase 1 moves it to a
  uGUI canvas below Subnautica's menus.

**Memory (both games):** Minecraft RSS ~0.6-0.9 GB (heap ~0.7 GB used of 2), Subnautica RSS
~0.26 GB at the menu (macOS compresses heavily); swap used 6.7-7.4 GB of 8 GB with Brave and the
Claude app also open. Frame rate dipped to single digits during some seconds; usable for scripted
checks. Two-game runs should stay short on this machine (rule 15).

**Next:** Phase 0c — the look spike: capture a small Minecraft scene (cobblestone, glass, oak
leaves, torch, glowstone, chest) to a dump file, load it in Subnautica with MarmosetUBER
materials and point lights, screenshot noon/dusk/night+flashlight/underwater/above/far.

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
