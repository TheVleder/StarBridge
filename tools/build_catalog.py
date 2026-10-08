#!/usr/bin/env python3
"""Builds core/src/main/resources/catalog.tsv from OpenNGC (CC-BY-SA-4.0, github.com/mattiaverga/OpenNGC).

Keeps:
- every Messier and Caldwell object;
- NGC/IC/addendum objects brighter than MAG_LIMIT (V, or B when there is no V): a 130 mm
  reflector under a dark sky shows galaxies to about there;
- objects without a catalogued magnitude that have a common name, or are nebulae larger than
  SIZE_LIMIT arcmin (the Veil, the Rosette, the Horsehead... have no magnitude in OpenNGC).
NGC/IC entries that are just stars are left out unless they are Messier/Caldwell.

Ids are written the usual way: "M31", "NGC 869", "IC 342", "C 14" stays the NGC id with
"C14" as an alias, addendum objects keep their name ("B 33", "Mel 111", "HCG 92").

Usage: python build_catalog.py NGC.csv addendum.csv > core/src/main/resources/catalog.tsv
"""
import csv, re, sys

MAG_LIMIT = 12.0
SIZE_LIMIT = 10.0
TYPES = {"*": "Star", "**": "Double star", "*Ass": "Asterism", "OCl": "Open cluster", "GCl": "Globular cluster",
         "Cl+N": "Cluster + nebula", "G": "Galaxy", "GPair": "Galaxy pair", "GTrpl": "Galaxy triplet", "GGroup": "Galaxy group",
         "PN": "Planetary nebula", "HII": "Emission nebula", "DrkN": "Dark nebula", "EmN": "Emission nebula",
         "Neb": "Nebula", "RfN": "Reflection nebula", "SNR": "Supernova remnant", "Nova": "Nova", "NonEx": None, "Dup": None, "Other": "Other"}
NEBULAE = {"HII", "EmN", "Neb", "RfN", "SNR", "Cl+N", "DrkN", "PN"}
NOT_DEEP_SKY = {"*", "**", "Nova", "Other"}

def hms(s):
    h, m, sec = s.split(":"); return (int(h) + int(m) / 60 + float(sec) / 3600)
def dms(s):
    sign = -1 if s.startswith("-") else 1
    d, m, sec = s.lstrip("+-").split(":"); return sign * (int(d) + int(m) / 60 + float(sec) / 3600)
def mag(r):
    for k in ("V-Mag", "B-Mag"):
        if r.get(k): return float(r[k])
    return None
def size(r):
    try: return float(r.get("MajAx") or 0)
    except ValueError: return 0.0
GREEK = {"alf": "α", "bet": "β", "gam": "γ", "del": "δ", "eps": "ε", "zet": "ζ", "eta": "η", "tet": "θ", "iot": "ι",
         "kap": "κ", "lam": "λ", "mu": "μ", "nu": "ν", "xi": "ξ", "omi": "ο", "pi": "π", "rho": "ρ", "sig": "σ",
         "tau": "τ", "ups": "υ", "phi": "φ", "chi": "χ", "psi": "ψ", "ome": "ω"}
def common(r, ident):
    """First common name, tidied: "the Witch Head Nebula" -> "Witch Head Nebula", "gam Cyg" -> "γ Cyg"."""
    n = (r.get("Common names") or "").split(",")[0].strip()
    n = re.sub(r"^the ", "", n)
    n = re.sub(r"^([a-z]{2,3}) (?=[A-Z][a-z]{2}\b)", lambda m: GREEK.get(m.group(1), m.group(1)) + " ", n)
    return "" if n.replace(" ", "").lower() == ident.replace(" ", "").lower() else n
def pretty(name):
    """NGC0869 -> NGC 869, IC0342 -> IC 342, B033 -> B 33, Mel111 -> Mel 111, HCG092 -> HCG 92."""
    m = re.fullmatch(r"([A-Za-z]+)0*(\d+)([A-Za-z_]*)", name)
    return f"{m.group(1)} {m.group(2)}{m.group(3)}" if m else name

sys.stdout.reconfigure(encoding="utf-8", newline="\n")  # Greek letters; LF also on Windows
rows = []
for path in sys.argv[1:]:
    with open(path, encoding="utf8") as f:
        rows += list(csv.DictReader(f, delimiter=";"))

print("# id\tname\ttype\tra_hours\tdec_deg\tmag\tconst\taliases\tSource: OpenNGC (CC-BY-SA-4.0)")
seen = set()
out = []
for r in rows:
    t = TYPES.get(r["Type"], r["Type"])
    if t is None or not r["RA"]: continue
    m = mag(r)
    messier = r.get("M", "").strip()
    cw = re.search(r"\bC (\d{3})\b", r.get("Identifiers") or "")
    caldwell = int(cw.group(1)) if cw else None
    if caldwell is None and re.fullmatch(r"C\d{3}", r["Name"]):  # addendum: Caldwell objects outside NGC/IC
        caldwell = int(r["Name"][1:])
    name = common(r, f"M{int(messier)}" if messier else r["Name"])
    if not (messier or caldwell):
        if r["Type"] in NOT_DEEP_SKY: continue  # NGC/IC entries that are stars
        bright = m is not None and m <= MAG_LIMIT
        notable = m is None and (name or (r["Type"] in NEBULAE and size(r) >= SIZE_LIMIT))
        if not (bright or notable): continue
    if messier:
        ident = f"M{int(messier)}"
    elif re.fullmatch(r"C\d{3}", r["Name"]):
        ident = f"C {caldwell}"
    else:
        ident = pretty(r["Name"])
    if ident in seen: continue
    seen.add(ident)
    alt = pretty(r["Name"]) if messier and not re.fullmatch(r"M\d+", r["Name"]) else ""
    label = " / ".join(x for x in (name, alt) if x)
    aliases = f"C{caldwell}" if caldwell and not ident.startswith("C ") else ""
    group = 0 if messier else 1 if caldwell else 2
    order = int(messier) if messier else caldwell or 0
    out.append((group, order, ident, label, t, hms(r["RA"]), dms(r["Dec"]), m, r["Const"], aliases))
out.sort(key=lambda x: (x[0], x[1], x[2]))
for o in out:
    print(f"{o[2]}\t{o[3]}\t{o[4]}\t{o[5]:.5f}\t{o[6]:.4f}\t{'' if o[7] is None else o[7]}\t{o[8]}\t{o[9]}")
