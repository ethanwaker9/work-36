import json
import os
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "results")
OUT = os.path.join(os.path.dirname(BASE), "final_paper")

REF = {
    "Epoque": r"\cite{pq_voting_1_epoque}",
    "NTRU-Mix": r"\cite{pq_voting_2_mixnet}",
    "FOO-based": r"\cite{pq_voting_3_foo}",
    "Code-based": r"\cite{pq_voting_4_code}",
    "EVOLVE": r"\cite{pq_voting_5_evolve}",
    "EVOLVED": r"\cite{pq_voting_6_evolved}",
    "Homo-LWE": r"\cite{pq_voting_7_homo}",
    "BPRIV-BC": r"\cite{pq_voting_8_bc_bpriv}",
    "Kyber-BC": r"\cite{pq_voting_9_bc_kyber}",
    "Hybrid-BC": r"\cite{pq_voting_10_bc_hybrid}",
    "Steg-MQPC": r"\cite{pq_voting_11_stego}",
    "LWE-Voting": r"\cite{pq_voting_12_lwe}",
    "Helios-PQ": r"\cite{pq_voting_13_helios}",
    "Oylama": "",
}

GUAR = {
    "Epoque": "wBPRIV / i,u", "NTRU-Mix": "-- / u", "FOO-based": "-- / pu",
    "Code-based": "-- / i,u", "EVOLVE": "BPRIV / pu", "EVOLVED": "-- / pu",
    "Homo-LWE": "BPRIV$^\\ast$ / i,u", "BPRIV-BC": "BPRIV / i,u",
    "Kyber-BC": "-- / pi", "Hybrid-BC": "-- / pi", "Steg-MQPC": "-- / --",
    "LWE-Voting": "-- / u", "Helios-PQ": "-- / --",
    "Oylama": "\\textbf{du-mb} / \\textbf{str}",
}



WRAP_HEAD = r"""\begin{table*}[t]
\centering
\resizebox{\linewidth}{!}{%
\small
\begin{tabular}{@{}l l r r r r r r r r@{}}
\toprule
& & \multicolumn{2}{c}{\textbf{Setup}} & \multicolumn{2}{c}{\textbf{Vote}} &
\textbf{Valid} & \multicolumn{2}{c}{\textbf{Tally}} & \textbf{Verify}\\
\cmidrule(lr){3-4}\cmidrule(lr){5-6}\cmidrule(lr){7-7}\cmidrule(lr){8-9}\cmidrule(lr){10-10}
\textbf{Scheme} & \textbf{Privacy / Verifiability} & ms & KB & ms & KB & ms &
ms & KB & ms\\
\midrule
"""

WRAP_TAIL = r"""
\bottomrule
\end{tabular}}
\caption{Measured cost of the five procedures for eight ballots, two mix
servers, four trustees of quorum three and two options, on one core; times are
milliseconds per ballot and sizes kilobytes, with the setup column giving the
public election data, the vote column one ballot and the tally column the
transcript per ballot. The second column states the proven notions, where
\textsf{wBPRIV} is the weak notion of~\cite{vote_privacy}, \textsf{i},
\textsf{u}, \textsf{pi} and \textsf{pu} are individual, universal, partial
individual and partial universal verifiability, \textsf{du-mb} is Definition~\ref{def:du-mb-bpriv}, \textsf{str}
is Definition~\ref{def:strong-verifiability}, a dash is the absence of a stated
guarantee and an asterisk a proof sketch. A dash in the verify column marks a
scheme with no universal verification procedure, and a tally entry of a scheme
whose verifiability column carries a dash measures a plain decryption loop.}
\label{tab:empirical}
\end{table*}
"""

def f(x, nd=2):
    if x is None:
        return "--"
    if x >= 1e5:
        return "%.1e" % x
    if x >= 100:
        return "%.0f" % x
    if x >= 10:
        return "%.1f" % x
    return "%.*f" % (nd, x)


def empirical(nv=8):
    with open(os.path.join(RES, "schemes_n%d.json" % nv)) as fh:
        d = json.load(fh)
    lines = []
    for r in d["rows"]:
        nm = r["name"]
        bold = nm == "Oylama"
        pre = "\\rowcolor{gray!12}\\textbf{" if bold else ""
        post = "}" if bold else ""
        lines.append(
            "%s%s%s %s & %s & %s & %s & %s & %s & %s & %s & %s & %s \\\\"
            % (pre, nm, post, REF[nm], GUAR[nm],
               f(r["setup_ms"]), f(r["setup_kb"]), f(r["vote_ms"]),
               f(r["vote_kb"]), f(r["valid_ms"]), f(r["tally_ms"]),
               f(r["tally_kb"]), f(r["verify_ms"])))
    return "\n".join(lines)


if __name__ == "__main__":
    body = empirical(int(sys.argv[1]) if len(sys.argv) > 1 else 8)
    with open(os.path.join(OUT, "tab_empirical.tex"), "w") as fh:
        fh.write(WRAP_HEAD + body + WRAP_TAIL)
    print(body)
