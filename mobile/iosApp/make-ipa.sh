#!/usr/bin/env bash
# Makes SalesApp.ipa for installing on iPhones with SideStore (no paid Apple account needed).
# The file is not signed here: SideStore signs it with the manager's own free Apple ID.
#   cd mobile/iosApp && bash make-ipa.sh      -> ~/Desktop/SalesApp.ipa
set -euo pipefail
cd "$(dirname "$0")"

host=$(sed -n 's/^[[:space:]]*SALES_API_HOST[[:space:]]*=[[:space:]]*//p' Configuration/Local.xcconfig 2>/dev/null | tail -1 | tr -d '[:space:]')
if [[ -z "$host" || "$host" == *"://"* || "$host" == */ ]]; then
  echo "Set SALES_API_HOST = your.live.server (no https://, no slash) in mobile/iosApp/Configuration/Local.xcconfig first." >&2
  exit 1
fi

build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Release -sdk iphoneos \
  SYMROOT="$build/products" OBJROOT="$build/obj" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build

app=$(find "$build/products/Release-iphoneos" -maxdepth 1 -name '*.app' | head -1)
[[ -n "$app" ]] || { echo "The build made no .app file." >&2; exit 1; }
mkdir -p "$build/Payload"
cp -R "$app" "$build/Payload/"
out="$HOME/Desktop/SalesApp.ipa"
rm -f "$out"
(cd "$build" && zip -qry "$out" Payload)
echo "Made $out for $host. AirDrop it to the iPhone and add it in SideStore (My Apps, +)."
