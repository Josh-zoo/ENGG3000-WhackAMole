"""
Analyse a TestLogger CSV (predicted or measured) into the Assessment 2 V1 vs V2 tables and figure.

    python tools/analyse_test.py v1v2_test.csv --outdir results

Metrics, per taped cell and overall, for each solver:
  fix rate        % of packets that produced any position
  tri rate        % of packets with a true multi-sensor fix (V1: S1+S2 circles, V2: 2+ sensors)
  median / P95    position error in cm, using the position the game would SHOW
                  (last fix held forward when a packet gives none), so dropouts count against you
  cell correct    % of packets where the shown position is in the right 50 x 50 cm box
Dead-zone rows (labels starting DZ) report the V2 alarm rate instead.
"""
import argparse
import os

import numpy as np
import pandas as pd

CELLS = ["A1", "B1", "C1", "A2", "B2", "C2", "A3", "B3", "C3"]


def cell_of(x, y):
    if np.isnan(x) or y < 50 or y >= 200 or x < 0 or x >= 150:
        return None
    return "ABC"[int(x // 50)] + str(int((y - 50) // 50) + 1)


def held(df, p):
    """Forward-fill each solver's position within a trial: what the cursor shows."""
    out = df.copy()
    for c in (f"{p}_x", f"{p}_y"):
        out[c] = out.groupby("trial")[c].ffill()
    return out


def per_solver(df, p, tri_mode):
    h = held(df, p)
    err = np.hypot(h[f"{p}_x"] - h.true_x, h[f"{p}_y"] - h.true_y)
    shown_cell = [cell_of(x, y) for x, y in zip(h[f"{p}_x"], h[f"{p}_y"])]
    return pd.DataFrame({
        "label": df.label,
        "fix": df[f"{p}_mode"] != "none",
        "tri": df[f"{p}_mode"] == tri_mode,
        "err": err,
        "correct": [s == l for s, l in zip(shown_cell, df.label)],
    })


def summarise(s):
    e = s.err.dropna()
    return pd.Series({
        "fix_rate": 100 * s.fix.mean(),
        "tri_rate": 100 * s.tri.mean(),
        "median_err": e.median() if len(e) else np.nan,
        "p95_err": e.quantile(0.95) if len(e) else np.nan,
        "cell_correct": 100 * s.correct.mean(),
    })


def analyse(path, outdir):
    df = pd.read_csv(path)
    src = df.source.iloc[0]
    grid = df[df.label.isin(CELLS)].reset_index(drop=True)
    v1 = per_solver(grid, "v1", "tri")
    v2 = per_solver(grid, "v2", "multi")

    rows = []
    for lab in CELLS + ["ALL"]:
        m = slice(None) if lab == "ALL" else (grid.label == lab)
        a, b = summarise(v1[m]), summarise(v2[m])
        rows.append({"cell": lab, **{f"v1_{k}": v for k, v in a.items()}, **{f"v2_{k}": v for k, v in b.items()}})
    table = pd.DataFrame(rows)
    os.makedirs(outdir, exist_ok=True)
    table.round(1).to_csv(os.path.join(outdir, f"{src}_per_cell.csv"), index=False)

    dz = df[df.label.str.startswith("DZ")]
    dz_table = dz.groupby("label").v2_alarm.mean().mul(100).round(1) if len(dz) else None
    if dz_table is not None:
        dz_table.to_csv(os.path.join(outdir, f"{src}_deadzone.csv"))
    return src, table, dz_table


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--outdir", default="results")
    a = ap.parse_args()
    src, t, dz = analyse(a.csv, a.outdir)
    pd.set_option("display.width", 200)
    print(src)
    print(t.round(1).to_string(index=False))
    if dz is not None:
        print("V2 alarm rate, standing 40 cm from the wall (%):")
        print(dz.to_string())
