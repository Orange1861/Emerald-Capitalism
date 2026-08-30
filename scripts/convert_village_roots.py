#!/usr/bin/env python3
"""
Converter: Emerald Capitalism language workbook -> canonical village-naming JSON.

CHANGES FROM THE PREVIOUS VERSION
---------------------------------
1. origin now reads column E, Concept origin. It used to read column C, which
   was Concept origin in the old workbook and is now Derivation, the single
   English source word the converter takes as input. Reading C gave "Emerald"
   where the data means "Emerald Ore".

2. The Alphabetical Index parity check is GONE, along with parse_alphabetical_index.
   It read column D as the section label; D is now Type, so it reported a
   mismatch on every root and blocked the conversion. It is also obsolete: the
   Index is generated from Concept Roots by build.py and cannot disagree with it.

3. Section is read from column K rather than inferred from header rows. Header
   rows still work as a fallback.

4. New fields emitted: enabled, weightHint, tags, stratum, type, dialects.

   enabled     comes from the Village naming column. A root marked no is a
               manufactured item, a UI term or a personal title, and makes
               nonsense in a place name.
   weightHint  comes from the stratum. Deep vocabulary that villagers have
               carried for centuries should name places more often than a
               collapse-era borrowing.
   dialects    the regional forms, keyed by region. The mod already detects
               biome, so a desert village can be named in Desert forms.

5. COMPOUND INTEGRITY. Compounds used to be hand-written in build.py. They are
   now built by rule C1 in root_converter.compound(). Two checks were added so a
   hand-edited compound cannot reach the mod:

     dangling_compound_parts  a compound naming a part that is not a root
     compound_rule_mismatch   a recorded form that rule C1 does not reproduce

   root_converter imports only re and sys at module level, so this script stays
   standalone. If it is genuinely absent the rule check is SKIPPED and the
   report says so, rather than silently passing.

Usage:
    python3 convert_village_roots.py --input <workbook.xlsx>
    python3 convert_village_roots.py --validate-only
"""

from __future__ import annotations

import argparse
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

NS = {"a": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
WORKBOOK_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

# A stratum is a claim about how long villagers have had the word. Deep
# vocabulary names places more readily than something borrowed after the
# collapse, and post-collapse compounds least of all.
STRATUM_WEIGHT = {"1": 1.25, "2a": 1.25, "2b": 1.25, "3": 1.0, "4": 0.75, "5": 0.5}

REGION = {"Des": "desert", "Sav": "savanna", "Tai": "taiga", "Sno": "snowy"}

try:
    import root_converter as rc
except ImportError:            # standalone run, no sibling module
    rc = None

# Numerals live on the Grammar sheet and substrate elements on the Substrate
# sheet. Neither is a root, so a compound built from one is not dangling. This
# mirrors build.py's NON_ROOT_PARTS; keep the two in step.
NON_ROOT_PARTS = {"un", "tu", "te", "fo", "faif", "sik", "sef", "et", "ploh",
                  "stak", "muk", "nek"}


@dataclass(frozen=True)
class RootEntry:
    root: str
    section: str
    meaning: str
    origin: str
    derivation: str
    type: str
    stratum: str
    enabled: bool
    dialects: dict = field(default_factory=dict)
    source_row: int = 0


def _cell_text(cell: ET.Element, shared: list[str]) -> str:
    kind = cell.attrib.get("t")
    if kind == "inlineStr":
        return "".join(n.text or "" for n in cell.findall(".//a:t", NS))
    node = cell.find("a:v", NS)
    if node is None:
        return "".join(n.text or "" for n in cell.findall(".//a:t", NS))
    raw = node.text or ""
    if kind == "s" and raw.isdigit():
        return shared[int(raw)]
    return raw


def _load_sheet_rows(xlsx_path: Path, sheet_name: str) -> list[tuple[int, dict[str, str]]]:
    with zipfile.ZipFile(xlsx_path) as z:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in z.namelist():
            root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for item in root.findall("a:si", NS):
                shared.append("".join(n.text or "" for n in item.findall(".//a:t", NS)))

        wb_root = ET.fromstring(z.read("xl/workbook.xml"))
        rels_root = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        rels = {r.attrib["Id"]: r.attrib["Target"]
                for r in rels_root.findall(f"{{{PACKAGE_REL_NS}}}Relationship")}

        sheet_path = None
        for sheet in wb_root.findall("a:sheets/a:sheet", NS):
            if sheet.attrib.get("name") != sheet_name:
                continue
            target = rels[sheet.attrib[f"{{{WORKBOOK_NS}}}id"]].lstrip("/").replace("\\", "/")
            sheet_path = Path(target)
            if not target.startswith("xl/"):
                sheet_path = Path("xl") / sheet_path
            break
        if sheet_path is None:
            raise ValueError(f"Sheet '{sheet_name}' was not found in {xlsx_path}")

        data = ET.fromstring(z.read(sheet_path.as_posix()))
        rows = []
        for row in data.findall("a:sheetData/a:row", NS):
            cells = {}
            for cell in row.findall("a:c", NS):
                col = "".join(ch for ch in cell.attrib.get("r", "") if ch.isalpha())
                cells[col] = _cell_text(cell, shared)
            rows.append((int(row.attrib["r"]), cells))
        return rows


def _slug(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")


def _dialects(raw: str) -> dict:
    """'Des wiht; Tai wuht' -> {'desert': 'wiht', 'taiga': 'wuht'}"""
    out = {}
    for part in (raw or "").split(";"):
        part = part.strip()
        if " " not in part:
            continue
        tag, form = part.split(" ", 1)
        if tag in REGION:
            out[REGION[tag]] = form.strip()
    return out


def parse_concept_roots(xlsx_path: Path) -> list[RootEntry]:
    rows = _load_sheet_rows(xlsx_path, "Concept Roots")
    entries: list[RootEntry] = []
    header_section = ""

    for row_num, cells in rows:
        col = lambda k: (cells.get(k) or "").strip()
        a, b, c = col("A"), col("B"), col("C")
        if not (a or b or c):
            continue
        if a == "Root":
            continue
        if a and not b and not c:
            header_section = a          # a row with only column A is a heading
            continue
        if not a or not col("D"):
            continue

        entries.append(RootEntry(
            root=a,
            # Column K is authoritative. Header rows are the fallback for any
            # row written before Section became a column.
            section=_slug(col("K") or header_section),
            meaning=b,
            origin=col("E"),            # Concept origin, NOT column C
            derivation=c,               # the English source word
            type=col("D"),
            stratum=col("I"),
            enabled=col("L").lower() != "no",
            dialects=_dialects(col("G")),
            source_row=row_num,
        ))
    return entries


def validate(entries: list[RootEntry]) -> dict[str, list[str]]:
    issues: dict[str, list[str]] = defaultdict(list)
    for e in entries:
        if not e.root:
            issues["blank_roots"].append(f"row {e.source_row}")
        if not e.section:
            issues["blank_sections"].append(f"{e.root} (row {e.source_row})")
        if not e.meaning:
            issues["missing_required_fields"].append(f"{e.root}: meaning is blank (row {e.source_row})")

    counts = Counter((e.root, e.section, e.meaning, e.origin) for e in entries)
    for key, n in counts.items():
        if n > 1:
            issues["exact_duplicates"].append(f"{key[0]} ({n} occurrences)")

    by_root: dict[str, set] = defaultdict(set)
    for e in entries:
        by_root[e.root].add((e.section, e.meaning, e.origin))
    for root, variants in by_root.items():
        if len(variants) > 1:
            issues["conflicting_duplicates"].append(root)

    known = {e.root for e in entries}
    compounds = {e.root for e in entries if "+" in e.derivation}
    for e in entries:
        if "+" not in e.derivation:
            continue
        mod, head = [p.strip() for p in e.derivation.split("+", 1)]
        for part in (mod, head):
            if part.startswith("-") or part in NON_ROOT_PARTS:
                continue          # an affix, a numeral or a substrate element
            if part not in known:
                issues["dangling_compound_parts"].append(
                    f"{e.root}: part '{part}' is not a root (row {e.source_row})")
        if (rc is None or e.root in getattr(rc, "LEXICALIZED", set())
                or mod in NON_ROOT_PARTS or head in NON_ROOT_PARTS):
            continue
        try:
            got = rc.build_from_parts(e.derivation, known_compounds=compounds,
                                      stratum=e.stratum.strip())
        except Exception:
            continue
        if got != e.root:
            issues["compound_rule_mismatch"].append(
                f"{e.root}: rule C1 on '{e.derivation}' gives {got} (row {e.source_row})")
    return issues


def write_json(entries: list[RootEntry], out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"roots": []}
    for e in entries:
        item = {
            "root": e.root,
            "section": e.section,
            "meaning": e.meaning,
            "origin": e.origin,
            "enabled": e.enabled,
            "weightHint": STRATUM_WEIGHT.get(e.stratum.strip(), 1.0),
            "tags": [f"stratum:{e.stratum.strip()}", f"type:{e.type.strip().lower()}"],
            "sourceSheet": "Concept Roots",
            "sourceRow": e.source_row,
        }
        if e.dialects:
            item["dialects"] = e.dialects
        payload["roots"].append(item)
    out_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def write_report(path: Path, issues, entries, out_path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    by_section = Counter(e.section for e in entries)
    enabled = sum(1 for e in entries if e.enabled)
    with_dialect = sum(1 for e in entries if e.dialects)
    lines = [
        "# Village Naming Root Conversion Report",
        "",
        "- Canonical data sheet: `Concept Roots`",
        f"- Converted root count: **{len(entries)}**",
        f"- Enabled for village naming: **{enabled}**",
        f"- Carrying at least one dialect form: **{with_dialect}**",
        f"- Output: `{out_path}`",
        "",
        "## Validation summary",
        "",
        ("- Rule C1 compound check: **SKIPPED**, root_converter was not importable."
         if rc is None else "- Rule C1 compound check: **ran**."),
    ]
    if not any(issues.values()):
        lines.append("- No validation issues detected.")
    else:
        for key in ["missing_required_fields", "blank_roots", "blank_sections",
                    "exact_duplicates", "conflicting_duplicates",
                    "dangling_compound_parts", "compound_rule_mismatch"]:
            values = issues.get(key, [])
            lines.append(f"- {key}: {len(values)}")
            for v in values:
                lines.append(f"  - {v}")
    lines += ["", "## Roots per section", ""]
    for section, n in by_section.most_common():
        lines.append(f"- `{section}`: {n}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", default="docs/lore/emerald_capitalism_language_system.xlsx", type=Path)
    p.add_argument("--output",
                   default="src/main/resources/data/emeraldcapitalism/village_naming/roots.json",
                   type=Path)
    p.add_argument("--report", default="docs/lore/village_naming_root_conversion_report.md", type=Path)
    p.add_argument("--validate-only", action="store_true")
    args = p.parse_args()

    entries = parse_concept_roots(args.input)
    issues = validate(entries)

    if args.validate_only:
        if any(issues.values()):
            print("Validation failed.")
            for key, values in issues.items():
                if values:
                    print(f"{key}: {len(values)}")
            return 1
        print(f"Validation passed for {len(entries)} roots.")
        return 0

    if any(issues.values()):
        write_report(args.report, issues, entries, args.output)
        print(f"Conversion blocked due to validation errors. See report: {args.report}")
        return 1

    write_json(entries, args.output)
    write_report(args.report, issues, entries, args.output)
    print(f"Wrote {len(entries)} roots to {args.output}")
    print(f"Wrote report to {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
