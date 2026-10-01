# SubCraft — tests for Sean

Things only you can judge (feel, taste) or decide. Everything here was already run by Claude
unless marked **unverified**.

## Decisions waiting for you

1. **Project licence.** The mod metadata says MIT (matching SkyCraft) but there is no `LICENSE`
   file. Keep MIT, or something else?

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
