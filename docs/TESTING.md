# SubCraft — tests for Sean

Things only you can judge (feel, taste) or decide. Everything here was already run by Claude
unless marked **unverified**.

## Review the look (Phase 0c gate) — please look first

Open `docs/look/`. A small Minecraft hut (cobblestone, glass, oak leaves, grass, torch, glowstone,
chest) captured from Minecraft and drawn by Subnautica with its own shader, on the seabed near the
lifepod: `0c-near-noon`, `0c-mid-noon`, `0c-near-dusk`, `0c-mid-dusk`,
`0c-near-night-flashlight`, `0c-mid-night-flashlight`, `0c-far-noon-fog`,
`0c-above-surface-noon` (`0c-before-fixes` is the first attempt, for contrast).

**Does it look like it belongs?** Things to judge: brightness of the blocks next to the sand and
rocks; whether the far shot fades into the water like the rocks do; torch/glowstone glow strength
and the warm light they throw; leaves and grass green. Known: the diver's hands/tools are still
Subnautica's (they get replaced by Minecraft's hand later); no shadows because your quality preset
has them off.

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
