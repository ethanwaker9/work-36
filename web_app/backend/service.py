import json
import multiprocessing
import os
import random
import re
import secrets
import shutil
import threading
import time
import traceback

import numpy as np

from .codec import (b64, ballot_from_json, ballot_from_npz, ballot_to_npz, cred_from_bytes,
                    cred_from_text, cred_to_bytes, cred_to_text, ring_to_b64, unb64)
from .engine import Oylama, Params
from .engine.aead import kdf, seal, unseal
from .engine.pq import BACKEND, Kem, Sig
from .engine.util import ser
from .engine.xof import DS_ROSTER, shake256
from .store import Store

ROLES = ("voter", "authority", "registrar", "trustee", "auditor")
CAPACITIES = (4, 8, 16)
PALETTE = ["#5B5BD6", "#0EA5E9", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899",
           "#14B8A6"]
SECONDS_PER_OPENING = 0.78
DEMO_TRUSTEES = [("ayla.demir@trustees.oylama.demo", "Prof. Ayla Demir"),
                 ("kenji.mori@trustees.oylama.demo", "Dr. Kenji Mori"),
                 ("maria.santos@trustees.oylama.demo", "Maria Santos")]
STEPS = [
    ("verify", "Re-verify every ballot proof on the board"),
    ("load", "Load ballots and the encrypted roster into one packed ciphertext"),
    ("sort", "Oblivious Batcher sort by credential and board position"),
    ("dedup", "Encrypted duplicate detection: the last ballot per credential wins"),
    ("roster", "Encrypted roster matching: fake credentials find no partner"),
    ("valid", "Compute and broadcast the encrypted validity bits"),
    ("gate", "Gate the votes of invalid entries to an empty vote"),
    ("extract", "Extract every encrypted vote slot"),
    ("mix", "Verifiable shuffle by two mix servers"),
    ("decrypt", "Threshold decryption with verified trustee shares"),
    ("count", "Count and publish the result"),
]


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def _now():
    return time.time()


def pretty_name(email):
    local = email.split("@")[0]
    parts = [p for p in re.split(r"[._\-+]+", local) if p]
    return " ".join(p[:1].upper() + p[1:] for p in parts) or email


def normalize_email(text):
    text = (text or "").strip().lower()
    if not text:
        return ""
    if "@" not in text:
        text = re.sub(r"[^a-z0-9._-]", "", text) or "guest"
        text = text + "@oylama.demo"
    return text


def mask_email(email):
    if not email or "@" not in email:
        return email
    local, dom = email.split("@", 1)
    return (local[:1] + "•" * max(2, len(local) - 1)) + "@" + dom


def phase_label(phase, nb, nv):
    parts = phase.split(":")
    key = parts[0]
    if key == "load":
        return "Loading entry %d of %d into the packed ciphertext" % (int(parts[1]) + 1, max(1, nb + nv))
    if key == "replicate":
        return "Replicating the entry layout across slot copies"
    if key == "sort":
        return "Batcher sorting network, stage %s of %s (masked compare and swap)" % (parts[1], parts[2])
    if key == "dedup":
        return "Detecting repeated credentials so only the last ballot survives"
    if key == "roster":
        return "Matching ballots against the encrypted roster"
    if key == "valid":
        return "Computing and broadcasting the validity bits"
    if key == "gate":
        return "Gating the votes of invalid entries"
    if key == "extract":
        return "Extracting encrypted vote slot %s of %s" % (parts[1], parts[2])
    return phase


class Service:
    def __init__(self, data_dir):
        self.data_dir = data_dir
        os.makedirs(os.path.join(data_dir, "elections"), exist_ok=True)
        self.db = Store(os.path.join(data_dir, "oylama.db"))
        self.params = Params()
        self._schemes = {}
        self._locks = {}
        self._glock = threading.RLock()
        self.processes = {}

    def lock(self, eid):
        with self._glock:
            return self._locks.setdefault(eid, threading.RLock())

    def edir(self, eid, *parts):
        return os.path.join(self.data_dir, "elections", eid, *parts)

    def system(self):
        pp = self.params
        return dict(name="Oylama", backend=BACKEND, ring_degree=pp.N, primes=pp.primes,
                    prime_bits=pp.prime_bits, plaintext_modulus=pp.p, credential_bits=pp.lam,
                    challenge_weight=pp.kappa, flooding_bits=pp.sec, mix_servers=pp.n_mix,
                    trustees=pp.n_trustee, threshold=pp.threshold, capacities=list(CAPACITIES),
                    signature="ML-DSA-65", kem="ML-KEM-768", aead="AES-256-GCM", xof="SHAKE256",
                    roles=list(ROLES), seconds_per_opening=SECONDS_PER_OPENING)

    def user(self, email):
        return self.db.one("SELECT * FROM users WHERE email=?", (email,))

    def ensure_user(self, email, name=None, provider="password"):
        u = self.user(email)
        if u:
            return u
        ek, dk = Kem.keygen()
        self.db.run("INSERT OR IGNORE INTO users (email, name, provider, created, kem_ek, kem_dk) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (email, name or pretty_name(email), provider, _now(), ek, dk))
        return self.user(email)

    def public_user(self, u, role):
        return dict(email=u["email"], name=u["name"], provider=u["provider"], role=role,
                    device_key=shake256(u["kem_ek"]).hex()[:24])

    def login(self, body):
        email = normalize_email(body.get("email")) or "guest-%s@oylama.demo" % secrets.token_hex(3)
        role = body.get("role") if body.get("role") in ROLES else "voter"
        provider = body.get("provider") or "password"
        name = (body.get("name") or "").strip() or pretty_name(email)
        u = self.ensure_user(email, name, provider)
        token = secrets.token_urlsafe(24)
        self.db.run("INSERT INTO sessions (token, email, role, created) VALUES (?, ?, ?, ?)",
                    (token, email, role, _now()))
        return dict(token=token, user=self.public_user(u, role))

    def session(self, token):
        if not token:
            return None
        s = self.db.one("SELECT * FROM sessions WHERE token=?", (token,))
        if not s:
            return None
        u = self.user(s["email"])
        if not u:
            return None
        return dict(token=token, role=s["role"], email=u["email"], name=u["name"],
                    user=self.public_user(u, s["role"]))

    def logout(self, token):
        self.db.run("DELETE FROM sessions WHERE token=?", (token,))
        return dict(ok=True)

    def require(self, eid):
        e = self.db.election(eid)
        if not e:
            raise ApiError(404, "Election not found")
        return e

    def need(self, actor, *roles):
        if actor is None:
            raise ApiError(401, "Please sign in first")
        if roles and actor["role"] not in roles:
            raise ApiError(403, "This action needs the %s role" % " or ".join(roles))

    def scheme(self, eid):
        with self._glock:
            if eid in self._schemes:
                return self._schemes[eid]
        e = self.require(eid)
        path = self.edir(eid, "pk.npz")
        if not os.path.exists(path):
            raise ApiError(409, "The key ceremony has not finished yet")
        V = Oylama(bytes.fromhex(e["uid"]), e["capacity"], len(e["candidates"]), pp=self.params)
        d = np.load(path)
        V.attach((d["a"].astype(np.int64), d["b"].astype(np.int64)))
        with self._glock:
            self._schemes[eid] = V
        return V

    def create_election(self, actor, body):
        self.need(actor, "authority")
        title = (body.get("title") or "").strip()
        if not title:
            raise ApiError(400, "An election needs a title")
        cands = []
        for i, c in enumerate(body.get("candidates") or []):
            if isinstance(c, str):
                c = dict(name=c)
            name = (c.get("name") or "").strip()
            if not name:
                continue
            cands.append(dict(idx=len(cands) + 1, name=name[:80],
                              party=(c.get("party") or "").strip()[:80],
                              bio=(c.get("bio") or "").strip()[:400],
                              color=c.get("color") or PALETTE[len(cands) % len(PALETTE)]))
        if len(cands) < 2:
            raise ApiError(400, "Add at least two candidates or options")
        if len(cands) > 8:
            raise ApiError(400, "Oylama ballots here support up to eight options")
        capacity = int(body.get("capacity") or 8)
        if capacity not in CAPACITIES:
            raise ApiError(400, "Board capacity must be one of 4, 8 or 16 entries")
        eligibility = "list" if body.get("eligibility") == "list" else "open"
        eid = "el-" + secrets.token_hex(4)
        now = _now()
        data = dict(id=eid, uid=os.urandom(16).hex(), title=title[:140],
                    description=(body.get("description") or "").strip()[:2000],
                    organization=(body.get("organization") or "").strip()[:120],
                    candidates=cands, capacity=capacity, n_trustees=self.params.n_trustee,
                    threshold=self.params.threshold, eligibility=eligibility,
                    auto_issue=bool(body.get("auto_issue", True)), status="ceremony",
                    created=now, created_by=actor["email"],
                    opens_at=body.get("opens_at") or "", closes_at=body.get("closes_at") or "",
                    phases=dict(ceremony=now), results=None, decoys=0, key=None, registrar=None)
        os.makedirs(self.edir(eid, "roster"), exist_ok=True)
        os.makedirs(self.edir(eid, "ballots"), exist_ok=True)
        self.db.save_election(data)
        seats = body.get("trustees") or []
        stmts = []
        for j in range(self.params.n_trustee):
            s = seats[j] if j < len(seats) and isinstance(seats[j], dict) else {}
            em = normalize_email(s.get("email")) or None
            stmts.append(("INSERT INTO trustees (election, idx, email, name, status) VALUES (?, ?, ?, ?, ?)",
                          (eid, j, em, (s.get("name") or (pretty_name(em) if em else None)), "open")))
        for em in self._emails(body.get("voters")):
            stmts.append(("INSERT OR IGNORE INTO voters (election, email, name, status) VALUES (?, ?, ?, ?)",
                          (eid, em, pretty_name(em), "eligible")))
        self.db.many(stmts)
        self.db.event(eid, actor["email"], "created",
                      "Election created with %d options and a board of %d entries" % (len(cands), capacity))
        return self.election_view(eid, actor)

    def _emails(self, value):
        if isinstance(value, str):
            value = re.split(r"[\s,;]+", value)
        out = []
        for v in value or []:
            em = normalize_email(v)
            if em and em not in out:
                out.append(em)
        return out

    def delete_election(self, actor, eid):
        self.need(actor, "authority")
        e = self.require(eid)
        if e["status"] == "tallying":
            raise ApiError(409, "Wait until the tally has finished")
        self.db.many([("DELETE FROM elections WHERE id=?", (eid,)),
                      ("DELETE FROM trustees WHERE election=?", (eid,)),
                      ("DELETE FROM voters WHERE election=?", (eid,)),
                      ("DELETE FROM ballots WHERE election=?", (eid,)),
                      ("DELETE FROM events WHERE election=?", (eid,)),
                      ("DELETE FROM tally WHERE election=?", (eid,)),
                      ("DELETE FROM tally_log WHERE election=?", (eid,))])
        with self._glock:
            self._schemes.pop(eid, None)
        shutil.rmtree(self.edir(eid), ignore_errors=True)
        return dict(ok=True)

    def counts(self, eid):
        roster = self.db.one("SELECT COUNT(*) AS c FROM voters WHERE election=? AND roster_pos IS NOT NULL", (eid,))["c"]
        ballots = self.db.one("SELECT COUNT(*) AS c FROM ballots WHERE election=?", (eid,))["c"]
        requested = self.db.one("SELECT COUNT(*) AS c FROM voters WHERE election=? AND status='requested'", (eid,))["c"]
        eligible = self.db.one("SELECT COUNT(*) AS c FROM voters WHERE election=?", (eid,))["c"]
        opened = self.db.one("SELECT COUNT(*) AS c FROM voters WHERE election=? AND status='opened'", (eid,))["c"]
        return dict(roster=roster, ballots=ballots, requested=requested, listed=eligible, opened=opened)

    def trustees(self, eid, full=True):
        rows = self.db.all("SELECT * FROM trustees WHERE election=? ORDER BY idx", (eid,))
        out = []
        for r in rows:
            out.append(dict(idx=r["idx"], seat=r["idx"] + 1, email=r["email"] if full else mask_email(r["email"]),
                            name=r["name"], status=r["status"], joined=r["joined"], approved=r["approved"],
                            fingerprint=r["fingerprint"]))
        return out

    def election_summary(self, e, actor=None):
        c = self.counts(e["id"])
        tr = self.db.all("SELECT status, approved FROM trustees WHERE election=?", (e["id"],))
        t = self.db.tally(e["id"]) or {}
        return dict(id=e["id"], title=e["title"], organization=e["organization"],
                    description=e["description"], status=e["status"], capacity=e["capacity"],
                    candidates=e["candidates"], created=e["created"], phases=e["phases"],
                    opens_at=e["opens_at"], closes_at=e["closes_at"], eligibility=e["eligibility"],
                    used=c["roster"] + c["ballots"], roster=c["roster"], ballots=c["ballots"],
                    requested=c["requested"],
                    trustees_joined=sum(1 for r in tr if r["status"] == "joined"),
                    approvals=sum(1 for r in tr if r["approved"]), threshold=e["threshold"],
                    n_trustees=e["n_trustees"],
                    tally=dict(state=t.get("state"), done=t.get("done"), total=t.get("total"),
                               eta=t.get("eta"), label=t.get("label")) if t else None,
                    results=self._results_public(e), me=self._me(e, actor))

    def _results_public(self, e):
        r = e.get("results")
        if not r:
            return None
        return dict(counts=r["counts"], total_valid=r["total_valid"], published=r["published"],
                    verified=r["verified"])

    def _me(self, e, actor):
        if not actor:
            return None
        v = self.db.one("SELECT status, roster_pos, issued, opened, requested FROM voters WHERE election=? AND email=?",
                        (e["id"], actor["email"]))
        seats = [r["idx"] for r in self.db.all("SELECT idx FROM trustees WHERE election=? AND email=?",
                                               (e["id"], actor["email"]))]
        return dict(voter=v, seats=seats)

    def list_elections(self, actor=None):
        return [self.election_summary(e, actor) for e in self.db.elections()]

    def election_view(self, eid, actor=None):
        e = self.require(eid)
        out = self.election_summary(e, actor)
        pp = self.params
        c = self.counts(eid)
        mbits = max(2, e["capacity"].bit_length())
        vbits = max(1, len(e["candidates"]).bit_length())
        plan_total = {4: 206, 8: 342, 16: 529}[e["capacity"]]
        if e.get("key") and c["roster"] + c["ballots"]:
            plan_total = self.scheme(eid).plan(c["ballots"], c["roster"])["total"]
        est = int(plan_total * SECONDS_PER_OPENING + 8)
        out.update(trustees=self.trustees(eid, full=actor is not None and actor["role"] in ("authority", "trustee")),
                   key=e.get("key"), uid=e["uid"], auto_issue=e["auto_issue"], decoys=e.get("decoys", 0)
                   if actor and actor["role"] == "authority" else None,
                   registrar=dict((k, v) for k, v in (e.get("registrar") or {}).items() if k != "pk"),
                   params=dict(ring_degree=pp.N, slots=pp.N, primes=pp.primes, log_q=sum(p.bit_length() for p in pp.primes),
                               plaintext_modulus=pp.p, credential_bits=pp.lam, index_bits=mbits, vote_bits=vbits,
                               bit_blocks=256, challenge_weight=pp.kappa, mix_servers=pp.n_mix,
                               threshold="%d of %d" % (pp.threshold, pp.n_trustee), ballot_kb=688,
                               estimated_tally_seconds=est, backend=BACKEND),
                   results_full=e.get("results"), created_by=e["created_by"])
        return out

    def open_voting(self, actor, eid):
        self.need(actor, "authority")
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] != "registration":
                raise ApiError(409, "Voting can be opened once the key ceremony is done")
            e["status"] = "voting"
            e["phases"]["voting"] = _now()
            self.db.save_election(e)
        self.db.event(eid, actor["email"], "voting", "Voting opened: the bulletin board accepts anonymous ballots")
        return self.election_view(eid, actor)

    def close_voting(self, actor, eid):
        self.need(actor, "authority")
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] != "voting":
                raise ApiError(409, "Voting is not open")
            e["status"] = "closed"
            e["phases"]["closed"] = _now()
            self.db.save_election(e)
        self.db.event(eid, actor["email"], "closed", "Voting closed: waiting for %d trustees to approve the tally" % e["threshold"])
        return self.election_view(eid, actor)

    def join_trustee(self, actor, eid, idx, simulated=False):
        if not simulated:
            self.need(actor, "trustee")
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] != "ceremony":
                raise ApiError(409, "The key ceremony is already complete")
            seat = self.db.one("SELECT * FROM trustees WHERE election=? AND idx=?", (eid, idx))
            if not seat:
                raise ApiError(404, "No such trustee seat")
            if seat["status"] == "joined":
                if seat["email"] == actor["email"]:
                    return self.election_view(eid, actor)
                raise ApiError(409, "Seat %d is already taken" % (idx + 1))
            if seat["email"] and seat["email"] != actor["email"]:
                raise ApiError(403, "Seat %d is reserved for %s" % (idx + 1, seat["email"]))
            other = self.db.one("SELECT idx FROM trustees WHERE election=? AND email=? AND status='joined'",
                                (eid, actor["email"]))
            if other:
                raise ApiError(409, "You already hold seat %d; every seat needs a different trustee" % (other["idx"] + 1))
            self.db.run("UPDATE trustees SET email=?, name=?, status='joined', joined=? WHERE election=? AND idx=?",
                        (actor["email"], actor.get("name") or pretty_name(actor["email"]), _now(), eid, idx))
            self.db.event(eid, actor["email"], "trustee", "%s joined the key ceremony as trustee %d"
                          % (actor.get("name") or actor["email"], idx + 1))
            left = self.db.one("SELECT COUNT(*) AS c FROM trustees WHERE election=? AND status!='joined'", (eid,))["c"]
            if left == 0:
                self._ceremony(eid)
        return self.election_view(eid, actor)

    def auto_ceremony(self, actor, eid):
        self.need(actor, "authority", "trustee")
        e = self.require(eid)
        if e["status"] != "ceremony":
            raise ApiError(409, "The key ceremony is already complete")
        seats = self.db.all("SELECT * FROM trustees WHERE election=? ORDER BY idx", (eid,))
        taken = {s["email"] for s in seats if s["status"] == "joined"}
        pool = [t for t in DEMO_TRUSTEES if t[0] not in taken]
        for s in seats:
            if s["status"] == "joined":
                continue
            if s["email"]:
                em, nm = s["email"], s["name"] or pretty_name(s["email"])
            else:
                em, nm = pool.pop(0)
            self.ensure_user(em, nm)
            self.join_trustee(dict(email=em, name=nm, role="trustee"), eid, s["idx"], simulated=True)
        return self.election_view(eid, actor)

    def _ceremony(self, eid):
        e = self.require(eid)
        if e.get("key"):
            return
        t0 = time.perf_counter()
        V = Oylama(bytes.fromhex(e["uid"]), e["capacity"], len(e["candidates"]), pp=self.params)
        pk, s = V.keygen(os.urandom(32))
        V.attach(pk)
        tk = V.deal(s, os.urandom(32))
        del s
        np.savez(self.edir(eid, "pk.npz"), a=pk[0].astype(np.uint32), b=pk[1].astype(np.uint32))
        stmts = []
        for j in range(self.params.n_trustee):
            hold = tk.holdings(j)
            arrays = {"u_" + "_".join(str(x) for x in U): arr.astype(np.uint32) for U, arr in hold.items()}
            np.savez(self.edir(eid, "trustee_%d.npz" % j), **arrays)
            fp = shake256(b"OYLAMA/share", bytes.fromhex(e["uid"]), j,
                          *[ser(hold[U]) for U in sorted(hold)]).hex()[:32]
            stmts.append(("UPDATE trustees SET fingerprint=? WHERE election=? AND idx=?", (fp, eid, j)))
        self.db.many(stmts)
        rpk, rsk = Sig.keygen()
        with open(self.edir(eid, "registrar.key"), "wb") as f:
            f.write(rsk)
        e["registrar"] = dict(alg=Sig.alg, pk=rpk.hex(), pk_fingerprint=shake256(rpk).hex()[:32],
                              root=None, signature=None, signed_entries=0)
        e["key"] = dict(fingerprint=shake256(ser(pk[0], pk[1])).hex()[:32], created=_now(),
                        seconds=round(time.perf_counter() - t0, 2),
                        size_kb=round(2 * V.R.L * V.R.n * 25 / 8 / 1024, 1))
        e["status"] = "registration"
        e["phases"]["registration"] = _now()
        self.db.save_election(e)
        with self._glock:
            self._schemes[eid] = V
        self.db.event(eid, "ceremony", "key", "Key ceremony complete: %d-of-%d threshold key over R_q with N=%d, public key %s"
                      % (e["threshold"], e["n_trustees"], self.params.N, e["key"]["fingerprint"][:16]))

    def holdings(self, eid, j):
        d = np.load(self.edir(eid, "trustee_%d.npz" % j))
        out = {}
        for name in d.files:
            U = tuple(int(x) for x in name[2:].split("_") if x != "")
            out[U] = d[name].astype(np.int64)
        return out

    def voters(self, actor, eid):
        self.need(actor, "registrar", "authority")
        self.require(eid)
        rows = self.db.all("SELECT * FROM voters WHERE election=? ORDER BY COALESCE(roster_pos, 99999), requested, email", (eid,))
        return [dict(email=r["email"], name=r["name"], status=r["status"], requested=r["requested"],
                     issued=r["issued"], opened=r["opened"], roster_pos=r["roster_pos"],
                     roster_digest=r["roster_digest"]) for r in rows]

    def add_voters(self, actor, eid, body):
        self.need(actor, "registrar", "authority")
        self.require(eid)
        ems = self._emails(body.get("emails"))
        if not ems:
            raise ApiError(400, "Enter at least one email address")
        self.db.many([("INSERT OR IGNORE INTO voters (election, email, name, status) VALUES (?, ?, ?, ?)",
                       (eid, em, pretty_name(em), "eligible")) for em in ems])
        self.db.event(eid, actor["email"], "eligibility", "%d %s added to the eligibility list" % (len(ems), "voter" if len(ems) == 1 else "voters"))
        if body.get("issue"):
            for em in ems:
                self.issue(actor, eid, em)
        return self.voters(actor, eid)

    def request_credential(self, actor, eid):
        self.need(actor, "voter")
        e = self.require(eid)
        if e["status"] not in ("registration", "voting"):
            raise ApiError(409, "Registration is not open for this election")
        row = self.db.one("SELECT * FROM voters WHERE election=? AND email=?", (eid, actor["email"]))
        if e["eligibility"] == "list" and not row:
            raise ApiError(403, "%s is not on the eligibility list of this election" % actor["email"])
        if row and row["status"] in ("issued", "opened"):
            return self.my_credential(actor, eid)
        self.ensure_user(actor["email"], actor.get("name"))
        if row:
            self.db.run("UPDATE voters SET status='requested', requested=? WHERE election=? AND email=?",
                        (_now(), eid, actor["email"]))
        else:
            self.db.run("INSERT INTO voters (election, email, name, status, requested) VALUES (?, ?, ?, ?, ?)",
                        (eid, actor["email"], actor.get("name") or pretty_name(actor["email"]), "requested", _now()))
        self.db.event(eid, actor["email"], "request", "%s asked the registrar for a voting credential" % mask_email(actor["email"]))
        if e["auto_issue"]:
            self.issue(dict(email="registrar@oylama.demo", role="registrar", name="Registrar service"), eid, actor["email"])
        return self.my_credential(actor, eid)

    def issue(self, actor, eid, email):
        self.need(actor, "registrar", "authority")
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] not in ("registration", "voting"):
                raise ApiError(409, "Credentials can be issued after the key ceremony and before voting closes")
            row = self.db.one("SELECT * FROM voters WHERE election=? AND email=?", (eid, email))
            if row and row["status"] in ("issued", "opened"):
                return dict(ok=True, already=True)
            c = self.counts(eid)
            if c["roster"] + c["ballots"] + 1 > e["capacity"]:
                raise ApiError(409, "The board is full: %d of %d entries are used" % (c["roster"] + c["ballots"], e["capacity"]))
            V = self.scheme(eid)
            u = self.ensure_user(email)
            cred = V.new_credential()
            pos = c["roster"]
            ct = V.register(cred, os.urandom(32))
            np.savez(self.edir(eid, "roster", "%d.npz" % pos), u=ct[0].astype(np.uint32), w=ct[1].astype(np.uint32))
            digest = shake256(DS_ROSTER, bytes.fromhex(e["uid"]), pos, ser(ct[0], ct[1])).hex()
            ss, kct = Kem.encaps(u["kem_ek"])
            aad = (e["uid"] + "|" + email).encode()
            blob = seal(kdf(ss, b"credential"), cred_to_bytes(cred), aad)
            env = json.dumps(dict(kem=Kem.alg, aead="AES-256-GCM", kem_ct=b64(kct), blob=b64(blob),
                                  sealed=_now(), device_key=shake256(u["kem_ek"]).hex()[:24]))
            del cred
            if row:
                self.db.run("UPDATE voters SET status='issued', issued=?, roster_pos=?, roster_digest=?, envelope=? "
                            "WHERE election=? AND email=?", (_now(), pos, digest, env, eid, email))
            else:
                self.db.run("INSERT INTO voters (election, email, name, status, issued, roster_pos, roster_digest, envelope) "
                            "VALUES (?, ?, ?, 'issued', ?, ?, ?, ?)",
                            (eid, email, u["name"], _now(), pos, digest, env))
            self._sign_roster(eid)
        self.db.event(eid, actor["email"], "issued", "Registrar sealed credential for roster entry %d with ML-KEM-768 and signed the roster with ML-DSA-65" % (pos + 1))
        return dict(ok=True, roster_pos=pos, digest=digest)

    def issue_all(self, actor, eid):
        self.need(actor, "registrar", "authority")
        rows = self.db.all("SELECT email FROM voters WHERE election=? AND status IN ('requested', 'eligible') ORDER BY requested", (eid,))
        done = 0
        for r in rows:
            try:
                self.issue(actor, eid, r["email"])
                done += 1
            except ApiError:
                if done == 0:
                    raise
                break
        return dict(issued=done, voters=self.voters(actor, eid))

    def _roster_digests(self, eid):
        rows = self.db.all("SELECT roster_pos, roster_digest FROM voters WHERE election=? AND roster_pos IS NOT NULL ORDER BY roster_pos", (eid,))
        return [r["roster_digest"] for r in rows]

    def _sign_roster(self, eid):
        e = self.require(eid)
        digests = self._roster_digests(eid)
        root = shake256(DS_ROSTER, bytes.fromhex(e["uid"]), *[bytes.fromhex(d) for d in digests])
        with open(self.edir(eid, "registrar.key"), "rb") as f:
            sk = f.read()
        sig = Sig.sign(sk, root)
        e["registrar"]["root"] = root.hex()
        e["registrar"]["signature"] = sig.hex()
        e["registrar"]["signed_entries"] = len(digests)
        e["registrar"]["signed_at"] = _now()
        self.db.save_election(e)

    def my_credential(self, actor, eid):
        self.need(actor)
        self.require(eid)
        row = self.db.one("SELECT * FROM voters WHERE election=? AND email=?", (eid, actor["email"]))
        if not row:
            return dict(status="none")
        env = json.loads(row["envelope"]) if row["envelope"] else None
        if env:
            env = dict(kem=env["kem"], aead=env["aead"], kem_ct_preview=env["kem_ct"][:48],
                       blob_bytes=len(unb64(env["blob"])), sealed=env["sealed"], device_key=env.get("device_key"))
        return dict(status=row["status"], roster_pos=row["roster_pos"], roster_digest=row["roster_digest"],
                    requested=row["requested"], issued=row["issued"], opened=row["opened"], envelope=env)

    def open_envelope(self, actor, eid):
        self.need(actor, "voter")
        e = self.require(eid)
        row = self.db.one("SELECT * FROM voters WHERE election=? AND email=?", (eid, actor["email"]))
        if not row or not row["envelope"]:
            raise ApiError(404, "The registrar has not sealed a credential for you yet")
        u = self.user(actor["email"])
        env = json.loads(row["envelope"])
        ss = Kem.decaps(u["kem_dk"], unb64(env["kem_ct"]))
        aad = (e["uid"] + "|" + actor["email"]).encode()
        raw = unseal(kdf(ss, b"credential"), unb64(env["blob"]), aad)
        text = cred_to_text(cred_from_bytes(raw, self.params.lam))
        if row["status"] != "opened":
            self.db.run("UPDATE voters SET status='opened', opened=? WHERE election=? AND email=?",
                        (_now(), eid, actor["email"]))
        return dict(credential=text, roster_pos=row["roster_pos"], roster_digest=row["roster_digest"])

    def public_key(self, eid):
        e = self.require(eid)
        V = self.scheme(eid)
        return dict(election=eid, uid=e["uid"], N=V.pp.N, primes=V.pp.primes, p=V.pp.p, kappa=V.pp.kappa,
                    lam=V.lam, mmax=V.Mmax, mbits=V.mbits, vbits=V.vbits, keybits=V.keybits,
                    n_cands=V.n_cands, sigma_bound=V.sigma_bound(), a=ring_to_b64(V.pk[0]),
                    b=ring_to_b64(V.pk[1]), fingerprint=e["key"]["fingerprint"],
                    candidates=[dict(idx=c["idx"], name=c["name"]) for c in e["candidates"]])

    def cast(self, eid, body, channel="anonymous"):
        e = self.require(eid)
        if e["status"] != "voting":
            raise ApiError(409, "The bulletin board is not accepting ballots right now")
        V = self.scheme(eid)
        try:
            ballot = ballot_from_json(V, body.get("ballot") or body)
        except (KeyError, ValueError, TypeError) as ex:
            raise ApiError(400, "Malformed ballot: %s" % ex)
        return self._post(eid, V, ballot, channel)

    def _post(self, eid, V, ballot, channel):
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] != "voting":
                raise ApiError(409, "The bulletin board is not accepting ballots right now")
            c = self.counts(eid)
            if c["roster"] + c["ballots"] + 1 > e["capacity"]:
                raise ApiError(409, "The board is full: all %d entries are used" % e["capacity"])
            ukey = V.ballot_key(ballot).hex()
            if self.db.one("SELECT position FROM ballots WHERE election=? AND ukey=?", (eid, ukey)):
                raise ApiError(409, "This ballot is already on the board")
            t0 = time.perf_counter()
            ok = V.check_ballot(ballot)
            ms = (time.perf_counter() - t0) * 1000
            if not ok:
                raise ApiError(422, "The board rejected the ballot: a proof of knowledge did not verify")
            pos = c["ballots"]
            tracker = V.tracker(ballot).hex()
            np.savez(self.edir(eid, "ballots", "%d.npz" % pos), **ballot_to_npz(ballot))
            now = _now()
            self.db.run("INSERT INTO ballots (election, position, tracker, ukey, posted, size_bits, verify_ms, channel) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", (eid, pos, tracker, ukey, now, int(ballot["size_bits"]), ms, channel))
        self.db.event(eid, "board", "ballot", "Ballot %d accepted on the board, tracker %s" % (pos + 1, tracker[:12]))
        return dict(election=eid, position=pos, tracker=tracker, verify_ms=round(ms, 1),
                    size_kb=round(ballot["size_bits"] / 8192, 1), posted=now)

    def cast_with_credential(self, eid, cred_text, choice, channel="assisted"):
        V = self.scheme(eid)
        e = self.require(eid)
        choice = int(choice)
        if choice < 1 or choice > len(e["candidates"]):
            raise ApiError(400, "Unknown option")
        try:
            cred = cred_from_text(cred_text, V.lam)
        except ValueError as ex:
            raise ApiError(400, str(ex))
        return self._post(eid, V, V.vote(cred, choice, os.urandom(32)), channel)

    def inject_decoys(self, actor, eid, body):
        self.need(actor, "authority")
        e = self.require(eid)
        if e["status"] != "voting":
            raise ApiError(409, "Decoy ballots can be posted while voting is open")
        n = max(1, min(4, int(body.get("count") or 1)))
        V = self.scheme(eid)
        out = []
        for _ in range(n):
            cred = V.new_credential()
            choice = random.randint(1, len(e["candidates"]))
            out.append(self._post(eid, V, V.vote(cred, choice, os.urandom(32)), "decoy"))
        with self.lock(eid):
            e = self.require(eid)
            e["decoys"] = e.get("decoys", 0) + len(out)
            self.db.save_election(e)
        self.db.event(eid, actor["email"], "decoy", "Authority posted %d decoy %s under fabricated credentials" % (len(out), "ballot" if len(out) == 1 else "ballots"))
        return dict(posted=out)

    def board(self, eid):
        e = self.require(eid)
        rows = self.db.all("SELECT position, tracker, posted, size_bits, verify_ms FROM ballots WHERE election=? ORDER BY position", (eid,))
        return dict(election=eid, status=e["status"], capacity=e["capacity"],
                    roster=self.counts(eid)["roster"],
                    ballots=[dict(position=r["position"], tracker=r["tracker"], posted=r["posted"],
                                  size_kb=round(r["size_bits"] / 8192, 1), verify_ms=round(r["verify_ms"], 1))
                             for r in rows])

    def _load_ballot(self, eid, pos):
        path = self.edir(eid, "ballots", "%d.npz" % pos)
        if not os.path.exists(path):
            raise ApiError(404, "No ballot at that position")
        return ballot_from_npz(np.load(path))

    def ballot_detail(self, eid, pos):
        self.require(eid)
        row = self.db.one("SELECT * FROM ballots WHERE election=? AND position=?", (eid, pos))
        if not row:
            raise ApiError(404, "No ballot at that position")
        b = self._load_ballot(eid, pos)
        V = self.scheme(eid)

        def comp(name, ct):
            return dict(name=name, u_digest=shake256(ser(ct[0])).hex()[:32], w_digest=shake256(ser(ct[1])).hex()[:32],
                        preview=[int(x) for x in ct[0][0][:8]])
        proofs = []
        for label, (pr, sig) in (("vote", b["pi"][0]), ("credential", b["pi"][1])):
            proofs.append(dict(statement=label, challenge_seed=pr.data[0].hex(), sigma=round(sig, 1),
                               max_abs_z=int(np.max(np.abs(pr.data[1]))), bound=round(6 * sig, 1),
                               size_kb=round(pr.size_bits / 8192, 1)))
        return dict(position=pos, tracker=row["tracker"], posted=row["posted"], size_kb=round(row["size_bits"] / 8192, 1),
                    verify_ms=round(row["verify_ms"], 1), ciphertexts=[comp("vote ciphertext C1", b["C1"]),
                                                                        comp("credential ciphertext C2", b["C2"])],
                    proofs=proofs, uniqueness_key=row["ukey"][:32], ring=dict(L=V.R.L, N=V.R.n))

    def verify_ballot(self, eid, pos):
        b = self._load_ballot(eid, pos)
        V = self.scheme(eid)
        row = self.db.one("SELECT tracker FROM ballots WHERE election=? AND position=?", (eid, pos))
        t0 = time.perf_counter()
        ok = V.check_ballot(b)
        ms = (time.perf_counter() - t0) * 1000
        tracker_ok = V.tracker(b).hex() == row["tracker"]
        return dict(position=pos, proofs_ok=bool(ok), tracker_ok=tracker_ok, ms=round(ms, 1))

    def roster(self, actor, eid):
        e = self.require(eid)
        full = actor is not None and actor["role"] in ("registrar", "authority")
        rows = self.db.all("SELECT email, name, roster_pos, roster_digest, issued FROM voters WHERE election=? AND roster_pos IS NOT NULL ORDER BY roster_pos", (eid,))
        reg = e.get("registrar") or {}
        return dict(election=eid, alg=reg.get("alg"), registrar_key=reg.get("pk_fingerprint"), root=reg.get("root"),
                    signature_preview=(reg.get("signature") or "")[:96], signature_bytes=len(reg.get("signature") or "") // 2,
                    signed_entries=reg.get("signed_entries", 0),
                    entries=[dict(position=r["roster_pos"], voter=r["email"] if full else mask_email(r["email"]),
                                  name=r["name"] if full else None, digest=r["roster_digest"], issued=r["issued"])
                             for r in rows])

    def events(self, eid, limit=80):
        self.require(eid)
        return self.db.all("SELECT ts, actor, kind, message FROM events WHERE election=? ORDER BY id DESC LIMIT ?",
                           (eid, limit))

    def activity(self, limit=30):
        rows = self.db.all("SELECT e.election, e.ts, e.kind, e.message FROM events e ORDER BY e.id DESC LIMIT ?", (limit,))
        titles = {x["id"]: x["title"] for x in self.db.elections()}
        for r in rows:
            r["title"] = titles.get(r["election"], "")
        return rows

    def approve(self, actor, eid, idx, simulated=False):
        if not simulated:
            self.need(actor, "trustee")
        start = False
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] != "closed":
                raise ApiError(409, "The tally can be approved after voting closes")
            seat = self.db.one("SELECT * FROM trustees WHERE election=? AND idx=?", (eid, idx))
            if not seat or seat["status"] != "joined":
                raise ApiError(404, "No such trustee")
            if seat["email"] != actor["email"]:
                raise ApiError(403, "Seat %d belongs to %s" % (idx + 1, seat["email"]))
            if not seat["approved"]:
                self.db.run("UPDATE trustees SET approved=? WHERE election=? AND idx=?", (_now(), eid, idx))
                self.db.event(eid, actor["email"], "approve", "%s released decryption share %d for the tally"
                              % (seat["name"] or seat["email"], idx + 1))
            n = self.db.one("SELECT COUNT(*) AS c FROM trustees WHERE election=? AND approved IS NOT NULL", (eid,))["c"]
            start = n >= e["threshold"]
        if start:
            self.start_tally(eid)
        return self.election_view(eid, actor)

    def auto_approve(self, actor, eid):
        self.need(actor, "authority", "trustee")
        e = self.require(eid)
        if e["status"] != "closed":
            raise ApiError(409, "Close voting first")
        seats = self.db.all("SELECT * FROM trustees WHERE election=? ORDER BY idx", (eid,))
        have = sum(1 for s in seats if s["approved"])
        for s in seats:
            if have >= e["threshold"]:
                break
            if s["approved"]:
                continue
            self.approve(dict(email=s["email"], name=s["name"], role="trustee"), eid, s["idx"], simulated=True)
            have += 1
            if self.require(eid)["status"] != "closed":
                break
        return self.election_view(eid, actor)

    def start_tally(self, eid):
        with self.lock(eid):
            e = self.require(eid)
            if e["status"] != "closed":
                raise ApiError(409, "The election is not waiting for a tally")
            seats = self.db.all("SELECT idx FROM trustees WHERE election=? AND approved IS NOT NULL ORDER BY idx", (eid,))
            if len(seats) < e["threshold"]:
                raise ApiError(409, "%d trustee approvals are needed" % e["threshold"])
            quorum = [s["idx"] for s in seats[:e["threshold"]]]
            c = self.counts(eid)
            V = self.scheme(eid)
            plan = V.plan(c["ballots"], c["roster"]) if c["ballots"] + c["roster"] else dict(total=0, groups={}, stages=0)
            e["status"] = "tallying"
            e["phases"]["tallying"] = _now()
            self.db.save_election(e)
            state = dict(state="queued", queued=_now(), quorum=quorum, total=plan["total"], done=0,
                         groups=plan["groups"], stages=plan["stages"], label="Waiting for the tally worker",
                         steps=[dict(key=k, label=l, state="pending", detail="") for k, l in STEPS],
                         eta=int(plan["total"] * SECONDS_PER_OPENING + 8), board=c["ballots"], roster=c["roster"])
            self.db.save_tally(eid, state)
            self.db.run("DELETE FROM tally_log WHERE election=?", (eid,))
        self.db.event(eid, "tally", "tally", "Tally started with trustees %s" % " and ".join(str(j + 1) for j in quorum))
        ctx = multiprocessing.get_context("spawn")
        from .worker import tally_main
        p = ctx.Process(target=tally_main, args=(self.data_dir, eid), daemon=True)
        p.start()
        self.processes[eid] = p
        return state

    def tally_status(self, eid, since=0):
        e = self.require(eid)
        t = self.db.tally(eid)
        logs = self.db.all("SELECT id, ts, level, message FROM tally_log WHERE election=? AND id>? ORDER BY id DESC LIMIT 200",
                           (eid, int(since or 0)))
        logs.reverse()
        return dict(election=eid, status=e["status"], tally=t, log=logs, results=e.get("results"))

    def recover(self):
        for e in self.db.elections():
            if e["status"] == "tallying":
                e["status"] = "closed"
                self.db.save_election(e)
                t = self.db.tally(e["id"]) or {}
                t["state"] = "failed"
                t["error"] = "The server stopped during the tally. Approve it again to restart."
                self.db.save_tally(e["id"], t)
                self.db.run("UPDATE trustees SET approved=NULL WHERE election=?", (e["id"],))

    def run_tally(self, eid):
        t = self.db.tally(eid)
        e = self.require(eid)
        V = self.scheme(eid)
        quorum = t["quorum"]
        nb, nv = t["board"], t["roster"]
        steps = {s["key"]: s for s in t["steps"]}
        state = dict(t, state="running", started=_now(), label="Starting")
        last = [0.0]

        def push(force=False):
            if force or time.time() - last[0] > 0.7:
                last[0] = time.time()
                state["updated"] = _now()
                self.db.save_tally(eid, state)

        def mark(key, st, detail=None):
            s = steps[key]
            s["state"] = st
            if detail is not None:
                s["detail"] = detail
            if st == "active":
                s["started"] = _now()
            if st == "done":
                s["finished"] = _now()
            state["steps"] = [steps[k] for k, _ in STEPS]

        def log(msg, level="info"):
            self.db.tally_log(eid, msg, level)

        try:
            push(True)
            names = {r["idx"]: r["name"] for r in self.db.all("SELECT idx, name FROM trustees WHERE election=?", (eid,))}
            log("Quorum formed by trustee %s (%s) and trustee %s (%s)" % (
                quorum[0] + 1, names.get(quorum[0]), quorum[1] + 1, names.get(quorum[1])))
            mark("verify", "active")
            push(True)
            ballots = [self._load_ballot(eid, i) for i in range(nb)]
            roster = []
            for i in range(nv):
                d = np.load(self.edir(eid, "roster", "%d.npz" % i))
                roster.append((d["u"].astype(np.int64), d["w"].astype(np.int64)))
            good = sum(1 for b in ballots if V.check_ballot(b))
            digests = self._roster_digests(eid)
            reg = e.get("registrar") or {}
            root = shake256(DS_ROSTER, bytes.fromhex(e["uid"]), *[bytes.fromhex(d) for d in digests])
            sig_ok = nv == 0 or (reg.get("signature") and root.hex() == reg.get("root")
                                 and Sig.verify(bytes.fromhex(reg["pk"]), root, bytes.fromhex(reg["signature"])))
            if good != nb or not sig_ok:
                raise RuntimeError("board or roster verification failed")
            mark("verify", "done", "%d ballot proofs valid, roster of %d signed by the registrar" % (nb, nv))
            log("Verified %d %s (two proofs each) and the ML-DSA-65 roster signature over %d %s" % (nb, "ballot" if nb == 1 else "ballots", nv, "entry" if nv == 1 else "entries"))
            push(True)
            holds = {j: self.holdings(eid, j) for j in quorum}
            secret, public = os.urandom(32), os.urandom(32)
            order = [k for k, _ in STEPS]
            t_pipe = time.perf_counter()
            cur = dict(group=None, label=None)
            plan_groups = dict(state.get("groups") or {})
            plan_groups["load"] = plan_groups.get("load", 0) + plan_groups.pop("replicate", 0)
            group_done = {}

            def monitor(kind, eng, info):
                state["done"] = state.get("done", 0) + 1
                group = eng.phase.split(":")[0]
                if group == "replicate":
                    group = "load"
                label = phase_label(eng.phase, nb, nv)
                if group != cur["group"]:
                    if cur["group"] is not None:
                        mark(cur["group"], "done")
                    for k in order[order.index("load"):order.index(group)]:
                        if steps[k]["state"] != "done":
                            mark(k, "done")
                    mark(group, "active")
                    cur["group"] = group
                if label != cur["label"]:
                    cur["label"] = label
                    log(label)
                group_done[group] = group_done.get(group, 0) + 1
                steps[group]["detail"] = "%d of %d masked openings" % (group_done[group], plan_groups.get(group, 0))
                steps[group]["done"] = group_done[group]
                steps[group]["total"] = plan_groups.get(group, 0)
                state["label"] = label
                done, total = state["done"], max(1, state["total"])
                rate = (time.perf_counter() - t_pipe) / done
                state["eta"] = int(rate * (total - done) + 6)
                state["stats"] = dict(eng.stats)
                if state["done"] % 10 == 0:
                    log("Trustees %s opened masked batch %d of %d (%d slots); decryption shares %s" % (
                        " and ".join(str(j + 1) for j in quorum), done, total, V.pp.N,
                        "verified" if info.get("ok") else "FAILED"), "info" if info.get("ok") else "error")
                push()

            eng = V.evaluator(holds, quorum, secret, public, monitor)
            if nb + nv > 0:
                F = V.load(ballots, roster, public + b"ld")
                out = V.cleanse(F, nb, public + b"cl")
                vs = V.extract(out, V.Mmax, public + b"ex")
                if cur["group"]:
                    mark(cur["group"], "done")
                for k in order[order.index("load"):order.index("mix")]:
                    if steps[k]["state"] != "done":
                        mark(k, "done")
                st = eng.stats
                mark("extract", "done", "%d vote slots extracted" % V.Mmax)
                log("Cleansing finished: %d masked openings, %d of %d decryption shares and %d of %d mask proofs verified" % (
                    st["batches"], st["shares_ok"], st["shares"], st["masks_ok"], st["masks"]))
                state["label"] = "Mix servers shuffling the cleansed votes"
                mark("mix", "active")
                push(True)
                mixed, mrep = V.mix(vs, secret, public + b"mx")
                mark("mix", "done", " / ".join("server %d %s" % (m["server"], "verified" if m["ok"] else "FAILED") for m in mrep))
                for m in mrep:
                    log("Mix server %d shuffled %d ciphertexts; shuffle proof of %.1f MB %s" % (
                        m["server"], V.Mmax, m["bits"] / 8 / 2 ** 20, "verified" if m["ok"] else "FAILED"),
                        "info" if m["ok"] else "error")
                state["label"] = "Trustees decrypting the shuffled votes"
                mark("decrypt", "active")
                push(True)
                votes, drep = V.decrypt(mixed, public + b"dec")
                mark("decrypt", "done", " / ".join("trustee %d %s" % (d["trustee"], "verified" if d["ok"] else "FAILED") for d in drep))
                for d in drep:
                    log("Trustee %d produced %.1f MB of decryption shares; proof %s" % (
                        d["trustee"], d["bits"] / 8 / 2 ** 20, "verified" if d["ok"] else "FAILED"),
                        "info" if d["ok"] else "error")
            else:
                votes, mrep, drep = [], [], []
                for k in order[1:-1]:
                    mark(k, "done", "nothing to process")
            mark("count", "active")
            counts = [sum(1 for v in votes if v == c + 1) for c in range(len(e["candidates"]))]
            st = eng.stats
            verified = (st["shares_ok"] == st["shares"] and st["masks_ok"] == st["masks"]
                        and all(m["ok"] for m in mrep) and all(d["ok"] for d in drep))
            results = dict(counts=counts, total_valid=sum(counts), votes=votes, board=nb, roster=nv,
                           capacity=V.Mmax, discarded=nb - sum(counts), quorum=[j + 1 for j in quorum],
                           openings=st["batches"], shares=st["shares"], shares_ok=st["shares_ok"],
                           masks=st["masks"], masks_ok=st["masks_ok"], mults=st["mults"],
                           rotations=st["rotations"], mix=mrep, decryption=drep,
                           transcript_mb=round((st["proof_bits"] + st["dec_bits"]) / 8 / 2 ** 20, 1),
                           digest=eng.digest.hex(), seconds=round(time.time() - state["started"], 1),
                           published=_now(), verified=bool(verified))
            mark("count", "done", "%d valid %s counted" % (sum(counts), "vote" if sum(counts) == 1 else "votes"))
            e = self.require(eid)
            e["results"] = results
            e["status"] = "published"
            e["phases"]["published"] = _now()
            self.db.save_election(e)
            state.update(state="done", label="Result published", eta=0, finished=_now())
            push(True)
            log("Result published: " + ", ".join("%s %d" % (c["name"], counts[i]) for i, c in enumerate(e["candidates"])))
            self.db.event(eid, "tally", "published", "Result published after %d masked openings; every proof verified" % st["batches"]
                          if verified else "Result published with verification failures")
        except Exception as ex:
            traceback.print_exc()
            state.update(state="failed", error=str(ex), label="Tally failed")
            push(True)
            log("Tally failed: %s" % ex, "error")
            e = self.require(eid)
            e["status"] = "closed"
            self.db.save_election(e)
            self.db.run("UPDATE trustees SET approved=NULL WHERE election=?", (eid,))

    def audit(self, eid):
        e = self.require(eid)
        t0 = time.perf_counter()
        checks = []
        if not e.get("key"):
            return dict(ok=False, checks=[dict(name="Key ceremony", ok=False, detail="not finished")], seconds=0)
        V = self.scheme(eid)
        checks.append(dict(name="Joint public key", ok=True,
                           detail="%d-of-%d threshold key, fingerprint %s" % (e["threshold"], e["n_trustees"], e["key"]["fingerprint"][:16])))
        digests = self._roster_digests(eid)
        recomputed = []
        for i in range(len(digests)):
            d = np.load(self.edir(eid, "roster", "%d.npz" % i))
            recomputed.append(shake256(DS_ROSTER, bytes.fromhex(e["uid"]), i,
                                       ser(d["u"].astype(np.int64), d["w"].astype(np.int64))).hex())
        reg = e.get("registrar") or {}
        root = shake256(DS_ROSTER, bytes.fromhex(e["uid"]), *[bytes.fromhex(d) for d in digests])
        sig_ok = bool(len(digests) == 0 or (reg.get("signature") and root.hex() == reg.get("root") and
                                             Sig.verify(bytes.fromhex(reg["pk"]), root, bytes.fromhex(reg["signature"]))))
        checks.append(dict(name="Encrypted roster", ok=recomputed == digests and sig_ok,
                           detail="%d %s, digests recomputed, ML-DSA-65 signature %s" % (len(digests), "entry" if len(digests) == 1 else "entries", "valid" if sig_ok else "INVALID")))
        rows = self.db.all("SELECT position, tracker, ukey FROM ballots WHERE election=? ORDER BY position", (eid,))
        good, keys = 0, set()
        for r in rows:
            b = self._load_ballot(eid, r["position"])
            if V.check_ballot(b) and V.tracker(b).hex() == r["tracker"] and V.ballot_key(b).hex() == r["ukey"]:
                good += 1
            keys.add(r["ukey"])
        checks.append(dict(name="Bulletin board", ok=good == len(rows) and len(keys) == len(rows),
                           detail="%d of %d %s two valid proofs of knowledge and unique ciphertexts" % (good, len(rows), "ballot carries" if len(rows) == 1 else "ballots carry")))
        used = len(digests) + len(rows)
        checks.append(dict(name="Board capacity", ok=used <= e["capacity"],
                           detail="%d of %d entries used" % (used, e["capacity"])))
        r = e.get("results")
        if r:
            checks.append(dict(name="Cleansing transcript", ok=r["shares_ok"] == r["shares"] and r["masks_ok"] == r["masks"],
                               detail="%d masked openings, %d/%d decryption shares and %d/%d mask proofs verified, digest %s"
                                      % (r["openings"], r["shares_ok"], r["shares"], r["masks_ok"], r["masks"], r["digest"][:16])))
            checks.append(dict(name="Mix-net", ok=all(m["ok"] for m in r["mix"]),
                               detail="; ".join("server %d shuffle proof %s" % (m["server"], "valid" if m["ok"] else "invalid") for m in r["mix"]) or "no ciphertexts"))
            checks.append(dict(name="Threshold decryption", ok=all(d["ok"] for d in r["decryption"]),
                               detail="; ".join("trustee %d shares %s" % (d["trustee"], "valid" if d["ok"] else "invalid") for d in r["decryption"]) or "no ciphertexts"))
            recount = [sum(1 for v in r["votes"] if v == c + 1) for c in range(len(e["candidates"]))]
            checks.append(dict(name="Result", ok=recount == r["counts"] and r["total_valid"] <= min(r["board"], r["roster"]),
                               detail="counts recomputed from the %d decrypted slots: %s" % (len(r["votes"]), recount)))
        else:
            checks.append(dict(name="Result", ok=True, detail="not published yet"))
        return dict(ok=all(c["ok"] for c in checks), checks=checks, seconds=round(time.perf_counter() - t0, 2),
                    audited=_now())

    def track(self, tracker):
        tracker = re.sub(r"[^0-9a-f]", "", (tracker or "").lower())
        if len(tracker) < 8:
            raise ApiError(400, "Enter at least 8 characters of the tracker")
        rows = self.db.all("SELECT election, position, tracker, posted FROM ballots WHERE tracker LIKE ?", (tracker + "%",))
        titles = {x["id"]: (x["title"], x["status"]) for x in self.db.elections()}
        return [dict(election=r["election"], title=titles.get(r["election"], ("", ""))[0],
                     status=titles.get(r["election"], ("", ""))[1], position=r["position"],
                     tracker=r["tracker"], posted=r["posted"]) for r in rows]
