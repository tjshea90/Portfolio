#!/usr/bin/env python3
"""
COWORK CHECKPOINT TOOL  —  the resume system for this project.

WHY THIS EXISTS
    Work on this app runs for hours and can be cut off at ANY moment by a usage
    limit, mid-edit, with no warning and no chance to tidy up. Everything needed
    to carry on must therefore already be on disk before the cut, not written
    after it. This tool is what keeps it there.

THE CONTRACT (a future session can rely on all of these)
    1. `RESUME.md` is regenerated from `state.json` on every save, so it can
       never disagree with the real state. Read it FIRST. It is short on purpose:
       CHECKPOINT.md is 240 KB of history and reading it cold wastes the budget
       that should go on the work.
    2. `state.json` is the machine-readable truth: every task, its status, the
       exact step in flight, the next action, and every open finding.
    3. Every save is a git commit AND a tarball. Git gives an exact diff of what
       the last session changed; the tarball is what gets handed back to the user.
    4. A watchdog commits the tree every 3 minutes if it changed, so a cut in the
       middle of an edit loses at most 3 minutes of typing, never a task.

USAGE
    ./ck start  <round> "<request summary>"     begin a round
    ./ck add    <id> "<title>"                  add a task to the ledger
    ./ck task   <id> <todo|doing|done|blocked> ["note"]
    ./ck now    "<step in flight>" ["<next action>"]
    ./ck find   <id> "<finding>" [sev]          record a bug/improvement found
    ./ck fixed  <id> ["note"]                   close a finding
    ./ck note   "<breadcrumb>"                  append to the log, no archive
    ./ck save   ["note"]                        full checkpoint (git + tar + deliver)
    ./ck status                                 print where things stand
"""
import json, os, subprocess, sys, time, datetime, shutil, pathlib

ROOT = pathlib.Path(__file__).resolve().parent
STATE = ROOT / "state.json"
RESUME = ROOT / "RESUME.md"
PROGRESS = ROOT / "PROGRESS.md"
OUT = pathlib.Path("/home/claude/checkpoints")
DELIVER = pathlib.Path("/mnt/user-data/outputs")

STATUS_MARK = {"done": "x", "doing": ">", "todo": " ", "blocked": "!"}


def now_utc():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")


def load():
    if STATE.exists():
        return json.loads(STATE.read_text())
    return {
        "schema": 1, "round": 0, "request": "", "updated": now_utc(),
        "version": {}, "now": {"step": "", "next": ""},
        "tasks": [], "findings": [], "log": [],
    }


def save_state(s):
    s["updated"] = now_utc()
    STATE.write_text(json.dumps(s, indent=2) + "\n")


def sh(cmd, cwd=ROOT, check=False):
    return subprocess.run(cmd, cwd=cwd, shell=isinstance(cmd, str),
                          capture_output=True, text=True, check=check)


# ---------------------------------------------------------------- git

def git_ready():
    if not (ROOT / ".git").exists():
        sh("git init -q")
        sh("git config user.email 'claude@local'")
        sh("git config user.name 'Claude'")
        (ROOT / ".gitignore").write_text(
            "build/\n.gradle/\n.kotlin/\n*.apk\n__pycache__/\n.ck/\nlocal.properties\n")
    # Ensure identity even on a re-cloned tree.
    sh("git config user.email 'claude@local'")
    sh("git config user.name 'Claude'")


LOCK = ROOT / ".ck" / "git.lock"


def git_commit(msg):
    """Commit under a file lock. The 3-minute watchdog commits the same repo, and
    two `git commit` runs at once leave index.lock behind and fail the second."""
    git_ready()
    LOCK.parent.mkdir(exist_ok=True)
    LOCK.touch()
    import fcntl
    with LOCK.open("w") as fh:
        fcntl.flock(fh, fcntl.LOCK_EX)
        sh("git add -A")
        r = sh(["git", "commit", "-q", "-m", msg])
        if "nothing to commit" in (r.stdout + r.stderr):
            return None
        return sh("git rev-parse --short HEAD").stdout.strip()


def git_stat_since_last():
    r = sh("git rev-list --count HEAD")
    return r.stdout.strip() or "0"


# ---------------------------------------------------------------- RESUME.md

def write_resume(s):
    t = s["tasks"]
    done = sum(1 for x in t if x["status"] == "done")
    doing = [x for x in t if x["status"] == "doing"]
    todo = [x for x in t if x["status"] == "todo"]
    blocked = [x for x in t if x["status"] == "blocked"]
    open_f = [f for f in s["findings"] if f["status"] != "fixed"]

    L = []
    A = L.append
    A(f"# RESUME — READ THIS FIRST  (round {s['round']}, saved {s['updated']})")
    A("")
    A("You are picking up a long-running Android project that was interrupted.")
    A("Everything you need is on disk. Do NOT re-read CHECKPOINT.md end to end —")
    A("it is 240 KB of round history. This file plus `state.json` is the live state;")
    A("CHECKPOINT.md sections 0-5 (lines 1-530) are the only part worth reading cold,")
    A("and only if you need the architecture.")
    A("")
    A("## 1. Bring the container back up")
    A("")
    A("```bash")
    A("cd /home/claude && tar xzf <the checkpoint tarball>   # if the tree is missing")
    A("bash /home/claude/portfolio/setup-env.sh              # Android SDK, ~2 min, once")
    A("export ANDROID_HOME=/root/android-sdk")
    A("bash /home/claude/portfolio/watchdog.sh &             # restart the 3-min autosave")
    A("./ck status                                           # where the work stopped")
    A("```")
    A("")
    A("Build traps that have cost real time before are in CHECKPOINT.md lines 22-60.")
    A("The short version: never blank `JAVA_TOOL_OPTIONS`; never run two Gradle builds")
    A("at once or kill one mid-flight; always background the build with")
    A("`setsid nohup ./gradlew ... > /home/claude/build.log 2>&1 < /dev/null & disown`.")
    A("")
    A("## 2. The request this round is answering")
    A("")
    for line in (s.get("request") or "(not recorded)").splitlines():
        A(f"> {line}")
    A("")
    A("## 3. WHERE THE WORK STOPPED")
    A("")
    A(f"- **In flight:** {s['now'].get('step') or '(nothing in flight)'}")
    A(f"- **Next action:** {s['now'].get('next') or '(pick the first unchecked task below)'}")
    if s["now"].get("files"):
        A(f"- **Files being edited:** {', '.join(s['now']['files'])}")
    A("")
    A("Uncommitted edits, if any, are shown by `git status`; every checkpoint is a")
    A("commit, so `git log --oneline` is the history of this round and")
    A("`git show HEAD` is exactly what the last save changed.")
    A("")
    A(f"## 4. Task ledger — {done}/{len(t)} done")
    A("")
    for x in t:
        A(f"- [{STATUS_MARK[x['status']]}] {x['id']}  {x['title']}"
          + (f"  — {x['note']}" if x.get("note") else ""))
    A("")
    if doing:
        A(f"**Resume at {doing[0]['id']}** ({doing[0]['title']}).")
    elif todo:
        A(f"**Resume at {todo[0]['id']}** ({todo[0]['title']}).")
    elif blocked:
        A(f"**Everything else is done; {blocked[0]['id']} is blocked** — {blocked[0].get('note','')}")
    else:
        A("**All tasks are done.** Verify, ship the APK, and checkpoint.")
    A("")
    A(f"## 5. Open findings — {len(open_f)} still open, "
      f"{len(s['findings']) - len(open_f)} fixed")
    A("")
    if not s["findings"]:
        A("(none recorded yet)")
    else:
        for f in s["findings"]:
            mark = "x" if f["status"] == "fixed" else " "
            A(f"- [{mark}] {f['id']} ({f.get('sev','med')}) {f['text']}"
              + (f"  — {f['note']}" if f.get("note") else ""))
    A("")
    A("## 6. Version")
    A("")
    v = s.get("version", {})
    if v:
        A(f"- Shipped: v{v.get('name')} (versionCode {v.get('code')})")
        A(f"- This round ships: v{v.get('next_name')} (versionCode {v.get('next_code')})")
        A("- Bump `app/build.gradle.kts` before the final APK. Android refuses an install")
        A("  whose versionCode is not higher than what is on the phone.")
    A("")
    A("## 7. Recent log")
    A("")
    for line in s["log"][-12:]:
        A(f"- {line}")
    A("")
    RESUME.write_text("\n".join(L) + "\n")


# ---------------------------------------------------------------- archive

def archive(s, note):
    OUT.mkdir(parents=True, exist_ok=True)
    n = s["round"]
    name = f"portfolio-checkpoint-{n}.tar.gz"
    dst = OUT / name
    r = sh(f"cd /home/claude && tar --exclude='.gradle' --exclude='build' "
           f"--exclude='.kotlin' --exclude='*.apk' --exclude='__pycache__' "
           f"-czf {dst} portfolio")
    if r.returncode != 0:
        print("ARCHIVE FAILED:", r.stderr[-800:])
        return None
    # Verify rather than trusting tar's exit code.
    listing = sh(f"tar -tzf {dst}").stdout.splitlines()
    need = ["portfolio/RESUME.md", "portfolio/state.json", "portfolio/CHECKPOINT.md",
            "portfolio/app/sideload.jks", "portfolio/setup-env.sh", "portfolio/ck.py"]
    missing = [w for w in need if w not in listing]
    if missing:
        print("CHECKPOINT INCOMPLETE — missing:", missing)
        return None
    kt = sum(1 for x in listing if x.endswith(".kt"))
    if kt < 30:
        print(f"CHECKPOINT INCOMPLETE — only {kt} Kotlin sources")
        return None
    try:
        DELIVER.mkdir(parents=True, exist_ok=True)
        shutil.copy2(dst, DELIVER / name)
    except Exception as e:
        print(f"(could not copy to {DELIVER}: {e})")
    return dst, kt


# ---------------------------------------------------------------- commands

def cmd_start(a):
    """Begin a round.

    A round's ledger describes THAT round. Carrying the previous round's finished
    tasks into the next one was a real trap: `./ck status` on a fresh container
    showed twelve ticked boxes from work that had already shipped, and the honest
    answer to "where did this stop" was buried under them. So starting a round
    files the old ledger into PROGRESS.md (nothing is lost - it is also in git and
    in every tarball) and resets `tasks`, `findings` and `now` to empty.

    Re-running `start` with the round number already in state.json is a no-op on
    the ledger, so a resumed session cannot wipe its own work by repeating the
    command it read in RESUME.md.
    """
    s = load()
    n = int(a[0])
    same_round = (s.get("round") == n and (s.get("tasks") or s.get("findings")))
    if s.get("round") and s["round"] != n and (s.get("tasks") or s.get("findings")):
        done = sum(1 for t in s["tasks"] if t["status"] == "done")
        fixed = sum(1 for f in s["findings"] if f["status"] == "fixed")
        with PROGRESS.open("a") as fh:
            fh.write(f"\n## Round {s['round']} ledger (closed {now_utc()})\n\n")
            fh.write(f"Request: {s.get('request','')}\n\n")
            fh.write(f"Tasks {done}/{len(s['tasks'])} done, "
                     f"findings {fixed}/{len(s['findings'])} fixed\n\n")
            for t in s["tasks"]:
                fh.write(f"- [{STATUS_MARK[t['status']]}] {t['id']}  {t['title']}"
                         + (f"  - {t['note']}" if t.get("note") else "") + "\n")
            for f in s["findings"]:
                mark = "x" if f["status"] == "fixed" else " "
                fh.write(f"- [{mark}] {f['id']} ({f.get('sev','med')}) {f['text']}"
                         + (f"  - {f['note']}" if f.get("note") else "") + "\n")
        s["tasks"] = []
        s["findings"] = []
        s["now"] = {"step": "", "next": ""}
    s["round"] = n
    if len(a) > 1:
        s["request"] = a[1]
    s["log"].append(f"{now_utc()}  round {s['round']} started"
                    + ("  (ledger kept - same round)" if same_round else ""))
    save_state(s); write_resume(s)
    print(f"round {s['round']} started"
          + (" (ledger reset)" if not same_round else " (ledger kept)"))


def cmd_add(a):
    s = load()
    if any(t["id"] == a[0] for t in s["tasks"]):
        print(f"{a[0]} already exists"); return
    s["tasks"].append({"id": a[0], "title": a[1], "status": "todo", "note": ""})
    save_state(s); write_resume(s)
    print(f"added {a[0]}")


def cmd_task(a):
    s = load()
    tid, st = a[0], a[1]
    if st not in STATUS_MARK:
        print(f"status must be one of {list(STATUS_MARK)}"); sys.exit(1)
    for t in s["tasks"]:
        if t["id"] == tid:
            t["status"] = st
            if len(a) > 2:
                t["note"] = a[2]
            s["log"].append(f"{now_utc()}  {tid} -> {st}"
                            + (f"  {a[2]}" if len(a) > 2 else ""))
            if st == "doing":
                s["now"]["step"] = f"{tid}: {t['title']}"
            elif st == "done" and s["now"].get("step", "").startswith(tid):
                s["now"]["step"] = ""
            save_state(s)
            do_save(s, f"{tid} {st}")
            return
    print(f"no task {tid}"); sys.exit(1)


def cmd_now(a):
    s = load()
    s["now"]["step"] = a[0]
    if len(a) > 1:
        s["now"]["next"] = a[1]
    save_state(s); write_resume(s)
    git_commit(f"wip: {a[0][:70]}")
    print("now:", a[0])


def cmd_find(a):
    s = load()
    fid = a[0]
    if any(f["id"] == fid for f in s["findings"]):
        print(f"{fid} already recorded"); return
    s["findings"].append({"id": fid, "text": a[1],
                          "sev": a[2] if len(a) > 2 else "med",
                          "status": "open", "note": ""})
    s["log"].append(f"{now_utc()}  finding {fid}: {a[1][:80]}")
    save_state(s); write_resume(s)
    print(f"recorded {fid}")


def cmd_fixed(a):
    s = load()
    for f in s["findings"]:
        if f["id"] == a[0]:
            f["status"] = "fixed"
            if len(a) > 1:
                f["note"] = a[1]
            s["log"].append(f"{now_utc()}  {a[0]} fixed"
                            + (f": {a[1]}" if len(a) > 1 else ""))
            save_state(s)
            do_save(s, f"fix {a[0]}")
            return
    print(f"no finding {a[0]}"); sys.exit(1)


def cmd_note(a):
    s = load()
    s["log"].append(f"{now_utc()}  {a[0]}")
    save_state(s); write_resume(s)
    git_commit(f"note: {a[0][:70]}")
    print("noted")


def do_save(s, note):
    write_resume(s)
    sha = git_commit(f"ckpt {s['round']}: {note}")
    res = archive(s, note)
    line = (f"{now_utc()}  checkpoint {s['round']}  "
            f"{'commit ' + sha if sha else 'no-change'}  {note}")
    with PROGRESS.open("a") as fh:
        fh.write(line + "\n")
    if res:
        dst, kt = res
        size = dst.stat().st_size // 1024
        print(f"saved: {dst} ({size} KB, {kt} sources)"
              f"{', commit ' + sha if sha else ''}")
    else:
        print("SAVE FAILED — archive did not verify. Fix before continuing.")
        sys.exit(1)


def cmd_save(a):
    s = load()
    do_save(s, a[0] if a else "manual save")


def cmd_status(a):
    s = load()
    print(f"round {s['round']}  updated {s['updated']}")
    print(f"in flight : {s['now'].get('step') or '(nothing)'}")
    print(f"next      : {s['now'].get('next') or '(first unchecked task)'}")
    print()
    for t in s["tasks"]:
        print(f"  [{STATUS_MARK[t['status']]}] {t['id']}  {t['title']}"
              + (f"  — {t['note']}" if t.get("note") else ""))
    open_f = [f for f in s["findings"] if f["status"] != "fixed"]
    print(f"\nfindings: {len(open_f)} open / {len(s['findings'])} total")
    for f in open_f:
        print(f"  [ ] {f['id']} ({f.get('sev')}) {f['text']}")
    r = sh("git status --porcelain")
    if r.stdout.strip():
        print("\nuncommitted changes:")
        print("\n".join("  " + l for l in r.stdout.strip().splitlines()[:20]))


CMDS = {"start": cmd_start, "add": cmd_add, "task": cmd_task, "now": cmd_now,
        "find": cmd_find, "fixed": cmd_fixed, "note": cmd_note,
        "save": cmd_save, "status": cmd_status}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in CMDS:
        print(__doc__); sys.exit(0 if len(sys.argv) < 2 else 1)
    CMDS[sys.argv[1]](sys.argv[2:])
