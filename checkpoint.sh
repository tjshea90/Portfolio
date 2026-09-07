#!/bin/bash
# Regenerates the resumable checkpoint archive.
#
#   bash checkpoint.sh 50 "what just changed"   -> portfolio-checkpoint-50.tar.gz
#   bash checkpoint.sh                          -> reads the number out of CHECKPOINT.md's title
#
# Run this after EVERY meaningful change, not just at the end of a session. The archive is
# the only thing that survives a session being cut off mid-work: uploading it to a new chat
# with "extract this and continue from CHECKPOINT.md" restores the project completely.
#
# ROUND 50 ADDITION: every run also appends a line to PROGRESS.md and copies the archive to
# /mnt/user-data/outputs/ so the newest checkpoint is always one tap away in the chat, even
# if the session dies mid-sentence.
set -e
P=/home/claude/portfolio
OUT=/home/claude/checkpoints
DELIVER=/mnt/user-data/outputs
mkdir -p "$OUT" "$DELIVER"

N="$1"
NOTE="$2"
if [ -z "$N" ]; then
  N=$(grep -m1 -oE 'CHECKPOINT [0-9]+' "$P/CHECKPOINT.md" | grep -oE '[0-9]+')
fi
[ -n "$N" ] || { echo "could not work out a checkpoint number; pass one"; exit 1; }

cd /home/claude
tar --exclude='.gradle' --exclude='build' --exclude='.kotlin' --exclude='*.apk' \
    --exclude='__pycache__' \
    -czf "$OUT/portfolio-checkpoint-$N.tar.gz" portfolio

# Prove the archive is readable and carries the things a cold start cannot do without,
# rather than trusting that tar exited 0.
for want in portfolio/CHECKPOINT.md portfolio/app/sideload.jks portfolio/setup-env.sh \
            portfolio/PROGRESS.md portfolio/checkpoint.sh; do
  tar -tzf "$OUT/portfolio-checkpoint-$N.tar.gz" | grep -qx "$want" \
    || { echo "CHECKPOINT INCOMPLETE - $want is missing"; exit 1; }
done
SRC=$(tar -tzf "$OUT/portfolio-checkpoint-$N.tar.gz" | grep -c '\.kt$')
[ "$SRC" -ge 30 ] || { echo "CHECKPOINT INCOMPLETE - only $SRC Kotlin sources"; exit 1; }

# The delivered copy always has the same predictable name, so a new chat is told one thing.
cp "$OUT/portfolio-checkpoint-$N.tar.gz" "$DELIVER/portfolio-checkpoint-$N.tar.gz"

echo "$(date -u '+%Y-%m-%d %H:%M UTC')  checkpoint $N  ($SRC sources)  ${NOTE:-}" \
  >> "$P/PROGRESS.md"

echo "verified: $SRC Kotlin sources, keystore, CHECKPOINT.md and PROGRESS.md present"
ls -lh "$OUT/portfolio-checkpoint-$N.tar.gz"
