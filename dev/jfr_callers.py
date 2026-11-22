#!/usr/bin/env python3
# The share of a profile's execution samples whose top frame is a map, hash or equals frame,
# attributed to the jegg frame under it (#138): which code pays for the hashcons. Reads the
# text of `jfr print --events jdk.ExecutionSample --stack-depth 12 <file>.jfr` on stdin or
# from the files named; prints, per file, the share of all samples that sit in the hashcons
# (a map frame on top, one of EGraph's hashcons readers in the stack) and the first jegg
# frame under the top for the largest callers.
#
#   jfr print --events jdk.ExecutionSample --stack-depth 12 prop.jfr > prop.txt
#   dev/jfr_callers.py prop.txt math.txt
import collections
import re
import sys

TOP = ("java.util.HashMap.", "io.github.vecbricks.jegg.Hashcons.",
       "jdk.internal.util.ArraysSupport.hashCode", "jdk.internal.util.ArraysSupport.mismatch",
       "java.util.Objects.equals", "io.github.vecbricks.jegg.IntList.equals",
       "io.github.vecbricks.jegg.IntList.hashCode")
HASHCONS = ("EGraph.idOf", "EGraph.lookupLive", "EGraph.repair", "EGraph.add",
            "EGraph.retainNodes")
PREFIX = "io.github.vecbricks.jegg."


def caller(frames):
    for f in frames:
        if f.startswith(PREFIX) and not f.startswith(PREFIX + "IntList") \
                and not f.startswith(PREFIX + "Hashcons"):
            return f[len(PREFIX):]
    return "?"


def report(name, text):
    total = 0
    hashcons = 0
    callers = collections.Counter()
    for sample in text.split("jdk.ExecutionSample"):
        frames = re.findall(r"^\s+([\w.$<>]+)\(", sample, flags=re.M)
        if not frames:
            continue
        total += 1
        if any(frames[0].startswith(t) for t in TOP) \
                and any(f.endswith(HASHCONS) for f in frames[:8]):
            hashcons += 1
            callers[caller(frames)] += 1
    print(f"{name}: {total} samples, {100 * hashcons / total:.1f}% in the hashcons; callers: "
          + ", ".join(f"{c} {100 * n / total:.1f}%" for c, n in callers.most_common(6)))


if len(sys.argv) > 1:
    for path in sys.argv[1:]:
        with open(path) as f:
            report(path, f.read())
else:
    report("stdin", sys.stdin.read())
