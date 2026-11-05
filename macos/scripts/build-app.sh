#!/bin/bash
# Собирает «Pods Companion.app» и PodsCompanion-mac.dmg в macos/out (нужен Mac с Xcode или Command Line Tools).
# Подпись — ad-hoc («-»): без платного аккаунта Apple Developer. Поэтому при первом запуске macOS
# спросит разрешение: Системные настройки → Конфиденциальность и безопасность → «Всё равно открыть».
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(cat VERSION)
BUILD=${GITHUB_RUN_NUMBER:-1}
ARCHS=(--arch arm64 --arch x86_64)

swift build -c release "${ARCHS[@]}"
BIN="$(swift build -c release "${ARCHS[@]}" --show-bin-path)/PodsCompanionMac"

APP="out/Pods Companion.app"
rm -rf out
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/PodsCompanion"
sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD/" Resources/Info.plist > "$APP/Contents/Info.plist"
cp -R Resources/*.lproj "$APP/Contents/Resources/"

# Значок приложения из той же картинки, что у Android-приложения.
ICONSET=out/AppIcon.iconset
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
  sips -z $size $size Resources/AppIcon.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  [ $double -le 512 ] && sips -z $double $double Resources/AppIcon.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
cp Resources/AppIcon.png "$ICONSET/icon_512x512@2x.png"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$ICONSET"

codesign --force --sign - --identifier dev.podscompanion.mac "$APP"
codesign --verify --verbose "$APP"

# Образ диска: окно с приложением и ярлыком «Программы» — перетащить одно на другое.
STAGE=out/dmg
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil create -volname "Pods Companion" -srcfolder "$STAGE" -ov -format UDZO out/PodsCompanion-mac.dmg
rm -rf "$STAGE"
echo "Готово: macos/out/PodsCompanion-mac.dmg"
