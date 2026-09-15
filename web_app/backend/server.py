import json
import mimetypes
import os
import re
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .service import ApiError

MAX_BODY = 8 * 1024 * 1024


class Router:
    def __init__(self):
        self.routes = []

    def add(self, method, pattern, fn):
        self.routes.append((method, re.compile("^" + pattern + "$"), fn))

    def match(self, method, path):
        allowed = False
        for m, rx, fn in self.routes:
            g = rx.match(path)
            if g:
                if m == method:
                    return fn, g.groupdict()
                allowed = True
        return (None, "method") if allowed else (None, None)


def build_router(svc):
    r = Router()
    E = r"/api/elections/(?P<eid>[a-z0-9\-]+)"

    r.add("GET", r"/api/system", lambda a, b, q: svc.system())
    r.add("POST", r"/api/auth/login", lambda a, b, q: svc.login(b))
    r.add("POST", r"/api/auth/logout", lambda a, b, q: svc.logout(a["token"]) if a else dict(ok=True))
    r.add("GET", r"/api/auth/me", lambda a, b, q: a["user"] if a else _unauth())
    r.add("GET", r"/api/activity", lambda a, b, q: svc.activity())
    r.add("GET", r"/api/elections", lambda a, b, q: svc.list_elections(a))
    r.add("POST", r"/api/elections", lambda a, b, q: svc.create_election(a, b))
    r.add("GET", E, lambda a, b, q, eid: svc.election_view(eid, a))
    r.add("DELETE", E, lambda a, b, q, eid: svc.delete_election(a, eid))
    r.add("POST", E + r"/voting/open", lambda a, b, q, eid: svc.open_voting(a, eid))
    r.add("POST", E + r"/voting/close", lambda a, b, q, eid: svc.close_voting(a, eid))
    r.add("POST", E + r"/ceremony/auto", lambda a, b, q, eid: svc.auto_ceremony(a, eid))
    r.add("POST", E + r"/trustees/(?P<idx>[0-9]+)/join",
          lambda a, b, q, eid, idx: svc.join_trustee(a, eid, int(idx)))
    r.add("POST", E + r"/trustees/(?P<idx>[0-9]+)/approve",
          lambda a, b, q, eid, idx: svc.approve(a, eid, int(idx)))
    r.add("POST", E + r"/tally/auto", lambda a, b, q, eid: svc.auto_approve(a, eid))
    r.add("GET", E + r"/tally", lambda a, b, q, eid: svc.tally_status(eid, (q.get("since") or ["0"])[0]))
    r.add("GET", E + r"/voters", lambda a, b, q, eid: svc.voters(a, eid))
    r.add("POST", E + r"/voters", lambda a, b, q, eid: svc.add_voters(a, eid, b))
    r.add("POST", E + r"/voters/issue",
          lambda a, b, q, eid: svc.issue_all(a, eid) if b.get("all") else svc.issue(a, eid, b.get("email") or ""))
    r.add("GET", E + r"/credential", lambda a, b, q, eid: svc.my_credential(a, eid))
    r.add("POST", E + r"/credential/request", lambda a, b, q, eid: svc.request_credential(a, eid))
    r.add("POST", E + r"/credential/open", lambda a, b, q, eid: svc.open_envelope(a, eid))
    r.add("GET", E + r"/public-key", lambda a, b, q, eid: svc.public_key(eid))
    r.add("POST", E + r"/ballots", lambda a, b, q, eid: svc.cast(eid, b))
    r.add("POST", E + r"/ballots/assisted",
          lambda a, b, q, eid: svc.cast_with_credential(eid, b.get("credential"), b.get("choice")))
    r.add("POST", E + r"/decoys", lambda a, b, q, eid: svc.inject_decoys(a, eid, b))
    r.add("GET", E + r"/board", lambda a, b, q, eid: svc.board(eid))
    r.add("GET", E + r"/board/(?P<pos>[0-9]+)", lambda a, b, q, eid, pos: svc.ballot_detail(eid, int(pos)))
    r.add("POST", E + r"/board/(?P<pos>[0-9]+)/verify", lambda a, b, q, eid, pos: svc.verify_ballot(eid, int(pos)))
    r.add("GET", E + r"/roster", lambda a, b, q, eid: svc.roster(a, eid))
    r.add("GET", E + r"/events", lambda a, b, q, eid: svc.events(eid))
    r.add("POST", E + r"/audit", lambda a, b, q, eid: svc.audit(eid))
    r.add("GET", r"/api/track/(?P<tracker>[0-9a-fA-F]+)", lambda a, b, q, tracker: svc.track(tracker))
    return r


def _unauth():
    raise ApiError(401, "Not signed in")


def make_handler(svc, router, static_dir):
    class Handler(BaseHTTPRequestHandler):
        server_version = "Oylama/1.0"
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            if os.environ.get("OYLAMA_ACCESS_LOG"):
                sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

        def _cors(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")

        def _send(self, status, payload, ctype="application/json; charset=utf-8", extra=None):
            body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self._cors()
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def do_OPTIONS(self):
            self.send_response(204)
            self._cors()
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            self._dispatch("GET")

        def do_HEAD(self):
            self._dispatch("GET")

        def do_POST(self):
            self._dispatch("POST")

        def do_DELETE(self):
            self._dispatch("DELETE")

        def _actor(self):
            h = self.headers.get("Authorization") or ""
            token = h[7:].strip() if h.lower().startswith("bearer ") else None
            return svc.session(token)

        def _dispatch(self, method):
            url = urlparse(self.path)
            path = url.path
            if not path.startswith("/api/"):
                return self._static(path)
            try:
                fn, params = router.match(method, path)
                if fn is None:
                    raise ApiError(405 if params == "method" else 404, "No such endpoint")
                body = {}
                length = int(self.headers.get("Content-Length") or 0)
                if length > MAX_BODY:
                    raise ApiError(413, "Request too large")
                if length:
                    raw = self.rfile.read(length)
                    try:
                        body = json.loads(raw.decode("utf-8")) if raw.strip() else {}
                    except ValueError:
                        raise ApiError(400, "Body must be JSON")
                    if not isinstance(body, dict):
                        raise ApiError(400, "Body must be a JSON object")
                result = fn(self._actor(), body, parse_qs(url.query), **params)
                self._send(200, result)
            except ApiError as ex:
                self._send(ex.status, dict(error=ex.message))
            except Exception as ex:
                traceback.print_exc()
                self._send(500, dict(error="Internal error: %s" % ex))

        def _static(self, path):
            rel = os.path.normpath(path.lstrip("/")) if path not in ("", "/") else "index.html"
            if rel.startswith(".."):
                return self._send(404, b"not found", "text/plain")
            full = os.path.join(static_dir, rel)
            if not os.path.isfile(full):
                full = os.path.join(static_dir, "index.html")
            ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
            if full.endswith(".js"):
                ctype = "text/javascript"
            with open(full, "rb") as f:
                data = f.read()
            if ctype.startswith("text/") or ctype in ("application/json", "image/svg+xml"):
                ctype += "; charset=utf-8"
            self._send(200, data, ctype, {"Cache-Control": "no-cache"})

    return Handler


def serve(svc, host, port, static_dir):
    router = build_router(svc)
    httpd = ThreadingHTTPServer((host, port), make_handler(svc, router, static_dir))
    httpd.daemon_threads = True
    return httpd
