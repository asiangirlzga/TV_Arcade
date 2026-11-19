#!/bin/sh
# Turns the PHP TV Arcade project into plain HTML pages for the app.  Usage: tools/prerender.sh /path/to/tvarcade
# Needs PHP 7+ on the command line. The result goes to app/src/main/assets/www and is already committed, so you only
# need this when the games change. It also writes www/versions.json, so the app knows which games it already has and
# only downloads the ones that changed on the site.
set -e
SRC="${1:-../tvarcade}"; SRC="$(cd "$SRC" && pwd)"
OUT="$(cd "$(dirname "$0")/../app/src/main/assets/www" && pwd)"
cd "$SRC"
GAMES="$(php -r 'require "_games.php"; foreach (arcade_builtin() as $g) if (is_file($g["id"] . ".php")) echo $g["id"], " ";')"
IDS="index $GAMES"
for f in "$OUT"/*.html; do [ "$(basename "$f")" = "pad.html" ] || rm -f "$f"; done
for id in $IDS; do ARCADE_APP=1 php "$id.php" > "$OUT/$id.html"; done
for f in "$OUT"/*.html; do
  [ "$(basename "$f")" = "pad.html" ] && continue
  for id in $IDS; do sed -i "s/\b$id\.php/$id.html/g" "$f"; done
done
php -r '$_GET["json"] = 1; ob_start(function ($b) { $j = json_decode($b, true); $v = array(); foreach ($j["games"] as $g) if (!empty($g["builtin"])) $v[$g["slug"]] = $g["ver"]; return json_encode($v); }); require "sync.php";' > "$OUT/versions.json"
echo "done: $(ls "$OUT" | wc -l) files, $(echo $GAMES | wc -w) games"
