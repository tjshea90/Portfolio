#!/usr/bin/env python3
"""emit.py — turn collected hook text into EXACTLY ONE JSON object.

WHY THIS EXISTS
---------------
Hook stdout is parsed as a single JSON document. The previous hook config
looped over every repo in the container and let each repo's script print its
own JSON, so with two repos the stream was:

    {"hookSpecificOutput": ...}{"hookSpecificOutput": ...}

which is not JSON at all. Confirmed by test: "Extra data: line 2 column 1".
With a second repo present the entire session briefing was silently lost —
the worst possible failure for a system whose only job is to brief a cold
session. So collection is now separated from emission, and emission happens
once, here.

    ... | python3 emit.py session-start
    ... | python3 emit.py system-message
"""
import json, sys

mode = sys.argv[1] if len(sys.argv) > 1 else "session-start"
text = sys.stdin.read().strip()
if not text:
    sys.exit(0)

if mode == "session-start":
    out = {"hookSpecificOutput": {"hookEventName": "SessionStart",
                                  "additionalContext": text}}
else:
    out = {"systemMessage": text}

json.dump(out, sys.stdout)
sys.stdout.write("\n")
