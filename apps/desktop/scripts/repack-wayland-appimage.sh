#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
tauri_dir="$(cd -- "$script_dir/../src-tauri" && pwd)"
bundle_dir="$tauri_dir/target/release/bundle/appimage"
app_dir="$bundle_dir/Murmur.AppDir"
gtk_hook="$app_dir/apprun-hooks/linuxdeploy-plugin-gtk.sh"

# appimagetool looks up the desktop entry's icon name at the AppDir root
if [[ -f "$app_dir/Murmur.png" && ! -e "$app_dir/murmur.png" ]]; then
  ln -s Murmur.png "$app_dir/murmur.png"
fi

if [[ ! -f "$gtk_hook" ]]; then
  echo "Missing AppImage GTK hook: $gtk_hook" >&2
  exit 1
fi

# linuxdeploy's GTK hook forces XWayland even when the app can run on Wayland
if grep -q '^export GDK_BACKEND=x11' "$gtk_hook"; then
  sed -i '/^export GDK_BACKEND=x11 /d' "$gtk_hook"
fi
if grep -q '^export GDK_BACKEND=' "$gtk_hook"; then
  echo "The AppImage GTK hook still forces a display backend" >&2
  exit 1
fi

transcribe_lib="$(find "$app_dir/usr/lib" -maxdepth 1 -type f -name 'libtranscribe.so*' -print -quit)"
if [[ -z "$transcribe_lib" ]]; then
  echo "Missing bundled transcribe library" >&2
  exit 1
fi
if readelf -Ws "$transcribe_lib" | grep -E '[[:space:]]UND[[:space:]]+cblas_' >/dev/null; then
  echo "Bundled transcribe library still requires an unlinked CBLAS runtime" >&2
  exit 1
fi

plugin_path="${MURMUR_APPIMAGE_PLUGIN:-${XDG_CACHE_HOME:-${HOME}/.cache}/tauri/linuxdeploy-plugin-appimage.AppImage}"
if [[ ! -x "$plugin_path" ]]; then
  echo "Missing Tauri AppImage plugin: $plugin_path" >&2
  exit 1
fi

version="$(node -e 'const fs = require("node:fs"); process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).version)' "$tauri_dir/tauri.conf.json")"
case "$(uname -m)" in
  x86_64) architecture=amd64 ;;
  aarch64) architecture=aarch64 ;;
  *)
    echo "Unsupported AppImage architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

output="$bundle_dir/Murmur_${version}_wayland_${architecture}.AppImage"
temporary_image="$bundle_dir/.Murmur_${version}_wayland_${architecture}_${BASHPID}.AppImage"
trap 'rm -f "$temporary_image"' EXIT
LDAI_OUTPUT="$temporary_image" APPIMAGE_EXTRACT_AND_RUN=1 "$plugin_path" "--appdir=$app_dir"
if [[ ! -s "$temporary_image" ]]; then
  echo "AppImage plugin did not create an output file" >&2
  exit 1
fi
mv -f -- "$temporary_image" "$output"
trap - EXIT
sha256sum "$output"
