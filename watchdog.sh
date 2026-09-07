#!/bin/bash
# THE 3-MINUTE AUTOSAVE.
#
# A usage limit can cut a session off mid-edit. `ck save` runs at task boundaries,
# but between two boundaries there can be twenty minutes of typing with nothing on
# record. This loop commits the tree every 3 minutes whenever it changed, so the
# most a cut can cost is three minutes of work — never a task, and never a finding.
#
#   bash watchdog.sh &          start it (do this on every cold resume)
#   touch /home/claude/portfolio/.ck/stop     stop it
#
# It shares .ck/git.lock with ck.py, so an autosave and a real checkpoint can never
# run `git commit` at the same time and leave index.lock behind.
P=/home/claude/portfolio
mkdir -p "$P/.ck"
rm -f "$P/.ck/stop"
echo $$ > "$P/.ck/watchdog.pid"
cd "$P" || exit 1
while true; do
  sleep 180
  [ -f "$P/.ck/stop" ] && { echo "watchdog stopped"; exit 0; }
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    (
      flock 9
      git add -A
      git commit -q -m "autosave $(date -u '+%Y-%m-%d %H:%M UTC')" 2>/dev/null \
        && echo "$(date -u '+%H:%M UTC') autosave" >> "$P/.ck/watchdog.log"
    ) 9>"$P/.ck/git.lock"
  fi
done
