import argparse
import os
import shutil
import socket
import sys
import threading

sys.dont_write_bytecode = True
os.environ.setdefault("PYTHONDONTWRITEBYTECODE", "1")
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def lan_address():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


def main():
    ap = argparse.ArgumentParser(description="Oylama online voting server")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--data", default=os.path.join(HERE, "data"))
    ap.add_argument("--no-demo", action="store_true", help="start without demo elections")
    ap.add_argument("--reset", action="store_true", help="delete all stored data first")
    args = ap.parse_args()

    if args.reset:
        shutil.rmtree(args.data, ignore_errors=True)

    print("Starting Oylama...", flush=True)
    from backend.seed import seed
    from backend.server import serve
    from backend.service import Service

    svc = Service(args.data)
    svc.recover()
    httpd = serve(svc, args.host, args.port, os.path.join(HERE, "static"))
    lan = lan_address()
    print("")
    print("  Oylama is running")
    print("  Local:    http://localhost:%d" % args.port)
    if lan and args.host in ("0.0.0.0", ""):
        print("  Network:  http://%s:%d   (use this address in the Android app)" % (lan, args.port))
    print("  Android emulator: http://10.0.2.2:%d" % args.port)
    print("  Post-quantum backend: %s" % svc.system()["backend"])
    print("  Press Ctrl+C to stop")
    print("", flush=True)
    if not args.no_demo:
        threading.Thread(target=seed, args=(svc, lambda m: print(m, flush=True)), daemon=True).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Oylama")
    finally:
        httpd.server_close()
        for p in svc.processes.values():
            if p.is_alive():
                p.terminate()


if __name__ == "__main__":
    main()
