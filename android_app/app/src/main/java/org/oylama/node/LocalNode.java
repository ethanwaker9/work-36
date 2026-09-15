package org.oylama.node;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.oylama.crypto.Ballot;
import org.oylama.crypto.BallotJson;
import org.oylama.crypto.Codec;
import org.oylama.crypto.Evaluator;
import org.oylama.crypto.Flat;
import org.oylama.crypto.Hash;
import org.oylama.crypto.Oylama;
import org.oylama.crypto.PackedBgv;
import org.oylama.crypto.Params;
import org.oylama.crypto.Pq;
import org.oylama.crypto.ThresholdKey;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalNode {
    public interface TallyListener {
        void started(String eid, String title);

        void progress(String eid, int done, int total, String label);

        void finished(String eid, boolean ok);
    }

    static final String[] ROLES = {"voter", "authority", "registrar", "trustee", "auditor"};
    static final int[] CAPACITIES = {4, 8, 16};
    static final String[] PALETTE = {"#5B5BD6", "#0EA5E9", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6", "#EC4899", "#14B8A6"};
    static final String[][] DEMO_TRUSTEES = {
        {"ayla.demir@trustees.oylama.demo", "Prof. Ayla Demir"},
        {"kenji.mori@trustees.oylama.demo", "Dr. Kenji Mori"},
        {"maria.santos@trustees.oylama.demo", "Maria Santos"}
    };
    static final String[][] STEPS = {
        {"verify", "Re-verify every ballot proof on the board"},
        {"load", "Load ballots and the encrypted roster into one packed ciphertext"},
        {"sort", "Oblivious Batcher sort by credential and board position"},
        {"dedup", "Encrypted duplicate detection: the last ballot per credential wins"},
        {"roster", "Encrypted roster matching: fake credentials find no partner"},
        {"valid", "Compute and broadcast the encrypted validity bits"},
        {"gate", "Gate the votes of invalid entries to an empty vote"},
        {"extract", "Extract every encrypted vote slot"},
        {"mix", "Verifiable shuffle by two mix servers"},
        {"decrypt", "Threshold decryption with verified trustee shares"},
        {"count", "Count and publish the result"}
    };
    static final Pattern ELECTION = Pattern.compile("^/api/elections/([a-z0-9\\-]+)(/.*)?$");
    static final Pattern TRACK = Pattern.compile("^/api/track/([0-9a-fA-F]+)$");
    static final Pattern SEAT = Pattern.compile("^/trustees/([0-9]+)/(join|approve)$");
    static final Pattern BOARD = Pattern.compile("^/board/([0-9]+)(/verify)?$");
    static final SecureRandom RNG = new SecureRandom();

    private final File dir;
    private final File stateFile;
    private JSONObject root;
    private final Map<String, Oylama> schemes = new HashMap<>();
    private volatile Params params;
    private volatile TallyListener listener;
    private long lastSave;

    public LocalNode(File dir) {
        this.dir = dir;
        this.stateFile = new File(dir, "state.json");
        dir.mkdirs();
        try {
            root = stateFile.exists() ? new JSONObject(Bin.readText(stateFile)) : new JSONObject();
        } catch (Exception e) {
            root = new JSONObject();
        }
        for (String k : new String[]{"users", "sessions"}) {
            if (J.o(root, k) == null) {
                J.put(root, k, new JSONObject());
            }
        }
        if (root.optJSONArray("elections") == null) {
            J.put(root, "elections", new JSONArray());
        }
        if (J.isNull(root, "seconds_per_opening")) {
            J.put(root, "seconds_per_opening", 0.45);
        }
        recover();
    }

    public void setListener(TallyListener l) {
        listener = l;
    }

    Params pp() {
        if (params == null) {
            params = Params.standard();
        }
        return params;
    }

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        return Hash.hex(b);
    }

    synchronized void save(boolean force) {
        long t = System.currentTimeMillis();
        if (!force && t - lastSave < 1500) {
            return;
        }
        lastSave = t;
        try {
            File tmp = new File(dir, "state.json.tmp");
            Bin.writeBytes(tmp, root.toString().getBytes("UTF-8"));
            if (!tmp.renameTo(stateFile)) {
                Bin.writeText(stateFile, root.toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    File edir(String eid, String... parts) {
        File f = new File(new File(dir, "elections"), eid);
        for (String p : parts) {
            f = new File(f, p);
        }
        return f;
    }

    static String prettyName(String email) {
        String local = email.split("@")[0];
        StringBuilder sb = new StringBuilder();
        for (String p : local.split("[._\\-+]+")) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? email : sb.toString();
    }

    static String normalizeEmail(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return "";
        }
        if (!t.contains("@")) {
            t = t.replaceAll("[^a-z0-9._-]", "");
            if (t.isEmpty()) {
                t = "guest";
            }
            t = t + "@oylama.demo";
        }
        return t;
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String[] parts = email.split("@", 2);
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0].charAt(0));
        for (int i = 0; i < Math.max(2, parts[0].length() - 1); i++) {
            sb.append('•');
        }
        return sb.append('@').append(parts[1]).toString();
    }

    static String phaseLabel(String phase, int nb, int nv) {
        String[] parts = phase.split(":");
        switch (parts[0]) {
            case "load":
                return "Loading entry " + (Integer.parseInt(parts[1]) + 1) + " of " + Math.max(1, nb + nv) + " into the packed ciphertext";
            case "replicate":
                return "Replicating the entry layout across slot copies";
            case "sort":
                return "Batcher sorting network, stage " + parts[1] + " of " + parts[2] + " (masked compare and swap)";
            case "dedup":
                return "Detecting repeated credentials so only the last ballot survives";
            case "roster":
                return "Matching ballots against the encrypted roster";
            case "valid":
                return "Computing and broadcasting the validity bits";
            case "gate":
                return "Gating the votes of invalid entries";
            case "extract":
                return "Extracting encrypted vote slot " + parts[1] + " of " + parts[2];
            default:
                return phase;
        }
    }

    public synchronized Object call(String method, String path, JSONObject body, String token) throws ApiException {
        if (body == null) {
            body = new JSONObject();
        }
        int since = 0;
        int qi = path.indexOf('?');
        if (qi >= 0) {
            for (String kv : path.substring(qi + 1).split("&")) {
                if (kv.startsWith("since=")) {
                    try {
                        since = Integer.parseInt(kv.substring(6));
                    } catch (NumberFormatException ignored) {
                        since = 0;
                    }
                }
            }
            path = path.substring(0, qi);
        }
        JSONObject actor = session(token);
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        try {
            switch (path) {
                case "/api/system":
                    return system();
                case "/api/auth/login":
                    return login(body);
                case "/api/auth/logout":
                    if (token != null) {
                        J.o(root, "sessions").remove(token);
                        save(true);
                    }
                    return J.obj("ok", true);
                case "/api/auth/me":
                    if (actor == null) {
                        throw new ApiException(401, "Not signed in");
                    }
                    return J.o(actor, "user");
                case "/api/activity":
                    return activity();
                case "/api/elections":
                    if (get) {
                        return listElections(actor);
                    }
                    return createElection(actor, body);
                default:
                    break;
            }
            Matcher tm = TRACK.matcher(path);
            if (tm.matches()) {
                return track(tm.group(1));
            }
            Matcher m = ELECTION.matcher(path);
            if (!m.matches()) {
                throw new ApiException(404, "No such endpoint");
            }
            String eid = m.group(1);
            String rest = m.group(2) == null ? "" : m.group(2);
            if (rest.isEmpty()) {
                if (get) {
                    return view(eid, actor);
                }
                if (method.equals("DELETE")) {
                    return deleteElection(actor, eid);
                }
            }
            Matcher sm = SEAT.matcher(rest);
            if (post && sm.matches()) {
                int idx = Integer.parseInt(sm.group(1));
                return sm.group(2).equals("join") ? joinTrustee(actor, eid, idx, false) : approve(actor, eid, idx, false);
            }
            Matcher bm = BOARD.matcher(rest);
            if (bm.matches()) {
                int pos = Integer.parseInt(bm.group(1));
                return bm.group(2) == null ? ballotDetail(eid, pos) : verifyBallot(eid, pos);
            }
            switch (rest) {
                case "/voting/open":
                    return openVoting(actor, eid);
                case "/voting/close":
                    return closeVoting(actor, eid);
                case "/ceremony/auto":
                    return autoCeremony(actor, eid);
                case "/tally/auto":
                    return autoApprove(actor, eid);
                case "/tally":
                    return tallyStatus(eid, since);
                case "/voters":
                    return get ? voters(actor, eid) : addVoters(actor, eid, body);
                case "/voters/issue":
                    if (J.bool(body, "all", false)) {
                        return issueAll(actor, eid);
                    }
                    return issue(actor, eid, J.str(body, "email"));
                case "/credential":
                    return myCredential(actor, eid);
                case "/credential/request":
                    return requestCredential(actor, eid);
                case "/credential/open":
                    return openEnvelope(actor, eid);
                case "/public-key":
                    return publicKey(eid);
                case "/ballots":
                    return cast(eid, body.has("ballot") ? body.getJSONObject("ballot") : body, "anonymous");
                case "/ballots/assisted":
                    return castWithCredential(eid, J.str(body, "credential"), J.num(body, "choice", 0), "assisted");
                case "/decoys":
                    return injectDecoys(actor, eid, body);
                case "/board":
                    return board(eid);
                case "/roster":
                    return roster(actor, eid);
                case "/events":
                    return events(eid);
                case "/audit":
                    return audit(eid);
                default:
                    throw new ApiException(404, "No such endpoint");
            }
        } catch (JSONException | IllegalArgumentException e) {
            throw new ApiException(400, "Malformed request: " + e.getMessage());
        }
    }

    JSONObject system() {
        Params p = pp();
        return J.obj("name", "Oylama", "backend", "on-device node · BouncyCastle", "ring_degree", p.N,
            "primes", primesJson(), "prime_bits", 24, "plaintext_modulus", p.p, "credential_bits", p.lam,
            "challenge_weight", p.kappa, "flooding_bits", p.sec, "mix_servers", p.nMix, "trustees", p.nTrustee,
            "threshold", p.threshold, "capacities", new JSONArray(Arrays.asList(4, 8, 16)), "signature", "ML-DSA-65",
            "kem", "ML-KEM-768", "aead", "AES-256-GCM", "xof", "SHAKE256", "roles", new JSONArray(Arrays.asList(ROLES)),
            "seconds_per_opening", J.dbl(root, "seconds_per_opening", 0.45));
    }

    JSONArray primesJson() {
        JSONArray a = new JSONArray();
        for (int q : pp().primes) {
            a.put(q);
        }
        return a;
    }

    JSONObject user(String email) {
        return J.o(J.o(root, "users"), email);
    }

    JSONObject ensureUser(String email, String name, String provider) {
        JSONObject u = user(email);
        if (u != null) {
            return u;
        }
        byte[][] kem = Pq.kemKeygen();
        u = J.obj("email", email, "name", name == null || name.isEmpty() ? prettyName(email) : name, "provider",
            provider == null ? "password" : provider, "created", now(), "kem_ek", Codec.b64(kem[0]), "kem_dk", Codec.b64(kem[1]));
        J.put(J.o(root, "users"), email, u);
        return u;
    }

    JSONObject publicUser(JSONObject u, String role) {
        String dk = Hash.hex(Hash.h(Codec.unb64(J.str(u, "kem_ek")))).substring(0, 24);
        return J.obj("email", J.str(u, "email"), "name", J.str(u, "name"), "provider", J.str(u, "provider"), "role", role, "device_key", dk);
    }

    JSONObject login(JSONObject body) {
        String email = normalizeEmail(J.str(body, "email"));
        if (email.isEmpty()) {
            email = "guest-" + hex(3) + "@oylama.demo";
        }
        String role = J.str(body, "role");
        if (!Arrays.asList(ROLES).contains(role)) {
            role = "voter";
        }
        String provider = J.str(body, "provider").isEmpty() ? "password" : J.str(body, "provider");
        String name = J.str(body, "name").trim().isEmpty() ? prettyName(email) : J.str(body, "name").trim();
        JSONObject u = ensureUser(email, name, provider);
        String token = hex(24);
        J.put(J.o(root, "sessions"), token, J.obj("email", email, "role", role, "created", now()));
        save(true);
        return J.obj("token", token, "user", publicUser(u, role));
    }

    JSONObject session(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        JSONObject s = J.o(J.o(root, "sessions"), token);
        if (s == null) {
            return null;
        }
        JSONObject u = user(J.str(s, "email"));
        if (u == null) {
            return null;
        }
        return J.obj("token", token, "role", J.str(s, "role"), "email", J.str(u, "email"), "name", J.str(u, "name"),
            "user", publicUser(u, J.str(s, "role")));
    }

    void need(JSONObject actor, String... roles) throws ApiException {
        if (actor == null) {
            throw new ApiException(401, "Please sign in first");
        }
        if (roles.length > 0 && !Arrays.asList(roles).contains(J.str(actor, "role"))) {
            throw new ApiException(403, "This action needs the " + String.join(" or ", roles) + " role");
        }
    }

    JSONObject find(String eid) throws ApiException {
        JSONArray els = root.optJSONArray("elections");
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.optJSONObject(i);
            if (e != null && J.str(e, "id").equals(eid)) {
                return e;
            }
        }
        throw new ApiException(404, "Election not found");
    }

    void event(JSONObject e, String actor, String kind, String message) {
        JSONArray ev = J.a(e, "events");
        ev.put(J.obj("ts", now(), "actor", actor, "kind", kind, "message", message));
        J.put(e, "events", ev);
    }

    static List<JSONObject> trustees(JSONObject e) {
        return J.list(J.a(e, "trustees"));
    }

    static List<JSONObject> votersOf(JSONObject e) {
        return J.list(J.a(e, "voters"));
    }

    static JSONObject voter(JSONObject e, String email) {
        for (JSONObject v : votersOf(e)) {
            if (J.str(v, "email").equals(email)) {
                return v;
            }
        }
        return null;
    }

    static int rosterCount(JSONObject e) {
        int n = 0;
        for (JSONObject v : votersOf(e)) {
            if (!J.isNull(v, "roster_pos")) {
                n++;
            }
        }
        return n;
    }

    static int ballotCount(JSONObject e) {
        return J.a(e, "ballots").length();
    }

    Oylama scheme(String eid) throws ApiException {
        Oylama V = schemes.get(eid);
        if (V != null) {
            return V;
        }
        JSONObject e = find(eid);
        File pk = edir(eid, "pk.bin");
        if (!pk.exists()) {
            throw new ApiException(409, "The key ceremony has not finished yet");
        }
        try {
            V = new Oylama(Hash.unhex(J.str(e, "uid")), J.num(e, "capacity", 8), J.a(e, "candidates").length(), pp());
            V.attach(Bin.readArrays(pk));
        } catch (IOException ex) {
            throw new ApiException(500, "Key material unavailable");
        }
        schemes.put(eid, V);
        return V;
    }

    JSONObject summary(JSONObject e, JSONObject actor) {
        int roster = rosterCount(e);
        int ballots = ballotCount(e);
        int requested = 0;
        for (JSONObject v : votersOf(e)) {
            if (J.str(v, "status").equals("requested")) {
                requested++;
            }
        }
        int joined = 0, approvals = 0;
        for (JSONObject t : trustees(e)) {
            if (J.str(t, "status").equals("joined")) {
                joined++;
            }
            if (!J.isNull(t, "approved")) {
                approvals++;
            }
        }
        JSONObject t = J.o(e, "tally");
        Object tally = t == null ? JSONObject.NULL : J.obj("state", t.opt("state"), "done", t.opt("done"), "total", t.opt("total"),
            "eta", t.opt("eta"), "label", t.opt("label"));
        JSONObject r = J.o(e, "results");
        Object results = r == null ? JSONObject.NULL : J.obj("counts", r.opt("counts"), "total_valid", r.opt("total_valid"),
            "published", r.opt("published"), "verified", r.opt("verified"));
        Object me = JSONObject.NULL;
        if (actor != null) {
            JSONObject v = voter(e, J.str(actor, "email"));
            Object vv = v == null ? JSONObject.NULL : J.obj("status", v.opt("status"), "roster_pos", v.opt("roster_pos"),
                "issued", v.opt("issued"), "opened", v.opt("opened"), "requested", v.opt("requested"));
            JSONArray seats = new JSONArray();
            for (JSONObject tr : trustees(e)) {
                if (J.str(tr, "email").equals(J.str(actor, "email"))) {
                    seats.put(J.num(tr, "idx", 0));
                }
            }
            me = J.obj("voter", vv, "seats", seats);
        }
        return J.obj("id", e.opt("id"), "title", e.opt("title"), "organization", e.opt("organization"), "description", e.opt("description"),
            "status", e.opt("status"), "capacity", e.opt("capacity"), "candidates", e.opt("candidates"), "created", e.opt("created"),
            "phases", e.opt("phases"), "opens_at", e.opt("opens_at"), "closes_at", e.opt("closes_at"), "eligibility", e.opt("eligibility"),
            "used", roster + ballots, "roster", roster, "ballots", ballots, "requested", requested, "trustees_joined", joined,
            "approvals", approvals, "threshold", e.opt("threshold"), "n_trustees", e.opt("n_trustees"), "tally", tally,
            "results", results, "me", me);
    }

    JSONArray listElections(JSONObject actor) {
        JSONArray out = new JSONArray();
        JSONArray els = root.optJSONArray("elections");
        for (int i = els.length() - 1; i >= 0; i--) {
            out.put(summary(els.optJSONObject(i), actor));
        }
        return out;
    }

    JSONObject view(String eid, JSONObject actor) throws ApiException {
        JSONObject e = find(eid);
        JSONObject out = summary(e, actor);
        Params p = pp();
        int cap = J.num(e, "capacity", 8);
        int mbits = Math.max(2, 32 - Integer.numberOfLeadingZeros(cap));
        int vbits = Math.max(1, 32 - Integer.numberOfLeadingZeros(J.a(e, "candidates").length()));
        int planTotal = cap == 4 ? 206 : cap == 8 ? 342 : 529;
        int nb = ballotCount(e), nv = rosterCount(e);
        if (!J.isNull(e, "key") && nb + nv > 0) {
            planTotal = scheme(eid).plan(nb, nv).total;
        }
        double spo = J.dbl(root, "seconds_per_opening", 0.45);
        boolean full = actor != null && (J.str(actor, "role").equals("authority") || J.str(actor, "role").equals("trustee"));
        JSONArray tr = new JSONArray();
        for (JSONObject t : trustees(e)) {
            String em = J.strOrNull(t, "email");
            tr.put(J.obj("idx", t.opt("idx"), "seat", J.num(t, "idx", 0) + 1, "email", full ? em : maskEmail(em), "name", t.opt("name"),
                "status", t.opt("status"), "joined", t.opt("joined"), "approved", t.opt("approved"), "fingerprint", t.opt("fingerprint")));
        }
        JSONObject reg = J.o(e, "registrar");
        JSONObject regPub = new JSONObject();
        if (reg != null) {
            JSONArray names = reg.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String k = names.optString(i);
                if (!k.equals("pk")) {
                    J.put(regPub, k, reg.opt(k));
                }
            }
        }
        JSONArray primes = primesJson();
        J.put(out, "trustees", tr);
        J.put(out, "key", e.opt("key"));
        J.put(out, "uid", e.opt("uid"));
        J.put(out, "auto_issue", e.opt("auto_issue"));
        J.put(out, "decoys", actor != null && J.str(actor, "role").equals("authority") ? e.opt("decoys") : null);
        J.put(out, "registrar", regPub);
        J.put(out, "params", J.obj("ring_degree", p.N, "slots", p.N, "primes", primes, "log_q", 125, "plaintext_modulus", p.p,
            "credential_bits", p.lam, "index_bits", mbits, "vote_bits", vbits, "bit_blocks", 256, "challenge_weight", p.kappa,
            "mix_servers", p.nMix, "threshold", p.threshold + " of " + p.nTrustee, "ballot_kb", 688,
            "estimated_tally_seconds", (int) (planTotal * spo + 8), "backend", "on-device"));
        J.put(out, "results_full", e.opt("results"));
        J.put(out, "created_by", e.opt("created_by"));
        return out;
    }

    JSONObject createElection(JSONObject actor, JSONObject body) throws ApiException {
        need(actor, "authority");
        String title = J.str(body, "title").trim();
        if (title.isEmpty()) {
            throw new ApiException(400, "An election needs a title");
        }
        JSONArray cands = new JSONArray();
        JSONArray in = J.a(body, "candidates");
        for (int i = 0; i < in.length(); i++) {
            Object c = in.opt(i);
            JSONObject co = c instanceof JSONObject ? (JSONObject) c : J.obj("name", String.valueOf(c));
            String name = J.str(co, "name").trim();
            if (name.isEmpty()) {
                continue;
            }
            int idx = cands.length() + 1;
            String color = J.str(co, "color").isEmpty() ? PALETTE[(idx - 1) % PALETTE.length] : J.str(co, "color");
            cands.put(J.obj("idx", idx, "name", trim(name, 80), "party", trim(J.str(co, "party").trim(), 80),
                "bio", trim(J.str(co, "bio").trim(), 400), "color", color));
        }
        if (cands.length() < 2) {
            throw new ApiException(400, "Add at least two candidates or options");
        }
        if (cands.length() > 8) {
            throw new ApiException(400, "Oylama ballots here support up to eight options");
        }
        int capacity = J.num(body, "capacity", 8);
        if (capacity != 4 && capacity != 8 && capacity != 16) {
            throw new ApiException(400, "Board capacity must be one of 4, 8 or 16 entries");
        }
        String eid = "el-" + hex(4);
        double t = now();
        JSONArray seats = J.a(body, "trustees");
        JSONArray tr = new JSONArray();
        for (int j = 0; j < pp().nTrustee; j++) {
            JSONObject s = j < seats.length() ? seats.optJSONObject(j) : null;
            String em = s == null ? "" : normalizeEmail(J.str(s, "email"));
            tr.put(J.obj("idx", j, "email", em.isEmpty() ? null : em, "name", em.isEmpty() ? null : prettyName(em), "status", "open",
                "joined", null, "approved", null, "fingerprint", null));
        }
        JSONArray vs = new JSONArray();
        for (String em : emails(body.opt("voters"))) {
            vs.put(J.obj("email", em, "name", prettyName(em), "status", "eligible"));
        }
        JSONObject e = J.obj("id", eid, "uid", hex(16), "title", trim(title, 140), "description", trim(J.str(body, "description").trim(), 2000),
            "organization", trim(J.str(body, "organization").trim(), 120), "candidates", cands, "capacity", capacity,
            "n_trustees", pp().nTrustee, "threshold", pp().threshold,
            "eligibility", J.str(body, "eligibility").equals("list") ? "list" : "open", "auto_issue", J.bool(body, "auto_issue", true),
            "status", "ceremony", "created", t, "created_by", J.str(actor, "email"), "opens_at", J.str(body, "opens_at"),
            "closes_at", J.str(body, "closes_at"), "phases", J.obj("ceremony", t), "results", null, "decoys", 0, "key", null,
            "registrar", null, "trustees", tr, "voters", vs, "ballots", new JSONArray(), "events", new JSONArray(), "tally", null,
            "tally_log", new JSONArray(), "log_seq", 0);
        event(e, J.str(actor, "email"), "created", "Election created with " + cands.length() + " options and a board of " + capacity + " entries");
        root.optJSONArray("elections").put(e);
        save(true);
        return view(eid, actor);
    }

    static String trim(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    static List<String> emails(Object value) {
        List<String> out = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                raw.add(a.optString(i));
            }
        } else if (value != null && value != JSONObject.NULL) {
            raw.addAll(Arrays.asList(String.valueOf(value).split("[\\s,;]+")));
        }
        for (String r : raw) {
            String em = normalizeEmail(r);
            if (!em.isEmpty() && !out.contains(em)) {
                out.add(em);
            }
        }
        return out;
    }

    JSONObject deleteElection(JSONObject actor, String eid) throws ApiException {
        need(actor, "authority");
        JSONObject e = find(eid);
        if (J.str(e, "status").equals("tallying")) {
            throw new ApiException(409, "Wait until the tally has finished");
        }
        JSONArray els = root.optJSONArray("elections");
        JSONArray keep = new JSONArray();
        for (int i = 0; i < els.length(); i++) {
            if (!J.str(els.optJSONObject(i), "id").equals(eid)) {
                keep.put(els.optJSONObject(i));
            }
        }
        J.put(root, "elections", keep);
        schemes.remove(eid);
        Bin.deleteTree(edir(eid));
        save(true);
        return J.obj("ok", true);
    }

    JSONObject openVoting(JSONObject actor, String eid) throws ApiException {
        need(actor, "authority");
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("registration")) {
            throw new ApiException(409, "Voting can be opened once the key ceremony is done");
        }
        J.put(e, "status", "voting");
        J.put(J.o(e, "phases"), "voting", now());
        event(e, J.str(actor, "email"), "voting", "Voting opened: the bulletin board accepts anonymous ballots");
        save(true);
        return view(eid, actor);
    }

    JSONObject closeVoting(JSONObject actor, String eid) throws ApiException {
        need(actor, "authority");
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("voting")) {
            throw new ApiException(409, "Voting is not open");
        }
        J.put(e, "status", "closed");
        J.put(J.o(e, "phases"), "closed", now());
        event(e, J.str(actor, "email"), "closed", "Voting closed: waiting for " + J.num(e, "threshold", 2) + " trustees to approve the tally");
        save(true);
        return view(eid, actor);
    }

    JSONObject joinTrustee(JSONObject actor, String eid, int idx, boolean simulated) throws ApiException {
        if (!simulated) {
            need(actor, "trustee");
        }
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("ceremony")) {
            throw new ApiException(409, "The key ceremony is already complete");
        }
        List<JSONObject> seats = trustees(e);
        if (idx < 0 || idx >= seats.size()) {
            throw new ApiException(404, "No such trustee seat");
        }
        JSONObject seat = seats.get(idx);
        String email = J.str(actor, "email");
        if (J.str(seat, "status").equals("joined")) {
            if (J.str(seat, "email").equals(email)) {
                return view(eid, actor);
            }
            throw new ApiException(409, "Seat " + (idx + 1) + " is already taken");
        }
        if (!J.str(seat, "email").isEmpty() && !J.str(seat, "email").equals(email)) {
            throw new ApiException(403, "Seat " + (idx + 1) + " is reserved for " + J.str(seat, "email"));
        }
        for (JSONObject s : seats) {
            if (J.str(s, "status").equals("joined") && J.str(s, "email").equals(email)) {
                throw new ApiException(409, "You already hold seat " + (J.num(s, "idx", 0) + 1) + "; every seat needs a different trustee");
            }
        }
        String name = J.str(actor, "name").isEmpty() ? prettyName(email) : J.str(actor, "name");
        J.put(seat, "email", email);
        J.put(seat, "name", name);
        J.put(seat, "status", "joined");
        J.put(seat, "joined", now());
        event(e, email, "trustee", name + " joined the key ceremony as trustee " + (idx + 1));
        boolean all = true;
        for (JSONObject s : seats) {
            all &= J.str(s, "status").equals("joined");
        }
        if (all) {
            ceremony(eid);
        }
        save(true);
        return view(eid, actor);
    }

    JSONObject autoCeremony(JSONObject actor, String eid) throws ApiException {
        need(actor, "authority", "trustee");
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("ceremony")) {
            throw new ApiException(409, "The key ceremony is already complete");
        }
        List<String> taken = new ArrayList<>();
        for (JSONObject s : trustees(e)) {
            if (J.str(s, "status").equals("joined")) {
                taken.add(J.str(s, "email"));
            }
        }
        List<String[]> pool = new ArrayList<>();
        for (String[] t : DEMO_TRUSTEES) {
            if (!taken.contains(t[0])) {
                pool.add(t);
            }
        }
        for (JSONObject s : trustees(e)) {
            if (J.str(s, "status").equals("joined")) {
                continue;
            }
            String em, nm;
            if (!J.str(s, "email").isEmpty()) {
                em = J.str(s, "email");
                nm = J.str(s, "name").isEmpty() ? prettyName(em) : J.str(s, "name");
            } else {
                String[] t = pool.remove(0);
                em = t[0];
                nm = t[1];
            }
            ensureUser(em, nm, "demo");
            joinTrustee(J.obj("email", em, "name", nm, "role", "trustee"), eid, J.num(s, "idx", 0), true);
        }
        return view(eid, actor);
    }

    void ceremony(String eid) throws ApiException {
        JSONObject e = find(eid);
        if (!J.isNull(e, "key")) {
            return;
        }
        long t0 = System.nanoTime();
        Oylama V = new Oylama(Hash.unhex(J.str(e, "uid")), J.num(e, "capacity", 8), J.a(e, "candidates").length(), pp());
        PackedBgv.KeyPair kp = V.keygen(Oylama.random(32));
        V.attach(kp.pk);
        ThresholdKey tk = V.deal(kp.s, Oylama.random(32));
        try {
            Bin.writeArrays(edir(eid, "pk.bin"), kp.pk[0], kp.pk[1]);
            List<JSONObject> seats = trustees(e);
            for (int j = 0; j < pp().nTrustee; j++) {
                Map<String, int[]> hold = tk.holdings(j);
                Bin.writeMap(edir(eid, "trustee_" + j + ".bin"), hold);
                Hash.Stream st = Hash.stream("OYLAMA/share", Hash.unhex(J.str(e, "uid")), j);
                for (String k : new java.util.TreeSet<>(hold.keySet())) {
                    st.add(Hash.ser(hold.get(k)));
                }
                J.put(seats.get(j), "fingerprint", Hash.hex(st.digest(32)).substring(0, 32));
            }
            byte[][] sig = Pq.sigKeygen();
            Bin.writeBytes(edir(eid, "registrar.key"), sig[1]);
            J.put(e, "registrar", J.obj("alg", "ML-DSA-65", "pk", Hash.hex(sig[0]), "pk_fingerprint", Hash.hex(Hash.h(sig[0])).substring(0, 32),
                "root", null, "signature", null, "signed_entries", 0));
        } catch (IOException ex) {
            throw new ApiException(500, "Could not store key material");
        }
        J.put(e, "key", J.obj("fingerprint", Hash.hex(Hash.h(Hash.ser(kp.pk[0], kp.pk[1]))).substring(0, 32), "created", now(),
            "seconds", Math.round((System.nanoTime() - t0) / 1e7) / 100.0, "size_kb", 250.0));
        J.put(e, "status", "registration");
        J.put(J.o(e, "phases"), "registration", now());
        schemes.put(eid, V);
        event(e, "ceremony", "key", "Key ceremony complete: " + J.num(e, "threshold", 2) + "-of-" + J.num(e, "n_trustees", 3)
            + " threshold key over R_q with N=" + pp().N + ", public key " + J.str(J.o(e, "key"), "fingerprint").substring(0, 16));
    }

    Map<String, int[]> holdings(String eid, int j) throws IOException {
        return Bin.readMap(edir(eid, "trustee_" + j + ".bin"));
    }

    JSONArray voters(JSONObject actor, String eid) throws ApiException {
        need(actor, "registrar", "authority");
        JSONObject e = find(eid);
        List<JSONObject> vs = votersOf(e);
        vs.sort((a, b) -> {
            int pa = J.isNull(a, "roster_pos") ? 99999 : J.num(a, "roster_pos", 0);
            int pb = J.isNull(b, "roster_pos") ? 99999 : J.num(b, "roster_pos", 0);
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            return Double.compare(J.dbl(a, "requested", 0), J.dbl(b, "requested", 0));
        });
        JSONArray out = new JSONArray();
        for (JSONObject v : vs) {
            out.put(J.obj("email", v.opt("email"), "name", v.opt("name"), "status", v.opt("status"), "requested", v.opt("requested"),
                "issued", v.opt("issued"), "opened", v.opt("opened"), "roster_pos", v.opt("roster_pos"), "roster_digest", v.opt("roster_digest")));
        }
        return out;
    }

    JSONArray addVoters(JSONObject actor, String eid, JSONObject body) throws ApiException {
        need(actor, "registrar", "authority");
        JSONObject e = find(eid);
        List<String> ems = emails(body.opt("emails"));
        if (ems.isEmpty()) {
            throw new ApiException(400, "Enter at least one email address");
        }
        JSONArray vs = J.a(e, "voters");
        for (String em : ems) {
            if (voter(e, em) == null) {
                vs.put(J.obj("email", em, "name", prettyName(em), "status", "eligible"));
            }
        }
        J.put(e, "voters", vs);
        event(e, J.str(actor, "email"), "eligibility", ems.size() + (ems.size() == 1 ? " voter" : " voters") + " added to the eligibility list");
        if (J.bool(body, "issue", false)) {
            for (String em : ems) {
                issue(actor, eid, em);
            }
        }
        save(true);
        return voters(actor, eid);
    }

    JSONObject requestCredential(JSONObject actor, String eid) throws ApiException {
        need(actor, "voter");
        JSONObject e = find(eid);
        String st = J.str(e, "status");
        if (!st.equals("registration") && !st.equals("voting")) {
            throw new ApiException(409, "Registration is not open for this election");
        }
        String email = J.str(actor, "email");
        JSONObject v = voter(e, email);
        if (J.str(e, "eligibility").equals("list") && v == null) {
            throw new ApiException(403, email + " is not on the eligibility list of this election");
        }
        if (v != null && (J.str(v, "status").equals("issued") || J.str(v, "status").equals("opened"))) {
            return myCredential(actor, eid);
        }
        ensureUser(email, J.str(actor, "name"), "password");
        if (v == null) {
            v = J.obj("email", email, "name", J.str(actor, "name").isEmpty() ? prettyName(email) : J.str(actor, "name"));
            J.a(e, "voters").put(v);
        }
        J.put(v, "status", "requested");
        J.put(v, "requested", now());
        event(e, email, "request", maskEmail(email) + " asked the registrar for a voting credential");
        if (J.bool(e, "auto_issue", true)) {
            issue(J.obj("email", "registrar@oylama.demo", "role", "registrar", "name", "Registrar service"), eid, email);
        }
        save(true);
        return myCredential(actor, eid);
    }

    JSONObject issue(JSONObject actor, String eid, String email) throws ApiException {
        need(actor, "registrar", "authority");
        JSONObject e = find(eid);
        String st = J.str(e, "status");
        if (!st.equals("registration") && !st.equals("voting")) {
            throw new ApiException(409, "Credentials can be issued after the key ceremony and before voting closes");
        }
        email = normalizeEmail(email);
        JSONObject v = voter(e, email);
        if (v != null && (J.str(v, "status").equals("issued") || J.str(v, "status").equals("opened"))) {
            return J.obj("ok", true, "already", true);
        }
        int roster = rosterCount(e), ballots = ballotCount(e);
        if (roster + ballots + 1 > J.num(e, "capacity", 8)) {
            throw new ApiException(409, "The board is full: " + (roster + ballots) + " of " + J.num(e, "capacity", 8) + " entries are used");
        }
        Oylama V = scheme(eid);
        JSONObject u = ensureUser(email, null, "password");
        int[] cred = V.newCredential();
        int pos = roster;
        int[][] ct = V.register(cred, Oylama.random(32));
        String digest;
        try {
            Bin.writeArrays(edir(eid, "roster", pos + ".bin"), ct[0], ct[1]);
        } catch (IOException ex) {
            throw new ApiException(500, "Could not store the roster");
        }
        digest = Hash.hex(Hash.h("OYLAMA/roster", Hash.unhex(J.str(e, "uid")), pos, Hash.ser(ct[0], ct[1])));
        byte[][] enc = Pq.kemEncaps(Codec.unb64(J.str(u, "kem_ek")));
        byte[] aad = (J.str(e, "uid") + "|" + email).getBytes();
        byte[] blob = Pq.seal(Pq.kdf(enc[0], "credential"), Codec.bytesFromBits(cred), aad);
        JSONObject env = J.obj("kem", "ML-KEM-768", "aead", "AES-256-GCM", "kem_ct", Codec.b64(enc[1]), "blob", Codec.b64(blob),
            "sealed", now(), "device_key", Hash.hex(Hash.h(Codec.unb64(J.str(u, "kem_ek")))).substring(0, 24));
        Arrays.fill(cred, 0);
        if (v == null) {
            v = J.obj("email", email, "name", J.str(u, "name"));
            J.a(e, "voters").put(v);
        }
        J.put(v, "status", "issued");
        J.put(v, "issued", now());
        J.put(v, "roster_pos", pos);
        J.put(v, "roster_digest", digest);
        J.put(v, "envelope", env);
        signRoster(e);
        event(e, J.str(actor, "email"), "issued", "Registrar sealed credential for roster entry " + (pos + 1)
            + " with ML-KEM-768 and signed the roster with ML-DSA-65");
        save(true);
        return J.obj("ok", true, "roster_pos", pos, "digest", digest);
    }

    JSONObject issueAll(JSONObject actor, String eid) throws ApiException {
        need(actor, "registrar", "authority");
        JSONObject e = find(eid);
        List<JSONObject> pending = new ArrayList<>();
        for (JSONObject v : votersOf(e)) {
            String s = J.str(v, "status");
            if (s.equals("requested") || s.equals("eligible")) {
                pending.add(v);
            }
        }
        int done = 0;
        for (JSONObject v : pending) {
            try {
                issue(actor, eid, J.str(v, "email"));
                done++;
            } catch (ApiException ex) {
                if (done == 0) {
                    throw ex;
                }
                break;
            }
        }
        return J.obj("issued", done, "voters", voters(actor, eid));
    }

    List<String> rosterDigests(JSONObject e) {
        List<JSONObject> vs = new ArrayList<>();
        for (JSONObject v : votersOf(e)) {
            if (!J.isNull(v, "roster_pos")) {
                vs.add(v);
            }
        }
        vs.sort((a, b) -> Integer.compare(J.num(a, "roster_pos", 0), J.num(b, "roster_pos", 0)));
        List<String> out = new ArrayList<>();
        for (JSONObject v : vs) {
            out.add(J.str(v, "roster_digest"));
        }
        return out;
    }

    byte[] rosterRoot(JSONObject e, List<String> digests) {
        Hash.Stream st = Hash.stream("OYLAMA/roster", Hash.unhex(J.str(e, "uid")));
        for (String d : digests) {
            st.add(Hash.unhex(d));
        }
        return st.digest(32);
    }

    void signRoster(JSONObject e) throws ApiException {
        List<String> digests = rosterDigests(e);
        byte[] rt = rosterRoot(e, digests);
        try {
            byte[] sk = Bin.readBytes(edir(J.str(e, "id"), "registrar.key"));
            byte[] sig = Pq.sign(sk, rt);
            JSONObject reg = J.o(e, "registrar");
            J.put(reg, "root", Hash.hex(rt));
            J.put(reg, "signature", Hash.hex(sig));
            J.put(reg, "signed_entries", digests.size());
            J.put(reg, "signed_at", now());
        } catch (IOException ex) {
            throw new ApiException(500, "Registrar key unavailable");
        }
    }

    JSONObject myCredential(JSONObject actor, String eid) throws ApiException {
        need(actor);
        JSONObject e = find(eid);
        JSONObject v = voter(e, J.str(actor, "email"));
        if (v == null) {
            return J.obj("status", "none");
        }
        JSONObject env = J.o(v, "envelope");
        Object envOut = JSONObject.NULL;
        if (env != null) {
            String ct = J.str(env, "kem_ct");
            envOut = J.obj("kem", env.opt("kem"), "aead", env.opt("aead"), "kem_ct_preview", ct.substring(0, Math.min(48, ct.length())),
                "blob_bytes", Codec.unb64(J.str(env, "blob")).length, "sealed", env.opt("sealed"), "device_key", env.opt("device_key"));
        }
        return J.obj("status", v.opt("status"), "roster_pos", v.opt("roster_pos"), "roster_digest", v.opt("roster_digest"),
            "requested", v.opt("requested"), "issued", v.opt("issued"), "opened", v.opt("opened"), "envelope", envOut);
    }

    JSONObject openEnvelope(JSONObject actor, String eid) throws ApiException {
        need(actor, "voter");
        JSONObject e = find(eid);
        String email = J.str(actor, "email");
        JSONObject v = voter(e, email);
        if (v == null || J.o(v, "envelope") == null) {
            throw new ApiException(404, "The registrar has not sealed a credential for you yet");
        }
        JSONObject u = user(email);
        JSONObject env = J.o(v, "envelope");
        byte[] ss = Pq.kemDecaps(Codec.unb64(J.str(u, "kem_dk")), Codec.unb64(J.str(env, "kem_ct")));
        byte[] aad = (J.str(e, "uid") + "|" + email).getBytes();
        byte[] raw = Pq.unseal(Pq.kdf(ss, "credential"), Codec.unb64(J.str(env, "blob")), aad);
        String text = Codec.credentialText(Codec.bitsFromBytes(raw, pp().lam));
        if (!J.str(v, "status").equals("opened")) {
            J.put(v, "status", "opened");
            J.put(v, "opened", now());
            save(true);
        }
        return J.obj("credential", text, "roster_pos", v.opt("roster_pos"), "roster_digest", v.opt("roster_digest"));
    }

    JSONObject publicKey(String eid) throws ApiException {
        JSONObject e = find(eid);
        Oylama V = scheme(eid);
        JSONArray cands = new JSONArray();
        for (JSONObject c : J.list(J.a(e, "candidates"))) {
            cands.put(J.obj("idx", c.opt("idx"), "name", c.opt("name")));
        }
        return J.obj("election", eid, "uid", e.opt("uid"), "N", V.pp.N, "primes", primesJson(), "p", V.pp.p, "kappa", V.pp.kappa,
            "lam", V.lam, "mmax", V.mmax, "mbits", V.mbits, "vbits", V.vbits, "keybits", V.keybits, "n_cands", V.nCands,
            "sigma_bound", V.sigmaBound(), "a", Codec.ringToB64(V.pk[0]), "b", Codec.ringToB64(V.pk[1]),
            "fingerprint", J.str(J.o(e, "key"), "fingerprint"), "candidates", cands);
    }

    JSONObject cast(String eid, JSONObject ballotJson, String channel) throws ApiException {
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("voting")) {
            throw new ApiException(409, "The bulletin board is not accepting ballots right now");
        }
        Oylama V = scheme(eid);
        Ballot b;
        try {
            b = BallotJson.fromJson(V, ballotJson);
        } catch (JSONException | IllegalArgumentException ex) {
            throw new ApiException(400, "Malformed ballot: " + ex.getMessage());
        }
        return post(eid, V, b, ballotJson, channel);
    }

    JSONObject post(String eid, Oylama V, Ballot b, JSONObject ballotJson, String channel) throws ApiException {
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("voting")) {
            throw new ApiException(409, "The bulletin board is not accepting ballots right now");
        }
        int roster = rosterCount(e), nb = ballotCount(e);
        if (roster + nb + 1 > J.num(e, "capacity", 8)) {
            throw new ApiException(409, "The board is full: all " + J.num(e, "capacity", 8) + " entries are used");
        }
        String ukey = Hash.hex(V.ballotKey(b));
        for (JSONObject x : J.list(J.a(e, "ballots"))) {
            if (J.str(x, "ukey").equals(ukey)) {
                throw new ApiException(409, "This ballot is already on the board");
            }
        }
        long t0 = System.nanoTime();
        boolean ok = V.checkBallot(b);
        double ms = (System.nanoTime() - t0) / 1e6;
        if (!ok) {
            throw new ApiException(422, "The board rejected the ballot: a proof of knowledge did not verify");
        }
        String tracker = Hash.hex(V.tracker(b));
        try {
            Bin.writeText(edir(eid, "ballots", nb + ".json"), ballotJson.toString());
        } catch (IOException ex) {
            throw new ApiException(500, "Could not store the ballot");
        }
        double t = now();
        J.a(e, "ballots").put(J.obj("position", nb, "tracker", tracker, "ukey", ukey, "posted", t, "size_bits", b.sizeBits,
            "verify_ms", Math.round(ms * 10) / 10.0, "channel", channel));
        event(e, "board", "ballot", "Ballot " + (nb + 1) + " accepted on the board, tracker " + tracker.substring(0, 12));
        save(true);
        return J.obj("election", eid, "position", nb, "tracker", tracker, "verify_ms", Math.round(ms * 10) / 10.0,
            "size_kb", Math.round(b.sizeBits / 819.2) / 10.0, "posted", t);
    }

    JSONObject castWithCredential(String eid, String credText, int choice, String channel) throws ApiException {
        JSONObject e = find(eid);
        Oylama V = scheme(eid);
        if (choice < 1 || choice > J.a(e, "candidates").length()) {
            throw new ApiException(400, "Unknown option");
        }
        int[] cred;
        try {
            cred = Codec.credentialBits(credText, V.lam);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ex.getMessage());
        }
        Ballot b = V.vote(cred, choice, Oylama.random(32), null);
        try {
            return post(eid, V, b, BallotJson.toJson(b), channel);
        } catch (JSONException ex) {
            throw new ApiException(500, ex.getMessage());
        }
    }

    JSONObject injectDecoys(JSONObject actor, String eid, JSONObject body) throws ApiException {
        need(actor, "authority");
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("voting")) {
            throw new ApiException(409, "Decoy ballots can be posted while voting is open");
        }
        int n = Math.max(1, Math.min(4, J.num(body, "count", 1)));
        Oylama V = scheme(eid);
        JSONArray out = new JSONArray();
        for (int i = 0; i < n; i++) {
            int choice = 1 + RNG.nextInt(J.a(e, "candidates").length());
            Ballot b = V.vote(V.newCredential(), choice, Oylama.random(32), null);
            try {
                out.put(post(eid, V, b, BallotJson.toJson(b), "decoy"));
            } catch (JSONException ex) {
                throw new ApiException(500, ex.getMessage());
            }
        }
        J.put(e, "decoys", J.num(e, "decoys", 0) + out.length());
        event(e, J.str(actor, "email"), "decoy", "Authority posted " + out.length() + (out.length() == 1 ? " decoy ballot" : " decoy ballots") + " under fabricated credentials");
        save(true);
        return J.obj("posted", out);
    }

    JSONObject board(String eid) throws ApiException {
        JSONObject e = find(eid);
        JSONArray bs = new JSONArray();
        for (JSONObject x : J.list(J.a(e, "ballots"))) {
            bs.put(J.obj("position", x.opt("position"), "tracker", x.opt("tracker"), "posted", x.opt("posted"),
                "size_kb", Math.round(J.dbl(x, "size_bits", 0) / 819.2) / 10.0, "verify_ms", x.opt("verify_ms")));
        }
        return J.obj("election", eid, "status", e.opt("status"), "capacity", e.opt("capacity"), "roster", rosterCount(e), "ballots", bs);
    }

    Ballot loadBallot(String eid, Oylama V, int pos) throws ApiException {
        File f = edir(eid, "ballots", pos + ".json");
        if (!f.exists()) {
            throw new ApiException(404, "No ballot at that position");
        }
        try {
            return BallotJson.fromJson(V, new JSONObject(Bin.readText(f)));
        } catch (IOException | JSONException ex) {
            throw new ApiException(500, "Stored ballot unreadable");
        }
    }

    JSONObject ballotRow(JSONObject e, int pos) throws ApiException {
        for (JSONObject x : J.list(J.a(e, "ballots"))) {
            if (J.num(x, "position", -1) == pos) {
                return x;
            }
        }
        throw new ApiException(404, "No ballot at that position");
    }

    JSONObject ballotDetail(String eid, int pos) throws ApiException {
        JSONObject e = find(eid);
        JSONObject row = ballotRow(e, pos);
        Oylama V = scheme(eid);
        Ballot b = loadBallot(eid, V, pos);
        JSONArray cts = new JSONArray();
        String[] names = {"vote ciphertext C1", "credential ciphertext C2"};
        int[][][] cs = {b.c1, b.c2};
        for (int i = 0; i < 2; i++) {
            JSONArray prev = new JSONArray();
            for (int k = 0; k < 8; k++) {
                prev.put(cs[i][0][k]);
            }
            cts.put(J.obj("name", names[i], "u_digest", Hash.hex(Hash.h(Hash.ser(cs[i][0]))).substring(0, 32),
                "w_digest", Hash.hex(Hash.h(Hash.ser(cs[i][1]))).substring(0, 32), "preview", prev));
        }
        JSONArray proofs = new JSONArray();
        String[] st = {"vote", "credential"};
        org.oylama.crypto.Proof[] ps = {b.p1, b.p2};
        double[] sg = {b.s1, b.s2};
        for (int i = 0; i < 2; i++) {
            long mx = 0;
            for (long[] r : ps[i].z) {
                for (long x : r) {
                    mx = Math.max(mx, Math.abs(x));
                }
            }
            proofs.put(J.obj("statement", st[i], "challenge_seed", Hash.hex(ps[i].seed), "sigma", Math.round(sg[i] * 10) / 10.0,
                "max_abs_z", mx, "bound", Math.round(6 * sg[i] * 10) / 10.0, "size_kb", Math.round(ps[i].sizeBits / 819.2) / 10.0));
        }
        return J.obj("position", pos, "tracker", row.opt("tracker"), "posted", row.opt("posted"),
            "size_kb", Math.round(J.dbl(row, "size_bits", 0) / 819.2) / 10.0, "verify_ms", row.opt("verify_ms"), "ciphertexts", cts,
            "proofs", proofs, "uniqueness_key", J.str(row, "ukey").substring(0, 32), "ring", J.obj("L", V.R.L, "N", V.R.n));
    }

    JSONObject verifyBallot(String eid, int pos) throws ApiException {
        JSONObject e = find(eid);
        JSONObject row = ballotRow(e, pos);
        Oylama V = scheme(eid);
        Ballot b = loadBallot(eid, V, pos);
        long t0 = System.nanoTime();
        boolean ok = V.checkBallot(b);
        double ms = (System.nanoTime() - t0) / 1e6;
        return J.obj("position", pos, "proofs_ok", ok, "tracker_ok", Hash.hex(V.tracker(b)).equals(J.str(row, "tracker")),
            "ms", Math.round(ms * 10) / 10.0);
    }

    JSONObject roster(JSONObject actor, String eid) throws ApiException {
        JSONObject e = find(eid);
        boolean full = actor != null && (J.str(actor, "role").equals("registrar") || J.str(actor, "role").equals("authority"));
        List<JSONObject> vs = new ArrayList<>();
        for (JSONObject v : votersOf(e)) {
            if (!J.isNull(v, "roster_pos")) {
                vs.add(v);
            }
        }
        vs.sort((a, b) -> Integer.compare(J.num(a, "roster_pos", 0), J.num(b, "roster_pos", 0)));
        JSONArray entries = new JSONArray();
        for (JSONObject v : vs) {
            entries.put(J.obj("position", v.opt("roster_pos"), "voter", full ? J.str(v, "email") : maskEmail(J.str(v, "email")),
                "name", full ? v.opt("name") : null, "digest", v.opt("roster_digest"), "issued", v.opt("issued")));
        }
        JSONObject reg = J.o(e, "registrar");
        String sig = reg == null ? "" : J.str(reg, "signature");
        return J.obj("election", eid, "alg", reg == null ? null : reg.opt("alg"), "registrar_key", reg == null ? null : reg.opt("pk_fingerprint"),
            "root", reg == null ? null : reg.opt("root"), "signature_preview", sig.substring(0, Math.min(96, sig.length())),
            "signature_bytes", sig.length() / 2, "signed_entries", reg == null ? 0 : J.num(reg, "signed_entries", 0), "entries", entries);
    }

    JSONArray events(String eid) throws ApiException {
        JSONObject e = find(eid);
        List<JSONObject> ev = J.list(J.a(e, "events"));
        JSONArray out = new JSONArray();
        for (int i = ev.size() - 1; i >= 0 && out.length() < 80; i--) {
            out.put(ev.get(i));
        }
        return out;
    }

    JSONArray activity() {
        List<JSONObject> all = new ArrayList<>();
        for (JSONObject e : J.list(root.optJSONArray("elections"))) {
            for (JSONObject ev : J.list(J.a(e, "events"))) {
                JSONObject c = J.copy(ev);
                J.put(c, "election", J.str(e, "id"));
                J.put(c, "title", J.str(e, "title"));
                all.add(c);
            }
        }
        all.sort((a, b) -> Double.compare(J.dbl(b, "ts", 0), J.dbl(a, "ts", 0)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(30, all.size()); i++) {
            out.put(all.get(i));
        }
        return out;
    }

    JSONObject approve(JSONObject actor, String eid, int idx, boolean simulated) throws ApiException {
        if (!simulated) {
            need(actor, "trustee");
        }
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("closed")) {
            throw new ApiException(409, "The tally can be approved after voting closes");
        }
        List<JSONObject> seats = trustees(e);
        if (idx < 0 || idx >= seats.size() || !J.str(seats.get(idx), "status").equals("joined")) {
            throw new ApiException(404, "No such trustee");
        }
        JSONObject seat = seats.get(idx);
        if (!J.str(seat, "email").equals(J.str(actor, "email"))) {
            throw new ApiException(403, "Seat " + (idx + 1) + " belongs to " + J.str(seat, "email"));
        }
        if (J.isNull(seat, "approved")) {
            J.put(seat, "approved", now());
            event(e, J.str(actor, "email"), "approve", J.str(seat, "name") + " released decryption share " + (idx + 1) + " for the tally");
        }
        int n = 0;
        for (JSONObject s : seats) {
            if (!J.isNull(s, "approved")) {
                n++;
            }
        }
        save(true);
        if (n >= J.num(e, "threshold", 2)) {
            startTally(eid);
        }
        return view(eid, actor);
    }

    JSONObject autoApprove(JSONObject actor, String eid) throws ApiException {
        need(actor, "authority", "trustee");
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("closed")) {
            throw new ApiException(409, "Close voting first");
        }
        int have = 0;
        for (JSONObject s : trustees(e)) {
            if (!J.isNull(s, "approved")) {
                have++;
            }
        }
        for (JSONObject s : trustees(e)) {
            if (have >= J.num(e, "threshold", 2)) {
                break;
            }
            if (!J.isNull(s, "approved")) {
                continue;
            }
            approve(J.obj("email", J.str(s, "email"), "name", J.str(s, "name"), "role", "trustee"), eid, J.num(s, "idx", 0), true);
            have++;
            if (!J.str(find(eid), "status").equals("closed")) {
                break;
            }
        }
        return view(eid, actor);
    }

    void startTally(String eid) throws ApiException {
        JSONObject e = find(eid);
        if (!J.str(e, "status").equals("closed")) {
            throw new ApiException(409, "The election is not waiting for a tally");
        }
        List<Integer> quorum = new ArrayList<>();
        for (JSONObject s : trustees(e)) {
            if (!J.isNull(s, "approved") && quorum.size() < J.num(e, "threshold", 2)) {
                quorum.add(J.num(s, "idx", 0));
            }
        }
        if (quorum.size() < J.num(e, "threshold", 2)) {
            throw new ApiException(409, J.num(e, "threshold", 2) + " trustee approvals are needed");
        }
        int nb = ballotCount(e), nv = rosterCount(e);
        Oylama V = scheme(eid);
        Oylama.Plan plan = nb + nv > 0 ? V.plan(nb, nv) : new Oylama.Plan();
        J.put(e, "status", "tallying");
        J.put(J.o(e, "phases"), "tallying", now());
        JSONArray steps = new JSONArray();
        for (String[] s : STEPS) {
            steps.put(J.obj("key", s[0], "label", s[1], "state", "pending", "detail", ""));
        }
        JSONObject groups = new JSONObject();
        for (Map.Entry<String, Integer> g : plan.groups.entrySet()) {
            J.put(groups, g.getKey(), g.getValue());
        }
        double spo = J.dbl(root, "seconds_per_opening", 0.45);
        J.put(e, "tally", J.obj("state", "queued", "queued", now(), "quorum", J.arr(quorum), "total", plan.total, "done", 0,
            "groups", groups, "stages", plan.stages, "label", "Waiting for the tally worker", "steps", steps,
            "eta", (int) (plan.total * spo + 8), "board", nb, "roster", nv));
        J.put(e, "tally_log", new JSONArray());
        J.put(e, "log_seq", 0);
        StringBuilder q = new StringBuilder();
        for (int j : quorum) {
            q.append(q.length() == 0 ? "" : " and ").append(j + 1);
        }
        event(e, "tally", "tally", "Tally started with trustees " + q);
        save(true);
        Thread th = new Thread(() -> runTally(eid), "oylama-tally");
        th.setPriority(Thread.NORM_PRIORITY);
        th.start();
        TallyListener l = listener;
        if (l != null) {
            l.started(eid, J.str(e, "title"));
        }
    }

    JSONObject tallyStatus(String eid, int since) throws ApiException {
        JSONObject e = find(eid);
        JSONArray logs = J.a(e, "tally_log");
        JSONArray out = new JSONArray();
        int start = Math.max(0, logs.length() - 200);
        for (int i = start; i < logs.length(); i++) {
            JSONObject l = logs.optJSONObject(i);
            if (J.num(l, "id", 0) > since) {
                out.put(l);
            }
        }
        return J.obj("election", eid, "status", e.opt("status"), "tally", e.opt("tally"), "log", out, "results", e.opt("results"));
    }

    void recover() {
        for (JSONObject e : J.list(root.optJSONArray("elections"))) {
            if (J.str(e, "status").equals("tallying")) {
                J.put(e, "status", "closed");
                JSONObject t = J.o(e, "tally");
                if (t == null) {
                    t = new JSONObject();
                    J.put(e, "tally", t);
                }
                J.put(t, "state", "failed");
                J.put(t, "error", "The app stopped during the tally. Approve it again to restart.");
                for (JSONObject s : trustees(e)) {
                    J.put(s, "approved", null);
                }
            }
        }
        save(true);
    }

    synchronized void log(JSONObject e, String msg, String level) {
        int seq = J.num(e, "log_seq", 0) + 1;
        J.put(e, "log_seq", seq);
        JSONArray logs = J.a(e, "tally_log");
        logs.put(J.obj("id", seq, "ts", now(), "level", level, "message", msg));
        J.put(e, "tally_log", logs);
    }

    static JSONObject step(JSONObject tally, String key) {
        for (JSONObject s : J.list(J.a(tally, "steps"))) {
            if (J.str(s, "key").equals(key)) {
                return s;
            }
        }
        return null;
    }

    static void mark(JSONObject tally, String key, String state, String detail) {
        JSONObject s = step(tally, key);
        if (s == null) {
            return;
        }
        J.put(s, "state", state);
        if (detail != null) {
            J.put(s, "detail", detail);
        }
        if (state.equals("active")) {
            J.put(s, "started", now());
        }
        if (state.equals("done")) {
            J.put(s, "finished", now());
        }
    }

    void runTally(String eid) {
        JSONObject e;
        JSONObject tally;
        Oylama V;
        List<Integer> quorum = new ArrayList<>();
        int nb, nv;
        List<Ballot> ballots = new ArrayList<>();
        List<int[][]> roster = new ArrayList<>();
        Map<Integer, Map<String, int[]>> holds = new LinkedHashMap<>();
        String[] order = new String[STEPS.length];
        for (int i = 0; i < STEPS.length; i++) {
            order[i] = STEPS[i][0];
        }
        double started = now();
        try {
            synchronized (this) {
                e = find(eid);
                tally = J.o(e, "tally");
                V = scheme(eid);
                JSONArray qa = J.a(tally, "quorum");
                for (int i = 0; i < qa.length(); i++) {
                    quorum.add(qa.getInt(i));
                }
                nb = J.num(tally, "board", 0);
                nv = J.num(tally, "roster", 0);
                J.put(tally, "state", "running");
                J.put(tally, "started", started);
                J.put(tally, "label", "Starting");
                Map<Integer, String> names = new HashMap<>();
                for (JSONObject s : trustees(e)) {
                    names.put(J.num(s, "idx", 0), J.str(s, "name"));
                }
                log(e, "Quorum formed by trustee " + (quorum.get(0) + 1) + " (" + names.get(quorum.get(0)) + ") and trustee "
                    + (quorum.get(1) + 1) + " (" + names.get(quorum.get(1)) + ")", "info");
                mark(tally, "verify", "active", null);
                save(true);
            }
            for (int i = 0; i < nb; i++) {
                ballots.add(loadBallot(eid, V, i));
            }
            for (int i = 0; i < nv; i++) {
                int[][] r = Bin.readArrays(edir(eid, "roster", i + ".bin"));
                roster.add(new int[][]{r[0], r[1]});
            }
            int good = 0;
            for (Ballot b : ballots) {
                if (V.checkBallot(b)) {
                    good++;
                }
            }
            boolean sigOk;
            synchronized (this) {
                List<String> digests = rosterDigests(e);
                byte[] rt = rosterRoot(e, digests);
                JSONObject reg = J.o(e, "registrar");
                sigOk = nv == 0 || (reg != null && Hash.hex(rt).equals(J.str(reg, "root"))
                    && Pq.verify(Hash.unhex(J.str(reg, "pk")), rt, Hash.unhex(J.str(reg, "signature"))));
            }
            if (good != nb || !sigOk) {
                throw new IllegalStateException("board or roster verification failed");
            }
            synchronized (this) {
                mark(tally, "verify", "done", nb + " ballot proofs valid, roster of " + nv + " signed by the registrar");
                log(e, "Verified " + nb + (nb == 1 ? " ballot" : " ballots") + " (two proofs each) and the ML-DSA-65 roster signature over " + nv + (nv == 1 ? " entry" : " entries"), "info");
                save(true);
            }
            for (int j : quorum) {
                holds.put(j, holdings(eid, j));
            }
            final JSONObject groups = J.o(tally, "groups");
            final int total = J.num(tally, "total", 1);
            final Map<String, Integer> groupDone = new HashMap<>();
            final String[] cur = {null, null};
            final long tPipe = System.nanoTime();
            final int[] done = {0};
            final JSONObject fe = e;
            final JSONObject ft = tally;
            Evaluator.Monitor monitor = (eng, count, rotation, ok) -> {
                String label;
                synchronized (LocalNode.this) {
                    done[0]++;
                    J.put(ft, "done", done[0]);
                    String group = eng.phase.split(":")[0];
                    if (group.equals("replicate")) {
                        group = "load";
                    }
                    label = phaseLabel(eng.phase, nb, nv);
                    if (!group.equals(cur[0])) {
                        if (cur[0] != null) {
                            mark(ft, cur[0], "done", null);
                        }
                        int from = indexOf(order, "load"), to = indexOf(order, group);
                        for (int k = from; k < to; k++) {
                            JSONObject s = step(ft, order[k]);
                            if (s != null && !J.str(s, "state").equals("done")) {
                                mark(ft, order[k], "done", null);
                            }
                        }
                        mark(ft, group, "active", null);
                        cur[0] = group;
                    }
                    if (!label.equals(cur[1])) {
                        cur[1] = label;
                        log(fe, label, "info");
                    }
                    int gd = groupDone.merge(group, 1, Integer::sum);
                    int gt = J.num(groups, group, 0) + (group.equals("load") ? J.num(groups, "replicate", 0) : 0);
                    JSONObject s = step(ft, group);
                    if (s != null) {
                        J.put(s, "detail", gd + " of " + gt + " masked openings");
                        J.put(s, "done", gd);
                        J.put(s, "total", gt);
                    }
                    J.put(ft, "label", label);
                    double rate = (System.nanoTime() - tPipe) / 1e9 / done[0];
                    J.put(ft, "eta", (int) (rate * (total - done[0]) + 6));
                    Evaluator.Stats st = eng.stats;
                    J.put(ft, "stats", J.obj("opens", st.opens, "batches", st.batches, "mults", st.mults, "rotations", st.rotations,
                        "refreshes", st.refreshes, "proof_bits", st.proofBits, "dec_bits", st.decBits, "shares", st.shares,
                        "shares_ok", st.sharesOk, "masks", st.masks, "masks_ok", st.masksOk));
                    if (done[0] % 10 == 0) {
                        StringBuilder q = new StringBuilder();
                        for (int j : quorum) {
                            q.append(q.length() == 0 ? "" : " and ").append(j + 1);
                        }
                        log(fe, "Trustees " + q + " opened masked batch " + done[0] + " of " + total + " (" + pp().N
                            + " slots); decryption shares " + (ok ? "verified" : "FAILED"), ok ? "info" : "error");
                    }
                    save(false);
                }
                TallyListener l = listener;
                if (l != null) {
                    l.progress(eid, done[0], total, label);
                }
            };
            Evaluator eng = V.evaluator(holds, quorum, Oylama.random(32), Oylama.random(32), monitor, true);
            int[] votes;
            List<Oylama.MixReport> mrep = new ArrayList<>();
            List<Oylama.DecReport> drep = new ArrayList<>();
            if (nb + nv > 0) {
                List<int[][]> sums = new ArrayList<>();
                for (Ballot b : ballots) {
                    sums.add(V.ballotSum(b));
                }
                Flat F = V.load(eng, sums, roster, Hash.concat(eng.pub, "ld"));
                Flat out = V.cleanse(eng, F, nb, Hash.concat(eng.pub, "cl"));
                int[][][] vs = V.extract(eng, out, V.mmax, Hash.concat(eng.pub, "ex"));
                synchronized (this) {
                    if (cur[0] != null) {
                        mark(tally, cur[0], "done", null);
                    }
                    for (int k = indexOf(order, "load"); k < indexOf(order, "mix"); k++) {
                        JSONObject s = step(tally, order[k]);
                        if (s != null && !J.str(s, "state").equals("done")) {
                            mark(tally, order[k], "done", null);
                        }
                    }
                    mark(tally, "extract", "done", V.mmax + " vote slots extracted");
                    Evaluator.Stats st = eng.stats;
                    log(e, "Cleansing finished: " + st.batches + " masked openings, " + st.sharesOk + " of " + st.shares
                        + " decryption shares and " + st.masksOk + " of " + st.masks + " mask proofs verified", "info");
                    J.put(tally, "label", "Mix servers shuffling the cleansed votes");
                    mark(tally, "mix", "active", null);
                    save(true);
                }
                int[][][] mixed = V.mix(vs, eng.secret, Hash.concat(eng.pub, "mx"), mrep);
                synchronized (this) {
                    StringBuilder d = new StringBuilder();
                    for (Oylama.MixReport r : mrep) {
                        d.append(d.length() == 0 ? "" : " / ").append("server ").append(r.server).append(r.ok ? " verified" : " FAILED");
                        log(e, String.format(Locale.US, "Mix server %d shuffled %d ciphertexts; shuffle proof of %.1f MB %s", r.server, V.mmax,
                            r.bits / 8.0 / 1048576.0, r.ok ? "verified" : "FAILED"), r.ok ? "info" : "error");
                    }
                    mark(tally, "mix", "done", d.toString());
                    J.put(tally, "label", "Trustees decrypting the shuffled votes");
                    mark(tally, "decrypt", "active", null);
                    save(true);
                }
                votes = V.decrypt(eng, mixed, Hash.concat(eng.pub, "dec"), drep);
                synchronized (this) {
                    StringBuilder d = new StringBuilder();
                    for (Oylama.DecReport r : drep) {
                        d.append(d.length() == 0 ? "" : " / ").append("trustee ").append(r.trustee).append(r.ok ? " verified" : " FAILED");
                        log(e, String.format(Locale.US, "Trustee %d produced %.1f MB of decryption shares; proof %s", r.trustee,
                            r.bits / 8.0 / 1048576.0, r.ok ? "verified" : "FAILED"), r.ok ? "info" : "error");
                    }
                    mark(tally, "decrypt", "done", d.toString());
                }
            } else {
                votes = new int[0];
                synchronized (this) {
                    for (int k = 1; k < order.length - 1; k++) {
                        mark(tally, order[k], "done", "nothing to process");
                    }
                }
            }
            synchronized (this) {
                mark(tally, "count", "active", null);
                JSONArray cands = J.a(e, "candidates");
                int[] counts = new int[cands.length()];
                for (int v : votes) {
                    if (v >= 1 && v <= counts.length) {
                        counts[v - 1]++;
                    }
                }
                int valid = 0;
                JSONArray cj = new JSONArray();
                for (int c : counts) {
                    valid += c;
                    cj.put(c);
                }
                Evaluator.Stats st = eng.stats;
                boolean verified = st.sharesOk == st.shares && st.masksOk == st.masks;
                JSONArray mj = new JSONArray();
                for (Oylama.MixReport r : mrep) {
                    verified &= r.ok;
                    mj.put(J.obj("server", r.server, "ok", r.ok, "bits", r.bits, "seconds", Math.round(r.seconds * 100) / 100.0));
                }
                JSONArray dj = new JSONArray();
                for (Oylama.DecReport r : drep) {
                    verified &= r.ok;
                    dj.put(J.obj("trustee", r.trustee, "ok", r.ok, "bits", r.bits, "seconds", Math.round(r.seconds * 100) / 100.0));
                }
                JSONArray vj = new JSONArray();
                for (int v : votes) {
                    vj.put(v);
                }
                JSONArray qj = new JSONArray();
                for (int j : quorum) {
                    qj.put(j + 1);
                }
                double seconds = Math.round((now() - started) * 10) / 10.0;
                JSONObject results = J.obj("counts", cj, "total_valid", valid, "votes", vj, "board", nb, "roster", nv, "capacity", V.mmax,
                    "discarded", nb - valid, "quorum", qj, "openings", st.batches, "shares", st.shares, "shares_ok", st.sharesOk,
                    "masks", st.masks, "masks_ok", st.masksOk, "mults", st.mults, "rotations", st.rotations, "mix", mj, "decryption", dj,
                    "transcript_mb", Math.round((st.proofBits + st.decBits) / 8.0 / 1048576.0 * 10) / 10.0, "digest", Hash.hex(eng.digest),
                    "seconds", seconds, "published", now(), "verified", verified);
                if (st.batches > 0) {
                    double measured = ((System.nanoTime() - tPipe) / 1e9) / st.batches;
                    J.put(root, "seconds_per_opening", Math.round(measured * 1000) / 1000.0);
                }
                mark(tally, "count", "done", valid + (valid == 1 ? " valid vote counted" : " valid votes counted"));
                J.put(e, "results", results);
                J.put(e, "status", "published");
                J.put(J.o(e, "phases"), "published", now());
                J.put(tally, "state", "done");
                J.put(tally, "label", "Result published");
                J.put(tally, "eta", 0);
                J.put(tally, "finished", now());
                StringBuilder r = new StringBuilder("Result published: ");
                for (int i = 0; i < counts.length; i++) {
                    r.append(i == 0 ? "" : ", ").append(J.str(cands.optJSONObject(i), "name")).append(' ').append(counts[i]);
                }
                log(e, r.toString(), "info");
                event(e, "tally", "published", verified ? "Result published after " + st.batches + " masked openings; every proof verified"
                    : "Result published with verification failures");
                save(true);
            }
            TallyListener l = listener;
            if (l != null) {
                l.finished(eid, true);
            }
        } catch (Exception ex) {
            synchronized (this) {
                try {
                    JSONObject e2 = find(eid);
                    JSONObject t2 = J.o(e2, "tally");
                    if (t2 != null) {
                        J.put(t2, "state", "failed");
                        J.put(t2, "error", String.valueOf(ex.getMessage()));
                        J.put(t2, "label", "Tally failed");
                    }
                    log(e2, "Tally failed: " + ex.getMessage(), "error");
                    J.put(e2, "status", "closed");
                    for (JSONObject s : trustees(e2)) {
                        J.put(s, "approved", null);
                    }
                    save(true);
                } catch (ApiException ignored) {
                }
            }
            TallyListener l = listener;
            if (l != null) {
                l.finished(eid, false);
            }
        }
    }

    static int indexOf(String[] arr, String key) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(key)) {
                return i;
            }
        }
        return arr.length;
    }

    public synchronized boolean tallyRunning() {
        for (JSONObject e : J.list(root.optJSONArray("elections"))) {
            if (J.str(e, "status").equals("tallying")) {
                return true;
            }
        }
        return false;
    }

    JSONObject audit(String eid) throws ApiException {
        JSONObject e = find(eid);
        long t0 = System.nanoTime();
        JSONArray checks = new JSONArray();
        if (J.isNull(e, "key")) {
            checks.put(J.obj("name", "Key ceremony", "ok", false, "detail", "not finished"));
            return J.obj("ok", false, "checks", checks, "seconds", 0, "audited", now());
        }
        Oylama V = scheme(eid);
        checks.put(J.obj("name", "Joint public key", "ok", true, "detail", J.num(e, "threshold", 2) + "-of-" + J.num(e, "n_trustees", 3)
            + " threshold key, fingerprint " + J.str(J.o(e, "key"), "fingerprint").substring(0, 16)));
        List<String> digests = rosterDigests(e);
        List<String> recomputed = new ArrayList<>();
        try {
            for (int i = 0; i < digests.size(); i++) {
                int[][] r = Bin.readArrays(edir(eid, "roster", i + ".bin"));
                recomputed.add(Hash.hex(Hash.h("OYLAMA/roster", Hash.unhex(J.str(e, "uid")), i, Hash.ser(r[0], r[1]))));
            }
        } catch (IOException ex) {
            throw new ApiException(500, "Roster unavailable");
        }
        JSONObject reg = J.o(e, "registrar");
        byte[] rt = rosterRoot(e, digests);
        boolean sigOk = digests.isEmpty() || (reg != null && Hash.hex(rt).equals(J.str(reg, "root"))
            && Pq.verify(Hash.unhex(J.str(reg, "pk")), rt, Hash.unhex(J.str(reg, "signature"))));
        checks.put(J.obj("name", "Encrypted roster", "ok", recomputed.equals(digests) && sigOk, "detail", digests.size()
            + (digests.size() == 1 ? " entry" : " entries") + ", digests recomputed, ML-DSA-65 signature " + (sigOk ? "valid" : "INVALID")));
        List<JSONObject> rows = J.list(J.a(e, "ballots"));
        int good = 0;
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JSONObject row : rows) {
            Ballot b = loadBallot(eid, V, J.num(row, "position", 0));
            if (V.checkBallot(b) && Hash.hex(V.tracker(b)).equals(J.str(row, "tracker")) && Hash.hex(V.ballotKey(b)).equals(J.str(row, "ukey"))) {
                good++;
            }
            keys.add(J.str(row, "ukey"));
        }
        checks.put(J.obj("name", "Bulletin board", "ok", good == rows.size() && keys.size() == rows.size(), "detail", good + " of "
            + rows.size() + (rows.size() == 1 ? " ballot carries" : " ballots carry") + " two valid proofs of knowledge and unique ciphertexts"));
        int used = digests.size() + rows.size();
        checks.put(J.obj("name", "Board capacity", "ok", used <= J.num(e, "capacity", 8), "detail", used + " of " + J.num(e, "capacity", 8) + " entries used"));
        JSONObject r = J.o(e, "results");
        if (r != null) {
            checks.put(J.obj("name", "Cleansing transcript", "ok", J.num(r, "shares_ok", 0) == J.num(r, "shares", 0)
                && J.num(r, "masks_ok", 0) == J.num(r, "masks", 0), "detail", J.num(r, "openings", 0) + " masked openings, "
                + J.num(r, "shares_ok", 0) + "/" + J.num(r, "shares", 0) + " decryption shares and " + J.num(r, "masks_ok", 0) + "/"
                + J.num(r, "masks", 0) + " mask proofs verified, digest " + J.str(r, "digest").substring(0, 16)));
            boolean mixOk = true;
            StringBuilder md = new StringBuilder();
            for (JSONObject m : J.list(J.a(r, "mix"))) {
                mixOk &= J.bool(m, "ok", false);
                md.append(md.length() == 0 ? "" : "; ").append("server ").append(J.num(m, "server", 0)).append(" shuffle proof ")
                    .append(J.bool(m, "ok", false) ? "valid" : "invalid");
            }
            checks.put(J.obj("name", "Mix-net", "ok", mixOk, "detail", md.length() == 0 ? "no ciphertexts" : md.toString()));
            boolean decOk = true;
            StringBuilder dd = new StringBuilder();
            for (JSONObject d : J.list(J.a(r, "decryption"))) {
                decOk &= J.bool(d, "ok", false);
                dd.append(dd.length() == 0 ? "" : "; ").append("trustee ").append(J.num(d, "trustee", 0)).append(" shares ")
                    .append(J.bool(d, "ok", false) ? "valid" : "invalid");
            }
            checks.put(J.obj("name", "Threshold decryption", "ok", decOk, "detail", dd.length() == 0 ? "no ciphertexts" : dd.toString()));
            JSONArray votes = J.a(r, "votes");
            int nc = J.a(e, "candidates").length();
            int[] recount = new int[nc];
            for (int i = 0; i < votes.length(); i++) {
                int v = votes.optInt(i);
                if (v >= 1 && v <= nc) {
                    recount[v - 1]++;
                }
            }
            JSONArray counts = J.a(r, "counts");
            boolean same = counts.length() == nc;
            StringBuilder rc = new StringBuilder("[");
            for (int i = 0; i < nc; i++) {
                same &= i < counts.length() && counts.optInt(i) == recount[i];
                rc.append(i == 0 ? "" : ", ").append(recount[i]);
            }
            rc.append(']');
            checks.put(J.obj("name", "Result", "ok", same, "detail", "counts recomputed from the " + votes.length() + " decrypted slots: " + rc));
        } else {
            checks.put(J.obj("name", "Result", "ok", true, "detail", "not published yet"));
        }
        boolean ok = true;
        for (int i = 0; i < checks.length(); i++) {
            ok &= checks.optJSONObject(i).optBoolean("ok");
        }
        return J.obj("ok", ok, "checks", checks, "seconds", Math.round((System.nanoTime() - t0) / 1e7) / 100.0, "audited", now());
    }

    JSONArray track(String tracker) throws ApiException {
        String t = tracker.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
        if (t.length() < 8) {
            throw new ApiException(400, "Enter at least 8 characters of the tracker");
        }
        JSONArray out = new JSONArray();
        for (JSONObject e : J.list(root.optJSONArray("elections"))) {
            for (JSONObject b : J.list(J.a(e, "ballots"))) {
                if (J.str(b, "tracker").startsWith(t)) {
                    out.put(J.obj("election", J.str(e, "id"), "title", J.str(e, "title"), "status", J.str(e, "status"),
                        "position", b.opt("position"), "tracker", b.opt("tracker"), "posted", b.opt("posted")));
                }
            }
        }
        return out;
    }

    public synchronized boolean isEmpty() {
        return root.optJSONArray("elections").length() == 0;
    }

    JSONObject quickSession(String email, String role, String name) {
        JSONObject res = login(J.obj("email", email, "role", role, "name", name, "provider", "demo"));
        return session(J.str(res, "token"));
    }

    String voterCast(String eid, String email, String name, int choice) throws ApiException {
        JSONObject v = quickSession(email, "voter", name);
        requestCredential(v, eid);
        String cred = J.str(openEnvelope(v, eid), "credential");
        if (choice > 0) {
            castWithCredential(eid, cred, choice, "seed");
        }
        return cred;
    }

    public synchronized void seed() throws ApiException {
        if (!isEmpty()) {
            return;
        }
        JSONObject admin = quickSession("authority@oylama.demo", "authority", "Election Authority");
        JSONObject a = createElection(admin, J.obj("title", "Student Union President 2026", "organization", "Oylama University",
            "description", "Choose the next president of the student union. Ballots are encrypted with post-quantum lattice encryption, posted anonymously, and cleansed under encryption so that revotes and coerced ballots stay invisible.",
            "capacity", 16, "opens_at", "2026-09-01T09:00", "closes_at", "2026-12-15T18:00",
            "candidates", new JSONArray()
                .put(J.obj("name", "Leyla Aydın", "party", "Progress Together", "bio", "Computer science senior. Wants open lab hours and a 24/7 study hall."))
                .put(J.obj("name", "Marcus Chen", "party", "Students First", "bio", "Economics junior. Proposes lower cafeteria prices and more scholarships."))
                .put(J.obj("name", "Sofia Rossi", "party", "Green Campus", "bio", "Environmental engineering. Plans solar roofs and a campus bike network."))));
        String aid = J.str(a, "id");
        autoCeremony(admin, aid);
        openVoting(admin, aid);
        String alice = voterCast(aid, "alice@oylama.demo", "Alice Johnson", 1);
        voterCast(aid, "bob@oylama.demo", "Bob Martin", 3);
        castWithCredential(aid, alice, 2, "seed");
        injectDecoys(admin, aid, J.obj("count", 1));

        JSONObject b = createElection(admin, J.obj("title", "Referendum: Solar Roof for the Library", "organization", "Oylama City",
            "description", "Should the city install a 400 kW solar roof on the central library? This small referendum is tallied on this device so you can inspect a published result, its cleansing transcript and the audit.",
            "capacity", 4, "opens_at", "2026-08-01T08:00", "closes_at", "2026-08-31T20:00",
            "candidates", new JSONArray()
                .put(J.obj("name", "Yes", "party", "Install the solar roof", "color", "#10B981"))
                .put(J.obj("name", "No", "party", "Keep the current roof", "color", "#EF4444"))));
        String bid = J.str(b, "id");
        autoCeremony(admin, bid);
        openVoting(admin, bid);
        voterCast(bid, "carol@oylama.demo", "Carol Diaz", 1);
        voterCast(bid, "dave@oylama.demo", "Dave Okafor", 0);
        castWithCredential(bid, Codec.randomCredentialText(), 2, "seed");
        closeVoting(admin, bid);
        autoApprove(admin, bid);

        createElection(admin, J.obj("title", "Faculty Senate Election 2026", "organization", "Oylama University",
            "description", "Four faculty members compete for the senate chair. The three trustee seats are still open: sign in as a trustee to join the key ceremony.",
            "capacity", 8, "opens_at", "2026-10-01T09:00", "closes_at", "2026-10-20T17:00",
            "candidates", new JSONArray().put(J.obj("name", "Prof. Elena Petrova", "party", "Mathematics"))
                .put(J.obj("name", "Prof. Daniel Kim", "party", "Physics"))
                .put(J.obj("name", "Dr. Amara Nwosu", "party", "Medicine"))
                .put(J.obj("name", "Dr. Lucas Silva", "party", "Law"))));
        save(true);
    }

    public synchronized void reset() {
        Bin.deleteTree(new File(dir, "elections"));
        schemes.clear();
        root = new JSONObject();
        J.put(root, "users", new JSONObject());
        J.put(root, "sessions", new JSONObject());
        J.put(root, "elections", new JSONArray());
        J.put(root, "seconds_per_opening", 0.45);
        save(true);
    }
}
