import traceback


def _session(svc, email, role, name):
    return svc.session(svc.login(dict(email=email, role=role, name=name))["token"])


def _voter_cast(svc, eid, email, name, choice):
    v = _session(svc, email, "voter", name)
    svc.request_credential(v, eid)
    cred = svc.open_envelope(v, eid)["credential"]
    if choice:
        svc.cast_with_credential(eid, cred, choice, channel="seed")
    return cred


def seed(svc, say=print):
    try:
        if svc.db.elections():
            return
        say("Preparing demo elections with real Oylama keys, credentials and ballots")
        admin = _session(svc, "authority@oylama.demo", "authority", "Election Authority")

        a = svc.create_election(admin, dict(
            title="Student Union President 2026",
            organization="Oylama University",
            description="Choose the next president of the student union. Ballots are encrypted "
                        "with post-quantum lattice encryption, posted anonymously, and cleansed "
                        "under encryption so that revotes and coerced ballots stay invisible.",
            capacity=16, opens_at="2026-09-01T09:00", closes_at="2026-12-15T18:00",
            candidates=[
                dict(name="Leyla Aydın", party="Progress Together",
                     bio="Computer science senior. Wants open lab hours and a 24/7 study hall."),
                dict(name="Marcus Chen", party="Students First",
                     bio="Economics junior. Proposes lower cafeteria prices and more scholarships."),
                dict(name="Sofia Rossi", party="Green Campus",
                     bio="Environmental engineering. Plans solar roofs and a campus bike network."),
            ]))
        svc.auto_ceremony(admin, a["id"])
        svc.open_voting(admin, a["id"])
        alice = _voter_cast(svc, a["id"], "alice@oylama.demo", "Alice Johnson", 1)
        _voter_cast(svc, a["id"], "bob@oylama.demo", "Bob Martin", 3)
        svc.cast_with_credential(a["id"], alice, 2, channel="seed")
        svc.inject_decoys(admin, a["id"], dict(count=1))
        say("  Student Union President 2026 is open for voting")

        b = svc.create_election(admin, dict(
            title="Referendum: Solar Roof for the Library",
            organization="Oylama City",
            description="Should the city install a 400 kW solar roof on the central library? "
                        "This small referendum is tallied automatically so you can inspect a "
                        "published result, its cleansing transcript and the audit.",
            capacity=4, opens_at="2026-08-01T08:00", closes_at="2026-08-31T20:00",
            candidates=[dict(name="Yes", party="Install the solar roof", color="#10B981"),
                        dict(name="No", party="Keep the current roof", color="#EF4444")]))
        svc.auto_ceremony(admin, b["id"])
        svc.open_voting(admin, b["id"])
        _voter_cast(svc, b["id"], "carol@oylama.demo", "Carol Diaz", 1)
        _voter_cast(svc, b["id"], "dave@oylama.demo", "Dave Okafor", 0)
        V = svc.scheme(b["id"])
        from .codec import cred_to_text
        svc.cast_with_credential(b["id"], cred_to_text(V.new_credential()), 2, channel="seed")
        svc.close_voting(admin, b["id"])
        svc.auto_approve(admin, b["id"])
        say("  Referendum: Solar Roof for the Library is being tallied in the background")

        svc.create_election(admin, dict(
            title="Faculty Senate Election 2026",
            organization="Oylama University",
            description="Four faculty members compete for the senate chair. The three trustee "
                        "seats are still open: sign in as a trustee to join the key ceremony.",
            capacity=8, opens_at="2026-10-01T09:00", closes_at="2026-10-20T17:00",
            candidates=[dict(name="Prof. Elena Petrova", party="Mathematics"),
                        dict(name="Prof. Daniel Kim", party="Physics"),
                        dict(name="Dr. Amara Nwosu", party="Medicine"),
                        dict(name="Dr. Lucas Silva", party="Law")]))
        say("  Faculty Senate Election 2026 is waiting for its key ceremony")
        say("Demo data ready")
    except Exception:
        traceback.print_exc()
