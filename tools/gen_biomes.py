#!/usr/bin/env python3
"""Writes SubCraft's Minecraft biomes (one per Subnautica region) and their tags into the guest's
data pack. Run after changing the table; the output is committed. Keep the names in step with
guest-neoforge/.../world/SubBiomes.java (the Subnautica name -> biome mapping)."""
import json, os

ROOT = os.path.join(os.path.dirname(__file__), "..", "guest-neoforge", "src", "main", "resources", "data")

# name: (water_color, water_fog_color, deep, spawns)  spawns: "warm", "cold", "cave", "none"
BIOMES = {
    "ocean":              (0x3F76E4, 0x050533, False, "cold"),
    "safe_shallows":      (0x43D5EE, 0x0A3A4A, False, "warm"),
    "kelp_forest":        (0x3FA49A, 0x0B2E26, False, "cold"),
    "grassy_plateaus":    (0x3D9CC4, 0x0A2C3C, False, "warm"),
    "mushroom_forest":    (0x4A7FC0, 0x0C2440, False, "cold"),
    "jellyshroom_caves":  (0x5B4FB0, 0x120A30, False, "cave"),
    "sparse_reef":        (0x3A88B8, 0x081E30, False, "warm"),
    "grand_reef":         (0x2F5FA8, 0x040C24, True, "cold"),
    "blood_kelp":         (0x3B4E7A, 0x05070F, True, "cave"),
    "mountains":          (0x3D7FB0, 0x071A2C, True, "cold"),
    "dunes":              (0x4C86A8, 0x0A1C2A, True, "cold"),
    "crash_zone":         (0x4D8FB5, 0x0B2232, True, "none"),
    "aurora":             (0x5F8DA8, 0x101C24, False, "none"),
    "underwater_islands": (0x3C8CC0, 0x08203A, False, "warm"),
    "bulb_zone":          (0x4A6FC8, 0x0A1238, False, "cold"),
    "sea_treader_path":   (0x4F86A0, 0x0B1A22, True, "cold"),
    "lost_river":         (0x3FB89A, 0x041A12, True, "cave"),
    "lava_zone":          (0xB0583A, 0x200804, True, "none"),
    "floating_island":    (0x43C5E8, 0x0A3040, False, "warm"),
    "crater_edge":        (0x22357A, 0x01030A, True, "none"),
    "precursor":          (0x46B87A, 0x082014, True, "none"),
}

SPAWNS = {
    "warm": {"water_ambient": [{"type": "minecraft:tropical_fish", "weight": 25, "minCount": 8, "maxCount": 8},
                               {"type": "minecraft:pufferfish", "weight": 15, "minCount": 1, "maxCount": 3}],
             "water_creature": [{"type": "minecraft:squid", "weight": 10, "minCount": 1, "maxCount": 4}]},
    "cold": {"water_ambient": [{"type": "minecraft:cod", "weight": 15, "minCount": 3, "maxCount": 6},
                               {"type": "minecraft:salmon", "weight": 15, "minCount": 1, "maxCount": 5}],
             "water_creature": [{"type": "minecraft:squid", "weight": 3, "minCount": 1, "maxCount": 4}]},
    "cave": {"underground_water_creature": [{"type": "minecraft:glow_squid", "weight": 10, "minCount": 4, "maxCount": 6}],
             "water_ambient": [{"type": "minecraft:cod", "weight": 5, "minCount": 1, "maxCount": 3}]},
    "none": {},
}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


for name, (water, fog, deep, spawns) in BIOMES.items():
    write(os.path.join(ROOT, "subcraft", "worldgen", "biome", name + ".json"), {
        "has_precipitation": False,
        "temperature": 0.8 if spawns == "warm" else 0.5,
        "downfall": 0.5,
        "effects": {"sky_color": 0x78A7FF, "fog_color": 0xC0D8FF, "water_color": water, "water_fog_color": fog},
        "spawners": SPAWNS[spawns],
        "spawn_costs": {},
        "carvers": {},
        "features": [],
    })

ids = ["subcraft:" + n for n in BIOMES]
deep = ["subcraft:" + n for n, v in BIOMES.items() if v[2]]
for ns, tag, values in (("minecraft", "is_ocean", ids), ("minecraft", "is_deep_ocean", deep), ("c", "is_ocean", ids),
                        ("c", "is_aquatic", ids)):
    write(os.path.join(ROOT, ns, "tags", "worldgen", "biome", tag + ".json"), {"replace": False, "values": values})
print("wrote", len(BIOMES), "biomes")
