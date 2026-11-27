"""Runs `apply` of scripts/import-money-manager.py against the app WITHOUT a network: every API call is written to stdout as
"@@REQ METHOD /path BODY-JSON" and the answer is read from stdin as "STATUS BODY-JSON" (the Java test, ImportMoneyManagerTest, performs
the request against the real Spring application). Everything else the script prints is passed through unchanged.

    python3 import_bridge.py PATH-TO-import-money-manager.py REVIEW-DIRECTORY
"""
import argparse
import importlib.util
import json
import os
import sys

script, review = sys.argv[1], sys.argv[2]
spec = importlib.util.spec_from_file_location("import_money_manager", script)
importer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(importer)


def bridge(self, method, path, body=None):
    sys.stdout.write("@@REQ %s %s %s\n" % (method, path, json.dumps(body) if body is not None else ""))
    sys.stdout.flush()
    status, _, reply = sys.stdin.readline().rstrip("\n").partition(" ")
    if int(status) >= 400:
        raise RuntimeError("%s %s -> %s: %s" % (method, path, status, reply[:300]))
    return json.loads(reply) if reply else None


importer.Api.call = bridge
importer.apply(argparse.Namespace(out=review, url="http://bridge", clear_starter_categories=os.environ.get("CLEAR_STARTER") == "1"))
