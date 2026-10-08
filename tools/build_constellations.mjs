#!/usr/bin/env node
// Builds core/src/main/resources/constellations.tsv from the d3-celestial data files
// (https://github.com/ofrohn/d3-celestial, BSD-3-Clause, Copyright (c) 2015, Olaf Frohn).
//
// Usage:
//   node tools/build_constellations.mjs constellations.lines.json constellations.json \
//        [core/src/main/resources/constellations.tsv]
//
// Output (UTF-8, LF, J2000):
//   L <TAB> abbr <TAB> ra,dec;ra,dec;...             one line per polyline
//   N <TAB> abbr <TAB> spanish_name <TAB> ra <TAB> dec  one line per constellation label
// RA in hours (0..24, 4 decimals), Dec in degrees (3 decimals).
//
// d3-celestial stores [lon, lat] in degrees with lon = RA mapped to -180..180.
// Serpens appears twice in both files (Caput and Cauda): its polylines are all kept under
// "Ser", but only the first label (Serpens Caput) is written so there is one N line per
// IAU constellation.

import { readFileSync, writeFileSync } from "node:fs";

const [linesPath, labelsPath, outPath = "core/src/main/resources/constellations.tsv"] =
  process.argv.slice(2);
if (!linesPath || !labelsPath) {
  console.error("usage: node build_constellations.mjs <constellations.lines.json> <constellations.json> [out.tsv]");
  process.exit(1);
}

const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
const lines = readJson(linesPath);
const labels = readJson(labelsPath);

// Formats a number, avoiding "-0.000"-style output.
function fixed(x, digits) {
  const s = x.toFixed(digits);
  return Number(s) === 0 ? (0).toFixed(digits) : s;
}

// lon (degrees, -180..180) -> RA hours string in [0, 24).
function raHours(lon) {
  const deg = ((lon % 360) + 360) % 360;
  let s = fixed(deg / 15, 4);
  if (Number(s) >= 24) s = fixed(Number(s) - 24, 4);
  return s;
}

function decDeg(lat) {
  if (lat < -90 || lat > 90) throw new Error(`declination out of range: ${lat}`);
  return fixed(lat, 3);
}

const abbrOf = (f) => (f.properties && f.properties.desig) || f.id;

const out = [
  "# Constellation lines and labels. Source: d3-celestial by Olaf Frohn " +
    "(BSD-3-Clause, Copyright (c) 2015, Olaf Frohn, https://github.com/ofrohn/d3-celestial)",
];

for (const f of lines.features) {
  const abbr = abbrOf(f);
  const g = f.geometry;
  const polylines = g.type === "MultiLineString" ? g.coordinates
    : g.type === "LineString" ? [g.coordinates]
    : null;
  if (!polylines) throw new Error(`${abbr}: unexpected geometry ${g.type}`);
  for (const pl of polylines) {
    if (pl.length < 2) continue;
    out.push(`L\t${abbr}\t${pl.map(([lon, lat]) => `${raHours(lon)},${decDeg(lat)}`).join(";")}`);
  }
}

const seen = new Set();
for (const f of labels.features) {
  const abbr = abbrOf(f);
  if (seen.has(abbr)) continue; // second Serpens part (Cauda)
  seen.add(abbr);
  if (f.geometry.type !== "Point") throw new Error(`${abbr}: label is not a Point`);
  const [lon, lat] = f.geometry.coordinates;
  const p = f.properties;
  // The usual Spanish names where the source keeps the Latin one or an unusual form.
  const SPANISH = { Car: 'Quilla', Oct: 'Octante' };
  const name = SPANISH[abbr] || (p.es && p.es.trim()) || p.name;
  out.push(`N\t${abbr}\t${name}\t${raHours(lon)}\t${decDeg(lat)}`);
}

writeFileSync(outPath, out.join("\n") + "\n", "utf8");
console.error(`wrote ${outPath}: ${out.length - 1 - seen.size} L lines, ${seen.size} N lines`);
