"""Restore Subnautica's screen prefs from Sean's exported original (sn_dev.sh stop calls this)."""
import os
import plistlib
import subprocess

DOMAIN = "unity.Unknown Worlds.Subnautica"
BACKUP = os.path.expanduser("~/Development/Modding/SubCraft-save-backups/subnautica-prefs-original.plist")
KEYS = ["Screenmanager Resolution Width", "Screenmanager Resolution Height", "Screenmanager Fullscreen mode",
        "Screenmanager Resolution Use Native"]

with open(BACKUP, "rb") as f:
    original = plistlib.load(f)
for k in KEYS:
    if k in original:
        subprocess.run(["defaults", "write", DOMAIN, k, "-int", str(int(original[k]))], check=True)
        now = subprocess.run(["defaults", "read", DOMAIN, k], capture_output=True, text=True).stdout.strip()
        print(f"{k} = {now} (original {original[k]})")
        assert now == str(int(original[k])), k
