#!/usr/bin/env python3
"""Builds core/src/main/resources/stars.tsv from the HYG database v4.x (CC BY-SA 4.0,
https://codeberg.org/astronexus/hyg). Keeps stars brighter than MAG_LIMIT (J2000 coordinates).
Usage: python build_stars.py hyg_v44.csv.gz > core/src/main/resources/stars.tsv
"""
import csv, gzip, io, sys
sys.stdout.reconfigure(encoding="utf-8", newline="\n")

MAG_LIMIT = 4.5

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
    out.append((mag, float(r["ra"]), float(r["dec"]), name, bayer(r), r["con"], r["hip"] or r["id"]))

out.sort()
print("# hip\tname\tbayer\tconst\tra_hours\tdec_deg\tmag\tSource: HYG v4 (CC BY-SA 4.0)")
for mag, ra, dec, name, bay, con, hip in out:
    print(f"{hip}\t{name}\t{bay}\t{con}\t{ra:.6f}\t{dec:.5f}\t{mag:.2f}")
