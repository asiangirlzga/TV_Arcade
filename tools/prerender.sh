#!/bin/sh
# Turns the PHP TV Arcade project into plain HTML pages for the app.  Usage: tools/prerender.sh /path/to/tvarcade
# Needs PHP 7+ on the command line. The result goes to app/src/main/assets/www and is already committed, so you only
# need this when the games change.
set -e
SRC="${1:-../tvarcade}"; SRC="$(cd "$SRC" && pwd)"
OUT="$(cd "$(dirname "$0")/../app/src/main/assets/www" && pwd)"
IDS="index blockhero snake breakout blaster hop g2048 train drive racer3d subway blockcraft"
cd "$SRC"
for id in $IDS; do ARCADE_APP=1 php "$id.php" > "$OUT/$id.html"; done
for f in "$OUT"/*.html; do
  [ "$(basename "$f")" = "pad.html" ] && continue
  for id in $IDS; do sed -i "s/\b$id\.php/$id.html/g" "$f"; done
done
echo "done: $(ls "$OUT" | wc -l) pages"
