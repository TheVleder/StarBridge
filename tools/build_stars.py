#!/usr/bin/env python3
"""Builds core/src/main/resources/stars.tsv from the HYG database v4.x (CC BY-SA 4.0,
https://codeberg.org/astronexus/hyg). Keeps stars brighter than MAG_LIMIT (J2000 coordinates).

Columns: hip, name, bayer, const, ra_hours, dec_deg, mag, flamsteed, hd, hr, variable.
Every designation is searchable ("α CMa", "61 Cyg", "HD 48915", "HR 2491", "R Leo").
Usage: python build_stars.py hyg_v44.csv.gz > core/src/main/resources/stars.tsv
"""
import csv, gzip, io, sys
sys.stdout.reconfigure(encoding="utf-8", newline="\n")

# About what a small telescope's finder shows; the map draws the faint ones only when zoomed.
MAG_LIMIT = 8.0

# Spanish names for the best-known stars; others keep their IAU proper name.
SPANISH = {
    "Sirius": "Sirio", "Arcturus": "Arturo", "Polaris": "Estrella Polar", "Castor": "Cástor",
    "Pollux": "Pólux", "Procyon": "Proción", "Aldebaran": "Aldebarán", "Spica": "Espiga",
    "Canopus": "Canopo", "Achernar": "Achernar", "Betelgeuse": "Betelgeuse",
}

GREEK = {"Alp": "α", "Bet": "β", "Gam": "γ", "Del": "δ", "Eps": "ε", "Zet": "ζ", "Eta": "η", "The": "θ",
         "Iot": "ι", "Kap": "κ", "Lam": "λ", "Mu": "μ", "Nu": "ν", "Xi": "ξ", "Omi": "ο", "Pi": "π",
         "Rho": "ρ", "Sig": "σ", "Tau": "τ", "Ups": "υ", "Phi": "φ", "Chi": "χ", "Psi": "ψ", "Ome": "ω"}

def bayer(row):
    b = row["bayer"].strip()
    if not b:
        return ""
    base = b.split("-")[0]
    return f"{GREEK.get(base, base)} {row['con']}"

def flamsteed(row):
    f = row["flam"].strip()
    return f"{int(float(f))} {row['con']}" if f else ""

def variable(row):
    """Variable-star name ("R Leo", "V822 Cyg"), unless it is just the Bayer letter again."""
    v = row["var"].strip()
    if not v or v.split("-")[0] in GREEK or not row["con"]:
        return ""
    return f"{v} {row['con']}"

def number(s):
    s = s.strip()
    return str(int(float(s))) if s else ""

rows = csv.DictReader(io.TextIOWrapper(gzip.open(sys.argv[1]), encoding="utf8"))
out = []
for r in rows:
    if r["proper"] == "Sol" or not r["mag"]:
        continue
    mag = float(r["mag"])
    if mag > MAG_LIMIT:
        continue
    proper = r["proper"].strip()
    name = SPANISH.get(proper, proper)
    hip = number(r["hip"]) or f"x{r['id']}"  # a few HYG stars have no HIP number
    out.append((mag, float(r["ra"]), float(r["dec"]), name, bayer(r), r["con"], hip,
                flamsteed(r), number(r["hd"]), number(r["hr"]), variable(r)))

out.sort()
print("# hip\tname\tbayer\tconst\tra_hours\tdec_deg\tmag\tflamsteed\thd\thr\tvariable\tSource: HYG v4 (CC BY-SA 4.0)")
for mag, ra, dec, name, bay, con, hip, flam, hd, hr, var in out:
    print(f"{hip}\t{name}\t{bay}\t{con}\t{ra:.5f}\t{dec:.4f}\t{mag:.2f}\t{flam}\t{hd}\t{hr}\t{var}")
