import json
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib import cm
from mpl_toolkits.mplot3d import Axes3D

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "results")
FIG = os.path.join(os.path.dirname(BASE), "final_paper", "figures")

plt.rcParams.update({
    "font.size": 8, "axes.labelsize": 8, "axes.titlesize": 8,
    "legend.fontsize": 7, "xtick.labelsize": 7, "ytick.labelsize": 7,
    "figure.dpi": 300, "savefig.bbox": "tight", "savefig.pad_inches": 0.02,
})


def save(fig, name):
    os.makedirs(FIG, exist_ok=True)
    eps = os.path.join(FIG, name + ".eps")
    fig.savefig(eps, format="eps")
    os.system('cd "%s" && epstopdf %s.eps' % (FIG, name))
    plt.close(fig)
    print("wrote", name)


def fig_schemes(nv=8):
    with open(os.path.join(RES, "schemes_n%d.json" % nv)) as f:
        d = json.load(f)
    with open(os.path.join(RES, "scale.json")) as f:
        srows = json.load(f)["rows"]
    rows = d["rows"]
    names = [r["name"] for r in rows]
    idx = np.arange(len(names))
    fig = plt.figure(figsize=(7.0, 3.85))
    gs = fig.add_gridspec(3, 2, height_ratios=[1.0, 1.35, 0.95],
                          hspace=0.62, wspace=0.30,
                          left=0.075, right=0.995, top=0.99, bottom=0.085)
    keys = [("setup_ms", "Setup"), ("vote_ms", "Vote"), ("valid_ms", "Valid"),
            ("tally_ms", "Tally"), ("verify_ms", "Verify")]
    ax = fig.add_subplot(gs[0, :])
    w = 0.16
    cols = plt.cm.viridis(np.linspace(0.05, 0.85, len(keys)))
    for j, (k, lab) in enumerate(keys):
        vals = np.array([max(r[k] if r[k] is not None else 0.0, 1e-4)
                         for r in rows])
        ax.bar(idx + (j - 2) * w, vals, w, label=lab, color=cols[j],
               edgecolor="black", linewidth=0.25)
    ax.set_yscale("log")
    ax.set_ylabel("ms per ballot")
    lo = min(max(r[k] if r[k] is not None else 1e-4, 1e-4)
             for r in rows for k, _ in keys)
    hi = max(r[k] if r[k] is not None else 0.0
             for r in rows for k, _ in keys)
    ax.set_ylim(lo / 3.0, hi * 60.0)
    ax.set_xticks(idx)
    ax.set_xticklabels([])
    ax.set_xlim(-0.6, len(names) - 0.4)
    ax.grid(axis="y", ls=":", lw=0.4)
    ax.legend(ncol=5, frameon=False, loc="upper left", fontsize=6.5,
              handlelength=1.0, columnspacing=1.0)
    ax.axvline(len(names) - 1.5, color="red", ls="--", lw=0.8)
    ax = fig.add_subplot(gs[1, :])
    keys2 = [("setup_kb", "Setup"), ("vote_kb", "Ballot"),
             ("tally_kb", "Tally transcript")]
    cols = plt.cm.plasma(np.linspace(0.1, 0.75, len(keys2)))
    w = 0.26
    for j, (k, lab) in enumerate(keys2):
        vals = np.array([max(r[k], 1e-3) for r in rows])
        ax.bar(idx + (j - 1) * w, vals, w, label=lab, color=cols[j],
               edgecolor="black", linewidth=0.25)
    ax.set_yscale("log")
    ax.set_ylabel("size (KB)")
    lo = min(max(r[k], 1e-3) for r in rows for k, _ in keys2)
    hi = max(r[k] for r in rows for k, _ in keys2)
    ax.set_ylim(lo / 3.0, hi * 60.0)
    ax.set_xticks(idx)
    ax.set_xticklabels(names, rotation=30, ha="right")
    ax.set_xlim(-0.6, len(names) - 0.4)
    ax.grid(axis="y", ls=":", lw=0.4)
    ax.legend(ncol=3, frameon=False, loc="upper left", fontsize=6.5,
              handlelength=1.0, columnspacing=1.0)
    ax.axvline(len(names) - 1.5, color="red", ls="--", lw=0.8)
    nn = np.array([r["n"] for r in srows], dtype=float)
    ax = fig.add_subplot(gs[2, 0])
    ax.plot(nn, [r["tally_s"] for r in srows], "o-", lw=1.1, ms=3.5,
            label="Tally")
    ax.plot(nn, [r["verify_s"] for r in srows], "s--", lw=1.1, ms=3.5,
            label="Verify")
    ax.plot(nn, srows[-1]["tally_s"] / nn[-1] * nn, ":", color="gray", lw=1.0,
            label="linear reference")
    ax.set_xlabel("number of ballots $n$")
    ax.set_ylabel("time (s)")
    ax.grid(ls=":", lw=0.4)
    ax.legend(frameon=False, fontsize=6)
    ax = fig.add_subplot(gs[2, 1])
    ax.plot(nn, [r["proof_kb"] / 1024 for r in srows], "^-", lw=1.1, ms=3.5,
            color="darkred", label="tally transcript")
    ax.plot(nn, [r["ballot_kb"] * x / 1024 for r, x in zip(srows, nn)], "v--",
            lw=1.1, ms=3.5, color="navy", label="all ballots")
    ax.set_xlabel("number of ballots $n$")
    ax.set_ylabel("size (MB)")
    ax.grid(ls=":", lw=0.4)
    ax.legend(frameon=False, fontsize=6)
    save(fig, "fig_perf")


def fig_scale():
    with open(os.path.join(RES, "scale.json")) as f:
        rows = json.load(f)["rows"]
    n = np.array([r["n"] for r in rows], dtype=float)
    fig, axes = plt.subplots(1, 2, figsize=(7.0, 2.1))
    ax = axes[0]
    ax.plot(n, [r["tally_s"] for r in rows], "o-", lw=1.1, ms=3.5,
            label=r"$\mathsf{Tally}$")
    ax.plot(n, [r["verify_s"] for r in rows], "s--", lw=1.1, ms=3.5,
            label=r"$\mathsf{Verify}$")
    a = rows[-1]["tally_s"] / n[-1]
    ax.plot(n, a * n, ":", color="gray", lw=1.0, label="linear reference")
    ax.set_xlabel("number of ballots $n$")
    ax.set_ylabel("wall-clock time (s)")
    ax.grid(ls=":", lw=0.4)
    ax.legend(frameon=False)
    ax = axes[1]
    ax.plot(n, [r["proof_kb"] / 1024 for r in rows], "^-", lw=1.1, ms=3.5,
            color="darkred", label="tally transcript")
    ax.plot(n, [r["ballot_kb"] * x / 1024 for r, x in zip(rows, n)], "v--",
            lw=1.1, ms=3.5, color="navy", label="all ballots")
    ax.set_xlabel("number of ballots $n$")
    ax.set_ylabel("size (MB)")
    ax.grid(ls=":", lw=0.4)
    ax.legend(frameon=False)
    save(fig, "fig_scale")


def cr_model(M, lam, t_mult, t_rot, mbits=None):
    mbits = mbits or max(2, int(M).bit_length())
    keyb = lam + mbits
    B = 1
    while B < keyb + 3:
        B *= 2
    lg = int(np.log2(B))
    stages = 0
    p = 1
    while p < M:
        k = p
        while k >= 1:
            stages += 1
            k //= 2
        p *= 2
    mults = stages * (3 + lg) + 2 * lg + 2
    rots = stages * (5 + 2 * lg) + 2 * lg + 3
    G = max(1, (M * B) // 4096)
    return G * (mults * t_mult + rots * t_rot)


def fig_cr():
    with open(os.path.join(RES, "cr.json")) as f:
        rows = json.load(f)["rows"]
    Ms = np.array([r["M"] for r in rows], dtype=float)
    cl = np.array([r["cleanse_s"] for r in rows])
    A = np.stack([[cr_model(int(m), 128, 1.0, 0.0),
                   cr_model(int(m), 128, 0.0, 1.0)] for m in Ms])
    coef, *_ = np.linalg.lstsq(A, cl, rcond=None)
    t_mult, t_rot = float(coef[0]), float(coef[1])
    fig = plt.figure(figsize=(7.0, 2.45))
    gsc = fig.add_gridspec(1, 3, width_ratios=[1.0, 1.0, 1.65], wspace=0.34,
                           left=0.062, right=0.99, top=0.97, bottom=0.16)
    axes = [fig.add_subplot(gsc[0, 0]), fig.add_subplot(gsc[0, 1])]
    ax = axes[0]
    w = 0.36
    x = np.arange(len(rows))
    ax.bar(x - w / 2, [r["cleanse_s"] for r in rows], w, label="cleansing",
           color="#3b6ea5", edgecolor="black", linewidth=0.3)
    ax.bar(x + w / 2, [r["load_s"] + r["extract_s"] for r in rows], w,
           label="load and extract", color="#c1666b", edgecolor="black",
           linewidth=0.3)
    ax.set_xticks(x)
    ax.set_xticklabels([str(int(m)) for m in Ms])
    ax.set_xlabel("list length $M=n_B+n_V$")
    ax.set_ylabel("wall-clock time (s)")
    ax.set_ylim(0, max(r["cleanse_s"] for r in rows) * 1.42)
    ax.grid(axis="y", ls=":", lw=0.4)
    ax.legend(frameon=False, fontsize=6.5)
    ax = axes[1]
    ax.bar(x - w / 2, [r["mults"] for r in rows], w, label="gate batches",
           color="#4c956c", edgecolor="black", linewidth=0.3)
    ax.bar(x + w / 2, [r["opens"] for r in rows], w, label="openings",
           color="#e9c46a", edgecolor="black", linewidth=0.3)
    ax.set_xticks(x)
    ax.set_xticklabels([str(int(m)) for m in Ms])
    ax.set_xlabel("list length $M=n_B+n_V$")
    ax.set_ylabel("count")
    ax.set_ylim(0, max(r["opens"] for r in rows) * 1.42)
    ax.grid(axis="y", ls=":", lw=0.4)
    ax.legend(frameon=False, fontsize=6.5)

    Mg = np.array([4, 8, 16, 32, 64, 128, 256, 512, 1024])
    Lg = np.array([64, 96, 128, 160, 192, 224, 256])
    Z = np.zeros((len(Lg), len(Mg)))
    for i, l in enumerate(Lg):
        for j, m in enumerate(Mg):
            Z[i, j] = cr_model(int(m), int(l), t_mult, t_rot) / 60.0
    X, Y = np.meshgrid(np.log2(Mg), Lg)
    ax = fig.add_subplot(gsc[0, 2], projection="3d")
    LZ = np.log10(Z)
    zlo, zhi = float(LZ.min()) - 0.9, float(LZ.max()) + 0.15
    surf = ax.plot_surface(X, Y, LZ, cmap=cm.viridis, linewidth=0.25,
                           antialiased=True, edgecolor="k", alpha=0.95,
                           rstride=1, cstride=1)
    ax.contourf(X, Y, LZ, zdir="z", offset=zlo, levels=14, cmap=cm.viridis,
                alpha=0.55)
    ax.contour(X, Y, LZ, zdir="z", offset=zlo, levels=14, colors="k",
               linewidths=0.25)
    ax.contour(X, Y, LZ, zdir="y", offset=float(Lg.max()), levels=8,
               colors="0.35", linewidths=0.3)
    mx = np.log2(Ms)
    my = np.full_like(mx, 128.0)
    mz = np.log10(cl / 60.0)
    for a0, b0, c0 in zip(mx, my, mz):
        ax.plot([a0, a0], [b0, b0], [zlo, c0], color="red", lw=0.7, ls=":",
                zorder=10)
    ax.scatter(mx, my, np.full_like(mz, zlo), color="red", s=26,
               depthshade=False, edgecolor="black", linewidth=0.4,
               marker="o", zorder=11, label="measured")
    ax.scatter(mx, my, mz, color="red", s=30, depthshade=False,
               edgecolor="white", linewidth=0.5, marker="^", zorder=12)
    ax.set_xlabel(r"$\log_2 M$", fontsize=7, labelpad=-3)
    ax.set_ylabel(r"$\lambda$ (bits)", fontsize=7, labelpad=-3)
    ax.set_zlabel(r"$\log_{10}$ minutes", fontsize=7, labelpad=-4)
    ax.set_yticks([64, 128, 192, 256])
    ax.set_xticks([2, 4, 6, 8, 10])
    ax.set_zlim(zlo, zhi)
    ax.tick_params(labelsize=6, pad=-1.0)
    ax.view_init(elev=22, azim=-126)
    cb = fig.colorbar(surf, ax=ax, shrink=0.70, pad=0.02, aspect=15)
    cb.set_label(r"$\log_{10}$ minutes", fontsize=6.5)
    cb.ax.tick_params(labelsize=6)
    ax.legend(loc="upper left", frameon=False, fontsize=6)
    save(fig, "fig_cr")
    return t_mult, t_rot


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    if which in ("all", "schemes"):
        fig_schemes(8)
    if which in ("all", "scale"):
        fig_scale()
    if which in ("all", "cr"):
        print(fig_cr())
