#!/usr/bin/env python3
"""Trims the reflection metadata that the GraalVM tracing agent recorded (scripts/native-experiment.sh --agent).

The agent records everything the app touched by reflection, but Spring's own native-image support already declares what Spring, Logback,
Micrometer and Kotlin need, and ByteBuddy cannot work in a native image at all (it makes classes at run time; Hibernate 7.4 picks its
byte-code provider only through the Java service lookup, so the ByteBuddy provider must not be visible). Declaring them again only makes
the image bigger and the build heavier: on GitHub's small macOS runner (7 GB) the build ran out of memory with them in.

usage: trim-native-metadata.py reachability-metadata.json
"""
import json
import sys

DROP_TYPE_PREFIXES = (
    "org.springframework.",
    "net.bytebuddy.",
    "ch.qos.logback.",
    "io.micrometer.",
    "kotlin.", "kotlinx.",
    "org.hibernate.bytecode.internal.bytebuddy.",
)
DROP_RESOURCES = ("org.hibernate.bytecode.spi.BytecodeProvider",)


def type_name(entry):
    t = entry.get("type")
    return t if isinstance(t, str) else ""


def drop_reflection(entry):
    name = type_name(entry)
    if name.startswith(DROP_TYPE_PREFIXES):
        return True
    t = entry.get("type")
    if isinstance(t, dict) and "proxy" in t:      # a JDK proxy: dropped when every interface is one that is dropped above
        interfaces = t["proxy"]
        return bool(interfaces) and all(i.startswith(DROP_TYPE_PREFIXES) for i in interfaces)
    return False


def main(path):
    d = json.load(open(path))
    before = (len(d.get("reflection", [])), len(d.get("resources", [])))
    d["reflection"] = [r for r in d.get("reflection", []) if not drop_reflection(r)]
    d["resources"] = [r for r in d.get("resources", []) if not any(x in json.dumps(r) for x in DROP_RESOURCES)]
    json.dump(d, open(path, "w"), indent=2)
    print(f"reflection {before[0]} -> {len(d['reflection'])}, resources {before[1]} -> {len(d['resources'])}")


if __name__ == "__main__":
    main(sys.argv[1])
