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

# BadVPN tun2socks + UDPGW JNI. Le projet amont utilise un ancien couple
# Gradle/AGP qui échoue à la configuration sur le runner GitHub actuel.
# On compile donc uniquement le module JNI CMake, sans passer par son Gradle.
git clone --depth 1 https://github.com/LondonX/tun2socks-android.git "$TMP/tun2socks"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$ANDROID_SDK" || { echo "Android SDK introuvable"; exit 1; }
NDK_VERSION="25.2.9519653"
CMAKE_VERSION="3.22.1"
SDKMANAGER="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager"
test -x "$SDKMANAGER" || SDKMANAGER="$(command -v sdkmanager || true)"
test -n "$SDKMANAGER" || { echo "sdkmanager introuvable"; exit 1; }
"$SDKMANAGER" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION" >/dev/null
NDK="$ANDROID_SDK/ndk/$NDK_VERSION"
CMAKE="$ANDROID_SDK/cmake/$CMAKE_VERSION/bin/cmake"
test -f "$NDK/build/cmake/android.toolchain.cmake" || { echo "NDK Android introuvable"; exit 1; }
test -x "$CMAKE" || { echo "CMake Android introuvable"; exit 1; }

TUN_CPP="$TMP/tun2socks/tun2socks/src/main/cpp"
for abi in arm64-v8a armeabi-v7a; do
  build_dir="$TMP/tun2socks-cmake-$abi"
  "$CMAKE" -S "$TUN_CPP" -B "$build_dir" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release
  "$CMAKE" --build "$build_dir" --config Release --parallel
  so="$(find "$build_dir" -type f -name libtun2socks.so | head -1)"
  test -n "$so" || { echo "libtun2socks.so absent pour $abi"; exit 1; }
  cp "$so" "$JNI/$abi/libtun2socks.so"
done

cp "$TMP/tun2socks/tun2socks/src/main/java/com/LondonX/tun2socks/Tun2Socks.java" \
   "$ROOT/app/src/main/java/com/LondonX/tun2socks/Tun2Socks.java"

echo "===== C6 native cores ====="
find "$JNI" -type f -maxdepth 2 -print -exec file {} \;
