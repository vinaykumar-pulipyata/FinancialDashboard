"""Runs scripts/load-demo-data.py against the app WITHOUT a network: every API call the script makes is written to
stdout as "@@REQ METHOD /path BODY-JSON" and the answer is read from stdin as "STATUS BODY-JSON". The Java test
(DemoDataTest) sits on the other end and performs the request against the real Spring application. Everything else the
script prints is passed through unchanged.

    python3 demo_bridge.py PATH-TO-load-demo-data.py YEARS
"""
import importlib.util
import json
import sys

script, years = sys.argv[1], int(sys.argv[2])
spec = importlib.util.spec_from_file_location("load_demo_data", script)
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


def bridge(method, path, body=None):
    sys.stdout.write("@@REQ %s %s %s\n" % (method, path, json.dumps(body) if body is not None else ""))
    sys.stdout.flush()
    status, _, reply = sys.stdin.readline().rstrip("\n").partition(" ")
    if int(status) >= 400:
        raise demo.Skip("%s %s failed: %s" % (method, path, reply))
    return json.loads(reply) if reply else None


demo.call = bridge
sys.exit(0 if demo.run(years) else 3)
