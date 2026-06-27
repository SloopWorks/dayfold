#!/usr/bin/env bash
# One command to populate a test account end-to-end: the example hub, then the feed
# cards (one deep-links into the hub). Run `dayfold login` first.
set -euo pipefail
cd "$(dirname "$0")"
bash hub-college/push.sh
bash feed/push.sh
echo
echo "Done. Open the app → Hubs ('Sample → Starting College') + Now (tap Financial Aid → opens the hub)."
