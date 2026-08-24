#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${RUNNER_TEMP:-/tmp}/barka-c6-native"
JNI="$ROOT/app/src/main/jniLibs"
rm -rf "$TMP" "$JNI"
mkdir -p "$TMP" "$JNI/arm64-v8a" "$JNI/armeabi-v7a"

# Xray/VLESS - version épinglée pour un build reproductible.
XRAY_TAG="v26.7.28"
# Les releases Android officielles Xray publient arm64-v8a, mais pas arm32-v7a.
# C6 embarque donc Xray/VLESS pour arm64-v8a uniquement au lieu d'appeler
# un asset inexistant qui provoque un HTTP 404 dans GitHub Actions.
abi="arm64-v8a"
asset="Xray-android-arm64-v8a.zip"
url="https://github.com/XTLS/Xray-core/releases/download/${XRAY_TAG}/${asset}"
curl -fL --retry 3 "$url" -o "$TMP/$asset"
mkdir -p "$TMP/xray-$abi"
unzip -q "$TMP/$asset" -d "$TMP/xray-$abi"
xray_bin="$(find "$TMP/xray-$abi" -type f -name xray | head -1)"
test -n "$xray_bin"
cp "$xray_bin" "$JNI/$abi/libbarka_xray.so"
chmod 0755 "$JNI/$abi/libbarka_xray.so"

# DNSTT client - compilé depuis la source officielle pour Android.
git clone https://www.bamsoftware.com/git/dnstt.git "$TMP/dnstt"
(
  cd "$TMP/dnstt"
  GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -o "$JNI/arm64-v8a/libbarka_dnstt.so" ./dnstt-client
)
chmod 0755 "$JNI/arm64-v8a/libbarka_dnstt.so"

# BadVPN tun2socks + UDPGW JNI. Le wrapper Java est copié depuis la même source,
# ce qui garantit la correspondance des symboles JNI.
git clone --depth 1 https://github.com/LondonX/tun2socks-android.git "$TMP/tun2socks"
(
  cd "$TMP/tun2socks"
  chmod +x gradlew
  ./gradlew tun2socks:assembleRelease --no-daemon
)
cp "$TMP/tun2socks/tun2socks/src/main/java/com/LondonX/tun2socks/Tun2Socks.java" \
   "$ROOT/app/src/main/java/com/LondonX/tun2socks/Tun2Socks.java"

for abi in arm64-v8a armeabi-v7a; do
  so="$(find "$TMP/tun2socks/tun2socks/build" -type f -path "*/$abi/libtun2socks.so" | head -1)"
  test -n "$so" || { echo "libtun2socks.so absent pour $abi"; exit 1; }
  cp "$so" "$JNI/$abi/libtun2socks.so"
done

echo "===== C6 native cores ====="
find "$JNI" -type f -maxdepth 2 -print -exec file {} \;
