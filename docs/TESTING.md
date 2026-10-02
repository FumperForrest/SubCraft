# SubCraft — tests for Sean

Things only you can judge (feel, taste) or decide. Everything here was already run by Claude
unless marked **unverified**.

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

None right now. (Licence: MIT, decided 2026-10-01.)

## Things Claude changed on your machine

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
