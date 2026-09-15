import sys


def tally_main(data_dir, eid):
    sys.dont_write_bytecode = True
    from .service import Service
    svc = Service(data_dir)
    svc.run_tally(eid)
