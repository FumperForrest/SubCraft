# SubCraft — tests for Sean

Things only you can judge (feel, taste) or decide. Everything here was already run by Claude
unless marked **unverified**.

## Branch `arch-review-2026-10-03` (cloud session, 2026-10-03): test this first

Made overnight without the games (details: `docs/ARCH-REVIEW.md` section 6). **Protocol v26:**
rebuild and deploy both sides together (`mc_dev.ps1 stop`, `sn_restart.ps1` rebuilds the plugin,
`mc_dev.ps1 start` rebuilds the mod). **Unverified in game**; everything below passed the no-game
checks (`tools/check_cloud.sh`, CI).

1. **Smoke:** `python tools\smoke.py` -> 12/12, and `docs/look` shots still look the same (the
   host now validates every render message before reading it; a well-formed one draws as before).
2. **Minecraft restart with Subnautica running (overlay fix):** `mc_dev.ps1 stop`, `mc_dev.ps1
   start`, twice. Expected: the hotbar and screens draw cleanly after each restart (before: every
   third frame could tear until Subnautica restarted).
3. **Subnautica restart with Minecraft running (collision epoch + version check):** restart
   Subnautica only. Expected: Minecraft's log shows `host instance changed`, then `collision epoch
   <big number>`; you can walk and swim on terrain right away; no invisible walls or falling
   through where you were before.
4. **Box-collider props (winding fix):** stand next to a crate or locker (lifepod, wrecks) and run
   `python tools/mc_cmd.py "subcraft whysolid <x> <y> <z>"` on a block inside it: now solid ghost
   terrain. A zombie should no longer walk through such props. The player should walk on them as
   before.
5. **Memory (native leak fix):** play linked for 30 minutes; Minecraft's process memory (Task
   Manager) should level off instead of climbing ~150 MB an hour.
6. **Logs to send back:** any line with `malformed render message`, `render ring framing broken`,
   `collision ring framing broken`, `render message failed` or `collision message failed`. None
   are expected in normal play; each one is a real bug that used to crash or stall instead.
7. **Which Subnautica build?** The plugin only compiles against the 82304 game libraries, but
   `versions.md` says changeSet 71288. Tell me the build number on Subnautica's main menu.

Proposed host fixes waiting for an in-game test (not on this branch): `ARCH-REVIEW.md` 6.2.

## Play it (Windows)

From PowerShell in the repo root:

```powershell
powershell -ExecutionPolicy Bypass -File tools\mc_dev.ps1 start      # hidden Minecraft (waits until ready)
powershell -ExecutionPolicy Bypass -File tools\sn_restart.ps1        # build + deploy the plugin, start Subnautica, load the dev game
```

`python tools\smoke.py` checks the essentials in a minute (12 checks, all [ok] on 2026-10-02).
Then click into the Subnautica window and play. Minecraft's keys work (WASD, Space, Shift, Ctrl,
E inventory, F5 camera, 1-9 hotbar, Q drop); Tab is Subnautica's PDA, Esc Subnautica's pause.
When done: `tools\sn_dev.ps1 stop` (puts your Subnautica window settings back) and
`tools\mc_dev.ps1 stop`. The dev game is `slot0004` (`DevSlot` in
`BepInEx\config\dev.subcraft.host.cfg`); your own saves are never touched.

## Phase 4 — combat: please try it first

What it does: hit Subnautica's creatures with Minecraft's weapons (sword, axe, bow, crits, sweeps):
they take the damage (x5) and get knocked back. Creatures that bite you hurt your Minecraft
character (x0.2, after Subnautica's suits): knockback, hurt tilt, the health dial drops. Running
out of oxygen kills both at once. Minecraft mobs (zombies turn into drowned underwater) fight you too.

**Try:** a sword in the safe shallows against peepers, a stalker or a sand shark; a bow; get bitten.
Creatures and Minecraft mobs fight each other too: summon a zombie near biters or a stalker
(`python tools/mc_cmd.py "/summon zombie ~3 ~ ~"`) and watch.
**Expected rough edges:** the health dial doesn't pulse at low health yet.
**Send back:** whether hits feel right (damage, knockback), anything you can't hit.

## Phase 4 — sound, a mix: please listen first

What it does: Subnautica keeps all of its own sound (ambience, music, creatures, vehicles).
Minecraft's own audio is silent; its gameplay sounds (your steps, hits, eating, blocks, mobs,
items, chests, doors, menu clicks) play through Subnautica's audio at the right place, with
Subnautica's volume sliders, pausing with the PDA, and muffled while the camera is underwater.
Minecraft's music, cave ambience and weather are dropped.

**Try:** break and place blocks on land and underwater, summon a zombie
(`python tools/mc_cmd.py "/summon zombie ~ ~ ~4"`) and listen from the surface, underwater and in
the lifepod; open a Minecraft chest; ride a minecart if you have rails.
**Expected rough edges:** the muffling is SubCraft's own low-pass (Subnautica muffles inside each of
its own sounds, there's no shared filter to borrow); no reverb in caves or the Aurora.
**Send back:** sounds too loud or quiet next to Subnautica's, missing sounds, the muffling amount.
`Sound.Bus` in the BepInEx config picks the Subnautica bus (harness command `fmod` lists them).

## Phase 4 — your character is Minecraft's: please try it first

What it does: Subnautica's diver is gone everywhere (vehicles too), Subnautica's swim/step camera
bob is gone, and the camera is Minecraft's: its FOV (narrower underwater, wider when sprinting),
its view bobbing, its hand, and **F5** cycles first person / behind / in front, with your Minecraft
character drawn by Subnautica (`docs/look/4-third-person-*.png`).

**Try:** walk on land and swim in first person (bobbing like Minecraft), sprint (FOV widens), F5
through all three views near rocks (the camera pulls in like Minecraft's), get in the Seamoth.
**Expected rough edges:** no hurt tilt yet (comes with combat); in a vehicle the camera is still
Subnautica's vehicle camera; the PDA still floats where the diver's hands were.
**Send back:** anything that doesn't feel like Minecraft's camera.

## Phase 4 (started) — time and HUD: please try it first

What it does: Subnautica's time of day drives Minecraft's (noon = 6000, midnight = 18000), and in
survival Subnautica's health dial shows your Minecraft health, the food dial your Minecraft hunger.
Minecraft's hearts, hunger, armour and air bars are hidden; the water dial is hidden (Minecraft has
no thirst). `UnifiedHud = false` in `BepInEx\config\dev.subcraft.host.cfg` brings Minecraft's bars back.

**Try:** a survival game; get hurt in Minecraft (`python tools/mc_cmd.py "/damage @p 6"`), eat, go
hungry. **Expected rough edge:** an empty dark ring where the water dial was.
**Send back:** whether the dials feel right, and whether you'd rather keep a water dial.

## Windows setup (2026-10-02)

Everything below was set up on the Windows PC; run commands from PowerShell in the repo root.

- Subnautica (Steam, `C:\Program Files (x86)\Steam\steamapps\common\Subnautica`) with BepInEx
  5.4.23.5 win_x64 unzipped into the game folder (`winhttp.dll` loads it, so no Steam launch
  options). `SubCraft.dll` is deployed to `BepInEx\plugins\SubCraft`; delete that folder to play
  plain Subnautica.
- Gradle must run on JDK 17 (`JAVA_HOME` = `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`);
  it downloads Java 21 itself for Minecraft. `tools\mc_dev.ps1` sets this for you.
- Windows twins of the shell tools: `tools\mc_dev.ps1 start|stop|status`,
  `tools\sn_dev.ps1 start|stop|status|log`, `tools\sn_restart.ps1 [scenario]`,
  `tools\check_layout.ps1 [-Write]` (needs g++ from winget `BrechtSanders.WinLibs.POSIX.UCRT`).
  The Python tools work as they are (`python tools\fake_host.py 60`).
- If PowerShell refuses the scripts ("running scripts is disabled"), call them as
  `powershell -ExecutionPolicy Bypass -File tools\mc_dev.ps1 start`.
- The shared folder is `%LOCALAPPDATA%\SubCraft`.
- In Git Bash, `export MSYS_NO_PATHCONV=1` before `python tools/mc_cmd.py "/time ..."`: otherwise
  Git Bash turns `/time` into a Windows path. PowerShell is unaffected.
- `sn_dev.ps1` keeps Subnautica's screen prefs in the registry
  (`HKCU\Software\Unknown Worlds\Subnautica`); the first `start` backs them up to
  `~\Documents\Development\Modding\SubCraft-save-backups\subnautica-prefs-original.json` and `stop`
  restores them. Unity only writes those values once the game has run and exited normally, so
  launch Subnautica normally once first.
- Saves live in `Subnautica\SNAppData\SavedGames` (Steam Cloud put your slot0000-0003 there; they
  were backed up to `~\Documents\Development\Modding\SubCraft-save-backups\win-*`). The dev slot
  on this PC is `slot0004` (created by the harness). Back the folder up before save-related work.
- **Seamoth, please re-try:** board it, fly around, look around, get out. The camera should stay on
  the seat and turn with the Seamoth, and on leaving you should be the Minecraft player at once.
- Verified on Windows: both games linked (after the clock fix), Phase 0a, movement, blocks, the
  Phase 3 hand, sound, combat. Not checked: performance with many Minecraft blocks on the RX 480.

---

## Phase 3 — mobs, items, particles and your hand drawn by Subnautica: please try it first

What it does: everything that moves in Minecraft (mobs, dropped items, chests and other block
entities, particles, your hand and held item) is drawn by Subnautica, lit by its sun, torches and
flashlight. Animated blocks (lava, fire, magma, sea lantern) animate.

**Try:** `python3 tools/mc_cmd.py "/summon zombie ~ ~ ~3"`, drop an item (Q), open and close a
chest, place a magma block, hold different items and an empty hand, at noon and at night with the
flashlight (`{"cmd":"equip","tech":"Flashlight","lights":true}`).

**Expected rough edges:** smoke particles are dark and don't fade; dropped items float up in
water (Minecraft); Create contraptions and other mods' special renderers aren't drawn yet (they
need the compat machine, see compat/README.md).

**Send back:** your hand/held item (size, position, brightness compared with Minecraft), anything
that flickers, missing mobs or items, frame-rate drops with many mobs.

## Phase 2 — Minecraft blocks drawn by Subnautica: please try it first

What it does: blocks you place in Minecraft appear in Subnautica, lit by Subnautica (sun,
caustics, fog, flashlight, Seamoth lights). Torches light the sand. Creatures and vehicles bump
into your builds.

**Steps** (as for Phase 1, then)

1. Next to the lifepod there's a test hut (cobblestone, torches) on the seabed. Look at it at
   different times of day (`python3 tools/sn_cmd.py '{"cmd":"time","value":0.0}'` = night).
2. Build and break things in survival or creative: the change shows in Subnautica within a frame
   or two. Try glass, leaves, a torch, glowstone, slabs and stairs.
3. Bump the Seamoth into a wall; watch fish swim around your build.

**Expected rough edges:** chests, signs and other block entities don't show yet (Phase 3).
Water, lava and fire don't animate. Torches can't sit in water (Minecraft rule), and they pop.

**Send back:** anything that looks wrong next to Subnautica's own rocks and props (too dark,
too bright, shiny, flickering), missing or misplaced blocks, frame-rate drops while building.

**Note:** when you play the dev game, tell Claude, so scripted runs don't fight your input.

## Fast flight (elytra) — fixed 2026-10-02, please re-try

Flying fast no longer freezes you in open water or air: Subnautica's voxel data tells Minecraft
early that the space ahead is empty. You still stop when you fly into real terrain (as in
Minecraft). **Send back:** any place where you still freeze in open space, with
`python3 tools/mc_cmd.py "subcraft tris"` taken there.

## Phase 1 — the world as blocks; walk and swim (done): please try it first

What it does: Minecraft's player moves through Subnautica's world on Minecraft physics. It
collides with Subnautica's exact collision surface. Mobs stand on "ghost terrain" blocks
voxelized from the same surface. The lifepod is dry, the air bar is Subnautica's oxygen, and the
diver is gone (you see Minecraft's hand and HUD). Keys act in one game only.

**Steps** (close other big apps first; both games together swap heavily on 8 GB)

```sh
cd ~/Development/Modding/Subnauticraft
tools/mc_dev.sh start
tools/sn_dev.sh start
python3 tools/sn_cmd.py --scenario tools/scenarios/load_dev_slot.jsonl
```

The dev game is Creative, and the Minecraft dev world starts in Creative too. For the real feel, run
`python3 tools/mc_cmd.py "/gamemode survival"`, and for oxygen
`python3 tools/sn_cmd.py '{"cmd":"gamemode","mode":"survival"}'` (not saved).

Then click into Subnautica and play:

1. **Lifepod:** you stand on its floor in air (not swimming). Leave through the hatch: the left
   click on the hatch is Subnautica's (any Subnautica prompt under the crosshair takes the click).
2. **Swim** toward the Kelp Forest, about 125 m north-east of the lifepod. Ctrl+W
   sprint-swims, Space rises, and Minecraft players sink when idle. Along rock faces and the seabed
   you should glide, never sink in, and never snag.
3. **Air:** underwater the bubbles drop with Subnautica's O2 dial (Subnautica Survival only). At 0
   Subnautica's own suffocation happens. Minecraft never damages you for drowning.
4. **Footsteps:** walk on the sand of the Safe Shallows, then on rock: Minecraft's sand, then stone,
   step sounds.
5. **Keys:** 1-9 and the wheel change only Minecraft's hotbar. Left click doesn't use Subnautica
   tools (the diver has none visible). **E** opens Minecraft's inventory: the mouse cursor appears,
   you can click and drag items, Esc closes it. Chat (**T**) takes typing. **Tab** opens the PDA,
   and Minecraft's GUI hides while it's open.
6. **Mob:** `python3 tools/mc_cmd.py "/summon cod ~ ~ ~"` (or a zombie): it stays out of the rock.
7. **Biome:** in the Kelp Forest,
   `python3 tools/mc_cmd.py "/execute at @p if biome ~ ~ ~ subcraft:kelp_forest"` says
   "Test passed" (the Minecraft window is hidden, so there's no F3 screen).
8. **Survival inventory (E):** your Minecraft character's model shows in it.

**Expected rough edges:**
- Minecraft's hearts and hunger still show above the hotbar (unified HUD dials: Phase 4).
- The hand is lit by Minecraft (darker at depth) until Unity draws it (Phase 3).
- Swimming nose-first into a rock face stops you, as in Minecraft.

**Send back:** how swimming and walking feel (snags, jitter, places you went through or got stuck),
whether clicking in Minecraft's inventory lands on the right slot at your window size, and for any
problem: `python3 tools/mc_cmd.py "subcraft tris"` taken right there, `tools/sn_dev.sh log 80`,
`guest-neoforge/run/logs/latest.log`.

**Unverified by Claude** (needs real mouse/keyboard or ears): cursor clicks in Minecraft screens,
typing in chat, Subnautica not reacting to number keys and clicks (the harness injects input past
Subnautica), how footsteps sound.

## Phase 0c look — approved by Sean (2026-10-01)

`docs/look/` keeps the screenshot set (noon/dusk/night+flashlight, near/mid/far, above surface).

## Decisions waiting for you

- **The water dial:** Minecraft has no thirst, so it's hidden, but its empty ring stays (it's part
  of the dial backplate). Keep it hidden, or show another Minecraft stat there (armour, which is
  hidden now too)?

(Licence: MIT, decided 2026-10-01.)

## Things Claude changed on your machine

On the Windows PC (2026-10-02): installed Subnautica (Steam), BepInEx 5.4.23.5 into the game folder,
LLVM (winget), ilspycmd (dotnet tool); created `%LOCALAPPDATA%\SubCraft` (the link file); added
the dev save `slot0004`; Subnautica's window settings are changed by `sn_dev.ps1 start` and put
back by `stop`. On the Mac, earlier:

- Your Subnautica saves were copied to `~/Development/Modding/SubCraft-save-backups/` before any
  plugin code ran; `slot0000`/`slot0001` have stayed byte-identical. SubCraft's dev game is
  `slot0002` — the harness may only ever save that slot.
- `SubCraft.dll` is installed in `Subnautica/BepInEx/plugins/SubCraft/`. Remove that folder to play
  plain Subnautica.
- Dev runs set Subnautica to a 960x540 window through its Unity prefs; `tools/sn_dev.sh stop`
  restores your 2940x1912 fullscreen (checked). If you ever start Subnautica and it's a small
  window, run `python3 tools/sn_prefs.py`.

---

## Phase 0a — Minecraft link without Subnautica

What it proves: a hidden Minecraft links to a stand-in host through shared memory, walks and jumps
on host-supplied collision, and ships its GUI as a transparent overlay.

**Steps**

```sh
cd ~/Development/Modding/Subnauticraft
tools/mc_dev.sh start            # dev client, 2 GB heap; waits until the mod has loaded
python3 tools/fake_host.py 60    # the stand-in host; the window hides itself once linked
tools/mc_dev.sh stop
```

**Expected**

- `fake_host.py` ends with six `[ok]` lines and exit code 0:
  linked, clocks agree, player released on the floor, W moved it forward (~+12 blocks),
  jumped onto the step (max y ~66.2), overlay on a transparent background.
- The Minecraft window disappears a moment after the fake host starts and stays hidden.
- `tools/out/overlay.png`: hotbar, hearts, hunger, crosshair, first-person hand; everything else
  transparent (an image viewer shows it on a checkerboard or white).

**Send back if it fails:** the whole `fake_host.py` output, `guest-neoforge/run/logs/latest.log`,
and `tools/out/overlay.png`.

**Unverified:** App Nap when Minecraft is launched by Prism instead of Gradle.

---

## Phase 0b — Subnautica driven by Minecraft

What it proves: with both games running, pressing W in Subnautica moves you through Minecraft's
physics, Minecraft's hotbar/hearts/air show on top, and Subnautica takes control back if Minecraft
dies.

**Steps** (close other big apps first; 8 GB is tight)

```sh
cd ~/Development/Modding/Subnauticraft
tools/mc_dev.sh start                     # Minecraft first (hides itself once linked)
tools/sn_dev.sh start                     # Subnautica through Steam, 960x540 window
python3 tools/sn_cmd.py --scenario tools/scenarios/load_dev_slot.jsonl
```

Then click into the Subnautica window and play:

1. You're in the dev game (Creative). Swim out of the lifepod.
2. Hold **W**: you move forward, a bit slower than Subnautica swimming, and you slowly **sink**
   (Minecraft has no seabed here yet, and Minecraft players sink in water). Mouse look is
   Subnautica's. **Space** swims up (Minecraft's swim-up).
3. Bottom of the screen: Minecraft's hearts, air bubbles, hunger and hotbar over Subnautica's
   (Subnautica's quickslots are still underneath — known, Phase 1).
4. Run `tools/mc_dev.sh stop` in a terminal: within ~2 s Subnautica's own swimming works again.

**Expected / known rough edges:** number keys change both hotbars; left click also uses
Subnautica's tool; E opens Minecraft's inventory but you can't click in it yet; Tab still opens the
PDA (intended). Frame rate may stutter while both games run.

**Afterwards:** `tools/sn_dev.sh stop` (also restores your screen settings), `tools/mc_dev.sh stop`.

**Send back:** what W/Space felt like (speed, sinking, any jitter of the camera), whether the
Minecraft HUD looked crisp at your window size, and if anything failed:
`tools/sn_dev.sh log 80`, `guest-neoforge/run/logs/latest.log`.

**Verified by Sean (2026-10-01):** real keyboard and mouse work.

Note since Phase 1: with exact collision you no longer sink forever — you land on the seabed.
