# Post-Quantum Verifiable Voting with Coercion Resistance

This repository contains **Oylama**, a post-quantum verifiable voting scheme
with ballot privacy against a malicious bulletin board, and of **Oylama-CR**,
its coercion resistant variant, along with its [Android Application](https://github.com/ethanwaker9/work-36/blob/main/android_app/Oylama.apk) and the [Web Application](https://github.com/ethanwaker9/work-36/tree/main/web_app) proof of concepts. The repository also contains a reimplementation of the cryptographic core of other prior post-quantum voting schemes, used for the complexity and performance comparison in our research.

The work is built from standardized primitives as threshold ML-KEM-768
(FIPS 203), ML-DSA-65 (FIPS 204), SHAKE256 (FIPS 202), AES-256-GCM (FIPS 197 and
SP 800-38D), BDLOP module commitments, and the lattice mix-net of Aranha, Baum,
Gjøsteen and Silde (CCS 2023). No new cryptographic building block is
introduced.

## Files and Contents

| Path | Contents |
| --- | --- |
| `oylama/` | The scheme as module BGV encryption, BDLOP commitments, Fiat–Shamir proofs with aborts, verifiable shuffle, verifiable distributed decryption |
| `oylama/ringkit.py` | The ring instantiation of the same building blocks, used by the coercion resistant variant and by the mix-net baselines |
| `oylama_cr/` | Oylama-CR: packed BGV with slot rotations, masked multiplication, Batcher sorting network, cleansing and extraction |
| `baselines/` | The thirteen compared schemes and the shared lattice, code and multivariate cores |
| `experiments/` | Benchmarks, table generation and figure generation |
| `results/` | JSON output of the benchmarks |
| `web_app/` | Oylama as a complete online voting system that runs on localhost as a Python server that plays every election party and a browser client that encrypts ballots on the voter's device |
| `android_app/` | Oylama as an Android app, with the installable `android_app/Oylama.apk`; it runs a complete election node on the phone or connects to the web app |

## Install
```bash
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
```
`liboqs-python` provides ML-KEM and ML-DSA at reference speed. If it is not
available the code falls back to the pure Python `kyber-py` and `dilithium-py`
packages, which are slower but produce identical sizes; `python3 -c "from
oylama.pq import backend; print(backend())"` reports which one is in use.

## Running Experiments

```bash
python3 experiments/test_all.py 4
```
runs the correctness suite as NTT against a naive negacyclic convolution, vote
encoding, the proof of knowledge on a wrong target, a full Oylama election with
tally verification and two tamper checks, and validity plus tally for all
thirteen baselines. It takes about two minutes.
```bash
python3 experiments/bench_schemes.py 8
python3 experiments/bench_scale.py 8 16 32 64
python3 experiments/bench_cr.py 4 8 16
python3 experiments/make_tables.py 8
python3 experiments/make_figures.py
```
The second command is the scaling of Oylama (`results/scale.json`,
about ten minutes), the third the coercion resistant measurements
(`results/cr.json`, about one hour for the three sizes). 

## Parameters
Oylama uses a module of rank `k = 16` over `R = Z[X]/(X^256+1)`, so the lattice
dimension is 4096, with a modulus of three NTT primes of 31 bits, plaintext
modulus 2, ternary secrets, challenge weight 23 giving a challenge set larger
than `2^131`, flooding parameter 40, batch size 32, two mix servers and four
trustees of quorum three. A ballot is 71.8 KB. Oylama-CR uses a ring of degree
8192 with five primes of 24 bits and plaintext modulus 65537, so one distributed
decryption serves 8192 parallel binary gates.

In our research, every benchmarked schemes is measured at parameters that place its own hard
problem above 128 bits and that give every Fiat–Shamir proof a soundness error
below `2^-128`: one repetition with a weight `kappa` challenge over a ring or a
module, seven repetitions with a twenty bit scalar challenge over an
unstructured lattice, and 137 repetitions for the code-based proof. 

## Online voting system (web app)
`web_app/` turns Oylama into an end-to-end online election service that runs
on your own machine. The server is plain Python (standard library HTTP
server, SQLite, a separate tally process) and plays every party of the
scheme: election authority, registrar, bulletin board, three trustees, two
mix servers and the tally worker. The browser encrypts and proves each ballot
locally in a Web Worker, so the server only ever receives ciphertexts and
proofs, which it verifies before posting them to the board.

### Start
```bash
pip install -r requirements.txt
python3 web_app/run.py
```
Open <http://localhost:8000>. On the first start the server prepares three
demo elections with real keys, credentials and ballots:
| Election | State |
| --- | --- |
| Student Union President 2026 | voting is open, Alice already holds a credential |
| Referendum: Solar Roof for the Library | tallied in the background, published after about three minutes |
| Faculty Senate Election 2026 | waiting for trustees to join its key ceremony |

| Option | Meaning |
| --- | --- |
| `--port 8000` | HTTP port |
| `--host 0.0.0.0` | listening interface; the default also accepts phones on the same network |
| `--data DIR` | location of the database (default `web_app/data`) |
| `--reset` | delete all stored elections before starting |
| `--no-demo` | start without demo elections |

### Signing in
We intentionally considered signing in as a toy so that anyone can explore every role as pick a
role, type any email or username and any password, and press *Sign in*. The
Google, Microsoft, Apple and passkey buttons open a simulated account chooser,
and the one-click demo accounts sign in directly. The role can be switched
later from the account menu.

## Android app
`android_app/Oylama.apk` is a signed, installable build for Android 8.0 and
newer. Copy it to a phone and open it (allow installation from that source),
or install it on a connected device or emulator:

```bash
adb install android_app/Oylama.apk
```

The build needs JDK 17 and the Android SDK with platform 34 and build tools 34.

```bash
cd android_app
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.
It is signed with the demo keystore `android_app/keystore/oylama-release.jks`
(store and key password `oylama-demo`), which is suitable for testing only.

