import json
import sqlite3
import threading
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    email TEXT PRIMARY KEY, name TEXT, provider TEXT, created REAL,
    kem_ek BLOB, kem_dk BLOB);
CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY, email TEXT, role TEXT, created REAL);
CREATE TABLE IF NOT EXISTS elections (
    id TEXT PRIMARY KEY, data TEXT, created REAL, updated REAL);
CREATE TABLE IF NOT EXISTS trustees (
    election TEXT, idx INTEGER, email TEXT, name TEXT, status TEXT,
    joined REAL, approved REAL, fingerprint TEXT, commitment TEXT,
    PRIMARY KEY (election, idx));
CREATE TABLE IF NOT EXISTS voters (
    election TEXT, email TEXT, name TEXT, status TEXT, requested REAL,
    issued REAL, opened REAL, roster_pos INTEGER, roster_digest TEXT,
    envelope TEXT, PRIMARY KEY (election, email));
CREATE TABLE IF NOT EXISTS ballots (
    election TEXT, position INTEGER, tracker TEXT, ukey TEXT, posted REAL,
    size_bits INTEGER, verify_ms REAL, channel TEXT,
    PRIMARY KEY (election, position));
CREATE INDEX IF NOT EXISTS ballots_tracker ON ballots (tracker);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT, election TEXT, ts REAL, actor TEXT,
    kind TEXT, message TEXT);
CREATE TABLE IF NOT EXISTS tally (
    election TEXT PRIMARY KEY, data TEXT, updated REAL);
CREATE TABLE IF NOT EXISTS tally_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, election TEXT, ts REAL, level TEXT,
    message TEXT);
"""


class Store:
    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(path, check_same_thread=False, timeout=30)
        self.conn.row_factory = sqlite3.Row
        with self.lock:
            self.conn.execute("PRAGMA journal_mode=WAL")
            self.conn.execute("PRAGMA synchronous=NORMAL")
            self.conn.executescript(SCHEMA)
            self.conn.commit()

    def all(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.conn.execute(sql, args).fetchall()]

    def one(self, sql, args=()):
        with self.lock:
            r = self.conn.execute(sql, args).fetchone()
            return dict(r) if r else None

    def run(self, sql, args=()):
        with self.lock:
            cur = self.conn.execute(sql, args)
            self.conn.commit()
            return cur.lastrowid

    def many(self, statements):
        with self.lock:
            for sql, args in statements:
                self.conn.execute(sql, args)
            self.conn.commit()

    def election(self, eid):
        r = self.one("SELECT data FROM elections WHERE id=?", (eid,))
        return json.loads(r["data"]) if r else None

    def elections(self):
        return [json.loads(r["data"]) for r in
                self.all("SELECT data FROM elections ORDER BY created DESC")]

    def save_election(self, data):
        now = time.time()
        self.run("INSERT INTO elections (id, data, created, updated) VALUES (?, ?, ?, ?) "
                 "ON CONFLICT(id) DO UPDATE SET data=excluded.data, updated=excluded.updated",
                 (data["id"], json.dumps(data), data.get("created", now), now))

    def event(self, eid, actor, kind, message):
        self.run("INSERT INTO events (election, ts, actor, kind, message) VALUES (?, ?, ?, ?, ?)",
                 (eid, time.time(), actor, kind, message))

    def tally(self, eid):
        r = self.one("SELECT data FROM tally WHERE election=?", (eid,))
        return json.loads(r["data"]) if r else None

    def save_tally(self, eid, data):
        self.run("INSERT INTO tally (election, data, updated) VALUES (?, ?, ?) "
                 "ON CONFLICT(election) DO UPDATE SET data=excluded.data, updated=excluded.updated",
                 (eid, json.dumps(data), time.time()))

    def tally_log(self, eid, message, level="info"):
        self.run("INSERT INTO tally_log (election, ts, level, message) VALUES (?, ?, ?, ?)",
                 (eid, time.time(), level, message))

    def close(self):
        with self.lock:
            self.conn.close()
