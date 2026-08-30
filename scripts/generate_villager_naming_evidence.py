#!/usr/bin/env python3
"""Generate a deterministic Brief 3 naming acceptance dump.

This is an offline evidence generator. It consumes the exported naming system
and mirrors the runtime's junction, regional, drift, age, and profession
pipeline; it does not author or add roots.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SYSTEM = ROOT / "src" / "main" / "resources" / "data" \
    / "emeraldcapitalism" / "village_naming" / "villager_names.json"
DEFAULT_OUTPUT = ROOT / "docs" / "lore" / "villager_naming_acceptance_dump.md"
STOPS = "bdgkpt"
VOWELS = "aeiou"


def mix(value: int) -> int:
    value &= (1 << 64) - 1
    value ^= value >> 33
    value = (value * 0xff51afd7ed558ccd) & ((1 << 64) - 1)
    value ^= value >> 33
    value = (value * 0xc4ceb9fe1a85ec53) & ((1 << 64) - 1)
    value ^= value >> 33
    return value & ((1 << 64) - 1)


def signed(value: int) -> int:
    return value - (1 << 64) if value & (1 << 63) else value


def unit_hash(seed: int, x: int, z: int) -> float:
    value = seed ^ ((x * 0x632BE59BD9B4E019) & ((1 << 64) - 1)) \
        ^ ((z * 0x8CB92BA72F3D8DD7) & ((1 << 64) - 1))
    return (mix(value) >> 11) / float(1 << 53)


def smooth(value: float) -> float:
    return value * value * (3.0 - 2.0 * value)


def sample_noise(seed: int, x: int, z: int, feature_size: int, index: int) -> float:
    grid_x = x / feature_size
    grid_z = z / feature_size
    cell_x = math.floor(grid_x)
    cell_z = math.floor(grid_z)
    local_x = smooth(grid_x - cell_x)
    local_z = smooth(grid_z - cell_z)
    salt = signed((0x9E3779B97F4A7C15 * (index + 1)) & ((1 << 64) - 1))
    a = unit_hash(seed ^ salt, cell_x, cell_z)
    b = unit_hash(seed ^ salt, cell_x + 1, cell_z)
    c = unit_hash(seed ^ salt, cell_x, cell_z + 1)
    d = unit_hash(seed ^ salt, cell_x + 1, cell_z + 1)
    top = a + (b - a) * local_x
    bottom = c + (d - c) * local_x
    return top + (bottom - top) * local_z


def capitalize(value: str) -> str:
    return value[:1].upper() + value[1:]


def simplify_geminates(value: str) -> str:
    result = []
    for char in value:
        if result and result[-1] == char and char not in VOWELS:
            continue
        result.append(char)
    return "".join(result)


def junction(first: str, second: str) -> str:
    if first[-1] in STOPS and second[0] not in VOWELS:
        if first[-1] == second[0]:
            return first + second
        return first[:-1] + second
    return first + second


def affix(stem: str, suffix: str) -> str:
    """Rule A1, matching root_converter.affix()."""
    if suffix in {"a", "i", "o", "in", "ek", "ur"} and stem[-1:] in VOWELS:
        return stem + "n" + suffix
    return stem + suffix


def regional_form(value: str, biome: str) -> str:
    if biome == "desert":
        return value.replace("e", "i")
    if biome == "savanna":
        return simplify_geminates(value)
    if biome == "taiga":
        return value.replace("o", "u")
    if biome == "snowy" and value[-1] in STOPS:
        return value[:-1]
    return value


def replace_medial(value: str, target: str, replacement: str) -> str:
    chars = list(value)
    for index in range(1, len(chars) - 1):
        if chars[index] == target and chars[index - 1] != target \
                and chars[index + 1] != target:
            chars[index] = replacement
    return "".join(chars)


def drift_operation(value: str, operation: str) -> str:
    if not value:
        return value
    if operation == "drop_final_nasal" and value[-1] in "mn":
        return value[:-1]
    if operation == "lenite_medial_s_to_h":
        return replace_medial(value, "s", "h")
    if operation == "lower_i_to_e":
        return value.replace("i", "e")
    if operation == "lower_u_to_o":
        return value.replace("u", "o")
    if operation == "simplify_geminates":
        return simplify_geminates(value)
    if operation == "drop_final_liquid" and value[-1] in "lr":
        return value[:-1]
    if operation == "change_final_stop_to_nasal":
        replacement = {"k": "ng", "g": "ng", "t": "n", "d": "n",
                       "p": "m", "b": "m"}.get(value[-1])
        return value[:-1] + replacement if replacement else value
    if operation == "lenite_medial_k_to_h":
        return replace_medial(value, "k", "h")
    if operation == "voice_initial_stop":
        return {"p": "b", "t": "d", "k": "g"}.get(value[0], value[0]) + value[1:]
    if operation == "devoice_initial_stop":
        return {"b": "p", "d": "t", "g": "k"}.get(value[0], value[0]) + value[1:]
    if operation == "lenite_medial_t_to_s":
        return replace_medial(value, "t", "s")
    if operation == "lenite_medial_m_to_w":
        return replace_medial(value, "m", "w")
    return value


def apply_drift(value: str, rules: list[dict], selected: list[str]) -> str:
    for rule in rules:
        if rule["id"] in selected:
            value = drift_operation(value, rule["operation"])
    return value


def choose_drift(system: dict, seed: int, x: int, z: int) -> list[str]:
    rules = system["drift_rules"]
    assignment = system["drift_assignment"]
    feature_sizes = assignment["feature_sizes"]
    values = [sample_noise(seed, x, z, feature_sizes[i], i)
              for i in range(len(rules))]
    selected = [value >= assignment["threshold"] for value in values]
    for group in assignment["inverse_groups"]:
        indexes = [i for i, rule in enumerate(rules) if rule["id"] in group]
        active = [i for i in indexes if selected[i]]
        if len(active) > 1:
            winner = max(active, key=lambda i: values[i])
            for index in active:
                selected[index] = index == winner
    ranked = [i for i, active in enumerate(selected) if active]
    if not ranked:
        ranked = [max(range(len(values)), key=values.__getitem__)]
    ranked.sort(key=lambda i: values[i], reverse=True)
    ranked = ranked[:assignment["max_rules"]]
    ranked.sort()
    return [rules[i]["id"] for i in ranked]


def make_rows(system: dict) -> list[dict[str, str]]:
    villages = [
        ("Aster Ford", "plains", 128, 192),
        ("Copper Reach", "savanna", 3840, -512),
        ("Glasswind", "desert", -2560, 1792),
        ("Pine Ledger", "taiga", 7168, 640),
        ("White Step", "snowy", -6144, -2048),
        ("Juniper Fold", "plains", 1536, 4608),
        ("Sunken Toll", "savanna", -4608, -5120),
    ]
    professions = ["fletcher", "farmer", "cleric", "mason", "librarian", "armorer"]
    ages = ["adult", "adult", "child", "adult", "adult", "child"]
    world_seed = 0x4D4F445F42524945
    rows = []
    for village_index, (village, biome, x, z) in enumerate(villages):
        pool = system["pools"][biome]
        drift_rules = choose_drift(system, world_seed, x, z)
        for villager_index in range(6):
            pair_index = (mix(world_seed ^ (village_index * 0x10001)
                               ^ (villager_index * 0x9E37))
                          % (len(pool["first"]) * len(pool["second"])))
            first = pool["first"][pair_index // len(pool["second"])]
            second = pool["second"][pair_index % len(pool["second"])]
            base = junction(first, second)
            personal_stem = regional_form(base, biome)
            age = ages[villager_index]
            suffix = system["age_suffixes"][age]
            personal = apply_drift(
                affix(personal_stem, suffix), system["drift_rules"], drift_rules)
            profession_key = professions[villager_index]
            byname = system["professions"][profession_key]["bynames"].get(
                biome, system["professions"][profession_key]["bynames"].get("plains", ""))
            byname = apply_drift(byname, system["drift_rules"], drift_rules)
            rendered = capitalize(personal + suffix)
            if byname:
                rendered += " " + capitalize(byname)
            origin_particle = system.get("origin_particle", {})
            if origin_particle.get("enabled") and origin_particle.get("prefix"):
                rendered += " " + origin_particle["prefix"] + " " + village
            rows.append({
                "villager": rendered,
                "village": village,
                "biome": biome,
                "drift": ", ".join(drift_rules),
                "base": capitalize(base),
                "profession": system["professions"][profession_key]["label"],
                "age": age,
            })
    return rows


def render(system: dict) -> str:
    rows = make_rows(system)
    lines = [
        "# Brief 3 village-naming acceptance dump",
        "",
        "This deterministic offline dump exercises the same exported naming system used by the runtime. It uses only `villager_names.json`; it does not add or synthesize roots. Each row resolves slot 1 from the selected biome pool, applies the regional form, builds slot 2 with rule A1 and then applies village drift in ascending rule order, derives slot 3 from the regional profession byname, and appends slot 4 from the named origin village.",
        "",
        f"Rows: {len(rows)} villagers across {len({row['village'] for row in rows})} villages and {len({row['biome'] for row in rows})} biomes.",
        "",
        "| Villager name | Village | Biome | Drift rules | Slot 1 base form | Profession | Age |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        lines.append("| {villager} | {village} | {biome} | {drift} | {base} | {profession} | {age} |".format(**row))
    lines.extend(["", "Acceptance notes:", "", "- Exactly one or two drift IDs are selected per village; D9 and D10 are mutually exclusive, and selected rules are applied in ascending rule index.", "- Slot 1 is a substrate pair and is not replaced during ordinary refreshes; the runtime reserves pairs until the village pool is exhausted.", "- Slot 2 uses A1 hiatus repair: vowel-final stems take inserted n before -a, -i, -o, -in, -ek or -ur.", "- Slot 4 renders as `Se [origin village]` after the persisted origin village has a determined name. It is not passed through drift.", "- Special first names are manually maintained in the converter's `SPECIAL_FIRST_NAMES` section, selected at 1 in 10,000 assignments, and replace only slot 1.", "- The elder suffix is exported, but Minecraft 1.21.1 exposes only child/adult villager age states; no elder transition was invented, so that gap is reported for a later age-state seam.", ""])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--system", type=Path, default=DEFAULT_SYSTEM)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    system = json.loads(args.system.read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(system), encoding="utf-8")
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
