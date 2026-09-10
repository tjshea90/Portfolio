#!/usr/bin/env python3
"""
Fails the build if PortfolioViewModel declares ANY property below its `init` block.

WHY. Kotlin runs property initialisers and init blocks in source order, so a property
declared after `init` does not exist yet while `init` runs - a `val` holding an object
reads as null. This file has been bitten twice:

  v4.5  the ledger cache fields sat below init. init -> recompute() filled them -> the
        initialisers then wiped every one. It self-healed on the next refresh, so it
        survived testing, but it left the app able to draw an empty portfolio over good data.

  v4.6  _dataMissing sat below init. recompute() writes to it and init calls recompute(),
        so the first thing the app did on launch was dereference null: crash on startup,
        every launch, with no way into the app.

An earlier version of this script tried to work out which properties init could actually
REACH. It produced a false positive on the first run, and a check that cries wolf gets
ignored - which is exactly how the second bug shipped. So the rule is now absolute and
needs no analysis to be correct: in this class, every property is declared above init.
The cost is trivial; the failure mode it prevents is an app that will not start.

Run: python3 tools/checkinit.py   (exit 1 on a violation)
"""
import re
import sys
import pathlib

VM = pathlib.Path(__file__).parent.parent / (
    "app/src/main/java/com/tj/portfolio/ui/PortfolioViewModel.kt"
)

PROP = re.compile(r"^    (?:private\s+|internal\s+)?(?:va[lr])\s+(\w+)")
INIT = re.compile(r"^    init\s*\{")


def main() -> int:
    if not VM.exists():
        print(f"checkinit: FAIL - {VM} not found")
        return 1
    lines = VM.read_text(encoding="utf-8").splitlines()

    init_line = next((i for i, l in enumerate(lines) if INIT.match(l)), None)
    if init_line is None:
        print("checkinit: FAIL - no init block found; has the class been restructured?")
        return 1

    end = next(
        (i for i in range(init_line + 1, len(lines)) if lines[i].startswith("    }")),
        len(lines),
    )
    bad = [
        (i + 1, PROP.match(lines[i]).group(1))
        for i in range(end, len(lines))
        if PROP.match(lines[i])
    ]

    if bad:
        print("checkinit: FAIL - properties declared BELOW init:\n")
        for ln, name in bad:
            print(f"    line {ln}: {name}")
        print(
            "\nMove them above the init block. A property declared after init does not\n"
            "exist yet while init runs - this is what crashed v4.6 on startup."
        )
        return 1

    print(f"checkinit: ok - every property is declared above init (line {init_line + 1})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
